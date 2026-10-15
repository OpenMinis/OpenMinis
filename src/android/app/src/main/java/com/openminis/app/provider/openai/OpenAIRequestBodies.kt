package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.thinking.ThinkingLadder
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal class OpenAIRequestBodies(
    private val host: Host,
) {
    interface Host {
        val model: LLMModel
        val basePath: String
        val extraHeaders: Map<String, String>
        val codexAccountId: String?
        val useResponsesAPI: Boolean
        val forceChatCompletions: Boolean
        val customUserAgent: String?
        val isAzure: Boolean
        val isOAuth: Boolean
        val chatExtraBody: Map<String, Any?>
        val chatExtraHeaders: Map<String, String>
        val absoluteEndpointOverride: String?
        val isOpenRouter: Boolean
        val needsOpenRouterAnthropicCacheControl: Boolean
        val isMistral: Boolean
        val isDashScope: Boolean
        val isXAI: Boolean
        val usesUnifiedReasoningEffort: Boolean
        val thinkingRuleInstanceId: String?
        /** True when this instance points at the Zen host with no API key —
         *  the bundled free lane, not a user's paid Zen provider. */
        val isZenFree: Boolean
        fun resolvedServiceTier(): String?
        fun endpointURL(defaultPath: String): String
        fun azureUrl(path: String): String?
        suspend fun getToken(): String
        fun explicitOffEffort(): String?
        fun applyKeyAuth(builder: Request.Builder, token: String): Request.Builder
        val codexClientVersion: String
        fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel
        val provider: com.openminis.app.provider.LLMProvider
    }

    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        host.applyKeyAuth(this, token)

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
        val supportsImages = host.model.hasImageInput
        val body = JSONObject()
        body.put("model", host.model.id)
        if (host.isOpenRouter) {
            body.put("max_tokens", maxTokens)
        } else {
            body.put("max_completion_tokens", maxTokens)
        }
        // [T-zen-stream-only-lane] The Zen free lane REFUSES stream=false with
        // 403 FreeTierError — live-measured 2026-10-05 on-device via adb:
        // stream=true answers 200 on the same session id, model and body, while
        // the identical request with stream=false answers
        // {"type":"error","error":{"type":"FreeTierError",…}} — byte-identical
        // to a request carrying no disguise at all, which is why this read as a
        // session-identity wall. The non-streaming caller
        // [OpenAIProvider.sendMessageClamped] already concatenates SSE deltas
        // into a single response, so forcing stream=true here changes nothing
        // for the caller while passing the lane's only accepted shape.
        val streamForced = if (host.isZenFree) true else stream
        body.put("stream", streamForced)

        // [T-android-xai-priority] xAI Priority Processing, driven by the same
        // app-level Fast Mode toggle as Codex (FastModePrefs), read here at
        // request-build time so a flip applies to the very next request.
        // Emitted only for xAI-capable providers, so every other provider's
        // body is unchanged — `service_tier` is an xAI extension and a strict
        // OpenAI-compatible relay would 400 on the unknown key.
        host.resolvedServiceTier()?.let { body.put("service_tier", it) }

        SamplingPolicy.wire(
            SamplingIdentity.of(host.provider), host.model.id, temperature, thinkingLevel.isEnabled,
        )?.let { body.put("temperature", it) }

        if (streamForced && !host.isOpenRouter) {
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
        //
        // [T-zen-free-thinking-budget] The Zen free lane is the exception
        // that replaces the field entirely: it ignores reasoning_effort
        // (live-measured: `low` produced MORE reasoning than `xhigh`, and
        // unknown fields occasionally surfaced as a 503). The only control
        // it enforces is max_completion_tokens, so the thinking level maps
        // to a real generation budget that REPLACES the caller's maxTokens
        // instead of a field the gateway drops. Only the bundled keyless
        // instance qualifies; a user's own paid Zen provider keeps the
        // normal reasoning_effort path untouched.
        if (host.isZenFree) {
            zenBudgetTokens(thinkingLevel, maxTokens)?.let { body.put("max_completion_tokens", it) }
        } else if (!host.isMistral) {
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
        if (host.needsOpenRouterAnthropicCacheControl) {
            body.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        // OpenCode Zen's anonymous lane validates a small tool gate even when
        // the caller has no tools. Keep this scoped to the Zen host; ordinary
        // OpenAI-compatible providers must retain their existing body shape.
        val effectiveTools = if (com.openminis.app.provider.ZenDisguise.isZenHost(host.basePath)) {
            val names = tools.map { it.name }.toSet()
            val gateTools = listOf(
                AgentToolDefinition("bash", "Reserved for the host runtime; do not call it.", emptyMap()),
                AgentToolDefinition("read", "Reserved for the host runtime; do not call it.", emptyMap()),
            )
            tools + gateTools.filter { it.name !in names }
        } else tools

        // Tools
        if (effectiveTools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in effectiveTools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", if (com.openminis.app.provider.ZenDisguise.isZenHost(host.basePath) && tools.isEmpty()) "none" else "auto")
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
        val modelAlwaysReasons = host.model.supportsReasoning == true
        val modelMayReason = host.model.supportsReasoning ?: true
        // [T-android-mistral-reasoning-422] Mistral forbids reasoning_content on
        // assistant messages entirely (closed schema → HTTP 422
        // extra_forbidden), so suppress BOTH the captured echo and the ""
        // placeholder for that endpoint. This cannot be driven by capability
        // metadata: MiMo/DeepSeek require the field's PRESENCE on multi-turn
        // history while Mistral forbids it, and neither advertises
        // supportsReasoning via /v1/models — opposite requirements on the same
        // generic openAI provider path. Hence a spec-driven vendor flag.
        val forbidReasoningField = host.isMistral
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
        if (host.chatExtraBody.isEmpty()) return
        if (host.isOAuth && !host.forceChatCompletions) return  // Codex OAuth exemption
        for ((k, v) in host.chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        body.put("model", host.model.id)
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
    internal suspend fun buildRequest(bodyStr: String): Request {
        val token = host.getToken()

        if (host.isOAuth && !host.forceChatCompletions) {
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
                .header("Version", host.codexClientVersion)
                .header("Openai-Beta", "responses=experimental")
                .header("User-Agent", "codex_cli_rs/${host.codexClientVersion} (Android; arm64)")
                .header("Originator", "codex_cli_rs")
            host.codexAccountId?.let { builder.header("Chatgpt-Account-Id", it) }
            // [T-provider-custom-user-agent] Applied last so a non-blank
            // override wins over the Codex default UA. In practice null on
            // this OAuth path (the UI only exposes it for custom-base
            // instances), so this is a no-op there.
            // [T-android-default-ua] `defaultUserAgent = null` — keep the
            // codex_cli_rs fingerprint set above when no per-provider
            // override is configured. We must NOT fall back to the branded
            // Minis UA here: the ChatGPT OAuth backend validates the
            // client identity against this header.
            builder.applyUserAgentOverride(host.customUserAgent, defaultUserAgent = null)
            return builder.build()
        }

        val endpointPath = if (host.useResponsesAPI) "/responses" else "/chat/completions"
        // [T-android-azure-openai] Azure routes via the deployments path and
        // auths with the api-key header. azureUrl() returns null when not in
        // Azure mode / no base, so the standard basePath join stays the default.
        // [T-android-model-use-passthrough-mode] endpointURL() honors an
        // absolute-path override ("/...") that replaces the whole path on the
        // provider host; otherwise Azure/basePath as before.
        val requestUrl = when {
            host.absoluteEndpointOverride?.startsWith("/") == true -> host.endpointURL(endpointPath)
            host.isAzure -> host.azureUrl(endpointPath) ?: "${host.basePath}$endpointPath"
            else -> "${host.basePath}$endpointPath"
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
        for ((key, value) in host.extraHeaders) {
            builder.header(key, value)
        }
        // [T-android-model-use-passthrough-mode] Per-call chat header overrides,
        // applied AFTER the ctor extraHeaders → same-name REPLACE over any
        // default (incl. Authorization/Content-Type). Empty on normal calls.
        for ((key, value) in host.chatExtraHeaders) {
            builder.header(key, value)
        }
        // [T-provider-custom-user-agent] Covers both chat/completions and
        // /responses (this builder serves both). Applied after extraHeaders
        // so the per-provider override wins. null/blank → default UA.
        builder.applyUserAgentOverride(host.customUserAgent)
        return builder.build()
    }

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
        val lid = host.model.id.lowercase()
        // [T-fallback-thinking-preclamp] Match the FAMILY substring, not one
        // spelling: catalog docs say "MiMo-2.5" but the live API returns
        // "mimo-v2.5" / "mimo-v2.5-pro", which the old "mimo-2.5" match missed
        // (mirrors iOS 72968c4f).
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    /**
     * [T-zen-free-thinking-budget] The generation ceiling one thinking level
     * carries on the Zen free lane. The gateway ignores every effort field it
     * is given and enforces ONLY max_completion_tokens, so a level here is a
     * real, enforced budget rather than a hint — a menu that did nothing
     * would be worse than no menu at all (design principle carried over from
     * the desktop reference implementation).
     *
     * The ceiling is shared by thinking and the visible answer: a lower level
     * shortens both. A model whose thinking cannot be switched off pays for
     * its reasoning out of the same ceiling before the answer starts, so its
     * rungs are scaled UP — measured on mimo-v2.6-flash-free over one day, 82%
     * of output tokens were reasoning, and the unscaled ceiling ended long
     * turns in `length` about every third request.
     *
     * Returns null when this model exposes no effort menu (supportsReasoning
     * is not true), so the caller's own maxTokens stays on the wire untouched.
     *
     * [T-thinking-ladder-shared] The ladder itself is NOT here any more — it
     * lives in [ThinkingLadder], shared with the Gemini / Qwen / Anthropic
     * numeric-budget sites. The old table lived here as seven ABSOLUTE
     * constants, which is precisely what cannot work on a lane whose models
     * have wildly different ceilings: on big-pickle / mimo-v2.5 / mimo-v2.6
     * (capacity 32000) and ling-3.1 (32768) — four REAL models in the device's
     * own Zen free catalogue — the top rungs all clipped to the same number,
     * so dragging the slider changed nothing on the wire.
     *
     * WHY PROPORTIONAL. Each rung is now a FRACTION of the model's OWN output
     * capacity (1/32, 2/32, 4/32, 8/32, 14/32, 22/32, 30/32) instead of a
     * constant. That is what makes the ladder strictly increasing for EVERY
     * capacity: a constant that sits below one model's ceiling is above
     * another's, but a fraction of that same ceiling is always below it.
     *
     * WHY THE FRACTIONS ARE UNEVEN. A linear ramp (1/7..6/7) would spend the
     * user's tokens almost evenly. Reasoning cost grows SUPERLINEARLY with
     * depth, so the ladder doubles early and tapers late — the same shape as
     * the measured must-think behaviour (82% of output tokens were reasoning).
     * ULTRA stops at 30/32 so it approaches the ceiling without ever being
     * pinned to it. OFF is the smallest rung and deliberately not a duplicate
     * of LOW: it exists to leave room for the ANSWER only, while LOW must buy
     * real reasoning room above it.
     *
     * MUST-THINK IS A RESCALE, NOT A MULTIPLICATION. `multiplier = 2` divides
     * the FRACTIONS, shifting the whole ladder down by one step's worth of
     * headroom instead of doubling the resulting numbers. Doubling the numbers
     * (`budget * 2`) would push XHIGH/MAX/ULTRA straight past the ceiling,
     * where `minOf(..., capacity)` clamps them all back to the same value —
     * reintroducing byte-for-byte the collapse this change removes, on exactly
     * the mimo models that motivated it.
     *
     * [UNMEASURED] The fractions are reasoned, not swept. The claim that THESE
     * steps raise reasoning quality on this lane is NOT measured — this
     * gateway may just truncate differently at a bigger ceiling. Strictly
     * increasing and strictly below capacity is the part that is provably
     * right; the exact steps need an on-device sweep (reasoning tokens and
     * `length` finish rate per level) before being called tuned.
     */
    private fun zenBudgetTokens(level: ThinkingLevel, maxTokens: Int): Int? {
        if (host.model.supportsReasoning != true) return null
        // [T-thinking-ladder-shared] capacity MUST be the model's own max
        // output tokens — scaling a ladder against the caller's arbitrary
        // maxTokens would make the same level produce a different budget
        // depending on who asked for it, and would destroy proportionality.
        val capacity = host.model.maxOutputTokens ?: maxTokens
        val lid = host.model.id.lowercase()
        // [T-zen-mimo-must-think] The `mimo-v2.5` arm is NOT dead code even though
        // commit 56b7437 dropped `mimo-v2.5-free` from the bundled SEED: this lane
        // follows the LIVE catalogue (`isZenFreeLaneId` = any `-free` suffix, see
        // ProviderRepository [T-zen-free-lane-follow]), so an upstream
        // re-advertisement of a mimo-v2.5* id lands here and would silently lose
        // its must-think rescale if the arm were removed. Pinned by
        // ZenFreeThinkingBudgetTest's `mimo-v2p5 …` cases. Keep both arms.
        val mustThink = lid.startsWith("mimo-v2.6") || lid.startsWith("mimo-v2.5")
        return ThinkingLadder.budgetFor(level, capacity, if (mustThink) 2 else 1)
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
            modelId = host.model.id,
            instanceId = host.thinkingRuleInstanceId,
            supportsReasoning = host.model.supportsReasoning,
            declaredEffortValues = host.model.reasoningEffortValues,
            budgetTokensMin = host.model.budgetTokensMin,
            budgetTokensMax = host.model.budgetTokensMax,
            // [OpenMinis#163] null (catalog silent) must read as false here —
            // only an affirmative declaration may suppress the field.
            declaresNoEffortTiers = host.model.declaresNoEffortTiers == true,
            level = host.clampThinkingLevel(level),
            maxTokens = maxTokens,
            isOpenRouter = host.isOpenRouter,
            usesUnifiedReasoningEffort = host.usesUnifiedReasoningEffort,
            isMistral = host.isMistral,
            isDashScope = host.isDashScope,
            isXAI = host.isXAI,
            offEffort = host.explicitOffEffort(),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        // [T-thinking-rules-observability] Design §8 / GH OpenMinis#100: which rule
        // actually won must be inspectable, or a rule layer just replaces one hidden
        // variable with a more complicated one. minis-config exposure is Phase 2.
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${host.model.id} level=${level.name} ${trace.logLine}",
        )
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
        temperature: Double? = null,
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
        val supportsImages = host.model.hasImageInput
        val body = JSONObject()
        body.put("model", host.model.id)
        body.put("stream", stream)
        SamplingPolicy.wire(
            SamplingIdentity.of(host.provider), host.model.id, temperature, thinkingLevel.isEnabled,
        )?.let { body.put("temperature", it) }
        // [T-android-xai-priority] xAI documents service_tier for both text
        // inference endpoints. xAI currently always resolves to the Chat
        // Completions path (forceChatCompletions), so this is belt-and-braces
        // — but keeping the two builders in step means a future routing change
        // does not silently drop the user's Fast Mode choice.
        host.resolvedServiceTier()?.let { body.put("service_tier", it) }
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
        if (host.isOAuth) {
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
            host.isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            host.isOAuth -> body.put(
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
            !thinkingLevel.isEnabled && host.model.supportsReasoning == true &&
                !host.model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                host.explicitOffEffort()?.let { offEffort ->
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
        if (maxTokens > 0 && !host.isOAuth) {
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
            host.model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        // Tools — flat shape required by Responses API ({type, name, description,
        // parameters}), distinct from Chat Completions' wrapped {type, function:{...}}.
        // The Zen Responses lane requires tool_choice=auto.
        val effectiveResponseTools = if (com.openminis.app.provider.ZenDisguise.isZenHost(host.basePath)) {
            val names = tools.map { it.name }.toSet()
            tools + listOf(
                AgentToolDefinition("bash", "Reserved for the host runtime; do not call it.", emptyMap()),
                AgentToolDefinition("read", "Reserved for the host runtime; do not call it.", emptyMap()),
            ).filter { it.name !in names }
        } else tools
        if (effectiveResponseTools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in effectiveResponseTools) {
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
        // [T-android-responses-orphan-tool-output] Last gate on the tool
        // pairing, over the ids this request actually carries (split on "|"
        // and capped). Without it a stranded or duplicated tool result 400s
        // every send until the chat is cleared. See ResponsesToolPairing.
        val paired = ResponsesToolPairing.sanitize(input)
        if (paired.changed) {
            AppLogger.warning(
                "OpenAIRequestBodies",
                "[sanitize-responses] repaired outgoing tool pairing: " +
                    "droppedOrphanOutputs=${paired.droppedOrphanOutputs} " +
                    "droppedDuplicateOutputs=${paired.droppedDuplicateOutputs} " +
                    "placeholderCalls=${paired.placeholderCalls} items=${input.length()}",
            )
        }
        body.put("input", paired.items)

        // [T-android-model-use-passthrough-mode GH#72] Same verbatim merge as the
        // chat-completions builder. Skipped for Codex OAuth inside mergeChatExtraBody.
        mergeChatExtraBody(body)

        return body
    }

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

}
