package com.openminis.app.provider.voice

import android.util.Log
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType

/**
 * [T-android-provider-voice] Maps a configured ProviderInstance to a concrete
 * VoiceProvider — Android port of iOS VoiceProviderFactory.swift.
 *
 * Minis has no dedicated "voice provider type": vendors riding on an
 * OpenAI/Anthropic-compatible instance (Groq, MiniMax, Doubao, Xunfei, Alibaba,
 * MiMo …) are detected from the instance's custom base URL. Returns null when
 * the instance cannot serve voice.
 *
 * Doubao / iFlytek need more than one credential. Rather than extend the
 * credential store, the user enters them as a ";"-joined compound string in the
 * single API-key field ("appId;apiKey;apiSecret" for iFlytek); [splitCompound]
 * unpacks it. The caller supplies the API key (loaded from the repository's
 * encrypted store) so the factory stays free of storage dependencies.
 */
object VoiceProviderFactory {

    private const val TAG = "VoiceFactory"

    fun make(instance: ProviderInstance, apiKey: String?): VoiceProvider? {
        val custom = instance.customBaseURL
        val normalizedBase = (custom ?: "").lowercase()

        return when (instance.providerType) {
            // OpenAI-compatible families -------------------------------------
            // [T-android-provider-type-parity] openAIResponses shares the
            // OpenAI voice endpoints (/v1/audio/*) — only the text
            // completion path differs, which voice never uses.
            ProviderType.openAI, ProviderType.openRouter, ProviderType.openAIResponses -> {
                if (instance.providerType == ProviderType.openRouter) {
                    // OpenRouter. TTS goes through chat.completions + the audio
                    // modality, NOT /v1/audio/speech — that endpoint does not
                    // exist there and 400s for every model id. ASR is split per
                    // model between chat.completions and /v1/audio/transcriptions.
                    return OpenRouterVoiceProvider(
                        instance.id,
                        custom ?: "https://openrouter.ai/api",
                        apiKey,
                    )
                }
                when {
                    normalizedBase.contains("groq.com") ->
                        GroqVoiceProvider(instance.id, custom ?: "https://api.groq.com/openai", apiKey)
                    normalizedBase.contains("dashscope") ->
                        AlibabaVoiceProvider(instance.id, custom ?: "https://dashscope.aliyuncs.com/compatible-mode", apiKey)
                    normalizedBase.contains("minimax") ->
                        MiniMaxVoiceProvider(instance.id, custom ?: "https://api.minimax.io", apiKey)
                    // Doubao / Volcano v3: single API Key (new console).
                    normalizedBase.contains("openspeech.bytedance") || normalizedBase.contains("volcano") -> {
                        Log.i(TAG, "Doubao voice provider: base=$normalizedBase keyLen=${apiKey?.length ?: -1}")
                        DoubaoVoiceProvider(instance.id, apiKey)
                    }
                    // iFlytek / Xunfei: the API-key field carries "appId;apiKey;apiSecret".
                    normalizedBase.contains("xfyun") -> {
                        val p = splitCompound(apiKey)
                        if (p.size >= 3) XunfeiVoiceProvider(instance.id, p[0], p[1], p[2]) else null
                    }
                    normalizedBase.contains("xiaomimimo") ->
                        MimoVoiceProvider(instance.id, custom ?: "https://api.xiaomimimo.com", apiKey)
                    normalizedBase.contains("elevenlabs") ->
                        ElevenLabsVoiceProvider(instance.id, custom ?: "https://api.elevenlabs.io", apiKey)
                    normalizedBase.contains("tts.speech.microsoft.com") -> {
                        // Strip /cognitiveservices if the user included it — the
                        // provider appends it in the endpoint path.
                        var azureBase = custom ?: "https://eastasia.tts.speech.microsoft.com"
                        if (azureBase.endsWith("/cognitiveservices")) {
                            azureBase = azureBase.removeSuffix("/cognitiveservices")
                        }
                        AzureTTSVoiceProvider(instance.id, azureBase, apiKey)
                    }
                    normalizedBase.contains("deepgram") ->
                        DeepgramVoiceProvider(instance.id, custom ?: "https://api.deepgram.com", apiKey)
                    else ->
                        VoiceProvider(instance.id, custom ?: "https://api.openai.com", apiKey)
                }
            }

            // xAI Grok ---------------------------------------------------------
            ProviderType.xAI ->
                XAIVoiceProvider(instance.id, custom ?: "https://api.x.ai", apiKey)

            // Anthropic — only MiniMax-behind-Anthropic-base serves voice ------
            ProviderType.anthropic ->
                if (normalizedBase.contains("minimax")) {
                    MiniMaxVoiceProvider(instance.id, custom ?: "https://api.minimax.io", apiKey)
                } else {
                    null
                }

            // Native Gemini TTS (generateContent + AUDIO modality, ?key= auth).
            ProviderType.gemini ->
                GeminiVoiceProvider(
                    instance.id,
                    custom ?: "https://generativelanguage.googleapis.com/v1beta",
                    apiKey,
                )

            // [T-kimi-oauth] Kimi Coding Plan serves no voice models.
            ProviderType.kimiCode -> null
            // [T-android-provider-type-parity] No voice support for types this
            // build cannot drive.
            ProviderType.antigravity, ProviderType.unsupported -> null
        }
    }

    /**
     * True when [make] would return a provider for [instance] with [apiKey] —
     * the shadow-voice candidate gate. Pass the real stored key: Xunfei's
     * compound-credential check ("appId;apiKey;apiSecret") depends on it.
     */
    fun supports(instance: ProviderInstance, apiKey: String?): Boolean =
        make(instance, apiKey) != null

    /**
     * Split a compound credential ("appId;key" / "appId;key;secret") stored as
     * one string. Tolerates whitespace; drops empty segments.
     */
    fun splitCompound(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
