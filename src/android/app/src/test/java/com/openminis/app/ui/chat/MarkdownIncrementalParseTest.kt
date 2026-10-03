package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-md-parse-incremental] The incremental streaming parse freezes
 * every block before the newest "sealed" blank-line boundary and re-parses
 * only the tail. A boundary wrongly declared sealed would freeze a half-open
 * code fence or math span and corrupt the render for the rest of the stream,
 * so the boundary rule is pinned here.
 */
class MarkdownIncrementalParseTest {

    /** Literal "$$" without tripping Kotlin string interpolation. */
    private val D = "$" + "$"

    @Test
    fun `a blank line between paragraphs is a sealed boundary`() {
        val md = "first para\n\nsecond para"
        val end = stableParsePrefixEnd(md, 0)
        assertEquals("first para\n\n".length, end)
        assertEquals("first para\n\n", md.substring(0, end))
    }

    @Test
    fun `a blank line INSIDE an open code fence is not sealed`() {
        // The fence opens and never closes — everything after it is still live.
        val md = "intro\n\n```kotlin\nval a = 1\n\nval b = 2"
        val end = stableParsePrefixEnd(md, 0)
        assertEquals("only the pre-fence boundary may freeze", "intro\n\n".length, end)
    }

    @Test
    fun `a blank line after a CLOSED fence is sealed`() {
        val md = "intro\n\n```\ncode\n```\n\ntail"
        val end = stableParsePrefixEnd(md, 0)
        assertEquals("intro\n\n```\ncode\n```\n\n".length, end)
    }

    @Test
    fun `a blank line inside an open display-math span is not sealed`() {
        val md = "intro\n\n" + D + "\nx = 1\n\ny = 2"
        assertEquals("intro\n\n".length, stableParsePrefixEnd(md, 0))
    }

    @Test
    fun `single-line display math does not count as an open span`() {
        val md = "intro\n\n" + D + "x = 1" + D + "\n\ntail"
        assertEquals(("intro\n\n" + D + "x = 1" + D + "\n\n").length, stableParsePrefixEnd(md, 0))
    }

    @Test
    fun `the prefix only ever grows`() {
        val md = "a\n\nb\n\nc"
        // Already frozen past the first boundary → never hand back a smaller one.
        val already = "a\n\nb\n\n".length
        assertEquals(already, stableParsePrefixEnd(md, already))
    }

    @Test
    fun `content with no blank line has nothing sealed`() {
        assertEquals(0, stableParsePrefixEnd("one continuous line of text", 0))
    }

    @Test
    fun `isSealedBoundary counts only line-leading delimiters`() {
        // A ``` appearing mid-sentence is not a fence opener.
        assertTrue(isSealedBoundary("see the ``` marker inline\n\n", "see the ``` marker inline\n\n".length))
        assertFalse(isSealedBoundary("```\nopen\n\n", "```\nopen\n\n".length))
    }

    @Test
    fun `indented fence still counts as a fence`() {
        val md = "  ```\ncode\n\nmore"
        assertFalse(isSealedBoundary(md, md.length))
    }

    // ─── Equivalence: incremental replay must equal a single full parse ────

    /** Feed [full] in growing prefixes, as a stream would arrive. */
    private fun chunked(full: String, step: Int): List<String> =
        generateSequence(step) { it + step }.takeWhile { it < full.length }
            .map { full.substring(0, it) }.toList() + listOf(full)

    private fun assertIncrementalMatchesFull(full: String, step: Int = 37) {
        assertEquals(
            "incremental replay must produce the same blocks as one full parse",
            debugParseBlockRaws(full),
            debugParseIncrementalRaws(chunked(full, step)),
        )
    }

    @Test
    fun `streamed paragraphs match a full parse`() {
        assertIncrementalMatchesFull(
            (1..12).joinToString("\n\n") { "Paragraph number $it with some trailing words." },
        )
    }

    @Test
    fun `streamed markdown with fences lists table and math matches a full parse`() {
        val d = D
        val full = buildString {
            append("# Title\n\n")
            append("Intro paragraph with `inline code` and **bold**.\n\n")
            append("## Section\n\n")
            append("- bullet one\n- bullet two\n- bullet three\n\n")
            append("1. first\n2. second\n\n")
            append("```kotlin\nfun main() {\n\n    println(\"blank line inside fence\")\n}\n```\n\n")
            append("| a | b |\n|---|---|\n| 1 | 2 |\n\n")
            append(d).append("\nE = mc^2\n\n").append(d).append("\n\n")
            append("Closing paragraph.\n")
        }
        assertIncrementalMatchesFull(full, step = 29)
    }

    @Test
    fun `a non-append edit invalidates the cached prefix`() {
        // Grow, then replace the text entirely — the result must match a full
        // parse of the NEW text, not a splice of the old prefix.
        val a = "alpha para\n\nbeta para\n\ngamma"
        val b = "totally different\n\ncontent here"
        assertEquals(debugParseBlockRaws(b), debugParseIncrementalRaws(listOf(a, b)))
    }
}
