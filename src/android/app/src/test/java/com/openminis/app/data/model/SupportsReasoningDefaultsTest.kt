package com.openminis.app.data.model

import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIModelsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M11][T119][T-android-claude-opus48-thinking-toggle] `supportsReasoning`
 * defaults for models models.dev has not catalogued yet.
 *
 * The gate everything hangs off is `== true`:
 *
 *     ChatViewModel.currentModelSupportsReasoning = currentModel?.supportsReasoning == true
 *
 * so `null` — "the catalog has not said" — behaves exactly like a stated `false`
 * and the Thinking pill is disabled. Two reports, same shape:
 *
 *   9938d0686  GPT-5.x / o-series registered with no explicit flag, so the pill
 *              stayed off for brand-new ids (gpt-5.5) that models.dev rarely has
 *              on release day, and `streamMessage(thinkingLevel = OFF)` then
 *              dropped reasoning_effort entirely — the high-effort setting
 *              looked broken while the provider plumbing was fine.
 *   f1b8e397f  Sow Sow 38845/38850: a DIRECT Anthropic instance on Opus 4.8 had
 *              no Deep Thinking toggle, because /v1/models returns no capability
 *              metadata at all and every id landed with null.
 *
 * Over-stamping is the opposite failure — a non-reasoning model advertising a
 * toggle that makes its requests 400 — so every family test below also pins the
 * ids that must stay null/false.
 */
class SupportsReasoningDefaultsTest {

    // ── OpenAI: the built-in catalog ──────────────────────────────────────

    @Test
    fun `GPT-5x and o-series built-ins declare reasoning explicitly`() {
        val byId = LLMModel.allOpenAI.associateBy { it.id }
        for (id in listOf(
            "gpt-5.5", "gpt-5.3-codex", "gpt-5.2-codex", "gpt-5.1-codex-max",
            "gpt-5.2", "o3", "o4-mini", "codex-mini-latest",
        )) {
            val m = requireNotNull(byId[id]) { "$id missing from allOpenAI" }
            assertEquals("$id must default to supportsReasoning=true", true, m.supportsReasoning)
        }
    }

    /**
     * The non-reasoning half of the same catalog. If these ever became `true`,
     * the composer would offer an effort picker for a model that rejects the
     * parameter.
     */
    @Test
    fun `GPT-4o class built-ins do NOT claim reasoning`() {
        val byId = LLMModel.allOpenAI.associateBy { it.id }
        assertNull(byId["gpt-4o"]!!.supportsReasoning)
        assertNull(byId["gpt-4o-mini"]!!.supportsReasoning)
    }

    /**
     * The Codex-OAuth static list is a separate copy of the catalog (OAuth
     * tokens cannot call /v1/models), and on that auth path `/v1/responses`
     * REQUIRES the reasoning object — so a null here is not merely a disabled
     * pill, it is a request shape the endpoint rejects. Every entry must be
     * stamped.
     */
    @Test
    fun `every chat model on the Codex OAuth path claims reasoning`() {
        val models = OpenAIModelsApi.fetchModelsOAuth()
        assertTrue("the Codex OAuth list must not be empty", models.isNotEmpty())
        // The image SKUs are appended after the chat list and route through the
        // Codex image_generation tool rather than /v1/responses, so a reasoning
        // flag would be meaningless on them — excluded by output modality rather
        // than by id, so a fourth image model needs no change here.
        val chat = models.filter { it.outputModalities?.contains("image") != true }
        assertTrue("expected chat SKUs in the list", chat.isNotEmpty())
        for (m in chat) {
            assertEquals(
                "${m.id} on the Codex OAuth path must declare supportsReasoning=true — " +
                    "/v1/responses REQUIRES the reasoning object on this auth path",
                true,
                m.supportsReasoning,
            )
        }
        assertTrue(
            "gpt-6-astra is the current flagship on this path",
            chat.any { it.id == "gpt-6-astra" },
        )
    }

    /**
     * The other side of the same list: an image-generation SKU must NOT claim
     * reasoning. Stamping it would put a Thinking pill on a model whose request
     * is a `{type:image_generation}` tool call with nowhere to send an effort.
     */
    @Test
    fun `the Codex image SKUs do not claim reasoning`() {
        for (m in OpenAIModelsApi.codexImageModels()) {
            assertNull("${m.id} must not claim reasoning", m.supportsReasoning)
            assertEquals(listOf("image"), m.outputModalities)
        }
    }

    // ── OpenAI: the /v1/models parse-time prefill ──────────────────────────
    //
    // `OpenAIModelsApi.fetchModels` stamps `supportsReasoning = true` for known
    // reasoning families before ModelsDevApi.enrichModels runs, so the pill is
    // live on the first paint rather than after the catalog catches up. The
    // predicate itself is inline in the parse loop (needs a live HTTP response
    // to reach), so it is restated here and guarded against drift below.

    /** Restated from OpenAIModelsApi.fetchModels — see the drift guard. */
    private fun knownReasoning(id: String): Boolean {
        val idLower = id.lowercase()
        return idLower.startsWith("gpt-5") ||
            idLower.startsWith("gpt-6") ||
            idLower.startsWith("o1") ||
            idLower.startsWith("o3") ||
            idLower.startsWith("o4") ||
            idLower.contains("codex")
    }

    @Test
    fun `the parse-time prefill covers the reasoning families`() {
        for (id in listOf(
            "gpt-5", "gpt-5.5", "gpt-5.6-terra", "gpt-5.4-mini", "GPT-5.2",
            "o1", "o1-mini", "o3", "o3-mini", "o4-mini",
            "codex-mini-latest", "gpt-5.3-codex", "some-relay/codex-max",
        )) {
            assertTrue("$id should be prefilled", knownReasoning(id))
        }
    }

    @Test
    fun `the prefill is anchored, so unrelated ids are not swept in`() {
        // `startsWith`, not `contains`: an id merely MENTIONING o3/o4 later on
        // (or a model whose name begins with "o" but is not the o-series) must
        // not be stamped. This is the over-granting direction.
        for (id in listOf(
            "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo",
            "olmo-2-13b", "omni-moderation-latest", "orca-mini",
            "text-embedding-3-large", "whisper-large-v3", "claude-sonnet-4-6",
        )) {
            assertFalse("$id must not be prefilled", knownReasoning(id))
        }
    }

    /**
     * Was a KNOWN GAP, now fixed: the predicate anchored `gpt-5` only, so
     * `gpt-6-astra` fell through. On the Codex OAuth path that was harmless (the
     * static list stamps it explicitly, asserted above), but an API-key instance
     * discovering gpt-6-astra through /v1/models got null until models.dev
     * catalogued it — the exact shape of 9938d0686 one generation later. The
     * predicate now anchors the current reasoning generation as well.
     */
    @Test
    fun `the prefill predicate anchors gpt-6 as well as gpt-5`() {
        for (id in listOf("gpt-6-astra", "gpt-6", "gpt-6.1-mini", "GPT-6-ASTRA")) {
            assertTrue("$id should be prefilled", knownReasoning(id))
        }
        // gpt-5 and the rest of the families must be untouched by the addition.
        assertTrue(knownReasoning("gpt-5.5"))
        assertTrue(knownReasoning("o3-mini"))
        // Still anchored, not a bare `contains`: an unrelated id merely
        // mentioning the generation must not be swept in.
        assertFalse(knownReasoning("relay-for-gpt-6"))
        assertFalse(knownReasoning("gpt-4o"))

        // The OAuth path's explicit stamp is unchanged.
        assertEquals(
            true,
            OpenAIModelsApi.fetchModelsOAuth().first { it.id == "gpt-6-astra" }.supportsReasoning,
        )
    }

    /** Guards the restated predicate against drifting from the source. */
    @Test
    fun `the restated prefill predicate matches the source`() {
        val src = com.openminis.app.ProductionSources.read("provider/openai/OpenAIModelsApi.kt")
        assertTrue(src.contains("""idLower.startsWith("gpt-5")"""))
        assertTrue(src.contains("""idLower.startsWith("gpt-6")"""))
        assertTrue(src.contains("""idLower.startsWith("o1")"""))
        assertTrue(src.contains("""idLower.startsWith("o3")"""))
        assertTrue(src.contains("""idLower.startsWith("o4")"""))
        assertTrue(src.contains("""idLower.contains("codex")"""))
        assertTrue(
            "the parse must stamp true / null, never false",
            src.contains("supportsReasoning = if (knownReasoning) true else null"),
        )
    }

    // ── Anthropic: direct instances ───────────────────────────────────────

    /**
     * f1b8e397f. `/v1/models` carries no capability metadata whatsoever, so the
     * parse stamps the flag from the id via `AnthropicProvider.supportsThinking`
     * — the same version predicate that already decides the wire format. Both
     * OAuth and API-key direct instances flow through it.
     */
    @Test
    fun `direct Claude reasoning models are stamped from the id`() {
        for (id in listOf(
            "claude-fable-5-1", "claude-fable-5", "claude-opus-5", "claude-opus-4-8",
            "claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-4-5",
            "claude-3-7-sonnet-20250219",
        )) {
            assertTrue("$id must support thinking", AnthropicProvider.supportsThinking(id))
        }
    }

    @Test
    fun `pre-3_7 Claude and non-Claude ids are not stamped`() {
        // The 3.7 boundary is where extended thinking arrived; below it the
        // request would carry a `thinking` block the API rejects.
        assertFalse(AnthropicProvider.supportsThinking("claude-3-5-sonnet-20241022"))
        assertFalse(AnthropicProvider.supportsThinking("claude-3-opus-20240229"))
        assertFalse(AnthropicProvider.supportsThinking("claude-2.1"))
        // Not Claude at all — an Anthropic-compatible relay serving something
        // else must not inherit the stamp.
        assertFalse(AnthropicProvider.supportsThinking("gpt-4o"))
        assertFalse(AnthropicProvider.supportsThinking("llama-4-maverick"))
        assertFalse(AnthropicProvider.supportsThinking(""))
    }

    @Test
    fun `the built-in Anthropic catalog declares reasoning on every entry`() {
        for (m in LLMModel.allAnthropic) {
            assertEquals("${m.id} must declare supportsReasoning=true", true, m.supportsReasoning)
        }
    }

    /**
     * The two Anthropic sources must not disagree: an id the wire format treats
     * as a thinking model has to be an id the catalog stamps, or the toggle and
     * the request shape describe different models.
     */
    @Test
    fun `the catalog stamp agrees with the wire-format predicate`() {
        for (m in LLMModel.allAnthropic) {
            assertEquals(
                "${m.id}: catalog says ${m.supportsReasoning}, wire says " +
                    AnthropicProvider.supportsThinking(m.id),
                m.supportsReasoning == true,
                AnthropicProvider.supportsThinking(m.id),
            )
        }
    }

    @Test
    fun `the Anthropic parse still stamps from supportsThinking`() {
        val src = com.openminis.app.ProductionSources.read("provider/anthropic/AnthropicModelsApi.kt")
        assertTrue(
            "the id-driven stamp must survive",
            src.contains("if (AnthropicProvider.supportsThinking(id)) true else null"),
        )
    }

    // ── xAI ───────────────────────────────────────────────────────────────

    @Test
    fun `xAI reasoning variants are stamped and the non-reasoning ones are not`() {
        val byId = LLMModel.allXAI.associateBy { it.id }
        for (id in listOf("grok-4.6", "grok-4.5", "grok-4.3", "grok-4-fast", "grok-code-fast-1")) {
            assertEquals("$id must declare reasoning", true, byId[id]?.supportsReasoning)
        }
        // The explicitly-non-reasoning siblings — xAI ships both spellings and
        // stamping the wrong one sends reasoning_effort to a model that refuses it.
        assertNull(byId["grok-4-fast-non-reasoning"]?.supportsReasoning)
        assertNull(byId["grok-4.20-0309-non-reasoning"]?.supportsReasoning)
    }

    // ── The override layer ────────────────────────────────────────────────

    /**
     * 6960e5b9c. A user's explicit answer must survive above the catalog in BOTH
     * directions — turning it off on a stamped model, and on for a relay the
     * catalog knows nothing about. The off direction is the one that regressed:
     * a default of `true` that ignored the override would keep sending a
     * parameter the user's endpoint rejects.
     */
    @Test
    fun `a user override wins over the catalog default`() {
        val off = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel.claudeSonnet5,                    // catalog: true
            overrides = ModelOverrides(supportsReasoning = false),
        )
        assertEquals(false, off.model.supportsReasoning)

        val on = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel("relay/unknown-r1", "Unknown R1", "Custom"),  // catalog: null
            overrides = ModelOverrides(supportsReasoning = true),
        )
        assertEquals(true, on.model.supportsReasoning)
    }

    /** No override means inherit verbatim — including inheriting `null`. */
    @Test
    fun `an absent override inherits the base model, null included`() {
        val stamped = ModelEntry("inst", LLMModel.claudeSonnet5)
        assertEquals(true, stamped.model.supportsReasoning)

        val unknown = ModelEntry("inst", LLMModel("relay/x", "X", "Custom"))
        assertNull(unknown.model.supportsReasoning)
    }

    /**
     * `supportsReasoning = false` is a REAL override, not an absent one. If
     * `isEmpty` ignored it the overrides object would be dropped on export and
     * the next refresh would restore the catalog's `true` — the user's answer
     * silently reverting.
     */
    @Test
    fun `an explicit false is not mistaken for an empty override`() {
        assertFalse(ModelOverrides(supportsReasoning = false).isEmpty)
        assertFalse(ModelOverrides(supportsReasoning = true).isEmpty)
        assertTrue(ModelOverrides().isEmpty)
    }

    /**
     * The gate is `== true`, which is the reason null and false behave alike.
     * Pinned explicitly so the three-state field is never "simplified" to a
     * Boolean — the distinction is what lets enrichment fill in an unknown
     * without overwriting a user's deliberate false.
     */
    @Test
    fun `null and false are distinct states even though the gate treats them alike`() {
        val unknown = LLMModel("a", "A", "P", supportsReasoning = null)
        val stated = LLMModel("b", "B", "P", supportsReasoning = false)
        assertNull(unknown.supportsReasoning)
        assertEquals(false, stated.supportsReasoning)
        assertFalse(unknown.supportsReasoning == true)
        assertFalse(stated.supportsReasoning == true)
        assertFalse("the two must remain distinguishable", unknown == stated)
    }
}
