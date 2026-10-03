// [T29] Stop / Retry keep what was already generated; errors are cleared
// only where they should be; error attribution between the top banner and
// the assistant bubble is deterministic and survives a session switch.
//
// Pins:
//   d2f282e04  mid-stream retry / fallback clears only the UNCOMMITTED tail
//              (clearUncommittedStreamTail), never committed prior-round text
//   bdc8d0a5f  retry()/resume() use the same helper instead of a blind
//              removeSubrange(committedBlockCount...) that lagged behind
//   6854085e1  a queued user bubble trailing the assistant no longer sends the
//              error to the top banner (messages.last(where: .assistant))
//   40633da8d  an error with no assistant row gets a persisted carrier row
//              (reportTurnFailure) so it survives leaving and coming back
//   4dd44b317  clearSupersededErrors: clear every errored row, drop empty
//              carriers, keep the resume target, clear the persisted copy
//   8889d9c71  error_info persisted on the assistant row and restored on reload
//
// Standalone (`swift StopRetryPreservationTests.swift`): the app cannot link
// for a simulator (deps/libs/libish_emu.a is device-only arm64). The
// ChatMessage model and the four functions under test are ported verbatim
// from AIChatViewModel.swift (file:line cited); section [5] re-reads the
// shipping source so the ports cannot drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported model (ChatModels.swift, reduced to what the functions read)

enum BlockKind: Equatable { case text, thinking, tool }
enum ToolStatus: Equatable { case streaming, running, completed, cancelled, failed }

final class AssistantBlock {
    let kind: BlockKind
    var content: String
    var toolStatus: ToolStatus?
    init(kind: BlockKind, content: String, toolStatus: ToolStatus? = nil) {
        self.kind = kind; self.content = content; self.toolStatus = toolStatus
    }
}

enum Role { case user, assistant, systemInfo }

final class ChatMessage {
    let id = UUID()
    let role: Role
    var content: String
    var blocks: [AssistantBlock]
    var error: String?
    var usage: Int? = nil
    var streamInterruptCount = 0
    var isQueued = false
    init(role: Role, content: String, blocks: [AssistantBlock] = [], isQueued: Bool = false) {
        self.role = role; self.content = content; self.blocks = blocks; self.isQueued = isQueued
    }
}

enum Part: Equatable { case text(String), toolUse(String), toolResult(String) }
struct AgentMessage: Equatable { var role: Role; var parts: [Part] }
extension Role: Equatable {}

// MARK: - Ported: AIChatViewModel.clearUncommittedStreamTail (line 5648)

func clearUncommittedStreamTail(_ message: ChatMessage, committedBlockCount: Int) {
    let lowerBound = max(0, min(committedBlockCount, message.blocks.count))
    guard lowerBound < message.blocks.count else { return }
    var kept = Array(message.blocks[0..<lowerBound])
    for block in message.blocks[lowerBound...] {
        if block.kind == .text { continue }
        if block.kind == .thinking { continue }
        if case .streaming = block.toolStatus { continue }
        if case .running = block.toolStatus { continue }
        kept.append(block)
    }
    message.blocks = kept
}

/// The pre-d2f282e04 clear: every text block, plus in-flight tools, whole message.
func clearStreamBlocks_preFix(_ message: ChatMessage) {
    message.blocks.removeAll { block in
        if block.kind == .text { return true }
        if case .streaming = block.toolStatus { return true }
        if case .running = block.toolStatus { return true }
        return false
    }
}

/// The pre-bdc8d0a5f retry()/resume() trim.
func blindTrim_preFix(_ message: ChatMessage, committedBlockCount: Int) {
    if committedBlockCount < message.blocks.count {
        message.blocks.removeSubrange(committedBlockCount...)
    }
}

// MARK: - Ported: the view model state the Stop / error paths touch

final class VM {
    var messages: [ChatMessage] = []
    var agentHistory: [AgentMessage] = []
    var errorMessage: String? = nil                  // the top banner
    var persistedErrorInfo: String? = nil            // the DB row's error_info (last assistant)
    var committedBlockCount = 0
    var prevCommittedBlockCount = 0
    var userDidCancel = false
    var canResume = false

    func persistErrorInfo(_ e: String?) { persistedErrorInfo = e }

    /// AIChatViewModel.swift:5324-5330
    func reportTurnFailure(_ displayDesc: String) {
        errorMessage = displayDesc
        let carrier = ChatMessage(role: .assistant, content: "", blocks: [])
        carrier.error = displayDesc
        messages.append(carrier)
        persistErrorInfo(displayDesc)
    }

    /// The error epilogue shared by send / retry / retryFrom / rerun /
    /// queued-drain (AIChatViewModel.swift:3273 and siblings), post-6854085e1.
    func attachError(_ displayDesc: String) {
        if let last = messages.last(where: { $0.role == .assistant }) {
            last.error = displayDesc
            last.blocks.removeAll { $0.kind == .text && $0.content.isEmpty }
            for block in last.blocks {
                if case .streaming = block.toolStatus { block.toolStatus = .cancelled }
                else if case .running = block.toolStatus { block.toolStatus = .cancelled }
            }
            persistErrorInfo(displayDesc)
        } else {
            reportTurnFailure(displayDesc)
        }
    }

    /// The pre-6854085e1 epilogue: keyed on messages.last, banner otherwise.
    func attachError_preFix(_ displayDesc: String) {
        if let last = messages.last, last.role == .assistant {
            last.error = displayDesc
        } else {
            errorMessage = displayDesc               // in-memory only
        }
    }

    /// AIChatViewModel.swift:5361-5392
    func clearSupersededErrors(reason: String, keeping keep: ChatMessage? = nil) {
        var erroredRows: [ObjectIdentifier: Bool] = [:]
        for msg in messages where msg.error != nil {
            erroredRows[ObjectIdentifier(msg)] = true
            msg.error = nil
            msg.streamInterruptCount = 0
        }
        let clearedCount = erroredRows.count
        var removedCarriers = 0
        messages.removeAll { msg in
            guard erroredRows[ObjectIdentifier(msg)] == true else { return false }
            guard msg !== keep else { return false }
            let isEmptyCarrier = msg.role == .assistant
                && msg.blocks.isEmpty
                && msg.content.isEmpty
                && msg.usage == nil
            if isEmptyCarrier { removedCarriers += 1 }
            return isEmptyCarrier
        }
        errorMessage = nil
        guard clearedCount > 0 || removedCarriers > 0 else { return }
        persistErrorInfo(nil)
    }

    /// AIChatViewModel.swift:5403-5596 — handleUserCancelledCleanup, the
    /// userDidCancel branch. Case 1 = tool cancel, Case 0 = thinking-only
    /// placeholder, Case 2 = text/param cancel.
    func handleUserCancelledCleanup() {
        guard userDidCancel else { return }
        userDidCancel = false
        guard let candidateIdx = messages.lastIndex(where: { $0.role == .assistant }) else { return }
        let last = messages[candidateIdx]
        let lastHistoryIsToolResult = agentHistory.last?.role == .user
            && agentHistory.last?.parts.contains(where: { if case .toolResult = $0 { return true }; return false }) == true
        if lastHistoryIsToolResult {
            for block in last.blocks {
                if let status = block.toolStatus {
                    switch status {
                    case .streaming, .running: block.toolStatus = .cancelled
                    default: break
                    }
                }
            }
            let toolCancelStart = min(committedBlockCount, last.blocks.count)
            if toolCancelStart < last.blocks.count {
                var tailTextParts: [Part] = []
                for i in toolCancelStart..<last.blocks.count {
                    let block = last.blocks[i]
                    if case .text = block.kind, !block.content.isEmpty { tailTextParts.append(.text(block.content)) }
                }
                if !tailTextParts.isEmpty {
                    tailTextParts.append(.text("<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"))
                    agentHistory.append(AgentMessage(role: .assistant, parts: tailTextParts))
                    prevCommittedBlockCount = committedBlockCount
                    committedBlockCount = last.blocks.count
                }
            }
            canResume = true
            return
        }
        let hasNonEmptyText = last.blocks.contains { if case .text = $0.kind, !$0.content.isEmpty { return true }; return false }
        let hasAnyToolUse = last.blocks.contains { $0.toolStatus != nil }
        if !hasNonEmptyText, !hasAnyToolUse, prevCommittedBlockCount == 0 {
            messages.remove(at: candidateIdx)
            return
        }
        last.blocks.removeAll { if case .streaming = $0.toolStatus { return true }; return false }
        for block in last.blocks where block.toolStatus == .running { block.toolStatus = .cancelled }
        let hasVisibleContent = last.blocks.contains(where: { !$0.content.isEmpty })
        if hasVisibleContent {
            var textParts: [Part] = []
            let safeStart = min(prevCommittedBlockCount, last.blocks.count)
            for i in safeStart..<last.blocks.count {
                let block = last.blocks[i]
                if case .text = block.kind, !block.content.isEmpty { textParts.append(.text(block.content)) }
            }
            if !textParts.isEmpty, agentHistory.last?.role != .assistant {
                textParts.append(.text("<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"))
                agentHistory.append(AgentMessage(role: .assistant, parts: textParts))
            }
            prevCommittedBlockCount = committedBlockCount
            committedBlockCount = last.blocks.count
            canResume = true
        } else if prevCommittedBlockCount > 0 {
            if prevCommittedBlockCount < last.blocks.count { last.blocks.removeSubrange(prevCommittedBlockCount...) }
            committedBlockCount = prevCommittedBlockCount
            if agentHistory.last?.role == .assistant { agentHistory.removeLast() }
            canResume = true
        } else {
            messages.remove(at: candidateIdx)
            if agentHistory.last?.role == .assistant { agentHistory.removeLast() }
        }
    }

    /// The reload half of 8889d9c71: `toChatMessage` restores error_info,
    /// normalising empty / whitespace to nil.
    static func reloadedError(_ persisted: String?) -> String? {
        guard let p = persisted?.trimmingCharacters(in: .whitespacesAndNewlines), !p.isEmpty else { return nil }
        return p
    }
}

func kinds(_ m: ChatMessage) -> [String] {
    m.blocks.map { b in
        switch b.kind {
        case .text: return "t(\(b.content))"
        case .thinking: return "think"
        case .tool: return "tool:\(b.toolStatus.map { "\($0)" } ?? "nil")"
        }
    }
}

// MARK: - [1] Stop after a tool executed

print("\n[1] Stop after a tool executed keeps the assistant text and the tool blocks")
do {
    let vm = VM()
    vm.messages = [ChatMessage(role: .user, content: "sync the repo")]
    let asst = ChatMessage(role: .assistant, content: "", blocks: [
        AssistantBlock(kind: .text, content: "Running the sync now."),
        AssistantBlock(kind: .tool, content: "shell_execute", toolStatus: .completed),
        AssistantBlock(kind: .text, content: "It finished; next I will"),          // post-tool text, uncommitted
        AssistantBlock(kind: .tool, content: "read_file", toolStatus: .running),    // in flight at Stop
    ])
    vm.messages.append(asst)
    vm.agentHistory = [
        AgentMessage(role: .user, parts: [.text("sync the repo")]),
        AgentMessage(role: .assistant, parts: [.text("Running the sync now."), .toolUse("shell_execute")]),
        AgentMessage(role: .user, parts: [.toolResult("ok")]),
    ]
    vm.committedBlockCount = 2
    vm.userDidCancel = true
    vm.handleUserCancelledCleanup()
    checkEq("all four blocks are still on screen", vm.messages.last!.blocks.count, 4)
    check("the assistant text before the tool is intact", asst.blocks[0].content == "Running the sync now.")
    check("the completed tool block is untouched", asst.blocks[1].toolStatus == .completed)
    check("the in-flight tool is marked cancelled, not removed", asst.blocks[3].toolStatus == .cancelled)
    check("post-tool text is flushed to agentHistory with the stop marker",
          vm.agentHistory.last == AgentMessage(role: .assistant, parts: [
            .text("It finished; next I will"),
            .text("<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>")]))
    check("the turn is resumable", vm.canResume)
    checkEq("committedBlockCount now covers the flushed tail", vm.committedBlockCount, 4)

    // Text-only Stop (Case 2): partial text is kept and committed once.
    let vm2 = VM()
    vm2.messages = [ChatMessage(role: .user, content: "hi")]
    let a2 = ChatMessage(role: .assistant, content: "", blocks: [
        AssistantBlock(kind: .thinking, content: "…"),
        AssistantBlock(kind: .text, content: "Half an answ"),
        AssistantBlock(kind: .tool, content: "browser_use", toolStatus: .streaming),
    ])
    vm2.messages.append(a2)
    vm2.agentHistory = [AgentMessage(role: .user, parts: [.text("hi")])]
    vm2.userDidCancel = true
    vm2.handleUserCancelledCleanup()
    checkEq("streaming (never-dispatched) tool block is dropped", kinds(a2), ["think", "t(Half an answ)"])
    check("partial text committed to history with the marker",
          vm2.agentHistory.last?.parts.first == .text("Half an answ"))
    check("resumable", vm2.canResume)

    // Case 0: a thinking-only placeholder with nothing committed is removed.
    let vm3 = VM()
    vm3.messages = [ChatMessage(role: .user, content: "hi"),
                    ChatMessage(role: .assistant, content: "", blocks: [AssistantBlock(kind: .thinking, content: "…")])]
    vm3.agentHistory = [AgentMessage(role: .user, parts: [.text("hi")])]
    vm3.userDidCancel = true
    vm3.handleUserCancelledCleanup()
    checkEq("thinking-only placeholder is removed on Stop", vm3.messages.count, 1)
}

// MARK: - [2] Error attribution with a queued prompt trailing

print("\n[2] Queued prompt + Stop/error: the error hangs on the bubble, not the banner")
do {
    let vm = VM()
    let asst = ChatMessage(role: .assistant, content: "", blocks: [
        AssistantBlock(kind: .text, content: "Working…"),
        AssistantBlock(kind: .tool, content: "shell_execute", toolStatus: .running),
    ])
    vm.messages = [ChatMessage(role: .user, content: "go"), asst,
                   ChatMessage(role: .user, content: "how is it going?", isQueued: true)]
    vm.attachError("Server returned an empty response (overloaded or upstream error)")
    check("the error is on the assistant bubble", asst.error != nil)
    check("the top banner stays empty", vm.errorMessage == nil)
    check("the running tool is cancelled, not left spinning", asst.blocks[1].toolStatus == .cancelled)
    check("the error is persisted on the row", vm.persistedErrorInfo != nil)
    checkEq("no carrier row was appended", vm.messages.count, 3)

    let pre = VM()
    let a2 = ChatMessage(role: .assistant, content: "", blocks: [AssistantBlock(kind: .text, content: "Working…")])
    pre.messages = [ChatMessage(role: .user, content: "go"), a2, ChatMessage(role: .user, content: "queued", isQueued: true)]
    pre.attachError_preFix("empty response")
    check("PRE-FIX: messages.last was the queued user row → banner path", pre.errorMessage != nil && a2.error == nil)
}

// MARK: - [3] Recovery clears exactly what it should

print("\n[3] clearSupersededErrors: every error cleared, empty carriers removed, non-empty rows kept")
do {
    let vm = VM()
    let good = ChatMessage(role: .assistant, content: "", blocks: [AssistantBlock(kind: .text, content: "a real reply")])
    good.error = "Provider error: GPT-6-Astra (OpenAI): Rate limited"
    good.streamInterruptCount = 2
    let carrier1 = ChatMessage(role: .assistant, content: "", blocks: [])
    carrier1.error = "Rate limited"
    let carrier2 = ChatMessage(role: .assistant, content: "", blocks: [])
    carrier2.error = "Rate limited again"
    let liveEmpty = ChatMessage(role: .assistant, content: "", blocks: [])   // the in-flight bubble: no error
    let withUsage = ChatMessage(role: .assistant, content: "", blocks: [])
    withUsage.error = "x"; withUsage.usage = 1200
    vm.messages = [ChatMessage(role: .user, content: "q"), good, carrier1, carrier2, withUsage, liveEmpty]
    vm.errorMessage = "Rate limited"
    vm.persistedErrorInfo = "Rate limited"

    vm.clearSupersededErrors(reason: "agent-loop-start")
    check("the non-empty reply keeps its content", good.blocks.first?.content == "a real reply")
    check("…and loses only its error", good.error == nil && good.streamInterruptCount == 0)
    check("both empty carriers are removed", !vm.messages.contains { $0 === carrier1 || $0 === carrier2 })
    check("the live in-flight bubble (no error) is NOT removed", vm.messages.contains { $0 === liveEmpty })
    check("a row with usage is not an empty carrier", vm.messages.contains { $0 === withUsage } && withUsage.error == nil)
    check("the banner is cleared", vm.errorMessage == nil)
    check("the persisted copy is cleared", vm.persistedErrorInfo == nil)
    check("no row carries an error afterwards", vm.messages.allSatisfy { $0.error == nil })

    // keeping: the resume target is a bare carrier and must survive.
    let vm2 = VM()
    let target = ChatMessage(role: .assistant, content: "", blocks: [])
    target.error = "network"
    vm2.messages = [ChatMessage(role: .user, content: "q"), target]
    vm2.clearSupersededErrors(reason: "resume", keeping: target)
    check("the resume target survives the sweep", vm2.messages.contains { $0 === target })
    check("…with its error cleared", target.error == nil)

    // Nothing to do → nothing persisted.
    let vm3 = VM()
    vm3.messages = [ChatMessage(role: .assistant, content: "", blocks: [AssistantBlock(kind: .text, content: "ok")])]
    vm3.persistedErrorInfo = "stale"
    vm3.clearSupersededErrors(reason: "noop")
    checkEq("no errors → the persisted store is not touched", vm3.persistedErrorInfo, "stale")
}

// MARK: - [4] Mid-stream fallback and retry keep committed content

print("\n[4] Mid-stream fallback / retry clear only the uncommitted tail")
do {
    let m = ChatMessage(role: .assistant, content: "", blocks: [
        AssistantBlock(kind: .text, content: "Round 1 text (persisted)"),
        AssistantBlock(kind: .tool, content: "shell_execute", toolStatus: .completed),
        AssistantBlock(kind: .text, content: "Round 2 partial"),
        AssistantBlock(kind: .tool, content: "read_file", toolStatus: .completed),   // completed in the tail: kept
        AssistantBlock(kind: .tool, content: "browser_use", toolStatus: .streaming),
        AssistantBlock(kind: .thinking, content: "…"),
    ])
    clearUncommittedStreamTail(m, committedBlockCount: 2)
    checkEq("committed prefix kept verbatim; tail drops partial text, streaming tool, thinking",
            kinds(m), ["t(Round 1 text (persisted))", "tool:completed", "tool:completed"])

    let pre = ChatMessage(role: .assistant, content: "", blocks: [
        AssistantBlock(kind: .text, content: "Round 1 text (persisted)"),
        AssistantBlock(kind: .tool, content: "shell_execute", toolStatus: .completed),
        AssistantBlock(kind: .text, content: "Round 2 partial"),
    ])
    clearStreamBlocks_preFix(pre)
    checkEq("PRE-d2f282e04: EVERY text block vanished, only the error stayed", kinds(pre), ["tool:completed"])

    // bdc8d0a5f: committedBlockCount lags (error paths never update it).
    let lag = ChatMessage(role: .assistant, content: "", blocks: [
        AssistantBlock(kind: .text, content: "committed text"),
        AssistantBlock(kind: .tool, content: "shell_execute", toolStatus: .completed),
        AssistantBlock(kind: .text, content: "more committed text"),
        AssistantBlock(kind: .tool, content: "read_file", toolStatus: .running),
    ])
    let lagCopy = ChatMessage(role: .assistant, content: "", blocks: lag.blocks.map { AssistantBlock(kind: $0.kind, content: $0.content, toolStatus: $0.toolStatus) })
    clearUncommittedStreamTail(lag, committedBlockCount: 1)          // lagging count
    checkEq("retry with a lagging committedBlockCount keeps committed text and terminal tools",
            kinds(lag), ["t(committed text)", "tool:completed"])
    blindTrim_preFix(lagCopy, committedBlockCount: 1)
    checkEq("PRE-bdc8d0a5f: the blind trim wiped the committed tool and text", kinds(lagCopy), ["t(committed text)"])

    // Degenerate bounds.
    let e = ChatMessage(role: .assistant, content: "", blocks: [AssistantBlock(kind: .text, content: "x")])
    clearUncommittedStreamTail(e, committedBlockCount: 5)
    checkEq("count beyond the array is a no-op", e.blocks.count, 1)
    clearUncommittedStreamTail(e, committedBlockCount: -1)
    checkEq("negative count clamps to 0 (partial text dropped)", e.blocks.count, 0)
}

// MARK: - [5] Early failure survives a session switch

print("\n[5] An early failure (no assistant row) is carried and persisted, so a switch does not lose it")
do {
    let vm = VM()
    vm.messages = [ChatMessage(role: .user, content: "hello")]
    vm.attachError("Bad API key")
    checkEq("a carrier assistant row is appended", vm.messages.count, 2)
    check("the carrier holds the error", vm.messages.last?.error == "Bad API key")
    check("the banner is set too (immediate feedback)", vm.errorMessage == "Bad API key")
    checkEq("the error is persisted", vm.persistedErrorInfo, "Bad API key")

    // Leave and come back: the view model is destroyed; only the DB survives.
    let reloaded = VM.reloadedError(vm.persistedErrorInfo)
    checkEq("reload restores the error from error_info", reloaded, "Bad API key")
    check("a blank persisted value never shows an empty banner", VM.reloadedError("  \n") == nil)

    let pre = VM()
    pre.messages = [ChatMessage(role: .user, content: "hello")]
    pre.attachError_preFix("Bad API key")
    check("PRE-40633da8d: banner only, nothing persisted → lost on switch",
          pre.errorMessage != nil && pre.persistedErrorInfo == nil && pre.messages.count == 1)

    // …and the carrier is exactly what clearSupersededErrors removes on recovery.
    vm.clearSupersededErrors(reason: "retry")
    checkEq("recovery removes the empty carrier", vm.messages.count, 1)
    check("…and clears the persisted error", vm.persistedErrorInfo == nil)
}

// MARK: - [6] Drift guards

print("\n[6] Shipping sources match these ports")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let vmSrc = source("Agent/Chat/AIChatViewModel.swift")
let fb = source("Agent/Chat/AIChatViewModel+Fallback.swift")
let store = source("Agent/Chat/ChatStore.swift")
let persist = source("Agent/Chat/AIChatViewModel+Persistence.swift")
if vmSrc.isEmpty || fb.isEmpty || store.isEmpty || persist.isEmpty {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    func count(_ needle: String, in s: String) -> Int { s.components(separatedBy: needle).count - 1 }
    // Attribution
    let attach = count("if let last = self.messages.last(where: { $0.role == .assistant }) {", in: vmSrc)
    check("error epilogues key on the last ASSISTANT row (\(attach) sites, need ≥5)", attach >= 5)
    check("no epilogue keys on messages.last + role (the queued-bubble trap)",
          !vmSrc.contains("if let last = self.messages.last, last.role == .assistant {\n                        last.error = displayDesc"))
    check("the else branch reports through reportTurnFailure", count("self.reportTurnFailure(displayDesc)", in: vmSrc) >= 5)
    check("reportTurnFailure appends a carrier and persists it",
          vmSrc.contains("let carrier = ChatMessage(role: .assistant, content: \"\", blocks: [])\n        carrier.error = displayDesc\n        messages.append(carrier)\n        Task { await self.persistErrorInfo(displayDesc) }"))
    // Sweep
    check("clearSupersededErrors clears every errored row", vmSrc.contains("for msg in messages where msg.error != nil {"))
    check("…removes only empty carriers", vmSrc.contains("let isEmptyCarrier = msg.role == .assistant\n                && msg.blocks.isEmpty\n                && msg.content.isEmpty\n                && msg.usage == nil"))
    check("…keeps the resume target", vmSrc.contains("guard msg !== keep else { return false }"))
    check("…and clears the persisted copy", vmSrc.contains("Task { await self.persistErrorInfo(nil) }"))
    check("the sweep runs at the top of every loop", vmSrc.contains("clearSupersededErrors(reason: \"agent-loop-start\","))
    check("retry() sweeps before capturing its resume index",
          vmSrc.range(of: "clearSupersededErrors(reason: \"retry\", keeping: lastMsg)")!.lowerBound
          < vmSrc.range(of: "let existingMsgIdx = messages.count - 1", range: vmSrc.range(of: "    func retry() {")!.lowerBound..<vmSrc.endIndex)!.lowerBound)
    check("resume() sweeps too", vmSrc.contains("clearSupersededErrors(reason: \"resume\""))
    // Tail clears
    check("clearUncommittedStreamTail keeps the committed prefix",
          vmSrc.contains("var kept = Array(message.blocks[0..<lowerBound])"))
    check("retry() uses the helper, not a blind trim",
          vmSrc.contains("Self.clearUncommittedStreamTail(lastMsg, committedBlockCount: committedBlockCount)"))
    check("no blind removeSubrange(committedBlockCount...) remains on the retry/resume paths",
          !vmSrc.contains("lastMsg.blocks.removeSubrange(committedBlockCount...)"))
    check("the mid-stream catch uses the helper", count("Self.clearUncommittedStreamTail(messages[msgIdx], committedBlockCount: committedBlockCount)", in: vmSrc) >= 2)
    check("the fallback-until-content loop uses the helper", fb.contains("clearUncommittedStreamTail("))
    // Stop keeps blocks
    check("Stop marks in-flight tools cancelled rather than removing them (Case 1)",
          vmSrc.contains("case .streaming, .running:\n                            block.toolStatus = .cancelled"))
    check("Stop flushes post-tool text into agentHistory", vmSrc.contains("Case 1: flushed"))
    // Persistence
    check("error_info round-trips through the store (updateMessageErrorInfo + persistErrorInfo)",
          store.contains("func updateMessageErrorInfo(") && store.contains("errorInfo") && persist.contains("func persistErrorInfo("))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
