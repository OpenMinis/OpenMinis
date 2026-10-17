package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.CredentialAcceptance
import com.openminis.app.provider.HttpRetryAfter
import com.openminis.app.provider.safeOptString
import org.json.JSONObject

internal fun OpenAIProvider.mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError {
    // [T-zen-structured-error] OpenCode Zen answers with a structured body
    // ({"type":"error","error":{"type":"FreeTierError",…}}) whose type carries
    // semantics the generic HTTP mapping cannot see. FreeTierError means the
    // request was refused as a non-official client; ModelError means the
    // catalogue lists the id but the server refuses to serve it; RegionError
    // means the model is geo-fenced. All three are provider refusals where
    // "Invalid API key" is simply false, and each has a hint worth showing.
    //
    // [T-zen-sniffer-host-gate] GATED ON THE ZEN HOST, deliberately. This
    // function is the single error mapper for EVERY OpenAI-compatible provider,
    // and `ModelError` / `RegionError` are generic type names — a relay or a
    // self-hosted vLLM that happens to answer `{"type":"error","error":
    // {"type":"ModelError"}}` would otherwise be captured by Zen-specific
    // wording ("geo-fenced upstream", "Refresh the model list") that describes
    // a service it knows nothing about. Only the Zen endpoint speaks this
    // dialect, so only the Zen endpoint gets this mapping; every other host
    // keeps the generic path it had before.
    if (com.openminis.app.provider.ZenEndpoint.isZenHost(basePath)) {
        zenStructuredProviderRefusal(body)?.let { return it }
    }
    if (statusCode == 401 || statusCode == 403) {
        // [T-llm-error-401-model-scope] A gateway fronting an upstream pool
        // answers 401 for failures in the channel IT picked, not for anything
        // wrong with the caller's key. When this same credential was accepted by
        // this same host recently, "Invalid API key" is simply false, and its
        // hint sends the user off to regenerate a key that works. Measured: one
        // relay served 200 for a single model and 401 for the other four — same
        // key, same minute, deterministic per model — while its own /v1/models
        // returned 200 throughout.
        //
        // No acceptance evidence falls through to the historical mapping below,
        // so nothing changes for a credential the app has never seen succeed.
        if (CredentialAcceptance.isAccepted(credentialGateKey)) {
            return LLMError.InvalidApiKey(
                "HTTP $statusCode — the same credential was accepted by this host recently, " +
                    "so the refusal is about this model, not the key",
                credentialAccepted = true,
            )
        }
    }
    if (statusCode == 401) return LLMError.InvalidApiKey()
    if (statusCode == 403) {
        // [T-llm-error-403] OpenRouter / self-hosted gateways answer 403 for
        // "valid key, but this model or region is not allowed on your plan".
        // Folding that into a bare InvalidApiKey() told users to check an API
        // key that is actually fine. The detail drives both the message and
        // the actionableHint's dedicated 403 branch.
        return LLMError.InvalidApiKey(
            "HTTP 403 forbidden — the credential was accepted but this model or region is not permitted on the current plan"
        )
    }
    if (statusCode == 429) return HttpRetryAfter.map429(body, retryAfterHeader)

    val message = try {
        val json = JSONObject(body)
        val error = json.optJSONObject("error")
        val errorMessage = error?.safeOptString("message", "") ?: body
        "[$statusCode] ${maskSecrets(errorMessage)}"
    } catch (_: Exception) {
        "HTTP $statusCode: ${maskSecrets(body.take(500))}"
    }

    // [T-llm-error-413] Payload Too Large is the request-size twin of
    // context_length_exceeded — route it to the actionable class (compact /
    // offload hint) instead of a generic, non-retryable ProviderError.
    if (statusCode == 413) return LLMError.ContextLengthExceeded(message)

    // [T-llm-error-classification] 细分 OpenAI 400 类错误，不再笼统地归 ProviderError。
    val lower = message.lowercase()
    when {
        // "max_tokens too large" 是输出参数超限（max_tokens 请求过大），
        // 不是上下文超长，保持 ProviderError。
        !lower.contains("max_tokens too large") && !lower.contains("max_output_tokens") && (
            lower.contains("context_length_exceeded") || lower.contains("maximum context length") ||
            (statusCode == 400 && (
                lower.contains("too many tokens") ||
                lower.contains("reduce the length") ||
                // [T-llm-error-413-note] Excludes output-parameter errors
                // (max_output_tokens mismatches) — a request-param problem is
                // not the context-window problem this class's hint assumes.
                (lower.contains("token") && (lower.contains("max") || lower.contains("limit") || lower.contains("exceed")))
            ))
        ) -> return LLMError.ContextLengthExceeded(message)
        lower.contains("content_filter") || lower.contains("content_policy") ||
        lower.contains("safety") || statusCode == 422 && lower.contains("content") ->
            return LLMError.ContentFiltered(message)
    }

    val transientCodes = setOf(500, 502, 503, 504, 529)
    if (statusCode in transientCodes) {
        // 503 with permanent failure indicators → ProviderError (trigger group fallback)
        if (statusCode == 503 && HttpRetryAfter.isPermanentCapacityBody(body)) {
            return LLMError.ProviderError(message)
        }
        return LLMError.TransientError(message)
    }
    return LLMError.ProviderError(message)
}

internal fun OpenAIProvider.mapError(error: Throwable): LLMError = mapThrowableToLLMError(error)

/**
 * [T-zen-structured-error] Parses an OpenCode Zen structured refusal body —
 * `{"type":"error","error":{"type":"FreeTierError"|"ModelError"|"RegionError",
 * "message":…}}` — into a precise [LLMError]; null when the body is not one, so
 * generic OpenAI bodies flow to the historical mapping untouched. The upstream
 * message is user-visible verbatim, so it goes through [maskSecrets] like every
 * other server-provided string in this file.
 */
internal fun zenStructuredProviderRefusal(body: String): LLMError? {
    val json = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    if (json.optString("type", "") != "error") return null
    val error = json.optJSONObject("error") ?: return null
    val type = error.optString("type", "")
    val message = error.safeOptString("message", "").orEmpty()
    return when (type) {
        // [T-zen-free-lane-retired] Measured 2026-10-17: this refusal is not a
        // header problem and not a key problem — the official 2.0.32 CLI answers
        // 200 on the same models from the same machine, while the CLI's own
        // header set, replayed from curl / Node / Python / Chromium, answers 403
        // on every model over both HTTP/1.1 and HTTP/2. The keyless lane this
        // app used to ship is therefore retired, and the honest instruction is
        // "bring your own Zen key".
        "FreeTierError" -> LLMError.ProviderError(
            "FreeTierError: ${maskSecrets(message.ifBlank { "free tier refused" })} — " +
                "upstream only accepts requests from the official OpenCode client, so the " +
                "keyless free lane is no longer usable here. Add your own OpenCode Zen API " +
                "key in this provider's settings, or switch provider.",
        )
        "RegionError" -> LLMError.ProviderError(
            "RegionError: ${maskSecrets(message.ifBlank { "not available in your country" })} — " +
                "this model is geo-fenced upstream and cannot be reached from your " +
                "current location. Try another model or another provider.",
        )
        "ModelError" -> LLMError.ProviderError(
            "ModelError: ${maskSecrets(message.ifBlank { "model refused by server" })} — " +
                "the server's catalogue lists this id but refuses to serve it. " +
                "Refresh the model list, or pick another model.",
        )
        else -> null
    }
}

/** Receiver-free so the unit tests can exercise the mapping directly. */
internal fun mapThrowableToLLMError(error: Throwable): LLMError {
    if (error is LLMError) return error
    // [T-llm-error-tls] TLS / certificate failures are permanent
    // misconfigurations (clock skew, MITM proxy, untrusted CA). They are
    // IOExceptions, so the old mapping filed them under retryable
    // NetworkError and burned the full retry budget on a failure no retry
    // can fix — and then the actionable hint told the user to "check your
    // internet connection".
    if (error is javax.net.ssl.SSLException) {
        return LLMError.ProviderError("TLS error: ${error.message ?: error.javaClass.simpleName}")
    }
    if (error is java.io.IOException) return LLMError.NetworkError(error)
    return LLMError.Unknown(error)
}

/**
 * [T-llm-error-secret-mask] Upstream error bodies occasionally echo parts of
 * the request — and with them key-shaped material. Nothing in
 * [LLMError.message] should ever be able to carry a credential into the chat
 * UI (message is truncated to 160 chars for display, but truncation is not
 * redaction).
 *
 * [T-secret-mask-single-source] The patterns moved to
 * [com.openminis.app.util.SecretMasking] so the context-assembly snapshot can
 * use the same rule. This stays as the local name the call sites and
 * LLMErrorClassificationTest already use.
 */
internal fun maskSecrets(text: String): String =
    com.openminis.app.util.SecretMasking.mask(text)
