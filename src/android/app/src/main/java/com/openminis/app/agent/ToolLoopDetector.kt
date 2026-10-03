package com.openminis.app.agent

import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Per-session tool-call sliding window record. Filled in two passes:
 *  - `check()` doesn't write here; it only reads existing history.
 *  - `record()` appends a new entry, back-filling `resultHash`/`unknownToolName`
 *    from the just-finished tool execution.
 */
data class ToolCallRecord(
    val toolName: String,
    val argsHash: String,
    val resultHash: String? = null,
    val unknownToolName: String? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class Level { NONE, WARNING, CRITICAL }

data class LoopCheckResult(
    val level: Level,
    val message: String? = null,
    val warningKey: String? = null,
) {
    companion object {
        val NONE = LoopCheckResult(Level.NONE)
    }
}

/**
 * Threshold contract:
 *   warningThreshold < criticalThreshold < globalCircuitBreakerThreshold
 * The constructor enforces this at construction time so callers cannot
 * silently ship a misconfigured detector.
 */
data class ToolLoopConfig(
    val historySize: Int = 30,
    val warningThreshold: Int = 10,
    val unknownToolThreshold: Int = 10,
    val criticalThreshold: Int = 20,
    val globalCircuitBreakerThreshold: Int = 30,
) {
    init {
        require(warningThreshold > 0) { "warningThreshold must be positive" }
        require(warningThreshold < criticalThreshold) {
            "warningThreshold ($warningThreshold) must be < criticalThreshold ($criticalThreshold)"
        }
        require(criticalThreshold < globalCircuitBreakerThreshold) {
            "criticalThreshold ($criticalThreshold) must be < globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
        require(historySize >= globalCircuitBreakerThreshold) {
            "historySize ($historySize) must be >= globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
    }
}

/**
 * Detects four classes of agent tool-call loops and emits warnings or hard
 * blocks. See fix_tool_loop_detection.md for the full behavioral spec.
 *
 * Strategy priority (highest first):
 *   1. unknown_tool_repeat       — consecutive hallucinated-tool errors.
 *   2. global_circuit_breaker    — same args + same result, ≥30 across any tool.
 *   3. known_poll_no_progress    — poll-style tool with frozen results.
 *   4. generic_repeat            — same args ≥10, regardless of result.
 *
 * One detector instance per Session.
 *
 * [T-android-concurrent-tools] Thread-safe by an instance lock. This class used
 * to say "serialize calls through the agent loop's existing single-threaded
 * dispatch" — and that dispatch is no longer single-threaded: a turn's tool
 * calls now run concurrently, so several threads reach [check] and [record] on
 * the same detector at once.
 *
 * The failure was not theoretical. On a Pixel 6 a sub agent died with
 * "Attempt to invoke virtual method 'String ToolCallRecord.getToolName()' on a
 * null object reference": ArrayDeque is unsynchronized, so a concurrent
 * `addLast` that grows the backing array leaves a transiently-null slot, and
 * the scans below dereference it. The whole run was lost — the parent got
 * "the agent ended with status failed before writing a final answer".
 *
 * Every public entry point takes the lock. Contention is irrelevant here: the
 * work is hashing a small map and scanning a 30-element window, next to tool
 * calls that take seconds.
 */
class ToolLoopDetector(private val config: ToolLoopConfig = ToolLoopConfig()) {

    /** [T-android-concurrent-tools] Guards [history] and [warningBuckets]. */
    private val lock = Any()

    private val history = ArrayDeque<ToolCallRecord>()
    // warningKey -> last bucket index already emitted, used to throttle warnings
    // so the LLM doesn't get the same warning attached on every single tool call.
    private val warningBuckets = HashMap<String, Int>()

    /** Drop all in-flight state. Call on session reset / new chat. */
    fun reset() = synchronized(lock) {
        history.clear()
        warningBuckets.clear()
    }

    /** Test-only window inspection. */
    internal fun historySnapshot(): List<ToolCallRecord> = synchronized(lock) { history.toList() }

    // ─── before-execution hook ────────────────────────────────────────────────

    /**
     * Inspect the about-to-run tool against the sliding window. Caller MUST
     * skip tool execution and surface `result.message` as a tool-error result
     * when `result.level == CRITICAL`.
     */
    fun check(toolName: String, params: Map<String, Any?>): LoopCheckResult = synchronized(lock) {
        val argsHash = argsHashFor(toolName, params)

        // 1. unknown_tool_repeat — most specific signal, runs first.
        val unknownStreak = countUnknownStreakFromTail(toolName)
        if (unknownStreak >= config.unknownToolThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: attempted unavailable tool '$toolName' " +
                "$unknownStreak times. Stop retrying that missing tool and answer without it."
            AppLogger.warning("ToolLoopDetector",
                "CRITICAL unknown_tool_repeat tool=$toolName streak=$unknownStreak")
            return LoopCheckResult(Level.CRITICAL, msg)
        }

        val noProgressStreak = getNoProgressStreak(toolName, argsHash)

        // 2. global_circuit_breaker — universal backstop, before poll-specific
        //    rule so a runaway non-poll loop can't slip past on lower thresholds.
        if (noProgressStreak >= config.globalCircuitBreakerThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: $toolName has repeated identical " +
                "no-progress outcomes $noProgressStreak times. Session execution " +
                "blocked by global circuit breaker."
            AppLogger.warning("ToolLoopDetector",
                "CRITICAL global_circuit_breaker tool=$toolName streak=$noProgressStreak")
            return LoopCheckResult(Level.CRITICAL, msg)
        }

        // 3. known_poll_no_progress — poll tools have a tighter critical bar
        //    because polling without progress is the canonical waste case.
        if (isPollTool(toolName, params)) {
            if (noProgressStreak >= config.criticalThreshold) {
                val msg = "[LOOP BLOCKED] CRITICAL: Called $toolName $noProgressStreak " +
                    "times with identical no-progress results. Session execution blocked."
                AppLogger.warning("ToolLoopDetector",
                    "CRITICAL known_poll_no_progress tool=$toolName streak=$noProgressStreak")
                return LoopCheckResult(Level.CRITICAL, msg)
            }
            if (noProgressStreak >= config.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $noProgressStreak " +
                    "times with no progress. Stop polling and either (1) increase wait " +
                    "time, or (2) report the task as failed."
                AppLogger.debug("ToolLoopDetector",
                    "WARNING known_poll_no_progress tool=$toolName streak=$noProgressStreak")
                return LoopCheckResult(Level.WARNING, msg, "poll:$toolName:$argsHash")
            }
        }

        // 4. generic_repeat — non-poll tools only; counts non-consecutive hits
        //    so flaky-but-progressing calls eventually fall out of the window.
        if (!isPollTool(toolName, params)) {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $totalCount times " +
                    "with identical arguments. If this is not making progress, stop " +
                    "retrying and report the task as failed."
                AppLogger.debug("ToolLoopDetector",
                    "WARNING generic_repeat tool=$toolName count=$totalCount")
                return LoopCheckResult(Level.WARNING, msg, "repeat:$toolName:$argsHash")
            }
        }

        return LoopCheckResult.NONE
    }

    // ─── after-execution hook ────────────────────────────────────────────────

    /**
     * Append the just-completed tool call to the window and decide whether a
     * warning should be appended to the tool result this turn. Critical
     * outcomes are reported by `check()` *before* the call; `record()` only
     * ever returns NONE or WARNING.
     */
    fun record(
        toolName: String,
        params: Map<String, Any?>,
        result: String?,
        errorMessage: String? = null,
        toolCallId: String? = null,
    ): LoopCheckResult = synchronized(lock) {
        val argsHash = argsHashFor(toolName, params)
        val resultHash = resultHashFor(result, errorMessage)
        val unknownToolName = extractUnknownToolName(errorMessage)

        history.addLast(ToolCallRecord(
            toolName = toolName,
            argsHash = argsHash,
            resultHash = resultHash,
            unknownToolName = unknownToolName,
            toolCallId = toolCallId,
        ))
        while (history.size > config.historySize) history.removeFirst()

        // Re-evaluate poll/generic warnings on the post-record window so the
        // warning text reflects the current count (including the call we just
        // appended). Critical paths are check()-only by spec.
        if (isPollTool(toolName, params)) {
            val streak = getNoProgressStreak(toolName, argsHash)
            if (streak in config.warningThreshold until config.criticalThreshold) {
                val key = "poll:$toolName:$argsHash"
                if (shouldEmitWarning(key, streak)) {
                    val msg = "[LOOP WARNING] You have called $toolName $streak times " +
                        "with no progress. Stop polling and either (1) increase wait " +
                        "time, or (2) report the task as failed."
                    return LoopCheckResult(Level.WARNING, msg, key)
                }
            }
        } else {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val key = "repeat:$toolName:$argsHash"
                if (shouldEmitWarning(key, totalCount)) {
                    val msg = "[LOOP WARNING] You have called $toolName $totalCount " +
                        "times with identical arguments. If this is not making " +
                        "progress, stop retrying and report the task as failed."
                    return LoopCheckResult(Level.WARNING, msg, key)
                }
            }
        }

        return LoopCheckResult.NONE
    }

    // ─── strategy helpers ────────────────────────────────────────────────────

    /**
     * Walk the window from newest to oldest and count how many *consecutive*
     * tail entries are unknown-tool errors targeting the same hallucinated
     * name. Stops at the first record without a parsed unknownToolName, or
     * one that targets a different name.
     */
    private fun countUnknownStreakFromTail(toolName: String): Int {
        var streak = 0
        for (rec in history.reversed()) {
            val unk = rec.unknownToolName ?: break
            if (unk == toolName) streak++ else break
        }
        return streak
    }

    /**
     * Count how many recent records share the given (toolName, argsHash) AND
     * a single common resultHash, scanning newest → oldest. Records of *other*
     * tools are skipped (don't reset the streak), but a result mismatch on the
     * target tool stops counting — that's the "progress observed" signal.
     */
    private fun getNoProgressStreak(toolName: String, argsHash: String): Int {
        var streak = 0
        var pinnedHash: String? = null
        for (rec in history.reversed()) {
            if (rec.toolName != toolName || rec.argsHash != argsHash) continue
            val rh = rec.resultHash ?: break
            if (pinnedHash == null) {
                pinnedHash = rh
                streak++
            } else if (rh == pinnedHash) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    private fun isPollTool(toolName: String, params: Map<String, Any?>): Boolean {
        if (toolName == "command_status") return true
        if (toolName == "process") {
            val action = (params["action"] as? String)?.lowercase()
            if (action == "poll" || action == "log") return true
        }
        return false
    }

    /**
     * Throttle warnings of the same key to once per `warningThreshold` calls,
     * so a 30-call repeat loop emits only at counts 10, 20, 30 — not on every
     * tool turn after threshold is crossed.
     */
    private fun shouldEmitWarning(warningKey: String, currentCount: Int): Boolean {
        val bucket = currentCount / config.warningThreshold
        val last = warningBuckets[warningKey]
        if (last == bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }

    // ─── hashing / parsing primitives ────────────────────────────────────────

    private fun argsHashFor(toolName: String, params: Map<String, Any?>): String {
        // Drop UI/telemetry-only fields the model freely varies — most notably
        // `tool_title`, which models routinely counter-suffix ("Read X #1",
        // "#2", ...). Without this filter every logically identical call hashes
        // unique and the repeat/circuit-breaker strategies all silently fail.
        val filtered = if (params.keys.any { it in ARGS_HASH_IGNORED_KEYS }) {
            params.filterKeys { it !in ARGS_HASH_IGNORED_KEYS }
        } else {
            params
        }
        return sha256("$toolName:${stableJson(filtered)}")
    }

    /**
     * Stable JSON: keys sorted alphabetically at every nesting level so that
     * a Map<"b" → 2, "a" → 1> hashes identically to one inserted "a" → 1, "b" → 2.
     */
    private fun stableJson(value: Any?): String = buildString { appendStable(value) }

    private fun StringBuilder.appendStable(value: Any?) {
        when (value) {
            null -> append("null")
            is Map<*, *> -> {
                append('{')
                value.entries
                    .map { it.key?.toString().orEmpty() to it.value }
                    .sortedBy { it.first }
                    .forEachIndexed { i, (k, v) ->
                        if (i > 0) append(',')
                        append(JSONObject.quote(k)); append(':'); appendStable(v)
                    }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    appendStable(v)
                }
                append(']')
            }
            is Array<*> -> appendStable(value.toList())
            is String -> append(JSONObject.quote(value))
            is Number, is Boolean -> append(value.toString())
            is JSONObject -> appendStable(value.toMap())
            is JSONArray -> appendStable(value.toList())
            else -> append(JSONObject.quote(value.toString()))
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = HashMap<String, Any?>(length())
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = unwrap(get(k))
        }
        return out
    }

    private fun JSONArray.toList(): List<Any?> {
        val out = ArrayList<Any?>(length())
        for (i in 0 until length()) out.add(unwrap(get(i)))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> v.toMap()
        is JSONArray -> v.toList()
        else -> v
    }

    /**
     * Hash only the success/failure-bearing parts of a tool result. We
     * deliberately fold the entire output text in too — the spec calls for
     * stripping noise like timestamps/requestIds, but the platform's tool
     * results don't carry those at this layer (the underlying tools already
     * sanitize them). If a future tool starts leaking volatile fields, prune
     * them here rather than at every call site.
     */
    private fun resultHashFor(result: String?, errorMessage: String?): String {
        val payload = buildString {
            append("err=")
            append(errorMessage ?: "")
            append("out=")
            append(result ?: "")
        }
        return sha256(payload)
    }

    /**
     * Two patterns cover the wording variations seen across providers:
     *   "unknown tool: foobar"          → group 1 = "foobar"
     *   "tool 'foobar' not found"       → group 1 = "foobar"
     * Both case-insensitive; quoting and surrounding whitespace tolerated.
     */
    private fun extractUnknownToolName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        UNKNOWN_TOOL_RE_1.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        UNKNOWN_TOOL_RE_2.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        /**
         * Param keys excluded from `argsHashFor`. Currently just `tool_title`
         * (a required UI label that models commonly counter-suffix). Add new
         * entries here if other purely-cosmetic fields ever leak into params.
         */
        private val ARGS_HASH_IGNORED_KEYS: Set<String> = setOf("tool_title")

        private val UNKNOWN_TOOL_RE_1 = Regex(
            """unknown tool[:\s]+["']?([a-zA-Z0-9_.\-]+)["']?""",
            RegexOption.IGNORE_CASE,
        )
        private val UNKNOWN_TOOL_RE_2 = Regex(
            """tool\s+["']?([a-zA-Z0-9_.\-]+)["']?\s+(?:not found|is not available)""",
            RegexOption.IGNORE_CASE,
        )
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
