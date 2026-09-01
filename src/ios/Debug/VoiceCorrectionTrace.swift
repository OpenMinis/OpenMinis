#if DEBUG
import Foundation

private let logger = AppLogger(category: "VoiceCorrection")

/// DEBUG-only capture of what each REAL correction run actually fed the model.
///
/// Why this exists: the pre-existing `debug.voiceCorrection.*` methods can inspect the
/// DB (`vocabularyList`) and simulate on hand-typed text (`dryRunCorrection`), but they
/// cannot answer the question that actually matters when tuning the algorithm — "for the
/// correction that just ran on my phone, which hotwords were mined out of the
/// conversation, which vocabulary rows won the 400-char budget, and what did the model do
/// with them?". Reconstructing that from the log needed a 100MB grep and still lost the
/// per-term scores, because the per-message `[CorrectionContext]` lines are `.debug` level
/// and the winning vocab terms were never logged at all.
///
/// So every run appends one `Entry` here, holding the full evidence chain:
///   transcript → segmented tokens → retrieved candidates (per source, with scores)
///             → context mining (rare-term digest with scores + per-message excerpts)
///             → the vocab/confusion terms that survived budget clamping
///             → assembled prompt → model verdict.
///
/// Design constraints this respects:
///   • `#if DEBUG` in full — the type does not exist in Release, so neither does the
///     retention. Callers wrap their record sites in `#if DEBUG` too.
///   • In-memory ring only. Nothing is written to disk or to voice-correction.db —
///     §10.3's rule that debugging must never feed data back into the learned tables
///     applies just as much to a trace log that could later be mistaken for capture.
///   • Bounded: `maxEntries` runs, and every stored string is clamped, so a pathological
///     5k-char prompt series can't grow unbounded in a long-running debug session.
actor VoiceCorrectionTrace {

    static let shared = VoiceCorrectionTrace()

    /// Ring capacity. 40 runs is several days of real voice usage at the observed rate
    /// (~1-4 corrections/day) while staying trivially small in memory.
    private let maxEntries = 40
    /// Per-field clamp for the bulky text fields. The prompt is budgeted at ~5.5k chars
    /// upstream; this is a defensive ceiling, not the real limit.
    private static let maxPromptChars = 8000

    private var entries: [Entry] = []
    /// Monotonic run id so a caller can poll for "anything new since N".
    private var nextID = 1

    // MARK: - Entry

    struct ScoredTerm: Sendable {
        let term: String
        let score: Int
        /// Occurrences within the scanned window (digest terms), else nil.
        let count: Int?
        /// Background-word-list rank, nil when the term is OOV (which is itself signal).
        let backgroundRank: Int?
    }

    struct CandidateInfo: Sendable {
        let token: String
        let phoneticKey: String
        let term: String
        let source: String
        let confidence: Double
        /// source weight × confidence — the value `fuse()` actually ranked by.
        let fusedScore: Double
        let evidence: String
    }

    struct MessageExcerpt: Sendable {
        let role: String
        /// 0 = newest.
        let newestIndex: Int
        let kind: String       // "grounding" | "rare"
        let text: String
        let chars: Int
    }

    struct Entry: Sendable {
        let id: Int
        let at: Date
        let trigger: String            // "manual" | "auto" | "debug"
        let locale: String
        let transcript: String

        // --- retrieval
        let segmentedTokens: [String]
        let phoneticKeys: [String]
        let candidates: [CandidateInfo]
        let retrievalMs: Int
        let retrievalCacheHit: Bool

        // --- context mining (the "hotwords from reply text + user message" half)
        let scannedMessageCount: Int
        let digestTerms: [ScoredTerm]
        let digestChars: Int
        let excerpts: [MessageExcerpt]
        let excerptChars: Int
        let contextBuildMs: Int

        // --- what actually survived into the prompt
        let promptVocabTerms: [String]
        let promptConfusionLines: [String]
        let blockChars: [String: Int]   // vocab/confusion/digest/context/transcript/total
        let prompt: String

        // --- verdict
        var corrected: String?
        var changed: Bool?
        var appliedPairs: [[String: String]]?
        var rejectedReason: String?
        var modelGroup: String?
        var totalMs: Int?
    }

    // MARK: - Recording

    /// Open a run. Returns the id the later `finish` must quote. Runs are recorded even
    /// if `finish` never arrives (a crash / timeout mid-correction) — a half entry with
    /// `changed == nil` is itself the diagnosis.
    func begin(trigger: String,
               locale: String,
               transcript: String,
               segmentedTokens: [String],
               phoneticKeys: [String],
               candidates: [CandidateInfo],
               retrievalMs: Int,
               retrievalCacheHit: Bool,
               scannedMessageCount: Int,
               digestTerms: [ScoredTerm],
               digestChars: Int,
               excerpts: [MessageExcerpt],
               excerptChars: Int,
               contextBuildMs: Int,
               promptVocabTerms: [String],
               promptConfusionLines: [String],
               blockChars: [String: Int],
               prompt: String) -> Int {
        let id = nextID
        nextID += 1
        entries.append(Entry(
            id: id, at: Date(), trigger: trigger, locale: locale, transcript: transcript,
            segmentedTokens: segmentedTokens, phoneticKeys: phoneticKeys,
            candidates: candidates, retrievalMs: retrievalMs, retrievalCacheHit: retrievalCacheHit,
            scannedMessageCount: scannedMessageCount, digestTerms: digestTerms,
            digestChars: digestChars, excerpts: excerpts, excerptChars: excerptChars,
            contextBuildMs: contextBuildMs,
            promptVocabTerms: promptVocabTerms, promptConfusionLines: promptConfusionLines,
            blockChars: blockChars, prompt: String(prompt.prefix(Self.maxPromptChars)),
            corrected: nil, changed: nil, appliedPairs: nil,
            rejectedReason: nil, modelGroup: nil, totalMs: nil))
        if entries.count > maxEntries { entries.removeFirst(entries.count - maxEntries) }
        return id
    }

    /// Close the run opened by `begin`. A no-op if the id already aged out of the ring.
    func finish(id: Int,
                corrected: String,
                changed: Bool,
                appliedPairs: [(from: String, to: String)],
                rejectedReason: String?,
                modelGroup: String,
                totalMs: Int) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].corrected = corrected
        entries[idx].changed = changed
        entries[idx].appliedPairs = appliedPairs.map { ["from": $0.from, "to": $0.to] }
        entries[idx].rejectedReason = rejectedReason
        entries[idx].modelGroup = modelGroup
        entries[idx].totalMs = totalMs
    }

    // MARK: - Reading

    func recent(limit: Int, sinceID: Int?) -> [Entry] {
        var out = entries
        if let since = sinceID { out = out.filter { $0.id > since } }
        return Array(out.suffix(limit))
    }

    func clear() {
        entries.removeAll()
        manualEdits.removeAll()
        logger.info("[VoiceCorrection][Trace] cleared")
    }

    func count() -> Int { entries.count }

    // MARK: - Manual edits (the misses)

    /// One span the user fixed BY HAND after the AI had its turn.
    ///
    /// This is the other half of the picture, and the more valuable one for tuning: a
    /// trace `Entry` shows what the corrector *did*, while these show what it *should
    /// have done and didn't*. Every row here is by definition a case the pipeline failed
    /// to catch — either it never suggested anything, or it suggested the wrong thing and
    /// the user overrode it.
    ///
    /// Crucially this records spans that `CorrectionAdmission` REJECTED as well as the
    /// admitted ones. A rejected span is invisible everywhere else (the log line carries
    /// only lengths, per T-log-noise-privacy, and nothing reaches the DB), yet a
    /// legitimate ASR fix being misjudged as a "rewrite" is one of the likelier reasons
    /// the confusion table stays at 4 rows while the user keeps fixing things by hand.
    struct ManualEdit: Sendable {
        let id: Int
        let at: Date
        let source: String          // "asr_transcript" | "text_input" | debug
        let locale: String
        let before: String
        let after: String
        let from: String            // the span as spoken/recognized
        let to: String              // what the user replaced it with
        let fromPhoneticKey: String
        let toPhoneticKey: String
        /// True when CorrectionAdmission let it through to confusion_dictionary.
        let admitted: Bool
        /// Admission verdict tag — "homophone", "rewrite_too_far", … Why it was kept/dropped.
        let reason: String
        /// Length of the sentence the span sits in — the locality signal admission uses.
        let sentenceLen: Int
        /// Did a correction run precede this edit on the same text? nil when unknown.
        /// Non-nil links the miss back to the `Entry` whose prompt failed to fix it.
        let precedingRunID: Int?
    }

    private var manualEdits: [ManualEdit] = []
    private var nextEditID = 1
    private let maxManualEdits = 120

    func recordManualEdit(source: String,
                          locale: String,
                          before: String,
                          after: String,
                          from: String,
                          to: String,
                          fromPhoneticKey: String,
                          toPhoneticKey: String,
                          admitted: Bool,
                          reason: String,
                          sentenceLen: Int) {
        // Link to the most recent correction run over the same transcript, when there is
        // one — that is the run whose prompt should have caught this and didn't.
        let precedingRunID = entries.last(where: {
            $0.transcript == before.trimmingCharacters(in: .whitespacesAndNewlines)
                || $0.corrected == before.trimmingCharacters(in: .whitespacesAndNewlines)
        })?.id

        manualEdits.append(ManualEdit(
            id: nextEditID, at: Date(), source: source, locale: locale,
            before: before, after: after, from: from, to: to,
            fromPhoneticKey: fromPhoneticKey, toPhoneticKey: toPhoneticKey,
            admitted: admitted, reason: reason, sentenceLen: sentenceLen,
            precedingRunID: precedingRunID))
        nextEditID += 1
        if manualEdits.count > maxManualEdits {
            manualEdits.removeFirst(manualEdits.count - maxManualEdits)
        }
    }

    func recentManualEdits(limit: Int, admittedOnly: Bool?) -> [ManualEdit] {
        var out = manualEdits
        if let flag = admittedOnly { out = out.filter { $0.admitted == flag } }
        return Array(out.suffix(limit))
    }
}
#endif
