package com.openminis.app.debug

import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ring buffer that captures recent LLM API requests and responses for debugging.
 * Accessible via debug.llmRequests JSON-RPC method.
 */
object LLMRequestLog {

    private const val MAX_ENTRIES = 20

    /**
     * T302: cap each retained `requestBody` at this many characters. Real
     * agent-loop request bodies can run 30+ MB (full message history with
     * heavy tool outputs); pinning 20 of those in a ring buffer was the
     * direct cause of an OOM crash on a HONOR PTP-AN00 — the JVM heap
     * blew past target footprint trying to hold ~600 MB of transient
     * debug strings. The truncated body is still useful for diagnosing
     * cache-prefix stability and small request shape.
     */
    private const val MAX_BODY_CHARS = 64 * 1024

    data class Entry(
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
        val requestURL: String,
        val requestMethod: String = "POST",
        val requestHeaders: Map<String, String>,
        val requestBody: String,      // JSON string — capped at MAX_BODY_CHARS by add()
        val durationMs: Long = 0,
        val responseStatusCode: Int = 0,
        val responseBody: String = "", // first 2000 chars
        val usage: JSONObject? = null,
    )

    private val entries = mutableListOf<Entry>()

    @Synchronized
    fun add(entry: Entry) {
        // T302: belt-and-suspenders. Release builds short-circuit the whole
        // log because the only consumer is the debug.llmRequests JSON-RPC
        // surface, which release users never reach — keeping a 20-deep ring
        // buffer of multi-MB request bodies hot for nobody is exactly the
        // OOM the HONOR PTP-AN00 user hit. Debug builds still record, with
        // each entry truncated to MAX_BODY_CHARS so even a single retained
        // entry can't push a memory-tight device over the line.
        if (!BuildConfig.DEV_TOOLS) return
        val safeEntry = if (entry.requestBody.length > MAX_BODY_CHARS) {
            entry.copy(
                requestBody = entry.requestBody.take(MAX_BODY_CHARS) +
                    "\n…[truncated ${entry.requestBody.length - MAX_BODY_CHARS} chars]",
            )
        } else entry
        entries.add(safeEntry)
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    @Synchronized
    fun getAll(): List<Entry> = entries.toList()

    @Synchronized
    fun getLast(n: Int): List<Entry> = entries.takeLast(n)

    @Synchronized
    fun clear() = entries.clear()

    fun toJSON(last: Int? = null): JSONObject {
        val list = if (last != null) getLast(last) else getAll()
        val array = JSONArray()
        for (entry in list) {
            array.put(JSONObject().apply {
                put("provider", entry.provider)
                put("timestamp", entry.timestamp)
                put("requestURL", entry.requestURL)
                put("requestMethod", entry.requestMethod)
                put("requestHeaders", JSONObject(entry.requestHeaders))
                put("requestBody", try { JSONObject(entry.requestBody) } catch (_: Exception) { entry.requestBody })
                put("durationMs", entry.durationMs)
                put("responseStatusCode", entry.responseStatusCode)
                if (entry.responseBody.isNotEmpty()) {
                    put("responseBody", entry.responseBody)
                }
                if (entry.usage != null) {
                    put("usage", entry.usage)
                }
            })
        }
        return JSONObject().apply {
            put("count", list.size)
            put("requests", array)
        }
    }
}
