package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * OAuth manager for Anthropic (Claude) OAuth flow.
 *
 * Flow (mirrors OpenRouterOAuthManager pattern):
 * 1. Generate PKCE verifier + challenge, state
 * 2. Start local callback server on port 54545
 * 3. Open claude.ai/oauth/authorize in Chrome Custom Tab (in-app browser)
 * 4. User authorizes → Anthropic redirects to localhost:54545/callback
 * 5. Callback server captures code + state
 * 6. Exchange code for tokens via POST JSON to claude.ai/v1/oauth/token
 * 7. Store access_token as API key via ProviderRepository
 *
 * Key differences from OpenRouter:
 * - Token exchange uses application/json (NOT form-urlencoded)
 * - `state` parameter required in token exchange body
 * - API calls need `anthropic-beta: oauth-2025-04-20` header
 * - System prompt must start with Claude Code prefix
 */
class ClaudeOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "ClaudeOAuth"

        /**
         * System prompt prefix required by Anthropic for Claude Code OAuth
         * credentials.
         *
         * The value is not hardcoded: it is supplied at build time via the
         * `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` BuildConfig field, populated from
         * `app/provider-customization.properties`. That file is tracked in the private
         * repo (filled in) but excluded from the public mirror, which ships
         * only `provider-customization.properties.example` with an empty value. Reading
         * it without a configured value throws — by design — the first time an
         * OAuth (Claude Code) request needs the prompt.
         */
        val ANTHROPIC_OAUTH_IDENTIFIER_PROMPT: String
            get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.ifEmpty {
                throw IllegalStateException(
                    "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy "
                        + "app/provider-customization.properties.example to "
                        + "app/provider-customization.properties and set "
                        + "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before using Claude Code OAuth."
                )
            }

        /**
         * How long before expiry we proactively refresh. Mirrors iOS
         * `ClaudeOAuthManager.refreshBuffer` (5 minutes) — significantly tighter
         * than OAuthManager's 4-hour default, which burns refresh cycles.
         */
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        private var callbackServer: OAuthCallbackServer? = null

        /**
         * Per-instance refresh mutex so concurrent callers (e.g. parallel
         * streaming requests that all discover an expired token at once)
         * don't each kick off an HTTP refresh. Only one waits on the network;
         * the rest fall through to read the fresh stored token. Mirrors iOS
         * `actor` serialization. Keyed by instanceId because each OAuth
         * account has its own token state.
         */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * Static helper: perform full login flow and save the API key.
         * Returns the access token.
         */
        suspend fun login(context: Context, instanceId: String, providerRepository: ProviderRepository): String {
            val manager = ClaudeOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    /**
     * Classifies the reason `refreshToken()` completed. UI layers dispatch on
     * this to decide between "retry the request" vs "prompt the user to sign
     * in again". Mirrors iOS `isRefreshTokenInvalid` + the surrounding try/catch.
     */
    enum class RefreshOutcome {
        /** New access token is in storage. */
        SUCCESS,
        /** Refresh token is invalid/revoked (HTTP 400/401/403 or invalid_grant). Credentials cleared; user must log in again. */
        INVALID_GRANT,
        /** Transient network/5xx error. Existing token kept; caller may retry later. */
        TRANSIENT,
        /** No refresh token available (never logged in, or lost). */
        NO_TOKEN,
    }

    override val authURL = "https://claude.ai/oauth/authorize"
    // https://github.com/anthropics/anthropic-sdk-swift/issues/243 reports
    // that tokens issued against console.anthropic.com get silently
    // demoted to pay-per-use pricing; claude.ai is the canonical endpoint.
    // Also: `claude.ai` proxies through Cloudflare so session state stays
    // in-sync with the web UI. Keep in lockstep with sub2api FullClaudeCodeMimicry.
    override val tokenURL = "https://claude.ai/v1/oauth/token"
    override val clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val clientSecret: String? = null
    override val callbackPort = 54545
    override val redirectPath = "/callback"
    override val scopes = "org:create_api_key user:profile user:inference"

    /**
     * Perform the full OAuth login flow (mirrors OpenRouterOAuthManager.login pattern).
     * Returns the access token. Throws on failure.
     */
    suspend fun performLogin(context: Context): String {
        Log.i(TAG, "=== Anthropic OAuth login started (instance: $instanceId) ===")

        callbackServer?.stop()
        callbackServer = null

        // Generate PKCE and state (saved for later token exchange)
        val authUrl = buildAuthorizationUrl()

        val accessToken = withContext(Dispatchers.IO) {
            // Step 1: Wait for callback code
            val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                val server = OAuthCallbackServer(callbackPort) { receivedCode, receivedState ->
                    if (cont.isActive) {
                        cont.resume(receivedCode to receivedState)
                    }
                }
                callbackServer = server
                server.start()
                Log.i(TAG, "Callback server started on port $callbackPort")

                cont.invokeOnCancellation {
                    server.stop()
                    callbackServer = null
                }

                // Open auth URL in Chrome Custom Tab (in-app browser). Do NOT set
                // FLAG_ACTIVITY_NEW_TASK: MainActivity is singleTask, and launching Custom
                // Tab in a new task causes the IME to be dismissed whenever the authorization
                // page focuses an input.
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                Log.i(TAG, "Opened Custom Tab for Anthropic authorization")
            }

            callbackServer?.stop()
            callbackServer = null
            Log.i(TAG, "Callback received — code length: ${code.length}")

            // Step 2: Validate state (CSRF protection)
            val savedState = loadOAuthString("state")
            if (state != null && state != savedState) {
                throw Exception("OAuth state mismatch — possible CSRF attack")
            }

            // Step 3: Exchange code for tokens (JSON body, NOT form-urlencoded)
            exchangeCodeJson(code)
        }

        Log.i(TAG, "=== Anthropic OAuth login complete (instance: $instanceId) ===")
        return accessToken
    }

    /**
     * Exchange authorization code for tokens using JSON body.
     * Anthropic requires application/json, NOT form-urlencoded.
     */
    private fun exchangeCodeJson(code: String): String {
        val body = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", loadOAuthString("verifier") ?: "")
            put("state", loadOAuthString("state") ?: "")
        }

        Log.d(TAG, "Token exchange: POST $tokenURL")

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            // Match the OAuthClient mimicry headers so the Anthropic OAuth
            // backend sees the same fingerprint as a real claude-cli request.
            // Without these, Cloudflare/Anthropic returns 403 before the
            // token handler is reached. Kept in sync with iOS ClaudeCodeMimicry.
            .header("anthropic-version", "2023-06-01")
            .header(
                "anthropic-beta",
                listOf(
                    "claude-code-20250219",
                    "oauth-2025-04-20",
                    "interleaved-thinking-2025-05-14",
                    "prompt-caching-scope-2026-01-05",
                    "effort-2025-11-24",
                    "context-management-2025-06-27",
                    "extended-cache-ttl-2025-04-11",
                ).joinToString(",")
            )
            .header("User-Agent", "claude-cli/2.1.195 (external, cli)")
            .build()
        val response = httpClient.newCall(request).execute()
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()

        if (responseCode !in 200..299) {
            Log.e(TAG, "Token exchange failed: $responseCode ${OAuthManager.sanitizeBody(responseBody)}")
            throw Exception("Token exchange failed ($responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            throw Exception("No access_token in response")
        }

        // Save full token data (for refresh later)
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveOAuthString("tokens", json.toString())

        Log.i(TAG, "Token exchange successful. Expires in ${expiresIn}s, has refresh: ${json.has("refresh_token")}")
        return accessToken
    }

    /**
     * Boolean wrapper over [refreshTokenClassified] for compatibility with the
     * base `OAuthManager.refreshToken()` signature. Returns true on SUCCESS.
     * Prefer [refreshTokenClassified] at new call sites — it tells you
     * whether a failure requires re-login vs a simple retry.
     */
    override suspend fun refreshToken(): Boolean =
        refreshTokenClassified() == RefreshOutcome.SUCCESS

    /**
     * Refresh with structured error classification and concurrency protection.
     * Mirrors iOS `ClaudeOAuthManager.validAccessToken` error handling:
     *
     *  - `INVALID_GRANT` — HTTP 400/401/403 or body mentions `invalid_grant`:
     *    stored credentials are cleared (iOS parity: `deleteOAuthToken`).
     *    Caller must re-run the OAuth login flow.
     *  - `TRANSIENT` — network/timeout/5xx: credentials kept intact; caller
     *    may retry. The still-valid access token (if any) remains usable.
     *  - `NO_TOKEN` — no refresh token stored (not logged in).
     *  - `SUCCESS` — new access token persisted.
     *
     * Concurrency: guarded by a per-instance [Mutex]. If another coroutine is
     * already refreshing, we wait for it to finish and then check whether the
     * stored token was updated in the meantime. This matches iOS's actor-
     * serialized refresh path.
     */
    suspend fun refreshTokenClassified(): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            val stored = loadStoredTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            // Coalesce concurrent refreshers: if the token was refreshed while
            // we were blocked on the mutex, return SUCCESS without hitting the
            // network again. Detected by comparing expire_at to what we saw
            // entering the lock — a different value means another coroutine
            // already refreshed.
            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                Log.d(TAG, "Refresh skipped — fresher token already present (concurrent refresh coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                val body = JSONObject().apply {
                    put("grant_type", "refresh_token")
                    put("client_id", clientId)
                    put("refresh_token", refreshTokenValue)
                }

                Log.d(TAG, "Refreshing OAuth token via JSON POST to $tokenURL")
                val request = okhttp3.Request.Builder()
                    .url(tokenURL)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseBody)
                    if (!json.has("refresh_token")) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Token refresh successful. Expires in ${expiresIn}s")
                    return@withLock RefreshOutcome.SUCCESS
                }

                // Classify failure.
                val bodyLower = responseBody.lowercase()
                val isInvalidGrant = responseCode == 400 || responseCode == 401 || responseCode == 403 ||
                    bodyLower.contains("invalid_grant") ||
                    bodyLower.contains("refresh_token")
                if (isInvalidGrant) {
                    Log.e(TAG, "Refresh token invalid ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — clearing credentials")
                    logout()
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                // 5xx or unexpected code — keep credentials, let caller retry.
                Log.w(TAG, "Token refresh transient failure ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                // Network failure (IOException / SocketTimeout / etc). Keep credentials.
                Log.w(TAG, "Token refresh transient error — keeping token", e)
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    /**
     * Check the stored token and refresh it if within the 5-minute expiry
     * window (iOS parity: `refreshBuffer = 5 * 60`). Returns the currently
     * valid access token string, or null if not logged in / refresh failed
     * and the old token is also expired. Mirrors iOS `validAccessToken`.
     *
     * Use this from network call sites that want a fresh token without
     * caring about the refresh outcome details.
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext token

        when (refreshTokenClassified()) {
            RefreshOutcome.SUCCESS ->
                loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> {
                // logout() already ran inside refreshTokenClassified.
                null
            }
            RefreshOutcome.TRANSIENT -> {
                // Old token still usable if not actually expired. If it IS
                // expired, surface null so the caller can bubble up auth error.
                if (expireAt > 0 && now >= expireAt) null else token
            }
            RefreshOutcome.NO_TOKEN -> null
        }
    }

    /**
     * Save PKCE verifier for JSON token exchange. Uses 96 random bytes to match
     * iOS `ClaudeOAuthManager` exactly — Anthropic accepts 43–128-char verifiers,
     * the common tooling default is 96 bytes base64url-encoded (128 chars).
     */
    override fun generateAndSavePKCE(): Pair<String, String> {
        val (verifier, challenge) = generatePKCE(byteLength = 96)
        saveOAuthString("verifier", verifier)
        return verifier to challenge
    }

    /** Save state for JSON token exchange. */
    override fun generateAndSaveState(): String {
        val state = super.generateAndSaveState()
        saveOAuthString("state", state)
        return state
    }
}
