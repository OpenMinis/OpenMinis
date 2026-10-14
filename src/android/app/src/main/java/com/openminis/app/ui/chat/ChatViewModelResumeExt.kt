package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resume an interrupted agent loop. Injects a `<system-reminder>` into
 * agentHistory so the model picks up where it left off, then re-enters
 * the agent loop in a fresh [streamJob]. Mirrors iOS
 * AIChatViewModel.resume().
 *
 * Safe to call only when [canResume] is true and [isStreaming] is false.
 * Clears [_canResume] on entry so repeated taps don't stack.
 */
fun ChatViewModel.resume() {
    if (_isStreaming.value || !_canResume.value) return
    val provider = currentProvider ?: run {
        _error.value = "No provider configured"
        return
    }
    _canResume.value = false
    _error.value = null
    // [T-android-resume-banner-race] A resume is a fresh claim too: bump the
    // epoch so any still-winding-down tail from the interrupted run (or a
    // concurrent loadSession re-detection) cannot re-raise the banner over
    // the turn this resume is about to start.
    claimResumeEpoch()
    // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
    // setInlineError (which persisted an error sticker on the last assistant
    // row). Clear it now so a successful resume doesn't merge-resurrect the
    // turn-limit banner on the next reload.
    clearPersistedLastAssistantError()
    AppLogger.info(ChatViewModel.TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
    // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
    // placeholder is the only visible delta until the model's first
    // token, and the auto-follow tuple won't advance until content
    // streams — ChatScreen would otherwise leave the placeholder
    // behind the input bar.
    _forceScrollToBottom.tryEmit(Unit)

    viewModelScope.launch {
        // Rebuild agentHistory from the DB before continuing — a fresh
        // sendMessage() does exactly this (rebuildAgentHistoryFromDatabase),
        // which is why a normal send never carries stale context while a
        // Resume could. The in-memory agentHistory lags the DB whenever the
        // process was killed mid-turn and the ViewModel was restored with a
        // partial in-memory tail, or any path committed rows the in-memory
        // list missed. The DB is the authoritative store; trusting memory
        // here is how a resume picks up an old, pre-interruption context.
        rebuildAgentHistoryFromDatabase(activeSessionId)
        // If history ends with assistant (Case 2: text-cancel committed a
        // partial assistant turn), append a continue reminder as a user
        // message. If it ends with user tool_result (Case 1), it's already
        // a valid starting point for the next API call — no reminder needed.
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT
        if (historyEndsWithAssistant) {
            val reminder =
                "<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>"
            val parts = listOf<AgentContentPart>(AgentContentPart.Text(reminder))
            appendBoundedHistory(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = reminder,
                    contentParts = parts,
                )
            )
            launch(Dispatchers.IO) {
                val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
                val persisted = chatRepository.appendMessage(activeSessionId, "user", partsJson)
                withContext(Dispatchers.Main.immediate) {
                    notePersistedUiRow(null, persisted.id)
                }
            }
        }

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt =
            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

        AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=true (sid=$activeSessionId)")
        _isStreaming.value = true
        streamJob = launchActiveRun(activeSessionId, Dispatchers.IO, ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId), beforeStart = { streamJob = it }) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "resume streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let {
                        providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy
                    } ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(provider)
                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop RETURN normal")
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(ChatViewModel.TAG_STREAM, "resume drainQueuedPrompts RETURN")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled (resume)")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (resume)", e)
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        setInlineError(e.message ?: "Unknown error")
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY enter")
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
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY exit")
                }
            } catch (e: com.openminis.app.service.SlotQueueTimeout) {
                if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                    setInlineError(e.message ?: "会话排队超时，名额已释放")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot (resume)")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob EXIT")
        }
    }
}
