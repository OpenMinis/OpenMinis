package com.openminis.app.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * [T-android-dynamic-island] Capability probe for Android 16 (Baklava, API 36)
 * "Live Updates" — the promoted-ongoing-notification surface users perceive as
 * the "dynamic island" / always-visible status chip.
 *
 * A device "supports the dynamic island" only when BOTH hold:
 *   1. It runs Android 16+ (`Build.VERSION.SDK_INT >= 36`), so the
 *      `Notification.ProgressStyle` + `FLAG_PROMOTED_ONGOING` +
 *      `NotificationManager.canPostPromotedNotifications()` APIs exist, AND
 *   2. `NotificationManager.canPostPromotedNotifications()` returns true — this
 *      reflects the *system + per-app user grant* (the user can disable Live
 *      Updates for this app from system settings), so it can flip at runtime.
 *
 * The probe is cached only briefly (see [capableCache]): callers re-query it
 * each time the app becomes foreground-visible (spec §3) so a permission the
 * user toggled off in system settings is picked up without needing a process
 * restart.
 *
 * As of 2026-07 this only actually returns true on Pixel 6+ hardware running
 * the Android 16 QPR that shipped Live Updates; on every other device the guard
 * short-circuits at the SDK_INT check (pre-16) or `canPostPromotedNotifications`
 * (16 without the feature), so all the mutual-exclusion logic degrades cleanly
 * to the existing overlay + plain-notification behavior.
 */
object DynamicIslandSupport {

    private const val TAG = "DynamicIslandSupport"

    /**
     * True when this device can post promoted ("dynamic island") notifications
     * right now. Runtime-safe on all API levels — returns false pre-36 without
     * touching any 36-only symbol.
     */
    /**
     * [T-android-island-capability-cache] Memoized result of the capability
     * probe below. `null` until first asked.
     *
     * The probe is a binder round trip (`getSystemService` +
     * `canPostPromotedNotifications`). It was being re-asked TWICE per second for as long as a
     * task ran: the chip ticker calls `isPromotedChipActive()` each tick and
     * `buildPromotedNotification()` asks again while building the very
     * notification that tick posts. With decorative animations fixed, those
     * binder calls were the single largest remaining app-side cost in a
     * profile of a running tool (24 samples, the top two app frames).
     *
     * [T-android-island-capability-ttl] It CAN change while the process
     * lives: the answer reflects the per-app Live Updates grant the user
     * toggles in system settings (see the class KDoc). Cached forever, a
     * revoke left applyOverlayState suppressing the floating capsule while the
     * system refused the promoted chip — neither surface showed — and a later
     * grant stayed invisible until the process died. So the cache now expires
     * after [CAPABILITY_TTL_MS] and can be dropped explicitly via
     * [invalidateCapabilityCache] (the settings screen's ON_RESUME re-probe).
     * At 2 asks/second a 5 s TTL still removes ~90% of the binder calls the
     * memoization was introduced to save.
     */
    @Volatile
    private var capableCache: Boolean? = null

    /** `SystemClock.elapsedRealtime()` at which [capableCache] was filled. */
    @Volatile
    private var capableCachedAtMs: Long = 0L

    private const val CAPABILITY_TTL_MS = 5_000L

    /** Forget the cached capability so the next ask re-probes the platform. */
    fun invalidateCapabilityCache() {
        capableCache = null
    }

    private fun cacheCapability(capable: Boolean): Boolean {
        capableCachedAtMs = android.os.SystemClock.elapsedRealtime()
        capableCache = capable
        return capable
    }

    fun isDynamicIslandCapable(context: Context): Boolean {
        // Pre-36 is a property of the OS build and truly cannot flip; no
        // binder call is involved, so nothing to cache.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        capableCache?.let { cached ->
            val age = android.os.SystemClock.elapsedRealtime() - capableCachedAtMs
            if (age in 0 until CAPABILITY_TTL_MS) return cached
        }
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            cacheCapability(nm?.canPostPromotedNotifications() == true)
        } catch (t: Throwable) {
            // Defensive: some early/partial Baklava builds may throw if the
            // feature isn't fully wired. Treat any failure as "not capable"
            // so we fall back to the overlay + plain-notification path.
            Log.w(TAG, "canPostPromotedNotifications() failed: ${t.message}")
            cacheCapability(false)
        }
    }

    /**
     * True when the Live Updates / dynamic-island experience should be the
     * ACTIVE status surface: the device is capable AND the user enabled the
     * toggle. This is the single predicate that (a) selects the promoted
     * ProgressStyle notification branch and (b) short-circuits the floating
     * overlay so the two never render at once.
     */
    fun isDynamicIslandActive(context: Context, userEnabled: Boolean): Boolean =
        userEnabled && isDynamicIslandCapable(context)
}
