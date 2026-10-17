package com.openminis.app.mcp.client

import com.openminis.app.provider.NonFiniteJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-mcp-native] Pure JSON-RPC 2.0 / MCP wire helpers shared by the native
 * HTTP and STDIO clients. Everything here is JVM-testable — no Android or
 * OkHttp types.
 *
 * Semantics mirror the in-guest reference implementation
 * (`assets/default_mount/usr/local/lib/minis-mcp-cli/transport/ *.py`) so both
 * paths behave identically against the same servers:
 *  - `$VAR` / `${VAR}` / `$$VAR` / `$${VAR}` all expand from the app env-var
 *    store; an unset name expands to the empty string (never left literal —
 *    a literal `$$KEY` leaked to a server would be a credential-shaped bug).
 *  - Streamable-HTTP responses may be plain JSON *or* an SSE event stream;
 *    both are parsed for the JSON-RPC reply.
 *  - tools/call results are flattened to text for the agent (text parts are
 *    concatenated; non-text parts are summarized; `structuredContent` is the
 *    fallback when there is no text).
 */
object McpJsonRpc {

    const val PROTOCOL_VERSION = "2025-06-18"
    const val CLIENT_NAME = "minis-ultra"
    const val CLIENT_VERSION = "1.0.0"

    private val idGen = AtomicInteger(1)

    /** Wire-level MCP failure with a CLI-compatible error code. */
    class McpError(val code: String, message: String) : Exception("[$code] $message") {
        companion object {
            const val CONNECTION_ERROR = "CONNECTION_ERROR"
            const val TIMEOUT = "TIMEOUT"
            const val AUTH_REQUIRED = "AUTH_REQUIRED"
            const val PROTOCOL_ERROR = "PROTOCOL_ERROR"
            const val SERVER_ERROR = "SERVER_ERROR"
            const val NOT_INITIALIZED = "NOT_INITIALIZED"
        }
    }

    fun nextId(): Int = idGen.getAndIncrement()

    fun request(id: Int, method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    fun notification(method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }

    /**
     * JSON-RPC 2.0 allows a string OR number id. Compare on the wire string
     * form so a server echoing `5` as `"5"` still matches; `optInt` silently
     * treated every string id as absent. Null/JSON null is a notification.
     */
    fun idKey(raw: Any?): String? =
        if (raw == null || raw == JSONObject.NULL) null else raw.toString()

    fun initializeParams(): JSONObject = JSONObject().apply {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", JSONObject())
        put("clientInfo", JSONObject().apply {
            put("name", CLIENT_NAME)
            put("version", CLIENT_VERSION)
        })
    }

    // -- env placeholder expansion -------------------------------------------

    // Matches $$VAR, $VAR, $${VAR}, ${VAR}. Greedy on the leading `$$` so a
    // UI-picker-emitted `$$NAME` never half-expands into `$` + value.
    private val ENV_PATTERN = Regex("""\$\$?\{?([A-Za-z_][A-Za-z0-9_]*)\}?""")

    /**
     * Replace every env placeholder in [value] using [resolver]. Unset names
     * expand to "" (matches transport/http.py `expand_env`).
     */
    fun expandEnv(value: String, resolver: (String) -> String?): String {
        if (!value.contains('$')) return value
        return ENV_PATTERN.replace(value) { m -> resolver(m.groupValues[1]) ?: "" }
    }

    // -- response parsing -----------------------------------------------------

    /**
     * Extract the JSON-RPC reply for [wantId] from an HTTP response.
     * [contentType] selects the parse mode: `text/event-stream` bodies are
     * scanned as SSE events, everything else is parsed as a single JSON
     * document. Returns null when nothing matching was found.
     */
    fun extractResponse(body: String, contentType: String, wantId: Int): JSONObject? {
        if (body.isBlank()) return null
        return if (contentType.contains("text/event-stream", ignoreCase = true)) {
            parseSseEvents(body).firstOrNull { isReplyFor(it, wantId) }
        } else {
            runCatching { NonFiniteJson.parse(body) }.getOrNull()?.takeIf { isReplyFor(it, wantId) }
        }
    }

    private fun isReplyFor(obj: JSONObject, wantId: Int): Boolean =
        obj.has("jsonrpc") &&
            idKey(obj.opt("id")) == wantId.toString() &&
            (obj.has("result") || obj.has("error"))

    /**
     * Parse an SSE stream body into the JSON documents carried by `data:`
     * fields. Multi-line `data:` continuations within one event are joined
     * with '\n' per the SSE spec. Non-JSON data (e.g. `event: ping`
     * keepalives with empty data) is skipped.
     */
    fun parseSseEvents(body: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val dataLines = mutableListOf<String>()
        fun flush() {
            if (dataLines.isEmpty()) return
            val joined = dataLines.joinToString("\n")
            dataLines.clear()
            if (joined.isBlank()) return
            runCatching { NonFiniteJson.parse(joined) }.getOrNull()?.let(out::add)
        }
        for (raw in body.split('\n')) {
            val line = raw.removeSuffix("\r")
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> { /* comment / keepalive */ }
                line.startsWith("data:") ->
                    dataLines.add(line.removePrefix("data:").removePrefix(" "))
                // event:, id:, retry: fields carry no payload we need.
            }
        }
        flush() // stream ended without a trailing blank line
        return out
    }

    /**
     * STDIO framing: read newline-delimited JSON from [reader] until the
     * reply for [wantId] arrives. Server-initiated notifications and
     * requests (no id / other id) are skipped. Throws [McpError] on EOF or
     * malformed stream. Blocking — callers own the thread + timeout.
     */
    fun readReply(reader: BufferedReader, wantId: Int): JSONObject {
        while (true) {
            val line = reader.readLine()
                ?: throw McpError(McpError.CONNECTION_ERROR, "server closed the stream before replying")
            if (line.isBlank()) continue
            val obj = runCatching { NonFiniteJson.parse(line) }.getOrNull() ?: continue
            if (obj.optInt("id", Int.MIN_VALUE) == wantId &&
                (obj.has("result") || obj.has("error"))
            ) return obj
            // Anything else (notification, server->client request, log line
            // that happens to be JSON) is ignored, matching daemon.py
            // `_read_reply`.
        }
    }

    /** Unwrap a JSON-RPC envelope: return `result` or throw the `error`. */
    fun unwrapResult(reply: JSONObject): JSONObject {
        val err = reply.optJSONObject("error")
        if (err != null) {
            throw McpError(
                "RPC_${err.opt("code")}",
                err.optString("message", "unknown JSON-RPC error"),
            )
        }
        return reply.optJSONObject("result")
            ?: throw McpError(McpError.PROTOCOL_ERROR, "reply has neither result nor error")
    }

    // -- tools result shaping ------------------------------------------------

    /**
     * Flatten a `tools/list` result into agent-readable text: one line per
     * tool with its description (first sentence) — the full input schema is
     * available via the raw JSON variant when the model needs it.
     */
    fun formatToolList(tools: JSONArray): String {
        if (tools.length() == 0) return "Server exposes 0 tools."
        val sb = StringBuilder()
        sb.append(tools.length()).append(" tool(s):\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name = t.optString("name", "?")
            var desc = t.optString("description", "").trim()
            if (desc.length > 160) desc = desc.substring(0, 160) + "…"
            sb.append("- ").append(name)
            if (desc.isNotEmpty()) sb.append(": ").append(desc.replace('\n', ' '))
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    data class CallOutcome(val text: String, val isError: Boolean)

    /**
     * Flatten a `tools/call` result into agent-readable text. Text content
     * parts are concatenated; image/audio parts are summarized (the native
     * path does not inline binary blobs into the transcript); embedded
     * resources render their text or JSON. `isError: true` results are
     * surfaced as failures by the caller.
     */
    fun formatCallResult(result: JSONObject): CallOutcome {
        val isError = result.optBoolean("isError", false)
        val content = result.optJSONArray("content")
        val parts = mutableListOf<String>()
        if (content != null) {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> parts.add(c.optString("text", ""))
                    "image" -> parts.add(
                        "[image ${c.optString("mimeType", "image/*")}, " +
                            "${c.optString("data").length} b64 chars — not inlined]",
                    )
                    "audio" -> parts.add("[audio ${c.optString("mimeType", "audio/*")}]")
                    "resource", "resource_link" -> {
                        val embedded = c.optJSONObject("resource")
                        val text = embedded?.optString("text", "")?.takeIf { it.isNotEmpty() }
                            ?: c.optString("uri", "").ifEmpty { null }
                            ?: embedded?.toString()
                        if (text != null) parts.add(text)
                    }
                    else -> parts.add(c.toString())
                }
            }
        }
        var text = parts.joinToString("\n").trim()
        if (text.isEmpty()) {
            val structured = result.optJSONObject("structuredContent")
            text = structured?.toString(2) ?: result.toString(2)
        }
        return CallOutcome(text, isError)
    }
}

/** Minimal transport contract shared by the HTTP and STDIO clients. */
interface McpClient {
    /** Server name from servers.json (the `mcpServers` object key). */
    val serverName: String

    /** Perform the MCP handshake if not already done. Idempotent. */
    fun ensureInitialized()

    /** `tools/list` — raw tool objects. */
    fun listTools(): JSONArray

    /** `tools/call` — raw result object (content array + isError). */
    fun callTool(tool: String, arguments: JSONObject): JSONObject

    /** Release the underlying connection/process. Safe to call twice. */
    fun close()
}
