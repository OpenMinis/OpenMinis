package com.openminis.app.sandbox.offload

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-calendar-all-day][T-android-calendar-strict-date] Date handling in
 * android-calendar, aligned with iOS CalendarOffload.m.
 *
 * The three functions under test never touch the Context the handler is
 * constructed with, so they can be exercised without an Android runtime.
 */
class CalendarDateParsingTest {

    // The three functions live in the companion object precisely because they
    // are pure — no Context, no ContentResolver — so they are callable here
    // without an Android runtime.
    private val handler = CalendarOffloadHandler.Companion

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(java.util.Date(ms))

    // ── strict parsing ──────────────────────────────────────────────────

    /**
     * The regression this exists for. SimpleDateFormat is lenient by default
     * and ROLLS OVER out-of-range fields instead of rejecting them, so these
     * two inputs used to parse — "2026-13-45" as 2027-02-14 and the 99:99:99
     * time as four days later at 04:40 — and android-calendar then created a
     * real event at a time nobody asked for, with no error anywhere.
     */
    @Test
    fun `out-of-range date and time components are rejected, not rolled over`() {
        assertNull("month 13 / day 45 must not roll into the next year", handler.parseDate("2026-13-45"))
        assertNull("99:99:99 must not roll into the next day", handler.parseDate("2026-12-01T99:99:99"))
        assertNull(handler.parseDate("2026-02-30"))
        assertNull(handler.parseDate("2026-00-10"))
    }

    /** Lenient parsing stopped at the first bad character, matching on a prefix. */
    @Test
    fun `trailing garbage is rejected rather than matched on its prefix`() {
        assertNull(handler.parseDate("2026-12-01nonsense"))
        assertNull(handler.parseDate("2026-12-01T10:30junk"))
    }

    @Test
    fun `malformed and empty input returns null instead of throwing`() {
        for (bad in listOf("", "   ", "not-a-date", "12-01", "2026/12/01", "T10:30", "--", "0")) {
            assertNull("'$bad' must parse to null", handler.parseDate(bad))
        }
    }

    @Test
    fun `the valid formats still parse`() {
        assertNotNull(handler.parseDate("2026-12-01"))
        assertNotNull(handler.parseDate("2026-12-01T10:30"))
        assertNotNull(handler.parseDate("2026-12-01T10:30:15"))
        assertNotNull(handler.parseDate("2026-12-01T10:30:15Z"))
        assertNotNull(handler.parseDate("2026-12-01T10:30:15+08:00"))
        // Relative offsets keep working.
        assertNotNull(handler.parseDate("-7d"))
        assertNotNull(handler.parseDate("+30m"))
        // Surrounding whitespace is tolerated, since the arg may be quoted.
        assertNotNull(handler.parseDate("  2026-12-01  "))
    }

    // ── date-only detection ─────────────────────────────────────────────

    @Test
    fun `only a bare YYYY-MM-DD counts as date-only`() {
        assertTrue(handler.isDateOnly("2026-12-01"))
        // A datetime must NOT match on its date prefix — that is the whole
        // reason this is a positional check and not a formatter.
        assertFalse(handler.isDateOnly("2026-12-01T10:30"))
        assertFalse(handler.isDateOnly("2026-12-01T00:00:00"))
        assertFalse(handler.isDateOnly(null))
        assertFalse(handler.isDateOnly(""))
        assertFalse(handler.isDateOnly("2026-12-1"))
        assertFalse(handler.isDateOnly("-7d"))
        // Right shape, impossible date: rejected because parseDate rejects it.
        assertFalse(handler.isDateOnly("2026-13-45"))
    }

    // ── all-day bounds ──────────────────────────────────────────────────

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    @Test
    fun `bounds snap to local midnight and the last second of the end day`() {
        val (s, e) = handler.allDayBounds(ms(2026, 12, 1, 15, 42), ms(2026, 12, 3, 9, 5))
        assertEquals("2026-12-01 00:00:00", fmt(s))
        assertEquals("2026-12-03 23:59:59", fmt(e))
    }

    @Test
    fun `a single day spans that whole day`() {
        val (s, e) = handler.allDayBounds(ms(2026, 12, 1, 15, 42), null)
        assertEquals("2026-12-01 00:00:00", fmt(s))
        assertEquals("2026-12-01 23:59:59", fmt(e))
    }

    /** An inverted range collapses to one day rather than producing end < start. */
    @Test
    fun `an end before the start collapses to the start day`() {
        val (s, e) = handler.allDayBounds(ms(2026, 12, 5), ms(2026, 12, 1))
        assertEquals("2026-12-05 00:00:00", fmt(s))
        assertEquals("2026-12-05 23:59:59", fmt(e))
        assertTrue("end must never precede start", e > s)
    }

    /**
     * The last second is computed as (next midnight - 1s) through calendar
     * arithmetic, so a short or long DST day still ends on its own last
     * second instead of landing an hour off. Asserted in a zone that actually
     * has DST, restored afterwards.
     */
    @Test
    fun `a DST transition day still ends at its own last second`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // 2026-03-08 is the US spring-forward day (a 23-hour day).
            val (s, e) = handler.allDayBounds(ms(2026, 3, 8, 12, 0), null)
            assertEquals("2026-03-08 00:00:00", fmt(s))
            assertEquals("2026-03-08 23:59:59", fmt(e))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
