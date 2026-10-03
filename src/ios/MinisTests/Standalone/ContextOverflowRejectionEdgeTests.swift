#!/usr/bin/env swift
// Edge cases around a provider's context-length REJECTION, the one piece of
// ground truth the calibrated size meter learns from.
//
// Guards commits:
//   df35445ee / 9aad0ffb5  T-ctx-measure-outbound (noteContextOverflow, ratioAfterOverflow)
//   0e0a8b339              T-ctx-valve-rearm ("a rejection spends the valve")
//   6a3f9e0da              T-ctx-overflow-no-cross-fallback (Android twin)
//
//   [0] the ported functions are the production ones (text compare);
//   [1] classification: which rejection texts count as "too many TOKENS";
//   [2] what a misclassified byte-size rejection does to the ratio;
//   [3] the valve across a retry — the real iOS control flow, where a
//       rejection always ends the loop and a retry starts a NEW loop;
//   [4] requestedTokens on real provider wordings.
//
// Items marked "EXPECTED TO FAIL until fixed" document real bugs found in
// review; they are kept failing on purpose (see the report).
//
// Run: swift ContextOverflowRejectionEdgeTests.swift
import Foundation

var failures = 0
func check(_ label: String, _ cond: Bool) {
    print(cond ? "  ✅ \(label)" : "  ❌ \(label) — expected true, got false")
    if !cond { failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    print(a == b ? "  ✅ \(label)" : "  ❌ \(label) — expected \(b), got \(a)")
    if a != b { failures += 1 }
}
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Port (verbatim; [0] checks it against Agent/Chat/ContextPolicy.swift)

enum Port {
    static let calibrationRange: ClosedRange<Double> = 0.8...3.0

    static let overflowMarkers = [
        "maximum context length", "context length exceeded", "context_length_exceeded",
        "reduce the length of the messages", "too many tokens", "prompt is too long",
        "request too large", "exceeds the maximum", "input is too long",
        "exceeds the context window", "input exceeds the context",
        // Anthropic: "input length and `max_tokens` exceed context limit: A + B > W"
        "exceed context limit",
        "上下文长度", "超出最大长度", "内容过长",
    ]

    static func isContextOverflow(_ text: String) -> Bool {
        let lower = text.lowercased()
        let codes = (try? NSRegularExpression(pattern: #"\[(\d{3})\]"#))?
            .matches(in: lower, range: NSRange(lower.startIndex..., in: lower))
            .compactMap { Range($0.range(at: 1), in: lower).flatMap { Int(lower[$0]) } } ?? []
        if !codes.isEmpty && !codes.contains(where: { $0 == 400 || $0 == 413 }) { return false }
        let hits = overflowMarkers.filter { lower.contains($0) }
        if lower.contains("bytes") {
            return hits.contains { !Self.byteAmbiguousMarkers.contains($0) }
        }
        return !hits.isEmpty
    }

    static let byteAmbiguousMarkers: Set<String> = ["exceeds the maximum", "request too large"]

    static func requestedTokens(inOverflowMessage text: String) -> Int? {
        let cleaned = text.replacingOccurrences(of: #"(?<=\d),(?=\d{3})"#, with: "", options: .regularExpression)
        let regex = try? NSRegularExpression(pattern: #"\d{4,}"#)
        let range = NSRange(cleaned.startIndex..., in: cleaned)
        let values = regex?.matches(in: cleaned, range: range).compactMap {
            Range($0.range, in: cleaned).flatMap { Int(cleaned[$0]) }
        } ?? []
        return values.filter { $0 >= 1000 }.max()
    }

    static func ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int) -> Double {
        guard estimated > 0 else { return current }
        let plausible = requested.flatMap { r -> Int? in
            guard window > 0 else { return r }
            return (Double(r) >= Double(window) * 0.9 && Double(r) <= Double(window) * 4) ? r : nil
        }
        let target = plausible.map(Double.init) ?? Double(max(window, 1)) * 1.02
        let implied = target / Double(estimated)
        return min(max(current, implied), calibrationRange.upperBound)
    }

    static func calibrated(_ estimated: Int, ratio: Double) -> Int {
        Int((Double(estimated) * ratio).rounded(.up))
    }

    enum CheckResult { case ok, needsCompact, exhausted }
    enum InLoopStep: Equatable { case proceed, compact, sendWithinWindow, sendUncalibratedOnce, stop }

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

/// `func NAME(` … its matching `}`, with `//` comments and whitespace removed.
func functionText(_ name: String, in text: String) -> String? {
    guard let start = text.range(of: "func \(name)(") else { return nil }
    var depth = 0, seenBrace = false
    var out = ""
    var i = start.lowerBound
    while i < text.endIndex {
        let c = text[i]
        out.append(c)
        if c == "{" { depth += 1; seenBrace = true }
        if c == "}" { depth -= 1; if seenBrace && depth == 0 { break } }
        i = text.index(after: i)
    }
    let noComments = out.split(separator: "\n").map { line -> Substring in
        if let r = line.range(of: "//") { return line[..<r.lowerBound] }
        return line
    }.joined()
    return noComments.filter { !$0.isWhitespace }
}

/// `static let overflowMarkers = [ … ]` with whitespace removed.
func markersText(_ text: String) -> String? {
    guard let s = text.range(of: "static let overflowMarkers = ["),
          let e = text.range(of: "]", range: s.upperBound..<text.endIndex) else { return nil }
    return String(text[s.lowerBound..<e.upperBound]).filter { !$0.isWhitespace }
}

let prod = source("Agent/Chat/ContextPolicy.swift")
let vm = source("Agent/Chat/AIChatViewModel.swift")
let compaction = source("Agent/Chat/AIChatViewModel+Compaction.swift")

// MARK: - [0]

print("══ [0] the port is the production code ══")
do {
    check("production sources were found", !prod.isEmpty && !vm.isEmpty && !compaction.isEmpty)
    let port = (try? String(contentsOf: URL(fileURLWithPath: #filePath), encoding: .utf8)) ?? ""
    let portBody = String(port[port.range(of: "enum Port {")!.lowerBound...])
    for name in ["isContextOverflow", "requestedTokens", "ratioAfterOverflow", "calibrated", "inLoopStep"] {
        let p = functionText(name, in: prod), q = functionText(name, in: portBody)
        check("\(name) is verbatim", p != nil && p == q)
    }
    check("overflowMarkers is verbatim", markersText(prod) != nil && markersText(prod) == markersText(portBody))
    check("calibration ceiling unchanged",
          prod.contains("static let calibrationRange: ClosedRange<Double> = 0.8...3.0"))
}

// MARK: - [1] classification

print("\n══ [1] which rejections are about TOKENS ══")
do {
    // Real token overflows (regression anchors).
    check("Anthropic 'prompt is too long'",
          Port.isContextOverflow("[invalid_request_error] prompt is too long: 210000 tokens > 200000 maximum"))
    check("Gemini 'exceeds the maximum number of tokens'",
          Port.isContextOverflow("[400] The input token count (1100000) exceeds the maximum number of tokens allowed (1048576)."))
    check("a per-minute token rate limit is not an overflow",
          !Port.isContextOverflow("[429] too many tokens per minute"))

    // Anthropic's 413 is a BYTE limit on the request body (images, PDFs),
    // not a token count; iOS formats it from the body as `[request_too_large] …`.
    // It matches the generic "exceeds the maximum" marker; fixed by T-ctx-byte-413-not-overflow.
    check("Anthropic byte-size 413 is not a token overflow",
          !Port.isContextOverflow("[request_too_large] Request exceeds the maximum allowed number of bytes."))

    // Anthropic's input + max_tokens rejection names no marker in the list,
    // so the ratio was never raised for it (now in the list).
    check("Anthropic 'input length and max_tokens exceed context limit' is an overflow",
          Port.isContextOverflow("[invalid_request_error] input length and `max_tokens` exceed context limit: 188240 + 21333 > 200000, decrease input length or `max_tokens` and try again"))
    // A group-exhausted error can mix a byte-size 413 with a genuine token
    // rejection from another member; the token one must still count.
    check("byte 413 + a real token overflow in one group error is an overflow",
          Port.isContextOverflow("A: [413] Request exceeds the maximum allowed number of bytes. B: [400] prompt is too long: 210000 tokens > 200000 maximum"))
    check("Gemini token wording is not mistaken for bytes",
          Port.isContextOverflow("[400] The input token count (1100000) exceeds the maximum number of tokens allowed (1048576)."))
}

// MARK: - [2] ratio after a misclassified byte-size rejection

print("\n══ [2] a byte-size rejection must not pin the ratio ══")
do {
    // noteContextOverflow: isContextOverflow → ratioAfterOverflow(current, lastDispatchEstimate, requestedTokens, window).
    func afterRejection(_ text: String, current: Double, estimated: Int, window: Int) -> Double {
        guard Port.isContextOverflow(text) else { return current }
        return Port.ratioAfterOverflow(current: current, estimated: estimated,
                                       requested: Port.requestedTokens(inOverflowMessage: text), window: window)
    }
    // An image-heavy request: 60K estimated tokens on a 200K model, rejected for bytes.
    let r = afterRejection("[request_too_large] Request exceeds the maximum allowed number of bytes.",
                           current: 1.0, estimated: 60_000, window: 200_000)
    // Today: 1.02 × 200K / 60K = 3.4 → clamped 3.0; the session is then measured at
    // 3x, compacts at ~60K, and the in-memory ratio survives reloads (carryingOver).
    // Fixed by T-ctx-byte-413-not-overflow.
    check("ratio unchanged after a byte-size rejection (got \(r))", r == 1.0)
    let measured = Port.calibrated(60_000, ratio: r)
    check("…so a 60K request on a 200K window is not judged over it (measured \(measured))", measured < 200_000)
}

// MARK: - [3] the valve across a retry

print("\n══ [3] a rejection's spent valve must survive into the retry ══")
do {
    // iOS control flow (verified from source below): a context-length rejection
    // is never retried inside the loop — it throws out of runAgentLoopCore,
    // runAgentLoop's catch calls noteContextOverflow (which sets
    // sentPastExtrapolatedLimitThisLoop = true), and the retry is a NEW
    // runAgentLoopCore, whose prologue resets the flag to false.
    let catchCallsNote = vm.contains("""
            let text = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
            noteContextOverflow(errorText: text, modelId: nil)
            throw error
""")
    let noteCallSites = vm.components(separatedBy: "noteContextOverflow(errorText:").count - 1
    let noteSpendsValve = compaction.contains("sentPastExtrapolatedLimitThisLoop = true\n        logger.warning(\"[CtxMeter] rejected")
    let prologueResets = vm.contains("var lastInLoopCompactionMadeNoProgress = false\n        sentPastExtrapolatedLimitThisLoop = false")
    check("source: noteContextOverflow runs only in runAgentLoop's catch (after the loop ended)",
          catchCallsNote && noteCallSites == 1)
    check("source: noteContextOverflow spends the valve", noteSpendsValve)

    // Model one retry after a rejection. History is 120K raw, the provider counts
    // 1.3x (156K) on a 128K window, compaction cannot shrink it (one big part in
    // the protected tail).
    let window = 128_000, raw = 120_000
    var ratio = 1.0
    var valveUsed = false
    var rejected = 0
    func attempt() {
        if prologueResets { valveUsed = false }
        var compactions = 0, noProgress = false
        while true {
            let measured = Port.calibrated(raw, ratio: ratio)
            let verdict: Port.CheckResult = measured >= window - 10_000 ? .needsCompact : .ok
            let step = Port.inLoopStep(verdict: verdict, measured: measured, rawTokens: raw, window: window,
                                       canCompact: compactions < 3 && !noProgress, ratio: ratio,
                                       uncalibratedSendUsed: valveUsed)
            switch step {
            case .stop: return
            case .compact: compactions += 1; noProgress = true
            default:
                if step == .sendUncalibratedOnce { valveUsed = true }
                let real = Int(Double(raw) * 1.3)
                guard real >= window else { return }
                rejected += 1
                ratio = Port.ratioAfterOverflow(current: ratio, estimated: raw, requested: real, window: window)
                if noteSpendsValve { valveUsed = true }
                return   // the rejection ends the loop (throw)
            }
        }
    }
    attempt()                  // first send: ratio 1.0 → sent, rejected
    checkEq("first attempt costs one rejection", rejected, 1)
    attempt()                  // user taps Retry
    // With the loop prologue resetting the flag, the retry compacts (no
    // progress) and then fires the valve on the request the provider just
    // rejected. EXPECTED TO FAIL until fixed.
    checkEq("the retry stops instead of re-sending the rejected request", rejected, 1)
    check("source: the loop prologue does not discard a rejection's spent valve", !prologueResets)
}

// MARK: - [4] requestedTokens

print("\n══ [4] requestedTokens on real wordings ══")
do {
    checkEq("Anthropic: the request, not the limit",
            Port.requestedTokens(inOverflowMessage: "prompt is too long: 210000 tokens > 200000 maximum"), 210_000)
    checkEq("thousands separators",
            Port.requestedTokens(inOverflowMessage: "maximum context length is 128,000 tokens, you requested 131,072 tokens"), 131_072)
    checkEq("no number → nil",
            Port.requestedTokens(inOverflowMessage: "Your input exceeds the context window of this model"), nil)
    // A request id's digit run is huge; ratioAfterOverflow's plausibility band rejects it.
    let idOnly = Port.requestedTokens(inOverflowMessage: "prompt is too long (request req_20260923123456789)")
    let r = Port.ratioAfterOverflow(current: 1.1, estimated: 100_000, requested: idOnly, window: 128_000)
    check("an implausible stated count falls back to the window (ratio \(r))", abs(r - 1.3056) < 0.001)
}

print(failures == 0 ? "\nALL PASSED" : "\n\(failures) FAILED")
exit(failures == 0 ? 0 : 1)
