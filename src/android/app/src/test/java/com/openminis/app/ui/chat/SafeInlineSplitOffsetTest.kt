package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-inline-code-poisons-split] `safeInlineSplitOffset` decides how much
 * of a streaming message can be frozen and reused instead of re-parsed on every
 * throttle tick. Returning 0 means "no safe boundary" → the WHOLE message is
 * re-parsed, every tick.
 *
 * The reported bug (2026-09-02): a long reply kept flickering between styled and
 * raw markdown — already-bold text dropped back to literal `**…**` and re-styled
 * a moment later, repeatedly. Cause: this scanner counted `**` by parity but did
 * not know that parseInline resolves an inline-code span FIRST. One stray
 * backtick-wrapped `**` (a glob, an `ls **` example, `` `a**b` ``) therefore
 * flipped boldStar with nothing to flip it back, so no later newline could ever
 * be marked safe and the offset was pinned at 0 for the rest of the stream.
 *
 * These tests pin both halves: the offset must recover after code spans, and the
 * split must stay SEMANTICALLY safe (the caller concatenates
 * `parse(prefix) + parse(suffix)`, so a boundary inside an open span would
 * corrupt the render).
 */
class SafeInlineSplitOffsetTest {

    /** Longer than INCREMENTAL_MIN_CHARS(1500) + INCR_TAIL_MARGIN(256). */
    private fun pad(lines: Int = 90) =
        (1..lines).joinToString("\n") { "$it. 这是一行足够长的正文内容用于撑开增量解析的阈值" } + "\n"

    @Test
    fun `inline code containing double star does not pin the offset at zero`() {
        val text = "1. 通配符写法 `a**b` 注意\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "a `**` INSIDE inline code is a literal to parseInline and must not " +
                "leave the bold counter open — offset was $offset (0 means the " +
                "whole message re-parses every tick, which is the reported flicker)",
            offset > 0,
        )
    }

    @Test
    fun `offset advances as the stream grows`() {
        val head = "1. 通配符 `x**y` 说明\n" + pad()
        val short = head + "z".repeat(300)
        val long = head + pad(40) + "z".repeat(300)
        val a = safeInlineSplitOffset(short)
        val b = safeInlineSplitOffset(long)
        assertTrue("offset must be usable on the shorter buffer, got $a", a > 0)
        assertTrue(
            "a longer buffer must freeze at least as much as the shorter one " +
                "(short=$a long=$b); a shrinking boundary re-parses settled text",
            b >= a,
        )
    }

    @Test
    fun `unclosed backtick is treated as a literal, matching parseInline`() {
        // findInlineCodeClose stops at '\n', so this backtick never closes.
        // parseInline emits it literally; the scanner must agree and keep
        // scanning rather than swallowing the rest of the line.
        val text = "1. 孤立反引号 ` 后面还有 **加粗** 内容\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue("an unclosed backtick must not pin the offset, got $offset", offset > 0)
    }

    @Test
    fun `an open bold span still blocks the split`() {
        // The conservative guarantee this function exists to provide: while a
        // real `**` is open, no newline after it may be declared safe.
        val text = "1. 开头 **还没有闭合\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertEquals(
            "a genuinely open bold run must yield no safe boundary (offset=$offset)",
            0,
            offset,
        )
    }

    @Test
    fun `offset always lands right after a newline`() {
        val text = "1. 代码 `a**b` 与 **加粗** 混排\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue("expected a usable offset, got $offset", offset > 0)
        assertEquals(
            "the split must sit immediately after a '\\n' — inline constructs " +
                "reset at line boundaries, which is what makes the split safe",
            '\n',
            text[offset - 1],
        )
    }

    @Test
    fun `short input yields no split`() {
        assertEquals(0, safeInlineSplitOffset("1. 很短的内容\n"))
    }
}
