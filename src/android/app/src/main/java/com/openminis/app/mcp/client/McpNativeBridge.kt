package com.openminis.app.mcp.client

import android.content.Context
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import com.openminis.app.data.repository.MCPToolPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.client.McpJsonRpc.McpError
import com.openminis.app.mcp.oauth.MCPOAuthStore
import com.openminis.app.mcp.oauth.MCPTokenBridge
import com.openminis.app.mcp.server.McpServerManager
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-native] In-process MCP access for the agent — replaces the round trip
 * through `minis-mcp-cli` (shell_execute → PRoot bash → Python CLI → daemon
 * socket → server) with a direct Kotlin transport. The CLI/daemon stay in
 * place as a fallback and for interactive terminal use.
 *
 * Responsibilities:
 *  - resolve the effective server config (global servers.json + per-session
 *    toggles) through [MCPRepository];
 *  - expand `$$VAR` placeholders in url/headers/env from the app env-var
 *    store (parity with transport/http.py `expand_env`);
 *  - attach OAuth bearer tokens from [MCPOAuthStore], refreshing expired
 *    grants natively and keeping [MCPTokenBridge] in sync so the guest CLI
 *    sees the same rotated tokens;
 *  - enforce [MCPToolPolicy] (per-tool disable switches) on this path too —
 *    previously only the shell command parser enforced it;
 *  - cache one [McpClient] per server, rebuilt when the config fingerprint
 *    changes; stdio clients hold a live guest process.
 *
 * All entry points are BLOCKING (OkHttp sync calls / process IO); callers
 * must run them on Dispatchers.IO.
 */
object McpNativeBridge {

    private const val TAG = "McpNativeBridge"

    /** Daemon-parity default when a stdio server sets no startupTimeoutSeconds. */
    private const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 60

    /** Connect/write are bounded; read stays long for slow tool executions
     *  (parity with httpx.Timeout(connect=15, read=TIMEOUT_SECONDS, …)). */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            // MCP servers are long-lived API endpoints, not browsers.
            .retryOnConnectionFailure(false)
            .build()
    }

    private data class Cached(val fingerprint: String, val client: McpClient)

    private val clients = ConcurrentHashMap<String, Cached>()

    /**
     * [T-mcp-client-rebuild-leak] One monitor per server id guarding the
     * check-then-build-then-publish in [clientFor]. `ConcurrentHashMap` alone made
     * each step atomic but the SEQUENCE not: two IO threads hitting the same server
     * after a config change both read the stale entry, both passed the fingerprint
     * test and both ran `buildClient()` — and for stdio that means two PRoot guest
     * processes plus their reader/stderr threads. The second `clients[id] = …`
     * then replaced the first WITHOUT closing it, leaking the process and its FDs,
     * and a later `invalidate()/close()` tore down the wrong (surviving) instance.
     *
     * Per-id monitors instead of one global lock: a slow node-server startup for
     * one server must not stall calls to an unrelated one.
     */
    private val clientBuildLocks = ConcurrentHashMap<String, Any>()

    private var envRepoRef: EnvVarRepository? = null

    private fun envRepo(context: Context): EnvVarRepository =
        envRepoRef ?: synchronized(this) {
            envRepoRef ?: EnvVarRepository(context.applicationContext).also { envRepoRef = it }
        }

    private fun envResolver(context: Context): (String) -> String? = { key ->
        envRepo(context).getValue(key)
    }

    // -- public surface --------------------------------------------------------

    /** Text overview of configured servers for `mcp(action="servers")`. */
    fun serversOverview(repo: MCPRepository, sessionId: String): String {
        val overrides = repo.effectiveEnabledMap(sessionId)
        val all = repo.servers.value
        if (all.isEmpty()) {
            return "No MCP servers configured. Add one in Settings → MCP."
        }
        val sb = StringBuilder()
        for (cfg in all) {
            val enabled = overrides[cfg.id] ?: cfg.enabled
            sb.append("- ").append(cfg.id)
            sb.append(if (enabled) " [enabled]" else " [disabled]")
            sb.append(" (").append(cfg.transportSummary).append(')')
            cfg.note?.takeIf { it.isNotBlank() }?.let { sb.append(" — ").append(it) }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * `tools/list` for [server], filtered through [MCPToolPolicy], formatted
     * for the agent. Throws [McpError] on transport failures.
     */
    fun listTools(context: Context, repo: MCPRepository, sessionId: String, server: String): String {
        val (client, cfg) = clientFor(context, repo, sessionId, server)
        val tools = client.listTools()
        val disabled = MCPToolPolicy.disabled(context, cfg.id)
        val filtered = if (disabled.isEmpty()) tools else org.json.JSONArray().apply {
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                if (t.optString("name") !in disabled) put(t)
            }
        }
        val header = "MCP server '${cfg.id}' (${cfg.transportSummary}):\n"
        val body = McpJsonRpc.formatToolList(filtered)
        val hidden = tools.length() - filtered.length()
        val tail = if (hidden > 0) "\n($hidden tool(s) disabled in Settings → MCP)" else ""
        return header + body + tail
    }

    /**
     * `tools/call`. Enforces the per-tool policy BEFORE touching the network.
     * Returns the flattened outcome; `isError` reflects the server verdict.
     */
    fun callTool(
        context: Context,
        repo: MCPRepository,
        sessionId: String,
        server: String,
        tool: String,
        arguments: JSONObject,
    ): McpJsonRpc.CallOutcome {
        val (client, cfg) = clientFor(context, repo, sessionId, server)
        if (MCPToolPolicy.isDisabled(context, cfg.id, tool)) {
            throw McpError(
                "TOOL_DISABLED",
                "MCP tool \"$tool\" on ${cfg.id} is disabled in Settings → MCP.",
            )
        }
        val result = client.callTool(tool, arguments)
        return McpJsonRpc.formatCallResult(result)
    }

    /** Drop the cached client for [server] (config changed / process died). */
    fun invalidate(server: String) {
        clients.remove(server)?.let { runCatching { it.client.close() } }
    }

    /** Close every cached client — app shutdown / sandbox teardown. */
    fun closeAll() {
        for ((name, cached) in clients) {
            runCatching { cached.client.close() }
                .onFailure { AppLogger.warning(TAG, "close($name): ${it.message}") }
        }
        clients.clear()
    }

    // -- client construction ---------------------------------------------------

    private fun resolveConfig(
        repo: MCPRepository,
        sessionId: String,
        server: String,
    ): MCPServerConfig {
        val overrides = repo.effectiveEnabledMap(sessionId)
        val cfg = repo.servers.value.firstOrNull { it.id == server }
            ?: throw McpError(
                "NOT_FOUND",
                "No MCP server named '$server'. Configured: " +
                    repo.servers.value.joinToString(", ") { it.id }.ifEmpty { "(none)" },
            )
        val enabled = overrides[cfg.id] ?: cfg.enabled
        if (!enabled) {
            throw McpError(
                "NOT_FOUND",
                "MCP server '$server' is disabled" +
                    (if (overrides.containsKey(cfg.id)) " for this session" else " in Settings → MCP") + ".",
            )
        }
        return cfg
    }

    private fun fingerprint(cfg: MCPServerConfig): String = listOf(
        cfg.isStdio.toString(), cfg.url, cfg.command, cfg.args.toString(),
        cfg.env.toSortedMap().toString(), cfg.headers.toSortedMap().toString(),
        cfg.oauth?.clientId, cfg.oauth?.mode, cfg.startupTimeoutSeconds,
    ).joinToString("|")

    /**
     * Cache-key fragment for the built-in-server wiring (see
     * [BuiltinLoopbackResolver]): the effective URL while wired,
     * "builtin-stopped" while the entry targets the built-in port but the
     * server is down, and "external" otherwise. Any transition rebuilds
     * the client.
     */
    private fun loopbackFingerprint(context: Context, cfg: MCPServerConfig): String {
        if (cfg.isStdio) return "no-url"
        val raw = cfg.url ?: return "no-url"
        val expanded = runCatching { McpJsonRpc.expandEnv(raw, envResolver(context)) }.getOrDefault(raw)
        return when (val w = builtinWiring(expanded)) {
            is BuiltinLoopbackResolver.Outcome.Wired -> "wired:${w.effectiveUrl}"
            BuiltinLoopbackResolver.Outcome.ServerNotRunning -> "builtin-stopped"
            BuiltinLoopbackResolver.Outcome.NotBuiltin -> "external"
        }
    }

    /** Current wiring outcome for an env-expanded URL against the built-in MCP server. */
    private fun builtinWiring(url: String): BuiltinLoopbackResolver.Outcome =
        BuiltinLoopbackResolver.resolve(
            url = url,
            configuredPort = McpServerManager.currentPort(),
            actualPort = McpServerManager.actualPort(),
            running = McpServerManager.state.value.status == McpServerManager.Status.RUNNING,
        )

    private fun clientFor(
        context: Context,
        repo: MCPRepository,
        sessionId: String,
        server: String,
    ): Pair<McpClient, MCPServerConfig> {
        val cfg = resolveConfig(repo, sessionId, server)
        // Include the built-in loopback wiring in the cache key: a port hop
        // or the server starting/stopping must rebuild the client, not reuse
        // a stale URL or a client without the built-in token.
        val fp = fingerprint(cfg) + "|" + loopbackFingerprint(context, cfg)
        val cachedNow = clients[cfg.id]
        if (cachedNow != null && cachedNow.fingerprint == fp) return cachedNow.client to cfg
        // [T-mcp-client-rebuild-leak] Re-check under the per-id monitor: the
        // winner of the race publishes, everyone else adopts its client instead of
        // building a second one. The stale/changed previous instance is closed
        // HERE (inside the lock), so it can never be leaked nor double-closed by a
        // concurrent rebuild.
        val monitor = clientBuildLocks.getOrPut(cfg.id) { Any() }
        synchronized(monitor) {
            val cached = clients[cfg.id]
            if (cached != null && cached.fingerprint == fp) return cached.client to cfg
            // Config changed (or first use): rebuild. A dead stdio process also
            // lands here via invalidate() from the caller's error path.
            if (cached != null) runCatching { cached.client.close() }
            val client = buildClient(context, cfg)
            clients[cfg.id] = Cached(fp, client)
            return client to cfg
        }
    }

    private fun buildClient(context: Context, cfg: MCPServerConfig): McpClient {
        val resolve = envResolver(context)
        return if (!cfg.isStdio) {
            var url = cfg.url?.let { McpJsonRpc.expandEnv(it, resolve) }
                ?: throw McpError(McpError.PROTOCOL_ERROR, "http server '${cfg.id}' has no url")
            val headers = cfg.headers.mapValues { (_, v) -> McpJsonRpc.expandEnv(v, resolve) }
            // [T-mcp-loopback-wiring] An entry pointing at the built-in
            // server's configured loopback port follows the port the server
            // actually bound (it falls back when the port is squatted) and
            // carries the built-in bearer token — no hand-copied headers.
            val builtin = builtinWiring(url)
            when (builtin) {
                is BuiltinLoopbackResolver.Outcome.Wired -> url = builtin.effectiveUrl
                BuiltinLoopbackResolver.Outcome.ServerNotRunning -> throw McpError(
                    McpError.CONNECTION_ERROR,
                    "built-in MCP server is not running — enable it in Settings → MCP, then retry",
                )
                BuiltinLoopbackResolver.Outcome.NotBuiltin -> Unit
            }
            McpHttpClient(
                cfg.id,
                url,
                headers,
                if (builtin is BuiltinLoopbackResolver.Outcome.Wired) {
                    // Live read: a token regenerated in Settings applies to
                    // the next request without rebuilding the client.
                    { McpServerManager.configStore()?.currentToken() }
                } else {
                    oauthTokenProvider(context, cfg) ?: { null }
                },
                oauthConfigured = cfg.oauth != null && builtin !is BuiltinLoopbackResolver.Outcome.Wired,
                http = httpClient,
            )
        } else {
            val command = cfg.command?.takeIf { it.isNotBlank() }
                ?: throw McpError(McpError.PROTOCOL_ERROR, "stdio server '${cfg.id}' has no command")
            McpStdioClient(
                cfg.id,
                buildSpawnSpec(context, cfg, command, resolve),
                startupTimeoutMs = (cfg.startupTimeoutSeconds ?: DEFAULT_STARTUP_TIMEOUT_SECONDS) * 1000L,
            )
        }
    }

    /**
     * Proot argv + environments for a guest stdio server. Mirrors
     * PersistentShell.startProcess for the proot prefix/env, and daemon.py
     * `MCPStdioServer.start` for the server-side env overlay. The command is
     * exec'd through `sh -c 'exec "$0" "$@"'` so PATH resolution happens
     * inside the rootfs (npx/python/uvx…), with no shell quoting hazards.
     */
    private fun buildSpawnSpec(
        context: Context,
        cfg: MCPServerConfig,
        command: String,
        resolve: (String) -> String?,
    ): McpStdioClient.SpawnSpec {
        if (!PRootKernel.isBooted) {
            throw McpError(
                McpError.CONNECTION_ERROR,
                "Sandbox not booted — run any shell_execute command once, then retry the MCP call.",
            )
        }
        val rm = RootfsManager.getInstance(context)
        val argv = mutableListOf(
            rm.prootBinary.absolutePath,
            "-0", "--link2symlink", "--kill-on-exit",
            "-r", rm.rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
        )
        for ((linuxPath, hostPath) in PRootKernel.bindMounts) {
            argv.add("-b")
            argv.add("$hostPath:$linuxPath")
        }
        argv.addAll(listOf("/bin/sh", "-c", "exec \"\$0\" \"\$@\"", command))
        argv.addAll(cfg.args)

        val processEnv = buildMap {
            put("PROOT_TMP_DIR", PRootKernel.getProotTmpDir(context).absolutePath)
            if (PRootKernel.nativeLibDir.isNotEmpty()) put("LD_LIBRARY_PATH", PRootKernel.nativeLibDir)
            if (PRootKernel.prootLoaderPath.isNotEmpty()) put("PROOT_LOADER", PRootKernel.prootLoaderPath)
            if (PRootKernel.prootLoader32Path.isNotEmpty()) put("PROOT_LOADER_32", PRootKernel.prootLoader32Path)
        }
        val guestEnv = buildMap {
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", "/root")
            put("TMPDIR", "/tmp")
            put("LANG", "C.UTF-8")
            put("TERM", "dumb")
            for ((k, v) in cfg.env) put(k, McpJsonRpc.expandEnv(v, resolve))
        }
        return McpStdioClient.SpawnSpec(argv, processEnv, guestEnv)
    }

    // -- OAuth ------------------------------------------------------------------

    /**
     * Bearer provider for OAuth-enabled servers: current access token, or a
     * natively refreshed one when the stored grant expired (60s early margin,
     * same as the CLI). Null when unauthorized/expired-unrefreshable — the
     * request then goes out bare and the server's 401 maps to AUTH_REQUIRED.
     */
    private fun oauthTokenProvider(context: Context, cfg: MCPServerConfig): (() -> String?)? {
        if (cfg.oauth == null) return null
        val appContext = context.applicationContext
        return provider@{
            val tokens = MCPOAuthStore.tokens(appContext, cfg.id) ?: return@provider null
            val now = System.currentTimeMillis()
            if (tokens.expiresAtMs <= 0 || now < tokens.expiresAtMs - 60_000) {
                tokens.accessToken
            } else {
                refreshTokens(appContext, cfg, tokens)?.accessToken
            }
        }
    }

    /**
     * refresh_token grant against the server's token endpoint. On success the
     * store AND the guest token bridge are updated (the bridge write merges
     * refresh tokens, so a response without one never downgrades the CLI's
     * copy). Best-effort: any failure returns null → AUTH_REQUIRED surfaces.
     */
    private fun refreshTokens(
        context: Context,
        cfg: MCPServerConfig,
        tokens: MCPOAuthStore.StoredTokens,
    ): MCPOAuthStore.StoredTokens? {
        val oauth = cfg.oauth ?: return null
        val refreshToken = tokens.refreshToken ?: return null
        if (oauth.tokenEndpoint.isBlank() || oauth.clientId.isBlank()) return null
        return try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", oauth.clientId)
            MCPOAuthStore.clientSecret(context, cfg.id)?.let { form.add("client_secret", it) }
            // RFC 8707 resource indicator: reuse whatever the authorize flow
            // recorded in the bridge file (the store has no resource field).
            bridgeResource(context, cfg.id)?.let { form.add("resource", it) }
            val request = okhttp3.Request.Builder()
                .url(oauth.tokenEndpoint)
                .post(form.build())
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.warning(TAG, "[${cfg.id}] token refresh HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) return null
                val expiresIn = json.optLong("expires_in", 0L)
                val updated = MCPOAuthStore.StoredTokens(
                    accessToken = access,
                    refreshToken = json.optString("refresh_token", "").ifBlank { refreshToken },
                    expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                )
                MCPOAuthStore.setTokens(context, cfg.id, updated)
                runCatching {
                    MCPTokenBridge.write(
                        context, cfg.id, updated, oauth.tokenEndpoint,
                        oauth.clientId, MCPOAuthStore.clientSecret(context, cfg.id),
                        bridgeResource(context, cfg.id),
                    )
                }
                AppLogger.info(TAG, "[${cfg.id}] access token refreshed natively")
                updated
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[${cfg.id}] token refresh failed: ${e.message}")
            null
        }
    }

    private fun bridgeResource(context: Context, server: String): String? = runCatching {
        JSONObject(File(MCPTokenBridge.oauthDir(context), "$server.json").readText())
            .optString("resource", "").ifBlank { null }
    }.getOrNull()
}
