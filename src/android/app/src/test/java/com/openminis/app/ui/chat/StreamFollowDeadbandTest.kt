package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stream-follow-deadband] The streaming auto-follow must ignore the
 * small backwards step that inline markdown re-measure produces, without going
 * deaf to a real layout change. Mirrors upstream
 * `StreamFollowDeadbandTest.kt` (1.14), which is public source.
 */
class StreamFollowDeadbandTest {

    private val d = StreamFollowDeadband.SHRINK_DEADBAND_PX
    private val t = StreamFollowDeadband.SHRINK_SETTLE_MS

    // ── the reported sequence ────────────────────────────────────────────────

    @Test
    fun smallReversalDuringActiveGrowthIsSuppressed() {
        // 8 px backwards, 40 ms after the last growth — the measured case.
        assertTrue(
            "an 8px reversal mid-stream is a text reflow, not the user's content moving",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = 40L),
        )
    }

    @Test
    fun growthIsAlwaysFollowed() {
        assertFalse(
            "the common path: content grew, the viewport must catch up",
            StreamFollowDeadband.shouldSuppress(current = 140f, previous = 100f, msSinceGrowth = 0L),
        )
    }

    @Test
    fun noChangeIsNotSuppressed() {
        assertFalse(
            StreamFollowDeadband.shouldSuppress(current = 100f, previous = 100f, msSinceGrowth = 0L),
        )
    }

    // ── the exception that keeps real widgets visible ────────────────────────

    @Test
    fun largeReversalIsFollowedEvenDuringActiveGrowth() {
        // An image finishing decode / a table settling moves hundreds of px.
        assertFalse(
            "a large reversal is a real layout change and must not be swallowed",
            StreamFollowDeadband.shouldSuppress(current = 100f, previous = 400f, msSinceGrowth = 0L),
        )
    }

    @Test
    fun reversalExactlyAtThresholdIsFollowed() {
        assertFalse(
            "the deadband is exclusive — at the threshold we defer to real layout",
            StreamFollowDeadband.shouldSuppress(
                current = 100f, previous = 100f + d, msSinceGrowth = 0L,
            ),
        )
    }

    @Test
    fun justInsideThresholdIsSuppressed() {
        assertTrue(
            StreamFollowDeadband.shouldSuppress(
                current = 100f, previous = 100f + d - 1f, msSinceGrowth = 0L,
            ),
        )
    }

    // ── suppression must not be permanent ────────────────────────────────────

    @Test
    fun smallReversalIsAcceptedOnceTheStreamGoesQuiet() {
        assertFalse(
            "after the settle window a small reversal is the new resting position; " +
                "suppressing forever would strand the viewport off-bottom",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = t),
        )
    }

    @Test
    fun settleBoundaryIsInclusive() {
        assertTrue(
            "one ms before the window closes, still suppressed",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = t - 1),
        )
    }

    // ── end-to-end: replay both sequences the fix must distinguish ───────────

    /** Feeds a distance series through the same bookkeeping ChatScreen does. */
    private fun replay(series: List<Pair<Float, Long>>): List<Boolean> {
        var lastDist = series.first().first
        var lastGrowMs = 0L
        val acted = mutableListOf<Boolean>()
        for ((dist, time) in series) {
            val suppressed = StreamFollowDeadband.shouldSuppress(dist, lastDist, time - lastGrowMs)
            acted += !suppressed
            if (!suppressed) {
                if (dist > lastDist) lastGrowMs = time
                lastDist = dist
            }
        }
        return acted
    }

    @Test
    fun growThenReflowThenGrowSuppressesOnlyTheReflow() {
        // distance-from-bottom, ms: grows, dips 8px (chip closes), grows again.
        val acted = replay(
            listOf(0f to 0L, 90f to 120L, 82f to 240L, 150f to 360L, 240f to 480L),
        )
        assertTrue("initial growth followed", acted[1])
        assertFalse("the 8px reflow dip is skipped", acted[2])
        assertTrue("growth after the dip is followed again", acted[3])
        assertTrue(acted[4])
    }

    @Test
    fun growThenLargeShrinkIsFollowed() {
        // An image finishes decode and the content collapses by 300px.
        val acted = replay(listOf(0f to 0L, 400f to 120L, 100f to 240L))
        assertTrue(acted[1])
        assertTrue("a 300px collapse must be followed, not swallowed", acted[2])
    }
}
