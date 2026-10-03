// Tests for [T-programmatic-prompt-no-composer] — a prompt nobody typed must
// never travel through `inputText`, the composer's two-way binding.
//
// Standalone (`swift ProgrammaticPromptComposerTests.swift`) like its
// neighbours: the MinisTests target has a pre-existing compile break, and
// AIChatViewModel cannot be instantiated headlessly. A minimal model of the
// composer + send/enqueue contract is reproduced here, and section [5]
// re-reads the shipping sources so the model cannot drift from them.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Model of the composer + send path

final class VMModel {
    /// The composer binding. Every write is counted: the real one is @Published
    /// with a didSet driving the slash/mention menus, so an extra write is a
    /// visible UI event, not a no-op.
    var inputText = "" { didSet { inputWrites += 1 } }
    var inputWrites = 0
    var attachments: [String] = []
    var isProcessing = false
    var sent: [String] = []
    var queued: [String] = []

    var pendingSendText: String?
    var pendingSendIsFromComposer = false
    var needsCompact = false

    /// Mirrors send(overrideText:).
    func send(overrideText: String? = nil) {
        let usingComposer = overrideText == nil
        let text = (overrideText ?? inputText).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !attachments.isEmpty, !isProcessing else { return }
        if needsCompact {
            pendingSendText = text
            pendingSendIsFromComposer = usingComposer
            if usingComposer { inputText = "" }
            attachments = []
            return
        }
        if usingComposer { inputText = "" }
        attachments = []
        isProcessing = true
        sent.append(text)
    }

    /// Mirrors enqueuePrompt(overrideText:).
    func enqueuePrompt(overrideText: String? = nil) {
        let usingComposer = overrideText == nil
        let text = (overrideText ?? inputText).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !attachments.isEmpty, isProcessing else { return }
        queued.append(text)
        if usingComposer { inputText = "" }
        attachments = []
    }

    /// Mirrors cancelCompactBeforeSend().
    func cancelCompactBeforeSend() {
        if pendingSendIsFromComposer { inputText = pendingSendText ?? "" }
        pendingSendText = nil
        pendingSendIsFromComposer = false
    }

    /// Mirrors submitProgrammaticPrompt's hand-off.
    @discardableResult
    func submitProgrammatic(_ text: String) -> String {
        if isProcessing { enqueuePrompt(overrideText: text); return "queued" }
        send(overrideText: text)
        return isProcessing ? "sent" : "rejected"
    }
}

let draft = "我正在写的半句话"

print("\n[1] An idle session: a job callback does not touch the draft")
do {
    let vm = VMModel()
    vm.inputText = draft
    let writesAfterTyping = vm.inputWrites
    checkEq("submit reports sent", vm.submitProgrammatic("job result: build ok"), "sent")
    checkEq("the job's text is what was sent", vm.sent, ["job result: build ok"])
    checkEq("the draft is intact", vm.inputText, draft)
    checkEq("the composer was not written at all", vm.inputWrites, writesAfterTyping)
}

print("\n[2] A busy session: the callback queues without touching the draft")
do {
    let vm = VMModel()
    vm.inputText = draft
    vm.isProcessing = true
    let writesAfterTyping = vm.inputWrites
    checkEq("submit reports queued", vm.submitProgrammatic("scheduled: 10am standup"), "queued")
    checkEq("the job's text is what was queued", vm.queued, ["scheduled: 10am standup"])
    checkEq("the draft is intact", vm.inputText, draft)
    checkEq("the composer was not written at all", vm.inputWrites, writesAfterTyping)
}

print("\n[3] The user's own send still behaves exactly as before")
do {
    let vm = VMModel()
    vm.inputText = "hello"
    vm.send()
    checkEq("their text is sent", vm.sent, ["hello"])
    checkEq("and the composer IS cleared", vm.inputText, "")

    let vm2 = VMModel()
    vm2.isProcessing = true
    vm2.inputText = "queued by me"
    vm2.enqueuePrompt()
    checkEq("their queued text is queued", vm2.queued, ["queued by me"])
    checkEq("and the composer IS cleared", vm2.inputText, "")
}

print("\n[4] The compact-before-send holding area keeps provenance")
do {
    // A programmatic prompt parked for compaction, then the user cancels.
    let vm = VMModel()
    vm.inputText = draft
    vm.needsCompact = true
    vm.submitProgrammatic("job: summarize the log")
    checkEq("the job's text is parked", vm.pendingSendText, "job: summarize the log")
    check("parked text is marked NOT from the composer", vm.pendingSendIsFromComposer, false)
    checkEq("draft survived the park", vm.inputText, draft)
    vm.cancelCompactBeforeSend()
    checkEq("cancelling does NOT pour the job's text into the composer", vm.inputText, draft)

    // The user's own text parked the same way IS restored on cancel.
    let vm2 = VMModel()
    vm2.inputText = "my long question"
    vm2.needsCompact = true
    vm2.send()
    check("parked text is marked from the composer", vm2.pendingSendIsFromComposer, true)
    checkEq("the composer was emptied while parked", vm2.inputText, "")
    vm2.cancelCompactBeforeSend()
    checkEq("cancelling restores THEIR text", vm2.inputText, "my long question")
}

print("\n[5] Shipping sources match these assumptions")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let vmSrc = source("Agent/Chat/AIChatViewModel.swift")
let miscSrc = source("Agent/Chat/AIChatViewModel+Misc.swift")
let progSrc = source("Agent/Chat/AIChatViewModel+ProgrammaticPrompt.swift")
let compSrc = source("Agent/Chat/AIChatViewModel+Compaction.swift")

if vmSrc.isEmpty || progSrc.isEmpty { print("  ⏭  sources not readable from this sandbox") } else {
    check("send() takes an optional overrideText",
          vmSrc.contains("func send(overrideText: String? = nil) {"))
    check("…and derives usingComposer from it",
          vmSrc.contains("let usingComposer = overrideText == nil"))
    check("…and reads the draft only as the fallback",
          vmSrc.contains("let text = (overrideText ?? inputText).trimmingCharacters"))
    check("enqueuePrompt() takes one too",
          miscSrc.contains("overrideText: String? = nil"))
    check("…and reads the draft only as the fallback",
          miscSrc.contains("let text = (overrideText ?? inputText).trimmingCharacters"))

    // The whole point: no unguarded clear survives on the send path. Scoped to
    // send() itself — `cancelEdit()` also clears the composer, and should: it
    // is the user tapping cancel on their own edit, with no programmatic
    // caller. A repo-wide match would be asserting the wrong invariant.
    let sendBody: String = {
        guard let start = vmSrc.range(of: "func send(overrideText: String? = nil) {"),
              let end = vmSrc.range(of: "\n    func ", range: start.upperBound..<vmSrc.endIndex)
        else { return "" }
        return String(vmSrc[start.upperBound..<end.lowerBound])
    }()
    check("send()'s body was located", !sendBody.isEmpty)
    let clears = sendBody.components(separatedBy: "\n").filter {
        $0.contains("inputText = \"\"") && !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//")
    }
    check("every inputText clear inside send() is composer-guarded (\(clears.count) found)",
          clears.count == 4 && clears.allSatisfy { $0.contains("if usingComposer") })
    let miscClears = miscSrc.components(separatedBy: "\n").filter {
        $0.contains("inputText = \"\"") && !$0.contains("//")
    }
    check("…and in enqueuePrompt (\(miscClears.count) found)",
          !miscClears.isEmpty && miscClears.allSatisfy { $0.contains("if usingComposer") })

    check("the programmatic entry point no longer assigns the composer",
          !progSrc.contains("\n        inputText = text\n"))
    check("it forwards to send(overrideText:)",
          progSrc.contains("send(overrideText: text)"))
    check("it forwards to enqueuePrompt(overrideText:)",
          progSrc.contains("enqueuePrompt(silent: silent, deferUntilIdle: gentle, overrideText: text)"))

    check("parked text records its provenance",
          vmSrc.contains("pendingSendIsFromComposer = usingComposer"))
    check("cancel restores only the user's own text",
          compSrc.contains("if pendingSendIsFromComposer {"))

    // The three App Intents that bypassed the entry point entirely.
    for f in ["Agent/Intents/FollowUpSessionIntent.swift",
              "Agent/Intents/AskMinisIntent.swift",
              "Agent/Intents/QuickTaskIntent.swift"] {
        let s = source(f)
        let name = (f as NSString).lastPathComponent
        check("\(name) no longer writes vm.inputText",
              !s.contains("vm.inputText ="))
        check("\(name) sends via overrideText", s.contains("vm.send(overrideText:"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
