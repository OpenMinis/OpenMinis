package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * [T-android-shell-fresh-process] Runs one command in its own PRoot process
 * and reads the exit code from the process itself.
 *
 * Step 2 of the migration away from in-band completion markers. See
 * [ShellExecutionStrategy] for the measurements and the two rejected
 * alternatives (FD 3, FIFO).
 *
 * What disappears here, compared with [PersistentShell]:
 *
 *  - **No `__MINIS_DONE__` marker.** Nothing is appended to the command's
 *    output and nothing is scanned for in it, so no command can forge
 *    completion by echoing a string. Completion is the command's own exit,
 *    reported out of band by the runner's status file (see
 *    [ForegroundCommandGroup.wrapForFreshProcess]) — proot's exit alone is
 *    not enough, because proot outlives any command that leaves a detached
 *    daemon behind.
 *  - **No carry-over buffer.** The marker was the only reason to hold bytes
 *    back between reads, and holding bytes back is what swallowed every short
 *    output in the regression this replaces.
 *  - **No cross-command contamination.** Each command owns its pipes. A
 *    runaway cannot write into the stream a later command reads, because that
 *    stream dies with it.
 *  - **No shell recycling after a timeout.** `destroyForcibly()` takes the
 *    process and its pipes together.
 *
 * Environment handling mirrors [PersistentShell.startProcess] exactly — the
 * same PROOT_* variables, TZ refresh, session id for native_offload, and the
 * seccomp fallback — so a command sees the identical environment either way.
 * That is what makes the two paths comparable in step 3.
 */
internal class FreshProcessShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,
    private val useNoSeccomp: Boolean = false,
) {

    /**
     * Run [command] to completion.
     *
     * @return (output, exitCode). Exit 124 on timeout, matching the warm-shell
     *   contract and `timeout(1)`.
     */
    suspend fun execute(
        command: String,
        timeout: Long,
        envVars: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        // [T-android-fresh-exit-status-file] Where the runner reports the
        // command's exit (see ForegroundCommandGroup.wrapForFreshProcess).
        // The rootfs is the guest's "/", so the same file is visible on both
        // sides without a bind mount.
        val exitDir = File(RootfsManager.getInstance(context).rootfsDir, EXIT_DIR_NAME)
        runCatching { exitDir.mkdirs(); pruneStaleExitFiles(exitDir) }
        val token = java.util.UUID.randomUUID().toString()
        val statusFile = File(exitDir, token)
        statusHandle = statusFile
        val process = try {
            spawn(command, envVars, "/$EXIT_DIR_NAME/$token")
        } catch (e: Exception) {
            Log.e(TAG, "[$sessionId] spawn failed: ${e.message}")
            return@withContext Pair("$SPAWN_FAILED_PREFIX ${e.message}]", -1)
        }
        // [T-android-shell-fresh-process] Step 5. Publish the process so
        // [stop] can reach it. The warm path's Stop button works by killing
        // the session's long-lived shell; a fresh process is owned by this
        // call frame and would otherwise be unreachable, so tapping Stop
        // would leave the command running with no way to cancel it.
        live = process
        if (stopped) {
            // Stop arrived between the check and the spawn. Honour it rather
            // than running a command the user already cancelled.
            runCatching { process.destroyForcibly() }
            live = null
            return@withContext Pair("[Cancelled]", CANCELLED_EXIT)
        }

        val output = StringBuilder()

        // [T-android-fresh-stderr-drain] Debug builds keep stderr on its own
        // pipe (see spawn); it MUST be read, or a command that writes 64 KB to
        // stderr blocks forever. Release merges stderr and needs no drain.
        val stderrDrain = if (SEPARATE_STDERR) {
            StderrDrain(process.errorStream) { Log.d("PRootStderr", it) }.start()
        } else {
            null
        }

        // The read below is a BLOCKING InputStream.read(). Coroutine
        // cancellation cannot interrupt it — withTimeoutOrNull would fire its
        // timer and then wait for the read anyway, so the timeout would not
        // take effect until the command finished on its own (measured: a 5s
        // timeout on `sleep 120` did not return). The only thing that unblocks
        // a pipe read is the writer going away, so the watchdog kills the
        // process and lets EOF end the loop naturally.
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val readEof = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(timeout)
            // [T-android-mcp-detached-daemon-hang] Do NOT gate this on
            // `process.isAlive`.
            //
            // A command may leave behind a deliberately detached daemon:
            // `minis-mcp-cli` forks one (setsid + its own session, fds 0/1/2
            // reopened on /dev/null) so later calls reuse warm MCP
            // connections. The daemon holds no pipe of its own — verified on
            // device — but proot stays ptrace-attached to it, and a tracer
            // cannot exit while its tracee lives. So proot remains alive
            // holding the host pipe's write end long after the command
            // printed its result and exited, and the blocking read below never
            // sees EOF.
            //
            // Measured on a Pixel 6 / Android 17: `minis-mcp-cli list` wrote
            // its complete output, yet the shell call was still running after
            // 90s under a 30s guest `timeout`, with proot showing
            // `TracerPid` = the surviving daemon.
            //
            // `process.isAlive` is TRUE in that state, so the old gate did let
            // this path run — but it is also the case that a dead process
            // still needs the stream closed, and closing is harmless either
            // way. Run the unblock path unconditionally once the deadline
            // passes rather than depending on which of the two shapes occurred.
            //
            // [T-android-fresh-exit-status-file] That completed command is now
            // finished by the completion monitor below, not by this deadline;
            // the watchdog only bounds a command that is genuinely still running.
            run {
                timedOut.set(true)
                // [T-android-fresh-exit-status-file] Kill the command's own
                // process group, not proot: proot may also be the tracer of a
                // daemon an earlier command detached, and killing it would
                // leave that daemon running untraced and broken. See
                // [killCommand].
                killCommand(process, readEof)
                Log.i(TAG, "[$sessionId] timeout after ${timeout / 1000}s, command killed")
            }
        }

        // [T-android-fresh-exit-status-file] Completion monitor. The runner
        // writes the status file once the command has exited; from then on
        // everything the command wrote is already in the pipe. If proot then
        // exits too, the read reaches EOF by itself and this does nothing. If
        // proot stays — it is still tracing a detached daemon, and its own
        // stdout IS this pipe — EOF never comes, so once the output has gone
        // quiet the read end is closed here and the call completes, leaving
        // proot (and the daemon) alone.
        val lastReadAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val closedOnStatus = java.util.concurrent.atomic.AtomicBoolean(false)
        val monitor = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            while (!statusFile.exists()) {
                if (readEof.get()) return@launch
                kotlinx.coroutines.delay(STATUS_POLL_MS)
            }
            val seenAt = System.currentTimeMillis()
            while (!readEof.get()) {
                val now = System.currentTimeMillis()
                if (outputSettled(now, seenAt, lastReadAt.get())) {
                    closedOnStatus.set(true)
                    runCatching { process.inputStream.close() }
                    Log.i(TAG, "[$sessionId] command exited; proot still tracing a detached process, left running")
                    return@launch
                }
                kotlinx.coroutines.delay(STATUS_POLL_MS)
            }
        }

        val exitCode = try {
            // [T-android-fresh-utf8-stream] Decode through a STATEFUL reader,
            // not per read(): an 8 KB read ends at an arbitrary byte offset, so
            // decoding each chunk on its own split any multibyte character on
            // the boundary into U+FFFD (a long CJK / emoji output came back
            // with replacement characters sprinkled through it). The reader's
            // decoder carries the incomplete tail into the next read. Closing
            // process.inputStream (watchdog / Stop) still unblocks it, since
            // the reader's read blocks in that same stream.
            val buf = CharArray(8192)
            val stream = java.io.InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                lastReadAt.set(System.currentTimeMillis())
                val text = String(buf, 0, n)
                output.append(text)
                lineCallback?.let { feedLines(text, it) }
            }
            readEof.set(true)
            resolveExitCode(process, statusFile, closedOnStatus.get())
        } catch (e: Exception) {
            // A read that fails because the watchdog, Stop or the completion
            // monitor closed the stream is the expected path, not an error.
            readEof.set(true)
            if (!timedOut.get() && !closedOnStatus.get() && !stopped) {
                Log.w(TAG, "[$sessionId] read failed: ${e.message}")
            }
            resolveExitCode(process, statusFile, closedOnStatus.get())
        } finally {
            watchdog.cancel()
            monitor.cancel()
        }

        // Stderr goes after stdout: separate pipes cannot be re-interleaved.
        // When proot was left running its stderr never reaches EOF either, so
        // do not spend the full wait on it.
        val stderrWait = if (closedOnStatus.get()) STDERR_SETTLE_MS else STDERR_DRAIN_WAIT_MS
        if (closedOnStatus.get()) runCatching { process.outputStream.close() }
        stderrDrain?.finish(stderrWait)?.takeIf { it.isNotEmpty() }?.let { err ->
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            output.append(err)
        }

        live = null
        statusHandle = null
        runCatching { statusFile.delete() }
        runCatching { File(statusFile.path + ".pid").delete() }

        if (stopped) {
            // A user-initiated Stop is not a timeout: it must not be reported
            // as 124, which the agent loop treats as "the command exceeded its
            // budget" and may retry.
            val partial = output.toString()
            val notice = "[Cancelled]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                CANCELLED_EXIT,
            )
        }

        if (timedOut.get()) {
            val partial = output.toString()
            val notice = "[Command timed out after ${timeout / 1000}s]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                124,
            )
        }

        Pair(output.toString(), exitCode)
    }

    /** Build the argv and environment, mirroring PersistentShell.startProcess. */
    private fun spawn(command: String, envVars: Map<String, String>, statusGuestPath: String): Process {
        val rootfsManager = RootfsManager.getInstance(context)
        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-r"); cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b"); cmd.add("$hostPath:$linuxPath")
        }
        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }
        // [T-android-fake-netlink] Same rtnetlink emulation the pooled shell
        // gets; the two paths must not disagree about whether the sandbox has
        // network interfaces.
        cmd.add("--fake-netlink")
        // [T-android-shell-fresh-process] Step 4: run the command as its own
        // process-group leader. No marker, no pgid reporting — the host does
        // not need to LEARN the group, because on the fresh path the guest
        // group leader is the direct child of the proot process we already
        // hold. It only needs the group to EXIST, so that killing reaches the
        // descendants that would otherwise keep the pipe open.
        cmd.addAll(ForegroundCommandGroup.wrapForFreshProcess(command, statusGuestPath))

        val debugOffload = com.openminis.app.BuildConfig.DEV_TOOLS
        val pb = ProcessBuilder(cmd)
        configureStdin(pb)
        // Separate in debug so proot's [native_offload] lines reach logcat
        // instead of the output — which is why execute() starts a StderrDrain
        // whenever SEPARATE_STDERR is set.
        pb.redirectErrorStream(!SEPARATE_STDERR)

        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        env["TERM"] = "dumb"
        env["PS1"] = ""
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        env["MINIS_CHAT_SESSION_ID"] = sessionId
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        // User-configured variables go in as real environment entries rather
        // than `export` lines written to a shared stdin — one more thing the
        // warm path had to do that this one does not.
        for ((k, v) in envVars) env[k] = v
        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }
        return pb.start()
    }

    /**
     * [T-android-shell-fresh-process] Step 5: cancel the running command.
     *
     * This is what the Stop button reaches. It reuses the same whole-tree kill
     * as the timeout path, because the failure it prevents is identical: a
     * SIGKILL to the proot process alone leaves traced guest descendants
     * running and holding the pipe open.
     *
     * Safe to call when nothing is running, and safe to call before [execute]
     * has spawned — [stopped] is checked immediately after the spawn so a
     * Stop racing a starting command still takes effect.
     */
    fun stop() {
        stopped = true
        val p = live ?: return
        // No isAlive gate: proot stays alive while it traces a detached
        // daemon, so "alive" says nothing about whether the command is.
        killCommand(p, readEof = null)
        Log.i(TAG, "[$sessionId] stopped by user, command killed")
    }

    /** The process currently running, or null between commands. */
    @Volatile private var live: Process? = null

    /** The running command's status file (its `.pid` sibling names the group). */
    @Volatile private var statusHandle: File? = null

    /** Set once [stop] is called; this instance runs one command only. */
    @Volatile private var stopped: Boolean = false

    /**
     * [T-android-fresh-exit-status-file] The command's exit code, once the
     * read has ended.
     *
     * The status file is authoritative: it is the COMMAND's status, written
     * by the runner after the command exited. proot's own exit value is only
     * the fallback for a command that never got that far (spawn failure, a
     * seccomp crash of the guest shell — whose 135/139 the seccomp fallback
     * keys on — or a group killed by a timeout or Stop).
     *
     * [T-android-mcp-detached-daemon-hang] Never an unbounded wait. When the
     * completion monitor closed the stream, proot is deliberately still
     * running — it is the tracer of a detached daemon, which breaks if its
     * tracer dies — so it is neither waited on nor killed.
     */
    private fun resolveExitCode(process: Process, statusFile: File, closedOnStatus: Boolean): Int {
        if (closedOnStatus) return readStatus(statusFile) ?: 0
        val exited = runCatching {
            process.waitFor(REAP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        val processExit = if (exited) runCatching { process.exitValue() }.getOrNull() else null
        return readStatus(statusFile) ?: processExit ?: 0
    }

    /** The runner's recorded exit status, or null when it never wrote one. */
    private fun readStatus(statusFile: File): Int? {
        // The runner's printf may still be mid-write when the file first
        // appears; a short retry covers that without ever blocking for long.
        repeat(3) {
            val v = runCatching { statusFile.readText().trim().toIntOrNull() }.getOrNull()
            if (v != null) return v
            if (!statusFile.exists()) return null
            Thread.sleep(10)
        }
        return null
    }

    /**
     * [T-android-fresh-exit-status-file] Kill the running command — its whole
     * process group — for a timeout or a Stop, and make sure the read ends.
     *
     * The group is named by the runner's pid file: setsid made the runner the
     * group leader, and proot does not virtualise pids, so the guest pid is
     * the host pid. `Os.kill` takes a negative pid as "this group", unlike
     * `Process.sendSignal`, which silently drops any pid <= 0. Everything the
     * command started in its own group dies with it; what it deliberately
     * detached into another session (a daemon) does not — and neither does
     * proot, which may still be that daemon's tracer. Killing proot here is
     * what used to leave `minis-mcp-cli`'s daemon running untraced, with every
     * later MCP call failing against it.
     *
     * If proot had nothing else to trace it exits on its own and the read
     * reaches EOF; otherwise the stream is closed after a short grace so the
     * call returns on time either way. Only when the group is unknown — Stop
     * or a timeout landing before the runner wrote its pid — does this fall
     * back to the old whole-process kill.
     */
    private fun killCommand(process: Process, readEof: java.util.concurrent.atomic.AtomicBoolean?) {
        val pgid = statusHandle?.let { readPgid(File(it.path + ".pid")) }
        if (pgid == null) {
            killProcessTree(process)
            runCatching { process.destroyForcibly() }
        } else {
            runCatching { android.system.Os.kill(-pgid, android.system.OsConstants.SIGKILL) }
            val deadline = System.currentTimeMillis() + KILL_SETTLE_MS
            while (readEof?.get() != true && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(STATUS_POLL_MS) }
            }
        }
        // Belt and braces: if anything still holds the write end, the read
        // must not wait on it. Closing makes a blocked read fail at once.
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    /** The runner's group id, or null if absent or implausible. */
    private fun readPgid(pidFile: File): Int? {
        val v = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull() ?: return null
        // 0 / 1 would mean "our own group" or init: never kill those.
        return if (v > 1) v else null
    }

    /** Drop status files a killed app left behind; they are tiny but unbounded. */
    private fun pruneStaleExitFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_EXIT_FILE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * [T-android-shell-fresh-process] Kill [process] and every descendant it
     * left behind in the guest.
     *
     * `Process.pid()` is the host pid of proot. Its guest children are
     * ordinary host processes too (proot traces rather than containerises), so
     * they are reachable by walking /proc for anything whose parent is that
     * pid, and recursing. That walk is the reliable part; a negative-pgid
     * signal is attempted first because it reaches the whole group in one call
     * when the platform honours it.
     *
     * SIGKILL rather than SIGTERM: this runs only after the command has
     * already missed its deadline, and a tree that ignored the clock is not
     * owed a chance to ignore a polite signal too. Work deliberately detached
     * with its own `setsid`/`nohup` has left this group and is untouched —
     * verified on device for the warm path, and the mechanism is identical.
     */
    private fun killProcessTree(process: Process) {
        // `Process.pid()` is Java 9+ and is NOT on Android's API 26 baseline,
        // so read the pid the way Android has always exposed it: the private
        // `pid` field on the platform's ProcessImpl. Defensive throughout — a
        // failure to identify the pid must degrade to "kill only the process
        // we hold", never mask the timeout.
        val pid: Int = runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(process)
        }.getOrNull() ?: return
        // One shot at the whole group. Harmless if unsupported.
        //
        // [T-android-mcp-detached-daemon-hang] On Android 17 this is the ONLY
        // part that can work. /proc is mounted `hidepid=invisible` there (it
        // was `hidepid=2` on Android 13), so an app cannot see even its own
        // descendants' /proc entries: the walk below returns an empty list and
        // the whole sweep silently does nothing. Verified on a Pixel 6 —
        // logcat printed "process tree killed" while proot and every guest
        // child were still running 6 minutes later.
        //
        // The group kill does not depend on /proc being readable, so it stays
        // the primary mechanism; the walk is a best-effort supplement for
        // platforms that still expose the tree.
        runCatching { android.os.Process.sendSignal(-pid, 9) }
        // Then the walk, which does not depend on pgid semantics holding.
        for (child in descendantsOf(pid)) {
            runCatching { android.os.Process.sendSignal(child, 9) }
        }
    }

    /** Host pids whose ancestry reaches [root], nearest-last. */
    private fun descendantsOf(root: Int, depth: Int = 0): List<Int> {
        if (depth > MAX_TREE_DEPTH) return emptyList()
        val out = mutableListOf<Int>()
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return out
        for (e in entries) {
            val childPid = e.name.toIntOrNull() ?: continue
            val ppid = runCatching {
                // /proc/<pid>/stat field 4 is the parent pid. The comm field
                // can contain spaces and parentheses, so split after the last
                // ')' rather than tokenising the whole line.
                val raw = File(e, "stat").readText()
                raw.substring(raw.lastIndexOf(')') + 2).split(' ')[1].toInt()
            }.getOrNull() ?: continue
            if (ppid == root) {
                out += descendantsOf(childPid, depth + 1)
                out += childPid
            }
        }
        return out
    }

    private var lineBuffer = StringBuilder()

    /** Emit whole lines to [callback], holding a partial trailing line. */
    private fun feedLines(text: String, callback: (String) -> Unit) {
        lineBuffer.append(text)
        var idx = lineBuffer.indexOf("\n")
        while (idx >= 0) {
            callback(lineBuffer.substring(0, idx))
            lineBuffer.delete(0, idx + 1)
            idx = lineBuffer.indexOf("\n")
        }
    }

    companion object {
        /**
         * [T-android-shell-process-budget] Leads the output when the process
         * could not be spawned at all - nothing ran, so GlobalShellThrottle
         * may queue the command and retry it.
         */
        const val SPAWN_FAILED_PREFIX = "[Failed to start shell:"

        /**
         * [T-android-parity-fixes] stdin reads /dev/null, so a command that
         * reads input gets EOF at once instead of waiting forever.
         *
         * ProcessBuilder's default stdin is a pipe to this process, and
         * nothing ever writes to it or closes it: `cat`, `read`, a bare
         * `python3` or an interactive prompt sat blocked until the timeout
         * (15 min by default), with no output to explain why. Unattended runs
         * (a scheduled task's prefilled command) had no one to notice at all.
         * iOS gives the command an immediate EOF; this matches it. Set on the
         * proot process itself, so every guest process inherits it.
         */
        internal fun configureStdin(pb: ProcessBuilder): ProcessBuilder =
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        private const val TAG = "FreshProcessShell"

        /** Debug builds keep stderr on its own pipe (drained by [StderrDrain]). */
        private val SEPARATE_STDERR = com.openminis.app.BuildConfig.DEV_TOOLS

        /** How long to let the stderr drain reach EOF after stdout has. */
        private const val STDERR_DRAIN_WAIT_MS = 2_000L

        /**
         * Depth limit for the descendant walk. A guest tree this deep is
         * pathological, and an unbounded recursion over /proc during a
         * timeout is a worse failure than missing a great-great-grandchild.
         */
        private const val MAX_TREE_DEPTH = 8

        /**
         * [T-android-mcp-detached-daemon-hang] How long to wait for proot to
         * exit after its output stream reached EOF (the pipe closing and the
         * process exiting are microseconds apart), so a wait is never unbounded.
         */
        private const val REAP_GRACE_MS = 2_000L

        /**
         * [T-android-fresh-exit-status-file] Directory under the rootfs (the
         * guest's "/") where the runner writes each command's status and pid.
         */
        const val EXIT_DIR_NAME = ".minis-exit"

        /** How often the completion monitor looks for the status file. */
        const val STATUS_POLL_MS = 25L

        /**
         * How long the output must have been silent — measured from both the
         * status file appearing and the last byte read — before the stream is
         * closed on a proot that stays alive. The command has already exited
         * when the status appears, so its output is already in the pipe; this
         * only lets the reader thread drain it.
         */
        const val OUTPUT_QUIET_MS = 150L

        /** Hard cap on that drain, for a detached process that keeps writing. */
        const val OUTPUT_DRAIN_CAP_MS = 2_000L

        /** Stderr wait when proot was left running (its EOF never comes). */
        private const val STDERR_SETTLE_MS = 200L

        /** After a group kill, how long to let proot exit before closing streams. */
        private const val KILL_SETTLE_MS = 1_000L

        /** Status files older than this belong to a process that died mid-command. */
        private const val STALE_EXIT_FILE_MS = 60 * 60_000L

        /**
         * [T-android-fresh-exit-status-file] Whether the stream may be closed
         * on a command whose status file appeared at [statusSeenAt]: its
         * output has been quiet for [OUTPUT_QUIET_MS], or [OUTPUT_DRAIN_CAP_MS]
         * has passed since the status appeared, whichever comes first.
         */
        fun outputSettled(now: Long, statusSeenAt: Long, lastReadAt: Long): Boolean {
            if (now - statusSeenAt >= OUTPUT_DRAIN_CAP_MS) return true
            return now - statusSeenAt >= OUTPUT_QUIET_MS && now - lastReadAt >= OUTPUT_QUIET_MS
        }

        /**
         * Exit code for a user-initiated Stop. Distinct from 124 (timeout) so
         * the agent loop can tell "you cancelled this" from "this ran too
         * long", which it retries differently.
         */
        const val CANCELLED_EXIT = 130
    }
}
