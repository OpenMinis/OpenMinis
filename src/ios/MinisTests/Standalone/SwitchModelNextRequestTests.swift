// Tests for [T-ios-switch-model-next-request] — switching model while a turn is
// running must NOT interrupt it; the new model applies from the next request.
//
// Reported: picking another model mid-task interrupted the tool call and the
// reasoning already in flight. Expected: a silent switch that takes effect on
// the next request.
//
// Cause: `cancelWorkBoundToPreviousModel` (T-ios-switch-model-ghost-retry)
// cancelled `currentTask` whenever one existed. That seam was written for a
// failing turn whose retry ladder kept calling the abandoned model; applied
// unconditionally it also killed healthy turns mid-stream / mid-tool.
//
// Fix: cancel only while the retry ladder is live; otherwise set
// `pendingModelSwitch`, and let the agent loop swap provider / entry / model /
// model prompt fragments at the top of its next iteration — i.e. right before
// its next LLM request.
//
// Standalone (`swift SwitchModelNextRequestTests.swift`) like its neighbours.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Model of the seam (mirrors cancelWorkBoundToPreviousModel)

struct VM {
    var hasLoop = false
    var autoRetryAttempt = 0
    var autoRetryCountdown = 0
    var isTitleGenerating = false
    var pendingModelSwitch = false
    var loopCancelled = false
    var titleEpoch = 0

    mutating func switchModel() {
        let retryLadderLive = autoRetryAttempt > 0 || autoRetryCountdown > 0
        let hadLoop = hasLoop && retryLadderLive
        if hasLoop && !retryLadderLive { pendingModelSwitch = true }
        guard hadLoop || isTitleGenerating else { return }
        if hadLoop {
            pendingModelSwitch = false
            loopCancelled = true
            hasLoop = false
            autoRetryAttempt = 0; autoRetryCountdown = 0
        }
        if isTitleGenerating { titleEpoch += 1; isTitleGenerating = false }
    }
}

print("\n▶️  the reported case: switch while a reply streams / a tool runs")
var streaming = VM(hasLoop: true)
streaming.switchModel()
check("the running turn is NOT cancelled", streaming.loopCancelled, false)
check("the loop keeps running", streaming.hasLoop)
check("a switch is queued for the next request", streaming.pendingModelSwitch)

print("\n▶️  the ghost-retry case still cancels (T-ios-switch-model-ghost-retry)")
var retrying = VM(hasLoop: true, autoRetryAttempt: 1, autoRetryCountdown: 4)
retrying.switchModel()
check("a turn stuck in the retry ladder IS cancelled", retrying.loopCancelled)
check("no pending switch left behind on a cancelled loop", retrying.pendingModelSwitch, false)
check("retry UI cleared", retrying.autoRetryAttempt == 0 && retrying.autoRetryCountdown == 0)

var countdownOnly = VM(hasLoop: true, autoRetryCountdown: 2)
countdownOnly.switchModel()
check("countdown alone counts as a live ladder", countdownOnly.loopCancelled)

print("\n▶️  idle conversation")
var idle = VM()
idle.switchModel()
check("nothing cancelled", idle.loopCancelled, false)
check("nothing queued (next turn resolves the binding anyway)", idle.pendingModelSwitch, false)

print("\n▶️  title generation is still abandoned on a healthy-turn switch")
var both = VM(hasLoop: true, isTitleGenerating: true)
both.switchModel()
check("title gen stopped", both.titleEpoch == 1 && !both.isTitleGenerating)
check("but the turn survives", both.loopCancelled == false && both.pendingModelSwitch)

// MARK: - Model of the loop-head swap

struct Loop {
    var activeEntryId: String
    var providerFor: String
    var pendingModelSwitch = false
    var appliedAtIteration: Int? = nil
    var boundEntry: String       // what resolveCurrentEntry() returns

    mutating func iterate(_ i: Int) {
        if pendingModelSwitch {
            pendingModelSwitch = false
            if boundEntry != activeEntryId {
                providerFor = boundEntry
                activeEntryId = boundEntry
                appliedAtIteration = i
            }
        }
    }
}

print("\n▶️  the switch lands on the NEXT request, not mid-request and not next turn")
var loop = Loop(activeEntryId: "old", providerFor: "old", boundEntry: "old")
loop.iterate(1)                                   // request 1 on old model
check("request 1 used the old model", loop.providerFor == "old")
loop.boundEntry = "new"; loop.pendingModelSwitch = true   // user switches during tool run
loop.iterate(2)                                   // request 2 (after tool result)
check("request 2 uses the new model", loop.providerFor == "new")
check("applied at iteration 2", loop.appliedAtIteration == 2)
check("flag consumed", loop.pendingModelSwitch == false)

print("\n▶️  a binding rewrite WITHOUT the flag (group fallback) is not a user switch")
var fb = Loop(activeEntryId: "fallbackMember", providerFor: "fallbackMember", boundEntry: "groupPrimary")
fb.iterate(3)
check("fallback entry kept", fb.providerFor == "fallbackMember")

print("\n▶️  switching back to the model already in use is a no-op")
var same = Loop(activeEntryId: "a", providerFor: "a", pendingModelSwitch: true, boundEntry: "a")
same.iterate(1)
check("no provider rebuild", same.appliedAtIteration == nil)

// MARK: - Prompt fragment swap (mirrors the loop-head code)

func fragments(cap: String?, behavior: String?) -> String {
    var s = ""
    if let cap { s += "\n\n" + cap }
    if let behavior { s += "\n\n" + behavior }
    return s
}
func swap(prompt: String, base: String, old: String, new: String) -> String? {
    let oldPrefix = base + old
    guard prompt.hasPrefix(oldPrefix) else { return nil }
    return base + new + String(prompt.dropFirst(oldPrefix.count))
}

print("\n▶️  only the model fragments change; skills / MCP / memory survive")
let base = "BASE"
let oldF = fragments(cap: "CAP-OLD", behavior: "BEHAV-OLD")
let newF = fragments(cap: "CAP-NEW", behavior: nil)
let tail = "\n\nSKILLS\n\nMCP\n\nMEMORY" + "STATUS"
let before = base + oldF + tail
let after = swap(prompt: before, base: base, old: oldF, new: newF)
check("swap succeeded", after != nil)
check("new capability fragment present", after?.contains("CAP-NEW") == true)
check("old fragments gone", after?.contains("OLD") == false)
check("skills/MCP/memory tail byte-identical", after?.hasSuffix(tail) == true)
check("an old model with NO fragments swaps too",
      swap(prompt: base + tail, base: base, old: "", new: newF) == base + newF + tail)
check("unexpected prompt shape is left alone (nil = keep)",
      swap(prompt: "OTHER" + tail, base: base, old: oldF, new: newF) == nil)

// MARK: - Source invariants

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    return ""
}
let vm = source("Agent/Chat/AIChatViewModel.swift")
print("\n▶️  source invariants")
if vm.isEmpty { print("  ⚠️  AIChatViewModel.swift not found — skipped") } else {
    check("seam gates cancellation on the retry ladder",
          vm.contains("let retryLadderLive = autoRetryAttempt > 0 || autoRetryCountdown > 0"))
    check("healthy turn sets the pending flag", vm.contains("if hasLoop && !retryLadderLive {\n            pendingModelSwitch = true"))
    check("loop head consumes the flag", vm.contains("if pendingModelSwitch {\n                pendingModelSwitch = false\n                if let newEntry = resolveCurrentEntry()"))
    check("activeModel is mutable so the next request gets the new limits",
          vm.contains("var activeModel = ProviderConfigStore.shared.entry(for: entry.id)?.model ?? selectedModel"))
    // The reset must precede the turn's resolve + `await makeAgentProvider`,
    // or a switch landing during that await is cleared and lost.
    let resetAt = vm.range(of: "model.\n        pendingModelSwitch = false\n\n        // Resolve provider from ProviderConfigStore")
    check("flag reset at turn start, BEFORE the entry is resolved", resetAt != nil)
    check("fragment helper exists", vm.contains("static func modelPromptFragments(_ model: LLMModel) -> String"))
}

print("\n" + String(repeating: "─", count: 60))
print(failures == 0 ? "✅ All switch-model next-request tests passed" : "❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
