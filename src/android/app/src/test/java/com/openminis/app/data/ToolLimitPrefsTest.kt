package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLimitPrefsTest {

    @Test
    fun clampsStayInRange() {
        assertEquals(ToolLimitPrefs.MIN_SHELL_TIMEOUT_SEC, ToolLimitPrefs.clampShell(1))
        assertEquals(ToolLimitPrefs.MAX_SHELL_TIMEOUT_SEC, ToolLimitPrefs.clampShell(99_000))
        assertEquals(600, ToolLimitPrefs.clampShell(600))
        assertEquals(0, ToolLimitPrefs.clampLines(0))
        assertEquals(0, ToolLimitPrefs.clampLines(-4))
        assertEquals(100, ToolLimitPrefs.clampLines(50))
        assertEquals(ToolLimitPrefs.MAX_FILE_READ_MAX_LINES, ToolLimitPrefs.clampLines(99_999))
        assertEquals(ToolLimitPrefs.MIN_SUBAGENT_MAX_TURNS, ToolLimitPrefs.clampTurns(1))
        assertEquals(ToolLimitPrefs.MAX_SUBAGENT_MAX_TURNS, ToolLimitPrefs.clampTurns(9_000))
    }

    /**
     * [T-main-session-turn-cap] The main agent loop's runaway guard is a
     * user setting now, not a hardcoded 200. Unprimed reads (unit tests,
     * early app startup) must fall back to the shipped default so behavior
     * is unchanged until the user touches the slider.
     */
    @Test
    fun mainSessionTurnCapClampsAndDefaults() {
        assertEquals(200, ToolLimitPrefs.DEFAULT_MAIN_MAX_TURNS)
        assertEquals(ToolLimitPrefs.MIN_MAIN_MAX_TURNS, ToolLimitPrefs.clampMainTurns(1))
        assertEquals(ToolLimitPrefs.MAX_MAIN_MAX_TURNS, ToolLimitPrefs.clampMainTurns(9_000))
        assertEquals(200, ToolLimitPrefs.clampMainTurns(200))
        // Unprimed prefs: the getter must return the default, not crash.
        assertEquals(ToolLimitPrefs.DEFAULT_MAIN_MAX_TURNS, ToolLimitPrefs.mainSessionMaxTurns())
        // The loop's legacy const must stay pinned to the same default so
        // log lines and the const itself never drift from the setting.
        assertEquals(
            ToolLimitPrefs.DEFAULT_MAIN_MAX_TURNS,
            com.openminis.app.ui.chat.ChatViewModel.MAX_AGENT_TURNS,
        )
    }

    @Test
    fun unprimedShellTimeoutUsesDefaultCap() {
        assertEquals(ToolLimitPrefs.DEFAULT_SHELL_TIMEOUT_SEC, ToolLimitPrefs.resolveShellTimeoutSec(null))
        assertEquals(30, ToolLimitPrefs.resolveShellTimeoutSec(30))
        assertEquals(ToolLimitPrefs.DEFAULT_SHELL_TIMEOUT_SEC, ToolLimitPrefs.resolveShellTimeoutSec(9_000))
    }
}
