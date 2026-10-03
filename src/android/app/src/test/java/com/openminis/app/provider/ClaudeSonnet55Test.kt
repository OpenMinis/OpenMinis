package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.effectiveInputModalities
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [T-anthropic-sonnet55] Claude Sonnet 5.5 (`claude-sonnet-5-5`), CLIProxyAPI
 * registry 9a3b869f; Android port of iOS 965ff194d / ClaudeSonnet55Tests.swift.
 * Pins the catalog entry, the MAX ceiling (and its scope), the Claude-5 wire
 * path it shares with Sonnet 5, the bundled models.dev snapshot, and the
 * claude-cli fingerprint upstream serves it under.
 */
class ClaudeSonnet55Test {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private val sonnet55: LLMModel get() = LLMModel.claudeSonnet55

    // ── Catalog entry ────────────────────────────────────────────────────

    @Test
    fun `sonnet 5 5 is shipped in the anthropic list right before sonnet 5`() {
        val ids = LLMModel.allAnthropic.map { it.id }
        val i = ids.indexOf("claude-sonnet-5-5")
        assertTrue("claude-sonnet-5-5 missing from allAnthropic", i >= 0)
        assertEquals("claude-sonnet-5", ids[i + 1])
        assertEquals("Claude Sonnet 5.5", sonnet55.displayName)
        assertEquals("Anthropic", sonnet55.provider)
        assertEquals(1_000_000, sonnet55.contextWindow)
        assertEquals("128K upstream, not Sonnet 5's static 64K", 128_000, sonnet55.maxOutputTokens)
        assertEquals(true, sonnet55.supportsReasoning)
    }

    @Test
    fun `sonnet 5 5 takes images through the anthropic default`() {
        assertEquals(true, sonnet55.effectiveInputModalities?.contains("image"))
    }

    // ── Thinking ceiling ─────────────────────────────────────────────────

    @Test
    fun `the ceiling is max even when models dev has no entry for it`() {
        // The static entry declares no effort tiers, i.e. exactly the "lookup
        // missed" case: without the rule this falls to the XHIGH default.
        assertTrue(sonnet55.selectableThinkingLevels.isEmpty())
        assertEquals(ThinkingLevel.MAX, sonnet55.catalogMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-sonnet-5-5"))
        assertEquals("dotted spelling", ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("claude-sonnet-5.5"))
    }

    @Test
    fun `the rule is scoped to 5 5 direct ids`() {
        assertNull(
            "vendor-prefixed relay ids speak their own ladder; a rule must not raise them",
            ThinkingLevelCatalog.declaredMaxLevel("anthropic/claude-sonnet-5.5"),
        )
        assertNull("claude-sonnet-5 resolves from the catalog, not a rule", ThinkingLevelCatalog.declaredMaxLevel("claude-sonnet-5"))
    }

    // ── Wire: same Claude-5 path as Sonnet 5 ─────────────────────────────

    @Test
    fun `sonnet 5 5 shares sonnet 5's wire gates`() {
        for (id in listOf("claude-sonnet-5-5", "claude-sonnet-5")) {
            assertTrue("$id rejects temperature", AnthropicProvider.modelRejectsTemperature(id))
            assertTrue("$id uses adaptive thinking", AnthropicProvider.modelUsesAdaptiveThinking(id))
            assertFalse("$id 400s on thinking.type=disabled", AnthropicProvider.modelAcceptsExplicitThinkingDisabled(id))
        }
    }

    private fun anthropicBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msg_1","type":"message","role":"assistant","content":[],""" +
                    """"model":"${model.id}","stop_reason":"end_turn",""" +
                    """"usage":{"input_tokens":1,"output_tokens":1}}""",
            ),
        )
        val provider = AnthropicProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/").toString().trimEnd('/'),
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

    @Test
    fun `max reaches the wire as adaptive thinking with effort max`() {
        val body = anthropicBody(sonnet55, ThinkingLevel.MAX)
        assertEquals("claude-sonnet-5-5", body.optString("model"))
        val thinking = body.optJSONObject("thinking")
        assertNotNull(thinking)
        assertEquals("adaptive", thinking!!.optString("type"))
        assertEquals("max", body.optJSONObject("output_config")?.optString("effort"))
        assertFalse("no temperature on Claude 5", body.has("temperature"))
    }

    @Test
    fun `off sends no disabled literal`() {
        val body = anthropicBody(sonnet55, ThinkingLevel.OFF)
        assertNotEquals("disabled", body.optJSONObject("thinking")?.optString("type"))
    }

    // ── Bundled models.dev snapshot ──────────────────────────────────────

    @Test
    fun `the bundled snapshot carries sonnet 5 5 with its official tiers`() {
        val root = ProductionSources.mainRoot() ?: error("source root not found")
        // src/main/java/com/openminis/app -> src/main/assets
        val assets = File(root, "../../../../assets/models-dev-api.json").canonicalFile
        val m = JSONObject(assets.readText()).getJSONObject("anthropic").getJSONObject("models")
            .getJSONObject("claude-sonnet-5-5")
        assertEquals(1_000_000, m.getJSONObject("limit").getInt("context"))
        assertEquals(128_000, m.getJSONObject("limit").getInt("output"))
        assertTrue(m.getString("release_date") >= "2026-09-28")
        val input = m.getJSONObject("modalities").getJSONArray("input").let { a -> List(a.length()) { a.getString(it) } }
        assertTrue("image" in input)
        val options = m.getJSONArray("reasoning_options")
        val efforts = (0 until options.length()).map { options.getJSONObject(it) }
            .first { it.optString("type") == "effort" }.getJSONArray("values")
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), List(efforts.length()) { efforts.getString(it) })
    }

    // ── Claude Code fingerprint ──────────────────────────────────────────

    @Test
    fun `the claude-cli fingerprint is at least upstream's 2_1_280`() {
        val src = ProductionSources.read("auth/ClaudeCliMimicryHeaders.kt")
        val v = Regex(""""User-Agent" to "claude-cli/(\d+)\.(\d+)\.(\d+)""").find(src)!!.groupValues.drop(1).map { it.toInt() }
        val (a, b, c) = v
        assertTrue("claude-cli/$a.$b.$c < 2.1.280", a > 2 || (a == 2 && (b > 1 || (b == 1 && c >= 280))))
    }
}
