package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-refactor] ui_read / ui_action — accessibility-driven device control.
 * Commands are assembled with validateCoord + shellEscape and executed in the
 * guest (android-a11y-cli lives in the rootfs, not on the host), gated exactly
 * like shell_exec.
 */
internal fun uiTools(deps: McpToolDeps): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "ui_read",
        description = McpToolSchemas.uiRead,
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        },
        handler = { uiRead(deps) },
    ),
    McpToolSpec(
        name = "ui_action",
        description = McpToolSchemas.uiAction,
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("description", "tap|long_press|type|swipe|scroll|back|open_app|open_url")
                })
                put("x", JSONObject().put("type", "integer"))
                put("y", JSONObject().put("type", "integer"))
                put("x2", JSONObject().put("type", "integer"))
                put("y2", JSONObject().put("type", "integer"))
                put("text", JSONObject().put("type", "string"))
                put("direction", JSONObject().put("type", "string"))
                put("package_name", JSONObject().put("type", "string"))
                put("url", JSONObject().put("type", "string"))
            })
            put("required", JSONArray().put("action"))
        },
        handler = { uiAction(deps, it) },
    ),
)

private fun uiRead(deps: McpToolDeps): McpServerCore.CallResult {
    val command = """
        if [ -x /usr/local/bin/android-a11y-cli ]; then
          android-a11y-cli read-screen 2>&1 | head -c 6000 || true
        else
          echo "android-a11y-cli not available in sandbox"
        fi
    """.trimIndent()
    return McpToolShared.gateAndRun(deps, "ui_read", command) {
        McpToolShared.executeInGuest(command, 30)
    }
}

private fun uiAction(
    deps: McpToolDeps,
    arguments: JSONObject,
): McpServerCore.CallResult {
    val action = arguments.optString("action", "").lowercase()
    if (action.isEmpty()) {
        return McpToolShared.error(
            "Error: 'action' is required (${McpToolShared.UI_ACTIONS.joinToString("/")})",
        )
    }
    if (action !in McpToolShared.UI_ACTIONS) {
        return McpToolShared.error(
            "Error: unknown action '$action'. Allowed: ${McpToolShared.UI_ACTIONS.joinToString(", ")}",
        )
    }
    // Build the command string with validateCoord + shellEscape on every
    // user-supplied value. Coordinates must be true integers in [0, MAX_COORD];
    // direction is validated against SCROLL_DIRECTIONS; strings are
    // single-quote-escaped. coordArgs returns the first validation error, if
    // any — the message ordering matches the original dispatcher.
    val cmd: String = when (action) {
        "tap" -> {
            val (v, err) = coordArgs(arguments, "x", "y")
            if (err != null) return McpToolShared.error("Error: $err")
            "android-a11y-cli tap ${McpToolShared.shellEscape(v[0])} ${McpToolShared.shellEscape(v[1])}"
        }
        "long_press" -> {
            val (v, err) = coordArgs(arguments, "x", "y")
            if (err != null) return McpToolShared.error("Error: $err")
            "android-a11y-cli long-press ${McpToolShared.shellEscape(v[0])} ${McpToolShared.shellEscape(v[1])}"
        }
        "type" -> {
            val textVal = arguments.optString("text", "")
            "android-a11y-cli type ${McpToolShared.shellEscape(textVal)}"
        }
        "swipe" -> {
            val (v, err) = coordArgs(arguments, "x", "y", "x2", "y2")
            if (err != null) return McpToolShared.error("Error: $err")
            "android-a11y-cli swipe ${McpToolShared.shellEscape(v[0])} ${McpToolShared.shellEscape(v[1])} " +
                "${McpToolShared.shellEscape(v[2])} ${McpToolShared.shellEscape(v[3])}"
        }
        "scroll" -> {
            val direction = arguments.optString("direction", "down").lowercase()
            if (direction !in McpToolShared.SCROLL_DIRECTIONS) {
                return McpToolShared.error(
                    "Error: invalid direction '$direction'. Allowed: ${McpToolShared.SCROLL_DIRECTIONS.joinToString(", ")}",
                )
            }
            "android-a11y-cli scroll ${McpToolShared.shellEscape(direction)}"
        }
        "back" -> "android-a11y-cli back"
        "open_app" -> {
            val pkg = arguments.optString("package_name", "")
            "android-shizuku-cli launch ${McpToolShared.shellEscape(pkg)}"
        }
        "open_url" -> {
            val url = arguments.optString("url", "")
            "android-open ${McpToolShared.shellEscape(url)}"
        }
        else -> error("unreachable: action validated above")
    }

    val script = """
        if [ -x /usr/local/bin/android-a11y-cli ]; then
          $cmd 2>&1 || true
        else
          echo "android-a11y-cli not available in sandbox"
        fi
    """.trimIndent()

    return McpToolShared.gateAndRun(deps, "ui_action", script) {
        McpToolShared.executeInGuest(script, 30)
    }
}

/**
 * Validate the named integer coordinates. Returns (values, null) or
 * (null, firstErrorMessage).
 */
private fun coordArgs(
    arguments: JSONObject,
    vararg names: String,
): Pair<List<String>, String?> {
    val values = mutableListOf<String>()
    for (n in names) {
        val pair = McpToolShared.validateCoord(arguments.opt(n), n)
        val err: String? = pair.second
        val v: Int? = pair.first
        if (err != null) return emptyList<String>() to err
        values.add(v!!.toString())
    }
    return values to null
}

