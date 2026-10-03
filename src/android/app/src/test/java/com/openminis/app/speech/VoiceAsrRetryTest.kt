package com.openminis.app.speech

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-send-waits-for-asr] / [T-voice-asr-failure-retry-prompt]
 *
 * Pure rules (wait/idle decision, failure queue, watchdog scaling, which
 * failures prompt) plus source invariants for the wiring that cannot run on
 * the JVM (engines bind AudioRecord / SpeechRecognizer; the gate is Compose).
 */
class VoiceAsrRetryTest {

    // ── VoiceFinishTracker: when may a waiting send proceed ────────────────

    @Test
    fun `send proceeds only after two idle ticks past the settle window`() {
        val t = VoiceFinishTracker()
        // Idle immediately, but inside the 300 ms settle: a flushed segment may
        // not have landed yet.
        assertEquals(VoiceFinishTracker.Step.WAIT, t.tick(idle = true, failedSinceStart = false, elapsedMs = 100))
        assertEquals(VoiceFinishTracker.Step.WAIT, t.tick(idle = true, failedSinceStart = false, elapsedMs = 200))
        assertEquals(VoiceFinishTracker.Step.WAIT, t.tick(idle = true, failedSinceStart = false, elapsedMs = 300))
        assertEquals(VoiceFinishTracker.Step.SEND, t.tick(idle = true, failedSinceStart = false, elapsedMs = 400))
    }

    @Test
    fun `a busy tick resets the idle streak`() {
        val t = VoiceFinishTracker()
        assertEquals(VoiceFinishTracker.Step.WAIT, t.tick(true, false, 400))
        assertEquals(VoiceFinishTracker.Step.WAIT, t.tick(false, false, 500)) // transcription resumed
        assertEquals(VoiceFinishTracker.Step.WAIT, t.tick(true, false, 600))
        assertEquals(VoiceFinishTracker.Step.SEND, t.tick(true, false, 700))
    }

    @Test
    fun `a failure while waiting holds the send even when idle`() {
        val t = VoiceFinishTracker()
        t.tick(true, false, 400)
        assertEquals(VoiceFinishTracker.Step.FAILED, t.tick(idle = true, failedSinceStart = true, elapsedMs = 500))
    }

    @Test
    fun `waiting past sixty seconds abandons the send`() {
        val t = VoiceFinishTracker()
        assertEquals(60_000L, VoiceFinishTracker.TIMEOUT_MS)
        assertEquals(VoiceFinishTracker.Step.WAIT, t.tick(false, false, 59_900))
        assertEquals(VoiceFinishTracker.Step.TIMED_OUT, t.tick(false, false, 60_000))
    }

    @Test
    fun `failure wins over timeout, idle send wins over timeout`() {
        assertEquals(VoiceFinishTracker.Step.FAILED, VoiceFinishTracker().tick(false, true, 70_000))
        val t = VoiceFinishTracker()
        t.tick(true, false, 59_950)
        assertEquals(VoiceFinishTracker.Step.SEND, t.tick(true, false, 60_050))
    }

    // ── RetryPromptQueue ────────────────────────────────────────────────────

    @Test
    fun `failures are asked one at a time, oldest first`() {
        val q = RetryPromptQueue<String>().enqueue("a").enqueue("b")
        assertEquals("a", q.current)
        assertEquals(2, q.size)
        val afterDiscard = q.discard()
        assertEquals("b", afterDiscard.current)
        assertTrue(afterDiscard.discard().isEmpty())
    }

    @Test
    fun `retry takes the head and a second failure re-queues it at the tail`() {
        val q = RetryPromptQueue<String>().enqueue("a").enqueue("b")
        val (retry, rest) = q.takeForRetry()
        assertEquals("a", retry)
        assertEquals("b", rest.current)
        // The retry failed again → queued again, asked after "b".
        val requeued = rest.enqueue(retry!!)
        assertEquals(listOf("b", "a"), requeued.items)
    }

    @Test
    fun `retry and discard on an empty queue are no-ops`() {
        val empty = RetryPromptQueue<String>()
        val (item, rest) = empty.takeForRetry()
        assertNull(item)
        assertTrue(rest.isEmpty())
        assertTrue(empty.discard().isEmpty())
        assertNull(empty.current)
    }

    @Test
    fun `queue is immutable - operations never change the original`() {
        val q = RetryPromptQueue<String>().enqueue("a")
        q.enqueue("b"); q.discard(); q.takeForRetry()
        assertEquals(listOf("a"), q.items)
    }

    // ── VoiceAsrWatchdog ────────────────────────────────────────────────────

    @Test
    fun `watchdog is at least 8s, grows with the audio, capped at 120s`() {
        assertEquals(8.0, VoiceAsrWatchdog.seconds(0.0), 0.0)
        assertEquals(8.0, VoiceAsrWatchdog.seconds(-3.0), 0.0)
        assertEquals(8.0, VoiceAsrWatchdog.seconds(Double.NaN), 0.0)
        assertEquals(18.0, VoiceAsrWatchdog.seconds(10.0), 1e-9)
        assertEquals(68.0, VoiceAsrWatchdog.seconds(60.0), 1e-9)
        assertEquals(120.0, VoiceAsrWatchdog.seconds(112.0), 1e-9)
        assertEquals(120.0, VoiceAsrWatchdog.seconds(300.0), 0.0)
    }

    @Test
    fun `audio duration from pcm bytes and wav header`() {
        assertEquals(1.0, VoiceAsrWatchdog.pcmSeconds(32_000), 1e-9) // 16 kHz default
        assertEquals(1.0, VoiceAsrWatchdog.pcmSeconds(96_000, 48_000), 1e-9)
        assertEquals(0.0, VoiceAsrWatchdog.pcmSeconds(0), 0.0)
        assertEquals(0.0, VoiceAsrWatchdog.pcmSeconds(100, 0), 0.0)

        assertEquals(2.5, VoiceAsrWatchdog.wavSeconds(wav(pcmBytes = 80_000, byteRate = 32_000)), 1e-9)
        assertEquals(1.0, VoiceAsrWatchdog.wavSeconds(wav(pcmBytes = 96_000, byteRate = 96_000)), 1e-9)
        assertEquals(0.0, VoiceAsrWatchdog.wavSeconds(ByteArray(44)), 0.0) // header only
        assertEquals(0.0, VoiceAsrWatchdog.wavSeconds(wav(pcmBytes = 100, byteRate = 0)), 0.0)
    }

    // ── VoiceAsrFailurePolicy ───────────────────────────────────────────────

    @Test
    fun `real failures with audio prompt, no-speech and blips do not`() {
        for (e in listOf(
            RecognitionError.NETWORK,
            RecognitionError.UNKNOWN,
            RecognitionError.TIMED_OUT,
            RecognitionError.TRANSCRIPTION_FAILED,
            RecognitionError.OEM_NO_SERVICE, // no usable provider
            RecognitionError.PERMISSION_DENIED, // provider 401 (only captured audio is handed back)
        )) {
            assertTrue("$e should prompt", VoiceAsrFailurePolicy.shouldPromptRetry(e, 5.0))
        }
        assertFalse(VoiceAsrFailurePolicy.shouldPromptRetry(RecognitionError.NO_MATCH, 5.0))
        assertFalse(VoiceAsrFailurePolicy.shouldPromptRetry(RecognitionError.NETWORK, 0.3))
        assertFalse(VoiceAsrFailurePolicy.shouldPromptRetry(RecognitionError.NETWORK, 0.0))
    }

    // ── Source invariants (wiring) ──────────────────────────────────────────

    @Test
    fun `provider engine hands the audio back before every exhausted-chain error`() {
        val src = ProductionSources.read("speech/ProviderSpeechRecognitionEngine.kt")
        // [T-android-voice-asr-stall-skip] The shared fail-over (one-shot take
        // and VAD segments) now lives in transcribeAndDeliver.
        val body = src.substringAfter("private suspend fun transcribeAndDeliver(").substringBefore("private fun transcribeWithSystem(")
        val tail = body.substringAfterLast("val kind = when {")
        assertTrue(tail.indexOf("listener.onRetainedAudio(wav)") in 0 until tail.indexOf("listener.onError("))
        assertTrue("retry must be implemented", src.contains("override fun transcribeRetained("))
    }

    @Test
    fun `system engine scales its watchdog and treats an empty timeout as a failure`() {
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(src.contains("VoiceAsrWatchdog.seconds("))
        val watchdog = src.substringAfter("private fun armWatchdog(").substringBefore("private fun cancelWatchdog(")
        assertTrue(watchdog.contains("RecognitionError.TIMED_OUT"))
        assertTrue(watchdog.indexOf("handBackAudio()") < watchdog.indexOf("RecognitionError.TIMED_OUT"))
        val stop = src.substringAfter("override fun stop() {").substringBefore("// ── [T-voice-asr-failure-retry-prompt]")
        assertTrue("stop() must arm the watchdog", stop.contains("armWatchdog("))
    }

    @Test
    fun `manager queues a kept-audio failure before publishing IDLE`() {
        val src = ProductionSources.read("speech/SpeechRecognitionManager.kt")
        val onError = src.substringAfter("override fun onError(error: RecognitionError, message: String?) {")
            .substringBefore("override fun onRmsDb")
        val queued = onError.indexOf("onFailedAudio?.invoke(failed)")
        val idle = onError.indexOf("setState(RecognitionState.IDLE)")
        assertTrue(queued >= 0 && idle > queued)
    }

    @Test
    fun `every composer send entry goes through the voice gate`() {
        val src = ProductionSources.read("ui/chat/ChatScreen.kt")
        val sendOrEnqueue = src.substringAfter("val performSendOrEnqueue: (String) -> Unit = handler@{")
            .substringBefore("\n    }")
        assertTrue(sendOrEnqueue.contains("deferSendUntilVoiceFinished()"))
        val enter = src.substringAfter("val performEnterSend: () -> Boolean = handler@{")
            .substringBefore("tryExecuteInputAsSlashCommand")
        assertTrue(enter.contains("deferSendUntilVoiceFinished()"))
        assertTrue(src.contains("val hasContent = hasText || attachments.isNotEmpty() || voiceOwesText"))
    }

    private fun wav(pcmBytes: Int, byteRate: Int): ByteArray {
        val b = ByteArray(44 + pcmBytes)
        b[28] = (byteRate and 0xFF).toByte()
        b[29] = ((byteRate shr 8) and 0xFF).toByte()
        b[30] = ((byteRate shr 16) and 0xFF).toByte()
        b[31] = ((byteRate shr 24) and 0xFF).toByte()
        return b
    }
}
