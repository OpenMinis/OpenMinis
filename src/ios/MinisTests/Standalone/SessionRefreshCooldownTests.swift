// Tests for [T-ios-listsessions-perf] Phase 4 — the sidebar refresh cooldown is
// measured from when the previous refresh FINISHED, not from when its trigger
// arrived.
//
// The bug this pins down: `.throttle(for: .seconds(1))` on the .sessionDidUpdate
// publisher looked like a rate limit but was not one. A refresh took 4.4 s
// median / 9.1 s p90 in the profiled trace, so it always outlived its own
// throttle window; the trailing `sessionRefreshPending` flag then re-queued a
// run the instant the previous one returned. 63 rebuilds ran back to back with
// a median idle gap of 2.8 s, holding the ChatStore actor busy 55% of wall time.
//
// Standalone (`swift SessionRefreshCooldownTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Under test (mirrors src/ios/Views/SessionRefreshScheduler.swift)

enum SessionRefreshScheduler {
    static let cooldown: TimeInterval = 3

    enum Decision: Equatable {
        case runNow
        case `defer`(TimeInterval)
    }

    static func decide(
        now: Date,
        lastFinishedAt: Date?,
        cooldown: TimeInterval = SessionRefreshScheduler.cooldown
    ) -> Decision {
        guard let last = lastFinishedAt else { return .runNow }
        let elapsed = now.timeIntervalSince(last)
        if elapsed < -1 { return .runNow }
        guard elapsed < cooldown else { return .runNow }
        return .defer(max(cooldown - elapsed, 0.001))
    }
}

// MARK: - 1. The decision table

print("\n▶️  decide()")
let t0 = Date(timeIntervalSince1970: 1_000_000)

checkEq("first ever event runs immediately",
        SessionRefreshScheduler.decide(now: t0, lastFinishedAt: nil), .runNow)

// THE case that matters: the trailing pending re-run re-enters the instant
// `lastFinishedAt` was stamped, so elapsed is ~0. It must take the FULL
// cooldown — an earlier draft returned .runNow here and silently reopened the
// back-to-back loop this change exists to close.
checkEq("zero elapsed (the trailing re-run) defers the full cooldown",
        SessionRefreshScheduler.decide(now: t0, lastFinishedAt: t0), .defer(3.0))

checkEq("0.5 s after → defer 2.5 s",
        SessionRefreshScheduler.decide(now: t0.addingTimeInterval(0.5), lastFinishedAt: t0),
        .defer(2.5))

// Float tolerance: Date arithmetic at this magnitude loses the last few bits,
// so compare the delay rather than the case payload exactly.
if case .defer(let d) = SessionRefreshScheduler.decide(
    now: t0.addingTimeInterval(2.999), lastFinishedAt: t0) {
    check("2.999 s after → still defers, by ~1 ms", abs(d - 0.001) < 0.01)
} else {
    check("2.999 s after → still defers", false)
}

checkEq("exactly 3 s after → runs",
        SessionRefreshScheduler.decide(now: t0.addingTimeInterval(3), lastFinishedAt: t0), .runNow)

checkEq("10 s after → runs",
        SessionRefreshScheduler.decide(now: t0.addingTimeInterval(10), lastFinishedAt: t0), .runNow)

checkEq("clock moved backwards → runs rather than stalling",
        SessionRefreshScheduler.decide(now: t0.addingTimeInterval(-500), lastFinishedAt: t0), .runNow)

print("\n▶️  a deferral is always positive and never exceeds the cooldown")
for ms in stride(from: 1, to: 3000, by: 7) {
    let d = SessionRefreshScheduler.decide(
        now: t0.addingTimeInterval(Double(ms) / 1000), lastFinishedAt: t0
    )
    guard case .defer(let delay) = d else {
        check("elapsed \(ms)ms should defer", false); continue
    }
    if !(delay > 0 && delay <= 3) {
        check("delay \(delay) in (0, 3] for elapsed \(ms)ms", false)
    }
}
print("  ✅ all 429 sub-cooldown elapsed values yield a delay in (0, 3]")

// MARK: - 2. Simulation: the old behaviour vs the new one
//
// Replays the trace's shape — a long agent task emitting .sessionDidUpdate
// every ~1 s while each refresh takes 4.4 s — and counts how many rebuilds each
// policy performs over the same wall-clock window.

struct Sim {
    let refreshDuration: TimeInterval
    let notificationEvery: TimeInterval
    let window: TimeInterval

    /// Old: 1 s throttle on arrival + a trailing pending flag that re-queues
    /// the moment the run returns. The throttle never bites because the run is
    /// longer than its window.
    func legacyRuns() -> Int {
        var clock: TimeInterval = 0
        var runs = 0
        while clock < window {
            runs += 1
            clock += refreshDuration
            // A notification always arrived during the run, so pending is set
            // and the next run starts with no gap at all.
        }
        return runs
    }

    /// New: after each run finishes, wait out the cooldown before the pending
    /// re-run is allowed to start.
    func cooldownRuns(_ cooldown: TimeInterval) -> Int {
        var clock: TimeInterval = 0
        var runs = 0
        var lastFinished: Date?
        let base = Date(timeIntervalSince1970: 0)
        while clock < window {
            switch SessionRefreshScheduler.decide(
                now: base.addingTimeInterval(clock), lastFinishedAt: lastFinished, cooldown: cooldown
            ) {
            case .runNow:
                runs += 1
                clock += refreshDuration
                lastFinished = base.addingTimeInterval(clock)
            case .defer(let d):
                clock += d
            }
        }
        return runs
    }
}

print("\n▶️  duty cycle over a 300 s agent task (4.4 s refresh, 1 s notifications)")
let sim = Sim(refreshDuration: 4.4, notificationEvery: 1, window: 300)
let legacy = sim.legacyRuns()
let now3 = sim.cooldownRuns(3)
print("  📊 legacy: \(legacy) rebuilds  |  3 s cooldown: \(now3) rebuilds")
check("cooldown cuts the rebuild count", now3 < legacy)
let busyLegacy = Double(legacy) * 4.4 / 300
let busyNew = Double(now3) * 4.4 / 300
print("  📊 actor busy: legacy \(Int(busyLegacy * 100))%  |  new \(Int(busyNew * 100))%")
check("legacy pegs the actor above 95%", busyLegacy > 0.95)
check("new policy holds the actor under 65%", busyNew < 0.65)
check("but the sidebar still refreshes regularly (>= 35 times in 300 s)", now3 >= 35)

// MARK: - 3. The first event is never delayed

print("\n▶️  responsiveness guarantees")
check("cold start is immediate",
      SessionRefreshScheduler.decide(now: t0, lastFinishedAt: nil) == .runNow)
// After an idle period (user reading, no agent running) the next event is also
// immediate — the cooldown only ever applies to back-to-back churn.
check("event after 60 s idle is immediate",
      SessionRefreshScheduler.decide(
        now: t0.addingTimeInterval(60), lastFinishedAt: t0) == .runNow)
checkEq("cooldown constant is the documented 3 s", SessionRefreshScheduler.cooldown, 3.0)

// MARK: - Summary

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All session-refresh cooldown tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
