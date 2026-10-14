package com.openminis.app.service

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import java.util.LinkedList
import kotlinx.coroutines.CancellableContinuation

class SlotQueueTimeout(val sessionId: String, val waitedMs: Long) :
    Exception("会话排队超过 ${waitedMs / 1000}s，名额已释放")

/**
 * Limits concurrent agent loop sessions to [maxConcurrent].
 * Excess sessions are suspended in a FIFO queue until a slot frees up.
 */
object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 5

    /** [T-STALL-DIAG] Lines go through AppLogger, not System.out: println
     * bypasses rotation/redaction and still runs in release builds. */
    private const val TAG = "SessionConcurrency"

    /** [T-STALL-DIAG] Heartbeat cadence while a turn is blocked on a slot. */
    private const val SLOT_WAIT_WARN_MS = 10_000L

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private data class Waiter(val sessionId: String, val continuation: CancellableContinuation<Unit>)
    private val waitQueue = LinkedList<Waiter>()

    /** 0 means unset. An unset queue wait is 120s, not forever. */
    fun queueWaitMs(configuredSec: Int): Long {
        val sec = if (configuredSec <= 0) 120 else configuredSec.coerceIn(1, 1800)
        return sec * 1000L
    }

    suspend fun acquireSlot(sessionId: String) {
        // [T-android-slot-lock-unify] The fast path used to check-and-add
        // WITHOUT the same lock releaseSlot holds (@Synchronized), so two
        // concurrent acquires could both read size < MAX and both add —
        // overshooting the cap. Route the check-and-add through the same
        // monitor; the StateFlow assignment inside is non-suspending.
        val fastAcquired = synchronized(this) {
            if (_runningSessions.value.size < MAX_CONCURRENT) {
                _runningSessions.value = _runningSessions.value + sessionId
                true
            } else {
                false
            }
        }
        if (fastAcquired) {
            // [T-STALL-DIAG] Fast path taken — record who now holds slots so a
            // later leak can be traced back to the turn that opened it.
            AppLogger.info(
                TAG,
                "[T-STALL-DIAG] slot ACQUIRED-fast sid=$sessionId " +
                    "running=${_runningSessions.value.size}/$MAX_CONCURRENT " +
                    "holders=${_runningSessions.value.joinToString(",")}",
            )
            return
        }

        // Slow path. A leaked holder must not park the next turn forever.
        // Timeout and a normal release both go through [releaseSlot]: the
        // cancellation handler is that call, not a second cleanup.
        val holdersAtWait = _runningSessions.value.toList()
        val waitStartMs = runCatching { android.os.SystemClock.elapsedRealtime() }
            .getOrElse { System.currentTimeMillis() }
        val waitMs = queueWaitMs(com.openminis.app.data.ToolLimitPrefs.queueTimeoutSec())
        AppLogger.info(
            TAG,
            "[T-STALL-DIAG] slot WAIT-BEGIN sid=$sessionId " +
                "running=${holdersAtWait.size}/$MAX_CONCURRENT " +
                "holders=${holdersAtWait.joinToString(",")} " +
                "queueDepth=${_suspendedSessions.value.size} waitMs=$waitMs",
        )
        try {
            withTimeout(waitMs) {
                coroutineScope {
                    val watchdog = launch(kotlinx.coroutines.Dispatchers.IO) {
                        var waited = 0L
                        while (true) {
                            kotlinx.coroutines.delay(SLOT_WAIT_WARN_MS)
                            waited += SLOT_WAIT_WARN_MS
                            AppLogger.info(
                                TAG,
                                "[T-STALL-DIAG] slot STILL-WAITING sid=$sessionId waitedMs=$waited " +
                                    "holders=${_runningSessions.value.joinToString(",")} " +
                                    "queueDepth=${_suspendedSessions.value.size} " +
                                    "— if these holders are not live turns, slots have LEAKED",
                            )
                        }
                    }
                    try {
                        suspendCancellableCoroutine<Unit> { cont ->
                            synchronized(this@SessionConcurrencyManager) {
                                waitQueue.add(Waiter(sessionId, cont))
                                _suspendedSessions.value = _suspendedSessions.value + sessionId
                            }
                            cont.invokeOnCancellation {
                                releaseSlot(sessionId)
                            }
                        }
                    } finally {
                        watchdog.cancel()
                        val end = runCatching { android.os.SystemClock.elapsedRealtime() }
                            .getOrElse { System.currentTimeMillis() }
                        AppLogger.info(
                            TAG,
                            "[T-STALL-DIAG] slot WAIT-END sid=$sessionId waitedMs=${end - waitStartMs}",
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw SlotQueueTimeout(sessionId, waitMs)
        }
    }

    /**
     * [T-STALL-DIAG] Snapshot for the send path to log BEFORE it tries to
     * acquire — so a turn that never reaches "slot acquired" still leaves a
     * record of what the manager looked like at that moment.
     */
    fun diagSnapshot(): String =
        "running=${_runningSessions.value.size}/$MAX_CONCURRENT " +
            "holders=${_runningSessions.value.joinToString(",")} " +
            "suspended=${_suspendedSessions.value.joinToString(",")}"

    fun releaseSlot(sessionId: String) {
        val pending = synchronized(this) {
            val removedWaiting = waitQueue.removeAll { it.sessionId == sessionId }
            _suspendedSessions.value = _suspendedSessions.value - sessionId
            val had = sessionId in _runningSessions.value
            if (had) _runningSessions.value = _runningSessions.value - sessionId
            AppLogger.info(
                TAG,
                "[T-STALL-DIAG] slot RELEASED sid=$sessionId wasHeld=$had waiting=$removedWaiting " +
                    "running=${_runningSessions.value.size}/$MAX_CONCURRENT " +
                    "holders=${_runningSessions.value.joinToString(",")}",
            )
            if (had || removedWaiting) pollNextLocked() else null
        }
        if (pending != null) {
            try {
                if (pending.continuation.isActive) pending.continuation.resume(Unit)
                else releaseSlot(pending.sessionId)
            } catch (_: IllegalStateException) {
                releaseSlot(pending.sessionId)
            }
        }
    }

    private fun pollNextLocked(): Waiter? {
        val next = waitQueue.pollFirst() ?: return null
        _suspendedSessions.value = _suspendedSessions.value - next.sessionId
        _runningSessions.value = _runningSessions.value + next.sessionId
        return next
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value
}
