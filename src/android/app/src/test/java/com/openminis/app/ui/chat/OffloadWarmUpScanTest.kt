package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-offload-warmup-scan] Port of iOS OffloadWarmUpScanTests
 * (155501fcd): the warm-up indices the offload may scan are exactly the
 * pre-anchor messages effectiveAgentHistoryUncounted sends.
 */
class OffloadWarmUpScanTest {

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)
    private fun call(id: String) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "",
        contentParts = listOf(AgentContentPart.ToolUse(id = id, name = "file_write", input = JSONObject().put("content", "x".repeat(800)))))
    private fun result(id: String, len: Int) = LLMMessage(role = LLMMessage.Role.USER, content = "",
        contentParts = listOf(AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "y".repeat(len))))
    private fun reply(text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text)

    private val history = listOf(
        reply("old tail"),          // 0 leading non-user -> peeled
        user("turn A"),             // 1
        call("big"),                // 2 call of a >1000 result -> emptied, dropped
        result("big", 5000),        // 3 >1000 -> pruned
        call("mid"),                // 4 file_write argument -> kept (offloadable)
        result("mid", 700),         // 5 501-1000 chars -> kept (offloadable)
        reply("done A"),            // 6 text-only -> kept
        user("turn B (anchor)"),    // 7 anchor
    )

    @Test fun `sent warm-up messages are kept and pruned ones are not`() {
        val (idx, pruned) = ChatViewModel.warmUpScanIndices(history, priorIdx = 0, anchorIdx = 7, drop = null)
        assertEquals(listOf(1, 4, 5, 6, 7), idx)
        assertEquals(setOf("big"), pruned)
    }

    @Test fun `a decided per-marker trim drops the oldest kept messages`() {
        val (idx, _) = ChatViewModel.warmUpScanIndices(history, 0, 7, drop = 2)
        assertEquals(listOf(5, 6, 7), idx)
        val (all, _) = ChatViewModel.warmUpScanIndices(history, 0, 7, drop = 99)
        assertTrue(all.isEmpty())
    }

    @Test fun `no warm-up when the walk-back found nothing`() {
        assertTrue(ChatViewModel.warmUpScanIndices(history, priorIdx = 8, anchorIdx = 7, drop = null).first.isEmpty())
    }
}
