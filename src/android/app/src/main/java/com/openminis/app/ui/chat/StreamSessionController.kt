package com.openminis.app.ui.chat

import com.openminis.app.sandbox.kernel.UIBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the streaming side-channel + dual-path flush that used to live inside
 * [ChatViewModel]. Behavior is unchanged: live tokens write [_streamingById],
 * canonical [_messages] is only mutated on structural/error/end paths.
 */
internal class StreamSessionController(
    private val scope: CoroutineScope,
    private val messages: MutableStateFlow<List<ChatMessage>>,
    private val streamingById: MutableStateFlow<Map<String, StreamingDelta>>,
    private val newlineFlushMinChars: Int,
    private val newlineFlushMaxLen: Int,
) {
    /**
     * [T-android-stream-flush-dualpath] Per-message streaming-flush state for
     * the dual-path throttle in [updateAssistantMessage]. Keyed by messageId so
     * the throttle accumulator survives the high-frequency token calls (the
     * earlier per-fragment produceState version reset every fragment rebuild and
     * so never actually throttled — diagnostics showed every tick flushing).
     * Mirrors iOS AIChatViewModel+SSEStream's lastTextDeltaFlush/…Length.
     */
    private class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        // The `st.trailingJob === coroutineContext[Job]` ownership guards in
        // updateAssistantMessage are only sound because every read/write of
        // this field happens on one thread: `scope` is viewModelScope
        // (Dispatchers.Main.immediate), so ticks and trailing jobs are
        // serialized and never interleave mid-statement. Move this state
        // off-main and the guard becomes a plain data race needing a CAS or a
        // mutex instead.
        var trailingJob: Job? = null
        // [T-android-stream-flush-review] Freshest suppressed delta. Updated on
        // EVERY throttled tick so the trailing job publishes the latest content
        // (not the stale value captured when the job was first scheduled) — a
        // burst of sub-throttle deltas followed by a pause would otherwise leave
        // the side channel several deltas behind.
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }

    private val streamFlushStates = HashMap<String, StreamFlushState>()

    /**
     * [T-android-stream-flush-review] Cancel a message's pending trailing flush
     * and drop its throttle accumulator. Call from EVERY stream-termination
     * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
     * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
     * therefore NOT cancelled by streamJob.cancel() — can't fire after the
     * side channel was drained and re-revive a stale "thinking" overlay row.
     */
    fun clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    fun clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }

    /** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
    fun retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    // Dual-path flush thresholds — ported from iOS. Time tiers scale with total
    // length; the newline fast-path flushes immediately on a line break once
    // enough new chars have accumulated, gated to short docs so dense
    // box-drawing streams don't pin the flush rate to the per-token cadence.
    fun streamFlushThrottleMs(len: Int): Long = when {
        len < 500 -> 200L
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

    fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        // T-streaming-side-channel: during a live turn, write high-frequency
        // fields into [_streamingById] instead of mutating the canonical
        // message list. This keeps the `messages` StateFlow reference stable
        // across the turn so ChatScreen's top-level reads
        // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
        // a full recompose of the 8980-line composable on every token.
        //
        // On stream end (isStreaming=false), drain the accumulated delta
        // back into the canonical message in a single `_messages` emit, then
        // clear the side-channel entry so post-turn reads (history rebuild,
        // persist, agent loop) see the canonical truth.
        if (isStreaming) {
            val toolBlocksImmutable = snapshotAssistantBlocks(toolBlocks)

            // [T-android-stream-flush-dualpath] Dual-path flush at the
            // message-accumulation layer (NOT per-fragment, which never
            // throttled). Decide whether to publish this delta now:
            //   • structural change (toolBlocks count / awaiting flag) →
            //     publish immediately — these drive tool-bubble UI and must
            //     never be coalesced away or the bubble state stalls.
            //   • else time-path: enough ms since last publish for this length.
            //   • else newline fast-path: a line break in the newly-streamed
            //     chunk + ≥50 new chars, gated to short docs (iOS parity).
            // When none fire, stash the latest as a trailing publish so the
            // final chunk before a pause still lands; a fresh delta cancels
            // and replaces it.
            val st = streamFlushStates.getOrPut(id) {
                StreamFlushState().also { it.lastFlushedLen = 0 }
            }
            val prev = streamingById.value[id]
            // [T-android-stream-flush-review] Structural change also covers an
            // in-place tool-block STATUS flip (running → success), not just a
            // count change — otherwise a spinner→checkmark could lag up to one
            // throttle tier. Compare a cheap (kind,status) fingerprint.
            val toolStatusChanged = prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
                }
            val toolContentChanged = prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].content != toolBlocksImmutable[i].content
                }
            val structuralChange = prev == null ||
                prev.toolBlocks.size != toolBlocksImmutable.size ||
                prev.isAwaitingModelResponse != isAwaitingModelResponse ||
                toolStatusChanged ||
                toolContentChanged
            val now = System.currentTimeMillis()
            val elapsed = now - st.lastFlushMs
            val throttle = streamFlushThrottleMs(content.length)
            val newChunk = if (content.length > st.lastFlushedLen) {
                content.substring(st.lastFlushedLen.coerceAtMost(content.length))
            } else ""
            val unflushed = content.length - st.lastFlushedLen
            val newlineFlush = content.length < newlineFlushMaxLen &&
                newChunk.contains('\n') &&
                unflushed >= newlineFlushMinChars

            /**
             * @return true when the delta reached the side channel now, false
             * when the UI bus was full and it was parked for the trailing job.
             * A caller running INSIDE a trailing job must not clear state on the
             * false path — see [T-android-stream-flush-reentry].
             */
            fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean): Boolean {
                val at = System.currentTimeMillis()
                if (!UIBus.admit(at, criticalEvent = false)) {
                    st.pendingContent = text
                    st.pendingBlocks = blocks
                    st.pendingAwaiting = awaiting
                    val wait = UIBus.waitMs(at, criticalEvent = false).coerceAtLeast(16L)
                    // [T-android-stream-flush-reentry] Reaching this branch from
                    // inside a trailing job means st.trailingJob IS the job now
                    // executing. Cancelling it is harmless (nothing suspends
                    // after this point), but the enclosing body then used to run
                    // its own cleanup: nulling the freshly scheduled job handle
                    // and its pendingContent. The replacement job fired, read
                    // null, and returned — so the last throttled delta vanished
                    // from the side channel until the next delta or the stream
                    // end, with nothing in the logs.
                    st.trailingJob?.cancel()
                    st.trailingJob = scope.launch {
                        delay(wait)
                        val pc = st.pendingContent ?: run {
                            // Nothing left to flush. Release the handle only if
                            // a re-entrant park has not already replaced it —
                            // the `trailingJob == null` guard otherwise refuses
                            // to schedule the next delta and parked text would
                            // sit unpublished until the stream ends.
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                            return@launch
                        }
                        if (publish(pc, st.pendingBlocks, st.pendingAwaiting)) {
                            st.pendingContent = null
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                        }
                    }
                    return false
                }
                streamingById.value = streamingById.value + (
                    id to StreamingDelta(
                        content = text,
                        toolBlocks = snapshotAssistantBlocks(blocks),
                        isAwaitingModelResponse = awaiting,
                    )
                )
                st.lastFlushMs = System.currentTimeMillis()
                st.lastFlushedLen = text.length
                return true
            }

            if (structuralChange || elapsed >= throttle || newlineFlush) {
                st.trailingJob?.cancel()
                st.trailingJob = null
                st.pendingContent = null
                publish(content, toolBlocksImmutable, isAwaitingModelResponse)
            } else {
                // Throttled: always record this delta as the freshest pending
                // value, so whenever the trailing job fires it publishes the
                // latest text — not whatever was captured when it was first
                // scheduled (review #2). Schedule the job only once.
                st.pendingContent = content
                st.pendingBlocks = toolBlocksImmutable
                st.pendingAwaiting = isAwaitingModelResponse
                if (st.trailingJob == null) {
                    val wait = (throttle - elapsed).coerceAtLeast(16L)
                    st.trailingJob = scope.launch {
                        delay(wait)
                        val pc = st.pendingContent ?: run {
                            // Nothing left to flush. Release the handle only if
                            // a re-entrant park has not already replaced it —
                            // the `trailingJob == null` guard otherwise refuses
                            // to schedule the next delta and parked text would
                            // sit unpublished until the stream ends.
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                            return@launch
                        }
                        // Only clear state this coroutine still owns.
                        if (publish(pc, st.pendingBlocks, st.pendingAwaiting)) {
                            st.pendingContent = null
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                        }
                    }
                }
            }
            // [T-android-timeout-while-running] If a transient banner
            // (`message.error`) is still on the canonical assistant message
            // when a fresh streaming event arrives, the banner is stale —
            // the model is producing again, by construction the prior
            // transient timeout / retry / fallback has been resolved.
            // Clear it in the same mutation. setTransientInlineError /
            // setInlineError are the only paths that write `error`; the
            // terminal path (setInlineError) sets isStreaming=false on the
            // same message in the same emit, so it cannot reach this
            // branch and the clear is safe.
            //
            // 𝙓𝙄𝙉 TG36302 (0.10): user saw a red "timeout / retry" banner
            // glued to the bottom of the conversation while the agent
            // continued running (LM Studio tool loop on 30/30, "Minis is
            // thinking" indicator). Caused by (a) the fallback-switch branch in
            // runAgentLoop not calling clearInlineError(), and (b) the
            // streaming-side-channel writing every subsequent delta into
            // _streamingById without ever touching _messages where
            // `error` lives. (a) is fixed at the fallback site; (b) is
            // fixed here defensively so any future write-path that forgets
            // to clear can't strand a stale banner across the rest of
            // the turn.
            val canonical = messages.value
            val canonicalIdx = canonical.indexOfLast { it.id == id }
            if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
                val updated = canonical.toMutableList()
                updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
                messages.value = snapshotChatMessages(updated)
            }
            return
        }
        // [T-android-stream-flush-dualpath] Stream end → cancel any pending
        // trailing flush and drop the throttle accumulator for this message;
        // the canonical drain below publishes the final, complete text.
        clearStreamFlushState(id)
        // Stream end → sync delta into canonical message + clear side-channel.
        val current = messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) {
            // The message itself is gone (e.g. clearChat raced ahead) —
            // just clear any leftover stream delta and bail.
            if (streamingById.value.containsKey(id)) {
                streamingById.value = streamingById.value - id
            }
            return
        }
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = content,
            isStreaming = false,
            toolBlocks = snapshotAssistantBlocks(toolBlocks),
            isAwaitingModelResponse = isAwaitingModelResponse,
        )
        messages.value = snapshotChatMessages(updated)
        if (streamingById.value.containsKey(id)) {
            streamingById.value = streamingById.value - id
        }
    }

    /**
     * Read a message's content + toolBlocks honoring any active streaming
     * delta. Use this from non-render code that needs the "current" view of
     * a message during a live turn (e.g. agent history builders, persistence
     * snapshots) without forcing the render layer to consult the delta map.
     */
    fun effectiveContent(id: String): String? {
        val delta = streamingById.value[id]
        if (delta != null) return delta.content
        return messages.value.firstOrNull { it.id == id }?.content
    }

    /**
     * Force-drain any outstanding streaming delta for [id] back into the
     * canonical message and clear the side-channel slot. Called from turn
     * exit paths (cancel / error / retry / resume / clearChat) so the
     * canonical message reflects all accumulated content even if the last
     * [updateAssistantMessage] call had isStreaming=true.
     */
    fun flushStreamingDelta(id: String) {
        val delta = streamingById.value[id] ?: return
        val current = messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            messages.value = snapshotChatMessages(updated)
        }
        // [T-android-stream-flush-review] Cancel the pending trailing flush
        // BEFORE clearing the side channel — otherwise its viewModelScope
        // coroutine (not cancelled by streamJob.cancel) fires later and
        // re-adds the orphan side-channel entry, reviving a stale "thinking"
        // row after the turn was stopped/drained.
        clearStreamFlushState(id)
        streamingById.value = streamingById.value - id
    }

    /** Drain ALL outstanding streaming deltas (called on global resets). */
    fun flushAllStreamingDeltas() {
        clearAllStreamFlushStates()
        val pending = streamingById.value
        if (pending.isEmpty()) return
        val current = messages.value.toMutableList()
        var changed = false
        for ((id, delta) in pending) {
            val idx = current.indexOfLast { it.id == id }
            if (idx < 0) continue
            current[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            changed = true
        }
        if (changed) messages.value = snapshotChatMessages(current)
        streamingById.value = emptyMap()
    }
}
