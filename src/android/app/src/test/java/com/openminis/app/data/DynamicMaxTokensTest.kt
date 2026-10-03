package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T12] dynamicMaxTokens and the mid-loop re-check (9b0606192, 3370298bf,
 * d2cf0cc13; issues #119 #326 #74).
 *
 * The 108%-over-cap session and the half-written file_write (#119) share a
 * root: once the input no longer fits, `max(remaining, MIN_MAX_TOKENS)` lifts a
 * negative remaining back to 1024 and the request still goes out. Android's
 * defence is upstream of that clamp — ContextPolicy.check returns
 * NEEDS_COMPACT at or past the window, and the in-loop guard runs BEFORE the
 * provider call on every tool iteration.
 *
 * [T-ctx-measure-outbound] What it judges changed: not the API-reported size of
 * the PREVIOUS request (stale the moment a compaction/offload/revert changes
 * the history), but the calibrated measurement of the request about to be sent
 * — `ContextSizeMeter` estimate × (report ÷ estimate of the same request). With
 * nothing changed since the report the two agree, so the cases below keep
 * their meaning; ContextMeasureOutboundTest covers what happens after a change.
 *
 * Both ChatViewModel pieces (dynamicMaxTokens, inLoopContextCheck, the Usage
 * bookkeeping) need a live ViewModel; they are ported verbatim below with a
 * source-grep drift guard on the load-bearing lines.
 */
class DynamicMaxTokensTest {

    // ── ports (ChatViewModel.kt) ───────────────────────────────────────────

    private val MIN_MAX_TOKENS = 1024
    private val GLOBAL_MAX_TOKENS_CEILING = 128_000
    private val maxInLoopCompactions = 3

    /** Port of `dynamicMaxTokens` (ChatViewModel ~L8952): the arithmetic only. */
    private fun dynamicMaxTokens(modelMaxOutput: Int, contextWindow: Int, lastContextTokens: Int): Int {
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, modelMaxOutput)
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        return minOf(maxOutputCeiling, clamped)
    }

    /** Port of the Usage-chunk bookkeeping (ChatViewModel ~L10322). */
    private fun contextTokensFrom(usage: LLMUsage, previous: Int): Int = when {
        usage.latestContextTokens > 0 -> usage.latestContextTokens
        usage.inputTokens > 0 -> usage.inputTokens + (usage.cacheReadInputTokens ?: 0) + (usage.cacheCreationInputTokens ?: 0)
        else -> previous
    }

    private enum class Action { PROCEED, COMPACTED, STOP }

    /**
     * Port of `inLoopContextCheck` (ChatViewModel) after T-ctx-measure-outbound:
     * judges the outbound measurement; a compaction must shrink it to earn a
     * retry; with compaction spent, sends while the request fits the window.
     * `compactTo` is what the compaction leaves the measurement at.
     */
    private class InLoopGuard(private val policy: ContextPolicy, private val window: Int) {
        var measured = 0
        var compactions = 0
        var noProgress = false
        var compactTo: (Int) -> Int = { it }
        fun check(): Action {
            val tokens = measured
            if (tokens <= 0) return Action.PROCEED
            return when (policy.check(tokens, window)) {
                ContextPolicy.CheckResult.OK -> Action.PROCEED
                ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                    if (compactions >= 3 || noProgress) {
                        return if (tokens < window) Action.PROCEED else Action.STOP
                    }
                    compactions += 1
                    measured = compactTo(tokens)
                    noProgress = measured >= tokens
                    Action.COMPACTED
                }
                ContextPolicy.CheckResult.EXHAUSTED -> Action.STOP
            }
        }
    }

    // ── case 1: window 128k, used 130k ─────────────────────────────────────

    @Test
    fun `input past the window is NEEDS_COMPACT before any max_tokens is computed - never a 1024 send`() {
        // Both resolutions of a 128K window agree.
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forUserCap(128_000).check(130_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forContextWindow(128_000).check(130_000, 128_000))
        // Exactly at the ceiling is already over.
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forContextWindow(128_000).check(128_000, 128_000))
        // A tier that cannot compact reports EXHAUSTED, which the loop turns into STOP.
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, ContextPolicy.forContextWindow(16_000).check(16_500, 16_000))

        // The clamp is why the guard has to fire first: on its own it would
        // hand back MIN_MAX_TOKENS for an input that does not fit.
        assertEquals(MIN_MAX_TOKENS, dynamicMaxTokens(8192, 128_000, 130_000))
        // …and the in-loop guard does fire, before the request is built.
        val g = InLoopGuard(ContextPolicy.forUserCap(128_000), 128_000)
        g.measured = 130_000
        g.compactTo = { it - 500 }                 // real but insufficient progress
        assertEquals(Action.COMPACTED, g.check())
        assertEquals(Action.COMPACTED, g.check())
        assertEquals(Action.COMPACTED, g.check())
        // Budget spent and still over the window: STOP rather than send.
        assertEquals(Action.STOP, g.check())
        assertEquals(3, g.compactions)
    }

    @Test
    fun `dynamicMaxTokens shrinks with the input and honours both ceilings`() {
        assertEquals(8192, dynamicMaxTokens(8192, 128_000, 10_000))            // plenty of room: model ceiling
        assertEquals(4_000, dynamicMaxTokens(8192, 128_000, 124_000))          // remaining wins
        assertEquals(GLOBAL_MAX_TOKENS_CEILING, dynamicMaxTokens(1_000_000, 2_000_000, 0)) // global cap
        assertEquals(8192, dynamicMaxTokens(8192, 0, 0))                       // unknown window: ceiling
    }

    // ── case 2: estimate 60k vs API 120k ───────────────────────────────────

    @Test
    fun `a report above the local estimate still decides - calibration reproduces it`() {
        val localEstimate = 60_000
        val reported = contextTokensFrom(LLMUsage(inputTokens = 5_000, outputTokens = 10, latestContextTokens = 120_000), previous = 0)
        assertEquals(120_000, reported)
        assertTrue(reported > localEstimate)
        // [T-ctx-measure-outbound] The judged size is estimate × (report ÷ estimate
        // of the same request): with nothing changed it IS the report.
        val ratio = ContextSizeMeter.calibrationRatio(reported, localEstimate)!!
        val judged = ContextSizeMeter.calibrated(localEstimate, ratio)
        assertEquals(reported, judged)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, ContextPolicy.forUserCap(128_000).check(judged, 128_000))
        assertEquals("the uncalibrated estimate alone would have let it through",
            ContextPolicy.CheckResult.OK, ContextPolicy.forUserCap(128_000).check(localEstimate, 128_000))

        // A provider that omits latestContextTokens: fresh input + cached prefix.
        val viaCache = contextTokensFrom(LLMUsage(inputTokens = 50_000, outputTokens = 1, cacheReadInputTokens = 70_000), previous = 0)
        assertEquals(120_000, viaCache)
        // A usage chunk with nothing usable keeps the previous reading.
        assertEquals(99, contextTokensFrom(LLMUsage(inputTokens = 0, outputTokens = 5), previous = 99))
    }

    // ── case 3: fifth tool turn crosses 85% ────────────────────────────────

    @Test
    fun `the 5th tool iteration crossing 85 percent compacts mid-loop and the next check measures the result`() {
        val window = 128_000
        val g = InLoopGuard(ContextPolicy.forUserCap(window), window)
        g.compactTo = { 40_000 }                   // summary + kept tail
        val readings = listOf(60_000, 72_000, 85_000, 100_000, 110_000) // 85% of 128k = 108,800
        val actions = readings.map { g.measured = it; g.check() }
        assertEquals(listOf(Action.PROCEED, Action.PROCEED, Action.PROCEED, Action.PROCEED, Action.COMPACTED), actions)
        // [T-ctx-measure-outbound] No free pass needed: the next check measures
        // the compacted history itself, so it proceeds on real data — and would
        // compact again only if the history were genuinely still too large.
        assertEquals(40_000, g.measured)
        assertEquals(Action.PROCEED, g.check())
        assertEquals(1, g.compactions)
    }

    // ── case 4: 32k user cap on a 1M model ─────────────────────────────────

    @Test
    fun `a 32k cap on a 1M model compacts at 27200`() {
        val p = ContextPolicy.forUserCap(32_000)
        assertEquals(27_200, p.compactThreshold)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(27_200, 32_000))
        assertEquals(ContextPolicy.CheckResult.OK, p.check(27_199, 32_000))
    }

    // ── drift guard ────────────────────────────────────────────────────────

    @Test
    fun `drift guard - guard precedes the provider call and the clamp still warns`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("private const val MIN_MAX_TOKENS = 1024"))
        assertTrue(vm.contains("private const val GLOBAL_MAX_TOKENS_CEILING = 128_000"))
        assertTrue(vm.contains("private val maxInLoopCompactions = 3"))
        assertTrue(vm.contains("val clamped = maxOf(remaining, MIN_MAX_TOKENS)"))
        assertTrue("overflow branch removed?", vm.contains("if (remaining <= 0) {"))
        // Order inside runAgentLoop: offload -> in-loop check -> streamMessage.
        val loop = vm.substring(vm.indexOf("suspend fun runAgentLoop"))
        val check = loop.indexOf("when (inLoopContextCheck(inLoopCompactions))")
        val send = loop.indexOf("currentProvider.streamMessage(")
        assertTrue("in-loop guard must run before the provider call", check in 1 until send)
        // [T-ctx-measure-outbound] max_tokens is sized from the dispatch measurement.
        assertTrue(loop.contains("dynamicMaxTokens(currentProvider, dispatchInputTokens)"))
        // The report still feeds the glow, and now calibrates the meter…
        assertTrue(vm.contains("lastContextTokens = chunk.usage.latestContextTokens"))
        assertTrue(vm.contains("_lastTurnContextTokens.value = lastContextTokens"))
        assertTrue(vm.contains("calibrateContextSize(lastContextTokens)"))
        // …but both guards judge the outbound measurement, not the report.
        assertEquals(2, vm.split("val m = contextMeasurement()\n        val tokens = m.measured").size - 1)
        assertTrue(!vm.contains("val tokens = _lastTurnContextTokens.value"))
        assertTrue(!vm.contains("private var lastTurnContextTokensStale"))
        // ContextPolicy hard stop still present.
        val policy = ProductionSources.read("data/ContextPolicy.kt")
        assertTrue(policy.contains("if (contextWindow > 0 && estimatedTokens >= contextWindow) {"))
    }
}
