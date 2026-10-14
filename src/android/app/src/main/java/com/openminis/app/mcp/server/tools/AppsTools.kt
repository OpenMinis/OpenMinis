package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject

/** [T-mcp-refactor] context_apps_query — launcher-reachable app inventory. */
internal fun appsTools(deps: McpToolDeps): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "context_apps_query",
        description = McpToolSchemas.contextAppsQuery,
        schema = McpToolSchemas.contextAppsQuerySchema,
        handler = { contextAppsQuery(deps, it) },
    ),
)

private fun contextAppsQuery(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val ctx = deps.context
        ?: return McpToolShared.error("Error: no host context available")
    val filter = arguments.optString("filter", "").trim().lowercase()
    val limit = arguments.optInt("limit", 50).coerceIn(1, 200)
    return runCatching {
        val pm = ctx.packageManager
        val launcher = android.content.Intent(
            android.content.Intent.ACTION_MAIN,
            null,
        ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != ctx.packageName }
            .map { info ->
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                Triple(label, info.packageName, info.name)
            }
            .filter { (label, pkg, _) ->
                filter.isEmpty() ||
                    label.lowercase().contains(filter) ||
                    pkg.lowercase().contains(filter)
            }
            .sortedBy { (label, _, _) -> label.lowercase() }
            .take(limit)
            .toList()
        val arr = JSONArray()
        for ((label, pkg, activity) in apps) {
            arr.put(
                JSONObject().apply {
                    put("label", label)
                    put("package", pkg)
                    put("activity", activity)
                },
            )
        }
        McpServerCore.CallResult(
            JSONArray().put(
                McpToolShared.text(
                    JSONObject().put("count", apps.size).put("apps", arr).toString(2),
                ),
            ),
        )
    }.getOrElse {
        McpToolShared.error("Error querying apps: ${it.message}")
    }
}
