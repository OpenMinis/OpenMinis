package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-ui-read] GUI-agent eyes: dump the accessibility tree of the foreground
 * screen as a bounded text snapshot. The agent then reasons about the UI
 * (what is on screen, where to tap/type) and acts through `android-a11y-cli`
 * in shell_execute — closing the see → decide → act → verify loop that
 * Muse/Operit ship as UiAgentRunner, but reusing our existing accessibility
 * CLI instead of a VirtualDisplay stack.
 *
 * Requires the AccessibilityService permission; the CLI returns a clear
 * message when it is not granted.
 */
object UiReadTool {
    const val NAME = "ui_read"
    const val MAX_CHARS = 12_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a bounded snapshot of the current foreground screen via the " +
            "device accessibility tree (labels, texts, clickable nodes, positions). " +
            "Use together with android-a11y-cli in shell_execute to drive the system UI: " +
            "read screen → decide → tap/type → read again to verify. " +
            "Requires Accessibility permission; returns a hint when missing.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, e.g. 'Read current screen'."),
            "max_chars" to AgentToolParam("integer", "Optional cap on returned characters (default 6000, max 12000)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "max_chars"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid args: ${it.message}", false)
        }
        val cap = (obj.optInt("max_chars", 6_000)).coerceIn(1_000, MAX_CHARS)
        val toolTitle = obj.optString("tool_title", "ui_read")
        // [T-ui-read-honest-status] `|| true` plus a hardcoded success=true made
        // "no accessibility service" and "dump failed" indistinguishable from a
        // real (empty) screen, so the GUI loop's read-again-to-verify treated
        // dead reads as observations. The CLI's exit status now decides.
        val script = """
            if [ -x /usr/local/bin/android-a11y-cli ]; then
              android-a11y-cli read-screen 2>&1 | head -c $cap
            else
              echo "android-a11y-cli not available in sandbox; use su_exec to reach host tools"
              exit 127
            fi
        """.trimIndent()
        return runCatching {
            val process = ProcessBuilder("/bin/bash", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val exit = process.exitValue()
            val trimmed = out.trim().take(cap)
            val body = trimmed.ifBlank { "（屏幕为空或读屏失败——确认已授予无障碍权限：Settings → 权限）" }
            val code = when {
                trimmed.contains("SERVICE_NOT_RUNNING") -> ToolErrorCode.PERMISSION_DENIED
                exit == 127 -> ToolErrorCode.UNSUPPORTED
                exit != 0 -> ToolErrorCode.EXECUTION_FAILED
                else -> null
            }
            ToolExecutionResult(body, exit == 0, errorCode = code, toolTitle = toolTitle)
        }.getOrElse {
            ToolExecutionResult("Error: ${it.message}", false, errorCode = ToolErrorCode.EXECUTION_FAILED, toolTitle = toolTitle)
        }
    }
}
