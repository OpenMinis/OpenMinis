package com.openminis.app.service

import com.openminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-live-update-content] Pins the phase → icon/title/text/timer
 * mapping behind the ongoing agent notification and the Android 16 Live
 * Update chip. Pure functions, no Android runtime needed.
 */
class AgentNotificationContentTest {

    @Test
    fun `completed wins over everything`() {
        assertEquals(
            AgentPhase.COMPLETED,
            resolveAgentPhase(isCompleted = true, toolName = "shell_execute", isThinking = true, hasActiveSessions = true),
        )
    }

    @Test
    fun `a running tool wins over thinking`() {
        assertEquals(
            AgentPhase.TOOL,
            resolveAgentPhase(isCompleted = false, toolName = "browser_use", isThinking = true, hasActiveSessions = true),
        )
    }

    @Test
    fun `thinking and generating are distinguished while a stream is active`() {
        assertEquals(
            AgentPhase.THINKING,
            resolveAgentPhase(isCompleted = false, toolName = null, isThinking = true, hasActiveSessions = true),
        )
        assertEquals(
            AgentPhase.GENERATING,
            resolveAgentPhase(isCompleted = false, toolName = null, isThinking = false, hasActiveSessions = true),
        )
    }

    @Test
    fun `no active session and not completed is idle even if a stale thinking flag remains`() {
        assertEquals(
            AgentPhase.IDLE,
            resolveAgentPhase(isCompleted = false, toolName = null, isThinking = true, hasActiveSessions = false),
        )
    }

    @Test
    fun `small icons are the app's monochrome tool glyphs, never legacy menu bitmaps`() {
        assertEquals(R.drawable.ic_tool_terminal, notificationSmallIconFor(AgentPhase.TOOL, "shell_execute"))
        assertEquals(R.drawable.ic_tool_globe, notificationSmallIconFor(AgentPhase.TOOL, "browser_use"))
        assertEquals(R.drawable.ic_tool_build, notificationSmallIconFor(AgentPhase.TOOL, "some_new_tool"))
        assertEquals(R.drawable.ic_tool_psychology, notificationSmallIconFor(AgentPhase.THINKING, null))
        assertEquals(R.drawable.ic_launcher_monochrome, notificationSmallIconFor(AgentPhase.GENERATING, null))
        assertEquals(R.drawable.ic_notification_completed, notificationSmallIconFor(AgentPhase.COMPLETED, "shell_execute"))
    }

    @Test
    fun `tool row title prefers the model-supplied tool_title`() {
        assertEquals("Open Baidu home page", toolRowTitle("browser_use", "Open Baidu home page"))
        assertEquals("Browse Web", toolRowTitle("browser_use", "   "))
        assertEquals("Execute Shell", toolRowTitle("shell_execute", null))
    }

    @Test
    fun `default Running status is humanized, custom statuses pass through`() {
        assertEquals("Execute Shell", humanizeToolStatus("Running: shell_execute", "shell_execute"))
        assertEquals("Tapping Login button", humanizeToolStatus("Tapping Login button", "accessibility"))
        assertEquals("Running: shell_execute", humanizeToolStatus("Running: shell_execute", null))
    }

    @Test
    fun `chip timer stays within seven characters and rolls over to hours`() {
        assertEquals("0:00", chipTimerText(0L))
        assertEquals("0:00", chipTimerText(-5_000L))
        assertEquals("0:21", chipTimerText(21_400L))
        assertEquals("12:05", chipTimerText((12 * 60 + 5) * 1000L))
        assertEquals("1:02:03", chipTimerText(((1 * 3600) + (2 * 60) + 3) * 1000L))
        assertEquals(7, chipTimerText(((9 * 3600) + (59 * 60) + 59) * 1000L).length)
    }
}
