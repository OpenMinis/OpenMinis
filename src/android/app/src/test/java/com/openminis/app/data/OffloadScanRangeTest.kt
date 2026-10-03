package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-offload-scan-range] Port of iOS 5a9c5bfcd + d302de846: the
 * context offload scans only what is sent (after the compact-v2 anchor), and
 * ranks / accounts with ContextSizeMeter's calibrated estimate instead of the
 * vocab-less BPETokenizer (a flat chars/3).
 */
class OffloadScanRangeTest {

    private fun result(text: String) = AgentContentPart.ToolResult(id = "t", name = "shell_execute", content = text)

    @Test fun `CJK output is no longer under-ranked against English of fewer tokens`() {
        val cjk = result("日志内容".repeat(250))            // 1000 CJK chars ≈ 1000 tokens
        val english = result("abcd efgh ".repeat(200))    // 2000 chars ≈ 450 tokens
        // The old ranking (chars/3) put English first ...
        assertTrue(BPETokenizer.countTokens(english.content) > BPETokenizer.countTokens(cjk.content))
        // ... the meter's estimate puts the CJK part, the larger one, first.
        assertTrue(ContextSizeMeter.estimateTokens(cjk) > ContextSizeMeter.estimateTokens(english))
    }

    @Test fun `freed tokens are on the calibrated scale`() {
        val part = result("x".repeat(4500))
        val est = ContextSizeMeter.estimateTokens(part)
        assertTrue(ContextSizeMeter.calibrated(est, 2.0) >= est * 2)
    }

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    private fun offloadBody(): String {
        val start = vm.indexOf("private fun offloadContextIfNeeded(")
        val end = vm.indexOf("\n    }\n", start)
        return vm.substring(start, end)
    }

    @Test fun `drift guard - the scan starts after the compaction anchor`() {
        val body = offloadBody()
        assertTrue(body.contains("val scanStart = minOf(offloadScanStartIndex(), candidateUpper)"))
        assertTrue(body.contains("for (msgIdx in warmUpIndices + (scanStart until candidateUpper))"))
        assertTrue(body.contains("agentHistory.subList(answeredFrom, agentHistory.size)"))
        // [T-android-offload-warmup-scan] pruned warm-up parts are skipped
        assertTrue(body.contains("if (prunedId != null && prunedId in warmUp.second) continue"))
        assertFalse(body.contains("for (msgIdx in 0 until candidateUpper)"))
    }

    @Test fun `drift guard - start index degrades to 0 exactly like the effective history`() {
        val start = vm.indexOf("private fun offloadScanStartIndex(): Int {")
        val fn = vm.substring(start, vm.indexOf("\n    }\n", start))
        assertTrue(fn.contains("_compactSummary.value.isNullOrBlank()) return 0"))
        assertTrue(fn.contains("?: return 0"))
        assertTrue(fn.contains("marker.version < 2) return 0"))
        assertTrue(fn.contains("if (anchorIdx < 0) 0 else anchorIdx + 1"))
    }

    @Test fun `drift guard - ranking and accounting use the calibrated meter, not BPE`() {
        val body = offloadBody()
        assertFalse(body.contains("countPartTokens("))
        assertTrue(body.contains("ContextSizeMeter.estimateTokens(part)"))
        assertTrue(body.contains("ContextSizeMeter.calibrated(candidate.tokens, calibrationRatio)"))
        assertTrue(body.contains("currentTokens -= candidateTokens"))
    }
}
