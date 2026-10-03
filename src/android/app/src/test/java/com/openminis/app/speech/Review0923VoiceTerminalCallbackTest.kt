package com.openminis.app.speech

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — provider-ASR VAD path must always end a take with a
 * terminal callback (onFinal or onError).
 *
 * Guards 759e60d6f and 5ec5f50d3 (T-android-vad-manual-stop). 759e60d6f fixed
 * the manual-stop hang by flushing on stop(), and stated the invariant:
 * "Every path out of here MUST end in onFinal or onError". 5ec5f50d3 then
 * reworked the same stop() for audio quality. The engine binds AudioRecord and
 * cannot be built on the JVM, so these are source invariants over
 * ProviderSpeechRecognitionEngine.kt plus the extracted decision.
 *
 * [BUG] one path still breaks it: the hold-expiry job. A sub-2 s segment is
 * held with the mic open; after HOLD_FLUSH_MS the job calls stopVad() (the
 * detector goes null) and reports NO_MATCH only when the held audio is above
 * TOO_SHORT_TOAST_FLOOR. For a cough (<= 0.3 s) it delivers nothing, so
 * SpeechRecognitionManager stays in RECORDING with a dead mic. When the user
 * then taps stop, stop() sees `detector == null`, skips the flush, and the
 * panel spins on "Recognizing..." forever — the exact 759e60d6f symptom.
 */
class Review0923VoiceTerminalCallbackTest {

    private val src by lazy { ProductionSources.read("speech/ProviderSpeechRecognitionEngine.kt") }

    @Test
    fun `a manual stop with a live session always resolves to a terminal outcome`() {
        // Every non-idle outcome must lead to onFinal (TRANSCRIBE) or onError.
        for (held in listOf(0f, 0.3f, 0.31f, 5f)) {
            val o = VadManualStopOutcome.decide(hasSession = true, heldSeconds = held, floorSeconds = 0.3f)
            assertTrue("held=$held gave $o", o == VadManualStopOutcome.TRANSCRIBE || o == VadManualStopOutcome.NO_MATCH)
        }
        assertEquals(
            VadManualStopOutcome.NOTHING_TO_DO,
            VadManualStopOutcome.decide(hasSession = false, heldSeconds = 5f, floorSeconds = 0.3f),
        )
    }

    @Test
    fun `the manual-stop flush is wired into stop and into the session-limit callback`() {
        val stopBody = src.substringAfter("override fun stop() {").substringBefore("\n    }")
        assertTrue("stop() must flush the VAD take", stopBody.contains("flushVadOnManualStop()"))
        assertTrue("stop() must collect the OPEN segment first", stopBody.contains(".flush()"))
        val limitBody = src.substringAfter("override fun onSessionLimit(").substringBefore("override fun onCaptureError")
        assertTrue("session limit must end in a terminal callback", limitBody.contains("flushVadOnManualStop()"))
    }

    /**
     * [BUG] Hold-expiry settles the mic without a terminal callback for a
     * sub-floor take, and stop() afterwards cannot recover because it only
     * flushes when a detector is still attached.
     *
     * Passes once EITHER (a) the hold-expiry job delivers a terminal callback
     * on every path (e.g. NO_MATCH with a null message for the silent case), OR
     * (b) stop() flushes whenever a VAD session is still open
     * (`wasVad = det != null || vadSession != null`).
     */
    @Test
    fun `settling the mic after a held take never leaves the manager without a terminal callback`() {
        val holdBlock = src.substringAfter("holdFlushJob = scope.launch {").substringBefore("return\n")
        assertTrue("hold-expiry block not found", holdBlock.contains("stopVad()"))
        // Remove the floor-gated branch; whatever remains runs unconditionally.
        val unconditional = holdBlock.replace(
            Regex("if \\(held > TOO_SHORT_TOAST_FLOOR\\) \\{[\\s\\S]*?\\n\\s*\\}"),
            "",
        )
        val holdAlwaysTerminates = unconditional.contains("onError(") ||
            unconditional.contains("onFinal(") ||
            unconditional.contains("flushVadOnManualStop()")

        val stopBody = src.substringAfter("override fun stop() {").substringBefore("\n    }")
        val stopFlushesOnSession = Regex("wasVad\\s*=.*vadSession").containsMatchIn(stopBody)

        assertTrue(
            "hold-expiry calls stopVad() and, for a take <= TOO_SHORT_TOAST_FLOOR, delivers no " +
                "onFinal/onError; stop() then skips the flush because detector is null — the " +
                "panel hangs on 'Recognizing...'",
            holdAlwaysTerminates || stopFlushesOnSession,
        )
    }

    @Test
    fun `a take that already resolved cannot be finalised a second time`() {
        // Silence-close transcribes and must disarm the manual-stop flush;
        // cancel() must disarm it too.
        val silence = src.substringAfter("if (reason == SegmentEndReason.SILENCE_DETECTED) {\n")
            .substringBefore("transcribeJob = scope.launch")
        assertTrue(silence.contains("vadSession = null"))
        val cancelBody = src.substringAfter("override fun cancel() {").substringBefore("\n    }")
        assertTrue(cancelBody.contains("vadSession = null"))
    }
}
