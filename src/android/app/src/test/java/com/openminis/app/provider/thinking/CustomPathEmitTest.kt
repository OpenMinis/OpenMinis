package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#306] The OpenAI emitter must be able to emit every wire format the rule editor
 * lets a user pick.
 *
 * Before this, `CustomPath`, `BooleanToggle` and `ExtraBodyToggle` were selectable,
 * saved fine, and then threw from inside `buildRequestBody`:
 *
 *     IllegalStateException: ThinkingWireFormat CustomPath(path=extra_body.thinking.mode,
 *     ...) is not emitted on the OpenAI path in Phase 1
 *
 * Reproduced on a Pixel 6: zero requests reached the network, the chat showed a red
 * error bubble, and Retry failed identically — the model was unusable until the rule
 * was deleted.
 */
class CustomPathEmitTest {

    @After
    fun tearDown() = ThinkingRuleResolver.setAllCustomRules(emptyMap())

    private fun ctx(level: ThinkingLevel) = ThinkingResolveContext(
        modelId = "some-model",
        instanceId = "inst-A",
        supportsReasoning = true,
        declaredEffortValues = null,
        level = level,
        maxTokens = 4096,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        offEffort = null,
    )

    private fun ruleWith(fmt: ThinkingWireFormat) = mapOf(
        "inst-A" to listOf(
            ThinkingRule(
                kind = ThinkingRule.Kind.CUSTOM,
                scope = ThinkingRule.Scope.AllModels,
                wireFormat = fmt,
                label = "t",
            ),
        ),
    )

    private fun emit(fmt: ThinkingWireFormat, level: ThinkingLevel = ThinkingLevel.HIGH): JSONObject {
        ThinkingRuleResolver.setAllCustomRules(ruleWith(fmt))
        val body = JSONObject()
        ThinkingRuleResolver.apply(body, ctx(level))
        return body
    }

    // ── the crash itself ────────────────────────────────────────────────────

    @Test
    fun `a CustomPath rule no longer throws and writes its nested value`() {
        val body = emit(
            ThinkingWireFormat.CustomPath(
                path = "extra_body.thinking.mode",
                values = mapOf(ThinkingLevel.HIGH to "enabled"),
                offValue = "disabled",
            ),
        )
        assertEquals(
            "enabled",
            body.getJSONObject("extra_body").getJSONObject("thinking").getString("mode"),
        )
    }

    @Test
    fun `a single-segment path writes at the root`() {
        val body = emit(
            ThinkingWireFormat.CustomPath("reasoning_mode", mapOf(ThinkingLevel.HIGH to "on"), null),
        )
        assertEquals("on", body.getString("reasoning_mode"))
    }

    @Test
    fun `BooleanToggle and ExtraBodyToggle are emitted too`() {
        // Same `else ->` branch, same crash. The issue named CustomPath, but a
        // gemini_budget rule reproduced it identically on device, so the fix has to
        // cover every format the editor offers, not just the reported one.
        assertTrue(emit(ThinkingWireFormat.BooleanToggle("enable_thinking")).getBoolean("enable_thinking"))
        assertTrue(
            emit(ThinkingWireFormat.ExtraBodyToggle("extra_body.thinking.enabled"))
                .getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"),
        )
    }

    // ── tier selection ──────────────────────────────────────────────────────

    @Test
    fun `a tier with no value of its own falls back to HIGH`() {
        // Mirrors iOS `values[ctx.level] ?? values[.high]`: a rule authored with one
        // value is a rule that applies at every enabled tier, not one that silently
        // does nothing on MEDIUM.
        val body = emit(
            ThinkingWireFormat.CustomPath("a.b", mapOf(ThinkingLevel.HIGH to "hi"), null),
            level = ThinkingLevel.MEDIUM,
        )
        assertEquals("hi", body.getJSONObject("a").getString("b"))
    }

    @Test
    fun `OFF writes the off value, and writes nothing at all when none is set`() {
        val withOff = emit(
            ThinkingWireFormat.CustomPath("a.b", mapOf(ThinkingLevel.HIGH to "hi"), "bye"),
            level = ThinkingLevel.OFF,
        )
        assertEquals("bye", withOff.getJSONObject("a").getString("b"))

        // No offValue means "say nothing" — distinct from "send a falsey value", which
        // is load-bearing for vendors whose switch defaults ON when the key is absent.
        val withoutOff = emit(
            ThinkingWireFormat.CustomPath("a.b", mapOf(ThinkingLevel.HIGH to "hi"), null),
            level = ThinkingLevel.OFF,
        )
        assertFalse(withoutOff.has("a"))
    }

    // ── the safety guard (stricter than iOS, deliberately) ──────────────────

    @Test
    fun `a reserved root key is refused rather than corrupting the request`() {
        for (reserved in listOf("messages", "model", "stream", "tools", "response_format")) {
            val body = emit(
                ThinkingWireFormat.CustomPath(reserved, mapOf(ThinkingLevel.HIGH to "x"), null),
            )
            assertFalse("must not write reserved root '$reserved'", body.has(reserved))
        }
    }

    @Test
    fun `reserved words are only reserved at the ROOT`() {
        // `extra_body.model` is a vendor's own nested field and none of our business;
        // only a root `model` would retarget the request.
        val body = emit(
            ThinkingWireFormat.CustomPath("extra_body.model", mapOf(ThinkingLevel.HIGH to "x"), null),
        )
        assertEquals("x", body.getJSONObject("extra_body").getString("model"))
    }

    @Test
    fun `a refused or empty path reports no emitted value`() {
        ThinkingRuleResolver.setAllCustomRules(
            ruleWith(ThinkingWireFormat.CustomPath("messages", mapOf(ThinkingLevel.HIGH to "x"), null)),
        )
        val body = JSONObject()
        val trace = ThinkingRuleResolver.apply(body, ctx(ThinkingLevel.HIGH))
        // The rule must not claim a key it never wrote, or the trace would tell the
        // user it worked.
        assertTrue("trace must report no emitted keys", trace.emittedKeys.isEmpty())

        assertFalse(
            emit(ThinkingWireFormat.CustomPath("   ", mapOf(ThinkingLevel.HIGH to "x"), null)).keys().hasNext(),
        )
    }

    @Test
    fun `a scalar sitting where the path needs to nest is replaced`() {
        // The alternative is failing the whole send because some other rule put a
        // scalar at `extra_body`; the user's explicit rule should win.
        ThinkingRuleResolver.setAllCustomRules(
            ruleWith(ThinkingWireFormat.CustomPath("extra_body.x", mapOf(ThinkingLevel.HIGH to "v"), null)),
        )
        val body = JSONObject().put("extra_body", "i am a string")
        ThinkingRuleResolver.apply(body, ctx(ThinkingLevel.HIGH))
        assertEquals("v", body.getJSONObject("extra_body").getString("x"))
    }
}
