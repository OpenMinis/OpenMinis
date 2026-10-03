package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.ContextPolicy.CheckResult
import com.openminis.app.data.ContextPolicy.InLoopStep
import com.openminis.app.data.ContextSizeMeter.CalibrationSample
import com.openminis.app.data.ContextSizeMeter.CalibrationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the auto-compaction and revert decisions that a later edit is
 * most likely to break without any other test noticing. Every case drives the
 * production functions, not a copy of them:
 *
 *  - the in-loop decision table ([ContextPolicy.inLoopStep]) and the
 *    "never wedge" properties it exists for;
 *  - a small agent-loop model built only from production functions, for the
 *    interactions (smoothing × valve × rejection × compaction);
 *  - the calibration replay a reload runs, and the same-session carry-over a
 *    revert depends on;
 *  - the warm-up trim's turn boundaries.
 *
 * iOS twin: MinisTests/Standalone/CompactionDecisionTests.swift.
 */
class CompactionDecisionTest {

    private val window = 128_000
    private val policy = ContextPolicy.forContextWindow(window)

    private fun step(
        verdict: CheckResult = CheckResult.NEEDS_COMPACT,
        measured: Int,
        raw: Int = measured,
        window: Int = this.window,
        canCompact: Boolean = false,
        ratio: Double = 1.0,
        used: Boolean = false,
    ) = ContextPolicy.inLoopStep(verdict, measured, raw, window, canCompact, ratio, used)

    // ── in-loop decision table ─────────────────────────────────────────────

    @Test
    fun `OK always sends and EXHAUSTED always stops`() {
        assertEquals(InLoopStep.PROCEED, step(CheckResult.OK, measured = 500_000, canCompact = true))
        assertEquals(InLoopStep.STOP, step(CheckResult.EXHAUSTED, measured = 10, canCompact = true, ratio = 2.0))
    }

    @Test
    fun `compaction is preferred while it has budget and is making progress`() {
        assertEquals(InLoopStep.COMPACT, step(measured = 200_000, canCompact = true))
        assertEquals("even when the request would still fit", InLoopStep.COMPACT, step(measured = 110_000, canCompact = true))
    }

    @Test
    fun `above the compact line but inside the window is sent, not stopped`() {
        // The original wedge: the loop stopped here having made zero API calls.
        assertEquals(InLoopStep.SEND_WITHIN_WINDOW, step(measured = window - 1))
        assertEquals("the window itself does not fit", InLoopStep.STOP, step(measured = window))
    }

    @Test
    fun `an over-window verdict resting only on the ratio gets one real request`() {
        assertEquals(InLoopStep.SEND_UNCALIBRATED_ONCE, step(measured = 140_000, raw = 100_000, ratio = 1.4))
        assertEquals("already used → stop", InLoopStep.STOP, step(measured = 140_000, raw = 100_000, ratio = 1.4, used = true))
        assertEquals("ratio ≤ 1: the raw estimate IS the verdict", InLoopStep.STOP, step(measured = 140_000, raw = 140_000, ratio = 1.0))
        assertEquals("raw estimate over the window too → stop", InLoopStep.STOP, step(measured = 190_000, raw = window, ratio = 1.4))
    }

    @Test
    fun `an unknown window never stops a request`() {
        assertEquals(InLoopStep.SEND_WITHIN_WINDOW, step(measured = 900_000, window = 0))
    }

    @Test
    fun `property - with the valve unused, a request whose raw estimate fits is never stopped`() {
        for (raw in listOf(1, 60_000, 100_000, window - 1)) for (ratio in listOf(0.8, 1.0, 1.01, 1.3, 2.0, 3.0)) {
            val measured = ContextSizeMeter.calibrated(raw, ratio)
            assertNotEquals("raw=$raw ratio=$ratio", InLoopStep.STOP, step(measured = measured, raw = raw, ratio = ratio))
        }
    }

    // ── agent-loop model (production functions only) ────────────────────────

    /**
     * One session on one model. [realFactor] is how the provider actually
     * counts our raw estimate; [floor] is what a compaction can shrink the
     * history to (incompressible remainder). Mirrors the order of
     * inLoopContextCheck → dispatch → calibrateContextSize / noteContextOverflow.
     */
    private inner class LoopModel(
        var raw: Int,
        val realFactor: Double,
        var own: Double?,
        val borrowed: Double? = null,
        val floor: Int = raw,
        val rearmValveOnSuccess: Boolean = true,
        val rejectionSpendsValve: Boolean = true,
    ) {
        var sent = 0
        var rejected = 0
        var compactions = 0
        val ratio get() = ContextSizeMeter.ratioFor("m", own?.let { mapOf("m" to it) } ?: emptyMap(), borrowed)

        /** One agent loop of [iterations] tool rounds; returns how it ended. */
        fun loop(iterations: Int): String {
            var valveUsed = false
            var noProgress = false
            var compactionsThisLoop = 0
            var done = 0
            var guard = 0
            while (done < iterations) {
                if (++guard > 50) return "spinning"
                val measured = ContextSizeMeter.calibrated(raw, ratio)
                val s = ContextPolicy.inLoopStep(
                    policy.check(measured, window), measured, raw, window,
                    canCompact = compactionsThisLoop < 3 && !noProgress, ratio = ratio, uncalibratedSendUsed = valveUsed,
                )
                when (s) {
                    InLoopStep.STOP -> return "stopped after $done"
                    InLoopStep.COMPACT -> {
                        compactionsThisLoop++; compactions++
                        raw = maxOf(floor, raw / 3)
                        noProgress = ContextSizeMeter.calibrated(raw, ratio) >= measured
                    }
                    else -> {
                        if (s == InLoopStep.SEND_UNCALIBRATED_ONCE) valveUsed = true
                        val real = (raw * realFactor).toInt()
                        if (real >= window) {
                            rejected++
                            own = ContextSizeMeter.ratioAfterOverflow(ratio, raw, real, window)
                            if (rejectionSpendsValve) valveUsed = true
                            continue
                        }
                        sent++; done++
                        own = ContextSizeMeter.smoothed(own, ContextSizeMeter.calibrationRatio(real, raw)!!)
                        noProgress = false
                        if (rearmValveOnSuccess) valveUsed = false
                        raw += 500   // the tool round adds a little
                    }
                }
            }
            return "completed"
        }
    }

    @Test
    fun `a slowly falling ratio does not stop a loop the provider keeps accepting`() {
        // Own ratio learned on image-heavy turns; the content is now plain text
        // that counts 1:1, and the history cannot be compacted any further.
        val m = LoopModel(raw = 100_000, realFactor = 1.0, own = 1.8, floor = 100_000)
        assertEquals("completed", m.loop(iterations = 6))
        assertEquals(0, m.rejected)
        // Falsification: with the valve only once per loop (before
        // [T-ctx-valve-rearm]) the second iteration stopped, right after the
        // provider accepted a same-size request.
        val old = LoopModel(raw = 100_000, realFactor = 1.0, own = 1.8, floor = 100_000, rearmValveOnSuccess = false)
        assertEquals("stopped after 1", old.loop(iterations = 6))
    }

    @Test
    fun `an under-read costs at most one rejection, then compaction recovers`() {
        // Borrowed ratio from a sparser model; this one counts 1.4x.
        val m = LoopModel(raw = 95_000, realFactor = 1.4, own = null, borrowed = 1.0, floor = 20_000)
        assertEquals("completed", m.loop(iterations = 4))
        assertTrue("rejections=${m.rejected}", m.rejected <= 1)
        assertTrue("compacted after learning the real size", m.compactions >= 1)
    }

    @Test
    fun `a genuine overflow that compaction cannot fix stops instead of retrying forever`() {
        val m = LoopModel(raw = 120_000, realFactor = 1.3, own = 1.0, floor = 120_000)
        val end = m.loop(iterations = 3)
        assertTrue(end, end.startsWith("stopped"))
        assertEquals("rejected once, then the raised ratio is believed", 1, m.rejected)
        // Falsification: before [T-ctx-valve-rearm] the valve then re-sent the
        // very request the provider had just rejected.
        val old = LoopModel(raw = 120_000, realFactor = 1.3, own = 1.0, floor = 120_000, rejectionSpendsValve = false)
        old.loop(iterations = 3)
        assertEquals(2, old.rejected)
    }

    @Test
    fun `a single low outlier cannot drag the ratio under an overflow it just learned`() {
        val raised = ContextSizeMeter.ratioAfterOverflow(1.1, 100_000, 140_000, window)
        assertEquals(1.4, raised, 1e-9)
        val afterOutlier = ContextSizeMeter.smoothed(raised, 1.0)
        assertTrue("fell only part of the way ($afterOutlier)", afterOutlier > 1.25)
        assertEquals("the same request is still judged over the compact line",
            CheckResult.NEEDS_COMPACT, policy.check(ContextSizeMeter.calibrated(100_000, afterOutlier), window))
    }

    // ── overflow edges ─────────────────────────────────────────────────────

    @Test
    fun `overflow handling edge cases`() {
        assertEquals("no dispatch recorded (fresh reload) → unchanged", 1.2,
            ContextSizeMeter.ratioAfterOverflow(1.2, 0, 140_000, window), 1e-9)
        assertEquals("plausibility lower bound is inclusive (0.9x window)", 115_200.0 / 100_000,
            ContextSizeMeter.ratioAfterOverflow(1.0, 100_000, 115_200, window), 1e-9)
        assertEquals("below 0.9x window: ignored, just past the window", window * 1.02 / 100_000,
            ContextSizeMeter.ratioAfterOverflow(1.0, 100_000, 115_199, window), 1e-9)
        assertEquals("unknown window: the stated count is trusted", 2.0,
            ContextSizeMeter.ratioAfterOverflow(1.0, 100_000, 200_000, 0), 1e-9)
        assertEquals("capped at the calibration ceiling", ContextSizeMeter.CALIBRATION_MAX,
            ContextSizeMeter.ratioAfterOverflow(1.0, 10_000, 500_000, window), 1e-9)
    }

    // ── reload / revert: calibration replay and carry-over ─────────────────

    private fun sample(reported: Int, estimated: Int = 100_000, model: String? = "a", fixed: Int = 9_000) =
        CalibrationSample(reported, estimated, fixed, model)

    @Test
    fun `a reload reproduces the ratio the live path built, sample for sample`() {
        val samples = listOf(sample(140_000), sample(100_000), sample(120_000), sample(90_000), sample(150_000))
        var live: Double? = null
        for (s in samples) live = ContextSizeMeter.smoothed(live, ContextSizeMeter.calibrationRatio(s.reported, s.estimated)!!)
        val replayed = ContextSizeMeter.replayCalibration(samples)
        assertEquals(live!!, replayed.ratios.getValue("a"), 1e-12)
        assertEquals(5, replayed.samples)
    }

    @Test
    fun `replay is order-sensitive - rows must arrive in conversation order`() {
        val rows = listOf(sample(150_000), sample(100_000))
        val forward = ContextSizeMeter.replayCalibration(rows).ratios.getValue("a")
        val reversed = ContextSizeMeter.replayCalibration(rows.reversed()).ratios.getValue("a")
        assertEquals(1.5 + 0.3 * (1.0 - 1.5), forward, 1e-9)
        assertEquals(1.5, reversed, 1e-9)
        // So the DAO query feeding it must keep conversation order.
        val dao = ProductionSources.read("data/db/ChatDao.kt")
        assertTrue(dao.contains("AND token_usage IS NOT NULL ORDER BY sort_order ASC\")"))
    }

    @Test
    fun `each model is replayed from its own samples only`() {
        val state = ContextSizeMeter.replayCalibration(
            listOf(sample(100_000, model = "a"), sample(180_000, model = "b"), sample(90_000, model = "a")),
        )
        assertEquals(1.0 + 0.3 * (0.9 - 1.0), state.ratios.getValue("a"), 1e-9)
        assertEquals("b's first sample is taken as-is", 1.8, state.ratios.getValue("b"), 1e-9)
        assertEquals("lastLearned follows the newest row", state.ratios.getValue("a"), state.lastLearned!!, 1e-9)
    }

    @Test
    fun `rows without a usable pair are skipped, not zeroed`() {
        assertNull(ContextSizeMeter.calibrationSample("""{"latestContextTokens":120000}"""))
        assertNull(ContextSizeMeter.calibrationSample("""{"latestContextTokens":0,"estimatedRequestTokens":100}"""))
        assertNull(ContextSizeMeter.calibrationSample("not json"))
        val s = ContextSizeMeter.calibrationSample(
            """{"latestContextTokens":130000,"estimatedRequestTokens":100000,"estimatedFixedTokens":7000,"calibrationModelId":"gpt-5"}""",
        )!!
        assertEquals(CalibrationSample(130_000, 100_000, 7_000, "gpt-5"), s)
        assertNull("an empty model id is no model",
            ContextSizeMeter.calibrationSample("""{"latestContextTokens":1,"estimatedRequestTokens":1,"calibrationModelId":""}""")!!.modelId)
        assertEquals(CalibrationState(), ContextSizeMeter.replayCalibration(emptyList()))
    }

    @Test
    fun `revert keeps a ratio that exists only in memory`() {
        // Measured on device before the fix: a rejection raised the ratio
        // 1.29 → 1.97, revert reloaded the session, the reload reset it to 1.29
        // from the rows, and the retry went out uncompacted and was rejected.
        val rows = listOf(sample(129_000))
        val inMemory = CalibrationState(mapOf("a" to 1.97), lastLearned = 1.97, fixedTokens = 9_500)
        val reloaded = ContextSizeMeter.replayCalibration(rows).carryingOver(inMemory)
        assertEquals(1.97, reloaded.ratios.getValue("a"), 1e-9)
        assertEquals(1.97, reloaded.lastLearned!!, 1e-9)
        assertEquals(9_500, reloaded.fixedTokens)
        assertEquals("the retry after the revert compacts instead of being rejected again",
            CheckResult.NEEDS_COMPACT, policy.check(ContextSizeMeter.calibrated(100_000, reloaded.ratios.getValue("a")), window))
    }

    @Test
    fun `carry-over fills gaps from the rows and never erases with empties`() {
        val fromRows = ContextSizeMeter.replayCalibration(listOf(sample(120_000, model = "a"), sample(150_000, model = "b")))
        val merged = fromRows.carryingOver(CalibrationState(mapOf("a" to 1.6), lastLearned = null, fixedTokens = 0))
        assertEquals("memory wins for a", 1.6, merged.ratios.getValue("a"), 1e-9)
        assertEquals("rows fill in b", 1.5, merged.ratios.getValue("b"), 1e-9)
        assertEquals("a null lastLearned keeps the rows'", fromRows.lastLearned!!, merged.lastLearned!!, 1e-9)
        assertEquals("a zero fixed share keeps the rows'", 9_000, merged.fixedTokens)
    }

    // ── warm-up trim ───────────────────────────────────────────────────────

    @Test
    fun `warm-up trim never starts the kept slice inside a tool round`() {
        // user text, assistant tool_use, user tool_result, assistant, user text, assistant
        val starts = listOf(true, false, false, false, true, false)
        val sizes = listOf(100, 50, 20_000, 300, 100, 400)
        val drop = ContextSizeMeter.warmUpDrop(sizes, starts, restTokens = 5_000, budget = 10_000)
        assertEquals("the whole first round goes, tool result included", 4, drop)
        // Property: the slice starts on a turn, or is empty.
        val rnd = java.util.Random(7)
        repeat(500) {
            val n = 1 + rnd.nextInt(12)
            val st = List(n) { i -> i == 0 || rnd.nextInt(3) == 0 }
            val sz = List(n) { rnd.nextInt(5_000) }
            val d = ContextSizeMeter.warmUpDrop(sz, st, rnd.nextInt(5_000), rnd.nextInt(30_000))
            assertTrue("drop=$d starts=$st", d == 0 || d == n || st[d])
        }
    }

    @Test
    fun `warm-up trim treats reaching the budget as over it`() {
        val sizes = listOf(1_000, 1_000)
        val starts = listOf(true, true)
        assertEquals("exactly at the budget still trims", 1, ContextSizeMeter.warmUpDrop(sizes, starts, 0, 2_000))
        assertEquals("one under keeps everything", 0, ContextSizeMeter.warmUpDrop(sizes, starts, 0, 2_001))
    }

    // ── wiring: the view model uses these, and revert keeps its guards ─────

    @Test
    fun `view model routes its decisions through the tested functions`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertEquals("both no-compaction exits share one table", 2,
            vm.split("return settleWithoutCompacting(m, window, compactionsSoFar)").size - 1)
        assertTrue(vm.contains("val step = ContextPolicy.inLoopStep("))
        assertTrue(vm.contains("val state = learned?.let { seeded.carryingOver(it) } ?: seeded"))
        assertTrue("carry-over only within the same session",
            vm.contains("val learned = if (sid.isNotEmpty() && calibrationSessionId == sid) {"))
        assertTrue(vm.contains("val drop = ContextSizeMeter.warmUpDrop("))
        assertTrue("warm-up decided once per marker",
            vm.contains("if (trim.decided) warmUpDropByMarker[marker.id] = preAnchorPruned.size - trim.kept.size"))
        assertTrue("a rejection spends the valve", vm.contains("        sentPastExtrapolatedLimitThisLoop = true\n        AppLogger.warning("))
        assertTrue("an accepted request re-arms the valve",
            vm.contains("lastInLoopCompactionMadeNoProgress = false\n") &&
                vm.contains("                            sentPastExtrapolatedLimitThisLoop = false\n"))

        val revert = vm.substring(vm.indexOf("fun revertCompact() {"), vm.indexOf("fun revertCompact() {") + 3_000)
        assertTrue("no revert mid-stream", revert.contains("if (_isStreaming.value) {"))
        assertTrue("no revert mid-compaction", revert.contains("if (_isCompacting.value) {"))
        assertTrue("falls back to the previous marker, not to none",
            revert.contains("val next = chatRepository.dao.latestCompactMarker(sid)") && revert.contains("_cachedLatestMarker = next"))
        assertTrue("reloads through loadSession, which re-seeds the calibration", revert.contains("reloadSessionFromDb()"))
    }
}
