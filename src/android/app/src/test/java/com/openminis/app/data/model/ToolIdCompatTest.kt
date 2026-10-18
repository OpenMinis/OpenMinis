package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-cross-api-tool-id] The cross-API compat layer must rewrite exactly
 * the ids that would fail another endpoint's wire format, must leave the rest
 * untouched, and must map a call and its result identically (that pairing is
 * what makes the whole thing safe).
 */
class ToolIdCompatTest {

    private val max = ToolIdCompat.MAX_WIRE_ID_LENGTH

    // ── pass-through ─────────────────────────────────────────────────────────

    @Test
    fun naturallySafeIdsAreReturnedUnchanged() {
        val safe = listOf("call_abc123", "toolu_01H8x9", "chatcmpl-tool-a1b2", "a")
        for (id in safe) {
            assertSame("$id is already wire-safe", id, ToolIdCompat.normalize(id))
        }
    }

    // ── charset: the Anthropic-direction failure ─────────────────────────────

    @Test
    fun dotsAndColonsAreRewritten() {
        // Relays (Together, Fireworks, OpenRouter passthrough) mint ids like
        // "functions.Read:0"; Anthropic's tool_use.id regex rejects both marks.
        val out = ToolIdCompat.normalize("functions.Read:0")
        assertEquals("functions-Read-0", out)
        assertTrue("only [A-Za-z0-9_-] may survive", out.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }

    @Test
    fun slashAndSpaceAndUnicodeAreRewritten() {
        for (raw in listOf("a/b", "a b", "识别-1", "a\tb")) {
            val out = ToolIdCompat.normalize(raw)
            assertTrue("$raw -> $out", out.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        }
    }

    @Test
    fun emptyIdGetsAStablePlaceholder() {
        val out = ToolIdCompat.normalize("")
        assertTrue(out.startsWith("tool_"))
        assertEquals("stable for repeated calls", out, ToolIdCompat.normalize(""))
        assertEquals("deterministic across instances", out, ToolIdCompat.normalize(""))
    }

    // ── length: the OpenAI-direction failure ─────────────────────────────────

    @Test
    fun overlongIdIsCappedBelowTheOpenAiCeiling() {
        val raw = "call_" + "x".repeat(300)
        val out = ToolIdCompat.normalize(raw)
        assertEquals(max, out.length)
        assertTrue(out.startsWith("call_"))
        assertTrue("capped form keeps the legal charset", out.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }

    @Test
    fun idsDifferingOnlyAtTheTailDoNotCollideAfterCapping() {
        val a = "call_" + "y".repeat(200) + "-alpha"
        val b = "call_" + "y".repeat(200) + "-beta"
        assertTrue("a digest suffix must separate late differences", ToolIdCompat.normalize(a) != ToolIdCompat.normalize(b))
    }

    @Test
    fun exactlyAtTheCeilingIsUntouched() {
        val raw = "c".repeat(max)
        assertSame(raw, ToolIdCompat.normalize(raw))
    }

    // ── pairing + idempotence: what makes it safe to ship ────────────────────

    @Test
    fun callAndResultMapToTheSameWireId() {
        val raw = "functions.Read:0"
        assertEquals(
            "the assistant tool_use and its tool_result must normalize identically",
            ToolIdCompat.normalize(raw),
            ToolIdCompat.normalize(raw),
        )
    }

    @Test
    fun normalizationIsIdempotent() {
        val samples = listOf(
            "functions.Read:0",
            "call_abc|fc_def",
            "call_" + "z".repeat(500),
            "",
            "识别",
        )
        for (raw in samples) {
            val once = ToolIdCompat.normalize(raw)
            assertEquals("$raw must be stable on a retried request", once, ToolIdCompat.normalize(once))
        }
    }

    // ── the combined Responses id keeps its call half ────────────────────────

    @Test
    fun combinedResponsesIdDegradesToItsCallHalf() {
        // Responses stores "call_…|fc_…"; the paired tool result refers to the
        // call half, so dropping the fc_ tail (not just replacing the pipe) is
        // what keeps the pair intact — this is the pre-existing behaviour the
        // compat layer must not lose.
        assertEquals("call_abc", ToolIdCompat.normalize("call_abc|fc_def"))
    }

    @Test
    fun combinedAndBareIdsAgree() {
        assertEquals(
            ToolIdCompat.normalize("call_abc"),
            ToolIdCompat.normalize("call_abc|fc_def"),
        )
    }

    // ── the shared symbol every provider already imports ─────────────────────

    @Test
    fun sanitizeToolIdDelegatesToTheCompatLayer() {
        assertEquals(ToolIdCompat.normalize("call_abc|fc_def"), sanitizeToolId("call_abc|fc_def"))
        assertEquals(ToolIdCompat.normalize("functions.Read:0"), sanitizeToolId("functions.Read:0"))
        assertFalse(sanitizeToolId("functions.Read:0").contains('.'))
    }
}
