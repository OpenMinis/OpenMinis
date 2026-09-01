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
    /// custom base URL + OAuth bearer, like xAI. See the Kimi Code OAuth design notes.
    case kimiCode
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
        case .unsupported: return "Unsupported"
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
