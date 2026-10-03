package com.openminis.app.speech

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * [T-voice-mic-preempted] Tells "another app holds the microphone" apart from
 * a genuine capture failure. Port of iOS 3a2e3e305 (issue #283).
 *
 * On iOS a mic taken by FaceTime / a call surfaced as "Parse failed:
 * Microphone input unavailable". Android had two versions of the same fault:
 *  - the recorder could not be created or started, and the panel showed the
 *    engine's raw English ("AudioRecord not initialized.", "Microphone
 *    unavailable: <exception>");
 *  - more often, since Android 10's concurrent-capture rules, the recording
 *    STARTS but the system feeds it silence while a call or another recorder
 *    owns the input. Nothing noticed, so the mic appeared to listen until the
 *    silence timeout and the take ended as "nothing recognized".
 *
 * Both now end as [RecognitionError.MIC_IN_USE] with a translated message
 * naming the cause and the fix. The engine detail goes to the log only.
 */
object MicInUse {

    /**
     * A capture failure is blamed on another app only with evidence: a call is
     * active, or the system reports our recording as silenced. Otherwise the
     * cause is unknown (route mid-change, hardware) and blaming another app
     * would be wrong - the same split iOS makes.
     */
    fun classify(callActive: Boolean, silenced: Boolean): RecognitionError =
        if (callActive || silenced) RecognitionError.MIC_IN_USE else RecognitionError.AUDIO_ERROR

    /** The error to report for a capture that could not be created, started or read. */
    fun captureFailure(context: Context, audioSessionId: Int? = null): RecognitionError =
        classify(isCallActive(context), audioSessionId?.let { isSilenced(context, it) } == true)

    /** Audio modes that mean a call or VoIP session owns the audio path. */
    internal fun isCallMode(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        4 /* MODE_CALL_SCREENING, API 30 */,
        5 /* MODE_CALL_REDIRECT, API 33 */,
        6 /* MODE_COMMUNICATION_REDIRECT, API 33 */,
        -> true
        else -> false
    }

    fun isCallActive(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        isCallMode(am.mode)
    }.getOrDefault(false)

    /**
     * Whether the system is feeding the recording with [audioSessionId]
     * silence because another app holds the input (Android 10+). False when
     * unknown: our own config is not listed yet, or the API is missing.
     */
    fun isSilenced(context: Context, audioSessionId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            am.activeRecordingConfigurations.any { it.clientAudioSessionId == audioSessionId && it.isClientSilenced }
        }.getOrDefault(false)
    }
}
