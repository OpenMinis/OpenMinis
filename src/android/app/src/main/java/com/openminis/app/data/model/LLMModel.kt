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
    // [T-openrouter-voice-catalog] OpenMinis#280. Which OpenRouter catalog
    // endpoint this model came from: "tts" (?output_modalities=speech), "stt"
    // (?output_modalities=transcription) or "none" (the default chat
    // catalog). null = not catalog-sourced (custom entries, other providers,
    // models saved before this field) — those keep the modality-based voice
    // rules. See VoiceRole. Nullable so older persisted models decode as null.
    // Mirrors iOS LLMModel.voiceRole.
    val voiceRole: String? = null,
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
        // [T-anthropic-fable51-android] Fable 5.1 (2026-09-01). Same specs as
        // Fable 5 — 1M context, 128K output, 5-series adaptive thinking and
        // no-temperature handling, all derived from the id by
        // parseClaudeVersion (which reads "claude-fable-5-1" as major=5,
        // minor=1).
        //
        // Adaptive-thinking ONLY: deliberately no manual thinking budget is
        // modelled. Declaring a min/max budget makes the server read the model
        // as hybrid-thinking and reject the request upstream. LLMModel has no
        // budget fields today, so this holds by construction; the note is here
        // so a future refactor that adds them does not quietly extend them to
        // this model.
        //
        // Reaching it also needs the claude-cli User-Agent at >= 2.1.251 (the
        // pinned value is now 2.1.280, which Opus 5.5 requires) — see
        // AnthropicProvider's fingerprint block.
        val claudeFable51 = LLMModel("claude-fable-5-1", "Claude Fable 5.1", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeFable5 = LLMModel("claude-fable-5", "Claude Fable 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        // [T-anthropic-opus55-catalog] Claude Opus 5.5 — 5-series, so
        // parseClaudeVersion already routes it to adaptive thinking and away
        // from both `temperature` and the literal `thinking.type="disabled"`
        // (which this model 400s on). Requires claude-cli >= 2.1.280 in the
        // mimicry User-Agent; below that the backend refuses the model in a
        // way that reads like a catalog error rather than a version gate.
        val claudeOpus55 = LLMModel("claude-opus-5-5", "Claude Opus 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus48 = LLMModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus46 = LLMModel("claude-opus-4-6", "Claude Opus 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        // [T-anthropic-sonnet55] Claude Sonnet 5.5 (CLIProxyAPI registry
        // 9a3b869f, iOS 965ff194d): 1M context, 128K output (not Sonnet 5's
        // 64K), text + image in via the Anthropic provider default, thinking
        // low…max. parseClaudeVersion reads (5,5), so it takes the same Claude-5
        // wire path as Sonnet 5: no `temperature`, adaptive thinking, and
        // "thinking off" = no thinking field. Served under the unchanged
        // claude-cli/2.1.280 fingerprint, the same one upstream uses for it.
        val claudeSonnet55 = LLMModel("claude-sonnet-5-5", "Claude Sonnet 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        // [T-anthropic-sonnet5-catalog-android] Claude Sonnet 5 — same 5-series
        // adaptive-thinking + no-temperature handling (parseClaudeVersion) and
        // identical modalities/capabilities as the Sonnet 4.6 entry below.
        val claudeSonnet5 = LLMModel("claude-sonnet-5", "Claude Sonnet 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeSonnet46 = LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeHaiku45 = LLMModel("claude-haiku-4-5", "Claude Haiku 4.5", "Anthropic", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true)

        // Newest first, matching iOS's ordering (LLMTypes.swift `allAnthropic`).
        val allAnthropic = listOf(claudeFable51, claudeFable5, claudeOpus55, claudeOpus48, claudeOpus46, claudeSonnet55, claudeSonnet5, claudeSonnet46, claudeHaiku45)

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
        // / build / fast / code-fast variants listed in the xAI docs
        // (docs.x.ai/docs/models; port iOS db973552).
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
        // High-frequency fast / code variants (docs.x.ai/docs/models).
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
            // [T-android-modeldisplayname-ios-parity] Kept byte-identical to
            // iOS `modelDisplayName(from:)` (LLMTypes.swift) — "xxl" was the
            // one entry Android was missing.
            val upperTokens = setOf(
                "gpt", "glm", "oss", "ai", "xl", "xxl", "vl", "llm", "moe", "api",
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
            // [T-android-modeldisplayname-ios-parity] Reflow BOTH separators
            // into spaces, exactly as iOS does.
            //
            // This previously split on '-' and joined back with '-', so the
            // function only re-cased tokens and left the machine-readable
            // separators in place. The same DeepSeek model therefore read
            // "DeepSeek-Flash" on Android and "DeepSeek Flash" on iOS — a
            // user-visible inconsistency reported from the field. DeepSeek's
            // official /v1/models sends no `name` field, so every one of its
            // models lands on this fallback and showed the hyphenated form.
            //
            // `filter { it.isNotEmpty() }` stands in for Swift's
            // `split(separator:)`, which drops empty subsequences — without it
            // a doubled separator ("org//model") would emit a blank token and
            // produce a double space.
            return fromId
                .replace('/', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { token ->
                    val lower = token.lowercase()
                    when {
                        brandRewrites.containsKey(lower) -> brandRewrites[lower]!!
                        upperTokens.contains(lower) -> lower.uppercase()
                        else -> token.replaceFirstChar { it.titlecase() }
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

/**
 * [T-android-modality-provider-fallback] Provider-level default modalities,
 * used ONLY when a model declares none at all.
 *
 * Android derived modality purely from the two stored lists, which are filled
 * either by a hand-written catalog literal or by ModelsDevApi.enrichModel. A
 * model that models.dev has not catalogued yet — i.e. every model in its first
 * days, Fable 5.1 being the case that surfaced this — has both lists null, and
 * every `contains("image")` check then answered false. The model detail screen
 * showed image / PDF / audio / video input all switched OFF for a model that
 * plainly supports images.
 *
 * iOS never had the gap because `capabilities` is computed and falls back to a
 * per-provider table (LLMTypes.swift `knownCapabilities`). This is the Android
 * port of that table, values copied rather than re-derived:
 *
 *   Anthropic  .vision          → text, image, pdf
 *   OpenAI     .vision          → text, image, pdf
 *   OpenRouter .vision          → text, image, pdf
 *   Google     .fullMultimodal  → text, image, pdf, audio, video
 *   xAI, Kimi, GitHub Copilot  .vision  → text, image, pdf
 *     ([T-provider-default-modality-key], iOS 97577e81d; see below)
 *   (unknown)  .textOnly        → text
 *
 * Providers absent from iOS's table are absent here too, and fall through to
 * text-only. That is deliberate: DeepSeek and every third-party
 * OpenAI-compatible endpoint (vLLM, Ollama, LiteLLM…, labelled "Custom" by
 * OpenAIModelsApi) keep deriving modality from what their /v1/models actually
 * returned. Inventing a default for them is exactly the regression this table
 * must not cause — a relay that serves a text-only model would start
 * advertising image input.
 */
private val VISION_INPUT = listOf("text", "image", "pdf")
private val FULL_MULTIMODAL_INPUT = listOf("text", "image", "pdf", "audio", "video")

private val PROVIDER_DEFAULT_INPUT_MODALITIES: Map<String, List<String>> = mapOf(
    "Anthropic" to VISION_INPUT,
    "OpenAI" to VISION_INPUT,
    "OpenRouter" to VISION_INPUT,
    "Google" to FULL_MULTIMODAL_INPUT,
    // [T-android-image-input-preflight] `LLMModel.provider` carries
    // ProviderType.displayName, and the Gemini type's display name is
    // "Google Gemini" — the "Google" row above never matched a real model, so
    // un-catalogued Gemini models fell through with no default at all.
    "Google Gemini" to FULL_MULTIMODAL_INPUT,
    // [T-provider-default-modality-key] Port of iOS 97577e81d. iOS's enum
    // (`ProviderType.defaultModality`) already said .vision for xAI, Kimi Code
    // and GitHub Copilot, but its capability table had no rows for them, and a
    // miss there is silent: it falls through to text-only. Android had the same
    // gap. The shape is GH#265's: a Grok released after the last models.dev
    // snapshot (grok-4.6 on its first day) declares no modalities, so it
    // reported no image input — the model detail screen showed every input
    // switch off and the image-input preflight refused photos for it.
    //
    // Keyed under BOTH spellings a real model can carry, the lesson of the
    // Gemini row above: the catalog and the models APIs write "xAI" / "Kimi" /
    // "GitHub Copilot" (the same strings iOS keys on), while the provider import
    // path stamps ProviderType.displayName ("xAI (Grok)", "Kimi Code"). A model
    // that declares its own list is unaffected; this only fills a null.
    "xAI" to VISION_INPUT,
    "xAI (Grok)" to VISION_INPUT,
    "Kimi" to VISION_INPUT,
    "Kimi Code" to VISION_INPUT,
    "GitHub Copilot" to VISION_INPUT,
)

/** Output side of the same table. Every entry above is input-multimodal only
 *  (iOS's `.fullMultimodal` is aliased to `.fullMultimodalInput`), so text out
 *  is the correct default across the board. */
private val PROVIDER_DEFAULT_OUTPUT_MODALITIES: Map<String, List<String>> = mapOf(
    "Anthropic" to listOf("text"),
    "OpenAI" to listOf("text"),
    "OpenRouter" to listOf("text"),
    "Google" to listOf("text"),
    "Google Gemini" to listOf("text"),
    "xAI" to listOf("text"),
    "xAI (Grok)" to listOf("text"),
    "Kimi" to listOf("text"),
    "Kimi Code" to listOf("text"),
    "GitHub Copilot" to listOf("text"),
)

/**
 * [T-android-modality-provider-fallback] Input modalities to USE, with the
 * provider default applied only as a last resort.
 *
 * Precedence, lowest priority last:
 *   1. the model's own list (a catalog literal, or models.dev enrichment)
 *   2. the provider default table
 *
 * The user's per-model override sits ABOVE both and is applied by the caller
 * (ModelEntryDetailScreen / ModelOverrides), which never reaches this property
 * when an override exists.
 *
 * Read this — not the raw field — for capability questions like "can it take an
 * image". Do NOT read it for the voice SHAPE predicates: those match the stored
 * lists exactly and must keep seeing null as null (see VoiceModality).
 */
val LLMModel.effectiveInputModalities: List<String>?
    get() = inputModalities.normalizeModalities()
        ?: PROVIDER_DEFAULT_INPUT_MODALITIES[provider]

val LLMModel.effectiveOutputModalities: List<String>?
    get() = outputModalities.normalizeModalities()
        ?: PROVIDER_DEFAULT_OUTPUT_MODALITIES[provider]
