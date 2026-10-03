package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray

/**
 * [T-android-scheduled-tasks-design] SharedPreferences-backed JSON array of
 * [ScheduledTask] rows. Same pattern as [com.openminis.app.offload.AlarmOffloadManager]
 * — small dataset, low write frequency, no Room migration cost.
 */
class ScheduledTaskStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { ScheduledTask.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    fun get(taskId: String): ScheduledTask? = all().firstOrNull { it.id == taskId }

    // [T-android-scheduled-fire-claim] Every write is a read-modify-write of
    // the WHOLE list, and writers run on several threads (the alarm receiver,
    // a run's completion on IO, the task screens, the CLI). Unserialised, two
    // writes that overlap lose one of them — a count or anchor that just moved
    // silently moves back, and an interval then overshoots its --count.
    // apply() updates the in-memory map before returning, so the next read
    // under the lock already sees the previous write.
    fun upsert(task: ScheduledTask): Unit = synchronized(LOCK) {
        val current = all().filter { it.id != task.id }
        write(current + task)
    }

    fun delete(taskId: String): Unit = synchronized(LOCK) {
        write(all().filter { it.id != taskId })
    }

    /**
     * [T-android-scheduled-fire-claim] Atomic read-modify-write of one row:
     * [transform] sees the row as stored right now and returns its new value,
     * or null to leave it unchanged. Returns what was written (null when the
     * row is missing or nothing was written).
     */
    fun update(taskId: String, transform: (ScheduledTask) -> ScheduledTask?): ScheduledTask? = synchronized(LOCK) {
        val tasks = all()
        val cur = tasks.firstOrNull { it.id == taskId } ?: return null
        val next = transform(cur) ?: return null
        write(tasks.map { if (it.id == taskId) next else it })
        next
    }

    fun clear() {
        prefs.edit().remove(KEY_TASKS).apply()
    }

    private fun write(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        prefs.edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    /**
     * Cold flow that emits the current task list whenever the prefs file
     * changes. Used by [ScheduledTasksViewModel] to keep the list screen
     * live.
     */
    fun observe(): Flow<List<ScheduledTask>> = callbackFlow {
        trySend(all())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TASKS || key == null) {
                trySend(all())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "ScheduledTaskStore"

        /** Process-wide: stores are constructed per call site, the file is one. */
        private val LOCK = Any()
        private const val PREFS_NAME = "minis_scheduled_tasks_prefs"
        private const val KEY_TASKS = "tasks_json"
    }
}
