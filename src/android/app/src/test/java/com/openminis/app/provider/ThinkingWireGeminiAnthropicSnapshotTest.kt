package com.openminis.app.provider

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GOLDEN SNAPSHOT for the Gemini and Anthropic thinking shapes — the Phase 2 §1
 * counterpart to [ThinkingWireGoldenSnapshotTest] (which covers the OpenAI family).
 *
 * WHY: Phase 1 deliberately left these two providers out of [ThinkingRuleResolver]
 * (their logic lived in `GeminiProvider.buildThinkingConfig` and `AnthropicProvider`'s
 * budget/effort helpers). Phase 2 §1 migrates them in. The acceptance criterion is the
 * same as Phase 1's: byte-for-byte identical behaviour. This file is generated against
 * the PRE-migration implementations and committed before the migration, so it is an
 * oracle rather than a rubber stamp.
 *
 * NOT ROW-COMPARABLE WITH THE iOS SNAPSHOT. Android's Gemini rules genuinely differ from
 * iOS's in two places, and those differences are PRE-EXISTING, not introduced here:
 *   • 3.x at MAX/ULTRA — Android's `when` falls to `else -> "low"`, iOS maps to "high".
 *   • Unknown/latest ids (e.g. `gemini-flash-latest`) — Android returns null (no
 *     thinkingConfig at all), iOS has a conservative 128-floor fallback table.
 * Phase 2 §1 is a pure refactor, so each platform keeps its own behaviour and each
 * snapshot is an oracle for its own side. Unifying them is a deliberate behaviour change
 * and belongs in its own commit.
 */
class ThinkingWireGeminiAnthropicSnapshotTest {

    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${canonical(v.get(it))}" }
        is String -> "\"$v\""
        else -> v.toString()
    }

    private val geminiModels = listOf(
        "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
        "gemini-3-pro-preview", "gemini-3-flash-preview", "gemini-flash-latest",
        "gemini-2.5-pro-exp-0827", "gemini-2.0-tts", "gemini-2.5-embedding",
        // [OpenMinis#226] Specialized ids that ALSO match a family pattern. Android had
        // no specialized-modality test at all, so these were answered by the family
        // branch and shipped a thinking parameter Gemini rejects with 400. The plain
        // `gemini-2.0-tts` above never caught it: matching no family, it already fell
        // through to null.
        "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-preview-tts", "gemini-3-pro-image-preview",
        // [T-gemini37-minimal-400] 3.6 vs 3.7 pins the OFF-floor split: everything
        // through 3.6 accepts thinkingLevel "minimal", 3.7 rejects it with a 400 and
        // must fall back to "low". The golden previously had no 3.5/3.6/3.7 entry at
        // all, which is why the unusable-at-Off regression reached users.
        "gemini-3.6-flash", "gemini-3.7-flash",
        "unknown-gemini-model",
    )

    private val anthropicModels = listOf(
        "claude-opus-4-8", "claude-opus-4.8", "claude-sonnet-4-6", "claude-sonnet-4-5",
        "claude-haiku-4-5", "claude-opus-4-1", "claude-3-7-sonnet", "not-a-claude",
    )

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
    )

    private fun render(): String = buildString {
        for (m in geminiModels) {
            for (lv in levels) {
                append("gemini/$m/$lv -> ${canonical(ThinkingRuleResolver.geminiThinkingConfig(m, lv))}")
                append('\n')
            }
        }
        for (m in anthropicModels) {
            for (lv in levels) {
                for (mt in listOf(8192, 65536)) {
                    val shape = ThinkingRuleResolver.anthropicThinkingShape(m, true, lv, mt)
                    append("anthropic/$m/$lv/mt$mt -> ${canonical(shape)}")
                    append('\n')
                }
            }
        }
    }.trimEnd('\n')

    @Test
    fun `gemini and anthropic golden snapshot`() {
        assertEquals(
            """
            GEMINI/ANTHROPIC THINKING SHAPE CHANGED.

            Byte-for-byte oracle for the Phase 2 §1 migration. A failure means the resolver
            emits something different from the pre-migration provider logic for at least one
            (model, level) pair. Diff the blocks and restore parity or justify explicitly.
            """.trimIndent(),
            EXPECTED,
            render(),
        )
    }

    companion object {
        /** Generated from the PRE-migration implementations. See the class doc. */
        private val EXPECTED = """
gemini/gemini-2.5-pro/OFF -> {thinkingBudget:128}
gemini/gemini-2.5-pro/LOW -> {includeThoughts:true,thinkingBudget:2048}
gemini/gemini-2.5-pro/MEDIUM -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-2.5-pro/HIGH -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-pro/XHIGH -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro/MAX -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro/ULTRA -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-flash/OFF -> {thinkingBudget:0}
gemini/gemini-2.5-flash/LOW -> {includeThoughts:true,thinkingBudget:1024}
gemini/gemini-2.5-flash/MEDIUM -> {includeThoughts:true,thinkingBudget:4096}
gemini/gemini-2.5-flash/HIGH -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-2.5-flash/XHIGH -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash/MAX -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash/ULTRA -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-flash-lite/OFF -> null
gemini/gemini-2.5-flash-lite/LOW -> null
gemini/gemini-2.5-flash-lite/MEDIUM -> null
gemini/gemini-2.5-flash-lite/HIGH -> null
gemini/gemini-2.5-flash-lite/XHIGH -> null
gemini/gemini-2.5-flash-lite/MAX -> null
gemini/gemini-2.5-flash-lite/ULTRA -> null
gemini/gemini-3-pro-preview/OFF -> {thinkingLevel:"low"}
gemini/gemini-3-pro-preview/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini/gemini-3-pro-preview/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini/gemini-3-pro-preview/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-pro-preview/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-pro-preview/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-pro-preview/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/OFF -> {thinkingLevel:"minimal"}
gemini/gemini-3-flash-preview/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini/gemini-3-flash-preview/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini/gemini-3-flash-preview/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3-flash-preview/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-flash-latest/OFF -> null
gemini/gemini-flash-latest/LOW -> null
gemini/gemini-flash-latest/MEDIUM -> null
gemini/gemini-flash-latest/HIGH -> null
gemini/gemini-flash-latest/XHIGH -> null
gemini/gemini-flash-latest/MAX -> null
gemini/gemini-flash-latest/ULTRA -> null
gemini/gemini-2.5-pro-exp-0827/OFF -> {thinkingBudget:128}
gemini/gemini-2.5-pro-exp-0827/LOW -> {includeThoughts:true,thinkingBudget:2048}
gemini/gemini-2.5-pro-exp-0827/MEDIUM -> {includeThoughts:true,thinkingBudget:8192}
gemini/gemini-2.5-pro-exp-0827/HIGH -> {includeThoughts:true,thinkingBudget:16384}
gemini/gemini-2.5-pro-exp-0827/XHIGH -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro-exp-0827/MAX -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.5-pro-exp-0827/ULTRA -> {includeThoughts:true,thinkingBudget:32768}
gemini/gemini-2.0-tts/OFF -> null
gemini/gemini-2.0-tts/LOW -> null
gemini/gemini-2.0-tts/MEDIUM -> null
gemini/gemini-2.0-tts/HIGH -> null
gemini/gemini-2.0-tts/XHIGH -> null
gemini/gemini-2.0-tts/MAX -> null
gemini/gemini-2.0-tts/ULTRA -> null
gemini/gemini-2.5-embedding/OFF -> null
gemini/gemini-2.5-embedding/LOW -> null
gemini/gemini-2.5-embedding/MEDIUM -> null
gemini/gemini-2.5-embedding/HIGH -> null
gemini/gemini-2.5-embedding/XHIGH -> null
gemini/gemini-2.5-embedding/MAX -> null
gemini/gemini-2.5-embedding/ULTRA -> null
gemini/gemini-3.1-flash-tts-preview/OFF -> null
gemini/gemini-3.1-flash-tts-preview/LOW -> null
gemini/gemini-3.1-flash-tts-preview/MEDIUM -> null
gemini/gemini-3.1-flash-tts-preview/HIGH -> null
gemini/gemini-3.1-flash-tts-preview/XHIGH -> null
gemini/gemini-3.1-flash-tts-preview/MAX -> null
gemini/gemini-3.1-flash-tts-preview/ULTRA -> null
gemini/gemini-2.5-flash-preview-tts/OFF -> null
gemini/gemini-2.5-flash-preview-tts/LOW -> null
gemini/gemini-2.5-flash-preview-tts/MEDIUM -> null
gemini/gemini-2.5-flash-preview-tts/HIGH -> null
gemini/gemini-2.5-flash-preview-tts/XHIGH -> null
gemini/gemini-2.5-flash-preview-tts/MAX -> null
gemini/gemini-2.5-flash-preview-tts/ULTRA -> null
gemini/gemini-2.5-pro-preview-tts/OFF -> null
gemini/gemini-2.5-pro-preview-tts/LOW -> null
gemini/gemini-2.5-pro-preview-tts/MEDIUM -> null
gemini/gemini-2.5-pro-preview-tts/HIGH -> null
gemini/gemini-2.5-pro-preview-tts/XHIGH -> null
gemini/gemini-2.5-pro-preview-tts/MAX -> null
gemini/gemini-2.5-pro-preview-tts/ULTRA -> null
gemini/gemini-3-pro-image-preview/OFF -> null
gemini/gemini-3-pro-image-preview/LOW -> null
gemini/gemini-3-pro-image-preview/MEDIUM -> null
gemini/gemini-3-pro-image-preview/HIGH -> null
gemini/gemini-3-pro-image-preview/XHIGH -> null
gemini/gemini-3-pro-image-preview/MAX -> null
gemini/gemini-3-pro-image-preview/ULTRA -> null
gemini/gemini-3.6-flash/OFF -> {thinkingLevel:"minimal"}
gemini/gemini-3.6-flash/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini/gemini-3.6-flash/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini/gemini-3.6-flash/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3.6-flash/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3.6-flash/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3.6-flash/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3.7-flash/OFF -> {thinkingLevel:"low"}
gemini/gemini-3.7-flash/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini/gemini-3.7-flash/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini/gemini-3.7-flash/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3.7-flash/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3.7-flash/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini/gemini-3.7-flash/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini/unknown-gemini-model/OFF -> null
gemini/unknown-gemini-model/LOW -> null
gemini/unknown-gemini-model/MEDIUM -> null
gemini/unknown-gemini-model/HIGH -> null
gemini/unknown-gemini-model/XHIGH -> null
gemini/unknown-gemini-model/MAX -> null
gemini/unknown-gemini-model/ULTRA -> null
anthropic/claude-opus-4-8/OFF/mt8192 -> {disabled=true}
anthropic/claude-opus-4-8/OFF/mt65536 -> {disabled=true}
anthropic/claude-opus-4-8/LOW/mt8192 -> {effort=low}
anthropic/claude-opus-4-8/LOW/mt65536 -> {effort=low}
anthropic/claude-opus-4-8/MEDIUM/mt8192 -> {effort=medium}
anthropic/claude-opus-4-8/MEDIUM/mt65536 -> {effort=medium}
anthropic/claude-opus-4-8/HIGH/mt8192 -> {effort=high}
anthropic/claude-opus-4-8/HIGH/mt65536 -> {effort=high}
anthropic/claude-opus-4-8/XHIGH/mt8192 -> {effort=max}
anthropic/claude-opus-4-8/XHIGH/mt65536 -> {effort=max}
anthropic/claude-opus-4-8/MAX/mt8192 -> {effort=max}
anthropic/claude-opus-4-8/MAX/mt65536 -> {effort=max}
anthropic/claude-opus-4-8/ULTRA/mt8192 -> {effort=max}
anthropic/claude-opus-4-8/ULTRA/mt65536 -> {effort=max}
anthropic/claude-opus-4.8/OFF/mt8192 -> {disabled=true}
anthropic/claude-opus-4.8/OFF/mt65536 -> {disabled=true}
anthropic/claude-opus-4.8/LOW/mt8192 -> {effort=low}
anthropic/claude-opus-4.8/LOW/mt65536 -> {effort=low}
anthropic/claude-opus-4.8/MEDIUM/mt8192 -> {effort=medium}
anthropic/claude-opus-4.8/MEDIUM/mt65536 -> {effort=medium}
anthropic/claude-opus-4.8/HIGH/mt8192 -> {effort=high}
anthropic/claude-opus-4.8/HIGH/mt65536 -> {effort=high}
anthropic/claude-opus-4.8/XHIGH/mt8192 -> {effort=max}
anthropic/claude-opus-4.8/XHIGH/mt65536 -> {effort=max}
anthropic/claude-opus-4.8/MAX/mt8192 -> {effort=max}
anthropic/claude-opus-4.8/MAX/mt65536 -> {effort=max}
anthropic/claude-opus-4.8/ULTRA/mt8192 -> {effort=max}
anthropic/claude-opus-4.8/ULTRA/mt65536 -> {effort=max}
anthropic/claude-sonnet-4-6/OFF/mt8192 -> {disabled=true}
anthropic/claude-sonnet-4-6/OFF/mt65536 -> {disabled=true}
anthropic/claude-sonnet-4-6/LOW/mt8192 -> {effort=low}
anthropic/claude-sonnet-4-6/LOW/mt65536 -> {effort=low}
anthropic/claude-sonnet-4-6/MEDIUM/mt8192 -> {effort=medium}
anthropic/claude-sonnet-4-6/MEDIUM/mt65536 -> {effort=medium}
anthropic/claude-sonnet-4-6/HIGH/mt8192 -> {effort=high}
anthropic/claude-sonnet-4-6/HIGH/mt65536 -> {effort=high}
anthropic/claude-sonnet-4-6/XHIGH/mt8192 -> {effort=max}
anthropic/claude-sonnet-4-6/XHIGH/mt65536 -> {effort=max}
anthropic/claude-sonnet-4-6/MAX/mt8192 -> {effort=max}
anthropic/claude-sonnet-4-6/MAX/mt65536 -> {effort=max}
anthropic/claude-sonnet-4-6/ULTRA/mt8192 -> {effort=max}
anthropic/claude-sonnet-4-6/ULTRA/mt65536 -> {effort=max}
anthropic/claude-sonnet-4-5/OFF/mt8192 -> {}
anthropic/claude-sonnet-4-5/OFF/mt65536 -> {}
anthropic/claude-sonnet-4-5/LOW/mt8192 -> {budget_tokens=8191}
anthropic/claude-sonnet-4-5/LOW/mt65536 -> {budget_tokens=8192}
anthropic/claude-sonnet-4-5/MEDIUM/mt8192 -> {budget_tokens=8191}
anthropic/claude-sonnet-4-5/MEDIUM/mt65536 -> {budget_tokens=32768}
anthropic/claude-sonnet-4-5/HIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-sonnet-4-5/HIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-sonnet-4-5/XHIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-sonnet-4-5/XHIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-sonnet-4-5/MAX/mt8192 -> {budget_tokens=8191}
anthropic/claude-sonnet-4-5/MAX/mt65536 -> {budget_tokens=65535}
anthropic/claude-sonnet-4-5/ULTRA/mt8192 -> {budget_tokens=8191}
anthropic/claude-sonnet-4-5/ULTRA/mt65536 -> {budget_tokens=65535}
anthropic/claude-haiku-4-5/OFF/mt8192 -> {}
anthropic/claude-haiku-4-5/OFF/mt65536 -> {}
anthropic/claude-haiku-4-5/LOW/mt8192 -> {budget_tokens=8191}
anthropic/claude-haiku-4-5/LOW/mt65536 -> {budget_tokens=8192}
anthropic/claude-haiku-4-5/MEDIUM/mt8192 -> {budget_tokens=8191}
anthropic/claude-haiku-4-5/MEDIUM/mt65536 -> {budget_tokens=32768}
anthropic/claude-haiku-4-5/HIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-haiku-4-5/HIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-haiku-4-5/XHIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-haiku-4-5/XHIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-haiku-4-5/MAX/mt8192 -> {budget_tokens=8191}
anthropic/claude-haiku-4-5/MAX/mt65536 -> {budget_tokens=65535}
anthropic/claude-haiku-4-5/ULTRA/mt8192 -> {budget_tokens=8191}
anthropic/claude-haiku-4-5/ULTRA/mt65536 -> {budget_tokens=65535}
anthropic/claude-opus-4-1/OFF/mt8192 -> {}
anthropic/claude-opus-4-1/OFF/mt65536 -> {}
anthropic/claude-opus-4-1/LOW/mt8192 -> {budget_tokens=8191}
anthropic/claude-opus-4-1/LOW/mt65536 -> {budget_tokens=8192}
anthropic/claude-opus-4-1/MEDIUM/mt8192 -> {budget_tokens=8191}
anthropic/claude-opus-4-1/MEDIUM/mt65536 -> {budget_tokens=32768}
anthropic/claude-opus-4-1/HIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-opus-4-1/HIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-opus-4-1/XHIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-opus-4-1/XHIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-opus-4-1/MAX/mt8192 -> {budget_tokens=8191}
anthropic/claude-opus-4-1/MAX/mt65536 -> {budget_tokens=65535}
anthropic/claude-opus-4-1/ULTRA/mt8192 -> {budget_tokens=8191}
anthropic/claude-opus-4-1/ULTRA/mt65536 -> {budget_tokens=65535}
anthropic/claude-3-7-sonnet/OFF/mt8192 -> {}
anthropic/claude-3-7-sonnet/OFF/mt65536 -> {}
anthropic/claude-3-7-sonnet/LOW/mt8192 -> {budget_tokens=8191}
anthropic/claude-3-7-sonnet/LOW/mt65536 -> {budget_tokens=8192}
anthropic/claude-3-7-sonnet/MEDIUM/mt8192 -> {budget_tokens=8191}
anthropic/claude-3-7-sonnet/MEDIUM/mt65536 -> {budget_tokens=32768}
anthropic/claude-3-7-sonnet/HIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-3-7-sonnet/HIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-3-7-sonnet/XHIGH/mt8192 -> {budget_tokens=8191}
anthropic/claude-3-7-sonnet/XHIGH/mt65536 -> {budget_tokens=65535}
anthropic/claude-3-7-sonnet/MAX/mt8192 -> {budget_tokens=8191}
anthropic/claude-3-7-sonnet/MAX/mt65536 -> {budget_tokens=65535}
anthropic/claude-3-7-sonnet/ULTRA/mt8192 -> {budget_tokens=8191}
anthropic/claude-3-7-sonnet/ULTRA/mt65536 -> {budget_tokens=65535}
anthropic/not-a-claude/OFF/mt8192 -> {}
anthropic/not-a-claude/OFF/mt65536 -> {}
anthropic/not-a-claude/LOW/mt8192 -> {budget_tokens=8191}
anthropic/not-a-claude/LOW/mt65536 -> {budget_tokens=8192}
anthropic/not-a-claude/MEDIUM/mt8192 -> {budget_tokens=8191}
anthropic/not-a-claude/MEDIUM/mt65536 -> {budget_tokens=32768}
anthropic/not-a-claude/HIGH/mt8192 -> {budget_tokens=8191}
anthropic/not-a-claude/HIGH/mt65536 -> {budget_tokens=65535}
anthropic/not-a-claude/XHIGH/mt8192 -> {budget_tokens=8191}
anthropic/not-a-claude/XHIGH/mt65536 -> {budget_tokens=65535}
anthropic/not-a-claude/MAX/mt8192 -> {budget_tokens=8191}
anthropic/not-a-claude/MAX/mt65536 -> {budget_tokens=65535}
anthropic/not-a-claude/ULTRA/mt8192 -> {budget_tokens=8191}
anthropic/not-a-claude/ULTRA/mt65536 -> {budget_tokens=65535}
        """.trimIndent()
    }
}
