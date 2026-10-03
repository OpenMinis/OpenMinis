// Tests for [T-stream-drop-silent] — a stream that ends WITHOUT a terminal
// finish event is an interruption, not a completed turn and not "the model
// returned nothing".
//
// Provider layer (AIChatViewModel+SSEStream.swift processStreamEvents):
//   * `result.stopReason` is set ONLY by a `.done(stopReason:)` event; a stream
//     that simply returns leaves it nil.
//   * `result.isStreamInterrupted` is set ONLY when the stream THROWS
//     (catch block ~1011). A silent EOF does not throw, so it stays false.
// Loop layer (AIChatViewModel.swift):
//   * `isEmptyResponse` (~6434): nil stop + no text + no tool → EMPTY →
//     `LLMError.transientError` → auto-retry → group fallback. (This is the
//     path Android's fcf438048 cites as "iOS already treats this as a
//     transient error".)
//   * no-tool exit (~6886) and post-tool exit (~7318): a nil stopReason with
//     content is surfaced as "The connection dropped — this reply may be
//     incomplete" with `canResume = true`; with no content as "Response ended
//     unexpectedly". Before that fix a partial reply was persisted as complete.
//
// Android's StreamDropNoFinishTest pins the provider half; iOS had nothing.
// This script ports the event reduction and the loop classification and
// greps the sources for the wiring. Standalone (`swift StreamDropNoFinishTests.swift`).

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(expected)\n     actual:   \(actual)"); failures += 1 }
}

// MARK: - Mirror of AgentStreamEvent / StreamResult (subset)

enum StopReason: Equatable { case endTurn, toolUse, maxTokens, refusal }
enum BlockStart { case text, toolUse(id: String, name: String) }
enum StreamEvent {
    case contentBlockStart(BlockStart)
    case textDelta(String)
    case toolCallComplete(id: String, name: String)
    case reasoningContent(String)
    case done(stopReason: StopReason)
}
struct StreamFailure: Error {}

struct StreamResult: Equatable {
    var assistantText = ""
    var toolEntries: [String] = []
    var reasoningContent: String? = nil
    var isStreamInterrupted = false
    var stopReason: StopReason? = nil
    var sawContentBlockStart = false
}

/// Port of processStreamEvents' reduction: events fold into the result; a
/// throw marks `isStreamInterrupted`; a plain end of the sequence sets NOTHING.
func reduce(_ events: [StreamEvent], throwsAfter: Int? = nil) -> StreamResult {
    var r = StreamResult()
    do {
        for (i, ev) in events.enumerated() {
            if let n = throwsAfter, i == n { throw StreamFailure() }
            switch ev {
            case .contentBlockStart: r.sawContentBlockStart = true
            case .textDelta(let t): r.assistantText += t
            case .toolCallComplete(_, let name): r.toolEntries.append(name)
            case .reasoningContent(let s): r.reasoningContent = (r.reasoningContent ?? "") + s
            case .done(let reason): r.stopReason = reason
            }
        }
        if let n = throwsAfter, n >= events.count { throw StreamFailure() }
    } catch {
        r.isStreamInterrupted = true
    }
    return r
}

/// Verbatim port of AIChatViewModel.isEmptyResponse.
func isEmptyResponse(_ r: StreamResult) -> Bool {
    return r.assistantText.isEmpty && r.toolEntries.isEmpty
        && !r.isStreamInterrupted
        && r.stopReason != .maxTokens
        && r.stopReason != .refusal
}

/// The loop's verdict on a finished stream, in shipping order.
enum Verdict: Equatable {
    case transientRetry                    // isEmptyResponse → transientError → auto-retry / fallback
    case interruptedResume(partial: Bool)  // nil stopReason surfaced with Resume (partial = text arrived)
    case resumePath                        // stream threw → isStreamInterrupted (Resume path owns it)
    case completed                         // concrete stopReason, normal handling
}
func verdict(_ r: StreamResult) -> Verdict {
    if isEmptyResponse(r) { return .transientRetry }
    if r.isStreamInterrupted { return .resumePath }
    if r.stopReason == nil { return .interruptedResume(partial: !r.assistantText.isEmpty) }
    return .completed
}

// MARK: - [1] content arrived, no message_stop

print("\n[1] content_block_start (+text) but no message_stop → interrupted, resumable")
do {
    let r = reduce([.contentBlockStart(.text), .textDelta("partial reply "), .textDelta("that got cut")])
    check("content arrived", r.assistantText == "partial reply that got cut")
    checkEq("stopReason stays nil (only .done sets it)", r.stopReason, nil)
    check("isStreamInterrupted is NOT set by a silent EOF (only a throw sets it)", r.isStreamInterrupted, false)
    check("not classified as an empty response (text is present)", isEmptyResponse(r), false)
    checkEq("verdict: surfaced as a dropped connection with Resume, partial text kept",
            verdict(r), .interruptedResume(partial: true))
    check("never treated as a completed turn", verdict(r) != .completed)

    // A block started but no text ever arrived: nothing to keep, so the
    // empty classifier takes it first and it goes through transient retry
    // rather than being persisted as an empty completed turn.
    let r2 = reduce([.contentBlockStart(.text)])
    check("block start only: sawContentBlockStart", r2.sawContentBlockStart)
    checkEq("block start only: stopReason nil", r2.stopReason, nil)
    checkEq("block start only → transient retry (not success)", verdict(r2), .transientRetry)

    // Tool block started, never completed, no stop: nothing usable.
    let r3 = reduce([.contentBlockStart(.toolUse(id: "t1", name: "shell_execute"))])
    checkEq("tool block start without completion → transient retry", verdict(r3), .transientRetry)
    check("…no tool entry was materialised", r3.toolEntries.isEmpty)
}

// MARK: - [2] zero events then EOF

print("\n[2] 0 events then EOF")
do {
    let r = reduce([])
    checkEq("result is the zero value", r, StreamResult())
    check("isStreamInterrupted=false (no throw)", r.isStreamInterrupted, false)
    // iOS routes this through the transient path, which is what a dropped
    // connection with nothing received should do: retry the same model, then
    // fall back. It is NOT success and it is NOT the interrupted/Resume path.
    check("isEmptyResponse=true", isEmptyResponse(r))
    checkEq("verdict: transient retry", verdict(r), .transientRetry)
    check("never completed", verdict(r) != .completed)
}

// MARK: - [3] normal message_stop

print("\n[3] normal message_stop → completed")
do {
    let r = reduce([.contentBlockStart(.text), .textDelta("done."), .done(stopReason: .endTurn)])
    checkEq("stopReason endTurn", r.stopReason, .endTurn)
    check("isStreamInterrupted=false", r.isStreamInterrupted, false)
    check("not empty", isEmptyResponse(r), false)
    checkEq("verdict: completed", verdict(r), .completed)

    let tool = reduce([.contentBlockStart(.toolUse(id: "t1", name: "shell_execute")),
                       .toolCallComplete(id: "t1", name: "shell_execute"), .done(stopReason: .toolUse)])
    checkEq("tool turn with stop → completed", verdict(tool), .completed)

    // An empty body WITH a concrete endTurn is still empty (the gpt-5.5 shape),
    // and takes the transient path — that classification is unchanged.
    let emptyStop = reduce([.done(stopReason: .endTurn)])
    checkEq("endTurn with no content → transient retry", verdict(emptyStop), .transientRetry)
}

// MARK: - [4] a thrown stream is the Resume path, not empty

print("\n[4] stream throws mid-flight")
do {
    let r = reduce([.contentBlockStart(.text), .textDelta("half"), .done(stopReason: .endTurn)], throwsAfter: 2)
    check("isStreamInterrupted=true", r.isStreamInterrupted)
    checkEq("stopReason nil (the done event never arrived)", r.stopReason, nil)
    check("text before the throw is kept", r.assistantText == "half")
    check("not empty (interrupted is exempt)", isEmptyResponse(r), false)
    checkEq("verdict: resume path", verdict(r), .resumePath)

    let r2 = reduce([], throwsAfter: 0)
    check("throw before any event: interrupted", r2.isStreamInterrupted)
    check("…and NOT empty (retry belongs to the error's own catch, not the empty path)", isEmptyResponse(r2), false)
}

// MARK: - [5] shipping source cross-check

print("\n[5] shipping source cross-check")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let sse = sourceOf("Agent/Chat/AIChatViewModel+SSEStream.swift")
let vm = sourceOf("Agent/Chat/AIChatViewModel.swift")
check("SSEStream source read", !sse.isEmpty)
check("view model source read", !vm.isEmpty)
check("isStreamInterrupted is set in the stream's catch block",
      sse.contains("} catch {\n            result.isStreamInterrupted = true"))
checkEq("…and nowhere else in the stream reducer",
        sse.components(separatedBy: "result.isStreamInterrupted = true").count - 1, 1)
check("stopReason is assigned from the done event only",
      sse.contains("result.stopReason = reason"))
checkEq("…exactly one assignment site", sse.components(separatedBy: "result.stopReason = ").count - 1, 1)
check("isEmptyResponse treats nil stop + no content as empty (transient path)",
      vm.contains("return r.assistantText.isEmpty && r.toolEntries.isEmpty")
        && vm.contains("return r.assistantText.isEmpty && r.toolEntries.isEmpty\n                    && !r.isStreamInterrupted"))
check("empty → transientError (auto-retry / fallback), not success",
      vm.contains("throw LLMError.transientError(message: \"Server returned an empty response (overloaded or upstream error)\")"))
check("no-tool exit surfaces a nil stopReason (T-stream-drop-silent)",
      vm.contains("if stopReason == nil {\n                    logger.warning(\"Agent loop ended with nil stopReason (stream closed unexpectedly)\")"))
check("…partial reply is worded as a dropped connection, not as empty",
      vm.contains("The connection dropped — this reply may be incomplete. Tap Resume to continue."))
check("…and the no-content variant is distinct",
      vm.contains("Response ended unexpectedly (no content received)"))
check("post-tool exit also surfaces nil stopReason with Resume",
      vm.contains("if stopReason == nil && toolEntries.isEmpty {"))
check("both sites arm canResume", {
    // Each nil-stop block must be followed by `canResume = true` before the next 'hitTurnLimit'.
    var ok = true
    for needle in ["if stopReason == nil {", "if stopReason == nil && toolEntries.isEmpty {"] {
        guard let r = vm.range(of: needle) else { ok = false; continue }
        let tail = vm[r.upperBound...].prefix(2000)
        if !tail.contains("canResume = true") { ok = false }
    }
    return ok
}())
check("the old text-only guard is gone (partial reply no longer persisted as complete)",
      vm.contains("if stopReason == nil && assistantText.isEmpty {"), false)

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
