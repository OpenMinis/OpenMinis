package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T180-bg-notif: persistence for background-related toggles. Currently
 * only "Task Notifications" lives here; iOS exposes the same toggle in
 * `EnhancedBackgroundSettingsView` bound to
 * `BackgroundKeepAliveManager.backgroundNotificationsEnabled`.
 *
 * Default value is `true` to match iOS, where the toggle ships ON so
 * Live Activity and task-completion notifications work out-of-the-box
 * on first install. The user can opt out from Settings.
 */
class BackgroundSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _taskNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS))

    /**
     * Live state of the toggle. Compose surfaces collect this so flipping
     * the switch in Settings is reflected immediately at every consumer
     * (notifier, FG service status text, etc).
     */
    val taskNotificationsEnabled: StateFlow<Boolean> =
        _taskNotificationsEnabled.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TASK_NOTIFICATIONS, value).apply()
        _taskNotificationsEnabled.value = value
    }

    /**
     * T-bg-overlay phase 2: "show floating tool-status overlay while the
     * app is backgrounded" toggle. Defaults to OFF — the overlay needs
     * SYSTEM_ALERT_WINDOW which is a separate system permission flow, so
     * we won't surface anything until the user opts in.
     */
    private val _backgroundOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false))
    val backgroundOverlayEnabled: StateFlow<Boolean> =
        _backgroundOverlayEnabled.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BG_OVERLAY_ENABLED, value).apply()
        _backgroundOverlayEnabled.value = value
    }

    /**
     * [T-android-dynamic-island] "Show live status on the dynamic island"
     * toggle (Android 16 Live Updates). Defaults to OFF — the capability only
     * exists on Android 16+ with the per-app grant, and when ON it REPLACES the
     * floating overlay (mutual exclusion in AgentForegroundService.applyOverlayState),
     * so we don't want it silently changing behavior on upgrade. Reactive:
     * flipping it re-drives the FG service's combined flow so the overlay
     * appears/disappears without an app restart.
     */
    private val _dynamicIslandEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, false))
    val dynamicIslandEnabled: StateFlow<Boolean> =
        _dynamicIslandEnabled.asStateFlow()

    fun setDynamicIslandEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND_ENABLED, value).apply()
        _dynamicIslandEnabled.value = value
    }

    /**
     * Last persisted overlay position (window x/y in pixels) from the
     * previous drag. -1 means "no remembered position — let the overlay
     * controller pick a default near the bottom-left, 10 dp from each
     * edge" ([T-bg-overlay-polish]).
     *
     * [T-android-overlay-orientation-position] The position is remembered
     * PER ORIENTATION: the capsule is a `TYPE_APPLICATION_OVERLAY` window, so
     * it survives rotation and used to be clamped from one shared pair of
     * keys. A spot that reads well in portrait (bottom-left, above the nav bar)
     * lands inside the landscape gesture strip or under the side nav; the user
     * dragged it again there and thereby destroyed the portrait placement. Two
     * slots keep both.
     *
     * The no-argument READERS stay: they read the pre-split keys, which also act
     * as the fallback so an upgrade keeps the position the user last chose.
     */
    fun getOverlayX(): Int = prefs.getInt(KEY_BG_OVERLAY_X, -1)
    fun getOverlayY(): Int = prefs.getInt(KEY_BG_OVERLAY_Y, -1)
    /** Orientation-aware read: that orientation's slot, else the pre-split key. */
    fun getOverlayX(landscape: Boolean): Int = prefs.getInt(overlayKeyX(landscape), getOverlayX())
    fun getOverlayY(landscape: Boolean): Int = prefs.getInt(overlayKeyY(landscape), getOverlayY())

    /**
     * Orientation-aware write. Only the current orientation's slot is touched,
     * so rotating never clobbers the other one.
     */
    fun setOverlayPosition(x: Int, y: Int, landscape: Boolean) {
        prefs.edit()
            .putInt(overlayKeyX(landscape), x)
            .putInt(overlayKeyY(landscape), y)
            .apply()
    }

    private fun overlayKeyX(landscape: Boolean) =
        if (landscape) KEY_BG_OVERLAY_X_LANDSCAPE else KEY_BG_OVERLAY_X_PORTRAIT

    private fun overlayKeyY(landscape: Boolean) =
        if (landscape) KEY_BG_OVERLAY_Y_LANDSCAPE else KEY_BG_OVERLAY_Y_PORTRAIT

    companion object {
        private const val PREFS_NAME = "background_settings"
        private const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        private const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
        private const val KEY_BG_OVERLAY_X_PORTRAIT = "backgroundOverlayXPortrait"
        private const val KEY_BG_OVERLAY_Y_PORTRAIT = "backgroundOverlayYPortrait"
        private const val KEY_BG_OVERLAY_X_LANDSCAPE = "backgroundOverlayXLandscape"
        private const val KEY_BG_OVERLAY_Y_LANDSCAPE = "backgroundOverlayYLandscape"
        private const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"
    }
}
