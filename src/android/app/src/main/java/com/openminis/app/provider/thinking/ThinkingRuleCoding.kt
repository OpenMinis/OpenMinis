package com.openminis.app.provider.thinking

import com.openminis.app.data.db.ProviderThinkingRuleEntity
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * [T-android-thinking-rules-phase2 / parity with iOS ThinkingRuleCoding.swift]
 * (De)serialize a user-authored [ThinkingRule] to/from its Room row.
 *
 * Only CUSTOM rules round-trip through here. The [ThinkingWireFormat] sealed hierarchy is
 * encoded as a small tagged JSON object (`{"type":"reasoning_effort","offValue":"low"}`),
 * chosen over typed columns so adding a new wire format needs no schema migration.
 *
 * Kept deliberately total and defensive: a row that fails to decode (corrupt blob, a
 * format written by a newer build) yields a rule with a null wireFormat — "no opinion" —
 * which the resolver safely falls through, rather than throwing mid-request.
 */
object ThinkingRuleCoding {

    // ---- Wire format <-> JSON ----

    fun encodeWireFormat(fmt: ThinkingWireFormat?): String? {
        if (fmt == null) return null
        val o = JSONObject()
        when (fmt) {
            is ThinkingWireFormat.OmitEverything -> o.put("type", "omit_everything")
            is ThinkingWireFormat.ReasoningEffort -> {
                o.put("type", "reasoning_effort")
                fmt.offValue?.let { o.put("offValue", it) }
            }
            is ThinkingWireFormat.ReasoningEffortNested -> {
                o.put("type", "reasoning_effort_nested")
                fmt.offValue?.let { o.put("offValue", it) }
            }
            is ThinkingWireFormat.DeepSeekSibling -> o.put("type", "deepseek_sibling")
            is ThinkingWireFormat.QwenDual -> o.put("type", "qwen_dual")
            is ThinkingWireFormat.AnthropicThinking -> {
                o.put("type", "anthropic_thinking")
                o.put("style", fmt.style.name)
            }
            is ThinkingWireFormat.GeminiBudget -> {
                o.put("type", "gemini_budget")
                o.put("floor", fmt.floor)
                o.put("canDisable", fmt.canDisable)
            }
            is ThinkingWireFormat.GeminiThinkingLevel -> o.put("type", "gemini_thinking_level")
            is ThinkingWireFormat.BooleanToggle -> {
                o.put("type", "boolean_toggle")
                o.put("path", fmt.path)
            }
            is ThinkingWireFormat.ExtraBodyToggle -> {
                o.put("type", "extra_body_toggle")
                o.put("path", fmt.path)
            }
            is ThinkingWireFormat.CustomPath -> {
                o.put("type", "custom_path")
                o.put("path", fmt.path)
                fmt.offValue?.let { o.put("offValue", it) }
                val vals = JSONObject()
                for ((lvl, v) in fmt.values) vals.put(lvl.name, v)
                o.put("values", vals)
            }
        }
        return o.toString()
    }

    fun decodeWireFormat(json: String?): ThinkingWireFormat? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            when (o.optString("type")) {
                "omit_everything" -> ThinkingWireFormat.OmitEverything
                "reasoning_effort" ->
                    ThinkingWireFormat.ReasoningEffort(o.optString("offValue", "").ifEmpty { null })
                "reasoning_effort_nested" ->
                    ThinkingWireFormat.ReasoningEffortNested(o.optString("offValue", "").ifEmpty { null })
                "deepseek_sibling" -> ThinkingWireFormat.DeepSeekSibling
                "qwen_dual" -> ThinkingWireFormat.QwenDual
                "anthropic_thinking" -> ThinkingWireFormat.AnthropicThinking(
                    runCatching { AnthropicThinkingStyle.valueOf(o.optString("style")) }
                        .getOrDefault(AnthropicThinkingStyle.ADAPTIVE),
                )
                "gemini_budget" -> ThinkingWireFormat.GeminiBudget(
                    floor = o.optInt("floor", 128),
                    canDisable = o.optBoolean("canDisable", true),
                )
                "gemini_thinking_level" -> ThinkingWireFormat.GeminiThinkingLevel
                "boolean_toggle" -> ThinkingWireFormat.BooleanToggle(o.optString("path", "thinking"))
                "extra_body_toggle" -> ThinkingWireFormat.ExtraBodyToggle(o.optString("path", "thinking.enabled"))
                "custom_path" -> {
                    val values = mutableMapOf<ThinkingLevel, String>()
                    o.optJSONObject("values")?.let { v ->
                        for (k in v.keys()) {
                            ThinkingLevel.parseOrNull(k)?.let { lvl ->
                                values[lvl] = v.optString(k)
                            }
                        }
                    }
                    ThinkingWireFormat.CustomPath(
                        path = o.optString("path", ""),
                        values = values,
                        offValue = o.optString("offValue", "").ifEmpty { null },
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- ReasoningEchoPolicy <-> JSON ----

    fun encodeEcho(echo: ReasoningEchoPolicy?): String? {
        if (echo == null) return null
        return JSONObject()
            .put("fieldName", echo.fieldName)
            .put("timing", echo.timing.name)
            .toString()
    }

    fun decodeEcho(json: String?): ReasoningEchoPolicy? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            ReasoningEchoPolicy(
                fieldName = o.optString("fieldName", "reasoning_content"),
                timing = runCatching { ReasoningEchoPolicy.Timing.valueOf(o.optString("timing")) }
                    .getOrDefault(ReasoningEchoPolicy.Timing.EVERY_TURN),
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- Rule <-> Entity ----

    fun toEntity(rule: ThinkingRule, id: String, instanceId: String, sortOrder: Int): ProviderThinkingRuleEntity {
        val (kind, pattern) = when (val s = rule.scope) {
            is ThinkingRule.Scope.AllModels -> "allModels" to null
            is ThinkingRule.Scope.ModelPattern -> "modelPattern" to s.pattern
        }
        return ProviderThinkingRuleEntity(
            id = id,
            providerInstanceId = instanceId,
            label = rule.label,
            scopeKind = kind,
            scopePattern = pattern,
            wireFormatJson = encodeWireFormat(rule.wireFormat),
            reasoningEchoJson = encodeEcho(rule.reasoningEcho),
            sortOrder = sortOrder,
        )
    }

    fun toRule(e: ProviderThinkingRuleEntity): ThinkingRule {
        val scope = when (e.scopeKind) {
            "modelPattern" -> ThinkingRule.Scope.ModelPattern(e.scopePattern ?: "*")
            else -> ThinkingRule.Scope.AllModels
        }
        return ThinkingRule(
            kind = ThinkingRule.Kind.CUSTOM,
            scope = scope,
            wireFormat = decodeWireFormat(e.wireFormatJson),
            reasoningEcho = decodeEcho(e.reasoningEchoJson),
            label = e.label,
        )
    }
}
