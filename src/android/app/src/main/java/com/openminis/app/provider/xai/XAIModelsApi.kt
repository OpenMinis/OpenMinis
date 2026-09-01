package com.openminis.app.provider.xai

import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi

/**
 * Built-in xAI (Grok) catalog: the SEED shown before the network answers,
 * and the FALLBACK when it does not.
 *
 * [T-provider-dynamic-catalog-reconcile] This is no longer the definitive
 * list. It used to be — `ProviderRepository.refreshModels` returned it
 * unconditionally for xAI, on the reasoning (from
 * /tmp/grok-oauth-design.md §6) that pinning a known-good set keeps Add
 * Provider off the network path. Seeding does that; pinning REFRESH too
 * meant a model released after the build could never appear, however many
 * times the user pressed Refresh (GH#265: grok-4.6 invisible).
 *
 * `api.x.ai/v1/models` is in fact live and OpenAI-compatible — an
 * unauthenticated GET returns 401 `{"code":"invalid-argument"…}`, an auth
 * challenge rather than a 404 — so refresh now performs a real fetch and
 * only falls back here when that yields nothing.
 *
 * The same set is the [LLMModel.allXAI] companion list — keep them in
 * sync. Returned through [ModelsDevApi.enrichModels] so context window
 * / modality metadata fills in from the shared catalog.
 */
object XAIModelsApi {
    private const val TAG = "XAIModelsApi"

    /** Static fallback used when /v1/models is unreachable or untrusted. */
    fun fetchModelsOAuth(): List<LLMModel> {
        val models = LLMModel.allXAI
        AppLogger.info(TAG, "xAI OAuth model list (${models.size} models): ${models.joinToString { it.id }}")
        return ModelsDevApi.enrichModels(models)
    }
}
