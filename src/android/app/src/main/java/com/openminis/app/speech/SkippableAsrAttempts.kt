package com.openminis.app.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * [T-android-voice-asr-stall-skip] Runs ASR attempts so a stalled one can be
 * skipped. Port of iOS `SkippableASRAttempts` (9f6207b05 / 4a1f44220).
 *
 * The fail-over chain only advanced when an attempt THREW. An attempt that
 * simply never returned — a half-dead connection, or an inference server that
 * accepted the upload and went quiet — held the take for up to OkHttp's
 * 120 s read timeout per candidate, and the user's only way out was to cancel
 * and lose the audio.
 *
 * Each attempt that has somewhere to go next is marked stalled after
 * [stallThresholdMs] without a result, which raises [stalled]; [skipStalled]
 * then releases every stalled caller at once with [Skipped] and cancels the
 * provider's work as a courtesy. It does not rely on the provider honouring
 * cancellation (a blocking OkHttp call may not): the attempt's late result is
 * simply discarded.
 */
class SkippableAsrAttempts(
    private val stallThresholdMs: Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Thrown into the waiting caller when the user skips a stalled attempt. */
    class Skipped : Exception("ASR attempt skipped by the user")

    private class Attempt(val label: String, val nextEntryId: String?) {
        val result = CompletableDeferred<String>()
        var work: Job? = null
        var stallTimer: Job? = null
    }

    private val lock = Any()
    private val live = LinkedHashMap<String, Attempt>()
    private val stalledIds = LinkedHashSet<String>()

    private val _stalled = MutableStateFlow(false)

    /** True while any running attempt is stalled (the "Switch Model" offer). */
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    var log: ((String) -> Unit)? = null

    private fun publishLocked() {
        _stalled.value = stalledIds.isNotEmpty()
    }

    /**
     * Run one attempt. Returns its text, or throws its error, [Skipped], or a
     * CancellationException when the calling coroutine is cancelled.
     * [nextEntryId] is where a skip should send the NEXT segments (null = no
     * preference); an attempt with [canSkip] false is never marked stalled.
     */
    suspend fun run(
        label: String,
        nextEntryId: String?,
        canSkip: Boolean,
        operation: suspend () -> String,
    ): String {
        val id = UUID.randomUUID().toString()
        val attempt = Attempt(label, nextEntryId)
        synchronized(lock) { live[id] = attempt }
        attempt.work = scope.launch {
            try {
                attempt.result.complete(operation())
            } catch (t: Throwable) {
                attempt.result.completeExceptionally(t)
            }
        }
        if (canSkip) {
            attempt.stallTimer = scope.launch {
                delay(stallThresholdMs)
                synchronized(lock) {
                    if (attempt.result.isActive && live.containsKey(id)) {
                        log?.invoke("ASR stall: $label has no result after ${stallThresholdMs / 1000}s — offering skip")
                        stalledIds.add(id)
                        publishLocked()
                    }
                }
            }
        }
        try {
            return attempt.result.await()
        } catch (c: kotlinx.coroutines.CancellationException) {
            // The CALLER was cancelled (a result or skip completes the deferred
            // with a value / Skipped, never a CancellationException of ours).
            attempt.work?.cancel()
            throw c
        } finally {
            attempt.stallTimer?.cancel()
            synchronized(lock) {
                live.remove(id)
                if (stalledIds.remove(id)) publishLocked()
            }
        }
    }

    /**
     * Abandon every stalled attempt. Returns the nextEntryId of each one
     * skipped (empty when nothing was stalled).
     */
    fun skipStalled(): List<String?> {
        val skipped = synchronized(lock) {
            val out = stalledIds.mapNotNull { live[it] }
            stalledIds.clear()
            publishLocked()
            out
        }
        for (attempt in skipped) {
            log?.invoke("ASR stall: user skipped ${attempt.label}")
            attempt.work?.cancel()
            attempt.result.completeExceptionally(Skipped())
        }
        return skipped.map { it.nextEntryId }
    }
}
