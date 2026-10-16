package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-perf-async-db-init] Helper for CPU-bound JSON operations in the stream
 * collect loop. Extract partial string values and toString() serialization
 * happen on Dispatchers.Default to prevent blocking the IO dispatcher
 * (which collect { } runs on) with repeated string scanning / copying.
 *
 * Background: the agent loop calls `collect { chunk -> ... }` on Dispatchers.IO,
 * but each ToolInputDelta carries a multi-KB accumulated JSON string, and
 * extractPartialStringValue scans it from scratch every time. Similarly,
 * chunk.args.toString() on a large JSONObject is a linear copy. Those CPU
 * bursts can stall the IO dispatcher (which is sized for blocking I/O, not
 * CPU work), delaying other coroutines. Moving them to Default keeps IO
 * responsive for network / disk / suspend calls.
 */
object StreamChunkJsonHelper {
    /**
     * Parse a partial JSON string to extract one field, tolerant of incomplete
     * JSON (mirrors iOS extractPartialStringValue). Returns null if the key is
     * not found or the value is not a string.
     *
     * Runs on Dispatchers.Default — suspend and await the result from the
     * IO-thread collect loop.
     */
    suspend fun extractPartialStringValue(key: String, json: String): String? = withContext(Dispatchers.Default) {
        val patterns = listOf("\"$key\": \"", "\"$key\":\"")
        for (p in patterns) {
            val at = json.indexOf(p)
            if (at < 0) continue
            val after = json.substring(at + p.length)
            return@withContext unescapePartialJsonString(findUnescapedEnd(after))
        }
        null
    }

    /**
     * Serialize a JSONObject to a String, on Dispatchers.Default to prevent
     * the IO dispatcher from blocking on a multi-KB linear copy.
     */
    suspend fun serializeArgs(args: JSONObject): String = withContext(Dispatchers.Default) {
        args.toString()
    }

    /** Return substring up to the first unescaped `"`, or the whole string if none. */
    private fun findUnescapedEnd(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                // Skip escaped character (could be `\"`, `\\`, `\n`, etc.)
                i += 2
                continue
            }
            if (c == '"') return s.substring(0, i)
            i++
        }
        return s
    }

    /** Unescape common JSON string escapes. */
    private fun unescapePartialJsonString(s: String): String =
        s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
}
