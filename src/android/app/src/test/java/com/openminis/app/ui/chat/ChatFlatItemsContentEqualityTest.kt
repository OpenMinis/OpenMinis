package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Same-length replacements must not be skipped by LazyColumn's stable-item
 * check. The old equals compared `rawText.length` / `segmentText.length`, so a
 * translation or retry that produced a same-length string left the previous
 * text on screen.
 */
class ChatFlatItemsContentEqualityTest {

    private fun block(
        raw: String,
        segment: String = "",
        markdown: String = raw,
    ) = FlatChatItem.AssistantMarkdownBlock(
        messageId = "m1",
        parentBlockId = "p1",
        rawText = raw,
        blockIndex = 0,
        isLastBlockOfMessage = false,
        messageIsStreaming = false,
        messageMarkdown = markdown,
        segmentText = segment,
    )

    @Test
    fun sameLengthRawTextWithDifferentContentIsNotEqual() {
        assertNotEquals(block("hello"), block("world"))
    }

    @Test
    fun sameLengthSegmentTextWithDifferentContentIsNotEqual() {
        assertNotEquals(block("same", segment = "one"), block("same", segment = "two"))
    }

    @Test
    fun identicalContentIsEqual() {
        assertEquals(block("hello", segment = "x"), block("hello", segment = "x"))
    }

    @Test
    fun legacySameLengthContentIsNotEqual() {
        val a = FlatChatItem.AssistantLegacyContent("m1", "abcd", isStreaming = false)
        val b = FlatChatItem.AssistantLegacyContent("m1", "abce", isStreaming = false)
        assertNotEquals(a, b)
    }

    @Test
    fun legacyIdenticalContentIsEqual() {
        val a = FlatChatItem.AssistantLegacyContent("m1", "abcd", isStreaming = false)
        val b = FlatChatItem.AssistantLegacyContent("m1", "abcd", isStreaming = false)
        assertEquals(a, b)
    }
}
