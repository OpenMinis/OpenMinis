package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Group context slider used to be min()'d with the model heuristic, so a user
 * 1M cap against DeepSeek's 128K fallback never moved compactThreshold off 108K.
 */
class ContextPolicyEffectiveWindowTest {

    @Test
    fun group1MRaisesHeuristic128K() {
        assertEquals(1_000_000, ContextPolicy.effectiveWindow(128_000, 1_000_000))
        assertEquals(980_000, ContextPolicy.forContextWindow(1_000_000).compactThreshold)
    }

    @Test
    fun group128KCapsModel1M() {
        assertEquals(128_000, ContextPolicy.effectiveWindow(1_000_000, 128_000))
        assertEquals(108_000, ContextPolicy.forContextWindow(128_000).compactThreshold)
    }

    @Test
    fun unlimitedAndMissingUseModelWindow() {
        assertEquals(128_000, ContextPolicy.effectiveWindow(128_000, null))
        assertEquals(128_000, ContextPolicy.effectiveWindow(128_000, 0))
        assertEquals(128_000, ContextPolicy.effectiveWindow(128_000, Int.MAX_VALUE))
    }
}
