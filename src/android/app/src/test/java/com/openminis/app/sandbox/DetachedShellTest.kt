package com.openminis.app.sandbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DetachedShellTest {

    @Test
    fun wrapDoesNotUseNohupAndUnbuffersPython() {
        val wrapped = DetachedShell.wrapDetachedCommand("python3 bot.py")
        assertTrue(wrapped.contains("PYTHONUNBUFFERED=1"))
        assertTrue(wrapped.contains("python3 bot.py"))
        assertFalse(wrapped.contains("nohup"))
        assertFalse(wrapped.trim().endsWith("&"))
    }

    @Test
    fun jsonFlagAcceptsBoolNumberAndString() {
        assertTrue(DetachedShell.jsonFlag(JSONObject("""{"detach":true}"""), "detach"))
        assertTrue(DetachedShell.jsonFlag(JSONObject("""{"detach":"true"}"""), "detach"))
        assertTrue(DetachedShell.jsonFlag(JSONObject("""{"detach":1}"""), "detach"))
        assertFalse(DetachedShell.jsonFlag(JSONObject("""{"detach":false}"""), "detach"))
        assertFalse(DetachedShell.jsonFlag(JSONObject("{}"), "detach"))
    }

    @Test
    fun readLogHonorsByteOffset() {
        val f = File.createTempFile("minis-detach", ".log")
        try {
            f.writeText("hello\nworld\n")
            val all = DetachedShell.readLogFile(f, 0)
            assertTrue(all.text.startsWith("hello"))
            val rest = DetachedShell.readLogFile(f, 6)
            assertEquals("world\n", rest.text)
            assertEquals(6L, rest.offset)
            assertEquals(f.length(), rest.nextOffset)
            assertFalse(rest.truncated)
        } finally {
            f.delete()
        }
    }

    @Test
    fun formatSpawnTellsModelToUseTaskOutput() {
        val snap = DetachedShell.Snapshot(
            id = "abcd1234",
            sessionId = "s1",
            command = "python3 bot.py",
            linuxLogPath = "/var/minis/offloads/tasks/abcd1234.log",
            hostLog = File("/tmp/x"),
            startedAtMs = 0L,
            pid = 42,
            exitCode = null,
        )
        val msg = DetachedShell.formatSpawn(snap)
        assertTrue(msg.contains("task_id=abcd1234"))
        assertTrue(msg.contains("task_output"))
        assertTrue(msg.contains("nohup"))
        assertTrue(snap.running)
    }

    @Test
    fun emptyListMessage() {
        assertEquals("No detached tasks for this session.", DetachedShell.formatList(emptyList()))
    }
}
