package com.openminis.app.auth

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/**
 * [T-kimi-oauth] Kimi Code OAuth manager — RFC 8628 Device Authorization
 * Grant (no browser redirect / callback server; the user enters a short code
 * on auth.kimi.com and we poll the token endpoint). Port of iOS
 * `KimiOAuthManager` with the Android Claude/Gemini refresh-race machinery
 * (per-instance Mutex + RefreshOutcome + expire_at coalescing) PLUS the iOS
 * 36d506ca compare-before-delete guard.
 *
 * The inherited PKCE / callback-server surface from [OAuthManager] is unused
 * — only the token storage helpers and logout are shared.
 */
class KimiOAuthManager(
    context: Context,
    instanceId: String,
) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "KimiOAuth"

        /**
         * Official Kimi Code CLI OAuth client id (observed in CLIProxyAPI's
         * public source). It is not issued to Minis, so it may be rotated,
         * rate-limited or revoked upstream. Overridable via AndroidManifest
         * meta-data
         * "KimiOAuthClientID" (the Android analog of iOS's Info.plist key);
         * when the meta-data is absent we fall back to this value rather
         * than disabling login (iOS lesson: an empty placeholder made the
         * login button a silent no-op).
         */
        const val OFFICIAL_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"

        /** 5-minute proactive-refresh buffer (Claude/Gemini/iOS parity). */
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        /** Per-instance refresh mutex — single-flight (see ClaudeOAuthManager). */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * Full device-code login flow. Suspends until the user approves (or
         * a terminal failure). [onDeviceCode] fires as soon as the device
         * code is issued so the UI can show the user code + verification URL
         * — the login is NOT complete at that point; polling continues.
         * Returns the access token and mirrors it to `apikey_<id>`.
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
            onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
        ): String {
            val manager = KimiOAuthManager(context, instanceId)
            val token = manager.performDeviceLogin(onDeviceCode)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    /** Same classification enum shape as ClaudeOAuthManager. */
    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    // Base-class abstract surface. Only tokenURL/clientId matter for the
    // device flow; the redirect/PKCE fields are inert placeholders.
    override val authURL = KimiDeviceFlow.AUTH_HOST
    override val tokenURL = KimiDeviceFlow.TOKEN_URL
    override val clientId: String
        get() {
            val meta = try {
                context.packageManager
                    .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString("KimiOAuthClientID")
            } catch (_: Exception) {
                null
            }
            // Explicit empty override disables login; absent → official value.
            return meta ?: OFFICIAL_CLIENT_ID
        }
    override val clientSecret: String? = null
    override val callbackPort = 0
    override val redirectPath = ""
    override val scopes = "" // Kimi rejects ANY explicit scope — never sent.

    val isLoginAvailable: Boolean get() = clientId.isNotEmpty()

    // ── Device flow ─────────────────────────────────────────────────────

    /**
     * POST a form-urlencoded body (the ONLY body shape auth.kimi.com
     * accepts — JSON gets "client_id is required"). Returns (httpStatus, json).
     */
    private fun postForm(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val request = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val response = httpClient.newCall(request).execute()
        val code = response.code
        val body = response.body?.string() ?: ""
        response.close()
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        return code to json
    }

    /** Step 1: request a device code. */
    suspend fun requestDeviceAuthorization(): KimiDeviceFlow.DeviceAuthorization =
        withContext(Dispatchers.IO) {
            if (!isLoginAvailable) {
                throw Exception("Kimi login unavailable — client id override is empty")
            }
            Log.i(TAG, "Requesting device authorization (instance: $instanceId)")
            // client_id ONLY — no scope (server rejects any explicit scope).
            val (status, json) = postForm(
                KimiDeviceFlow.DEVICE_AUTHORIZATION_URL,
                mapOf("client_id" to clientId),
            )
            if (status !in 200..299) {
                val desc = json.optString("error_description", "")
                    .ifEmpty { json.optString("error", "device authorization failed") }
                Log.e(TAG, "device_authorization failed: $desc")
                throw Exception("Kimi device authorization failed: $desc")
            }
            KimiDeviceFlow.parseDeviceAuthorization(json)
                ?: throw Exception("Kimi device authorization response missing required fields")
                    .also { Log.e(TAG, "device_authorization parse failure: ${sanitizeBody(json.toString())}") }
        }

    /** Step 2: poll the token endpoint until approval / terminal failure. */
    suspend fun pollForToken(auth: KimiDeviceFlow.DeviceAuthorization): String =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
            var interval = auth.intervalSeconds
            while (System.currentTimeMillis() < deadline) {
                // Sleep first, then poll (RFC 8628 §3.4 + iOS parity).
                delay(interval * 1000)
                val (status, json) = postForm(
                    tokenURL,
                    mapOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code" to auth.deviceCode,
                        "client_id" to clientId,
                    ),
                )
                when (val result = KimiDeviceFlow.classifyPoll(json, status in 200..299)) {
                    is KimiDeviceFlow.PollResult.Success -> {
                        persistLoginSuccess(result)
                        Log.i(TAG, "Device login success (instance: $instanceId)")
                        return@withContext result.accessToken
                    }
                    KimiDeviceFlow.PollResult.Pending -> Unit // keep polling
                    KimiDeviceFlow.PollResult.SlowDown -> {
                        interval = KimiDeviceFlow.bumpedInterval(interval)
                        Log.d(TAG, "slow_down — interval now ${interval}s")
                    }
                    is KimiDeviceFlow.PollResult.Denied ->
                        throw Exception("Kimi login denied: ${result.description}")
                    is KimiDeviceFlow.PollResult.Expired ->
                        throw Exception("Kimi login code expired: ${result.description}")
                    is KimiDeviceFlow.PollResult.Fatal ->
                        throw Exception("Kimi login failed: ${result.description}")
                }
            }
            throw Exception("Kimi login timed out")
        }

    /** Steps 1+2 chained; [onDeviceCode] surfaces the code/URL to the UI. */
    suspend fun performDeviceLogin(
        onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
    ): String {
        val auth = requestDeviceAuthorization()
        Log.i(TAG, "Device code issued — user_code=${OAuthLogRedaction.secret(auth.userCode)} interval=${auth.intervalSeconds}s expires=${auth.expiresInSeconds}s")
        withContext(Dispatchers.Main) { onDeviceCode(auth) }
        return pollForToken(auth)
    }

    private fun persistLoginSuccess(result: KimiDeviceFlow.PollResult.Success) {
        val json = JSONObject().apply {
            put("access_token", result.accessToken)
            result.refreshToken?.let { put("refresh_token", it) }
            result.expiresInSeconds?.let {
                put("expire_at", System.currentTimeMillis() + it * 1000)
            }
            // Client-generated device identity, carried across refreshes but
            // never sent in request bodies (iOS parity).
            put("device_id", UUID.randomUUID().toString())
            put("last_refresh", System.currentTimeMillis())
        }
        saveOAuthString("tokens", json.toString())
    }

    // ── Refresh (single-flight + compare-before-delete) ─────────────────

    suspend fun refreshTokenClassified(): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            val stored = loadStoredTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            // Coalesce concurrent refreshers (see ClaudeOAuthManager): if a
            // fresher token landed while we were blocked on the mutex, skip
            // the network round-trip entirely.
            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                Log.d(TAG, "Refresh skipped — fresher token already present (coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                val (status, json) = postForm(
                    tokenURL,
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshTokenValue,
                        "client_id" to clientId,
                    ),
                )
                if (status in 200..299 && json.optString("access_token", "").isNotEmpty()) {
                    // Non-rotating servers may omit refresh_token — keep old.
                    if (!json.has("refresh_token") || json.optString("refresh_token").isEmpty()) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    // Carry the device identity + stamp last_refresh.
                    json.put("device_id", stored.optString("device_id", UUID.randomUUID().toString()))
                    json.put("last_refresh", System.currentTimeMillis())
                    saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Token refresh successful (expires in ${expiresIn}s)")
                    return@withLock RefreshOutcome.SUCCESS
                }

                // Classify like ClaudeOAuthManager: hard 4xx auth failures or
                // an explicit invalid-grant marker → INVALID_GRANT; 5xx /
                // anything else → TRANSIENT (credentials kept).
                val bodyLower = json.toString().lowercase()
                val isInvalidGrant = status == 400 || status == 401 || status == 403 ||
                    bodyLower.contains("invalid_grant") ||
                    bodyLower.contains("refresh_token_reused")
                if (isInvalidGrant) {
                    // [T-oauth-refresh-race] Compare-before-delete: only clear
                    // credentials when the persisted refresh token is STILL
                    // the one this failed request used. A concurrent winner
                    // may have rotated it — deleting then would destroy the
                    // fresh login (iOS 36d506ca).
                    val currentStored = loadStoredTokens()?.optString("refresh_token", "")
                    if (!KimiDeviceFlow.shouldDeleteAfterInvalidGrant(refreshTokenValue, currentStored)) {
                        Log.w(TAG, "Stale invalid_grant ignored — refresh token was rotated concurrently; keeping new credentials")
                        return@withLock RefreshOutcome.SUCCESS
                    }
                    // [T-oauth-keep-credentials] Never delete: mark for re-login.
                    Log.e(TAG, "Refresh token invalid — marking for re-login, credentials kept")
                    markNeedsReauth(refreshTokenValue)
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                Log.w(TAG, "Token refresh transient failure — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh transient error — keeping token: ${e.message}")
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    /** 5-minute-buffered valid token (Claude parity, overrides the 4h base). */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        loadManualBearerToken()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val stored = loadStoredTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = stored.optString("refresh_token", "").isNotEmpty() &&
            expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext token

        when (refreshTokenClassified()) {
            RefreshOutcome.SUCCESS ->
                loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null // marked for re-login, credentials kept
            RefreshOutcome.TRANSIENT ->
                if (expireAt in 1..now) null else token
            RefreshOutcome.NO_TOKEN -> null
        }
    }
}
