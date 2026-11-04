package com.openminis.app.inbound

import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledRun
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [inbound-task-api] The row handed to ScheduledTaskManager does not also arm an alarm. */
class ScheduledInboundTaskStarterTest {

    @Test
    fun `a new chat is a consumed one-shot with no alarm`() {
        val task = ScheduledInboundTaskStarter.buildTask(
            prompt = "do the thing",
            session = null,
            label = null,
            defaultLabel = "Inbound",
            now = 1_000L,
        )
        assertEquals("do the thing", task.prompt)
        assertEquals("Inbound", task.label)
        assertTrue(task.targetMode is ScheduledTargetMode.NewSession)
        assertFalse(task.enabled)
        assertEquals(ScheduledTriggerKind.AFTER, task.triggerKind)
        assertEquals(ScheduledRepeatMode.ONCE, task.repeatMode)
        assertEquals(0L, task.delaySec)
        assertEquals(1, task.triggeredCount)
        assertEquals(1_000L, task.anchorMs)
        assertNull(task.creatorSessionId)
        assertNull(task.nextTriggerMs(5_000L))
        // Even if something flipped enabled without the editor's reset, the
        // consumed count leaves no pending slot for AlarmManager.
        assertNull(task.copy(enabled = true).nextTriggerMs(5_000L))
        assertEquals("new", ScheduledInboundTaskStarter.targetOf(task.targetMode))
    }

    @Test
    fun `a session is a follow-up into that chat`() {
        val task = ScheduledInboundTaskStarter.buildTask(
            prompt = "continue",
            session = "abc-def",
            label = "  note  ",
            defaultLabel = "Inbound",
            now = 2L,
        )
        val mode = task.targetMode as ScheduledTargetMode.AppendToSession
        assertEquals("abc-def", mode.sessionId)
        assertEquals("abc-def", task.creatorSessionId)
        assertEquals("note", task.label)
        assertEquals("follow-up", ScheduledInboundTaskStarter.targetOf(task.targetMode))
    }

    @Test
    fun `status names the chat without repeating the prompt`() {
        val task = ScheduledInboundTaskStarter.buildTask("secret prompt", null, "label", "Inbound", 1L)
        val running = ScheduledInboundTaskStarter.describe(
            task.copy(lastResultSessionId = "sess"),
            running = true,
        )
        assertEquals("running", running.status)
        assertEquals("sess", running.sessionId)
        assertNull(running.ok)
        assertEquals("label", running.label)

        val done = ScheduledInboundTaskStarter.describe(
            task.copy(
                lastFiredAt = 9L,
                lastResultSessionId = "sess",
                runHistory = listOf(ScheduledRun(firedAt = 9L, sessionId = "sess", preview = "secret prompt echoed", ok = true)),
            ),
            running = false,
        )
        assertEquals("completed", done.status)
        assertEquals(true, done.ok)
        val failed = ScheduledInboundTaskStarter.describe(
            task.copy(
                lastFiredAt = 9L,
                runHistory = listOf(ScheduledRun(9L, "sess", null, ok = false)),
            ),
            running = false,
        )
        assertEquals("failed", failed.status)
        assertEquals(false, failed.ok)
        val waiting = ScheduledInboundTaskStarter.describe(task, running = false)
        assertEquals("accepted", waiting.status)
        assertNull(waiting.sessionId)
    }
}
