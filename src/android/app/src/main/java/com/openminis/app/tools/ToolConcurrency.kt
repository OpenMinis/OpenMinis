package com.openminis.app.tools

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-android-tool-pool-cap] Concurrency ceilings for the two tool families whose
 * work blocks a thread for an unbounded time.
 *
 * ## Why
 *
 * Nothing in the tool layer used to bound how much blocking work one turn could
 * start:
 *
 *  - **Native offload** (the host-capability CLIs: shell, browser, speech,
 *    photos, scheduled, model-use …) starts a fresh daemon thread per request
 *    *and* another per handler deadline ([com.openminis.app.sandbox.NativeOffloadServer]).
 *    A guest script that runs `minis-photos … &` in a loop, or an agent that
 *    emits eight parallel `shell_execute` calls, therefore produced 2N threads
 *    whose handlers may each sit for up to 18 minutes (`minis-model-use`). On a
 *    phone this is how a "stuck" turn turns into a system-wide stall: the
 *    scheduler thrashes, the notification and UI threads lose their slices, and
 *    the user sees the app freeze rather than a slow tool.
 *  - **File tools** (`file_read`, `grep`, `glob`, `list_dir`, …) run blocking
 *    disk IO inline on `Dispatchers.IO`, whose default parallelism is 64. A grep
 *    fan-out over a big tree can occupy every one of those 64 slots, and the app
 *    still needs that pool for provider streams, image decode and DB writes.
 *
 * Both ceilings are deliberately process-wide rather than per-turn: the
 * starvation they prevent is a property of the device, not of one conversation.
 * Calls past the ceiling wait for a slot instead of failing, so nothing is
 * dropped or reordered — a queued tool still runs, just later.
 *
 * ## Numbers
 *
 * - [MAX_CONCURRENT_FILE_OPS] = 8: one slot per core on a typical phone with
 *   headroom for one blocked read; the shared IO pool keeps 56 slots for
 *   everything else.
 * - [MAX_CONCURRENT_OFFLOAD_HANDLERS] = 4: matches the app's existing ceiling
 *   for the same class of work ([com.openminis.app.mcp.server.McpHttpServer]'s
 *   `newFixedThreadPool(4)`); each offload call forks a PRoot/guest process or
 *   drives a WebView, so more than four at once is already past the point where
 *   a phone schedules usefully.
 *
 * The permission-dialog timeout ([com.openminis.app.offload.OffloadPermissionManager],
 * 120 s) is intentionally NOT part of this file: it was reviewed during the
 * 1.15-beta.5 port and kept at 120 s rather than upstream's 300 s, because a
 * longer human-wait window only extends the time a wedged handler holds its
 * slot — see docs/upstream-beta5-triage.md §4.
 */
object ToolConcurrency {

    /** Bounded slot count for the coroutine-side file tools. */
    const val MAX_CONCURRENT_FILE_OPS: Int = 8

    /** Bounded slot count for blocking native-offload handlers. */
    const val MAX_CONCURRENT_OFFLOAD_HANDLERS: Int = 4

    /**
     * How long an offload request waits for a free handler slot before the
     * caller is told the device is busy. Short enough that a guest CLI still
     * exits well inside its own timeout, long enough to absorb a burst.
     */
    const val OFFLOAD_QUEUE_WAIT_MS: Long = 30_000L

    /**
     * File-tool dispatcher: `Dispatchers.IO` narrowed to [MAX_CONCURRENT_FILE_OPS].
     * Same backing pool, so a blocked read still cannot starve the UI thread,
     * but a fan-out can no longer take the whole pool.
     */
    val fileOps: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_FILE_OPS, "minis-file-ops")

    /** Coroutine-side companion for the host-capability API path. */
    val offload: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_OFFLOAD_HANDLERS, "minis-offload")

    /**
     * Run [block] on the bounded file-tool dispatcher. Non-suspending on
     * purpose: every file tool is synchronous, and making the wrapper suspend
     * would tempt callers back onto the shared pool.
     */
    suspend fun <T> onFileOp(block: () -> T): T = withContext(fileOps) { block() }

    /** Run a suspending offload handler on the bounded offload dispatcher. */
    suspend fun <T> onOffload(block: suspend () -> T): T = withContext(offload) { block() }

    // ── raw-thread gate (NativeOffloadServer spawns threads, not coroutines) ──

    private val offloadSlots = Semaphore(MAX_CONCURRENT_OFFLOAD_HANDLERS, true)
    private val inFlight = AtomicInteger(0)

    /**
     * Claim a handler slot, waiting at most [waitMs]. False means the device is
     * already running [MAX_CONCURRENT_OFFLOAD_HANDLERS] long handlers and the
     * caller should reply "busy" instead of queueing another thread.
     */
    fun tryAcquireOffloadSlot(waitMs: Long = OFFLOAD_QUEUE_WAIT_MS): Boolean {
        val acquired = try {
            offloadSlots.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (acquired) inFlight.incrementAndGet()
        return acquired
    }

    /** Release a slot claimed by [tryAcquireOffloadSlot]. Idempotent-safe via the counter floor. */
    fun releaseOffloadSlot() {
        if (inFlight.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) offloadSlots.release()
    }

    /** Slots currently running a handler (diagnostics + tests). */
    fun offloadInFlight(): Int = inFlight.get()

    /** Slots still free (diagnostics + tests). */
    fun offloadSlotsAvailable(): Int = offloadSlots.availablePermits()
}
