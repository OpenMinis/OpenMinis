package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger

/**
 * [T-android-scheduled-tasks-design] Schedules / cancels AlarmManager
 * entries for [ScheduledTask] rows held in [ScheduledTaskStore]. Pairs with
 * [ScheduledTaskAlarmReceiver] which fires on the trigger and hands the
 * task off to [ScheduledAgentRunner].
 *
 * Repeating tasks (DAILY / WEEKDAYS / CUSTOM) are NOT scheduled via
 * AlarmManager.setRepeating — that primitive is inexact on Android 19+
 * and Doze makes it worse. Instead the receiver re-schedules the next
 * occurrence after every fire, giving Doze-tolerant precision.
 */
class ScheduledTaskManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = ScheduledTaskStore(context)

    init { ensureNotificationChannel() }

    /** Read-only access to persistence for callers that only need data. */
    fun store(): ScheduledTaskStore = store

    fun list(): List<ScheduledTask> = store.all()
    fun get(taskId: String): ScheduledTask? = store.get(taskId)

    fun create(task: ScheduledTask): ScheduledTask {
        // [T-android-scheduled-triggers] A relative trigger counts from now.
        val armed = if (task.usesAnchor && task.anchorMs == null) {
            task.copy(anchorMs = System.currentTimeMillis())
        } else task
        store.upsert(armed)
        if (armed.enabled) registerAlarm(armed)
        if (armed.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        return armed
    }

    /**
     * Save an edited task.
     *
     * [T-android-scheduled-triggers] Bookkeeping the editor does not own is
     * carried over from the stored row: the editor rebuilds the task from its
     * fields, so saving used to reset the run history and fire count (and
     * would reset the new relative triggers' anchor and fire count). Only a
     * value the incoming task leaves empty is taken from the stored one.
     */
    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        val merged = store.get(task.id)?.let { prev -> mergeBookkeeping(task, prev) } ?: task
        store.upsert(merged)
        if (merged.enabled) registerAlarm(merged)
        return merged
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        val t = store.get(taskId) ?: return
        // [T-android-scheduled-triggers] Re-enabling a relative trigger starts
        // it over: "after 30m" counts from now again, and an interval gets its
        // full count back. A calendar task simply resumes its schedule.
        val updated = if (enabled && t.usesAnchor) {
            t.copy(enabled = true, anchorMs = System.currentTimeMillis(), triggeredCount = 0)
        } else if (enabled && t.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            t.copy(enabled = true, triggeredCount = 0)
        } else {
            t.copy(enabled = enabled)
        }
        store.upsert(updated)
        if (enabled) registerAlarm(updated) else cancelAlarm(taskId)
    }

    /**
     * [T-android-scheduled-triggers] Atomically take a one-shot fire that is
     * not driven by an alarm (on-completion): false when it is disabled or has
     * already fired, so two callers cannot both fire it.
     */
    fun claimOneShotFire(taskId: String): Boolean =
        store.update(taskId) { t ->
            if (!t.enabled || (t.triggeredCount ?: 0) >= 1) null
            else t.copy(enabled = false, triggeredCount = 1)
        } != null

    fun delete(taskId: String) {
        cancelAlarm(taskId)
        store.delete(taskId)
    }

    /**
     * Re-register every enabled task with AlarmManager. Called from
     * [com.openminis.app.offload.AlarmReceiver]'s BOOT_COMPLETED branch
     * so persisted tasks survive a device reboot.
     */
    fun rescheduleAll() {
        val tasks = store.all()
        if (tasks.isEmpty()) {
            AppLogger.info(TAG, "rescheduleAll: no tasks")
            return
        }
        var count = 0
        // [T-android-scheduled-triggers] On-completion tasks have no alarm; they
        // need the agent-job completion stream collected in this process.
        if (tasks.any { it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION }) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        for (t in tasks) {
            if (!t.enabled) continue
            registerAlarm(t)
            count++
        }
        AppLogger.info(TAG, "rescheduleAll: re-registered $count enabled task(s)")
    }

    /**
     * Called by the receiver after firing — for repeating tasks, schedule
     * the next occurrence. ONCE tasks get disabled in-place (enabled=false)
     * so they linger in the list with their lastResult* metadata visible.
     */
    /**
     * Returns whether this alarm delivery is a real fire that should run. Only
     * a relative trigger can say no — see [relativeAlarmDue].
     */
    fun rescheduleNext(taskId: String): Boolean {
        val t = store.get(taskId) ?: return false
        // [T-android-scheduled-triggers] Counted HERE, when the alarm fires and
        // before the run starts, so the next slot never depends on a run that
        // may take minutes to record itself.
        //
        // [T-android-scheduled-fire-claim] Checked and counted in ONE atomic
        // step. A cold start by this very alarm also runs rescheduleAll, which
        // can read the row before it is counted, see it overdue and arm it for
        // "now" again — a second delivery of the same fire. Both deliveries
        // then counted and ran. Now the second finds the fire already taken
        // (the anchor has moved on), re-arms the real next slot that the stale
        // registration replaced (same PendingIntent), and does not run.
        if (!t.isCalendar) {
            val now = System.currentTimeMillis()
            var arm = false
            val stored = store.update(taskId) { cur ->
                if (!cur.enabled || !relativeAlarmDue(cur, now)) return@update null
                val (next, a) = afterAlarmFire(cur, now) ?: return@update null
                arm = a
                next
            }
            if (stored == null) {
                val cur = store.get(taskId)
                AppLogger.info(TAG, "alarm for task=$taskId is not a due fire (enabled=${cur?.enabled}) — not running")
                if (cur != null && cur.enabled) registerAlarm(cur)
                return false
            }
            if (arm) registerAlarm(stored)
            return true
        }
        if (t.repeatMode == ScheduledRepeatMode.ONCE) {
            // Mark fired ONCE task as disabled — keep row so the user can
            // see its last result, re-enable if they want to re-run.
            store.upsert(t.copy(enabled = false))
            return true
        }
        registerAlarm(t)
        return true
    }

    fun markFired(
        taskId: String,
        sessionId: String?,
        resultPreview: String?,
        ok: Boolean = true,
        // [T-scheduled-task-detail] When the fire STARTED. The run record used
        // to be stamped at completion, which could be minutes later and could
        // not be matched to the fire's envelope (which carries the start).
        firedAt: Long = System.currentTimeMillis(),
    ) {
        val now = firedAt
        // [T-android-scheduled-tasks-run-records] Prepend a run record
        // (newest-first), capped at MAX_RUN_HISTORY. lastResult* are kept in
        // sync for back-compat but are no longer surfaced in the list UI.
        val run = ScheduledRun(firedAt = now, sessionId = sessionId, preview = resultPreview, ok = ok)
        // [T-android-scheduled-fire-claim] Atomic: a run finishing minutes
        // after its fire must not write back a count / anchor the next fire
        // has moved on since.
        store.update(taskId) { t ->
            t.copy(
                lastFiredAt = now,
                lastResultPreview = resultPreview,
                lastResultSessionId = sessionId,
                runHistory = (listOf(run) + t.runHistory).take(ScheduledTask.MAX_RUN_HISTORY),
                fireCount = t.firesSoFar + 1,
            )
        }
    }

    private fun registerAlarm(task: ScheduledTask) {
        val triggerAt = task.nextTriggerMs() ?: run {
            AppLogger.warning(TAG, "task ${task.id} has no next trigger — skipping register")
            return
        }
        val pi = buildPendingIntent(task.id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            AppLogger.info(TAG, "registered task=${task.id} label=\"${task.label}\" triggerAt=$triggerAt")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back
            // to inexact so we still fire eventually rather than dropping.
            AppLogger.warning(
                TAG,
                "exact-alarm denied for task=${task.id} (${e.message}); falling back to inexact",
            )
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for task=${task.id}: ${t2.message}")
            }
        }
    }

    private fun cancelAlarm(taskId: String) {
        val pi = buildPendingIntent(taskId)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val requestCode = taskId.hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Posts when a scheduled task finishes running."
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ScheduledTaskManager"

        /**
         * [T-android-scheduled-fire-claim] Tolerance for an alarm delivered a
         * moment before its slot (clock granularity). Exact alarms are not
         * delivered early, so anything well before the slot is a stale
         * registration, not the fire.
         */
        private const val ALARM_EARLY_SLACK_MS = 5_000L

        /**
         * [T-android-scheduled-fire-claim] Whether an alarm delivered at [now]
         * is [t]'s pending fire: the slot has come, and a --after has not
         * fired / an interval has count left. Pure, for tests. Calendar and
         * on-completion kinds are not decided here.
         */
        internal fun relativeAlarmDue(t: ScheduledTask, now: Long): Boolean {
            val anchor = t.anchorMs ?: t.createdAt
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER ->
                    (t.triggeredCount ?: 0) < 1 && anchor + (t.delaySec ?: 0L) * 1000 <= now + ALARM_EARLY_SLACK_MS
                ScheduledTriggerKind.INTERVAL -> {
                    val iv = t.intervalSec ?: return false
                    (t.maxFires == null || (t.triggeredCount ?: 0) < t.maxFires) &&
                        anchor + iv * 1000 <= now + ALARM_EARLY_SLACK_MS
                }
                ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> true
            }
        }

        /**
         * [T-android-scheduled-triggers] What a relative trigger's row becomes
         * when its alarm fires at [now], and whether to arm the next one. Pure,
         * for tests; null for kinds the alarm does not drive.
         *  - AFTER: fired once -> disabled (kept in the list, like ONCE).
         *  - INTERVAL: counted and re-anchored to [now]; the last of --count
         *    disables it instead of arming another.
         */
        internal fun afterAlarmFire(t: ScheduledTask, now: Long): Pair<ScheduledTask, Boolean>? = when (t.triggerKind) {
            ScheduledTriggerKind.AFTER -> t.copy(enabled = false, triggeredCount = 1) to false
            ScheduledTriggerKind.INTERVAL -> {
                val n = (t.triggeredCount ?: 0) + 1
                val next = t.copy(triggeredCount = n, anchorMs = now)
                if (t.maxFires != null && n >= t.maxFires) next.copy(enabled = false) to false else next to true
            }
            ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> null
        }

        /**
         * [T-android-scheduled-triggers] [incoming] with the bookkeeping it
         * leaves empty taken from [prev]. Pure, for tests.
         */
        internal fun mergeBookkeeping(incoming: ScheduledTask, prev: ScheduledTask): ScheduledTask =
            incoming.copy(
                runHistory = incoming.runHistory.ifEmpty { prev.runHistory },
                fireCount = incoming.fireCount ?: prev.fireCount,
                anchorMs = incoming.anchorMs ?: prev.anchorMs,
                triggeredCount = incoming.triggeredCount ?: prev.triggeredCount,
            )
        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val CHANNEL_ID = "minis_scheduled_tasks"
        private const val CHANNEL_NAME = "Scheduled Tasks"
    }
}
