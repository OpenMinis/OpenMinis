package com.openminis.app.service

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager

/**
 * [T-android-overlay-orientation-position] Geometry and persistence rules for
 * the floating tool-status capsule.
 *
 * ## The defect
 *
 * The capsule is a `TYPE_APPLICATION_OVERLAY` window, so it is **not** recreated
 * on rotation — only re-clamped. Two things were wrong with that:
 *
 *  1. **One position for two shapes.** x/y were saved in a single pair of
 *     prefs keys. A position that reads well in portrait (say bottom-left, just
 *     above the nav bar) is a bad place in landscape, where the same y is inside
 *     the gesture bar and the same x sits under the system's side nav strip. The
 *     user then dragged it again in landscape — overwriting the portrait
 *     position — so every rotation lost one of the two placements.
 *  2. **The clamp box was the raw screen.** `coerceIn(0, screen - view)` keeps a
 *     window on the display but not *visible*: y = 0 puts the capsule under the
 *     status bar, and in landscape a gesture/navigation strip runs down a side
 *     edge that the box does not exclude.
 *
 * Upstream 1.15-beta.5 fixed both ("位置按横竖屏分别记忆，始终完整在屏内"). This file
 * holds the pure part so it is unit-testable without a WindowManager, and
 * [ToolOverlayController] only forwards window insets and drag deltas into it.
 */
internal data class OverlaySafeArea(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
) {
    /** Clamp a window position into the area. Never throws (see [OverlayPlacement.safeArea]). */
    fun clampX(x: Int): Int = if (minX >= maxX) minX else x.coerceIn(minX, maxX)

    fun clampY(y: Int): Int = if (minY >= maxY) minY else y.coerceIn(minY, maxY)

    fun clamp(x: Int, y: Int): Pair<Int, Int> = clampX(x) to clampY(y)

    /** True when the position is fully visible without moving it. */
    fun contains(x: Int, y: Int): Boolean = x in minX..maxX && y in minY..maxY
}

internal object OverlayPlacement {

    /** Default status-bar allowance when the platform will not report it (24dp). */
    private const val FALLBACK_STATUS_BAR_DP = 24

    /** Default nav-bar allowance when the platform will not report it (48dp). */
    private const val FALLBACK_NAV_BAR_DP = 48

    /** Landscape gesture strips are thin (~24dp), portrait nav bars are taller. */
    private const val LANDSCAPE_NAV_STRIP_DP = 24

    /**
     * Portrait ⇔ height ≥ width. Uses the metrics rather than
     * `Configuration.orientation` because multi-window reports the *display's*
     * orientation, while the capsule must fit the space it is actually in.
     */
    fun isLandscape(screenWidthPx: Int, screenHeightPx: Int): Boolean = screenWidthPx > screenHeightPx

    fun isLandscape(context: Context): Boolean {
        val dm = context.resources.displayMetrics
        val configured = context.resources.configuration.orientation
        // A square-ish window (foldable inner screen, freeform resize) has no
        // reliable orientation; defer to the configuration in that case.
        if (dm.widthPixels == dm.heightPixels) {
            return configured == Configuration.ORIENTATION_LANDSCAPE
        }
        return isLandscape(dm.widthPixels, dm.heightPixels)
    }

    /**
     * The box a [viewWidth]×[viewHeight] overlay may occupy on a
     * [screenWidth]×[screenHeight] display without hiding under system chrome.
     *
     * @param statusBarPx height of the status bar (excluded at the top)
     * @param navBarPx portrait: height of the bottom nav/gesture bar;
     *   landscape: width of the side gesture strip, excluded on BOTH sides
     *   because we cannot know which edge the OEM put it on and a symmetric
     *   inset is always safe
     * @param edgePadPx the capsule's own breathing room from the edges
     */
    fun safeArea(
        screenWidth: Int,
        screenHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        statusBarPx: Int,
        navBarPx: Int,
        edgePadPx: Int,
    ): OverlaySafeArea {
        val minY = (statusBarPx + edgePadPx).coerceAtLeast(0)
        val landscape = isLandscape(screenWidth, screenHeight)
        return if (landscape) {
            val side = (navBarPx + edgePadPx).coerceAtLeast(0)
            OverlaySafeArea(
                minX = side,
                maxX = (screenWidth - viewWidth - side).coerceAtLeast(side),
                minY = minY,
                maxY = (screenHeight - viewHeight - edgePadPx).coerceAtLeast(minY),
            )
        } else {
            OverlaySafeArea(
                minX = edgePadPx.coerceAtLeast(0),
                maxX = (screenWidth - viewWidth - edgePadPx).coerceAtLeast(edgePadPx),
                minY = minY,
                maxY = (screenHeight - viewHeight - navBarPx - edgePadPx).coerceAtLeast(minY),
            )
        }
    }

    /** Convenience overload reading live metrics + insets from [context]. */
    fun safeArea(
        context: Context,
        viewWidth: Int,
        viewHeight: Int,
        edgePadPx: Int,
    ): OverlaySafeArea {
        val dm = context.resources.displayMetrics
        val insets = systemBarInsets(context)
        return safeArea(
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            statusBarPx = insets.first,
            navBarPx = insets.second,
            edgePadPx = edgePadPx,
        )
    }

    /** First-run placement: the bottom-left corner of the safe area. */
    fun defaultBottomLeft(area: OverlaySafeArea): Pair<Int, Int> = area.minX to area.maxY

    /**
     * (status bar px, nav bar px) for the capsule's display.
     *
     * API 30+ asks the platform; older releases have no display-scoped inset API
     * reachable from a service, so they fall back to the framework resource
     * dimensions and then to dp constants. Everything is best-effort: an
     * exception here must never take the overlay down.
     */
    fun systemBarInsets(context: Context): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val fromPlatform = runCatching {
                val wm = context.getSystemService(WindowManager::class.java)
                val metrics = wm.currentWindowMetrics
                val status = metrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                val nav = if (isLandscape(context)) {
                    val side = metrics.windowInsets
                        .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                    maxOf(side.left, side.right)
                } else {
                    metrics.windowInsets
                        .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
                }
                status to nav
            }.getOrNull()
            if (fromPlatform != null && fromPlatform.first > 0) return fromPlatform
        }
        val status = resourceDp(context, "status_bar_height") ?: (FALLBACK_STATUS_BAR_DP * density).toInt()
        val nav = if (isLandscape(context)) {
            (LANDSCAPE_NAV_STRIP_DP * density).toInt()
        } else {
            resourceDp(context, "navigation_bar_height") ?: (FALLBACK_NAV_BAR_DP * density).toInt()
        }
        return status to nav
    }

    private fun resourceDp(context: Context, name: String): Int? = runCatching {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        if (id == 0) return@runCatching null
        context.resources.getDimensionPixelSize(id)
    }.getOrNull()
}
