package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult

/**
 * MinisTextKit — a self-contained text selection layer that survives
 * LazyColumn item recycling.
 *
 * Compose's built-in [androidx.compose.foundation.text.selection.SelectionContainer]
 * pairs each text node with a [androidx.compose.foundation.text.selection.Selectable]
 * registered on a per-SelectionContainer registrar. When a LazyColumn item
 * scrolls out of the viewport, its Selectable disposes → the registrar drops
 * the anchor → the active selection collapses.
 *
 * MinisTextKit avoids that by keeping the *authoritative selection state*
 * outside the LazyColumn entirely. Each visible text fragment registers a
 * [TextShard] with [SelectionController] while it's composed; when it
 * scrolls off-screen the registration drops but the [selection] state, keyed
 * by stable (messageId, shardId, charOffset), persists. When the shard
 * scrolls back into view it re-registers and the selection highlight is
 * redrawn automatically.
 *
 * This file defines only the state holder and the registration ABI.
 * Hit-test, drag gestures, and highlight drawing are implemented in
 * [MinisMarkdownView] and consumed by [StreamingMarkdownText] via
 * [LocalMinisSelectionController].
 */

/**
 * A handle, stable across recompositions, that identifies a single text-bearing
 * node inside a chat message. The combination (messageId, shardId) is unique
 * within the chat list — messageId scopes to a single ChatMessage, shardId
 * scopes to a fragment within that message (one paragraph / code block /
 * heading, matching [splitMarkdownIntoBlockTexts]).
 */
data class TextShardId(
    val messageId: String,
    val shardId: String,
)

/**
 * Per-shard payload registered with [SelectionController]. The controller
 * uses [textLayoutResult] for hit-testing screen points → character offsets
 * and the inverse (offset → bounding box) for highlight rendering. The
 * controller does NOT capture the LayoutCoordinates reference; it captures
 * the *position-in-window* recomputed every layout pass via [positionInWindow].
 */
class TextShard(
    val id: TextShardId,
    /** Plain text actually shown to the user — used for copy fallback. */
    val plainText: String,
    /** The TextLayoutResult of the rendered Text composable. */
    val textLayoutResult: TextLayoutResult,
    /** Top-left in window coordinates, refreshed by the shard's owner. */
    val positionInWindow: () -> Offset,
    /** Size of the laid-out text in pixels. */
    val sizePx: () -> androidx.compose.ui.unit.IntSize,
    /**
     * Optional mapping from a rendered AnnotatedString character offset back
     * to a raw markdown source offset. When null, copy falls back to
     * [plainText.substring]. When provided, copy can return raw markdown
     * (preserving `**bold**`, links, etc.).
     */
    val renderedToRawOffset: ((Int) -> Int)? = null,
    /** The raw markdown source for this shard. Used together with [renderedToRawOffset] for "copy markdown" paths. */
    val rawMarkdown: String? = null,
    /**
     * True when this shard is a self-contained unit that a long-press should
     * select in full — currently, a table cell.
     *
     * Prose wants the sentence-level expansion [SelectionController] normally
     * applies; a table cell does not. A cell's text is usually punctuation-free
     * ("Alice Smith", "张三 项目经理"), so that scan runs to both ends and picks
     * up the whole cell anyway — but a cell that DOES contain punctuation
     * ("1,200", "v1.2, beta") would select only a fragment, which is never what
     * someone long-pressing a table wants. Marking the cell atomic makes the
     * cell boundary the rule instead of an accident of its content.
     */
    val isAtomicUnit: Boolean = false,
)

/**
 * Identifies one endpoint of a selection. Stable across LazyColumn recycle:
 * even if the owning shard is currently not composed, the controller can
 * still emit the persisted selection back to the user as soon as the shard
 * re-registers.
 */
data class TextPosition(
    val shard: TextShardId,
    /** Character offset within the shard's [TextShard.plainText]. */
    val charOffset: Int,
)

/** A live text selection, possibly spanning multiple shards / messages. */
data class TextSelection(
    val start: TextPosition,
    val end: TextPosition,
)

/**
 * Owns the active selection plus the registry of currently-composed shards.
 * Hoist one of these per logical "text surface" — for ChatScreen that means
 * one controller for the entire LazyColumn, owned above the LazyColumn so
 * its lifetime exceeds any individual item's lifetime.
 */
class SelectionController {
    /** Which of the two selection endpoints a drag is adjusting. */
    enum class Handle { Start, End }

    /** Active selection or null when nothing is selected. */
    val selection = mutableStateOf<TextSelection?>(null)

    /**
     * [T-android-mouse-text-selection] True when the active selection was made
     * with a mouse, which suppresses the draggable handle dots.
     *
     * The dots exist to solve a touch-only problem: a fingertip cannot re-grab
     * a selection edge precisely, so the edges are given large hit targets that
     * hang below the text. A mouse re-adjusts by shift-clicking or simply
     * dragging again, so the dots would be decoration that covers the very
     * text the user is reading — and, worse, sit under the cursor as
     * click-targets that steal presses meant for the text.
     *
     * Set per selection rather than globally, so a 2-in-1 that switches from
     * trackpad to finger gets the handles back on the next touch selection.
     */
    val selectionFromMouse = mutableStateOf(false)

    /**
     * Message-level markdown snapshots captured at selection time so the
     * "Copy Markdown / Copy Rich Text" actions stay available even after
     * the originating shard scrolls out of viewport (the
     * MessageBoundsRegistry would have removed it by then). Keyed by
     * messageId; populated by [rememberMessageMarkdown] (called when a
     * shard registers), kept across the selection's lifetime, cleared on
     * [clearSelection].
     */
    // Snapshot-state map so writes from a SideEffect during one frame
    // invalidate any downstream reader (e.g. the toolbar's `md =
    // resolveSelectionMarkdown()` call) on the next frame, surfacing the
    // markdown buttons the moment a shard has published its source.
    private val messageMarkdownCache = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** Called by shard owners to publish the parent message's joined markdown. */
    fun rememberMessageMarkdown(messageId: String, markdown: String) {
        if (markdown.isEmpty()) return
        // Skip if already correct — avoid re-emitting on every recomposition.
        if (messageMarkdownCache[messageId] == markdown) return
        messageMarkdownCache[messageId] = markdown
    }

    /** Resolve the active selection's parent message markdown. Null when
     *  the selection spans multiple messages OR the cache never saw it. */
    fun selectionMessageMarkdown(): String? {
        val msgId = singleMessageId() ?: return null
        return messageMarkdownCache[msgId]
    }

    /**
     * [T-android-markdown-table-copy-actions] Table actions for the message
     * currently under selection, so the SINGLE selection toolbar can append
     * "Copy Table" / "Copy Table Image" alongside Copy / Copy Markdown / etc.
     * (instead of the table rendering a second, separate popup). A
     * [RenderTable] publishes its [TableActions] keyed by messageId while it's
     * composed; the toolbar reads them for the selected message.
     *
     * [copyTableMarkdown] returns the table's exact markdown source.
     * [copyTableImage] captures + clipboards the rendered table bitmap.
     */
    class TableActions(
        val copyTableMarkdown: () -> Unit,
        val copyTableImage: () -> Unit,
    )

    private val tableActionsByMessage =
        androidx.compose.runtime.mutableStateMapOf<String, TableActions>()

    /** Publish a table's actions for [messageId] (called while it's composed). */
    fun rememberTableActions(messageId: String, actions: TableActions) {
        tableActionsByMessage[messageId] = actions
    }

    /** Drop a table's actions when it leaves composition. */
    fun forgetTableActions(messageId: String) {
        tableActionsByMessage.remove(messageId)
    }

    /**
     * Table actions for the message under the active single-message selection,
     * or null when there's no selection, it spans messages, or that message has
     * no table composed.
     */
    fun selectionTableActions(): TableActions? {
        val msgId = singleMessageId() ?: return null
        return tableActionsByMessage[msgId]
    }

    /**
     * Drag intent published by the gesture handler. While the user is
     * actively dragging (long-press phase 2 OR handle drag), `point` is the
     * latest finger position in window coords and `handle` identifies which
     * endpoint to update. When the finger lifts, set to null.
     *
     * A separate effect in ChatScreen watches this state together with the
     * LazyColumn's scroll position so the selection keeps tracking even
     * when the finger is stationary inside the edge auto-scroll zone (new
     * shards scroll into view and the selection extends to them).
     */
    val dragIntent = mutableStateOf<DragIntent?>(null)

    data class DragIntent(val point: Offset, val handle: Handle)

    /**
     * Currently-composed shards, keyed by their stable id. A shard registers
     * on enter-composition (DisposableEffect) and unregisters on dispose.
     */
    private val shards = mutableStateMapOf<TextShardId, TextShard>()

    fun register(shard: TextShard) {
        shards[shard.id] = shard
    }

    fun unregister(id: TextShardId) {
        shards.remove(id)
    }

    /** Read-only snapshot of currently-composed shards. */
    internal fun currentShards(): Map<TextShardId, TextShard> = shards

    /**
     * Hit-test a window-space point against every currently composed shard.
     * Returns the [TextPosition] of the character nearest to that point, or
     * null if the point isn't over any registered shard.
     */
    fun hitTest(windowPoint: Offset): TextPosition? {
        val (shard, localPoint) = locateShard(windowPoint) ?: return null
        val offset = shard.textLayoutResult.getOffsetForPosition(localPoint)
            .coerceIn(0, shard.plainText.length)
        return TextPosition(shard.id, offset)
    }

    /**
     * Stricter hit-test that ONLY returns a position when the point falls
     * directly inside a registered shard's rect — no nearest-shard fallback.
     * Used by the long-press path so a press on a non-selectable region
     * (e.g. a user message bubble, which intentionally skips MinisTextKit
     * shard registration so it can show its own long-press menu) doesn't
     * snap to whichever assistant shard happens to be closest.
     */
    fun hitTestStrict(windowPoint: Offset): TextPosition? {
        for (shard in shards.values) {
            val origin = shard.positionInWindow()
            if (origin == Offset.Zero) continue
            val size = shard.sizePx()
            val rect = Rect(origin, Offset(size.width.toFloat(), size.height.toFloat()).let { d ->
                androidx.compose.ui.geometry.Size(d.x, d.y)
            })
            if (rect.contains(windowPoint)) {
                val local = windowPoint - origin
                val offset = shard.textLayoutResult.getOffsetForPosition(local)
                    .coerceIn(0, shard.plainText.length)
                return TextPosition(shard.id, offset)
            }
        }
        return null
    }

    /** Find a registered shard covering [windowPoint], plus the corresponding local-space point. */
    private fun locateShard(windowPoint: Offset): Pair<TextShard, Offset>? {
        var best: Pair<TextShard, Offset>? = null
        for (shard in shards.values) {
            val origin = shard.positionInWindow()
            val size = shard.sizePx()
            val rect = Rect(origin, Offset(size.width.toFloat(), size.height.toFloat()).let { d ->
                androidx.compose.ui.geometry.Size(d.x, d.y)
            })
            if (rect.contains(windowPoint)) {
                return shard to (windowPoint - origin)
            }
            // No direct hit; remember nearest-vertically shard as a fallback
            // so dragging out the side of a shard still extends sensibly.
            if (best == null) {
                val clampedX = windowPoint.x.coerceIn(rect.left, rect.right.coerceAtLeast(rect.left))
                val clampedY = windowPoint.y.coerceIn(rect.top, rect.bottom.coerceAtLeast(rect.top))
                best = shard to Offset(clampedX - origin.x, clampedY - origin.y)
            } else {
                val (curBest, _) = best!!
                val curOrigin = curBest.positionInWindow()
                val curSize = curBest.sizePx()
                val curCenterY = curOrigin.y + curSize.height / 2f
                val newCenterY = origin.y + size.height / 2f
                if (kotlin.math.abs(newCenterY - windowPoint.y) <
                    kotlin.math.abs(curCenterY - windowPoint.y)
                ) {
                    val clampedX = windowPoint.x.coerceIn(rect.left, rect.right.coerceAtLeast(rect.left))
                    val clampedY = windowPoint.y.coerceIn(rect.top, rect.bottom.coerceAtLeast(rect.top))
                    best = shard to Offset(clampedX - origin.x, clampedY - origin.y)
                }
            }
        }
        return best
    }

    /** Begin a fresh selection collapsed at [pos] (long-press start). */
    fun beginSelection(pos: TextPosition) {
        selection.value = TextSelection(start = pos, end = pos)
    }

    /**
     * Begin a selection that snaps to the WORD under [pos]. Mirrors iOS long-
     * press semantics: a one-finger long press should reveal a real, non-zero-
     * width selection so the user gets immediate visual feedback and can drag
     * handles outward to grow it. Falls back to a collapsed caret when the
     * shard isn't registered or the character isn't part of a word.
     */
    fun beginSelectionWord(pos: TextPosition) {
        val shard = currentShards()[pos.shard]
        if (shard == null) {
            beginSelection(pos)
            return
        }
        val text = shard.plainText
        if (text.isEmpty()) {
            beginSelection(pos)
            return
        }
        // A table cell selects as a whole cell — see TextShard.isAtomicUnit.
        if (shard.isAtomicUnit) {
            val start = text.indexOfFirst { !it.isWhitespace() }
            if (start < 0) {
                beginSelection(pos)
                return
            }
            val end = text.indexOfLast { !it.isWhitespace() } + 1
            selection.value = TextSelection(
                start = TextPosition(pos.shard, start),
                end = TextPosition(pos.shard, end),
            )
            return
        }
        val (lo, hi) = wordBoundsAt(text, pos.charOffset)
        if (hi <= lo) {
            beginSelection(pos)
            return
        }
        selection.value = TextSelection(
            start = TextPosition(pos.shard, lo),
            end = TextPosition(pos.shard, hi),
        )
    }

    /**
     * Compute the word range surrounding [offset] in [text]. Uses
     * [java.text.BreakIterator.getWordInstance] so the result honors locale
     * rules. When [offset] sits on whitespace / punctuation, scan outward
     * for the nearest word so a long-press near a paragraph end / between
     * two CJK glyphs still produces a non-collapsed selection. Falls back
     * to "select this single character" when no word can be found nearby,
     * which still gives the user a visible selection to drag.
     */
    private fun wordBoundsAt(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val len = text.length
        val clamped = offset.coerceIn(0, len)

        // Sentence-level selection: long-press should grab a meaningful
        // chunk of text — for Latin that's a word-ish run, for CJK we
        // expand to the next punctuation/whitespace boundary on either
        // side so the user gets a clause-sized selection (matching the
        // expectation set by other Chinese readers). System TextView's
        // word-iterator behavior on CJK is too aggressive (single-char
        // selection) and the resulting near-collapsed highlight isn't
        // discoverable. Define "sentence stops" as the union of common
        // Latin + CJK punctuation plus newline.
        val sentenceStops = setOf(
            '。', '？', '！', '；', '：', '，', '、',
            '.', '?', '!', ';', ':', ',',
            '\n', '\r',
        )
        // Scan backward to find the LO bound: the index just after the
        // nearest preceding sentence stop (or 0 if none).
        var lo = clamped.coerceAtMost(len - 1).coerceAtLeast(0)
        while (lo > 0 && text[lo - 1] !in sentenceStops) lo--
        // Skip leading whitespace inside the sentence so the selection
        // doesn't begin on a space.
        while (lo < len && text[lo].isWhitespace()) lo++
        // Scan forward to find the HI bound: the index of the first
        // sentence stop at or after the press point (inclusive of the stop
        // itself so the punctuation is part of the selection — feels more
        // natural to copy).
        var hi = clamped.coerceIn(0, len)
        while (hi < len && text[hi] !in sentenceStops) hi++
        if (hi < len) hi++ // include the stop character itself
        // Trim trailing whitespace.
        while (hi > lo && text[hi - 1].isWhitespace()) hi--
        if (hi <= lo) {
            // Degenerate — fall back to a single character so the user
            // still gets a visible selection to drag.
            val s = clamped.coerceIn(0, (len - 1).coerceAtLeast(0))
            return s to (s + 1).coerceAtMost(len)
        }
        return lo to hi
    }

    /** Extend the active selection's end anchor to [pos] (drag update). */
    fun extendSelectionTo(pos: TextPosition) {
        val cur = selection.value ?: return
        selection.value = cur.copy(end = pos)
    }

    /** Replace start anchor (left handle drag). */
    fun replaceStart(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.start == pos) return
        selection.value = cur.copy(start = pos)
    }

    /** Replace end anchor (right handle drag). */
    fun replaceEnd(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.end == pos) return
        selection.value = cur.copy(end = pos)
    }

    fun clearSelection() {
        selection.value = null
        messageMarkdownCache.clear()
        contextMenuRequest.value = null
    }

    /**
     * [T-android-mouse-text-selection] Window point at which a right-click
     * asked for the action menu, or null when no such request is pending.
     *
     * The touch path has no equivalent: there, the menu follows the selection
     * handles, because a finger that just long-pressed is still covering the
     * text and the menu has to get out of its way. A mouse cursor occludes
     * nothing, and the desktop convention users arrive with is that the menu
     * appears exactly where they clicked. So the anchor is carried separately
     * rather than derived from the selection rect.
     *
     * Cleared by [clearSelection] and whenever an action runs, so the menu
     * never outlives the selection it acts on.
     */
    val contextMenuRequest = mutableStateOf<Offset?>(null)

    fun requestContextMenu(windowPoint: Offset) {
        contextMenuRequest.value = windowPoint
    }

    fun dismissContextMenu() {
        contextMenuRequest.value = null
    }

    /**
     * Whether [pos] falls inside the active selection. Used by the right-click
     * handler to decide between "act on what is already highlighted" and
     * "select the word under the cursor first".
     *
     * Reuses [orderedEndpoints] and [isShardBetween] rather than comparing
     * offsets directly, so a selection spanning several shards or messages is
     * judged by the same document ordering the rest of the controller uses.
     * Returns false when ordering cannot be established (an endpoint shard has
     * recycled off-screen) — the caller then re-selects the word under the
     * cursor, which is the safe outcome: the menu still has a subject, and the
     * subject is one the user can see.
     */
    fun selectionContains(pos: TextPosition): Boolean {
        val sel = selection.value ?: return false
        val (first, last) = orderedEndpoints(sel) ?: return false
        if (first.shard == last.shard) {
            return pos.shard == first.shard &&
                pos.charOffset >= first.charOffset &&
                pos.charOffset <= last.charOffset
        }
        return when (pos.shard) {
            first.shard -> pos.charOffset >= first.charOffset
            last.shard -> pos.charOffset <= last.charOffset
            else -> isShardBetween(first.shard, last.shard, pos.shard)
        }
    }

    /**
     * Return the messageId common to BOTH endpoints of the active selection,
     * or null when the selection spans multiple messages (or there's no
     * selection). Used by the floating toolbar to decide whether
     * "Copy Markdown / Copy Rich Text" buttons should appear — those need
     * an unambiguous source message.
     */
    fun singleMessageId(): String? {
        val sel = selection.value ?: return null
        val a = sel.start.shard.messageId
        val b = sel.end.shard.messageId
        return if (a == b) a else null
    }

    /**
     * Window-space point at which to draw the small "handle" knob for an
     * endpoint, or null if that endpoint's shard isn't currently composed.
     * The point is the bottom of the cursor line at the endpoint's char
     * offset — start handle sits at the LEFT of the first selected char,
     * end handle at the RIGHT of the last selected char (mirrors iOS /
     * Android-native text-selection handles).
     */
    fun handleAnchor(handle: Handle): Offset? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        val endpoint = when (handle) {
            Handle.Start -> first
            Handle.End -> last
        }
        val shard = shards[endpoint.shard] ?: return null
        // When the shard is registered but its LayoutCoordinates have
        // detached (the row scrolled out of view but the composable hasn't
        // disposed yet), positionInWindow falls back to Offset.Zero — that
        // would slam the handle Popup to the top-left corner of the window.
        // Return null instead so the host simply hides the handle until the
        // shard re-attaches, matching system text-selection UX.
        val origin = shard.positionInWindow()
        if (origin == Offset.Zero) return null
        val tlr = shard.textLayoutResult
        val laidOutLen = tlr.layoutInput.text.length
        val clampedOffset = endpoint.charOffset.coerceIn(0, laidOutLen)
        if (laidOutLen <= 0) return null
        val box = runCatching {
            when (handle) {
                Handle.Start -> {
                    val charForStart = clampedOffset.coerceAtMost(laidOutLen - 1).coerceAtLeast(0)
                    tlr.getBoundingBox(charForStart)
                }
                Handle.End -> {
                    val charForEnd = (clampedOffset - 1).coerceIn(0, laidOutLen - 1)
                    tlr.getBoundingBox(charForEnd)
                }
            }
        }.getOrNull() ?: return null
        val (lx, by) = when (handle) {
            Handle.Start -> box.left to box.bottom
            Handle.End -> box.right to box.bottom
        }
        val result = Offset(origin.x + lx, origin.y + by)
        return result
    }

    /**
     * If the window-space point [p] lies within the visible-hit-target of
     * either handle's drag knob, return which one. The handle knob hangs
     * BELOW the line of text the endpoint sits on (anchor.y is the bottom
     * of the cursor line), so we look for [p] inside the rectangle
     *   x ∈ [anchor.x − hitSlopPx, anchor.x + hitSlopPx]
     *   y ∈ [anchor.y − 4, anchor.y + hitSlopPx]
     * The asymmetric vertical box stops "long-press, then tap below the
     * selected text to scroll" from being mistaken for a handle grab —
     * which was the cause of the "selection drifts as I scroll" bug.
     */
    fun grabHandleAt(p: Offset, hitSlopPx: Float): Handle? {
        val start = handleAnchor(Handle.Start)
        val end = handleAnchor(Handle.End)
        // Generous circular hit area centered slightly BELOW each anchor
        // (the visible dot hangs ~hitSlopPx below the text baseline that
        // anchor.y reports). hitSlopPx defaults to 48 dp at the call site —
        // about a thumb-pad's worth of forgiveness in any direction.
        fun within(anchor: Offset?): Float {
            if (anchor == null) return Float.MAX_VALUE
            // Bias center downward so a press AT the dot's visible position
            // counts; the dot is ~hitSlopPx/2 below anchor.y.
            val cx = anchor.x
            val cy = anchor.y + hitSlopPx / 2f
            val dx = p.x - cx
            val dy = p.y - cy
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val ds = within(start)
        val de = within(end)
        val nearest = if (ds <= de) Handle.Start to ds else Handle.End to de
        return if (nearest.second <= hitSlopPx * 1.5f) nearest.first else null
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Compute the union bounding rectangle (window space) of the active
     * selection, walking only registered shards. Used to position the
     * floating toolbar. Returns null if no selection or none of the
     * selection's shards are currently composed.
     */
    fun selectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        val firstShard = shards[first.shard] ?: return null
        val lastShard = shards[last.shard] ?: return null
        val firstBox = firstShard.textLayoutResult.getBoundingBox(
            first.charOffset.coerceIn(0, firstShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0)
        )
        val lastBox = lastShard.textLayoutResult.getBoundingBox(
            last.charOffset.coerceIn(0, lastShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0)
        )
        val topLeft = firstShard.positionInWindow() + Offset(firstBox.left, firstBox.top)
        val bottomRight = lastShard.positionInWindow() + Offset(lastBox.right, lastBox.bottom)
        return Rect(topLeft, bottomRight)
    }

    /**
     * Union the visible parts of the active selection across all currently-
     * composed shards. Returns the bounding rect of what's actually drawn
     * on screen right now, in window coords — which may be a subset of the
     * full logical selection when either endpoint shard has scrolled off-
     * screen. Used by the floating toolbar to track the visible portion
     * instead of vanishing the moment a selection endpoint goes off-view.
     *
     * Returns null when no part of the selection is currently drawable
     * (both endpoints AND all in-between shards are off-screen). Callers
     * are expected to fall back to a fixed anchor position in that case.
     */
    /**
     * Window-space rect of the LAST visible line of the active selection —
     * the line that contains the end handle (or, if the end handle's shard
     * is off-screen, the bottom-most line of the lowest still-visible
     * shard in the selection). Used by the floating toolbar to anchor
     * itself above the END of the selection so the menu and the end
     * handle stay clustered together rather than the menu floating up by
     * the start handle (which can be many lines away).
     */
    /**
     * Window-space horizontal mid-point between the two handles, used by
     * the toolbar to center itself "between" the handles instead of above
     * the geometric center of the highlight (which on multi-line selections
     * sits in the middle of a paragraph). Falls back to null when neither
     * endpoint is currently registered — caller should keep its own
     * default.
     */
    fun handlesCenterX(): Float? {
        val s = handleAnchor(Handle.Start)
        val e = handleAnchor(Handle.End)
        return when {
            s != null && e != null -> (s.x + e.x) / 2f
            s != null -> s.x
            e != null -> e.x
            else -> null
        }
    }

    /**
     * Window-space rect of the text line under a specific handle's
     * endpoint — used during a handle drag so the floating toolbar can
     * follow whichever handle the user is moving. Returns null when the
     * handle's shard isn't currently registered (off-screen). Sized to
     * the line height of the character under the endpoint, not the whole
     * shard, so the toolbar hugs the actual moving line rather than the
     * entire paragraph.
     */
    fun draggedHandleLineRect(handle: Handle): Rect? {
        val sel = selection.value ?: return null
        // The Handle enum corresponds to the visual START / END of the
        // selection in document order, not selection.start vs selection.end
        // (which depend on which side the user grabbed first). Use the
        // ordered pair so a "drag the right handle" gesture always tracks
        // the bottom/right endpoint regardless of whether it's stored in
        // selection.start or selection.end.
        val ordered = orderedEndpoints(sel) ?: return null
        val endpoint = when (handle) {
            Handle.Start -> ordered.first
            Handle.End -> ordered.second
        }
        val shard = shards[endpoint.shard] ?: return null
        val origin = shard.positionInWindow()
        if (origin == Offset.Zero) return null
        val tlr = shard.textLayoutResult
        val len = tlr.layoutInput.text.length
        if (len <= 0) return null
        val clamped = endpoint.charOffset.coerceIn(0, len)
        val charIdx = when (handle) {
            Handle.Start -> clamped.coerceAtMost(len - 1).coerceAtLeast(0)
            Handle.End -> (clamped - 1).coerceIn(0, len - 1)
        }
        val box = runCatching { tlr.getBoundingBox(charIdx) }.getOrNull() ?: return null
        return Rect(
            left = origin.x + box.left,
            top = origin.y + box.top,
            right = origin.x + box.right,
            bottom = origin.y + box.bottom,
        )
    }

    fun visibleSelectionEndLineRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered

        // Prefer the actual end shard if it's still composed and on screen.
        val lastShard = shards[last.shard]
        if (lastShard != null && lastShard.positionInWindow() != Offset.Zero) {
            val tlr = lastShard.textLayoutResult
            val len = tlr.layoutInput.text.length
            if (len > 0) {
                val charIdx = (last.charOffset - 1).coerceIn(0, len - 1)
                val box = runCatching { tlr.getBoundingBox(charIdx) }.getOrNull()
                if (box != null) {
                    val origin = lastShard.positionInWindow()
                    return Rect(
                        left = origin.x + box.left,
                        top = origin.y + box.top,
                        right = origin.x + box.right,
                        bottom = origin.y + box.bottom,
                    )
                }
            }
        }

        // Fallback: end handle's shard scrolled off-screen. Find the
        // BOTTOM-most still-composed shard that's part of the selection —
        // that's the visual "tail" of what the user sees as the highlight.
        var bestShard: TextShard? = null
        var bestBottom = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            val origin = shard.positionInWindow()
            if (origin == Offset.Zero) continue
            val isFirst = id == first.shard
            val isLast = id == last.shard
            val isBetween = !isFirst && !isLast &&
                isShardBetween(first.shard, last.shard, id)
            if (!isFirst && !isLast && !isBetween) continue
            val bottom = origin.y + shard.sizePx().height
            if (bottom > bestBottom) {
                bestBottom = bottom
                bestShard = shard
            }
        }
        val tail = bestShard ?: return null
        val tlr = tail.textLayoutResult
        val len = tlr.layoutInput.text.length
        if (len <= 0) return null
        // Use the LAST char of this shard for the visual line.
        val lineCount = tlr.lineCount
        if (lineCount <= 0) return null
        val lastLine = lineCount - 1
        val lineTop = runCatching { tlr.getLineTop(lastLine) }.getOrNull() ?: return null
        val lineBottom = runCatching { tlr.getLineBottom(lastLine) }.getOrNull() ?: return null
        val origin = tail.positionInWindow()
        // Approximate the line's visible x range via the shard's body width.
        return Rect(
            left = origin.x,
            top = origin.y + lineTop,
            right = origin.x + tail.sizePx().width,
            bottom = origin.y + lineBottom,
        )
    }

    fun visibleSelectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        var minL = Float.POSITIVE_INFINITY
        var minT = Float.POSITIVE_INFINITY
        var maxR = Float.NEGATIVE_INFINITY
        var maxB = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            val isFirstHere = id == first.shard
            val isLastHere = id == last.shard
            val isBetween = !isFirstHere && !isLastHere &&
                isShardBetween(first.shard, last.shard, id)
            if (!isFirstHere && !isLastHere && !isBetween) continue
            val tlr = shard.textLayoutResult
            val laidOutLen = tlr.layoutInput.text.length
            if (laidOutLen <= 0) continue
            val from = if (isFirstHere) first.charOffset.coerceIn(0, laidOutLen) else 0
            val to = if (isLastHere) last.charOffset.coerceIn(0, laidOutLen) else laidOutLen
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            if (hi <= lo) continue
            val origin = shard.positionInWindow()
            // The shard's positionInWindow falls back to Offset.Zero when
            // the LayoutCoordinates have detached — skip those, they
            // wouldn't be drawn anyway.
            if (origin == Offset.Zero) continue
            val startBox = runCatching {
                tlr.getBoundingBox((lo).coerceAtMost(laidOutLen - 1).coerceAtLeast(0))
            }.getOrNull() ?: continue
            val endBox = runCatching {
                tlr.getBoundingBox((hi - 1).coerceIn(0, laidOutLen - 1))
            }.getOrNull() ?: continue
            // Union both boxes — covers the case where the selection spans
            // multiple lines within this shard.
            val left = origin.x + minOf(startBox.left, endBox.left)
            val top = origin.y + minOf(startBox.top, endBox.top)
            val right = origin.x + maxOf(startBox.right, endBox.right)
            val bottom = origin.y + maxOf(startBox.bottom, endBox.bottom)
            if (left < minL) minL = left
            if (top < minT) minT = top
            if (right > maxR) maxR = right
            if (bottom > maxB) maxB = bottom
        }
        if (!minL.isFinite() || !maxR.isFinite() || maxR <= minL || maxB <= minT) return null
        return Rect(minL, minT, maxR, maxB)
    }

    /**
     * Returns (firstEndpoint, lastEndpoint) ordered by *visual* position so
     * the caller can iterate left-to-right / top-to-bottom regardless of
     * which handle the user grabbed. Requires both endpoints' shards to be
     * registered (so we can compare window-y).
     */
    fun orderedEndpoints(sel: TextSelection): Pair<TextPosition, TextPosition>? {
        val a = sel.start
        val b = sel.end
        if (a.shard == b.shard) {
            return if (a.charOffset <= b.charOffset) a to b else b to a
        }
        // Prefer stable document-order index when both endpoints have one,
        // because that survives a shard being off-screen / unregistered.
        // Fall back to y-comparison if we can't get an index for either.
        val ka = shardOrderKey(a.shard)
        val kb = shardOrderKey(b.shard)
        if (ka != null && kb != null && a.shard.messageId == b.shard.messageId) {
            return if (ka <= kb) a to b else b to a
        }
        val sA = shards[a.shard] ?: return null
        val sB = shards[b.shard] ?: return null
        val yA = sA.positionInWindow().y
        val yB = sB.positionInWindow().y
        return if (yA <= yB) a to b else b to a
    }

    /**
     * Build the plain-text string covered by the active selection. Shards
     * not currently composed are still included via [TextShard.plainText]
     * lookups — but cross-message selections can only contain shards we've
     * seen at least once. (In practice every shard in a sane selection will
     * have been registered at the moment the user dragged across it.)
     */
    fun selectedPlainText(documentRegistry: Map<TextShardId, String> = emptyMap()): String {
        val sel = selection.value ?: return ""

        // [T-android-copy-selection-not-whole-message] orderedEndpoints()
        // returns null only when an endpoint shard recycled off-screen AND has
        // no document-order key (long drag). Fall back to the precise per-shard
        // walk over whatever shards ARE still registered — NOT the whole cached
        // message. (The prior fix [T-android-copy-long-reply-incomplete]
        // returned the entire message markdown here and in the cross-shard
        // branch below, which is why a partial multi-shard selection pasted the
        // full reply.) If even the walk comes up empty (genuinely-degenerate
        // single-shard recycle), keep the original collapsed substring.
        val ordered = orderedEndpoints(sel) ?: run {
            val walked = crossShardSelectedText(sel.start, sel.end, documentRegistry)
            return walked.ifEmpty { collapsedFallback(sel) }
        }
        val (first, last) = ordered

        // Single-shard selection — precise substring of the plainText.
        // Intentionally NOT routed through the message cache: a within-one-
        // shard selection is a normal short/partial-paragraph copy and must
        // stay an exact substring.
        if (first.shard == last.shard) {
            val txt = shards[first.shard]?.plainText
                ?: documentRegistry[first.shard]
                ?: return ""
            val a = first.charOffset.coerceIn(0, txt.length)
            val b = last.charOffset.coerceIn(0, txt.length)
            return txt.substring(minOf(a, b), maxOf(a, b))
        }

        return crossShardSelectedText(first, last, documentRegistry)
    }

    /**
     * [T-android-copy-selection-not-whole-message] Build the text covered by a
     * cross-shard selection, bounded to the selection — never the whole
     * message.
     *
     * Primary path: the per-shard walk over registered [MdText] shards, sliced
     * at both endpoints. That is exact for the common case (selection spans
     * paragraphs / headings / list items, all of which ARE shards — Jackson's
     * report).
     *
     * The walk's only blind spot is non-shard blocks (code fences, tables,
     * math display) that render via their own Text composables and so drop out
     * of the shard registry. To recover THOSE without dumping the entire reply,
     * we splice in the slice of the cached message markdown that sits strictly
     * between the first selected shard's text and the last selected shard's
     * text — and only when such a gap actually exists. If the markdown anchors
     * can't be located, we keep the precise shard-walk result rather than
     * falling back to the full message.
     */
    private fun crossShardSelectedText(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): String {
        val sb = StringBuilder()
        val visitedOrder = registeredShardsInOrder()
        var started = false
        for (id in visitedOrder) {
            val txt = shards[id]?.plainText ?: documentRegistry[id] ?: continue
            when (id) {
                first.shard -> {
                    sb.append(txt.substring(first.charOffset.coerceIn(0, txt.length), txt.length))
                    started = true
                }
                last.shard -> {
                    if (started) sb.append('\n')
                    sb.append(txt.substring(0, last.charOffset.coerceIn(0, txt.length)))
                    started = true
                    break
                }
                else -> if (started) {
                    sb.append('\n').append(txt)
                }
            }
        }
        val walk = sb.toString()

        // Same-message selection: try to recover non-shard blocks (code /
        // tables) inside the selected span by slicing the cached markdown
        // between the selection's head and tail anchors. Cross-message
        // selections have no single source string, so the walk stands.
        if (first.shard.messageId == last.shard.messageId) {
            spliceNonShardSpan(first, last, walk, documentRegistry)?.let { return it }
        }
        return walk
    }

    /**
     * [T-android-copy-selection-not-whole-message] When a same-message cross-
     * shard selection spans a non-shard block (code/table/math), return the
     * slice of the cached message markdown bounded by the selection's head and
     * tail anchors. Returns null when there's no cache, no usable anchors, or
     * the selected span contains no extra (non-shard) content beyond the
     * shard-walk text — in which case the caller keeps the exact shard walk.
     */
    private fun spliceNonShardSpan(
        first: TextPosition,
        last: TextPosition,
        walk: String,
        documentRegistry: Map<TextShardId, String>,
    ): String? {
        val md = messageMarkdownCache[first.shard.messageId]?.takeIf { it.isNotEmpty() } ?: return null

        val firstText = shards[first.shard]?.plainText ?: documentRegistry[first.shard] ?: return null
        val lastText = shards[last.shard]?.plainText ?: documentRegistry[last.shard] ?: return null

        // Head anchor: a run of the selected text right after the start offset.
        val headSel = firstText.substring(first.charOffset.coerceIn(0, firstText.length))
        // Tail anchor: a run of the selected text right before the end offset.
        val tailSel = lastText.substring(0, last.charOffset.coerceIn(0, lastText.length))

        val headAnchor = anchorFor(headSel, fromStart = true)
        val tailAnchor = anchorFor(tailSel, fromStart = false)
        if (headAnchor.isEmpty() || tailAnchor.isEmpty()) return null

        val startIdx = md.indexOf(headAnchor)
        if (startIdx < 0) return null
        val tailIdx = md.indexOf(tailAnchor, startIdx + headAnchor.length)
        if (tailIdx < 0) return null
        val endIdx = tailIdx + tailAnchor.length
        if (endIdx <= startIdx) return null

        val slice = md.substring(startIdx, endIdx)
        // Only prefer the markdown slice when the selected span actually
        // crosses a NON-SHARD block — a fenced code block (``` / ~~~), a table
        // (pipe rows), or display math ($$). Those render via their own Text
        // composables (no MinisTextKit shard), so the per-shard walk drops them
        // and the markdown slice is the only way to recover them. For a plain
        // run of paragraphs / headings / list items (all shards), the walk is
        // already exact and formatting-free, so prefer it — returning the
        // markdown slice there would re-introduce `#`, `**`, etc. the user
        // didn't select as rendered text.
        return if (sliceCrossesNonShardBlock(slice)) slice else null
    }

    /**
     * [T-android-copy-selection-not-whole-message] Heuristic: does this raw-
     * markdown slice contain a block that doesn't register as a MinisTextKit
     * text shard (fenced code, table, or display math)? Used to decide whether
     * the markdown slice carries content the per-shard walk would have dropped.
     */
    private fun sliceCrossesNonShardBlock(slice: String): Boolean {
        if (slice.contains("```") || slice.contains("~~~")) return true
        if (slice.contains("$$")) return true
        // A markdown table needs a header row plus a delimiter row of dashes
        // and pipes (e.g. `|---|---|`). Look for that delimiter shape so a
        // stray inline `|` in prose doesn't trigger a false positive.
        val tableDelimiter = Regex("""(?m)^\s*\|?\s*:?-{3,}.*\|""")
        return tableDelimiter.containsMatchIn(slice)
    }

    /**
     * Pick a short, distinctive anchor substring from one end of a selected
     * run so it can be located in the raw markdown. Trims whitespace and caps
     * the length to keep the match cheap and resilient to inline formatting
     * that lies deeper in the run. Returns "" when no usable anchor exists.
     */
    private fun anchorFor(selected: String, fromStart: Boolean): String {
        val trimmed = selected.trim()
        if (trimmed.isEmpty()) return ""
        val cap = 24
        return if (trimmed.length <= cap) trimmed
        else if (fromStart) trimmed.substring(0, cap)
        else trimmed.substring(trimmed.length - cap)
    }

    private fun collapsedFallback(sel: TextSelection): String {
        val txt = shards[sel.start.shard]?.plainText ?: return ""
        val a = sel.start.charOffset.coerceIn(0, txt.length)
        val b = sel.end.charOffset.coerceIn(0, txt.length)
        return txt.substring(minOf(a, b), maxOf(a, b))
    }

    /** Currently-composed shards in visual top-to-bottom order. */
    private fun registeredShardsInOrder(): List<TextShardId> =
        shards.values
            .sortedBy { it.positionInWindow().y }
            .map { it.id }
}

/**
 * Composition local exposing the active [SelectionController] to descendants.
 * Default = a no-op controller so callers can pretend it's always there;
 * shards composed without a real controller simply never participate in any
 * selection. Wrap with [ProvideSelectionController] at the ChatScreen level.
 */
val LocalMinisSelectionController = compositionLocalOf<SelectionController?> { null }

@Composable
fun ProvideSelectionController(
    controller: SelectionController,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinisSelectionController provides controller) {
        content()
    }
}

/**
 * Helper for shard owners: registers the shard with the ambient controller
 * (if any) for the lifetime of the call site, then unregisters on dispose.
 *
 * Pass the *currently up-to-date* [TextShard] every time — the helper holds
 * the reference under the same id, replacing on each recomposition so
 * position-in-window callbacks and TextLayoutResult always reflect the
 * latest measurement.
 */
@Composable
fun RegisterSelectionShard(shard: TextShard?) {
    val controller = LocalMinisSelectionController.current ?: return
    if (shard == null) return
    DisposableEffect(controller, shard.id, shard) {
        controller.register(shard)
        onDispose { controller.unregister(shard.id) }
    }
}

/**
 * Draw the active selection's highlight rectangles inside a single shard's
 * DrawScope. Call from a `Modifier.drawBehind` on the same node whose
 * [TextLayoutResult] backed the shard registration — the highlight is drawn
 * in the shard's local coordinate space and only the portion of the
 * selection that falls inside this shard.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionForShard(
    shardId: TextShardId,
    result: TextLayoutResult,
    selection: TextSelection,
    controller: SelectionController?,
    color: androidx.compose.ui.graphics.Color,
) {
    val laidOutText = result.layoutInput.text.text
    val maxOffset = laidOutText.length
    val lineCount = result.lineCount
    if (maxOffset == 0 || lineCount == 0) return

    val ordered = controller?.orderedEndpoints(selection) ?: run {
        val a = selection.start
        val b = selection.end
        if (a.shard == b.shard && a.charOffset <= b.charOffset) a to b else b to a
    }
    val (first, last) = ordered

    // Decide the [from, to] character range within this shard.
    val isFirstHere = first.shard == shardId
    val isLastHere = last.shard == shardId
    if (!isFirstHere && !isLastHere) {
        // This shard sits BETWEEN the two endpoints — only include it if
        // the selection actually spans across us. Use shard order via the
        // controller's snapshot of registered shards' window-y positions.
        if (controller == null) {
            return
        }
        val between = controller.isShardBetween(first.shard, last.shard, shardId)
        if (!between) return
    }

    val from = if (isFirstHere) first.charOffset.coerceIn(0, maxOffset) else 0
    val to = if (isLastHere) last.charOffset.coerceIn(0, maxOffset) else maxOffset
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    if (hi <= lo) return

    val startLine = result.getLineForOffset(lo).coerceIn(0, lineCount - 1)
    val endLine = result.getLineForOffset((hi - 1).coerceAtLeast(0)).coerceIn(0, lineCount - 1)
    if (endLine < startLine) return

    for (line in startLine..endLine) {
        val lineStart = if (line == startLine) lo else result.getLineStart(line)
        val lineEndRaw = if (line == endLine) hi else result.getLineEnd(line)
        val lineEnd = lineEndRaw.coerceAtMost(maxOffset)
        if (lineEnd <= lineStart) continue
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (offset in lineStart until lineEnd) {
            val box = result.getBoundingBox(offset)
            if (box.width <= 0f) continue
            if (box.left < left) left = box.left
            if (box.right > right) right = box.right
        }
        if (!left.isFinite() || right <= left) continue
        // If this is the last visible line of the selection in this shard
        // and we DON'T contain the final endpoint, extend the highlight
        // to the line's right edge so cross-shard selections feel continuous.
        val isFinalLineHere = line == endLine && isLastHere
        val drawRight = if (!isFinalLineHere) {
            val lineRight = result.getLineRight(line)
            maxOf(right, lineRight)
        } else right
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(drawRight - left, bottom - top),
        )
    }
}

/**
 * Tells whether shard [middle] lies (in visual top-to-bottom order) between
 * shards [a] and [b], using their current registered positions. Used by the
 * highlight code to decide whether a shard with no selection endpoint
 * inside it should still be filled (it lies in the middle of a multi-shard
 * range).
 */
/**
 * Best-effort document-order index for a shard, extracted from the
 * trailing integer in its shardId string. Returns null if the shardId
 * doesn't carry a parseable index. Use case: ordering shards even when
 * one of them isn't currently registered with the SelectionController
 * (e.g. it scrolled off-screen and got disposed by LazyColumn).
 *
 * Convention from ChatScreen.kt: shard ids look like
 *   "mdblock:<parentBlockId>:<blockIndex>"   ← splitMarkdownIntoBlockTexts
 *   "text:<blockId>"                          ← single AssistantText block
 *   "legacy"                                  ← whole-message legacy path
 * The mdblock case is the only one that needs ordering today; the others
 * are single-shard so the comparison never has to disambiguate.
 */
private fun shardOrderKey(id: TextShardId): Int? {
    val s = id.shardId
    val lastColon = s.lastIndexOf(':')
    if (lastColon < 0) return null
    return s.substring(lastColon + 1).toIntOrNull()
}

internal fun SelectionController.isShardBetween(
    a: TextShardId,
    b: TextShardId,
    middle: TextShardId,
): Boolean {
    // First try the document-order index baked into the shardId string —
    // mdblock:<parent>:<index> / text:<blockId>. The numeric index is a
    // stable document position, immune to a/b having scrolled off-screen
    // (which would leave their TextShard entries unregistered and the
    // y-comparison below unable to position them). The "messageId" axis
    // is compared first so cross-message selections still slot correctly:
    // a shard from message M1 only lies "between" two endpoints if they
    // straddle M1 in the registered-shards' visual order.
    val orderA = shardOrderKey(a)
    val orderB = shardOrderKey(b)
    val orderM = shardOrderKey(middle)
    if (orderA != null && orderB != null && orderM != null &&
        a.messageId == b.messageId && middle.messageId == a.messageId
    ) {
        val lo = minOf(orderA, orderB)
        val hi = maxOf(orderA, orderB)
        return orderM in lo..hi
    }
    // Fall back to y-based comparison when index parsing fails or the
    // selection spans multiple messages.
    val shards = currentShards()
    val sa = shards[a] ?: return false
    val sb = shards[b] ?: return false
    val sm = shards[middle] ?: return false
    val ya = sa.positionInWindow().y
    val yb = sb.positionInWindow().y
    val ym = sm.positionInWindow().y
    val lo = minOf(ya, yb)
    val hi = maxOf(ya, yb)
    return ym in lo..hi
}

/**
 * Convenience for callers that already have a [LayoutCoordinates] from an
 * `onGloballyPositioned` modifier — wraps it into the `positionInWindow` and
 * `sizePx` closures the controller expects.
 */
fun buildTextShard(
    id: TextShardId,
    plainText: String,
    layoutResult: TextLayoutResult,
    coordinatesProvider: () -> LayoutCoordinates?,
    rawMarkdown: String? = null,
    renderedToRawOffset: ((Int) -> Int)? = null,
    isAtomicUnit: Boolean = false,
): TextShard = TextShard(
    id = id,
    plainText = plainText,
    textLayoutResult = layoutResult,
    isAtomicUnit = isAtomicUnit,
    positionInWindow = {
        val c = coordinatesProvider()
        if (c != null && c.isAttached) c.positionInWindow() else Offset.Zero
    },
    sizePx = {
        val c = coordinatesProvider()
        if (c != null && c.isAttached) {
            androidx.compose.ui.unit.IntSize(c.size.width, c.size.height)
        } else {
            androidx.compose.ui.unit.IntSize.Zero
        }
    },
    renderedToRawOffset = renderedToRawOffset,
    rawMarkdown = rawMarkdown,
)

