package com.openminis.app.data

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-fallback] The decisions behind compact's new fallback loop.
 *
 * Compact used to call the provider directly with no chain: a rate-limited or
 * exhausted model threw, and the ONLY recovery was the splitter's halving —
 * which re-sends to the same dead model, so it could never help. These pin the
 * two rules that make the new loop correct:
 *
 *  1. "worth switching" must be the agent loop's classification, not a new one,
 *     or compact and normal turns would disagree about the same error.
 *  2. the splitter must NOT swallow those errors first, or the fallback loop
 *     never sees them.
 *
 * The real loop needs a live ViewModel + providers, so these mirror the
 * predicates rather than instantiating one; the predicates are the whole
 * decision.
 */
class CompactFallbackDecisionTest {

    /** Mirrors the worth-switching test in the compact loop. */
    private fun worthSwitching(
        error: Throwable,
        strategy: FallbackStrategy = FallbackStrategy.default,
    ): Boolean {
        val err = error as? LLMError
        return err?.isFallbackable == true ||
            err?.isHttpServerError == true ||
            error is LLMError.RateLimited ||
            strategy == FallbackStrategy.always
    }

    @Test
    fun `quota and auth failures switch models`() {
        // The reported case: the session's model is exhausted, so compact must
        // move to another model rather than retrying the dead one.
        assertTrue(worthSwitching(LLMError.RateLimited()))
        assertTrue(worthSwitching(LLMError.InvalidApiKey("401")))
        assertTrue(worthSwitching(LLMError.ProviderError("insufficient balance")))
    }

    @Test
    fun `a 5xx switches models`() {
        val e = LLMError.TransientError("upstream", httpStatus = 503)
        assertTrue("a confirmed 5xx is another model's problem to solve", worthSwitching(e))
    }

    @Test
    fun `a transient error with no status does not switch`() {
        // No evidence another model behaves differently — switching would just
        // spend a second model on the same network fault.
        val e = LLMError.TransientError("socket closed", httpStatus = null)
        assertFalse(worthSwitching(e))
    }

    @Test
    fun `always strategy switches on anything`() {
        val e = LLMError.TransientError("socket closed", httpStatus = null)
        assertFalse(worthSwitching(e, FallbackStrategy.default))
        assertTrue(worthSwitching(e, FallbackStrategy.always))
    }

    @Test
    fun `the splitter does not swallow fallbackable errors`() {
        // This ordering is the crux: if shouldSplitOnError returned true for
        // these, the splitter would burn the call budget halving the input
        // against a model that is refusing for a reason size cannot fix, and
        // the fallback loop would never get a turn.
        assertFalse(ChatViewModelCompanionProbe.shouldSplit(LLMError.RateLimited()))
        assertFalse(ChatViewModelCompanionProbe.shouldSplit(LLMError.InvalidApiKey("401")))
        assertFalse(
            ChatViewModelCompanionProbe.shouldSplit(
                LLMError.TransientError("upstream", httpStatus = 503),
            ),
        )
        // An over-length refusal still splits — that is what splitting is for.
        assertTrue(ChatViewModelCompanionProbe.shouldSplit(LLMError.ProviderError("too many tokens")))
    }

    /** Failed-entry avoidance used by the group re-pick. */
    private fun pick(available: List<String>, failed: Set<String>): String =
        available.firstOrNull { it !in failed } ?: available.first()

    @Test
    fun `group re-pick skips the member that just failed`() {
        // Re-selecting a group after model A died used to hand A straight back
        // (available.first()), so the picker looked like it did nothing.
        assertEquals("B", pick(listOf("A", "B", "C"), setOf("A")))
    }

    @Test
    fun `a fully degraded group still resolves`() {
        // Every member marked: fall back to first() rather than refusing to
        // pick at all, which would strand the session with no provider.
        assertEquals("A", pick(listOf("A", "B"), setOf("A", "B")))
    }
}

/** Reaches the companion-object predicate under test. */
private object ChatViewModelCompanionProbe {
    fun shouldSplit(e: Throwable): Boolean =
        com.openminis.app.ui.chat.ChatViewModel.shouldSplitOnError(e)
}
