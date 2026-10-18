package com.openminis.app.data.model

/**
 * [T-android-cross-api-tool-id] The one authority for a tool-call id's WIRE form.
 *
 * ## Why this exists
 *
 * A conversation can change provider mid-flight — the user switches model, a
 * group fails over to another member, a scheduled task reopens a session under
 * a different instance. The ids stored in that history were minted by whichever
 * API style produced them, and the styles do not agree on what an id may look
 * like:
 *
 * | origin | shape | what the next endpoint may reject |
 * | --- | --- | --- |
 * | OpenAI Responses | `call_…` / `call_…\u007cfc_…` | the `|` half, ids > 64 chars |
 * | OpenAI Chat | `call_…` | ids > 64 chars ("maximum length 64") |
 * | Anthropic | `toolu_01…` | anything outside `^[a-zA-Z0-9_-]+$` |
 * | Gemini | `call_…` | ids it never issued (no `thought_signature`) |
 * | relays / custom endpoints | anything, e.g. `functions.Read:0` | dots, colons, `|`, long ids |
 *
 * Before this file each provider carried its own partial fix: Anthropic had
 * `sanitizeToolId` (charset only), the Chat-Completions body builder had
 * `capChatToolCallId` (length only), the Responses body builder had a bare
 * 64-char truncation. So a session that came from a relay with `a.b` in an id
 * reached an Anthropic turn fine but an OpenAI turn with the dot still in it,
 * and a 200-char id reached Anthropic untouched. A session that switched the
 * other way lost the id outright to a blind `substring(0, 64)`.
 *
 * ## Contract
 *
 * [normalize] is a pure, deterministic, idempotent function. Determinism is not
 * cosmetic: a tool call and its result are two separate message parts carrying
 * the SAME raw id, and both must map to the same wire id or the request 400s
 * with "unexpected tool_use_id" / "tool_call_id not found". Idempotence means
 * re-normalizing an already-normalized id (a retried request, a replayed
 * history) cannot drift.
 *
 * The mapping is:
 *  1. keep the call-id half before any `|` — OpenAI Responses' combined
 *     `call_…|fc_…` form must degrade to the call half, which is the id the
 *     paired tool result refers to;
 *  2. replace every character outside `[A-Za-z0-9_-]` with `-` (Anthropic's
 *     accepted alphabet, and a safe subset for every OpenAI-compatible relay);
 *  3. an empty result becomes `tool_<hash>`, because ANTHROPIC rejects "";
 *  4. ids longer than [MAX_WIRE_ID_LENGTH] keep a prefix and gain a stable
 *     SHA-256 suffix, so truncation cannot collide for ids that differ late.
 */
object ToolIdCompat {

    /**
     * Longest id every endpoint in the fleet accepts. OpenAI Chat-Completions
     * documents 64 for `tool_calls[].id` and 400s above it; Anthropic has no
     * documented ceiling but rejects megabyte ids with a 413 on the body.
     */
    const val MAX_WIRE_ID_LENGTH = 64

    /** How many hex chars of the digest the capped form keeps. */
    private const val HASH_CHARS = 8

    /**
     * Rewrite [raw] into an id that is safe on every endpoint we talk to.
     * Returns [raw] itself when it was already safe — callers can compare
     * identity to know whether a rewrite happened.
     */
    fun normalize(raw: String): String {
        val callHalf = raw.substringBefore('\u007c')
        val sanitized = StringBuilder(callHalf.length)
        var changed = false
        for (ch in callHalf) {
            val keep = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-'
            sanitized.append(if (keep) ch else '-')
            if (!keep) changed = true
        }
        val body = sanitized.toString()
        val base = if (body.isBlank()) "tool_" + digest(body.ifEmpty { callHalf }) else body
        if (base.length <= MAX_WIRE_ID_LENGTH) {
            return if (!changed && base == raw) raw else base
        }
        // Keep the leading prefix (it is what a human reading a log or the
        // request dump recognises) and append a digest of the FULL id.
        val keep = MAX_WIRE_ID_LENGTH - HASH_CHARS - 1
        return base.take(keep) + "-" + digest(callHalf)
    }

    /** Stable 8-hex digest; SHA-256 so it does not vary across JVM runs. */
    private fun digest(text: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(HASH_CHARS)
        for (i in 0 until HASH_CHARS / 2) {
            val b = bytes[i].toInt() and 0xFF
            hex.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"
}
