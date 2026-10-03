// Regression test for [T-voice-vad-start-leak] — VoiceActivityDetector.start()
// must roll back configureSession()'s side effects on EVERY failure path.
//
// From the issue #283 logs (microphone held by FaceTime):
//     suspendSilentAudioForMedia: count 2→3→4→5→6→7→8   (6×, caller=VAD.capture)
//     resumeSilentAudioForMedia:  count 8→7             (1×)
// Each failed start leaked one suspend and left the `.capture` intent held.
//
// Standalone (`swift VADStartRollbackTests.swift`) because the MinisTests
// target has a pre-existing compile break — same rationale and directory as
// CJKPaginationThresholdTests / SkillDescriptionStaleTests.
//
// The models below mirror the SHIPPED semantics of the three collaborators:
//   * BackgroundKeepAliveManager.resumeSilentAudioForMedia clamps at 0
//     (`max(0, count - 1)`)
//   * AudioSessionCoordinator.end(_:) no-ops when the intent is not held
//     (`guard active.contains(intent) else { return }`)
//   * start() arms a `defer` that calls tearDown() unless `startedCleanly`
// so this pins the invariant, not a paraphrase of the control flow.

import Foundation

// MARK: - Models of the shipped collaborators

final class KeepAlive {
    private(set) var suspendCount = 0
    func suspend() { suspendCount += 1 }
    func resume() { suspendCount = max(0, suspendCount - 1) }   // clamps, per shipped code
}

final class SessionCoordinator {
    private(set) var active: Set<String> = []
    func begin(_ intent: String) { active.insert(intent) }
    func end(_ intent: String) {
        guard active.contains(intent) else { return }           // no-ops, per shipped code
        active.remove(intent)
    }
}

struct StartFailure: Error { let step: String }

/// Mirrors the shipped start(): configureSession() takes the resources, a
/// `defer` releases them unless the run reached `startedCleanly = true`.
final class Detector {
    let keepAlive: KeepAlive
    let coordinator: SessionCoordinator
    private(set) var isRunning = false

    init(keepAlive: KeepAlive, coordinator: SessionCoordinator) {
        self.keepAlive = keepAlive
        self.coordinator = coordinator
    }

    private func configureSession() {
        keepAlive.suspend()
        coordinator.begin("capture")
    }

    private func tearDown() {
        coordinator.end("capture")
        keepAlive.resume()
        isRunning = false
    }

    /// `failAt` names the step that throws: "setup", "engineError",
    /// "objcException", or nil for the success path.
    func start(failAt: String?) throws {
        guard !isRunning else { return }
        configureSession()

        var startedCleanly = false
        defer { if !startedCleanly { tearDown() } }

        if failAt == "setup" { throw StartFailure(step: "setupEngineAndVAD") }
        if failAt == "engineError" { throw StartFailure(step: "audioEngine.start") }
        if failAt == "objcException" { throw StartFailure(step: "objc exception") }

        isRunning = true
        startedCleanly = true
    }
}

// MARK: - Harness

var failures = 0
func check(_ cond: Bool, _ what: String) {
    if cond { print("  ok   \(what)") } else { print("  FAIL \(what)"); failures += 1 }
}

// 1. Every individual failure path leaves both counters balanced.
for step in ["setup", "engineError", "objcException"] {
    let ka = KeepAlive(), co = SessionCoordinator()
    let d = Detector(keepAlive: ka, coordinator: co)
    do { try d.start(failAt: step); check(false, "\(step): should have thrown") }
    catch {
        check(ka.suspendCount == 0, "\(step): suspendCount back to 0")
        check(co.active.isEmpty, "\(step): .capture released")
        check(!d.isRunning, "\(step): not running")
    }
}

// 2. THE REPORTED SCENARIO: six failed retries must not accumulate.
//    Pre-fix this ended at suspendCount == 6 with .capture stuck held.
do {
    let ka = KeepAlive(), co = SessionCoordinator()
    let d = Detector(keepAlive: ka, coordinator: co)
    for _ in 0..<6 { try? d.start(failAt: "setup") }
    check(ka.suspendCount == 0, "6 failed retries: suspendCount == 0 (was 6 pre-fix)")
    check(co.active.isEmpty, "6 failed retries: no stuck .capture intent")
}

// 3. Success must NOT roll back — the session stays claimed while recording.
do {
    let ka = KeepAlive(), co = SessionCoordinator()
    let d = Detector(keepAlive: ka, coordinator: co)
    try? d.start(failAt: nil)
    check(ka.suspendCount == 1, "success: suspend still held while running")
    check(co.active.contains("capture"), "success: .capture still held while running")
    check(d.isRunning, "success: isRunning true")
}

// 4. Retry-after-failure must still be able to start (rollback left it usable).
do {
    let ka = KeepAlive(), co = SessionCoordinator()
    let d = Detector(keepAlive: ka, coordinator: co)
    try? d.start(failAt: "setup")
    try? d.start(failAt: nil)
    check(d.isRunning, "failure then success: running")
    check(ka.suspendCount == 1, "failure then success: exactly one suspend held")
}

// 5. Double rollback would be harmless — both halves clamp/no-op. Guards
//    against a future branch calling tearDown() *and* falling into the defer.
do {
    let ka = KeepAlive(), co = SessionCoordinator()
    ka.suspend(); co.begin("capture")
    co.end("capture"); ka.resume()
    co.end("capture"); ka.resume()      // second rollback
    check(ka.suspendCount == 0, "double rollback: no underflow below 0")
    check(co.active.isEmpty, "double rollback: end() is a no-op when unheld")
}

print(failures == 0 ? "\nALL PASS" : "\n\(failures) FAILURE(S)")
exit(failures == 0 ? 0 : 1)
