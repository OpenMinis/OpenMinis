package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.sandbox.ExecutionCoordinator
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
 *  1. apt install (when [DevComponent.aptPackages] is non-empty)
 *  2. postInstallSteps (setup scripts shipped via assets/default_mount)
 *  3. checkCommand probe — exit 0 marks the component INSTALLED
 *
 * Concurrency: one install at a time (installMutex) — apt holds a global
 * lock in the guest anyway, so parallel installs would only queue behind
 * it while burning shell slots. Progress lines flow to [DevSuiteStore]
 * for the UI card.
 */
object DevSuiteInstaller {

    /** Dedicated session id: never collides with chat sessions. */
    private const val SESSION_ID = "dev-suite-installer"

    /** Long wall clock: SDK/NDK/llama.cpp installs run tens of minutes. */
    private const val INSTALL_TIMEOUT_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Live progress for the currently installing component, null when idle. */
    private val _current = MutableStateFlow<Pair<String, String>?>(null)
    val current: StateFlow<Pair<String, String>?> = _current.asStateFlow()

    fun isReadyCached(context: Context, component: DevComponent): Boolean =
        DevSuiteStore.snapshot(context)[component.id]?.status ==
            DevSuiteStore.Status.INSTALLED

    /** Run the readiness probe in the guest. Exit 0 = ready. */
    suspend fun probe(component: DevComponent): Boolean {
        val result = ExecutionCoordinator.execute(
            sessionId = SESSION_ID,
            command = component.checkCommand,
            timeout = 30_000L,
        )
        return result.exitCode == 0
    }

    /**
     * Install one component (apt → postInstall → probe). Returns true when
     * the probe passes. Safe to call for an already-ready component — the
     * probe short-circuits without touching apt.
     */
    suspend fun install(context: Context, component: DevComponent): Boolean = installMutex.withLock {
        _busy.value = true
        _current.value = component.id to "开始安装"
        DevSuiteStore.update(context, component.id, DevSuiteStore.Status.INSTALLING, "开始安装")
        try {
            // Fast path: probe first — idempotent recipes make reinstall a no-op,
            // but skipping apt entirely saves minutes on rebuilds.
            if (probe(component)) {
                DevSuiteStore.update(context, component.id, DevSuiteStore.Status.INSTALLED, "已就绪")
                return true
            }

            if (component.aptPackages.isNotEmpty()) {
                val aptLine = "apt-get install -y --no-install-recommends " +
                    component.aptPackages.joinToString(" ")
                val aptResult = run(aptLine, component.id) { line ->
                    DevSuiteStore.update(context, component.id, DevSuiteStore.Status.INSTALLING, line.take(120))
                }
                if (aptResult != 0) {
                    DevSuiteStore.update(context, component.id, DevSuiteStore.Status.FAILED, "apt 安装失败 (exit $aptResult)")
                    return false
                }
            }

            for (step in component.postInstallSteps) {
                val rc = run(step, component.id) { line ->
                    DevSuiteStore.update(context, component.id, DevSuiteStore.Status.INSTALLING, line.take(120))
                }
                if (rc != 0) {
                    DevSuiteStore.update(context, component.id, DevSuiteStore.Status.FAILED, "步骤失败 (exit $rc): ${step.take(60)}")
                    return false
                }
            }

            if (probe(component)) {
                DevSuiteStore.update(context, component.id, DevSuiteStore.Status.INSTALLED, "已就绪")
                true
            } else {
                DevSuiteStore.update(context, component.id, DevSuiteStore.Status.FAILED, "安装完成但探针未通过")
                false
            }
        } finally {
            _busy.value = false
            _current.value = null
        }
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
            val ready = mutableListOf<String>()
            for (c in ordered) {
                val ok = install(context, c)
                if (ok) ready.add(c.id) else break  // stop at first failure; deps below would fail anyway
            }
            onDone(ready)
        }
    }

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
     * could break other components) — same trade-off taixu makes for
     * apt-backed components.
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
        val rc = run("sh -c \"$script\"", component.id) {}
        val ok = rc == 0
        DevSuiteStore.update(
            context, component.id,
            if (ok) DevSuiteStore.Status.NOT_INSTALLED else DevSuiteStore.Status.FAILED,
            if (ok) "" else "卸载失败 (exit $rc)",
        )
        return ok
    }

    private suspend fun run(
        command: String,
        componentId: String,
        onLine: (String) -> Unit,
    ): Int {
        val result = ExecutionCoordinator.execute(
            sessionId = SESSION_ID,
            command = command,
            timeout = INSTALL_TIMEOUT_MS,
            lineCallback = onLine,
        )
        return result.exitCode
    }
}
