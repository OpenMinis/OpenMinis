package com.openminis.app.sandbox.offload

import com.openminis.app.ProductionSources
import com.openminis.app.sandbox.offload.CalendarOffloadHandler.Companion.CalPick
import com.openminis.app.sandbox.offload.CalendarOffloadHandler.Companion.CalRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-calendar-exact-target] android-calendar write targeting (iOS
 * 8200eeb81 + 381e59dc7, issue #282).
 *
 * --calendar matched by substring and fell back to the auto-picked calendar on
 * a miss, still reporting success; for `update` a typo therefore MOVED the
 * event to the default calendar.
 */
class CalendarExactTargetTest {

    private val work = CalRef(3, "Work", "me@gmail.com")
    private val workout = CalRef(4, "Workout", "me@gmail.com")
    private val personal = CalRef(5, "Personal", "Local")
    private val cals = listOf(workout, work, personal)

    @Test
    fun `a name matches exactly and ignores case and surrounding space`() {
        assertEquals(CalPick.Found(work), CalendarOffloadHandler.pickCalendarByName("work", cals))
        assertEquals(CalPick.Found(work), CalendarOffloadHandler.pickCalendarByName("  WORK ", cals))
    }

    @Test
    fun `a substring no longer lands in another calendar`() {
        // "Wor" used to match whichever of Work/Workout came first.
        assertTrue(CalendarOffloadHandler.pickCalendarByName("Wor", cals) is CalPick.Missing)
        // "Work" must not pick "Workout" even when it is listed first.
        assertEquals(CalPick.Found(work), CalendarOffloadHandler.pickCalendarByName("Work", cals))
    }

    @Test
    fun `a miss is an error that names the input`() {
        val pick = CalendarOffloadHandler.pickCalendarByName("Wrok", cals)
        assertTrue(pick is CalPick.Missing)
        assertTrue((pick as CalPick.Missing).message.contains("'Wrok'"))
    }

    @Test
    fun `two calendars with the same name are refused as ambiguous`() {
        val twin = CalRef(9, "Work", "Local")
        val pick = CalendarOffloadHandler.pickCalendarByName("work", cals + twin)
        assertTrue(pick is CalPick.Ambiguous)
        assertEquals(listOf(work, twin), (pick as CalPick.Ambiguous).matches)
        assertTrue(pick.message.contains("--calendar-id"))
    }

    @Test
    fun `a calendar id must name a writable calendar`() {
        assertEquals(CalPick.Found(personal), CalendarOffloadHandler.pickCalendarById(5, cals))
        assertTrue(CalendarOffloadHandler.pickCalendarById(42, cals) is CalPick.Missing)
    }

    @Test
    fun `writes refuse an unresolved target instead of falling back, and echo where they landed`() {
        val src = ProductionSources.read("sandbox/offload/CalendarOffloadHandler.kt")
        assertFalse("the silent auto-pick fallback is back", src.contains("falling back to auto-pick"))
        val update = src.substringAfter("private fun doUpdate(").substringBefore("private fun doDelete(")
        assertTrue(update.contains("is Target.Failed -> return target.result"))
        assertTrue(update.contains(".putCalendar(calendarOfEvent(id))"))
        val create = src.substringAfter("private fun doCreate(").substringBefore("private fun doUpdate(")
        assertTrue(create.contains("if (target is Target.Failed) return target.result"))
        val delete = src.substringAfter("private fun doDelete(").substringBefore("private fun doFreebusy(")
        val captured = delete.indexOf("val cal = calendarOfEvent(id)")
        assertTrue("delete captures the calendar before the row is gone",
            captured >= 0 && captured < delete.indexOf("contentResolver.delete("))
    }

    @Test
    fun `the list filter keeps substring matching`() {
        val src = ProductionSources.read("sandbox/offload/CalendarOffloadHandler.kt")
        val list = src.substringAfter("private fun doList(").substringBefore("private fun doCreate(")
        assertTrue(list.contains("calFilter"))
        assertFalse(list.contains("pickCalendarByName"))
    }
}
