package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.data.CapabilityRouter
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.R

/**
 * @param skipContextCheck set by the pre-send context dialog's own actions,
 *   which have already made the compact decision. Without it the re-entrant
 *   send would re-evaluate the same (still stale until the next usage
 *   chunk) token count and pop the dialog again — iOS guards the identical
 *   re-entry with `skipCompactCheck`.
 */
internal fun ChatViewModel.sendMessage(
    text: String,
    skipContextCheck: Boolean,
    internalGoalRun: Boolean = false,
) {
    // [T-android-paste-mediaref] `[Pasted#N]` markers are NOT expanded here
    // any more.
    //
    // They used to be: this funnel substituted the full text inline, so the
    // persisted message held one enormous `text` part. That is the same
    // shape that made huge tool results freeze the app — every time the
    // bubble scrolled into view, TextKit had to lay out the whole block on
    // the main thread. The markers now survive down to the parts-building
    // step below, where each becomes its own `text/plain` mediaRef; the full
    // content is re-attached to the REQUEST from disk (see toLLMMessage and
    // the fresh-send contentParts), so the model still sees everything while
    // the bubble stays small.
    //
    // The buffer is cleared where the mediaRefs are actually written, not
    // here — clearing at this point would strip the content out from under
    // a send that then bails on the context-check paths below.
    val trimmed = text.trim()
    // While streaming, enqueue instead of silently dropping (iOS: send vs enqueuePrompt).
    if (_isStreaming.value) {
        enqueuePrompt(text)
        return
    }
    // T180: allow attachments-only sends (no caption). Mirrors iOS, where
    // an empty text + non-empty attachments still produces a valid user
    // message. Without this an image-only "look at this" send dropped.
    if (!internalGoalRun && trimmed.isBlank() && _attachments.value.isEmpty()) return
    if (_isCompacting.value) {
        appendSystemInfo(
            text = context.getString(R.string.vm_wait_compact_before_send),
            iconKind = "compact",
        )
        return
    }
    // Context pressure check. Unlike before, needsCompact now HOLDS the
    // send: either compact silently (auto-compact on) or ask first. The
    // whole point is that the request which tripped the threshold must not
    // be the one that goes out over-length.
    if (!skipContextCheck && !internalGoalRun) {
        when (checkContextBeforeSend()) {
            ChatViewModel.PreSendContextAction.PROCEED -> {}
            ChatViewModel.PreSendContextAction.COMPACT_THEN_SEND -> {
                pendingSendText = text
                _inputText.value = ""
                compactAndSendPending()
                return
            }
            ChatViewModel.PreSendContextAction.ASK_USER -> {
                // Park the text on the VM (not the composer) so the dialog
                // owns it; cancelCompactBeforeSend puts it back.
                pendingSendText = text
                _inputText.value = ""
                _showCompactBeforeSendPrompt.value = true
                return
            }
        }
    }
    // A fresh send supersedes any pending resume — mirror iOS which clears
    // canResume at the top of send().
    _canResume.value = false
    // [T-android-resume-banner-race] Claim the epoch BEFORE the run starts
    // (and before waiting for the old run's concurrency slot): any old-run
    // tail that reaches its own `_canResume = true` after this point sees a
    // stale snapshot and drops the raise — the banner must not come back
    // over the fresh turn this send is about to start.
    claimResumeEpoch()
    // T185: clear the share-injected flag the moment the user actually
    // sends. Without this, the "Move to…" capsule (gated on
    // hasInjectedShareContent) keeps floating over the user-message row
    // after the share content has been committed — it then visually
    // collides with the user-attachment chips, which renders as the
    // "image attachment shows up as Move to" symptom in T185. Mirrors
    // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
    // inside the send button's tap closure).
    if (!internalGoalRun && _hasInjectedShareContent.value) _hasInjectedShareContent.value = false

    val initialProvider = currentProvider
    if (initialProvider == null) {
        _error.value = "No provider configured"
        return
    }
    var provider: LLMProvider = initialProvider

    _error.value = null

    val currentAttachments = if (internalGoalRun) emptyList() else _attachments.value
    val groupIdForCaps = _selectedGroupId.value
    if (!groupIdForCaps.isNullOrBlank()) {
        val neededCaps = CapabilityRouter.neededForTask(
            trimmed,
            currentAttachments.any { it.isImage },
        )
        // [T-capability-route-no-vm-mutation] Capability routing is a
        // PER-TURN detour: this message needs IMAGE_INPUT, so a capable
        // member serves THIS request while the user's active entry stays
        // untouched for the next plain-text turn. The old call went through
        // resolveProviderFromGroup, which unconditionally rewrote
        // currentProvider/_modelName while pinActiveEntry=false left
        // _activeEntryId on the user's pick — the VM then showed model A
        // while buildSystemPrompt injected the persona of entry B for the
        // rest of the session. Resolve the target WITHOUT touching VM state
        // and build a local provider for this turn only.
        resolveGroupTargetEntry(groupIdForCaps, _activeEntryId.value, neededCaps)?.let { (target, reason) ->
            reason?.let { SessionActivityTracker.updateToolStatus(it.take(80)) }
            val instance = providerRepository.instance(target.providerInstanceId)
            // Non-null by construction (availableMemberEntries required a
            // credential); guard anyway so a mid-send config change degrades
            // to the user's provider instead of crashing.
            if (instance != null) {
                val apiKey = providerRepository.usableApiKey(instance) ?: ""
                provider = ProviderFactory.create(instance, apiKey, target.model, context)
            }
        }
    }
    if (internalGoalRun && provider.model.isPureVideoGenerator) {
        appendSystemInfo("Goal execution needs a text-capable model. Select one and resume the Goal.", "info")
        return
    }
    if (!internalGoalRun) clearAttachments()

    // T145: claim _isStreaming synchronously so a rapid second tap can't
    // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
    AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    // [T-android-instant-thinking] REMOVED: the placeholder inserted here
    // was meant to give instant "thinking" feedback before DB writes complete,
    // but it broke live streaming rendering — buildFlatChatItems frozen/live
    // split + reverseLayout keying prevented the streaming-side-channel delta
    // from applying to the pre-inserted placeholder bubble. Regression tracked
    // to 89896de (introduced) + 7c89d38 (order fix exposed it). Reverted to
    // runAgentLoop's own message creation (overrideAssistantId=null below).
    // The scroll-retention benefit of 89896de was in ChatScreen and is kept.

    // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send
    // only reaches here when no turn is streaming (the _isStreaming guard
    // at the top routes mid-stream sends to enqueuePrompt). So any residual
    // _streamingById entry is an orphan stranded by a prior turn that
    // exited without draining it (e.g. a late delta re-added the entry
    // after finalizeAtTurnLimit / cancel cleared it). mergeStreamingOverlay
    // forces isStreaming=true on any message holding such an entry, so an
    // orphan would render a second "thinking" row alongside the new turn's.
    // Flush them into the canonical messages (isStreaming=false) before the
    // new streaming message is created — no two messages ever stream at once.
    if (_streamingById.value.isNotEmpty()) {
        AppLogger.warning(ChatViewModel.TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
        flushAllStreamingDeltas()
    }

    // T187: when the user is editing a previous message, truncate the
    // conversation from that message (inclusive) before persisting the
    // edited text as a fresh user turn. Snapshot + clear the id here so
    // any error in the truncate path doesn't leave the composer stuck
    // in edit mode.
    val editingId = if (internalGoalRun) null else _editingMessageId.value
    if (editingId != null) _editingMessageId.value = null

    viewModelScope.launch {
        var streamLaunched = false
        try {
        // Ensure session exists in DB (creates on first message for draft sessions)
        val activeSessionId = ensureSession()

        if (editingId != null && !truncateBeforeEdit(editingId)) {
            // [T-anchor-lookup-fail-closed] The truncation anchor could not be
            // read, so the rows this edit would replace are still live in the
            // DB. Persisting the edited text anyway would stack a duplicate
            // turn on top of them (and the next history rebuild would show the
            // model both). Drop the send; truncateBeforeEdit mutated nothing on
            // this path and already raised _error with the reason.
            return@launch
        }

        val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

        // [T-android-paste-mediaref] Fold `[Pasted#N]` markers out to disk
        // BEFORE persisting, so the stored message carries a mediaRef per
        // paste instead of one huge text part.
        val pasted = buildPastedParts(trimmed, activeSessionId)
        if (pasted != null) {
            // Safe to clear now: the content is on disk and the parts JSON
            // below references it, so nothing depends on the buffer any more.
            _pastedTexts.value = _pastedTexts.value.filterNot { it.id in pasted.consumedIds }
        }

        // Save user message — text + persisted mediaRef parts so images survive
        // a session reload (T128). Non-image attachments still only contribute
        // their name (rendered as a file tile) and are not persisted.
        val userPartsJson = buildUserPartsJson(
            trimmed,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = pasted?.partsJson,
        )
        val persistedUser = if (internalGoalRun) null else
            chatRepository.appendMessage(activeSessionId, "user", userPartsJson)

        if (persistedUser != null) {
            val userMsg = ChatMessage(
                id = persistedUser.id,
                role = "user",
                content = pasted?.let { p ->
                    p.consumedIds.fold(trimmed) { acc, id -> acc.replace(PastedText.placeholderFor(id), "") }
                        .trim()
                } ?: trimmed,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames + (pasted?.uiNames ?: emptyList()),
                attachmentUris = prepared.nonImageUris + (pasted?.uiUris ?: emptyList()),
                sourceDbIds = listOf(persistedUser.id),
            )
            _messages.value = trimLoadedWindow(_messages.value + userMsg)
            notePersistedUiRow(persistedUser.id, persistedUser.id)
        }
        val imageParts = prepared.imageParts

        // T132: build the user contentParts in iOS order — caption first
        // (only if non-empty), then per image emit
        //   text("[attached image: /var/minis/attachments/uploads/<f>]")
        //   ImageData(<bytes>, <mime>)
        // so the caption sits adjacent to the image in the wire payload,
        // and the agent's read_image tool can resolve the same path back
        // to bytes. Trailing <user-attached-files> XML block lets the
        // model see filenames/sizes without needing tool calls.
        // [T-android-paste-mediaref] The MODEL gets the fully expanded body
        // even though the bubble and the DB row do not. This is the whole
        // point of the split: local rendering stays cheap, the prompt is
        // unchanged from what it used to be.
        //
        // On later turns the same expansion is rebuilt from disk by
        // toLLMMessage's mediaRef branch, so history replay (retry, rerun,
        // session reload, compaction) sees the identical text.
        val modelBodyRaw = pasted?.modelText ?: trimmed
        // [T-user-transformers-wired] The user-transformer chain actually runs
        // here (the 257faf1 commit message claimed this wiring; the code never
        // had it). Enrichment is request-side only — the bubble and the DB row
        // keep the raw text — so retries/reloads re-inject exactly once.
        val workspaceHint = runCatching {
            val dir = com.openminis.app.sandbox.SessionWorkspace
                .hostDir(context.filesDir, activeSessionId, "workspace")
            dir.absolutePath.takeIf { dir.isDirectory }
        }.getOrNull()
        val modelBody = com.openminis.app.agent.MessageTransformerChain.applyUser(
            modelBodyRaw,
            com.openminis.app.agent.UserTransformContext(workspaceHint = workspaceHint),
        )

        val userContentParts = mutableListOf<AgentContentPart>()
        if (modelBody.isNotEmpty()) userContentParts.add(AgentContentPart.Text(modelBody))
        imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) userContentParts.add(AgentContentPart.Text("[attached image: $path]"))
            userContentParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
        prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

        if (persistedUser != null) {
            appendBoundedHistory(LLMMessage(
                role = LLMMessage.Role.USER,
                content = modelBody,
                imageParts = imageParts,
                contentParts = userContentParts,
                dbMessageId = persistedUser.id,
            ))
            // [T-context-ring] Refresh after user appends.
            refreshContextUsage()
        }

        // Refresh OAuth token if needed before sending (mirrors iOS validAccessToken)
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
                            // Recreate provider with fresh token
                            provider = com.openminis.app.provider.ProviderFactory.create(
                                instance, freshToken, currentModel ?: provider.model, context
                            )
                            currentProvider = provider
                            android.util.Log.i(ChatViewModel.TAG, "OAuth token refreshed before send")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(ChatViewModel.TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        // Build system prompt
        // Anthropic OAuth requires the Claude Code prefix in the system prompt
        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // Start agent loop with fallback. _isStreaming was set synchronously at top.
        streamLaunched = true
        val runSessionId = activeSessionId
        streamJob = launchActiveRun(
            runSessionId,
            Dispatchers.IO,
            ownerSessionIds = setOf(runSessionId, activeSessionId, sessionId),
            beforeStart = { streamJob = it },
        ) { run ->
            AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob ENTER sid=$runSessionId")
            try {
                // [T-STALL-DIAG] Snapshot BEFORE the (possibly blocking)
                // acquire. If the log shows this line and then no "slot
                // acquired", the turn is parked in acquireSlot — and this
                // line already records who was holding the slots, so the
                // leak is diagnosable from a single log.
                println(
                    "[T-STALL-DIAG] send PRE-ACQUIRE sid=$activeSessionId " +
                        SessionConcurrencyManager.diagSnapshot(),
                )
                // Acquire concurrency slot (suspends if at max)
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "send streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })


                // Resolve the active group's fallback strategy
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }

                // Build full fallback provider list upfront (mirrors iOS triedEntries approach)
                val fallbackProviders = buildFallbackProviders(provider)
                val groupChat = groupChatEnabled.value &&
                    !internalGoalRun &&
                    !provider.model.isPureVideoGenerator

                try {
                    if (groupChat) {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runGroupChat CALL")
                        groupChatCloseRequested = false
                        runGroupChat(provider, closing = false)
                        if (!run.isStopped && !groupChatCloseRequested) {
                            drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        }
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runGroupChat RETURN")
                    } else if (provider.model.isPureVideoGenerator) {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runVideoGenerationTurn CALL")
                        runVideoGenerationTurn(provider, modelBody, activeSessionId)
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runVideoGenerationTurn RETURN")
                    } else {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                            goalExecutionRun = internalGoalRun,

                        )
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop RETURN normal")
                        // Drain any prompts the user queued while this loop was running.
                        // Skipped on cancel: cancelled job won't reach here.
                        if (!run.isStopped) {
                            drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                            AppLogger.info(ChatViewModel.TAG_STREAM, "send drainQueuedPrompts RETURN")
                        }
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "send runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (all fallbacks exhausted)", e)
                    if (!run.isStopped) {
                        setInlineError(e.message ?: "Unknown error")
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob FINALLY enter")
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
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob FINALLY exit")
                }
            } catch (e: com.openminis.app.service.SlotQueueTimeout) {
                if (!run.isStopped) setInlineError(e.message ?: "会话排队超时，名额已释放")
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard — see
            // `var streamJob` KDoc; identical pattern as runRerunStreamTail.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob EXIT")
        }
        } finally {
            if (!streamLaunched) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
}
