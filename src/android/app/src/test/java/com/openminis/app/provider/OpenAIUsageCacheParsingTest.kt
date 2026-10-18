package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.parseChatCompletionsUsage
import com.openminis.app.provider.openai.parseResponsesAPIUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-usage-cache-hit-rate] The usage parsers must hand the app a *fresh-only*
 * input count, with cached tokens living exclusively in cacheReadInputTokens.
 *
 * The consumer side sums `totalInput = inputTokens + cacheRead + cacheCreation`
 * (UsageStatsScreen) and divides `cacheRead / totalInput`. If the parser leaves
 * the cached portion inside inputTokens, that portion is counted twice in the
 * denominator and the displayed hit rate collapses (user data: 76% real hit
 * rate displayed as 43.2%; the DeepSeek 99% case displayed as ~48%).
 *
 * These tests pin the contract at the parser boundary so the arithmetic can
 * never silently regress again — the previous code carried a `freshInput`
 * variable that was computed but never returned, which is invisible in review
 * without a value-level assertion.
 */
class OpenAIUsageCacheParsingTest {
    private val provider = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel.gpt4oMini,
        basePath = "http://localhost",
    )

    // -- Chat Completions --

    @Test
    fun `chat completions subtracts cached tokens so inputTokens is fresh only`() {
        val usage = JSONObject(
            """
            {"prompt_tokens": 1000, "completion_tokens": 50,
             "prompt_tokens_details": {"cached_tokens": 800}}
            """.trimIndent(),
        )
        val parsed = provider.parseChatCompletionsUsage(usage)
        assertEquals(200, parsed.inputTokens)
        assertEquals(800, parsed.cacheReadInputTokens)
        // latestContextTokens stays the FULL prompt — that IS the context size.
        assertEquals(1000, parsed.latestContextTokens)
        // The regression: input + cacheRead must reconstruct prompt_tokens exactly
        // once. Doubling here is what deflated the hit rate.
        assertEquals(1000, parsed.inputTokens + (parsed.cacheReadInputTokens ?: 0))
    }

    @Test
    fun `chat completions reads deepseek prompt_cache_hit_tokens`() {
        // DeepSeek reports hits outside prompt_tokens_details; without the
        // fallback it looked like DeepSeek never cached even when it did.
        val usage = JSONObject(
            """
            {"prompt_tokens": 2000, "completion_tokens": 120,
             "prompt_cache_hit_tokens": 1980}
            """.trimIndent(),
        )
        val parsed = provider.parseChatCompletionsUsage(usage)
        assertEquals(20, parsed.inputTokens)
        assertEquals(1980, parsed.cacheReadInputTokens)
        assertEquals(2000, parsed.inputTokens + (parsed.cacheReadInputTokens ?: 0))
    }

    @Test
    fun `chat completions without cache fields keeps promptTokens intact`() {
        val usage = JSONObject("""{"prompt_tokens": 500, "completion_tokens": 10}""")
        val parsed = provider.parseChatCompletionsUsage(usage)
        assertEquals(500, parsed.inputTokens)
        assertNull(parsed.cacheReadInputTokens)
        assertEquals(500, parsed.latestContextTokens)
    }

    @Test
    fun `chat completions dirty cached larger than prompt never goes negative`() {
        // Defensive: a relay reporting cached > prompt must not produce a
        // negative fresh count (that would corrupt every downstream sum).
        val usage = JSONObject(
            """
            {"prompt_tokens": 100, "completion_tokens": 5,
             "prompt_tokens_details": {"cached_tokens": 150}}
            """.trimIndent(),
        )
        val parsed = provider.parseChatCompletionsUsage(usage)
        assertTrue(parsed.inputTokens >= 0)
        assertEquals(150, parsed.cacheReadInputTokens)
    }

    // -- Responses API --

    @Test
    fun `responses api subtracts cached tokens so inputTokens is fresh only`() {
        val usage = JSONObject(
            """
            {"input_tokens": 4000, "output_tokens": 80,
             "input_tokens_details": {"cached_tokens": 3000}}
            """.trimIndent(),
        )
        val parsed = provider.parseResponsesAPIUsage(usage)
        assertEquals(1000, parsed.inputTokens)
        assertEquals(3000, parsed.cacheReadInputTokens)
        assertEquals(4000, parsed.latestContextTokens)
        assertEquals(4000, parsed.inputTokens + (parsed.cacheReadInputTokens ?: 0))
    }

    @Test
    fun `responses api without cache details keeps inputTokens intact`() {
        val usage = JSONObject("""{"input_tokens": 900, "output_tokens": 30}""")
        val parsed = provider.parseResponsesAPIUsage(usage)
        assertEquals(900, parsed.inputTokens)
        assertNull(parsed.cacheReadInputTokens)
    }

    // -- Consumer arithmetic, pinned end to end --

    @Test
    fun `hit rate over parsed usage matches the raw cached fraction`() {
        // One real call: 800 of 1000 prompt tokens served from cache.
        val usage = JSONObject(
            """
            {"prompt_tokens": 1000, "completion_tokens": 50,
             "prompt_tokens_details": {"cached_tokens": 800}}
            """.trimIndent(),
        )
        val parsed = provider.parseChatCompletionsUsage(usage)
        // Same formula the usage screen applies: cacheRead / (input + cacheRead + creation).
        val totalInput = parsed.inputTokens +
            (parsed.cacheReadInputTokens ?: 0) +
            (parsed.cacheCreationInputTokens ?: 0)
        val hitRate = (parsed.cacheReadInputTokens ?: 0).toDouble() / totalInput * 100
        assertEquals(80.0, hitRate, 0.01)
        // The pre-fix failure mode displayed 800 / 1800 = 44.4% instead.
    }
}
