import ActivityKit
import AVFoundation
import Combine
import CoreLocation
import Foundation
import SwiftUI
import UserNotifications
import os.log

private let logger = AppLogger(category: "BackgroundKeepAlive")

// MARK: - Background Keep Alive Manager

@MainActor
final class BackgroundKeepAliveManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = BackgroundKeepAliveManager()

    /// True when there are active sessions and enhanced background is enabled.
    /// Used by `waitIfBackgroundSuspended()` to skip suspension.
    @Published private(set) var isActive = false

    /// [T-keepalive-survival-tier] Background-survival capability shown in
    /// Settings → Background → Status. Independent of whether a task is
    /// running right now — it answers "if the app backgrounds at this moment,
    /// how long can it keep working": every iOS app gets only the standard
    /// ~30s grace window unless one of the long-lived legs is enabled.
    enum BackgroundSurvivalTier: Equatable {
        /// Only the standard iOS background grace window (~30 seconds).
        case short
        /// Long-lived, via whichever legs are currently enabled:
        /// location = enhanced background (coarse-location heartbeat, needs
        /// permission); audio = background audio session (TTS / playback).
        case extended(location: Bool, audio: Bool)
    }

    /// Reactive: all inputs are @Published on this ObservableObject, so the
    /// Settings row re-renders on toggle/permission changes automatically.
    var survivalTier: BackgroundSurvivalTier {
        let locationLeg = enhancedBackgroundEnabled
            && (locationAuthStatus == .authorizedAlways || locationAuthStatus == .authorizedWhenInUse)
        let audioLeg = backgroundSpeakEnabled
        if locationLeg || audioLeg {
            return .extended(location: locationLeg, audio: audioLeg)
        }
        return .short
    }

    @Published var enhancedBackgroundEnabled: Bool = UserDefaults.standard.bool(forKey: "enhancedBackgroundExecution") {
        didSet {
            UserDefaults.standard.set(enhancedBackgroundEnabled, forKey: "enhancedBackgroundExecution")
            // Turning on the master switch also opts the user into
            // Background Speak — without it, TTS narration cuts off the
            // instant the app backgrounds, which defeats the typical
            // "enable enhanced background so my long task can keep
            // narrating" intent users have when flipping this from the
            // interruption banner. Off → on only; flipping the master
            // back off leaves Background Speak alone so the user's
            // own choice isn't clobbered (T-enhanced-bg-auto-speak).
            if enhancedBackgroundEnabled && !backgroundSpeakEnabled {
                backgroundSpeakEnabled = true
            }
        }
    }

    @Published var locationTrackingEnabled: Bool = UserDefaults.standard.bool(forKey: "locationTrackingEnabled") {
        didSet { UserDefaults.standard.set(locationTrackingEnabled, forKey: "locationTrackingEnabled") }
    }

    /// [T-live-activity-toggle] User switch for the agent-progress Live
    /// Activity (Lock Screen / Dynamic Island). Defaults to ON — the key is
    /// absent until the user first flips it, so read via object(forKey:).
    /// AgentLiveActivityManager gates its start/update entry points on this;
    /// flipping OFF also tears down any currently-displayed activity so the
    /// Lock Screen doesn't keep a stale card.
    @Published var liveActivityEnabled: Bool = (UserDefaults.standard.object(forKey: "liveActivityEnabled") as? Bool) ?? true {
        didSet {
            guard liveActivityEnabled != oldValue else { return }
            UserDefaults.standard.set(liveActivityEnabled, forKey: "liveActivityEnabled")
            if liveActivityEnabled {
                // Re-enabled mid-task: restore the activity for any running sessions.
                updateLiveActivityIfNeeded(source: "liveActivityToggleOn")
            } else {
                AgentLiveActivityManager.shared.endActivity()
            }
        }
    }

    @Published var backgroundSpeakEnabled: Bool = UserDefaults.standard.bool(forKey: "backgroundSpeakEnabled") {
        didSet { UserDefaults.standard.set(backgroundSpeakEnabled, forKey: "backgroundSpeakEnabled") }
    }

    /// True only when the configuration can actually keep the app alive in the
    /// background. The master switch alone is not enough: silent-audio
    /// keep-alive (the mechanism that prevents background-task expiry) only
    /// plays when Background Speak is also on (see `evaluateSilentAudio`).
    /// So "master on but Background Speak off" still gets interrupted — and
    /// must be treated as "enhanced background NOT effective" everywhere we
    /// decide whether to count interruptions / surface the reminder banner.
    var enhancedBackgroundEffective: Bool {
        enhancedBackgroundEnabled && backgroundSpeakEnabled
    }

    /// When enabled, posts local notifications when agent tasks start and complete.
    @Published var backgroundNotificationsEnabled: Bool = {
        // Default to true if key hasn't been set
        UserDefaults.standard.object(forKey: "backgroundNotificationsEnabled") == nil
            ? true
            : UserDefaults.standard.bool(forKey: "backgroundNotificationsEnabled")
    }() {
        didSet { UserDefaults.standard.set(backgroundNotificationsEnabled, forKey: "backgroundNotificationsEnabled") }
    }

    /// [T-ios-live-activity-privacy-mode] When enabled, the Live Activity, the
    /// Dynamic Island and background task notifications stop surfacing session
    /// content — no session title, tool status/icon or reply summary. They
    /// degrade to a neutral progress report: completed/total task count plus the
    /// elapsed timer.
    ///
    /// Deliberately NOT related to the pre-existing `envvars.privacyMode`
    /// (ConfigRegistry / EnvVarPrivacyStore), which masks environment-variable
    /// values in the config UI — different concept, hence the distinct
    /// `liveActivityPrivacyMode` key to avoid conflation.
    ///
    /// Defaults to OFF so existing installs keep their current behavior.
    @Published var liveActivityPrivacyMode: Bool =
        UserDefaults.standard.bool(forKey: "liveActivityPrivacyMode") {
        didSet { UserDefaults.standard.set(liveActivityPrivacyMode, forKey: "liveActivityPrivacyMode") }
    }

    // MARK: - Speech Settings

    /// Speech rate (0.0–1.0). Default is AVSpeechUtteranceDefaultSpeechRate (0.5).
    @Published var speechRate: Float = UserDefaults.standard.object(forKey: "speechRate") as? Float ?? AVSpeechUtteranceDefaultSpeechRate {
        didSet { UserDefaults.standard.set(speechRate, forKey: "speechRate") }
    }

    /// Pitch multiplier (0.5–2.0). Default is 1.0.
    @Published var speechPitch: Float = UserDefaults.standard.object(forKey: "speechPitch") as? Float ?? 1.0 {
        didSet { UserDefaults.standard.set(speechPitch, forKey: "speechPitch") }
    }

    /// Volume (0.0–1.0). Default is 1.0.
    @Published var speechVolume: Float = UserDefaults.standard.object(forKey: "speechVolume") as? Float ?? 1.0 {
        didSet { UserDefaults.standard.set(speechVolume, forKey: "speechVolume") }
    }

    /// Voice identifier for Chinese TTS. nil = system default.
    @Published var speechVoiceZh: String = UserDefaults.standard.string(forKey: "speechVoiceZh") ?? "" {
        didSet { UserDefaults.standard.set(speechVoiceZh, forKey: "speechVoiceZh") }
    }

    /// Voice identifier for English TTS. nil = system default.
    @Published var speechVoiceEn: String = UserDefaults.standard.string(forKey: "speechVoiceEn") ?? "" {
        didSet { UserDefaults.standard.set(speechVoiceEn, forKey: "speechVoiceEn") }
    }

    /// Reset all speech settings to defaults.
    func resetSpeechSettings() {
        speechRate = AVSpeechUtteranceDefaultSpeechRate
        speechPitch = 1.0
        speechVolume = 1.0
        speechVoiceZh = ""
        speechVoiceEn = ""
    }

    /// Build a voice for the given language, respecting user's voice selection.
    func voiceForLanguage(_ lang: String) -> AVSpeechSynthesisVoice? {
        let identifier = lang.hasPrefix("zh") ? speechVoiceZh : speechVoiceEn
        if !identifier.isEmpty, let voice = AVSpeechSynthesisVoice(identifier: identifier) {
            return voice
        }
        return AVSpeechSynthesisVoice(language: lang)
    }

    @Published private(set) var locationAuthStatus: CLAuthorizationStatus = .notDetermined

    /// Timer for periodic Live Activity updates.
    private var updateTimer: Timer?
    private var cancellables = Set<AnyCancellable>()
    /// [T-ios-scene-create-watchdog-corelocation] `lazy`, not a stored `let`:
    /// allocating a CLLocationManager opens a connection to locationd, so a
    /// plain stored property would drag that IPC into this singleton's `init()`
    /// — i.e. into whatever thread first touches `.shared`. Created on first
    /// real use (from `configureLocationManager()` onward), never at init.
    private lazy var locationManager = CLLocationManager()
    private var locationUpdating = false
    private var locationTimer: Timer?
    private var appIsInBackground = false

    // MARK: - CLBackgroundActivitySession (iOS 17+)
    private var bgActivitySession: Any?
    private var liveUpdatesTask: Task<Void, Never>?
    private var lastLocationLAUpdate: Date = .distantPast
    private static let locationLAMinInterval: TimeInterval = 5.0

    /// [T-ios-bg-location-arm-delay] Gate for background location keep-alive.
    /// Location updates + the CLBackgroundActivitySession only start once this
    /// is true, which happens `backgroundLocationArmDelay` seconds AFTER the app
    /// enters the background — so a brief background blip (or normal foreground
    /// use) never spins up location at all. Reset to false on foreground return.
    private var backgroundLocationArmed = false
    private var backgroundLocationArmTimer: Timer?
    /// How long to wait after backgrounding before arming location keep-alive.
    private static let backgroundLocationArmDelay: TimeInterval = 15.0

    /// [T-ios-scenephase-active-sigkill] True between a `willEnterForeground`
    /// notification and its deferred (yielded) handler running. Replaces the
    /// `applicationState != .background` guard, which was unreliable because
    /// `.inactive` is ambiguous — it can mean "entering foreground" OR "entering
    /// background". A synchronous `didEnterBackground` clears this flag, so a
    /// rapid bg→fg→bg blip cancels the in-flight foreground work cleanly. Also
    /// gates `reevaluate`'s @Published writes off the transition tick.
    private var pendingForegroundTransition = false

    // MARK: - Silent Audio Keep-Alive
    private var audioEngine: AVAudioEngine?
    private var silentPlayerNode: AVAudioPlayerNode?
    private var silentAudioActive = false

    /// [T-bg-keepalive-debounce] Pending debounced stop of silent audio. When
    /// the app receives a transient `foreground` signal (e.g. a call ending →
    /// Minis briefly foregrounds → immediately backgrounds again), stopping the
    /// keep-alive audio synchronously releases the audio session right before
    /// we go back to background, and iOS then kills the process. While sessions
    /// are active we instead DELAY the stop; if a `background` signal arrives
    /// within the window we cancel it and keep the audio running. nil when no
    /// stop is pending.
    private var pendingSilentAudioStop: DispatchWorkItem?
    /// Debounce window for a foreground-triggered stop while sessions are active.
    private static let silentAudioStopDebounce: TimeInterval = 1.5

    /// [T-bg-keepalive-debounce] How many consecutive `setActive(true)` failures
    /// we've retried for the current start attempt. Reset on success / when the
    /// budget is exhausted.
    private var silentAudioActivationRetries = 0
    private static let maxActivationRetries = 3
    private static let activationRetryDelay: TimeInterval = 0.5

    /// Wall-clock when the current debounced stop was scheduled, for logging the
    /// remaining window. nil when no stop is pending.
    private var pendingStopScheduledAt: Date?

    /// [T-ios-bg-idle-grace] One-shot timer holding keep-alive briefly after the
    /// last session ends. Non-nil ONLY while that window is open. A
    /// `DispatchSourceTimer` (not `Timer`) so it still fires with the run loop
    /// suspended in the background, and deliberately non-repeating so a missed
    /// cleanup can fire at most once. See `beginIdleGraceIfNeeded` for the full
    /// list of paths that clear it.
    private var idleGraceTimer: DispatchSourceTimer?
    /// When the current idle grace was armed, for log/diagnostic elapsed times.
    private var idleGraceStartedAt: Date?
    /// How long keep-alive survives after going idle. Distinct from — and much
    /// longer than — `silentAudioStopDebounce` (1.5s), which covers a transient
    /// foreground blip while sessions are STILL active. The two never overlap.
    private static let idleGracePeriod: TimeInterval = 60.0

    // MARK: - [BKA] Diagnostic snapshot state (read by CrashReporter)

    /// Last `[BKA][...]` action recorded, e.g. "Stop(reason=foreground,debounce=pending)".
    private(set) var lastBKAEvent: String?
    private(set) var lastBKAEventAt: Date?
    /// Last lifecycle transition, e.g. "Background" / "Foreground".
    private(set) var lastLifecycle: String?
    private(set) var lastLifecycleAt: Date?
    /// Audio-interruption state: "none" / "began" / "ended", with timestamp.
    private(set) var lastAudioInterruption: String = "none"
    private(set) var lastAudioInterruptionAt: Date?

    /// True if the keep-alive engine is genuinely running right now.
    var silentAudioIsPlaying: Bool { audioEngine?.isRunning ?? false }
    /// True if the shared audio session currently holds an active route.
    var audioSessionIsActive: Bool { silentAudioActive }
    /// True if a debounced stop is queued.
    var stopDebouncePending: Bool { pendingSilentAudioStop != nil }

    /// Record the most recent BKA action for the crash snapshot + log it.
    private func recordBKAEvent(_ event: String) {
        lastBKAEvent = event
        lastBKAEventAt = Date()
    }

    /// Human-readable debounce-timer state for the lifecycle snapshot line.
    private func debounceTimerDescription() -> String {
        guard pendingSilentAudioStop != nil else { return "nil" }
        guard let scheduledAt = pendingStopScheduledAt else { return "pending" }
        let left = Self.silentAudioStopDebounce - Date().timeIntervalSince(scheduledAt)
        return left > 0 ? "pending(\(String(format: "%.1f", left))s left)" : "pending(firing)"
    }

    /// One structured snapshot line covering every signal that decides whether
    /// the process survives in the background. Emitted on every lifecycle change.
    /// [BKA][Lifecycle]
    private func logLifecycleSnapshot(_ transition: String) {
        lastLifecycle = transition
        lastLifecycleAt = Date()
        let sessions = SessionActivityTracker.shared.activeSessions.count
        let shellRunning = ShellCommandRingBuffer.hasRunningCommand
        let bgTask = isActive
        let remaining = UIApplication.shared.backgroundTimeRemaining
        let remainingStr = remaining > 99999 ? "unlimited" : String(format: "%.0fs", remaining)
        logger.info("[BKA][Lifecycle] → \(transition) | isActive=\(self.isActive) bg=\(self.appIsInBackground) sessions=\(sessions) shellRunning=\(shellRunning) bgTask=\(bgTask) bgTaskRemaining=\(remainingStr) silentAudioPlaying=\(self.silentAudioIsPlaying) suspendCount=\(self.silentAudioSuspendCount) bgSpeak=\(self.backgroundSpeakEnabled) stopDebounceTimer=\(self.debounceTimerDescription())")
    }

    /// When > 0, silent audio is suspended so media playback gets full volume.
    /// Multiple callers can nest suspend/resume via this counter. Readable
    /// from outside for diagnostic logging at suspend/resume call sites.
    private(set) var silentAudioSuspendCount = 0

    /// Temporarily suspend silent audio for media playback (full volume).
    /// Each call must be balanced by a matching `resumeSilentAudioForMedia()`.
    func suspendSilentAudioForMedia(caller: String = #function) {
        let before = silentAudioSuspendCount
        silentAudioSuspendCount += 1
        logger.info("[BackgroundKeepAlive] suspendSilentAudioForMedia: count \(before) → \(self.silentAudioSuspendCount) caller=\(caller)")
        if silentAudioSuspendCount == 1 && silentAudioActive {
            stopSilentAudio()
        }
    }

    /// Resume silent audio after media playback ends. Only restarts if all
    /// suspenders have called resume and keep-alive conditions are still met.
    func resumeSilentAudioForMedia(caller: String = #function) {
        let before = silentAudioSuspendCount
        silentAudioSuspendCount = max(0, silentAudioSuspendCount - 1)
        logger.info("[BackgroundKeepAlive] resumeSilentAudioForMedia: count \(before) → \(self.silentAudioSuspendCount) caller=\(caller)")
        if silentAudioSuspendCount == 0 {
            evaluateSilentAudio(caller: "resumeMedia")
        }
    }

    // [T-ios-scene-create-watchdog-corelocation] `init()` deliberately does NO
    // CoreLocation work. Every CLLocationManager touch — the allocation itself
    // and `authorizationStatus` — is a synchronous XPC round-trip to locationd.
    // This is a `static let` singleton, so its init runs inside a dispatch_once
    // on whichever thread first reads `.shared`; that turned out to be the main
    // thread during SwiftUI's scene-create body evaluation, where a slow
    // locationd blocks the watchdog's 10s budget. See `configureLocationManager()`.
    private override init() {
        super.init()
    }

    private var didSetup = false

    /// Wire up the CLLocationManager. Split out of `init()` so no CoreLocation
    /// IPC can ever run as a side effect of merely touching `.shared`; callers
    /// reach this only from `setup()`, which runs after the scene exists.
    private func configureLocationManager() {
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        locationManager.pausesLocationUpdatesAutomatically = false
        locationAuthStatus = locationManager.authorizationStatus
    }

    /// Read the current authorization status on demand, for UI that displays it
    /// without having necessarily gone through `setup()` first. Safe to call
    /// from a settings screen (the user is deep in the app by then, nowhere near
    /// the scene-create watchdog window) but NEVER from a view-body/init path
    /// that can run during launch — this performs synchronous locationd IPC.
    func refreshLocationAuthStatus() {
        locationAuthStatus = locationManager.authorizationStatus
    }

    func setup() {
        guard !didSetup else { return }
        didSetup = true
        configureLocationManager()
        logger.info("[BackgroundKeepAlive] Setup: enabled=\(self.enhancedBackgroundEnabled), locationTracking=\(self.locationTrackingEnabled)")

        if #available(iOS 17.0, *) {
            let probe = CLBackgroundActivitySession()
            probe.invalidate()
            logger.info("[BKA] unconditional orphan-session retract on setup")
        }

        // [T-ios-bgactivitysession-leak] A CLBackgroundActivitySession outlives
        // app termination by design: if the previous process was force-killed
        // without invalidating it, iOS keeps the system location indicator
        // pinned to the Dynamic Island and relaunches us when the user taps it.
        // On cold start, before deciding whether keep-alive should run, clear
        // any orphaned session + stale Live Activity so a leaked session from a
        // prior run doesn't keep the indicator (and a dead Live Activity) up.
        retractOrphanedLocationSession(caller: "setup")
        AgentLiveActivityManager.shared.cleanupStaleActivities(source: "BKA.setup")

        evaluateBackgroundActivitySession()

        // [T-ish-bg-cpu-governor] Background LAUNCH coverage: a process
        // relaunched directly into the background (RunningBoard after-life,
        // push/location wake) never receives didEnterBackgroundNotification,
        // so the governor sink below would leave background CPU unguarded —
        // exactly the state a post-kill relaunch resumes into. Arm it here
        // when setup finds the app already backgrounded (begin is idempotent;
        // a later real didEnterBackground is a no-op).
        if UIApplication.shared.applicationState == .background {
            appIsInBackground = true
            ISHKernel.shared.beginBackgroundCPUGovernor()
            logger.info("[BKA] launched in background — iSH CPU governor armed at setup")
        }

        // Observe app lifecycle
        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                // [T-ios-scenephase-active-sigkill] Cancel any in-flight
                // foreground transition: a bg→fg→bg blip must not let the
                // deferred willEnterForeground handler flip appIsInBackground.
                self.pendingForegroundTransition = false
                self.appIsInBackground = true
                // [T-ish-bg-cpu-governor] Closed-loop governor replaces the
                // old fixed 80% duty cycle (which sat exactly on the iOS
                // background kill line and still measured 91% — see
                // docs/ish-bg-cpu-governor-design.md).
                ISHKernel.shared.beginBackgroundCPUGovernor()
                logger.info("[BKA] iSH background CPU governor STARTED")
                self.logLifecycleSnapshot("Background")
                // [T-ios-bg-location-arm-delay] Don't start location keep-alive
                // immediately on backgrounding — arm it after a delay so a brief
                // bg blip never spins up location. evaluateLocationUpdates() runs
                // inside scheduleBackgroundLocationArming() once disarmed → armed.
                self.scheduleBackgroundLocationArming()
                self.evaluateSilentAudio(caller: "didEnterBackground")
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.pendingForegroundTransition = true
                // [T-ios-scenephase-active-sigkill] Defer @Published mutations
                // off the synchronous foreground-notification delivery. Flipping
                // appIsInBackground + evaluateSilentAudio triggers objectWillChange
                // on BackgroundKeepAliveManager, which invalidates any SwiftUI view
                // observing it — during the same tick as the foreground view-graph
                // transition. Yielding first avoids the overlap.
                Task { @MainActor in
                    await Task.yield()
                    // Guard against rapid bg→fg→bg cycling: if didEnterBackground
                    // fired between the notification and this yield it cleared the
                    // flag, so don't flip appIsInBackground. Uses a self-maintained
                    // flag rather than applicationState because `.inactive` is
                    // ambiguous (can mean "entering foreground" OR "entering
                    // background") and would let a backgrounding blip slip through.
                    guard self.pendingForegroundTransition else { return }
                    self.pendingForegroundTransition = false
                    self.appIsInBackground = false
                    ISHKernel.shared.endBackgroundCPUGovernor()
                    logger.info("[BKA] iSH background CPU governor STOPPED (foreground)")
                    self.logLifecycleSnapshot("Foreground")
                    // [T-ios-bg-location-arm-delay] Unified location-state
                    // cleanup on every foreground return — disarms, tears down
                    // our own session/timer, and retracts any orphaned session
                    // left pinned in the Dynamic Island (force-kill / crash).
                    self.cleanupLocationStateOnForeground()
                    self.evaluateSilentAudio(caller: "willEnterForeground")
                }
            }
            .store(in: &cancellables)

        // [BKA] willResignActive / didBecomeActive don't change appIsInBackground
        // (that's driven by did-enter-background / will-enter-foreground), but
        // they bracket the transient blip that the debounce defends against, so
        // we snapshot them too for the crash-time timeline.
        NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.logLifecycleSnapshot("Inactive") }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.logLifecycleSnapshot("Active")
                self?.retractOrphanedLocationSession(caller: "didBecomeActive")
            }
            .store(in: &cancellables)

        // Re-evaluate keep-alive whenever sessions or toggle change
        Publishers.CombineLatest(
            SessionActivityTracker.shared.$activeSessions,
            $enhancedBackgroundEnabled
        )
        .receive(on: DispatchQueue.main)
        .sink { [weak self] sessions, enabled in
            guard let self else { return }
            self.reevaluate(sessions: sessions, enabled: enabled)
        }
        .store(in: &cancellables)

        // App-icon badge: tracks the running-task count while the app is
        // backgrounded, gated by `backgroundNotificationsEnabled` (the
        // same toggle that controls task-completion banners). Foreground
        // entry clears the badge in `MinisApp.handleScenePhaseChange`.
        Publishers.CombineLatest(
            SessionActivityTracker.shared.$activeSessions,
            $backgroundNotificationsEnabled
        )
        .receive(on: DispatchQueue.main)
        .sink { [weak self] sessions, enabled in
            self?.refreshActiveTaskBadge(sessions: sessions, enabled: enabled)
        }
        .store(in: &cancellables)

        // Re-evaluate location when location toggle changes
        $locationTrackingEnabled
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.evaluateLocationUpdates()
            }
            .store(in: &cancellables)

        // Re-evaluate silent audio when background speak toggle changes
        $backgroundSpeakEnabled
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.evaluateSilentAudio(caller: "bgSpeakToggle")
            }
            .store(in: &cancellables)

        // [T-ios-live-activity-audio-toggle] Drive the Live Activity from the
        // read-aloud (TTS) engine state, NOT the chat audio-attachment player.
        // `VoiceOutputState.shared` mirrors both TTS engines: isReadingAloud ==
        // a reply is being read (starts/attaches the Live Activity, shows the
        // toggle), speechPaused flips the control icon, and reading-ends tears an
        // audio-only activity down. `removeDuplicates` avoids redundant pushes.
        // (Root-cause fix for b0f3f884: GlobalAudioPlayer.isPlaying/isLoaded
        // never change during read-aloud, so the toggle never appeared.)
        Publishers.CombineLatest(
            VoiceOutputState.shared.$isReadingAloud,
            VoiceOutputState.shared.$speechPaused
        )
        .removeDuplicates { $0 == $1 }
        .dropFirst() // skip the initial replay on subscribe
        .receive(on: DispatchQueue.main)
        .sink { [weak self] reading, paused in
            self?.audioPlaybackStateChanged(source: "voiceOut(reading=\(reading),paused=\(paused))")
        }
        .store(in: &cancellables)

        // Restart silent audio when an external app's audio interruption ends.
        // External media (Spotify, Apple Music, etc.) starting playback will
        // pause our AVAudioEngine via .interruptionTypeBegan; iOS sends
        // .ended once the foreign session relinquishes. Without re-evaluating
        // here, `silentAudioActive` stays true (engine is silently dead) and
        // the process becomes eligible for suspension/termination.
        NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] note in
                guard let self,
                      let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
                switch type {
                case .began:
                    self.lastAudioInterruption = "began"
                    self.lastAudioInterruptionAt = Date()
                    let wasSuspended = (note.userInfo?[AVAudioSessionInterruptionReasonKey] as? UInt)
                        == AVAudioSession.InterruptionReason.appWasSuspended.rawValue
                    logger.info("[BKA][Interrupt] began — appWasSuspended=\(wasSuspended) wasPlaying=\(self.silentAudioIsPlaying)")
                    self.recordBKAEvent("Interrupt began")
                case .ended:
                    let opts = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map {
                        AVAudioSession.InterruptionOptions(rawValue: $0)
                    }
                    let shouldResume = opts?.contains(.shouldResume) ?? false
                    self.lastAudioInterruption = "ended"
                    self.lastAudioInterruptionAt = Date()
                    logger.info("[BKA][Interrupt] ended — shouldResume=\(shouldResume) sessions=\(SessionActivityTracker.shared.activeSessions.count) bg=\(self.appIsInBackground)")
                    // 0.5s grace so the foreign session fully releases before we
                    // re-acquire .playback.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        self.handleAudioInterruptionEnded()
                    }
                @unknown default:
                    break
                }
            }
            .store(in: &cancellables)

        // [T-bg-keepalive-debounce] Route changes (e.g. another app's session
        // taking over, headphones unplugged) can leave our engine running into
        // a dead session, or signal the system reclaimed audio focus. When that
        // happens while a background task is active, re-acquire the session.
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] note in
                self?.handleAudioSessionPossiblyReclaimed(source: "routeChange", note: note)
            }
            .store(in: &cancellables)

        // [T-bg-keepalive-debounce] The secondary-audio hint fires when the
        // system asks us to silence secondary audio (`.begin`) and again when
        // it's safe to resume (`.end`). An `.end` while we're backgrounded with
        // active sessions is another cue to make sure our keep-alive audio is
        // actually live.
        NotificationCenter.default.publisher(for: AVAudioSession.silenceSecondaryAudioHintNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] note in
                self?.handleAudioSessionPossiblyReclaimed(source: "silenceSecondaryAudioHint", note: note)
            }
            .store(in: &cancellables)
    }

    /// [T-bg-keepalive-debounce] Common handler for route-change /
    /// secondary-audio-hint notifications: if we're backgrounded with active
    /// sessions and our silent audio is supposed to be playing, verify the
    /// engine is genuinely alive and re-acquire the session if not. This catches
    /// the case where iOS quietly tore down our session (so `silentAudioActive`
    /// is a lie) and the process becomes eligible for termination.
    private func handleAudioSessionPossiblyReclaimed(source: String, note: Notification) {
        // [BKA][RouteChange] log the route transition (only routeChange carries
        // route info; secondary-hint just signals begin/end).
        if source == "routeChange",
           let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
           let reason = AVAudioSession.RouteChangeReason(rawValue: raw) {
            let outs = AVAudioSession.sharedInstance().currentRoute.outputs
                .map { $0.portType.rawValue }.joined(separator: "+")
            logger.info("[BKA][RouteChange] reason=\(reason) newRoute=\(outs.isEmpty ? "none" : outs) sessions=\(SessionActivityTracker.shared.activeSessions.count) bg=\(self.appIsInBackground)")
        }

        let sessionCount = SessionActivityTracker.shared.activeSessions.count
        let wantsKeepAlive = isActive && backgroundSpeakEnabled
            && appIsInBackground && silentAudioSuspendCount == 0 && sessionCount > 0
        guard wantsKeepAlive else { return }

        // If we think audio is active but the engine isn't actually running, iOS
        // pulled the session out from under us — tear down and restart.
        let engineRunning = audioEngine?.isRunning ?? false
        if silentAudioActive && !engineRunning {
            logger.warning("[BKA][\(source)] silentAudioActive but engine NOT running — session reclaimed, restarting")
            recordBKAEvent("Session reclaimed (\(source))")
            silentPlayerNode?.stop()
            audioEngine?.stop()
            silentPlayerNode = nil
            audioEngine = nil
            silentAudioActive = false
            cancelPendingSilentAudioStop(reason: "\(source) reclaim")
            startSilentAudio(reason: "\(source)Reclaim")
        } else if !silentAudioActive {
            // Should be playing but isn't — re-evaluate (also covers a stale
            // pending stop from a prior foreground blip).
            logger.info("[BKA][\(source)] keep-alive wanted but not playing — re-evaluating")
            cancelPendingSilentAudioStop(reason: "\(source) not playing")
            evaluateSilentAudio(caller: source)
        }
    }

    /// Force-reset silent audio state after an AVAudioSession interruption
    /// ends. The engine may have been stopped silently by iOS while we still
    /// believe `silentAudioActive == true`, which prevents `evaluateSilentAudio`
    /// from restarting it.
    private func handleAudioInterruptionEnded() {
        let sessionCount = SessionActivityTracker.shared.activeSessions.count
        logger.info("[BackgroundKeepAlive] Audio session interruption ended — bg=\(self.appIsInBackground) sessions=\(sessionCount) playing=\(self.silentAudioActive)")
        // The engine may have been stopped silently by iOS while we still
        // believe `silentAudioActive == true`, which would block
        // `evaluateSilentAudio` from restarting it. Tear down the stale engine
        // unconditionally so evaluate can cleanly re-start.
        if silentAudioActive {
            silentPlayerNode?.stop()
            audioEngine?.stop()
            silentPlayerNode = nil
            audioEngine = nil
            silentAudioActive = false
        }
        // [T-bg-keepalive-debounce] An interruption ending mid-task (e.g. a
        // WeChat/phone call finishing while a background agent task is still
        // running) is exactly when we must re-acquire the session. If we're in
        // background with active sessions, force a fresh start with retry rather
        // than relying solely on evaluate (which is correct, but be explicit).
        if appIsInBackground && sessionCount > 0 {
            cancelPendingSilentAudioStop(reason: "interruption ended, sessions active")
        }
        evaluateSilentAudio(caller: "interruptionEnded")
    }

    // MARK: - Location Permission

    func requestLocationPermission() {
        locationManager.requestAlwaysAuthorization()
    }

    // MARK: - Termination

    /// Called from `applicationWillTerminate` so a clean exit tears the location
    /// session + Live Activity down. [T-ios-bgactivitysession-leak] Without this,
    /// the CLBackgroundActivitySession outlives the process and keeps the system
    /// location indicator pinned (and relaunches the app when tapped).
    /// `applicationWillTerminate` isn't guaranteed on force-kill, so the cold-start
    /// + foreground `retractOrphanedLocationSession(...)` paths are the backstop.
    func prepareForTermination() {
        logger.info("[BackgroundKeepAlive] prepareForTermination — stopping location session + Live Activity")
        // [T-ios-bg-idle-grace] Never carry a pending grace timer into termination.
        cancelIdleGrace(reason: "prepareForTermination")
        disarmBackgroundLocation()
        locationTimer?.invalidate()
        locationTimer = nil
        locationUpdating = false
        if locationManager.allowsBackgroundLocationUpdates {
            locationManager.stopUpdatingLocation()
            locationManager.allowsBackgroundLocationUpdates = false
        }
        stopBackgroundActivitySession()
        // Belt-and-suspenders: after tearing down our owned session, probe-retract
        // so the system indicator is definitively cleared even if the handle was
        // already lost.
        retractOrphanedLocationSession(caller: "terminate")
        AgentLiveActivityManager.shared.endActivity()
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.locationAuthStatus = status
            logger.info("[BackgroundKeepAlive] Authorization changed: \(status.rawValue)")
            self.evaluateLocationUpdates()
            self.evaluateBackgroundActivitySession()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let loc = locations.last {
            let lat = Self.maskCoordinate(loc.coordinate.latitude)
            let lon = Self.maskCoordinate(loc.coordinate.longitude)
            Task { @MainActor in
                logger.info("[BackgroundKeepAlive] Location update: (\(lat), \(lon)) accuracy=\(loc.horizontalAccuracy)m")
                // Piggyback Live Activity update on location callback —
                // Timer may be suspended in background, but location callbacks keep firing.
                self.updateLiveActivityIfNeeded(source: "location")
            }
        }
    }

    /// Mask a coordinate for privacy: keep first 2 and last 1 digits, replace middle with "..."
    /// e.g. 39.9042 → "39...2", 116.4074 → "11...4"
    nonisolated private static func maskCoordinate(_ value: Double) -> String {
        let s = String(format: "%.4f", value)
        guard s.count > 3 else { return s }
        return "\(s.prefix(2))...\(s.suffix(1))"
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            logger.warning("[BackgroundKeepAlive] Location error: \(error.localizedDescription)")
        }
    }

    // MARK: - Evaluate State

    private func reevaluate(sessions: Set<String>, enabled: Bool) {
        // [T-ios-scenephase-active-sigkill] If we're mid foreground transition
        // (between willEnterForeground and its deferred yield), defer this
        // reevaluation. It writes @Published isActive; doing so on the
        // transition tick overlaps the SwiftUI view-graph rebuild → potential
        // SIGKILL (same family as the 2026-06-19 mid-request crash).
        if pendingForegroundTransition {
            Task { @MainActor in
                await Task.yield()
                self.reevaluate(sessions: sessions, enabled: enabled)
            }
            return
        }

        let shouldBeActive = !sessions.isEmpty && enabled
        let sessionList = sessions.prefix(5).joined(separator: ",")
        // [T-ios-log-noise-reduction] INFO→DEBUG: reevaluate runs on every
        // session-set / toggle change and was ~482 lines/day even when nothing
        // transitioned. The actual state CHANGES (Activating / Deactivating
        // below) keep their INFO level, so production logs still show the
        // transitions without the per-call no-op chatter.
        logger.debug("[BackgroundKeepAlive] Reevaluate: sessions=\(sessions.count) enabled=\(enabled) isActive=\(self.isActive) shouldBeActive=\(shouldBeActive) ids=[\(sessionList)]")

        if shouldBeActive && !isActive {
            logger.info("[BackgroundKeepAlive] Activating keep-alive (sessions=\(sessions.count))")
            isActive = true
            AgentLiveActivityManager.shared.startActivity(sessions: buildSessionSnapshots())
            startUpdateTimer()
            evaluateLocationUpdates()
            evaluateBackgroundActivitySession()
            evaluateSilentAudio(caller: "reevaluate-activate")
        } else if !shouldBeActive && isActive {
            logger.info("[BackgroundKeepAlive] Deactivating keep-alive")
            stopUpdateTimer()
            isActive = false
            // [T-ios-live-activity-soft-finish] STOP LOCATION FIRST, fully, before
            // touching the Live Activity. The old order (end Live Activity, then
            // tear down location) let the probe-retract race surface a "ghost"
            // location arrow right as the task ended — it appeared and couldn't be
            // dismissed even though nothing was locating. Quiescing location first
            // closes that window. [T-ios-bgactivitysession-leak]
            disarmBackgroundLocation()
            evaluateLocationUpdates()
            evaluateBackgroundActivitySession()
            retractOrphanedLocationSession(caller: "deactivate")
            evaluateSilentAudio(caller: "reevaluate-deactivate")
            // THEN soft-finish the Live Activity: flip to a completed state
            // (checkmark + last message) and leave it on screen; it's dismissed
            // when the user taps it and Minis foregrounds.
            Task { await AgentLiveActivityManager.shared.finishActivity() }
        }
    }

    // MARK: - App Icon Badge

    /// Update the app-icon badge so it reflects the live count of
    /// running agent sessions while the app is in the background. When
    /// the user has turned the task-notifications toggle off, the badge
    /// is forced to 0 — Apple HIG treats badge as a notification
    /// affordance, so it inherits that gate. Foreground transitions
    /// clear the badge separately in `MinisApp.handleScenePhaseChange`.
    func refreshActiveTaskBadge() {
        refreshActiveTaskBadge(
            sessions: SessionActivityTracker.shared.activeSessions,
            enabled: backgroundNotificationsEnabled
        )
    }

    private func refreshActiveTaskBadge(sessions: Set<String>, enabled: Bool) {
        let center = UNUserNotificationCenter.current()
        guard enabled else {
            center.setBadgeCount(0) { _ in }
            return
        }
        center.setBadgeCount(sessions.count) { err in
            if let err {
                logger.error("[Badge] setBadgeCount failed: \(err.localizedDescription)")
            }
        }
    }

    // MARK: - Background Task Notifications

    /// Notification category for background task completion.
    private static let bgTaskCategoryId = "BACKGROUND_TASK"

    /// Post a local notification when a background task completes.
    /// - Parameters:
    ///   - sessionTitle: Session/conversation title (shown as notification title)
    ///   - responseSummary: Truncated assistant response text (shown as notification body)
    ///   - sessionId: The session ID for tap-to-open navigation
    ///   - isError: Whether the task ended with an error
    /// [T-ios-live-activity-privacy-mode] Content-free notification body:
    /// "1 task completed · 2m 15s". The duration is appended only when a start
    /// time is available (a Live Activity run was in flight); otherwise the body
    /// degrades to just the count, per spec — no extra time tracking is added.
    private static func privacyNotificationBody(isError: Bool, startedAt: Date?) -> String {
        let base = isError
            ? AppLocalized("1 task failed")
            : AppLocalized("1 task completed")
        guard let startedAt else { return base }
        let elapsed = Int(Date().timeIntervalSince(startedAt).rounded())
        guard elapsed > 0 else { return base }
        let formatted: String
        if elapsed >= 3600 {
            formatted = String(format: "%dh %dm", elapsed / 3600, (elapsed % 3600) / 60)
        } else if elapsed >= 60 {
            formatted = String(format: "%dm %ds", elapsed / 60, elapsed % 60)
        } else {
            formatted = String(format: "%ds", elapsed)
        }
        return "\(base) · \(formatted)"
    }

    func postBackgroundTaskNotification(sessionTitle: String, responseSummary: String, sessionId: String, isError: Bool = false, wasBackground: Bool? = nil) {
        guard backgroundNotificationsEnabled else { return }
        // Use the caller-captured snapshot when available (the async Task in
        // endBackgroundProcessing may not run until the app is already active).
        // Fall back to the live check for other call sites.
        let isBackground = wasBackground ?? (UIApplication.shared.applicationState != .active)
        guard isBackground else { return }

        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }

        let category = UNNotificationCategory(
            identifier: Self.bgTaskCategoryId,
            actions: [],
            intentIdentifiers: []
        )
        center.setNotificationCategories([category])

        let content = UNMutableNotificationContent()
        if liveActivityPrivacyMode {
            // [T-ios-live-activity-privacy-mode] Neutral report only: no session
            // title, no reply summary. sessionId stays in userInfo below so the
            // tap-to-open jump keeps working, and the badge logic is untouched.
            content.title = isError
                ? AppLocalized("❌ Task failed")
                : AppLocalized("✅ Task completed")
            content.body = Self.privacyNotificationBody(
                isError: isError,
                startedAt: AgentLiveActivityManager.shared.taskStartTime
            )
        } else {
            content.title = "\(isError ? "❌" : "✅") \(sessionTitle)"
            content.body = responseSummary
        }
        content.sound = .default
        content.categoryIdentifier = Self.bgTaskCategoryId
        content.userInfo = ["sessionId": sessionId]
        logger.info("[BackgroundNotification] posting notification title=\(content.title.debugDescription) bodyLength=\(content.body.count) sessionId=\(sessionId)")

        // Increment app badge count
        let current = UIApplication.shared.applicationIconBadgeNumber
        content.badge = NSNumber(value: current + 1)
        UIApplication.shared.applicationIconBadgeNumber = current + 1

        let request = UNNotificationRequest(
            identifier: "bg-task-\(sessionId)-\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil // deliver immediately
        )
        center.add(request) { error in
            if let error {
                logger.error("[BackgroundNotification] Failed to post: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Background Location Arming (delay before keep-alive)

    /// [T-ios-bg-location-arm-delay] Start the delay timer that arms background
    /// location keep-alive `backgroundLocationArmDelay` seconds after the app
    /// backgrounds. If the app returns to foreground first, `disarmBackgroundLocation`
    /// cancels this and nothing ever spins up. Idempotent: a no-op if a timer is
    /// already pending or location is already armed.
    private func scheduleBackgroundLocationArming() {
        guard appIsInBackground else { return }
        guard !backgroundLocationArmed, backgroundLocationArmTimer == nil else { return }
        let delay = Self.backgroundLocationArmDelay
        logger.info("[BKA][LocationArm] scheduling location keep-alive in \(Int(delay))s")
        let timer = Timer(timeInterval: delay, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.backgroundLocationArmTimer = nil
                // Re-check we're still backgrounded — a fg blip may have fired
                // after the timer was created but the disarm beat us here.
                guard self.appIsInBackground else {
                    logger.info("[BKA][LocationArm] arm fired but no longer backgrounded — skip")
                    return
                }
                self.backgroundLocationArmed = true
                logger.info("[BKA][LocationArm] armed — starting location keep-alive now")
                self.evaluateLocationUpdates()
                self.evaluateBackgroundActivitySession()
            }
        }
        backgroundLocationArmTimer = timer
        RunLoop.current.add(timer, forMode: .common)
    }

    /// Cancel a pending arm timer and disarm immediately (foreground return).
    private func disarmBackgroundLocation() {
        backgroundLocationArmTimer?.invalidate()
        backgroundLocationArmTimer = nil
        if backgroundLocationArmed {
            logger.info("[BKA][LocationArm] disarmed (foreground)")
        }
        backgroundLocationArmed = false
    }

    /// [T-ios-bgactivitysession-leak] Unified location-state cleanup run on every
    /// foreground return. Order matters:
    ///   1. disarm + stop the 5s requestLocation timer (foreground never needs it),
    ///   2. evaluate the iOS17 session — now disarmed, this tears OUR session down,
    ///   3. retract any ORPHANED session (a leak from a prior crash/force-kill that
    ///      our handle can't reach) so a stale indicator never survives a relaunch,
    ///   4. re-sync the Live Activity to the current session set.
    /// This is the single place that guarantees: back in the foreground ⇒ no
    /// location indicator, regardless of how the app last died.
    private func cleanupLocationStateOnForeground() {
        disarmBackgroundLocation()
        // Stop the legacy requestLocation timer immediately (foreground).
        locationTimer?.invalidate()
        locationTimer = nil
        locationUpdating = false
        if locationManager.allowsBackgroundLocationUpdates {
            locationManager.stopUpdatingLocation()
            locationManager.allowsBackgroundLocationUpdates = false
        }
        // Tear down our own iOS17 session (disarmed ⇒ shouldRun is false).
        evaluateBackgroundActivitySession()
        // Retract any orphaned session our handle can't reach.
        retractOrphanedLocationSession(caller: "foreground")
        // Keep the Live Activity consistent with the live session set.
        updateLiveActivityIfNeeded(source: "foregroundCleanup")
    }

    // MARK: - Location Updates

    private func evaluateLocationUpdates() {
        let shouldUpdate = isActive
            && locationTrackingEnabled
            && appIsInBackground
            && backgroundLocationArmed
            && (locationAuthStatus == .authorizedAlways || locationAuthStatus == .authorizedWhenInUse)
            && liveUpdatesTask == nil

        if shouldUpdate && !locationUpdating {
            logger.info("[BackgroundKeepAlive] Starting location updates (5s interval)")
            locationManager.allowsBackgroundLocationUpdates = true
            locationManager.showsBackgroundLocationIndicator = false
            locationUpdating = true
            // Request location immediately, then every 5 seconds
            locationManager.requestLocation()
            locationTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.locationManager.requestLocation()
                }
            }
            RunLoop.current.add(locationTimer!, forMode: .common)
        } else if !shouldUpdate && locationUpdating {
            logger.info("[BackgroundKeepAlive] Stopping location updates")
            locationTimer?.invalidate()
            locationTimer = nil
            locationUpdating = false
        }
    }

    // MARK: - Background Activity Session (iOS 17+)

    private func evaluateBackgroundActivitySession() {
        let hasPermission = locationAuthStatus == .authorizedAlways || locationAuthStatus == .authorizedWhenInUse
        // `isActive` is false the moment no agent task is running (see
        // reevaluate). So as soon as the last task finishes, shouldRun flips to
        // false and the session below is torn down immediately — we never keep
        // the location stream (or its system indicator) alive past the work.
        //
        // [T-ios-bg-location-arm-delay] Also gated on appIsInBackground +
        // backgroundLocationArmed: the CLBackgroundActivitySession (and its
        // system location indicator) only starts 15s AFTER backgrounding, never
        // in the foreground. This gives a grace window so brief background blips
        // don't flash the indicator, and keeps the foreground free of location.
        let shouldRun = isActive && enhancedBackgroundEnabled && hasPermission
            && appIsInBackground && backgroundLocationArmed

        // Singleton by construction: this manager is a `.shared` instance and
        // `liveUpdatesTask`/`bgActivitySession` are single-valued, so N
        // concurrent background sessions still drive exactly ONE global
        // CLBackgroundActivitySession + one liveUpdates stream — never one per
        // chat session. The `liveUpdatesTask == nil` guard makes start idempotent.
        if shouldRun && liveUpdatesTask == nil {
            startBackgroundActivitySession()
        } else if !shouldRun {
            // Tear down whenever we shouldn't be running — even if liveUpdatesTask
            // is already nil — so a session orphaned by a prior force-kill (task
            // nil, but the system indicator still pinned) gets explicitly
            // invalidated rather than lingering.
            stopBackgroundActivitySession()
        }
    }

    private func startBackgroundActivitySession() {
        guard liveUpdatesTask == nil else { return }

        if #available(iOS 17.0, *) {
            let session = CLBackgroundActivitySession()
            bgActivitySession = session
            logger.info("[BKA][LocationLA] CLBackgroundActivitySession created")

            liveUpdatesTask = Task { @MainActor [weak self] in
                guard let self else { return }
                do {
                    let updates = CLLocationUpdate.liveUpdates(.otherNavigation)
                    for try await update in updates {
                        guard !Task.isCancelled else { break }
                        let now = Date()
                        let elapsed = now.timeIntervalSince(self.lastLocationLAUpdate)
                        guard elapsed >= Self.locationLAMinInterval else { continue }
                        self.lastLocationLAUpdate = now
                        let lat = update.location.map { Self.maskCoordinate($0.coordinate.latitude) } ?? "?"
                        let lon = update.location.map { Self.maskCoordinate($0.coordinate.longitude) } ?? "?"
                        logger.info("[BKA][LocationLA] tick (\(lat),\(lon)) — pushing LA update")
                        self.updateLiveActivityIfNeeded(source: "locationLA")
                    }
                } catch {
                    if !Task.isCancelled {
                        logger.error("[BKA][LocationLA] liveUpdates stream error: \(error.localizedDescription)")
                    }
                }
                logger.info("[BKA][LocationLA] stream ended")
                self.liveUpdatesTask = nil
            }
            logger.info("[BKA][LocationLA] liveUpdates stream started")
        }
    }

    private func stopBackgroundActivitySession() {
        let hadSomething = liveUpdatesTask != nil || bgActivitySession != nil
        liveUpdatesTask?.cancel()
        liveUpdatesTask = nil
        if #available(iOS 17.0, *) {
            (bgActivitySession as? CLBackgroundActivitySession)?.invalidate()
        }
        bgActivitySession = nil
        if hadSomething {
            logger.info("[BKA][LocationLA] stopped")
        }
    }

    /// Retract a CLBackgroundActivitySession orphaned by a prior force-kill /
    /// crash, clearing the pinned system location indicator.
    ///
    /// [T-ios-bgactivitysession-leak] When a previous process died without
    /// invalidating its session, iOS keeps the location indicator pinned and
    /// relaunches us on tap. That leaked session belongs to the dead process, so
    /// our `bgActivitySession` is nil and `stopBackgroundActivitySession()`
    /// can't reach it. The only reliable retraction is to create a fresh session
    /// and immediately invalidate it: that hands iOS a live handle for *this*
    /// process which we then explicitly end, clearing the "app is using
    /// background location" status (and its indicator).
    ///
    /// `caller` is logged so we can tell which path (cold start / foreground)
    /// actually cleared a leak. Safe to call whenever WE are not currently
    /// running our own session — guarded on `liveUpdatesTask == nil &&
    /// bgActivitySession == nil` so it never tears down a live, owned session.
    /// Note: it intentionally does NOT skip when a task is active — a leaked
    /// session must be retracted even mid-task; if keep-alive should genuinely
    /// run, the subsequent `evaluateBackgroundActivitySession()` recreates a
    /// fresh, owned session.
    private func retractOrphanedLocationSession(caller: String) {
        guard #available(iOS 17.0, *) else { return }
        guard liveUpdatesTask == nil, bgActivitySession == nil else { return }
        let probe = CLBackgroundActivitySession()
        probe.invalidate()
        logger.info("[BKA][LocationLA] retracted orphaned CLBackgroundActivitySession (caller=\(caller))")
    }

    // MARK: - Silent Audio Keep-Alive

    private func evaluateSilentAudio(caller: String = #function) {
        // Self-heal a leaked suspendCount. suspend/resume should pair, but
        // task cancellation, network timeouts mid-playback, etc. occasionally
        // skip the resume call. If keepalive should be active and silent
        // audio is NOT running yet the count is stuck > 2, treat it as leaked
        // and reset so we can re-engage keepalive.
        if isActive && appIsInBackground && backgroundSpeakEnabled
            && !silentAudioActive && silentAudioSuspendCount > 2 {
            logger.warning("[BackgroundKeepAlive] silentAudioSuspendCount=\(self.silentAudioSuspendCount) looks leaked — resetting to 0")
            silentAudioSuspendCount = 0
        }

        let shouldPlay = isActive && backgroundSpeakEnabled && appIsInBackground && silentAudioSuspendCount == 0
        let sessions = SessionActivityTracker.shared.activeSessions.count
        let reason: String = {
            if !isActive { return "isActive=false" }
            if !backgroundSpeakEnabled { return "bgSpeak=false" }
            if !appIsInBackground { return "bg=false" }
            if silentAudioSuspendCount > 0 { return "suspended=\(silentAudioSuspendCount)" }
            return "ok"
        }()
        // [BKA][Evaluate] decision chain — caller, all inputs, and the resolved
        // PLAY / STOP / NOOP decision so the crash-time timeline shows exactly
        // who triggered each evaluation and why it stopped or started audio.
        let decision: String = {
            if shouldPlay { return silentAudioActive ? "NOOP(already playing)" : "PLAY" }
            return silentAudioActive ? "STOP" : "NOOP(already stopped)"
        }()
        logger.info("[BKA][Evaluate] caller=\(caller) isActive=\(self.isActive) bgSpeak=\(self.backgroundSpeakEnabled) bg=\(self.appIsInBackground) sessions=\(sessions) suspended=\(self.silentAudioSuspendCount) playing=\(self.silentAudioActive) stopDebounce=\(self.stopDebouncePending ? "pending" : "nil") → decision=\(decision) reason=\(reason)")

        if shouldPlay && !silentAudioActive {
            // A fresh background signal (or any condition that re-enables play)
            // cancels any debounced stop in flight — the transient foreground
            // blip is over and we want to keep the audio running.
            cancelPendingSilentAudioStop(reason: "shouldPlay=true")
            startSilentAudio()
        } else if shouldPlay && silentAudioActive {
            // Already playing AND should keep playing — a transient foreground
            // blip that flipped back before its debounce fired. Make sure no
            // stale stop is still queued.
            cancelPendingSilentAudioStop(reason: "still shouldPlay")
        } else if !shouldPlay && silentAudioActive {
            // [T-ios-bg-idle-grace] Split the single "should stop" branch by WHY.
            // Only the pure-idle transition (isActive went false while every other
            // condition still says "keep playing") gets the 60s grace window.
            // Everything else — Background Speak switched off, an explicit media
            // suspend, a genuine foreground return — is an explicit intent to
            // stop and must take effect immediately, so those fall through to the
            // pre-existing path unchanged (which includes the 1.5s transient-
            // foreground debounce; the two windows never overlap because this
            // branch requires appIsInBackground && backgroundSpeakEnabled).
            let idleOnly = !isActive
                && backgroundSpeakEnabled
                && appIsInBackground
                && silentAudioSuspendCount == 0
            if idleOnly {
                beginIdleGraceIfNeeded()
            } else {
                // An explicit stop supersedes a grace window already in flight.
                cancelIdleGrace(reason: "explicit stop (\(reason))")
                requestStopSilentAudio(transientForeground: !appIsInBackground)
            }
        } else if shouldPlay {
            // Work is back (or never left) — a queued idle grace is now moot.
            // Covers both shouldPlay branches above; cancelIdleGrace is a no-op
            // when nothing is pending.
            cancelIdleGrace(reason: "shouldPlay=true")
        }
    }

    // MARK: - [T-ios-bg-idle-grace] Idle grace period

    /// Arm the one-shot idle grace timer, unless one is already pending.
    ///
    /// Purely time-driven: it consults no "is something still running" signal.
    /// It buys a fixed, bounded window of background battery in exchange for
    /// tolerating a state-sync hiccup around the moment the last session ends.
    ///
    /// Leak safety — this timer is cleared in EVERY one of these paths:
    ///   1. its own handler, which always nils it before doing anything else;
    ///   2. `cancelIdleGrace` from `evaluateSilentAudio` when work returns
    ///      (`shouldPlay`) or when an explicit stop reason appears;
    ///   3. `stopSilentAudio` — catches direct callers (media suspend,
    ///      interruption teardown) that bypass `evaluateSilentAudio` entirely;
    ///   4. `startSilentAudio` — a restart must never leave a stale stop queued;
    ///   5. `prepareForTermination`.
    /// Non-repeating by construction (`.now() + interval`, no `repeating:`), so
    /// even a missed cleanup can only ever fire once.
    private func beginIdleGraceIfNeeded() {
        // Idempotent: never let repeated evaluations stack timers or slide the
        // deadline forward, which is what would turn this into "keep-alive that
        // never stops".
        guard idleGraceTimer == nil else {
            let waited = idleGraceStartedAt.map { Date().timeIntervalSince($0) } ?? 0
            logger.info("[BKA][IdleGrace] already pending (\(String(format: "%.1f", waited))s elapsed) — not re-arming")
            return
        }
        logger.info("[BKA][IdleGrace] idle — holding keep-alive for \(Int(Self.idleGracePeriod))s before stopping")
        recordBKAEvent("IdleGrace(start,\(Int(Self.idleGracePeriod))s)")
        idleGraceStartedAt = Date()
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + Self.idleGracePeriod)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            // Clear state FIRST so every path below leaves no residue, and so a
            // stop triggered from here can't re-enter this handler.
            self.idleGraceTimer?.cancel()
            self.idleGraceTimer = nil
            self.idleGraceStartedAt = nil
            // Unconditional stop. Re-checking "is it still idle" here would
            // reintroduce a branch that can expire WITHOUT stopping, which is
            // exactly the failure mode this design forbids. It is safe to be
            // unconditional: any path that made keep-alive wanted again
            // (evaluateSilentAudio -> shouldPlay, startSilentAudio) has already
            // cancelled this timer, so reaching here means nothing re-armed it.
            logger.info("[BKA][IdleGrace] expired — stopping keep-alive now")
            self.stopSilentAudio(reason: "idle_grace_expired(\(Int(Self.idleGracePeriod))s)")
        }
        idleGraceTimer = timer
        timer.resume()
    }

    /// Cancel a pending idle grace. Safe to call unconditionally; a no-op when
    /// nothing is armed.
    private func cancelIdleGrace(reason: String) {
        guard let timer = idleGraceTimer else { return }
        timer.cancel()
        idleGraceTimer = nil
        let waited = idleGraceStartedAt.map { Date().timeIntervalSince($0) } ?? 0
        idleGraceStartedAt = nil
        logger.info("[BKA][IdleGrace] cancelled after \(String(format: "%.1f", waited))s — \(reason)")
        recordBKAEvent("IdleGrace(cancel,\(reason))")
    }

    /// [T-bg-keepalive-debounce] Decide whether to stop silent audio now or
    /// after a debounce window. A stop caused purely by a `foreground` signal
    /// (`transientForeground == true`) while sessions are still active is the
    /// dangerous case: it may be the brief blip of an app switch (call ending,
    /// app-switcher peek, Dynamic Island tap, Siri/Control Center) that is about
    /// to background again. We delay it so a `background` signal arriving within
    /// the window can cancel it. Every other reason (no active sessions, media
    /// suspend, Background Speak turned off) is a genuine intent to stop, so we
    /// stop immediately.
    private func requestStopSilentAudio(transientForeground: Bool) {
        let sessionCount = SessionActivityTracker.shared.activeSessions.count
        let shouldDebounce = transientForeground && sessionCount > 0
        guard shouldDebounce else {
            cancelPendingSilentAudioStop(reason: "immediate stop")
            recordBKAEvent("Stop(reason=\(transientForeground ? "foreground,sessions=0" : "non-foreground"))")
            stopSilentAudio(reason: transientForeground ? "foreground_sessions0" : "evaluate")
            return
        }
        // Already have a pending stop — let it ride (don't reset the timer, so a
        // burst of foreground passes can't indefinitely postpone the real stop).
        if pendingSilentAudioStop != nil {
            logger.info("[BKA][Debounce] already scheduled — keeping existing timer (sessions=\(sessionCount))")
            return
        }
        logger.info("[BKA][Debounce] scheduled stop in \(Self.silentAudioStopDebounce)s — sessions=\(sessionCount) caller=foregroundSignal")
        recordBKAEvent("Stop(reason=foreground,debounce=pending)")
        let scheduledAt = Date()
        pendingStopScheduledAt = scheduledAt
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.pendingSilentAudioStop = nil
            self.pendingStopScheduledAt = nil
            let nowSessions = SessionActivityTracker.shared.activeSessions.count
            // Re-check the world at fire time: if we went back to background (or
            // any other reason now keeps it playing), do NOT stop.
            let stillForeground = !self.appIsInBackground
            let stillActive = self.isActive && self.backgroundSpeakEnabled
                && self.silentAudioSuspendCount == 0
            if stillForeground || !stillActive {
                logger.info("[BKA][Debounce] fired — proceeding to stop (sessions=\(nowSessions), foreground=\(stillForeground))")
                self.stopSilentAudio(reason: "debounce_expired(\(Self.silentAudioStopDebounce)s)")
            } else {
                logger.info("[BKA][Debounce] fired — skipping stop, app re-backgrounded (sessions=\(nowSessions)) — KEEPING silent audio")
                self.recordBKAEvent("Stop skipped (re-backgrounded)")
            }
        }
        pendingSilentAudioStop = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.silentAudioStopDebounce, execute: work)
    }

    /// Cancel any in-flight debounced stop of silent audio.
    private func cancelPendingSilentAudioStop(reason: String) {
        guard let pending = pendingSilentAudioStop else { return }
        pending.cancel()
        pendingSilentAudioStop = nil
        let elapsed = pendingStopScheduledAt.map { Date().timeIntervalSince($0) }
        pendingStopScheduledAt = nil
        let elapsedStr = elapsed.map { String(format: "elapsed=%.1fs", $0) } ?? ""
        logger.info("[BKA][Debounce] cancelled — \(reason) \(elapsedStr)")
        recordBKAEvent("Debounce cancelled (\(reason))")
    }

    private func startSilentAudio(reason: String = "evaluate") {
        guard !silentAudioActive else { return }
        // Starting up means we definitely don't want any queued stop to fire.
        cancelPendingSilentAudioStop(reason: "startSilentAudio")
        cancelIdleGrace(reason: "startSilentAudio")
        let attempt = silentAudioActivationRetries + 1
        let sessions = SessionActivityTracker.shared.activeSessions.count
        logger.info("[BKA][Start] reason=\(reason) sessions=\(sessions) attempt=\(attempt)/\(Self.maxActivationRetries)")
        recordBKAEvent("Start(reason=\(reason),attempt=\(attempt))")

        // [T-bg-keepalive-debounce] Activate the session, retrying on failure.
        // After a stopSilentAudio() the session was deactivated with
        // notifyOthersOnDeactivation; the system may briefly reject a
        // re-activation while another app is still re-acquiring focus, or while
        // a call/media session is releasing. A single sync attempt here closes
        // the common case; on failure we schedule an async retry (0.5s apart,
        // up to 3 total attempts) by re-invoking startSilentAudio, so we never
        // block the main thread yet still recover from the transient rejection.
        // The session category/active is owned by AudioSessionCoordinator. Declare
        // the keep-alive intent (lowest priority) — the coordinator applies the
        // .mixWithOthers profile ONLY when nothing higher (reply TTS / media /
        // capture) is active, so the silent track no longer clobbers TTS's profile.
        // The silent AVAudioEngine below plays regardless to keep the process alive.
        MainActor.assumeIsolated {
            AudioSessionCoordinator.shared.begin(.backgroundKeepAlive)
        }
        logger.info("[BKA][Start] begin(.backgroundKeepAlive) — acquiring engine")
        silentAudioActivationRetries = 0

        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)

        // Create a silent audio buffer (1 second of silence, looping)
        let sampleRate: Double = 44100
        let frameCount = AVAudioFrameCount(sampleRate) // 1 second
        guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
            logger.error("[BackgroundKeepAlive] Failed to create silent audio buffer")
            return
        }
        buffer.frameLength = frameCount
        // Buffer is already zero-filled (silent)

        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.mainMixerNode.outputVolume = 0.001

        do {
            try engine.start()
            player.scheduleBuffer(buffer, at: nil, options: .loops)
            player.play()
            audioEngine = engine
            silentPlayerNode = player
            silentAudioActive = true
            startAudioUpdateTimer()
            logger.info("[BKA][Start] engine running — silent audio keep-alive ACTIVE")
        } catch {
            logger.error("[BKA][Start] engine.start() FAILED: \(error.localizedDescription)")
        }
    }

    /// [T-bg-keepalive-debounce] Schedule a delayed re-attempt of
    /// `startSilentAudio` after a `setActive(true)` rejection, up to
    /// `maxActivationRetries` total attempts. At fire time we re-check that
    /// keep-alive is still wanted (background + active + not suspended) so we
    /// don't fight a state that has since changed.
    private func scheduleSilentAudioActivationRetry() {
        silentAudioActivationRetries += 1
        guard silentAudioActivationRetries < Self.maxActivationRetries else {
            logger.error("[BKA][Start] exhausted \(Self.maxActivationRetries) activation attempts — giving up")
            recordBKAEvent("Start FAILED (exhausted retries)")
            silentAudioActivationRetries = 0
            return
        }
        let attempt = silentAudioActivationRetries
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.activationRetryDelay) { [weak self] in
            guard let self else { return }
            let stillWanted = self.isActive && self.backgroundSpeakEnabled
                && self.appIsInBackground && self.silentAudioSuspendCount == 0
                && !self.silentAudioActive
            guard stillWanted else {
                logger.info("[BKA][Start] activation retry #\(attempt) skipped — keep-alive no longer wanted")
                self.silentAudioActivationRetries = 0
                return
            }
            logger.info("[BKA][Start] activation retry #\(attempt) — re-attempting")
            self.startSilentAudio(reason: "activationRetry#\(attempt)")
        }
    }

    /// Called by SpeechFinishedDelegate when TTS completes, to restart silent audio if needed.
    func evaluateSilentAudioFromDelegate() {
        evaluateSilentAudio(caller: "ttsFinished")
    }

    /// [T-shortcuts-eager-keepalive] Eager keep-alive hook for AppIntent entry
    /// points (Shortcuts automations). Call this synchronously at the top of an
    /// Intent's `perform()` BEFORE any `await` that would let iOS suspend the
    /// AppIntent-woken process. It:
    ///   1) records the (already-known) session id in the tracker so the
    ///      Combine publisher fires and `reevaluate` flips `isActive` to true,
    ///   2) immediately re-evaluates silent audio so the AVAudioEngine
    ///      spins up while the process still has wall-clock time.
    ///
    /// STRICTLY gated on `enhancedBackgroundEffective` (the user's
    /// enhancedBackgroundEnabled AND backgroundSpeakEnabled toggles both being
    /// on). If the user hasn't opted into background keep-alive, this is a
    /// no-op — we do NOT silently start background audio playback behind their
    /// back. Returns `(armed, skipReason)` — a non-nil `skipReason` accompanies
    /// `armed=false` so callers can log/persist WHY we skipped without having
    /// to reconstruct the gating logic.
    @MainActor
    @discardableResult
    func armEagerlyForShortcut(sessionId: String, caller: String) -> (armed: Bool, skipReason: String?) {
        let enhancedOn = enhancedBackgroundEnabled
        let bgSpeakOn = backgroundSpeakEnabled
        if !enhancedOn || !bgSpeakOn {
            let reason: String
            if !enhancedOn && !bgSpeakOn {
                reason = "enhancedBackgroundEnabled=false;backgroundSpeakEnabled=false"
            } else if !enhancedOn {
                reason = "enhancedBackgroundEnabled=false"
            } else {
                reason = "backgroundSpeakEnabled=false"
            }
            logger.info("[ShortcutDiag] eagerKeepAlive caller=\(caller) decision=SKIPPED reason=\(reason)")
            return (false, reason)
        }
        logger.info("[ShortcutDiag] eagerKeepAlive caller=\(caller) decision=STARTED session=\(sessionId.prefix(8))")
        SessionActivityTracker.shared.setActive(sessionId, source: "\(caller).eager")
        evaluateSilentAudio(caller: "\(caller).eager")
        return (true, nil)
    }

    func stopSilentAudio(reason: String = "direct") {
        // A direct stop supersedes any debounced one and aborts pending retries.
        cancelPendingSilentAudioStop(reason: "stopSilentAudio called")
        // [T-ios-bg-idle-grace] Also clear a pending idle grace. Required, not
        // belt-and-braces: stopSilentAudio has direct callers (media suspend,
        // interruption teardown) that never go through evaluateSilentAudio, and
        // the grace timer's own handler calls in here — clearing it first there
        // makes this a no-op rather than re-entrancy.
        cancelIdleGrace(reason: "stopSilentAudio called")
        silentAudioActivationRetries = 0
        guard silentAudioActive else { return }
        let sessions = SessionActivityTracker.shared.activeSessions.count
        logger.info("[BKA][Stop] reason=\(reason) sessions=\(sessions)")
        recordBKAEvent("Stop(reason=\(reason))")
        stopAudioUpdateTimer()
        silentPlayerNode?.stop()
        audioEngine?.stop()
        silentPlayerNode = nil
        audioEngine = nil
        silentAudioActive = false
        // End the keep-alive intent — the coordinator re-applies the next-highest
        // intent's profile (reply TTS / capture) or deactivates the session,
        // letting other apps resume. (No direct setActive here anymore.)
        MainActor.assumeIsolated {
            AudioSessionCoordinator.shared.end(.backgroundKeepAlive)
        }
        logger.info("[BKA][Stop] engine.stop() + end(.backgroundKeepAlive)")
    }

    // MARK: - Live Activity Updates

    /// Shared method to push a Live Activity update. Called from:
    /// - updateTimer (foreground, every 2-10s)
    /// - location callbacks (background with location tracking)
    /// - audioUpdateTimer (background with silent audio, no location)
    /// [T-ios-live-activity-audio-toggle] Entry point for audio-driven Live
    /// Activity changes. Unlike `updateLiveActivityIfNeeded` (gated on `isActive`,
    /// which is only true while an Agent task runs), this always forwards to the
    /// manager so a pure-audio playback — with zero Agent sessions — can start,
    /// refresh, or end its own Live Activity. When an Agent task is also running,
    /// the manager updates that single existing activity in place (never a 2nd one).
    func audioPlaybackStateChanged(source: String) {
        let v = VoiceOutputState.shared
        logger.info("[LiveActivity][audio] BKA forward src=\(source) reading=\(v.isReadingAloud) paused=\(v.speechPaused) isActive=\(self.isActive)")
        AgentLiveActivityManager.shared.audioStateChanged(source: source)
    }

    func updateLiveActivityIfNeeded(source: String = "?") {
        guard isActive else {
            let cnt = SessionActivityTracker.shared.activeSessions.count
            if cnt > 0 {
                logger.info("[LiveActivity][update] SKIP src=\(source) isActive=false tracker=\(cnt) session(s)")
            }
            return
        }
        let sessions = SessionActivityTracker.shared.activeSessions
        let count = sessions.count
        let idList = sessions.map { $0.prefix(8) }.joined(separator: ",")
        let appState: String = {
            switch UIApplication.shared.applicationState {
            case .active: return "fg"
            case .inactive: return "inactive"
            case .background: return "bg"
            @unknown default: return "?"
            }
        }()
        let silentAudio = silentAudioIsPlaying
        let bgRemaining = UIApplication.shared.backgroundTimeRemaining
        let remainStr = bgRemaining > 99999 ? "unlimited" : String(format: "%.0fs", bgRemaining)
        logger.info("[LiveActivity][update] src=\(source) sessions=\(count) ids=[\(idList)] appState=\(appState) silentAudio=\(silentAudio) bgRemaining=\(remainStr)")
        AgentLiveActivityManager.shared.updateActivity(sessions: buildSessionSnapshots())
    }

    /// [T-ios-live-activity-audio-toggle] Public accessor so the Live Activity
    /// manager's audio-only start/update path can rebuild the current session
    /// snapshots (empty when only audio is active).
    func buildLiveSessionSnapshots() -> [LiveSessionSnapshot] {
        buildSessionSnapshots()
    }

    private func buildSessionSnapshots() -> [LiveSessionSnapshot] {
        guard #available(iOS 16.2, *) else { return [] }
        let tracker = SessionActivityTracker.shared
        return tracker.activeSessions.sorted().map { sid in
            let info = tracker.sessionToolInfo[sid]
            let title = info?.title ?? ""
            let toolName = info?.toolName ?? ""
            let toolStatus = info?.toolStatus ?? ""
            let icon = AgentLiveActivityManager.sfSymbol(forTool: toolName)
            let displayName = AgentLiveActivityManager.displayName(forTool: toolName)
            let statusText: String
            if toolName.isEmpty {
                statusText = AppLocalized("Working...")
            } else if toolStatus.isEmpty {
                statusText = displayName
            } else {
                statusText = "\(displayName) · \(toolStatus)"
            }
            logger.info("[LiveActivity][snapshot] sid=\(sid.prefix(8)) toolName=\(toolName) icon=\(icon) status=\(statusText) title=\(title.prefix(20))")
            return LiveSessionSnapshot(
                sessionId: sid,
                title: title.isEmpty ? AppLocalized("Agent Task") : title,
                toolIcon: icon,
                toolStatus: statusText,
                loopIteration: info?.loopIteration ?? 0
            )
        }
    }

    private var updateInterval: TimeInterval {
        return 10.0
    }

    /// Foreground RunLoop timer — works while app is active.
    private func startUpdateTimer() {
        stopUpdateTimer()
        let interval = updateInterval
        logger.info("[BackgroundKeepAlive] Starting update timer (interval=\(interval)s)")
        updateTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.updateLiveActivityIfNeeded(source: "fgTimer")
            }
        }
        RunLoop.current.add(updateTimer!, forMode: .common)
    }

    private func stopUpdateTimer() {
        updateTimer?.invalidate()
        updateTimer = nil
    }

    // MARK: - Background Audio Update Timer

    /// A GCD timer that fires even when the main RunLoop is suspended,
    /// as long as the process stays alive (via audio or location background mode).
    private var audioUpdateTimer: DispatchSourceTimer?

    private func startAudioUpdateTimer() {
        stopAudioUpdateTimer()
        stopUpdateTimer()
        let interval = updateInterval
        logger.info("[BackgroundKeepAlive] Starting audio update timer (interval=\(interval)s) — fgTimer suspended")
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in
            Task { @MainActor [weak self] in
                self?.updateLiveActivityIfNeeded(source: "bgAudioTimer")
            }
        }
        timer.resume()
        audioUpdateTimer = timer
    }

    private func stopAudioUpdateTimer() {
        guard audioUpdateTimer != nil else { return }
        audioUpdateTimer?.cancel()
        audioUpdateTimer = nil
        if isActive {
            startUpdateTimer()
            logger.info("[BackgroundKeepAlive] audioUpdateTimer stopped — fgTimer resumed")
        }
    }
}

// MARK: - Background Interruption Tracker

@MainActor
final class BackgroundInterruptionTracker: ObservableObject {
    static let shared = BackgroundInterruptionTracker()

    @Published var showBanner = false

    /// How many interruptions must accumulate before the banner is shown again.
    /// Starts at 1 (remind on every interruption). Each "Remind me later" tap
    /// doubles it, clamped to [minInterval, maxInterval] — true exponential
    /// back-off: 1 → 2 → 4 → 8 → 10 (capped).
    private static let minInterval = 1
    private static let maxInterval = 10
    private static let intervalKey = "enhancedBackgroundRemindInterval"
    private static let countKey = "enhancedBackgroundInterruptionCount"

    /// Current reminder frequency (show the banner once every `remindInterval`
    /// interruptions). Defaults to 1 when unset.
    var remindInterval: Int {
        get {
            let stored = UserDefaults.standard.integer(forKey: Self.intervalKey)
            return stored <= 0 ? 1 : stored
        }
        set { UserDefaults.standard.set(newValue, forKey: Self.intervalKey) }
    }

    /// Accumulated interruptions since the last time the banner was shown or
    /// the user tapped "Remind me later".
    private var interruptionCount: Int {
        get { UserDefaults.standard.integer(forKey: Self.countKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.countKey) }
    }

    private var wasInterrupted = false
    private var cancellables: Set<AnyCancellable> = []

    // [T-ios-scene-create-watchdog-corelocation] This singleton is first touched
    // from a SwiftUI view body (BackgroundInterruptionBanner, mounted at the app
    // root), so its init runs on the main thread during scene-create. Reaching
    // for `BackgroundKeepAliveManager.shared` HERE is what dragged that manager's
    // own dispatch_once — and, before the fix, its CoreLocation IPC — onto that
    // critical path. Keep this init free of cross-singleton work and subscribe
    // lazily instead.
    private init() {}

    /// Auto-dismiss the banner once enhanced background is turned on, so the
    /// user isn't left wondering what else they need to enable after tapping
    /// "Enable" and flipping the switch. Deferred out of `init()` (see above);
    /// idempotent, and only forces the keep-alive manager once the banner is
    /// actually being observed.
    private var didSubscribe = false
    func startObservingIfNeeded() {
        guard !didSubscribe else { return }
        didSubscribe = true
        BackgroundKeepAliveManager.shared.$enhancedBackgroundEnabled
            .removeDuplicates()
            .sink { [weak self] enabled in
                guard let self, enabled, self.showBanner else { return }
                self.showBanner = false
            }
            .store(in: &cancellables)
    }

    func recordInterruption() {
        wasInterrupted = true
    }

    /// Called when app returns to foreground. Counts the interruption and only
    /// surfaces the banner once the running count reaches `remindInterval`.
    func checkOnForeground() {
        guard wasInterrupted,
              !BackgroundKeepAliveManager.shared.enhancedBackgroundEffective else {
            wasInterrupted = false
            return
        }
        wasInterrupted = false

        interruptionCount += 1
        guard interruptionCount >= remindInterval else { return }
        // Threshold reached — reset the counter and show the banner.
        interruptionCount = 0
        showBanner = true
    }

    func dismiss() {
        showBanner = false
    }

    /// "Remind me later" — back off the reminder frequency (double it, clamped
    /// to [minInterval, maxInterval]), clear the accumulated count, and dismiss.
    func remindLaterAndDismiss() {
        let doubled = remindInterval * 2
        remindInterval = min(max(doubled, Self.minInterval), Self.maxInterval)
        interruptionCount = 0
        showBanner = false
    }
}

// MARK: - Background Interruption Banner

struct BackgroundInterruptionBanner: View {
    @ObservedObject private var tracker = BackgroundInterruptionTracker.shared
    @State private var dragOffset: CGFloat = 0
    @State private var showSettings = false

    private let dismissThreshold: CGFloat = 40

    var body: some View {
        if tracker.showBanner {
            bannerContent
                .transition(.move(edge: .top).combined(with: .opacity))
                .offset(y: min(dragOffset, 0))
                .gesture(swipeToDismiss)
                .animation(.spring(response: 0.35), value: tracker.showBanner)
                .sheet(isPresented: $showSettings) {
                    NavigationStack { EnhancedBackgroundSettingsView() }
                }
                // [T-ios-scene-create-watchdog-corelocation] Subscribe only once
                // the banner is actually on screen — the subscription exists to
                // auto-dismiss a VISIBLE banner, so there is nothing to observe
                // before that. This keeps BackgroundKeepAliveManager.shared off
                // the scene-create path (it is otherwise forced by setup(), well
                // after the scene exists).
                .onAppear { tracker.startObservingIfNeeded() }
        }
    }

    private var bannerContent: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.white)
                .font(.title3)

            VStack(alignment: .leading, spacing: 2) {
                Text("Background Task Interrupted")
                    .font(.subheadline.bold())
                    .foregroundColor(.white)
                Text("Enable enhanced background to keep tasks running")
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.85))
            }

            Spacer()

            VStack(spacing: 6) {
                Button {
                    DeepLinkCoordinator.shared.setFocus(
                        rawQueryValue: "enhancedBackgroundExecution:true,backgroundSpeakEnabled:true,locationTrackingEnabled:true")
                    showSettings = true
                    withAnimation { tracker.dismiss() }
                } label: {
                    Text("Enable")
                        .font(.caption.bold())
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(Color.white)
                        .foregroundColor(.orange)
                        .cornerRadius(14)
                }

                Button {
                    withAnimation { tracker.remindLaterAndDismiss() }
                } label: {
                    Text("Remind me later")
                        .font(.caption2)
                        .foregroundColor(.white.opacity(0.8))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .padding(.top, 4)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.orange.gradient)
                .shadow(color: .black.opacity(0.15), radius: 8, y: 4)
        )
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }

    private var swipeToDismiss: some Gesture {
        DragGesture()
            .onChanged { value in
                dragOffset = value.translation.height
            }
            .onEnded { value in
                if value.translation.height < -dismissThreshold {
                    withAnimation { tracker.dismiss() }
                }
                dragOffset = 0
            }
    }
}
