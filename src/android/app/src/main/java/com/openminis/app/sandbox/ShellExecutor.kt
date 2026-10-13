package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.StreamSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.openminis.app.service.ActiveRunContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Executes shell commands inside the PRoot sandbox via ProcessBuilder.
 * Corresponds to iOS ISHShellExecutor.
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 600_000L // 10 minutes

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    /** The currently running process, if any. Can be destroyed to stop execution. */
    @Volatile
    var currentProcess: Process? = null
        private set

    /**
     * Execute a command inside the PRoot sandbox.
     *
     * @param context Android context for resolving PROOT_TMP_DIR
     * @param command Shell command string to execute
     * @param timeout Timeout in milliseconds (default 10 minutes). May
     *   SHORTEN the armed wall clock, never extend it: armed =
     *   min(timeout, class wall). [T-timeout-honored] The old code ignored
     *   this parameter outright and logged "caller timeout ignored" — a
     *   60s request then rode the full 1200s BATCH wall.
     * @param environment Additional environment variables
     * @param lineCallback Called for each line of output (on IO dispatcher)
     * @param resourceClass Caller's `resource_class`. [T-resource-class-threaded]
     *   Must be forwarded: the 1-arg `classify(command)` overload silently
     *   degrades `heavy` to AUTO (the parameter has a default, so dropping it
     *   compiles), and the tighter of two disagreeing layers is what actually
     *   arms. See the matching note in ExecutionCoordinator.
     * @return ShellResult with combined stdout+stderr, exit code, and duration
     */
    suspend fun execute(
        context: Context,
        command: String,
        timeout: Long = DEFAULT_TIMEOUT_MS,
        environment: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null,
        resourceClass: SandboxResourceGate.ResourceClass = SandboxResourceGate.ResourceClass.AUTO,
    ): ShellResult = withContext(Dispatchers.IO) {
        check(PRootKernel.isBooted) { "PRootKernel must be booted before executing commands" }

        val budget = BudgetClassifier.classify(command, resourceClass)
        // [T-timeout-honored] The caller may shorten the class wall but never
        // extend it, and the setup floor survives.
        val armed = BudgetClassifier.armMs(budget, command, timeout)
        if (timeout > 0L && armed < budget.wallMs) {
            Log.i(TAG, "caller timeout ${timeout}ms shortens class ${budget.workClass} wall ${budget.wallMs}ms → ${armed}ms")
        }
        val first = runOnce(
            context, command, armed, environment, lineCallback, noSeccomp = false,
            outputCapBytes = budget.outputCapBytes,
            outputRateBytesPerSec = budget.outputRateBytesPerSec,
        )

        // [T-android-seccomp-selfheal / GH#186] If the child died on an early
        // fatal signal with no output at all, the host kernel's seccomp fast
        // path is the prime suspect — retry once without it. Gated hard so a
        // normal failing command is never re-run; see SeccompFallbackPolicy.
        if (!SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = first.exitCode,
                durationMs = first.durationMs,
                producedOutput = first.output.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            return@withContext first
        }

        com.openminis.app.logging.AppLogger.warning(
            TAG,
            SeccompFallbackPolicy.retryLogLine(first.exitCode, first.durationMs, "shell command"),
        )
        // The retry deliberately reuses the caller's lineCallback: the first
        // attempt produced no output (that is a precondition of retrying), so
        // there is nothing to duplicate.
        val retried = runOnce(
            context, command, armed, environment, lineCallback, noSeccomp = true,
            outputCapBytes = budget.outputCapBytes,
            outputRateBytesPerSec = budget.outputRateBytesPerSec,
        )
        if (retried.exitCode == 0) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                "[proot-retry] succeeded with ${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — " +
                    "this device needs the seccomp workaround (GH#186)",
            )
        }
        // Whatever the retry returned is what the caller sees: if it failed
        // too, the error surfaces normally rather than being swallowed.
        retried
    }

    /**
     * One proot invocation. Split out of [execute] so the GH#186 seccomp
     * fallback can re-run the identical command instead of duplicating the
     * spawn/plumbing logic.
     */
    private suspend fun runOnce(
        context: Context,
        command: String,
        timeout: Long,
        environment: Map<String, String>,
        lineCallback: ((String) -> Unit)?,
        noSeccomp: Boolean,
        outputCapBytes: Long = BudgetClassifier.normal().outputCapBytes,
        outputRateBytesPerSec: Long = BudgetClassifier.normal().outputRateBytesPerSec,
    ): ShellResult = withContext(Dispatchers.IO) {
        val prootCommand = PRootKernel.buildProotCommand(command)

        Log.d(TAG, "Executing: $command")

        val startTime = System.currentTimeMillis()

        val processBuilder = ProcessBuilder(prootCommand)
        processBuilder.redirectErrorStream(true)

        // Set required environment for PRoot
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

        // Apply custom environment from PRootKernel
        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }

        // Apply per-call environment overrides
        for ((key, value) in environment) {
            env[key] = value
        }

        // [T-android-seccomp-selfheal / GH#186] Applied last so nothing above
        // can clobber it on the retry attempt.
        if (noSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        val output = BoundedOutputBuffer()
        var exitCode = -1

        // Keep the Process in a local. withTimeout cancels the block and runs
        // its finally before the catch, so clearing currentProcess there made
        // destroyForcibly a no-op and left the timed-out proot alive.
        var started: Process? = null
        var activeRun: com.openminis.app.service.ActiveRun? = null
        try {
            val process = processBuilder.start()
            started = process
            currentProcess = process
            SandboxWorkload.track(process, "oneshot")
            activeRun = ActiveRunContext.current()
            activeRun?.registerProcess(process)
            SandboxWorkload.armDeadline(process, timeout)
            withTimeout(timeout) {
                    coroutineScope {
                    // Read raw chars to preserve \r for TerminalSanitizer CR-folding.
                    // readLine() would consume \r as line terminator, losing progress overwrites.
                    // The chat card only sees lineCallback, so a CR-only meter used to
                    // look frozen. Emit the latest CR line at most once a second, and
                    // speak if the guest produces no visible output at all.
                    val sink = StreamSink(outputCapBytes, outputRateBytesPerSec)
                    val beat = SilentOutputHeartbeat()
                    val callbackLock = Any()
                    val beatJob = if (lineCallback != null) {
                        launchSilentHeartbeat(beat, { true }) { line ->
                            synchronized(callbackLock) { lineCallback.invoke(line) }
                        }
                    } else {
                        null
                    }
                    try {
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        var lastLineForCallback = StringBuilder()
                        var lastProgressAt = 0L
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            output.append(buf, 0, n)
                            val wait = sink.writeWaitMs(n)
                            val room = sink.append(buf, 0, n)
                            // Feed lines to callback for UI updates. The sink
                            // rate is the producer brake; the callback still
                            // sees lines already read.
                            if (lineCallback != null) {
                                for (i in 0 until n) {
                                    val c = buf[i]
                                    if (c == '\n' || c == '\r') {
                                        val now = System.currentTimeMillis()
                                        beat.onOutput(now)
                                        val line = lastLineForCallback.toString()
                                        lastLineForCallback.clear()
                                        if (line.isNotEmpty() && (c == '\n' || now - lastProgressAt >= 1_000L)) {
                                            lastProgressAt = now
                                            synchronized(callbackLock) { lineCallback.invoke(line) }
                                        }
                                    } else if (lastLineForCallback.length < BoundedOutputBuffer.MAX_LINE_CHARS) {
                                        lastLineForCallback.append(c)
                                    }
                                }
                            }
                            if (!room || !sink.canRead()) {
                                // [T-output-cap-vs-backpressure] Two distinct
                                // brakes downstream, deliberately different:
                                // the rate brake above (`wait > 0` → delay and
                                // keep reading) is backpressure; THIS break is
                                // the output CAP — a terminal condition where
                                // the read loop ends for this command, the
                                // guest blocks in write, and the full accepted
                                // buffer is still returned via
                                // StreamSink.snapshot(). Not a bug to "fix"
                                // into a wait.
                                Log.w(TAG, "output cap hit; stopping read so the guest blocks")
                                break
                            }
                            if (wait > 0L) delay(wait)
                        }
                        if (lineCallback != null && lastLineForCallback.isNotEmpty()) {
                            beat.onOutput()
                            synchronized(callbackLock) { lineCallback.invoke(lastLineForCallback.toString()) }
                        }
                    }

                    exitCode = process.waitFor()
                    } finally {
                        beatJob?.cancel()
                    }
                    }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Command timed out after ${timeout}ms: $command")
            SandboxWorkload.release(started, kill = true, reason = "oneshot-timeout")
            output.appendLine("\n[Command timed out after ${timeout / 1000}s; guest process group killed]")
            exitCode = 124 // Standard timeout exit code
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (started != null && activeRun?.hasProcess(started) == true) {
                // ActiveRun.stop() already killed the exact owned process tree.
            } else {
                SandboxWorkload.release(started, kill = true, reason = "oneshot-cancel")
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            SandboxWorkload.release(started, kill = true, reason = "oneshot-error")
            output.appendLine("\n[Error: ${e.message}]")
            exitCode = -1
        } finally {
            if (currentProcess === started) currentProcess = null
            if (started != null) activeRun?.unregisterProcess(started)
            SandboxWorkload.release(started, kill = false, reason = "oneshot-done")
        }

        val durationMs = System.currentTimeMillis() - startTime
        if (output.truncated) Log.w(TAG, "output truncated, dropped ${output.dropped} chars")
        Log.d(TAG, "Command completed in ${durationMs}ms with exit code $exitCode")

        ShellResult(
            output = output.toString().trimEnd(),
            exitCode = exitCode,
            durationMs = durationMs
        )
    }

    /**
     * Forcibly destroy the currently running process.
     */
    fun destroyCurrent() {
        currentProcess?.let { process ->
            Log.i(TAG, "Destroying current process")
            SandboxWorkload.release(process, kill = true, reason = "destroyCurrent")
            currentProcess = null
        }
    }
}
