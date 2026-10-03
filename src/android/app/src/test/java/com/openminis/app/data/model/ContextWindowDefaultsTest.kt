package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M10][T-anthropic-context-window][T-android-grok-context-underestimate]
 * Context-window defaults for models the catalog has not shipped yet.
 *
 * Two field reports, one mechanism. `LLMModel.contextWindowTokens` answers with
 * a declared `contextWindow` when there is one and otherwise runs an id
 * heuristic, and ContextPolicy turns whatever comes back into
 * `compactThreshold = window - 20_000`. So an UNDER-count does not merely
 * mis-label a number in the Usage sheet — it makes the agent loop compact at a
 * fraction of the real capacity, which reads as "it keeps forgetting" and never
 * points at a context window.
 *
 *   507105f09  every Claude id mapped to 200K, but modern Opus / Sonnet / Fable
 *              are 1M. A fifth of the true window.
 *   33b028477  no xAI branch existed at all, so an uncatalogued Grok id fell
 *              through to the trailing 128K default. iOS measured grok-4.6
 *              compacting 6 times in 47 minutes on that.
 *
 * The heuristic only ever runs for ids models.dev has NOT catalogued — an
 * explicit value always wins — which is why the precedence tests below matter
 * as much as the per-family values. Over-counting is the opposite hazard (a
 * request that 400s for exceeding the real window), so each family pins a
 * specific number rather than "big enough".
 *
 * Two consumers, two functions: `LLMModel.contextWindowTokens` (agent loop,
 * token accounting, compaction) and `inferContextWindowTokens` (the model-group
 * "Limit Context Window" slider ceiling). They are deliberately separate — see
 * ModelContextWindowHeuristic's header — and both are covered here because a
 * divergence between them shows up as a slider that cannot reach the window the
 * loop is actually using.
 */
class ContextWindowDefaultsTest {

    private fun model(id: String, ctx: Int? = null) =
        LLMModel(id = id, displayName = id, provider = "P", contextWindow = ctx)

    private fun window(id: String) = model(id).contextWindowTokens

    // ── The two reported regressions ──────────────────────────────────────

    /**
     * 33b028477. `grok-4.6` is genuinely absent from the bundled catalog, so it
     * reaches the heuristic — that is what made the report possible at all.
     */
    @Test
    fun `an unknown Grok is not the generic 128K default`() {
        assertNotEquals(
            "a Grok 4+ id must not inherit the trailing default",
            128_000,
            window("grok-4.6"),
        )
        assertEquals(256_000, window("grok-4.6"))
    }

    /**
     * 507105f09. Modern Claude is 1M; only Haiku and the legacy 2.x/3.x line
     * are 200K. The mistake was one branch covering all of them.
     */
    @Test
    fun `modern Claude is 1M, not 200K`() {
        for (id in listOf(
            "claude-fable-5-1", "claude-fable-5", "claude-opus-5",
            "claude-opus-4-8", "claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-4-6",
        )) {
            assertEquals("$id must be 1M", 1_000_000, window(id))
        }
    }

    // ── Claude: where the 200K line actually falls ─────────────────────────

    @Test
    fun `Haiku and the legacy Claude generations stay at 200K`() {
        assertEquals(200_000, window("claude-haiku-4-5"))
        assertEquals(200_000, window("claude-3-5-haiku-20241022"))
        assertEquals(200_000, window("claude-3-5-sonnet-20241022"))
        assertEquals(200_000, window("claude-3-opus-20240229"))
        assertEquals(200_000, window("claude-2.1"))
    }

    /**
     * Haiku is checked BEFORE the generation split, so a hypothetical
     * `claude-haiku-5` stays 200K rather than inheriting the modern 1M. Pinned
     * because the branch order is the only thing expressing that, and a
     * reordering would look harmless.
     */
    @Test
    fun `Haiku wins over the generation check regardless of version`() {
        assertEquals(200_000, window("claude-haiku-5"))
        assertEquals(200_000, window("claude-haiku-4-5-20251001"))
    }

    /**
     * Proxy and Bedrock-style ids carry the family name as a path segment or a
     * region-prefixed namespace. The heuristic matches on `contains`, so these
     * resolve to the family rather than the generic default — which is the
     * right answer, because a relay serving Sonnet really does have Sonnet's
     * window.
     */
    @Test
    fun `prefixed and namespaced Claude ids still resolve to the family`() {
        assertEquals(1_000_000, window("anthropic/claude-sonnet-4-6"))
        assertEquals(1_000_000, window("openrouter/anthropic/claude-opus-4-8"))
        assertEquals(200_000, window("us.anthropic.claude-3-5-sonnet-20241022-v1:0"))
    }

    // ── Grok: the generation split ────────────────────────────────────────

    @Test
    fun `Grok 2 and 3 are the 131K generation`() {
        assertEquals(131_072, window("grok-2-1212"))
        assertEquals(131_072, window("grok-3-mini"))
        assertEquals(131_072, window("grok-3-mini-fast"))
    }

    @Test
    fun `Grok 4 and later floor at 256K`() {
        for (id in listOf(
            "grok-4.6", "grok-4.5", "grok-4.3", "grok-4-fast",
            "grok-4.20-0309-reasoning", "grok-code-fast-1", "xai/grok-4.1",
        )) {
            assertEquals("$id must floor at 256K", 256_000, window(id))
        }
    }

    /**
     * 33b028477 checked this explicitly and it is worth keeping: no earlier
     * branch (claude / gemini / gpt / o3 / o4 / codex / deepseek) may shadow a
     * Grok id, or the new branch would be dead code for exactly the ids it was
     * added for.
     */
    @Test
    fun `no earlier family branch shadows a Grok id`() {
        val grokIds = listOf(
            "grok-4.6", "grok-2-1212", "grok-3-mini", "grok-4-20-fast",
            "xai/grok-4.1", "grok-code-fast-1",
        )
        for (id in grokIds) {
            val w = window(id)
            assertTrue(
                "$id resolved to $w — a non-Grok branch matched first",
                w == 131_072 || w == 256_000,
            )
        }
    }

    // ── The other families, so a reorder cannot go unnoticed ───────────────

    @Test
    fun `Gemini is 1M except the 1_0 generation`() {
        assertEquals(1_000_000, window("gemini-3-pro-preview"))
        assertEquals(1_000_000, window("gemini-2.5-pro"))
        assertEquals(1_000_000, window("gemini-2.5-flash-lite"))
        assertEquals(32_000, window("gemini-1.0-pro"))
    }

    @Test
    fun `the OpenAI family keeps its per-generation values`() {
        assertEquals(16_000, window("gpt-3.5-turbo"))
        assertEquals(128_000, window("gpt-4o"))
        assertEquals(128_000, window("gpt-4-turbo"))
        assertEquals(400_000, window("gpt-5.6-terra"))
        assertEquals(8_000, window("gpt-4"))
        assertEquals(200_000, window("o3"))
        assertEquals(200_000, window("o4-mini"))
        // `codex-mini-latest` has no gpt-5 prefix, so it reaches the codex branch.
        assertEquals(200_000, window("codex-mini-latest"))
    }

    /**
     * `gpt-5.3-codex` matches BOTH the `gpt-5` branch (400K) and the `codex`
     * branch (200K), and the gpt-5 test comes first — so it resolves to 400K.
     *
     * That is the right answer and worth pinning as a decision rather than left
     * as an accident of ordering: the Codex variants are GPT-5.x models with the
     * GPT-5 window, and the 200K `codex` branch exists for the older
     * `codex-mini-latest` shape. Reordering the two branches to "group the codex
     * models together" would halve the window for every gpt-5.x-codex id, and
     * the only symptom would be premature compaction.
     */
    @Test
    fun `a gpt-5 codex id takes the gpt-5 window, not the legacy codex one`() {
        assertEquals(400_000, window("gpt-5.3-codex"))
        assertEquals(400_000, window("gpt-5.1-codex-max"))
        assertEquals(400_000, window("gpt-5-codex"))
    }

    /**
     * Branch order inside the OpenAI family is load-bearing in both directions:
     * `gpt-4o` and `gpt-4-turbo` must be tested before the bare `gpt-4` → 8K,
     * and `gpt-5` before it too. Getting this wrong halves or eightfolds a real
     * window with no other symptom.
     */
    @Test
    fun `gpt-4o and gpt-4-turbo are not caught by the bare gpt-4 branch`() {
        assertNotEquals(8_000, window("gpt-4o"))
        assertNotEquals(8_000, window("gpt-4o-mini"))
        assertNotEquals(8_000, window("gpt-4-turbo-2024-04-09"))
        // …while the bare id genuinely is the 8K one.
        assertEquals(8_000, window("gpt-4-0613"))
    }

    @Test
    fun `DeepSeek and everything unrecognised land on 128K`() {
        assertEquals(128_000, window("deepseek-v4"))
        assertEquals(128_000, window("deepseek-flash"))
        // The trailing default: deliberately a modern long-context guess rather
        // than 64K, so the group slider does not collapse to a single stop.
        assertEquals(128_000, window("some-relay/unknown-model-v9"))
        assertEquals(128_000, window(""))
    }

    // ── Precedence: a declared value always wins ──────────────────────────

    /**
     * The whole heuristic is a LAST resort. models.dev enrichment and the
     * built-in catalog both write `contextWindow`, and when they have it the
     * heuristic must not run at all — otherwise the relay-hosted grok-4 entries
     * that really are 128K would be inflated to 256K.
     */
    @Test
    fun `a declared context window beats the heuristic in both directions`() {
        // Smaller than the heuristic would guess (a capped relay).
        assertEquals(131_072, model("grok-4.6", ctx = 131_072).contextWindowTokens)
        // Larger than the heuristic would guess.
        assertEquals(2_000_000, model("grok-4.6", ctx = 2_000_000).contextWindowTokens)
        // And for Claude, where the pre-fix value was the bug.
        assertEquals(200_000, model("claude-sonnet-4-6", ctx = 200_000).contextWindowTokens)
    }

    /**
     * The `it > 0` guard. A models.dev row (or a hand-edited override) carrying
     * 0 must fall THROUGH to the heuristic — returning 0 would make
     * compactThreshold negative and compact on the first turn.
     */
    @Test
    fun `a zero or negative declared window falls through to the heuristic`() {
        assertEquals(1_000_000, model("claude-sonnet-5", ctx = 0).contextWindowTokens)
        assertEquals(256_000, model("grok-4.6", ctx = -1).contextWindowTokens)
    }

    /** Built-in Claude entries carry explicit values, independent of the id heuristic. */
    @Test
    fun `the built-in Anthropic catalog declares its windows explicitly`() {
        for (m in LLMModel.allAnthropic) {
            val declared = requireNotNull(m.contextWindow) {
                "${m.id} must declare a contextWindow rather than lean on the heuristic"
            }
            assertTrue("${m.id} declared $declared", declared > 0)
            assertEquals("${m.id} heuristic must agree", declared, m.contextWindowTokens)
        }
        assertEquals(200_000, LLMModel.claudeHaiku45.contextWindow)
    }

    /**
     * A user override is applied through ModelEntry, above the base model —
     * `contextWindowTokens` reads whatever `ModelEntry.model` resolved to, so
     * the override must win over both the declared value and the heuristic.
     */
    @Test
    fun `a user override wins over the catalog and the heuristic`() {
        val entry = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel.claudeSonnet5,           // declares 1M
            overrides = ModelOverrides(contextWindow = 300_000),
        )
        assertEquals(300_000, entry.model.contextWindowTokens)

        val heuristicOnly = ModelEntry(
            providerInstanceId = "inst",
            baseModel = model("grok-4.6"),                 // heuristic 256K
            overrides = ModelOverrides(contextWindow = 131_072),
        )
        assertEquals(131_072, heuristicOnly.model.contextWindowTokens)
    }

    // ── The slider's separate heuristic ───────────────────────────────────

    /**
     * `inferContextWindowTokens` feeds the group slider's "Unlimited" ceiling
     * and is intentionally a second implementation (it must not overwrite
     * source-of-truth metadata read by the loop). The values it returns for the
     * families both functions know must still AGREE, or the slider cannot reach
     * the window the loop is using.
     */
    @Test
    fun `the slider heuristic agrees with the loop for modern Claude and Gemini`() {
        for (id in listOf("claude-opus-4-8", "claude-sonnet-4-6", "gemini-2.5-pro", "gemini-3-pro-preview")) {
            val m = model(id)
            assertEquals(
                "$id: slider ${inferContextWindowTokens(m)} vs loop ${m.contextWindowTokens}",
                m.contextWindowTokens,
                inferContextWindowTokens(m),
            )
        }
    }

    @Test
    fun `the slider heuristic also prefers a declared window`() {
        assertEquals(777_000, inferContextWindowTokens(model("anything", ctx = 777_000)))
        // Zero falls through, same guard as the loop's.
        assertEquals(1_000_000, inferContextWindowTokens(model("claude-opus-4-8", ctx = 0)))
    }

    /**
     * Was a KNOWN GAP, now fixed: 33b028477 added the xAI branch to
     * `LLMModel.contextWindowTokens` but not to the slider heuristic, so an
     * uncatalogued `grok-4.6` got 128K there while the agent loop used 256K —
     * a group's limit could not be raised to the window the loop honours. Both
     * heuristics now carry the same branch with the same values.
     */
    @Test
    fun `the slider heuristic carries the Grok branch and agrees with the loop`() {
        for (id in listOf(
            "grok-4.6", "grok-4.5", "grok-4.3", "grok-4-fast",
            "grok-4.20-0309-reasoning", "grok-code-fast-1", "xai/grok-4.1",
            "grok-2-1212", "grok-3-mini", "grok-3-mini-fast",
        )) {
            val m = model(id)
            val slider = inferContextWindowTokens(m)
            val loop = m.contextWindowTokens
            assertEquals("$id: slider $slider vs loop $loop", loop, slider)
            // The slider must never exceed the loop's window — that is the
            // direction that WOULD be harmful (a limit above the real window).
            assertTrue("slider ceiling $slider exceeds the real window $loop", slider <= loop)
        }
        // The generation split is present on this side too, not just a blanket
        // 256K that happens to match grok-4.6.
        assertEquals(256_000, inferContextWindowTokens(model("grok-4.6")))
        assertEquals(131_072, inferContextWindowTokens(model("grok-3-mini")))
    }

    /**
     * The standing invariant, not another family list: for EVERY id below the
     * two heuristics must return the same number, and the slider must never
     * exceed the loop. Four OpenAI families used to disagree here — gpt-5 was
     * 128K on the slider against 400K in the loop (a 272K shortfall, the
     * largest of any family), o3/o4 and codex had no slider row at all and
     * collapsed to the trailing 128K default, and bare gpt-4 said 16K against
     * the loop's 8K, the one case where the slider sat ABOVE the real window
     * and could offer a limit the model rejects.
     *
     * The ids are grouped by family so a new branch on one side and not the
     * other fails with the offending id named. A model already carrying a
     * `contextWindow` never reaches either body (see the sibling test), so
     * this only governs ids no catalog has shipped yet.
     */
    @Test
    fun `both heuristics agree on every family they know`() {
        val ids = listOf(
            // Anthropic
            "claude-opus-4-8", "claude-sonnet-4-6", "claude-3-5-sonnet-20241022",
            "claude-haiku-4-5", "claude-2-1",
            // Google
            "gemini-2.5-pro", "gemini-3-pro-preview", "gemini-2.5-flash",
            // OpenAI — the four that used to diverge
            "gpt-5", "gpt-5.6-sol", "gpt-5.3-codex", "openai/gpt-5",
            "o3", "o3-mini", "o4-mini", "openai/o3",
            "codex-mini-latest",
            "gpt-4", "gpt-4-0613",
            // OpenAI — already agreeing, kept so a reorder cannot break them
            "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo",
            // Others
            "deepseek-v4", "grok-4.6", "grok-3-mini", "xai/grok-4.1",
            // No family: both must land on the same trailing default
            "llama-3-70b-instruct", "some-relay-model",
        )
        for (id in ids) {
            val m = model(id)
            val slider = inferContextWindowTokens(m)
            val loop = m.contextWindowTokens
            assertEquals("$id: slider $slider vs loop $loop", loop, slider)
            assertTrue(
                "$id: slider ceiling $slider exceeds the real window $loop",
                slider <= loop,
            )
        }
    }

    /**
     * Pins the two values the alignment turned on, so a revert to the old
     * numbers fails with the reason rather than only tripping the agreement
     * test above (which would also pass if BOTH sides regressed together).
     */
    @Test
    fun `the aligned OpenAI rows carry the loop's numbers`() {
        assertEquals(400_000, inferContextWindowTokens(model("gpt-5.6-sol")))
        assertEquals(200_000, inferContextWindowTokens(model("o4-mini")))
        assertEquals(200_000, inferContextWindowTokens(model("codex-mini-latest")))
        assertEquals(8_000, inferContextWindowTokens(model("gpt-4-0613")))
        // gpt-5 must still win over codex, as it does in the loop.
        assertEquals(400_000, inferContextWindowTokens(model("gpt-5.3-codex")))
    }

    /**
     * The slider's anchored matching (startsWith / "/" prefix) is deliberately
     * stricter than the loop's bare `contains`, so an id that merely EMBEDS a
     * marker mid-name is not claimed by that family here. That asymmetry is
     * intentional and outside the agreement rule above: the conservative
     * 128K default is the right answer for a relay's own invented name.
     */
    @Test
    fun `the slider does not claim a family from a mid-name marker`() {
        for (id in listOf("my-o3-tune", "relay-codex-proxy", "some-gpt-5-clone")) {
            assertEquals(
                "$id should fall through to the default on the slider side",
                128_000,
                inferContextWindowTokens(model(id)),
            )
        }
    }

    // ── Source-grep drift guard ───────────────────────────────────────────

    /**
     * The values above are the contract; this pins that they are still stated
     * in the one place the app reads, so a reintroduced "Claude → 200K" or a
     * deleted xAI branch fails here with the reason attached.
     */
    @Test
    fun `the heuristic source still carries both fixed branches`() {
        val src = com.openminis.app.ProductionSources.read("data/model/LLMModel.kt")
        val body = src.substringAfter("val contextWindowTokens: Int")
            .substringBefore("fun capabilityPromptFragment(")
        assertTrue("the xAI branch must exist", body.contains("""lid.contains("grok")"""))
        assertTrue("Grok 2/3 stay 131_072", body.contains("131_072"))
        assertTrue("modern Claude must be 1M", body.contains("1_000_000"))
        assertTrue(
            "the declared-value guard must reject non-positive values",
            body.contains("contextWindow?.let { if (it > 0) return it }"),
        )
    }

    /**
     * The same guard for the slider's copy. 33b028477 was applied to one of the
     * two heuristics only, which is the failure this pins: a family branch added
     * to the loop and forgotten here reads as a slider that cannot reach the
     * real window.
     */
    @Test
    fun `the slider heuristic source still carries the xAI branch`() {
        val src = com.openminis.app.ProductionSources.read("data/model/ModelContextWindowHeuristic.kt")
        assertTrue("the xAI branch must exist", src.contains("""idLower.startsWith("grok")"""))
        assertTrue("Grok 2/3 stay 131_072", src.contains("131_072"))
        assertTrue("Grok 4+ floors at 256_000", src.contains("256_000"))
    }
}
