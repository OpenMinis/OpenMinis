package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-display-strip-unterminated] Pins the DISPLAY variant of the reasoning-tag
 * stripper (`stripForDisplay` / `applyForDisplay`), wired into ChatViewModel's
 * restored-bubble hydration.
 *
 * The defect this guards against: an assistant reply that *bare-mentions* a
 * reasoning-tag spelling while discussing the stripper itself — e.g. a reply
 * explaining "把 4 个正则(<思考>…)替换成了线性扫描器" — went through the
 * send-path rule "unterminated open strips to end-of-text", so everything
 * after the mention was deleted from the restored bubble and the message
 * rendered as thinking-card-only. The live stream had shown the body fine;
 * only the DB-reload path lost it.
 *
 * Display mode contract:
 *  - paired blocks are still removed whole (a paired block is a real
 *    reasoning span; the prose after its close survives either way);
 *  - an unterminated open removes ONLY the tag literal — the tail is prose;
 *  - fence and inline-code protection are identical to the send path;
 *  - the send path itself is unchanged (replay must never see an open tag).
 */
class ReasoningTagDisplayModeTest {

    private fun strip(text: String): String = ReasoningTagStrip.strip(text)
    private fun display(text: String): String = ReasoningTagStrip.stripForDisplay(text)

    // ── the reported defect: bare mention eats the tail ──

    @Test
    fun displayKeepsTailAfterBareMention() {
        val text = "把 <思考> 标签剥掉后正文保留。命令：git push。"
        // Send path: unterminated open strips to end-of-text.
        assertEquals("把 ", strip(text))
        // Display path: only the tag literal goes; the prose tail survives.
        assertEquals("把  标签剥掉后正文保留。命令：git push。", display(text))
    }

    @Test
    fun displayKeepsTailAfterBareLatinMention() {
        val text = "PR56 把 4 个正则(<thinking>…)换成了线性扫描器。提交命令：git push。"
        assertEquals("PR56 把 4 个正则(…)换成了线性扫描器。提交命令：git push。", display(text))
    }

    @Test
    fun displayKeepsTailAfterUnterminatedSpecialToken() {
        val text = "旧实现是 <|thinking|> 形态的正则。正文继续。"
        assertEquals("旧实现是  形态的正则。正文继续。", display(text))
    }

    // ── paired blocks still removed whole in display mode ──

    @Test
    fun displayRemovesPairedBlocks() {
        assertEquals("answer", display("<thinking>secret</thinking>answer"))
        assertEquals("正文", display("<思考>中文思考</思考>正文"))
        assertEquals("answer", display("<inner_thought>hidden</inner_thought>answer"))
        // nested: display keeps the outer open's body as prose (adjacent
        // pairing only collapses the inner pair); send keeps the old
        // lazy-pairing shape.
        assertEquals("ac</thinking>d", display("<thinking>a<thinking>b</thinking>c</thinking>d"))
        assertEquals("c</thinking>d", strip("<thinking>a<thinking>b</thinking>c</thinking>d"))
    }

    // ── mention + real pair in one text ──

    @Test
    fun displayMentionThenPairRemovesPairKeepsTail() {
        val text = "先说 <thinking> 是开标签。然后模型输出 <thinking>真思考</thinking>正文。"
        // First open is a bare mention (no close) → literal removed, tail kept.
        // Second open pairs with the close → whole span removed.
        assertEquals("先说  是开标签。然后模型输出 正文。", display(text))
    }

    // ── protection identical to send path ──

    @Test
    fun displayProtectsFencesAndInlineCode() {
        val fenced = "用法：\n```\n<thinking>思考</thinking>正文\n```\n如上。"
        assertEquals(fenced, display(fenced))
        val inline = "用 `<thinking>` 标记，正文继续。"
        assertEquals(inline, display(inline))
    }

    // ── send path unchanged ──

    @Test
    fun sendPathStillStripsToEof() {
        assertEquals("把 ", strip("把 <思考> 标签剥掉后正文保留。命令：git push。"))
        assertEquals("a", strip("a<thinking>tail without close"))
    }

    // ── chain wiring ──

    @Test
    fun chainApplyForDisplayMatchesStripForDisplay() {
        val text = "把 <思考> 标签剥掉后正文保留。"
        assertEquals(ReasoningTagStrip.stripForDisplay(text), MessageTransformerChain.applyForDisplay(text))
    }

    @Test
    fun chainApplyStillMatchesSendStrip() {
        val text = "把 <思考> 标签剥掉后正文保留。"
        assertEquals(ReasoningTagStrip.strip(text), MessageTransformerChain.apply(text))
    }

    // ── source-drift guard: hydration must call the display variant ──

    @Test
    fun hydrationCallsDisplayVariantNotSendVariant() {
        val src = ProductionSources.read("ui/chat/ChatViewModel.kt")
        // The restored-bubble strip site must use applyForDisplay.
        assertTrue(
            "hydration strip must call applyForDisplay (bare mentions must not lose their tail)",
            src.contains("MessageTransformerChain.applyForDisplay(it)"),
        )
        // And the restored-bubble site must no longer call the send-path apply.
        val idx = src.indexOf("Last-resort UI")
        assertTrue(idx >= 0)
        val window = src.substring(idx, (idx + 1200).coerceAtMost(src.length))
        assertFalse(
            "restored-bubble strip site must not use send-path apply()",
            window.contains("MessageTransformerChain.apply(it)"),
        )
    }
}
