package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-largecontent-guard-position] Proves where the 32K large-content
 * guard actually applies.
 *
 * `shouldCollapse` compares ONE string against LARGE_MESSAGE_THRESHOLD_CHARS
 * (32_000). The render path calls it per FlatChatItem, and for an assistant
 * text block that item is a *fragment* produced by
 * `splitMarkdownIntoBlockTexts` + `coalesceMarkdownFragments` — the latter
 * budgeted at 2000 chars. So for ordinary prose the guard can never fire no
 * matter how large the message is, because nothing that reaches it is ever
 * big enough. These tests pin that behaviour down rather than assuming it.
 */
class LargeContentGuardThresholdTest {

    /** Paragraph-shaped body: many blank-line-separated paragraphs. */
    private fun prose(totalChars: Int): String {
        val para = "word ".repeat(40).trim()          // ~199 chars, no blank line
        val sb = StringBuilder()
        while (sb.length < totalChars) sb.append(para).append("\n\n")
        return sb.toString()
    }

    @Test
    fun `whole message is far above the guard threshold`() {
        val body = prose(200_000)
        assertTrue(
            "precondition: test body must exceed the 32K threshold",
            body.length > LARGE_MESSAGE_THRESHOLD_CHARS,
        )
        assertTrue(
            "the guard WOULD fire if it saw the whole message",
            shouldCollapse(body, isStreaming = false),
        )
    }

    /**
     * THE BUG: after the flatten split, every fragment the guard actually sees
     * is <= 2000 chars, so `shouldCollapse` is false for all of them. A 200KB
     * message renders in full through the markdown parser with the 32K
     * protection never engaging.
     */
    @Test
    fun `no post-split fragment can ever trigger the guard`() {
        val body = prose(200_000)
        val fragments = coalesceMarkdownFragments(splitMarkdownIntoBlockTexts(body))

        assertTrue("splitter must produce fragments", fragments.isNotEmpty())

        val collapsing = fragments.count { shouldCollapse(it, isStreaming = false) }
        val largest = fragments.maxOf { it.length }

        assertEquals(
            "no fragment triggers the guard — largest fragment is $largest chars, " +
                "threshold is $LARGE_MESSAGE_THRESHOLD_CHARS",
            0, collapsing,
        )
        assertTrue(
            "every fragment sits under the coalesce budget (largest=$largest)",
            largest <= 2_000,
        )
    }

    /**
     * The one shape that DOES still reach the guard: a single paragraph with no
     * blank line, which `coalesceMarkdownFragments` deliberately keeps whole
     * rather than dropping. So the guard is not 100% dead code — it is dead for
     * ordinary prose, which is the common case.
     */
    @Test
    fun `a single unsplittable oversized paragraph still reaches the guard`() {
        val oneParagraph = "x".repeat(50_000)          // no blank lines at all
        val fragments = coalesceMarkdownFragments(splitMarkdownIntoBlockTexts(oneParagraph))

        assertEquals("stays as one fragment", 1, fragments.size)
        assertTrue(
            "this shape DOES trip the guard",
            shouldCollapse(fragments.first(), isStreaming = false),
        )
    }

    // ---- after [T-android-largecontent-guard-position] ----

    private fun assistantWithText(content: String, streaming: Boolean) = ChatMessage(
        id = "m1",
        role = "assistant",
        content = content,
        isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "b1", kind = "text", content = content)),
    )

    /**
     * THE FIX: an oversized FROZEN block is emitted as a single un-split item,
     * so the item the guard sees carries the real size and the collapse path
     * engages.
     */
    @Test
    fun `oversized frozen block is emitted un-split so the guard can fire`() {
        val body = prose(200_000)
        val items = buildFlatChatItems(listOf(assistantWithText(body, streaming = false)))
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertEquals("exactly one un-split row for the oversized block", 1, blocks.size)
        assertEquals("row carries the whole block", body.length, blocks.first().rawText.length)
        assertTrue(
            "the guard now fires on what the renderer actually receives",
            shouldCollapse(blocks.first().rawText, isStreaming = blocks.first().isStreaming),
        )
    }

    /** A normal-sized message must still be split into fragments as before. */
    @Test
    fun `a small frozen message is still split normally`() {
        val body = prose(10_000)
        val items = buildFlatChatItems(listOf(assistantWithText(body, streaming = false)))
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertTrue("small message still produces multiple fragments", blocks.size > 1)
        assertTrue(
            "and none of them trips the guard",
            blocks.none { shouldCollapse(it.rawText, isStreaming = it.isStreaming) },
        )
    }

    /**
     * Streaming must NOT take the un-split path: collapsing mid-stream would
     * flicker, and the fine-grained tail fragments are what keep per-token
     * re-parse cheap.
     */
    @Test
    fun `an oversized streaming block keeps its fragmented rows`() {
        val body = prose(200_000)
        val items = buildFlatChatItems(listOf(assistantWithText(body, streaming = true)))
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertTrue("streaming keeps many fragments (got ${blocks.size})", blocks.size > 1)
    }

    @Test
    fun `streaming content is never collapsed regardless of size`() {
        val body = prose(200_000)
        assertFalse(shouldCollapse(body, isStreaming = true))
    }
}
