package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-follow-version] Contract tests for the thinking block's inner
 * auto-follow policy — the fix for "ThinkingBlock 展开后，思考内容增长到
 * 超过 300dp 时不再自动跟随尾部".
 *
 * The follow trigger in ThinkingBlock is keyed on a content VERSION holder
 * (a Compose MutableIntState written from recomposition), NOT on the
 * captured `block.content.length`: `block` is a plain immutable data class
 * that streaming replaces wholesale, so a snapshotFlow over its captured
 * properties never ticks. These tests pin the state-transition contract the
 * Compose side relies on:
 *
 *  1. follow is ON by default (not paused) — streaming content growth snaps;
 *  2. a drag that leaves the bottom pauses the follow;
 *  3. returning to the bottom resumes the follow (even mid-drag);
 *  4. non-gesture movement off the bottom (programmatic snap / layout
 *     growth) never flips the switch by itself;
 *  5. the follow decision on a content-growth tick is exactly "not paused".
 */
class ThinkingFollowPolicyTest {

    // ── 1. default state ────────────────────────────────────────────────

    @Test
    fun `follow is active by default so streaming growth snaps to bottom`() {
        // A fresh block starts unpaused — the default streaming behavior.
        val paused = false
        assertTrue(
            "fresh streaming block must follow its tail",
            ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused),
        )
    }

    // ── 2. drag away pauses ─────────────────────────────────────────────

    @Test
    fun `dragging away from the bottom pauses the follow`() {
        val next = ThinkingFollowPolicy.onScrollObserved(
            dragging = true,
            gap = 40,
            latestPaused = false,
        )
        assertTrue("a drag with a real gap must pause the follow", next)
    }

    @Test
    fun `tiny subpixel gap while dragging does not pause the follow`() {
        // The follow snap itself can land a hair short of maxValue; that
        // must not read as the user scrolling away.
        val next = ThinkingFollowPolicy.onScrollObserved(
            dragging = true,
            gap = ThinkingFollowPolicy.DRAG_GAP_THRESHOLD,
            latestPaused = false,
        )
        assertFalse("gap <= threshold counts as at-the-bottom", next)
    }

    // ── 3. return to bottom resumes ─────────────────────────────────────

    @Test
    fun `returning to the bottom resumes the follow`() {
        val paused = ThinkingFollowPolicy.onScrollObserved(
            dragging = true,
            gap = 200,
            latestPaused = false,
        )
        assertTrue("precondition: drag away paused the follow", paused)
        val resumed = ThinkingFollowPolicy.onScrollObserved(
            dragging = false,
            gap = 0,
            latestPaused = paused,
        )
        assertFalse("back at the bottom the follow must resume", resumed)
    }

    @Test
    fun `a drag settling at the bottom resumes the follow mid-drag`() {
        // The user drags up, then drags back down to the bottom and is still
        // touching the glass when the gap closes — that is a "back to
        // following" gesture, not a stuck pause.
        val paused = ThinkingFollowPolicy.onScrollObserved(
            dragging = true,
            gap = 200,
            latestPaused = false,
        )
        val resumed = ThinkingFollowPolicy.onScrollObserved(
            dragging = true,
            gap = 0,
            latestPaused = paused,
        )
        assertFalse("gap <= threshold wins even while dragging", resumed)
    }

    // ── 4. non-gesture movement is not intent ───────────────────────────

    @Test
    fun `programmatic snap off the bottom keeps the previous state`() {
        // The follow snap itself moves the viewport without a gesture; a
        // growing row is not user intent (same rule as the timeline's
        // ChatScrollPolicy.rearmIfDraggedAway).
        val stillPaused = ThinkingFollowPolicy.onScrollObserved(
            dragging = false,
            gap = 120,
            latestPaused = true,
        )
        assertTrue("no drag in progress must keep the paused state", stillPaused)
        val stillFollowing = ThinkingFollowPolicy.onScrollObserved(
            dragging = false,
            gap = 120,
            latestPaused = false,
        )
        assertFalse("no drag in progress must keep the following state", stillFollowing)
    }

    // ── 5. content-growth tick contract ─────────────────────────────────

    @Test
    fun `content growth follows only while not paused`() {
        assertTrue(
            "growth tick must snap while following",
            ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused = false),
        )
        assertFalse(
            "growth tick must NOT snap while paused",
            ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused = true),
        )
    }

    @Test
    fun `production follow observes the live state backed content version`() {
        val followSource = ProductionSources.read("ui/chat/ThinkingAutoFollow.kt")
        val renderSource = ProductionSources.read("ui/chat/ChatAssistantMessageUI.kt")

        assertTrue(
            "recomposition must publish the current block version into Compose state",
            "SideEffect { contentVersion.intValue = contentVersionValue }" in followSource,
        )
        assertTrue(
            "the streaming collector must observe the state holder, not a captured data class",
            "snapshotFlow { contentVersion.intValue to scrollState.maxValue }" in followSource,
        )
        assertFalse(
            "snapshotFlow over a captured AssistantBlock cannot observe replacement objects",
            "snapshotFlow { block.content.length }" in followSource,
        )
        assertTrue(
            "ThinkingBlock must pass the latest content length to the follow owner",
            "contentVersionValue = block.content.length" in renderSource,
        )
    }

    // ── full user journey ───────────────────────────────────────────────

    @Test
    fun `journey - follow, pause on drag up, resume on return to bottom`() {
        var paused = false
        // streaming ticks follow…
        assertTrue(ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused))
        // user drags up to read history…
        paused = ThinkingFollowPolicy.onScrollObserved(true, 500, paused)
        assertTrue(paused)
        // …more tokens arrive, no yank…
        assertFalse(ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused))
        // …user scrolls back to the bottom…
        paused = ThinkingFollowPolicy.onScrollObserved(false, 2, paused)
        assertFalse(paused)
        // …streaming resumes following.
        assertTrue(ThinkingFollowPolicy.shouldFollowOnContentGrowth(paused))
    }
}
