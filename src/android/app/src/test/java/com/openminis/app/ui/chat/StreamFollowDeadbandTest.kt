package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stream-follow-deadband] The streaming auto-follow must ignore the
 * small backwards step that inline markdown re-measure produces, without going
 * deaf to a real layout change.
 *
 * Reported 2026-09-02: during a long stream the last line kept drifting toward
 * the tool card in uneven steps. Measured from three frames — viewport moved
 * -18px then +8px while a whole new line arrived, which append-only growth
 * cannot produce. Cause: `` `applicationId` `` finished streaming, its closing
 * backtick turned plain text into an inline-code chip, the line re-measured
 * shorter, and the follow loop chased the reversal.
 */
class StreamFollowDeadbandTest {

    private val D = StreamFollowDeadband.SHRINK_DEADBAND_PX
    private val T = StreamFollowDeadband.SHRINK_SETTLE_MS

    // ── the reported sequence ────────────────────────────────────────────────

    @Test
    fun `small reversal during active growth is suppressed`() {
        // 8px backwards, 40ms after the last growth — the measured case.
        assertTrue(
            "an 8px reversal mid-stream is a text reflow, not the user's content moving",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = 40L),
        )
    }

    @Test
    fun `growth is always followed`() {
        assertFalse(
            "the common path: content grew, the viewport must catch up",
            StreamFollowDeadband.shouldSuppress(current = 140f, previous = 100f, msSinceGrowth = 0L),
        )
    }

    @Test
    fun `no change is not suppressed`() {
        assertFalse(
            StreamFollowDeadband.shouldSuppress(current = 100f, previous = 100f, msSinceGrowth = 0L),
        )
    }

    // ── the exception the user asked for: big widgets still win ──────────────

    @Test
    fun `large reversal is followed even during active growth`() {
        // An image finishing decode / a table settling moves hundreds of px.
        assertFalse(
            "a large reversal is a real layout change and must not be swallowed",
            StreamFollowDeadband.shouldSuppress(current = 100f, previous = 400f, msSinceGrowth = 0L),
        )
    }

    @Test
    fun `reversal exactly at the threshold is followed`() {
        assertFalse(
            "the deadband is exclusive — at the threshold we defer to real layout",
            StreamFollowDeadband.shouldSuppress(
                current = 100f, previous = 100f + D, msSinceGrowth = 0L,
            ),
        )
    }

    @Test
    fun `just inside the threshold is suppressed`() {
        assertTrue(
            StreamFollowDeadband.shouldSuppress(
                current = 100f, previous = 100f + D - 1f, msSinceGrowth = 0L,
            ),
        )
    }

    // ── suppression must not be permanent ────────────────────────────────────

    @Test
    fun `small reversal is accepted once the stream goes quiet`() {
        assertFalse(
            "after the settle window a small reversal is the new resting position; " +
                "suppressing forever would strand the viewport off-bottom",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = T),
        )
    }

    @Test
    fun `settle boundary is inclusive`() {
        assertTrue(
            "one ms before the window closes, still suppressed",
            StreamFollowDeadband.shouldSuppress(current = 92f, previous = 100f, msSinceGrowth = T - 1),
        )
    }

    // ── end-to-end: replay both sequences the fix must distinguish ───────────

    /** Feeds a distance series through the same bookkeeping the collect body does. */
    private fun replay(series: List<Pair<Float, Long>>): List<Boolean> {
        var lastDist = series.first().first
        var lastGrowMs = 0L
        val acted = mutableListOf<Boolean>()
        for ((dist, t) in series) {
            val suppressed = StreamFollowDeadband.shouldSuppress(dist, lastDist, t - lastGrowMs)
            acted += !suppressed
            if (!suppressed) {
                if (dist > lastDist) lastGrowMs = t
                lastDist = dist
            }
        }
        return acted
    }

    @Test
    fun `grow then reflow then grow suppresses only the reflow`() {
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
    fun `grow then large shrink is followed`() {
        // An image finishes decode and the content collapses by 300px.
        val acted = replay(listOf(0f to 0L, 400f to 120L, 100f to 240L))
        assertTrue(acted[1])
        assertTrue("a 300px collapse must be followed, not swallowed", acted[2])
    }
}
