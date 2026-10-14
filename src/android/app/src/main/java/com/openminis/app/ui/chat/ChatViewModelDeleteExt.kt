package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [T-android-delete-from-here] Delete [messageId] and every message after
 * it, leaving the conversation as it stood immediately before that turn.
 * Mirrors iOS `6717c0ab0`.
 *
 * Deliberately close to — but not the same as — [retryFromMessage]. Both
 * rewind the conversation to a point, so this reuses that method's DB
 * anchoring ([resolveDeleteCutoffSortOrder]): the tapped message resolves
 * straight to its own persisted `sort_order` by ROW ID (with `sourceDbIds`
 * covering merged bubbles) — no walk, no counting. The ordinal version this
 * replaced counted only user messages with VISIBLE text while skipping the
 * synthetic `<system-reminder>` / tool-result user rows the agent loop also
 * persists, and the two counters disagreed on any session with tool traffic:
 * the cut landed early and everything from there went with it (the
 * [T-delete-anchor-by-id] incident, 2026-10-14).
 *
 * The one deliberate difference is the cut point: retry keeps the target
 * user message and re-sends it, so it cuts at `sortOrder + 1`. Delete From
 * Here removes the target too, so it cuts at `sortOrder`. Off-by-one in
 * either direction is silent and destructive — one strands the message the
 * user asked to delete, the other eats the preceding turn.
 *
 * No stream is started and `_isStreaming` is never claimed: this is a pure
 * truncation. It is still refused while a turn is in flight, because
 * deleting rows out from under a live agent loop would leave
 * [agentHistory] describing messages that no longer exist.
 */
fun ChatViewModel.deleteFromMessage(messageId: String) {
    if (_isStreaming.value) return
    _canResume.value = false
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    // Snapshot what's about to go so any memory_write tool blocks inside
    // can be revoked — otherwise the on-disk daily log keeps entries from
    // messages the user just deleted.
    val deletedMessages = messages.subList(index, messages.size).toList()

    // Truncate the UI to everything BEFORE the target message, and drop
    // any queued prompts that belonged to the deleted range so a later
    // auto-drain can't resurrect them.
    val retainedHead = messages.subList(0, index)
    for (m in deletedMessages) {
        m.queuedPromptId?.let { pid ->
            _promptQueue.update { queue -> queue.filterNot { it.id == pid } }
        }
    }
    _messages.value = retainedHead

    // Scrub stream deltas pointing at truncated messages so they can't
    // resurface into a row that no longer exists.
    val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
    retainStreamFlushStates(keptIds)
    if (_streamingById.value.isNotEmpty()) {
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }

    revokeMemoryWritesInDeletedMessages(deletedMessages)

    val sid = activeSessionId ?: return
    viewModelScope.launch {
        val target = messages[index]
        val cutoffSortOrder = resolveDeleteCutoffSortOrder(sid, messages, index, target)
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }

        // Rebuild agentHistory from what survived, so the next turn is
        // built on the truncated conversation rather than a stale list.
        val tail = awaitBoundedHistoryRebuild(sid)
        // Drop the auto-trim digest too. llmDigestLines summarizes rows that
        // trimAgentHistory evicted from the model window; after a truncation
        // some of those rows no longer exist (the user just deleted them) and
        // the window boundary moved, so their summaries are stale memory of
        // content the user asked to forget. effectiveAgentHistory() injects
        // this digest on every turn, which is exactly the "deleted but still
        // in context" leak. The user-initiated compact summary is a different
        // store (_compactSummary) and is left alone: it condenses turns that
        // still exist. The digest re-accumulates naturally as the rebuilt
        // window trims again.
        llmDigestLines.clear()
        llmDigestOmitted = 0
        // Refresh the session's last-message preview; otherwise the
        // session list keeps quoting a message that no longer exists.
        // An empty remainder clears it rather than leaving the stale text.
        runCatching {
            chatRepository.updateSessionPreview(sid, tail.lastOrNull()?.partsJson ?: "[]")
        }
        AppLogger.info(
            ChatViewModel.TAG,
            "deleteFromMessage: cut at sortOrder=$cutoffSortOrder, " +
                "${deletedMessages.size} message(s) removed, ${tail.size} remain",
        )
    }
}
