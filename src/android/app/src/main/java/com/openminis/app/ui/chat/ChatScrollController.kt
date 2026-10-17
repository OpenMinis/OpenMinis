package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single command authority for the main chat timeline.
 *
 * The screen may observe [state], but it must not issue a scroll command
 * itself. Commands are serialized so a late settle from one action cannot
 * undo a newer user action. The previous-turn queue is intentionally bounded:
 * rapid taps remain deterministic without creating an unbounded backlog.
 */
internal class ChatScrollController(
    val state: LazyListState,
    private val maxQueuedPreviousTurns: Int = 2,
) {
    private val commandMutex = Mutex()
    private var previousJob: Job? = null
    private var queuedPreviousTurns = 0

    suspend fun scrollToItem(source: String, index: Int, offset: Int = 0) {
        commandMutex.withLock {
            try {
                state.scrollToItem(index.coerceAtLeast(0), offset)
            } catch (_: IllegalStateException) {
                // The list can be disposed between a queued command and its
                // execution during navigation. The next screen owns the
                // restored state and does not need this stale command.
            }
        }
    }

    suspend fun scrollBy(source: String, delta: Float) {
        commandMutex.withLock {
            try {
                state.scrollBy(delta)
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
