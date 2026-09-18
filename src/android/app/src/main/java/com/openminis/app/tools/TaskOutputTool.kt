package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.DetachedShell
import org.json.JSONObject

object TaskOutputTool {

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = "task_output",
        description = "Read stdout of a background job started with shell_execute detach=true. " +
            "Omit task_id to list this session's detached tasks. " +
            "Pass kill=true to stop a task. Optional timeout (seconds) waits for more output.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user.",
            ),
            "task_id" to AgentToolParam("string", "Id returned by shell_execute detach=true. Omit to list tasks."),
            "offset" to AgentToolParam("integer", "Byte offset to continue from (from the previous bytes= line). Default 0."),
            "timeout" to AgentToolParam("integer", "Seconds to wait for more output or process exit (0 = return immediately, max 60)."),
            "kill" to AgentToolParam("boolean", "If true, destroy the detached process."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "task_id", "offset", "timeout", "kill"),
    )

    suspend fun execute(argsJson: String, sessionId: String): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        val toolTitle = args.optString("tool_title").ifBlank { "task_output" }
        val taskId = args.optString("task_id").trim()
        val offset = args.optLong("offset", 0L).coerceAtLeast(0L)
        val timeout = args.optInt("timeout", 0).coerceIn(0, 60)
        val kill = DetachedShell.jsonFlag(args, "kill")

        if (taskId.isEmpty()) {
            return ToolExecutionResult(
                output = DetachedShell.formatList(DetachedShell.list(sessionId)),
                success = true,
                toolTitle = toolTitle,
            )
        }
        val snap0 = DetachedShell.snapshot(taskId)
            ?: return ToolExecutionResult(
                output = "Unknown task_id=$taskId. Omit task_id to list this session's tasks.",
                success = false,
                toolTitle = toolTitle,
            )
        if (snap0.sessionId != sessionId) {
            return ToolExecutionResult(
                output = "task_id=$taskId belongs to another session.",
                success = false,
                toolTitle = toolTitle,
            )
        }
        if (kill) {
            DetachedShell.kill(taskId)
            val after = DetachedShell.snapshot(taskId) ?: snap0
            val slice = DetachedShell.readLog(taskId, offset) ?: DetachedShell.LogSlice("", 0, 0, 0, false)
            return ToolExecutionResult(
                output = "killed=true\n" + DetachedShell.formatOutput(after, slice),
                success = true,
                toolTitle = toolTitle,
            )
        }
        val slice = if (timeout > 0) {
            DetachedShell.waitForMore(taskId, offset, timeout)
        } else {
            DetachedShell.readLog(taskId, offset)
        } ?: DetachedShell.LogSlice("", offset, offset, 0, false)
        val snap = DetachedShell.snapshot(taskId) ?: snap0
        return ToolExecutionResult(
            output = DetachedShell.formatOutput(snap, slice),
            success = true,
            toolTitle = toolTitle,
        )
    }
}
