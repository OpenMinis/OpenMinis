package com.openminis.app.provider

import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-xai-priority]
 *
 * Regression lock for xAI Priority Processing, which rides the app-level Fast
 * Mode toggle (FastModePrefs) that Codex already uses rather than a
 * per-provider switch.
 *
 * Injection requires BOTH halves, and each fails in its own direction:
 *
 *  1. Provider eligibility WITHOUT the toggle must stay silent — otherwise
 *     every xAI user is billed at the premium tier without ever asking.
 *  2. The toggle WITHOUT eligibility must stay silent — `service_tier` is an
 *     xAI extension, and an OpenAI-compatible relay that validates unknown body
 *     keys returns 400, so a user who flips Fast Mode for their Codex model
 *     would break unrelated providers.
 *
 * Both must hold at REQUEST-BUILD time, not construction time: the toggle is
 * read per request so a mid-session flip applies to the very next call.
 *
 * Asserted on the JSON body rather than a mocked HTTP exchange, matching
 * ToolResultImageSerializationTest: the whole question is body construction.
 */
class XAIPriorityProcessingTest {

    private val model = LLMModel(
        id = "grok-4",
        displayName = "Grok 4",
        provider = "xai",
    )

    @After
    fun tearDown() {
        // The prefs object is a process-wide singleton; leaving it on would
        // leak into any other test that builds a request body.
        FastModePrefs.setCachedEnabledForTest(false)
    }

    private fun provider(eligible: Boolean, useResponsesAPI: Boolean = false) =
        OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = "https://api.x.ai/v1",
            useResponsesAPI = useResponsesAPI,
        ).also { it.supportsPriorityProcessing = eligible }

    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))

    private fun chatBody(eligible: Boolean, fastMode: Boolean) = run {
        FastModePrefs.setCachedEnabledForTest(fastMode)
        provider(eligible).buildRequestBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )
    }

    private fun responsesBody(eligible: Boolean, fastMode: Boolean) = run {
        FastModePrefs.setCachedEnabledForTest(fastMode)
        provider(eligible, useResponsesAPI = true).buildResponsesAPIBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            imageParts = emptyList(),
        )
    }

    // ---- Chat Completions (the endpoint xAI actually resolves to) ----

    @Test
    fun `chat body carries priority when eligible and Fast Mode is on`() {
        val body = chatBody(eligible = true, fastMode = true)
        assertTrue(
            "service_tier must be present for an xAI provider with Fast Mode on",
            body.has("service_tier"),
        )
        assertEquals("priority", body.getString("service_tier"))
    }

    @Test
    fun `chat body omits service_tier when Fast Mode is off`() {
        assertFalse(
            "an eligible provider must still stay on the standard tier while the " +
                "global toggle is off — nobody is billed at the premium rate by default",
            chatBody(eligible = true, fastMode = false).has("service_tier"),
        )
    }

    @Test
    fun `chat body omits service_tier for an ineligible provider even with Fast Mode on`() {
        assertFalse(
            "service_tier is an xAI extension — leaking it into another vendor's " +
                "body would 400 on a relay that validates unknown keys",
            chatBody(eligible = false, fastMode = true).has("service_tier"),
        )
    }

    @Test
    fun `a plain provider never carries service_tier`() {
        assertFalse(chatBody(eligible = false, fastMode = false).has("service_tier"))
    }

    // ---- Responses API (belt-and-braces; xAI does not use it today) ----

    @Test
    fun `responses body carries priority when eligible and Fast Mode is on`() {
        assertEquals(
            "priority",
            responsesBody(eligible = true, fastMode = true).getString("service_tier"),
        )
    }

    @Test
    fun `responses body omits service_tier when Fast Mode is off`() {
        assertFalse(responsesBody(eligible = true, fastMode = false).has("service_tier"))
    }

    // ---- The toggle is read per REQUEST, not per construction ----

    @Test
    fun `flipping Fast Mode applies to the very next request on the same provider`() {
        val p = provider(eligible = true)
        fun body() = p.buildRequestBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )

        FastModePrefs.setCachedEnabledForTest(false)
        assertFalse("built while off", body().has("service_tier"))

        FastModePrefs.setCachedEnabledForTest(true)
        assertEquals(
            "the SAME provider instance must pick up the flip — the toggle is read " +
                "at build time so offload / title-gen calls honour it too",
            "priority",
            body().getString("service_tier"),
        )

        FastModePrefs.setCachedEnabledForTest(false)
        assertFalse("and back off again", body().has("service_tier"))
    }
}
