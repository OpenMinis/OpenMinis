package com.openminis.app.speech

/**
 * [T-android-vad-manual-stop] How much audio around the VAD's own segment a
 * flushed take should carry.
 *
 * Silero reports speech only once it is CONFIRMED and ends a segment as soon
 * as its silence window is satisfied, so the audio it hands over is trimmed at
 * both ends: the onset spoken while it was still deciding, and the decay of
 * the final syllable. Feeding that to a recogniser drops leading and trailing
 * words — reported as "识别到的内容一直没有前面两个字".
 *
 * Both ends are therefore padded from the raw capture: a pre-roll ring held
 * before confirmation, and a tail hold kept after the endpoint. This object
 * exists so the frame arithmetic is checkable without a microphone.
 */
internal object VadCaptureWindow {

    /** Frames of post-endpoint audio to keep, for a given frame size. */
    fun tailFrames(sampleRate: Int, frameSamples: Int, tailSeconds: Float): Int =
        (sampleRate * tailSeconds / frameSamples).toInt()

    /** Bytes of pre-confirmation audio to retain (PCM16 mono). */
    fun prerollCapacityBytes(sampleRate: Int, prerollSeconds: Float): Int =
        (sampleRate * prerollSeconds).toInt() * 2

    /**
     * Duration a flushed take should span: the pre-roll actually held, plus
     * everything since speech was confirmed. Used to reason about the expected
     * flush length in traces — a flush that merely equals
     * `confirmedSeconds` means the pre-roll never filled, which is exactly how
     * the first attempt at this failed (the staging guard skipped every frame
     * while `isSpeaking` was false, so the ring stayed empty).
     */
    fun expectedFlushSeconds(prerollHeldSeconds: Float, confirmedSeconds: Float): Float =
        prerollHeldSeconds + confirmedSeconds
}
