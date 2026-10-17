package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-usage-time-range] Source-grep drift guards for the Usage page's
 * performance decisions. The page was rebuilt with a time filter and a
 * daily chart; two properties of that rebuild are load-bearing for the
 * "no jank / no regression" requirement and invisible in a screenshot:
 *
 *  1. Aggregation (thousands of rows × per-row JSONObject parsing) must run
 *     on Dispatchers.Default, never on the main thread — the same discipline
 *     the streaming path follows since the native-OOM fix.
 *  2. The chart series is window-independent (fixed 14-day lookback built
 *     once per records load). If it were re-derived inside the range
 *     LaunchedEffect, every filter tap would re-parse the whole record list
 *     for no benefit.
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
    fun chartSeriesIsNotRebuiltOnRangeChange() {
        val source = ProductionSources.read("ui/settings/UsageStatsScreen.kt")
        // No closing paren: the effect keys on (timeRange, records) so a
        // records load also re-derives; the guard must match that prefix.
        val rangeEffectStart = source.indexOf("LaunchedEffect(timeRange")
        assertTrue("range LaunchedEffect must exist", rangeEffectStart >= 0)
        val rangeEffectEnd = source.indexOf("SettingsScaffold", rangeEffectStart)
        val rangeEffect = source.substring(rangeEffectStart, rangeEffectEnd)
        assertTrue(
            "buildDailySeries must NOT run inside the range LaunchedEffect — the series " +
                "is window-independent and rebuilding it per tap re-parses every row",
            !rangeEffect.contains("buildDailySeries"),
        )
        // And it must still be built somewhere (once, on load).
        assertTrue(
            "buildDailySeries must be called on records load",
            source.contains("buildDailySeries("),
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
