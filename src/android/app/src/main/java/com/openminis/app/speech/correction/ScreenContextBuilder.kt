package com.openminis.app.speech.correction

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [T-android-voice-viewport-context] What the user is LOOKING AT when they
 * dictate, as correction evidence.
 *
 * The history-mining context ([CorrectionContextBuilder]) always reads the
 * newest turns. A user who has scrolled back through a long session and
 * dictates a question about what is on screen gets a correction grounded in
 * the wrong part of the conversation. This block adds the on-screen text,
 * alongside (not instead of) the history block:
 *
 *  - Window: the viewport's centre line, extended half a screen past each
 *    viewport edge, so two screen-heights in total. Visible rows have exact
 *    geometry from the list's layout info; rows beyond them are placed by an
 *    estimated height (their text length times the px-per-char measured on
 *    the visible rows).
 *  - Content: user text, assistant reply text, and tool-call TITLES only (the
 *    line the tool pill shows, never arguments or output).
 *  - Budget: [CorrectionContextBudget.SCREEN_VIEWPORT] characters, filled
 *    nearest-to-centre first, so what the user is looking at straight on is
 *    the last thing to be cut. Output is re-sorted chronologically.
 *  - Latest reply: the newest assistant reply, on screen or not, in its own
 *    block capped at [CorrectionContextBudget.SCREEN_LATEST_REPLY]. When that
 *    reply is inside the window its rows are left out of the viewport block,
 *    so it appears exactly once (flagged as on screen).
 *
 * Pure: no Compose or Android UI types, so the geometry and budgeting are
 * unit-tested on the JVM.
 */
object ScreenContextBuilder {

    private const val TAG = "CorrectionContext"

    enum class Kind(val label: String) { USER("用户"), ASSISTANT("AI"), TOOL("工具") }

    /**
     * One row of the chat list, in chronological order (oldest first). [kind]
     * is null for rows that carry no text for correction (headers, thinking,
     * usage…); they still count for geometry.
     */
    data class Segment(
        val key: String,
        val messageId: String,
        val kind: Kind?,
        val text: String,
    )

    /** A row the list has laid out: its key, main-axis offset and size in px. */
    data class VisibleItem(val key: String, val offset: Int, val size: Int)

    /** Everything captured from the screen at the moment correction starts. */
    data class Snapshot(
        val segments: List<Segment>,
        val visible: List<VisibleItem>,
        val viewportStart: Int,
        val viewportEnd: Int,
        /**
         * True when older rows sit at LARGER offsets (a reverseLayout list,
         * which is how the chat renders). Needed to place off-screen rows on
         * the correct side.
         */
        val olderAtLargerOffsets: Boolean,
    )

    /** The newest assistant reply, from the full message list. */
    data class LatestReply(val messageId: String, val text: String)

    /** One selected row, with its distance from the viewport centre (px). */
    data class Picked(val index: Int, val kind: Kind, val text: String, val distance: Int)

    /**
     * Rows inside the two-screen window, nearest to the centre first. Rows of
     * [excludeMessageId] with [Kind.ASSISTANT] are skipped (the latest reply,
     * carried by its own block). Returns the picks and whether any skipped row
     * fell inside the window.
     */
    fun selectWindow(snapshot: Snapshot, excludeMessageId: String?): Pair<List<Picked>, Boolean> {
        val segments = snapshot.segments
        val height = snapshot.viewportEnd - snapshot.viewportStart
        if (segments.isEmpty() || height <= 0) return emptyList<Picked>() to false
        val indexByKey = HashMap<String, Int>(segments.size * 2)
        segments.forEachIndexed { i, s -> indexByKey[s.key] = i }
        val visible = snapshot.visible
            .mapNotNull { v -> indexByKey[v.key]?.let { it to v } }
            .sortedBy { it.first }
        if (visible.isEmpty()) return emptyList<Picked>() to false

        val center = (snapshot.viewportStart + snapshot.viewportEnd) / 2.0
        // The window is the viewport plus half a screen past each edge, i.e.
        // one screen-height from the centre line in either direction.
        val reach = height * (0.5 + CorrectionContextBudget.SCREEN_EXTENSION_SCREENS)

        // Which way is "older" on screen. Read from the rows themselves when two
        // or more are laid out (the oldest one sits at the larger offset in a
        // reverseLayout list); the caller's flag covers a single visible row.
        val olderAtLarger = if (visible.size >= 2) {
            visible.first().second.offset > visible.last().second.offset
        } else {
            snapshot.olderAtLargerOffsets
        }

        // Signed position along the chronological axis: negative = older.
        fun signedCenterOf(v: VisibleItem): Double {
            val mid = v.offset + v.size / 2.0
            return if (olderAtLarger) center - mid else mid - center
        }

        val distance = DoubleArray(segments.size) { Double.NaN }
        for ((i, v) in visible) distance[i] = signedCenterOf(v)

        // px per char from the visible text rows, to place rows nobody laid out.
        val textVisible = visible.filter { segments[it.first].text.isNotBlank() }
        val chars = textVisible.sumOf { segments[it.first].text.length }
        val px = textVisible.sumOf { it.second.size }
        val pxPerChar = if (chars > 0 && px > 0) px.toDouble() / chars else height / 400.0
        val minRowPx = height / 20.0
        fun estimate(i: Int): Double {
            val t = segments[i].text
            return if (t.isBlank()) minRowPx else max(minRowPx, t.length * pxPerChar)
        }

        // Walk outward from the first / last laid-out row.
        val (loIdx, loItem) = visible.first()
        val (hiIdx, hiItem) = visible.last()
        var edge = signedCenterOf(loItem) - loItem.size / 2.0
        for (i in loIdx - 1 downTo 0) {
            if (!distance[i].isNaN()) continue
            val h = estimate(i)
            distance[i] = edge - h / 2.0
            edge -= h
            if (-edge > reach) break
        }
        edge = signedCenterOf(hiItem) + hiItem.size / 2.0
        for (i in hiIdx + 1 until segments.size) {
            if (!distance[i].isNaN()) continue
            val h = estimate(i)
            distance[i] = edge + h / 2.0
            edge += h
            if (edge > reach) break
        }

        var excludedInWindow = false
        val picks = mutableListOf<Picked>()
        for (i in segments.indices) {
            val d = distance[i]
            if (d.isNaN() || abs(d) > reach) continue
            val s = segments[i]
            val kind = s.kind ?: continue
            if (s.text.isBlank()) continue
            if (excludeMessageId != null && s.messageId == excludeMessageId && kind == Kind.ASSISTANT) {
                excludedInWindow = true
                continue
            }
            picks.add(Picked(i, kind, s.text.trim(), d.roundToInt()))
        }
        return picks.sortedBy { abs(it.distance) } to excludedInWindow
    }

    /**
     * Fill [budget] nearest-first. A row that does not fit whole is cut only
     * while at least [MIN_PARTIAL] characters remain, keeping the end nearer
     * the centre: an older row keeps its tail, a newer one its head. The
     * result is chronological.
     */
    fun pack(picks: List<Picked>, budget: Int): List<String> {
        val kept = mutableListOf<Pair<Int, String>>()
        var left = budget
        for (p in picks) {
            // The prompt joins lines with "\n"; the budget covers the block as
            // rendered, separators included.
            val sep = if (kept.isEmpty()) 0 else 1
            val label = "【${p.kind.label}】"
            val full = label + p.text
            val room = left - sep
            val line = when {
                full.length <= room -> full
                room - label.length - 1 >= MIN_PARTIAL -> {
                    val textRoom = room - label.length - 1
                    if (p.distance < 0) label + "…" + p.text.takeLast(textRoom)
                    else label + p.text.take(textRoom) + "…"
                }
                else -> continue
            }
            kept.add(p.index to line)
            left -= line.length + sep
            if (left < MIN_PARTIAL) break
        }
        return kept.sortedBy { it.first }.map { it.second }
    }

    /**
     * Cap the latest reply at [budget]: keep its opening (the topic) and most
     * of its ending (the conclusion or question the user is answering).
     */
    fun capLatestReply(text: String, budget: Int): String {
        val t = text.trim()
        if (t.length <= budget) return t
        val head = budget / 3
        val tail = budget - head - 1
        return t.take(head) + "…" + t.takeLast(tail)
    }

    /**
     * Build the screen block. Null when there is nothing on screen to use and
     * no latest reply.
     */
    fun build(snapshot: Snapshot?, latest: LatestReply?): ScreenContext? {
        val started = System.currentTimeMillis()
        val (picks, latestOnScreen) = if (snapshot != null) {
            selectWindow(snapshot, latest?.messageId)
        } else {
            emptyList<Picked>() to false
        }
        val lines = pack(picks, CorrectionContextBudget.SCREEN_VIEWPORT)
        val reply = latest?.text?.takeIf { it.isNotBlank() }
            ?.let { capLatestReply(it, CorrectionContextBudget.SCREEN_LATEST_REPLY) }
        if (lines.isEmpty() && reply == null) return null
        val result = ScreenContext(lines, reply, latestOnScreen && reply != null)
        Log.i(
            TAG,
            "[Screen] visibleRows=${snapshot?.visible?.size ?: 0} windowRows=${picks.size} " +
                "keptLines=${lines.size} viewportChars=${result.viewportChars}/${CorrectionContextBudget.SCREEN_VIEWPORT} " +
                "latestReplyChars=${reply?.length ?: 0}/${CorrectionContextBudget.SCREEN_LATEST_REPLY} " +
                "latestReplyOnScreen=${result.latestReplyOnScreen} durationMs=${System.currentTimeMillis() - started}",
        )
        return result
    }

    /** Smallest cut worth keeping; below this a row is skipped, not stubbed. */
    const val MIN_PARTIAL = 40
}

/** The on-screen evidence, rendered by the prompt as its own blocks. */
data class ScreenContext(
    /** Chronological, each prefixed with its role label. */
    val viewportLines: List<String>,
    val latestReply: String?,
    /** The latest reply was inside the viewing window (and so left out of [viewportLines]). */
    val latestReplyOnScreen: Boolean,
) {
    val viewportChars: Int get() = viewportLines.sumOf { it.length }
}
