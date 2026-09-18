package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStatusNotificationTest {

    @Test
    fun `wall clock when is now minus elapsed`() {
        assertEquals(1_000L, AgentForegroundService.wallClockWhenMs(1_500L, 500L))
        assertEquals(1_500L, AgentForegroundService.wallClockWhenMs(1_500L, -10L))
    }

    @Test
    fun `ticker joins title and live status`() {
        assertEquals("Minis is using Shell", AgentForegroundService.tickerLine("Minis is using Shell", ""))
        assertEquals(
            "Minis is using Shell · ls -la",
            AgentForegroundService.tickerLine("Minis is using Shell", "ls -la"),
        )
        assertEquals("idle", AgentForegroundService.tickerLine("idle", "idle"))
    }

    @Test
    fun `ticker prefers overlay reply excerpt over tool status`() {
        assertEquals(
            "Hello from Minis",
            AgentForegroundService.glanceLine("Idle", "Hello from Minis"),
        )
        assertEquals(
            "Task completed · Hello from Minis",
            AgentForegroundService.tickerLine("Task completed", "Idle", "Hello from Minis"),
        )
        assertEquals(
            "Task completed · Idle",
            AgentForegroundService.tickerLine("Task completed", "Idle", null),
        )
    }
}
