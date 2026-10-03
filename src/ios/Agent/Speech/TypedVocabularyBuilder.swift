import Foundation

private let logger = AppLogger(category: "VoiceCorrection")

/// Builds `typed_vocabulary` from what the user has actually typed (design §3.3, §12.3).
///
/// Why typed messages and not the draft box: a sent message is an *implicit confirmation*
/// that the text is what the user meant. Drafts get rewritten and abandoned; harvesting
/// them would learn words the user rejected.
///
/// This exists to solve cold start. `confusion_dictionary` only knows what the user has
/// already corrected by hand — useless on day one. But their typed history already
/// contains the proper nouns an ASR will mangle ("CppJieba", a colleague's name, a project
/// codename), and those are exactly the terms worth feeding the corrector.
///
/// Runs OFF the interaction path (§12.3): background QoS, throttled, incremental.
actor TypedVocabularyBuilder {

    static let shared = TypedVocabularyBuilder()

    private static let cursorKey = "vocab_last_built_at"
    private static let lastBuildKey = "vocab_last_build_time"

    /// Don't rebuild on every message — batch up (§12.3-2).
    private let minNewMessages = 20
    private let minInterval: TimeInterval = 3600

    private var isBuilding = false

    /// Throttled entry point: call freely (e.g. after each send); it decides whether to
    /// actually run.
    func buildIfNeeded(force: Bool = false) async {
        guard !isBuilding else { return }
        guard let db = VoiceCorrectionDB.shared else { return }

        let lastBuild = Double(await db.metaValue(Self.lastBuildKey) ?? "0") ?? 0
        let sinceLast = Date().timeIntervalSince1970 - lastBuild
        if !force && sinceLast < minInterval {
            return
        }
        await build(force: force)
    }

    /// Full incremental build. Only messages newer than the stored cursor are scanned, so
    /// this stays O(new) rather than O(all history) (§12.3-1).
    func build(force: Bool = false) async {
        // [T-voice-correction-collection-consent] typed_vocabulary is derived
        // from the user's own messages — same opt-in gate as the other
        // learning tables (applies to debug-RPC forced builds too).
        guard VoiceCorrectionCollectionConsent.collectionEnabled else {
            logger.info("[VocabBuild] skipped — collection consent off")
            return
        }
        guard !isBuilding else { return }
        guard let db = VoiceCorrectionDB.shared else { return }
        isBuilding = true
        defer { isBuilding = false }

        let started = Date()
        let cursor = force ? 0 : (Double(await db.metaValue(Self.cursorKey) ?? "0") ?? 0)
        let messages = await Self.fetchUserMessages(since: cursor)
        guard !messages.isEmpty else {
            logger.info("[VoiceCorrection][VocabBuild] no new messages since cursor=\(cursor)")
            return
        }

        var stats = BuildStats()
        var newest = cursor

        for message in messages {
            newest = max(newest, message.timestamp)
            let day = VoiceCorrectionDB.dayKey(from: message.timestamp)
            for (term, occurrences, posTag) in VocabularyFilter.candidates(in: message.text) {
                stats.candidates += 1
                let decision = VocabularyFilter.evaluate(term: term, posTag: posTag)
                switch decision {
                case .rejected(let reason):
                    stats.reject(reason)
                case .accepted:
                    stats.accepted += 1
                    guard let key = PhoneticNormalizerRegistry.normalizer(for: "zh")?.normalize(term),
                          !key.isEmpty else { continue }
                    await db.upsertVocabulary(term: term,
                                              phoneticKey: key,
                                              locale: "zh",
                                              posTag: posTag,
                                              backgroundRank: BackgroundWordFrequency.shared.rank(of: term),
                                              occurrences: occurrences,
                                              day: day)
                }
            }
        }

        await db.setMetaValue(Self.cursorKey, String(newest))
        await db.setMetaValue(Self.lastBuildKey, String(Date().timeIntervalSince1970))

        let ms = Int(Date().timeIntervalSince(started) * 1000)
        logger.info("[VoiceCorrection][VocabBuild] completed messages=\(messages.count) candidates=\(stats.candidates) accepted=\(stats.accepted) rejected_stopword=\(stats.stopword) rejected_len=\(stats.tooShort) rejected_score=\(stats.lowScore) durationMs=\(ms)")
    }

    // MARK: - Message source

    struct SourceMessage {
        let text: String
        let timestamp: Double
    }

    /// User messages newer than `since`, with attachment markup stripped.
    ///
    /// Only `.text` parts are harvested. A message's other parts (mediaRef, toolUse) are
    /// metadata, not something the user typed — harvesting them would fill the vocabulary
    /// with filenames and tool identifiers. The `<user-attached-files>` strip mirrors
    /// `AIChatViewModel.fallbackTitle`'s rule, which the design (§6) says to reuse rather
    /// than reinvent.
    static func fetchUserMessages(since: Double) async -> [SourceMessage] {
        var result: [SourceMessage] = []
        // [T-child-session-leak] A child session's "user" messages are the
        // delegating model's brief, not text the person typed — skip them.
        let sessions = await ChatStore.shared.listSessions().filter { !$0.isChild }
        for session in sessions {
            let raws = await ChatStore.shared.loadMessages(sessionId: session.id)
            for raw in raws where raw.role == .user {
                let ts = raw.createdAt.timeIntervalSince1970
                guard ts > since else { continue }
                var text = ""
                for part in raw.parts {
                    if case .text(let s) = part { text += s }
                }
                let cleaned = stripAttachmentMarkup(text)
                guard !cleaned.isEmpty else { continue }
                result.append(SourceMessage(text: cleaned, timestamp: ts))
            }
        }
        return result
    }

    static func stripAttachmentMarkup(_ raw: String) -> String {
        var t = raw
        if let start = t.range(of: "<user-attached-files>"),
           let end = t.range(of: "</user-attached-files>") {
            t.removeSubrange(start.lowerBound..<end.upperBound)
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Stats

    private struct BuildStats {
        var candidates = 0
        var accepted = 0
        var stopword = 0
        var tooShort = 0
        var lowScore = 0
        mutating func reject(_ reason: String) {
            if reason.hasPrefix("stopword") { stopword += 1 }
            else if reason.hasPrefix("too_short") || reason.hasPrefix("too_long") || reason.hasPrefix("no_lexical") { tooShort += 1 }
            else { lowScore += 1 }
        }
    }
}
