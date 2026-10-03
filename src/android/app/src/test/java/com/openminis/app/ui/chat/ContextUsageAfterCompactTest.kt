package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-usage-after-compact] After a manual or automatic compaction, the
 * composer placeholder kept showing the pre-compaction "Context NN% used"
 * line. The composer holds the last presented line until typing and ignores
 * a null hint, so only a NEW generation replaces it, and compaction never
 * issued one. The glow was already fine: [publishMeasuredContextUsage] sets
 * `_lastTurnContextTokens`, from which `contextUsage` is derived.
 *
 * Source guards on the wiring, plus the tier/percent the new line reports.
 * Mirrors iOS ContextUsageAfterCompactTests.
 */
class ContextUsageAfterCompactTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    private val announceBody: String by lazy {
        val start = vm.indexOf("private fun announceContextUsageAfterCompaction()")
        assertTrue("announceContextUsageAfterCompaction not found", start >= 0)
        val end = vm.indexOf("\n    }\n", start)
        vm.substring(start, end)
    }

    @Test
    fun `a successful compaction measures, then announces`() {
        val tail = vm.substringAfter("if (compactSucceeded) runCatching {")
            .substringBefore("onFinished?.invoke(compactSucceeded)")
        val measure = tail.indexOf("publishMeasuredContextUsage()")
        val announce = tail.indexOf("announceContextUsageAfterCompaction()")
        assertTrue("measure before announce", measure in 0 until announce)
    }

    @Test
    fun `the announcement is a new generation built from the measured size`() {
        assertTrue(announceBody.contains("usedTokens = _lastTurnContextTokens.value"))
        assertTrue(announceBody.contains("contextHintGeneration += 1"))
        assertTrue(announceBody.contains("_contextUsageHint.value = ContextUsageHint("))
    }

    @Test
    fun `it is shown at any tier, unlike the turn-end line`() {
        // publishContextUsage returns early at NORMAL; the post-compaction line
        // must not, or a drop to 20% would leave the stale 85% line on screen.
        assertFalse(announceBody.contains("Tier.NORMAL) return"))
    }

    @Test
    fun `it resets the tier baseline without markFired`() {
        assertTrue(announceBody.contains("contextTierTracker.observe(usage.tier, System.currentTimeMillis())"))
        assertFalse(announceBody.contains("markFired("))
    }

    @Test
    fun `helper view models stay silent`() {
        assertTrue(announceBody.contains("if (isHelper) return"))
    }

    @Test
    fun `the reported figure is the post-compaction size`() {
        val before = ContextUsage.from(usedTokens = 170_000, windowTokens = 200_000)!!
        val after = ContextUsage.from(usedTokens = 40_000, windowTokens = 200_000)!!
        assertEquals(ContextUsage.Tier.CRITICAL, before.tier)
        assertEquals(ContextUsage.Tier.NORMAL, after.tier)
        assertEquals(20, after.percent)
    }
}
