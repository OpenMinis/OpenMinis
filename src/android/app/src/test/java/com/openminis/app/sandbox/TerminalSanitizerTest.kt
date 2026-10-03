package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

class TerminalSanitizerTest {

    // ==================== sanitize: empty / plain text ====================

    @Test
    fun `sanitize returns empty string for empty input`() {
        assertEquals("", TerminalSanitizer.sanitize(""))
    }

    @Test
    fun `sanitize returns plain text unchanged`() {
        val input = "hello world"
        assertEquals(input, TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize preserves multiline plain text`() {
        val input = "line1\nline2\nline3"
        assertEquals(input, TerminalSanitizer.sanitize(input))
    }

    // ==================== sanitize: ANSI color codes ====================

    @Test
    fun `sanitize strips basic color codes`() {
        // ESC[31m = red, ESC[0m = reset
        val input = "\u001B[31mERROR\u001B[0m: something failed"
        assertEquals("ERROR: something failed", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize strips bold and multi-param SGR codes`() {
        // ESC[1;32m = bold green
        val input = "\u001B[1;32m✓ pass\u001B[0m"
        assertEquals("✓ pass", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize strips cursor movement sequences`() {
        // ESC[2J = clear screen, ESC[H = home
        val input = "\u001B[2J\u001B[Hhello"
        assertEquals("hello", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize strips OSC sequences with BEL terminator`() {
        // OSC title set: ESC]0;titleBEL
        val input = "\u001B]0;my-terminal\u0007some text"
        assertEquals("some text", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize strips OSC sequences with ST terminator`() {
        // OSC terminated by ESC\
        val input = "\u001B]0;title\u001B\\content"
        assertEquals("content", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize strips multiple ANSI codes in one line`() {
        val input = "\u001B[1m\u001B[34mblue bold\u001B[0m normal \u001B[31mred\u001B[0m"
        assertEquals("blue bold normal red", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize strips erase line sequence`() {
        // ESC[K = erase to end of line
        val input = "progress\u001B[Kdone"
        assertEquals("progressdone", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles 256-color and truecolor codes`() {
        // ESC[38;5;196m = 256-color red
        val input = "\u001B[38;5;196mred text\u001B[0m"
        assertEquals("red text", TerminalSanitizer.sanitize(input))
    }

    // ==================== sanitize: carriage return folding ====================

    @Test
    fun `sanitize folds simple CR overwrite`() {
        // "AAAA\rBB" → "BBAA" (BB overwrites first two chars)
        val input = "AAAA\rBB"
        assertEquals("BBAA", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize folds progress-style CR`() {
        // Simulates progress bar: 10%\r50%\r100%
        val input = "10%\r50%\r100%"
        assertEquals("100%", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles CR at start of line`() {
        val input = "\rHello"
        assertEquals("Hello", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize preserves newlines across CR folding`() {
        val input = "line1\rLINE\nline2"
        assertEquals("LINE1\nline2", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles CRLF line endings`() {
        // \r\n should be treated as line feed, not overwrite
        // split('\n') gives ["line1\r", "line2\r", "line3"]
        // The \r in "line1\r" splits to ["line1", ""] — empty segment skipped → "line1"
        val input = "line1\r\nline2\r\nline3"
        assertEquals("line1\nline2\nline3", TerminalSanitizer.sanitize(input))
    }

    // ==================== sanitize: combined ANSI + CR ====================

    @Test
    fun `sanitize strips ANSI after CR folding`() {
        // Both segments are the same length (escapes included), so the second
        // fully overwrites the first; CR folding yields "\u001B[32mcomplete   \u001B[0m".
        // The trailing spaces then go to sanitize()'s final outer trim (pass 5,
        // edda1e7d1) — not to CR folding, which keeps them.
        val input = "\u001B[32mdownloading\u001B[0m\r\u001B[32mcomplete   \u001B[0m"
        assertEquals("complete", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles real-world wget output`() {
        // wget progress: "  50%[=====>    ] 500K  --.-KB/s\r 100%[=========>] 1.0M  1.5MB/s"
        // Equal-length segments: full overwrite gives " 100%[=========>]", and
        // the leading space is removed by sanitize()'s final outer trim
        // (pass 5, edda1e7d1), not by CR folding.
        val input = "  50%[=====>    ]\r 100%[=========>]"
        assertEquals("100%[=========>]", TerminalSanitizer.sanitize(input))

        // A SHORTER later segment must leave the old tail visible, exactly as a
        // terminal renders it: " 100" lands on "  50" and the "%[=====>    ]"
        // beyond it stays. "Last non-empty segment wins" would drop the bar.
        val shrinking = "  50%[=====>    ]\r 100"
        assertEquals("100%[=====>    ]", TerminalSanitizer.sanitize(shrinking))
    }

    // ==================== truncateIfNeeded ====================

    @Test
    fun `truncateIfNeeded returns short string unchanged`() {
        val input = "short"
        assertSame(input, TerminalSanitizer.truncateIfNeeded(input, 100))
    }

    @Test
    fun `truncateIfNeeded returns string at exact limit unchanged`() {
        val input = "a".repeat(100)
        assertSame(input, TerminalSanitizer.truncateIfNeeded(input, 100))
    }

    @Test
    fun `truncateIfNeeded truncates long string preserving head and tail`() {
        val input = "A".repeat(50) + "B".repeat(50) + "C".repeat(50)
        val result = TerminalSanitizer.truncateIfNeeded(input, 100)

        // Should contain head (first 50 chars of A's)
        assertTrue(result.startsWith("A".repeat(50)))
        // Should contain tail (last 50 chars of C's)
        assertTrue(result.endsWith("C".repeat(50)))
        // Should contain omission marker
        assertTrue(result.contains("[... 50 characters omitted ...]"))
    }

    @Test
    fun `truncateIfNeeded with default 50000 char limit`() {
        val short = "a".repeat(49_999)
        assertSame(short, TerminalSanitizer.truncateIfNeeded(short))

        val long = "x".repeat(60_000)
        val result = TerminalSanitizer.truncateIfNeeded(long)
        assertTrue(result.contains("[... 10000 characters omitted ...]"))
        assertTrue(result.length < 60_000)
    }

    @Test
    fun `truncateIfNeeded omitted count is accurate`() {
        val input = "x".repeat(200)
        val result = TerminalSanitizer.truncateIfNeeded(input, 100)
        assertTrue(result.contains("[... 100 characters omitted ...]"))
    }

    // ==================== Edge cases ====================

    @Test
    fun `sanitize handles only ANSI codes no text`() {
        val input = "\u001B[31m\u001B[0m"
        assertEquals("", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles only CR`() {
        val input = "\r"
        // split("\r") = ["", ""], both empty → buffer stays 1-char default
        // but bufLen is 0, so append(buffer, 0, 0) = ""
        assertEquals("", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles unicode text with ANSI`() {
        val input = "\u001B[33m你好世界\u001B[0m"
        assertEquals("你好世界", TerminalSanitizer.sanitize(input))
    }

    @Test
    fun `sanitize handles tab characters`() {
        val input = "col1\tcol2\tcol3"
        assertEquals(input, TerminalSanitizer.sanitize(input))
    }

    // ==================== [GH#326] pre-sanitize input ceiling ====================

    /**
     * [T-android-longsession-sanitize-pretrim / GH#326] A huge dump must not be
     * rewritten five times over before being thrown away.
     *
     * `sanitize` builds a fresh full-size string per pass, so peak transient
     * memory used to be several times the raw output — for a result the caller
     * immediately truncates to 50k. Those large contiguous char[] allocations
     * are what the field's `Scudo ERROR: internal map failure` self-abort is
     * made of. The ceiling is on the INPUT, so the passes now see a bounded
     * string no matter how much the shell produced.
     */
    @Test
    fun `sanitize bounds a huge input instead of rewriting all of it`() {
        val huge = "x".repeat(5_000_000)
        val out = TerminalSanitizer.sanitize(huge)
        assertTrue(
            "a 5M-char dump must not survive sanitize at full size (was ${out.length})",
            out.length < 1_000_000,
        )
        assertTrue("the omission must be reported", out.contains("characters omitted"))
    }

    /**
     * The margin is what keeps this invisible: anything that could still fit
     * under the caller's 50k cap after cleaning must come through untouched.
     */
    @Test
    fun `sanitize leaves ordinary output byte-identical`() {
        val ordinary = (1..2_000).joinToString("\n") { "line $it: some ordinary build output" }
        assertEquals(ordinary, TerminalSanitizer.sanitize(ordinary))
    }

    /** Escape stripping still works on an input large enough to be pre-trimmed. */
    @Test
    fun `sanitize still strips escapes in a pre-trimmed input`() {
        val noisy = "\u001B[31mred\u001B[0m " + "y".repeat(2_000_000)
        val out = TerminalSanitizer.sanitize(noisy)
        assertFalse("ANSI escapes must still be gone", out.contains("\u001B"))
        assertTrue(out.startsWith("red "))
    }

    /**
     * The head/tail shape the caller depends on: a pre-trimmed result still
     * carries both ends, so `truncateIfNeeded`'s own 50k window is taken from
     * real head and real tail rather than from one contiguous chunk.
     */
    @Test
    fun `a pre-trimmed result keeps both head and tail`() {
        val body = "m".repeat(3_000_000)
        val out = TerminalSanitizer.sanitize("HEAD_MARKER" + body + "TAIL_MARKER")
        assertTrue("head must survive", out.contains("HEAD_MARKER"))
        assertTrue("tail must survive", out.contains("TAIL_MARKER"))
    }

}
