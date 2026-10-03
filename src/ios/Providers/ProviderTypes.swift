import Foundation

// MARK: - Provider Type

/// The LLM provider backend.
enum ProviderType: String, Codable, CaseIterable, Hashable, Sendable {
    case openAI
    case anthropic
    case gemini
    case antigravity
    case openRouter
    /// OpenAI Responses API — uses the /v1/responses endpoint format.
    /// Works with OpenAI directly or any Responses-API-compatible service.
    case openAIResponses
    /// xAI Grok (SuperGrok / X Premium+ OAuth). OpenAI-compatible API,
    /// flows through OpenAIProvider with custom base URL + OAuth bearer.
    case xAI
    /// Kimi Code / Coding Plan (Moonshot). RFC 8628 device-code OAuth,
    /// OpenAI-compatible coding upstream — flows through OpenAIProvider with
    /// custom base URL + OAuth bearer, like xAI.
    case kimiCode
    /// [T-copilot-provider] GitHub Copilot via an UNOFFICIAL reverse-engineered
    /// integration: RFC 8628 device-code sign-in to GitHub, then a short-lived
    /// Copilot session token. The chat surface is OpenAI-compatible, so it
    /// flows through OpenAIProvider like xAI/Kimi. Opt-in, warned about in the
    /// sign-in UI, and hideable via CopilotConstants.isEnabled.
    case githubCopilot
    /// Sentinel for a provider type this app build doesn't recognize — e.g. a
    /// NEWER build synced an instance whose `provider_type` string isn't a known
    /// case here. We DECODE to this instead of throwing/dropping, so the instance
    /// is preserved (shown as "Unsupported", unusable) and not silently rewritten
    /// to a wrong type on the next save. The original raw string is kept alongside
    /// (see ProviderInstance.unknownProviderTypeRaw) for faithful round-tripping.
    case unsupported

    /// Decode a raw provider-type string, never throwing: an unrecognized value
    /// maps to `.unsupported` (forward-compat with newer builds).
    static func decoded(_ raw: String) -> ProviderType {
        ProviderType(rawValue: raw) ?? .unsupported
    }

    var displayName: String {
        switch self {
        case .anthropic: return "Anthropic"
        case .gemini: return "Google Gemini"
        case .openAI: return "OpenAI"
        case .antigravity: return "Antigravity"
        case .openRouter: return "OpenRouter"
        case .openAIResponses: return "Responses API (v3)"
        case .xAI: return "xAI (Grok)"
        case .kimiCode: return "Kimi Code"
        case .githubCopilot: return "GitHub Copilot"
        case .unsupported: return "Unsupported"
        }
    }

    /// Whether an OAuth instance of this provider can discover its model list
    /// from the network, rather than only reporting a compiled-in catalog.
    ///
    /// [T-provider-oauth-model-discovery] This drives the one-shot refresh
    /// `ProviderConfigStore.addInstance` fires after a provider is added, so a
    /// freshly-authenticated account shows the vendor's CURRENT models instead
    /// of whatever this build happened to ship with (GH#265: xAI OAuth seeded a
    /// static catalog with no grok-4.6, and only a manual Settings → Models →
    /// Refresh fixed it).
    ///
    /// Deliberately a property of the TYPE rather than a check at the call
    /// site: the same question is asked of every provider, and the answer is a
    /// fact about how that provider's OAuth branch in
    /// `fetchModelsForInstance` is implemented. Keeping it here means adding a
    /// provider means answering it, instead of silently inheriting "no".
    ///
    /// `false` for the two whose OAuth branch returns a static list without a
    /// request — refreshing them would spend a network round-trip to arrive
    /// back at the catalog already seeded.
    var oauthSupportsModelDiscovery: Bool {
        switch self {
        case .anthropic, .gemini, .antigravity, .openRouter, .xAI, .kimiCode, .githubCopilot:
            // These OAuth branches all reach a real /models endpoint.
            return true
        case .openAI:
            // OpenAIModelsAPI.fetchModelsOAuth() is a compiled-in list.
            return false
        case .openAIResponses:
            // Responses-API instances are API-key only; the OAuth branch
            // returns builtInModels.
            return false
        case .unsupported:
            // Synced from a newer build — nothing to fetch with.
            return false
        }
    }

    /// Built-in models for this provider type.
    var builtInModels: [LLMModel] {
        switch self {
        case .anthropic: return LLMModel.allAnthropic
        case .gemini: return LLMModel.allGemini
        case .openAI: return LLMModel.allOpenAI
        case .antigravity: return LLMModel.allAntigravity
        case .openRouter: return LLMModel.allOpenRouter
        case .openAIResponses: return LLMModel.allOpenAI
        case .xAI: return XAIModelsAPI.allModels
        case .kimiCode: return KimiModelsAPI.allModels
        // Deliberately empty: the task requires the list come from the server
        // (`model_picker_enabled`), never a compiled-in guess that would go
        // stale and offer models the account cannot use.
        case .githubCopilot: return []
        case .unsupported: return []
        }
    }

    /// Short description shown under the provider name in the Add Provider
    /// picker — what kinds of services this protocol supports, rather than a
    /// raw built-in model count. Localized; English key, translations in
    /// Localizable.xcstrings.
    var pickerSubtitle: String {
        switch self {
        case .openAI, .openAIResponses:
            return AppLocalized("Works with Codex, DeepSeek, Moonshot, Groq and other compatible vendors")
        case .anthropic:
            return AppLocalized("Works with Claude and Anthropic-protocol-compatible services")
        case .gemini:
            return AppLocalized("Works with the Gemini series and Google AI Studio")
        case .openRouter:
            return AppLocalized("Aggregates GPT, Claude, Gemini, Llama and other mainstream models")
        case .xAI:
            return AppLocalized("Works with the Grok series of models")
        case .kimiCode:
            return AppLocalized("Sign in with your Kimi Code / Coding Plan subscription")
        case .githubCopilot:
            return AppLocalized("Sign in with GitHub — unofficial, use at your own risk")
        case .antigravity:
            return AppLocalized("\(builtInModels.count) built-in models")
        case .unsupported:
            return AppLocalized("\(builtInModels.count) built-in models")
        }
    }

    /// Default modality assumed for custom models added to this provider.
    var defaultModality: ModelModality {
        switch self {
        case .anthropic: return .vision
        case .gemini:    return .fullMultimodal
        case .openAI:    return .vision
        case .antigravity: return .fullMultimodal
        case .openRouter: return .vision
        case .openAIResponses: return .vision
        case .xAI: return .vision
        case .kimiCode: return .vision
        case .githubCopilot: return .vision
        case .unsupported: return .vision
        }
    }

    /// True when this build can't actually use the provider (synced from a newer app).
    var isUnsupported: Bool { self == .unsupported }
}

// MARK: - Credential Type

/// How a provider instance authenticates.
enum ProviderCredential: String, Codable, Hashable, Sendable {
    case apiKey
    case oauth
}
