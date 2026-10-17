package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live suffix build must be row-for-row identical to the tail of a full
 * build. `splitRounds(fromIndex)` now starts at the round containing
 * `fromIndex - 1`, so a mismatch here would mean earlier rounds leaked into
 * (or were lost from) the live rows.
 */
class ChatFlatItemsSplitTest {

    private fun message(id: String, role: String, text: String) = ChatMessage(
        id = id,
        role = role,
        content = text,
    )

    private val messages = listOf(
        message("u1", "user", "first question"),
        message("a1", "assistant", "first answer"),
        message("u2", "user", "second question"),
        message("a2", "assistant", "second answer"),
    )

    @Test
    fun splitBuildMatchesTheSuffixOfAFullBuild() {
        val full = buildFlatChatItems(messages, foldAiProcess = true)
        val split = buildFlatChatItems(
            messages,
            fromIndex = 2,
            foldAiProcess = true,
            processCardBoundary = 2,
        )
        val fullKeys = full.map { it.key }
        val splitKeys = split.map { it.key }
        assertTrue("split must not be empty", splitKeys.isNotEmpty())
        assertEquals(fullKeys.takeLast(splitKeys.size), splitKeys)
    }

    @Test
    fun splitAtTheEndOnlyBuildsTheBoundaryRound() {
        val split = buildFlatChatItems(
            messages,
            fromIndex = messages.size,
            foldAiProcess = true,
            processCardBoundary = messages.size,
        )
        // Rows for the tail round (u2/a2) may still be emitted for the
        // boundary card/lookbacks, but nothing before it may appear.
        assertTrue(split.none { it.key.contains("u1") || it.key.contains("a1") })
    }
}
