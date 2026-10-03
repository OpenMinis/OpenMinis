import Foundation
import AVFoundation
import UIKit

// MARK: - AudioSessionCoordinator
//
// The SINGLE owner of AVAudioSession category/active across the app. Every
// subsystem that needs audio declares an INTENT via begin()/end(); the
// coordinator picks the highest-priority active intent and applies its session
// profile (the only place setCategory / setActive is called). This removes the
// previous "everyone calls setCategory last-wins" races and the foreground-return
// silence (a stale category survived because a partial guard skipped reconfigure).
//
// Audio sources → intents (System TTS and cloud TTS are the SAME `replyTTS`
// source, just different engines resolved from the Voice Output group):
//   .capture            — mic recording (.record/.measurement)            [highest]
//   .mediaAttachment    — Markdown audio attachment / auto_play playback
//   .replyTTS           — read-replies (cloud VoiceOutputPlayer OR System AVSpeech)
//   .backgroundKeepAlive— silent keep-alive track (background only)        [lowest]
//
// `.mediaAttachment` and `.replyTTS` are mutually exclusive at the source level
// (the media player preempts TTS — see AIChatViewModel.stopSpeech on play), so in
// practice only one of them is active; the coordinator still ranks them.

@MainActor
final class AudioSessionCoordinator {
    static let shared = AudioSessionCoordinator()
    private init() { registerInterruptionObserver() }

    enum Intent: Int {
        // Higher rawValue = higher priority.
        case backgroundKeepAlive = 0
        case replyTTS = 1
        case mediaAttachment = 2
        case capture = 3
    }

    private let logger = AppLogger(category: "AudioSession")
    private var active: Set<Intent> = []

    /// True while the mic is capturing — reply TTS is suppressed in this state.
    var isCapturing: Bool { active.contains(.capture) }

    // MARK: - Public API

    /// Posted when a media attachment preempts reply TTS — the active chat VM stops
    /// its System AVSpeechSynthesizer in response (cloud TTS is stopped directly).
    static let replyTTSPreemptedNotification = Notification.Name("AudioSession.replyTTSPreempted")

    /// Declare that `intent` now needs the audio session. Idempotent.
    ///
    /// The profile is applied ASYNCHRONOUSLY (see `apply`), so on return the
    /// session may not yet carry this intent's category. That is fine for
    /// playback intents but NOT for mic capture, which must read
    /// `AVAudioEngine.inputNode.inputFormat` only once `.record` is live — use
    /// `beginAndWait` there.
    func begin(_ intent: Intent) {
        beginInternal(intent)
    }

    /// [T-voice-input-double-tap] `begin` + bounded wait for the profile to
    /// actually be applied. Returns `true` if the session reached this intent's
    /// profile within `timeout`.
    ///
    /// Why this exists: reply TTS holds `.playback`, and tapping the mic while it
    /// speaks stops TTS and begins `.capture` in the same turn. Since
    /// bf9d66f6 moved `setCategory`/`setActive` onto a serial background queue
    /// (to stop a wedged mediaserverd from tripping the 10s watchdog), `begin`
    /// returns before the switch lands. The VAD then read `inputFormat` while the
    /// session was still in TTS's `.playback` profile, got 0 channels / 0 Hz, and
    /// threw "Microphone input unavailable" — so the first tap stopped the speech
    /// but never started recording. By the second tap the queue had drained and
    /// the profile was correct, which is exactly the reported "works on the
    /// second tap" behaviour.
    ///
    /// The wait is bounded and runs on the SAME serial queue the work is enqueued
    /// on, so it cannot outlive the queued block and cannot deadlock; on timeout
    /// the caller proceeds and its own 0-channel guard still protects it. The main
    /// thread is parked for at most `timeout`, well inside the watchdog budget.
    @discardableResult
    func beginAndWait(_ intent: Intent, timeout: TimeInterval = 1.0) -> Bool {
        beginInternal(intent)
        // Nothing was queued (already in this profile and active) → already live.
        guard Self.pendingLock.withLock({ Self.pendingApplies }) > 0 else { return true }
        let sem = DispatchSemaphore(value: 0)
        Self.sessionQueue.async { sem.signal() }   // FIFO: runs after pending applies
        let hit = sem.wait(timeout: .now() + timeout) == .success
        if !hit {
            logger.error("[AudioSession] beginAndWait(\(intent)) timed out after \(timeout)s — proceeding")
        }
        return hit
    }

    private func beginInternal(_ intent: Intent) {
        // Media attachment preempts reply TTS (mutually exclusive voice content):
        // stop the cloud queue directly and notify the chat VM to stop System TTS,
        // BEFORE media takes the session. (TTS is not auto-resumed afterwards.)
        if intent == .mediaAttachment, active.contains(.replyTTS) {
            VoiceOutputPlayer.shared.stopAll()
            NotificationCenter.default.post(name: Self.replyTTSPreemptedNotification, object: nil)
        }
        let was = highest
        active.insert(intent)
        if highest != was || !sessionActive {
            apply(reason: "begin(\(intent))")
        }
    }

    /// Declare that `intent` no longer needs the session.
    func end(_ intent: Intent) {
        guard active.contains(intent) else { return }
        active.remove(intent)
        apply(reason: "end(\(intent))")
    }

    /// Re-assert the correct session on foreground return (the stale-category fix)
    /// and stop the background keep-alive track (not needed in foreground).
    func reassertForForeground() {
        active.remove(.backgroundKeepAlive)
        apply(reason: "foreground")
    }

    // MARK: - Core (the ONLY setCategory/setActive site)

    private var sessionActive = false

    /// [T-voice-mic-preempted] Error from the most recent `apply()`, or nil if it
    /// succeeded. Lets a caller that just ran `beginAndWait` tell "the session
    /// never activated" (another app owns the mic — FaceTime, a phone call, a
    /// Bluetooth device) apart from "activated fine but the input node still
    /// reported 0 channels", which have completely different user-facing causes.
    ///
    /// Written on `sessionQueue` inside apply()'s catch and read from the main
    /// actor after `beginAndWait`'s barrier, so it needs the same lock as
    /// `pendingApplies` rather than actor isolation. Cleared at the START of
    /// every apply so a stale failure can never be attributed to a later attempt.
    /// Annotated exactly like `pendingApplies`/`pendingLock` below: the lock,
    /// not actor isolation, is what makes these safe across the main actor and
    /// `sessionQueue`.
    nonisolated private static let lastApplyLock = NSLock()
    nonisolated(unsafe) private static var _lastApplyError: Error?

    /// The most recent activation failure, or nil if the last apply succeeded.
    /// Only meaningful immediately after `beginAndWait` returns.
    nonisolated static var lastApplyError: Error? {
        lastApplyLock.withLock { _lastApplyError }
    }

    private var highest: Intent? { active.max(by: { $0.rawValue < $1.rawValue }) }

    private func profile(for intent: Intent) -> (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions) {
        switch intent {
        case .capture:
            return (.record, .measurement, [])
        case .mediaAttachment:
            return (.playback, .default, [.duckOthers])
        case .replyTTS:
            return (.playback, .spokenAudio, [.duckOthers])
        case .backgroundKeepAlive:
            return (.playback, .default, [.mixWithOthers])
        }
    }

    /// Serial queue that owns every blocking AVAudioSession mutation.
    ///
    /// [T-audiosession-setactive-watchdog] `setActive` / `setCategory` forward to
    /// mediaserverd over an NSXPCConnection and wait for a SYNCHRONOUS reply.
    /// When that daemon is wedged the reply never lands, and because this class
    /// is `@MainActor` the call was blocking the main thread — the app was
    /// SIGKILLed by the 10s scene-update watchdog (0x8BADF00D, incident
    /// EFB2E09B: app CPU ~1%, main thread parked in
    /// `__NSXPCCONNECTION_IS_WAITING_FOR_A_SYNCHRONOUS_REPLY__` under
    /// `-[AVAudioSession privateSetActive:withOptions:error:core:]`, reached from
    /// a Combine sink delivered on the main queue).
    ///
    /// Serial, so the ordering guarantees the old main-thread-only code relied on
    /// (deactivate-then-activate, category-before-active) still hold. AVAudioSession
    /// is thread-safe; it was never the main thread that made these calls correct.
    private static let sessionQueue = DispatchQueue(label: "com.openminis.audiosession.apply")

    /// Number of profile switches enqueued on `sessionQueue` but not yet applied.
    /// Written from BOTH the main actor (enqueue) and the session queue
    /// (completion), so it lives outside the actor's isolation with its own lock
    /// — see `beginAndWait`. `nonisolated` on the lock too, otherwise the
    /// background completion block can't touch it.
    nonisolated(unsafe) private static var pendingApplies = 0
    nonisolated private static let pendingLock = NSLock()

    private func apply(reason: String) {
        // Decide WHAT to do on the actor (reads `active` / `sessionActive`), then
        // perform the blocking AVAudioSession work off the main thread. State is
        // updated optimistically here so concurrent begin()/end() calls see the
        // intended session state without waiting on the daemon round-trip.
        guard let top = highest else {
            if sessionActive {
                sessionActive = false
                logger.info("[AudioSession] \(reason) → idle, deactivating (async)")
                Self.sessionQueue.async {
                    try? AVAudioSession.sharedInstance()
                        .setActive(false, options: .notifyOthersOnDeactivation)
                }
            }
            return
        }
        let (cat, mode, opts) = profile(for: top)
        let session = AVAudioSession.sharedInstance()
        // FULL compare (category + mode + options), not just category — a partial
        // guard let BKA's `.mixWithOthers` profile poison reply TTS before.
        // These are plain property reads (no IPC), so they stay on the actor.
        let needsReconfig = session.category != cat
            || session.mode != mode
            || session.categoryOptions != opts
        let needsActivate = !sessionActive || needsReconfig
        guard needsReconfig || needsActivate else { return }
        if needsActivate { sessionActive = true }

        let log = logger
        // Tracks queued-but-unapplied profile switches so `beginAndWait` knows
        // whether there is anything to wait for. Incremented here on the main
        // actor and decremented ON THE SESSION QUEUE (not via a hop back to the
        // actor, which could land after the waiter's barrier and read stale).
        Self.pendingLock.withLock { Self.pendingApplies += 1 }
        let t0 = CFAbsoluteTimeGetCurrent()
        Self.sessionQueue.async {
            // [T-voice-mic-preempted] Clear before attempting, so a success
            // wipes any earlier failure and a reader can never see a stale one.
            Self.lastApplyLock.withLock { Self._lastApplyError = nil }
            do {
                if needsReconfig {
                    try session.setCategory(cat, mode: mode, options: opts)
                }
                if needsActivate {
                    try session.setActive(true)
                }
                let ms = (CFAbsoluteTimeGetCurrent() - t0) * 1000
                log.info("[VoiceInputDebug][AudioSession] \(reason) → \(top) (\(cat.rawValue)/\(mode.rawValue)) applied in \(String(format: "%.0f", ms))ms")
            } catch {
                let ms = (CFAbsoluteTimeGetCurrent() - t0) * 1000
                log.error("[VoiceInputDebug][AudioSession] \(reason) apply FAILED after \(String(format: "%.0f", ms))ms: \(error.localizedDescription)")
                // [T-voice-mic-preempted] Publish the reason so the capture path
                // can report "mic busy" instead of a generic parse failure.
                Self.lastApplyLock.withLock { Self._lastApplyError = error }
                // Roll back the optimistic flag so the next begin() retries the
                // activation instead of assuming the session is already live.
                Task { @MainActor in self.sessionActive = false }
            }
            Self.pendingLock.withLock { Self.pendingApplies -= 1 }
        }
    }

    // MARK: - Unified interruption handling (replaces per-subsystem observers)

    /// Set by subsystems so the coordinator can resume them after an interruption.
    var onInterruptionEnded: (() -> Void)?

    /// True while TTS was paused by us because external audio took priority
    /// (interruption or secondary-audio-silence hint). Guards the resume so we
    /// never resume playback the USER paused.
    private var pausedByExternalAudio = false

    private func registerInterruptionObserver() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification, object: nil)
        // [T-tts-pause-on-external-record] A third-party keyboard's dictation
        // (or any other app recording) runs its OWN audio session alongside our
        // .playback/.spokenAudio one — iOS does NOT post an
        // interruptionNotification for that coexistence, so TTS kept talking
        // and the keyboard transcribed our own speech. The system DOES post
        // silenceSecondaryAudioHintNotification (.begin) when higher-priority
        // audio (recording/call) should dominate: pause TTS there, resume on
        // .end.
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleSilenceSecondaryAudioHint(_:)),
            name: AVAudioSession.silenceSecondaryAudioHintNotification, object: nil)
        // [T-tts-pause-inapp-keyboard-dictation] A third-party keyboard's
        // dictation started FROM INSIDE our app shares this process's audio
        // context — iOS does NOT post a silence-secondary-audio hint for it (that
        // notification fires only when ANOTHER app takes over). But the keyboard's
        // record session still triggers a route change, and the authoritative
        // `secondaryAudioShouldBeSilencedHint` property flips true while it holds
        // the mic. Poll that property on every route change and pause/resume TTS
        // accordingly, so in-app dictation gets the same treatment as cross-app
        // recording. (Reason codes alone are unreliable — categoryChange /
        // routeConfigurationChange fire for many unrelated cases — so we trust the
        // property, not the reason.)
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification, object: nil)
    }

    @objc private func handleRouteChange(_ note: Notification) {
        let shouldSilence = AVAudioSession.sharedInstance().secondaryAudioShouldBeSilencedHint
        if shouldSilence {
            pauseTTSForExternalAudio(reason: "route-change(secondary-silence)")
        } else {
            resumeTTSAfterExternalAudio(reason: "route-change(secondary-clear)")
        }
    }

    /// Pause reply TTS because external audio (interruption / recording in
    /// another app) needs to dominate. `pause()` keeps the synthesis queue so
    /// playback can pick up where it left off — deliberately NOT stopAll().
    ///
    /// The intent flag `pausedByExternalAudio` is latched INDEPENDENTLY of the
    /// player's physical `isPaused`: an external recording frequently stops our
    /// AVAudioPlayer BEFORE the pause signal arrives, so at this point there may
    /// be no live player to physically pause — but we still must remember that
    /// WE own the pause, or the matching `.end` won't resume. `pause()` now
    /// latches `isPaused` even with no live player and gates `pumpPlayback`, so
    /// the queue can't sneak a unit out under the recorder.
    private func pauseTTSForExternalAudio(reason: String) {
        // Idempotent: repeated BEGIN signals (interruption + secondary-hint can
        // both fire) must not clear an already-recorded pause intent.
        guard !pausedByExternalAudio else { return }
        pausedByExternalAudio = true
        VoiceOutputPlayer.shared.pause()
        logger.info("[AudioSession] \(reason) → TTS paused (external audio)")
    }

    /// Resume reply TTS if (and only if) WE paused it for external audio.
    private func resumeTTSAfterExternalAudio(reason: String) {
        guard pausedByExternalAudio else { return }
        pausedByExternalAudio = false
        VoiceOutputPlayer.shared.resume()
        logger.info("[AudioSession] \(reason) → TTS resumed")
    }

    @objc private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            logger.info("[AudioSession] interruption began")
            sessionActive = false   // system deactivated us
            // Previously we only flagged the session inactive; the TTS queue
            // kept "playing" into a dead session. Pause it so the queue
            // survives and can resume when the interruption ends.
            pauseTTSForExternalAudio(reason: "interruption-began")
        case .ended:
            logger.info("[AudioSession] interruption ended → re-asserting")
            apply(reason: "interruption-ended")
            resumeTTSAfterExternalAudio(reason: "interruption-ended")
            onInterruptionEnded?()
        @unknown default:
            break
        }
    }

    @objc private func handleSilenceSecondaryAudioHint(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt,
              let type = AVAudioSession.SilenceSecondaryAudioHintType(rawValue: raw) else { return }
        switch type {
        case .begin:
            logger.info("[AudioSession] silence-secondary-audio hint BEGIN")
            pauseTTSForExternalAudio(reason: "secondary-audio-hint")
        case .end:
            logger.info("[AudioSession] silence-secondary-audio hint END")
            resumeTTSAfterExternalAudio(reason: "secondary-audio-hint-end")
        @unknown default:
            break
        }
    }
}
