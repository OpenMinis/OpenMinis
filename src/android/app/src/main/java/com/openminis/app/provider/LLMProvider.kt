package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull

interface LLMProvider {
    val name: String
    var model: LLMModel

    /**
     * Effective max output tokens ceiling for the given model.
     * Priority: model.maxOutputTokens > provider-level default.
     * Used as the upper bound in dynamicMaxTokens().
     */
    fun effectiveMaxOutputTokens(model: LLMModel): Int =
        model.maxOutputTokens ?: defaultMaxOutputTokens

    /** Provider-level fallback when model.maxOutputTokens is unknown. */
    val defaultMaxOutputTokens: Int get() = 16_384

    /**
     * [T-android-tool-splits-reply-fix] True when the streamed assistant text
     * is one monolithic `content` string per response (OpenAI Chat
     * Completions): text deltas carry NO positional relationship to
     * tool_calls deltas — a non-streaming materialisation is always
     * {content, tool_calls} with content first — so ALL text deltas of one
     * streamed response belong to a single text block that precedes the tool
     * blocks. Some endpoints (qwen) flush trailing content chunks after
     * tool_calls deltas purely as a chunking artifact; reconstructing those
     * chronologically fabricates an order the wire format cannot express.
     * False for formats with genuinely ordered output blocks (Responses API
     * output items, Anthropic content blocks), where arrival order IS the
     * semantic block order.
     */
    val streamTextIsMonolithic: Boolean get() = false

    /**
     * [T-android-thinking-level-arch] PUBLIC entry — this is what every caller
     * (agent loop, fallback, quick test, model-use, …) invokes. It is NOT
     * overridden by providers: the default implementation clamps the requested
     * thinking level to the current [model]'s ceiling ONCE, then delegates to
     * [sendMessageClamped]. Making the clamp structural (rather than a call each
     * impl must remember) means no code path can send an over-range level — a
     * fallback that swapped [model] mid-flight clamps to the NEW model's limit
     * automatically. Mirrors iOS AgentProvider.streamAgentMessage → …Clamped.
     */
    suspend fun sendMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): LLMResponse = sendMessageClamped(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools,
        clampThinkingLevel(thinkingLevel),
    )

    /** See [sendMessage] — the clamped, provider-implemented counterpart. */
    fun streamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): Flow<LLMStreamChunk> = streamMessageClamped(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools,
        clampThinkingLevel(thinkingLevel),
    )

    /**
     * [T-android-thinking-level-arch] Provider implementations override THIS
     * (not [sendMessage]). The `thinkingLevel` received here has already been
     * clamped to the model's ceiling by [sendMessage] — implementations must NOT
     * re-clamp. Mirrors iOS `streamAgentMessageClamped`.
     */
    suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse

    /** See [sendMessageClamped]. */
    fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk>

    /**
     * [T-android-thinking-level-arch] Cap a requested thinking level to the
     * current [model]'s ceiling by rank. Used by the [sendMessage]/[streamMessage]
     * default entries above; the catalog ceiling is resolved off the live
     * `model`.
     */
    fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel {
        val ceiling = model.catalogMaxThinkingLevel
        return if (level.rank > ceiling.rank) ceiling else level
    }
}

/**
 * [T-android-empty-stream-retry] Detect a silently-truncated stream.
 *
 * When a relay/upstream drops the SSE connection without an error status,
 * the provider flow completes "normally" having emitted no content and no
 * finish reason — the chat just stops mid-air with no error (user report).
 * iOS treats this as a transient error at its stream-collection layer
 * (AIChatViewModel.isEmptyResponse → LLMError.transientError) and
 * auto-retries; this operator is the Android provider-layer equivalent.
 *
 * Empty = no text / thinking / reasoning / tool-call / media chunk AND no
 * finish reason. A stream that finished WITH a stop reason but no content
 * ("stop"/"end_turn") is deliberately let through — the agent loop's
 * empty-after-tool-result reminder path (ChatViewModel) owns that case, and
 * a "length"/"max_tokens" cut is a legitimate empty. Cancellation and
 * thrown errors propagate before the check runs (code after `collect`
 * only executes on normal completion).
 */
fun Flow<LLMStreamChunk>.failOnSilentEmptyCompletion(providerName: String): Flow<LLMStreamChunk> = flow {
    var sawContent = false
    var sawFinishReason = false
    collect { chunk ->
        when (chunk) {
            is LLMStreamChunk.Text -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ThinkingDelta -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ReasoningContent -> if (chunk.content.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ToolUseStart,
            is LLMStreamChunk.ToolInputDelta,
            is LLMStreamChunk.ToolCallComplete,
            is LLMStreamChunk.MediaAttachment -> sawContent = true
            is LLMStreamChunk.Finished -> if (chunk.stopReason != null) sawFinishReason = true
            else -> {}
        }
        emit(chunk)
    }
    if (!sawContent && !sawFinishReason) {
        android.util.Log.w(
            "LLMProvider",
            "$providerName: stream completed with no content and no finish reason — treating as transient upstream failure",
        )
        throw LLMError.TransientError("Server returned an empty response (connection dropped or upstream error)")
    }
}

/**
 * [T-compact-idle-timeout] Fail a stream that stops producing, while letting a
 * slow-but-alive one run as long as it needs.
 *
 * The distinction matters because the two are indistinguishable to a total
 * elapsed-time budget: a long transcript and a dead socket both just "take too
 * long". Measuring the GAP BETWEEN chunks separates them — a healthy stream
 * resets the clock thousands of times, a stuck one never resets it once — so a
 * limit can be strict about hangs without being a limit on length.
 *
 * [onTimeout] runs on the collecting coroutine and is expected to throw; it
 * takes the elapsed idle milliseconds so the caller can word its own error.
 *
 * Implemented over a channel rather than by racing a `delay()` against the
 * flow, so the timer is armed per element and the upstream is left running
 * untouched between elements. `coroutineScope` bounds the producer: normal
 * completion, a throw from [onTimeout], and cancellation all tear it down, so
 * no collection outlives the caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.withIdleTimeout(
    idleMs: Long,
    onTimeout: (Long) -> Unit,
): Flow<T> = flow {
    coroutineScope {
        val channel = produceIn(this)
        while (true) {
            // receiveCatching, not receive(): a normally-closed channel means
            // the stream ended, which is success, while a close CAUSED by an
            // upstream error carries that error and must be re-thrown so the
            // caller sees the provider's failure rather than a silent stop.
            val result = withTimeoutOrNull(idleMs) { channel.receiveCatching() }
            if (result == null) {
                onTimeout(idleMs)
                // Defensive: a caller whose onTimeout returns instead of
                // throwing would otherwise spin here forever.
                throw LLMError.TransientError(
                    "Stream idle for ${idleMs}ms with no new data"
                )
            }
            if (result.isClosed) {
                // A close with no cause is the stream ending normally; a close
                // WITH one is the provider's error, which must reach the caller
                // rather than looking like a clean finish.
                result.exceptionOrNull()?.let { throw it }
                return@coroutineScope
            }
            emit(result.getOrThrow())
        }
    }
}
