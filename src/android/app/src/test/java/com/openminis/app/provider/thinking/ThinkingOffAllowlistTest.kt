package com.openminis.app.provider.thinking

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M17 / M14 / M18] The OFF-tier ALLOWLIST and the rule-chain ORDER — the two
 * decisions that together choose what a "thinking off" request looks like.
 *
 * Pins ff60c818a (the allowlist itself), c5efeb1ed (MiMo/Agnes strict-enum
 * exemption — "minimal" killed the whole request), 4a89f5caf / 72968c4f2 (match
 * the mimo FAMILY, not one spelling: the live API serves `mimo-v2.5` while the
 * docs say `mimo-2.5`), 866387d63 (the unified-gateway rule must sit BELOW the
 * OpenAI-native and qwen rules, never above), 466b00f9b / 68fb961eb (GH#356,
 * `deepseek-flash` is a sibling rule, not a widened scope), and 3ba3eb5c0 /
 * 36420f1d2 (GH#163, an affirmatively empty tier list on first-party xAI omits
 * the field).
 *
 * WHY THIS FILE, given ThinkingWireGoldenSnapshotTest already pins values: a
 * snapshot pins WHAT goes out, not WHY. Its rows are the visible surface of two
 * orthogonal mechanisms — a caller-side allowlist (`explicitOffEffort`, which
 * decides whether there IS an off tier) and a resolver-side dispatch (which of
 * OpenAI-native / self-reasoning-family / everyone-else branch consumes it).
 * A refactor can move a decision from one to the other and reproduce every
 * snapshot row while destroying the reason. These tests name the mechanism, so
 * they fail at the point the reasoning breaks rather than at the point a value
 * happens to change.
 *
 * Two seams, deliberately:
 *  • [ThinkingRuleResolver.apply] directly, for the OFF dispatch and the chain
 *    order — that is a pure function and the honest place to assert precedence.
 *  • the real [OpenAIProvider] request body, for the allowlist — it is derived
 *    from the base URL by a private member, so the body is the only seam that
 *    proves the URL predicate and the resolver agree.
 */
class ThinkingOffAllowlistTest {

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

    /**
     * Read the request body the production builder produces for `base`. A direct
     * call, not MockWebServer: the allowlist is a pure function of (base URL,
     * model id) and the body is where its answer lands.
     */
    private fun body(
        base: String,
        m: LLMModel,
        level: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = OpenAIProvider(apiKey = "k", model = m, basePath = base).buildRequestBody(
        messages = listOf(LLMMessage(LLMMessage.Role.USER, "q")),
        systemPrompt = null,
        maxTokens = 4096,
        stream = false,
        temperature = null,
        imageParts = emptyList(),
        tools = emptyList(),
        thinkingLevel = level,
    )

    private fun effort(b: JSONObject): String? =
        if (b.has("reasoning_effort")) b.getString("reasoning_effort") else null

    // ══════════════════════════════════════════ the allowlist (ff60c818a)

    /**
     * Official OpenAI documents `none` as its off tier, so OFF is stated
     * explicitly rather than omitted. Omission is not equivalent: it lets the
     * vendor's own (non-off) default decide, which is the bug ff60c818a fixed.
     */
    @Test
    fun `official OpenAI states none as its off tier`() {
        assertEquals(
            "none",
            effort(body("https://api.openai.com/v1", model("gpt-5.3"))),
        )
    }

    /**
     * Volcano Ark's smallest tier is `minimal`, reachable two ways — by BASE
     * (volces / ark.) and by MODEL FAMILY (seed- / doubao). Both are in the
     * allowlist because Ark models also appear on Ark-shaped relays, and an
     * Ark id on an unrecognised base still needs a floor rather than Ark's
     * non-off default.
     */
    @Test
    fun `Volcano Ark states minimal, by base or by family`() {
        assertEquals(
            "ark base",
            "minimal",
            effort(body("https://ark.cn-beijing.volces.com/api/v3", model("doubao-pro"))),
        )
        assertEquals(
            "volces base",
            "minimal",
            effort(body("https://open.volces.com/api/v3", model("some-model"))),
        )
        assertEquals(
            "seed- family on an unrecognised base",
            "minimal",
            effort(body("https://relay.example.com/v1", model("seed-1.6"))),
        )
        assertEquals(
            "doubao family on an unrecognised base",
            "minimal",
            effort(body("https://relay.example.com/v1", model("doubao-1.5-pro"))),
        )
    }

    /**
     * Everyone else OMITS the field. This is the allowlist's whole point: an
     * off tier is only stated where the vendor DOCUMENTS one, because an
     * undocumented value is a 400 risk on a request the user asked to be cheap.
     *
     * Azure is the load-bearing row — it is an OpenAI endpoint, so a blanket
     * "OpenAI → none" rule would have swept it in, and its off tier is
     * model-dependent ('none' on gpt-5.1+, 'minimal' on the original gpt-5,
     * unsupported on o1/o3).
     */
    @Test
    fun `everyone else omits the off tier`() {
        val unlisted = listOf(
            "https://my-resource.openai.azure.com/openai/deployments/gpt5",
            "https://api.x.ai/v1",
            "https://openrouter.ai/api/v1",
            "https://integrate.api.nvidia.com/v1",
            "https://relay.example.com/v1",
        )
        for (base in unlisted) {
            val b = body(base, model("gpt-5.3"))
            assertNull("$base must omit the off tier, got $b", effort(b))
        }
    }

    /**
     * A host that merely CONTAINS api.openai.com is not official OpenAI — the
     * predicate anchors with startsWith, so a vanity relay does not inherit
     * OpenAI's documented tier. Fails safe: omission is today's behaviour.
     */
    @Test
    fun `a relay that only mentions the OpenAI host does not inherit its tier`() {
        assertNull(effort(body("https://proxy.example.com/api.openai.com/v1", model("gpt-5.3"))))
    }

    // ═════════════════════════════ strict-enum exemption (c5efeb1ed, 72968c4f2)

    /**
     * MiMo and Agnes validate `reasoning_effort` against a STRICT enum, so
     * "minimal" is not a weaker tier to them — it is an invalid value that
     * fails the whole request. They are exempt from the allowlist even when the
     * base would otherwise supply one.
     *
     * Both spellings, because the family ships two: the docs say `mimo-2.5`
     * while the live API (api.xiaomimimo.com) returns `mimo-v2.5`. A
     * single-spelling check passed "minimal" straight through to a backend that
     * 400s on it.
     */
    @Test
    fun `strict-enum families are exempt from every off tier`() {
        for (id in listOf("mimo-2.5", "mimo-v2.5", "mimo-v2.5-pro", "agnes-1", "MiMo-V2.5")) {
            // Official OpenAI base would hand out "none"…
            assertNull(
                "$id must receive no off tier even on the OpenAI base",
                effort(body("https://api.openai.com/v1", model(id))),
            )
            // …and an Ark base would hand out "minimal".
            assertNull(
                "$id must receive no off tier even on an Ark base",
                effort(body("https://ark.cn-beijing.volces.com/api/v3", model(id))),
            )
        }
    }

    /**
     * The exemption is scoped to OFF: the same families still receive a tier
     * when thinking is ON — clamped to "high", which is where their ladder tops
     * out (xhigh is a 400/422). Proving both halves matters because "send
     * nothing to MiMo" would also satisfy the test above.
     */
    @Test
    fun `the strict-enum exemption does not mute the enabled path`() {
        assertEquals(
            "high",
            effort(body("https://api.openai.com/v1", model("mimo-v2.5"), ThinkingLevel.XHIGH)),
        )
        assertEquals(
            "max is not xhigh and is not clamped by this rule",
            "max",
            effort(body("https://api.openai.com/v1", model("mimo-v2.5"), ThinkingLevel.MAX)),
        )
    }

    // ═══════════════════════════════════ the OFF dispatch, three-way (resolver)

    private fun ctx(
        modelId: String,
        offEffort: String?,
        declared: List<String>? = null,
        supportsReasoning: Boolean? = true,
        unified: Boolean = false,
        level: ThinkingLevel = ThinkingLevel.OFF,
        isXAI: Boolean = false,
        declaresNoEffortTiers: Boolean = false,
        isDashScope: Boolean = false,
        isOpenRouter: Boolean = false,
    ) = ThinkingResolveContext(
        modelId = modelId,
        supportsReasoning = supportsReasoning,
        declaredEffortValues = declared,
        declaresNoEffortTiers = declaresNoEffortTiers,
        level = level,
        maxTokens = 4096,
        isOpenRouter = isOpenRouter,
        usesUnifiedReasoningEffort = unified,
        isMistral = false,
        isDashScope = isDashScope,
        isXAI = isXAI,
        offEffort = offEffort,
    )

    private fun resolve(c: ThinkingResolveContext): Pair<JSONObject, ThinkingResolveTrace> {
        val b = JSONObject()
        return b to ThinkingRuleResolver.apply(b, c)
    }

    /**
     * Branch 1 — an OpenAI-native id sends the off tier UNCONDITIONALLY, with no
     * reference to what the model declares. Its declaration is routinely
     * incomplete (models.dev rarely lists `none`), and honouring it would have
     * suppressed the tier for exactly the vendor that documents it.
     */
    @Test
    fun `OpenAI-native ids send the off tier without consulting the declaration`() {
        val (withNothing, _) = resolve(ctx("gpt-5.3", offEffort = "none"))
        assertEquals("none", withNothing.getString("reasoning_effort"))

        val (againstDeclaration, _) = resolve(
            ctx("gpt-5.3", offEffort = "none", declared = listOf("low", "medium", "high")),
        )
        assertEquals(
            "a declaration that omits 'none' must not suppress it for this family",
            "none",
            againstDeclaration.getString("reasoning_effort"),
        )
    }

    /**
     * Branch 2 — the self-reasoning families (deepseek / glm / kimi / minimax)
     * send the off tier only on a unified gateway (which normalizes the field
     * and owns its model list) or when the model itself declares that exact
     * tier. Otherwise nothing: at their native endpoint these models reason by
     * their own mechanism and an unknown root tier is not a documented input.
     */
    @Test
    fun `self-reasoning families need a gateway or an explicit declaration`() {
        val (bare, _) = resolve(ctx("glm-4.6", offEffort = "minimal"))
        assertFalse("no gateway, no declaration → omit: $bare", bare.has("reasoning_effort"))

        val (onGateway, _) = resolve(ctx("glm-4.6", offEffort = "minimal", unified = true))
        assertEquals(
            "a unified gateway normalizes the field, so the tier is safe",
            "minimal",
            onGateway.getString("reasoning_effort"),
        )

        val (declaring, _) = resolve(
            ctx("glm-4.6", offEffort = "minimal", declared = listOf("minimal", "high")),
        )
        assertEquals(
            "the model naming the tier itself is the other sufficient signal",
            "minimal",
            declaring.getString("reasoning_effort"),
        )

        val (declaringOther, _) = resolve(
            ctx("kimi-k2", offEffort = "minimal", declared = listOf("low", "high")),
        )
        assertFalse(
            "declaring OTHER tiers is not declaring this one: $declaringOther",
            declaringOther.has("reasoning_effort"),
        )
    }

    /**
     * Branch 3 — everyone else sends the tier unless the model declares a set
     * that EXCLUDES it. Note the asymmetry against branch 2, which is the
     * point: "declares nothing" is permissive here and restrictive there,
     * because a relay the catalog has never heard of is the common case for an
     * unknown vendor and the uncommon case for a known family.
     */
    @Test
    fun `everyone else sends the tier unless the declaration excludes it`() {
        val (silent, _) = resolve(ctx("some-relay-reasoner", offEffort = "minimal"))
        assertEquals("minimal", silent.getString("reasoning_effort"))

        val (declaresIt, _) = resolve(
            ctx("some-relay-reasoner", offEffort = "minimal", declared = listOf("minimal", "high")),
        )
        assertEquals("minimal", declaresIt.getString("reasoning_effort"))

        val (excludesIt, _) = resolve(
            ctx("some-relay-reasoner", offEffort = "minimal", declared = listOf("low", "high")),
        )
        assertFalse(
            "a declaration without the tier suppresses it: $excludesIt",
            excludesIt.has("reasoning_effort"),
        )

        val (nonReasoning, _) = resolve(
            ctx("gpt-4o-audio", offEffort = "none", supportsReasoning = false),
        )
        assertFalse(
            "a model that cannot reason takes no tier at all: $nonReasoning",
            nonReasoning.has("reasoning_effort"),
        )
    }

    /**
     * The off tier is deliberately NOT clamped onto the declared set.
     * `clampEffort` walks UP when nothing at or below is declared, so a
     * `["high","max"]` model would have turned an OFF request into "high" —
     * inverting the user's intent in the one direction that costs them money.
     * Omission is the correct answer there.
     */
    @Test
    fun `the off tier is never clamped upward into a thinking tier`() {
        val (b, _) = resolve(
            ctx("some-relay-reasoner", offEffort = "minimal", declared = listOf("high", "max")),
        )
        assertFalse("OFF must not become 'high': $b", b.has("reasoning_effort"))
    }

    // ═══════════════════════════ chain order: unified gateway sits BELOW (866387d63)

    /**
     * 866387d63's regression, in both directions. The first version of the rule
     * registry hoisted the unified-gateway rule above the OpenAI-native and
     * qwen rules, which silently swapped the wire mechanism for any request
     * matching BOTH dimensions. Single-dimension rows cannot see it; these are
     * the cross-product rows.
     *
     * Asserted on the TRACE LABEL, not only the emitted keys: the label is the
     * precedence answer itself, so this fails on a reordering even if two rules
     * happen to agree on the body for the level under test.
     */
    @Test
    fun `a gpt-5 id on a unified gateway keeps the OpenAI-native mechanism`() {
        val (b, trace) = resolve(ctx("gpt-5.3", offEffort = "minimal", unified = true, level = ThinkingLevel.HIGH))
        assertEquals("openai-native", trace.matchedRuleLabel)
        assertEquals("high", b.getString("reasoning_effort"))
        assertFalse("must not flip to the qwen mechanism: $b", b.has("enable_thinking"))
    }

    /**
     * The mirror case: a qwen id on Ark/Azure/Venice keeps its native
     * `enable_thinking` + `thinking_budget` dual-send. Hoisting the gateway
     * rule flipped this one the other way, to a bare `reasoning_effort`.
     */
    @Test
    fun `a qwen id on a unified gateway keeps the qwen dual-send`() {
        val (b, trace) = resolve(ctx("qwen3-32b", offEffort = "minimal", unified = true, level = ThinkingLevel.HIGH))
        assertEquals("qwen-dashscope", trace.matchedRuleLabel)
        assertTrue("qwen mechanism, not the gateway's: $b", b.getBoolean("enable_thinking"))
        assertFalse("and never the gateway's root tier: $b", b.has("reasoning_effort"))
    }

    /**
     * A DashScope ENDPOINT claims every model it serves, whatever the id — the
     * pre-refactor chain matched `contains("qwen") || isDashScope` with no
     * unified guard, and both halves survive. Kept next to the row above so the
     * id-match and the endpoint-match cannot drift apart.
     */
    @Test
    fun `a DashScope endpoint claims a non-qwen id too`() {
        val (b, trace) = resolve(
            ctx("some-other-model", offEffort = null, isDashScope = true, level = ThinkingLevel.HIGH),
        )
        assertEquals("qwen-dashscope", trace.matchedRuleLabel)
        assertTrue(b.getBoolean("enable_thinking"))
    }

    /**
     * What the gateway rule DOES legitimately claim: the third-party families
     * below it in the chain. An Ark-hosted deepseek takes the gateway's uniform
     * root tier, not DeepSeek's vendor-native `thinking:{}` object — Ark does
     * not honour that object, and on Venice an unknown root key is a hard 400
     * raised at schema validation, before model dispatch.
     */
    @Test
    fun `the gateway rule does claim the families registered below it`() {
        val (b, trace) = resolve(
            ctx("deepseek-v4-pro", offEffort = "minimal", unified = true, level = ThinkingLevel.HIGH),
        )
        assertEquals("unified-gateway(ark|azure|venice)", trace.matchedRuleLabel)
        assertEquals("high", b.getString("reasoning_effort"))
        assertFalse("no vendor-native thinking object on a gateway: $b", b.has("thinking"))
    }

    /**
     * OpenRouter sits ABOVE all of them, and omits at OFF rather than sending a
     * tier: it fronts forced-reasoning backends that reject `effort:"none"`.
     * The nesting is also part of its contract — root `reasoning_effort` is the
     * wrong placement for that gateway.
     */
    @Test
    fun `OpenRouter outranks every model rule and nests the effort`() {
        val (on, trace) = resolve(
            ctx("gpt-5.3", offEffort = "none", isOpenRouter = true, level = ThinkingLevel.HIGH),
        )
        assertEquals("openrouter", trace.matchedRuleLabel)
        assertEquals("high", on.getJSONObject("reasoning").getString("effort"))
        assertFalse("nested, never at the root: $on", on.has("reasoning_effort"))

        val (off, _) = resolve(ctx("gpt-5.3", offEffort = "none", isOpenRouter = true))
        assertFalse("OpenRouter omits at OFF: $off", off.has("reasoning"))
    }

    // ═══════════════ deepseek-flash vs deepseek-v4, side by side (466b00f9b, GH#356)

    /**
     * M14's side-by-side: the two DeepSeek ids reach the SAME wire format
     * through DIFFERENT rules. That distinction is the fix — `deepseek-flash`
     * does not match `*deepseek-v4*`, so before the sibling rule existed it
     * fell through to the generic `reasoning_effort` default, which is the wrong
     * shape for this vendor.
     *
     * Also pins that neither of them outranks the gateway rule (checked above)
     * and that both carry the same reasoning-echo policy, since they are the
     * same backend.
     */
    @Test
    fun `deepseek-flash and deepseek-v4 share a shape but not a rule`() {
        for ((id, label) in listOf(
            "deepseek-flash" to "deepseek-flash-official",
            "deepseek-flash-lite" to "deepseek-flash-official",
            "deepseek-v4-pro" to "deepseek-v4-official",
            "deepseek-v4-flash" to "deepseek-v4-official",
        )) {
            val (b, trace) = resolve(ctx(id, offEffort = null, level = ThinkingLevel.HIGH))
            assertEquals("$id must resolve through $label", label, trace.matchedRuleLabel)
            assertEquals("$id: sibling shape", "enabled", b.getJSONObject("thinking").getString("type"))
            assertEquals("$id: root tier", "high", b.getString("reasoning_effort"))
        }
    }

    /**
     * The anchored `deepseek-flash*` scope must not sweep in the ids whose wire
     * format may genuinely differ. A bare `*deepseek*` would have claimed both
     * of these, which is why the commit chose a neighbouring rule over widening
     * the existing one.
     */
    @Test
    fun `the anchored flash scope leaves chat and reasoner alone`() {
        for (id in listOf("deepseek-chat", "deepseek-reasoner")) {
            val (b, trace) = resolve(ctx(id, offEffort = null, level = ThinkingLevel.HIGH))
            assertFalse(
                "$id must not take the sibling shape (label=${trace.matchedRuleLabel}): $b",
                b.has("thinking"),
            )
        }
    }

    // ══════════════════════════════ xAI empty tiers, at OFF too (GH#163)

    /**
     * XAIEmptyEffortTiersTest covers the ENABLED path, where the skip lives. The
     * OFF path reaches the same field through an entirely different dispatch —
     * the three-way branch above, which returns before the skip is ever
     * evaluated — so the skip has to be re-stated there or it does not apply.
     *
     * KNOWN GAP: it is not re-stated. See the test below. This row pins the half
     * that does work, so the two cannot be conflated.
     */
    @Test
    fun `an xAI model declaring no tiers omits the field on the enabled path`() {
        val (on, _) = resolve(
            ctx(
                "grok-build-0.1",
                offEffort = "minimal",
                isXAI = true,
                declaresNoEffortTiers = true,
                level = ThinkingLevel.HIGH,
            ),
        )
        assertFalse("the enabled-path skip must hold even when an off tier exists: $on", on.has("reasoning_effort"))
    }

    /**
     * WAS A KNOWN GAP — found while writing this file, FIXED in production by
     * hoisting the guard above the OFF dispatch. Now a hard assertion.
     *
     * GH#163's skip ("the catalog affirmatively declares no effort tiers, so omit
     * `reasoning_effort`") is implemented in the ENABLED branch of
     * `ThinkingWireFormat.ReasoningEffort` only. The OFF branch is a separate
     * early-returning dispatch placed ABOVE it, and none of its three arms
     * consults `declaresNoEffortTiers`. So an xAI model that answers the field
     * with
     *   400 "Model grok-build-0.1 does not support parameter reasoningEffort"
     * receives that field anyway the moment an off tier exists — i.e. turning
     * thinking OFF is the one way to put the 400-triggering parameter back on the
     * wire, which inverts the user's intent in the most confusing possible
     * direction ("it only breaks when I turn thinking off").
     *
     * REACHABILITY (as it stood before the fix). It needed `offEffort != null` on a first-party xAI endpoint,
     * and `explicitOffEffort()` returns null for api.x.ai today — official OpenAI
     * and Ark/seed/doubao are the only allowlist members — so this is LATENT, not
     * a live field bug. It becomes live if xAI is ever added to the allowlist
     * (plausible: the commit notes widening it is "a one-line change backed by new
     * evidence"), or for a grok id served from an Ark-shaped base, where the
     * family half of the allowlist supplies "minimal" while `isXAI` is false —
     * that combination is already excluded by the unified-gateway exemption, so
     * the allowlist is the realistic trigger.
     *
     * FIX APPLIED: the `isXAI && !usesUnifiedReasoningEffort &&
     * declaresNoEffortTiers && !declaresEffort` guard now sits ABOVE the
     * `if (!ctx.level.isEnabled)` block, so one check governs both paths — which
     * also removes the possibility of the two drifting apart again. "The model
     * does not accept this parameter" is a property of the model, not of the
     * requested level, so it must outrank the level dispatch.
     */
    @Test
    fun `the xAI empty-tier skip also applies on the OFF path`() {
        val (off, trace) = resolve(
            ctx(
                "grok-build-0.1",
                offEffort = "minimal",
                isXAI = true,
                declaresNoEffortTiers = true,
                level = ThinkingLevel.OFF,
            ),
        )
        assertFalse(
            "an xAI model the catalog says takes NO effort tiers must not receive reasoning_effort " +
                "even at OFF — that field is exactly what grok-build-0.1 answers with a 400 " +
                "(rule=${trace.matchedRuleLabel}): $off",
            off.has("reasoning_effort"),
        )

        // The complementary case: with no off tier — every shipping xAI config
        // exists. With no off tier — every shipping xAI config — OFF correctly
        // emits nothing, so the live behaviour is safe and this stays latent.
        val (noOffTier, _) = resolve(
            ctx(
                "grok-build-0.1",
                offEffort = null,
                isXAI = true,
                declaresNoEffortTiers = true,
                level = ThinkingLevel.OFF,
            ),
        )
        assertFalse(
            "with no off tier — today's real xAI config — OFF must still emit nothing: $noOffTier",
            noOffTier.has("reasoning_effort"),
        )
    }

    // ══════════════════════════════════════════════ drift guard

    /**
     * SOURCE-GREP DRIFT GUARD. `explicitOffEffort` is private and reached here
     * only through a request body, so the assertions above would keep passing if
     * someone reimplemented the allowlist as a blanket rule that happens to
     * produce the same answers for the bases named in this file. Pin the
     * load-bearing literals: the allowlist is defined BY its membership, and a
     * new member is a deliberate decision that should update this test.
     */
    @Test
    fun `the allowlist literals still live in the production predicate`() {
        val src = com.openminis.app.ProductionSources.read("provider/openai/OpenAIProvider.kt")
        for (needle in listOf(
            "private fun explicitOffEffort(): String?",
            "if (isAzure) return null",
            "startsWith(\"https://api.openai.com\")",
            "base.contains(\"volces\")",
            "base.contains(\"ark.\")",
            "lid.contains(\"seed-\")",
            "lid.contains(\"doubao\")",
        )) {
            assertTrue("explicitOffEffort no longer carries: $needle", src.contains(needle))
        }

        val resolver = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        assertTrue(
            "the strict-enum exemption must stay keyed on the mimo/agnes FAMILY",
            resolver.contains("val strictEffortEnum = lid.contains(\"mimo\") || lid.contains(\"agnes\")"),
        )
        assertTrue(
            "the self-reasoning family list drives the OFF dispatch",
            resolver.contains("listOf(\"deepseek\", \"glm\", \"kimi\", \"minimax\")"),
        )
        assertTrue(
            "the gateway rule must still be registered under its own label",
            resolver.contains("label = \"unified-gateway(ark|azure|venice)\""),
        )
    }

    /**
     * ORDER guard, asserted on the source text rather than on behaviour. The
     * behavioural rows above catch a swap between two rules that emit different
     * shapes; they cannot catch a reordering among rules whose difference is
     * only reachable through a context this file does not construct. The
     * registry order IS the priority, so assert the offsets.
     */
    @Test
    fun `the unified-gateway rule is registered below openai-native and qwen`() {
        val src = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        val openrouter = src.indexOf("label = \"openrouter\"")
        val native = src.indexOf("label = \"openai-native\"")
        val qwen = src.indexOf("label = \"qwen-dashscope\"")
        val gateway = src.indexOf("label = \"unified-gateway(ark|azure|venice)\"")
        val v4 = src.indexOf("label = \"deepseek-v4-official\"")
        val flash = src.indexOf("label = \"deepseek-flash-official\"")
        val default = src.indexOf("label = \"openai-compatible-default\"")
        for ((name, at) in listOf(
            "openrouter" to openrouter, "openai-native" to native, "qwen-dashscope" to qwen,
            "unified-gateway" to gateway, "deepseek-v4" to v4, "deepseek-flash" to flash,
            "default" to default,
        )) {
            assertTrue("rule label not found in the registry: $name", at > 0)
        }
        assertTrue("OpenRouter must outrank the model rules", openrouter < native)
        assertTrue("openai-native must outrank the gateway rule (866387d63)", native < gateway)
        assertTrue("qwen-dashscope must outrank the gateway rule (866387d63)", qwen < gateway)
        assertTrue("the gateway rule must outrank the deepseek families", gateway < v4)
        assertTrue("the flash sibling sits next to the v4 rule", v4 < flash)
        assertTrue("the provider default is last, so stage A always matches", flash < default)
    }
}
