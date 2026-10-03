package com.openminis.app.provider.anthropic

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-anthropic-cli-version] Resolves the `claude-cli/<version>` fingerprint
 * used on the Anthropic OAuth (Claude Code subscription) path.
 *
 * ### Why this exists
 *
 * Anthropic gates *new models* on a minimum Claude Code version and rejects
 * older clients outright:
 *
 * ```
 * 400 invalid_request_error
 * "Claude Code 2.1.195 does not support this model; version 2.1.280 or newer
 *  is required. Run 'claude update', or update the Claude desktop app."
 * details.error_code = "claude_code_version_too_old"
 * ```
 *
 * The fingerprint used to be a hardcoded `2.1.195`, so **every** model launch
 * broke OAuth users until a new app release shipped a bumped constant:
 *
 * | Model             | Required claude-cli | Pinned constant | Result  |
 * |-------------------|---------------------|-----------------|---------|
 * | claude-fable-5-1  | >= 2.1.251          | 2.1.195         | 400     |
 * | claude-opus-5-5   | >= 2.1.280          | 2.1.195         | 400     |
 *
 * (see #301 — fixed by bumping the constant to 2.1.251, which was already
 * stale by the time Opus 5.5 shipped. A constant cannot win this race.)
 *
 * ### Strategy
 *
 * Resolve the version at runtime from the npm registry dist-tags endpoint,
 * which is the same source `claude update` consults and is a 56-byte,
 * unauthenticated, CDN-cached JSON document:
 *
 * ```
 * GET https://registry.npmjs.org/-/package/@anthropic-ai/claude-code/dist-tags
 * {"stable":"2.1.277","latest":"2.1.284","next":"2.1.284"}
 * ```
 *
 * We prefer `latest` over `stable`: the gate checks a *minimum*, so the
 * newest published version always satisfies it, whereas `stable` can lag
 * behind a model launch (it did for Opus 5.5 — see anthropics/claude-code#96130,
 * where `stable` was 2.1.267 against a required 2.1.280).
 *
 * ### Failure policy
 *
 * Never blocks a request. Reads are served from a volatile cache; the network
 * refresh happens off the request path (app start + a TTL re-check) and any
 * failure leaves the previous value in place. The resolution order is:
 *
 *   1. live value fetched this session
 *   2. last value persisted to SharedPreferences (survives restarts/offline)
 *   3. [FALLBACK_VERSION] compiled into the build
 *
 * So the worst case equals the old hardcoded behaviour, and the common case
 * self-heals without an app update.
 *
 * Mirrors the context-priming pattern of `FastModePrefs`: the provider layer
 * has no `Context`, so [prime] captures the application context once at
 * startup and warms the cache; the context-free [userAgent] is then safe to
 * call from any request builder.
 */
object ClaudeCliVersion {

    private const val TAG = "ClaudeCliVersion"
    private const val PREFS = "minis_claude_cli_version_prefs"
    private const val KEY_VERSION = "claudeCliVersion"
    private const val KEY_FETCHED_AT = "claudeCliVersionFetchedAt"

    private const val DIST_TAGS_URL =
        "https://registry.npmjs.org/-/package/@anthropic-ai/claude-code/dist-tags"

    /**
     * Compiled-in floor, used until the first successful fetch on a fresh
     * install. Read from the `User-Agent` literal in
     * [com.openminis.app.auth.ClaudeCliMimicryHeaders.ALL] rather than kept
     * as a second constant: that literal is the value the gate tests pin
     * (`>= 2.1.280`), and two copies of one floor are free to drift.
     */
    val FALLBACK_VERSION: String =
        com.openminis.app.auth.ClaudeCliMimicryHeaders.ALL
            .first { it.first == "User-Agent" }.second
            .removePrefix("claude-cli/").substringBefore(' ')

    /** Re-check at most once per day; the gate moves on release cadence. */
    private val REFRESH_TTL_MS = TimeUnit.DAYS.toMillis(1)

    /** Guards against a pathological registry response poisoning the UA. */
    private val VERSION_PATTERN = Regex("""^\d+\.\d+\.\d+$""")

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedVersion: String = FALLBACK_VERSION

    /**
     * Atomic so a concurrent `prime` + manual refresh can't both pass the
     * guard and issue duplicate requests (a plain @Volatile read/write pair
     * is check-then-act, not compare-and-set).
     */
    private val refreshInFlight = AtomicBoolean(false)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context, warm the cache from disk, and kick off a
     * background refresh when the persisted value is stale. Called from
     * `MinisApp.onCreate` alongside the other `prime` calls.
     */
    fun prime(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val stored = prefs(ctx).getString(KEY_VERSION, null)
        if (!stored.isNullOrBlank() &&
            VERSION_PATTERN.matches(stored) &&
            compareVersions(stored, FALLBACK_VERSION) > 0
        ) {
            cachedVersion = stored
        }
        val fetchedAt = prefs(ctx).getLong(KEY_FETCHED_AT, 0L)
        if (System.currentTimeMillis() - fetchedAt > REFRESH_TTL_MS) {
            refreshAsync()
        }
    }

    /**
     * Context-free read for request builders. Returns the best version known
     * right now and never performs I/O.
     */
    fun current(): String = cachedVersion

    /** The full `User-Agent` header value for the Anthropic OAuth path. */
    fun userAgent(): String = "claude-cli/${current()} (external, cli)"

    /**
     * Fetch dist-tags off the request path. Safe to call repeatedly; a second
     * call while one is in flight is a no-op. Failures are logged and ignored.
     */
    fun refreshAsync(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val fetched = fetchLatest()
                if (fetched != null) {
                    // Only ever move forward: a registry hiccup or a yanked
                    // release must not walk the fingerprint backwards past a
                    // gate we already satisfy.
                    accept(fetched)
                    appContext?.let { ctx ->
                        prefs(ctx).edit()
                            .putString(KEY_VERSION, cachedVersion)
                            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                            .apply()
                    }
                    AppLogger.debug(TAG, "claude-cli fingerprint resolved: $cachedVersion")
                }
            } catch (t: Throwable) {
                // Offline, DNS failure, registry outage — keep the cached value.
                AppLogger.debug(TAG, "claude-cli version refresh failed: ${t.message}")
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    /**
     * Blocking dist-tags read. Returns null on any failure or if the payload
     * doesn't contain a plausible version.
     */
    internal fun fetchLatest(): String? {
        val request = Request.Builder()
            .url(DIST_TAGS_URL)
            .get()
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return parseDistTags(body)
        }
    }

    /**
     * Pick the highest plausible version among the published dist-tags.
     * `latest` normally wins; comparing all of them means a registry that
     * reorders or renames tags still yields the newest release.
     */
    internal fun parseDistTags(json: String): String? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        var best: String? = null
        for (key in obj.keys()) {
            val value = obj.optString(key, "")
            if (!VERSION_PATTERN.matches(value)) continue
            if (best == null || compareVersions(value, best!!) > 0) {
                best = value
            }
        }
        return best
    }

    /** Numeric dotted-version comparison: 2.1.280 > 2.1.99 > 2.1.9. */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val na = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (na != nb) return na.compareTo(nb)
        }
        return 0
    }

    /**
     * Apply a resolved version under the same rule a fetch follows: only
     * plausible values, and only forward. Shared by [refreshAsync] and the
     * tests, so the tests exercise the real rule instead of restating it.
     */
    internal fun accept(version: String): Boolean {
        if (!VERSION_PATTERN.matches(version)) return false
        if (compareVersions(version, cachedVersion) <= 0) return false
        cachedVersion = version
        return true
    }

    /** Test seam: reset all state so each test starts from a known baseline. */
    internal fun resetForTest() {
        appContext = null
        cachedVersion = FALLBACK_VERSION
        refreshInFlight.set(false)
    }
}
