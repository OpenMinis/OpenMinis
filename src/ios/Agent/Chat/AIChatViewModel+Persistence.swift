import Foundation

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Session Persistence

extension AIChatViewModel {


    /// T-baseline-hash-drift: shared hasher used by reloadMessagesFromDB's
    /// reorder detector AND by send/drain paths that refresh the baseline
    /// without going through reloadMessagesFromDB. Without one shared
    /// implementation, the two writers can disagree and the reorder
    /// detector trips on every iCloud fetch after a send.
    static func computeOrderHash(of messages: [RawMessage]) -> Int {
        var hasher = Hasher()
        for m in messages {
            hasher.combine(m.id)
            hasher.combine(m.sortOrder)
        }
        return hasher.finalize()
    }

    func reloadMessagesFromDB(reason: String = "unspecified") async {
        guard let sessionId else {
            logger.info("[ReloadTrace] skip reason=\(reason) sid=nil")
            return
        }
        // [T-ios-cancel-reload-overwrite] If we skip because the loop is busy,
        // RE-ARM the deferred flag so the reload isn't lost. This matters for the
        // resume() path: tapping Resume sets canResume=false (whose didSet drains
        // pendingSyncReloadOnIdle and dispatches a reload) and then synchronously
        // sets isProcessing=true; by the time the dispatched reload runs, this
        // guard would skip it — and without re-arming, the deferred iCloud-sync
        // reload would be stranded forever. The next isProcessing→false drain
        // (line ~198) replays it. Mirrors the post-await guard below.
        guard !isProcessing, !isCompacting, !isTruncatingForRetry else {
            self.pendingSyncReloadOnIdle = true
            logger.info("[ReloadTrace] skip+rearm reason=\(reason) sid=\(sessionId.prefix(8)) isProcessing=\(self.isProcessing) isCompacting=\(self.isCompacting) isTruncatingForRetry=\(self.isTruncatingForRetry)")
            return
        }
        // [T-ios-defer-icloud-sync-after-stop] During the post-stop grace window
        // (60s after the loop stops, or until the user leaves / backgrounds),
        // hold the pull-driven reload too. Re-arm so the hold release replays it.
        // Manual/explicit reloads (force-pull, session open) pass through —
        // they're user intent, not background sync, and use distinct reasons.
        if isSyncHeld, reason == "cloudSyncDidFetchChanges" || reason == "deferredSyncReloadOnIdle" {
            self.pendingSyncReloadOnIdle = true
            logger.info("[ReloadTrace] HOLD reason=\(reason) sid=\(sessionId.prefix(8)) — post-stop sync hold active; reload deferred until release")
            return
        }
        // [T-ios-cancel-reload-overwrite] A reload rebuilds `messages` wholesale
        // from the DB. Right after a Stop, handleUserCancelledCleanup has just
        // preserved the candidate's blocks IN MEMORY — including a cancelled
        // streaming tool block that was never persisted (committedBlockCount <
        // blocks.count). If a deferred iCloud-sync reload fires here (cancel sets
        // isProcessing=false, which drains pendingSyncReloadOnIdle), it overwrites
        // that in-memory candidate with the DB's shorter version and the kept
        // content vanishes from screen (field repro 2026-05-31 18:56, the
        // "read two iOS worker session screens" tool bubble). When the session holds
        // unpersisted interrupted content (canResume && a candidate whose
        // blocks exceed committedBlockCount), defer this reload; the next safe
        // idle (after Resume / normal completion persists everything) replays it.
        if canResume,
           let candidate = messages.last(where: { $0.role == .assistant }),
           candidate.blocks.count > committedBlockCount {
            pendingSyncReloadOnIdle = true
            logger.info("[ReloadTrace] DEFER reason=\(reason) sid=\(sessionId.prefix(8)) — canResume with unpersisted candidate (blocks=\(candidate.blocks.count) > committed=\(self.committedBlockCount)); not overwriting in-memory interrupted content")
            return
        }
        let dbMessages = await ChatStore.shared.loadMessages(sessionId: sessionId)
        // [T-stream-empty-banner-trace race fix] Re-check after the
        // `await loadMessages` suspension: a `send()` invocation
        // queued just behind us on the main thread can have flipped
        // `isProcessing` to true and appended a placeholder assistant
        // message while we were waiting on disk I/O. If we proceed
        // here and trigger `loadSession()` below, that placeholder
        // gets wiped by the wholesale `messages = loadedUIMessages`
        // reassignment — the symptom is the user seeing their just-
        // sent message bubble + a top error banner with no assistant
        // bubble (because the catch path can't find a trailing
        // assistant to attach the error to, so it falls back to
        // `vm.errorMessage`). Punt to the idle-drain path which will
        // pick this up after `isProcessing` returns to false.
        if isProcessing || isCompacting {
            self.pendingSyncReloadOnIdle = true
            logger.info("[ReloadTrace] DEFER-AFTER-AWAIT reason=\(reason) sid=\(sessionId.prefix(8)) isProcessing=\(self.isProcessing) isCompacting=\(self.isCompacting) — race detected after loadMessages await, will reload on idle")
            return
        }
        let lastDbSortOrder = dbMessages.last?.sortOrder ?? 0
        let dbCount = dbMessages.count
        // Reload when new messages exist beyond our baseline. The second condition
        // handles synced sessions that were initially empty (lastKnownDbSortOrder == 0)
        // — messages arriving via iCloud should trigger a reload in that case too.
        // Also reload when messages were removed (e.g. retry deletion propagated from
        // another device) — detected by DB count being less than the known baseline.
        let hasNewMessages = lastDbSortOrder > lastKnownDbSortOrder
        let wasEmptyNowHasMessages = lastKnownDbSortOrder == 0 && !dbMessages.isEmpty && messages.count <= 1
        let hasRemovedMessages = dbCount < lastKnownDbCount
        // Detect a silent sort_order rewrite by another sync path (e.g.
        // iCloud HEAL when LWW skipped the message but sort_order
        // disagreed with server). Without this, the view model's
        // in-memory ChatMessage array stays in the old order forever
        // because lastDbSortOrder is unchanged. Hash the sequence of
        // (id, sortOrder) pairs and compare against the last seen.
        let hash = Self.computeOrderHash(of: dbMessages)
        let hasReorderedMessages = hash != lastKnownDbOrderHash && lastKnownDbOrderHash != 0
        if hasNewMessages || wasEmptyNowHasMessages || hasRemovedMessages || hasReorderedMessages {
            let branch: String
            if hasRemovedMessages { branch = "hasRemovedMessages" }
            else if hasReorderedMessages { branch = "hasReorderedMessages" }
            else if wasEmptyNowHasMessages { branch = "wasEmptyNowHasMessages" }
            else { branch = "hasNewMessages" }
            logger.info("[ReloadTrace] LOAD reason=\(reason) branch=\(branch) sid=\(sessionId.prefix(8)) dbCount=\(dbCount) knownCount=\(self.lastKnownDbCount) lastDB=\(lastDbSortOrder) lastKnown=\(self.lastKnownDbSortOrder) uiMsgs=\(self.messages.count) hashDiff=\(hash != self.lastKnownDbOrderHash)")
            lastKnownDbSortOrder = lastDbSortOrder
            lastKnownDbCount = dbCount
            lastKnownDbOrderHash = hash
            await loadSession()
        } else {
            logger.info("[ReloadTrace] SKIP-LOAD reason=\(reason) sid=\(sessionId.prefix(8)) dbCount=\(dbCount) lastDB=\(lastDbSortOrder) hashSame=\(hash == self.lastKnownDbOrderHash)")
            // Update baseline without reloading
            lastKnownDbSortOrder = max(lastKnownDbSortOrder, lastDbSortOrder)
            lastKnownDbCount = dbCount
            lastKnownDbOrderHash = hash
            // [T-ios-paused-badge-desync] SKIP-LOAD skips loadSession(), which
            // is the only place that evaluates the interrupted-loop tail and
            // sets canResume. If reconcileInterruptedSessions (foreground
            // reconcile) set a .paused badge while this cached VM's canResume
            // is still false, the detail page shows no "Interrupted" banner
            // even though the list shows ⏸. Re-run the lightweight check here.
            recheckCanResumeFromHistory()
        }
    }

    /// For UI, we merge consecutive assistant + tool-result turns into a single ChatMessage,
    /// matching how they appeared during the live session.
    func loadSession(activateSession: Bool = true) async {
        guard let sessionId else { return }

        let sinceAppear = (CFAbsoluteTimeGetCurrent() - Self.onAppearTimestamp) * 1000
        logger.info("[SessionLoad] loadSession START T+\(String(format: "%.0f", sinceAppear))ms session=\(sessionId) msgs=\(self.messages.count)")

        // [T-ios-scroll-suspend-leak] This vm may be re-entered via a cached VM
        // (ViewModelCache HIT) that still carries `messages` from a prior view of
        // this session. Annotate that state — a HIT with a non-empty `messages`
        // is exactly the reload path where a stale scroll-suspend would freeze the
        // UI on the pre-switch snapshot.
        let cacheState = self.messages.isEmpty ? "MISS/empty" : "HIT(msgs=\(self.messages.count))"
        logger.info("[SessionLoad] ViewModelCache \(cacheState) sid=\(sessionId.prefix(8)) suspended=\(self.streamingUIUpdatesSuspended)")

        // [T-ios-scroll-suspend-leak] Clear any scroll-induced suspend that leaked
        // from a previous view of this (or another) session. `scrollViewWillBeginDragging`
        // sets streamingUIUpdatesSuspended=true; if the user switches away or
        // backgrounds before scroll deceleration settles, `scrollViewDidEndDecelerating`
        // never fires (the collection view left the window), `setSuspendedForTransition`
        // only touches the outgoing vm, and `resumeAllStreamingUI` only runs on
        // foreground return — so the flag stays true and the Coordinator's pending
        // snapshot can never flush. Re-entering the session (this path) is the
        // guaranteed chokepoint: clear it here so the UI re-shows with fresh content.
        if streamingUIUpdatesSuspended {
            logger.warning("[SessionLoad] ⚠️ re-enter with stale streamingUIUpdatesSuspended=true — clearing to unblock pending snapshot")
            setStreamingUIUpdatesSuspended(false)
        }
        // [ReloadTrace] Caller stack at the spinner-on moment. UIKit
        // backtrace on device is unsymbolicated but the offsets are
        // enough to disambiguate whether the spinner came from a sync
        // inbound, AIChatView.onAppear, force-pull, or some unrelated
        // SwiftUI body re-evaluation.
        let stack = Thread.callStackSymbols.prefix(10).joined(separator: " | ")
        logger.info("[ReloadTrace] loadSession() → spinner ON sid=\(sessionId.prefix(8)) callerStack=\(stack)")

        if activateSession { Self.activeSessionId = sessionId }
        // [T-ios-session-unread-badge] Opening the session reads its new message,
        // so clear the transient red "unread" dot. Only `.unread` is removed; the
        // `.paused` entry (a different concern) is left to its canResume-driven
        // lifecycle below.
        if activateSession { SessionBadgeStore.shared.remove(.unread, for: sessionId) }
        // [T-session-paused-badge-active-false-positive] No clear-on-open here:
        // merely opening an interrupted session doesn't resolve it. The PAUSED
        // badge is driven by `canResume` (AIChatViewModel's canResume didSet) —
        // the interrupted-loop detection later in this method sets canResume,
        // which raises/keeps the badge; resolving (Resume / new turn) clears it.
        isLoadingSession = true
        defer {
            isLoadingSession = false
            hasCompletedInitialLoad = true
        }

        // Loop detector state is per-runtime; reload of a session clears it.
        toolLoopDetector.reset()

        // Load persisted memory-write toggle
        memoryEnabled = await ChatStore.shared.getMemoryEnabled(sessionId: sessionId)
        // [T-memory-enabled-new-session-bug DIAG] confirm loadSession set
        // vm.memoryEnabled from the DB/global default, and which session.
        AppLogger(category: "MemDiag").info("[MemDiag] loadSession sid=\(sessionId.prefix(8)) → vm.memoryEnabled=\(self.memoryEnabled)")

        // Clear stale compact/pending state from previous lifecycle
        skipCompactCheck = false
        if isCompacting {
            compactTask?.cancel()
            compactTask = nil
            isCompacting = false
        }
        if showCompactBeforeSendPrompt {
            showCompactBeforeSendPrompt = false
            pendingSendText = nil
            pendingSendAttachments = []
        }

        let loadStart = CFAbsoluteTimeGetCurrent()

        // Phase 1: Load raw messages from DB
        let dbStart = CFAbsoluteTimeGetCurrent()
        let rawMessages: [RawMessage]
        if let remoteDeviceId {
            rawMessages = await ChatStore.shared.loadRemoteMessages(sessionId: sessionId, deviceId: remoteDeviceId)
        } else {
            rawMessages = await ChatStore.shared.loadMessages(sessionId: sessionId)
        }
        let dbElapsed = (CFAbsoluteTimeGetCurrent() - dbStart) * 1000
        // Track the highest sortOrder and count so iCloud sync reload triggers for new or removed messages.
        lastKnownDbSortOrder = rawMessages.last?.sortOrder ?? 0
        lastKnownDbCount = rawMessages.count
        // [T-ios-token-usage-reload-per-iteration] Zero the session token
        // accumulators BEFORE the empty-session early return below, so switching
        // this vm from a non-empty session to an empty one doesn't leave the old
        // session's totals stranded in the sheet. Rebuilt from rawMessages further
        // down for non-empty sessions. loadSession can run repeatedly for one vm
        // (reopen, sync catch-up), and these are never zeroed elsewhere — without
        // this a reopened session would double-count on each reload.
        sessionInputTokens = 0
        sessionOutputTokens = 0
        sessionCacheReadTokens = 0
        sessionCacheWriteTokens = 0
        guard !rawMessages.isEmpty else {
            logger.info("[SessionLoad] \(sessionId) — empty session, DB query took \(String(format: "%.1f", dbElapsed))ms")
            // Still mount minis even for empty sessions so /var/minis works in terminal
            logger.info("🔍MOUNT loadSession empty-session fallback, still mounting for \(sessionId)")
            mountMinis(for: sessionId)
            return
        }

        let resolver = await ChatStore.shared.mediaFileURLResolver()
        logger.info("[SessionLoad] \(sessionId) — Phase 1 DB load: \(String(format: "%.1f", dbElapsed))ms (\(rawMessages.count) messages)")

        // Phase 2: Build UI messages and agent history
        let buildStart = CFAbsoluteTimeGetCurrent()
        let phase2aStart = CFAbsoluteTimeGetCurrent()
        var loadedUIMessages: [ChatMessage] = []
        var loadedHistory: [AgentMessage] = []

        // The current merged assistant ChatMessage (accumulates blocks across loop iterations)
        var currentAssistant: ChatMessage? = nil

        // Determine whether thinking blocks should be shown based on session config
        let showThinking = ProviderConfigStore.shared.inferenceConfig(for: sessionId)?.thinkingLevel.isEnabled ?? false

        for raw in rawMessages {
            // Always rebuild agent history (every message matters for API context).
            // Phase B: stamp the DB row id into dbMessageId so compact can resolve
            // boundaries by id after restore.
            var agentMsg = raw.toAgentMessage(mediaResolver: resolver)
            agentMsg.dbMessageId = raw.id
            loadedHistory.append(agentMsg)

            // [T-bridge-message-ui-leak] The #579 role-alternation bridge is
            // LLM-context-only: it stays in loadedHistory (appended above, so
            // the …user(tool_result) → assistant(bridge) → user(queued)…
            // sequence survives reload) but must never surface as a chat
            // bubble. Without this skip it either merged into the previous
            // assistant message's blocks or rendered standalone after app
            // restart. currentAssistant is deliberately left untouched — the
            // bridge is invisible, so it must not affect the merge chain
            // either (the queued user message that follows breaks it anyway).
            if raw.isInternalBridge {
                continue
            }

            if raw.role == .user && raw.isToolResultOnly {
                // Tool-result user messages: apply results to the current assistant's tool blocks
                if let assistant = currentAssistant {
                    Self.applyToolResults(from: raw, to: assistant, mediaResolver: resolver)
                }
                // Don't create a UI message for tool results
                continue
            }

            if raw.role == .assistant {
                if let assistant = currentAssistant {
                    // Continuation of an agent loop — append new blocks to
                    // existing assistant message AND extend the source-sort
                    // range so compact-marker resolution can map raw[i] back
                    // to this merged UI row even when i is mid-stream.
                    let continuation = raw.toChatMessage(mediaResolver: resolver, showThinking: showThinking)
                    let textParts = raw.parts.compactMap { if case .text(let s) = $0 { return s }; return nil }
                    let textBlockSummary = continuation.blocks.enumerated().compactMap { (i, b) -> String? in
                        guard b.kind == .text else { return nil }
                        return "len=\(b.content.count)"
                    }.joined(separator: ",")
                    // [T-log-noise-privacy 2026-07-18] info → debug: fires once
                    // per raw assistant row on EVERY session load.
                    logger.debug("[BlocksLost] RAW-MERGE so=\(raw.sortOrder) id=\(raw.id.prefix(8)) partsCount=\(raw.parts.count) textParts=\(textParts.count) rawTextLens=[\(textParts.map { "\($0.count)" }.joined(separator: ","))] blocks=\(continuation.blocks.count) textBlocks=[\(textBlockSummary)]")
                    for block in continuation.blocks {
                        assistant.blocks.append(block)
                    }
                    if let usage = continuation.usage {
                        assistant.usage = usage
                    }
                    // [T-error-persist-retry-clear-ios] Resolve stale-vs-genuine
                    // persisted errors across a merged agent run. A persisted
                    // error_info always sits on the row that was LAST at stall
                    // time; if further assistant rows follow, the loop provably
                    // progressed past that error (a retry succeeded), so the
                    // banner is stale — clear it rather than showing a red error
                    // mid-way through a visibly completed conversation (observed
                    // on device: DeepSeek stream stalled silently, watchdog
                    // persisted the error, retry() resumed and finished, reload
                    // re-materialised the banner from the orphaned row). A
                    // NEWER row's error wins in the other direction: a run that
                    // genuinely ends in a stall keeps its banner because the
                    // final row carries it (this also fixes the inverse bug
                    // where a mid-run continuation row's error was silently
                    // dropped by this merge and the banner vanished on reload).
                    if continuation.error != nil {
                        logger.info("[ErrorPersist] reload: carrying error from so=\(raw.sortOrder) onto merged message")
                        assistant.error = continuation.error
                    } else if assistant.error != nil {
                        logger.info("[ErrorPersist] reload: clearing stale mid-run error at so=\(raw.sortOrder) — later assistant rows exist")
                        assistant.error = nil
                    }
                    assistant.lastSourceSortOrder = raw.sortOrder
                } else {
                    // First assistant message in this turn
                    let msg = raw.toChatMessage(mediaResolver: resolver, showThinking: showThinking)
                    msg.sourceSortOrder = raw.sortOrder
                    msg.lastSourceSortOrder = raw.sortOrder
                    currentAssistant = msg
                    loadedUIMessages.append(msg)
                }
                continue
            }

            // Real user message — finalize any pending assistant and start fresh
            if currentAssistant != nil {
                let chainBreakBlocks = currentAssistant!.blocks.count
                // [T-log-noise-privacy 2026-07-18] Log the tail LENGTH, not the
                // tail text — message content must not land in log files.
                let lastTextLen = currentAssistant!.blocks.last(where: { $0.kind == .text })?.content.count ?? -1
                logger.info("[BlocksLost] MERGE-CHAIN-BREAK at so=\(raw.sortOrder) userParts=\(raw.parts.count) isToolResultOnly=\(raw.isToolResultOnly) prevAssistantBlocks=\(chainBreakBlocks) prevLastTextLen=\(lastTextLen) sid=\(sessionId.prefix(8))")
            }
            currentAssistant = nil
            let msg = raw.toChatMessage(mediaResolver: resolver, showThinking: showThinking)
            if msg.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               msg.attachments.isEmpty,
               msg.blocks.isEmpty {
                continue
            }
            msg.sourceSortOrder = raw.sortOrder
            msg.lastSourceSortOrder = raw.sortOrder
            loadedUIMessages.append(msg)
        }

        // [BlocksLost] MERGE TRACE — log every text block of the last assistant
        // message so we can confirm whether the final 839-char (or whatever)
        // block actually made it through the merge.
        let assistantMsgCount = loadedUIMessages.filter { $0.role == .assistant }.count
        logger.info("[BlocksLost] MERGE totalUIMessages=\(loadedUIMessages.count) assistantMessages=\(assistantMsgCount) sid=\(sessionId.prefix(8))")
        // [T-log-noise-privacy 2026-07-18] Per-assistant / per-block trace rows
        // demoted to debug (they fire for EVERY assistant message on EVERY
        // session load — hundreds of info lines for a long session), and the
        // block descriptors carry lengths only: the old head/tail excerpts put
        // 40 chars of message content per block into the log files.
        for (aIdx, aMsg) in loadedUIMessages.enumerated() where aMsg.role == .assistant {
            let textBlocks = aMsg.blocks.enumerated().compactMap { (i, b) -> String? in
                guard b.kind == .text else { return nil }
                return "[\(i)] len=\(b.content.count)"
            }
            let toolBlocks = aMsg.blocks.filter { $0.kind != .text && $0.kind != .thinking }.count
            let isLast = aIdx == loadedUIMessages.lastIndex(where: { $0.role == .assistant })
            logger.debug("[BlocksLost] MERGE assistant[\(aIdx)] totalBlocks=\(aMsg.blocks.count) textBlocks=\(textBlocks.count) toolBlocks=\(toolBlocks) isLast=\(isLast) srcSO=\(aMsg.sourceSortOrder ?? -1)...\(aMsg.lastSourceSortOrder ?? -1) blocks=[\(textBlocks.joined(separator: " "))]")
        }

        // Finalize any tool blocks still seeded as .running / .streaming — these
        // are orphaned tool_uses whose tool_result user message was never
        // persisted (app killed / run cancelled mid-flight). Mark them .cancelled
        // so the UI no longer shows a spinner forever.
        for uiMsg in loadedUIMessages {
            for block in uiMsg.blocks {
                guard let status = block.toolStatus else { continue }
                switch status {
                case .running, .streaming:
                    block.toolStatus = .cancelled
                default:
                    break
                }
            }
        }

        let phase2aElapsed = (CFAbsoluteTimeGetCurrent() - phase2aStart) * 1000

        // Phase 2.5 (Phase B): Apply compact marker if present.
        //
        // New model: marker.firstKeptMessageId is the authoritative boundary. agentHistory
        // is NOT trimmed — it stays full and summary injection is moved to effectiveAgentHistory()
        // at inference time. Here we only:
        //   1. Locate the UI boundary for the divider
        //   2. Gray all UI messages before the divider
        //
        // Fallback chain for legacy markers (no firstKeptMessageId):
        //   firstKeptMessageId → boundaryMessageId → firstKeptSortOrder
        let compactMarker = remoteDeviceId == nil ? await ChatStore.shared.latestCompactMarker(sessionId: sessionId) : nil
        self.cachedLatestMarker = compactMarker
        if let marker = compactMarker {
            logger.info("[Compact] ━━━ Phase 2.5: restore (Phase B id-first) ━━━")
            logger.info("[Compact] marker: id=\(marker.id.prefix(8)) fkmId=\(marker.firstKeptMessageId?.prefix(8) ?? "nil") bmId=\(marker.boundaryMessageId?.prefix(8) ?? "nil") lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil") fkSO=\(marker.firstKeptSortOrder) compactedCount=\(marker.compactedCount) summary=\(marker.summary.count)chars createdAt=\(marker.createdAt)")
            logger.info("[Compact] DB: totalRaw=\(rawMessages.count) range=\(rawMessages.first?.sortOrder ?? -1)...\(rawMessages.last?.sortOrder ?? -1)")
            logger.info("[Compact] Memory: history=\(loadedHistory.count) uiMessages=\(loadedUIMessages.count)")
            // Diag: locate marker.lcmId in rawMessages so we can see the exact
            // sortOrder that needs to match a UI msg.sourceSortOrder later.
            if let lcmId = marker.lastCompactedMessageId,
               let lcmRaw = rawMessages.first(where: { $0.id == lcmId }) {
                let uiHit = loadedUIMessages.firstIndex(where: { $0.sourceSortOrder == lcmRaw.sortOrder })
                logger.info("[Compact] marker.lcmId → raw.sortOrder=\(lcmRaw.sortOrder) role=\(lcmRaw.role) → UIIdx=\(uiHit.map(String.init) ?? "MISS")")
            } else {
                logger.info("[Compact] marker.lcmId → \(marker.lastCompactedMessageId == nil ? "nil (marker has no lcmId)" : "NOT in rawMessages — orphaned id")")
            }

            // --- Resolve boundary messageId with priority: firstKeptMessageId → boundaryMessageId → sortOrder ---
            //
            // sortOrder fallback is SKIPPED when this is a compactAll-shape
            // marker (both firstKeptMessageId and boundaryMessageId are nil
            // but lastCompactedMessageId is set). For those markers,
            // firstKeptSortOrder was written as a past-end sentinel at
            // compact time — using it as a real boundary today (after the
            // session has grown past that sortOrder) silently points at a
            // coincidental row and bypasses the compactAll/v2 branches that
            // would otherwise heal via createdAt. Spec: compactAll markers
            // are anchored by lcmId alone.
            let isCompactAllShape = marker.firstKeptMessageId == nil
                && marker.boundaryMessageId == nil
                && marker.lastCompactedMessageId != nil
            var boundaryMsgId: String? = marker.firstKeptMessageId ?? marker.boundaryMessageId
            var boundaryResolutionPath: String = "none"
            if let mid = boundaryMsgId, rawMessages.contains(where: { $0.id == mid }) {
                boundaryResolutionPath = (marker.firstKeptMessageId == mid) ? "firstKeptMessageId" : "boundaryMessageId"
            } else if isCompactAllShape {
                // Leave boundaryMsgId nil so the compactAll branch (which
                // resolves via lcmId + createdAt heal) takes over.
                boundaryResolutionPath = "compactAll(skipLegacySortOrder)"
            } else {
                // Legacy fallback: use sort_order. This path is best-effort on broken data.
                boundaryMsgId = rawMessages.first(where: { $0.sortOrder >= marker.firstKeptSortOrder })?.id
                if boundaryMsgId != nil {
                    boundaryResolutionPath = "firstKeptSortOrder(legacy)"
                } else {
                    boundaryResolutionPath = "failed"
                }
            }
            logger.info("[Compact] boundary resolution path=\(boundaryResolutionPath) resolvedMsgId=\(boundaryMsgId?.prefix(8) ?? "nil")")

            // --- UI graying: locate divider position. ---
            //
            // Resolution order:
            //   1) compactAll marker (no firstKept/boundaryMessageId): use
            //      lastCompactedMessageId. The kept tail (last N user turns +
            //      tool/assistant follow-ups) sits AFTER the divider; only
            //      messages up to and including lastCompactedMessageId are
            //      grayed. If lcmId is missing (legacy marker), fall back to
            //      "gray everything" so behavior matches old builds.
            //   2) compactBefore marker (firstKept/boundaryMessageId set):
            //      divider goes BEFORE that boundary message (boundary is
            //      first kept/active).
            //   3) Total failure: divider at top, no graying.
            let insertIdx: Int

            // ─── v2 markers (id-only model) ───────────────────────────
            // Single source of truth: lastCompactedMessageId. Divider
            // sits AFTER that UI row; everything before is grayed; the
            // active anchor + new messages live below.
            if marker.version >= 2 {
                // Match by INCLUSIVE source-sort-order range. Phase 2 may have
                // folded multiple raw assistant continuation rows into a
                // single UI message — its sourceSortOrder is the FIRST raw's
                // sortOrder, lastSourceSortOrder is the LAST. The marker's
                // anchor can sit anywhere inside that range and still belongs
                // to that UI message.
                let lcmRaw = marker.lastCompactedMessageId.flatMap { id in rawMessages.first(where: { $0.id == id }) }
                if let lcmId = marker.lastCompactedMessageId, let lcmRaw,
                   let lcmUIIdx = Self.uiIndexForAnchorRaw(lcmRaw, in: loadedUIMessages) {
                    insertIdx = lcmUIIdx + 1
                    boundaryResolutionPath = "v2(lcmId)"
                    logger.info("[Compact] Phase2.5 v2 OK: divider after UIIdx=\(lcmUIIdx) (lcmId=\(lcmId.prefix(8)) raw.sortOrder=\(lcmRaw.sortOrder), UI range=[\(loadedUIMessages[lcmUIIdx].sourceSortOrder ?? -1)…\(loadedUIMessages[lcmUIIdx].lastSourceSortOrder ?? -1)]) → insertIdx=\(insertIdx)/uiCount=\(loadedUIMessages.count)")
                } else if lcmRaw == nil {
                    // lcmId genuinely orphaned (id not in rawMessages at all).
                    // Heal by createdAt. We deliberately do NOT heal when
                    // lcmRaw is present but UI failed to map it — that's a
                    // UI-fold edge case, not a corrupt marker, and rewriting
                    // would silently shrink the grayed range.
                    let historyDbIds = Set(loadedHistory.compactMap { $0.dbMessageId })
                    let healAnchor = Self.anchorByCreatedAt(in: rawMessages, markerCreatedAt: marker.createdAt, historyDbIds: historyDbIds)
                    let healUIIdx = healAnchor.flatMap { Self.uiIndexForAnchorRaw($0, in: loadedUIMessages) }
                    logger.info("[Compact] Phase2.5 v2 self-heal attempt: rawCount=\(rawMessages.count) historyDbIdsCount=\(historyDbIds.count) markerCreatedAt=\(marker.createdAt) → healAnchor=\(healAnchor?.id.prefix(8) ?? "nil") (sortOrder=\(healAnchor?.sortOrder.description ?? "nil"), createdAt=\(healAnchor?.createdAt.description ?? "nil")) → healUIIdx=\(healUIIdx?.description ?? "nil")")
                    if let healAnchor, let healUIIdx {
                        insertIdx = healUIIdx + 1
                        boundaryResolutionPath = "v2(orphan→healByCreatedAt)"
                        logger.warning("[Compact] Phase2.5 v2 self-heal: orphaned lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil") → newAnchor=\(healAnchor.id.prefix(8)) (sortOrder=\(healAnchor.sortOrder)) → UIIdx=\(healUIIdx) insertIdx=\(insertIdx)")
                        let healed = Self.rewriteMarkerForHeal(marker, newAnchor: healAnchor, lastRaw: rawMessages.last)
                        await ChatStore.shared.updateCompactMarker(healed)
                        self.cachedLatestMarker = healed
                        logger.info("[Compact] Phase2.5 v2 self-heal: marker rewritten id=\(marker.id.prefix(8)) lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil")→\(healAnchor.id.prefix(8)) version=\(marker.version)→2")
                    } else {
                        // createdAt fallback failed (no qualifying raw predates
                        // the marker). Place divider at top, no graying.
                        logger.warning("[Compact] Phase2.5 v2 unresolved (heal failed): marker.id=\(marker.id.prefix(8)) lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil") healAnchor=\(healAnchor?.id.prefix(8) ?? "nil") healUIIdx=\(healUIIdx?.description ?? "nil") — placing divider at top, no graying (use revert to clear)")
                        insertIdx = 0
                        boundaryResolutionPath = "v2(unresolved→noGray)"
                    }
                } else {
                    // lcmRaw present but UI failed to map it (UI-fold edge
                    // case, not a corrupt marker). Don't rewrite — that would
                    // silently shrink grayed range. Place divider at top.
                    logger.warning("[Compact] Phase2.5 v2 unresolved (UI miss with lcmRaw present): marker.id=\(marker.id.prefix(8)) lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil") raw.sortOrder=\(lcmRaw?.sortOrder ?? -1) uiCount=\(loadedUIMessages.count) — placing divider at top, no graying")
                    insertIdx = 0
                    boundaryResolutionPath = "v2(uiMiss→noGray)"
                }
            } else if boundaryMsgId == nil && marker.firstKeptMessageId == nil && marker.boundaryMessageId == nil {
                // compactAll marker — use lastCompactedMessageId for cutoff.
                if let lcmId = marker.lastCompactedMessageId,
                   let lcmRaw = rawMessages.first(where: { $0.id == lcmId }),
                   let lcmUIIdx = loadedUIMessages.firstIndex(where: { $0.sourceSortOrder == lcmRaw.sortOrder }) {
                    insertIdx = lcmUIIdx + 1
                    boundaryResolutionPath = "compactAll(lastCompactedMessageId)"
                    logger.info("[Compact] Phase2.5 compactAll OK: divider after UIIdx=\(lcmUIIdx) (lcmId=\(lcmId.prefix(8)) raw.sortOrder=\(lcmRaw.sortOrder)) → insertIdx=\(insertIdx)/uiCount=\(loadedUIMessages.count)")
                } else {
                    // Diagnostic: explain WHICH stage of resolution failed.
                    // Used to silently slide divider to messages.count, manifesting
                    // as "divider drifted to bottom + every UI message grayed."
                    // Real-world cause (M-team session, marker created 2026-05-06):
                    // marker.lcmId pointed at a row id that no longer exists in DB
                    // — the underlying messages were re-indexed by a v1→v2 sync
                    // migration, leaving the marker's lcmId orphaned.
                    let lcmIdStr = marker.lastCompactedMessageId?.prefix(8) ?? "nil"
                    let rawHit = marker.lastCompactedMessageId.flatMap { id in rawMessages.first(where: { $0.id == id }) }
                    let stage: String
                    if marker.lastCompactedMessageId == nil {
                        stage = "marker.lcmId is nil"
                    } else if rawHit == nil {
                        stage = "lcmId not in rawMessages(count=\(rawMessages.count))"
                    } else {
                        stage = "lcmId raw.sortOrder=\(rawHit!.sortOrder) not matched by any UI msg.sourceSortOrder (loadedUI.count=\(loadedUIMessages.count))"
                    }
                    let uiTail = loadedUIMessages.suffix(5).enumerated().map { (off, m) -> String in
                        let abs = loadedUIMessages.count - 5 + off
                        return "[\(abs)] role=\(m.role) sortOrder=\(m.sourceSortOrder.map(String.init) ?? "nil")"
                    }.joined(separator: " | ")
                    logger.warning("[Compact] Phase2.5 compactAll DIAG: marker.id=\(marker.id.prefix(8)) lcmId=\(lcmIdStr) reason=\(stage) uiTail5=\(uiTail)")

                    // Self-heal: recompute anchor by marker.createdAt — the
                    // last raw message that predates the marker is the natural
                    // replacement for the orphaned lcmId. On success, rewrite
                    // the marker and upgrade to v2 so subsequent loads /
                    // effectiveAgentHistory() resolve through the corrected id
                    // without recomputing. Avoids the marker-position drift
                    // bug where indexOfNthFromLastUserText slides forward as
                    // new messages arrive.
                    //
                    // Anchor candidate is filtered to raw rows whose id is
                    // present as a dbMessageId in agentHistory — otherwise the
                    // rewrite would be cosmetic (effectiveAgentHistory still
                    // can't resolve the new lcmId and stays in degraded mode).
                    let historyDbIds = Set(loadedHistory.compactMap { $0.dbMessageId })
                    let healAnchor = Self.anchorByCreatedAt(in: rawMessages, markerCreatedAt: marker.createdAt, historyDbIds: historyDbIds)
                    let healUIIdx = healAnchor.flatMap { Self.uiIndexForAnchorRaw($0, in: loadedUIMessages) }
                    logger.info("[Compact] Phase2.5 compactAll self-heal attempt: rawCount=\(rawMessages.count) historyDbIdsCount=\(historyDbIds.count) markerCreatedAt=\(marker.createdAt) → healAnchor=\(healAnchor?.id.prefix(8) ?? "nil") (sortOrder=\(healAnchor?.sortOrder.description ?? "nil"), createdAt=\(healAnchor?.createdAt.description ?? "nil")) → healUIIdx=\(healUIIdx?.description ?? "nil")")
                    if let healAnchor, let healUIIdx {
                        insertIdx = healUIIdx + 1
                        boundaryResolutionPath = "compactAll(orphanedLcm→healByCreatedAt)"
                        logger.warning("[Compact] Phase2.5 compactAll self-heal: orphaned lcmId → newAnchor=\(healAnchor.id.prefix(8)) (sortOrder=\(healAnchor.sortOrder)) → UIIdx=\(healUIIdx) insertIdx=\(insertIdx)")
                        let healed = Self.rewriteMarkerForHeal(marker, newAnchor: healAnchor, lastRaw: rawMessages.last)
                        await ChatStore.shared.updateCompactMarker(healed)
                        self.cachedLatestMarker = healed
                        logger.info("[Compact] Phase2.5 compactAll self-heal: marker rewritten id=\(marker.id.prefix(8)) lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil")→\(healAnchor.id.prefix(8)) version=\(marker.version)→2")
                    } else {
                        // createdAt fallback also failed — walk back N user
                        // turns as last resort. This is the legacy behavior
                        // that drifts on every send but at least keeps SOME
                        // active tail rather than graying everything.
                        let anchor = Self.uiAnchorIndexForKeptTail(
                            in: loadedUIMessages,
                            keepUserTurns: Self.compactKeepRecentUserTurns
                        )
                        insertIdx = anchor
                        boundaryResolutionPath = "compactAll(orphanedLcm→tailAnchor)"
                        logger.warning("[Compact] Phase2.5 compactAll fallback: divider before UIIdx=\(anchor) (kept=\(loadedUIMessages.count - anchor) tail messages active, \(anchor) grayed)")
                    }
                }
            } else if let mid = boundaryMsgId,
               let bmRaw = rawMessages.first(where: { $0.id == mid }),
               let bmUIIdx = loadedUIMessages.firstIndex(where: { $0.sourceSortOrder == bmRaw.sortOrder }) {
                insertIdx = bmUIIdx  // divider BEFORE boundary (boundary message is first active)
            } else {
                // Total resolution failure: don't gray anything, but still show divider at top for the summary.
                logger.warning("[Compact] UI boundary unresolved; placing divider at top. This marker will not render a grayed zone.")
                insertIdx = 0
            }

            let compactedUICount = insertIdx
            logger.info("[Compact] UI: insertIdx=\(insertIdx) compactedUI=\(compactedUICount) totalUI=\(loadedUIMessages.count)")

            let divider = ChatMessage(role: .compactDivider, content: "\(compactedUICount) messages compacted")
            divider.compactSummary = marker.summary
            loadedUIMessages.insert(divider, at: insertIdx)
            for i in 0..<insertIdx {
                if loadedUIMessages[i].role != .systemInfo {
                    loadedUIMessages[i].isCompactedHistory = true
                }
            }

            let keptUICount = loadedUIMessages.count - insertIdx - 1
            logger.info("[Compact] History (Phase B): full-retain, loadedHistory=\(loadedHistory.count) (no trim, no summary injection)")
            logger.info("[Compact] ━━━ Phase 2.5 DONE: \(compactedUICount) compacted + divider + \(keptUICount) active ━━━")
        }

        // Defer assigning messages until after Phase 3 (tool snapshots) so the
        // UI never sees messages without their corresponding snapshot data.
        agentHistory = loadedHistory

        // [T-ios-unread-dot-reappears] Opening the session means the user has
        // now seen every completed assistant turn in it, so align the unread
        // badge baseline to the current assistant-turn count. Without this, a
        // late/duplicate endBackgroundProcessing() after read (baseline still
        // at its -1 init) would see turns > -1 and re-badge with no new content.
        if activateSession {
            lastBadgedAssistantTurnCount = loadedHistory.reduce(0) { $0 + ($1.role == .assistant ? 1 : 0) }
        }

        let phase25Elapsed = (CFAbsoluteTimeGetCurrent() - phase2aStart) * 1000 - phase2aElapsed
        let phase2sigStart = CFAbsoluteTimeGetCurrent()

        // Restore Gemini thought signatures from persisted tool calls
        pendingThoughtSignatures = [:]
        for raw in rawMessages {
            for part in raw.parts {
                if case .toolUse(let tu) = part, let sig = tu.thoughtSignature {
                    pendingThoughtSignatures[tu.toolUseId] = ToolCallMetadata(thoughtSignature: sig)
                }
            }
        }

        let phase2sigElapsed = (CFAbsoluteTimeGetCurrent() - phase2sigStart) * 1000

        // Phase 2b: Pre-cache NSAttributedString for all completed text blocks.
        // This ensures the first sizeThatFits call returns the correct height,
        // eliminating the estimated→actual contentSize oscillation that causes
        // scroll jitter when cells first enter the viewport.
        for msg in loadedUIMessages where msg.role == .assistant {
            for block in msg.blocks where block.kind == .text && !block.content.isEmpty {
                cacheAttributedString(for: block)
            }
        }

        let phase2bElapsed = (CFAbsoluteTimeGetCurrent() - buildStart) * 1000 - phase2aElapsed - phase25Elapsed - phase2sigElapsed
        let buildElapsed = (CFAbsoluteTimeGetCurrent() - buildStart) * 1000
        let restoredEstimate = estimateContextTokens()
        logger.info("[SessionLoad] \(sessionId) — Phase 2 build messages: \(String(format: "%.1f", buildElapsed))ms (\(loadedUIMessages.count) UI messages, \(loadedHistory.count) history entries, estimated ~\(restoredEstimate) context tokens)")
        logger.info("[SessionLoad] \(sessionId) — Phase 2 breakdown: [2a iterate: \(String(format: "%.1f", phase2aElapsed)) | 2.5 compact: \(String(format: "%.1f", phase25Elapsed)) | 2sig thoughts: \(String(format: "%.1f", phase2sigElapsed)) | 2b attrStr: \(String(format: "%.1f", phase2bElapsed))]ms")

        // Phase 3: Extract tool snapshots
        let snapshotStart = CFAbsoluteTimeGetCurrent()
        var loadedSnapshots: [ToolSnapshotItem] = []
        for raw in rawMessages {
            for part in raw.parts {
                if case .toolResult(let tr) = part, let snap = tr.snapshot {
                    let toolName = Self.findToolName(for: tr.toolUseId, in: rawMessages)
                    // For file_write: ensure snapshot contains file content, not just tool result
                    let finalSnap: ToolSnapshot
                    if toolName == "file_write",
                       let text = snap.text, text.hasPrefix("Wrote to ") || text.hasPrefix("Appended to ") {
                        // Old-format snapshot — recover file content from ToolUse input or disk
                        let fileContent = Self.recoverFileWriteContent(for: tr.toolUseId, in: rawMessages)
                        if let fileContent, !fileContent.isEmpty {
                            let lines = fileContent.components(separatedBy: "\n")
                            let preview = lines.prefix(200).joined(separator: "\n")
                            finalSnap = ToolSnapshot(type: .text, text: preview, mediaRef: nil, duration: snap.duration)
                        } else {
                            finalSnap = snap
                        }
                    } else {
                        finalSnap = snap
                    }
                    loadedSnapshots.append(ToolSnapshotItem(
                        id: tr.toolUseId, toolName: toolName, snapshot: finalSnap, mediaResolver: resolver
                    ))
                }
            }
        }
        // Assign snapshots and messages together so the UI renders with
        // complete data — prevents tables/images from rendering without snapshots.
        toolSnapshots = loadedSnapshots
        messages = loadedUIMessages

        // [T-ios-scroll-suspend-leak] UI/DB consistency snapshot. `rawMessages` is
        // the source of truth (one row per persisted turn/iteration); `messages`
        // is the merged UI view (tool-result rows fold into their assistant turn,
        // so UI count is normally ≤ raw count). Log both plus the last UI message's
        // preview so a "UI shows old content but DB is fresh" report can be pinned
        // to whether loadSession itself under-loaded, vs the snapshot never flushing.
        // [T-log-noise-privacy 2026-07-18] Length instead of a 40-char content
        // preview — the consistency check only needs "is the last message the
        // fresh one", which the length + counts answer without putting user
        // text into the log files.
        let lastTextLen = loadedUIMessages.last.map { m in
            m.blocks.compactMap { b -> String? in
                if case .text = b.kind { return b.content }; return nil
            }.joined(separator: " ").count
        } ?? -1
        logger.info("[SessionLoad] DB raw=\(rawMessages.count) → VM messages=\(loadedUIMessages.count) suspended=\(self.streamingUIUpdatesSuspended) lastTextLen=\(lastTextLen)")

        if let lastAssistant = loadedUIMessages.last(where: { $0.role == .assistant }) {
            let lastTextBlock = lastAssistant.blocks.last(where: { $0.kind == .text })
            let lastRawSO = rawMessages.last(where: { $0.role == .assistant })?.sortOrder ?? -1
            let lastRawTextLen = rawMessages.last(where: { $0.role == .assistant })?.parts.compactMap {
                if case .text(let s) = $0 { return s }; return nil
            }.joined().count ?? 0
            // [T-log-noise-privacy 2026-07-18] Tail excerpt dropped — the
            // UI-vs-raw length pair already answers "did the final block
            // survive the merge" without logging message content.
            logger.info("[BlocksLost] POST-ASSIGN lastAssistant totalBlocks=\(lastAssistant.blocks.count) lastTextLen=\(lastTextBlock?.content.count ?? -1) lastRawSO=\(lastRawSO) lastRawTextLen=\(lastRawTextLen) sid=\(sessionId.prefix(8))")
        }

        // [T-ios-token-usage-reload-per-iteration] Rebuild the session token
        // accumulators from EVERY persisted assistant turn (rawMessages), not
        // the merged UI messages. One agent loop persists a separate assistant
        // row per iteration (each with its own tokenUsage), but the UI build
        // above MERGES those iterations into a single ChatMessage and OVERWRITES
        // `.usage` with only the last iteration's value (see the merge at
        // `assistant.usage = usage`). Summing loadedUIMessages therefore counted
        // just the final iteration of each turn — e.g. a 30-iteration session
        // whose per-turn cache_read is ~46k reported only ~tens of k instead of
        // the billed cumulative total. The Token Usage sheet uses billing
        // semantics (every iteration is charged), so sum the raw per-iteration
        // usage. Runtime totals were already correct via the per-iteration `+=`.
        // (Accumulators were already zeroed above, before the empty-session guard.)
        for raw in rawMessages where raw.role == .assistant {
            if let u = raw.tokenUsage {
                sessionInputTokens += u.inputTokens
                sessionOutputTokens += u.outputTokens
                sessionCacheReadTokens += u.cacheReadTokens
                sessionCacheWriteTokens += u.cacheCreationTokens
            }
        }

        let snapshotElapsed = (CFAbsoluteTimeGetCurrent() - snapshotStart) * 1000
        logger.info("[SessionLoad] \(sessionId) — Phase 3 tool snapshots: \(String(format: "%.1f", snapshotElapsed))ms (\(loadedSnapshots.count) snapshots)")

        // Phase 4: Migrate offloads + mount minis
        let mountStart = CFAbsoluteTimeGetCurrent()
        migrateOffloadsIfNeeded(for: sessionId)
        let migrateElapsed = (CFAbsoluteTimeGetCurrent() - mountStart) * 1000
        logger.info("🔍MOUNT loadSession Phase4 calling mountMinis for \(sessionId) (msgs=\(rawMessages.count))")
        mountMinis(for: sessionId)
        let mountElapsed = (CFAbsoluteTimeGetCurrent() - mountStart) * 1000
        logger.info("🔍MOUNT loadSession Phase4 done in \(String(format: "%.1f", mountElapsed))ms")

        // If the session already has a title, skip further generation attempts.
        // Also cache `session.modelId` so resolveCurrentEntry can fall back to
        // the session's persisted model when no in-memory binding exists
        // (e.g. iCloud-synced sessions where SessionModelBinding doesn't carry
        // across devices). Without this, compact / title gen silently jumps
        // to the global default group, running on a different model than the
        // one the chat header is showing.
        if let session = await ChatStore.shared.getSession(sessionId) {
            if session.title != nil {
                titleGenAttempts = 3 // max out so no more attempts
            }
            cachedSessionModelId = session.modelId
        }

        // iCloud-synced sessions never went through ensureSession() on this
        // device, so createInitialBinding() was never called and the local
        // store has no SessionModelBinding for them. Without a binding, the
        // resolver falls into step 2 (cachedSessionModelId lookup), which can
        // pick an entry that lives outside the default group — observed bug:
        // synced session routes to Anthropic(wsvn63) whose OAuth token is
        // org-blocked, even though Default Model group's only sonnet-4-6
        // member is Anthropic(53) (and that's what the UI subtitle showed).
        //
        // Build the binding the same way ensureSession does, so that loading
        // a synced session converges to the exact same state as creating a
        // local one: a group-bound binding pointing into the default primary
        // group. If the cached modelId matches a specific member of the
        // default group, route the binding to THAT member (so synced session
        // sticks to the user-visible model). Otherwise fall back to the
        // group's normal resolve (first available member).
        ensureSessionBindingFromDefaults(sessionId: sessionId)

        // Detect interrupted agent loop:
        // Case A: last history entry is user with all toolResult parts → tools completed, next model call never happened
        // Case B: last history entry is assistant with toolUse parts → model called tools, but they never executed
        // Case C: last history entry is user with synthetic "Continue" message → text streaming was cancelled
        recheckCanResumeFromHistory()

        let totalElapsed = (CFAbsoluteTimeGetCurrent() - loadStart) * 1000
        let totalSinceAppear = (CFAbsoluteTimeGetCurrent() - Self.onAppearTimestamp) * 1000
        logger.info("[SessionLoad] \(sessionId) — TOTAL: \(String(format: "%.1f", totalElapsed))ms T+\(String(format: "%.0f", totalSinceAppear))ms [DB: \(String(format: "%.1f", dbElapsed)) | Build: \(String(format: "%.1f", buildElapsed)) | Snapshots: \(String(format: "%.1f", snapshotElapsed)) | Mount: \(String(format: "%.1f", mountElapsed))]")
    }

    /// [T-ios-paused-badge-desync] Lightweight interrupted-loop tail check.
    /// Called from both `loadSession()` (full reload) and the SKIP-LOAD branch
    /// (cached VM re-enter) so the detail page's canResume/banner always
    /// matches the list's .paused badge.
    private func recheckCanResumeFromHistory() {
        guard let sessionId, !isProcessing, let lastEntry = agentHistory.last else { return }
        let isInterrupted: Bool
        if lastEntry.role == .user {
            let allToolResults = !lastEntry.parts.isEmpty && lastEntry.parts.allSatisfy {
                if case .toolResult = $0 { return true }; return false
            }
            let isContinueMessage = lastEntry.parts.count == 1 && {
                if case .text(let t) = lastEntry.parts.first,
                   t.contains("The user stopped the previous response") {
                    return true
                }
                return false
            }()
            // [T-ios-orphan-user-tail GH#262/#263] Third interrupted shape: a
            // plain-text user turn with NO reply after it at all.
            //
            // How it is produced: `send()` persists the user turn BEFORE the
            // request goes out (AIChatViewModel ~2645). If the process dies
            // between that write and the reply landing — iOS reclaiming a
            // backgrounded app is the reported case, `BG task grants: 0` — the
            // assistant side never reaches the store. And it cannot be
            // reconstructed later, because ChatStore.appendMessages drops any
            // assistant row with no echoable content (the ~2633 filter that
            // exists to avoid `400 content or tool_calls must be set`). The
            // same filter also swallows persistErrorInfo's empty-parts carrier
            // row, so an early network failure lands here too.
            //
            // The result is a tail that looks finished but never got a reply,
            // and — before this case existed — reported canResume=false: no
            // PAUSED badge, no Resume, and `retry()` bailing at its own
            // "last must be assistant" guard. The session had NO recovery
            // affordance at all and the user could only start a new chat.
            //
            // Deliberately the LAST clause: the two shapes above describe a
            // turn that was mid-flight, this one describes a turn that never
            // started. Ordering keeps their (more specific) logging and
            // semantics intact.
            //
            // False-positive safety — this must never fire on a turn that is
            // simply still waiting. Four independent gates already hold:
            //   1. `!isProcessing` (this function's guard) — send() sets it
            //      true at ~2424, BEFORE persisting the user row at ~2645, and
            //      only clears it in the task epilogue, so the entire in-flight
            //      window is excluded in-process.
            //   2. `SessionActivityTracker.isActive` (checked below) — covers a
            //      second VM observing a session another VM is driving.
            //   3. This function only runs from loadSession() / the SKIP-LOAD
            //      re-enter — never mid-stream.
            //   4. The list cell re-checks `!vm.isProcessing && !trackerActive`
            //      before drawing the banner (CollectionViewMessageListV3 ~1462).
            // A cold start after a kill satisfies all four precisely because
            // the process that was processing no longer exists.
            let isUnansweredUserTurn = !allToolResults && !isContinueMessage
            isInterrupted = allToolResults || isContinueMessage || isUnansweredUserTurn
        } else if lastEntry.role == .assistant {
            let hasToolUse = lastEntry.parts.contains {
                if case .toolUse = $0 { return true }; return false
            }
            isInterrupted = hasToolUse
        } else {
            isInterrupted = false
        }
        if isInterrupted {
            // [T-ios-session-status-mismatch] Cross-check the tracker before
            // declaring "interrupted" — a live loop's DB tail looks identical
            // to a stopped one.
            if SessionActivityTracker.shared.isActive(sessionId) {
                logger.info("[SessionLoad] \(sessionId.prefix(8)) — tail looks interrupted but tracker says active, skipping canResume=true")
            } else if !canResume {
                // [T-ios-group-pause-badge-restamp] This is a RE-DETECTION of an
                // interruption that already happened (possibly days ago) — the
                // persisted tail still looks unfinished. It is not a new entry
                // into the paused state, so the badge must keep its original
                // entry timestamp; otherwise merely opening or cold-start
                // scanning an old chat resets the group card's 24h freshness
                // window and a long-stale pause flags its group forever.
                isRedetectingInterruptedTail = true
                defer { isRedetectingInterruptedTail = false }
                canResume = true
                if let lastAssistant = messages.last, lastAssistant.role == .assistant {
                    self.committedBlockCount = lastAssistant.blocks.count
                }
                logger.info("[SessionLoad] \(sessionId.prefix(8)) — detected interrupted agent loop, canResume=true, committedBlocks=\(self.committedBlockCount)")
            }
        } else if canResume {
            // Tail no longer looks interrupted (e.g. a normal turn completed
            // while the VM was cached) — clear canResume so the badge drops.
            canResume = false
            logger.info("[SessionLoad] \(sessionId.prefix(8)) — tail NOT interrupted, clearing stale canResume")
        }
    }

    /// Find the tool name for a given toolUseId by scanning all messages for a matching toolUse part.
    private static func findToolName(for toolUseId: String, in messages: [RawMessage]) -> String {
        for msg in messages {
            for part in msg.parts {
                if case .toolUse(let tu) = part, tu.toolUseId == toolUseId {
                    return tu.name
                }
            }
        }
        return "tool"
    }

    /// Recover original file content for a file_write tool from persisted ToolUse input.
    private static func recoverFileWriteContent(for toolUseId: String, in messages: [RawMessage]) -> String? {
        for msg in messages {
            for part in msg.parts {
                if case .toolUse(let tu) = part, tu.toolUseId == toolUseId, tu.name == "file_write" {
                    guard let data = tu.input.data(using: .utf8),
                          let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                          let content = dict["content"] as? String else { return nil }
                    return content
                }
            }
        }
        return nil
    }

    /// Apply tool results from a tool-result RawMessage onto an assistant ChatMessage's tool blocks.
    private static func applyToolResults(from raw: RawMessage, to assistant: ChatMessage, mediaResolver: (MediaRef) -> URL) {
        let toolResults: [ToolResult] = raw.parts.compactMap {
            if case .toolResult(let tr) = $0 { return tr }
            return nil
        }

        // Find unapplied tool blocks (those still showing placeholder content)
        var resultIdx = 0
        for block in assistant.blocks {
            guard resultIdx < toolResults.count else { break }

            let isToolBlock: Bool
            switch block.kind {
            case .shellTool, .fileReadTool, .fileWriteTool, .fileEditTool, .browserTool, .readImageTool, .memoryTool:
                isToolBlock = true
            default:
                isToolBlock = false
            }
            guard isToolBlock else { continue }

            // A tool block "needs update" when its status is still a non-terminal
            // seed (running / streaming) set by toChatMessage for a tool_use whose
            // tool_result hasn't been applied yet. Terminal statuses (.success,
            // .failed, .cancelled) mean the block was already finalized by an
            // earlier apply pass and must not be overwritten.
            let needsUpdate: Bool = {
                guard let status = block.toolStatus else { return false }
                switch status {
                case .running, .streaming: return true
                case .success, .failed, .cancelled: return false
                }
            }()
            guard needsUpdate else { continue }

            let tr = toolResults[resultIdx]
            resultIdx += 1

            switch block.kind {
            case .shellTool(let cmd):
                block.content = tr.output
            case .browserTool, .readImageTool:
                block.content = tr.output
                if let ref = tr.mediaRef {
                    block.imageFilePath = mediaResolver(ref).path
                }
            default:
                block.content = tr.output
            }
            // Prefer the explicit persisted status (distinguishes cancelled from failed);
            // fall back to the success bool for rows written before the status field existed.
            switch tr.status {
            case "cancelled":
                block.toolStatus = .cancelled
            case "failed":
                block.toolStatus = .failed(message: tr.output.components(separatedBy: "\n").first ?? "Failed")
            case "success":
                block.toolStatus = .success
            default:
                block.toolStatus = tr.success
                    ? .success
                    : .failed(message: tr.output.components(separatedBy: "\n").first ?? "Failed")
            }

            // [T-tool-duration-restore] Restore the capsule's execution time from
            // the persisted snapshot. The live path stores it on the block
            // (AIChatViewModel+ConcurrentTools), and ToolSnapshot.duration is
            // persisted (ChatStore), but reload never copied it back — so the
            // capsule's "耗时" vanished after a session reload. Seed it here, on
            // the same finalize pass that sets status, so it survives reload.
            if let dur = tr.snapshot?.duration {
                block.toolDuration = dur
            }
        }
    }

    /// Lazily create a session on first message send (draft mode).
    func ensureSession() async {
        guard sessionId == nil else { return }
        await ensureSessionReturningId()
    }

    /// Creates a session if needed and returns its ID.
    @discardableResult
    func ensureSessionReturningId(activateSession: Bool = true) async -> String {
        if let sid = sessionId {
            logger.info("🔑DRAFT [vm=\(self.vmInstanceId)] ensureSession already has sessionId=\(sid)")
            return sid
        }
        let model = selectedModel
        let session = await ChatStore.shared.createSession(modelId: model.id, source: sessionSource)
        sessionId = session.id
        if activateSession { Self.activeSessionId = session.id }
        // [T-memory-enabled-new-session-bug] Sync the @Published memoryEnabled
        // to the value createSession just persisted from the global default.
        // A draft VM initializes memoryEnabled = true and never runs
        // loadSession() (it's a fresh session, not a load), so without this
        // the in-memory flag stays true even when the DB row (and the global
        // toggle) say false — the agent loop then injected GLOBAL.md/daily and
        // registered memory_get/memory_write despite the toggle being off.
        // Read it back through getMemoryEnabled so this single source of truth
        // (DB row, which createSession wrote from the global default) drives
        // the flag.
        memoryEnabled = await ChatStore.shared.getMemoryEnabled(sessionId: session.id)
        AppLogger(category: "MemDiag").info("[MemDiag] ensureSession sid=\(session.id.prefix(8)) → vm.memoryEnabled=\(self.memoryEnabled)")
        // Tracker migration: if this vm was marked active under its draftId,
        // swap that entry to the real session id. draftId is kept on the vm
        // itself (sidebar UX still references it until the proxy row is
        // replaced), but the tracker should no longer key off the draftId —
        // otherwise a second send() would re-insert the draftId alongside the
        // real id and setInactive may miss one on the way back.
        //
        // We also register a draftId→realId alias so sidebar rows that still
        // identify themselves by draftId (the `displaySessions` proxy in
        // ContentView.swift keeps the draft tag to preserve List(selection:)
        // across the migration) can still query `isActive(draftId)` and get
        // the current running state of the real session underneath. Alias is
        // cleared in the $isProcessing sink's inactive branch.
        if isProcessing {
            let src = "vm=\(vmInstanceId) ensureSession draft=\(draftId ?? "nil")→real"
            SessionActivityTracker.shared.setActive(session.id, source: src)
            if let did = draftId {
                SessionActivityTracker.shared.setDraftAlias(draft: did, real: session.id)
                SessionActivityTracker.shared.setInactive(did, source: src + " (migrate off draftId)")
            }
        }
        // [T-first-msg-thinking-level-clobber] The user may have set a temporary
        // per-session thinking level before ever sending (a new, not-yet-
        // persisted session). Flush it into the session config NOW, and remember
        // whether it wrote a real override — createInitialBinding below seeds the
        // group's default thinking level, which would otherwise overwrite the
        // user's pick on the very first send.
        let userSetThinkingLevel = flushPendingThinkingLevel()
        // Cache this draft VM now that it has a session ID
        ViewModelCache.shared.cacheDraft(self, sessionId: session.id)
        logger.info("🔑DRAFT [vm=\(self.vmInstanceId)] ensureSession CREATED sessionId=\(session.id) draftId=\(self.draftId ?? "nil")")
        mountMinis(for: session.id)

        // Create initial session binding from default group → last-used → latest
        // provider + latest text model. See createInitialBinding doc for the
        // 3-tier fallback chain.
        await createInitialBinding(for: session.id, preserveThinkingLevel: userSetThinkingLevel)

        // Notify sidebar to replace placeholder with the real session.
        // Include draftId so the receiver can verify this came from the active draft.
        var userInfo: [String: String] = [:]
        if let draftId { userInfo["draftId"] = draftId }
        NotificationCenter.default.post(name: .sessionDidCreate, object: session.id, userInfo: userInfo)

        return session.id
    }

    /// Ensure a SessionModelBinding exists for an already-persisted session
    /// (typically one loaded via iCloud sync). Idempotent: returns immediately
    /// if a binding is already present in the store. When building a new
    /// binding, prefer routing to the default group's member that matches the
    /// session's cached modelId — that way "open a synced session" converges
    /// to exactly the same routing as "switch model and re-pick the same one"
    /// (which is the user-observed workaround for the OAuth-routing bug).
    func ensureSessionBindingFromDefaults(sessionId: String) {
        let store = ProviderConfigStore.shared
        guard store.binding(for: sessionId) == nil else { return }
        guard let groupId = store.defaultPrimaryGroupId,
              let group = store.group(for: groupId) else {
            logger.info("🔀ENSURE binding skip: no default primary group for session=\(sessionId)")
            return
        }

        // Prefer a group member whose model.id matches the session's cached
        // modelId — this is the "synced session sticks to its model" path.
        // Fall back to the group's normal resolve if no match (rare: the
        // model the synced session used isn't in this device's default
        // group anymore).
        var resolvedEntryId: String? = nil
        if !cachedSessionModelId.isEmpty {
            for eid in group.memberEntryIds {
                guard let entry = store.entry(for: eid), !entry.isHidden,
                      entry.model.id == cachedSessionModelId else { continue }
                // [T-disabled-provider-via-group-ios] Don't stick to the cached
                // model if its provider instance has been disabled (or lost its
                // credential). Skipping it here lets the fallback below pick an
                // available member instead of binding to a disabled provider —
                // matching ModelGroupRouter.availableEntryIds (GitHub #34 / #563).
                guard let instance = store.instance(for: entry.providerInstanceId),
                      instance.isEnabled, instance.hasAnyCredential else { continue }
                resolvedEntryId = eid
                break
            }
        }
        if resolvedEntryId == nil {
            resolvedEntryId = ModelGroupRouter.resolve(group: group, sessionId: sessionId, store: store)
        }
        guard let entryId = resolvedEntryId else {
            logger.info("🔀ENSURE binding skip: no resolvable entry in default group for session=\(sessionId)")
            return
        }

        let primarySource = SessionModelSource.group(groupId: groupId, resolvedEntryId: entryId)

        var subSource: SessionModelSource? = nil
        if let subGroupId = store.defaultSubGroupId,
           let subGroup = store.group(for: subGroupId),
           let subEntryId = ModelGroupRouter.resolve(group: subGroup, sessionId: sessionId, store: store) {
            subSource = .group(groupId: subGroupId, resolvedEntryId: subEntryId)
        }

        let binding = SessionModelBinding(
            sessionId: sessionId,
            primarySource: primarySource,
            subModelSource: subSource
        )
        store.setBinding(binding, for: sessionId)
        applyGroupSessionDefaults(group: group, sessionId: sessionId)
        logger.info("🔀ENSURE binding created for synced session=\(sessionId): group=\(groupId) entry=\(entryId) (cachedModelId=\(self.cachedSessionModelId))")
    }

    /// Create a SessionModelBinding for a newly created session.
    ///
    /// [T-newchat-default-model-fallback-ios] Three-tier fallback chain so a
    /// fresh session always lands on a sensible, working model entry even
    /// when the user hasn't configured a default group yet (or the configured
    /// default group is empty):
    ///
    ///   1. **Default group** — if `defaultPrimaryGroupId` is set AND the
    ///      group resolves to at least one available (enabled + credentialed,
    ///      non-hidden) member, use it. Existing behaviour — unchanged.
    ///   2. **Last-used model** — read the most recently updated session via
    ///      `ChatStore.listSessions()` (skipping the one we just created and
    ///      any whose modelId no longer maps to a usable entry on this
    ///      device). Bind to that entry as `.directEntry(...)`. Implicit
    ///      "last-used" — no separate persistence column needed because the
    ///      session row's `modelId` already records the user's choice.
    ///   3. **Latest provider + latest text model** — when no usable
    ///      precedent session exists (clean install with one provider just
    ///      added), pick the newest enabled+credentialed `ProviderInstance`
    ///      (last in `store.instances`), then within that instance pick the
    ///      newest non-hidden text-output model (last in its `modelEntries`
    ///      filter). Image / audio / video output models are excluded so
    ///      "send a message" works out of the box.
    ///
    /// Every decision point logs under `[NewSessionDefault]` so a no-binding
    /// regression can be diagnosed from the daily log alone.
    /// - Parameter preserveThinkingLevel: when true, a per-session thinking
    ///   level the user already set (flushed just before this call) is kept —
    ///   the bound group's default thinking level is NOT seeded over it.
    ///   [T-first-msg-thinking-level-clobber]
    private func createInitialBinding(for sessionId: String, preserveThinkingLevel: Bool = false) async {
        let store = ProviderConfigStore.shared

        // Tier 0 — explicit group from long-press FAB.
        let overrideGroupId = initialGroupId
        // Tier 1 — default group (current behaviour).
        if let groupId = overrideGroupId ?? store.defaultPrimaryGroupId,
           let group = store.group(for: groupId),
           let entryId = ModelGroupRouter.resolve(group: group, sessionId: sessionId, store: store) {
            let primarySource = SessionModelSource.group(groupId: groupId, resolvedEntryId: entryId)

            // Resolve sub source
            var subSource: SessionModelSource? = nil
            if let subGroupId = store.defaultSubGroupId,
               let subGroup = store.group(for: subGroupId),
               let subEntryId = ModelGroupRouter.resolve(group: subGroup, sessionId: sessionId, store: store) {
                subSource = .group(groupId: subGroupId, resolvedEntryId: subEntryId)
            }

            let binding = SessionModelBinding(
                sessionId: sessionId,
                primarySource: primarySource,
                subModelSource: subSource
            )
            store.setBinding(binding, for: sessionId)

            // Apply group session defaults (thinking level + context limit).
            // [T-first-msg-thinking-level-clobber] Skip the thinking-level seed
            // when the user already picked one for this session pre-send.
            applyGroupSessionDefaults(group: group, sessionId: sessionId,
                                      preserveThinkingLevel: preserveThinkingLevel)

            // Fix session model_id to match the actually resolved model (not the hardcoded default)
            if let entry = store.entry(for: entryId) {
                Task { await ChatStore.shared.updateSessionModelId(sessionId, modelId: entry.model.id) }
                let tierTag = overrideGroupId != nil ? "tier=0 longpress-group" : "tier=1 default-group"
                logger.info("[NewSessionDefault] \(tierTag) sid=\(sessionId.prefix(8)) groupId=\(groupId.prefix(8)) entry=\(entryId.prefix(8)) modelId=\(entry.model.id)")
            } else {
                let tierTag = overrideGroupId != nil ? "tier=0 longpress-group" : "tier=1 default-group"
                logger.info("[NewSessionDefault] \(tierTag) sid=\(sessionId.prefix(8)) groupId=\(groupId.prefix(8)) entry=\(entryId.prefix(8)) (entry missing in store)")
            }
            return
        }

        logger.info("[NewSessionDefault] tier=1 default-group MISS sid=\(sessionId.prefix(8)) defaultGroupId=\(store.defaultPrimaryGroupId?.prefix(8) ?? "nil") — falling through to tier=2 last-used")

        // Tier 2 — last-used model: walk recent sessions, take the first
        // whose modelId still maps to a usable entry on this device. Skip
        // the freshly-created session we're binding for.
        if let entry = await Self.resolveLastUsedEntry(excludingSessionId: sessionId, store: store) {
            let source = SessionModelSource.directEntry(modelEntryId: entry.id)
            let binding = SessionModelBinding(
                sessionId: sessionId,
                primarySource: source,
                subModelSource: nil
            )
            store.setBinding(binding, for: sessionId)
            Task { await ChatStore.shared.updateSessionModelId(sessionId, modelId: entry.model.id) }
            logger.info("[NewSessionDefault] tier=2 last-used sid=\(sessionId.prefix(8)) entry=\(entry.id.prefix(8)) modelId=\(entry.model.id) provider=\(entry.providerInstanceId.prefix(8))")
            return
        }

        logger.info("[NewSessionDefault] tier=2 last-used MISS sid=\(sessionId.prefix(8)) — no recent session whose modelId still resolves to a usable entry; falling through to tier=3 latest-provider")

        // Tier 3 — latest provider + latest text-output model.
        if let entry = Self.resolveLatestProviderTextEntry(store: store) {
            let source = SessionModelSource.directEntry(modelEntryId: entry.id)
            let binding = SessionModelBinding(
                sessionId: sessionId,
                primarySource: source,
                subModelSource: nil
            )
            store.setBinding(binding, for: sessionId)
            Task { await ChatStore.shared.updateSessionModelId(sessionId, modelId: entry.model.id) }
            logger.info("[NewSessionDefault] tier=3 latest-provider sid=\(sessionId.prefix(8)) entry=\(entry.id.prefix(8)) modelId=\(entry.model.id) provider=\(entry.providerInstanceId.prefix(8))")
            return
        }

        logger.warning("[NewSessionDefault] tier=3 latest-provider MISS sid=\(sessionId.prefix(8)) — no enabled+credentialed provider with a usable text model; session left without binding (resolveCurrentEntry will fall back to global config)")
    }

    /// [T-newchat-default-model-fallback-ios] Tier-2 helper: scan the most
    /// recent sessions and return the first ModelEntry on this device that
    /// matches one of their stored `modelId`s and is currently usable
    /// (enabled instance + credential + not hidden).
    ///
    /// Scans up to 20 sessions — far past the typical "last 1-2" the
    /// fallback usually finds, but cheap and bounded for the edge case
    /// where the few most-recent sessions all used a model the user has
    /// since removed.
    private static func resolveLastUsedEntry(excludingSessionId: String, store: ProviderConfigStore) async -> ModelEntry? {
        let recents = await ChatStore.shared.listSessions()
        let scanLimit = 20
        var scanned = 0
        for session in recents {
            if scanned >= scanLimit { break }
            if session.id == excludingSessionId { continue }
            let mid = session.modelId
            scanned += 1
            // Find ALL entries on this device matching the recorded modelId,
            // then prefer one whose instance is currently usable.
            let candidates = store.modelEntries.filter { entry in
                entry.model.id == mid && !entry.isHidden
            }
            if candidates.isEmpty { continue }
            if let usable = candidates.first(where: { entry in
                guard let inst = store.instance(for: entry.providerInstanceId) else { return false }
                return inst.isEnabled && inst.hasAnyCredential
            }) {
                logger.info("[NewSessionDefault] last-used scan: sourceSession=\(session.id.prefix(8)) modelId=\(mid) → entry=\(usable.id.prefix(8)) provider=\(usable.providerInstanceId.prefix(8)) (scanned=\(scanned))")
                return usable
            }
            // Match existed but isn't usable on this device — keep scanning
            // earlier sessions rather than binding to a disabled provider.
            logger.info("[NewSessionDefault] last-used scan: sourceSession=\(session.id.prefix(8)) modelId=\(mid) matched \(candidates.count) entries but none usable (instance disabled or no credential) — continuing scan")
        }
        logger.info("[NewSessionDefault] last-used scan: exhausted (scanned=\(scanned)/\(recents.count)) — no usable match")
        return nil
    }

    /// [T-newchat-default-model-fallback-ios] Tier-3 helper: pick the newest
    /// enabled+credentialed provider instance and within it the newest
    /// non-hidden text-output model entry.
    ///
    /// "Newest" = last position in the in-memory array (instances/entries
    /// are appended on add, so the tail is the most recent addition).
    /// Text-output is determined by `modalityOverride`: an entry is treated
    /// as text-capable unless its modalityOverride is explicitly one of the
    /// pure non-text output modalities (imageOutput / audioOutput /
    /// videoOutput) — same logic as the picker's `nonTextOutputLabel`. A
    /// nil modalityOverride is assumed text-capable, matching the picker.
    private static func resolveLatestProviderTextEntry(store: ProviderConfigStore) -> ModelEntry? {
        // Walk providers newest-first.
        for instance in store.instances.reversed() {
            guard instance.isEnabled, instance.hasAnyCredential else {
                logger.info("[NewSessionDefault] latest-provider scan: skip instance=\(instance.label) (enabled=\(instance.isEnabled) hasCredential=\(instance.hasAnyCredential))")
                continue
            }
            let entries = store.modelEntries.filter { e in
                e.providerInstanceId == instance.id && !e.isHidden && isTextOutputModel(e.model)
            }
            guard let chosen = entries.last else {
                logger.info("[NewSessionDefault] latest-provider scan: instance=\(instance.label) has no usable text-output entries")
                continue
            }
            logger.info("[NewSessionDefault] latest-provider scan: picked instance=\(instance.label) entry=\(chosen.id.prefix(8)) modelId=\(chosen.model.id) (textEntriesInInstance=\(entries.count))")
            return chosen
        }
        return nil
    }

    /// [T-newchat-default-model-fallback-ios] True when `model` produces text
    /// output — i.e. NOT a pure image / audio / video generation model. Used
    /// by the tier-3 fallback to exclude `gpt-image-2`, TTS models, etc.
    /// from auto-selection on a fresh install. nil modalityOverride = assume
    /// text (most models don't override, and historically text-output is the
    /// default for chat models).
    private static func isTextOutputModel(_ model: LLMModel) -> Bool {
        guard let modality = model.modalityOverride else { return true }
        if modality.contains(.imageOutput) { return false }
        if modality.contains(.audioOutput) { return false }
        if modality.contains(.videoOutput) { return false }
        return true
    }

    /// Apply a ModelGroup's session defaults (thinking level) to the given session.
    /// The group's context limit is no longer mirrored here — effectiveContextWindow
    /// reads it live from the bound group on every capacity check, so it stays
    /// current when the group (or the model's context window) is edited mid-session.
    /// - Parameter preserveThinkingLevel: when true, the caller has already
    ///   written a user-chosen thinking level for this session, so don't seed
    ///   the group default over it. [T-first-msg-thinking-level-clobber]
    private func applyGroupSessionDefaults(group: ModelGroup, sessionId: String,
                                           preserveThinkingLevel: Bool = false) {
        let store = ProviderConfigStore.shared
        // Apply default thinking level (unless the user set one pre-send).
        if !preserveThinkingLevel, let thinkLevel = group.defaultThinkingLevel {
            var cfg = store.inferenceConfig(for: sessionId) ?? SessionInferenceConfig()
            cfg.thinkingLevel = thinkLevel
            store.setInferenceConfig(cfg, for: sessionId)
        }
    }

    /// Persist an AgentMessage to the database as a RawMessage.
    /// Build a RawMessage from an AgentMessage without persisting it.
    /// [T-token-attribution-snapshot] `modelEntryId` identifies the entry that
    /// ACTUALLY served this turn, and must be supplied by the caller from the
    /// send loop's `activeEntryId` — the value failover reassigns when it moves
    /// to a backup model.
    ///
    /// Do NOT resolve it here from the session or from `selectedModel`: by the
    /// time a turn is persisted the session may already point somewhere else,
    /// which is the exact defect the snapshot exists to remove. nil simply
    /// leaves the row unattributed (rendered as "estimated") rather than
    /// recording a guess.
    func buildRawMessage(_ msg: AgentMessage, tokenUsage: TokenUsage? = nil, snapshots: [String: (toolName: String, snapshot: ToolSnapshot)] = [:], thoughtSignatures: [String: String] = [:], reasoningContent: String? = nil, streamInterruptCount: Int = 0, toolStatuses: [String: String] = [:], modelEntryId: String? = nil) async -> RawMessage? {
        await ensureSession()
        guard let sessionId else { return nil }

        var parts: [ContentPart] = []
        for part in msg.parts {
            switch part {
            case .text(let s):
                parts.append(.text(s))
            case .toolUse(let id, let name, let input):
                let inputJSON: String
                if let data = try? JSONSerialization.data(withJSONObject: input),
                   let str = String(data: data, encoding: .utf8) {
                    inputJSON = str
                } else {
                    inputJSON = "{}"
                }
                parts.append(.toolUse(ToolUse(
                    toolUseId: id, name: name, input: inputJSON,
                    description: input["tool_title"] as? String,
                    thoughtSignature: thoughtSignatures[id]
                )))
            case .toolResult(let id, let name, let content, let isError, let imgData, _, let pageURL, _):
                _ = name // name stored only in toolUse
                let snap = snapshots[id]?.snapshot
                // Only persist mediaRef for tool results that carried image data to the model
                // (i.e. screenshot action). Snapshot images are for display only and should
                // NOT be resent to the model on session reload.
                let mediaRef: MediaRef? = imgData != nil ? snap?.mediaRef : nil
                let truncatedURL = pageURL.map { ToolResult.truncateURL($0) }
                let statusStr = toolStatuses[id] ?? (isError ? "failed" : "success")
                parts.append(.toolResult(ToolResult(
                    toolUseId: id, output: content, success: !isError, mediaRef: mediaRef, snapshot: snap, pageURL: truncatedURL, status: statusStr
                )))
            case .imageData(let data, let mimeType, let linuxPath):
                let ref = await ChatStore.shared.saveMedia(
                    data: data, mimeType: mimeType, sessionId: sessionId,
                    linuxPath: linuxPath
                )
                parts.append(.mediaRef(ref))
            }
        }

        let storedUsage: StoredTokenUsage? = tokenUsage.map {
            StoredTokenUsage(
                inputTokens: $0.inputTokens, outputTokens: $0.outputTokens,
                cacheCreationTokens: $0.cacheCreationTokens, cacheReadTokens: $0.cacheReadTokens,
                latestContextTokens: $0.latestContextTokens
            )
        }

        var raw = RawMessage(
            id: UUID().uuidString, sessionId: sessionId,
            role: msg.role == .user ? .user : .assistant,
            parts: parts, createdAt: Date(), tokenUsage: storedUsage,
            reasoningContent: reasoningContent ?? msg.reasoningContent,
            streamInterruptCount: streamInterruptCount
        )
        // [T-token-attribution-snapshot] Resolved from the entry the caller
        // says served this turn — the provider TYPE is stored as its rawValue
        // so grouping never depends on a localized display string.
        if let eid = modelEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
            raw.modelId = entry.model.id
            raw.modelDisplayName = entry.model.displayName
            raw.providerType = ProviderConfigStore.shared
                .instance(for: entry.providerInstanceId)?.providerType.rawValue
            raw.providerInstanceId = entry.providerInstanceId
        }
        return raw
    }

    /// Phase B: Return the LLM-facing view of agentHistory.
    ///
    /// If there is a cached latest compact marker, return:
    ///   [summary] + agentHistory[firstKeptIdx...]
    /// where firstKeptIdx is the position of the message whose dbMessageId
    /// matches marker.firstKeptMessageId.
    ///
    /// If the marker can't be resolved (stale / missing id), fall back to returning
    /// the full agentHistory — safer to over-inform the LLM than to under-inform.
    ///
    /// This is the single entry point all LLM context builders must go through.
    /// [T-ios-empty-after-toolresult-reminder] True when the most recent
    /// message in the effective (about-to-be-sent) history ends with a tool
    /// result — i.e. the model owes us a follow-up (new tool call or a text
    /// answer). Used to decide whether an empty server response warrants the
    /// one-shot reminder retry.
    func lastEffectiveMessageIsToolResult() -> Bool {
        guard let last = effectiveAgentHistoryUncounted().last else { return false }
        return last.parts.contains { if case .toolResult = $0 { return true }; return false }
    }

    /// [T-ios-empty-after-toolresult-reminder] Returns the effective history
    /// with a `<system-reminder>` appended to the LAST tool result's text
    /// content, nudging the model to actually produce output after a tool
    /// result. In-flight only — never persisted, so the DB / reload path is
    /// unchanged; the reminder exists solely on the single retry request.
    func historyWithEmptyToolResultReminder() -> [AgentMessage] {
        var history = effectiveAgentHistory()
        guard let lastIdx = history.indices.last else { return history }
        let reminder = "\n\n<system-reminder>The previous response was empty. A tool result was just provided and you MUST continue: respond with the next tool call(s) if more work is needed, or a final text answer for the user. Do not return an empty response.</system-reminder>"
        // Append to the last text-bearing tool result part; fall back to the
        // last tool result of any kind.
        var parts = history[lastIdx].parts
        if let partIdx = parts.indices.last(where: {
            if case .toolResult = parts[$0] { return true }; return false
        }) {
            if case let .toolResult(id, name, content, isError, imageData, imageMimeType, pageURL, imageLinuxPath) = parts[partIdx] {
                parts[partIdx] = .toolResult(
                    id: id, name: name, content: content + reminder, isError: isError,
                    imageData: imageData, imageMimeType: imageMimeType,
                    pageURL: pageURL, imageLinuxPath: imageLinuxPath
                )
                history[lastIdx] = AgentMessage(
                    role: history[lastIdx].role,
                    parts: parts,
                    isInterrupted: history[lastIdx].isInterrupted,
                    reasoningContent: history[lastIdx].reasoningContent,
                    reasoningEcho: history[lastIdx].reasoningEcho,
                    dbMessageId: history[lastIdx].dbMessageId
                )
            }
        }
        return history
    }

    func effectiveAgentHistory() -> [AgentMessage] {
        let result = Self.dropOrphanedToolParts(effectiveAgentHistoryUncounted(), logger: logger)
        // One-line breadcrumb on every inference call so a "summary-only"
        // regression is visible in logs without scraping agent traces.
        logger.info("[Compact] effectiveAgentHistory: returning \(result.count) msg(s) from agentHistory.count=\(self.agentHistory.count) markerId=\(self.cachedLatestMarker?.id.prefix(8) ?? "nil")")
        return result
    }

    /// [T-ios-compact-orphan-toolcall] Last line of defence before a history
    /// slice becomes a provider request: every `toolResult`
    /// (function_call_output) must have a matching `toolUse` (function_call)
    /// somewhere in the same slice, and vice versa. An unmatched pair is a hard
    /// 400 on OpenAI-compatible APIs —
    ///     No tool call found for function call output with call_id …
    /// — and, because the slice is recomputed deterministically, it repeats on
    /// every retry and every fallback model, wedging the conversation until the
    /// user clears the session.
    ///
    /// Any orphan reaching here is a bug upstream (the walk-back boundary and
    /// the preAnchor prune are both supposed to preserve pairing), so this
    /// logs loudly rather than silently papering over it. Recovery mirrors the
    /// long-standing pass `runAgentLoop` runs over the FULL `agentHistory`
    /// (AIChatViewModel ~4556) — that one cannot help here because compaction
    /// slices the history *after* it runs, which is precisely how this orphan
    /// is born:
    ///
    ///   * orphaned function_call_output → drop it. Its call is gone from the
    ///     slice, and nothing can reconstruct it.
    ///   * orphaned function_call → synthesise an error tool_result rather than
    ///     deleting the call. Deleting would silently discard the assistant's
    ///     own reasoning; the placeholder keeps the turn intact and tells the
    ///     model that round failed. Same wording/shape as the existing pass.
    ///
    /// Messages emptied by the drop are removed, since a parts-less message is
    /// itself invalid on several providers.
    static func dropOrphanedToolParts(_ history: [AgentMessage], logger: AppLogger) -> [AgentMessage] {
        var toolUseIds: Set<String> = []
        var toolResultIds: Set<String> = []
        for msg in history {
            for part in msg.parts {
                switch part {
                case .toolUse(let id, _, _): toolUseIds.insert(id)
                case .toolResult(let id, _, _, _, _, _, _, _): toolResultIds.insert(id)
                default: break
                }
            }
        }
        let orphanedResults = toolResultIds.subtracting(toolUseIds)
        var orphanedUses = toolUseIds.subtracting(toolResultIds)

        // [T-ios-compact-orphan-toolcall] IN-FLIGHT EXEMPTION. The tool_uses in
        // the FINAL assistant message are not orphans when the loop is still
        // between "model asked for tools" and "results appended" — agentHistory
        // legitimately looks unpaired for the whole of that window
        // (AIChatViewModel appends the assistant turn ~5263 and its tool results
        // only ~5569). `keepAliveHistory = effectiveAgentHistory()` snapshots
        // inside exactly that gap, so without this exemption a cache-warmup
        // request would carry fabricated "Tool execution was interrupted"
        // results for tools that were about to run normally — poisoning the
        // cached prefix and, worse, telling the model its tools had failed.
        // Trailing unanswered calls need no repair anyway: a request ending on
        // an assistant tool_use is exactly what the API expects mid-round.
        if let last = history.last, last.role == .assistant {
            for part in last.parts {
                if case .toolUse(let id, _, _) = part { orphanedUses.remove(id) }
            }
        }

        guard !orphanedResults.isEmpty || !orphanedUses.isEmpty else { return history }

        logger.warning("[CompactDiag] orphan tool parts in OUTGOING history — repairing. orphanedOutputs=\(orphanedResults.count) [\(orphanedResults.sorted().prefix(3).joined(separator: ","))] orphanedCalls=\(orphanedUses.count) [\(orphanedUses.sorted().prefix(3).joined(separator: ","))] historyCount=\(history.count)")

        var cleaned: [AgentMessage] = []
        cleaned.reserveCapacity(history.count)
        for var msg in history {
            let kept = msg.parts.filter { part in
                if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                    return !orphanedResults.contains(id)
                }
                return true
            }
            if kept.isEmpty { continue }
            msg.parts = kept
            cleaned.append(msg)

            // Follow an assistant turn holding orphaned calls with the
            // placeholder results it never got, so the pair is complete.
            guard msg.role == .assistant else { continue }
            let unanswered = kept.compactMap { part -> (String, String)? in
                if case .toolUse(let id, let name, _) = part, orphanedUses.contains(id) {
                    return (id, name)
                }
                return nil
            }
            if !unanswered.isEmpty {
                cleaned.append(AgentMessage(
                    role: .user,
                    parts: unanswered.map { id, name in
                        AgentContentPart.toolResult(
                            id: id, name: name,
                            content: "Tool execution was interrupted by an unexpected error.",
                            isError: true
                        )
                    }
                ))
            }
        }
        return cleaned
    }

    func effectiveAgentHistoryUncounted() -> [AgentMessage] {
        guard let marker = cachedLatestMarker else { return agentHistory }

        // ─── v2 markers (id-only model) ────────────────────────────────
        //
        // Marker semantics:
        //   anchorMessageId (= lastCompactedMessageId in DB) = "this marker
        //   covers everything from session start (or the previous marker's
        //   anchor + 1) up to and including this message. summary is the
        //   compressed form of that range."
        //
        // What we send to the model, in chronological order:
        //   1. Last `compactKeepRecentUserTurns` user-text turns BEFORE the
        //      anchor (inclusive of anchor) — gives the model a few real
        //      messages of warm-up context immediately preceding the summary.
        //   2. The summary itself, injected as a `<context-summary>` text
        //      part PREPENDED to the first user message AFTER the anchor.
        //      This keeps strict role alternation (user → assistant → ...)
        //      intact without inserting a synthetic standalone user turn.
        //   3. All messages strictly after the anchor (with the first user
        //      already mutated above).
        //
        // Effect on the wire:
        //   [m_priorN..m_anchor]        (real history, last N turns)
        //   [first_post_anchor_user with summary as part-0]
        //   [m_post_anchor_rest]
        //   [the new user input the caller is about to append]
        if marker.version >= 2 {
            guard let anchorId = marker.lastCompactedMessageId,
                  let anchorIdx = agentHistory.lastIndex(where: { $0.dbMessageId == anchorId }) else {
                // Anchor unresolvable → degrade safely. Don't emit a
                // standalone summary message (creates the empty-context loop
                // when paired with hot tools). Fall back to full history;
                // the user can `revertCompact` if the marker is corrupt.
                logger.warning("[Compact] effectiveAgentHistory v2: anchorId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil") not in agentHistory(count=\(self.agentHistory.count)) — degrading to full history (no summary)")
                return agentHistory
            }

            // Step 1: walk back from anchor collecting user-text turns. Stop
            // when EITHER we've collected N user-text turns OR including the
            // next turn would push preAnchor over 100 messages. Decisions
            // happen only at user-message boundaries so we never split a
            // user/assistant/tool round in half (which would orphan a
            // tool_use with no matching tool_result).
            //
            // priorIdx is always set to a `user` message — that's what the
            // API requires for the first message in a request.
            let preAnchorCap = 100
            let walkBack = walkBackUserTurnsBounded(
                anchorIdx: anchorIdx,
                maxUserTextTurns: Self.compactKeepRecentUserTurns,
                maxMessages: preAnchorCap
            )
            let priorIdxResolved: Int? = walkBack.priorIdx
            let priorIdx = walkBack.priorIdx ?? (anchorIdx + 1)  // empty preAnchor sentinel
            if walkBack.stopReason != "userTextTargetMet" {
                logger.info("[CompactDiag] eAH v2 walkBack stopped: reason=\(walkBack.stopReason) priorIdx=\(priorIdx) userTextTurnsFound=\(walkBack.userTextTurnsFound) preAnchorMsgs=\(walkBack.messageCount)")
            }

            let summaryText = Self.compactSummaryWrappedText(marker.summary)

            // Step 2 & 3: inject summary into the first POST-anchor user
            //             message (or, if there isn't one, append a
            //             standalone user message — safe because no later
            //             user follows it).
            //
            // PRE-ANCHOR PRUNE (tool-heavy session fix):
            // The walk-back-3-user-text strategy pulls in everything between
            // the 3rd-last and last user-text turn — in a heavy tool-call
            // session that can be 200+ messages of tool_result / tool_use,
            // tens of thousands of tokens that the summary already covers.
            // Drop any tool_result > 1000 chars in the preAnchor slice and
            // strip the matching tool_use part (same id) from the assistant
            // message so the model never sees a dangling tool_use/result.
            let preAnchorRaw: [AgentMessage] = (priorIdx <= anchorIdx) ? Array(agentHistory[priorIdx...anchorIdx]) : []
            var droppedToolIds: Set<String> = []
            var droppedCount = 0
            for msg in preAnchorRaw {
                for part in msg.parts {
                    if case .toolResult(let id, _, let content, _, _, _, _, _) = part,
                       content.count > 1000 {
                        droppedToolIds.insert(id)
                        droppedCount += 1
                    }
                }
            }
            var preAnchorPruned: [AgentMessage] = []
            preAnchorPruned.reserveCapacity(preAnchorRaw.count)
            for var msg in preAnchorRaw {
                // Filter parts: drop the large toolResult AND its paired toolUse.
                let kept = msg.parts.filter { part in
                    switch part {
                    case .toolUse(let id, _, _):
                        return !droppedToolIds.contains(id)
                    case .toolResult(let id, _, _, _, _, _, _, _):
                        return !droppedToolIds.contains(id)
                    default:
                        return true
                    }
                }
                if kept.isEmpty { continue }  // skip empty shells
                msg.parts = kept
                preAnchorPruned.append(msg)
            }
            if droppedCount > 0 {
                logger.info("[CompactDiag] eAH v2 preAnchor prune: dropped \(droppedCount) toolResult(>1kc) + paired toolUse, \(preAnchorRaw.count - preAnchorPruned.count) messages emptied; pruned slice=\(preAnchorPruned.count)")
            }

            // ROLE ALIGNMENT: the API requires the first message to be `user`.
            // After clamp (100-cap may land on assistant) and after prune
            // (the head user may have been emptied), peel any leading non-user
            // messages so preAnchor starts on a user turn. If everything got
            // peeled, preAnchor is empty — that's fine; summary will be
            // injected into the first postAnchor user instead.
            while let first = preAnchorPruned.first, first.role != .user {
                preAnchorPruned.removeFirst()
            }

            var result: [AgentMessage] = []
            result.append(contentsOf: preAnchorPruned)

            let postAnchor = (anchorIdx + 1) < agentHistory.count
                ? Array(agentHistory[(anchorIdx + 1)...])
                : []

            // DIAG: explain how the slice was sized — uses post-prune /
            // post-alignment counts so the log reflects what actually reaches
            // the model, not the raw walk-back range.
            let preAnchorCountSent = preAnchorPruned.count
            let postAnchorCount = postAnchor.count
            let priorIdxSource = priorIdxResolved == nil ? "fallback=0(<\(Self.compactKeepRecentUserTurns) user-text turns before anchor)" : "userTextWalkBack(N=\(Self.compactKeepRecentUserTurns))"
            let preAnchorRawCount = max(0, anchorIdx - priorIdx + 1)
            logger.info("[CompactDiag] eAH v2 slice: priorIdx=\(priorIdx) anchorIdx=\(anchorIdx) agentHistory.count=\(self.agentHistory.count) → preAnchorRaw=\(preAnchorRawCount) preAnchorSent=\(preAnchorCountSent) postAnchor=\(postAnchorCount) summaryChars=\(marker.summary.count) priorIdxSource=\(priorIdxSource) markerId=\(marker.id.prefix(8))")
            // Show the post-prune / post-alignment preAnchor absolute indices,
            // not the raw walk-back range. This way "what is actually sent"
            // matches what the log shows.
            let preAnchorTags = preAnchorPruned.map { msg -> String in
                let dbId = msg.dbMessageId?.prefix(8) ?? "----"
                return "\(msg.role.rawValue)/db=\(dbId)"
            }
            let postAnchorTags = postAnchor.enumerated().map { (off, m) -> String in
                "\(anchorIdx + 1 + off):\(m.role.rawValue)"
            }
            logger.info("[CompactDiag] eAH v2 layout: preAnchorSent=[\(preAnchorTags.joined(separator: ","))] | SUMMARY injected into first postAnchor user | postAnchorAbsIdx=[\(postAnchorTags.joined(separator: ","))]")

            if let firstUserOffset = postAnchor.firstIndex(where: { $0.role == .user }) {
                // Splice: copy prefix as-is, mutate the first user, copy rest.
                if firstUserOffset > 0 {
                    result.append(contentsOf: postAnchor[0..<firstUserOffset])
                }
                var injected = postAnchor[firstUserOffset]
                injected.parts.insert(.text(summaryText), at: 0)
                result.append(injected)
                if firstUserOffset + 1 < postAnchor.count {
                    result.append(contentsOf: postAnchor[(firstUserOffset + 1)...])
                }
            } else {
                // No user message after anchor — append everything, then a
                // standalone summary user turn at the end. The new user input
                // about to be added by the caller will follow it as the last
                // user, but two user turns at the tail will be merged by the
                // existing mergeConsecutiveSameRole pass downstream. (Rare:
                // only happens when compact ran but no new user prompt exists
                // yet.)
                result.append(contentsOf: postAnchor)
                result.append(AgentMessage(role: .user, parts: [.text(summaryText)]))
            }

            return result
        }

        // ─── v1 (legacy) markers ───────────────────────────────────────
        // Resolve boundary with same priority as Phase 2.5 restore.
        if let fkmId = marker.firstKeptMessageId ?? marker.boundaryMessageId,
           let keepStart = agentHistory.firstIndex(where: { $0.dbMessageId == fkmId }) {
            let summaryMsg = Self.summaryAsAgentMessage(marker.summary)
            var result: [AgentMessage] = [summaryMsg]
            result.append(contentsOf: agentHistory[keepStart...])
            return result
        }
        // compactAll marker (nil firstKeptMessageId): summary + (anchor user
        // turns kept by compact) + (any post-compact new messages). The kept
        // tail is bounded by lastCompactedMessageId — everything strictly AFTER
        // it in agentHistory is "kept anchor + new messages".
        //
        // ⚠️ Defensive lcm resolution: lastCompactedMessageId can fail to map
        // back into agentHistory in real sessions:
        //   - The compacted boundary message may have been appended without its
        //     DB id written back yet (persistAgentMessage races the next agent
        //     iteration), so the marker was written with `nil` lcmId.
        //   - View-model rebuild from DB (Phase 2.5 restore) reorders or drops
        //     transient agentHistory entries, leaving the lcm dbMessageId
        //     unfindable on the next inference call.
        // Both used to silently degrade to `[summaryMsg]` (a single message
        // with no tool_use_id context); the model would then hot-loop calling
        // memory_get to "recover state" — see the M-Team session bug. Recompute
        // the anchor from the tail when lcm resolution fails so the model still
        // has the recent conversation as context.
        if marker.firstKeptMessageId == nil && marker.boundaryMessageId == nil {
            let summaryMsg = Self.summaryAsAgentMessage(marker.summary)
            var result: [AgentMessage] = [summaryMsg]

            // Normal path: lcmId resolves in agentHistory. Append everything
            // strictly after it = (anchor user turns kept by compact) + (new
            // messages added since compact).
            if let lcmId = marker.lastCompactedMessageId,
               let lcmIdx = agentHistory.lastIndex(where: { $0.dbMessageId == lcmId }) {
                if lcmIdx + 1 < agentHistory.count {
                    result.append(contentsOf: agentHistory[(lcmIdx + 1)...])
                }
                return result
            }

            // Degraded path: lcmId is nil OR not in agentHistory. Recompute
            // anchor by walking back N user-text turns — same rule compactBefore
            // uses to *write* the kept tail, so behavior matches the user's
            // mental model ("compact left the last N turns alone").

            // Diagnostic dump: which msg ids actually exist in agentHistory
            // around the boundary, vs what the marker expects to find. Prints
            // last 5 entries' dbMessageId + role + a short content preview so
            // we can spot whether the lcm is just the wrong id, or whether
            // entries near the boundary lost their dbMessageId entirely.
            let withDbId = agentHistory.filter { $0.dbMessageId != nil }.count
            let tailSlice = agentHistory.suffix(5).enumerated().map { (offset, m) -> String in
                let absoluteIdx = agentHistory.count - 5 + offset
                let preview: String = m.parts.first.map { part -> String in
                    switch part {
                    case .text(let t):                 return "text(\(t.prefix(40)))"
                    case .toolUse(_, let name, _):     return "tool_use(\(name))"
                    case .toolResult(let id, _, _, _, _, _, _, _): return "tool_result(id=\(id.prefix(8)))"
                    case .imageData:                   return "imageData"
                    }
                } ?? "(empty)"
                return "[\(absoluteIdx)] role=\(m.role) dbId=\(m.dbMessageId?.prefix(8) ?? "nil") \(preview)"
            }.joined(separator: " | ")
            logger.warning("[Compact] effectiveAgentHistory DIAG: marker.id=\(marker.id.prefix(8)) marker.lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil") agentHistory.count=\(self.agentHistory.count) entriesWithDbId=\(withDbId) tail5=\(tailSlice)")

            if let nthIdx = indexOfNthFromLastUserText(Self.compactKeepRecentUserTurns) {
                result.append(contentsOf: agentHistory[nthIdx...])
                logger.warning("[Compact] effectiveAgentHistory: lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil") not in agentHistory(count=\(self.agentHistory.count)); falling back to last \(Self.compactKeepRecentUserTurns) user turns (anchorIdx=\(nthIdx), kept=\(self.agentHistory.count - nthIdx))")
            } else {
                // Not even N user-text turns available — append full history
                // rather than risk the empty-context loop.
                result.append(contentsOf: agentHistory)
                logger.warning("[Compact] effectiveAgentHistory: lcmId unresolved AND <\(Self.compactKeepRecentUserTurns) user turns in agentHistory(count=\(self.agentHistory.count)); falling back to full history")
            }
            return result
        }
        // Stale / legacy marker with only sortOrder info. agentHistory entries
        // don't carry sort_order directly, so give up on marker filtering and
        // return full history.
        logger.warning("[Compact] effectiveAgentHistory: marker id=\(marker.id.prefix(8)) unresolvable in agentHistory — returning full history")
        return agentHistory
    }

    /// Persist an agent message to DB. Returns the assigned DB row id on success,
    /// or nil if buildRawMessage fails (e.g. no sessionId yet).
    ///
    /// Callers should write the returned id back to `agentHistory[i].dbMessageId`
    /// so compact logic can later resolve boundaries by id.
    @discardableResult
    func persistAgentMessage(_ msg: AgentMessage, tokenUsage: TokenUsage? = nil, snapshots: [String: (toolName: String, snapshot: ToolSnapshot)] = [:], thoughtSignatures: [String: String] = [:], reasoningContent: String? = nil, streamInterruptCount: Int = 0, modelEntryId: String? = nil) async -> String? {
        let sid = self.sessionId ?? "nil"
        logger.info("[Persist] enter sid=\(sid.prefix(8)) role=\(msg.role.rawValue) parts=\(msg.parts.count)")
        guard let raw = await buildRawMessage(msg, tokenUsage: tokenUsage, snapshots: snapshots, thoughtSignatures: thoughtSignatures, reasoningContent: reasoningContent, streamInterruptCount: streamInterruptCount, modelEntryId: modelEntryId) else {
            logger.warning("[Persist] buildRawMessage returned nil sid=\(sid.prefix(8)) role=\(msg.role.rawValue) — NOT WRITTEN")
            return nil
        }
        logger.info("[Persist] built raw.sid=\(raw.sessionId.prefix(8)) raw.id=\(raw.id.prefix(8)) role=\(raw.role.rawValue)")
        await ChatStore.shared.appendMessage(raw)
        // Post-write DB sanity check: query count + max(sort_order) for this session
        let stats = await ChatStore.shared.sessionWriteStats(sessionId: raw.sessionId)
        logger.info("[Persist] post-write sid=\(raw.sessionId.prefix(8)) dbCount=\(stats.count) dbMaxSortOrder=\(stats.maxSortOrder)")
        // Bump baseline so iCloud sync doesn't mistake our own persisted messages for remote ones.
        lastKnownDbSortOrder += 1
        lastKnownDbCount += 1
        return raw.id
    }

    /// [T-error-persist-ios] Persist (or clear, when `error == nil`) the device-local
    /// error string for the CURRENT assistant turn, so the indicator survives session
    /// reload / app restart. The error lives on the UI ChatMessage, but the DB row is
    /// keyed by the last assistant agentHistory entry's `dbMessageId` (the same id the
    /// persist path wrote). No-op until that turn has been persisted (dbMessageId set)
    /// — the row doesn't exist yet, and the eventual INSERT carries error_info itself
    /// only if set on RawMessage; for the common "set error after persist" ordering
    /// this UPDATE is what writes it. iCloud is untouched (error_info is device-local).
    ///
    /// [T-ios-error-banner-lost] When no assistant row has been persisted yet,
    /// this used to give up and return — which is exactly the case that loses
    /// the error. A request that fails EARLY (bad API key, refused connection,
    /// provider 4xx before any content) never reaches the point where the
    /// assistant turn is written, so there was no row to hang error_info on and
    /// the banner existed only in memory. Switching sessions or backgrounding
    /// the app destroys the per-session @StateObject view model, and with it the
    /// only copy of the error: the user comes back to a chat that looks like
    /// nothing happened.
    ///
    /// So when the assistant row is missing, write a minimal one now, purely to
    /// carry the error. It is a real assistant row with empty content, which is
    /// what the reload path already renders errors from (a user row cannot show
    /// one — `ChatMessageRow` only draws `inlineError` for the assistant), and
    /// it matches the in-memory shape the send path already uses for the
    /// context-exhausted case (`ChatMessage(role: .assistant, content: "")` with
    /// `.error` set).
    func persistErrorInfo(_ error: String?) async {
        if let dbId = agentHistory.last(where: { $0.role == .assistant && $0.dbMessageId != nil })?.dbMessageId {
            let ok = await ChatStore.shared.updateMessageErrorInfo(messageId: dbId, errorInfo: error)
            logger.info("[ErrorPersist] wrote error_info to msg=\(dbId.prefix(8)) cleared=\(error == nil) ok=\(ok)")
            return
        }

        // Clearing is a no-op when there is nothing persisted: an absent row
        // already means "no error", and creating one to record its absence
        // would put an empty assistant bubble in the transcript.
        guard let error else {
            logger.info("[ErrorPersist] skip clear — no persisted assistant row")
            return
        }

        // Only for a real session. A draft has no session id yet, so there is
        // nothing to reload into and nothing to lose.
        guard sessionId != nil else {
            logger.info("[ErrorPersist] skip — no sessionId (draft)")
            return
        }

        let carrier = AgentMessage(role: .assistant, parts: [])
        guard let newId = await persistAgentMessage(carrier) else {
            logger.warning("[ErrorPersist] failed to persist error-carrier assistant row")
            return
        }
        // Record the id so a later clear (retry succeeding) can find this row
        // instead of orphaning it — the same link `retry()` walks.
        var stamped = carrier
        stamped.dbMessageId = newId
        agentHistory.append(stamped)

        let ok = await ChatStore.shared.updateMessageErrorInfo(messageId: newId, errorInfo: error)
        logger.info("[ErrorPersist] created carrier row msg=\(newId.prefix(8)) for early failure ok=\(ok)")
    }

}
