package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [T-codex-dynamic-discovery GH#319] Live model discovery for the Codex OAuth
 * path.
 *
 * ## Why this exists
 *
 * [OpenAIModelsApi.fetchModelsOAuth] returns a list compiled into the app. Its
 * comment ("OAuth tokens can't call /v1/models") is true and remains true —
 * but it was read as "therefore no discovery is possible", which is not. The
 * Codex backend has its own discovery route, `/backend-api/codex/models`, that
 * the OAuth token DOES authenticate against. Consequence of the gap: a model
 * enabled on the user's own ChatGPT account was invisible until someone shipped
 * a new build with the id typed into that literal, and pressing Refresh could
 * not help because Refresh ran the same literal.
 *
 * Verified independently before this was written (2026-09-11):
 *   * `GET https://chatgpt.com/backend-api/codex/models?client_version=…`
 *     with a bogus bearer answers **401** + `{"detail":"Could not parse your
 *     authentication token…"}` — a real auth challenge on a real route.
 *   * `GET https://chatgpt.com/backend-api/models?client_version=…` answers
 *     **403 text/html** (a Cloudflare-style block page). That is NOT the
 *     discovery route; it is deliberately not in the path list below.
 *
 * ## Identity on the wire
 *
 * The headers mirror [OpenAIProvider]'s inference request EXACTLY — same
 * `Originator: codex_cli_rs`, same `codex_cli_rs/<v> (Android; arm64)` UA, same
 * `Version`, same `Openai-Beta`. This is deliberate and load-bearing in two
 * directions: OpenAI gates model availability on the advertised client version,
 * so discovery must not advertise a different one than inference (a model
 * listed under one version and refused under another is the worst outcome), and
 * the account must not see two different client identities from one app.
 *
 * The upstream reference implementation this was checked against uses its own
 * `originator: omp`; that is *its* identity, not ours, and is not copied.
 *
 * ## What this is NOT
 *
 * Nothing here touches the API-key path, the user-configured OpenAI-compatible
 * bearer path, or `OpenAIModelsApi.fetchModels`. Those keep calling
 * `/v1/models` exactly as before.
 */
object CodexModelsApi {
    private const val TAG = "CodexModelsApi"

    /**
     * Discovery route. Single-element on purpose — see the class comment: the
     * bare `/backend-api/models` sibling returns a 403 HTML block page, so
     * probing it would only add a wasted round trip and a misleading log line.
     */
    private const val MODELS_URL = "https://chatgpt.com/backend-api/codex/models"

    /**
     * Separate from the shared OkHttp default so a hung discovery call can
     * never stall a Refresh tap for minutes. Discovery is strictly optional —
     * every failure path here has a fallback — so it gets a short leash.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Namespaced away from `"openai"` so a Codex discovery result and an
     * API-key `/v1/models` result for the same account can never collide.
     */
    private val cache = ProviderModelsCache("codex-oauth")

    /** Outcome of one discovery attempt. */
    sealed interface Result {
        /** The backend answered and we parsed at least one visible model. */
        data class Success(val models: List<LLMModel>) : Result

        /**
          * The backend rejected the CREDENTIAL (401/403 JSON). Distinct from
          * [Failed] because the honest user-facing message differs: this one
          * means "sign in again", and per GH#319 must NOT be laundered into a
          * silent success that redisplays the previous list as if it were
          * fresh.
          */
        data class AuthFailed(val status: Int) : Result

        /**
         * Transport error, non-auth HTTP error, unparseable body, or a parsed
         * body with no visible models. All of these mean "we learned nothing",
         * which is the caller's cue to fall back — never to erase.
         */
        data class Failed(val reason: String) : Result
    }

    /**
     * Fetch the account's live model list.
     *
     * @param accessToken a CURRENT access token — refreshing is the OAuth
     *   manager's job and is done by the caller before we get here.
     * @param accountId `chatgpt-account-id`. Optional on the wire (its
     *   absence does not 401) and it is also the preferred cache partition —
     *   see [cacheKey] for what happens when it is missing.
     * @param forceRefresh true for a user-initiated Refresh: skips the cache
     *   READ entirely. GH#319 calls this out explicitly — a Refresh button that
     *   returns a cached list is not a refresh.
     */
    suspend fun fetchModels(
        accessToken: String,
        accountId: String?,
        clientVersion: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val key = cacheKey(accountId, accessToken, clientVersion)
        // hasAccountId, never the id itself. It gates BOTH the
        // chatgpt-account-id header and the cache partition, so when a refresh
        // mysteriously never caches this one line says why (see [cacheKey]).
        AppLogger.info(
            TAG,
            "discovery start: clientVersion=$clientVersion hasAccountId=${!accountId.isNullOrBlank()} force=$forceRefresh",
        )
        if (!forceRefresh && context != null) {
            cache.load(context, key)?.let {
                AppLogger.info(TAG, "discovery cache hit (${it.size} models)")
                return@withContext Result.Success(it)
            }
        }

        val url = "$MODELS_URL?client_version=$clientVersion"
        val builder = Request.Builder()
            .url(url)
            .get()
            // Same identity as the inference request — see the class comment.
            .header("Authorization", "Bearer $accessToken")
            .header("Version", clientVersion)
            .header("Openai-Beta", "responses=experimental")
            .header("User-Agent", "codex_cli_rs/$clientVersion (Android; arm64)")
            .header("Originator", "codex_cli_rs")
            .header("Accept", "application/json")
        accountId?.takeIf { it.isNotBlank() }?.let { builder.header("Chatgpt-Account-Id", it) }

        val (status, body) = try {
            client.newCall(builder.build()).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "discovery transport error: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.Failed("network: ${e.javaClass.simpleName}")
        }

        if (status == 401 || status == 403) {
            // Drop the cached catalog for this account: it was fetched under a
            // credential the backend now refuses, so continuing to serve it
            // would be exactly the "stale list presented as success" that
            // GH#319 asks us to avoid.
            if (context != null) cache.invalidate(context, key)
            AppLogger.warning(TAG, "discovery rejected credential: HTTP $status")
            return@withContext Result.AuthFailed(status)
        }
        if (status !in 200..299) {
            AppLogger.warning(TAG, "discovery HTTP $status: ${body.take(200)}")
            return@withContext Result.Failed("http $status")
        }

        val parsed = try {
            parseModels(body)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "discovery parse error: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.Failed("parse: ${e.javaClass.simpleName}")
        }

        if (parsed.isEmpty()) {
            AppLogger.warning(TAG, "discovery returned no visible models")
            return@withContext Result.Failed("empty")
        }

        val enriched = enrichPreservingFresh(parsed)
        AppLogger.info(
            TAG,
            "discovery OK (${enriched.size} models): ${enriched.joinToString { it.id }}",
        )
        if (context != null) cache.save(context, key, enriched)
        Result.Success(enriched)
    }

    /** Drop this account's cached catalog (e.g. on sign-out). */
    fun invalidate(context: Context, accountId: String?, accessToken: String, clientVersion: String) {
        cache.invalidate(context, cacheKey(accountId, accessToken, clientVersion))
    }

    /**
     * Cache partition. **Both** components matter:
     *   * account id — GH#319 requires that switching accounts must not reuse
     *     the other account's catalog, and the catalog is genuinely per-account
     *     (tier gates which models are returned);
     *   * client version — the backend filters by it, so a version bump must
     *     re-fetch rather than serve a list computed under the old gate.
     *
     * Null when there is no account id: an unpartitionable result is not
     * cached at all. Losing a cache entry costs one HTTP call; mixing two
     * accounts' catalogs costs the user's trust in the picker.
     */
    internal fun cacheKey(accountId: String?, accessToken: String, clientVersion: String): String {
        // Prefer the account id: it is stable across token refreshes, so the
        // cache survives them. Fall back to the token itself — verified
        // necessary, not hypothetical: on a real signed-in account here the
        // id_token carried no `chatgpt_account_id`, so an account-only key
        // would have disabled caching outright for that user.
        //
        // Either way the value is one-way hashed by ProviderModelsCache before
        // it becomes a filename, so no credential reaches disk. The token
        // fallback re-fetches once per refresh cycle, which is the correct
        // cost: two accounts can never collide, which is the property that
        // matters.
        val identity = accountId?.takeIf { it.isNotBlank() } ?: "tok:$accessToken"
        return "$identity|$clientVersion"
    }

    /**
     * Parse the discovery payload.
     *
     * Shape (confirmed against the upstream Codex catalog reference): a top
     * level `models` array, with `data` accepted as an alias since the same
     * backend family serves both spellings. Each entry:
     *
     * ```
     * { "slug"|"id", "display_name", "context_window", "max_context_window",
     *   "default_reasoning_level", "supported_reasoning_levels":[{"effort":…}],
     *   "input_modalities":[…], "visibility": "…", "priority": n }
     * ```
     *
     * Every field except the id is optional and defaulted — a discovery route
     * that adds or drops a field must degrade to a usable model, never to a
     * dropped one.
     */
    internal fun parseModels(body: String): List<LLMModel> {
        val root = JSONObject(body)
        val array = root.optJSONArray("models") ?: root.optJSONArray("data") ?: return emptyList()

        // priority ascending (the backend's own ranking), id as the tiebreak so
        // the picker order is stable across refreshes rather than reshuffling
        // whenever the server emits equal priorities in a different order.
        val ranked = mutableListOf<Pair<Int, LLMModel>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val model = parseEntry(obj) ?: continue
            ranked.add(obj.optInt("priority", Int.MAX_VALUE) to model)
        }
        return ranked
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }
    }

    private fun parseEntry(obj: JSONObject): LLMModel? {
        val id = obj.optString("slug").ifBlank { obj.optString("id") }.trim()
        if (id.isEmpty()) return null

        // GH#319: "隐藏/不可见的模型条目保持隐藏". The backend marks internal /
        // withdrawn SKUs here; surfacing one puts a model in the picker that
        // errors on first send.
        val visibility = obj.optString("visibility").trim().lowercase()
        if (visibility == "hide" || visibility == "hidden") return null

        val contextWindow = obj.optInt("context_window", 0).takeIf { it > 0 }
        val maxOutput = obj.optInt("max_output_tokens", 0).takeIf { it > 0 }

        return LLMModel(
            id = id,
            displayName = obj.optString("display_name").trim().ifEmpty { id },
            provider = "OpenAI",
            contextWindow = contextWindow,
            maxOutputTokens = maxOutput,
            supportsReasoning = parseReasoning(obj),
            inputModalities = obj.optJSONArray("input_modalities")
                ?.toStringList()
                .normalizeModalities(),
            // Deliberately NOT derived. The route describes inputs only; every
            // Codex chat model outputs text, and the one Codex model that
            // outputs images (gpt-image-2*) does not come from this route at
            // all — it is appended by OpenAIModelsApi with its own modalities.
            outputModalities = null,
            reasoningEffortValues = parseEffortTiers(obj),
        )
    }

    /**
     * A model reasons if it declares a default level other than "none", or if
     * any supported level offers a non-"none" effort. Checking BOTH matters:
     * a model can ship with reasoning off by default while still accepting it.
     *
     * Returns null rather than false when the payload says nothing, so
     * [enrichPreservingFresh] can let models.dev answer instead of pinning a
     * fabricated "does not reason" onto a model that does.
     */
    private fun parseReasoning(obj: JSONObject): Boolean? {
        val default = obj.optString("default_reasoning_level").trim().lowercase()
        if (default.isNotEmpty() && default != "none") return true

        val levels = obj.optJSONArray("supported_reasoning_levels")
        var sawLevels = false
        if (levels != null) {
            for (i in 0 until levels.length()) {
                val effort = when (val raw = levels.opt(i)) {
                    is JSONObject -> raw.optString("effort").trim().lowercase()
                    is String -> raw.trim().lowercase()
                    else -> ""
                }
                if (effort.isEmpty()) continue
                sawLevels = true
                if (effort != "none") return true
            }
        }
        // Only an affirmative "the backend listed levels and every one was
        // none" justifies false; silence stays unknown.
        return if (default == "none" || sawLevels) false else null
    }

    /**
     * The effort tiers the picker's Thinking control may offer, in the order
     * the backend listed them. Null when the payload declares none, so
     * enrichment can fill them in from models.dev.
     */
    private fun parseEffortTiers(obj: JSONObject): List<String>? {
        val levels = obj.optJSONArray("supported_reasoning_levels") ?: return null
        val out = LinkedHashSet<String>()
        for (i in 0 until levels.length()) {
            val effort = when (val raw = levels.opt(i)) {
                is JSONObject -> raw.optString("effort").trim().lowercase()
                is String -> raw.trim().lowercase()
                else -> ""
            }
            if (effort.isNotEmpty() && effort != "none") out.add(effort)
        }
        return out.toList().takeIf { it.isNotEmpty() }
    }

    /**
     * Fill gaps from models.dev WITHOUT letting it overwrite what the
     * authoritative route just told us.
     *
     * This is GH#319 requirement 3 ("不要用过期的 enrichment 数据覆盖来自权威端点
     * 的新鲜元数据") and it cannot be met by calling [ModelsDevApi.enrichModels]
     * directly: `applyDevData` resolves every field as `devModel.x ?: model.x`,
     * i.e. models.dev WINS wherever it has an opinion. That is the right rule
     * for a `/v1/models` response, which carries almost no metadata — but here
     * the response is the vendor's own registry for this very account, and a
     * community catalog lagging behind it would silently re-cap a freshly
     * widened context window (the 5.6 family's 272k→1M change is exactly this
     * shape of edit).
     *
     * So: enrich, then restore every field the route actually populated.
     * Fields the route left null keep the enriched value, which is the whole
     * point of still enriching.
     */
    internal fun enrichPreservingFresh(fresh: List<LLMModel>): List<LLMModel> =
        restoreAuthoritative(fresh, ModelsDevApi.enrichModels(fresh))

    /**
     * The restore half of [enrichPreservingFresh], split out so it can be
     * tested for real.
     *
     * Keeping it inline would have made the guard effectively untestable on
     * the JVM: `ModelsDevApi` reads its registry through an Android `Context`,
     * so in a unit test enrichment is a no-op and "authoritative values
     * survived" passes whether or not the guard exists. Taking the enriched
     * list as a parameter lets a test supply an enrichment that genuinely
     * disagrees — which is the only version of this check worth having.
     */
    internal fun restoreAuthoritative(
        fresh: List<LLMModel>,
        enriched: List<LLMModel>,
    ): List<LLMModel> {
        return fresh.zip(enriched) { authoritative, filled ->
            filled.copy(
                // NOT a plain "authoritative wins" for the two capacity
                // numbers — see [maxCapacity]. Everything else is.
                contextWindow = maxCapacity(authoritative.contextWindow, filled.contextWindow),
                maxOutputTokens = maxCapacity(authoritative.maxOutputTokens, filled.maxOutputTokens),
                supportsReasoning = authoritative.supportsReasoning ?: filled.supportsReasoning,
                inputModalities = authoritative.inputModalities ?: filled.inputModalities,
                reasoningEffortValues = authoritative.reasoningEffortValues
                    ?: filled.reasoningEffortValues,
                // displayName: see [preferredName]. Compared against our
                // hand-written label for this id, NOT against `filled` —
                // models.dev enrichment never touches displayName, so
                // `filled.displayName` is just the route's own string and
                // comparing the two would always be a no-op.
                displayName = preferredName(authoritative.id, authoritative.displayName),
            )
        }
    }

    /**
     * Capacity fields take the LARGER of the two, not the authoritative one.
     *
     * Found on-device, not reasoned about: refreshing a real ChatGPT account
     * moved every 5.6/6-family model from a 1,050,000 context window down to
     * 272,000. The backend registry genuinely reports the stale 272,000 —
     * OpenAI widened these SKUs to 1M without updating that field, which the
     * upstream Codex catalog documents and works around the same way (it
     * floors luna/sol/terra at 1M). So "the vendor's own endpoint is
     * authoritative" is true about WHICH models exist and false about how
     * large they are.
     *
     * Max, rather than a hardcoded per-slug floor, because the rule should
     * hold for the next family too: an under-report costs the user usable
     * context, while an over-report is bounded by the server rejecting the
     * request. Neither source is trusted to shrink a number the other one
     * vouches for.
     */
    private fun maxCapacity(fromRoute: Int?, fromCatalog: Int?): Int? = when {
        fromRoute == null -> fromCatalog
        fromCatalog == null -> fromRoute
        else -> maxOf(fromRoute, fromCatalog)
    }

    /**
     * Keep our own display name when the route's differs only in punctuation.
     *
     * Also found on-device: a successful discovery renamed "GPT-6 Astra" to
     * "GPT-6-Astra" and "GPT-5.6 Sol" to "GPT-5.6-Sol". The backend's
     * `display_name` is slug-derived, so for every model we already ship it is
     * strictly worse-looking than the hand-written label — and it silently
     * renamed models the user has been reading for months, in exchange for
     * nothing.
     *
     * The comparison strips separators and case, so this can ONLY suppress a
     * cosmetic difference. A genuinely different name still comes through, and
     * a NEW model — the whole point of this feature, and therefore absent from
     * [builtInNames] — keeps the route's name untouched.
     */
    private fun preferredName(id: String, fromRoute: String): String {
        val builtIn = builtInNames[id] ?: return fromRoute
        return if (cosmeticKey(fromRoute) == cosmeticKey(builtIn)) builtIn else fromRoute
    }

    /**
     * Our hand-written labels, by id. Read from the same list the fallback
     * tier serves, so the two tiers can never disagree about what a model is
     * called — a refresh must not rename anything just because it succeeded.
     */
    private val builtInNames: Map<String, String> by lazy {
        OpenAIModelsApi.fetchModelsOAuth().associate { it.id to it.displayName }
    }

    private fun cosmeticKey(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
