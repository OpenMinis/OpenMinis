package com.openminis.app.ui.chat.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.RetryPromptQueue
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceFinishTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared state between the inline voice panel and the chat composer's send
 * paths. Process-scoped like [VoiceModePrefs]: only one voice panel is ever on
 * screen, and the composer (which owns Send) and the panel (which owns the
 * recognizer callbacks) are separate composables.
 *
 * Two jobs, mirroring iOS VoiceInputViewModel:
 *
 *  - [T-voice-send-waits-for-asr] "Does voice mode still owe text?" and the
 *    wait-then-send that Send performs when it does ([finishCaptureForSend]).
 *    Before this, Send while recording cleared the composer, which made the
 *    panel CANCEL the capture — the last sentence spoken before the tap never
 *    reached the message.
 *  - [T-voice-asr-failure-retry-prompt] The queue of failed utterances whose
 *    audio is kept until the user picks Retry or Discard.
 */
object VoiceSendGate {

    private const val TAG = "VoiceSend"

    /** One failed utterance awaiting the user's Retry / Discard decision. */
    class FailedUtterance(
        val id: Long,
        val audio: SpeechRecognitionManager.FailedAudio,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = AtomicLong(1)

    /**
     * Bumped every failure, synchronously on the reporting thread — before
     * SpeechRecognitionManager publishes IDLE — so a waiting send can never
     * mistake a failed take for a clean finish.
     */
    private val failureCount = AtomicInteger(0)

    /** Failed utterances, oldest first. Main-thread writes only. */
    var queue by mutableStateOf(RetryPromptQueue<FailedUtterance>())
        private set

    /** True while a send is waiting for capture to stop and ASR to finish. */
    var isFinishingForSend by mutableStateOf(false)
        private set

    /**
     * Composition generation: bumped when the composer is emptied from outside
     * the panel (a send). A failure belonging to an older generation is logged,
     * not prompted — its composition is already gone.
     */
    var generation by mutableIntStateOf(0)
        private set

    /** Latest panel transcript, so a deferred send can copy it explicitly. */
    @Volatile
    var transcript: String = ""

    fun bumpGeneration() {
        generation += 1
    }

    /**
     * Anything captured that has not become composer text yet: the mic is
     * capturing, audio is held below the segment threshold (the provider
     * engine keeps the mic open then), a transcription or retry is in flight,
     * or a send is already waiting on one.
     */
    fun owesText(): Boolean =
        VoiceModePrefs.isVoiceActive &&
            (isFinishingForSend || SpeechRecognitionManager.state.value != RecognitionState.IDLE)

    // ── [T-voice-asr-failure-retry-prompt] failure queue ────────────────────

    /**
     * Queue a failed utterance for the retry prompt. Safe from any thread (the
     * provider engine reports on an IO thread).
     */
    fun recordFailure(failed: SpeechRecognitionManager.FailedAudio, captureGeneration: Int) {
        if (captureGeneration != generation) {
            AppLogger.info(
                TAG,
                "[voice-failure] ${"%.1f".format(failed.seconds)}s failed after the composition " +
                    "was sent/cleared — not prompting (${failed.error})",
            )
            return
        }
        failureCount.incrementAndGet()
        AppLogger.info(TAG, "⚠️ [voice-failure] ${"%.1f".format(failed.seconds)}s kept for retry (${failed.error})")
        val item = FailedUtterance(nextId.getAndIncrement(), failed)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            queue = queue.enqueue(item)
        } else {
            mainHandler.post { queue = queue.enqueue(item) }
        }
    }

    /** User chose Retry: remove the head and return it for re-transcription. */
    fun takeForRetry(): FailedUtterance? {
        val (item, rest) = queue.takeForRetry()
        queue = rest
        if (item != null) {
            AppLogger.info(TAG, "🔁 [voice-failure] retry requested (${"%.1f".format(item.audio.seconds)}s)")
        }
        return item
    }

    /** Put an utterance back (a retry that could not start right now). */
    fun requeue(item: FailedUtterance) {
        queue = queue.enqueue(item)
    }

    /** User chose Discard: drop the head's audio. */
    fun discardCurrent() {
        queue.current?.let {
            AppLogger.info(TAG, "🗑️ [voice-failure] discarded by user (${"%.1f".format(it.audio.seconds)}s)")
        }
        queue = queue.discard()
    }

    // ── [T-voice-send-waits-for-asr] finish before send ─────────────────────

    /**
     * Called by every send entry (button, swipe, IME return) before it sends.
     *
     * @return false when voice mode owes nothing — the caller sends normally.
     *   true when the send was deferred (or a deferral is already pending, in
     *   which case this repeated tap is ignored so nothing is sent twice); the
     *   caller must stop, and [onReady] runs once every captured sample has
     *   been transcribed into the composer.
     */
    fun deferSendIfOwed(scope: CoroutineScope, context: Context, onReady: () -> Unit): Boolean {
        if (!owesText()) return false
        finishCaptureForSend(scope, context, onReady)
        return true
    }

    /**
     * Stop the mic, push EVERY captured sample to recognition — the manual
     * stop flushes the open VAD segment AND audio held below the segment
     * minimum — and run [onReady] once all transcriptions have landed.
     *
     * [onReady] does not run when a transcription failed while waiting (the
     * retry prompt takes over; sending now would drop that speech) or when
     * recognition is still running after 60 s (a toast asks the user to tap
     * Send again once the text appears; the result still lands in the
     * composer when it arrives).
     */
    private fun finishCaptureForSend(scope: CoroutineScope, context: Context, onReady: () -> Unit) {
        if (isFinishingForSend) {
            AppLogger.info(TAG, "[voice-send] finish already pending — ignoring repeated send")
            return
        }
        isFinishingForSend = true
        val failuresBefore = failureCount.get()
        val state = SpeechRecognitionManager.state.value
        AppLogger.info(TAG, "🎙️ [voice-send] send while state=$state — finishing recognition first")
        if (state == RecognitionState.RECORDING || state == RecognitionState.STARTING) {
            SpeechRecognitionManager.stopRecording()
        }
        val app = context.applicationContext
        scope.launch {
            val tracker = VoiceFinishTracker()
            val started = SystemClock.elapsedRealtime()
            var step: VoiceFinishTracker.Step
            try {
                do {
                    delay(100)
                    step = tracker.tick(
                        idle = SpeechRecognitionManager.state.value == RecognitionState.IDLE,
                        failedSinceStart = failureCount.get() > failuresBefore,
                        elapsedMs = SystemClock.elapsedRealtime() - started,
                    )
                } while (step == VoiceFinishTracker.Step.WAIT)
            } finally {
                isFinishingForSend = false
            }
            val elapsedS = (SystemClock.elapsedRealtime() - started) / 1000.0
            when (step) {
                VoiceFinishTracker.Step.SEND -> {
                    AppLogger.info(TAG, "✅ [voice-send] recognition finished in ${"%.1f".format(elapsedS)}s — sending")
                    onReady()
                }
                VoiceFinishTracker.Step.FAILED ->
                    AppLogger.info(TAG, "⚠️ [voice-send] a transcription failed while finishing — send held for the retry prompt")
                VoiceFinishTracker.Step.TIMED_OUT -> {
                    AppLogger.info(TAG, "⏳ [voice-send] recognition still running after ${elapsedS.toInt()}s — send abandoned")
                    android.widget.Toast.makeText(
                        app,
                        app.getString(R.string.voice_send_still_transcribing),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                VoiceFinishTracker.Step.WAIT -> Unit
            }
        }
    }
}
