import Foundation

/// Built-in Kimi Code / Coding Plan model catalog. The Kimi Coding upstream
/// (`https://api.kimi.com/coding`) is OpenAI-compatible, so these flow through
/// `OpenAIProvider` with a custom base URL + OAuth bearer (mirroring xAI).
///
/// This is only the BUILT-IN fallback shown before the first live model fetch.
/// After OAuth login the app calls the real `/coding/v1/models` endpoint (via
/// ProviderConfigStore's model-fetch path) and replaces this with the account's
/// actual catalog — so the authoritative list is always the live one.
///
/// The built-in list is deliberately minimal and NON-speculative: it carries
/// only `kimi-k3`, the current flagship confirmed present in a live fetch
/// (logged as the first model), plus the prior `kimi-k2` id. We do NOT hardcode
/// a guessed catalog — the live fetch fills in the rest (e.g. "Kimi K2.7 Code").
enum KimiModelsAPI {
    /// Default pick before the first live fetch — current flagship.
    static let defaultModelId = "kimi-k3"

    static let allModels: [LLMModel] = [
        LLMModel(id: defaultModelId, displayName: "Kimi K3", provider: "Kimi"),
        LLMModel(id: "kimi-k2", displayName: "Kimi K2", provider: "Kimi"),
    ]

    /// The real catalog comes from the OAuth model-fetch path
    /// (`OpenAIModelsAPI.fetchModels` against `/coding/v1/models`), not here.
    /// This fallback returns the built-in list only.
    static func fetchModels(accessToken: String) async -> [LLMModel] {
        return allModels
    }
}
