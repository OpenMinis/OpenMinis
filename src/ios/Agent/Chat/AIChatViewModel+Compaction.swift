import Foundation

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Context Compaction

extension AIChatViewModel {

    // MARK: - Context Compaction

    /// Check context usage against the model's policy thresholds.
    /// Resolves the entry via `resolveCurrentEntry()` — the SAME resolution the
    /// send path uses (availability re-routing, cachedSessionModelId fallback,
    /// default group) — so capacity is always judged against the model that
    /// will actually serve the request. The previous manual dig through
    /// `binding.primarySource` could disagree with the send path in two ways:
    /// a group's stale resolvedEntryId (member since disabled/hidden) judged
    /// capacity by the WRONG member's window, and sessions without a binding
    /// (e.g. iCloud-synced) skipped capacity checks entirely.
    func checkContextBeforeSend() -> ContextPolicy.CheckResult {
        guard let entry = resolveCurrentEntry() else { return .ok }
        let contextWindow = effectiveContextWindow(for: entry.model)
        guard contextWindow > 0 else { return .ok }

        let policy = ContextPolicy(contextWindow: contextWindow)
        let estimated = estimateContextTokens()
        let result = policy.check(estimatedTokens: estimated, contextWindow: contextWindow)
        let markerInfo: String
        if let m = cachedLatestMarker {
            let ageSec = Int(Date().timeIntervalSince(m.createdAt))
            markerInfo = "marker=\(m.id.prefix(8)) ageSec=\(ageSec) summaryChars=\(m.summary.count)"
        } else {
            markerInfo = "marker=nil"
        }
        logger.info("[CompactDiag] checkContextBeforeSend: model=\(entry.model) window=\(contextWindow) estimated=\(estimated) compactThreshold=\(policy.compactThreshold) offloadThreshold=\(policy.offloadThreshold) exhaustedOnly=\(policy.exhaustedOnly) → \(String(describing: result)) | \(markerInfo) | agentHistory.count=\(self.agentHistory.count)")
        return result
    }

    /// Legacy compatibility — returns true if any intervention is needed before send.
    func needsCompactBeforeSend() -> Bool {
        checkContextBeforeSend() != .ok
    }

    /// Compact then send the pending message.
    func compactAndSend() {
        showCompactBeforeSendPrompt = false
        let text = pendingSendText ?? ""
        let atts = pendingSendAttachments
        pendingSendText = nil
        pendingSendAttachments = []

        // Show the message as queued immediately
        let queuedPrompt = QueuedPrompt(text: text, attachments: atts)
        promptQueue.append(queuedPrompt)
        let chatMsg = ChatMessage(role: .user, content: text, isQueued: true)
        chatMsg.queuedPromptId = queuedPrompt.id
        chatMsg.inputAttachments = atts
        messages.append(chatMsg)
        scrollToBottomSignal.send()

        // Find the last active non-queued message as compact target
        let activeMessages = messages.filter {
            $0.role != .compactDivider && $0.role != .systemInfo && !$0.isCompactedHistory && !$0.isQueued
        }
        guard activeMessages.count > 1, let lastActive = activeMessages.last else {
            // Not enough to compact — send the queued message directly
            promptQueue.removeAll { $0.id == queuedPrompt.id }
            messages.removeAll { $0.queuedPromptId == queuedPrompt.id }
            inputText = text
            attachments = atts
            skipCompactCheck = true
            send()
            return
        }

        let target = lastActive
        compactTask = Task {
            await compactBefore(target.id)
            // Drain the queued prompt(s) through the SAME path normal
            // streaming uses (drainQueuedPrompts clears `isQueued` on every
            // matching placeholder and runs the agent loop) — see
            // T-ios-queued-prompt-stale-style-after-compact for why send()
            // is wrong here. On SUCCESS compactBefore's tail has already
            // scheduled the drain (postCompactDrainPending dedups this call
            // into a no-op); this backstop covers compactBefore's failure
            // exits, where the user's compact-and-send message must still go
            // out — that's this path's long-standing behavior, unlike
            // /compact whose failure leaves the queue untouched.
            // [T-compact-queued-drain]
            guard !Task.isCancelled else { return }
            self.schedulePostCompactDrain()
        }
    }

    /// [T-compact-queued-drain] Spawn the post-compact queue drain on
    /// `currentTask` so prompts enqueued DURING a compact actually run once it
    /// finishes. Every compact completion funnels through here:
    /// compactBefore's success tail (covers compactAll → /compact, the
    /// long-press Compact-Before menu, compactAndSend, and the debug RPC) plus
    /// compactAndSend's failure backstop. Running on `currentTask` — not
    /// inline in the (already state-reset) compact task — keeps Stop working:
    /// cancel() cancels currentTask, and the tail guard mirrors the
    /// stop-handover rule from T-stop-with-queue-render-desync (2d037aa5).
    /// `postCompactDrainPending` dedups the two schedulers; drainQueuedPrompts'
    /// own reentrancy guard stays the last line of defense.
    func schedulePostCompactDrain() {
        guard !promptQueue.isEmpty else { return }
        guard !postCompactDrainPending else { return }
        postCompactDrainPending = true
        logger.info("[Compact] scheduling post-compact drain — \(self.promptQueue.count) queued prompt(s)")
        currentTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.postCompactDrainPending = false }
            self.isProcessing = true
            self.beginBackgroundProcessing()
            await self.drainQueuedPrompts()
            // Stop during the drained run hands state ownership to cancel()
            // (and possibly a fresh resumeQueueAfterCancel task) — don't
            // clobber it from the superseded task.
            guard !Task.isCancelled else { return }
            self.isProcessing = false
            self.endBackgroundProcessing()
        }
    }

    /// Cancel the compact-before-send prompt, restoring text to input.
    func cancelCompactBeforeSend() {
        showCompactBeforeSendPrompt = false
        inputText = pendingSendText ?? ""
        attachments = pendingSendAttachments
        pendingSendText = nil
        pendingSendAttachments = []
    }

    /// Number of recent user-text turns kept verbatim as inference anchors when
    /// compactAll runs. The summary stands in for everything older; the LLM
    /// still sees the last N user-text turns + their assistant replies + tool
    /// I/O so it can answer follow-ups that need verbatim detail (specific
    /// commands, exact strings) rather than the summary's distilled form.
    static let compactKeepRecentUserTurns: Int = 3

    /// Phase 2.5 self-heal: when a marker's `lastCompactedMessageId` no longer
    /// resolves in rawMessages (id orphaned by a v1→v2 sync migration or by a
    /// row delete), recompute the anchor by createdAt.
    ///
    /// Rule: anchor = the latest raw message whose `createdAt < marker.createdAt`
    /// AND whose id is present as a `dbMessageId` in `historyDbIds`. The
    /// agentHistory-presence filter is critical: `effectiveAgentHistory()`
    /// resolves the marker via `agentHistory.lastIndex(where: dbMessageId ==
    /// lcmId)` on every send, so a healed lcmId that's not in agentHistory
    /// would re-trigger the degraded "keep last N user turns" path, making
    /// the heal cosmetic only. Pass an empty `historyDbIds` to disable the
    /// filter (returns first match by createdAt alone).
    ///
    /// Falls back to `nil` only when no qualifying raw message predates the
    /// marker (rare: session wiped down to messages newer than the marker, or
    /// every predating raw has lost its dbMessageId binding in agentHistory).
    static func anchorByCreatedAt(in rawMessages: [RawMessage], markerCreatedAt: Date, historyDbIds: Set<String>) -> RawMessage? {
        rawMessages.last { raw in
            guard raw.createdAt < markerCreatedAt else { return false }
            return historyDbIds.isEmpty || historyDbIds.contains(raw.id)
        }
    }

    /// Locate a UI message whose source-sort-order range contains the given
    /// raw message's sortOrder. Used in Phase 2.5 to map an anchor raw back
    /// to its UI row, accounting for Phase 2's folding of multi-row assistant
    /// continuations into a single UI message (sourceSortOrder = first raw's
    /// sortOrder, lastSourceSortOrder = last raw's sortOrder).
    static func uiIndexForAnchorRaw(_ anchor: RawMessage, in uiMessages: [ChatMessage]) -> Int? {
        uiMessages.firstIndex { ui in
            guard let first = ui.sourceSortOrder else { return false }
            let last = ui.lastSourceSortOrder ?? first
            return anchor.sortOrder >= first && anchor.sortOrder <= last
        }
    }

    /// Build a healed v2 marker that preserves identity (`id`, `sessionId`,
    /// `summary`, `createdAt`, `compactedCount`) but swaps `lastCompactedMessageId`
    /// to the recomputed anchor and zeroes legacy fields. Future loads will
    /// resolve through the corrected lcmId directly without re-running the
    /// createdAt fallback.
    static func rewriteMarkerForHeal(_ marker: CompactMarker, newAnchor: RawMessage, lastRaw: RawMessage?) -> CompactMarker {
        let pastEnd = (lastRaw?.sortOrder ?? 0) + 1
        return CompactMarker(
            id: marker.id,
            sessionId: marker.sessionId,
            summary: marker.summary,
            firstKeptSortOrder: pastEnd,
            compactedCount: marker.compactedCount,
            createdAt: marker.createdAt,
            uiBoundarySortOrder: pastEnd,
            boundaryMessageId: nil,
            firstKeptMessageId: nil,
            lastCompactedMessageId: newAnchor.id,
            version: 2
        )
    }

    /// Compact all active history. Equivalent to "compact from the last active
    /// message" — under v2 semantics that single rule covers both /compact and
    /// long-press → "compact from here". Anchor = last active ChatMessage;
    /// everything from session-start (or prev marker's anchor + 1) up through
    /// the anchor is folded into a new marker's summary; agentHistory is not
    /// mutated. The kept tail (live anchor for the next turn) is whatever the
    /// user types next — there is no "auto-keep last N user turns" magic.
    func compactAll() {
        guard !isProcessing else {
            appendSystemInfo("Cannot compact while processing.", icon: "arrow.down.right.and.arrow.up.left")
            return
        }
        let activeMessages = messages.filter {
            $0.role != .compactDivider && $0.role != .systemInfo && !$0.isCompactedHistory
        }
        guard activeMessages.count > 1 else {
            appendSystemInfo("Not enough messages to compact.", icon: "arrow.down.right.and.arrow.up.left")
            return
        }
        guard let lastActive = activeMessages.last else { return }
        // includesBoundary=false: same path as long-press compactBefore. The
        // flag is preserved on the API for ABI compat with older v1 callers
        // but is ignored by the v2 anchor calculation.
        compactTask = Task { await compactBefore(lastActive.id, includesBoundary: false) }
    }

    /// Find the agentHistory index of the Nth-from-last user message that has
    /// visible text content (i.e. a user the user actually typed, not a
    /// tool_result-only synthetic row). Returns nil if fewer than `n` such
    /// messages exist.
    ///
    /// Used by compactAll to anchor "keep last N user turns" — we cut at the
    /// returned index, so everything strictly before it is the compacted
    /// range; from it onward stays as live inference anchors.
    func indexOfNthFromLastUserText(_ n: Int) -> Int? {
        indexOfNthFromLastUserText(n, upToIncluding: agentHistory.count - 1)
    }

    /// Variant that walks back from `upToIncluding` (an absolute agentHistory
    /// index) instead of the tail. Used by v2 effectiveAgentHistory to find
    /// the start of "last N user-text turns leading INTO the compact anchor."
    func indexOfNthFromLastUserText(_ n: Int, upToIncluding endIdx: Int) -> Int? {
        guard n > 0, endIdx >= 0, endIdx < agentHistory.count else { return nil }
        var seen = 0
        for i in stride(from: endIdx, through: 0, by: -1) {
            let msg = agentHistory[i]
            guard msg.role == .user else { continue }
            let hasText = msg.parts.contains { part in
                if case .text(let t) = part, !t.isEmpty { return true }
                return false
            }
            guard hasText else { continue }
            seen += 1
            if seen == n { return i }
        }
        return nil
    }

    /// Result of a bounded walk-back. `priorIdx` is the agentHistory index
    /// the caller should use as the start of preAnchor; `nil` means even the
    /// first user turn including anchor would exceed `maxMessages`, so
    /// preAnchor should be empty.
    struct WalkBackResult {
        let priorIdx: Int?
        let userTextTurnsFound: Int
        let messageCount: Int
        let stopReason: String  // "userTextTargetMet" | "messageCapWouldExceed" | "reachedStart"
    }

    /// Walk back from `anchorIdx` toward 0, deciding ONLY at user-message
    /// boundaries whether to include the next round. Stops when:
    /// - we've collected `maxUserTextTurns` user-text turns (success), OR
    /// - including the next user round would push total messages over
    ///   `maxMessages` (cap reason — don't split a user/assistant/tool round
    ///   in the middle, otherwise a tool_use would be orphaned without its
    ///   tool_result), OR
    /// - we hit index 0 (start of history).
    ///
    /// A "round" runs from one user message up to (but not including) the
    /// previous user message — i.e. assistant + tool_use/tool_result messages
    /// that follow a user message belong to that user's round.
    ///
    /// [T-ios-compact-orphan-toolcall] IMPORTANT: not every `user` message is a
    /// legal round boundary. Tool results are carried as `role: .user` messages
    /// (`AIChatViewModel:5566`), so cutting at one of those splits an
    /// assistant(tool_use) / user(tool_result) pair down the middle: the
    /// tool_use falls before `priorIdx` and is dropped, while its
    /// function_call_output survives inside preAnchor. OpenAI-compatible APIs
    /// reject that outright with
    ///     [400] No tool call found for function call output with call_id …
    /// and because the bad slice is recomputed identically on every retry, the
    /// whole conversation wedges — it fails across every fallback model and is
    /// unrecoverable without clearing the session. Field report 2026-08-13:
    /// `call_M1ate3tSzXCh3c1lr8QCsild`, priorIdx=24 landing on the tool_result
    /// whose tool_use sat at [23]. Boundaries are therefore restricted to user
    /// messages that carry NO toolResult part.
    func walkBackUserTurnsBounded(
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int
    ) -> WalkBackResult {
        guard anchorIdx >= 0, anchorIdx < agentHistory.count else {
            return WalkBackResult(priorIdx: nil, userTextTurnsFound: 0, messageCount: 0, stopReason: "invalidAnchor")
        }
        var acceptedPriorIdx: Int? = nil
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0

        // Scan strictly right-to-left. When we hit a user message, evaluate
        // "would accepting [thisUser ... anchorIdx] still fit?"
        for i in stride(from: anchorIdx, through: 0, by: -1) {
            let msg = agentHistory[i]
            guard msg.role == .user else { continue }
            // [T-ios-compact-orphan-toolcall] A user message carrying a
            // toolResult is the SECOND half of an assistant/tool round, not the
            // start of a new one. Cutting here strands its function_call_output
            // without the function_call. Skip it as a boundary candidate.
            let carriesToolResult = msg.parts.contains { part in
                if case .toolResult = part { return true }
                return false
            }
            if carriesToolResult { continue }
            let candidateMessageCount = anchorIdx - i + 1
            if candidateMessageCount > maxMessages {
                // Including this user round would exceed cap. Stop — keep
                // last accepted priorIdx (which is on an earlier-found user,
                // closer to anchor).
                return WalkBackResult(
                    priorIdx: acceptedPriorIdx,
                    userTextTurnsFound: acceptedUserTextTurns,
                    messageCount: acceptedMessageCount,
                    stopReason: "messageCapWouldExceed"
                )
            }
            // Accept this user as the new tentative priorIdx.
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            let hasText = msg.parts.contains { part in
                if case .text(let t) = part, !t.isEmpty { return true }
                return false
            }
            if hasText {
                acceptedUserTextTurns += 1
                if acceptedUserTextTurns >= maxUserTextTurns {
                    return WalkBackResult(
                        priorIdx: acceptedPriorIdx,
                        userTextTurnsFound: acceptedUserTextTurns,
                        messageCount: acceptedMessageCount,
                        stopReason: "userTextTargetMet"
                    )
                }
            }
        }
        return WalkBackResult(
            priorIdx: acceptedPriorIdx,
            userTextTurnsFound: acceptedUserTextTurns,
            messageCount: acceptedMessageCount,
            stopReason: "reachedStart"
        )
    }

    /// Wrap a compact summary in the `<context-summary>` envelope used both
    /// by the standalone `summaryAsAgentMessage` form (legacy) and by v2's
    /// inline injection into the next user message's content array.
    static func compactSummaryWrappedText(_ summary: String) -> String {
        """
        <context-summary>
        The following is a summary of the earlier conversation that was compacted to save context space.
        Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.

        \(summary)
        </context-summary>
        """
    }

    /// UI counterpart to `indexOfNthFromLastUserText` — used by Phase 2.5
    /// restore when the marker's lcmId is orphaned (DB rows the marker
    /// referenced have since been deleted / re-indexed by a sync migration).
    /// Returns the UI message index of the nth-from-last user message; the
    /// divider is placed before this index so the kept tail stays active and
    /// only the prefix is grayed. Returns 0 (no graying) when there are
    /// fewer than `keepUserTurns` user messages — same conservative behavior
    /// as compactBefore on tiny sessions.
    static func uiAnchorIndexForKeptTail(in messages: [ChatMessage], keepUserTurns n: Int) -> Int {
        guard n > 0, !messages.isEmpty else { return 0 }
        var seen = 0
        for i in stride(from: messages.count - 1, through: 0, by: -1) {
            let m = messages[i]
            // Only count user messages with non-empty content; matches the
            // agentHistory rule (skip tool-result-only user turns).
            guard m.role == .user, !m.content.isEmpty else { continue }
            seen += 1
            if seen == n { return i }
        }
        return 0
    }

    /// Compact all messages before the specified chat message.
    ///
    /// Phase B model (rule 1/2):
    ///   - Range = [prevMarker.firstKeptMessageId ..< userClickedBoundaryId) in agentHistory
    ///     (if no prevMarker, range = [0 ..< userClickedBoundaryId))
    ///   - Generate new summary via LLM using `previousSummary = cachedLatestMarker?.summary`
    ///     (merge strategy — new summary covers all history, old marker becomes archive)
    ///   - Write new marker with firstKeptMessageId + lastCompactedMessageId
    ///   - agentHistory is NOT mutated. Summary is injected at inference time via
    ///     effectiveAgentHistory().
    ///   - cachedLatestMarker is updated so subsequent agent loop iterations see it.

    /// Revert the most recent compact for this session.
    ///
    /// Drops the latest CompactMarker (its summary is discarded), refreshes
    /// the cached marker to whatever's left (if any), and triggers a UI
    /// rebuild. Effect by design:
    ///   - If a previous (older) marker exists, the divider snaps back to that
    ///     marker's anchor — the session shows what it looked like one
    ///     compact-step ago.
    ///   - If no previous marker exists, the session goes back to "no
    ///     compaction" — every message becomes active, the divider disappears,
    ///     and full agentHistory flows to the model again.
    ///
    /// Safe to call while idle. Refuses to run mid-stream so we don't yank
    /// context out from under an active agent loop.
    @MainActor
    func revertCompact() async {
        guard let sessionId else { return }
        guard !isProcessing else {
            logger.info("[Compact] revert refused: session is processing")
            appendSystemInfo("Cannot revert compact while a response is in progress.", icon: "arrow.uturn.backward")
            return
        }
        guard let marker = cachedLatestMarker else {
            logger.info("[Compact] revert: no marker to revert")
            appendSystemInfo("Nothing to revert — no compact marker on this session.", icon: "arrow.uturn.backward")
            return
        }

        logger.info("[Compact] ━━━ REVERT ━━━ session=\(sessionId.prefix(8)) markerId=\(marker.id.prefix(8)) v=\(marker.version) lcmId=\(marker.lastCompactedMessageId?.prefix(8) ?? "nil")")

        let deleted = await ChatStore.shared.deleteCompactMarker(id: marker.id)
        guard deleted else {
            logger.error("[Compact] revert: deleteCompactMarker returned false (marker.id=\(marker.id.prefix(8)))")
            appendSystemInfo("Revert failed: marker not found in DB.", icon: "arrow.uturn.backward")
            return
        }

        // Refresh cached marker to the next-most-recent one (or nil).
        let next = await ChatStore.shared.latestCompactMarker(sessionId: sessionId)
        self.cachedLatestMarker = next

        // Rebuild UI message list from DB to reflect the new (or absent)
        // marker. loadSession() re-runs Phase 2.5 restore against the
        // remaining markers, which will either:
        //   - find the previous marker and place the divider at its anchor, or
        //   - find no marker and ungray everything (no divider rendered).
        //
        // We deliberately DON'T inject a "Reverted ..." systemInfo row here.
        // The divider itself already conveys the post-revert state (either
        // the previous marker re-emerges as "N messages compacted", or all
        // dividers disappear when the last marker is gone). A separate
        // notice next to the divider is visually redundant — same anchor,
        // two stacked rows saying overlapping things.
        await loadSession()
        if let next {
            logger.info("[Compact] revert DONE: now showing previous marker id=\(next.id.prefix(8)) v=\(next.version)")
        } else {
            logger.info("[Compact] revert DONE: no remaining markers, full history active")
        }
    }

    @MainActor
    /// - allowDuringProcessing: normally compaction is a user-initiated action
    ///   that must not run mid-turn (the `!isProcessing` guard). The agent loop's
    ///   in-loop auto-compact [T-chat-auto-compact-inloop] passes true: it runs
    ///   WHILE processing, between iterations, and relies on the same
    ///   `isCompacting` re-entrancy guard below. compactBefore only reads/rewrites
    ///   agentHistory + the compact cache, which the loop consumes fresh via
    ///   effectiveAgentHistory() on its next API call — so no special resume
    ///   handoff is needed.
    func compactBefore(_ chatMessageId: UUID, includesBoundary: Bool = false,
                        allowDuringProcessing: Bool = false) async {
        guard allowDuringProcessing || !isProcessing else {
            logger.info("[Compact] Cannot compact while processing")
            appendSystemInfo("Cannot compact while processing.", icon: "arrow.down.right.and.arrow.up.left")
            return
        }
        guard !isCompacting else {
            logger.info("[Compact] Compaction already in progress")
            return
        }
        guard let sessionId else { return }

        // Find the boundary UI message.
        guard let boundaryIndex = messages.firstIndex(where: { $0.id == chatMessageId }) else { return }
        guard boundaryIndex > 0 else { return }
        let boundaryUIMsg = messages[boundaryIndex]

        logger.info("[Compact] ━━━ BEGIN compactBefore (Phase B id-first) ━━━")
        logger.info("[Compact] session=\(sessionId.prefix(8)) boundaryIndex=\(boundaryIndex) totalUIMessages=\(self.messages.count) totalHistory=\(self.agentHistory.count)")

        // Collect active UI messages before the boundary (for count/display only).
        let toCompactUI = messages[0..<boundaryIndex].filter {
            $0.role != .compactDivider && $0.role != .systemInfo && !$0.isCompactedHistory
        }
        guard !toCompactUI.isEmpty else {
            logger.info("[Compact] No active UI messages before boundary — aborting")
            return
        }

        // ───── Resolve boundary's dbMessageId (firstKeptMessageId for the new marker) ─────
        //
        // Priority 1: UI msg's sourceSortOrder → look up raw.id from DB
        //   (used for messages loaded from prior sessions)
        // Priority 2: Match the boundary UI message to an agentHistory entry by timestamp
        //   proximity — fall back to the last agentHistory entry of the same role
        //   at or before the UI boundary index (covers in-session messages where
        //   sourceSortOrder is not yet populated).
        //
        // When includesBoundary=true (compactAll), we don't need a real fkmId for
        // the marker, but we still need to locate the boundary in agentHistory to
        // size the compacted range.
        let allRaw = await ChatStore.shared.loadMessages(sessionId: sessionId)
        var firstKeptMessageId: String? = nil
        if let bso = boundaryUIMsg.sourceSortOrder,
           let rawMsg = allRaw.first(where: { $0.sortOrder == bso }) {
            firstKeptMessageId = rawMsg.id
        }

        // Fallback for in-session messages without sourceSortOrder: map UI →
        // agentHistory by scanning agentHistory in order for an entry whose role
        // matches and whose dbMessageId corresponds to a raw row persisted recently.
        // We take the LAST such entry to bias toward the end of history.
        //
        // [T-ios-grok-context-underestimate] This is the EXPECTED path for
        // in-loop auto-compaction, not a failure. `sourceSortOrder` is only
        // ever assigned by loadSession when hydrating UI messages from the DB
        // (AIChatViewModel+Persistence ~314/337); a message created during the
        // current run never carries one. The in-loop caller anchors on
        // `messages.last(...)` (AIChatViewModel ~4740) — the turn still being
        // streamed — so `bso` is nil by construction every single time, and
        // "last agentHistory entry with a dbMessageId" is the correct boundary
        // rather than a degraded guess. Logged at debug because a user report
        // of "9 compactions, 9 fallbacks" read as a 100% failure rate when it
        // was in fact 100% expected.
        if firstKeptMessageId == nil {
            if let lastMatching = agentHistory.last(where: { $0.dbMessageId != nil }) {
                firstKeptMessageId = lastMatching.dbMessageId
                logger.debug("[Compact] boundaryUIMsg.sourceSortOrder was nil (expected for in-session/in-loop boundaries) — using last agentHistory entry with dbMessageId=\(lastMatching.dbMessageId?.prefix(8) ?? "?")")
            }
        }

        let boundaryIdx: Int
        if let fkmId = firstKeptMessageId,
           let idx = agentHistory.firstIndex(where: { $0.dbMessageId == fkmId }) {
            boundaryIdx = idx
        } else if includesBoundary {
            // compactAll: no boundary needed — compact the entire history.
            boundaryIdx = agentHistory.count
            logger.info("[Compact] compactAll with no resolvable boundary — compacting full agentHistory (\(self.agentHistory.count) entries)")
        } else {
            logger.error("[Compact] firstKeptMessageId=\(firstKeptMessageId?.prefix(8) ?? "nil") not present in agentHistory (count=\(self.agentHistory.count))")
            appendSystemInfo("Cannot compact: boundary not in memory history.", icon: "arrow.down.right.and.arrow.up.left")
            return
        }

        // ───── v2 anchor calculation ─────
        //
        // anchorIdx = the agentHistory index of the message that becomes this
        // marker's anchor. Semantics: "everything from the previous marker's
        // anchor (exclusive) up to and including agentHistory[anchorIdx] is
        // folded into this marker's summary."
        //
        // - compactBefore(X, includesBoundary: false): user long-pressed X
        //   meaning "fold everything up through this point." anchor = X.
        //   (Old code treated X as "first kept"; v2 unifies on "anchor is the
        //   last compacted message" so summary timing is consistent with
        //   compactAll.)
        // - compactAll: walk back N user-text turns; the message strictly
        //   before that point is the anchor (everything before/including it
        //   is folded; the last N user-text turns + their replies stay live).
        // v2 unified semantics: marker.anchor = the message the caller pointed
        // at (`chatMessageId`). Everything from session-start (or the previous
        // marker's anchor + 1) up to and including this message is folded.
        // `includesBoundary` is accepted for ABI compatibility but no longer
        // changes anchor calculation — both /compact (compactAll → last active
        // message) and long-press → "compact from here" go through the same
        // code path.
        let anchorIdx = boundaryIdx
        logger.info("[Compact] anchorIdx=\(anchorIdx) (caller-supplied message becomes the marker anchor; includesBoundary=\(includesBoundary) ignored in v2)")
        let endExclusive = anchorIdx + 1   // [start, anchorIdx] inclusive
        let fkmId: String = firstKeptMessageId ?? ""

        // Resolve compact range START.
        //
        // Merge strategy: always start from 0 so the LLM sees a contiguous
        // replay of all history (plus the previous summary) and produces a single
        // coherent new summary. Previous marker's range is implicitly re-processed,
        // but `previousSummaryText` is passed into generateCompactSummaryWithSplitting
        // so the LLM uses the compact form of old content and only reads the new
        // increment in full detail.
        let startIdx = 0

        guard startIdx < endExclusive else {
            logger.info("[Compact] empty range — aborting (startIdx=\(startIdx) endExclusive=\(endExclusive))")
            return
        }

        logger.info("[Compact] range: startIdx=\(startIdx) endExclusive=\(endExclusive) historyCount=\(endExclusive - startIdx)")

        isCompacting = true
        // [T-ios-inloop-compact-freeze] When invoked mid-agent-loop
        // (allowDuringProcessing=true) isProcessing is ALREADY true and must
        // stay true after compaction — the loop keeps running. The old
        // unconditional `defer { isProcessing = false }` fired the
        // "loop finished" didSet mid-loop (post-stop sync hold, deferred-
        // reload drain, tracker/badge bookkeeping), and left the rest of the
        // loop running with isProcessing=false: sync-driven
        // reloadMessagesFromDB was no longer deferred and could rebuild
        // `messages` wholesale, detaching the live streaming ChatMessage —
        // every later round persisted to DB but never rendered, and the real
        // loop end became a false→false no-op (no snapshot replay, tracker
        // never cleared → false ⏸️ badge). Restore the entry value instead.
        let wasProcessingOnEntry = isProcessing
        isProcessing = true

        // Sweep stale compact-status rows from prior attempts (e.g. a leftover
        // "Compaction failed: ..." or "Compaction cancelled." systemInfo from
        // a previous run). They are in-memory only (never persisted to DB)
        // and become misleading the moment the user retries — without this
        // sweep the chat shows the old failure message right next to the
        // new one. Only systemInfo rows are removed; .compactDivider rows
        // (real prior successful compactions) are preserved here and replaced
        // later only when this attempt succeeds.
        messages.removeAll { msg in
            msg.role == .systemInfo
                && msg.systemIcon == "arrow.down.right.and.arrow.up.left"
        }

        // Insert a systemInfo loading message
        let statusMsg = ChatMessage(role: .systemInfo, content: "Compacting conversation...")
        statusMsg.systemIcon = "arrow.down.right.and.arrow.up.left"
        statusMsg.isCompactLoading = true
        messages.append(statusMsg)

        defer {
            isCompacting = false
            isProcessing = wasProcessingOnEntry
            compactTask = nil
        }

        // Slice to compact. Skip messages already folded by the previous
        // marker (its anchor + everything before).
        //
        // v2 semantics: prev.lastCompactedMessageId IS the anchor, and the
        // prev marker covers [0, prevAnchorIdx] inclusive — so our new range
        // must start at prevAnchorIdx + 1.
        //
        // v1 fallback: prev.firstKeptMessageId points to the FIRST KEPT
        // (post-compact) message, so v1 range starts AT that index (it was
        // exclusive on the right side of the compacted range).
        var effectiveStartIdx = startIdx
        if let prev = cachedLatestMarker {
            let prevAnchorOrFirstKept: String?
            let v1FallbackStartAtPrevIdx: Bool
            if prev.version >= 2, let anchor = prev.lastCompactedMessageId {
                prevAnchorOrFirstKept = anchor
                v1FallbackStartAtPrevIdx = false   // v2: start AFTER prev anchor
            } else {
                prevAnchorOrFirstKept = prev.firstKeptMessageId ?? prev.boundaryMessageId
                v1FallbackStartAtPrevIdx = true     // v1: prev.firstKept IS our start
            }
            if let prevId = prevAnchorOrFirstKept,
               let prevIdx = agentHistory.firstIndex(where: { $0.dbMessageId == prevId }) {
                let proposedStart = v1FallbackStartAtPrevIdx ? prevIdx : (prevIdx + 1)
                if proposedStart < endExclusive {
                    effectiveStartIdx = proposedStart
                    logger.info("[Compact] prev marker found (v\(prev.version)); effectiveStartIdx=\(effectiveStartIdx) (prevAnchor/firstKept=\(prevId.prefix(8)) at idx=\(prevIdx))")
                } else {
                    logger.info("[Compact] prev marker (id=\(prevId.prefix(8))) at idx=\(prevIdx) already covers our range (proposedStart=\(proposedStart) >= endExclusive=\(endExclusive)) — aborting")
                    statusMsg.content = "Already compacted up to this point."
                    statusMsg.isCompactLoading = false
                    return
                }
            }
        }

        guard effectiveStartIdx < endExclusive else {
            logger.info("[Compact] empty effective range — aborting (effectiveStartIdx=\(effectiveStartIdx) endExclusive=\(endExclusive))")
            statusMsg.content = "Nothing to compact."
            statusMsg.isCompactLoading = false
            return
        }

        let toCompact = Array(agentHistory[effectiveStartIdx..<endExclusive])
        let historyCount = toCompact.count

        // Merge-strategy previousSummary: the old summary (from the cached marker) is passed
        // to the LLM so it can fold prior compressed history into the new summary.
        let previousSummaryText: String? = cachedLatestMarker?.summary

        // Generate summary via LLM (auto-splits if too large for context window)
        let summary: String
        do {
            try Task.checkCancellation()
            summary = try await generateCompactSummaryWithSplitting(
                messages: toCompact,
                statusMsg: statusMsg,
                previousSummary: previousSummaryText
            )
            try Task.checkCancellation()
        } catch is CancellationError {
            logger.info("[Compact] Cancelled by user")
            statusMsg.content = "Compaction cancelled."
            statusMsg.isCompactLoading = false
            return
        } catch {
            // [T-compact-segment-retry-any-error] Reaching here means the
            // segment retry is EXHAUSTED, not that the first attempt failed:
            // `generateCompactSummaryWithSplitting` only rethrows once it can no
            // longer split (a single indivisible message, or depth 3 / 8 leaf
            // segments), or when the error is one splitting cannot fix
            // (cancelled / offline — see `isSegmentRetryableError`). So this is
            // the point where showing the server's own words is genuinely
            // useful rather than premature.
            // Distinguish the two ways we get here, so the message never claims
            // a retry that did not happen. Device testing surfaced this: pulling
            // the mock server's plug produced "failed after retrying in
            // segments" for an NSURLErrorDomain -1004 that was (correctly) never
            // retried at all.
            let didSegment = Self.isSegmentRetryableError(error)
            logger.error("[Compact] Summary generation failed (segmented=\(didSegment)): \(error)")
            statusMsg.content = didSegment
                ? "Compaction failed after retrying in segments: \(error.localizedDescription)"
                : "Compaction failed: \(error.localizedDescription)"
            statusMsg.isCompactLoading = false
            return
        }

        // ───── Compute marker fields (v2) ─────
        //
        // v2 model: lastCompactedMessageId is the ONLY anchor — a real,
        // persisted, UI-visible message id. agentHistory[lcmIdx + 1...] is the
        // active region (anchor + new msgs). All v1 multi-field bookkeeping
        // (firstKeptMessageId, boundaryMessageId, sortOrder fallbacks) is
        // skipped on the read side; we still persist them here so a downgrade
        // / older device that hits this row reads sensible defaults.
        //
        // Walk back from endExclusive looking for the first agentHistory entry
        // that already has a persisted dbMessageId AND is present in DB. This
        // avoids the past failure mode where lcmId pointed at a transient
        // row id that was never persisted (or was deleted by a later prune).
        var lcmIdResolved: String? = nil
        var lcmHistoryIdx: Int? = nil
        do {
            var i = endExclusive - 1
            while i >= 0 {
                if let id = agentHistory[i].dbMessageId,
                   allRaw.contains(where: { $0.id == id }) {
                    lcmIdResolved = id
                    lcmHistoryIdx = i
                    break
                }
                i -= 1
            }
        }
        guard let lastCompactedMessageId = lcmIdResolved else {
            logger.error("[Compact] Cannot write v2 marker: no agentHistory entry in [0..\(endExclusive)) has a persisted dbMessageId. Aborting compact.")
            statusMsg.content = "Compaction failed: could not anchor marker to a persisted message."
            statusMsg.isCompactLoading = false
            return
        }
        if lcmHistoryIdx != endExclusive - 1 {
            logger.warning("[Compact] v2 marker lcm anchor walked back from idx=\(endExclusive - 1) to idx=\(lcmHistoryIdx ?? -1) (closest persisted message). Some unsynced tail entries will fall on the active side of the divider.")
        }

        // Legacy fields are written with neutral / past-the-end values for
        // cross-version compatibility. Older builds reading this v2 row will
        // see boundary fallbacks pointing past the live tail (= "everything
        // compacted, nothing kept" — graceful degradation, never overlap).
        // New builds (v2) ignore these and use lastCompactedMessageId only.
        let legacyPastEndSortOrder = (allRaw.last?.sortOrder ?? 0) + 1

        let marker = CompactMarker(
            id: UUID().uuidString,
            sessionId: sessionId,
            summary: summary,
            firstKeptSortOrder: legacyPastEndSortOrder,
            compactedCount: historyCount,
            createdAt: Date(),
            uiBoundarySortOrder: legacyPastEndSortOrder,
            boundaryMessageId: nil,
            firstKeptMessageId: nil,
            lastCompactedMessageId: lastCompactedMessageId,
            version: 2
        )
        logger.info("[Compact] Persisting v2 marker: id=\(marker.id.prefix(8)) lcmId=\(lastCompactedMessageId.prefix(8)) lcmHistoryIdx=\(lcmHistoryIdx ?? -1)/agentHistory.count=\(self.agentHistory.count) historyCount=\(historyCount) includesBoundary=\(includesBoundary)")
        await ChatStore.shared.insertCompactMarker(marker)

        // Phase B: update cache so effectiveAgentHistory() starts using the new summary immediately.
        self.cachedLatestMarker = marker

        // Phase B: do NOT mutate agentHistory. It stays full; summary is synthesized
        // at inference time via effectiveAgentHistory().
        logger.info("[Compact] agentHistory untouched (Phase B): \(self.agentHistory.count) entries")

        // Update UI: remove the loading statusMsg, remove old dividers, then insert
        // a new divider. The divider goes AFTER the last compacted UI message
        // (= the UI row matching marker.lastCompactedMessageId). Anything above
        // the divider is grayed; anything below stays active (the kept tail
        // for compactAll, or the user's clicked boundary onward for compactBefore).
        //
        // Old dividers from earlier compact passes are removed unconditionally —
        // a session shows at most one compact divider (the latest marker).
        messages.removeAll { $0.id == statusMsg.id }
        let dividersBefore = messages.filter { $0.role == .compactDivider }.count
        messages.removeAll { $0.role == .compactDivider }
        // Also drop any stale compact-status systemInfo rows that survived
        // (shouldn't normally happen — start-of-run sweep already removed them
        // — but defends against any code path that appended one between then
        // and now). Belt-and-suspenders for the "two markers" report.
        messages.removeAll { msg in
            msg.role == .systemInfo
                && msg.systemIcon == "arrow.down.right.and.arrow.up.left"
        }

        // Locate divider insert position. v2: divider goes immediately AFTER
        // the UI row matching the marker's anchor (lastCompactedMessageId) —
        // anchor row + everything before it become grayed history; everything
        // below stays active.
        let dividerInsertIdx: Int
        if let lcmRaw = allRaw.first(where: { $0.id == lastCompactedMessageId }),
           let uiIdx = messages.firstIndex(where: { $0.sourceSortOrder == lcmRaw.sortOrder }) {
            dividerInsertIdx = uiIdx + 1
        } else if let bIdx = messages.firstIndex(where: { $0.id == chatMessageId }) {
            // Anchor's UI row not yet present (in-session messages without
            // sourceSortOrder). Fall back to the user-clicked boundary +1
            // since v2 includes the clicked message in the compacted range.
            dividerInsertIdx = bIdx + 1
        } else {
            dividerInsertIdx = messages.count
        }

        let compactedUICount = messages[0..<dividerInsertIdx].filter {
            $0.role != .systemInfo && !$0.isCompactedHistory
        }.count
        let divider = ChatMessage(role: .compactDivider, content: "\(compactedUICount) messages compacted")
        divider.compactSummary = summary
        messages.insert(divider, at: dividerInsertIdx)

        // Gray out everything above the divider; the kept tail (below divider) stays active.
        var grayedCount = 0
        for i in 0..<dividerInsertIdx {
            if messages[i].role != .compactDivider && messages[i].role != .systemInfo {
                messages[i].isCompactedHistory = true
                grayedCount += 1
            }
        }
        logger.info("[Compact] UI update: divider at index \(dividerInsertIdx), grayed \(grayedCount) messages, removed \(dividersBefore) old dividers")

        // Log final state
        let keptUIMessages = messages.filter { !$0.isCompactedHistory && $0.role != .compactDivider && $0.role != .systemInfo }
        logger.info("[Compact] ━━━ COMPLETE ━━━")
        logger.info("[Compact] Summary: \(summary.count) chars, \(historyCount) history entries compacted")
        logger.info("[Compact] UI: \(toCompactUI.count) messages compacted (grayed), \(keptUIMessages.count) active messages kept")
        logger.info("[Compact] History (Phase B): \(self.agentHistory.count) entries total (unchanged, summary synthesized via effectiveAgentHistory)")
        logger.info("[Compact] DB: v2 marker anchorMessageId=\(lastCompactedMessageId.prefix(8)) (legacy fields nil)")

        // Unconditionally offload large tool results/file_write content in the
        // kept messages. After compaction, the remaining turns may still contain
        // heavy tool output that would bloat the context on the next API call.
        let activeModel: LLMModel
        if let binding = ProviderConfigStore.shared.binding(for: sessionId) {
            let eid: String
            switch binding.primarySource {
            case .directEntry(let id, _): eid = id
            case .group(_, let id): eid = id
            }
            activeModel = ProviderConfigStore.shared.entry(for: eid)?.model ?? selectedModel
        } else {
            activeModel = selectedModel
        }
        offloadContextIfNeeded(model: activeModel, lastContextTokens: 0, force: true)

        // Scroll to bottom so the user sees the compact divider and retained messages.
        forceScrollToBottom.send()

        // [T-compact-queued-drain] Success tail: run any prompts that were
        // enqueued while the compact was in flight. Previously only the
        // compactAndSend caller drained — /compact (compactAll) and the
        // long-press Compact-Before path left queued messages stuck in the
        // dashed "queued" style forever after "N messages compacted". The
        // drain task starts after this function returns, i.e. after the defer
        // above has reset isCompacting/isProcessing/compactTask to a clean
        // idle state. Failure exits intentionally don't drain (messages stay
        // queued and user-cancellable).
        // [T-ios-inloop-compact-freeze] Mid-loop invocation must NOT schedule
        // the drain: the still-running agent loop drains its own queue at the
        // injection points, and schedulePostCompactDrain would overwrite
        // `currentTask` (the loop's task handle — Stop would then cancel the
        // drain instead of the loop) and run a second loop concurrently.
        if !wasProcessingOnEntry {
            schedulePostCompactDrain()
        }
    }

    /// Build a text representation of messages for summarization.
    private func buildConversationTextForSummary(_ messages: [AgentMessage]) -> String {
        var lines: [String] = []
        for msg in messages {
            let role = msg.role == .user ? "User" : "Assistant"
            for part in msg.parts {
                switch part {
                case .text(let t):
                    if !t.isEmpty {
                        lines.append("[\(role)] \(t)")
                    }
                case .toolUse(_, let name, let input):
                    // Extract the most informative input fields for each tool type
                    var details: [String] = []
                    if let path = input["path"] as? String ?? input["file_path"] as? String {
                        details.append(path)
                    }
                    if let cmd = input["command"] as? String {
                        details.append(cmd)
                    }
                    if let dir = input["directory"] as? String, details.isEmpty {
                        details.append(dir)
                    }
                    if let content = input["content"] as? String, details.isEmpty {
                        details.append(String(content.prefix(200)))
                    }
                    lines.append("[Tool] \(name): \(details.joined(separator: " | "))")
                case .toolResult(_, let name, let content, let isError, _, _, _, _):
                    // Cap tool results to avoid bloating the summary input
                    let preview = String(content.prefix(500))
                    lines.append("[Result\(isError ? " ERROR" : "")] \(name): \(preview)")
                case .imageData:
                    lines.append("[\(role)] [Image attached]")
                }
            }
        }
        return lines.joined(separator: "\n")
    }

    /// Summarize messages, automatically splitting into chunks if conversation is too large.
    private func generateCompactSummaryWithSplitting(
        messages: [AgentMessage],
        statusMsg: ChatMessage,
        previousSummary: String? = nil,
        depth: Int = 0
    ) async throws -> String {
        var conversationText = buildConversationTextForSummary(messages)

        // Prepend previous summary so the LLM merges old + new into one summary
        if let prev = previousSummary {
            conversationText = "Previous context summary:\n\(prev)\n\nNew conversation to merge:\n\(conversationText)"
        }

        do {
            try Task.checkCancellation()
            return try await generateCompactSummary(conversationText: conversationText, statusMsg: statusMsg)
        } catch let error where Self.isSegmentRetryableError(error) && messages.count >= 2 && depth < 3 {
            // [T-compact-segment-retry-any-error] Split on ANY non-network,
            // non-cancellation failure — not just a recognised "context too
            // large" one.
            //
            // Why the widening: the old guard was `isContextTooLargeError`, a
            // substring match over nine hand-collected phrases ("token limit",
            // "prompt is too long", …). That list is a guess about how each
            // provider words an over-length refusal, and it is provably
            // incomplete — OpenMinis#133 reports
            // `[context_length_exceeded] Your input exceeds the context window
            // of this model`, whose only matching substring is "context window",
            // and which several providers emit with different wording again.
            // Every miss meant the split path was skipped and compaction failed
            // outright.
            //
            // Splitting is a safe response to an unrecognised error: the worst
            // case is that we spend two smaller LLM calls to reach the same
            // failure, and depth < 3 bounds that at 8 leaf calls. A summary
            // built from halves is never worse than no summary at all, which is
            // what the narrow guard produced. So the burden of proof is
            // inverted — retry unless the error is one where retrying is
            // pointless (offline / cancelled), rather than only when we happen
            // to recognise the phrasing.
            let mid = messages.count / 2
            let firstHalf = Array(messages[..<mid])
            let secondHalf = Array(messages[mid...])

            logger.info("[Compact] Retry segments: splitting \(messages.count) messages into \(firstHalf.count) + \(secondHalf.count) (depth=\(depth)) after error: \(String(describing: error).prefix(200))")
            // Surfaced verbatim so this retry is distinguishable from a plain
            // first-pass compaction in screenshots and bug reports.
            statusMsg.content = "Retry segments \(firstHalf.count)+\(secondHalf.count)..."

            let summary1 = try await generateCompactSummaryWithSplitting(messages: firstHalf, statusMsg: statusMsg, depth: depth + 1)
            try Task.checkCancellation()
            let summary2 = try await generateCompactSummaryWithSplitting(messages: secondHalf, statusMsg: statusMsg, depth: depth + 1)
            try Task.checkCancellation()

            // Join the partial summaries textually — the caller stores a single
            // summary string, so segmentation stays invisible downstream.
            //
            // This used to be a THIRD LLM call that re-summarised the two
            // partials. Dropped, because the size premise behind it does not
            // hold: each segment's output is already hard-capped at 8192 tokens
            // (`maxOutputTokens` in generateCompactSummary), so two partials are
            // at most ~16k — nowhere near a context boundary, and not worth
            // another round-trip to shrink.
            //
            // It was also the one genuinely fragile step. The merge call went
            // through `generateCompactSummary` directly, with no depth and no
            // split retry of its own: if it failed, the segments that had just
            // succeeded were thrown away with it. So the mechanism that exists
            // to rescue a failing compaction ended its own happy path on an
            // unprotected call. A string join cannot fail.
            //
            // What is lost is the merge prompt's cross-part editing — it asked
            // the model to prefer the newer half and to de-duplicate shared
            // background. Accepted: the parts are already ordered oldest-first,
            // which is the same signal in positional form, and each part is
            // internally coherent because it was summarised under the full
            // system prompt. A little repeated background beats losing the
            // whole summary to a failed merge.
            return summary1 + "\n\n" + summary2
        }
    }

    /// [T-compact-segment-retry-any-error] Should a failed summary attempt be
    /// retried by splitting the input in half?
    ///
    /// Everything EXCEPT the two cases where a smaller request cannot help:
    ///
    ///   * cancellation — the user (or a session switch) stopped the work; a
    ///     retry would fight that and `Task.checkCancellation()` would throw
    ///     again immediately anyway;
    ///   * network/offline — the request never reached a model, so the payload
    ///     size is irrelevant and splitting just doubles the failed round-trips.
    ///
    /// This deliberately REPLACES the old `isContextTooLargeError` substring
    /// allow-list ("token limit", "prompt is too long", …). That list tried to
    /// enumerate how every provider words an over-length refusal and was
    /// provably incomplete — OpenMinis#133's `context_length_exceeded` wording
    /// slipped past several of its variants — and each miss silently disabled
    /// the split path. A server-side 4xx/5xx we cannot classify is exactly the
    /// case where trying a smaller payload is worth one attempt.
    static func isSegmentRetryableError(_ error: Error) -> Bool {
        if error is CancellationError { return false }
        if let llm = error as? LLMError {
            switch llm {
            case .cancelled, .networkError:
                return false
            default:
                return true
            }
        }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain {
            // URLError covers offline / DNS / TLS / timeout — all payload-size
            // independent. `.cancelled` also arrives here when a stream is torn
            // down mid-flight.
            return false
        }
        return true
    }

    /// Call the current LLM to generate a compact summary.
    private func generateCompactSummary(conversationText: String, statusMsg: ChatMessage? = nil) async throws -> String {
        // Resolve the model entry the same way the chat path does — this falls back to the
        // default group when the session has no binding (e.g. sessions synced from iCloud on
        // another device, whose provider binding doesn't travel with the CloudKit record).
        guard let entry = resolveCurrentEntry() else {
            throw NSError(domain: "Compact", code: -1, userInfo: [NSLocalizedDescriptionKey: "No model available for summarization"])
        }

        let provider = try await Self.makeLLMProvider(for: entry)
        let contextWindow = effectiveContextWindow(for: entry.model)

        let systemPrompt = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the \
        conversation context window. The agent will read your summary as past context, then proceed \
        based on the user's NEXT message — your summary is background, not a standing work order. \
        Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — \
           "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" \
           or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; \
           if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path \
        references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. \
        Be concise but never lose information the agent needs.
        """

        // Estimate input tokens and cap maxTokens to fit in context window
        let inputEstimate = conversationText.count / 4 + 600  // rough: ~4 chars/token + system prompt
        let maxOutputTokens: Int
        if contextWindow > 0 {
            maxOutputTokens = max(1024, min(8192, contextWindow - inputEstimate))
        } else {
            maxOutputTokens = 4096
        }

        let compactUserMessage = """
        Compact this conversation into a context summary:

        \(conversationText)

        ---
        END OF CONVERSATION TO COMPACT.

        Now generate a structured context summary following the system prompt instructions. \
        Do NOT continue the conversation above — summarize it. Write everything in past tense, \
        framed as "what was discussed / what was done", NOT as an ongoing goal or todo list.
        """

        let stream = try await provider.streamMessage(
            messages: [LLMMessage(role: .user, content: compactUserMessage)],
            systemPrompt: systemPrompt,
            maxTokens: maxOutputTokens,
            temperature: nil   // let provider/model use its default
        )

        var responseText = ""
        var didTag = false
        for try await chunk in stream {
            // Tag the request the FIRST time the stream yields anything —
            // by then the provider has actually sent the wire request and
            // pushed it onto LastAPIRequestBody's ring. Tagging before
            // consuming the stream is too early (streamMessage returns a
            // lazy AsyncStream — no HTTP request is in flight yet) and
            // would pin some unrelated earlier ring entry as "compact".
            #if DEBUG
            if !didTag {
                LastAPIRequestBody.shared.tagLatest("compact")
                didTag = true
            }
            #endif
            switch chunk {
            case .text(let delta):
                responseText += delta
                statusMsg?.content = "Compacting conversation... (\(responseText.count) chars)"
            case .finished, .usage, .started:
                break
            }
        }

        guard !responseText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NSError(domain: "Compact", code: -2, userInfo: [NSLocalizedDescriptionKey: "LLM returned empty summary"])
        }

        return responseText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Create an AgentMessage that injects a compact summary into the conversation context.
    /// Used by v1 markers (which still inject the summary as a standalone user
    /// turn). v2 markers prefer inline injection into the first post-anchor
    /// user message via `compactSummaryWrappedText`.
    static func summaryAsAgentMessage(_ summary: String) -> AgentMessage {
        AgentMessage(role: .user, parts: [.text(compactSummaryWrappedText(summary))])
    }
}
