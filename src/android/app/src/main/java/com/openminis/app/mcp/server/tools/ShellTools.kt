package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-refactor] shell_exec — gated guest execution. The gate dance lives
 * once in [McpToolShared.gateAndRun]; this file only owns the argument
 * contract and the result envelope.
 */
internal fun shellTools(deps: McpToolDeps): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "shell_exec",
        description = McpToolSchemas.shellExec,
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().apply {
                    put("type", "string")
                    put("description", "Shell command to run in the Ubuntu guest.")
                })
                put("timeout_sec", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Timeout in seconds (1-900, default 60).")
                })
            })
            put("required", JSONArray().put("command"))
        },
        handler = { shellExec(deps, it) },
    ),
)

private fun shellExec(
    deps: McpToolDeps,
    arguments: JSONObject,
): McpServerCore.CallResult {
    val command = arguments.optString("command", "").ifEmpty {
        return McpToolShared.error("Error: 'command' is required")
    }
    val timeoutSec = arguments.optInt("timeout_sec", 60).coerceIn(1, 900)

    return McpToolShared.gateAndRun(deps, "shell_exec", command) {
        val result = McpToolShared.runInGuest(command, timeoutSec)
        val output = JSONObject().apply {
            put("exit_code", result.exitCode)
            put("output", result.output)
            put("duration_ms", result.durationMs)
        }
        McpServerCore.CallResult(
            JSONArray().put(McpToolShared.text(output.toString(2))),
            isError = result.exitCode != 0,
        )
    }
}
