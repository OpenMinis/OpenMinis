package com.openminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-openrouter-reasoning-details] Port of iOS ReasoningDetailsParseTests
 * (8d17198d6, GH#263): OpenRouter's structured `delta.reasoning_details` array
 * was never read, so reasoning sent only that way left the turn empty.
 */
class ReasoningDetailsTest {

    private fun parse(json: String) = ReasoningDetails.parse(JSONObject(json))

    /** Mirrors the provider: string fields first, then details. */
    private fun run(vararg chunks: String): String = buildString {
        for (c in chunks) {
            val d = JSONObject(c)
            val rc = listOf("reasoning_content", "reasoning", "reasoning_text")
                .map { (d.opt(it) as? String).orEmpty() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            append(rc)
            ReasoningDetails.parse(d)?.let { append(it.text) }
        }
    }

    @Test
    fun `reasoning text items are accumulated across chunks`() {
        assertEquals(
            "Let me think. Done.",
            run(
                """{"reasoning_details":[{"type":"reasoning.text","text":"Let me think. ","index":0}]}""",
                """{"reasoning_details":[{"type":"reasoning.text","text":"Done.","index":0}]}""",
            ),
        )
    }

    @Test
    fun `summary is read from its own key`() {
        assertEquals("Plan: X", parse("""{"reasoning_details":[{"type":"reasoning.summary","summary":"Plan: X"}]}""")!!.text)
    }

    @Test
    fun `encrypted items add no text but count as reasoning`() {
        val p = parse(
            """{"reasoning_details":[{"type":"reasoning.encrypted","data":"gAAAA"},{"type":"reasoning.encrypted","data":"gBBBB"}]}""",
        )!!
        assertEquals("", p.text)
        assertEquals(2, p.encryptedCount)
        assertTrue(p.sawReasoning)
    }

    @Test
    fun `unknown item types are ignored`() {
        assertNull(parse("""{"reasoning_details":[{"type":"reasoning.future","x":1}]}"""))
    }

    @Test
    fun `mixed items keep their order`() {
        assertEquals(
            "S T",
            parse("""{"reasoning_details":[{"type":"reasoning.summary","summary":"S "},{"type":"reasoning.text","text":"T"}]}""")!!.text,
        )
    }

    @Test
    fun `a JSON null text is empty, not the word null`() {
        assertEquals("", parse("""{"reasoning_details":[{"type":"reasoning.text","text":null}]}""")!!.text)
    }

    @Test
    fun `string plus details for the same text counts it once`() {
        assertEquals(
            "Hmm. Ok.",
            run(
                """{"reasoning":"Hmm. ","reasoning_details":[{"type":"reasoning.text","text":"Hmm. "}]}""",
                """{"reasoning":"Ok.","reasoning_details":[{"type":"reasoning.text","text":"Ok."}]}""",
            ),
        )
        assertNull(parse("""{"reasoning_content":"A","reasoning_details":[{"type":"reasoning.text","text":"A"}]}"""))
    }

    @Test
    fun `an empty string field does not suppress the details`() {
        assertEquals("real", run("""{"reasoning":"","reasoning_details":[{"type":"reasoning.text","text":"real"}]}"""))
    }

    @Test
    fun `plain string reasoning is unchanged and no details means null`() {
        assertEquals("xy", run("""{"reasoning":"x"}""", """{"reasoning":"y"}"""))
        assertNull(parse("""{"content":"hi"}"""))
    }

    @Test
    fun `the chat-completions parser is wired to ReasoningDetails`() {
        val src = listOf(
            File("src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt"),
            File("app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt"),
        ).first { it.exists() }.readText()
        assertTrue(src.contains("ReasoningDetails.parse(d)?.let { rd ->"))
        assertTrue(src.contains("encryptedReasoningChunks=\$encryptedReasoningChunks"))
    }
}
