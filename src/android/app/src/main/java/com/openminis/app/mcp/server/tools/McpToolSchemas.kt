package com.openminis.app.mcp.server.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-refactor] Tool descriptors for listTools — declarations separated
 * from implementations (after OmniBot's McpToolDefinitions). Each entry is
 * consumed by the dispatcher's registry; the JSON shape is byte-identical to
 * the pre-refactor listTools output (tests assert the exact tool surface).
 */
internal object McpToolSchemas {

    private fun prop(type: String, description: String? = null): JSONObject = JSONObject().apply {
        put("type", type)
        if (description != null) put("description", description)
    }

    private fun schema(vararg required: String, props: JSONObject = JSONObject()): JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray().apply { required.forEach { put(it) } })
        }

    fun descriptor(spec: McpToolSpec): JSONObject = JSONObject().apply {
        put("name", spec.name)
        put("description", spec.description)
        put("inputSchema", spec.schema)
    }

    val fileTransferPullSchema = schema("path", props = JSONObject().apply {
        put("offset", JSONObject().put("type", "integer").put("description", "Byte offset to start from (default 0)."))
        put("maxBytes", JSONObject().put("type", "integer").put("description", "Maximum bytes to return (default 262144, hard cap 1048576)."))
    })

    val scheduleTaskListSchema = schema(props = JSONObject())

    val deviceInfo = "Get device information: model, manufacturer, Android version, battery level."

    val shellExec = "Execute a command in an isolated Linux process (Ubuntu 24.04 arm64 via PRoot). " +
        "The command runs in GNU bash (/bin/bash). stdout and stderr are merged. " +
        "Use 'command' to specify the shell command, optional 'timeout_sec' to limit execution."

    val shellExecSchema = schema("command", props = JSONObject().apply {
        put("command", prop("string"))
        put("timeout_sec", prop("integer"))
    })

    val uiRead = "Read a bounded snapshot of the current foreground screen via the accessibility tree."

    val uiAction = "Perform one UI action via the accessibility service: tap, type, swipe, scroll, back, open_app, open_url, long_press."

    val uiActionSchema = schema("action", props = JSONObject().apply {
        put("action", prop("string"))
        put("x", prop("integer"))
        put("y", prop("integer"))
        put("x2", prop("integer"))
        put("y2", prop("integer"))
        put("text", prop("string"))
        put("direction", prop("string"))
        put("package_name", prop("string"))
        put("url", prop("string"))
    })

    val fileRead = "Read a file from the Linux filesystem. Restricted to /var/minis/ and /sdcard/ prefixes. " +
        "Binary files return a type guess and a bounded hex preview instead of raw contents."

    val fileReadSchema = schema("path", props = JSONObject().apply {
        put("path", prop("string"))
    })

    val fileWrite = "Write a UTF-8 text file to the Linux filesystem. " +
        "Restricted to /var/minis/ and /sdcard/ prefixes; max 256 KB per call."

    val fileWriteSchema = schema("path", "content", props = JSONObject().apply {
        put("path", prop("string"))
        put("content", prop("string"))
        put("append", prop("boolean", "Append to the file instead of overwriting (default false)."))
    })

    val fileTransferPull = "Read a binary or text file from the Linux filesystem and return it base64-encoded " +
        "with size and sha256. Same path restrictions as file_read; max 8 MB per call."

    val fileTransferPush = "Write base64-encoded content to a file on the Linux filesystem (binary-safe). " +
        "Same path restrictions as file_write; max 8 MB decoded per call."

    val fileTransferPushSchema = schema("path", "content_base64", props = JSONObject().apply {
        put("path", prop("string"))
        put("content_base64", prop("string"))
    })

    val scheduleTaskCreate = "Create a scheduled task that fires an agent prompt. " +
        "Schedule: '30m', '2h', '1d' (once) or 'every 30m' / 'every 2h' / 'every 1d' (repeating)."

    val scheduleTaskCreateSchema = schema("schedule", "prompt", props = JSONObject().apply {
        put("name", prop("string"))
        put("schedule", prop("string"))
        put("prompt", prop("string"))
    })

    val scheduleTaskList = "List all scheduled tasks with id, name, schedule, next trigger, and enabled state."

    val scheduleTaskUpdate = "Enable or disable a scheduled task by id (accepts a unique id prefix)."

    val scheduleTaskUpdateSchema = schema("id", "enabled", props = JSONObject().apply {
        put("id", prop("string"))
        put("enabled", prop("boolean"))
    })

    val scheduleTaskDelete = "Delete a scheduled task by id (accepts a unique id prefix)."

    val scheduleTaskDeleteSchema = schema("id", props = JSONObject().apply {
        put("id", prop("string"))
    })

    val alarmReminderCreate = "Create a one-shot reminder that runs an agent prompt after a delay. " +
        "Equivalent to a schedule_task with a 'once' schedule."

    val alarmReminderCreateSchema = schema("delay_sec", "message", props = JSONObject().apply {
        put("delay_sec", prop("integer", "Seconds from now until the reminder fires (60-86400)."))
        put("message", prop("string", "Agent prompt to run when the reminder fires."))
        put("name", prop("string"))
    })

    val contextAppsQuery = "List launchable apps on this device (label + package name). " +
        "Optional 'filter' substring matches label or package, case-insensitive. " +
        "Optional 'limit' caps results (default 50, max 200)."

    val contextAppsQuerySchema = schema(props = JSONObject().apply {
        put("filter", prop("string"))
        put("limit", prop("integer"))
    })
}
