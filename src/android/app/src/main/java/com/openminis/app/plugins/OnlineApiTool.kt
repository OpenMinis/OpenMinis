package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.FetchUrlGuard
import com.openminis.app.provider.NonFiniteJson
import com.openminis.app.tools.ToolExecutionResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Execute one remote OpenAPI-lite operation as an agent tool.
 *
 * Adapted from XINCODE-Public OnlineApiTool
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 * SSRF via [FetchUrlGuard]; auth header is never returned to the model.
 */
object OnlineApiTool {
    const val PREFIX = "online_"
    private val networkClients = object : LinkedHashMap<String, OkHttpClient>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?): Boolean {
            val remove = size > 16
            if (remove) eldest?.value?.connectionPool?.evictAll()
            return remove
        }
    }

    private fun networkClient(context: Context, plugin: PluginRegistry.RemotePlugin): OkHttpClient = synchronized(networkClients) {
        networkClients.getOrPut(plugin.configurationKey()) {
            OkHttpClient.Builder()
                .dns(ConnectorNetworkPolicy.dns(context.applicationContext, plugin))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }

    private const val UA = "OpenMinis-Agent/1.26"
    private const val MAX_OUTPUT = 4_000

    /** Byte cap on a connector response body — see [T-bounded-body-read]. */
    private const val MAX_RESPONSE_BYTES = 256_000L

    /**
     * [T-hoisted-regex] `{placeholder}` path template matcher: constant pattern,
     * previously recompiled on every tool call.
     */
    private val PATH_PLACEHOLDER_RE = Regex("""\{([a-zA-Z0-9_]+)\}""")

    internal fun evictNetworkClient(plugin: PluginRegistry.RemotePlugin) {
        val prefix = connectorScopePrefix(plugin.id)
        synchronized(networkClients) {
            networkClients.keys.filter { it.startsWith(prefix) }.forEach { key ->
                networkClients.remove(key)?.connectionPool?.evictAll()
            }
        }
    }

    fun isOnline(name: String): Boolean = name.startsWith(PREFIX)

    fun toolName(pluginId: String, specName: String): String = "${PREFIX}${pluginId}__$specName"

    fun definition(
        plugin: PluginRegistry.RemotePlugin,
        spec: PluginRegistry.RemoteTool,
    ): AgentToolDefinition {
        val params = linkedMapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user. Use the same language as the user.",
            ),
        )
        val required = mutableListOf("tool_title")
        for (p in spec.params) {
            params[p.name] = AgentToolParam(p.type, p.description.ifBlank { p.name })
            if (p.required) required += p.name
        }
        return AgentToolDefinition(
            name = toolName(plugin.id, spec.name),
            description = "[${plugin.name}] ${spec.summary.ifBlank { spec.name }}",
            parameters = params,
            required = required,
            propertyOrdering = params.keys.toList(),
        )
    }

    fun execute(name: String, argsJson: String, context: Context): ToolExecutionResult {
        val parsed = parseName(name) ?: return ToolExecutionResult("Unknown online tool: $name", false)
        val plugin = PluginRegistry.cached(context).find { it.id == parsed.first }
            ?: return ToolExecutionResult("Plugin ${parsed.first} is not in the local catalog. Open Settings → Plugin market.", false)
        if (!OnlinePluginStore.isInstalled(context, plugin)) {
            return ToolExecutionResult("Plugin ${plugin.name} is not installed.", false)
        }
        val spec = plugin.tools.find { it.name == parsed.second }
            ?: return ToolExecutionResult("Unknown operation ${parsed.second} on ${plugin.name}", false)
        val args = NonFiniteJson.parseOrNull(argsJson) ?: JSONObject()
        args.remove("tool_title")
        return runCatching { request(plugin, spec, args, context) }
            .getOrElse { ToolExecutionResult("Cannot reach plugin server: ${it.message?.take(200)}", false) }
    }

    private fun parseName(name: String): Pair<String, String>? {
        if (!name.startsWith(PREFIX)) return null
        val rest = name.removePrefix(PREFIX)
        val idx = rest.indexOf("__")
        if (idx <= 0) return null
        val id = rest.substring(0, idx)
        val op = rest.substring(idx + 2)
        if (id.isBlank() || op.isBlank()) return null
        return id to op
    }

    private fun request(
        plugin: PluginRegistry.RemotePlugin,
        spec: PluginRegistry.RemoteTool,
        args: JSONObject,
        context: Context,
    ): ToolExecutionResult {
        var urlPath = spec.path
        val substituted = mutableSetOf<String>()
        PATH_PLACEHOLDER_RE.findAll(urlPath).forEach { m ->
            val key = m.groupValues[1]
            val v = args.optString(key)
            if (v.isNotBlank()) {
                urlPath = urlPath.replace(m.value, java.net.URLEncoder.encode(v, "UTF-8"))
                substituted.add(key)
            }
        }
        val url = plugin.baseUrl.trimEnd('/') + urlPath
        ConnectorNetworkPolicy.blockedReason(context, plugin, url)?.let {
            return ToolExecutionResult("Outbound connector request blocked: $it", false)
        }
        val method = spec.method.uppercase().ifBlank { "GET" }
        var requestUrl = buildUrl(url, method, args, substituted)
        var code: Int
        var text = ""
        var redirectCount = 0
        val client = networkClient(context, plugin)
        while (true) {
            ConnectorNetworkPolicy.blockedReason(context, plugin, requestUrl)?.let {
                return ToolExecutionResult("Outbound connector request blocked: $it", false)
            }
            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
            val header = plugin.authHeader.trim()
            val key = OnlinePluginStore.apiKey(context, plugin)
            if (header.isNotBlank() && !key.isNullOrBlank()) requestBuilder.header(header, key)
            val requestMethod = if (method == "PATCH") "POST" else method
            val body = if (method == "POST" || method == "PUT" || method == "PATCH") {
                val json = JSONObject()
                args.keys().forEach { k -> if (k !in substituted) json.putOpt(k, args.opt(k)) }
                json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            } else null
            requestBuilder.method(requestMethod, body)
            client.newCall(requestBuilder.build()).execute().use { response ->
                code = response.code
                if (code in 300..399) {
                    val location = response.header("Location")
                    if (location.isNullOrBlank() || redirectCount++ >= 2) {
                        return ToolExecutionResult("Remote connector redirected too many times", false)
                    }
                    if (method != "GET" && method != "HEAD") {
                        return ToolExecutionResult("Remote connector redirects are disabled for write requests", false)
                    }
                    val redirected = URL(URL(requestUrl), location)
                    val original = URL(requestUrl)
                    if (!redirected.protocol.equals(original.protocol, ignoreCase = true) ||
                        !redirected.host.equals(original.host, ignoreCase = true) ||
                        effectivePort(redirected) != effectivePort(original)
                    ) {
                        return ToolExecutionResult("Cross-origin connector redirects are blocked", false)
                    }
                    requestUrl = redirected.toString()
                    return@use
                }
                // [T-bounded-body-read] `body.string()` buffered the whole remote
                // response (a connector returning a 40 MB blob allocated 40 MB)
                // and only the first MAX_OUTPUT characters were ever used.
                text = com.openminis.app.tools.readBodyBounded(response.body, MAX_RESPONSE_BYTES)
            }
            if (code in 300..399) continue
            break
        }
        return when (code) {
            401, 403 -> ToolExecutionResult(
                "Plugin auth failed ($code). Re-enter a valid API key in Settings → Plugin market.",
                false,
            )
            429 -> ToolExecutionResult("Plugin rate-limited (429). Try again later.", false)
            in 200..299 -> ToolExecutionResult(text.take(MAX_OUTPUT), true)
            else -> ToolExecutionResult("Remote HTTP $code: ${text.take(300)}", false)
        }
    }

    private fun effectivePort(url: URL): Int =
        if (url.port >= 0) url.port else url.defaultPort

    private fun buildUrl(
        base: String,
        method: String,
        args: JSONObject,
        substituted: Set<String>,
    ): String {
        if (method == "POST" || method == "PUT" || method == "PATCH") return base
        val sb = StringBuilder(base)
        var first = !base.contains("?")
        args.keys().forEach { k ->
            if (k in substituted || args.isNull(k)) return@forEach
            val v = args.optString(k)
            if (v.isEmpty()) return@forEach
            sb.append(if (first) "?" else "&")
                .append(URLEncoder.encode(k, "UTF-8"))
                .append("=")
                .append(URLEncoder.encode(v, "UTF-8"))
            first = false
        }
        return sb.toString()
    }
}
