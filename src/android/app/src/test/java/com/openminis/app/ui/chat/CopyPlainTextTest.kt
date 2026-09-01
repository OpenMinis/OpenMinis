package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-copy-full-plain-text] "Copy Full Reply ▸ Plain Text" copies the
 * RENDERED text, not the raw markdown with its markers deleted.
 *
 * The distinction from iOS `1b4353367`: headings, lists and quotes land the
 * way the user SEES them — no `#`, `-`, `**`, `>` left behind — but tables and
 * math still expand, because dropping those loses *content*, not formatting.
 * A table that came back as an empty line would be a silent data loss in the
 * paste target.
 */
class CopyPlainTextTest {

    private fun plain(md: String) = MarkdownClipboard.markdownToPlainText(md)

    // ─── Markers are stripped ────────────────────────────────────────────

    @Test
    fun `heading markers are removed but the text stays`() {
        val out = plain("# Title\n## Subtitle")
        assertTrue("heading text kept", out.contains("Title"))
        assertTrue("subheading text kept", out.contains("Subtitle"))
        assertFalse("no hash markers", out.contains("#"))
    }

    @Test
    fun `emphasis markers are removed but the words stay`() {
        val out = plain("Some **bold** and *italic* and ~~struck~~ text.")
        assertTrue(out.contains("bold"))
        assertTrue(out.contains("italic"))
        assertTrue(out.contains("struck"))
        assertFalse("no asterisks", out.contains("*"))
        assertFalse("no tildes", out.contains("~"))
    }

    @Test
    fun `blockquote marker is removed but the quote stays`() {
        val out = plain("> quoted wisdom")
        assertTrue(out.contains("quoted wisdom"))
        assertFalse(out.trimStart().startsWith(">"))
    }

    @Test
    fun `link text is kept and the URL is dropped`() {
        val out = plain("See [the docs](https://example.com/page) for more.")
        assertTrue("anchor text kept", out.contains("the docs"))
        assertFalse("url dropped", out.contains("example.com"))
        assertFalse(out.contains("]("))
    }

    // ─── Content is NOT lost ─────────────────────────────────────────────

    @Test
    fun `table cell content survives`() {
        // The separator row is dropped as pure formatting, but every cell
        // value must still be present — losing them is losing content.
        val out = plain(
            """
            | Col A | Col B |
            |-------|-------|
            | 1     | 2     |
            | 3     | 4     |
            """.trimIndent(),
        )
        listOf("Col A", "Col B", "1", "2", "3", "4").forEach {
            assertTrue("table cell '$it' survives, got:\n$out", out.contains(it))
        }
        assertFalse("separator row dropped", out.contains("---"))
    }

    @Test
    fun `math content survives`() {
        val out = plain("Math: \$x^2 + y^2 = z^2\$")
        assertTrue("formula body kept, got: $out", out.contains("x^2"))
        assertTrue(out.contains("z^2"))
    }

    @Test
    fun `fenced code block content survives verbatim`() {
        val out = plain("```kotlin\nval x = 1\n```")
        assertTrue("code line kept", out.contains("val x = 1"))
        assertFalse("fence markers dropped", out.contains("```"))
    }

    @Test
    fun `list item text survives with the bullet normalized`() {
        val out = plain("- first\n- second")
        assertTrue(out.contains("first"))
        assertTrue(out.contains("second"))
        // Raw markdown bullets are replaced by a rendered bullet glyph.
        assertFalse("no raw dash bullets", out.lines().any { it.trimStart().startsWith("- ") })
    }

    @Test
    fun `ordered list keeps its numbering`() {
        val out = plain("1. alpha\n2. beta")
        assertTrue(out.contains("1."))
        assertTrue(out.contains("alpha"))
        assertTrue(out.contains("2."))
        assertTrue(out.contains("beta"))
    }

    // ─── Plain text differs from raw markdown ────────────────────────────

    @Test
    fun `plain text is not merely the raw markdown`() {
        // Guards against a regression where the Plain Text action is wired to
        // the same source as Copy Markdown — the two must produce different
        // output for marked-up input, or the menu item is a lie.
        val md = "# Title\n\nSome **bold** text."
        assertFalse(plain(md) == md)
    }
}
