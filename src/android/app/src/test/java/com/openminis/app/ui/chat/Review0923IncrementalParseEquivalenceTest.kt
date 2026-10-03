package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review 2026-09-23 — guards f0a57d93f / 656889f68 (T-android-md-parse-incremental).
 *
 * The incremental streaming parse freezes every block before the newest
 * "sealed" blank line and re-parses only the tail. That is only correct if the
 * block parser never lets a construct continue across a blank line that
 * [isSealedBoundary] accepts. The existing MarkdownIncrementalParseTest covers
 * ``` fences and `$$` math; these cases cover the parser constructs that DO
 * cross a blank line but that the boundary rule does not know about:
 *
 *  - bullet / ordered lists (`if (t.isEmpty()) { i++; continue }` inside the
 *    list loops — a "loose" list with a blank line between items is one list
 *    in a full parse),
 *  - an indented list-item continuation after a blank line,
 *  - `\[ … \]` display math, whose loop scans forward across blank lines.
 *
 * A mismatch means the live render shows a different block structure than the
 * frozen render of the same text, so the block re-lays out (flashes) at the
 * streaming -> frozen edge — the class of bug d24130e26 was about.
 *
 * Equivalence is compared on block `raw` via the production test hooks
 * [debugParseBlockRaws] / [debugParseIncrementalRaws].
 */
class Review0923IncrementalParseEquivalenceTest {

    private fun chunked(full: String, step: Int): List<String> =
        generateSequence(step) { it + step }.takeWhile { it < full.length }
            .map { full.substring(0, it) }.toList() + listOf(full)

    private fun assertEquivalent(full: String, step: Int = 7) {
        assertEquals(
            "incremental replay must produce the same blocks as one full parse",
            debugParseBlockRaws(full),
            debugParseIncrementalRaws(chunked(full, step)),
        )
    }

    // ── constructs that cross a blank line (currently FAIL: real bug) ──────

    @Test
    fun `loose bullet list streams as one list, like the full parse`() {
        assertEquivalent("Intro:\n\n- alpha\n\n- beta\n\n- gamma\n")
    }

    @Test
    fun `loose ordered list with an indented continuation streams like the full parse`() {
        assertEquivalent("1. **Install**\n\n   run the installer\n\n2. **Configure**\n\n   edit the file\n")
    }

    @Test
    fun `bracket display math spanning a blank line streams like the full parse`() {
        assertEquivalent("Before\n\n\\[\na = 1\n\nb = 2\n\\]\n\nAfter\n")
    }

    // ── constructs that stop at a blank line (pass: pin they stay safe) ────

    @Test
    fun `blockquotes separated by a blank line`() {
        assertEquivalent("> first quote\n\n> second quote\n\ntail\n")
    }

    @Test
    fun `table followed by a paragraph`() {
        assertEquivalent("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\nAfter the table.\n")
    }

    @Test
    fun `a dollar-dollar line inside a code fence does not corrupt later blocks`() {
        // isSealedBoundary counts the `$$` inside the fence as an open math
        // span, so nothing after it is ever frozen. That costs re-parse work
        // but must never change the result.
        val d = "$" + "$"
        assertEquivalent("```sh\necho $d\n$d\n```\n\npara one\n\npara two\n")
    }

    @Test
    fun `an unclosed dollar-dollar line is plain text in both parses`() {
        val d = "$" + "$"
        assertEquivalent("cost is $d\n\n" + d + " not math\n\nnext para\n\nlast\n")
    }
}
