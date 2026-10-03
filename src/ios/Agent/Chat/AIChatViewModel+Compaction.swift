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
    func checkContextBeforeSend(site: String = "pre-send") -> ContextPolicy.CheckResult {
        guard let entry = resolveCurrentEntry() else { return .ok }
        let resolved = resolvedContextWindow(for: entry.model)
        let contextWindow = resolved.window
        guard contextWindow > 0 else { return .ok }

        let policy = ContextPolicy(contextWindow: contextWindow, isUserCap: resolved.isUserCap)
        // [T-ctx-measure-outbound] Judge the request that is about to go out.
        //
        // This used to be `max(estimateContextTokens(), lastReportedContextTokens())`.
        // The report is exact but describes the PREVIOUS request, and it was only
        // ever replaced by the next successful call — so right after a compaction
        // (or offload) it still held the pre-compaction size, won the max(), and
        // the in-loop guard (which runs before that next call) compacted again
        // on it until its cap, ending the turn with "Context is full" and zero
        // API calls. Measuring the outbound history, calibrated by the ratio the
        // provider's own count gave us, keeps the accuracy that motivated the
        // max() (T-ctx-trust-api-usage: system prompt, tools and CJK are now in
        // the estimate, and the ratio reproduces the report when nothing has
        // changed) while following every change to the history immediately.
        ensureContextFixedTokens()
        let m = contextMeasurement()
        let measured = m.measured
        let result = policy.check(estimatedTokens: measured, contextWindow: contextWindow)
        // [CtxMeter] decide — every capacity decision with its inputs, so a
        // field log can replay why a turn compacted, stopped or was sent.
        logger.info("[CtxMeter] decide site=\(site) model=\(entry.model.id) history=\(m.history) fixed=\(m.fixed) ratio=\(String(format: "%.3f", m.ratio))(\(m.source)) measured=\(measured) threshold=\(policy.compactThreshold) window=\(contextWindow) userCap=\(resolved.isUserCap) → \(String(describing: result))")
        let markerInfo: String
        if let m = cachedLatestMarker {
            let ageSec = Int(Date().timeIntervalSince(m.createdAt))
            markerInfo = "marker=\(m.id.prefix(8)) ageSec=\(ageSec) summaryChars=\(m.summary.count)"
        } else {
            markerInfo = "marker=nil"
        }
        logger.verbose("[CompactDiag] checkContextBeforeSend: model=\(entry.model) window=\(contextWindow) userCap=\(resolved.isUserCap) measured=\(measured) fixed=\(self.contextFixedTokens) ratio=\(String(format: "%.2f", self.contextCalibrationRatio())) compactThreshold=\(policy.compactThreshold) offloadThreshold=\(policy.offloadThreshold) exhaustedOnly=\(policy.exhaustedOnly) → \(String(describing: result)) | \(markerInfo) | agentHistory.count=\(self.agentHistory.count)")
        return result
    }

    // MARK: - Outbound context measurement [T-ctx-measure-outbound]

    /// The model the next request is judged for — the same resolution the send
    /// path and the capacity check use.
    func currentContextModelId() -> String? { resolveCurrentEntry()?.model.id }

    /// Calibration ratio for `modelId` (default: the current model).
    func contextCalibrationRatio(for modelId: String? = nil) -> Double {
        ContextSizeMeter.ratio(for: modelId ?? currentContextModelId(),
                               known: contextCalibrationRatios, lastLearned: lastLearnedCalibration)
    }

    /// Estimated size of the next request: the compaction-aware outbound
    /// history plus system prompt and tool schemas, scaled by the calibration
    /// ratio for the model that will serve it. This is the one number every
    /// capacity decision reads (compact guard, offload, `max_tokens`), so they
    /// can no longer disagree.
    ///
    /// Reads `effectiveAgentHistoryUncounted()` — the same history the request is
    /// built from, minus the per-call log line and orphan repair (a handful of
    /// tokens either way). It deliberately does NOT apply the request image
    /// budget: that can spill files to disk, and the calibration side measures
    /// the pre-budget history too, so the two stay comparable.
    func measureOutboundContextTokens(ratio: Double? = nil) -> Int {
        contextMeasurement(ratio: ratio).measured
    }

    /// The measurement with its parts, for the [CtxMeter] logs.
    struct ContextMeasurement {
        let history: Int, fixed: Int, ratio: Double, source: String, measured: Int
    }
    func contextMeasurement(ratio override: Double? = nil) -> ContextMeasurement {
        let history = ContextSizeMeter.estimateTokens(effectiveAgentHistoryUncounted())
        let model = currentContextModelId()
        let ratio = override ?? contextCalibrationRatio(for: model)
        let source = override != nil ? "forced"
            : ContextSizeMeter.ratioSource(for: model, known: contextCalibrationRatios, lastLearned: lastLearnedCalibration)
        return ContextMeasurement(history: history, fixed: contextFixedTokens, ratio: ratio, source: source,
                                  measured: ContextSizeMeter.calibrated(history + contextFixedTokens, ratio: ratio))
    }

    /// Record the estimate of the request being dispatched, so the provider's
    /// count for it can calibrate the meter. Returns the calibrated size, which
    /// the caller uses for `max_tokens`.
    @discardableResult
    func recordContextDispatch(history: [AgentMessage], model: LLMModel) -> Int {
        let modelId = model.id
        lastDispatchEstimate = ContextSizeMeter.estimateTokens(history) + contextFixedTokens
        // [T-ctx-overflow-attribute-dispatch] Remember WHICH model this request
        // went to, so a rejection is scored against it (see noteContextOverflow).
        lastDispatchModelId = modelId
        lastDispatchWindow = resolvedContextWindow(for: model).window
        lastDispatchRatio = contextCalibrationRatio(for: modelId)
        lastDispatchPredicted = ContextSizeMeter.calibrated(lastDispatchEstimate, ratio: lastDispatchRatio)
        return lastDispatchPredicted
    }

    /// Fold in the provider's count for the request recorded by
    /// `recordContextDispatch`, for the model that actually served it (a group
    /// fallback may have switched). Returns false when there was nothing to pair.
    @discardableResult
    func calibrateContextSize(reportedTokens: Int, servedModelId: String?) -> Bool {
        guard let sample = ContextSizeMeter.calibrationRatio(reported: reportedTokens,
                                                             estimated: lastDispatchEstimate) else { return false }
        // [T-ctx-ratio-smoothing] Blend into the model's OWN history only; a
        // ratio borrowed from another model is not evidence about this one.
        let own = servedModelId.flatMap { contextCalibrationRatios[$0] }
        let updated = ContextSizeMeter.smoothed(previous: own, sample: sample)
        if let servedModelId { contextCalibrationRatios[servedModelId] = updated }
        lastLearnedCalibration = updated
        // [CtxMeter] actual — the prediction made at dispatch vs what the
        // provider counted. `err` is the number to watch: it is how wrong the
        // capacity decision for this request could have been.
        let err = lastDispatchPredicted > 0
            ? Double(lastDispatchPredicted - reportedTokens) / Double(reportedTokens) * 100 : 0
        logger.info("[CtxMeter] actual model=\(servedModelId ?? "?") predicted=\(self.lastDispatchPredicted) reported=\(reportedTokens) err=\(String(format: "%+.1f", err))% estimate=\(self.lastDispatchEstimate) sample=\(String(format: "%.3f", sample)) ratio=\(String(format: "%.3f", self.lastDispatchRatio))→\(String(format: "%.3f", updated))\(own == nil ? " (first own sample)" : "")")
        return true
    }

    /// A provider rejected the request as too long. That is ground truth that we
    /// under-read it — the one thing a stale or borrowed ratio can get wrong in
    /// the unsafe direction — so raise this model's ratio until that request
    /// measures at least what the provider counted. The retry (or the next send)
    /// then sees the real size and compacts instead of being rejected again.
    /// Returns whether the error was a context-length rejection.
    @discardableResult
    func noteContextOverflow(errorText: String, modelId: String?) -> Bool {
        guard ContextSizeMeter.isContextOverflow(errorText) else { return false }
        // [T-ctx-overflow-attribute-dispatch] Score the rejection against the
        // model the rejected request was dispatched to. The session binding can
        // name a different model (a group member, or a mid-turn switch), and
        // its window then fails the stated-count plausibility band and pins
        // the WRONG model's ratio at the ceiling. Falls back to the binding
        // only when nothing was dispatched yet.
        let model = modelId ?? lastDispatchModelId ?? currentContextModelId()
        let window = lastDispatchWindow > 0
            ? lastDispatchWindow
            : (resolveCurrentEntry().map { resolvedContextWindow(for: $0.model).window } ?? 0)
        let current = contextCalibrationRatio(for: model)
        let requested = ContextSizeMeter.requestedTokens(inOverflowMessage: errorText)
        let raised = ContextSizeMeter.ratioAfterOverflow(current: current, estimated: lastDispatchEstimate,
                                                         requested: requested, window: window)
        if let model { contextCalibrationRatios[model] = raised }
        lastLearnedCalibration = raised
        // [T-ctx-valve-rearm] The ratio now rests on the provider's own count,
        // not an extrapolation, so the uncalibrated send-once is spent: firing
        // it would re-send the request that was just rejected.
        sentPastExtrapolatedLimitThisLoop = true
        logger.warning("[CtxMeter] rejected predicted=\(self.lastDispatchPredicted) — provider rejected the request as too long — calibration model=\(model ?? "?") \(String(format: "%.2f", current)) → \(String(format: "%.2f", raised)) (estimated=\(self.lastDispatchEstimate) statedTokens=\(requested.map(String.init) ?? "none") window=\(window)); the next attempt will compact")
        publishMeasuredContextUsage()
        return true
    }

    /// Re-derive calibration from the loaded transcript: for each model, the
    /// newest turn that recorded BOTH its report and our estimate of the same
    /// request. Only ratios are carried over, never a raw size, so a compaction
    /// or revert done since cannot make the next decision stale.
    /// Returns true when at least one pair was found.
    ///
    /// Reloading the SAME session keeps what this view model already learned.
    /// loadSession runs far more often than a session switch — the debug prompt
    /// path, a revert, re-opening the chat — and a ratio raised by a provider
    /// rejection exists only in memory (a rejected request stamps no pair).
    /// Wiping it on every reload was measured on device: the rejection raised
    /// the ratio 1.29 → 1.97, a reload reset it to 1.29, and the retry was sent
    /// uncompacted and rejected again. In-memory values are never older than the
    /// transcript's, so they win; a different session starts from scratch.
    @discardableResult
    func seedContextCalibration() -> Bool {
        let learned: ContextSizeMeter.CalibrationState? = sessionId != nil && calibrationSessionId == sessionId
            ? .init(ratios: contextCalibrationRatios, lastLearned: lastLearnedCalibration, fixedTokens: contextFixedTokens)
            : nil
        // [T-ctx-ratio-smoothing] Replay the persisted samples oldest → newest
        // through the same rule the live path uses, so a reload reproduces the
        // smoothed ratio rather than jumping to the newest raw sample.
        let samples = messages.compactMap { msg -> ContextSizeMeter.CalibrationSample? in
            guard msg.role == .assistant, let usage = msg.usage else { return nil }
            return .init(reported: usage.latestContextTokens, estimated: usage.estimatedRequestTokens,
                         fixedTokens: usage.estimatedFixedTokens, modelId: usage.calibrationModelId)
        }
        let seeded = ContextSizeMeter.replayCalibration(samples)
        let state = learned.map { seeded.carryingOver($0) } ?? seeded
        contextCalibrationRatios = state.ratios
        lastLearnedCalibration = state.lastLearned
        contextFixedTokens = state.fixedTokens
        lastDispatchEstimate = 0
        lastDispatchModelId = nil
        lastDispatchWindow = 0
        // [T-ctx-valve-retry-keeps-spent] A spent valve is evidence about THIS
        // session's requests; it must not carry into another session.
        if calibrationSessionId != sessionId { sentPastExtrapolatedLimitThisLoop = false }
        calibrationSessionId = sessionId
        logger.info("[CtxMeter] seed session=\(self.sessionId?.prefix(8) ?? "nil") samples=\(seeded.samples) keptInMemory=\(learned?.ratios.count ?? 0) ratios=\(state.ratios.map { "\($0.key)=\(String(format: "%.3f", $0.value))" }.sorted().joined(separator: ","))")
        return seeded.samples > 0
    }

    /// [T-ctx-warmup-fit] Trim a compaction's warm-up turns so the request fits
    /// under the compact line.
    ///
    /// A compaction keeps the last few user turns before its anchor as warm-up
    /// context. That is right while they are small, but when those turns are
    /// themselves large (long generated answers), or the model's tokenizer is
    /// denser than the one the session ran on, the summary plus warm-up can
    /// still be over the line. Every later compaction then keeps the same
    /// warm-up, so it makes no progress, and the session can never continue on
    /// that model — measured on a device with a 1.8x-denser model: compacted
    /// and still at 100% of the window.
    ///
    /// Only runs over budget, so a normal compaction's request (and its prompt
    /// cache prefix) is unchanged. Drops whole user-TEXT turns from the oldest
    /// end; tool results are `.user` messages too, and cutting between a call
    /// and its result would orphan it. Works in raw-estimate units so it can be
    /// called from inside the measurement without recursing into it.
    ///
    /// [T-ctx-warmup-trim-undecided] `decided` is false when there was nothing
    /// to measure against yet — no resolved entry, an unknown window, or the
    /// fixed prompt+tools size not seeded. The warm-up comes back unchanged
    /// then, and the caller must NOT cache that as this marker's answer:
    /// caching it pinned "drop nothing" for the marker's whole life, so a
    /// summary + warm-up over the line stayed over it until another compaction.
    func trimWarmUpToFit(_ warmUp: [AgentMessage], rest: [AgentMessage], summaryText: String) -> (kept: [AgentMessage], decided: Bool) {
        guard !warmUp.isEmpty else { return (warmUp, true) }
        guard let entry = resolveCurrentEntry() else { return (warmUp, false) }
        let resolved = resolvedContextWindow(for: entry.model)
        guard resolved.window > 0, contextFixedTokens > 0 else { return (warmUp, false) }
        let policy = ContextPolicy(contextWindow: resolved.window, isUserCap: resolved.isUserCap)
        let line = policy.compactThreshold > 0 ? policy.compactThreshold : resolved.window
        let budget = Int(Double(line) / contextCalibrationRatio(for: entry.model.id)) - contextFixedTokens
        let restTokens = ContextSizeMeter.estimateTokens(rest) + ContextSizeMeter.estimateTokens(summaryText)
        guard ContextSizeMeter.estimateTokens(warmUp) + restTokens >= budget else { return (warmUp, true) }

        let drop = ContextSizeMeter.warmUpDrop(
            sizes: warmUp.map { ContextSizeMeter.estimateTokens(message: $0) },
            startsTurn: warmUp.map { m in
                m.role == .user && !m.parts.contains { if case .toolResult = $0 { return true }; return false }
            },
            restTokens: restTokens, budget: budget)
        logger.info("[CtxMeter] warmup trimmed to fit: kept \(warmUp.count - drop)/\(warmUp.count) message(s) (budget=\(budget) raw tokens)")
        return (Array(warmUp.dropFirst(drop)), true)
    }

    /// Compaction can do no more for this request (budget spent, no progress, or
    /// no anchor). Above the compact THRESHOLD is still sendable — that line
    /// sits below the window by design — and an over-the-window verdict that
    /// rests only on the ratio gets one real request. The table and its tests
    /// live in `ContextPolicy.inLoopStep`.
    func settleWithoutCompacting() -> (step: ContextPolicy.InLoopStep, measurement: ContextMeasurement) {
        let m = contextMeasurement()
        let window = resolveCurrentEntry().map { resolvedContextWindow(for: $0.model).window } ?? 0
        let step = ContextPolicy.inLoopStep(verdict: .needsCompact, measured: m.measured, rawTokens: m.history + m.fixed,
                                            window: window, canCompact: false, ratio: m.ratio,
                                            uncalibratedSendUsed: sentPastExtrapolatedLimitThisLoop)
        return (step, m)
    }

    /// Refresh the composer's context glow from the measurement. The glow
    /// otherwise shows the last report, which after a compaction or revert is
    /// the size of a context that no longer exists until the next call returns.
    func publishMeasuredContextUsage() {
        ensureContextFixedTokens()
        let measured = measureOutboundContextTokens()
        guard measured > 0 else { return }
        publishContextUsage(liveContextTokens: measured)
    }

    /// Legacy compatibility — returns true if any intervention is needed before send.
    func needsCompactBeforeSend() -> Bool {
        checkContextBeforeSend() != .ok
    }

    /// The transient status rows a compaction attempt leaves behind
    /// ("Compacting conversation…", "Compaction failed: …", "Compaction
    /// cancelled.").
    ///
    /// [T-compact-retry-stale-status] These are in-memory only — never
    /// persisted — and describe ONE attempt. They must be swept whenever the
    /// conversation moves on, or a failure notice keeps sitting in the
    /// transcript describing work that is no longer in flight, right next to
    /// the output of whatever ran after it. `compactBefore` sweeps them on
    /// entry (so a retried compaction doesn't show old and new side by side);
    /// `retry()` sweeps them too, because an assistant retry is the other way
    /// the conversation moves past a failed compaction.
    ///
    /// `.compactDivider` rows are deliberately NOT touched: those record real,
    /// completed compactions and are replaced only when a new one succeeds.
    func clearCompactStatusRows() {
        messages.removeAll { msg in
            msg.role == .systemInfo
                && msg.systemIcon == "arrow.down.right.and.arrow.up.left"
        }
    }

    /// Compact then send the pending message.
    func compactAndSend() {
        showCompactBeforeSendPrompt = false
        let text = pendingSendText ?? ""
        let atts = pendingSendAttachments
        // [T-programmatic-prompt-no-composer] Consume the provenance with the
        // text: the eventual send() must know whether this was the composer's.
        let fromComposer = pendingSendIsFromComposer
        pendingSendText = nil
        pendingSendAttachments = []
        pendingSendIsFromComposer = false

        // Show the message as queued immediately
        // [T-paste-live-bubble-card] Same birth-time plan as send()/enqueue:
        // literal stays in the bubble text, cards are born with the row, the
        // drain writes files to the planned ids.
        let pastePlanned = planPastedCards(for: text)
        let queuedPrompt = QueuedPrompt(text: text, attachments: atts,
                                        pastePlan: pastePlanned.plan)
        promptQueue.append(queuedPrompt)
        let chatMsg = ChatMessage(role: .user, content: text, isQueued: true)
        chatMsg.queuedPromptId = queuedPrompt.id
        chatMsg.inputAttachments = atts
        chatMsg.attachments = pastePlanned.metas
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
            attachments = atts
            skipCompactCheck = true
            // A composer-sourced text is sent the composer way (it is still the
            // draft being committed, and send() clears it); a programmatic one
            // rides the argument and leaves the composer alone.
            if fromComposer {
                inputText = text
                send()
            } else {
                send(overrideText: text)
            }
            return
        }

        let target = lastActive
        startCompactTask(origin: .beforeSend) { [self] in
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

    /// [T-ios-compact-task-binding] Start a compaction as the session's tracked
    /// `compactTask`.
    ///
    /// Every entry point must go through here. Before this existed, only
    /// `/compact` and compact-and-send assigned `compactTask`; the long-press
    /// menu and the debug RPC spawned bare `Task { await compactBefore(...) }`,
    /// which nothing retained. `cancel()`'s `compactTask?.cancel()` therefore
    /// hit `nil` for those, so Stop could not interrupt them and a hung stream
    /// left `isCompacting == true` — the guard at the top of `compactBefore`
    /// then silently rejected every later attempt, and only re-opening the
    /// session (loadSession's forced reset) recovered it.
    ///
    /// Routing construction through one function is what keeps that from
    /// regressing: a new caller gets the binding by construction rather than by
    /// remembering to add it.
    ///
    /// The mid-loop auto-compaction is deliberately NOT routed here — see the
    /// note at its call site in the agent loop.
    @discardableResult
    func startCompactTask(
        origin: CompactOrigin,
        _ body: @escaping @MainActor () async -> Void
    ) -> Task<Void, Never> {
        let task = Task { @MainActor [weak self] in
            await body()
            // Clear the origin only if this task still owns the slot; a newer
            // compaction may already have replaced it.
            guard let self, self.compactTask == nil else { return }
            self.compactOrigin = nil
        }
        compactTask = task
        compactOrigin = origin
        return task
    }

    /// Cancel the compact-before-send prompt, restoring text to input.
    func cancelCompactBeforeSend() {
        showCompactBeforeSendPrompt = false
        // [T-programmatic-prompt-no-composer] Only the user's own draft goes
        // back into the composer. A job/CLI/Shortcut prompt can be parked here
        // too, and restoring THAT would put text the user never typed into
        // their input box — the very clobber this work removes, one step later.
        if pendingSendIsFromComposer {
            inputText = pendingSendText ?? ""
            attachments = pendingSendAttachments
        }
        pendingSendText = nil
        pendingSendAttachments = []
        pendingSendIsFromComposer = false
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
        startCompactTask(origin: .slashCommand) { [self] in
            await compactBefore(lastActive.id, includesBoundary: false)
        }
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
        // A manual compaction runs without isProcessing. Reverting under it
        // deleted the current marker just before the compaction wrote a new one
        // summarising the pre-revert context. Android refuses the same way.
        guard !isCompacting else {
            logger.info("[Compact] revert refused: compaction in progress")
            appendSystemInfo("Cannot revert compact while compaction is in progress.", icon: "arrow.uturn.backward")
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
        // [T-ctx-measure-outbound] The history just grew back. loadSession
        // re-seeded the calibration; show the restored size rather than the
        // report from the compacted context, which would under-read it.
        publishMeasuredContextUsage()
        logger.info("[CtxMeter] reverted marker=\(marker.id.prefix(8)) measured=\(self.measureOutboundContextTokens())")
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
        // [T-ios-compact-model-fallback] Per-RUN state: a model that was out of
        // quota an hour ago may be fine now, so the burn list must not persist
        // across compactions. `isCompacting` above makes runs non-overlapping,
        // so a plain reset here is safe.
        Self.compactFailedEntryIds.removeAll()

        // Find the boundary UI message.
        guard let boundaryIndex = messages.firstIndex(where: { $0.id == chatMessageId }) else { return }
        guard boundaryIndex > 0 else { return }
        let boundaryUIMsg = messages[boundaryIndex]

        let compactMeasuredBefore = measureOutboundContextTokens()
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
        clearCompactStatusRows()

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
            // [T-ios-compact-stale-index] Clamp to the CURRENT end of
            // agentHistory, not the `endExclusive` captured before the await.
            //
            // `endExclusive` is computed near the top of this function, but the
            // summary is generated by an `await` that can run for many seconds.
            // Everything here is @MainActor, so this is not a data race — it is
            // a STALE INDEX: while the LLM call is suspended, the user can run
            // any main-actor mutation that shrinks agentHistory. Deleting
            // messages does exactly that (removeAll / removeSubrange in
            // AIChatViewModel), which is what the reporter did ("I was
            // compacting the conversation, then deleted the conversation").
            // `endExclusive - 1` then indexes past the end and Swift traps —
            // "Index out of range" at this line, EXC_BREAKPOINT.
            //
            // Clamping (rather than aborting) keeps the compaction that already
            // paid for an LLM round-trip: the walk-back only needs SOME entry
            // with a persisted dbMessageId at or before the requested end, and
            // any surviving entry still satisfies that. If the array was
            // emptied entirely the loop body never runs and the `guard` below
            // aborts cleanly with its existing diagnostic.
            var i = min(endExclusive, agentHistory.count) - 1
            if i != endExclusive - 1 {
                logger.warning("[Compact] agentHistory shrank during summary generation (endExclusive=\(endExclusive) → count=\(self.agentHistory.count)); clamping the marker walk-back")
            }
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
        clearCompactStatusRows()

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

        // [T-ctx-measure-outbound] The glow still shows the last report — the
        // size of the context we just replaced. Show what will actually be sent.
        // [T-ctx-usage-after-compact] …and refresh the placeholder line too.
        announceContextUsageAfterCompaction()
        logger.info("[CtxMeter] compacted marker=\(marker.id.prefix(8)) measured \(compactMeasuredBefore)→\(self.measureOutboundContextTokens())")

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
                case .toolUse(_, let name, let input, _):
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
                case .toolResult(_, let name, let content, let isError, _, _, _, _, _):
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
        // [T-paste-single-split] agentHistory carries fully-expanded pasted
        // text (consumed at the draft→message boundary), so the summary input
        // needs no placeholder resolution here.
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
            case .rateLimited, .invalidAPIKey:
                // [T-ios-compact-model-fallback] Quota / auth exhaustion is not
                // a size problem, so halving the input cannot fix it — it just
                // re-runs a dead model on smaller inputs, up to 8 leaf calls,
                // and fails anyway. The fallback loop in generateCompactSummary
                // has already walked every candidate by the time one of these
                // escapes, so reaching here means there is nothing left to try.
                return false
            case .transientError:
                // [T-ios-compact-no-timeout] Our stall/overall deadlines land
                // here. Splitting is exactly the WRONG response: the halves
                // inherit the same deadlines, so one idle-stall timeout can
                // multiply into up to eight of them. Splitting only helps when
                // the input was too LARGE, which a timeout does not establish.
                return false
            default:
                // Deliberately still true: `providerError` covers the
                // context-length rejections that splitting genuinely fixes.
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

    // MARK: - [T-ios-compact-model-fallback] Model fallback for compaction

    /// Entries whose compact call has already failed in THIS compaction run.
    ///
    /// Two jobs. It stops the fallback loop cycling back onto a model that just
    /// returned 429/402/5xx, and — because it outlives the loop for the
    /// duration of the run — it also stops a re-resolve (the user switching
    /// group mid-compact, or the split path re-entering) from picking the same
    /// dead entry again. Cleared at the start of every compaction.
    private static var compactFailedEntryIds: Set<String> = []

    /// Candidate entries to try for a compact call, best first.
    ///
    /// Mirrors the chat path's routing rather than inventing a second policy:
    ///   * a group-bound session walks its group via `ModelGroupRouter`, which
    ///     already filters hidden / disabled / credential-less members;
    ///   * a DIRECT-entry session has no group to walk, so it borrows the
    ///     default primary group's members as fallbacks (requirement 3). Without
    ///     this a hand-picked single model had nowhere to go and compaction
    ///     simply died with it.
    /// Entries that already failed this run are dropped, not merely
    /// deprioritised — retrying a model that just reported an exhausted quota
    /// spends a round-trip to learn what we were told a moment ago.
    private func compactFallbackCandidates(startingAt first: ModelEntry) -> [ModelEntry] {
        let store = ProviderConfigStore.shared
        var ordered: [ModelEntry] = [first]
        var seen: Set<String> = [first.id]

        func appendGroupMembers(_ groupId: String?) {
            guard let groupId, let group = store.group(for: groupId) else { return }
            var cursor = first.id
            // Walk the ring once; `nextFallback` wraps, so bound the walk by
            // the member count instead of trusting it to terminate.
            for _ in 0..<max(1, group.memberEntryIds.count) {
                guard let nextId = ModelGroupRouter.nextFallback(
                    group: group, currentEntryId: cursor, store: store) else { break }
                cursor = nextId
                if seen.contains(nextId) { continue }
                guard let e = store.entry(for: nextId) else { continue }
                seen.insert(nextId)
                ordered.append(e)
            }
        }

        if let sid = sessionId, let binding = store.binding(for: sid),
           case .group(let groupId, _) = binding.primarySource {
            appendGroupMembers(groupId)
        } else {
            // Direct entry (or no binding): borrow the default primary group.
            appendGroupMembers(store.defaultPrimaryGroupId)
        }

        let usable = ordered.filter { !Self.compactFailedEntryIds.contains($0.id) }
        // Never return empty: if every candidate is burned, let the caller try
        // the primary once more and surface its real error rather than a
        // synthetic "no model" that hides what actually happened.
        return usable.isEmpty ? [first] : usable
    }

    /// Generate a compact summary, walking model candidates when one is
    /// exhausted or failing.
    ///
    /// Previously this was a single call on `resolveCurrentEntry()`. When that
    /// model was rate-limited or out of balance the error propagated to
    /// `generateCompactSummaryWithSplitting`, which classified it as
    /// segment-retryable and halved the input — re-running the SAME dead model
    /// up to eight times. Splitting only helps when the input is too large; a
    /// 429 is not a size problem, so that was pure round-trip waste ending in
    /// failure anyway.
    ///
    /// Each candidate gets its own attempt, so a model that dies first cannot
    /// consume the budget of the ones after it.
    private func generateCompactSummary(conversationText: String, statusMsg: ChatMessage? = nil) async throws -> String {
        // Resolve the model entry the same way the chat path does — this falls back to the
        // default group when the session has no binding (e.g. sessions synced from iCloud on
        // another device, whose provider binding doesn't travel with the CloudKit record).
        //
        // [T-ios-compact-model-fallback] Resolved fresh on every call, and the
        // resolve cache is keyed on `configRevision` / `authRevision`, so a
        // model or group the user switches to mid-compact is picked up
        // immediately rather than served from a stale entry (requirement 3).
        guard let primary = resolveCurrentEntry() else {
            throw NSError(domain: "Compact", code: -1, userInfo: [NSLocalizedDescriptionKey: "No model available for summarization"])
        }

        let candidates = compactFallbackCandidates(startingAt: primary)
        var lastError: Error?
        for (idx, entry) in candidates.enumerated() {
            do {
                if idx > 0 {
                    logger.info("[Compact] falling back to candidate \(idx + 1)/\(candidates.count): \(entry.model.id)")
                    // Requirement 5: the status line names the model actually
                    // doing the work, so a long compact on a fallback model is
                    // not mistaken for the primary being slow.
                    statusMsg?.content = "Compacting with \(entry.model.displayName)..."
                }
                let summary = try await generateCompactSummaryOnce(
                    conversationText: conversationText, entry: entry, statusMsg: statusMsg)
                if idx > 0 {
                    // Requirement 2: a fallback that SUCCEEDS becomes the
                    // session's effective model, exactly as the agent loop does
                    // — otherwise the next chat turn would go straight back to
                    // the model we just proved is unusable.
                    noteEffectiveEntry(entry.id)
                    logger.info("[Compact] fallback succeeded on \(entry.model.id) — adopted as effective entry")
                }
                return summary
            } catch let error as LLMError where error.isFallbackable || error.isServerCapacityTransient {
                // Quota / auth / capacity: this model cannot serve the request
                // at all, so move on rather than retrying or splitting.
                Self.compactFailedEntryIds.insert(entry.id)
                lastError = error
                logger.warning("[Compact] candidate \(entry.model.id) failed (fallbackable): \(error.localizedDescription)")
                continue
            }
            // Any other error (size, decode, cancellation, network) belongs to
            // the caller: splitting or aborting is the right response there, and
            // walking to another model would not help.
        }
        throw lastError ?? NSError(domain: "Compact", code: -2,
            userInfo: [NSLocalizedDescriptionKey: "All model candidates failed to summarize"])
    }

    /// One compact attempt against one specific entry.
    private func generateCompactSummaryOnce(
        conversationText: String,
        entry: ModelEntry,
        statusMsg: ChatMessage? = nil
    ) async throws -> String {
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

        // [T-ios-compact-oversize-request] Budget the request BEFORE sending it.
        //
        // The old code computed `max(1024, min(8192, contextWindow - inputEstimate))`.
        // When the input alone exceeded the window that inner term went
        // NEGATIVE and `max` quietly clamped it back to 1024 — so an
        // impossible request was sent anyway, with no error and no log, and
        // the failure only surfaced as a provider rejection. That rejection
        // was then treated as segment-retryable, multiplying one doomed
        // request into as many as eight.
        //
        // Compaction is uniquely prone to this: it replays the full history
        // (see the `startIdx = 0` note in compactBefore) plus any accumulated
        // previous summary, so the compact request is systematically LARGER
        // than the chat request whose size triggered it.
        //
        // Reserve `compactOutputReserve` for the summary the model must still
        // produce; if the input cannot fit alongside it, throw a size error.
        // `isSegmentRetryableError` classifies that as retryable, so the
        // existing split-in-half path takes over and each half is re-checked
        // here — the pre-flight and the split retry cooperate rather than
        // duplicate each other.
        let compactOutputReserve = 1024
        // ~4 chars/token, plus the system prompt and the fixed wrapper text
        // that `compactUserMessage` puts around the conversation below.
        let inputEstimate = conversationText.count / 4 + 600 + 200
        let maxOutputTokens: Int
        if contextWindow > 0 {
            let available = contextWindow - inputEstimate
            guard available >= compactOutputReserve else {
                logger.error("[Compact] pre-flight: input ~\(inputEstimate) tok exceeds window \(contextWindow) (needs \(compactOutputReserve) for output) — not sending")
                throw LLMError.providerError(
                    message: "compact input too large: ~\(inputEstimate) tokens estimated against a \(contextWindow)-token window"
                )
            }
            maxOutputTokens = min(8192, available)
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

        // [T-ios-compact-no-timeout] Two independent deadlines guard this loop.
        // Without them a summary request that goes quiet simply hangs: the
        // provider sessions only set `timeoutIntervalForRequest` (600s), which
        // is an INTER-PACKET idle timeout, and none of them set
        // `timeoutIntervalForResource` — so a stream that dribbles a byte
        // occasionally, or one frozen by app suspension, never trips anything.
        //
        //   stallDeadline  — 120s since the last chunk (IDLE timeout — reset by
        //                    every chunk via progress.touch()). Matches the main
        //                    stream's watchdog (see AIChatViewModel+SSEStream);
        //                    this is the PRIMARY detector: a stream that keeps
        //                    yielding data, however slowly, never trips it.
        //   overallDeadline — 900s (15 min) wall-clock BACKSTOP for a truly
        //                    runaway stream. [T-compact-idle-timeout] This used
        //                    to be 180s and acted as the main judge, which
        //                    killed perfectly healthy long summaries: a large
        //                    conversation at ~40 tok/s legitimately needs
        //                    several minutes while data flows the whole time
        //                    (Android reproduced the exact failure — cancelled
        //                    at 150s with 7488 SSE events already received).
        //                    Android uses the same 120s idle + 900s backstop.
        //
        // The deadlines are enforced by a SEPARATE watchdog task, not by checks
        // inside the consuming loop: the failure being fixed is a stream that
        // stops yielding, and an in-loop check never runs in exactly that case
        // (the `for await` is parked on the next element). The watchdog polls a
        // shared timestamp and cancels the consumer when a deadline passes.
        //
        // Timestamps are WALL-CLOCK (Date), deliberately not sleep-relative:
        // compaction can run while the app is backgrounded, where `Task.sleep`
        // gets stretched by CPU throttling — the trap documented in
        // AIChatViewModel+SSEStream and fixed the same way in BrowserTabPool.
        // The watchdog still sleeps, but only to wake up and COMPARE dates, so
        // a stretched sleep delays detection without corrupting the decision.
        let progress = CompactStreamProgress(overallLimit: 900, stallLimit: 120)

        let consumeTask = Task { @MainActor () -> String in
            var text = ""
            var didTag = false
            for try await chunk in stream {
                try Task.checkCancellation()
                await progress.touch()
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
                    text += delta
                    statusMsg?.content = "Compacting conversation... (\(text.count) chars)"
                case .finished, .usage, .started:
                    break
                }
            }
            return text
        }

        let watchdog = Task {
            while true {
                try? await Task.sleep(nanoseconds: 5 * 1_000_000_000)
                if Task.isCancelled { return }
                if let breach = await progress.breach() {
                    await MainActor.run { consumeTask.cancel() }
                    await progress.recordBreach(breach)
                    return
                }
            }
        }

        let responseText: String
        do {
            responseText = try await consumeTask.value
            watchdog.cancel()
        } catch {
            watchdog.cancel()
            // A cancellation raised BY the watchdog is a timeout, not a user
            // stop — surface it as such so the caller reports something
            // actionable instead of a silent "cancelled".
            if let breach = await progress.breachReason() {
                logger.error("[Compact] summary stream timed out (\(breach.logLabel))")
                throw LLMError.transientError(message: breach.userMessage)
            }
            throw error
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

// MARK: - Compact stream deadlines

/// [T-ios-compact-no-timeout] Tracks liveness of the compact summary stream so a
/// watchdog can cut it off. An actor because the consumer (main actor) and the
/// watchdog (background) both touch it.
///
/// Two deadlines, because one cannot cover the other:
///  - **stall**: no chunk for `stallLimit` seconds. Catches a dead stream —
///    the case an in-loop check can never see, since the loop is parked.
///  - **overall**: `overallLimit` seconds since the request began. Catches a
///    stream that dribbles just often enough to keep resetting the stall timer.
actor CompactStreamProgress {
    /// Which deadline was breached; determines what the user is told.
    enum Breach {
        case stalled(TimeInterval)
        case overall(TimeInterval)

        var logLabel: String {
            switch self {
            case .stalled(let s): return "stalled \(Int(s))s with no data"
            case .overall(let s): return "exceeded \(Int(s))s overall"
            }
        }

        /// Distinguishes the two causes: a stall is usually the connection, a
        /// wall-clock overrun usually means the input was too big to summarize
        /// in time — different next steps for the user.
        var userMessage: String {
            switch self {
            case .stalled(let s):
                return "the model stopped responding for \(Int(s))s"
            case .overall(let s):
                return "it did not finish within \(Int(s))s"
            }
        }
    }

    private let overallLimit: TimeInterval
    private let stallLimit: TimeInterval
    private let startedAt = Date()
    private var lastChunkAt = Date()
    private var recorded: Breach?

    init(overallLimit: TimeInterval, stallLimit: TimeInterval) {
        self.overallLimit = overallLimit
        self.stallLimit = stallLimit
    }

    /// Called for every chunk — resets the stall clock.
    func touch() { lastChunkAt = Date() }

    /// Non-nil once a deadline has passed. Wall-clock, so a watchdog whose
    /// sleep was stretched by background throttling still decides correctly;
    /// it only detects later, never wrongly.
    func breach() -> Breach? {
        let now = Date()
        let sinceChunk = now.timeIntervalSince(lastChunkAt)
        if sinceChunk >= stallLimit { return .stalled(sinceChunk) }
        let total = now.timeIntervalSince(startedAt)
        if total >= overallLimit { return .overall(total) }
        return nil
    }

    /// Remember why the consumer was cancelled, so the catch can tell a timeout
    /// apart from a user-initiated Stop (both arrive as CancellationError).
    func recordBreach(_ b: Breach) { recorded = b }
    func breachReason() -> Breach? { recorded }
}
