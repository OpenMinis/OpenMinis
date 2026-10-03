package com.openminis.app.provider.thinking

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [OpenMinis#377] The OFF tier that goes on the wire must be one the MODEL
 * accepts, not just one the VENDOR documents.
 *
 * Reported on Android: Responses API + gpt-6-astra with reasoning effort set to
 * `low`. Compaction hardcodes thinking OFF (it must not spend the output budget
 * on hidden reasoning), the official-OpenAI off tier is `"none"`, and
 * gpt-6-astra mandates reasoning — so every compact died with
 * `[400] reasoning.effort: 'none' is not supported`, including all four halving
 * retries (963 → 481 → 240 → 120 messages). Switching to Chat Completions +
 * gpt-5.6 worked, because that model does accept `none`.
 *
 * These assertions are on the REQUEST BODY rather than on the clamp helper
 * (covered by OffEffortClampTest) because the defect was never in the
 * arithmetic — it was that the OFF branch never consulted the model at all,
 * while the enabled branch beside it always had.
 */
class Issue377OffEffortWireTest {

    /** gpt-6-astra as the catalog reports it: reasoning mandatory, no `none`. */
    private val gpt6Astra = LLMModel(
        id = "gpt-6-astra",
        displayName = "GPT-6 Astra",
        provider = "OpenAI",
        supportsReasoning = true,
        reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max"),
    )

    /** A model with no catalog effort data — the zero-regression control. */
    private val undeclared = LLMModel(
        id = "gpt-5.3",
        displayName = "GPT-5.3",
        provider = "OpenAI",
        supportsReasoning = true,
    )

    private fun provider(m: LLMModel, responses: Boolean) = OpenAIProvider(
        apiKey = "k",
        model = m,
        basePath = "https://api.openai.com/v1",
        useResponsesAPI = responses,
    )

    private fun responsesBody(m: LLMModel): JSONObject =
        provider(m, responses = true).buildResponsesAPIBody(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "summarize this")),
            systemPrompt = null,
            maxTokens = 4096,
            stream = false,
            thinkingLevel = ThinkingLevel.OFF,
        )

    private fun chatBody(m: LLMModel): JSONObject =
        provider(m, responses = false).buildRequestBody(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "summarize this")),
            systemPrompt = null,
            maxTokens = 4096,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )

    private fun nestedEffort(b: JSONObject): String? =
        b.optJSONObject("reasoning")?.optString("effort")?.takeIf { it.isNotEmpty() }

    private fun rootEffort(b: JSONObject): String? =
        b.optString("reasoning_effort").takeIf { it.isNotEmpty() }

    // ── Responses API: the path the issue was filed against ──────────────

    @Test
    fun `responses OFF on a model without none sends its lowest tier`() {
        assertEquals("low", nestedEffort(responsesBody(gpt6Astra)))
    }

    @Test
    fun `responses OFF never sends the value the model rejects`() {
        // Stated separately from the positive assertion: "not none" is the
        // property that keeps the request legal, and it must hold even if the
        // chosen replacement tier changes later.
        assertFalse(
            "gpt-6-astra rejects effort=none with HTTP 400",
            nestedEffort(responsesBody(gpt6Astra)) == "none",
        )
    }

    @Test
    fun `responses OFF is unchanged for a model that declares nothing`() {
        // No catalog data means no basis to override the vendor's off tier —
        // behaviour here must be byte-identical to before the fix.
        assertEquals("none", nestedEffort(responsesBody(undeclared)))
    }

    // ── Chat Completions: deliberately NOT clamped ───────────────────────

    /**
     * The OpenAI-native Chat arm keeps sending its off tier unconditionally,
     * and that is not an oversight. Clamping it was implemented and reverted:
     * models.dev lists `none` for only ~37% of the entries that declare effort
     * tiers (1209/3243 in the bundled catalog), and o1/o3/o4-mini declare
     * exactly `[low, medium, high]` — indistinguishable from gpt-6-astra's
     * `[low…max]` — yet have always accepted `none`. No rule over catalog data
     * alone separates "forgot to list none" from "rejects none", so clamping
     * here would change the wire format for the whole o-series on a guess.
     *
     * The reported 400 is on the Responses path, which is where the fix lives.
     * Pinned here so the asymmetry is a decision on record, not a gap.
     */
    @Test
    fun `chat OFF stays unconditional for the OpenAI-native family`() {
        assertEquals("none", rootEffort(chatBody(gpt6Astra)))
        assertEquals("none", rootEffort(chatBody(undeclared)))
    }
}
