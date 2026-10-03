import Foundation

/// Built-in xAI Grok model catalog. The xAI API is OpenAI-compatible, so these
/// models flow through `OpenAIProvider` with a custom base URL + OAuth bearer.
enum XAIModelsAPI {
    // Catalog ordering = default-pick order, newest flagship first.
    //
    // [T-provider-oauth-model-discovery] This list is a SEED and a fallback,
    // not the source of truth. An xAI instance refreshes against the live
    // /models endpoint when it is added and whenever it is re-authenticated,
    // so a model that ships after this build still appears; this catalog is
    // what the user sees in the moment before that lands, and what they keep
    // if the network is unavailable. It is therefore worth keeping roughly
    // current, but it will always trail the vendor — which is precisely why
    // the refresh exists (GH#265: a stale catalog with no grok-4.6 was the
    // reported symptom, and pinning the list alone would not have fixed the
    // next release).
    static let allModels: [LLMModel] = [
        // Official xAI catalog (docs.x.ai/docs/models) — synced from CLIProxyAPI models.json
        LLMModel(id: "grok-4.6", displayName: "Grok 4.6", provider: "xAI"),
        LLMModel(id: "grok-4.5", displayName: "Grok 4.5", provider: "xAI"),
        LLMModel(id: "grok-4.3", displayName: "Grok 4.3", provider: "xAI"),
        LLMModel(id: "grok-4.20-0309-reasoning", displayName: "Grok 4.20 Reasoning", provider: "xAI"),
        LLMModel(id: "grok-4.20-0309-non-reasoning", displayName: "Grok 4.20", provider: "xAI"),
        LLMModel(id: "grok-4.20-multi-agent-0309", displayName: "Grok 4.20 Multi-Agent", provider: "xAI"),
        LLMModel(id: "grok-build-0.1", displayName: "Grok Build 0.1", provider: "xAI"),
        LLMModel(id: "grok-3-mini", displayName: "Grok 3 Mini", provider: "xAI"),
        LLMModel(id: "grok-3-mini-fast", displayName: "Grok 3 Mini Fast", provider: "xAI"),
        LLMModel(id: "grok-composer-2.5-fast", displayName: "Grok Composer 2.5 Fast", provider: "xAI"),
        // High-frequency fast / code variants (docs.x.ai/docs/models).
        LLMModel(id: "grok-4-fast", displayName: "Grok 4 Fast", provider: "xAI"),
        LLMModel(id: "grok-4-fast-non-reasoning", displayName: "Grok 4 Fast (Non-Reasoning)", provider: "xAI"),
        LLMModel(id: "grok-code-fast-1", displayName: "Grok Code Fast 1", provider: "xAI"),
    ]
}
