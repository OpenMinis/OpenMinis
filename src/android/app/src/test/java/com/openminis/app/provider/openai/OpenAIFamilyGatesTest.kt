package com.openminis.app.provider.openai

import com.openminis.app.ProductionSources
import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M19 / M20] The per-family GATES on the OpenAI-compatible request body: which
 * ids and which endpoints a given field is allowed to reach.
 *
 * Every row here is a field that one vendor requires and another rejects, so
 * each is asserted BOTH present where it belongs and absent where it does not —
 * an over-broad gate is a 400 for someone else, which is how each of these was
 * reported in the first place.
 *
 * Pins:
 *  • #35 / a5a0de20d / 8db455fff / 20bd0367b / bd68bf809 — `thinking_budget`
 *    must be STRICTLY below `max_completion_tokens`.
 *  • 672fc4de9 / 251657006 / 8b72080da — the DashScope dual-send is scoped to
 *    DashScope; a third-party gateway serving the same qwen id must not get it.
 *  • fb671083 / 838ba929 — Fast Mode's `service_tier: "priority"` is gated to
 *    gpt-family ids on the Responses path.
 *  • GH#191 — the OpenRouter `anthropic/` prefix routes Anthropic-shaped quirks.
 *  • The `startsWith("o")` reasoning predicate, which is deliberately BROADER on
 *    Android than on iOS (see the cross-check at the bottom).
 *
 * Seam: the production body builders (`buildRequestBody` /
 * `buildResponsesAPIBody`) are `internal` and documented as directly callable
 * from unit tests, which is the honest place to read a field-placement decision
 * — going through MockWebServer would add a network dependency to a question
 * that is pure JSON construction.
 */
class OpenAIFamilyGatesTest {

    @After
    fun tearDown() {
        // Process-wide singleton; leaving it on would leak into any other test
        // that builds a request body.
        FastModePrefs.setCachedEnabledForTest(false)
    }

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "Test",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
    )

    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))

    private fun chat(
        m: LLMModel,
        base: String = "https://api.openai.com/v1",
        level: ThinkingLevel = ThinkingLevel.HIGH,
        maxTokens: Int = 4096,
        temperature: Double? = null,
    ): JSONObject = OpenAIProvider(apiKey = "k", model = m, basePath = base).buildRequestBody(
        messages = history,
        systemPrompt = null,
        maxTokens = maxTokens,
        stream = false,
        temperature = temperature,
        imageParts = emptyList(),
        tools = emptyList(),
        thinkingLevel = level,
    )

    private fun responses(
        m: LLMModel,
        base: String = "https://api.openai.com/v1",
        maxTokens: Int = 4096,
    ): JSONObject = OpenAIProvider(
        apiKey = "k", model = m, basePath = base, useResponsesAPI = true,
    ).buildResponsesAPIBody(
        messages = history,
        systemPrompt = null,
        maxTokens = maxTokens,
        stream = false,
        imageParts = emptyList(),
    )

    // ═══════════════════════ M19a: thinking_budget < max_completion_tokens (#35)

    /**
     * Qwen rejects a `thinking_budget` that is not STRICTLY below the output
     * cap, so the two fields must be read together — the budget is carved out of
     * the same allowance as the reply. GH#35 was "thinking on, every request
     * 400s"; the budget was landing at or above the cap for small caps.
     *
     * Asserted as an INEQUALITY across a sweep of caps rather than as fixed
     * numbers, because the ladder's absolute values are tuning and may change
     * while the inequality may not.
     */
    @Test
    fun `qwen thinking_budget stays strictly below the output cap at every level`() {
        val m = model("qwen3-32b")
        for (maxTokens in listOf(2, 64, 1024, 4096, 8192, 16384, 65536, 200_000)) {
            for (level in listOf(
                ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH,
                ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
            )) {
                val b = chat(m, base = "https://dashscope.aliyuncs.com/compatible-mode/v1", level = level, maxTokens = maxTokens)
                val cap = b.getInt("max_completion_tokens")
                assertEquals("the cap must be the one the caller asked for", maxTokens, cap)
                if (!b.has("thinking_budget")) continue
                val budget = b.getInt("thinking_budget")
                assertTrue(
                    "maxTokens=$maxTokens $level: thinking_budget=$budget must be > 0",
                    budget > 0,
                )
                assertTrue(
                    "maxTokens=$maxTokens $level: thinking_budget=$budget must be STRICTLY below cap=$cap (#35)",
                    budget < cap,
                )
            }
        }
    }

    /**
     * The pathological cap: when there is no room for a budget at all the field
     * is DROPPED rather than sent as 0 or as a negative. `maxTokens < 2` leaves
     * nothing to carve out, and a 0 budget is itself a 400 on this vendor.
     */
    @Test
    fun `qwen drops the budget entirely when the cap leaves no room`() {
        val b = chat(
            model("qwen3-32b"),
            base = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            maxTokens = 1,
        )
        assertTrue("the dual-send switch is still on", b.getBoolean("enable_thinking"))
        assertFalse("no room → omit the budget, never send 0: $b", b.has("thinking_budget"))
        assertFalse(
            "…on the extra_body copy too: $b",
            b.getJSONObject("extra_body").has("thinking_budget"),
        )
    }

    /**
     * The budget appears in BOTH places or neither. DashScope reads it from
     * `extra_body` on some deployments and from the root on others, so the
     * dual-send is the point — and the two copies disagreeing is worse than
     * either alone, because the request then succeeds with a budget the user did
     * not ask for.
     */
    @Test
    fun `the root and extra_body copies of the budget always agree`() {
        for (maxTokens in listOf(1, 2, 1024, 65536)) {
            for (level in listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX)) {
                val b = chat(
                    model("qwen3-32b"),
                    base = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    level = level,
                    maxTokens = maxTokens,
                )
                val extra = b.getJSONObject("extra_body")
                assertEquals(
                    "maxTokens=$maxTokens $level: enable_thinking must match across both copies",
                    b.getBoolean("enable_thinking"),
                    extra.getBoolean("enable_thinking"),
                )
                assertEquals(
                    "maxTokens=$maxTokens $level: the budget key must be present in both or neither: $b",
                    b.has("thinking_budget"),
                    extra.has("thinking_budget"),
                )
                if (b.has("thinking_budget")) {
                    assertEquals(
                        "maxTokens=$maxTokens $level: the two copies must carry the SAME budget",
                        b.getInt("thinking_budget"),
                        extra.getInt("thinking_budget"),
                    )
                }
            }
        }
    }

    // ═══════════════════ M19b: the dual-send is scoped to DashScope (672fc4de9)

    /**
     * `enable_thinking` is DashScope's own extension. A gateway reselling the
     * same qwen id controls thinking by its own mechanism and a strict schema
     * rejects the unknown key outright, so the dual-send must not follow the id
     * off DashScope.
     *
     * OpenRouter is the load-bearing row: it nests the effort under
     * `reasoning:{}` instead, so this is a mechanism SWAP, not just a missing
     * field — the request succeeds either way and only the thinking behaviour
     * silently differs.
     */
    @Test
    fun `a qwen id on OpenRouter nests the effort instead of dual-sending`() {
        val b = chat(model("qwen3-32b"), base = "https://openrouter.ai/api/v1")
        assertFalse("DashScope's extension must not reach OpenRouter: $b", b.has("enable_thinking"))
        assertFalse(b.has("thinking_budget"))
        assertEquals("high", b.getJSONObject("reasoning").getString("effort"))
    }

    /**
     * The mirror of the ordering rule: on a UNIFIED gateway the qwen rule still
     * wins (it is registered above the gateway rule), so the dual-send DOES
     * apply there. Kept beside the OpenRouter row because the two look alike and
     * resolve differently — OpenRouter is registered ABOVE the qwen rule and Ark
     * below it, which is the entire difference.
     */
    @Test
    fun `a qwen id on a unified gateway keeps the dual-send`() {
        val b = chat(model("qwen3-32b"), base = "https://ark.cn-beijing.volces.com/api/v3")
        assertTrue("the qwen rule outranks the gateway rule: $b", b.getBoolean("enable_thinking"))
        assertFalse(b.has("reasoning_effort"))
    }

    /**
     * And the endpoint half: DashScope claims a NON-qwen id too, because the
     * mechanism belongs to the endpoint as much as to the family. Both halves of
     * the original `contains("qwen") || isDashScope` predicate survive.
     */
    @Test
    fun `DashScope dual-sends for a non-qwen id as well`() {
        val b = chat(model("some-other-model"), base = "https://dashscope.aliyuncs.com/compatible-mode/v1")
        assertTrue(b.getBoolean("enable_thinking"))
        assertFalse("never the generic root tier on this mechanism: $b", b.has("reasoning_effort"))
    }

    /**
     * A plain custom relay serving a qwen-named id gets neither: the id matches
     * the qwen rule, so it dual-sends. Recording the ACTUAL behaviour, which is
     * deliberate — the pre-refactor chain matched the id without an endpoint
     * guard, and Phase 1 preserved that verbatim. The field-reported failure was
     * the reverse direction (a gateway losing its own mechanism), so the id match
     * stays.
     */
    @Test
    fun `a qwen id on an unrecognised relay keeps the id-matched mechanism`() {
        val b = chat(model("qwen3-32b"), base = "https://relay.example.com/v1")
        assertTrue(
            "the qwen rule is an ID match, not an endpoint match — preserved from the pre-refactor chain: $b",
            b.getBoolean("enable_thinking"),
        )
    }

    // ═════════════════════ M20a: Fast Mode is gpt-family only (fb671083)

    /**
     * Fast Mode injects `service_tier: "priority"` on the Responses path, gated
     * to gpt-family ids. The value is not the UI name: verified against
     * openai/codex source (`codex-rs/protocol config_types.rs`),
     * `ServiceTier::Fast` sends "priority".
     *
     * The gate matters because a strict OpenAI-compatible relay 400s on an
     * unknown key, and this builder serves relays too.
     */
    @Test
    fun `Fast Mode carries priority for gpt-family ids on the Responses path`() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (id in listOf("gpt-5.3", "gpt-5.6-sol", "gpt-6-astra", "gpt-4o", "GPT-5.3")) {
            val b = responses(model(id))
            assertEquals(
                "$id is gpt-family and must carry the fast tier: $b",
                "priority",
                b.optString("service_tier", null),
            )
        }
    }

    /** Off by default, and off means the key is ABSENT — not `"default"`. */
    @Test
    fun `Fast Mode off omits the key rather than stating a default`() {
        FastModePrefs.setCachedEnabledForTest(false)
        val b = responses(model("gpt-5.3"))
        assertFalse(
            "omission keeps the body byte-identical to before the feature existed: $b",
            b.has("service_tier"),
        )
    }

    /** A non-gpt id never carries it, however the toggle is set. */
    @Test
    fun `a non-gpt id never carries the fast tier`() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (id in listOf("o3-mini", "claude-opus-5", "deepseek-v4", "qwen3-32b", "grok-4-20")) {
            val b = responses(model(id))
            assertFalse("$id is not gpt-family and must not carry it: $b", b.has("service_tier"))
        }
    }

    /**
     * The gate is on the RESPONSES builder. The Chat builder's `service_tier` is
     * a different feature entirely (xAI Priority Processing, keyed on a
     * per-provider capability flag), so a gpt id on the Chat path must stay
     * clean — otherwise the two features would silently merge.
     */
    @Test
    fun `the gpt fast-mode gate does not leak onto the Chat path`() {
        FastModePrefs.setCachedEnabledForTest(true)
        val b = chat(model("gpt-5.3"))
        assertFalse(
            "the Chat path's tier belongs to xAI Priority Processing, not Fast Mode: $b",
            b.has("service_tier"),
        )
    }

    /**
     * Read at request-BUILD time, not at provider construction: a flip must
     * apply to the very next request of an ongoing session, including
     * offload / title-gen calls that never pass through ChatViewModel.
     */
    @Test
    fun `flipping Fast Mode applies to the next request on the same provider`() {
        val provider = OpenAIProvider(
            apiKey = "k", model = model("gpt-5.3"),
            basePath = "https://api.openai.com/v1", useResponsesAPI = true,
        )
        fun build() = provider.buildResponsesAPIBody(history, null, 4096, false, emptyList())

        FastModePrefs.setCachedEnabledForTest(false)
        assertFalse(build().has("service_tier"))
        FastModePrefs.setCachedEnabledForTest(true)
        assertEquals("priority", build().optString("service_tier", null))
        FastModePrefs.setCachedEnabledForTest(false)
        assertFalse("and back off again", build().has("service_tier"))
    }

    // ══════════════════ M20b: the OpenRouter anthropic/ prefix (GH#191)

    /**
     * OpenRouter passes `cache_control` through to Anthropic but never injects
     * it, so without this field a Claude request caches NOTHING — the reporter
     * measured `cache_read_input_tokens` pinned at 0 and a 3-6x cost overrun.
     * Unlike the OpenAI / Grok / Moonshot models on the same gateway, Claude
     * needs an explicit breakpoint.
     *
     * Two conditions, and BOTH are required: the host must be OpenRouter and the
     * id must carry OpenRouter's `anthropic/` namespace.
     */
    @Test
    fun `an anthropic-prefixed id on OpenRouter opts into prompt caching`() {
        for (id in listOf("anthropic/claude-sonnet-4.5", "anthropic/claude-opus-5", "Anthropic/Claude-Opus-5")) {
            val b = chat(model(id), base = "https://openrouter.ai/api/v1")
            assertEquals(
                "$id must carry an automatic cache breakpoint: $b",
                "ephemeral",
                b.getJSONObject("cache_control").getString("type"),
            )
        }
    }

    /** Every other model on the same gateway keeps a byte-identical body. */
    @Test
    fun `other models on OpenRouter are unchanged`() {
        for (id in listOf("openai/gpt-5.3", "google/gemini-3-pro", "qwen3-32b", "moonshotai/kimi-k2")) {
            val b = chat(model(id), base = "https://openrouter.ai/api/v1")
            assertFalse("$id must not get cache_control: $b", b.has("cache_control"))
        }
    }

    /**
     * And the same `anthropic/` id OFF OpenRouter is unchanged: the gate is
     * host-matched, never a compat flag. Keying it on a compat flag would have
     * leaked the field into Mistral requests, which set the same flag for an
     * unrelated reason (the legacy `max_tokens` body shape).
     */
    @Test
    fun `an anthropic-prefixed id off OpenRouter gets no cache_control`() {
        for (base in listOf("https://api.openai.com/v1", "https://relay.example.com/v1")) {
            val b = chat(model("anthropic/claude-opus-5"), base = base)
            assertFalse("$base must not inject the field: $b", b.has("cache_control"))
        }
    }

    // ═══════════════════════ M20c: max_completion_tokens vs max_tokens

    /**
     * OpenRouter needs the LEGACY `max_tokens`; everyone else takes
     * `max_completion_tokens`. Exactly one of the two, always — sending both
     * makes the effective cap ambiguous, and sending neither lets a relay's tiny
     * default truncate the reply.
     */
    @Test
    fun `exactly one output-cap field is sent, chosen by gateway`() {
        val onOpenRouter = chat(model("gpt-5.3"), base = "https://openrouter.ai/api/v1", maxTokens = 1234)
        assertEquals("OpenRouter takes the legacy spelling", 1234, onOpenRouter.getInt("max_tokens"))
        assertFalse("…and only that one: $onOpenRouter", onOpenRouter.has("max_completion_tokens"))

        for (base in listOf("https://api.openai.com/v1", "https://relay.example.com/v1", "https://api.x.ai/v1")) {
            val b = chat(model("gpt-5.3"), base = base, maxTokens = 1234)
            assertEquals("$base takes the modern spelling", 1234, b.getInt("max_completion_tokens"))
            assertFalse("…and only that one: $b", b.has("max_tokens"))
        }
    }

    /** The Responses path has its own spelling again, and states the cap. */
    @Test
    fun `the Responses path states the cap as max_output_tokens`() {
        val b = responses(model("gpt-5.3"), maxTokens = 4321)
        assertEquals(4321, b.getInt("max_output_tokens"))
        assertFalse(b.has("max_completion_tokens"))
        assertFalse(b.has("max_tokens"))
    }

    // ═══════════════════════ M20d: temperature placement

    /**
     * `temperature` is written only when the CALLER supplies one — the builder
     * does not invent a default. That is the layering this file pins: the
     * decision "this model rejects temperature" belongs to the caller (for
     * Anthropic it is `modelRejectsTemperature`; for reasoning models the chat
     * layer passes null), and the builder must not second-guess it by adding a
     * value nobody asked for.
     */
    @Test
    fun `temperature is written only when the caller supplies one`() {
        assertFalse(
            "no temperature from the caller → no field",
            chat(model("gpt-5.3"), temperature = null).has("temperature"),
        )
        assertEquals(
            0.7,
            chat(model("gpt-4o"), temperature = 0.7).getDouble("temperature"),
            1e-9,
        )
        assertEquals(
            "an explicit 0.0 is a real request for determinism, not 'unset'",
            0.0,
            chat(model("gpt-4o"), temperature = 0.0).getDouble("temperature"),
            1e-9,
        )
    }

    /**
     * The Responses builder takes no temperature at all — no caller supplies one
     * on that path — so the field can never appear there. Pinned because adding
     * it would be a silent behaviour change for reasoning models, which reject
     * it.
     */
    @Test
    fun `the Responses path never carries temperature`() {
        assertFalse(responses(model("gpt-5.3")).has("temperature"))
        assertFalse(responses(model("o3-mini")).has("temperature"))
    }

    // ══════════════ M20e: the reasoning-family predicate, and its breadth

    /**
     * The OpenAI-native reasoning predicate on Android is
     * `startsWith("o") || startsWith("gpt-5")`. The real o-series ids are the
     * cases it exists for.
     */
    @Test
    fun `the o-series and gpt-5 families take a root reasoning_effort`() {
        for (id in listOf("o1", "o1-mini", "o3", "o3-mini", "o4-mini", "gpt-5.3", "gpt-5.6-sol")) {
            val b = chat(model(id))
            assertEquals("$id must take a root tier: $b", "high", b.optString("reasoning_effort", null))
        }
    }

    /**
     * The OpenAI-native branch sends its tier UNCONDITIONALLY — it does not
     * consult the model's declared set. That is what distinguishes it from the
     * generic branch, where an undeclared tier is clamped down, so the two are
     * asserted side by side on the same declaration.
     */
    @Test
    fun `the native branch ignores an incomplete declaration while the generic branch clamps`() {
        val declared = listOf("low", "medium")
        assertEquals(
            "native: the tier goes out as asked",
            "high",
            chat(model("gpt-5.3", reasoningEffortValues = declared)).optString("reasoning_effort", null),
        )
        assertEquals(
            "generic: an undeclared tier is snapped down to the declared top",
            "medium",
            chat(model("some-relay-reasoner", reasoningEffortValues = declared)).optString("reasoning_effort", null),
        )
    }

    /**
     * CROSS-PLATFORM CHECK, and the reason this test exists rather than a
     * comment. iOS narrows the same predicate to an explicit prefix set
     * (o1/o3/o4/gpt-5/gpt-4); Android keeps a bare `startsWith("o")`, which is
     * DELIBERATELY broader — the Phase 1 refactor preserved it verbatim rather
     * than narrowing it, on the grounds that narrowing changes behaviour for any
     * id beginning with "o" and that phase must not.
     *
     * So the breadth is a documented choice, and this test's job is to make the
     * blast radius visible rather than to assert it is empty. Each id below
     * begins with "o" and is NOT an o-series reasoning model: it is routed as
     * one anyway, which means the tier goes out unclamped and the model's own
     * declaration is ignored.
     *
     * ⚠️ printed, not failed — the fix is a production edit this test layer may
     * not make, and the narrowing is exactly what Phase 1 declined to do.
     */
    @Test
    fun `KNOWN GAP - the broad startsWith o predicate routes non-o-series ids as native`() {
        val notReasoningModels = listOf("olmo-2-13b", "omni-x-7b", "openchat-3.5", "orca-2-13b")
        val misrouted = mutableListOf<String>()
        for (id in notReasoningModels) {
            // Declare a narrow set: the generic branch would clamp HIGH down to
            // "low", the native branch sends "high". The emitted value therefore
            // names which branch claimed the id.
            val b = chat(model(id, reasoningEffortValues = listOf("low")))
            if (b.optString("reasoning_effort", null) == "high") misrouted += id
        }
        if (misrouted.isNotEmpty()) {
            println(
                "⚠️ KNOWN GAP [M20]: Android's OpenAI-native predicate is a bare startsWith(\"o\"), so " +
                    "these non-o-series ids are routed as OpenAI reasoning models and their declared " +
                    "effort set is ignored: $misrouted. iOS narrows the same predicate to " +
                    "o1/o3/o4/gpt-5/gpt-4. DELIBERATE on this side — ThinkingRuleResolver.kt ~190 " +
                    "records that the broad prefix is preserved verbatim because narrowing it would " +
                    "change behaviour for every id starting with \"o\" and Phase 1 must not. " +
                    "Consequence: an undeclared tier reaches such a backend unclamped. Expected " +
                    "behaviour if ever narrowed: these ids fall to the generic branch and clamp to " +
                    "\"low\". Recorded, not fixed.",
            )
        } else {
            println("✅ [M20] the OpenAI-native predicate has been narrowed; tighten this test to assert it.")
        }

        // What IS asserted: the breadth is bounded to the leading-"o" rule and
        // has not silently grown. An id that does NOT begin with "o" or "gpt-5"
        // must still take the generic, clamping branch.
        for (id in listOf("some-relay-reasoner", "gpt-4o", "llama-3.1-70b", "mistral-large")) {
            assertEquals(
                "$id must take the generic branch and clamp",
                "low",
                chat(model(id, reasoningEffortValues = listOf("low"))).optString("reasoning_effort", null),
            )
        }
    }

    /**
     * The same predicate appears TWICE — once as a rule scope (`ModelPattern`)
     * and once inside the emitter as `isOpenAINative` — and the two must agree,
     * or a rule matches while the emitter takes the other path. Asserted through
     * the resolver directly, since only the emitter's copy is observable there.
     */
    @Test
    fun `the rule scope and the emitter predicate agree on what is native`() {
        fun nativeByEmitter(id: String): Boolean {
            // A declared set the generic branch would clamp; the native branch
            // ignores it. The answer therefore identifies the branch taken.
            val b = JSONObject()
            ThinkingRuleResolver.apply(
                b,
                ThinkingResolveContext(
                    modelId = id,
                    supportsReasoning = true,
                    declaredEffortValues = listOf("low"),
                    level = ThinkingLevel.HIGH,
                    maxTokens = 4096,
                    isOpenRouter = false,
                    usesUnifiedReasoningEffort = false,
                    isMistral = false,
                    isDashScope = false,
                    offEffort = null,
                ),
            )
            return b.optString("reasoning_effort", null) == "high"
        }
        for (id in listOf("o3-mini", "gpt-5.3", "olmo-2-13b")) {
            assertTrue(
                "$id matches an openai-native rule scope, so the emitter must agree it is native",
                nativeByEmitter(id),
            )
        }
        for (id in listOf("gpt-4o", "some-relay-reasoner")) {
            assertFalse(
                "$id matches no native scope, so the emitter must take the generic branch",
                nativeByEmitter(id),
            )
        }
    }

    // ══════════════════════════════════════════════ drift guards

    /**
     * SOURCE-GREP DRIFT GUARD. Each gate below is private or inlined in a
     * builder, so the behavioural rows above are the only view of it — and a
     * refactor can reproduce those rows while relocating the decision. Pin the
     * literals that define each gate's SCOPE, since scope is what every one of
     * these bugs was about.
     */
    @Test
    fun `the production gates still carry their scoping literals`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        for (needle in listOf(
            // Fast Mode: toggle AND gpt-family AND don't clobber an existing tier.
            "if (!body.has(\"service_tier\") &&",
            "com.openminis.app.data.FastModePrefs.isEnabled() &&",
            "model.id.contains(\"gpt\", ignoreCase = true)",
            // OpenRouter Anthropic cache control: host AND prefix.
            "isOpenRouter && model.id.lowercase().startsWith(\"anthropic/\")",
            "body.put(\"cache_control\", JSONObject().put(\"type\", \"ephemeral\"))",
            // The output-cap fork.
            "body.put(\"max_tokens\", maxTokens)",
            "body.put(\"max_completion_tokens\", maxTokens)",
            // DashScope is host-matched, like the other vendor predicates.
            "basePath.contains(\"dashscope\")",
        )) {
            assertTrue("gate literal is gone from OpenAIProvider: $needle", src.contains(needle))
        }
    }

    /**
     * The budget inequality and the broad-"o" predicate both live in the
     * resolver, and both are the kind of line a tidy-up would "simplify". The
     * margin arithmetic is what keeps the budget strictly below the cap for
     * small caps; the comment on the prefix is what records that its breadth is
     * a decision.
     */
    @Test
    fun `the resolver still carries the budget margin and the documented broad prefix`() {
        val src = ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        assertTrue(
            "the budget must keep a margin below the cap (#35)",
            src.contains("val margin = maxOf(2048, ctx.maxTokens / 8)"),
        )
        assertTrue(
            "…and a hard strictly-below ceiling",
            src.contains("val ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))"),
        )
        // [OpenMinis#377] gpt-6 joined the family; the point of this assertion is
        // that the emitter predicate and the rule scopes move TOGETHER, so both
        // sides are checked for the same three generations rather than for one
        // frozen spelling.
        assertTrue(
            "the emitter's native predicate must stay in lockstep with the rule scopes",
            src.contains("lid.startsWith(\"o\")") &&
                src.contains("lid.startsWith(\"gpt-5\")") &&
                src.contains("lid.startsWith(\"gpt-6\")"),
        )
        assertTrue(
            "the broad-\"o\" breadth must stay DOCUMENTED as deliberate, not silently inherited",
            src.contains("the broad \"o\" prefix is"),
        )
        assertTrue(
            "the openai-native rule scopes are what the emitter mirrors",
            src.contains("ThinkingRule.Scope.ModelPattern(\"o*\")") &&
                src.contains("ThinkingRule.Scope.ModelPattern(\"gpt-5*\")") &&
                src.contains("ThinkingRule.Scope.ModelPattern(\"gpt-6*\")"),
        )
    }
}
