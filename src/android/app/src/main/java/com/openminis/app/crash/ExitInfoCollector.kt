package com.openminis.app.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [OpenMinis#363] Recover the PREVIOUS process death from the system, after
 * the fact.
 *
 * The problem this exists for: a native SIGABRT kills the process outright, so
 * nothing in-process gets to describe it. debuggerd does write a tombstone —
 * carrying the "Abort message:" line and a full memory map — but it lands in
 * `/data/tombstones/`, which an app cannot read (SELinux, owner `system`), and
 * which a user cannot fetch without adb. Field reports therefore arrived as a
 * handful of bare PCs with no abort message and no way to tell which .so they
 * belonged to.
 *
 * `ActivityManager.getHistoricalProcessExitReasons()` is the supported way
 * out: querying your OWN package needs no permission, and for a
 * [ApplicationExitInfo.REASON_CRASH_NATIVE] record the platform hands back
 *
 *   - [ApplicationExitInfo.getDescription] — the abort message, and
 *   - [ApplicationExitInfo.getTraceInputStream] — the WHOLE tombstone,
 *     memory map included, which is what turns a raw PC into "libfoo.so+0x…".
 *
 * Output goes to `filesDir/logs/exitinfo-<stamp>.log`, the same directory and
 * `.log` extension [CrashFileSender] and the native handler already use, so it
 * shows up in LogManagementScreen and the user can export it without adb.
 *
 * Runs once per process start, off the main thread, and only for records newer
 * than the high-water mark it persists — so a device that keeps the same exit
 * record for weeks does not re-write the same file on every launch.
 */
object ExitInfoCollector {

    private const val TAG = "ExitInfoCollector"
    private const val PREFS = "exit_info_collector"

    /** Millis timestamp of the newest record already written to disk. */
    internal const val KEY_HIGH_WATER = "last_handled_timestamp_ms"

    /** How many records to ask the platform for. It keeps ~16 per package. */
    internal const val MAX_RECORDS = 16

    /**
     * Cap on the tombstone bytes copied into our log. Tombstones run to a few
     * hundred KB; the head carries the abort message, the signal, the faulting
     * thread's backtrace and the memory map — everything triage needs — while
     * the tail is the other threads' stacks and full register dumps. Bounded so
     * one crash cannot fill the user's logs directory.
     */
    internal const val MAX_TRACE_BYTES = 256 * 1024

    private val STAMP_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /**
     * Write a log file for every not-yet-seen crash/ANR exit record.
     *
     * Safe to call on any thread; does file I/O, so callers should keep it off
     * the main thread. Never throws — a diagnostic that can crash the app it
     * is diagnosing is worse than no diagnostic.
     */
    fun collect(context: Context) {
        // ApplicationExitInfo is API 30; getTraceInputStream carries the
        // tombstone from API 31. Below that there is nothing to read.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val records = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            if (records.isEmpty()) return

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val highWater = prefs.getLong(KEY_HIGH_WATER, 0L)
            var newest = highWater
            var written = 0

            for (info in records) {
                if (info.timestamp <= highWater) continue
                if (info.timestamp > newest) newest = info.timestamp
                if (!isInteresting(info.reason)) continue
                if (writeReport(context, info)) written++
            }

            if (newest > highWater) {
                // Advance past every record we LOOKED at, not only the ones we
                // wrote. An uninteresting record (a normal user-initiated exit)
                // would otherwise be re-examined on every launch forever.
                prefs.edit().putLong(KEY_HIGH_WATER, newest).apply()
            }
            if (written > 0) {
                Log.i(TAG, "wrote $written exit report(s) to filesDir/logs")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "collect failed: ${t.message}")
        }
    }

    /**
     * Which exit reasons are worth a file. Native crashes are the reason this
     * class exists; the JVM crash and ANR records are collected too because
     * they cost nothing extra and carry a trace the app could not otherwise
     * retrieve after the fact (ACRA only sees a JVM crash while the process is
     * still alive — a low-memory kill or a watchdog ANR never reaches it).
     */
    internal fun isInteresting(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_ANR

    /** Human-readable reason, for the report header. */
    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason=$reason"
    }

    private fun writeReport(context: Context, info: ApplicationExitInfo): Boolean {
        val dir = File(context.filesDir, "logs").also { it.mkdirs() }
        val out = File(dir, "exitinfo-${STAMP_FMT.format(Date(info.timestamp))}.log")
        // A record's timestamp is its identity; if the file is already there
        // (two processes racing, or a high-water write that did not stick)
        // leave it alone rather than rewriting it.
        if (out.exists()) return false

        val body = buildString {
            appendLine("=== Minis Process Exit Report ===")
            appendLine("Collected: ${STAMP_FMT.format(Date())}")
            appendLine("Exit time: ${STAMP_FMT.format(Date(info.timestamp))}")
            appendLine("Reason: ${reasonName(info.reason)}")
            appendLine("Status: ${info.status}")
            appendLine("Importance: ${info.importance}")
            appendLine("PID: ${info.pid}  Process: ${info.processName}")
            appendLine("PSS: ${info.pss} kB  RSS: ${info.rss} kB")
            appendLine()
            // THE field this class was written for.
            appendLine("Abort message / description:")
            appendLine(info.description ?: "(none provided by the platform)")

            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                appendLine()
                appendLine("--- Tombstone (truncated to ${MAX_TRACE_BYTES / 1024} kB) ---")
                appendLine(readTrace(info) ?: "(no trace available)")
            }
        }

        return try {
            out.writeText(body)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "write failed for ${out.name}: ${t.message}")
            false
        }
    }

    /**
     * The tombstone itself. On [ApplicationExitInfo.REASON_CRASH_NATIVE] this
     * is the proto/text dump debuggerd produced — the same content as
     * `/data/tombstones/`, delivered through a channel the app is allowed to
     * read.
     */
    private fun readTrace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream ->
            val buf = ByteArray(MAX_TRACE_BYTES)
            var total = 0
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            if (total <= 0) null else String(buf, 0, total, Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "traceInputStream failed: ${t.message}")
        null
    }
}
