package com.openminis.app.crash

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenMinis#363] The recovery path for a process death nothing in-process
 * survived to describe.
 *
 * Two seams, because they need different tools:
 *
 *  • The pure decisions — which exit reasons are worth a file, how they are
 *    named — are real unit tests.
 *  • Everything else is a call into ActivityManager / ApplicationExitInfo /
 *    SharedPreferences. This module has no Robolectric and runs with
 *    `unitTests.isReturnDefaultValues = true`, so those classes are
 *    zero-returning stubs here: a "test" driving them would assert that a stub
 *    did nothing. Those parts are pinned as SOURCE assertions instead, which is
 *    honest about what is being checked — that a future refactor cannot
 *    silently drop the high-water mark, the native-crash filter, or the
 *    tombstone read, each of which fails invisibly (the log file simply stops
 *    carrying the field) rather than breaking a build.
 */
class ExitInfoCollectorTest {

    // ── Pure logic ───────────────────────────────────────────────────────

    @Test
    fun `native crashes are collected — the reason this exists`() {
        // ApplicationExitInfo.REASON_CRASH_NATIVE == 5.
        assertTrue(ExitInfoCollector.isInteresting(5))
    }

    @Test
    fun `JVM crashes and ANRs are collected too`() {
        assertTrue("REASON_CRASH", ExitInfoCollector.isInteresting(4))
        assertTrue("REASON_ANR", ExitInfoCollector.isInteresting(6))
    }

    @Test
    fun `ordinary exits are not written to the logs dir`() {
        // A user swiping the app away, or the system trimming it, is not a
        // diagnostic event; writing a file per launch for those would bury the
        // crashes this directory exists to surface.
        assertFalse("REASON_EXIT_SELF", ExitInfoCollector.isInteresting(1))
        assertFalse("REASON_SIGNALED", ExitInfoCollector.isInteresting(2))
        assertFalse("REASON_LOW_MEMORY", ExitInfoCollector.isInteresting(3))
        assertFalse("REASON_USER_REQUESTED", ExitInfoCollector.isInteresting(10))
    }

    @Test
    fun `reason names are stable for the collected kinds`() {
        // These strings land in a file a user pastes into an issue, so they are
        // part of the report's contract rather than an implementation detail.
        assertEquals("CRASH_NATIVE", ExitInfoCollector.reasonName(5))
        assertEquals("CRASH", ExitInfoCollector.reasonName(4))
        assertEquals("ANR", ExitInfoCollector.reasonName(6))
    }

    @Test
    fun `an unknown reason still renders rather than throwing`() {
        // Newer platforms add reasons; a report must degrade to the raw number,
        // never fail to be written.
        assertEquals("reason=999", ExitInfoCollector.reasonName(999))
    }

    @Test
    fun `the trace cap is bounded but large enough to carry a tombstone head`() {
        // The abort message, faulting backtrace and memory map all sit in the
        // first few KB; the cap exists so one crash cannot fill the logs dir.
        assertTrue(ExitInfoCollector.MAX_TRACE_BYTES >= 64 * 1024)
        assertTrue(ExitInfoCollector.MAX_TRACE_BYTES <= 1024 * 1024)
    }

    // ── Source assertions: platform-bound behaviour ──────────────────────

    private val src: String by lazy { ProductionSources.read("crash/ExitInfoCollector.kt") }

    @Test
    fun `the collector asks the platform for exit records`() {
        assertTrue(
            "getHistoricalProcessExitReasons is the only permission-free route to a tombstone",
            src.contains("getHistoricalProcessExitReasons"),
        )
    }

    @Test
    fun `the abort message and the tombstone are both read`() {
        assertTrue(
            "description IS the abort message — the field the issue was filed for",
            src.contains("info.description"),
        )
        assertTrue(
            "traceInputStream carries the full tombstone incl. the memory map",
            src.contains("traceInputStream"),
        )
    }

    @Test
    fun `de-duplication keeps a persisted high-water mark`() {
        // Without this the same exit record is re-written on every launch for
        // as long as the platform keeps it — which is days.
        assertTrue(src.contains(ExitInfoCollector.KEY_HIGH_WATER))
        assertTrue("the mark must be persisted, not just held in memory", src.contains("getSharedPreferences"))
        assertTrue("…and advanced", src.contains("putLong(KEY_HIGH_WATER"))
        assertTrue("…and consulted", src.contains("if (info.timestamp <= highWater) continue"))
    }

    @Test
    fun `reports land where the log UI and the other crash writers already look`() {
        assertTrue("same dir as crash-*.log / native-crash-*.log", src.contains("File(context.filesDir, \"logs\")"))
        assertTrue("same .log extension LogManagementScreen filters for", src.contains("exitinfo-"))
    }

    @Test
    fun `collection is gated on the API that provides the trace`() {
        assertTrue(
            "getTraceInputStream carries the tombstone from API 31 (S)",
            src.contains("Build.VERSION_CODES.S"),
        )
    }

    @Test
    fun `a diagnostic must never crash the app it is diagnosing`() {
        assertTrue("the whole collect() body is guarded", src.contains("catch (t: Throwable)"))
    }
}
