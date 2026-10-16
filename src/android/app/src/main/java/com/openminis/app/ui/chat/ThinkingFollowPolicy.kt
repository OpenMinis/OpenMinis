package com.openminis.app.ui.chat

/**
 * [T-thinking-follow-version] Pure state machine for the thinking block's
 * inner auto-follow scroller. Extracted from ThinkingBlock so the follow
 * semantics are unit-testable without Compose (the Compose side only
 * forwards raw scroll observations and content-growth ticks into it).
 *
 * Contract (mirrors the timeline's ChatScrollPolicy, scoped to the 300dp
 * inner scroller of one thinking block):
 * - While streaming and NOT paused, every content-growth tick snaps the
 *   scroller to the new bottom.
 * - The user dragging away from the bottom (gap > [DRAG_GAP_THRESHOLD]
 *   while a drag is in progress) pauses the follow.
 * - The user returning to the bottom (gap <= [DRAG_GAP_THRESHOLD]) resumes
 *   the follow — even mid-drag, so a drag that ends at the bottom is a
 *   "back to following" gesture, not a stuck pause.
 * - Non-streaming blocks never start following (the caller gates the effect
 *   on isStreaming; this policy adds no extra rule for it).
 *
 * The pause flag itself lives in Compose state in ThinkingBlock; these
 * functions are the ONLY writers of its value.
 */
internal object ThinkingFollowPolicy {

    /**
     * Gap (px) between the current scroll position and the bottom below
     * which the viewport counts as "at the bottom". Small nonzero gaps come
     * from subpixel layout and from the follow snap itself landing a hair
     * short — those must not read as "user scrolled away".
     */
    const val DRAG_GAP_THRESHOLD: Int = 4

    /**
     * A raw scroll observation from the scroller's snapshot flow.
     *
     * @param dragging true while a touch drag/fling is driving the scroller
     * @param gap pixels from the bottom, clamped at 0
     * @param latestPaused the paused flag as of the previous observation
     * @return the new paused flag
     */
    fun onScrollObserved(dragging: Boolean, gap: Int, latestPaused: Boolean): Boolean =
        when {
            // Back at the bottom: follow resumes — even mid-drag, so a drag
            // that settles at the bottom is a "back to following" gesture.
            gap <= DRAG_GAP_THRESHOLD -> false
            // Off the bottom with a finger on the glass: the user took control.
            dragging && gap > DRAG_GAP_THRESHOLD -> true
            // Off the bottom with no drag in progress (programmatic snap,
            // fling settle, layout growth): keep whatever state we had — a
            // growing row is not intent (same rule as
            // ChatScrollPolicy.rearmIfDraggedAway).
            else -> latestPaused
        }

    /**
     * Whether a content-growth tick should snap the scroller to the bottom.
     */
    fun shouldFollowOnContentGrowth(paused: Boolean): Boolean = !paused
}
