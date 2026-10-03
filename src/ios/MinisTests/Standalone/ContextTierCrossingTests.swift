// Tests for [T-ios-context-usage-realtime-crossing] — the mid-loop "first
// crossing" hint for the composer's context-usage line.
//
// Pins the pure decision logic (a copy of ContextTierCrossingTracker from
// src/ios/Agent/Chat/ChatModels.swift) and greps the view model for the
// wiring invariants: the loop observes every API call's usage, the loop-end
// path dedups against a just-fired crossing, and session load resets the
// baseline.
//
// Standalone (`swift ContextTierCrossingTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}

// MARK: - Copy of the pure logic

enum Tier: Equatable {
    case normal, warning, critical
    var rank: Int { switch self { case .normal: return 0; case .warning: return 1; case .critical: return 2 } }
}
struct ContextTierCrossingTracker: Equatable {
    static let minInterval: TimeInterval = 4.0
    private(set) var lastTier: Tier = .normal
    private(set) var lastFiredAt: Date? = nil
    private(set) var lastFiredTier: Tier? = nil
    mutating func reset(to tier: Tier) { lastTier = tier; lastFiredAt = nil; lastFiredTier = nil }
    mutating func observe(_ tier: Tier, now: Date) -> Bool {
        let previous = lastTier
        lastTier = tier
        guard tier.rank > previous.rank else { return false }
        // Rate limit only re-announcing a tier at or below the last one shown
        // (threshold flapping); a genuine rise past a HIGHER line is never
        // held back by the interval.
        if let firedAt = lastFiredAt, now.timeIntervalSince(firedAt) < Self.minInterval,
           tier.rank <= (lastFiredTier?.rank ?? -1) { return false }
        return true
    }
    mutating func markFired(_ tier: Tier, now: Date) { lastFiredAt = now; lastFiredTier = tier }
    func recentlyFired(for tier: Tier, now: Date) -> Bool {
        guard let firedAt = lastFiredAt, lastFiredTier == tier else { return false }
        return now.timeIntervalSince(firedAt) < Self.minInterval
    }
}
/// The view-model glue, as noteContextUsageMidLoop + the loop-end block do it.
struct Harness {
    var tracker = ContextTierCrossingTracker()
    var hintsShown: [Tier] = []
    mutating func midLoop(_ tier: Tier, at t: TimeInterval) {
        let now = Date(timeIntervalSince1970: t)
        if tracker.observe(tier, now: now) { hintsShown.append(tier); tracker.markFired(tier, now: now) }
    }
    mutating func loopEnd(_ tier: Tier, at t: TimeInterval) {
        let now = Date(timeIntervalSince1970: t)
        _ = tracker.observe(tier, now: now)
        if tracker.recentlyFired(for: tier, now: now) { return }
        hintsShown.append(tier); tracker.markFired(tier, now: now)
    }
}

print("▶️  1. normal → warning mid-loop fires once")
do {
    var h = Harness()
    h.midLoop(.normal, at: 0); h.midLoop(.normal, at: 1); h.midLoop(.warning, at: 2)
    check("one hint, for warning", h.hintsShown == [.warning])
}

print("▶️  2. repeated updates inside the same tier do not re-fire")
do {
    var h = Harness()
    h.midLoop(.warning, at: 0)
    for t in stride(from: 10.0, through: 60.0, by: 10) { h.midLoop(.warning, at: t) }
    check("still exactly one hint", h.hintsShown.count == 1)
    h.midLoop(.critical, at: 70)
    check("warning → critical is a new crossing", h.hintsShown == [.warning, .critical])
    for t in stride(from: 80.0, through: 120.0, by: 10) { h.midLoop(.critical, at: t) }
    check("critical repeats do not re-fire", h.hintsShown.count == 2)
}

print("▶️  3. fall back to normal (compaction / model switch), then rise again → fires again")
do {
    var h = Harness()
    h.midLoop(.warning, at: 0)
    h.midLoop(.normal, at: 30)        // compaction shrank the numerator
    check("falling does not fire", h.hintsShown.count == 1)
    h.midLoop(.warning, at: 60)
    check("re-crossing the same line later fires again", h.hintsShown == [.warning, .warning])
    h.midLoop(.critical, at: 61)       // model switch shrank the window
    check("crossing two lines in one update fires once for the new tier", h.hintsShown == [.warning, .warning, .critical])
}

print("▶️  4. mid-loop crossing then loop end at the same tier → no second hint")
do {
    var h = Harness()
    h.midLoop(.warning, at: 0)
    h.loopEnd(.warning, at: 2.5)
    check("loop-end deduped", h.hintsShown == [.warning])
    var g = Harness()
    g.midLoop(.warning, at: 0)
    g.loopEnd(.warning, at: 30)
    check("loop-end long after the crossing shows its usual line", g.hintsShown == [.warning, .warning])
    var k = Harness()
    k.midLoop(.normal, at: 0)
    k.loopEnd(.normal, at: 5)
    check("loop-end with no crossing keeps the original behaviour (always shows)", k.hintsShown == [.normal])
}

print("▶️  5. threshold flapping within seconds is rate-limited")
do {
    var h = Harness()
    h.midLoop(.warning, at: 0)        // 71%
    h.midLoop(.normal, at: 0.5)       // 69%
    h.midLoop(.warning, at: 1.0)      // 71% again
    h.midLoop(.normal, at: 1.5)
    h.midLoop(.warning, at: 2.0)
    check("only the first flap fires within the interval", h.hintsShown == [.warning])
    check("the baseline still tracks the latest observation", h.tracker.lastTier == .warning)
}

print("▶️  6. reset adopts the loaded tier without firing")
do {
    var h = Harness()
    h.tracker.reset(to: .warning)
    h.midLoop(.warning, at: 0)
    check("opening a 75% session is not a crossing", h.hintsShown.isEmpty)
    h.midLoop(.critical, at: 1)
    check("but rising further is", h.hintsShown == [.critical])
}

// MARK: - Source invariants
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
func src(_ rel: String) -> String { (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? "" }
let vm = src("Agent/Chat/AIChatViewModel.swift")
let persist = src("Agent/Chat/AIChatViewModel+Persistence.swift")
let models = src("Agent/Chat/ChatModels.swift")

print("▶️  source invariants")
check("tracker lives in ChatModels", models.contains("struct ContextTierCrossingTracker"))
check("publishContextUsage accepts live tokens", vm.contains("func publishContextUsage(liveContextTokens: Int? = nil)"))
if let r = vm.range(of: "turnUsage = streamResult.turnUsage") {
    let after = vm[r.upperBound...].prefix(900)
    check("loop observes usage right after each API call", after.contains("noteContextUsageMidLoop(liveContextTokens: turnUsage.latestContextTokens)"))
} else { check("found the per-call usage assignment", false) }
check("mid-loop path marks the shared record", vm.contains("contextTierTracker.markFired(usage.tier, now: now)"))
check("loop-end path dedups against it", vm.contains("contextTierTracker.recentlyFired(for: usage.tier, now: loopEndNow)"))
check("loop-end path keeps its original eligibility", vm.contains("if !userDidCancel, !turnWasSilentProgrammatic, inputText.isEmpty,\n                   let usage = contextUsage {"))
check("session load resets the baseline", persist.contains("contextTierTracker.reset(to: contextUsage?.tier ?? .normal)"))
check("message-side usage still only written at turn boundaries", vm.components(separatedBy: "].usage = turnUsage").count - 1 == 3)

print(failures == 0 ? "\n🎉 all checks passed" : "\n💥 \(failures) failure(s)")
exit(failures == 0 ? 0 : 1)
