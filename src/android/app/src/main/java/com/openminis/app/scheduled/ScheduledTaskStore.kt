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

    private val lock = Any()

    fun upsert(task: ScheduledTask) {
        mutate { cur ->
            cur.removeAll { it.id == task.id }
            cur.add(task)
        }
    }

    fun delete(taskId: String): Boolean {
        var removed = false
        mutate { cur ->
            removed = cur.removeAll { it.id == taskId }
        }
        return removed && get(taskId) == null
    }

    /** Exact id, or a unique prefix of at least 4 characters. */
    fun resolveId(idOrPrefix: String): String? {
        val tasks = all()
        tasks.find { it.id == idOrPrefix }?.let { return it.id }
        if (idOrPrefix.length < 4) return null
        return tasks.filter { it.id.startsWith(idOrPrefix) }.singleOrNull()?.id
    }

    private fun mutate(block: (MutableList<ScheduledTask>) -> Unit) {
        synchronized(lock) {
            val cur = all().toMutableList()
            block(cur)
            write(cur)
        }
    }

    /**
     * Null model pins that name entries removed by a provider model-list clear.
     *
     * [T-scheduled-binding-exact] The pin used to be tested with
     * `binding.contains(id)`. Entry ids are `instance/model`, so removing
     * `inst/gpt-4` also un-pinned every task bound to `inst/gpt-4o` — the same
     * over-match [com.openminis.app.data.db.ChatDao.clearBindingReferencing]
     * had, and it silently dropped the user's model choice. Match the parsed
     * `entryId` against the removed set instead (same JSON shape
     * [ScheduledAgentRunner] reads; the only writer,
     * [com.openminis.app.ui.scheduled.ScheduledTaskEditScreen], always emits
     * that shape.
     */
    fun dropEntryRefs(removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        mutate { cur ->
            for (i in cur.indices) {
                val task = cur[i]
                val binding = task.modelBinding
                val entryId = binding?.let { entryIdOfBinding(it) }
                val bindingHit = entryId != null && entryId in removedIds
                val modelHit = task.modelId != null && task.modelId in removedIds
                if (!bindingHit && !modelHit) continue
                cur[i] = task.copy(
                    modelId = if (modelHit) null else task.modelId,
                    modelBinding = if (bindingHit) null else binding,
                )
            }
        }
    }

    private fun entryIdOfBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(KEY_TASKS).commit()
    }

    private fun write(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        // apply() returns before the file is durable. A later delete then looked
        // successful while a stale flush restored the task under a new id.
        prefs.edit().putString(KEY_TASKS, arr.toString()).commit()
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
        private const val PREFS_NAME = "minis_scheduled_tasks_prefs"
        private const val KEY_TASKS = "tasks_json"
    }
}
