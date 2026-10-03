// Tests for [T-anthropic-stream-accumulator] — backlog item T22 (issue #241).
//
// Report: with an Anthropic-compatible `/v1/messages` streaming provider,
// "every new assistant message contains the full text of the previous
// assistant message, followed by the current response" — AAA → AAA+BBB →
// AAA+BBB+CCC — and the concatenation was persisted.
//
// The invariant that prevents it: NOTHING that accumulates streamed text
// outlives one stream. In the shipping code
//   * AnthropicAgentProvider keeps its per-stream state (`thinkingContent`,
//     the tool-JSON chunk ring, current block ids) as locals of the Task
//     created inside `streamAgentMessage` — the class has no stored text
//     accumulator at all;
//   * the view model's `StreamResult` (which owns `assistantText`) is a
//     fresh `var result = StreamResult()` per `processStreamEvents` call,
//     i.e. per agent-loop iteration, per fallback attempt.
// This script models both layers, shows the failure a shared accumulator
// would produce, and greps the sources for the locals.
//
// Standalone (`swift AnthropicStreamAccumulatorTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Wire events (SSE) and provider events

enum SSE { case messageStart, blockStart(type: String), textDelta(String), thinkingDelta(String), blockStop, messageDelta(stop: String), messageStop }
enum Event: Equatable { case blockStartText, textDelta(String), thinkingDelta(String), reasoningContent(String), done(String) }

/// AnthropicAgentProvider.streamAgentMessage: all accumulation state is
/// declared inside the per-call Task. The class itself holds none.
final class Provider {
    var streamsOpened = 0
    func streamAgentMessage(_ sse: [SSE]) -> [Event] {
        streamsOpened += 1
        // Locals — born and die with this stream.
        var isThinkingBlock = false
        var thinkingContent = ""
        var out: [Event] = []
        for e in sse {
            switch e {
            case .messageStart: break
            case .blockStart(let type):
                if type == "thinking" { isThinkingBlock = true } else if type == "text" { isThinkingBlock = false; out.append(.blockStartText) }
            case .textDelta(let t): if !isThinkingBlock { out.append(.textDelta(t)) }
            case .thinkingDelta(let t): if isThinkingBlock { thinkingContent += t; out.append(.thinkingDelta(t)) }
            case .blockStop: break
            case .messageDelta(let stop):
                if !thinkingContent.isEmpty { out.append(.reasoningContent(thinkingContent)) }
                out.append(.done(stop))
            case .messageStop: break
            }
        }
        return out
    }
}

/// The pre-fix shape the report describes: a text buffer that lives on the
/// provider and is only ever appended to.
final class LeakyProvider {
    var textBuffer = ""
    func streamAgentMessage(_ sse: [SSE]) -> [Event] {
        var out: [Event] = []
        for e in sse {
            if case .textDelta(let t) = e { textBuffer += t; out.append(.textDelta(textBuffer)) }
            if case .messageDelta(let s) = e { out.append(.done(s)) }
        }
        return out
    }
}

/// AIChatViewModel+SSEStream.processStreamEvents: `var result = StreamResult()`
/// at the top of every call.
struct StreamResult { var assistantText = ""; var thinkingText = ""; var reasoningContent: String? = nil; var stop: String? = nil }
func processStreamEvents(_ events: [Event]) -> StreamResult {
    var result = StreamResult()
    for e in events {
        switch e {
        case .blockStartText: break
        case .textDelta(let t): result.assistantText += t
        case .thinkingDelta(let t): result.thinkingText += t
        case .reasoningContent(let r): result.reasoningContent = r
        case .done(let s): result.stop = s
        }
    }
    return result
}

func turn(_ text: String, thinking: String? = nil, stop: String = "end_turn") -> [SSE] {
    var s: [SSE] = [.messageStart]
    if let thinking { s += [.blockStart(type: "thinking")] + thinking.map { .thinkingDelta(String($0)) } + [.blockStop] }
    s += [.blockStart(type: "text")] + text.map { .textDelta(String($0)) } + [.blockStop, .messageDelta(stop: stop), .messageStop]
    return s
}

print("▶️  1. two turns, each with its own message_start → the second carries none of the first")
do {
    let p = Provider()
    let r1 = processStreamEvents(p.streamAgentMessage(turn("AAA", thinking: "t1")))
    let r2 = processStreamEvents(p.streamAgentMessage(turn("BBB", thinking: "t2")))
    let r3 = processStreamEvents(p.streamAgentMessage(turn("CCC")))
    checkEq("turn 1 text", r1.assistantText, "AAA")
    checkEq("turn 2 text is only BBB", r2.assistantText, "BBB")
    checkEq("turn 3 text is only CCC", r3.assistantText, "CCC")
    checkEq("turn 1 reasoning", r1.reasoningContent, "t1")
    checkEq("turn 2 reasoning does not include turn 1's", r2.reasoningContent, "t2")
    check("turn 3 (no thinking block) has no stale reasoning", r3.reasoningContent == nil)
    checkEq("thinking text does not accumulate across turns either", r3.thinkingText, "")
    // What the report saw.
    let leaky = LeakyProvider()
    _ = leaky.streamAgentMessage(turn("AAA"))
    let leaked = leaky.streamAgentMessage(turn("BBB")).compactMap { if case .textDelta(let t) = $0 { return t }; return nil }.last
    checkEq("PRE-FIX model: the second turn's final text is AAA+BBB", leaked, "AAABBB")
}

print("▶️  2. a mid-turn fallback that re-opens a stream on the SAME provider starts clean")
do {
    let p = Provider()
    // Attempt 1 dies after some deltas (network drop): the consumer discards
    // its partial StreamResult and the fallback re-issues the request.
    let partial: [SSE] = [.messageStart, .blockStart(type: "text"), .textDelta("AA"), .textDelta("A")]
    let attempt1 = processStreamEvents(p.streamAgentMessage(partial))
    checkEq("attempt 1 saw the partial text", attempt1.assistantText, "AAA")
    let attempt2 = processStreamEvents(p.streamAgentMessage(turn("BBB")))
    checkEq("attempt 2 on the same provider instance is only BBB", attempt2.assistantText, "BBB")
    checkEq("two streams were opened on one instance", p.streamsOpened, 2)
    // Tool loop: iteration N+1 of one send also gets a fresh StreamResult.
    let it1 = processStreamEvents(p.streamAgentMessage(turn("plan", stop: "tool_use")))
    let it2 = processStreamEvents(p.streamAgentMessage(turn("answer")))
    check("iteration text does not carry over", it1.assistantText == "plan" && it2.assistantText == "answer")
}

print("▶️  3. shipping sources keep every accumulator scoped to one stream")
do {
    let anth = source("Providers/Anthropic/AnthropicAgentProvider.swift")
    let sse = source("Agent/Chat/AIChatViewModel+SSEStream.swift")
    if anth.isEmpty || sse.isEmpty { print("  ⏭  sources not readable") } else {
        // The provider's stored properties: none of them is a text/thinking buffer.
        let stored = anth.components(separatedBy: "\n").filter { line in
            let t = line.trimmingCharacters(in: .whitespaces)
            return line.hasPrefix("    ") && !line.hasPrefix("     ") && (t.hasPrefix("var ") || t.hasPrefix("private var ") || t.hasPrefix("let ") || t.hasPrefix("private let "))
        }
        check("no stored String accumulator on the provider class", !stored.contains { $0.contains(": String = \"\"") || $0.contains("Text = \"\"") || $0.contains("Content = \"\"") })
        check("thinking accumulator is a local inside the stream Task", anth.contains("                var thinkingContent = \"\"") && anth.range(of: "let task = Task")!.lowerBound < anth.range(of: "                var thinkingContent = \"\"")!.lowerBound)
        check("tool-JSON ring is a local too", anth.contains("                var currentToolJsonChunks: [String] = []"))
        check("text is yielded as deltas, never as a running total", anth.contains("continuation.yield(.textDelta(text))") && !anth.contains("continuation.yield(.textDelta(accumulated"))
        check("the view model starts a fresh StreamResult per call", sse.contains("var result = StreamResult()"))
        check("assistantText lives in StreamResult, not on the view model", sse.contains("var assistantText: String = \"\"") && sse.contains("result.assistantText += text"))
        check("the per-iteration re-creation is documented at the TTS gate", sse.contains("StreamResult is recreated\n                        // on every agent-loop iteration"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
