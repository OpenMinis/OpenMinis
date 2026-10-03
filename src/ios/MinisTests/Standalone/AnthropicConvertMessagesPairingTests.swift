// Tests for [T-ios-anthropic-boundary-pairing] — the two self-healing passes
// AnthropicAgentProvider.convertMessages runs on EVERY outbound payload:
//
//   * injectMissingToolResults (04db20f01): a tool_use not answered in the
//     very next user message gets a synthetic error tool_result spliced in
//     right after its assistant turn — Anthropic 400s on
//     `tool_use ids were found without tool_result blocks immediately after`.
//   * stripOrphanToolResults (60b4603f0): a tool_result whose tool_use_id is
//     not in the most recent assistant turn is dropped — Anthropic 400s on
//     `unexpected tool_use_id … no corresponding tool_use block`.
//
// The point being protected: these run INSIDE convertMessages, i.e. on the
// exact snapshot each request is built from. The startup-time orphan sweep
// in runAgentLoop only sees the history as it was when the loop began; a
// fallback re-send that re-snapshots a half-finished turn must still be
// healed, or the second model gets the 400 the first one caused.
//
// Ported verbatim (minus logging) from
// src/ios/Providers/Anthropic/AnthropicAgentProvider.swift (~572 / ~632);
// section [4] re-reads the source so the copy cannot drift. Standalone
// (`swift AnthropicConvertMessagesPairingTests.swift`) because the app cannot
// link for a simulator.

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

// MARK: - Minimal mirror of Providers/AgentProvider.swift

enum AgentContentPart: Equatable {
    case text(String)
    case toolUse(id: String, name: String)
    case toolResult(id: String, name: String, content: String, isError: Bool)
}
struct AgentMessage: Equatable {
    enum Role { case user, assistant }
    let role: Role
    var parts: [AgentContentPart]
}

let syntheticText = "Tool execution was interrupted before a result was returned (the session or stream ended mid-tool). Treat this tool call as unfinished and retry or proceed without its result."

// MARK: - Verbatim port: injectMissingToolResults

func injectMissingToolResults(_ messages: [AgentMessage]) -> [AgentMessage] {
    var result: [AgentMessage] = []
    result.reserveCapacity(messages.count + 2)
    var i = 0
    while i < messages.count {
        let msg = messages[i]
        result.append(msg)
        guard msg.role == .assistant else { i += 1; continue }

        let toolUses = msg.parts.compactMap { part -> (id: String, name: String)? in
            if case .toolUse(let id, let name) = part { return (id, name) }
            return nil
        }
        guard !toolUses.isEmpty else { i += 1; continue }

        var satisfied: Set<String> = []
        if i + 1 < messages.count, messages[i + 1].role == .user {
            for part in messages[i + 1].parts {
                if case .toolResult(let id, _, _, _) = part { satisfied.insert(id) }
            }
        }

        let missing = toolUses.filter { !satisfied.contains($0.id) }
        guard !missing.isEmpty else { i += 1; continue }

        let placeholders = missing.map { tu in
            AgentContentPart.toolResult(id: tu.id, name: tu.name, content: syntheticText, isError: true)
        }
        if i + 1 < messages.count, messages[i + 1].role == .user {
            var next = messages[i + 1]
            next.parts = placeholders + next.parts
            result.append(next)
            i += 2
        } else {
            result.append(AgentMessage(role: .user, parts: placeholders))
            i += 1
        }
    }
    return result
}

// MARK: - Verbatim port: stripOrphanToolResults

func stripOrphanToolResults(_ messages: [AgentMessage]) -> [AgentMessage] {
    var result = messages
    var liveToolUseIds: Set<String> = []
    for i in 0..<result.count {
        switch result[i].role {
        case .assistant:
            liveToolUseIds = Set(result[i].parts.compactMap { part -> String? in
                if case .toolUse(let id, _) = part { return id }
                return nil
            })
        case .user:
            let original = result[i].parts
            let kept = original.filter { part in
                if case .toolResult(let id, _, _, _) = part { return liveToolUseIds.contains(id) }
                return true
            }
            if kept.count != original.count { result[i].parts = kept }
            for part in kept {
                if case .toolResult(let id, _, _, _) = part { liveToolUseIds.remove(id) }
            }
        }
    }
    return result.filter { !$0.parts.isEmpty }
}

/// convertMessages' pipeline, in its shipping order: inject first so anything
/// it adds is then validated by strip.
func heal(_ messages: [AgentMessage]) -> [AgentMessage] {
    stripOrphanToolResults(injectMissingToolResults(messages))
}

// MARK: - Helpers

func user(_ t: String) -> AgentMessage { AgentMessage(role: .user, parts: [.text(t)]) }
func assistant(_ t: String) -> AgentMessage { AgentMessage(role: .assistant, parts: [.text(t)]) }
func calls(_ ids: [String]) -> AgentMessage {
    AgentMessage(role: .assistant, parts: ids.map { .toolUse(id: $0, name: "shell_execute") })
}
func results(_ ids: [String]) -> AgentMessage {
    AgentMessage(role: .user, parts: ids.map { .toolResult(id: $0, name: "shell_execute", content: "ok", isError: false) })
}
func resultIds(_ h: [AgentMessage]) -> [String] {
    h.flatMap { $0.parts.compactMap { if case .toolResult(let id, _, _, _) = $0 { return id }; return nil } }
}
func syntheticIds(_ h: [AgentMessage]) -> [String] {
    h.flatMap { $0.parts.compactMap {
        if case .toolResult(let id, _, let c, let e) = $0, e, c == syntheticText { return id }; return nil
    } }
}
/// Anthropic's rule, as a checkable predicate: every assistant tool_use is
/// answered by a tool_result in the IMMEDIATELY following user message, and
/// every tool_result answers a tool_use in the most recent assistant message.
func anthropicPaired(_ h: [AgentMessage]) -> Bool {
    var live: Set<String> = []
    for (i, m) in h.enumerated() {
        switch m.role {
        case .assistant:
            live = Set(m.parts.compactMap { if case .toolUse(let id, _) = $0 { return id }; return nil })
            if !live.isEmpty {
                guard i + 1 < h.count, h[i + 1].role == .user else { return false }
                let answered = Set(h[i + 1].parts.compactMap { if case .toolResult(let id, _, _, _) = $0 { return id }; return nil })
                guard live.isSubset(of: answered) else { return false }
            }
        case .user:
            for p in m.parts {
                if case .toolResult(let id, _, _, _) = p {
                    guard live.contains(id) else { return false }
                    live.remove(id)
                }
            }
        }
    }
    return true
}

// MARK: - [1] fallback re-send snapshot with a dangling tool_use

print("\n[1] fallback re-send: history gained a dangling tool_use after the loop started")
do {
    // First request: clean. The loop's startup sweep saw this and found nothing.
    let firstSend = [user("list the files"), calls(["toolu_1"]), results(["toolu_1"]), assistant("3 files.")]
    checkEq("clean history passes through untouched", heal(firstSend), firstSend)

    // Mid-run the model called a tool, the stream died, and the group fell
    // back to another model. The fallback re-snapshots the history — which now
    // ends on an unanswered tool_use — and convertMessages sees THAT.
    let fallbackSnapshot = firstSend + [user("now delete them"), calls(["toolu_2"])]
    let out = heal(fallbackSnapshot)
    checkEq("a synthetic tool_result for toolu_2 is in the request body", syntheticIds(out), ["toolu_2"])
    check("it lands in a fresh user message right after the assistant turn",
          out.count == fallbackSnapshot.count + 1 && out.last?.role == .user
            && out.last?.parts == [.toolResult(id: "toolu_2", name: "shell_execute", content: syntheticText, isError: true)])
    check("the request satisfies Anthropic's adjacency rule", anthropicPaired(out))
    check("the caller's snapshot is a value copy — untouched",
          fallbackSnapshot.last == calls(["toolu_2"]))

    // Same dangle, but the user had already typed again: the placeholder is
    // PREPENDED to that user message so tool_result still leads it.
    let typedAfter = firstSend + [calls(["toolu_3"]), user("still there?")]
    let out2 = heal(typedAfter)
    checkEq("no extra message when a user message already follows", out2.count, typedAfter.count)
    check("placeholder is the FIRST part of that user message",
          out2.last?.parts.first == .toolResult(id: "toolu_3", name: "shell_execute", content: syntheticText, isError: true))
    check("the user's text is preserved after it", out2.last?.parts.last == .text("still there?"))
    check("adjacency rule holds", anthropicPaired(out2))

    // A result two messages later does NOT count (Anthropic only looks one ahead).
    let lateResult = [calls(["toolu_4"]), user("wait"), results(["toolu_4"])]
    let out3 = heal(lateResult)
    checkEq("late result: synthetic is injected for toolu_4", syntheticIds(out3), ["toolu_4"])
    check("…and the real late result, now a duplicate, is stripped as an orphan",
          resultIds(out3) == ["toolu_4"])
}

// MARK: - [2] tool_result whose tool_use_id is not in the snapshot

print("\n[2] tool_result with no matching tool_use → not in the request body")
do {
    // Compaction sliced the tool_use away but the result survived.
    let h = [user("hi"), results(["toolu_gone"]), assistant("ok")]
    let out = heal(h)
    check("orphan result is stripped", !resultIds(out).contains("toolu_gone"))
    checkEq("the emptied user message is dropped (empty content is a 400)", out, [user("hi"), assistant("ok")])

    // The id must match the MOST RECENT assistant turn, not any earlier one.
    let stale = [calls(["a"]), results(["a"]), assistant("done"), results(["a"])]
    let out2 = heal(stale)
    checkEq("a second result for an already-consumed id is an orphan", resultIds(out2), ["a"])

    // A result that matches a tool_use from an earlier assistant turn (with an
    // intervening assistant) is an orphan too.
    let crossed = [calls(["x"]), results(["x"]), assistant("…"), user("more"), calls(["y"]), results(["y", "x"])]
    let out3 = heal(crossed)
    checkEq("cross-turn id is stripped, current-turn result kept", resultIds(out3), ["x", "y"])
    check("adjacency rule holds", anthropicPaired(out3))

    // Mixed message: only the orphan part is removed, the user's text stays.
    let mixed = [user("hi"), AgentMessage(role: .user, parts: [.toolResult(id: "ghost", name: "t", content: "?", isError: false), .text("keep me")])]
    checkEq("text next to an orphan result survives", heal(mixed).last?.parts, [.text("keep me")])
}

// MARK: - [3] the two passes compose

print("\n[3] inject then strip")
do {
    // Late-arriving results across consecutive user messages are all kept:
    // the live set is consumed per id, not cleared per user message.
    let split = [calls(["a", "b"]), results(["a"]), AgentMessage(role: .user, parts: [.text("hurry"), .toolResult(id: "b", name: "t", content: "late", isError: false)])]
    let out = heal(split)
    // inject: `b` is not in the immediately-following user message → synthetic
    // prepended there; strip: the real late `b` is then a duplicate → dropped.
    checkEq("synthetic for the id missing from the next message", syntheticIds(out), ["b"])
    checkEq("real late result for b is dropped as a duplicate", resultIds(out), ["b", "a"])
    check("adjacency rule holds", anthropicPaired(out))

    // Synthetic results survive the strip pass (they are validated by it).
    let dangling = [calls(["p", "q"]), results(["p"])]
    let out2 = heal(dangling)
    checkEq("injected placeholder survives stripping", syntheticIds(out2), ["q"])
    checkEq("ordering: placeholder first, then the real result", resultIds(out2), ["q", "p"])

    // The pipeline is a fixed point.
    check("healing is idempotent", heal(out) == out && heal(out2) == out2)

    // Random-ish sweep: every combination of {answered, dangling, orphan,
    // duplicate} ends paired.
    var unpaired = 0
    let shapes: [[AgentMessage]] = [
        [calls(["1"]), user("t"), calls(["2"]), results(["1", "2"])],
        [results(["z"]), calls(["1"]), calls(["2"]), results(["2"])],
        [calls(["1", "2", "3"]), results(["2"]), assistant("x"), results(["1"])],
        [user("a"), assistant("b"), calls(["1"]), user("c"), results(["1"]), calls(["1"]), results(["1"])],
    ]
    for s in shapes where !anthropicPaired(heal(s)) { unpaired += 1 }
    checkEq("every shape ends Anthropic-paired", unpaired, 0)
}

// MARK: - [4] shipping source cross-check

print("\n[4] shipping source cross-check")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let src = sourceOf("Providers/Anthropic/AnthropicAgentProvider.swift")
check("AnthropicAgentProvider.swift was read", !src.isEmpty)
check("convertMessages runs inject first",
      src.contains("let healed = Self.injectMissingToolResults(messages)"))
check("…then strip on the healed copy",
      src.contains("let cleaned = Self.stripOrphanToolResults(healed)"))
check("both passes live INSIDE convertMessages (per request, not per loop start)", {
    guard let fn = src.range(of: "private func convertMessages(_ messages: [AgentMessage])"),
          let inj = src.range(of: "let healed = Self.injectMissingToolResults(messages)"),
          let end = src.range(of: "private static func injectMissingToolResults") else { return false }
    return fn.lowerBound < inj.lowerBound && inj.lowerBound < end.lowerBound
}())
check("convertMessages is what the streaming request path calls",
      src.components(separatedBy: "let anthropicMessages = convertMessages(messages)").count - 1 >= 1)
check("inject looks exactly one message ahead",
      src.contains("if i + 1 < messages.count, messages[i + 1].role == .user {"))
check("inject prepends to an existing next user message",
      src.contains("next.parts = placeholders + next.parts"))
check("synthetic wording unchanged", src.contains(syntheticText))
check("strip consumes matched ids instead of clearing the set",
      src.contains("liveToolUseIds.remove(id)"))
check("strip drops emptied messages", src.contains("return result.filter { !$0.parts.isEmpty }"))

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
