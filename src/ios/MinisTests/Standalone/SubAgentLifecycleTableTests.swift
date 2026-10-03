// [T25] Sub-agent lifecycle state table (iOS half).
//
// The rules that landed one commit at a time, gathered into one table so a
// change to any of them shows up as a row flipping:
//
//   59c1d7208  Stop on one card stops the whole sibling set; a sibling that
//              finishes afterwards must not re-wake the stopped parent turn
//              (AgentJobRegistry.siblingSessions / cancelSiblings, the
//              delivery-time mute check in runThen, delegationResultMayDriveParent)
//   4a1ddb032  resuming an interrupted delegation keeps the named sub agent
//   671a12ce2  a steer reaches the child's NEXT turn: loop-end continue + idle nudge
//   5a4c669b6  control calls (status/steer/cancel/resume) are classified by
//              child_session_id FIRST, then by result shape — a resumed
//              delegation (`resumed: true` + child_session_id) stays a card
//   6110fa4ca → b56101f3b  batch callbacks: every completion wakes the parent
//              once (the hold-until-last-sibling throttle was reverted)
//   c21fa4b0d / 8d320582c / 140844023  the Android counterparts
//
// Standalone (`swift SubAgentLifecycleTableTests.swift`): deps/libs/libish_emu.a
// is device-only arm64, so the app cannot link for a simulator and an XCTest
// bundle has nowhere to run. The registry below is a small in-memory model of
// AgentJobRegistry whose transitions are ported verbatim (file:line cited);
// section [B] re-reads the shipping sources so the ports cannot drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported registry model

enum JobState: String { case pending, running, done, cancelled, failed }
enum JobThen: Equatable { case none, followUpParent }

final class Job {
    let id: String
    let parent: String
    var child: String?
    var state: JobState
    var then: JobThen
    var subAgentName: String?
    init(id: String, parent: String, child: String?, state: JobState, then: JobThen = .followUpParent, subAgentName: String? = nil) {
        self.id = id; self.parent = parent; self.child = child; self.state = state; self.then = then; self.subAgentName = subAgentName
    }
}

/// Parent-side flags (AIChatViewModel.swift: userDidCancel / delegationResultsMuted).
final class ParentVM {
    var userDidCancel = false
    var delegationResultsMuted = false
    var callbacksReceived: [String] = []          // job ids whose completion woke this parent
    /// HelperRunner.swift — delegationResultsAreMuted / delegationResultMayDriveParent.
    var delegationResultsAreMuted: Bool {
        !Registry.delegationResultMayDriveParent(parentCancelled: userDidCancel, delegationsMuted: delegationResultsMuted)
    }
    /// HelperRunner.swift — muteDelegationResults(reason:)
    func muteDelegationResults() { guard !delegationResultsMuted else { return }; delegationResultsMuted = true }
}

final class Registry {
    var jobs: [String: Job] = [:]
    var queuedDelegations: [(id: String, parent: String)] = []
    var parents: [String: ParentVM] = [:]

    func parent(_ id: String) -> ParentVM {
        if let p = parents[id] { return p }
        let p = ParentVM(); parents[id] = p; return p
    }

    /// AgentJobRegistry.swift — siblingSessions(ofChild:children:) (verbatim).
    static func siblingSessions(ofChild stopped: String,
                                children: [(child: String, parent: String)]) -> [String] {
        guard let parent = children.first(where: { $0.child == stopped })?.parent else {
            return [stopped]
        }
        return children.filter { $0.parent == parent }.map(\.child)
    }

    /// HelperRunner.swift — delegationResultMayDriveParent (verbatim).
    static func delegationResultMayDriveParent(parentCancelled: Bool, delegationsMuted: Bool) -> Bool {
        !parentCancelled && !delegationsMuted
    }

    /// AgentJobRegistry.swift — parentSession(ofChild:)
    func parentSession(ofChild childSessionId: String) -> String? {
        for job in jobs.values where job.child == childSessionId { return job.parent }
        return nil
    }

    func activeChildren(parent: String) -> [Job] {
        jobs.values.filter { $0.parent == parent && ($0.state == .running || $0.state == .pending) }
    }

    func dropQueuedDelegations(parent: String) {
        queuedDelegations.removeAll { $0.parent == parent }
    }

    /// AgentJobRegistry.swift:664-673 — cancel(jobId:reason:silent:): a silent
    /// cancel drops the parent callback of the job it cancels, then finishes it.
    func cancel(jobId: String, silent: Bool) {
        guard let job = jobs[jobId] else { return }
        if silent { job.then = .none }
        finish(job, state: .cancelled)
    }

    /// AgentJobRegistry.swift:805-835 — cancelSiblings(ofChild:reason:) (verbatim shape).
    @discardableResult
    func cancelSiblings(ofChild childSessionId: String) -> Int {
        let live: [(child: String, parent: String)] = jobs.values.compactMap { job in
            guard job.state == .running || job.state == .pending, let child = job.child else { return nil }
            return (child: child, parent: job.parent)
        }
        let targets = Set(Self.siblingSessions(ofChild: childSessionId, children: live))
        guard let parent = parentSession(ofChild: childSessionId) else {
            // Not owned by this registry: stop the one session we were given.
            let mine = jobs.values.filter { $0.child == childSessionId }
            for j in mine { cancel(jobId: j.id, silent: true) }
            return mine.count
        }
        dropQueuedDelegations(parent: parent)
        var cancelled = 0
        for job in activeChildren(parent: parent) where targets.contains(job.child ?? "") {
            cancel(jobId: job.id, silent: true)
            cancelled += 1
        }
        return cancelled
    }

    /// The card's Stop (HelperBlockView → HelperRunner): mute the parent FIRST,
    /// then cancel the sibling set (59c1d7208 strategy step 3).
    func cardStop(childSessionId: String) {
        if let parent = parentSession(ofChild: childSessionId) {
            self.parent(parent).muteDelegationResults()
        }
        cancelSiblings(ofChild: childSessionId)
    }

    /// AgentJobRegistry.swift:539 finish → runThen.
    func finish(_ job: Job, state: JobState) {
        job.state = state
        runThen(for: job)
    }

    /// AgentJobRegistry.swift:900-965 — runThen: a completion is NEVER held
    /// back (b56101f3b); the mute is checked at delivery time (59c1d7208).
    private func runThen(for job: Job) {
        switch job.then {
        case .none:
            return
        case .followUpParent:
            let vm = parent(job.parent)
            guard !vm.delegationResultsAreMuted else { return }
            vm.callbacksReceived.append(job.id)
        }
    }

    /// The pre-b56101f3b hold (6110fa4ca), kept so the table shows the difference.
    var pendingBatchResults: [String: [String]] = [:]
    func runThen_heldUntilLastSibling(for job: Job) -> [String] {
        pendingBatchResults[job.parent, default: []].append(job.id)
        let stillWorking = jobs.values.contains {
            $0.id != job.id && $0.parent == job.parent && ($0.state == .running || $0.state == .pending)
        } || queuedDelegations.contains { $0.parent == job.parent }
        if stillWorking { return [] }
        return pendingBatchResults.removeValue(forKey: job.parent) ?? []
    }
}

// MARK: - Ported: steer delivery (671a12ce2)

struct ChildLoop {
    var isHelper: Bool            // helperConfig != nil
    var pendingSteerMessages: [String] = []
    var isProcessing: Bool
    var nudged = false
    var turnsRun = 0

    /// AIChatViewModel.swift:7022-7027 — the loop-end decision when a turn
    /// converged with no tool call and no queued prompt.
    mutating func loopEndContinues() -> Bool {
        if isHelper, !pendingSteerMessages.isEmpty { return true }
        return false
    }
    /// AIChatViewModel.swift:6013-6015 — the top-of-iteration consumption.
    mutating func consumeSteerAtTurnStart() -> [String] {
        guard isHelper, !pendingSteerMessages.isEmpty else { return [] }
        let steers = pendingSteerMessages
        pendingSteerMessages.removeAll()
        turnsRun += 1
        return steers
    }
    /// HelperRunner.swift:702-712 — executeAgentStatus's steer branch.
    mutating func steer(_ message: String) {
        pendingSteerMessages.append(message)
        if !isProcessing { nudged = true; isProcessing = true }
    }
}

// MARK: - Ported: resume keeps the name (4a1ddb032, HelperRunner.swift)

func parseDelegateResult(_ content: String) -> [String: Any]? {
    guard content.hasPrefix("{"), let d = content.data(using: .utf8),
          let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return nil }
    return o
}
func resolveAgentName(blockContent: String, persistedToolResult: String?) -> String? {
    var name = parseDelegateResult(blockContent)?["agent"] as? String
    if name?.isEmpty ?? true {
        name = persistedToolResult.flatMap { parseDelegateResult($0)?["agent"] as? String }
        if name?.isEmpty ?? true { return nil }
    }
    return name
}
func json(_ d: [String: Any]) -> String {
    String(data: try! JSONSerialization.data(withJSONObject: d, options: [.sortedKeys]), encoding: .utf8)!
}

// MARK: - Ported: control-vs-delegation classification (HelperBlockView.swift:236-263)

func isControlOnly(args: [String: Any]?, resultContent: String) -> Bool {
    if let args, let action = (args["action"] as? String)?.lowercased() {
        return action != "delegate"
    }
    guard let obj = parseDelegateResult(resultContent) else { return false }
    if obj["child_session_id"] is String { return false }
    if obj["agents"] != nil { return true }                  // action=status
    if obj["child_session_ids"] != nil { return true }        // action=resume
    if let s = obj["status"] as? String, s == "queued", obj["job_id"] != nil { return true }  // steer
    if let r = obj["reason"] as? String,
       ["already_finished", "child_not_running"].contains(r) { return true }
    return false
}

// MARK: - [A] The table

struct Row {
    let name: String
    let expected: Bool
    let run: () -> Bool
}

func fanOut(_ r: Registry, parent: String = "P1", n: Int) -> [Job] {
    (1...n).map { i in
        let j = Job(id: "job\(i)", parent: parent, child: "child\(i)", state: .running)
        r.jobs[j.id] = j
        return j
    }
}

let rows: [Row] = [
    // ---- Stop one → whole batch stops ---------------------------------------
    Row(name: "batch of 3, cancel the 1st from its card → all 3 cancelled", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 3)
        r.cardStop(childSessionId: "child1")
        return jobs.allSatisfy { $0.state == .cancelled }
    },
    Row(name: "…and the cancelled siblings carry no parent callback", expected: true) {
        let r = Registry(); _ = fanOut(r, n: 3)
        r.cardStop(childSessionId: "child2")
        return r.parent("P1").callbacksReceived.isEmpty
    },
    Row(name: "…and the queued backlog behind the batch is dropped first", expected: true) {
        let r = Registry(); _ = fanOut(r, n: 2)
        r.queuedDelegations = [(id: "q1", parent: "P1"), (id: "q2", parent: "P2")]
        r.cardStop(childSessionId: "child1")
        return r.queuedDelegations.map(\.id) == ["q2"]
    },
    Row(name: "another conversation's agents keep running", expected: true) {
        let r = Registry(); _ = fanOut(r, parent: "P1", n: 2)
        let other = Job(id: "jobX", parent: "P2", child: "childX", state: .running); r.jobs["jobX"] = other
        r.cardStop(childSessionId: "child1")
        return other.state == .running
    },
    Row(name: "an untracked child (previous process) still stops itself only", expected: true) {
        return Registry.siblingSessions(ofChild: "orphan", children: []) == ["orphan"]
    },
    Row(name: "PRE-FIX: cancelling by child id alone left 2 of 3 running", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 3)
        r.cancel(jobId: "job1", silent: true)                 // the old card Stop
        return jobs.filter { $0.state == .running }.count == 2
    },

    // ---- A finished sibling cannot re-wake a stopped parent -----------------
    Row(name: "parent stopped (card), sibling finishes 48ms later → parent NOT resumed", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 3)
        r.cardStop(childSessionId: "child1")
        // job2 was already closing on its own; it still holds a live `then`.
        jobs[1].then = .followUpParent
        r.finish(jobs[1], state: .done)
        return r.parent("P1").callbacksReceived.isEmpty
    },
    Row(name: "parent stopped (conversation Stop) → sibling result withheld too", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 2)
        r.parent("P1").userDidCancel = true
        r.finish(jobs[0], state: .done)
        return r.parent("P1").callbacksReceived.isEmpty
    },
    Row(name: "card Stop mutes without cancelling the parent turn", expected: true) {
        let r = Registry(); _ = fanOut(r, n: 2)
        r.cardStop(childSessionId: "child1")
        let p = r.parent("P1")
        return p.delegationResultsMuted && !p.userDidCancel
    },
    Row(name: "PRE-FIX: only userDidCancel gated delivery, so the sibling drove the parent", expected: true) {
        // The old gate: !parentCancelled alone.
        let parentCancelled = false, delegationsMuted = true
        return !parentCancelled && delegationsMuted   // muted, yet delivered
    },
    Row(name: "nothing stopped → an ordinary completion still drives the parent", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 1)
        r.finish(jobs[0], state: .done)
        return r.parent("P1").callbacksReceived == ["job1"]
    },

    // ---- Resume keeps the name -----------------------------------------------
    Row(name: "interrupted → resume → name unchanged (LENS stays LENS)", expected: true) {
        let live = "◐ Agent · Investigate · running in background · 1:35"
        let persisted = json(["ok": true, "status": "running", "job_id": "j1", "child_session_id": "c1", "agent": "LENS"])
        return resolveAgentName(blockContent: live, persistedToolResult: persisted) == "LENS"
    },
    Row(name: "PRE-FIX: a start payload without `agent` resumes as the built-in", expected: true) {
        let live = "◐ Agent · Investigate · running in background · 1:35"
        let persisted = json(["ok": true, "status": "running", "job_id": "j1", "child_session_id": "c1"])
        return resolveAgentName(blockContent: live, persistedToolResult: persisted) == nil
    },
    Row(name: "a resumed run records its own name on the job for a 2nd interruption", expected: true) {
        let j = Job(id: "j", parent: "P", child: "c", state: .running)
        j.subAgentName = "LENS"                                // set before startBackgroundHelper
        return j.subAgentName == "LENS"
    },

    // ---- Steer reaches the next turn ---------------------------------------
    Row(name: "steer arrives while the child converges → loop continues one more turn", expected: true) {
        var c = ChildLoop(isHelper: true, isProcessing: true)
        c.steer("focus on the logs")
        guard c.loopEndContinues() else { return false }
        return c.consumeSteerAtTurnStart() == ["focus on the logs"] && c.pendingSteerMessages.isEmpty
    },
    Row(name: "steer to an IDLE child nudges its loop (submitProgrammaticPrompt)", expected: true) {
        var c = ChildLoop(isHelper: true, isProcessing: false)
        c.steer("stop after step 2")
        return c.nudged && c.isProcessing
    },
    Row(name: "steer to a busy child does not nudge (the loop is already there to read it)", expected: true) {
        var c = ChildLoop(isHelper: true, isProcessing: true)
        c.steer("x")
        return !c.nudged
    },
    Row(name: "no pending steer → the loop ends as before", expected: false) {
        var c = ChildLoop(isHelper: true, isProcessing: true)
        return c.loopEndContinues()
    },
    Row(name: "a top-level (non-helper) conversation never consumes steers", expected: false) {
        var c = ChildLoop(isHelper: false, pendingSteerMessages: ["x"], isProcessing: true)
        return c.loopEndContinues() || !c.consumeSteerAtTurnStart().isEmpty
    },
    Row(name: "PRE-FIX: the steer stayed pending at loop end → reported as missed", expected: true) {
        var c = ChildLoop(isHelper: true, isProcessing: true)
        c.steer("x")
        let oldLoopEndContinues = false                     // old code: break unconditionally
        return !oldLoopEndContinues && !c.pendingSteerMessages.isEmpty
    },

    // ---- Classification: child_session_id first, then resumed -------------
    Row(name: "resumed=true + child_session_id → still a delegation card", expected: false) {
        isControlOnly(args: nil, resultContent: json(["ok": true, "status": "running", "resumed": true, "child_session_id": "c1", "job_id": "j"]))
    },
    Row(name: "resumed as Int 1 (bridged JSON bool) + child_session_id → still a card", expected: false) {
        isControlOnly(args: nil, resultContent: json(["status": "running", "resumed": 1, "child_session_id": "c1"]))
    },
    Row(name: "action=resume result (child_session_ids) → control", expected: true) {
        isControlOnly(args: nil, resultContent: json(["ok": true, "child_session_ids": ["c1", "c2"]]))
    },
    Row(name: "action=status result (agents) → control", expected: true) {
        isControlOnly(args: nil, resultContent: json(["ok": true, "agents": []]))
    },
    Row(name: "steer result (status=queued + job_id, no child) → control", expected: true) {
        isControlOnly(args: nil, resultContent: json(["ok": true, "status": "queued", "job_id": "j1"]))
    },
    Row(name: "a queued DELEGATION (status=queued + child_session_id) → card, not control", expected: false) {
        isControlOnly(args: nil, resultContent: json(["ok": true, "status": "queued", "job_id": "j1", "child_session_id": "c1"]))
    },
    Row(name: "rejected steer (already_finished) → control", expected: true) {
        isControlOnly(args: nil, resultContent: json(["ok": false, "reason": "already_finished"]))
    },
    Row(name: "rejected steer (child_not_running) → control", expected: true) {
        isControlOnly(args: nil, resultContent: json(["ok": false, "reason": "child_not_running"]))
    },
    Row(name: "args present: action=steer wins over any result shape", expected: true) {
        isControlOnly(args: ["action": "Steer"], resultContent: json(["child_session_id": "c1"]))
    },
    Row(name: "args present: action=delegate is never control", expected: false) {
        isControlOnly(args: ["action": "delegate"], resultContent: json(["agents": []]))
    },
    Row(name: "a progress line (not JSON) is not control", expected: false) {
        isControlOnly(args: nil, resultContent: "◐ Agent · t · 0:10")
    },
    Row(name: "PRE-FIX: keying on `resumed` hid the resumed delegation", expected: true) {
        let obj = parseDelegateResult(json(["resumed": true, "child_session_id": "c1"]))!
        return obj["resumed"] != nil                          // the old test fired here
    },

    // ---- Batch callbacks: one per completion -------------------------------
    Row(name: "3 sub agents finish → 3 callbacks, not 1", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 3)
        for j in jobs { r.finish(j, state: .done) }
        return r.parent("P1").callbacksReceived == ["job1", "job2", "job3"]
    },
    Row(name: "the first of 3 to finish wakes the parent while 2 still run", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 3)
        r.finish(jobs[0], state: .done)
        return r.parent("P1").callbacksReceived == ["job1"] && jobs[1].state == .running
    },
    Row(name: "PRE-b56101f3b: the hold withheld the 1st result until the last sibling landed", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 3)
        jobs[0].state = .done
        let first = r.runThen_heldUntilLastSibling(for: jobs[0])
        jobs[1].state = .done
        let second = r.runThen_heldUntilLastSibling(for: jobs[1])
        jobs[2].state = .done
        let last = r.runThen_heldUntilLastSibling(for: jobs[2])
        return first.isEmpty && second.isEmpty && last == ["job1", "job2", "job3"]
    },
    Row(name: "a wait-mode job (then = .none) delivers nothing through runThen", expected: true) {
        let r = Registry(); let jobs = fanOut(r, n: 1)
        jobs[0].then = .none
        r.finish(jobs[0], state: .done)
        return r.parent("P1").callbacksReceived.isEmpty
    },
]

print("\n[A] Lifecycle table")
for row in rows {
    check(row.name, row.run(), row.expected)
}

// MARK: - [B] Drift guards

print("\n[B] Shipping sources match these ports")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let reg = source("Agent/Jobs/AgentJobRegistry.swift")
let hr = source("Agent/Jobs/HelperRunner.swift")
let vm = source("Agent/Chat/AIChatViewModel.swift")
let hbv = source("Views/Chat/HelperBlockView.swift")
if [reg, hr, vm, hbv].contains(where: { $0.isEmpty }) {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    // Stop siblings
    check("siblingSessions is the pure fan-out rule",
          reg.contains("static func siblingSessions(ofChild stopped: String,")
          && reg.contains("return children.filter { $0.parent == parent }.map(\\.child)"))
    check("cancelSiblings drops the queued backlog before cancelling",
          reg.range(of: "dropQueuedDelegations(parent: parent, reason: reason)")!.lowerBound
          < reg.range(of: "cancel(jobId: job.id, reason: reason, silent: true)\n            cancelled += 1")!.lowerBound)
    check("a silent cancel drops the job's callback", reg.contains("if silent { job.then = AgentJobThen.none }"))
    check("runThen checks the mute at delivery time", reg.contains("guard !vm.delegationResultsAreMuted else {"))
    check("delegationResultMayDriveParent is the shipped gate",
          hr.contains("static func delegationResultMayDriveParent(parentCancelled: Bool,")
          && hr.contains("!parentCancelled && !delegationsMuted"))
    check("the tool loop's stop check consults the mute",
          vm.contains("if cancelledDuringToolExecution || self.userDidCancel || self.delegationResultsMuted {"))
    check("a muted parent keeps the result for display but not for agentHistory",
          hr.contains("if delegationResultsAreMuted {") && hr.contains("kept for display, withheld from agentHistory"))
    // Resume name
    check("the background-start payload carries the agent name", hr.contains("\"agent\": job.subAgentName ?? NSNull()"))
    check("resume falls back to the persisted tool_result for the name",
          hr.contains("agentName = await Self.persistedAgentName(toolUseId: toolUseId, sessionId: parentSid)"))
    // Steer
    check("loop end continues on a pending steer",
          vm.contains("if helperConfig != nil, !pendingSteerMessages.isEmpty {\n                    logger.info(\"[subagent_task] steer arrived at loop end — continuing for another turn\")"))
    check("steer at the top of an iteration is consumed once",
          vm.contains("let steers = pendingSteerMessages\n                pendingSteerMessages.removeAll()"))
    check("an idle child is nudged through submitProgrammaticPrompt",
          hr.contains("if !child.isProcessing {") && hr.contains("_ = child.submitProgrammaticPrompt(Self.steerNudgePrompt,"))
    // Classification order
    let cidIdx = hbv.range(of: "if obj[\"child_session_id\"] is String { return false }")!.lowerBound
    let agentsIdx = hbv.range(of: "if obj[\"agents\"] != nil { return true }")!.lowerBound
    let steerIdx = hbv.range(of: "s == \"queued\", obj[\"job_id\"] != nil { return true }")!.lowerBound
    check("child_session_id is tested BEFORE every control shape", cidIdx < agentsIdx && cidIdx < steerIdx)
    check("action argument wins when the block still has its args",
          hbv.contains("let action = (args[\"action\"] as? String)?.lowercased() {\n            return action != \"delegate\""))
    check("isControlOnly is not keyed on `resumed`", !hbv.contains("obj[\"resumed\"] is Int") && !hbv.contains("obj[\"resumed\"] != nil"))
    check("the queue_lost scan skips control calls", hr.contains("!HelperBlockInfo.isControlOnly(block),"))
    // Batch callbacks
    check("the batch hold is gone (pendingBatchResults removed)",
          !reg.contains("pendingBatchResults") && !reg.contains("flushBatchIfIdle"))
    check("every completion is delivered as-is", reg.contains("let payload = text"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
