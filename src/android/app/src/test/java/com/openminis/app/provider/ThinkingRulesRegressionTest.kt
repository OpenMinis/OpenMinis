package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.thinking.ThinkingWireFormat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression safety-net for the thinking/reasoning wire-format rules (17 rules mined
 * from past regressions; each test cites the rule and the commit that fixed it).
 *
 * WHY THIS EXISTS: the dominant failure mode for these rules is SILENT DEGRADATION —
 * not a thrown error, but a field quietly landing at the wrong path, or a tier
 * quietly collapsing onto the vendor default. Two shipped examples:
 *   • 847822eb — DeepSeek V4's tier was nested INSIDE `thinking:{}` instead of being a
 *     root sibling, so every request silently ran at the vendor default for ~3 months.
 *   • 22647505 — a `glm`/`kimi`/`deepseek`/`minimax` id-substring skip-list meant the
 *     request carried NO thinking field at all; the server applied its own default.
 * Neither raised an exception. So every assertion here targets the REAL serialized
 * request body, and positive assertions are paired with mutually-exclusive negative
 * ones (e.g. root `reasoning_effort` present AND absent from inside `thinking`).
 *
 * The body is captured through the genuine production path: a real [OpenAIProvider]
 * is pointed at a [MockWebServer] URL whose path carries the vendor literal the
 * production predicate greps for (`isMistral` = `basePath.contains("mistral.ai")`,
 * `usesUnifiedReasoningEffort` = contains `api.venice.ai` / `volces` / `ark.`). This
 * exercises the real detection + real `injectThinkingParams` — which is `private`, so
 * driving it end-to-end is the only way to regression-test it — with no network and
 * no credentials. Mirrors the established pattern in [MistralReasoningFieldTest].
 */
class ThinkingRulesRegressionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------------------------------------------------------------- helpers

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
        interleavedReasoningField: String? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "TestProvider",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
        interleavedReasoningField = interleavedReasoningField,
    )

    private fun plainHistory(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "question"),
    )

    private fun historyWithReasoning(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "first question"),
        LLMMessage(LLMMessage.Role.ASSISTANT, "first answer")
            .copy(reasoningContent = "captured chain of thought"),
        LLMMessage(LLMMessage.Role.USER, "second question"),
    )

    /**
     * Drive the real provider and return the serialized outbound body.
     *
     * `basePath` embeds the vendor literal so the production URL predicates fire.
     * Several identical responses are enqueued because the provider may retry; a
     * drained queue surfaces as a confusing transient error instead of the
     * assertion under test. Only the FIRST recorded request is read.
     */
    private fun capture(
        model: LLMModel,
        level: ThinkingLevel,
        basePath: String = server.url("/v1").toString().trimEnd('/'),
        history: List<LLMMessage> = plainHistory(),
        maxTokens: Int = 4096,
    ): JSONObject {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
            )
        }
        val provider = OpenAIProvider(apiKey = "test-key", model = model, basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = history,
                    systemPrompt = null,
                    maxTokens = maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    /** Every key anywhere in the body that could carry a thinking control. */
    private fun thinkingKeysPresent(body: JSONObject): List<String> {
        val watched = listOf(
            "reasoning_effort", "reasoning", "thinking", "enable_thinking",
            "thinking_budget", "reasoningEffort", "thinkingConfig",
        )
        return watched.filter { body.has(it) }
    }

    private fun anyMessageHasKey(body: JSONObject, key: String): Boolean {
        val msgs = body.optJSONArray("messages") ?: return false
        for (i in 0 until msgs.length()) {
            if (msgs.getJSONObject(i).has(key)) return true
        }
        return false
    }

    // ============================================================ NEGATIVE: zero fields

    /**
     * Rule: Mistral — no thinking request parameter may EVER be sent.
     * Ref: "[Mistral] every reasoning field is strictly forbidden" · 4592ca9b (422
     * `extra_forbidden body.reasoning`) · OpenMinis#87.
     * This is the single most likely rule to be broken by a future refactor, because
     * it is a pure absence — nothing in the body points at it.
     */
    @Test
    fun `mistral sends no thinking request parameter at any level`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX)) {
            val body = capture(
                model = model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")),
                level = level,
                basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
            )
            assertEquals(
                "Mistral must carry zero thinking control keys at level=$level (422 extra_forbidden): $body",
                emptyList<String>(),
                thinkingKeysPresent(body),
            )
        }
    }

    /**
     * The half of the Mistral rule that Android DOES implement today: thinking OFF
     * emits no control key. Kept un-ignored so the currently-correct behaviour stays
     * protected while the enabled-level gap above remains open.
     */
    @Test
    fun `mistral sends no thinking control when off`() {
        val body = capture(
            model = model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")),
            level = ThinkingLevel.OFF,
            basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
        )
        assertEquals(
            "Mistral must carry zero thinking control keys at OFF: $body",
            emptyList<String>(),
            thinkingKeysPresent(body),
        )
    }

    /**
     * Rule: Mistral must stay thinking-free on the RESPONSES API path too.
     *
     * `buildResponsesAPIBody` injects `reasoning` on its own, independent of
     * `injectThinkingParams` — so the Chat-Completions `isMistral` gate does not
     * protect it. A Mistral instance with `useResponsesAPI = true` reached that
     * builder ungated and put `reasoning` back on the wire, re-triggering the
     * `422 extra_forbidden body.reasoning` that OpenMinis#87 is about.
     *
     * Regression-guards the second injection site found while fixing the first.
     */
    @Test
    fun `mistral sends no reasoning on the responses api path`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
            val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
            repeat(4) {
                server.enqueue(
                    MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
                )
            }
            val provider = OpenAIProvider(
                apiKey = "test-key",
                model = model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")),
                basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
                useResponsesAPI = true,
            )
            runCatching {
                runBlocking {
                    provider.sendMessageClamped(
                        messages = plainHistory(),
                        systemPrompt = null,
                        maxTokens = 4096,
                        temperature = null,
                        imageParts = emptyList(),
                        tools = emptyList(),
                        thinkingLevel = level,
                    )
                }
            }
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(
                "Responses-path Mistral must not carry `reasoning` at level=$level (422 extra_forbidden): $body",
                body.has("reasoning"),
            )
        }
    }

    /**
     * Control for the test above: a NON-Mistral Responses-path provider must still
     * receive `reasoning`. Without this, the Mistral assertion could pass simply
     * because the Responses path stopped emitting reasoning for everyone — the
     * gate has to be Mistral-specific, not a blanket suppression.
     */
    @Test
    fun `non-mistral responses api still receives reasoning`() {
        val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
        repeat(4) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
            )
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model("gpt-5.3", reasoningEffortValues = listOf("low", "medium", "high")),
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.HIGH,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(
            "a non-Mistral Responses provider must still get reasoning — the gate must be vendor-specific: $body",
            body.has("reasoning"),
        )
    }

    /**
     * Rule: Mistral — `reasoning_content` is forbidden on assistant history too.
     * The request schema and the message schema have DIFFERENT strictness: only
     * `AssistantMessage` is `additionalProperties:false`. Ref: 29065ca0 / 0839f019.
     */
    @Test
    fun `mistral strips reasoning_content from assistant history`() {
        val body = capture(
            model = model("mistral-large-latest"),
            level = ThinkingLevel.MEDIUM,
            basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
            history = historyWithReasoning(),
        )
        assertFalse(
            "reasoning_content must never reach Mistral (422 extra_forbidden): $body",
            anyMessageHasKey(body, "reasoning_content"),
        )
    }

    /**
     * Rule: Venice — the root `thinking` key must not appear even with thinking OFF.
     * Venice's ChatCompletionRequest is `additionalProperties:false`, so an unknown
     * root key is rejected at schema validation BEFORE model dispatch — which is why
     * the reporter saw every model fail and why turning thinking OFF did not help
     * (the `{"type":"disabled"}` branch still emitted the key).
     * Ref: "[Venice] unknown root key…" · 84f5c9e1 · OpenMinis#86.
     */
    @Test
    fun `venice never receives root thinking key even when off`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
            val body = capture(
                // A deepseek-v4 id is the exact trigger from the report: the id-substring
                // branch would otherwise select the vendor-native thinking:{} object.
                model = model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")),
                level = level,
                basePath = server.url("/api.venice.ai/api/v1").toString().trimEnd('/'),
            )
            assertFalse(
                "root `thinking` must never be sent to Venice at level=$level (400 unrecognized_keys): $body",
                body.has("thinking"),
            )
        }
    }

    /**
     * Rule: families that declare NO effort tiers keep the legacy self-reasoning skip.
     * Ref: "[data-driven refactor]" · 22647505 — the inverse case of the GLM report.
     */
    @Test
    fun `undeclared glm family sends no thinking field`() {
        val body = capture(
            model = model("glm-4.5-air", supportsReasoning = null, reasoningEffortValues = null),
            level = ThinkingLevel.HIGH,
        )
        assertEquals(
            "a glm id with no declared effort tiers must send no thinking control: $body",
            emptyList<String>(),
            thinkingKeysPresent(body),
        )
    }

    // ============================================================ POSITIVE: effort mapping

    /**
     * Rule: a model DECLARING effort tiers is driven by declared capability, not by
     * its family name — the fix for "GLM 5.2 ignores the thinking level while Hermes
     * on the same relay honours it". Ref: 22647505 / 47dc71b3.
     */
    @Test
    fun `declared glm model receives clamped root reasoning_effort`() {
        val body = capture(
            model = model("glm-5.2", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.HIGH,
        )
        assertEquals(
            "declared effort model must carry root reasoning_effort: $body",
            "high",
            body.optString("reasoning_effort", null),
        )
    }

    /**
     * Rule: the requested tier is clamped ONTO the declared set — asking for a tier the
     * model never declared must not reach the wire. `["high","max"]` is the most common
     * sparse shape in the catalog. Ref: 47dc71b3.
     */
    @Test
    fun `sparse declared set clamps xhigh onto a declared tier`() {
        val body = capture(
            model = model("glm-5.2", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.XHIGH,
        )
        val sent = body.optString("reasoning_effort", null)
        assertTrue(
            "xhigh must be clamped onto the declared set [high,max], got '$sent': $body",
            sent == "high" || sent == "max",
        )
    }

    /**
     * Rule: ULTRA is a client-side "Max + orchestration" concept and must NEVER reach a
     * backend as the literal string "ultra" — both MAX and ULTRA map to "max".
     * Ref: "[GPT-5.6 / ULTRA]" · b38bf3d5.
     */
    @Test
    fun `ultra never reaches the wire as a literal`() {
        val body = capture(
            model = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max")),
            level = ThinkingLevel.ULTRA,
        )
        assertFalse(
            "literal 'ultra' must never be sent (backends 400 on it): $body",
            body.optString("reasoning_effort", "") == "ultra",
        )
    }

    /**
     * [T-gpt6-astra] gpt-6-astra reuses the 5.6-sol/terra tier wholesale. Its
     * upstream metadata lists an "ultra" wire value, and the temptation is to
     * special-case it; the rule is that ULTRA is client-side only, so the
     * ceiling comes from the id rule and ULTRA still leaves as "max".
     */
    @Test
    fun `gpt-6-astra reaches max by id rule and sends ultra as max`() {
        val m = model("gpt-6-astra")
        assertEquals(
            "undeclared gpt-6-astra must reach MAX through the id rule",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
        val body = capture(model = m, level = ThinkingLevel.ULTRA)
        assertEquals(
            "ULTRA on gpt-6-astra must go out as 'max', never 'ultra': $body",
            "max",
            body.optString("reasoning_effort", null),
        )
    }

    // ============================================================ OFF semantics

    /**
     * Rule: MiMo/Agnes validate `reasoning_effort` against a STRICT low/medium/high enum.
     * At OFF the field must be OMITTED — sending "minimal" killed the whole request
     * on-device (iPhone 11, api.xiaomimimo.com): no reply at all, strictly worse than
     * the vendor-default reasoning the change was meant to avoid.
     * Ref: "[MiMo / Agnes] OFF must omit the field entirely" · c5efeb1e.
     */
    @Test
    fun `mimo omits reasoning_effort entirely when off`() {
        for (id in listOf("mimo-v2.5", "mimo-2.5", "agnes-1")) {
            val body = capture(model = model(id), level = ThinkingLevel.OFF)
            assertFalse(
                "$id must OMIT reasoning_effort at OFF, never send an off-tier string: $body",
                body.has("reasoning_effort"),
            )
        }
    }

    /**
     * Rule: OFF-tier injection is an ALLOWLIST, not a blanket rule. Vendors with
     * undocumented off semantics keep field omission; only official OpenAI ("none")
     * and Volcano Ark ("minimal") are on the list.
     * Ref: "[global] explicit thinking-off values are an ALLOWLIST" · ff60c818.
     */
    @Test
    fun `unknown custom base omits the off tier`() {
        val body = capture(
            model = model("some-relay-model", reasoningEffortValues = listOf("low", "medium", "high")),
            level = ThinkingLevel.OFF,
        )
        assertFalse(
            "an unlisted custom base must keep OFF omission, not invent an off tier: $body",
            body.has("reasoning_effort"),
        )
    }

    /**
     * Rule: Volcano Ark IS on the allowlist and takes the documented smallest tier.
     * Ref: ff60c818 (`volces`/`ark.` base or seed/doubao model → "minimal").
     */
    @Test
    fun `volcano ark sends minimal as its off tier`() {
        val body = capture(
            model = model("doubao-pro"),
            level = ThinkingLevel.OFF,
            basePath = server.url("/ark.volces.com/api/v3").toString().trimEnd('/'),
        )
        assertEquals(
            "Ark's documented off tier is 'minimal' so the vendor default can't silently reason: $body",
            "minimal",
            body.optString("reasoning_effort", null),
        )
    }

    // ============================================================ STRUCTURE

    /**
     * Rule: DeepSeek V4 carries the switch and the tier as ROOT SIBLINGS —
     * `{"thinking":{"type":"enabled"}, "reasoning_effort":"high"}`. The tier must NOT
     * be nested inside the `thinking` object; doing so made it an unknown nested key
     * with no root tier at all, so every V4 request silently ran at the vendor default.
     * The paired negative assertion is the whole point: the positive one alone passed
     * for 3 months. Ref: "[DeepSeek V4] …root-level siblings" · 847822eb.
     */
    @Test
    fun `deepseek v4 sends thinking and reasoning_effort as root siblings`() {
        val body = capture(
            model = model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.HIGH,
        )
        val thinking = body.optJSONObject("thinking")
        assertTrue("`thinking` object must be present when enabled: $body", thinking != null)
        assertEquals("thinking.type must be 'enabled': $body", "enabled", thinking?.optString("type"))
        assertTrue("root reasoning_effort must be present: $body", body.has("reasoning_effort"))
        assertFalse(
            "reasoning_effort must NOT be nested inside thinking{} — that is the silent 847822eb bug: $body",
            thinking?.has("reasoning_effort") ?: false,
        )
    }

    /**
     * Rule: thinking is ON by default on DeepSeek V4, so OFF must be sent EXPLICITLY.
     * Ref: 9d4d4f2e / 847822eb.
     */
    @Test
    fun `deepseek v4 explicitly disables when off`() {
        val body = capture(
            model = model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.OFF,
        )
        assertEquals(
            "V4 reasons by default, so OFF must be explicit: $body",
            "disabled",
            body.optJSONObject("thinking")?.optString("type"),
        )
    }

    /**
     * Rule: Ark/Azure re-host third-party families behind a uniform OpenAI surface where
     * thinking is controlled ONLY by `reasoning_effort` — the vendor-native `thinking:{}`
     * shape is not honoured there. Same model id, different endpoint, different shape.
     * Ref: "[Volcengine Ark / Azure] unified reasoning_effort" · ba055121.
     */
    @Test
    fun `ark hosted deepseek uses uniform reasoning_effort not vendor thinking object`() {
        val body = capture(
            model = model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")),
            level = ThinkingLevel.HIGH,
            basePath = server.url("/ark.volces.com/api/v3").toString().trimEnd('/'),
        )
        assertFalse(
            "on Ark the vendor-native thinking:{} must NOT be sent: $body",
            body.has("thinking"),
        )
        assertTrue("on Ark the tier travels in reasoning_effort: $body", body.has("reasoning_effort"))
    }

    /**
     * Rule: Qwen sends `enable_thinking`/`thinking_budget` at BOTH root and `extra_body`
     * (DashScope expects extra_body; vLLM/SGLang accept top-level).
     * Ref: "[Qwen] sent both at root level and in extra_body" · 25165700.
     */
    @Test
    fun `qwen dual-sends thinking params at root and extra_body`() {
        val body = capture(model = model("qwen3-32b"), level = ThinkingLevel.MEDIUM)
        assertTrue("root enable_thinking expected: $body", body.has("enable_thinking"))
        assertTrue(
            "extra_body.enable_thinking expected (DashScope reads it there): $body",
            body.optJSONObject("extra_body")?.has("enable_thinking") ?: false,
        )
    }

    // ============================================================ BOUNDARY

    /**
     * Rule: DashScope enforces `thinking_budget < max_completion_tokens` STRICTLY —
     * equal values are rejected too ("[16384] must be greater than [16384]").
     * Ref: "[Qwen / DashScope] …equal values rejected too" · 8db455ff → a5a0de20 · issues #35 / #641.
     */
    @Test
    fun `qwen thinking_budget stays strictly below max_tokens`() {
        for (maxTokens in listOf(16384, 64000, 4096)) {
            val body = capture(model = model("qwen3-32b"), level = ThinkingLevel.MAX, maxTokens = maxTokens)
            val budget = body.optInt("thinking_budget", -1)
            if (budget > 0) {
                assertTrue(
                    "thinking_budget($budget) must be STRICTLY < max_completion_tokens($maxTokens): $body",
                    budget < maxTokens,
                )
            }
        }
    }

    /**
     * Rule: pathological max_tokens leaves no room for a positive budget strictly below
     * max, so the field must be dropped rather than emitted invalid.
     * Ref: a5a0de20 ("maxTokens < 2 → drop thinking_budget entirely").
     */
    @Test
    fun `qwen drops thinking_budget when max_tokens leaves no room`() {
        val body = capture(model = model("qwen3-32b"), level = ThinkingLevel.MAX, maxTokens = 1)
        val budget = body.optInt("thinking_budget", 0)
        assertTrue(
            "with max_tokens=1 no positive budget can be strictly below it; expected omitted/0, got $budget: $body",
            budget <= 0,
        )
    }

    // ============================================================ ECHO

    /**
     * Rule: MiMo/DeepSeek REQUIRE `reasoning_content` on assistant history — the exact
     * inverse of Mistral. Neither vendor advertises supportsReasoning, so capability
     * metadata cannot distinguish them; this is why echo policy must be per-provider.
     * Ref: "[DeepSeek / MiMo / GLM / Kimi] thinking content must be echoed back verbatim" · 7f88321e.
     */
    @Test
    fun `interleaved model echoes reasoning_content on assistant history`() {
        val body = capture(
            model = model(
                "deepseek-v4-flash",
                reasoningEffortValues = listOf("low", "high", "max"),
                interleavedReasoningField = "reasoning_content",
            ),
            level = ThinkingLevel.MEDIUM,
            history = historyWithReasoning(),
        )
        assertTrue(
            "interleaved-reasoning models 400 when history omits reasoning_content: $body",
            anyMessageHasKey(body, "reasoning_content"),
        )
    }

    // ============================================================ ID NORMALIZATION

    /**
     * Rule: MiMo ships BOTH spellings in the wild — catalog docs say `mimo-2.5` while the
     * live API returns `mimo-v2.5`. A rule matching one spelling silently misses the
     * other, letting xhigh through to a backend that 400s on it.
     * Ref: "[MiMo] model id spelling variants" · 72968c4f.
     * (Covered for the OFF path by `mimo omits reasoning_effort entirely when off`,
     * which iterates both spellings; this asserts the clamp side.)
     */
    @Test
    fun `both mimo spellings are clamped below xhigh`() {
        for (id in listOf("mimo-2.5", "mimo-v2.5", "mimo-v2.5-pro")) {
            val body = capture(model = model(id), level = ThinkingLevel.XHIGH)
            val sent = body.optString("reasoning_effort", "")
            assertFalse(
                "$id must never receive xhigh (backend 400s); got '$sent': $body",
                sent == "xhigh",
            )
        }
    }

    // ── [T-thinking-levels-data-driven] declared tiers drive the UI ceiling ──
    //
    // Field report (Alice, 2026-08-20..31): deepseek-v4 and GLM could not be
    // pushed past "high" on Android no matter what the picker was set to, while
    // the same models reached "max" on iOS. Two layers disagreed: the catalog
    // declares `["high","max"]`, but the ceiling came from the hardcoded XHIGH
    // default, so the strongest thing a user could ASK for was "xhigh" — which
    // is not in the declared set, so clampEffort snapped it DOWN to "high".
    // Mirrors iOS testDeclaredTiersOverrideSubstringCeiling (47dc71b3).

    /**
     * Rule: a declared effort set is a STRONGER statement than any id-substring rule and
     * must raise the ceiling to the declared top tier — otherwise a tier the catalog
     * declares is clamped to on the wire yet unselectable in the UI.
     */
    @Test
    fun `declared tiers override the substring ceiling`() {
        val m = model("glm-5.2", reasoningEffortValues = listOf("high", "max"))
        assertEquals(
            "a declared top tier of 'max' must be reachable from the UI: ${m.reasoningEffortValues}",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
    }

    /**
     * Rule: sparse declarations yield one option per DISTINCT wire tier, so every option
     * the user can pick produces a different request.
     */
    @Test
    fun `sparse declaration yields distinct selectable levels`() {
        val m = model("glm-5.2", reasoningEffortValues = listOf("high", "max"))
        val levels = m.selectableThinkingLevels
        assertEquals(
            "selectable levels must be distinct — duplicates mean a slider that changes nothing",
            levels.size,
            levels.toSet().size,
        )
        assertEquals(
            "a [high, max] declaration must surface exactly two options, got $levels",
            listOf(ThinkingLevel.HIGH, ThinkingLevel.MAX),
            levels,
        )
    }

    /**
     * The end-to-end assertion behind the field report: asking for MAX on a real
     * deepseek-v4 id must put `"max"` on the wire, not the "high" the old ceiling
     * produced. Goes through sendMessageClamped, so it covers ceiling → clamp → body.
     */
    @Test
    fun `deepseek v4 max request reaches the wire as max`() {
        for (id in listOf("deepseek-v4-flash", "deepseek-v4-pro", "glm-5.2")) {
            val body = capture(
                model = model(id, reasoningEffortValues = listOf("high", "max")),
                level = ThinkingLevel.MAX,
            )
            assertEquals(
                "$id must send reasoning_effort=max when the user asks for MAX: $body",
                "max",
                body.optString("reasoning_effort", null),
            )
        }
    }

    /**
     * The other direction, and the reason this fix belongs in the ceiling rather than in
     * clampEffort: a NARROW declaration must LOWER the ceiling too. `gpt-5.3` matches no
     * id rule (so it used to ride the XHIGH default), but a backend declaring only
     * `["low","high"]` 400s on xhigh. iOS resolves this to .high through the same path.
     */
    @Test
    fun `narrow declaration lowers the ceiling below the default`() {
        val m = model("gpt-5.3", reasoningEffortValues = listOf("low", "high"))
        assertEquals(
            "a declared set topping out at 'high' must cap the ceiling there: " +
                "${m.reasoningEffortValues}",
            ThinkingLevel.HIGH,
            m.catalogMaxThinkingLevel,
        )
        val body = capture(model = m, level = ThinkingLevel.MAX)
        assertEquals(
            "an undeclared tier must never reach the wire: $body",
            "high",
            body.optString("reasoning_effort", null),
        )
    }

    /**
     * [T-thinking-max-unreachable] …but a narrow declaration must never lower a ceiling
     * a family RULE knows about. models.dev often lists an incomplete set for a model we
     * know reaches MAX — gpt-5.6-sol has an explicit MAX rule yet commonly declares only
     * ["low","medium","high"] — and taking the declared top verbatim hid MAX from the
     * picker for exactly the models the rule exists to describe. Note the contrast with
     * the test above: gpt-5.3 matches NO rule, so its narrow declaration still caps it.
     */
    @Test
    fun `incomplete declaration does not lower a known rule ceiling`() {
        assertEquals(
            "precondition: the family rule reaches MAX",
            ThinkingLevel.MAX,
            ThinkingLevelCatalog.declaredMaxLevel("gpt-5.6-sol"),
        )
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium", "high"))
        assertEquals(
            "an incomplete declaration must not cut the rule's ceiling to HIGH",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
    }

    /** The same shape one tier up: a declaration topping at xhigh must not hide MAX. */
    @Test
    fun `declaration topping at xhigh keeps max reachable`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("high", "xhigh"))
        assertEquals(ThinkingLevel.MAX, m.catalogMaxThinkingLevel)
    }

    /** A declaration reaching ABOVE the rule still wins — the original behaviour. */
    @Test
    fun `declaration above the rule still raises the ceiling`() {
        val m = model("glm-5.2", reasoningEffortValues = listOf("high", "max"))
        assertEquals(ThinkingLevel.MAX, m.catalogMaxThinkingLevel)
    }

    /** A non-reasoning model stays OFF regardless of rule or declaration. */
    @Test
    fun `non-reasoning model ignores both the rule and the declaration`() {
        val m = model(
            "gpt-5.6-sol",
            supportsReasoning = false,
            reasoningEffortValues = listOf("high", "max"),
        )
        assertEquals(ThinkingLevel.OFF, m.catalogMaxThinkingLevel)
    }

    /**
     * Guard on the fix's blast radius: a model that declares NOTHING must still resolve
     * through the legacy id-rule/default path, unchanged.
     */
    @Test
    fun `undeclared model still uses the id-rule ceiling`() {
        assertEquals(
            "no declaration → hardcoded default is still XHIGH",
            ThinkingLevel.XHIGH,
            model("some-unknown-reasoner").catalogMaxThinkingLevel,
        )
        assertEquals(
            "no declaration → an id rule still applies (mimo caps at high)",
            ThinkingLevel.HIGH,
            model("mimo-v2.5").catalogMaxThinkingLevel,
        )
        assertEquals(
            "a non-reasoning model is OFF regardless of declarations",
            ThinkingLevel.OFF,
            model("gpt-4o", supportsReasoning = false, reasoningEffortValues = listOf("high", "max"))
                .catalogMaxThinkingLevel,
        )
    }

    // ── [T23] new id / declared ceiling vs rule top / provider-scoped custom rule ──
    //
    // GH#356 (deepseek-flash missed the substring rule), ef95c007d (an
    // incomplete effort declaration must not hide Max), GH#306/#311 (a custom
    // rule has to be matchable by PROVIDER, not only by model name — MiniMax M3
    // reached over the Anthropic-compatible protocol).

    /**
     * Rule: the bare `deepseek-flash` id DeepSeek now recommends resolves to the
     * vendor-native sibling shape, on and off — the same contract as the legacy
     * `deepseek-v4-*` alias. An UNDECLARED sibling id (no effort set from the
     * catalog) still lands on the rule, so a relay publishing it under its own
     * name gets the right shape too.
     */
    @Test
    fun `deepseek-flash resolves to the vendor-native sibling shape`() {
        val m = model("deepseek-flash", reasoningEffortValues = listOf("high", "max"))
        val on = capture(m, ThinkingLevel.HIGH)
        assertEquals("enabled", on.getJSONObject("thinking").getString("type"))
        assertEquals("high", on.optString("reasoning_effort", null))
        assertFalse("tier must be a ROOT sibling, never nested: $on", on.getJSONObject("thinking").has("reasoning_effort"))
        val off = capture(m, ThinkingLevel.OFF)
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
        assertFalse("OFF carries no tier on this shape: $off", off.has("reasoning_effort"))

        val undeclared = capture(model("deepseek-flash-lite"), ThinkingLevel.MAX)
        assertEquals("enabled", undeclared.getJSONObject("thinking").getString("type"))
        assertEquals("the catalog rule caps at MAX, so MAX goes out as max", "max", undeclared.optString("reasoning_effort", null))
    }

    /** The anchored scope must not sweep in the chat/reasoner ids whose shape may differ. */
    @Test
    fun `deepseek-chat does not inherit the flash rule`() {
        val body = capture(model("deepseek-chat", supportsReasoning = null), ThinkingLevel.HIGH)
        assertFalse("deepseek-chat must not get the sibling thinking object: $body", body.has("thinking"))
    }

    /**
     * ef95c007d — a declaration topping at XHIGH for a model whose family rule
     * reaches MAX keeps MAX selectable. The ceiling is a pure function of the
     * model, so the picker "round trip" (XHigh → Max → XHigh) resolves the same
     * way every time; nothing about the current selection feeds back into it.
     */
    @Test
    fun `declared xhigh ceiling keeps max reachable and the round trip is stable`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium", "high", "xhigh"))
        assertEquals(ThinkingLevel.MAX, m.catalogMaxThinkingLevel)
        assertEquals(
            "the declared tiers themselves are unchanged — only the ceiling is lifted",
            listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH),
            m.selectableThinkingLevels,
        )
        val provider = OpenAIProvider(apiKey = "k", model = m, basePath = server.url("/v1").toString().trimEnd('/'))
        assertEquals(ThinkingLevel.XHIGH, provider.clampThinkingLevel(ThinkingLevel.XHIGH))
        assertEquals(ThinkingLevel.MAX, provider.clampThinkingLevel(ThinkingLevel.MAX))
        assertEquals("selecting XHigh again after Max must not lower anything", ThinkingLevel.XHIGH, provider.clampThinkingLevel(ThinkingLevel.XHIGH))
        assertEquals(ThinkingLevel.MAX, provider.clampThinkingLevel(ThinkingLevel.ULTRA))
        // The OpenAI-native family trusts its rule over an incomplete declaration
        // and sends the tier the rule knows the backend accepts.
        val body = capture(m, ThinkingLevel.MAX)
        assertEquals("max", body.optString("reasoning_effort", null))
    }

    /** Contrast: an UNRULED model with the same declaration is capped at its declared top. */
    @Test
    fun `an unruled model keeps a narrow declaration as its ceiling`() {
        val m = model("some-relay-reasoner", reasoningEffortValues = listOf("low", "medium", "high"))
        assertEquals(ThinkingLevel.HIGH, m.catalogMaxThinkingLevel)
        assertEquals("high", capture(m, ThinkingLevel.MAX).optString("reasoning_effort", null))
    }

    /**
     * GH#306/#311 — a custom rule is keyed by the PROVIDER INSTANCE, so the same
     * model id resolves differently depending on which instance serves it. The
     * motivating case: MiniMax M3 through an Anthropic-compatible relay needs the
     * DeepSeek-style sibling switch, while the same id elsewhere keeps the
     * built-in (which emits nothing for an undeclared self-reasoning family).
     */
    @Test
    fun `a custom rule is scoped to the provider instance, not the model name`() {
        fun ctx(instanceId: String?) = ThinkingResolveContext(
            modelId = "MiniMax-M3",
            instanceId = instanceId,
            supportsReasoning = true,
            declaredEffortValues = null,
            level = ThinkingLevel.HIGH,
            maxTokens = 4096,
            isOpenRouter = false,
            usesUnifiedReasoningEffort = false,
            isMistral = false,
            isDashScope = false,
            offEffort = null,
        )
        try {
            ThinkingRuleResolver.setCustomRules(
                "inst-anthropic-compat",
                listOf(
                    ThinkingRule(
                        kind = ThinkingRule.Kind.CUSTOM,
                        scope = ThinkingRule.Scope.AllModels,
                        wireFormat = ThinkingWireFormat.DeepSeekSibling,
                        label = "minimax-via-anthropic-compat",
                    ),
                ),
            )
            val scoped = JSONObject()
            val scopedTrace = ThinkingRuleResolver.apply(scoped, ctx("inst-anthropic-compat"))
            assertEquals("enabled", scoped.getJSONObject("thinking").getString("type"))
            assertEquals("high", scoped.getString("reasoning_effort"))
            assertEquals(ThinkingRule.Kind.CUSTOM, scopedTrace.matchedRuleKind)
            assertEquals("minimax-via-anthropic-compat", scopedTrace.matchedRuleLabel)

            val other = JSONObject()
            val otherTrace = ThinkingRuleResolver.apply(other, ctx("inst-other"))
            assertFalse("another instance's model must not pick up the rule: $other", other.has("thinking"))
            assertFalse(other.has("reasoning_effort"))
            assertEquals("openai-compatible-default", otherTrace.matchedRuleLabel)

            val unscoped = JSONObject()
            ThinkingRuleResolver.apply(unscoped, ctx(null))
            assertFalse("no instance → built-ins only", unscoped.has("thinking"))

            // End to end: the provider carries the instance id into the resolver.
            val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
            val provider = OpenAIProvider(apiKey = "k", model = model("MiniMax-M3"), basePath = server.url("/v1").toString().trimEnd('/'))
            provider.thinkingRuleInstanceId = "inst-anthropic-compat"
            runCatching {
                runBlocking {
                    provider.sendMessageClamped(plainHistory(), null, 4096, null, emptyList(), emptyList(), ThinkingLevel.HIGH)
                }
            }
            val wire = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("enabled", wire.getJSONObject("thinking").getString("type"))
        } finally {
            ThinkingRuleResolver.setAllCustomRules(emptyMap())
        }
    }

    // ══════════════ [M13] ThinkingMaxUnreachable — the ceiling is max(rule, declared)
    //
    // ef95c007d. The section above already covers the two named shapes
    // (["low","medium","high"] and ["high","xhigh"] against gpt-5.6-sol's MAX
    // rule). What follows is the part those rows leave open: the ceiling rule is
    // a MAXIMUM of two independent sources, and a max() has four quadrants.
    // Testing two of them leaves the interesting failure — a silent swap of the
    // comparison direction — reachable, because `>` and `<` agree on any pair
    // where one side is absent.

    /**
     * The rule is a MAXIMUM, asserted as a truth table over all four quadrants.
     * Each quadrant had its own bug at some point in this file's history:
     * rule-only was the original behaviour, declared-only was 47dc71b3's fix,
     * declared-above-rule is what that fix enabled, and declared-BELOW-rule is
     * ef95c007d — the one where an incomplete models.dev list hid MAX from the
     * picker for exactly the models the rule exists to describe.
     */
    @Test
    fun `the ceiling is the maximum of the rule top and the declared top`() {
        // (1) rule only — no declaration to consult.
        assertEquals(
            "rule MAX, nothing declared → MAX",
            ThinkingLevel.MAX,
            model("gpt-5.6-sol").catalogMaxThinkingLevel,
        )
        // (2) declaration only — no rule matches this id.
        assertEquals(
            "no rule, declared top HIGH → HIGH",
            ThinkingLevel.HIGH,
            model("some-relay-reasoner", reasoningEffortValues = listOf("low", "high")).catalogMaxThinkingLevel,
        )
        // (3) declaration ABOVE the rule → the declaration wins (47dc71b3).
        assertEquals(
            "rule HIGH, declared MAX → MAX",
            ThinkingLevel.MAX,
            model("mimo-v2.5", reasoningEffortValues = listOf("high", "max")).catalogMaxThinkingLevel,
        )
        // (4) declaration BELOW the rule → the RULE wins (ef95c007d).
        assertEquals(
            "rule MAX, declared HIGH → MAX, never HIGH",
            ThinkingLevel.MAX,
            model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "high")).catalogMaxThinkingLevel,
        )
    }

    /**
     * The item's own example: an incomplete declaration of exactly
     * `["low","medium"]` must not hide MAX. Two tiers is the sparsest realistic
     * shape and the furthest below the rule — if the comparison direction were
     * ever inverted, this is the row with the largest visible drop (MAX → MEDIUM,
     * i.e. three tiers vanish from the picker at once).
     */
    @Test
    fun `a two-tier declaration does not hide Max from a MAX-rule model`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium"))
        assertEquals(
            "the declared top is MEDIUM, but the family rule reaches MAX",
            ThinkingLevel.MEDIUM,
            m.selectableThinkingLevels.last(),
        )
        assertEquals(
            "…and the ceiling must follow the RULE: ${m.reasoningEffortValues}",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
    }

    /**
     * The fix is scoped to the CEILING and must not touch `clampEffort`, whose job
     * is the opposite — stopping an undeclared tier from reaching a backend that
     * 400s on it. The two halves are asserted together because loosening the clamp
     * is the tempting way to "finish" this fix, and it would trade this bug for
     * that one.
     *
     * So for a rule-lifted model the ceiling is MAX (the user can ask) while the
     * WIRE value for a generic-branch id stays clamped onto the declared set. The
     * OpenAI-native branch is the deliberate exception — it trusts its own rule
     * over an incomplete declaration — which is why both are shown here.
     */
    @Test
    fun `lifting the ceiling does not loosen the wire clamp`() {
        val nativeModel = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium"))
        assertEquals(ThinkingLevel.MAX, nativeModel.catalogMaxThinkingLevel)
        assertEquals(
            "openai-native trusts the rule and sends the tier as asked",
            "max",
            capture(nativeModel, ThinkingLevel.MAX).optString("reasoning_effort", null),
        )

        // A model with the SAME declaration on the generic branch: the clamp is
        // untouched, so an undeclared tier is still snapped down.
        val genericModel = model("relay-hosted-reasoner", reasoningEffortValues = listOf("low", "medium"))
        assertEquals(
            "generic branch: MAX is not declared, so the wire value clamps to the declared top",
            "medium",
            capture(genericModel, ThinkingLevel.MAX).optString("reasoning_effort", null),
        )
    }

    /**
     * XHigh → Max → XHigh round trip. The ceiling is a PURE function of the model
     * — nothing about the current selection feeds back into it — so re-selecting a
     * lower tier after the highest must not lower the ceiling or the clamp.
     *
     * Worth an explicit round trip because a ceiling cached per provider instance
     * (a plausible optimisation, given `catalogMaxThinkingLevel` walks the rule
     * list) would pass every single-shot assertion in this file and fail exactly
     * here.
     */
    @Test
    fun `an XHigh to Max to XHigh round trip is stable in both directions`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium"))
        val provider = OpenAIProvider(
            apiKey = "k", model = m, basePath = server.url("/v1").toString().trimEnd('/'),
        )
        val walk = listOf(
            ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.XHIGH,
            ThinkingLevel.MAX, ThinkingLevel.HIGH, ThinkingLevel.MAX, ThinkingLevel.XHIGH,
        )
        for ((step, level) in walk.withIndex()) {
            assertEquals(
                "step $step: the ceiling must not drift as the selection moves",
                ThinkingLevel.MAX,
                m.catalogMaxThinkingLevel,
            )
            assertEquals(
                "step $step: $level must survive the clamp under a MAX ceiling",
                level,
                provider.clampThinkingLevel(level),
            )
        }
        assertEquals(
            "ULTRA collapses onto MAX — it is a client-side concept, never a wire tier",
            ThinkingLevel.MAX,
            provider.clampThinkingLevel(ThinkingLevel.ULTRA),
        )
    }

    /**
     * The precedence that must hold ABOVE the max(): a model that cannot reason is
     * OFF regardless of either source. Checked before the rule chain on purpose —
     * family rules match by id substring, so a broadened rule must not lift the
     * ceiling of that family's non-reasoning members (`mimo-v2.5-tts` / `-asr`
     * against the `mimo` rule is the real case).
     */
    @Test
    fun `a non-reasoning family member is OFF despite both the rule and the declaration`() {
        for (id in listOf("mimo-v2.5-tts", "mimo-v2.5-asr", "gpt-5.6-sol-audio")) {
            assertEquals(
                "$id cannot reason, so no rule or declaration may lift its ceiling",
                ThinkingLevel.OFF,
                model(
                    id,
                    supportsReasoning = false,
                    reasoningEffortValues = listOf("high", "max"),
                ).catalogMaxThinkingLevel,
            )
        }
    }

    /**
     * SOURCE-GREP DRIFT GUARD on the comparison itself. Every behavioural row
     * above reads the ceiling through `catalogMaxThinkingLevel`, and the whole fix
     * is one comparison inside it — `ruleTop.rank > declaredTop.rank`. An inverted
     * or `>=`-ified version of that line is the regression, and it is a one-token
     * edit, so pin the token.
     */
    @Test
    fun `the ceiling comparison still takes the higher of rule and declaration`() {
        val src = com.openminis.app.ProductionSources.read("provider/ThinkingLevelCatalog.kt")
        assertTrue(
            "the ceiling must be a max() of the two sources — this line IS ef95c007d",
            src.contains("return if (ruleTop != null && ruleTop.rank > declaredTop.rank) ruleTop else declaredTop"),
        )
        assertTrue(
            "the non-reasoning check must stay AHEAD of the rule lookup",
            src.indexOf("if (supportsReasoning == false) return ThinkingLevel.OFF") <
                src.indexOf("val ruleTop = ThinkingLevelCatalog.declaredMaxLevel(id)"),
        )
        assertTrue(
            "…and the undeclared fallback must stay the conservative XHIGH default",
            src.contains("return ruleTop ?: ThinkingLevel.XHIGH"),
        )
    }
}
