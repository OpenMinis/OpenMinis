package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenAIModelsApi {
    private const val TAG = "OpenAIModelsApi"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("openai")

    /**
     * Static model list for Codex OAuth (tokens can't call /v1/models).
     * Matches iOS `LLMModel.allOpenAICodexOAuth` (Providers/LLMTypes.swift).
     * Order is preserved so the model picker shows the same default rank.
     */
    // T119: every GPT-5.x model on Codex OAuth supports reasoning_effort
    // (`/v1/responses` requires the `reasoning` object on this auth path),
    // so set supportsReasoning = true up front. Without it the Thinking
    // pill in chat is disabled and the user can't pick low/medium/high.
    fun fetchModelsOAuth(): List<LLMModel> = listOf(
        // [T-android-thinking-level-arch] GPT-5.6 family — Codex OAuth only
        // (not in LLMModel.allOpenAI, matching iOS). sol/terra reach ULTRA,
        // luna reaches MAX (see ThinkingLevelCatalog).
        // [T-gpt6-astra] GPT-6 Astra — Codex OAuth only, the current flagship,
        // so it leads the list. Reaches MAX like sol/terra (see
        // ThinkingLevelCatalog); no effort tiers declared here, same pattern
        // as the 5.6 family, so the catalog rule sets the ceiling.
        // [T-gpt6-sol-luna] GPT-6 Sol / Luna — Codex OAuth only, available on
        // every plan (including free). `supportsReasoning` is EXPLICIT and load
        // bearing: neither id is in the bundled models.dev snapshot, so nothing
        // enriches the flag later, and the Responses builder's "Codex OAuth
        // needs a reasoning object" fallback hard-codes effort "low" — the
        // exact data gap GPT6AstraReasoningTest exists to pin.
        LLMModel("gpt-6-sol", "GPT-6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-luna", "GPT-6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-astra", "GPT-6 Astra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-sol", "GPT-5.6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4", "GPT-5.4", "OpenAI", supportsReasoning = true),
        // [T-codex-oauth-model-prune] Verified callable on a live
        // ChatGPT-account token (2026-08-01); we had never listed it.
        LLMModel("gpt-5.4-mini", "GPT-5.4 Mini", "OpenAI", supportsReasoning = true),
        // [T-codex-oauth-model-prune] gpt-5.3-codex, gpt-5.3-codex-spark,
        // gpt-5-codex-mini, gpt-5.3, gpt-5.2 and gpt-5 were removed here.
        // The Codex backend answers each with
        //   HTTP 400 {"detail":"The '<id>' model is not supported when using
        //   Codex with a ChatGPT account."}
        // and that error renders as an EMPTY assistant turn, so leaving them in
        // the picker reads to the user as "tool calls are broken" rather than
        // "wrong model". Verified on-device 2026-08-01 against a real Codex
        // OAuth token; the survivors match the set CLIProxyAPI ships for its
        // Codex client (internal/registry/models/codex_client_models.json).
        // Availability is tier-dependent — re-probe before restoring any id,
        // do not add one back from documentation alone. Mirrors iOS
        // LLMModel.allOpenAICodexOAuth.
    ).let {
        AppLogger.info(TAG, "Codex OAuth model list (${it.size} models): ${it.joinToString { m -> m.id }}")
        ModelsDevApi.enrichModels(it)
    } + codexImageModels()

    /**
     * The Codex-OAuth image-generation models.
     *
     * [T-codex-dynamic-discovery GH#319] Extracted from the inline tail of
     * [fetchModelsOAuth] so the live-discovery path can append the same set.
     * Discovery returns CHAT SKUs only — these are not in it — so without a
     * shared accessor a successful discovery would drop three models that do
     * work on this auth path. Same list, one definition, two callers.
     */
    fun codexImageModels(): List<LLMModel> = listOf(
        // [T-codex-gpt-image2-oauth-android] Special image-generation model on
        // the Codex OAuth path. Appended AFTER enrichModels so its declared
        // image input/output modalities survive (models.dev doesn't know it).
        // It does NOT take the normal Chat Completions / Responses path — the
        // gpt-image-2 branch in OpenAIProvider routes it through the Codex
        // image_generation tool. Additive only: the GPT-5.x entries above and
        // their existing OAuth flow are unchanged.
        LLMModel(
            id = "gpt-image-2",
            displayName = "GPT Image 2",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        // [T-codex-gpt-image25-android] The 2.5 variants. Same Codex OAuth path
        // as gpt-image-2 above and the same reason for sitting after
        // enrichModels — models.dev does not carry them either.
        //
        // What differs is on the wire: gpt-image-2 is whatever the backend
        // picks by default for a bare {type:image_generation} tool, while these
        // two name themselves in that tool object. See
        // OpenAIProvider.buildCodexImageBody.
        LLMModel(
            id = "gpt-image-2.5-sunburst",
            displayName = "GPT Image 2.5 Sunburst",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        LLMModel(
            id = "gpt-image-2.5-flare",
            displayName = "GPT Image 2.5 Flare",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
    )

    // Chat-capable model prefixes (matching iOS)
    private val chatPrefixes = listOf("gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-")

    // Suffixes to exclude (matching iOS)
    private val excludeSuffixes = listOf(
        "-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding"
    )

    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] Per-provider UA override; null/blank
        // keeps the default UA. Threaded from ProviderRepository.refreshModels.
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        // For third-party endpoints (vLLM, Ollama, etc.), return empty on failure
        // so the caller preserves existing models instead of replacing with built-in GPT list.
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI

        val cacheKey = (baseURL ?: "") + "|" + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val url = buildURL(baseURL)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            // [T-provider-custom-user-agent] models-list UA override.
            .applyUserAgentOverride(customUserAgent)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext fallback

        if (!response.isSuccessful) {
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            return@withContext fallback
        }

        val models = try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext fallback
            val parsed = mutableListOf<LLMModel>()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val id = obj.getString("id")

                // Only filter by chat prefixes for official OpenAI endpoints;
                // custom endpoints (vLLM, Ollama) may serve any model ID.
                if (!isCustomBase) {
                    if (!chatPrefixes.any { id.startsWith(it) }) continue
                    if (excludeSuffixes.any { id.contains(it) }) continue
                    if (id.contains(":ft-")) continue
                }

                val displayName = obj.optString("name", "").ifBlank { LLMModel.modelDisplayName(id) }
                // Third-party gateways (vLLM, OpenRouter-compat proxies) often
                // report per-model modalities under `architecture.{input,output}_modalities`
                // the same way OpenRouter does — pick them up so vision/audio
                // models are routable without waiting for models.dev enrichment.
                val arch = obj.optJSONObject("architecture")
                // OpenAI / OpenRouter return modalities as `image_input` / `text_output` with
                // suffixes; the rest of the codebase (models.dev, capability fragments,
                // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
                // boundary so persisted overrides round-trip correctly through the toggles.
                val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
                val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

                // T119: known reasoning families (GPT-5.x, o-series, Codex
                // Mini) get supportsReasoning pre-set to true so the
                // Thinking pill enables before models.dev enrichment lands
                // — for brand-new ids (e.g. gpt-5.5) the catalog rarely has
                // the `reasoning` flag yet, and without this the pill
                // stays disabled.
                //
                // `gpt-6` is anchored alongside `gpt-5` for exactly the reason
                // 9938d0686 anchored gpt-5 in the first place, one generation
                // later: the Codex OAuth path stamps gpt-6-astra from its static
                // list, but an API-key instance discovering it through
                // /v1/models had no Thinking pill until models.dev catalogued
                // the id.
                val idLower = id.lowercase()
                val knownReasoning = idLower.startsWith("gpt-5") ||
                    idLower.startsWith("gpt-6") ||
                    idLower.startsWith("o1") ||
                    idLower.startsWith("o3") ||
                    idLower.startsWith("o4") ||
                    idLower.contains("codex")

                parsed.add(
                    LLMModel(
                        id = id,
                        displayName = displayName,
                        provider = if (isCustomBase) "Custom" else "OpenAI",
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                        supportsReasoning = if (knownReasoning) true else null,
                    )
                )
            }
            if (parsed.isEmpty()) return@withContext fallback
            ModelsDevApi.enrichModels(parsed)
        } catch (_: Exception) {
            return@withContext fallback
        }

        if (context != null) cache.save(context, cacheKey, models)
        models
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Check if a base URL points to official OpenAI endpoints. */
    private fun isOfficialOpenAI(baseURL: String): Boolean {
        val lower = baseURL.lowercase()
        return lower.contains("api.openai.com") || lower.contains("chatgpt.com")
    }

    private fun buildURL(baseURL: String?): String {
        if (baseURL == null) return "https://api.openai.com/v1/models"
        // baseURL is ProviderConfig.effectiveBaseURL — it already has /v1 iff the
        // appendV1Suffix toggle is on. Only append /models; never force /v1, or the
        // toggle is overridden and services without /v1 return 404.
        val base = baseURL.trimEnd('/')
        return "$base/models"
    }
}
