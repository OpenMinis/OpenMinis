package com.openminis.app.speech

/**
 * Pure rules behind two voice-input behaviours, kept free of Android types so
 * they can be unit-tested on the JVM (the engines bind AudioRecord /
 * SpeechRecognizer and cannot be constructed there).
 *
 *  - [T-voice-send-waits-for-asr] Send while voice mode still owes text waits
 *    for capture to stop and recognition to land ([VoiceFinishTracker]).
 *  - [T-voice-asr-failure-retry-prompt] A failed transcription keeps its audio
 *    and asks the user to retry or discard ([RetryPromptQueue],
 *    [VoiceAsrFailurePolicy], [VoiceAsrWatchdog]).
 *
 * Mirrors iOS VoiceInputPanel.swift (`finishCaptureForSend`,
 * `FailedUtterance`, `retryFailedUtterance`) and VoiceProvider+System.swift
 * (`recognitionWatchdogSeconds`).
 */

/**
 * [T-voice-asr-failure-retry-prompt] How long a recognizer may take over one
 * utterance before we call it a timeout. A flat limit cut long dictation off
 * mid-recognition; scale with the audio instead: at least 8 s, plus the
 * audio's own duration, capped at 2 minutes.
 */
object VoiceAsrWatchdog {
    const val MIN_SECONDS = 8.0
    const val MAX_SECONDS = 120.0

    fun seconds(audioSeconds: Double): Double {
        val audio = if (audioSeconds.isNaN() || audioSeconds < 0) 0.0 else audioSeconds
        return (audio + MIN_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /** Duration of mono PCM16 audio of [pcmBytes] bytes at [sampleRate]. */
    fun pcmSeconds(pcmBytes: Int, sampleRate: Int = 16_000): Double =
        if (sampleRate <= 0 || pcmBytes <= 0) 0.0 else pcmBytes / 2.0 / sampleRate

    /**
     * Duration of a canonical 44-byte-header PCM16 WAV, reading the byte rate
     * at offset 28. An unreadable header yields 0 (so the watchdog falls back
     * to its 8 s floor).
     */
    fun wavSeconds(wav: ByteArray): Double {
        if (wav.size <= 44) return 0.0
        val rate = (wav[28].toLong() and 0xFF) or
            ((wav[29].toLong() and 0xFF) shl 8) or
            ((wav[30].toLong() and 0xFF) shl 16) or
            ((wav[31].toLong() and 0xFF) shl 24)
        if (rate <= 0) return 0.0
        return (wav.size - 44).toDouble() / rate
    }
}

/**
 * [T-voice-asr-failure-retry-prompt] Which terminal errors are "the speech was
 * captured but could not be transcribed" — and therefore keep the audio and ask
 * the user — versus outcomes where there is nothing worth retrying.
 */
object VoiceAsrFailurePolicy {
    /** Below this there is no speech worth asking about (matches the VAD's too-short floor). */
    const val MIN_AUDIO_SECONDS = 0.3

    fun shouldPromptRetry(error: RecognitionError, audioSeconds: Double): Boolean {
        if (audioSeconds <= MIN_AUDIO_SECONDS) return false
        // NO_MATCH is "you said nothing" — an empty-but-successful result, not
        // a failure. Everything else with real audio behind it (HTTP / auth /
        // network errors, timeouts, no usable provider, a recognizer that
        // heard speech and returned nothing) is worth asking about: only
        // engines that actually captured audio hand it back, so e.g. a
        // PERMISSION_DENIED here is a provider 401, not a refused microphone.
        // [T-voice-mic-preempted] MIC_IN_USE neither: the system was feeding
        // the take silence, so the kept audio holds nothing worth re-sending,
        // and the message already tells the user when to try again.
        return error != RecognitionError.NO_MATCH && error != RecognitionError.MIC_IN_USE
    }
}

/**
 * [T-voice-asr-failure-retry-prompt] Failed utterances awaiting a Retry /
 * Discard decision, oldest first. Immutable so a Compose state holder can swap
 * it atomically.
 *
 * Retry removes the head and hands it back for re-transcription; if that fails
 * again the caller [enqueue]s it once more (at the tail, like iOS). Discard
 * drops the head.
 */
class RetryPromptQueue<T> private constructor(val items: List<T>) {
    constructor() : this(emptyList())

    val current: T? get() = items.firstOrNull()
    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()

    fun enqueue(item: T): RetryPromptQueue<T> = RetryPromptQueue(items + item)

    /** @return the utterance to retry (null when empty) and the queue without it. */
    fun takeForRetry(): Pair<T?, RetryPromptQueue<T>> =
        if (items.isEmpty()) null to this else items.first() to RetryPromptQueue(items.drop(1))

    fun discard(): RetryPromptQueue<T> =
        if (items.isEmpty()) this else RetryPromptQueue(items.drop(1))
}

/**
 * [T-voice-send-waits-for-asr] Decides, one poll tick at a time, when a send
 * that is waiting on voice input may proceed.
 *
 * Poll rather than observe: stopping capture delivers its final segment
 * asynchronously, so "nothing pending" is only trustworthy after it has had a
 * moment to land. Require [requiredIdleTicks] consecutive idle ticks after a
 * short [settleMs]. A failure while waiting holds the send (the retry prompt
 * takes over); past [timeoutMs] the send is abandoned rather than sent
 * half-done.
 */
class VoiceFinishTracker(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val settleMs: Long = 300,
    private val requiredIdleTicks: Int = 2,
) {
    enum class Step { WAIT, SEND, FAILED, TIMED_OUT }

    private var idleTicks = 0

    fun tick(idle: Boolean, failedSinceStart: Boolean, elapsedMs: Long): Step {
        idleTicks = if (idle && elapsedMs >= settleMs) idleTicks + 1 else 0
        return when {
            failedSinceStart -> Step.FAILED
            idleTicks >= requiredIdleTicks -> Step.SEND
            elapsedMs >= timeoutMs -> Step.TIMED_OUT
            else -> Step.WAIT
        }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
