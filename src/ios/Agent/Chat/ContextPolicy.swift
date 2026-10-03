import Foundation

/// Determines offload / compact / exhausted thresholds based on model context window size.
///
/// Tiers:
/// - **< 32K**: No auto-offload, no auto-compact. Prompt "start new / clear" when full.
/// - **32K–64K**: Auto-offload when remaining ≤ 10K. No auto-compact (user can manually).
///   Prompt "start new / clear" when exhausted.
/// - **64K–128K**: Auto-offload when remaining ≤ 20K. Auto-compact when remaining ≤ 10K.
/// - **≥ 128K**: Auto-offload when remaining ≤ 40K. Auto-compact when remaining ≤ 20K.
struct ContextPolicy {
    /// Tokens used must exceed this to trigger auto-offload. 0 = offload disabled.
    let offloadThreshold: Int
    /// Target token count after offloading. 0 = offload everything eligible.
    let offloadTarget: Int
    /// Tokens used must exceed this to trigger auto-compact. 0 = auto-compact disabled.
    let compactThreshold: Int
    /// When true, reaching the limit shows "start new session / clear" instead of compact.
    let exhaustedOnly: Bool
    /// Whether user-initiated manual compact is allowed.
    let manualCompactAllowed: Bool

    /// [T-ctx-user-cap] A user-chosen group cap is NOT the same thing as a
    /// model's native window, and the tier table below only makes sense for the
    /// latter. The fixed 10K/20K/40K headroom subtractions — and the decision to
    /// disable auto-compact entirely under 64K — exist because a genuinely small
    /// model cannot afford a summary plus re-appended warm-up turns. When the
    /// user caps a 1M model at 32K none of that holds: the model has room to run
    /// the compaction call, and "I want this group to stay under 32K" is an
    /// instruction to compact, not a reason to stop compacting. So a user cap
    /// gets proportional thresholds and always keeps auto-compact available.
    init(contextWindow: Int, isUserCap: Bool = false) {
        if isUserCap {
            // Proportional: compact at 85%, offload at 70%, settle back to 55%.
            // Percentages (not fixed subtractions) keep the headroom sane across
            // the whole 32K…1M slider ladder — at 32K a 20K subtraction would
            // leave a threshold below the floor, and at 1M it would be noise.
            offloadThreshold = Int(Double(contextWindow) * 0.70)
            offloadTarget = Int(Double(contextWindow) * 0.55)
            compactThreshold = Int(Double(contextWindow) * 0.85)
            exhaustedOnly = false
            manualCompactAllowed = true
            return
        }
        if contextWindow < 32_000 {
            // < 32K: no offload, no compact
            offloadThreshold = 0
            offloadTarget = 0
            compactThreshold = 0
            exhaustedOnly = true
            manualCompactAllowed = false
        } else if contextWindow < 64_000 {
            // 32K–64K: offload when remaining ≤ 10K, no auto-compact
            offloadThreshold = contextWindow - 10_000
            offloadTarget = contextWindow - 15_000
            compactThreshold = 0
            exhaustedOnly = true
            manualCompactAllowed = true
        } else if contextWindow < 128_000 {
            // 64K–128K: offload when remaining ≤ 20K, compact when remaining ≤ 10K
            offloadThreshold = contextWindow - 20_000
            offloadTarget = contextWindow - 30_000
            compactThreshold = contextWindow - 10_000
            exhaustedOnly = false
            manualCompactAllowed = true
        } else {
            // ≥ 128K: offload when remaining ≤ 40K, compact when remaining ≤ 20K
            offloadThreshold = contextWindow - 40_000
            offloadTarget = contextWindow - 60_000
            compactThreshold = contextWindow - 20_000
            exhaustedOnly = false
            manualCompactAllowed = true
        }
    }

    // MARK: - Pre-send Check

    /// Pre-send context check result.
    enum CheckResult {
        /// Context is fine, proceed normally.
        case ok
        /// Context is near capacity — auto-compact before sending.
        case needsCompact
        /// Context is exhausted — prompt user to start new session or clear chat.
        case exhausted
    }

    /// Evaluate current token usage against policy thresholds.
    func check(estimatedTokens: Int, contextWindow: Int) -> CheckResult {
        // Check compact threshold first (only for tiers that support auto-compact)
        if compactThreshold > 0, estimatedTokens >= compactThreshold {
            return .needsCompact
        }

        // [T-ctx-overflow-hard-stop] Already at or past the ceiling. Whatever the
        // tier says about headroom economics is moot — the next request does not
        // fit. Compact if this tier can (manual compaction being allowed is the
        // signal that a summary is viable at all), otherwise report exhausted so
        // the caller prompts instead of sending a request that cannot succeed.
        // Without this, an exhausted-only tier answered `.ok` right up to its
        // advisory line and then kept answering `.ok` past 100%.
        if contextWindow > 0, estimatedTokens >= contextWindow {
            return manualCompactAllowed ? .needsCompact : .exhausted
        }

        // For tiers where auto-compact is disabled, check if context is exhausted
        if exhaustedOnly {
            let exhaustedThreshold = offloadThreshold > 0
                ? offloadThreshold
                : Int(Double(contextWindow) * 0.90)
            if estimatedTokens >= exhaustedThreshold {
                return .exhausted
            }
        }

        return .ok
    }

    /// Whether auto-offload should trigger at the given token count.
    func shouldOffload(estimatedTokens: Int) -> Bool {
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold
    }

    /// What the agent loop does with its next request (see `inLoopStep`).
    enum InLoopStep: Equatable {
        /// Under the compact line — send.
        case proceed
        /// Compact in place, then re-check.
        case compact
        /// Compaction can do no more, but the request fits the window — send.
        case sendWithinWindow
        /// Over the window only by the calibrated extrapolation — send once for the provider to decide.
        case sendUncalibratedOnce
        /// Does not fit and nothing left to try.
        case stop
    }

    /// [T-ctx-measure-outbound] The in-loop guard's decision table, shared with
    /// Android `ContextPolicy.inLoopStep`. Every branch that is not `.compact`
    /// or `.stop` exists to keep a session from wedging: above the compact line
    /// is still sendable, and an "over the window" that rests only on a ratio
    /// (learned on other content, or borrowed from another model) gets one real
    /// request, whose answer or rejection then corrects the ratio.
    ///
    /// `canCompact` = compaction budget left AND the last pass made progress.
    /// `rawTokens` = the same request with no calibration applied.
    /// A window of 0 (unknown) never stops a request.
    static func inLoopStep(verdict: CheckResult, measured: Int, rawTokens: Int, window: Int,
                           canCompact: Bool, ratio: Double, uncalibratedSendUsed: Bool) -> InLoopStep {
        switch verdict {
        case .ok: return .proceed
        case .exhausted: return .stop
        case .needsCompact:
            if canCompact { return .compact }
            if window <= 0 || measured < window { return .sendWithinWindow }
            if !uncalibratedSendUsed, ratio > 1.0, rawTokens < window { return .sendUncalibratedOnce }
            return .stop
        }
    }
}

// MARK: - Outbound context measurement [T-ctx-measure-outbound]

/// Sizes the request that is ABOUT to be sent, so every capacity decision
/// (compact, offload, `max_tokens`) judges the context the model will actually
/// receive — not the size of some earlier request.
///
/// Why this exists: the guards used to take `max(localEstimate, lastAPIReport)`.
/// The API report is exact but describes the PREVIOUS request, and nothing
/// short of another successful call ever replaced it. So after a compaction
/// (or an offload) the stale pre-compaction number kept winning the `max()`,
/// the in-loop guard — which runs BEFORE the next call — re-compacted on it
/// until its cap, and the turn stopped with "Context is full and could not be
/// compacted further" having made zero API calls. Reopening the session
/// re-seeded the same number from the message it was stamped on, so the
/// session stayed wedged. The opposite happened after a revert: the last
/// report came from the compacted context, and under-read the restored history.
///
/// The fix is to stop treating the API number as a measurement of NOW and use
/// it for what it is good at — calibration:
///
///     size(now) = calibration × (estimate(outbound history) + estimate(system prompt + tools))
///
/// where `calibration = lastReported / estimate(that same request)`. Both sides
/// of the ratio describe ONE request, so it stays valid across compaction,
/// offload, revert and relaunch; whatever changes the outbound history changes
/// `estimate(outbound history)`, and the decision follows automatically.
enum ContextSizeMeter {

    /// Token estimate for a string, by character class.
    ///
    /// Fitted against cl100k_base on Chinese, Japanese, English prose, Swift,
    /// JSON, git logs and `ls -la` output: 0.66–1.18x the real count, versus
    /// 0.30–1.21x for the previous flat `chars / 3.5` — which read CJK text at
    /// under a third of its real size. The residual is per-session and is
    /// absorbed by the calibration ratio after the first response.
    ///
    /// Scans UTF-8 bytes rather than `unicodeScalars`: identical counts (a
    /// non-ASCII scalar is counted once, at its lead byte) at about half the
    /// cost — which matters because the measurement runs several times per
    /// loop iteration on the main actor, and dev builds are Debug (~45ms per
    /// pass over a 1M-token history via scalars, ~21ms via bytes).
    static func estimateTokens(_ text: String) -> Int {
        var letters = 0, digits = 0, asciiOther = 0, nonASCII = 0
        var copy = text
        copy.withUTF8 { bytes in
            for b in bytes {
                if b >= 0x80 {
                    if b & 0xC0 != 0x80 { nonASCII += 1 }   // lead byte = one scalar
                } else if (b >= 65 && b <= 90) || (b >= 97 && b <= 122) {
                    letters += 1
                } else if b >= 48 && b <= 57 {
                    digits += 1
                } else {
                    asciiOther += 1
                }
            }
        }
        let tokens = Double(letters) / 4.5 + Double(digits) / 2.0
            + Double(asciiOther) * 0.35 + Double(nonASCII)
        return Int(tokens.rounded(.up))
    }

    /// Per-message framing (role markers, separators). Matches the structural
    /// overhead BPETokenizer.countPartTokens adds per tool part.
    static let perMessageOverhead = 4
    static let perToolPartOverhead = 4

    static func estimateTokens(_ part: AgentContentPart) -> Int {
        switch part {
        case .text(let text):
            return estimateTokens(text)
        case .toolUse(_, let name, let input, _):
            var total = estimateTokens(name) + perToolPartOverhead
            if let data = try? JSONSerialization.data(withJSONObject: input),
               let json = String(data: data, encoding: .utf8) {
                total += estimateTokens(json)
            }
            return total
        case .toolResult(_, let name, let content, _, let imageData, _, _, _, _):
            var total = estimateTokens(name) + estimateTokens(content) + perToolPartOverhead
            if let imageData { total += imageTokens(imageData) }
            return total
        case .imageData(let data, _, _):
            return imageTokens(data)
        }
    }

    static func estimateTokens(_ messages: [AgentMessage]) -> Int {
        messages.reduce(0) { $0 + estimateTokens(message: $1) }
    }

    /// Persisted messages are cached: without it every measurement re-scans the
    /// whole history, several times per iteration. The key carries the
    /// message's content SHAPE as well as its id, because offload rewrites a
    /// persisted message in place (tool result → stub, argument → notice): a
    /// changed length or offload flag is a new key, so a shrunk message is
    /// re-measured instead of keeping its old size. In-flight messages (no
    /// dbMessageId yet) are always measured.
    static func estimateTokens(message msg: AgentMessage) -> Int {
        guard let id = msg.dbMessageId else { return uncachedEstimate(msg) }
        var shape = "\(id)|\(msg.parts.count)"
        for part in msg.parts {
            switch part {
            case .text(let t): shape += "|t\(t.utf8.count)"
            case .toolUse(_, _, let input, let offloaded): shape += "|u\(input.count)\(offloaded ? "o" : "")"
            case .toolResult(_, _, let content, _, let img, _, _, _, _): shape += "|r\(content.utf8.count)/\(img?.count ?? 0)"
            case .imageData(let d, _, _): shape += "|i\(d.count)"
            }
        }
        let key = shape as NSString
        if let hit = messageEstimateCache.object(forKey: key) { return hit.intValue }
        let tokens = uncachedEstimate(msg)
        messageEstimateCache.setObject(NSNumber(value: tokens), forKey: key)
        return tokens
    }

    private static let messageEstimateCache: NSCache<NSString, NSNumber> = {
        let c = NSCache<NSString, NSNumber>()
        c.countLimit = 20_000
        return c
    }()

    private static func uncachedEstimate(_ msg: AgentMessage) -> Int {
        perMessageOverhead + msg.parts.reduce(0) { $0 + estimateTokens($1) }
    }

    /// System prompt plus tool schemas — the part of every request that the
    /// history-only estimate used to leave out entirely.
    static func estimateFixedTokens(systemPrompt: String, tools: [AgentToolDefinition]) -> Int {
        var total = estimateTokens(systemPrompt)
        for tool in tools {
            total += estimateTokens(tool.name) + estimateTokens(tool.description) + 10
            for (name, param) in tool.parameters {
                total += estimateTokens(name) + estimateTokens(param.description) + 4
                if let values = param.enumValues {
                    total += values.reduce(0) { $0 + estimateTokens($1) + 1 }
                }
            }
        }
        return total
    }

    // MARK: Calibration

    /// Bounds on reported / estimated. Outside this band the report is more
    /// likely to describe something other than the request we estimated (a
    /// relay that bills differently, a provider that omits cached tokens) than
    /// a real tokenizer difference, so it is clamped rather than trusted.
    ///
    /// The floor is deliberately tight. The estimator's worst measured
    /// over-read is 1.18x, so a genuine ratio never drops much below 0.85; a
    /// lower one means an under-reporting upstream, and following it down
    /// would let an over-length request through — the old max() at least kept
    /// the estimate as a floor. The ceiling is loose because under-reads are
    /// real: denser tokenizers, plus content we do not measure (reasoning
    /// echoes, provider-side framing).
    static let calibrationRange: ClosedRange<Double> = 0.8...3.0

    /// Ratio of what the provider counted to what we estimated for the SAME
    /// request, or nil when either side is missing.
    static func calibrationRatio(reported: Int, estimated: Int) -> Double? {
        guard reported > 0, estimated > 0 else { return nil }
        let raw = Double(reported) / Double(estimated)
        return min(max(raw, calibrationRange.lowerBound), calibrationRange.upperBound)
    }

    static func calibrated(_ estimated: Int, ratio: Double) -> Int {
        Int((Double(estimated) * ratio).rounded(.up))
    }

    /// Applied when the current model has no calibration of its own and we are
    /// borrowing another model's ratio. Tokenizers differ by roughly 10–30%
    /// (e.g. a session run on one vendor, then switched to one whose tokenizer
    /// is denser): judging the new model by the old ratio under-reads it, and a
    /// session sitting near the old model's limit can then be sent over the new
    /// one's. Leaning high for that one request costs, at worst, an early
    /// compaction; its first response replaces the borrowed ratio.
    static let uncalibratedModelMargin = 1.2

    /// Where `ratio(for:known:lastLearned:)` got its answer, for logs.
    static func ratioSource(for modelId: String?, known: [String: Double], lastLearned: Double?) -> String {
        if let modelId, known[modelId] != nil { return "own" }
        return lastLearned == nil ? "default" : "borrowed"
    }

    // MARK: Smoothing [T-ctx-ratio-smoothing]

    /// Share of the gap closed per sample when a new sample says the ratio
    /// should come DOWN. Recent samples then weigh 0.3, 0.21, 0.147, …
    static let calibrationFallRate = 0.3

    /// Fold one sample into a model's ratio — an asymmetric weighted average.
    ///
    /// The two directions of error are not equally dangerous. A ratio that is
    /// too LOW under-reads the context and can send a request over the window
    /// (a rejection); one that is too high only compacts a little early. So a
    /// sample that says "higher" applies at once — the safety property the
    /// unsmoothed ratio had — while one that says "lower" moves the ratio only
    /// part of the way. A single anomalous low report (a relay that under-counts
    /// once) then barely moves it, where before it replaced it outright; a real
    /// drop (the conversation's content changed) settles within 3–4 responses.
    static func smoothed(previous: Double?, sample: Double) -> Double {
        guard let previous else { return sample }
        if sample >= previous { return sample }
        return previous + calibrationFallRate * (sample - previous)
    }

    /// The ratio to judge `modelId` by: its own if it has one; otherwise the
    /// most recently learned ratio (any model) with the margin above; otherwise
    /// 1.0 for a session that has never reported usage.
    static func ratio(for modelId: String?, known: [String: Double], lastLearned: Double?) -> Double {
        if let modelId, let own = known[modelId] { return own }
        guard let lastLearned else { return 1.0 }
        return min(max(lastLearned, 1.0) * uncalibratedModelMargin, calibrationRange.upperBound)
    }

    // MARK: Provider rejections

    /// Phrases providers use to refuse an over-length request. Matches Android's
    /// ContextOverflowGuard, plus the wording in OpenMinis#133.
    static let overflowMarkers = [
        "maximum context length", "context length exceeded", "context_length_exceeded",
        "reduce the length of the messages", "too many tokens", "prompt is too long",
        "request too large", "exceeds the maximum", "input is too long",
        "exceeds the context window", "input exceeds the context",
        // Anthropic: "input length and `max_tokens` exceed context limit: A + B > W"
        "exceed context limit",
        "上下文长度", "超出最大长度", "内容过长",
    ]

    /// Whether an error text is a context-length rejection. The status must be
    /// 400/413 when one is present in the text (`[400] …`): other statuses with
    /// similar words (a 429 "too many tokens per minute") are rate limits.
    ///
    /// A group-exhausted error lists one reason per member ("A: [429] … B: [400]
    /// prompt is too long"), so it counts when ANY stated status is 400/413.
    static func isContextOverflow(_ text: String) -> Bool {
        let lower = text.lowercased()
        let codes = (try? NSRegularExpression(pattern: #"\[(\d{3})\]"#))?
            .matches(in: lower, range: NSRange(lower.startIndex..., in: lower))
            .compactMap { Range($0.range(at: 1), in: lower).flatMap { Int(lower[$0]) } } ?? []
        if !codes.isEmpty && !codes.contains(where: { $0 == 400 || $0 == 413 }) { return false }
        let hits = overflowMarkers.filter { lower.contains($0) }
        // [T-ctx-byte-413-not-overflow] Anthropic's 413 "Request exceeds the
        // maximum allowed number of bytes" is a payload-SIZE limit (images,
        // attachments), not a token count. Matched by the generic markers
        // alone it raised the calibration ratio toward 3x on a request far
        // under the window and made the session compact early for many
        // turns. With byte wording, only a token-specific marker counts.
        if lower.contains("bytes") {
            return hits.contains { !Self.byteAmbiguousMarkers.contains($0) }
        }
        return !hits.isEmpty
    }

    /// Markers that a byte-size rejection also matches.
    static let byteAmbiguousMarkers: Set<String> = ["exceeds the maximum", "request too large"]

    /// The token count the provider says the rejected request had, when its
    /// message states one. Rejections name two numbers — the limit and the
    /// request — and the request is the larger, since it overflowed. Numbers
    /// under 1000 are status codes and the like, not token counts.
    static func requestedTokens(inOverflowMessage text: String) -> Int? {
        let cleaned = text.replacingOccurrences(of: #"(?<=\d),(?=\d{3})"#, with: "", options: .regularExpression)
        let regex = try? NSRegularExpression(pattern: #"\d{4,}"#)
        let range = NSRange(cleaned.startIndex..., in: cleaned)
        let values = regex?.matches(in: cleaned, range: range).compactMap {
            Range($0.range, in: cleaned).flatMap { Int(cleaned[$0]) }
        } ?? []
        return values.filter { $0 >= 1000 }.max()
    }

    /// Ratio after a rejection: the provider's stated count ÷ our estimate of
    /// the same request when it gave one; otherwise just enough to put that
    /// request at the window. Never lower than the current ratio — a rejection
    /// only ever says we under-read.
    static func ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int) -> Double {
        guard estimated > 0 else { return current }
        // A stated count is only trusted when it is plausible for this window:
        // a request id or byte size in the same message would otherwise read as
        // a huge "token count" and pin the ratio at its ceiling.
        let plausible = requested.flatMap { r -> Int? in
            guard window > 0 else { return r }
            return (Double(r) >= Double(window) * 0.9 && Double(r) <= Double(window) * 4) ? r : nil
        }
        let target = plausible.map(Double.init) ?? Double(max(window, 1)) * 1.02
        let implied = target / Double(estimated)
        return min(max(current, implied), calibrationRange.upperBound)
    }

    // MARK: Session calibration: replay and reload

    /// One persisted (report, estimate) pair — an assistant turn's usage.
    struct CalibrationSample: Equatable {
        let reported: Int, estimated: Int, fixedTokens: Int, modelId: String?
    }

    /// A session's calibration: per-model ratios, the newest learned one, and the fixed share.
    struct CalibrationState: Equatable {
        var ratios: [String: Double] = [:]
        var lastLearned: Double? = nil
        var fixedTokens = 0
        var samples = 0

        /// Reloading the SAME session keeps what was learned in memory: a ratio
        /// raised by a rejection exists nowhere else (a rejected request stamps
        /// no pair), and in-memory values are never older than the transcript's.
        /// So they win, key by key; the transcript fills in models memory has
        /// not seen.
        func carryingOver(_ learned: CalibrationState) -> CalibrationState {
            var merged = self
            merged.ratios.merge(learned.ratios) { _, inMemory in inMemory }
            if let last = learned.lastLearned { merged.lastLearned = last }
            if learned.fixedTokens > 0 { merged.fixedTokens = learned.fixedTokens }
            return merged
        }
    }

    /// [T-ctx-ratio-smoothing] Rebuild calibration from persisted samples in
    /// conversation order, through the same `smoothed` rule the live path uses,
    /// so a reload reproduces the ratio the session had in memory rather than
    /// jumping to the newest raw sample. A sample with no model id (older rows)
    /// informs only `lastLearned`.
    static func replayCalibration(_ samples: [CalibrationSample]) -> CalibrationState {
        var state = CalibrationState()
        for s in samples {
            guard let sample = calibrationRatio(reported: s.reported, estimated: s.estimated) else { continue }
            state.samples += 1
            if let model = s.modelId {
                let updated = smoothed(previous: state.ratios[model], sample: sample)
                state.ratios[model] = updated
                state.lastLearned = updated
            } else {
                state.lastLearned = sample
            }
            state.fixedTokens = s.fixedTokens
        }
        return state
    }

    /// [T-ctx-warmup-fit] How many leading warm-up messages to drop so that
    /// warm-up + `restTokens` is under `budget`. Drops whole turns: after each
    /// cut it keeps dropping until the slice starts on a user-TEXT message
    /// (`startsTurn`), so a tool call is never separated from its result.
    /// 0 when it already fits; `sizes.count` when nothing does.
    static func warmUpDrop(sizes: [Int], startsTurn: [Bool], restTokens: Int, budget: Int) -> Int {
        precondition(sizes.count == startsTurn.count)
        var total = sizes.reduce(0, +)
        var drop = 0
        while drop < sizes.count, total + restTokens >= budget {
            total -= sizes[drop]; drop += 1
            while drop < sizes.count, !startsTurn[drop] { total -= sizes[drop]; drop += 1 }
        }
        return drop
    }

    // MARK: Images

    /// `countImageTokens` decodes the image to read its dimensions, and the
    /// measurement runs several times per loop iteration, so results are
    /// cached by a cheap content fingerprint.
    private static let imageTokenCache = NSCache<NSString, NSNumber>()

    static func imageTokens(_ data: Data) -> Int {
        let key = "\(data.count)-\(data.prefix(64).hashValue)-\(data.suffix(64).hashValue)" as NSString
        if let cached = imageTokenCache.object(forKey: key) { return cached.intValue }
        let tokens = BPETokenizer.shared.countImageTokens(data)
        imageTokenCache.setObject(NSNumber(value: tokens), forKey: key)
        return tokens
    }
}
