import Foundation

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Auto-Retry + Group-Level Fallback

extension AIChatViewModel {

    // MARK: - Auto-Retry on Network Errors

    static let retryDelays = [3, 5, 10, 15, 30]

    func streamWithAutoRetry(
        provider initialProvider: any AgentProvider,
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        chatMessage: ChatMessage?
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        var lastError: Error?
        var currentProvider = initialProvider
        for attempt in 0...Self.retryDelays.count {
            if attempt > 0 {
                let delay = Self.retryDelays[attempt - 1]
                self.autoRetryAttempt = attempt
                // Show the network error on the message during countdown
                if let lastError {
                    let desc = (lastError as? LocalizedError)?.errorDescription ?? String(describing: lastError)
                    chatMessage?.error = desc
                }
                for remaining in stride(from: delay, through: 1, by: -1) {
                    self.autoRetryCountdown = remaining
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                    try Task.checkCancellation()
                }
                self.autoRetryCountdown = 0
                chatMessage?.error = nil

                // Re-read current model binding — user may have switched models during countdown
                if let entry = resolveCurrentEntry() {
                    let newProvider = await makeAgentProvider(for: entry)
                    if newProvider.model.id != currentProvider.model.id {
                        logger.info("🔄 Retry: model changed during countdown \(currentProvider.model.id) → \(newProvider.model.id)")
                        currentProvider = newProvider
                    }
                }
            }
            do {
                var thinkLvl = sessionId.flatMap { ProviderConfigStore.shared.inferenceConfig(for: $0)?.thinkingLevel } ?? .off
                // [T-fallback-thinking-preclamp] Clamp to the CURRENT entry's
                // effective max BEFORE building the request — the session's
                // persisted level may exceed what this (possibly re-resolved)
                // model accepts, and the protocol-extension clamp only knows
                // the catalog max, not the entry-level override.
                if let entry = resolveCurrentEntry() {
                    thinkLvl = min(thinkLvl, entry.effectiveMaxThinkingLevel)
                }
                let stream = try await currentProvider.streamAgentMessage(
                    messages: messages,
                    systemPrompt: systemPrompt,
                    tools: tools,
                    maxTokens: maxTokens,
                    thinkingLevel: thinkLvl
                )
                self.autoRetryAttempt = 0
                return stream
            } catch let error as LLMError where error.isRetryable {
                lastError = error
                continue
            } catch {
                self.autoRetryAttempt = 0
                throw error
            }
        }
        self.autoRetryAttempt = 0
        throw lastError!
    }


    // MARK: - Group-Level Fallback

    /// Resolve the entry and open a stream. This is the entry point for EVERY
    /// LLM request in the agent loop — it tries the current entry first and
    /// returns its stream on success. The `🔀ROUTE start → success with original
    /// entry` log on every request is therefore the normal happy path, NOT error
    /// recovery; only the `advancing`/`fallbackable` branches below are actual
    /// fallback. [diag-0800 Q1]
    ///
    /// On provider-level errors (rate limit, invalid key, provider rejection)
    /// immediately fallback to the next model in the group. On retryable errors
    /// (network, 5xx), retry with countdown on the current entry via
    /// `streamWithAutoRetry`; if retries are exhausted, also fallback to the next
    /// model in the group.
    func streamWithGroupFallback(
        provider initialProvider: any AgentProvider,
        messages: [AgentMessage],
        baseSystemPrompt: String,
        systemPrompt initialSystemPrompt: String?,
        tools: [AgentToolDefinition],
        model: LLMModel,
        lastContextTokens: Int = 0,
        chatMessage: ChatMessage?,
        activeGroupId: inout String?,
        activeEntryId: inout String?
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        var currentProvider = initialProvider
        var currentSystemPrompt = initialSystemPrompt
        var currentEntryId = activeEntryId
        var triedEntries: Set<String> = []
        if let eid = currentEntryId { triedEntries.insert(eid) }

        do {
            let _aeid = activeEntryId; let _agid = activeGroupId
            logger.info("🔀ROUTE start: activeEntryId=\(_aeid ?? "nil") activeGroupId=\(_agid ?? "nil") provider=\(initialProvider.name)")
        }

        while true {
            do {
                logger.info("🔀ROUTE trying entry=\(currentEntryId ?? "nil") provider=\(currentProvider.name) tried=\(triedEntries)")
                // First attempt: call provider directly (no auto-retry) so we can
                // distinguish fallbackable errors from network errors.
                let currentModel = currentEntryId.flatMap { ProviderConfigStore.shared.entry(for: $0)?.model } ?? model
                let maxTok = dynamicMaxTokens(provider: currentProvider, model: currentModel, lastContextTokens: lastContextTokens)
                var thinkLvl = sessionId.flatMap { ProviderConfigStore.shared.inferenceConfig(for: $0)?.thinkingLevel } ?? .off
                // [T-fallback-thinking-preclamp] When falling back (e.g. a
                // Responses-API primary at xhigh → a Chat-API seed model that
                // tops out at high), the session's persisted level was passed
                // through unclamped and 400'd ("Invalid reasoning_effort:
                // xhigh"): the existing effectiveMaxThinkingLevel clamp below
                // runs only AFTER a successful request, to update the
                // persisted config. Clamp for THIS request up front; the
                // post-success write-back stays so the next turn starts right.
                if let eid = currentEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
                    thinkLvl = min(thinkLvl, entry.effectiveMaxThinkingLevel)
                }
                let stream = try await currentProvider.streamAgentMessage(
                    messages: messages,
                    systemPrompt: currentSystemPrompt,
                    tools: tools,
                    maxTokens: maxTok,
                    thinkingLevel: thinkLvl
                )
                // Success — update binding if we fell back to a different entry
                let prevEntryId = activeEntryId
                if currentEntryId != activeEntryId, let currentEntryId,
                   let groupId = activeGroupId, let sid = sessionId {
                    logger.info("🔀ROUTE success with different entry: \(prevEntryId ?? "nil") → \(currentEntryId)")
                    activeEntryId = currentEntryId
                    let binding = SessionModelBinding(
                        sessionId: sid,
                        primarySource: .group(groupId: groupId, resolvedEntryId: currentEntryId),
                        subModelSource: ProviderConfigStore.shared.binding(for: sid)?.subModelSource
                    )
                    ProviderConfigStore.shared.setBinding(binding, for: sid)
                    if let entry = ProviderConfigStore.shared.entry(for: currentEntryId) {
                        Task { await ChatStore.shared.updateSessionModelId(sid, modelId: entry.model.id) }
                    }
                    if let newEntry = ProviderConfigStore.shared.entry(for: currentEntryId) {
                        let maxLevel = newEntry.effectiveMaxThinkingLevel
                        if var cfg = ProviderConfigStore.shared.inferenceConfig(for: sid),
                           cfg.thinkingLevel > maxLevel {
                            cfg.thinkingLevel = maxLevel
                            ProviderConfigStore.shared.setInferenceConfig(cfg, for: sid)
                        }
                    }
                    logger.info("🔀ROUTE binding updated to entry \(currentEntryId)")
                } else {
                    logger.info("🔀ROUTE success with original entry=\(currentEntryId ?? "nil")")
                }
                return stream
            } catch let error as LLMError where error.isFallbackable {
                // Provider-level error (rate limit, invalid key, provider rejection):
                // immediately try next model in group without retry countdown.
                logger.error("🔀ROUTE entry=\(currentEntryId ?? "nil") fallbackable error: \(error.localizedDescription)")
                if let eid = currentEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
                    let inst = ProviderConfigStore.shared.instance(for: entry.providerInstanceId)?.label ?? entry.model.provider
                    fallbackReasons.append((model: entry.model.displayName, instance: inst, reason: error.fallbackReason))
                }

                let gid = activeGroupId
                let eid = currentEntryId
                guard let groupId = gid,
                      let currentEid = eid,
                      let group = ProviderConfigStore.shared.group(for: groupId) else {
                    logger.error("🔀ROUTE no group context, re-throwing. groupId=\(gid ?? "nil") entryId=\(eid ?? "nil")")
                    throw error
                }

                let nextEntryId = ModelGroupRouter.nextFallback(
                    group: group, currentEntryId: currentEid, store: ProviderConfigStore.shared
                )
                logger.info("🔀ROUTE nextFallback returned: \(nextEntryId ?? "nil")")

                guard let nextEntryId,
                      !triedEntries.contains(nextEntryId),
                      let nextEntry = ProviderConfigStore.shared.entry(for: nextEntryId) else {
                    logger.error("🔀ROUTE exhausted: nextEntryId=\(nextEntryId ?? "nil") alreadyTried=\(nextEntryId.map { triedEntries.contains($0) } ?? false)")
                    let skipped = ModelGroupRouter.unavailableMembers(group: group, store: ProviderConfigStore.shared)
                    fallbackReasons.append(contentsOf: skipped)
                    if !fallbackReasons.isEmpty {
                        let trailLines = fallbackReasons.map { "⚠️ \($0.model) (\($0.instance)): \($0.reason)" }
                        let finalDesc = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                        throw LLMError.providerError(message: trailLines.joined(separator: "\n") + "\n" + finalDesc)
                    }
                    throw error
                }

                logger.info("🔀ROUTE advancing: \(currentEid) → \(nextEntryId) (model=\(nextEntry.model.id) instance=\(nextEntry.providerInstanceId))")
                triedEntries.insert(nextEntryId)
                currentEntryId = nextEntryId
                currentProvider = await makeAgentProvider(for: nextEntry)
                // Rebuild system prompt for the new model's capabilities
                var rebuiltPrompt = baseSystemPrompt
                if let capFragment = nextEntry.model.capabilityPromptFragment {
                    rebuiltPrompt += "\n\n" + capFragment
                }
                if let behaviorFragment = nextEntry.model.agentBehaviorPromptFragment {
                    rebuiltPrompt += "\n\n" + behaviorFragment
                }
                currentSystemPrompt = rebuiltPrompt
                // continue loop — will try next entry immediately
            } catch {
                // Check if group uses "always" fallback strategy — if so, treat all
                // errors as immediately fallbackable (skip auto-retry on current model).
                let groupFallbackStrategy = activeGroupId
                    .flatMap { ProviderConfigStore.shared.group(for: $0) }?.fallbackStrategy ?? .limited
                if groupFallbackStrategy == .always {
                    logger.error("🔀ROUTE entry=\(currentEntryId ?? "nil") always-strategy, immediate fallback for: \(error.localizedDescription)")
                    if let eid = currentEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
                        let inst = ProviderConfigStore.shared.instance(for: entry.providerInstanceId)?.label ?? entry.model.provider
                        fallbackReasons.append((model: entry.model.displayName, instance: inst, reason: (error as? LLMError)?.fallbackReason ?? AppLocalized("Error")))
                    }

                    let gid = activeGroupId
                    let eid = currentEntryId
                    guard let groupId = gid,
                          let currentEid = eid,
                          let group = ProviderConfigStore.shared.group(for: groupId) else {
                        logger.error("🔀ROUTE no group context (always), re-throwing. groupId=\(gid ?? "nil") entryId=\(eid ?? "nil")")
                        throw error
                    }

                    let nextEntryId = ModelGroupRouter.nextFallback(
                        group: group, currentEntryId: currentEid, store: ProviderConfigStore.shared
                    )
                    logger.info("🔀ROUTE always-strategy nextFallback returned: \(nextEntryId ?? "nil")")

                    guard let nextEntryId,
                          !triedEntries.contains(nextEntryId),
                          let nextEntry = ProviderConfigStore.shared.entry(for: nextEntryId) else {
                        logger.error("🔀ROUTE always-strategy exhausted: nextEntryId=\(nextEntryId ?? "nil") alreadyTried=\(nextEntryId.map { triedEntries.contains($0) } ?? false)")
                        let skipped = ModelGroupRouter.unavailableMembers(group: group, store: ProviderConfigStore.shared)
                        fallbackReasons.append(contentsOf: skipped)
                        if !fallbackReasons.isEmpty {
                            let trailLines = fallbackReasons.map { "⚠️ \($0.model) (\($0.instance)): \($0.reason)" }
                            let finalDesc = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                            throw LLMError.providerError(message: trailLines.joined(separator: "\n") + "\n" + finalDesc)
                        }
                        throw error
                    }

                    logger.info("🔀ROUTE always-strategy advancing: \(currentEid) → \(nextEntryId) (model=\(nextEntry.model.id) instance=\(nextEntry.providerInstanceId))")
                    triedEntries.insert(nextEntryId)
                    currentEntryId = nextEntryId
                    currentProvider = await makeAgentProvider(for: nextEntry)
                    var rebuiltPrompt = baseSystemPrompt
                    if let capFragment = nextEntry.model.capabilityPromptFragment {
                        rebuiltPrompt += "\n\n" + capFragment
                    }
                    if let behaviorFragment = nextEntry.model.agentBehaviorPromptFragment {
                        rebuiltPrompt += "\n\n" + behaviorFragment
                    }
                    currentSystemPrompt = rebuiltPrompt
                    continue
                }

                // Network error, unknown error, etc.: use auto-retry with countdown
                // on the *current* entry first. If retries are exhausted, fallback to
                // the next model in the group.
                logger.error("🔀ROUTE entry=\(currentEntryId ?? "nil") non-fallbackable error, using auto-retry: \(error.localizedDescription)")
                let retryModel = currentEntryId.flatMap { ProviderConfigStore.shared.entry(for: $0)?.model } ?? model
                do {
                    let stream = try await streamWithAutoRetry(
                        provider: currentProvider,
                        messages: messages,
                        systemPrompt: currentSystemPrompt,
                        tools: tools,
                        maxTokens: dynamicMaxTokens(provider: currentProvider, model: retryModel, lastContextTokens: lastContextTokens),
                        chatMessage: chatMessage
                    )
                    // Auto-retry succeeded
                    let prevEntryId = activeEntryId
                    if currentEntryId != activeEntryId, let currentEntryId,
                       let groupId = activeGroupId, let sid = sessionId {
                        logger.info("🔀ROUTE auto-retry success with different entry: \(prevEntryId ?? "nil") → \(currentEntryId)")
                        activeEntryId = currentEntryId
                        let binding = SessionModelBinding(
                            sessionId: sid,
                            primarySource: .group(groupId: groupId, resolvedEntryId: currentEntryId),
                            subModelSource: ProviderConfigStore.shared.binding(for: sid)?.subModelSource
                        )
                        ProviderConfigStore.shared.setBinding(binding, for: sid)
                        if let entry = ProviderConfigStore.shared.entry(for: currentEntryId) {
                            Task { await ChatStore.shared.updateSessionModelId(sid, modelId: entry.model.id) }
                        }
                    }
                    return stream
                } catch {
                    // Auto-retry exhausted — attempt group fallback
                    logger.error("🔀ROUTE auto-retry exhausted for entry=\(currentEntryId ?? "nil"), attempting group fallback")
                    if let eid = currentEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
                        let inst = ProviderConfigStore.shared.instance(for: entry.providerInstanceId)?.label ?? entry.model.provider
                        fallbackReasons.append((model: entry.model.displayName, instance: inst, reason: AppLocalized("Retries exhausted")))
                    }

                    let gid = activeGroupId
                    let eid = currentEntryId
                    guard let groupId = gid,
                          let currentEid = eid,
                          let group = ProviderConfigStore.shared.group(for: groupId) else {
                        logger.error("🔀ROUTE no group context after retry exhaustion, re-throwing. groupId=\(gid ?? "nil") entryId=\(eid ?? "nil")")
                        throw error
                    }

                    let nextEntryId = ModelGroupRouter.nextFallback(
                        group: group, currentEntryId: currentEid, store: ProviderConfigStore.shared
                    )
                    logger.info("🔀ROUTE after retry exhaustion, nextFallback returned: \(nextEntryId ?? "nil")")

                    guard let nextEntryId,
                          !triedEntries.contains(nextEntryId),
                          let nextEntry = ProviderConfigStore.shared.entry(for: nextEntryId) else {
                        logger.error("🔀ROUTE exhausted all entries after retry: nextEntryId=\(nextEntryId ?? "nil") alreadyTried=\(nextEntryId.map { triedEntries.contains($0) } ?? false)")
                        let skipped = ModelGroupRouter.unavailableMembers(group: group, store: ProviderConfigStore.shared)
                        fallbackReasons.append(contentsOf: skipped)
                        if !fallbackReasons.isEmpty {
                            let trailLines = fallbackReasons.map { "⚠️ \($0.model) (\($0.instance)): \($0.reason)" }
                            let finalDesc = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                            throw LLMError.providerError(message: trailLines.joined(separator: "\n") + "\n" + finalDesc)
                        }
                        throw error
                    }

                    logger.info("🔀ROUTE after retry exhaustion, advancing: \(currentEid) → \(nextEntryId) (model=\(nextEntry.model.id) instance=\(nextEntry.providerInstanceId))")
                    triedEntries.insert(nextEntryId)
                    currentEntryId = nextEntryId
                    currentProvider = await makeAgentProvider(for: nextEntry)
                    var rebuiltPrompt = baseSystemPrompt
                    if let capFragment = nextEntry.model.capabilityPromptFragment {
                        rebuiltPrompt += "\n\n" + capFragment
                    }
                    if let behaviorFragment = nextEntry.model.agentBehaviorPromptFragment {
                        rebuiltPrompt += "\n\n" + behaviorFragment
                    }
                    currentSystemPrompt = rebuiltPrompt
                    // continue loop — will try next entry
                }
            }
        }
    }

    // MARK: - Group Fallback Until Content

    /// Wraps `streamWithGroupFallback` + `processStreamEvents` in a loop that
    /// keeps advancing through group entries when a model returns an empty response
    /// (HTTP 200 but no content blocks). This handles the case where one provider
    /// is silently failing (e.g. Anthropic overloaded returning empty SSE) and the
    /// group should advance to a different provider (e.g. OpenAI).
    func streamWithGroupFallbackUntilContent(
        provider: any AgentProvider,
        messages: [AgentMessage],
        baseSystemPrompt: String,
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        model: LLMModel,
        lastContextTokens: Int,
        chatMessage: ChatMessage?,
        msgIdx: Int,
        activeGroupId: inout String?,
        activeEntryId: inout String?
    ) async throws -> StreamResult {
        // Track entries we've gotten empty responses from to avoid infinite loops.
        var emptyResponseEntries: Set<String> = []

        while true {
            let fbStream = try await streamWithGroupFallback(
                provider: provider,
                messages: messages,
                baseSystemPrompt: baseSystemPrompt,
                systemPrompt: systemPrompt,
                tools: tools,
                model: model,
                lastContextTokens: lastContextTokens,
                chatMessage: chatMessage,
                activeGroupId: &activeGroupId,
                activeEntryId: &activeEntryId
            )
            let result = try await processStreamEvents(
                stream: fbStream,
                msgIdx: msgIdx,
                provider: provider
            )

            // Empty-response detection: HTTP 200 + stream finished, but no text,
            // no tool calls, no reasoning. Some providers (e.g. OpenAI gpt-5.5
            // on long contexts) emit `finish_reason=stop` with zero output, which
            // the provider maps to `.endTurn`. Treat any such "ghost" turn as
            // empty regardless of the reported stopReason — only `.maxTokens` is
            // excluded since it has its own surfaced error path. `.toolUse` is
            // implicitly excluded because toolEntries would be non-empty.
            let hasReasoning = !(result.reasoningContent ?? "").isEmpty
            let isEmpty = result.assistantText.isEmpty && result.toolEntries.isEmpty
                && !hasReasoning && !result.isStreamInterrupted
                && result.stopReason != .maxTokens

            guard isEmpty else { return result }

            // Empty response — record this entry and force-fail so the next
            // streamWithGroupFallback call advances to a different entry.
            let entryKey = activeEntryId ?? "unknown"
            logger.error("🔀ROUTE-CONTENT entry=\(entryKey) returned empty response, advancing")
            if let eid = activeEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
                let inst = ProviderConfigStore.shared.instance(for: entry.providerInstanceId)?.label ?? entry.model.provider
                fallbackReasons.append((model: entry.model.displayName, instance: inst, reason: AppLocalized("Empty response")))
            }

            emptyResponseEntries.insert(entryKey)

            // Check if we've exhausted all entries
            if let gid = activeGroupId, let group = ProviderConfigStore.shared.group(for: gid) {
                let available = group.memberEntryIds.filter { !emptyResponseEntries.contains($0) }
                if available.isEmpty {
                    logger.error("🔀ROUTE-CONTENT all entries returned empty, giving up")
                    return result  // Return the empty result — caller will surface the error
                }
            } else {
                return result  // No group context — can't fallback further
            }

            // Clear the incomplete in-flight tail before trying the next entry.
            // [T-ios-stream-retry-text-disappear] Bound the clear to the
            // uncommitted tail (>= committedBlockCount) so text/tool blocks
            // already committed+persisted by earlier rounds of this loop stay on
            // screen — the old unbounded removeAll wiped them from the UI on a
            // mid-stream fallback even though they were still in the DB.
            await MainActor.run {
                guard msgIdx < self.messages.count else { return }
                AIChatViewModel.clearUncommittedStreamTail(self.messages[msgIdx], committedBlockCount: self.committedBlockCount)
            }

            // Throw a fallbackable error so streamWithGroupFallback skips the
            // current entry and picks the next one.
            // We catch it immediately in the next loop iteration.
            // But wait — streamWithGroupFallback creates its own triedEntries...
            // We need to make the current entry "fail" from streamWithGroupFallback's
            // perspective. The simplest way: temporarily disable the entry? No.
            // Better: just call streamWithGroupFallback again — it starts fresh with
            // triedEntries = {activeEntryId}. If the activeEntryId hasn't changed,
            // it will try the same entry again.
            //
            // Fix: update activeEntryId to force streamWithGroupFallback to start
            // from a different entry. We do this by finding the next available entry
            // ourselves and updating the binding.
            if let gid = activeGroupId, let currentEid = activeEntryId,
               let group = ProviderConfigStore.shared.group(for: gid) {
                let nextEid = ModelGroupRouter.nextFallback(
                    group: group, currentEntryId: currentEid, store: ProviderConfigStore.shared
                )
                if let nextEid, !emptyResponseEntries.contains(nextEid) {
                    logger.info("🔀ROUTE-CONTENT manually advancing: \(currentEid) → \(nextEid)")
                    activeEntryId = nextEid
                    if let sid = sessionId {
                        let binding = SessionModelBinding(
                            sessionId: sid,
                            primarySource: .group(groupId: gid, resolvedEntryId: nextEid),
                            subModelSource: ProviderConfigStore.shared.binding(for: sid)?.subModelSource
                        )
                        ProviderConfigStore.shared.setBinding(binding, for: sid)
                    }
                } else {
                    logger.error("🔀ROUTE-CONTENT no more untried entries")
                    return result
                }
            }
        }
    }

}
