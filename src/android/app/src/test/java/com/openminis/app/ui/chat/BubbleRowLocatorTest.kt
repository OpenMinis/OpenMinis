package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-bubble-anchor] Retry, delete-from-here and edit find the
 * persisted row behind a user bubble by id (iOS 6c0de68f5 / 8a1bf2e8b).
 *
 * The counting rule they replace is reproduced here as [oldEditCutoff] so the
 * regression cases show both answers side by side.
 */
class BubbleRowLocatorTest {

    private var order = 0

    private fun row(id: String, role: String, partsJson: String) =
        MessageEntity(id = id, sessionId = "s", role = role, partsJson = partsJson, createdAt = 0L, sortOrder = order++)

    private fun text(v: String) = """[{"type":"text","value":${org.json.JSONObject.quote(v)}}]"""
    private val imageOnly = """[{"type":"mediaRef","value":{"path":"a.jpg","mime":"image/jpeg"}}]"""
    private val toolResult = """[{"type":"toolResult","value":{"id":"t1","content":"ok"}}]"""

    private fun user(id: String, content: String = "x") = ChatMessage(id = id, role = "user", content = content)
    private fun assistant(id: String) = ChatMessage(id = id, role = "assistant", content = "reply")

    /** The pre-fix edit rule: the n-th user bubble is the n-th user row with visible TEXT. */
    private fun oldEditCutoff(messages: List<ChatMessage>, index: Int, rows: List<MessageEntity>): Int {
        val ordinal = messages.subList(0, index).count { it.role == "user" }
        var n = 0
        for (r in rows) {
            if (r.role != "user") continue
            val arr = org.json.JSONArray(r.partsJson)
            val hasText = (0 until arr.length()).any { i ->
                val o = arr.getJSONObject(i)
                o.optString("type") == "text" && o.optString("value").isNotBlank() &&
                    !o.optString("value").trimStart().startsWith("<system-reminder>")
            }
            if (!hasText) continue
            if (n == ordinal) return r.sortOrder
            n++
        }
        return -1
    }

    // ── end-of-run queue drain [T-android-bubble-anchor-drain] ──────────────

    /**
     * "A", reply; two prompts queued while it ran and drained as ONE row "Q1\n\nQ2"
     * (one bubble each); reply; "B", reply.
     */
    private fun drainedSession(linked: Boolean): Pair<List<ChatMessage>, List<MessageEntity>> {
        order = 0
        val rows = listOf(
            row("u1", "user", text("A")), row("a1", "assistant", text("r1")),
            row("r2", "user", text("Q1\n\nQ2")), row("a2", "assistant", text("r2")),
            row("u3", "user", text("B")), row("a3", "assistant", text("r3")),
        )
        val link = if (linked) listOf("r2") else emptyList()
        val messages = listOf(
            user("u1", "A"), assistant("a1"),
            ChatMessage(id = "queued_msg_1", role = "user", content = "Q1", sourceDbIds = link),
            ChatMessage(id = "queued_msg_2", role = "user", content = "Q2", sourceDbIds = link),
            assistant("a2"), user("u3", "B"), assistant("a3"),
        )
        return messages to rows
    }

    @Test
    fun `editing the second of two drained prompts cuts their shared row, where counting cut the next turn`() {
        // Before the fix the drained bubbles carried no link, so the locator
        // counted: two bubbles over one row put the edit on "B" - B's turn was
        // cut and the merged row, old Q2 included, stayed in the context.
        val (unlinked, rows) = drainedSession(linked = false)
        val old = BubbleRowLocator.locateUserRow(unlinked, 3, rows)
        assertEquals(BubbleRowLocator.Via.ORDINAL, old.via)
        assertEquals("u3", old.row?.id)

        val (messages, rows2) = drainedSession(linked = true)
        val located = BubbleRowLocator.locateUserRow(messages, 3, rows2)
        assertEquals(BubbleRowLocator.Via.LINKED, located.via)
        assertEquals("r2", located.row?.id)
        assertEquals(2, BubbleRowLocator.cutoffFor(located, keepBubble = false))
        // The chat is cut from the batch's FIRST bubble: Q1 lived in the same
        // row, so leaving its bubble would show what the model no longer has.
        assertEquals(2..3, BubbleRowLocator.groupSpan(messages, 3))
    }

    @Test
    fun `retrying the first drained prompt keeps the whole batch`() {
        val (messages, rows) = drainedSession(linked = true)
        val located = BubbleRowLocator.locateUserRow(messages, 2, rows)
        assertEquals("r2", located.row?.id)
        assertEquals(3, BubbleRowLocator.cutoffFor(located, keepBubble = true))
        // Retry keeps the shared row, so the chat keeps through the LAST bubble.
        assertEquals(3, BubbleRowLocator.groupSpan(messages, 2).last)
    }

    @Test
    fun `a bubble with no link, and an assistant bubble, are groups of one`() {
        val (messages, _) = drainedSession(linked = true)
        assertEquals(0..0, BubbleRowLocator.groupSpan(messages, 0))
        assertEquals(5..5, BubbleRowLocator.groupSpan(messages, 5))
        assertEquals(4..4, BubbleRowLocator.groupSpan(messages, 4))
    }

    @Test
    fun `a drain links its bubbles, and retry, delete and edit cut the chat by group`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val drain = vm.substring(vm.indexOf("private suspend fun drainQueuedPrompts"))
        assertTrue(
            "drainQueuedPrompts must link every drained bubble to the persisted row",
            drain.substring(0, 6000).contains("sourceDbIds = listOf(drainedRow.id)"),
        )
        for (fn in listOf("fun retryFromMessage", "fun deleteFromMessage", "private suspend fun truncateBeforeEdit")) {
            val body = vm.substring(vm.indexOf(fn)).take(4000)
            assertTrue("$fn must cut the chat by group", body.contains("BubbleRowLocator.groupSpan(messages, index)"))
        }
    }

    // ── the reported shape ────────────────────────────────────────────────

    @Test
    fun `editing after an image-only turn cuts at the edited row, where counting cut nothing`() {
        // image-only question, reply, "A", reply. The user edits "A".
        val rows = listOf(
            row("u1", "user", imageOnly), row("a1", "assistant", text("pic")),
            row("u2", "user", text("A")), row("a2", "assistant", text("ans")),
        )
        val messages = listOf(user("u1", ""), assistant("a1"), user("u2", "A"), assistant("a2"))

        // Old rule: the image-only bubble is counted on screen but skipped in
        // the DB, so the match runs off the end and nothing is cut - the old
        // "A" stays in the model's history behind the edited text.
        assertEquals(-1, oldEditCutoff(messages, 2, rows))

        val located = BubbleRowLocator.locateUserRow(messages, 2, rows)
        assertEquals(BubbleRowLocator.Via.LINKED, located.via)
        assertEquals("u2", located.row?.id)
        assertEquals(2, BubbleRowLocator.cutoffFor(located, keepBubble = false))
    }

    @Test
    fun `retry keeps the bubble's row and delete removes it`() {
        val rows = listOf(row("u1", "user", text("q")), row("a1", "assistant", text("r")))
        val located = BubbleRowLocator.locateUserRow(listOf(user("u1"), assistant("a1")), 0, rows)
        assertEquals(1, BubbleRowLocator.cutoffFor(located, keepBubble = true))
        assertEquals(0, BubbleRowLocator.cutoffFor(located, keepBubble = false))
    }

    @Test
    fun `hidden synthetic rows do not shift a linked cut`() {
        // A stop-continue <system-reminder> row and a tool-result row sit
        // between the turns; neither has a bubble.
        val rows = listOf(
            row("u1", "user", text("first")), row("a1", "assistant", text("r1")),
            row("sr", "user", text("<system-reminder>continue</system-reminder>")),
            row("tr", "user", toolResult),
            row("u2", "user", text("second")), row("a2", "assistant", text("r2")),
        )
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        val located = BubbleRowLocator.locateUserRow(messages, 2, rows)
        assertEquals("u2", located.row?.id)
        assertEquals(4, BubbleRowLocator.cutoffFor(located, keepBubble = false))
    }

    @Test
    fun `a merged loaded bubble is found through sourceDbIds`() {
        val rows = listOf(row("u1", "user", text("q")))
        val bubble = ChatMessage(id = "other", role = "user", content = "q", sourceDbIds = listOf("u1"))
        val located = BubbleRowLocator.locateUserRow(listOf(bubble), 0, rows)
        assertEquals(BubbleRowLocator.Via.LINKED, located.via)
        assertEquals("u1", located.row?.id)
    }

    @Test
    fun `an assistant row with the bubble's id is never taken for the user row`() {
        val rows = listOf(row("same", "assistant", text("r")), row("u", "user", text("q")))
        val located = BubbleRowLocator.locateUserRow(listOf(user("same")), 0, rows)
        assertEquals("u", located.row?.id)
        assertEquals(BubbleRowLocator.Via.ORDINAL, located.via)
    }

    // ── unlinked fallback ─────────────────────────────────────────────────

    @Test
    fun `an unpersisted queued bubble falls back to counting rendered rows, image-only included`() {
        val rows = listOf(
            row("u1", "user", imageOnly), row("a1", "assistant", text("r1")),
            row("u2", "user", text("B")), row("a2", "assistant", text("r2")),
        )
        val messages = listOf(user("u1", ""), assistant("a1"), user("queued_msg_1", "B"), assistant("a2"))
        val located = BubbleRowLocator.locateUserRow(messages, 2, rows)
        assertEquals(BubbleRowLocator.Via.ORDINAL, located.via)
        assertEquals("u2", located.row?.id)
    }

    @Test
    fun `nothing to anchor yields no cut`() {
        val located = BubbleRowLocator.locateUserRow(listOf(user("queued_msg_1")), 0, emptyList())
        assertEquals(BubbleRowLocator.Via.NONE, located.via)
        assertNull(located.row)
        assertEquals(-1, BubbleRowLocator.cutoffFor(located, keepBubble = true))
    }

    @Test
    fun `visible rows match what the chat renders`() {
        assertTrue(BubbleRowLocator.isVisibleUserRow(text("hello")))
        assertTrue(BubbleRowLocator.isVisibleUserRow(imageOnly))
        assertFalse(BubbleRowLocator.isVisibleUserRow(toolResult))
        assertFalse(BubbleRowLocator.isVisibleUserRow(text("<system-reminder>x</system-reminder>")))
        assertFalse(BubbleRowLocator.isVisibleUserRow(text("<user-attached-files>f</user-attached-files>")))
    }

    // ── wiring ────────────────────────────────────────────────────────────

    @Test
    fun `retry, delete and edit all anchor through the locator, with no counting loop left`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("logBubbleAnchor(\"retry\", messageId, located)"))
        assertTrue(vm.contains("logBubbleAnchor(\"edit\", messageId, located)"))
        assertTrue(vm.contains("logBubbleAnchor(\"delete\", messages[anchorIdx].id, located)"))
        assertFalse("the per-operation ordinal loops are gone", vm.contains("visibleUserCount"))
    }

    @Test
    fun `a steer bubble takes its persisted row's id`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("id = steerRow?.id ?: \"steer_\${System.currentTimeMillis()}\""))
    }
}
