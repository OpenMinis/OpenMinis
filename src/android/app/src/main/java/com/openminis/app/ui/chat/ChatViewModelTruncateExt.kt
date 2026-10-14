package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * T187: drop the message at [messageId] *and* every later message
 * (in UI, in agentHistory, and on disk) so the new sendMessage()
 * call below this can persist the edited text as a fresh user
 * turn at the same position. Reuses the cutoff-search machinery
 * from retryFromMessage but offsets by `entity.sortOrder` (not
 * +1) — retry preserves the original turn, edit replaces it.
 *
 * Returns false when the DB truncation could not be carried out because the
 * anchor lookup itself FAILED ([T-anchor-lookup-fail-closed]) — the caller must
 * then NOT send the replacement text, because the rows it would replace are
 * still live and the edit would land a duplicate turn on top of them. `_error`
 * carries the user-visible reason. True covers both "cut applied" and "the
 * message has no persisted row at all" (an un-persisted optimistic bubble), the
 * latter being safe to send over.
 */
internal suspend fun ChatViewModel.truncateBeforeEdit(messageId: String): Boolean {
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return true

    val deletedMessages = messages.subList(index, messages.size).toList()
    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    // [T-delete-anchor-by-id] Anchor the truncation by the edited message's
    // DB row id, NOT by visible-user ordinal. The old ordinal walk counted a
    // different set of user rows than the UI list (UI counts every user-role
    // ChatMessage; the DB walk skips toolResult-only user rows), so the cut
    // could land dozens of turns early and deleteMessagesAfter wiped the
    // session tail (2026-10-14 incident). ChatMessage.id IS the DB row id.
    //
    // Resolved BEFORE anything is mutated: on a failed lookup this returns
    // false without touching the UI, the memory log or the DB, so the caller
    // can drop the edit and the session is left exactly as it was.
    val edited = messages[index]
    val anchorIds = listOf(edited.id) + edited.sourceDbIds
    val resolved = resolveAnchorSortOrder(anchorIds)
    if (resolved.isFailure) {
        // [T-anchor-lookup-fail-closed] A thrown lookup is not "the row isn't
        // persisted" — the tail is still live. Sending the edited text anyway
        // would land a replacement turn ON TOP of the rows the user just
        // edited away, and the next history rebuild would put both in front of
        // the model. Abort the send and say so.
        AppLogger.error(
            ChatViewModel.TAG_STREAM,
            "✏️ truncateBeforeEdit: anchor lookup FAILED for ${edited.id.take(8)} — edit not sent: " +
                resolved.exceptionOrNull()?.message,
        )
        _error.value = "Couldn't read the message history — the edit was not sent. Please try again."
        return false
    }
    val cutoffSortOrder = resolved.getOrNull() ?: -1

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
    return true
}
