package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

abstract class OAuthManager(
    protected val context: Context,
    protected val instanceId: String,
) {
    companion object {
        private const val TAG = "OAuthManager"
        private const val KEY_MANUAL_BEARER = "manual_bearer_token"

        /** OAuth `error` codes that mean the refresh token itself is dead. */
        val DEFAULT_FATAL_REFRESH_CODES = setOf(
            "invalid_grant", "invalid_token", "invalid_request",
            "unauthorized_client", "refresh_token_reused",
        )

        /** Shared OkHttp client for all OAuth HTTP requests (respects system proxy). */
        internal val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * [T-android-oauth-log-redaction] Make an HTTP body safe to log:
         * masks the VALUES of known credential fields (access_token,
         * refresh_token, id_token, api_key/key, client_secret, device_code,
         * user_code, code_verifier)
         * and truncates to a diagnostic-sized prefix. OAuth failure bodies
         * are usually just {"error":"invalid_grant"} — but some IdPs echo
         * request material, and a malformed SUCCESS body reaching an error
         * path would otherwise dump live credentials into logcat / the
         * on-device log files.
         */
        fun sanitizeBody(body: String, maxLen: Int = 300): String {
            val masked = Regex(
                // [T-android-oauth-log-redact] + user_code / code_verifier (not bare "code": error bodies use it for error codes).
                "\"(access_token|refresh_token|id_token|api_key|key|client_secret|device_code|user_code|code_verifier)\"\\s*:\\s*\"[^\"]*\"",
            ).replace(body) { m -> "\"${m.groupValues[1]}\":\"***\"" }
            return if (masked.length <= maxLen) masked else masked.take(maxLen) + "…(${masked.length} chars)"
        }

        /**
         * [T-android-group-resolve-skip-uncredentialed] Whether ANY OAuth
         * credential is stored for [instanceId] — either a token bundle from a
         * completed login or a user-pasted manual bearer.
         *
         * Static on purpose. Callers (notably provider routing) need a cheap
         * synchronous yes/no and must not depend on [forInstance], which
         * deliberately omits some provider types (gemini) and would report
         * those as uncredentialed. Token storage is keyed purely by instance
         * id, so reading the prefs directly is both correct and complete.
         *
         * Presence only — this does NOT validate or refresh the token. An
         * expired token still counts as a credential so routing keeps the
         * provider and the request path performs the refresh, matching iOS
         * `hasAnyCredential`.
         */
        fun hasStoredCredential(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            if (!prefs.getString("oauth_tokens_$instanceId", null).isNullOrEmpty()) return true
            return !prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

        /**
         * [T-oauth-keep-credentials] Whether this instance's stored OAuth token
         * bundle is the one a refresh was rejected with. Automatic paths never
         * delete credentials any more; they set this mark instead, the UI shows
         * the instance red, and routing treats it as uncredentialed. Only an
         * explicit Sign Out deletes the bundle.
         *
         * The mark is a SHA-256 fingerprint of the rejected bundle, never the
         * credential, and it only applies while that exact bundle is stored, so
         * a new login (or a restored backup) lapses it with no explicit clear.
         * A stored manual bearer overrides it: that credential is still usable.
         * Static for the same reason as [hasStoredCredential].
         */
        fun needsReauth(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            val mark = prefs.getString(needsReauthKey(instanceId), null) ?: return false
            val blob = prefs.getString("oauth_tokens_$instanceId", null) ?: return false
            if (tokenFingerprint(blob) != mark) return false
            // A manual bearer never refreshes and stands in for the bundle.
            return prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

        private fun needsReauthKey(instanceId: String) = "oauth_needs_reauth_$instanceId"

        private fun tokenFingerprint(blob: String): String =
            MessageDigest.getInstance("SHA-256").digest(blob.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /**
         * [T-oauth-keep-credentials] Structured "the token endpoint rejected
         * this refresh token" test, iOS `OAuthRefreshErrorClassifier` parity:
         * an auth-rejection status, or an exact OAuth `error` code from the
         * JSON body. No substring matching — a body that merely mentions
         * `refresh_token` (e.g. `refresh_token_expiry_ms`) is not a rejection.
         */
        fun isRefreshRejected(status: Int, body: String, fatalErrorCodes: Set<String>): Boolean {
            if (status == 400 || status == 401 || status == 403) return true
            val code = try { JSONObject(body).optString("error", "") } catch (_: Exception) { "" }
            return code.lowercase() in fatalErrorCodes
        }

        /** Create the appropriate OAuthManager for a provider instance. */
        fun forInstance(context: Context, instance: com.openminis.app.data.model.ProviderInstance): OAuthManager? {
            return when (instance.providerType) {
                com.openminis.app.data.model.ProviderType.anthropic -> ClaudeOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.openAI -> OpenAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.xAI -> XAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.kimiCode -> KimiOAuthManager(context, instance.id)
                // [T-copilot-provider] Device-flow OAuth like Kimi, but the
                // token it hands out is Copilot's short-lived session token.
                com.openminis.app.data.model.ProviderType.githubCopilot ->
                    CopilotOAuthManager(context, instance.id)
                else -> null
            }
        }
    }

    abstract val authURL: String
    abstract val tokenURL: String
    abstract val clientId: String
    abstract val clientSecret: String?
    abstract val callbackPort: Int
    abstract val redirectPath: String
    abstract val scopes: String

    // `open` so a provider whose server-side allow-list pins a different
    // loopback spelling can override it (xAI registered 127.0.0.1, not
    // localhost — see [XAIOAuthManager]). The default stays `localhost`,
    // which OpenAI/Codex, Gemini, Antigravity and Claude all register, so
    // this change is override-only and leaves every other provider untouched.
    open val redirectUri: String get() = "http://localhost:$callbackPort$redirectPath"

    // PKCE
    private var codeVerifier: String? = null

    protected fun generatePKCE(): Pair<String, String> = generatePKCE(byteLength = 64)

    /**
     * Generate a PKCE verifier / challenge pair from [byteLength] random bytes.
     * Anthropic Claude Code requires 96 bytes (matches iOS `ClaudeOAuthManager`);
     * other providers use the 64-byte default via the no-arg overload.
     */
    protected fun generatePKCE(byteLength: Int): Pair<String, String> {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateHexPKCE(): Pair<String, String> {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = bytes.joinToString("") { "%02x".format(it) }
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Override to save PKCE verifier for later retrieval (e.g. for JSON token exchange). */
    open fun generateAndSavePKCE(): Pair<String, String> = generatePKCE()

    /** Override to save state for later retrieval. */
    open fun generateAndSaveState(): String = generateState()

    private var currentState: String? = null
    private var callbackServer: OAuthCallbackServer? = null

    open fun buildAuthorizationUrl(): String {
        val (_, challenge) = generateAndSavePKCE()
        currentState = generateAndSaveState()
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$currentState",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
        ).joinToString("&")
    }

    suspend fun startLogin(onComplete: (Boolean) -> Unit) {
        callbackServer?.stop()
        callbackServer = OAuthCallbackServer(callbackPort) { code, state ->
            if (state != null && state != currentState) {
                Log.w(TAG, "State mismatch")
                onComplete(false)
                return@OAuthCallbackServer
            }
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                // [T-android-oauth-foreground-exchange] See OAuthForegroundGate.
                OAuthForegroundGate.awaitForeground(TAG)
                val success = exchangeCode(code)
                withContext(Dispatchers.Main) { onComplete(success) }
            }
        }
        callbackServer?.start()

        val url = buildAuthorizationUrl()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    suspend fun exchangeCode(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = buildTokenParams(code)
            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token exchange failed: $responseCode ${sanitizeBody(responseBody)}")
                return@withContext false
            }

            val json = JSONObject(responseBody)
            saveTokens(json)
            onTokensReceived(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            false
        } finally {
            callbackServer?.stop()
            callbackServer = null
        }
    }

    protected open fun buildTokenParams(code: String): Map<String, String> {
        val params = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to (codeVerifier ?: ""),
        )
        clientSecret?.let { params["client_secret"] = it }
        return params
    }

    open suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext false
        val refreshToken = stored.optString("refresh_token", "").ifEmpty { return@withContext false }

        try {
            val params = mutableMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
            clientSecret?.let { params["client_secret"] = it }

            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token refresh failed: $responseCode")
                if (isRefreshRejected(responseCode, responseBody, DEFAULT_FATAL_REFRESH_CODES)) {
                    markNeedsReauth(refreshToken)
                }
                return@withContext false
            }

            val json = JSONObject(responseBody)
            // Preserve refresh_token if not returned
            if (!json.has("refresh_token")) {
                json.put("refresh_token", refreshToken)
            }
            saveTokens(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            false
        }
    }

    open suspend fun validAccessToken(): String? {
        // Manual bearer token takes precedence — user wants a static token,
        // no refresh attempted. Mirrors iOS behavior for custom proxy providers.
        loadManualBearerToken()?.takeIf { it.isNotEmpty() }?.let { return it }

        val stored = loadStoredTokens() ?: return null
        val token = stored.optString("access_token", "").ifEmpty { return null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        // Refresh if expires within 4 hours
        if (expireAt > 0 && (expireAt - now) < 4 * 3600 * 1000) {
            if (refreshToken()) {
                return loadStoredTokens()?.optString("access_token")
            }
            // Refresh failed and the token is already expired: nothing usable
            // to return. [T-oauth-keep-credentials] Credentials are kept — a
            // rejected refresh was marked inside refreshToken(); a transient
            // failure (network, 5xx) must never cost the user their login.
            if (expireAt > 0 && now >= expireAt) {
                Log.w(TAG, "Token expired and refresh failed — keeping credentials")
                return null
            }
        }
        return token
    }

    /**
     * Whether this instance has a usable credential.
     *
     * [T-android-copilot-not-connected] `open` because a subclass may persist
     * its credential somewhere other than the shared `oauth_tokens_<id>` blob
     * this default inspects. Copilot does exactly that (two tiers under its own
     * keys), and while the method was final its sign-in looked like a failure
     * to every screen that asks this question.
     */
    open fun isAuthenticated(): Boolean {
        if (loadManualBearerToken()?.isNotEmpty() == true) return true
        val stored = loadStoredTokens() ?: return false
        return stored.optString("access_token", "").isNotEmpty()
    }

    fun logout() {
        getEncryptedPrefs().edit()
            .remove("oauth_tokens_$instanceId")
            .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    /** See the companion [needsReauth]. */
    fun needsReauth(): Boolean = needsReauth(context, instanceId)

    /**
     * [T-oauth-keep-credentials] Called instead of [logout] when the token
     * endpoint rejects [staleRefreshToken]. Compare-before-mark: when the
     * stored bundle no longer carries that refresh token, a concurrent refresh
     * already rotated it and this rejection is stale — marking would flag a
     * perfectly good fresh login.
     */
    protected fun markNeedsReauth(staleRefreshToken: String) {
        val blob = loadOAuthString("tokens") ?: return
        val current = try { JSONObject(blob).optString("refresh_token", "") } catch (_: Exception) { "" }
        if (current != staleRefreshToken) {
            Log.w(TAG, "Stale refresh rejection ignored — token already rotated; not marking")
            return
        }
        getEncryptedPrefs().edit()
            .putString(needsReauthKey(instanceId), tokenFingerprint(blob))
            .apply()
        Log.w(TAG, "Refresh token rejected — marked for re-login, credentials kept")
    }

    private fun clearNeedsReauth() {
        getEncryptedPrefs().edit().remove(needsReauthKey(instanceId)).apply()
    }

    // Token storage
    private fun saveTokens(json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", json.toString())
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    protected fun loadStoredTokens(): JSONObject? {
        val str = getEncryptedPrefs().getString("oauth_tokens_$instanceId", null) ?: return null
        return try { JSONObject(str) } catch (_: Exception) { null }
    }

    protected fun saveOAuthString(key: String, value: String) {
        getEncryptedPrefs().edit().putString("oauth_${key}_$instanceId", value).apply()
        // A new token bundle supersedes any needs-re-login mark.
        if (key == "tokens") clearNeedsReauth()
    }

    protected fun loadOAuthString(key: String): String? {
        return getEncryptedPrefs().getString("oauth_${key}_$instanceId", null)
    }

    /**
     * User-provided static bearer token used in place of the dynamic OAuth
     * access token. Mirrors iOS `"manual-oauth-token"` keychain account.
     * When set, [validAccessToken] returns it verbatim and no refresh is
     * attempted — intended for custom proxy endpoints that don't run the
     * normal OAuth flow but still expect a Bearer credential.
     */
    fun saveManualBearerToken(token: String) {
        saveOAuthString(KEY_MANUAL_BEARER, token)
    }

    fun loadManualBearerToken(): String? = loadOAuthString(KEY_MANUAL_BEARER)

    fun deleteManualBearerToken() {
        getEncryptedPrefs().edit()
            .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            .apply()
    }

    // [T-android-provider-export-oauth-token] Public export/import shims for the
    // provider export/import flow. The structured OAuth-login credential
    // (access_token / refresh_token / expire_at) lives as one JSON blob under
    // `oauth_tokens_<instanceId>`; the export flow previously omitted it, so an
    // OAuth-logged-in provider exported with no usable credential and imported
    // as not-authenticated. Mirrors iOS ProviderKeychainHelper.loadOAuthToken /
    // saveOAuthToken round-trip. These wrap the protected token storage without
    // exposing it broadly.

    /** The structured OAuth-login token blob as a JSON string, or null if the
     *  instance never completed an OAuth login. */
    fun exportStoredTokensJson(): String? = loadStoredTokens()?.toString()

    /** Restore a structured OAuth-login token blob from [json] (as produced by
     *  [exportStoredTokensJson]). Written verbatim so the absolute `expire_at`
     *  is preserved — unlike [saveTokens] this does NOT recompute expiry from a
     *  relative `expires_in`, which would be wrong at import time. */
    fun importStoredTokensJson(json: String) {
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        val normalized = normalizeCamelToSnake(obj)
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", normalized.toString())
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    private fun normalizeCamelToSnake(obj: JSONObject): JSONObject {
        val keyMap = mapOf(
            "accessToken" to "access_token",
            "refreshToken" to "refresh_token",
            "idToken" to "id_token",
            "expireDate" to "expire_at",
            "lastRefresh" to "last_refresh",
            "accountId" to "account_id",
            "planType" to "plan_type",
            "tokenEndpoint" to "token_endpoint",
        )
        if (keyMap.keys.none { obj.has(it) }) return obj
        val result = JSONObject()
        for (key in obj.keys()) {
            val mapped = keyMap[key] ?: key
            var value: Any = obj.get(key)
            if (mapped == "expire_at" && value is Number) {
                val v = value.toDouble()
                // iOS JSONEncoder encodes Date as seconds since 2001-01-01.
                // Values < 2e9 are reference-date offsets; convert to epoch-ms.
                val appleReferenceEpochMs = 978307200000L
                value = if (v < 2_000_000_000) {
                    (v * 1000).toLong() + appleReferenceEpochMs
                } else if (v < 2_000_000_000_000) {
                    v.toLong()
                } else {
                    v.toLong()
                }
            }
            result.put(mapped, value)
        }
        return result
    }

    /** Read an auxiliary OAuth string (e.g. Gemini's `email` / `gcp_project`)
     *  for export. */
    fun exportOAuthString(key: String): String? = loadOAuthString(key)

    /** Restore an auxiliary OAuth string (e.g. Gemini's `email` / `gcp_project`)
     *  on import. */
    fun importOAuthString(key: String, value: String) = saveOAuthString(key, value)

    // T-android-keystore-aead-fail: routed through the self-healing
    // factory; Samsung One UI / Android 16 can invalidate the master
    // key under the user, and OAuth callers can't afford a crash here
    // since some of them run in the background refresh path.
    private fun getEncryptedPrefs() =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "oauth_prefs")

    protected open suspend fun onTokensReceived(json: JSONObject) {}
}
