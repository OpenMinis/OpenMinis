package com.openminis.app.network

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [T-llm-connection-warmup] Pre-warms the TLS connection for a provider's
 * API host so the FIRST real request after a provider switch (or cold start)
 * doesn't pay the full DNS + TCP + TLS handshake cost inline.
 *
 * Why the shared pool matters: every provider client is built with
 * `.connectionPool(NetworkMonitor.sharedLLMConnectionPool)` (see
 * AnthropicProvider.kt:95 / GeminiProvider.kt:61 / OpenAIProvider.kt:475).
 * A warm-up connection made by ANY client lands in the same pool and is
 * reused by the real request's client — OkHttp matches connections by
 * host+port+scheme, not by client identity.
 *
 * Why HEAD and not a real call: the goal is a completed handshake in the
 * pool, not a response. A 404/405 on a bare base URL is just as useful —
 * the connection is already established. Every failure is swallowed; warmup
 * must never surface an error to the user.
 *
 * Why a debounce window: keep-alive on [NetworkMonitor.sharedLLMConnectionPool]
 * is 5 minutes, and [com.openminis.app.provider.ProviderFactory] only calls
 * the constructor path on a provider-cache miss (cold start, provider/model
 * switch). A per-host debounce keeps repeated factory churn from spamming
 * connections, while still re-warming within the keep-alive window.
 *
 * Pure decision cores ([shouldWarm], [hostOf], [defaultHostFor]) are unit
 * tested; the network call itself cannot be exercised in CI.
 */
internal object ConnectionWarmup {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastWarmAt = ConcurrentHashMap<String, AtomicLong>()

    /** Per-host debounce window, tuned against the pool's 5-minute keep-alive. */
    const val WARM_DEBOUNCE_MS = 30_000L
    private const val TAG = "ConnWarmup"

    /**
     * Pure decision core: whether a host needs warming right now.
     * `lastWarmAtMs == null` means never warmed.
     */
    internal fun shouldWarm(lastWarmAtMs: Long?, nowMs: Long, debounceMs: Long = WARM_DEBOUNCE_MS): Boolean =
        lastWarmAtMs == null || nowMs - lastWarmAtMs >= debounceMs

    /**
     * Pure decision core: the HTTP host inside a base URL, or null when the
     * string is not a parseable http(s) URL (caller then skips warming).
     */
    internal fun hostOf(basePath: String): String? = try {
        val url = basePath.toHttpUrlOrNull()
        url?.host?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * Pure decision core: the official default host when an instance carries
     * NO custom base URL. Must stay in sync with the hardcoded fallbacks in
     * ProviderFactory.createUncached (openAI api.openai.com/v1, openRouter,
     * xAI, kimiCode) and the provider-side defaults (AnthropicProvider.kt:47,
     * GeminiProvider.kt:46).
     */
    internal fun defaultHostFor(type: com.openminis.app.data.model.ProviderType): String = when (type) {
        com.openminis.app.data.model.ProviderType.anthropic -> "api.anthropic.com"
        com.openminis.app.data.model.ProviderType.gemini -> "generativelanguage.googleapis.com"
        com.openminis.app.data.model.ProviderType.openRouter -> "openrouter.ai"
        com.openminis.app.data.model.ProviderType.xAI -> "api.x.ai"
        com.openminis.app.data.model.ProviderType.kimiCode -> "api.kimi.com"
        else -> "api.openai.com"
    }

    /** One shared warm-up client. Pool reference is the SAME instance every
     *  provider uses — that identity is what makes warmup effective. */
    private val warmClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DohDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .connectionPool(NetworkMonitor.sharedLLMConnectionPool)
            .build()
    }

    /**
     * Fire-and-forget warm of [basePath] (the provider's actual API base, or
     * null on the official direct path — then [defaultHostFor] applies).
     * Never throws, never blocks the caller: the HEAD request runs on the
     * IO scope and every failure is swallowed with a debug log.
     */
    fun warmAsync(basePath: String?, type: com.openminis.app.data.model.ProviderType) {
        val host = basePath?.let { hostOf(it) } ?: defaultHostFor(type) ?: return
        val now = System.currentTimeMillis()
        val marker = lastWarmAt[host]
        if (!shouldWarm(marker?.get(), now)) return
        // Race on concurrent callers is harmless: two HEADs to the same host
        // share one pool anyway, and put/update keeps the freshest stamp.
        lastWarmAt.putIfAbsent(host, AtomicLong(now))?.set(now)
        scope.launch {
            try {
                val url = "https://$host/"
                val call = warmClient.newCall(
                    Request.Builder().url(url).head().build()
                )
                call.execute().use { resp ->
                    Log.d(TAG, "warmup host=$host status=${resp.code}")
                }
            } catch (e: Exception) {
                // Expected on offline/airplane/DoH-miss — warmup is best-effort.
                Log.d(TAG, "warmup host=$host skipped: ${e.javaClass.simpleName}")
            }
        }
    }
}
