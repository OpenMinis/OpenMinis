package com.openminis.app.ui.chat

import com.openminis.app.data.ContextSizeMeter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android counterpart of iOS `ReviewFollowupEdgeTests` [1] and [2]
 * (iOS 08f3feea2; Android fix adb48fce3 / 454025e99).
 *
 *  [1] T-ctx-warmup-trim-undecided: a warm-up trim made with nothing to
 *      measure against (no window, no context policy, fixed size not seeded)
 *      must not be cached as the marker's answer, or a summary + warm-up
 *      over the line stays over it until the next compaction.
 *  [2] T-ctx-overflow-attribute-dispatch: a context rejection is scored
 *      against the model the request went to. Scored against the SELECTED
 *      model (different after a group fallback or a mid-turn switch), the
 *      wrong window fails the plausibility band and pins the wrong model's
 *      ratio at the 3.0 ceiling, while the model that overflowed is never
 *      corrected.
 *
 * Where iOS ports the logic into its standalone script, these run the real
 * Android functions ([warmUpBudget], [ContextSizeMeter.ratioAfterOverflow]);
 * the per-marker cache and the model/window choice are small ports of
 * ChatViewModel code, pinned to the source by the checks at the bottom.
 */
class ContextReviewFollowupParityTest {

    private val vm: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    // ── [1] warm-up trim cache ────────────────────────────────────────────

    private data class Env(val window: Int?, val compactThreshold: Int?, val fixed: Int)

    /** trimWarmUpToFit's decision, with the REAL budget function deciding "undecided". */
    private fun trim(warmUp: Int, overBy: Int, env: Env): Pair<Int, Boolean> {
        if (warmUp == 0) return warmUp to true
        warmUpBudget(env.window, env.compactThreshold, ratio = 1.0, fixedTokens = env.fixed)
            ?: return warmUp to false
        if (overBy <= 0) return warmUp to true
        return maxOf(0, warmUp - overBy) to true
    }

    /** Port of the per-marker cache in the request builder (warmUpDropByMarker). */
    private class Builder(val trim: (Int, Int, Env) -> Pair<Int, Boolean>) {
        val cache = mutableMapOf<String, Int>()
        fun build(marker: String, warmUp: Int, overBy: Int, env: Env): Int {
            cache[marker]?.let { return warmUp - minOf(it, warmUp) }
            val (kept, decided) = trim(warmUp, overBy, env)
            if (decided) cache[marker] = warmUp - kept
            return kept
        }
    }

    private val known = Env(window = 200_000, compactThreshold = 160_000, fixed = 4_000)

    @Test
    fun `an undecided warm-up trim is not cached, and the next request decides`() {
        val b = Builder(::trim)
        // Session load: window and policy known, fixed size not seeded yet.
        val first = b.build("M", warmUp = 6, overBy = 2, env = known.copy(fixed = 0))
        assertEquals("undecided pass keeps the warm-up as is", 6, first)
        assertNull("…and caches nothing", b.cache["M"])
        val second = b.build("M", warmUp = 6, overBy = 2, env = known)
        assertEquals("the next request decides for real and trims", 4, second)
        assertEquals("…and that decision is cached", 2, b.cache["M"])
        val third = b.build("M", warmUp = 6, overBy = 5, env = known)
        assertEquals("later requests reuse it (stable prefix for prompt caching)", 4, third)
    }

    @Test
    fun `no window or no context policy is undecided too, and a fitting warm-up is a real decision`() {
        val noWindow = Builder(::trim)
        noWindow.build("N", warmUp = 3, overBy = 1, env = known.copy(window = null))
        assertTrue("no window → nothing cached", noWindow.cache.isEmpty())
        val noPolicy = Builder(::trim)
        noPolicy.build("P", warmUp = 3, overBy = 1, env = known.copy(compactThreshold = null))
        assertTrue("no context policy (no entry) → nothing cached", noPolicy.cache.isEmpty())
        val fits = Builder(::trim)
        fits.build("F", warmUp = 3, overBy = 0, env = known)
        assertEquals("a warm-up that fits is a real decision (drop 0 cached)", 0, fits.cache["F"])
    }

    @Test
    fun `source - the trim reports decided and the builder caches only decisions`() {
        assertTrue(vm.contains(") ?: return WarmUpTrim(warmUp, decided = false)"))
        assertTrue(vm.contains("if (trim.decided) warmUpDropByMarker[marker.id] = preAnchorPruned.size - trim.kept.size"))
    }

    // ── [2] rejection attribution ─────────────────────────────────────────

    /** Port of noteContextOverflow's model / window choice (Android wording). */
    private fun attribution(
        dispatched: String?, dispatchedWindow: Int,
        selected: String?, selectedWindow: Int?,
    ): Pair<String?, Int> {
        val model = dispatched ?: selected
        val window = dispatchedWindow.takeIf { dispatched != null && it > 0 } ?: selectedWindow ?: 0
        return model to window
    }

    @Test
    fun `a rejection is scored against the model that received the request, with its window`() {
        // Group member A (128K) rejected the request; the selection already
        // names B (1M) after a fallback / a mid-turn switch.
        val (model, window) = attribution("A", 131_072, "B", 1_000_000)
        assertEquals("the rejected model is the one that received the request", "A", model)
        assertEquals("…with ITS window, not the selection's", 131_072, window)
        val (m2, w2) = attribution(null, 0, "B", 1_000_000)
        assertEquals("nothing dispatched yet → falls back to the selection", "B", m2)
        assertEquals(1_000_000, w2)
        val (_, w3) = attribution("A", 0, "B", 1_000_000)
        assertEquals("a dispatch with no known window uses the selection's", 1_000_000, w3)
    }

    @Test
    fun `scoring against the wrong window pins the wrong ratio at the ceiling`() {
        // The case the fix exists for, through the REAL ratio function: A has a
        // 128K window and rejected ~140K tokens; our estimate was 100K.
        val stated = ContextSizeMeter.requestedTokens("prompt is too long: 140,000 tokens > 131,072 maximum")
        assertEquals(140_000, stated)
        val right = ContextSizeMeter.ratioAfterOverflow(1.0, estimated = 100_000, requested = stated, window = 131_072)
        assertEquals("A's own window: the stated count is plausible, ratio 1.4", 1.4, right, 1e-9)
        val wrong = ContextSizeMeter.ratioAfterOverflow(1.0, estimated = 100_000, requested = stated, window = 1_000_000)
        assertEquals(
            "B's window: 140K is implausible for 1M, falls to window x 1.02 and is pinned at the ceiling",
            ContextSizeMeter.CALIBRATION_MAX, wrong, 1e-9,
        )
    }

    @Test
    fun `source - dispatch records the model and window, overflow prefers them, a new session clears them`() {
        val record = vm.substringAfter("private fun recordContextDispatch(").substringBefore("private fun calibrateContextSize(")
        assertTrue(record.contains("lastDispatchModel = model"))
        assertTrue(record.contains("lastDispatchWindow = resolvedContextWindow(model)?.first ?: 0"))
        assertTrue("the loop records the model that serves the request",
            vm.contains("recordContextDispatch(outboundHistory, currentProvider.model)"))
        val overflow = vm.substringAfter("private fun noteContextOverflow(").substringBefore("private fun seedContextCalibration(")
        assertTrue(overflow.contains("val model = lastDispatchModel?.id ?: currentModel?.id"))
        assertTrue(overflow.contains("val window = lastDispatchWindow.takeIf { lastDispatchModel != null && it > 0 }"))
        assertTrue("a new session's calibration seed clears the dispatch record",
            vm.contains("lastDispatchEstimate = 0\n        lastDispatchModel = null\n        lastDispatchWindow = 0"))
    }
}
