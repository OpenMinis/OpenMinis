package com.openminis.app.speech

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Application-scoped orchestrator for speech-to-text. Owns:
 *
 *  - A registry of [SpeechRecognitionEngine] adapters (system + provider).
 *  - The user's selected engine id (persisted) and active locale.
 *  - Lifecycle of the current capture session (single in-flight request).
 *  - Public state flows the UI collects:
 *      * [isAvailable] — whether the mic button should render at all.
 *      * [state] — idle / starting / recording / finishing.
 *      * [recognizedText] — live transcription (empty between sessions).
 *      * [locale] — current recognition locale.
 *
 * Mirrors the responsibilities of iOS `SpeechRecognitionManager.swift`.
 */
object SpeechRecognitionManager {

    private const val TAG = "SpeechManager"
    private const val PREFS = "speech_recognition"
    private const val KEY_ENGINE_ID = "engineId"
    private const val KEY_LOCALE = "locale"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val engines = mutableListOf<SpeechRecognitionEngine>()

    private val _state = MutableStateFlow(RecognitionState.IDLE)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    /**
     * [T-android-tts-stop-on-capture] Resume read-aloud the moment capture
     * ends, whichever way it ended.
     *
     * There are five separate writes of IDLE in this file — final result,
     * engine error, engine-start throw, cancelRecording, and the mid-session
     * locale restart — plus FINISHING from stopRecording. Hanging the resume
     * off each one individually means a sixth path added later silently leaves
     * TTS muted forever, which is a much worse failure than a missed pause.
     * Funnelling every transition through one setter makes that impossible by
     * construction.
     *
     * FINISHING deliberately does NOT resume: the engine is still delivering
     * the final result and the mic may still be hot.
     */
    private fun setState(next: RecognitionState) {
        val prev = _state.value
        _state.value = next
        if (next == RecognitionState.IDLE && prev != RecognitionState.IDLE) {
            VoiceOutputState.resumeAllAfterCapture()
        }
    }

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _locale = MutableStateFlow(Locale.getDefault())
    val locale: StateFlow<Locale> = _locale.asStateFlow()

    private val _selectedEngineId = MutableStateFlow("system")
    val selectedEngineId: StateFlow<String> = _selectedEngineId.asStateFlow()

    private val _lastError = MutableStateFlow<RecognitionError?>(null)
    val lastError: StateFlow<RecognitionError?> = _lastError.asStateFlow()

    /**
     * Whether SOME engine can transcribe right now. This is a runtime probe —
     * it flips as engines degrade, providers get configured, etc.
     *
     * [T-android-voice-entry-always-available] Do NOT use this to decide
     * whether the mic / keyboard-toggle button RENDERS. Gating a control on a
     * runtime probe is what stranded users inside voice mode when the active
     * engine degraded mid-session: the toggle was the only way out and it
     * vanished with the probe. Use [hasMicrophoneHardware] for existence, and
     * this flow only to decide what the voice panel SHOWS once entered.
     */
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    /**
     * [T-android-voice-entry-always-available] Structural, device-level fact:
     * does this device have microphone hardware at all?
     *
     * Unlike [isAvailable] this never changes at runtime, so it is the only
     * thing the composer's voice button existence depends on. A device with no
     * mic genuinely cannot offer voice input; everything else (no speech
     * service installed, engine degraded, no ASR provider configured) is a
     * RECOVERABLE state that must be explained inside the panel rather than by
     * silently removing the control.
     */
    val hasMicrophoneHardware: Boolean
        get() = if (::appContext.isInitialized) {
            runCatching {
                appContext.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_MICROPHONE,
                )
            }.getOrDefault(true)
        } else {
            // Not initialised yet — assume present. Showing the button and
            // explaining inside the panel beats hiding the entry point.
            true
        }

    /**
     * Locales the active engine reports as supported. Populated lazily after
     * the first availability probe; see [refreshSupportedLocales]. The UI's
     * language picker observes this; while empty it falls back to showing the
     * current locale only.
     */
    private val _supportedLocales = MutableStateFlow<List<Locale>>(emptyList())
    val supportedLocales: StateFlow<List<Locale>> = _supportedLocales.asStateFlow()

    /**
     * Rolling window of normalized (0f..1f) audio levels for the live waveform.
     * New samples push in at the tail; oldest drop off the head. Mirrors iOS
     * `SpeechRecognitionManager.audioLevels` (40-element buffer).
     */
    private val _audioLevels = MutableStateFlow(List(WAVEFORM_SIZE) { 0f })
    val audioLevels: StateFlow<List<Float>> = _audioLevels.asStateFlow()

    /** How many rolling level samples to keep. */
    const val WAVEFORM_SIZE = 40

    /**
     * Android's onRmsChanged reports values roughly in [-2, 10]. Clamp to
     * [0, 12] and scale linearly to [0, 1]; negatives (quieter than the
     * recognizer's own reference) land on 0 and read as silence.
     *
     * [T-android-voice-parity] The previous doc-comment claimed "a floor at the
     * mid-point of the noise range" — no such floor exists here or ever did,
     * and adding one now would raise the idle baseline and make silence look
     * like speech, which is the opposite of what that sentence promised. The
     * visual floor is applied at render time instead (bars are drawn at
     * `5.dp + level * 21.dp`, so 0 still shows a 5 dp stub), matching iOS,
     * which likewise floors in the view layer.
     *
     * Note this is a plain linear ramp, whereas iOS applies a perceptual
     * `sqrt(rms * 14) * 0.95` curve (VoiceInputPanel.swift:1177). Full parity
     * would need iOS's raw-PCM level source; with onRmsChanged's already-dB-ish
     * scale a second curve would double-compress the top end.
     */
    private fun normalizeRms(db: Float): Float {
        val clamped = db.coerceIn(0f, 12f)
        return (clamped / 12f).coerceIn(0f, 1f)
    }

    internal fun pushLevel(rawDb: Float) {
        val n = normalizeRms(rawDb)
        val current = _audioLevels.value
        val updated = ArrayList<Float>(current.size)
        for (i in 1 until current.size) updated.add(current[i])
        updated.add(n)
        _audioLevels.value = updated
    }

    private fun resetLevels() {
        _audioLevels.value = List(WAVEFORM_SIZE) { 0f }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        engines.clear()
        engines.add(SystemSpeechRecognitionEngine(appContext))
        engines.add(ProviderSpeechRecognitionEngine(appContext))

        _selectedEngineId.value = prefs.getString(KEY_ENGINE_ID, null) ?: pickDefaultEngineId()
        prefs.getString(KEY_LOCALE, null)?.let { tag ->
            runCatching { _locale.value = Locale.forLanguageTag(tag) }
        }
        refreshAvailability()
        refreshSupportedLocales()
        observeAppLifecycle()
    }

    /**
     * [T-android-vad] Feed foreground/background transitions to the engines so
     * their detectors can apply iOS's 15 s backgrounded auto-stop
     * (VoiceInputPanel.swift:252-260, 285-295). Capture survives a brief switch
     * away — a notification pull-down, a glance at another app — but not a real
     * one, which would otherwise leave the mic live indefinitely.
     *
     * Observer registration is main-thread only.
     */
    private fun observeAppLifecycle() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                    androidx.lifecycle.LifecycleEventObserver { _, event ->
                        when (event) {
                            androidx.lifecycle.Lifecycle.Event.ON_STOP -> setBackgrounded(true)
                            androidx.lifecycle.Lifecycle.Event.ON_START -> setBackgrounded(false)
                            else -> {}
                        }
                    },
                )
            }.onFailure { Log.w(TAG, "lifecycle observer failed: ${it.message}") }
        }
    }

    private fun setBackgrounded(backgrounded: Boolean) {
        for (e in engines) {
            when (e) {
                is SystemSpeechRecognitionEngine -> e.setBackgrounded(backgrounded)
                is ProviderSpeechRecognitionEngine -> e.setBackgrounded(backgrounded)
            }
        }
    }

    /**
     * Ask the active engine which languages it supports (async) and update
     * [_supportedLocales]. Also re-validates the current locale: if the saved
     * locale isn't in the list (e.g. user changed regions) it falls back to
     * the system default, then en-US.
     */
    fun refreshSupportedLocales() {
        val engine = currentEngine() ?: return
        if (engine is SystemSpeechRecognitionEngine) {
            engine.fetchSupportedLocales { locales ->
                _supportedLocales.value = locales
                if (locales.isNotEmpty() && locales.none { it.toLanguageTag() == _locale.value.toLanguageTag() }) {
                    val fallback = locales.firstOrNull { it.language == Locale.getDefault().language }
                        ?: locales.firstOrNull { it.language == "en" }
                        ?: locales.first()
                    _locale.value = fallback
                }
            }
        } else {
            _supportedLocales.value = engine.supportedLocales
        }
    }

    private fun pickDefaultEngineId(): String =
        engines.firstOrNull { it.isAvailable }?.id ?: "system"

    fun selectEngine(id: String) {
        if (_selectedEngineId.value == id) return
        _selectedEngineId.value = id
        prefs.edit().putString(KEY_ENGINE_ID, id).apply()
        // [T-android-voice-entry-always-available] Deliberately picking an
        // engine is an explicit "try this one" — clear its degraded flag so a
        // past transient failure (mic busy, permission not yet granted, a
        // provider that was misconfigured and has since been fixed) doesn't
        // leave it permanently unselectable. `degraded` is otherwise
        // process-scoped with no reset path at all.
        engines.firstOrNull { it.id == id }?.clearDegraded()
        refreshAvailability()
    }

    /**
     * [T-android-voice-entry-always-available] Give every engine a fresh start.
     * Called when the user re-enters voice mode, so a transient failure does
     * not poison the feature for the rest of the process — the user's retry is
     * the signal that conditions may have changed (permission granted, other
     * app released the mic, provider configured).
     */
    fun clearDegradationAndRefresh() {
        engines.forEach { it.clearDegraded() }
        refreshAvailability()
    }

    fun selectLocale(locale: Locale) {
        if (_locale.value.toLanguageTag() == locale.toLanguageTag()) return
        _locale.value = locale
        prefs.edit().putString(KEY_LOCALE, locale.toLanguageTag()).apply()
        // If the user changed language mid-recording, iOS restarts the
        // session in the new language. Matching that behavior.
        if (_state.value == RecognitionState.RECORDING || _state.value == RecognitionState.STARTING) {
            val cb = activeCallbacks
            if (cb != null) {
                currentEngine()?.cancel()
                setState(RecognitionState.IDLE)
                startRecording(cb.onPartialOrFinal, cb.onError, cb.onFailedAudio)
            }
        }
    }

    /** Callbacks from the most recent [startRecording] so [selectLocale] can restart with them. */
    private class Callbacks(
        val onPartialOrFinal: (String, Boolean) -> Unit,
        val onError: (RecognitionError, String?) -> Unit,
        val onFailedAudio: ((FailedAudio) -> Unit)?,
    )
    private var activeCallbacks: Callbacks? = null

    /**
     * [T-voice-asr-failure-retry-prompt] One utterance whose transcription
     * failed, with its audio kept so the user can retry instead of repeating
     * it. [engineId] is the engine that produced it (and will retry it).
     */
    class FailedAudio(
        val wav: ByteArray,
        val seconds: Double,
        val error: RecognitionError,
        val message: String?,
        val engineId: String,
    )

    /** Engine running a [retryTranscription], so stop/cancel reach it. */
    @Volatile
    private var retryEngine: SpeechRecognitionEngine? = null

    fun availableEngines(): List<SpeechRecognitionEngine> = engines.toList()

    private val noAsrStall = MutableStateFlow(false).asStateFlow()

    /**
     * [T-android-voice-asr-stall-skip] True while a cloud ASR attempt has gone
     * [ProviderSpeechRecognitionEngine.ASR_STALL_THRESHOLD_MS] without a result
     * and there is a next voice model to switch to — the voice panel shows its
     * "Switch Model" pill (and hides transcript delete) exactly while this is
     * true. Drops as soon as the stall ends: skipped, finished, failed or
     * cancelled.
     */
    fun asrStalled(): StateFlow<Boolean> =
        engines.filterIsInstance<ProviderSpeechRecognitionEngine>().firstOrNull()?.asrAttempts?.stalled
            ?: noAsrStall

    /**
     * [T-android-voice-asr-stall-skip] Skip every stalled attempt to its next
     * voice model (System last). Returns the model now used, for the toast, or
     * null when nothing was stalled.
     */
    fun skipStalledTranscription(systemName: String, genericName: String): String? =
        engines.filterIsInstance<ProviderSpeechRecognitionEngine>().firstOrNull()
            ?.skipStalledTranscription(systemName, genericName)

    /**
     * Recompute [isAvailable]. Cheap — the UI may call this before rendering
     * the mic button so runtime degradation (first-failure observations in
     * the active engine) takes effect.
     */
    fun refreshAvailability() {
        _isAvailable.value = engines.any { it.isAvailable }
    }

    private fun currentEngine(): SpeechRecognitionEngine? {
        val byId = engines.firstOrNull { it.id == _selectedEngineId.value }
        if (byId != null && byId.isAvailable) return byId
        return engines.firstOrNull { it.isAvailable }
    }

    /**
     * Start a capture session. [onPartialOrFinal] fires with the current best
     * transcription; the caller is responsible for delta-appending into the
     * composer (mirrors iOS `lastRecognizedLength` algorithm).
     *
     * No-op if a session is already in flight.
     */
    fun startRecording(
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        // [T-voice-asr-failure-retry-prompt] Receives failures whose audio the
        // engine kept (instead of [onError]). Null keeps the old behaviour.
        onFailedAudio: ((FailedAudio) -> Unit)? = null,
    ) {
        if (_state.value != RecognitionState.IDLE) {
            Log.d(TAG, "startRecording ignored; state=${_state.value}")
            VoicePipelineLog.event("manager.start.rejected", "state" to _state.value)
            return
        }
        val engine = currentEngine()
        if (engine == null) {
            _lastError.value = RecognitionError.OEM_NO_SERVICE
            VoicePipelineLog.event("manager.start.noEngine")
            onError(RecognitionError.OEM_NO_SERVICE, "No recognition engine available")
            return
        }
        VoicePipelineLog.event("manager.start", "engine" to engine.id)

        // [T-android-tts-stop-on-capture] Silence read-aloud BEFORE the engine
        // opens the mic. Done here rather than at the UI call sites so every
        // entry point is covered — the voice panel, the voice_chat shortcut and
        // hands-free re-arm all funnel through startRecording.
        //
        // Ordering matters: this runs before STARTING is published, so playback
        // is already stopping by the time the recognizer warms up, rather than
        // racing the first audio frames.
        VoiceOutputState.suspendAllForCapture()

        setState(RecognitionState.STARTING)
        _recognizedText.value = ""
        _lastError.value = null
        resetLevels()
        activeCallbacks = Callbacks(onPartialOrFinal, onError, onFailedAudio)

        val listener = makeListener(engine.id, onPartialOrFinal, onError, onFailedAudio)

        try {
            engine.start(_locale.value, listener)
        } catch (e: Throwable) {
            setState(RecognitionState.IDLE)
            _lastError.value = RecognitionError.UNKNOWN
            onError(RecognitionError.UNKNOWN, e.message)
        }
    }

    /**
     * [T-voice-asr-failure-retry-prompt] Re-transcribe audio kept from a failed
     * take. Runs as a FINISHING session (so the panel shows "Recognizing…" and
     * voice mode still owes text), and reports through the same callbacks as a
     * capture; a second failure comes back through [onFailedAudio] again.
     *
     * @return false when another session is in flight — the caller keeps the
     *   utterance queued and asks again later.
     */
    fun retryTranscription(
        failed: FailedAudio,
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        onFailedAudio: (FailedAudio) -> Unit,
    ): Boolean {
        if (_state.value != RecognitionState.IDLE) {
            VoicePipelineLog.event("manager.retry.rejected", "state" to _state.value)
            return false
        }
        val engine = engines.firstOrNull { it.id == failed.engineId } ?: currentEngine()
        if (engine == null) {
            onFailedAudio(failed)
            return true
        }
        VoicePipelineLog.event("manager.retry", "engine" to engine.id, "seconds" to "%.1f".format(failed.seconds))
        setState(RecognitionState.FINISHING)
        _lastError.value = null
        retryEngine = engine
        val listener = makeListener(engine.id, onPartialOrFinal, onError, onFailedAudio)
        try {
            engine.transcribeRetained(failed.wav, _locale.value, listener)
        } catch (e: Throwable) {
            retryEngine = null
            setState(RecognitionState.IDLE)
            onFailedAudio(FailedAudio(failed.wav, failed.seconds, RecognitionError.UNKNOWN, e.message, engine.id))
        }
        return true
    }

    private fun makeListener(
        engineId: String,
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        onFailedAudio: ((FailedAudio) -> Unit)?,
    ): SpeechRecognitionEngine.Listener {
        // [T-voice-asr-failure-retry-prompt] Audio the engine handed back just
        // before its terminal onError.
        var retained: ByteArray? = null
        return object : SpeechRecognitionEngine.Listener {
            override fun onReadyForSpeech() {
                setState(RecognitionState.RECORDING)
            }

            override fun onPartial(text: String) {
                _recognizedText.value = text
                onPartialOrFinal(text, false)
            }

            override fun onFinal(text: String) {
                // Length only — never the recognised text itself.
                VoicePipelineLog.event("manager.final", "chars" to text.length, "state" to _state.value)
                retained = null
                retryEngine = null
                if (text.isNotEmpty()) _recognizedText.value = text
                onPartialOrFinal(text, true)
                setState(RecognitionState.IDLE)
                resetLevels()
                refreshAvailability()
            }

            override fun onRetainedAudio(wav: ByteArray) {
                retained = wav
            }

            override fun onError(error: RecognitionError, message: String?) {
                VoicePipelineLog.event("manager.error", "kind" to error.name, "state" to _state.value)
                _lastError.value = error
                retryEngine = null
                val audio = retained
                retained = null
                val seconds = audio?.let { VoiceAsrWatchdog.wavSeconds(it) } ?: 0.0
                // [T-voice-asr-failure-retry-prompt] A failure with kept audio
                // goes to the retry prompt instead of the error pill, and the
                // state reaches IDLE only AFTER the caller has queued it — so a
                // send waiting on IDLE sees the failure, not a clean finish.
                val failed = if (audio != null && onFailedAudio != null &&
                    VoiceAsrFailurePolicy.shouldPromptRetry(error, seconds)
                ) FailedAudio(audio, seconds, error, message, engineId) else null
                if (failed != null) onFailedAudio?.invoke(failed)
                setState(RecognitionState.IDLE)
                resetLevels()
                if (failed == null) onError(error, message)
                refreshAvailability()
            }

            override fun onRmsDb(rms: Float) { pushLevel(rms) }
        }
    }

    fun stopRecording() {
        if (_state.value != RecognitionState.RECORDING && _state.value != RecognitionState.STARTING) {
            VoicePipelineLog.event("manager.stop.ignored", "state" to _state.value)
            return
        }
        VoicePipelineLog.event("manager.stop", "state" to _state.value)
        setState(RecognitionState.FINISHING)
        currentEngine()?.stop()
    }

    fun cancelRecording() {
        VoicePipelineLog.event("manager.cancel", "state" to _state.value)
        // [T-voice-asr-failure-retry-prompt] A retry may run on an engine other
        // than the current selection; cancel that one too.
        retryEngine?.let { if (it !== currentEngine()) it.cancel() }
        retryEngine = null
        currentEngine()?.cancel()
        setState(RecognitionState.IDLE)
        _recognizedText.value = ""
    }
}
