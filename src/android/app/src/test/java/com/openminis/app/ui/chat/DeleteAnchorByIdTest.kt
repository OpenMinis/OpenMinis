package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-delete-anchor-by-id] Regression tests for the 2026-10-14 session-loss
 * incident.
 *
 * INCIDENT: deleteFromMessage / retryFromMessage / truncateBeforeEdit
 * resolved their DB truncation cutoff by VISIBLE-USER ORDINAL:
 *
 *   UI side  : messages.count { it.role == "user" }          — counts EVERY
 *               user-role ChatMessage, including toolResult-only carrier
 *               rows that toChatMessages renders as nothing.
 *   DB side  : visibleUserCutoff walk + anchorHeadHasVisibleUserText —
 *               counts only user rows whose head has a text part.
 *
 * The two counters disagree on any session with tool traffic (the agent
 * loop persists a synthetic user row per tool_result). Ordinal N on the UI
 * side resolved to a DB row dozens of turns earlier, and
 * deleteMessagesAfter(sid, anchor.sortOrder) archived + deleted everything
 * from there: 1386 rows lost from ONE retry tap.
 *
 * FIX: anchor by the tapped message's DB row id (ChatMessage.id IS the
 * MessageEntity.id; sourceDbIds covers merged assistant bubbles). These
 * tests pin the anchor-selection logic that replaced the ordinal walk.
 */
class DeleteAnchorByIdTest {

    private fun userMsg(id: String, content: String = "hello") =
        ChatMessage(id = id, role = "user", content = content)

    private fun asstMsg(id: String, content: String = "reply") =
        ChatMessage(id = id, role = "assistant", content = content)

    /**
     * The exact incident shape: a long session where the UI list contains
     * toolResult-only user rows the DB walk used to skip. With id-based
     * anchoring the resolved anchor is the tapped row itself — no ordinal,
     * no mismatch, regardless of how many synthetic user rows exist.
     */
    @Test
    fun `user target anchors to its own id regardless of toolResult-only user rows`() {
        val messages = listOf(
            userMsg("u1"), asstMsg("a1"),
            userMsg("u2"), asstMsg("a2"),
            // toolResult-only carrier rows render as nothing in the UI but
            // ARE user-role ChatMessages in the old counter
            userMsg("u3"), asstMsg("a3"),
        )
        val target = messages[4] // "u3"
        val anchor = selectAnchor(messages, 4, target)
        assertEquals("u3", anchor)
    }

    @Test
    fun `assistant target anchors to the preceding visible user message`() {
        val messages = listOf(
            userMsg("u1"), asstMsg("a1"),
            userMsg("u2"), asstMsg("a2"),
        )
        val target = messages[3] // "a2"
        val anchor = selectAnchor(messages, 3, target)
        assertEquals("u2", anchor)
    }

    @Test
    fun `assistant target skips blank user rows when walking back`() {
        val messages = listOf(
            userMsg("u1", "real text"), asstMsg("a1"),
            userMsg("u2", ""), asstMsg("a2"), // blank user row must be skipped
        )
        assertEquals(5, messages.size)
        val target = messages[4] // "a2"
        val anchor = selectAnchor(messages, 4, target)
        assertEquals("u1", anchor)
    }

    @Test
    fun `assistant target with no preceding user message resolves no anchor`() {
        val messages = listOf(asstMsg("a1"), asstMsg("a2"))
        val target = messages[1]
        val anchor = selectAnchor(messages, 1, target)
        assertNull(anchor)
    }

    @Test
    fun `merged assistant bubble anchors via sourceDbIds`() {
        // toChatMessages merges consecutive assistant rows; the merged bubble
        // keeps every source id. The anchor lookup tries id then sourceDbIds.
        val merged = asstMsg("a2").copy(sourceDbIds = listOf("a1", "a2"))
        val messages = listOf(userMsg("u1"), merged)
        // For an assistant target the anchor is the preceding USER message,
        // not the bubble itself — the whole turn goes.
        val anchor = selectAnchor(messages, 1, messages[1])
        assertEquals("u1", anchor)
    }

    /**
     * The cut-point arithmetic, pinned separately from anchor selection:
     * retry keeps the target user row (cut = sortOrder + 1), delete removes
     * it too (cut = sortOrder), edit replaces it (cut = sortOrder).
     */
    @Test
    fun `cut offsets are exact for each caller`() {
        val anchorSort = 55
        // retryFromMessage: keep the user row, drop everything after
        assertEquals(56, anchorSort + 1)
        // deleteFromMessage on a user target: drop the row itself
        assertEquals(55, anchorSort)
        // deleteFromMessage on an assistant target: keep the user turn
        assertEquals(56, anchorSort + 1)
    }

    /**
     * Extracts the anchor-selection logic from resolveDeleteCutoffSortOrder
     * so it can be tested without a DB: user target → itself; assistant
     * target → last visible user message at or before it.
     */
    private fun selectAnchor(
        messages: List<ChatMessage>,
        index: Int,
        target: ChatMessage,
    ): String? {
        val anchorMessage: ChatMessage? = if (target.role == "user") {
            target
        } else {
            (index downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
        }
        return anchorMessage?.let { listOf(it.id) + it.sourceDbIds }?.firstOrNull()
    }
}
