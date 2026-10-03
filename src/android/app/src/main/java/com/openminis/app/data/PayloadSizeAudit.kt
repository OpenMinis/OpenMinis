package com.openminis.app.data

/**
 * [T-android-payload-size-audit] (GH#352) A byte-side cross-check on the
 * token-side estimate, for the one case where the two disagree catastrophically.
 *
 * Why this exists. Two budgets guard the outgoing request and they are
 * denominated differently:
 *
 *   - [com.openminis.app.provider.ImageBudget] counts BYTES (25 MB request cap).
 *   - [BPETokenizer.countImageTokens] counts TOKENS, using the vision grid-tile
 *     heuristic — area / 32², a few thousand tokens for a large photo.
 *
 * Both are right when an image travels as a structured `image_url` block: a
 * 1 MB photo really is ~1-3k tokens to a vision model, and 1.37 MB of base64 is
 * nowhere near 25 MB. But if that same image is measured as TEXT anywhere
 * downstream, its true cost is ~960k tokens — and no gate in the app sees that,
 * because every gate is looking at a number three orders of magnitude smaller.
 *
 * Field report (GH#352): a 1,030,449-byte PNG produced
 * `[400] maximum context length is 1048576 … requested ≈10906xx tokens`, and
 * the reporter's own A/B measured the same image at 267 tokens via image_url
 * and 959,936 tokens via a text channel. Nothing in this codebase encodes an
 * image as text (every emit site uses `image_url` / `input_image`), so the
 * mismatch is believed to arise downstream — but the app's inability to NOTICE
 * it is ours, and that is what this fixes.
 *
 * The rule is deliberately a ratio, not a fixed ceiling. A fixed byte cap would
 * have to be tuned per provider and would fire on legitimately large requests;
 * a ratio only fires when the two measurements disagree by so much that one of
 * them must be wrong. Normal content cannot trip it: prose is ~3.5 bytes per
 * token, JSON tool input maybe 6-8, so even pathological text stays far under
 * [SUSPICIOUS_BYTES_PER_TOKEN]. Base64 image bytes measured against a
 * grid-tile token estimate land around 500-800 bytes/token.
 */
object PayloadSizeAudit {

    /**
     * Bytes-per-estimated-token above which a part is reported as suspicious.
     *
     * 64 is ~18x the ratio of ordinary prose and ~8x the worst realistic JSON,
     * so it cannot fire on normal content, while the failure it looks for sits
     * an order of magnitude beyond it. Chosen to make a false positive
     * essentially impossible, since the consequence of firing is a log line the
     * next investigation will trust.
     */
    const val SUSPICIOUS_BYTES_PER_TOKEN = 64

    /** Parts smaller than this are never reported — the ratio is noisy on tiny values. */
    const val MIN_BYTES_TO_AUDIT = 32 * 1024

    /**
     * Fraction of the context window at which a single part is considered
     * dangerous on its own, measured in BYTES-as-if-text.
     *
     * Deliberately conservative: at 0.5 a part must be big enough to consume
     * half the window by itself before anything acts on it, which no ordinary
     * message approaches.
     */
    const val DANGEROUS_WINDOW_FRACTION = 0.5

    /**
     * What the audit concluded about one part.
     *
     * @param bytes serialized size of the part's payload.
     * @param estimatedTokens what the app's own estimator thinks it costs.
     * @param bytesPerToken the disagreement ratio, the thing worth logging.
     * @param suspicious the two measurements disagree beyond [SUSPICIOUS_BYTES_PER_TOKEN].
     * @param dangerous this part alone could plausibly exhaust the window if
     *   measured as text downstream.
     */
    data class Finding(
        val bytes: Int,
        val estimatedTokens: Int,
        val bytesPerToken: Int,
        val suspicious: Boolean,
        val dangerous: Boolean,
    )

    /**
     * Audit one part.
     *
     * @param contextWindowTokens the model's window, for the danger test; pass
     *   0 or less when unknown, which disables that half (an unknown window is
     *   no basis for calling something dangerous).
     */
    fun audit(bytes: Int, estimatedTokens: Int, contextWindowTokens: Int): Finding {
        if (bytes < MIN_BYTES_TO_AUDIT) {
            return Finding(bytes, estimatedTokens, 0, suspicious = false, dangerous = false)
        }
        // A zero/negative estimate means the estimator had no opinion; treat it
        // as 1 so the ratio stays defined and errs toward reporting.
        val safeTokens = estimatedTokens.coerceAtLeast(1)
        val ratio = bytes / safeTokens
        val suspicious = ratio >= SUSPICIOUS_BYTES_PER_TOKEN
        // Bytes are compared against the window as if each byte were ~1 token,
        // which is the worst case this audit exists to catch (base64 measured
        // as text runs ~1.4 chars/token, so this is if anything lenient).
        val dangerous = contextWindowTokens > 0 &&
            bytes.toLong() >= (contextWindowTokens * DANGEROUS_WINDOW_FRACTION).toLong()
        return Finding(bytes, estimatedTokens, ratio, suspicious, dangerous)
    }
}
