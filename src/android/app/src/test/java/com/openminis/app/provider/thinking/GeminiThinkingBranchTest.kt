package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M15] The THREE-WAY BRANCH in `ThinkingRuleResolver.geminiThinkingConfig`:
 * gemini-3.x (thinkingLevel) / 2.5-pro (budget, floor 128) / 2.5-flash (budget,
 * floor 0) — and the two ids that must fall out of it entirely, 2.5-flash-lite
 * and the specialized modalities.
 *
 * Pins d5d2aaba4 / 2881f9cbf (3.7+ Flash rejects thinkingLevel "minimal" and
 * must floor at "low") and 4b6121833 / GH#226 (a TTS id gets no thinking config
 * and no systemInstruction — both are hard 400s).
 *
 * WHY THIS FILE, given ThinkingWireGeminiAnthropicSnapshotTest exists: the
 * snapshot is a value oracle over a fixed id list. It cannot state the STRUCTURAL
 * invariants, and those are where this function's bugs have actually come from:
 *
 *  • SUBSTRING COLLAPSE. `"gemini-2.5-flash-lite".contains("gemini-2.5-flash")`
 *    is true, so the flash branch would claim flash-lite if the guards were
 *    ordered or written differently. The snapshot records "flash-lite → null"
 *    but says nothing about which of the two mechanisms keeps it there, so a
 *    refactor can delete the `!contains("lite")` guard and re-add the null
 *    through an unrelated path with every row intact.
 *  • PRECEDENCE. A TTS id also matches a family pattern
 *    (`gemini-3.1-flash-tts-preview` contains "gemini-3"), so the suffix check
 *    is only correct BECAUSE it runs first. Both platforms shipped that check
 *    below the family branches at some point, where it was dead code.
 *  • MUTUAL EXCLUSIVITY. Three overlapping `contains` predicates over one id is
 *    exactly the shape that silently double-matches.
 *
 * So these tests assert relationships between ids rather than values per id, and
 * add a source-grep guard on the guards themselves.
 */
class GeminiThinkingBranchTest {

    private fun config(id: String, level: ThinkingLevel) =
        ThinkingRuleResolver.geminiThinkingConfig(id, level)

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
    )

    // ══════════════════════════════════════ the branches are distinguishable

    /**
     * Each branch is identified by the KEY it writes, not by a value: 3.x speaks
     * `thinkingLevel` (an enum), 2.5 speaks `thinkingBudget` (an int). Mixing
     * them is not a tuning difference — the wrong key is rejected outright.
     */
    @Test
    fun `the 3x branch speaks thinkingLevel and the 2_5 branch speaks thinkingBudget`() {
        for (level in levels) {
            val three = config("gemini-3-pro-preview", level)!!
            assertTrue("3.x must use thinkingLevel at $level: $three", three.has("thinkingLevel"))
            assertFalse("3.x must never use thinkingBudget at $level: $three", three.has("thinkingBudget"))

            for (id in listOf("gemini-2.5-pro", "gemini-2.5-flash")) {
                val two = config(id, level)!!
                assertTrue("$id must use thinkingBudget at $level: $two", two.has("thinkingBudget"))
                assertFalse("$id must never use thinkingLevel at $level: $two", two.has("thinkingLevel"))
            }
        }
    }

    /**
     * pro and flash are the SAME mechanism with different numbers, and the
     * difference that matters is the OFF floor: 2.5-pro rejects `thinkingBudget:
     * 0` (df8a823d) and must send its documented minimum of 128, while 2.5-flash
     * accepts 0 and genuinely stops thinking. Asserted as a relationship so a
     * future retune of the ladder cannot quietly give pro a zero floor.
     */
    @Test
    fun `2_5-pro floors at 128 while 2_5-flash floors at 0`() {
        assertEquals(128, config("gemini-2.5-pro", ThinkingLevel.OFF)!!.getInt("thinkingBudget"))
        assertEquals(0, config("gemini-2.5-flash", ThinkingLevel.OFF)!!.getInt("thinkingBudget"))
        assertTrue(
            "pro's floor must stay strictly above flash's — 0 is a 400 on pro",
            config("gemini-2.5-pro", ThinkingLevel.OFF)!!.getInt("thinkingBudget") >
                config("gemini-2.5-flash", ThinkingLevel.OFF)!!.getInt("thinkingBudget"),
        )
        // pro's ladder sits above flash's at every enabled tier, for the same
        // reason the model exists. A regression that swapped the two tables
        // would keep both keys and both branch labels intact.
        for (level in levels.filter { it.isEnabled }) {
            assertTrue(
                "pro must budget more than flash at $level",
                config("gemini-2.5-pro", level)!!.getInt("thinkingBudget") >
                    config("gemini-2.5-flash", level)!!.getInt("thinkingBudget"),
            )
        }
    }

    /**
     * `includeThoughts` is the "stream the thinking text back" opt-in, so it
     * belongs on exactly the enabled tiers: asking for thoughts on an OFF
     * request either wastes tokens or contradicts the budget.
     */
    @Test
    fun `includeThoughts tracks the enabled tiers, on every branch`() {
        for (id in listOf("gemini-3-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash")) {
            assertFalse(
                "$id must not ask for thoughts at OFF: ${config(id, ThinkingLevel.OFF)}",
                config(id, ThinkingLevel.OFF)!!.optBoolean("includeThoughts", false),
            )
            for (level in levels.filter { it.isEnabled }) {
                assertTrue(
                    "$id must ask for thoughts at $level",
                    config(id, level)!!.optBoolean("includeThoughts", false),
                )
            }
        }
    }

    // ═══════════════════════════════ the substring collapse (the M15 headline)

    /**
     * THE COLLAPSE. `"gemini-2.5-flash-lite".contains("gemini-2.5-flash")` is
     * true, so without the `!contains("lite")` guard flash-lite would inherit
     * flash's budget table — for a model that takes no thinking config at all.
     *
     * Asserted both ways round: the containment is REAL (so the guard is load-
     * bearing, not decoration) and the outcome is nonetheless null at every
     * level. Stating the containment in the test is the part a value snapshot
     * cannot do; it is what makes a later reader see why the guard exists.
     */
    @Test
    fun `flash-lite is not swept in by the flash substring`() {
        assertTrue(
            "precondition: the ids really do overlap, which is why a guard is needed",
            "gemini-2.5-flash-lite".contains("gemini-2.5-flash"),
        )
        for (level in levels) {
            assertNull(
                "gemini-2.5-flash-lite must take NO thinking config at $level",
                config("gemini-2.5-flash-lite", level),
            )
        }
        // And the guard must not over-reach: an id that merely contains "lite"
        // elsewhere is still a flash model. (`gemini-2.5-flash-lite-preview` is
        // correctly excluded; a hypothetical `…-flash-elite` is not the case
        // being guarded, so use the real shape.)
        assertNull(
            "the lite PREVIEW spelling is still lite",
            config("gemini-2.5-flash-lite-preview-06-17", ThinkingLevel.HIGH),
        )
    }

    /**
     * The same collapse hazard between the 2.5 branches: `gemini-2.5-pro` and
     * `gemini-2.5-flash` are disjoint substrings, so exactly one branch may
     * claim any id. A future `contains("gemini-2.5")` simplification would
     * merge the two ladders and is caught here.
     */
    @Test
    fun `no id is claimed by two of the three families`() {
        // A representative id per family, each asserted to produce its OWN
        // family's key/floor rather than a neighbour's.
        val claims = mapOf(
            "gemini-3-pro-preview" to "thinkingLevel",
            "gemini-3.1-flash" to "thinkingLevel",
            "gemini-2.5-pro" to "thinkingBudget",
            "gemini-2.5-pro-exp-0827" to "thinkingBudget",
            "gemini-2.5-flash" to "thinkingBudget",
        )
        for ((id, key) in claims) {
            val c = config(id, ThinkingLevel.MEDIUM)!!
            assertEquals("$id must emit exactly one thinking key: $c", 2, c.length()) // key + includeThoughts
            assertTrue("$id must emit $key: $c", c.has(key))
        }
    }

    /**
     * A 3.x id wins over a 2.5 substring if both somehow appear — the
     * `isGemini3` check is first among the family branches. Constructed rather
     * than observed: no such id ships, but the ordering is the invariant, and an
     * accidental reorder is invisible on real ids.
     */
    @Test
    fun `the 3x family check precedes the 2_5 checks`() {
        val src = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        val three = src.indexOf("val isGemini3 = ")
        val pro = src.indexOf("val is25Pro = ")
        val flash = src.indexOf("val is25Flash = ")
        val lite = src.indexOf("val is25FlashLite = ")
        assertTrue("isGemini3 not found", three > 0)
        assertTrue("isGemini3 must be evaluated before the 2.5 predicates", three < pro && pro < flash)
        assertTrue("is25FlashLite must exist as its own predicate", lite > flash)
        assertTrue(
            "the flash predicate must still carry its anti-lite guard — this is the collapse",
            src.contains("modelId.contains(\"gemini-2.5-flash\") && !modelId.contains(\"lite\")"),
        )
        assertTrue(
            "flash-lite must still short-circuit to null before the branch table",
            src.contains("if (is25FlashLite) return null"),
        )
    }

    // ═════════════════════════ the 3.7+ OFF floor (d5d2aaba4, 2881f9cbf)

    /**
     * 3.x cannot fully disable thinking, so OFF means "the weakest level this
     * model accepts" — and that floor is NOT uniform across the family. Verified
     * on-device: 3-flash / 3.5-flash / 3.6-flash accept "minimal", but
     * gemini-3.7-flash answers EVERY request with
     *   400 "Thinking level MINIMAL is not supported for this model."
     * so with thinking Off the model was not merely un-thinking, it was
     * completely unusable.
     *
     * The split is by MINOR VERSION rather than an id list because the asymmetry
     * decides it: guessing "low" for a model that would have accepted "minimal"
     * costs a slightly higher floor, while guessing "minimal" for one that
     * rejects it costs the whole model.
     */
    @Test
    fun `3x Flash floors at minimal through 3_6 and at low from 3_7 up`() {
        for (id in listOf("gemini-3-flash-preview", "gemini-3.5-flash", "gemini-3.6-flash")) {
            assertEquals(
                "$id accepts minimal (verified on-device)",
                "minimal",
                config(id, ThinkingLevel.OFF)!!.getString("thinkingLevel"),
            )
        }
        for (id in listOf("gemini-3.7-flash", "gemini-3.8-flash", "gemini-3.10-flash")) {
            assertEquals(
                "$id must fall back to low — minimal is a hard 400",
                "low",
                config(id, ThinkingLevel.OFF)!!.getString("thinkingLevel"),
            )
        }
    }

    /**
     * The floor rule is scoped to FLASH. 3.x Pro never had "minimal" and already
     * used "low", so it must be unaffected in both version ranges — otherwise the
     * fix would read as "3.7 changed something about Pro too", which it did not.
     */
    @Test
    fun `3x Pro floors at low regardless of minor version`() {
        for (id in listOf("gemini-3-pro-preview", "gemini-3.6-pro", "gemini-3.7-pro")) {
            assertEquals("low", config(id, ThinkingLevel.OFF)!!.getString("thinkingLevel"))
        }
    }

    /**
     * And it is scoped to OFF: 3.7 Flash is a normal 3.x model at every enabled
     * tier. A fix that floored the whole ladder would have capped the model at
     * "low" forever.
     */
    @Test
    fun `the 3_7 floor does not touch the enabled tiers`() {
        assertEquals("low", config("gemini-3.7-flash", ThinkingLevel.LOW)!!.getString("thinkingLevel"))
        assertEquals("medium", config("gemini-3.7-flash", ThinkingLevel.MEDIUM)!!.getString("thinkingLevel"))
        for (level in listOf(ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA)) {
            assertEquals(
                "Gemini's ladder tops out at high, so $level maps there",
                "high",
                config("gemini-3.7-flash", level)!!.getString("thinkingLevel"),
            )
        }
    }

    // ═══════════════════════════ specialized modalities (4b6121833, GH#226)

    /**
     * A specialized-modality id takes NO thinking config, at any level — these
     * models answer the parameter with
     *   400 "Thinking level is not supported for this model."
     *
     * The rows that matter are the ones that ALSO match a family pattern:
     * `gemini-3.1-flash-tts-preview` contains "gemini-3" and
     * `gemini-2.5-flash-preview-tts` contains "gemini-2.5-flash". Before the
     * check was hoisted above the family branches, every Gemini TTS model on
     * Android was unusable, and the plain `gemini-2.0-tts` id could not reveal
     * it: matching no family, it already fell through to null.
     */
    @Test
    fun `specialized modalities take no thinking config even when they match a family`() {
        val alsoMatchesAFamily = listOf(
            "gemini-3.1-flash-tts-preview" to "gemini-3",
            "gemini-2.5-flash-preview-tts" to "gemini-2.5-flash",
            "gemini-2.5-pro-preview-tts" to "gemini-2.5-pro",
            "gemini-3-pro-image-preview" to "gemini-3",
        )
        for ((id, family) in alsoMatchesAFamily) {
            assertTrue(
                "precondition: $id must really match the $family pattern, else the row proves nothing",
                id.contains(family),
            )
            for (level in levels) {
                assertNull("$id must take no thinking config at $level", config(id, level))
            }
        }
    }

    /** Every documented suffix, in both the trailing and the mid-id position. */
    @Test
    fun `all four specialized suffixes are recognised, trailing or mid-id`() {
        for (suffix in listOf("tts", "image", "embedding", "vision")) {
            assertNull(
                "trailing -$suffix",
                config("gemini-3-pro-$suffix", ThinkingLevel.HIGH),
            )
            assertNull(
                "mid-id -$suffix-",
                config("gemini-3-pro-$suffix-preview", ThinkingLevel.HIGH),
            )
        }
    }

    /**
     * Case-insensitivity is deliberate, not incidental: the bundled catalog and
     * the live API disagree on case for some ids, and a case-sensitive suffix
     * check would have left the uppercase spelling 400ing.
     */
    @Test
    fun `the suffix match is case-insensitive`() {
        assertNull(config("Gemini-3.1-Flash-TTS-Preview", ThinkingLevel.HIGH))
        assertNull(config("gemini-2.5-pro-EMBEDDING", ThinkingLevel.HIGH))
    }

    /**
     * The suffix must not fire on a substring that is not the modality: a model
     * whose name merely contains those letters is a normal text model. Guards
     * against a future loosening of `endsWith`/`contains("-x-")` into a bare
     * `contains("tts")`.
     */
    @Test
    fun `the suffix match does not fire on an incidental substring`() {
        assertNotNull(
            "a plain 3.x flash id is unaffected",
            config("gemini-3-flash-preview", ThinkingLevel.HIGH),
        )
        assertNotNull(
            "'vision' as part of a longer word is not the -vision modality",
            config("gemini-2.5-pro-envisioned", ThinkingLevel.HIGH),
        )
    }

    /**
     * An id matching no family at all returns null rather than a guessed
     * default. Android and iOS genuinely differ here — iOS has a conservative
     * 128-floor fallback table — and that difference is pre-existing and
     * deliberate on this side, so it is pinned rather than "fixed".
     */
    @Test
    fun `an unknown gemini id takes no config rather than a guessed default`() {
        for (id in listOf("gemini-flash-latest", "gemini-2.0-pro", "unknown-model")) {
            assertNull(config(id, ThinkingLevel.HIGH))
        }
    }

    // ══════════════════════════════════════════════ drift guard

    /**
     * SOURCE-GREP DRIFT GUARD on the two orderings this function's correctness
     * rests on, plus the version predicate. All three are invisible to a value
     * snapshot: the suffix check must be FIRST (it is dead below the families),
     * and the minor-version threshold must stay `>= 7`.
     */
    @Test
    fun `the specialized-suffix check still runs before every family branch`() {
        val src = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        val fnAt = src.indexOf("fun geminiThinkingConfig(")
        assertTrue("geminiThinkingConfig not found", fnAt > 0)
        val body = src.substring(fnAt)
        val suffixCheck = body.indexOf("noThinkingSuffixes")
        val familyCheck = body.indexOf("val isGemini3 = ")
        assertTrue("the suffix list is gone", suffixCheck > 0)
        assertTrue(
            "the specialized-suffix check must precede the family branches — below them it is dead code (GH#226)",
            suffixCheck < familyCheck,
        )
        assertTrue(
            "the suffix list must still name all four modalities",
            body.contains("listOf(\"-tts\", \"-image\", \"-embedding\", \"-vision\")"),
        )
        assertTrue(
            "the suffix match must stay case-insensitive via the lowercased id",
            body.contains("val lowerId = modelId.lowercase()"),
        )
        assertTrue(
            "the 3.7 floor must stay a minor-version comparison, not an id list",
            src.contains("return minor >= 7"),
        )
        assertTrue(
            "…driven by the gemini-3.<minor> regex",
            src.contains("Regex(\"\"\"gemini-3\\.(\\d+)\"\"\")"),
        )
    }
}
