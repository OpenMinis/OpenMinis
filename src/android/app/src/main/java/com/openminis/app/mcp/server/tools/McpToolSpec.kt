package com.openminis.app.mcp.server.tools

import android.content.Context
import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.SecurityGate
import org.json.JSONObject

/**
 * [T-mcp-refactor] One MCP tool = one self-contained spec: name, description,
 * inputSchema and the call handler. Adding a tool means adding a spec to its
 * domain file — the dispatcher itself never changes (registry pattern, after
 * OmniBot's DeviceTool / taixu's HarnessToolProvider).
 */
internal class McpToolSpec(
    val name: String,
    val description: String,
    val schema: JSONObject,
    val handler: (JSONObject) -> McpServerCore.CallResult,
)

/**
 * Dependencies every domain tool file shares. Constructed once by the
 * dispatcher; [context] is nullable for plain-JVM unit tests (every
 * dereference degrades to an error result, never a crash).
 */
internal class McpToolDeps(
    val context: Context?,
    val gate: SecurityGate,
    val shellMode: () -> PermissionMode,
)
