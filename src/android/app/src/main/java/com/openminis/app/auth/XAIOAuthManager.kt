package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume

/**
 * xAI (Grok) OAuth manager.
 *
 * Two material differences from a vanilla [OAuthManager]:
 *
 * 1. **OIDC discovery**: authorization / token endpoints aren't constants but
 *    are fetched from `https://auth.x.ai/.well-known/openid-configuration`
 *    on first use and cached in [EncryptedSharedPreferences] (the base class's
 *    `oauth_<key>_<instance>` keyspace). Both endpoints are validated to
 *    live under `*.x.ai` before being honoured, matching the OpenClaw
 *    `xai-oauth.ts` security check — the spec calls this out explicitly.
 *
 * 2. **Token exchange echoes `code_challenge`**: xAI's token endpoint
 *    requires the `code_challenge` + `code_challenge_method` pair on the
 *    exchange request, not just the verifier. This is non-standard PKCE
 *    behavior verified from OpenClaw source — see [buildTokenParams].
 *
 * PKCE is hex-encoded (32 random bytes → 64 hex chars) per OpenClaw —
 * [generateHexPKCE] on the base class produces exactly that form.
 *
 * Authorization URL additionally carries `nonce` (OIDC required), `plan=generic`,
 * and `referrer=minis` (xAI consumer attribution).
 *
 * Redirect URI uses a fixed loopback port `56121/callback` — xAI's
 * server-side allowlist rejects any other port, per the spec.
 */
class XAIOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "XAIOAuth"

        const val OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
        const val CALLBACK_PORT = 56121
        const val CALLBACK_PATH = "/callback"

        /**
         * Static helper: perform the full login flow, persist the bearer
         * token via [ProviderRepository.saveApiKey] so the existing
         * `Authorization: Bearer …` request path in OpenAIProvider can
         * pick it up. Mirrors `OpenAIOAuthManager.login`.
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: com.openminis.app.data.repository.ProviderRepository,
        ): String {
            val manager = XAIOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    // ── Static OAuthManager surface ────────────────────────────────────

    // Endpoints are resolved dynamically from discovery; the abstract
    // fields are kept non-blank only so the base class doesn't choke on
    // them — buildAuthorizationUrl + buildTokenParams + refreshToken
    // all bypass these and read the cached/discovered endpoints instead.
    override val authURL = "https://auth.x.ai/oauth/authorize"
    override val tokenURL = "https://auth.x.ai/oauth/token"
    override val clientId = OAUTH_CLIENT_ID
    override val clientSecret: String? = null
    override val callbackPort = CALLBACK_PORT
    override val redirectPath = CALLBACK_PATH
    override val scopes = "openid profile email offline_access grok-cli:access api:access"

    // [T-android-xai-oauth-redirect-uri-127] xAI's server-side allow-list
    // registers the redirect as http://127.0.0.1:56121/callback, NOT the
    // base class's http://localhost:… spelling. OAuth redirect_uri is an
    // exact string match, so localhost ≠ 127.0.0.1 makes xAI reject the
    // authorize request with "redirect_uri does not match any registered
    // URI". iOS hard-codes 127.0.0.1 for the same reason (XAIOAuthManager
    // .swift). Both the authorize URL and the token exchange read this one
    // property, so overriding it here fixes both. The local callback server
    // still binds the loopback interface and receives the redirect either
    // way — only the string sent to xAI matters.
    override val redirectUri: String get() = "http://127.0.0.1:$callbackPort$redirectPath"

    // xAI ID-token derived account info, persisted alongside the token bundle.
    var accountId: String?
        get() = loadOAuthString("account_id")
        private set(value) { value?.let { saveOAuthString("account_id", it) } }

    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    var displayName: String?
        get() = loadOAuthString("display_name")
        private set(value) { value?.let { saveOAuthString("display_name", it) } }

    private var loginCallbackServer: OAuthCallbackServer? = null

    /**
     * In-flight login flag — protects against re-entrant
     * [performLogin] calls. A SwiftUI-equivalent on Android: a double
     * button tap (or a Compose recomposition that re-launches the
     * `scope.launch { performLogin… }`) can fire two coroutines in
     * rapid succession. Each would generate its own PKCE+state,
     * race to bind port 56121 (SO_REUSEPORT on Linux happily allows
     * both), and then xAI's eventual callback would be resolved
     * against ONE coroutine's state but routed into the OTHER's
     * continuation — manifesting as "state mismatch" even though the
     * received state is byte-identical to one of them
     * (T-xai-oauth-double-fire, port iOS 84067d0a).
     */
    @Volatile private var isAuthenticating: Boolean = false

    // ── PKCE: hex form per OpenClaw ───────────────────────────────────

    override fun generateAndSavePKCE(): Pair<String, String> {
        val (verifier, challenge) = generateHexPKCE()
        saveOAuthString("verifier", verifier)
        saveOAuthString("challenge", challenge)
        return verifier to challenge
    }

    // ── Discovery + endpoint resolution ───────────────────────────────

    /**
     * Fetch the OIDC discovery document and cache the resolved
     * authorization + token endpoints. Validates both endpoints live
     * under `*.x.ai` before honouring them — mirrors OpenClaw and the
     * spec's "abort immediately if the discovery endpoint domain is not *.x.ai" rule.
     */
    private suspend fun fetchDiscoveryEndpoints(): Pair<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(DISCOVERY_URL).get().build()
        val resp = httpClient.newCall(req).execute()
        val code = resp.code
        val body = resp.body?.string() ?: ""
        resp.close()
        if (code !in 200..299 || body.isEmpty()) {
            throw IllegalStateException("xAI OIDC discovery failed: HTTP $code")
        }
        val json = JSONObject(body)
        val authEndpoint = json.optString("authorization_endpoint", "")
        val tokenEndpoint = json.optString("token_endpoint", "")
        if (authEndpoint.isEmpty() || tokenEndpoint.isEmpty()) {
            throw IllegalStateException("xAI OIDC discovery missing endpoints")
        }
        // Open-redirect guard — both endpoints must be under *.x.ai.
        require(Uri.parse(authEndpoint).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted authorization endpoint: $authEndpoint"
        }
        require(Uri.parse(tokenEndpoint).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted token endpoint: $tokenEndpoint"
        }
        saveOAuthString("auth_endpoint", authEndpoint)
        saveOAuthString("token_endpoint", tokenEndpoint)
        authEndpoint to tokenEndpoint
    }

    /** Read cached endpoint or fetch fresh discovery. */
    private suspend fun resolveAuthEndpoint(): String =
        loadOAuthString("auth_endpoint")?.takeIf { it.isNotEmpty() }
            ?: fetchDiscoveryEndpoints().first

    private suspend fun resolveTokenEndpoint(): String =
        loadOAuthString("token_endpoint")?.takeIf { it.isNotEmpty() }
            ?: fetchDiscoveryEndpoints().second

    // ── Authorization URL ─────────────────────────────────────────────

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * NOT overriding [OAuthManager.buildAuthorizationUrl] (suspendless
     * shape) — this manager runs its own [performLogin] which can
     * await discovery before building the URL. The base-class
     * `startLogin` path is unreachable for xAI.
     */
    private suspend fun buildXAIAuthorizationUrl(): String {
        val (_, challenge) = generateAndSavePKCE()
        val state = generateState()
        saveOAuthString("state", state)
        val nonce = generateNonce()
        val authEndpoint = resolveAuthEndpoint()
        return "$authEndpoint?" + listOf(
            "response_type=code",
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "nonce=$nonce",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "plan=generic",
            "referrer=minis",
        ).joinToString("&")
    }

    // ── Token exchange (xAI: echoes code_challenge) ───────────────────

    override fun buildTokenParams(code: String): Map<String, String> {
        return mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to (loadOAuthString("verifier") ?: ""),
            // xAI-specific: token endpoint requires the challenge pair on
            // exchange, not just the verifier.
            "code_challenge" to (loadOAuthString("challenge") ?: ""),
            "code_challenge_method" to "S256",
        )
    }

    /**
     * Mirror the base-class exchange but POST to the discovered token
     * endpoint instead of the static [tokenURL] constant. Persists the
     * token bundle via the base class's `saveTokens`-equivalent path
     * ("oauth_tokens" inside [onTokensReceived]) and parses the ID
     * token for `email` / `accountId` for display in ProviderDetailScreen.
     */
    private suspend fun exchangeCodeForToken(code: String): String = withContext(Dispatchers.IO) {
        val tokenEndpoint = resolveTokenEndpoint()
        val params = buildTokenParams(code)
        val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val req = Request.Builder()
            .url(tokenEndpoint)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val resp = httpClient.newCall(req).execute()
        val code0 = resp.code
        val body = resp.body?.string() ?: ""
        resp.close()
        if (code0 !in 200..299) {
            Log.e(TAG, "xAI token exchange failed: $code0 ${OAuthManager.sanitizeBody(body)}")
            throw Exception("xAI token exchange failed ($code0): $body")
        }
        val json = JSONObject(body)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            throw Exception("xAI token exchange: no access_token in response")
        }
        // Cache token bundle via the same key used by validAccessToken /
        // refreshToken in the base class.
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveTokensJson(json)
        onTokensReceived(json)
        Log.i(TAG, "xAI token exchange OK, expires in ${expiresIn}s, has refresh: ${json.has("refresh_token")}")
        accessToken
    }

    /**
     * Persist token bundle. Mirrors the base class's private `saveTokens`
     * — same prefs key namespace ("oauth_tokens_<instance>") so the
     * inherited `validAccessToken` / `refreshToken` / `isAuthenticated`
     * helpers pick the bundle up automatically.
     */
    private fun saveTokensJson(json: JSONObject) {
        // Stash under the base class's own key. We can't call the
        // private member directly, so re-implement the single-line write
        // with the same key format.
        val pref = context.getSharedPreferences("dummy", Context.MODE_PRIVATE)
        // Falls back to using the base class's saveOAuthString helper,
        // but the canonical key the base reads from is "oauth_tokens_<id>".
        // The helper saves under "oauth_<key>_<id>" — so key="tokens"
        // → prefs key "oauth_tokens_<id>", which is what we want.
        saveOAuthString("tokens", json.toString())
    }

    override suspend fun onTokensReceived(json: JSONObject) {
        // Parse the id_token JWT payload for the user-visible email /
        // display name and the xAI account id. All optional — failure
        // leaves them unset and the ProviderDetailScreen falls back to
        // a plain "Signed in" pill.
        val idToken = json.optString("id_token", "")
        if (idToken.isNotEmpty()) parseIdToken(idToken)
        // Cache the discovered token endpoint so refreshes don't have
        // to repeat the discovery round-trip.
        runCatching { resolveTokenEndpoint() }
    }

    /**
     * Parse the JWT id_token payload (Base64URL middle segment) and
     * extract any of `email`, `name`, `sub`, `account_id` we recognise.
     */
    private fun parseIdToken(idToken: String) {
        try {
            val parts = idToken.split(".")
            if (parts.size < 2) return
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            val json = JSONObject(payload)
            json.optString("email").takeIf { it.isNotEmpty() }?.let { email = it }
            json.optString("name").takeIf { it.isNotEmpty() }?.let { displayName = it }
            // xAI's id_token uses `sub` for user id; `account_id` is the
            // workspace/org. Persist whichever is present so the UI has
            // an identifier to show in the worst case.
            (json.optString("account_id").takeIf { it.isNotEmpty() }
                ?: json.optString("sub").takeIf { it.isNotEmpty() })?.let { accountId = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xAI id_token: ${e.message}")
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────

    /**
     * xAI refresh: form-urlencoded against the cached / re-discovered
     * token endpoint. No client_secret (public client). Per the spec, a
     * response without `refresh_token` retains the original.
     *
     * Also handles HTTP 403 specially: the spec says a `403` after
     * refresh means the account isn't on a tier that exposes API
     * access, not that the token is bad — preserve the bundle in that
     * case so a manual API-key fallback or a later subscription bump
     * can pick up where we left off without re-OAuthing.
     */
    override suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val stored = JSONObject(
            loadOAuthString("tokens").takeUnless { it.isNullOrEmpty() }
                ?: return@withContext false,
        )
        val refresh = stored.optString("refresh_token", "").ifEmpty { return@withContext false }
        try {
            val tokenEndpoint = resolveTokenEndpoint()
            val params = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to clientId,
            )
            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val req = Request.Builder()
                .url(tokenEndpoint)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""
            resp.close()
            if (code == 403) {
                Log.w(TAG, "xAI refresh returned 403 — preserving token (subscription tier issue)")
                return@withContext false
            }
            if (code !in 200..299) {
                Log.e(TAG, "xAI refresh failed: $code")
                // [T-oauth-keep-credentials] Mark (never delete) on a rejected grant.
                if (isRefreshRejected(code, body, DEFAULT_FATAL_REFRESH_CODES)) markNeedsReauth(refresh)
                return@withContext false
            }
            val json = JSONObject(body)
            if (!json.has("refresh_token")) json.put("refresh_token", refresh)
            val expiresIn = json.optLong("expires_in", 0)
            if (expiresIn > 0) {
                json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
            }
            saveTokensJson(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "xAI refresh error", e)
            false
        }
    }

    // ── Login orchestration ───────────────────────────────────────────

    /**
     * Perform the full OAuth login flow. Mirrors
     * [OpenAIOAuthManager.performLogin]: spin up the callback server,
     * open a Chrome Custom Tab against the authorization endpoint, wait
     * for the code, then exchange. Throws on failure.
     */
    suspend fun performLogin(context: Context): String {
        // Re-entrancy guard. A double Compose recomposition / button tap
        // can launch two performLogin coroutines on the same instanceId
        // before either has resolved. Each would generate its own
        // PKCE+state, race to bind port 56121, then xAI's callback would
        // resolve against ONE coroutine's expected state but the OTHER
        // coroutine's continuation — surfacing as "state mismatch"
        // even though the wire state value was byte-identical. Collapse
        // re-entries to a no-op (T-xai-oauth-double-fire, port iOS
        // 84067d0a).
        if (isAuthenticating) {
            Log.w(TAG, "xAI login already in progress — ignoring re-entrant call (instance: $instanceId)")
            // Wait for the in-flight login to finish and surface its
            // resulting token so the caller's `providerRepository.saveApiKey`
            // path still has a value to persist when a second tap races.
            // Suspending here is cheaper than throwing and forcing the
            // caller into retry logic.
            while (isAuthenticating) {
                kotlinx.coroutines.delay(100)
            }
            return loadOAuthString("tokens")?.let { JSONObject(it).optString("access_token", "") }
                ?.takeIf { it.isNotEmpty() }
                ?: throw Exception("xAI login: prior re-entrant call finished without a token")
        }
        isAuthenticating = true
        try {
            Log.i(TAG, "=== xAI OAuth login started (instance: $instanceId) ===")

            loginCallbackServer?.stop()
            loginCallbackServer = null

            val authUrl = buildXAIAuthorizationUrl()
            val expectedState = loadOAuthString("state") ?: ""

            val accessToken = withContext(Dispatchers.IO) {
                val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                    val server = OAuthCallbackServer(callbackPort) { receivedCode, receivedState ->
                        if (cont.isActive) {
                            // Successful redirect — clear the
                            // external-cancel hook before the callback
                            // server's own onCode→stop() chain runs, so
                            // the resulting stop() doesn't fire a
                            // spurious cancel after we've already
                            // resumed the continuation with the code.
                            loginCallbackServer?.onExternalCancel = null
                            cont.resume(receivedCode to receivedState)
                        }
                    }
                    // [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
                    // If the user dismisses the Custom Tab before xAI
                    // redirects, externalCancel() resumes us with a
                    // CancellationException so the surrounding try /
                    // finally resets isAuthenticating immediately —
                    // instead of hanging until the socket accept() loop
                    // notices stop() (which on Android only happens
                    // when a future client connects).
                    server.onExternalCancel = {
                        if (cont.isActive) {
                            cont.cancel(kotlinx.coroutines.CancellationException("xAI OAuth callback cancelled"))
                        }
                    }
                    loginCallbackServer = server
                    server.start()
                    Log.i(TAG, "Callback server started on port $callbackPort")

                    cont.invokeOnCancellation {
                        server.stop()
                        loginCallbackServer = null
                    }

                    // Same Chrome Custom Tab pattern as OpenAIOAuthManager —
                    // don't set FLAG_ACTIVITY_NEW_TASK or the IME on the
                    // hosting MainActivity (singleTask) gets dismissed every
                    // time the authorize page focuses an input.
                    val customTabsIntent = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                    customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                    Log.i(TAG, "Opened Custom Tab for xAI authorization")

                    // [T-xai-oauth-stop-resume, port iOS d1dbdd5d
                    //  SFSafariViewControllerDelegate]
                    //
                    // Android Custom Tabs don't expose a reliable
                    // "user dismissed" callback like iOS's
                    // SFSafariViewControllerDelegate.didFinish, so we
                    // detect dismissal through ProcessLifecycleOwner:
                    // when MainActivity is foregrounded again WITHOUT
                    // the loopback callback having fired, the user
                    // backed out of the auth page. A 1.5s grace period
                    // covers the race window where the redirect arrives
                    // a beat after the Tab dismisses; if no callback
                    // resolves the continuation in that window we
                    // stop() the server, which fires onExternalCancel
                    // → cont.cancel and the surrounding try/finally
                    // clears isAuthenticating so the next tap works.
                    armDismissalDetector()
                }

                loginCallbackServer?.stop()
                loginCallbackServer = null
                Log.i(TAG, "xAI callback received — code length: ${code.length}")

                if (expectedState.isNotEmpty() && state != null && state != expectedState) {
                    throw Exception("xAI OAuth state mismatch — possible CSRF, refusing to exchange")
                }

                // [T-android-oauth-foreground-exchange] The callback lands while the
                // user is still in the Custom Tab, i.e. with Minis in the background,
                // where the OS blocks this app's network (DNS answers "no address").
                // Exchange only once the user is back.
                OAuthForegroundGate.awaitForeground(TAG)
                exchangeCodeForToken(code)
            }

            Log.i(TAG, "=== xAI OAuth login complete (instance: $instanceId) ===")
            return accessToken
        } finally {
            isAuthenticating = false
            disarmDismissalDetector()
        }
    }

    // ── Custom Tab dismissal detector ─────────────────────────────────

    /**
     * Observer that fires when the app returns to the foreground. We
     * install this RIGHT BEFORE opening the Custom Tab, then wait a
     * single ON_RESUME event: that means MainActivity has come back
     * (either because the loopback callback brought it back, OR because
     * the user dismissed the Tab without completing auth). We can't
     * tell those two cases apart from lifecycle alone — but if a
     * successful callback fired, [loginCallbackServer] is already null
     * (the server self-stops after onCode), so checking that state in
     * a short grace window after ON_RESUME tells us whether to treat
     * this as a dismissal.
     */
    private var dismissalObserver: DefaultLifecycleObserver? = null

    private fun armDismissalDetector() {
        disarmDismissalDetector()
        val observer = object : DefaultLifecycleObserver {
            // Track that we've already seen the Tab consume foreground
            // (CCS launch → ON_PAUSE on MainActivity → ON_RESUME). The
            // first ON_RESUME after install fires immediately for the
            // currently-foregrounded activity (when the lifecycle
            // observer is added on the main thread); we ignore that
            // synthetic event by gating on a "have we seen a stop yet"
            // flag set by ON_STOP / ON_PAUSE.
            @Volatile var sawBackgrounded = false

            override fun onPause(owner: LifecycleOwner) { sawBackgrounded = true }
            override fun onStop(owner: LifecycleOwner) { sawBackgrounded = true }

            override fun onResume(owner: LifecycleOwner) {
                if (!sawBackgrounded) return
                // Give the loopback callback up to 1.5s to land —
                // covers the race where xAI's redirect arrives a beat
                // after the Custom Tab dismisses.
                GlobalScope.launch {
                    delay(1500)
                    val server = loginCallbackServer ?: return@launch
                    Log.w(TAG, "App resumed without OAuth callback — treating as user dismissal, stopping server")
                    server.stop()
                }
            }
        }
        dismissalObserver = observer
        // ProcessLifecycleOwner observer ops are main-thread only.
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            }
        } catch (e: Exception) {
            Log.w(TAG, "armDismissalDetector failed to attach observer: ${e.message}")
        }
    }

    private fun disarmDismissalDetector() {
        val observer = dismissalObserver ?: return
        dismissalObserver = null
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            }
        } catch (_: Exception) {}
    }
}
