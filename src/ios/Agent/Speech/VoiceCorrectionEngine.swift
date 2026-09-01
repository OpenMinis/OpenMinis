import Foundation

private let logger = AppLogger(category: "VoiceCorrection")

/// Orchestrates one correction: retrieve → fuse → assemble prompt → call the model →
/// validate the result (design §6, §12).
///
/// Everything here is best-effort. Every failure path — no model, retrieval timeout, LLM
/// error, an output that looks like a rewrite rather than a fix — resolves to "keep the
/// original transcript". Correction is an enhancement; it must never be able to block or
/// mangle a send (design §1 principle 1, restated as a performance constraint in §12.5).
actor VoiceCorrectionEngine {

    static let shared = VoiceCorrectionEngine()

    private let sources: [any CorrectionSignalSource]
    private let strategy: any CorrectionStrategy

    /// Short-TTL memo of phonetic-key → candidates. The same handful of names/terms
    /// recur constantly within a session, so this skips most SQLite round-trips on the
    /// critical path (design §12.4-3).
    private var cache: [String: (candidates: [CorrectionCandidate], at: Date)] = [:]
    private let cacheTTL: TimeInterval = 300
    private let cacheLimit = 200

    init(sources: [any CorrectionSignalSource] = [ConfusionDictionarySource(), TypedVocabularySource()],
         strategy: any CorrectionStrategy = LLMCorrectionStrategy()) {
        self.sources = sources
        self.strategy = strategy
    }

    // MARK: - Result

    struct Suggestion: Sendable {
        let original: String
        let corrected: String
        /// False when the model returned the text unchanged, or we rejected its output.
        let hasChange: Bool
        let modelGroupUsed: String
        let durationMs: Int
        /// nil on success; otherwise why we fell back to the original.
        let rejectedReason: String?
        /// "石塘→食堂，张山→张三" — for the UI's one-line summary (design §15.3.2).
        let diffSummary: String
        /// The (errorTerm, correctedTerm) pairs actually applied — the UI needs these to
        /// attribute negative feedback to the right rows if the user hits "ignore" (§15.3.4).
        let appliedPairs: [(from: String, to: String)]
    }

    // MARK: - Warm-up

    /// Pay the one-off costs BEFORE the critical path needs them.
    ///
    /// Measured on an iPhone 11: the first `TextSegmenter.segment()` of the process took
    /// **1934ms** (loading the ~5MB jieba dictionary + HMM model), against an 80ms
    /// retrieval budget (§12.4) and an 800ms end-to-end budget (§12.5). Every subsequent
    /// call was 10–12ms. So the budget isn't wrong — it's just that whoever corrects
    /// first would otherwise eat the entire dictionary load, and that user is the one who
    /// sees a 2-second stall.
    ///
    /// Call this off the critical path (app launch / voice panel appearing). Idempotent
    /// and cheap once warm; safe to call repeatedly.
    static func warmUp() {
        Task.detached(priority: .utility) {
            let started = Date()
            _ = TextSegmenter.shared.segment("预热分词词典")
            _ = PinyinNormalizer.shared.normalize("预热")
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            logger.info("[VoiceCorrection][Retrieve] warmUp completed durationMs=\(ms)")
        }
    }

    // MARK: - Retrieval (design §12.4)

    /// Tokenise, key, fan out across sources, fuse by weight×confidence, take top-N.
    func retrieveCandidates(for transcript: String, locale: String) async -> [CorrectionCandidate] {
        let started = Date()
        guard let normalizer = PhoneticNormalizerRegistry.normalizer(for: locale) else { return [] }

        let tokens = TextSegmenter.shared.segment(transcript)
        // Single-character tokens are dropped: a lone character's phonetic key collides
        // with dozens of unrelated homophones, so it produces noise, not signal.
        let keys = tokens
            .filter { $0.count >= 2 }
            .map { normalizer.normalize($0) }
            .filter { !$0.isEmpty }
            .uniqued()
        guard !keys.isEmpty else {
            #if DEBUG
            // Still record the shape: "0 keys" (every token was a single character) is a
            // real and otherwise invisible reason for a correction to have no evidence.
            lastRetrieval = (tokens: tokens, keys: [],
                             ms: Int(Date().timeIntervalSince(started) * 1000), cacheHit: false)
            #endif
            return []
        }

        let (cached, missing) = partitionByCache(keys)
        var candidates = cached

        if !missing.isEmpty {
            // Fan out concurrently — the sources are independent, and this keeps the
            // wall-clock at the slowest source rather than their sum.
            var fetched: [CorrectionCandidate] = []
            await withTaskGroup(of: [CorrectionCandidate].self) { group in
                for source in sources {
                    group.addTask { [missing] in
                        await source.lookupCandidates(forPhoneticKeys: missing,
                                                      locale: locale,
                                                      limit: VoiceCorrectionConfig.maxCandidates)
                    }
                }
                for await chunk in group { fetched.append(contentsOf: chunk) }
            }
            storeInCache(keys: missing, candidates: fetched)
            candidates.append(contentsOf: fetched)
        }

        let fused = fuse(candidates)
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        let bySource = Dictionary(grouping: fused, by: \.sourceIdentifier).mapValues(\.count)
        logger.info("[VoiceCorrection][Retrieve] tokens=\(tokens.count) keys=\(keys.count) candidatesFound=\(fused.count) sources=\(bySource) cacheHit=\(missing.isEmpty) durationMs=\(ms)")
        #if DEBUG
        lastRetrieval = (tokens: tokens, keys: keys, ms: ms, cacheHit: missing.isEmpty)
        #endif
        if ms > VoiceCorrectionConfig.retrievalBudgetMs {
            logger.warning("[VoiceCorrection][Retrieve] OVER BUDGET budget=\(VoiceCorrectionConfig.retrievalBudgetMs)ms elapsed=\(ms)ms")
        }
        return fused
    }

    /// Weighted fusion (design §6): score = source weight × candidate confidence. A term
    /// proposed by two sources keeps its best score rather than being double-counted.
    private func fuse(_ candidates: [CorrectionCandidate]) -> [CorrectionCandidate] {
        let weights = Dictionary(uniqueKeysWithValues: sources.map { ($0.sourceIdentifier, $0.defaultWeight) })
        func score(_ c: CorrectionCandidate) -> Double {
            (weights[c.sourceIdentifier] ?? 0.5) * c.confidence
        }
        var best: [String: CorrectionCandidate] = [:]   // key: phoneticKey|term
        for c in candidates {
            let k = "\(c.phoneticKey)|\(c.term)"
            if let existing = best[k], score(existing) >= score(c) { continue }
            best[k] = c
        }
        return best.values
            .sorted { score($0) > score($1) }
            .prefix(VoiceCorrectionConfig.maxCandidates)
            .map { $0 }
    }

    // MARK: - Full correction

    /// Retrieve + correct + validate. Never throws; a failure is a `Suggestion` with
    /// `hasChange == false` and a `rejectedReason`.
    ///
    /// `persistEvent: false` is what the debug RPCs pass — §10.3 forbids debugging from
    /// writing capture data, or we'd be teaching the system from our own test strings.
    func correct(transcript: String,
                 locale: String = "zh",
                 context: ConversationContext = .empty,
                 persistEvent: Bool = true,
                 trigger: String = "auto") async -> Suggestion {
        let started = Date()
        let text = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return Suggestion(original: transcript, corrected: transcript, hasChange: false,
                              modelGroupUsed: "none", durationMs: 0, rejectedReason: "empty_input",
                              diffSummary: "", appliedPairs: [])
        }

        let candidates = await retrieveCandidates(for: text, locale: locale)

        do {
            let outcome = try await withCorrectionTimeout(ms: VoiceCorrectionConfig.correctionBudgetMs) {
                try await self.strategy.correct(transcript: text, candidates: candidates,
                                                context: context, locale: locale)
            }

            #if DEBUG
            // [T-voice-correction-debug-capture] Open the trace only once the strategy has
            // returned, because the prompt it actually sent (and which evidence survived
            // clamping) is only knowable then. `finish` below closes it with the verdict.
            let traceID = await recordTraceBegin(trigger: trigger, locale: locale, text: text,
                                                 candidates: candidates, context: context,
                                                 outcome: outcome)
            #endif

            let corrected = outcome.correctedText
            if corrected == text {
                logger.info("[VoiceCorrection][Correct] modelGroup=\(outcome.modelGroupUsed) changed=false durationMs=\(outcome.durationMs)")
                #if DEBUG
                await VoiceCorrectionTrace.shared.finish(
                    id: traceID, corrected: corrected, changed: false, appliedPairs: [],
                    rejectedReason: nil, modelGroup: outcome.modelGroupUsed,
                    totalMs: outcome.durationMs)
                #endif
                return Suggestion(original: text, corrected: text, hasChange: false,
                                  modelGroupUsed: outcome.modelGroupUsed, durationMs: outcome.durationMs,
                                  rejectedReason: nil, diffSummary: "", appliedPairs: [])
            }

            // Guard against the model rewriting instead of correcting (design §6).
            let ratio = VoiceCorrectionDiff.charChangeRatio(from: text, to: corrected)
            if ratio > VoiceCorrectionConfig.maxCharChangeRatio {
                logger.warning("[VoiceCorrection][Correct] REJECTED reason=diff_too_large charChangeRatio=\(String(format: "%.2f", ratio)) threshold=\(VoiceCorrectionConfig.maxCharChangeRatio)")
                #if DEBUG
                // Keep the model's rejected output in `corrected` — seeing WHAT it tried
                // to rewrite is the whole point of tracing a rejection.
                await VoiceCorrectionTrace.shared.finish(
                    id: traceID, corrected: corrected, changed: false, appliedPairs: [],
                    rejectedReason: "diff_too_large", modelGroup: outcome.modelGroupUsed,
                    totalMs: outcome.durationMs)
                #endif
                return Suggestion(original: text, corrected: text, hasChange: false,
                                  modelGroupUsed: outcome.modelGroupUsed, durationMs: outcome.durationMs,
                                  rejectedReason: "diff_too_large", diffSummary: "", appliedPairs: [])
            }

            let pairs = VoiceCorrectionDiff.tokenPairs(from: text, to: corrected)
            let summary = pairs.map { "\($0.from)→\($0.to)" }.joined(separator: "，")
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            logger.info("[VoiceCorrection][Correct] modelGroup=\(outcome.modelGroupUsed) original_len=\(text.count) corrected_len=\(corrected.count) changed=true pairs=\(pairs.count) durationMs=\(ms)")

            if persistEvent, VoiceCorrectionCollectionConsent.collectionEnabled,
               let db = VoiceCorrectionDB.shared {
                await db.recordCorrectionEvent(original: text, corrected: corrected,
                                               outcome: "suggested",
                                               sourceSummary: summary.isEmpty ? nil : summary)
            }

            #if DEBUG
            await VoiceCorrectionTrace.shared.finish(
                id: traceID, corrected: corrected, changed: true, appliedPairs: pairs,
                rejectedReason: nil, modelGroup: outcome.modelGroupUsed, totalMs: ms)
            #endif

            return Suggestion(original: text, corrected: corrected, hasChange: true,
                              modelGroupUsed: outcome.modelGroupUsed, durationMs: ms,
                              rejectedReason: nil, diffSummary: summary, appliedPairs: pairs)
        } catch {
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            let reason = (error is CorrectionTimeoutError) ? "llm_timeout" : "llm_error:\(error)"
            logger.error("[VoiceCorrection][Correct] FAILED reason=\(reason) fallback=original_text durationMs=\(ms)")
            #if DEBUG
            // The strategy threw before returning an outcome, so there is no prompt and no
            // trace to close — open and immediately close a run that carries the retrieval
            // and context evidence anyway. A timeout that only ever fires when the digest
            // is large is exactly the pattern this makes visible.
            let failedID = await recordTraceBegin(trigger: trigger, locale: locale, text: text,
                                                  candidates: candidates, context: context,
                                                  outcome: nil)
            await VoiceCorrectionTrace.shared.finish(
                id: failedID, corrected: text, changed: false, appliedPairs: [],
                rejectedReason: reason, modelGroup: "none", totalMs: ms)
            #endif
            return Suggestion(original: text, corrected: text, hasChange: false,
                              modelGroupUsed: "none", durationMs: ms, rejectedReason: reason,
                              diffSummary: "", appliedPairs: [])
        }
    }

    #if DEBUG
    // MARK: - DEBUG trace [T-voice-correction-debug-capture]

    /// Retrieval telemetry from the most recent `retrieveCandidates` call, so the trace can
    /// report tokens/keys/timing without re-running segmentation (which would both cost
    /// ~10ms on the critical path and, worse, report numbers from a second run rather than
    /// the one that produced the candidates).
    private var lastRetrieval: (tokens: [String], keys: [String], ms: Int, cacheHit: Bool)?

    /// Assemble one trace entry. `outcome == nil` means the strategy threw, so there is no
    /// prompt to record — everything upstream of the model call is still captured.
    private func recordTraceBegin(trigger: String,
                                  locale: String,
                                  text: String,
                                  candidates: [CorrectionCandidate],
                                  context: ConversationContext,
                                  outcome: CorrectionOutcome?) async -> Int {
        let weights = Dictionary(uniqueKeysWithValues: sources.map { ($0.sourceIdentifier, $0.defaultWeight) })

        // The token that produced a candidate's key isn't carried on the candidate, so
        // resolve it back from the retrieval tokens. Built ONCE as a key→token map: doing
        // it inline per candidate would be O(candidates × tokens) pinyin normalizations
        // (~300 for a long transcript) on the correction path, to populate a debug field.
        // First token wins, matching the order `retrieveCandidates` keyed them in.
        var tokenForKey: [String: String] = [:]
        if let normalizer = PhoneticNormalizerRegistry.normalizer(for: locale) {
            for token in lastRetrieval?.tokens ?? [] where token.count >= 2 {
                let key = normalizer.normalize(token)
                if !key.isEmpty, tokenForKey[key] == nil { tokenForKey[key] = token }
            }
        }

        let candidateInfos = candidates.map { c in
            VoiceCorrectionTrace.CandidateInfo(
                token: tokenForKey[c.phoneticKey] ?? "",
                phoneticKey: c.phoneticKey, term: c.term, source: c.sourceIdentifier,
                confidence: c.confidence,
                fusedScore: (weights[c.sourceIdentifier] ?? 0.5) * c.confidence,
                evidence: c.evidence)
        }
        let digest = context.mining.digestScores.map {
            VoiceCorrectionTrace.ScoredTerm(term: $0.term, score: $0.score, count: $0.count,
                                            backgroundRank: BackgroundWordFrequency.shared.rank(of: $0.term))
        }
        // Excerpt bodies live in `context.recentExcerpts`, their metadata in
        // `context.mining.excerpts`. Both are produced by the builder in the same
        // oldest→newest order, so `zip` pairs them correctly — and, unlike index
        // arithmetic, degrades to "no rows" rather than to mispaired rows if a future
        // change ever makes the two lists diverge in length.
        let withText = zip(context.mining.excerpts, context.recentExcerpts).map { meta, line in
            VoiceCorrectionTrace.MessageExcerpt(
                role: meta.role, newestIndex: meta.newestIndex, kind: meta.kind,
                text: line, chars: meta.chars)
        }

        return await VoiceCorrectionTrace.shared.begin(
            trigger: trigger, locale: locale, transcript: text,
            segmentedTokens: lastRetrieval?.tokens ?? [],
            phoneticKeys: lastRetrieval?.keys ?? [],
            candidates: candidateInfos,
            retrievalMs: lastRetrieval?.ms ?? 0,
            retrievalCacheHit: lastRetrieval?.cacheHit ?? false,
            scannedMessageCount: context.mining.scannedMessageCount,
            digestTerms: digest,
            digestChars: context.rareTermsDigest?.count ?? 0,
            excerpts: withText,
            excerptChars: context.recentExcerpts.reduce(0) { $0 + $1.count },
            contextBuildMs: context.mining.buildMs,
            promptVocabTerms: outcome?.diagnostics?.vocabTerms ?? [],
            promptConfusionLines: outcome?.diagnostics?.confusionLines ?? [],
            blockChars: outcome?.diagnostics?.blockChars ?? [:],
            prompt: outcome?.prompt ?? "")
    }
    #endif

    /// Prompt the engine would send, without sending it — backs
    /// `debug.voiceCorrection.dryRunCorrection` (design §10.2b).
    func dryRun(transcript: String, locale: String = "zh", context: ConversationContext = .empty)
    async -> (tokens: [String], candidates: [CorrectionCandidate], prompt: String) {
        let tokens = TextSegmenter.shared.segment(transcript)
        let candidates = await retrieveCandidates(for: transcript, locale: locale)
        let prompt = LLMCorrectionStrategy.buildPrompt(transcript: transcript,
                                                       candidates: candidates, context: context)
        return (tokens, candidates, prompt)
    }

    // MARK: - Cache

    private func partitionByCache(_ keys: [String]) -> ([CorrectionCandidate], [String]) {
        let now = Date()
        var hits: [CorrectionCandidate] = []
        var misses: [String] = []
        for key in keys {
            if let entry = cache[key], now.timeIntervalSince(entry.at) < cacheTTL {
                hits.append(contentsOf: entry.candidates)
            } else {
                misses.append(key)
            }
        }
        return (hits, misses)
    }

    private func storeInCache(keys: [String], candidates: [CorrectionCandidate]) {
        let byKey = Dictionary(grouping: candidates, by: \.phoneticKey)
        let now = Date()
        for key in keys {
            // Cache the empty result too — a key that matched nothing is exactly the one
            // we don't want to re-query on every keystroke of a repeated phrase.
            cache[key] = (byKey[key] ?? [], now)
        }
        if cache.count > cacheLimit {
            let oldest = cache.sorted { $0.value.at < $1.value.at }.prefix(cache.count - cacheLimit)
            for (k, _) in oldest { cache.removeValue(forKey: k) }
        }
    }

    func clearCache() { cache.removeAll() }
}

// MARK: - Timeout

struct CorrectionTimeoutError: Error {}

/// Race the work against a deadline: whichever finishes first wins, the loser is
/// cancelled. Correction must never hold a send hostage (design §12.5).
func withCorrectionTimeout<T: Sendable>(ms: Int, _ work: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await work() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000)
            throw CorrectionTimeoutError()
        }
        guard let first = try await group.next() else { throw CorrectionTimeoutError() }
        group.cancelAll()
        return first
    }
}

private extension Array where Element == String {
    func uniqued() -> [String] {
        var seen = Set<String>()
        return filter { seen.insert($0).inserted }
    }
}
