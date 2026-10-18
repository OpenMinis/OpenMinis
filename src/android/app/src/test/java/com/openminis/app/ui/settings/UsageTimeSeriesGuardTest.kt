package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-usage-time-range] Source-grep drift guards for the Usage page's
 * performance decisions. The page was rebuilt with a time filter and a
 * window-following daily chart; three properties of that rebuild are
 * load-bearing for the "no jank / no regression" requirement and invisible
 * in a screenshot:
 *
 *  1. Aggregation (thousands of rows × per-row JSONObject parsing) must run
 *     on Dispatchers.Default, never on the main thread — the same discipline
 *     the streaming path follows since the native-OOM fix.
 *  2. The chart FOLLOWS the selected window ([T-usage-window-drives-everything])
 *     by slicing pre-parsed day buckets. The parse (buildDayBuckets) runs
 *     exactly once, on records load, off the main thread; the range effect
 *     must never re-parse rows — slicing is the only chart work it does.
 *  3. The chart draws on Canvas with a tap-select; an infinite animation
 *     would peg the frame budget on a settings page for zero gain.
 */
class UsageTimeSeriesGuardTest {

    @Test
    fun aggregationRunsOffTheMainThread() {
        val source = ProductionSources.read("ui/settings/UsageStatsScreen.kt")
        assertTrue(
            "aggregateUsageRecords must be wrapped in withContext(Dispatchers.Default) — " +
                "running it on main reintroduces a startup jank burst",
            Regex("withContext\\(Dispatchers\\.Default\\)\\s*\\{\\s*[\\s\\S]*?aggregateUsageRecords")
                .containsMatchIn(source),
        )
    }

    @Test
    fun dayBucketsAreParsedOnceOffTheMainThread() {
        val source = ProductionSources.read("ui/settings/UsageStatsScreen.kt")
        assertTrue(
            "buildDayBuckets must be called (the chart slices its output)",
            source.contains("buildDayBuckets("),
        )
        assertTrue(
            "buildDayBuckets must be wrapped in withContext(Dispatchers.Default) — " +
                "it parses a JSONObject per row and must never touch main",
            Regex("withContext\\(Dispatchers\\.Default\\)\\s*\\{\\s*buildDayBuckets")
                .containsMatchIn(source),
        )
    }

    @Test
    fun chartFollowsTheWindowWithoutReparsing() {
        val source = ProductionSources.read("ui/settings/UsageStatsScreen.kt")
        val rangeEffectStart = source.indexOf("LaunchedEffect(timeRange")
        assertTrue("range LaunchedEffect must exist", rangeEffectStart >= 0)
        val rangeEffectEnd = source.indexOf("SettingsScaffold", rangeEffectStart)
        val rangeEffect = source.substring(rangeEffectStart, rangeEffectEnd)
        assertTrue(
            "the range effect must slice the chart from the selected window — " +
                "otherwise the chart ignores the filter the user just tapped",
            rangeEffect.contains("sliceDailySeries("),
        )
        assertTrue(
            "the range effect must NOT re-parse rows (buildDailySeries/buildDayBuckets) — " +
                "slicing pre-parsed buckets is the whole point; a per-tap parse " +
                "re-does the load-time JSONObject work for zero benefit",
            !rangeEffect.contains("buildDailySeries(") && !rangeEffect.contains("buildDayBuckets("),
        )
    }

    @Test
    fun chartComponentStaysOffAnimation() {
        // The chart draws on Canvas with a tap-select; an infinite animation
        // (rememberInfiniteTransition / animate* loop) would peg the frame
        // budget on a settings page for zero information gain.
        val source = ProductionSources.read("ui/settings/UsageTrendChart.kt")
        assertTrue(
            "UsageTrendChart must not use infinite animations",
            !source.contains("rememberInfiniteTransition") && !source.contains("animateFloatAsState"),
        )
    }
}
