package com.openminis.app.provider

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.applyUnrecognizedModelDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.network.withDohDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches and caches the models.dev provider registry.
 * Used as a fallback when a provider's /v1/models endpoint is unavailable,
 * and as the source of truth for model capabilities (context window, output limit, reasoning).
 *
 * Three-tier cache: in-memory → disk cache → bundled asset fallback.
 */
object ModelsDevApi {
    private const val TAG = "ModelsDevApi"
    private const val SOURCE_URL = "https://models.dev/api.json"
    private const val CACHE_TTL_MS = 48 * 3600 * 1000L // 48 hours

    // Provider-key mapping for enrichment lookups (matches iOS)
    private val providerKeyMap = mapOf(
        "Anthropic" to listOf("anthropic"),
        "Google" to listOf("google", "google-vertex"),
        "OpenAI" to listOf("openai"),
        "OpenRouter" to listOf("openrouter"),
        "Antigravity" to emptyList(), // Custom proxy, no public models.dev entry
    )

    private var cachedRegistry: Map<String, ProviderEntry>? = null
    private var cacheTimestamp: Long = 0L
    private val isRefreshing = AtomicBoolean(false)
    private var appContext: Context? = null
    // [T-modelsdev-id-normalization] Memoized stage-2 winner per normalized id.
    // Rebuilt whenever cacheTimestamp moves (disk load / network refresh).
    private var cachedStage2Index: Stage2Index? = null
    private var cachedStage3Corpus: List<FuzzyRow>? = null
    private var stage2IndexBuiltFrom: Long? = null
    private var stage2IndexRegistryId: Int? = null

    private val client = OkHttpClient.Builder()
        // [T-doh-resolver-fallback] The catalog fetch is the app's first call on
        // a cold start; when the system resolver is poisoned this is exactly
        // where the user sees an empty model picker.
        .withDohDns()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Must be called once at app startup with application context. */
    fun init(context: Context) {
        appContext = context.applicationContext
        DataLearnerApi.init(context)
    }

    // MARK: - Public: Fetch models by base URL (fallback)

    fun fetchModels(forBaseURL: String): List<LLMModel> {
        val registry = loadRegistry() ?: return emptyList()

        // Phase 1: Exact API base match (with/without /v1)
        val candidates = normalizedCandidates(forBaseURL)
        for ((_, provider) in registry) {
            val api = provider.api ?: continue
            if (api.isEmpty()) continue
            val normalizedAPI = stripTrailingSlash(api)
            for (candidate in candidates) {
                if (candidate == normalizedAPI) {
                    val models = buildModels(provider)
                    Log.d(TAG, "Exact match ${provider.id} (api=$api) — ${models.size} models")
                    return models
                }
            }
        }

        // Phase 2: Hostname fallback
        val inputHost = extractHost(forBaseURL)
        if (inputHost != null) {
            for ((_, provider) in registry) {
                val api = provider.api ?: continue
                val providerHost = extractHost(api) ?: continue
                if (inputHost == providerHost) {
                    val models = buildModels(provider)
                    Log.d(TAG, "Host match ${provider.id} (host=$providerHost) — ${models.size} models")
                    return models
                }
            }
        }

        Log.d(TAG, "No models.dev match for base URL: $forBaseURL")
        return emptyList()
    }

    // MARK: - Public: Enrich models with models.dev data

    fun enrichModel(model: LLMModel): LLMModel {
        val fromDev = applyDevMatch(model)
        // Cache only on this path: enrichModel is also called from Compose /
        // the send path. A synchronous HTML scrape would ANR. Misses are
        // filled in the background; the next enrich sees the overlay.
        val supplemented = DataLearnerApi.supplement(fromDev, fetchIfMissing = false)
        DataLearnerApi.scheduleSupplement(supplemented)
        return applyUnrecognizedModelDefaults(supplemented)
    }

    fun enrichModels(models: List<LLMModel>): List<LLMModel> {
        val fromDev = models.map { applyDevMatch(it) }
        return DataLearnerApi.supplementAll(fromDev).map { applyUnrecognizedModelDefaults(it) }
    }

    private fun applyDevMatch(model: LLMModel): LLMModel {
        val registry = loadRegistry() ?: return model
        val match = resolveDevModel(model, registry) ?: return model
        return applyDevData(model, match.model)
    }

    /**
     * [T-modelsdev-id-normalization] Relays publish the same model under many
     * spellings — `glm-5.2`, `z-ai/glm-5.2`, `zai-org/GLM-5.2`. Exact-id
     * matching made context / max-output / effort tiers depend on which
     * spelling the gateway happened to use, so new models fell through to
     * provider defaults (16k output, 128k context).
     *
     * Drop the vendor/namespace path, lowercase, and collapse EVERY run of
     * separator punctuation to a single `-`.
     *
     * [T-modelsdev-separator-equivalence] The earlier version mapped only `.`
     * and `_`, which left `grok-4.6` -> `grok-4-6` matching while `grok4.6` ->
     * `grok4-6` missed the stage-2 index entirely. Stage 3 could not rescue it:
     * `grok` is in [ModelAliasMatcher]'s GENERIC set (deliberately excluded so
     * a bare brand cannot inherit another family's limits) and the leftover
     * digits score below the acceptance bar. These are the same model and only
     * a separator differs, so EVERY separator must be equivalent — including
     * whitespace, `:` and the empty-separator spelling `grok46`.
     *
     * Distinct families stay distinct (`glm-5.2` vs `glm-5.1`).
     */
    fun normalizedModelKey(id: String): String {
        val bare = id.substringAfterLast('/')
        return bare.lowercase()
            .replace(NON_ASCII_RUN, "-")
            .replace(SEPARATOR_RUN, "-")
            .replace(LETTER_DIGIT_BOUNDARY, "$1-$2")
            .replace(DIGIT_LETTER_BOUNDARY, "$1-$2")
            .replace(SEPARATOR_RUN, "-")
            .trim('-')
    }

    /**
     * Any run of characters that are neither a letter nor a digit: `.`, `_`,
     * `-`, whitespace, `,`, `:`, `/` and friends. Treated identically so that
     * the number of separators a relay happens to use cannot change which
     * catalog entry a model resolves to.
     */
    private val SEPARATOR_RUN = Regex("[^\\p{L}\\p{N}]+")
    private val LETTER_DIGIT_BOUNDARY = Regex("([\\p{L}])([\\p{N}])")
    private val DIGIT_LETTER_BOUNDARY = Regex("([\\p{N}])([\\p{L}])")

    /**
     * [T-modelsdev-relay-noise-catalog-driven] Non-ASCII LETTER runs are relay
     * decoration, not part of any model id: across the whole models.dev catalog
     * (8389 ids) not one contains a CJK character, while relay ids routinely
     * end in `破甲` / `尊享版` / `白嫖`. Treating them as separators makes
     * `grok4.6破甲` normalize to the same key as `grok-4.6` instead of
     * depending on a hand-maintained word list that the next relay invents
     * past. Also covers `qwen3-max尊享版`, `kimi-k3-内部版`, and any future
     * marker, in any position, for any family.
     */
    private val NON_ASCII_RUN = Regex("[^\\x00-\\x7F]+")

    internal data class DevModelMatch(
        val model: ModelDevEntry,
        val authoritative: Boolean,
    )

    /**
     * Resolution order (mirrors iOS ModelsDevAPI.resolveDevModel):
     *   1. the model's OWN provider (mapped key), exact id then normalized —
     *      an authoritative statement about this exact endpoint;
     *   2. every provider, matching on the NORMALIZED id, majority-vote among
     *      entries that declare effort tiers (ties: first in sorted scan order);
     *   3. dirty relay names (`GPT-6免费`, `免费GPT-6 Astra`): most matching
     *      characters against catalog id+name, never brand-only.
     */
    internal fun resolveDevModel(
        model: LLMModel,
        registry: Map<String, ProviderEntry>,
    ): DevModelMatch? {
        // [T-modelsdev-local-models-isolated] Ollama / llama.cpp ids carry a
        // runtime tag (`llama3.1:8b`, `qwen2.5:7b-instruct-q4`). Normalization
        // treats `:` as a separator, so `llama3.1:8b` collided with the catalog's
        // `meta/llama-3.1-8b` and inherited a 128k output cap for a local 8B
        // model that never had one. Local ids must never inherit catalog
        // limits — check BEFORE any lookup, not only before the fuzzy stage.
        if (isLocalModelId(model.id)) return null

        val wanted = normalizedModelKey(model.id)

        for (key in providerKeyMap[model.provider].orEmpty()) {
            val prov = registry[key] ?: continue
            prov.models[model.id]?.let {
                return DevModelMatch(it, authoritative = true)
            }
            for (id in prov.models.keys.sorted()) {
                if (normalizedModelKey(id) == wanted) {
                    val devModel = prov.models[id] ?: continue
                    return DevModelMatch(devModel, authoritative = true)
                }
            }
        }

        val stage2 = stage2Index(registry)
        stage2.byKey[wanted]?.let { return it }
        matchDirtyTail(wanted, stage2)?.let { return it }
        return fuzzyMatch(model, registry)
    }

    /**
     * [T-modelsdev-relay-noise-catalog-driven] True for ids addressed at a LOCAL
     * runtime by tag rather than a catalogued endpoint.
     */
    internal fun isLocalModelId(id: String): Boolean {
        val tail = id.substringAfterLast('/')
        return ':' in tail || tail.endsWith(".gguf", ignoreCase = true)
    }

    /**
     * Stage 2.5 — trim the relay's dirty tail off the query and look the base
     * model up as a NORMALIZED catalog entry (not a fuzzy guess, so the match
     * carries real catalog limits).
     *
     * A trailing token is dirty when the catalog never uses it. That test is
     * data, not a guess: across all 8389 catalog ids there is NOT ONE token
     * outside the catalog's own 895-word vocabulary, so the rule cannot discard
     * a real variant — `free`, `fast`, `thinking`, `preview`, `turbo`, `plus`,
     * `max`, `latest` are all real ids and all survive. Only relay inventions
     * (`oc`, `vx`, `破甲`) fall away, shortening the query one token at a time
     * so the LONGEST surviving prefix wins:
     *   `kimi-k3-oc`        -> `kimi-k3`
     *   `gpt-5.5-vx`        -> `gpt-5.5`
     *   `gemini-3-flash-lite-preview` -> `gemini-3-flash-lite` (or deeper)
     */
    internal fun matchDirtyTail(
        wanted: String,
        stage2: Stage2Index,
    ): DevModelMatch? {
        if (wanted.isEmpty()) return null
        val parts = wanted.split('-').filter { it.isNotEmpty() }
        for (cut in parts.size - 1 downTo 1) {
            val head = parts.take(cut).joinToString("-")
            val tail = parts.drop(cut)
            if (tail.any { it in stage2.vocabulary }) continue
            stage2.byKey[head]?.let { return it }
        }
        return null
    }

    internal data class Stage2Index(
        val byKey: Map<String, DevModelMatch>,
        /**
         * Every alphanumeric token the catalog uses anywhere. Membership is the
         * definition of "this word belongs to a real model id".
         */
        val vocabulary: Set<String>,
    )

    @Synchronized
    private fun stage2Index(registry: Map<String, ProviderEntry>): Stage2Index {
        val cached = cachedStage2Index
        val registryId = System.identityHashCode(registry)
        if (cached != null &&
            stage2IndexBuiltFrom == cacheTimestamp &&
            stage2IndexRegistryId == registryId
        ) {
            return cached
        }
        val built = buildStage2(registry)
        cachedStage2Index = built
        cachedStage3Corpus = buildStage3Corpus(built.byKey)
        stage2IndexBuiltFrom = cacheTimestamp
        stage2IndexRegistryId = registryId
        Log.d(TAG, "[ModelsDev] stage-2 index built: ${built.byKey.size} normalized keys, ${built.vocabulary.size} vocabulary tokens")
        return built
    }

    internal fun buildStage2(registry: Map<String, ProviderEntry>): Stage2Index =
        Stage2Index(buildStage2Index(registry), buildCatalogVocabulary(registry))

    /**
     * [T-modelsdev-relay-noise-catalog-driven] Union of every alphanumeric
     * token in every catalog id. Built from whatever registry is loaded — the
     * bundled asset, the disk cache, or a network refresh — so the dirty-tail
     * test always reflects the catalog actually being consulted.
     */
    internal fun buildCatalogVocabulary(registry: Map<String, ProviderEntry>): Set<String> {
        val vocab = HashSet<String>()
        for (prov in registry.values) {
            for (id in prov.models.keys) {
                val bare = id.substringAfterLast('/').lowercase()
                for (m in TOKEN_RUN.findAll(bare)) {
                    vocab.add(m.value)
                }
            }
        }
        return vocab
    }

    private val TOKEN_RUN = Regex("[0-9a-z]+")

    internal data class FuzzyRow(
        val match: DevModelMatch,
        val tokens: Set<String>,
    )

    internal fun buildStage3Corpus(index: Map<String, DevModelMatch>): List<FuzzyRow> {
        return index.values.map { match ->
            val text = listOfNotNull(match.model.id, match.model.name).joinToString(" ")
            // [T-modelsdev-relay-noise-catalog-driven] The corpus side of the
            // score must NOT strip noise words: `free` / `fast` / `thinking` are
            // real catalog ids, and stripping them collapses `kimi-k3-free`
            // onto `kimi-k3` before the variant penalty can separate them.
            FuzzyRow(match, ModelAliasMatcher.catalogTokens(text).toSet())
        }
    }

    internal fun fuzzyMatch(
        model: LLMModel,
        registry: Map<String, ProviderEntry>,
    ): DevModelMatch? {
        if (isLocalModelId(model.id)) return null
        stage2Index(registry)
        val corpus = cachedStage3Corpus.orEmpty()
        if (corpus.isEmpty()) return null
        return ModelAliasMatcher.pickBest(
            model.id,
            model.displayName,
            corpus,
            tokensOf = { it.tokens },
            idOf = { it.match.model.id },
            versionCompatible = { ModelAliasMatcher.versionCompatible(model.id, it.match.model.id) },
        )?.match
    }

    internal fun buildStage2Index(registry: Map<String, ProviderEntry>): Map<String, DevModelMatch> {
        val grouped = linkedMapOf<String, MutableList<ModelDevEntry>>()
        for (key in registry.keys.sorted()) {
            val prov = registry[key] ?: continue
            for (id in prov.models.keys.sorted()) {
                val devModel = prov.models[id] ?: continue
                grouped.getOrPut(normalizedModelKey(id)) { mutableListOf() }.add(devModel)
            }
        }
        val index = HashMap<String, DevModelMatch>(grouped.size)
        for ((normalized, candidates) in grouped) {
            pickStage2Winner(candidates)?.let { winner ->
                index[normalized] = DevModelMatch(winner, authoritative = false)
            }
        }
        return index
    }

    /**
     * Among candidates that declare effort tiers, the most commonly declared
     * set wins; ties keep the first-seen (scan-order) candidate. If nobody
     * declares effort, the first candidate still supplies context/output.
     */
    internal fun pickStage2Winner(candidates: List<ModelDevEntry>): ModelDevEntry? {
        if (candidates.isEmpty()) return null
        val declaring = candidates.filter { !it.reasoningEffortValues.isNullOrEmpty() }
        if (declaring.isEmpty()) return candidates.first()
        val counts = HashMap<List<String>, Int>()
        for (c in declaring) {
            val values = c.reasoningEffortValues ?: continue
            counts[values] = (counts[values] ?: 0) + 1
        }
        var winner: List<String>? = null
        var winnerCount = 0
        for (c in declaring) {
            val values = c.reasoningEffortValues ?: continue
            val count = counts[values] ?: 0
            if (count > winnerCount) {
                winnerCount = count
                winner = values
            }
        }
        val selected = declaring.firstOrNull { it.reasoningEffortValues == winner } ?: declaring.first()
        // Stage-2 combines equivalent model IDs across providers. Numeric
        // budget bounds must be safe for all of them: intersect every bound
        // actually declared, rather than inheriting whichever provider sorts
        // first. An empty intersection is invalid metadata, so discard it and
        // let request paths use their standard output/request-cap fallback.
        val declaredMins = candidates.mapNotNull { it.budgetTokensMin }
        val declaredMaxes = candidates.mapNotNull { it.budgetTokensMax }
        val conservativeMin = declaredMins.maxOrNull()
        val conservativeMax = declaredMaxes.minOrNull()
        val valid = conservativeMin == null || conservativeMax == null || conservativeMin <= conservativeMax
        return selected.copy(
            budgetTokensMin = if (valid) conservativeMin else null,
            budgetTokensMax = if (valid) conservativeMax else null,
        )
    }

    // MARK: - Apply models.dev data

    private fun applyDevData(model: LLMModel, devModel: ModelDevEntry): LLMModel {
        return model.copy(
            contextWindow = devModel.contextWindow ?: model.contextWindow,
            maxOutputTokens = devModel.maxOutputTokens ?: model.maxOutputTokens,
            supportsReasoning = devModel.reasoning ?: model.supportsReasoning,
            interleavedReasoningField = devModel.interleavedField ?: model.interleavedReasoningField,
            inputModalities = devModel.inputModalities ?: model.inputModalities,
            outputModalities = devModel.outputModalities ?: model.outputModalities,
            reasoningEffortValues = devModel.reasoningEffortValues ?: model.reasoningEffortValues,
            // [OpenMinis#163] Only carry the AFFIRMATIVE answer forward, so
            // enriching against an entry the catalog is silent about cannot
            // overwrite a prior real answer with a meaningless `false`.
            declaresNoEffortTiers = if (devModel.declaresNoEffortTiers) true else model.declaresNoEffortTiers,
            budgetTokensMin = devModel.budgetTokensMin ?: model.budgetTokensMin,
            budgetTokensMax = devModel.budgetTokensMax ?: model.budgetTokensMax,
        )
    }

    // MARK: - Build models from provider entry

    private fun buildModels(provider: ProviderEntry): List<LLMModel> {
        return provider.models.values.mapNotNull { model ->
            val family = model.family?.lowercase() ?: ""
            if (family.contains("embedding") || family.contains("moderation")) return@mapNotNull null
            LLMModel(
                id = model.id,
                displayName = model.name ?: model.id,
                provider = provider.name ?: provider.id,
                contextWindow = model.contextWindow,
                maxOutputTokens = model.maxOutputTokens,
                supportsReasoning = model.reasoning,
                interleavedReasoningField = model.interleavedField,
                inputModalities = model.inputModalities,
                outputModalities = model.outputModalities,
                reasoningEffortValues = model.reasoningEffortValues,
                // [OpenMinis#163] null (not false) when the catalog is silent,
                // so "unknown" stays distinguishable from "declared none".
                declaresNoEffortTiers = if (model.declaresNoEffortTiers) true else null,
                budgetTokensMin = model.budgetTokensMin,
                budgetTokensMax = model.budgetTokensMax,
            )
        }
    }

    // MARK: - URL Matching Helpers

    private fun normalizedCandidates(url: String): List<String> {
        val stripped = stripTrailingSlash(url)
        val results = mutableListOf(stripped)
        if (stripped.endsWith("/v1")) {
            results.add(stripped.dropLast(3))
        } else {
            results.add("$stripped/v1")
        }
        return results
    }

    private fun stripTrailingSlash(s: String): String {
        var r = s
        while (r.endsWith("/")) r = r.dropLast(1)
        return r
    }

    private fun extractHost(urlString: String): String? {
        return try {
            URL(stripTrailingSlash(urlString)).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Registry Cache (3-tier)

    /**
     * [T-model-release-ranking] Read-only view of the loaded catalog, for
     * [ModelReleaseIndex] to build its ranking tables from. Returns an empty map
     * rather than null so callers can't accidentally treat "catalog unavailable"
     * as an error state — an absent catalog just means nothing gets a rank and
     * every model keeps its fallback ordering.
     */
    fun registrySnapshot(): Map<String, ProviderEntry> = loadRegistry() ?: emptyMap()

    @Synchronized
    private fun installRegistry(parsed: Map<String, ProviderEntry>, timestamp: Long) {
        cachedRegistry = parsed
        cacheTimestamp = timestamp
        cachedStage2Index = null
        stage2IndexBuiltFrom = null
    }

    @Synchronized
    private fun loadRegistry(): Map<String, ProviderEntry>? {
        // 1. In-memory cache (fresh)
        val cached = cachedRegistry
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }

        // 2. In-memory cache exists but stale — return it, schedule refresh
        if (cached != null) {
            scheduleBackgroundRefresh()
            return cached
        }

        // 3. Disk cache
        val diskResult = loadDiskCache()
        if (diskResult != null) {
            val (parsed, diskDate) = diskResult
            installRegistry(parsed, diskDate)
            if (System.currentTimeMillis() - diskDate >= CACHE_TTL_MS) {
                scheduleBackgroundRefresh()
            }
            return parsed
        }

        // 4. Bundled fallback
        val bundled = loadBundledRegistry()
        if (bundled != null) {
            installRegistry(bundled, System.currentTimeMillis())
            scheduleBackgroundRefresh()
            return bundled
        }

        return null
    }

    private fun scheduleBackgroundRefresh() {
        if (!isRefreshing.compareAndSet(false, true)) return
        Thread {
            try {
                refreshFromNetwork()
            } finally {
                isRefreshing.set(false)
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun refreshFromNetwork() {
        try {
            val request = Request.Builder().url(SOURCE_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "models.dev HTTP error: ${response.code}")
                response.close()
                return
            }
            val body = response.body?.string() ?: return
            response.close()

            val parsed = parseRegistry(body)
            if (parsed != null) {
                synchronized(this) {
                    installRegistry(parsed, System.currentTimeMillis())
                }
                saveDiskCache(body)
                Log.d(TAG, "Background-refreshed models.dev registry: ${parsed.size} providers")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models.dev: ${e.message}")
        }
    }

    // MARK: - Parse registry JSON

    private fun parseRegistry(jsonStr: String): Map<String, ProviderEntry>? {
        return try {
            val json = JSONObject(jsonStr)
            val result = mutableMapOf<String, ProviderEntry>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val provObj = json.optJSONObject(key) ?: continue
                val entry = parseProviderEntry(key, provObj) ?: continue
                result[key] = entry
            }
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse models.dev JSON: ${e.message}")
            null
        }
    }

    private fun parseProviderEntry(id: String, obj: JSONObject): ProviderEntry? {
        val name = obj.optString("name", "").ifEmpty { null }
        val api = obj.optString("api", "").ifEmpty { null }
        val modelsObj = obj.optJSONObject("models") ?: return ProviderEntry(id, name, api, emptyMap())

        val models = mutableMapOf<String, ModelDevEntry>()
        val modelKeys = modelsObj.keys()
        while (modelKeys.hasNext()) {
            val modelKey = modelKeys.next()
            val modelObj = modelsObj.optJSONObject(modelKey) ?: continue
            models[modelKey] = parseModelDevEntry(modelKey, modelObj)
        }
        return ProviderEntry(id, name, api, models)
    }

    private fun parseModelDevEntry(id: String, obj: JSONObject): ModelDevEntry {
        val name = obj.optString("name", "").ifEmpty { null }
        val family = obj.optString("family", "").ifEmpty { null }

        // Parse limits
        val limitObj = obj.optJSONObject("limit")
        val contextWindow = limitObj?.optInt("context", 0)?.takeIf { it > 0 }
        val maxOutputTokens = limitObj?.optInt("output", 0)?.takeIf { it > 0 }

        // Parse reasoning
        val reasoning = if (obj.has("reasoning")) obj.optBoolean("reasoning") else null

        // Parse interleaved (can be bool or object {"field": "reasoning_content"})
        var interleavedField: String? = null
        if (obj.has("interleaved")) {
            val interleaved = obj.opt("interleaved")
            when (interleaved) {
                is JSONObject -> interleavedField = interleaved.optString("field", "").ifEmpty { null }
                is Boolean -> if (interleaved) interleavedField = "reasoning_content"
                true -> interleavedField = "reasoning_content" // JSON true
            }
        }

        // Parse modalities.input / modalities.output arrays
        val modalitiesObj = obj.optJSONObject("modalities")
        fun parseArray(key: String): List<String>? {
            val arr = modalitiesObj?.optJSONArray(key) ?: return null
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotEmpty() }?.let(out::add)
            }
            return out.takeIf { it.isNotEmpty() }
        }
        val inputModalities = parseArray("input")
        val outputModalities = parseArray("output")

        // [T-reasoning-effort-data-driven] and [T-modelsdev-budget-tokens]
        // reasoning_options carries independent wire mechanisms: effort strings,
        // toggles and numeric budget ranges. Parse both numeric bounds without
        // treating an absent bound as zero; many providers publish max only.
        var reasoningEffortValues: List<String>? = null
        var budgetTokensMin: Int? = null
        var budgetTokensMax: Int? = null
        val reasoningOptions = obj.optJSONArray("reasoning_options")
        fun bound(opt: JSONObject, key: String): Int? {
            if (!opt.has(key) || opt.isNull(key)) return null
            val raw = opt.opt(key)
            return when (raw) {
                is Number -> raw.toLong().takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                is String -> raw.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            }
        }
        reasoningOptions?.let { arr ->
            for (i in 0 until arr.length()) {
                val opt = arr.optJSONObject(i) ?: continue
                when (opt.optString("type")) {
                    "budget_tokens" -> {
                        budgetTokensMin = bound(opt, "min") ?: budgetTokensMin
                        budgetTokensMax = bound(opt, "max")?.takeIf { it > 0 } ?: budgetTokensMax
                    }
                    "effort" -> {
                        val vals = opt.optJSONArray("values") ?: continue
                        val out = mutableListOf<String>()
                        for (j in 0 until vals.length()) {
                            vals.optString(j, "").takeIf { it.isNotEmpty() }?.let { out.add(it.lowercase()) }
                        }
                        reasoningEffortValues = out.takeIf { it.isNotEmpty() }
                    }
                }
            }
        }
        if (budgetTokensMin != null && budgetTokensMax != null && budgetTokensMin!! > budgetTokensMax!!) {
            // Invalid ranges are ignored as a pair and handled by the shared
            // provider fallback chain; never emit a min greater than max.
            budgetTokensMin = null
            budgetTokensMax = null
        }
        // [OpenMinis#163] The catalog AFFIRMATIVELY says this model has no
        // effort tiers, as opposed to saying nothing at all. reasoningEffortValues
        // collapses both to null, losing the difference that matters on the wire:
        //   • reasoning_options absent → no opinion. Stay permissive and keep
        //     sending reasoning_effort; relays serve models the catalog has
        //     never heard of.
        //   • reasoning_options PRESENT but with no usable `effort` entry ([],
        //     or an effort entry whose values are empty) → the model reasons
        //     WITHOUT an effort parameter. Sending it is a hard 400: xAI
        //     grok-build-0.1 and grok-4.20-0309-reasoning both ship
        //     "reasoning": true with "reasoning_options": [].
        // Deliberately keyed on "no usable effort entry" rather than
        // "reasoning_options is empty", so a model declaring only toggle /
        // budget_tokens — also affirmatively not effort-controlled — counts.
        val declaresNoEffortTiers = reasoningOptions != null && reasoningEffortValues == null

        return ModelDevEntry(
            id = id,
            name = name,
            family = family,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoning,
            interleavedField = interleavedField,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            reasoningEffortValues = reasoningEffortValues,
            declaresNoEffortTiers = declaresNoEffortTiers,
            releaseDate = obj.optString("release_date", "").ifEmpty { null },
            outputCost = obj.optJSONObject("cost")
                ?.optDouble("output", Double.NaN)
                ?.takeIf { !it.isNaN() },
            budgetTokensMin = budgetTokensMin,
            budgetTokensMax = budgetTokensMax,
        )
    }

    // MARK: - Bundled Fallback

    private fun loadBundledRegistry(): Map<String, ProviderEntry>? {
        val ctx = appContext ?: return null
        return try {
            // [T-apk-size] Only the gzipped catalog ships in the APK
            // (~416KB vs 4.1MB plaintext). A local asset that fails to
            // gunzip means the APK is corrupt; there is no second bundled
            // copy to fall back to — the caller treats null as "use the
            // network path", which is the real refresh mechanism anyway.
            val jsonStr = ctx.assets.open("models-dev-api.json.gzip").use { raw ->
                java.util.zip.GZIPInputStream(raw).bufferedReader().readText()
            }
            val parsed = parseRegistry(jsonStr)
            Log.d(TAG, "Loaded bundled models.dev registry: ${parsed?.size ?: 0} providers")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled models-dev-api.json.gzip: ${e.message}")
            null
        }
    }

    // MARK: - Disk Cache

    private fun getCacheFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "models-dev-cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "api.json")
    }

    private fun loadDiskCache(): Pair<Map<String, ProviderEntry>, Long>? {
        val file = getCacheFile() ?: return null
        if (!file.exists()) return null
        return try {
            val jsonStr = file.readText()
            val parsed = parseRegistry(jsonStr) ?: return null
            Pair(parsed, file.lastModified())
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDiskCache(jsonStr: String) {
        val file = getCacheFile() ?: return
        try {
            file.writeText(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache: ${e.message}")
        }
    }

    // MARK: - Data classes

    data class ProviderEntry(
        val id: String,
        val name: String?,
        val api: String?,
        val models: Map<String, ModelDevEntry>,
    )

    data class ModelDevEntry(
        val id: String,
        val name: String?,
        val family: String?,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
        val reasoning: Boolean?,
        val interleavedField: String?,
        // modalities.input / modalities.output from models.dev (e.g. ["text","image"]).
        val inputModalities: List<String>?,
        val outputModalities: List<String>?,
        // [T-reasoning-effort-data-driven] `values` of the reasoning_options
        // entry whose type == "effort"; null when the model declares only
        // toggle / budget_tokens (different mechanisms, not effort control).
        val reasoningEffortValues: List<String>?,
        // [OpenMinis#163] True when reasoning_options was PRESENT but declared
        // no usable effort tier — "reasons, but takes no reasoning_effort".
        // Distinct from reasoningEffortValues == null, which also covers "the
        // catalog has never heard of this model"; only this affirmative case
        // may suppress the field.
        val declaresNoEffortTiers: Boolean = false,
        // [T-model-release-ranking] Raw `release_date`. models.dev fills this
        // for every entry, but 181 of them carry `YYYY-MM` with no day — the
        // parser must tolerate that or those models sink in every sorted list.
        val releaseDate: String?,
        // [T-model-release-ranking] USD per million output tokens. Tie-breaker
        // for same-day releases: sol/terra/luna all shipped 2026-07-09 and only
        // price (30 / 12 / 1.2) separates their tiers.
        val outputCost: Double?,
        // [T-modelsdev-budget-tokens] Provider/model-specific numeric thinking
        // budget bounds. Nullable because models.dev often publishes only one
        // side or only the `budget_tokens` mechanism without numeric values.
        val budgetTokensMin: Int? = null,
        val budgetTokensMax: Int? = null,
    )
}
