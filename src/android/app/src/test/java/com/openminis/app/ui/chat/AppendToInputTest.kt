package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-append-to-input-eats-draft] "Add to input" must not rewrite the
 * draft the user has already typed.
 *
 * The bug this pins: the old implementation computed `current.trimEnd()` and
 * assigned that trimmed copy back into the composer. Trimming leaked from the
 * emptiness *predicate* into the *assignment*, so appending a snippet silently
 * ate a trailing newline the user had deliberately typed — their paragraph
 * break collapsed into a single separator space, with no undo.
 *
 * Ported from iOS `e6c0ace6a`, which fixed the byte-for-byte same defect.
 */
class AppendToInputTest {

    private fun join(draft: String, snippet: String) =
        ChatViewModel.joinDraftWithSnippet(draft, snippet)

    // ─── The regression itself ───────────────────────────────────────────

    @Test
    fun `trailing newline in draft survives the append`() {
        // The user typed a paragraph and pressed Enter, intending to start a
        // new line. Appending must not undo that keystroke.
        assertEquals(
            "First paragraph.\nquoted ",
            join("First paragraph.\n", "quoted"),
        )
    }

    @Test
    fun `blank line paragraph break survives the append`() {
        // Two newlines = a deliberate blank line. Both must come back.
        assertEquals(
            "Intro.\n\nquoted ",
            join("Intro.\n\n", "quoted"),
        )
    }

    @Test
    fun `draft is preserved byte for byte including interior whitespace`() {
        val draft = "  leading spaces and\ttabs\nkept  "
        val result = join(draft, "snippet")!!
        // Whatever else happens, the draft must be an untouched prefix.
        assertEquals(draft, result.take(draft.length))
    }

    // ─── Separator insertion ─────────────────────────────────────────────

    @Test
    fun `draft ending in a space does not get a second separator`() {
        // Already ends in whitespace — adding another space would double it.
        assertEquals("hello world ", join("hello ", "world"))
    }

    @Test
    fun `draft not ending in whitespace gets exactly one separator space`() {
        assertEquals("hello world ", join("hello", "world"))
    }

    @Test
    fun `newline-terminated draft gets no separator space`() {
        // A newline already separates; a space after it would indent the line.
        assertEquals("hello\nworld ", join("hello\n", "world"))
    }

    // ─── Empty / blank handling ──────────────────────────────────────────

    @Test
    fun `empty draft yields just the snippet and a trailing space`() {
        assertEquals("snippet ", join("", "snippet"))
    }

    @Test
    fun `whitespace-only draft is treated as empty, not prefixed`() {
        // The trimmed view drives this decision; we must not emit a leading
        // run of blanks the user can't see.
        assertEquals("snippet ", join("   \n  ", "snippet"))
    }

    @Test
    fun `blank snippet is rejected and leaves the draft alone`() {
        assertNull(join("my draft", "   "))
        assertNull(join("my draft", ""))
        assertNull(join("my draft", "\n\t "))
    }

    // ─── Snippet trimming ────────────────────────────────────────────────

    @Test
    fun `snippet is trimmed on both ends before joining`() {
        assertEquals("draft snippet ", join("draft", "  snippet  "))
    }

    @Test
    fun `interior whitespace inside the snippet is left intact`() {
        // Only the ENDS are trimmed — a multi-line selection keeps its shape.
        assertEquals("draft line1\nline2 ", join("draft", "  line1\nline2  "))
    }

    // ─── Repeated appends ────────────────────────────────────────────────

    @Test
    fun `successive appends do not accumulate separators`() {
        // Each append leaves a trailing space, so the next one must not add
        // another. Three appends in a row should read as a clean sentence.
        var draft = ""
        listOf("alpha", "beta", "gamma").forEach { draft = join(draft, it)!! }
        assertEquals("alpha beta gamma ", draft)
    }
}
