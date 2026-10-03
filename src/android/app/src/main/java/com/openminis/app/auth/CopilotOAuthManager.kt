package com.openminis.app.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * [T-copilot-provider] GitHub Copilot OAuth manager.
 *
 * Two tiers, and keeping them apart is the whole job:
 *
 *  - The GitHub account token (`gho_…`) is long-lived. Won once via device
 *    flow, stored encrypted, and never sent to Copilot's chat endpoint.
 *  - The Copilot session token lasts ~25 minutes and IS what chat requests
 *    carry. It is derived from the account token on demand.
 *
 * [validAccessToken] returns the SESSION token, because that is what the
 * request layer needs; the account token stays behind this class. Refresh is
 * lazy (checked before each request) rather than timer-driven — a timer would
 * keep waking a backgrounded app to refresh a credential nothing is about to
 * use, and would still have to re-check after process death.
 *
 * ⚠️ Unofficial integration — see [CopilotDeviceFlow] for the risk note.
 */
class CopilotOAuthManager(
    context: Context,
    instanceId: String,
) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "CopilotOAuth"

        /** Encrypted-prefs keys, namespaced per provider instance by the base class. */
        private const val KEY_GITHUB_TOKEN = "copilot_github_token"
        private const val KEY_SESSION_TOKEN = "copilot_session_token"
        private const val KEY_SESSION_EXPIRES = "copilot_session_expires_at"

        /** Single-flight per instance: a fan-out of agent requests would
         *  otherwise all miss the cache at once and each burn an exchange. */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * Full device-code login. Suspends until the user approves or a
         * terminal failure. [onDeviceCode] fires as soon as the code is
         * issued so the UI can show it — login is NOT complete at that point.
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            onDeviceCode: (CopilotDeviceFlow.DeviceAuthorization) -> Unit,
        ): String {
            val mgr = CopilotOAuthManager(context, instanceId)
            val auth = mgr.requestDeviceAuthorization()
            onDeviceCode(auth)
            val githubToken = mgr.pollForToken(auth)
            mgr.saveOAuthString(KEY_GITHUB_TOKEN, githubToken)
            // Exchange immediately: it proves the account actually has Copilot
            // access, so the user learns at login rather than on first send.
            //
            // [T-android-copilot-login-main-thread] The device flow has already
            // SUCCEEDED at this point and the account token is saved, so a
            // failure here must not read as "authorization failed" — it means
            // the sign-in worked and Copilot access did not. Re-thrown with a
            // message that says which, because an exception with a null message
            // renders as a bare "Authentication failed" in the UI.
            try {
                mgr.exchangeSessionToken(githubToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[Copilot] signed in, but the session-token exchange failed", e)
                throw Exception(
                    e.message
                        ?: "Signed in to GitHub, but could not obtain a Copilot token " +
                        "(${e.javaClass.simpleName}).",
                    e,
                )
            }
            Log.i(TAG, "Copilot login complete (instance: $instanceId)")
            return githubToken
        }
    }

    // Base-class abstract surface. Only tokenURL / clientId matter for a
    // device flow; the redirect/PKCE fields are inert placeholders, as in
    // KimiOAuthManager.
    override val authURL = CopilotDeviceFlow.DEVICE_CODE_URL
    override val tokenURL = CopilotDeviceFlow.ACCESS_TOKEN_URL
    override val clientId: String get() = CopilotDeviceFlow.CLIENT_ID
    override val clientSecret: String? get() = null
    override val callbackPort: Int get() = 0
    override val redirectPath: String get() = ""
    override val scopes = CopilotDeviceFlow.SCOPE

    /** True once a GitHub account token is stored for this instance. */
    fun hasGithubToken(): Boolean = !loadOAuthString(KEY_GITHUB_TOKEN).isNullOrEmpty()

    /**
     * [T-android-copilot-not-connected] Copilot's own answer to "is this
     * instance signed in".
     *
     * The base implementation reads `oauth_tokens_<id>` and looks for an
     * `access_token` — the shape every other provider here persists. Copilot
     * stores its two tiers under its OWN keys instead, so the inherited check
     * always returned false and every caller concluded the user had not signed
     * in: the provider list showed a grey dot and the detail screen said "Not
     * connected", immediately after a sign-in whose log reads "device flow
     * complete / session token refreshed / login complete" and which had just
     * loaded 28 models.
     *
     * Overriding here rather than teaching each screen about Copilot is what
     * fixes all of them at once — this is the question they already ask.
     *
     * The GITHUB token is the credential; the session token is derived and may
     * legitimately be absent or expired. Mirrors iOS
     * `CopilotOAuthManager.isAuthenticated(instanceId:)`, which tests exactly
     * the same field for the same reason.
     */
    override fun isAuthenticated(): Boolean = hasGithubToken()

    /**
     * [T-android-copilot-backup-token] Copilot's credential as a backup blob.
     *
     * The generic exporter reads `oauth_tokens_<id>` via
     * [exportStoredTokensJson], but Copilot never writes that key — it stores
     * the two tiers under `copilot_github_token` / `copilot_session_token`. So
     * a backup silently omitted the Copilot sign-in: the instance came back
     * after a restore, signed out, with no error anywhere. Same user-visible
     * loss iOS hit for a different reason (its switch had a `default` that
     * swallowed the case), and the login notice explicitly promises "a backup
     * you export yourself may contain it".
     *
     * The shape matches iOS `CopilotTokenStorage` exactly — camelCase keys,
     * same field names — so a package written by either platform restores on
     * the other. Only `githubToken` is load-bearing; the session token is
     * short-lived and re-minted on demand, so an expired one costs nothing.
     */
    fun exportCopilotTokensJson(): String? {
        val github = loadOAuthString(KEY_GITHUB_TOKEN)
        if (github.isNullOrEmpty()) return null
        return JSONObject().apply {
            put("githubToken", github)
            loadOAuthString(KEY_SESSION_TOKEN)?.takeIf { it.isNotEmpty() }
                ?.let { put("sessionToken", it) }
            loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
                ?.let { put("sessionExpiresAt", it) }
        }.toString()
    }

    /** Restore what [exportCopilotTokensJson] wrote. Tolerates a blob that
     *  carries only `githubToken` — the session tier re-mints itself. */
    fun importCopilotTokensJson(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val github = obj.optString("githubToken", "")
        if (github.isEmpty()) return
        saveOAuthString(KEY_GITHUB_TOKEN, github)
        obj.optString("sessionToken", "").takeIf { it.isNotEmpty() }
            ?.let { saveOAuthString(KEY_SESSION_TOKEN, it) }
        // `sessionExpiresAt` may be absent or 0; a missing or stale expiry simply
        // forces a refresh on the next request, which is the safe direction.
        obj.optLong("sessionExpiresAt", 0L).takeIf { it > 0 }
            ?.let { saveOAuthString(KEY_SESSION_EXPIRES, it.toString()) }
        Log.i(TAG, "[Copilot] credentials restored from backup (instance: $instanceId)")
    }

    // ── Stage 1: device flow ─────────────────────────────────────────────

    private fun postForm(url: String, fields: Map<String, String>): Pair<Int, JSONObject> {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .post(form)
            // GitHub returns url-encoded bodies unless JSON is requested.
            .header("Accept", "application/json")
            .header("User-Agent", CopilotDeviceFlow.USER_AGENT)
            .build()
        val response = httpClient.newCall(request).execute()
        val code = response.code
        val body = response.body?.string().orEmpty()
        response.close()
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        return code to json
    }

    suspend fun requestDeviceAuthorization(): CopilotDeviceFlow.DeviceAuthorization =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Requesting device code (instance: $instanceId)")
            val (status, json) = postForm(
                CopilotDeviceFlow.DEVICE_CODE_URL,
                mapOf("client_id" to clientId, "scope" to CopilotDeviceFlow.SCOPE),
            )
            if (status !in 200..299) {
                val desc = json.optString("error_description", "")
                    .ifEmpty { json.optString("error", "device authorization failed") }
                Log.e(TAG, "device/code failed: $desc")
                throw Exception("GitHub device authorization failed: $desc")
            }
            CopilotDeviceFlow.parseDeviceAuthorization(json)
                ?: throw Exception("GitHub device authorization response missing required fields")
        }

    /** Poll until approval or terminal failure, honouring RFC 8628 backoff. */
    suspend fun pollForToken(auth: CopilotDeviceFlow.DeviceAuthorization): String =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
            var interval = auth.intervalSeconds
            // [T-android-copilot-poll-diagnostics] A device flow that sits on
            // "waiting for authorization" forever used to produce NO log line
            // at all — the loop logged nothing on any branch — so the one case
            // anyone would pull logs for was invisible, and a stuck flow could
            // not be told apart from a wrong `error` code, an unparseable body,
            // or a classifier fall-through. GitHub's `error` field is a machine
            // code (authorization_pending / access_denied / …) carrying no user
            // content, so it is safe to log verbatim; the device code and any
            // token are NOT logged.
            Log.i(
                TAG,
                "[Copilot] device flow polling START interval=${interval}s " +
                    "expiresIn=${auth.expiresInSeconds}s instance=${instanceId.take(8)}",
            )
            while (System.currentTimeMillis() < deadline) {
                // Sleep first: polling immediately can only ever return
                // authorization_pending, and risks an instant slow_down.
                delay(interval * 1000)
                val (status, json) = postForm(
                    CopilotDeviceFlow.ACCESS_TOKEN_URL,
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to auth.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
                val errCode = json.optString("error", "").ifEmpty {
                    if (status in 200..299) "none" else "unparsed"
                }
                Log.i(
                    TAG,
                    "[Copilot] poll status=$status error=$errCode " +
                        "keys=${json.keys().asSequence().sorted().joinToString(",")}",
                )
                when (val r = CopilotDeviceFlow.classifyPoll(json, status in 200..299)) {
                    is CopilotDeviceFlow.PollResult.Success -> {
                        Log.i(TAG, "[Copilot] device flow complete for instance ${instanceId.take(8)}")
                        return@withContext r.accessToken
                    }
                    CopilotDeviceFlow.PollResult.Pending -> Unit
                    CopilotDeviceFlow.PollResult.SlowDown -> {
                        interval = CopilotDeviceFlow.bumpedInterval(interval)
                        Log.i(TAG, "[Copilot] slow_down — interval now ${interval}s")
                    }
                    is CopilotDeviceFlow.PollResult.Denied -> {
                        Log.e(TAG, "[Copilot] device flow DENIED: ${r.description}")
                        throw Exception("GitHub sign-in was denied: ${r.description}")
                    }
                    is CopilotDeviceFlow.PollResult.Expired -> {
                        Log.e(TAG, "[Copilot] device flow EXPIRED")
                        throw Exception("The device code expired — please try again.")
                    }
                    is CopilotDeviceFlow.PollResult.Fatal -> {
                        // The classifier's catch-all: an unrecognised `error`
                        // code lands here, and that is exactly what a stuck flow
                        // would be caused by.
                        Log.e(TAG, "[Copilot] device flow FATAL: ${r.description}")
                        throw Exception("GitHub sign-in failed: ${r.description}")
                    }
                }
            }
            Log.e(
                TAG,
                "[Copilot] device flow timed out after ${auth.expiresInSeconds}s " +
                    "without a terminal answer",
            )
            throw Exception("The device code expired — please try again.")
        }

    // ── Stage 2: session token ───────────────────────────────────────────

    /** Exchange the account token for a session token and persist both parts. */
    /**
     * [T-android-copilot-login-main-thread] Stage 2, always off the main
     * thread.
     *
     * This used to be a plain blocking function. Its two callers disagreed
     * about who owned the dispatch: `validAccessToken` invokes it INSIDE
     * `withContext(Dispatchers.IO)`, so it was fine, while the `login()`
     * companion called it directly — and `login()` runs from the Add Provider
     * screen's `scope.launch`, i.e. on the main thread. The result was a
     * synchronous OkHttp call on the UI thread, which Android answers with
     * `NetworkOnMainThreadException` — an exception whose `message` is NULL, so
     * the screen fell through to its bare "Authentication failed" fallback and
     * logged nothing. The device flow itself had already succeeded, which is
     * why the user saw a completed authorization reported as a failure.
     *
     * Owning the dispatch here rather than at each call site is what makes that
     * class of mistake unavailable: `withContext(IO)` from an IO thread is a
     * no-op, so the correct caller pays nothing.
     */
    private suspend fun exchangeSessionToken(githubToken: String): String =
        withContext(Dispatchers.IO) { exchangeSessionTokenBlocking(githubToken) }

    private fun exchangeSessionTokenBlocking(githubToken: String): String {
        val builder = Request.Builder().url(CopilotDeviceFlow.COPILOT_TOKEN_URL).get()
        CopilotDeviceFlow.tokenExchangeHeaders(githubToken).forEach { (k, v) ->
            builder.header(k, v)
        }
        val response = httpClient.newCall(builder.build()).execute()
        val status = response.code
        val body = response.body?.string().orEmpty()
        response.close()
        if (status !in 200..299) {
            // 401/403 here usually means the account has no Copilot
            // subscription — distinct from a bad token, and worth saying so.
            Log.e(TAG, "Copilot token exchange failed: HTTP $status")
            throw Exception(
                if (status == 401 || status == 403) {
                    "This GitHub account does not have Copilot access (HTTP $status)."
                } else {
                    "Copilot token exchange failed (HTTP $status)."
                }
            )
        }
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis() / 1000
        val session = CopilotDeviceFlow.parseSessionToken(json, now)
            ?: throw Exception("Copilot token exchange returned no usable token.")
        saveOAuthString(KEY_SESSION_TOKEN, session.token)
        saveOAuthString(KEY_SESSION_EXPIRES, session.expiresAtEpochSeconds.toString())
        Log.i(TAG, "Copilot session token refreshed (expires_at=${session.expiresAtEpochSeconds})")
        return session.token
    }

    /**
     * The SESSION token, refreshed if it is at/near expiry.
     *
     * Returns null rather than throwing when there is no account token — the
     * provider layer turns that into InvalidApiKey, which is the state a user
     * who has not signed in should see.
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val githubToken = loadOAuthString(KEY_GITHUB_TOKEN)
        if (githubToken.isNullOrEmpty()) return@withContext null

        val cached = loadOAuthString(KEY_SESSION_TOKEN)
        val expiresAt = loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
        val now = System.currentTimeMillis() / 1000
        if (!cached.isNullOrEmpty() && expiresAt != null &&
            !CopilotDeviceFlow.SessionToken(cached, expiresAt).needsRefresh(now)
        ) {
            return@withContext cached
        }

        mutexFor(instanceId).withLock {
            // Re-read inside the lock: a request that queued behind a refresh
            // must use its result instead of starting a second exchange.
            val freshExpiry = loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
            val freshToken = loadOAuthString(KEY_SESSION_TOKEN)
            val nowInner = System.currentTimeMillis() / 1000
            if (!freshToken.isNullOrEmpty() && freshExpiry != null &&
                !CopilotDeviceFlow.SessionToken(freshToken, freshExpiry).needsRefresh(nowInner)
            ) {
                return@withLock freshToken
            }
            runCatching { exchangeSessionToken(githubToken) }
                .onFailure { Log.e(TAG, "Session refresh failed: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * Clear both tiers. The base [logout] only knows its own keys, so the
     * Copilot-specific ones are blanked here — leaving a stale GitHub token
     * behind would silently re-authenticate a user who just signed out.
     */
    fun logoutCopilot() {
        saveOAuthString(KEY_GITHUB_TOKEN, "")
        saveOAuthString(KEY_SESSION_TOKEN, "")
        saveOAuthString(KEY_SESSION_EXPIRES, "")
        logout()
        Log.i(TAG, "Copilot credentials cleared (instance: $instanceId)")
    }

    // ── Model list ───────────────────────────────────────────────────────

    /**
     * Models the server says the picker may offer, as (id, displayName).
     * Empty on any failure — the caller keeps whatever it had rather than
     * wiping the user's model list because one refresh did not answer.
     */
    suspend fun fetchModels(): List<Pair<String, String>> =
        fetchModelsDetailed().map { it.id to it.displayName }

    /**
     * [T-android-copilot-model-capabilities] Models with their capabilities,
     * and [T-android-copilot-model-diagnostics] a log line for anything the
     * filters removed.
     *
     * Both matter for the same reason: there is NO fallback behind this list.
     * Copilot ships no built-in catalog and has no models.dev entry, so an
     * empty result reaches the user as a bare "no models" with nothing to
     * distinguish a policy filter from a dead token or an unparseable body.
     * Previously every failure collapsed into `emptyList()` behind a single
     * `Log.w`. Now each outcome says which one it was.
     */
    suspend fun fetchModelsDetailed(): List<CopilotDeviceFlow.CopilotModel> =
        withContext(Dispatchers.IO) {
            val session = validAccessToken()
            if (session == null) {
                Log.w(TAG, "[Copilot] /models skipped — no valid session token")
                return@withContext emptyList()
            }
            val builder = Request.Builder()
                .url("${CopilotDeviceFlow.API_BASE}/models")
                .get()
                .header("Authorization", "Bearer $session")
            CopilotDeviceFlow.staticChatHeaders().forEach { (k, v) -> builder.header(k, v) }
            builder.header("X-Request-Id", java.util.UUID.randomUUID().toString())

            val result = runCatching {
                val response = httpClient.newCall(builder.build()).execute()
                val ok = response.isSuccessful
                val code = response.code
                val body = response.body?.string().orEmpty()
                response.close()
                if (!ok) {
                    Log.e(TAG, "[Copilot] /models returned HTTP $code")
                    return@runCatching emptyList<CopilotDeviceFlow.CopilotModel>()
                }
                val parsed = CopilotDeviceFlow.parseModelsDetailed(JSONObject(body))
                Log.i(
                    TAG,
                    "[Copilot] ${parsed.models.size} selectable models of " +
                        "${parsed.totalReturned} returned; " +
                        "ids=[${parsed.models.joinToString(",") { it.id }}]",
                )
                if (parsed.hiddenByPolicy.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "[Copilot] hidden by model_picker_enabled=false: " +
                            parsed.hiddenByPolicy.joinToString(","),
                    )
                }
                if (parsed.droppedByType.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "[Copilot] dropped by capabilities.type != chat: " +
                            parsed.droppedByType.joinToString(","),
                    )
                }
                if (parsed.models.isEmpty()) {
                    // Nothing behind this to fall back on, so say so loudly.
                    Log.e(
                        TAG,
                        "[Copilot] model list is EMPTY after filtering — " +
                            "${parsed.totalReturned} entries returned by /models",
                    )
                }
                parsed.models
            }
            result.onFailure { Log.e(TAG, "[Copilot] /models fetch failed: ${it.message}") }
            result.getOrDefault(emptyList())
        }
}
