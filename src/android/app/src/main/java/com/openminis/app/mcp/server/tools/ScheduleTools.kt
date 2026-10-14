package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.scheduled.CronScheduler
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledRepeatMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/** [T-mcp-refactor] schedule_task_* + alarm_reminder_create. */
internal fun scheduleTools(deps: McpToolDeps): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "schedule_task_create",
        description = McpToolSchemas.scheduleTaskCreate,
        schema = McpToolSchemas.scheduleTaskCreateSchema,
        handler = { scheduleTaskCreate(deps, it) },
    ),
    McpToolSpec(
        name = "schedule_task_list",
        description = McpToolSchemas.scheduleTaskList,
        schema = McpToolSchemas.scheduleTaskListSchema,
        handler = { scheduleTaskList(deps) },
    ),
    McpToolSpec(
        name = "schedule_task_update",
        description = McpToolSchemas.scheduleTaskUpdate,
        schema = McpToolSchemas.scheduleTaskUpdateSchema,
        handler = { scheduleTaskUpdate(deps, it) },
    ),
    McpToolSpec(
        name = "schedule_task_delete",
        description = McpToolSchemas.scheduleTaskDelete,
        schema = McpToolSchemas.scheduleTaskDeleteSchema,
        handler = { scheduleTaskDelete(deps, it) },
    ),
    McpToolSpec(
        name = "alarm_reminder_create",
        description = McpToolSchemas.alarmReminderCreate,
        schema = McpToolSchemas.alarmReminderCreateSchema,
        handler = { alarmReminderCreate(deps, it) },
    ),
)

/** Resolve the ScheduledTaskManager, or an error result when no context. */
private fun taskManager(deps: McpToolDeps): Pair<ScheduledTaskManager?, McpServerCore.CallResult?> {
    val ctx = deps.context ?: return null to McpToolShared.error("Error: no host context available")
    return ScheduledTaskManager(ctx) to null
}

private fun scheduleTaskCreate(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val schedule = arguments.optString("schedule", "").trim()
    val prompt = arguments.optString("prompt", "").trim()
    if (prompt.isEmpty()) {
        return McpToolShared.error("Error: 'prompt' is required")
    }
    CronScheduler.parseSchedule(schedule)
        ?: return McpToolShared.error(
            "Error: schedule must look like '30m', '2h', '1d', or 'every 30m'/'every 2h'/'every 1d'"
        )
    val (manager, err) = taskManager(deps)
    if (manager == null) return err!!
    return runCatching {
        createScheduledTask(manager, schedule, prompt, arguments.optString("name", "").trim())
    }.getOrElse {
        McpToolShared.error("Error creating task: ${it.message}")
    }
}

private fun alarmReminderCreate(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val delaySec = arguments.optInt("delay_sec", 0)
    if (delaySec < 60 || delaySec > 86_400) {
        return McpToolShared.error("Error: 'delay_sec' must be 60-86400")
    }
    val message = arguments.optString("message", "").trim()
    if (message.isEmpty()) {
        return McpToolShared.error("Error: 'message' is required")
    }
    val (manager, err) = taskManager(deps)
    if (manager == null) return err!!
    return runCatching {
        val now = System.currentTimeMillis()
        val fireAt = now + delaySec * 1000L
        val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            label = arguments.optString("name", "").trim().ifBlank { message.take(32) },
            timeOfDayHour = cal.get(Calendar.HOUR_OF_DAY),
            timeOfDayMinute = cal.get(Calendar.MINUTE),
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = message,
            fireAtMs = fireAt,
            createdAt = now,
        )
        val saved = manager.create(task)
        McpServerCore.CallResult(
            JSONArray().put(
                McpToolShared.text(
                    JSONObject().apply {
                        put("ok", true)
                        put("id", saved.id)
                        put("name", saved.label)
                        put("fireAtMs", saved.fireAtMs ?: fireAt)
                        put("repeat", "ONCE")
                    }.toString(2),
                ),
            ),
        )
    }.getOrElse {
        McpToolShared.error("Error creating reminder: ${it.message}")
    }
}

private fun createScheduledTask(
    manager: ScheduledTaskManager,
    schedule: String,
    prompt: String,
    name: String,
): McpServerCore.CallResult {
    val parsed = CronScheduler.parseSchedule(schedule)
        ?: return McpToolShared.error("Error: bad schedule '$schedule'")
    val now = System.currentTimeMillis()
    val fireAt = now + parsed.firstDelayMs
    val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
    val interval = parsed.kind == "interval"
    val task = ScheduledTask(
        id = UUID.randomUUID().toString(),
        label = name.ifBlank { prompt.take(32) },
        timeOfDayHour = cal.get(Calendar.HOUR_OF_DAY),
        timeOfDayMinute = cal.get(Calendar.MINUTE),
        repeatMode = if (interval) ScheduledRepeatMode.INTERVAL else ScheduledRepeatMode.ONCE,
        prompt = prompt,
        intervalMinutes = parsed.intervalMinutes,
        fireAtMs = fireAt,
        createdAt = now,
    )
    val saved = manager.create(task)
    return McpServerCore.CallResult(
        JSONArray().put(
            McpToolShared.text(
                JSONObject().apply {
                    put("ok", true)
                    put("id", saved.id)
                    put("name", saved.label)
                    put("schedule", schedule)
                    put("fireAtMs", saved.fireAtMs ?: fireAt)
                    put("repeat", saved.repeatMode.name)
                    if (saved.id != task.id) put("already_existed", true)
                }.toString(2),
            ),
        ),
    )
}

private fun scheduleTaskList(deps: McpToolDeps): McpServerCore.CallResult {
    val (manager, err) = taskManager(deps)
    if (manager == null) return err!!
    return runCatching {
        val tasks = manager.list()
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.label)
                    put("enabled", t.enabled)
                    put("repeat", t.repeatMode.name)
                    if (t.intervalMinutes > 0) put("intervalMinutes", t.intervalMinutes)
                    if (t.fireAtMs != null) put("fireAtMs", t.fireAtMs)
                    put("nextTriggerMs", t.nextTriggerMs() ?: JSONObject.NULL)
                    put("prompt", t.prompt.take(200))
                },
            )
        }
        McpServerCore.CallResult(
            JSONArray().put(
                McpToolShared.text(JSONObject().put("count", tasks.size).put("tasks", arr).toString(2))
            ),
        )
    }.getOrElse {
        McpToolShared.error("Error listing tasks: ${it.message}")
    }
}

private fun scheduleTaskUpdate(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val idRaw = arguments.optString("id", "").trim()
    if (idRaw.isEmpty()) {
        return McpToolShared.error("Error: 'id' is required")
    }
    val enabled = arguments.optBoolean("enabled", true)
    val (manager, err) = taskManager(deps)
    if (manager == null) return err!!
    return runCatching {
        val id = manager.resolveId(idRaw)
            ?: return McpToolShared.error("Error: no task matches id/prefix '$idRaw'")
        manager.setEnabled(id, enabled)
        McpServerCore.CallResult(
            JSONArray().put(McpToolShared.text("[$id ${if (enabled) "enabled" else "disabled"}]")),
        )
    }.getOrElse {
        McpToolShared.error("Error updating task: ${it.message}")
    }
}

private fun scheduleTaskDelete(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val idRaw = arguments.optString("id", "").trim()
    if (idRaw.isEmpty()) {
        return McpToolShared.error("Error: 'id' is required")
    }
    val (manager, err) = taskManager(deps)
    if (manager == null) return err!!
    return runCatching {
        val id = manager.resolveId(idRaw)
            ?: return McpToolShared.error("Error: no task matches id/prefix '$idRaw'")
        val deleted = manager.delete(id)
        McpServerCore.CallResult(
            JSONArray().put(McpToolShared.text(if (deleted) "[deleted $id]" else "[no task $id]")),
            isError = !deleted,
        )
    }.getOrElse {
        McpToolShared.error("Error deleting task: ${it.message}")
    }
}
