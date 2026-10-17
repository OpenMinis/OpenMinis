package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.HttpRetryAfter
import com.openminis.app.provider.ProviderKeyGate
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
import com.openminis.app.provider.VendorMedia
import com.openminis.app.provider.VendorMediaKind
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion

class OpenAIProvider private constructor(
    private val apiKey: String?,
    private val oauthTokenProvider: (suspend () -> String)?,
    override var model: LLMModel = LLMModel.gpt4oMini,
    internal val basePath: String = "https://api.openai.com/v1",
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** Codex account ID for OAuth mode (extracted from JWT). */
    var codexAccountId: String? = null,
    /** When true, route through /v1/responses even on API-key providers. */
    private val useResponsesAPI: Boolean = false,
    /**
     * When true, force Chat Completions even when the bearer is provided
     * via OAuth. Set by OAuth-but-not-Codex callers (xAI Grok) — without
     * it the default `usesChatCompletionsAPI = !isOAuth && !useResponsesAPI`
     * heuristic incorrectly drags an OAuth bearer onto the Codex
     * Responses backend at chatgpt.com, where it 404s.
     */
    private val forceChatCompletions: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on every
     * outbound request (chat + responses). Only set for custom-base
     * OpenAI-compat instances.
     */
    private val customUserAgent: String? = null,
    /**
     * [T-android-azure-openai] Azure OpenAI mode. When true, requests auth with
     * the `api-key:` header (not `Authorization: Bearer`) and the URL is built
     * as {azureBase}/openai/deployments/{model.id}/{path}?api-version=… from
     * [azureBase] (the raw user endpoint, which carries the ?api-version query).
     * Defaults false so every non-Azure path is byte-for-byte unchanged.
     */
    private val isAzure: Boolean = false,
    /**
     * Raw Azure endpoint the user pasted (with any ?api-version query). Only
     * used when [isAzure]; the factory passes instance.customBaseURL verbatim
     * here because [basePath] has been normalized (/v1 appended, query dropped)
     * which is wrong for Azure's deployments-path routing.
     */
    private val azureBase: String? = null,
) : LLMProvider {
    override val name = "OpenAI"

    override val callGateKey: String
        get() = ProviderKeyGate.key(
            basePath,
            apiKey ?: "oauth:${codexAccountId ?: "codex"}",
            model.id,
        )

    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, set by
     * ProviderFactory after construction (mirrors how [codexAccountId] is a
     * post-construction var). Lets the thinking resolver look up this instance's
     * user-authored custom rules from [com.openminis.app.provider.thinking.ThinkingRuleResolver]'s
     * cache. Null → no custom rules (identical to Phase-1 built-in-only behaviour).
     */
    var thinkingRuleInstanceId: String? = null

    /**
     * [T-android-xai-priority] Whether this provider speaks xAI's Priority
     * Processing extension, i.e. whether it is eligible to carry
     * `service_tier: "priority"` when the user's global Fast Mode is on.
     *
     * This is a CAPABILITY flag, not the user's choice. The choice lives in
     * the app-level [com.openminis.app.data.FastModePrefs] toggle that Codex
     * Fast Mode already uses, and is read at request-BUILD time (see
     * [buildRequestBody]) so flipping it applies to the very next request of
     * an ongoing session — including offload / title-gen calls that never pass
     * through ChatViewModel. Storing the user's answer here instead would
     * freeze it at provider-construction time and miss those.
     *
     * False by default so every other provider's body is byte-for-byte
     * unchanged. That matters beyond tidiness: `service_tier` is an xAI
     * extension, and OpenAI-compatible relays that reject unknown body keys
     * would 400 on it, so ProviderFactory sets this for ProviderType.xAI alone.
     * A post-construction var rather than a constructor parameter for the same
     * reason as [thinkingRuleInstanceId] — xAI resolves through two different
     * constructors (API key and OAuth), and threading a flag through both
     * duplicates it.
     */
    var supportsPriorityProcessing: Boolean = false

    /**
     * [T-android-xai-priority] The effective `service_tier` for this request,
     * or null to omit the field. Consulted by both body builders.
     *
     * Omits rather than sending `"default"` when Fast Mode is off: "default" is
     * xAI's own behaviour, so leaving the key out keeps the body byte-identical
     * to before this feature existed.
     */
    internal fun resolvedServiceTier(): String? =
        if (supportsPriorityProcessing && com.openminis.app.data.FastModePrefs.isEnabled()) {
            "priority"
        } else {
            null
        }

    /** API Key constructor (Chat Completions API by default; set useResponsesAPI=true for /v1/responses). */
    constructor(
        apiKey: String,
        model: LLMModel = LLMModel.gpt4oMini,
        basePath: String = "https://api.openai.com/v1",
        extraHeaders: Map<String, String> = emptyMap(),
        useResponsesAPI: Boolean = false,
        customUserAgent: String? = null,
        isAzure: Boolean = false,
        azureBase: String? = null,
    ) : this(
        apiKey = apiKey,
        oauthTokenProvider = null,
        model = model,
        basePath = basePath,
        extraHeaders = extraHeaders,
        useResponsesAPI = useResponsesAPI,
        customUserAgent = customUserAgent,
        isAzure = isAzure,
        azureBase = azureBase,
    )

    /** OAuth constructor (Codex Responses API). */
    constructor(
        oauthTokenProvider: suspend () -> String,
        model: LLMModel = LLMModel.codexMini,
        codexAccountId: String? = null,
    ) : this(apiKey = null, oauthTokenProvider = oauthTokenProvider, model = model, codexAccountId = codexAccountId)

    companion object {
        /**
         * [T-android-thinking-level-arch] Codex OAuth client version advertised
         * in the Version / User-Agent headers. Bumped 0.142.3 → 0.144.1 to
         * match the CLIProxyAPI/sub2api upstream (fixes a gpt-5.6-luna 404 seen
         * on the older client). Shared constant so future bumps touch one place.
         */
        private const val CODEX_CLIENT_VERSION = "0.144.1"

        /**
         * Factory for OAuth-bearer OpenAI-compatible providers that aren't
         * Codex (e.g. xAI Grok). Same dynamic bearer plumbing, but the
         * wire format stays Chat Completions and the endpoint is the
         * caller-supplied base URL — not chatgpt.com's Responses API.
         *
         * Implemented as a factory (not a secondary ctor) because the
         * JVM erases the signature down to
         * `(Function1, LLMModel, String)` which collides with the Codex
         * ctor's `(oauthTokenProvider, model, codexAccountId)` overload.
         */
        fun oauthOpenAICompat(
            oauthTokenProvider: suspend () -> String,
            model: LLMModel,
            basePath: String,
        ): OpenAIProvider = OpenAIProvider(
            apiKey = null,
            oauthTokenProvider = oauthTokenProvider,
            model = model,
            basePath = basePath,
            forceChatCompletions = true,
        )
    }

    internal val isOAuth: Boolean get() = oauthTokenProvider != null

    // MARK: - Image passthrough [T-android-model-use-image-passthrough GH#62]

    /**
     * Arbitrary extra fields merged into the /images/generations JSON body, so
     * `minis-model-use` can pass provider-specific params our fixed schema never
     * modeled (e.g. Volcengine Seedream's `image` for image-to-image,
     * `watermark`, `tools`). User keys WIN over our defaults (response_format)
     * but never replace the resolved `model`. Empty = no passthrough. Set
     * per-call by ModelUseOffloadHandler on a freshly-built provider; never
     * persisted. Values are raw JSON (String/Number/Boolean/JSONObject/JSONArray).
     */
    var imageExtraBody: Map<String, Any?> = emptyMap()

    /** Test hook: Videos API poll cadence (create → first GET → later GETs). */
    internal var videoFirstPollMillis: Long = 1_500L
    internal var videoPollMillis: Long = 5_000L

    /**
     * Test hook: force a vendor media protocol on MockWebServer (localhost
     * hosts never match volces/bigmodel/dashscope). Null → [VendorMedia.detect].
     */
    internal var vendorMediaOverride: VendorMediaKind? = null

    internal fun resolvedVendorMedia(): VendorMediaKind =
        vendorMediaOverride ?: VendorMedia.detect(basePath, model.id)

    /**
     * Extra HTTP headers merged into the /images/generations request (added, not
     * replacing the ctor extraHeaders). Per-call, never persisted.
     */
    var imageExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Optional endpoint-path override for the image request (e.g. a non-standard
     * `/api/v3/images/generations`). When set, replaces the hardcoded
     * `/images/generations` path (base URL + this verbatim). null = default path.
     */
    var imagePathOverride: String? = null

    /** Optional video `mode` (std/pro/…). Null means omit until the provider says it is required. */
    var videoMode: String? = null

    /**
     * Mode actually placed on the last successful create body. Survives the
     * per-call clear of [videoMode] so the caller can report it, and is cleared
     * at the start of the next [generateVideo] so it is never sent again.
     */
    var videoModeSent: String? = null

    // MARK: - Chat passthrough [T-android-model-use-passthrough-mode / GH#72]

    /**
     * Arbitrary extra fields merged into the chat/completions AND responses
     * request bodies, mirroring [imageExtraBody] on the image path. Populated
     * per-call by ModelUseOffloadHandler from the input JSON's explicit
     * `extra_body` / `passthrough.body` envelope (never from implicit top-level
     * keys — the chat schema owns its top level). User keys WIN over our
     * defaults (e.g. `plugins`, `web_search_options`, provider-specific knobs)
     * but `model` is force-restored after the merge. Empty = no passthrough.
     * Mirrors iOS OpenAIProvider.chatExtraBody.
     */
    var chatExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into chat/completions and /responses requests,
     * applied AFTER the default set → same-name REPLACE semantics over every
     * default (including Authorization/Content-Type). Per-call, never persisted.
     * Mirrors iOS OpenAIProvider.extraHeaders (promoted to all endpoints).
     */
    var chatExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Absolute-path endpoint override. When set (must start with "/"), it
     * replaces the ENTIRE URL path after scheme+host — unlike [imagePathOverride],
     * which is joined after `basePath` and therefore can never escape a base-URL
     * prefix like `/compatible-mode/v1` (proven by iOS device baseline p03).
     * Applies to chat/completions, responses, and images/generations builders.
     * Never applies to Codex OAuth (hardcoded backend). Per-call, never
     * persisted. Mirrors iOS OpenAIProvider.absoluteEndpointOverride.
     */
    var absoluteEndpointOverride: String? = null

    /**
     * [T-android-model-use-passthrough-mode] Build a URL from the provider's
     * scheme+host(+port) ONLY, with [path] replacing the entire URL path.
     * [path] must start with "/" and may carry a query string. Credentials stay
     * bound to the instance's host — callers can never point this at a different
     * host. Returns null if the base URL can't be parsed. Mirrors iOS
     * OpenAIProvider.hostRootURL.
     */
    fun hostRootURL(path: String): String? {
        val base = basePath.toHttpUrlOrNull() ?: return null
        val qIdx = path.indexOf('?')
        val pathPart = if (qIdx >= 0) path.substring(0, qIdx) else path
        val queryPart = if (qIdx >= 0) path.substring(qIdx + 1) else null
        val builder = base.newBuilder()
            .encodedPath(pathPart)
            .fragment(null)
        builder.encodedQuery(queryPart)
        return builder.build().toString()
    }

    /**
     * Resolve the effective URL for a modeled endpoint, honoring the
     * absolute-path override when present. [defaultPath] is joined after
     * [basePath] (which is already normalized to base + /v1). Mirrors iOS
     * OpenAIProvider.endpointURL.
     */
    private fun endpointURL(defaultPath: String): String {
        val abs = absoluteEndpointOverride
        if (abs != null && abs.startsWith("/")) {
            hostRootURL(abs)?.let { return it }
        }
        return "$basePath$defaultPath"
    }

    // MARK: - Azure helpers [T-android-azure-openai]

    /**
     * Set the API-key auth header on a request builder. Azure uses the `api-key`
     * header; every other OpenAI-compatible endpoint uses `Authorization:
     * Bearer`. Centralized so the Azure branch can't accidentally set the wrong
     * one. Mirrors iOS OpenAIProvider.applyKeyAuth.
     */
    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        // [T-empty-key-compat-endpoints] A keyless third-party endpoint
        // (ollama, LM Studio, LiteLLM, private relays) is a supported
        // configuration. Send NO auth header rather than a malformed
        // `Authorization: Bearer ` / empty `api-key:` — strict gateways
        // reject the empty form, an absent header is universally fine.
        if (token.isEmpty()) this
        else if (isAzure) header("api-key", token)
        else header("Authorization", "Bearer $token")

    /**
     * Build the request URL for Azure OpenAI, mirroring the official AzureOpenAI
     * SDK shape (and iOS azureURL, T-ios-azure-openai-deployments):
     *
     *   {azure_endpoint}/openai/deployments/{model.id}/{path}?api-version=…
     *
     * The user pastes the resource endpoint as the custom base — typically the
     * bare `https://x.openai.azure.com`, optionally already including `/openai`,
     * with the `?api-version=…` query on it. We (1) split off the query, (2)
     * strip a trailing `/`, a stray `/v1` (Azure has no /v1), and a trailing
     * `/openai` (re-added), then (3) assemble the deployments path. [path] is
     * e.g. "/chat/completions". Returns null when no Azure base is configured.
     */
    private fun azureUrl(path: String): String? {
        val raw = azureBase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val qIdx = raw.indexOf('?')
        val query = if (qIdx >= 0) raw.substring(qIdx) else ""
        var p = (if (qIdx >= 0) raw.substring(0, qIdx) else raw).trimEnd('/')
        if (p.endsWith("/v1")) p = p.dropLast(3).trimEnd('/')
        if (p.endsWith("/openai")) p = p.dropLast("/openai".length).trimEnd('/')
        val endpointPath = path.removePrefix("/")
        return "$p/openai/deployments/${model.id}/$endpointPath$query"
    }

    /**
     * Whether this provider uses Chat Completions API (vs Responses API).
     * Responses API is used when OAuth (Codex) OR when the user explicitly
     * flipped the per-instance `useResponsesAPI` switch.
     */
    internal val usesChatCompletionsAPI: Boolean get() = forceChatCompletions || (!isOAuth && !useResponsesAPI)

    /**
     * [T-android-tool-splits-reply-fix] Chat Completions streams ONE
     * monolithic `content` string per assistant response — qwen endpoints
     * flush trailing content chunks AFTER tool_calls deltas (chunking
     * artifact), and those must merge back into the single pre-tool text
     * block instead of becoming a post-tool block (which split sentences
     * mid-word in the chat UI). The Responses API streams genuinely ordered
     * output items, so it keeps chronological reconstruction.
     */
    override val streamTextIsMonolithic: Boolean get() = usesChatCompletionsAPI

    /**
     * [T-codex-gpt-image2-oauth-android] gpt-image-2 is a special image-
     * generation model driven through the Codex OAuth backend's built-in
     * image_generation tool (wire model gpt-5.5, tools=[{type:image_generation}]).
     * Only meaningful on the Codex OAuth path; everything else (the GPT-5.x
     * Codex models and their existing OAuth flow) is untouched by this gate.
     */
    internal val isCodexImageModel: Boolean get() = isOAuth && model.id == "gpt-image-2"

    private suspend fun getToken(): String {
        oauthTokenProvider?.let { return it() }
        return apiKey ?: throw LLMError.InvalidApiKey()
    }

    // T-android-openai-codex-timeout: bump readTimeout 180s → 600s to
    // match iOS. T171 had cut it to 180s on the theory that GPT-5.x
    // thinking warm-up tops out around 60-90s, but the Codex Responses
    // OAuth path on gpt-5.5 with a real-world agent body (440KB, 20
    // messages, 8 tools) routinely sits silent on the SSE stream for
    // 2:50-3:10 between the reasoning `response.output_item.added`
    // event and the burst of text deltas after the reasoning step
    // completes — server-side it's still working, no keep-alive bytes
    // arrive in between, and OkHttp's idle-data-read counter trips.
    // The 180s cap turned that normal reasoning silence into a hard
    // SocketTimeoutException (observed in 0.10-preview, log file
    // minis-2026-05-27.log around 13:28 — 3:00 of silence then trip).
    // Going back to 600s leaves room for the longest realistic
    // reasoning bursts; the cancel-race concern T171 hedged against
    // (OkHttp call.cancel() racing a thread inside execute()) is
    // covered by the outer coroutine cancellation chain — Job.cancel
    // propagates down through the agent loop and the socket gets
    // closed via Call.cancel() from the coroutine's invokeOnCancellation,
    // so a stuck OAuth read never lingers past the agent turn.
    //
    // T-android-openai-codex-timeout: also attach an OkHttp EventListener
    // so future timeout reports show WHICH leg of the network path
    // stalled — DNS, proxy connect, TLS handshake, idle-after-headers,
    // or mid-stream silence. Previous OAuth-streaming logs only printed
    // request/response envelopes; when a SocketTimeoutException fired
    // we had no way to tell whether the upstream proxy went away
    // (idle-close after 3min, common with clash/v2ray), TLS renegotiated,
    // or the server itself stopped emitting bytes. Each milestone goes
    // through AppLogger.info at the OkHttpEvents tag with the call's
    // identity hash so concurrent streams can be disambiguated.
    internal val client = OkHttpClient.Builder()
        // [T-doh-resolver-fallback] Custom/OpenRouter endpoints are arbitrary
        // hostnames, so this is the path most exposed to a resolver that
        // cannot name them. Must stay above the connection pool comment below.
        .dns(com.openminis.app.network.DohDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
        // The eviction is NetworkMonitor.evictSharedPoolOnReconnect(), which
        // calls evictAll() on the SHARED pool when a callback actually flips
        // the merged status to CONNECTED; the per-call watchdog in
        // OpenAIRawStream only ever closes its own socket, so this pool-level
        // pass is what clears tunnels that died with the previous interface.
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        .build()

    /** Detect OpenRouter base URL. */
    private val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    /**
     * [OpenMinis#191] OpenRouter does NOT enable Anthropic prompt caching
     * automatically — unlike the OpenAI / Grok / Moonshot / Groq models it
     * hosts, which cache with no opt-in. Claude requests must carry an explicit
     * `cache_control` breakpoint or nothing is cached at all, which is why the
     * reporter measured `cache_read_input_tokens` / `cache_write_tokens` pinned
     * at 0 across every turn and a 3-6x cost overrun.
     *
     * Matched on the `anthropic/` model-id prefix, OpenRouter's namespace for
     * the Claude family (`anthropic/claude-sonnet-4.5`, `anthropic/claude-opus-4.1`,
     * …). Scoped to OpenRouter AND that prefix so every other model on the
     * gateway keeps a byte-identical request body.
     *
     * Note the gate is the HOST-matched [isOpenRouter], never a compat flag:
     * on iOS the equivalent `useOpenRouterCompat` only selects the legacy
     * `max_tokens` / no-`stream_options` body shape and Mistral sets it too, so
     * keying on it would have leaked the field into Mistral requests. Android's
     * [isOpenRouter] is already host-matched, the same way [isDashScope] is.
     *
     * Carries the same caveat as [isMistral]: a relay or vanity domain without
     * `openrouter.ai` in its URL is not recognised, which fails safe — the
     * request simply goes out unchanged, i.e. today's behaviour.
     */
    private val needsOpenRouterAnthropicCacheControl: Boolean
        get() = isOpenRouter && model.id.lowercase().startsWith("anthropic/")

    /** Detect DashScope (Alibaba Qwen) base URL. */
    private val isDashScope: Boolean = basePath.contains("dashscope")

    /**
     * [T-android-mistral-reasoning-422] (GH OpenMinis#87, iOS 29065ca0)
     * Detect Mistral's OpenAI-compatible endpoint.
     *
     * Mistral's AssistantMessage is a CLOSED schema
     * (`additionalProperties: false`; only role/content/tool_calls/prefix), so
     * `reasoning_content` on a prior assistant turn is rejected outright with
     * HTTP 422 `extra_forbidden`. Their native reasoning representation is a
     * different, Mistral-signed mechanism (content ThinkChunks), not this
     * field. Note the REQUEST schema has no additionalProperties:false, which
     * is why only multi-turn history carrying reasoning_content ever 422'd
     * while spec-external top-level params went through fine.
     *
     * Case-insensitive to match iOS (LLMProviderFactory lowercases before the
     * same `contains("mistral.ai")` test) — hosts are case-insensitive, so a
     * user typing `API.Mistral.AI` must still be recognised.
     *
     * Known limits, both inherited from iOS's identical predicate: a relay that
     * proxies Mistral models under its own hostname is not detected (still
     * 422s), and a URL that merely mentions mistral.ai in a query string would
     * over-suppress (harmless — the field is optional for everyone else).
     */
    private val isMistral: Boolean = basePath.lowercase().contains("mistral.ai")

    /**
     * [OpenMinis#163] Talking to xAI's own API (api.x.ai), as opposed to a relay
     * that merely serves grok-named models. Mirrors iOS OpenAIProvider.isXAI.
     *
     * Scopes the "catalog declares no effort tiers → omit reasoning_effort" skip
     * to first-party xAI. The bundled catalog marks 2090 entries across many
     * vendors with the same empty-tier shape (relay-hosted Claude, GPT-5, Qwen,
     * and grok itself behind poe / fastrouter / anyapi); while omitting the
     * field is arguably more correct for some of those too, none of those routes
     * has been verified, so the skip stays where the 400 was actually observed.
     *
     * URL matching alone is sufficient: ProviderFactory always populates a base
     * for xAI, defaulting to https://api.x.ai/v1 when the user set no override.
     */
    private val isXAI: Boolean = basePath.lowercase().let {
        it.contains("api.x.ai") || it.contains("//x.ai")
    }

    /**
     * [T-unified-reasoning-effort] Whether this endpoint applies OpenAI's
     * `reasoning_effort` (Chat) / `reasoning.effort` (Responses) uniformly to
     * EVERY model it hosts — including third-party families (GLM / Kimi /
     * DeepSeek / MiniMax) that, at their vendor-native endpoint, would instead
     * use a `thinking:{}` object or self-reason with no toggle.
     *
     * Three known such gateways (mirrors iOS OpenAIProvider.usesUnifiedReasoningEffort):
     *   • Volcengine Ark (`ark.` / `volces` in the base URL) — re-exposes
     *     doubao/deepseek/glm/kimi through a single OpenAI-compatible surface
     *     where thinking is controlled ONLY by `reasoning_effort` (min tier
     *     `minimal`); the vendor-native `thinking:{}` shape is not honored.
     *   • Azure OpenAI ([isAzure]) — reasoning is `reasoning_effort` for every
     *     model surfaced through the deployment.
     *   • Venice.ai (`api.venice.ai`) — [OpenMinis#86] resells deepseek / claude /
     *     aion behind one OpenAI-compatible surface. Its ChatCompletionRequest
     *     schema is `additionalProperties: false`, so an unknown root key is
     *     rejected at validation time — BEFORE model dispatch — with
     *     `400 Unrecognized key(s) in object: 'thinking'`. That is why every
     *     model failed and why turning thinking OFF did not help: the
     *     `{"type":"disabled"}` branch still sends the key. Venice natively
     *     accepts root `reasoning_effort`, a superset of the tiers the generic
     *     path emits, so no value mapping is needed.
     *
     * Gated tightly so official direct endpoints (DeepSeek/GLM/Kimi native,
     * which DO want their own thinking shape) are never mis-routed. Caveat (same
     * class as [isMistral]): a relay or vanity domain that does not carry these
     * hosts in its URL is still exposed.
     */
    private val usesUnifiedReasoningEffort: Boolean =
        isAzure || basePath.lowercase().let {
            it.contains("volces") || it.contains("ark.") || it.contains("api.venice.ai")
        }

    /**
     * [T-thinking-off-explicit] The wire value for "thinking OFF", or null to
     * keep the historical omit-the-field behavior. ALLOWLIST, not blanket
     * (mirrors iOS OpenAIAgentProvider.explicitOffEffort): only vendors whose
     * off tier is DOCUMENTED get an explicit value —
     *   • official OpenAI base (non-Azure) → "none" (documented off tier);
     *   • Volcano Ark (volces/ark bases, seed/doubao families) → "minimal"
     *     (their smallest tier — Ark's non-off default is what motivated this).
     * Everyone else (relays, NIM, xAI, MiMo, …) keeps field omission = the
     * vendor's own default. Azure stays omission too: its off tier is
     * model-dependent ('none' on gpt-5.1+, 'minimal' on original gpt-5,
     * unsupported on o1/o3), so an explicit value risks a 400.
     */
    private fun explicitOffEffort(): String? {
        if (isAzure) return null
        val base = basePath.lowercase()
        if (base.startsWith("https://api.openai.com")) return "none"
        val lid = model.id.lowercase()
        if (base.contains("volces") || base.contains("ark.") ||
            lid.contains("seed-") || lid.contains("doubao")
        ) {
            return "minimal"
        }
        return null
    }

    /**
     * Non-streaming entry point. Some providers (e.g. GPT-5.x via certain
     * gateways, Codex Responses backend) reject `stream=false` outright with
     * `[400] Stream must be set to true`. To keep this method usable across
     * all providers we always issue a streaming request internally and
     * concatenate the deltas back into a single [LLMResponse]. Callers that
     * actually want incremental delivery should use [streamMessage] instead.
     */
    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val textBuf = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        // [T-codex-gpt-image2-oauth-android] Collect model-generated media
        // (gpt-image-2 images) so non-streaming callers — notably
        // minis-model-use (ModelUseOffloadHandler) — get them on
        // LLMResponse.mediaAttachments and can write the image to --output.
        val media = mutableListOf<LLMMediaAttachment>()
        rawStreamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
            stream = false,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textBuf.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                is LLMStreamChunk.MediaAttachment -> media.add(chunk.attachment)
                else -> Unit
            }
        }
        LLMResponse(textBuf.toString(), stopReason, usage, media)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        systemStablePrefixLen: Int,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        stream = true,
    ).failOnSilentEmptyCompletion(name)


    // MARK: - Raw Passthrough [T-android-model-use-passthrough-mode]

    /**
     * Result of a raw passthrough call: unparsed response bytes + HTTP status +
     * the fully-assembled URL that was actually hit (surfaced to the caller per
     * the passthrough-mode contract). Mirrors iOS RawPassthroughResult.
     */
    class RawPassthroughResult(
        val data: ByteArray,
        val status: Int,
        val contentType: String?,
        val url: String,
    )

    /**
     * Execute a verbatim request against this provider instance's base URL with
     * the instance's credentials. The response is returned UNPARSED — passthrough
     * mode's output contract is raw bytes; the caller (agent or follow-up script)
     * owns interpretation. Mirrors iOS OpenAIProvider.rawPassthroughRequest.
     *
     * - endpoint: absolute path ("/x/y?q=1", replaces the whole URL path) or
     *   relative segment (joined after basePath like modeled endpoints). null →
     *   the default chat/completions path.
     * - headers: applied LAST → same-name REPLACE semantics over every default
     *   (including Authorization/Content-Type), per design.
     */
    suspend fun rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: Map<String, String>,
        body: HttpBody?,
    ): RawPassthroughResult = withContext(Dispatchers.IO) {
        val url: String = when {
            endpoint != null && endpoint.startsWith("/") ->
                hostRootURL(endpoint)
                    ?: throw LLMError.ProviderError("Invalid passthrough endpoint: $endpoint")
            endpoint != null -> "$basePath/${endpoint.trimStart('/')}"
            else -> endpointURL("/chat/completions")
        }

        if (body != null) RequestBodyGate.check(body, "rawPassthrough")
        val admitted = body?.estimatedBytes ?: 0L

        val verb = method.uppercase()
        val builder = Request.Builder().url(url)
        // GET never carries a body. POST/PUT/PATCH/DELETE with Empty/null still
        // need a RequestBody — OkHttp rejects method(POST, null).
        val requestBody = com.openminis.app.data.body.Admission.occupy(admitted) {
            if (verb == "GET") {
                null
            } else {
                body?.toOkHttpRequestBody()
                    ?: ByteArray(0).toRequestBody(HttpBody.JSON_MEDIA_TYPE)
            }
        }
        builder.method(verb, requestBody)
        val token = getToken()
        builder.applyKeyAuth(token)
        // Content-Type is DERIVED. Multipart returns null so OkHttp owns the
        // boundary; a user Content-Type header must not clobber that.
        val multipart = body is HttpBody.Multipart
        if (!multipart) {
            val derived = body?.contentType() ?: HttpBody.JSON_MEDIA_TYPE
            builder.header("Content-Type", derived.toString())
        }
        // ctor extraHeaders, then user headers LAST — replace semantics,
        // except Content-Type on multipart (boundary belongs to OkHttp).
        for ((k, v) in extraHeaders) {
            if (multipart && k.equals("Content-Type", ignoreCase = true)) continue
            builder.header(k, v)
        }
        for ((k, v) in headers) {
            if (multipart && k.equals("Content-Type", ignoreCase = true)) continue
            builder.header(k, v)
        }

        val bodyKeys = when (body) {
            is HttpBody.Json -> body.obj.keys().asSequence().sorted().joinToString(",")
            is HttpBody.Multipart -> body.parts.joinToString(",") { it.name }
            else -> ""
        }
        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] route=raw-passthrough method=$verb url=$url " +
                "bodyKeys=[$bodyKeys] " +
                "headerOverrides=[${headers.keys.sorted().joinToString(",")}]",
        )

        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            RawPassthroughResult(
                data = resp.body?.bytes() ?: ByteArray(0),
                status = resp.code,
                contentType = resp.header("Content-Type"),
                url = url,
            )
        }
    }

    /**
     * [T-android-image-endpoint-mode] Generate an image via the OpenAI Images
     * API (`POST $basePath/images/generations`). Mirrors iOS
     * OpenAIProvider.generateImage. Used only by ModelUseOffloadHandler's
     * image-output routing for API-key OpenAI-compat instances — the Codex
     * OAuth gpt-image-2 path goes through the existing isCodexImageModel branch
     * and never reaches here.
     *
     * Request body: `{ model, prompt, n, size?, quality?, response_format:
     * "b64_json" }`. Some gateways (e.g. xAI) reject `response_format` — on a
     * 400 mentioning it, we retry once without the field (iOS parity).
     *
     * On a non-2xx response throws [mapHttpError]'s result. A route-missing
     * error (404 / "got chat completions response") surfaces as
     * LLMError.ProviderError whose message the handler matches with
     * looksLikeEndpointMissing() to drive the auto-mode fallback.
     */
    override suspend fun generateImage(
        prompt: String,
        n: Int,
        size: String?,
        quality: String?,
    ): LLMResponse = withContext(Dispatchers.IO) {
        // Codex OAuth image models use the existing Responses SSE image_generation tool.
        if (isCodexImageModel) {
            return@withContext sendMessage(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, prompt.trim())),
                systemPrompt = null,
                maxTokens = 1024,
            )
        }
        val vendor = resolvedVendorMedia()
        VendorMedia.unsupportedMessage(vendor, "image")?.let { throw LLMError.ProviderError(it) }
        val token = getToken()
        if (vendor == VendorMediaKind.DASHSCOPE && VendorMedia.looksLikeWanxNativeImage(model.id)) {
            return@withContext generateDashScopeImage(prompt, n, size, token)
        }
        if (vendor == VendorMediaKind.MINIMAX) {
            return@withContext generateMinimaxImage(prompt, n, token)
        }
        // [T-android-model-use-image-passthrough GH#62] Honor an explicit
        // endpoint-path override (non-standard providers); default otherwise.
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/generations"
        // [T-android-model-use-passthrough-mode] The absolute-path override wins
        // over the legacy relative imagePathOverride (which is joined after
        // basePath and can't escape base prefixes — iOS baseline p03).
        // [T-android-azure-openai] Azure image generation routes via the
        // deployments path + api-key header; falls back to basePath otherwise.
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            // Ark Seedream and Zhipu CogView speak OpenAI Images JSON but live
            // at a host-root path that /v1 suffixing would miss.
            imagePathOverride.isNullOrBlank() && vendor == VendorMediaKind.ARK ->
                hostRootURL("/api/v3/images/generations") ?: "$basePath$imagePath"
            imagePathOverride.isNullOrBlank() && vendor == VendorMediaKind.ZHIPU ->
                hostRootURL("/api/paas/v4/images/generations") ?: "$basePath$imagePath"
            imagePathOverride.isNullOrBlank() && vendor == VendorMediaKind.DASHSCOPE ->
                hostRootURL("/compatible-mode/v1/images/generations") ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        // [T-android-model-use-image-passthrough GH#62] When the user explicitly
        // supplies response_format, respect it and skip the b64_json auto-probe.
        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val body = JSONObject()
                .put("model", model.id)
                .put("prompt", prompt)
                .put("n", n)
            if (size != null) body.put("size", size)
            if (quality != null) body.put("quality", quality)
            if (!triedWithoutFormat) body.put("response_format", "b64_json")
            // [T-android-model-use-image-passthrough GH#62] Merge user-supplied
            // passthrough fields. User keys WIN over our defaults (they can
            // override prompt/size or add Seedream's `image`/`watermark`), but
            // `model` is force-kept to the resolved id afterward so a stray
            // override can't misroute the request.
            for ((k, v) in imageExtraBody) body.put(k, v ?: JSONObject.NULL)
            body.put("model", model.id)

            val bodyStr = body.toString()
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url(url)
                .post(requestBody)
                .applyKeyAuth(token)
                .header("Content-Type", "application/json")
            for ((key, value) in extraHeaders) {
                builder.header(key, value)
            }
            // [T-android-model-use-image-passthrough GH#62] Per-call passthrough
            // headers, merged after the ctor extraHeaders so they can add/override.
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyUserAgentOverride(customUserAgent)
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/generations url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            // Some providers (xAI) don't support b64_json — retry without it once.
            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/generations returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/generations: unreachable")
    }

    /**
     * [T-android-image-edit-endpoint] Call `/images/edits` for image-to-image
     * (reference-image) generation. Android previously had no such endpoint, so
     * minis-model-use returned `image_edit_not_supported` for every
     * input-image + pure-image-generator call — the gap this closes. Mirrors
     * iOS `OpenAIProvider.editImage`.
     *
     * Request: multipart/form-data with `image` (file), `prompt`, `model`, `n`,
     * plus optional `size` / `quality`.
     * Response: identical shape to `/images/generations`
     * (`{ data: [{ b64_json?, url? }] }`), so [parseImageGenerationsResult] is
     * reused verbatim.
     *
     * Multi-image: the first attachment goes in as `image`, any extras as
     * `image[]` — same field naming as iOS. Providers that only accept a single
     * reference image reject the extras themselves; nothing is silently dropped
     * on our side.
     */
    suspend fun editImage(
        prompt: String,
        images: List<LLMMessage.ImagePart>,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        if (images.isEmpty()) {
            throw LLMError.ProviderError("images/edits requires at least one input image")
        }
        val token = getToken()
        // Same override precedence as generateImage: explicit path override →
        // Azure deployments path → basePath. Only the default differs.
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/edits"
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        // b64_json auto-probe, mirroring generateImage: some providers reject
        // response_format on the edits route, so retry once without it.
        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipart.addFormDataPart("model", model.id)
            multipart.addFormDataPart("prompt", prompt)
            multipart.addFormDataPart("n", n.toString())
            if (size != null) multipart.addFormDataPart("size", size)
            if (quality != null) multipart.addFormDataPart("quality", quality)
            if (!triedWithoutFormat) multipart.addFormDataPart("response_format", "b64_json")
            // Passthrough body fields arrive as JSON scalars; multipart carries
            // text only, so stringify. `model` is re-pinned below so a stray
            // override can't misroute the request (same rule as generateImage).
            for ((k, v) in imageExtraBody) {
                if (k == "model") continue
                multipart.addFormDataPart(k, v?.toString() ?: "")
            }

            for ((idx, img) in images.withIndex()) {
                val ext = img.mimeType.substringAfterLast('/', "").ifEmpty { "png" }
                val fieldName = if (idx == 0) "image" else "image[]"
                multipart.addFormDataPart(
                    fieldName,
                    "image$idx.$ext",
                    img.data.toRequestBody(img.mimeType.toMediaType()),
                )
            }

            val builder = Request.Builder()
                .url(url)
                .post(multipart.build())
                .applyKeyAuth(token)
            for ((key, value) in extraHeaders) {
                builder.header(key, value)
            }
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyUserAgentOverride(customUserAgent)
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/edits url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality images=${images.size} " +
                    "respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/edits returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/edits: unreachable")
    }

    /**
     * OpenAI Videos API (`POST /videos` + poll + `/content`) and common
     * OpenAI-compatible relay shapes (`/video/generations`, sync `data[].url`).
     */
    /**
     * Blocking [Call.execute] does not notice coroutine cancellation until the
     * socket times out. Stop must tear the video request down the same way the
     * streaming path does: [Call.cancel] from the cancellation handler.
     */
    private suspend fun Call.executeCancellable(): Response {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            try {
                val response = execute()
                if (cont.isActive) cont.resume(response) else runCatching { response.close() }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    }

    override suspend fun generateVideo(prompt: String): LLMResponse = try {
        videoModeSent = null
        withContext(Dispatchers.IO) {
            ProviderKeyGate.withPermit(callGateKey) {
                generateVideoLocked(prompt.trim())
            }
        }
    } finally {
        // Per-call. A later video request must not inherit std/pro from this one.
        videoMode = null
    }

    private suspend fun generateVideoLocked(prompt: String): LLMResponse {
        if (prompt.isEmpty()) throw LLMError.ProviderError("Video prompt is empty")
        val token = getToken()
        val vendor = resolvedVendorMedia()
        VendorMedia.unsupportedMessage(vendor, "video")?.let { throw LLMError.ProviderError(it) }
        when (vendor) {
            VendorMediaKind.ARK -> return generateArkVideo(prompt, token)
            VendorMediaKind.ZHIPU -> return generateZhipuVideo(prompt, token)
            VendorMediaKind.DASHSCOPE -> return generateDashScopeVideo(prompt, token)
            VendorMediaKind.MINIMAX -> return generateMinimaxVideo(prompt, token)
            else -> Unit
        }
        val abs = absoluteEndpointOverride?.takeIf { it.startsWith("/") }
        // Image calls join under basePath (`/v1/images/generations`) and the same
        // key works. Guessing host-root `/videos` first hits a different gateway
        // that answers "Invalid API key" and used to abort before `/v1/videos`.
        val candidates = linkedMapOf<String, Boolean>()
        fun addCandidate(url: String, authFatal: Boolean) {
            if (url.isNotBlank()) candidates.putIfAbsent(url, authFatal)
        }
        if (abs != null) {
            addCandidate(hostRootURL(abs) ?: "$basePath$abs", true)
        } else {
            addCandidate("$basePath/videos", true)
            hostRootURL("/v1/videos")?.let { addCandidate(it, false) }
            addCandidate("$basePath/video/generations", false)
            addCandidate("$basePath/videos/generations", false)
            hostRootURL("/videos")?.let { addCandidate(it, false) }
            hostRootURL("/video/generations")?.let { addCandidate(it, false) }
            hostRootURL("/videos/generations")?.let { addCandidate(it, false) }
        }
        var lastError: LLMError? = null
        for ((url, authFatal) in candidates) {
            val path = when {
                url.contains("/video/generations") -> "/video/generations"
                url.contains("/videos/generations") -> "/videos/generations"
                else -> "/videos"
            }
            var modeToSend = videoMode?.trim()?.takeIf { it.isNotEmpty() }
            var triedDefaultMode = false
            while (true) {
            val body = org.json.JSONObject()
                .put("model", model.id)
                .put("prompt", prompt)
            if (modeToSend != null) body.put("mode", modeToSend)
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .applyKeyAuth(token)
                .header("Content-Type", "application/json")
                .apply {
                    for ((k, v) in extraHeaders) header(k, v)
                }
                .applyUserAgentOverride(customUserAgent)
                .build()
            val response = client.newCall(req).executeCancellable()
            val code = response.code
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val contentType = response.header("Content-Type").orEmpty()
            response.close()
            if (code == 404 || code == 405 || (!authFatal && (code == 401 || code == 403))) {
                lastError = mapHttpError(code, bytes.decodeToString())
                break
            }
            if (code !in 200..299) {
                val errText = bytes.decodeToString()
                if (!triedDefaultMode && modeToSend == null &&
                    errText.contains("mode", ignoreCase = true) &&
                    errText.contains("required", ignoreCase = true)
                ) {
                    triedDefaultMode = true
                    modeToSend = "std"
                    continue
                }
                throw mapHttpError(code, errText, null)
            }
            videoModeSent = modeToSend
            if (looksLikeMp4(bytes)) {
                return LLMResponse("", "end_turn", null, listOf(videoAtt(bytes)))
            }
            val text = bytes.decodeToString()
            if (text.isBlank()) {
                // 空响应：socket 被 STALL 后取消时 read 会提前返回空 bytes。
                // 协程已取消就按取消传播（CancellationException），别伪装成
                // ProviderError —— 否则 cancelAndJoin 的调用方收到错误而非取消。
                kotlin.coroutines.coroutineContext.ensureActive()
                throw LLMError.ProviderError("Video create: empty response")
            }
            val json = try {
                org.json.JSONObject(text)
            } catch (_: Exception) {
                throw LLMError.ProviderError("Video create: not JSON (${contentType.take(40)})")
            }
            // [T-video-poll-url-from-create] Poll under the mount point that ACTUALLY
            // answered 2xx, not under basePath. The candidate list above deliberately
            // tries host-root URLs (`https://host/videos`, the absolute override) for
            // relays that do not mount the videos API under `/v1`; rebuilding the poll
            // URL from `basePath` then asked `https://host/v1/videos/<id>` for a job
            // created at `https://host/videos` — a 404 on exactly the relays that had
            // required the host-root create, and only AFTER the user had spent the
            // quota. Stripping the create path off the create URL is the identity
            // mapping for every candidate shape (`$basePath$path` and `hostRootURL$path`
            // both end in `path`); an override that does not end in it falls back to
            // basePath, which is the historical behaviour.
            val pollBase = url.removeSuffix(path).let { if (it == url) basePath else it }
            return resolveVideoJob(json, token, path, pollBase)
            }
        }
        throw lastError ?: LLMError.ProviderError("No video endpoint on this provider")
    }

    private suspend fun resolveVideoJob(
        json: org.json.JSONObject,
        token: String,
        createPath: String,
        // [T-video-poll-url-from-create] Origin (scheme://host[:port] + any mount
        // prefix) taken from the create URL that returned 2xx. Defaults to basePath
        // so any other caller keeps its historical behaviour.
        pollBase: String = basePath,
    ): LLMResponse {
        extractVideoAttachment(json)?.let { return it }
        val err = json.optJSONObject("error")?.safeOptString("message", "")
            ?: json.safeOptString("message", "")
        val status0 = json.safeOptString("status", json.safeOptString("task_status", ""))
        if (status0.equals("failed", true) || status0.equals("error", true)) {
            throw LLMError.ProviderError(err.ifBlank { "Video generation failed" })
        }
        val id = json.safeOptString("id", json.safeOptString("task_id", json.safeOptString("taskId", "")))
        if (id.isEmpty()) {
            if (err.isNotBlank()) throw LLMError.ProviderError(err)
            throw LLMError.ProviderError("Video create returned no id and no file")
        }
        val pollPath = when {
            createPath.contains("video/generations") -> "/video/generations/$id"
            createPath.contains("videos/generations") -> "/videos/generations/$id"
            else -> "/videos/$id"
        }
        var lastJson = json
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) videoFirstPollMillis else videoPollMillis)
            val pollReq = Request.Builder()
                // [T-video-poll-url-from-create] `pollBase`, not `basePath`: a job
                // created at a host-root URL must be polled there, or the very
                // relays that needed the host-root create 404 the poll.
                .url("$pollBase$pollPath")
                .get()
                .applyKeyAuth(token)
                .apply {
                    for ((k, v) in extraHeaders) header(k, v)
                }
                .applyUserAgentOverride(customUserAgent)
                .build()
            val resp = client.newCall(pollReq).executeCancellable()
            val code = resp.code
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            resp.close()
            if (code !in 200..299) throw mapHttpError(code, bodyBytes.decodeToString())
            if (looksLikeMp4(bodyBytes)) {
                return LLMResponse("", "end_turn", null, listOf(videoAtt(bodyBytes)))
            }
            lastJson = try {
                org.json.JSONObject(bodyBytes.decodeToString())
            } catch (_: Exception) {
                throw LLMError.ProviderError("Video poll: not JSON")
            }
            extractVideoAttachment(lastJson)?.let { return it }
            val st = lastJson.safeOptString("status", lastJson.safeOptString("task_status", ""))
            if (st.equals("failed", true) || st.equals("error", true)) {
                val msg = lastJson.optJSONObject("error")?.safeOptString("message", "")
                    ?: lastJson.safeOptString("message", "Video generation failed")
                throw LLMError.ProviderError(msg)
            }
            if (st.equals("completed", true) || st.equals("success", true) || st.equals("succeeded", true)) {
                downloadVideoContent(token, id, pollBase)?.let { return it }
                throw LLMError.ProviderError("Video completed but no file/url")
            }
        }
        throw LLMError.ProviderError("Video generation timed out")
    }

    private suspend fun extractVideoAttachment(json: org.json.JSONObject): LLMResponse? {
        val data = json.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                attachmentFromItem(item)?.let { return LLMResponse(item.safeOptString("revised_prompt", ""), "end_turn", null, listOf(it)) }
            }
        }
        attachmentFromItem(json)?.let { return LLMResponse("", "end_turn", null, listOf(it)) }
        json.optJSONObject("output")?.let { out ->
            attachmentFromItem(out)?.let { return LLMResponse("", "end_turn", null, listOf(it)) }
        }
        json.optJSONObject("result")?.let { out ->
            attachmentFromItem(out)?.let { return LLMResponse("", "end_turn", null, listOf(it)) }
        }
        return null
    }

    private suspend fun attachmentFromItem(item: org.json.JSONObject): LLMMediaAttachment? {
        val b64 = item.safeOptString("b64_json", item.safeOptString("video_b64", ""))
        if (b64.isNotEmpty()) {
            val raw = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
            if (raw != null && raw.isNotEmpty()) return videoAtt(raw)
        }
        val url = item.safeOptString(
            "url",
            item.safeOptString("video_url", item.safeOptString("output", "")),
        )
        if (url.startsWith("http")) {
            return downloadUrl(url)
        }
        return null
    }

    private suspend fun downloadUrl(url: String): LLMMediaAttachment? {
        return try {
            val resp = client.newCall(Request.Builder().url(url).get().build()).executeCancellable()
            val mime = resp.header("Content-Type")
            val raw = resp.body?.bytes()
            resp.close()
            if (raw != null && raw.isNotEmpty()) videoAtt(raw, mime) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun downloadVideoContent(
        token: String,
        id: String,
        // [T-video-poll-url-from-create] Same origin the CREATE (and therefore the
        // poll) succeeded against — a host-root relay 404s here too, which used to
        // surface as "Video completed but no file/url" after the quota was spent.
        pollBase: String = basePath,
    ): LLMResponse? {
        val url = "$pollBase/videos/$id/content"
        val req = Request.Builder()
            .url(url)
            .get()
            .applyKeyAuth(token)
            .apply {
                for ((k, v) in extraHeaders) header(k, v)
            }
            .applyUserAgentOverride(customUserAgent)
            .build()
        val resp = client.newCall(req).executeCancellable()
        val code = resp.code
        val raw = resp.body?.bytes() ?: ByteArray(0)
        resp.close()
        if (code !in 200..299) return null
        if (raw.isEmpty()) return null
        if (looksLikeMp4(raw) || raw.size > 256) {
            return LLMResponse("", "end_turn", null, listOf(videoAtt(raw)))
        }
        return null
    }

    private fun videoAtt(bytes: ByteArray, mimeHint: String? = null): LLMMediaAttachment {
        val mime = when {
            mimeHint != null && mimeHint.startsWith("video/") -> mimeHint.substringBefore(';')
            else -> "video/mp4"
        }
        return LLMMediaAttachment(LLMMediaAttachment.MediaType.VIDEO, mime, bytes)
    }

    private fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // ....ftyp
        return bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }

    private suspend fun generateArkVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath("/api/v3/contents/generations/tasks", "Ark video")
        val body = JSONObject().put("model", model.id)
        if (VendorMedia.looksLikeSeedance(model.id)) {
            body.put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
            )
        } else {
            body.put("prompt", prompt)
            body.put("duration", 5)
            body.put("resolution", "720p")
        }
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return pollVendorVideo(
            JSONObject(created.second),
            token,
            pollUrl = { id -> requireHostPath("/api/v3/contents/generations/tasks/$id", "Ark video poll") },
            minPollMillis = 8_000L,
            label = "Ark video",
        )
    }

    private suspend fun generateZhipuVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath("/api/paas/v4/videos/generations", "Zhipu video")
        val body = JSONObject().put("model", model.id).put("prompt", prompt)
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return pollVendorVideo(
            JSONObject(created.second),
            token,
            pollUrl = { id -> requireHostPath("/api/paas/v4/async-result/$id", "Zhipu video poll") },
            minPollMillis = 0L,
            label = "Zhipu video",
        )
    }

    private suspend fun generateDashScopeVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath(
            "/api/v1/services/aigc/video-generation/video-synthesis",
            "DashScope video",
        )
        val body = JSONObject()
            .put("model", model.id)
            .put("input", JSONObject().put("prompt", prompt))
            .put("parameters", JSONObject())
        val created = postMediaJson(url, token, body, mapOf("X-DashScope-Async" to "enable"))
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return pollVendorVideo(
            JSONObject(created.second),
            token,
            pollUrl = { id -> requireHostPath("/api/v1/tasks/$id", "DashScope video poll") },
            minPollMillis = 0L,
            label = "DashScope video",
        )
    }

    private suspend fun generateMinimaxVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath("/v1/video_generation", "MiniMax video")
        val body = JSONObject().put("model", model.id).put("prompt", prompt)
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        val createdJson = JSONObject(created.second)
        val taskId = VendorMedia.taskId(createdJson)
        if (taskId.isEmpty()) throw LLMError.ProviderError("MiniMax video create returned no task_id")
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(vendorPollDelay(attempt, 0L))
            val pollUrl = requireHostPath(
                "/v1/query/video_generation?task_id=$taskId",
                "MiniMax video poll",
            )
            val poll = getMedia(pollUrl, token)
            if (poll.first !in 200..299) throw mapHttpError(poll.first, poll.second)
            val json = JSONObject(poll.second)
            val st = VendorMedia.taskStatus(json)
            if (VendorMedia.isFailedStatus(st)) {
                throw LLMError.ProviderError(VendorMedia.errorMessage(json).ifBlank { "MiniMax video failed" })
            }
            VendorMedia.extractHttpVideoUrl(json)?.let { return videoFromUrl(it) }
            val fileId = VendorMedia.minimaxFileId(json)
            if (fileId.isNotEmpty() && (VendorMedia.isSuccessStatus(st) || st.isEmpty())) {
                val fileUrl = requireHostPath("/v1/files/retrieve?file_id=$fileId", "MiniMax file")
                val fileResp = getMedia(fileUrl, token)
                if (fileResp.first !in 200..299) throw mapHttpError(fileResp.first, fileResp.second)
                val fileJson = JSONObject(fileResp.second)
                VendorMedia.extractHttpVideoUrl(fileJson)?.let { return videoFromUrl(it) }
                throw LLMError.ProviderError("MiniMax video completed but no download_url")
            }
        }
        throw LLMError.ProviderError("MiniMax video generation timed out")
    }

    private suspend fun generateDashScopeImage(
        prompt: String,
        n: Int,
        size: String?,
        token: String,
    ): LLMResponse {
        val url = requireHostPath(
            "/api/v1/services/aigc/text2image/image-synthesis",
            "DashScope image",
        )
        val parameters = JSONObject().put("n", n)
        val sizeValue = size?.replace('x', '*')?.replace('X', '*') ?: "1024*1024"
        parameters.put("size", sizeValue)
        val body = JSONObject()
            .put("model", model.id)
            .put("input", JSONObject().put("prompt", prompt))
            .put("parameters", parameters)
        val created = postMediaJson(url, token, body, mapOf("X-DashScope-Async" to "enable"))
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        var json = JSONObject(created.second)
        val immediate = imageResponseFromVendorJson(json)
        if (immediate.mediaAttachments.isNotEmpty()) return immediate
        val id = VendorMedia.taskId(json)
        if (id.isEmpty()) {
            val err = VendorMedia.errorMessage(json)
            throw LLMError.ProviderError(err.ifBlank { "DashScope image create returned no task_id" })
        }
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(vendorPollDelay(attempt, 0L))
            val poll = getMedia(requireHostPath("/api/v1/tasks/$id", "DashScope image poll"), token)
            if (poll.first !in 200..299) throw mapHttpError(poll.first, poll.second)
            json = JSONObject(poll.second)
            val st = VendorMedia.taskStatus(json)
            if (VendorMedia.isFailedStatus(st)) {
                throw LLMError.ProviderError(VendorMedia.errorMessage(json).ifBlank { "DashScope image failed" })
            }
            val parsed = imageResponseFromVendorJson(json)
            if (parsed.mediaAttachments.isNotEmpty()) return parsed
            if (VendorMedia.isSuccessStatus(st)) {
                throw LLMError.ProviderError("DashScope image completed but no url")
            }
        }
        throw LLMError.ProviderError("DashScope image generation timed out")
    }

    private suspend fun generateMinimaxImage(prompt: String, n: Int, token: String): LLMResponse {
        val url = requireHostPath("/v1/image_generation", "MiniMax image")
        val body = JSONObject()
            .put("model", model.id)
            .put("prompt", prompt)
            .put("n", n)
            .put("response_format", "base64")
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return imageResponseFromVendorJson(JSONObject(created.second))
    }

    private suspend fun pollVendorVideo(
        created: JSONObject,
        token: String,
        pollUrl: (String) -> String,
        minPollMillis: Long,
        label: String,
    ): LLMResponse {
        val err0 = VendorMedia.errorMessage(created)
        val st0 = VendorMedia.taskStatus(created)
        if (VendorMedia.isFailedStatus(st0)) {
            throw LLMError.ProviderError(err0.ifBlank { "$label failed" })
        }
        VendorMedia.extractHttpVideoUrl(created)?.let { return videoFromUrl(it) }
        val id = VendorMedia.taskId(created)
        if (id.isEmpty()) {
            throw LLMError.ProviderError(err0.ifBlank { "$label create returned no task id and no file" })
        }
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(vendorPollDelay(attempt, minPollMillis))
            val poll = getMedia(pollUrl(id), token)
            if (poll.first !in 200..299) throw mapHttpError(poll.first, poll.second)
            val json = try {
                JSONObject(poll.second)
            } catch (_: Exception) {
                throw LLMError.ProviderError("$label poll: not JSON")
            }
            val st = VendorMedia.taskStatus(json)
            if (VendorMedia.isFailedStatus(st)) {
                throw LLMError.ProviderError(VendorMedia.errorMessage(json).ifBlank { "$label failed" })
            }
            VendorMedia.extractHttpVideoUrl(json)?.let { return videoFromUrl(it) }
            if (VendorMedia.isSuccessStatus(st)) {
                throw LLMError.ProviderError("$label completed but no video_url")
            }
        }
        throw LLMError.ProviderError("$label generation timed out")
    }

    private fun imageResponseFromVendorJson(json: JSONObject): LLMResponse {
        val b64s = VendorMedia.minimaxImageBase64(json)
        if (b64s.isNotEmpty()) {
            val attachments = b64s.mapNotNull { b64 ->
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
                if (bytes == null || bytes.isEmpty()) null
                else LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, detectImageMime(bytes), bytes)
            }
            if (attachments.isNotEmpty()) return LLMResponse("", "end_turn", null, attachments)
        }
        val urls = VendorMedia.extractHttpImageUrls(json)
        if (urls.isEmpty()) return LLMResponse("", "end_turn", null, emptyList())
        val attachments = urls.mapNotNull { url ->
            try {
                val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                val mime = resp.header("Content-Type")
                val raw = resp.body?.bytes()
                resp.close()
                if (raw == null || raw.isEmpty()) null
                else LLMMediaAttachment(
                    LLMMediaAttachment.MediaType.IMAGE,
                    mime?.substringBefore(';') ?: detectImageMime(raw),
                    raw,
                )
            } catch (_: Exception) {
                null
            }
        }
        return LLMResponse("", "end_turn", null, attachments)
    }

    private suspend fun videoFromUrl(url: String): LLMResponse {
        val att = downloadUrl(url)
            ?: throw LLMError.ProviderError("Failed to download video from temporary URL")
        return LLMResponse("", "end_turn", null, listOf(att))
    }

    private fun vendorPollDelay(attempt: Int, minMillis: Long): Long {
        val configured = if (attempt == 0) videoFirstPollMillis else videoPollMillis
        // Tests set poll to 1ms; never inflate those. Production Ark asks ≥8s.
        if (configured < 100L) return configured
        return maxOf(configured, minMillis)
    }

    private fun requireHostPath(path: String, label: String): String =
        hostRootURL(path) ?: throw LLMError.ProviderError("Cannot resolve $label endpoint from $basePath")

    private suspend fun postMediaJson(
        url: String,
        token: String,
        body: JSONObject,
        extra: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
            .apply {
                for ((k, v) in extraHeaders) header(k, v)
                for ((k, v) in extra) header(k, v)
            }
            .applyUserAgentOverride(customUserAgent)
            .build()
        val resp = client.newCall(req).executeCancellable()
        val code = resp.code
        val text = resp.body?.string() ?: ""
        resp.close()
        return code to text
    }

    private suspend fun getMedia(url: String, token: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .get()
            .applyKeyAuth(token)
            .apply {
                for ((k, v) in extraHeaders) header(k, v)
            }
            .applyUserAgentOverride(customUserAgent)
            .build()
        val resp = client.newCall(req).executeCancellable()
        val code = resp.code
        val text = resp.body?.string() ?: ""
        resp.close()
        return code to text
    }

    private val requestBodies by lazy { OpenAIRequestBodies(OpenAIRequestHost()) }

    private inner class OpenAIRequestHost : OpenAIRequestBodies.Host {
        override val model get() = this@OpenAIProvider.model
        override val basePath get() = this@OpenAIProvider.basePath
        override val extraHeaders get() = this@OpenAIProvider.extraHeaders
        override val codexAccountId get() = this@OpenAIProvider.codexAccountId
        override val useResponsesAPI get() = this@OpenAIProvider.useResponsesAPI
        override val forceChatCompletions get() = this@OpenAIProvider.forceChatCompletions
        override val customUserAgent get() = this@OpenAIProvider.customUserAgent
        override val isAzure get() = this@OpenAIProvider.isAzure
        override val isOAuth get() = this@OpenAIProvider.isOAuth
        override val chatExtraBody get() = this@OpenAIProvider.chatExtraBody
        override val chatExtraHeaders get() = this@OpenAIProvider.chatExtraHeaders
        override val absoluteEndpointOverride get() = this@OpenAIProvider.absoluteEndpointOverride
        override val isOpenRouter get() = this@OpenAIProvider.isOpenRouter
        override val needsOpenRouterAnthropicCacheControl get() =
            this@OpenAIProvider.needsOpenRouterAnthropicCacheControl
        override val isMistral get() = this@OpenAIProvider.isMistral
        override val isDashScope get() = this@OpenAIProvider.isDashScope
        override val isXAI get() = this@OpenAIProvider.isXAI
        override val usesUnifiedReasoningEffort get() = this@OpenAIProvider.usesUnifiedReasoningEffort
        override val thinkingRuleInstanceId get() = this@OpenAIProvider.thinkingRuleInstanceId
        override fun resolvedServiceTier() = this@OpenAIProvider.resolvedServiceTier()
        override fun endpointURL(defaultPath: String) = this@OpenAIProvider.endpointURL(defaultPath)
        override fun azureUrl(path: String) = this@OpenAIProvider.azureUrl(path)
        override suspend fun getToken() = this@OpenAIProvider.getToken()
        override fun explicitOffEffort() = this@OpenAIProvider.explicitOffEffort()
        override fun applyKeyAuth(builder: Request.Builder, token: String) = builder.applyKeyAuth(token)
        override val codexClientVersion get() = CODEX_CLIENT_VERSION
        override fun clampThinkingLevel(level: ThinkingLevel) = this@OpenAIProvider.clampThinkingLevel(level)
        override val provider get() = this@OpenAIProvider
    }

    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = requestBodies.buildRequestBody(
        messages, systemPrompt, maxTokens, stream, temperature, imageParts, tools, thinkingLevel,
    )

    internal suspend fun buildRequest(bodyStr: String): Request =
        requestBodies.buildRequest(bodyStr)

    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        temperature: Double? = null,
    ): JSONObject = requestBodies.buildResponsesAPIBody(
        messages, systemPrompt, maxTokens, stream, imageParts, tools, thinkingLevel, temperature,
    )




    /**
     * Inject provider-specific thinking parameters into the request body.
     * - OpenRouter: `reasoning: {effort: ...}` (omitted when off so
     *   forced-reasoning models keep their default)
     * - OpenAI o-series / GPT-5.x: `reasoning_effort: ...` (off → skip)
     * - Qwen3 (DashScope): `enable_thinking: true/false, thinking_budget: N`
     *   — Qwen3 thinks by default, so OFF needs an explicit disable.
     * - DeepSeek V4 (deepseek-v4-flash / deepseek-v4-pro): `thinking` object —
     *   V4 thinks by default and rejects requests without an explicit toggle
     *   when reasoning_content is missing. Distinct from deepseek-reasoner /
     *   deepseek-chat which keep the no-params path below.
     * - DeepSeek (pre-V4) / GLM / Kimi / MiniMax: no params (model decides).
     */

    /**
     * [T-reasoning-effort-data-driven] Snap an effort string onto the tiers the
     * model actually declares. Mirrors iOS
     * `OpenAIAgentProvider.clampEffort(_:to:)` — keep both in sync.
     *
     * Necessary because the catalog's effort sets are far from uniform
     * (["low","medium","high"], ["high","max"], ["high","xhigh"], …). Sending an
     * undeclared tier is the same class of failure the MiMo/Agnes xhigh clamp
     * already guards against ("Invalid reasoning_effort: xhigh" 400s).
     *
     * Nearest-tier semantics: step down to the closest declared tier at or below
     * the request; only if none exists step up to the lowest declared one.
     * Downgrading is preferred because overshooting costs money and latency the
     * user did not ask for. Null/empty values pass the string through unchanged.
     */
    internal fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }



    /**
     * Combine Responses-API call_id + item_id into a single string the agent loop
     * can carry through tool_use/tool_result blocks. The next request splits it
     * back apart so the API sees the original ids verbatim.
     */
    internal fun combineResponsesAPIIds(callId: String, fcId: String): String =
        if (fcId.isEmpty()) callId else "$callId|$fcId"



}

/**
 * [T-android-ttfb-upload-split / #188] Per-call state the streaming TTFB
 * watchdog shares with [OkHttpNetTraceListener]. Attached to the streaming
 * request as an OkHttp request tag; the listener fills it in from its
 * event callbacks (which run on OkHttp's I/O thread during `execute()`),
 * and the watchdog coroutine reads it.
 *
 * Why: the watchdog must NOT count request-body UPLOAD time against the
 * 30s time-to-first-byte budget (a 1.3MB body over a slow proxy took 24-27s,
 * leaving almost nothing for the server, so a healthy server looked like a
 * dead connection — #188). The listener signals [uploadDoneAtNanos] on
 * `requestBodyEnd`; only then does the real TTFB clock start. [connection]
 * is captured so the watchdog can evict THIS ONE physical connection on
 * timeout (never the whole pool — a sibling session's healthy connection
 * must survive).
 */
internal class CallWatchState {
    /** Set by requestBodyEnd — the moment upload finished (monotonic nanos). */
    val uploadDoneAtNanos = java.util.concurrent.atomic.AtomicLong(0L)
    /** Physical connection serving this call, captured at connectionAcquired. */
    val connection = java.util.concurrent.atomic.AtomicReference<okhttp3.Connection?>(null)
}

