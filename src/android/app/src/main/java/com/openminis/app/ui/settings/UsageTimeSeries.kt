package com.openminis.app.ui.settings

import com.openminis.app.data.db.UsageRecord
import java.util.Calendar

/**
 * Time-window selector for the Usage page. [days] counts the calendar days
 * INCLUDED in the window (today inclusive): LAST_7 covers today and the six
 * days before it. ALL keeps the full-history behaviour the page used to have.
 */
internal enum class UsageTimeRange(val days: Int?) {
    TODAY(1),
    LAST_7(7),
    LAST_30(30),
    ALL(null),
}

/** One day's aggregated usage; [dateKey] is a local `yyyy-MM-dd` string. */
internal data class DailyUsage(
    val dateKey: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

/**
 * Bars shown in the trend chart for the ALL window. Full history is
 * unreadable as daily bars on a phone, so the widest window still caps at
 * the widest readable span; the section header states the window so the
 * cap is never mistaken for the data's extent.
 */
internal const val ALL_CHART_DAYS = 30

/**
 * Parse every record ONCE into per-day buckets. This is the expensive half
 * of the chart pipeline (a JSONObject parse per row); it runs on records
 * load only — never per filter tap.
 */
internal fun buildDayBuckets(records: List<UsageRecord>): Map<String, LongArray> {
    val byKey = HashMap<String, LongArray>()
    for (record in records) {
        val usage = try {
            org.json.JSONObject(record.tokenUsage)
        } catch (_: Exception) {
            continue
        }
        val input = usage.optLong("inputTokens", 0) +
            usage.optLong("cacheReadTokens", 0) +
            usage.optLong("cacheCreationTokens", 0)
        val output = usage.optLong("outputTokens", 0)
        val key = localDateKey(record.createdAt)
        val acc = byKey.getOrPut(key) { longArrayOf(0, 0) }
        acc[0] += input
        acc[1] += output
    }
    return byKey
}

/**
 * Zero-filled daily series for the last [days] days, read from pre-parsed
 * [buildDayBuckets] output. Pure arithmetic + map lookups — cheap enough to
 * run on every filter tap, which is exactly what the window-following chart
 * requires ([T-usage-window-drives-everything]).
 */
internal fun sliceDailySeries(
    buckets: Map<String, LongArray>,
    days: Int,
    nowMillis: Long,
): List<DailyUsage> {
    require(days >= 1) { "days must be >= 1" }
    return (days - 1 downTo 0).map { offset ->
        val key = localDateKey(localMidnightBefore(nowMillis, offset))
        val acc = buckets[key]
        DailyUsage(key, acc?.get(0) ?: 0L, acc?.get(1) ?: 0L)
    }
}

/**
 * Local-midnight boundary (ms) of the day `offsetDays` before today.
 * offsetDays=0 → today 00:00. Pure w.r.t. (nowMillis, offsetDays) so the
 * window math is unit-testable without touching the clock.
 */
internal fun localMidnightBefore(nowMillis: Long, offsetDays: Int): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowMillis
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_YEAR, -offsetDays)
    return cal.timeInMillis
}

/**
 * Keep only records inside [range]. ALL returns the input untouched (no
 * defensive copy — the caller treats records as read-only).
 */
internal fun filterUsageByRange(
    records: List<UsageRecord>,
    range: UsageTimeRange,
    nowMillis: Long,
): List<UsageRecord> {
    val days = range.days ?: return records
    val start = localMidnightBefore(nowMillis, days - 1)
    return records.filter { it.createdAt >= start }
}

/**
 * Local `yyyy-MM-dd` key derived from the SAME Calendar source as
 * [localMidnightBefore] — deliberately NOT IsoTime.formatLocalDate, whose
 * DateTimeFormatter captures ZoneId.systemDefault() at class-load time;
 * under a mid-process timezone change (or a test JVM that pins the default
 * after IsoTime was first loaded) the two sources disagree and a record can
 * land one day off its bucket.
 */
internal fun localDateKey(ms: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    return "%04d-%02d-%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )
}

/**
 * Build a fixed-length daily series ending today (inclusive), zero-filled
 * for days without usage. Composed of [buildDayBuckets] (the parse) and
 * [sliceDailySeries] (the window); kept as one call for tests and for
 * callers that only need the series once.
 */
internal fun buildDailySeries(
    records: List<UsageRecord>,
    days: Int,
    nowMillis: Long,
): List<DailyUsage> = sliceDailySeries(buildDayBuckets(records), days, nowMillis)
