package com.openminis.app.provider

import android.content.Context
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.network.ConnectionWarmup
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider

object ProviderFactory {
    /**
     * [T-android-provider-memo] Memoized provider construction.
     *
     * Per send, `buildFallbackProviders` constructs one provider per group
     * candidate and the sub-agent lanes construct one per entry — and each
     * OpenAIProvider/AnthropicProvider carries its own OkHttpClient (Dispatcher
     * included; only the connection pool is shared). With a 14-member group that
     * was up to 14 fresh clients per turn, every turn. The memo keys on
     * everything that shapes the client: instance identity + full instance
     * state (data-class hashCode covers baseURL/UA/azure/responses flags),
     * resolved model state, and a fingerprint of the credential so a rotated
     * OAuth token yields a fresh provider. Config writes call [invalidateAll]
     * via ProviderRepository.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, LLMProvider>()

    // [T-provider-factory-race] Bump on every invalidation. The old
    // check-then-act (cache miss → slow build → put) could interleave with
    // invalidateAll(): thread A misses, a config write clears the map, A
    // publishes a provider built on the PRE-invalidation credential — and it
    // lives forever after. The put now only lands when the generation is
    // unchanged; a stale build is returned to its caller but never cached.
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)

    private const val CACHE_CAP = 64

    fun create(instance: ProviderInstance, apiKey: String, model: LLMModel, context: Context? = null): LLMProvider {
        val key = instance.id + "|" + model.hashCode() + "|" +
            ProviderKeyGate.fingerprint(apiKey) + "|" + instance.hashCode()
        cache[key]?.let { return it }
        val genAtEntry = generation.get()
        val built = createUncached(instance, apiKey, model, context)
        // Build runs OUTSIDE the lock (OkHttpClient construction is slow); the
        // generation re-check + cap + put run INSIDE it, and every invalidator
        // bumps the generation under the same lock. Without that, a check-then-put
        // straddling invalidateAll() could still publish a stale provider: check
        // passes (gen unchanged), invalidation lands, put resurrects the entry
        // the invalidation was meant to kill.
        synchronized(cache) {
            if (generation.get() != genAtEntry) return built
            if (cache.size >= CACHE_CAP) cache.clear()
            cache[key] = built
        }
        return built
    }

    /** Drop every memoized provider. Called on config writes with no narrower key. */
    fun invalidateAll() {
        synchronized(cache) {
            generation.incrementAndGet()
            cache.clear()
        }
    }

    /**
     * Drop only one instance's memoized providers. Keys start with the
     * instance id followed by `|`, so the prefix match cannot bleed into
     * another instance (ids are UUID-shaped; the delimiter ends the match).
     * [T-provider-factory-race] Coarse [invalidateAll] on every config write
     * — including per-image-generation `setImageEndpointResolved` — churned
     * all 64 entries and rebuilt every OkHttpClient; instance-scoped
     * invalidation keeps unrelated instances warm.
     */
    fun invalidateInstance(instanceId: String) {
        if (instanceId.isBlank()) return
        val prefix = instanceId + "|"
        synchronized(cache) {
            generation.incrementAndGet()
            cache.keys.removeAll { it.startsWith(prefix) }
        }
    }

    /**
     * Remove entries whose instance is no longer in [liveInstanceIds] —
     * the config-persist counterpart of [invalidateInstance]: a RESHAPED
     * instance already lands on a new key (the key carries the full
     * instance hashCode), so only deletions need explicit cleanup.
     */
    fun pruneToLive(liveInstanceIds: Set<String>) {
        synchronized(cache) {
            generation.incrementAndGet()
            cache.keys.removeAll { key -> key.substringBefore('|') !in liveInstanceIds }
        }
    }

    /**
     * Create a provider, optionally with OAuth support.
     * [context] is needed for OpenAI OAuth to access encrypted storage for token refresh.
     */
    private fun createUncached(instance: ProviderInstance, apiKey: String, model: LLMModel, context: Context? = null): LLMProvider {
        // T174: route through ProviderInstance.effectiveBaseURL instead of
        // re-implementing the trim-+-endsWith dance inline. The previous
        // version did `url.endsWith("/v1")` on the raw, untrimmed string,
        // so a customBaseURL of "https://api.deepseek.com/v1/" (trailing
        // slash) failed the check and the code appended a second "/v1",
        // producing requests to "/v1//v1/chat/completions" → HTTP 404.
        // Likewise "https://api.deepseek.com/" was concatenated as-is to
        // ".../" + "/v1/chat/completions" = ".//v1/chat/completions",
        // which DeepSeek tolerated only by accident. effectiveBaseURL
        // trimEnd('/')'s the input first, so all four customBaseURL
        // shapes (no slash, trailing slash, /v1, /v1/) now collapse to
        // the same canonical "https://host/v1" string. The /chat/
        // completions endpoint suffix at OpenAIProvider.kt:710 then
        // produces a single-slash join.
        val basePath = instance.effectiveBaseURL
        val provider: LLMProvider = when (instance.providerType) {
            ProviderType.anthropic -> {
                val isOAuth = instance.credentialType == ProviderCredential.oauth
                // [T-provider-custom-user-agent] Only meaningful for custom-base
                // (relay) instances; on the official direct path it's null.
                if (basePath != null) AnthropicProvider(apiKey, model, basePath, isOAuth = isOAuth, customUserAgent = instance.customUserAgent)
                else AnthropicProvider(apiKey, model, isOAuth = isOAuth)
            }
            ProviderType.gemini -> {
                if (basePath != null) GeminiProvider(apiKey, model, basePath)
                else GeminiProvider(apiKey, model)
            }
            // [T-android-provider-type-parity] openAIResponses shares this
            // branch: on iOS it is "OpenAI with forceResponsesAPI = true", and
            // the Responses endpoint is already reachable here through the
            // instance's useResponsesAPI flag (forced on below for this type).
            ProviderType.openAI, ProviderType.openAIResponses -> {
                // Manual bearer token (set via Manual Bearer Token UI / imported
                // from JSON) bypasses the Codex OAuth flow entirely and is sent
                // verbatim as `Authorization: Bearer …` against api.openai.com
                // (or the user's custom base URL). Mirrors iOS LLMProviderFactory:
                // a manual token routes through the API-key constructor, not the
                // OAuth (Codex Responses) constructor — so requests go to the
                // standard Chat Completions endpoint instead of chatgpt.com's
                // codex backend (which only accepts real ChatGPT session tokens).
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null

                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && basePath == null && context != null) {
                    // Codex OAuth mode — Responses API with refresh-aware token provider
                    val oauthManager = OpenAIOAuthManager(context, instance.id)
                    OpenAIProvider(
                        oauthTokenProvider = { oauthManager.validAccessToken() ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey() },
                        model = model,
                        codexAccountId = oauthManager.accountId,
                    )
                } else {
                    // API key, or manual OAuth bearer (with or without custom
                    // base URL). The user can flip `useResponsesAPI` on a
                    // per-instance basis when the backend only speaks
                    // /v1/responses.
                    val base = basePath ?: "https://api.openai.com/v1"
                    val effectiveKey = if (!manualBearer.isNullOrEmpty()) manualBearer else apiKey
                    OpenAIProvider(
                        apiKey = effectiveKey,
                        model = model,
                        basePath = base,
                        // [T-android-provider-type-parity] The dedicated
                        // Responses type forces the endpoint regardless of the
                        // per-instance flag — that IS its definition, and an
                        // instance imported from iOS carries no Android-side
                        // useResponsesAPI value to have set.
                        useResponsesAPI = instance.useResponsesAPI ||
                            instance.providerType == ProviderType.openAIResponses,
                        // [T-provider-custom-user-agent] Covers both chat and
                        // /responses for custom-base OpenAI-compat relays; null
                        // on the official direct path.
                        customUserAgent = instance.customUserAgent,
                        // [T-android-azure-openai] Azure auths with api-key +
                        // deployments-path URL. Pass the RAW customBaseURL (not
                        // the /v1-appended, query-stripped effectiveBaseURL) so
                        // azureUrl() can preserve the ?api-version query.
                        isAzure = instance.azureMode,
                        azureBase = instance.customBaseURL,
                    )
                }
            }
            ProviderType.openRouter -> {
                // OpenRouter uses OpenAI-compatible API with custom base URL and headers
                OpenAIProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://github.com/OpenMinis/OpenMinis",
                        "X-Title" to "Minis App",
                    ),
                )
            }
            ProviderType.xAI -> {
                // xAI exposes an OpenAI-compatible /v1/chat/completions
                // endpoint at api.x.ai/v1. Two credential modes:
                //   - OAuth (SuperGrok / X Premium+): bearer fetched via
                //     XAIOAuthManager.validAccessToken() — refresh-aware.
                //   - Manual API key: passed through verbatim. Some users
                //     prefer this when their tier doesn't expose OAuth API
                //     access (the spec's known-issue 403 case).
                val base = basePath ?: "https://api.x.ai/v1"
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null
                // [T-android-xai-priority] Mark this provider as eligible for
                // Priority Processing. This is a CAPABILITY flag only — whether
                // the tier is actually requested is the user's global Fast Mode
                // toggle (FastModePrefs), which the body builders read at
                // request time. Set ONLY here, so the xAI-specific
                // `service_tier` key can never leak into another vendor's body;
                // a strict OpenAI-compatible relay 400s on unknown keys.
                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && context != null) {
                    val oauthManager = com.openminis.app.auth.XAIOAuthManager(context, instance.id)
                    OpenAIProvider.oauthOpenAICompat(
                        oauthTokenProvider = {
                            oauthManager.validAccessToken()
                                ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                        },
                        model = model,
                        basePath = base,
                    ).also { it.supportsPriorityProcessing = true }
                } else {
                    val effectiveKey = if (!manualBearer.isNullOrEmpty()) manualBearer else apiKey
                    OpenAIProvider(
                        apiKey = effectiveKey,
                        model = model,
                        basePath = base,
                    ).also { it.supportsPriorityProcessing = true }
                }
            }
            ProviderType.kimiCode -> {
                // [T-kimi-oauth] Kimi Coding Plan — OpenAI-compatible upstream.
                // ⚠️ The `/v1` is load-bearing: /coding/chat/completions 404s;
                // only /coding/v1/chat/completions works (verified live on iOS).
                // Custom bases go through effectiveBaseURL's /v1-append logic.
                val base = basePath ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null
                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && context != null) {
                    val oauthManager = com.openminis.app.auth.KimiOAuthManager(context, instance.id)
                    // Same path as xAI OAuth: Bearer token provider +
                    // forceChatCompletions so the Codex Responses backend
                    // shaping never applies. No custom UA / extra headers —
                    // iOS sends none either.
                    OpenAIProvider.oauthOpenAICompat(
                        oauthTokenProvider = {
                            oauthManager.validAccessToken()
                                ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                        },
                        model = model,
                        basePath = base,
                    )
                } else {
                    // Manual Moonshot API key (or manual bearer) path.
                    val effectiveKey = if (!manualBearer.isNullOrEmpty()) manualBearer else apiKey
                    OpenAIProvider(
                        apiKey = effectiveKey,
                        model = model,
                        basePath = base,
                    )
                }
            }
            // [T-android-provider-type-parity] Types this build can decode and
            // display but not drive. Reaching here means the user selected a
            // model on an instance restored from another platform (or a newer
            // build) whose provider Android cannot speak. Fail with a clear
            // credential error rather than constructing a provider that would
            // emit malformed requests. iOS throws FactoryError here likewise.
            ProviderType.antigravity, ProviderType.unsupported -> {
                throw com.openminis.app.data.model.LLMError.InvalidApiKey()
            }
        }
        // [T-android-thinking-rules-phase2] Tag OpenAI-family providers with their
        // owning instance id so the thinking resolver can look up this instance's
        // user-authored custom rules. Only OpenAIProvider consults the resolver's
        // custom-rule path (Gemini/Anthropic use their own emitters), so this is the
        // only type that needs it.
        (provider as? OpenAIProvider)?.thinkingRuleInstanceId = instance.id
        SamplingIdentity.bind(provider, instance.id)
        // [T-llm-connection-warmup] This constructor path only runs on a
        // provider-cache miss (cold start, provider/model switch) — exactly
        // the moments where the FIRST request would otherwise pay the full
        // DNS+TLS handshake inline. basePath is the effective API base
        // (null on the official direct path); ConnectionWarmup resolves the
        // host and fires a debounced HEAD into the shared pool.
        ConnectionWarmup.warmAsync(basePath, instance.providerType)
        return provider
    }
}
