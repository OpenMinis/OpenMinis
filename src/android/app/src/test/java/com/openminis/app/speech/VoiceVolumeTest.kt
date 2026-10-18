package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-tts-volume] The 0–200% slider splits into two platform facts:
 * a 0–1 fraction every Android volume knob accepts, and a dB gain that only
 * applies above 100%. These pin the arithmetic so a future "make it louder"
 * tweak cannot silently double-apply or invert the curve.
 */
class VoiceVolumeTest {

    @Test
    fun fractionTopsOutAtOne() {
        assertEquals(1f, VoiceOutputState.volumeFraction(2f), 0.0001f)
        assertEquals(1f, VoiceOutputState.volumeFraction(1f), 0.0001f)
        assertEquals(0.5f, VoiceOutputState.volumeFraction(0.5f), 0.0001f)
        assertEquals(0f, VoiceOutputState.volumeFraction(-1f), 0.0001f)
    }

    @Test
    fun noGainAtOrBelowNormalVolume() {
        assertEquals(0, VoiceOutputState.volumeGainMillibels(1f))
        assertEquals(0, VoiceOutputState.volumeGainMillibels(0.3f))
    }

    @Test
    fun doubleVolumeIsAboutSixDecibels() {
        // 20*log10(2) ≈ 6.0206 dB → 602 mB (rounded).
        assertEquals(602, VoiceOutputState.volumeGainMillibels(2f))
    }

    @Test
    fun gainIsMonotonic() {
        assert(VoiceOutputState.volumeGainMillibels(1.25f) < VoiceOutputState.volumeGainMillibels(1.5f))
        assert(VoiceOutputState.volumeGainMillibels(1.5f) < VoiceOutputState.volumeGainMillibels(2f))
    }

    @Test
    fun labelRendersPercent() {
        assertEquals("100%", VoiceOutputState.volumeLabel(1f))
        assertEquals("200%", VoiceOutputState.volumeLabel(2f))
        assertEquals("0%", VoiceOutputState.volumeLabel(0f))
    }
}
