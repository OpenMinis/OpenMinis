package com.openminis.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [T-fgs-stop-linger] Defers a foreground-service stop by [lingerMs].
 *
 * Root cause this closes (install-time ForegroundServiceDidNotStartInTimeException,
 * crash-2026-10-15_01-00-01): every sandbox command (probe / apt / post-install
 * step) drove the tracker through setActive → startForegroundService() → command →
 * setInactive → stopService(). A probe against an already-installed component
 * finishes in ~7ms, so the stopService() binder call and the NEXT command's
 * startForegroundService() cross in flight at ActivityManager. The platform
 * defers the start behind the teardown and arms the 5-10s foreground deadline
 * against a service instance that has not been created yet — the deadline fires
 * before onCreate ever runs, and the process is killed.
 *
 * Lingering the stop closes the crossing for every rapid command sequence
 * (dev-suite assemblies, chat tool-call chains, probe cascades): the next
 * setActive/setPresent lands while the service is still alive, cancels the
 * pending stop, and the sequence keeps ONE service instance instead of
 * churning start/stop per command. The stop still fires after a genuinely
 * idle window, so no battery regression beyond [DEFAULT_LINGER_MS] of
 * completed-state notification.
 *
 * Pure coroutine class (no Android imports) so the schedule/cancel/restart
 * contract is unit-testable with virtual time.
 */
class ServiceStopLinger(
    private val scope: CoroutineScope,
    private val lingerMs: Long = DEFAULT_LINGER_MS,
    private val onStop: () -> Unit,
) {
    private var job: Job? = null

    /** True while a stop is scheduled but has not fired yet. */
    val isPending: Boolean get() = job?.isActive == true

    /**
     * (Re)schedule the deferred stop. Calling this again restarts the window —
     * the linger measures quiet time since the LAST emptiness event, not since
     * the first.
     */
    fun schedule() {
        job?.cancel()
        job = scope.launch {
            delay(lingerMs)
            onStop()
        }
    }

    /** Cancel a pending stop — the service is needed again. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * Long enough to bridge command-to-command gaps (ExecutionCoordinator
         * bind-mount + shell-reuse overhead is sub-second; component-to-component
         * transitions in an assembly are adjacent), short enough that the
         * completed-state notification clears promptly after a real idle.
         */
        const val DEFAULT_LINGER_MS: Long = 10_000L
    }
}
