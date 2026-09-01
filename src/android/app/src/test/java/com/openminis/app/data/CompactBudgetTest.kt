package com.openminis.app.data

import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-runaway] The ceilings that bound a compaction run.
 *
 * Compaction previously had no time or call ceiling of its own: its only bound
 * was each provider's 10-minute OkHttp readTimeout, and the split-retry path
 * could issue 1+2+4+8 = 15 sequential leaf calls before depth 3 stopped it.
 * Slow-but-not-failing calls therefore accumulated into the ~20-minute apparent
 * hang users reported. These pin the arithmetic of the replacement budgets.
 */
class CompactBudgetTest {

    @Test
    fun `short transcript gets the base timeout`() {
        assertEquals(
            ChatViewModel.COMPACT_TIMEOUT_BASE_MS,
            ChatViewModel.compactTimeoutMsFor(0),
        )
        assertEquals(
            ChatViewModel.COMPACT_TIMEOUT_BASE_MS,
            ChatViewModel.compactTimeoutMsFor(9_999),
        )
    }

    @Test
    fun `timeout grows with transcript length`() {
        // The point of a dynamic budget: a long first compaction must not be
        // cut off by a limit sized for a short one.
        val short = ChatViewModel.compactTimeoutMsFor(5_000)
        val medium = ChatViewModel.compactTimeoutMsFor(50_000)
        val long = ChatViewModel.compactTimeoutMsFor(120_000)
        assertTrue("longer transcript must get more time", medium > short)
        assertTrue("longer transcript must get more time", long > medium)
    }

    @Test
    fun `growth is one step per 10k characters`() {
        val base = ChatViewModel.COMPACT_TIMEOUT_BASE_MS
        val step = ChatViewModel.COMPACT_TIMEOUT_PER_10K_CHARS_MS
        assertEquals(base + step, ChatViewModel.compactTimeoutMsFor(10_000))
        assertEquals(base + 3 * step, ChatViewModel.compactTimeoutMsFor(35_000))
    }

    @Test
    fun `timeout is capped so nothing can hang indefinitely`() {
        val huge = ChatViewModel.compactTimeoutMsFor(10_000_000)
        assertEquals(ChatViewModel.COMPACT_TIMEOUT_MAX_MS, huge)
    }

    @Test
    fun `cap stays below the providers' read timeout`() {
        // Providers use a 10-minute readTimeout. Our ceiling must land first,
        // or the run ends on a socket timeout with no message instead of our
        // own "timed out, try again" path.
        val providerReadTimeoutMs = 10 * 60 * 1000L
        assertTrue(
            "compact cap (${ChatViewModel.COMPACT_TIMEOUT_MAX_MS}ms) must be under " +
                "the provider read timeout (${providerReadTimeoutMs}ms)",
            ChatViewModel.COMPACT_TIMEOUT_MAX_MS < providerReadTimeoutMs,
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
    fun `worst-case wall clock is bounded to minutes, not tens of minutes`() {
        // The regression this whole change exists to prevent.
        val worstMs = ChatViewModel.COMPACT_TIMEOUT_MAX_MS
        assertTrue("must be under 6 minutes", worstMs <= 6 * 60 * 1000L)
    }
}
