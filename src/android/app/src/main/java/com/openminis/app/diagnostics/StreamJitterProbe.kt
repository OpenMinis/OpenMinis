package com.openminis.app.diagnostics

import android.util.Log
import com.openminis.app.BuildConfig

/**
 * Debug-only timeline markers for the streaming-jitter harness
 * (tools/perf-baseline + the scratchpad `jitter_analyze.py`). Each mark logs
 * a `mono=` System.nanoTime() stamp so it can be joined against
 * `dumpsys gfxinfo framestats` (same CLOCK_MONOTONIC) to classify frames as
 * "within N ms of a publish / flatten / programmatic scroll" versus quiet.
 *
 * Lives here, permanently, because the previous incarnation was five loose
 * `Log.d("JitterProbe", …)` lines in ChatViewModel/ChatScreen that another
 * session's commit swept into HEAD by accident. A gated helper costs one
 * boolean check in release and cannot leak into a shipped log.
 *
 * `experiment()` reads `debug.minis.<key>` system properties so a rendering
 * A/B (e.g. `adb shell setprop debug.minis.glide snap`) can be flipped on a
 * debug device without a rebuild. Never consulted in release builds.
 */
object StreamJitterProbe {
    private const val TAG = "JitterProbe"

    @JvmStatic
    fun publish(kind: String, len: Int) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(TAG, "$kind len=$len mono=${System.nanoTime()}")
    }

    @JvmStatic
    fun scroll(source: String, idx: Int) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(TAG, "scroll src=$source idx=$idx mono=${System.nanoTime()}")
    }

    @JvmStatic
    fun flatten(ms: Long, frozenReused: Boolean, frozen: Int, live: Int, streamEmpty: Boolean) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(
            TAG,
            "flatten ms=$ms frozenReused=$frozenReused frozen=$frozen live=$live " +
                "streamEmpty=$streamEmpty mono=${System.nanoTime()}",
        )
    }

    private val expCache = HashMap<String, Pair<Long, String>>()

    /** Value of `debug.minis.<key>` (cached 1 s), "" when unset or in release. */
    @JvmStatic
    fun experiment(key: String): String {
        if (!BuildConfig.DEV_TOOLS) return ""
        val now = System.nanoTime()
        synchronized(expCache) {
            expCache[key]?.let { (t, v) -> if (now - t < 1_000_000_000L) return v }
            val v = runCatching {
                val cls = Class.forName("android.os.SystemProperties")
                cls.getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "debug.minis.$key", "") as String
            }.getOrDefault("")
            expCache[key] = now to v
            return v
        }
    }
}
