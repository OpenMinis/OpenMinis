package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel

/**
 * How a given endpoint expects the thinking/reasoning control to appear on the wire.
 *
 * Every entry is anchored to a shipped field report or vendor doc (17 rules mined
 * from past regressions, each traced to the commit that fixed it). The short form is
 * kept here so the reasoning survives next to the code.
 *
 * Mirrors iOS `ThinkingWireFormat.swift` case for case; the two must stay in step, since
 * the whole point of the rule registry is that a vendor contract is described ONCE.
 *
 * PHASE 1 SCOPE: models the OpenAI-compatible family only (everything flowing through
 * [com.openminis.app.provider.openai.OpenAIProvider]). Gemini and Anthropic have their
 * own emitters and are deliberately NOT routed here yet — their formats are declared so
 * the vocabulary is complete, but nothing resolves to them on this path. Phase 2 wires
 * them up.
 */
sealed interface ThinkingWireFormat {

    /**
     * Send nothing at all. Not "send off" — send NO thinking key whatsoever.
     *
     * Mistral (GH OpenMinis#87): `AssistantMessage` is a closed schema and the request
     * rejects `reasoning` with `422 extra_forbidden`. Venice (GH OpenMinis#86) is the
     * same class at the request level: `additionalProperties:false` means an unknown ROOT
     * key is rejected during schema validation, before model dispatch — which is why
     * every model failed there and why turning thinking off did not help, since the
     * disabled branch still emitted the key.
     */
    data object OmitEverything : ThinkingWireFormat

    /**
     * Root-level `reasoning_effort: "<tier>"` (OpenAI Chat Completions shape).
     *
     * [offValue] `null` means OMIT when thinking is off — a load-bearing distinction:
     * MiMo/Agnes validate against a strict low/medium/high enum and rejected the ENTIRE
     * request when sent `"minimal"`, producing no reply at all, strictly worse than the
     * vendor default the explicit-off change was meant to prevent (iOS c5efeb1e).
     */
    data class ReasoningEffort(val offValue: String?) : ThinkingWireFormat

    /**
     * Nested `reasoning: {effort: "<tier>"}` (OpenAI Responses / OpenRouter shape).
     * OpenRouter omits entirely when off so forced-reasoning models don't reject
     * `effort:"none"` with "Reasoning is mandatory for this endpoint".
     */
    data class ReasoningEffortNested(val offValue: String?) : ThinkingWireFormat

    /**
     * DeepSeek V4's OpenAI-format shape: switch and tier are ROOT SIBLINGS —
     * `{"thinking":{"type":"enabled"}, "reasoning_effort":"high"}`.
     *
     * The tier must NOT be nested inside `thinking`. Nesting made it an unknown key with
     * no root tier at all, so every V4 request silently ran at the vendor default —
     * ~3 months on iOS (847822eb) and longer on Android until the port (df776253).
     * Thinking is ON by default on V4, so OFF must be explicit `{"type":"disabled"}`,
     * without a tier (the off vocabulary isn't in the enum this endpoint validates).
     */
    data object DeepSeekSibling : ThinkingWireFormat

    /**
     * Qwen/DashScope: `enable_thinking` + `thinking_budget` at BOTH the root and inside
     * `extra_body` (DashScope reads extra_body; vLLM/SGLang accept top-level).
     *
     * The budget must be STRICTLY below `max_completion_tokens` — equal values are
     * rejected too ("[16384] must be greater than [16384]", issues #35/#641) — and the
     * ceiling varies per model, so it is computed relative to maxTokens (iOS a5a0de20).
     */
    data object QwenDual : ThinkingWireFormat

    // ---- Declared but not yet routed here (Phase 2) ----

    /**
     * Anthropic `thinking:{type:…, budget_tokens:N}`. Claude 4.6+ uses adaptive thinking
     * and ignores the older enabled+budget form, so the generation matters.
     */
    data class AnthropicThinking(val style: AnthropicThinkingStyle) : ThinkingWireFormat

    /**
     * Gemini `generationConfig.thinkingConfig.thinkingBudget`. [floor] exists because
     * `thinkingBudget: 0` is invalid on models that require thinking (2.5 Pro returns
     * 400 INVALID_ARGUMENT); df8a823d added a 128 floor for unrecognized ids. models.dev
     * publishes this as `min` per model, so Phase 2 can drive it from data.
     */
    data class GeminiBudget(val floor: Int, val canDisable: Boolean) : ThinkingWireFormat

    /** Gemini 3.x `thinkingLevel` string rather than a numeric budget. */
    data object GeminiThinkingLevel : ThinkingWireFormat

    /** Plain root boolean switch with no tiers. models.dev `reasoning_options` "toggle". */
    data class BooleanToggle(val path: String) : ThinkingWireFormat

    /**
     * Nested boolean under `extra_body`, e.g. `extra_body.thinking.enabled`. DeepSeek's
     * official endpoint reasons by default and its real switch lives here; Minis never
     * sent it, so the official endpoint always ran its default config (GH OpenMinis#171).
     */
    data class ExtraBodyToggle(val path: String) : ThinkingWireFormat

    /**
     * ESCAPE HATCH — reserved, deliberately inert in Phase 1.
     *
     * Design §5.1: the Venice class of failure is "an endpoint shape we did not
     * anticipate", and the shipped Venice guard admits in its own comment that a relay on
     * a vanity domain is still exposed. A user-editable rule turns "wait for a release"
     * into "fix it yourself in 30 seconds". Deliberately limited to a dotted path plus
     * per-tier values — NOT JSONPath or a template engine — so every rule stays
     * statically checkable and explainable in a trace.
     *
     * Declared now so Phase 2 can add persistence and UI without changing this type.
     * Nothing constructs it yet and the resolver treats it as "no opinion".
     */
    data class CustomPath(
        val path: String,
        val values: Map<ThinkingLevel, String>,
        val offValue: String?,
    ) : ThinkingWireFormat
}

/** Anthropic's thinking control changed shape across model generations. */
enum class AnthropicThinkingStyle {
    /** Claude 4.6+ — `thinking:{type:"adaptive"}`; the older budget form is ignored. */
    ADAPTIVE,

    /** Pre-4.6 — `thinking:{type:"enabled", budget_tokens:N}`. */
    BUDGET_TOKENS,
}
