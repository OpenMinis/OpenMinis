// Tests for [T-voice-interrupt-cannot-restart] — after an audio interruption
// (FaceTime / phone call / Siri) mid-dictation, voice input must be startable
// again.
//
// Bug: recording, FaceTime interrupts, and from then on the mic is dead — no
// error, no capture, taps do nothing.
//
// Root cause: `isRunning` stays true across an interruption ON PURPOSE (that is
// what lets `.ended` resume the same capture), but the entry points treated it
// as proof that capture was live:
//
//   VoiceActivityDetector.start()   `guard !isRunning else { return }`  ← silent
//   SpeechRecognitionManager        `guard state == .idle else { return }` ← silent
//   VoiceInputPanel.handleMainButtonTap  `if vad.isRunning { ...pause... }`
//
// `.ended` is not guaranteed: iOS does not post it to a SUSPENDED app, and
// answering a FaceTime call backgrounds us. The 15s background auto-stop timer
// cannot rescue it either — Timers do not fire while suspended. A second path
// needs no backgrounding at all: `.began` arriving while `audioEngine.isRunning`
// was still true was dismissed as "spurious" WITHOUT arming the resume, so the
// matching `.ended` had nothing to do and no second `.began` ever came.
//
// Standalone (`swift VoiceInterruptRestartTests.swift`) like its neighbours:
// the MinisTests target has a pre-existing compile break and these types pull
// in AVFoundation + the VAD library. Sections [1]-[3] exercise reproduced
// state machines; section [4] re-reads the shipping sources so they cannot
// drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Reproduced from VoiceActivityDetector

/// `fixed == false` reproduces the shipped behaviour; `true` is the new logic.
final class VAD {
    let fixed: Bool
    init(fixed: Bool) { self.fixed = fixed }

    var isRunning = false
    var engineRunning = false
    var interruptedWhileRunning = false
    /// Whether the hardware will accept a start right now (a live call → false).
    var micAvailable = true
    /// Unbalanced AudioSessionCoordinator/keep-alive claims.
    var sessionClaims = 0

    /// The honest signal the fix introduces.
    var isCapturing: Bool { isRunning && engineRunning }

    @discardableResult
    func start() -> Bool {
        if fixed {
            if isRunning {
                if engineRunning { return false }   // genuinely capturing
                tearDown()                          // reclaim the stale state
            }
        } else {
            if isRunning { return false }           // silent no-op — the bug
        }
        guard micAvailable else { return false }
        sessionClaims += 1                          // configureSession()
        isRunning = true; engineRunning = true
        interruptedWhileRunning = false
        return true
    }

    func tearDown() {
        isRunning = false; engineRunning = false
        interruptedWhileRunning = false
        if sessionClaims > 0 { sessionClaims -= 1 }
    }

    func interruptionBegan() {
        guard isRunning else { return }
        if fixed {
            interruptedWhileRunning = true          // arm FIRST
            if engineRunning { return }             // engine survived, keep going
            engineRunning = false
        } else {
            if engineRunning { return }             // "spurious" — resume NOT armed
            interruptedWhileRunning = true
            engineRunning = false
        }
    }

    func interruptionEnded() {
        guard interruptedWhileRunning else { return }
        interruptedWhileRunning = false
        if fixed, isRunning, engineRunning { return }   // nothing to rebuild
        attemptResume()
    }

    private func attemptResume() {
        engineRunning = false
        if fixed { if sessionClaims > 0 { sessionClaims -= 1 } }  // release before re-claim
        sessionClaims += 1                                        // configureSession()
        guard micAvailable else { tearDown(); return }
        isRunning = true; engineRunning = true
    }

    /// The system stops our engine without (or before) a notification.
    func systemStopsEngine() { engineRunning = false }
}

print("\n[1] The reported flow — FaceTime interrupts, `.ended` never arrives")

for fixed in [false, true] {
    let tag = fixed ? "AFTER " : "BEFORE"
    let v = VAD(fixed: fixed)
    v.start()
    v.systemStopsEngine()        // FaceTime takes the mic
    v.interruptionBegan()
    // User answers the call; app is suspended, so no `.ended` is delivered and
    // the 15s background timer never fires. Later they come back and tap.
    let restarted = v.start()
    if fixed {
        check("\(tag): the mic restarts after the call", restarted)
        check("\(tag): capture is actually live", v.isCapturing)
    } else {
        check("\(tag): restart is silently refused (bug reproduced)", restarted, false)
        check("\(tag): left wedged — 'running' with a dead engine",
              v.isRunning && !v.engineRunning)
    }
}

print("\n[2] The spurious-interruption path — no backgrounding needed")

for fixed in [false, true] {
    let tag = fixed ? "AFTER " : "BEFORE"
    let v = VAD(fixed: fixed)
    v.start()
    v.interruptionBegan()        // arrives while the engine still looks alive
    v.systemStopsEngine()        // ...and the system kills it right after
    // Pre-fix this is FALSE (the "spurious" return skipped arming) and
    // post-fix TRUE — the whole difference between the two paths.
    check(fixed ? "AFTER : `.began` arms the resume even with the engine alive"
                : "BEFORE: `.began` did NOT arm the resume (bug reproduced)",
          v.interruptedWhileRunning, fixed)
    v.interruptionEnded()        // pre-fix: nothing armed, so this is a no-op
    let restarted = v.start()
    if fixed {
        check("\(tag): recovers", restarted || v.isCapturing)
    } else {
        check("\(tag): still wedged (bug reproduced)", restarted, false)
    }
}

print("\n[3] Interrupt → resume must not leak session claims")

let leak = VAD(fixed: false)
leak.start()
for _ in 0..<3 {                 // three call/Siri interruptions in one recording
    leak.systemStopsEngine(); leak.interruptionBegan(); leak.interruptionEnded()
}
check("BEFORE: claims leaked (count > 1)", leak.sessionClaims > 1)

let noLeak = VAD(fixed: true)
noLeak.start()
for _ in 0..<3 {
    noLeak.systemStopsEngine(); noLeak.interruptionBegan(); noLeak.interruptionEnded()
}
check("AFTER: exactly one claim held while capturing", noLeak.sessionClaims == 1)
noLeak.tearDown()
check("AFTER: all claims released on stop", noLeak.sessionClaims == 0)

print("\n[4] The mic button must not treat a dead capture as 'pause'")

// Reproduces VoiceInputPanel.handleMainButtonTap's branch condition.
func tapTakesPauseBranch(useIsCapturing: Bool, v: VAD) -> Bool {
    useIsCapturing ? v.isCapturing : v.isRunning
}
let wedged = VAD(fixed: true)
wedged.start()
wedged.systemStopsEngine()
wedged.interruptionBegan()
check("BEFORE: tap is misread as 'pause' on a dead capture",
      tapTakesPauseBranch(useIsCapturing: false, v: wedged))
check("AFTER: tap correctly starts instead",
      tapTakesPauseBranch(useIsCapturing: true, v: wedged), false)

let live = VAD(fixed: true)
live.start()
check("a genuinely live capture still pauses on tap",
      tapTakesPauseBranch(useIsCapturing: true, v: live))

print("\n[5] Anti-drift — re-read the shipping sources")

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    for prefix in ["", "../", "../../", "../../../"] {
        if let s = try? String(contentsOfFile: root + "/" + prefix + rel, encoding: .utf8) { return s }
    }
    print("  ⚠️  could not locate \(rel) — skipping its drift checks")
    return ""
}

let vadSrc = source("Providers/Voice/VoiceActivityDetector.swift")
if !vadSrc.isEmpty {
    check("start() no longer bails on a bare isRunning",
          vadSrc.contains("func start() throws {\n        guard !isRunning else { return }"), false)
    check("start() reconciles against the engine",
          vadSrc.contains("guard !audioEngine.isRunning else { return }"))
    check("isCapturing exists for callers to branch on",
          vadSrc.contains("var isCapturing: Bool { isRunning && audioEngine.isRunning }"))
    check("`.began` arms the resume before the spurious check",
          vadSrc.range(of: "interruptedWhileRunning = true").map { a in
              vadSrc.range(of: "if audioEngine.isRunning {").map { b in a.lowerBound < b.lowerBound } ?? false
          } ?? false)
    check("resume releases session claims before re-taking them",
          vadSrc.contains("releaseSessionClaims()") && vadSrc.contains("private func releaseSessionClaims()"))
}

let speechSrc = source("Agent/Speech/SpeechRecognitionManager.swift")
if !speechSrc.isEmpty {
    check("speech manager observes interruptions at all",
          speechSrc.contains("AVAudioSession.interruptionNotification"))
    check("speech manager reconciles a stale recording state",
          speechSrc.contains("guard !audioEngine.isRunning else { return }"))
}

let panelSrc = source("Views/Chat/Voice/VoiceInputPanel.swift")
if !panelSrc.isEmpty {
    check("mic button branches on isCapturing", panelSrc.contains("if vad.isCapturing {"))
    check("foreground return reconciles a capture that died while away",
          panelSrc.contains("guard vad.isRunning, !vad.isCapturing else { return }"))
}

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
