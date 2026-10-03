package com.openminis.app.speech

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Wraps [android.speech.SpeechRecognizer]. Handles OEM fragmentation —
 * especially Chinese ROMs where [SpeechRecognizer.isRecognitionAvailable]
 * can return `true` yet [SpeechRecognizer.startListening] immediately fires
 * ERROR_INSUFFICIENT_PERMISSIONS or ERROR_CLIENT. A first-failure observation
 * marks the engine permanently degraded for the rest of the process so the
 * UI can hide the mic button.
 *
 * Must be constructed and used on the main thread because [SpeechRecognizer]
 * delivers callbacks on the thread that creates it.
 */
class SystemSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    override val id: String = "system"
    override val displayName: String = "System recognizer"
    override val supportsPartialResults: Boolean = true

    /** Session-scoped degraded flag — set once a runtime error proves the service unusable. */
    @Volatile private var degraded: Boolean = false

    /** Cached supported locales (populated lazily on first request). */
    @Volatile private var cachedSupportedLocales: List<Locale>? = null

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechRecognitionEngine.Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override val isAvailable: Boolean
        get() {
            if (degraded) return false
            // Two-layer probe. The first returns true on many Chinese ROMs
            // that actually ship no real service — the intent query is the
            // backstop. Both cheap.
            val systemSaysYes = try { SpeechRecognizer.isRecognitionAvailable(appContext) }
                catch (_: Throwable) { false }
            if (!systemSaysYes) return false
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val services = try {
                appContext.packageManager.queryIntentServices(intent, 0)
            } catch (_: Throwable) { emptyList() }
            return services.isNotEmpty()
        }

    override val supportedLocales: List<Locale>
        get() = cachedSupportedLocales ?: fallbackLocales

    /**
     * Fire the [RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS] ordered broadcast
     * and cache the recognizer's reported supported languages. Safe to call
     * repeatedly — only the first call does work; subsequent calls short-circuit.
     *
     * The broadcast can take a few hundred ms and some OEM services never
     * respond, so [onResult] is guaranteed to fire exactly once — either with
     * the system's answer or with the curated fallback after a timeout.
     */
    fun fetchSupportedLocales(onResult: (List<Locale>) -> Unit) {
        cachedSupportedLocales?.let { onResult(it); return }

        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver(locales: List<Locale>) {
            if (fired.compareAndSet(false, true)) {
                cachedSupportedLocales = locales
                onResult(locales)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val tags = getResultExtras(true)
                    ?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    .orEmpty()
                val locales = tags.mapNotNull { tag ->
                    runCatching { Locale.forLanguageTag(tag) }.getOrNull()
                        ?.takeIf { !it.language.isNullOrEmpty() }
                }.distinct()
                if (locales.isNotEmpty()) deliver(locales) else deliver(fallbackLocales)
            }
        }
        try {
            appContext.sendOrderedBroadcast(
                Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
                null, receiver, mainHandler, 0, null, null,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "ACTION_GET_LANGUAGE_DETAILS failed: ${e.message}")
            deliver(fallbackLocales)
            return
        }
        // Backstop in case no one responds.
        mainHandler.postDelayed({ deliver(fallbackLocales) }, 2500)
    }

    /**
     * Curated locale list used when the system refuses to enumerate. Covers
     * the major languages that Google and most OEM recognizers support.
     */
    private val fallbackLocales: List<Locale> by lazy {
        listOf(
            // [T-android-asr-script-subtag] Script-stripped: the raw default on
            // a Simplified-Chinese device is zh-Hans-CN, which the recognizer
            // rejects. Since this list is also what SpeechRecognitionManager
            // re-validates the saved locale against, leaving the raw default
            // here made the broken locale look "supported" and the fallback
            // never fired.
            Locale.forLanguageTag(recognizerLanguageTag(Locale.getDefault())),
            Locale.forLanguageTag("en-US"),
            Locale.forLanguageTag("zh-CN"),
            Locale.forLanguageTag("zh-TW"),
            Locale.forLanguageTag("ja-JP"),
            Locale.forLanguageTag("ko-KR"),
            Locale.forLanguageTag("es-ES"),
            Locale.forLanguageTag("fr-FR"),
            Locale.forLanguageTag("de-DE"),
            Locale.forLanguageTag("it-IT"),
            Locale.forLanguageTag("pt-BR"),
            Locale.forLanguageTag("ru-RU"),
            Locale.forLanguageTag("ar-SA"),
            Locale.forLanguageTag("hi-IN"),
            Locale.forLanguageTag("id-ID"),
            Locale.forLanguageTag("th-TH"),
            Locale.forLanguageTag("vi-VN"),
            Locale.forLanguageTag("tr-TR"),
        ).distinctBy { it.toLanguageTag() }
    }

    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        this.listener = listener
        bufferedPartial = null
        heardSpeech = false
        sessionCommitted = false
        heldSpokenSeconds = 0f
        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        holdFlushRunnable = null
        cancelWatchdog()
        resetSessionPcm()
        replayPcm = null

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(RecognitionError.PERMISSION_DENIED, "RECORD_AUDIO not granted")
            return
        }

        // [T-android-vad] Start OUR capture first when we can feed the
        // recogniser, so the pipe already has audio by the time it starts
        // reading. When we can't, the recogniser opens the mic itself and no
        // VAD runs — see startEndpointingVad.
        segmentTranscript.setLength(0)
        startEndpointingVad()
        // [T-android-continuous-dictation] Only when we own the capture can we
        // survive the platform's ~1.5 s cut: restarting the recogniser is
        // harmless because the mic never changes hands. If the recogniser owns
        // the mic, a restart would re-acquire it each time, so we leave that
        // path on single-shot behaviour.
        continuousSession = feedingAudio
        continuousLocale = if (feedingAudio) locale else null
        startInternal(locale, allowOnDeviceRetry = true)
    }

    // ── [T-android-vad] Silero endpointing ────────────────────────────────

    /**
     * The VAD that decides when the user stopped talking, replacing the
     * platform recognizer's OEM-specific endpointing.
     *
     * It DOES capture the audio that gets recognised. An earlier attempt ran
     * the VAD on a second AudioRecord alongside the recogniser's own; that
     * does not work, because Android gives the real stream to one client and
     * starves the other. On a Pixel 6 it was the recogniser that lost: it
     * reported `onStartOfSpeech` and then `withSpeech: false`, having been
     * handed nothing to transcribe.
     *
     * So there is exactly ONE capture. Its frames feed the VAD and, through
     * `RecognizerIntent.EXTRA_AUDIO_SOURCE`, the recogniser as well. Ending
     * the session is then simply closing the pipe: "the recognition session
     * will end when and only when the audio is closed" — which stops the mic
     * and commits the transcript in one step, exactly like iOS's silence
     * auto-stop (VoiceInputPanel.swift:642-669).
     *
     * EXTRA_AUDIO_SOURCE is API 33+; below that, and whenever the pipe cannot
     * be set up, the recogniser opens the mic itself and no VAD runs.
     */
    @Volatile
    private var vad: VoiceActivityDetector? = null

    /** Wall-clock ms when the current VAD speech segment opened; 0 when idle. */
    @Volatile
    private var speechStartedAt: Long = 0L

    /**
     * [T-android-asr-silent-failure] Sticky per-dictation flag: the VAD saw at
     * least one voiced segment since start(). Unlike [speechStartedAt] it is
     * NOT cleared at segment end — it exists so the error paths can tell
     * "user never spoke" (NO_MATCH, stays quiet) from "user spoke and the
     * recognizer returned nothing" (TRANSCRIPTION_FAILED, must be visible).
     */
    @Volatile
    private var heardSpeech = false

    /**
     * [T-android-asr-rom-compat] True once this session has delivered its
     * terminal outcome (final text or error). The segmented-session contract
     * says onEndOfSegmentedSession REPLACES onResults, but that's Google's
     * recognizer — a nonconforming OEM implementation may fire both, or tack
     * an error on after the result. Without this guard the second callback
     * re-committed against an already-cleared transcript: a blank commit with
     * heardSpeech still true, i.e. a spurious TRANSCRIPTION_FAILED error
     * popping up right after a successful dictation.
     */
    @Volatile
    private var sessionCommitted = false

    /**
     * [T-android-vad-merge-segments] Speech seconds handed to the recognizer
     * across consecutive sub-threshold bursts. The 2 s floor applies to this
     * total, not to any single burst.
     */
    private var heldSpokenSeconds = 0f
    private var holdFlushRunnable: Runnable? = null

    /**
     * Write end of the pipe handed to the recogniser via EXTRA_AUDIO_SOURCE.
     * Closing it is what ends the recognition session, so it is the single
     * mechanism by which our VAD controls endpointing.
     */
    @Volatile
    private var audioPipeWrite: android.os.ParcelFileDescriptor? = null

    @Volatile
    private var audioPipeStream: java.io.OutputStream? = null

    /** Read end, handed to the recogniser; closed once it has taken ownership. */
    @Volatile
    private var audioPipeRead: android.os.ParcelFileDescriptor? = null

    /**
     * True when this session feeds the recogniser our own audio. False on
     * API < 33 (EXTRA_AUDIO_SOURCE did not exist) or when the pipe could not
     * be created — in which case the recogniser opens the mic itself and no
     * VAD runs, because a second AudioRecord would starve it.
     */
    @Volatile
    private var feedingAudio: Boolean = false

    /**
     * [T-android-continuous-dictation] True from the user's mic tap until the
     * user taps again. While set, a platform-initiated end of session is a
     * SEGMENT boundary, not the end of dictation, and we immediately open a
     * new recogniser over the same capture.
     *
     * Why: `SpeechRecognizer` runs its own endpointing on whatever audio it is
     * given — even audio we supply through EXTRA_AUDIO_SOURCE — and cuts after
     * roughly 1.5 s of silence. The silence extras that would widen that are
     * advisory and Google's recogniser ignores them, so a single session
     * cannot be held open across a natural pause. Restarting is the only way
     * to make "keep listening until I say stop" true on this engine.
     *
     * The mic is NOT re-acquired on restart: our AudioRecord runs for the whole
     * dictation and each new recogniser reads a fresh pipe fed from it. That is
     * what stops the restart from becoming a mic-grab fight.
     */
    @Volatile
    private var continuousSession: Boolean = false

    /** Locale of the running dictation, so a restart reuses it. */
    @Volatile
    private var continuousLocale: Locale? = null

    /**
     * Text committed by earlier segments of this dictation. Each restart makes
     * the platform emit its own independent result, so the caller would
     * otherwise see only the last fragment.
     */
    private val segmentTranscript = StringBuilder()

    private fun startEndpointingVad() {
        // EXTRA_AUDIO_SOURCE is API 33+. Below that the recogniser must open
        // the mic itself, so we cannot run a VAD at all: two AudioRecords on
        // one device leaves one of them silent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "[vad] API ${Build.VERSION.SDK_INT} < 33 — platform endpointing")
            return
        }
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[vad] pipe creation failed, platform endpointing: ${t.message}")
            return
        }
        audioPipeRead = pipe[0]
        audioPipeWrite = pipe[1]
        audioPipeStream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val det = VoiceActivityDetector(
            appContext,
            object : VoiceActivityListener {
                override fun onVoiceStart() {
                    speechStartedAt = System.currentTimeMillis()
                    heardSpeech = true
                    Log.i(TAG, "[vad] speech start")
                }

                override fun onLevel(level: Float) {
                    // Drive the waveform from the VAD's AGC-boosted tap rather
                    // than onRmsChanged: it updates ~10x more often and uses
                    // the same perceptual curve as iOS. Mapped back onto the
                    // [0,12] scale SpeechRecognitionManager normalises from.
                    listener?.onRmsDb(level * 12f)
                }

                override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
                    if (reason != SegmentEndReason.SILENCE_DETECTED) return
                    speechStartedAt = 0L

                    // iOS discards a silence-closed segment under 2.0 s
                    // outright (VoiceInputPanel.swift:652-663) — a cough or a
                    // door shouldn't commit text. Cancel rather than stop so
                    // the recognizer produces no result at all.
                    //
                    // spokenSeconds is measured from the audio payload; a
                    // wall-clock figure would include the ~5 s silence that
                    // closed the segment and could never fall below 2 s.
                    // [T-android-vad-merge-segments] Accumulate DURATION, don't
                    // cancel.
                    //
                    // The Provider engine can merge WAV payloads, but this path
                    // can't: the audio has ALREADY been streamed into the
                    // recognizer's pipe by the time onVoiceEnd fires, so there
                    // is nothing left to concatenate. The equivalent fix is to
                    // keep the session open — a sub-2s burst just isn't a
                    // reason to tear it down. iOS applies its 2 s floor to the
                    // running total (VoiceInputPanel.swift:651); here the
                    // recognizer holds the audio and we only track how much
                    // speech it has been given.
                    //
                    // Without this, natural stop-start speech — "yes" (0.8 s),
                    // pause, "send it" (0.9 s) — cancelled on the first burst
                    // and could never be dictated.
                    heldSpokenSeconds += spokenSeconds
                    if (heldSpokenSeconds < MIN_SEGMENT_SECONDS) {
                        Log.i(
                            TAG,
                            "[vad] silence close, spoken=${"%.2f".format(spokenSeconds)}s " +
                                "(held ${"%.2f".format(heldSpokenSeconds)}s) — holding session open",
                        )
                        // Force-flush after HOLD_FLUSH_MS so an isolated cough
                        // still settles instead of pinning the mic open. The
                        // recognizer's own session cap still bounds the total.
                        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
                        val flush = Runnable {
                            val held = heldSpokenSeconds
                            Log.i(TAG, "[vad] hold expired at ${"%.2f".format(held)}s — settling")
                            stopVad()
                            cancel()
                            if (held > TOO_SHORT_FLOOR_SECONDS) {
                                listener?.onError(
                                    RecognitionError.NO_MATCH,
                                    "Too short — tap the mic and speak.",
                                )
                            }
                        }
                        holdFlushRunnable = flush
                        mainHandler.postDelayed(flush, HOLD_FLUSH_MS)
                        return
                    }
                    holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
                    holdFlushRunnable = null
                    Log.i(TAG, "[vad] silence close, spoken=${"%.2f".format(spokenSeconds)}s — finalising")
                    finaliseSession()
                }

                override fun onSessionLimit(limit: SessionLimit) {
                    // Not a failure — the session ran out its allowance. Commit
                    // whatever the recognizer has rather than discarding it, so
                    // a long dictation that hits the 300 s ceiling is not lost.
                    Log.i(TAG, "[vad] session limit $limit — finalising")
                    finaliseSession()
                }

                override fun onCaptureError(message: String) {
                    // Endpointing died; leave the recognizer running on the
                    // platform's own timing rather than stranding the user
                    // with a mic that never stops.
                    Log.w(TAG, "[vad] endpointing unavailable, platform timing applies: $message")
                    stopVad()
                }

                // [T-voice-mic-preempted] The shared capture is silent or
                // gone because another app holds the mic, so the recogniser
                // would only wait out its timeout: end the take now and say why.
                override fun onMicInUse(detail: String) {
                    Log.w(TAG, "[vad] microphone in use by another app: $detail")
                    mainHandler.post {
                        stopVad()
                        cancel()
                        listener?.onError(RecognitionError.MIC_IN_USE, detail)
                    }
                }
            },
        )
        // ONE capture serves both: the VAD judges these samples and the very
        // same frames go down the pipe to the recogniser.
        det.rawAudioSink = { bytes, len ->
            // [T-voice-asr-failure-retry-prompt] Keep a copy of what the
            // recognizer is fed, so a failed take can be retried instead of
            // re-dictated.
            retainPcm(bytes, len)
            val out = audioPipeStream
            if (out != null) {
                try {
                    out.write(bytes, 0, len)
                } catch (t: Throwable) {
                    // Recogniser closed its end (finished or died). Stop
                    // writing; the session is over either way.
                    Log.i(TAG, "[vad] audio pipe closed by reader: ${t.javaClass.simpleName}")
                    audioPipeStream = null
                }
            }
        }
        when (val err = det.start()) {
            null -> {
                vad = det
                feedingAudio = true
                Log.i(TAG, "[vad] capture started, feeding recogniser via EXTRA_AUDIO_SOURCE")
            }
            else -> {
                // Capture failed: tear the pipe down so the recogniser is never
                // handed a source that will stay empty forever, and let it open
                // the mic itself.
                Log.w(TAG, "[vad] could not start, platform endpointing applies: $err")
                closeAudioPipe()
            }
        }
    }

    /**
     * End the recognition session the way EXTRA_AUDIO_SOURCE defines it:
     * "the recognition session will end when and only when the audio is
     * closed". Closing the write end makes the recogniser finalise and deliver
     * onResults, so this both stops the mic and commits the transcript — the
     * single step iOS performs on a silence close.
     *
     * When we are NOT feeding audio (API < 33, or the pipe failed) there is no
     * stream to close and stopListening() is the only lever.
     */
    private fun finaliseSession() {
        // stop() already routes both cases correctly: it closes the pipe when
        // we own the audio and always forwards stopListening().
        stop()
    }

    /** Everything heard this dictation, including the segment just closed. */
    private fun joinedTranscript(lastSegment: String): String {
        if (segmentTranscript.isEmpty()) return lastSegment
        if (lastSegment.isEmpty()) return segmentTranscript.toString()
        return "$segmentTranscript $lastSegment"
    }

    /**
     * [T-android-continuous-dictation] Open a new recogniser for the next
     * segment, leaving the microphone capture untouched.
     *
     * Only the pipe is rebuilt: the previous recogniser consumed its read end
     * and will not read another byte, so a fresh pair is handed to the new one
     * and the VAD's sink is repointed at the new write end. The AudioRecord
     * behind it never stops, which is what keeps this from turning into the
     * mic-grab fight that a full stop/start would cause.
     */
    private fun restartRecogniserForNextSegment() {
        val locale = continuousLocale ?: run {
            Log.w(TAG, "[continuous] no locale to restart with — ending dictation")
            endContinuousSession()
            return
        }
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null

        // Retire the consumed pipe and hand the capture a fresh one.
        closeAudioPipe()
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[continuous] could not re-open pipe — ending dictation: ${t.message}")
            endContinuousSession()
            return
        }
        audioPipeRead = pipe[0]
        audioPipeWrite = pipe[1]
        audioPipeStream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        feedingAudio = true

        startInternal(locale, allowOnDeviceRetry = false)
    }

    /**
     * End a continuous dictation and deliver everything heard. Used when the
     * user stops, and when a restart cannot continue.
     */
    private fun endContinuousSession() {
        continuousSession = false
        continuousLocale = null
        val all = joinedTranscript(bufferedPartial.orEmpty())
        segmentTranscript.setLength(0)
        bufferedPartial = null
        if (all.isNotEmpty()) listener?.onFinal(all) else listener?.onFinal("")
        tearDown()
    }

    private fun closeAudioPipe() {
        audioPipeStream?.let { runCatching { it.close() } }
        audioPipeStream = null
        audioPipeWrite = null
        audioPipeRead?.let { runCatching { it.close() } }
        audioPipeRead = null
        feedingAudio = false
    }

    private fun stopVad() {
        vad?.let {
            it.rawAudioSink = null
            runCatching { it.stop() }
        }
        vad = null
        speechStartedAt = 0L
    }

    /**
     * [T-android-vad] Told by [SpeechRecognitionManager] when the app moves
     * between foreground and background, so the detector can apply iOS's
     * 15 s backgrounded stop (VoiceInputPanel.swift:285-295). Recording
     * survives a brief switch away but not a real one.
     */
    fun setBackgrounded(backgrounded: Boolean) {
        vad?.isBackgrounded = backgrounded
    }

    /**
     * [T-android-asr-ondevice-fallback] Start a session.
     *
     * [allowOnDeviceRetry] is true for the first attempt; when the on-device
     * (SODA) recognizer answers ERROR_LANGUAGE_UNAVAILABLE we restart once with
     * it false, which forces the regular (typically cloud) recognizer.
     */
    private fun startInternal(locale: Locale, allowOnDeviceRetry: Boolean) {
        mainHandler.post {
            try {
                // Only reach for the on-device recognizer when the user actually
                // asked for offline. It used to be created unconditionally on
                // S+, which silently overrode the panel's "System Recognition
                // (Online)" choice — and on a device with no on-device pack for
                // the locale (e.g. a zh-CN Pixel 4a) SODA fails every session
                // with "Failed to get language pack" / ERROR_LANGUAGE_
                // UNAVAILABLE(12), so the mic opened and closed instantly.
                val wantOnDevice = preferOffline &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    allowOnDeviceRetry
                val r = if (wantOnDevice) {
                    try { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
                    catch (_: Throwable) { SpeechRecognizer.createSpeechRecognizer(appContext) }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
                usingOnDevice = wantOnDevice
                pendingLocale = locale
                recognizer = r
                r.setRecognitionListener(callbacks)
                r.startListening(buildIntent(locale))
            } catch (e: Throwable) {
                Log.w(TAG, "startListening threw: ${e.message}")
                this.listener?.onError(RecognitionError.UNKNOWN, e.message)
                markDegraded()
                tearDown()
            }
        }
    }

    /** True when the live session is using the on-device recognizer. */
    @Volatile private var usingOnDevice: Boolean = false

    /** Locale of the live session, so a fallback restart can reuse it. */
    @Volatile private var pendingLocale: Locale? = null

    override fun stop() {
        // [T-android-vad] Order matters. stopListening() first, so the
        // recogniser finalises what it already has and delivers onResults;
        // only then close the pipe. Closing first (which segmented-session
        // mode required) meant the audio source vanished before the result was
        // routed, and onResults never reached us.
        //
        // Our capture is stopped here because nothing else will — the
        // recogniser only owns the pipe, not the AudioRecord behind it.
        mainHandler.post {
            // [T-android-continuous-dictation] Disarm BEFORE stopListening, so
            // the onResults it triggers ends the dictation instead of starting
            // another segment.
            continuousSession = false
            continuousLocale = null

            // Stop feeding first, THEN ask the recogniser to finalise.
            //
            // Closing the pipe is what tells the recogniser the audio has
            // ended; without it, stopListening() leaves it waiting on a stream
            // that is still open and it finalises on whatever it has buffered.
            // Doing this in the other order (stopListening, then close) meant
            // the recogniser evaluated a stream that our capture was still
            // writing to and repeatedly answered NO_SPEECH_DETECTED even though
            // the user had spoken — the "recording silently discarded" report.
            stopVad()
            closeAudioPipe()
            try { recognizer?.stopListening() }
            catch (e: Throwable) { Log.w(TAG, "stopListening: ${e.message}") }
            // [T-voice-asr-failure-retry-prompt] Never hang in "Recognizing…":
            // a send waiting on this take would otherwise sit out its full
            // minute. Scaled with the audio so long dictation gets its time.
            if (recognizer != null && !sessionCommitted) {
                armWatchdog(VoiceAsrWatchdog.pcmSeconds(retainedPcmSize(), VoiceActivityDetector.SAMPLE_RATE))
            }
        }
    }

    // ── [T-voice-asr-failure-retry-prompt] Kept audio, replay, watchdog ────

    /**
     * Raw PCM16 mono of the current take at [VoiceActivityDetector.SAMPLE_RATE],
     * exactly as fed to the recognizer. Held as chunks (no doubling copies)
     * and capped at the VAD's 300 s session ceiling. Empty when the recognizer
     * opened the mic itself (API < 33), in which case nothing can be retried.
     */
    private val sessionPcm = ArrayList<ByteArray>()
    private var sessionPcmBytes = 0
    private val sessionPcmLock = Any()

    private fun resetSessionPcm() = synchronized(sessionPcmLock) {
        sessionPcm.clear()
        sessionPcmBytes = 0
    }

    /** PCM being replayed by [transcribeRetained]; survives the on-device fallback restart. */
    @Volatile
    private var replayPcm: ByteArray? = null

    private var watchdogRunnable: Runnable? = null

    private fun retainPcm(bytes: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(sessionPcmLock) {
            if (sessionPcmBytes + len > MAX_RETAINED_PCM_BYTES) return
            sessionPcm.add(bytes.copyOf(len))
            sessionPcmBytes += len
        }
    }

    private fun retainedPcmSize(): Int = synchronized(sessionPcmLock) { sessionPcmBytes }

    private fun retainedPcm(): ByteArray = synchronized(sessionPcmLock) {
        val out = ByteArray(sessionPcmBytes)
        var pos = 0
        for (c in sessionPcm) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        out
    }

    /**
     * Hand the take's audio to the listener just before a terminal onError,
     * so SpeechRecognitionManager can offer a retry.
     */
    private fun handBackAudio() {
        val pcm = retainedPcm()
        if (pcm.isEmpty()) return
        listener?.onRetainedAudio(
            com.openminis.app.provider.voice.VoiceProvider.wrapPcm16InWav(pcm, VoiceActivityDetector.SAMPLE_RATE),
        )
    }

    /**
     * Watchdog over one recognition. Scales with the audio (see
     * [VoiceAsrWatchdog]); on expiry, whatever text the recognizer produced is
     * committed, and "nothing at all" is a TIMED_OUT failure with the audio
     * kept — not a "no speech" result that silently drops it.
     */
    private fun armWatchdog(audioSeconds: Double) {
        cancelWatchdog()
        val seconds = VoiceAsrWatchdog.seconds(audioSeconds)
        val r = Runnable {
            watchdogRunnable = null
            if (sessionCommitted || listener == null) return@Runnable
            sessionCommitted = true
            val salvage = joinedTranscript(bufferedPartial.orEmpty())
            try { recognizer?.cancel() } catch (_: Throwable) {}
            if (salvage.isNotEmpty()) {
                Log.i(TAG, "[watchdog] fired after ${seconds.toInt()}s — committing ${salvage.length} salvaged chars")
                listener?.onFinal(salvage)
            } else {
                Log.w(TAG, "[watchdog] fired after ${seconds.toInt()}s with no text — reporting timeout")
                handBackAudio()
                listener?.onError(RecognitionError.TIMED_OUT, "Speech recognition timed out")
            }
            tearDown()
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, (seconds * 1000).toLong())
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    /**
     * Re-recognize a take kept from a failed session by feeding its PCM to a
     * fresh recognizer through EXTRA_AUDIO_SOURCE — the same pipe live capture
     * uses, minus the microphone. Needs API 33+; below that the audio is
     * handed straight back (still retryable / discardable by the user).
     */
    override fun transcribeRetained(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val header = VoiceActivityDetector.WAV_HEADER_BYTES
        val pcm = if (wav.size > header) wav.copyOfRange(header, wav.size) else ByteArray(0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || pcm.isEmpty()) {
            listener.onRetainedAudio(wav)
            listener.onError(RecognitionError.UNKNOWN, "Re-transcribing saved audio needs Android 13 or later")
            return
        }
        mainHandler.post {
            this.listener = listener
            bufferedPartial = null
            // The take was already judged to contain speech: an empty result
            // is a failure (kept, asked again), not "you said nothing".
            heardSpeech = true
            sessionCommitted = false
            heldSpokenSeconds = 0f
            segmentTranscript.setLength(0)
            continuousSession = false
            continuousLocale = null
            startReplay(pcm, locale, allowOnDeviceRetry = true)
        }
    }

    private fun startReplay(pcm: ByteArray, locale: Locale, allowOnDeviceRetry: Boolean) {
        replayPcm = pcm
        synchronized(sessionPcmLock) {
            sessionPcm.clear()
            sessionPcm.add(pcm)
            sessionPcmBytes = pcm.size
        }
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[replay] pipe creation failed: ${t.message}")
            sessionCommitted = true
            handBackAudio()
            listener?.onError(RecognitionError.UNKNOWN, t.message)
            tearDown()
            return
        }
        audioPipeRead = pipe[0]
        audioPipeWrite = pipe[1]
        val out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        audioPipeStream = out
        feedingAudio = true
        // Write on a worker: the pipe holds ~64 KB and blocks until the
        // recognizer reads. Closing the write end is what tells it the audio
        // has ended and makes it deliver its result.
        Thread({
            try {
                var pos = 0
                while (pos < pcm.size) {
                    val n = minOf(REPLAY_CHUNK_BYTES, pcm.size - pos)
                    out.write(pcm, pos, n)
                    pos += n
                }
            } catch (t: Throwable) {
                Log.i(TAG, "[replay] pipe closed early: ${t.javaClass.simpleName}")
            } finally {
                runCatching { out.close() }
            }
        }, "asr-replay").start()
        startInternal(locale, allowOnDeviceRetry)
        armWatchdog(VoiceAsrWatchdog.pcmSeconds(pcm.size, VoiceActivityDetector.SAMPLE_RATE))
    }

    override fun cancel() {
        mainHandler.post {
            try { recognizer?.cancel() }
            catch (e: Throwable) { Log.w(TAG, "cancel: ${e.message}") }
            tearDown()
        }
    }

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    private fun tearDown() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        // [T-android-vad] The VAD holds the mic; leaking it past a session
        // would keep recording after the user stopped. The pipe must go too —
        // a dangling write end keeps a stale reader alive.
        stopVad()
        closeAudioPipe()
        bufferedPartial = null
        // [T-android-continuous-dictation] A stale arm would make the NEXT
        // session restart on its first platform cut, before start() has set it.
        continuousSession = false
        continuousLocale = null
        segmentTranscript.setLength(0)
        // [T-android-vad-merge-segments] Drop the hold: a pending flush firing
        // into a torn-down session would stop a VAD that no longer exists and
        // emit a "too short" toast for audio the user already abandoned.
        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        holdFlushRunnable = null
        heldSpokenSeconds = 0f
        // [T-voice-asr-failure-retry-prompt] Every terminal path hands the
        // audio back BEFORE calling tearDown, so it is safe to drop here.
        cancelWatchdog()
        resetSessionPcm()
        replayPcm = null
    }

    /**
     * [T-android-vad] Last interim hypothesis, held back from the UI. See
     * onPartialResults — this is the salvage path for a recognizer that never
     * emits a final, not a live preview.
     */
    @Volatile
    private var bufferedPartial: String? = null

    /**
     * [T-android-voice-panel] Prefer fully on-device recognition (maps the
     * panel's "System Recognition (Offline)" pick). Best-effort: the OS may
     * still fall back when no offline pack is installed for the locale.
     */
    @Volatile var preferOffline: Boolean = false

    /**
     * [T-android-asr-script-subtag] Strip the SCRIPT subtag from a language tag
     * before handing it to the recognizer.
     *
     * `Locale.getDefault()` on a Simplified-Chinese device is `zh-Hans-CN`, and
     * `toLanguageTag()` faithfully returns "zh-Hans-CN". Google's recognizer
     * matches its language packs on plain `language-REGION` tags, so the script
     * subtag makes it report "Locale not supported" / "Failed to get language
     * pack of required locale" and fail the session with ERROR_LANGUAGE_
     * UNAVAILABLE (12) — the mic opens and immediately closes. Observed on a
     * Pixel 4a whose system locale is zh-Hans-CN.
     *
     * Reducing to language(+region) keeps the meaningful part ("zh-CN") and is
     * safe for every other locale: tags without a script subtag are unchanged.
     */
    private fun recognizerLanguageTag(locale: Locale): String {
        val lang = locale.language
        var region = locale.country
        // A script-only locale carries no region ("zh-Hans"), and a bare "zh"
        // is just as unresolvable to the recognizer as "zh-Hans" — it needs a
        // concrete language pack. Derive the region from the script, which is
        // exactly the information the script subtag encodes.
        if (region.isNullOrEmpty() && lang == "zh") {
            region = if (locale.script == "Hant") "TW" else "CN"
        }
        // Last resort for any other script-only/region-less locale: ask ICU to
        // fill in the likely region (zh-Hans -> zh-Hans-CN, etc.).
        if (region.isNullOrEmpty()) {
            region = runCatching { Locale.Builder().setLocale(locale).build() }
                .getOrNull()?.country.orEmpty()
        }
        return if (region.isEmpty()) lang else "$lang-$region"
    }

    private fun buildIntent(locale: Locale): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLanguageTag(locale))
            // [T-android-vad] Partials are still REQUESTED but never surfaced —
            // see onPartialResults. iOS does exactly this: its
            // SFSpeechURLRecognitionRequest sets shouldReportPartialResults =
            // true purely as a salvage path when the recognizer never emits a
            // final (VoiceProvider+System.swift:139-143), and buffers the text
            // in a local rather than showing it.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // [T-android-vad] Endpointing now belongs to our Silero VAD, so ask
            // the platform to sit still and let it decide. These extras are
            // advisory (Google's recognizer has largely ignored them since
            // Android 4.x), which is precisely why we can no longer rely on
            // them: the same app segmented differently per OEM. Padding them
            // well past the VAD's ~5 s window keeps the platform from cutting
            // a segment before the VAD does on ROMs that DO honour them.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)

            // [T-android-vad] Feed OUR capture instead of letting the
            // recogniser open the mic. This is what makes a VAD possible at
            // all: two AudioRecords on one device leaves one silent, and it
            // was the recogniser that starved — it reported onStartOfSpeech
            // and then `withSpeech: false`, having been given no audio.
            //
            // With a single source the VAD judges the same samples the
            // recogniser transcribes, and closing the pipe ends the session:
            // "The recognition session will end when and only when the audio
            // is closed" (RecognizerIntent.EXTRA_AUDIO_SOURCE).
            audioPipeRead?.let { readEnd ->
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceActivityDetector.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                // [T-android-asr-silent-failure] EXTRA_SEGMENTED_SESSION is
                // back ON — and this time the segment callbacks below are
                // implemented, which is the piece the first attempt missed.
                //
                // History: the extra was originally set, results went to
                // onSegmentResults (unimplemented) and the panel spun forever,
                // so the "fix" was to drop the extra. That made things WORSE,
                // not better: Google's recognizer classifies an injected-audio
                // session as AMBIENT_CONTINUOUS with multi-utterance
                // endpointing REGARDLESS (its own logcat: `Initialize Soda
                // [applicationDomain: AMBIENT_CONTINUOUS]`, `multi = true`).
                // Per-utterance finals then had no compliant delivery path:
                // every terminal onResults arrived with an EMPTY
                // RESULTS_RECOGNITION (finalLen=0 on 100% of sessions in
                // minis-2026-08-16.log) while Google's own process logged
                // `#onResults withSpeech: true` two ms earlier — the text
                // existed and went nowhere. The engine then salvaged only the
                // LAST buffered partial, which resets at every internal
                // utterance boundary: a 30.72 s dictation committed 3 chars
                // (13:58:42), and when the tail utterance held no clean speech
                // the whole session collapsed to ERROR_NO_MATCH (the 13:58 /
                // 14:29 error-7 bursts) even though the VAD heard speech.
                //
                // The documented contract (RecognizerIntent.EXTRA_SEGMENTED_
                // SESSION): with the extra set to EXTRA_AUDIO_SOURCE, results
                // arrive per segment via onSegmentResults and the session
                // terminates with onEndOfSegmentedSession when the audio
                // closes. Both are implemented below; segment finals accumulate
                // in segmentTranscript, so nothing rides on the partial-salvage
                // path anymore.
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_AUDIO_SOURCE,
                )
            }
        }
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { listener?.onReadyForSpeech() }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onRmsChanged(rms: Float) { listener?.onRmsDb(rms) }

        /**
         * [T-android-vad] Partials are BUFFERED, never forwarded.
         *
         * This is the behavioural change that removes live streaming: text no
         * longer appears word-by-word as you speak. It is held here and only
         * committed once the utterance is complete, matching iOS, where the
         * recognizer's partials likewise never reach the UI
         * (VoiceProvider+System.swift:173-187 keeps them in a local `latestText`).
         *
         * Kept rather than disabled because it is the salvage path: some
         * recognizers fail to emit a final result on the first request or two
         * after a cold start, and without this the whole utterance would be
         * lost. [flushBufferedPartial] delivers it if that happens.
         */
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isEmpty()) return
            // [T-android-asr-silent-failure] Fold an utterance-boundary reset
            // into the transcript instead of overwriting it. In multi-utterance
            // (AMBIENT_CONTINUOUS) sessions the hypothesis stream RESTARTS at
            // every internal endpoint: partials for utterance N+1 begin from
            // scratch, so plain `bufferedPartial = text` threw away everything
            // utterance N had said whenever its final went missing — the
            // observed "30.72 s of speech commits 3 chars". A reset is
            // recognised by the new hypothesis being much shorter than, and
            // not a prefix-refinement of, the buffered one; mid-utterance
            // rewrites keep roughly monotonic length. With onSegmentResults
            // implemented the finals normally land there first (which clears
            // the buffer), so this only fires when a segment final was
            // genuinely dropped — exactly the case worth salvaging.
            val prev = bufferedPartial
            if (prev != null && prev.length > text.length * 2 && !prev.startsWith(text)) {
                Log.i(TAG, "[partial] reset detected (${prev.length}→${text.length}) — folding previous into transcript")
                if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                segmentTranscript.append(prev)
            }
            bufferedPartial = text
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            // Prefer the final; fall back to the last partial when the
            // recognizer returned an empty final but did hear something.
            val committed = text.ifEmpty { bufferedPartial.orEmpty() }
            Log.i(
                TAG,
                "onResults finalLen=${text.length} bufferedLen=${bufferedPartial?.length ?: 0} " +
                    "committedLen=${committed.length} hasListener=${listener != null}",
            )
            bufferedPartial = null

            // [T-android-continuous-dictation] The platform ended ITS session,
            // which after ~1.5 s of silence is a pause, not the end of what the
            // user is saying. Keep the transcript, open a new recogniser over
            // the same running capture, and only surface text when the user
            // actually stops.
            if (continuousSession) {
                if (committed.isNotEmpty()) {
                    if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                    segmentTranscript.append(committed)
                }
                Log.i(TAG, "[continuous] segment done (+${committed.length}), restarting recogniser")
                restartRecogniserForNextSegment()
                return
            }

            commitFinal(joinedTranscript(committed))
        }

        /**
         * [T-android-asr-silent-failure] Segmented-session delivery — the
         * documented result path for an EXTRA_AUDIO_SOURCE session (see
         * buildIntent). Each internal utterance's FINAL lands here; they
         * accumulate in segmentTranscript so the terminal commit carries the
         * whole dictation instead of whatever partial happened to be last.
         */
        override fun onSegmentResults(segmentResults: Bundle) {
            val text = segmentResults
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.i(TAG, "[segmented] segment result len=${text.length}")
            if (text.isNotEmpty()) {
                if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                segmentTranscript.append(text)
                // The segment final supersedes its own partials; a stale one
                // must not be re-folded into the next segment's salvage.
                bufferedPartial = null
            }
        }

        /**
         * [T-android-asr-silent-failure] Terminal callback of a segmented
         * session — fires when the audio source closes (our VAD's silence
         * close / the user's stop tap). Commits everything accumulated plus
         * any trailing partial the last segment never finalised.
         */
        override fun onEndOfSegmentedSession() {
            val tail = bufferedPartial.orEmpty()
            bufferedPartial = null
            Log.i(
                TAG,
                "[segmented] session end segLen=${segmentTranscript.length} tailLen=${tail.length}",
            )
            continuousSession = false
            continuousLocale = null
            commitFinal(joinedTranscript(tail))
        }

        /**
         * [T-android-asr-silent-failure] Single terminal-commit path shared by
         * onResults and onEndOfSegmentedSession.
         *
         * A blank commit after the VAD heard speech is a REAL failure — the
         * user spoke and the recognizer returned nothing — and used to vanish
         * (onFinal("") is discarded by the panel's blank guard). Surface it as
         * TRANSCRIPTION_FAILED, which the panel renders, unlike NO_MATCH.
         */
        private fun commitFinal(text: String) {
            // [T-android-asr-rom-compat] One terminal outcome per session —
            // see sessionCommitted. Second/duplicate callbacks are dropped.
            if (sessionCommitted) {
                Log.i(TAG, "[commit] duplicate terminal callback ignored (len=${text.length})")
                return
            }
            sessionCommitted = true
            if (text.isBlank() && heardSpeech) {
                Log.w(TAG, "[commit] speech heard but zero text — surfacing TRANSCRIPTION_FAILED")
                // [T-voice-asr-failure-retry-prompt] Keep the take for a retry.
                handBackAudio()
                listener?.onError(
                    RecognitionError.TRANSCRIPTION_FAILED,
                    "Speech detected but nothing was transcribed. If this keeps happening, " +
                        "install the offline language pack in system voice-input settings, " +
                        "or switch to a provider recognizer.",
                )
                tearDown()
                return
            }
            listener?.onFinal(text)
            tearDown()
        }

        override fun onError(errorCode: Int) {
            // [T-voice-mic-preempted] ERROR_AUDIO while a call owns the audio
            // path is the mic being taken, not a broken recognizer - and must
            // not mark the engine degraded for the rest of the process below.
            val err = mapError(errorCode).let {
                if (it == RecognitionError.AUDIO_ERROR) MicInUse.captureFailure(appContext) else it
            }
            val msg = errorMessage(errorCode)
            // [T-android-asr-ondevice-fallback] The on-device recognizer has no
            // language pack for this locale. That is a property of THIS
            // recognizer, not of the device's speech support — the cloud one
            // usually handles the same locale fine. Restart once against the
            // regular recognizer instead of surfacing a dead end to the user.
            val retryLocale = pendingLocale
            if (err == RecognitionError.LANGUAGE_UNSUPPORTED && usingOnDevice && retryLocale != null) {
                Log.i(TAG, "on-device recognizer lacks a pack for the locale — retrying via the default recognizer")
                usingOnDevice = false
                // [T-voice-asr-failure-retry-prompt] A replay must restart on
                // its saved audio — a bare restart would open the microphone.
                val replay = replayPcm
                tearDown()
                if (replay != null) {
                    startReplay(replay, retryLocale, allowOnDeviceRetry = false)
                } else {
                    startInternal(retryLocale, allowOnDeviceRetry = false)
                }
                return
            }
            // First-class ROM-level failures poison the engine for the rest of
            // the process so we don't keep showing a button that doesn't work.
            if (err == RecognitionError.OEM_NO_SERVICE ||
                err == RecognitionError.PERMISSION_DENIED ||
                err == RecognitionError.AUDIO_ERROR
            ) {
                markDegraded()
            }
            // [T-android-continuous-dictation] A silent stretch inside a
            // dictation surfaces as NO_MATCH / SPEECH_TIMEOUT. That is the user
            // thinking, not a failure — restart the segment instead of ending
            // the session and showing an error. Anything else is real and ends
            // the dictation.
            // (ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT both map to NO_MATCH.)
            if (continuousSession && err == RecognitionError.NO_MATCH) {
                Log.i(TAG, "[continuous] pause (error $errorCode) — restarting recogniser")
                restartRecogniserForNextSegment()
                return
            }
            Log.w(TAG, "recognizer error $errorCode ($msg)")

            // [T-android-asr-rom-compat] A trailing error AFTER the session
            // already delivered its result (nonconforming ROMs can fire both)
            // must not surface — the user got their text; a late error would
            // read as the dictation failing retroactively.
            if (sessionCommitted) {
                Log.i(TAG, "[error] post-commit error $errorCode ignored")
                tearDown()
                return
            }
            sessionCommitted = true

            // [T-android-continuous-dictation] Salvage whatever this dictation
            // already produced, whether or not it is still armed.
            //
            // The arm flag is NOT the right condition here. stop() clears it
            // before calling stopListening(), so the error that arrives
            // moments later — very often NO_SPEECH_DETECTED, because the
            // recogniser sees only the tail of the audio after our capture
            // closes — lands with continuousSession already false. Gating the
            // salvage on the flag therefore threw the transcript away on the
            // single most common path: the user taps stop and gets nothing.
            //
            // Anything heard this session lives in segmentTranscript (previous
            // segments) and bufferedPartial (the current one), so both are
            // recoverable independently of the flag.
            continuousSession = false
            continuousLocale = null
            val salvaged = joinedTranscript(bufferedPartial.orEmpty())
            segmentTranscript.setLength(0)
            if (salvaged.isNotEmpty()) {
                Log.i(TAG, "[continuous] salvaging ${salvaged.length} chars from error $errorCode")
                bufferedPartial = null
                listener?.onFinal(salvaged)
                tearDown()
                return
            }
            // [T-android-asr-silent-failure] NO_MATCH with nothing salvaged is
            // only "you didn't say anything" when the user actually didn't.
            // When the VAD registered speech this session, the honest report
            // is a transcription failure — the panel swallows NO_MATCH by
            // design, which is precisely how four consecutive error-7s at
            // 14:29 produced zero visible feedback. Root cause on this device:
            // the offline recognizer has no zh-CN language pack (its own
            // logcat: `Failed to get language pack of required locale:
            // error 12`) and the platform degrades that to ERROR_NO_MATCH, so
            // the actionable cause never reaches our error 11/12 mapping.
            val effErr = if (err == RecognitionError.NO_MATCH && heardSpeech) {
                Log.w(TAG, "[error] NO_MATCH but VAD heard speech — surfacing TRANSCRIPTION_FAILED")
                RecognitionError.TRANSCRIPTION_FAILED
            } else err
            val effMsg = if (effErr == RecognitionError.TRANSCRIPTION_FAILED) {
                "Speech detected but nothing was transcribed. If this keeps happening, " +
                    "install the offline language pack in system voice-input settings, " +
                    "or switch to a provider recognizer."
            } else msg
            // [T-voice-asr-failure-retry-prompt] A real failure keeps the take
            // for a retry; "you said nothing" (plain NO_MATCH) does not.
            if (effErr != RecognitionError.NO_MATCH) handBackAudio()
            listener?.onError(effErr, effMsg)
            tearDown()
        }
    }

    companion object {
        private const val TAG = "SystemSTT"

        // ── [T-android-vad] ──
        /**
         * Silence-close minimum, matching iOS `minSegmentSeconds`
         * (VoiceInputPanel.swift:607). Shorter utterances are discarded.
         */
        private const val MIN_SEGMENT_SECONDS = 2.0f

        /** Below this we stay silent; above it the user is told why (iOS :661). */
        private const val TOO_SHORT_FLOOR_SECONDS = 0.3f

        /**
         * [T-android-vad-merge-segments] How long the session stays open
         * waiting for the user to top up a sub-2s utterance. Matches the iOS
         * force-flush timer (VoiceInputPanel.swift:609).
         */
        private const val HOLD_FLUSH_MS = 5_000L

        /**
         * What we ask the platform to wait before endpointing itself. Our VAD
         * closes at ~5 s, so this is padded well past that: on a ROM that DOES
         * honour the extra, the platform must not cut first.
         */
        private const val PLATFORM_SILENCE_GUARD_MS = 15_000

        /** [T-voice-asr-failure-retry-prompt] 300 s of capture-rate PCM16 mono. */
        private const val MAX_RETAINED_PCM_BYTES = 300 * VoiceActivityDetector.SAMPLE_RATE * 2

        /** Pipe write granularity when replaying kept audio. */
        private const val REPLAY_CHUNK_BYTES = 8_192

        private fun mapError(code: Int): RecognitionError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_MATCH
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionError.NETWORK
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.PERMISSION_DENIED
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionError.RECOGNIZER_BUSY
            SpeechRecognizer.ERROR_AUDIO -> RecognitionError.AUDIO_ERROR
            SpeechRecognizer.ERROR_CLIENT -> RecognitionError.OEM_NO_SERVICE
            SpeechRecognizer.ERROR_SERVER -> RecognitionError.NETWORK
            // Codes added in API 33; referenced by literal for compatibility.
            11 /* ERROR_LANGUAGE_NOT_SUPPORTED */,
            12 /* ERROR_LANGUAGE_UNAVAILABLE   */ -> RecognitionError.LANGUAGE_UNSUPPORTED
            else -> RecognitionError.UNKNOWN
        }

        private fun errorMessage(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error (no recognition service?)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO required"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            // [T-android-asr-script-subtag] mapError already classifies 11/12 as
            // LANGUAGE_UNSUPPORTED, but this function built the user-visible
            // string and had no case for them — so a language-pack problem
            // surfaced as the useless "Unknown error (12)". Name the actual
            // cause, and point at the fix the user can act on.
            11 /* ERROR_LANGUAGE_NOT_SUPPORTED */ ->
                "Language not supported by the recognizer"
            12 /* ERROR_LANGUAGE_UNAVAILABLE */ ->
                "Language pack unavailable — download it in the system speech settings"
            else -> "Unknown error ($code)"
        }
    }
}
