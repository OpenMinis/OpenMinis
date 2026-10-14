package com.openminis.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-think-tag-fence-open] The fence splitter used to advance its segment
 * cursor only on the CLOSE branch, so every prose run preceding a code block was
 * classified as code and appended VERBATIM by transformProtectingCode. Reasoning
 * tags sitting before a fence — the common shape, the model thinks, then writes a
 * snippet — were therefore replayed to the provider intact, and a closing fence
 * that was longer than the opener or carried trailing spaces never closed at all,
 * turning the remainder of the reply into one code segment.
 *
 * These tests pin the PERSISTED-history path (MessageTransformerChain.apply),
 * which is what gets sent back to the model on the next turn.
 */
class FenceAwareTagStripperTest {

    private fun apply(text: String): String = MessageTransformerChain.apply(text)

    @Test
    fun `tag in prose before a code fence is stripped while the code survives`() {
        val text = "before <thinking>secretreasoning</thinking> after\n```\ncode line\n```\n"
        val out = apply(text)
        assertFalse("reasoning leaked before the fence: $out", out.contains("secretreasoning"))
        assertTrue("code block must be preserved verbatim: $out", out.contains("```\ncode line\n```"))
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after"))
    }

    @Test
    fun `tag between two fences is stripped`() {
        val text = "```\nfirst\n```\n<thinking>middle</thinking>\n```\nsecond\n```\n"
        val out = apply(text)
        assertFalse("reasoning between fences leaked: $out", out.contains("middle"))
        assertTrue(out.contains("first"))
        assertTrue(out.contains("second"))
    }

    @Test
    fun `longer closing fence closes the block`() {
        // CommonMark: a closing fence may be longer than the opener.
        val text = "```\ncode\n````\nafter <thinking>tail</thinking> end\n"
        val out = apply(text)
        assertFalse("reasoning after a long close leaked: $out", out.contains("tail"))
    }

    @Test
    fun `closing fence with trailing spaces closes the block`() {
        val text = "```\ncode\n```  \nafter <thinking>tail</thinking> end\n"
        val out = apply(text)
        assertFalse("reasoning after a padded close leaked: $out", out.contains("tail"))
    }

    @Test
    fun `tilde fence behaves like the backtick fence`() {
        val text = "hi <thinking>gone</thinking>\n~~~\ncode\n~~~\n"
        val out = apply(text)
        assertFalse(out.contains("gone"))
        assertTrue(out.contains("~~~\ncode\n~~~"))
    }

    @Test
    fun `inline code span in prose is still protected and the tag still goes`() {
        val text = "run `<cmd>` now <thinking>why</thinking> done\n```\nfenced\n```\n"
        val out = apply(text)
        assertFalse("reasoning leaked around inline code: $out", out.contains("why"))
        assertTrue("inline code span must survive: $out", out.contains("`<cmd>`"))
        assertTrue(out.contains("fenced"))
    }

    @Test
    fun `unterminated fence still protects its body`() {
        // An assistant turn cut off mid-block: everything after the opener is
        // code, so a `<thinking>` string inside it must NOT be treated as markup.
        val text = "lead <thinking>gone</thinking>\n```\nnot a <thinking>literal</thinking> tag\n"
        val out = apply(text)
        assertFalse("prose reasoning leaked: $out", out.contains("gone</thinking>"))
        assertTrue("literal inside unterminated fence must survive: $out", out.contains("literal"))
    }
}
