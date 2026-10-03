package com.openminis.app.data

import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-runaway][T-compact-idle-timeout] The ceilings that bound a
 * compaction run.
 *
 * Compaction originally had no ceiling of its own: its only bound was each
 * provider's 10-minute OkHttp readTimeout, and the split-retry path could issue
 * 1+2+4+8 = 15 sequential leaf calls before depth 3 stopped it, so slow calls
 * accumulated into the ~20-minute hang users reported.
 *
 * The fix for that — a total elapsed-time budget — then caused the opposite
 * failure, because total elapsed time cannot tell a stuck run from a long one.
 * A healthy stream was cancelled at 150s mid-flight after 7,488 events. The
 * budget is now an IDLE timer (time since the last chunk), which measures the
 * thing that actually distinguishes the two, plus a loose total backstop for
 * runaway output. These pin that arithmetic.
 */
class CompactBudgetTest {

    @Test
    fun `idle timeout is the working limit and total is only a backstop`() {
        // [T-compact-idle-timeout] The ordering IS the design: whatever goes
        // wrong, the idle timer should be what notices, because it is the only
        // one of the two that distinguishes "stuck" from "long". If these ever
        // invert, a slow-but-healthy compaction gets killed by the ceiling —
        // exactly the bug this replaced.
        assertTrue(
            "idle timeout must fire long before the total ceiling",
            ChatViewModel.COMPACT_IDLE_TIMEOUT_MS < ChatViewModel.COMPACT_MAX_TOTAL_MS,
        )
    }

    @Test
    fun `idle timeout lands before the providers' socket read timeout`() {
        // Providers use a 10-minute OkHttp readTimeout, which is itself an idle
        // timer. Ours must fire first, or a dead stream surfaces as a raw
        // socket error with no message instead of our own "stalled" path.
        val providerReadTimeoutMs = 10 * 60 * 1000L
        assertTrue(
            "idle timeout (${ChatViewModel.COMPACT_IDLE_TIMEOUT_MS}ms) must be under " +
                "the provider read timeout (${providerReadTimeoutMs}ms)",
            ChatViewModel.COMPACT_IDLE_TIMEOUT_MS < providerReadTimeoutMs,
        )
    }

    @Test
    fun `the reported regression would now succeed`() {
        // The run from /tmp/minis-compact-timeout-2026-09-02.log: cancelled at
        // 150s having received 7,488 SSE events and 19,459 characters — data
        // arriving continuously, gaps between chunks in the millisecond range.
        val reportedRunMs = 150_179L
        val largestObservedGapMs = 2_400L // the widest inter-event gap in that log
        assertTrue(
            "a continuously-streaming run must not trip the idle timer",
            largestObservedGapMs < ChatViewModel.COMPACT_IDLE_TIMEOUT_MS,
        )
        assertTrue(
            "and it must finish well inside the total ceiling",
            reportedRunMs < ChatViewModel.COMPACT_MAX_TOTAL_MS,
        )
    }

    @Test
    fun `total ceiling is the 15 minute backstop`() {
        assertEquals(15 * 60 * 1000L, ChatViewModel.COMPACT_MAX_TOTAL_MS)
    }

    @Test
    fun `a silent stream is not retried by splitting`() {
        // Splitting exists for over-length payloads. A stream that went quiet
        // was already accepted and streaming, so halving it just issues two
        // more calls into the same broken pipe and burns the call budget.
        assertTrue(
            "idle timeout must not trigger the split retry",
            !ChatViewModel.shouldSplitOnError(
                ChatViewModel.Companion.CompactIdleTimeoutException("no data for 120s")
            ),
        )
    }

    @Test
    fun `call budget is well under what the depth cap alone permits`() {
        // depth<3 allows 1+2+4+8 = 15 leaf calls. The budget is what actually
        // bounds wall-clock when each call is slow rather than failing fast.
        val depthCapWorstCase = 1 + 2 + 4 + 8
        assertTrue(
            "call budget must materially cut the depth-cap worst case",
            ChatViewModel.MAX_COMPACT_LLM_CALLS < depthCapWorstCase,
        )
        // Still enough for a full first split (1 + 2) plus a deeper rescue,
        // so the mechanism that exists to save an over-length compaction
        // is not budgeted out of existence.
        assertTrue(
            "budget must still allow a first split plus a rescue",
            ChatViewModel.MAX_COMPACT_LLM_CALLS >= 5,
        )
    }

    @Test
    fun `worst case is still bounded, just generously`() {
        // [T-compact-idle-timeout] This assertion used to read "under 6
        // minutes", which was the runaway fix over-correcting: it bounded the
        // pathological case by also bounding the legitimate one. The ceiling is
        // now deliberately loose because the idle timer — not this — is what
        // catches a stuck run. What still matters is that SOME finite bound
        // exists, so a looping model cannot hold the compact lock forever.
        val worstMs = ChatViewModel.COMPACT_MAX_TOTAL_MS
        assertTrue("must stay bounded", worstMs <= 20 * 60 * 1000L)
    }
}
