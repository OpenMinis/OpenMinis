package com.openminis.app.ui.markdown

import com.openminis.app.ui.chat.MdBlock
import com.openminis.app.ui.chat.parseMarkdownBlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-list-item-block-children] Regression coverage for the "table / code
 * fence under a list item renders as raw pipes and backticks" defect.
 *
 * Root cause (both parsers): list-item continuation lines were appended to
 * the item text verbatim, so the renderer printed `|` and ``` as prose.
 * The numbered-list branch of the classic parser additionally dropped
 * indented continuations entirely, re-parsing them at top level.
 *
 * Fix: continuation lines carrying block-shaped content (fenced code block
 * or a header+separator table) parse into `ListItem.children`, which the
 * list renderers nest under the item. Plain text continuations keep the
 * legacy merge-into-text behavior.
 *
 * [T-para-table-lookahead] Also covers the classic parser's paragraph
 * branch: a paragraph directly followed by a table (no blank line) used to
 * swallow the table rows as prose.
 */
class ListItemNestedBlocksTest {

    private val tableUnderBullet = """
        - item one
          | A | B |
          |---|---|
          | 1 | 2 |
    """.trimIndent()

    private val codeUnderBullet = """
        - item one
          ```kotlin
          val x = 1
          ```
    """.trimIndent()

    private val tableUnderNumbered = """
        1. item one
           | A | B |
           |---|---|
           | 1 | 2 |
        2. item two
    """.trimIndent()

    private val plainContinuation = """
        - item one
          second prose line of the same item
        - item two
    """.trimIndent()

    // ── Classic parser (MarkdownParser) ─────────────────────────────────────

    @Test
    fun `classic bullet table continuation becomes nested child`() {
        val blocks = MarkdownParser.parse(tableUnderBullet)
        assertEquals(1, blocks.size)
        val list = blocks[0] as MarkdownParser.Block.BulletList
        assertEquals(1, list.items.size)
        val item = list.items[0]
        assertEquals("item one", item.content)
        assertEquals(1, item.children.size)
        val table = item.children[0] as MarkdownParser.Block.Table
        assertEquals(listOf("A", "B"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        // The pipes must not leak into the item prose.
        assertFalse(item.content.contains('|'))
    }

    @Test
    fun `classic bullet code fence continuation becomes nested child`() {
        val blocks = MarkdownParser.parse(codeUnderBullet)
        assertEquals(1, blocks.size)
        val list = blocks[0] as MarkdownParser.Block.BulletList
        val item = list.items[0]
        assertEquals("item one", item.content)
        assertEquals(1, item.children.size)
        val code = item.children[0] as MarkdownParser.Block.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertFalse(item.content.contains("```"))
    }

    @Test
    fun `classic numbered table continuation becomes nested child`() {
        // Before the fix the numbered branch dropped indented continuations:
        // the table re-parsed at top level as a sibling block and the list
        // split into two lists.
        val blocks = MarkdownParser.parse(tableUnderNumbered)
        assertEquals(1, blocks.size)
        val list = blocks[0] as MarkdownParser.Block.NumberedList
        assertEquals(2, list.items.size)
        assertEquals("item two", list.items[1].content)
        val item = list.items[0]
        assertEquals("item one", item.content)
        assertEquals(1, item.children.size)
        val table = item.children[0] as MarkdownParser.Block.Table
        assertEquals(listOf("A", "B"), table.headers)
    }

    @Test
    fun `classic plain continuation still merges into item text`() {
        val blocks = MarkdownParser.parse(plainContinuation)
        assertEquals(1, blocks.size)
        val list = blocks[0] as MarkdownParser.Block.BulletList
        assertEquals(2, list.items.size)
        // Legacy behavior preserved: no children, prose merged.
        assertEquals("item one\nsecond prose line of the same item", list.items[0].content)
        assertTrue(list.items[0].children.isEmpty())
    }

    @Test
    fun `classic task item keeps checked flag when gaining children`() {
        val md = """
            - [x] done task
              | A | B |
              |---|---|
              | 1 | 2 |
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        val list = blocks[0] as MarkdownParser.Block.BulletList
        val item = list.items[0]
        assertEquals(java.lang.Boolean.TRUE, item.checked)
        assertEquals("done task", item.content)
        assertEquals(1, item.children.size)
    }

    @Test
    fun `classic paragraph followed directly by table splits into sibling blocks`() {
        val md = "intro text\n| A | B |\n|---|---|\n| 1 | 2 |"
        val blocks = MarkdownParser.parse(md)
        assertEquals(2, blocks.size)
        assertEquals("intro text", (blocks[0] as MarkdownParser.Block.Paragraph).content)
        assertEquals(listOf("A", "B"), (blocks[1] as MarkdownParser.Block.Table).headers)
    }

    @Test
    fun `classic pipe prose without separator stays a paragraph`() {
        // "a | b" has a pipe but no separator row after it — must NOT be
        // mistaken for a table header.
        val md = "a | b\nc | d"
        val blocks = MarkdownParser.parse(md)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownParser.Block.Paragraph)
    }

    // ── Streaming parser (StreamingMarkdownParse) ───────────────────────────

    @Test
    fun `streaming bullet table continuation becomes nested child`() = runBlocking {
        val blocks = parseMarkdownBlocks(tableUnderBullet)
        val list = blocks.filterIsInstance<MdBlock.UnorderedList>().single()
        assertEquals(1, list.items.size)
        val item = list.items[0]
        assertEquals("item one", item.text)
        assertEquals(1, item.children.size)
        val table = item.children[0] as MdBlock.Table
        assertEquals(listOf("A", "B"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        assertFalse(item.text.contains('|'))
    }

    @Test
    fun `streaming bullet code fence continuation becomes nested child`() = runBlocking {
        val blocks = parseMarkdownBlocks(codeUnderBullet)
        val list = blocks.filterIsInstance<MdBlock.UnorderedList>().single()
        val item = list.items[0]
        assertEquals("item one", item.text)
        assertEquals(1, item.children.size)
        val code = item.children[0] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertFalse(item.text.contains("```"))
    }

    @Test
    fun `streaming numbered table continuation becomes nested child`() = runBlocking {
        val blocks = parseMarkdownBlocks(tableUnderNumbered)
        val list = blocks.filterIsInstance<MdBlock.OrderedList>().single()
        assertEquals(2, list.items.size)
        val item = list.items[0]
        assertEquals("item one", item.text)
        assertEquals(1, item.children.size)
        val table = item.children[0] as MdBlock.Table
        assertEquals(listOf("A", "B"), table.headers)
    }

    @Test
    fun `streaming plain continuation still merges into item text`() = runBlocking {
        val blocks = parseMarkdownBlocks(plainContinuation)
        val list = blocks.filterIsInstance<MdBlock.UnorderedList>().single()
        assertEquals(2, list.items.size)
        assertEquals("item one\nsecond prose line of the same item", list.items[0].text)
        assertTrue(list.items[0].children.isEmpty())
    }
}
