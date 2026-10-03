package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
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

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        /**
         * [T-android-shell-process-budget] A <system-reminder> for the model
         * when the command waited in [GlobalShellThrottle]'s queue; null when
         * it started at once. Kept apart from [output] so the caller can put
         * it after everything else it appends (exit status, redaction).
         */
        val queueNote: String? = null,
    )

    /**
     * [T-android-shell-fresh-process] Which mechanism runs commands.
     *
     * Defaults to the warm shell so step 1-3 change nothing for users; step 4
     * flips it once the comparison harness shows the two paths agree. Kept
     * mutable so a debug build can switch at runtime without a reinstall,
     * which is how the step-3 comparison is driven.
     */
    @Volatile
    internal var executionStrategy: ShellExecutionStrategy = ShellExecutionStrategy.DEFAULT

    /**
     * [T-android-shell-fresh-process] One PRoot process per command.
     *
     * No lease, no pool slot, no mutex: a command that owns its own process
     * and pipes cannot be interfered with by another, so the serialisation the
     * pooled path needs does not apply. That also means commands in one
     * session now run genuinely concurrently rather than queueing — the
     * behaviour the tool contract already promises ("Each invocation spawns a
     * fresh process — there is no shared terminal session").
     */
    private suspend fun executeFresh(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
    ): CommandResult {
        val startTime = System.currentTimeMillis()
        val bindMounts = buildSessionBindMounts(fsSessionId ?: sessionId)
        val envVars = envVarRepository?.allAsDict() ?: emptyMap()

        suspend fun attempt(noSeccomp: Boolean): Pair<String, Int> {
            val shell = FreshProcessShell(
                context = appContext,
                sessionId = sessionId,
                sessionBindMounts = bindMounts,
                useNoSeccomp = noSeccomp,
            )
            // [T-android-shell-fresh-process] Step 5. Register before running so
            // stopCurrentCommand() can reach this command. A fresh shell is owned
            // by this call frame, unlike a pooled PersistentShell, so without the
            // registry the Stop button would have nothing to kill and a running
            // command would ignore it.
            // [T-android-fresh-shells-concurrent] The inner set is a concurrent
            // key set: it is mutated here and in the finally below under the
            // map's bin lock, but iterated by stopFreshShells() from the Stop /
            // terminate thread WITHOUT that lock, so a plain LinkedHashSet
            // could throw ConcurrentModificationException mid-Stop.
            freshShells.compute(sessionId) { _, existing ->
                (existing ?: newFreshShellSet()).also { it.add(shell) }
            }
            return try {
                shell.execute(
                    command = command,
                    timeout = timeout,
                    envVars = envVars,
                    lineCallback = lineCallback,
                )
            } finally {
                freshShells.computeIfPresent(sessionId) { _, set ->
                    set.remove(shell)
                    if (set.isEmpty()) null else set
                }
            }
        }

        var (rawOutput, exitCode) = attempt(noSeccomp = false)
        // [T-android-fresh-seccomp-selfheal / GH#186] The warm path
        // (PersistentShell), ShellExecutor and TerminalSession all retry once
        // with PROOT_NO_SECCOMP=1 when the child dies on an early fatal signal
        // with no output. The fresh path became the default without that
        // self-heal, so on a GH#186 device every shell_execute died with
        // 135/139. Same narrow gate as the others (SeccompFallbackPolicy):
        // a normal failure, a timeout (124) or a user Stop (130) never
        // qualifies, and it fires at most once per command.
        val firstDurationMs = System.currentTimeMillis() - startTime
        if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = exitCode,
                durationMs = firstDurationMs,
                producedOutput = rawOutput.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                SeccompFallbackPolicy.retryLogLine(exitCode, firstDurationMs, "fresh shell command"),
            )
            // No output came out of the first attempt (a precondition of the
            // retry), so reusing lineCallback cannot duplicate anything.
            val retried = attempt(noSeccomp = true)
            rawOutput = retried.first
            exitCode = retried.second
        }
        val durationMs = System.currentTimeMillis() - startTime
        val sanitized = TerminalSanitizer.sanitize(rawOutput)
        val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
        val output = if (exitCode != 0 && exitCode != 124) {
            ShellExitCode.ensureSuffix(truncated, exitCode)
        } else {
            truncated
        }
        return CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
    }

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /**
     * [T-android-shell-fresh-process] Live fresh-process commands per session.
     *
     * A set rather than a single entry: a session runs several commands
     * concurrently (the same reason the warm path holds a pool), and Stop must
     * mean "stop everything this conversation is running", not just the most
     * recent one. Entries are removed in a finally block, so a crashing
     * command cannot leak one.
     */
    private val freshShells = ConcurrentHashMap<String, MutableSet<FreshProcessShell>>()

    /** [T-android-fresh-shells-concurrent] Thread-safe per-session set for [freshShells]. */
    private fun newFreshShellSet(): MutableSet<FreshProcessShell> = ConcurrentHashMap.newKeySet()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * [T-android-a11y-helper-mount] dispatch session id -> the session whose
     * `/var/minis` directories its shell binds (fsSessionId), recorded only
     * when they differ (a helper / sub agent sharing its parent's workspace,
     * T-p2-shared-workspace). Dropped in [sessionDidTerminate].
     */
    private val mountedSessionIds = ConcurrentHashMap<String, String>()

    /**
     * [T-android-a11y-helper-mount] The session id whose per-session dirs are
     * mounted at `/var/minis/...` for a command dispatched as [sessionId].
     *
     * The shell exports `MINIS_CHAT_SESSION_ID=<dispatch id>`, but for a
     * helper the bind mounts come from `fsSessionId ?: sessionId` (the
     * PARENT). A native offload that resolves `/var/minis/attachments` with
     * the env id therefore writes into the helper's own directory, which
     * neither the helper's shell nor read_image (called with the parent id)
     * can see. Offloads map the env id through here first — the same
     * `fsSessionId ?: sessionId` rule [executeFresh] mounts with.
     */
    fun mountedSessionIdFor(sessionId: String): String = mountedSessionIds[sessionId] ?: sessionId

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

    fun init(context: Context) {
        appContext = context.applicationContext
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
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        /** [T-p2-shared-workspace] Session whose `/var/minis` dirs the shell
         *  binds (a helper's PARENT). The shell process itself stays keyed by
         *  [sessionId], so stop / cleanup never reach the parent's shell. */
        fsSessionId: String? = null,
    ): CommandResult {
        // [T-android-concurrent-shell] Pick a FREE shell from this session's
        // pool instead of queueing behind its one shell.
        //
        // Why a pool and not "drop the lock": the lock is load-bearing. A
        // PersistentShell writes the command to one long-lived /bin/sh's stdin
        // and reads that process's stdout until an end marker, through a SINGLE
        // `pendingCallback` slot. Two commands on one shell would interleave on
        // the same pipe and race that slot — the output of one would be
        // attributed to the other. So the serialization stays PER SHELL; what
        // changes is that a session may have more than one.
        //
        // Why not iOS's model: iOS forks a fresh /bin/sh per call and has no
        // lock at all (ISHExecutionCoordinator's comment records deleting its
        // FIFO in 2026-05-25 for exactly the reason we hit here — it "silently
        // serialize[d] at the coordinator, defeating the upper-layer
        // parallelism"). Android cannot copy that directly: a proot shell costs
        // a process plus every bind mount re-applied at spawn, which is far
        // heavier than iSH's fork, so spawning one per call would trade this
        // latency for a worse one. Pooling keeps the warm-shell benefit and
        // still lets N commands run at once.
        //
        // The documented contract is unaffected: the tool schema already tells
        // the model "Each invocation spawns a fresh process — there is no shared
        // terminal session", so nothing may depend on cwd or shell variables
        // surviving between calls. Env vars are re-applied per command below.
        // The lease arrives with its slot's lock ALREADY HELD (claimed
        // atomically inside acquireShellLease), so this is try/finally rather
        // than withLock — re-locking here would deadlock on a non-reentrant
        // Mutex.
        // [T-android-env-first-turn] Boot BEFORE taking the lease, not after.
        //
        // This ordering is the first-turn env bug. acquireShellLease() spawns
        // the shell, and it used to run while PRootKernel had not booted yet —
        // so PROOT_LOADER was still empty, proot bare-execve'd the rootfs
        // busybox, and SELinux killed it on Android 10+:
        //
        //   spawn ctx: proot=true rootfs=true loader64=false loader32=false
        //   spawn outcome: alive=false        <- execve("/bin/sh"): Permission denied
        //   Auto-booting PRootKernel          <- boot only starts HERE
        //   PRoot kernel booted ... loader=.../libproot-loader.so
        //   spawn ctx: ... loader64=true loader32=true
        //   spawn outcome: alive=true
        //
        // The user's env was then exported into that dead first shell and lost
        // when executeCommand() silently respawned, so the first tool call of
        // every cold start ran with no environment and a retry "fixed" it.
        // Booting first means the very first spawn already has its loader.
        if (!PRootKernel.isBooted) {
            Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
            PRootKernel.boot(appContext)
        }

        // [T-android-a11y-helper-mount] Remember whose /var/minis dirs this
        // dispatch id is mounted with, so native offloads (which only learn
        // the MINIS_CHAT_SESSION_ID = dispatch id) resolve guest paths
        // against the directory the guest actually sees. See
        // [mountedSessionIdFor].
        if (fsSessionId != null && fsSessionId != sessionId) {
            mountedSessionIds[sessionId] = fsSessionId
        }

        // [T-android-shell-fresh-process] Step 3: route through the strategy.
        // FRESH_PROCESS needs no lease, no pool slot and no mutex — a command
        // that owns its own process cannot collide with another one, so the
        // serialisation the warm path requires is simply absent here.
        // [T-android-global-shell-throttle] Commands are admitted against a
        // budget of live Minis child processes across every session; the rest
        // queue. Taken BEFORE the pool lease, so a queued command holds no
        // shell slot.
        // [T-android-shell-process-budget] The queue does not eat the
        // command's timeout: once admitted it runs with its full budget. A
        // fresh spawn that failed ran nothing, so it re-queues and retries.
        val outcome = GlobalShellThrottle.run(
            tag = sessionId,
            isStartFailure = ::isStartFailure,
        ) {
            if (executionStrategy == ShellExecutionStrategy.FRESH_PROCESS) {
                executeFresh(sessionId, command, timeout, lineCallback, fsSessionId)
            } else {
                executeWarm(sessionId, command, timeout, lineCallback, fsSessionId)
            }
        }
        return when (outcome) {
            is GlobalShellThrottle.Outcome.Ran -> {
                if (!outcome.queued) outcome.value
                else outcome.value.copy(queueNote = queueNote(outcome.queuedMs, outcome.startRetries))
            }
            is GlobalShellThrottle.Outcome.QueueTimedOut -> CommandResult(
                output = "Not run: waited ${outcome.waitedMs / 1000}s for Android's per-app process limit to " +
                    "free up (${outcome.lastLiveCount} Minis processes running, budget " +
                    "${GlobalShellThrottle.PROCESS_BUDGET}), or for the shell to start. Nothing was executed; " +
                    "retry once other commands or background processes have finished.",
                exitCode = 124,
                durationMs = outcome.waitedMs,
            )
        }
    }

    /**
     * A fresh process that could not be spawned (see FreshProcessShell's
     * spawn catch) ran nothing and is safe to retry. Everything else - a
     * warm shell that died, a command killed mid-run - may have had effects.
     */
    internal fun isStartFailure(result: CommandResult): Boolean =
        result.exitCode == -1 && result.output.startsWith(FreshProcessShell.SPAWN_FAILED_PREFIX)

    /**
     * [T-android-shell-process-budget] Tell the model a command sat in the
     * queue, so a slow result is not read as a slow command.
     */
    internal fun queueNote(queuedMs: Long, startRetries: Int): String {
        val secs = String.format(java.util.Locale.US, "%.1f", queuedMs / 1000.0)
        val retried = if (startRetries > 0) " Its process failed to start $startRetries time(s) and was retried." else ""
        return "<system-reminder>This command was queued for ${secs}s before it started, because Android " +
            "limits how many processes an app may run at once.$retried It then ran to completion and its output " +
            "is complete, but its measured duration may be inaccurate.</system-reminder>"
    }

    /** The pooled warm-shell path (ShellExecutionStrategy.WARM_SHELL). */
    private suspend fun executeWarm(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
    ): CommandResult {
        val lease = acquireShellLease(sessionId, fsSessionId ?: sessionId)

        return try {
            val startTime = System.currentTimeMillis()

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            // The lease already resolved (and started) this slot's shell.
            val shell = lease.shell

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            // [T-android-concurrent-shell] Keyed by SLOT, not by session: each
            // pooled shell is its own process with its own environment, so a
            // session-wide snapshot would tell slot 2 that keys slot 1 already
            // exported are present when slot 2 has never seen them.
            // [T-android-env-first-turn] applyEnvironment now also REMEMBERS
            // this snapshot on the shell, so that if the process turns out to
            // be dead (or dies and is respawned by executeCommand below), the
            // exports are replayed into the incarnation that actually runs the
            // command. Before that, a cold-start shell death between here and
            // executeCommand silently dropped the whole environment and the
            // user's first tool call ran bare.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[lease.key] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[lease.key] = envVars.keys.toSet()
            }

            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (exitCode != 0 && exitCode != 124) {
                ShellExitCode.ensureSuffix(truncated, exitCode)
            } else {
                truncated
            }

            CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
        } finally {
            lease.mutex.unlock()
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    /**
     * [T-android-concurrent-shell] Upper bound on shells per session.
     *
     * Each is a proot process holding the session's bind mounts, so this is
     * real memory and real PIDs on a phone — not a free knob. Four covers the
     * observed fan-out (this device's history: of 88,839 tool-carrying turns,
     * the widest were a handful of 5-call turns, and shell_execute appears in
     * at most a few of those at once) while staying well under
     * ChatViewModel.MAX_CONCURRENT_TOOLS, which bounds the whole turn anyway.
     * A 5th concurrent shell command simply waits for a slot — the pre-change
     * behaviour, applied at a much higher threshold.
     */
    private const val MAX_SHELLS_PER_SESSION = 4

    /** One pooled shell plus the lock that serializes ITS pipe. */
    private data class ShellLease(
        /** "<sessionId>#<slot>" — the key for [shells], [mutexes], [lastInjectedKeys]. */
        val key: String,
        val shell: PersistentShell,
        val mutex: Mutex,
    )

    /**
     * [T-android-concurrent-shell] Find a shell for this session whose lock is
     * free, creating one if the pool has room.
     *
     * Deliberately best-effort rather than a strict allocator: `tryLock` is
     * only a hint (the winner takes it for real inside `withLock`), so two
     * callers can pick the same slot and one will wait. That is correct and
     * cheap — the alternative, holding a global allocation lock across the
     * whole command, would reintroduce the serialization this exists to remove.
     *
     * Falls back to slot 0 when every slot is busy and the pool is full, so a
     * 5th concurrent command queues instead of failing.
     */
    private suspend fun acquireShellLease(sessionId: String, fsSessionId: String): ShellLease {
        // Claim a slot ATOMICALLY, before doing anything slow.
        //
        // The first cut here scanned for a slot whose Mutex was not `isLocked`
        // and returned it. That is a race, and the device proved it: three
        // concurrent commands all reached the scan before ANY of them had
        // entered `withLock`, so every one saw slot 0 free, all three picked
        // it, and they queued exactly as before — the logs even show slot 0
        // being created twice. `isLocked` answers about the past, not about
        // what the caller is going to do next.
        //
        // `tryLock` is the fix: it CLAIMS the slot in the same operation that
        // tests it, so exactly one caller can win a given slot. The lock is
        // held from here until the command finishes and is released in the
        // caller's `finally` — see [ShellLease.release].
        for (slot in 0 until MAX_SHELLS_PER_SESSION) {
            val key = "$sessionId#$slot"
            val mutex = mutexes.getOrPut(key) { Mutex() }
            if (!mutex.tryLock()) continue
            // Won this slot. Reuse its shell when warm; otherwise spawn one,
            // still holding the claim so no one else spawns into it too.
            val existing = shells[key]
            val shell = if (existing != null && existing.isAlive) existing
            else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
            return ShellLease(key, shell, mutex)
        }
        // Pool exhausted: every slot is genuinely busy. Wait for slot 0 — the
        // pre-pooling behaviour, now at a 4x higher threshold.
        val key = "$sessionId#0"
        val mutex = mutexes.getOrPut(key) { Mutex() }
        mutex.lock()
        val existing = shells[key]
        val shell = if (existing != null && existing.isAlive) existing
        else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
        return ShellLease(key, shell, mutex)
    }

    /**
     * @param sessionId the POOL KEY ("<chatSessionId>#<slot>"), which is what
     *   [shells] is registered under.
     * @param chatSessionId the real chat session id. Kept separate because it
     *   is exported into the shell as MINIS_CHAT_SESSION_ID, which the native
     *   offload layer reads to scope ASK_ONCE permission grants per chat.
     *   Handing it the slotted key would make every slot a different "session"
     *   to the permission manager, so a user who granted a permission once
     *   would be prompted again on the next slot. Defaults to the pool key so
     *   the non-pooled callers below are unchanged.
     */
    private suspend fun getOrCreateShell(
        sessionId: String,
        fsSessionId: String = sessionId,
        chatSessionId: String = sessionId,
    ): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(fsSessionId)
            val shell = PersistentShell(appContext, chatSessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            shell
        }
    }

    /**
     * [T-android-session-private-mounts] Bind mounts for a shell of
     * [sessionId]: its own workspace / attachments / offloads / browser, the
     * global dirs, and the user's external folders — see [SessionMounts].
     *
     * The result goes ONLY into that shell's PRoot argv. This used to also
     * write the per-session dirs into the process-global
     * PRootKernel.bindMounts, where the last session to build a shell decided
     * what every other reader (the terminal, the image loader) saw.
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val built = SessionMounts.forContext(appContext, sessionId)
        // Legacy readers that resolve `/var/minis/<per-session>` WITHOUT a
        // session id keep their old answer (the most recent session) through
        // this explicit hint instead of through the shared mount table.
        PRootKernel.noteLegacySession(appContext, sessionId)
        Log.i(TAG, "[$sessionId] private bind mounts=${built.mounts.size} skipped=${built.skipped}")
        return built.mounts
    }

    /**
     * [T-android-concurrent-shell] Tear down every pooled shell belonging to
     * [sessionId] and drop its per-slot bookkeeping.
     *
     * Stops each one here rather than returning them, so no caller can forget a
     * slot; the return value is only the FIRST shell removed, kept so the two
     * existing callers' "did anything exist?" logging still works.
     *
     * Matching is on the "<sessionId>#<slot>" key prefix, anchored with the
     * separator so a session id that is a prefix of another cannot sweep its
     * neighbour's shells.
     */
    private fun removeSessionShells(sessionId: String): PersistentShell? {
        val prefix = "$sessionId#"
        var first: PersistentShell? = null
        for (key in shells.keys.toList()) {
            if (key != sessionId && !key.startsWith(prefix)) continue
            val shell = shells.remove(key) ?: continue
            // T124a: the env snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(key)
            // [T-android-concurrent-shell] The Mutex is deliberately NOT removed.
            // A command may be running on this slot right now and holding it;
            // dropping it from the map would let the next command create a FRESH
            // Mutex for the same key and run concurrently with the in-flight one
            // on a shell being torn down. Leaving it means the in-flight holder
            // still unlocks the object it locked, and the next caller waits
            // correctly. The map is bounded by (sessions x 4), not a real leak.
            if (first == null) first = shell else shell.stop()
        }
        // Legacy un-slotted key, from a build before pooling.
        lastInjectedKeys.remove(sessionId)
        return first
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        val shell = removeSessionShells(sessionId)
        mutexes.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        mountedSessionIds.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
        // [T-android-fresh-session-terminate] Fresh-process commands are not in
        // the pooled `shells` map, so the lines above never reached them: a
        // closed session's running command kept going (and holding its
        // bind-mounted workspace) until it finished or timed out. Same path as
        // the Stop button — FreshProcessShell.stop() kills the whole process
        // group — and keyed on this exact session id, so other sessions'
        // commands are untouched.
        val stoppedFresh = stopFreshShells(sessionId)
        if (stoppedFresh > 0) Log.i(TAG, "[$sessionId] $stoppedFresh fresh-process command(s) killed on terminate")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            // [T-android-concurrent-shell] Stop EVERY shell in the session's
            // pool, not one. A session now holds several, and Stop must mean
            // "stop what this conversation is running" — killing slot 0 while
            // slots 1-3 kept working would leave commands the user believes
            // they cancelled still executing, with no way to reach them.
            val shell = removeSessionShells(sessionId)
            shell?.stop()
            stopFreshShells(sessionId)
            Log.i(TAG, "[$sessionId] Shells stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            // [T-android-fresh-shells-concurrent] Snapshot via ArrayList, see stopFreshShells.
            ArrayList(freshShells.keys).forEach { stopFreshShells(it) }
            lastInjectedKeys.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    /**
     * Stop every fresh-process command running for [sessionId].
     *
     * The entries are NOT removed here — each execute() call removes its own
     * in a finally block. Removing them here too would race that cleanup and
     * could drop a set another command had just re-created.
     */
    private fun stopFreshShells(sessionId: String): Int {
        // [T-android-fresh-shells-concurrent] Iterate a snapshot: a command
        // finishing concurrently removes itself from the live set. ArrayList(c)
        // copies via toArray(), which is weakly consistent on a concurrent set;
        // Kotlin's toList() is NOT — for a size-1 collection it calls
        // iterator().next() after reading size, and throws
        // NoSuchElementException if the element was removed in between.
        val snapshot = freshShells[sessionId]?.let { ArrayList(it) } ?: return 0
        snapshot.forEach { runCatching { it.stop() } }
        return snapshot.size
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
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
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
}
