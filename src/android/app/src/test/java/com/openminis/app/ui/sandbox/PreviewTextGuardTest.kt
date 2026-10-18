package com.openminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-preview-long-line] The preview guard exists so a single enormous
 * line cannot stall text layout. These cases pin the two bounds and the
 * "don't touch normal files" property, which is the one that would be silently
 * broken by an over-eager clamp.
 */
class PreviewTextGuardTest {

    @Test
    fun ordinaryMarkdownPassesThroughUnchanged() {
        val md = "# Title\n\n- one\n- two\n\ntrailing text"
        assertEquals(md, PreviewTextGuard.clamp(md))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", PreviewTextGuard.clamp(""))
    }

    @Test
    fun longSingleLineIsCutAtTheCharCap() {
        val line = "x".repeat(PreviewTextGuard.MAX_LINE_CHARS * 3)
        val out = PreviewTextGuard.clamp(line)
        // The kept prefix is exactly the cap, plus the truncation marker.
        assertTrue(out.startsWith("x".repeat(PreviewTextGuard.MAX_LINE_CHARS)))
        assertTrue(out.endsWith("(line truncated)"))
        assertFalse("marker must not itself repeat the payload", out.length > PreviewTextGuard.MAX_LINE_CHARS + 64)
    }

    @Test
    fun shortLinesKeepTheirBreaks() {
        val text = (1..5).joinToString("\n") { "line$it" }
        assertEquals(text, PreviewTextGuard.clamp(text))
    }

    @Test
    fun lineCountIsCapped() {
        val text = (1..(PreviewTextGuard.MAX_LINES + 10)).joinToString("\n") { "l$it" }
        val out = PreviewTextGuard.clamp(text)
        assertEquals(PreviewTextGuard.MAX_LINES, out.lines().size - 1) // + the truncation line
        assertTrue(out.endsWith("(preview truncated)"))
    }

    @Test
    fun aLineExactlyAtTheCapIsNotMarked() {
        val line = "y".repeat(PreviewTextGuard.MAX_LINE_CHARS)
        assertEquals(line, PreviewTextGuard.clamp(line))
    }
}
