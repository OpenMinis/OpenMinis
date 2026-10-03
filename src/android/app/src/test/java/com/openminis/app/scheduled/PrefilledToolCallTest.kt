package com.openminis.app.scheduled

import com.openminis.app.data.model.LLMStreamChunk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-tool-prefill] The data side of a scheduled task's prefilled
 * tool call: storage, validation, the envelope attribute, and the scripted
 * chunk stream the agent loop consumes in place of a model request.
 */
class PrefilledToolCallTest {

    private fun task(prefill: PrefilledToolCall?) = ScheduledTask(
        id = "T1",
        label = "Weather",
        timeOfDayHour = 8,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "Summarise the weather",
        prefillToolCall = prefill,
        createdAt = 1L,
    )

    // ── storage ──

    @Test
    fun `a prefilled task round-trips through its JSON`() {
        val call = PrefilledToolCall.shell("curl -s wttr.in", title = "Weather")
        val back = ScheduledTask.fromJson(task(call).toJson())
        assertEquals(call, back.prefillToolCall)
        assertEquals("curl -s wttr.in", back.prefillToolCall!!.shellCommand)
    }

    @Test
    fun `a task without prefill writes no key, so its JSON is unchanged`() {
        // Back-compat both ways: an older build reads exactly what it always
        // read, and a task written before this field existed stays null.
        val json = task(null).toJson()
        assertFalse(json.has("prefillToolCall"))
        assertNull(ScheduledTask.fromJson(json).prefillToolCall)
    }

    @Test
    fun `a task stored by an older build deserializes with no prefill`() {
        val legacy = JSONObject(
            """{"id":"old","label":"L","hour":9,"minute":30,"repeatMode":"DAILY",""" +
                """"customDays":"","prompt":"p","targetMode":"NEW_SESSION","enabled":true,"createdAt":5}""",
        )
        val t = ScheduledTask.fromJson(legacy)
        assertNull(t.prefillToolCall)
        assertEquals("p", t.prompt)
    }

    @Test
    fun `a malformed stored prefill is dropped, not crashed on`() {
        val json = task(null).toJson().put("prefillToolCall", JSONObject().put("args", JSONObject()))
        assertNull(ScheduledTask.fromJson(json).prefillToolCall)
    }

    // ── construction + validation ──

    @Test
    fun `tool_title is filled from the label when the caller gave none`() {
        val call = PrefilledToolCall.shell("df -h", title = "Disk check")
        assertEquals("Disk check", call.args().getString("tool_title"))
        assertEquals("df -h", call.args().getString("command"))
    }

    @Test
    fun `without a label the title falls back to the command's first line`() {
        val call = PrefilledToolCall.shell("uptime\necho done")
        assertEquals("uptime", call.args().getString("tool_title"))
    }

    @Test
    fun `an explicit tool_title is kept`() {
        val call = PrefilledToolCall.of(
            "shell",
            JSONObject().put("command", "ls").put("tool_title", "List"),
            title = "ignored",
        )
        assertEquals("shell_execute", call.toolName)
        assertEquals("List", call.args().getString("tool_title"))
    }

    @Test
    fun `only supported tools with valid arguments pass validation`() {
        assertNull(PrefilledToolCall.shell("ls").validationError())
        assertNotNull(PrefilledToolCall("browser_use", "{}").validationError())
        assertNotNull(PrefilledToolCall.of("shell_execute", JSONObject()).validationError())
        assertNotNull(PrefilledToolCall("shell_execute", "not json").validationError())
        assertTrue(
            PrefilledToolCall("file_read", "{}").validationError()!!.contains("not supported yet"),
        )
        // iOS limits: 1s..1h timeout, 16 000-char command.
        assertNull(PrefilledToolCall.shell("ls", timeoutSec = 3600).validationError())
        assertNotNull(PrefilledToolCall.shell("ls", timeoutSec = 0).validationError())
        assertNotNull(PrefilledToolCall.shell("ls", timeoutSec = 3601).validationError())
        assertNotNull(PrefilledToolCall.shell("x".repeat(16_001)).validationError())
    }

    // ── envelope ──

    @Test
    fun `the envelope names the prefilled tool and parses it back`() {
        val marker = ScheduledTaskMarker("T1", "Weather", 100L, "Summarise", prefilledTool = "shell_execute")
        assertTrue(marker.xml.contains(" prefilled=\"shell_execute\""))
        assertEquals(marker, ScheduledTaskMarker.parse(marker.xml))
    }

    @Test
    fun `a prefilled envelope opens its body with the iOS note, verbatim`() {
        // Word-for-word the iOS ScheduledPresetToolCall.envelopeNote, so both
        // platforms tell the model the same thing: the call already ran, work
        // from its output, re-run only on failure.
        val iosNote = "[This task's first step already ran for you: shell_execute — its call and output follow. " +
            "Work from that output; re-run only if it failed.]"
        assertEquals(iosNote, ScheduledTaskMarker.prefillNote("shell_execute"))
        val marker = ScheduledTaskMarker("T1", "Weather", 100L, "Summarise", prefilledTool = "shell_execute")
        assertEquals(
            "<scheduled_task id=\"T1\" label=\"Weather\" next=\"100\" prefilled=\"shell_execute\">\n" +
                iosNote + "\nSummarise\n</scheduled_task>",
            marker.xml,
        )
        // The card shows the prompt alone: parse strips the note back off.
        assertEquals("Summarise", ScheduledTaskMarker.parse(marker.xml)!!.prompt)
        assertEquals("Summarise", ScheduledTaskMarker.stripMarker(marker.xml))
    }

    @Test
    fun `a note-like line is only stripped from a prefilled envelope`() {
        val note = ScheduledTaskMarker.prefillNote("shell_execute")
        val typed = "<scheduled_task id=\"T1\" label=\"L\">\n$note\nhello\n</scheduled_task>"
        assertEquals("$note\nhello", ScheduledTaskMarker.parse(typed)!!.prompt)
    }

    @Test
    fun `an ordinary task's envelope is byte-identical to before`() {
        val marker = ScheduledTaskMarker("T1", "Weather", 100L, "Summarise")
        assertEquals("<scheduled_task id=\"T1\" label=\"Weather\" next=\"100\">\nSummarise\n</scheduled_task>", marker.xml)
        assertNull(ScheduledTaskMarker.parse(marker.xml)!!.prefilledTool)
    }

    // ── scripted turn ──

    @Test
    fun `the scripted turn streams exactly what a tool-only model turn would`() {
        val turn = ScriptedToolTurn.from(listOf(PrefilledToolCall.shell("ls", "L")), setOf("shell_execute"))!!
        val chunks = turn.asStreamChunks()
        val id = turn.calls.single().id
        assertEquals(4, chunks.size)
        assertEquals(LLMStreamChunk.Started, chunks[0])
        assertEquals(LLMStreamChunk.ToolUseStart(id, "shell_execute"), chunks[1])
        val complete = chunks[2] as LLMStreamChunk.ToolCallComplete
        assertEquals(id, complete.id)
        assertEquals("shell_execute", complete.name)
        assertEquals("ls", complete.args.getString("command"))
        assertNull(complete.thoughtSignature)
        assertEquals(LLMStreamChunk.Finished("tool_use"), chunks[3])
        // No text, thinking or usage: nothing was asked of a model.
        assertTrue(chunks.none { it is LLMStreamChunk.Text || it is LLMStreamChunk.Usage || it is LLMStreamChunk.ThinkingDelta })
    }

    @Test
    fun `a tool the session does not offer falls back to the model deciding`() {
        assertNull(ScriptedToolTurn.from(listOf(PrefilledToolCall.shell("ls")), setOf("file_read")))
        assertNull(ScriptedToolTurn.from(emptyList(), setOf("shell_execute")))
    }

    @Test
    fun `invalid calls are dropped from the scripted turn`() {
        val turn = ScriptedToolTurn.from(
            listOf(PrefilledToolCall.shell("ls"), PrefilledToolCall("shell_execute", "{}")),
            setOf("shell_execute"),
        )!!
        assertEquals(1, turn.calls.size)
    }

    @Test
    fun `scripted ids satisfy every provider's id rules and are recognisable`() {
        repeat(50) {
            val id = PrefilledToolCall.newToolUseId()
            // Anthropic: ^[a-zA-Z0-9_-]+$ ; OpenAI-compatible: <= 64 chars.
            assertTrue(id, Regex("^[a-zA-Z0-9_-]+$").matches(id))
            assertTrue(id.length <= 64)
            assertTrue(ScriptedToolTurn.isScriptedId(id))
        }
        assertFalse(ScriptedToolTurn.isScriptedId("toolu_01ABC"))
        assertFalse(ScriptedToolTurn.isScriptedId("call_abc123"))
    }

    @Test
    fun `each firing gets fresh ids`() {
        val prefill = listOf(PrefilledToolCall.shell("ls"))
        val a = ScriptedToolTurn.from(prefill, setOf("shell_execute"))!!.calls.single().id
        val b = ScriptedToolTurn.from(prefill, setOf("shell_execute"))!!.calls.single().id
        assertTrue(a != b)
    }
}
