package com.openminis.app.plugins

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [T-dev-suite] Recipe engine for dev-suite components.
 *
 * Drives the declarative [DevComponent] recipes inside the guest sandbox
 * through [ExecutionCoordinator]:
 *
 *  1. apt install (when [DevComponent.aptPackages] is non-empty) — inside the
 *     shared guest apt lock, with an `apt-get update` first
 *  2. postInstallSteps (setup scripts shipped via assets/default_mount)
 *  3. checkCommand probe — exit 0 marks the component INSTALLED
 *
 * Concurrency: one install at a time (installMutex) — apt holds a global
 * lock in the guest anyway, so parallel installs would only queue behind
 * it while burning shell slots. Progress lines flow to [DevSuiteStore],
 * which throttles what actually reaches disk; the card reads
 * [DevSuiteStore.states].
 */
object DevSuiteInstaller {

    /** How long the installer waits for the guest-wide apt lock (seconds). */
    private const val APT_LOCK_WAIT_SEC = 1800

    /** Guest drop directory for offline payloads (see offline.sh). */
    private const val OFFLINE_DIR = "/var/minis/shared/offline"
    private const val CACHE_DIR = "/var/minis/shared/cache/devsuite"

    /**
     * Extra room the assembly needs on top of the component estimates: the
     * resumable `.part` cache under [CACHE_DIR] holds the largest payload
     * while it is being fetched (and, until it verifies, whatever half-built
     * download it is holding), plus unpack scratch space.
     */
    private const val RESERVE_CAP_BYTES = 2_500_000_000L
    private const val GB = 1_000_000_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Run the readiness probe in the guest. Exit 0 = ready. */
    suspend fun probe(component: DevComponent): Boolean {
        return probeResult(component).exitCode == 0
    }

    // [T-devsuite-qemu-probe] Matches each `command -v <name>` clause so the
    // probe can be expanded to the package's ACTUAL artifact names. Only
    // command-v probes expand; test -x / file-glob probes run verbatim.
    private val PROBE_COMMAND = Regex("""command -v ([A-Za-z0-9._+-]+)""")

    /**
     * [T-devsuite-qemu-probe] Quote every `command -v` clause so tool names
     * with special characters can't break the guest shell, preserving the
     * operators and every non-command-v probe (test -x / file globs)
     * verbatim. The bare-vs-`-static` naming duality is owned by the
     * catalog's checkCommand itself (it lists both names where a package
     * ships either, e.g. qemu-x86_64 vs qemu-x86_64-static) — this pass
     * must NOT inject candidate alternates, or a compound probe grows
     * duplicate clauses and the operators between them are guessed wrong.
     * Pure so the expansion is unit-testable without a shell.
     */
    internal fun probeCommand(component: DevComponent): String =
        PROBE_COMMAND.replace(component.checkCommand) { m ->
            "command -v '${m.groupValues[1]}'"
        }

    private suspend fun probeResult(component: DevComponent): DevSuiteCommandResult =
        DevSuiteCommandRunner.run(probeCommand(component), timeoutMs = 30_000L) {}

    /**
     * Install one component (apt → postInstall → probe). Returns true when
     * the probe passes. Safe to call for an already-ready component — the
     * probe short-circuits without touching apt.
     *
     * [probeResult] lets a caller that already ran the probe (the disk gate
     * in [assembleBundleAsync]) reuse that answer instead of paying a second
     * guest round trip — for the ready AND the not-ready case.
     */
    suspend fun install(
        context: Context,
        component: DevComponent,
        probeResult: Boolean? = null,
    ): Boolean = installMutex.withLock {
        _busy.value = true
        val started = context.getString(R.string.dev_suite_start_install)
        try {
            DevSuiteStore.update(context, component.id, DevSuiteStore.Status.INSTALLING, started)
            // Fast path: probe first — idempotent recipes make reinstall a no-op,
            // but skipping apt entirely saves minutes on rebuilds.
            if (probeResult ?: probe(component)) {
                DevSuiteStore.update(
                    context, component.id, DevSuiteStore.Status.INSTALLED,
                    context.getString(R.string.dev_suite_ready),
                )
                return true
            }

            if (component.aptPackages.isNotEmpty()) {
                val aptResult = DevSuiteCommandRunner.run(aptCommand(component)) { line ->
                    DevSuiteStore.update(
                        context, component.id, DevSuiteStore.Status.INSTALLING, line.take(120)
                    )
                }
                if (aptResult.exitCode != 0) {
                    DevSuiteStore.update(
                        context, component.id, DevSuiteStore.Status.FAILED,
                        DevSuiteInstallDiagnostics.text(
                            DevSuiteInstallError(
                                component.id,
                                DevSuiteInstallError.Stage.APT,
                                aptResult.exitCode,
                                aptCommand(component),
                                aptResult.outputTail,
                            ),
                        ),
                    )
                    return false
                }
            }

            for (step in component.postInstallSteps) {
                val result = DevSuiteCommandRunner.run(step) { line ->
                    DevSuiteStore.update(
                        context, component.id, DevSuiteStore.Status.INSTALLING, line.take(120)
                    )
                }
                if (result.exitCode != 0) {
                    DevSuiteStore.update(
                        context, component.id, DevSuiteStore.Status.FAILED,
                        DevSuiteInstallDiagnostics.text(
                            DevSuiteInstallError(
                                component.id,
                                DevSuiteInstallError.Stage.POST_INSTALL,
                                result.exitCode,
                                step,
                                result.outputTail,
                            ),
                        ),
                    )
                    return false
                }
            }

            val probe = probeResult(component)
            if (probe.exitCode == 0) {
                DevSuiteStore.update(
                    context, component.id, DevSuiteStore.Status.INSTALLED,
                    context.getString(R.string.dev_suite_ready),
                )
                true
            } else {
                DevSuiteStore.update(
                    context, component.id, DevSuiteStore.Status.FAILED,
                    // [T-devsuite-probe-selfdescribe] The failure line must
                    // carry the probe command: "安装完成但探针未通过" alone
                    // gave the user nothing to self-diagnose (the qemu case
                    // looked like a broken install while the probe was simply
                    // looking for a name the package does not ship).
                    DevSuiteInstallDiagnostics.text(
                        DevSuiteInstallError(
                            component.id,
                            DevSuiteInstallError.Stage.PROBE,
                            probe.exitCode,
                            probeCommand(component),
                            probe.outputTail,
                        ),
                    ),
                )
                false
            }
        } finally {
            _busy.value = false
        }
    }

    /**
     * [T-devsuite-apt-lock] Every guest script that touches apt takes the
     * shared lock first (minis-setup-flutter, minis-setup-llama-cpp,
     * minis-setup-qemu, …). The installer used to run a bare `apt-get
     * install` through the coordinator, which contended the dpkg lock with
     * those very installs and returned a spurious `exit 100`, and it never
     * ran `apt-get update`, so a fresh rootfs with empty/stale lists failed
     * package resolution. Same protocol as the scripts: source the helper,
     * acquire, `update`, install, release.
     *
     * Runs in a subshell so the EXIT trap fires when THIS command finishes —
     * the persistent guest shell outlives the command, so a trap set at top
     * level would keep the lock until the shell died.
     */
    internal fun aptCommand(component: DevComponent): String {
        val pkgs = component.aptPackages.joinToString(" ")
        return "( . /usr/local/lib/minis/apt-lock.sh; " +
            "if minis_acquire_apt_lock $APT_LOCK_WAIT_SEC; then " +
            "trap minis_release_apt_lock EXIT; " +
            "apt-get update && " +
            "apt-get install -y --no-install-recommends $pkgs; " +
            "else echo \"devsuite: apt lock busy, waited ${APT_LOCK_WAIT_SEC}s\" >&2; exit 75; " +
            "fi )"
    }

    /**
     * [T-dev-suite-disk-gate] Refuse an assembly the device cannot hold, BEFORE
     * a byte is downloaded. The runtime gate inside the sandbox only fires under
     * 2 GiB free, so an android-suite assembly (~8 GB of toolchains) on a nearly
     * full phone used to start blindly, fill the volume mid-transfer and fail —
     * leaving partially unpacked SDK trees behind.
     *
     * [components] must be the ones that still have to install: the caller
     * filters out components whose probe already passes, otherwise re-assembling
     * a partly installed suite would be refused for space the existing
     * install already spent. The estimate adds a bounded reserve for the
     * resumable `.part` cache the run is about to fill.
     *
     * Returns the shortfall message, or null when there is room.
     */
    internal fun roomShortfall(context: Context, components: List<DevComponent>): String? {
        if (components.isEmpty()) return null
        val needed = components.sumOf { it.estimatedBytes }
        if (needed <= 0L) return null
        val reserve = minOf(components.maxOf { it.estimatedBytes }, RESERVE_CAP_BYTES)
        val required = needed + reserve
        val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free >= required) return null
        return context.getString(R.string.dev_suite_no_space, required / GB, free / GB)
    }

    /**
     * [T-devsuite-cascade] Pure dependency cascade: given [ordered] (already in
     * topological order) and the ids whose install FAILED, return every
     * selected component that must be skipped, mapped to the dependency that
     * blocks it. Components that do not (transitively) depend on a failure keep
     * going — a failed optional Flutter download must not kill rust-dev.
     */
    internal fun blockedByFailures(
        ordered: List<DevComponent>,
        failedIds: Set<String>,
    ): Map<String, String> {
        val blocked = LinkedHashMap<String, String>()
        for (c in ordered) {
            if (c.id in failedIds) continue
            val blocker = c.dependsOn.firstOrNull { it in failedIds || it in blocked }
            if (blocker != null) blocked[c.id] = blocker
        }
        return blocked
    }

    /**
     * Install a bundle's selected components in dependency order: required
     * components always install; optional ones only when [includeIds]
     * contains them (or when another selected component depends on them).
     * Returns the ids that ended up ready.
     */
    fun assembleBundleAsync(
        context: Context,
        bundle: DevBundle,
        includeIds: Set<String>,
        onDone: (List<String>) -> Unit = {},
    ) {
        scope.launch {
            val ordered = orderComponents(bundle, includeIds)
            if (ordered.isEmpty()) { onDone(emptyList()); return@launch }
            // The documented offline drop target has to exist before anyone can
            // put a payload in it — create it here, at the start of the flow.
            DevSuiteCommandRunner.run("mkdir -p $OFFLINE_DIR $CACHE_DIR 2>/dev/null || true") {}

            // [T-devsuite-uncheck-converge] The checkbox is the target state:
            // an installed component the user unchecked must LEAVE the
            // assembly, not merely be skipped. Without this, unchecking was a
            // visual no-op — the install loop never ran for it, and the next
            // dialog open pre-checked it again ("已安装插件保持勾选无法取消
            // 勾选"). Probe-gated: only actually-installed targets are touched,
            // and removals run BEFORE the disk gate because they can only free
            // space. A failed removal marks that component FAILED but must not
            // block installing the newly selected ones.
            for (c in convergeUninstallTargets(bundle, includeIds)) {
                if (probe(c)) uninstall(context, c)
            }

            // Probe once up front: components that are already ready cost
            // nothing and must not count against the disk gate.
            val readyFromProbe = ordered.mapNotNullTo(mutableSetOf<String>()) {
                if (probe(it)) it.id else null
            }
            val pending = ordered.filterNot { it.id in readyFromProbe }

            roomShortfall(context, pending)?.let { reason ->
                Log.e("DevSuiteInstaller", "assembly refused: $reason")
                // Selection-level refusal: every component this assembly still
                // had to install reports the same reason. Pinning it on
                // ordered.firstOrNull() blamed an arbitrary component for a
                // decision that was about the whole selection.
                pending.forEach { DevSuiteStore.update(context, it.id, DevSuiteStore.Status.FAILED, reason) }
                readyFromProbe.forEach { id ->
                    DevSuiteStore.update(
                        context, id, DevSuiteStore.Status.INSTALLED,
                        context.getString(R.string.dev_suite_ready),
                    )
                }
                onDone(emptyList())
                return@launch
            }

            val ready = mutableListOf<String>()
            val failed = mutableSetOf<String>()
            var blocked = blockedByFailures(ordered, failed)
            for (c in ordered) {
                val blocker = blocked[c.id]
                if (blocker != null) {
                    failed.add(c.id)
                    blocked = blockedByFailures(ordered, failed)
                    DevSuiteStore.update(
                        context, c.id, DevSuiteStore.Status.FAILED,
                        context.getString(R.string.dev_suite_skipped_dep, nameOf(blocker)),
                    )
                    continue
                }
                val ok = install(context, c, probeResult = c.id in readyFromProbe)
                if (ok) {
                    ready.add(c.id)
                } else {
                    failed.add(c.id)
                    blocked = blockedByFailures(ordered, failed)
                }
            }
            onDone(ready)
        }
    }

    /** Display name for a component id (bundles first, then online tools). */
    private fun nameOf(id: String): String =
        DevSuiteCatalog.allComponents().firstOrNull { it.id == id }?.name ?: id

    /**
     * [T-devsuite-uncheck-converge] Components this assembly must REMOVE so
     * the dialog's checkbox target state converges: in the bundle, not
     * required, and not selected. Pure so the selection contract is
     * unit-testable; the caller probe-gates each target at runtime.
     */
    internal fun convergeUninstallTargets(bundle: DevBundle, includeIds: Set<String>): List<DevComponent> =
        bundle.components.filter { !it.isRequired && it.id !in includeIds }

    /** Topological order over dependsOn within the selection. */
    internal fun orderComponents(bundle: DevBundle, includeIds: Set<String>): List<DevComponent> {
        val selected = bundle.components.filter { it.isRequired || it.id in includeIds }
        val byId = selected.associateBy { it.id }
        val out = mutableListOf<DevComponent>()
        val done = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(c: DevComponent) {
            if (c.id in done || c.id in visiting) return
            visiting.add(c.id)
            c.dependsOn.forEach { dep -> byId[dep]?.let(::visit) }
            visiting.remove(c.id)
            done.add(c.id)
            out.add(c)
        }
        selected.forEach(::visit)
        return out
    }

    /**
     * Uninstall: purge paths + links inside the guest, then clear the marker.
     * apt packages are left installed (dpkg state is shared; removing them
     * could break other components), same trade-off as the setup scripts
     * make for apt-backed components.
     *
     * [T-devsuite-uninstall] Reached from the "开发环境套件" card (installed
     * components used to have no way back out of the app).
     */
    suspend fun uninstall(context: Context, component: DevComponent): Boolean {
        if (component.purgePaths.isEmpty() && component.purgeLinks.isEmpty()) {
            DevSuiteStore.update(context, component.id, DevSuiteStore.Status.NOT_INSTALLED)
            return true
        }
        val cmds = buildList {
            component.purgePaths.forEach { p ->
                if (p.startsWith("/") && !p.contains("..")) add("rm -rf '$p'")
            }
            component.purgeLinks.forEach { l ->
                if (!l.contains('/')) add("rm -f /usr/local/bin/'$l'")
            }
        }
        val script = cmds.joinToString(" && ")
        _busy.value = true
        val result = try {
            DevSuiteCommandRunner.run("sh -c \"$script\"") { line ->
                DevSuiteStore.update(
                    context, component.id, DevSuiteStore.Status.INSTALLING, line.take(120)
                )
            }
        } finally {
            _busy.value = false
        }
        val ok = result.exitCode == 0
        DevSuiteStore.update(
            context, component.id,
            if (ok) DevSuiteStore.Status.NOT_INSTALLED else DevSuiteStore.Status.FAILED,
            if (ok) context.getString(R.string.dev_suite_not_installed)
            else DevSuiteInstallDiagnostics.text(
                DevSuiteInstallError(
                    component.id,
                    DevSuiteInstallError.Stage.UNINSTALL,
                    result.exitCode,
                    script,
                    result.outputTail,
                ),
            ),
        )
        return ok
    }

    /** Uninstall [component] without blocking the caller (UI entry point). */
    fun uninstallAsync(context: Context, component: DevComponent, onDone: (Boolean) -> Unit = {}) {
        scope.launch { onDone(uninstall(context, component)) }
    }

}
