package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ChatViewModel.loadSession() {
    // [T-android-resume-banner-race] Snapshot the resume epoch at load
    // start. loadSession's interrupted-tail re-detection sets
    // `_canResume = true` after a full DB walk; if the user has sent (or
    // resumed) something in the meantime, the epoch moved and the stale
    // detection must not re-show the banner over the fresh turn.
    val loadResumeEpoch = snapshotResumeEpoch()
    // T-android-crash-detected-halt: when CrashFrequencyDetector
    // tripped (#459, ≥3 crashes in last hour), skip the heavy
    // session-restore path entirely. Re-running the same persisted
    // state is exactly what produced the burst, so we'd just feed
    // a re-crash loop while the user is staring at the share dialog.
    // The flag clears the moment the dialog closes (share / dismiss /
    // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
    if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
        android.util.Log.w(ChatViewModel.TAG, "loadSession: safe-mode active, skipping session restore")
        // [T-android-perf-logging] Surface the skip on the Perf timeline
        // too — when a crash_or_stall recovery loop is suspected, this
        // distinguishes "loadSession ran and was slow" from "loadSession
        // was skipped (safe-mode), so the stall is elsewhere".
        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "loadSession.skipped",
            "reason=safeMode",
        )
        return
    }
    viewModelScope.launch {
        // [T-HANG-DIAG] timing markers to localise where session entry
        // stalls. Sentinel-tagged so a single grep -v can strip them
        // when this diagnostic is removed. Declared OUTSIDE the try
        // block so the EXIT log in `finally` can still read it after
        // an early-return / exception path.
        val tHangDiagStart = System.currentTimeMillis()
        println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
        try {
        val config = providerRepository.config.value
        _availableGroups.value = config.modelGroups

        // [T-android-intercept-banner-session-leak] InterceptFeedback is a
        // process-wide singleton, so a rejection banner from the PREVIOUS
        // session (e.g. a deep-link "new chat" right after denying a tool)
        // survived the switch and rendered on top of an empty chat. Gate
        // banners are session-scoped feedback: clear them on every session
        // entry so a fresh chat starts visually fresh.
        com.openminis.app.security.InterceptFeedback.clear()

        if (isDraft) {
            applyGateMode(_permissionMode.value)
            // Draft session: just set up provider using default group or first entry
            _sessionTitle.value = "New Chat"
            _sessionCategory.value = null
            if (!applyDefaultPrimarySlot(initialGroupId, applyGroupDefaults = true)) {
                // [T-newchat-default-model-fallback-android] No default
                // group (or it had no usable model) → last-used model, then
                // newest-provider/newest-text-model. Was firstOrNull().
                applyNewChatDefaultModel()
            }
            return@launch
        }

        // Existing session: load from DB
        val session = chatRepository.getSession(sessionId) ?: return@launch
        _sessionTitle.value = session.title ?: "New Chat"
        _sessionCategory.value = session.category
        _memoryEnabled.value = session.memoryEnabled != 0
        _permissionMode.value = com.openminis.app.security.PermissionMode.sessionDefault(
            session.permissionMode,
        )
        applyGateMode(_permissionMode.value)
        // T239: hydrate persisted thinking-mode override. null = unset
        // (use OFF as the legacy default); non-null = explicit user
        // choice persisted across cold-start. runCatching guards against
        // a stale enum name from a future rename — fall back silently
        // rather than crashing the session load.
        _thinkingLevel.value = session.thinkingOverride
            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?: ThinkingLevel.OFF

        // Priority 1: restore from persisted model_binding (group or entry)
        var resolved = restoreFromBinding(session.modelBinding)

        // Priority 2: fall back to stored model_id
        if (!resolved) {
            val entry = findModelEntry(session.modelId)
            if (entry != null) {
                currentModel = entry.model
                _modelName.value = entry.model.displayName
                _activeEntryId.value = entry.id
                val instance = providerRepository.instance(entry.providerInstanceId)
                if (instance != null) {
                    // [T-android-group-resolve-skip-uncredentialed] Gate on
                    // hasAnyCredential — keying off the API key alone left a
                    // session whose model lives on an OAuth provider unable
                    // to restore, despite being signed in.
                    val apiKey = providerRepository.usableApiKey(instance) ?: ""
                    if (providerRepository.hasAnyCredential(instance)) {
                        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
                        _providerName.value = instance.label.ifEmpty { entry.model.provider }
                        resolved = true
                        // Pin as a single provider model. Do NOT adopt a
                        // Settings model group just because this entry also
                        // appears in one — that would auto-switch models
                        // (and billing) the user never selected as a group.
                    }
                }
            }
        }

        // Priority 3: fall back to default group
        if (!resolved) {
            val defaultGroupId = providerRepository.defaultPrimaryGroupId
            if (defaultGroupId != null) {
                resolved = resolveProviderFromGroup(defaultGroupId)
                if (resolved) _selectedGroupId.value = defaultGroupId
            }
        }

        // [T-HANG-DIAG] measure DB load + transform separately so a long
        // load on one stage is obvious in the trace.
        //
        // T-android-gc-storm-hang-crash (P0, issue #17): on a 405-message
        // session with one 397KB user row, loadMessages + toChatMessages
        // + the agentHistory rebuild below ran on Main and triggered a
        // GC storm (34MB freed, repeated) that blocked the frame loop for
        // 58s → crash_or_stall restart. Hoist the heavy DB + JSON-parse
        // work off Main so the UI thread stays responsive even when one
        // row is large. Stays inside the existing safe-mode guard above
        // (#466/#470) — we only move work, not gating.
        val tHangDiagBeforeLoad = System.currentTimeMillis()
        data class LoadedSessionData(
            val messages: List<com.openminis.app.data.db.MessageEntity>,
            val ordered: List<ChatMessage>,
            val llmHistory: List<LLMMessage>,
            val totalMessages: Int,
            val firstMessageOffset: Int,
            val llmOldestSortOrder: Int?,
            val loadMs: Long,
            val transformMs: Long,
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
        val loaded = withContext(Dispatchers.IO) {
            val tIoBeforeLoad = System.currentTimeMillis()
            val tail = chatRepository.loadSessionTail(sessionId)
            val totalMessages = tail.totalMessages
            var dbRows = tail.messages
            if (dbRows.isNotEmpty() && dbRows.first().role != "user" && tail.firstMessageOffset > 0) {
                val prefix = loadSplitTurnPrefix(dbRows.first().sortOrder)
                if (prefix.isNotEmpty()) dbRows = prefix + dbRows
            }
            val rows = chatRepository.hydrateDisplayRows(dbRows)
            val firstMessageOffset = tail.firstMessageOffset
            val tIoAfterLoad = System.currentTimeMillis()
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "db.query.end",
                "count=${rows.size} total=$totalMessages offset=$firstMessageOffset",
            )
            val chatUi = rows.toChatMessages()
            val tIoAfterTransform = System.currentTimeMillis()
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toChatMessages.end",
                "count=${chatUi.size}",
            )
            // Pre-build the LLM history list off-Main too — toLLMMessage
            // re-parses partsJson for every row, which is the second
            // contributor to the GC storm. Build into a local list and
            // bulk-append to `agentHistory` on Main below; loadSession
            // runs once at init before any other writer touches
            // agentHistory, so a bulk addAll is race-free.
            //
            // [T-android-coldopen-window-parse] The UI tail is retained as
            // loaded, but request-side history has a smaller parse budget.
            // Re-parsing every tail row on each session open caused a GC
            // storm; rows outside this LLM budget are represented by the
            // request-only role+preview digest below.
            val windowRows = if (rows.size > ChatViewModel.INITIAL_LLM_HISTORY_ROW_CAP) {
                rows.subList(rows.size - ChatViewModel.INITIAL_LLM_HISTORY_ROW_CAP, rows.size)
            } else {
                rows
            }
            val llmOldestSortOrder = windowRows.firstOrNull()?.sortOrder
            val llm = ArrayList<LLMMessage>(windowRows.size)
            var totalPartsChars = 0L
            for (entity in windowRows) {
                totalPartsChars += entity.partsJson.length
                llm.add(entity.toLLMMessage())
            }
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toLLMMessage.end",
                "count=${llm.size} totalPartsChars=$totalPartsChars",
            )
            LoadedSessionData(
                messages = rows,
                ordered = chatUi,
                llmHistory = llm,
                totalMessages = totalMessages,
                firstMessageOffset = firstMessageOffset,
                llmOldestSortOrder = llmOldestSortOrder,
                loadMs = tIoAfterLoad - tIoBeforeLoad,
                transformMs = tIoAfterTransform - tIoAfterLoad,
            )
        }
        val messages = loaded.messages
        val ordered = loaded.ordered
        // [T-android-timeline-ledger] Single reset point for the window
        // ledger: cursors cleared, counters seeded from the tail summary.
        timeline.reset()
        timeline.seedCounters(offset = loaded.firstMessageOffset, total = loaded.totalMessages)
        llmDigestLines.clear()
        llmDigestOmitted = 0
        timeline.noteBounds(messages)
        // [T-android-coldopen-window-parse] agentHistory covers only the
        // newest request-side DB-row budget. Count in DB rows, not UI
        // messages — toChatMessages merges tool-result rows, so the UI list
        // is shorter than the tail.
        val windowedRows = minOf(messages.size, ChatViewModel.INITIAL_LLM_HISTORY_ROW_CAP)
        llmHistoryStartOffset = (loaded.totalMessages - windowedRows).coerceAtLeast(0)
        loadingOlderFlag.set(false)
        _isLoadingHistory.value = false
        val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
        val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
        println(
            "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                "tookMs=${loaded.loadMs}",
        )
        println(
            "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
        )
        // Per-message size sketch + oversize-row scan. Pure diagnostics —
        // does a full second pass over partsJson with several substring
        // searches per row, so on a 405-row session with 1MB total it
        // adds material main-thread time. Fire-and-forget on the IO
        // dispatcher so it can't contribute to the GC-storm hang the
        // rest of this task is trying to fix.
        viewModelScope.launch(Dispatchers.IO) {
            var totalChars = 0L
            var maxChars = 0
            var withTools = 0
            var withAttachments = 0
            for (m in messages) {
                val len = m.partsJson.length
                totalChars += len
                if (len > maxChars) maxChars = len
                // ContentPart serialises its discriminator in camelCase
                // ("toolUse" / "toolResult" — see ContentPart.PartType), so
                // the snake_case probe this used to run matched NOTHING and
                // reported toolMessages=0 on every session, including ones
                // whose history is almost entirely tool traffic. That is
                // the opposite of the signal this diagnostic exists to give
                // — it is here to finger oversized tool_result inlines as
                // the GC-storm culprit, and it was reporting them absent.
                if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) {
                    withTools++
                }
                // Same casing trap: attachments serialise as "mediaRef",
                // never as "image"/"attachment".
                if (m.partsJson.contains("\"mediaRef\"")) {
                    withAttachments++
                }
            }
            println(
                "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                    "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                    "attachmentMessages=$withAttachments",
            )

            // [T-HANG-DIAG] for any message ≥ 50_000 chars, log size /
            // role / createdAt / structural type markers only — NEVER
            // the partsJson content (or any prefix/suffix of it). Earlier
            // versions echoed head500/tail500 to localise the culprit;
            // now that the cause is known (oversized tool_result inlines)
            // and FileReadTool / AIChatViewModel.executeFileRead enforce
            // an 80 KB hard cap upstream, only metadata is needed for
            // future audits.
            val OVERSIZE_THRESHOLD = 50_000
            val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
            if (oversized.isNotEmpty()) {
                println(
                    "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                        "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                )
                for (m in oversized) {
                    val raw = m.partsJson
                    val len = raw.length
                    val hasToolUse = raw.contains("\"toolUse\"")
                    val hasToolResult = raw.contains("\"toolResult\"")
                    val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                    val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                    println(
                        "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                            "createdAt=${m.createdAt} len=$len " +
                            "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                            "hasImage=$hasImage hasBase64=$hasBase64 " +
                            "streamInterrupts=${m.streamInterruptCount}",
                    )
                }
            }
        }

        // Rebuild agentHistory from persisted messages.
        // Pre-built off-Main inside the withContext(Dispatchers.IO) block
        // above to avoid re-parsing partsJson on the UI thread. Safe to
        // bulk-addAll here because loadSession runs once at init before
        // any sender writes into agentHistory.
        appendBoundedHistoryAll(loaded.llmHistory)
        val tHangDiagAfterAgentHistory = System.currentTimeMillis()
        println(
            "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
        )

        // Restore the most-recent compact summary, if any, so the first
        // outgoing turn after reopening a compacted session still sees
        // the folded-away context via [effectiveAgentHistory]. Also gray
        // out every UI message that falls before the marker's boundary —
        // mirrors iOS Phase 2.5 restore (AIChatViewModel.swift:3360+).
        val marker = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }
            .onFailure { Log.w(ChatViewModel.TAG, "latestCompactMarker failed: ${it.message}") }
            .getOrNull()
        _compactSummary.value = marker?.summary
        _cachedLatestMarker = marker
        if (marker == null) {
            val oldest = loaded.llmOldestSortOrder
            if (oldest != null) {
                val prefix = withContext(Dispatchers.IO) { collectDigestLines(oldest) }
                val notExcerpted = withContext(Dispatchers.IO) {
                    chatRepository.dao.countMessagesBeforeSort(sessionId, oldest) - prefix.size
                }.coerceAtLeast(0)
                if (prefix.isNotEmpty() || notExcerpted > 0) {
                    val trimmed = llmDigestLines.toList()
                    val omitted = llmDigestOmitted
                    llmDigestLines.clear()
                    llmDigestOmitted = 0
                    rememberDigestLines(prefix + trimmed)
                    llmDigestOmitted += omitted + notExcerpted
                }
            }
        }

        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "stateflow.emit.begin",
            "count=${ordered.size}",
        )
        // The painted list is the loaded window. A smaller cap used to hide
        // the middle of that window and make it look deleted. Paging, not a
        // second cut, is what bounds memory.
        _messages.value = if (marker == null) {
            ordered
        } else {
            // Phase 2.5: build the historyDbIds set used by the
            // createdAt self-heal to filter to anchors that are
            // actually represented in agentHistory. Mirrors iOS
            // AIChatViewModel+Persistence.swift:406-408.
            val historyDbIds: Set<String> = buildSet {
                for (m in loaded.llmHistory) {
                    m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
            applyCompactMarkerGraying(ordered, marker, loaded.messages, historyDbIds)
        }
        refreshHistoryEdges()

        // Cold-start interrupt detection: an agent loop that was killed by
        // the OS (or app force-quit) leaves agentHistory in one of four
        // tell-tale shapes. Detecting any of them lets the user tap
        // Resume to pick up where the model left off — the in-memory
        // [_canResume] flag set by the live interruption paths is lost
        // across cold starts so we have to re-derive it from the DB.
        // Mirrors iOS AIChatViewModel.loadSession lines 3546-3581.
        //   Case A: last entry is user with all-toolResult parts —
        //           tools completed but the next model call never fired.
        //   Case B: last entry is assistant with any tool_use parts —
        //           the model requested tools that never executed.
        //   Case C: last entry is user with the synthetic "Continue"
        //           reminder text — text-cancel handler committed it
        //           but [resume] never re-entered the agent loop.
        //   Case D: last entry is a PLAIN-TEXT user turn that never got a
        //           reply at all — see below (GH#262/#263).
        val lastEntry = agentHistory.lastOrNull()
        // [T-android-orphan-user-tail GH#262/#263] `isActive` covers the
        // case this VM cannot see: another VM (or the foreground service)
        // is driving this very session, so `_isStreaming` is false HERE
        // while a request is genuinely in flight THERE. Without it, Case D
        // would light Resume on a turn that is merely still waiting.
        val trackerActive = SessionActivityTracker.isActive(activeSessionId)
        if (lastEntry != null && !_isStreaming.value && !trackerActive) {
            val isInterrupted = when (lastEntry.role) {
                LLMMessage.Role.USER -> {
                    val parts = lastEntry.contentParts
                    val allToolResults = parts.isNotEmpty() &&
                        parts.all { it is AgentContentPart.ToolResult }
                    val isContinueReminder = parts.size == 1 &&
                        (parts.first() as? AgentContentPart.Text)?.text
                            ?.contains("The user stopped the previous response") == true
                    // Case D — a user turn with NO reply after it at all.
                    //
                    // How it is produced: send() persists the user row
                    // (~5605) BEFORE the reply lands. If the process dies
                    // in between — Android reclaiming a backgrounded app is
                    // the reported case — the assistant side never reaches
                    // the store, and it cannot be reconstructed later
                    // because persistAssistantTurn() drops any row with no
                    // parts (~9112, the guard that stops us POSTing a
                    // content-less assistant message back to the API).
                    // An in-app first-turn network failure lands here too:
                    // setInlineError() attaches the error to the last
                    // ASSISTANT row and is a no-op when none exists (~5789),
                    // so that tail is equally reply-less and equally stuck.
                    //
                    // Before this case, such a tail reported canResume=false
                    // — no PAUSED badge, no Resume banner, and retryLast()
                    // bailing at its own `lastAssistantIdx < 0` guard
                    // (~5906). The session had NO recovery affordance and
                    // the user could only start a new chat.
                    //
                    // Deliberately LAST: A and C describe a turn that was
                    // mid-flight; D describes one that never started. Order
                    // keeps their more specific semantics (and logging)
                    // intact for tails that match both.
                    //
                    // False-positive safety — this must never fire on a turn
                    // that is simply still waiting. Three gates hold:
                    //   1. `!_isStreaming` (above) — send() sets it true at
                    //      ~5564, BEFORE persisting the user row at ~5605,
                    //      and clears it only in the stream epilogue, so the
                    //      whole in-flight window is excluded in-process.
                    //   2. `!trackerActive` (above) — the cross-VM case.
                    //   3. This block runs only from loadSession(), never
                    //      mid-stream.
                    // A cold start after a kill satisfies all three exactly
                    // because the process that was streaming no longer
                    // exists.
                    val isUnansweredUserTurn = !allToolResults && !isContinueReminder
                    allToolResults || isContinueReminder || isUnansweredUserTurn
                }
                LLMMessage.Role.ASSISTANT -> {
                    lastEntry.contentParts.any { it is AgentContentPart.ToolUse }
                }
                else -> false
            }
            if (isInterrupted) {
                // [T-android-group-pause-badge-restamp] This is a
                // RE-DETECTION of an interruption that already happened
                // (possibly days ago) — the persisted tail still looks
                // unfinished. It is NOT a new entry into the paused state,
                // so the badge must keep its original entry timestamp;
                // otherwise merely opening or cold-start scanning an old
                // chat resets the group card's 24h freshness window and a
                // long-stale pause flags its group forever. Raised BEFORE
                // the assignment (the collector runs asynchronously — see
                // the field's doc) and consumed by the collector, not here.
                markRedetectingInterruptedTail()
                raiseCanResume(loadResumeEpoch)
                // Name the shape, not just the role: Case D (reply-less
                // user tail) is the one that used to be invisible, so a
                // field log has to be able to tell it from A/C.
                val shape = when {
                    lastEntry.role == LLMMessage.Role.ASSISTANT -> "B/assistant-toolUse"
                    lastEntry.contentParts.isNotEmpty() &&
                        lastEntry.contentParts.all { it is AgentContentPart.ToolResult } -> "A/toolResult-tail"
                    lastEntry.contentParts.size == 1 &&
                        (lastEntry.contentParts.first() as? AgentContentPart.Text)?.text
                            ?.contains("The user stopped the previous response") == true -> "C/continue-reminder"
                    else -> "D/unanswered-user-turn"
                }
                Log.i(ChatViewModel.TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry.role} shape=$shape)")
            }
        }

        // Restore the last provider-reported context size on cold open. Without
        // this, the menu ring stays `?` until the next model call even though
        // the session has persisted an exact latestContextTokens value.
        if (!isDraft) {
            val sid = realSessionId.ifEmpty { sessionId }
            val restoredContext = withContext(Dispatchers.IO) {
                chatRepository.sessionTokenUsages(sid).asReversed().firstNotNullOfOrNull { json ->
                    runCatching {
                        org.json.JSONObject(json).optLong("latestContextTokens", 0L)
                    }.getOrNull()?.takeIf { it > 0L }
                }
            }
            if (restoredContext != null) {
                _lastTurnContextTokens.value = restoredContext
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            refreshContextUsage()
        }
        } finally {
            // T201: open the gate even on early `return@launch` (draft path,
            // missing-session path) and on exception, so the init-time
            // config.collect can never deadlock waiting for us.
            sessionLoaded.value = true
            // [T-HANG-DIAG] total time spent in loadSession from ENTER to
            // either successful completion or early return. tHangDiagStart
            // was captured just inside `try` so this covers the whole
            // body the user perceives as "loading".
            println(
                "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.exit",
                "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
        }
    }
}
