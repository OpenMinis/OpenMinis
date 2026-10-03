package com.openminis.app.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * [T-android-scheduled-tasks-design] Receives an AlarmManager fire and
 * hands the task off to [ScheduledAgentRunner].
 *
 * This is a HANDOFF, not a run: everything here must finish in
 * milliseconds. goAsync() covers only "load the task, arm the next
 * occurrence, start the FGS and dispatch the prompt"; the agent loop
 * itself then runs on [ScheduledAgentRunner]'s app-scoped scope, kept
 * alive by the foreground service (and its wake lock) rather than by this
 * broadcast.
 *
 * [GH#197] It used to await the entire agent loop before calling
 * pending.finish(), which held the broadcast for minutes and ANR-killed
 * the process — and with it every PRoot sandbox child. goAsync() alone
 * does not buy unlimited time; it only defers the deadline, so the work
 * it wraps still has to be short.
 */
class ScheduledTaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledTaskManager.ACTION_FIRE) return
        val taskId = intent.getStringExtra(ScheduledTaskManager.EXTRA_TASK_ID) ?: return

        AppLogger.info(TAG, "fire received: task=$taskId")

        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val startedAt = SystemClock.elapsedRealtime()
        scope.launch {
            try {
                // [GH#197] Hard ceiling on how long this broadcast can stay
                // alive. Everything inside is meant to take milliseconds; the
                // timeout exists so that if any step wedges (a stalled DB
                // read, a hung FGS start), we still release the broadcast
                // instead of holding it until the system ANRs us.
                withTimeout(HANDOFF_TIMEOUT_MS) {
                    val manager = ScheduledTaskManager(appContext)
                    val task = manager.get(taskId) ?: run {
                        AppLogger.warning(TAG, "task $taskId not found in store — skipping")
                        return@withTimeout
                    }
                    if (!task.enabled) {
                        AppLogger.info(TAG, "task $taskId disabled — skipping fire")
                        return@withTimeout
                    }
                    // Schedule the next occurrence FIRST so a long-running
                    // prompt doesn't block tomorrow's fire even if we crash.
                    // [T-android-scheduled-fire-claim] False for a stale second
                    // delivery of a relative trigger's fire: nothing to run.
                    if (!manager.rescheduleNext(taskId)) return@withTimeout
                    // [T-android-scheduled-triggers] Run the row as it is NOW:
                    // rescheduleNext just advanced an interval's anchor and
                    // count, and the fire's envelope shows the NEXT fire time.
                    val current = manager.get(taskId) ?: task

                    // [GH#197] waitForCompletion=false is load-bearing, not a
                    // tweak. With the default (true) this call suspends until
                    // the whole agent loop finishes — up to RUN_TIMEOUT_MS
                    // (10 min) — and because HeadlessChatRunner.prompt/retry
                    // are `withContext(Dispatchers.Main)`, that wait also
                    // lands back on the main thread. pending.finish() then
                    // sits behind it, blowing far past the ~10s budget a
                    // manifest-declared receiver gets, so the system ANRs the
                    // process and kills it — taking every PRoot sandbox child
                    // with it. The UI "Run now" path already passes false
                    // (638f3eb0b); the alarm path silently kept the default.
                    //
                    // Nothing is lost by not waiting: run() still resolves the
                    // session and starts the foreground service (which holds
                    // the wake lock) before returning, and mark-fired plus the
                    // completion notification are finished off ScheduledAgent-
                    // Runner's app-scoped bgScope, which outlives this
                    // receiver.
                    ScheduledAgentRunner.run(appContext, current, waitForCompletion = false, scheduledFire = true)
                }
            } catch (t: Throwable) {
                // Includes TimeoutCancellationException. The task stays
                // scheduled (rescheduleNext already armed the next occurrence)
                // so a wedged fire self-heals instead of killing the process.
                AppLogger.error(TAG, "task $taskId fire failed: ${t.message}")
            } finally {
                pending.finish()
                // [GH#197] The number to watch: this is the goAsync hold time,
                // i.e. how much of the broadcast ANR budget we consumed. It
                // must stay in the tens of milliseconds. If it ever creeps
                // toward HANDOFF_TIMEOUT_MS, something on the handoff path
                // started blocking again.
                AppLogger.info(
                    TAG,
                    "task $taskId handoff done in ${SystemClock.elapsedRealtime() - startedAt}ms",
                )
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskRecv"

        /**
         * [GH#197] Ceiling for the whole handoff (load task → arm next
         * occurrence → start FGS + dispatch). A manifest-declared receiver
         * gets roughly 10s of background-broadcast budget before the system
         * ANRs it; 8s leaves headroom to log and finish cleanly while still
         * being ~100x more than the handoff actually needs.
         */
        private const val HANDOFF_TIMEOUT_MS = 8_000L
    }
}
