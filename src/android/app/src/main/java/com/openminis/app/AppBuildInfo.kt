package com.openminis.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-android-about-build-date] When this APK was built, read from the
 * `build_info/build_time.txt` asset that app/build.gradle.kts stamps on every
 * build. Port of the iOS About "Built …" line (AboutView.buildDate).
 */
object AppBuildInfo {
    private const val ASSET = "build_info/build_time.txt"

    @Volatile
    private var cached: Long? = null

    /** Build time in epoch millis, or null when the asset is missing or unreadable. */
    fun buildTimeMillis(context: Context): Long? {
        cached?.let { return it }
        val value = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }.trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
        if (value != null) cached = value
        return value
    }

    /**
     * Local time with seconds, "unknown" when absent: this gets compared by
     * eye against the timestamp of a build that just finished.
     */
    fun buildDate(context: Context): String {
        val millis = buildTimeMillis(context) ?: return "unknown"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }
}
