// Edge cases around a model switch that lands while a turn is running, and the
// group fallback that runs inside the same turn.
//
// Guards: 05d654128 (T-ios-switch-model-ghost-retry), 86a045284
// (T-ios-switch-model-next-request) and the group-fallback binding rewrite in
// AIChatViewModel+Fallback.swift. Extends SwitchModelGhostRetryTests and
// SwitchModelNextRequestTests (which pin the seam and the loop-head swap); this
// file covers the interactions those two do not:
//
//   A. Turn-start race: the flag is reset AFTER the entry is resolved and after
//      an `await makeAgentProvider`. A switch that lands in that window is
//      dropped for the whole turn.                                  (BUG today)
//   B. A group fallback that succeeds while a user switch is pending rewrites
//      the session binding to the fallback member, erasing the user's pick; the
//      loop head then resolves the fallback entry and the switch is a no-op.
//                                                                   (BUG today)
//   C. Group-fallback prompt rebuild for a helper (delegate_task child) keys
//      skills / MCP on the CHILD session id, while setup keys them on the
//      PARENT — a fallback silently changes the helper's skill set. (BUG today)
//   D. Invariants the next-request switch depends on (all pass today):
//      - the ladder counter is cleared as soon as a stream opens, so a switch
//        during a HEALTHY retried stream is not mistaken for a ghost retry;
//      - the picker writes the binding BEFORE posting the notification;
//      - setBinding bumps configRevision, the resolve-cache key includes it, so
//        the loop head cannot read a cached pre-switch entry;
//      - every system-prompt builder starts with base + capability + behaviour,
//        the prefix the loop-head swap relies on.
//
// Run: cd src/ios/MinisTests/Standalone && swift SwitchModelMidTurnEdgeTests.swift

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Model: turn start vs. a switch arriving during `await makeAgentProvider`

/// Steps of runAgentLoopCore's setup, in the order the shipping code runs them.
enum SetupStep { case resolveEntry, awaitProvider, resetFlag }

/// Returns the entry the turn's FIRST request uses, and the entry its SECOND
/// request uses, when the user switches from "old" to "new" while the setup is
/// suspended at `awaitProvider`.
func simulate(order: [SetupStep]) -> (first: String, second: String) {
    var binding = "old"
    var flag = false
    var active = ""
    for step in order {
        switch step {
        case .resolveEntry: active = binding
        case .awaitProvider:
            // The switch lands here (the only suspension point in the window).
            binding = "new"; flag = true
        case .resetFlag: flag = false
        }
    }
    let first = active
    // Loop head before request 2.
    if flag { flag = false; if binding != active { active = binding } }
    return (first, active)
}

print("\n▶️  A. a switch during the turn-start await must not be lost")
let shipping = simulate(order: [.resolveEntry, .awaitProvider, .resetFlag])
check("[model, shipping order] second request still on the OLD model (the bug)", shipping.second == "old")
let fixed = simulate(order: [.resetFlag, .resolveEntry, .awaitProvider])
check("[model, fixed order] reset before resolve → second request on the NEW model", fixed.second == "new")
check("[model, fixed order] first request stays on the entry resolved at start", fixed.first == "old")

// MARK: - Model: fallback success while a user switch is pending

struct Session {
    var binding: String          // what the picker / fallback wrote
    var pendingModelSwitch = false
    var activeEntry: String

    /// Group fallback moved the in-flight request to `member`.
    mutating func fallbackSucceeded(on member: String, guardPending: Bool) {
        activeEntry = member
        if guardPending && pendingModelSwitch { return }   // user's pick wins
        binding = "group:" + member
    }
    mutating func loopHead() {
        guard pendingModelSwitch else { return }
        pendingModelSwitch = false
        let resolved = binding.hasPrefix("group:") ? String(binding.dropFirst(6)) : binding
        if resolved != activeEntry { activeEntry = resolved }
    }
}

print("\n▶️  B. group fallback must not overwrite a pick the user made mid-request")
var unguarded = Session(binding: "group:A", activeEntry: "A")
unguarded.binding = "X"; unguarded.pendingModelSwitch = true      // user picks X
unguarded.fallbackSucceeded(on: "B", guardPending: false)         // A 429 → B
unguarded.loopHead()
check("[model, shipping] user's pick X is erased from the binding", unguarded.binding == "group:B")
check("[model, shipping] next request stays on fallback B, not X", unguarded.activeEntry == "B")

var guarded = Session(binding: "group:A", activeEntry: "A")
guarded.binding = "X"; guarded.pendingModelSwitch = true
guarded.fallbackSucceeded(on: "B", guardPending: true)
check("[model, fixed] this request is still served by B", guarded.activeEntry == "B")
guarded.loopHead()
check("[model, fixed] binding keeps the user's pick", guarded.binding == "X")
check("[model, fixed] next request moves to X", guarded.activeEntry == "X")

var noSwitch = Session(binding: "group:A", activeEntry: "A")
noSwitch.fallbackSucceeded(on: "B", guardPending: true)
check("[model, fixed] without a pending switch the fallback still persists (sticky fallback kept)",
      noSwitch.binding == "group:B")

// MARK: - Source

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
/// Body of `func <name>` up to the next top-level-ish `func ` declaration.
func body(of marker: String, in text: String) -> String? {
    guard let start = text.range(of: marker) else { return nil }
    let rest = text[start.upperBound...]
    let end = rest.range(of: "\n    func ") ?? rest.range(of: "\n    private func ")
    return String(rest[..<(end?.lowerBound ?? rest.endIndex)])
}
func offset(_ needle: String, in hay: String) -> Int? {
    hay.range(of: needle).map { hay.distance(from: hay.startIndex, to: $0.lowerBound) }
}
/// Strip `//` comment lines so prose cannot satisfy a code check.
func code(_ s: String) -> String {
    s.split(separator: "\n", omittingEmptySubsequences: false)
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") && !$0.trimmingCharacters(in: .whitespaces).hasPrefix("///") }
        .joined(separator: "\n")
}

let vm = source("Agent/Chat/AIChatViewModel.swift")
let fb = source("Agent/Chat/AIChatViewModel+Fallback.swift")
let factory = source("Agent/Chat/AIChatViewModel+ProviderFactory.swift")
let store = source("Providers/ProviderConfigStore.swift")
let picker = source("Views/Providers/SessionModelPicker.swift")

guard !vm.isEmpty, !fb.isEmpty, !factory.isEmpty, !store.isEmpty, !picker.isEmpty else {
    print("  ❌ shipping sources not found — run from src/ios/MinisTests/Standalone")
    exit(1)
}

print("\n▶️  A. source: turn-start reset happens before the entry is resolved")
if let core = body(of: "private func runAgentLoopCore(", in: vm).map(code) {
    let resolveAt = offset("guard let entry = resolveCurrentEntry() else", in: core)
    let resetAt = offset("pendingModelSwitch = false", in: core)
    check("runAgentLoopCore resolves the entry", resolveAt != nil)
    check("runAgentLoopCore resets pendingModelSwitch", resetAt != nil)
    if let r = resolveAt, let z = resetAt {
        check("reset precedes resolveCurrentEntry (else a switch during `await makeAgentProvider` is dropped)", z < r)
    }
} else { check("runAgentLoopCore found", false) }

print("\n▶️  B. source: every fallback binding rewrite yields to a pending user switch")
let fbCode = code(fb)
let bindingWrites = fbCode.components(separatedBy: "ProviderConfigStore.shared.setBinding(binding, for: sid)").count - 1
check("fallback file still rewrites the binding (sticky fallback) — found \(bindingWrites) site(s)", bindingWrites >= 3)
// Each write must be preceded, within its enclosing `if`, by a pendingModelSwitch check.
var unguardedSites = 0
var searchFrom = fbCode.startIndex
while let hit = fbCode.range(of: "ProviderConfigStore.shared.setBinding(binding, for: sid)", range: searchFrom..<fbCode.endIndex) {
    let windowStart = fbCode.index(hit.lowerBound, offsetBy: -900, limitedBy: fbCode.startIndex) ?? fbCode.startIndex
    let window = fbCode[windowStart..<hit.lowerBound]
    if !window.contains("pendingModelSwitch") { unguardedSites += 1 }
    searchFrom = hit.upperBound
}
check("no fallback setBinding ignores pendingModelSwitch (unguarded: \(unguardedSites))", unguardedSites == 0)

print("\n▶️  C. source: fallback prompt rebuild keys skills/MCP like setup does (helper → parent session)")
if let apply = body(of: "func applyFallbackSwitch()", in: vm).map(code) {
    check("setup keys skills on helperConfig?.parentSessionId ?? sessionId",
          vm.contains("if let sid = helperConfig?.parentSessionId ?? sessionId,\n           let skillFragment = SkillStore.shared.skillPromptFragment(for: sid)"))
    check("applyFallbackSwitch uses the same key for skills/MCP",
          apply.contains("helperConfig?.parentSessionId ?? sessionId"))
} else { check("applyFallbackSwitch found", false) }

print("\n▶️  D. invariants the next-request switch depends on")
if let retry = body(of: "func streamWithAutoRetry(", in: fb).map(code) {
    let openAt = offset("try await currentProvider.streamAgentMessage(", in: retry)
    let resetAt = offset("self.autoRetryAttempt = 0\n                return stream", in: retry)
    check("streamWithAutoRetry clears autoRetryAttempt the moment the stream opens",
          openAt != nil && resetAt != nil && openAt! < resetAt!)
} else { check("streamWithAutoRetry found", false) }

for fn in ["private func bindToGroup(", "private func bindToEntry("] {
    if let b = body(of: fn, in: picker) {
        let set = offset("store.setBinding(binding, for: sid)", in: b)
        let post = offset("name: .sessionModelBindingChanged", in: b)
        check("\(fn) writes the binding before posting the switch",
              set != nil && post != nil && set! < post!)
    } else { check("\(fn) found", false) }
}

check("setBinding persists through save()",
      store.contains("func setBinding(_ binding: SessionModelBinding, for sessionId: String) {\n        config.sessionBindings[sessionId] = binding\n        save()"))
check("save() bumps configRevision", store.contains("configRevision &+= 1"))
check("resolve cache key includes configRevision",
      factory.contains("configRevision: store.configRevision"))

let prefixBuilders = [
    ("loop setup", vm, "var userSystemPrompt = baseSystemPrompt"),
    ("applyFallbackSwitch", vm, "userSystemPrompt = baseSystemPrompt\n                if let capFragment = newEntry.model.capabilityPromptFragment"),
    ("streamWithGroupFallback rebuild", fb, "var rebuiltPrompt = baseSystemPrompt\n                if let capFragment = nextEntry.model.capabilityPromptFragment"),
]
for (name, text, marker) in prefixBuilders {
    check("\(name) starts the prompt with base + capability fragment", text.contains(marker))
}
check("loop head swap uses the same fragment helper order (capability, then behaviour)",
      vm.contains("if let cap = model.capabilityPromptFragment { s += \"\\n\\n\" + cap }\n        if let behavior = model.agentBehaviorPromptFragment { s += \"\\n\\n\" + behavior }"))

print("\n" + String(repeating: "─", count: 60))
print(failures == 0 ? "✅ All switch-model mid-turn edge tests passed" : "❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
