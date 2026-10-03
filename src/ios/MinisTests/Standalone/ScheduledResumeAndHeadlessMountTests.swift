// [T27] Scheduled-task resume must not self-skip on its own previous
// completion marker; a headless AppIntent must wait for external mounts
// before it starts an agent.
//
// Issues: OpenMinis#201 (a daily follow-up task answered "this is a dedup
// marker, already done" with zero tool calls after reading YESTERDAY's
// completion report in the same session), #335 (iCloud Drive mounts missing
// in a cold headless Shortcut run because `activateAll()` only ran from the
// root view's `.onAppear`), #325.
//
// Standalone (`swift ScheduledResumeAndHeadlessMountTests.swift`): the app
// cannot link for a simulator (deps/libs/libish_emu.a is device-only arm64).
// Sections [1]-[2] port ScheduledJobRunner.fire / scheduledEnvelope and
// MountedFoldersManager.ensureActivated verbatim (file:line cited); section
// [3] re-reads the shipping sources — including every headless intent's
// ordering — so the ports cannot drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func knownGap(_ label: String, _ holds: Bool) {
    if holds { print("  ✅ \(label) (gap closed)") } else { print("  ⚠️  KNOWN GAP: \(label)") }
}

// MARK: - Ported: the scheduled envelope (ScheduledJobRunner.swift:252-331, AgentCallback.swift:135-172)

enum Trigger {
    case loop(interval: TimeInterval, count: Int?)
    case cron
    case once, immediate, onCompletion(of: String)
    var logLabel: String {
        switch self {
        case .loop(let i, let c): return "loop(\(Int(i))s×\(c.map(String.init) ?? "∞"))"
        case .cron: return "cron"
        case .once: return "once"
        case .immediate: return "immediate"
        case .onCompletion: return "on-completion"
        }
    }
}

struct SchedJob {
    var id: String
    var label: String?
    var title: String
    var trigger: Trigger
    var firedCount: Int
    var remaining: Int?
    var prompt: String
}

/// ScheduledJobRunner.roundPrefix (line 318-329).
func roundPrefix(_ job: SchedJob) -> String {
    let name = job.label ?? job.title
    switch job.trigger {
    case .loop, .cron:
        let fire = job.firedCount + 1
        let remaining = job.remaining.map { " · remaining \($0 - 1 < 0 ? 0 : $0 - 1)" } ?? ""
        return "[Scheduled task \"\(name)\" · fire \(fire)\(remaining) · \(job.trigger.logLabel)]\n"
    case .onCompletion(let of):
        return "[Scheduled task \"\(name)\" · triggered by completion of job \(of.prefix(8))]\n"
    case .once, .immediate:
        return "[Scheduled task \"\(name)\"]\n"
    }
}

struct ScheduledCallback {
    let jobId: String, title: String, status: String, body: String
    let fireIndex: Int?, remaining: Int?, triggerLabel: String?, nextFireAt: String?
    /// AgentCallback.xml, scheduled-kind subset.
    var xml: String {
        var attrs: [(String, String)] = [("kind", "scheduled"), ("job", jobId)]
        attrs.append(("title", title)); attrs.append(("status", status))
        if let fireIndex, fireIndex > 0 { attrs.append(("fire", String(fireIndex))) }
        if let remaining, remaining >= 0 { attrs.append(("remaining", String(remaining))) }
        if let triggerLabel, !triggerLabel.isEmpty { attrs.append(("trigger", triggerLabel)) }
        if let nextFireAt, !nextFireAt.isEmpty { attrs.append(("next_fire_at", nextFireAt)) }
        let open = "<agent_callback " + attrs.map { "\($0.0)=\"\($0.1)\"" }.joined(separator: " ") + ">"
        return [open, "<prompt>", body, "</prompt>", "</agent_callback>"].joined(separator: "\n")
    }
}

/// ScheduledJobRunner.scheduledEnvelope (line 252-315).
func scheduledEnvelope(_ job: SchedJob, prompt: String, now: Date = Date()) -> ScheduledCallback {
    var fireIndex: Int?
    var remaining: Int?
    switch job.trigger {
    case .loop, .cron:
        fireIndex = job.firedCount + 1
        remaining = job.remaining.map { max(0, $0 - 1) }
    default: break
    }
    let nextFire: Date? = {
        switch job.trigger {
        case .loop(let interval, _):
            if let r = job.remaining, r - 1 <= 0 { return nil }
            return now.addingTimeInterval(interval)
        case .cron: return now.addingTimeInterval(86_400)   // stands in for nextCronDate
        case .once, .immediate, .onCompletion: return nil
        }
    }()
    let iso = ISO8601DateFormatter()
    return ScheduledCallback(jobId: job.id, title: job.label ?? job.title, status: "running",
                             body: roundPrefix(job) + prompt,
                             fireIndex: fireIndex, remaining: remaining,
                             triggerLabel: job.trigger.logLabel, nextFireAt: nextFire.map { iso.string(from: $0) })
}

// MARK: - Ported: the follow-up fire path (ScheduledJobRunner.swift:155-167)

enum FireOutcome: Equatable { case aborted, submitted(String) }

/// `.followUp(sid)`: the ONLY guard is that the session still exists. The
/// session's history — including the previous fire's envelope and whatever
/// the model said about it — is never consulted.
func fireFollowUp(job: SchedJob, sessionExists: Bool, history: [String],
                  submit: (String) -> Void) -> FireOutcome {
    guard sessionExists else { return .aborted }
    let text = scheduledEnvelope(job, prompt: job.prompt).xml
    submit(text)
    return .submitted(text)
}

// MARK: - Ported: awaitable mount activation (MountedFoldersManager.swift:413-528)

/// A deterministic model of `ensureActivated` / `ensureActivated(timeout:)`
/// driven by a fake clock: `resolveCost` is how long the pass takes.
final class MountActivation {
    var didCompleteActivationPass = false
    var passesStarted = 0
    var activeCount = 0
    var snapshotPushes = 0
    private var passInFlight = false
    private var passCompletesAt: TimeInterval?
    var now: TimeInterval = 0
    let entries: Int
    let resolveCost: TimeInterval
    init(entries: Int, resolveCost: TimeInterval) { self.entries = entries; self.resolveCost = resolveCost }

    /// `ensureActivated()` — single-flight, at most one pass per process.
    func ensureActivated() {
        if didCompleteActivationPass { return }
        if passInFlight { return }              // join the existing pass
        passesStarted += 1
        passInFlight = true
        passCompletesAt = now + resolveCost     // resolves run CONCURRENTLY: one cost, not n×cost
    }
    /// Advance the fake clock; the pass lands when its deadline passes.
    func tick(_ dt: TimeInterval) {
        now += dt
        if passInFlight, let at = passCompletesAt, now >= at {
            activeCount = entries
            snapshotPushes += 1
            didCompleteActivationPass = true
            passInFlight = false
            passCompletesAt = nil
        }
    }
    /// `ensureActivated(timeout:)` — start the pass, poll the flag until the
    /// deadline, RETURN (never cancel) on expiry. Returns whether it completed.
    func ensureActivated(timeout: TimeInterval) -> Bool {
        if didCompleteActivationPass { return true }
        ensureActivated()
        let deadline = now + max(0, timeout)
        while !didCompleteActivationPass && now < deadline { tick(0.05) }
        return didCompleteActivationPass
    }
    /// `activateAll()` — fire-and-forget wrapper over the same pass.
    func activateAll() { ensureActivated() }
}

/// The headless intent's sequence (SendPromptIntent.swift:122-131).
func headlessIntentStart(_ m: MountActivation) -> (mountsVisibleAtAgentStart: Int, waited: Bool) {
    let done = m.ensureActivated(timeout: 12)
    // …only now: getOrCreate / createDraft / loadSession / submit.
    return (m.activeCount, done)
}

// MARK: - [1] A resumed scheduled task still fires

print("\n[1] The scheduled fire ignores the session's previous completion marker")
do {
    var job = SchedJob(id: "job-daily", label: "Daily scan", title: "scan", trigger: .cron,
                       firedCount: 6, remaining: nil, prompt: "Scan the market and publish today's article.")
    // The #201 transcript: yesterday's envelope, then the model's "already done".
    var history: [String] = []
    let yesterday = scheduledEnvelope(job, prompt: job.prompt).xml
    history.append(yesterday)
    history.append("Published, backed up, logged. Done for today.")
    job.firedCount += 1

    var submitted: String? = nil
    let outcome = fireFollowUp(job: job, sessionExists: true, history: history) { submitted = $0 }
    check("today's fire is submitted even though history holds yesterday's callback",
          { if case .submitted = outcome { return true }; return false }())
    check("the injected turn is a scheduled envelope", submitted?.hasPrefix("<agent_callback kind=\"scheduled\"") == true)
    check("today's envelope is NOT byte-identical to yesterday's (fire index moved on)", submitted != yesterday)
    check("today's envelope says fire 8, yesterday's said fire 7",
          submitted!.contains("fire=\"8\"") && yesterday.contains("fire=\"7\""))
    check("the model-facing body carries the same fire index in prose",
          submitted!.contains("[Scheduled task \"Daily scan\" · fire 8 · cron]"))
    check("the body still carries the task prompt verbatim", submitted!.contains(job.prompt))

    // The only abort is a deleted session.
    checkEq("a deleted follow-up session aborts the fire",
            fireFollowUp(job: job, sessionExists: false, history: history) { _ in }, .aborted)

    // A history-based skip would look like this; the shipped path has none.
    let selfSkip = history.contains { $0.hasPrefix("<agent_callback kind=\"scheduled\"") }
    check("(the tempting heuristic) history DOES contain a previous scheduled envelope", selfSkip)
    check("…and the fire path does not consult it", { if case .submitted = outcome { return true }; return false }())

    // #201 asks for runtime context (today's date / last fire) in the envelope so
    // the model can tell this firing from the previous one without a prompt hack.
    knownGap("the scheduled envelope carries today's date / last-fired-at for the model (#201 feature; only fire index + next_fire_at exist)",
             submitted!.contains("today=") || submitted!.contains("last_fired_at=") || submitted!.contains("date="))

    // Loop trigger: remaining counts down, next_fire_at disappears on the last fire.
    let loop = SchedJob(id: "j2", label: nil, title: "ping", trigger: .loop(interval: 60, count: 3),
                        firedCount: 2, remaining: 1, prompt: "p")
    let last = scheduledEnvelope(loop, prompt: "p")
    checkEq("last loop fire → remaining 0", last.remaining, 0)
    check("last loop fire → no next_fire_at", last.nextFireAt == nil)
    let mid = scheduledEnvelope(SchedJob(id: "j2", label: nil, title: "ping", trigger: .loop(interval: 60, count: 3),
                                         firedCount: 0, remaining: 3, prompt: "p"), prompt: "p")
    check("a mid-run loop fire promises the next one", mid.nextFireAt != nil && mid.remaining == 2)
    check("a one-shot fire has no fire index", scheduledEnvelope(SchedJob(id: "j3", label: nil, title: "t", trigger: .once, firedCount: 0, remaining: nil, prompt: "p"), prompt: "p").fireIndex == nil)
}

// MARK: - [2] Headless start waits for mounts

print("\n[2] A headless intent awaits mount activation before constructing the agent")
do {
    // #335 reproduction: cold process, 4 iCloud bookmarks at ~5s each.
    let m = MountActivation(entries: 4, resolveCost: 5)
    let r = headlessIntentStart(m)
    check("the agent starts with every mount visible", r.mountsVisibleAtAgentStart == 4)
    check("the wait completed inside the 12s ceiling", r.waited)
    check("resolves ran concurrently: 4×5s finished in ~5s, not 20s", m.now < 6)
    checkEq("exactly one activation pass ran", m.passesStarted, 1)

    // PRE-FIX: activateAll only ran from .onAppear, which a headless run never builds.
    let cold = MountActivation(entries: 4, resolveCost: 5)
    let rootViewAppeared = false
    if rootViewAppeared { cold.activateAll() }
    check("PRE-FIX: without the await the agent saw zero mounts", cold.activeCount == 0)

    // A cached VM in a process that never activated is the force-quit case:
    // the await sits OUTSIDE the "is the VM new" branch.
    let cached = MountActivation(entries: 2, resolveCost: 1)
    let vmWasCached = true
    let r2 = headlessIntentStart(cached)
    check("a cached VM still waits for activation", vmWasCached && r2.mountsVisibleAtAgentStart == 2)

    // Fast path: a second intent in the same process does not wait again.
    let before = cached.now
    _ = headlessIntentStart(cached)
    check("a later intent returns immediately once a pass completed", cached.now == before && cached.passesStarted == 1)

    // Single-flight: .onAppear's activateAll and a headless intent join one pass.
    let shared = MountActivation(entries: 3, resolveCost: 2)
    shared.activateAll()
    _ = headlessIntentStart(shared)
    checkEq("activateAll + ensureActivated share one pass", shared.passesStarted, 1)

    // The ceiling is real: a hung FileProvider returns the intent, and the
    // pass keeps running so the mount becomes usable mid-run.
    let slow = MountActivation(entries: 1, resolveCost: 30)
    let r3 = headlessIntentStart(slow)
    check("a 30s resolve gives up at the 12s ceiling", !r3.waited && slow.now >= 12 && slow.now < 13)
    check("…the agent starts without mounts rather than never", r3.mountsVisibleAtAgentStart == 0)
    slow.tick(20)
    check("…and the pass still lands afterwards (not cancelled)", slow.didCompleteActivationPass && slow.snapshotPushes == 1)

    // No entries: nothing to wait for, snapshot still pushed.
    let none = MountActivation(entries: 0, resolveCost: 0)
    check("zero mounts completes immediately", headlessIntentStart(none).waited)
}

// MARK: - [3] Drift guards

print("\n[3] Shipping sources match these ports")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let sched = source("Agent/Jobs/ScheduledJobRunner.swift")
let mfm = source("Views/Settings/MountedFoldersManager.swift")
let app = source("MinisApp.swift")
if sched.isEmpty || mfm.isEmpty || app.isEmpty {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    // Scheduled fire
    let fu = sched.range(of: "case .followUp(let sid):")!.lowerBound
    let fuEnd = sched.range(of: "case .rerun(let sid, let messageId):")!.lowerBound
    let followUpBranch = String(sched[fu..<fuEnd])
    check("follow-up fire's only guard is session existence",
          followUpBranch.contains("guard await ChatStore.shared.getSession(sid) != nil else {"))
    check("follow-up fire never inspects history for a previous callback",
          !followUpBranch.contains("agentHistory") && !followUpBranch.contains("isCallbackText")
          && !followUpBranch.contains("kind: .scheduled") && !followUpBranch.contains("lastFire"))
    check("follow-up fire submits through the job-origin channel",
          followUpBranch.contains("vm.submitProgrammaticPrompt(text, origin: .job(jobId: job.id), silent: true)"))
    check("the envelope's fire index is firedCount + 1", sched.contains("fireIndex = job.firedCount + 1"))
    check("the body keeps roundPrefix + prompt verbatim for the model", sched.contains("body: roundPrefix(job) + prompt,"))
    check("the round prefix names the fire in prose",
          sched.contains("return \"[Scheduled task \\\"\\(name)\\\" · fire \\(fire)\\(remaining) · \\(job.trigger.logLabel)]\\n\""))

    // Mount activation
    check("activateAll delegates to the awaitable pass", mfm.contains("Task { await self.ensureActivated() }"))
    check("ensureActivated is single-flight",
          mfm.contains("if didCompleteActivationPass { return }\n        if let existing = activationPass {\n            await existing.value\n            return\n        }"))
    check("resolves run concurrently in a task group", mfm.contains("group.addTask { await Self.resolveAndCommit(entry: entry) }"))
    check("the timeout variant polls the flag (never awaits the pass)",
          mfm.contains("while !didCompleteActivationPass && Date() < deadline {"))
    check("…and returns on expiry without cancelling", mfm.contains("gave up after") && !mfm.contains("activationPass?.cancel()"))
    check("the root view still calls activateAll", app.contains("MountedFoldersManager.shared.activateAll()"))

    // Every headless intent awaits BEFORE it touches the VM or sends.
    for name in ["SendPromptIntent", "QuickTaskIntent", "FollowUpSessionIntent", "RetryRunIntent"] {
        let src = source("Agent/Intents/\(name).swift")
        guard !src.isEmpty else { check("\(name) readable", false); continue }
        check("\(name) is headless", src.contains("static var openAppWhenRun = false"))
        guard let waitIdx = src.range(of: "await MountedFoldersManager.shared.ensureActivated(timeout: 12)")?.lowerBound else {
            check("\(name) awaits ensureActivated(timeout:)", false); continue
        }
        // The agent is constructed by loadSession / started by send; a draft VM
        // object may be created earlier (QuickTaskIntent) — that is inert.
        let starts = ["vm.send(overrideText:", ".submitProgrammaticPrompt(", "await vm.loadSession()", "vm.retryFromMessage("]
            .compactMap { src.range(of: $0)?.lowerBound }
        check("\(name) awaits mounts before loadSession and before any send",
              !starts.isEmpty && starts.allSatisfy { $0 > waitIdx })
        // `send`/`submit` in particular must come after the wait.
        let sendIdx = (src.range(of: "vm.send(overrideText:")?.lowerBound)
            ?? (src.range(of: ".submitProgrammaticPrompt(")?.lowerBound)
            ?? (src.range(of: "vm.retryFromMessage(")?.lowerBound)
        check("\(name): the send comes after the wait", sendIdx.map { $0 > waitIdx } ?? false)
    }
    // AskMinis opens the app, so .onAppear's activateAll covers it.
    let ask = source("Agent/Intents/AskMinisIntent.swift")
    check("AskMinisIntent opens the app (activateAll runs from .onAppear)", ask.contains("static var openAppWhenRun = true"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
