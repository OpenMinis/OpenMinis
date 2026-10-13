package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * T187: drop the message at [messageId] *and* every later message
 * (in UI, in agentHistory, and on disk) so the new sendMessage()
 * call below this can persist the edited text as a fresh user
 * turn at the same position. Reuses the cutoff-search machinery
 * from retryFromMessage but offsets by `entity.sortOrder` (not
 * +1) — retry preserves the original turn, edit replaces it.
 */
internal suspend fun ChatViewModel.truncateBeforeEdit(messageId: String) {
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    val deletedMessages = messages.subList(index, messages.size).toList()
    // [T-android-uimessages-sublist-cme] Defensive: without `.toList()` this
    // stores a live subList VIEW as `_messages.value`.
    //
    // Reproduced on device with this `.toList()` reverted (Pixel 4a): the
    // truncation ran (8 messages → 4) and a further message was sent, and it
    // did NOT crash — the next `+` copies the view into a plain ArrayList
    // before anything can invalidate it. So this line is hardening, not the
    // proven cause of the reported CME. See the long note on `uiMessages`.
    // (`deletedMessages` above already copies; this line did not.)
    val kept = messages.subList(0, index).toList()
    _messages.value = kept
    if (_streamingById.value.isNotEmpty()) {
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }
    revokeMemoryWritesInDeletedMessages(deletedMessages)

    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    // [T-delete-anchor-by-id] Anchor the truncation by the edited message's
    // DB row id, NOT by visible-user ordinal. The old ordinal walk counted a
    // different set of user rows than the UI list (UI counts every user-role
    // ChatMessage; the DB walk skips toolResult-only user rows), so the cut
    // could land dozens of turns early and deleteMessagesAfter wiped the
    // session tail (2026-10-14 incident). ChatMessage.id IS the DB row id.
    val edited = messages[index]
    val anchorIds = listOf(edited.id) + edited.sourceDbIds
    val cutoffSortOrder = anchorIds.firstNotNullOfOrNull { anchorId ->
        runCatching { chatRepository.dao.sortOrderForMessageId(anchorId) }.getOrNull()
    } ?: -1
    if (cutoffSortOrder >= 0) {
        chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
    } else {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "✏️ truncateBeforeEdit: anchor row ${edited.id.take(8)} not found in DB — " +
                "skipping DB truncation (UI already truncated)",
        )
    }
    val remaining = awaitBoundedHistoryRebuild(sid)
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
    )
}
