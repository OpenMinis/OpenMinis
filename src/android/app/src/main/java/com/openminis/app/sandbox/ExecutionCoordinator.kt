package com.openminis.app.sandbox

import com.openminis.app.data.repository.MCPToolPolicy

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.notification.SandboxNotifyActions
import com.openminis.app.sandbox.SandboxResourceGate
import com.openminis.app.sandbox.kernel.BudgetClassifier
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"
    private const val LANE_PREFIX = "subagent:"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    private var appContextRef: Context? = null
    private val appContext: Context get() = checkNotNull(appContextRef) { "ExecutionCoordinator.init must be called first" }
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()
    private val lastIdle = ConcurrentHashMap<String, Long>()
    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var evictionJob: Job? = null

    @Synchronized
    private fun scheduleIdleReap() {
        if (evictionJob?.isActive == true) return
        evictionJob = evictionScope.launch {
            while (shells.isNotEmpty()) {
                delay(30_000)
                reapIdleShells()
            }
        }
    }

    private suspend fun reapIdleShells() {
        // Conservative process-wide guard. A background job in ANY chat pins
        // shells until it exits. Never terminate a service to meet a cache cap.
        if (!ShellIdleSafety.canReap()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val prefs = com.openminis.app.data.ToolLimitPrefs
        val idle = lastIdle.entries.sortedBy { it.value }
        var excess = (idle.size - prefs.idleShellCount()).coerceAtLeast(0)
        for ((id, timestamp) in idle) {
            if (excess <= 0 && now - timestamp < prefs.idleShellMinutes() * 60_000L) continue
            val mutex = mutexes[id] ?: continue
            if (!mutex.tryLock()) continue
            try {
                if (lastIdle[id] != timestamp || !ShellIdleSafety.canReap()) continue
                // Keep the per-session mutex: queued callers already reference it.
                globalLock.withLock {
                    shells.remove(id)?.stop()
                    lastInjectedKeys.remove(id)
                    lastIdle.remove(id)
                }
                excess--
            } finally { mutex.unlock() }
        }
    }

    fun init(context: Context) {
        appContextRef = context.applicationContext
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 1. Get or create per-session Mutex (thread-safe via ConcurrentHashMap)
     * 2. Acquire per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        // [T-timeout-honored] May SHORTEN the armed wall clock, never
        // extend it: armed = min(timeout, class wall). The class ceiling
        // stays the authority; the caller gets to bound its own wait.
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        resourceClass: SandboxResourceGate.ResourceClass = SandboxResourceGate.ResourceClass.AUTO,
    ): CommandResult {
        MCPToolPolicy.blockedMessage(appContext, command)?.let { msg ->
            lineCallback?.invoke(msg)
            return CommandResult(output = msg, exitCode = 1, durationMs = 0)
        }
        SandboxJobKeepAlive.onStart(appContext, sessionId, command)
        try {
        // ConcurrentHashMap.getOrPut (the Kotlin extension) is NOT atomic — it
        // does get→compute→put, so two first-touching coroutines can both
        // construct a Mutex and the loser's callers serialise on an orphan.
        // computeIfAbsent is atomic; use it and say so (matches the same
        // pattern in SandboxResourceGate.withNamedLock).
        val mutex = mutexes.computeIfAbsent(sessionId) { Mutex() }

        return mutex.withLock {
        lastIdle.remove(sessionId)
        try {
        // [T-yoyo-root-deletion] Under YOYO the gate has already shown a
        // mustPrompt confirmation for a root deletion before the command reaches
        // this executor, so refusing it again here made that approval a dead
        // end: the user tapped through and the command still died with exit 126.
        // The guest rootfs is also rebuildable (`minis-dev-setup`), unlike a host
        // partition. Block-device writes stay refused in every mode.
        val yoyo = com.openminis.app.security.SecurityGateHolder
            .activeSessionMode(sessionId)
            .isYoyo()
        val refused = GuestWorkloadPolicy.hostRefusal(command, allowRootDeletion = yoyo)
            ?: DiskPressure.refusal(appContext, command)
            ?: run {
                // [T-memory-poison-guard] 工具路径之外的同一配额：/var/minis/memory
                // bind 的宿主目录就是本会话的 memory 目录，直接 shell 写语法在
                // 当日配额耗尽后一并拒绝（详见 GuestWorkloadPolicy KDoc）。
                val memoryHost = com.openminis.app.sandbox.SessionWorkspace.memoryDir(
                    appContext.filesDir,
                    ownerSessionId(sessionId),
                )
                GuestWorkloadPolicy.memoryQuotaRefusal(
                    command,
                    com.openminis.app.data.repository.MemoryRepository.dailyEntryCount(memoryHost),
                    com.openminis.app.data.repository.MemoryRepository.MAX_DAILY_ENTRIES,
                )
            }
        if (refused != null) {
            Log.w(TAG, "[$sessionId] refused before start: $refused")
            lineCallback?.invoke(refused)
            return@withLock CommandResult(output = refused, exitCode = 126, durationMs = 0)
        }
        SandboxResourceGate.withCommandLock(
            command,
            resourceClass = resourceClass,
            pressure = { SandboxMemoryPressure.reason(appContext) },
            limits = { SandboxMemoryPressure.executionLimits(appContext) },
            onWaiting = { lineCallback?.invoke(it) },
        ) {
            val budget = BudgetClassifier.classify(command, resourceClass)
            // [T-timeout-honored] The caller may shorten the class wall but
            // never extend it, and the setup floor always survives (a killed
            // minis-dev-setup-full leaves a broken rootfs). The old code
            // logged "caller timeout ignored" — a 60s request then rode the
            // full 1200s BATCH wall while the user watched the shell "hang".
            val armed = BudgetClassifier.armMs(budget, command, timeout)
            if (timeout > 0L && armed < budget.wallMs) {
                Log.i(
                    TAG,
                    "[$sessionId] caller timeout ${timeout}ms shortens class " +
                        "${budget.workClass} wall ${budget.wallMs}ms → ${armed}ms",
                )
            }
            val startTime = System.currentTimeMillis()

            // Auto-boot PRoot if not already booted
            if (!PRootKernel.isBooted) {
                Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                PRootKernel.boot(appContext)
            }

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            // Get or create shell — protected by globalLock to avoid duplicate creation
            val shell = getOrCreateShell(sessionId)
            recordLastCommand(sessionId, command)

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }
            // Plugin installers add command links to the guest PATH while this
            // persistent shell remains alive. Refresh both PATH and bash's
            // command hash before every command so the next command sees it.
            shell.refreshPath()

            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = armed,
                lineCallback = lineCallback,
                // [T-resource-class-honoured] Pass the budget this function
                // already computed. Re-deriving it downstream from the command
                // text alone silently dropped `resourceClass`.
                callerBudget = budget,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val (stripped, loggedExit) = TerminalSanitizer.dropOffloadTrace(sanitized)
            var effectiveExit = exitCode
            if (effectiveExit == 0 && loggedExit != null && loggedExit != 0) effectiveExit = loggedExit
            if (effectiveExit == 0 && stripped.contains("handler_timeout")) effectiveExit = 124
            val truncated = TerminalSanitizer.truncateIfNeeded(stripped)
            val output = if (effectiveExit != 0 && effectiveExit != 124) {
                "$truncated\n(exit code: $effectiveExit)"
            } else {
                truncated
            }

            CommandResult(
                output = MCPToolPolicy.filterToolsOutput(appContext, command, output),
                exitCode = effectiveExit,
                durationMs = durationMs,
            )
        }
        } finally {
            if (shells.containsKey(sessionId)) lastIdle[sessionId] = android.os.SystemClock.elapsedRealtime()
            scheduleIdleReap()
        }
        }
        } finally {
            SandboxJobKeepAlive.onEnd(appContext, sessionId)
        }
    }


    private fun recordLastCommand(sessionId: String, command: String) {
        if (sessionId.startsWith("__") || sessionId == "self-build") return
        if (!SandboxNotifyActions.shouldRecordLastCommand(command)) return
        // [T-per-command-file-write] The only consumer of this file is the
        // background task-completion notification's RETRY action
        // (`sh /var/minis/workspace/.minis-last-cmd.sh`), which
        // BackgroundTaskNotifier posts only while "Task Notifications" is
        // enabled. With that toggle off nothing reads the file, so skip the
        // per-command write + chmod entirely. Defaults to recording when the
        // setting cannot be resolved (feature ships ON; conservative).
        val notifyEnabled = runCatching {
            (appContext.applicationContext as? com.openminis.app.MinisApp)
                ?.backgroundSettingsRepository?.taskNotificationsEnabled?.value
        }.getOrNull() ?: true
        if (!notifyEnabled) return
        val host = PRootKernel.resolveHostPath(SandboxNotifyActions.LAST_CMD_GUEST_PATH) ?: return
        val content = "#!/bin/sh\n# recorded by ExecutionCoordinator\n$command\n"
        try {
            // Skip the truncate-rewrite + setExecutable when the exact same
            // command is already recorded — a no-op rewrite is not consumed by
            // anything and the exec bit is already set on an existing file.
            if (host.isFile && host.readText() == content) return
            host.parentFile?.mkdirs()
            host.writeText(content)
            host.setExecutable(true, false)
        } catch (t: Throwable) {
            Log.w(TAG, "recordLastCommand failed: ${t.message}")
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive && !shellMountsStale(existing, sessionId)) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive && !shellMountsStale(recheck, sessionId)) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell ${if (recheck.isAlive) "mounts stale" else "died"}, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            shell
        }
    }

    fun sessionBindMounts(sessionId: String): Map<String, String> = buildSessionBindMounts(sessionId)

    internal fun hasLiveShell(sessionId: String): Boolean = shells[sessionId]?.isAlive == true

    private fun shellMountsStale(shell: PersistentShell, sessionId: String): Boolean {
        // Compare the whole argv, not only /sdcard. A user mount named sdcard,
        // a new /var/minis/mounts entry, or a filed-session hostDir change is
        // otherwise stuck on the shell that already started.
        return shell.bindSnapshot() != buildSessionBindMounts(sessionId, publish = false)
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String, publish: Boolean = true): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // [diag] previous attachments mount target — exposes cross-session
        // overwrite of the global bindMounts map (the suspected cause of
        // the "file disappears after first download" bug)
        val prevAttachments = PRootKernel.bindMounts["/var/minis/attachments"]

        // Session / project directories. Sub-agent lanes share the parent
        // session tree so files written by a teammate stay visible here.
        // Filed sessions mount attachments/workspace/offloads/browser from the
        // project folder; memory stays per-session.
        val owner = ownerSessionId(sessionId)
        SessionWorkspace.SESSION_SUBDIRS.forEach { subdir ->
            val hostDir = SessionWorkspace.hostDir(filesDir, owner, subdir).also {
                if (publish) it.mkdirs()
            }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            if (publish) PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        Log.w(TAG, "[diag] buildSessionBindMounts sessionId=$sessionId " +
            "attachments: prev=$prevAttachments new=${mounts["/var/minis/attachments"]}")

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs minis-mcp-cli — /var/minis/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `minis-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        val globalBase = File(filesDir, SessionWorkspace.GLOBAL_DIR)
        SessionWorkspace.GLOBAL_BIND_SUBDIRS.forEach { subdir ->
            val hostDir = File(globalBase, subdir).also {
                if (publish) it.mkdirs()
            }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            if (publish) PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        // Shared storage is not a MountedFoldersStore entry. Without this,
        // shell_execute and the interactive PTY never see /sdcard even when
        // All Files Access is granted (PRootKernel.bindMounts is a different map).
        for ((linuxPath, host) in PRootKernel.shellSharedStorageBinds(appContext, publish)) {
            if (linuxPath == SharedStorageBindPlan.MOUNTS_SDCARD && linuxPath in mounts) continue
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        terminateOne(sessionId)
        if (!isLaneSession(sessionId)) {
            val prefix = lanePrefix(sessionId)
            shells.keys.filter { it.startsWith(prefix) }.forEach { terminateOne(it) }
        }
    }

    private fun terminateOne(sessionId: String) {
        val shell = shells.remove(sessionId)
        lastIdle.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            stopOne(sessionId)
            if (!isLaneSession(sessionId)) {
                val prefix = lanePrefix(sessionId)
                shells.keys.filter { it.startsWith(prefix) }.forEach { stopOne(it) }
            }
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            lastInjectedKeys.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     * - Rewrites guest `/etc/localtime`. Processes that ignore `TZ` still read it.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        runCatching { PRootKernel.syncHostTimezoneFiles() }
            .onFailure { Log.w(TAG, "syncHostTimezoneFiles failed: ${it.message}") }
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }

    private fun stopOne(sessionId: String) {
        val shell = shells.remove(sessionId)
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        Log.i(TAG, "[$sessionId] Shell stopped by user")
    }

    fun ownerSessionId(sessionId: String): String {
        if (!isLaneSession(sessionId)) return sessionId
        val rest = sessionId.substring(LANE_PREFIX.length)
        val cut = rest.lastIndexOf(':')
        return if (cut > 0) rest.substring(0, cut) else sessionId
    }

    fun isLaneSession(sessionId: String): Boolean = sessionId.startsWith(LANE_PREFIX)

    private fun lanePrefix(ownerSessionId: String): String = "$LANE_PREFIX$ownerSessionId:"
}
