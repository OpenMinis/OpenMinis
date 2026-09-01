package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Limits concurrent agent loop sessions to [maxConcurrent].
 * Excess sessions are suspended in a FIFO queue until a slot frees up.
 */
object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 5

    /** [T-STALL-DIAG] Heartbeat cadence while a turn is blocked on a slot. */
    private const val SLOT_WAIT_WARN_MS = 10_000L

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private data class Waiter(val sessionId: String, val continuation: Continuation<Unit>)
    private val waitQueue = LinkedList<Waiter>()

    suspend fun acquireSlot(sessionId: String) {
        if (_runningSessions.value.size < MAX_CONCURRENT) {
            _runningSessions.value = _runningSessions.value + sessionId
            // [T-STALL-DIAG] Fast path taken — record who now holds slots so a
            // later leak can be traced back to the turn that opened it.
            println(
                "[T-STALL-DIAG] slot ACQUIRED-fast sid=$sessionId " +
                    "running=${_runningSessions.value.size}/$MAX_CONCURRENT " +
                    "holders=${_runningSessions.value.joinToString(",")}",
            )
            return
        }

        // [T-STALL-DIAG] SLOW PATH — every slot is taken, so this turn is about
        // to suspend with NO timeout. This is the prime suspect for the reported
        // "new session sits on thinking… forever, nothing in the UI, stop button
        // still armed": if a streamJob is killed before its `finally`
        // (process death, cancellation gap), its id is never removed from
        // _runningSessions, and after MAX_CONCURRENT such leaks EVERY new turn
        // blocks here silently and indefinitely.
        //
        // Log WHO holds the slots at the moment we start waiting, then emit a
        // heartbeat while still blocked, so the log distinguishes:
        //   - "waiting, holders are real live sessions"  → legitimate queueing
        //   - "waiting, holders are stale/unknown ids"   → leaked slots (bug)
        val holdersAtWait = _runningSessions.value.toList()
        val waitStartMs = android.os.SystemClock.elapsedRealtime()
        println(
            "[T-STALL-DIAG] slot WAIT-BEGIN sid=$sessionId " +
                "running=${holdersAtWait.size}/$MAX_CONCURRENT " +
                "holders=${holdersAtWait.joinToString(",")} " +
                "queueDepth=${_suspendedSessions.value.size}",
        )
        val watchdog = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var waited = 0L
            while (true) {
                kotlinx.coroutines.delay(SLOT_WAIT_WARN_MS)
                waited += SLOT_WAIT_WARN_MS
                println(
                    "[T-STALL-DIAG] slot STILL-WAITING sid=$sessionId waitedMs=$waited " +
                        "holders=${_runningSessions.value.joinToString(",")} " +
                        "queueDepth=${_suspendedSessions.value.size} " +
                        "— if these holders are not live turns, slots have LEAKED",
                )
            }
        }

        // Queue and suspend
        _suspendedSessions.value = _suspendedSessions.value + sessionId
        try {
            suspendCancellableCoroutine { cont ->
                synchronized(this@SessionConcurrencyManager) {
                    waitQueue.add(Waiter(sessionId, cont))
                }
                cont.invokeOnCancellation {
                    synchronized(this@SessionConcurrencyManager) {
                        waitQueue.removeAll { it.sessionId == sessionId }
                        _suspendedSessions.value = _suspendedSessions.value - sessionId
                    }
                }
            }
        } finally {
            watchdog.cancel()
            println(
                "[T-STALL-DIAG] slot WAIT-END sid=$sessionId " +
                    "waitedMs=${android.os.SystemClock.elapsedRealtime() - waitStartMs}",
            )
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

    @Synchronized
    fun releaseSlot(sessionId: String) {
        val had = sessionId in _runningSessions.value
        _runningSessions.value = _runningSessions.value - sessionId
        println(
            "[T-STALL-DIAG] slot RELEASED sid=$sessionId wasHeld=$had " +
                "running=${_runningSessions.value.size}/$MAX_CONCURRENT " +
                "holders=${_runningSessions.value.joinToString(",")}",
        )

        // Resume next waiter
        val next = synchronized(this) { waitQueue.pollFirst() }
        if (next != null) {
            _suspendedSessions.value = _suspendedSessions.value - next.sessionId
            _runningSessions.value = _runningSessions.value + next.sessionId
            next.continuation.resume(Unit)
        }
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value
}
