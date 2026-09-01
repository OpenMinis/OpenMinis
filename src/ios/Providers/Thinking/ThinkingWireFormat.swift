import Foundation

/// How a given endpoint expects the thinking/reasoning control to appear on the wire.
///
/// Every case here is anchored to at least one shipped field report or vendor doc — see
/// `/tmp/thinking_rules_evidence.md` §A for the full provenance chain (17 rules mined
/// from git history, each with a file:line and commit hash). The comments below carry the
/// short form so the reasoning survives next to the code.
///
/// PHASE 1 SCOPE: this models the OpenAI-compatible family only (the ~10 vendors that
/// flow through `OpenAIAgentProvider`). Gemini and Anthropic have their own emitters
/// (`GeminiProvider`'s private thinkingConfig builders, `AnthropicAgentProvider.thinkingBudget`)
/// and are deliberately NOT routed through this type yet — their cases are declared so
/// the vocabulary is complete and the registry can name them, but nothing resolves to
/// them on this path. Wiring them up is Phase 2.
enum ThinkingWireFormat: Equatable {

    /// Send nothing at all. Not "send off" — send NO thinking key whatsoever.
    ///
    /// Mistral (OpenMinis#87): `AssistantMessage` is a closed schema and the request
    /// rejects `reasoning` with `422 extra_forbidden`. Venice (OpenMinis#86) is the same
    /// class at the request level: `additionalProperties:false` means an unknown ROOT key
    /// is rejected during schema validation, before model dispatch — which is why every
    /// model failed there and why turning thinking off did not help, since the disabled
    /// branch still emitted the key.
    case omitEverything

    /// Root-level `reasoning_effort: "<tier>"` (OpenAI Chat Completions shape).
    ///
    /// `offValue == nil` means OMIT the field when thinking is off, which is a real and
    /// load-bearing distinction: MiMo/Agnes validate against a strict low/medium/high
    /// enum and rejected the ENTIRE request when sent `"minimal"` — the turn produced no
    /// reply at all, strictly worse than the vendor default the explicit-off change was
    /// meant to prevent (c5efeb1e, verified on-device iPhone 11).
    case reasoningEffort(offValue: String?)

    /// Nested `reasoning: {effort: "<tier>"}` (OpenAI Responses / OpenRouter shape).
    ///
    /// OpenRouter omits the parameter entirely when off so forced-reasoning models
    /// (DeepSeek R1, Kimi K2.5) don't reject `effort:"none"` with "Reasoning is mandatory
    /// for this endpoint".
    case reasoningEffortNested(offValue: String?)

    /// DeepSeek V4's OpenAI-format shape: the switch and the tier are ROOT SIBLINGS —
    /// `{"thinking":{"type":"enabled"}, "reasoning_effort":"high"}`.
    ///
    /// The tier must NOT be nested inside the `thinking` object. Nesting it made it an
    /// unknown key with no root tier at all, so every V4 request silently ran at the
    /// vendor default for ~3 months (847822eb on iOS; the Android port was df776253).
    /// Thinking is ON by default on V4, so OFF must be sent explicitly as
    /// `{"type":"disabled"}` — and without a tier, since the off vocabulary is not part
    /// of the effort enum this endpoint validates.
    case deepSeekSibling

    /// Qwen/DashScope: `enable_thinking` + `thinking_budget` sent at BOTH the root and
    /// inside `extra_body` (DashScope reads extra_body; vLLM/SGLang accept top-level).
    ///
    /// The budget must be STRICTLY below `max_completion_tokens` — equal values are
    /// rejected too ("[16384] must be greater than [16384]", issues #35/#641) — and the
    /// ceiling varies per model, so it is computed relative to maxTokens rather than
    /// against a fixed threshold (a5a0de20).
    case qwenDual

    /// [T-ios-qwen-extra-body-400] Bare root-level `enable_thinking` — no `extra_body`
    /// envelope and no `thinking_budget`. The minimal shape for a qwen-named model served
    /// by something that is not DashScope.
    ///
    /// Both omissions were forced by measurement against a real relay
    /// (tokenrhythm.studio, qwen3.8-max / qwen3.7-max), probing one field at a time:
    ///
    ///   | body                                  | result |
    ///   |---------------------------------------|--------|
    ///   | baseline (no thinking fields)         | 200    |
    ///   | + `enable_thinking`                   | 200    |
    ///   | + `enable_thinking`, `thinking_budget`| 400    |
    ///
    /// So `enable_thinking` is portable but `thinking_budget` is NOT: the gateway answers
    /// `400 UNKNOWN_FIELD` and the whole request fails rather than degrading. `extra_body`
    /// fails the same way. Both fields stay in `qwenDual` because DashScope's documented
    /// contract does use them — this case exists precisely so that contract is not
    /// imposed on endpoints that never agreed to it.
    ///
    /// The `*qwen*` built-in rule matches on the MODEL NAME alone, which is why a relay
    /// ever received the DashScope shape to begin with.
    case qwenRootOnly

    // MARK: - Declared but not yet routed here (Phase 2)

    /// Anthropic `thinking:{type:…, budget_tokens:N}`. Claude 4.6+ uses *adaptive*
    /// thinking and ignores the older enabled+budget form, so the generation matters —
    /// which is why this carries a style rather than a single shape.
    case anthropicThinking(style: AnthropicThinkingStyle)

    /// Gemini `generationConfig.thinkingConfig.thinkingBudget` numeric budget.
    /// `floor` exists because `thinkingBudget: 0` is invalid on models that require
    /// thinking (2.5 Pro rejects it with 400 INVALID_ARGUMENT); df8a823d had to add a
    /// 128 floor for unrecognized ids. models.dev publishes this as `min` per model, so
    /// Phase 2 can drive it from data instead of a constant.
    case geminiBudget(floor: Int, canDisable: Bool)

    /// Gemini 3.x `thinkingLevel` string rather than a numeric budget.
    case geminiThinkingLevel

    /// Plain root boolean switch with no tiers, e.g. `{"thinking": true}`.
    /// Corresponds to models.dev `reasoning_options.type == "toggle"`.
    case booleanToggle(path: String)

    /// Nested boolean under `extra_body`, e.g. `extra_body.thinking.enabled`.
    /// DeepSeek's official endpoint reasons by default and its real switch lives here;
    /// Minis never sent it, which is why the official endpoint always ran its default
    /// configuration (OpenMinis#171).
    case extraBodyToggle(path: String)

    /// ESCAPE HATCH — reserved, deliberately inert in Phase 1.
    ///
    /// Design §5.1 argues for this: the Venice class of failure is "an endpoint shape we
    /// did not anticipate", and the shipped Venice guard admits in its own comment that a
    /// relay on a vanity domain is still exposed. A user-editable rule turns "wait for a
    /// release" into "fix it yourself in 30 seconds". It is intentionally limited to a
    /// dotted path plus per-tier values — NOT JSONPath or a template engine — so every
    /// rule stays statically checkable and explainable in a trace.
    ///
    /// Declared now so Phase 2 can add persistence and UI without touching this enum
    /// (which would otherwise be a breaking change to every switch over it). Nothing
    /// constructs this case yet, and the resolver treats it as "no opinion".
    case customPath(path: String, values: [ThinkingLevel: String], offValue: String?)
}

/// Anthropic's thinking control changed shape across model generations.
enum AnthropicThinkingStyle: Equatable {
    /// Claude 4.6+ — `thinking:{type:"adaptive"}`; the older budget form is ignored.
    case adaptive
    /// Pre-4.6 — `thinking:{type:"enabled", budget_tokens:N}`.
    case budgetTokens
}
