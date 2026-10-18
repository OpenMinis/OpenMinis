package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL):
 * durable app-level toggle + the one-time confirmation acknowledgement.
 *
 * History: the toggle used to be per-ViewModel memory only (mirroring iOS
 * `AIChatViewModel.enhancedCacheEnabled`), so every process restart silently
 * flipped the user's choice back to OFF — the exact opposite of a "set it and
 * save money" preference. It is now persisted and shared through [enabled],
 * following the FastModePrefs pattern: [prime] warms the state at app startup,
 * [isEnabled] is the context-free read used at request-build time, and
 * [setEnabled] writes both the flow and the prefs.
 *
 * The confirmation flag stays separate: it gates the one-time extra-billing
 * warning dialog, while the toggle state is what actually reaches the provider.
 */
object EnhancedCachePrefs {
    private const val PREFS = "minis_enhanced_cache_prefs"
    private const val KEY_CONFIRMED = "enhancedCacheConfirmed"
    private const val KEY_ENABLED = "enhancedCacheEnabled"

    @Volatile
    private var cachedEnabled: Boolean = false

    private val _enabled = MutableStateFlow(false)

    /** Observable toggle state for both the chat menu and the usage screen. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the toggle cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
        _enabled.value = cachedEnabled
    }

    /** Context-free read for request builders (provider layer has no Context). */
    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        _enabled.value = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isConfirmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRMED, false)

    fun setConfirmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED, true).apply()
    }

    /** JVM unit-test seam: mirrors FastModePrefs.setCachedEnabledForTest. */
    @androidx.annotation.VisibleForTesting
    internal fun setEnabledForTest(enabled: Boolean) {
        cachedEnabled = enabled
        _enabled.value = enabled
    }
}
