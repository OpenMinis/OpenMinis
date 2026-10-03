package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-modelsdev-suffix-alias] Prefix walk used to match an aliased
 * model id against models.dev.
 *
 * Reported: a relay publishing ~14 CPA models returned `unknown_path` for every
 * metadata field. Every lookup in ModelsDevApi was an exact
 * `prov.models[model.id]`, so a vendor id carrying a suffix —
 * `glm-5.3-flash-<suffix>` — never matched the catalogued `glm-5.3-flash` and
 * no context window / reasoning flag was ever applied. The same report notes
 * that PREFIXED ids already worked, which is what an exact-match lookup
 * predicts and what makes the suffix the whole story.
 *
 * The risk in shortening ids is the opposite failure: letting an unrelated
 * model inherit a real model's metadata. These tests pin both directions.
 */
class ModelsDevAliasTest {

    private fun candidates(id: String) = ModelsDevApi.aliasCandidates(id)

    @Test
    fun `the reported suffix alias reaches its catalogued base`() {
        val c = candidates("glm-5.3-flash-cpa")
        assertTrue("must reach the catalogued base id: $c", c.contains("glm-5.3-flash"))
    }

    @Test
    fun `the exact id is always tried first`() {
        // An exact match must never be shadowed by a shortened one, or a relay
        // whose id is a prefix of another catalogued model would lose its own
        // entry.
        assertEquals("glm-5.3-flash-cpa", candidates("glm-5.3-flash-cpa").first())
        assertEquals("gpt-5-codex", candidates("gpt-5-codex").first())
    }

    @Test
    fun `candidates are ordered longest first`() {
        // The most specific catalogued entry should win, so the walk has to
        // offer the longer prefix before the shorter one.
        assertEquals(listOf("a-b-c", "a-b"), candidates("a-b-c"))
    }

    @Test
    fun `the walk stops before a bare generic head`() {
        // "glm" / "gpt" / "claude" alone must never be a lookup key: an
        // unrelated model would inherit a real model's context window.
        val c = candidates("glm-5.3-flash-cpa")
        assertFalse("must not degrade to a bare vendor head: $c", c.contains("glm"))
        assertFalse(candidates("gpt-5-codex-preview").contains("gpt"))
    }

    @Test
    fun `a two segment id does not shorten at all`() {
        assertEquals(listOf("gpt-5"), candidates("gpt-5"))
        assertEquals(listOf("claude-opus"), candidates("claude-opus"))
    }

    @Test
    fun `a prefixed id keeps working unchanged`() {
        // The reporter observed prefixes already resolve; that path must be
        // untouched — the id itself is candidate 0 either way.
        assertEquals("cpa-glm-5.3", candidates("cpa-glm-5.3").first())
    }

    @Test
    fun `colon separated ids are handled`() {
        // OpenRouter-style vendor-qualified ids.
        val c = candidates("openai:gpt-5-preview")
        assertTrue("colon must be a separator too: $c", c.contains("openai:gpt-5"))
    }

    @Test
    fun `degenerate inputs terminate and stay inert`() {
        // The walk must not loop or throw on ids that are all separators or
        // empty — these come from third-party relays, not a curated catalog.
        assertEquals(listOf(""), candidates(""))
        assertEquals(listOf("gpt"), candidates("gpt"))
        assertTrue(candidates("-x-y").isNotEmpty())
        assertTrue(candidates("---").isNotEmpty())
    }

    @Test
    fun `no candidate is ever longer than the input`() {
        for (id in listOf("a-b-c-d-e", "openai:gpt-5-codex-preview", "glm-5.3-flash-cpa")) {
            assertTrue(candidates(id).all { it.length <= id.length })
        }
    }

    // ── [M04] The id shapes a relay actually publishes ────────────────────
    //
    // The cases above establish the walk's rules. These pin it against the three
    // shapes named in the spec — two dots, a dated snapshot suffix, a `vendor/`
    // path prefix — because each is a real wire id and each stresses a different
    // part of the splitter. The walk cuts on `-` and `:` ONLY: `.` and `/` are
    // not separators here, and that asymmetry is deliberate (a version dot is
    // part of the name, and a path prefix is part of the catalog key).

    @Test
    fun `an id with two dots keeps both, shortening only on hyphens`() {
        // `qwen3.5-max.preview-cpa`: the dots are version/channel markers, not
        // separators. Cutting on a dot would ask the catalog for `qwen3` — a
        // bare generic head — and let an unrelated model's metadata through.
        val c = candidates("qwen3.5-max.preview-cpa")
        assertEquals("qwen3.5-max.preview-cpa", c.first())
        assertTrue("must reach the hyphen-trimmed base: $c", c.contains("qwen3.5-max.preview"))
        assertFalse("a dot is not a cut point: $c", c.any { it == "qwen3" })
        assertTrue("no candidate may lose a dot: $c", c.all { it.count { ch -> ch == '.' } >= 1 })
    }

    @Test
    fun `a dated snapshot suffix is shortened away one segment at a time`() {
        // `claude-sonnet-4-6-20260115` is how a dated Anthropic id arrives from
        // a relay. The walk reaches the undated base, and — the part worth
        // pinning — it does so by plain hyphen trimming, so the intermediate
        // steps are real prefixes rather than a regex guess at what a date is.
        val c = candidates("claude-sonnet-4-6-20260115")
        assertEquals("claude-sonnet-4-6-20260115", c.first())
        assertTrue("must reach the undated id: $c", c.contains("claude-sonnet-4-6"))
        assertTrue(c.contains("claude-sonnet-4"))
        assertTrue(c.contains("claude-sonnet"))
        // …and stops before the bare vendor head, which would let any Claude
        // inherit any other Claude's context window.
        assertFalse("must never degrade to `claude`: $c", c.contains("claude"))
    }

    @Test
    fun `a vendor slash prefix is preserved, never cut`() {
        // OpenRouter-style `anthropic/claude-sonnet-4-6` is catalogued under the
        // namespaced id. Cutting at the slash would turn a precise lookup into a
        // cross-vendor one; the candidates therefore all keep the prefix.
        val c = candidates("anthropic/claude-sonnet-4-6-20260115")
        assertEquals("anthropic/claude-sonnet-4-6-20260115", c.first())
        assertTrue("must reach the namespaced undated id: $c",
            c.contains("anthropic/claude-sonnet-4-6"))
        assertTrue("every candidate keeps the vendor prefix: $c",
            c.all { it.startsWith("anthropic/") })
    }

    @Test
    fun `a colon vendor prefix IS a cut point, unlike a slash`() {
        // The two prefix spellings behave differently on purpose: `:` is in the
        // cut set, `/` is not. Worth pinning as a pair, because "make them
        // consistent" is the obvious wrong refactor — it would either strand
        // every OpenRouter id or let `openai:` be dropped wholesale.
        val colon = candidates("openai:gpt-5-codex-preview")
        assertTrue("colon shortens: $colon", colon.contains("openai:gpt-5-codex"))
        assertTrue(colon.contains("openai:gpt-5"))

        val slash = candidates("openai/gpt-5-codex-preview")
        assertTrue("slash never splits: $slash", slash.all { it.startsWith("openai/gpt-5") })
    }

    @Test
    fun `the segment floor counts across both separators`() {
        // MIN_ALIAS_SEGMENTS = 2, counted over `-` AND `:` — so `openai:gpt-5`
        // is three segments and may still shorten, while `gpt-5` is two and
        // stops. Getting the count wrong in either direction is silent: too
        // strict strands aliases, too loose hands out generic-head lookups.
        assertTrue(candidates("openai:gpt-5-codex").contains("openai:gpt-5"))
        assertEquals(listOf("gpt-5"), candidates("gpt-5"))
        assertEquals(listOf("openai:gpt"), candidates("openai:gpt"))
    }

    @Test
    fun `candidates are unique and strictly shrinking`() {
        // The walk must terminate with no repeats — a duplicate would double
        // every catalog probe for that id, and the resolver runs this per model
        // on a list that can be 800+ entries.
        for (id in listOf(
            "claude-sonnet-4-6-20260115",
            "anthropic/claude-sonnet-4-6-20260115",
            "qwen3.5-max.preview-cpa",
            "openai:gpt-5-codex-preview",
            "a-b-c-d-e-f-g",
        )) {
            val c = candidates(id)
            assertEquals("duplicate candidates for $id: $c", c.size, c.distinct().size)
            for (i in 1 until c.size) {
                assertTrue(
                    "candidate $i is not shorter than ${i - 1} for $id: $c",
                    c[i].length < c[i - 1].length,
                )
            }
        }
    }

    // ── [M04] Aggregate fallback is for UNRECOGNIZED providers only ───────
    //
    // ModelsDevAggregateFallbackTest covers how an aggregate is computed. The
    // gating is restated here because it is the half that protects first-party
    // accuracy: an aggregate is a MAXIMUM over a family, so applying it to a
    // direct Anthropic or OpenAI connection could replace a precisely-known
    // context window with a larger sibling's.

    @Test
    fun `a third-party endpoint is unrecognized and a first-party one is not`() {
        // "Custom" is what OpenAIModelsApi stamps on a relay-served model, which
        // is what makes the reported unknown-endpoint case reach stage 3.
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Custom"))
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Some Relay"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("Anthropic"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("OpenAI"))
    }
}
