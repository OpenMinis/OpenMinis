package com.openminis.app.tools

/**
 * [T-tool-output-policy] Unified post-processing for every tool result
 * before it reaches the model.
 *
 * 1. Environment-variable redaction (Kelivo-equivalent): any guest env var
 *    value of 6+ chars containing both letters and digits is replaced with
 *    `[REDACTED]` — the agent gets to *see* tool output without learning
 *    the API keys / tokens that happened to leak into it.
 * 2. Oversized output: DELEGATED to [ToolOutputSpill], which resolves the spill
 *    file PER SESSION (host dir the app can write, guest path the model can
 *    `file_read`).
 *
 * [T-tool-output-single-mechanism] This object used to carry its OWN second
 * spill implementation writing into `File(workspaceRoot, "tool_outputs")` with
 * `var workspaceRoot = "/var/minis/workspace"` — a GUEST path. The app process
 * is not the guest and cannot create it, so `mkdirs()` + `writeText` failed on
 * every call, `apply()` hit its `if (!written) return text` branch and returned
 * the UNCAPPED body: the 12k limit never applied to anything, and the two
 * parallel mechanisms (this one + [ToolOutputSpill], which does resolve the
 * session and was the only one that actually spilled) duplicated the threshold,
 * the preview format and the file naming. Redaction stays here; capping and
 * spilling are now one code path in [ToolOutputSpill], and a spill that cannot
 * happen still yields a bounded preview.
 */
object ToolOutputPolicy {
    private const val REDACTED = "[REDACTED]"

    /**
     * substring() 会在 UTF-16 码元边界切割，正好落在代理对中间就会产生
     * 半个 emoji（孤立的高低代理符）。这里把边界回退到码点边界，
     * 保证 head/tail 预览不会切碎任何非 BMP 字符。
     *
     * [T-tool-output-single-mechanism] internal because [ToolOutputSpill] — the
     * one preview builder now — slices its head/tail through this.
     */
    internal fun safeSubstring(s: String, start: Int, endExclusive: Int): String {
        val startIdx = start.coerceIn(0, s.length)
        val endIdx = endExclusive.coerceIn(startIdx, s.length)
        if (startIdx == endIdx) return ""
        // 起点落在低代理符上 → 上一个码元是它的高代理，回退一格。
        val safeStart = if (startIdx > 0 && startIdx < s.length &&
            Character.isLowSurrogate(s[startIdx]) && Character.isHighSurrogate(s[startIdx - 1])
        ) startIdx - 1 else startIdx
        // 终点切在高代理符上 → 它的低代理被甩在区间外，收回一格。
        val safeEnd = if (endIdx > safeStart && endIdx < s.length &&
            Character.isHighSurrogate(s[endIdx - 1]) && Character.isLowSurrogate(s[endIdx])
        ) endIdx - 1 else endIdx
        return s.substring(safeStart, safeEnd)
    }

    /** Kept as the public name of the threshold; [ToolOutputSpill.LIMIT] owns it. */
    const val TOOL_OUTPUT_CHAR_LIMIT = ToolOutputSpill.LIMIT

    fun redactEnvVars(text: String): String {
        val env = runCatching { System.getenv() }.getOrDefault(emptyMap())
        val sensitive = env.values.filter { v ->
            v.length >= 6 && v.any { it.isLetter() } && v.any { it.isDigit() }
        }
        return redactWith(text, sensitive)
    }

    /** Testable core: apply redaction against an explicit sensitive list. */
    internal fun redactWith(text: String, sensitive: List<String>): String {
        if (sensitive.isEmpty() || text.isBlank()) return text
        // Kelivo 式结构化脱敏：JSON 里字符串值经转义后纯文本 replace 会漏
        // （引号逃逸），先解析再递归替换每个字符串值，命中面完整得多。
        runCatching {
            val node = org.json.JSONTokener(text).nextValue()
            if (node is org.json.JSONObject || node is org.json.JSONArray) {
                return redactNode(node, sensitive, depth = 0).toString()
            }
        }
        // 非 JSON 输出：退回纯文本替换。
        var out = text
        for (v in sensitive) {
            if (out.contains(v)) out = redactInText(out, v)
        }
        return out
    }

    /**
     * [T-redact-boundary] Whole-substring `replace` rewrote ANY text that
     * happened to contain a sensitive value — `5up3r` inside `s5up3rhouse`,
     * a token-shaped string embedded in an identifier. The replacement is
     * boundary-aware: the value must not be glued to further alphanumerics
     * (SQL `LIKE '%token%'`-style context still matches).
     */
    private fun redactInText(s: String, v: String): String =
        Regex("(?<![A-Za-z0-9])" + Regex.escape(v) + "(?![A-Za-z0-9])").replace(s, REDACTED)

    /** [T-redact-depth] Hard recursion bound; a hostile deep-nested JSON must
     *  not be able to StackOverflow the redactor (the caller's `runCatching`
     *  around parsing does not cover a throw from inside redactNode's tree
     *  walk — and a StackOverflowError is an Error, not an Exception).
     *  Past the cap the subtree is FLATTENED to text and plain-text redacted
     *  (fail-closed): the previous version returned the raw node, so a
     *  sensitive string nested one level past the cap reached the model
     *  unredacted — the depth bound must not double as a leak channel. */
    private const val MAX_REDACT_DEPTH = 32

    private fun redactString(s0: String, sensitive: List<String>): String {
        var s = s0
        for (v in sensitive) {
            if (s.contains(v)) s = redactInText(s, v)
        }
        return s
    }

    private fun redactNode(node: Any?, sensitive: List<String>, depth: Int): Any? {
        if (depth > MAX_REDACT_DEPTH) {
            val flat = runCatching { node.toString() }.getOrDefault(REDACTED)
            return redactString(flat, sensitive)
        }
        return when (node) {
            is org.json.JSONObject -> {
                val out = org.json.JSONObject()
                node.keys().forEach { k ->
                    // [T-redact-no-drop] The old runCatching { put } silently
                    // dropped a key whose transformed value was rejected by
                    // JSONObject (e.g. NaN) — the model saw quietly pruned
                    // JSON. On failure, fall back to the ORIGINAL value; the
                    // redaction pass is lossless with respect to structure.
                    val redacted = runCatching { redactNode(node.get(k), sensitive, depth + 1) }
                        .getOrElse { node.get(k) }
                    runCatching { out.put(k, redacted) }
                        .onFailure { runCatching { out.put(k, node.get(k)) } }
                }
                out
            }
            is org.json.JSONArray -> {
                val out = org.json.JSONArray()
                for (i in 0 until node.length()) {
                    val redacted = runCatching { redactNode(node.get(i), sensitive, depth + 1) }
                        .getOrElse { node.get(i) }
                    runCatching { out.put(redacted) }
                        .onFailure { runCatching { out.put(node.get(i)) } }
                }
                out
            }
            is String -> redactString(node, sensitive)
            else -> node
        }
    }

    /**
     * Apply both policies. [toolId] is the tool-call id.
     *
     * [T-tool-output-single-mechanism] Prefer the session-aware overload: only it
     * can put the full text somewhere the model can page through. This form has
     * no session, so an oversized result is capped WITHOUT a spill file — capped
     * is still the contract (the old code returned the full body here).
     * Callers that hold a session (ui/chat/ChatViewModelExecuteToolExt.kt:263)
     * should pass `context` + `sessionId`.
     */
    fun apply(output: String, toolId: String): String =
        apply(output, toolId, null, null, "")

    /**
     * Session-aware form: redact, then hand the oversized body to
     * [ToolOutputSpill] so it lands in THIS session's workspace and the model
     * gets a guest path it can actually `file_read`.
     */
    fun apply(
        output: String,
        toolId: String,
        context: android.content.Context?,
        sessionId: String?,
        toolName: String = "",
    ): String {
        val text = redactEnvVars(output)
        if (text.length <= ToolOutputSpill.LIMIT) return text
        val name = toolName.ifBlank { toolId.ifBlank { "tool" } }
        return if (context != null && !sessionId.isNullOrBlank()) {
            ToolOutputSpill.maybeSpill(context, sessionId, name, toolId, text)
        } else {
            ToolOutputSpill.cappedPreview(name, text)
        }
    }
}
