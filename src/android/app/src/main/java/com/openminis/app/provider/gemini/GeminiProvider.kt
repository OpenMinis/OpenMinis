package com.openminis.app.provider.gemini

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
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

class GeminiProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.gemini25Flash,
    private val basePath: String = "https://generativelanguage.googleapis.com/v1beta",
) : LLMProvider {
    override val name = "Google"

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
        val body = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val url = "$basePath/models/${model.id}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            // [T-android-default-ua] Brand the outbound UA so server logs
            // can trace the request back to the Minis build. Gemini has no
            // SDK-specific UA requirement, so the helper's default kicks in.
            .applyUserAgentOverride(null)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapHttpError(response.code, responseBody)
        }

        val json = JSONObject(responseBody)
        val text = extractText(json)
        val finishReason = extractFinishReason(json)
        val usage = extractUsage(json)
        val mediaAttachments = extractInlineMedia(json)
        LLMResponse(text, finishReason ?: "end_turn", usage, mediaAttachments)
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

    /**
     * [T-android-image-upload-format] One inline image as a Gemini part. Goes
     * through [ImageBudget.prepareForUpload] like every other provider: the
     * user-image and legacy image paths here used to inline the stored bytes
     * with their stored MIME — no byte budget at all — and the tool-result
     * path only had the budget. An image that cannot be made sendable becomes
     * a text part.
     */
    private fun inlinePart(data: ByteArray, declaredMime: String?): JSONObject {
        val upload = ImageBudget.prepareForUpload(data, declaredMime)
            ?: return JSONObject().put("text", ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
        return JSONObject().put("inlineData", JSONObject().apply {
            put("mimeType", upload.mimeType)
            put("data", upload.base64())
        })
    }

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val url = "$basePath/models/${model.id}:streamGenerateContent?alt=sse&key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            // [T-android-default-ua] same intent as the non-streaming
            // branch above — brand outbound requests with Minis/<version>.
            .applyUserAgentOverride(null)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw mapHttpError(response.code, errorBody)
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            var started = false
            var lastFinishReason: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val payload = l.removePrefix("data: ")

                val json = try { JSONObject(payload) } catch (_: Exception) { continue }

                if (!started) {
                    send(LLMStreamChunk.Started)
                    started = true
                }

                // Separate thought parts from text parts
                val (text, thinking) = extractTextAndThinking(json)
                if (thinking.isNotEmpty()) {
                    send(LLMStreamChunk.ThinkingDelta(thinking))
                }
                if (text.isNotEmpty()) {
                    send(LLMStreamChunk.Text(text))
                }

                // Extract function calls from streaming response
                val functionCalls = extractFunctionCalls(json)
                for ((fcName, fcArgs, fcSig) in functionCalls) {
                    val toolId = "gemini_${System.nanoTime()}"
                    send(LLMStreamChunk.ToolUseStart(toolId, fcName))
                    // [T-android-gemini3-thoughtsig / #179] Carry the part's
                    // thoughtSignature through so it can be persisted and replayed
                    // on the historical functionCall (gemini-3.x requires it).
                    send(LLMStreamChunk.ToolCallComplete(toolId, fcName, fcArgs, thoughtSignature = fcSig))
                }

                extractUsage(json)?.let { usage ->
                    send(LLMStreamChunk.Usage(usage))
                }

                extractFinishReason(json)?.let { reason ->
                    lastFinishReason = reason
                }
            }
            send(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))
        } catch (e: Exception) {
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose()
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        // [T-android-thinking-level-arch] Already clamped to the model ceiling by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val body = JSONObject()

        // [T-android-gemini3-thoughtsig / #179] Gemini 3.x REQUIRES a
        // thoughtSignature on every historical functionCall part; a bare
        // functionCall 400s ("Function call is missing a thought_signature").
        // Only gemini-3.x enforces this, so gate the whole special-case on it
        // to leave 1.x/2.x/flash behaviour byte-for-byte unchanged.
        val requiresSig = model.id.lowercase().contains("gemini-3")
        // Tool-call ids we CANNOT safely replay as a functionCall because their
        // signature is missing (old sessions, non-3.x-origin history, dropped
        // field). For these, both the functionCall AND its paired functionResponse
        // are downgraded to plain-text summaries so no unsigned functionCall is
        // ever sent. Mirrors iOS convertMessages `unsignedToolCallIds`.
        //
        // [T-android-gemini3-parallel-thoughtsig] The set is computed PER
        // MESSAGE and covers the whole message once any of its calls is
        // unsigned — not per call.
        //
        // Gemini emits ONE thoughtSignature for a parallel batch, on the FIRST
        // functionCall only; the rest of the batch legitimately has none. The
        // per-call rule therefore replayed call #1 as a signed functionCall and
        // downgraded #2..#5 to text, splitting a batch the signature describes
        // as a whole. Gemini rejected that with
        // `400 "Corrupted thought signature."` — reproduced on a Pixel 6:
        // gemini-3.8-flash, five parallel subagent_task calls in one message,
        // exactly one with a signature (len 4096) and four null, failing on
        // every retryLast after a restart.
        //
        // A signature belongs to the model turn, so the turn is the unit that
        // can be replayed or downgraded. All-or-nothing per message keeps a
        // fully-signed turn on the fast path and sends a mixed one entirely as
        // text, which is always accepted.
        val unsignedToolCallIds: Set<String> = if (!requiresSig) emptySet() else buildSet {
            for (msg in messages) {
                val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                if (toolUses.isEmpty()) continue
                // One unsigned call condemns the whole message's batch: sending
                // the signed remainder alone is precisely what Gemini calls
                // corrupted.
                if (toolUses.any { it.thoughtSignature.isNullOrEmpty() }) {
                    toolUses.forEach { add(it.id) }
                }
            }
        }

        val contents = JSONArray()
        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            val role = if (msg.role == LLMMessage.Role.USER) "user" else "model"
            val content = JSONObject()
            content.put("role", role)

            val parts = JSONArray()

            if (msg.contentParts.isNotEmpty()) {
                for (part in msg.contentParts) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            // Gemini rejects {"text": ""} with oneof 400; skip empty text parts.
                            if (part.text.isNotEmpty()) {
                                parts.put(JSONObject().put("text", part.text))
                            }
                        }
                        is AgentContentPart.ToolUse -> {
                            if (part.id in unsignedToolCallIds) {
                                // [T-android-gemini3-thoughtsig / #179] No signature
                                // to replay → downgrade to text rather than send a
                                // bare functionCall (which gemini-3.x 400s on).
                                // [T-gemini-unsigned-narration] As plain prose, not a
                                // bracketed marker the model would imitate.
                                parts.put(JSONObject().put("text", narratedToolCall(part.name, part.input)))
                            } else {
                                parts.put(JSONObject().apply {
                                    // [T-android-gemini3-thoughtsig / #179] The
                                    // signature is a sibling of functionCall on the
                                    // part object, not nested inside it.
                                    if (requiresSig && !part.thoughtSignature.isNullOrEmpty()) {
                                        put("thoughtSignature", part.thoughtSignature)
                                    }
                                    put("functionCall", JSONObject().apply {
                                        put("name", part.name)
                                        put("args", part.input)
                                    })
                                })
                            }
                        }
                        is AgentContentPart.ToolResult -> {
                            if (part.id in unsignedToolCallIds) {
                                // [T-android-gemini3-thoughtsig / #179] Its paired
                                // functionCall became text, so this result must too —
                                // a functionResponse with no matching functionCall is
                                // itself a 400.
                                // [T-gemini-unsigned-narration] Prose again (see
                                // narratedToolResult), and the image a result
                                // carried still goes along, as on the signed path.
                                parts.put(JSONObject().put("text", narratedToolResult(part.content, part.isError)))
                                val trBytes = part.imageData
                                if (trBytes != null && trBytes.isNotEmpty()) {
                                    parts.put(inlinePart(trBytes, part.imageMimeType))
                                }
                            } else {
                                val responseObj = JSONObject()
                                responseObj.put("name", part.name)
                                val responseContent = JSONObject()
                                val safeContent = part.content.ifEmpty { " " }
                                responseContent.put("result", safeContent)
                                if (part.isError) responseContent.put("error", true)
                                responseObj.put("response", responseContent)
                                parts.put(JSONObject().put("functionResponse", responseObj))
                                // [T-android-toolresult-image-dropped] functionResponse
                                // carries only the `result` string, so read_image's
                                // pixels were dropped here exactly as in the OpenAI
                                // paths. Gemini takes heterogeneous parts in one turn,
                                // so the bytes go straight after as inlineData.
                                val trBytes = part.imageData
                                if (trBytes != null && trBytes.isNotEmpty()) {
                                    parts.put(inlinePart(trBytes, part.imageMimeType))
                                }
                            }
                        }
                        is AgentContentPart.ImageData -> {
                            parts.put(inlinePart(part.data, part.mimeType))
                        }
                    }
                }
            } else {
                // Legacy: plain text with optional images
                if (index == lastUserIndex && imageParts.isNotEmpty()) {
                    for (part in imageParts) {
                        parts.put(inlinePart(part.data, part.mimeType))
                    }
                }
                val legacyText = msg.content.ifEmpty { " " }
                parts.put(JSONObject().put("text", legacyText))
            }
            // [T-gemini-empty-part-oneof-400] Parity with iOS convertMessages: a
            // turn whose only content was an empty .Text (skipped above) would
            // otherwise emit an empty parts[] → Gemini 400 ("contents[N].parts
            // must not be empty"). Fall back to a placeholder so parts is never
            // empty. Matches iOS's "(empty)" fallback.
            if (parts.length() == 0) {
                parts.put(JSONObject().put("text", "(empty)"))
            }
            content.put("parts", parts)
            contents.put(content)
        }
        body.put("contents", contents)

        // [OpenMinis#226] Audio-output models reject systemInstruction with 400
        // "Developer instruction is not enabled for this model", the same way they reject
        // a thinking config. Keyed off the declared output modality (the property actually
        // responsible) rather than the model id. Dropping it costs nothing: the preamble
        // is guidance for a text responder, and a TTS model speaks `contents` verbatim.
        val rejectsSystemInstruction = "audio" in model.outputModalities.orEmpty()
        if (systemPrompt != null && !rejectsSystemInstruction) {
            body.put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemPrompt))
            ))
        }

        // Tools
        //
        // [T-android-gemini-tool-field] The wrapper key is `functionDeclarations`,
        // camelCase — the REST JSON spelling. `function_declarations` is the
        // proto/gRPC field name; Google's own endpoint accepts either, which is
        // why this went unnoticed, but relays that transcode the request do not:
        // a control-variable probe against the same Flash model returned a real
        // `functionCall` for camelCase and TOOL_UNAVAILABLE for snake_case, and
        // one native streaming request came back HTTP 200 with `parts: []` and
        // finishReason STOP — the "model returned an empty response" the user
        // sees. iOS has always sent camelCase (GeminiProvider.swift:193, :262),
        // which is why the same account works there and not here.
        //
        // toolConfig goes with it for the same reason: iOS sends an explicit
        // AUTO functionCallingConfig on both the streaming and non-streaming
        // paths. Omitting it leaves the calling mode to the endpoint's default,
        // and a relay that defaults differently can decline to call tools at all
        // while still answering 200.
        if (tools.isNotEmpty()) {
            val funcDecls = JSONArray()
            for (tool in tools) {
                funcDecls.put(tool.toGeminiJson())
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", funcDecls)))
            body.put(
                "toolConfig",
                JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")),
            )
        }

        val config = JSONObject()
        config.put("maxOutputTokens", maxTokens)
        if (temperature != null) {
            config.put("temperature", temperature)
        }

        // Thinking configuration (model-specific)
        buildThinkingConfig(thinkingLevel)?.let { thinkingConfig ->
            config.put("thinkingConfig", thinkingConfig)
        }

        // Response modalities — required for Gemini to actually emit inlineData
        // image/audio parts. Without this, image-generation models return only
        // text tokens and the caller's --output file stays empty. Mirrors iOS
        // GeminiProvider.swift:401-407.
        val outputs = model.outputModalities.orEmpty()
        when {
            "audio" in outputs -> config.put("responseModalities", JSONArray().put("AUDIO"))
            "image" in outputs -> config.put(
                "responseModalities",
                JSONArray().put("TEXT").put("IMAGE"),
            )
        }

        body.put("generationConfig", config)

        return body
    }

    /**
     * Build model-specific thinking config.
     * - Gemini 3.x: uses thinkingLevel string + includeThoughts
     * - Gemini 2.5 Pro: uses thinkingBudget (128-16384)
     * - Gemini 2.5 Flash: uses thinkingBudget (0-8192)
     * - Gemini 2.5 Flash Lite: no thinking support
     */
    private fun buildThinkingConfig(level: ThinkingLevel): JSONObject? {
        // [T-thinking-rules-phase2] The per-family rules now live in
        // ThinkingRuleResolver.geminiThinkingConfig so every vendor's thinking contract
        // is described in ONE place. Behaviour is byte-for-byte unchanged — verified by
        // a reflection cross-check against this method's previous body (0 diffs across
        // 10 models x 7 levels) and pinned by ThinkingWireGeminiAnthropicSnapshotTest.
        val cfg = ThinkingRuleResolver.geminiThinkingConfig(model.id, level)
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] provider=gemini model=${model.id} level=${level.name} " +
                "keys=[${cfg?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}]",
        )
        return cfg
    }

    /** Extract text from all non-thought parts. */
    private fun extractText(json: JSONObject): String {
        return extractTextAndThinking(json).first
    }

    /** Separate thought parts (thought=true) from regular text parts. */
    private fun extractTextAndThinking(json: JSONObject): Pair<String, String> {
        val candidates = json.optJSONArray("candidates") ?: return "" to ""
        val first = candidates.optJSONObject(0) ?: return "" to ""
        val content = first.optJSONObject("content") ?: return "" to ""
        val parts = content.optJSONArray("parts") ?: return "" to ""

        val textBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val text = part.safeOptString("text", "")
            if (text.isEmpty()) continue
            if (part.optBoolean("thought", false)) {
                thinkingBuilder.append(text)
            } else {
                textBuilder.append(text)
            }
        }
        return textBuilder.toString() to thinkingBuilder.toString()
    }

    /**
     * Extract inline binary media (images/audio) from response candidate parts.
     * Mirrors iOS GeminiProvider.extractResponseContent (GeminiProvider.swift:529-549) —
     * Gemini returns generated images as `inlineData: { mimeType, data (base64) }`.
     */
    private fun extractInlineMedia(json: JSONObject): List<LLMMediaAttachment> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val out = mutableListOf<LLMMediaAttachment>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: continue
            val mime = inline.safeOptString("mimeType", "")
            val b64 = inline.safeOptString("data", "")
            if (mime.isEmpty() || b64.isEmpty()) continue
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Throwable) {
                android.util.Log.d("ModelUseImage", "gemini inlineData base64 decode failed: ${e.message}")
                continue
            }
            val type = when {
                mime.startsWith("image/") -> LLMMediaAttachment.MediaType.IMAGE
                mime.startsWith("audio/") -> LLMMediaAttachment.MediaType.AUDIO
                mime.startsWith("video/") -> LLMMediaAttachment.MediaType.VIDEO
                else -> LLMMediaAttachment.MediaType.IMAGE  // iOS fallback
            }
            android.util.Log.d("ModelUseImage", "gemini inlineData received: mime=$mime bytes=${bytes.size}")
            out.add(LLMMediaAttachment(type, mime, bytes))
        }
        return out
    }

    /**
     * [T-android-gemini3-thoughtsig / #179] Returns (name, args, thoughtSignature)
     * per functionCall part. The signature lives as a sibling `thoughtSignature`
     * field on the SAME part object as `functionCall` (not inside it), per the
     * Gemini v1beta wire format. Null when absent (non-3.x models, or a chunk
     * without a signature).
     */
    private fun extractFunctionCalls(json: JSONObject): List<Triple<String, JSONObject, String?>> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val calls = mutableListOf<Triple<String, JSONObject, String?>>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val fc = part.optJSONObject("functionCall") ?: continue
            val name = fc.safeOptString("name", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            val sig = part.safeOptString("thoughtSignature", "").ifEmpty { null }
            if (name.isNotEmpty()) calls.add(Triple(name, args, sig))
        }
        return calls
    }

    private fun extractFinishReason(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val reason = first.safeOptString("finishReason", "").ifEmpty { return null }
        return when (reason) {
            "STOP" -> "end_turn"
            "MAX_TOKENS" -> "max_tokens"
            else -> reason.lowercase()
        }
    }

    /**
     * Parse `usageMetadata` into [LLMUsage]. [GH#384]
     *
     * Google's shape differs from this app's convention in two ways:
     *
     * 1. `candidatesTokenCount` counts ONLY the visible answer. Gemini 3.x
     *    bills thinking separately in `thoughtsTokenCount`, the API keeping
     *    `totalTokenCount = prompt + candidates + thoughts`, so reading
     *    candidates alone drops every thinking token. Captured on
     *    gemini-3.8-flash: prompt 11, candidates 20, thoughts 310 — 94% of the
     *    output went unreported.
     *
     * 2. `promptTokenCount` is the FULL input, cache included, while
     *    `inputTokens` here means the FRESH part and the cached part travels in
     *    `cacheReadInputTokens` (same as [OpenAIProvider]'s extractUsage).
     *    Reporting the full prompt AND the cache double-counts it: hit rate is
     *    `cacheRead / (input + cacheRead)`, so a real 91.8% hit (45026 of
     *    49016) would render as 47.8%. `latestContextTokens` keeps the full
     *    prompt, since that IS the context size.
     *
     * `cachedContentTokenCount` appears only on the FINAL chunk of a stream, so
     * most chunks legitimately report no cache; null (not 0) marks "this chunk
     * said nothing about caching".
     */
    private fun extractUsage(json: JSONObject): LLMUsage? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        return parseUsageMetadata(usage)
    }

    companion object {
        /**
         * [T-gemini-unsigned-narration] How much of a downgraded tool result
         * to inline. Large outputs are offloaded upstream already, so this
         * only bounds genuinely inline results (was 1000 here, 500 on iOS).
         */
        const val NARRATED_RESULT_LIMIT = 2000

        /**
         * [T-gemini-unsigned-narration] Port of iOS 1a33fc85f. Gemini 3.x
         * needs a thoughtSignature on every historical functionCall; a call
         * without one (made by another model before a switch, or older than
         * signature capture) is sent as history TEXT instead. That text was
         * `[Called shell_execute with: {...}]` - a bracketed pseudo-marker the
         * model reads as part of the transcript and imitates: it starts
         * writing `[Called ...]` as its answer instead of calling the tool.
         * Plain prose carries the same facts with no syntax worth copying.
         */
        internal fun narratedToolCall(name: String, input: JSONObject): String =
            "Earlier in this conversation, the $name tool was run with arguments ${canonicalJson(input)}."

        /** Follows the call's sentence, so it names no tool of its own. */
        internal fun narratedToolResult(content: String, isError: Boolean = false): String {
            val lead = if (isError) "It failed" else "It returned"
            if (content.isEmpty()) return if (isError) "It failed with no output." else "It returned no output."
            if (content.length <= NARRATED_RESULT_LIMIT) return "$lead:\n$content"
            // Head kept; the cut is stated in words, not with a marker.
            return "$lead (showing the first $NARRATED_RESULT_LIMIT of ${content.length} characters):\n" +
                content.take(NARRATED_RESULT_LIMIT)
        }

        /** JSON with object keys sorted, so identical history gives an identical prompt (cache stability). */
        internal fun canonicalJson(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted()
                .joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonicalJson(value.opt(k)) }
            is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            // numberToString throws on a non-finite value; a replayed call must never fail the request build.
            is Number -> runCatching { JSONObject.numberToString(value) }.getOrDefault("null")
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

        /** Exposed for tests and for any other endpoint speaking this protocol. */
        fun parseUsageMetadata(usage: JSONObject): LLMUsage {
            val promptTokens = usage.optInt("promptTokenCount", 0)
            val candidatesTokens = usage.optInt("candidatesTokenCount", 0)
            val thoughtsTokens = usage.optInt("thoughtsTokenCount", 0)
            // Absent or 0 both mean "no cache to report" — keep null so a
            // cache-less chunk stays distinguishable from a measured zero.
            val cachedTokens = usage.optInt("cachedContentTokenCount", 0).takeIf { it > 0 }

            val totalOutput = candidatesTokens + thoughtsTokens
            // Clamped: a relay reporting a cache larger than the prompt must not
            // yield a negative input count.
            val freshInput = cachedTokens?.let { (promptTokens - it).coerceAtLeast(0) } ?: promptTokens

            return LLMUsage(
                inputTokens = freshInput,
                outputTokens = totalOutput,
                cacheCreationInputTokens = null,
                cacheReadInputTokens = cachedTokens,
                latestContextTokens = promptTokens,
            )
        }
    }

    private fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()
        val message = "Gemini API error $statusCode: ${body.take(200)}"
        val transientCodes = setOf(500, 502, 503, 504, 529)
        // [T-android-503-fallback] Status carried through for group fallback.
        if (statusCode in transientCodes) return LLMError.TransientError(message, httpStatus = statusCode)
        return LLMError.ProviderError(message, httpStatus = statusCode)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
