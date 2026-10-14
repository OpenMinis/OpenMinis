package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-ui-action] GUI-agent hands: a single typed entry point for the system
 * UI actions the accessibility CLI exposes, so the loop
 * `ui_read → decide → ui_action → ui_read (verify)` stays schema-validated
 * instead of the model hand-rolling `android-a11y-cli` flags in shell.
 *
 * Requires the AccessibilityService permission (same as [UiReadTool]).
 */
object UiActionTool {
    const val NAME = "ui_action"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Perform one UI action on the current foreground screen via the " +
            "accessibility service: tap coordinates, type text, swipe, scroll, press back, " +
            "open an app by package, or launch an intent URL. " +
            "Use with ui_read in a loop: read the screen to find a target, act, read again " +
            "to verify. Requires Accessibility permission.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, e.g. 'Tap the send button'."),
            "action" to AgentToolParam("string", "One of: tap, type, swipe, scroll, back, open_app, open_url, long_press."),
            "x" to AgentToolParam("integer", "X coordinate in screen pixels (required for tap, long_press, swipe start)."),
            "y" to AgentToolParam("integer", "Y coordinate in screen pixels (required for tap, long_press, swipe start)."),
            "x2" to AgentToolParam("integer", "Swipe end X (required for swipe)."),
            "y2" to AgentToolParam("integer", "Swipe end Y (required for swipe)."),
            "text" to AgentToolParam("string", "Text to type (required for type)."),
            "direction" to AgentToolParam("string", "up or down (for scroll)."),
            "package_name" to AgentToolParam("string", "Android package to launch (for open_app)."),
            "url" to AgentToolParam("string", "Intent URL (for open_url, e.g. https://…, tel:, geo:, market:)."),
            "window_token" to AgentToolParam(
                "string",
                "Optional window token from the last ui_read / ui dump. When present, the coordinate gesture is " +
                    "refused if the foreground screen changed since that snapshot — coordinates are validated against " +
                    "window consistency only, so this stays usable on video / live streams where node ids churn.",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "x", "y", "x2", "y2", "text",
            "direction", "package_name", "url", "window_token",
        ),
    )

    /** Single-quoted for `bash -c`; `it.replace` handles the nested-quote dance. */
    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** `--window <token>` suffix for coordinate gestures; empty when absent. */
    private fun windowFlag(obj: JSONObject): String =
        obj.optString("window_token").takeIf { it.isNotBlank() }?.let { " --window ${shQuote(it)}" } ?: ""

    /**
     * [T-ui-action-arg-quoting] Coordinates are model-supplied and get
     * interpolated into a `bash -c` command line. They used to be stringified
     * verbatim, so `x = "0 0; <cmd>"` executed as shell — and SecurityGate could
     * not stop it, because the gate approved `ui_action`, not the command
     * materialised here. Accept a plain integer only; anything else is null and
     * the caller refuses the action.
     */
    private fun coordinate(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val raw = obj.get(key)) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    /** Directions the accessibility CLI accepts (`--direction must be up|down|left|right`). */
    private val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right")

    fun execute(argsJson: String): ToolExecutionResult {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid args: ${it.message}", false)
        }
        val action = obj.optString("action").lowercase()
        val toolTitle = obj.optString("tool_title", "ui_action")

        /** Space-separated validated coordinates, or null when one is missing/bogus. */
        fun coords(vararg keys: String): String? {
            val out = ArrayList<Int>(keys.size)
            for (key in keys) {
                val value = coordinate(obj, key) ?: return null
                out.add(value)
            }
            return out.joinToString(" ")
        }

        val invalidArgs = ToolExecutionResult(
            "Error: bad arguments for action '$action' — coordinates must be plain integers, " +
                "direction must be one of ${SCROLL_DIRECTIONS.joinToString("/")}. " +
                "(支持: tap/type/swipe/scroll/back/open_app/open_url/long_press)",
            false,
            errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            recoveryHint = "Re-read the screen with ui_read and pass numeric x/y (and x2/y2 for swipe) " +
                "from that snapshot.",
            toolTitle = toolTitle,
        )

        val cmd: String = when (action) {
            "tap" -> coords("x", "y")?.let { "android-a11y-cli tap $it${windowFlag(obj)}" } ?: return invalidArgs
            "long_press" -> coords("x", "y")?.let { "android-a11y-cli long-press $it${windowFlag(obj)}" } ?: return invalidArgs
            "swipe" -> coords("x", "y", "x2", "y2")?.let { "android-a11y-cli swipe $it${windowFlag(obj)}" } ?: return invalidArgs
            "type" -> "android-a11y-cli type " + shQuote(obj.optString("text"))
            "scroll" -> {
                val direction = obj.optString("direction", "down").lowercase()
                if (direction !in SCROLL_DIRECTIONS) return invalidArgs
                "android-a11y-cli scroll $direction${windowFlag(obj)}"
            }
            "back" -> "android-a11y-cli back"
            "open_app" -> "android-shizuku-cli launch " + shQuote(obj.optString("package_name"))
            "open_url" -> "android-open " + shQuote(obj.optString("url"))
            else -> return ToolExecutionResult(
                "Error: unknown action '$action' (支持: tap/type/swipe/scroll/back/open_app/open_url/long_press)",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = "Choose one of: tap, type, swipe, scroll, back, open_app, open_url, long_press.",
                toolTitle = toolTitle,
            )
        }

        // [T-ui-action-arg-quoting] No `|| true`: it pinned bash's exit status to
        // 0, so every failed tap/type was reported to the model as a success and
        // the read-back-to-verify loop trusted it.
        val script = """
            if [ -x /usr/local/bin/android-a11y-cli ]; then
              $cmd 2>&1
            else
              echo "android-a11y-cli not available in sandbox"
            fi
        """.trimIndent()

        return runCatching {
            val process = ProcessBuilder("/bin/bash", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val trimmed = out.trim().ifBlank { "(无输出)" }
            val code = when {
                trimmed.contains("WINDOW_CHANGED") -> ToolErrorCode.STALE_OBSERVATION
                trimmed.contains("SERVICE_NOT_RUNNING") -> ToolErrorCode.PERMISSION_DENIED
                process.exitValue() != 0 -> ToolErrorCode.EXECUTION_FAILED
                else -> null
            }
            ToolExecutionResult(trimmed, process.exitValue() == 0, errorCode = code, toolTitle = toolTitle)
        }.getOrElse {
            ToolExecutionResult("Error: ${it.message}", false, toolTitle = toolTitle)
        }
    }
}
