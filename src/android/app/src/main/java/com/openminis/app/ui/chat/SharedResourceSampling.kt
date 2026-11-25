package com.openminis.app.ui.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive

/**
 * A single producer for all visible resource HUDs. shareIn serializes producer
 * cancellation/restart, including a sample still finishing on IO. No subscriber
 * means no polling; stop without a grace period and discard stale replay.
 */
internal class SharedResourceSampling(
    scope: CoroutineScope,
    reset: () -> Unit,
    sample: suspend () -> Unit,
) {
    val ticks = flow {
        reset()
        while (currentCoroutineContext().isActive) {
            sample()
            emit(Unit)
            // Keep the original cadence: an immediate baseline, then 2 s
            // after each completed sample, not a faster fixed-rate timer.
            delay(2_000)
        }
    }.shareIn(
        scope,
        started = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = 0,
            replayExpirationMillis = 0,
        ),
        replay = 1,
    )

    suspend fun collectWhileStarted(
        lifecycle: Lifecycle,
        active: Boolean,
        onSample: () -> Unit,
    ) {
        if (!active) return
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ticks.collect { onSample() }
        }
    }
}
