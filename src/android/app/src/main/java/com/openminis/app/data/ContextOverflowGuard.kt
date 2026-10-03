package com.openminis.app.data

/**
 * [T-android-context-overflow-selfheal] (GH#352) Recognises the one provider
 * error a session cannot recover from on its own.
 *
 * When a request exceeds the model's context window the provider answers
 * `[400] maximum context length is 1048576 … requested ≈1090600 tokens`, and
 * because the offending content is already persisted in the session's history
 * the SAME request is rebuilt on every retry and every fallback model. The
 * session is wedged permanently; the reporter's only workaround was starting a
 * new chat.
 *
 * Compaction does not save it either: the content that overflowed is usually a
 * single oversized part, and compaction summarises text — it does not remove
 * the one part that is 100x everything else combined.
 *
 * So this is matched narrowly and acted on narrowly. It is matched on TEXT,
 * which is normally unreliable, but here the consequences are bounded in both
 * directions and there is no alternative signal:
 *
 *   - false positive: one oversized part is offloaded to disk and replaced by
 *     a stub naming the file. The content is not lost and the turn proceeds.
 *   - false negative: today's behaviour, unchanged.
 *
 * Deliberately NOT matched on status alone. A bare 400 covers malformed JSON,
 * content policy, unknown parameters — none of which offloading would fix, and
 * silently mutating history for them would be worse than the error.
 */
object ContextOverflowGuard {

    /** Statuses a context-overflow refusal arrives on. */
    private val OVERFLOW_STATUSES = setOf(400, 413)

    /**
     * Phrases that identify the refusal as being about LENGTH.
     *
     * Every entry names size explicitly; none of them can match a policy
     * refusal or a parameter complaint. Both English and the Chinese wording
     * relays commonly translate to are listed.
     */
    private val OVERFLOW_MARKERS = listOf(
        "maximum context length",
        "context length exceeded",
        "context_length_exceeded",
        "reduce the length of the messages",
        "too many tokens",
        "prompt is too long",
        "request too large",
        "exceeds the maximum",
        "input is too long",
        // [T-ctx-overflow-marker-parity] iOS ContextPolicy.overflowMarkers had
        // these two and Android did not: OpenMinis#133 arrives as "[400] Your
        // input exceeds the context window…", and missing it meant no ratio
        // raise, no GH#352 self-heal, and a pinned session silently falling
        // back to a different model. Keep this list identical to iOS.
        "exceeds the context window",
        "input exceeds the context",
        // Anthropic: "input length and `max_tokens` exceed context limit: A + B > W".
        "exceed context limit",
        "上下文长度",
        "超出最大长度",
        "内容过长",
    )

    /** Generic size phrases that also appear in a byte-size (not token) rejection. */
    private val BYTE_AMBIGUOUS_MARKERS = setOf("exceeds the maximum", "request too large")

    /**
     * True when [detail] on [status] is a context-overflow refusal, i.e. the
     * request was too big rather than wrong.
     */
    fun isContextOverflow(status: Int?, detail: String?): Boolean {
        if (status == null || status !in OVERFLOW_STATUSES) return false
        val text = detail?.lowercase() ?: return false
        val hits = OVERFLOW_MARKERS.filter { text.contains(it) }
        if (hits.isEmpty()) return false
        // [T-ctx-overflow-marker-parity] Anthropic's 413 "[request_too_large]
        // Request exceeds the maximum allowed number of bytes." is a BYTE-size
        // limit (usually images/attachments), not a token overflow: raising the
        // token calibration ratio for it would poison every later size judgement.
        // Its only hits are the generic size phrases, so on byte wording those
        // two do not count; a token-specific phrase still does. Same rule on iOS.
        if (text.contains("bytes")) return hits.any { it !in BYTE_AMBIGUOUS_MARKERS }
        return true
    }
}
