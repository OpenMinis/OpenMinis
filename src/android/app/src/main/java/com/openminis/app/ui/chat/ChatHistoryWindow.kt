package com.openminis.app.ui.chat

/**
 * Display-window paging. The database stays the full transcript.
 *
 * A page is a contiguous slice of complete user turns. Loading older prepends
 * that slice and keeps the newer side. The loaded window is always a suffix
 * of the session: rows after the newest loaded sort_order are attached, not
 * left behind a control. Nothing in this type drops a side to stay under a
 * capacity: that kick is what made the middle of a long chat unreachable.
 *
 * The cursor is [SortAnchor.sortOrder], not an offset and not created_at.
 * Offsets move when a row is inserted or deleted. created_at moves when a
 * row is replaced.
 */
internal object ChatHistoryWindow {
    const val TURN_PAGE_SIZE = 5
    const val PAGE_ROW_LIMIT = 500
    const val TAIL_ATTACH_CHUNK = 50
    private const val ANCHOR_PROBE = 200

    /**
     * Rows with sort_order in `[loadedNewest + 1, sessionEndExclusive)`.
     * Null means the loaded cursor already covers the session tail.
     */
    fun missingTailRange(loadedNewestSortOrder: Int?, sessionEndExclusive: Int): SortRange? {
        val newest = loadedNewestSortOrder ?: return null
        if (newest == Int.MAX_VALUE) return null
        val start = newest + 1
        if (start >= sessionEndExclusive) return null
        return SortRange(start, sessionEndExclusive)
    }

    /**
     * Insertion index for the not-yet-painted rows of a contiguous tail
     * chunk. [chunkSourceIds] is the WHOLE chunk in DB (sort_order) order;
     * [freshStartIndex] is the first chunk row that is not painted yet. The
     * fresh block goes immediately before the first painted row that
     * represents any chunk id at or after [freshStartIndex] — those rows
     * sort after the fresh block. -1 means append.
     *
     * [T-android-timeline-ledger] Anchoring on the whole chunk regardless of
     * paint state (the old behavior) could place the fresh block before
     * painted rows that sort BEFORE it whenever an early chunk id was
     * already attached to a live painted row — reordering the transcript
     * and duplicating rows.
     */
    fun missingTailInsertIndex(
        currentSourceIds: List<List<String>>,
        chunkSourceIds: List<List<String>>,
        freshStartIndex: Int,
    ): Int {
        if (freshStartIndex >= chunkSourceIds.size) return -1
        val laterIds = chunkSourceIds.drop(freshStartIndex).flatten().toSet()
        if (laterIds.isEmpty()) return -1
        return currentSourceIds.indexOfFirst { ids -> ids.any(laterIds::contains) }
    }

    data class SortAnchor(val sortOrder: Int, val isUser: Boolean)

    /**
     * How far one newest-first probe moves the older cursor.
     *
     * [startSortOrder] is the oldest anchor that belongs in this page.
     * [needsMore] is true only when the probe ran out before [turnCount]
     * user turns and the caller still has older rows to inspect. The caller
     * must not stop mid-turn: if the oldest included row is not a user
     * message, it keeps probing until a user message or the session start.
     */
    data class OlderProbe(
        val startSortOrder: Int?,
        val usersIncluded: Int,
        val needsMore: Boolean,
    )

    data class NewerProbe(
        val endSortOrder: Int?,
        val usersIncluded: Int,
        val needsMore: Boolean,
    )

    fun absorbOlder(
        anchorsNewestFirst: List<SortAnchor>,
        turnCount: Int,
        usersAlready: Int = 0,
    ): OlderProbe {
        if (turnCount <= 0) return OlderProbe(null, usersAlready, false)
        var users = usersAlready
        var start: Int? = null
        for (anchor in anchorsNewestFirst) {
            start = anchor.sortOrder
            if (anchor.isUser) {
                users++
                if (users >= turnCount) {
                    return OlderProbe(start, users, needsMore = false)
                }
            }
        }
        val exhausted = anchorsNewestFirst.isEmpty()
        return OlderProbe(
            startSortOrder = start,
            usersIncluded = users,
            needsMore = !exhausted && users < turnCount,
        )
    }

    /**
     * Oldest-first anchors newer than the loaded cursor.
     * Stops before the user message that would start turn [turnCount] + 1,
     * so a question is not split from the replies already included.
     * If the probe ends on a user message, [needsMore] stays true: that
     * turn's replies may still be ahead.
     */
    fun absorbNewer(
        anchorsOldestFirst: List<SortAnchor>,
        turnCount: Int,
        usersAlready: Int = 0,
    ): NewerProbe {
        if (turnCount <= 0) return NewerProbe(null, usersAlready, false)
        var users = usersAlready
        var end: Int? = null
        for (anchor in anchorsOldestFirst) {
            if (anchor.isUser && users >= turnCount) {
                return NewerProbe(end, users, needsMore = false)
            }
            end = anchor.sortOrder
            if (anchor.isUser) users++
        }
        return NewerProbe(
            endSortOrder = end,
            usersIncluded = users,
            // True when this probe did not end on a user-turn boundary.
            // The caller continues only if the probe was also full; a short
            // probe means the session end, not a split turn.
            needsMore = anchorsOldestFirst.isNotEmpty(),
        )
    }

    fun historyEdgeAction(
        hasOlder: Boolean,
        hasNewer: Boolean,
        olderSentinelVisible: Boolean,
        newerSentinelVisible: Boolean,
        newestEdgeVisible: Boolean,
        oldestEdgeVisible: Boolean,
    ): HistoryPageRequest {
        val spansBothEdges = newestEdgeVisible && oldestEdgeVisible
        return HistoryPageRequest(
            loadOlder = hasOlder && olderSentinelVisible && !spansBothEdges,
            loadNewer = hasNewer && newerSentinelVisible && !spansBothEdges,
        )
    }

    /**
     * [T-android-scroll-policy] With the anchor-restore effect deleted, the
     * viewport under appends is held by Compose's native key anchoring —
     * no manual index compensation exists (or is needed) any more. The old
     * [compensatedLazyIndex] had zero callers left and a KDoc describing a
     * mechanism that no longer runs; it is gone.
     */

    /**
     * Compose-free snapshot of one visible LazyColumn row, so the visual-top
     * selection below is unit-testable.
     */
    data class VisibleRow(val index: Int, val key: String, val offset: Int, val size: Int)

    /**
     * The visual-TOP row of the reverseLayout chat list: the HIGHEST lazy
     * index among visible, non-synthetic rows.
     *
     * [T-android-visual-top] Under `reverseLayout=true` with
     * `items(flatItems.asReversed())`, index 0 is the NEWEST row and paints
     * at the visual BOTTOM (ChatHistoryWindow KDoc), so index ascends upward
     * and the oldest visible row — the one at the top of the screen — has
     * the highest index. The up-button's old selector used `minByOrNull`
     * with a "measured on device" comment whose own dump (offset ASCENDS
     * with index) proves the opposite; the two "visual top" definitions in
     * the file contradicted each other and the min one made the first tap
     * resolve the anchor against the NEWEST visible row. Both call sites
     * now go through this single function.
     *
     * Synthetic `__` rows (load-older pill, resume banner) never anchor
     * anything.
     */
    fun visibleTopRow(rows: List<VisibleRow>): VisibleRow? =
        rows.filter { !it.key.startsWith("__") }.maxByOrNull { it.index }

    /**
     * User-turn ids whose bubbles are FULLY on screen (already seen by the
     * reader; a turn scrolled half off the top edge is still unread).
     */
    fun fullyVisibleUserIds(rows: List<VisibleRow>, viewportStart: Int, viewportEnd: Int): Set<String> =
        rows.asSequence()
            .filter { it.offset >= viewportStart && it.offset + it.size <= viewportEnd }
            .mapNotNull { FlatKeys.parse(it.key) }
            .filter { it.kind == FlatKeys.KIND_USER }
            .map { it.messageId }
            .toSet()

    fun lazyIndexOfOldestFirstKey(
        oldestFirstCount: Int,
        keyIndexInOldestFirst: Int,
        itemsBeforeMessages: Int,
    ): Int {
        if (oldestFirstCount <= 0 || keyIndexInOldestFirst !in 0 until oldestFirstCount) return -1
        return itemsBeforeMessages + (oldestFirstCount - 1 - keyIndexInOldestFirst)
    }

    fun probeLimit(): Int = ANCHOR_PROBE

    /** Pick an older range that never exceeds the page row ceiling. */
    fun boundedOlderStart(candidate: Int?, oldestInLimit: Int?, before: Int): Int? =
        when {
            candidate != null && candidate < before && oldestInLimit != null && candidate >= oldestInLimit -> candidate
            oldestInLimit != null && oldestInLimit < before -> oldestInLimit
            else -> candidate?.takeIf { it < before }
        }

    /**
     * Up-button turn-walk decision — which user turn a tap should land on.
     * iOS `scrollToPreviousUserTurn` (dcdec3c5) semantics:
     *
     *  - First tap anchors on the turn the user is currently reading (the
     *    nearest user message at or above the viewport's top row).
     *  - Repeated taps chain through [lastJumpedUserId], stepping one turn
     *    further back per tap.
     *  - Every user turn already fully on screen counts as "seen" and is
     *    skipped (a turn scrolled half off the top edge is still unread).
     *  - The oldest loaded turn is the floor — no overscroll past it.
     *
     * [loaded] is the painted window, oldest → newest, as (messageId, isUser).
     * [topMessageId] is the FlatKeys-parsed id of the viewport-top row, or
     * null when the top row is synthetic/unloaded (the user is at/above the
     * window's oldest edge — anchor on the oldest loaded turn).
     *
     * [T-android-upbtn-no-scan] The caller resolves the returned id to a row
     * by key and jumps directly. The old implementation scanned the list
     * viewport-by-viewport when the target key wasn't visible — one full
     * layout pass per step, unbounded — which is what ANR'd repeated taps.
     */
    fun previousUserTurnTarget(
        loaded: List<Pair<String, Boolean>>,
        topMessageId: String?,
        lastJumpedUserId: String?,
        fullyVisibleUserIds: Set<String>,
    ): String? {
        val userIds = loaded.filter { it.second }.map { it.first }
        if (userIds.isEmpty()) return null
        val topMsgIdx = topMessageId
            ?.let { id -> loaded.indexOfFirst { it.first == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val currentAnchor = loaded.take(topMsgIdx + 1).lastOrNull { it.second }?.first
            ?: userIds.first()
        // Once a walk has started, continue from lastJumpedUserId: the
        // viewport can no longer identify the current turn (the list clamps
        // at its end, and top-aligning lands on a NEWER row than the target).
        val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
        val pos = userIds.indexOf(walkFrom)
        val steppedTarget = if (lastJumpedUserId == walkFrom && pos > 0) {
            userIds[pos - 1]
        } else {
            walkFrom
        }
        return if (steppedTarget in fullyVisibleUserIds) {
            var i = userIds.indexOf(steppedTarget)
            while (i > 0 && userIds[i] in fullyVisibleUserIds) i--
            userIds[i]
        } else {
            steppedTarget
        }
    }
}

internal data class HistoryPageRequest(
    val loadOlder: Boolean,
    val loadNewer: Boolean,
)
