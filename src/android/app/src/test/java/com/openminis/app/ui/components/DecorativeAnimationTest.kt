package com.openminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-decorative-anim-perf] The pure phase helpers every decorative
 * animation now derives its motion from. If these drift, every spinner, dot
 * and shimmer drifts with them, so the shape is pinned here.
 */
class DecorativeAnimationTest {

    private val ms = 1_000_000L

    @Test
    fun `phase walks 0 to 1 over one period and wraps`() {
        assertEquals(0f, decorativePhase(0L, 1000), 1e-6f)
        assertEquals(0.25f, decorativePhase(250 * ms, 1000), 1e-6f)
        assertEquals(0.5f, decorativePhase(500 * ms, 1000), 1e-6f)
        // Exactly one period later is the start again, not 1f.
        assertEquals(0f, decorativePhase(1000 * ms, 1000), 1e-6f)
        assertEquals(0.1f, decorativePhase(1100 * ms, 1000), 1e-6f)
    }

    @Test
    fun `offset staggers the phase and stays in range for negative shifts`() {
        // 120 ms behind a sibling: at t=120ms the offset dot is at its start.
        assertEquals(0f, decorativePhase(120 * ms, 700, 120), 1e-6f)
        // Before the offset the modulo of a negative number must still land in [0,1).
        val p = decorativePhase(0L, 700, 120)
        assertTrue("$p", p >= 0f && p < 1f)
        assertEquals((700f - 120f) / 700f, p, 1e-5f)
    }

    @Test
    fun `phase never reaches 1 and is safe on a non-positive period`() {
        val p = decorativePhase(999_999_999L, 1000)
        assertTrue("$p", p < 1f)
        assertEquals(0f, decorativePhase(12345L, 0), 0f)
        assertEquals(0f, decorativePhase(12345L, -5), 0f)
    }

    @Test
    fun `ping pong is the Reverse repeat shape`() {
        // 0 -> 1 across the first half, 1 -> 0 across the second.
        assertEquals(0f, decorativePingPong(0f), 1e-6f)
        assertEquals(0.5f, decorativePingPong(0.25f), 1e-6f)
        assertEquals(1f, decorativePingPong(0.5f), 1e-6f)
        assertEquals(0.5f, decorativePingPong(0.75f), 1e-6f)
        // Tolerates callers handing it an unwrapped phase.
        assertEquals(0.5f, decorativePingPong(1.25f), 1e-6f)
    }

    @Test
    fun `clock interval is a throttle below the panel rate`() {
        // 90 Hz is ~11.1 ms; the decorative clock must step markedly slower.
        assertTrue(DecorativeClock.INTERVAL_NS >= 30_000_000L)
        // But not so slow that a 1 s spin looks stepped.
        assertTrue(DecorativeClock.INTERVAL_NS <= 40_000_000L)
    }
}
