package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
        findIdentical(task)?.let { return it }
        store.upsert(task)
        if (task.enabled) registerAlarm(task)
        return task
    }

    /**
     * A retry of the same label, time, prompt and repeat must not mint a second
     * id after the caller deleted the id that was returned.
     */
    fun findIdentical(task: ScheduledTask): ScheduledTask? = store.all().firstOrNull {
        it.label == task.label &&
            it.timeOfDayHour == task.timeOfDayHour &&
            it.timeOfDayMinute == task.timeOfDayMinute &&
            it.repeatMode == task.repeatMode &&
            it.customDays == task.customDays &&
            it.prompt == task.prompt &&
            it.targetMode == task.targetMode &&
            it.enabled == task.enabled
    }

    fun resolveId(idOrPrefix: String): String? = store.resolveId(idOrPrefix)

    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        store.upsert(task)
        if (task.enabled) registerAlarm(task)
        return task
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        val t = store.get(taskId) ?: return
        val updated = t.copy(enabled = enabled)
        store.upsert(updated)
        if (enabled) registerAlarm(updated) else cancelAlarm(taskId)
    }

    fun delete(taskId: String): Boolean {
        val id = store.resolveId(taskId) ?: return false
        // One cancel is enough: cancelAlarm is idempotent (PendingIntent
        // equality), the second back-to-back call was pure noise.
        cancelAlarm(id)
        val removed = store.delete(id)
        return removed && store.get(id) == null
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
    fun rescheduleNext(taskId: String) {
        val t = store.get(taskId) ?: return
        if (t.repeatMode == ScheduledRepeatMode.ONCE) {
            // Mark fired ONCE task as disabled — keep row so the user can
            // see its last result, re-enable if they want to re-run.
            store.upsert(t.copy(enabled = false))
            return
        }
        if (t.repeatMode == ScheduledRepeatMode.INTERVAL) {
            val intervalMs = t.intervalMinutes * 60_000L
            if (intervalMs <= 0L) return
            val updated = t.copy(fireAtMs = System.currentTimeMillis() + intervalMs)
            store.upsert(updated)
            registerAlarm(updated)
            return
        }
        registerAlarm(t)
    }

    fun markFired(taskId: String, sessionId: String?, resultPreview: String?, ok: Boolean = true) {
        val t = store.get(taskId) ?: return
        val now = System.currentTimeMillis()
        // [T-android-scheduled-tasks-run-records] Prepend a run record
        // (newest-first), capped at MAX_RUN_HISTORY. lastResult* are kept in
        // sync for back-compat but are no longer surfaced in the list UI.
        val run = ScheduledRun(firedAt = now, sessionId = sessionId, preview = resultPreview, ok = ok)
        val history = (listOf(run) + t.runHistory).take(ScheduledTask.MAX_RUN_HISTORY)
        store.upsert(
            t.copy(
                lastFiredAt = now,
                lastResultPreview = resultPreview,
                lastResultSessionId = sessionId,
                runHistory = history,
            ),
        )
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
        // minSdk 26 (O): the SDK_INT guard was unreachable.
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
        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val CHANNEL_ID = "minis_scheduled_tasks"
        private const val CHANNEL_NAME = "Scheduled Tasks"
    }
}
