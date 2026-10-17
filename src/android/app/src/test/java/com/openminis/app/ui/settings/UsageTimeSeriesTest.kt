package com.openminis.app.ui.settings

import com.openminis.app.data.db.UsageRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Time-window math for the Usage page. All tests pin the JVM default
 * timezone first: localMidnightBefore and buildDailySeries are defined in
 * LOCAL calendar days, and a CI runner in UTC would otherwise shift every
 * expected boundary by 8 hours.
 */
class UsageTimeSeriesTest {
    private val originalTz = TimeZone.getDefault()

    @Before
    fun pinTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimezone() {
        TimeZone.setDefault(originalTz)
    }

    /** 2026-10-18 15:30:00 CST (epoch ms), built via the same Calendar path. */
    private val now: Long
        get() {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            cal.clear()
            cal.set(2026, Calendar.OCTOBER, 18, 15, 30, 0)
            return cal.timeInMillis
        }

    private fun midnight(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.clear()
        cal.set(year, month, day, 0, 0, 0)
        return cal.timeInMillis
    }

    private fun row(createdAt: Long, usage: String = """{"inputTokens":100,"outputTokens":10}""") =
        UsageRecord(
            modelId = "m", modelDisplayName = "M", providerType = "openAI",
            providerInstanceId = null, hasSnapshot = true,
            tokenUsage = usage, createdAt = createdAt, sessionId = "s",
        )

    // ── localMidnightBefore ───────────────────────────────────────────

    @Test
    fun midnightTodayIsLocalZeroHour() {
        assertEquals(midnight(2026, Calendar.OCTOBER, 18), localMidnightBefore(now, 0))
    }

    @Test
    fun midnightYesterdayIsOneDayBack() {
        assertEquals(midnight(2026, Calendar.OCTOBER, 17), localMidnightBefore(now, 1))
    }

    @Test
    fun midnightThirteenDaysBackIsWindowStart() {
        assertEquals(midnight(2026, Calendar.OCTOBER, 5), localMidnightBefore(now, 13))
    }

    @Test
    fun midnightCrossesMonthBoundary() {
        // 2026-10-18 minus 18 days = 2026-09-30
        assertEquals(midnight(2026, Calendar.SEPTEMBER, 30), localMidnightBefore(now, 18))
    }

    // ── filterUsageByRange ────────────────────────────────────────────

    @Test
    fun todayKeepsOnlyRowsFromLocalMidnight() {
        val inToday = row(midnight(2026, Calendar.OCTOBER, 18) + 3600_000)
        val yesterday = row(midnight(2026, Calendar.OCTOBER, 17) + 3600_000)
        val out = filterUsageByRange(listOf(inToday, yesterday), UsageTimeRange.TODAY, now)
        assertEquals(listOf(inToday), out)
    }

    @Test
    fun last7IncludesBoundaryMidnightAndExcludesDay7() {
        val boundary = row(midnight(2026, Calendar.OCTOBER, 12))          // 6 days back, 00:00
        val justBefore = row(midnight(2026, Calendar.OCTOBER, 12) - 1)    // 10-11 23:59:59.999
        val out = filterUsageByRange(listOf(boundary, justBefore), UsageTimeRange.LAST_7, now)
        assertEquals(listOf(boundary), out)
    }

    @Test
    fun allReturnsInputUnfiltered() {
        val old = row(0L)
        val out = filterUsageByRange(listOf(old), UsageTimeRange.ALL, now)
        assertEquals(listOf(old), out)
    }

    // ── buildDailySeries ──────────────────────────────────────────────

    @Test
    fun seriesIsFixedLengthAndZeroFilled() {
        val series = buildDailySeries(emptyList(), 14, now)
        assertEquals(14, series.size)
        assertEquals("2026-10-05", series.first().dateKey)
        assertEquals("2026-10-18", series.last().dateKey)
        assertTrue(series.all { it.inputTokens == 0L && it.outputTokens == 0L })
    }

    @Test
    fun inputAggregatesWithCacheTokens() {
        // The chart's input bar must use the SAME口径 as ModelStats.totalInput
        // (input + cacheRead + cacheCreation), or the bars and the rows would
        // disagree about what "input" means.
        val r = row(
            midnight(2026, Calendar.OCTOBER, 18) + 1000,
            """{"inputTokens":100,"outputTokens":30,"cacheReadTokens":50,"cacheCreationTokens":25}""",
        )
        val series = buildDailySeries(listOf(r), 14, now)
        assertEquals(175L, series.last().inputTokens)
        assertEquals(30L, series.last().outputTokens)
    }

    @Test
    fun rowsOutsideWindowAreIgnored() {
        val outside = row(midnight(2026, Calendar.OCTOBER, 4) + 1000) // 14 days back
        val series = buildDailySeries(listOf(outside), 14, now)
        assertTrue(series.all { it.inputTokens == 0L })
    }

    @Test
    fun sameDayRowsSumIntoOneBucket() {
        val a = row(midnight(2026, Calendar.OCTOBER, 17) + 1000, """{"inputTokens":10,"outputTokens":1}""")
        val b = row(midnight(2026, Calendar.OCTOBER, 17) + 2000, """{"inputTokens":20,"outputTokens":2}""")
        val series = buildDailySeries(listOf(a, b), 14, now)
        assertEquals(30L, series[12].inputTokens)  // 10-17 is index 12
        assertEquals(3L, series[12].outputTokens)
    }

    @Test
    fun malformedJsonRowsAreSkipped() {
        val bad = row(midnight(2026, Calendar.OCTOBER, 18) + 1000, """not-json""")
        val good = row(midnight(2026, Calendar.OCTOBER, 18) + 2000, """{"inputTokens":5,"outputTokens":1}""")
        val series = buildDailySeries(listOf(bad, good), 14, now)
        assertEquals(5L, series.last().inputTokens)
    }
}
