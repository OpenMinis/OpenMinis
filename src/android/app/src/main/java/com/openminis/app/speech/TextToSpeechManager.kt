package com.openminis.app.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Manages Android TextToSpeech engine for reading assistant responses aloud.
 *
 * [T-android-tts-locale GH#324] Language is resolved per utterance by
 * [TtsLocaleResolver]: an identifying script wins, else the user's in-app
 * language, else the system locale. This class used to support only en-US and
 * zh-CN, which meant every other shipped language was read aloud in American
 * English.
 */
class TextToSpeechManager : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeech"

        /**
         * [T-android-tts-silent-blackhole] Upper bound on waiting for the
         * engine to bind. Generous because low-end devices cold-start their
         * TTS service; a device with NO engine may never call onInit, and this
         * is what turns that into a detectable failure instead of a hang.
         */
        const val INIT_TIMEOUT_MS = 4_000L
        // [T-android-tts-intranumber-guard] The sentence-boundary set moved to
        // SpeechSentenceSplitter.SENTENCE_ENDERS — a single source of truth
        // shared with ReadAloudPlayer, so the two TTS paths can't drift.
    }

    // @Volatile: written on the binder thread in onInit / nulled on main in
    // shutdown(), and read from both — the posted pre-init replay relies on
    // seeing a torn-down engine rather than a stale non-null.
    @Volatile
    private var tts: TextToSpeech? = null

    /**
     * [T-android-tts-locale GH#324] Application context, kept for reading the
     * saved UI language. Application-scoped, so no Activity is leaked.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * [T-android-tts-locale GH#324] Set when a caller picked the language
     * explicitly via [setLanguage] — today `android-speak --voice <tag>`.
     *
     * Without this the explicit choice was silently discarded: SpeakOffloadHandler
     * calls `setLanguage(...)` and then `speak(text)`, and `speak` re-ran the
     * detector, overwriting the caller's locale microseconds later. So
     * `--voice es-ES` has never actually worked. Cleared by [stop]/[shutdown]
     * so one explicit utterance cannot pin every later one.
     */
    @Volatile
    private var explicitLocale: Locale? = null
    @Volatile
    var isInitialized = false
        private set

    /**
     * [T-android-tts-silent-blackhole] True when the engine reported ERROR at
     * init — typically a device with no functional TTS engine installed
     * (common on Chinese ROMs without Google TTS, e.g. ColorOS). Callers can
     * use this + [awaitReady] to distinguish "not ready YET" from "will never
     * speak", instead of silently dropping text.
     */
    var initFailed = false
        private set

    /**
     * Resolves once [onInit] runs: true on SUCCESS, false on ERROR. Engine
     * binding is asynchronous, so the first utterances can easily arrive
     * before this settles — see [preInitQueue].
     */
    private val initResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

    /**
     * [T-android-tts-silent-blackhole] Utterances that arrived before init
     * settled. `speak()` used to DROP text whenever `!isInitialized` — since
     * init is async, the very first utterance after construction was silently
     * lost (LazyReadAloudPlayer constructs the engine on first tap, so the
     * first Read Aloud tap spoke nothing on every device). Buffered here and
     * replayed in order from [onInit] instead. Guarded by [queueLock];
     * `onInit` may arrive on a binder thread.
     */
    private val preInitQueue = mutableListOf<String>()
    private val queueLock = Any()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var isPaused = false
    private var pendingTexts = mutableListOf<String>()
    private var pausedAtIndex = 0

    /**
     * Rolling buffer of partial-streaming text that hasn't hit a sentence
     * boundary yet. Cleared by [flush] or [stop]. See [appendText].
     */
    private val sentenceBuffer = StringBuilder()

    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    var speechPitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    var speechVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /**
     * Initializes the TTS engine. Must be called before speaking.
     */
    fun init(context: Context) {
        // [T-android-tts-locale GH#324] Retained so the per-utterance locale
        // resolution can read the user's in-app language choice. The manager
        // previously kept no Context at all, which is part of why the language
        // decision could only ever look at the text's script.
        appContext = context.applicationContext
        tts = TextToSpeech(context.applicationContext, this)
    }

    /**
     * [T-android-tts-locale GH#324] The user's saved in-app language tag, or
     * null when they follow the system. Read fresh each utterance rather than
     * cached: the Appearance screen writes this preference and the user can
     * change it without the TTS engine being re-created.
     */
    private fun savedAppLanguageTag(): String? = runCatching {
        appContext
            ?.getSharedPreferences(
                com.openminis.app.ui.settings.PREF_APPEARANCE,
                Context.MODE_PRIVATE,
            )
            ?.getString(com.openminis.app.ui.settings.KEY_LANGUAGE, "")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.apply {
                setSpeechRate(speechRate)
                setPitch(speechPitch)
                // [T-android-tts-locale GH#324] Was `setLanguage(Locale.US)`.
                // Every utterance re-resolves the language anyway, so this only
                // ever decided the engine's resting state — but on a device
                // whose first utterance raced init, that resting state WAS the
                // voice. Seed from the user's own language instead of pinning
                // American English for everyone.
                applyResolvedLocale(TtsLocaleResolver.resolve("", savedAppLanguageTag()))
                setOnUtteranceProgressListener(createProgressListener())
            }
            Log.d(TAG, "TTS initialized successfully")
            // Replay whatever arrived while the engine was binding, in order.
            val queued = synchronized(queueLock) {
                val copy = preInitQueue.toList()
                preInitQueue.clear()
                copy
            }
            // [T-android-tts-init-thread] Post the replay to the MAIN thread.
            //
            // onInit arrives on a binder thread. speakQueued touches
            // pendingTexts (a plain MutableList that speak()/stop()/the
            // progress listener also mutate) and `tts` (which shutdown()
            // nulls), with no happens-before against any of them. Disposing the
            // ChatScreen inside the ~4 s init window — rotate the device, or
            // navigate away right after enabling read-replies — therefore
            // raced a ConcurrentModificationException on pendingTexts, or a
            // speak() against an already shut-down engine. Same lifecycle shape
            // as the VAD SIGSEGV fixed in 91c7b2944.
            //
            // Re-check liveness inside the post: shutdown() can land between
            // the binder callback and the main-thread turn.
            if (queued.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    if (tts != null && isInitialized) {
                        for (text in queued) speakQueued(text)
                    } else {
                        Log.d(TAG, "pre-init replay dropped — engine shut down during init")
                    }
                }
            }
        } else {
            initFailed = true
            synchronized(queueLock) { preInitQueue.clear() }
            _isSpeaking.value = false
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
        initResult.complete(status == TextToSpeech.SUCCESS)
    }

    /**
     * [T-android-tts-silent-blackhole] Suspend until init settles (bounded by
     * [timeoutMs] — a device with no engine may never call [onInit] at all).
     * @return true when the engine is usable.
     */
    suspend fun awaitReady(timeoutMs: Long = INIT_TIMEOUT_MS): Boolean {
        if (isInitialized) return true
        if (initFailed) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { initResult.await() } ?: false
    }

    /**
     * Speaks text immediately, interrupting any ongoing speech (QUEUE_FLUSH).
     * Automatically detects language from text content.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            // [T-android-tts-silent-blackhole] Engine still binding (or dead).
            // Buffer instead of dropping; onInit replays in order. On a failed
            // init the queue is discarded there — nothing can ever speak it.
            if (!initFailed) {
                synchronized(queueLock) {
                    preInitQueue.clear() // speak() semantics: flush what came before
                    preInitQueue.add(text)
                }
                _isSpeaking.value = true
            }
            return
        }

        isPaused = false
        pendingTexts.clear()
        pendingTexts.add(text)
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)

        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] speak() returns ERROR synchronously when
        // the engine rejects the request (missing language data, engine died —
        // both real on OEM/AOSP engines without Google TTS). The result used
        // to be discarded, so isSpeaking stayed true with NO utterance in
        // flight and no progress callback ever coming — ReadAloudPlayer's
        // completion poll then spun forever and the whole read-aloud queue
        // wedged for the rest of the process.
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, generateUtteranceId())
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak rejected by engine (rc=$rc len=${text.length})")
            _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    /**
     * Adds text to the speech queue without interrupting (QUEUE_ADD).
     */
    fun speakQueued(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            // [T-android-tts-diag] A field report of "read replies is on but
            // nothing is spoken" could not be diagnosed from the log: this
            // path emitted nothing at all, so whether text ever reached the
            // engine was unknowable. Queued-before-init is the one silent
            // outcome that looks identical to success from the caller's side.
            Log.i(TAG, "speakQueued deferred (init pending) len=${text.length} initFailed=$initFailed")
            if (!initFailed) {
                synchronized(queueLock) { preInitQueue.add(text) }
                _isSpeaking.value = true
            }
            return
        }
        Log.i(TAG, "speakQueued len=${text.length}")

        pendingTexts.add(text)
        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] Same rejected-enqueue guard as speak().
        val rc = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, generateUtteranceId())
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speakQueued rejected by engine (rc=$rc len=${text.length})")
            // Only clear the flag when nothing else is in flight — a QUEUE_ADD
            // rejection must not mark an ongoing earlier utterance as done.
            if (pendingTexts.isEmpty()) _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    /**
     * Feed a chunk of streaming AI output. Any complete sentences the chunk
     * produces (boundary characters: `。！？.!?\n` — same set as iOS
     * `extractNewSentences` in AIChatViewModel) are immediately enqueued for
     * TTS via [speakQueued], so the first sentence starts speaking the moment
     * its terminator arrives instead of waiting for the full response.
     *
     * Incomplete tail text stays in [sentenceBuffer] until the next call, or
     * until [flush] runs at stream end.
     *
     * Safe to call before [init]/onInit — chunks are still split; the TTS
     * engine simply drops them if not yet ready (speakQueued guards).
     */
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        val sentences = extractCompleteSentences(sentenceBuffer)
        if (sentences.isEmpty()) return
        for (sentence in sentences) {
            speakQueued(sentence)
        }
    }

    /**
     * Emit the remaining buffered tail (a sentence fragment with no trailing
     * punctuation) as a final utterance. Call at the end of a streaming
     * response. Mirrors iOS's post-stream "flush remaining speech" logic.
     */
    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) {
            speakQueued(tail)
        }
    }

    /**
     * [T-android-tts-intranumber-guard] Delegates to the shared
     * [SpeechSentenceSplitter] so this path and [ReadAloudPlayer] apply the same
     * terminator set AND the same intra-number guards ("3.14" is never cut into
     * "3." + "14").
     */
    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    /**
     * Stops all speech immediately.
     */
    fun stop() {
        tts?.stop()
        // [T-android-tts-locale GH#324] One explicit `--voice` utterance must
        // not pin the language for every later one.
        explicitLocale = null
        isPaused = false
        pendingTexts.clear()
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)
        // A stop while the engine is still binding must also drop the buffered
        // utterances, or they'd blurt out the moment onInit lands.
        synchronized(queueLock) { preInitQueue.clear() }
        _isSpeaking.value = false
    }

    /**
     * Toggles pause/resume. Since Android TTS doesn't natively support pause,
     * this stops playback and tracks position for manual resume.
     */
    fun togglePause() {
        if (isPaused) {
            // Resume: replay remaining texts from where we paused
            isPaused = false
            if (pausedAtIndex < pendingTexts.size) {
                val remaining = pendingTexts.subList(pausedAtIndex, pendingTexts.size)
                remaining.forEachIndexed { index, text ->
                    autoDetectAndSetLanguage(text)
                    val params = buildSpeechParams()
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts?.speak(text, queueMode, params, generateUtteranceId())
                }
                _isSpeaking.value = true
            }
        } else {
            // Pause: stop and mark position
            isPaused = true
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    /**
     * Sets the TTS language explicitly.
     */
    fun setLanguage(locale: Locale) {
        if (!isInitialized) return
        // [T-android-tts-locale GH#324] Remember it, or the very next speak()
        // would re-run detection and throw this away.
        explicitLocale = locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: ${locale.toLanguageTag()}")
        } else {
            Log.d(TAG, "Language set to: ${locale.toLanguageTag()}")
        }
    }

    /**
     * Cleans up the TTS engine. Must be called when no longer needed.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        explicitLocale = null
        appContext = null
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shut down")
    }

    /**
     * Detects if text contains Han characters and sets language accordingly.
     */
    /**
     * [T-android-system-voice-catalog] Engine voice name to speak with; null =
     * auto (per-utterance language detection below). Set per utterance by
     * ReadAloudPlayer from the capsule's picked system voice. When the named
     * voice exists it wins over language auto-detection — same as iOS, where a
     * picked voice is used regardless of the reply's language.
     */
    @Volatile
    var preferredVoiceName: String? = null

    private fun autoDetectAndSetLanguage(text: String) {
        preferredVoiceName?.let { wanted ->
            val v = runCatching { tts?.voices?.firstOrNull { it.name == wanted } }.getOrNull()
            if (v != null) {
                val rc = tts?.setVoice(v)
                if (rc == TextToSpeech.SUCCESS) return
                Log.w(TAG, "setVoice($wanted) rejected (rc=$rc) — falling back to auto language")
            } else {
                Log.w(TAG, "preferred voice '$wanted' not found on this engine — falling back to auto language")
            }
        }
        // [T-android-tts-locale GH#324] An explicit caller choice outranks
        // detection — see [explicitLocale] for why this guard has to exist.
        explicitLocale?.let { applyResolvedLocale(it); return }

        applyResolvedLocale(TtsLocaleResolver.resolve(text, savedAppLanguageTag()))
    }

    /**
     * [T-android-tts-locale GH#324] Hand [wanted] to the engine, degrading to
     * the bare language when the region-qualified form has no voice data.
     *
     * The degradation is the point: a device may ship `pt` but not `pt-BR`, and
     * `setLanguage` with an unsupported locale is a no-op that leaves whatever
     * was set last in place. Before this change that leftover was English, so
     * an unsupported locale produced exactly the reported bug rather than an
     * approximation. When nothing matches we log and leave the engine alone —
     * a wrong-but-present voice beats silence, and the log says which language
     * the device is missing.
     */
    private fun applyResolvedLocale(wanted: Locale) {
        val engine = tts ?: return
        for (candidate in TtsLocaleResolver.candidates(wanted)) {
            val availability = runCatching { engine.isLanguageAvailable(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (availability == TextToSpeech.LANG_MISSING_DATA ||
                availability == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                continue
            }
            val rc = runCatching { engine.setLanguage(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (rc != TextToSpeech.LANG_MISSING_DATA && rc != TextToSpeech.LANG_NOT_SUPPORTED) {
                return
            }
        }
        Log.w(
            TAG,
            "no voice data for ${wanted.toLanguageTag()} — keeping the engine's " +
                "current language (install the language's TTS data to fix)",
        )
    }

    private fun buildSpeechParams(): android.os.Bundle {
        return android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)
        }
    }

    private fun generateUtteranceId(): String = UUID.randomUUID().toString()

    private fun createProgressListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                // [T-android-tts-init-thread] Progress callbacks arrive on a
                // binder thread. This used to bump pausedAtIndex and read
                // pendingTexts.size straight from there, while togglePause()
                // takes a subList VIEW of that same list on main — a
                // concurrent stop() clearing it makes the view throw. Confine
                // both to the main thread so pendingTexts/pausedAtIndex have a
                // single owner.
                Handler(Looper.getMainLooper()).post {
                    pausedAtIndex++
                    // Only mark as not speaking if nothing else is queued
                    if (pausedAtIndex >= pendingTexts.size) {
                        _isSpeaking.value = false
                    }
                }
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance $utteranceId, code: $errorCode")
                _isSpeaking.value = false
            }
        }
}
