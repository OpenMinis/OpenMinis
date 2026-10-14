package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * Look up a named sub-agent type, inject prompt + tool/skill whitelist, then
 * spawn via the existing SubAgentRunner (independent loop, not nested AgentCore).
 *
 * Adapted from XINCODE-Public SubAgentTool (GPL-3.0-or-later).
 */
object DispatchAgentsTool {
    const val NAME = "dispatch_agents"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Dispatch one or more named sub-agent types (探索者/审查员/编码员/研究员 or user-defined). " +
            "Each type has its own kind, skills, and tool whitelist. Nested dispatch is forbidden. " +
            "Prefer this when you know which specialist to send; use spawn_agent for ad-hoc kinds.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "assignments" to AgentToolParam(
                type = "array",
                description = "List of {agent, task} or {name, prompt}.",
                items = AgentToolParam(
                    type = "object",
                    description = "One assignment",
                    properties = mapOf(
                        "agent" to AgentToolParam("string", "Type name, e.g. 探索者"),
                        "name" to AgentToolParam("string", "Alias of agent"),
                        "task" to AgentToolParam("string", "What this agent should do"),
                        "prompt" to AgentToolParam("string", "Alias of task"),
                    ),
                ),
            ),
        ),
        required = listOf("tool_title", "assignments"),
        propertyOrdering = listOf("tool_title", "assignments"),
    )

    /** Rewrite into spawn_agent tasks JSON so ChatViewModel.executeRunSubAgent can run it. */
    fun toSpawnArgs(argsJson: String, context: Context): String {
        val src = JSONObject(argsJson)
        val assignments = src.optJSONArray("assignments") ?: JSONArray()
        val tasks = JSONArray()
        for (i in 0 until assignments.length()) {
            val a = assignments.optJSONObject(i) ?: continue
            val agentName = a.optString("agent").ifBlank { a.optString("name") }
            val task = a.optString("task").ifBlank { a.optString("prompt") }
            // A lane with no brief burns a full sub-agent turn budget and then
            // reports nothing. Drop it instead of spawning an empty agent.
            if (task.isBlank()) continue
            val type = SubAgentTypeStore.find(context, agentName)
            val kind = type?.kind ?: SubAgentKind.WORKER
            val prompt = buildString {
                if (type != null) {
                    append(type.systemPrompt.trim())
                    append("\n\n")
                }
                append(task)
            }
            val obj = JSONObject()
                .put("prompt", prompt)
                .put("kind", kind)
                .put("role", agentName)
            if (type != null && type.skillNames.isNotEmpty()) {
                // [T-dispatch-skills-contract] `skills` is DECLARED as a string
                // ("Comma-separated skill ids", AgentTools spawn_agent schema) and
                // the consumer reads it with `args.optString("skills")`. Emitting a
                // JSONArray made the value arrive as the literal text
                // `["a","b"]` inside the worker's system prompt — brackets and
                // quotes and all. Join it into the contracted form.
                obj.put("skills", type.skillNames.joinToString(","))
            }
            tasks.put(obj)
        }
        return JSONObject()
            .put("tool_title", src.optString("tool_title", NAME))
            .put("tasks", tasks)
            .toString()
    }

    fun filterToolsForType(type: SubAgentType?, tools: List<AgentToolDefinition>): List<AgentToolDefinition> {
        if (type == null || type.toolNames.isEmpty()) return SubAgentKind.dedupeByName(tools)
        val allow = type.toolNames.toSet()
        return SubAgentKind.dedupeByName(tools.filter { it.name in allow })
    }
}
