import Foundation

// MARK: - Model Capability Metadata

struct ModelModality: OptionSet, Sendable, Codable, Hashable {
    let rawValue: Int
    // Input
    static let textInput      = ModelModality(rawValue: 1 << 0)
    static let imageInput     = ModelModality(rawValue: 1 << 2)
    static let pdfInput       = ModelModality(rawValue: 1 << 3)
    static let audioInput     = ModelModality(rawValue: 1 << 4)
    static let videoInput     = ModelModality(rawValue: 1 << 5)
    // Output
    static let textOutput     = ModelModality(rawValue: 1 << 1)
    static let imageOutput    = ModelModality(rawValue: 1 << 6)
    static let audioOutput    = ModelModality(rawValue: 1 << 7)
    static let videoOutput    = ModelModality(rawValue: 1 << 8)

    /// All input-side bits and all output-side bits, for direction-scoped checks.
    static let allInputs: ModelModality = [.textInput, .imageInput, .pdfInput, .audioInput, .videoInput]
    static let allOutputs: ModelModality = [.textOutput, .imageOutput, .audioOutput, .videoOutput]

    /// Just the input-side modalities of this set.
    var inputs: ModelModality { intersection(.allInputs) }
    /// Just the output-side modalities of this set.
    var outputs: ModelModality { intersection(.allOutputs) }

    static let textOnly: ModelModality = [.textInput, .textOutput]
    static let vision: ModelModality = [.textInput, .textOutput, .imageInput, .pdfInput]
    static let fullMultimodalInput: ModelModality = [.textInput, .textOutput, .imageInput, .pdfInput, .audioInput, .videoInput]
    /// Legacy alias — input multimodal only (no image/audio/video output).
    static let fullMultimodal: ModelModality = .fullMultimodalInput
}

enum ProviderAuthMethod: String, Sendable, Codable, Hashable {
    case apiKey
    case oauth
}

struct ModelCapabilities: Sendable, Codable, Hashable {
    let supportedModalities: ModelModality
    let supportedAuth: Set<ProviderAuthMethod>
}

// MARK: - LLMModel

struct LLMModel: Equatable, Hashable, Identifiable, Sendable, Codable {
    let id: String
    let displayName: String
    let provider: String
    /// Per-model modality override. When set, takes precedence over provider-level capabilities.
    var modalityOverride: ModelModality?
    /// Context window size in tokens (from models.dev or provider API). nil = use fallback heuristic.
    var contextWindow: Int?
    /// Maximum output tokens the model supports. nil = unknown.
    var maxOutputTokens: Int?
    /// Whether this model supports reasoning/thinking mode (from models.dev `reasoning` field).
    var supportsReasoning: Bool?
    /// Field name for interleaved reasoning content in streaming (e.g. "reasoning_content" for DeepSeek/Kimi).
    /// nil means the provider uses native thinking blocks (Anthropic) or thought parts (Gemini).
    var interleavedReasoningField: String?

    /// [T-reasoning-effort-data-driven] Effort tiers this model accepts on the
    /// wire, from the models.dev `reasoning_options` entry of type `effort`
    /// (e.g. `["high","max"]` for zhipuai glm-5.2). Lowercased.
    ///
    /// Two distinct jobs, both previously done by hardcoded id substring lists:
    ///   1. Presence (non-nil, non-empty) = "this model is controlled by
    ///      `reasoning_effort`" — the signal that replaces the
    ///      deepseek/glm/kimi/minimax family skip-list.
    ///   2. Contents = the ALLOWED tiers. The sets vary wildly across the
    ///      catalog (`["low","medium","high"]`, `["high","max"]`,
    ///      `["high","xhigh"]`, …), so the user's chosen level must be clamped
    ///      onto this set — sending "xhigh" to a `["high","max"]` model is the
    ///      same class of 400 the MiMo/Agnes clamp already guards against.
    ///
    /// nil = catalog says nothing; fall back to the legacy heuristics.
    var reasoningEffortValues: [String]?

    /// [OpenMinis#163] The catalog affirmatively declares NO effort tiers for
    /// this model — it reasons, but takes no `reasoning_effort` parameter.
    ///
    /// Distinct from `reasoningEffortValues == nil`, which also covers "the
    /// catalog has never heard of this model". Only the affirmative case may
    /// suppress the field; the unknown case stays permissive so third-party
    /// relays keep working. See `ModelsDevModel.declaresNoEffortTiers`.
    ///
    /// Optional (not a plain Bool) so decoding a model persisted before this
    /// field existed yields nil — "unknown", the pre-existing behaviour —
    /// rather than a synthesized `false` that would look like a real answer.
    var declaresNoEffortTiers: Bool?

    /// [T-thinking-off-custom-provider] True when `reasoningEffortValues` was read from a
    /// catalog entry belonging to this model's OWN provider, rather than from the
    /// cross-provider fallback scan.
    ///
    /// The fallback scan matches a bare model id under any publisher and majority-votes
    /// the effort sets, which is what every user-defined OpenAI-compatible relay resolves
    /// through (a custom provider name is in no `providerKeyMap` entry). Good enough to
    /// clamp a requested tier; NOT good enough to override the user's explicit "thinking
    /// off", because it describes somebody else's endpoint.
    ///
    /// Optional (not a plain `Bool`) for the same reason as `declaresNoEffortTiers`:
    /// `LLMModel` uses SYNTHESIZED Codable, so a non-optional field would make every
    /// model persisted before this existed fail to decode outright — taking the user's
    /// configured entries with it. nil decodes cleanly and reads as "not authoritative",
    /// which is the permissive/pass-through direction.
    var effortDeclarationIsAuthoritative: Bool?

    init(id: String, displayName: String, provider: String, modalityOverride: ModelModality? = nil,
         contextWindow: Int? = nil, maxOutputTokens: Int? = nil,
         supportsReasoning: Bool? = nil, interleavedReasoningField: String? = nil,
         reasoningEffortValues: [String]? = nil, declaresNoEffortTiers: Bool? = nil) {
        self.id = id
        self.displayName = displayName
        self.provider = provider
        self.modalityOverride = modalityOverride
        self.contextWindow = contextWindow
        self.maxOutputTokens = maxOutputTokens
        self.supportsReasoning = supportsReasoning
        self.interleavedReasoningField = interleavedReasoningField
        self.reasoningEffortValues = reasoningEffortValues
        self.declaresNoEffortTiers = declaresNoEffortTiers
    }

    // [T-anthropic-context-window] Explicit context/output caps per Anthropic's
    // model catalog: modern Opus/Sonnet 4.x & 5 and Fable 5 are 1M context;
    // Haiku 4.5 is 200K. Output: 128K (Opus/Fable), 64K (Sonnet/Haiku). Set
    // explicitly so the values don't depend on the id heuristic (which also
    // now defaults modern Claude to 1M as a backstop). models.dev enrich can
    // still override at runtime.
    static let claudeFable5 = LLMModel(
        id: "claude-fable-5",
        displayName: "Claude Fable 5",
        provider: "Anthropic",
        contextWindow: 1_000_000,
        maxOutputTokens: 128_000
    )

    static let claudeOpus5 = LLMModel(
        id: "claude-opus-5",
        displayName: "Claude Opus 5",
        provider: "Anthropic",
        contextWindow: 1_000_000,
        maxOutputTokens: 128_000
    )

    static let claudeOpus48 = LLMModel(
        id: "claude-opus-4-8",
        displayName: "Claude Opus 4.8",
        provider: "Anthropic",
        contextWindow: 1_000_000,
        maxOutputTokens: 128_000
    )

    static let claudeOpus46 = LLMModel(
        id: "claude-opus-4-6",
        displayName: "Claude Opus 4.6",
        provider: "Anthropic",
        contextWindow: 1_000_000,
        maxOutputTokens: 128_000
    )

    static let claudeSonnet5 = LLMModel(
        id: "claude-sonnet-5",
        displayName: "Claude Sonnet 5",
        provider: "Anthropic",
        contextWindow: 1_000_000,
        maxOutputTokens: 64_000
    )

    static let claudeSonnet46 = LLMModel(
        id: "claude-sonnet-4-6",
        displayName: "Claude Sonnet 4.6",
        provider: "Anthropic",
        contextWindow: 1_000_000,
        maxOutputTokens: 64_000
    )

    static let claudeHaiku45 = LLMModel(
        id: "claude-haiku-4-5",
        displayName: "Claude Haiku 4.5",
        provider: "Anthropic",
        contextWindow: 200_000,
        maxOutputTokens: 64_000
    )

    static let allAnthropic: [LLMModel] = [
        .claudeFable5, .claudeOpus5, .claudeOpus48, .claudeOpus46, .claudeSonnet5, .claudeSonnet46, .claudeHaiku45,
    ]

    // MARK: - Gemini Models

    static let gemini31ProPreview = LLMModel(
        id: "gemini-3.1-pro-preview",
        displayName: "Gemini 3.1 Pro (Preview)",
        provider: "Google"
    )

    static let gemini3ProPreview = LLMModel(
        id: "gemini-3-pro-preview",
        displayName: "Gemini 3 Pro (Preview)",
        provider: "Google"
    )

    static let gemini3FlashPreview = LLMModel(
        id: "gemini-3-flash-preview",
        displayName: "Gemini 3 Flash (Preview)",
        provider: "Google"
    )

    static let gemini25Pro = LLMModel(
        id: "gemini-2.5-pro",
        displayName: "Gemini 2.5 Pro",
        provider: "Google"
    )

    static let gemini25Flash = LLMModel(
        id: "gemini-2.5-flash",
        displayName: "Gemini 2.5 Flash",
        provider: "Google"
    )

    static let gemini25FlashLite = LLMModel(
        id: "gemini-2.5-flash-lite",
        displayName: "Gemini 2.5 Flash Lite",
        provider: "Google"
    )

    static let allGemini: [LLMModel] = [
        .gemini31ProPreview, .gemini3ProPreview, .gemini3FlashPreview,
        .gemini25Pro, .gemini25Flash, .gemini25FlashLite,
    ]

    // MARK: - Antigravity Models (via Cloud Code — Gemini, Claude, GPT)

    static let agGemini31ProHigh = LLMModel(id: "gemini-3.1-pro-high", displayName: "Gemini 3.1 Pro (High)", provider: "Antigravity")
    static let agGemini31ProLow = LLMModel(id: "gemini-3.1-pro-low", displayName: "Gemini 3.1 Pro (Low)", provider: "Antigravity")
    static let agGemini3ProHigh = LLMModel(id: "gemini-3-pro-high", displayName: "Gemini 3 Pro (High)", provider: "Antigravity")
    static let agGemini3ProLow = LLMModel(id: "gemini-3-pro-low", displayName: "Gemini 3 Pro (Low)", provider: "Antigravity")
    static let agGemini3ProImage = LLMModel(id: "gemini-3-pro-image", displayName: "Gemini 3 Pro (Image)", provider: "Antigravity")
    static let agGemini3Flash = LLMModel(id: "gemini-3-flash", displayName: "Gemini 3 Flash", provider: "Antigravity")
    static let agClaudeOpus46Thinking = LLMModel(id: "claude-opus-4-6-thinking", displayName: "Claude Opus 4.6 Thinking", provider: "Antigravity")
    static let agClaudeSonnet46 = LLMModel(id: "claude-sonnet-4-6", displayName: "Claude Sonnet 4.6", provider: "Antigravity")
    static let agClaudeSonnet45Thinking = LLMModel(id: "claude-sonnet-4-5-thinking", displayName: "Claude Sonnet 4.5 Thinking", provider: "Antigravity")
    static let agClaudeSonnet45 = LLMModel(id: "claude-sonnet-4-5", displayName: "Claude Sonnet 4.5", provider: "Antigravity")

    static let allAntigravity: [LLMModel] = [
        .agGemini31ProHigh, .agGemini31ProLow,
        .agGemini3ProHigh, .agGemini3ProLow, .agGemini3ProImage, .agGemini3Flash,
        .agClaudeOpus46Thinking, .agClaudeSonnet46,
        .agClaudeSonnet45Thinking, .agClaudeSonnet45,
    ]

    // MARK: - OpenAI Models

    static let gpt56Sol = LLMModel(
        id: "gpt-5.6-sol",
        displayName: "GPT-5.6 Sol",
        provider: "OpenAI"
    )

    static let gpt56Terra = LLMModel(
        id: "gpt-5.6-terra",
        displayName: "GPT-5.6 Terra",
        provider: "OpenAI"
    )

    static let gpt56Luna = LLMModel(
        id: "gpt-5.6-luna",
        displayName: "GPT-5.6 Luna",
        provider: "OpenAI"
    )

    static let gpt55 = LLMModel(
        id: "gpt-5.5",
        displayName: "GPT-5.5",
        provider: "OpenAI"
    )

    static let gpt54 = LLMModel(
        id: "gpt-5.4",
        displayName: "GPT-5.4",
        provider: "OpenAI"
    )

    /// [T-codex-oauth-model-prune] Codex OAuth only — verified callable on a
    /// live ChatGPT-account token (2026-08-01) and present in CLIProxyAPI's
    /// Codex client registry, but we had never listed it.
    static let gpt54Mini = LLMModel(
        id: "gpt-5.4-mini",
        displayName: "GPT-5.4 Mini",
        provider: "OpenAI"
    )

    static let gpt53 = LLMModel(
        id: "gpt-5.3",
        displayName: "GPT-5.3",
        provider: "OpenAI"
    )

    static let gpt52 = LLMModel(
        id: "gpt-5.2",
        displayName: "GPT-5.2",
        provider: "OpenAI"
    )

    static let gpt5 = LLMModel(
        id: "gpt-5",
        displayName: "GPT-5",
        provider: "OpenAI"
    )

    static let gpt41 = LLMModel(
        id: "gpt-4.1",
        displayName: "GPT-4.1",
        provider: "OpenAI"
    )

    static let gpt4o = LLMModel(
        id: "gpt-4o",
        displayName: "GPT-4o",
        provider: "OpenAI"
    )

    static let gpt4oMini = LLMModel(
        id: "gpt-4o-mini",
        displayName: "GPT-4o Mini",
        provider: "OpenAI"
    )

    static let o3 = LLMModel(
        id: "o3",
        displayName: "o3",
        provider: "OpenAI"
    )

    static let o3Mini = LLMModel(
        id: "o3-mini",
        displayName: "o3 Mini",
        provider: "OpenAI"
    )

    static let o4Mini = LLMModel(
        id: "o4-mini",
        displayName: "o4 Mini",
        provider: "OpenAI"
    )

    static let gpt53Codex = LLMModel(
        id: "gpt-5.3-codex",
        displayName: "GPT-5.3 Codex",
        provider: "OpenAI"
    )

    static let gpt53CodexSpark = LLMModel(
        id: "gpt-5.3-codex-spark",
        displayName: "GPT-5.3 Codex Spark",
        provider: "OpenAI"
    )

    static let gpt52Codex = LLMModel(
        id: "gpt-5.2-codex",
        displayName: "GPT-5.2 Codex",
        provider: "OpenAI"
    )

    static let gpt51CodexMax = LLMModel(
        id: "gpt-5.1-codex-max",
        displayName: "GPT-5.1 Codex Max",
        provider: "OpenAI"
    )

    static let gpt51Codex = LLMModel(
        id: "gpt-5.1-codex",
        displayName: "GPT-5.1 Codex",
        provider: "OpenAI"
    )

    static let gpt5Codex = LLMModel(
        id: "gpt-5-codex",
        displayName: "GPT-5 Codex",
        provider: "OpenAI"
    )

    static let gpt5CodexMini = LLMModel(
        id: "gpt-5-codex-mini",
        displayName: "GPT-5 Codex Mini",
        provider: "OpenAI"
    )

    static let codexMini = LLMModel(
        id: "codex-mini-latest",
        displayName: "Codex Mini",
        provider: "OpenAI"
    )

    /// Special image model: generated through the Codex OAuth `image_generation`
    /// tool (driven by gpt-5.5 on chatgpt.com/backend-api/codex/responses), NOT
    /// the normal Chat Completions / Responses API. `modalityOverride` is set
    /// explicitly — the inference rules don't match "gpt-image-2" and would
    /// otherwise leave it text-only. `.imageInput` enables future image-to-image
    /// (reference image) flows; `.imageOutput` marks it as a generator.
    /// Codex OAuth only — listed in `allOpenAICodexOAuth`, not `allOpenAI`.
    static let gptImage2 = LLMModel(
        id: "gpt-image-2",
        displayName: "GPT Image 2",
        provider: "OpenAI",
        modalityOverride: [.textInput, .imageInput, .imageOutput]
    )

    static let allOpenAI: [LLMModel] = [
        .gpt54, .gpt52, .gpt5, .gpt41,
        .gpt4o, .gpt4oMini,
        .o3, .o4Mini,
        .codexMini,
    ]

    /// Models available via Codex OAuth (ChatGPT subscription).
    ///
    /// [T-codex-oauth-model-prune] The Codex backend rejects a model the
    /// ChatGPT tier does not carry with
    /// `HTTP 400 {"detail":"The '<id>' model is not supported when using Codex
    /// with a ChatGPT account."}` — and that error surfaces as an empty
    /// assistant turn, so an unusable entry in this picker reads to the user as
    /// "tool calls are broken" rather than "wrong model". Entries verified 400
    /// on-device (2026-08-01, real Codex OAuth token) were removed:
    /// gpt-5.3-codex, gpt-5.3-codex-spark, gpt-5-codex-mini, gpt-5.3, gpt-5.2,
    /// gpt-5. The survivors match the model set CLIProxyAPI ships for the Codex
    /// client (`internal/registry/models/codex_client_models.json`), which is
    /// also gpt-5.6-*/5.5/5.4 only.
    ///
    /// Availability is tier-dependent, not absolute — a higher ChatGPT plan may
    /// carry more. Re-probe before adding anything back; do not restore an id
    /// from documentation alone.
    static let allOpenAICodexOAuth: [LLMModel] = [
        .gpt56Sol, .gpt56Terra, .gpt56Luna,
        .gpt55, .gpt54, .gpt54Mini,
        // gptImage2 is kept: it is an image endpoint, not a Codex chat model,
        // so the text-model probe above does not apply to it.
        .gptImage2,
    ]

    // MARK: - OpenRouter Models

    static let orClaudeSonnet4 = LLMModel(
        id: "anthropic/claude-sonnet-4",
        displayName: "Claude Sonnet 4",
        provider: "OpenRouter"
    )

    static let orGemini25Flash = LLMModel(
        id: "google/gemini-2.5-flash",
        displayName: "Gemini 2.5 Flash",
        provider: "OpenRouter"
    )

    static let orGPT4o = LLMModel(
        id: "openai/gpt-4o",
        displayName: "GPT-4o",
        provider: "OpenRouter"
    )

    static let orLlama4Maverick = LLMModel(
        id: "meta-llama/llama-4-maverick",
        displayName: "Llama 4 Maverick",
        provider: "OpenRouter"
    )

    static let allOpenRouter: [LLMModel] = [
        .orClaudeSonnet4, .orGemini25Flash, .orGPT4o, .orLlama4Maverick,
    ]

    static let allModels: [LLMModel] = allAnthropic + allGemini + allOpenAI + allAntigravity + allOpenRouter

    // MARK: - Modality Inference from Model Name

    /// Pattern table: substring matches (case-insensitive) on model ID or display name → output modalities.
    /// Applied when `modalityOverride` is nil and the provider API didn't return explicit modality info.
    private static let modalityInferenceRules: [(patterns: [String], modality: ModelModality)] = [
        // Image generation models
        (["image-gen", "image-generation", "imagen", "dall-e", "dalle", "gpt-5-image", "gpt-5.1-image"], .imageOutput),
        // Gemini image models (e.g. gemini-3-pro-image, gemini-3.1-flash-image-preview, gemini-2.5-flash-image)
        (["flash-image", "pro-image"], .imageOutput),
        // TTS / audio generation models
        (["tts", "audio-gen", "audio-generation", "speech", "gpt-audio", "4o-audio"], .audioOutput),
        // Video generation models
        (["video-gen", "video-generation", "veo", "sora"], .videoOutput),
        // Broad image/audio/video output markers
        (["image-output", "img-gen", "img-output"], .imageOutput),
    ]

    /// [T-mimo-shadow-voice] Substrings that mark a DEDICATED speech-to-text
    /// (ASR) model — audio-in, text-out. Kept separate from the output-modality
    /// table because an ASR model's modality must be the EXACT audio-only-input
    /// shape (`[.audioInput, .textOutput]`), not `base ∪ audioInput`: the voice
    /// pickers gate on `modalities.inputs == .audioInput` exactly, so a model that
    /// also advertises text/image input (from `base`) would NOT register as an
    /// ASR voice model.
    private static let asrInferencePatterns: [String] = [
        "-asr", "asr-", "_asr", "asr_", "whisper", "transcrib", "speech-to-text",
        "speech2text", "stt-", "-stt", "_stt", "stt_", "voice-input", "voice_input",
    ]

    /// [T-mimo-shadow-voice] Substrings that mark a DEDICATED text-to-speech
    /// (TTS) model — text-in, audio-out. Its modality must be exactly
    /// `[.textInput, .audioOutput]` for the TTS voice picker
    /// (`outputs == .audioOutput && !contains(.audioInput)`), so it's handled
    /// here with an exact shape rather than the broad output-union path.
    private static let ttsInferencePatterns: [String] = [
        "tts", "-tts", "_tts", "text-to-speech", "text2speech", "audio-gen",
        "audio-generation", "seed-tts", "voice-output", "voice_output",
    ]

    /// Infer a DEDICATED voice model's EXACT modality from its id/name, or nil if
    /// it isn't a dedicated ASR/TTS model. Checked before the broad output-union
    /// inference so the exact voice shape wins.
    static func inferDedicatedVoiceModality(id: String, displayName: String) -> ModelModality? {
        let hay = (id + " " + displayName).lowercased()
        if asrInferencePatterns.contains(where: { hay.contains($0) }) {
            return [.audioInput, .textOutput]   // ASR: audio in → text out
        }
        if ttsInferencePatterns.contains(where: { hay.contains($0) }) {
            return [.textInput, .audioOutput]   // TTS: text in → audio out
        }
        return nil
    }

    /// Infer output modalities from model ID/name patterns.
    /// Returns nil if no patterns match (no inference to apply).
    static func inferOutputModality(id: String, displayName: String) -> ModelModality? {
        let lid = id.lowercased()
        let lname = displayName.lowercased()
        var inferred: ModelModality = []
        for rule in modalityInferenceRules {
            for pattern in rule.patterns {
                if lid.contains(pattern) || lname.contains(pattern) {
                    inferred.insert(rule.modality)
                    break // this rule matched, move to next rule
                }
            }
        }
        return inferred.isEmpty ? nil : inferred
    }

    /// Enrich a model with models.dev data and apply pattern-based modality inference.
    /// models.dev data fills in modality, context window, and max output tokens.
    /// Pattern inference is a secondary fallback for models not in models.dev.
    func withModalityOverride(_ modality: ModelModality) -> LLMModel {
        var copy = self
        copy.modalityOverride = modality
        return copy
    }

    func withInferredModality() -> LLMModel {
        // First: enrich from models.dev (fills modality, contextWindow, maxOutputTokens if available)
        var enriched = ModelsDevAPI.enrichModel(self)

        // If models.dev already set the modality, we're done
        if enriched.modalityOverride != nil { return enriched }

        // [T-mimo-shadow-voice] Dedicated ASR/TTS models get their EXACT
        // voice-model modality (audio-only-input / audio-only-output), which the
        // voice pickers require. This must win over the broad output-union path
        // below (which would add text/image input from `base` and break the
        // pickers' exact-modality gate).
        if let voiceModality = Self.inferDedicatedVoiceModality(id: id, displayName: displayName) {
            enriched.modalityOverride = voiceModality
            return enriched
        }

        // Second: try pattern-based inference as fallback
        guard let inferred = Self.inferOutputModality(id: id, displayName: displayName) else { return enriched }
        let base = Self.knownCapabilities[provider]?.supportedModalities ?? Self.defaultCapabilities.supportedModalities
        enriched.modalityOverride = base.union(inferred)
        return enriched
    }

    // MARK: - Capability Lookup

    /// Known capabilities keyed by provider name.
    private static let knownCapabilities: [String: ModelCapabilities] = [
        "Anthropic": ModelCapabilities(
            supportedModalities: .vision,
            supportedAuth: [.apiKey, .oauth]
        ),
        "Google": ModelCapabilities(
            supportedModalities: .fullMultimodal,
            supportedAuth: [.apiKey, .oauth]
        ),
        "OpenAI": ModelCapabilities(
            supportedModalities: .vision,
            supportedAuth: [.apiKey, .oauth]
        ),
        "Antigravity": ModelCapabilities(
            supportedModalities: .fullMultimodal,
            supportedAuth: [.oauth]
        ),
        "OpenRouter": ModelCapabilities(
            supportedModalities: .vision,
            supportedAuth: [.apiKey, .oauth]
        ),
    ]

    /// Default capabilities when the provider is unknown.
    private static let defaultCapabilities = ModelCapabilities(
        supportedModalities: .textOnly,
        supportedAuth: [.apiKey]
    )

    /// Context window size in tokens.
    /// Uses the stored `contextWindow` (from models.dev / provider API) if available,
    /// otherwise falls back to a model-id heuristic.
    var contextWindowTokens: Int {
        if let ctx = contextWindow, ctx > 0 { return ctx }
        let lid = id.lowercased()
        // Anthropic Claude. Modern Claude (Opus/Sonnet 4.x & 5, Fable/Mythos 5)
        // ship a 1M context window; only Haiku and the legacy 3.x/2.x line are
        // 200K. The old "everything non-`-1m` is 200K" default wrongly capped
        // Sonnet 4.6 / Sonnet 5 / Opus 4.x / Fable 5 at 200K. models.dev still
        // overrides via the explicit `contextWindow` above.
        if lid.contains("claude") {
            if lid.contains("haiku") { return 200_000 }
            // Legacy generations: Claude 2.x and 3/3.5 are 200K (or less, but
            // 200K is a safe modern floor for the slider).
            if lid.contains("claude-2") || lid.contains("claude-3") { return 200_000 }
            // Everything else (opus/sonnet/fable/mythos 4.x & 5) is 1M.
            return 1_000_000
        }
        // Google Gemini — modern Gemini models all advertise 1M+ context
        if lid.contains("gemini") {
            if lid.contains("1.0") { return 32_000 }
            return 1_000_000
        }
        // OpenAI
        if lid.contains("gpt-3.5") { return 16_000 }
        if lid.contains("gpt-4o") || lid.contains("gpt-4-turbo") { return 128_000 }
        if lid.contains("gpt-5") { return 400_000 }
        if lid.contains("gpt-4") { return 8_000 }
        if lid.contains("o3") || lid.contains("o4") { return 200_000 }
        if lid.contains("codex") { return 200_000 }
        // DeepSeek
        if lid.contains("deepseek") { return 128_000 }
        // xAI Grok. [T-ios-grok-context-underestimate] Without this branch a
        // Grok id missing from the models.dev catalog fell through to the
        // 128K default, and ContextPolicy turned that into
        // compactThreshold = 128K - 20K = 108K — so a model with a 256K–2M
        // window auto-compacted every ~20-30 tool calls. Field report
        // 2026-08-13: `grok-4.6` (not yet in the catalog) compacted 6 times in
        // 47 minutes. Grok 3 is the only 131K generation; Grok 4 and later are
        // 256K at minimum, and the fast/4.20 lines advertise 2M. 256K is the
        // conservative floor for an unknown Grok 4+; models.dev still
        // overrides via the explicit `contextWindow` above whenever the id IS
        // in the catalog.
        if lid.contains("grok") {
            if lid.contains("grok-2") || lid.contains("grok-3") { return 131_072 }
            return 256_000
        }
        // Default: assume a modern long-context model rather than 64K so the
        // group context-limit slider doesn't collapse to a single Unlimited stop.
        return 128_000
    }

    var capabilities: ModelCapabilities {
        if let override = modalityOverride {
            return ModelCapabilities(
                supportedModalities: override,
                supportedAuth: (Self.knownCapabilities[provider] ?? Self.defaultCapabilities).supportedAuth
            )
        }
        return Self.knownCapabilities[provider] ?? Self.defaultCapabilities
    }

    /// Returns a concise system-prompt fragment describing modality limits,
    /// or `nil` when the model supports all modalities.
    var capabilityPromptFragment: String? {
        let modalities = capabilities.supportedModalities

        // Full multimodal — nothing to add.
        if modalities.contains(.fullMultimodal) { return nil }

        var can: [String] = []
        var cannot: [String] = []

        if modalities.contains(.imageInput) { can.append("images") } else { cannot.append("images") }
        if modalities.contains(.pdfInput)   { can.append("PDFs") }   else { cannot.append("PDFs") }
        if modalities.contains(.audioInput) { can.append("audio") }  else { cannot.append("audio") }
        if modalities.contains(.videoInput) { can.append("video") }  else { cannot.append("video") }

        // Nothing limited — shouldn't happen if fullMultimodal check above is correct,
        // but guard anyway.
        if cannot.isEmpty { return nil }

        var parts: [String] = []
        if !can.isEmpty {
            parts.append("You can natively process \(can.joined(separator: " and ")) in this conversation.")
        }
        parts.append("You cannot natively process \(cannot.joined(separator: " or ")).")
        parts.append("To handle unsupported media, use shell_execute to run ffmpeg or other CLI tools.")

        return parts.joined(separator: " ")
    }

    /// Returns a model-specific agent behavior prompt fragment,
    /// or `nil` when no special instructions are needed.
    ///
    /// Codex models are trained with strong autonomy/persistence instructions
    /// in the Codex CLI but default to a cautious "ask before acting" style
    /// when those instructions are absent. This fragment bridges that gap.
    var agentBehaviorPromptFragment: String? {
        let lid = id.lowercased()

        // Gemini models sometimes output tool call syntax as plain text instead of
        // using function calling. This prompt fragment prevents that.
        if lid.contains("gemini") {
            return """
                CRITICAL: Always invoke tools via function calling — never output tool calls, \
                parameters, or execution metadata as text in your response. If a call fails, retry via function calling.
                """
        }

        let codexIds = [
            "codex-mini",
            "gpt-5.3-codex",
            "gpt-5.2-codex",
            "gpt-5.1-codex",
            "gpt-5-codex",
        ]
        guard codexIds.contains(where: { id.hasPrefix($0) }) else { return nil }

        return """
            Autonomy and persistence:
            - Persist until the task is fully handled end-to-end. Do not stop at analysis or partial work; \
            carry through to completion unless the user explicitly pauses or redirects you.
            - Assume the user wants you to act. Unless they explicitly ask for a plan or are brainstorming, \
            go ahead and call tools to solve the problem. Do not output your proposed solution in a message \
            without actually executing it — that is a failure mode.
            - NEVER say "I will use tool X to do Y" and then stop without calling the tool. \
            Always pair stated intent with an actual tool call in the same response.
            - Do not ask for permission or confirmation before using tools. You have full access to the \
            shell, filesystem, and browser. Execute directly.
            - Never ask the user whether they want you to proceed with a tool call under any circumstances. \
            If a tool call is the appropriate next step, invoke it immediately without narrating your intent \
            or seeking approval first.
            - If you encounter a blocker, attempt to resolve it yourself before asking the user.
            """
    }
}

// MARK: - Session Model Binding

/// How a session resolves its model.
///
/// [T-provider-entry-composite-key] `directEntry` is DUAL-WRITTEN for downgrade
/// safety:
///   - `modelEntryId`: the value an OLDER build reads. To keep a downgraded old
///     build resolving the binding, this stays a value the old build can match
///     against `ModelEntry.id` — i.e. the random uuid. (Old builds had
///     id == uuid.)
///   - `compositeKey`: the new stable identity ("{instanceId}/{modelId}") that
///     the current build reads first. Cross-device consistent.
/// New build resolves via `compositeKey` when present, falling back to
/// `modelEntryId`. An old build only sees `modelEntryId` (uuid) and resolves
/// fine. group sources are NOT dual-written (group has fallback; downgrade
/// re-select accepted) — `resolvedEntryId` carries the composite key.
enum SessionModelSource: Codable, Hashable {
    case directEntry(modelEntryId: String, compositeKey: String? = nil)
    case group(groupId: String, resolvedEntryId: String)

    // Encoding format = Swift's auto-synthesized NESTED enum shape, so an OLD
    // build's auto-synthesized decoder reads it without changes (downgrade
    // safety). We just add an extra `compositeKey` key inside the directEntry
    // payload that the old decoder ignores and the new decoder prefers:
    //   {"directEntry":{"modelEntryId":"<uuid>","compositeKey":"<inst/model>"}}
    //   {"group":{"groupId":"...","resolvedEntryId":"..."}}
    private enum OuterKeys: String, CodingKey { case directEntry, group }
    private enum DirectKeys: String, CodingKey { case modelEntryId, compositeKey }
    private enum GroupKeys: String, CodingKey { case groupId, resolvedEntryId }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: OuterKeys.self)
        if let dc = try? c.nestedContainer(keyedBy: DirectKeys.self, forKey: .directEntry) {
            self = .directEntry(
                modelEntryId: try dc.decodeIfPresent(String.self, forKey: .modelEntryId) ?? "",
                compositeKey: try dc.decodeIfPresent(String.self, forKey: .compositeKey)
            )
        } else if let gc = try? c.nestedContainer(keyedBy: GroupKeys.self, forKey: .group) {
            self = .group(
                groupId: try gc.decode(String.self, forKey: .groupId),
                resolvedEntryId: try gc.decode(String.self, forKey: .resolvedEntryId)
            )
        } else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unrecognized SessionModelSource encoding"))
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: OuterKeys.self)
        switch self {
        case .directEntry(let modelEntryId, let compositeKey):
            var dc = c.nestedContainer(keyedBy: DirectKeys.self, forKey: .directEntry)
            try dc.encode(modelEntryId, forKey: .modelEntryId)
            try dc.encodeIfPresent(compositeKey, forKey: .compositeKey)
        case .group(let groupId, let resolvedEntryId):
            var gc = c.nestedContainer(keyedBy: GroupKeys.self, forKey: .group)
            try gc.encode(groupId, forKey: .groupId)
            try gc.encode(resolvedEntryId, forKey: .resolvedEntryId)
        }
    }

    /// The preferred reference for the CURRENT build: composite key if present,
    /// else the (possibly-uuid) modelEntryId. Group source returns its
    /// resolvedEntryId.
    var preferredReference: String {
        switch self {
        case .directEntry(let modelEntryId, let compositeKey):
            return compositeKey ?? modelEntryId
        case .group(_, let resolvedEntryId):
            return resolvedEntryId
        }
    }
}

/// Binding between a session and its model selection.
struct SessionModelBinding: Codable, Hashable {
    let sessionId: String
    var primarySource: SessionModelSource
    var subModelSource: SessionModelSource?
}

// MARK: - Session Inference Config

/// Thinking intensity level.
enum ThinkingLevel: String, Codable, Hashable, CaseIterable, Comparable {
    case off
    case low
    case medium
    case high
    case xhigh
    case max
    case ultra

    static func < (lhs: ThinkingLevel, rhs: ThinkingLevel) -> Bool {
        allCases.firstIndex(of: lhs)! < allCases.firstIndex(of: rhs)!
    }

    var displayName: String {
        switch self {
        case .off: return AppLocalized("Off")
        case .low: return AppLocalized("Low")
        case .medium: return AppLocalized("Med")
        case .high: return AppLocalized("High")
        case .xhigh: return AppLocalized("XHigh")
        case .max: return AppLocalized("Max")
        case .ultra: return AppLocalized("Ultra")
        }
    }

    var isEnabled: Bool { self != .off }

    static func decoded(_ raw: String) -> ThinkingLevel {
        ThinkingLevel(rawValue: raw) ?? .xhigh
    }
}

extension LLMModel {
    var catalogMaxThinkingLevel: ThinkingLevel {
        // A model that can't reason has max level .off regardless of any
        // catalog family rule — family rules match by id substring, so a
        // broadened rule (e.g. "mimo" covering mimo-v2.5) must not lift the
        // ceiling of that family's non-reasoning members (mimo-v2.5-tts/-asr).
        // [T-fallback-thinking-preclamp]
        if supportsReasoning == false { return .off }
        // [T-thinking-levels-data-driven] A declared effort set is a stronger
        // statement than any id-substring rule: it names the exact tiers the
        // backend accepts. Take its top tier as the ceiling so a model whose
        // declaration reaches beyond the hardcoded default (.xhigh) — e.g.
        // zhipuai glm-5.2 / deepseek-v4, both `["high","max"]` — is actually
        // reachable from the UI. Without this, "max" was declared by the
        // catalog, clamped to by the wire path, and yet unselectable.
        if let declaredTop = selectableThinkingLevels.last {
            return declaredTop
        }
        return ThinkingLevelCatalog.declaredMaxLevel(for: id) ?? .xhigh
    }

    /// [T-thinking-levels-data-driven] The thinking levels worth OFFERING for
    /// this model, derived from the catalog's declared effort tiers.
    ///
    /// The wire path (`clampEffort`) snaps any request onto the declared set,
    /// so when a model declares a sparse set — `["high","max"]` is the single
    /// most common sparse shape in the catalog (58 models, incl. zhipuai
    /// glm-5.2 and official deepseek-v4) — the five generic UI levels collapse
    /// onto one or two distinct wire values. The user then drags a slider that
    /// provably changes nothing: verified on-device 2026-08-01, glm-5.2 sent
    /// `reasoning_effort:"high"` for Low AND Med AND High AND XHigh.
    ///
    /// Returning one level per DISTINCT declared tier makes the picker honest:
    /// every option the user can pick produces a different request. Empty when
    /// nothing is declared, so the legacy id-rule ceiling still applies.
    ///
    /// `.off` is never included — it is a separate toggle, not an effort tier,
    /// and the OFF wire value is chosen by `explicitOffEffort`, not here.
    var selectableThinkingLevels: [ThinkingLevel] {
        guard let declared = reasoningEffortValues, !declared.isEmpty else { return [] }
        // Map each declared wire tier to the UI level that emits it. "none" and
        // "minimal" are OFF-ish tiers handled by the toggle, not the ladder.
        let mapping: [(wire: String, level: ThinkingLevel)] = [
            ("low", .low), ("medium", .medium), ("high", .high),
            ("xhigh", .xhigh), ("max", .max),
        ]
        let set = Set(declared.map { $0.lowercased() })
        return mapping.filter { set.contains($0.wire) }.map(\.level)
    }
}

/// Per-session inference settings (thinking toggle, etc.).
struct SessionInferenceConfig: Codable, Hashable {
    var thinkingLevel: ThinkingLevel = .off

    /// Convenience — true when thinking is enabled at any level.
    var thinkingEnabled: Bool { thinkingLevel.isEnabled }

    // Backward-compatible decode: handle both old `thinkingEnabled: Bool` and new `thinkingLevel` key.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let raw = try container.decodeIfPresent(String.self, forKey: .thinkingLevel) {
            thinkingLevel = ThinkingLevel.decoded(raw)
        } else if let enabled = try container.decodeIfPresent(Bool.self, forKey: .thinkingEnabled) {
            thinkingLevel = enabled ? .medium : .off
        } else {
            thinkingLevel = .off
        }
    }

    init() { thinkingLevel = .off }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(thinkingLevel, forKey: .thinkingLevel)
    }

    private enum CodingKeys: String, CodingKey {
        case thinkingLevel, thinkingEnabled
    }
}

// MARK: - LLM Messages

struct LLMMessage: Sendable {
    enum Role: String, Sendable {
        case user
        case assistant
    }

    /// An inline image attachment for multimodal messages.
    struct ImageAttachment: Sendable {
        let mimeType: String   // e.g. "image/png"
        let data: Data         // raw image bytes
    }

    /// An inline audio attachment for audio-input messages (GH#67).
    /// Kept as the base64 string from the caller — audio payloads can be
    /// megabytes, and every current consumer (OpenAI input_audio blocks)
    /// wants base64 back, so decode/re-encode would be pure churn.
    struct AudioAttachment: Sendable {
        let format: String     // e.g. "wav", "mp3" (OpenAI input_audio.format)
        let base64Data: String // base64-encoded audio bytes
    }

    let role: Role
    let content: String
    /// Optional image attachments (for vision / image-edit requests).
    var images: [ImageAttachment] = []
    /// Optional audio attachments (for audio-input models, GH#67).
    var audios: [AudioAttachment] = []
}

/// A media attachment in an LLM response (e.g. generated image, audio).
struct LLMMediaAttachment: Sendable {
    enum MediaType: String, Sendable {
        case image
        case audio
        case video
    }
    let type: MediaType
    /// MIME type (e.g. "image/png", "audio/wav")
    let mimeType: String
    /// Raw data (decoded from base64)
    let data: Data
}

struct LLMResponse: Sendable {
    let text: String
    let stopReason: String?
    let usage: LLMUsage?
    /// Media attachments (generated images, audio, etc.) — empty for text-only responses.
    var mediaAttachments: [LLMMediaAttachment] = []
}

enum LLMStreamChunk: Sendable {
    case started
    case text(String)
    case usage(LLMUsage)
    case finished(stopReason: String?)
}

struct LLMUsage: Sendable {
    let inputTokens: Int
    let outputTokens: Int
    let cacheCreationInputTokens: Int?
    let cacheReadInputTokens: Int?
}

// MARK: - Model ID → Display Name

/// Convert a model ID like "openai/gpt-4o-mini" into a human-friendly display name
/// while preserving well-known abbreviations (GPT, GLM, OSS, etc.).
func modelDisplayName(from id: String) -> String {
    // Abbreviations that should be fully uppercased
    let uppercaseAbbreviations: Set<String> = [
        "gpt", "glm", "oss", "ai", "xl", "xxl",
        "vl", "llm", "moe", "api", "hd", "sd",
        "rp", "sft", "rl", "dpo", "gguf", "fp16", "bf16", "int4", "int8",
    ]
    // Brand names with specific casing
    let brandCasing: [String: String] = [
        "openai": "OpenAI",
        "deepseek": "DeepSeek",
        "chatgpt": "ChatGPT",
        "llama": "Llama",
        "gemma": "Gemma",
        "phi": "Phi",
        "mistral": "Mistral",
        "mixtral": "Mixtral",
        "qwen": "Qwen",
        "yi": "Yi",
    ]

    return id
        .replacingOccurrences(of: "/", with: " ")
        .replacingOccurrences(of: "-", with: " ")
        .split(separator: " ")
        .map { token -> String in
            let lower = token.lowercased()
            if let brand = brandCasing[lower] {
                return brand
            }
            if uppercaseAbbreviations.contains(lower) {
                return lower.uppercased()
            }
            // Default: capitalize first letter
            return token.prefix(1).uppercased() + token.dropFirst()
        }
        .joined(separator: " ")
}
