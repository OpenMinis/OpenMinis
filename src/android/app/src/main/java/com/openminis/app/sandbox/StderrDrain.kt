package com.openminis.app.sandbox

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * [T-android-fresh-stderr-drain] Keeps a command's separate stderr pipe
 * flowing, and decides where each line goes.
 *
 * Debug builds (BuildConfig.DEV_TOOLS) spawn the fresh-process shell with
 * stderr NOT merged into stdout, so proot's `[native_offload]` debug lines
 * can go to logcat instead of into the command's output. The fresh path
 * copied that split from PersistentShell but not PersistentShell's stderr
 * reader thread, so nothing read the pipe at all. Once a command wrote 64 KB
 * to stderr — wget's progress meter on a ~40 MB download, pip, npm, a
 * compiler — the pipe buffer filled and the command blocked in write()
 * forever, with the download stalled mid-file. Observed on a Pixel 6: wget in
 * `pipe_write` for 10+ minutes, zero bytes read or written over a 10 s
 * sample, the .part file frozen at 42.9 MB. Release builds merge stderr and
 * were never affected.
 *
 * Lines are routed the way a release build would show them: our proot
 * extensions' debug lines (`[native_offload] `, `[fake_netlink] `) go to [log]; every other line is kept and
 * returned by [finish] so the caller can append it to the command output —
 * the model sees the command's errors in debug builds too, not just in
 * release. Stderr is appended after stdout rather than interleaved; the order
 * between the two streams is not recoverable once they are separate pipes.
 */
internal class StderrDrain(
    private val stream: InputStream,
    private val log: (String) -> Unit,
) {
    private val kept = StringBuilder()
    private val thread = Thread({ drain() }, "FreshProcessShell-stderr").apply { isDaemon = true }

    fun start(): StderrDrain = apply { thread.start() }

    private fun drain() {
        try {
            stream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (isProotDebugLine(line)) log(line) else synchronized(kept) { kept.append(line).append('\n') }
            }
        } catch (_: Exception) {
            // Closed by timeout / Stop / finish(): whatever was read is kept.
        }
    }

    /**
     * Wait at most [waitMs] for the drain to reach EOF, then close the stream
     * (a detached daemon may still hold the write end) and return the kept
     * text. Bounded so a lingering writer can never hang the shell call.
     */
    fun finish(waitMs: Long): String {
        runCatching { thread.join(waitMs) }
        runCatching { stream.close() }
        return synchronized(kept) { kept.toString() }
    }

    companion object {
        /**
         * Debug-only lines of our own proot extensions, printed when
         * MINIS_NOFF_DEBUG is set (DEV_TOOLS builds). `[fake_netlink] ` joined
         * when its startup line moved from note(INFO) to the debug switch.
         */
        private val PROOT_DEBUG_PREFIXES = listOf("[native_offload] ", "[fake_netlink] ")

        fun isProotDebugLine(line: String): Boolean = PROOT_DEBUG_PREFIXES.any { line.startsWith(it) }
    }
}
