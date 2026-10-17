package com.openminis.app.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.ExperimentalCoroutinesApi

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
@OptIn(ExperimentalCoroutinesApi::class)
class UiUpdateBatcher(
    private val scope: CoroutineScope,
    private val updateFn: (String, String, Boolean, List<AssistantBlock>, Boolean) -> Unit,
    private val batchWindowMs: Long = 200L,
    private val consumerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class Update(
        val id: String,
        val content: String,
        val isStreaming: Boolean,
        val toolBlocks: List<AssistantBlock>,
        val isAwaitingModelResponse: Boolean = false,
    )

    private val queue = Channel<Update>(Channel.UNLIMITED)
    private val flushRequests = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private var consumerJob: Job? = null

    private sealed interface BatchEvent {
        data class Item(val update: Update) : BatchEvent
        data class Flush(val completion: CompletableDeferred<Unit>) : BatchEvent
        data object WindowElapsed : BatchEvent
        data object Closed : BatchEvent
    }

    /**
     * Start the consumer coroutine that pulls from the queue and pushes
     * batched updates to the main thread every 200ms.
     */
    fun start() {
        if (consumerJob?.isActive == true) return
        consumerJob = scope.launch(consumerDispatcher) {
            consumeUpdates()
        }
    }

    private suspend fun consumeUpdates() {
        var pending: Update? = null
        while (true) {
            val event = if (pending == null) {
                select {
                    queue.onReceiveCatching { result ->
                        result.getOrNull()?.let { BatchEvent.Item(it) } ?: BatchEvent.Closed
                    }
                    flushRequests.onReceive { BatchEvent.Flush(it) }
                }
            } else {
                // The timeout starts with the first item. Items arriving in
                // the window replace pending but never extend the window.
                withTimeoutOrNull(batchWindowMs) {
                    while (true) {
                        when (val incoming = select<BatchEvent> {
                            queue.onReceiveCatching { result ->
                                result.getOrNull()?.let { BatchEvent.Item(it) } ?: BatchEvent.Closed
                            }
                            flushRequests.onReceive { BatchEvent.Flush(it) }
                        }) {
                            is BatchEvent.Item -> pending = incoming.update
                            else -> return@withTimeoutOrNull incoming
                        }
                    }
                } ?: BatchEvent.WindowElapsed
            }

            when (event) {
                is BatchEvent.Item -> pending = event.update
                BatchEvent.WindowElapsed -> {
                    pending?.let { pushToMain(it) }
                    pending = null
                }
                is BatchEvent.Flush -> {
                    var latest = pending
                    while (true) {
                        val next = queue.tryReceive()
                        if (!next.isSuccess) break
                        latest = next.getOrThrow()
                    }
                    latest?.let { pushToMain(it) }
                    pending = null
                    event.completion.complete(Unit)
                }
                BatchEvent.Closed -> {
                    var latest = pending
                    while (true) {
                        val next = queue.tryReceive()
                        if (!next.isSuccess) break
                        latest = next.getOrThrow()
                    }
                    latest?.let { pushToMain(it) }
                    return
                }
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
        val job = consumerJob ?: return
        if (!job.isActive) return
        val completion = CompletableDeferred<Unit>()
        flushRequests.send(completion)
        completion.await()
    }

    /** Stop the consumer (call on cleanup or when the loop exits). */
    fun stop() {
        queue.close()
        consumerJob?.cancel()
    }

    private suspend fun pushToMain(update: Update) {
        withContext(Dispatchers.Main.immediate) {
            updateFn(update.id, update.content, update.isStreaming, update.toolBlocks, update.isAwaitingModelResponse)
        }
    }
}
