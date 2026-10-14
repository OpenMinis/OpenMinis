package com.openminis.app.mcp.server.tools

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject

/** [T-mcp-refactor] device_info — the only tool with no arguments and no gate. */
internal fun deviceTools(deps: McpToolDeps): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "device_info",
        description = McpToolSchemas.deviceInfo,
        schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        },
        handler = { deviceInfo(deps) },
    ),
)

private fun deviceInfo(deps: McpToolDeps): McpServerCore.CallResult {
    val battery = batteryPercent(deps.context)
    val result = JSONObject().apply {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("android_version", Build.VERSION.RELEASE)
        put("battery_percent", battery)
    }
    return McpServerCore.CallResult(
        JSONArray().put(McpToolShared.text(result.toString(2))),
    )
}

private fun batteryPercent(context: Context?): Int? {
    return runCatching {
        // Nullable-context contract: no Context (JVM tests) → null battery.
        val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()
}
