package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-settle-programmatic-scroll] The post-drag settle re-pin must fire
 * only when the scroll that just ended was the USER'S drag, never when it was
 * the streaming auto-follow's own `scroll { }` glide.
 *
 * Measured on a Pixel 4a streaming 300 short paragraphs: the settle fired 103
 * times in one turn, once after each follow glide, because it keyed on the
 * `isScrollInProgress -> false` edge that programmatic scrolls also produce.
 * On screen that read as an eased glide followed by an instant snap, over and
 * over. The gate replaces that edge with "a finger lifted recently".
 */
class SettleAfterInteractionGateTest {

    private val W = SettleAfterInteractionGate.RECENT_DRAG_MS

    private fun gate(
        scrollInProgress: Boolean = false,
        userScrolledAway: Boolean = false,
        nearBottom: Boolean = true,
        streaming: Boolean = true,
        msSinceDrag: Long = 200L,
    ) = SettleAfterInteractionGate.shouldSettle(
        scrollInProgress, userScrolledAway, nearBottom, streaming, msSinceDrag,
    )

    // ── the bug ─────────────────────────────────────────────────────────────

    @Test
    fun `a follow glide ending with no recent drag does not settle`() {
        // No finger has touched the list this session: lastInterruptMs is 0,
        // so msSinceDrag is effectively "forever". This is every one of the
        // 103 measured fires.
        assertFalse(gate(msSinceDrag = Long.MAX_VALUE / 2))
        assertFalse(gate(msSinceDrag = W + 1))
    }

    // ── what T170 exists for ────────────────────────────────────────────────

    @Test
    fun `a drag that just ended near the bottom while streaming settles`() {
        assertTrue(gate(msSinceDrag = 0))
        assertTrue(gate(msSinceDrag = 400))
        assertTrue("window edge is inclusive", gate(msSinceDrag = W))
    }

    @Test
    fun `a fling that outlives the finger still counts as that drag`() {
        // DragInteraction.Stop fires at finger lift; the fling settles later.
        // Inside the window it is still the user's interaction ending.
        assertTrue(gate(msSinceDrag = W - 1))
    }

    // ── the guards the old condition already had, kept verbatim ────────────

    @Test
    fun `never settles while a scroll is still in progress`() {
        assertFalse(gate(scrollInProgress = true))
    }

    @Test
    fun `never settles when the user has scrolled away`() {
        assertFalse(gate(userScrolledAway = true))
    }

    @Test
    fun `never settles when not near the bottom`() {
        assertFalse(gate(nearBottom = false))
    }

    @Test
    fun `never settles when nothing is streaming`() {
        // The "small upward drag snaps back to bottom" bug: a finger lift near
        // the bottom outside a stream must not yank the viewport.
        assertFalse(gate(streaming = false))
    }

    @Test
    fun `a negative clock delta is treated as no drag`() {
        // Clock skew / a lastInterruptMs written in the future must fail
        // closed rather than count as "recent".
        assertFalse(gate(msSinceDrag = -1))
    }
}
