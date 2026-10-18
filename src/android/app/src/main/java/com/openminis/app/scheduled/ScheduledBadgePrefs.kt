package com.openminis.app.scheduled

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-scheduled-badge-toggle] Whether the home toolbar's clock icon
 * shows the enabled-task count as a badge.
 *
 * Upstream added this as a switch on the Scheduled Tasks screen: users who keep
 * a permanent recurring task saw a badge that never went away and had no way to
 * retire it except deleting the task. Default is ON, so behaviour only changes
 * when someone opts out.
 *
 * Backed by SharedPreferences rather than the settings/Room store on purpose —
 * it is a pure display preference, and `SessionListScreen` reads it during
 * composition, so it must not touch disk on the main thread more than the
 * one-time prime below.
 */
object ScheduledBadgePrefs {
    const val PREFS = "minis_scheduled"
    private const val KEY_SHOW_BADGE = "showBadge"

    /** Default ON: the badge is existing behaviour, the switch opts out. */
    const val DEFAULT_SHOW_BADGE = true

    private val _showBadge = MutableStateFlow(DEFAULT_SHOW_BADGE)

    /**
     * Primes the flow from disk once and returns it. Callers collect it, so a
     * toggle on the Scheduled Tasks screen updates the toolbar immediately
     * without a recreate.
     */
    fun flow(context: Context): StateFlow<Boolean> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _showBadge.value = prefs.getBoolean(KEY_SHOW_BADGE, DEFAULT_SHOW_BADGE)
        return _showBadge.asStateFlow()
    }

    fun set(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_BADGE, enabled)
            .apply()
        _showBadge.value = enabled
    }
}
