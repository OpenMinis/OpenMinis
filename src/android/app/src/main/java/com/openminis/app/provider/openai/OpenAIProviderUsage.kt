package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject

internal fun OpenAIProvider.parseChatCompletionsUsage(usage: JSONObject): LLMUsage {
    val promptTokens = usage.optInt("prompt_tokens", 0)
    // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens` instead
    // of the OpenAI-native `prompt_tokens_details.cached_tokens`. Mirrors
    // iOS OpenAIProvider.swift:629-630. Without this fallback, DeepSeek V4
    // looked like it never cached even when it did, masking T122's win.
    val cacheRead = usage.optJSONObject("prompt_tokens_details")
        ?.optInt("cached_tokens")?.takeIf { it > 0 }
        ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
    // OpenAI/DeepSeek `prompt_tokens` is the FULL input (cached + fresh), so
    // subtract the cached portion to keep `inputTokens` meaning fresh-only —
    // matching the Anthropic convention. Otherwise the cached tokens are
    // counted twice in `input + cacheRead` (deflates the cache-hit rate;
    // DeepSeek 99% hit showed as ~48%). Guard: only subtract when it stays
    // non-negative; no cache field (cacheRead == null) → unchanged.
    // latestContextTokens stays the full prompt (that IS the context size).
    val freshInput = cacheRead?.let { (promptTokens - it).takeIf { d -> d >= 0 } } ?: promptTokens
    return LLMUsage(
        inputTokens = freshInput,
        outputTokens = usage.optInt("completion_tokens", 0),
        cacheReadInputTokens = cacheRead,
        latestContextTokens = promptTokens,
    )
}

/** Parse usage from Responses API format. */
internal fun OpenAIProvider.parseResponsesAPIUsage(usage: JSONObject): LLMUsage {
    val inputTokens = usage.optInt("input_tokens", 0)
    // Responses API reports cache hits at `input_tokens_details.cached_tokens`
    // (distinct from Chat Completions' `prompt_tokens_details.cached_tokens`).
    // Mirrors iOS OpenAIProvider.swift:640. Without this, even a perfectly
    // cached Responses-API request showed cacheRead=0 in usage stats —
    // making T126's prompt_cache_key wiring look like it had no effect.
    val cacheRead = usage.optJSONObject("input_tokens_details")
        ?.optInt("cached_tokens")?.takeIf { it > 0 }
    // `input_tokens` is the FULL input (cached subset included); subtract the
    // cached portion so `inputTokens` is fresh-only, matching Anthropic — else
    // the cache is counted twice in `input + cacheRead` (deflates hit rate).
    // Guard: only subtract when non-negative; no cache field → unchanged.
    // latestContextTokens stays the full input (that IS the context size).
    val freshInput = cacheRead?.let { (inputTokens - it).takeIf { d -> d >= 0 } } ?: inputTokens
    return LLMUsage(
        inputTokens = freshInput,
        outputTokens = usage.optInt("output_tokens", 0),
        cacheReadInputTokens = cacheRead,
        latestContextTokens = inputTokens,
    )
}
