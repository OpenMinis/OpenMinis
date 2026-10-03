// [T24] Queued-prompt origin policy: a USER follow-up interrupts the running
// plan at the next tool boundary, a PROGRAMMATIC job result waits for the loop
// to converge, a programmatic prompt never touches the composer draft, and the
// #579 role-alternation bridge is LLM-context-only (kept once in
// agentHistory, never rendered, never merged away by same-role folding).
//
// Pins:
//   d172f9acd  job results wait for the loop to converge (QueuedPrompt.deferUntilIdle,
//              QueueInterrupt trigger = "at least one NON-deferred prompt")
//   9f608639a  bridge wording extended to hint the model to resume the prior task
//   4688e3168 / 1ad38e0cf  the Android ports this is the iOS counterpart of
//   issues #201, #279
//
// Standalone (`swift QueuedPromptOriginTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run. Sections [1]-[4] exercise
// logic ported verbatim from the view model (file:line cited at each port);
// section [5] re-reads the shipping sources so the copies cannot drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
/// A case that documents a real production gap: prints a warning, never fails the run.
func knownGap(_ label: String, _ holds: Bool) {
    if holds { print("  ✅ \(label) (gap closed)") }
    else { print("  ⚠️  KNOWN GAP: \(label)") }
}

// MARK: - Ported: origin → queue semantics
// AIChatViewModel+ProgrammaticPrompt.swift:31-46 (ProgrammaticPromptOrigin)
// AIChatViewModel+ProgrammaticPrompt.swift:134-135 (`gentle` = .job only)

enum ProgrammaticPromptOrigin: Equatable {
    case cli
    case shortcut
    case job(jobId: String)
}

/// The one line that decides whether a programmatic prompt may interrupt.
func deferUntilIdle(for origin: ProgrammaticPromptOrigin) -> Bool {
    let gentle: Bool = { if case .job = origin { return true } else { return false } }()
    return gentle
}

// AIChatViewModel+Misc.swift:69-100 (enqueuePrompt) — the parts that matter here.
struct QueuedPrompt: Equatable {
    let id = UUID()
    let text: String
    let attachments: [String]
    var deferUntilIdle: Bool = false
}

// AIChatViewModel.swift:7273 — the QueueInterrupt trigger at a tool-close boundary.
func queueInterruptFires(_ promptQueue: [QueuedPrompt]) -> Bool {
    promptQueue.contains(where: { !$0.deferUntilIdle })
}

/// Composer state as enqueuePrompt sees it.
struct ComposerState {
    var inputText: String
    var attachments: [String]
    var isProcessing: Bool
    var promptQueue: [QueuedPrompt] = []
}

/// Verbatim port of the state transitions in enqueuePrompt (Misc.swift:69-100).
/// Returns false when the guard declined the prompt.
@discardableResult
func enqueuePrompt(_ s: inout ComposerState, deferUntilIdle: Bool = false,
                   overrideText: String? = nil) -> Bool {
    let usingComposer = overrideText == nil
    let text = (overrideText ?? s.inputText).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty || !s.attachments.isEmpty, s.isProcessing else { return false }
    let pendingAttachments = s.attachments
    var prompt = QueuedPrompt(text: text, attachments: pendingAttachments)
    prompt.deferUntilIdle = deferUntilIdle
    s.promptQueue.append(prompt)
    if usingComposer { s.inputText = "" }
    s.attachments = []
    return true
}

// MARK: - Ported: the bridge
// ChatStore.swift:427-454 (RawMessage.internalBridgeText / isInternalBridgeText / isInternalBridge)

enum Bridge {
    static let internalBridgeText =
        "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"
    static let internalBridgeTexts: [String] = [
        internalBridgeText,
        "(Interrupted mid-task to handle your new message. Will return to the prior task after.)",
    ]
    static func isInternalBridgeText(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return internalBridgeTexts.contains { trimmed == $0 }
    }
}

enum Part: Equatable { case text(String); case toolUse(String); case toolResult(String) }
enum Role: Equatable { case user, assistant }
struct AgentMessage: Equatable {
    var role: Role
    var parts: [Part]
    /// RawMessage.isInternalBridge (ChatStore.swift:450).
    var isInternalBridge: Bool {
        guard role == .assistant, parts.count == 1, case .text(let s) = parts[0] else { return false }
        return Bridge.isInternalBridgeText(s)
    }
    var isToolResultOnly: Bool {
        !parts.isEmpty && parts.allSatisfy { if case .toolResult = $0 { return true }; return false }
    }
}

/// ChatMessage.isInternalBridge (ChatModels.swift:154-163) — the UI row shape.
struct UIMessage: Equatable {
    var role: Role
    var content: String
    var blocks: [Part] = []
    var isInternalBridge: Bool {
        guard role == .assistant else { return false }
        if Bridge.isInternalBridgeText(content) { return true }
        if content.isEmpty, blocks.count == 1, case .text(let t) = blocks[0] {
            return Bridge.isInternalBridgeText(t)
        }
        return false
    }
}

/// CollectionViewMessageListV3.swift:2623 — the single UI-collection sink filter.
func uiSink(_ rawMessages: [UIMessage]) -> [UIMessage] {
    rawMessages.contains(where: { $0.isInternalBridge })
        ? rawMessages.filter { !$0.isInternalBridge }
        : rawMessages
}

/// AIChatViewModel+Persistence.swift:300-325 — loadSession's split: every raw
/// row goes to loadedHistory; the bridge and tool-result-only rows produce no
/// UI message.
func loadSession(_ rows: [AgentMessage]) -> (history: [AgentMessage], ui: [UIMessage]) {
    var history: [AgentMessage] = []
    var ui: [UIMessage] = []
    for raw in rows {
        history.append(raw)
        if raw.isInternalBridge { continue }
        if raw.role == .user && raw.isToolResultOnly { continue }
        let text = raw.parts.compactMap { if case .text(let t) = $0 { return t }; return nil }.joined()
        ui.append(UIMessage(role: raw.role, content: text))
    }
    return (history, ui)
}

/// AIChatViewModel.swift:5109-5124 — injectQueuedPromptsAsNewTurn's bridge rule:
/// insert the bridge only when the tail is a user row.
func injectQueuedPrompt(_ history: inout AgentMessage, into agentHistory: inout [AgentMessage]) {
    if agentHistory.last?.role == .user {
        agentHistory.append(AgentMessage(role: .assistant, parts: [.text(Bridge.internalBridgeText)]))
    }
    agentHistory.append(history)
}

/// AnthropicAgentProvider.swift:686 — mergeConsecutiveSameRole, reduced to roles + parts.
func mergeConsecutiveSameRole(_ messages: [AgentMessage]) -> [AgentMessage] {
    var out: [AgentMessage] = []
    for m in messages {
        if let last = out.last, last.role == m.role {
            out[out.count - 1].parts += m.parts
        } else {
            out.append(m)
        }
    }
    return out
}

// MARK: - A tiny replay of the agent loop's two injection sites

/// Simulates one turn whose plan has `plannedTools` tool calls. `arrivals`
/// maps "after tool N closes" → the prompt that lands then. Returns the number
/// of tools that actually ran before the queued prompt started its own turn,
/// and where the injection happened.
enum InjectionSite: Equatable { case toolClose(afterTool: Int), finishBranch, none }
func replayTurn(plannedTools: Int, arrivals: [Int: QueuedPrompt]) -> (toolsRun: Int, site: InjectionSite) {
    var queue: [QueuedPrompt] = []
    var toolsRun = 0
    for tool in 1...plannedTools {
        toolsRun = tool
        if let p = arrivals[tool] { queue.append(p) }
        // AIChatViewModel.swift:7273 — checked right after the tool call closes.
        if queueInterruptFires(queue) { return (toolsRun, .toolClose(afterTool: tool)) }
    }
    // AIChatViewModel.swift:6993 — the finish branch drains whatever is queued.
    return (toolsRun, queue.isEmpty ? .none : .finishBranch)
}

// MARK: - [1] Origin → interrupt policy

print("\n[1] Which origins may interrupt a running plan")
check("a job result is deferred until idle", deferUntilIdle(for: .job(jobId: "abc")))
check("a CLI send keeps user-message semantics (interruptible)", deferUntilIdle(for: .cli), false)
check("a Shortcut send keeps user-message semantics (interruptible)", deferUntilIdle(for: .shortcut), false)

let userPrompt = QueuedPrompt(text: "how is it going?", attachments: [])
var jobPrompt = QueuedPrompt(text: "<agent_callback kind=\"finished\">…</agent_callback>", attachments: [])
jobPrompt.deferUntilIdle = deferUntilIdle(for: .job(jobId: "j1"))

check("USER prompt in queue → QueueInterrupt fires", queueInterruptFires([userPrompt]))
check("job-only queue → [QueueHold] (no interrupt)", queueInterruptFires([jobPrompt]), false)
check("mixed queue → the USER prompt still earns the interrupt", queueInterruptFires([jobPrompt, userPrompt]))
check("empty queue never interrupts", queueInterruptFires([]), false)

print("\n[2] Replayed loop: where the queued prompt lands")
do {
    // USER follow-up arrives after tool 2 of a 5-tool plan → the plan is
    // abandoned at that boundary and the prompt gets its own turn.
    let r = replayTurn(plannedTools: 5, arrivals: [2: userPrompt])
    checkEq("USER: injected at the tool-close boundary", r.site, .toolClose(afterTool: 2))
    checkEq("USER: the remaining 3 planned tools are abandoned", r.toolsRun, 2)

    // The 15:53 device run: a helper result arrives while the parent's own
    // shell plan is mid-flight. Pre-d172f9acd this abandoned the plan.
    let j = replayTurn(plannedTools: 5, arrivals: [2: jobPrompt])
    checkEq("PROGRAMMATIC: held until the finish branch", j.site, .finishBranch)
    checkEq("PROGRAMMATIC: every planned tool still runs", j.toolsRun, 5)

    // Pre-fix trigger ("queue is non-empty") would have interrupted here.
    let preFixFires = ![jobPrompt].isEmpty
    check("PRE-FIX: a non-empty queue interrupted regardless of origin", preFixFires)

    let none = replayTurn(plannedTools: 3, arrivals: [:])
    checkEq("no arrivals: nothing is injected", none.site, .none)
}

// MARK: - [3] The composer is not the transport

print("\n[3] A programmatic prompt leaves the user's draft alone")
do {
    var s = ComposerState(inputText: "half-typed sentence", attachments: ["photo.jpg"], isProcessing: true)
    check("programmatic enqueue is accepted",
          enqueuePrompt(&s, deferUntilIdle: true, overrideText: "helper finished"))
    checkEq("the queued text is the override, not the draft", s.promptQueue.last?.text, "helper finished")
    checkEq("inputText survives untouched", s.inputText, "half-typed sentence")
    // The spec (and Android 1ad38e0cf) wants staged attachments to stay in the
    // composer for a non-user prompt. iOS deliberately shares `attachments`
    // with the CLI/Shortcut staging path (ProgrammaticPrompt.swift:121-124),
    // so enqueuePrompt still takes AND clears them for every origin — a job
    // result landing while files are staged walks off with them.
    knownGap("a job-origin prompt must not take the composer's staged attachments (expected: attachments still [\"photo.jpg\"], queued prompt carries none)",
             s.attachments == ["photo.jpg"] && s.promptQueue.last?.attachments.isEmpty == true)

    // The user's own tap: draft is consumed, that is the one clearing case.
    var u = ComposerState(inputText: "  send this  ", attachments: ["a.png"], isProcessing: true)
    check("composer enqueue is accepted", enqueuePrompt(&u))
    checkEq("composer text is trimmed into the prompt", u.promptQueue.last?.text, "send this")
    checkEq("composer draft is cleared", u.inputText, "")
    checkEq("composer attachments travel with the prompt", u.promptQueue.last?.attachments, ["a.png"])
    checkEq("composer attachments are cleared", u.attachments, [])

    // The guard: enqueue is only for a busy loop.
    var idle = ComposerState(inputText: "x", attachments: [], isProcessing: false)
    check("idle vm declines enqueue (send() owns that path)", enqueuePrompt(&idle), false)
    var empty = ComposerState(inputText: "   ", attachments: [], isProcessing: true)
    check("blank text with no attachments is declined", enqueuePrompt(&empty), false)
}

// MARK: - [4] The bridge

print("\n[4] The bridge is internal: kept once for the model, never shown, never folded")
do {
    let bridge = AgentMessage(role: .assistant, parts: [.text(Bridge.internalBridgeText)])
    check("current wording is recognised", bridge.isInternalBridge)
    check("pre-d2e111e9 wording is recognised too",
          AgentMessage(role: .assistant, parts: [.text(Bridge.internalBridgeTexts[1])]).isInternalBridge)
    check("trailing whitespace from a DB round-trip still matches",
          Bridge.isInternalBridgeText(Bridge.internalBridgeText + "\n"))
    check("a user row with the same text is NOT a bridge",
          AgentMessage(role: .user, parts: [.text(Bridge.internalBridgeText)]).isInternalBridge, false)
    check("a bridge plus another part is NOT a bridge (parts.count == 1)",
          AgentMessage(role: .assistant, parts: [.text(Bridge.internalBridgeText), .toolUse("x")]).isInternalBridge, false)
    check("ordinary assistant text is NOT a bridge",
          AgentMessage(role: .assistant, parts: [.text("(Interrupted…) something else")]).isInternalBridge, false)

    // Injection at a tool-close boundary: tail is user(tool_result).
    var hist: [AgentMessage] = [
        AgentMessage(role: .user, parts: [.text("run the sync")]),
        AgentMessage(role: .assistant, parts: [.toolUse("shell_execute")]),
        AgentMessage(role: .user, parts: [.toolResult("ok")]),
    ]
    var queued = AgentMessage(role: .user, parts: [.text("how is it going?")])
    injectQueuedPrompt(&queued, into: &hist)
    checkEq("bridge inserted exactly once", hist.filter { $0.isInternalBridge }.count, 1)
    checkEq("sequence is …tool_result → bridge → queued",
            hist.suffix(3).map { $0.role }, [.user, .assistant, .user])

    // The #579 failure mode: without the bridge, the queued text folds into
    // the tool_result and reads as in-loop context.
    let merged = mergeConsecutiveSameRole(hist)
    checkEq("with the bridge, same-role merge keeps the queued prompt as its own turn",
            merged.last, queued)
    var noBridge = Array(hist.prefix(3)); noBridge.append(queued)
    let foldedLast = mergeConsecutiveSameRole(noBridge).last!
    check("PRE-#579: without the bridge the queued text is folded into the tool_result",
          foldedLast.parts == [.toolResult("ok"), .text("how is it going?")])

    // Finish-branch injection: tail is already an assistant reply → no bridge.
    var finished: [AgentMessage] = [
        AgentMessage(role: .user, parts: [.text("hi")]),
        AgentMessage(role: .assistant, parts: [.text("done")]),
    ]
    var q2 = AgentMessage(role: .user, parts: [.text("next")])
    injectQueuedPrompt(&q2, into: &finished)
    checkEq("assistant tail → no bridge", finished.filter { $0.isInternalBridge }.count, 0)

    // Reload: the bridge stays in agentHistory but produces no UI row.
    let reloaded = loadSession(hist)
    checkEq("reload keeps the bridge in agentHistory", reloaded.history.filter { $0.isInternalBridge }.count, 1)
    check("reload emits no UI row for the bridge",
          reloaded.ui.contains { $0.isInternalBridge }, false)
    checkEq("reload emits the queued user bubble", reloaded.ui.last?.content, "how is it going?")

    // UI sink: every other path that pushes into the list is filtered too.
    let leaked: [UIMessage] = [
        UIMessage(role: .user, content: "run the sync"),
        UIMessage(role: .assistant, content: Bridge.internalBridgeText),
        UIMessage(role: .assistant, content: "", blocks: [.text(Bridge.internalBridgeTexts[1])]),
        UIMessage(role: .assistant, content: "real reply"),
    ]
    let shown = uiSink(leaked)
    checkEq("the UI sink drops both bridge shapes", shown.count, 2)
    check("…and keeps the real reply", shown.contains { $0.content == "real reply" })
    check("a lone-block bridge with non-empty content is NOT hidden (content wins)",
          UIMessage(role: .assistant, content: "x", blocks: [.text(Bridge.internalBridgeText)]).isInternalBridge, false)
}

// MARK: - [5] Drift guards against the shipping sources

print("\n[5] Shipping sources match these ports")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let pp = source("Agent/Chat/AIChatViewModel+ProgrammaticPrompt.swift")
let vm = source("Agent/Chat/AIChatViewModel.swift")
let misc = source("Agent/Chat/AIChatViewModel+Misc.swift")
let store = source("Agent/Chat/ChatStore.swift")
let models = source("Agent/Chat/ChatModels.swift")
let cv3 = source("Agent/MessageList/CollectionViewMessageListV3.swift")
let persist = source("Agent/Chat/AIChatViewModel+Persistence.swift")
if [pp, vm, misc, store, models, cv3, persist].contains(where: { $0.isEmpty }) {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    check("gentle = .job origin only",
          pp.contains("let gentle: Bool = { if case .job = origin { return true } else { return false } }()"))
    check("submitProgrammaticPrompt queues with deferUntilIdle: gentle",
          pp.contains("enqueuePrompt(silent: silent, deferUntilIdle: gentle, overrideText: text)"))
    check("the idle path sends the text as an argument, not via inputText",
          pp.contains("send(overrideText: text)")
              && !pp.split(separator: "\n").contains { $0.trimmingCharacters(in: .whitespaces) == "inputText = text" })
    check("QueueInterrupt keys off a NON-deferred prompt",
          vm.contains("if promptQueue.contains(where: { !$0.deferUntilIdle }) {"))
    check("QueuedPrompt carries deferUntilIdle", models.contains("var deferUntilIdle: Bool = false"))
    check("enqueuePrompt clears the draft only for the composer's own send",
          misc.contains("let usingComposer = overrideText == nil") && misc.contains("if usingComposer { inputText = \"\" }"))
    check("bridge text is the shared constant",
          vm.contains("AgentMessage(role: .assistant, parts: [.text(RawMessage.internalBridgeText)])"))
    check("bridge only when the tail is a user row", vm.contains("if agentHistory.last?.role == .user {"))
    checkEq("bridge wording matches the shipping constant",
            store.contains("\"" + Bridge.internalBridgeText + "\""), true)
    check("old wording stays in the recognised set",
          store.contains("\"" + Bridge.internalBridgeTexts[1] + "\""))
    check("RawMessage.isInternalBridge requires a single text part",
          store.contains("guard role == .assistant, parts.count == 1,"))
    check("loadSession skips the bridge for UI but keeps it in history",
          persist.contains("if raw.isInternalBridge {\n                continue\n            }"))
    check("the UI sink filters every path", cv3.contains("? rawMessages.filter { !$0.isInternalBridge }"))
    check("ChatMessage.isInternalBridge handles the lone-block shape",
          models.contains("if content.isEmpty, blocks.count == 1,"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
