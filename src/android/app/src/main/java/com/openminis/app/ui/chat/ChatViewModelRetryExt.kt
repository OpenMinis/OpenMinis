package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Retry the last agent turn (triggered by inline error Retry button).
 *
 *  T258: ports iOS AIChatViewModel.retry() (AIChatViewModel.swift:2079).
 *  Earlier behaviour blew away the entire failed assistant ChatMessage —
 *  including its already-completed tool_use cards — and reset
 *  agentHistory back to the last "real" user message, so on Retry every
 *  succeeded tool re-executed from scratch (the bug the user reported).
 *
 *  New behaviour:
 *   - Keep the assistant ChatMessage in the UI; clear its error sticker
 *     and the streaming/awaiting flags. Drop only tool blocks still in
 *     STREAMING / PENDING / RUNNING state — those have no matching
 *     tool_result and would orphan the request body.
 *   - From agentHistory, pop ONLY a trailing assistant entry (i.e. the
 *     turn whose stream errored). If the tail is already user(tool_result),
 *     the failure happened on the NEXT LLM call before any output —
 *     history is already valid, leave it.
 *   - GC orphaned tool_result rows whose tool_use is no longer in
 *     agentHistory (defends against the API "unexpected tool_use_id" 400).
 *   - Sync the DB: if we popped a trailing assistant, drop just its
 *     persisted row so a re-load doesn't resurrect the failed turn.
 */
fun ChatViewModel.retryLast() {
    if (_isStreaming.value) return
    // T-streaming-side-channel: belt-and-suspenders flush in case any
    // delta survived an earlier abnormal exit; retryLast is gated on
    // !isStreaming so this is normally a no-op.
    flushAllStreamingDeltas()
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
    _forceScrollToBottom.tryEmit(Unit)

    // 1. Keep the assistant message; clear error + streaming flags + drop
    //    in-flight tool blocks (STREAMING args / PENDING dispatch /
    //    RUNNING execution all have no tool_result, so they'd orphan).
    val lastMsg = msgs[lastAssistantIdx]
    val keptToolBlocks = lastMsg.toolBlocks.filter { block ->
        block.toolStatus !in ChatViewModel.IN_FLIGHT_TOOL_STATUSES
    }
    msgs[lastAssistantIdx] = lastMsg.copy(
        error = null,
        isStreaming = false,
        isAwaitingModelResponse = false,
        toolBlocks = keptToolBlocks,
    )
    _messages.value = msgs
    // [T-error-persist-android] Clear the persisted error sticker on the last
    // assistant row up-front. The DB-sync below only DELETES the trailing
    // assistant row when a trailing assistant was popped (Case A); in the
    // Case B path (tail = user(tool_result), next LLM call errored) the
    // stamped row is an EARLIER completed turn that is NOT deleted, so
    // without this clear the new successful turn would merge-resurrect the
    // old error banner on reload (msg.error ?: prev.error). Harmless in
    // Case A too — the row is deleted moments later regardless.
    clearPersistedLastAssistantError()

    val initialProvider = currentProvider ?: return
    var provider: LLMProvider = initialProvider
    _error.value = null

    // T145: claim _isStreaming synchronously — see retryFromMessage for rationale.
    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    viewModelScope.launch {
        var streamLaunched = false
        try {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

        // Rebuild agentHistory from the DB before deciding what to pop —
        // a fresh sendMessage() does the same (rebuildAgentHistoryFromDatabase),
        // so a normal send never carries stale context while a retry that
        // trusted memory could. In-memory agentHistory lags the DB after a
        // mid-turn process kill or any commit path memory missed; the DB is
        // authoritative. Rebuild FIRST, then run the pop/GC steps below on
        // the rebuilt (authoritative) history.
        rebuildAgentHistoryFromDatabase(sid)

        // 2. Pop ONLY a trailing assistant entry from agentHistory (mirrors
        //    iOS retry() :2107-2109). If the tail is already user(tool_result),
        //    the next-turn LLM call errored — leave history alone.
        val poppedAssistant = if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            agentHistory.removeAt(agentHistory.size - 1)
        } else null

        // 3. GC orphaned tool_result parts whose tool_use is gone (mirrors
        //    iOS retry() :2114-2128). Walks backward so removeAt is safe.
        val liveToolUseIds = agentHistory.flatMap { m ->
            m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        }.toSet()
        for (i in agentHistory.indices.reversed()) {
            val m = agentHistory[i]
            if (m.role != LLMMessage.Role.USER) continue
            val cleanedParts = m.contentParts.filter { p ->
                p !is AgentContentPart.ToolResult || p.id in liveToolUseIds
            }
            when {
                cleanedParts.isEmpty() && m.contentParts.isNotEmpty() ->
                    agentHistory.removeAt(i)
                cleanedParts.size < m.contentParts.size ->
                    agentHistory[i] = m.copy(contentParts = cleanedParts)
            }
        }

        // T258: only sync the DB when step 2 popped a trailing assistant
        // entry from agentHistory. In that case the persisted partial-
        // assistant row would resurrect the failed turn on next session
        // load — drop it (and only it) by deleting from its sort_order.
        // Completed assistant + tool_result rows for earlier turns are
        // unchanged and stay persisted, so retry preserves their cards.
        // toolLoopDetector keeps its accumulated state — completed tools
        // shouldn't be unlearned just because the next turn errored.
        if (poppedAssistant != null) {
            val dbMessages = chatRepository.loadMessagesTail(sid, ChatViewModel.MAX_AGENT_HISTORY_MESSAGES)
            val trailingAssistantSortOrder = dbMessages
                .lastOrNull { it.role == "assistant" }?.sortOrder
            if (trailingAssistantSortOrder != null) {
                // [T-answer-versions] 删除前把旧回答归档：重试后的新回答覆盖，
                // 但上一版不会凭空消失，可在 offloads/answer-versions/ 找回。
                val doomed = dbMessages.lastOrNull { it.sortOrder >= trailingAssistantSortOrder && it.role == "assistant" }
                if (doomed != null && doomed.partsJson.isNotBlank()) {
                    runCatching {
                        val dir = java.io.File("/var/minis/workspace/offloads/answer-versions")
                        dir.mkdirs()
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
                        val json = org.json.JSONObject()
                            .put("sessionId", sid)
                            .put("sortOrder", doomed.sortOrder)
                            .put("messageId", doomed.id)
                            .put("role", doomed.role)
                            .put("partsJson", doomed.partsJson)
                            .put("reasoningContent", doomed.reasoningContent.orEmpty())
                        java.io.File(dir, "${sid}_${doomed.sortOrder}_$stamp.json").writeText(json.toString(2))
                    }
                }
                chatRepository.deleteMessagesAfter(sid, trailingAssistantSortOrder)
                AppLogger.info(
                    ChatViewModel.TAG_STREAM,
                    "retryLast: deleted trailing assistant row sortOrder=$trailingAssistantSortOrder, kept ${trailingAssistantSortOrder} prior rows",
                )
            }
        } else {
            AppLogger.info(
                ChatViewModel.TAG_STREAM,
                "retryLast: agentHistory tail was user(tool_result) — no DB cleanup needed",
            )
        }

        // Refresh OAuth token if needed
        if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            try {
                val activeEntryId = _activeEntryId.value
                val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                if (instance != null) {
                    val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val freshToken = manager?.validAccessToken()
                    if (freshToken != null) {
                        val storedKey = providerRepository.loadApiKey(instance.id)
                        if (freshToken != storedKey) {
                            providerRepository.saveApiKey(instance.id, freshToken)
                            provider = ProviderFactory.create(instance, freshToken, currentModel ?: provider.model, context)
                            currentProvider = provider
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // _isStreaming was already set synchronously at the top.
        streamLaunched = true
        streamJob = launchActiveRun(activeSessionId, Dispatchers.IO, ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId), beforeStart = { streamJob = it }) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "retryLast streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(provider)
                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast drainQueuedPrompts RETURN")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (retryLast)", e)
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        setInlineError(e.message ?: "Unknown error")
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob FINALLY enter")
                    // [T-android-overlay-reply-status-34599] Surface
                    // the assistant's most recent reply text to the
                    // overlay BEFORE setInactive so the post-completion
                    // overlay state (no-running, has-outcome) carries a
                    // non-null excerpt. Reading _messages here is safe:
                    // we're in the finally block of the agent loop and
                    // the stream has already flushed its last delta.
                    publishOverlayReplyExcerpt(activeSessionId)
                    SessionActivityTracker.setInactive(activeSessionId)
                    SessionConcurrencyManager.releaseSlot(activeSessionId)
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob FINALLY exit")
                }
            } catch (e: com.openminis.app.service.SlotQueueTimeout) {
                if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                    setInlineError(e.message ?: "会话排队超时，名额已释放")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob EXIT")
        }
        } finally {
            if (!streamLaunched) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
}
