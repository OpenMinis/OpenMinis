import Foundation

/// A pluggable source of correction candidates (design §4.1).
///
/// The engine never knows *where* a candidate came from — it only fans out across the
/// registered sources and fuses their results by `defaultWeight × candidate.confidence`.
/// Adding ContactsSource / CalendarSource later (design §8) means writing one more
/// conformance and registering it; the retrieval/fusion code does not change.
protocol CorrectionSignalSource: Sendable {
    var sourceIdentifier: String { get }
    var defaultWeight: Double { get }
    /// Candidates whose *phonetic key* matches one of `keys`. Batched (not one call per
    /// token) because §12.4 forbids N round-trips for an N-token transcript.
    func lookupCandidates(forPhoneticKeys keys: [String], locale: String, limit: Int) async -> [CorrectionCandidate]
}

struct CorrectionCandidate: Sendable {
    /// The phonetic key this candidate answers — lets the engine map candidates back to
    /// the transcript token that produced the key.
    let phoneticKey: String
    /// The term the source believes is correct.
    let term: String
    /// Source-internal confidence (confusion_dictionary.confidence; a normalized
    /// frequency signal for the vocabulary source).
    let confidence: Double
    let sourceIdentifier: String
    /// Human-readable provenance for the prompt / debug output, e.g. "张山→张三 ×3".
    let evidence: String
}

// MARK: - Source 1: learned ASR corrections (highest trust)

/// Rows the user *explicitly* produced by fixing a transcript — the highest-quality
/// supervision we have, hence weight 1.0 (design §4.1).
struct ConfusionDictionarySource: CorrectionSignalSource {
    let sourceIdentifier = "confusion_dictionary"
    let defaultWeight: Double = 1.0

    /// Rows below this confidence are excluded in SQL — a rule the user has repeatedly
    /// disproven must stop polluting retrieval (design §3.2).
    let minConfidence: Double

    init(minConfidence: Double = VoiceCorrectionConfig.minConfusionConfidence) {
        self.minConfidence = minConfidence
    }

    func lookupCandidates(forPhoneticKeys keys: [String], locale: String, limit: Int) async -> [CorrectionCandidate] {
        guard let db = VoiceCorrectionDB.shared else { return [] }
        let rows = await db.lookupConfusion(phoneticKeys: keys, locale: locale,
                                            minConfidence: minConfidence, limit: limit)
        return rows.map { row in
            CorrectionCandidate(
                phoneticKey: row.phoneticKey,
                term: row.correctedTerm,
                confidence: row.confidence,
                sourceIdentifier: sourceIdentifier,
                // [T-correction-low-freq-evidence] "出现N次" read as a frequency
                // statistic, which let the model discount a freq=1 record when the
                // transcript looked grammatically valid. Naming it as an explicit
                // manual correction is what distinguishes this from a guess.
                evidence: "\(row.variants.first ?? row.phoneticKey)→\(row.correctedTerm)（用户已手动纠正\(row.frequency)次）")
        }
    }
}

// MARK: - Source 2: typed vocabulary (cold-start coverage)

/// Terms the user types often. Weaker evidence than an explicit correction — the user
/// never said "the ASR got this wrong", we only know the word exists in their
/// vocabulary — so weight 0.6 (design §4.1).
struct TypedVocabularySource: CorrectionSignalSource {
    let sourceIdentifier = "typed_vocabulary"
    let defaultWeight: Double = 0.6

    func lookupCandidates(forPhoneticKeys keys: [String], locale: String, limit: Int) async -> [CorrectionCandidate] {
        guard let db = VoiceCorrectionDB.shared else { return [] }
        let rows = await db.lookupVocabulary(phoneticKeys: keys, locale: locale, limit: limit)
        return rows.map { row in
            CorrectionCandidate(
                phoneticKey: row.phoneticKey,
                term: row.term,
                // Squash raw frequency into (0,1) so it's comparable with the confusion
                // source's probability-like confidence. Saturating (not linear) so a term
                // seen 50× doesn't outrank a *confirmed* correction just by being common.
                confidence: min(1.0, Double(row.frequency) / 10.0),
                sourceIdentifier: sourceIdentifier,
                evidence: "\(row.term)（常用词，出现\(row.frequency)次）")
        }
    }
}

// MARK: - Tunables

/// Thresholds in one place (design §4.4 asks for ConfigRegistry; centralizing them here
/// first keeps the wiring to that system a one-file change rather than a scattered hunt).
enum VoiceCorrectionConfig {
    /// Rows this unconfident are excluded from retrieval (design §3.2).
    static let minConfusionConfidence: Double = 0.3
    /// Top-N candidates handed to the model (design §6 "suggested N=20").
    static let maxCandidates: Int = 20
    /// Retrieval budget; blowing it means "no candidates", never "wait longer" (§12.4-5).
    static let retrievalBudgetMs: Int = 80

    /// Whole-correction budget INCLUDING the LLM round-trip.
    ///
    /// §12.5 specifies 800ms, but that number was written for the *send-time* trigger,
    /// where the user is actively waiting on the send. We adopted §15.3.1 instead: the
    /// correction fires when the transcript lands (`.result`), while the user is reading
    /// it and deciding whether to send. Nothing is blocked on it — the transcript is
    /// already on screen and already sendable — so the only real requirement is "finish
    /// before they press send".
    ///
    /// Measured on device (iPhone 11, real provider): 800ms times out *every* time —
    /// `llm_timeout` at ~830–860ms, with the request genuinely in flight. A network LLM
    /// round-trip simply does not complete in 800ms. Keeping the 800ms figure would mean
    /// shipping a feature that never once produces a suggestion.
    ///
    /// 5s is a ceiling against a hung request, not a latency target: the user is reading
    /// during this window, and if they send first the suggestion is simply discarded
    /// (design §15.4 "sent as-is (no action) → implicitly ignored").
    static let correctionBudgetMs: Int = 5000
    /// Reject the model's output if it rewrote more than this share of the text — that's
    /// a rewrite, not a correction (design §6 "result validation and fallback").
    static let maxCharChangeRatio: Double = 0.5
    /// Conversation-context soft cap and its hard ceiling (design §6).
    static let contextSoftLimit: Int = 500
    static let contextHardCeiling: Int = 600
    // typed_vocabulary intake (design §3.3)
    static let vocabMinFrequency: Int = 3
    static let vocabMinDistinctDays: Int = 2
    static let vocabMinScore: Int = 2
    static let vocabBackgroundRankThreshold: Int = 3000
}

// MARK: - Collection Consent (T-voice-correction-collection-consent)

/// User consent gate for PERSISTING voice-correction learning data
/// (correction_events / confusion_dictionary / typed_vocabulary in the local
/// voice-correction.db). Default OFF: nothing is written until the user opts
/// in — either via Settings → Permissions or the one-time prompt shown on the
/// first tap of the AI-correction button. Reading data that already exists is
/// NOT gated; the correction feature itself keeps working either way, it just
/// stops learning while collection is off.
///
/// Guards live at the WRITE layer (recorder / engine / vocabulary builder) so
/// any future call site is covered automatically.
@MainActor
final class VoiceCorrectionCollectionConsent: ObservableObject {
    static let shared = VoiceCorrectionCollectionConsent()

    nonisolated private static let enabledKey = "voiceCorrectionCollectionEnabled"
    nonisolated private static let promptedKey = "voiceCorrectionCollectionPrompted"

    @Published var isEnabled: Bool {
        didSet { UserDefaults.standard.set(isEnabled, forKey: Self.enabledKey) }
    }

    /// True once the one-time enable prompt has been shown and answered —
    /// whatever the answer was, it never fires again.
    @Published var hasPrompted: Bool {
        didSet { UserDefaults.standard.set(hasPrompted, forKey: Self.promptedKey) }
    }

    private init() {
        isEnabled = UserDefaults.standard.bool(forKey: Self.enabledKey)   // default false
        hasPrompted = UserDefaults.standard.bool(forKey: Self.promptedKey)
    }

    /// Thread-safe read for non-UI writers (recorder actors, engine tasks).
    /// UserDefaults is the source of truth and is itself thread-safe, so this
    /// read is safe off the main actor.
    nonisolated static var collectionEnabled: Bool {
        UserDefaults.standard.bool(forKey: enabledKey)
    }
}
