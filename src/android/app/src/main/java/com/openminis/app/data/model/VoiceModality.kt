package com.openminis.app.data.model

/**
 * [T-android-provider-voice] Voice-modality helpers — the Android port of the
 * iOS ModelModality voice-shape conventions (LLMTypes.swift).
 *
 * iOS models modality as one OptionSet; Android as two String lists
 * (inputModalities / outputModalities, bare names — see normalizeModalities()).
 * The cross-platform shape conventions are:
 *
 *   - Dedicated ASR model  = audio in → text out   (iOS [.audioInput, .textOutput])
 *   - Dedicated TTS model  = text in → audio out   (iOS [.textInput, .audioOutput])
 *   - TEMPLATE SEED        = exactly ONE audio flag and nothing else:
 *       ASR seed → inputs == ["audio"], outputs == null
 *       TTS seed → inputs == null,      outputs == ["audio"]
 *
 * The single-flag seed shape is the [T-voice-seed-shape-exact] discriminator:
 * no API-derived model is ever a single audio flag (inference and the models
 * APIs always attach the paired text side), so launch cleanup can safely
 * remove retired seeds by this exact shape without ever touching API models.
 */
/**
 * Sentinel ids for the on-device System speech engines (SpeechRecognizer /
 * TextToSpeech), used as virtual provider/model ids in voice groups. MUST stay
 * byte-identical to iOS (SystemVoiceProvider.builtinProviderId +
 * UnifiedModelPicker system entries) so voice-group member ids survive a
 * cross-platform config move.
 */
object SystemVoiceIds {
    const val BUILTIN_PROVIDER_ID = "__builtin_system_speech__"
    const val SYSTEM_ASR_ONLINE = "system-asr-online"
    const val SYSTEM_ASR_OFFLINE = "system-asr-offline"
    const val SYSTEM_TTS = "system-tts"
}

object VoiceModality {

    /** Substrings marking a DEDICATED speech-to-text (ASR) model. Mirrors iOS
     *  LLMTypes.asrInferencePatterns. */
    private val asrInferencePatterns = listOf(
        "-asr", "asr-", "_asr", "asr_", "whisper", "transcrib", "speech-to-text",
        "speech2text", "stt-", "-stt", "_stt", "stt_", "voice-input", "voice_input",
        // [T-asr-vendor-family-names] (GH#305) Families named after the model
        // ARCHITECTURE rather than the task. The list above only held
        // delimiter-bracketed forms of the literal "asr", so widely-used
        // Chinese ASR models matched nothing:
        //
        //   paraformer-realtime-v2  (Alibaba DashScope)
        //   SenseVoiceSmall         (FunAudioLLM)
        //
        // A non-match is not a loud failure — it falls through to the broad
        // modality inference, the model gets a general text shape, and the
        // voice pickers (which gate on the EXACT audio-in shape) simply never
        // list it. Silent absence, which is why it went unreported for so long.
        //
        // Kept byte-identical to iOS LLMTypes.asrInferencePatterns; the guard
        // test re-reads both sources so the two cannot drift apart.
        "paraformer", "sensevoice", "fun-asr", "qwen-asr",
    )

    /** Substrings marking a DEDICATED text-to-speech (TTS) model. Mirrors iOS
     *  LLMTypes.ttsInferencePatterns. */
    private val ttsInferencePatterns = listOf(
        "tts", "-tts", "_tts", "text-to-speech", "text2speech", "audio-gen",
        "audio-generation", "seed-tts", "voice-output", "voice_output",
    )

    /**
     * Infer a DEDICATED voice model's EXACT modality from its id/name, or null
     * when it isn't a dedicated ASR/TTS model. The returned pair is
     * (inputModalities, outputModalities) with BOTH sides populated — the
     * paired text side is mandatory so an inferred model never collides with
     * the single-flag template-seed shape. Mirrors iOS
     * LLMTypes.inferDedicatedVoiceModality.
     */
    fun inferDedicatedVoiceModality(id: String, displayName: String): Pair<List<String>, List<String>>? {
        val hay = "$id $displayName".lowercase()
        if (asrInferencePatterns.any { hay.contains(it) }) {
            return listOf("audio") to listOf("text")   // ASR: audio in → text out
        }
        if (ttsInferencePatterns.any { hay.contains(it) }) {
            return listOf("text") to listOf("audio")   // TTS: text in → audio out
        }
        return null
    }
}

/**
 * RAW normalized modalities — exactly what the model declares, with null
 * preserved as null.
 *
 * [T-android-modality-provider-fallback] Deliberately NOT the provider-default
 * fallback (`effectiveInputModalities`). The seed-shape predicate below matches
 * `null` and `["audio"]` exactly, so a fallback here would give every
 * modality-less model a synthetic `["text", …]` and the exact match could never
 * fire again. Capability questions use the effective lists; shape questions use
 * these.
 */
private val LLMModel.normalizedInputs: List<String>? get() = inputModalities.normalizeModalities()
private val LLMModel.normalizedOutputs: List<String>? get() = outputModalities.normalizeModalities()

/**
 * True when this model consumes audio (ASR or audio-capable chat model).
 *
 * [T-android-modality-provider-fallback] Reads the EFFECTIVE list, so a
 * Google model that models.dev has not catalogued yet still reports audio in —
 * matching iOS's `.fullMultimodal` default for that provider. No table entry
 * grants audio to Anthropic / OpenAI / OpenRouter, so this cannot start
 * reporting audio for a text-and-vision model.
 */
val LLMModel.hasAudioInput: Boolean
    get() = effectiveInputModalities?.contains("audio") == true

/** True when this model produces audio (TTS or omni model). No provider in the
 *  default table declares audio OUT, so the fallback never flips this on. */
val LLMModel.hasAudioOutput: Boolean
    get() = effectiveOutputModalities?.contains("audio") == true

/**
 * True when this model natively consumes images (a vision-capable model).
 * [T-android-vision-group] The Vision Group resolver filters group members by
 * this predicate. Normalizes so "image_input" (OpenAI/OpenRouter suffix form)
 * and bare "image" (models.dev) both match.
 *
 * [T-android-modality-provider-fallback] Effective list, so an un-catalogued
 * Anthropic/OpenAI/OpenRouter/Google model is vision-capable by provider
 * default instead of silently failing the Vision Group filter.
 */
val LLMModel.hasImageInput: Boolean
    get() = effectiveInputModalities?.contains("image") == true

/** True when this model has ANY audio modality — the "voice model" predicate
 *  behind Voice Services shadow visibility (iOS hasVoiceModels). */
val LLMModel.hasVoiceModality: Boolean
    get() = hasAudioInput || hasAudioOutput

/**
 * [T-openrouter-voice-catalog] OpenMinis#280. Values of [LLMModel.voiceRole],
 * and the OpenRouter chat-audio allowlist. Mirrors iOS `VoiceRole`; the guard
 * tests re-read both sources so the values cannot drift.
 *
 * OpenRouter's default `GET /models` lists only chat models. Its 18 speech
 * and 22 transcription models are served ONLY by
 * `?output_modalities=speech` / `?output_modalities=transcription`, which
 * share no id with the default list (checked against the live API). Before,
 * the voice pickers were built from the default list's audio flags, so they
 * held only false positives — chat models that merely hear audio (Muse Spark
 * as "STT") or music generators that emit it (Lyria as "TTS") — and none of
 * the real voice models.
 */
object VoiceRole {
    const val TTS = "tts"
    const val STT = "stt"
    const val NONE = "none"

    /** Chat models on OpenRouter that genuinely do both directions, via
     *  chat/completions (audio-preview for TTS, `input_audio` for ASR). */
    val OPENROUTER_CHAT_AUDIO_MODELS = setOf("openai/gpt-audio", "openai/gpt-audio-mini")
}

/**
 * [T-openrouter-voice-catalog] Voice INPUT (ASR) candidate — the predicate
 * behind voice pickers and voice-group resolution, deliberately separate from
 * [hasAudioInput], which still answers "can this chat model hear audio"
 * for multimodal chat.
 *
 * A catalog-tagged model is judged by its tag alone: "stt", or a chat-audio
 * allowlist entry. An untagged model (custom entry, other provider, legacy
 * save) keeps the modality rule — that is the manual-entry escape hatch.
 */
val LLMModel.isVoiceInputCandidate: Boolean
    get() = when (voiceRole) {
        null -> hasAudioInput
        VoiceRole.STT -> true
        else -> id in VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS
    }

/** Voice OUTPUT (TTS) counterpart of [isVoiceInputCandidate]. */
val LLMModel.isVoiceOutputCandidate: Boolean
    get() = when (voiceRole) {
        null -> hasAudioOutput
        VoiceRole.TTS -> true
        else -> id in VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS
    }

/** Either direction — the Voice Services visibility predicate. */
val LLMModel.isVoiceCandidate: Boolean
    get() = isVoiceInputCandidate || isVoiceOutputCandidate

/**
 * [T-voice-seed-shape-exact] True when this model carries the exact single-flag
 * VOICE-TEMPLATE SEED shape (see VoiceModality doc). Template mockModels are
 * authored this way on purpose; API models always carry the paired text side.
 *
 * [T-android-modality-provider-fallback] Uses the RAW lists on purpose — the
 * whole discriminator is "exactly one audio flag and nothing on the other
 * side", and the provider-default fallback would replace both nulls with a
 * synthetic text entry, making this permanently false and stranding retired
 * seeds that launch cleanup is supposed to remove.
 */
val LLMModel.isVoiceTemplateSeedShape: Boolean
    get() {
        val ins = normalizedInputs
        val outs = normalizedOutputs
        val asrSeed = ins == listOf("audio") && outs == null
        val ttsSeed = ins == null && outs == listOf("audio")
        return asrSeed || ttsSeed
    }

/**
 * Apply dedicated-voice modality inference when the model has no explicit
 * modality info. Mirrors the dedicated-voice step of iOS
 * LLMModel.withInferredModality (models.dev enrichment stays where it is —
 * this only fills the exact ASR/TTS shape from id/name patterns).
 */
fun LLMModel.withInferredVoiceModality(): LLMModel {
    if (inputModalities != null || outputModalities != null) return this
    val (ins, outs) = VoiceModality.inferDedicatedVoiceModality(id, displayName) ?: return this
    return copy(inputModalities = ins, outputModalities = outs)
}
