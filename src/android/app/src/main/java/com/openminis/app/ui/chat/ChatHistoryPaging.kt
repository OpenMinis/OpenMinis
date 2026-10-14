package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.MessagePreviewRow
import com.openminis.app.data.db.MessageSortAnchor

internal data class SortRange(val startInclusive: Int, val endExclusive: Int) {
    init {
        require(startInclusive <= endExclusive) {
            "Sort range must be half-open and ordered: [$startInclusive, $endExclusive)"
        }
    }
}

internal fun MessageSortAnchor.toWindowAnchor(): ChatHistoryWindow.SortAnchor =
    ChatHistoryWindow.SortAnchor(sortOrder = sortOrder, isUser = role == "user")

/**
 * Oldest sort_order of a page of [turnCount] complete user turns ending
 * before [beforeSortOrder]. Null when nothing older exists.
 */
internal suspend fun ChatViewModel.olderTurnStart(
    beforeSortOrder: Int,
    turnCount: Int,
): Int? {
    var cursor = beforeSortOrder
    var users = 0
    var start: Int? = null
    while (users < turnCount) {
        val probe = chatRepository.dao.loadOlderSortAnchors(
            sessionId,
            cursor,
            ChatHistoryWindow.probeLimit(),
        )
        if (probe.isEmpty()) break
        val absorbed = ChatHistoryWindow.absorbOlder(
            probe.map { it.toWindowAnchor() },
            turnCount = turnCount,
            usersAlready = users,
        )
        start = absorbed.startSortOrder ?: start
        users = absorbed.usersIncluded
        if (!absorbed.needsMore || probe.size < ChatHistoryWindow.probeLimit()) break
        val next = probe.last().sortOrder
        if (next >= cursor) break
        cursor = next
    }
    return start
}

/** Newest sort_order of a page of [turnCount] complete turns after [afterSortOrder]. */
internal suspend fun ChatViewModel.newerTurnEnd(afterSortOrder: Int, turnCount: Int): Int? {
    var cursor = afterSortOrder
    var users = 0
    var end: Int? = null
    while (true) {
        val probe = chatRepository.dao.loadNewerSortAnchors(
            sessionId,
            cursor,
            ChatHistoryWindow.probeLimit(),
        )
        if (probe.isEmpty()) break
        val absorbed = ChatHistoryWindow.absorbNewer(
            probe.map { it.toWindowAnchor() },
            turnCount = turnCount,
            usersAlready = users,
        )
        end = absorbed.endSortOrder ?: end
        users = absorbed.usersIncluded
        if (!absorbed.needsMore || probe.size < ChatHistoryWindow.probeLimit()) break
        val next = probe.last().sortOrder
        if (next <= cursor) break
        cursor = next
    }
    return end
}

internal suspend fun ChatViewModel.loadSortRange(range: SortRange): List<MessageEntity> =
    chatRepository.loadMessagesInSortRange(sessionId, range.startInclusive, range.endExclusive)

/**
 * Rows strictly before [beforeSortOrder] that finish the turn the loaded
 * window cut in half. Empty when that window already starts on a user message
 * or at the session start.
 */
internal suspend fun ChatViewModel.loadSplitTurnPrefix(beforeSortOrder: Int): List<MessageEntity> {
    // Complete the cut turn before painting the cold-open tail. The probe
    // reads only role + sort_order, so a long tool turn does not parse bodies
    // merely to find its boundary.
    //
    // The prefix is capped at the same ceiling as the cold-open tail. A tool
    // turn can itself be thousands of rows; completing it unbounded would
    // undo the render-window cap the moment a page boundary lands inside one.
    // What does not fit stays above the window and comes back through
    // loadOlderMessages.
    val rawStart = olderTurnStart(beforeSortOrder, turnCount = 1) ?: return emptyList()
    if (rawStart >= beforeSortOrder) return emptyList()
    val capped = chatRepository.dao.loadOlderSortAnchors(
        sessionId,
        beforeSortOrder,
        com.openminis.app.data.repository.ChatRepository.MAX_TAIL_MESSAGES,
    ).lastOrNull()?.sortOrder ?: return emptyList()
    val start = maxOf(rawStart, capped)
    if (start >= beforeSortOrder) return emptyList()
    return loadSortRange(SortRange(start, beforeSortOrder))
}

internal suspend fun ChatViewModel.collectDigestLines(beforeSortOrder: Int): List<HistoryDigest.Line> {
    val newestFirst = ArrayList<HistoryDigest.Line>()
    var used = 0
    var cursor = beforeSortOrder
    while (used < HistoryDigest.MAX_CHARS) {
        val page = chatRepository.dao.loadPreviewPageBefore(sessionId, cursor, limit = 40)
        if (page.isEmpty()) break
        var stop = false
        for (row in page) {
            val line = row.toDigestLine()
            val clipped = HistoryDigest.clip(line.role, line.text)
            if (clipped.isEmpty()) continue
            if (newestFirst.isNotEmpty() && used + clipped.length > HistoryDigest.MAX_CHARS) {
                stop = true
                break
            }
            newestFirst.add(line)
            used += clipped.length + 1
        }
        if (stop || page.size < 40) break
        val next = page.last().sortOrder
        if (next >= cursor) break
        cursor = next
    }
    return newestFirst.asReversed()
}

private fun MessagePreviewRow.toDigestLine(): HistoryDigest.Line =
    HistoryDigest.Line(role, HistoryDigest.readablePreview(preview.orEmpty()))
