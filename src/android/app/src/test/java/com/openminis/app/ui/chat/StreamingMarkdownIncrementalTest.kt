package com.openminis.app.ui.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incremental block parse must be observationally identical to a full
 * parse; the only observable difference is [BlockParseSnapshot.reparsedChars].
 *
 * These tests pin that contract for the shapes that actually grow during a
 * stream: paragraphs, an open fenced code block, and a table.
 */
class StreamingMarkdownIncrementalTest {

    private suspend fun incremental(
        previous: BlockParseSnapshot?,
        text: String,
    ): BlockParseSnapshot = parseMarkdownBlocksIncremental(previous, text)

    private fun assertSameBlocks(expected: List<MdBlock>, actual: List<MdBlock>) {
        assertEquals(expected.map { it.raw }, actual.map { it.raw })
        assertEquals(expected.map { it::class.java }, actual.map { it::class.java })
    }

    @Test
    fun appendingAParagraphReusesFrozenBlocks() = runTest {
        val base = (1..60).joinToString("\n\n") { "Paragraph $it with enough prose to parse." }
        val first = incremental(null, base)
        val next = "$base\n\nA new paragraph arrives."
        val inc = incremental(first, next)
        assertSameBlocks(parseMarkdownBlocks(next), inc.blocks)
        // Only the new tail should be re-parsed, not the 60 frozen paragraphs.
        assertTrue("reparsed=${inc.reparsedChars} full=${next.length}", inc.reparsedChars < next.length / 2)
    }

    @Test
    fun growingAnUnclosedFenceKeepsEarlierBlocksStable() = runTest {
        val base = "Intro paragraph.\n\n```kotlin\nfun a() = 1\n"
        val first = incremental(null, base)
        val next = "$base" + "fun b() = 2\n```\n\nAfter the fence."
        val inc = incremental(first, next)
        assertSameBlocks(parseMarkdownBlocks(next), inc.blocks)
        assertTrue(inc.blocks.last() is MdBlock.Paragraph)
    }

    @Test
    fun appendingATableRowRebuildsOnlyTheTable() = runTest {
        val base = "Before.\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val first = incremental(null, base)
        val next = "$base\n| 3 | 4 |"
        val inc = incremental(first, next)
        assertSameBlocks(parseMarkdownBlocks(next), inc.blocks)
        val table = inc.blocks.last() as MdBlock.Table
        assertEquals(2, table.rows.size)
    }

    @Test
    fun trailingBlankLinesDoNotBreakTheSuffixCheck() = runTest {
        val first = incremental(null, "A\n\nB\n\n")
        val next = first.raw + "C"
        val inc = incremental(first, next)
        assertSameBlocks(parseMarkdownBlocks(next), inc.blocks)
        assertEquals(listOf("A", "B", "C"), inc.blocks.map { it.raw })
    }

    @Test
    fun nonAppendEditFallsBackToFullParse() = runTest {
        val first = incremental(null, "hello world")
        val next = "hello WORLD and more"
        val inc = incremental(first, next)
        assertSameBlocks(parseMarkdownBlocks(next), inc.blocks)
        assertEquals(next.length, inc.reparsedChars)
    }

    @Test
    fun singleGrowingParagraphAppendsWithoutBlockReparse() = runTest {
        val first = incremental(null, "one growing paragraph")
        val next = "one growing paragraph with more"
        val inc = incremental(first, next)
        assertSameBlocks(parseMarkdownBlocks(next), inc.blocks)
        // Only the appended characters are accounted; no block regex ran over
        // the whole (still growing) paragraph.
        assertEquals(next.length - first.raw.length, inc.reparsedChars)
        assertTrue(inc.blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun mediaOrNewlineGrowthFallsBackToFullParse() = runTest {
        val first = incremental(null, "one paragraph")
        val newline = incremental(first, "one paragraph\nnext line")
        assertSameBlocks(parseMarkdownBlocks("one paragraph\nnext line"), newline.blocks)
        assertEquals("one paragraph\nnext line".length, newline.reparsedChars)

        val media = incremental(first, "one paragraph ![alt](https://x/y.png)")
        assertSameBlocks(
            parseMarkdownBlocks("one paragraph ![alt](https://x/y.png)"),
            media.blocks,
        )
    }

    @Test
    fun deepBlockquoteIsBoundedInsteadOfStackOverflowing() = runTest {
        val doc = (0 until 40).joinToString("\n") { i ->
            "> ".repeat(i + 1) + "line$i"
        }
        val blocks = parseMarkdownBlocks(doc)
        assertEquals(1, blocks.size)
        var quote = blocks[0] as MdBlock.BlockQuote
        var levels = 1
        while (true) {
            val next = quote.innerBlocks.filterIsInstance<MdBlock.BlockQuote>().firstOrNull() ?: break
            levels++
            quote = next
        }
        // Root + 16 recursive levels; the innermost content degrades to
        // Paragraphs instead of recursing again.
        assertTrue("levels=$levels", levels <= 17)
        assertTrue(quote.innerBlocks.isNotEmpty())
        assertTrue(quote.innerBlocks.none { it is MdBlock.BlockQuote })
    }

    @Test
    fun unclosedFenceIsMarkedAndClosedFenceIsNot() = runTest {
        val open = parseMarkdownBlocks("```kotlin\nval x = 1\n")
        val openCode = open.single() as MdBlock.CodeBlock
        assertFalse(openCode.closed)

        val closed = parseMarkdownBlocks("```kotlin\nval x = 1\n```")
        val closedCode = closed.single() as MdBlock.CodeBlock
        assertTrue(closedCode.closed)
    }

    @Test
    fun incrementalParseKeepsTheUnclosedFlag() = runTest {
        val base = "Intro.\n\n```kotlin\nval a = 1\n"
        val first = incremental(null, base)
        val inc = incremental(first, "$base" + "val b = 2\n")
        val code = inc.blocks.last() as MdBlock.CodeBlock
        assertFalse(code.closed)
    }

    @Test
    fun paragraphLoopStillStopsAtLooseBlockMarkers() = runTest {
        // A fence, a quote, a bullet, a numbered item, an image line and a
        // table must each still terminate a paragraph — the first-char fast
        // path must not skip them.
        val doc = buildString {
            appendLine("prose line")
            appendLine("# heading")
            appendLine("more prose")
            appendLine("```")
            appendLine("code")
            appendLine("```")
            appendLine("> quote")
            appendLine("- bullet")
            appendLine("1. numbered")
            appendLine("![alt](x.png)")
            appendLine("| h |")
            appendLine("|---|")
            appendLine("| v |")
        }
        val blocks = parseMarkdownBlocks(doc)
        assertTrue(blocks.any { it is MdBlock.Heading })
        assertTrue(blocks.any { it is MdBlock.CodeBlock })
        assertTrue(blocks.any { it is MdBlock.BlockQuote })
        assertTrue(blocks.any { it is MdBlock.UnorderedList })
        assertTrue(blocks.any { it is MdBlock.OrderedList })
        assertTrue(blocks.any { it is MdBlock.Image })
        assertTrue(blocks.any { it is MdBlock.Table })
    }

    @Test
    fun hashPrefixedProseStillSplitsWithoutBecomingAHeading() = runTest {
        val blocks = parseMarkdownBlocks("prose line\n#not-a-heading")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertEquals("#not-a-heading", blocks[1].raw)
    }
}
