package com.openminis.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.provider.voice.VoiceInputRequest
import com.openminis.app.provider.voice.VoiceProvider
import com.openminis.app.provider.voice.VoiceProviderException
import com.openminis.app.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * [T-android-provider-voice] Provider-backed transcription engine — the
 * formerly-stubbed Phase 2. Captures PCM16 mono 16 kHz via [AudioRecord],
 * wraps the take in a WAV container on stop, and ships it to the VoiceProvider
 * resolved from the Voice Input group (VoiceProviderFactory + the instance's
 * stored API key). Mirrors the iOS provider ASR path (VoiceProvider.transcribe:
 * dedicated Whisper-style endpoint, vendor adapters, or chat-based ASR for
 * audio chat models).
 *
 * No partial results: cloud transcription is one-shot on stop. The System
 * engine remains the streaming/live option.
 */
class ProviderSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "ProviderASR"

        /**
         * [T-android-voice-asr-stall-skip] How long an attempt may go without a
         * result before "Switch Model" is offered. iOS 4a1f44220: 5 s (the user
         * found 10 s too long). Only an offer: an attempt the user does not skip
         * keeps running and lands normally.
         */
        const val ASR_STALL_THRESHOLD_MS = 5_000L

        /** nextEntryId meaning "System recognition" (Android candidates never include System). */
        private const val SYSTEM_NEXT = "__system_asr__"
        private const val SAMPLE_RATE = 16_000
        /** Hard cap on a single take (60 s at 16 kHz PCM16 mono ≈ 1.9 MB). */
        private const val MAX_RECORD_SECONDS = 60

        // ── [T-android-vad] ──
        /**
         * Cloud ASR has no per-request length ceiling the way Apple's system
         * recogniser does, so a segment may run to the full session cap
         * (iOS VoiceInputPanel.swift:488-489).
         */
        private const val CLOUD_MAX_SEGMENT_SECONDS = 300

        /**
         * Segments shorter than this are dropped on a silence close, matching
         * iOS `minSegmentSeconds` (VoiceInputPanel.swift:607). A cough or a
         * door would otherwise cost a paid transcription request.
         */
        private const val MIN_SEGMENT_SECONDS = 2.0f

        /** Below this we stay silent; above it the user gets told why (iOS :661). */
        private const val TOO_SHORT_TOAST_FLOOR = 0.3f

        /**
         * [T-android-vad-merge-segments] How long to keep the mic open waiting
         * for the user to top up a sub-2s utterance. Matches the iOS
         * force-flush timer (VoiceInputPanel.swift:609).
         */
        private const val HOLD_FLUSH_MS = 5_000L
    }

    override val id: String = "provider"
    override val displayName: String = "Provider transcription"
    override val supportsPartialResults: Boolean = false

    /** Cheap check only: is a provider ASR selection resolvable right now? */
    override val isAvailable: Boolean
        get() = !degraded && repository()?.resolveVoiceInputEntry() != null

    /** Cloud ASR is language-agnostic (auto-detect); no fixed locale list. */
    override val supportedLocales: List<Locale> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var transcribeJob: Job? = null
    /** [T-android-vad-merge-segments] Sub-threshold segments held for merge. */
    private val pendingSegments = mutableListOf<ByteArray>()
    private var holdFlushJob: Job? = null
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var degraded = false

    // [T-android-voice-system-fallback-cancel] One number per take. The System
    // recognition fallback (after a user skip exhausts the chain) is handed the
    // audio and runs on its own engine; nothing used to stop it. Tapping X, or
    // sending, cancelled only this engine, and seconds later the System result
    // still reached the listener: the old dictation reappeared in a cleared
    // composer, and if a new take had started, its onFinal forced the panel to
    // IDLE under a live mic (the next tap then failed RECOGNIZER_BUSY). iOS
    // awaits the System transcription inside its cancellable task and drops
    // stale results by transcriptGeneration; this is the same guard.
    private val takeGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    /** The take whose audio went to the System recognizer, or -1. */
    private val systemReplayGeneration = java.util.concurrent.atomic.AtomicInteger(-1)

    /**
     * [T-android-vad] Live Silero detector for the segmented path, null when
     * idle or when running the legacy record-until-stop loop.
     */
    @Volatile
    private var detector: VoiceActivityDetector? = null

    /**
     * [T-android-vad-manual-stop] Everything [stop] needs to finalise a take
     * the user ended by hand.
     *
     * On the VAD path audio reaches transcription ONLY through the detector's
     * `onVoiceEnd` callback, and that callback closes over the locale, repo,
     * candidate chain and listener for the session. `stop()` takes no
     * arguments, so without holding them here it could tear the detector down
     * but had no way to transcribe what had already been captured.
     */
    private class VadSession(
        val locale: Locale,
        val repo: com.openminis.app.data.repository.ProviderRepository,
        val candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        val listener: SpeechRecognitionEngine.Listener,
    )

    @Volatile
    private var vadSession: VadSession? = null

    /**
     * Whether to segment with Silero instead of recording until the user taps
     * stop. Defaults ON: this engine had no endpointing at all, so the VAD is
     * strictly better here. Kept as a flag so a device where the native VAD
     * fails to load can be dropped back to the legacy loop rather than losing
     * voice input entirely.
     */
    @Volatile
    var useVad: Boolean = true

    /**
     * [T-voice-asr-group-failover] STICKY fail-over (mirrors iOS
     * VoiceInputViewModel and the text agent loop's nextFallback): once a take
     * succeeds on a group member, later takes start there — only advance when
     * IT fails. Cleared implicitly when the entry leaves the candidate set
     * (the ordering below just falls back to chain order then).
     */
    @Volatile
    private var stickyEntryId: String? = null

    /**
     * [T-android-voice-asr-stall-skip] Every candidate attempt runs through
     * this, so one that stalls past [ASR_STALL_THRESHOLD_MS] can be skipped to
     * the next voice model (System recognition last). Port of iOS
     * VoiceInputViewModel.asrAttempts.
     */
    val asrAttempts = SkippableAsrAttempts(ASR_STALL_THRESHOLD_MS).also { a ->
        a.log = { msg -> Log.i(TAG, msg); VoicePipelineLog.event("engine.asr.stall", "msg" to msg.take(80)) }
    }

    /**
     * [T-android-voice-asr-stall-skip] Set when the user skipped past the last
     * candidate: the rest of this take goes straight to System recognition
     * instead of queueing on a server that just hung. Cleared by [start].
     */
    @Volatile
    private var systemAsrPinned = false

    /** Candidate chain of the running take, for naming a skip's target. */
    @Volatile
    private var currentCandidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>> = emptyList()

    // [T-android-safemode-lateinit-crash-147] subsystemsReady() first — the
    // safe call rules out a null Application, not an unassigned lateinit,
    // whose getter throws. Speech recognition can be started from a
    // shortcut/assistant intent that never went through MainActivity's guard.
    // Every caller already treats null as "no provider configured".
    private fun repository() =
        (appContext.applicationContext as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO per interface contract
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        // [T-voice-asr-group-failover] Resolve the whole ordered candidate
        // chain instead of one entry. loadBalance groups get a fresh rotation
        // seed per capture so takes spread across members; fallback groups
        // keep declaration order. Provider construction is deferred to the
        // transcription step, where a failing member advances to the next.
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        systemAsrPinned = false
        currentCandidates = candidates
        if (repo == null || candidates.isEmpty()) {
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
            )
            return
        }
        if (recording.getAndSet(true)) {
            listener.onError(RecognitionError.RECOGNIZER_BUSY, "A capture is already in flight.")
            return
        }
        supersedeTake()
        cancelled.set(false)
        // [T-android-vad-merge-segments] A fresh session must not inherit audio
        // held from the previous one.
        pendingSegments.clear()
        holdFlushJob?.cancel()
        holdFlushJob = null

        // [T-android-vad] VAD-segmented path. Before this, the provider engine
        // had NO endpointing at all: it recorded until the user tapped stop or
        // hit the 60 s cap, so a custom-model user had to manually bracket
        // every utterance. Silero now closes a segment after ~5 s of silence
        // and the mic stops, exactly as on iOS.
        if (useVad) {
            startVadCapture(locale, repo, candidates, listener)
            return
        }

        captureJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                recording.set(false)
                listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord unsupported buffer size.")
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                )
            } catch (e: Exception) {
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext), "AudioRecord init failed: ${e.message}")
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext), "AudioRecord not initialized.")
                return@launch
            }

            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            val maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS
            try {
                recorder.startRecording()
                listener.onReadyForSpeech()
                // [T-voice-mic-preempted] Same silenced-capture check as the VAD.
                var nextSilenceCheckAtMs = System.currentTimeMillis() + 400L
                while (recording.get() && pcm.size() < maxBytes) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSilenceCheckAtMs) {
                        nextSilenceCheckAtMs = now + 1_000L
                        if (MicInUse.isSilenced(appContext, recorder.audioSessionId)) {
                            recording.set(false)
                            listener.onError(RecognitionError.MIC_IN_USE, "Recording silenced by the system")
                            return@launch
                        }
                    }
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    pcm.write(buf, 0, n)
                    listener.onRmsDb(rmsDb(buf, n))
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed: ${e.message}", e)
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext, recorder.audioSessionId), e.message)
                return@launch
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }

            if (cancelled.get()) {
                VoicePipelineLog.event("engine.capture.cancelled")
                return@launch
            }
            val audio = pcm.toByteArray()
            VoicePipelineLog.event("engine.capture.end", "bytes" to audio.size)
            if (audio.isEmpty()) {
                listener.onError(RecognitionError.NO_MATCH, "No audio captured.")
                return@launch
            }

            // One-shot cloud transcription of the whole take, with SEAMLESS
            // fail-over across the candidate chain (mirrors iOS
            // transcribeWithFailover / VoiceOutputPlayer): a candidate that
            // throws advances to the next; the first success becomes the
            // STICKY member for later takes; only when EVERY candidate fails
            // does the error surface (last error wins).
            transcribeJob = scope.launch {
                VoicePipelineLog.event("engine.transcribe.begin", "candidates" to candidates.size)
                val wav = VoiceProvider.wrapPcm16InWav(audio, SAMPLE_RATE)
                // [T-android-voice-asr-stall-skip] Same fail-over as a VAD
                // segment: each attempt is skippable once stalled.
                transcribeAndDeliver(wav, locale, repo, candidates, listener, logEvents = true)
            }
        }
    }

    // ── [T-android-vad] VAD-segmented capture ─────────────────────────────

    /**
     * Silence-segmented capture. Silero closes a segment after ~5 s of silence;
     * we then transcribe that segment and STOP the mic, matching iOS
     * (`VoiceInputPanel.swift:642-669`) — the user re-taps for the next
     * utterance rather than the mic staying hot.
     */
    private fun startVadCapture(
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
    ) {
        val det = VoiceActivityDetector(
            appContext,
            object : VoiceActivityListener {
                override fun onVoiceStart() {
                    Log.i(TAG, "[vad] speech start")
                }

                override fun onLevel(level: Float) {
                    // Map the detector's perceptual [0,1] back onto the [0,12]
                    // dB-ish scale SpeechRecognitionManager normalizes from, so
                    // both engines drive the waveform identically.
                    listener.onRmsDb(level * 12f)
                }

                override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
                    if (cancelled.get()) return

                    // [T-android-vad-merge-segments] ACCUMULATE, don't discard.
                    //
                    // iOS holds each silence-closed segment in `pendingSegments`
                    // and tests the 2 s minimum against the RUNNING TOTAL
                    // (VoiceInputPanel.swift:635-663). Android used to drop every
                    // sub-2s segment on its own, so natural stop-start speech —
                    // "yes" (0.8 s), pause, "send it" (0.9 s) — could NEVER be
                    // dictated: each piece was binned and the mic switched off.
                    // The 2 s floor still does its job (one isolated cough is
                    // still one short segment), it just applies to the whole
                    // held utterance now.
                    pendingSegments.add(wav)
                    val heldSeconds = WavSegmentMerger.totalSeconds(pendingSegments)

                    if (reason == SegmentEndReason.SILENCE_DETECTED &&
                        heldSeconds < MIN_SEGMENT_SECONDS
                    ) {
                        Log.i(
                            TAG,
                            "[vad] holding ${"%.2f".format(spokenSeconds)}s segment " +
                                "(total ${"%.2f".format(heldSeconds)}s < ${MIN_SEGMENT_SECONDS}s)",
                        )
                        // Keep the mic OPEN so the next burst can top it up.
                        // Force-flush after HOLD_FLUSH_MS of real silence so a
                        // genuine cough still resolves (to a "too short" toast)
                        // instead of leaving the mic on indefinitely.
                        holdFlushJob?.cancel()
                        holdFlushJob = scope.launch {
                            kotlinx.coroutines.delay(HOLD_FLUSH_MS)
                            if (cancelled.get()) return@launch
                            val held = WavSegmentMerger.totalSeconds(pendingSegments)
                            Log.i(TAG, "[vad] hold expired at ${"%.2f".format(held)}s — settling")
                            stopVad()
                            // [T-android-vad-hold-expiry-terminal] Settling the mic
                            // ends the take, so it MUST end in a terminal callback
                            // on every path. A sub-floor take (a cough) used to get
                            // none: SpeechRecognitionManager stayed RECORDING with
                            // a dead mic, and a later stop() skipped the flush
                            // (detector already null) — the panel spun on
                            // "Recognizing..." forever. The floor now only decides
                            // whether the user sees a reason: NO_MATCH with a null
                            // message is the silent outcome (the panel swallows
                            // NO_MATCH). The session is disarmed so nothing can
                            // finalise it a second time.
                            vadSession = null
                            listener.onError(
                                RecognitionError.NO_MATCH,
                                if (held > TOO_SHORT_TOAST_FLOOR) "Too short — hold the mic and speak." else null,
                            )
                            pendingSegments.clear()
                        }
                        return
                    }

                    holdFlushJob?.cancel()
                    holdFlushJob = null
                    val merged = WavSegmentMerger.merge(pendingSegments) ?: wav
                    if (pendingSegments.size > 1) {
                        Log.i(
                            TAG,
                            "[vad] merged ${pendingSegments.size} segments → " +
                                "${"%.2f".format(heldSeconds)}s",
                        )
                    }
                    pendingSegments.clear()

                    if (reason == SegmentEndReason.SILENCE_DETECTED) {
                        // Silence = the user stopped. Mic off, then transcribe.
                        // The detector already delivered this segment, so the
                        // manual-stop flush must not fire for it as well.
                        vadSession = null
                        stopVad()
                    }
                    transcribeJob = scope.launch {
                        transcribeSegment(merged, locale, repo, candidates, listener)
                    }
                }

                override fun onSessionLimit(limit: SessionLimit) {
                    // Allowance exhausted, not a failure. The library has
                    // already delivered any closed segment, so settle the mic.
                    Log.i(TAG, "[vad] session limit $limit — stopping")
                    VoicePipelineLog.event("engine.sessionLimit", "limit" to limit.name)
                    stopVad()
                    // [T-android-vad-manual-stop] …but settling the mic is not
                    // a terminal callback, and SpeechRecognitionManager is left
                    // mid-session waiting for one. Flush whatever is held (and
                    // emit NO_MATCH when nothing is) so the state machine
                    // always returns to IDLE — same invariant as stop().
                    flushVadOnManualStop()
                }

                override fun onCaptureError(message: String) {
                    stopVad()
                    recording.set(false)
                    listener.onError(RecognitionError.AUDIO_ERROR, message)
                }

                // [T-voice-mic-preempted] Another app holds the mic.
                override fun onMicInUse(detail: String) {
                    Log.w(TAG, "[vad] microphone in use by another app: $detail")
                    stopVad()
                    recording.set(false)
                    listener.onError(RecognitionError.MIC_IN_USE, detail)
                }
            },
        ).also {
            // iOS splits this by engine: 59 s for Apple's system ASR (which
            // rejects >60 s per request) and the full 300 s session cap for
            // cloud providers, which have no such per-request ceiling
            // (VoiceInputPanel.swift:488-489). This IS the cloud path.
            it.maxSegmentSeconds = CLOUD_MAX_SEGMENT_SECONDS
        }

        val err = det.start()
        if (err != null) {
            recording.set(false)
            // [T-voice-mic-preempted] "not initialized" is usually another
            // app holding the input; blamed on it only with evidence.
            listener.onError(MicInUse.captureFailure(appContext), err)
            return
        }
        detector = det
        vadSession = VadSession(locale, repo, candidates, listener)
        listener.onReadyForSpeech()
    }

    /** Transcribe one segment. Extracted so the VAD and legacy paths share it. */
    private suspend fun transcribeSegment(
        wav: ByteArray,
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
    ) = transcribeAndDeliver(wav, locale, repo, candidates, listener, logEvents = false)

    /**
     * Transcribe [wav] with SEAMLESS fail-over across [candidates] (mirrors iOS
     * transcribeWithFailover): the last successful member goes first (sticky),
     * a candidate that throws advances to the next, and only when every
     * candidate fails does the error surface, with the audio handed back for
     * Retry / Discard.
     *
     * [T-android-voice-asr-stall-skip] Every attempt runs through
     * [asrAttempts]. An attempt with somewhere to go next — the next
     * candidate, or System recognition after the last one — is offered as
     * stalled after [ASR_STALL_THRESHOLD_MS]; a user skip continues the chain,
     * and when a skip exhausts it System recognition transcribes the same
     * audio. A chain that merely FAILS keeps the existing Retry / Discard
     * prompt.
     */
    private suspend fun transcribeAndDeliver(
        wav: ByteArray,
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
        logEvents: Boolean,
    ) {
        if (systemAsrPinned) {
            Log.i(TAG, "ASR: System recognition pinned for this take after a skip")
            transcribeWithSystem(wav, locale, listener)
            return
        }
        if (logEvents) VoicePipelineLog.event("engine.transcribe.begin", "candidates" to candidates.size)
        // Sticky-first try order: rotate the chain so the last successful
        // member goes first, the rest wrap around.
        val ordered = stickyEntryId
            ?.let { sticky -> candidates.indexOfFirst { it.second.id == sticky } }
            ?.takeIf { it > 0 }
            ?.let { i -> candidates.drop(i) + candidates.take(i) }
            ?: candidates
        val systemAvailable = systemEngine() != null
        var lastError: Exception? = null
        var userSkipped = false
        for ((i, pair) in ordered.withIndex()) {
            val (instance, entry) = pair
            if (cancelled.get()) return
            val provider = VoiceProviderFactory.make(instance, repo.loadApiKey(instance.id))
            if (provider == null) {
                Log.w(TAG, "candidate ${instance.label} cannot serve voice input — skipping")
                if (logEvents) VoicePipelineLog.event("engine.transcribe.skip", "model" to entry.baseModel.id)
                continue
            }
            val nextEntryId: String? = when {
                i + 1 < ordered.size -> ordered[i + 1].second.id
                systemAvailable -> SYSTEM_NEXT
                else -> null
            }
            val tryStartedMs = System.currentTimeMillis()
            if (logEvents) VoicePipelineLog.event("engine.transcribe.try", "model" to entry.baseModel.id)
            try {
                val response = asrAttempts.run(entry.model.displayName, nextEntryId, canSkip = nextEntryId != null) {
                    provider.transcribe(
                        VoiceInputRequest(
                            audioData = wav,
                            model = entry.baseModel.id,
                            language = locale.toLanguageTag(),
                            resolvedModel = entry.model,
                        ),
                    ).text
                }
                if (cancelled.get()) {
                    if (logEvents) VoicePipelineLog.event("engine.transcribe.cancelledAfterResponse")
                    return
                }
                stickyEntryId = entry.id
                val text = response.trim()
                // Length only — never the transcript.
                if (logEvents) {
                    VoicePipelineLog.event(
                        "engine.transcribe.ok",
                        "model" to entry.baseModel.id,
                        "ms" to (System.currentTimeMillis() - tryStartedMs),
                        "chars" to text.length,
                    )
                }
                if (text.isEmpty()) {
                    // A successful round-trip that heard nothing is a semantic
                    // no-match, not a provider failure — do NOT advance for it.
                    listener.onError(RecognitionError.NO_MATCH, "Empty transcription.")
                } else {
                    listener.onFinal(text)
                }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SkippableAsrAttempts.Skipped) {
                userSkipped = true
                lastError = e
                Log.i(TAG, "ASR fail-over: ${entry.model.displayName} skipped by the user after " +
                    "${ASR_STALL_THRESHOLD_MS / 1000}s without a result" +
                    if (i + 1 < ordered.size) " — trying next candidate" else " — falling back to System")
                if (logEvents) VoicePipelineLog.event("engine.transcribe.skipped", "model" to entry.baseModel.id)
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed on ${entry.model.displayName}: ${e.message} — trying next candidate")
                // Class name only: a provider message can echo the request.
                if (logEvents) {
                    VoicePipelineLog.event(
                        "engine.transcribe.fail",
                        "model" to entry.baseModel.id,
                        "ms" to (System.currentTimeMillis() - tryStartedMs),
                        "err" to VoicePipelineLog.errorKind(e),
                    )
                }
                lastError = e
            }
        }
        if (cancelled.get()) return
        // [T-android-voice-asr-stall-skip] The user chose to move on and the
        // chain has nothing left: System recognition is the last resort. Only
        // after a user skip — a chain that simply FAILED keeps Retry / Discard.
        if (userSkipped && systemAvailable) {
            systemAsrPinned = true
            Log.i(TAG, "ASR fail-over: chain exhausted after a user skip — using System recognition for this take")
            transcribeWithSystem(wav, locale, listener)
            return
        }
        if (logEvents) VoicePipelineLog.event("engine.transcribe.exhausted", "err" to VoicePipelineLog.errorKind(lastError))
        val e = lastError
        val kind = when {
            e is VoiceProviderException.Auth -> RecognitionError.PERMISSION_DENIED
            e is java.io.IOException -> RecognitionError.NETWORK
            else -> RecognitionError.UNKNOWN
        }
        // [T-voice-asr-failure-retry-prompt] Every candidate failed (or none
        // could serve): hand the audio back so the user is asked to retry
        // instead of losing the take.
        listener.onRetainedAudio(wav)
        listener.onError(kind, e?.message ?: "No usable voice-input model could transcribe this.")
    }

    /**
     * [T-android-voice-asr-stall-skip] System recognition as the last resort,
     * fed the same audio through [SystemSpeechRecognitionEngine.transcribeRetained]
     * (API 33+; below that it hands the audio back for Retry / Discard).
     */
    private fun transcribeWithSystem(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val system = systemEngine()
        if (system == null) {
            listener.onRetainedAudio(wav)
            listener.onError(RecognitionError.OEM_NO_SERVICE, "System speech recognition is not available.")
            return
        }
        // [T-android-voice-system-fallback-cancel] Only this take may hear
        // back; a newer take or a cancel supersedes it (see takeGeneration).
        val gen = takeGeneration.get()
        systemReplayGeneration.set(gen)
        system.transcribeRetained(wav, locale, object : SpeechRecognitionEngine.Listener {
            private fun live(what: String): Boolean =
                (takeGeneration.get() == gen).also { if (!it) Log.i(TAG, "ASR: dropped stale System $what (take superseded)") }
            override fun onPartial(text: String) { if (live("partial")) listener.onPartial(text) }
            override fun onFinal(text: String) { if (live("final")) listener.onFinal(text) }
            override fun onError(error: RecognitionError, message: String?) {
                if (live("error")) listener.onError(error, message)
            }
            override fun onRmsDb(rms: Float) { if (takeGeneration.get() == gen) listener.onRmsDb(rms) }
            override fun onReadyForSpeech() { if (takeGeneration.get() == gen) listener.onReadyForSpeech() }
            override fun onRetainedAudio(wav: ByteArray) { if (live("audio")) listener.onRetainedAudio(wav) }
        })
    }

    /**
     * [T-android-voice-system-fallback-cancel] Start a new take (a capture, a
     * retry, or a cancel): the previous take's System fallback, if it started
     * one, is cancelled and anything it still reports is dropped.
     */
    private fun supersedeTake() {
        val old = takeGeneration.getAndIncrement()
        if (systemReplayGeneration.compareAndSet(old, -1)) {
            Log.i(TAG, "ASR: take superseded — cancelling its System recognition fallback")
            systemEngine()?.cancel()
        }
    }

    /** The on-device/System recognizer, when it can replay retained audio (API 33+). */
    private fun systemEngine(): SystemSpeechRecognitionEngine? =
        SpeechRecognitionManager.availableEngines()
            .firstOrNull { it is SystemSpeechRecognitionEngine && it.isAvailable }
            ?.takeIf { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU }
            as? SystemSpeechRecognitionEngine

    /**
     * [T-android-voice-asr-stall-skip] The user tapped "Switch Model": abandon
     * every attempt stalled past the threshold. Each abandoned transcription
     * continues with its next voice model (System last); the audio is not lost.
     * The NEXT segments are re-pointed here, synchronously, so audio captured
     * after the tap does not queue on the server that just hung. Returns the
     * name of the model now used (null when nothing was stalled), for the toast.
     */
    fun skipStalledTranscription(systemName: String, genericName: String): String? {
        val nexts = asrAttempts.skipStalled()
        if (nexts.isEmpty()) return null
        val targets = nexts.filterNotNull()
        val nextCandidate = targets.firstOrNull { it != SYSTEM_NEXT }
            ?.let { id -> currentCandidates.firstOrNull { it.second.id == id } }
        val name = when {
            nextCandidate != null -> {
                stickyEntryId = nextCandidate.second.id
                nextCandidate.second.model.displayName
            }
            targets.contains(SYSTEM_NEXT) -> {
                systemAsrPinned = true
                systemName
            }
            // The candidate set changed under the skip: the abandoned attempts
            // still move on inside their own loops.
            else -> genericName
        }
        Log.i(TAG, "ASR stall: skip → next segments start on $name")
        return name
    }

    /**
     * [T-voice-asr-failure-retry-prompt] Retry a segment whose transcription
     * failed. Re-resolves the candidate chain (the user may have fixed a key or
     * switched model since), then goes through the same fail-over as a live
     * take. With no usable candidate the audio is handed back again, so a
     * "no provider" failure stays retryable rather than being dropped.
     */
    override fun transcribeRetained(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        if (repo == null || candidates.isEmpty()) {
            listener.onRetainedAudio(wav)
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
            )
            return
        }
        supersedeTake()
        cancelled.set(false)
        currentCandidates = candidates
        // [T-android-voice-asr-stall-skip] A retry re-resolves the chain, so a
        // System pin from an earlier skip no longer applies (iOS clears it when
        // the candidates are re-resolved); the retry starts on the chain again.
        systemAsrPinned = false
        VoicePipelineLog.event("engine.retry.begin", "candidates" to candidates.size)
        transcribeJob = scope.launch {
            transcribeSegment(wav, locale, repo, candidates, listener)
        }
    }

    private fun stopVad() {
        detector?.let { runCatching { it.stop() } }
        detector = null
        recording.set(false)
    }

    /**
     * [T-android-vad-manual-stop] Finalise a VAD take the user ended by hand.
     *
     * The detector emits a segment only when IT decides the utterance ended
     * (silence, length cap). `VoiceActivityDetector.stop()` is documented as
     * "any segment already delivered stands; nothing new is emitted", so
     * tearing it down on a manual stop meant the audio held in
     * [pendingSegments] was never merged, never transcribed, and — the part
     * the user actually sees — NO terminal callback ever reached
     * SpeechRecognitionManager. The manager had already moved to FINISHING, so
     * the panel sat on "Recognizing…" forever with no text and no error.
     *
     * This is the flush iOS performs with `vad.flush()` on a manual stop
     * (VoiceInputPanel.swift), and the case `SegmentEndReason.MANUAL_FLUSH`
     * was reserved for: take whatever is held, transcribe it, and when there
     * is nothing held still deliver an error so the state machine leaves
     * FINISHING. Every path out of here MUST end in onFinal or onError.
     */
    private fun flushVadOnManualStop() {
        val session = vadSession
        vadSession = null
        holdFlushJob?.cancel()
        holdFlushJob = null
        if (session == null) return
        val held = pendingSegments.toList()
        pendingSegments.clear()
        val merged = if (held.isEmpty()) null else WavSegmentMerger.merge(held) ?: held.firstOrNull()
        val seconds = if (held.isEmpty()) 0f else WavSegmentMerger.totalSeconds(held)
        VoicePipelineLog.event(
            "engine.manualStop.flush",
            "segments" to held.size,
            "seconds" to "%.2f".format(seconds),
        )
        val outcome = VadManualStopOutcome.decide(
            hasSession = true,
            heldSeconds = if (merged == null) 0f else seconds,
            floorSeconds = TOO_SHORT_TOAST_FLOOR,
        )
        if (outcome != VadManualStopOutcome.TRANSCRIBE || merged == null) {
            // Nothing usable — still a terminal callback, never silence.
            session.listener.onError(
                RecognitionError.NO_MATCH,
                "Too short — hold the mic and speak.",
            )
            return
        }
        transcribeJob = scope.launch {
            transcribeSegment(merged, session.locale, session.repo, session.candidates, session.listener)
        }
    }

    /** [T-android-vad] See SystemSpeechRecognitionEngine.setBackgrounded. */
    fun setBackgrounded(backgrounded: Boolean) {
        detector?.isBackgrounded = backgrounded
    }

    override fun stop() {
        // Flip the capture loop off. On the LEGACY path the capture coroutine
        // then hands the take to the transcription step itself, which delivers
        // onFinal/onError.
        recording.set(false)
        // [T-android-vad-manual-stop] The VAD path has no such coroutine: its
        // audio only ever leaves through the detector's onVoiceEnd callback,
        // so it must be flushed explicitly BEFORE the detector is destroyed.
        //
        // Two different buffers are in play and BOTH have to be collected:
        //  - the segment the library still has OPEN (the user tapped stop
        //    mid-sentence, so onVoiceEnd will never fire for it) — only
        //    VoiceActivityDetector.flush() can produce it;
        //  - segments already closed and held in pendingSegments for merge.
        val det = detector
        val wasVad = det != null
        val openSegment = det?.let { runCatching { it.flush() }.getOrNull() }
        if (openSegment != null) pendingSegments.add(openSegment)
        det?.let { runCatching { it.stop() } }
        detector = null
        if (wasVad) flushVadOnManualStop()
    }

    override fun cancel() {
        supersedeTake()
        cancelled.set(true)
        recording.set(false)
        transcribeJob?.cancel()
        holdFlushJob?.cancel()
        holdFlushJob = null
        // [T-android-vad-manual-stop] A cancelled session must not be
        // finalisable afterwards.
        vadSession = null
        // Drop held audio: a cancelled session's partial utterance must never
        // surface in a later one.
        pendingSegments.clear()
    }

    /** Rough dB estimate over the chunk for the UI waveform. */
    private fun rmsDb(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        if (rms <= 1.0) return 0f
        // Map amplitude RMS onto the [0, 12]-ish scale the System engine's
        // onRmsChanged reports, so the shared normalizer behaves identically.
        return (20 * log10(rms / 32768.0) + 50).toFloat().coerceIn(0f, 12f)
    }
}
