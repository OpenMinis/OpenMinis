package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fallback-pulse-replay] The reported bug: re-entering a session
 * that had already fallen back to a working model flashed the red "switching
 * model" pulse again, every time.
 */
class FallbackPulseGateTest {

    @Test
    fun `a fresh session that never fell back does not pulse`() {
        assertFalse(shouldPulse(current = 0, baseline = 0))
    }

    @Test
    fun `a fallback while the screen is open pulses`() {
        // Opened at 0, a fallback happens → counter becomes 1.
        assertTrue(shouldPulse(current = 1, baseline = 0))
    }

    @Test
    fun `re-entering a session that fell back earlier does NOT pulse`() {
        // The regression: VM is reused, so the counter arrives already at 3.
        // Baseline is taken at first composition, so nothing new has happened.
        assertFalse(shouldPulse(current = 3, baseline = 3))
    }

    @Test
    fun `a further fallback after re-entry still pulses`() {
        // Re-entered at 3; a NEW fallback during this visit → 4.
        assertTrue(shouldPulse(current = 4, baseline = 3))
    }

    @Test
    fun `several fallbacks in one visit each clear the gate`() {
        assertTrue(shouldPulse(current = 5, baseline = 3))
        assertTrue(shouldPulse(current = 6, baseline = 3))
    }

    @Test
    fun `a counter that somehow moves backwards does not pulse`() {
        // Defensive: switching this composable onto a different session's VM
        // with a lower count must not be read as a fallback.
        assertFalse(shouldPulse(current = 1, baseline = 3))
    }
}
