package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-preview-flicker-toolresult] A row that yields no preview must not
 * blank the one already on screen.
 *
 * The bug this pins: during a multi-tool run the session list oscillated
 * between the current tool's title and "No messages yet". The agent loop
 * pushes a live preview holding the tool title just before a tool runs
 * (T-android-session-last-message-live-tool-call), then on completion inserts
 * a tool-result row. `appendMessage` recomputed the preview from that row and
 * wrote the result unconditionally — and a tool-result row extracts to null,
 * so the column was blanked on every tool boundary.
 *
 * `extractTextPreview` returning null for a tool-result row is CORRECT and is
 * asserted here: a raw tool payload is not a sensible thing to show as a
 * session's last message. The defect was writing that null.
 */
class PreviewFlickerTest {

    private fun preview(json: String) = ChatRepository.extractTextPreview(json)

    private val toolResultRow = """
        [{"type":"toolResult","value":{"toolUseId":"t1","name":"shell_execute",
          "output":"git version 2.47.3","success":true,
          "snapshot":{"type":"text","text":"git version 2.47.3"}}}]
    """.trimIndent()

    // ─── The shape that caused the flicker ───────────────────────────────

    @Test
    fun `a tool-result row yields no preview`() {
        // If this ever starts returning a value, the guard added in
        // appendMessage stops being load-bearing — but the row would then be
        // summarised, which is also fine. Either way this documents WHY the
        // null path exists.
        assertNull(preview(toolResultRow))
    }

    @Test
    fun `a tool-use row still yields a preview`() {
        // This is what the live push writes before the tool runs — the value
        // the flicker was wiping out. It must keep working.
        val toolUse =
            """[{"type":"toolUse","value":{"name":"shell_execute","description":"Install Git"}}]"""
        assertNotNull("tool_use must summarise, or the list has nothing to show", preview(toolUse))
    }

    @Test
    fun `assistant text still wins over everything else`() {
        val mixed = """
            [{"type":"toolUse","value":{"name":"shell_execute"}},
             {"type":"text","value":"Git is installed."}]
        """.trimIndent()
        assertEquals("Git is installed.", preview(mixed))
    }

    // ─── Rows that legitimately produce nothing ──────────────────────────

    @Test
    fun `an empty parts array yields no preview`() {
        assertNull(preview("[]"))
    }

    @Test
    fun `a blank-text row yields no preview`() {
        // A text part whose value is empty must not be mistaken for content —
        // it would blank the list just as effectively as a null.
        assertNull(preview("""[{"type":"text","value":"   "}]"""))
    }

    @Test
    fun `media-only rows summarise rather than blanking`() {
        assertEquals("[Image]", preview("""[{"type":"mediaRef","value":{"kind":"image"}}]"""))
    }

    // ─── Ordering ────────────────────────────────────────────────────────

    @Test
    fun `the last tool use wins when several are present`() {
        // A turn can dispatch several tools; the preview should track the most
        // recent one rather than the first.
        val two = """
            [{"type":"toolUse","value":{"name":"shell_execute","description":"First"}},
             {"type":"toolUse","value":{"name":"shell_execute","description":"Second"}}]
        """.trimIndent()
        val out = preview(two)
        assertNotNull(out)
        // Whatever the summary format, it must reflect the SECOND tool.
        assertEquals(true, out!!.contains("Second"))
    }
}
