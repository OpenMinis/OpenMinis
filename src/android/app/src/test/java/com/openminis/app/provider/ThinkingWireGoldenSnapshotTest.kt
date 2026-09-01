package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * GOLDEN SNAPSHOT of the thinking-parameter injection on the Chat Completions path.
 *
 * WHY THIS EXISTS, and how it differs from [ThinkingRulesRegressionTest]: that suite
 * asserts the *rules we knew to look for* — one hand-written assertion per catalogued
 * rule. This file asserts something weaker but far broader: for a matrix of
 * (model × level × endpoint) combinations, the emitted thinking fields are
 * **byte-for-byte what they are today**, whatever that happens to be.
 *
 * That distinction is what makes it useful for the Thinking Rules refactor. The
 * acceptance criterion is "behaviour is byte-for-byte unchanged", but the named suite
 * only covers the vendors it was written for — it says nothing about the OpenAI-native
 * o/gpt-5 branch, the OpenRouter nested shape, or the seed family. Passing it proves
 * "the parts we tested did not change", not "nothing changed". These snapshots pin the
 * ACTUAL current output of every branch, including ones nobody wrote a named rule for.
 *
 * HOW TO USE DURING THE REFACTOR
 *  1. Committed BEFORE the refactor, generated against the OLD code.
 *  2. After the refactor it must still pass unchanged. A diff here is a behaviour
 *     change — either an unintended regression, or an intended fix that must be called
 *     out explicitly in the commit message and updated deliberately.
 *  3. Do NOT regenerate expectations to turn a red test green without first explaining,
 *     in words, why the wire format legitimately changed.
 *
 * Only the thinking-related keys are captured (the rest of the body — messages, model,
 * stream flags — is noise for this purpose and would make the snapshot brittle). Keys
 * are rendered sorted so JSON ordering can never masquerade as a behaviour change.
 *
 * NOT COMPARABLE ROW-BY-ROW WITH THE iOS SNAPSHOT — the two capture different layers,
 * deliberately. iOS calls the pure `injectThinkingParams` directly, so its rows show the
 * injector in isolation. Android's injector is private and reads instance state, so this
 * drives the real `sendMessageClamped`, which means the rows also include two upstream
 * effects: (a) the `catalogMaxThinkingLevel` ceiling — `gpt-5.3` matches no catalog rule
 * so it defaults to XHIGH, which is why MAX/ULTRA render as `"xhigh"` here and `"max"` on
 * iOS; and (b) `explicitOffEffort`, which requires a literal `https://api.openai.com`
 * base, so a MockWebServer URL never qualifies and OFF renders `{}` rather than
 * `{"reasoning_effort":"none"}`. Both are correct for their layer. Each snapshot is an
 * oracle for ITS OWN platform across the refactor; cross-platform parity is what
 * ThinkingRulesRegressionTest asserts.
 *
 * BASELINE MOVED ONCE, DELIBERATELY — [T-thinking-levels-data-driven]. 18 rows changed
 * when `catalogMaxThinkingLevel` began deriving its ceiling from the model's DECLARED
 * effort set (iOS parity, LLMTypes.swift:881-883 / 47dc71b3). Two groups, moving in
 * opposite directions, both correct:
 *
 *   • RAISED to "max" — deepseek-v4, deepseek-v4-unified, glm-declared, sparse-high-max,
 *     venice-deepseek, deepseek-v4-on-openrouter. All declare `["high","max"]`. The
 *     ceiling used to be the XHIGH default, so a MAX request became "xhigh", which is
 *     NOT in the declared set, and the wire clamp then snapped it DOWN to "high" — the
 *     user could never reach the "max" the catalog plainly declares (field report:
 *     deepseek-v4/GLM stuck below max on Android while iOS reached it).
 *
 *   • LOWERED to "high" — gpt5-on-dashscope (`["low","high"]`) and openrouter
 *     (`["low","medium","high"]`). A declared set is authoritative in BOTH directions:
 *     offering XHIGH for a backend that only accepts low/high invites a 400. These rows
 *     previously rode the XHIGH default because `gpt-5.3` / `claude-sonnet-4-6` match no
 *     id rule. iOS resolves both to .high through the same early return.
 *
 * `clampEffort` itself is untouched — the fix is the ceiling, not the clamp.
 *
 * READING THE BASELINE — `mimo/XHIGH` and `seed/XHIGH` record `reasoning_effort:"xhigh"`
 * even though both families 400 on that value (72968c4f). That is correct: the family
 * ceiling is applied UPSTREAM, by the clamp in LLMProvider.streamMessage/sendMessage,
 * so injection is never reached with XHIGH for those ids in production. These rows pin
 * this layer in isolation; the ceiling is covered by the named suite. Do not "fix" the
 * snapshot by clamping here — that would put the ceiling in two places and let them drift.
 */
class ThinkingWireGoldenSnapshotTest {

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

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "Golden",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
    )

    /** Stable textual form: sorted keys at every level, bools distinct from numbers. */
    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${canonical(v.get(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> "\"$v\""
        else -> v.toString()
    }

    /** The keys this layer is allowed to touch. Everything else is request noise. */
    private val watched = listOf(
        "reasoning_effort", "reasoning", "thinking",
        "enable_thinking", "thinking_budget", "extra_body",
    )

    private fun emit(m: LLMModel, level: ThinkingLevel, basePath: String, maxTokens: Int): String {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(apiKey = "test-key", model = m, basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "q")),
                    systemPrompt = null,
                    maxTokens = maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val present = watched.filter { body.has(it) }.sorted()
        return present.joinToString(",", "{", "}") { "$it:${canonical(body.get(it))}" }
    }

    private data class Case(
        val label: String,
        val model: LLMModel,
        val path: String,
        val maxTokens: Int = 8192,
    )

    private fun matrix(): List<Case> = listOf(
        Case("openai-gpt5", model("gpt-5.3", reasoningEffortValues = listOf("none", "low", "medium", "high", "xhigh")), "/v1"),
        Case("openai-o3", model("o3-mini"), "/v1"),
        Case("openai-gpt4o-nonreasoning", model("gpt-4o", supportsReasoning = false), "/v1"),
        Case("openrouter", model("anthropic/claude-sonnet-4-6", reasoningEffortValues = listOf("low", "medium", "high")), "/openrouter.ai/api/v1"),
        Case("qwen", model("qwen3-32b"), "/v1", maxTokens = 16384),
        Case("qwen-tiny-max", model("qwen3-32b"), "/v1", maxTokens = 1),
        Case("deepseek-v4", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("deepseek-v4-unified", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/ark.volces.com/api/v3"),
        Case("glm-declared", model("glm-5.2", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("glm-undeclared", model("glm-4.5-air", supportsReasoning = null), "/v1"),
        Case("mimo", model("mimo-v2.5"), "/v1"),
        Case("agnes", model("agnes-1"), "/v1"),
        Case("seed", model("seed-1.6"), "/ark.volces.com/api/v3"),
        Case("generic-unknown", model("some-relay-model"), "/v1"),
        Case("sparse-high-max", model("vendor-x", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("mistral", model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")), "/mistral.ai/v1"),
        Case("venice-deepseek", model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")), "/api.venice.ai/api/v1"),

        // ---- CROSS-PRODUCT ROWS ----
        // Every case above varies ONE dimension, and that blind spot let a real ordering
        // regression through: hoisting the unified-gateway rule above the qwen and
        // OpenAI-native patterns changed the wire shape for models that match a vendor
        // pattern AND sit on a unified/DashScope endpoint. Single-dimension rows cannot
        // see it, because neither dimension alone is wrong. These rows pin the
        // interaction, which is where rule-ordering bugs actually live.
        Case("qwen-on-unified", model("qwen3-32b"), "/ark.volces.com/api/v3"),
        Case("gpt5-on-dashscope", model("gpt-5.3", reasoningEffortValues = listOf("low", "high")), "/dashscope.aliyuncs.com/compatible-mode/v1"),
        Case("mimo-on-unified", model("mimo-v2.5"), "/ark.volces.com/api/v3"),
        Case("qwen-on-openrouter", model("qwen3-32b"), "/openrouter.ai/api/v1"),
        Case("deepseek-v4-on-openrouter", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/openrouter.ai/api/v1"),
    )

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
    )

    private fun render(): String = buildString {
        for (c in matrix()) {
            for (lv in levels) {
                append("${c.label}/${lv.name} -> ${emit(c.model, lv, server.url(c.path).toString().trimEnd('/'), c.maxTokens)}")
                append('\n')
            }
        }
    }.trimEnd('\n')

    @Test
    fun `golden snapshot of every branch`() {
        assertEquals(
            """
            THINKING WIRE FORMAT CHANGED.

            This is the byte-for-byte oracle for the Thinking Rules refactor. If you are
            mid-refactor and see this fail, the new rule engine emits a different request
            than the old if-return chain for at least one (model, level) pair.

            Do not "fix" this by pasting the new output in. Diff the two blocks, identify
            which branch moved, and either restore parity or justify the change explicitly.
            """.trimIndent(),
            EXPECTED,
            render(),
        )
    }

    companion object {
        /** Generated from the PRE-refactor implementation. See the class doc. */
        private val EXPECTED = """
openai-gpt5/OFF -> {}
openai-gpt5/LOW -> {reasoning_effort:"low"}
openai-gpt5/MEDIUM -> {reasoning_effort:"medium"}
openai-gpt5/HIGH -> {reasoning_effort:"high"}
openai-gpt5/XHIGH -> {reasoning_effort:"xhigh"}
openai-gpt5/MAX -> {reasoning_effort:"xhigh"}
openai-gpt5/ULTRA -> {reasoning_effort:"xhigh"}
openai-o3/OFF -> {}
openai-o3/LOW -> {reasoning_effort:"low"}
openai-o3/MEDIUM -> {reasoning_effort:"medium"}
openai-o3/HIGH -> {reasoning_effort:"high"}
openai-o3/XHIGH -> {reasoning_effort:"xhigh"}
openai-o3/MAX -> {reasoning_effort:"xhigh"}
openai-o3/ULTRA -> {reasoning_effort:"xhigh"}
openai-gpt4o-nonreasoning/OFF -> {}
openai-gpt4o-nonreasoning/LOW -> {}
openai-gpt4o-nonreasoning/MEDIUM -> {}
openai-gpt4o-nonreasoning/HIGH -> {}
openai-gpt4o-nonreasoning/XHIGH -> {}
openai-gpt4o-nonreasoning/MAX -> {}
openai-gpt4o-nonreasoning/ULTRA -> {}
openrouter/OFF -> {}
openrouter/LOW -> {reasoning:{effort:"low"}}
openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
openrouter/HIGH -> {reasoning:{effort:"high"}}
openrouter/XHIGH -> {reasoning:{effort:"high"}}
openrouter/MAX -> {reasoning:{effort:"high"}}
openrouter/ULTRA -> {reasoning:{effort:"high"}}
qwen/OFF -> {}
qwen/LOW -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:4096},thinking_budget:4096}
qwen/MEDIUM -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/HIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/XHIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/MAX -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/ULTRA -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen-tiny-max/OFF -> {}
qwen-tiny-max/LOW -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/MEDIUM -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/HIGH -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/XHIGH -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/MAX -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/ULTRA -> {enable_thinking:true,extra_body:{enable_thinking:true}}
deepseek-v4/OFF -> {thinking:{type:"disabled"}}
deepseek-v4/LOW -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/MEDIUM -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/HIGH -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/XHIGH -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/MAX -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-v4/ULTRA -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-v4-unified/OFF -> {reasoning_effort:"minimal"}
deepseek-v4-unified/LOW -> {reasoning_effort:"high"}
deepseek-v4-unified/MEDIUM -> {reasoning_effort:"high"}
deepseek-v4-unified/HIGH -> {reasoning_effort:"high"}
deepseek-v4-unified/XHIGH -> {reasoning_effort:"high"}
deepseek-v4-unified/MAX -> {reasoning_effort:"max"}
deepseek-v4-unified/ULTRA -> {reasoning_effort:"max"}
glm-declared/OFF -> {}
glm-declared/LOW -> {reasoning_effort:"high"}
glm-declared/MEDIUM -> {reasoning_effort:"high"}
glm-declared/HIGH -> {reasoning_effort:"high"}
glm-declared/XHIGH -> {reasoning_effort:"high"}
glm-declared/MAX -> {reasoning_effort:"max"}
glm-declared/ULTRA -> {reasoning_effort:"max"}
glm-undeclared/OFF -> {}
glm-undeclared/LOW -> {}
glm-undeclared/MEDIUM -> {}
glm-undeclared/HIGH -> {}
glm-undeclared/XHIGH -> {}
glm-undeclared/MAX -> {}
glm-undeclared/ULTRA -> {}
mimo/OFF -> {}
mimo/LOW -> {reasoning_effort:"low"}
mimo/MEDIUM -> {reasoning_effort:"medium"}
mimo/HIGH -> {reasoning_effort:"high"}
mimo/XHIGH -> {reasoning_effort:"high"}
mimo/MAX -> {reasoning_effort:"high"}
mimo/ULTRA -> {reasoning_effort:"high"}
agnes/OFF -> {}
agnes/LOW -> {reasoning_effort:"low"}
agnes/MEDIUM -> {reasoning_effort:"medium"}
agnes/HIGH -> {reasoning_effort:"high"}
agnes/XHIGH -> {reasoning_effort:"high"}
agnes/MAX -> {reasoning_effort:"high"}
agnes/ULTRA -> {reasoning_effort:"high"}
seed/OFF -> {reasoning_effort:"minimal"}
seed/LOW -> {reasoning_effort:"low"}
seed/MEDIUM -> {reasoning_effort:"medium"}
seed/HIGH -> {reasoning_effort:"high"}
seed/XHIGH -> {reasoning_effort:"high"}
seed/MAX -> {reasoning_effort:"high"}
seed/ULTRA -> {reasoning_effort:"high"}
generic-unknown/OFF -> {}
generic-unknown/LOW -> {reasoning_effort:"low"}
generic-unknown/MEDIUM -> {reasoning_effort:"medium"}
generic-unknown/HIGH -> {reasoning_effort:"high"}
generic-unknown/XHIGH -> {reasoning_effort:"xhigh"}
generic-unknown/MAX -> {reasoning_effort:"xhigh"}
generic-unknown/ULTRA -> {reasoning_effort:"xhigh"}
sparse-high-max/OFF -> {}
sparse-high-max/LOW -> {reasoning_effort:"high"}
sparse-high-max/MEDIUM -> {reasoning_effort:"high"}
sparse-high-max/HIGH -> {reasoning_effort:"high"}
sparse-high-max/XHIGH -> {reasoning_effort:"high"}
sparse-high-max/MAX -> {reasoning_effort:"max"}
sparse-high-max/ULTRA -> {reasoning_effort:"max"}
mistral/OFF -> {}
mistral/LOW -> {}
mistral/MEDIUM -> {}
mistral/HIGH -> {}
mistral/XHIGH -> {}
mistral/MAX -> {}
mistral/ULTRA -> {}
venice-deepseek/OFF -> {}
venice-deepseek/LOW -> {reasoning_effort:"low"}
venice-deepseek/MEDIUM -> {reasoning_effort:"low"}
venice-deepseek/HIGH -> {reasoning_effort:"high"}
venice-deepseek/XHIGH -> {reasoning_effort:"high"}
venice-deepseek/MAX -> {reasoning_effort:"max"}
venice-deepseek/ULTRA -> {reasoning_effort:"max"}
qwen-on-unified/OFF -> {}
qwen-on-unified/LOW -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:4096},thinking_budget:4096}
qwen-on-unified/MEDIUM -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/HIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/XHIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/MAX -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/ULTRA -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
gpt5-on-dashscope/OFF -> {}
gpt5-on-dashscope/LOW -> {reasoning_effort:"low"}
gpt5-on-dashscope/MEDIUM -> {reasoning_effort:"medium"}
gpt5-on-dashscope/HIGH -> {reasoning_effort:"high"}
gpt5-on-dashscope/XHIGH -> {reasoning_effort:"high"}
gpt5-on-dashscope/MAX -> {reasoning_effort:"high"}
gpt5-on-dashscope/ULTRA -> {reasoning_effort:"high"}
mimo-on-unified/OFF -> {}
mimo-on-unified/LOW -> {reasoning_effort:"low"}
mimo-on-unified/MEDIUM -> {reasoning_effort:"medium"}
mimo-on-unified/HIGH -> {reasoning_effort:"high"}
mimo-on-unified/XHIGH -> {reasoning_effort:"high"}
mimo-on-unified/MAX -> {reasoning_effort:"high"}
mimo-on-unified/ULTRA -> {reasoning_effort:"high"}
qwen-on-openrouter/OFF -> {}
qwen-on-openrouter/LOW -> {reasoning:{effort:"low"}}
qwen-on-openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
qwen-on-openrouter/HIGH -> {reasoning:{effort:"high"}}
qwen-on-openrouter/XHIGH -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/MAX -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/ULTRA -> {reasoning:{effort:"xhigh"}}
deepseek-v4-on-openrouter/OFF -> {}
deepseek-v4-on-openrouter/LOW -> {reasoning:{effort:"low"}}
deepseek-v4-on-openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
deepseek-v4-on-openrouter/HIGH -> {reasoning:{effort:"high"}}
deepseek-v4-on-openrouter/XHIGH -> {reasoning:{effort:"xhigh"}}
deepseek-v4-on-openrouter/MAX -> {reasoning:{effort:"max"}}
deepseek-v4-on-openrouter/ULTRA -> {reasoning:{effort:"max"}}
        """.trimIndent()
    }
}
