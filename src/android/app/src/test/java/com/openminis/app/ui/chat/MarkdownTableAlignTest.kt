package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-table-align] The streaming parser must read the separator row's
 * alignment markers instead of dropping them.
 *
 * Everything here used to render start-aligned regardless of the source: the
 * separator line was consumed by `parseTable` and thrown away, so a table
 * written with `:---:` or `---:` reached the cell layout with no alignment at
 * all. The markers are how an author says "these numbers are right-aligned";
 * losing them is a silent formatting regression, which is what makes it worth
 * pinning down in a unit test rather than only checking by eye.
 */
class MarkdownTableAlignTest {

    @Test
    fun separatorMarkersMapToAlignments() {
        assertEquals(
            listOf(MdTableAlign.START, MdTableAlign.CENTER, MdTableAlign.END),
            parseTableAlignments("| --- | :---: | ---: |"),
        )
    }

    @Test
    fun leadingColonIsStillStart() {
        // GFM: `:---` and `---` are both left-aligned; only both colons centre.
        assertEquals(listOf(MdTableAlign.START), parseTableAlignments("| :--- |"))
        assertEquals(listOf(MdTableAlign.START), parseTableAlignments("---"))
    }

    @Test
    fun singleColonCellIsNotCentre() {
        // A cell that is just ":" has no width to align; it must not crash or
        // become CENTER (the length guard is what prevents that).
        assertEquals(listOf(MdTableAlign.START), parseTableAlignments("| : |"))
    }

    @Test
    fun separatorWithoutOuterPipesIsParsed() {
        assertEquals(
            listOf(MdTableAlign.END, MdTableAlign.CENTER),
            parseTableAlignments("---:|:---:"),
        )
    }

    @Test
    fun parseTableCapturesAlignmentsAndKeepsCells() {
        val parsed = parseTable(
            listOf(
                "| Name | Score | Note |",
                "| :--- | ---: | :---: |",
                "| a | 1 | ok |",
            ),
        )
        assertEquals(listOf("Name", "Score", "Note"), parsed.headers)
        assertEquals(1, parsed.rows.size)
        assertEquals(listOf("a", "1", "ok"), parsed.rows.single())
        assertEquals(
            listOf(MdTableAlign.START, MdTableAlign.END, MdTableAlign.CENTER),
            parsed.alignments,
        )
    }

    @Test
    fun tableWithoutSeparatorHasNoAlignments() {
        // Defensive: callers must fall back to START rather than crash on an
        // empty list (RenderTable does `alignments.getOrNull(col) ?: START`).
        val parsed = parseTable(listOf("| a | b |", "| c | d |"))
        assertEquals(emptyList<MdTableAlign>(), parsed.alignments)
        assertEquals(listOf("a", "b"), parsed.headers)
    }

    @Test
    fun emptyCellsArePreserved() {
        // T308 regression guard: the empty first column of `| | Manus |` must
        // survive so header and body stay column-aligned.
        val parsed = parseTable(
            listOf("| | Manus | TikTok |", "| --- | --- | --- |", "| x | y | z |"),
        )
        assertEquals(listOf("", "Manus", "TikTok"), parsed.headers)
        assertEquals(3, parsed.alignments.size)
    }
}
