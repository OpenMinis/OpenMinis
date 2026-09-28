package com.openminis.app.provider.anthropic

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.sanitizeToolId
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion
import com.openminis.app.provider.thinking.ThinkingRuleResolver

class AnthropicProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.claudeHaiku45,
    private val basePath: String = "https://api.anthropic.com",
    /** Whether this provider uses OAuth credentials (Bearer + beta header). */
    val isOAuth: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on the chat
     * request. Only set for custom-base Anthropic-compat instances.
     */
    private val customUserAgent: String? = null,
) : LLMProvider {
    override val name = "Anthropic"
    override val defaultMaxOutputTokens: Int get() = 64_000

    /**
     * [T-android-enhanced-cache] Enhanced Cache toggle — when true, every
     * `cache_control: ephemeral` breakpoint carries `ttl:"1h"` (1-hour cache
     * TTL) instead of the default 5-minute TTL, and the
     * `extended-cache-ttl-2025-04-11` beta flag is attached to API-key requests
     * (OAuth already carries it). Set per chat turn from
     * `ChatViewModel.enhancedCacheEnabled`; defaults off so existing behaviour
     * is unchanged. Mirrors iOS `RequestBodyPatcher.setExtendedCacheTTL`.
     */
    var enhancedCache: Boolean = false

    /**
     * Build a `cache_control` object honoring [enhancedCache]. Default 5-minute
     * TTL is `{"type":"ephemeral"}`; enhanced is `{"type":"ephemeral","ttl":"1h"}`.
     * Every ephemeral breakpoint in the request body routes through here.
     */
    private fun ephemeralCacheControl(): JSONObject =
        JSONObject().put("type", "ephemeral").apply {
            if (enhancedCache) put("ttl", "1h")
        }

    /** Use Bearer auth for custom (non-Anthropic) base URLs or OAuth. */
    private val isCustomEndpoint: Boolean = basePath != "https://api.anthropic.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool — see NetworkMonitor.
        // Network-transition eviction must reach provider connections.
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .build()

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = false, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        val request = buildRequest(body.toString(), body)
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapHttpError(response.code, responseBody)
        }

        val json = JSONObject(responseBody)
        parseResponse(json)
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
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        // T302: serialize the body exactly once and pass the string to
        // buildRequest. Pre-T302 body.toString() ran twice per call (once
        // here, once inside buildRequest); each emitted string was tens of
        // MB on long agent loops and stacked under GC pressure.
        val bodyStr = body.toString()
        val request = buildRequest(bodyStr, body)

        val startTime = System.currentTimeMillis()

        // Capture request headers as map
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }

        val response = client.newCall(request).execute()
        val durationMs = System.currentTimeMillis() - startTime

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // see OpenAIProvider for the full rationale (release users never
            // reach debug.llmRequests, so retaining multi-MB request bodies
            // there is pure OOM risk for zero benefit).
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "anthropic",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = durationMs,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            android.util.Log.e("AnthropicProvider", "Stream failed: ${response.code} isOAuth=$isOAuth body=${errorBody.take(300)}")
            throw mapHttpError(response.code, errorBody)
        }

        // Log successful request (debug builds only — see above)
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "anthropic",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = durationMs,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        // Track current tool_use block being streamed
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolInputBuffer = StringBuilder()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val payload = l.removePrefix("data: ")
                if (payload == "[DONE]") break

                val event = try { JSONObject(payload) } catch (_: Exception) { continue }
                android.util.Log.d("ToolChain[Provider]", "RAW SSE: $payload")
                val eventType = event.safeOptString("type", "")

                when (eventType) {
                    "message_start" -> {
                        send(LLMStreamChunk.Started)
                        event.optJSONObject("message")?.optJSONObject("usage")?.let { usage ->
                            send(LLMStreamChunk.Usage(parseUsage(usage)))
                        }
                    }
                    "content_block_start" -> {
                        val contentBlock = event.optJSONObject("content_block")
                        if (contentBlock?.safeOptString("type", "") == "tool_use") {
                            currentToolId = contentBlock.safeOptString("id", "")
                            currentToolName = contentBlock.safeOptString("name", "")
                            toolInputBuffer.clear()
                            android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=$currentToolId name=$currentToolName")
                            send(LLMStreamChunk.ToolUseStart(currentToolId!!, currentToolName!!))
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        when (delta.safeOptString("type", "")) {
                            "text_delta" -> {
                                val text = delta.safeOptString("text", "")
                                if (text.isNotEmpty()) send(LLMStreamChunk.Text(text))
                            }
                            "thinking_delta" -> {
                                val thinking = delta.safeOptString("thinking", "")
                                if (thinking.isNotEmpty()) send(LLMStreamChunk.ThinkingDelta(thinking))
                            }
                            "input_json_delta" -> {
                                val partial = delta.safeOptString("partial_json", "")
                                if (partial.isNotEmpty() && currentToolId != null) {
                                    toolInputBuffer.append(partial)
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=$currentToolId accumulated=${toolInputBuffer.length}chars")
                                    send(LLMStreamChunk.ToolInputDelta(currentToolId!!, toolInputBuffer.toString()))
                                }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolId != null && currentToolName != null) {
                            val args = try {
                                JSONObject(toolInputBuffer.toString())
                            } catch (_: Exception) {
                                JSONObject()
                            }
                            android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=$currentToolId name=$currentToolName args=${args.toString().take(300)}")
                            send(LLMStreamChunk.ToolCallComplete(currentToolId!!, currentToolName!!, args))
                            currentToolId = null
                            currentToolName = null
                            toolInputBuffer.clear()
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("usage")?.let { usage ->
                            send(LLMStreamChunk.Usage(parseUsage(usage)))
                        }
                        val stopReason = event.optJSONObject("delta")
                            ?.safeOptString("stop_reason", "")?.ifEmpty { null }
                        send(LLMStreamChunk.Finished(stopReason))
                    }
                }
            }
        } catch (e: Exception) {
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose()
    }

    /**
     * Build the `system` field as a JSON array of content blocks.
     * Mirrors iOS AnthropicProvider.resolveSystemPrompt — two authentication paths:
     *
     * - **OAuth (isOAuth=true)**: must start with the Claude Code prefix block (uncached)
     *   so Anthropic's server-side OAuth check sees the exact expected prompt. Any user
     *   tail is emitted as a second block with `cache_control: ephemeral` for cache hits.
     *   If the caller already embedded the prefix, strip it before splitting; if not,
     *   force-prepend the prefix so OAuth never fails the server-side gate.
     *
     * - **API key (isOAuth=false)**: single user-prompt block with `cache_control: ephemeral`.
     *   Returns null when the prompt is null/empty (iOS parity — no empty `system` field).
     */
    internal fun resolveSystemPrompt(userPrompt: String?): JSONArray? {
        val claudeCodePrefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        if (isOAuth) {
            // Strip the prefix if the caller already prepended it; the tail is the real user prompt.
            val tail = when {
                userPrompt == null -> ""
                userPrompt.startsWith(claudeCodePrefix) ->
                    userPrompt.removePrefix(claudeCodePrefix).trimStart('\n')
                else -> userPrompt
            }
            val arr = JSONArray()
            // Block 1: Claude Code base prompt — NO cache_control (iOS parity).
            arr.put(JSONObject().apply {
                put("type", "text")
                put("text", claudeCodePrefix)
            })
            // Block 2 (optional): user tail with ephemeral cache_control for max cache hits.
            if (tail.isNotEmpty()) {
                arr.put(JSONObject().apply {
                    put("type", "text")
                    put("text", tail)
                    put("cache_control", ephemeralCacheControl())
                })
            }
            return arr
        }
        // API key path: single cached block, or null when prompt is missing/empty.
        if (userPrompt.isNullOrEmpty()) return null
        return JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", userPrompt)
            put("cache_control", ephemeralCacheControl())
        })
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        // [T-android-thinking-level-arch] Already clamped to the model ceiling by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model.id)
        body.put("max_tokens", maxTokens)
        body.put("stream", stream)

        if (temperature != null && !thinkingLevel.isEnabled && !modelRejectsTemperature(model.id)) {
            body.put("temperature", temperature)
        }

        // Thinking / extended thinking. Two protocol shapes:
        //   - Claude 4.6+ (adaptive): thinking.type="adaptive" + output_config.effort.
        //     The legacy budget_tokens form is silently ignored on the wire, which is
        //     the exact "thinking enabled but no thoughts" symptom seen on 4.7.
        //   - Claude <=4.5 (legacy): thinking.type="enabled" + budget_tokens. For these
        //     Anthropic also requires temperature=1 on the request.
        // Claude 4.6+ rejects temperature outright (handled by the temperature guard
        // above; we don't re-inject it here).
        // [T-thinking-rules-phase2] WHICH shape applies is decided by
        // ThinkingRuleResolver.anthropicThinkingShape — one place owns every vendor's
        // thinking contract. Emission stays here because Android's Anthropic body also
        // carries `display`/`output_config`/`temperature` companions that the abstract
        // shape deliberately does not model. Behaviour is byte-for-byte unchanged, pinned
        // by ThinkingWireGeminiAnthropicSnapshotTest.
        val thinkShape = ThinkingRuleResolver.anthropicThinkingShape(
            model.id, model.supportsReasoning, thinkingLevel, maxTokens,
        )
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] provider=anthropic model=${model.id} level=${thinkingLevel.name} " +
                "shape=[${thinkShape.keys.sorted().joinToString(",")}]",
        )
        if (thinkingLevel.isEnabled) {
            if (modelUsesAdaptiveThinking(model.id)) {
                // [T-anthropic-thinking-display] Explicitly request summarized
                // thinking. This is the SECOND half of the "thinking on but no
                // thoughts shown" fix (first half: dropping the redact-thinking
                // beta, f24e3f07). adaptive thinking has a separate `display`
                // field: "summarized" returns readable thinking text; "omitted"
                // leaves the thinking block empty (only a signature) — identical
                // to being redacted. Newer models default `display` to "omitted"
                // (Opus 4.7+ / Fable-tier), so without setting it Claude Sonnet 5
                // etc. still show no thinking text even after the beta was
                // removed. Pin it to "summarized" regardless of the model default.
                body.put("thinking", JSONObject().apply {
                    put("type", "adaptive")
                    put("display", "summarized")
                })
                body.put("output_config", JSONObject().apply {
                    put("effort", thinkingEffort(thinkingLevel))
                })
            } else {
                val budgetTokens = thinkingBudget(maxTokens, thinkingLevel)
                if (budgetTokens > 0) {
                    body.put("thinking", JSONObject().apply {
                        put("type", "enabled")
                        put("budget_tokens", budgetTokens)
                    })
                    if (!modelRejectsTemperature(model.id)) {
                        body.put("temperature", 1)
                    }
                }
            }
        } else if (modelUsesAdaptiveThinking(model.id)) {
            // Adaptive-generation models (4.6+/5) think by DEFAULT when the
            // request carries no thinking field at all — "off" must be sent
            // explicitly, or small-maxTokens calls burn the whole budget on
            // thinking_tokens and return zero text (iOS ea86dd8a observed:
            // max_tokens=256 → stop_reason=max_tokens, thinking_tokens=256/256,
            // empty body). Legacy (<=4.5) models default to no thinking, so
            // absence is fine there.
            body.put("thinking", JSONObject().put("type", "disabled"))
        }

        // System prompt blocks — mirrors iOS AnthropicProvider.resolveSystemPrompt.
        val systemArray = resolveSystemPrompt(systemPrompt)
        if (systemArray != null) {
            body.put("system", systemArray)
        }

        // Tools: enable eager_input_streaming on every tool (GA feature, no beta
        // header required) so the SDK streams partial tool input JSON as it's
        // generated — mirrors iOS `RequestBodyPatcher.injectEagerInputStreaming`.
        // cache_control: ephemeral goes on the *last* tool, matching the first of
        // the four prompt-cache breakpoints Anthropic honors.
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for ((i, tool) in tools.withIndex()) {
                val toolJson = tool.toAnthropicJson()
                toolJson.put("eager_input_streaming", true)
                if (i == tools.lastIndex) {
                    toolJson.put("cache_control", ephemeralCacheControl())
                }
                toolsArray.put(toolJson)
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", JSONObject().put("type", "auto"))
        }

        // Build and merge messages
        val rawMessages = buildMessages(messages, imageParts)
        val merged = mergeConsecutiveSameRole(rawMessages)

        // Inject cache_control on last 2 user messages
        injectMessageCacheControl(merged)

        body.put("messages", merged)

        return body
    }

    /**
     * Drop `tool_result` parts whose `tool_use_id` does not match a `tool_use`
     * in the most recent assistant message. Anthropic rejects orphan
     * tool_results with 400 (`unexpected tool_use_id ... no corresponding
     * tool_use block`). Operates on a copy so the caller's stored history
     * (DB rows / in-memory state) is untouched — only the outbound payload
     * is sanitized. Mirrors iOS AnthropicAgentProvider.stripOrphanToolResults.
     */
    private fun stripOrphanToolResults(messages: List<LLMMessage>): List<LLMMessage> {
        var liveToolUseIds: Set<String> = emptySet()
        val result = ArrayList<LLMMessage>(messages.size)
        for ((i, msg) in messages.withIndex()) {
            when (msg.role) {
                LLMMessage.Role.ASSISTANT -> {
                    liveToolUseIds = msg.contentParts
                        .filterIsInstance<AgentContentPart.ToolUse>()
                        .map { it.id }
                        .toSet()
                    result.add(msg)
                }
                LLMMessage.Role.USER -> {
                    val original = msg.contentParts
                    if (original.isEmpty()) {
                        result.add(msg)
                    } else {
                        val kept = original.filter { part ->
                            if (part is AgentContentPart.ToolResult) {
                                liveToolUseIds.contains(part.id)
                            } else true
                        }
                        if (kept.size != original.size) {
                            val dropped = original.size - kept.size
                            android.util.Log.i(
                                "AnthropicProvider",
                                "Stripped $dropped orphan tool_result block(s) from outbound payload (idx=$i)"
                            )
                            result.add(msg.copy(contentParts = kept))
                        } else {
                            result.add(msg)
                        }
                    }
                    // Clear so a later user turn can't match a stale assistant.
                    liveToolUseIds = emptySet()
                }
            }
        }
        // Drop user messages that became empty (only orphan tool_results).
        // An empty content array is itself a 400.
        return result.filter { m ->
            // Keep messages that still have either non-empty contentParts or a
            // non-empty `content` string (string-only messages never had parts
            // to strip in the first place).
            m.contentParts.isNotEmpty() || m.content.isNotEmpty()
        }
    }

    /**
     * [T-android-anthropic-thinking-echo] (issue #70) Whether to echo prior
     * assistant `thinking` content blocks back into the history.
     *
     * Anthropic-compat proxies that speak the Anthropic protocol but reason
     * with interleaved (unsigned) thinking — DeepSeek V4 / GLM-4.5+ / Kimi
     * K2.5+ / Nemotron etc. — reject a multi-turn tool-use history where a
     * previous assistant turn produced thinking but that block isn't passed
     * back: 400 `content[].thinking in the thinking mode must be passed back
     * to the API`. The captured reasoning already lives on
     * `LLMMessage.reasoningContent`; we replay it as a synthesized
     * `{"type":"thinking","thinking":…}` block (no `signature` — we never
     * capture one off the wire).
     *
     * Mirrors iOS AnthropicAgentProvider's `echoReasoning` gate exactly:
     *   echo = supportsReasoning != false        (model may reason)
     *          AND interleavedReasoningField != null  (needs + tolerates
     *              UNSIGNED interleaved echo)
     *          AND !official Anthropic endpoint.
     * The `interleavedReasoningField` gate is load-bearing: the real
     * api.anthropic.com and strict Claude-class proxies verify the
     * `signature` field and reject an unsigned thinking block with
     * `thinking.signature: invalid_request_error`, so we must NOT echo there
     * even when supportsReasoning=true. Independent of the user's thinking
     * toggle — DeepSeek V4 emits thinking even when we don't request it.
     */
    private fun shouldEchoInterleavedThinking(): Boolean {
        val modelMayReason = model.supportsReasoning != false
        val modelRequiresInterleave = model.interleavedReasoningField != null
        return modelMayReason && modelRequiresInterleave && isCustomEndpoint
    }

    /** Build the messages JSONArray from LLMMessage list. */
    private fun buildMessages(
        rawMessages: List<LLMMessage>,
        imageParts: List<LLMMessage.ImagePart>,
    ): JSONArray {
        // Sanitize orphan tool_results before serialization. Operates on a
        // copy — caller's stored history is untouched.
        val messages = stripOrphanToolResults(rawMessages)
        // [T-android-anthropic-thinking-echo] (issue #70) Compute once — the
        // gate depends only on the model + endpoint, not the message.
        val echoThinking = shouldEchoInterleavedThinking()
        val messagesArray = JSONArray()
        for ((index, msg) in messages.withIndex()) {
            val obj = JSONObject()
            obj.put("role", msg.role.value)

            if (msg.contentParts.isNotEmpty()) {
                val contentArray = JSONArray()
                // [T-android-anthropic-thinking-echo] (issue #70) Prepend the
                // synthesized thinking block FIRST so it precedes text/tool_use
                // (Anthropic requires the thinking block to lead the assistant
                // turn). Replay captured reasoning verbatim; fall back to an
                // empty string as a field-presence placeholder for turns whose
                // reasoning was never captured (interrupted stream, history from
                // before thinking was on, cross-provider session) — without it
                // the compat proxy still 400s. Mirrors iOS
                // injectThinkingBlocksForCompatProxy (unsigned block, no
                // signature). User turns never carry thinking.
                if (echoThinking && msg.role == LLMMessage.Role.ASSISTANT) {
                    contentArray.put(JSONObject().apply {
                        put("type", "thinking")
                        put("thinking", msg.reasoningContent ?: "")
                    })
                }
                for (part in msg.contentParts) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            contentArray.put(JSONObject().apply {
                                put("type", "text")
                                put("text", part.text)
                            })
                        }
                        is AgentContentPart.ToolUse -> {
                            // Anthropic requires tool ids to match ^[a-zA-Z0-9_-]+$.
                            // OpenAI Responses can emit ids like `call_abc|fc_def`
                            // (pipe), so echo every id through sanitizeToolId.
                            contentArray.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", sanitizeToolId(part.id))
                                put("name", part.name)
                                put("input", part.input)
                            })
                        }
                        is AgentContentPart.ToolResult -> {
                            val resultContent = JSONArray()
                            resultContent.put(JSONObject().apply {
                                put("type", "text")
                                put("text", part.content)
                            })
                            if (part.imageData != null && part.imageMimeType != null) {
                                // T-imgsize: belt-and-braces — history tool-result
                                // screenshots can be raw 12-megapixel PNGs that
                                // never went through the composer's budget pass.
                                // Re-encode in-place if a single part already blows
                                // the per-image cap so we don't 413 on replay.
                                val safeBytes = ImageBudget.compressUnderBudget(part.imageData)
                                val safeMime = if (safeBytes === part.imageData) part.imageMimeType else "image/jpeg"
                                resultContent.put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", safeMime)
                                        put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                    })
                                })
                            }
                            contentArray.put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", sanitizeToolId(part.id))
                                put("content", resultContent)
                                if (part.isError) put("is_error", true)
                            })
                        }
                        is AgentContentPart.ImageData -> {
                            // T-imgsize: provider-boundary backstop for history
                            // image bytes that bypassed the composer budget
                            // (restored sessions, retry-after-edit, agent-emitted
                            // ImageData). Composer already capped fresh sends.
                            val safeBytes = ImageBudget.compressUnderBudget(part.data)
                            val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                            contentArray.put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", safeMime)
                                    put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                })
                            })
                        }
                    }
                }
                obj.put("content", contentArray)
            } else {
                val isLastUser = msg.role == LLMMessage.Role.USER &&
                    index == messages.indexOfLast { it.role == LLMMessage.Role.USER }
                if (isLastUser && imageParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    for (part in imageParts) {
                        // T-imgsize: backstop for the legacy ImagePart path used
                        // when contentParts is empty (older user-message shape
                        // pre-T132). Composer already runs ImageBudget but this
                        // arm is also reached on session restore where
                        // contentParts may not be populated.
                        val safeBytes = ImageBudget.compressUnderBudget(part.data)
                        val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                        contentArray.put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", safeMime)
                                put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                            })
                        })
                    }
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                    obj.put("content", contentArray)
                } else if (echoThinking && msg.role == LLMMessage.Role.ASSISTANT) {
                    // [T-android-anthropic-thinking-echo] (issue #70) Assistant
                    // turn with no structured parts (plain-text reply, or history
                    // rehydrated without contentParts). Still must carry its
                    // thinking block, so materialize the string content into an
                    // array with the thinking block first.
                    val contentArray = JSONArray()
                    contentArray.put(JSONObject().apply {
                        put("type", "thinking")
                        put("thinking", msg.reasoningContent ?: "")
                    })
                    if (msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }
            }

            messagesArray.put(obj)
        }
        return messagesArray
    }

    /**
     * Merge consecutive messages with the same role.
     * Anthropic API requires alternating user/assistant messages.
     * For assistant messages, reorder: text/image first, then tool_use (Anthropic
     * rejects text appearing after tool_use in the same message).
     */
    private fun mergeConsecutiveSameRole(messages: JSONArray): JSONArray {
        if (messages.length() <= 1) return messages
        val result = JSONArray()
        var i = 0
        while (i < messages.length()) {
            val current = messages.getJSONObject(i)
            val role = current.getString("role")
            // Collect all consecutive messages with same role
            val contentBlocks = mutableListOf<JSONObject>()
            collectContentBlocks(current, contentBlocks)
            var j = i + 1
            while (j < messages.length() && messages.getJSONObject(j).getString("role") == role) {
                collectContentBlocks(messages.getJSONObject(j), contentBlocks)
                j++
            }
            if (j > i + 1 || contentBlocks.isNotEmpty()) {
                // Reorder assistant content: thinking first, then text/image,
                // then tool_use last. Anthropic rejects text before thinking and
                // text after tool_use in the same turn; when several assistant
                // messages merge (T-android-anthropic-thinking-echo, issue #70)
                // this hoists every synthesized thinking block ahead of the text
                // so the merged turn still leads with thinking.
                if (role == "assistant") {
                    val thinking = contentBlocks.filter { it.optString("type") == "thinking" }
                    val other = contentBlocks.filter {
                        val t = it.optString("type")
                        t != "thinking" && t != "tool_use"
                    }
                    val toolUse = contentBlocks.filter { it.optString("type") == "tool_use" }
                    contentBlocks.clear()
                    contentBlocks.addAll(thinking)
                    contentBlocks.addAll(other)
                    contentBlocks.addAll(toolUse)
                }
                val merged = JSONObject().apply {
                    put("role", role)
                    put("content", JSONArray(contentBlocks.map { it }))
                }
                result.put(merged)
            } else {
                result.put(current)
            }
            i = j
        }
        return result
    }

    /** Extract content blocks from a message into a flat list. */
    private fun collectContentBlocks(msg: JSONObject, out: MutableList<JSONObject>) {
        val content = msg.opt("content")
        when (content) {
            is JSONArray -> {
                for (k in 0 until content.length()) {
                    out.add(content.getJSONObject(k))
                }
            }
            is String -> {
                out.add(JSONObject().apply {
                    put("type", "text")
                    put("text", content)
                })
            }
        }
    }

    /** Inject cache_control on the last 2 user messages' last content block. */
    private fun injectMessageCacheControl(messages: JSONArray) {
        var userCount = 0
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.getJSONObject(i)
            if (msg.getString("role") != "user") continue
            userCount++
            if (userCount > 2) break
            val content = msg.opt("content")
            if (content is JSONArray && content.length() > 0) {
                val lastBlock = content.getJSONObject(content.length() - 1)
                lastBlock.put("cache_control", ephemeralCacheControl())
            }
        }
    }

    companion object {
        /**
         * Parse the `-<major>-<minor>` (or `/<major>.<minor>`) version out of a Claude id.
         * [T-anthropic-temp-claude5-android] The minor segment is OPTIONAL and
         * defaults to 0: single-segment ids (claude-fable-5, claude-opus-5,
         * future claude-*-6) parse as (major, 0). Previously they returned
         * null, so modelRejectsTemperature said false and the API answered
         * 400 "temperature is deprecated for this model"; the same null also
         * blinded modelUsesAdaptiveThinking and supportsThinking for the
         * whole 5-series. The optional group is greedy, so a real pair like
         * claude-3-5-sonnet still wins as (3,5) — never (3,0).
         * Returns null for non-Claude ids or ids without any version digits.
         */
        private fun parseClaudeVersion(modelId: String): Pair<Int, Int>? {
            val lower = modelId.lowercase()
            if (!lower.contains("claude")) return null
            val regex = Regex("""[-/]?(\d+)(?:[-.](\d+))?(?:$|[^0-9])""")
            val match = regex.find(lower) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: 0
            return major to minor
        }

        /**
         * Claude models from 4.6 onward reject the `temperature` parameter.
         * Returns true when the model id parses as Claude version >= 4.6.
         */
        fun modelRejectsTemperature(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || (major == 4 && minor >= 6)
        }

        /**
         * Claude 4.6 and later use *adaptive* thinking (`thinking.type="adaptive"` plus
         * `output_config.effort = low|medium|high|xhigh|max`) and silently ignore the
         * older `thinking.type="enabled" + budget_tokens` form. Older Claude models still
         * need the legacy budget-based form.
         */
        fun modelUsesAdaptiveThinking(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || (major == 4 && minor >= 6)
        }

        /**
         * [T-android-claude-opus48-thinking-toggle] (Sow Sow 38845/38850) True
         * when this Claude model supports extended thinking — i.e. the Deep
         * Thinking toggle should appear for it. Anthropic added extended thinking
         * with Claude 3.7; every 4.x model (opus / sonnet / haiku) supports it.
         * Used to stamp `supportsReasoning = true` on models fetched from
         * `/v1/models` (which returns no capability metadata), so the toggle shows
         * on a direct-Anthropic instance even when models.dev hasn't catalogued
         * the model yet (e.g. brand-new Opus 4.8). The provider already builds the
         * correct `thinking` request for any such model (see
         * modelUsesAdaptiveThinking / the legacy budget path), so this is purely
         * the UI-capability flag those request paths assumed.
         */
        fun supportsThinking(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || major == 4 || (major == 3 && minor >= 7)
        }

        /** Calculate thinking budget tokens based on level (legacy <=4.5 protocol). */
        fun thinkingBudget(maxTokens: Int, level: ThinkingLevel): Int {
            val cap = when (level) {
                ThinkingLevel.OFF -> 0
                ThinkingLevel.LOW -> 8192
                ThinkingLevel.MEDIUM -> 32768
                ThinkingLevel.HIGH -> minOf(maxTokens, 65536)
                // [T-android-thinking-level-arch] XHIGH and above all take the
                // full token budget (already capped to maxTokens below).
                ThinkingLevel.XHIGH,
                ThinkingLevel.MAX,
                ThinkingLevel.ULTRA -> maxTokens
            }
            // Anthropic requires budget_tokens STRICTLY LESS THAN max_tokens; an
            // equal value is a 400 ("thinking.budget_tokens must be less than
            // max_tokens"). HIGH's min(maxTokens, 65536) and the top tiers both
            // clamp to exactly maxTokens, so pull back to maxTokens-1 when they
            // hit the ceiling. Affects the legacy budget_tokens path (pre-4.6
            // Claude + Anthropic-compatible relays without adaptive thinking).
            val clamped = minOf(cap, maxTokens)
            return if (clamped >= maxTokens && maxTokens > 1) maxTokens - 1 else clamped
        }

        /**
         * Map ThinkingLevel onto Anthropic adaptive thinking effort strings (Claude 4.6+).
         * `xhigh` maps to "max" — both 4.6 and 4.7 advertise "max" universally; 4.7 also
         * lists "xhigh" but we standardize on "max" for cross-version compatibility.
         */
        fun thinkingEffort(level: ThinkingLevel): String = when (level) {
            ThinkingLevel.OFF -> "low"  // unreached: caller checks isEnabled
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            // [T-android-thinking-level-arch] Anthropic tops out at "max"; XHIGH
            // and the new MAX/ULTRA tiers all collapse onto it.
            ThinkingLevel.XHIGH,
            ThinkingLevel.MAX,
            ThinkingLevel.ULTRA -> "max"
        }
    }

    /**
     * T302: takes the pre-serialized body string AND the JSONObject. The
     * string is used directly for the OkHttp RequestBody so we don't pay
     * for a second `body.toString()` (each call is tens of MB on long
     * agent loops). The JSONObject is still needed for header logic that
     * inspects fields like `thinking`. See OpenAIProvider.buildRequest
     * for the same pattern.
     */
    private fun buildRequest(bodyStr: String, body: JSONObject): Request {
        // T192: `basePath` may already end in `/v1` because
        // `ProviderInstance.effectiveBaseURL` appends `/v1` when
        // `appendV1Suffix=true` and the user-entered base doesn't end in `/v1`.
        // Without stripping here we'd produce `…/v1/v1/messages` for Anthropic-
        // compatible endpoints (DeepSeek, Bailian, etc.). Mirrors
        // `AnthropicProvider.swift::stripV1Suffix` (iOS) so the same custom
        // base URL works on both platforms.
        val cleanBase = basePath.trimEnd('/').let {
            if (it.endsWith("/v1")) it.dropLast(3).trimEnd('/') else it
        }
        val builder = Request.Builder()
            .url("$cleanBase/v1/messages")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")

        // OkHttp forbids multiple values for the same header slot via `.header(...)`,
        // so build a combined anthropic-beta string and set it once.
        //
        // For OAuth (Claude Code) credentials we mimic the real CLI as closely as
        // possible — Anthropic's backend uses the *combination* of anthropic-beta,
        // User-Agent, and X-Stainless-* headers to decide whether the request is
        // coming from the official CLI or a third-party client. Non-CLI requests
        // get downgraded (extra-usage billing, silently-disabled thinking on 4.7).
        // Aligned with sub2api FullClaudeCodeMimicryBetas / DefaultHeaders
        // (Wei-Shaw/sub2api backend/internal/pkg/claude/constants.go).
        //
        // For API-key / custom endpoints we only carry the betas actually needed
        // by the request body; we must NOT include oauth-2025-04-20 or
        // claude-code-20250219 there because those signal Claude-Code-only
        // surface and get rejected on plain API-key auth.
        val betaFlags = mutableListOf<String>()
        if (isOAuth) {
            // [T-anthropic-redact-thinking] Deliberately OMIT
            // "redact-thinking-2026-02-12" from the Claude-Code mimicry betas.
            // When present, Anthropic redacts the plaintext of `thinking` content
            // blocks (returns an empty `thinking` string with only a `signature`),
            // so a reasoning model runs (usage.thinking_tokens > 0) but the App
            // can't show any thinking text. The official Claude Code CLI only adds
            // this beta when `showThinkingSummaries` is unset/false (confirmed via
            // CLI de-obfuscation, anthropics/claude-code#31326, and the
            // code.claude.com model-config docs); omitting it is equivalent to
            // `showThinkingSummaries: true` — pure UI visibility, no effect on
            // reasoning quality or token budget. All other mimicry betas stay.
            betaFlags.addAll(listOf(
                "claude-code-20250219",
                "oauth-2025-04-20",
                "interleaved-thinking-2025-05-14",
                "prompt-caching-scope-2026-01-05",
                "effort-2025-11-24",
                "context-management-2025-06-27",
                "extended-cache-ttl-2025-04-11",
            ))
        } else if (body.has("thinking")) {
            val isAdaptive = body.optJSONObject("thinking")?.optString("type") == "adaptive"
            if (isAdaptive) {
                betaFlags.add("effort-2025-11-24")
            } else {
                betaFlags.add("interleaved-thinking-2025-05-14")
            }
        }
        // [T-android-enhanced-cache] API-key path only carries the betas the
        // body needs; the 1-hour cache TTL requires this flag. OAuth already
        // lists it above, so guard on !isOAuth to avoid a duplicate token.
        if (enhancedCache && !isOAuth) {
            betaFlags.add("extended-cache-ttl-2025-04-11")
        }
        if (betaFlags.isNotEmpty()) {
            builder.header("anthropic-beta", betaFlags.joinToString(","))
        }

        // Stainless / CLI fingerprint headers — only on OAuth.
        //
        // [T-anthropic-cli-version] The claude-cli version is resolved at
        // runtime by [ClaudeCliVersion] instead of being hardcoded: Anthropic
        // gates new models on a minimum CLI version (Fable 5.1 needed
        // >= 2.1.251, Opus 5.5 needs >= 2.1.280) and rejects older clients
        // with `claude_code_version_too_old`. A constant means every model
        // launch breaks OAuth users until the next app release (#301).
        if (isOAuth) {
            builder.header("User-Agent", ClaudeCliVersion.userAgent())
            builder.header("X-Stainless-Lang", "js")
            builder.header("X-Stainless-Package-Version", "0.106.0")
            builder.header("X-Stainless-OS", "Linux")
            builder.header("X-Stainless-Arch", "arm64")
            builder.header("X-Stainless-Runtime", "node")
            builder.header("X-Stainless-Runtime-Version", "v24.18.0")
            builder.header("X-Stainless-Retry-Count", "0")
            builder.header("X-Stainless-Timeout", "600")
            builder.header("X-App", "cli")
            builder.header("Anthropic-Dangerous-Direct-Browser-Access", "true")
        }

        if (isOAuth) {
            builder.header("Authorization", "Bearer $apiKey")
        } else if (apiKey.isEmpty()) {
            // [T-empty-key-compat-endpoints] Keyless third-party
            // Anthropic-compatible endpoint: send NO auth header rather than
            // a malformed `Bearer ` / empty x-api-key that strict relays
            // reject. Only reachable for custom-endpoint instances — routing
            // never builds a keyless provider for the official API.
        } else if (isCustomEndpoint) {
            builder.header("Authorization", "Bearer $apiKey")
        } else {
            builder.header("x-api-key", apiKey)
        }

        // [T-provider-custom-user-agent] Applied last so a non-blank override
        // wins over the OAuth claude-cli UA above. null/blank → fall back to
        // the branded Minis UA on the regular apiKey path, but on the OAuth
        // path keep the claude-cli fingerprint set above (the
        // Anthropic OAuth backend pairs UA + X-Stainless-* and rejects calls
        // whose UA doesn't match the registered client identity). T-android-
        // default-ua: pass defaultUserAgent=null on OAuth, branded default
        // everywhere else.
        builder.applyUserAgentOverride(
            customUserAgent,
            defaultUserAgent = if (isOAuth) null else com.openminis.app.provider.MinisUserAgent.DEFAULT,
        )
        return builder.build()
    }

    private fun parseResponse(json: JSONObject): LLMResponse {
        val content = json.optJSONArray("content")
        val text = buildString {
            if (content != null) {
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.safeOptString("type", "") == "text") {
                        append(block.safeOptString("text", ""))
                    }
                }
            }
        }
        val stopReason = json.safeOptString("stop_reason", "").ifEmpty { null }
        val usage = json.optJSONObject("usage")?.let { parseUsage(it) }
        return LLMResponse(text, stopReason, usage)
    }

    private fun parseUsage(json: JSONObject): LLMUsage {
        val inputTokens = json.optInt("input_tokens", 0)
        return LLMUsage(
            inputTokens = inputTokens,
            outputTokens = json.optInt("output_tokens", 0),
            cacheCreationInputTokens = json.optInt("cache_creation_input_tokens").takeIf { it > 0 },
            cacheReadInputTokens = json.optInt("cache_read_input_tokens").takeIf { it > 0 },
            latestContextTokens = inputTokens,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()

        val message = try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorType = error?.safeOptString("type", "") ?: "error"
            val errorMessage = error?.safeOptString("message", "") ?: body
            "[$errorType] $errorMessage"
        } catch (_: Exception) {
            "HTTP $statusCode: ${body.take(500)}"
        }

        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) {
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
