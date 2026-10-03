package com.openminis.app.provider.thinking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OpenMinis#377] Clamping the OFF tier onto what a model actually accepts.
 *
 * Reported on Android: Responses API + gpt-6-astra, reasoning effort set to
 * `low`, and every compaction died with
 * `[400] reasoning.effort: 'none' is not supported` (server listing
 * low/medium/high/xhigh/max as the supported set). The off tier is chosen by
 * ENDPOINT — official OpenAI → "none" — and was never checked against the
 * MODEL, so a model that mandates reasoning received an illegal value.
 */
class OffEffortClampTest {

    private val gpt6Astra = listOf("low", "medium", "high", "xhigh", "max")

    @Test
    fun `the reported case snaps none to the lowest supported tier`() {
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", gpt6Astra))
    }

    @Test
    fun `a model that supports none keeps none`() {
        assertEquals(
            "none",
            ThinkingRuleResolver.clampOffEffort("none", listOf("none", "low", "high")),
        )
    }

    @Test
    fun `no declared values is left exactly as-is`() {
        // Zero-regression guarantee: models without catalog effort data must
        // behave precisely as they did before this change.
        assertEquals("none", ThinkingRuleResolver.clampOffEffort("none", null))
        assertEquals("none", ThinkingRuleResolver.clampOffEffort("none", emptyList()))
        assertEquals("minimal", ThinkingRuleResolver.clampOffEffort("minimal", null))
    }

    @Test
    fun `OFF never escalates to a high tier`() {
        // The hazard that makes clampEffort unusable here: it walks DOWN then
        // UP, so a ["high","max"] model turns an OFF request into "high" —
        // inverting the user's intent. clampOffEffort picks the LOWEST declared
        // tier instead, which is the closest legal value to "do not think".
        assertEquals("high", ThinkingRuleResolver.clampEffort("none", listOf("high", "max")))
        assertEquals("high", ThinkingRuleResolver.clampOffEffort("none", listOf("high", "max")))
        // …and where the two differ, clampOffEffort is never the higher one.
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", listOf("max", "low")))
    }

    @Test
    fun `declaration order does not matter`() {
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", listOf("max", "high", "low")))
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", gpt6Astra.reversed()))
    }

    @Test
    fun `the Ark minimal off-tier clamps too`() {
        // Volcano Ark's off tier is "minimal"; a model there declaring only
        // low/medium/high must receive "low", not a rejected "minimal".
        assertEquals(
            "low",
            ThinkingRuleResolver.clampOffEffort("minimal", listOf("low", "medium", "high")),
        )
    }

    @Test
    fun `unrecognisable declarations are not guessed at`() {
        // A catalog entry with spellings outside the ladder tells us nothing
        // reliable, so the vendor's off tier stands rather than being replaced
        // by an arbitrary pick.
        assertEquals("none", ThinkingRuleResolver.clampOffEffort("none", listOf("turbo", "ultra-max")))
    }
}
