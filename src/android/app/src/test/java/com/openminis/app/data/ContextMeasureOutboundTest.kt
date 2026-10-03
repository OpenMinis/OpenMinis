package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-measure-outbound] Capacity decisions judge the request that is about
 * to be sent, not the provider's count for the previous one. Mirrors iOS
 * ContextMeasureOutboundTests.swift.
 *
 * The Android form of the bug: both guards read `_lastTurnContextTokens`,
 * which only a usage chunk ever replaced. The in-loop path had a one-shot
 * "stale" flag; the send-time and manual compactions did not, so the first
 * check of the next turn compacted AGAIN on the pre-compaction number — an
 * extra summary that folded the user's just-sent message in. After a revert it
 * kept the compacted context's count and under-read the restored history.
 *
 * [ContextSizeMeter] is pure and exercised directly. The ViewModel wiring needs
 * a live ViewModel, so the flow is modelled on its call order and the
 * load-bearing lines are pinned by source greps at the bottom.
 */
class ContextMeasureOutboundTest {

    // ── the estimator ──────────────────────────────────────────────────────

    @Test
    fun `CJK is read at its real size, not a third of it`() {
        // 64 characters; cl100k_base counts 61 tokens (tiktoken, measured).
        val zh = "我们需要排查自动压缩逻辑，如果会话消息触发自动压缩阈值并自动压缩后继续发消息，此时会更新之前成功对话消息的 usage 数据吗？"
        val real = 61
        val est = ContextSizeMeter.estimateTokens(zh)
        val ratio = est.toDouble() / real
        assertTrue("new estimate $est should be within 0.66–1.18x of $real", ratio in 0.66..1.18)
        val old = (zh.length / 3.5).toInt()
        assertTrue("OLD chars/3.5 read it at under a third ($old of $real)", old.toDouble() / real < 0.34)
        assertEquals(0, ContextSizeMeter.estimateTokens(""))
        assertTrue(ContextSizeMeter.estimateTokens("1234567890") > ContextSizeMeter.estimateTokens("abcdefghij"))
    }

    @Test
    fun `surrogate pairs count once, as iOS unicodeScalars do`() {
        // An emoji is one scalar (two UTF-16 units): must not be double-counted.
        assertEquals(1, ContextSizeMeter.estimateTokens("😀"))
    }

    @Test
    fun `a message is measured the way providers serialise it`() {
        val input = JSONObject().put("path", "/a")
        val parts = LLMMessage(
            LLMMessage.Role.ASSISTANT, content = "ignored when parts exist",
            contentParts = listOf(
                AgentContentPart.Text("hello"),
                AgentContentPart.ToolUse("t1", "file_read", input),
            ),
        )
        val expected = ContextSizeMeter.PER_MESSAGE_OVERHEAD +
            ContextSizeMeter.estimateTokens("hello") +
            ContextSizeMeter.estimateTokens("file_read") + ContextSizeMeter.estimateTokens(input.toString()) +
            ContextSizeMeter.PER_TOOL_PART_OVERHEAD
        assertEquals(expected, ContextSizeMeter.estimateTokens(parts))

        val plain = LLMMessage(LLMMessage.Role.USER, content = "just text")
        assertEquals(ContextSizeMeter.PER_MESSAGE_OVERHEAD + ContextSizeMeter.estimateTokens("just text"),
            ContextSizeMeter.estimateTokens(plain))
    }

    @Test
    fun `system prompt and tool schemas are part of the measurement`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "shell_execute",
                description = "Run a shell command",
                parameters = mapOf("command" to AgentToolParam("string", "The command")),
            ),
        )
        val withTools = ContextSizeMeter.estimateFixedTokens("You are Minis.", tools)
        val promptOnly = ContextSizeMeter.estimateFixedTokens("You are Minis.", emptyList())
        assertTrue("tool schemas add to the fixed share", withTools > promptOnly)
        assertEquals(0, ContextSizeMeter.estimateFixedTokens(null, emptyList()))
    }

    // ── calibration ────────────────────────────────────────────────────────

    @Test
    fun `calibration pairs a report with the estimate of the same request`() {
        assertEquals(1.4, ContextSizeMeter.calibrationRatio(140, 100)!!, 1e-9)
        assertEquals("absurd reports are clamped high", 3.0, ContextSizeMeter.calibrationRatio(1_000_000, 100)!!, 1e-9)
        assertEquals("an under-reporting upstream cannot shrink the estimate by more than 20%",
            0.8, ContextSizeMeter.calibrationRatio(1, 100)!!, 1e-9)
        assertEquals("a genuine over-read (the estimator's worst, 1.18x) is still followed",
            100.0 / 118.0, ContextSizeMeter.calibrationRatio(100, 118)!!, 1e-9)
        assertNull(ContextSizeMeter.calibrationRatio(0, 100))
        assertNull(ContextSizeMeter.calibrationRatio(100, 0))
        assertEquals("with nothing changed the measurement reproduces the report",
            138_800, ContextSizeMeter.calibrated(100_000, ContextSizeMeter.calibrationRatio(138_800, 100_000)!!))
    }

    // ── the flow ───────────────────────────────────────────────────────────
    //
    // Sizes are what the MEASUREMENT sees; the "provider" reports
    // estimate × providerFactor for whatever is actually sent.

    private val window = 128_000
    private val policy = ContextPolicy.forContextWindow(window)
    private val providerFactor = 1.4
    private val fixed = 9_000

    private inner class Session(var history: MutableList<Int>) {
        var saved: List<Int> = emptyList()
        var ratio = 1.0
        var apiCalls = 0
        var compactions = 0
        var stampedReport = 0
        var stampedEstimate = 0
        val estimate get() = history.sum() + fixed
        val measured get() = ContextSizeMeter.calibrated(estimate, ratio)

        fun dispatch() {
            val est = estimate
            apiCalls++
            val report = (est * providerFactor).toInt()
            ContextSizeMeter.calibrationRatio(report, est)?.let { ratio = it }
            stampedReport = report
            stampedEstimate = est
        }
        fun compact() {
            saved = history.toList()
            history = (listOf(3_000) + history.takeLast(2)).toMutableList()
            compactions++
        }
        fun revert() { history = (saved + history.drop(3)).toMutableList() }
        fun reload() { ratio = ContextSizeMeter.calibrationRatio(stampedReport, stampedEstimate) ?: 1.0 }

        /** inLoopContextCheck as fixed. */
        fun runTurn(): String {
            var noProgress = false
            while (true) {
                val tokens = measured
                when (policy.check(tokens, window)) {
                    ContextPolicy.CheckResult.OK -> { dispatch(); return "sent" }
                    else -> {
                        if (compactions >= 3 || noProgress) {
                            return if (tokens < window) { dispatch(); "sent" } else "exhausted"
                        }
                        compact()
                        noProgress = measured >= tokens
                    }
                }
            }
        }
    }

    private fun bigSession() = Session((List(13) { 5_500 } + 1_000).toMutableList())

    @Test
    fun `compact then send - the next request goes out with no re-compaction`() {
        val s = bigSession()
        s.dispatch()
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(s.measured, window))
        val staleReport = s.stampedReport
        s.compact()                                    // compactAndSendPending
        val calls = s.apiCalls
        assertEquals("sent", s.runTurn())
        assertEquals("exactly one API call", 1, s.apiCalls - calls)
        assertEquals("no in-loop re-compaction", 1, s.compactions)

        // Falsification: the old guard read the stale report, with no stale
        // flag set by a send-time compaction.
        assertEquals("OLD: the first in-loop check compacted again",
            ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(staleReport, window))
    }

    @Test
    fun `reopening a compacted session re-seeds the ratio, not the stale size`() {
        val s = bigSession()
        s.dispatch()
        s.compact()
        s.reload()
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(s.measured, window))
        assertEquals(providerFactor, s.ratio, 0.01)
    }

    @Test
    fun `revert restores the full size instead of keeping the compacted count`() {
        val s = bigSession()
        s.dispatch()
        s.compact()
        s.history.addAll(listOf(2_000, 2_000)); s.dispatch()   // turns on the compacted context
        val compactedReport = s.stampedReport
        s.revert(); s.reload()                                   // revertCompact → loadSession
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(s.measured, window))
        assertEquals("OLD: the compacted context's report under-read it",
            ContextPolicy.CheckResult.OK, policy.check(compactedReport, window))
    }

    @Test
    fun `offload is seen by the very next decision`() {
        val s = bigSession()
        s.dispatch()
        val staleReport = s.stampedReport
        for (i in 0 until 6) s.history[i] = 120               // stubs replace tool results in place
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(s.measured, window))
        assertEquals("OLD: the pre-offload report still forced a compaction",
            ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(staleReport, window))
    }

    @Test
    fun `a compaction that cannot shrink the request is not retried`() {
        val within = Session(mutableListOf(3_000, 60_000, 30_000)).apply { ratio = 1.1 }
        assertEquals("above threshold but within the window → sent", "sent", within.runTurn())
        assertEquals(1, within.compactions)
        val over = Session(mutableListOf(3_000, 90_000, 40_000)).apply { ratio = 1.1 }
        assertEquals("exhausted", over.runTurn())
        assertEquals(1, over.compactions)
    }

    // ── persistence ────────────────────────────────────────────────────────

    @Test
    fun `usage rows carry the pair and a reload replays them in order`() {
        fun seed(rows: List<String>) = ContextSizeMeter.replayCalibration(rows.mapNotNull(ContextSizeMeter::calibrationSample))
        val legacy = """{"inputTokens":10,"outputTokens":5,"cacheCreationTokens":0,"cacheReadTokens":0,"latestContextTokens":120000}"""
        assertEquals("rows written before the pair do not seed", 0, seed(listOf(legacy)).samples)
        val older = """{"latestContextTokens":100000,"estimatedRequestTokens":100000,"estimatedFixedTokens":8000}"""
        val newer = """{"latestContextTokens":140000,"estimatedRequestTokens":100000,"estimatedFixedTokens":9000,"streamMs":12}"""
        val state = seed(listOf(older, newer, legacy))
        assertEquals(2, state.samples)
        assertEquals("model-less rows set only the newest-learned ratio", 1.4, state.lastLearned!!, 1e-9)
        assertEquals(9_000, state.fixedTokens)
    }

    // ── optimisation round: per-model ratios, rejections, valve, cache ──────

    @Test
    fun `a model is judged by its own ratio, or a margin over a borrowed one`() {
        val known = mapOf("gpt-5" to 1.05, "claude-5" to 1.30)
        assertEquals(1.30, ContextSizeMeter.ratioFor("claude-5", known, 1.05), 1e-9)
        assertEquals("switching back uses that model's own ratio", 1.05, ContextSizeMeter.ratioFor("gpt-5", known, 1.30), 1e-9)
        assertEquals("unseen model: borrowed with the margin", 1.05 * 1.2, ContextSizeMeter.ratioFor("gemini-4", known, 1.05), 1e-9)
        assertEquals("a borrowed ratio below 1 does not shrink", 1.2, ContextSizeMeter.ratioFor("x", emptyMap(), 0.85), 1e-9)
        assertEquals("ceiling respected", 3.0, ContextSizeMeter.ratioFor("x", emptyMap(), 2.9), 1e-9)
        assertEquals("no usage yet", 1.0, ContextSizeMeter.ratioFor("x", emptyMap(), null), 1e-9)
    }

    @Test
    fun `scenario 7 - switching to a denser tokenizer compacts first instead of overflowing`() {
        val estimate = 95_000
        val realOnB = (estimate * 1.36).toInt()
        assertTrue("B's real count is over its window", realOnB > window)
        val oldJudged = (estimate * 1.05).toInt()      // A's count carried over
        assertEquals("OLD: judged ok and sent over B's window", ContextPolicy.CheckResult.OK, policy.check(oldJudged, window))
        val newJudged = ContextSizeMeter.calibrated(estimate, ContextSizeMeter.ratioFor("B", mapOf("A" to 1.05), 1.05))
        assertEquals("NEW: the margin compacts first", ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(newJudged, window))
    }

    @Test
    fun `a context-length rejection raises the ratio from the provider's own count`() {
        val openai = "[400] This model's maximum context length is 128000 tokens. However, your messages resulted in 131,244 tokens."
        val anthropic = "prompt is too long: 205000 tokens > 200000 maximum"
        assertEquals(131_244, ContextSizeMeter.requestedTokens(openai))
        assertEquals(205_000, ContextSizeMeter.requestedTokens(anthropic))
        assertNull(ContextSizeMeter.requestedTokens("Your input exceeds the context window of this model"))

        val raised = ContextSizeMeter.ratioAfterOverflow(1.05, 100_000, 131_244, 128_000)
        assertEquals(1.31244, raised, 1e-9)
        assertEquals("that request now measures over the compact line",
            ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(ContextSizeMeter.calibrated(100_000, raised), window))
        assertEquals("no count stated: just past the window", 1.3056,
            ContextSizeMeter.ratioAfterOverflow(1.05, 100_000, null, 128_000), 1e-9)
        assertEquals("never lowered", 1.5, ContextSizeMeter.ratioAfterOverflow(1.5, 100_000, 120_000, 128_000), 1e-9)
        assertEquals("an implausible number (request id) is ignored", 1.3056,
            ContextSizeMeter.ratioAfterOverflow(1.0, 100_000, 123_456_789, 128_000), 1e-9)
    }

    @Test
    fun `an offload that rewrites a persisted message in place is re-measured`() {
        val big = "x".repeat(40_000)
        val before = LLMMessage(LLMMessage.Role.USER, content = "", dbMessageId = "m1",
            contentParts = listOf(AgentContentPart.ToolResult("t1", "shell_execute", big)))
        val after = before.copy(contentParts = listOf(AgentContentPart.ToolResult("t1", "shell_execute", "[offloaded] saved to /var/minis/offloads/a")))
        val sizeBefore = ContextSizeMeter.estimateTokens(listOf(before))
        assertEquals("cache hit is stable", sizeBefore, ContextSizeMeter.estimateTokens(listOf(before)))
        val sizeAfter = ContextSizeMeter.estimateTokens(listOf(after))
        assertTrue("same id, new content shape → not the cached size ($sizeAfter vs $sizeBefore)", sizeAfter < sizeBefore / 10)
    }

    @Test
    fun `warm-up is trimmed only when it keeps the request over the line`() {
        val sizes = listOf(10, 9_000, 10, 9_000, 10, 300, 200)
        val starts = listOf(true, false, true, false, true, false, false)
        assertEquals("under budget → untouched", 0, ContextSizeMeter.warmUpDrop(sizes, starts, 500, 40_000))
        val drop = ContextSizeMeter.warmUpDrop(sizes, starts, 500, 12_000)
        assertEquals("the oldest turn is dropped, and no more than needed", 2, drop)
        assertTrue("kept slice starts on a user-text turn", starts[drop])
        assertEquals("nothing fits → warm-up dropped entirely", sizes.size, ContextSizeMeter.warmUpDrop(sizes, starts, 500, 100))
    }

    // ── drift guard ────────────────────────────────────────────────────────

    @Test
    fun `drift guard - every size decision reads the outbound measurement`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val meter = ProductionSources.read("data/ContextPolicy.kt")
        assertTrue(meter.contains("val tokens = letters / 4.5 + digits / 2.0 + asciiOther * 0.35 + nonAscii"))
        assertTrue(meter.contains("const val CALIBRATION_MIN = 0.8"))
        assertTrue(meter.contains("const val CALIBRATION_MAX = 3.0"))

        // Both guards judge the measurement; the stale flag is gone.
        assertEquals(2, vm.split("val m = contextMeasurement()\n        val tokens = m.measured").size - 1)
        assertFalse(vm.contains("private var lastTurnContextTokensStale"))
        assertTrue(vm.contains("val history = ContextSizeMeter.estimateTokens(effectiveAgentHistoryUncounted())"))
        assertTrue(vm.contains("ContextSizeMeter.calibrated(history + contextFixedTokens, ratio)"))

        // In-loop: progress-gated retry; everything else goes through the
        // shared decision table (CompactionDecisionTest).
        assertTrue(vm.contains("lastInLoopCompactionMadeNoProgress = after >= tokens"))
        assertTrue(vm.contains("val canCompact = compactionsSoFar < maxInLoopCompactions && !lastInLoopCompactionMadeNoProgress"))

        // Dispatch: estimate recorded on the exact pre-budget history, and it sizes max_tokens.
        val loop = vm.substring(vm.indexOf("suspend fun runAgentLoop"))
        val record = loop.indexOf("val dispatchInputTokens = recordContextDispatch(outboundHistory, currentProvider.model)")
        val send = loop.indexOf("currentProvider.streamMessage(")
        assertTrue("dispatch recorded before the provider call", record in 1 until send)
        // [T-scheduled-tool-prefill] One level deeper: the provider request now
        // sits in the non-scripted branch of the turn's chunk source.
        assertTrue(loop.contains("applyRequestImageBudget(\n                                    outboundHistory,"))
        assertTrue(loop.contains("dynamicMaxTokens(currentProvider, dispatchInputTokens)"))
        assertTrue(loop.contains("lastContextTokens = measureOutboundContextTokens(),"))
        assertTrue(loop.contains("contextFixedTokens = ContextSizeMeter.estimateFixedTokens("))

        // Calibration on arrival, persisted under the iOS key names, seeded on load.
        assertTrue(vm.contains("calibrateContextSize(lastContextTokens)"))
        assertTrue(vm.contains("\"\"\",\"estimatedRequestTokens\":\$est,\"estimatedFixedTokens\":\$fixed\$modelField\"\"\""))
        assertTrue("model id travels with the pair", vm.contains("\"calibrationModelId\":"))
        assertEquals("both usage-row writers carry the pair",
            2, vm.split("\${calibrationJsonFields()}}\"\"\"").size - 1)
        assertTrue(vm.contains("seedContextCalibration(chatRepository.sessionTokenUsages(realSessionId.ifEmpty { sessionId }))"))
        // Seeding replays the rows through the smoothing in order, so they must
        // come back in conversation order, not storage order.
        val dao = ProductionSources.read("data/db/ChatDao.kt")
        assertTrue(dao.contains("AND token_usage IS NOT NULL ORDER BY sort_order ASC\")"))

        // Optimisation round.
        assertTrue(vm.contains("private val contextCalibrationRatios = mutableMapOf<String, Double>()"))
        assertTrue("every context-length rejection raises the ratio", vm.contains("noteContextOverflow(overflowErr?.detail ?: \"\")"))
        assertTrue("valve is once per loop", vm.contains("uncalibratedSendUsed = sentPastExtrapolatedLimitThisLoop,")
            && vm.contains("sentPastExtrapolatedLimitThisLoop = false"))
        assertTrue("seeding replays the rows through the shared replay",
            vm.contains("ContextSizeMeter.replayCalibration(usageJsons.mapNotNull(ContextSizeMeter::calibrationSample))"))
        assertTrue(meter.contains("val id = message.dbMessageId ?: return estimateTokens(message)"))

        // A pinned model's overflow is recovered by compaction, never by
        // answering from another model (parity with iOS).
        assertTrue(vm.contains("val shouldFallback = !isDirectEntryOverflow && (isRateLimit || is5xx || isFallbackableError ||"))

        // Same-session reload keeps learned (incl. overflow-raised) ratios.
        // (Behaviour: CompactionDecisionTest `revert keeps a ratio that exists only in memory`.)
        assertTrue(vm.contains("val learned = if (sid.isNotEmpty() && calibrationSessionId == sid) {"))
        assertTrue(vm.contains("val state = learned?.let { seeded.carryingOver(it) } ?: seeded"))

        // Warm-up trimming.
        assertTrue(vm.contains("} ?: trimWarmUpToFit(preAnchorPruned, postAnchor, summaryWrappedText).let { trim ->"))
        assertTrue(vm.contains("result.addAll(warmUp)"))
        assertTrue("warm-up trim is decided once per marker (stable prompt-cache prefix)",
            vm.contains("val warmUp = warmUpDropByMarker[marker.id]?.let { drop ->")
                && vm.contains("if (trim.decided) warmUpDropByMarker[marker.id] = preAnchorPruned.size - trim.kept.size"))
        assertTrue(vm.contains("if (ContextSizeMeter.estimateTokens(warmUp) + restTokens < budget) return WarmUpTrim(warmUp, decided = true)"))

        // The glow follows the measurement after a compaction and on (re)load.
        assertTrue(vm.contains("if (compactSucceeded) runCatching {\n                    publishMeasuredContextUsage()"))
        assertTrue(vm.contains("runCatching { publishMeasuredContextUsage() }"))
    }

    // [T-ctx-ratio-smoothing] A rise applies at once (under-counting is what
    // overflows); a fall moves 30% of the way, so one cache-heavy or
    // image-light turn cannot drag the ratio down by itself.
    @Test
    fun calibrationRatioSmoothingIsAsymmetric() {
        assertEquals(1.4, ContextSizeMeter.smoothed(null, 1.4), 1e-9)
        assertEquals(1.6, ContextSizeMeter.smoothed(1.2, 1.6), 1e-9)
        assertEquals(1.4 + 0.3 * (1.0 - 1.4), ContextSizeMeter.smoothed(1.4, 1.0), 1e-9)
        // A sustained fall still converges: five low samples close most of the gap.
        var r = 1.8
        repeat(5) { r = ContextSizeMeter.smoothed(r, 1.0) }
        assertTrue("converges toward the new level (r=$r)", r < 1.15 && r > 1.0)
        // One outlier among steady samples costs a fraction, not the whole jump.
        assertEquals(1.33, ContextSizeMeter.smoothed(1.36, 1.26), 1e-9)
    }

    @Test
    fun ctxMeterLogsCoverEveryStage() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        for (tag in listOf("[CtxMeter] decide site=", "[CtxMeter] dispatch model=", "[CtxMeter] actual model=",
            "[CtxMeter] rejected", "[CtxMeter] seed session=", "[CtxMeter] warmup trimmed",
            "[CtxMeter] compacted marker=", "[CtxMeter] reverted marker=")) {
            assertTrue("missing log $tag", vm.contains(tag))
        }
        assertTrue(vm.contains("logContextDecision(\"pre-send\", m, policy.compactThreshold, window, verdict.name)"))
        assertTrue(vm.contains("logContextDecision(\"in-loop\", m, policy.compactThreshold, window, verdict.name)"))
        assertTrue("revert log waits for the reload's measurement", vm.contains("pendingRevertLogMarker = current.id.take(8)"))
    }
}
