package com.openminis.app.provider.openrouter

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.VoiceRole
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenRouterModelsApi {
    private val client = OkHttpClient()
    // [T-openrouter-voice-catalog] "-v2": entries saved before the speech /
    // transcription catalogs were merged in hold no voice models and no
    // voiceRole, and would otherwise be served for up to 7 more days — the
    // fix would look like it did nothing.
    private val cache = ProviderModelsCache("openrouter-v2")

    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"

    /**
     * Fetch OpenRouter's model catalog. OpenRouter returns rich metadata
     * alongside each model (context window, max output, reasoning support,
     * input/output modalities) — we parse it here instead of waiting for
     * `ModelsDevApi.enrichModels` to match, matching iOS `OpenRouterModelsAPI`.
     *
     * @param context When provided, results are cached for 7 days at
     *   `cacheDir/models-cache/openrouter-v2/<sha256(apiKey)>.json`. Pass null to
     *   bypass the cache entirely (matches existing callers).
     * @param forceRefresh Skip the cache read but still write-through on success.
     */
    suspend fun fetchModels(
        apiKey: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        // [T-openrouter-voice-catalog] OpenMinis#280. The default list holds
        // only chat models; speech and transcription models are listed solely
        // by their output_modalities filters. Fetch all three at once and tag
        // each model with the list it came from.
        val (defaultResult, speech, transcription) = coroutineScope {
            val d = async { fetchList(MODELS_URL, apiKey) }
            val tts = async { fetchList("$MODELS_URL?output_modalities=speech", apiKey) }
            val stt = async { fetchList("$MODELS_URL?output_modalities=transcription", apiKey) }
            Triple(d.await(), tts.await(), stt.await())
        }

        val defaultModels = when (defaultResult) {
            is ListResult.Ok -> defaultResult.models
            is ListResult.Failed -> {
                if (context != null && (defaultResult.code == 401 || defaultResult.code == 403)) {
                    cache.invalidate(context, cacheKey)
                }
                return@withContext emptyList()
            }
        }
        // A failed filter endpoint only costs its voice models; the chat
        // catalog still loads.
        val speechModels = (speech as? ListResult.Ok)?.models.orEmpty()
        val transcriptionModels = (transcription as? ListResult.Ok)?.models.orEmpty()
        if (speech is ListResult.Failed || transcription is ListResult.Failed) {
            android.util.Log.w(
                "OpenRouterModelsApi",
                "voice catalog partial: speech=${speech.describe()} transcription=${transcription.describe()}",
            )
        }

        val merged = mergeVoiceCatalogs(
            ModelsDevApi.enrichModels(defaultModels),
            speechModels,
            transcriptionModels,
        )
        if (context != null) cache.save(context, cacheKey, merged)
        merged
    }

    private sealed class ListResult {
        class Ok(val models: List<LLMModel>) : ListResult()
        class Failed(val code: Int) : ListResult()

        fun describe(): String = when (this) {
            is Ok -> "${models.size}"
            is Failed -> "failed(HTTP $code)"
        }
    }

    private fun fetchList(url: String, apiKey: String): ListResult = try {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
            .header("X-Title", "Minis App")
            // [T-android-default-ua] brand outbound /api/v1/models request.
            .applyUserAgentOverride(null)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                ListResult.Failed(response.code)
            } else {
                val data = JSONObject(body).optJSONArray("data")
                if (data == null) ListResult.Failed(response.code) else ListResult.Ok(parseModels(data))
            }
        }
    } catch (_: Exception) {
        ListResult.Failed(-1)
    }

    /**
     * [T-openrouter-voice-catalog] Tag every model with the catalog it came
     * from and give voice models their exact dedicated shape. The filtered
     * lists declare `speech` / `transcription` rather than `audio`, so without
     * the shape they would show no audio at all.
     *
     *   default list        → voiceRole "none", modalities as declared
     *   speech filter       → voiceRole "tts",  text in → audio out
     *   transcription filter → voiceRole "stt",  audio in → text out
     *
     * The filtered lists win on an id collision (none today), since the
     * filter IS the authoritative "this is a voice model" signal. Order: chat
     * catalog first, then TTS, then ASR, each in the order OpenRouter returned.
     */
    internal fun mergeVoiceCatalogs(
        defaultModels: List<LLMModel>,
        speech: List<LLMModel>,
        transcription: List<LLMModel>,
    ): List<LLMModel> {
        val byId = LinkedHashMap<String, LLMModel>()
        for (m in defaultModels) byId[m.id] = m.copy(voiceRole = VoiceRole.NONE)
        for (m in speech) {
            byId[m.id] = m.copy(
                voiceRole = VoiceRole.TTS,
                inputModalities = listOf("text"),
                outputModalities = listOf("audio"),
            )
        }
        for (m in transcription) {
            byId[m.id] = m.copy(
                voiceRole = VoiceRole.STT,
                inputModalities = listOf("audio"),
                outputModalities = listOf("text"),
            )
        }
        return byId.values.toList()
    }

    private fun parseModels(data: JSONArray): List<LLMModel> {
        val models = mutableListOf<LLMModel>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            val name = obj.optString("name", "").ifBlank { LLMModel.modelDisplayName(id) }

            // OpenRouter exposes `architecture.input_modalities` / `output_modalities`
            // as arrays of short strings (e.g. "text", "image", "audio"). Fall back
            // to `[text, text]` when absent — most text-only models omit these.
            val arch = obj.optJSONObject("architecture")
            // OpenRouter returns modalities as `image_input` / `text_output` with suffixes;
            // the rest of the codebase (models.dev, capability fragments,
            // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
            // boundary so persisted overrides round-trip correctly through the toggles.
            val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
            val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

            val contextWindow = obj.optInt("context_length").takeIf { it > 0 }
            val maxOutputTokens = obj.optJSONObject("top_provider")
                ?.optInt("max_completion_tokens")?.takeIf { it > 0 }

            val supportedParams = obj.optJSONArray("supported_parameters")?.toStringList() ?: emptyList()
            val supportsReasoning = "reasoning" in supportedParams

            models.add(
                LLMModel(
                    id = id,
                    displayName = name,
                    provider = "OpenRouter",
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = if (supportsReasoning) true else null,
                    inputModalities = inputModalities,
                    outputModalities = outputModalities,
                )
            )
        }
        return models
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
