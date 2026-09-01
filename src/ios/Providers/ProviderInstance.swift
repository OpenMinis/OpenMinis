import Foundation

/// User preference for which OpenAI-compatible endpoint to use for image generation.
///   - `auto`:  try /v1/images/generations first; fall back to /v1/chat/completions on 4xx.
///              The successful path is cached on the instance via `imageEndpointResolved`.
///   - `imagesGenerations`: always use /v1/images/generations.
///   - `chatCompletions`:   always use /v1/chat/completions (multimodal output).
enum ImageEndpointMode: String, Codable, Hashable, CaseIterable {
    case auto
    case imagesGenerations = "images_generations"
    case chatCompletions = "chat_completions"
}

/// A configured provider source (type + credential + label).
/// Multiple instances of the same type are allowed (e.g. two Anthropic accounts).
struct ProviderInstance: Identifiable, Codable, Hashable {
    let id: String
    var label: String
    let providerType: ProviderType
    let credentialType: ProviderCredential
    var isEnabled: Bool
    let createdAt: Date
    /// Optional custom API base URL (e.g. for proxy services). nil = use provider default.
    var customBaseURL: String?
    /// When true (default), "/v1" is automatically appended to the custom base URL.
    /// Set to false for endpoints that already include the full path (e.g. "https://host/api/v2").
    var appendV1Suffix: Bool
    /// User preference for image-generation endpoint. Defaults to `.auto` so existing
    /// instances (decoded from JSON without this field) keep the previous probe behavior.
    var imageEndpointMode: ImageEndpointMode
    /// Persisted cache of which endpoint actually worked under `.auto`. Cleared whenever
    /// the user changes `customBaseURL`/`appendV1Suffix` so we re-probe the new endpoint.
    var imageEndpointResolved: ImageEndpointMode?
    /// Optional custom `User-Agent` header for outbound requests. nil/empty = use the
    /// Minis default UA. Set for proxy/relay endpoints that gate on client UA (only
    /// allow e.g. Claude Code). Applied to chat/models/responses requests of API-key
    /// and manual-token providers; OAuth providers keep their required client UA.
    var customUserAgent: String?
    /// [T-ios-azure-openai] Azure OpenAI mode. When true, OpenAIProvider auths
    /// with the `api-key:` header (not `Authorization: Bearer`) and treats the
    /// custom base URL as an Azure endpoint (the user pastes the full Azure URL
    /// including `?api-version=…`). Orthogonal to the Chat/Responses API-format
    /// choice — both work under Azure. Defaults to false so existing OpenAI /
    /// Responses instances are completely unaffected.
    var azureMode: Bool
    /// When `providerType == .unsupported`, the ORIGINAL raw type string from a
    /// newer build, kept so we re-serialize it faithfully (no data loss / no
    /// rewrite to a wrong type). nil for normal instances.
    var unknownProviderTypeRaw: String?

    init(
        id: String = UUID().uuidString,
        label: String,
        providerType: ProviderType,
        credentialType: ProviderCredential,
        isEnabled: Bool = true,
        createdAt: Date = Date(),
        customBaseURL: String? = nil,
        appendV1Suffix: Bool = true,
        imageEndpointMode: ImageEndpointMode = .auto,
        imageEndpointResolved: ImageEndpointMode? = nil,
        customUserAgent: String? = nil,
        azureMode: Bool = false,
        unknownProviderTypeRaw: String? = nil
    ) {
        self.id = id
        self.label = label
        self.providerType = providerType
        self.credentialType = credentialType
        self.isEnabled = isEnabled
        self.createdAt = createdAt
        self.customBaseURL = customBaseURL
        self.appendV1Suffix = appendV1Suffix
        self.imageEndpointMode = imageEndpointMode
        self.imageEndpointResolved = imageEndpointResolved
        self.customUserAgent = customUserAgent
        self.azureMode = azureMode
        self.unknownProviderTypeRaw = unknownProviderTypeRaw
    }

    // MARK: - Codable (manual, for backwards compatibility)

    enum CodingKeys: String, CodingKey {
        case id, label, providerType, credentialType, isEnabled, createdAt, customBaseURL, appendV1Suffix
        case imageEndpointMode, imageEndpointResolved, customUserAgent, azureMode
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        label = try c.decode(String.self, forKey: .label)
        // Forward-compat: an unknown provider-type string (synced from a newer
        // build) decodes to `.unsupported` instead of throwing, and we keep the
        // raw string so we re-serialize it unchanged.
        let typeRaw = try c.decode(String.self, forKey: .providerType)
        providerType = ProviderType.decoded(typeRaw)
        unknownProviderTypeRaw = (providerType == .unsupported) ? typeRaw : nil
        credentialType = try c.decode(ProviderCredential.self, forKey: .credentialType)
        isEnabled = try c.decode(Bool.self, forKey: .isEnabled)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        customBaseURL = try c.decodeIfPresent(String.self, forKey: .customBaseURL)
        appendV1Suffix = try c.decodeIfPresent(Bool.self, forKey: .appendV1Suffix) ?? true
        // Both new fields tolerate absence (old data) and unknown values (forward compat).
        imageEndpointMode = (try? c.decodeIfPresent(ImageEndpointMode.self, forKey: .imageEndpointMode)) ?? .auto
        imageEndpointResolved = try? c.decodeIfPresent(ImageEndpointMode.self, forKey: .imageEndpointResolved)
        // Additive + optional: old data without this key decodes to nil (default UA).
        customUserAgent = try c.decodeIfPresent(String.self, forKey: .customUserAgent)
        // [T-ios-azure-openai] Additive + optional: old data → false (off), so
        // existing OpenAI/Responses instances keep their exact prior behavior.
        azureMode = (try? c.decodeIfPresent(Bool.self, forKey: .azureMode)) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(label, forKey: .label)
        // Re-serialize the ORIGINAL raw type for unsupported (newer-build) instances
        // so we never overwrite it with the "unsupported" sentinel string.
        try c.encode(unknownProviderTypeRaw ?? providerType.rawValue, forKey: .providerType)
        try c.encode(credentialType, forKey: .credentialType)
        try c.encode(isEnabled, forKey: .isEnabled)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encodeIfPresent(customBaseURL, forKey: .customBaseURL)
        try c.encode(appendV1Suffix, forKey: .appendV1Suffix)
        try c.encode(imageEndpointMode, forKey: .imageEndpointMode)
        try c.encodeIfPresent(imageEndpointResolved, forKey: .imageEndpointResolved)
        try c.encodeIfPresent(customUserAgent, forKey: .customUserAgent)
        // [T-ios-azure-openai] Only encode when on, so off-instances serialize
        // byte-identically to before this field existed (no diff/churn, no LWW noise).
        if azureMode { try c.encode(azureMode, forKey: .azureMode) }
    }

    /// Returns customUserAgent if non-nil and non-empty (trimmed), otherwise nil.
    /// Use this everywhere the effective UA is needed — empty string means "default UA".
    var effectiveCustomUserAgent: String? {
        guard let ua = customUserAgent?.trimmingCharacters(in: .whitespacesAndNewlines), !ua.isEmpty else { return nil }
        return ua
    }

    /// Whether this instance should expose / inject a custom User-Agent.
    /// Only OpenAI-compatible or Anthropic-compatible endpoints reached through a
    /// custom base URL (proxy/relay) — official direct connections (no customBaseURL)
    /// and native Gemini/Antigravity are excluded.
    var supportsCustomUserAgent: Bool {
        guard effectiveCustomBaseURL != nil else { return false }
        switch providerType {
        case .openAI, .openAIResponses, .openRouter, .xAI, .kimiCode, .anthropic:
            return true
        case .gemini, .antigravity, .unsupported:
            return false
        }
    }

    // [T-mimo-shadow-voice] The old `isVoiceOnlyProvider` domain-whitelist
    // property has been REMOVED. Classification no longer binds an entire
    // instance to "voice-only" by base-URL substring — that misfiled mixed
    // vendors (MiMo's text chat + voice share one host) as pure-voice, which
    // skipped model refresh and dropped user-added text models. Voice ability is
    // now a per-MODEL modality concern; "does this instance have any voice
    // models" is computed dynamically by `ProviderConfigStore.hasVoiceModels(for:)`
    // (union of its model entries' modalities), and Voice Services shows a
    // read-only SHADOW view of any instance that has audio-modality models.

    /// [T-ios-azure-openai] Whether the Azure toggle applies to this instance —
    /// only OpenAI / OpenAI-Responses instances using an API key (Azure auths
    /// with an api-key header). Other provider types are unaffected.
    var supportsAzureMode: Bool {
        (providerType == .openAI || providerType == .openAIResponses) && credentialType == .apiKey
    }

    /// Returns customBaseURL if non-nil and non-empty, otherwise nil.
    /// Use this to check whether a custom base is configured; for building request URLs use resolvedBaseURL(default:).
    var effectiveCustomBaseURL: String? {
        guard let url = customBaseURL, !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return url.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Returns the base URL to use for API requests.
    /// - When no custom URL is set, returns `defaultBase`.
    /// - When `appendV1Suffix` is true (default), strips any trailing "/v1" so callers can safely append "/v1/...".
    /// - When `appendV1Suffix` is false, returns the custom URL verbatim (no strip, no append by caller).
    func resolvedBaseURL(default defaultBase: String) -> (url: String, appendV1: Bool) {
        guard let custom = effectiveCustomBaseURL else {
            return (normalizeV1(defaultBase), true)
        }
        if appendV1Suffix {
            return (normalizeV1(custom), true)
        } else {
            return (custom, false)
        }
    }

    private func normalizeV1(_ s: String) -> String {
        var r = s
        if r.hasSuffix("/v1/") { r = String(r.dropLast(4)) }
        else if r.hasSuffix("/v1") { r = String(r.dropLast(3)) }
        if r.hasSuffix("/") { r = String(r.dropLast()) }
        return r
    }

    /// Keychain service name for storing this instance's API key.
    var keychainService: String {
        "com.openminis.app.provider.\(id)"
    }

    /// Whether this provider type's image-output models flow through OpenAIProvider
    /// (and therefore can be routed via /v1/images/generations vs /v1/chat/completions).
    /// Anthropic / Gemini / Antigravity have their own native image paths and aren't
    /// affected by `imageEndpointMode`.
    var supportsImageEndpointSetting: Bool {
        switch providerType {
        case .openAI, .openAIResponses, .openRouter, .xAI, .kimiCode:
            return true
        default:
            return false
        }
    }

    /// [T-empty-key-compat-endpoints] Whether an EMPTY API key is a valid
    /// configuration for this instance. True only for API-key-mode instances
    /// pointing at a third-party OpenAI/Anthropic-compatible endpoint (custom
    /// base URL set): local gateways, ollama, LM Studio, LiteLLM and many
    /// relay deployments require no key at all, and forcing the user to type a
    /// dummy one is pure friction. Deliberately NOT extended to:
    ///   - official endpoints (no custom base URL) — an empty key against
    ///     api.openai.com / api.anthropic.com is always a misconfiguration and
    ///     treating it as valid would just move the failure to a confusing 401;
    ///   - OAuth-mode instances — their credential is the token, and an
    ///     instance with no token must keep reading as unauthenticated.
    var allowsEmptyAPIKey: Bool {
        guard credentialType == .apiKey,
              let base = customBaseURL?.trimmingCharacters(in: .whitespacesAndNewlines),
              !base.isEmpty else { return false }
        switch providerType {
        case .openAI, .openAIResponses, .anthropic:
            return true
        default:
            return false
        }
    }

    /// Whether this instance currently has any usable credential — an API key,
    /// a manual OAuth token, or a stored per-provider OAuth token (e.g. Claude
    /// login). Routing layers consult this to skip instances that would
    /// otherwise fire a no-auth request and produce a confusing 4xx (e.g.
    /// `OAuth authentication is currently not allowed for this organization`).
    ///
    /// Why this method lives here:
    ///   - The decision is purely a function of the instance + Keychain state,
    ///     so it belongs on ProviderInstance.
    ///   - Multiple call sites (group router, single-model resolver, UI
    ///     subtitle) need exactly the same answer. Centralizing avoids the
    ///     "router accepts X, factory bails on X" footgun that produced this
    ///     bug (iCloud-synced OAuth instance with no token still won the
    ///     fallback race because the router didn't filter on credential).
    ///
    /// Synchronous because all Keychain lookups underneath are synchronous;
    /// safe to call from any thread (no UI / actor side effects).
    ///
    /// L2 CACHE (T-new-session-hang-credential-cache): the router calls this
    /// per group member on every `resolveCurrentEntry`, which SwiftUI body
    /// re-eval fires ~84-178×/new-session. Each MISS is the expensive path —
    /// up to 6 synchronous Keychain XPC round-trips (apiKey / oauthString /
    /// oauthToken, each sync+legacy). Measured on device: a no-credential
    /// instance is ~6× the cost of a hit and is the main-thread pile-up behind
    /// the 1057ms hang. So results (INCLUDING negatives — that's the whole
    /// point) are memoized by instanceId. Invalidated precisely on every
    /// credential write (`ProviderCredentialCache.invalidate` from
    /// `notifyAuthChanged`), on OAuth refresh, on Keychain iCloud-sync
    /// notifications, and wholesale on foreground; a 15s TTL is only a
    /// defensive backstop, never the primary mechanism.
    var hasAnyCredential: Bool {
        ProviderCredentialCache.shared.value(for: id) { self.computeHasAnyCredential() }
    }

    /// The uncached credential probe. Kept separate so the cache wraps it and
    /// tests / diagnostics can force a fresh read.
    func computeHasAnyCredential() -> Bool {
        // [T-empty-key-compat-endpoints] Third-party compatible endpoint in
        // API-key mode: configured-by-definition, no Keychain probe needed
        // (also the cheapest possible path — zero XPC round-trips).
        if allowsEmptyAPIKey { return true }
        // API-key path is identical across providers — query first.
        if ProviderKeychainHelper.loadAPIKey(instanceId: id, caller: "hasAnyCredential")?.isEmpty == false {
            return true
        }
        // Manual OAuth token (user-pasted, used by Anthropic/Gemini/OpenAI to
        // override the OAuth flow with a bearer string they obtained out of
        // band) — counts as credential for routing.
        if ProviderKeychainHelper.loadOAuthString(
            instanceId: id, account: "manual-oauth-token", caller: "hasAnyCredential"
        )?.isEmpty == false {
            return true
        }
        // Provider-specific OAuth storage (Claude Code login, Codex login, …).
        // Mirrors the diagnostic in MinisApp.swift scenePhase=active.
        switch providerType {
        case .anthropic:
            return ProviderKeychainHelper.loadOAuthToken(
                instanceId: id, as: ClaudeTokenStorage.self, caller: "hasAnyCredential"
            ) != nil
        case .gemini:
            return ProviderKeychainHelper.loadOAuthToken(
                instanceId: id, as: GeminiTokenStorage.self, caller: "hasAnyCredential"
            ) != nil
        case .openAI, .openAIResponses:
            return ProviderKeychainHelper.loadOAuthToken(
                instanceId: id, as: CodexTokenStorage.self, caller: "hasAnyCredential"
            ) != nil
        case .xAI:
            return ProviderKeychainHelper.loadOAuthToken(
                instanceId: id, as: XAITokenStorage.self, caller: "hasAnyCredential"
            ) != nil
        case .kimiCode:
            return ProviderKeychainHelper.loadOAuthToken(
                instanceId: id, as: KimiTokenStorage.self, caller: "hasAnyCredential"
            ) != nil
        case .antigravity, .openRouter, .unsupported:
            // unsupported = synced from a newer build; no usable credential here.
            // antigravity stores its token via AntigravityOAuthManager (no
            // standalone Codable used by the diagnostic); OpenRouter is
            // API-key only in practice. If a manual token is missing, treat
            // as no-credential — the router will skip the entry and the
            // factory's empty-key branch can no longer fire.
            return false
        }
    }
}

/// L2 credential cache: memoizes `hasAnyCredential` per instanceId, negatives
/// included. [T-new-session-hang-credential-cache]
///
/// `@unchecked Sendable` + a lock because `hasAnyCredential` is documented as
/// callable from any thread (the router runs on MainActor, but nothing enforces
/// it). The stored value is a plain Bool + timestamp; the lock only guards the
/// dictionary.
///
/// Invalidation is precise (see `ProviderInstance.hasAnyCredential` doc): callers
/// hit `invalidate(_:)` / `invalidateAll()` on every credential-affecting event.
/// The `ttl` is a backstop only — if some future write path forgets to
/// invalidate, a stale entry self-heals within 15s rather than sticking forever.
final class ProviderCredentialCache: @unchecked Sendable {
    static let shared = ProviderCredentialCache()

    /// Backstop TTL. Deliberately far shorter than a single new-session resolve
    /// storm (which finishes in a few seconds), so within one storm every lookup
    /// after the first is a cache hit; and short enough that a missed
    /// invalidation can't wedge routing for long.
    static let ttl: TimeInterval = 15

    private let lock = NSLock()
    private var entries: [String: (value: Bool, at: Date)] = [:]

    private init() {}

    /// Return the cached result if fresh, otherwise compute via `probe`, store,
    /// and return. `probe` runs OUTSIDE the lock (it does Keychain XPC — must not
    /// serialize concurrent probes for *different* instances behind one lock).
    func value(for instanceId: String, probe: () -> Bool) -> Bool {
        let now = Date()
        lock.lock()
        if let e = entries[instanceId], now.timeIntervalSince(e.at) < Self.ttl {
            lock.unlock()
            return e.value
        }
        lock.unlock()

        let fresh = probe()

        lock.lock()
        entries[instanceId] = (fresh, now)
        lock.unlock()
        return fresh
    }

    /// Drop one instance's cached result — call after any credential write for it.
    func invalidate(_ instanceId: String) {
        lock.lock()
        entries.removeValue(forKey: instanceId)
        lock.unlock()
    }

    /// Drop everything — foreground backstop, and the Keychain iCloud-sync hook
    /// (a sync event carries no instanceId, so the safe move is a full clear).
    func invalidateAll() {
        lock.lock()
        entries.removeAll()
        lock.unlock()
    }
}
