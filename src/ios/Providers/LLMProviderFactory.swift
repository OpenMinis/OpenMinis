import Foundation

/// Shared factory for creating LLMProvider instances from a ModelEntry.
/// Used by both the agent loop (AIChatViewModel) and minis-model-use offload bridge.
@MainActor
enum LLMProviderFactory {

    enum FactoryError: Error {
        case noInstance
        case noCredentials
        case voiceOnlyProvider
    }

    /// Create an LLMProvider for the given entry, looking up its ProviderInstance and credentials.
    /// - Parameter sessionId: the conversation this provider serves, or nil
    ///   when the call belongs to none (title generation, quick test, Vision).
    ///   Only used by the OpenCode channel.
    ///
    ///   Prefer `makeProvider(for:resolveSessionId:)` for anything attached to a
    ///   live conversation: a fixed value here is captured once, and a draft's
    ///   promotion to a real session would not be picked up.
    static func makeProvider(for entry: ModelEntry, sessionId: String? = nil) async throws -> any LLMProvider {
        try await makeProvider(for: entry, resolveSessionId: { sessionId })
    }

    /// Create an LLMProvider whose OpenCode session id is resolved **per
    /// request** rather than captured now.
    ///
    /// [T-opencode-dedicated-channel] This is the form every conversation
    /// should use. A provider outlives the draft→session promotion: a new chat
    /// builds its provider while `AIChatViewModel.sessionId` is still nil, and
    /// the real UUID only exists once `ensureSession()` persists the row on the
    /// first send. Capturing the id here left the FIRST turn of every new
    /// conversation with no `x-opencode-session` — a hard 400 from OpenCode Go,
    /// and cross-turn cache instability for anything that got past it.
    static func makeProvider(for entry: ModelEntry,
                             resolveSessionId: @escaping @Sendable () -> String?) async throws -> any LLMProvider {
        let store = ProviderConfigStore.shared
        guard let instance = store.instance(for: entry.providerInstanceId) else {
            throw FactoryError.noInstance
        }
        // [T-model-custom-params] / [T-opencode-dedicated-channel] One seam for
        // every OpenAI-family branch: the dedicated OpenCode channel, then the
        // entry's own overrides. Chained here rather than inside each `make*`
        // builder so the branches below cannot drift apart on which one
        // remembered to apply them.
        //
        // The channel is attached only for instances that belong to it, and it
        // installs a per-request hook rather than a snapshot header — see
        // `OpenCodeChannel.attach`.
        func finish(_ p: OpenAIProvider) -> OpenAIProvider {
            if instance.isOpenCodeChannel {
                OpenCodeChannel.attach(to: p, resolveSessionId: resolveSessionId)
            }
            return applyModelOverrides(p, entry: entry)
        }
        let openCode = finish
        switch instance.providerType {
        case .anthropic:
            return makeAnthropicProvider(instance: instance, model: entry.model)
        case .gemini:
            return await makeGeminiProvider(instance: instance, model: entry.model)
        case .openAI:
            return openCode(makeOpenAIProvider(instance: instance, model: entry.model))
        case .antigravity:
            return await makeAntigravityProvider(instance: instance, model: entry.model)
        case .openRouter:
            return openCode(makeOpenRouterProvider(instance: instance, model: entry.model))
        case .openAIResponses:
            return openCode(makeOpenAIResponsesProvider(instance: instance, model: entry.model))
        case .xAI:
            return openCode(makeXAIProvider(instance: instance, model: entry.model))
        case .kimiCode:
            return openCode(makeKimiProvider(instance: instance, model: entry.model))
        case .githubCopilot:
            return openCode(makeCopilotProvider(instance: instance, model: entry.model))
        case .unsupported:
            throw FactoryError.voiceOnlyProvider
        }
    }

    /// Inject the instance's custom `User-Agent` into an OpenAI-family provider's
    /// `extraHeaders` (which every request builder applies — chat/responses/models/
    /// image). Only for custom-base OpenAI/Anthropic-compat instances that are NOT
    /// OAuth: Codex OAuth requires its own `codex_cli_rs/...` UA to be accepted, so
    /// we never clobber it. Merges into any existing extraHeaders (e.g. OpenRouter
    /// attribution). No-op when no custom UA is set → default UA unchanged.
    /// Called from inside each OpenAI-family builder so BOTH makeProvider() and
    /// AIChatViewModel.makeAgentProvider() (which calls the builders directly) apply it.
    /// [T-ios-azure-openai] Flip the provider into Azure mode (api-key header +
    /// Azure URL) for instances that opted in. No-op (and zero behavior change)
    /// when azureMode is off, so non-Azure OpenAI/Responses instances are unaffected.
    @discardableResult
    static func applyAzure(_ provider: OpenAIProvider, instance: ProviderInstance) -> OpenAIProvider {
        if instance.azureMode { provider.isAzure = true }
        return provider
    }

    // MARK: - OpenCode Go
    //
    // [T-opencode-dedicated-channel] The header injection that used to live
    // here (`openCodeSessionHeader` / `isOpenCodeBaseURL` /
    // `applyOpenCodeSession`) has MOVED to `OpenCodeChannel`, and changed
    // shape while moving.
    //
    // It is no longer a base-URL sniff that stamps `extraHeaders` at
    // construction. Two things were wrong with that and neither was fixable by
    // improving the sniff: a provider is built while a new chat is still a
    // draft, so the captured id was nil for the FIRST turn of every
    // conversation (a hard 400 from OpenCode Go, then cache instability); and
    // sniffing asks "does this URL look like OpenCode" when the question is
    // "did the user configure this instance as OpenCode", which a self-hosted
    // relay answers differently.
    //
    // Channel membership is now `ProviderInstance.isOpenCodeChannel`, and the
    // id is resolved per request through `perRequestHeaders`. See
    // `OpenCodeChannel` for the full rationale.

    /// [T-model-custom-params] Carry the entry's user overrides onto the
    /// provider: custom headers, temperature, top_p and extra body params.
    ///
    /// Applied at construction, next to the other `apply*` steps, because the
    /// overrides belong to the ENTRY and the provider only knows its
    /// `LLMModel` — the entry is the single place that pairs a model with the
    /// user's edits to it.
    ///
    /// Headers go through `extraHeaders`, which every OpenAI request builder
    /// already applies. They are merged LAST so a per-model header wins over a
    /// provider-wide one; that is the point of setting it on one model. The
    /// reserved auth/routing keys are refused rather than merged — overwriting
    /// `Authorization` here would silently break the request with a value the
    /// settings screen never intended to be a credential.
    ///
    /// Entirely no-op when the entry has no overrides, so an unconfigured
    /// model builds exactly the request it did before.
    @discardableResult
    static func applyModelOverrides(_ provider: OpenAIProvider, entry: ModelEntry) -> OpenAIProvider {
        let o = entry.overrides
        if let headers = o.customHeaders, !headers.isEmpty {
            for (k, v) in headers where !isReservedOverrideHeader(k) {
                provider.extraHeaders[k] = v
            }
        }
        if let t = o.temperature { provider.overrideTemperature = t }
        if let p = o.topP { provider.overrideTopP = p }
        if let extra = o.extraBodyParams, !extra.isEmpty {
            provider.overrideExtraBody = extra
        }
        return provider
    }

    /// Headers a per-model override must never set.
    ///
    /// `Authorization` / `api-key` / `x-api-key` carry the credential the
    /// provider just resolved; `Content-Type` and `Content-Length` describe a
    /// body the encoder owns; `Host` routes the request. Letting a text field
    /// in the model editor replace any of these turns a settings typo into a
    /// failed or misrouted call with no obvious cause.
    static func isReservedOverrideHeader(_ name: String) -> Bool {
        let reserved: Set<String> = [
            "authorization", "api-key", "x-api-key", "anthropic-version",
            "content-type", "content-length", "host",
        ]
        return reserved.contains(name.lowercased())
    }

    @discardableResult
    static func applyCustomUserAgent(_ provider: OpenAIProvider, instance: ProviderInstance) -> OpenAIProvider {
        // OAuth (Codex) requires its own `codex_cli_rs/...` UA — never touch it.
        guard !provider.isOAuth else { return provider }
        // A user-set per-provider custom UA wins (only honored for
        // custom-base proxy/relay instances, per supportsCustomUserAgent).
        if instance.supportsCustomUserAgent, let ua = instance.effectiveCustomUserAgent {
            provider.extraHeaders["User-Agent"] = ua
            return provider
        }
        // Otherwise inject the app default UA so outbound requests carry the
        // marketing version (Minis/1.10 …) instead of URLSession's build-number
        // default (Minis/1 CFNetwork/… Darwin/…). Don't clobber a UA another
        // builder already set (e.g. some future provider-specific UA).
        if provider.extraHeaders["User-Agent"] == nil {
            provider.extraHeaders["User-Agent"] = MinisUserAgent.default
        }
        return provider
    }

    // MARK: - Per-Provider Builders

    static func makeAnthropicProvider(instance: ProviderInstance, model: LLMModel) -> AnthropicProvider {
        let customBase = instance.effectiveCustomBaseURL
        let appendV1 = instance.appendV1Suffix
        // Only custom-base Anthropic-compat (proxy/relay) instances get a custom UA.
        // The OAuth (Claude Code) path below is deliberately excluded — its required
        // `claude-cli/...` UA is set in OAuthURLProtocol and must not be overridden.
        let ua = instance.supportsCustomUserAgent ? instance.effectiveCustomUserAgent : nil
        switch instance.credentialType {
        case .apiKey:
            let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) ?? ""
            // No user custom UA → send the app default (Minis/<marketing>) so the
            // SDK (which sets no UA itself) doesn't fall back to URLSession's
            // build-number default. OAuth branches below keep nil so the
            // claude-cli UA set in OAuthURLProtocol is preserved.
            return AnthropicProvider(apiKey: key, model: model, basePath: customBase, appendV1Suffix: appendV1, customUserAgent: ua ?? MinisUserAgent.default)
        case .oauth:
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                return AnthropicProvider(manualToken: manualToken, model: model, basePath: customBase, appendV1Suffix: appendV1, customUserAgent: ua)
            }
            let iid = instance.id
            return AnthropicProvider(
                oauthTokenProvider: { try await ClaudeOAuthManager.shared.validAccessToken(instanceId: iid) },
                model: model,
                basePath: customBase,
                appendV1Suffix: appendV1
            )
        }
    }

    static func makeGeminiProvider(instance: ProviderInstance, model: LLMModel) async -> GeminiProvider {
        let customBase = instance.effectiveCustomBaseURL
        switch instance.credentialType {
        case .apiKey:
            let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) ?? ""
            return GeminiProvider(apiKey: key, model: model, customBasePath: customBase)
        case .oauth:
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                return GeminiProvider(apiKey: manualToken, model: model, customBasePath: customBase)
            }
            let iid = instance.id
            if GeminiOAuthManager.shared.gcpProjectID(instanceId: iid) == nil {
                await GeminiOAuthManager.shared.discoverProjectIfNeeded(instanceId: iid)
            }
            let provider = GeminiProvider(
                oauthTokenProvider: { try await GeminiOAuthManager.shared.validAccessToken(instanceId: iid) },
                model: model,
                customBasePath: customBase
            )
            provider.gcpProjectID = GeminiOAuthManager.shared.gcpProjectID(instanceId: iid)
            return provider
        }
    }

    static func makeOpenAIProvider(instance: ProviderInstance, model: LLMModel) -> OpenAIProvider {
        let customBase = instance.effectiveCustomBaseURL
        let appendV1 = instance.appendV1Suffix
        // Mistral's chat-completions endpoint rejects `max_completion_tokens`
        // (newer OpenAI parameter name) and `stream_options` with HTTP 422,
        // and ALSO rejects the `reasoning: {effort: …}` body. Reuse the
        // OpenRouter `max_tokens` body builder via `useOpenRouterCompat`
        // (it switches max_tokens + drops stream_options at once) but mark
        // `isMistral` so the agent loop skips the OpenRouter thinking-param
        // auto-inject.
        let isMistral = (customBase ?? "").lowercased().contains("mistral.ai")
        func configure(_ p: OpenAIProvider) -> OpenAIProvider {
            if isMistral {
                p.useOpenRouterCompat = true
                p.isMistral = true
            }
            // [T-thinking-rules-phase2] Carry the instance id so the thinking resolver can
            // load this provider's user-authored rules. Set for every OpenAI-family
            // provider, not just Mistral.
            p.providerInstanceId = instance.id
            return p
        }
        switch instance.credentialType {
        case .apiKey:
            let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) ?? ""
            return applyAzure(applyCustomUserAgent(configure(OpenAIProvider(apiKey: key, model: model, customBaseURL: customBase, appendV1Suffix: appendV1)), instance: instance), instance: instance)
        case .oauth:
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                return applyCustomUserAgent(configure(OpenAIProvider(apiKey: manualToken, model: model, customBaseURL: customBase, appendV1Suffix: appendV1)), instance: instance)
            }
            let iid = instance.id
            let provider = OpenAIProvider(
                oauthTokenProvider: { try await CodexOAuthManager.shared.validAccessToken(instanceId: iid) },
                model: model
            )
            provider.codexAccountId = CodexOAuthManager.shared.accountId(instanceId: iid)
            return provider
        }
    }

    static func makeOpenRouterProvider(instance: ProviderInstance, model: LLMModel) -> OpenAIProvider {
        let key: String
        if instance.credentialType == .oauth,
           let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
            key = manualToken
        } else {
            key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) ?? ""
        }
        let customBase = instance.effectiveCustomBaseURL
        let provider = OpenAIProvider(apiKey: key, model: model, customBaseURL: customBase ?? "https://openrouter.ai/api", appendV1Suffix: customBase == nil)
        provider.extraHeaders = [
            "HTTP-Referer": "https://github.com/OpenMinis/OpenMinis",
            "X-Title": "Minis App",
        ]
        provider.useOpenRouterCompat = true
        return applyCustomUserAgent(provider, instance: instance)
    }

    static func makeOpenAIResponsesProvider(instance: ProviderInstance, model: LLMModel) -> OpenAIProvider {
        let customBase = instance.effectiveCustomBaseURL
        let appendV1 = instance.appendV1Suffix
        // Both credential types are valid for Responses API instances
        // (e.g. an OpenAI2 instance configured with the user's Codex
        // OAuth login). Previously this branch only loaded an API key
        // and silently produced an empty-auth request when the user had
        // OAuth-only credentials — that's the model-use "Codex model
        // call fails" case (T-model-use-codex-responses-api-34889).
        switch instance.credentialType {
        case .apiKey:
            let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) ?? ""
            let provider = OpenAIProvider(apiKey: key, model: model, customBaseURL: customBase, appendV1Suffix: appendV1)
            provider.forceResponsesAPI = true
            return applyAzure(applyCustomUserAgent(provider, instance: instance), instance: instance)
        case .oauth:
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                let provider = OpenAIProvider(apiKey: manualToken, model: model, customBaseURL: customBase, appendV1Suffix: appendV1)
                provider.forceResponsesAPI = true
                return applyCustomUserAgent(provider, instance: instance)
            }
            let iid = instance.id
            let provider = OpenAIProvider(
                oauthTokenProvider: { try await CodexOAuthManager.shared.validAccessToken(instanceId: iid) },
                model: model
            )
            provider.customBaseURL = customBase
            provider.appendV1Suffix = appendV1
            provider.forceResponsesAPI = true
            provider.codexAccountId = CodexOAuthManager.shared.accountId(instanceId: iid)
            return provider
        }
    }

    /// Kimi Code / Coding Plan — OpenAI-compatible coding upstream reached with
    /// the device-code OAuth bearer. Mirrors makeXAIProvider (custom base +
    /// OAuth bearer through OpenAIProvider).
    /// [T-copilot-provider] GitHub Copilot. UNOFFICIAL — see CopilotConstants.
    ///
    /// Rides `OpenAIProvider` because the chat surface below
    /// `api.githubcopilot.com` is OpenAI-compatible; what is Copilot-specific is
    /// the credential and a fixed set of headers, and both have a seam here
    /// already (`oauthTokenProvider`, `extraHeaders`).
    ///
    /// The token closure returns the SHORT-LIVED session token, minting one from
    /// the stored GitHub token whenever the cached one is near expiry — so the
    /// two-layer lifetime is invisible above this line.
    ///
    /// The headers applied here are the static ones. `X-Initiator` and
    /// `Copilot-Vision-Request` genuinely vary per request, and this builder
    /// cannot see the message list; they are handled at the request site rather
    /// than pinned to a wrong constant value here.
    ///
    /// [T-copilot-per-request-headers] That request site is now real. The
    /// closure below is called by the OpenAI request builders with the outgoing
    /// body, which is the first point that knows whether this turn is
    /// agent-driven and whether images are attached. Before this, the per-request
    /// headers were computed by a function nothing called, so they never shipped.
    static func makeCopilotProvider(instance: ProviderInstance, model: LLMModel) -> OpenAIProvider {
        let iid = instance.id
        let provider = OpenAIProvider(
            oauthTokenProvider: { try await CopilotOAuthManager.shared.validSessionToken(instanceId: iid) },
            model: model
        )
        provider.customBaseURL = instance.effectiveCustomBaseURL ?? CopilotConstants.apiBaseURL
        // Endpoints are `/chat/completions` and `/models` directly under the
        // host — no `/v1` segment, unlike most OpenAI-compatible vendors.
        provider.appendV1Suffix = false
        provider.extraHeaders.merge(CopilotConstants.baseHeaders) { _, new in new }
        provider.perRequestHeaders = { body in
            CopilotConstants.perRequestHeaders(forBody: body)
        }
        return provider
    }

    static func makeKimiProvider(instance: ProviderInstance, model: LLMModel) -> OpenAIProvider {
        let customBase = instance.effectiveCustomBaseURL ?? "https://api.kimi.com/coding"
        // The default Kimi coding base is `…/coding` WITHOUT `/v1`; the real
        // endpoints are `/coding/v1/chat/completions` and `/coding/v1/models`
        // (verified: `/coding/chat/completions` 404s, `/coding/v1/…` needs auth).
        // So append `/v1` for the default base; a user-supplied custom base keeps
        // their own appendV1 preference.
        let appendV1 = instance.effectiveCustomBaseURL == nil ? true : instance.appendV1Suffix
        switch instance.credentialType {
        case .apiKey:
            let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) ?? ""
            return applyCustomUserAgent(OpenAIProvider(apiKey: key, model: model, customBaseURL: customBase, appendV1Suffix: appendV1), instance: instance)
        case .oauth:
            // [T-kimi-manual-token-ignored] A manually-pasted bearer token has
            // to be honoured here, exactly as the Anthropic / Gemini / xAI
            // branches do. "Or Configure Manually" saves the token under
            // `manual-oauth-token` with `credentialType: .oauth`, and its own
            // footer names Kimi as the intended use case ("For third-party
            // Coding Plans (e.g. MiniMax, Kimi) … enter the API base URL and
            // bearer token manually"). Without this check the token was stored
            // and displayed as configured, then ignored: the branch went
            // straight to the device-flow manager, which found no device-flow
            // token and failed with "Kimi: no OAuth token found" — an error that
            // makes no sense to a user who just supplied one.
            if let manualToken = ProviderKeychainHelper.loadOAuthString(
                instanceId: instance.id, account: "manual-oauth-token") {
                return applyCustomUserAgent(
                    OpenAIProvider(apiKey: manualToken, model: model,
                                   customBaseURL: customBase, appendV1Suffix: appendV1),
                    instance: instance)
            }
            let iid = instance.id
            let provider = OpenAIProvider(
                oauthTokenProvider: { try await KimiOAuthManager.shared.validAccessToken(instanceId: iid) },
                model: model
            )
            provider.customBaseURL = customBase
            provider.appendV1Suffix = appendV1
            return provider
        }
    }

    static func makeXAIProvider(instance: ProviderInstance, model: LLMModel) -> OpenAIProvider {
        let customBase = instance.effectiveCustomBaseURL ?? "https://api.x.ai/v1"
        // Custom base ships with /v1 — never re-append.
        let appendV1 = instance.effectiveCustomBaseURL == nil ? false : instance.appendV1Suffix
        switch instance.credentialType {
        case .apiKey:
            let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id) ?? ""
            return applyCustomUserAgent(OpenAIProvider(apiKey: key, model: model, customBaseURL: customBase, appendV1Suffix: appendV1), instance: instance)
        case .oauth:
            if let manualToken = ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") {
                return applyCustomUserAgent(OpenAIProvider(apiKey: manualToken, model: model, customBaseURL: customBase, appendV1Suffix: appendV1), instance: instance)
            }
            let iid = instance.id
            let provider = OpenAIProvider(
                oauthTokenProvider: { try await XAIOAuthManager.shared.validAccessToken(instanceId: iid) },
                model: model
            )
            provider.customBaseURL = customBase
            provider.appendV1Suffix = appendV1
            return provider
        }
    }

    static func makeAntigravityProvider(instance: ProviderInstance, model: LLMModel) async -> AntigravityProvider {
        let iid = instance.id
        if AntigravityOAuthManager.shared.projectID(instanceId: iid) == nil {
            await AntigravityOAuthManager.shared.discoverProjectIfNeeded(instanceId: iid)
        }
        let provider = AntigravityProvider(
            oauthTokenProvider: { try await AntigravityOAuthManager.shared.validAccessToken(instanceId: iid) },
            model: model
        )
        provider.projectID = AntigravityOAuthManager.shared.projectID(instanceId: iid)
        if let baseURL = AntigravityOAuthManager.shared.resolvedBaseURL(instanceId: iid) {
            provider.activeBaseURL = baseURL
        }
        return provider
    }
}
