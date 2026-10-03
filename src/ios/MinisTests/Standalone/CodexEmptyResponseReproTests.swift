// Tests for [T-ios-reasoning-only-empty-turn] + [T-ios-callback-tail-empty-turn].
//
// The bug, reported on Android first and confirmed to exist on iOS by code
// analysis: when the history tail is a sub-agent `<agent_callback
// kind="finished">` — a completion report with NO follow-up instruction,
// auto-delivered by AgentJobRegistry.runThen → submitProgrammaticPrompt —
// GPT-5.6 Terra over Codex OAuth answers ~70% of the time with ONE round of
// empty reasoning and a stop: no text, no tool call. Two separate iOS defects
// let that through:
//
//   1. `isEmptyResponse` exempted ANY turn carrying reasoning (`!hasReasoning`),
//      so a reasoning-only stop was not classified as empty at all — no
//      reminder, no transient retry, no group fallback. The turn was treated as
//      a SUCCESS and the run silently ended.
//   2. The one-shot self-heal was gated on `lastEffectiveMessageIsToolResult()`,
//      and a callback's parts are `.text`, never `.toolResult`, so the tail
//      shape never qualified.
//
// Standalone (`swift CodexEmptyResponseReproTests.swift`) for the same reason as
// the neighbouring files: deps/libs/libish_emu.a is device-only arm64, so the
// app cannot link for a simulator and an XCTest bundle has nowhere to run.
// Sections [1]-[3] exercise reproduced logic; section [4] re-reads the shipping
// source so the copies cannot drift from what ships.

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

// MARK: - Reproduced from AIChatViewModel.isEmptyResponse

enum StopReason { case endTurn, toolUse, maxTokens, refusal }

struct StreamResult {
    var assistantText: String = ""
    var toolEntries: [String] = []
    var reasoningContent: String? = nil
    var isStreamInterrupted: Bool = false
    var stopReason: StopReason? = nil
}

/// Verbatim port of the shipping predicate (AIChatViewModel.swift:6434).
func isEmptyResponse(_ r: StreamResult) -> Bool {
    return r.assistantText.isEmpty && r.toolEntries.isEmpty
        && !r.isStreamInterrupted
        && r.stopReason != .maxTokens
        && r.stopReason != .refusal
}

// MARK: - Reproduced from AIChatViewModel+Persistence

enum Part {
    case text(String)
    case toolResult(String)
    case image
}

struct AgentMsg {
    enum Role { case user, assistant }
    var role: Role
    var parts: [Part]
}

let callbackTag = "agent_callback"
func isCallbackText(_ t: String) -> Bool {
    t.hasPrefix("<\(callbackTag) ") || t.hasPrefix("<\(callbackTag)>")
}

func lastEffectiveMessageIsToolResult(_ history: [AgentMsg]) -> Bool {
    guard let last = history.last else { return false }
    return last.parts.contains { if case .toolResult = $0 { return true }; return false }
}

/// Verbatim port of the shipping predicate (AIChatViewModel+Persistence.swift).
func lastEffectiveMessageIsBareAgentCallback(_ history: [AgentMsg]) -> Bool {
    guard let last = history.last, last.role == .user else { return false }
    var sawCallback = false
    for part in last.parts {
        switch part {
        case .text(let t):
            let trimmed = t.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            guard isCallbackText(trimmed) else { return false }
            sawCallback = true
        default:
            return false
        }
    }
    return sawCallback
}

/// The shipping guard's decision: does this empty turn earn the one-shot reminder?
func selfHealFires(history: [AgentMsg], alreadyInjected: Bool = false) -> Bool {
    let tailIsToolResult = lastEffectiveMessageIsToolResult(history)
    let tailIsBareCallback = !tailIsToolResult && lastEffectiveMessageIsBareAgentCallback(history)
    return !alreadyInjected && (tailIsToolResult || tailIsBareCallback)
}

// MARK: - Fixtures

/// The exact envelope AgentJobRegistry.completionCallback produces.
let finishedCallback = """
<agent_callback kind="finished" job="ab12cd34" session="S1" title="check the docs" \
status="done" model="primary" elapsed="8m06s">
<summary>tools 3 (browser_use 2, read_file 1) · turns 4 · tokens in 12k / out 2k</summary>
<result>
The docs confirm the feature shipped in 1.12.
</result>
</agent_callback>
"""

// MARK: - [1] reasoning-only turns

print("\n[1] isEmptyResponse — reasoning-only turns")

// THE REPORTED FAILURE: one round of empty reasoning, then stop.
check("reasoning-only + endTurn is EMPTY (the Codex/Terra stall)",
      isEmptyResponse(StreamResult(reasoningContent: "Let me think about this…",
                                   stopReason: .endTurn)))

// Same shape with no stop reason at all (Anthropic SSE error inside HTTP 200).
check("reasoning-only + nil stop is EMPTY",
      isEmptyResponse(StreamResult(reasoningContent: "thinking…", stopReason: nil)))

// REGRESSION GUARD for 5af31fe21's intent: interleaved thinking emits reasoning
// and then CALLS A TOOL. That turn continues and must never be called empty.
check("reasoning + toolUse stop is NOT empty (interleaved thinking continues)",
      isEmptyResponse(StreamResult(toolEntries: ["read_file"],
                                   reasoningContent: "I should read the file",
                                   stopReason: .toolUse)),
      false)
// [T-ios-tooluse-stop-no-entries] Tool entries are attached while the stream
// runs, so a .toolUse stop with ZERO entries is not "entries pending" — the
// tool call was lost (e.g. index-less proxy deltas). It must be retried like
// any other empty turn, or the run ends silently.
check("reasoning + toolUse stop with no tool entries IS empty",
      isEmptyResponse(StreamResult(reasoningContent: "picking a tool",
                                   stopReason: .toolUse)),
      true)

// Everything the old predicate already got right must still hold.
check("text present is NOT empty",
      isEmptyResponse(StreamResult(assistantText: "Here is the answer.", stopReason: .endTurn)),
      false)
check("tool call present is NOT empty",
      isEmptyResponse(StreamResult(toolEntries: ["shell_execute"], stopReason: .toolUse)),
      false)
check("maxTokens is NOT empty (has its own surfaced path)",
      isEmptyResponse(StreamResult(stopReason: .maxTokens)), false)
check("refusal is NOT empty (deterministic, must not burn retries)",
      isEmptyResponse(StreamResult(stopReason: .refusal)), false)
check("interrupted stream is NOT empty (Resume path owns it)",
      isEmptyResponse(StreamResult(isStreamInterrupted: true, stopReason: nil)), false)
check("totally empty turn is EMPTY (pre-existing behaviour)",
      isEmptyResponse(StreamResult(stopReason: .endTurn)))

// MARK: - [2] callback tail detection

print("\n[2] lastEffectiveMessageIsBareAgentCallback")

check("a bare finished callback is a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.text("do the thing")]),
        AgentMsg(role: .assistant, parts: [.text("delegating")]),
        AgentMsg(role: .user, parts: [.text(finishedCallback)]),
      ]))

check("a progress callback counts too (same instruction-free shape)",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.text("<agent_callback kind=\"progress\" job=\"x\">…</agent_callback>")]),
      ]))

// A callback the user followed up on is NOT this case: the model has something
// to do, and an empty answer to a real instruction is an ordinary empty response.
check("callback + real user text is NOT a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.text(finishedCallback), .text("now summarise that")]),
      ]),
      false)

check("plain user text is NOT a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.text("hello")]),
      ]),
      false)

check("an assistant tail is NOT a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .assistant, parts: [.text(finishedCallback)]),
      ]),
      false)

check("a tool-result tail is NOT a bare callback tail (the other path owns it)",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.toolResult("file contents")]),
      ]),
      false)

check("a callback plus an image is NOT a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.text(finishedCallback), .image]),
      ]),
      false)

check("empty history is NOT a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback([]), false)

check("whitespace around the envelope still detects",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.text("\n\n" + finishedCallback + "\n")]),
      ]))

// Text that merely MENTIONS the tag must not qualify — the prefix test is the
// contract, so a user asking about callbacks is a real instruction.
check("text mentioning the tag mid-sentence is NOT a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback([
        AgentMsg(role: .user, parts: [.text("what does <agent_callback> mean?")]),
      ]),
      false)

// MARK: - [3] the end-to-end decision

print("\n[3] self-heal decision for an empty turn")

let callbackTail = [
    AgentMsg(role: .user, parts: [.text("research this")]),
    AgentMsg(role: .assistant, parts: [.text("delegating")]),
    AgentMsg(role: .user, parts: [.text(finishedCallback)]),
]
let toolResultTail = [
    AgentMsg(role: .assistant, parts: [.text("calling a tool")]),
    AgentMsg(role: .user, parts: [.toolResult("ok")]),
]
let plainUserTail = [AgentMsg(role: .user, parts: [.text("hi")])]

check("THE BUG: empty turn after a bare callback now self-heals",
      selfHealFires(history: callbackTail))
check("tool-result tail still self-heals (pre-existing behaviour)",
      selfHealFires(history: toolResultTail))
check("plain user tail does NOT self-heal (ordinary empty response)",
      selfHealFires(history: plainUserTail), false)
check("the one-shot flag still caps it — callback tail, already injected",
      selfHealFires(history: callbackTail, alreadyInjected: true), false)
check("the one-shot flag still caps it — tool-result tail, already injected",
      selfHealFires(history: toolResultTail, alreadyInjected: true), false)

// The two tail shapes must pick DIFFERENT reminder text: claiming "a tool
// result was just provided" to a callback tail would be a false statement.
print("\n[3b] reminder selection")
check("callback tail selects the callback reminder",
      !lastEffectiveMessageIsToolResult(callbackTail)
        && lastEffectiveMessageIsBareAgentCallback(callbackTail))
check("tool-result tail selects the tool-result reminder",
      lastEffectiveMessageIsToolResult(toolResultTail))

// MARK: - [4] the shipping source still matches these copies

print("\n[4] shipping source cross-check")

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()   // Standalone
    .deletingLastPathComponent()   // MinisTests
    .deletingLastPathComponent()   // ios
let vm = (try? String(contentsOf: root.appendingPathComponent("Agent/Chat/AIChatViewModel.swift"), encoding: .utf8)) ?? ""
let persistence = (try? String(contentsOf: root.appendingPathComponent("Agent/Chat/AIChatViewModel+Persistence.swift"), encoding: .utf8)) ?? ""

check("AIChatViewModel.swift was read", !vm.isEmpty)
check("AIChatViewModel+Persistence.swift was read", !persistence.isEmpty)

// [1] the reasoning exemption is stop-reason gated, not a blanket !hasReasoning
check("isEmptyResponse has no stop-reason reasoning exemption left",
      vm.contains("reasoningLedSomewhere"), false)
check("isEmptyResponse no longer exempts every reasoning-bearing turn",
      vm.contains("&& !hasReasoning && !r.isStreamInterrupted"), false)

// [2] the guard covers both tail shapes
check("the self-heal guard consults the callback predicate",
      vm.contains("lastEffectiveMessageIsBareAgentCallback()"))
check("the guard accepts either tail shape",
      vm.contains("tailIsToolResult || tailIsBareCallback"))
check("the retry request picks the reminder matching the tail",
      vm.contains("tailIsToolResult ? historyWithEmptyToolResultReminder() : historyWithEmptyCallbackReminder()"))

// [3] both predicates and both reminder builders exist
check("the callback predicate ships",
      persistence.contains("func lastEffectiveMessageIsBareAgentCallback() -> Bool"))
check("the callback reminder builder ships",
      persistence.contains("func historyWithEmptyCallbackReminder() -> [AgentMessage]"))
check("the tool-result reminder builder is untouched",
      persistence.contains("func historyWithEmptyToolResultReminder() -> [AgentMessage]"))

// [4] the two reminders say different things, and the callback one does not
// claim a tool result was provided.
let callbackReminderLine = persistence
    .split(separator: "\n")
    .first { $0.contains("The message above is a sub agent's completion report") }
    .map(String.init) ?? ""
check("the callback reminder exists and is distinct",
      !callbackReminderLine.isEmpty)
check("the callback reminder does not claim a tool result was provided",
      callbackReminderLine.contains("A tool result was just provided"), false)
check("the callback reminder still forbids an empty answer",
      callbackReminderLine.contains("Do not return an empty response."))
check("the callback reminder says the user typed nothing",
      callbackReminderLine.contains("the user has not typed anything new"))

// [5] the predicate keys off the real envelope helper, so a tag rename cannot
// silently desync the detection from what AgentCallback emits.
check("the predicate uses AgentCallback.isCallbackText",
      persistence.contains("AgentCallback.isCallbackText(trimmed)"))


// MARK: - [5] tail shape × stop reason matrix [T-ios-empty-turn-matrix]
//
// Sections [1]-[3] lock the two combinations that were actually reported
// (reasoning-only + endTurn, and the callback tail). This section pins the
// REST of the matrix so the next "relax one axis" change cannot quietly leak
// through another: image-only user tail, callback followed by real text,
// stop=maxTokens / refusal with no text, the one-shot cap, and the fact that
// the reminder is an in-flight copy that never touches the stored history.
//
// `decideEmptyTurn` is a port of the retry block in AIChatViewModel.swift
// (~6488-6560): `isEmptyResponse` first, then the tail predicates, then the
// one-shot flag; a still-empty reminder round is a providerError, never a
// second reminder.

enum EmptyTurnDecision: Equatable {
    case notEmpty                  // proceeds as a normal turn (its own stop-reason path)
    case reminderToolResult        // one-shot <system-reminder> on the last tool result
    case reminderCallback          // one-shot <system-reminder> on the bare callback
    case transientError            // LLMError.transientError → auto-retry / group fallback
}

func decideEmptyTurn(_ r: StreamResult, history: [AgentMsg], alreadyInjected: Bool) -> EmptyTurnDecision {
    guard isEmptyResponse(r) else { return .notEmpty }
    let tailIsToolResult = lastEffectiveMessageIsToolResult(history)
    let tailIsBareCallback = !tailIsToolResult && lastEffectiveMessageIsBareAgentCallback(history)
    guard !alreadyInjected, tailIsToolResult || tailIsBareCallback else { return .transientError }
    return tailIsToolResult ? .reminderToolResult : .reminderCallback
}

/// What happens to the reminder round's own result (the `isEmptyResponse(reminderResult)` branch).
enum ReminderRoundOutcome: Equatable { case recovered, providerError }
func afterReminderRound(_ reminderResult: StreamResult) -> ReminderRoundOutcome {
    isEmptyResponse(reminderResult) ? .providerError : .recovered
}

/// Ports of the two reminder builders (AIChatViewModel+Persistence.swift ~1644 / ~1690):
/// they take a VALUE copy of the effective history and mutate only the last part.
let toolResultReminder = "\n\n<system-reminder>The previous response was empty. A tool result was just provided and you MUST continue: respond with the next tool call(s) if more work is needed, or a final text answer for the user. Do not return an empty response.</system-reminder>"
let callbackReminder = "\n\n<system-reminder>The previous response was empty. The message above is a sub agent's completion report, delivered by the system — the user has not typed anything new. You MUST still respond: tell the user what the sub agent found and what it means for the task, call the next tool if more work is needed, or delegate the next step. Do not return an empty response.</system-reminder>"

func historyWithEmptyToolResultReminder(_ effective: [AgentMsg]) -> [AgentMsg] {
    var history = effective
    guard let lastIdx = history.indices.last else { return history }
    var parts = history[lastIdx].parts
    if let partIdx = parts.indices.last(where: { if case .toolResult = parts[$0] { return true }; return false }),
       case let .toolResult(content) = parts[partIdx] {
        parts[partIdx] = .toolResult(content + toolResultReminder)
        history[lastIdx] = AgentMsg(role: history[lastIdx].role, parts: parts)
    }
    return history
}
func historyWithEmptyCallbackReminder(_ effective: [AgentMsg]) -> [AgentMsg] {
    var history = effective
    guard let lastIdx = history.indices.last else { return history }
    var parts = history[lastIdx].parts
    guard let partIdx = parts.indices.last(where: { if case .text = parts[$0] { return true }; return false }),
          case let .text(existing) = parts[partIdx] else { return history }
    parts[partIdx] = .text(existing + callbackReminder)
    history[lastIdx] = AgentMsg(role: history[lastIdx].role, parts: parts)
    return history
}

extension Part: Equatable {}
extension AgentMsg: Equatable {}

print("\n[5] tail shape × stop reason matrix")

let emptyEndTurn = StreamResult(stopReason: .endTurn)
let imageOnlyTail = [
    AgentMsg(role: .user, parts: [.text("look at this")]),
    AgentMsg(role: .assistant, parts: [.text("sure")]),
    AgentMsg(role: .user, parts: [.image]),
]
let callbackPlusText = [
    AgentMsg(role: .assistant, parts: [.text("delegating")]),
    AgentMsg(role: .user, parts: [.text(finishedCallback), .text("now summarise that")]),
]

// (a) image-only user tail. iOS deliberately scopes the one-shot reminder to
// the two "the model owes us a follow-up" shapes (tool result / bare callback).
// An image-only tail is a user STATEMENT and an empty reply to it takes the
// ordinary transient path (auto-retry → group fallback → surfaced error) —
// it must never be misread as a callback tail, because the callback reminder
// would then tell the model "a sub agent's completion report is above", which
// is false. Pinned here so a future widening of the callback predicate cannot
// silently claim image parts. (Android nudges on any tail — a documented
// platform divergence, not a defect on this side: nothing stalls silently.)
check("image-only tail: not a bare callback tail",
      lastEffectiveMessageIsBareAgentCallback(imageOnlyTail), false)
check("image-only tail: not a tool-result tail",
      lastEffectiveMessageIsToolResult(imageOnlyTail), false)
checkEq("image-only tail + empty → transient error path (retry/fallback), never silent success",
        decideEmptyTurn(emptyEndTurn, history: imageOnlyTail, alreadyInjected: false), .transientError)
checkEq("image-only tail + empty is still classified EMPTY (not treated as a finished turn)",
        isEmptyResponse(emptyEndTurn), true)

// (b) callback + real user text → the user gave an instruction; ordinary empty response.
checkEq("callback + user text + empty → transient error, no reminder",
        decideEmptyTurn(emptyEndTurn, history: callbackPlusText, alreadyInjected: false), .transientError)

// (c) stop=maxTokens with no text is NOT empty: the max_tokens handler owns it
// (surfaces "Response truncated" + Resume) regardless of tail shape.
for (name, tail) in [("tool-result tail", toolResultTail), ("callback tail", callbackTail), ("plain tail", plainUserTail)] {
    checkEq("stop=maxTokens, no text, \(name) → notEmpty (maxTokens path)",
            decideEmptyTurn(StreamResult(stopReason: .maxTokens), history: tail, alreadyInjected: false), .notEmpty)
}
// (d) stop=refusal with no text is deterministic: no reminder, no retry burn.
checkEq("stop=refusal, no text, tool-result tail → notEmpty (refusal path)",
        decideEmptyTurn(StreamResult(stopReason: .refusal), history: toolResultTail, alreadyInjected: false), .notEmpty)
// (e) an interrupted stream is owned by the Resume path.
checkEq("interrupted, tool-result tail → notEmpty (resume path)",
        decideEmptyTurn(StreamResult(isStreamInterrupted: true), history: toolResultTail, alreadyInjected: false), .notEmpty)

// (f) the one-shot cap: after one injection, a second empty turn is an error,
// never a second reminder — for BOTH tail shapes and both stop shapes.
for (name, tail) in [("tool-result tail", toolResultTail), ("callback tail", callbackTail)] {
    for (sname, r) in [("endTurn", emptyEndTurn), ("nil stop", StreamResult(stopReason: nil)),
                       ("reasoning-only", StreamResult(reasoningContent: "hmm", stopReason: .endTurn))] {
        checkEq("\(name), \(sname): first empty → reminder",
                decideEmptyTurn(r, history: tail, alreadyInjected: false),
                name == "tool-result tail" ? .reminderToolResult : .reminderCallback)
        checkEq("\(name), \(sname): already injected → transient error (no loop)",
                decideEmptyTurn(r, history: tail, alreadyInjected: true), .transientError)
    }
}
checkEq("reminder round still empty → providerError (surfaced, not another reminder)",
        afterReminderRound(emptyEndTurn), .providerError)
checkEq("reminder round with text → recovered",
        afterReminderRound(StreamResult(assistantText: "Here you go.", stopReason: .endTurn)), .recovered)
checkEq("reminder round with a tool call → recovered",
        afterReminderRound(StreamResult(toolEntries: ["shell_execute"], stopReason: .toolUse)), .recovered)

// (g) the reminder is in-flight only: the builders return a modified COPY and
// the input history is untouched; the reminder is appended to exactly one part.
do {
    let before = toolResultTail
    let withReminder = historyWithEmptyToolResultReminder(before)
    check("tool-result reminder: source history unchanged", before == toolResultTail)
    check("tool-result reminder: appended to the last tool result",
          withReminder.last?.parts.last == .toolResult("ok" + toolResultReminder))
    check("tool-result reminder: nothing else changed",
          withReminder.count == before.count && withReminder.dropLast() == before.dropLast())

    let cb = callbackTail
    let withCb = historyWithEmptyCallbackReminder(cb)
    check("callback reminder: source history unchanged", cb == callbackTail)
    check("callback reminder: appended to the last text part",
          withCb.last?.parts.last == .text(finishedCallback + callbackReminder))
    check("callback reminder never claims a tool result was provided",
          callbackReminder.contains("A tool result was just provided"), false)
    check("the two reminders differ", toolResultReminder != callbackReminder)
    // A callback builder on a tool-result tail is a no-op (the text guard fails) —
    // the wrong reminder can never be glued onto the wrong tail shape.
    check("callback builder on a tool-result tail leaves it untouched",
          historyWithEmptyCallbackReminder(toolResultTail) == toolResultTail)
    check("tool-result builder on a callback tail leaves it untouched",
          historyWithEmptyToolResultReminder(callbackTail) == callbackTail)
}

// (h) source: the reminder never reaches persistence or the UI. Both builders
// start from a value copy of the effective history, and the only call site of
// either is the single retry request in the view model.
check("tool-result builder starts from a copy of effectiveAgentHistory()",
      persistence.contains("func historyWithEmptyToolResultReminder() -> [AgentMessage] {\n        var history = effectiveAgentHistory()"))
check("callback builder starts from a copy of effectiveAgentHistory()",
      persistence.contains("func historyWithEmptyCallbackReminder() -> [AgentMessage] {\n        var history = effectiveAgentHistory()"))
checkEq("tool-result builder is called from exactly one place in the view model",
        vm.components(separatedBy: "historyWithEmptyToolResultReminder()").count - 1, 1)
checkEq("callback builder is called from exactly one place in the view model",
        vm.components(separatedBy: "historyWithEmptyCallbackReminder()").count - 1, 1)
check("that place is the reminder request itself (messages: of streamWithAutoRetry)",
      vm.contains("messages: applyRequestImageBudget(tailIsToolResult ? historyWithEmptyToolResultReminder() : historyWithEmptyCallbackReminder())"))
check("the builders are never fed to persistAgentMessage / agentHistory",
      vm.contains("agentHistory = historyWithEmpty") || vm.contains("persistAgentMessage(historyWithEmpty")
        || persistence.contains("agentHistory = historyWithEmpty"), false)
check("one-shot flag is set BEFORE the reminder request is sent",
      (vm.range(of: "didInjectEmptyToolReminderThisRun = true")?.lowerBound ?? vm.endIndex)
        < (vm.range(of: "let reminderStream = try await streamWithAutoRetry(")?.lowerBound ?? vm.startIndex))
check("a still-empty reminder round throws providerError, not another reminder",
      vm.contains("if isEmptyResponse(reminderResult) {")
        && vm.contains("throw LLMError.providerError(message: \"The model returned no response after a tool result, even after a reminder."))
check("the empty-turn guard falls through to transientError for every other tail",
      vm.contains("guard !didInjectEmptyToolReminderThisRun, tailIsToolResult || tailIsBareCallback else {")
        && vm.contains("throw LLMError.transientError(message: \"Server returned an empty response (overloaded or upstream error)\")"))

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
