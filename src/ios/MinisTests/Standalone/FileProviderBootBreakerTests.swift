// Tests for [T-ios-fp-mac-bootcrash] — the File Provider boot circuit breaker.
//
// The crash: on Macs running the iPhone build, the FP appex sometimes dies
// PRE-MAIN (SIGILL at DYLD-STUB$$NSExtensionMain, only dyld +
// libsystem_platform loaded). No line of our code runs, so nothing inside the
// extension can defend against it. `fileproviderd` then relaunches it — 5
// crashes in 8 seconds on one machine — and a REGISTERED DOMAIN is the only
// reason it keeps trying.
//
// The blunt fix ("never register on Mac", c4669fca4) shipped and was reverted
// (90803ceb3): Finder browsing works between bursts, so the extension boots
// most of the time and withholding the domain outright disables a working
// feature to silence a crash report.
//
// This breaker registers normally, watches whether the appex ever reports a
// successful boot, and withdraws the domain only for a machine demonstrably
// stuck in a boot loop. These tests pin the properties that make that safe to
// ship without being able to reproduce the crash.
//
// Standalone (`swift FileProviderBootBreakerTests.swift`) like its neighbours:
// the MinisTests target has a pre-existing compile break and the shipping type
// reaches for the App Group container.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced from FileProviderBootHealth

struct Breaker {
    static let tripThreshold = 3

    struct State { var generation: String; var pendingBoots: Int; var trippedAt: Date? }
    var state: State?
    /// Mirrors `shouldWithholdRegistration`'s isiOSAppOnMac gate.
    var onMac = true

    mutating func recordSuccessfulBoot(generation: String) {
        state = State(generation: generation, pendingBoots: 0, trippedAt: nil)
    }

    @discardableResult
    mutating func noteRegistrationAttempt(generation: String) -> Int {
        let sameGen = state?.generation == generation
        let pending = (sameGen ? (state?.pendingBoots ?? 0) : 0) + 1
        var tripped = sameGen ? state?.trippedAt : nil
        if pending >= Self.tripThreshold, tripped == nil { tripped = Date() }
        state = State(generation: generation, pendingBoots: pending, trippedAt: tripped)
        return pending
    }

    func shouldWithhold(generation: String) -> Bool {
        guard onMac else { return false }
        guard let s = state, s.generation == generation else { return false }
        return s.pendingBoots >= Self.tripThreshold
    }
}

let genA = "2026-09-21T10:00:00Z"
let genB = "2026-09-22T08:30:00Z"   // a new build / replaced bundle

print("\n[1] The healthy machine — must never be affected")

var ok = Breaker()
for i in 1...10 {
    ok.noteRegistrationAttempt(generation: genA)
    ok.recordSuccessfulBoot(generation: genA)      // appex boots every time
    check("launch \(i): domain still registered", ok.shouldWithhold(generation: genA), false)
}

print("\n[2] The transient — one bad launch must NOT trip it")
// This is the bundle-replacement window the leading theory blames (d881875e1),
// and the case the reverted blunt fix punished.

var blip = Breaker()
blip.noteRegistrationAttempt(generation: genA)      // launch fails to boot appex
check("after 1 failure: still registering", blip.shouldWithhold(generation: genA), false)
blip.noteRegistrationAttempt(generation: genA)
check("after 2 failures: still registering", blip.shouldWithhold(generation: genA), false)
blip.recordSuccessfulBoot(generation: genA)         // heals on its own
check("after it finally boots: still registering", blip.shouldWithhold(generation: genA), false)
blip.noteRegistrationAttempt(generation: genA)
checkEq("a successful boot reset the count", blip.state?.pendingBoots, 1)

print("\n[3] The crash loop — must trip")

var loop = Breaker()
loop.noteRegistrationAttempt(generation: genA)
loop.noteRegistrationAttempt(generation: genA)
check("still registering at 2", loop.shouldWithhold(generation: genA), false)
loop.noteRegistrationAttempt(generation: genA)
check("trips at the threshold (3)", loop.shouldWithhold(generation: genA))
check("records why it tripped", loop.state?.trippedAt != nil)

print("\n[4] Self-healing — one successful boot clears a tripped breaker")

loop.recordSuccessfulBoot(generation: genA)
check("un-trips after a single successful boot", loop.shouldWithhold(generation: genA), false)
checkEq("count is back to zero", loop.state?.pendingBoots, 0)
checkEq("trip marker cleared", loop.state?.trippedAt == nil, true)

print("\n[5] Generation scoping — a trip can never outlive its binary")

var stale = Breaker()
for _ in 1...5 { stale.noteRegistrationAttempt(generation: genA) }
check("tripped on the old build", stale.shouldWithhold(generation: genA))
check("a NEW build starts clean", stale.shouldWithhold(generation: genB), false)
stale.noteRegistrationAttempt(generation: genB)
checkEq("new build's count starts at 1, not 6", stale.state?.pendingBoots, 1)
check("and is not tripped", stale.shouldWithhold(generation: genB), false)

print("\n[6] Real iOS devices are never withheld from")
// The pre-main SIGILL has only ever been seen on iOS-app-on-Mac. A real device
// that fails to boot the appex should keep retrying rather than silently lose
// Files integration.

var device = Breaker(); device.onMac = false
for _ in 1...10 { device.noteRegistrationAttempt(generation: genA) }
check("never withholds on a real iOS device", device.shouldWithhold(generation: genA), false)

print("\n[7] Anti-drift — re-read the shipping sources")

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    print("  ⚠️  could not locate \(rel) — skipping its drift checks")
    return ""
}

let health = source("FileProvider/FileProviderBootHealth.swift")
if !health.isEmpty {
    checkEq("threshold is still 3", health.contains("tripThreshold = 3"), true)
    check("withholding is gated on isiOSAppOnMac",
          health.contains("guard ProcessInfo.processInfo.isiOSAppOnMac else { return false }"))
    check("a successful boot zeroes the count and the trip",
          health.contains("pendingBoots: 0, trippedAt: nil"))
    check("state is generation-scoped",
          health.contains("s.generation == generation"))
}

let ext = source("FileProvider/FileProviderExtension.swift")
if !ext.isEmpty {
    check("the appex reports its successful boot",
          ext.contains("FileProviderBootHealth.recordSuccessfulBoot("))
}

let app = source("MinisApp.swift")
if !app.isEmpty {
    check("the app consults the breaker before registering",
          app.contains("FileProviderBootHealth.shouldWithholdRegistration()"))
    check("the app counts each registration attempt",
          app.contains("FileProviderBootHealth.noteRegistrationAttempt()"))
    check("breaker check precedes the attempt count",
          app.range(of: "shouldWithholdRegistration()").map { w in
              app.range(of: "noteRegistrationAttempt()").map { n in w.lowerBound < n.lowerBound } ?? false
          } ?? false)
    check("no blanket isiOSAppOnMac disable was reintroduced",
          app.contains("if ProcessInfo.processInfo.isiOSAppOnMac {\n            NSFileProviderManager"), false)
}

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
