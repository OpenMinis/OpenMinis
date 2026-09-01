package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
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
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
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
    private val basePath: String = "https://api.openai.com/v1",
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
         * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
         * budget: response HEADERS must arrive within this window. Does NOT
         * bound the SSE body — a flowing stream stays unlimited.
         *
         * [T-android-ttfb-upload-split / #188] This window now starts at
         * `requestBodyEnd` (upload complete), NOT at call start — a large
         * multimodal body over a slow proxy could burn the whole budget just
         * uploading, so a healthy-but-slow server looked like a dead
         * connection. See [STREAM_UPLOAD_CAP_MS] for the upload-phase bound.
         *
         * Raised 30s -> 120s (user report, TG soyo): complex agent turns,
         * locally-hosted large models, and slow relay endpoints can legitimately
         * take well over 30s to emit the first response header, and the old
         * budget cancelled those healthy requests as false timeouts. readTimeout
         * is 600s, so 120s stays comfortably inside it while still catching a
         * genuinely dead connection.
         */
        private const val STREAM_TTFB_TIMEOUT_MS = 120_000L

        /**
         * [T-android-ttfb-upload-split / #188] Overall ceiling for the UPLOAD
         * phase (call start → requestBodyEnd). Keeps the watchdog effective if
         * the body upload itself wedges (writeTimeout is 30s per write op, but a
         * trickling proxy can dribble bytes forever without tripping it). Chosen
         * generous so a legitimately large body over a slow link isn't cut off:
         * the writeTimeout(30s) already bounds a fully-stalled socket; this only
         * catches the slow-but-never-idle case. Once upload completes the tighter
         * [STREAM_TTFB_TIMEOUT_MS] takes over.
         */
        private const val STREAM_UPLOAD_CAP_MS = 120_000L

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

    private val isOAuth: Boolean get() = oauthTokenProvider != null

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
    private val usesChatCompletionsAPI: Boolean get() = forceChatCompletions || (!isOAuth && !useResponsesAPI)

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
    private val isCodexImageModel: Boolean get() = isOAuth && model.id == "gpt-image-2"

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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
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
        streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
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
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = if (isCodexImageModel) {
            // [T-gpt-image2-codex-backend-route-android] gpt-image-2 on an
            // OpenAI OAuth (Codex) instance is driven through the Codex backend
            // image_generation tool (chatgpt.com/backend-api/codex/responses),
            // NOT the public /v1/images/generations Images API — a Codex OAuth
            // token lacks the api.model.images.request scope and the Images API
            // 401s with "Missing scopes: api.model.images.request" (see
            // codex_oauth_image_generation_summary.md §11.1). Aligns with iOS
            // commit 2dd35a14. The Codex-backend route is selected purely by
            // `isCodexImageModel` (isOAuth + model id) — it does NOT require a
            // codexAccountId (the Chatgpt-Account-Id header is optional and its
            // absence doesn't 401), which is exactly the iOS fall-through bug
            // this avoids. Android has no Images-API path at all, so an OAuth
            // gpt-image-2 request can never reach /v1/images/generations.
            //
            // Special image-generation body — not Chat Completions, not the
            // normal Responses tool shape.
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] gpt-image-2 → route=codex-backend " +
                    "url=chatgpt.com/backend-api/codex/responses isOAuth=$isOAuth " +
                    "hasAccountId=${codexAccountId != null}",
            )
            buildCodexImageBody(messages)
        } else if (usesChatCompletionsAPI) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        }
        // T302: serialize the request body exactly once. Pre-T302 we called
        // body.toString() three times per request (debug log + OAuth byte
        // build + non-OAuth RequestBody), each materialising a fresh 30+ MB
        // string for long agent loops with heavy tool outputs. Stacked, that
        // pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
        // [T-android-mem-probe-trust] Bracket the serialisation itself. The
        // 2026-08-15 field log recorded `bodyLen=2342987` on the request before
        // a process death but nothing about its memory cost, so "did building
        // this body kill us?" could not be answered from the log. We measure
        // around the call and report the realised length, so an OOM thrown here
        // now arrives with an attributed stack instead of anonymously.
        val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
        val serStartNs = System.nanoTime()
        val bodyStr = try {
            body.toString()
        } catch (t: Throwable) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", -1, "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = t,
            )
            throw t
        }
        if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", bodyStr.length.toLong(),
                "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = null,
            )
        }
        val request = buildRequest(bodyStr)
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        // T321: request-side diagnostic log. Header *keys* + Authorization
        // presence (no token values), and a body summary (counts only — never
        // the message text/images/tool-result bytes).
        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        // [T-android-ttfb-upload-split / #188] Attach per-call state the trace
        // listener fills in (upload-done timestamp + physical connection) so the
        // watchdog below can (a) start the TTFB clock only after upload and
        // (b) evict THIS ONE connection on timeout.
        val watchState = CallWatchState()
        val call = client.newCall(request.newBuilder().tag(CallWatchState::class.java, watchState).build())
        // [T-android-stale-conn-retry-hang] Time-to-first-byte watchdog. A
        // request written into a dead pooled h2 tunnel (local proxy socket
        // survives a network flap) produces NO further events — no headers,
        // no failure — until the 600s read timeout, so the UI showed
        // "thinking" forever.
        //
        // [T-android-ttfb-upload-split / #188] The window is split in two so
        // slow-but-healthy uploads aren't mistaken for a dead connection:
        //   1. UPLOAD phase (call start → requestBodyEnd): bounded loosely by
        //      STREAM_UPLOAD_CAP_MS. writeTimeout(30s) already catches a fully
        //      stalled socket; this only catches slow-trickle-forever.
        //   2. TTFB phase (requestBodyEnd → response headers): the tight
        //      STREAM_TTFB_TIMEOUT_MS budget, measured FROM upload completion.
        // On timeout we cancel the call AND evict just this connection so the
        // auto-retry gets a fresh one (a sibling session's connection is never
        // touched — no evictAll). Once headers arrive the watchdog stops and a
        // flowing SSE stream has NO total-duration limit, as before.
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val callStartNanos = System.nanoTime()
        val ttfbWatchdog = launch {
            val pollMs = 250L
            var timedOutPhase: String? = null
            while (!headersArrived.get()) {
                val uploadDoneAt = watchState.uploadDoneAtNanos.get()
                val nowNanos = System.nanoTime()
                if (uploadDoneAt == 0L) {
                    // Still uploading (or connecting) — loose upload-phase cap.
                    val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
                    if (elapsedMs >= STREAM_UPLOAD_CAP_MS) { timedOutPhase = "upload"; break }
                } else {
                    // Upload finished — tight TTFB budget measured from that point.
                    val sinceUploadMs = (nowNanos - uploadDoneAt) / 1_000_000L
                    if (sinceUploadMs >= STREAM_TTFB_TIMEOUT_MS) { timedOutPhase = "ttfb"; break }
                }
                delay(pollMs)
            }
            if (timedOutPhase != null && !headersArrived.get()) {
                ttfbTimedOut.set(true)
                val budgetS = if (timedOutPhase == "ttfb") STREAM_TTFB_TIMEOUT_MS / 1000 else STREAM_UPLOAD_CAP_MS / 1000
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-ttfb-upload-split] no response headers ($timedOutPhase phase, ${budgetS}s) — cancelling call + evicting connection (stale pooled connection?)",
                )
                call.cancel()
                // Targeted eviction: close ONLY this call's physical connection so
                // the retry can't reuse it. Never evictAll — concurrent sessions
                // may hold healthy connections in the shared pool.
                try {
                    watchState.connection.get()?.socket()?.close()
                } catch (_: Throwable) {
                    // Best-effort; call.cancel() already unblocks execute().
                }
            }
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${STREAM_TTFB_TIMEOUT_MS / 1000}s TTFB) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            ttfbWatchdog.cancel()
        }
        // T321: response-side diagnostic log (status + select header values).
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body — debug-only, but kept unconditional here
            // since non-2xx is rare and the body is critical for diagnosis.
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(response.code, errorBody)
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        // [T-codex-gpt-image2-oauth-android] gpt-image-2: the Codex backend
        // streams the image as a base64 blob (PNG / JPEG / WebP) inside the SSE
        // `image_generation_call` output item; there's no incremental text/tool
        // stream to parse. handleCodexImageStream parses the SSE line-by-line,
        // pulls the image (or a structured failure), emits a MediaAttachment
        // chunk, and finishes — bypassing the chat/tool SSE state machine below.
        if (isCodexImageModel) {
            try {
                handleCodexImageStream(reader) { chunk -> trySend(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel("Image stream error", mapError(e))
            } finally {
                reader.close()
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        // Chat Completions: tool calls are streamed as deltas keyed by index.
        data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        // Responses API: function_call items are keyed by their item_id (fc_…). We store
        // the call_id separately because the agent loop needs the call_id to correlate
        // tool results, but the next request must echo back the item_id verbatim — so we
        // emit a combined "callId|fcId" identifier that splitResponsesAPIIds() unpacks.
        data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
        val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
        // One-shot info log the first time the Responses API streams a reasoning
        // delta — useful for confirming the Thinking pipeline is wired up when
        // diagnosing "I set thinking high but see nothing" reports.
        var sawReasoningDelta = false
        // Accumulates the opaque reasoning_content blob across SSE deltas so we
        // can echo the exact server-emitted value back on the next turn. Tracked
        // separately from ThinkingDelta concatenation because DeepSeek V4 emits
        // `reasoning_content: ""` legitimately (non-thinking turns) and the
        // empty string must round-trip — fabricating placeholder text causes
        // the model to in-context-learn it (see T249 / T257 history).
        val reasoningAccum = StringBuilder()
        var sawReasoningField = false
        // [T-android-think-prefix-stream] One parser per streaming turn. Handles
        // ALL models: a turn with no `<think>` prefix passes through verbatim, so
        // there is no vendor allowlist to keep in sync (the old `hasThinkTags`
        // heuristic only armed extraction for DashScope/qwen ids, which meant
        // MiniMax M3 relied on the mid-stream `text.contains("<think>")` fallback).
        val thinkParser = ThinkPrefixStreamParser()

        // T321: turn-level SSE counters for empty-response triage.
        var sseEventCount = 0
        var contentLen = 0
        // [T-android-incomplete-keep-partial] True once a text delta carried at
        // least one non-whitespace character. See the response.incomplete handler.
        var sawNonBlankText = false
        var reasoningLen = 0
        var toolCallEventCount = 0
        var sawFinishReason = false
        var sawUsageBlock = false
        // [T-android-responses-missing-finished] Hoisted out of the try block so
        // the stream tail can emit Finished for streams that end WITHOUT a
        // `data: [DONE]` sentinel — see the emission site after the read loop.
        var finishReason: String? = null
        // True once the [DONE] branch has emitted Finished, so the tail below
        // does not send a second one.
        var sentFinished = false

        try {
            send(LLMStreamChunk.Started)
            var line: String?

            // Branch streaming parser based on API format
            val isResponsesAPI = !usesChatCompletionsAPI

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // Tolerate `data:` with or without the optional space — the
                // HTML5 SSE spec only treats one leading space as ignorable,
                // and some OpenAI-compatible servers (e.g. China Telecom's
                // eaichat.ctyun.cn deepseek-v4-oc endpoint) emit `data:{...}`
                // with no space. Strict `data: ` matching dropped every
                // chunk on those providers, surfacing as empty-stream errors.
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.removePrefix(" ") else it
                }
                if (payload == "[DONE]") {
                    // [T-android-think-prefix-stream] Flush whatever the parser
                    // still holds (a cross-chunk tag tail, or an unterminated
                    // <think>). Idempotent, so the finish_reason path may also
                    // call it. Withheld trailing whitespace is dropped by design.
                    thinkParser.finishTurn().let { fin ->
                        if (fin.thinking.isNotEmpty()) {
                            reasoningAccum.append(fin.thinking)
                            send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                        }
                        if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                    }
                    // [T-android-think-prefix-stream] Persist reasoning captured
                    // EITHER from the `reasoning_content` field or from a
                    // `<think>` prefix. Gating solely on sawReasoningField would
                    // stream think-tag reasoning live and then drop it — the
                    // thinking bubble would vanish on session reload.
                    if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                    }
                    send(LLMStreamChunk.Finished(finishReason))
                    sentFinished = true
                    break
                }

                val event = try { JSONObject(payload) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${payload.take(300)}"
                    )
                    continue
                }
                android.util.Log.d("ToolChain[Provider]", "RAW SSE: $payload")
                sseEventCount++

                // T321: per-event delta-field summary. Only counts/lengths,
                // never the actual delta text — keeps log volume bounded.
                run {
                    val ev = event
                    val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val type = ev.optString("type", "")
                    if (delta != null) {
                        val cLen = delta.optString("content", "").length
                        val rcLen = delta.optString("reasoning_content", "").length
                        val rLen = delta.optString("reasoning", "").length
                        val tcLen = delta.optJSONArray("tool_calls")?.length() ?: 0
                        val role = delta.optString("role", "")
                        if (cLen + rcLen + rLen + tcLen > 0 || delta.has("role")) {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            )
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                        if (tcLen > 0) toolCallEventCount += tcLen
                    } else if (type.isNotEmpty()) {
                        // Responses API event-typed diagnostics
                        val dLen = ev.optString("delta", "").length
                        if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE responses type=$type deltaLen=$dLen"
                            )
                        }
                        if (type == "response.output_text.delta") {
                            contentLen += dLen
                            // [T-android-incomplete-keep-partial] contentLen alone
                            // can't distinguish " " from real text, and the
                            // response.incomplete handler needs that difference to
                            // decide between keeping a truncated answer and failing
                            // the turn. Track it here, where the delta text is in
                            // hand, rather than buffering the whole response.
                            if (!sawNonBlankText && ev.optString("delta", "").isNotBlank()) {
                                sawNonBlankText = true
                            }
                        }
                        if (type.startsWith("response.reasoning_")) reasoningLen += dLen
                    }
                }

                if (isResponsesAPI) {
                    // Responses API SSE parsing
                    val type = event.optString("type", "")
                    when {
                        // Reasoning text deltas — both event variants the API emits.
                        // For Codex OAuth the actual content is encrypted (echoed via
                        // include=reasoning.encrypted_content), so the .delta value
                        // is typically empty; for non-Codex Responses (forceResponsesAPI
                        // or custom base) it streams plaintext we can render.
                        // Mirrors iOS OpenAIAgentProvider.swift:374-382.
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                if (!sawReasoningDelta) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content"
                                    )
                                    sawReasoningDelta = true
                                }
                                send(LLMStreamChunk.ThinkingDelta(delta))
                            }
                        }
                        type == "response.output_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) send(LLMStreamChunk.Text(delta))
                        }
                        // function_call item announced — capture call_id + name, start accumulator.
                        type == "response.output_item.added" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val callId = item.optString("call_id", "")
                                val name = item.optString("name", "")
                                if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                                    responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                                    val combined = combineResponsesAPIIds(callId, itemId)
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                                    send(LLMStreamChunk.ToolUseStart(combined, name))
                                    responsesToolCalls[itemId]?.started = true
                                }
                            }
                        }
                        type == "response.function_call_arguments.delta" -> {
                            val itemId = event.optString("item_id", "")
                            val delta = event.optString("delta", "")
                            val acc = responsesToolCalls[itemId]
                            if (acc != null && delta.isNotEmpty()) {
                                acc.args.append(delta)
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                send(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                            } else if (acc == null) {
                                // Pre-T107 this branch silently dropped the entire tool call
                                // because no accumulator was set up — leaving the model with
                                // no real tool channel and provoking <tool_call>{...} text
                                // hallucinations. Keep a warn so any future regression here
                                // surfaces in the daily log instead of a silent failure.
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
                                )
                            }
                        }
                        // The accumulator is finalized at response.output_item.done, when the
                        // arguments stream has flushed. The completed item carries `arguments`
                        // as a JSON string — we prefer that authoritative value over our own
                        // streamed buffer in case the API ever emits a corrected payload.
                        type == "response.output_item.done" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val acc = responsesToolCalls.remove(itemId) ?: continue
                                val argsStr = item.optString("arguments", acc.args.toString())
                                val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
                                send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                            }
                        }
                        type == "response.failed" -> {
                            // [T-responses-terminal-events] Explicit terminal
                            // handling instead of the generic fallthrough: pull
                            // the structured error off the response object so
                            // the thrown LLMError carries the real reason (and
                            // so retry/fallback classification can act on it).
                            // Official shape: response.status == "failed",
                            // response.error = {code, message}. Mirrors iOS 637cd890.
                            val resp = event.optJSONObject("response")
                            val err = resp?.optJSONObject("error")
                            val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                            val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                                ?: "response.failed with no error detail"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            if (code == "server_error" || code == "rate_limit_exceeded") {
                                // Transient family: retry on the same model
                                // rather than falling back through the group.
                                throw LLMError.TransientError("[$code] $message")
                            }
                            throw LLMError.ProviderError("[$code] $message")
                        }
                        type == "response.incomplete" -> {
                            // [T-responses-terminal-events] The server ended the
                            // response early; incomplete_details.reason is
                            // "max_output_tokens" or "content_filter".
                            val reason = event.optJSONObject("response")
                                ?.optJSONObject("incomplete_details")
                                ?.optString("reason")?.takeIf { it.isNotEmpty() }
                                ?: "unknown"

                            // [T-android-incomplete-keep-partial] Only fail the
                            // turn when there is genuinely nothing to show.
                            //
                            // The old code threw unconditionally, and the comment
                            // above it ("Partial output has already been streamed
                            // — surface WHY") described an intent the code did not
                            // implement: throwing here discards the streamed text,
                            // so a truncated-but-useful answer was reported to the
                            // user as a hard failure with nothing rendered.
                            //
                            // Reported against a Responses-format relay proxying
                            // Claude: the model spent its whole budget in the
                            // reasoning phase and emitted one space of visible
                            // text, then `response.incomplete
                            // reason=max_output_tokens` with
                            // `usage.output_tokens=0`. Raising the setting could
                            // not help (the budget went to reasoning, and the
                            // relay reports output_tokens=0 regardless), so every
                            // retry failed the same way and the turn was lost.
                            //
                            // Truncation is a normal terminal condition, not an
                            // error: Chat Completions already models it as
                            // `finish_reason=length` and ends the stream
                            // normally. Treating the Responses spelling the same
                            // way keeps the two API flavours consistent and lets
                            // the agent loop persist what did arrive.
                            //
                            // `contentLen` counts text deltas actually forwarded
                            // downstream, so it is the honest test for "does the
                            // user have something to read". Whitespace-only output
                            // (the reported case) counts as nothing, since a bubble
                            // containing one space is indistinguishable from a bug.
                            val hasUsableOutput = sawNonBlankText
                            if (hasUsableOutput) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API response.incomplete — reason=$reason; " +
                                        "keeping ${contentLen}ch of partial output (finish_reason=length)"
                                )
                                // Same terminal shape Chat Completions uses for a
                                // budget-truncated answer, so downstream code needs
                                // no new branch: the loop stops, the text persists,
                                // and the UI can mark it truncated.
                                finishReason = "length"
                                sawFinishReason = true
                                break
                            }

                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.incomplete — reason=$reason (no usable output)"
                            )
                            throw LLMError.ProviderError(
                                "Response ended incomplete (reason: $reason)" +
                                    if (reason == "max_output_tokens") {
                                        // The old text told the user to raise Max
                                        // Output Tokens. When reasoning consumed
                                        // the budget that advice is actively
                                        // misleading — this user tried 128k, 32k
                                        // and 16k, all identical — so name the
                                        // real lever too.
                                        " — the model used its entire output budget before producing a reply" +
                                            " (often the thinking phase on a reasoning model). Try turning off or" +
                                            " lowering Deep Thinking, shortening the request, or raising the model's" +
                                            " Max Output Tokens."
                                    } else ""
                            )
                        }
                        type == "response.completed" -> {
                            val resp = event.optJSONObject("response")
                            val status = resp?.optString("status", "")
                            // When the model emitted tool calls the API returns status=completed
                            // with no stop_reason; surface "tool_use" so the agent loop knows to
                            // dispatch the calls instead of treating the turn as final.
                            val sawToolCalls = responsesToolCalls.isNotEmpty() ||
                                (resp?.optJSONArray("output")?.let { out ->
                                    var found = false
                                    for (i in 0 until out.length()) {
                                        if (out.optJSONObject(i)?.optString("type") == "function_call") { found = true; break }
                                    }
                                    found
                                } ?: false)
                            finishReason = when {
                                sawToolCalls -> "tool_use"
                                status == "completed" -> "stop"
                                else -> status
                            }
                            if (!sawFinishReason) {
                                sawFinishReason = true
                                // [T-codex-fast-mode] The response object inside
                                // response.completed echoes the EFFECTIVE
                                // service_tier — "priority" here is definitive
                                // proof Fast Mode was honored; "default"/absent
                                // means requested-but-downgraded (OpenAI silently
                                // downgrades ineligible accounts). Mirrors iOS
                                // 63a71146.
                                val serviceTier = resp?.optString("service_tier", "")
                                    ?.takeIf { it.isNotEmpty() } ?: "n/a"
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
                                )
                            }
                            resp?.optJSONObject("usage")?.let { usage ->
                                sawUsageBlock = true
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses usage block: $usage"
                                )
                                send(LLMStreamChunk.Usage(parseResponsesAPIUsage(usage)))
                            }
                        }
                        type == "response.output_text.done" -> {
                            // Text output complete, no action needed
                        }
                    }
                } else {
                    // Chat Completions API SSE parsing
                    // Check for inline error (OpenRouter sends error inside SSE with empty choices)
                    val inlineError = event.optJSONObject("error")
                    if (inlineError != null) {
                        val code = inlineError.optInt("code", 0)
                        val msg = inlineError.optString("message", "Unknown SSE error")
                        val err = mapHttpError(code, event.toString())
                        throw err
                    }
                    val choices = event.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        // Reasoning / thinking content (DeepSeek, Kimi, etc.)
                        delta?.let { d ->
                            // Track presence of either field — even an empty string
                            // counts so we can round-trip DeepSeek V4's `reasoning_content: ""`.
                            val hasRcKey = d.has("reasoning_content")
                            val hasReasoningKey = d.has("reasoning")
                            if (hasRcKey || hasReasoningKey) {
                                sawReasoningField = true
                            }
                            val rc = d.safeOptString("reasoning_content", "")
                                .ifEmpty { d.safeOptString("reasoning", "") }
                            if (rc.isNotEmpty()) {
                                reasoningAccum.append(rc)
                                if (!sawReasoningDelta) {
                                    sawReasoningDelta = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Chat Completions: first reasoning_content delta arrived on ${model.id} — streaming Thinking content"
                                    )
                                }
                                send(LLMStreamChunk.ThinkingDelta(rc))
                            }
                        }

                        // [T-android-think-prefix-stream] Text content. Models that
                        // embed reasoning as a `<think>…</think>` PREFIX of
                        // `content` (MiniMax M3, some Qwen/DeepSeek deployments)
                        // are split by ThinkPrefixStreamParser, which replaced the
                        // old extractThinkTags scanner. That scanner searched for
                        // `<think>` at ANY offset, so a reply merely explaining the
                        // tag had its prose swallowed into the thinking bubble, and
                        // it passed M3's post-`</think>` "\n\n" straight through so
                        // every such body began with a blank line.
                        delta?.safeOptString("content", "")?.let { text ->
                            if (text.isNotEmpty()) {
                                val out = thinkParser.feed(text)
                                if (out.thinking.isNotEmpty()) {
                                    reasoningAccum.append(out.thinking)
                                    send(LLMStreamChunk.ThinkingDelta(out.thinking))
                                }
                                if (out.visible.isNotEmpty()) send(LLMStreamChunk.Text(out.visible))
                            }
                        }

                        // Tool calls (parallel: keyed by index)
                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                                tc.safeOptString("id", "").let { if (it.isNotEmpty()) acc.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                                    fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                                }

                                // Emit start exactly once per tool call
                                if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                                    acc.started = true
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                                    send(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                                }
                                // Emit input delta
                                if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${acc.args.length}chars")
                                    send(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                                }
                            }
                        }

                        // Finish reason
                        choice.safeOptString("finish_reason", "").let {
                            if (it.isNotEmpty()) {
                                finishReason = it
                                if (!sawFinishReason) {
                                    sawFinishReason = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}"
                                    )
                                }
                            }
                        }
                    }

                    event.optJSONObject("usage")?.let { usage ->
                        sawUsageBlock = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "[T321] usage block: $usage"
                        )
                        send(LLMStreamChunk.Usage(parseChatCompletionsUsage(usage)))
                    }
                }
            }

            // [T-android-think-prefix-stream] Stream-end flush, for streams that
            // end without a `[DONE]` sentinel. finishTurn() is idempotent, so
            // running after the [DONE] path already flushed is a no-op.
            thinkParser.finishTurn().let { fin ->
                if (fin.thinking.isNotEmpty()) {
                    reasoningAccum.append(fin.thinking)
                    send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                }
                if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
            }

            // Emit ToolCallComplete for all accumulated tool calls
            for ((_, acc) in toolCallAccumulators) {
                if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                    send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
                }
            }
            // Drain Responses-API tool accumulators that didn't get an output_item.done
            // before the stream closed. Without this, mid-tool-call truncation (server
            // closes connection while function_call_arguments is still streaming) leaves
            // ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
            // and the UI hangs with the tool thumbnail spinning while the stop button
            // disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
            for ((itemId, acc) in responsesToolCalls) {
                if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                    )
                    send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                }
            }
            responsesToolCalls.clear()

            // T321: stream ended — final tally + warning if we never saw a
            // finish_reason. The latter is the strongest signal of a server-
            // side truncation / connection-dropped scenario.
            // [T-android-responses-missing-finished] Emit the terminal chunk for
            // streams that end without a `data: [DONE]` sentinel.
            //
            // Finished was only ever sent from the [DONE] branch. Chat
            // Completions always sends that sentinel, but the Responses API
            // terminates with `response.completed` and many relays simply close
            // the socket afterwards — no [DONE] ever arrives. The read loop then
            // exits normally, the channel closes, and no Finished is emitted.
            //
            // Downstream, ChatViewModel.runAgentLoop only ever assigns
            // turnFinishReason from a Finished chunk, so it stayed null and the
            // turn was reported as "stream closed without a finish reason" —
            // the red "连接中断，此回复可能不完整" banner on a reply that was in
            // fact complete. It looked intermittent because it depends on the
            // relay: those that do append [DONE] worked, the rest did not, which
            // is why the same model on the same account failed only sometimes,
            // and why Chat Completions models (deepseek) never showed it.
            //
            // Gated on sawFinishReason: reaching here WITHOUT one is a genuine
            // truncation, and must keep falling through to the warning below so
            // the interrupted-reply UI still fires for real drops.
            if (sawFinishReason && !sentFinished) {
                // Mirror the [DONE] branch's ordering: flush the think-tag
                // parser and reasoning blob before the terminal chunk, or a
                // trailing <think> tail would be dropped and reasoning would
                // vanish on reload.
                thinkParser.finishTurn().let { fin ->
                    if (fin.thinking.isNotEmpty()) {
                        reasoningAccum.append(fin.thinking)
                        send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                    }
                    if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                }
                if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                    send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                }
                send(LLMStreamChunk.Finished(finishReason))
                sentFinished = true
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream ended without [DONE] — emitted Finished(finishReason=$finishReason) from tail"
                )
            }

            if (!sawFinishReason) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=${model.id}"
                )
            } else {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock"
                )
            }
        } catch (e: Exception) {
            // T321: never silently swallow — log message + top-3 stack frames.
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
            )
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        // T171: when the coroutine is cancelled (user tapped stop), the
        // reader loop above is suspended inside the OkHttp source — only
        // call.cancel() will tear the socket down promptly. response.close()
        // is also explicit so connection-pool leaks are impossible if cancel
        // races with the finally block.
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
    }

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
        bodyObject: JSONObject?,
    ): RawPassthroughResult = withContext(Dispatchers.IO) {
        val url: String = when {
            endpoint != null && endpoint.startsWith("/") ->
                hostRootURL(endpoint)
                    ?: throw LLMError.ProviderError("Invalid passthrough endpoint: $endpoint")
            endpoint != null -> "$basePath/${endpoint.trimStart('/')}"
            else -> endpointURL("/chat/completions")
        }

        val verb = method.uppercase()
        val builder = Request.Builder().url(url)
        if (verb != "GET" && bodyObject != null) {
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyObject.toString().toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            builder.method(verb, requestBody)
        } else {
            builder.method(verb, null)
        }
        val token = getToken()
        builder.applyKeyAuth(token)
        builder.header("Content-Type", "application/json")
        // ctor extraHeaders, then user headers LAST — replace semantics.
        for ((k, v) in extraHeaders) builder.header(k, v)
        for ((k, v) in headers) builder.header(k, v)

        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] route=raw-passthrough method=$verb url=$url " +
                "bodyKeys=[${bodyObject?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}] " +
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
    suspend fun generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val token = getToken()
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
     * Parse the `/images/generations` response into an [LLMResponse] carrying
     * the decoded image bytes as [LLMMediaAttachment]s. Supports `b64_json`
     * (inline) and `url` (downloaded) item shapes. Mirrors iOS
     * parseImageGenerationsResult. When the body has no `data` array but DOES
     * carry `choices`, a proxy silently rerouted us to chat completions — throw
     * a route-missing error so auto-mode falls back instead of caching the
     * wrong endpoint.
     */
    private fun parseImageGenerationsResult(json: JSONObject): LLMResponse {
        val dataArray = json.optJSONArray("data")
        if (dataArray == null) {
            if (json.has("choices")) {
                throw LLMError.ProviderError(
                    "[404] /images/generations not supported (got chat completions response)",
                )
            }
            return LLMResponse("", "end_turn", null, emptyList())
        }

        val attachments = mutableListOf<LLMMediaAttachment>()
        val revisedPrompts = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val hintMime = item.safeOptString("mime_type", "").ifEmpty { null } // xAI extension
            val b64 = item.safeOptString("b64_json", "")
            if (b64.isNotEmpty()) {
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[ModelUseRoute] images/generations b64 decode failed: ${e.message}",
                    )
                    continue
                }
                val mime = hintMime ?: detectImageMime(bytes)
                attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, bytes))
            } else {
                val urlStr = item.safeOptString("url", "")
                if (urlStr.isNotEmpty()) {
                    try {
                        val dlReq = Request.Builder().url(urlStr).get().build()
                        val dlResp = client.newCall(dlReq).execute()
                        val dlBytes = dlResp.body?.bytes()
                        val ctMime = dlResp.header("Content-Type")
                        dlResp.close()
                        if (dlBytes != null && dlBytes.isNotEmpty()) {
                            val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                            attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                        }
                    } catch (e: Exception) {
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "[ModelUseRoute] failed to download image from $urlStr: ${e.message}",
                        )
                    }
                }
            }
            val revised = item.safeOptString("revised_prompt", "")
            if (revised.isNotEmpty()) revisedPrompts.add(revised)
        }

        val text = revisedPrompts.joinToString("\n")
        return LLMResponse(text, "end_turn", null, attachments)
    }

    // `internal` rather than private so the serialization can be asserted
    // directly in unit tests. The tool-result-image regression this guards
    // (T-android-toolresult-image-dropped) is a property of the request BODY,
    // and going through MockWebServer to read it only adds a network dependency
    // to a question that is pure JSON construction.
    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: cross-provider image sanitization, mirrors iOS
        // OpenAIAgentProvider.swift:744-768 / 900-918. When the target model
        // doesn't declare "image" in inputModalities (e.g. DeepSeek V4 after
        // user sent image to GPT-5.5 then switched provider), serialize a
        // text placeholder instead of an image_url block — otherwise the
        // server returns "400 unknown variant `image_url`". Decided once
        // here so the structured-contentParts loop and the legacy
        // imageParts loop below stay consistent.
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        if (isOpenRouter) {
            body.put("max_tokens", maxTokens)
        } else {
            body.put("max_completion_tokens", maxTokens)
        }
        body.put("stream", stream)

        // [T-android-xai-priority] xAI Priority Processing, driven by the same
        // app-level Fast Mode toggle as Codex (FastModePrefs), read here at
        // request-build time so a flip applies to the very next request.
        // Emitted only for xAI-capable providers, so every other provider's
        // body is unchanged — `service_tier` is an xAI extension and a strict
        // OpenAI-compatible relay would 400 on the unknown key.
        resolvedServiceTier()?.let { body.put("service_tier", it) }

        if (temperature != null) {
            body.put("temperature", temperature)
        }

        if (stream && !isOpenRouter) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        // Provider-specific thinking params. We always call this — some
        // models (e.g. DeepSeek V4) reason by default and need an explicit
        // `disabled` signal when the user toggles thinking off.
        //
        // [T-android-mistral-reasoning-422] …EXCEPT on Mistral, which rejects
        // the thinking request parameters outright with
        // `422 extra_forbidden body.reasoning`. Mirrors iOS
        // OpenAIAgentProvider.swift's `if !provider.isMistral` gate around this
        // same call (4592ca9b). Until now [isMistral] only suppressed the
        // message-level echo ([forbidReasoningField], 0839f019 / GH
        // OpenMinis#87) — the request-parameter half of that fix was never
        // ported, so an enabled thinking level still put `reasoning_effort` on
        // the wire to api.mistral.ai.
        if (!isMistral) {
            injectThinkingParams(body, thinkingLevel, maxTokens)
        }

        // [OpenMinis#191] Opt this request into Anthropic prompt caching.
        // OpenRouter passes the field through to Anthropic but never injects it
        // for us, so without it Claude requests cache nothing at all.
        //
        // Top-level "automatic" form: the breakpoint advances to the last
        // cacheable block on its own as the conversation grows, which is what an
        // agent loop wants — the per-block form would need us to hand-manage a
        // 4-breakpoint budget across a mutating history.
        //
        // Gated on host + `anthropic/` prefix, so no other model's body changes.
        if (needsOpenRouterAnthropicCacheControl) {
            body.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        // Tools
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val messagesArray = JSONArray()
        if (systemPrompt != null) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Mirror iOS OpenAIAgentProvider.flattenChatCompletionsMessages —
        // echo reasoning_content on prior assistant turns when:
        //   - user requested thinking this turn, OR the model always reasons (forced); AND
        //   - the model isn't explicitly known to reject reasoning.
        // Prevents 400s from Kimi / DeepSeek / GLM / QwQ that reject
        // multi-turn history missing reasoning_content once thinking is on.
        val modelAlwaysReasons = model.supportsReasoning == true
        val modelMayReason = model.supportsReasoning ?: true
        // [T-android-mistral-reasoning-422] Mistral forbids reasoning_content on
        // assistant messages entirely (closed schema → HTTP 422
        // extra_forbidden), so suppress BOTH the captured echo and the ""
        // placeholder for that endpoint. This cannot be driven by capability
        // metadata: MiMo/DeepSeek require the field's PRESENCE on multi-turn
        // history while Mistral forbids it, and neither advertises
        // supportsReasoning via /v1/models — opposite requirements on the same
        // generic openAI provider path. Hence a spec-driven vendor flag.
        val forbidReasoningField = isMistral
        val includeReasoning =
            (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
        val echoReasoning = includeReasoning
        // T-mimo-reasoning-echo-34671: Mimo V2.5 returns 400 Param Incorrect on
        // multi-turn tool-call history when any prior assistant turn (especially
        // a tool_calls-bearing one) omits `reasoning_content`. Mimo's docs say
        // the field MUST be present (empty string OK) whenever thinking is on
        // and a tool call is in history. We drop the previous interleaved-only
        // gate and always emit reasoning_content (possibly "") whenever the
        // echo gate (includeReasoning) is true. OpenAI o-series ignores
        // unknown message-level `reasoning_content` so this stays harmless
        // there; non-reasoning models gate this off via includeReasoning=false.
        val placeholderAllowed = includeReasoning

        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            if (msg.contentParts.isNotEmpty()) {
                // Structured content parts
                when {
                    // Assistant with tool_use → emit assistant message with tool_calls
                    msg.role == LLMMessage.Role.ASSISTANT -> {
                        val obj = JSONObject()
                        obj.put("role", "assistant")
                        if (echoReasoning) {
                            val rc = msg.reasoningContent
                            if (rc != null) {
                                // Round-trip exactly what the server emitted,
                                // including empty strings. DeepSeek V4 emits
                                // `reasoning_content: ""` on non-thinking turns
                                // and accepts the same shape on input — the empty
                                // value is the field-presence guarantee that
                                // prevents 400s once thinking is on.
                                obj.put("reasoning_content", rc)
                            } else if (placeholderAllowed) {
                                // No captured reasoning for this turn (e.g. fallback
                                // to a non-thinking model, or message persisted before
                                // thinking was enabled). Send "" rather than a synthetic
                                // marker: prior placeholders ("[no prior reasoning]",
                                // T249; single space, T257) were in-context-learned by
                                // DeepSeek V4 and echoed back as the model's own
                                // reasoning. Empty string satisfies the field-presence
                                // check with no learnable pattern.
                                obj.put("reasoning_content", "")
                            }
                        }
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            obj.put("content", textParts.joinToString("") { it.text })
                        }
                        val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        if (toolUseParts.isNotEmpty()) {
                            val toolCallsArr = JSONArray()
                            for (tu in toolUseParts) {
                                toolCallsArr.put(JSONObject().apply {
                                    put("id", capChatToolCallId(tu.id))
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tu.name)
                                        put("arguments", tu.input.toString())
                                    })
                                })
                            }
                            obj.put("tool_calls", toolCallsArr)
                        }
                        messagesArray.put(obj)
                    }
                    // User with tool_results → emit separate tool messages
                    msg.role == LLMMessage.Role.USER -> {
                        val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()

                        for (tr in toolResults) {
                            messagesArray.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", capChatToolCallId(tr.id))
                                put("content", tr.content)
                            })
                            // [T-android-toolresult-image-dropped] THE reported bug.
                            // read_image hands its pixels back on the ToolResult
                            // (ChatViewModel sets imageData on the part), but this
                            // loop only ever emitted `content`, so on a native-vision
                            // model the bytes were dropped at the provider boundary
                            // and the model answered "I can't actually see pixels" —
                            // with a green, successful-looking tool card above it.
                            //
                            // Chat Completions has no image block inside a `tool`
                            // message (the schema takes a plain string), so the
                            // pixels ride on a following USER message that references
                            // the call. Anthropic can nest the image directly in its
                            // tool_result and does (AnthropicProvider ~L600); this is
                            // the same intent expressed in the shape this API allows.
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                val safeBytes = com.openminis.app.provider.ImageBudget
                                    .compressUnderBudget(trBytes)
                                val safeMime = if (safeBytes === trBytes) {
                                    tr.imageMimeType ?: "image/jpeg"
                                } else "image/jpeg"
                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put(
                                                "text",
                                                "[Image returned by ${tr.name}]",
                                            )
                                        })
                                        put(JSONObject().apply {
                                            put("type", "image_url")
                                            put("image_url", JSONObject().apply {
                                                put("url", "data:$safeMime;base64,$b64")
                                            })
                                        })
                                    })
                                })
                            }
                        }
                        // T132: emit text + image_url parts as a structured user
                        // message. The previous structured-contentParts branch
                        // dropped AgentContentPart.ImageData entirely — only the
                        // legacy non-contentParts path knew how to encode images,
                        // and that path is unreachable once contentParts is
                        // populated (which is always now). Mirrors iOS
                        // OpenAIAgentProvider.swift L732-738.
                        val hasImages = imageParts.isNotEmpty()
                        if (hasImages || textParts.isNotEmpty()) {
                            if (hasImages) {
                                val contentArray = JSONArray()
                                // Walk contentParts in original order so the
                                // [attached image: …] text caption that
                                // precedes each ImageData part stays adjacent
                                // to the right image, matching iOS.
                                for (part in msg.contentParts) {
                                    when (part) {
                                        is AgentContentPart.Text -> {
                                            if (part.text.isNotEmpty()) {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is AgentContentPart.ImageData -> {
                                            if (supportsImages) {
                                                // T-imgsize: backstop — re-encode oversize
                                                // history image bytes before base64-inlining.
                                                val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "image_url")
                                                    put("image_url", JSONObject().apply {
                                                        put("url", "data:$safeMime;base64,$b64")
                                                    })
                                                })
                                            } else {
                                                // T264: target model has no vision modality —
                                                // emit a text placeholder in place of the pixels.
                                                // [T-android-vision-group / GH#182] Vision-Group
                                                // read_image hint when seeded (carries the path);
                                                // else the historical literal.
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.noVisionPlaceholder
                                                        ?: "[Image attached but this model does not support vision input]")
                                                })
                                            }
                                        }
                                        else -> Unit  // ToolUse/ToolResult never appear on user role here
                                    }
                                }
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                })
                            } else {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", textParts.joinToString("") { it.text })
                                })
                            }
                        }
                    }
                }
            } else {
                // Legacy: plain text messages
                val obj = JSONObject()
                obj.put("role", msg.role.value)

                if (echoReasoning && msg.role == LLMMessage.Role.ASSISTANT) {
                    val rc = msg.reasoningContent
                    if (rc != null) {
                        // Round-trip exactly what the server emitted (including "").
                        // See structured-content branch above for the full rationale.
                        obj.put("reasoning_content", rc)
                    } else if (placeholderAllowed) {
                        // No captured reasoning — empty string satisfies the field-
                        // presence check without giving DeepSeek V4 a learnable
                        // marker to imitate (T249 / T257 history).
                        obj.put("reasoning_content", "")
                    }
                }

                val attachTopLevelImages =
                    index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
                if (attachTopLevelImages || msg.audioParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    if (attachTopLevelImages) {
                        for (part in imageParts) {
                            if (supportsImages) {
                                // T-imgsize: provider-boundary backstop.
                                val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                val imageUrl = JSONObject()
                                imageUrl.put("url", "data:$safeMime;base64,$b64")
                                contentArray.put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", imageUrl)
                                })
                            } else {
                                // T264: target model has no vision modality — emit
                                // a text placeholder in place of the pixels.
                                // [T-android-vision-group / GH#182] When a Vision
                                // Group is configured, ChatViewModel seeds
                                // part.noVisionPlaceholder with a read_image call
                                // hint (carrying the image path) so the model
                                // routes the image through the group instead of
                                // being told it can't see it. Null → the historical
                                // iOS-parity literal (no Vision Group configured).
                                contentArray.put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", part.noVisionPlaceholder
                                        ?: "[Image attached but this model does not support vision input]")
                                })
                            }
                        }
                    }
                    // [GH#67] Official Chat Completions audio-input shape,
                    // forwarded verbatim (modality is gated at the call site).
                    for (audio in msg.audioParts) {
                        contentArray.put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", audio.base64Data)
                                put("format", audio.format)
                            })
                        })
                    }
                    // Preserve the pre-GH#67 image-path behavior (text part
                    // always present); for audio-only messages skip an empty
                    // text block some servers reject.
                    if (attachTopLevelImages || msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }

                messagesArray.put(obj)
            }
        }
        // [T-dedupe-toolcallid follow-up] Cross-message defense-in-depth:
        // rename any tool_call_id that collides with one already seen
        // elsewhere in this request. 9421990 covers the stream-time
        // collision; historical messages reloaded from the DB — or
        // messages produced by a different provider before the user
        // switched — bypass that pass, and DeepSeek (plus several
        // OpenAI-compat gateways) reject the assembled request with
        // "Duplicate value for tool_call_id ... in message[N]" whenever
        // any id repeats across the full messages array.
        globallyDedupeToolCallIds(messagesArray)
        body.put("messages", messagesArray)

        // [T-android-model-use-passthrough-mode GH#72] Merge user-supplied extra
        // body fields verbatim (no OpenAI→native conversion — callers own the
        // shape). User keys win over our defaults, but `model` is force-kept so a
        // stray override can't misroute. Mirrors generateImage's merge + iOS.
        mergeChatExtraBody(body)

        return body
    }

    /**
     * [T-android-model-use-passthrough-mode GH#72] Shared verbatim merge of
     * [chatExtraBody] into a request body, applied by BOTH the chat/completions
     * and responses builders so no endpoint can forget the passthrough. User
     * keys overwrite; `model` is force-restored last. Skipped for Codex OAuth
     * (its body is part of the client fingerprint and must stay untouched).
     */
    private fun mergeChatExtraBody(body: JSONObject) {
        if (chatExtraBody.isEmpty()) return
        if (isOAuth && !forceChatCompletions) return  // Codex OAuth exemption
        for ((k, v) in chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        body.put("model", model.id)
    }

    /**
     * Walk every assistant.tool_calls entry and every role:"tool"
     * tool_call_id in order, renaming any duplicate id to `{id}-{N}`.
     * The first occurrence keeps the raw id; subsequent collisions get
     * a numeric suffix starting at 2. Renames propagate to each pair's
     * matching role:"tool" reply by remembering the latest rename per
     * raw id (the reply is required to immediately follow its claiming
     * assistant tool_calls on this provider).
     */
    private fun globallyDedupeToolCallIds(messagesArray: JSONArray) {
        // raw id → max suffix already issued (0 = unused, 1 = raw kept,
        // 2+ = renamed copies).
        val seen = HashMap<String, Int>()
        // raw id → latest renamed id, so the next role:"tool" reply
        // claiming this raw id can pick up the same rewrite.
        val renameForPendingResults = HashMap<String, String>()
        var renamedCount = 0
        val n = messagesArray.length()
        for (i in 0 until n) {
            val msg = messagesArray.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            when (role) {
                "assistant" -> {
                    val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val rawId = call.optString("id", "")
                        if (rawId.isEmpty()) continue
                        val used = seen[rawId] ?: 0
                        val renamedId: String
                        if (used == 0) {
                            renamedId = rawId
                            seen[rawId] = 1
                        } else {
                            val next = used + 1
                            renamedId = "$rawId-$next"
                            seen[rawId] = next
                            renamedCount += 1
                            call.put("id", renamedId)
                        }
                        renameForPendingResults[rawId] = renamedId
                    }
                }
                "tool" -> {
                    val rawId = msg.optString("tool_call_id", "")
                    if (rawId.isEmpty()) continue
                    val renamedId = renameForPendingResults[rawId] ?: continue
                    if (renamedId != rawId) {
                        msg.put("tool_call_id", renamedId)
                    }
                }
            }
        }
        if (renamedCount > 0) {
            android.util.Log.w("OpenAIProvider", "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
        }
    }

    /**
     * T302: takes the pre-serialized body string instead of the JSONObject so
     * the caller can serialize once and reuse the result for the debug log,
     * the OAuth byte build, and the OkHttp RequestBody. Per-call peak heap
     * dropped by ~2× the body size (often tens of MB on long agent loops).
     */
    private suspend fun buildRequest(bodyStr: String): Request {
        val token = getToken()

        if (isOAuth && !forceChatCompletions) {
            // Codex OAuth — use ChatGPT backend Responses API
            // Use MediaType without charset — ChatGPT backend rejects "application/json; charset=utf-8"
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url("https://chatgpt.com/backend-api/codex/responses")
                .post(requestBody)
                .header("Authorization", "Bearer $token")
                .header("Version", CODEX_CLIENT_VERSION)
                .header("Openai-Beta", "responses=experimental")
                .header("User-Agent", "codex_cli_rs/$CODEX_CLIENT_VERSION (Android; arm64)")
                .header("Originator", "codex_cli_rs")
            codexAccountId?.let { builder.header("Chatgpt-Account-Id", it) }
            // [T-provider-custom-user-agent] Applied last so a non-blank
            // override wins over the Codex default UA. In practice null on
            // this OAuth path (the UI only exposes it for custom-base
            // instances), so this is a no-op there.
            // [T-android-default-ua] `defaultUserAgent = null` — keep the
            // codex_cli_rs fingerprint set above when no per-provider
            // override is configured. We must NOT fall back to the branded
            // Minis UA here: the ChatGPT OAuth backend validates the
            // client identity against this header.
            builder.applyUserAgentOverride(customUserAgent, defaultUserAgent = null)
            return builder.build()
        }

        val endpointPath = if (useResponsesAPI) "/responses" else "/chat/completions"
        // [T-android-azure-openai] Azure routes via the deployments path and
        // auths with the api-key header. azureUrl() returns null when not in
        // Azure mode / no base, so the standard basePath join stays the default.
        // [T-android-model-use-passthrough-mode] endpointURL() honors an
        // absolute-path override ("/...") that replaces the whole path on the
        // provider host; otherwise Azure/basePath as before.
        val requestUrl = when {
            absoluteEndpointOverride?.startsWith("/") == true -> endpointURL(endpointPath)
            isAzure -> azureUrl(endpointPath) ?: "$basePath$endpointPath"
            else -> "$basePath$endpointPath"
        }
        // T-responses-include: match iOS bare `application/json` Content-Type
        // by using a custom RequestBody (same workaround the OAuth branch
        // above already does). OkHttp's `String.toRequestBody(MediaType)`
        // helper attaches the MediaType to the body and several proxies/
        // backends end up seeing `application/json; charset=utf-8`. iOS sends
        // bare `application/json`; some third-party Responses-API proxies are
        // stricter and reject the charset suffix.
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
        val builder = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
        for ((key, value) in extraHeaders) {
            builder.header(key, value)
        }
        // [T-android-model-use-passthrough-mode] Per-call chat header overrides,
        // applied AFTER the ctor extraHeaders → same-name REPLACE over any
        // default (incl. Authorization/Content-Type). Empty on normal calls.
        for ((key, value) in chatExtraHeaders) {
            builder.header(key, value)
        }
        // [T-provider-custom-user-agent] Covers both chat/completions and
        // /responses (this builder serves both). Applied after extraHeaders
        // so the per-provider override wins. null/blank → default UA.
        builder.applyUserAgentOverride(customUserAgent)
        return builder.build()
    }

    private fun parseChatCompletionsUsage(usage: JSONObject): LLMUsage {
        val promptTokens = usage.optInt("prompt_tokens", 0)
        // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens` instead
        // of the OpenAI-native `prompt_tokens_details.cached_tokens`. Mirrors
        // iOS OpenAIProvider.swift:629-630. Without this fallback, DeepSeek V4
        // looked like it never cached even when it did, masking T122's win.
        val cacheRead = usage.optJSONObject("prompt_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
            ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
        // OpenAI/DeepSeek `prompt_tokens` is the FULL input (cached + fresh), so
        // subtract the cached portion to keep `inputTokens` meaning fresh-only —
        // matching the Anthropic convention. Otherwise the cached tokens are
        // counted twice in `input + cacheRead` (deflates the cache-hit rate;
        // DeepSeek 99% hit showed as ~48%). Guard: only subtract when it stays
        // non-negative; no cache field (cacheRead == null) → unchanged.
        // latestContextTokens stays the full prompt (that IS the context size).
        val freshInput = cacheRead?.let { (promptTokens - it).takeIf { d -> d >= 0 } } ?: promptTokens
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("completion_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = promptTokens,
        )
    }

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
     * [T-android-xhigh-effort-clamp] Clamp the reasoning-effort string for
     * model families whose backend only accepts low/medium/high and 400/422 on
     * our `xhigh` tier: MiMo-2.5/Pro and Agnes. For these, `xhigh` → `high`;
     * every other value passes through untouched, and every other model is
     * unaffected. Applied at the single point where each branch would emit an
     * effort string (Chat Completions reasoning_effort / reasoning.effort AND
     * the Responses API reasoning.effort) so no branch can leak a raw xhigh.
     * lowercase-contains match, mirroring the T-reasoning-effort-fallback keys.
     */
    private fun clampEffortForModel(effort: String): String {
        val lid = model.id.lowercase()
        // [T-fallback-thinking-preclamp] Match the FAMILY substring, not one
        // spelling: catalog docs say "MiMo-2.5" but the live API returns
        // "mimo-v2.5" / "mimo-v2.5-pro", which the old "mimo-2.5" match missed
        // (mirrors iOS 72968c4f).
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    /**
     * Inject the provider-specific thinking parameters for one request.
     *
     * [T-thinking-rules-phase1] The body of this function used to be an if-return chain
     * keyed on model-id substrings. That logic now lives in [ThinkingRuleResolver] as a
     * data-driven rule registry (design §4/§5); this remains as the call-site-compatible
     * entry point so every caller — and the golden snapshot that pins this exact
     * behaviour — is unchanged.
     *
     * Behaviour is byte-for-byte identical to the pre-refactor chain, enforced by
     * ThinkingWireGoldenSnapshotTest (119 rows generated against the old code and
     * committed before the refactor, fdc28e2b).
     *
     * PHASE 1 SCOPE: OpenAI-compatible endpoints only. Gemini and Anthropic keep their
     * own emitters and are not routed through the resolver yet.
     */
    private fun injectThinkingParams(body: JSONObject, level: ThinkingLevel, maxTokens: Int) {
        // [T-android-thinking-level-arch] `level` is already clamped to the model ceiling
        // by LLMProvider.streamMessage/sendMessage — do NOT re-clamp here.
        val ctx = ThinkingResolveContext(
            modelId = model.id,
            instanceId = thinkingRuleInstanceId,
            supportsReasoning = model.supportsReasoning,
            declaredEffortValues = model.reasoningEffortValues,
            // [OpenMinis#163] null (catalog silent) must read as false here —
            // only an affirmative declaration may suppress the field.
            declaresNoEffortTiers = model.declaresNoEffortTiers == true,
            level = level,
            maxTokens = maxTokens,
            isOpenRouter = isOpenRouter,
            usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
            isMistral = isMistral,
            isDashScope = isDashScope,
            isXAI = isXAI,
            offEffort = explicitOffEffort(),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        // [T-thinking-rules-observability] Design §8 / GH OpenMinis#100: which rule
        // actually won must be inspectable, or a rule layer just replaces one hidden
        // variable with a more complicated one. minis-config exposure is Phase 2.
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${model.id} level=${level.name} ${trace.logLine}",
        )
    }

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

    // MARK: - Codex image generation (gpt-image-2)

    /**
     * [T-codex-gpt-image2-oauth-android] Build the Codex image_generation
     * request body. The wire model is gpt-5.5 (the Codex backend invokes the
     * underlying gpt-image-2 via the built-in image_generation tool); the user
     * turn is the fixed "Use the image generation tool to create: <prompt>"
     * instruction. The <prompt> is the latest user text — plain string content
     * or the concatenated text parts of the last user message.
     */
    private fun buildCodexImageBody(messages: List<LLMMessage>): JSONObject {
        val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER }
        val prompt = lastUser?.let { m ->
            m.content.takeIf { it.isNotBlank() }
                ?: m.contentParts.filterIsInstance<AgentContentPart.Text>()
                    .joinToString(" ") { it.text }.trim()
        }.orEmpty()
        return JSONObject().apply {
            put("model", "gpt-5.5")
            put("instructions", "You are a helpful assistant. Use tools when available.")
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Use the image generation tool to create: $prompt")
            }))
            put("store", false)
            put("tools", JSONArray().put(JSONObject().put("type", "image_generation")))
            put("reasoning", JSONObject().put("effort", "low"))
            put("include", JSONArray())
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            put("stream", true)
        }
    }

    /**
     * [T-android-codex-image-stream-parse-fix #617] Consume the Codex Responses
     * SSE stream and extract the generated image, emitting it as a
     * MediaAttachment chunk followed by Finished.
     *
     * Structurally aligned with iOS `consumeCodexImageStream` (1225ec0b /
     * 2dd35a14): parse each `data:` SSE line as JSON and pull the base64 from
     * the `image_generation_call` output item's `result` field — NOT a blind
     * regex over the raw body. The previous regex `iVBOR[A-Za-z0-9+/=]{1000,}`
     * only matched PNG base64 (iVBOR is the base64 of the PNG \x89PNG header),
     * so a WebP (UklGR…) or JPEG (/9j/…) image — which gpt-image-2 routinely
     * returns — never matched and the method threw "no image data" even though
     * the Codex backend had returned a full ~8 MB valid image (#615 diagnosis).
     *
     * Failure modes, each a distinct LLMError (mirrors iOS):
     *   - auth (401/403): surfaced earlier by the non-2xx branch → mapHttpError,
     *     never reaches here.
     *   - safety refusal: `image_generation_call` status=failed and/or a refusal
     *     message instead of an image → ProviderError("rejected by safety…").
     *   - no image: stream completed with neither image nor refusal →
     *     ProviderError("No image data…"). Only reported in this genuine case —
     *     not on a successfully-decoded non-PNG image.
     *   - network/interface: read throws (IOException) → caller maps to
     *     NetworkError.
     *
     * Streams line-by-line (no 8 MB StringBuilder + regex backtracking): only
     * the one `result` base64 string is retained, decoded once at the end.
     * Never logs the token (the SSE body carries no Authorization).
     */
    private suspend fun handleCodexImageStream(
        reader: BufferedReader,
        emit: (LLMStreamChunk) -> Unit,
    ) {
        var b64Result: String? = null
        var revisedPrompt: String? = null
        var imageCallFailed = false
        var refusalText: String? = null

        // Pull the base64 result / failure / revised prompt out of one output
        // item. Used both for streamed `response.output_item.done` items and,
        // as a fallback, for every item in the final `response.completed`
        // payload (matches iOS scanItem).
        fun scanItem(item: JSONObject) {
            when (item.optString("type")) {
                "image_generation_call" -> {
                    if (item.optString("status") == "failed") imageCallFailed = true
                    item.optString("result").takeIf { it.isNotEmpty() }?.let { b64Result = it }
                    item.optString("revised_prompt").takeIf { it.isNotEmpty() }?.let { revisedPrompt = it }
                }
                "message", "output_text" -> {
                    // Refusal / explanation text the model emits when it declines.
                    val content = item.optJSONArray("content")
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val c = content.optJSONObject(i) ?: continue
                            if (c.optString("type").contains("text")) {
                                c.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                            }
                        }
                    } else {
                        item.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                    }
                }
            }
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            // Tolerate both `data: {…}` and `data:{…}` (same as the chat path).
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").let { if (it.startsWith(" ")) it.removePrefix(" ") else it }
            if (payload == "[DONE]") break

            val event = try { JSONObject(payload) } catch (e: Exception) { continue }
            when (event.optString("type")) {
                "response.output_item.done" -> {
                    event.optJSONObject("item")?.let { scanItem(it) }
                }
                "response.output_text.done", "response.output_text.delta" -> {
                    event.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                        ?: event.optString("delta").takeIf { it.isNotEmpty() }
                            ?.let { refusalText = (refusalText ?: "") + it }
                }
                "response.completed" -> {
                    if (b64Result == null) {
                        val output = event.optJSONObject("response")?.optJSONArray("output")
                        if (output != null) {
                            for (i in 0 until output.length()) {
                                output.optJSONObject(i)?.let { scanItem(it) }
                            }
                        }
                    }
                }
                "response.failed", "error" -> {
                    val msg = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotEmpty() }
                        ?: event.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                        ?: "Codex image generation failed"
                    throw LLMError.ProviderError(msg)
                }
            }
        }

        // Success: base64 image extracted. Detect the real format from the
        // decoded bytes (PNG / JPEG / WebP / GIF) instead of assuming PNG.
        val b64 = b64Result
        if (b64 != null) {
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Exception) {
                throw LLMError.ProviderError("Failed to decode generated image: ${e.message}")
            }
            if (bytes.isNotEmpty()) {
                emit(
                    LLMStreamChunk.MediaAttachment(
                        LLMMediaAttachment(
                            type = LLMMediaAttachment.MediaType.IMAGE,
                            mimeType = detectImageMime(bytes),
                            data = bytes,
                        ),
                    ),
                )
                emit(LLMStreamChunk.Finished("end_turn"))
                return
            }
        }

        // Safety refusal: the image call explicitly failed and/or the model
        // returned a refusal message instead of an image.
        if (imageCallFailed || refusalText != null) {
            val reason = refusalText?.trim()
            throw LLMError.ProviderError(
                "Image generation was rejected by the safety system" +
                    (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
            )
        }

        // Stream completed with neither an image nor a refusal.
        throw LLMError.ProviderError("No image data in Codex response")
    }

    /**
     * [T-android-codex-image-stream-parse-fix] Detect an image's MIME type from
     * its magic bytes. Mirrors iOS `detectImageMime`. gpt-image-2 can return
     * PNG, JPEG, or WebP, so the previous hardcoded "image/png" mislabeled
     * non-PNG output. Falls back to image/png when too short / unrecognized.
     */
    private fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        val b = data.map { it.toInt() and 0xFF }
        return when {
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
            b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
            b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 -> "image/webp" // RIFF (WebP)
            b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 -> "image/gif"
            else -> "image/png"
        }
    }

    // MARK: - Responses API (Codex OAuth)

    /**
     * Build request body for the Responses API format (used by Codex OAuth).
     * Uses `input` instead of `messages`, `instructions` instead of system prompt.
     */
    /**
     * [T-android-responses-toplevel-images] Encode one image as a Responses-API
     * content block, or as the no-vision text placeholder when the target model
     * cannot see pixels.
     *
     * Extracted so the two Responses call sites (structured `contentParts`
     * messages and legacy top-level `imageParts` messages) cannot drift apart —
     * the drift is exactly what produced the silent drop this fixes. Note the
     * Responses shape differs from Chat Completions: `image_url` is a bare
     * STRING here, not a `{"url": …}` object.
     */
    private fun responsesImageBlock(
        data: ByteArray,
        mimeType: String,
        supportsImages: Boolean,
        noVisionPlaceholder: String?,
    ): JSONObject = if (supportsImages) {
        // T-imgsize: provider-boundary backstop.
        val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(data)
        val safeMime = if (safeBytes === data) mimeType else "image/jpeg"
        val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
        JSONObject().apply {
            put("type", "input_image")
            put("image_url", "data:$safeMime;base64,$b64")
        }
    } else {
        JSONObject().apply {
            put("type", "input_text")
            put(
                "text",
                noVisionPlaceholder
                    ?: "[Image attached but this model does not support vision input]",
            )
        }
    }

    // `internal` for the same reason as [buildRequestBody]: the tool-result-image
    // regression is asserted against the constructed JSON directly.
    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        /**
         * [T-android-responses-toplevel-images] Images passed as the top-level
         * argument rather than on `msg.contentParts`, attached to the LAST user
         * message — the same contract [buildRequestBody] implements.
         *
         * This parameter did not exist, and that was a silent data loss: every
         * caller that supplies images this way (minis-model-use's `image_url`
         * blocks, VisionGroupResolver.describeOnce, any direct
         * sendMessage(imageParts=…)) had its pixels dropped on the floor the
         * moment the provider was on the Responses path, with no error. The
         * user-visible symptom was a vision model replying "no image was
         * provided" — reported against a Vision Group whose describing model
         * ran on Responses.
         */
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: same vision-capability gate as buildRequestBody. Responses API
        // path (Codex OAuth) is currently always wired to a vision-capable
        // GPT-5.x so this branch is defensive rather than load-bearing, but
        // keeping the two paths symmetric prevents future regressions when
        // a non-vision model gets routed through Responses (e.g. via
        // forceResponsesAPI on a custom provider).
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        body.put("stream", stream)
        // [T-android-xai-priority] xAI documents service_tier for both text
        // inference endpoints. xAI currently always resolves to the Chat
        // Completions path (forceChatCompletions), so this is belt-and-braces
        // — but keeping the two builders in step means a future routing change
        // does not silently drop the user's Fast Mode choice.
        resolvedServiceTier()?.let { body.put("service_tier", it) }
        body.put("store", false)
        body.put("parallel_tool_calls", true)
        // Stable per-conversation cache key so the Responses API can hit prompt
        // cache across turns. Codex CLI sets this to its conversation_id; at
        // this layer we don't have one, so we hash the first user message —
        // re-sent verbatim every turn of the same chat → stable across turns,
        // distinct between chats. iOS does the same in
        // OpenAIAgentProvider.swift:325 + derivePromptCacheKey() at line 515.
        // Without this, each turn was treated as a separate prompt by the
        // Responses-API cache regardless of how byte-stable the prefix was —
        // that's the missing piece between Android (~70%) and iOS (90%+) on
        // the Codex OAuth / forceResponsesAPI path.
        body.put("prompt_cache_key", derivePromptCacheKey(messages))
        // T-responses-include: `include: ["reasoning.encrypted_content"]` is a
        // ChatGPT-backend-only field. Third-party Responses-API-compatible
        // proxies (non-OpenAI) don't recognize it and reject the request with
        // 400. Mirrors iOS OpenAIAgentProvider.swift:404 which gates this
        // strictly behind isCodexOAuth. OpenAI's first-party Responses API
        // also accepts the field, so we keep it on for OAuth (Codex) only —
        // the encrypted reasoning content is what lets the ChatGPT backend
        // re-attach prior reasoning across turns without store=true.
        if (isOAuth) {
            body.put("include", JSONArray().put("reasoning.encrypted_content"))
        }
        // Thinking level → Responses API `reasoning.effort`. Mirrors iOS
        // OpenAIAgentProvider.swift:327-338. Pre-T119 this was hardcoded to
        // "low" regardless of the user's setting, so toggling Thinking
        // High/Medium/Off had no effect on GPT-5.x via the Responses path.
        // - When the user has thinking enabled → map their level to the
        //   matching effort string.
        // - When off but using Codex OAuth → fall back to "low" because the
        //   ChatGPT backend rejects requests without a `reasoning` object.
        // - Else → omit the field so the upstream applies its own default.
        // [T-android-codex-thinking-summary] `summary: "auto"` opts in to
        // streaming the human-readable reasoning SUMMARY (delivered as
        // `response.reasoning_summary_text.delta` SSE events with non-empty
        // `delta`). Without it the Responses API / Codex backend returns ONLY
        // `encrypted_content` — the reasoning deltas arrive empty, so the
        // Thinking region never renders even though the model reasoned (token
        // usage shows it did). This was the Codex-OAuth "thinking on but UI
        // shows nothing" bug (XIN). Mirrors iOS OpenAIAgentProvider.swift:415
        // (`["effort": effort, "summary": "auto"]`). OpenAI ignores the variant
        // it doesn't support and falls back to an auto-equivalent, so it's safe
        // on every Responses-flavor endpoint.
        // [T-android-xhigh-effort-clamp] Also clamp on the Responses API path:
        // the reasoning.effort field is the same name/values as Chat Completions
        // and would send xhigh too. MiMo-2.5/Agnes normally use Chat Completions
        // (the reported 400/422), but a user could flip useResponsesAPI on, so
        // guard it here as well — only xhigh for those two families is affected.
        // [T-android-thinking-level-arch] `thinkingLevel` is already clamped by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        val effort = if (thinkingLevel.isEnabled) {
            mapThinkingLevelToResponsesEffort(thinkingLevel)?.let { clampEffortForModel(it) }
        } else null
        when {
            // [T-android-mistral-reasoning-422] Mistral rejects the reasoning
            // request parameter outright (`422 extra_forbidden body.reasoning`,
            // GH OpenMinis#87). The gate added alongside injectThinkingParams
            // covers only the Chat Completions path; this builder is a SECOND,
            // independent injection site that a Mistral instance with
            // useResponsesAPI enabled reaches ungated. For Mistral the answer to
            // "should any thinking field be sent" is NEVER, on every request
            // path — so suppress the whole block. Must stay FIRST so it wins over
            // the isOAuth fallback below.
            isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            isOAuth -> body.put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            // [T-thinking-off-explicit] Thinking OFF on a reasoning-capable
            // model: send the explicit off tier instead of omitting `reasoning`
            // — omission lets the vendor default kick in. Same ALLOWLIST as the
            // Chat path (official OpenAI → "none", Volcano Ark → "minimal");
            // vendors with undocumented off semantics keep the historical
            // omission. No summary/include: nothing should stream back.
            // Mirrors iOS OpenAIAgentProvider ff60c818's Responses off branch.
            !thinkingLevel.isEnabled && model.supportsReasoning == true &&
                !model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                explicitOffEffort()?.let { offEffort ->
                    body.put("reasoning", JSONObject().put("effort", offEffort))
                }
            }
        }

        // [T-responses-max-output-tokens] The builder received maxTokens but
        // never wrote it into the body, so Responses-flavor vendors fell back
        // to their (often tiny) defaults and truncated. Same guard as iOS
        // 637cd890/5f148144: maxTokens > 0, and Codex OAuth excluded — the
        // codex_cli_rs body shape is a client fingerprint and must not carry
        // fields the real CLI doesn't send.
        if (maxTokens > 0 && !isOAuth) {
            body.put("max_output_tokens", maxTokens)
        }

        // [T-codex-fast-mode] Fast tier injection (mirrors iOS fb671083 +
        // 838ba929). Wire value verified against openai/codex source
        // (codex-rs/protocol config_types.rs): ServiceTier::Fast sends
        // service_tier="priority" — "fast" is only the UI name, so this stays
        // inside the codex_cli_rs client fingerprint on the OAuth route. Gate
        // is toggle + gpt-family model only: this builder IS the Responses
        // path, and Responses relays (e.g. sub2api) normalize/pass the tier
        // through, so no isOAuth narrowing. Ineligible upstreams ignore the
        // field or silently downgrade (receipt visible via the
        // response.completed service_tier log).
        //
        // [T-android-xai-priority] Guarded on the key being absent so this
        // cannot clobber a per-instance tier set above. The two gates are
        // disjoint today (that toggle is xAI-only, this one gpt-only, and xAI
        // never reaches this builder), so the guard changes no current
        // behaviour — it just means neither feature can silently overwrite the
        // other if either's routing widens later. Both want the same value
        // anyway, so "first writer wins" loses nothing.
        if (!body.has("service_tier") &&
            com.openminis.app.data.FastModePrefs.isEnabled() &&
            model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        // Tools — flat shape required by Responses API ({type, name, description,
        // parameters}), distinct from Chat Completions' wrapped {type, function:{...}}.
        // Until this branch existed, Responses-API requests went out with no `tools`
        // field at all, so the model invented its own <tool_call>{...} text format.
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toResponsesAPIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        // Mirrors iOS convertMessagesResponsesAPI (OpenAIAgentProvider.swift:895):
        // structured content parts become typed input items — function_call /
        // function_call_output — instead of free-text role/content pairs.
        val input = JSONArray()
        // [T-android-responses-toplevel-images] Same contract as
        // buildRequestBody: top-level images ride on the LAST user message.
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((msgIndex, msg) in messages.withIndex()) {
            val attachTopLevelImages =
                msgIndex == lastUserIdx && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
            if (msg.contentParts.isNotEmpty()) {
                when (msg.role) {
                    LLMMessage.Role.ASSISTANT -> {
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                input.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", text)
                                })
                            }
                        }
                        for (tu in msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                            val (callId, fcId) = splitResponsesAPIIds(tu.id)
                            val safeCallId = capResponsesId(callId)
                            // Responses API requires both `id` (fc_…) and `call_id` (call_…).
                            // When the message was synthesized outside a Responses round-trip
                            // (e.g. injected from Chat Completions history) the fcId is null —
                            // generate a deterministic synthetic so the API still accepts it.
                            val safeFcId = fcId?.let { capResponsesId(it) }
                                ?: "fc_syn_${safeCallId.takeLast(24)}"
                            input.put(JSONObject().apply {
                                put("type", "function_call")
                                put("id", safeFcId)
                                put("call_id", safeCallId)
                                put("name", tu.name)
                                put("arguments", tu.input.toString())
                            })
                        }
                    }
                    LLMMessage.Role.USER -> {
                        for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                            val (callId, _) = splitResponsesAPIIds(tr.id)
                            input.put(JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", capResponsesId(callId))
                                put("output", tr.content)
                            })
                            // [T-android-toolresult-image-dropped] Same defect as the
                            // Chat Completions branch: function_call_output takes a
                            // string `output`, so read_image's pixels had nowhere to
                            // go and were silently dropped. Emit them as a following
                            // user turn carrying an input_image block.
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                input.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", "[Image returned by ${tr.name}]")
                                        })
                                        put(
                                            responsesImageBlock(
                                                trBytes,
                                                tr.imageMimeType ?: "image/jpeg",
                                                supportsImages,
                                                null,
                                            ),
                                        )
                                    })
                                })
                            }
                        }
                        // T132: emit text + input_image content for the user
                        // turn so vision-capable Responses-API models actually
                        // see the bytes. Without the input_image branch the
                        // outer `content` was a flat concatenated string and
                        // image bytes never reached the wire (the textual
                        // [attached image: …] caption was the only hint, and
                        // the model fell back to read_image / shell_execute
                        // groping for a path it could see). Mirrors iOS
                        // convertMessagesResponsesAPI's image handling.
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imgParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()
                        if (imgParts.isNotEmpty()) {
                            val contentArray = JSONArray()
                            for (part in msg.contentParts) {
                                when (part) {
                                    is AgentContentPart.Text -> {
                                        if (part.text.isNotEmpty()) {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                        }
                                    }
                                    is AgentContentPart.ImageData -> {
                                        if (supportsImages) {
                                            // T-imgsize: backstop for Responses API path.
                                            val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                            val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                            val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_image")
                                                // Responses API takes image_url
                                                // as a *string*, not the
                                                // {"url":...} object shape used
                                                // by Chat Completions.
                                                put("image_url", "data:$safeMime;base64,$b64")
                                            })
                                        } else {
                                            // T264: target model has no vision modality —
                                            // emit a text placeholder. [T-android-vision-group
                                            // / GH#182] Vision-Group read_image hint when
                                            // seeded (carries the path); else the historical
                                            // literal. Note: Responses API uses "input_text"
                                            // type (vs "text" on Chat Completions).
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.noVisionPlaceholder
                                                    ?: "[Image attached but this model does not support vision input]")
                                            })
                                        }
                                    }
                                    // [T-android-responses-toplevel-images]
                                    // ToolUse/ToolResult are handled above for
                                    // this role and legitimately don't belong
                                    // in the content array. Anything else is a
                                    // part type this converter has never been
                                    // taught to encode — the failure mode being
                                    // fixed here (content dropped with no error
                                    // and no log) is exactly what that produces,
                                    // so make it visible rather than silent.
                                    is AgentContentPart.ToolUse,
                                    is AgentContentPart.ToolResult -> Unit
                                    else -> com.openminis.app.logging.AppLogger.error(
                                        "OpenAIProvider",
                                        "[responses] DROPPED unconvertible content part " +
                                            "${part.javaClass.simpleName} on role=user — it will NOT " +
                                            "reach the model. Add an encoding branch for it.",
                                    )
                                }
                            }
                            // [T-android-responses-toplevel-images] Top-level
                            // images belong on this same turn.
                            if (attachTopLevelImages) {
                                for (p in imageParts) {
                                    contentArray.put(
                                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                    )
                                }
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (attachTopLevelImages) {
                            // [T-android-responses-toplevel-images] Structured
                            // message with no ImageData parts, but images were
                            // supplied top-level (minis-model-use / Vision
                            // Group). Previously this fell into the text-only
                            // branch below and the pixels vanished.
                            val contentArray = JSONArray()
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                contentArray.put(JSONObject().apply {
                                    put("type", "input_text")
                                    put("text", text)
                                })
                            }
                            for (p in imageParts) {
                                contentArray.put(
                                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                )
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (textParts.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", textParts.joinToString("") { it.text })
                            })
                        }
                    }
                    else -> {
                        input.put(JSONObject().apply {
                            put("role", msg.role.value)
                            put("content", msg.content)
                        })
                    }
                }
            } else if (msg.audioParts.isNotEmpty()) {
                // [GH#67] Legacy (non-contentParts) message carrying audio —
                // the minis-model-use path. The Responses API keeps the SAME
                // nested input_audio shape as Chat Completions ({data,
                // format}), unlike input_image which flattens image_url to a
                // string. Text rides along as input_text.
                val contentArray = JSONArray()
                for (audio in msg.audioParts) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().apply {
                            put("data", audio.base64Data)
                            put("format", audio.format)
                        })
                    })
                }
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                // [T-android-responses-toplevel-images] An audio-carrying turn
                // can also carry images.
                if (attachTopLevelImages) {
                    for (p in imageParts) {
                        contentArray.put(
                            responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                        )
                    }
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else if (attachTopLevelImages) {
                // [T-android-responses-toplevel-images] THE reported bug's path.
                // A plain (contentParts-free) user message plus top-level
                // images — what VisionGroupResolver.describeOnce and
                // minis-model-use's image_url blocks produce. This builder had
                // no imageParts parameter at all, so the message was emitted as
                // a bare text string and the pixels never reached the wire. The
                // vision model then answered "no image was provided", with no
                // error anywhere to explain it.
                val contentArray = JSONArray()
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                for (p in imageParts) {
                    contentArray.put(
                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                    )
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else {
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", msg.content)
                })
            }
        }
        body.put("input", input)

        // [T-android-model-use-passthrough-mode GH#72] Same verbatim merge as the
        // chat-completions builder. Skipped for Codex OAuth inside mergeChatExtraBody.
        mergeChatExtraBody(body)

        return body
    }

    /**
     * Combine Responses-API call_id + item_id into a single string the agent loop
     * can carry through tool_use/tool_result blocks. The next request splits it
     * back apart so the API sees the original ids verbatim.
     */
    private fun combineResponsesAPIIds(callId: String, fcId: String): String =
        if (fcId.isEmpty()) callId else "$callId|$fcId"

    private fun splitResponsesAPIIds(combined: String): Pair<String, String?> {
        val sep = combined.indexOf('|')
        return if (sep < 0) combined to null
        else combined.substring(0, sep) to combined.substring(sep + 1)
    }

    /** Responses-API ids must be ≤64 chars; truncate defensively to avoid 400s. */
    private fun capResponsesId(id: String): String =
        if (id.length <= 64) id else id.substring(0, 64)

    /**
     * [T-android-tool-call-id-too-long] Chat-Completions `tool_calls[].id` /
     * `tool_call_id` must be ≤64 chars — OpenAI-compatible endpoints reject longer
     * ids with a 400 ("string too long. Expected a string with maximum length 64").
     * Two ways an id gets over 64 here:
     *   - a Responses-origin history turn stores the combined "call_…|fc_…" id
     *     (splitResponsesAPIIds' form) which is replayed verbatim on a Chat
     *     Completions request;
     *   - memory-tool / synthetic ids that are long by construction.
     * We keep only the call_-id half (before any '|') and, if still >64, replace
     * it with a deterministic SHA-256-derived id. Determinism matters: the SAME
     * raw id must map to the SAME capped id so the assistant tool_call and its
     * matching tool result still pair up (a mismatch is its own 400). The
     * downstream dedupe pass then guarantees uniqueness within the request.
     */
    private fun capChatToolCallId(id: String): String {
        val callHalf = id.substringBefore('|')
        if (callHalf.length <= 64) return callHalf
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(callHalf.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        // "call_" + 56 hex chars = 61 chars, safely under 64 and clearly a call id.
        return "call_${hex.take(56)}"
    }

    /**
     * Derives a stable per-conversation `prompt_cache_key` for the Responses
     * API. Mirrors iOS derivePromptCacheKey (OpenAIAgentProvider.swift:515).
     * The first user message is re-sent verbatim on every turn → its hash is
     * stable across turns within the same chat, distinct between chats.
     * Falls back to a random UUID when there is no user text yet (first
     * turn with attachments-only input, etc.).
     */
    private fun derivePromptCacheKey(messages: List<LLMMessage>): String {
        for (msg in messages) {
            if (msg.role != LLMMessage.Role.USER) continue
            val text = msg.contentParts
                .filterIsInstance<AgentContentPart.Text>()
                .joinToString("") { it.text }
                .ifEmpty { msg.content }
            if (text.isNotEmpty()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                return "minis-${hex.take(32)}"
            }
        }
        return "minis-${java.util.UUID.randomUUID().toString().lowercase()}"
    }

    /**
     * Map ThinkingLevel → Responses API `reasoning.effort` string. Mirrors
     * iOS reasoningEffort(for:level:) (OpenAIAgentProvider.swift:531).
     * Returns null when the level is OFF — caller decides whether to omit
     * the `reasoning` field entirely or fall back to "low" (Codex requires it).
     */
    private fun mapThinkingLevelToResponsesEffort(level: ThinkingLevel): String? = when (level) {
        ThinkingLevel.OFF -> null
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] MAX → "max"; ULTRA also → "max" —
        // the Responses/Codex endpoint rejects a literal "ultra"; ultra is a
        // client-side "Max + orchestration" concept only (mirrors iOS).
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    /**
     * Responses-API tool shape — flat {type, name, description, parameters},
     * NOT the Chat Completions wrapper {type, function:{...}}. Mirrors iOS
     * convertToolsResponsesAPI (OpenAIAgentProvider.swift:977).
     */
    private fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** Parse usage from Responses API format. */
    private fun parseResponsesAPIUsage(usage: JSONObject): LLMUsage {
        val inputTokens = usage.optInt("input_tokens", 0)
        // Responses API reports cache hits at `input_tokens_details.cached_tokens`
        // (distinct from Chat Completions' `prompt_tokens_details.cached_tokens`).
        // Mirrors iOS OpenAIProvider.swift:640. Without this, even a perfectly
        // cached Responses-API request showed cacheRead=0 in usage stats —
        // making T126's prompt_cache_key wiring look like it had no effect.
        val cacheRead = usage.optJSONObject("input_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
        // `input_tokens` is the FULL input (cached subset included); subtract the
        // cached portion so `inputTokens` is fresh-only, matching Anthropic — else
        // the cache is counted twice in `input + cacheRead` (deflates hit rate).
        // Guard: only subtract when non-negative; no cache field → unchanged.
        // latestContextTokens stays the full input (that IS the context size).
        val freshInput = cacheRead?.let { (inputTokens - it).takeIf { d -> d >= 0 } } ?: inputTokens
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("output_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = inputTokens,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()

        val message = try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorMessage = error?.safeOptString("message", "") ?: body
            "[$statusCode] $errorMessage"
        } catch (_: Exception) {
            "HTTP $statusCode: ${body.take(500)}"
        }

        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) {
            // 503 with permanent failure indicators → ProviderError (trigger group fallback)
            if (statusCode == 503 && (body.contains("no_available_providers") || body.contains("model_not_found"))) {
                return LLMError.ProviderError(message)
            }
            return LLMError.TransientError(message)
        }
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
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
private class CallWatchState {
    /** Set by requestBodyEnd — the moment upload finished (monotonic nanos). */
    val uploadDoneAtNanos = java.util.concurrent.atomic.AtomicLong(0L)
    /** Physical connection serving this call, captured at connectionAcquired. */
    val connection = java.util.concurrent.atomic.AtomicReference<okhttp3.Connection?>(null)
}

/**
 * [T-android-openai-codex-timeout]
 * Network-leg trace listener for OpenAIProvider's OkHttpClient. Logs every
 * OkHttp call lifecycle event with timestamps so a future SocketTimeout
 * report can be triaged to a specific leg:
 *
 *   - dnsStart / dnsEnd          : was the host resolvable, how long
 *   - proxySelect{Start,End}     : which proxy (or DIRECT) routed this
 *   - connectStart / -End / -Failed : TCP connect to proxy or origin
 *   - secureConnect{Start,End}   : TLS handshake duration + cipher / alpn
 *   - connectionAcquired/Released: which physical connection served the
 *                                  call — repeated calls reusing the
 *                                  same Connection identityHash mean
 *                                  the OkHttp pool is recycling, useful
 *                                  for spotting "stale-proxy-mid-stream"
 *   - requestHeaders/BodyEnd     : when the request was fully sent
 *   - responseHeadersStart/End   : time to first server byte (the TFB
 *                                  number tells us whether the proxy
 *                                  was slow vs. the origin)
 *   - responseBodyStart/End      : SSE stream lifecycle — `End` firing
 *                                  with a SocketTimeout root cause is
 *                                  the classic "mid-stream silence" case
 *   - callFailed                 : terminal — pairs the failure to the
 *                                  earliest leg that completed cleanly
 *
 * One instance per call (the factory in OpenAIProvider). Holds a
 * monotonic start timestamp so all log lines carry a relative offset
 * from callStart.
 */
private class OkHttpNetTraceListener : EventListener() {
    private val tag = "OkHttpNetTrace"
    private val t0 = System.nanoTime()
    private fun ms(): Long = (System.nanoTime() - t0) / 1_000_000L
    private fun callTag(call: Call): String {
        val id = System.identityHashCode(call).toString(16)
        return "call#$id"
    }

    override fun callStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callStart url=${call.request().url}"
        )
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectStart host=${url.host}"
        )
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectEnd host=${url.host} chain=${proxies.joinToString(",") { it.toString() }}"
        )
    }

    override fun dnsStart(call: Call, domainName: String) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsStart host=$domainName"
        )
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsEnd host=$domainName resolved=${inetAddressList.size} addrs=${inetAddressList.take(3).joinToString(",") { it.hostAddress ?: "?" }}"
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectStart target=$inetSocketAddress proxy=$proxy"
        )
    }

    override fun secureConnectStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsStart"
        )
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsEnd version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}"
        )
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectEnd target=$inetSocketAddress proxy=$proxy proto=$protocol"
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms connectFailed target=$inetSocketAddress proxy=$proxy proto=$protocol err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        // [T-android-ttfb-upload-split / #188] Capture the physical connection so
        // the TTFB watchdog can evict THIS ONE on timeout (targeted, never the pool).
        call.request().tag(CallWatchState::class.java)?.connection?.set(connection)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionAcquired conn#$conn route=${connection.route()} proto=${connection.protocol()}"
        )
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionReleased conn#$conn"
        )
    }

    override fun requestHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersStart"
        )
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersEnd"
        )
    }

    override fun requestBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyStart"
        )
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        // [T-android-ttfb-upload-split / #188] Upload finished — from here the
        // real time-to-first-byte clock starts (the watchdog polls this).
        call.request().tag(CallWatchState::class.java)?.uploadDoneAtNanos?.set(System.nanoTime())
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyEnd bytes=$byteCount"
        )
    }

    override fun responseHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersStart (server first byte)"
        )
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersEnd status=${response.code} proto=${response.protocol}"
        )
    }

    override fun responseBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyStart"
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyEnd bytes=$byteCount"
        )
    }

    override fun callEnd(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callEnd"
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        // The most diagnostic of all: pairs the failure with whatever
        // milestone WAS reached before it. Read alongside the listener's
        // earlier lines to localize the stall.
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms callFailed err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun canceled(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms canceled"
        )
    }
}
