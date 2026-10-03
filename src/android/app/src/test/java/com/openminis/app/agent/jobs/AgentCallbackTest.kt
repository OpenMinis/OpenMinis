package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-p2-agent-callback] The envelope must round-trip and match the iOS shape byte for byte. */
class AgentCallbackTest {
    @Test
    fun `finished envelope serialises in the iOS shape and parses back`() {
        val cb = AgentCallback(
            kind = AgentCallback.Kind.FINISHED, jobId = "ab12cd34", childSessionId = "S1", title = "Survey \"repo\" <tests>",
            status = "done", tier = "primary", elapsed = "1m02s", summary = "tools browser_use×2 · turns 4 · tokens in 12k / out 2k",
            body = "# Report\nline & more",
        )
        val xml = cb.xml
        assertTrue(xml.startsWith("<agent_callback kind=\"finished\" job=\"ab12cd34\" session=\"S1\" title=\"Survey &quot;repo&quot; &lt;tests&gt;\" status=\"done\" model=\"primary\" elapsed=\"1m02s\">"))
        assertTrue(xml.contains("<summary>tools browser_use×2 · turns 4 · tokens in 12k / out 2k</summary>"))
        assertTrue(xml.contains("<result>\n# Report\nline & more\n</result>\n</agent_callback>"))
        assertTrue(AgentCallback.isCallbackText(xml))
        val back = AgentCallback.parse(xml)!!
        assertEquals(cb.copy(), back)
        assertEquals(62, back.elapsedSeconds)
    }

    @Test
    fun `progress envelope uses last_message and carries tool attributes`() {
        val cb = AgentCallback(AgentCallback.Kind.PROGRESS, "J", "S", "t", "running", "sub", "45s", tool = "shell_execute", activity = "ls -la", turn = 3, summary = null, body = "so far")
        val xml = cb.xml
        assertTrue(xml.contains("tool=\"shell_execute\" activity=\"ls -la\" turn=\"3\">"))
        assertTrue(xml.contains("<last_message>\nso far\n</last_message>"))
        val back = AgentCallback.parse(xml)!!
        assertEquals("shell_execute", back.tool); assertEquals(3, back.turn); assertEquals("so far", back.body); assertEquals(45, back.elapsedSeconds)
    }

    @Test
    fun `plain text and malformed envelopes are not callbacks`() {
        assertFalse(AgentCallback.isCallbackText("hello <agent_callback"))
        assertNull(AgentCallback.parse("<agent_callback kind=\"nope\" job=\"x\">body</agent_callback>"))
        assertNull(AgentCallback.parse("<agent_callback>no attrs</agent_callback>"))
    }

    @Test
    fun `preview line reads Agent result · title · status`() {
        val cb = AgentCallback.parse(AgentCallback(AgentCallback.Kind.FINISHED, "J", null, "Deep research", "timeout", body = "x").xml)!!
        assertEquals("Agent result · Deep research · timeout", AgentCallbackLabels.previewLine(cb))
    }
}
