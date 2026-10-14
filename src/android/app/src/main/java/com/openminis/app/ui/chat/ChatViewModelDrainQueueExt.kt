package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-queue-atomic-mutation] Atomically take the whole queued-prompt
 * snapshot, leaving the queue empty; null when there is nothing to claim.
 *
 * CAS, not the old read-then-clear: the drain / inject paths run on
 * Dispatchers.IO (the agent loop) while `enqueuePrompt` writes from Main, and
 * a plain read+clear is NOT atomic across that boundary. A prompt enqueued
 * between the read and the clear was dropped silently — its `queued_msg_…`
 * placeholder bubble stayed "queued" forever — and the reverse interleaving
 * re-materialised an already-drained snapshot, so the next drain re-persisted
 * and re-sent a combined user message (the double-append class
 * `T-android-queued-message-duplicated-on-inject` fought). `resumeQueueAfterCancel`
 * made this worse: its `validAccessToken()` suspend gap sits between the
 * `_isStreaming` gate and the claim, so two drains can be in flight at once.
 * A CAS loser simply retries against the value that won the race, so every
 * prompt is claimed exactly once.
 */
internal fun ChatViewModel.claimPromptQueue(): List<QueuedPrompt>? {
    while (true) {
        val current = _promptQueue.value
        if (current.isEmpty()) return null
        if (_promptQueue.compareAndSet(current, emptyList())) return current
    }
}

/**
 * Drain queued prompts after an agent loop finishes. Each queued prompt is
 * appended to agentHistory, persisted, and re-runs the agent loop.
 * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
 */
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate>,
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
) {
    while (true) {
        val queued = claimPromptQueue() ?: break
        Log.i(ChatViewModel.TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
            queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

        // Flip isQueued=false on corresponding chat messages so they render as sent.
        // T189: also clear queuedPromptId so a later retry of this bubble
        // doesn't try to drop a phantom queue entry (and so the field state
        // matches what retryFromMessage's truncate path now produces).
        val queuedIds = queued.map { it.id }.toSet()
        _messages.value = _messages.value.map { m ->
            if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }

        // Build a combined user message (text + images from all queued prompts).
        // Persist as a single row.
        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)

        // T132: same shape as sendMessage — caption(s) first, then for each
        // image emit "[attached image: <path>]" + ImageData, finally the
        // <user-attached-files> XML. Keeps caption adjacent to image and
        // lets the agent re-read the file via read_image.
        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            if (prompt.text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(prompt.text)
                combinedParts.add(AgentContentPart.Text(prompt.text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
            combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        val userText = combinedText.toString()
        // [T-android-paste-mediaref] Same marker handling as the mid-loop
        // inject path above — see the note there for why queued prompts
        // need it at all.
        val drainPaste = buildPastedParts(userText, sid)
        if (drainPaste != null) {
            _pastedTexts.value =
                _pastedTexts.value.filterNot { it.id in drainPaste.consumedIds }
        }
        val userPartsJson = buildUserPartsJson(
            userText,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = drainPaste?.partsJson,
        )
        val persistedUser = chatRepository.appendMessage(sid, "user", userPartsJson)
        val queuedUiId = withContext(Dispatchers.Main.immediate) {
            _messages.value.firstOrNull { it.isQueued && it.content == userText }?.id
        }
        withContext(Dispatchers.Main.immediate) {
            notePersistedUiRow(queuedUiId, persistedUser.id)
        }

        appendBoundedHistory(LLMMessage(
            role = LLMMessage.Role.USER,
            content = drainPaste?.modelText ?: userText,
            imageParts = prepared.imageParts,
            contentParts = drainPaste?.let { p ->
                val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
            } ?: combinedParts,
            dbMessageId = persistedUser.id,
        ))

        try {
            if (groupChatEnabled.value && !provider.model.isPureVideoGenerator) {
                runGroupChat(provider, closing = false)
            } else {
            runAgentLoop(
                provider = provider,
                systemPrompt = systemPrompt,
                fallbackProviders = fallbackProviders,
                fallbackStrategy = fallbackStrategy,
            )
            }
        } catch (e: CancellationException) {
            Log.d(ChatViewModel.TAG, "Agent loop (queued-drain) cancelled")
            // Cancel mid-drain: cancelStream() will check _promptQueue
            // and call resumeQueueAfterCancel() if anything's still pending,
            // so just propagate.
            throw e
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Agent loop (queued-drain) error", e)
            if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                setInlineError(e.message ?: "Unknown error")
            }
            break
        }
    }
}
