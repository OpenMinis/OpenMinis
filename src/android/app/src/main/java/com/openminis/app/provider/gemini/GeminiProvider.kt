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
        val unsignedToolCallIds: Set<String> = if (!requiresSig) emptySet() else buildSet {
            for (msg in messages) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolUse && part.thoughtSignature.isNullOrEmpty()) {
                        add(part.id)
                    }
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
                                // to replay → downgrade to a text summary rather than
                                // send a bare functionCall (which gemini-3.x 400s on).
                                val argsDesc = part.input.toString().let {
                                    if (it.length > 500) it.take(500) + "…" else it
                                }
                                parts.put(JSONObject().put("text", "[Called ${part.name} with: $argsDesc]"))
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
                                val safe = part.content.ifEmpty { " " }.let {
                                    if (it.length > 1000) it.take(1000) + "…" else it
                                }
                                val prefix = if (part.isError) "Error from" else "Result of"
                                parts.put(JSONObject().put("text", "[$prefix ${part.name}: $safe]"))
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
                                    val safeBytes = ImageBudget.compressUnderBudget(trBytes)
                                    val safeMime = if (safeBytes === trBytes) {
                                        part.imageMimeType ?: "image/jpeg"
                                    } else "image/jpeg"
                                    parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                        put("mimeType", safeMime)
                                        put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                    }))
                                }
                            }
                        }
                        is AgentContentPart.ImageData -> {
                            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", part.mimeType)
                                put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                            }))
                        }
                    }
                }
            } else {
                // Legacy: plain text with optional images
                if (index == lastUserIndex && imageParts.isNotEmpty()) {
                    for (part in imageParts) {
                        val inlineData = JSONObject()
                        inlineData.put("mimeType", part.mimeType)
                        inlineData.put("data", Base64.encodeToString(part.data, Base64.NO_WRAP))
                        parts.put(JSONObject().put("inlineData", inlineData))
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
        if (tools.isNotEmpty()) {
            val funcDecls = JSONArray()
            for (tool in tools) {
                funcDecls.put(tool.toGeminiJson())
            }
            body.put("tools", JSONArray().put(JSONObject().put("function_declarations", funcDecls)))
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

    private fun extractUsage(json: JSONObject): LLMUsage? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        return LLMUsage(
            inputTokens = usage.optInt("promptTokenCount", 0),
            outputTokens = usage.optInt("candidatesTokenCount", 0),
        )
    }

    private fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()
        val message = "Gemini API error $statusCode: ${body.take(200)}"
        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) return LLMError.TransientError(message)
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
