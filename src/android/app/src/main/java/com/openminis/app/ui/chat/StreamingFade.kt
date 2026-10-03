package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.channels.Channel

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
 *    MdText reads that map ONLY from a draw lambda ([forEachActive]) that
 *    paints a background-coloured mask over each fading word inside its own
 *    graphics layer. Composition never reads it, so a frame costs a few
 *    small paths — not a recomposition, not a paragraph re-layout.
 *
 *    [T-android-stream-fade-relayout] It used to be consumed in composition:
 *    `overlay()` rebuilt the whole AnnotatedString with per-word alpha spans
 *    every frame, which made `Text` re-lay-out and re-record the ENTIRE live
 *    paragraph per vsync. Measured on a Pixel 4a: record(draw) 8→18 ms as the
 *    live block grew from 2k to 8k chars, ~93 % of frames over budget for the
 *    whole stream — the fade (350 ms + 300 ms stagger) never went idle between
 *    500 ms publishes, so this was the steady state, not a burst.
 *  - When all ranges reach α=1 the loop suspends until a new append
 *    arrives, keeping idle cost at zero.
 *  - A guard caps the in-flight word count: bursts beyond [MAX_FADE_WORDS]
 *    are emitted fully opaque instead, matching iOS's TextFadeAnimator
 *    `maxAnimatedWords = 160` short-circuit so a 1k-token reflow can't
 *    saturate the frame budget.
 */

internal val LocalAppendOnlyFade = compositionLocalOf { false }

/**
 * [T-android-stream-fade-reentry] How a streaming text node's fade starts
 * when its [FadeController] is created WITH fade on.
 *
 * Being created with fade on used to mean "this paragraph just appeared",
 * so all of its first text faded in. It does not always mean that. The node
 * is created from scratch whenever its composition is: re-entering a chat
 * whose reply is still streaming, or scrolling the streaming message out of
 * the list and back. Its text was on screen before, and fading it from
 * alpha 0 replays the animation over text the user had already read.
 * [FadeShownText] remembers what each node has shown, outside composition,
 * so a recreated node can tell the two apart.
 */
internal sealed class FadeBirth {
    /** Genuinely new paragraph: fade all of it. */
    object FadeAll : FadeBirth()

    /** All of it was on screen already: adopt it, fade nothing. */
    object SeedAll : FadeBirth()

    /** [shown] was on screen; only what follows it is new and fades. */
    data class SeedPrefix(val shown: String) : FadeBirth()
}

/**
 * [T-android-stream-fade-reentry] Decide how a new controller starts.
 *
 * @param fadeFromBirth fade was on at this node's first composition.
 * @param shown texts [FadeShownText] recorded as on screen for this node's
 *   fragment (most recent first).
 * @param text the text it is being created with.
 */
internal fun fadeBirthPlan(fadeFromBirth: Boolean, shown: List<String>, text: String): FadeBirth {
    // Flag flipped on later (the existing T-android-stream-fade-seed case):
    // the node rendered this text opaque before it had a controller.
    if (!fadeFromBirth) return FadeBirth.SeedAll
    // The longest recorded text this one extends: that much was on screen, so
    // only what follows it is new. A node recreated mid-stream finds its own
    // earlier text here; a genuinely new paragraph matches nothing.
    val prior = shown.filter { it.isNotEmpty() && text.startsWith(it) }.maxByOrNull { it.length }
        ?: return FadeBirth.FadeAll
    return if (prior.length == text.length) FadeBirth.SeedAll else FadeBirth.SeedPrefix(prior)
}

/**
 * [T-android-stream-fade-reentry] What the fading text nodes of each
 * streaming fragment have put on screen, keyed by "messageId/baseShardId"
 * (the fragment's selection shard id). Deliberately NOT the per-node shard
 * id: that carries a sub-index handed out in composition order, which can
 * change when the fragment is rebuilt — exactly when this is needed. A few
 * recent texts are kept per fragment; a recreated node finds its own earlier
 * text among them by prefix. Process-wide so it outlives the composition;
 * LRU-bounded like [TableHScrollStates]. Pure Kotlin (not
 * android.util.LruCache) so it is unit-testable.
 */
internal object FadeShownText {
    private const val MAX_FRAGMENTS = 64
    private const val TEXTS_PER_FRAGMENT = 6

    private val shown = object : LinkedHashMap<String, ArrayDeque<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<String>>): Boolean =
            size > MAX_FRAGMENTS
    }

    /** Recorded texts for [key], most recent first. */
    fun get(key: String): List<String> = synchronized(shown) { shown[key]?.toList() ?: emptyList() }

    /**
     * Record [text] as on screen. A text that extends the most recent entry
     * replaces it (the same paragraph growing); anything else is a new entry.
     */
    fun put(key: String, text: String) {
        if (text.isEmpty()) return
        synchronized(shown) {
            val q = shown.getOrPut(key) { ArrayDeque() }
            val head = q.firstOrNull()
            if (head != null && (text.startsWith(head) || head.startsWith(text))) {
                if (text.length >= head.length) q[0] = text
            } else {
                q.addFirst(text)
                while (q.size > TEXTS_PER_FRAGMENT) q.removeLast()
            }
        }
    }

    internal fun clearForTests() = synchronized(shown) { shown.clear() }
}

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

    /**
     * [T-android-stream-fade-seed] Whether this controller has ever been
     * handed text. Lets the caller tell a controller's FIRST text apart from
     * an append, which the prefix-diff in [ingest] cannot do on its own: a
     * fresh controller's `lastPlainText` is "", every string starts with "",
     * so the whole initial text would be sliced into fade ranges and drawn
     * at alpha 0 — erasing text the user was already reading.
     */
    var hasSeenText: Boolean = false
        private set

    /** Active animating ranges. Frozen at α=1 ranges are removed each tick. */
    private val rangesState: SnapshotStateList<FadeRange> = mutableListOf<FadeRange>().toMutableStateList()

    /** start-time nanos per range (parallel to rangesState; same indexing). */
    private val rangeStartNanos = ArrayDeque<Long>()

    /** Per-range current alpha, updated each frame; read in [overlay]. */
    val alphas: SnapshotStateMap<Int, Float> = SnapshotStateMap()

    /** True when at least one range is still under α=1. */
    val hasActiveRanges: Boolean get() = rangesState.isNotEmpty()

    /**
     * [T-android-stream-fade-lost-wake] Signalled whenever [ingest] adds fade
     * ranges; [runTicks] waits on it while idle. Conflated, so a signal sent
     * while the ticker is still busy is kept for its next wait instead of
     * lost. See [runTicks].
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * [T-android-stream-fade-seed] Adopt [plainText] as already-visible
     * content: record it as the prefix and create NO fade ranges. For a
     * controller created after its block was rendered opaque — a list that
     * only became the live tail once its second item arrived, or any block
     * whose fade flag flipped on later — the text it is handed at birth was
     * on screen before the controller existed. Fading it would paint the
     * background over it.
     *
     * Subsequent [ingest] calls diff against this prefix normally, so words
     * appended AFTER seeding still fade in.
     */
    fun seed(plainText: String) {
        hasSeenText = true
        rangesState.clear()
        rangeStartNanos.clear()
        alphas.clear()
        lastPlainText = plainText
    }

    fun ingest(newPlainText: String) {
        hasSeenText = true
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
        wake.trySend(Unit)
    }

    /**
     * [T-android-stream-fade-lost-wake] The per-frame loop: tick until every
     * range is opaque, wait for [ingest] to add more, repeat. Never returns.
     *
     * Reported on a Pixel 4a: a reply showed only its first character above
     * a running tool. The accessibility tree held the whole 27-character
     * paragraph, two lines tall, so the text was laid out — every character
     * after the first sat under a fully opaque mask, permanently.
     *
     * The old driver was `LaunchedEffect(hasActiveRanges)` whose loop broke
     * once [tick] emptied the ranges. A frame runs animation callbacks (the
     * tick) BEFORE recomposition, so when the last range finished in the same
     * frame whose recomposition ingested more text, the loop had already
     * exited while `hasActiveRanges` read true both before and after: the
     * effect key never changed, nothing relaunched it, and the new ranges
     * were never ticked. [forEachActive] reports an unticked range at α=0, so
     * their mask stayed at full strength. The window is one frame; a heavy
     * recomposition — a tool call landing right after text — widens it.
     *
     * Waiting on [wake] instead of on a composition key closes it: an ingest
     * during a busy loop leaves a token, so the next wait returns at once.
     *
     * @param awaitFrame runs one frame callback and returns its result;
     *   `withFrameNanos` in production, a fake clock in tests.
     */
    suspend fun runTicks(awaitFrame: suspend (onFrame: (Long) -> Boolean) -> Boolean): Nothing {
        while (true) {
            while (awaitFrame(::tick)) Unit
            wake.receive()
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
     * Visit every range still fading, with its current alpha. Call this from a
     * DRAW scope only: the snapshot reads here are what schedule the next
     * redraw, and keeping them out of composition is the whole point.
     */
    fun forEachActive(block: (start: Int, end: Int, alpha: Float) -> Unit) {
        for (r in rangesState) block(r.start, r.end, alphas[r.start] ?: 0f)
    }
}

@Composable
internal fun rememberFadeController(): FadeController =
    remember { FadeController() }

/**
 * Drives the per-frame tick for [controller]. Suspends when nothing is
 * animating and wakes when [FadeController.ingest] adds ranges. Single
 * instance per MdText so each animating block runs independently.
 *
 * [T-android-stream-fade-lost-wake] Keyed on the controller, not on
 * `hasActiveRanges`: a composition key cannot see a range set that emptied
 * and refilled within one frame, which left new text masked forever. See
 * [FadeController.runTicks].
 */
@Composable
internal fun FadeFrameDriver(controller: FadeController) {
    LaunchedEffect(controller) {
        controller.runTicks { onFrame -> withFrameNanos(onFrame) }
    }
}

/**
 * Hold a stable mutable holder for the most recent base color so the
 * overlay() call doesn't need MdText to pass it through composition every
 * time. Currently unused externally but kept as a hook for future fade
 * extensions (color-shift, ramp-up speed) that depend on the surrounding
 * theme color.
 */
internal data class FadeColorHolder(var color: Color = Color.Unspecified) {
    val state = mutableStateOf(color)
}
