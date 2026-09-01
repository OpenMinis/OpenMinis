import Foundation

private let logger = AppLogger(category: "VoiceCorrection")

/// Learns from the user manually fixing a transcript (design §5).
///
/// This is the highest-quality signal in the system: the user looked at what the ASR
/// produced, decided it was wrong, and typed what they actually meant. Everything else
/// (typed vocabulary, conversation context) is inference; this is ground truth.
///
/// The crucial job here is telling the two kinds of edit apart:
///   • **ASR correction** — "张山"→"张三". Same sounds, different characters. LEARN this.
///   • **Content rewrite** — "去吃饭"→"去看电影,顺便买点东西". The user changed their mind.
///     Learning it would poison the dictionary with nonsense "corrections".
///
/// The discriminator is phonetic: a genuine ASR error *sounds like* what was meant. If the
/// phonetic keys are far apart, the user rewrote rather than corrected (design §3.2).
actor VoiceCorrectionRecorder {

    static let shared = VoiceCorrectionRecorder()

    // Admission (homophone / acronym / digit-norm / locality / reorder) lives in
    // `CorrectionAdmission` [T-correction-admission-multisignal] — a pure,
    // unit-tested value type. The recorder just diffs, judges, and upserts.

    /// Entry point from `VoiceInputViewModel.endEditing` (design §12.2: fire-and-forget,
    /// off the UI path — the user's "resume recording" must not wait on any of this).
    ///
    /// `asrProvider` is recorded so that, later, a confusion pattern can be attributed to
    /// a specific recognizer rather than blamed on the user's speech.
    /// `source` marks where the edit came from — "asr_transcript" (voice
    /// transcript fix, the original signal) or "text_input" (composer
    /// select-and-replace, T-text-input-correction-source). Same diff /
    /// phonetic-admission / consent path either way; only the stored origin
    /// differs so downstream can weight or filter by source.
    func recordEdit(before: String, after: String, locale: String = "zh",
                    asrProvider: String = "", source: String = "asr_transcript") async {
        // [T-voice-correction-collection-consent] Persisting learning data is
        // opt-in (default off). Correction still runs; it just doesn't learn.
        //
        // The consent gate is evaluated as `persist` rather than an early return, so that
        // in DEBUG the trace below still sees the edit. Consent governs what we WRITE to
        // voice-correction.db; it is not a reason for a developer inspecting their own
        // device to be unable to see which cases the corrector missed. Nothing below
        // touches the DB when `persist` is false.
        let persist = VoiceCorrectionCollectionConsent.collectionEnabled
        #if !DEBUG
        guard persist else {
            logger.info("[VoiceCorrection][Record] skipped — collection consent off")
            return
        }
        #endif
        let beforeText = before.trimmingCharacters(in: .whitespacesAndNewlines)
        let afterText = after.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !beforeText.isEmpty, !afterText.isEmpty, beforeText != afterText else { return }
        guard let normalizer = PhoneticNormalizerRegistry.normalizer(for: locale) else { return }
        let db = VoiceCorrectionDB.shared
        #if !DEBUG
        guard db != nil else { return }
        #endif

        // Sentence-align first (§5.1): only sentences that actually changed are diffed, so
        // an edit at the end of a long transcript doesn't re-diff the untouched start.
        let beforeSentences = SentenceSplitter.split(beforeText)
        let afterSentences = SentenceSplitter.split(afterText)

        var candidates: [(from: String, to: String, context: String)] = []
        // Sentence counts can differ (the user split or merged sentences). Pair them
        // positionally where we can, and fall back to whole-text diff if the shapes differ
        // too much to align — a wrong alignment produces garbage pairs, which is worse
        // than a coarser diff.
        // Each candidate carries the length of the sentence it was diffed within,
        // so [T-correction-admission-multisignal] can score edit locality (a short
        // span inside a long sentence = local fix; a span that is most of a short
        // sentence = rewrite).
        var candidatesWithLen: [(from: String, to: String, context: String, sentenceLen: Int)] = []
        if beforeSentences.count == afterSentences.count {
            for (b, a) in zip(beforeSentences, afterSentences) where b != a {
                let sentenceLen = max(b.count, a.count)
                for pair in VoiceCorrectionDiff.tokenPairs(from: b, to: a) {
                    candidatesWithLen.append((pair.from, pair.to, b, sentenceLen))
                }
            }
        } else {
            let sentenceLen = max(beforeText.count, afterText.count)
            for pair in VoiceCorrectionDiff.tokenPairs(from: beforeText, to: afterText) {
                candidatesWithLen.append((pair.from, pair.to, beforeText, sentenceLen))
            }
        }

        var kept = 0
        var droppedRewrite = 0
        for candidate in candidatesWithLen {
            let fromKey = normalizer.normalize(candidate.from)
            let verdict = CorrectionAdmission.judge(from: candidate.from, to: candidate.to,
                                                    normalizer: normalizer,
                                                    sentenceLength: candidate.sentenceLen)
            #if DEBUG
            // Capture the span BEFORE the admission gate, so the trace also holds the
            // ones we drop — a legitimate fix misjudged as a "rewrite" is exactly the
            // case that is otherwise invisible (the log line carries lengths only).
            await VoiceCorrectionTrace.shared.recordManualEdit(
                source: source, locale: locale, before: beforeText, after: afterText,
                from: candidate.from, to: candidate.to,
                fromPhoneticKey: fromKey, toPhoneticKey: normalizer.normalize(candidate.to),
                admitted: verdict.isAdmitted && !fromKey.isEmpty,
                reason: fromKey.isEmpty ? "empty_phonetic_key" : verdict.reason,
                sentenceLen: candidate.sentenceLen)
            #endif
            guard verdict.isAdmitted, !fromKey.isEmpty else {
                droppedRewrite += 1
                // [T-log-noise-privacy 2026-07-18] Reason tag + lengths only —
                // never the verbatim span content.
                logger.info("[VoiceCorrection][Record] dropped reason=\(verdict.reason) originalLen=\(candidate.from.count) correctedLen=\(candidate.to.count)")
                continue
            }

            // Context sample capped: it's for debugging/display, not storage of the user's
            // whole message (design §3.2 "≤50 token").
            let context = String(candidate.context.prefix(50))
            if persist, let db {
                await db.upsertConfusion(phoneticKey: fromKey,
                                         originalVariant: candidate.from,
                                         correctedTerm: candidate.to,
                                         locale: locale,
                                         contextSample: context,
                                         asrProvider: asrProvider,
                                         source: source)
            }
            kept += 1
        }

        // `admitted` is the admission verdict; `persisted` says whether those rows were
        // actually written. They diverge in DEBUG with consent off — where the run still
        // proceeds so the trace can observe the misses — so log both rather than one
        // number that means different things depending on a flag.
        logger.info("[VoiceCorrection][Record] source=\(source) persisted=\(persist) diff candidates=\(candidatesWithLen.count) admitted=\(kept)(asr_correction) dropped=\(droppedRewrite)(rewrite) beforeLen=\(beforeText.count) afterLen=\(afterText.count)")
    }

    /// The user rejected an AI suggestion (design §15.3.4).
    ///
    /// This writes NEGATIVE feedback only. It deliberately does not write a positive row
    /// for the "ignored" text: the user declining our suggestion tells us the rule was
    /// wrong *here*, not that some new rule is right.
    func recordSuggestionRejected(pairs: [(from: String, to: String)], locale: String = "zh") async {
        guard VoiceCorrectionCollectionConsent.collectionEnabled else { return }
        guard let normalizer = PhoneticNormalizerRegistry.normalizer(for: locale) else { return }
        guard let db = VoiceCorrectionDB.shared else { return }
        for pair in pairs {
            let key = normalizer.normalize(pair.from)
            guard !key.isEmpty else { continue }
            await db.recordNegativeFeedback(phoneticKey: key, correctedTerm: pair.to, locale: locale)
        }
        await db.recordCorrectionEvent(original: pairs.map(\.from).joined(separator: ","),
                                       corrected: pairs.map(\.to).joined(separator: ","),
                                       outcome: "reverted",
                                       sourceSummary: nil)
    }

    /// The user accepted an AI suggestion (design §15.3.4).
    ///
    /// Records ONLY a metrics event — never a confusion row. The suggestion was itself
    /// derived from confusion_dictionary/typed_vocabulary, so writing it back would feed
    /// the model's own output in as fresh evidence: frequency inflates, confidence
    /// inflates, and the system ends up "learning" from an echo of itself. The design
    /// calls this out explicitly as the trap to avoid.
    func recordSuggestionAccepted(original: String, corrected: String, summary: String) async {
        guard VoiceCorrectionCollectionConsent.collectionEnabled else { return }
        guard let db = VoiceCorrectionDB.shared else { return }
        await db.recordCorrectionEvent(original: original, corrected: corrected,
                                       outcome: "accepted", sourceSummary: summary)
        // [T-log-noise-privacy 2026-07-18] Length only — the summary quotes
        // the user's spoken terms verbatim.
        logger.info("[VoiceCorrection][Feedback] accepted summaryLen=\(summary.count) (metrics only — NOT written back to confusion_dictionary)")
    }
}
