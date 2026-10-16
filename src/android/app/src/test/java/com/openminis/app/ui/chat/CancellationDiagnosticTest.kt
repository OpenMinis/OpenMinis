package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-cancel-diag] Pure decision core for the agent-loop cancellation
 * timeline. The Log call itself is not exercised in CI — these tests pin
 * the source classification and the record shape.
 */
class CancellationDiagnosticTest {

    @Test
    fun `bare scope cancellation classifies as scope-cancel`() {
        val line = CancellationDiagnostic.recordLine(
            model = "claude-sonnet",
            sessionId = "s1",
            reason = null,
            streaming = true,
        )
        assertEquals(
            "source=scope-cancel model=claude-sonnet session=s1 streaming=true",
            line,
        )
    }

    @Test
    fun `user stop reason classifies as user-stop`() {
        val line = CancellationDiagnostic.recordLine(
            model = "gemini-2.0",
            sessionId = "s2",
            reason = "Stopped by user",
            streaming = false,
        )
        assertEquals(
            "source=user-stop model=gemini-2.0 session=s2 streaming=false reason=\"Stopped by user\"",
            line,
        )
    }

    @Test
    fun `compact cancellation classifies as compact-cancel`() {
        val line = CancellationDiagnostic.recordLine(
            model = "gpt-4o",
            sessionId = "s3",
            reason = "compact cancelled by user",
            streaming = false,
        )
        assertEquals("source=compact-cancel", line.substringBefore(" model="))
    }

    @Test
    fun `unknown reason falls to other and is capped at 120 chars`() {
        val longReason = "x".repeat(500)
        val line = CancellationDiagnostic.recordLine(
            model = "m",
            sessionId = "s",
            reason = longReason,
            streaming = true,
        )
        assertTrue(line.startsWith("source=other"))
        // Reason segment is capped: 120 chars + 2 quotes.
        val reasonSegment = line.substringAfter(" reason=\"").removeSuffix("\"")
        assertEquals(120, reasonSegment.length)
    }

    @Test
    fun `multi-line reason is flattened so the record stays one line`() {
        val line = CancellationDiagnostic.recordLine(
            model = "m",
            sessionId = "s",
            reason = "line1\nline2\tline3",
            streaming = false,
        )
        assertEquals(-1, line.indexOf('\n'))
        assertEquals(-1, line.indexOf('\t'))
        assertEquals("reason=\"line1 line2 line3\"", line.substringAfter(" streaming=false "))
    }
}
