package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The two viewport mutations the chat timeline needs, abstracted for tests. */
internal interface ScrollTarget {
    suspend fun scrollToItem(index: Int, offset: Int)
    suspend fun scrollBy(delta: Float)
}

private class LazyListScrollTarget(private val state: LazyListState) : ScrollTarget {
    override suspend fun scrollToItem(index: Int, offset: Int) {
        state.scrollToItem(index, offset)
    }

    override suspend fun scrollBy(delta: Float) {
        state.scrollBy(delta)
    }
}

/**
 * Single command authority for the main chat timeline.
 *
 * The screen may observe [state], but it must not issue a scroll command
 * itself. Commands are serialized so a late settle from one action cannot
 * undo a newer user action.
 *
 * [commandMutex] alone only serializes: a settle enqueued 100 ms after send
 * still runs *after* the user has dragged away, because waiting commands are
 * never re-checked. Every command therefore captures a [generation] before
 * waiting for the lock and re-checks it after acquiring. A gesture (or a
 * session switch) bumps the generation, so every command issued before it
 * becomes a no-op even if it already reached the front of the queue.
 *
 * While the finger is down ([beginUserGesture]) all programmatic commands are
 * dropped outright: the user owns the viewport until they lift.
 */
internal class ChatScrollController(
    private val target: ScrollTarget,
    private val maxQueuedPreviousTurns: Int = 2,
) {
    constructor(state: LazyListState, maxQueuedPreviousTurns: Int = 2) :
        this(LazyListScrollTarget(state), maxQueuedPreviousTurns)

    private val commandMutex = Mutex()
    private val generation = AtomicLong(0L)
    private var previousJob: Job? = null
    private var queuedPreviousTurns = 0

    @Volatile
    private var userDragging = false

    /** Current command generation; exposed for traces/tests. */
    fun currentGeneration(): Long = generation.get()

    /**
     * A gesture started: drop every queued programmatic command and refuse new
     * ones until [endUserGesture].
     */
    fun beginUserGesture() {
        userDragging = true
        invalidate("user-gesture-start")
    }

    fun endUserGesture() {
        userDragging = false
    }

    /**
     * Invalidate every command issued before this call and cancel queued
     * previous-turn work. Used for session switches, explicit re-pins and
     * gestures.
     */
    fun invalidate(reason: String) {
        generation.incrementAndGet()
        cancelPending()
    }

    suspend fun scrollToItem(source: String, index: Int, offset: Int = 0) {
        if (userDragging) return
        val issued = generation.get()
        commandMutex.withLock {
            // Re-check AFTER the wait: this is the stale-settle guard. Without
            // it a command queued before the drag executes after it.
            if (issued != generation.get() || userDragging) return
            try {
                target.scrollToItem(index.coerceAtLeast(0), offset)
            } catch (_: IllegalStateException) {
                // The list can be disposed between a queued command and its
                // execution during navigation. The next screen owns the
                // restored state and does not need this stale command.
            }
        }
    }

    suspend fun scrollBy(source: String, delta: Float) {
        if (userDragging) return
        val issued = generation.get()
        commandMutex.withLock {
            if (issued != generation.get() || userDragging) return
            try {
                target.scrollBy(delta)
            } catch (_: IllegalStateException) {
                // See scrollToItem: disposal is a normal cancellation edge.
            }
        }
    }

    /** Enqueue one previous-turn action and execute it strictly serially. */
    fun enqueuePreviousTurn(scope: CoroutineScope, action: suspend () -> Unit) {
        val active = previousJob
        if (active?.isActive == true) {
            queuedPreviousTurns = (queuedPreviousTurns + 1).coerceAtMost(maxQueuedPreviousTurns)
            return
        }
        previousJob = scope.launch {
            try {
                action()
                while (queuedPreviousTurns > 0) {
                    queuedPreviousTurns--
                    action()
                }
            } finally {
                queuedPreviousTurns = 0
                previousJob = null
            }
        }
    }

    fun cancelPending() {
        previousJob?.cancel()
        previousJob = null
        queuedPreviousTurns = 0
    }

    companion object {
        /**
         * Wait for a newly published flat-list generation containing [key].
         * There is no timeout fallback: a missing target is a failed request,
         * and callers must keep the current viewport rather than jump blindly.
         */
        suspend fun awaitTarget(
            flatItems: Flow<List<FlatChatItem>>,
            flatItemsVersion: Flow<Long>,
            requestedVersion: Long,
            key: String,
        ): Boolean {
            return awaitTarget(flatItems, flatItemsVersion, requestedVersion) { it.key == key }
        }

        suspend fun awaitTarget(
            flatItems: Flow<List<FlatChatItem>>,
            flatItemsVersion: Flow<Long>,
            requestedFlatVersion: Long,
            historyVersion: Flow<Long>,
            requestedHistoryVersion: Long,
            key: String,
        ): Boolean {
            return combine(flatItems, flatItemsVersion, historyVersion) { items, flatVersion, history ->
                if (flatVersion > requestedFlatVersion && history > requestedHistoryVersion) {
                    items.any { it.key == key }
                } else {
                    false
                }
            }.first { it }
        }

        suspend fun awaitPublishedVersion(
            flatItemsVersion: Flow<Long>,
            requestedFlatVersion: Long,
            historyVersion: Flow<Long>? = null,
            requestedHistoryVersion: Long = Long.MIN_VALUE,
        ) {
            if (historyVersion == null) {
                flatItemsVersion.first { it > requestedFlatVersion }
            } else {
                combine(flatItemsVersion, historyVersion) { flat, history ->
                    flat > requestedFlatVersion && history > requestedHistoryVersion
                }.first { it }
            }
        }

        suspend fun awaitTarget(
            flatItems: Flow<List<FlatChatItem>>,
            flatItemsVersion: Flow<Long>,
            requestedVersion: Long,
            matches: (FlatChatItem) -> Boolean,
        ): Boolean {
            return combine(flatItems, flatItemsVersion) { items, version ->
                if (version > requestedVersion) items.any(matches) else null
            }.filterNotNull().first { it }
        }

        suspend fun awaitVersion(
            flatItemsVersion: Flow<Long>,
            requestedVersion: Long,
        ): Long = flatItemsVersion.first { it > requestedVersion }
    }
}
