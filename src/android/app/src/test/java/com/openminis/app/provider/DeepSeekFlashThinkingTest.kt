package com.openminis.app.provider

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-deepseek-flash-scope] — GH#356, port of iOS 466b00f9b.
 *
 * DeepSeek now recommends the bare `deepseek-flash` (`deepseek-v4-flash` is
 * kept only as a legacy alias; both are served by the same
 * DeepSeek-V4.1-Flash). The vendor-native thinking rule was scoped
 * `*deepseek-v4*`, which the new id does not satisfy, so it fell through to
 * the generic `reasoning_effort` default — the wrong wire shape for this
 * vendor — and the catalog's XHIGH default offered a tier DeepSeek rejects.
 *
 * The scope claim is exercised against the SHIPPING glob
 * (`ThinkingRule.Scope.matches`), not a reimplementation, so a change to the
 * matcher's semantics is caught here rather than silently re-breaking the id.
 */
class DeepSeekFlashThinkingTest {

    private fun claims(pattern: String, modelId: String): Boolean =
        ThinkingRule.Scope.ModelPattern(pattern).matches(modelId)

    // ---- The bug: the legacy pattern misses the id DeepSeek recommends -----

    @Test
    fun `the legacy pattern does not match the bare id`() {
        // Pre-fix behaviour, pinned so the reason for the sibling rule stays
        // legible: this is why `deepseek-flash` fell through to the generic
        // default.
        assertFalse(claims("*deepseek-v4*", "deepseek-flash"))
        assertFalse(claims("*deepseek-v4*", "deepseek-flash-lite"))
    }

    @Test
    fun `the new rule claims the bare id and its lite sibling`() {
        assertTrue(claims("deepseek-flash*", "deepseek-flash"))
        assertTrue(claims("deepseek-flash*", "deepseek-flash-lite"))
    }

    @Test
    fun `the legacy alias stays on the original rule`() {
        // Both rules are OFFICIAL_VENDOR with the same wire format, so an id
        // matching both would be harmless — but they must not overlap by
        // accident, or a later edit to one would silently change the other's
        // coverage.
        assertTrue(claims("*deepseek-v4*", "deepseek-v4-flash"))
        assertTrue(claims("*deepseek-v4*", "deepseek-v4-pro"))
        assertFalse(claims("deepseek-flash*", "deepseek-v4-flash"))
        assertFalse(claims("deepseek-flash*", "deepseek-v4-pro"))
    }

    @Test
    fun `chat and reasoner are NOT claimed`() {
        // The anchored pattern is the point. A naive `*deepseek*` would sweep
        // these in, and their wire format may differ.
        for (id in listOf("deepseek-chat", "deepseek-reasoner")) {
            assertFalse("`deepseek-flash*` must not claim $id", claims("deepseek-flash*", id))
            assertFalse("`*deepseek-v4*` must not claim $id", claims("*deepseek-v4*", id))
        }
    }

    @Test
    fun `dotted spellings normalise to the same claim`() {
        // `Scope.matches` maps "." to "-" before globbing, because third-party
        // proxies publish dotted ids. A relay serving "deepseek.flash" must
        // land on the same rule.
        assertTrue(claims("deepseek-flash*", "deepseek.flash"))
        assertTrue(claims("*deepseek-v4*", "deepseek.v4.flash"))
    }

    // ---- The ceiling fallback ---------------------------------------------

    @Test
    fun `the catalog caps deepseek at max, not the xhigh default`() {
        // Consulted only when the model declares no tiers of its own. Without
        // it the conservative XHIGH default offers a level DeepSeek refuses.
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-flash"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-flash-lite"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-v4-flash"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-v4-pro"))
    }

    @Test
    fun `the catalog rule does not lift other vendors`() {
        // The neighbouring HIGH ceilings must be untouched by this addition.
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("mimo-v2.5"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("seed-2.0"))
    }

    // ---- Source facts ------------------------------------------------------

    private val resolverSrc by lazy {
        java.io.File("src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt")
            .readText()
    }

    @Test
    fun `the sibling rule ships the same wire format and echo policy`() {
        // Same family, same backend — a divergence would send one of the two
        // ids the wrong shape. Check each rule's OWN block: from its scope
        // line to its label.
        // `kind` is declared BEFORE `scope` in each ThinkingRule(...), so the
        // window runs from the rule's opening `kind =` back-scan to its label.
        fun block(scope: String, label: String): String {
            val scopeAt = resolverSrc.indexOf(scope)
            assertTrue("scope not found: $scope", scopeAt > 0)
            val from = resolverSrc.lastIndexOf("kind = ", scopeAt)
            assertTrue("no kind= above $scope", from > 0)
            val to = resolverSrc.indexOf(label, scopeAt)
            assertTrue("label not found after scope: $label", to > scopeAt)
            return resolverSrc.substring(from, to)
        }
        val legacy = block("ModelPattern(\"*deepseek-v4*\")", "deepseek-v4-official")
        val flash = block("ModelPattern(\"deepseek-flash*\")", "deepseek-flash-official")
        for ((name, b) in listOf("legacy" to legacy, "flash" to flash)) {
            assertTrue(
                "$name must use the sibling wire format",
                b.contains("ThinkingWireFormat.DeepSeekSibling"),
            )
            assertTrue(
                "$name must echo reasoning_content after tool use only",
                b.contains("ReasoningEchoPolicy(\"reasoning_content\"") &&
                    b.contains("AFTER_TOOL_USE_ONLY"),
            )
            assertTrue("$name must be an official-vendor rule", b.contains("Kind.OFFICIAL_VENDOR"))
        }
    }

    @Test
    fun `the rule is registered as a sibling, not by widening the old scope`() {
        // House style: Scope is one pattern with no OR, so coverage grows by
        // adding a neighbour. Widening `*deepseek-v4*` would also have been
        // wrong — it would claim ids this vendor rule should not.
        assertTrue(
            "the legacy scope must be left intact",
            resolverSrc.contains("ModelPattern(\"*deepseek-v4*\")"),
        )
        assertTrue(
            "the new scope is anchored",
            resolverSrc.contains("ModelPattern(\"deepseek-flash*\")"),
        )
    }
}
