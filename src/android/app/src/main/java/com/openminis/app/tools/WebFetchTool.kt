package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.network.guardedDohDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetch a public URL to text. SSRF via [FetchUrlGuard].
 *
 * Adapted from XINCODE-Public WebFetchTool (GPL-3.0-or-later).
 */
object WebFetchTool {
    const val NAME = "web_fetch"
    private const val MAX_OUTPUT = 8_000

    /**
     * [T-bounded-body-read] Never buffer a whole response. `body.string()` reads
     * the ENTIRE body into memory (a 50 MB download from a link the model pasted
     * became a 50 MB String, then three more copies through the regex passes)
     * only for `take(8_000)` of it. The stream is capped at this many BYTES —
     * the read stops there, the connection closes, and the worst case is bounded
     * by the cap instead of by the server.
     */
    private const val MAX_FETCH_BYTES = 512_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // [T-doh-resolver-fallback] Composed, NOT replaced: the SSRF guard must keep
            // veto power over every address OkHttp would dial (incl. redirects),
            // while still getting the DoH fallback when system DNS is dead.
            .dns(guardedDohDns { !FetchUrlGuard.isUnsafeAddress(it) })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch a public http(s) URL and return extracted text. Use web_search first to find URLs. Private/loopback hosts are blocked.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "url" to AgentToolParam("string", "http or https URL."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val url = JSONObject(argsJson).optString("url", "").trim()
            val blocked = FetchUrlGuard.blockedReason(url)
            if (blocked != null) {
                return ToolExecutionResult("Error: $blocked", false, toolTitle = toolTitle)
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MinisUltra/web_fetch")
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                FetchUrlGuard.blockedReason(finalUrl)?.let {
                    return ToolExecutionResult("Error after redirect: $it", false, toolTitle = toolTitle)
                }
                if (!resp.isSuccessful) {
                    return ToolExecutionResult("HTTP ${resp.code} for $url", false, toolTitle = toolTitle)
                }
                // [T-bounded-body-read] Bounded stream read instead of
                // `body?.string()`: the extraction below only ever keeps the
                // first MAX_OUTPUT characters, so buffering the whole body (a
                // 50 MB HTML page) was pure waste — and unbounded memory on a
                // model-supplied URL.
                val raw = readBodyBounded(resp.body, MAX_FETCH_BYTES)
                val text = htmlToText(raw).take(MAX_OUTPUT)
                ToolExecutionResult("URL: $finalUrl\n\n$text", true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error fetching URL: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    internal fun htmlToText(raw: String): String {
        var s = raw
        s = SCRIPT_RE.replace(s, " ")
        s = STYLE_RE.replace(s, " ")
        s = TAG_RE.replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"")
        s = HSPACE_RE.replace(s, " ")
        s = BLANK_RUN_RE.replace(s, "\n\n")
        return s.trim()
    }

    // [T-hoisted-regex] `Regex(...)` inside the function body recompiled the
    // pattern on EVERY call (web_fetch is called per search result). These are
    // constant patterns, so they are compiled once as file-level vals — the same
    // convention the rest of tools/ uses (KeylessSearchSources.SNIPPET_RE).
    private val SCRIPT_RE = Regex("(?is)<script[^>]*>.*?</script>")
    private val STYLE_RE = Regex("(?is)<style[^>]*>.*?</style>")
    private val TAG_RE = Regex("(?is)<[^>]+>")
    private val HSPACE_RE = Regex("[ \\t]+")
    private val BLANK_RUN_RE = Regex("\\n{3,}")
}

/**
 * [T-bounded-body-read] Read at most [maxBytes] of an OkHttp response body and
 * decode it with the declared charset (UTF-8 when the response declares none),
 * exactly like `ResponseBody.string()` does — except the buffer is bounded, so a
 * hostile or huge body can no longer be materialised in full just to be
 * truncated afterwards. Truncation may cut a multi-byte character; the decoder
 * substitutes U+FFFD for the incomplete tail, which is harmless for text output.
 */
internal fun readBodyBounded(body: okhttp3.ResponseBody?, maxBytes: Long): String {
    if (body == null) return ""
    val charset = runCatching {
        body.contentType()?.charset(Charsets.UTF_8)
    }.getOrNull() ?: Charsets.UTF_8
    return body.byteStream().use { input ->
        val out = java.io.ByteArrayOutputStream(
            minOf(maxBytes, 64L * 1024L).toInt().coerceAtLeast(1024),
        )
        val buf = ByteArray(8192)
        var total = 0L
        while (total < maxBytes) {
            val room = minOf(buf.size.toLong(), maxBytes - total).toInt()
            val n = input.read(buf, 0, room)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        String(out.toByteArray(), charset)
    }
}
