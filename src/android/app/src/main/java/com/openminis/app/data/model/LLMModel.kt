package com.openminis.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LLMModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsReasoning: Boolean? = null,
    val interleavedReasoningField: String? = null,
    // [T-reasoning-effort-data-driven] Effort tiers this model accepts, from the
    // models.dev `reasoning_options` entry of type `effort` (e.g. ["high","max"]
    // for zhipuai glm-5.2). Mirrors iOS LLMModel.reasoningEffortValues.
    //
    // Presence (non-null, non-empty) means "controlled by reasoning_effort" and
    // replaces the old hardcoded deepseek/glm/kimi/minimax skip list; the
    // contents are the ALLOWED tiers, which the request builder clamps onto
    // (the catalog's sets vary: ["low","medium","high"], ["high","max"], …).
    val reasoningEffortValues: List<String>? = null,
    // [OpenMinis#163] The catalog affirmatively declares NO effort tiers for
    // this model — it reasons, but takes no `reasoning_effort` parameter.
    // Mirrors iOS LLMModel.declaresNoEffortTiers.
    //
    // Distinct from `reasoningEffortValues == null`, which also covers "the
    // catalog has never heard of this model". Only the affirmative case may
    // suppress the field; the unknown case stays permissive so third-party
    // relays keep working.
    //
    // Nullable (not a plain Boolean) so decoding a model persisted before this
    // field existed yields null — "unknown", the pre-existing behaviour —
    // rather than a synthesized `false` that would read as a real answer.
    val declaresNoEffortTiers: Boolean? = null,
    // Input/output modalities from models.dev (e.g. "text", "image", "audio", "video", "pdf").
    // Mirrors iOS ModelModality flags. When null, treat as text-in/text-out only.
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
) {
    companion object {
        // Anthropic — mirrors iOS LLMTypes.swift allAnthropic.
        // [T-android-claude-opus48-thinking-toggle] (Sow Sow 38845/38850) Every
        // Claude 4.x model supports extended thinking, so hard-stamp
        // supportsReasoning = true (same as the OpenAI gpt-5.x catalog). Without
        // it the built-in fallback list — used when the /v1/models fetch fails
        // on a direct-Anthropic instance — lands with supportsReasoning=null and
        // ChatViewModel's `== true` gate hides the Deep Thinking toggle (the
        // reported Opus 4.8 bug). The dynamic /v1/models path stamps it the same
        // way via AnthropicProvider.supportsThinking.
        // [T-anthropic-fable5-catalog-android] Claude Fable 5 (2026-06-09,
        // first GA Mythos-class model; API id has no dated variant). Context
        // window / max output deliberately unset — 1M ctx is third-party
        // reported, not confirmed on Anthropic's model page; models.dev /
        // dynamic lookup fills them in once catalogued, same as the sibling
        // entries. 5-series adaptive thinking + no-temperature handling
        // comes from parseClaudeVersion (243dadf3). Claude Mythos 5 has no
        // public API id (Project Glasswing) and is intentionally absent.
        // [T-anthropic-context-window] Explicit context/output caps per
        // Anthropic's catalog (mirrors iOS): modern Opus/Sonnet 4.x & 5 and
        // Fable 5 are 1M context; Haiku 4.5 is 200K. Output: 128K (Opus/Fable),
        // 64K (Sonnet/Haiku). Set explicitly so the values don't depend on the
        // id heuristic; models.dev enrich can still override at runtime.
        val claudeFable5 = LLMModel("claude-fable-5", "Claude Fable 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus48 = LLMModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus46 = LLMModel("claude-opus-4-6", "Claude Opus 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        // [T-anthropic-sonnet5-catalog-android] Claude Sonnet 5 — same 5-series
        // adaptive-thinking + no-temperature handling (parseClaudeVersion) and
        // identical modalities/capabilities as the Sonnet 4.6 entry below.
        val claudeSonnet5 = LLMModel("claude-sonnet-5", "Claude Sonnet 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeSonnet46 = LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeHaiku45 = LLMModel("claude-haiku-4-5", "Claude Haiku 4.5", "Anthropic", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true)

        val allAnthropic = listOf(claudeFable5, claudeOpus48, claudeOpus46, claudeSonnet5, claudeSonnet46, claudeHaiku45)

        // Gemini
        val gemini3Pro = LLMModel("gemini-3-pro-preview", "Gemini 3 Pro (Preview)", "Google")
        val gemini3Flash = LLMModel("gemini-3-flash-preview", "Gemini 3 Flash (Preview)", "Google")
        val gemini25Pro = LLMModel("gemini-2.5-pro", "Gemini 2.5 Pro", "Google")
        val gemini25Flash = LLMModel("gemini-2.5-flash", "Gemini 2.5 Flash", "Google")
        val gemini25FlashLite = LLMModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Google")

        val allGemini = listOf(gemini3Pro, gemini3Flash, gemini25Pro, gemini25Flash, gemini25FlashLite)

        // OpenAI — GPT-5.x and o-series ALWAYS support reasoning
        // (`reasoning_effort` field is required on Codex-OAuth and respected
        // by /v1/responses for these models). Without the explicit
        // `supportsReasoning = true` here the catalog falls back to `null`,
        // ChatViewModel.currentModelSupportsReasoning resolves to `false`,
        // and the Thinking pill in the composer is disabled — the user
        // can't pick high/medium/low even though the provider plumbing
        // honours it. Mirrors iOS LLMTypes.swift defaults plus the
        // OpenAIAgentProvider `supportsReasoning ?? true` GPT-5.x
        // assumption (T119).
        val gpt55 = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true)
        val gpt53Codex = LLMModel("gpt-5.3-codex", "GPT-5.3 Codex", "OpenAI", supportsReasoning = true)
        val gpt52Codex = LLMModel("gpt-5.2-codex", "GPT-5.2 Codex", "OpenAI", supportsReasoning = true)
        val gpt51CodexMax = LLMModel("gpt-5.1-codex-max", "GPT-5.1 Codex Max", "OpenAI", supportsReasoning = true)
        val gpt52 = LLMModel("gpt-5.2", "GPT-5.2", "OpenAI", supportsReasoning = true)
        val gpt4o = LLMModel("gpt-4o", "GPT-4o", "OpenAI")
        val gpt4oMini = LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI")
        val o3 = LLMModel("o3", "o3", "OpenAI", supportsReasoning = true)
        val o4Mini = LLMModel("o4-mini", "o4 Mini", "OpenAI", supportsReasoning = true)
        val codexMini = LLMModel("codex-mini-latest", "Codex Mini", "OpenAI", supportsReasoning = true)

        val allOpenAI = listOf(gpt55, gpt53Codex, gpt52Codex, gpt51CodexMax, gpt52, gpt4o, gpt4oMini, o3, o4Mini, codexMini)

        // OpenRouter (matching iOS built-in set)
        val orClaudeSonnet4 = LLMModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", "OpenRouter")
        val orGemini25Flash = LLMModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", "OpenRouter")
        val orGpt4o = LLMModel("openai/gpt-4o", "GPT-4o", "OpenRouter")
        val orLlamaMaverick = LLMModel("meta-llama/llama-4-maverick", "Llama 4 Maverick", "OpenRouter")

        val allOpenRouter = listOf(orClaudeSonnet4, orGemini25Flash, orGpt4o, orLlamaMaverick)

        // xAI (Grok) — OAuth-only path uses these as the built-in catalog.
        // Source of truth = XAIModelsAPI; this list is what surfaces in the
        // Add Provider → Models step before any models-cache call.
        //
        // Catalog ordering = default-pick order. grok-4.3 stays the
        // flagship on top. T-xai-models-refresh dropped grok-3-* slugs
        // (xAI server-side now redirects those to grok-4.3, so showing
        // them in the picker is just noise) and added the multi-agent
        // / build / fast / code-fast variants surfaced by xAI docs and
        // OpenClaw's catalog (port iOS db973552).
        // Official xAI catalog (docs.x.ai/docs/models) - synced from CLIProxyAPI models.json
        // [T-provider-dynamic-catalog-reconcile] grok-4.6 added for GH#265.
        // Note this list is now a SEED/FALLBACK, not the whole story: since
        // that fix refreshModels does a real GET /v1/models against api.x.ai,
        // so a model released after this build still shows up. Keeping the
        // list current only improves the pre-network first paint.
        val grok46 = LLMModel("grok-4.6", "Grok 4.6", "xAI", supportsReasoning = true)
        val grok45 = LLMModel("grok-4.5", "Grok 4.5", "xAI", supportsReasoning = true)
        val grok43 = LLMModel("grok-4.3", "Grok 4.3", "xAI", supportsReasoning = true)
        val grok420Reasoning = LLMModel("grok-4.20-0309-reasoning", "Grok 4.20 Reasoning", "xAI", supportsReasoning = true)
        val grok420NonReasoning = LLMModel("grok-4.20-0309-non-reasoning", "Grok 4.20", "xAI")
        val grok420MultiAgent = LLMModel("grok-4.20-multi-agent-0309", "Grok 4.20 Multi-Agent", "xAI", supportsReasoning = true)
        val grokBuild01 = LLMModel("grok-build-0.1", "Grok Build 0.1", "xAI")
        val grok3Mini = LLMModel("grok-3-mini", "Grok 3 Mini", "xAI", supportsReasoning = true)
        val grok3MiniFast = LLMModel("grok-3-mini-fast", "Grok 3 Mini Fast", "xAI", supportsReasoning = true)
        val grokComposer25Fast = LLMModel("grok-composer-2.5-fast", "Grok Composer 2.5 Fast", "xAI")
        // High-frequency fast / code variants surfaced by OpenClaw's catalog.
        val grok4Fast = LLMModel("grok-4-fast", "Grok 4 Fast", "xAI", supportsReasoning = true)
        val grok4FastNonReasoning = LLMModel("grok-4-fast-non-reasoning", "Grok 4 Fast (Non-Reasoning)", "xAI")
        val grokCodeFast1 = LLMModel("grok-code-fast-1", "Grok Code Fast 1", "xAI", supportsReasoning = true)

        val allXAI = listOf(
            grok46,
            grok45,
            grok43,
            grok420Reasoning,
            grok420NonReasoning,
            grok420MultiAgent,
            grokBuild01,
            grok3Mini,
            grok3MiniFast,
            grokComposer25Fast,
            grok4Fast,
            grok4FastNonReasoning,
            grokCodeFast1,
        )

        // [T-kimi-oauth] Kimi Code (Coding Plan) built-in fallback — deliberately
        // minimal and non-speculative (iOS parity): only confirmed current-gen
        // models. kimi-k3 verified present as the first entry of a live
        // GET /coding/v1/models fetch (2026-07-23). The real catalog replaces
        // this via refreshModels after login — the upstream lineup shifts
        // (K2 → K3 → …), so we never hand-author a "complete" list.
        val kimiK3 = LLMModel("kimi-k3", "Kimi K3", "Kimi")
        val kimiK2 = LLMModel("kimi-k2", "Kimi K2", "Kimi")

        val allKimi = listOf(kimiK3, kimiK2)

        val allModels = allAnthropic + allGemini + allOpenAI + allOpenRouter + allXAI + allKimi

        /**
         * Heuristic display-name formatter for API model ids.
         * Mirrors iOS `modelDisplayName(from:)`: splits on `/` and `-`, preserves a
         * small set of uppercase acronyms, applies brand-name capitalization for
         * well-known vendors (OpenAI, DeepSeek, etc.), and title-cases the rest.
         */
        fun modelDisplayName(fromId: String): String {
            if (fromId.isBlank()) return fromId
            val upperTokens = setOf(
                "gpt", "glm", "oss", "ai", "xl", "vl", "llm", "moe", "api",
                "hd", "sd", "rp", "sft", "rl", "dpo", "gguf", "fp16", "bf16", "int4", "int8",
            )
            val brandRewrites = mapOf(
                "openai" to "OpenAI",
                "deepseek" to "DeepSeek",
                "chatgpt" to "ChatGPT",
                "llama" to "Llama",
                "gemma" to "Gemma",
                "phi" to "Phi",
                "mistral" to "Mistral",
                "mixtral" to "Mixtral",
                "qwen" to "Qwen",
                "yi" to "Yi",
            )
            return fromId.split('/').joinToString(" / ") { segment ->
                segment.split('-').joinToString("-") { token ->
                    val lower = token.lowercase()
                    when {
                        brandRewrites.containsKey(lower) -> brandRewrites[lower]!!
                        upperTokens.contains(lower) -> lower.uppercase()
                        token.isEmpty() -> token
                        else -> token.replaceFirstChar { it.titlecase() }
                    }
                }
            }
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] True when this model can
     * produce a TEXT reply — the only kind a fresh chat should default to.
     * Per the field's documented convention (outputModalities null ⇒ "text
     * out only"), a null/empty list counts as text. A non-empty list must
     * contain "text" (normalized) to qualify — this excludes pure
     * image/audio/video generators (e.g. an image-only model whose
     * outputModalities is ["image"]). Mirrors iOS #636 isTextOutput.
     */
    val isTextOutput: Boolean
        get() {
            val out = outputModalities.normalizeModalities() ?: return true
            return "text" in out
        }

    /**
     * Effective context window in tokens. When `contextWindow` is set (from
     * models.dev enrichment or the built-in catalog) use it; otherwise fall
     * back to a model-id heuristic. Mirrors iOS LLMModel.contextWindowTokens
     * (T-anthropic-context-window): the old "Claude → 200K" default wrongly
     * capped Sonnet 4.6 / Sonnet 5 / Opus 4.x / Fable 5, whose real window is
     * 1M — only Haiku and the legacy 2.x/3.x line are 200K.
     */
    val contextWindowTokens: Int
        get() {
            contextWindow?.let { if (it > 0) return it }
            val lid = id.lowercase()
            // Anthropic Claude — modern Opus/Sonnet 4.x & 5 and Fable/Mythos 5
            // ship 1M; Haiku and legacy 2.x/3.x are 200K.
            if (lid.contains("claude")) {
                if (lid.contains("haiku")) return 200_000
                if (lid.contains("claude-2") || lid.contains("claude-3")) return 200_000
                return 1_000_000
            }
            // Google Gemini — modern Gemini advertises 1M+; only 1.0 was 32K.
            if (lid.contains("gemini")) {
                if (lid.contains("1.0")) return 32_000
                return 1_000_000
            }
            // OpenAI family
            if (lid.contains("gpt-3.5")) return 16_000
            if (lid.contains("gpt-4o") || lid.contains("gpt-4-turbo")) return 128_000
            if (lid.contains("gpt-5")) return 400_000
            if (lid.contains("gpt-4")) return 8_000
            if (lid.contains("o3") || lid.contains("o4")) return 200_000
            if (lid.contains("codex")) return 200_000
            if (lid.contains("deepseek")) return 128_000
            // xAI Grok. [T-android-grok-context-underestimate] Without this
            // branch a Grok id missing from the models.dev catalog fell through
            // to the 128K default, and ContextPolicy turned that into
            // compactThreshold = 128K - 20K = 108K — so a model with a 256K-2M
            // window auto-compacted every ~20-30 tool calls. iOS field report
            // 2026-08-13: `grok-4.6` (still absent from the bundled catalog,
            // verified) compacted 6 times in 47 minutes.
            //
            // Grok 2/3 are the only 131K generation; Grok 4 and later are 256K
            // at minimum and the fast / 4.20 lines advertise 2M. 256K is the
            // conservative floor for an unknown Grok 4+. A handful of
            // relay-hosted grok-4 entries do declare 128K-200K, but every one
            // of them IS in the catalog, so the explicit `contextWindow` check
            // above wins and this heuristic never runs for them — it only ever
            // sees ids models.dev has not shipped yet, which is the whole
            // failure mode. Port of iOS d63e9b9c9.
            if (lid.contains("grok")) {
                if (lid.contains("grok-2") || lid.contains("grok-3")) return 131_072
                return 256_000
            }
            // Default: assume a modern long-context model rather than 64K so the
            // group context-limit slider doesn't collapse to a single stop.
            return 128_000
        }

    /**
     * Capability hint appended to the system prompt so the model knows exactly
     * what it can natively consume vs what it must route through shell tools.
     * Returns `null` for fully-multimodal models (no hint needed). Matches the
     * iOS `capabilityPromptFragment` wording so Android/iOS chats are identical
     * when routed through the same model.
     */
    fun capabilityPromptFragment(): String? {
        // [T-android-vision-native-check-misses-image_input] normalizeModalities,
        // not a bare lowercase: OpenAI / OpenRouter spell these "image_input" /
        // "audio_input", models.dev spells them bare. Lowercasing alone left the
        // suffix intact, so a vision model from an OpenAI-shaped catalog was told
        // in its own system prompt that it could NOT see images.
        val inputs = inputModalities.normalizeModalities() ?: emptyList()
        val hasImage = "image" in inputs
        val hasPdf = "pdf" in inputs
        val hasAudio = "audio" in inputs
        val hasVideo = "video" in inputs
        if (hasImage && hasPdf && hasAudio && hasVideo) return null

        val natives = buildList {
            if (hasImage) add("images")
            if (hasPdf) add("PDFs")
            if (hasAudio) add("audio")
            if (hasVideo) add("video")
        }
        val missing = buildList {
            if (!hasImage) add("images")
            if (!hasPdf) add("PDFs")
            if (!hasAudio) add("audio")
            if (!hasVideo) add("video")
        }

        val sb = StringBuilder()
        if (natives.isNotEmpty()) {
            sb.append("You can natively process ").append(natives.joinToString(", ")).append(". ")
        }
        if (missing.isNotEmpty()) {
            sb.append("You cannot natively process ").append(missing.joinToString(", "))
            sb.append(" — for those formats, call shell_execute with ffmpeg or similar tools to extract text/metadata first.")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * Per-model-family agent-loop behavior hint. Gemini needs a reminder to
     * actually invoke tools via function calling; OpenAI Codex family needs a
     * push toward autonomous persistence ("don't stop at analysis").
     */
    fun agentBehaviorPromptFragment(): String? {
        val idLower = id.lowercase()
        val providerLower = provider.lowercase()

        if (providerLower == "google" || idLower.contains("gemini")) {
            return "When you need to use a tool, invoke it via the function-calling mechanism directly. Do not emit tool invocations as plain text — they will not be executed."
        }

        val isCodex = idLower.contains("codex") ||
            Regex("gpt-5(?:\\.\\d+)?-codex").containsMatchIn(idLower)
        if (isCodex) {
            return "Act autonomously: don't stop at analysis, don't ask for permission on reversible local actions, and never announce \"I will use tool X\" without actually calling X. Keep iterating until the task is fully complete."
        }
        return null
    }
}

/**
 * Normalize a modality string to its bare form. Provider APIs vary —
 * OpenAI / OpenRouter return "image_input" / "text_output" with _input/_output
 * suffixes; models.dev returns bare "image" / "text". Internally we always
 * use the bare form ("image", "pdf", "audio", "video", "text") so toggles,
 * `"image" in modalities` checks, and capability fragments work uniformly.
 */
fun String.normalizeModalityName(): String =
    // [T-android-modality-normalize-case-order] Lowercase FIRST, then strip.
    // The reverse order silently failed on any non-lowercase spelling:
    // removeSuffix("_input") matches literally, so "IMAGE_INPUT" kept its
    // suffix and normalized to "image_input", which then compared unequal to
    // "image" everywhere — hasImageInput / hasAudioInput / hasAudioOutput and
    // the Vision Group member filter all read such a model as lacking the
    // modality it actually declares.
    lowercase().removeSuffix("_input").removeSuffix("_output")

fun List<String>?.normalizeModalities(): List<String>? =
    this?.map { it.normalizeModalityName() }?.distinct()?.takeIf { it.isNotEmpty() }
