package com.openminis.app.sandbox.offload

import com.openminis.app.ProductionSources
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-calendar-update-all-day] `android-calendar update` all-day support
 * (iOS parity: fe7a01bc5 + 86e91526e) and the all-day storage form both create
 * and update now write.
 *
 * The provider requires an all-day event to be stored as UTC midnights with
 * EVENT_TIMEZONE=UTC. create used to store LOCAL midnight .. 23:59:59; east of
 * UTC that is the previous UTC day (Asia/Shanghai: 16:00Z), which the provider
 * snaps down — the event landed a day early.
 */
class CalendarUpdateAllDayTest {

    private val h = CalendarOffloadHandler.Companion
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(tz: TimeZone, y: Int, m: Int, d: Int, hh: Int = 0, mm: Int = 0, ss: Int = 0): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, hh, mm, ss) }.timeInMillis

    private fun fmt(tz: TimeZone, ms: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }.format(java.util.Date(ms))

    // ---- storage form ---------------------------------------------------

    @Test
    fun `a local day east of UTC is stored as that same date's UTC midnights`() {
        val (s, e) = h.allDayStoredRange(at(shanghai, 2026, 12, 10), at(shanghai, 2026, 12, 10, 23, 59, 59), shanghai)
        assertEquals("2026-12-10 00:00:00", fmt(utc, s))
        assertEquals("exclusive end: the next UTC midnight", "2026-12-11 00:00:00", fmt(utc, e))
    }

    @Test
    fun `west of UTC too - the local date wins, not the UTC instant`() {
        // 2026-12-10 23:30 in LA is already 12-11 in UTC.
        val (s, e) = h.allDayStoredRange(at(la, 2026, 12, 10, 23, 30), null, la)
        assertEquals("2026-12-10 00:00:00", fmt(utc, s))
        assertEquals("2026-12-11 00:00:00", fmt(utc, e))
    }

    @Test
    fun `a multi-day range and an end before the start`() {
        val (s, e) = h.allDayStoredRange(at(shanghai, 2026, 12, 10), at(shanghai, 2026, 12, 12, 9), shanghai)
        assertEquals("2026-12-10 00:00:00", fmt(utc, s))
        assertEquals("2026-12-13 00:00:00", fmt(utc, e))
        val (s2, e2) = h.allDayStoredRange(at(shanghai, 2026, 12, 10), at(shanghai, 2026, 12, 8), shanghai)
        assertEquals("collapses to one day", "2026-12-11 00:00:00", fmt(utc, e2))
        assertEquals(s2 + 24 * 3600_000L, e2)
    }

    @Test
    fun `stored range reads back as local 00-00-00 to 23-59-59`() {
        for (tz in listOf(shanghai, la, utc)) {
            val (s, e) = h.allDayStoredRange(at(tz, 2026, 12, 10), at(tz, 2026, 12, 11), tz)
            val (ls, le) = h.allDayLocalRange(s, e, tz)
            assertEquals(tz.id, "2026-12-10 00:00:00", fmt(tz, ls))
            assertEquals(tz.id, "2026-12-11 23:59:59", fmt(tz, le))
        }
    }

    @Test
    fun `a missing or degenerate stored end reads back as one day`() {
        val s = at(utc, 2026, 12, 10)
        val (_, e1) = h.allDayLocalRange(s, null, shanghai)
        assertEquals("2026-12-10 23:59:59", fmt(shanghai, e1))
        val (_, e2) = h.allDayLocalRange(s, s, shanghai)
        assertEquals("2026-12-10 23:59:59", fmt(shanghai, e2))
    }

    @Test
    fun `a DST day still ends on its own last second`() {
        // 2026-03-08 is 23 hours long in Los Angeles.
        val (s, e) = h.allDayStoredRange(at(la, 2026, 3, 8), null, la)
        val (ls, le) = h.allDayLocalRange(s, e, la)
        assertEquals("2026-03-08 00:00:00", fmt(la, ls))
        assertEquals("2026-03-08 23:59:59", fmt(la, le))
    }

    // ---- update wiring ----------------------------------------------------

    private val src = ProductionSources.read("sandbox/offload/CalendarOffloadHandler.kt")
    private val update = src.substringAfter("private fun doUpdate(").substringBefore("// ── delete")

    @Test
    fun `update infers all-day only from the date arguments actually passed`() {
        assertTrue(update.contains("val makeAllDay = args.hasFlag(\"all-day\") || allBare"))
        assertTrue(update.contains("val allBare = sawDate &&"))
        assertTrue(update.contains("(startStr == null || isDateOnly(startStr)) && (endStr == null || isDateOnly(endStr))"))
    }

    @Test
    fun `going all-day writes the provider's form, going timed writes every field together`() {
        assertTrue(update.contains("putAllDay(values, s0, e0)"))
        val timed = update.substringAfter("cur.allDay && carriesTime -> {").substringBefore("else -> {")
        for (f in listOf("ALL_DAY, 0", "EVENT_TIMEZONE, TimeZone.getDefault().id", "DTSTART, s0", "DTEND, e0")) {
            assertTrue(f, timed.contains(f))
        }
        assertTrue("a lone --all-day snaps the event's current range", update.contains("allDayBounds(newStart ?: curStart, newEnd ?: curEnd)"))
        assertTrue("recurring events refuse a switch", update.contains("switching && cur.rrule != null"))
    }

    @Test
    fun `update echoes is_all_day and the stored range`() {
        assertTrue(update.contains(".put(\"is_all_day\", t.allDay)"))
        assertTrue(update.contains("if (t.allDay) allDayLocalRange(t.dtStart, t.dtEnd)"))
    }

    @Test
    fun `list reports and filters all-day rows as their local days`() {
        val list = src.substringAfter("private fun doList(").substringBefore("private fun doCreate(")
        assertTrue("query widened by a day each side", list.contains("arrayOf((startMs - DAY_MS).toString(), (endMs + DAY_MS).toString())"))
        assertTrue(list.contains("if (allDay) allDayLocalRange(it.getLong(2), rawEnd)"))
        assertTrue("filtered on the LOCAL start", list.contains("if (s0 < startMs || s0 > endMs) continue"))
        assertTrue(list.contains("rows.sortedBy { it.first }"))
    }

    @Test
    fun `create stores the same form, and its usage and update's list --all-day`() {
        val create = src.substringAfter("private fun doCreate(").substringBefore("private fun doUpdate(")
        assertTrue(create.contains("putAllDay(this, startMsFinal, endMs)"))
        assertTrue(src.contains("[--all-day] [--notes ...] [--location ...] [--alarm <minutes>]"))
    }
}
