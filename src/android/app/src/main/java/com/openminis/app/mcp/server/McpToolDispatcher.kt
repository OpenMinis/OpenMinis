package com.openminis.app.mcp.server

import android.content.Context
import com.openminis.app.mcp.server.tools.McpToolDeps
import com.openminis.app.mcp.server.tools.McpToolShared
import com.openminis.app.mcp.server.tools.McpToolSpec
import com.openminis.app.mcp.server.tools.McpToolSchemas
import com.openminis.app.mcp.server.tools.appsTools
import com.openminis.app.mcp.server.tools.deviceTools
import com.openminis.app.mcp.server.tools.fileTools
import com.openminis.app.mcp.server.tools.scheduleTools
import com.openminis.app.mcp.server.tools.shellTools
import com.openminis.app.mcp.server.tools.uiTools
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.SecurityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-refactor] Registry-based dispatcher. The 14 built-in tools live in
 * `tools/` as self-contained [McpToolSpec] entries grouped by domain
 * (device / shell / ui / file / schedule / apps) — after OmniBot's DeviceTool
 * registry and taixu's HarnessToolProvider. Adding a tool means adding a
 * spec to its domain file; this class never changes.
 *
 * The companion keeps forwarding the pre-refactor public statics so the
 * existing JVM tests (777 lines of contract) stay untouched.
 *
 * @param context nullable for plain-JVM tests — every dereference degrades
 *   to an error result, never a crash.
 * @param gate the security gate every command goes through.
 * @param shellMode supplies the current permission mode (ALLOW_ALL / STRICT).
 */
class McpToolDispatcher(
    private val context: Context?,
    private val gate: SecurityGate,
    private val shellMode: () -> PermissionMode,
) : McpServerCore.ToolDispatcher {

    private val deps = McpToolDeps(context, gate, shellMode)

    private val tools: List<McpToolSpec> = buildList {
        addAll(deviceTools(deps))
        addAll(shellTools(deps))
        addAll(uiTools(deps))
        addAll(fileTools(deps))
        addAll(scheduleTools(deps))
        addAll(appsTools(deps))
    }

    private val byName: Map<String, McpToolSpec> = tools.associateBy { it.name }

    override fun listTools(): JSONArray = JSONArray().apply {
        tools.forEach { put(McpToolSchemas.descriptor(it)) }
    }

    override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
        val tool = byName[name]
            ?: return McpToolShared.error("Error: unknown tool '$name'. Call list_tools first.")
        return tool.handler(arguments)
    }

    companion object {
        // ── Pre-refactor public surface (tests depend on these) ──────────────

        @JvmStatic
        fun text(s: String): JSONObject = McpToolShared.text(s)

        @JvmStatic
        fun shellEscape(s: String): String = McpToolShared.shellEscape(s)

        @JvmStatic
        fun normalizePath(p: String): String = McpToolShared.normalizePath(p)

        @JvmStatic
        fun validateFilePath(p: String): String? = McpToolShared.validateFilePath(p)

        @JvmStatic
        fun validateCoord(v: Any?, name: String): Pair<Int?, String?> =
            McpToolShared.validateCoord(v, name)

        val MAX_COORD: Int get() = McpToolShared.MAX_COORD

        val MAX_FILE_WRITE_CHARS: Int get() = McpToolShared.MAX_FILE_WRITE_CHARS

        val UI_ACTIONS: Set<String> get() = McpToolShared.UI_ACTIONS

        val SCROLL_DIRECTIONS: Set<String> get() = McpToolShared.SCROLL_DIRECTIONS

        val SHELL_STRICT_HINT: String get() = McpToolShared.SHELL_STRICT_HINT

        val SESSION_ID: String get() = McpToolShared.SESSION_ID
    }
}
