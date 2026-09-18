package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * A long-running PRoot shell process that persists across commands.
 * Agent commands are sent via stdin and output is captured using unique
 * end-of-command markers, similar to iOS ISHKernel.executeCommandAndWait.
 *
 * This ensures that environment variables, working directory, installed
 * packages, and running services all persist between commands within the
 * same session.
 */
class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath
) {

    companion object {
        private const val TAG = "PersistentShell"

        /**
         * [T-android-shell-death-diagnosability] Death-capture windows. The
         * head must comfortably hold proot's error line(s) printed BEFORE the
         * multi-KB talloc leak dump; the tail shows how the output ended.
         */
        private const val OUTPUT_HEAD_MAX = 1024
        private const val OUTPUT_TAIL_MAX = 2048
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    @Volatile
    private var stdoutStream: java.io.InputStream? = null

    private val isStarting = AtomicBoolean(false)

    /** Pending command callback — only one command at a time. */
    @Volatile
    private var pendingCallback: CommandCallback? = null

    /**
     * [OpenMinis-Fix #7] Stdout captured when a command is cancelled or the
     * shell is killed mid-flight. [takeInterruptedOutput] is destructive.
     */
    @Volatile
    private var interruptedOutput: String = ""

    fun takeInterruptedOutput(): String {
        val s = interruptedOutput
        interruptedOutput = ""
        return s
    }

    private fun stashInterruptedOutput(text: String) {
        if (text.isNotEmpty() && text.length >= interruptedOutput.length) {
            interruptedOutput = text
        }
    }

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** [diag] Read back the mount this shell was started with (frozen at boot). */
    fun debugBindMount(linuxPath: String): String? = sessionBindMounts[linuxPath]

    private class CommandCallback(
        val marker: String,
        val output: StringBuilder = StringBuilder(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int) -> Unit)? = null,
    )

    /**
     * Ensure the persistent shell process is running.
     * Idempotent — returns immediately if already alive.
     */
    suspend fun ensureStarted() {
        if (isAlive) return
        if (!isStarting.compareAndSet(false, true)) {
            // Another coroutine is already starting — wait for it
            while (isStarting.get() && !isAlive) {
                kotlinx.coroutines.delay(50)
            }
            return
        }

        try {
            withContext(Dispatchers.IO) {
                // [T-android-seccomp-selfheal / GH#186] The guest /bin/sh is
                // itself a dynamically linked binary, so on an affected kernel
                // it can die during dynamic linking before it ever reads a
                // command — the shell simply never comes up, which surfaces to
                // the user as "[Shell not running] (exit code: -1)". Give it
                // one retry with PROOT_NO_SECCOMP=1 under the same narrow
                // conditions used for one-shot commands.
                //
                // Timed around startProcess() (which sleeps 200ms before
                // returning): if the shell is already dead when it returns, it
                // died during that window, i.e. essentially instantly.
                val spawnedAt = System.currentTimeMillis()
                startProcess()
                val aliveMs = System.currentTimeMillis() - spawnedAt

                if (!useNoSeccomp && !isAlive) {
                    val exit = runCatching { process?.exitValue() }.getOrNull() ?: lastExitCode
                    if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                            exitCode = exit,
                            durationMs = aliveMs,
                            producedOutput = false,
                            alreadyRetried = false,
                        )
                    ) {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            SeccompFallbackPolicy.retryLogLine(exit, aliveMs, "persistent shell startup"),
                        )
                        // Sticky for this shell's lifetime: every later respawn
                        // keeps the workaround instead of rediscovering it.
                        useNoSeccomp = true
                        PRootKernel.stickyNoSeccomp = true
                        startProcess()
                        if (isAlive) {
                            com.openminis.app.logging.AppLogger.warning(
                                TAG,
                                "[proot-retry] persistent shell came up with " +
                                    "${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — this device " +
                                    "needs the seccomp workaround (GH#186)",
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // [T-android-shell-spawn-outcome] Previously swallowed: the only
            // handler here was `finally { isStarting.set(false) }`, so a throw
            // out of startProcess() (ProcessBuilder.start() IOException —
            // ENOENT on the proot binary, EACCES, EAGAIN under memory
            // pressure) unwound with NOTHING written anywhere. The caller then
            // saw isAlive == false and reported the generic "[Shell not
            // running]", which is exactly the dead end the field reports keep
            // hitting. Record it and rethrow — swallowing the cause is what
            // made this class of failure undiagnosable.
            com.openminis.app.logging.AppLogger.error(
                TAG,
                "spawn threw: ${t.javaClass.simpleName}: ${t.message}",
            )
            throw t
        } finally {
            isStarting.set(false)
        }
    }

    /**
     * [T-android-seccomp-selfheal / GH#186] Once the seccomp workaround proves
     * necessary on this device, keep it for every subsequent respawn of this
     * shell. Without stickiness the shell would crash-and-retry on every
     * restart, doubling startup latency for exactly the users already hitting
     * the bug.
     */
    @Volatile
    private var useNoSeccomp = false

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")

        val rootfsManager = RootfsManager.getInstance(context)

        // [T-android-shell-death-diagnosability] One-line spawn context in the
        // FILE log. Field reports of "[Shell not running] (exit code: -1)" have
        // had two distinct causes already (missing proot ELF loaders after
        // bc2566b2, and at least one custom-ROM death we couldn't classify),
        // and a user log that only says "process exited" cannot tell them
        // apart. Loader presence is the first thing to rule out: without
        // PROOT_LOADER, proot bare-execve's rootfs busybox and SELinux kills
        // it on Android 10+ (see a25d93f7).
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn ctx: proot=${rootfsManager.prootBinary.exists()} " +
                "rootfs=${File(rootfsManager.rootfsDir, "bin/busybox").exists()} " +
                "loader64=${PRootKernel.prootLoaderPath.isNotEmpty()} " +
                "loader32=${PRootKernel.prootLoader32Path.isNotEmpty()}",
        )
        // Fresh capture per process incarnation — a respawned shell must not
        // report its predecessor's dying output as its own.
        synchronized(outputTail) {
            outputHead.setLength(0)
            outputTail.setLength(0)
            outputTotal = 0
        }
        lastExitCode = null

        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // Apply this session's bind mounts (session-specific, not global)
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
        }

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        cmd.add("/bin/sh")

        val debugOffload = com.openminis.app.BuildConfig.DEBUG

        val processBuilder = ProcessBuilder(cmd)
        // In debug builds we want proot's native_offload stderr logs in
        // logcat, not merged into shell stdout (which would break the
        // __MINIS_DONE__ marker detection). Release keeps the original
        // merged behavior so no stderr output is lost silently.
        processBuilder.redirectErrorStream(!debugOffload)

        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        if (PRootKernel.prootLoaderPath.isNotEmpty()) {
            env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        }
        if (PRootKernel.prootLoader32Path.isNotEmpty()) {
            env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        }
        env["TERM"] = "dumb"
        env["PS1"] = ""  // Suppress prompt to avoid polluting output
        // Timezone: customEnvironment["TZ"] is seeded at PRootKernel.boot(),
        // but refresh it here in case the system timezone changed between boot
        // and now.
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        // T340: forward the chat session id to native_offload handlers via
        // proot env. NativeOffloadServer reads this off `request.env` and
        // hands it to OffloadPermissionManager so ASK_ONCE grants/denials
        // are scoped per chat session, not globally.
        env["MINIS_CHAT_SESSION_ID"] = sessionId

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }

        // [T-android-seccomp-selfheal / GH#186] Applied last so nothing above
        // can clobber it once this device is known to need it.
        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        val p = processBuilder.start()
        process = p
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        stdoutStream = p.inputStream

        // Start background reader thread
        Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        // In debug, drain stderr separately into logcat.
        if (debugOffload) {
            Thread({
                val br = p.errorStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    for (line in br.lineSequence()) Log.d("PRootStderr", line)
                } catch (_: Exception) {}
            }, "PersistentShell-stderr").apply {
                isDaemon = true
                start()
            }
        }

        // Wait briefly for shell to initialize
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {}

        // [T-android-shell-spawn-outcome] Record the OUTCOME of the spawn, not
        // just that one was attempted. "Starting persistent shell process"
        // followed by silence is the signature every field report has, and it
        // cannot distinguish "proot is running fine" from "proot exited during
        // the 200ms window" — the two cases that lead to completely different
        // investigations. Logged to the FILE log (AppLogger, not Log.i) because
        // that is what a user can actually send us; logcat is gone by then.
        val alive = isAlive
        val exit = runCatching { process?.exitValue() }.getOrNull()
        val early = synchronized(outputTail) { outputHead.toString().take(300) }
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn outcome: alive=$alive" +
                (if (exit != null) " exit=$exit" else "") +
                " loader=${PRootKernel.prootLoaderPath.isNotEmpty()}" +
                " noSeccomp=$useNoSeccomp" +
                (if (early.isNotEmpty()) " early=${early.replace('\n', '|')}" else ""),
        )
        Log.i(TAG, "Persistent shell started")
    }

    /**
     * [T-android-shell-death-diagnosability] Captured shell output for
     * premature-death reporting, including chunks that arrive with no pending
     * callback — which readLoop previously discarded outright. In release
     * builds stderr is merged into stdout (redirectErrorStream), so proot's
     * dying words land exactly in that discarded window.
     *
     * HEAD + rolling TAIL, not tail-only. The first crDroid field log proved
     * tail-only insufficient: proot prints its one-line cause FIRST ("proot
     * error: …") and then talloc_enable_leak_report() dumps a multi-KB
     * allocation tree at exit — which evicted the cause and left us staring at
     * HandlerEntry leak rows. The head is where the answer lives; the tail
     * still shows how it ended.
     */
    private val outputHead = StringBuilder()
    private val outputTail = StringBuilder()
    private var outputTotal = 0

    private fun appendTail(text: String) {
        synchronized(outputTail) {
            outputTotal += text.length
            if (outputHead.length < OUTPUT_HEAD_MAX) {
                outputHead.append(text.take(OUTPUT_HEAD_MAX - outputHead.length))
            }
            outputTail.append(text)
            val over = outputTail.length - OUTPUT_TAIL_MAX
            if (over > 0) outputTail.delete(0, over)
        }
    }

    /**
     * Captured output (trimmed) for premature-death reporting: the head (where
     * proot's error line lives), plus the tail when output outgrew the head
     * window, with the elided middle marked.
     */
    fun deathTail(): String = synchronized(outputTail) {
        val head = outputHead.toString().trim()
        if (outputTotal <= OUTPUT_HEAD_MAX) return head // head holds everything
        // Tail buffer starts at byte (outputTotal - tail.length); the head
        // covers [0, OUTPUT_HEAD_MAX). Drop any overlap from the tail so the
        // two windows concatenate without duplication.
        val overlap = OUTPUT_HEAD_MAX - (outputTotal - outputTail.length)
        val tail = outputTail.substring(overlap.coerceIn(0, outputTail.length)).trim()
        if (tail.isEmpty()) return head
        val elided = (-overlap).coerceAtLeast(0)
        val sep = if (elided > 0) "\n…[$elided bytes elided]…\n" else "\n"
        "$head$sep$tail"
    }

    /** Exit code of the dead shell process, when known. */
    @Volatile
    var lastExitCode: Int? = null
        private set

    private fun readLoop(p: Process) {
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val text = String(buffer, 0, n, StandardCharsets.UTF_8)
                appendTail(text)

                val cb = pendingCallback
                if (cb != null) {
                    // Check if this chunk contains the end marker
                    val markerExitPattern = "__MINIS_DONE_${cb.marker}_EXIT_"
                    val markerIdx = text.indexOf(markerExitPattern)

                    if (markerIdx >= 0) {
                        // Extract output before marker
                        val beforeMarker = text.substring(0, markerIdx)
                        cb.output.append(beforeMarker)
                        if (cb.lineCallback != null) {
                            feedLines(beforeMarker, cb.lineCallback)
                        }

                        // Extract exit code from marker line
                        val afterMarker = text.substring(markerIdx)
                        val exitCode = parseExitCode(afterMarker, cb.marker)

                        // Signal completion
                        cb.onComplete?.invoke(cb.output.toString(), exitCode)
                        pendingCallback = null
                    } else {
                        cb.output.append(text)
                        if (cb.lineCallback != null) {
                            feedLines(text, cb.lineCallback)
                        }
                    }
                }
                // If no pending callback, discard (shell prompt noise etc.)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // Process exited
        val cb = pendingCallback
        if (cb != null) {
            cb.onComplete?.invoke(cb.output.toString(), -1)
            pendingCallback = null
        }

        // [T-android-shell-death-diagnosability] Name the cause in the FILE
        // log. exitValue distinguishes death classes (signal deaths are
        // 128+n: 132=SIGILL, 139=SIGSEGV, 159=SIGSYS/seccomp), and the tail
        // carries proot's own error line in release builds. Without these,
        // a field log reads "started → exited" 25ms apart and is
        // unactionable — precisely the shape of the crDroid report.
        val exit = runCatching { p.waitFor() }.getOrNull()
        lastExitCode = exit
        val tail = deathTail()
        com.openminis.app.logging.AppLogger.error(
            TAG,
            "shell process exited code=$exit" +
                (if (tail.isNotEmpty()) " capture=${tail.take(1200)}" else " capture=(no output)"),
        )

        process = null
        stdinWriter = null
        Log.i(TAG, "Persistent shell process exited")
    }

    private fun feedLines(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i].replace("\r", "")
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                callback(line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates
                callback(line)
            }
        }
    }

    private fun parseExitCode(text: String, marker: String): Int {
        // Pattern: __MINIS_DONE_{marker}_EXIT_{code}__
        val regex = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * Execute a command in the persistent shell and wait for completion.
     *
     * Wraps the command with a unique marker to detect output boundaries:
     *   {command}; echo "__MINIS_DONE_{marker}_EXIT_$?__"
     *
     * @return Pair of (output, exitCode)
     */
    suspend fun executeCommand(
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> {
        ensureStarted()

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            // [T-android-shell-death-diagnosability] Surface WHY the shell is
            // down, not just that it is. The exit code and proot's own last
            // words (captured by outputTail; stderr is merged into stdout in
            // release) turn "[Shell not running]" from a dead end into a
            // self-describing report — both for the agent reading the tool
            // result and for the screenshot a user sends us.
            val exit = lastExitCode
            val tail = deathTail()
            val detail = buildString {
                append("[Shell not running]")
                if (exit != null) append(" proot exit=$exit")
                if (tail.isNotEmpty()) append("\n${tail.take(300)}")
            }
            return Pair(detail, -1)
        }

        val marker = UUID.randomUUID().toString().take(8)
        val wrappedCommand = "$command\necho \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n"

        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { cont ->
                    val cb = CommandCallback(
                        marker = marker,
                        lineCallback = lineCallback,
                    )
                    cb.onComplete = { output, exitCode ->
                        if (cont.isActive) {
                            cont.resume(Pair(output, exitCode))
                        }
                    }
                    pendingCallback = cb

                    cont.invokeOnCancellation {
                        val cb = pendingCallback
                        pendingCallback = null
                        stashInterruptedOutput(cb?.output?.toString().orEmpty())
                    }

                    try {
                        writer.write(wrappedCommand)
                        writer.flush()
                    } catch (e: Exception) {
                        pendingCallback = null
                        if (cont.isActive) {
                            cont.resume(Pair("[Write error: ${e.message}]", -1))
                        }
                    }
                }
            }

            if (result == null) {
                // Timeout (GH#358). The command is still running inside the
                // persistent shell's /bin/sh, and that shell will not read the
                // next command off stdin until the current one finishes. Merely
                // dropping the callback therefore leaves every later command
                // queued behind a process that may never exit — the session's
                // shell execution is dead until it is recreated. Tear the shell
                // down here so the next execute() gets a fresh one;
                // ExecutionCoordinator re-injects the env-var snapshot on it.
                Log.w(TAG, "Command timed out after ${timeout}ms — killing shell so the next command gets a clean channel")
                pendingCallback = null
                stop()
                Pair("[Command timed out after ${timeout / 1000}s]", 124)
            } else {
                result
            }
        }
    }

    /**
     * Apply environment variables to the running shell.
     *
     * The shell is long-lived and reused across commands, so a stale `export`
     * from a previous turn lingers until something overwrites it. Caller can
     * supply [previousKeys] — the set of variable names injected on the prior
     * call — so any name absent from the new [envVars] is `unset` first. That
     * gives whole-snapshot semantics matching the per-command isolation iOS
     * gets for free with one `/bin/sh` process per command.
     *
     * Empty default for [previousKeys] preserves the original behaviour for
     * system broadcasts (TZ, proxy) that are only meant to overlay specific
     * keys, never wipe the user's env-var snapshot.
     */
    suspend fun applyEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        if (!isAlive) return
        val writer = stdinWriter ?: return
        withContext(Dispatchers.IO) {
            try {
                for (key in previousKeys - envVars.keys) {
                    writer.write("unset $key\n")
                }
                for ((key, value) in envVars) {
                    // Escape single quotes in values
                    val escaped = value.replace("'", "'\\''")
                    writer.write("export $key='$escaped'\n")
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply env vars: ${e.message}")
            }
        }
    }

    /**
     * Stop the persistent shell.
     *
     * GH#358: on timeout the reader thread was left blocking forever on
     * `InputStream.read()` because nothing closed the stdout stream.  Close
     * both stdin and stdout here so the readLoop unblocks, then kill the
     * process — the reader will see EOF / broken pipe and fall through to the
     * "process exited" path which cleans up pending callbacks.
     */
    fun stop() {
        try { stdoutStream?.close() } catch (_: Exception) {}
        stdoutStream = null
        try { stdinWriter?.close() } catch (_: Exception) {}
        stdinWriter = null
        process?.destroyForcibly()
        process = null
        pendingCallback?.let {
            stashInterruptedOutput(it.output.toString())
            it.onComplete?.invoke(it.output.toString(), -1)
        }
        pendingCallback = null
        Log.i(TAG, "Persistent shell stopped")
    }
}
