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

        /** Shared OkHttp client for all OAuth HTTP requests (respects system proxy). */
        internal val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * [T-android-oauth-log-redaction] Make an HTTP body safe to log:
         * masks the VALUES of known credential fields (access_token,
         * refresh_token, id_token, api_key/key, client_secret, device_code)
         * and truncates to a diagnostic-sized prefix. OAuth failure bodies
         * are usually just {"error":"invalid_grant"} — but some IdPs echo
         * request material, and a malformed SUCCESS body reaching an error
         * path would otherwise dump live credentials into logcat / the
         * on-device log files.
         */
        fun sanitizeBody(body: String, maxLen: Int = 300): String {
            val masked = Regex(
                "\"(access_token|refresh_token|id_token|api_key|key|client_secret|device_code)\"\\s*:\\s*\"[^\"]*\"",
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

        /** Create the appropriate OAuthManager for a provider instance. */
        fun forInstance(context: Context, instance: com.openminis.app.data.model.ProviderInstance): OAuthManager? {
            return when (instance.providerType) {
                com.openminis.app.data.model.ProviderType.anthropic -> ClaudeOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.openAI -> OpenAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.xAI -> XAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.kimiCode -> KimiOAuthManager(context, instance.id)
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
            // Refresh failed — if token is already expired, clear credentials
            if (expireAt > 0 && now >= expireAt) {
                Log.w(TAG, "Token expired and refresh failed — clearing credentials")
                logout()
                return null
            }
        }
        return token
    }

    fun isAuthenticated(): Boolean {
        if (loadManualBearerToken()?.isNotEmpty() == true) return true
        val stored = loadStoredTokens() ?: return false
        return stored.optString("access_token", "").isNotEmpty()
    }

    fun logout() {
        getEncryptedPrefs().edit()
            .remove("oauth_tokens_$instanceId")
            .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            .apply()
    }

    // Token storage
    private fun saveTokens(json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", json.toString())
            .apply()
    }

    protected fun loadStoredTokens(): JSONObject? {
        val str = getEncryptedPrefs().getString("oauth_tokens_$instanceId", null) ?: return null
        return try { JSONObject(str) } catch (_: Exception) { null }
    }

    protected fun saveOAuthString(key: String, value: String) {
        getEncryptedPrefs().edit().putString("oauth_${key}_$instanceId", value).apply()
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
