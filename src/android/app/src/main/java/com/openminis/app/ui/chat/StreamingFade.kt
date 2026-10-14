package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * [T-android-stream-fade] Word-level fade-in for streamed markdown text.
 *
 * Mirror of iOS TextFadeAnimator: newly appended characters render at α=0
 * and ease to α=1 over [FADE_DURATION_MS], with a small staggered delay
 * across word boundaries so the appearance reads as "words landing in
 * sequence" rather than "a single block flashing on". Only the streaming
 * last block opts in via [LocalAppendOnlyFade]; everything else (history,
 * cold-loaded sessions, completed messages) renders fully opaque.
 *
 * Implementation:
 *  - Each MdText with the local set to true holds a [FadeController] that
 *    tracks the previous plainText prefix. When the new plainText extends
 *    that prefix, the suffix gets sliced into word ranges, each tagged with
 *    a (startTimeNanos, staggerOffsetMs) pair.
 *  - A single `withFrameNanos` loop in the composable advances animation
 *    progress and writes the current alpha to a snapshot-state map. The
 *    MdText reads that map when composing its AnnotatedString overlay, so
 *    only this one MdText recomposes per frame — sibling blocks are inert.
 *  - When all ranges reach α=1 the loop suspends until a new append
 *    arrives, keeping idle cost at zero.
 *  - A guard caps the in-flight word count: bursts beyond [MAX_FADE_WORDS]
 *    are emitted fully opaque instead, matching iOS's TextFadeAnimator
 *    `maxAnimatedWords = 160` short-circuit so a 1k-token reflow can't
 *    saturate the frame budget.
 */

internal val LocalAppendOnlyFade = compositionLocalOf { false }

// [T-android-streaming-incremental-inline] True only for the LIVE streaming tail
// block. When set, RenderBlock's Paragraph branch routes inline/math through
// the incremental cache (frozen closed prefix + fresh unclosed suffix) instead
// of re-scanning the whole growing paragraph every throttle tick. Off (false)
// for every frozen/history block, which keeps the plain per-block cache.
internal val LocalLiveIncremental = compositionLocalOf { false }

// [T-android-stream-fade] Fade made more legible after the dual-path flush +
// smooth scroll landed: a flush batch is ~5–12 words and the newline fast-path
// can fire again in <100ms, so a tight 100ms stagger window made the whole
// batch light up almost together and the per-word reveal was invisible.
// Widening the stagger window to 300ms (and the per-word cap to 90ms) spreads
// the words within a batch into a clearly sequential left-to-right reveal even
// when the next batch arrives quickly; the 350ms per-word fade is unchanged
// (longer starts to feel laggy).
private const val FADE_DURATION_MS = 350L
private const val STAGGER_WINDOW_MS = 300L
private const val PER_WORD_STAGGER_MS = 90L
private const val MAX_FADE_WORDS = 160

private data class FadeRange(
    val start: Int,
    val end: Int,
    val staggerMs: Long,
)

internal class FadeController {
    /** plainText prefix already seen — anything beyond this is fresh. */
    var lastPlainText: String = ""
        private set

    /** Active animating ranges. Frozen at α=1 ranges are removed each tick. */
    private val rangesState: SnapshotStateList<FadeRange> = mutableListOf<FadeRange>().toMutableStateList()

    /** start-time nanos per range (parallel to rangesState; same indexing). */
    private val rangeStartNanos = ArrayDeque<Long>()

    /** Per-range current alpha, updated each frame; read in [overlay]. */
    val alphas: SnapshotStateMap<Int, Float> = SnapshotStateMap()

    /** True when at least one range is still under α=1. Drives the frame loop. */
    val hasActiveRanges: Boolean get() = rangesState.isNotEmpty()

    fun ingest(newPlainText: String) {
        if (newPlainText == lastPlainText) return
        // On a hard reset (text shrank or diverged from prefix), drop all
        // in-flight ranges — the caller is rendering a brand-new block.
        if (!newPlainText.startsWith(lastPlainText)) {
            rangesState.clear()
            rangeStartNanos.clear()
            alphas.clear()
            lastPlainText = newPlainText
            return
        }
        val base = lastPlainText.length
        val suffix = newPlainText.substring(base)
        lastPlainText = newPlainText
        if (suffix.isEmpty()) return

        // Split suffix into word-like runs separated by whitespace. Punctuation
        // stays attached to its preceding word (iOS does the same), keeping
        // the rhythm of "words landing" rather than "every glyph landing".
        val words = mutableListOf<IntRange>()
        var cursor = 0
        var inWord = false
        var wordStart = 0
        for (i in suffix.indices) {
            val c = suffix[i]
            val isWs = c.isWhitespace()
            if (!isWs && !inWord) {
                wordStart = i; inWord = true
            } else if (isWs && inWord) {
                words.add(wordStart until i); inWord = false
            }
        }
        if (inWord) words.add(wordStart until suffix.length)

        // Whitespace-only suffix: nothing visible to fade — skip.
        if (words.isEmpty()) return

        // Stagger budget mirrors iOS: window is fixed (100ms), so per-word
        // stagger shrinks as word count grows; capped at 60ms per word.
        val totalWords = words.size + rangesState.size
        if (totalWords > MAX_FADE_WORDS) {
            // Too many in flight — flush everything to α=1 and skip the new
            // ranges so we don't spend frames rendering an invisible wall.
            rangesState.clear()
            rangeStartNanos.clear()
            alphas.clear()
            return
        }
        val perWordStaggerMs = minOf(PER_WORD_STAGGER_MS, STAGGER_WINDOW_MS / words.size.coerceAtLeast(1))

        for ((idx, wr) in words.withIndex()) {
            val absStart = base + wr.first
            val absEnd = base + wr.last + 1
            val staggerMs = idx * perWordStaggerMs
            rangesState.add(FadeRange(absStart, absEnd, staggerMs))
            rangeStartNanos.addLast(System.nanoTime())
        }
    }

    /**
     * Advance every range to its current alpha based on [nowNanos]. Returns
     * false when no ranges remain animating (caller can suspend the loop).
     */
    fun tick(nowNanos: Long): Boolean {
        if (rangesState.isEmpty()) return false
        val finished = mutableListOf<Int>()
        for (i in rangesState.indices) {
            val r = rangesState[i]
            val startNs = rangeStartNanos.elementAt(i)
            val elapsedMs = (nowNanos - startNs) / 1_000_000L - r.staggerMs
            val alpha = if (elapsedMs <= 0) 0f
            else if (elapsedMs >= FADE_DURATION_MS) 1f
            else {
                val t = elapsedMs.toFloat() / FADE_DURATION_MS
                // Ease-out cubic 1 - (1-t)^3 (matches iOS animator curve).
                val inv = 1f - t
                1f - inv * inv * inv
            }
            alphas[r.start] = alpha
            if (alpha >= 1f) finished.add(i)
        }
        // Pop finished ranges from the end so indices shift predictably.
        for (i in finished.asReversed()) {
            val r = rangesState.removeAt(i)
            rangeStartNanos.removeAt(i)
            alphas.remove(r.start)
        }
        return rangesState.isNotEmpty()
    }

    /**
     * Build an AnnotatedString that re-colours each active range to apply
     * its current alpha. Inactive (α=1) ranges drop out automatically as
     * [tick] removes them; the surrounding text and original spans are
     * preserved.
     */
    fun overlay(base: AnnotatedString, baseColor: Color): AnnotatedString {
        if (rangesState.isEmpty()) return base
        return buildAnnotatedString {
            append(base)
            for (r in rangesState) {
                val a = alphas[r.start] ?: 0f
                if (r.end > base.length) continue
                addStyle(
                    SpanStyle(color = baseColor.copy(alpha = a)),
                    r.start,
                    r.end,
                )
            }
        }
    }
}

@Composable
internal fun rememberFadeController(): FadeController =
    remember { FadeController() }

/**
 * Drives the per-frame tick for [controller]. Suspends when nothing is
 * animating; resumes whenever [controller.hasActiveRanges] flips back to
 * true. Single instance per MdText so each animating block runs independently.
 */
@Composable
internal fun FadeFrameDriver(controller: FadeController) {
    // ticker is read inside withFrameNanos so the body re-suspends when no
    // ranges are active; a state read on hasActiveRanges restarts it.
    val active = controller.hasActiveRanges
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val anyActive = withFrameNanos { now -> controller.tick(now) }
            if (!anyActive) break
        }
    }
}
