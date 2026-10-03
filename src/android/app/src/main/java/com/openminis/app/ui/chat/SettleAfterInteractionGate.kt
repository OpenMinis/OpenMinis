package com.openminis.app.ui.chat

/**
 * [T-android-settle-programmatic-scroll] Decides whether the post-drag
 * "settle" re-pin (T170, mirrors iOS settleAfterInteraction) may fire.
 *
 * The settle exists for one situation: the user's finger was on the list, the
 * streaming auto-follow was suppressed while they dragged, content grew in the
 * meantime, and the finger lifted near the bottom — so one extra pin catches
 * the growth that was missed. It was driven from the
 * `isScrollInProgress -> false` edge, which is wrong: the auto-follow glide is
 * itself a `listState.scroll { }` block, so every glide that finished also
 * flipped that edge and the settle snapped `scrollToItem(0, 0)` on top of it.
 * Measured on a Pixel 4a during a 300-paragraph stream: 103 settle fires in
 * one turn, and on screen a repeating pattern of an eased glide followed by an
 * instant jump — the "来回抖" the user reported. Programmatic scrolls emit no
 * DragInteraction; only a real finger lift records [msSinceDrag]'s timestamp.
 *
 * Pure so it can be unit-tested; the composable passes its own state in.
 */
object SettleAfterInteractionGate {

    /**
     * How long after a `DragInteraction.Stop` a scroll-end still counts as the
     * end of THAT drag (including its fling). Same window the return-to-bottom
     * re-arm already uses for "recent user interaction".
     */
    const val RECENT_DRAG_MS = 1500L

    /**
     * @param scrollInProgress current `listState.isScrollInProgress`
     * @param userScrolledAway the user has parked the viewport away from the bottom
     * @param nearBottom       viewport is within the near-bottom threshold
     * @param streaming        a reply is currently streaming
     * @param msSinceDrag      now - lastInterruptMs (last DragInteraction.Stop);
     *                         `Long.MAX_VALUE`-ish when no drag has ever happened
     * @return true when the settle re-pin should run
     */
    fun shouldSettle(
        scrollInProgress: Boolean,
        userScrolledAway: Boolean,
        nearBottom: Boolean,
        streaming: Boolean,
        msSinceDrag: Long,
    ): Boolean {
        if (scrollInProgress) return false
        if (userScrolledAway) return false
        if (!nearBottom) return false
        if (!streaming) return false
        // The discriminator: a scroll that just ended is only "the user's drag
        // ending" if a finger actually lifted recently. The auto-follow glide,
        // pin-to-bottom and the settle itself all end scrolls without one.
        return msSinceDrag in 0..RECENT_DRAG_MS
    }
}
