package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming overlay must not copy the whole transcript per tick: frozen
 * messages stay reference-identical, and only the streamed row is rebuilt.
 */
class StreamingOverlayTest {

    private val base = listOf(
        ChatMessage(id = "u1", role = "user", content = "hi"),
        ChatMessage(id = "a1", role = "assistant", content = "partial"),
        ChatMessage(id = "a2", role = "assistant", content = "other"),
    )

    @Test
    fun emptyStreamingReturnsTheSameList() {
        assertSame(base, mergeStreamingOverlay(base, emptyMap()))
    }

    @Test
    fun onlyTheStreamedRowIsMaterialized() {
        val merged = mergeStreamingOverlay(
            base,
            mapOf(
                "a1" to StreamingDelta(
                    content = "partial reply",
                    toolBlocks = emptyList(),
                    isAwaitingModelResponse = false,
                ),
            ),
        )
        assertEquals(base.size, merged.size)
        assertSame(base[0], merged[0])
        assertSame(base[2], merged[2])
        assertTrue(merged[1].isStreaming)
        assertEquals("partial reply", merged[1].content)
        assertFalse(merged[0].isStreaming)
    }

    @Test
    fun overlaidRowIsCachedPerIndex() {
        val merged = mergeStreamingOverlay(
            base,
            mapOf("a1" to StreamingDelta("x", emptyList(), false)),
        )
        assertSame(merged[1], merged[1])
    }
}
