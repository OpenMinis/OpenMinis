import Foundation
import os.log

private let logger = AppLogger(category: "OpenAIProvider")

/// Strips a trailing "/v1" segment (with or without trailing slash) from a
/// base URL. Used when the caller will explicitly append "/v1/<endpoint>"
/// itself — without this, a user-supplied base like "https://api.x.com/v1/"
/// would produce "/v1/v1/<endpoint>". Slash normalization is delegated to
/// `URLBuilding.join`.
private func stripV1Suffix(_ base: String) -> String {
    var s = base
    while s.hasSuffix("/") { s = String(s.dropLast()) }
    if s.hasSuffix("/v1") { s = String(s.dropLast(3)) }
    return s
}

/// Resolve the base URL for an endpoint. When `appendV1` is true, the caller
/// will tack on "/v1/<endpoint>" via `URLBuilding.join`, so we must drop any
/// existing trailing "/v1" first. When `appendV1` is false, the user is
/// managing the full path themselves (e.g. base already ends in `/v1`) — return
/// it verbatim so `URLBuilding.join(base, "/chat/completions")` lands on
/// `<base>/chat/completions`.
private func resolvedAPIBase(_ base: String, appendV1: Bool) -> String {
    appendV1 ? stripV1Suffix(base) : base
}

/// URLSession with a long read timeout for SSE streaming.
/// URLSession.shared uses a 60-second timeoutIntervalForRequest which can kill
/// long-running streams (e.g. large file_write generation). This session gives
/// the server 10 minutes of idle time before timing out.
///
/// [T-ios-stream-connection-reuse] Used by the NON-STREAMING calls only
/// (image generation, model listing, image download). Those complete inside a
/// single `data(for:)` await, so a shared pool is exactly right for them:
/// they benefit from connection reuse and never sit idle holding a stream
/// open. The SSE streaming path deliberately does NOT use this session — see
/// `makeStreamingSession()`.
private let streamingSession: URLSession = {
    let config = URLSessionConfiguration.default
    config.timeoutIntervalForRequest = 600  // 10 minutes
    let session = URLSession(configuration: config)
    // Evict this session's pooled (possibly stale) connections on network
    // transitions — see LLMSessionRegistry / Android #740.
    LLMSessionRegistry.shared.register(session)
    return session
}()

/// [T-ios-stream-connection-reuse] A FRESH session for one SSE stream, torn
/// down when that stream ends.
///
/// Why per-request rather than the shared pool above: the shared session is a
/// process-level singleton, so its pooled HTTP/2 connections to the provider
/// survive for as long as the app runs — observed on device holding a single
/// connection open for 20+ minutes while backgrounded. HTTP/2 multiplexes
/// streams over one connection, so when a relay silently drops or wedges the
/// stream carrying our request, the socket stays healthy and CFNetwork has
/// nothing to report: the read simply never produces another byte. That is
/// exactly the shape of the 2026-08-18 22:11 field stall — connection
/// established normally (1.87s), 47 events inside the first 50ms, then 120.0s
/// of perfect silence with no transport-level error at all.
///
/// URLSession exposes no per-request "don't reuse" switch (HTTP/2 forbids the
/// `Connection` header, and there is no pool-age API), so the only way to
/// guarantee a stream never inherits a stale multiplexed connection is to give
/// it its own session and invalidate that session when the stream finishes.
///
/// This mirrors what the Anthropic path already does in practice: SwiftAnthropic
/// routes through a `URLProtocol`, which Foundation instantiates once PER
/// REQUEST, and each instance lazily creates its own inner `URLSession`. There
/// the per-request session is a side effect of needing to rewrite auth headers
/// on a SDK whose request type is module-internal; here we own the code, so we
/// get the same connection behaviour directly, without the interception layer.
///
/// Deliberately NOT registered with `LLMSessionRegistry`: that registry exists
/// to evict long-lived pooled connections on network-interface changes, and a
/// session that lives for exactly one stream has nothing to evict.
private func makeStreamingSession() -> URLSession {
    let config = URLSessionConfiguration.default
    config.timeoutIntervalForRequest = 600  // 10 minutes — matches the shared session
    // One stream per session, so there is no second connection to pool.
    config.httpMaximumConnectionsPerHost = 1
    return URLSession(configuration: config)
}

/// LLMProvider implementation for OpenAI models.
/// Supports two modes:
///   - API Key: Standard Chat Completions API at api.openai.com
///   - OAuth (Codex): Responses API at chatgpt.com/backend-api/codex/responses
final class OpenAIProvider: LLMProvider {
    static let codexClientVersion = "0.144.1"

    /// [T-codex-fast-mode] UserDefaults key for the Codex Fast Mode toggle.
    /// Read at request-build time (not cached at init) so flipping the "..."
    /// menu toggle affects the very next request of an ongoing session.
    static let fastModeDefaultsKey = "codexFastModeEnabled"

    let name = "OpenAI"
    var model: LLMModel

    enum AuthMode {
        case apiKey(String)
        case oauth(() async throws -> String)
    }

    let authMode: AuthMode
    /// Codex account ID for OAuth mode (set by provider factory).
    var codexAccountId: String?
    /// Optional custom API base URL for proxy services (e.g. "https://my-proxy.com").
    var customBaseURL: String?
    /// When false, customBaseURL is used verbatim without appending "/v1".
    var appendV1Suffix: Bool
    /// Extra HTTP headers to include in every request (e.g. OpenRouter attribution).
    var extraHeaders: [String: String] = [:]

    /// [T-model-use-image-passthrough GH#62] Arbitrary extra fields merged into the
    /// /images/generations JSON body, so `minis-model-use` can pass provider-specific
    /// params our fixed schema never modeled (e.g. Volcengine Seedream's `image` for
    /// image-to-image, `watermark`, `tools`). User keys WIN over our defaults
    /// (response_format) but never replace the resolved `model`. Empty = no passthrough.
    var imageExtraBody: [String: Any] = [:]

    /// [T-model-use-chat-passthrough GH#72] Arbitrary extra fields merged into
    /// the chat/completions and responses request bodies, mirroring
    /// `imageExtraBody` on the image path. Populated per-call by the
    /// minis-model-use bridge from the input JSON's explicit `extra_body`
    /// envelope (never from implicit top-level keys — the chat schema owns
    /// its top level). User keys WIN over our defaults (e.g. `plugins`,
    /// `web_search_options`, provider-specific knobs) but `model` is
    /// force-restored after the merge. Empty = no passthrough (default).
    var chatExtraBody: [String: Any] = [:]

    /// [T-model-use-endpoint-override] Absolute-path endpoint override.
    /// When set (must start with "/"), it replaces the ENTIRE URL path after
    /// scheme+host — unlike the legacy `imagePathOverride`, which is joined
    /// after `base [+ /v1]` and therefore can never escape a base-URL prefix
    /// like `/compatible-mode/v1` (proven by device baseline p03). Applies to
    /// chat/completions, responses, and images/generations builders. Never
    /// applies to Codex OAuth (hardcoded backend). Per-call, never persisted.
    var absoluteEndpointOverride: String? = nil

    /// [T-model-use-image-passthrough GH#62] Optional endpoint path override for the
    /// image request (e.g. a non-standard `/api/v3/images/generations`). When set, it
    /// replaces the hardcoded `/images/generations` path (base URL + this verbatim,
    /// preserving any query). nil = default path.
    var imagePathOverride: String? = nil
    /// When true, use `max_tokens` instead of `max_completion_tokens` and skip `stream_options`.
    /// OpenRouter's API doesn't support the newer OpenAI parameter names.
    var useOpenRouterCompat: Bool = false
    /// When true, this is Mistral's chat-completions endpoint. It rejects
    /// `max_completion_tokens` / `stream_options` (use legacy `max_tokens`)
    /// AND rejects the `reasoning: {effort: …}` body that OpenRouter accepts.
    /// We treat this as a strict subset of OpenRouter compat so the agent
    /// loop doesn't auto-inject thinking params.
    var isMistral: Bool = false

    /// [T-thinking-rules-phase2] The provider instance this was built from, so the
    /// thinking resolver can load user-authored rules for it. nil for providers built
    /// outside the instance factory (title-gen references, tests) — those simply resolve
    /// against the built-in registry, which is the pre-Phase-2 behaviour.
    var providerInstanceId: String?
    /// When true, always use the Responses API format (/v1/responses) regardless of auth mode.
    /// Used by the "Responses API" provider type for third-party Responses-compatible endpoints.
    var forceResponsesAPI: Bool = false

    /// [T-ios-azure-openai] Azure OpenAI mode. When true:
    ///   • auth uses the `api-key:` header instead of `Authorization: Bearer`;
    ///   • the request URL is built from the user-supplied Azure base URL, which
    ///     may carry a `?api-version=…` query — that query is preserved and the
    ///     endpoint path (`/chat/completions` or `/responses`) is appended before it.
    /// Defaults to false, so every existing OpenAI / Responses path is byte-for-byte
    /// unchanged. Set by LLMProviderFactory for instances with `azureMode == true`.
    var isAzure: Bool = false

    /// Whether this provider points at Alibaba DashScope (supports Qwen cache_control).
    var isDashScope: Bool {
        guard let base = customBaseURL?.lowercased() else { return false }
        return base.contains("dashscope")
    }

    /// Whether this provider actually talks to OpenRouter.
    ///
    /// Deliberately NOT `useOpenRouterCompat`: that flag only means "use the
    /// legacy `max_tokens` / no-`stream_options` body shape", and Mistral sets it
    /// too (see `isMistral`, documented as "a strict subset of OpenRouter
    /// compat"). Anything gated on being genuinely OpenRouter has to match the
    /// host, the same way `isDashScope` does.
    ///
    /// Carries the same caveat as `isMistral` / `usesUnifiedReasoningEffort`: a
    /// relay or vanity domain that doesn't carry `openrouter.ai` in its URL is
    /// not recognised, which fails safe — the request simply goes out unchanged.
    var isOpenRouter: Bool {
        guard let base = customBaseURL?.lowercased() else { return false }
        return base.contains("openrouter.ai")
    }

    /// [GH#191] OpenRouter does NOT enable Anthropic prompt caching automatically
    /// (unlike the OpenAI / Grok / Moonshot / Groq models it hosts, which cache
    /// with no opt-in). Per OpenRouter's prompt-caching guide, Claude requests
    /// must carry an explicit `cache_control` breakpoint or nothing is cached at
    /// all — which is why the reporter measured `cache_read_input_tokens` and
    /// `cache_write_tokens` pinned at 0 and a 3–6x cost overrun.
    ///
    /// Matched on the `anthropic/` model-id prefix, which is OpenRouter's
    /// namespace for the Claude family (`anthropic/claude-sonnet-4`,
    /// `anthropic/claude-opus-4.1`, …). Scoped to OpenRouter AND that prefix so
    /// every other model on the gateway — and every non-OpenRouter provider that
    /// happens to share `useOpenRouterCompat` — keeps a byte-identical body.
    var needsOpenRouterAnthropicCacheControl: Bool {
        isOpenRouter && model.id.lowercased().hasPrefix("anthropic/")
    }

    /// [T-unified-reasoning-effort] Whether this endpoint applies OpenAI's
    /// `reasoning_effort` (Chat) / `reasoning.effort` (Responses) uniformly to
    /// EVERY model it hosts — including third-party families (GLM / Kimi /
    /// DeepSeek / MiniMax) that, when hit at their vendor-native endpoint, would
    /// instead use a `thinking:{}` object or self-reason with no toggle.
    ///
    /// Three known such gateways:
    ///   • Volcengine Ark (`ark.` / `volces` in the base URL) — the platform
    ///     re-exposes doubao/deepseek/glm/kimi through a single OpenAI-compatible
    ///     surface where thinking is controlled ONLY by `reasoning_effort`
    ///     (min tier `minimal`); the vendor-native `thinking:{}` shape is not
    ///     honored, so a GLM/DeepSeek id must NOT take its native branch here.
    ///   • Azure OpenAI (`isAzure`) — reasoning is `reasoning_effort` for every
    ///     model surfaced through the deployment.
    ///   • Venice.ai (`api.venice.ai`) — [OpenMinis#86] resells deepseek /
    ///     claude / aion behind one OpenAI-compatible surface. Its
    ///     `ChatCompletionRequest` schema is `additionalProperties: false`, so an
    ///     unknown root key is rejected at validation time — BEFORE model
    ///     dispatch — with `400 Unrecognized key(s) in object: 'thinking'`. That
    ///     is why the reporter saw every model fail and why turning thinking OFF
    ///     did not help: the `{"type":"disabled"}` branch still sends the key.
    ///     Venice natively accepts root `reasoning_effort`
    ///     (none/minimal/low/medium/high/xhigh/max), a superset of the tiers the
    ///     generic path emits, so no value mapping is needed.
    ///
    /// Gated tightly so official direct endpoints (DeepSeek/GLM/Kimi native,
    /// which DO want their own thinking shape) are never mis-routed: it requires
    /// an explicit Ark/Venice base-URL match or the Azure flag, and nothing else.
    /// Caveat (same class as `isMistral`): a relay or vanity domain that does not
    /// carry these hosts in its URL is still exposed.
    var usesUnifiedReasoningEffort: Bool {
        if isAzure { return true }
        guard let base = customBaseURL?.lowercased() else { return false }
        return base.contains("volces") || base.contains("ark.")
            || base.contains("api.venice.ai")
    }

    /// [OpenMinis#163] Talking to xAI's own API (api.x.ai), as opposed to a
    /// relay that merely serves grok-named models.
    ///
    /// Scopes the "catalog declares no effort tiers → omit `reasoning_effort`"
    /// skip to first-party xAI. The catalog marks 1292 models across many
    /// vendors with the same empty-tier shape (relay-hosted Claude, GPT-5,
    /// Qwen …), and while omitting the field for those is arguably more
    /// correct too, none of those routes has been verified — so the skip stays
    /// where the 400 was actually observed.
    ///
    /// URL-matching alone is sufficient: `makeXAIProvider` always populates
    /// `customBaseURL`, defaulting to `https://api.x.ai/v1` when the user set
    /// no override (LLMProviderFactory:254), so a first-party xAI provider is
    /// never seen here with a nil base.
    var isXAI: Bool {
        guard let base = customBaseURL?.lowercased() else { return false }
        return base.contains("api.x.ai") || base.contains("//x.ai")
    }

    var isOAuth: Bool {
        if case .oauth = authMode { return true }
        return false
    }

    // MARK: - Azure helpers [T-ios-azure-openai]

    /// Apply auth to a non-OAuth request. Azure uses the `api-key` header; every
    /// other OpenAI-compatible endpoint uses `Authorization: Bearer`. Centralized
    /// so the Azure branch can never accidentally set the wrong header.
    private func applyKeyAuth(_ request: inout URLRequest, token: String) {
        // [T-empty-key-compat-endpoints] A keyless third-party endpoint
        // (ollama, LM Studio, LiteLLM, private relays) is a supported
        // configuration. Send NO auth header rather than a malformed
        // `Authorization: Bearer ` / empty `api-key:` — strict gateways
        // reject the empty form, whereas an absent header is universally fine.
        guard !token.isEmpty else { return }
        if isAzure {
            request.setValue(token, forHTTPHeaderField: "api-key")
        } else {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
    }

    /// [T-model-use-endpoint-override] Build a URL from the provider's
    /// scheme+host(+port) ONLY, with `path` replacing the entire URL path.
    /// `path` must start with "/" and may carry a query string. Credentials
    /// stay bound to the instance's host — callers can never point this at
    /// a different host.
    func hostRootURL(_ path: String) -> URL? {
        let baseStr = customBaseURL ?? "https://api.openai.com"
        guard var comps = URLComponents(string: baseStr) else { return nil }
        // Split optional query off the override path.
        let qIdx = path.firstIndex(of: "?")
        comps.path = qIdx.map { String(path[..<$0]) } ?? path
        comps.query = qIdx.map { String(path[path.index(after: $0)...]) }
        comps.fragment = nil
        return comps.url
    }

    /// Resolve the effective URL for a modeled endpoint, honoring the
    /// absolute-path override when present.
    private func endpointURL(defaultPath: String) -> URL? {
        if let abs = absoluteEndpointOverride, abs.hasPrefix("/") {
            return hostRootURL(abs)
        }
        let base = resolvedAPIBase(customBaseURL ?? "https://api.openai.com", appendV1: appendV1Suffix)
        let v1Path = appendV1Suffix ? "/v1" : ""
        return URL(string: URLBuilding.join(base, v1Path, defaultPath))
    }

    /// [T-ios-custom-endpoint-url-crash] `endpointURL` / `URL(string:)` return
    /// nil for a base the user typed wrong, and every call site used to force-
    /// unwrap that — so a typo in the provider's Base URL field crashed the app
    /// instead of showing an error.
    ///
    /// `effectiveCustomBaseURL` already trims surrounding whitespace, so the
    /// surviving nil cases are malformations INSIDE the string: a space in the
    /// host or port (`https://10.0.0.5 :8080`, `https://10.0.0.5:80 80`), a
    /// non-numeric port (`https://host:port`), a stray control character from a
    /// paste. All of them are ordinary user error and must surface as a
    /// readable message.
    ///
    /// The message names the offending value and the field to fix, because at
    /// the point this fires the user is staring at a request that failed for
    /// no visible reason.
    private func requireEndpointURL(defaultPath: String) throws -> URL {
        guard let url = endpointURL(defaultPath: defaultPath) else {
            let shown = customBaseURL ?? "(default)"
            throw LLMError.providerError(
                message: "Invalid endpoint URL: \(shown). Check the provider's Base URL — it must be a full URL such as https://host:port, with no spaces."
            )
        }
        return url
    }

    /// [T-ios-custom-endpoint-url-crash] Same guard for the two `streamRaw`
    /// sites, which build their URL inline rather than through `endpointURL`.
    private func requireURL(_ string: String) throws -> URL {
        guard let url = URL(string: string) else {
            let shown = customBaseURL ?? "(default)"
            throw LLMError.providerError(
                message: "Invalid endpoint URL: \(shown). Check the provider's Base URL — it must be a full URL such as https://host:port, with no spaces."
            )
        }
        return url
    }

    /// Build the request URL for Azure OpenAI. Mirrors the official
    /// `AzureOpenAI` SDK URL shape:
    ///
    ///   {azure_endpoint}/openai/deployments/{deployment}/{path}?api-version=…
    ///
    /// where `deployment` is the model id and `path` is e.g. `chat/completions`.
    /// The user pastes the resource/endpoint as the custom base — typically the
    /// bare `azure_endpoint` (`https://x.openai.azure.com`), optionally already
    /// including `/openai`, and Minis instructs them to put `?api-version=…` on
    /// the base. We:
    ///   1. split off the `?api-version=…` query,
    ///   2. strip a trailing `/`, and a trailing `/openai` (we re-add it) and any
    ///      stray `/v1` (Azure has no `/v1` — it uses the deployments path),
    ///   3. assemble `<base>/openai/deployments/<model.id>/<path>` + query.
    /// Returns nil if no custom base is set (Azure requires an explicit endpoint).
    ///
    /// [T-ios-azure-openai-deployments] Previously this produced
    /// `<base>/chat/completions?api-version=…`, omitting the mandatory
    /// `/openai/deployments/{deployment}` segment, so every Azure request 404'd
    /// or hit the wrong route.
    private func azureURL(path: String) -> URL? {
        guard let base = customBaseURL?.trimmingCharacters(in: .whitespacesAndNewlines), !base.isEmpty else {
            return nil
        }
        var query = ""
        var pathPart = base
        if let qIdx = base.firstIndex(of: "?") {
            query = String(base[qIdx...])   // includes leading "?"
            pathPart = String(base[..<qIdx])
        }
        var p = pathPart
        while p.hasSuffix("/") { p = String(p.dropLast()) }
        // Azure uses the deployments path, never `/v1`; drop a stray one the user
        // (or the Auto-Append "/v1" toggle) may have left on the base.
        if p.hasSuffix("/v1") { p = String(p.dropLast(3)) }
        while p.hasSuffix("/") { p = String(p.dropLast()) }
        // Re-add `/openai` exactly once (the base may or may not include it).
        if p.hasSuffix("/openai") { p = String(p.dropLast("/openai".count)) }
        while p.hasSuffix("/") { p = String(p.dropLast()) }

        let deployment = model.id
        let endpointPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let full = "\(p)/openai/deployments/\(deployment)/\(endpointPath)\(query)"
        return URL(string: full)
    }

    init(apiKey: String, model: LLMModel = .gpt4oMini, customBaseURL: String? = nil, appendV1Suffix: Bool = true) {
        self.model = model
        self.authMode = .apiKey(apiKey)
        self.customBaseURL = customBaseURL
        self.appendV1Suffix = appendV1Suffix
    }

    init(oauthTokenProvider: @escaping @Sendable () async throws -> String, model: LLMModel = .codexMini) {
        self.model = model
        self.authMode = .oauth(oauthTokenProvider)
        self.appendV1Suffix = true
    }

    // MARK: - LLMProvider

    func sendMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> LLMResponse {
        let useChat = usesChatCompletionsAPI

        // [T-codex-oauth-mandatory-stream] Codex OAuth backend rejects
        // every non-streaming request — transparently upgrade to stream.
        //
        // Background. When an OpenAI provider instance is configured
        // with ChatGPT (Codex) OAuth and no custom base URL, requests
        // land at `https://chatgpt.com/backend-api/codex/responses`
        // (see buildResponsesAPIRequest below). That endpoint is the
        // private Codex CLI surface, NOT the public api.openai.com
        // Responses API, and its server-side validator enforces
        // `stream=true` unconditionally:
        //
        //     HTTP 400 {"detail":"Stream must be set to true"}
        //
        // sendMessage always built its request with stream=false
        // (line ~132), so every caller that hits a Codex-OAuth instance
        // got that 400 before the model was ever invoked. Concrete
        // breakage observed in the wild:
        //   • `minis-model-use run --model gpt-5.5 --input X.json`
        //     without `--stream` — ModelUseOffloadBridge picks
        //     sendMessage vs streamMessage based on streamFd; the
        //     non-streaming default failed every time. User reported
        //     "new build still can't recognise images" and the 400 was the actual cause
        //     (image format was already correct after eaa20a08).
        //   • Title generation and any other sync-completion paths
        //     calling sendMessage on a Codex-OAuth OpenAIProvider.
        //
        // The error reaches users as a raw HTTP 400 from a provider
        // labelled "OpenAI", which is confusing — the payload they
        // wrote follows public OpenAI API docs and there's no client-
        // side knob exposed to request streaming when the API surface
        // is "give me one response".
        //
        // Bridge. Detect the Codex OAuth backend (same condition
        // buildResponsesAPIRequest uses for endpoint selection, see
        // `isCodexOAuth` at line ~412) and transparently route through
        // streamMessage, concatenating the SSE chunks back into a
        // single LLMResponse. Callers asking for "one response" still
        // get one response; we simply don't express that preference on
        // the wire.
        //
        // Scope. The branch is intentionally narrow so a regression
        // here can only affect Codex OAuth:
        //   • !useChat            — only the Responses API path; Chat
        //                           Completions instances are untouched
        //                           (they accept stream=false fine).
        //   • !forceResponsesAPI  — third-party Responses-compatible
        //                           endpoints (xAI etc.) work with
        //                           stream=false; only the literal
        //                           chatgpt.com Codex endpoint rejects.
        //   • isOAuth             — API-key OpenAI Responses API
        //                           accepts stream=false.
        //   • customBaseURL==nil  — user-supplied proxy bases are NOT
        //                           the Codex endpoint.
        //
        // Tradeoff. Streaming adds a few hundred ms of overhead vs a
        // single POST for short responses, but the alternative on
        // Codex OAuth is "request always fails", so this is strictly
        // better. Streaming chunks are concatenated in
        // sendMessageViaStream below; LLMResponse.text equals the
        // exact prose stream the same as a streamMessage caller would
        // see.
        let isCodexOAuthBackend = !useChat && !forceResponsesAPI && isOAuth && customBaseURL == nil
        if isCodexOAuthBackend {
            return try await sendMessageViaStream(
                messages: messages,
                systemPrompt: systemPrompt,
                maxTokens: maxTokens,
                temperature: temperature
            )
        }

        let serialized = messages.map { useChat ? Self.openAIMessageDict($0) : Self.responsesAPIMessageDict($0) }
        let request = try await buildRequest(
            systemPrompt: systemPrompt,
            messages: serialized,
            tools: nil,
            maxTokens: maxTokens,
            stream: false
        )

        let (data, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1

        guard (200..<300).contains(statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw mapHTTPError(statusCode: statusCode, body: body)
        }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        if usesChatCompletionsAPI {
            return parseChatCompletionsResult(json)
        } else {
            return parseResponsesAPIResult(json)
        }
    }

    /// Adapter for sendMessage callers when the target backend (Codex
    /// OAuth) refuses non-streaming requests. Drives streamMessage to
    /// completion and assembles a single LLMResponse — text gets
    /// concatenated; the final usage report wins. See
    /// T-codex-oauth-mandatory-stream.
    private func sendMessageViaStream(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> LLMResponse {
        var text = ""
        var stopReason: String?
        var usage: LLMUsage?
        let stream = try await streamMessage(
            messages: messages,
            systemPrompt: systemPrompt,
            maxTokens: maxTokens,
            temperature: temperature
        )
        for try await chunk in stream {
            switch chunk {
            case .text(let t): text += t
            case .usage(let u): usage = u
            case .finished(let s): stopReason = s
            default: break
            }
        }
        return LLMResponse(text: text, stopReason: stopReason, usage: usage)
    }

    func streamMessage(
        messages: [LLMMessage],
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ) async throws -> AsyncThrowingStream<LLMStreamChunk, Error> {
        let useChat = usesChatCompletionsAPI
        let serialized = messages.map { useChat ? Self.openAIMessageDict($0) : Self.responsesAPIMessageDict($0) }
        let request = try await buildRequest(
            systemPrompt: systemPrompt,
            messages: serialized,
            tools: nil,
            maxTokens: maxTokens,
            stream: true
        )

        // [T-ios-stream-connection-reuse] Own session for this stream; see
        // `makeStreamingSession()`. Invalidated on every exit path below so the
        // connection is torn down rather than returned to a long-lived pool.
        let session = makeStreamingSession()
        let byteStream: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (byteStream, response) = try await session.bytes(for: request)
        } catch {
            session.finishTasksAndInvalidate()
            throw error
        }
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1

        if !(200..<300).contains(statusCode) {
            var body = ""
            for try await line in byteStream.lines { body += line }
            session.finishTasksAndInvalidate()
            throw mapHTTPError(statusCode: statusCode, body: body)
        }

        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    continuation.yield(.started)
                    for try await line in byteStream.lines {
                        // Tolerate `data:` with or without the optional space
                        // — see OpenAIAgentProvider.ssePayload for context
                        // (China Telecom's deepseek-v4-oc endpoint omits it).
                        guard line.hasPrefix("data:") else { continue }
                        let after = line.dropFirst(5)
                        let payload = after.first == " " ? String(after.dropFirst()) : String(after)
                        if payload == "[DONE]" {
                            continuation.yield(.finished(stopReason: nil))
                            break
                        }
                        guard let eventData = payload.data(using: .utf8),
                              let event = try? JSONSerialization.jsonObject(with: eventData) as? [String: Any] else { continue }

                        if self.usesChatCompletionsAPI {
                            if let choices = event["choices"] as? [[String: Any]],
                               let delta = choices.first?["delta"] as? [String: Any],
                               let text = delta["content"] as? String {
                                continuation.yield(.text(text))
                            }
                            if let usage = event["usage"] as? [String: Any] {
                                continuation.yield(.usage(self.parseChatCompletionsUsage(usage)))
                            }
                        } else {
                            if let text = self.parseResponsesAPITextDelta(event) {
                                continuation.yield(.text(text))
                            }
                            if let usage = self.parseResponsesAPIUsage(event) {
                                continuation.yield(.usage(usage))
                            }
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: self.mapError(error))
                }
            }
            // [T-ios-stream-connection-reuse] Cancel the pump AND tear down
            // this stream's session. Fires on normal finish, thrown error, and
            // consumer cancellation alike, so the connection is never returned
            // to a pool that could hand it to a later request.
            continuation.onTermination = { _ in
                task.cancel()
                session.finishTasksAndInvalidate()
            }
        }
    }

    // MARK: - Request body size guard

    /// Hard ceiling on the serialized request body. Above this we refuse the
    /// request instead of handing it to JSONSerialization.
    ///
    /// Sizing: serializing costs roughly 3x the body at peak — NSJSONSerialization
    /// grows one contiguous output buffer by doubling, so the old and the new
    /// (2x) buffer are both live across the realloc, on top of the source
    /// dictionary that is already resident. 32MB therefore means a ~100MB spike.
    /// That is a real but survivable cost against the app's footprint budget,
    /// which ISHKernel's fork guard puts at 1-2GB for the WHOLE process (UI,
    /// conversation, iSH rootfs included) — and the crash happens precisely when
    /// a long conversation has already eaten most of it.
    ///
    /// Note this is a backstop against process death, not a context-window
    /// policy: the model will reject anything this large long before memory does.
    /// No legitimate text request comes close (200k tokens of text is under 1MB);
    /// reaching 32MB means something pathological is in the history, in practice
    /// inlined base64 images, which the token estimator badly under-counts and
    /// which are exactly what pushed the reported crash over the edge.
    private static let maxRequestBodyBytes = 32 * 1024 * 1024

    /// Ceiling for the DEBUG-only on-wire body capture, which copies the whole
    /// serialized body into a String. Well below the request ceiling because this
    /// copy is pure diagnostics — nothing about the request depends on it.
    private static let maxDebugCaptureBytes = 8 * 1024 * 1024

    /// Approximate the serialized JSON size of a request body WITHOUT serializing it.
    ///
    /// [T-ios-openai-body-oom] This exists because the failure it guards against
    /// cannot be caught. When NSJSONSerialization cannot grow its output buffer it
    /// calls _CFRaiseMemoryException -> abort(); that is a process abort, not a
    /// Swift error and not an ObjC exception we can catch, so `try` around
    /// `JSONSerialization.data` is useless. The check has to happen BEFORE the call.
    ///
    /// Accuracy only has to be good enough to separate "normal request" from
    /// "about to abort the process", so this walks the tree and counts bytes
    /// without building any strings. Strings are measured with utf8.count (exact
    /// for the content, ignoring escapes) and given a small allowance for quoting.
    /// It deliberately does not recurse without limit — a pathological nesting
    /// depth returns the ceiling rather than blowing the Swift stack, which would
    /// be its own crash.
    private static func estimateSerializedSize(_ value: Any, depth: Int = 0) -> Int {
        // Deeply nested input is not something we can measure safely; report it as
        // over-limit so the caller rejects it rather than recursing into a stack
        // overflow. Real bodies nest a handful of levels.
        if depth > 64 { return maxRequestBodyBytes }

        switch value {
        case let s as String:
            // +2 for the quotes. Escaping can only make the output longer (a
            // quote or backslash becomes 2 bytes, a control character becomes 6
            // as ), so utf8.count is a lower bound, never an over-estimate.
            // Measured against real JSONSerialization output this is within 5% on
            // ordinary text and exact on base64 — but escape-heavy content (JSON
            // embedded in a tool result, Windows paths) measured ~0.79 of actual,
            // so scale up to stay on the conservative side. Over-estimating costs
            // nothing here: the ceiling is far above any legitimate request.
            return (s.utf8.count * 5) / 4 + 2
        case let dict as [String: Any]:
            var total = 2 // {}
            for (k, v) in dict {
                // key quotes + colon + comma
                total += k.utf8.count + 4
                total += estimateSerializedSize(v, depth: depth + 1)
                // Bail out early once we are already over — no point walking the
                // rest of a body we are going to reject, and this keeps the guard
                // cheap on exactly the huge inputs where it matters.
                if total >= maxRequestBodyBytes { return total }
            }
            return total
        case let array as [Any]:
            var total = 2 // []
            for v in array {
                total += estimateSerializedSize(v, depth: depth + 1) + 1 // comma
                if total >= maxRequestBodyBytes { return total }
            }
            return total
        case let data as Data:
            // Not valid JSON input, but if it ever appears it would be huge.
            return data.count
        case is NSNull:
            return 4
        case let n as NSNumber:
            // Covers Bool and every numeric type; 20 bytes covers Int64/Double text.
            return CFGetTypeID(n) == CFBooleanGetTypeID() ? 5 : 20
        default:
            return 16
        }
    }

    /// Reject a body that would abort the process during serialization.
    ///
    /// Throws a recoverable `LLMError.providerError` so the agent loop surfaces a
    /// readable message (and can fall back / let the user compact) instead of the
    /// app disappearing mid-stream.
    private func guardRequestBodySize(_ body: [String: Any], context: String) throws {
        let estimated = Self.estimateSerializedSize(body)
        if estimated >= Self.maxRequestBodyBytes {
            let estimatedMB = estimated / (1024 * 1024)
            let limitMB = Self.maxRequestBodyBytes / (1024 * 1024)
            logger.error(
                "🛑 [\(context)] request body ~\(estimatedMB)MB exceeds the \(limitMB)MB ceiling; "
                + "refusing to serialize (would risk an out-of-memory abort). "
                + "Conversation likely contains large inlined attachments."
            )
            throw LLMError.providerError(
                message: String(
                    format: AppLocalized("Request too large (about %1$d MB, limit %2$d MB). Start a new conversation or remove large images/attachments, then try again."),
                    estimatedMB, limitMB
                )
            )
        }

        // [T-ios-openai-body-oom] Second gate: dynamic headroom. The fixed
        // ceiling above assumes the process still has ~100MB to spare, but the
        // crash population is exactly the sessions that have already eaten most
        // of the footprint budget — TestFlight BJhH4xxkZcrKGOmwgHZ3Ez (1.13(14))
        // aborted in __CFReallocationFailed on a body well under the ceiling.
        // Serializing costs ~3x the body at peak (source dict + old buffer +
        // doubled new buffer), so require that much headroom from the kernel's
        // own accounting before committing. The 4MB floor keeps ordinary text
        // requests exempt: near-death transient dips must not fail a request
        // that would have cost a few hundred KB to serialize anyway.
        let headroomFloor = 4 * 1024 * 1024
        if estimated > headroomFloor {
            let available = Int(os_proc_available_memory())
            // os_proc_available_memory() returns 0 when the limit is unknown
            // (e.g. some extension/simulator contexts) — treat that as "no
            // information", never as "no memory".
            if available > 0, estimated * 3 > available {
                let estimatedMB = estimated / (1024 * 1024)
                let availableMB = available / (1024 * 1024)
                logger.error(
                    "🛑 [\(context)] request body ~\(estimatedMB)MB needs ~\(estimatedMB * 3)MB to serialize "
                    + "but only \(availableMB)MB of process memory remains; refusing to serialize "
                    + "(would abort in __CFReallocationFailed)."
                )
                throw LLMError.providerError(
                    message: String(
                        format: AppLocalized("Not enough memory to send this request (about %1$d MB needed, %2$d MB free). Close other sessions or restart the app, then try again."),
                        estimatedMB * 3, availableMB
                    )
                )
            }
        }
    }

    // MARK: - Streaming for Agent (tool use)

    /// Build and execute a streaming request, returning raw SSE lines for the agent provider to parse.
    func streamRaw(
        body: [String: Any],
        isResponsesAPI: Bool
    ) async throws -> (AsyncThrowingStream<String, Error>, Int) {
        let url: URL
        var request: URLRequest

        let isCodexOAuth = isResponsesAPI && !forceResponsesAPI && isOAuth && customBaseURL == nil

        if isResponsesAPI && isCodexOAuth {
            url = URL(string: "https://chatgpt.com/backend-api/codex/responses")!
            request = URLRequest(url: url)
            let token = try await getToken()
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.setValue(Self.codexClientVersion, forHTTPHeaderField: "Version")
            request.setValue("responses=experimental", forHTTPHeaderField: "Openai-Beta")
            request.setValue("codex_cli_rs/\(Self.codexClientVersion) (iOS; arm64)", forHTTPHeaderField: "User-Agent")
            request.setValue("codex_cli_rs", forHTTPHeaderField: "Originator")
            if let accountId = codexAccountId {
                request.setValue(accountId, forHTTPHeaderField: "Chatgpt-Account-Id")
            }
            // [T-codex-prompt-cache-headers] The ChatGPT backend keys prompt
            // cache on the conversation identity carried in these HEADERS, not
            // on the `prompt_cache_key` body field alone. Sending the body
            // field by itself produced a measured 0% cache-hit rate across
            // every multi-turn test on device (see /tmp/gpt56_cache_findings.md
            // — 0% even for two byte-identical 8.7k-token requests sent
            // back-to-back), which is what motivated this change.
            //
            // Mirrors CLIProxyAPI's codex_executor.go `cacheHelper`, which sets
            // both headers to the same id it writes into `prompt_cache_key`
            // (its `applyCodexHeaders` only falls back to a random Session_id
            // when no cache id exists — EnsureHeader preserves an already-set
            // value). Reusing our derived key keeps one stable id per
            // conversation, which is the property the backend needs.
            if let cacheKey = body["prompt_cache_key"] as? String, !cacheKey.isEmpty {
                request.setValue(cacheKey, forHTTPHeaderField: "Session_id")
                request.setValue(cacheKey, forHTTPHeaderField: "Conversation_id")
            }
        } else if isResponsesAPI {
            // forceResponsesAPI or custom base — /v1/responses on configured base.
            // [T-ios-azure-openai] Azure: build from the Azure endpoint (preserving
            // ?api-version) and auth with the api-key header.
            if isAzure, let azure = azureURL(path: "/responses") {
                url = azure
            } else {
                let base = resolvedAPIBase(customBaseURL ?? "https://api.openai.com", appendV1: appendV1Suffix)
                let v1Path = appendV1Suffix ? "/v1" : ""
                url = try requireURL(URLBuilding.join(base, v1Path, "/responses"))
            }
            request = URLRequest(url: url)
            let token = try await getToken()
            applyKeyAuth(&request, token: token)
        } else {
            // [T-ios-azure-openai] Azure chat-completions variant.
            if isAzure, let azure = azureURL(path: "/chat/completions") {
                url = azure
            } else {
                let base = resolvedAPIBase(customBaseURL ?? "https://api.openai.com", appendV1: appendV1Suffix)
                let v1Path = appendV1Suffix ? "/v1" : ""
                url = try requireURL(URLBuilding.join(base, v1Path, "/chat/completions"))
            }
            request = URLRequest(url: url)
            let token = try await getToken()
            applyKeyAuth(&request, token: token)
        }

        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }
        // [T-ios-openai-body-oom] Must run BEFORE serializing: an out-of-memory
        // inside JSONSerialization aborts the process and cannot be caught. This
        // is the agent loop's request path, where the body grows with every turn.
        try guardRequestBodySize(body, context: "streamRaw")

        // Use .sortedKeys so every request with the same body produces byte-identical
        // JSON, letting DeepSeek's prefix-based disk cache match from the 0th token.
        // Without this, Swift's dictionary iteration order randomizes top-level /
        // nested key order across calls and the cache misses on every request.
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])

        #if DEBUG
        // Capture the actual on-wire body bytes for debug.llmRequests, so parity
        // checks can compare exactly what DeepSeek received (byte-for-byte).
        //
        // Skip the capture on very large bodies: this makes a full second copy of
        // the serialized request as a String, so on a long conversation it doubles
        // the very allocation that D.2 crashes on. Release is unaffected (the whole
        // block is DEBUG-only), but a debug build should not OOM where the shipped
        // build would not. Byte-parity checks are done on ordinary-sized requests.
        if let onWireBody = request.httpBody {
            if onWireBody.count <= Self.maxDebugCaptureBytes,
               let bodyStr = String(data: onWireBody, encoding: .utf8) {
                LastAPIRequestBody.shared.set(bodyStr, provider: "OpenAI")
            } else {
                LastAPIRequestBody.shared.set(
                    "<body omitted: \(onWireBody.count) bytes exceeds the debug capture limit>",
                    provider: "OpenAI"
                )
            }
        }
        let maskedHeaders: [String: String] = (request.allHTTPHeaderFields ?? [:]).reduce(into: [:]) { acc, pair in
            acc[pair.key] = pair.key == "Authorization" ? "Bearer ***" : pair.value
        }
        LastAPIRequestBody.shared.setRequestMeta(
            url: url.absoluteString,
            method: "POST",
            headers: maskedHeaders
        )
        logOutgoingRequest(url: url, headers: request.allHTTPHeaderFields, body: body)
        #endif

        // [T-ios-stream-connection-reuse] Own session for this stream; see
        // `makeStreamingSession()`. This is the agent loop's request path — the
        // one the 22:11 stall happened on.
        let session = makeStreamingSession()
        let byteStream: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (byteStream, response) = try await session.bytes(for: request)
        } catch {
            session.finishTasksAndInvalidate()
            throw error
        }
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1

        if !(200..<300).contains(statusCode) {
            var body = ""
            for try await line in byteStream.lines { body += line }
            session.finishTasksAndInvalidate()
            throw mapHTTPError(statusCode: statusCode, body: body)
        }

        let lineStream = AsyncThrowingStream<String, Error> { continuation in
            let task = Task {
                do {
                    for try await line in byteStream.lines {
                        continuation.yield(line)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            // [T-ios-stream-connection-reuse] Cancel the pump AND tear down
            // this stream's session. Fires on normal finish, thrown error, and
            // consumer cancellation alike, so the connection is never returned
            // to a pool that could hand it to a later request.
            continuation.onTermination = { _ in
                task.cancel()
                session.finishTasksAndInvalidate()
            }
        }

        return (lineStream, statusCode)
    }

    // MARK: - Request Building

    /// Whether this provider should use the Chat Completions API shape.
    /// True for API key mode and for manual OAuth with custom base URL,
    /// unless `forceResponsesAPI` is set (Responses API provider type).
    var usesChatCompletionsAPI: Bool {
        if forceResponsesAPI { return false }
        if !isOAuth { return true }
        // Manual OAuth with custom base URL uses Chat Completions API
        if customBaseURL != nil { return true }
        return false
    }

    private func buildRequest(
        systemPrompt: String?,
        messages: [[String: Any]],
        tools: [[String: Any]]?,
        maxTokens: Int,
        stream: Bool
    ) async throws -> URLRequest {
        if usesChatCompletionsAPI {
            return try await buildChatCompletionsRequest(
                systemPrompt: systemPrompt,
                messages: messages,
                tools: tools,
                maxTokens: maxTokens,
                stream: stream
            )
        } else {
            return try await buildResponsesAPIRequest(
                systemPrompt: systemPrompt,
                messages: messages,
                tools: tools,
                maxTokens: maxTokens,
                stream: stream
            )
        }
    }

    private func buildChatCompletionsRequest(
        systemPrompt: String?,
        messages: [[String: Any]],
        tools: [[String: Any]]?,
        maxTokens: Int,
        stream: Bool
    ) async throws -> URLRequest {
        let url: URL
        if isAzure, let azure = azureURL(path: "/chat/completions") {
            url = azure
        } else {
            // [T-model-use-endpoint-override] honor the absolute-path override
            url = try requireEndpointURL(defaultPath: "/chat/completions")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let token = try await getToken()
        applyKeyAuth(&request, token: token)
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        var allMessages = messages
        if let sys = systemPrompt, !sys.isEmpty {
            allMessages.insert(["role": "system", "content": sys], at: 0)
        }

        var body: [String: Any] = [
            "model": model.id,
            "messages": allMessages,
            "stream": stream,
        ]
        if useOpenRouterCompat {
            body["max_tokens"] = maxTokens
        } else {
            body["max_completion_tokens"] = maxTokens
            if stream {
                body["stream_options"] = ["include_usage": true]
            }
        }
        // [GH#191] See the matching block in OpenAIAgentProvider — OpenRouter
        // requires an explicit cache_control breakpoint for Claude or nothing is
        // cached. Same host + `anthropic/` gate, so no other model is affected.
        if needsOpenRouterAnthropicCacheControl {
            body["cache_control"] = ["type": "ephemeral"]
        }
        if let tools, !tools.isEmpty {
            body["tools"] = tools
        }
        // [T-model-use-chat-passthrough GH#72] Merge user-supplied extra body
        // fields verbatim (no OpenAI→native conversion — callers own the shape).
        // User keys win over our defaults, but `model` is force-kept so a stray
        // override can't misroute the call. Mirrors generateImage's merge.
        if !chatExtraBody.isEmpty {
            for (k, v) in chatExtraBody { body[k] = v }
            body["model"] = model.id
        }

        // [T-ios-openai-body-oom] See streamRaw — carries the conversation too.
        try guardRequestBodySize(body, context: "chatCompletions")

        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])

        #if DEBUG
        logOutgoingRequest(url: url, headers: request.allHTTPHeaderFields, body: body)
        // Capture into LastAPIRequestBody so debug.llmRequests sees the
        // standard streamMessage / sendMessage path (not just streamRaw).
        // Compact / title generation go through this build path; without
        // capture here, those requests never appear in the ring.
        if let onWireBody = request.httpBody,
           let bodyStr = String(data: onWireBody, encoding: .utf8) {
            LastAPIRequestBody.shared.set(bodyStr, provider: "OpenAI")
        }
        let maskedHeaders: [String: String] = (request.allHTTPHeaderFields ?? [:]).reduce(into: [:]) { acc, pair in
            acc[pair.key] = pair.key == "Authorization" ? "Bearer ***" : pair.value
        }
        LastAPIRequestBody.shared.setRequestMeta(
            url: url.absoluteString,
            method: "POST",
            headers: maskedHeaders
        )
        #endif

        return request
    }

    private func buildResponsesAPIRequest(
        systemPrompt: String?,
        messages: [[String: Any]],
        tools: [[String: Any]]?,
        maxTokens: Int,
        stream: Bool
    ) async throws -> URLRequest {
        let url: URL
        let isCodexOAuth = !forceResponsesAPI && isOAuth && customBaseURL == nil

        if isCodexOAuth {
            // Codex OAuth — hardcoded ChatGPT backend endpoint
            url = URL(string: "https://chatgpt.com/backend-api/codex/responses")!
        } else if isAzure, let azure = azureURL(path: "/responses") {
            url = azure   // [T-ios-azure-openai]
        } else {
            // forceResponsesAPI or custom base — use /v1/responses on the
            // configured base. [T-model-use-endpoint-override] honors the
            // absolute-path override (never reaches the Codex branch above).
            url = try requireEndpointURL(defaultPath: "/responses")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let token = try await getToken()
        applyKeyAuth(&request, token: token)

        if isCodexOAuth {
            request.setValue(Self.codexClientVersion, forHTTPHeaderField: "Version")
            request.setValue("responses=experimental", forHTTPHeaderField: "Openai-Beta")
            request.setValue("codex_cli_rs/\(Self.codexClientVersion) (iOS; arm64)", forHTTPHeaderField: "User-Agent")
            request.setValue("codex_cli_rs", forHTTPHeaderField: "Originator")
            if let accountId = codexAccountId {
                request.setValue(accountId, forHTTPHeaderField: "Chatgpt-Account-Id")
            }
        }

        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        var body: [String: Any] = [
            "model": model.id,
            "stream": stream,
            "store": false,
            "parallel_tool_calls": true,
            "input": messages,
        ]
        // [T-responses-max-output-tokens] Same injection (and same Codex
        // exclusion) as the agent path in OpenAIAgentProvider: without the
        // field, third-party Responses vendors fall back to their own small
        // output defaults and truncate. This builder covers the offload /
        // non-agent callers (title generation, model-use bridge, …), which
        // received maxTokens but never wrote it into the body. Codex OAuth is
        // excluded — its body shape is part of the codex_cli fingerprint.
        if maxTokens > 0, !isCodexOAuth {
            body["max_output_tokens"] = maxTokens
        }
        // Include encrypted reasoning only for Codex OAuth
        if isCodexOAuth {
            body["include"] = ["reasoning.encrypted_content"]
            body["reasoning"] = ["effort": "low"]
        }
        // [T-codex-fast-mode] User-toggled Fast Mode ("..." menu). Wire value
        // verified against codex_cli_rs source: ServiceTier::Fast.
        // request_value() == "priority" ("fast" is only the UI name).
        // Broadened to every Responses-API flavor (this builder is the
        // Responses path) gated on a gpt-family model id. ~1.5x faster,
        // 2x credit burn on the subscription.
        if UserDefaults.standard.bool(forKey: Self.fastModeDefaultsKey),
           model.id.lowercased().contains("gpt") {
            body["service_tier"] = "priority"
        }
        if let sys = systemPrompt, !sys.isEmpty {
            body["instructions"] = sys
        }
        if let tools, !tools.isEmpty {
            body["tools"] = tools
            body["tool_choice"] = "auto"
        }
        // [T-model-use-chat-passthrough GH#72] Same verbatim merge as the
        // chat-completions builder. Skipped for Codex OAuth — its body shape
        // is part of the client fingerprint and must stay untouched.
        if !chatExtraBody.isEmpty && !isCodexOAuth {
            for (k, v) in chatExtraBody { body[k] = v }
            body["model"] = model.id
        }

        // [T-ios-openai-body-oom] See streamRaw — carries the conversation too.
        try guardRequestBodySize(body, context: "responsesAPI")

        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])

        #if DEBUG
        logOutgoingRequest(url: url, headers: request.allHTTPHeaderFields, body: body)
        #endif

        return request
    }

    // MARK: - Debug Logging

    #if DEBUG
    private func logOutgoingRequest(url: URL, headers: [String: String]?, body: [String: Any]) {
        var parts: [String] = []
        parts.append("📤 OpenAI Request")
        parts.append("  url: \(url.absoluteString)")
        parts.append("  model: \(body["model"] ?? "?")")
        parts.append("  stream: \(body["stream"] ?? "?")")
        // "Codex" only for the real ChatGPT/Codex backend (OAuth + official base
        // + Responses path). Other OAuth providers that flow through
        // OpenAIProvider (xAI, Kimi Code, …) are plain "OAuth" — the old label
        // hardcoded "Codex" and mislabeled their requests.
        let isCodexBackend = isOAuth && !forceResponsesAPI && customBaseURL == nil && !usesChatCompletionsAPI
        let modeLabel = forceResponsesAPI ? "Responses API"
            : isOAuth ? (isCodexBackend ? "OAuth (Codex)" : "OAuth") : "API Key"
        parts.append("  authMode: \(modeLabel)")

        if let accountId = codexAccountId {
            parts.append("  accountId: \(accountId)")
        }

        // Headers (mask Authorization)
        if let headers {
            for (key, val) in headers.sorted(by: { $0.key < $1.key }) {
                let display = key == "Authorization" ? "Bearer ***" : val
                parts.append("  header: \(key): \(display)")
            }
        }

        if let instructions = body["instructions"] as? String {
            let preview = String(instructions.prefix(200))
            parts.append("  instructions: \"\(preview)\"" + (instructions.count > 200 ? "..." : ""))
        }

        if let input = body["input"] as? [[String: Any]] {
            parts.append("  input messages: \(input.count)")
        } else if let messages = body["messages"] as? [[String: Any]] {
            parts.append("  messages: \(messages.count)")
        }

        if let tools = body["tools"] as? [[String: Any]] {
            let names = tools.compactMap { $0["name"] as? String }
            parts.append("  tools: [\(names.joined(separator: ", "))]")
        }

        // Full body as pretty JSON (for the debug logger preview only; the raw on-wire
        // body is captured separately in streamRaw so debug.llmRequests reflects
        // exactly what hit the network).
        if let data = try? JSONSerialization.data(withJSONObject: body, options: [.prettyPrinted, .sortedKeys]),
           let str = String(data: data, encoding: .utf8) {
            let truncated = String(str.prefix(3000))
            parts.append("  Full Body:")
            parts.append(truncated)
            if str.count > 3000 {
                parts.append("  ... (\(str.count) chars total)")
            }
        }

        logger.debug("\(parts.joined(separator: "\n"))")
    }
    #endif

    // MARK: - Message Serialization

    /// Convert an LLMMessage to an OpenAI-compatible message dict.
    /// If the message has image attachments, uses the multi-part content array format.
    static func openAIMessageDict(_ msg: LLMMessage) -> [String: Any] {
        guard !msg.images.isEmpty || !msg.audios.isEmpty else {
            return ["role": msg.role.rawValue, "content": msg.content]
        }
        var parts: [[String: Any]] = []
        for img in msg.images {
            let dataURL = "data:\(img.mimeType);base64,\(img.data.base64EncodedString())"
            parts.append([
                "type": "image_url",
                "image_url": ["url": dataURL]
            ])
        }
        for audio in msg.audios {
            // GH#67: official Chat Completions audio-input shape.
            parts.append([
                "type": "input_audio",
                "input_audio": ["data": audio.base64Data, "format": audio.format]
            ])
        }
        if !msg.content.isEmpty {
            parts.append(["type": "text", "text": msg.content])
        }
        return ["role": msg.role.rawValue, "content": parts]
    }

    /// Responses-API counterpart of `openAIMessageDict`. The Responses API
    /// rejects Chat-Completions-shaped content blocks: `text` must be
    /// `input_text`, and image blocks must be `input_image` with `image_url`
    /// as a string (not `{url: ...}`). Mirrors the shape emitted by
    /// `OpenAIAgentProvider.convertMessagesResponsesAPI` for the agent path,
    /// so plain `streamMessage` calls (minis-model-use, title gen, etc.)
    /// land the same Codex backend without a 400 on `image_url`.
    static func responsesAPIMessageDict(_ msg: LLMMessage) -> [String: Any] {
        if msg.images.isEmpty && msg.audios.isEmpty {
            return ["role": msg.role.rawValue, "content": msg.content]
        }
        var parts: [[String: Any]] = []
        for img in msg.images {
            let dataURL = "data:\(img.mimeType);base64,\(img.data.base64EncodedString())"
            parts.append([
                "type": "input_image",
                "image_url": dataURL,
            ])
        }
        for audio in msg.audios {
            // GH#67: the Responses API keeps the SAME nested input_audio shape
            // as Chat Completions ({data, format}), unlike input_image which
            // flattens image_url to a string.
            parts.append([
                "type": "input_audio",
                "input_audio": ["data": audio.base64Data, "format": audio.format]
            ])
        }
        if !msg.content.isEmpty {
            parts.append(["type": "input_text", "text": msg.content])
        }
        return ["role": msg.role.rawValue, "content": parts]
    }

    // MARK: - Token

    private func getToken() async throws -> String {
        switch authMode {
        case .apiKey(let key):
            return key
        case .oauth(let provider):
            return try await provider()
        }
    }

    // MARK: - Response Parsing (non-streaming)

    private func parseChatCompletionsResult(_ json: [String: Any]) -> LLMResponse {
        let choices = json["choices"] as? [[String: Any]] ?? []
        let message = choices.first?["message"] as? [String: Any] ?? [:]
        let stopReason = choices.first?["finish_reason"] as? String
        let usage = (json["usage"] as? [String: Any]).map { parseChatCompletionsUsage($0) }

        var text = ""
        var attachments: [LLMMediaAttachment] = []

        // content may be a string or an array of content blocks
        if let stringContent = message["content"] as? String {
            text = stringContent
        } else if let arrayContent = message["content"] as? [[String: Any]] {
            for block in arrayContent {
                let blockType = block["type"] as? String ?? ""
                if blockType == "text", let t = block["text"] as? String {
                    text += t
                } else if blockType == "image_url",
                          let imgObj = block["image_url"] as? [String: Any],
                          let url = imgObj["url"] as? String,
                          let attachment = Self.parseDataURL(url, mediaType: .image) {
                    attachments.append(attachment)
                }
            }
        }

        // OpenRouter returns images in a separate "images" field on the message
        if let images = message["images"] as? [[String: Any]] {
            for img in images {
                if let imgObj = img["image_url"] as? [String: Any],
                   let url = imgObj["url"] as? String,
                   let attachment = Self.parseDataURL(url, mediaType: .image) {
                    attachments.append(attachment)
                }
            }
        }

        var response = LLMResponse(text: text, stopReason: stopReason, usage: usage)
        response.mediaAttachments = attachments
        return response
    }

    /// Parse a data URL (e.g. "data:image/png;base64,iVBOR...") into an LLMMediaAttachment.
    private static func parseDataURL(_ url: String, mediaType: LLMMediaAttachment.MediaType) -> LLMMediaAttachment? {
        // data:<mime>;base64,<data>
        guard url.hasPrefix("data:") else { return nil }
        let rest = url.dropFirst(5) // drop "data:"
        guard let semicolonIdx = rest.firstIndex(of: ";"),
              let commaIdx = rest.firstIndex(of: ",") else { return nil }
        let mimeType = String(rest[rest.startIndex..<semicolonIdx])
        let b64String = String(rest[rest.index(after: commaIdx)...])
        guard let data = Data(base64Encoded: b64String) else { return nil }
        return LLMMediaAttachment(type: mediaType, mimeType: mimeType, data: data)
    }

    private func parseResponsesAPIResult(_ json: [String: Any]) -> LLMResponse {
        var text = ""
        if let output = json["output"] as? [[String: Any]] {
            for item in output {
                if let content = item["content"] as? [[String: Any]] {
                    for c in content {
                        if let t = c["text"] as? String { text += t }
                    }
                }
            }
        }
        let usage = (json["usage"] as? [String: Any]).map { parseResponsesAPIUsageDict($0) }
        return LLMResponse(text: text, stopReason: json["status"] as? String, usage: usage)
    }

    // MARK: - SSE Parsing Helpers

    func parseResponsesAPITextDelta(_ event: [String: Any]) -> String? {
        let type = event["type"] as? String
        if type == "response.output_text.delta" {
            return event["delta"] as? String
        }
        return nil
    }

    func parseResponsesAPIUsage(_ event: [String: Any]) -> LLMUsage? {
        let type = event["type"] as? String
        if type == "response.completed",
           let response = event["response"] as? [String: Any],
           let usage = response["usage"] as? [String: Any] {
            return parseResponsesAPIUsageDict(usage)
        }
        return nil
    }

    // MARK: - Usage Parsing

    private func parseChatCompletionsUsage(_ usage: [String: Any]) -> LLMUsage {
        // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens` instead
        // of the OpenAI-native `prompt_tokens_details.cached_tokens`.
        let cacheRead = (usage["prompt_tokens_details"] as? [String: Any])?["cached_tokens"] as? Int
            ?? (usage["prompt_cache_hit_tokens"] as? Int)
        // OpenAI/DeepSeek `prompt_tokens` is the FULL input (cached + fresh), so
        // subtract the cached portion to keep `inputTokens` meaning fresh-only —
        // matching the Anthropic convention. Otherwise the cached tokens get
        // counted twice in `input + cacheRead` (deflates the cache-hit rate).
        // Guard: only subtract when it stays non-negative; no cache field → unchanged.
        let promptTokens = usage["prompt_tokens"] as? Int ?? 0
        let freshInput = (cacheRead.map { promptTokens - $0 }).flatMap { $0 >= 0 ? $0 : nil } ?? promptTokens
        return LLMUsage(
            inputTokens: freshInput,
            outputTokens: usage["completion_tokens"] as? Int ?? 0,
            cacheCreationInputTokens: nil,
            cacheReadInputTokens: cacheRead
        )
    }

    private func parseResponsesAPIUsageDict(_ usage: [String: Any]) -> LLMUsage {
        let cacheRead = (usage["input_tokens_details"] as? [String: Any])?["cached_tokens"] as? Int
        // `input_tokens` is the FULL input (cached subset included); subtract the
        // cached portion so `inputTokens` is fresh-only (see parseChatCompletionsUsage).
        let totalInput = usage["input_tokens"] as? Int ?? 0
        let freshInput = (cacheRead.map { totalInput - $0 }).flatMap { $0 >= 0 ? $0 : nil } ?? totalInput
        return LLMUsage(
            inputTokens: freshInput,
            outputTokens: usage["output_tokens"] as? Int ?? 0,
            cacheCreationInputTokens: nil,
            cacheReadInputTokens: cacheRead
        )
    }

    // MARK: - Image Generation (/images/generations)

    /// Call the `/images/generations` endpoint to generate images.
    ///
    /// Request: `{ model, prompt, n, size?, quality?, response_format }` (OpenAI-compatible)
    /// Response: `{ data: [{ url?, b64_json?, revised_prompt? }] }`
    ///
    /// Always requests `b64_json` so we get the image data inline without needing to fetch URLs.
    // MARK: - Raw Passthrough [T-model-use-passthrough-mode]

    /// Result of a raw passthrough call: unparsed response bytes + HTTP status
    /// + the fully-assembled URL that was actually hit (surfaced to the caller
    /// per the passthrough-mode contract).
    struct RawPassthroughResult {
        let data: Data
        let status: Int
        let contentType: String?
        let url: String
    }

    /// Execute a verbatim request against this provider instance's base URL
    /// with the instance's credentials. The response is returned UNPARSED —
    /// passthrough mode's output contract is raw bytes; the caller (agent or
    /// follow-up script) owns interpretation.
    ///
    /// - endpoint: absolute path ("/x/y?q=1", replaces the whole URL path) or
    ///   relative segment (joined after base [+/v1] like modeled endpoints).
    ///   nil → the default chat/completions path.
    /// - headers: applied LAST → same-name REPLACE semantics over every
    ///   default (including Authorization/Content-Type), per design.
    func rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: [String: String],
        bodyObject: [String: Any]?
    ) async throws -> RawPassthroughResult {
        let url: URL
        if let ep = endpoint, ep.hasPrefix("/") {
            guard let u = hostRootURL(ep) else {
                throw LLMError.providerError(message: "Invalid passthrough endpoint: \(ep)")
            }
            url = u
        } else if let ep = endpoint {
            let base = resolvedAPIBase(customBaseURL ?? "https://api.openai.com", appendV1: appendV1Suffix)
            let v1Path = appendV1Suffix ? "/v1" : ""
            guard let u = URL(string: URLBuilding.join(base, v1Path, "/" + ep)) else {
                throw LLMError.providerError(message: "Invalid passthrough endpoint: \(ep)")
            }
            url = u
        } else {
            url = try requireEndpointURL(defaultPath: "/chat/completions")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 600
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let token = try await getToken()
        applyKeyAuth(&request, token: token)
        for (k, v) in extraHeaders { request.setValue(v, forHTTPHeaderField: k) }
        // User headers LAST — replace semantics over all defaults.
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }

        if let bodyObject, method.uppercased() != "GET" {
            // [T-ios-openai-body-oom] Passthrough bodies are caller-supplied and
            // can carry conversation-scale payloads — same abort risk as the
            // three chat builders, same guard.
            try guardRequestBodySize(bodyObject, context: "rawPassthrough")
            request.httpBody = try JSONSerialization.data(withJSONObject: bodyObject, options: [.sortedKeys])
        }

        // [T-log-noise-privacy 2026-07-18] Log without query/fragment — custom
        // endpoints can carry credentials in the query string.
        let logURL = url.absoluteString.split(separator: "?", maxSplits: 1).first.map(String.init) ?? url.absoluteString
        logger.info("[ModelUseRoute] route=raw-passthrough method=\(method) url=\(logURL) bodyKeys=[\((bodyObject ?? [:]).keys.sorted().joined(separator: ","))] headerOverrides=[\(headers.keys.sorted().joined(separator: ","))]")
        #if DEBUG
        if let bodyObject {
            logOutgoingRequest(url: url, headers: request.allHTTPHeaderFields, body: bodyObject)
            if let onWire = request.httpBody, let s = String(data: onWire, encoding: .utf8) {
                LastAPIRequestBody.shared.set(s, provider: "OpenAI")
            }
            let masked: [String: String] = (request.allHTTPHeaderFields ?? [:]).reduce(into: [:]) { acc, p in
                acc[p.key] = p.key.lowercased() == "authorization" ? "Bearer ***" : p.value
            }
            LastAPIRequestBody.shared.setRequestMeta(url: url.absoluteString, method: method, headers: masked)
        }
        #endif

        let (data, response) = try await streamingSession.data(for: request)
        let http = response as? HTTPURLResponse
        return RawPassthroughResult(
            data: data,
            status: http?.statusCode ?? -1,
            contentType: http?.value(forHTTPHeaderField: "Content-Type"),
            url: url.absoluteString
        )
    }

    func generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = nil,
        quality: String? = nil
    ) async throws -> LLMResponse {
        // [T-model-use-image-passthrough GH#62] Honor an explicit endpoint path
        // override (non-standard providers). Falls back to the default path.
        // [T-model-use-endpoint-override] The absolute-path override wins over
        // the legacy relative `imagePathOverride` (which is joined after
        // base [+/v1] and can't escape base prefixes — baseline p03).
        let imagePath = imagePathOverride ?? "/images/generations"
        let url: URL
        if let abs = absoluteEndpointOverride, abs.hasPrefix("/"), let u = hostRootURL(abs) {
            url = u
        } else if isAzure, let azure = azureURL(path: imagePath) {
            url = azure   // [T-ios-azure-openai]
        } else {
            let base = resolvedAPIBase(customBaseURL ?? "https://api.openai.com", appendV1: appendV1Suffix)
            let v1Path = appendV1Suffix ? "/v1" : ""
            guard let u = URL(string: URLBuilding.join(base, v1Path, imagePath)) else {
                throw LLMError.providerError(message: "Invalid URL for \(imagePath)")
            }
            url = u
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // [T-image-gen-timeout] 600s, not 120s. URLRequest.timeoutInterval
        // OVERRIDES the session's timeoutIntervalForRequest, so even though
        // this call now runs on streamingSession (600s), a per-request 120s
        // ceiling would still abort gpt-image-2 renders that cross ~120s
        // with NSURLErrorTimedOut. Observed: a 120.1s render failed while
        // a 115s one succeeded on the same model. Match the session budget.
        request.timeoutInterval = 600

        let token = try await getToken()
        applyKeyAuth(&request, token: token)
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let tokenPrefix = String(token.prefix(12))
        let tokenSuffix = String(token.suffix(4))
        logger.info("🖼️ /images/generations auth: \(tokenPrefix)...\(tokenSuffix) (len=\(token.count)) isOAuth=\(isOAuth)")

        var body: [String: Any] = [
            "model": model.id,
            "prompt": prompt,
            "n": n,
        ]
        if let size { body["size"] = size }
        if let quality { body["quality"] = quality }

        // [T-model-use-image-passthrough GH#62] Merge user-supplied passthrough
        // fields. User keys WIN over our defaults (e.g. they can override prompt/
        // size or add Seedream's `image`/`watermark`), but `model` is force-kept
        // to the resolved model id afterward so a stray override can't misroute.
        for (k, v) in imageExtraBody { body[k] = v }
        body["model"] = model.id
        // If the user explicitly set response_format, respect it and don't run
        // the b64_json probe/retry below.
        let userSetResponseFormat = imageExtraBody["response_format"] != nil

        // [T-ios-openai-body-oom] Image edit/gen bodies can inline multi-MB
        // base64 source images via imageExtraBody. Guard once up front — the
        // loop below only toggles the tiny response_format key between rounds.
        try guardRequestBodySize(body, context: "generateImage")

        // Try b64_json first; if the provider rejects it, retry without response_format
        var triedWithoutFormat = userSetResponseFormat
        while true {
            if !triedWithoutFormat {
                body["response_format"] = "b64_json"
            }
            request.httpBody = try JSONSerialization.data(withJSONObject: body)

            #if DEBUG
            logOutgoingRequest(url: url, headers: request.allHTTPHeaderFields, body: body)
            // Capture for Copy Requests / debug.llmRequests so model-use image-gen
            // failures (e.g. 502 from a proxy) can be inspected on-device.
            if let onWireBody = request.httpBody,
               let bodyStr = String(data: onWireBody, encoding: .utf8) {
                LastAPIRequestBody.shared.set(bodyStr, provider: "OpenAI")
            }
            let maskedHeaders: [String: String] = (request.allHTTPHeaderFields ?? [:]).reduce(into: [:]) { acc, pair in
                acc[pair.key] = pair.key == "Authorization" ? "Bearer ***" : pair.value
            }
            LastAPIRequestBody.shared.setRequestMeta(
                url: url.absoluteString,
                method: "POST",
                headers: maskedHeaders
            )
            #endif

            // [T-image-gen-timeout] Use the 10-minute streamingSession,
            // NOT URLSession.shared. /images/generations is synchronous
            // (no SSE) but image models like gpt-image-2 routinely take
            // 70–120s+ to render; URLSession.shared's default 60s
            // timeoutIntervalForRequest aborts mid-generation with
            // NSURLErrorTimedOut (-1001), which surfaced as "request timed out"
            // even when the coordinator/CLI budget was 300s. The longer
            // request timeout on streamingSession lets the generation
            // finish.
            let (data, response) = try await streamingSession.data(for: request)
            let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1

            #if DEBUG
            let httpResp = response as? HTTPURLResponse
            let respHeaders: [String: String]? = (httpResp?.allHeaderFields as? [String: Any])?
                .reduce(into: [String: String]()) { acc, pair in acc[pair.key] = "\(pair.value)" }
            LastAPIRequestBody.shared.setHTTPResponse(statusCode: statusCode, headers: respHeaders)
            // Capture a bounded preview of the response body — image payloads can be huge,
            // but error JSON / HTML from a misbehaving proxy is exactly what the user
            // needs to see in Copy Requests.
            if let preview = String(data: data.prefix(8 * 1024), encoding: .utf8) {
                LastAPIRequestBody.shared.appendResponseBody(preview)
            }
            #endif

            if !triedWithoutFormat && statusCode == 400 {
                // Some providers (e.g. xAI) don't support b64_json — retry without it
                let responseBody = String(data: data, encoding: .utf8) ?? ""
                if responseBody.lowercased().contains("response_format") || responseBody.contains("b64_json") {
                    logger.info("🖼️ Provider rejected b64_json, retrying without response_format")
                    body.removeValue(forKey: "response_format")
                    triedWithoutFormat = true
                    continue
                }
            }

            guard (200..<300).contains(statusCode) else {
                let responseBody = String(data: data, encoding: .utf8) ?? ""
                throw mapHTTPError(statusCode: statusCode, body: responseBody)
            }

            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
            return try await parseImageGenerationsResult(json)
        }
    }

    /// Parse the `/images/generations` response.
    /// Supports both `b64_json` (inline data) and `url` (download) response formats.
    private func parseImageGenerationsResult(_ json: [String: Any]) async throws -> LLMResponse {
        guard let dataArray = json["data"] as? [[String: Any]] else {
            // Some upstreams (e.g. proxies) accept POSTs to /v1/images/generations but
            // route them to /v1/chat/completions, returning a `choices` payload with no
            // image data. Treat that as "endpoint missing" so the OffloadBridge auto
            // path can fall back to /chat/completions instead of silently caching the
            // wrong endpoint.
            if json["choices"] != nil {
                throw LLMError.providerError(message: "[404] /images/generations not supported (got chat completions response)")
            }
            return LLMResponse(text: "", stopReason: "end_turn", usage: nil)
        }

        var attachments: [LLMMediaAttachment] = []
        var revisedPrompts: [String] = []

        for item in dataArray {
            let hintMime = item["mime_type"] as? String  // xAI extension

            if let b64 = item["b64_json"] as? String,
               let imageData = Data(base64Encoded: b64) {
                let mime = hintMime ?? Self.detectImageMime(imageData)
                attachments.append(LLMMediaAttachment(type: .image, mimeType: mime, data: imageData))
            } else if let urlStr = item["url"] as? String, let url = URL(string: urlStr) {
                // Download image from URL. [T-image-gen-timeout] Use
                // streamingSession (10-min request timeout) — a freshly
                // generated image URL can be a large asset and the 60s
                // default on URLSession.shared can truncate the download.
                do {
                    let (imageData, urlResponse) = try await streamingSession.data(from: url)
                    let contentType = (urlResponse as? HTTPURLResponse)?
                        .value(forHTTPHeaderField: "Content-Type")
                    let mime = hintMime ?? contentType ?? Self.detectImageMime(imageData)
                    attachments.append(LLMMediaAttachment(type: .image, mimeType: mime, data: imageData))
                    logger.info("🖼️ Downloaded image from URL (\(imageData.count) bytes)")
                } catch {
                    logger.warning("🖼️ Failed to download image from \(urlStr): \(error.localizedDescription)")
                }
            }

            if let revised = item["revised_prompt"] as? String, !revised.isEmpty {
                revisedPrompts.append(revised)
            }
        }

        let text = revisedPrompts.isEmpty ? "" : revisedPrompts.joined(separator: "\n")
        var response = LLMResponse(text: text, stopReason: "end_turn", usage: nil)
        response.mediaAttachments = attachments
        return response
    }

    // MARK: - Image Editing (/images/edits)

    /// Call the `/images/edits` endpoint for image-to-image editing.
    /// Uses multipart/form-data since the endpoint requires file upload.
    ///
    /// Request: multipart form with `image` (file), `prompt`, `model`, `n`, `size?`
    /// Response: same as /images/generations — `{ data: [{ b64_json?, url? }] }`
    func editImage(
        prompt: String,
        images: [LLMMessage.ImageAttachment],
        n: Int = 1,
        size: String? = nil,
        quality: String? = nil
    ) async throws -> LLMResponse {
        let url: URL
        if isAzure, let azure = azureURL(path: "/images/edits") {
            url = azure   // [T-ios-azure-openai]
        } else {
            let base = resolvedAPIBase(customBaseURL ?? "https://api.openai.com", appendV1: appendV1Suffix)
            let v1Path = appendV1Suffix ? "/v1" : ""
            guard let u = URL(string: URLBuilding.join(base, v1Path, "/images/edits")) else {
                throw LLMError.providerError(message: "Invalid URL for /images/edits")
            }
            url = u
        }

        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        // [T-image-gen-timeout] 600s — see generateImage above. Per-request
        // timeoutInterval overrides the session budget, so /images/edits
        // needs the same bump or long edits still abort at 120s.
        request.timeoutInterval = 600

        let token = try await getToken()
        applyKeyAuth(&request, token: token)
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        var body = Data()
        func appendField(_ name: String, _ value: String) {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }
        func appendFile(_ name: String, filename: String, mimeType: String, data: Data) {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
            body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
            body.append(data)
            body.append("\r\n".data(using: .utf8)!)
        }

        appendField("model", model.id)
        appendField("prompt", prompt)
        appendField("n", "\(n)")
        appendField("response_format", "b64_json")
        if let size { appendField("size", size) }
        if let quality { appendField("quality", quality) }

        // Attach images — first image as "image", additional as "image[]"
        for (idx, img) in images.enumerated() {
            let ext = img.mimeType.split(separator: "/").last.map(String.init) ?? "png"
            let fieldName = idx == 0 ? "image" : "image[]"
            appendFile(fieldName, filename: "image\(idx).\(ext)", mimeType: img.mimeType, data: img.data)
        }

        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        #if DEBUG
        logger.debug("📤 OpenAI /images/edits request: model=\(model.id) prompt=\(prompt.prefix(100)) images=\(images.count)")
        // multipart bodies are mostly binary; record a JSON summary instead so Copy
        // Requests still shows something useful (model, prompt, image count, sizes).
        let editSummary: [String: Any] = [
            "_endpoint": "/v1/images/edits",
            "_note": "multipart/form-data — binary fields elided",
            "model": model.id,
            "prompt": prompt,
            "n": n,
            "size": size as Any,
            "quality": quality as Any,
            "images": images.enumerated().map { idx, img in
                ["index": idx, "mime_type": img.mimeType, "bytes": img.data.count]
            },
        ]
        if let summary = try? JSONSerialization.data(withJSONObject: editSummary, options: [.prettyPrinted, .sortedKeys]),
           let summaryStr = String(data: summary, encoding: .utf8) {
            LastAPIRequestBody.shared.set(summaryStr, provider: "OpenAI")
        }
        let editHeaders: [String: String] = (request.allHTTPHeaderFields ?? [:]).reduce(into: [:]) { acc, pair in
            acc[pair.key] = pair.key == "Authorization" ? "Bearer ***" : pair.value
        }
        LastAPIRequestBody.shared.setRequestMeta(
            url: url.absoluteString,
            method: "POST",
            headers: editHeaders
        )
        #endif

        // [T-image-gen-timeout] streamingSession (10-min request timeout)
        // — /images/edits is as slow as /images/generations; the 60s
        // default on URLSession.shared aborts long edits with -1001.
        let (data, response) = try await streamingSession.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1

        #if DEBUG
        let httpResp = response as? HTTPURLResponse
        let respHeaders: [String: String]? = (httpResp?.allHeaderFields as? [String: Any])?
            .reduce(into: [String: String]()) { acc, pair in acc[pair.key] = "\(pair.value)" }
        LastAPIRequestBody.shared.setHTTPResponse(statusCode: statusCode, headers: respHeaders)
        if let preview = String(data: data.prefix(8 * 1024), encoding: .utf8) {
            LastAPIRequestBody.shared.appendResponseBody(preview)
        }
        #endif

        guard (200..<300).contains(statusCode) else {
            let responseBody = String(data: data, encoding: .utf8) ?? ""
            throw mapHTTPError(statusCode: statusCode, body: responseBody)
        }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        return try await parseImageGenerationsResult(json)
    }

    /// Detect image MIME type from magic bytes.
    private static func detectImageMime(_ data: Data) -> String {
        guard data.count >= 4 else { return "image/png" }
        let bytes = [UInt8](data.prefix(4))
        if bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 {
            return "image/png"
        }
        if bytes[0] == 0xFF && bytes[1] == 0xD8 {
            return "image/jpeg"
        }
        if bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 {
            return "image/webp" // RIFF container (WebP)
        }
        if bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 {
            return "image/gif"
        }
        return "image/png"
    }

    // MARK: - Image Generation (Codex OAuth image_generation tool)

    /// Generate (or edit) an image through the Codex OAuth `image_generation`
    /// tool. Codex OAuth only — the caller (ModelUseOffloadBridge) must ensure
    /// this provider is a Codex OAuth instance.
    ///
    /// Unlike the API-key `/v1/images/generations` path, this drives a Codex
    /// text model (gpt-5.5 etc.) on `chatgpt.com/backend-api/codex/responses`
    /// with the built-in `image_generation` tool, which invokes gpt-image-2
    /// server-side. Reuses `streamRaw(isResponsesAPI:true)` for the endpoint +
    /// full Codex auth header set, and crucially the OAuth token from
    /// CodexOAuthManager (NOT a chatgpt.com/api/auth/session web token, which
    /// 401s against the codex backend — see summary §8.1/§11.1).
    ///
    /// - Parameters:
    ///   - topLevelModel: Codex text model placed in `body["model"]` that
    ///     triggers the tool (e.g. the latest gpt-* model). nil → `model.id`.
    ///   - imageModel: informational; the tool resolves gpt-image-2 itself.
    func generateImageViaCodexResponses(
        prompt: String,
        inputImages: [LLMMessage.ImageAttachment] = [],
        topLevelModel: String? = nil,
        size: String? = nil,
        quality: String? = nil
    ) async throws -> LLMResponse {
        // The verified request shape (summary §4.1): a plain-text instruction
        // that asks the model to use the image_generation tool, the bare tool
        // entry, string tool_choice="auto", low reasoning effort. size/quality
        // are not part of this shape — the codex backend only does "auto"; the
        // caller logs that they're ignored.
        let userContent = "Use the image generation tool to create: \(prompt)"
        var input: [[String: Any]]
        if inputImages.isEmpty {
            input = [["role": "user", "content": userContent]]
        } else {
            // Image-to-image: send multimodal content (text + input_image data
            // URLs). Mirrors responsesAPIMessageDict's input_image shape.
            var parts: [[String: Any]] = [["type": "input_text", "text": userContent]]
            for img in inputImages {
                parts.append([
                    "type": "input_image",
                    "image_url": "data:\(img.mimeType);base64,\(img.data.base64EncodedString())",
                    "detail": "auto",
                ])
            }
            input = [["role": "user", "content": parts]]
        }

        let body: [String: Any] = [
            "model": topLevelModel ?? model.id,
            "instructions": "You are a helpful assistant. Use tools when available.",
            "input": input,
            "store": false,
            "tools": [["type": "image_generation"]],
            "reasoning": ["effort": "low"],
            "include": [],
            "tool_choice": "auto",
            "parallel_tool_calls": true,
            "stream": true,
        ]

        let (lineStream, _) = try await streamRaw(body: body, isResponsesAPI: true)
        return try await consumeCodexImageStream(lineStream)
    }

    /// Consume a Codex Responses SSE stream and extract a generated image,
    /// distinguishing the four documented failure modes (summary §5/§8/§11.3):
    ///   - auth (401/403): surfaced earlier by streamRaw's mapHTTPError as
    ///     `.invalidAPIKey`; not reached here.
    ///   - safety refusal: `image_generation_call` with status=failed, then an
    ///     output_text refusal → `.providerError("rejected by safety system…")`.
    ///   - no image data: stream completed but no base64 PNG → `.providerError`.
    ///   - network/interface: stream throws → propagated.
    private func consumeCodexImageStream(
        _ lineStream: AsyncThrowingStream<String, Error>
    ) async throws -> LLMResponse {
        var b64Result: String?
        var revisedPrompt: String?
        var imageCallFailed = false
        var refusalText: String?

        func scanItem(_ item: [String: Any]) {
            guard let type = item["type"] as? String else { return }
            if type == "image_generation_call" {
                if let status = item["status"] as? String, status == "failed" {
                    imageCallFailed = true
                }
                if let r = item["result"] as? String, !r.isEmpty { b64Result = r }
                if let rp = item["revised_prompt"] as? String { revisedPrompt = rp }
            } else if type == "message" || type == "output_text" {
                // Refusal/explanation text the model emits when it won't generate.
                if let content = item["content"] as? [[String: Any]] {
                    for c in content where (c["type"] as? String)?.contains("text") == true {
                        if let t = c["text"] as? String, !t.isEmpty { refusalText = t }
                    }
                } else if let t = item["text"] as? String, !t.isEmpty {
                    refusalText = t
                }
            }
        }

        do {
            for try await line in lineStream {
                guard let payload = Self.ssePayloadLocal(line) else { continue }
                if payload == "[DONE]" { break }
                guard let data = payload.data(using: .utf8),
                      let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
                else { continue }

                switch event["type"] as? String {
                case "response.output_item.done":
                    if let item = event["item"] as? [String: Any] { scanItem(item) }
                case "response.output_text.done", "response.output_text.delta":
                    if let t = event["text"] as? String, !t.isEmpty { refusalText = t }
                    else if let delta = event["delta"] as? String, !delta.isEmpty {
                        refusalText = (refusalText ?? "") + delta
                    }
                case "response.completed":
                    if b64Result == nil,
                       let resp = event["response"] as? [String: Any],
                       let output = resp["output"] as? [[String: Any]] {
                        output.forEach(scanItem)
                    }
                case "response.failed", "error":
                    let msg = ((event["response"] as? [String: Any])?["error"] as? [String: Any])?["message"] as? String
                        ?? (event["error"] as? [String: Any])?["message"] as? String
                        ?? "Codex image generation failed"
                    throw LLMError.providerError(message: msg)
                default:
                    break
                }
            }
        } catch let e as LLMError {
            throw e
        } catch {
            // Network / interface error mid-stream.
            throw LLMError.networkError(underlying: error)
        }

        // Success: base64 image extracted.
        if let b64 = b64Result, let imgData = Data(base64Encoded: b64) {
            let mime = Self.detectImageMime(imgData)
            var response = LLMResponse(text: revisedPrompt ?? "", stopReason: "end_turn", usage: nil)
            response.mediaAttachments = [LLMMediaAttachment(type: .image, mimeType: mime, data: imgData)]
            return response
        }

        // Safety refusal: image call explicitly failed and/or the model gave a
        // refusal message instead of an image.
        if imageCallFailed || refusalText != nil {
            let reason = refusalText?.trimmingCharacters(in: .whitespacesAndNewlines)
            throw LLMError.providerError(
                message: "Image generation rejected by safety system" + (reason.map { ": \($0)" } ?? ""))
        }

        // Stream completed with neither an image nor a refusal.
        throw LLMError.providerError(message: "No image data in Codex response")
    }

    /// Local copy of OpenAIAgentProvider.ssePayload (avoids widening that type's
    /// access). Strips the `data:` SSE-line prefix.
    private static func ssePayloadLocal(_ line: String) -> String? {
        guard line.hasPrefix("data:") else { return nil }
        let after = line.dropFirst(5)
        return after.first == " " ? String(after.dropFirst()) : String(after)
    }

    // MARK: - Error Handling

    func mapError(_ error: Error) -> LLMError {
        if error is CancellationError { return .cancelled }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            if nsError.code == NSURLErrorCancelled { return .cancelled }
            return .networkError(underlying: error)
        }
        if let llmError = error as? LLMError { return llmError }
        return .providerError(message: error.localizedDescription)
    }

    func mapHTTPError(statusCode: Int, body: String) -> LLMError {
        if statusCode == 401 || statusCode == 403 { return .invalidAPIKey(detail: "HTTP \(statusCode): \(String(body.prefix(200)))") }
        if statusCode == 429 { return .rateLimited }

        // Transient server errors: retry same model, do not trigger group fallback.
        let transientStatusCodes: Set<Int> = [500, 502, 503, 504, 529]
        if transientStatusCodes.contains(statusCode) {
            return .transientError(message: "HTTP \(statusCode): \(body.prefix(200))")
        }

        // Try to extract error message from JSON body
        if let data = body.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let error = json["error"] as? [String: Any],
           let message = error["message"] as? String {
            return .providerError(message: "[\(statusCode)] \(message)")
        }

        return .providerError(message: "HTTP \(statusCode): \(body.prefix(500))")
    }
}
