package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Append every reasoning delta without copying the accumulated string. Immutable
 * UI snapshots are materialized at most once per interval, plus explicit stream
 * boundaries. The stream owns [publishPending]; no ViewModel-scope timer survives
 * a failed/cancelled attempt. Stop can synchronously [closeAndSnapshot] before the
 * stream coroutine has unwound.
 */
internal class ThinkingStreamBuffer(
    val blockId: String,
    private val intervalMs: Long = 200L,
    private val materialize: (CharSequence) -> String = { it.toString() },
) {
    init { require(intervalMs > 0) }

    private val text = StringBuilder()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var received = false
    private var generation = 0L
    private var closed = false
    private var publishedLength = -1
    private var lastPublishedMs: Long? = null
    private var cachedLength = -1
    private var cachedText = ""

    val length: Int
        @Synchronized get() = text.length
    val isClosed: Boolean
        @Synchronized get() = closed
    fun isNotEmpty(): Boolean = length != 0

    @Synchronized
    fun append(delta: String) {
        if (closed) return
        received = true
        text.append(delta)
        wake.trySend(Unit)
    }

    /** Null means no new snapshot is due; callers do no Main dispatch in that case. */
    @Synchronized
    fun takeIfDue(nowMs: Long): Snapshot? {
        if (closed || !received || publishedLength == text.length) return null
        val previous = lastPublishedMs
        if (previous != null && nowMs - previous < intervalMs) return null
        return take(nowMs)
    }

    /** Force the latest tail before text/tool boundaries and clean completion. */
    @Synchronized
    fun snapshot(nowMs: Long): Snapshot? = if (received) take(nowMs) else null

    @Synchronized
    private fun take(nowMs: Long): Snapshot {
        publishedLength = text.length
        lastPublishedMs = nowMs
        return Snapshot(blockId, toString(), generation)
    }

    /** Reject delayed snapshots from an older boundary or failed attempt. */
    @Synchronized
    fun accepts(snapshot: Snapshot): Boolean = !closed && snapshot.generation == generation &&
        snapshot.blockId == blockId && snapshot.text.length == publishedLength

    @Synchronized
    override fun toString(): String {
        if (cachedLength != text.length) {
            cachedText = materialize(text)
            cachedLength = text.length
        }
        return cachedText
    }

    /** Retry discards the failed attempt, including its throttle baseline. */
    @Synchronized
    fun clear() {
        generation++
        text.setLength(0)
        received = false
        closed = false
        publishedLength = -1
        lastPublishedMs = null
        cachedLength = -1
        cachedText = ""
    }

    /**
     * Invalidate the failed generation before rollback publication can suspend.
     * Stop in that suspension must not restore the reasoning being removed.
     */
    suspend fun resetBeforeRetry(publishRollback: suspend () -> Unit) {
        clear()
        publishRollback()
    }

    /** Called by the existing synchronous Stop/error drain, before late writes can land. */
    @Synchronized
    fun closeAndSnapshot(): Snapshot? {
        closed = true
        wake.trySend(Unit)
        return if (received) Snapshot(blockId, toString(), generation) else null
    }

    @Synchronized
    private fun remainingDelay(nowMs: Long): Long? {
        if (closed || !received || publishedLength == text.length) return null
        return lastPublishedMs?.let { (intervalMs - (nowMs - it)).coerceAtLeast(0L) } ?: 0L
    }

    /** One suspended worker per live thinking segment; a burst leaves one wake token. */
    suspend fun publishPending(nowMs: () -> Long, publish: suspend (Snapshot) -> Unit) {
        while (!isClosed) {
            wake.receive()
            while (!isClosed) {
                val remaining = remainingDelay(nowMs()) ?: break
                if (remaining > 0) delay(remaining)
                val snapshot = takeIfDue(nowMs()) ?: continue
                publish(snapshot)
            }
        }
    }

    data class Snapshot(val blockId: String, val text: String, internal val generation: Long = 0) {
        /** Preserve tool order/status and replace only this turn's thinking content. */
        fun applyTo(blocks: List<AssistantBlock>): List<AssistantBlock> {
            val index = blocks.indexOfFirst { it.kind == "thinking" && it.id == blockId }
            if (index < 0) {
                return blocks + AssistantBlock(id = blockId, kind = "thinking", content = text, toolTitle = "Thinking")
            }
            if (blocks[index].content == text) return blocks
            return blocks.toMutableList().also { it[index] = it[index].copy(content = text) }
        }
    }
}

/**
 * A thinking tick changes only thinking. The unpublished trailing snapshot can
 * contain newer reply text/tool progress than the visible overlay; never build
 * a new update from that older overlay when a pending snapshot exists.
 * Used by the worker and both terminal drains so Stop follows the same precedence.
 */
internal fun resolveThinkingStreamSnapshot(
    pending: StreamingDelta?,
    published: StreamingDelta?,
    canonical: StreamingDelta?,
    thinking: ThinkingStreamBuffer.Snapshot?,
): StreamingDelta? {
    val latest = pending ?: published ?: canonical ?: return null
    val blocks = thinking?.applyTo(latest.toolBlocks) ?: latest.toolBlocks
    return if (blocks === latest.toolBlocks) latest else latest.copy(toolBlocks = blocks)
}

/**
 * Child publication failures cancel only this attempt. The caller's catch remains
 * active for its normal error/retry drain. The attempt must cancel its waiting
 * publisher in its finally block before returning, including on clean completion.
 */
internal suspend fun <T> withThinkingStreamAttempt(
    block: suspend CoroutineScope.() -> T,
): T = coroutineScope(block)

/**
 * A recovered Stop exception can carry its original as a cause. Check the
 * caller's Job after the attempt scope has unwound, just as TitleCandidates does.
 * A causeful provider-flow failure in an active caller still takes error/retry.
 */
internal suspend fun isRealStreamCancellation(error: CancellationException): Boolean =
    error.cause == null || !currentCoroutineContext().isActive
