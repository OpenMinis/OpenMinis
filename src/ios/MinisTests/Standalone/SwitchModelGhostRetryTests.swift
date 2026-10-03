// Tests for [T-ios-switch-model-ghost-retry] — switching model mid-conversation
// must stop work still aimed at the PREVIOUS model.
//
// The bug: a turn fails with a 5xx; the user picks a different model from the
// header menu; the new model answers normally — but the gateway log shows the
// OLD model still being retried twice more, and its errors keep popping up over
// the working conversation. Pressing Stop BEFORE switching made it go away,
// which is the tell: `cancel()` tears the in-flight work down and switching
// model did not.
//
// Two survivors kept the old model alive:
//   1. The agent loop (`currentTask`), parked in streamWithAutoRetry's
//      countdown or mid-ladder, holding a provider built from the old entry.
//   2. Title generation, whose detached Task resolves its model ONCE and then
//      retries up to 3 times — with no per-session sub model that resolved
//      model IS the primary one the user just switched away from.
//
// `SessionModelPicker` already posted `.sessionModelBindingChanged` on both
// switch paths; nothing observed it. The fix observes it and cancels the
// model-bound work only.
//
// Standalone (`swift SwitchModelGhostRetryTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func ck(_ l: String, _ ok: Bool) {
    if ok { print("  ✅ \(l)") } else { print("  ❌ \(l)"); failures += 1 }
}
func ckEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Model of the view model's cancellation seams

/// Mirrors the parts of AIChatViewModel this fix touches.
final class VMModel {
    // In-flight work
    var currentTaskAlive = false
    var isProcessing = false
    var autoRetryAttempt = 0
    var autoRetryCountdown = 0
    var userDidCancel = false

    // Title generation
    var isTitleGenerating = false
    var titleGenEpoch: UInt = 0

    // Work that a model switch must NOT touch (only Stop may).
    var subAgentsRunning = 0
    var scheduledTimers = 0
    var queuedDelegations = 0
    var shellCommandRunning = false

    /// The requests that actually reached a provider, in order.
    var requestLog: [String] = []

    // MARK: Mirrors cancelWorkBoundToPreviousModel(reason:)
    func cancelWorkBoundToPreviousModel() {
        let hadLoop = currentTaskAlive
        let hadTitleGen = isTitleGenerating
        guard hadLoop || hadTitleGen else { return }
        if hadLoop {
            userDidCancel = true
            currentTaskAlive = false
            autoRetryAttempt = 0
            autoRetryCountdown = 0
            isProcessing = false
        }
        if hadTitleGen {
            titleGenEpoch &+= 1
            isTitleGenerating = false
        }
    }

    // MARK: Mirrors cancel() — the Stop button
    func cancel() {
        userDidCancel = true
        currentTaskAlive = false
        autoRetryAttempt = 0
        autoRetryCountdown = 0
        isProcessing = false
        titleGenEpoch &+= 1
        isTitleGenerating = false
        // Stop additionally tears down everything the conversation owns.
        subAgentsRunning = 0
        scheduledTimers = 0
        queuedDelegations = 0
        shellCommandRunning = false
    }
}

/// Mirrors streamWithAutoRetry's countdown ladder: between attempts it checks
/// cancellation, and only then issues the next request.
func runRetryLadder(_ vm: VMModel, model: String, attempts: Int,
                    switchAfterAttempt: Int? = nil, newModel: String = "gemini-3.8-flash-high") {
    vm.currentTaskAlive = true
    vm.isProcessing = true
    for attempt in 1...attempts {
        // The cancellation check that Task.cancel() drives.
        if !vm.currentTaskAlive { return }
        vm.requestLog.append("\(model)#\(attempt)")
        vm.autoRetryAttempt = attempt
        if switchAfterAttempt == attempt {
            // User picks a different model from the header menu.
            vm.cancelWorkBoundToPreviousModel()
        }
    }
}

// MARK: - 1. The reported bug, and the fix

print("\n▶️  the reported scenario: 503, then switch model")

// WITHOUT the fix: nothing observes the binding change, the ladder runs on.
let broken = VMModel()
broken.currentTaskAlive = true
broken.isProcessing = true
for attempt in 1...3 {
    broken.requestLog.append("gpt-6-astra#\(attempt)")
    // switching model used to only write the binding — no cancellation at all
}
ckEq("unguarded: the old model is called for every ladder attempt",
     broken.requestLog, ["gpt-6-astra#1", "gpt-6-astra#2", "gpt-6-astra#3"])
ck("unguarded: the loop is still alive after the switch", broken.currentTaskAlive)

// WITH the fix: the switch cancels, so the ladder stops where it stands.
let fixed = VMModel()
runRetryLadder(fixed, model: "gpt-6-astra", attempts: 3, switchAfterAttempt: 1)
ckEq("fixed: the old model is not called again after the switch",
     fixed.requestLog, ["gpt-6-astra#1"])
ck("fixed: the loop is torn down", !fixed.currentTaskAlive)
ck("fixed: isProcessing cleared so the composer leaves the retry state",
   !fixed.isProcessing)

print("\n▶️  the stale retry UI is cleared (the visible ghost)")
let ui = VMModel()
ui.currentTaskAlive = true
ui.autoRetryAttempt = 2
ui.autoRetryCountdown = 5
ui.cancelWorkBoundToPreviousModel()
ckEq("autoRetryAttempt reset", ui.autoRetryAttempt, 0)
ckEq("autoRetryCountdown reset", ui.autoRetryCountdown, 0)

print("\n▶️  the cancellation is marked user-initiated")
// The loop's error handler keys off this to mark the message interrupted and
// resumable, instead of surfacing the OLD model's error the user moved on from.
let marked = VMModel()
marked.currentTaskAlive = true
marked.cancelWorkBoundToPreviousModel()
ck("userDidCancel set", marked.userDidCancel)

// MARK: - 2. Title generation stops calling the abandoned model

print("\n▶️  title generation abandons its captured (old) model")

/// Mirrors the detached title Task: it captures the epoch once, then re-checks
/// before every candidate — the 1.5s pause between candidates is exactly the
/// window in which the user switches.
func runTitleGen(_ vm: VMModel, model: String, candidates: Int,
                 switchBeforeCandidate: Int? = nil) {
    vm.isTitleGenerating = true
    let captured = vm.titleGenEpoch
    for idx in 1...candidates {
        if switchBeforeCandidate == idx { vm.cancelWorkBoundToPreviousModel() }
        guard vm.titleGenEpoch == captured else { return }   // superseded()
        vm.requestLog.append("title:\(model)#\(idx)")
    }
}

let tg = VMModel()
runTitleGen(tg, model: "gpt-6-astra", candidates: 3, switchBeforeCandidate: 2)
ckEq("only the candidate before the switch ran", tg.requestLog, ["title:gpt-6-astra#1"])
ck("isTitleGenerating cleared", !tg.isTitleGenerating)

print("\n▶️  an untouched title run still completes all its candidates")
let tgOK = VMModel()
runTitleGen(tgOK, model: "gpt-6-astra", candidates: 3)
ckEq("no switch ⇒ nothing is skipped", tgOK.requestLog.count, 3)

print("\n▶️  the epoch is a token, not a bool — title gen can legitimately re-run")
// Title generation re-runs as more turns arrive. A bool would either block the
// new run or be reset by it; the token lets the OLD run die and a NEW one live.
let reRun = VMModel()
reRun.isTitleGenerating = true
let firstEpoch = reRun.titleGenEpoch
reRun.cancelWorkBoundToPreviousModel()          // switch kills run #1
ck("run #1 is superseded", reRun.titleGenEpoch != firstEpoch)
// A fresh run captures the NEW epoch and proceeds.
runTitleGen(reRun, model: "gemini-3.8-flash-high", candidates: 2)
ckEq("run #2 completes under the new model", reRun.requestLog,
     ["title:gemini-3.8-flash-high#1", "title:gemini-3.8-flash-high#2"])

// MARK: - 3. A model switch is NOT Stop

print("\n▶️  switching model leaves everything not addressed to the old model alone")
// This is the line between the two seams. Stop means "stop this conversation";
// switching model means "use a different model from here on". Sub agents,
// scheduled timers and a running shell command are not aimed at the old model.
let keep = VMModel()
keep.currentTaskAlive = true
keep.subAgentsRunning = 3
keep.scheduledTimers = 2
keep.queuedDelegations = 4
keep.shellCommandRunning = true
keep.cancelWorkBoundToPreviousModel()
ckEq("sub agents survive", keep.subAgentsRunning, 3)
ckEq("scheduled timers survive", keep.scheduledTimers, 2)
ckEq("queued delegations survive", keep.queuedDelegations, 4)
ck("a running shell command survives", keep.shellCommandRunning)
ck("but the model-bound loop is gone", !keep.currentTaskAlive)

print("\n▶️  Stop, by contrast, takes all of it down (unchanged behaviour)")
let stopped = VMModel()
stopped.currentTaskAlive = true
stopped.subAgentsRunning = 3
stopped.scheduledTimers = 2
stopped.queuedDelegations = 4
stopped.shellCommandRunning = true
stopped.cancel()
ckEq("sub agents cancelled", stopped.subAgentsRunning, 0)
ckEq("timers cancelled", stopped.scheduledTimers, 0)
ckEq("delegations dropped", stopped.queuedDelegations, 0)
ck("shell command stopped", !stopped.shellCommandRunning)

// MARK: - 4. No-op when nothing is running

print("\n▶️  switching model on an idle conversation does nothing")
let idle = VMModel()
idle.cancelWorkBoundToPreviousModel()
ck("userDidCancel NOT set on an idle switch", !idle.userDidCancel)
ckEq("epoch untouched, so a later title run is not spuriously cancelled",
     idle.titleGenEpoch, 0)
ck("isProcessing untouched", !idle.isProcessing)

// MARK: - 5. Source invariants

print("\n▶️  source invariants")

func read(_ p: String) -> String? { try? String(contentsOfFile: p, encoding: .utf8) }
guard let vm = read("../../Agent/Chat/AIChatViewModel.swift"),
      let titleGen = read("../../Agent/Chat/AIChatViewModel+TitleGeneration.swift"),
      let picker = read("../../Views/Providers/SessionModelPicker.swift") else {
    print("  ❌ could not read sources"); failures += 1; exit(1)
}

ck("the cancellation seam exists",
   vm.contains("func cancelWorkBoundToPreviousModel(reason: String)"))
ck("it is wired to the binding-changed notification",
   vm.contains("forName: .sessionModelBindingChanged"))
ck("the observer is torn down", vm.contains("modelBindingChangeObserver {"))
// It must filter by session: the notification is global, and a switch in
// another conversation must not cancel this one.
ck("the observer filters on sessionId",
   vm.contains("notification.userInfo?[\"sessionId\"] as? String"))
// The seam must NOT reach the job registry — that is Stop's job.
let seam = vm.range(of: "func cancelWorkBoundToPreviousModel(reason: String)").map {
    String(vm[$0.lowerBound...].prefix(2200))
} ?? ""
ck("the seam does not cancel sub agents", !seam.contains("AgentJobRegistry"))
ck("the seam does not stop shell commands", !seam.contains("stopCurrentCommand"))
ck("but it does cancel the loop", seam.contains("currentTask?.cancel()"))
ck("and bumps the title-gen token", seam.contains("titleGenEpoch &+= 1"))

// Title generation must re-check the token, not just read it once.
ck("title gen captures the epoch", titleGen.contains("let genEpoch = titleGenEpoch"))
ck("title gen defines a superseded() check", titleGen.contains("func superseded() -> Bool"))
ck("title gen re-checks before EVERY candidate",
   titleGen.contains("abandoned at candidate"))

// Both switch paths must carry the session id, or the observer's filter
// silently drops the event and the bug returns for that path.
let posts = picker.components(separatedBy: "name: .sessionModelBindingChanged").count - 1
ckEq("both picker paths post the notification", posts, 2)
ck("the group path carries sessionId",
   picker.contains("userInfo: [\"groupId\": group.id, \"sessionId\": sid]"))
ck("the entry path carries sessionId",
   picker.contains("var info: [String: Any] = [\"sessionId\": sid]"))

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All switch-model ghost-retry tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
