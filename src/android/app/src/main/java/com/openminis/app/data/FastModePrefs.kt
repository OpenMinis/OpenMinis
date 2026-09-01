package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-codex-fast-mode] App-level persisted Fast Mode toggle (mirrors iOS
 * UserDefaults key `codexFastModeEnabled`, commit fb671083). Unlike Enhanced
 * Cache the flag IS durable and shared across sessions (user-confirmed on
 * iOS): switching to another chat reads the same enabled state.
 *
 * The provider layer has no Context, and iOS deliberately reads the flag at
 * REQUEST-BUILD time so a flip applies to the very next request of an ongoing
 * session — including offload / title-gen calls that never pass through the
 * ChatViewModel. To reproduce that, [prime] captures the application context
 * once at app startup (MinisApp.onCreate) and warms a volatile cache; the
 * context-free [isEnabled] is then safe to call from any request builder.
 */
object FastModePrefs {
    private const val PREFS = "minis_fast_mode_prefs"
    private const val KEY_ENABLED = "codexFastModeEnabled"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    /**
     * Context-free read for request builders. Falls back to the warm cache
     * value (false before [prime] has ever run — matches an un-toggled fresh
     * install, and prime runs before any request can be built).
     */
    fun isEnabled(): Boolean {
        return cachedEnabled
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * [T-android-xai-priority] Set ONLY the in-memory cache, with no Context and
     * no persistence. Exists so JVM unit tests can exercise the real
     * [isEnabled] path that request builders consult — there is no Robolectric
     * in this module, so the Context-taking [setEnabled] is unusable there, and
     * asserting against a hand-rolled fake would test the fake instead of the
     * shipping code. Not for production use: a write here is lost on restart.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setCachedEnabledForTest(enabled: Boolean) {
        cachedEnabled = enabled
    }
}
