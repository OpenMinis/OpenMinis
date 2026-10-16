package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-regex-bomb] Pins the linear scanner that replaced the regex-based
 * reasoning-tag stripper. Three jobs:
 *
 *  1. **Semantics** — the edge matrix the old regexes were verified against
 *     (paired / unterminated / special-token / nested / stray-close /
 *     case / whitespace / CJK forms, fence and inline-code protection).
 *     Expected values were cross-checked against a Python port of the old
 *     regex pipeline before being written down.
 *  2. **Robustness on pathological input** — the shapes that killed the
 *     regex implementation in production (native matcher allocation storm
 *     and backtracking stack overflow). The scanner must complete them in
 *     bounded linear time, not just survive.
 *  3. **No-regex source guard** — the scanner file must never grow a
 *     `Regex`/`Pattern` again; that is the regression this fix exists to
 *     prevent.
 */
class ReasoningTagStripTest {

    private fun strip(text: String): String = ReasoningTagStrip.strip(text)

    // ── 1. semantics: the verified edge matrix ──

    @Test
    fun pairedNamedBlocksAreRemoved() {
        assertEquals("answer", strip("answer<thinking>secret</thinking>"))
        assertEquals("answer", strip("<thinking>x</thinking>answer"))
        assertEquals("answer", strip("<antThinking>hidden</antThinking>answer"))
        assertEquals("answer", strip("<inner_thought>hidden</inner_thought>answer"))
        assertEquals("answer", strip("<scratchpad>hidden</scratchpad>answer"))
        assertEquals("ok", strip("<THINKING>upper</THINKING>ok"))
        assertEquals("正文", strip("<思考>中文思考</思考>正文"))
    }

    @Test
    fun whitespaceToleranceMatchesTheOldRegex() {
        assertEquals("ok", strip("< Thinking >spaced</ Thinking >ok"))
        assertEquals("ok", strip("<thinking >spaced2</thinking >ok"))
        assertEquals("ok", strip("<\nthinking\n>nl</\nthinking\n>ok"))
        assertEquals("ok", strip("<ANTTHINKING>mix</ANTTHINKING>ok"))
    }

    @Test
    fun unterminatedOpenStripsToEndOfText() {
        assertEquals("开头", strip("开头<thinking>Inspecting exact blocks\nAdding state"))
        assertEquals("", strip("<thinking>tail"))
        assertEquals("", strip("<thinking>a<thinking>b"))
        // paired pair first, then the unpaired tail open strips to end
        assertEquals("AB", strip("A<thinking>x</thinking>B<thinking>unclosed tail"))
        assertEquals("ac", strip("a<thinking>b</thinking>c<thinking>tail"))
    }

    @Test
    fun strayCloseStaysVerbatim() {
        assertEquals("</thinking>tail", strip("</thinking>tail"))
        assertEquals("close</thinking>first", strip("close</thinking>first"))
    }

    @Test
    fun specialTokenFormsAreRemoved() {
        assertEquals("", strip("<|thinking|>x<|/thinking|>"))
        assertEquals("y", strip("<|think|>x<|/think|>y"))
        assertEquals("y", strip("<|reasoning|>x<|/reasoning|>y"))
        assertEquals("y", strip("<|THINK|>x<|/think|>y"))
        assertEquals("b", strip("<|thinking|>a<|/thinking|>b<thinking>c"))
    }

    @Test
    fun nestedAndInterleavedFormsCollapseLikeTheLazyRegexDid() {
        // lazy .*? from the first open to the first same-name close eats everything inside;
        // the trailing close is then stray and stays (old regex: single replace pass)
        assertEquals("c</thinking>", strip("<thinking>a<thinking>b</thinking>c</thinking>"))
        assertEquals("", strip("<thinking><scratchpad></scratchpad></thinking>"))
        assertEquals("</scratchpad>", strip("<thinking><scratchpad></thinking></scratchpad>"))
        assertEquals("tail", strip("<thinking>a</thinking><thinking>b</thinking>tail"))
        assertEquals("ace", strip("a<thinking>b</thinking>c<scratchpad>d</scratchpad>e"))
    }

    @Test
    fun nonTagAngleBracketsAreUntouched() {
        assertEquals(
            "Use a < b for comparison. And <div>HTML</div> stays.",
            strip("Use a < b for comparison. And <div>HTML</div> stays."),
        )
        assertEquals("a<b<c and <notatag> stays", strip("a<b<c and <notatag> stays"))
    }

    @Test
    fun degenerateFragmentsStayOrStripPerTheOldRules() {
        assertEquals("", strip(""))
        assertEquals("<", strip("<"))
        assertEquals("<>", strip("<>"))
        assertEquals("<thinking", strip("<thinking"))
        // a bare open with no close anywhere is an unterminated block: strips to end
        assertEquals("", strip("<thinking>"))
        assertEquals("</thinking>", strip("</thinking>"))
        assertEquals("<|", strip("<|"))
        assertEquals("<|thinking|", strip("<|thinking|"))
    }

    @Test
    fun codeFencesProtectTheirContent() {
        // whole fenced block round-trips verbatim (fence lines included)
        val fenced = "```html\n<thinking>not reasoning, just demo markup</thinking>\n```\ndone"
        assertEquals(fenced, strip(fenced))
        val tildes = "~~~\n<thinking>demo markup</thinking>\n~~~\nbody"
        assertEquals(tildes, strip(tildes))
        // fence kept, prose after it stripped
        assertEquals(
            "```\ncode\n````\nafter  end",
            strip("```\ncode\n````\nafter <thinking>tail</thinking> end"),
        )
        assertEquals(
            "before  after\n```\ncode line\n```\n",
            strip("before <thinking>secretreasoning</thinking> after\n```\ncode line\n```\n"),
        )
        assertEquals(
            "```\nfirst\n```\n\n```\nsecond\n```\n",
            strip("```\nfirst\n```\n<thinking>middle</thinking>\n```\nsecond\n```\n"),
        )
    }

    @Test
    fun inlineCodeMasksTagCharacters() {
        // the tag name inside backticks is inline code: survives verbatim
        val text = "Use the `thinking` tag to mark reasoning."
        assertEquals(text, strip(text))
        assertEquals("single ` here", strip("single ` here"))
        assertEquals("````", strip("````"))
    }

    @Test
    fun maskingRoundTripsOddBacktickRuns() {
        // ``x`` masks as one span; a lone ` with no partner stays
        val text = "``x`` and `y`"
        val masked = ReasoningTagStrip.maskInlineCode(text)
        assertEquals(listOf("``x``", "`y`"), masked.spans)
        assertEquals(text, ReasoningTagStrip.restoreMasked(masked.text, masked.spans))
        // 4+ backticks with no closing run mask themselves (regex within-run backtrack)
        val four = "````"
        val m4 = ReasoningTagStrip.maskInlineCode(four)
        assertEquals(listOf("````"), m4.spans)
        assertEquals(four, ReasoningTagStrip.restoreMasked(m4.text, m4.spans))
    }

    // ── 2. pathological input: linear time, no matcher storm ──

    @Test
    fun oneMegabyteOfUnterminatedOpensCompletesInBoundedTime() {
        // The shape that backtracked the regex to a stack overflow: many
        // openers, none closed, filler full of angle brackets. The scanner
        // must finish in linear time — the budget below is ~50x what the
        // scanner needs on CI hardware, but an exponential regex would need
        // minutes to hours.
        val bomb = buildString {
            repeat(20_000) {
                append("<thinking>\n")
                append("filler with < chars and <tags> inside\n")
            }
        }
        val start = System.nanoTime()
        val out = strip(bomb)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals("", out) // first open is unpaired → strips to end
        assertTrue("1MB bomb took ${elapsedMs}ms (linear scanner expects < 1000ms)", elapsedMs < 1000)
    }

    @Test
    fun longTailAfterASingleOpenIsLinear() {
        // 2MB of comparison operators after one opener: the old
        // unterminated lookahead rescanned the whole tail per candidate.
        val bomb = "<thinking>" + "x < y data ".repeat(200_000)
        val start = System.nanoTime()
        val out = strip(bomb)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals("", out)
        assertTrue("2MB tail took ${elapsedMs}ms (linear scanner expects < 2000ms)", elapsedMs < 2000)
    }

    @Test
    fun nearMissCloseTagsDoNotBacktrack() {
        // Every "almost </thinking>" is a near-miss the regex engine had to
        // enter and abandon; 680k chars of them. "thinkingx" is not a tag
        // name, so the opener stays unpaired and strips to end.
        val bomb = "<thinking>" + "almost </thinkingx> but not quite ".repeat(20_000)
        assertEquals("", strip(bomb))
    }

    @Test
    fun manyPairedBlocksStripCorrectly() {
        val bomb = buildString {
            repeat(50_000) { append("<thinking>reasoning</thinking>") }
            append("answer")
        }
        assertEquals("answer", strip(bomb))
    }

    @Test
    fun repeatedStripIsIdempotent() {
        val once = strip("a<thinking>b</thinking>c<thinking>tail")
        assertEquals("ac", once)
        assertEquals(once, strip(once))
    }

    // ── 3. no-regex source guard ──

    @Test
    fun scannerSourceContainsNoRegex() {
        // [T-regex-bomb] The fix exists because regexes on this path
        // allocated native ICU matchers per call and backtracked to a stack
        // overflow. If someone reintroduces Regex/Pattern here, the whole
        // class of defects comes back. Fail the build instead.
        val source = ProductionSources.read("agent/ReasoningTagStrip.kt")
        assertFalse("ReasoningTagStrip.kt must not use Regex(", source.contains("Regex("))
        assertFalse("ReasoningTagStrip.kt must not use toRegex()", source.contains("toRegex()"))
        assertFalse("ReasoningTagStrip.kt must not use Pattern.compile", source.contains("Pattern.compile"))
    }

    @Test
    fun sendPathStillRoutesThroughTheStripper() {
        // The delegate in MessageTransformers must keep calling the scanner —
        // a wiring regression would silently stop all stripping.
        assertEquals("answer", MessageTransformerChain.apply("<thinking>x</thinking>answer"))
    }
}
