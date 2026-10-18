package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-orientation-position] The floating capsule must be fully on
 * screen in BOTH orientations, and its saved position must be remembered per
 * orientation.
 *
 * The capsule is `TYPE_APPLICATION_OVERLAY`: it is not recreated on rotation, so
 * every rule here is about re-clamping an existing window — the case where a
 * wrong box leaves it half-visible under the status bar or the gesture strip.
 */
class OverlayPlacementTest {

    // A typical 1080×2400 phone.
    private val w = 1080
    private val h = 2400
    private val status = 72     // status bar
    private val navPortrait = 132   // bottom gesture bar
    private val navLandscape = 60   // side strip
    private val pad = 8

    private val capsuleW = 420
    private val capsuleH = 120

    @Test
    fun portraitExcludesStatusBarAndNavBar() {
        val area = OverlayPlacement.safeArea(w, h, capsuleW, capsuleH, status, navPortrait, pad)
        assertEquals(status + pad, area.minY)
        assertEquals(h - capsuleH - navPortrait - pad, area.maxY)
        assertEquals(pad, area.minX)
        assertEquals(w - capsuleW - pad, area.maxX)
    }

    @Test
    fun landscapeExcludesBothSideStrips() {
        // We cannot know which edge the OEM put the gesture strip on, so the
        // inset is applied symmetrically — that is always safe.
        val area = OverlayPlacement.safeArea(h, w, capsuleW, capsuleH, status, navLandscape, pad)
        assertEquals(navLandscape + pad, area.minX)
        assertEquals(h - capsuleW - navLandscape - pad, area.maxX)
        assertEquals(status + pad, area.minY)
        // No bottom nav bar in landscape.
        assertEquals(w - capsuleH - pad, area.maxY)
    }

    @Test
    fun clampKeepsTheWholeCapsuleVisible() {
        val area = OverlayPlacement.safeArea(w, h, capsuleW, capsuleH, status, navPortrait, pad)
        val (x, y) = area.clamp(-500, -500)
        assertEquals(area.minX, x)
        assertEquals(area.minY, y)
        val (x2, y2) = area.clamp(99_999, 99_999)
        assertEquals(area.maxX, x2)
        assertEquals(area.maxY, y2)
        // The far corner still fits: x + width and y + height stay on screen.
        assertTrue(x2 + capsuleW <= w)
        assertTrue(y2 + capsuleH + navPortrait <= h)
    }

    @Test
    fun clampNeverThrowsWhenTheWindowIsBiggerThanTheScreen() {
        // A freeform/multi-window resize can hand us a view wider than the
        // display; coerceIn on an empty range throws, so the box must degrade to
        // a single point instead.
        val area = OverlayPlacement.safeArea(400, 300, 900, 900, status, navPortrait, pad)
        assertTrue("minX must not exceed maxX", area.minX <= area.maxX)
        assertTrue("minY must not exceed maxY", area.minY <= area.maxY)
        assertEquals(area.minX, area.clampX(12345))
        assertEquals(area.minY, area.clampY(-12345))
    }

    @Test
    fun firstRunPlacementIsInsideTheAreaAndOnScreen() {
        val area = OverlayPlacement.safeArea(w, h, capsuleW, capsuleH, status, navPortrait, pad)
        val (x, y) = OverlayPlacement.defaultBottomLeft(area)
        assertTrue(area.contains(x, y))
        // Bottom-left, i.e. above the nav bar, not under the status bar.
        assertTrue(y + capsuleH + navPortrait <= h)
        assertTrue(y >= status)
    }

    @Test
    fun positionSavedInTheOtherOrientationIsClampedNotDiscarded() {
        // The user dragged to the bottom-right in portrait: x = 640, y = 2100.
        // Rotating must not put the capsule under the landscape side strip.
        val landscape = OverlayPlacement.safeArea(h, w, capsuleW, capsuleH, status, navLandscape, pad)
        val (x, y) = landscape.clamp(640, 2100)
        assertTrue("landscape x must clear the side strip", x >= navLandscape + pad)
        assertTrue("landscape x must keep the capsule on screen", x + capsuleW <= h - navLandscape - pad)
        assertTrue("landscape y must clear the status bar", y >= status + pad)
        assertTrue("landscape y must keep the capsule on screen", y + capsuleH + pad <= w)
    }

    @Test
    fun containsRejectsAHalfVisibleCapsule() {
        val area = OverlayPlacement.safeArea(w, h, capsuleW, capsuleH, status, navPortrait, pad)
        // A capsule one pixel above the status-bar allowance is not "contained":
        // this is exactly the "only half visible" state the clamp exists to stop.
        assertFalse(area.contains(area.minX, area.minY - 1))
        assertFalse(area.contains(area.maxX + 1, area.maxY))
        assertTrue(area.contains(area.minX, area.minY))
        assertTrue(area.contains(area.maxX, area.maxY))
    }

    @Test
    fun orientationComesFromTheMetricsBeingClamped() {
        // Multi-window reports the DISPLAY's orientation, so the capsule's own
        // box decides: a wide-short box is landscape even on a portrait phone.
        assertTrue(OverlayPlacement.isLandscape(2400, 1080))
        assertFalse(OverlayPlacement.isLandscape(1080, 2400))
    }
}
