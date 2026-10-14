package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * T189: spawn a fresh agent loop to drain whatever the user queued during
 * the cancelled stream. 200ms delay matches iOS resumeQueueAfterCancel
 * (Task.sleep(200_000_000)) — gives the cancelled streamJob's finally block
 * room to release the concurrency slot + write back state. Race-guards on
 * entry: empty queue (user withdrew) or already streaming (user manually
 * retried) → noop return.
 *
 * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
 * verbatim (incl. OAuth token refresh + Claude Code prefix), so a queued
 * prompt drain after cancel uses the same plumbing as a fresh send.
 */
internal fun ChatViewModel.resumeQueueAfterCancel() {
    viewModelScope.launch {
        kotlinx.coroutines.delay(200)
        if (_promptQueue.value.isEmpty()) return@launch
        if (_isStreaming.value) return@launch
        // [T-android-compact-queued-drain] Defer while a compact is in
        // flight — draining would mutate agentHistory mid-marker-write.
        // Safe to just return: every SUCCESSFUL compact re-kicks this
        // function from its own tail, so a deferred drain is never lost
        // (and a failed compact leaves the queue pending by design).
        if (_isCompacting.value) {
            AppLogger.info(ChatViewModel.TAG, "resumeQueueAfterCancel: compact in flight — deferring to its completion kick")
            return@launch
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            AppLogger.warning(ChatViewModel.TAG, "resumeQueueAfterCancel: no provider, dropping queue")
            _promptQueue.value = emptyList()
            _messages.value = _messages.value.filterNot { it.isQueued }
            return@launch
        }
        var provider: LLMProvider = initialProvider

        // Refresh OAuth token if needed (mirrors sendMessage L2477-2501).
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
                            provider = com.openminis.app.provider.ProviderFactory.create(
                                instance, freshToken, currentModel ?: provider.model, context
                            )
                            currentProvider = provider
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "OAuth token refresh failed (resumeQueueAfterCancel): ${e.message}")
            }
        }

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // T145: claim the streaming flag synchronously before launching
        // the streamJob so a concurrent send/retry tap is rejected by the
        // entry guard. Mirrors sendMessage discipline.
        AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true
        _canResume.value = false
        _error.value = null
        // [T-android-resume-banner-race] Queue continuation is a fresh claim
        // as well — bump so stale tails cannot re-raise the banner.
        claimResumeEpoch()

        streamJob = launchActiveRun(activeSessionId, Dispatchers.IO, ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId), beforeStart = { streamJob = it }) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(provider)

                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts CALL")
                    drainQueuedPrompts(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts RETURN")
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drain CANCELLED")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel drain EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Queued drain error (resumeQueueAfterCancel)", e)
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        setInlineError(e.message ?: "Unknown error")
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY enter")
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
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY exit")
                }
            } catch (e: com.openminis.app.service.SlotQueueTimeout) {
                if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                    setInlineError(e.message ?: "会话排队超时，名额已释放")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob CANCELLED waiting for slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "resumeQueueAfterCancel streamJob EXIT")
        }
    }
}
