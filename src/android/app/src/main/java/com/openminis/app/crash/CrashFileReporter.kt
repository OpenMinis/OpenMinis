package com.openminis.app.crash

import android.content.Context
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.ReportField
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import com.openminis.app.util.IsoTime
import java.io.File
import java.io.FileOutputStream

/**
 * T283: Java/Kotlin crash → file. Writes a single text report into
 * `filesDir/logs/` using the same `.log` extension that
 * [com.openminis.app.logging.AppLogger.listLogFiles] already filters
 * for, so reports surface in LogManagementScreen without needing a
 * separate crash-files screen.
 *
 * Service-loader registered via
 * `META-INF/services/org.acra.sender.ReportSenderFactory`.
 *
 * No network. No HTTP sender. No external dependencies beyond
 * `acra-core`.
 *
 * Crash-time write rules: this runs while the process is already dying, so
 * nothing here may throw. A disk-full / read-only / removed-directory failure
 * must lose the report quietly (and fall back to cacheDir) rather than raise a
 * second exception inside ACRA and lose every report.
 */
class CrashFileSender : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        try {
            val body = buildReport(errorContent)
            val name = "crash-${IsoTime.formatFileStamp()}.log"
            val primary = File(context.filesDir, "logs")
            if (CrashReportWriter.write(primary, name, body) != null) return
            // filesDir may be full/unwritable; cacheDir is the last resort.
            CrashReportWriter.write(File(context.cacheDir, "logs"), name, body)
        } catch (t: Throwable) {
            android.util.Log.e("CrashFileSender", "failed to persist crash report: ${t.message}")
        }
    }

    private fun buildReport(errorContent: CrashReportData): String = buildString {
        appendLine("=== Minis Java/Kotlin Crash ===")
        appendLine("Time: ${IsoTime.formatFileStamp()}")
        appendLine(
            "Version: ${errorContent.getString(ReportField.APP_VERSION_NAME)} " +
                "(${errorContent.getString(ReportField.APP_VERSION_CODE)})",
        )
        appendLine(
            "Android: ${errorContent.getString(ReportField.ANDROID_VERSION)} " +
                "(SDK ${errorContent.getString(ReportField.BUILD)})",
        )
        appendLine(
            "Device: ${errorContent.getString(ReportField.PHONE_MODEL)} " +
                "(${errorContent.getString(ReportField.BRAND)})",
        )
        appendLine()
        appendLine("--- Stack Trace ---")
        appendLine(errorContent.getString(ReportField.STACK_TRACE))
        val logcat = errorContent.getString(ReportField.LOGCAT)
        if (!logcat.isNullOrBlank()) {
            appendLine()
            appendLine("--- Logcat (last 200 lines) ---")
            appendLine(logcat)
        }
    }
}

/**
 * Side-effect-only writer kept separate from ACRA so it is unit-testable and
 * so every failure mode is one `try`. Never throws.
 */
internal object CrashReportWriter {

    /** A runaway stack trace / logcat must not fill the device. */
    const val MAX_CHARS = 512 * 1024

    fun write(dir: File, name: String, body: String): File? {
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val target = File(dir, name)
            val tmp = File(dir, ".$name.tmp")
            val text = if (body.length > MAX_CHARS) {
                body.substring(0, MAX_CHARS) + "\n[report truncated at $MAX_CHARS chars]"
            } else {
                body
            }
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                    // fsync: the process may be killed right after this.
                    out.fd.sync()
                }
                // JVM rename is atomic on the same filesystem; clear a
                // same-timestamp target first (Windows cannot replace).
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return null
                }
                target
            } catch (t: Throwable) {
                tmp.delete()
                null
            }
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * Service-loader factory. ACRA discovers and instantiates senders via
 * this factory at crash time on the dedicated `:acra` reporter process.
 */
class CrashFileSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        CrashFileSender()

    override fun enabled(config: CoreConfiguration): Boolean = true
}
