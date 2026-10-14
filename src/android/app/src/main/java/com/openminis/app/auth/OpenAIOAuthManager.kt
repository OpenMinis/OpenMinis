package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ProxySelector
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.resume

/**
 * Thrown by [OpenAIOAuthManager] when DNS lookup or socket connect to
 * the OpenAI auth endpoint fails. The UI catches this specifically to
 * render the localized "check network / proxy" prompt instead of the
 * raw OkHttp message.
 */
class OAuthNetworkUnreachableException(cause: Throwable) : Exception(cause.message, cause)

class OpenAIOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "CodexOAuth"

        /**
         * Static helper: perform full login flow and save the access token.
         * Returns the access token. Mirrors ClaudeOAuthManager.login().
         */
        suspend fun login(context: Context, instanceId: String, providerRepository: com.openminis.app.data.repository.ProviderRepository): String {
            val manager = OpenAIOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    private var loginCallbackServer: OAuthCallbackServer? = null
    private var expectedState: String? = null

    /**
     * Perform the full OAuth login flow.
     * Returns the access token. Throws on failure.
     */
    suspend fun performLogin(context: Context): String {
        AppLogger.info(TAG, "=== OAuth login started (instance=$instanceId) ===")

        loginCallbackServer?.stop()
        loginCallbackServer = null

        val authUrl = buildAuthorizationUrl()
        AppLogger.info(TAG, "authorization flow prepared (callbackPort=$callbackPort)")

        val accessToken = withContext(Dispatchers.IO) {
            // Step 1: Wait for callback code
            val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                val server = OAuthCallbackServer(callbackPort, expectedPath = redirectPath, onCode = { receivedCode, receivedState ->
                    if (cont.isActive) {
                        cont.resume(receivedCode to receivedState)
                    }
                })
                loginCallbackServer = server
                server.start()
                AppLogger.info(TAG, "callback server listening on port $callbackPort path=$redirectPath")

                cont.invokeOnCancellation {
                    server.stop()
                    loginCallbackServer = null
                }

                // Open auth URL in Chrome Custom Tab. Do NOT set FLAG_ACTIVITY_NEW_TASK:
                // MainActivity is singleTask, and launching Custom Tab in a new task causes
                // the IME to be dismissed whenever the authorization page focuses an input.
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                AppLogger.info(TAG, "Custom Tab launched")
            }

            loginCallbackServer?.stop()
            loginCallbackServer = null
            val stateMatches = !expectedState.isNullOrEmpty() && !state.isNullOrEmpty() && state == expectedState
            AppLogger.info(TAG, "callback received: codePresent=${code.isNotEmpty()} statePresent=${!state.isNullOrEmpty()} match=$stateMatches")
            if (!stateMatches) {
                throw IllegalStateException("OAuth state mismatch; refusing to exchange authorization code")
            }

            // Step 2: Exchange code for tokens (JSON body, matching iOS CodexOAuthManager)
            exchangeCodeJson(code)
        }

        AppLogger.info(TAG, "=== OAuth login complete (instance=$instanceId) ===")
        return accessToken
    }

    override val authURL = "https://auth.openai.com/oauth/authorize"
    override val tokenURL = "https://auth.openai.com/oauth/token"
    override val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val clientSecret: String? = null
    override val callbackPort = 1455
    override val redirectPath = "/auth/callback"
    override val scopes = "openid profile email offline_access"

    override fun buildAuthorizationUrl(): String {
        val (verifier, challenge) = generatePKCE()
        saveOAuthString("verifier", verifier)
        val state = generateState()
        expectedState = state
        AppLogger.debug(TAG, "PKCE and state generated")
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "codex_cli_simplified_flow=true",
            "originator=codex_cli_rs",
            "id_token_add_organizations=true",
        ).joinToString("&")
    }

    var accountId: String?
        get() = loadOAuthString("account_id")
        private set(value) { value?.let { saveOAuthString("account_id", it) } }

    var planType: String?
        get() = loadOAuthString("plan_type")
        private set(value) { value?.let { saveOAuthString("plan_type", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        val idToken = json.optString("id_token", "")
        if (idToken.isNotEmpty()) {
            parseIdToken(idToken)
        }
    }

    /**
     * Exchange authorization code for tokens using JSON body.
     * OpenAI requires application/json (same as Anthropic).
     *
     * [T-android-codex-oauth-dns] Uses a dedicated OkHttp client wired to
     * the system [ProxySelector] (the default selector reads
     * `Settings.Global.HTTP_PROXY` + ConnectivityManager's link proxy +
     * any active VPN routing). Pre-fix the shared OAuthManager.httpClient
     * was built with .build() defaults, which silently bypass VPN-style
     * SOCKS / fake-IP DNS proxies common in CN (Clash etc.) — users could
     * complete the browser-side login (Custom Tab uses system network
     * stack) but the in-app token POST resolved auth.openai.com via the
     * raw carrier resolver and got UnknownHostException.
     */
    private fun exchangeCodeJson(code: String): String {
        val jsonBody = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", loadOAuthString("verifier") ?: "")
        }

        AppLogger.info(TAG, "token exchange POST $tokenURL bodyLen=${jsonBody.toString().length}")
        logNetworkEnvironment(tokenURL)

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // Retry up to 3 times — DNS can briefly fail after Custom Tab closes
        var response: okhttp3.Response? = null
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                response = systemAwareHttpClient.newCall(request).execute()
                lastError = null
                break
            } catch (e: Exception) {
                AppLogger.warning(
                    TAG,
                    "token exchange attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                lastError = e
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        if (response == null) {
            AppLogger.error(
                TAG,
                "token exchange failed after 3 attempts: ${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            )
            throw lastError?.let { wrapIfNetworkError(it) } ?: Exception("Token exchange failed")
        }
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()
        AppLogger.info(TAG, "token exchange response received: $responseCode")

        if (responseCode !in 200..299) {
            AppLogger.error(TAG, "token exchange non-2xx: $responseCode")
            // [T-android-oauth-log-redaction] This message is BOTH logged and
            // thrown into the UI, and a token error body from a broken/lying
            // endpoint can carry the credential material itself (a malformed
            // SUCCESS body reaching this error path dumps a live access_token /
            // id_token into logcat and into filesDir/logs). `sanitizeBody` exists
            // precisely for this; the base class's own exchange/refresh failure
            // logs already go through it, so this was the drifting copy.
            throw Exception("Token exchange failed ($responseCode): ${OAuthManager.sanitizeBody(responseBody)}")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            AppLogger.error(TAG, "no access_token field in response")
            throw Exception("No access_token in response")
        }

        // Save full token data (for refresh later)
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveOAuthString("tokens", json.toString())

        // Parse id_token for account info
        val idToken = json.optString("id_token", "")
        if (idToken.isNotEmpty()) {
            parseIdToken(idToken)
        }

        AppLogger.info(
            TAG,
            "token exchange OK expiresIn=${expiresIn}s hasRefresh=${json.has("refresh_token")}",
        )
        return accessToken
    }

    /**
     * Wrap raw network failures so the UI can render the localized
     * "check network / proxy" prompt instead of the cryptic
     * UnknownHostException text. Anything else passes through unchanged
     * so token-format errors / 4xx still surface their original message.
     */
    private fun wrapIfNetworkError(e: Throwable): Throwable =
        if (e is UnknownHostException || e is SocketTimeoutException) {
            OAuthNetworkUnreachableException(e)
        } else {
            e
        }

    /**
     * Log the resolved IP for [url]'s host plus the system-selected
     * proxy chain. Useful for diagnosing the "browser works but OkHttp
     * doesn't" gap on networks where the user is running a VPN-style
     * proxy (Clash, Shadowsocks) that intercepts via fake-IP DNS but
     * doesn't register as an HTTP_PROXY.
     */
    private fun logNetworkEnvironment(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        try {
            val addr = InetAddress.getByName(host)
            AppLogger.info(TAG, "dns: $host → ${addr.hostAddress}")
        } catch (e: Exception) {
            AppLogger.warning(
                TAG,
                "dns lookup FAILED for $host: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
        try {
            val proxies = ProxySelector.getDefault().select(URI(url))
            AppLogger.info(TAG, "proxy chain for $host: $proxies")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "proxy lookup failed: ${e.message}")
        }
    }

    /**
     * Per-call OkHttp client wired to the system ProxySelector +
     * Authenticator with an interceptor that records the chosen route's
     * remote address. Lazy so the proxy state is read fresh each login
     * (network may have switched between cold-start and tap-Sign-in).
     */
    private val systemAwareHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            // [T-doh-resolver-fallback] Resolver fallback for the token
            // exchange. Must stay ahead of the proxySelector below, which is
            // load-bearing for this client.
            .dns(com.openminis.app.network.DohDns)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .proxySelector(ProxySelector.getDefault())
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val route = chain.connection()?.route()
                AppLogger.info(
                    TAG,
                    "okhttp connect: url=${req.url} via proxy=${route?.proxy} socket=${route?.socketAddress}",
                )
                chain.proceed(req)
            }
            .build()
    }

    private fun parseIdToken(token: String) {
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                val json = JSONObject(payload)
                accountId = json.optString("chatgpt_account_id").ifEmpty { null }
                planType = json.optString("chatgpt_plan_type").ifEmpty { null }
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "id_token parse failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
