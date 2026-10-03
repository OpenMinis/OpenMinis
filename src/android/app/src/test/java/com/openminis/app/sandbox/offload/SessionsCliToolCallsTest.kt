package com.openminis.app.sandbox.offload

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.OffloadToolResult
import com.openminis.app.data.repository.OffloadToolUse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-sessions-cli-tool-calls] `minis-sessions-cli messages --tools`
 * (iOS 7af22b59f): tool calls come back with name, full input and output,
 * including the ones on messages that also carry text.
 */
class SessionsCliToolCallsTest {

    private val command = """{"command":"sleep 12; echo STEP2","tool_title":"Wait then print"}"""

    /** Text + a tool call in one message — the shape the text extractor drops calls from. */
    private val assistantParts = JSONArray()
        .put(JSONObject().put("type", "text").put("value", "I'll check it."))
        .put(JSONObject().put("type", "toolUse").put("value", JSONObject()
            .put("toolUseId", "call_1").put("name", "shell_execute").put("input", command)))
        .toString()

    private val resultParts = JSONArray()
        .put(JSONObject().put("type", "toolResult").put("value", JSONObject()
            .put("toolUseId", "call_1").put("output", "STEP2\n").put("success", true)))
        .toString()

    @Test fun `tool calls on a message with text are decoded in full`() {
        val (uses, results) = ChatRepository.decodeToolParts(assistantParts)
        assertEquals(listOf(OffloadToolUse("call_1", "shell_execute", command)), uses)
        assertTrue(results.isEmpty())
    }

    @Test fun `tool results keep their full output and success`() {
        val (uses, results) = ChatRepository.decodeToolParts(resultParts)
        assertTrue(uses.isEmpty())
        assertEquals(listOf(OffloadToolResult("call_1", true, "STEP2\n")), results)
    }

    @Test fun `malformed parts yield nothing instead of throwing`() {
        val (uses, results) = ChatRepository.decodeToolParts("not json")
        assertTrue(uses.isEmpty() && results.isEmpty())
    }

    @Test fun `input and output are capped like text and flagged when cut`() {
        val long = "x".repeat(700)
        val calls = SessionsOffloadHandler.toolCallsJson(listOf(OffloadToolUse("a", "shell_execute", long)), 600)
        val call = calls.getJSONObject(0)
        assertEquals(600, call.getString("input").length)
        assertTrue(call.getBoolean("input_truncated"))

        val res = SessionsOffloadHandler.toolResultsJson(listOf(OffloadToolResult("a", false, long)), 50_000)
            .getJSONObject(0)
        assertEquals(700, res.getString("output").length)
        assertFalse(res.has("output_truncated"))
        assertFalse(res.getBoolean("success"))
        assertEquals("a", res.getString("tool_use_id"))
    }

    @Test fun `input stays a string so the field has one type`() {
        val call = SessionsOffloadHandler.toolCallsJson(listOf(OffloadToolUse("a", "n", command)), 600).getJSONObject(0)
        assertTrue(call.get("input") is String)
        assertEquals(command, call.getString("input"))
        assertFalse(call.has("input_truncated"))
    }
}
