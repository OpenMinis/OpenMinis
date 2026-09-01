#if DEBUG
import Foundation

/// `debug.voiceCorrection.*` JSON-RPC methods (design §10).
///
/// The whole file is `#if DEBUG`, matching every other debug-server handler — so these
/// methods simply do not exist in Release, which is what the task's constraint #4 asks
/// for (the debug server is already a DEBUG-only subsystem; no extra gating needed).
///
/// Two hard rules from design §10.3, enforced here:
///   • `dryRunCorrection` / `runCorrection` MUST NOT write capture data. Debugging must
///     never feed synthetic text back in as if it were real user behavior — that would
///     poison the very dictionary we're trying to evaluate.
///   • Destructive methods (`clearTable`, `injectConfusionRecord`) are flagged as such
///     in their registry descriptions so they can't be invoked casually.
@MainActor
enum VoiceCorrectionDebugRPC {

    /// Local required-param helper. `DebugRPCProviderChat` has an identical one but it's
    /// `private` to that file; duplicating three lines here is cheaper (and less
    /// intrusive) than widening another file's access level.
    private static func require<T>(_ value: T?, _ name: String) throws -> T {
        guard let v = value else { throw DebugRPCErr(-32602, "Missing required param: \(name)") }
        return v
    }

    // MARK: (a) Data viewing

    static func confusionList(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "voice-correction.db unavailable") }
        let orderBy = (params["orderBy"] as? String) ?? "confidence"
        let limit = (params["limit"] as? Int) ?? 50
        let locale = params["locale"] as? String
        let rows = await db.allConfusion(orderBy: orderBy, locale: locale, limit: limit)
        return [
            "count": rows.count,
            "rows": rows.map(confusionDict),
        ]
    }

    static func vocabularyList(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "voice-correction.db unavailable") }
        let orderBy = (params["orderBy"] as? String) ?? "frequency"
        let limit = (params["limit"] as? Int) ?? 50
        let rows = await db.allVocabulary(orderBy: orderBy, limit: limit)
        return [
            "count": rows.count,
            "rows": rows.map(vocabDict),
        ]
    }

    /// Cross-table history for one term, plus the phonetic key it hashes to — the fastest
    /// way to answer "why didn't this get corrected?" (design §10.2a).
    static func lookup(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "voice-correction.db unavailable") }
        let term = try require(params["term"] as? String, "term")
        let locale = (params["locale"] as? String) ?? "zh"
        let key = PhoneticNormalizerRegistry.normalizer(for: locale)?.normalize(term) ?? ""
        let confusion = await db.lookupConfusion(phoneticKeys: [key], locale: locale, minConfidence: 0, limit: 50)
        let vocab = await db.lookupVocabulary(phoneticKeys: [key], locale: locale, limit: 50)
        return [
            "term": term,
            "phoneticKey": key,
            "confusionMatches": confusion.map(confusionDict),
            "vocabularyMatches": vocab.map(vocabDict),
        ]
    }

    /// DB overview: row counts, schema version, file path/size. Doubles as the §11.5
    /// compatibility-matrix probe (user_version + the columns actually present).
    static func stats(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "voice-correction.db unavailable") }
        let path = await db.dbPath()
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? Int) ?? nil
        return [
            "dbPath": path,
            "dbSizeBytes": size ?? 0,
            "userVersion": await db.userVersion(),
            "schemaVersion": VoiceCorrectionDB.schemaVersion,
            "confusionRows": await db.rowCount(table: "confusion_dictionary"),
            "vocabularyRows": await db.rowCount(table: "typed_vocabulary"),
            "correctionEvents": await db.rowCount(table: "correction_events"),
        ]
    }

    // MARK: (b) Replay / tuning — the highest-value pair (design §10.2b)

    /// Retrieval + fusion + prompt assembly, WITHOUT calling the model. Answers "did the
    /// lookup even find anything?" in one shot, instead of the speak→edit→accumulate
    /// loop. Writes nothing (design §10.3).
    static func dryRunCorrection(params: [String: Any]) async throws -> [String: Any] {
        let transcript = try require(params["transcript"] as? String, "transcript")
        let locale = (params["locale"] as? String) ?? "zh"
        let context = contextFromParams(params)

        let started = Date()
        let result = await VoiceCorrectionEngine.shared.dryRun(transcript: transcript,
                                                               locale: locale, context: context)
        let ms = Int(Date().timeIntervalSince(started) * 1000)

        // Group candidates under the token that produced their phonetic key, which is how
        // you actually read this output ("did 张山 find anything?").
        let normalizer = PhoneticNormalizerRegistry.normalizer(for: locale)
        var byToken: [[String: Any]] = []
        for token in result.tokens where token.count >= 2 {
            let key = normalizer?.normalize(token) ?? ""
            let matches = result.candidates.filter { $0.phoneticKey == key }
            guard !matches.isEmpty else { continue }
            byToken.append([
                "token": token,
                "phoneticKey": key,
                "matches": matches.map {
                    ["term": $0.term, "source": $0.sourceIdentifier,
                     "confidence": $0.confidence, "evidence": $0.evidence]
                },
            ])
        }

        return [
            "segmentedTokens": result.tokens,
            "candidateCount": result.candidates.count,
            "candidates": byToken,
            "assembledPrompt": result.prompt,
            "retrievalMs": ms,
            "retrievalBudgetMs": VoiceCorrectionConfig.retrievalBudgetMs,
        ]
    }

    /// Full correction INCLUDING the model call. `persistEvent: false` — a debug run must
    /// never write capture data, or we'd be training the system on our own test strings
    /// (design §10.3).
    static func runCorrection(params: [String: Any]) async throws -> [String: Any] {
        let transcript = try require(params["transcript"] as? String, "transcript")
        let locale = (params["locale"] as? String) ?? "zh"
        let context = contextFromParams(params)

        let s = await VoiceCorrectionEngine.shared.correct(transcript: transcript,
                                                           locale: locale,
                                                           context: context,
                                                           persistEvent: false,
                                                           trigger: "debug")
        return [
            "original": s.original,
            "corrected": s.corrected,
            "diffApplied": s.hasChange,
            "diffSummary": s.diffSummary,
            "modelGroupUsed": s.modelGroupUsed,
            "durationMs": s.durationMs,
            "rejectedReason": s.rejectedReason ?? NSNull(),
        ]
    }

    /// Two ways to supply context, in priority order:
    ///
    /// 1. `messages: [{role:"user"|"assistant", text}]` — runs the REAL
    ///    `CorrectionContextBuilder`, so the rare-term digest, the rarity scores and the
    ///    excerpt selection are all produced by the production code path. This is the one
    ///    to use when tuning the mining algorithm; the legacy form below bypasses the
    ///    builder entirely and therefore can never exercise it.
    /// 2. `lastUserMessage` / `lastAgentReply` — the original two-field form, kept so
    ///    existing scripts and the §10.2b examples keep working unchanged.
    private static func contextFromParams(_ params: [String: Any]) -> ConversationContext {
        if let raw = params["messages"] as? [[String: Any]], !raw.isEmpty {
            let source: [CorrectionSourceMessage] = raw.compactMap { m in
                guard let text = m["text"] as? String, !text.isEmpty else { return nil }
                let role: CorrectionSourceMessage.Role =
                    (m["role"] as? String) == "assistant" ? .assistant : .user
                return CorrectionSourceMessage(
                    role: role, text: TypedVocabularyBuilder.stripAttachmentMarkup(text))
            }
            return CorrectionContextBuilder.build(messages: source)
        }
        return ConversationContext(
            lastUserMessage: (params["lastUserMessage"] as? String).map {
                ConversationContextTruncator.truncate($0).text
            },
            lastAgentReply: (params["lastAgentReply"] as? String).map {
                ConversationContextTruncator.truncate($0).text
            })
    }

    /// Run the typed_vocabulary intake rules over arbitrary text and show, per candidate,
    /// exactly why it was kept or dropped — WITHOUT writing anything. This replaces the
    /// "send 20 messages and see what sticks" loop when tuning the stopword list or the
    /// score threshold (design §10.2b).
    static func simulateVocabularyFilter(params: [String: Any]) async throws -> [String: Any] {
        let text = try require(params["text"] as? String, "text")
        var rows: [[String: Any]] = []
        for (term, occurrences, posTag) in VocabularyFilter.candidates(in: text) {
            let rank = BackgroundWordFrequency.shared.rank(of: term)
            switch VocabularyFilter.evaluate(term: term, posTag: posTag) {
            case .rejected(let reason):
                rows.append(["term": term, "rejected": true, "reason": reason,
                             "posTag": posTag ?? NSNull(), "occurrences": occurrences])
            case .accepted(let score, let b):
                rows.append([
                    "term": term, "rejected": false, "score": score,
                    "posTag": posTag ?? NSNull(), "occurrences": occurrences,
                    "backgroundRank": rank ?? NSNull(),
                    // Straight from evaluate() — never recomputed here, or the two copies drift.
                    "breakdown": ["backgroundRank": b.backgroundRank, "posTag": b.posTag,
                                  "hasLatinOrDigit": b.hasLatinOrDigit, "lengthOk": b.lengthOk],
                ])
            }
        }
        return ["count": rows.count, "candidates": rows,
                "backgroundListVersion": BackgroundWordFrequency.shared.version]
    }

    /// Force a full typed_vocabulary rebuild now (normally throttled + incremental).
    static func rebuildVocabulary(params: [String: Any]) async throws -> [String: Any] {
        let force = (params["force"] as? Bool) ?? true
        let started = Date()
        await TypedVocabularyBuilder.shared.build(force: force)
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "db unavailable") }
        return [
            "ok": true,
            "durationMs": Int(Date().timeIntervalSince(started) * 1000),
            "vocabularyRows": await db.rowCount(table: "typed_vocabulary"),
        ]
    }

    // MARK: (d) Effect metrics (design §10.2d)

    /// Acceptance rate over the window. This is the number that answers the product
    /// question the whole feature rests on — "is it actually getting better with use?".
    static func metrics(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "db unavailable") }
        let sinceDays = (params["sinceDays"] as? Int) ?? 30
        let counts = await db.correctionEventCounts(sinceDays: sinceDays)
        let accepted = counts["accepted"] ?? 0
        let reverted = counts["reverted"] ?? 0
        let suggested = counts["suggested"] ?? 0
        let decided = accepted + reverted
        return [
            "sinceDays": sinceDays,
            "suggestedCount": suggested,
            "acceptedCount": accepted,
            "revertedCount": reverted,
            // Undecided suggestions (user just sent without touching them) are excluded
            // from the denominator: "ignored" is not the same as "rejected".
            "acceptanceRate": decided > 0 ? Double(accepted) / Double(decided) : 0,
            "topConfusionPairs": (await db.allConfusion(orderBy: "confidence", locale: nil, limit: 10))
                .map { ["original": $0.variants.first ?? $0.phoneticKey, "corrected": $0.correctedTerm,
                        "frequency": $0.frequency, "confidence": $0.confidence] },
        ]
    }

    /// Aggregate shape of typed_vocabulary — the health check for the table that fills the
    /// prompt's largest evidence block.
    ///
    /// `vocabularyList` can only show a page of rows, so answering "how much of this table
    /// is noise?" meant pulling thousands of rows over RPC and bucketing them client-side.
    /// The frequency histogram and the noise classes below are the numbers that decide
    /// whether the intake filter needs tightening: `hexLike`/`urlEncoded` are pure
    /// segmentation garbage, and a large `singleOccurrence` share means the table is mostly
    /// terms seen once, which can never outrank real vocabulary for a prompt slot anyway.
    static func vocabularyStats(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "db unavailable") }
        let sample = (params["sample"] as? Int) ?? 5000
        let rows = await db.allVocabulary(orderBy: "frequency", limit: sample)

        var histogram: [String: Int] = [:]
        for r in rows {
            let bucket: String
            switch r.frequency {
            case ...1: bucket = "1"
            case 2...3: bucket = "2-3"
            case 4...10: bucket = "4-10"
            case 11...50: bucket = "11-50"
            case 51...200: bucket = "51-200"
            default: bucket = "200+"
            }
            histogram[bucket, default: 0] += 1
        }

        // Noise classes, each with examples so a threshold change can be sanity-checked
        // against the actual terms it would drop.
        func isHexLike(_ t: String) -> Bool {
            t.count >= 4 && t.allSatisfy { $0.isHexDigit } && t.contains { $0.isNumber }
        }
        func isURLEncoded(_ t: String) -> Bool {
            let lower = t.lowercased()
            return t.count <= 12 && (lower.hasPrefix("2f") || lower.hasPrefix("3a")
                || lower.hasPrefix("3d") || lower.hasPrefix("3f") || lower.hasPrefix("5d"))
        }
        // Path fragments repeatedly observed to enter via attachment markup rather than
        // anything the user typed as prose.
        let pathish: Set<String> = ["image", "images", "var", "attachments", "uploads",
                                    "attached", "jpg", "jpeg", "png", "photo", "tmp",
                                    "documents", "library", "file", "files"]
        func classify(_ t: String) -> String? {
            if isHexLike(t) { return "hexLike" }
            if isURLEncoded(t) { return "urlEncoded" }
            if pathish.contains(t.lowercased()) { return "pathFragment" }
            return nil
        }

        var classCounts: [String: Int] = [:]
        var examples: [String: [String]] = [:]
        for r in rows {
            guard let cls = classify(r.term) else { continue }
            classCounts[cls, default: 0] += 1
            if examples[cls, default: []].count < 12 { examples[cls, default: []].append(r.term) }
        }

        let singleOccurrence = rows.filter { $0.frequency <= 1 }.count
        let oov = rows.filter { $0.backgroundRank == nil }.count
        return [
            "totalRows": await db.rowCount(table: "typed_vocabulary"),
            "sampled": rows.count,
            "frequencyHistogram": histogram,
            "singleOccurrence": singleOccurrence,
            // OOV against the background list is the signal the rarity scorer leans on —
            // a very high share means the background list is too small to discriminate.
            "outOfBackgroundVocabulary": oov,
            "noiseClasses": classCounts,
            "noiseExamples": examples,
            "backgroundListVersion": BackgroundWordFrequency.shared.version,
        ]
    }

    // MARK: (e) Run trace [T-voice-correction-debug-capture]

    /// Every REAL correction run, with the full evidence chain that produced it.
    ///
    /// This is the method to reach for when tuning the algorithm: unlike `dryRunCorrection`
    /// (which corrects text you typed, with a context you supply) it reports what actually
    /// happened on the device — which hotwords were mined out of the conversation, which
    /// vocabulary rows won the 400-char budget, and what the model did with them.
    static func traceList(params: [String: Any]) async throws -> [String: Any] {
        let limit = (params["limit"] as? Int) ?? 10
        let sinceID = params["sinceID"] as? Int
        let includePrompt = (params["includePrompt"] as? Bool) ?? false
        let entries = await VoiceCorrectionTrace.shared.recent(limit: limit, sinceID: sinceID)
        return [
            "count": entries.count,
            "totalRetained": await VoiceCorrectionTrace.shared.count(),
            "runs": entries.map { traceDict($0, includePrompt: includePrompt) },
        ]
    }

    /// Spans the user fixed BY HAND — i.e. the cases the corrector missed.
    ///
    /// `admittedOnly:false` is the interesting filter: those are edits `CorrectionAdmission`
    /// judged to be rewrites rather than ASR errors, so they never reached
    /// confusion_dictionary. If a genuine fix shows up there, the admission thresholds are
    /// what needs tuning — and that is invisible from the DB alone, since nothing was written.
    static func traceManualEdits(params: [String: Any]) async throws -> [String: Any] {
        let limit = (params["limit"] as? Int) ?? 30
        let admittedOnly = params["admittedOnly"] as? Bool
        let edits = await VoiceCorrectionTrace.shared.recentManualEdits(limit: limit,
                                                                        admittedOnly: admittedOnly)
        return [
            "count": edits.count,
            "edits": edits.map { e in
                [
                    "id": e.id, "at": e.at.timeIntervalSince1970, "source": e.source,
                    "locale": e.locale, "from": e.from, "to": e.to,
                    "fromPhoneticKey": e.fromPhoneticKey, "toPhoneticKey": e.toPhoneticKey,
                    "phoneticKeyMatch": e.fromPhoneticKey == e.toPhoneticKey,
                    "admitted": e.admitted, "reason": e.reason,
                    "sentenceLen": e.sentenceLen,
                    "before": e.before, "after": e.after,
                    "precedingRunID": e.precedingRunID ?? NSNull(),
                ] as [String: Any]
            },
        ]
    }

    static func traceClear(params: [String: Any]) async throws -> [String: Any] {
        await VoiceCorrectionTrace.shared.clear()
        return ["ok": true]
    }

    private static func traceDict(_ e: VoiceCorrectionTrace.Entry,
                                  includePrompt: Bool) -> [String: Any] {
        var out: [String: Any] = [
            "id": e.id,
            "at": e.at.timeIntervalSince1970,
            "trigger": e.trigger,
            "locale": e.locale,
            "transcript": e.transcript,
            "segmentedTokens": e.segmentedTokens,
            "phoneticKeys": e.phoneticKeys,
            "retrieval": [
                "ms": e.retrievalMs, "cacheHit": e.retrievalCacheHit,
                "candidateCount": e.candidates.count,
                "candidates": e.candidates.map {
                    ["token": $0.token, "phoneticKey": $0.phoneticKey, "term": $0.term,
                     "source": $0.source, "confidence": $0.confidence,
                     "fusedScore": $0.fusedScore, "evidence": $0.evidence] as [String: Any]
                },
            ] as [String: Any],
            // The conversation-mining half: what got pulled out of the user's messages
            // and the AI's replies, with the rarity score that earned each term its slot.
            "contextMining": [
                "scannedMessages": e.scannedMessageCount,
                "buildMs": e.contextBuildMs,
                "digestChars": e.digestChars,
                "digestTerms": e.digestTerms.map {
                    ["term": $0.term, "score": $0.score,
                     "count": $0.count ?? NSNull(),
                     "backgroundRank": $0.backgroundRank ?? NSNull()] as [String: Any]
                },
                "excerptChars": e.excerptChars,
                "excerpts": e.excerpts.map {
                    ["role": $0.role, "newestIndex": $0.newestIndex, "kind": $0.kind,
                     "chars": $0.chars, "text": $0.text] as [String: Any]
                },
            ] as [String: Any],
            // What survived budget clamping into the prompt. Comparing `promptVocabTerms`
            // against `retrieval.candidates` shows exactly which evidence got squeezed out.
            "promptEvidence": [
                "vocabTerms": e.promptVocabTerms,
                "confusionLines": e.promptConfusionLines,
                "blockChars": e.blockChars,
            ] as [String: Any],
            "verdict": [
                "corrected": e.corrected ?? NSNull(),
                "changed": e.changed ?? NSNull(),
                "appliedPairs": e.appliedPairs ?? [],
                "rejectedReason": e.rejectedReason ?? NSNull(),
                "modelGroup": e.modelGroup ?? NSNull(),
                "totalMs": e.totalMs ?? NSNull(),
            ] as [String: Any],
        ]
        // Off by default: a full prompt is ~2.5k chars and swamps a multi-run listing.
        if includePrompt { out["prompt"] = e.prompt }
        return out
    }

    // MARK: (c) Admin / test-scaffolding (DESTRUCTIVE — see registry descriptions)

    /// Insert a confusion row without going through a real speak→edit cycle, so a
    /// correction scenario can be staged in one call (design §10.2c).
    static func injectConfusionRecord(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "voice-correction.db unavailable") }
        let original = try require(params["original"] as? String, "original")
        let corrected = try require(params["corrected"] as? String, "corrected")
        let locale = (params["locale"] as? String) ?? "zh"
        guard let normalizer = PhoneticNormalizerRegistry.normalizer(for: locale) else {
            throw DebugRPCErr(-32602, "no phonetic normalizer for locale \(locale)")
        }
        let key = normalizer.normalize(original)
        await db.upsertConfusion(phoneticKey: key,
                                 originalVariant: original,
                                 correctedTerm: corrected,
                                 locale: locale,
                                 contextSample: "(debug-injected)",
                                 asrProvider: "debug")
        return ["ok": true, "phoneticKey": key, "original": original, "corrected": corrected]
    }

    /// [T-text-input-correction-source] Drive the FULL recordEdit pipeline
    /// (diff → phonetic admission → consent gate → upsert with source tag) so
    /// the text-input capture path can be verified end-to-end on device —
    /// synthetic taps can't perform a real select-and-replace in the composer.
    static func recordManualEdit(params: [String: Any]) async throws -> [String: Any] {
        let before = try require(params["before"] as? String, "before")
        let after = try require(params["after"] as? String, "after")
        let locale = (params["locale"] as? String) ?? "zh"
        let source = (params["source"] as? String) ?? "text_input"
        await VoiceCorrectionRecorder.shared.recordEdit(before: before, after: after,
                                                        locale: locale, source: source)
        return ["ok": true, "before": before, "after": after, "source": source,
                "consentEnabled": VoiceCorrectionCollectionConsent.collectionEnabled]
    }

    static func clearTable(params: [String: Any]) async throws -> [String: Any] {
        guard let db = VoiceCorrectionDB.shared else { throw DebugRPCErr(-32603, "voice-correction.db unavailable") }
        let table = try require(params["table"] as? String, "table")
        guard (params["confirm"] as? Bool) == true else {
            throw DebugRPCErr(-32602, "refusing to clear \(table) without confirm:true")
        }
        let ok = await db.clearTable(table)
        return ["ok": ok, "table": table]
    }

    // MARK: - Encoding

    private static func confusionDict(_ r: ConfusionRow) -> [String: Any] {
        [
            "id": r.id,
            "originalPhoneticKey": r.phoneticKey,
            "originalVariants": r.variants,
            "correctedTerm": r.correctedTerm,
            "locale": r.locale,
            "frequency": r.frequency,
            "negativeFeedbackCount": r.negativeFeedbackCount,
            "confidence": r.confidence,
            "lastSeen": r.lastSeen,
            "source": r.source,
        ]
    }

    private static func vocabDict(_ r: VocabularyRow) -> [String: Any] {
        [
            "id": r.id,
            "term": r.term,
            "phoneticKey": r.phoneticKey,
            "locale": r.locale,
            "posTag": r.posTag ?? NSNull(),
            "frequency": r.frequency,
            "distinctDays": r.distinctDays,
            "backgroundRank": r.backgroundRank ?? NSNull(),
            "lastSeen": r.lastSeen,
            "source": r.source,
        ]
    }
}
#endif
