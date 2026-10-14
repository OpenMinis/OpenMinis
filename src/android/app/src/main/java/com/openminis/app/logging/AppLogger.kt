package com.openminis.app.logging

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import com.openminis.app.text.BoundedText
import com.openminis.app.util.IsoTime

/**
 * Daily-rotating file logger that mirrors iOS LoggingManager.
 * Writes log entries to files named yyyy-MM-dd.log in app's files/logs/ directory.
 * Retains logs for 15 days by default.
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val LOG_DIR = "logs"
    // [T-android-log-retention-15d] 15 days, matching iOS logRetentionDays
    // (d83bc894). The previous 14 came from the March parity-scaffolding
    // batch with no recorded rationale — plain historical drift, not intent.
    private const val MAX_AGE_DAYS = 15

    private const val PREF_NAME = "logging_prefs"
    private const val KEY_ENABLED = "logging_enabled"

    // [T-android-log-write-amp] Flush-policy bounds for the daily file writer.
    // Flushing every line cost a write syscall per logcat line; buffering
    // unboundedly would break `adb shell tail -f` and the in-app log reader.
    // Compromise: flush on every priority line and at most FLUSH_LINES lines /
    // FLUSH_IDLE_MS seconds of staleness in between.
    private const val FLUSH_LINES = 32
    private const val FLUSH_IDLE_MS = 2_000L

    private var logDir: File? = null

    /**
     * T-android-safemode-lateinit-crash: application context captured as
     * early as possible so the read-only helpers below can find
     * `filesDir/logs` even when [init] never ran.
     *
     * [init] is called from MinisApp.onCreate AFTER the safe-mode
     * early-return, so on a launch following a crash burst [logDir] stays
     * null — and every reader keyed off it ([listLogFiles],
     * [listLogFileMetas], [readLog], [totalSize]) reported "no logs".
     * That is precisely the launch on which the user goes looking for the
     * crash records, which is why they saw an empty list while the files
     * were sitting on disk the whole time. The crash writers were never
     * affected: CrashFileSender and NativeCrashHandler each build
     * `filesDir/logs` from their own Context.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * Capture the context for [resolveLogDir] without doing any of
     * [init]'s side effects (no mkdirs, no prefs read, no stdout capture,
     * no pruning). Safe to call from the very top of Application.onCreate,
     * before any safe-mode decision is made.
     */
    fun primeContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Log directory for READ paths. Prefers the [init]-assigned [logDir];
     * falls back to deriving it from [appContext]. Returns null only when
     * neither is available, and never creates the directory — readers
     * treat a missing directory as "no logs", which is correct.
     */
    private fun resolveLogDir(): File? =
        logDir ?: appContext?.let { File(it.filesDir, LOG_DIR) }

    private var currentDate: String = ""
    private var writer: PrintWriter? = null
    private var logFileCapped: Boolean = false
    private var enabled: Boolean = false

    // [T-android-log-write-amp] Incremental size of the open day file, so
    // appendFileLine no longer stats it per line. Set once by getWriter when a
    // day file opens, then bumped by the written byte count. Guarded by the
    // same monitor as appendFileLine/getWriter (both @Synchronized).
    private var currentFileSize: Long = 0L
    private var unflushedLines: Int = 0
    private var lastFlushAtMs: Long = 0L

    // Saved references to the JVM's original stdout/stderr. Captured on the
    // first startCapture() so stopCapture() can restore them — without this we
    // would never be able to detach our PrintStream wrapper, and the redirection
    // would survive the toggle being flipped off.
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var captureActive: Boolean = false

    // Logcat tail child process — captures Log.d/i/w/e/v from the framework,
    // third-party libraries, and project code that calls android.util.Log
    // directly. Without this only stdout/stderr (println, stack traces) end
    // up in the file, which is < 1% of the actual log volume on Android.
    private var logcatTailer: LogcatTailer? = null

    /**
     * Initialize the logger with app context. Call once from Application.onCreate().
     * If logging was previously enabled (persisted in SharedPreferences),
     * automatically begins capturing stdout/stderr — mirrors iOS
     * `LoggingManager.startIfEnabled()`.
     */
    fun init(context: Context) {
        primeContext(context)
        logDir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        enabled = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        pruneOldLogs()
        if (enabled) startCapture()
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) startCapture() else stopCapture()
    }

    /**
     * Redirect [System.out] and [System.err] through line-buffered writers
     * that prepend a timestamp to each line and append it to today's log file
     * before forwarding the original bytes to the previous stream. The forward
     * is essential — without it, anything written through stdout (println,
     * Throwable.printStackTrace, third-party libs that write to System.err)
     * would silently disappear from logcat.
     *
     * `Log.d/i/w/e` calls go through the Android native logging bridge and
     * are NOT captured by this redirection — only stdout/stderr.
     */
    @Synchronized
    private fun startCapture() {
        if (captureActive) return
        if (logDir == null) return // init() not called yet
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err
        System.setOut(PrintStream(LineCapturingStream(originalOut!!, "STDOUT"), true))
        System.setErr(PrintStream(LineCapturingStream(originalErr!!, "STDERR"), true))
        // Spawn the logcat tail before emitting the session-start marker so
        // the marker itself shows up in the captured stream as a sanity check.
        logcatTailer = LogcatTailer { line -> writeLogcatLine(line) }.also { it.start() }
        captureActive = true
        info("AppLogger", "Logging session started — capturing stdout/stderr + logcat tail")
        // [T-android-mem-probe-trust] Device/ROM/heap identity, immediately
        // after the session marker. The 2026-08-15 field log carried none of
        // this: the hardware had to be guessed from an incidental
        // `/proc/vivo_rsc/…` line in the logcat tail, and triage then compared
        // it against unrelated hardware. Emitted per logging session (not per
        // launch) so it is present in every attached log, including the
        // post-crash one.
        appContext?.let { com.openminis.app.diagnostics.EnvironmentBanner.log(it) }
    }

    @Synchronized
    private fun stopCapture() {
        if (!captureActive) return
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        logcatTailer?.stop()
        logcatTailer = null
        captureActive = false
        // Close the daily writer so any buffered bytes are flushed; getWriter()
        // will reopen on the next file write.
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        logFileCapped = false
        currentDate = ""
        currentFileSize = 0L
        unflushedLines = 0
    }

    /**
     * Append a logcat tail line to today's log file. Lines that AppLogger
     * itself produced (tag prefix `Minis.`) are skipped — [log] already wrote
     * them via [writer], so without this filter every `info()` / `warning()`
     * / etc. call would appear twice in the file (once from [log], once
     * echoed back through logcat).
     */
    private fun writeLogcatLine(rawLine: String) {
        if (!enabled) return
        // logcat -v time format: "MM-DD HH:MM:SS.mmm L/Tag(pid): message"
        // Extract the tag to filter our own output.
        val slashIdx = rawLine.indexOf('/')
        val parenIdx = if (slashIdx >= 0) rawLine.indexOf('(', slashIdx) else -1
        if (slashIdx >= 0 && parenIdx > slashIdx) {
            val tag = rawLine.substring(slashIdx + 1, parenIdx).trim()
            if (tag.startsWith("Minis.") || tag == "AppLogger") return
        }
        val today = IsoTime.formatLocalDate()
        appendFileLine(today, "[LOGCAT] $rawLine", quiet = true)
    }

    /**
     * OutputStream wrapper that:
     *   1. Forwards every byte to [delegate] (the original stdout/stderr) so
     *      logcat / adb still receives the output unchanged.
     *   2. Buffers bytes into [buffer] until a `\n` arrives, then writes the
     *      complete line — prefixed with `[HH:mm:ss.SSS] [LEVEL] [tag]` — to
     *      the daily log file. Partial lines are flushed on close().
     */
    private class LineCapturingStream(
        private val delegate: PrintStream,
        private val tag: String,
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            // Always forward first; any failure to capture must NOT swallow output.
            delegate.write(b)
            if (b == '\n'.code) {
                emitLine()
            } else {
                buffer.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            var lineStart = off
            val end = off + len
            for (i in off until end) {
                if (b[i] == '\n'.code.toByte()) {
                    if (i > lineStart) buffer.write(b, lineStart, i - lineStart)
                    emitLine()
                    lineStart = i + 1
                }
            }
            if (lineStart < end) buffer.write(b, lineStart, end - lineStart)
        }

        override fun flush() {
            delegate.flush()
        }

        private fun emitLine() {
            val line = try {
                buffer.toString("UTF-8")
            } catch (_: Exception) {
                buffer.toString()
            }
            buffer.reset()
            // Drop empty lines so the file isn't full of bare timestamps.
            if (line.isEmpty()) return
            writeFileLine(tag, line)
        }
    }

    /**
     * Append a captured stdout/stderr line to today's log file. Catches all
     * I/O failures so a flaky filesystem can't crash the app's stdout.
     */
    @Synchronized
    private fun writeFileLine(channel: String, line: String) {
        if (!enabled) return
        val ms = System.currentTimeMillis()
        val today = IsoTime.formatLocalDate(ms)
        val timestamp = IsoTime.formatLocalTimeMillis(ms)
        appendFileLine(today, "[$timestamp] [$channel] $line")
    }

    /**
     * Log a message at INFO level with a category tag.
     */
    fun info(category: String, message: String) {
        log("INFO", category, message)
    }

    fun warning(category: String, message: String) {
        log("WARN", category, message)
    }

    fun error(category: String, message: String) {
        log("ERROR", category, message)
    }

    /**
     * Categories whose DEBUG logs we silently drop. Useful for high-volume
     * categories that fire on every scroll frame / token (e.g. the chat
     * scroll-follow path under tag "ChatScrollFollow") — gating at the
     * caller would require touching dozens of sites; gating here keeps the
     * Log.d + file-write cost off the hot path. The string formatting at
     * each caller still pays for itself (we can't fix that without lambdas)
     * but `Log.d → liblog → LogcatTailer → file write` is more expensive
     * than the string build alone.
     */
    private val mutedDebugCategories = setOf("ChatScrollFollow")

    fun debug(category: String, message: String) {
        if (category in mutedDebugCategories) return
        log("DEBUG", category, message)
    }

    private fun log(level: String, category: String, message: String) {
        val ms = System.currentTimeMillis()
        val today = IsoTime.formatLocalDate(ms)
        val timestamp = IsoTime.formatLocalTimeMillis(ms)

        // Also output to logcat
        val logcatTag = "Minis.$category"
        when (level) {
            "ERROR" -> Log.e(logcatTag, message)
            "WARN" -> Log.w(logcatTag, message)
            "DEBUG" -> Log.d(logcatTag, message)
            else -> Log.i(logcatTag, message)
        }

        // Write to file (only if enabled)
        if (!enabled) return
        appendFileLine(today, "[$timestamp] [$level] [$category] $message")
    }

    @Synchronized
    private fun appendFileLine(today: String, line: String, quiet: Boolean = false) {
        if (!enabled) return
        try {
            // [T-android-log-write-amp] Per line this used to pay a stat syscall
            // (file.length()), a full UTF-8 encode purely to measure the byte
            // count, and a flush() — and the LogcatTailer sink feeds it EVERY
            // logcat line while logging is enabled, which is the busiest path
            // during streaming replies. Now: the day-file size is tracked
            // incrementally (one stat when getWriter opens it), the byte count
            // is computed by a char scan with no byte[] allocation (the line is
            // already clamped to MAX_LOG_LINE_CHARS by clampLogLine before any
            // measuring), and the flush is bounded (priority lines flush
            // immediately; the rest at most FLUSH_LINES/FLUSH_IDLE_MS late, so
            // `adb shell tail` and the in-app reader stay near-real-time).
            // Rotation limits and redaction behaviour are unchanged.
            val clipped = BoundedText.clampLogLine(line)
            val priorityLine = BoundedText.isPriorityLogLine(clipped)
            val w = getWriter(today)
            val size = currentFileSize
            val lineBytes = utf8ByteLength(clipped) + 1 // println appends the line separator
            if (!BoundedText.canAppendLog(size, lineBytes)) {
                if (!logFileCapped) {
                    logFileCapped = true
                    val marker = "[AppLogger] daily log reached ${BoundedText.MAX_LOG_FILE_BYTES} bytes; info/debug dropped, ERROR/WARN kept"
                    w.println(marker)
                    currentFileSize += utf8ByteLength(marker) + 1
                    w.flush()
                    unflushedLines = 0
                    lastFlushAtMs = System.currentTimeMillis()
                }
                val keepPriority = !quiet && priorityLine &&
                    BoundedText.canAppendPriorityLog(size, lineBytes)
                if (!keepPriority) return
            }
            w.println(clipped)
            currentFileSize += lineBytes
            unflushedLines++
            val now = System.currentTimeMillis()
            if (priorityLine || unflushedLines >= FLUSH_LINES ||
                now - lastFlushAtMs >= FLUSH_IDLE_MS
            ) {
                w.flush()
                unflushedLines = 0
                lastFlushAtMs = now
            }
        } catch (e: Exception) {
            if (!quiet) Log.w(TAG, "Failed to write log: ${e.message}")
        }
    }

    /**
     * UTF-8 byte length of [s] computed by scanning its code points — the same
     * math the encoder does, without allocating (and throwing away) the byte[]
     * just to measure it.
     */
    private fun utf8ByteLength(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            n += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(cp)
        }
        return n
    }

    @Synchronized
    private fun getWriter(date: String): PrintWriter {
        if (date != currentDate || writer == null) {
            writer?.close()
            val dir = logDir ?: throw IllegalStateException("AppLogger not initialized")
            val file = File(dir, "minis-$date.log")
            // The single per-day stat; appendFileLine tracks the size from here.
            currentFileSize = file.length()
            logFileCapped = currentFileSize >= BoundedText.MAX_LOG_FILE_BYTES
            writer = PrintWriter(FileWriter(file, true))
            currentDate = date
            unflushedLines = 0
            lastFlushAtMs = System.currentTimeMillis()
        }
        return writer!!
    }

    /**
     * List available log files, newest first.
     */
    fun listLogFiles(): List<File> {
        val dir = resolveLogDir() ?: return emptyList()
        return dir.listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Lightweight metadata for a single log file. Captures name + size +
     * mtime ONCE so the UI never re-stat's the file during recomposition
     * (LogManagementScreen used to call file.length() per row per
     * recomposition, which scaled badly once dozens of crash files piled
     * up alongside the daily logs).
     */
    data class LogFileMeta(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /**
     * Capped, prefix-filtered log listing for the UI.
     *
     * - `prefix`: filename starts-with filter (e.g. `"minis-"` for daily
     *   logs, `"crash-"` / `"native-crash-"` for crash reports). Empty
     *   string returns all `.log` files.
     * - `limit`: keep at most this many files, sorted by name descending
     *   (newest first, since both daily and crash filenames embed
     *   YYYY-MM-DD prefixes that sort correctly).
     *
     * Captures size + mtime per file via a single `stat` per entry, so a
     * caller iterating the result list never has to re-stat. Total size
     * is computed by the caller (sum of `sizeBytes`) — no second
     * directory walk needed.
     *
     * Pure data; safe to call from `Dispatchers.IO`.
     */
    fun listLogFileMetas(prefix: String, limit: Int): List<LogFileMeta> {
        val dir = resolveLogDir() ?: return emptyList()
        val files = dir.listFiles { f ->
            f.extension == "log" && (prefix.isEmpty() || f.name.startsWith(prefix))
        } ?: return emptyList()
        // Sort then cap BEFORE the per-file stat — File.listFiles already
        // populated name internally, but length()/lastModified() are
        // separate stat syscalls we'd rather skip on the tail.
        return files
            .sortedByDescending { it.name }
            .take(limit)
            .map { LogFileMeta(it.name, it.length(), it.lastModified()) }
    }

    /**
     * Read content of a specific log file.
     */
    fun readLog(
        filename: String,
        offset: Int = 0,
        limit: Int = BoundedText.MAX_LOG_READ_BYTES,
    ): String? {
        val dir = resolveLogDir() ?: return null
        val file = File(dir, filename)
        if (!file.exists()) return null
        val bytes = limit.coerceAtMost(BoundedText.MAX_LOG_READ_BYTES).toLong()
        return com.openminis.app.data.body.Admission.occupy(bytes) {
            BoundedText.readFileRange(
                file,
                offset.coerceAtLeast(0).toLong(),
                bytes.toInt(),
            )
        }
    }

    fun logFileSize(filename: String): Long {
        val dir = resolveLogDir() ?: return -1L
        val file = File(dir, filename)
        return if (file.exists()) file.length() else -1L
    }

    /**
     * Delete all log files.
     */
    @Synchronized
    fun clearLogs() {
        logDir?.listFiles()?.forEach { it.delete() }
        // [T-logging-zombie-fd-android] The open writer still references the
        // just-deleted file; a FileWriter on an unlinked inode keeps writing to
        // the zombie file (invisible on disk) until currentDate changes or the
        // writer is nulled. Drop it and reset currentDate so the next
        // getWriter() reopens a fresh minis-<date>.log on the following write.
        // @Synchronized shares getWriter()'s monitor so this can't race a write.
        writer?.close()
        writer = null
        logFileCapped = false
        currentDate = ""
        currentFileSize = 0L
        unflushedLines = 0
    }

    /**
     * Total size of all log files in bytes.
     */
    fun totalSize(): Long {
        // Read path — uses the same fallback as the listing helpers so the
        // storage footer isn't reported as 0 B next to a populated list.
        // Deletion paths (clearLogs / pruneOldLogs) deliberately stay on
        // the raw logDir: a process that never finished init has no
        // business unlinking the user's crash evidence.
        return resolveLogDir()?.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun pruneOldLogs() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        logDir?.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
