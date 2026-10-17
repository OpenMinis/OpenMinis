package com.openminis.app.mcp.oauth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.auth.OAuthCallbackServer
import com.openminis.app.auth.OAuthManager
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * [T-android-mcp-oauth] Drives the MCP Static-OAuth PKCE Authorization Code
 * flow (RFC 7636 + RFC 8252 loopback redirect + RFC 8707 resource). Android
 * port of iOS `MCPOAuthController.authorize`, reusing the app's existing
 * [OAuthCallbackServer] + Chrome Custom Tab pattern (same as ClaudeOAuthManager).
 *
 * Phase 2 scope: authorize() runs the browser round-trip and token exchange and
 * stores the issued tokens in [MCPOAuthStore]. The in-guest transport bridge
 * (materializing refresh material for minis-mcp-cli) and autonomous refresh are
 * intentionally out of this phase.
 */
class MCPOAuthController(private val context: Context) {

    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var callbackServer: OAuthCallbackServer? = null

    /**
     * Run the full authorize flow for [server] against [oauth]. [serverUrl] is
     * the server's own URL, used verbatim as the RFC 8707 resource indicator
     * (canonicalized). Opens the authorization page in a Custom Tab, waits (up
     * to 5 min) for the loopback callback, verifies state, exchanges the code,
     * and persists tokens. Never throws — returns a [Result].
     */
    suspend fun authorize(server: String, serverUrl: String?, oauth: MCPOAuthConfig): Result {
        if (!oauth.isConfigured) {
            return Result.Failed("OAuth is not fully configured (client id + endpoints required).")
        }
        // MCPOAUTH-1: the fixed 54546 collides with a previous/parallel
        // listener and the login then waits out the 5-minute timeout. When the
        // user has not pinned a redirect URI, pick a free loopback port and
        // build the redirect from it (RFC 8252 allows any loopback port for
        // native clients). A user-configured redirect URI is honored verbatim
        // because the IdP may have it registered.
        val configuredRedirect = oauth.redirectUri?.trim()?.takeIf { it.isNotEmpty() }
        val redirect: String
        val port: Int
        if (configuredRedirect != null) {
            redirect = configuredRedirect
            val configuredPort = runCatching { URI(redirect).port }.getOrDefault(-1)
            if (configuredPort <= 0) {
                return Result.Failed(
                    "Redirect URI must include an explicit loopback port, e.g. $DEFAULT_REDIRECT_URI.",
                )
            }
            port = configuredPort
        } else {
            port = findFreeLoopbackPort(LOOPBACK_PORT)
            redirect = "http://localhost:$port/callback"
        }
        val redirectHost = runCatching { URI(redirect).host?.lowercase() }.getOrNull()
        if (redirectHost != "localhost" && redirectHost != "127.0.0.1") {
            return Result.Failed("Redirect URI must be a loopback address, e.g. $DEFAULT_REDIRECT_URI.")
        }
        val resource = McpPkce.canonicalResourceUri(serverUrl)
        val pkce = McpPkce.newPkce()
        val authUrl = McpPkce.buildAuthorizationUrl(
            authorizationEndpoint = oauth.authorizationEndpoint,
            clientId = oauth.clientId,
            redirectUri = redirect,
            pkce = pkce,
            resource = resource,
            scopes = oauth.scopes,
        )

        return try {
        withTimeout(AUTHORIZE_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            callbackServer?.stop()
            callbackServer = null

            val callback = try {
                suspendCancellableCoroutine<Pair<String, String?>?> { cont ->
                    val callbackPath = runCatching { URI(redirect).path }.getOrNull().orEmpty().ifEmpty { "/oauth/callback" }
                    val srv = OAuthCallbackServer(
                        port,
                        expectedPath = callbackPath,
                        onCode = { code, state -> if (cont.isActive) cont.resume(code to state) },
                        onDenied = { if (cont.isActive) cont.resume(null) },
                    )
                    callbackServer = srv
                    // If the user dismisses the Custom Tab, stop() fires this so
                    // we don't hang until the OS eventually tears the socket down.
                    srv.onExternalCancel = { if (cont.isActive) cont.resume(null) }
                    srv.start()
                    AppLogger.info(TAG, "[Authorize] '$server' callback server on :$port")

                    cont.invokeOnCancellation {
                        srv.stop()
                        callbackServer = null
                    }

                    // Do NOT set FLAG_ACTIVITY_NEW_TASK — MainActivity is
                    // singleTask (matches ClaudeOAuthManager's note about IME).
                    CustomTabsIntent.Builder().setShowTitle(true).build()
                        .launchUrl(context, Uri.parse(authUrl))
                    AppLogger.info(TAG, "[Authorize] '$server' opened Custom Tab")
                }
            } finally {
                callbackServer?.stop()
                callbackServer = null
            } ?: return@withContext Result.Cancelled

            val (code, state) = callback
            // [T-android-mcp-oauth-state-required] RFC 6749 §4.1.2: state MUST
            // be verified. We always send one, so a callback WITHOUT it is an
            // anomaly (or a forged loopback hit) — reject, never skip.
            if (state != pkce.state) {
                AppLogger.warning(TAG, "[Authorize] '$server' state mismatch")
                return@withContext Result.Failed("State mismatch in the OAuth callback.")
            }

            exchangeCode(server, oauth, redirect, resource, code, pkce.verifier)
        }
        }
        } catch (exc: TimeoutCancellationException) {
            // [T-android-mcp-oauth-timeout] The docstring promised "up to 5
            // min" but nothing enforced it — an abandoned authorize (tab
            // backgrounded, consent never given) hung the coroutine forever.
            callbackServer?.stop()
            callbackServer = null
            AppLogger.warning(TAG, "[Authorize] '$server' timed out")
            Result.Failed("Authorization timed out — please try again.")
        }
    }

    private fun exchangeCode(
        server: String,
        oauth: MCPOAuthConfig,
        redirect: String,
        resource: String?,
        code: String,
        verifier: String,
    ): Result {
        val body = buildTokenExchangeBody(
            code = code,
            redirect = redirect,
            clientId = oauth.clientId,
            verifier = verifier,
            clientSecret = MCPOAuthStore.clientSecret(context, server),
            resource = resource,
        )
        val request = Request.Builder()
            .url(oauth.tokenEndpoint)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // [T-android-oauth-log-redaction] `Result.Failed.message` is
                    // surfaced in the MCP settings UI AND logged through AppLogger,
                    // which persists to filesDir/logs. A malformed SUCCESS body
                    // arriving on this error path would otherwise dump live
                    // credentials (access_token / id_token / client_secret echo).
                    // OAuthManager.sanitizeBody is the app-wide rule — same as the
                    // OpenAI / Claude / Gemini / xAI token failure logs — so it goes
                    // through it instead of a bare take().
                    AppLogger.error(
                        TAG,
                        "[Authorize] '$server' token exchange HTTP ${resp.code}: ${OAuthManager.sanitizeBody(text)}",
                    )
                    return Result.Failed("Token exchange failed (${resp.code}): ${OAuthManager.sanitizeBody(text)}")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) {
                    return Result.Failed("Token endpoint returned no access_token.")
                }
                val expiresIn = json.optLong("expires_in", 0L)
                val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
                MCPOAuthStore.setTokens(
                    context, server,
                    MCPOAuthStore.StoredTokens(
                        accessToken = access,
                        refreshToken = json.optString("refresh_token", "").ifBlank { null },
                        expiresAtMs = expiresAt,
                    ),
                )
                // [T-android-mcp-token-bridge] Hand the tokens to the in-guest
                // CLI; without this the transport bridge the Python side reads
                // never materializes on Android and every call 401s.
                MCPTokenBridge.write(
                    context, server,
                    tokens = MCPOAuthStore.StoredTokens(
                        accessToken = access,
                        refreshToken = json.optString("refresh_token", "").ifBlank { null },
                        expiresAtMs = expiresAt,
                    ),
                    tokenEndpoint = oauth.tokenEndpoint,
                    clientId = oauth.clientId,
                    clientSecret = MCPOAuthStore.clientSecret(context, server),
                    resource = resource,
                )
                AppLogger.info(
                    TAG,
                    "[Authorize] '$server' OK (hasRefresh=${json.has("refresh_token")}, expiresIn=${expiresIn}s)",
                )
                Result.Success
            }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Authorize] '$server' token exchange failed: ${t.message}")
            Result.Failed("Token exchange failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "MCPOAuthController"

        /**
         * First bindable loopback port at/after [preferred]. Binding and
         * releasing is inherently racy; [OAuthCallbackServer] still handles a
         * late BindException, but this avoids the common "previous listener
         * still holds 54546" failure.
         */
        internal fun findFreeLoopbackPort(preferred: Int = LOOPBACK_PORT, span: Int = 20): Int {
            val loopback = java.net.InetAddress.getByName("127.0.0.1")
            for (p in preferred until preferred + span) {
                try {
                    java.net.ServerSocket(p, 1, loopback).use { return p }
                } catch (_: java.io.IOException) {
                    // busy — try the next one
                }
            }
            return java.net.ServerSocket(0, 1, loopback).use { it.localPort }
        }

        /**
         * Assemble the form-urlencoded token-exchange body for the
         * authorization_code grant (MCP auth spec / RFC 6749). client_secret is
         * included only when present; the RFC 8707 `resource` only when non-null.
         * Extracted for unit testing. Mirrors iOS authorize()'s token form.
         */
        internal fun buildTokenExchangeBody(
            code: String,
            redirect: String,
            clientId: String,
            verifier: String,
            clientSecret: String?,
            resource: String?,
        ): String {
            val form = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirect,
                "client_id" to clientId,
                "code_verifier" to verifier,
            )
            if (!clientSecret.isNullOrEmpty()) form["client_secret"] = clientSecret
            if (!resource.isNullOrEmpty()) form["resource"] = resource
            return form.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
        }

        /** Fixed loopback port for MCP OAuth — distinct from ClaudeOAuthManager's
         *  54545 so a concurrent login never collides. Mirrors iOS 54546. */
        private const val AUTHORIZE_TIMEOUT_MS = 5 * 60 * 1000L
        const val LOOPBACK_PORT = 54546
        const val DEFAULT_REDIRECT_URI = "http://localhost:$LOOPBACK_PORT/callback"
    }
}
