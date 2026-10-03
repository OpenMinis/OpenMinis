package com.openminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    /**
     * [T-fallback-5xx-status] [httpStatus] carries the real response code when
     * the error came from an HTTP response, null for errors that did not
     * (decoding, safety rejections, non-JSON image bodies). Structured on
     * purpose: the mappers format their messages three different ways
     * (`[503] …` for OpenAI JSON bodies, `HTTP 522: …` for non-JSON bodies,
     * `Gemini API error 500: …`, `[overloaded_error] …` for Anthropic), and
     * parsing the code back out of prose is exactly how a Cloudflare 522 with
     * an HTML body stopped triggering group fallback.
     */
    class ProviderError(val detail: String, val httpStatus: Int? = null) :
        LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited : LLMError("Rate limited — please try again later")
    /**
     * [T-android-503-fallback] Retry-worthy failure. [httpStatus] carries the
     * response code ONLY when the failure was a real HTTP response from the
     * provider; it stays null for failures that never got one — a dropped
     * connection, a TTFB timeout, an empty stream. That distinction is what
     * lets group fallback treat a provider-side 5xx as "this model is down,
     * try the next one" without also treating the user's own dead network as
     * a reason to switch models (a switch that could not possibly help).
     */
    class TransientError(val detail: String, val httpStatus: Int? = null) :
        LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /** Should immediately fall back to the next model in the group — same model won't help. */
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is ProviderError

    /**
     * [T-android-503-fallback] A confirmed HTTP 5xx from the provider, as a
     * structured status rather than a substring of the message.
     *
     * Two shapes carry one. [ProviderError] is how a 5xx that is known to be
     * permanent for this model arrives (e.g. OpenAI's 503 +
     * `no_available_providers`), and its status is only recoverable from the
     * `[503] …` prefix its mapper writes. [TransientError] carries the code
     * directly in [TransientError.httpStatus].
     *
     * Anchored parsing matters here: the previous check scanned the whole
     * detail with `[5][0-9]{2}`, so a message mentioning "5000 tokens" read as
     * a server error. Only the leading `[<code>]` written by `mapHttpError` is
     * accepted.
     */
    val httpServerErrorStatus: Int?
        get() = when (this) {
            is TransientError -> httpStatus?.takeIf { it in 500..599 }
            // Structured code first; the `[<code>]` prefix is only a fallback
            // for a ProviderError built somewhere that did not thread the
            // status through. It never matches a bare number inside prose.
            is ProviderError -> httpStatus?.takeIf { it in 500..599 }
                ?: LEADING_STATUS.find(detail)
                    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 500..599 }
            else -> null
        }

    /** True for a failure the provider answered with a 5xx status code. */
    val isHttpServerError: Boolean get() = httpServerErrorStatus != null

    private companion object {
        /**
         * Matches only a status code in the leading `[<code>]` position that
         * `mapHttpError` emits — never a number that merely appears somewhere
         * inside the provider's prose.
         */
        val LEADING_STATUS = Regex("""^\[(\d{3})\]""")
    }

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}
