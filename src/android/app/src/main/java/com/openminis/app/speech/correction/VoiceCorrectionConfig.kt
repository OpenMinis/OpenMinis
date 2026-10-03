package com.openminis.app.speech.correction

/**
 * [T-android-voice-correction] Tunables for the correction pipeline. Values
 * mirror iOS `Providers/Voice/CorrectionSignalSource.swift` exactly — changing
 * one here without changing it there makes the two platforms behave
 * differently on the same dictionary.
 */
object VoiceCorrectionConfig {

    /** Retrieval floor: below this a learned pair stops being offered. */
    const val MIN_CONFUSION_CONFIDENCE = 0.3

    /** Cap on candidates carried into the prompt. */
    const val MAX_CANDIDATES = 20

    /**
     * Observational budget for the retrieval stage. Exceeding it is LOGGED, not
     * enforced — matching iOS, where the only real timeout is the whole-
     * correction one below.
     */
    const val RETRIEVAL_BUDGET_MS = 80L

    /**
     * Hung-request ceiling for the LLM call, NOT a latency target.
     *
     * History, because this number has been wrong twice:
     *  - The design originally specified 800ms. On a real device with a real
     *    provider that timed out EVERY time (~830-860ms, request still in
     *    flight), so the trigger moved to "transcript has landed and the user
     *    is reading it" — nothing is blocked while this runs — and iOS raised
     *    the ceiling to 5s.
     *  - 5s was still too tight on Android. Measured against MiMo on a Pixel
     *    4a, a SHORT prompt round-trips in ~4.1s; a correction prompt carries
     *    evidence blocks plus conversation context and reliably exceeded 5s
     *    (observed 9.5s), so every correction returned llm_timeout.
     *
     * 15s is sized to catch a genuinely hung request while leaving room for a
     * slow provider on a slow device. Nothing waits on it — the user is
     * reading their transcript — so a generous ceiling costs nothing, whereas
     * a tight one silently disables the feature.
     */
    const val CORRECTION_BUDGET_MS = 15_000L

    /**
     * Reject an LLM result that changed more than half the characters — that is
     * a rewrite, not a correction.
     */
    const val MAX_CHAR_CHANGE_RATIO = 0.5

    /** A term must appear this often before retrieval will offer it. */
    const val VOCAB_MIN_FREQUENCY = 3

    /**
     * …and on this many distinct days. A term typed 50 times in one afternoon
     * is not yet established vocabulary.
     */
    const val VOCAB_MIN_DISTINCT_DAYS = 2

    /** Admission score a mined term must reach. */
    const val VOCAB_MIN_SCORE = 2

    /** Background-list rank above which a term still counts as "rare". */
    const val VOCAB_BACKGROUND_RANK_THRESHOLD = 3000

    /** Candidate cache time-to-live. */
    const val CACHE_TTL_MS = 300_000L

    /** Candidate cache entry ceiling. */
    const val CACHE_LIMIT = 200

    // -- Recorder admission --
    //
    // [T-android-correction-admission-multisignal] The live admission rule now
    // lives in CorrectionAdmission (multi-signal: homophone / acronym /
    // digit-norm, guarded by edit locality and a reorder check) — see
    // CorrectionAdmission.Config for its tunables. The two constants below are
    // the retired single-axis rule; they are NOT consulted when deciding
    // admission any more and are kept only because PhoneticNormalizerTest uses
    // the similarity floor to assert the normalizer's own behaviour.

    /**
     * Phonetic similarity floor. Still the value CorrectionAdmission uses for
     * its homophone signal (Config.PHONETIC_SIMILARITY), kept here for the
     * normalizer tests.
     */
    const val PHONETIC_SIMILARITY_THRESHOLD = 0.6

    /**
     * Length-ratio ceiling of the retired rule. Superseded by
     * CorrectionAdmission.Config.MAX_EDIT_LOCALITY, which measures the edit
     * against its SENTENCE rather than against the other side of the span.
     */
    @Deprecated("Superseded by CorrectionAdmission.Config.MAX_EDIT_LOCALITY")
    const val MAX_LENGTH_RATIO = 2.0

    /** Context snippet stored alongside a learned pair. */
    const val CONTEXT_SAMPLE_CHARS = 50
}

/**
 * Character budgets for the correction prompt, in characters (not tokens).
 * Mirrors iOS `CorrectionContextBudget`.
 */
object CorrectionContextBudget {
    /** Reported only; enforcement is implicit via the two sub-budgets. */
    const val MESSAGE_CONTEXT_TOTAL = 5000

    const val RARE_DIGEST = 200
    const val MESSAGE_EXCERPTS = 4800
    const val PER_MESSAGE_EXCERPT = 600
    const val GROUNDING_EXCERPT = 500
    const val CONFUSION_BLOCK = 800
    const val VOCAB_BLOCK = 400

    /** Cost guards, not prompt-size guards: bound the work a single build does. */
    const val MAX_MESSAGES_SCANNED = 12
    const val PER_MESSAGE_SCAN_CAP = 2000

    // [T-android-voice-viewport-context] The on-screen blocks (ScreenContextBuilder).
    // Separate from the history budgets above: the two sources coexist and
    // neither eats the other's room.

    /** Viewport block: text around the screen's centre, separators included. */
    const val SCREEN_VIEWPORT = 2000

    /** The newest assistant reply, on screen or not. */
    const val SCREEN_LATEST_REPLY = 2000

    /** How far past each viewport edge the window reaches, in screen-heights. */
    const val SCREEN_EXTENSION_SCREENS = 0.5
}
