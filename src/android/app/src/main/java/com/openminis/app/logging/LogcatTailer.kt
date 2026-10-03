package com.openminis.app.logging

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Spawns `logcat --pid=<self>` as a child process and streams its output line
 * by line to a sink callback. This is how we capture `Log.d/i/w/e/v` calls —
 * they go through liblog.so directly, bypassing JVM stdout/stderr, so the
 * `System.setOut` redirection in [AppLogger] cannot see them.
 *
 * Reading our own PID requires no special permission on Android 8+ (READ_LOGS
 * is only needed to read other apps' logs). Tested on Pixel 4a (API 33) and
 * Pixel 6 (API 34) — works in DEBUG builds without any manifest changes.
 */
internal class LogcatTailer(
    /**
     * [T-android-log-level] Lowest logcat priority captured for tags not
     * silenced below: 'V' (everything) at Verbose, 'I' at Info (default), which
     * drops framework/library Log.d/Log.v chatter.
     */
    private val minPriority: Char = 'V',
    private val sink: (String) -> Unit,
) {

    private var process: java.lang.Process? = null
    private var thread: Thread? = null
    @Volatile private var stopping: Boolean = false

    @Synchronized
    fun start() {
        if (process != null) return
        stopping = false
        try {
            // -v time     : "MM-DD HH:MM:SS.mmm L/Tag(pid): message" format
            // -T 1        : start from the most recent line (skip backlog)
            // --pid=<self>: only this process — avoids leaking other apps'
            //               logs and keeps volume manageable
            //
            // [T-android-log-noise] silence high-volume framework tags whose
            // output never aids diagnosis. Measured on a 117 MB daily log,
            // these three tags alone accounted for ~76% of all lines:
            //   - View                 : Android 14+ dVRR emits
            //     setRequestedFrameRate=NaN/-4.0 every dispatchDraw, ~280k
            //     lines/day in active sessions (64% of total).
            //   - ViewRootImpl         : dVRR companion + per-frame
            //     setFrameRateCategory thermal hints (~20k lines).
            //   - BLASTBufferQueue_Java: surface-buffer queue admin, every
            //     frame committed (~3k lines).
            // Per-tag silencing via `TagName:S` and a wildcard fallthrough
            // (`*:V`) restricts the suppression to those three tags only —
            // all other framework warnings/errors still flow through, and
            // app-emitted `Minis.<category>` lines (AppLogger) are
            // unaffected. Doing this at the logcat command line keeps the
            // bytes out of the JNI stream → the LogcatTailer reader thread
            // never sees them, no per-line filter cost.
            val pid = android.os.Process.myPid().toString()
            val pb = ProcessBuilder(
                "logcat", "-v", "time", "-T", "1", "--pid=$pid",
                "View:S", "ViewRootImpl:S", "BLASTBufferQueue_Java:S", "*:$minPriority",
            ).redirectErrorStream(true)
            val p = pb.start()
            process = p
            val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            thread = Thread({
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (stopping) break
                        val l = line ?: continue
                        // Skip the leading "--------- beginning of main" banner
                        if (l.startsWith("---------")) continue
                        try { sink(l) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    // Reader closed by stop() or process exited
                } finally {
                    try { reader.close() } catch (_: Throwable) {}
                }
            }, "LogcatTailer").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.w("LogcatTailer", "Failed to spawn logcat: ${e.message}")
            process = null
            thread = null
        }
    }

    @Synchronized
    fun stop() {
        stopping = true
        try { process?.destroy() } catch (_: Throwable) {}
        process = null
        thread = null
    }
}
