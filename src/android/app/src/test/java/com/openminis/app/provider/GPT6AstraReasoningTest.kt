package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-gpt6-astra-effort] Android counterpart to iOS `GPT6AstraReasoningTests`.
 *
 * A user picked "xhigh" on gpt-6-astra and iOS put `reasoning: {effort: "low"}`
 * on the wire. The cause was a data gap, not a logic bug: `LLMModel.gpt6Astra`
 * never declared `supportsReasoning`, the model was too new to be matched by
 * the bundled models.dev catalog that normally fills that flag in, and the
 * Responses builder's "Codex OAuth requires a reasoning object" fallback
 * hard-codes effort "low". The user's level was never consulted.
 *
 * Android's static entry already declares the flag, so the reported bug does
 * not reproduce here. That is worth pinning rather than asserting in prose:
 * the protection is one boolean in a list of model literals, and nothing about
 * deleting it would look wrong in review. These tests fail if it goes.
 *
 * Where the two platforms actually differ: both ceilings bail only on
 * `supportsReasoning == false` (iOS LLMTypes.catalogMaxThinkingLevel is the
 * same), so an undeclared (null) flag reaches MAX on both. iOS's `?? false`
 * lives one layer down, in the WIRE guard `reasoningEffort(for:)`, which
 * returned nil for a nil flag and dropped into the Codex "low" fallback.
 * Android's Responses builder has no supportsReasoning guard on its enabled
 * path, so the same nil flag never reached that fallback here. The third
 * test pins the ceiling half; the wire tests pin the builder half.
 */
class GPT6AstraReasoningTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private val astra: LLMModel
        get() = OpenAIModelsApi.fetchModelsOAuth().first { it.id == "gpt-6-astra" }

    /**
     * Capture the Responses-API body the production builder emits.
     *
     * Uses `useResponsesAPI = true` on an API-key provider rather than the
     * OAuth constructor: the Codex branch hard-codes
     * chatgpt.com/backend-api/codex/responses, so it cannot be pointed at a
     * MockWebServer, but both branches serialize the SAME body before the URL
     * split. The reasoning block under test is therefore identical.
     */
    private fun responsesBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "question")),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun effortOf(body: JSONObject): String? =
        body.optJSONObject("reasoning")?.optString("effort")?.takeIf { it.isNotEmpty() }

    /** iOS target 1: the static flag is the load-bearing guarantee. */
    @Test
    fun `the shipped codex oauth entry declares supportsReasoning`() {
        assertEquals(
            "gpt-6-astra must declare supportsReasoning=true in the static Codex OAuth " +
                "list — without it the Responses builder falls back to effort 'low' and " +
                "silently ignores the user's thinking level (iOS 682029976)",
            true,
            astra.supportsReasoning,
        )
    }

    /** iOS target 2: the catalog ceiling. */
    @Test
    fun `catalog ceiling is max`() {
        assertEquals(ThinkingLevel.MAX, astra.catalogMaxThinkingLevel)
    }

    /**
     * iOS target 4, adapted: the pre-fix collapse.
     *
     * Both platforms' ceilings use a strict `== false`, so a null flag still
     * reaches MAX through the id rule; iOS's collapse happened in the wire
     * guard, not here. Pinning the ceiling keeps a future "simplification" to
     * `!= true` from introducing a collapse at THIS layer.
     */
    @Test
    fun `an undeclared flag still reaches max rather than collapsing to off`() {
        val undeclared = astra.copy(supportsReasoning = null)
        assertNotEquals(
            "a null supportsReasoning must NOT collapse the ceiling to OFF — that was " +
                "the iOS failure mode (?? false); Android's guard is `== false` by design",
            ThinkingLevel.OFF,
            undeclared.catalogMaxThinkingLevel,
        )
        assertEquals(ThinkingLevel.MAX, undeclared.catalogMaxThinkingLevel)
        // A model that genuinely cannot reason must still be OFF.
        assertEquals(
            ThinkingLevel.OFF,
            astra.copy(supportsReasoning = false).catalogMaxThinkingLevel,
        )
    }

    /**
     * iOS target 3: every level reaches the wire as itself.
     *
     * This is the assertion that would have caught the reported bug: under it,
     * every level produced "low".
     */
    @Test
    fun `every thinking level maps to its own wire effort`() {
        val expected = mapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high",
            ThinkingLevel.XHIGH to "xhigh",
            ThinkingLevel.MAX to "max",
            // ULTRA is client-side only; the shared mapping sends "max" and the
            // literal "ultra" is rejected by the backend. Deliberately NOT
            // special-cased for gpt-6-astra.
            ThinkingLevel.ULTRA to "max",
        )
        for ((level, wire) in expected) {
            val body = responsesBody(astra, level)
            assertEquals(
                "gpt-6-astra at $level must send effort '$wire': $body",
                wire,
                effortOf(body),
            )
        }
    }

    /**
     * The regression in its original shape: xhigh must not arrive as "low".
     *
     * Separate from the table above so a failure names the reported symptom
     * directly instead of surfacing as one row of a loop.
     */
    @Test
    fun `xhigh does not collapse to low`() {
        val body = responsesBody(astra, ThinkingLevel.XHIGH)
        assertNotEquals(
            "the reported bug: user picked xhigh, wire carried 'low': $body",
            "low",
            effortOf(body),
        )
        assertEquals("xhigh", effortOf(body))
    }

    /**
     * The enriched shape, which is what a real device actually runs.
     *
     * `fetchModelsOAuth()` pipes the static list through
     * `ModelsDevApi.enrichModels`, and the bundled catalog now carries
     * gpt-6-astra with effort tiers low/medium/high/xhigh/max (iOS 2ffa7e071).
     * In a plain JVM unit test that enrichment silently no-ops — `loadRegistry`
     * needs an Android Context for assets — so the entry above arrives with
     * null tiers and the tests exercise the UN-enriched shape. Both shapes must
     * be safe, so this pins the enriched one explicitly rather than leaving the
     * device path untested: with the tiers declared, the ceiling comes from
     * the declaration instead of the id rule. (The Responses builder applies
     * only the mimo/agnes clampEffortForModel, not the declared-tier
     * clampEffort — that runs on the Chat Completions path — so xhigh passes
     * through here regardless of the declaration.)
     */
    @Test
    fun `the enriched device shape also reaches max and passes xhigh through`() {
        val enriched = astra.copy(
            reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
        )
        assertEquals(
            "declared tiers must put the ceiling at MAX",
            ThinkingLevel.MAX,
            enriched.catalogMaxThinkingLevel,
        )
        assertEquals(
            "every declared tier must be individually selectable",
            listOf(
                ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH,
                ThinkingLevel.XHIGH, ThinkingLevel.MAX,
            ),
            enriched.selectableThinkingLevels,
        )
        assertEquals("xhigh", effortOf(responsesBody(enriched, ThinkingLevel.XHIGH)))
        assertEquals("max", effortOf(responsesBody(enriched, ThinkingLevel.ULTRA)))
    }

    /**
     * Negative control, stated precisely. A model that cannot reason gets its
     * level clamped to OFF by `clampThinkingLevel` before the builder runs;
     * on the API-key Responses path (`isOAuth` false) that means NO
     * `reasoning` key at all — not "low". The Codex-OAuth "low" fallback is
     * only reachable with a real OAuth provider, whose URL is hard-coded and
     * cannot be pointed at MockWebServer. So this proves the switch from
     * enabled to disabled changes the body, and that the user's tier never
     * leaks through; it does not, and cannot here, reproduce the "low".
     */
    @Test
    fun `a non-reasoning model sends no reasoning block on this path`() {
        val body = responsesBody(astra.copy(supportsReasoning = false), ThinkingLevel.XHIGH)
        assertNull(
            "clampThinkingLevel drives a non-reasoning model to OFF, and the API-key " +
                "Responses path omits the reasoning object entirely: $body",
            effortOf(body),
        )
    }
}
