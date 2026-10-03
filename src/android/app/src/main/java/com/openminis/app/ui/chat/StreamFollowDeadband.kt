package com.openminis.app.ui.chat

/**
 * [T-android-stream-follow-deadband] Should the streaming auto-follow act on
 * this position sample, or sit this one out?
 *
 * The streaming follow loop glides the viewport toward the bottom whenever the
 * growing message pushes it away. The distance it chases is
 * `firstVisibleItemIndex * avgItemSize + firstVisibleItemScrollOffset`, and that
 * number does not only GROW — a line can get SHORTER after it was already laid
 * out, because inline markdown re-measures as it streams:
 *
 *   `` `applicationId` `` arrives one character at a time. Until the closing
 *   backtick lands, parseInline renders it as plain text; the moment it lands
 *   the span becomes an inline-code chip with its own padding and a monospace
 *   face, and the line's measured height changes.
 *
 * Measured from a user report (2026-09-02, three frames 17s apart): the viewport
 * moved -18px, then +8px — up, then back down — while a whole line of new text
 * was arriving. Content that only ever grows cannot move the viewport DOWN; the
 * reversal was the re-measure landing mid-glide, and the follow loop dutifully
 * "corrected" for it. That correction is the visible jitter.
 *
 * The rule: while the stream is actively growing, ignore a SMALL reversal for a
 * short settle window. Anything larger, or anything still present after the
 * window, is treated as a real layout change and followed normally.
 *
 * Deliberately amplitude-based rather than component-based. We cannot ask "was
 * that an image?" from here, and we should not try: a text reflow is bounded by
 * roughly one line, while an image/table/code-block settling its height moves
 * hundreds of pixels. One threshold separates them without enumerating widget
 * types, so a future widget needs no new case.
 */
object StreamFollowDeadband {

    /**
     * Reversals smaller than this are candidates for suppression.
     *
     * ~1.5 lines at this app's body size. The observed inline-code reflow was
     * 8px and CJK line-height shifts land in the 8–18px band; a genuine block
     * change (image finishing decode, table settling, code block measuring) is
     * an order of magnitude larger and must pass through.
     */
    const val SHRINK_DEADBAND_PX = 24f

    /**
     * How long after the last real growth a small reversal is still assumed to
     * be re-measure noise.
     *
     * Sized against the follow loop's own 120ms sample: two ticks. Long enough
     * to swallow the reflow that follows a chunk, short enough that a reversal
     * which is genuinely the new resting position is honoured on the next tick
     * rather than stranding the viewport. Without this bound, suppression would
     * be permanent and a real shrink at end-of-stream would never be corrected.
     */
    const val SHRINK_SETTLE_MS = 250L

    /**
     * @param current    this sample's distance-from-bottom, in px
     * @param previous   the distance at the last sample the loop acted on
     * @param msSinceGrowth elapsed ms since the distance last INCREASED
     * @return true when the follow loop should skip this sample
     */
    fun shouldSuppress(
        current: Float,
        previous: Float,
        msSinceGrowth: Long,
    ): Boolean {
        val delta = current - previous
        // Growing (or unchanged) — always follow. This is the common path.
        if (delta >= 0f) return false
        // Reversal. Large ones are real layout, not a text reflow.
        if (-delta >= SHRINK_DEADBAND_PX) return false
        // Small reversal, but the stream has gone quiet — accept it as the new
        // resting position instead of suppressing forever.
        if (msSinceGrowth >= SHRINK_SETTLE_MS) return false
        return true
    }
}
