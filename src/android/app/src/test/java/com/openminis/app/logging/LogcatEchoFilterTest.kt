package com.openminis.app.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-log-stdout-double-write] Lines the app already wrote itself must
 * not be written a second time when they come back through the logcat tail.
 *
 * `AppLogger.startCapture()` does two things at once: it redirects
 * `System.out`/`System.err` into the log file, and it spawns a
 * `logcat --pid=self` tail that also writes into the same file. The platform
 * copies stdout to logcat, so every `println` arrives twice — once as
 * `[STDOUT] …`, once as `[LOGCAT] … I/System.out(pid): …`. Confirmed on the
 * 2026-09-15 field log: 8 `T-HANG-DIAG` lines for 4 real events.
 *
 * `writeLogcatLine` already filtered `Minis.*` and `AppLogger` for exactly this
 * reason; `System.out` / `System.err` were the gap.
 *
 * The real method is private and touches file state, so this mirrors its tag
 * extraction and predicate. That keeps the test honest about its scope — it
 * covers the DECISION (which tags are echoes of our own writes), not the I/O.
 */
class LogcatEchoFilterTest {

    /** Mirror of writeLogcatLine's tag parse + skip rule. */
    private fun isEcho(rawLine: String): Boolean {
        val slashIdx = rawLine.indexOf('/')
        val parenIdx = if (slashIdx >= 0) rawLine.indexOf('(', slashIdx) else -1
        if (slashIdx < 0 || parenIdx <= slashIdx) return false
        val tag = rawLine.substring(slashIdx + 1, parenIdx).trim()
        return tag.startsWith("Minis.") || tag == "AppLogger" ||
            tag == "System.out" || tag == "System.err"
    }

    @Test
    fun `stdout and stderr echoes are dropped`() {
        assertTrue(isEcho("09-15 13:59:56.292 I/System.out(26955): [T-HANG-DIAG] tick=37230"))
        assertTrue(isEcho("09-15 13:59:56.292 W/System.err(26955): stack trace line"))
    }

    @Test
    fun `our own AppLogger tags stay filtered`() {
        assertTrue(isEcho("09-15 13:59:40.304 I/Minis.AppLogger(26955): session started"))
        assertTrue(isEcho("09-15 13:59:40.304 I/Minis.ChatVMStream(26955): send"))
        assertTrue(isEcho("09-15 13:59:40.304 I/AppLogger(26955): x"))
    }

    @Test
    fun `framework and other-app lines are still captured`() {
        // The whole point of the tail is these; over-filtering would silently
        // lose the GC and ANR evidence this log gets pulled for.
        assertFalse(isEcho("09-15 13:59:43.310 I/m.openminis.app(26955): NativeAlloc concurrent mark compact GC freed 204MB"))
        assertFalse(isEcho("09-15 14:00:07.563 I/HangDetector(26955): hang count reset after quiet period"))
        assertFalse(isEcho("09-15 14:00:01.000 I/Choreographer(26955): Davey! duration=1526ms"))
    }

    @Test
    fun `a tag that merely starts with System is not treated as an echo`() {
        // `System.out` is matched exactly, not by prefix — SystemUI/SystemClock
        // must keep flowing.
        assertFalse(isEcho("09-15 14:00:01.000 I/SystemUI(26955): something"))
        assertFalse(isEcho("09-15 14:00:01.000 I/SystemClock(26955): something"))
    }

    @Test
    fun `a malformed line is kept rather than silently dropped`() {
        assertFalse(isEcho("no tag here at all"))
        assertFalse(isEcho(""))
    }
}
