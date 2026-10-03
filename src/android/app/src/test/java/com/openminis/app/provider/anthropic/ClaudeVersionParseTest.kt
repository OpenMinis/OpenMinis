package com.openminis.app.provider.anthropic

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M16] The Claude id → (major, minor) PARSER, and the three unrelated
 * behaviours it silently drives.
 *
 * Pins 69be65763 / 500abe41b / 243dadf30. Root cause of all three symptoms was
 * one regex: the minor segment was REQUIRED, so a single-segment id
 * (`claude-fable-5`, `claude-opus-5`) parsed as null instead of (5, 0). Every
 * consumer reads that null as "not a versioned Claude", so one parse failure
 * produced three different user-visible faults at once:
 *
 *   1. `modelRejectsTemperature` → false → the API answered
 *      400 "temperature is deprecated for this model".
 *   2. `modelUsesAdaptiveThinking` → false → the request carried the legacy
 *      `budget_tokens` form, which 4.6+ silently IGNORES. Thinking appeared to
 *      be on and did nothing.
 *   3. `supportsThinking` → false → the Deep Thinking toggle never appeared for
 *      the whole 5-series.
 *
 * WHY A DEDICATED FILE. AnthropicProviderTest already asserts the three
 * CONSUMERS for the 5-series. This pins the parser's own contract instead,
 * because the consumers are all monotone threshold tests (`>= 4.6`) and
 * therefore cannot distinguish the answers that matter most here: a
 * `claude-3-5-sonnet` misparsed as (3, 0) and a `claude-sonnet-4-6` misparsed as
 * (4, 0) both answer "no" to all three questions, exactly as the correct parse
 * of (3, 5) does for the first. So a regression that re-broke the greedy pair
 * match would leave every consumer assertion green. Parsing the version out of
 * the id is its own claim and is asserted as one.
 *
 * `parseClaudeVersion` is private, so the algorithm is PORTED VERBATIM below
 * from AnthropicProvider.kt:840-848 (companion object) and paired with a
 * source-grep drift guard plus consumer cross-checks that tie the port back to
 * the shipping code.
 */
class ClaudeVersionParseTest {

    // ───────────────────────────────────────────────────────────────────
    // VERBATIM PORT of AnthropicProvider.parseClaudeVersion
    // (src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt,
    //  companion object, ~line 840). Copied character-for-character; the drift
    //  guard below asserts the production body still reads this way.
    // ───────────────────────────────────────────────────────────────────
    private fun parseClaudeVersion(modelId: String): Pair<Int, Int>? {
        val lower = modelId.lowercase()
        val claudeAt = lower.indexOf("claude")
        if (claudeAt < 0) return null
        val afterClaude = lower.substring(claudeAt + "claude".length)
        val regex = Regex("""[-/]?(\d+)(?:[-.](\d{1,2}))?(?:$|[^0-9])""")
        val match = regex.find(afterClaude) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major to minor
    }

    private fun assertVersion(expected: Pair<Int, Int>?, id: String) {
        assertEquals("parseClaudeVersion(\"$id\")", expected, parseClaudeVersion(id))
    }

    // ══════════════════════════════════════ single-segment ids (the fix)

    /**
     * THE REGRESSION. An id whose version is one segment must parse as (n, 0),
     * not null. `claude-fable-5` and `claude-opus-5` are the shipping cases;
     * `claude-sonnet-6` stands in for the next one, since the whole point of the
     * optional group is that a future single-segment id needs no code change.
     */
    @Test
    fun `a single-segment version parses as minor zero, not null`() {
        assertVersion(5 to 0, "claude-fable-5")
        assertVersion(5 to 0, "claude-opus-5")
        assertVersion(5 to 0, "claude-sonnet-5")
        assertVersion(5 to 0, "claude-haiku-5")
        assertVersion(6 to 0, "claude-sonnet-6")
    }

    /**
     * The optional group is GREEDY, so a real two-segment version still wins.
     * This is the assertion the consumer tests cannot make: (3, 5) and (3, 0)
     * answer every downstream threshold question identically, so nothing else in
     * the suite notices if the pair match degrades to a bare major.
     */
    @Test
    fun `a real version pair still beats the single-segment reading`() {
        assertVersion(3 to 5, "claude-3-5-sonnet")
        assertVersion(3 to 7, "claude-3-7-sonnet")
        assertVersion(4 to 6, "claude-sonnet-4-6")
        assertVersion(4 to 8, "claude-opus-4-8")
        assertVersion(4 to 1, "claude-opus-4-1")
        assertTrue(
            "the minor must be the second segment, never 0 — that is the greedy match",
            parseClaudeVersion("claude-3-5-sonnet")!!.second == 5,
        )
    }

    /**
     * Both separators, because the id reaches this parser in both spellings:
     * hyphenated from `LLMModel.id` / the Anthropic `/v1/models` list, dotted
     * from models.dev and from an OpenRouter-style namespaced id.
     */
    @Test
    fun `the minor separator may be a hyphen or a dot`() {
        assertVersion(4 to 8, "claude-opus-4-8")
        assertVersion(4 to 8, "claude-opus-4.8")
        assertVersion(3 to 5, "claude-3.5-sonnet")
        assertVersion(4 to 5, "anthropic/claude-sonnet-4.5")
    }

    // ══════════════════════════════════════════════ non-Claude and junk

    /**
     * The `claude` gate comes FIRST, so a versioned id from another vendor never
     * enters the regex. Without it every `gpt-5.3` / `gemini-3-pro` would parse
     * as a Claude version and inherit Claude's temperature and thinking rules —
     * which is a much louder failure than the one being fixed.
     */
    @Test
    fun `a non-Claude id is null however it is versioned`() {
        for (id in listOf(
            "gpt-5.3", "gemini-3-pro-preview", "deepseek-v4", "grok-4-20",
            "mimo-v2.5", "o3-mini", "qwen3-32b", "",
        )) {
            assertNull("non-Claude id must not parse: $id", parseClaudeVersion(id))
        }
    }

    /** A Claude id carrying no digits at all is null — there is nothing to read. */
    @Test
    fun `a Claude id with no version digits is null`() {
        assertNull(parseClaudeVersion("claude"))
        assertNull(parseClaudeVersion("claude-instant"))
        assertNull(parseClaudeVersion("claude-latest"))
    }

    /**
     * The match is case-insensitive via the lowercased id. Catalog and live-API
     * spellings differ in case for some ids, and the whole 5-series bug class is
     * "the parse returned null and three features went quiet", so a case
     * mismatch must not be able to reproduce it.
     */
    @Test
    fun `the parse is case-insensitive`() {
        assertVersion(5 to 0, "Claude-Fable-5")
        assertVersion(4 to 6, "CLAUDE-SONNET-4-6")
    }

    /**
     * A date-stamped id parses on its VERSION, not on the stamp, as long as the
     * version is a PAIR: the regex stops at the first version-shaped run, so the
     * 8-digit date never becomes the version. A greedier or last-match variant
     * would read (2024, 10) and put a 2024-era Claude above every real one in
     * every threshold test.
     *
     * The single-segment + date-stamp combination is a different story — see the
     * KNOWN GAP test at the bottom of this file.
     */
    @Test
    fun `a date-stamped id parses on its version pair, not on the stamp`() {
        assertVersion(3 to 5, "claude-3-5-sonnet-20241022")
        assertVersion(4 to 5, "claude-sonnet-4-5-20250929")
        assertVersion(4 to 8, "claude-opus-4-8-20260115")
    }

    /**
     * Bedrock / Vertex style ids, which is where a "first version-shaped run"
     * parser is most exposed: a region prefix or a trailing `-v1:0` must not
     * contribute digits. Recorded as the parser's ACTUAL answer rather than an
     * aspiration — see the KNOWN GAP below.
     */
    @Test
    fun `namespaced and suffixed vendor ids read their version segment`() {
        assertVersion(4 to 5, "us.anthropic.claude-sonnet-4-5-v1:0")
        assertVersion(3 to 5, "anthropic.claude-3-5-sonnet-20240620-v1:0")
        assertVersion(5 to 0, "anthropic/claude-opus-5")
    }

    // ══════════════════════════════════════ the three consumers, at the boundary

    /**
     * Tie the port back to the shipping code: each consumer is a monotone
     * threshold over the parsed pair, so feeding it the boundary ids proves the
     * REAL parser agrees with the port on the value that decides the answer.
     *
     * 4.6 is the boundary for two of the three (temperature rejection and
     * adaptive thinking share it), so 4.5 vs 4.6 is the row that matters.
     */
    @Test
    fun `the production consumers agree with the ported parse at the 4_6 boundary`() {
        val below = listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-3-7-sonnet", "claude-3-5-sonnet")
        val atOrAbove = listOf("claude-sonnet-4-6", "claude-opus-4-8", "claude-fable-5", "claude-opus-5")

        for (id in below) {
            val (major, minor) = parseClaudeVersion(id)!!
            assertFalse(
                "port says ${major}.$minor < 4.6, so production must accept temperature: $id",
                AnthropicProvider.modelRejectsTemperature(id),
            )
            assertFalse(
                "port says ${major}.$minor < 4.6, so production must use legacy budget thinking: $id",
                AnthropicProvider.modelUsesAdaptiveThinking(id),
            )
        }
        for (id in atOrAbove) {
            val (major, minor) = parseClaudeVersion(id)!!
            assertTrue(
                "port says ${major}.$minor >= 4.6, so production must reject temperature: $id",
                AnthropicProvider.modelRejectsTemperature(id),
            )
            assertTrue(
                "port says ${major}.$minor >= 4.6, so production must use adaptive thinking: $id",
                AnthropicProvider.modelUsesAdaptiveThinking(id),
            )
        }
    }

    /**
     * `supportsThinking` has a DIFFERENT threshold — 3.7, where Anthropic
     * introduced extended thinking — so it must not be assumed to track the
     * other two. It is the flag that makes the Deep Thinking toggle appear at
     * all, and the 5-series parse failure is what hid it.
     */
    @Test
    fun `the thinking-toggle threshold is 3_7, distinct from the 4_6 threshold`() {
        for (id in listOf("claude-3-7-sonnet", "claude-sonnet-4-5", "claude-sonnet-4-6", "claude-fable-5", "claude-opus-5")) {
            assertTrue("the toggle must be offered for $id", AnthropicProvider.supportsThinking(id))
        }
        for (id in listOf("claude-3-5-sonnet", "claude-3-haiku", "claude-2-1")) {
            assertTrue(
                "…and withheld below 3.7: $id",
                !AnthropicProvider.supportsThinking(id),
            )
        }
        assertFalse("a non-Claude id never gets the Anthropic toggle", AnthropicProvider.supportsThinking("gpt-5.3"))
    }

    /**
     * The third consumer, reached through the resolver: the parse decides the
     * wire SHAPE, not just a number. THREE shapes, not two — this test used to
     * assert only two and encoded the bug G5 fixed:
     *
     *   ON, 4.6+ and 5+   → `{effort:…}`      (adaptive)
     *   OFF, 4.6-4.x ONLY → `{disabled:true}` (they think by default, so OFF
     *                                          must be stated explicitly)
     *   OFF, 5+           → `{}`              (Claude 5 REJECTS the literal and
     *                                          treats absence as adaptive)
     *   ON/OFF, ≤4.5      → `{budget_tokens:…}` / `{}`
     *
     * So "is adaptive" and "may we say disabled" are different questions with
     * different answers on 5+, which is exactly what
     * `modelAcceptsExplicitThinkingDisabled` exists to separate.
     */
    @Test
    fun `the parse selects the thinking wire shape, adaptive versus legacy`() {
        // ON: every adaptive generation takes an effort tier, 4.6 through 5+.
        for (id in listOf("claude-fable-5", "claude-opus-5", "claude-sonnet-4-6")) {
            val on = com.openminis.app.provider.thinking.ThinkingRuleResolver
                .anthropicThinkingShape(id, true, ThinkingLevel.HIGH, 8192)
            assertEquals("$id must take adaptive effort", mapOf("effort" to "high"), on)
        }
        // OFF on 4.6-4.x: the literal is REQUIRED — these think by default.
        for (id in listOf("claude-sonnet-4-6", "claude-opus-4-8")) {
            assertEquals(
                "$id thinks by default, so OFF must be stated explicitly",
                mapOf("disabled" to true),
                com.openminis.app.provider.thinking.ThinkingRuleResolver
                    .anthropicThinkingShape(id, true, ThinkingLevel.OFF, 8192),
            )
        }
        // OFF on 5+: the literal is REJECTED — send nothing (iOS 69be65763).
        for (id in listOf("claude-fable-5", "claude-opus-5", "claude-sonnet-5")) {
            assertTrue(
                "Claude 5 400s on thinking.type=disabled and treats absence as adaptive, " +
                    "so OFF must send nothing: $id",
                com.openminis.app.provider.thinking.ThinkingRuleResolver
                    .anthropicThinkingShape(id, true, ThinkingLevel.OFF, 8192).isEmpty(),
            )
        }
        val legacyOn = com.openminis.app.provider.thinking.ThinkingRuleResolver
            .anthropicThinkingShape("claude-sonnet-4-5", true, ThinkingLevel.HIGH, 8192)
        assertTrue("a pre-4.6 model takes a token budget: $legacyOn", legacyOn.containsKey("budget_tokens"))
        val legacyOff = com.openminis.app.provider.thinking.ThinkingRuleResolver
            .anthropicThinkingShape("claude-sonnet-4-5", true, ThinkingLevel.OFF, 8192)
        assertTrue("…and sends nothing at OFF: $legacyOff", legacyOff.isEmpty())
    }

    // ══════════════════════════════════════════════ drift guard

    /**
     * SOURCE-GREP DRIFT GUARD. The port above is a copy, so it stays correct
     * only while production reads the same way. Pin the regex literal and the
     * optional-minor default — those two lines ARE the fix — plus the three
     * thresholds, so a consumer cannot be retuned without this file noticing.
     */
    @Test
    fun `the production parser still carries the ported regex and defaults`() {
        val src = ProductionSources.read("provider/anthropic/AnthropicProvider.kt")
        assertTrue(
            "the ported regex literal is gone from AnthropicProvider — re-port it",
            src.contains("""Regex(""${'"'}[-/]?(\d+)(?:[-.](\d{1,2}))?(?:${'$'}|[^0-9])""${'"'})"""),
        )
        assertTrue(
            "the minor group must stay capped at 1-2 digits — an uncapped group swallows a " +
                "snapshot stamp and reads claude-sonnet-4-20250514 as (4, 20250514)",
            src.contains("""(?:[-.](\d{1,2}))?"""),
        )
        assertTrue(
            "the search must stay anchored AFTER the word claude, or a digit-bearing namespace " +
                "(v2-gateway/claude-opus-5) is read as the version",
            src.contains("val afterClaude = lower.substring(claudeAt + \"claude\".length)") &&
                src.contains("regex.find(afterClaude)"),
        )
        assertTrue(
            "the minor segment must stay OPTIONAL with a 0 default — this is the 5-series fix",
            src.contains("val minor = match.groupValues[2].toIntOrNull() ?: 0"),
        )
        assertTrue(
            "the non-Claude gate must stay ahead of the regex (now expressed as the anchor index)",
            src.contains("val claudeAt = lower.indexOf(\"claude\")") &&
                src.contains("if (claudeAt < 0) return null"),
        )
        assertTrue("temperature threshold moved", src.contains("return major > 4 || (major == 4 && minor >= 6)"))
        assertTrue(
            "modelAcceptsExplicitThinkingDisabled must stay a 4.6 <= v < 5 window — it is NOT " +
                "the same predicate as modelUsesAdaptiveThinking, which also covers 5+",
            src.contains("fun modelAcceptsExplicitThinkingDisabled(modelId: String): Boolean") &&
                src.contains("return major == 4 && minor >= 6"),
        )
        assertTrue(
            "the request builder must gate the disabled literal on acceptsExplicitThinkingDisabled, " +
                "not on modelUsesAdaptiveThinking",
            src.contains("} else if (modelAcceptsExplicitThinkingDisabled(model.id)) {"),
        )
        val resolver = ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        assertTrue(
            "the resolver's OFF shape must ask acceptsExplicitThinkingDisabled, not adaptive",
            resolver.contains(".modelAcceptsExplicitThinkingDisabled(modelId)"),
        )
        assertTrue(
            "the thinking-toggle threshold moved off 3.7",
            src.contains("return major > 4 || major == 4 || (major == 3 && minor >= 7)"),
        )
    }

    /**
     * WAS A KNOWN GAP, now FIXED and asserted: a digit-bearing namespace BEFORE
     * the word `claude` used to be read as the version.
     *
     * The parser took the FIRST version-shaped run in the id, which is a
     * positional assumption rather than a Claude-anchored one. An Azure/Bedrock
     * style deployment name (`v2-gateway/claude-opus-5`) parsed as (2, 0), and a
     * provider-instance prefix (`inst-53/claude-haiku-4-5`) as (53, 0) — so a
     * 5-series model would take `temperature` plus legacy budget thinking, i.e.
     * exactly the 400 and the silent no-op this commit family fixed.
     *
     * FIX: the search starts after the `claude` occurrence, so only digits that
     * follow the family name can be the version.
     */
    @Test
    fun `a digit-bearing namespace before the word claude is not read as the version`() {
        assertVersion(5 to 0, "v2-gateway/claude-opus-5")
        assertVersion(4 to 5, "inst-53/claude-haiku-4-5")
        assertVersion(5 to 0, "inst-7/anthropic/claude-sonnet-5")
        // …and a namespace with no digits was always fine; it must stay so.
        assertVersion(5 to 0, "anthropic/claude-opus-5")

        // The consequence that motivated the fix: such an id is now correctly
        // treated as 5-series rather than as a pre-4.6 model.
        assertTrue(
            "a namespaced 5-series id must reject temperature",
            AnthropicProvider.modelRejectsTemperature("v2-gateway/claude-opus-5"),
        )
        assertTrue(
            "…and use adaptive thinking",
            AnthropicProvider.modelUsesAdaptiveThinking("v2-gateway/claude-opus-5"),
        )
    }

    /**
     * WAS A KNOWN GAP, now FIXED and asserted: for a SINGLE-segment version the
     * optional minor group greedily swallowed a trailing snapshot stamp, so
     * `claude-opus-5-20260401` parsed as (5, 20260401) rather than (5, 0). A
     * version PAIR was always fine (`claude-opus-4-8-20260115` → (4, 8)) because
     * the pair consumes the separator first.
     *
     * This was NOT harmless. Two shipping catalog ids hit it —
     * `claude-sonnet-4-20250514` and `claude-opus-4-20250514` (Claude Sonnet 4.0
     * and Opus 4.0), plus their `anthropic/` forms — and parsed as
     * (4, 20250514), i.e. >= 4.6. Those 4.0 models therefore had `temperature`
     * stripped, were sent adaptive `output_config.effort` instead of the
     * `budget_tokens` form they actually understand, and were sent an explicit
     * `thinking.type=disabled` at OFF. Only the 5-series consumers happen to be
     * lower-bound thresholds where the misparse is invisible.
     *
     * FIX: the minor group is bounded to 1-2 digits. No real Claude version has
     * ever had a 3+ digit minor.
     */
    @Test
    fun `a single-segment version does not swallow a trailing date stamp as its minor`() {
        assertVersion(5 to 0, "claude-opus-5-20260401")
        assertVersion(5 to 0, "claude-sonnet-5-20260101")
        // The version-PAIR form was never affected and must stay intact.
        assertVersion(4 to 8, "claude-opus-4-8-20260115")
        assertVersion(3 to 5, "claude-3-5-sonnet-20241022")

        // The shipping ids the gap actually mis-drove: Sonnet/Opus 4.0 snapshots
        // must read as 4.0, and therefore take the LEGACY treatment.
        for (id in listOf(
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250514",
            "anthropic/claude-sonnet-4-20250514",
        )) {
            assertVersion(4 to 0, id)
            assertFalse(
                "a Claude 4.0 snapshot id must keep temperature: $id",
                AnthropicProvider.modelRejectsTemperature(id),
            )
            assertFalse(
                "…and must use legacy budget thinking, not adaptive effort: $id",
                AnthropicProvider.modelUsesAdaptiveThinking(id),
            )
        }

        // The 5-series behaviour the original fix delivered is unchanged.
        val dated = "claude-opus-5-20260401"
        assertTrue("a dated 5-series id must still reject temperature", AnthropicProvider.modelRejectsTemperature(dated))
        assertTrue("…and still use adaptive thinking", AnthropicProvider.modelUsesAdaptiveThinking(dated))
        assertTrue("…and still offer the toggle", AnthropicProvider.supportsThinking(dated))
    }
}
