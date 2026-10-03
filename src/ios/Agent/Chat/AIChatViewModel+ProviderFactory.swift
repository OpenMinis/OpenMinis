import Foundation

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Provider Factory

extension AIChatViewModel {

    // MARK: - Provider Factory

    /// Construct an AgentProvider from a ModelEntry by looking up its ProviderInstance and credential.
    ///
    /// [T-gemini-signature-restore] Every provider built for THIS session's
    /// agent loop gets the session's recorded Gemini thought signatures handed
    /// to it here. `toolCallMetadataMap` is per-instance state, and the loop
    /// builds a fresh provider on group fallback, on a mid-stream failover, and
    /// when the user switches model during a retry countdown — each of those
    /// used to start with an empty map, so a history whose signatures we DO
    /// hold was misread as unsigned and downgraded to narrated text. Restoring
    /// at the single factory choke point covers all of those paths at once.
    func makeAgentProvider(for entry: ModelEntry) async -> AgentProvider {
        // [T-opencode-dedicated-channel] This instance path is the only one
        // that has a conversation to name, and it hands the factory a LIVE
        // resolver rather than today's value.
        //
        // A provider outlives the draft→session promotion: a new chat builds
        // its provider while `sessionId` is still nil, and the real UUID only
        // appears once `ensureSession()` persists the row. Passing `sessionId`
        // by value captured that nil, so the FIRST turn of every new
        // conversation reached OpenCode Go with no `x-opencode-session` — a
        // hard 400 — and later turns reached it with one. Reading it at
        // request-build time instead means the promotion is picked up with no
        // provider rebuild and no patch-the-provider step at the promotion
        // site (which is how Android solves the same problem).
        //
        // `@Sendable` + MainActor hop: the closure runs on the request-building
        // path, while `sessionId` is MainActor state. `assumeIsolated` is not
        // safe here (the builder is not guaranteed to be on main), so the value
        // is mirrored into a lock-guarded box that the VM keeps current.
        let box = openCodeSessionBox
        let provider = await Self.makeAgentProvider(
            for: entry,
            resolveSessionId: { box.value }
        )
        applyPendingThoughtSignatures(to: provider)
        return provider
    }

    /// Hand the session's persisted signatures to a newly built provider.
    /// Deliberately does NOT clear `pendingThoughtSignatures`: it is the
    /// session's durable record (rebuilt from the DB by `loadSession`), and the
    /// next provider this session builds needs exactly the same map. Clearing
    /// it after the first use is what left every later provider blind.
    func applyPendingThoughtSignatures(to provider: AgentProvider) {
        guard !pendingThoughtSignatures.isEmpty else { return }
        if let gemini = provider as? GeminiAgentProvider {
            gemini.restoreToolCallMetadata(pendingThoughtSignatures)
        } else if let antigravity = provider as? AntigravityAgentProvider {
            antigravity.restoreToolCallMetadata(pendingThoughtSignatures)
        }
    }

    /// Static variant — used by sub-task call sites (title generation, etc.)
    /// that don't have a viewmodel context. Same lookup logic as the instance
    /// method, since the resolution depends only on global state
    /// (ProviderConfigStore + LLMProviderFactory).
    /// - Parameter sessionId: the conversation this provider will serve, or nil
    ///   when there is none (title generation, Vision resolution, voice
    ///   correction, quick test). Only used by the OpenCode channel; nil simply
    ///   omits the header.
    ///
    ///   A fixed value is captured once. Anything attached to a LIVE
    ///   conversation should use the `resolveSessionId:` form instead, so a
    ///   draft's promotion to a real session is picked up — see
    ///   [T-opencode-dedicated-channel].
    static func makeAgentProvider(for entry: ModelEntry, sessionId: String? = nil) async -> AgentProvider {
        await makeAgentProvider(for: entry, resolveSessionId: { sessionId })
    }

    /// [T-opencode-dedicated-channel] Static variant whose OpenCode session id
    /// is resolved per request rather than captured at construction.
    static func makeAgentProvider(
        for entry: ModelEntry,
        resolveSessionId: @escaping @Sendable () -> String?
    ) async -> AgentProvider {
        let store = ProviderConfigStore.shared
        guard let instance = store.instance(for: entry.providerInstanceId) else {
            logger.error("No ProviderInstance found for entry \(entry.id)")
            return AnthropicAgentProvider(provider: AnthropicProvider(apiKey: "", model: entry.model))
        }
        // [T-model-custom-params] / [T-opencode-dedicated-channel] One seam for
        // every OpenAI-family branch below: the dedicated OpenCode channel,
        // then this entry's user overrides (temperature / top_p / custom
        // headers / extra body params). Defined once so the branches cannot
        // drift on which applied what.
        //
        // The channel installs a PER-REQUEST hook, so the session id is read
        // when each request is built rather than captured here.
        func wrap(_ p: OpenAIProvider) -> OpenAIProvider {
            if instance.isOpenCodeChannel {
                OpenCodeChannel.attach(to: p, resolveSessionId: resolveSessionId)
            }
            return LLMProviderFactory.applyModelOverrides(p, entry: entry)
        }
        switch instance.providerType {
        case .anthropic:
            return AnthropicAgentProvider(provider: LLMProviderFactory.makeAnthropicProvider(instance: instance, model: entry.model))
        case .gemini:
            return GeminiAgentProvider(provider: await LLMProviderFactory.makeGeminiProvider(instance: instance, model: entry.model))
        case .openAI:
            return OpenAIAgentProvider(provider: wrap(LLMProviderFactory.makeOpenAIProvider(instance: instance, model: entry.model)))
        case .antigravity:
            return AntigravityAgentProvider(provider: await LLMProviderFactory.makeAntigravityProvider(instance: instance, model: entry.model))
        case .openRouter:
            return OpenAIAgentProvider(provider: wrap(LLMProviderFactory.makeOpenRouterProvider(instance: instance, model: entry.model)))
        case .openAIResponses:
            return OpenAIAgentProvider(provider: wrap(LLMProviderFactory.makeOpenAIResponsesProvider(instance: instance, model: entry.model)))
        case .xAI:
            return OpenAIAgentProvider(provider: wrap(LLMProviderFactory.makeXAIProvider(instance: instance, model: entry.model)))
        case .kimiCode:
            return OpenAIAgentProvider(provider: wrap(LLMProviderFactory.makeKimiProvider(instance: instance, model: entry.model)))
        case .githubCopilot:
            // [T-copilot-no-chat-warning] No warning in the chat.
            //
            // There used to be a one-time banner here on first use. Removed: the
            // risk is already disclosed where the user can act on it — the
            // sign-in sheet's consent page must be accepted before a single
            // request goes out, and the provider row says "unofficial, use at
            // your own risk" before that. Repeating it inside the conversation
            // adds nothing the user has not already agreed to, and puts a
            // warning in the one place they cannot do anything about it.
            return OpenAIAgentProvider(
                provider: LLMProviderFactory.makeCopilotProvider(instance: instance, model: entry.model))
        case .unsupported:
            logger.error("\(instance.providerType) has no agent provider; returning placeholder")
            return AnthropicAgentProvider(provider: AnthropicProvider(apiKey: "", model: entry.model))
        }
    }

    /// Build an AnthropicAgentProvider for cache keep-alive warmup, reusing the
    /// same entry that was used in the last agent loop.
    func makeAnthropicProviderForWarmup() -> AnthropicAgentProvider? {
        guard let entry = keepAliveEntry else { return nil }
        let store = ProviderConfigStore.shared
        guard let instance = store.instance(for: entry.providerInstanceId),
              instance.providerType == .anthropic else { return nil }
        RequestBodyPatcher.setExtendedCacheTTL(self.enhancedCacheEnabled)
        return AnthropicAgentProvider(provider: LLMProviderFactory.makeAnthropicProvider(instance: instance, model: entry.model))
    }


    /// Construct a lightweight LLMProvider from a ModelEntry (for sub-tasks like title generation).
    static func makeLLMProvider(for entry: ModelEntry) async throws -> any LLMProvider {
        let store = ProviderConfigStore.shared
        guard let instance = store.instance(for: entry.providerInstanceId) else {
            throw LLMProviderError.noCredentials
        }
        let customBase = instance.effectiveCustomBaseURL
        let appendV1 = instance.appendV1Suffix
        // Custom UA only for custom-base OpenAI/Anthropic-compat (proxy/relay) instances;
        // OAuth-login paths keep their required client UA (nil here). Mirrors LLMProviderFactory.
        let ua = instance.supportsCustomUserAgent ? instance.effectiveCustomUserAgent : nil
        switch instance.providerType {
        case .anthropic:
            switch instance.credentialType {
            case .apiKey:
                guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                    throw LLMProviderError.noCredentials
                }
                return AnthropicProvider(apiKey: key, model: entry.model, basePath: customBase, appendV1Suffix: appendV1, customUserAgent: ua)
            case .oauth:
                if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                    return AnthropicProvider(manualToken: manualToken, model: entry.model, basePath: customBase, appendV1Suffix: appendV1, customUserAgent: ua)
                }
                let iid = instance.id
                return AnthropicProvider(
                    oauthTokenProvider: { try await ClaudeOAuthManager.shared.validAccessToken(instanceId: iid) },
                    model: entry.model,
                    basePath: customBase,
                    appendV1Suffix: appendV1
                )
            }
        case .gemini:
            switch instance.credentialType {
            case .apiKey:
                guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                    throw LLMProviderError.noCredentials
                }
                return GeminiProvider(apiKey: key, model: entry.model, customBasePath: customBase)
            case .oauth:
                if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                    return GeminiProvider(apiKey: manualToken, model: entry.model, customBasePath: customBase)
                }
                let iid = instance.id
                let provider = GeminiProvider(
                    oauthTokenProvider: { try await GeminiOAuthManager.shared.validAccessToken(instanceId: iid) },
                    model: entry.model,
                    customBasePath: customBase
                )
                provider.gcpProjectID = GeminiOAuthManager.shared.gcpProjectID(instanceId: iid)
                return provider
            }
        case .openAI:
            // Mistral compat: use legacy max_tokens + skip stream_options +
            // suppress reasoning auto-inject. Mirrors LLMProviderFactory.makeOpenAIProvider.
            let isMistral = (customBase ?? "").lowercased().contains("mistral.ai")
            switch instance.credentialType {
            case .apiKey:
                guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                    throw LLMProviderError.noCredentials
                }
                let provider = OpenAIProvider(apiKey: key, model: entry.model, customBaseURL: customBase, appendV1Suffix: appendV1)
                if isMistral { provider.useOpenRouterCompat = true; provider.isMistral = true }
                return LLMProviderFactory.applyCustomUserAgent(provider, instance: instance)
            case .oauth:
                if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                    let provider = OpenAIProvider(apiKey: manualToken, model: entry.model, customBaseURL: customBase, appendV1Suffix: appendV1)
                    if isMistral { provider.useOpenRouterCompat = true; provider.isMistral = true }
                    return LLMProviderFactory.applyCustomUserAgent(provider, instance: instance)
                }
                let iid = instance.id
                let provider = OpenAIProvider(
                    oauthTokenProvider: { try await CodexOAuthManager.shared.validAccessToken(instanceId: iid) },
                    model: entry.model
                )
                provider.codexAccountId = CodexOAuthManager.shared.accountId(instanceId: iid)
                return provider
            }
        case .antigravity:
            let iid = instance.id
            let provider = AntigravityProvider(
                oauthTokenProvider: { try await AntigravityOAuthManager.shared.validAccessToken(instanceId: iid) },
                model: entry.model
            )
            provider.projectID = AntigravityOAuthManager.shared.projectID(instanceId: iid)
            if let baseURL = AntigravityOAuthManager.shared.resolvedBaseURL(instanceId: iid) {
                provider.activeBaseURL = baseURL
            }
            return provider
        case .openRouter:
            let key: String
            if instance.credentialType == .oauth,
               let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                key = manualToken
            } else {
                guard let storedKey = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                    throw LLMProviderError.noCredentials
                }
                key = storedKey
            }
            let orProvider = OpenAIProvider(apiKey: key, model: entry.model, customBaseURL: customBase ?? "https://openrouter.ai/api", appendV1Suffix: customBase == nil)
            orProvider.extraHeaders = [
                "HTTP-Referer": "https://github.com/OpenMinis/OpenMinis",
                "X-Title": "Minis App",
            ]
            orProvider.useOpenRouterCompat = true
            return LLMProviderFactory.applyCustomUserAgent(orProvider, instance: instance)
        case .openAIResponses:
            // Mirror the apiKey + OAuth branch logic in
            // LLMProviderFactory.makeOpenAIResponsesProvider so an OpenAI2
            // instance configured with Codex OAuth (not an API key) reaches
            // /v1/responses with a real bearer token instead of the
            // empty-auth header the apiKey-only path produced
            // (T-model-use-codex-responses-api-34889).
            switch instance.credentialType {
            case .apiKey:
                guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                    throw LLMProviderError.noCredentials
                }
                let respProvider = OpenAIProvider(apiKey: key, model: entry.model, customBaseURL: customBase, appendV1Suffix: appendV1)
                respProvider.forceResponsesAPI = true
                return LLMProviderFactory.applyCustomUserAgent(respProvider, instance: instance)
            case .oauth:
                if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                    let respProvider = OpenAIProvider(apiKey: manualToken, model: entry.model, customBaseURL: customBase, appendV1Suffix: appendV1)
                    respProvider.forceResponsesAPI = true
                    return LLMProviderFactory.applyCustomUserAgent(respProvider, instance: instance)
                }
                let iid = instance.id
                let respProvider = OpenAIProvider(
                    oauthTokenProvider: { try await CodexOAuthManager.shared.validAccessToken(instanceId: iid) },
                    model: entry.model
                )
                respProvider.customBaseURL = customBase
                respProvider.appendV1Suffix = appendV1
                respProvider.forceResponsesAPI = true
                respProvider.codexAccountId = CodexOAuthManager.shared.accountId(instanceId: iid)
                return respProvider
            }
        case .xAI:
            let xaiBase = customBase ?? "https://api.x.ai/v1"
            let xaiAppendV1 = customBase == nil ? false : appendV1
            switch instance.credentialType {
            case .apiKey:
                guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                    throw LLMProviderError.noCredentials
                }
                return LLMProviderFactory.applyCustomUserAgent(OpenAIProvider(apiKey: key, model: entry.model, customBaseURL: xaiBase, appendV1Suffix: xaiAppendV1), instance: instance)
            case .oauth:
                if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                    return LLMProviderFactory.applyCustomUserAgent(OpenAIProvider(apiKey: manualToken, model: entry.model, customBaseURL: xaiBase, appendV1Suffix: xaiAppendV1), instance: instance)
                }
                let iid = instance.id
                let provider = OpenAIProvider(
                    oauthTokenProvider: { try await XAIOAuthManager.shared.validAccessToken(instanceId: iid) },
                    model: entry.model
                )
                provider.customBaseURL = xaiBase
                provider.appendV1Suffix = xaiAppendV1
                return provider
            }
        case .githubCopilot:
            // OAuth only; the builder owns the base URL and headers.
            return LLMProviderFactory.makeCopilotProvider(instance: instance, model: entry.model)
        case .kimiCode:
            let kimiBase = customBase ?? "https://api.kimi.com/coding"
            let kimiAppendV1 = customBase == nil ? true : appendV1  // default base …/coding needs /v1 appended
            switch instance.credentialType {
            case .apiKey:
                guard let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) else {
                    throw LLMProviderError.noCredentials
                }
                return LLMProviderFactory.applyCustomUserAgent(OpenAIProvider(apiKey: key, model: entry.model, customBaseURL: kimiBase, appendV1Suffix: kimiAppendV1), instance: instance)
            case .oauth:
                // [T-kimi-manual-token-ignored] Honour a manually-pasted bearer
                // token, as the anthropic / gemini / openAI branches in this
                // same switch already do. Third copy of this decision (factory,
                // model fetch, here) — all three had to be corrected.
                if let manualToken = ProviderKeychainHelper.loadOAuthString(
                    instanceId: instance.id, account: "manual-oauth-token") {
                    return LLMProviderFactory.applyCustomUserAgent(
                        OpenAIProvider(apiKey: manualToken, model: entry.model,
                                       customBaseURL: kimiBase, appendV1Suffix: kimiAppendV1),
                        instance: instance)
                }
                let iid = instance.id
                let provider = OpenAIProvider(
                    oauthTokenProvider: { try await KimiOAuthManager.shared.validAccessToken(instanceId: iid) },
                    model: entry.model
                )
                provider.customBaseURL = kimiBase
                provider.appendV1Suffix = kimiAppendV1
                return provider
            }
        case .unsupported:
            throw LLMProviderError.noCredentials
        }
    }

    /// Resolve the current session's primary model entry from its binding, or fall back to defaults.
    /// L1 cache (T-new-session-hang-credential-cache): SwiftUI body re-eval
    /// drives this ~84-178×/new-session (nav-bar thinking badge etc.), each call
    /// re-walking the group + per-member `hasAnyCredential`. The result is a pure
    /// function of (sessionId, cachedSessionModelId, defaultGroupId, configRevision,
    /// authRevision); when all five are unchanged the answer can't change, so we
    /// memoize. configRevision/authRevision are the epoch counters bumped on any
    /// config or credential mutation, so a stale entry cannot survive a change.
    private struct ResolveCacheKey: Hashable {
        let sessionId: String
        let cachedModelId: String
        let defaultGroupId: String
        let configRevision: UInt
        let authRevision: UInt
    }
    private static var resolveCache: [ResolveCacheKey: String] = [:]  // key → entryId
    /// Negatives (no resolvable entry) are cached too, as the sentinel below —
    /// the no-config/no-credential path is itself moderately expensive to re-walk.
    private static let resolveNilSentinel = "\u{0}nil"

    func resolveCurrentEntry() -> ModelEntry? {
        let store = ProviderConfigStore.shared
        let key = ResolveCacheKey(
            sessionId: sessionId ?? "",
            cachedModelId: cachedSessionModelId,
            defaultGroupId: store.defaultPrimaryGroupId ?? "",
            configRevision: store.configRevision,
            authRevision: store.authRevision
        )
        if let cachedId = Self.resolveCache[key] {
            if cachedId == Self.resolveNilSentinel { return nil }
            // The entry could have been deleted out from under a still-valid
            // epoch only via a save() (which bumps configRevision → new key),
            // so a present cache value always still resolves; but guard anyway.
            if let entry = store.entry(for: cachedId) { return entry }
        }
        let resolved = resolveCurrentEntryUncached()
        Self.resolveCache[key] = resolved?.id ?? Self.resolveNilSentinel
        return resolved
    }

    private func resolveCurrentEntryUncached() -> ModelEntry? {
        let store = ProviderConfigStore.shared

        // 1. Try session binding
        if let sid = sessionId, let binding = store.binding(for: sid) {
            switch binding.primarySource {
            case .directEntry(let entryId, _):
                if let entry = store.entry(for: entryId) {
                    logger.info("🔀RESOLVE directEntry=\(entryId) model=\(entry.model.id)")
                    return entry
                }
                // Entry was deleted (e.g. provider removed) — fall through to default group
                logger.warning("🔀RESOLVE directEntry=\(entryId) missing, falling back to default group")
            case .group(let groupId, let resolvedEntryId):
                // [T-ios-disabled-provider-still-selectable-via-group #34] Only
                // honor the cached resolvedEntryId when its provider instance is
                // still usable (enabled + credentialed) AND the entry isn't
                // hidden. The group binding caches whichever member was resolved
                // when the session was created / last re-picked; if the user
                // later DISABLES that member's provider (e.g. a Coding Plan whose
                // quota ran out, disabled to force fallback to the next provider),
                // the stale resolvedEntryId would otherwise keep routing to the
                // disabled provider's pay-as-you-go model and bill the user. Treat
                // a disabled/credential-less/hidden resolved entry the same as a
                // deleted one: re-resolve through ModelGroupRouter, which already
                // filters by ModelGroupRouter.availableEntryIds (enabled +
                // credential + not hidden).
                if let entry = store.entry(for: resolvedEntryId),
                   let inst = store.instance(for: entry.providerInstanceId),
                   inst.isEnabled, inst.hasAnyCredential, !entry.isHidden {
                    logger.info("🔀RESOLVE group=\(groupId) resolvedEntry=\(resolvedEntryId) model=\(entry.model.id)")
                    return entry
                }
                // Resolved entry was deleted OR its provider is now disabled /
                // credential-less / hidden — re-resolve the group to the next
                // available member, then fall through to the default group.
                if let group = store.group(for: groupId),
                   let freshEntryId = ModelGroupRouter.resolve(group: group, sessionId: sid, store: store),
                   let entry = store.entry(for: freshEntryId) {
                    logger.warning("🔀RESOLVE group=\(groupId) resolvedEntry=\(resolvedEntryId) unavailable (deleted/disabled/hidden), re-resolved to \(freshEntryId) model=\(entry.model.id)")
                    return entry
                }
                logger.warning("🔀RESOLVE group=\(groupId) resolvedEntry=\(resolvedEntryId) unavailable and group has no available member, falling back to default group")
            }
        } else {
            logger.info("🔀RESOLVE no binding for session=\(self.sessionId ?? "nil")")
        }

        // 2. Try the session's persisted modelId (cached at loadSession time)
        //    BEFORE falling back to the default group. The session row stores
        //    `modelId` (set when the user first picked a model on this
        //    session) which survives iCloud sync even when the in-memory
        //    SessionModelBinding doesn't. Without this step, compact / title
        //    generation would silently pick the global default group and run
        //    on a different model than what the chat header is showing.
        //
        //    When MULTIPLE entries match the same model id (common when iCloud
        //    sync brings over a second provider instance for the same model,
        //    e.g. "claude-sonnet-4-6" served by both a working API-key
        //    instance and an OAuth instance whose token never landed on this
        //    device), prefer one whose instance has a usable credential.
        //    Without that preference the first-match-wins pick would silently
        //    route to the credential-less instance, producing a 403 OAuth
        //    error every time the user opens an iCloud-synced session.
        if !cachedSessionModelId.isEmpty {
            let matching = store.modelEntries.filter { $0.model.id == cachedSessionModelId && !$0.isHidden }

            // 2a. **Prefer entries that live inside the default primary group.**
            //     This is what the chat header subtitle has been showing all
            //     along ("Default Model · Anthropic(X) · Claude Sonnet 4.6"),
            //     so honoring it is the principle of least surprise. It also
            //     fixes an iCloud-sync footgun: when a session arrives with
            //     `modelId=claude-sonnet-4-6` but no SessionModelBinding (the
            //     binding row didn't sync), the older "first credentialed
            //     match across ALL instances" logic would land on whatever
            //     Anthropic instance came first in `store.modelEntries`. On a
            //     device with multiple Anthropic OAuth logins (e.g. account A +
            //     account B), it could route to account A whose OAuth token is rejected
            //     by Anthropic at the org level — even though the UI showed
            //     "Anthropic(53)" and the default group would have picked 53.
            //     User experiences this as "open synced session → 403; switch
            //     model and pick the SAME model again → 200" because the
            //     re-pick creates a group binding that routes via
            //     ModelGroupRouter (= the default-group path).
            if let groupId = store.defaultPrimaryGroupId,
               let group = store.group(for: groupId) {
                let groupMemberSet = Set(group.memberEntryIds)
                let inGroup = matching.filter { groupMemberSet.contains($0.id) }
                if let entry = inGroup.first(where: { e in
                    guard let inst = store.instance(for: e.providerInstanceId) else { return false }
                    return inst.isEnabled && inst.hasAnyCredential
                }) {
                    logger.info("🔀RESOLVE via cachedSessionModelId=\(self.cachedSessionModelId) → entry=\(entry.id) (defaultGroup member, credentialed)")
                    return entry
                }
                if let entry = inGroup.first {
                    logger.info("🔀RESOLVE via cachedSessionModelId=\(self.cachedSessionModelId) → entry=\(entry.id) (defaultGroup member, no-credential fallback)")
                    return entry
                }
            }

            // 2b. No default-group match — fall back to first credentialed
            //     enabled match anywhere. (Rare: the model the session uses
            //     is no longer in the default group.)
            if let entry = matching.first(where: { e in
                guard let inst = store.instance(for: e.providerInstanceId) else { return false }
                return inst.isEnabled && inst.hasAnyCredential
            }) {
                logger.info("🔀RESOLVE via cachedSessionModelId=\(self.cachedSessionModelId) → entry=\(entry.id) (credentialed, outside default group)")
                return entry
            }
            // 2c. Last-ditch: first enabled (no credential) → first match.
            if let entry = matching.first(where: { e in
                store.instance(for: e.providerInstanceId)?.isEnabled == true
            }) {
                logger.warning("🔀RESOLVE via cachedSessionModelId=\(self.cachedSessionModelId) → entry=\(entry.id) (NO CREDENTIAL — request will likely fail)")
                return entry
            }
            if let entry = matching.first {
                logger.warning("🔀RESOLVE via cachedSessionModelId=\(self.cachedSessionModelId) → entry=\(entry.id) (instance disabled/missing)")
                return entry
            }
        }

        // 3. Try default group
        let resolveId = sessionId ?? "__resolve__"
        if let groupId = store.defaultPrimaryGroupId,
           let group = store.group(for: groupId),
           let entryId = ModelGroupRouter.resolve(group: group, sessionId: resolveId, store: store) {
            logger.info("🔀RESOLVE via default group=\(groupId) → entry=\(entryId)")
            return store.entry(for: entryId)
        }

        // 4. No config — return nil
        logger.warning("🔀RESOLVE no config store data, returning nil")
        return nil
    }

    /// Resolve a sub-model entry for lightweight tasks (title gen, etc.).
    func resolveSubEntry() -> ModelEntry? {
        let store = ProviderConfigStore.shared

        // 1. Session-specific sub model binding takes precedence — user
        //    explicitly picked a sub model for this conversation.
        //    [T-ios-regen-title-model-resolution #112] Resolution now goes
        //    through the shared static helper so this path and the static
        //    regenerate path can never drift apart again (the original #34
        //    availability fix was hand-copied into regenerateSessionTitle and
        //    the copy missed it).
        if let sid = sessionId, let binding = store.binding(for: sid),
           let sub = binding.subModelSource,
           let entry = Self.resolveSourceForSubTasks(sub, sessionId: sid, store: store) {
            return entry
        }

        // 2. No session-level sub binding — use the current session's primary
        //    model (NOT the global defaultSubGroup). This keeps title gen +
        //    other lightweight LLM calls on the same provider/model the user
        //    is actively chatting with, instead of silently jumping to a
        //    different default. Users who DO want a separate cheap sub model
        //    can configure it explicitly per-session.
        return resolveCurrentEntry()
    }

    // MARK: - [T-ios-regen-title-model-resolution #112] Shared sub-task source resolution

    /// Resolve one SessionModelSource for sub-tasks (title generation etc.).
    /// Stateless so BOTH the instance auto-title path (`resolveSubEntry`) and
    /// the static regenerate path use the same code — GitHub #112 was caused
    /// by a hand-copied inline version that missed #34's availability rules.
    ///
    /// Group sources ALWAYS re-route through ModelGroupRouter first, so a
    /// member-order change takes effect on the very next resolution (#112
    /// symptom ①). The router already filters to available members
    /// (enabled + credentialed + not hidden, #34), and both its strategies
    /// are deterministic (fallback → first available; loadBalance → stable
    /// hash), so when nothing changed the result equals the cached
    /// resolvedEntryId. The cached id is kept only as a last resort for the
    /// "router finds no available member right now" edge.
    static func resolveSourceForSubTasks(
        _ source: SessionModelSource,
        sessionId: String,
        store: ProviderConfigStore
    ) -> ModelEntry? {
        switch source {
        case .directEntry(let entryId, _):
            // Explicit user pick: honor it as long as the entry still exists
            // (deleted → nil so the caller can fall through, mirroring the
            // historical auto-path behavior).
            return store.entry(for: entryId)
        case .group(let groupId, let resolvedEntryId):
            if let group = store.group(for: groupId),
               let freshEntryId = ModelGroupRouter.resolve(group: group, sessionId: sessionId, store: store),
               let entry = store.entry(for: freshEntryId) {
                return entry
            }
            // No routable member (e.g. everything temporarily disabled) —
            // fall back to the cached resolution iff still usable.
            if let entry = store.entry(for: resolvedEntryId),
               let inst = store.instance(for: entry.providerInstanceId),
               inst.isEnabled, inst.hasAnyCredential, !entry.isHidden {
                return entry
            }
            return nil
        }
    }

    /// [T-titlegen-group-order] Expand one sub-task source into its FULL
    /// within-group traversal order — the router's first pick, then repeated
    /// `nextFallback` steps until the walk wraps back to the start. This is
    /// byte-for-byte the walk the agent loop performs on provider failures
    /// (resolve → nextFallback → …), so a title retry visits Model A → B → C
    /// of the bound group before any cross-tier degradation, instead of
    /// taking only the group's first pick and skipping its siblings.
    /// Direct entries expand to themselves.
    static func expandSourceForSubTasks(
        _ source: SessionModelSource,
        sessionId: String,
        store: ProviderConfigStore
    ) -> [ModelEntry] {
        switch source {
        case .directEntry(let entryId, _):
            return store.entry(for: entryId).map { [$0] } ?? []
        case .group(let groupId, let resolvedEntryId):
            guard let group = store.group(for: groupId) else {
                return store.entry(for: resolvedEntryId).map { [$0] } ?? []
            }
            var ids: [String] = []
            if let first = ModelGroupRouter.resolve(group: group, sessionId: sessionId, store: store) {
                ids.append(first)
                var current = first
                // nextFallback wraps around; stop when it revisits a member
                // (full cycle) or the group has a single available entry (nil).
                while let next = ModelGroupRouter.nextFallback(group: group, currentEntryId: current, store: store),
                      !ids.contains(next) {
                    ids.append(next)
                    current = next
                }
            } else if let entry = store.entry(for: resolvedEntryId),
                      let inst = store.instance(for: entry.providerInstanceId),
                      inst.isEnabled, inst.hasAnyCredential, !entry.isHidden {
                // Router found nothing routable — cached resolution as last
                // resort, mirroring resolveSourceForSubTasks.
                return [entry]
            }
            return ids.compactMap { store.entry(for: $0) }
        }
    }

    /// [T-ios-regen-title-model-resolution #112] Title-eligibility filter,
    /// aligned with Android's T334 rules (SessionListViewModel.regenerateTitle):
    /// the model must output text (no declared modalities counts as text) and
    /// its id must not name a non-chat capability — those endpoints reject the
    /// chat/completions schema or stream nothing useful.
    static func isTitleEligible(_ entry: ModelEntry) -> Bool {
        // Declared modalities without text output = pure TTS/image/video/ASR
        // model. No override (nil) counts as a normal text chat model —
        // mirrors Android's "outs == null || outs.isEmpty() || contains(text)".
        if let modality = entry.baseModel.modalityOverride, !modality.contains(.textOutput) {
            return false
        }
        let idLower = entry.model.id.lowercased()
        let nonChat = ["tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video"]
        return !nonChat.contains { idLower.contains($0) }
    }

    /// [T-ios-regen-title-model-resolution #112] Ordered candidate list for the
    /// regenerate-title fallback loop, mirroring Android:
    ///   session sub-model → session primary (binding / session.modelId /
    ///   default group) → every other available title-eligible entry.
    /// All candidates are deduped by entry id and must pass the availability
    /// (enabled + credentialed + visible) and title-eligibility filters.
    static func regenerateTitleCandidates(sessionId: String) async -> [ModelEntry] {
        let store = ProviderConfigStore.shared

        func isAvailable(_ entry: ModelEntry) -> Bool {
            guard let inst = store.instance(for: entry.providerInstanceId) else { return false }
            return inst.isEnabled && inst.hasAnyCredential && !entry.isHidden
        }

        var ordered: [ModelEntry] = []
        var seen = Set<String>()
        func push(_ entry: ModelEntry?) {
            guard let entry, !seen.contains(entry.id),
                  isAvailable(entry), Self.isTitleEligible(entry) else { return }
            seen.insert(entry.id)
            ordered.append(entry)
        }

        let binding = store.binding(for: sessionId)

        // Tier 1: explicit per-session sub model — the WHOLE bound group in
        // router order (resolve pick first, then nextFallback wrap), not just
        // its first member. [T-titlegen-group-order]
        if let sub = binding?.subModelSource {
            for entry in Self.expandSourceForSubTasks(sub, sessionId: sessionId, store: store) {
                push(entry)
            }
        }
        // Tier 2: the session's primary model (same full-group expansion).
        if let primary = binding?.primarySource {
            for entry in Self.expandSourceForSubTasks(primary, sessionId: sessionId, store: store) {
                push(entry)
            }
        }
        // Tier 2b: no binding (or unresolvable) — the session row's persisted
        // modelId (survives iCloud sync without a binding row).
        if ordered.isEmpty, let session = await ChatStore.shared.getSession(sessionId) {
            let mid = session.modelId
            if !mid.isEmpty {
                let matching = store.modelEntries.filter { $0.model.id == mid }
                push(matching.first(where: isAvailable))
            }
        }
        // Tier 2c: default primary group — full within-group walk as well.
        if let gid = store.defaultPrimaryGroupId {
            for entry in Self.expandSourceForSubTasks(
                .group(groupId: gid, resolvedEntryId: ""), sessionId: sessionId, store: store) {
                push(entry)
            }
        }
        // Tier 3: every other available title-eligible entry (fallback pool).
        for entry in store.modelEntries {
            push(entry)
        }
        return ordered
    }

}
