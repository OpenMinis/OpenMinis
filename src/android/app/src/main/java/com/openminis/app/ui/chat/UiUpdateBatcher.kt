package com.openminis.app.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-perf-ui-batch] UI update batcher: queue non-text UI mutations during the
 * agent loop's collect phase, then push them to the main thread in batches
 * (200ms window) instead of pushing every single chunk. Text deltas already
 * have a 50ms throttle with newline-flush; this batcher handles the rest
 * (ThinkingDelta updates, ToolUseStart, ToolCallComplete block pushes).
 *
 * Background: every `withContext(Dispatchers.Main) { updateAssistantMessage(...) }`
 * is a context switch + potential Compose recomposition. High-frequency small
 * updates (dozens of thinking deltas / tool starts in <100ms) can create
 * frame-drop during heavy stream bursts. Batching reduces context switches and
 * lets Compose coalesce recompositions.
 *
 * Usage:
 *   val batcher = UiUpdateBatcher(viewModelScope, ::updateAssistantMessage)
 *   // in collect loop:
 *   batcher.enqueue(assistantId, messageSnapshot, isStreaming, allToolBlocks.toList())
 *   // on loop exit:
 *   batcher.flush()
 *   batcher.stop()
 */
class UiUpdateBatcher(
    private val scope: CoroutineScope,
    private val updateFn: (String, String, Boolean, List<AssistantBlock>, Boolean) -> Unit,
) {
    private data class Update(
        val id: String,
        val content: String,
        val isStreaming: Boolean,
        val toolBlocks: List<AssistantBlock>,
        val isAwaitingModelResponse: Boolean = false,
    )

    private val queue = Channel<Update>(Channel.UNLIMITED)
    private var consumerJob: Job? = null
    private var lastPushMs = 0L

    /**
     * Start the consumer coroutine that pulls from the queue and pushes
     * batched updates to the main thread every 200ms.
     */
    fun start() {
        consumerJob = scope.launch(Dispatchers.Default) {
            var pending: Update? = null
            while (true) {
                // Wait for the first item or until the batch window expires
                val item = if (pending == null) {
                    queue.receiveCatching().getOrNull() ?: break
                } else {
                    val nowMs = System.currentTimeMillis()
                    val elapsed = nowMs - lastPushMs
                    if (elapsed >= 200L) {
                        // Batch window expired — push immediately
                        pushToMain(pending)
                        pending = null
                        lastPushMs = System.currentTimeMillis()
                        continue
                    } else {
                        // Wait for the remainder of the window
                        delay(200L - elapsed)
                        // After delay, try to receive more (non-blocking peek)
                        queue.tryReceive().getOrNull()
                    }
                }
                if (item == null) {
                    // Channel closed or delay expired without new item
                    if (pending != null) {
                        pushToMain(pending)
                        pending = null
                        lastPushMs = System.currentTimeMillis()
                    }
                    continue
                }
                // Coalesce: keep the LATEST update for this assistant message
                pending = item
            }
        }
    }

    /**
     * Enqueue a UI update. The consumer will coalesce repeated updates to the
     * same message id and push the latest snapshot after the batch window.
     */
    fun enqueue(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        queue.trySend(Update(id, content, isStreaming, toolBlocks, isAwaitingModelResponse))
    }

    /**
     * Flush any pending update immediately (used when the stream ends or the
     * loop is interrupted).
     */
    suspend fun flush() {
        // Send a sentinel to wake the consumer, then wait briefly for it to drain
        queue.close()
        consumerJob?.join()
    }

    /** Stop the consumer (call on cleanup or when the loop exits). */
    fun stop() {
        queue.close()
        consumerJob?.cancel()
    }

    private suspend fun pushToMain(update: Update) {
        withContext(Dispatchers.Main) {
            updateFn(update.id, update.content, update.isStreaming, update.toolBlocks, update.isAwaitingModelResponse)
        }
    }
}
