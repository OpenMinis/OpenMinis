package com.openminis.app.speech

/**
 * [T-android-vad-manual-stop] What a manually-stopped VAD take should do with
 * the audio it is holding.
 *
 * Extracted from [ProviderSpeechRecognitionEngine] so the rule can be tested
 * without an AudioRecord: the engine itself binds the mic and a Context and
 * cannot be constructed in a JVM test, and this decision is the whole of the
 * bug that was fixed — a manual stop used to fall off the end of the VAD path
 * producing NO terminal callback at all, leaving SpeechRecognitionManager in
 * FINISHING and the panel spinning on "Recognizing…" forever.
 */
internal enum class VadManualStopOutcome {
    /** Nothing was captured for this session — no session, no held audio. */
    TRANSCRIBE,

    /** Held audio is too short to be speech; report a no-match. */
    NO_MATCH,

    /** No active VAD session (already settled, or cancelled). */
    NOTHING_TO_DO,
    ;

    companion object {
        /**
         * @param hasSession an un-settled VAD session is present.
         * @param heldSeconds total duration of the segments held for merge.
         * @param floorSeconds below this a take counts as noise, not speech.
         *
         * Note both non-idle outcomes end in a terminal callback
         * (onFinal via transcription, or onError) — that is the invariant the
         * fix exists to guarantee.
         */
        fun decide(hasSession: Boolean, heldSeconds: Float, floorSeconds: Float): VadManualStopOutcome = when {
            !hasSession -> NOTHING_TO_DO
            heldSeconds > floorSeconds -> TRANSCRIBE
            else -> NO_MATCH
        }
    }
}
