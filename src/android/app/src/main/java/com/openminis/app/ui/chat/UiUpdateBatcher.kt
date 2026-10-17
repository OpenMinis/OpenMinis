package com.openminis.app.ui.chat

import java.util.concurrent.ConcurrentHashMap
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
 *   // when a bubble is finalized mid-loop (queued-prompt inject):
 *   batcher.seal(oldAssistantId)
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

    // [T-android-batcher-seal] Seal request. `seal(id)` sends one of these and
    // waits for `done`; the consumer handles it on the same dispatcher that
    // pushes, so every push already committed for `id` has finished by the
    // time the seal returns.
    private class SealRequest(val id: String, val done: CompletableDeferred<Unit>)

    private val queue = Channel<Update>(Channel.UNLIMITED)
    private val flushRequests = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
    private val sealRequests = Channel<SealRequest>(Channel.UNLIMITED)

    /**
     * [T-android-batcher-seal] Retired ids. Consulted at three points — when an
     * Update is coalesced, when the window expires, and again inside
     * [pushToMain] — so a seal that lands while the consumer sits in its batch
     * window can never resurrect the bubble.
     */
    private val sealedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var consumerJob: Job? = null

    private sealed interface BatchEvent {
        data class Item(val update: Update) : BatchEvent
        data class Flush(val completion: CompletableDeferred<Unit>) : BatchEvent
        data class Seal(val id: String, val done: CompletableDeferred<Unit>) : BatchEvent
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

    private suspend fun nextEvent(): BatchEvent = select {
        queue.onReceiveCatching { result ->
            result.getOrNull()?.let { BatchEvent.Item(it) } ?: BatchEvent.Closed
        }
        flushRequests.onReceive { BatchEvent.Flush(it) }
        sealRequests.onReceive { BatchEvent.Seal(it.id, it.done) }
    }

    private suspend fun consumeUpdates() {
        var pending: Update? = null
        while (true) {
            val event = if (pending == null) {
                nextEvent()
            } else {
                // The timeout starts with the first item. Items arriving in
                // the window replace pending but never extend the window.
                withTimeoutOrNull(batchWindowMs) {
                    while (true) {
                        when (val incoming = nextEvent()) {
                            is BatchEvent.Item ->
                                if (incoming.update.id !in sealedIds) pending = incoming.update
                            is BatchEvent.Seal ->
                                if (applySeal(incoming, pending)) pending = null
                            else -> return@withTimeoutOrNull incoming
                        }
                    }
                } ?: BatchEvent.WindowElapsed
            }

            when (event) {
                is BatchEvent.Item ->
                    if (event.update.id !in sealedIds) pending = event.update
                is BatchEvent.Seal ->
                    if (applySeal(event, pending)) pending = null
                BatchEvent.WindowElapsed -> {
                    val latest = pending
                    pending = null
                    latest?.let { pushToMain(it) }
                }
                is BatchEvent.Flush -> {
                    var latest = pending
                    while (true) {
                        val next = queue.tryReceive()
                        if (!next.isSuccess) break
                        val candidate = next.getOrThrow()
                        if (candidate.id !in sealedIds) latest = candidate
                    }
                    pending = null
                    latest?.let { pushToMain(it) }
                    event.completion.complete(Unit)
                }
                BatchEvent.Closed -> {
                    var latest = pending
                    while (true) {
                        val next = queue.tryReceive()
                        if (!next.isSuccess) break
                        val candidate = next.getOrThrow()
                        if (candidate.id !in sealedIds) latest = candidate
                    }
                    pending = null
                    latest?.let { pushToMain(it) }
                    return
                }
            }
        }
    }

    /**
     * Apply one seal: retire [id], drop the coalesced [pending] update when it
     * targets that id, and release the waiting [BatchEvent.Seal]. Returns true
     * when the caller's `pending` reference must be cleared.
     */
    private fun applySeal(event: BatchEvent.Seal, pending: Update?): Boolean {
        sealedIds.add(event.id)
        event.done.complete(Unit)
        return pending?.id == event.id
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
     * [T-android-batcher-seal] Permanently retire a message id mid-loop.
     * After this returns, no further Main-thread push will target `id`:
     * pushes that already executed are done, the still-coalesced one for `id`
     * is dropped, and the tombstone set drops everything enqueued after.
     *
     * Why: the queued-prompt inject path finalizes the interrupted bubble
     * (isStreaming=false) while this batcher keeps running for the next
     * turn. A coalesced isStreaming=true update still sitting in the
     * consumer would land up to 200ms AFTER that seal and resurrect the
     * old thinking box (it re-expands and never collapses — the id never
     * receives another update). flush()/stop() can't be used here: they
     * close the channel permanently and the loop still needs it.
     *
     * The tombstone is set BEFORE the request is sent, so the guarantee holds
     * even if the consumer never answers. The 1s await cap only trips if the
     * consumer died without closing the queue (an already-broken loop); it
     * degrades to the tombstone alone.
     */
    suspend fun seal(id: String) {
        sealedIds.add(id)
        val job = consumerJob
        if (job == null || !job.isActive) return
        val done = CompletableDeferred<Unit>()
        if (sealRequests.trySend(SealRequest(id, done)).isSuccess) {
            withTimeoutOrNull(1000L) { done.await() }
        }
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
        // Tombstone re-check at push time: the id may have been sealed while
        // the consumer sat in the batch window with the update coalesced.
        if (update.id in sealedIds) return
        withContext(Dispatchers.Main.immediate) {
            updateFn(update.id, update.content, update.isStreaming, update.toolBlocks, update.isAwaitingModelResponse)
        }
    }
}
