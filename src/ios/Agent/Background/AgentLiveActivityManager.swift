import ActivityKit
import Foundation
import UIKit
import os.log

private let logger = AppLogger(category: "LiveActivity")

@MainActor
final class AgentLiveActivityManager {
    static let shared = AgentLiveActivityManager()
    private init() {}

    private var currentActivity: Any?
    private var startTime: Date?

    /// [T-ios-live-activity-privacy-mode] Read-only view of the current task's
    /// start time, so Privacy Mode notifications can report elapsed duration
    /// ("1 task completed · 2m 15s") without introducing separate time tracking.
    /// nil when no Live Activity run is in flight.
    var taskStartTime: Date? { startTime }
    private var carouselIndex: Int = 0
    private var updateCount: Int = 0
    /// Toggled on every Live Activity refresh to alternate the Dynamic Island
    /// minimal icon between the latest-tool icon and the session-count badge.
    private var minimalShowsTool: Bool = false
    private var lastPushedState: Any?
    private var lastPushDate: Date = .distantPast
    private var pendingState: Any?
    private var pendingPushWorkItem: DispatchWorkItem?

    private var lastRenewDate: Date = .distantPast
    private static let minRenewInterval: TimeInterval = 5.0

    /// [T-ios-liveactivity-renew-bg-race] When a renew ends the old activity but
    /// the restart fails because the app slipped to the background mid-renew
    /// ("Target is not foreground"), we stash the state here and re-`start` it on
    /// the next foreground return so the Live Activity isn't left missing.
    private var pendingStartState: Any?

    /// [T-ios-live-activity-soft-finish] True after `finishActivity()` flips the
    /// Live Activity to its completed resting state but leaves it on screen. The
    /// next foreground return ends it (`dismissFinishedActivityOnForeground`).
    /// Cleared by start/end so a fresh task never inherits a stale "finished" flag.
    private var awaitingDismissal = false
    private var isFinishing = false

    private static let isActivityKitAvailable: Bool = {
        #if targetEnvironment(macCatalyst)
        return false
        #else
        if ProcessInfo.processInfo.isiOSAppOnMac {
            return false
        }
        guard #available(iOS 16.2, *) else { return false }
        // [T-ipad16-activitykit-crash] ActivityKit is WEAK-linked
        // (LC_LOAD_WEAK_DYLIB) and Live Activities came to iPad only with
        // iPadOS 17. `#available(iOS 16.2, *)` passes on iPadOS 16.2, so gated
        // code ran there and the first ActivityKit call / generic-metadata
        // instantiation dereferenced a NULL weak symbol: 1.9 crashed calling
        // a null function pointer (pc=0 via Combine), 1.10 crashed in
        // swift_getTypeByMangledName resolving Activity<AgentActivityAttributes>
        // from _endActivity (user report: iPad8,11 @ iPadOS 16.2, 100% on send).
        //
        // [T-ipad16-liveactivity-restore-crash GH#82] The dlopen probe alone
        // did NOT stop the crash on that iPad (builds 51→54, identical
        // swift_getTypeByMangledName stack, now firing from the foreground
        // session-restore task → cleanupStaleActivities): a framework binary
        // can be present in the dyld shared cache — dlopen returns a handle —
        // on a system where the Activity machinery itself is absent/broken.
        // Two additional gates:
        //  1. iPad requires iPadOS 17+ (Apple's actual Live Activity support
        //     matrix; the feature never worked on iPad 16.x anyway).
        //  2. The ActivityKit.Activity class descriptor symbol must actually
        //     resolve — if it doesn't, instantiating
        //     Activity<AgentActivityAttributes> metadata is what crashes.
        if UIDevice.current.userInterfaceIdiom == .pad {
            guard #available(iOS 17.0, *) else {
                logger.info("[LiveActivity][probe] unavailable: iPad on iPadOS 16.x")
                return false
            }
        }
        guard let handle = dlopen("/System/Library/Frameworks/ActivityKit.framework/ActivityKit", RTLD_LAZY) else {
            logger.info("[LiveActivity][probe] unavailable: dlopen failed")
            return false
        }
        guard dlsym(handle, "$s11ActivityKit0A0CMn") != nil else {
            logger.info("[LiveActivity][probe] unavailable: Activity descriptor symbol missing")
            return false
        }
        return true
        #endif
    }()

    /// Shared "can this device show Live Activities at all" check — the same
    /// hardened runtime probe the manager gates every ActivityKit call on
    /// (macCatalyst/iOS-on-Mac excluded, iPad requires iPadOS 17+, framework
    /// binary loadable AND Activity descriptor symbol present). UI that offers
    /// Live Activity preferences should hide behind this so users can't toggle
    /// a switch that does nothing on their device. [T-ipad16-liveactivity-restore-crash]
    static var isLiveActivitySupported: Bool { isActivityKitAvailable }

    func cleanupStaleActivities(source: String = "?") {
        guard Self.isActivityKitAvailable else {
            logger.info("[LiveActivity][cleanup src=\(source)] skipped: ActivityKit unavailable")
            return
        }
        if #available(iOS 16.2, *) {
            _cleanupStaleActivities(source: source)
        }
    }

    /// [T-live-activity-toggle] User preference gate (Settings → Background →
    /// Live Activity, default ON). Gates the CREATE/UPDATE entry points only;
    /// end/cleanup/finish stay allowed so flipping the switch off (or a stale
    /// activity from before the switch was off) always tears down cleanly.
    private static var isUserEnabled: Bool {
        (UserDefaults.standard.object(forKey: "liveActivityEnabled") as? Bool) ?? true
    }

    func startActivity(sessions: [LiveSessionSnapshot]) {
        guard Self.isActivityKitAvailable, Self.isUserEnabled else { return }
        if #available(iOS 16.2, *) {
            _startActivity(sessions: sessions)
        }
    }

    func updateActivity(sessions: [LiveSessionSnapshot]) {
        guard Self.isActivityKitAvailable, Self.isUserEnabled else { return }
        if #available(iOS 16.2, *) {
            _updateActivity(sessions: sessions)
        }
    }

    func endActivity() {
        guard Self.isActivityKitAvailable else { return }
        if #available(iOS 16.2, *) {
            _endActivity()
        }
    }

    /// [T-ios-live-activity-multisession-dismiss] Mark a single session as
    /// completed while other sessions are still running. Updates the Live
    /// Activity in place — the completed session shows a checkmark + last
    /// message, while remaining sessions continue refreshing normally.
    func markSessionCompleted(sessionId: String, lastMessage: String) {
        guard Self.isActivityKitAvailable, Self.isUserEnabled else { return }
        if #available(iOS 16.2, *) {
            _markSessionCompleted(sessionId: sessionId, lastMessage: lastMessage)
        }
    }

    /// [T-ios-live-activity-soft-finish] Soft-finish: when the last task ends,
    /// DON'T tear the Live Activity down. Flip it to a "completed" resting state
    /// (checkmark + each session's last message, carousel frozen) and leave it on
    /// the Lock Screen / Dynamic Island. It's dismissed later — when the user taps
    /// it and Minis comes to the foreground (see `dismissFinishedActivityOnForeground`).
    /// Falls back to a hard end if there's no prior running snapshot to complete.
    func finishActivity(lastMessages: [String: String] = [:]) async {
        guard Self.isActivityKitAvailable else { return }
        // Disabled → no soft-finish card; fall through to a hard end so any
        // activity left over from before the switch was flipped is removed.
        guard Self.isUserEnabled else {
            if #available(iOS 16.2, *) { _endActivity() }
            return
        }
        if #available(iOS 16.2, *) {
            await _finishActivity(lastMessages: lastMessages)
        }
    }

    /// [T-ios-delete-session-live-activity] Called when a session is deleted.
    /// Removes the session from the Live Activity so the Dynamic Island / Lock
    /// Screen never keeps showing content from a deleted conversation: if other
    /// sessions are still represented the activity updates in place without the
    /// deleted one; otherwise it ends immediately (dismissalPolicy .immediate).
    func handleSessionDeleted(_ sessionId: String) {
        guard Self.isActivityKitAvailable else { return }
        if #available(iOS 16.2, *) {
            _handleSessionDeleted(sessionId)
        }
    }

    /// Called on foreground return. If a soft-finished ("completed") activity is
    /// lingering, end it now that the user is back in the app.
    func dismissFinishedActivityOnForeground() {
        guard Self.isActivityKitAvailable else { return }
        if #available(iOS 16.2, *) {
            let completedState = (lastPushedState as? AgentActivityAttributes.ContentState)?.allCompleted == true
            guard awaitingDismissal || completedState else { return }
            logger.info("[LiveActivity][finish] foreground return — dismissing completed activity (awaitingDismissal=\(awaitingDismissal) completedState=\(completedState))")
            _endActivity()
            let remaining = SessionActivityTracker.shared.activeSessions
            if !remaining.isEmpty {
                logger.info("[LiveActivity][finish] \(remaining.count) session(s) still active after dismiss — restarting activity")
                BackgroundKeepAliveManager.shared.updateLiveActivityIfNeeded(source: "postDismissRecovery")
            }
        }
    }

    // MARK: - Audio state (T-ios-live-activity-audio-toggle)

    /// Current audio state read from the app's global player. Nil-safe snapshot so
    /// every ContentState we build can carry the audio fields, and so start /
    /// keep-alive / cleanup decisions can treat "audio loaded" as a reason to keep
    /// the Live Activity alive even with zero Agent sessions.
    struct AudioState {
        var isPlaying: Bool
        var isLoaded: Bool
        var title: String
    }

    static func currentAudioState() -> AudioState {
        // [T-ios-live-activity-audio-toggle] Data source is the read-aloud (TTS)
        // engine, NOT the chat audio-attachment player. `VoiceOutputState.shared`
        // mirrors both TTS engines (System AVSpeechSynthesizer + cloud
        // VoiceOutputPlayer): isReadingAloud == a reply is being read (loaded /
        // capsule visible), speechPaused == currently paused. GlobalAudioPlayer's
        // isPlaying/isLoaded never change during read-aloud, which is why the
        // toggle previously never appeared (root-cause fix for b0f3f884).
        let v = VoiceOutputState.shared
        return AudioState(isPlaying: !v.speechPaused, isLoaded: v.isReadingAloud, title: "")
    }

    /// True when a reply is being read aloud (playing or paused) — the condition,
    /// alongside active sessions, that should keep a Live Activity on screen.
    static func isAudioActive() -> Bool {
        VoiceOutputState.shared.isReadingAloud
    }

    /// [T-ios-live-activity-audio-toggle] Drive a Live Activity update purely from
    /// an audio-state change. Starts an audio-only activity if none exists (and
    /// there are no Agent sessions), or updates the existing one in place so the
    /// play/pause icon flips promptly. Ends the activity if audio just went away
    /// and no Agent sessions remain.
    /// [T-live-activity-audio-toggle-latency] The `source` that marks a push as
    /// user-initiated (a tap on the Live Activity's audio button) and therefore
    /// exempt from the push throttle. Kept as one shared constant so the producer
    /// (VoiceOutputState.handleLiveActivityToggle) and this consumer cannot drift
    /// apart into a silently-ineffective bypass. Every OTHER source — notably the
    /// TTS engine's own `voiceOut(...)` state changes — stays throttled.
    static let userAudioToggleSource = "liveActivityToggle"

    func audioStateChanged(source: String) {
        guard Self.isActivityKitAvailable else { return }
        if #available(iOS 16.2, *) {
            _audioStateChanged(source: source)
        }
    }

    @available(iOS 16.2, *)
    private func _audioStateChanged(source: String) {
        // Only the user's own tap skips the throttle; engine-driven audio state
        // changes keep the original damping.
        let bypassRateLimit = (source == Self.userAudioToggleSource)
        let audio = Self.currentAudioState()
        let sessions = SessionActivityTracker.shared.activeSessions
        logger.info("[LiveActivity][audio] src=\(source) isPlaying=\(audio.isPlaying) isLoaded=\(audio.isLoaded) sessions=\(sessions.count) hasActivity=\(self.currentActivity != nil)")

        // Audio gone AND no sessions → let the standard end path run (unless a
        // soft-finished/awaiting-dismissal activity is deliberately lingering).
        if !audio.isLoaded && sessions.isEmpty {
            if currentActivity != nil && !awaitingDismissal {
                logger.info("[LiveActivity][audio] audio stopped + no sessions — ending activity")
                _endActivity()
            }
            return
        }

        // Audio present (or sessions present). If nothing is on screen yet, start
        // an activity; otherwise update the existing one in place. This is the
        // audio-only start path that the session-count guards would otherwise block.
        if currentActivity == nil {
            _startActivity(sessions: BackgroundKeepAliveManager.shared.buildLiveSessionSnapshots())
        } else {
            // [T-live-activity-audio-toggle-latency] This is the audio path — it
            // is reached by the user tapping pause/resume on the Live Activity
            // (AudioTogglePlaybackIntent → Darwin notification →
            // VoiceOutputState.handleLiveActivityToggle → BackgroundKeepAliveManager
            // .audioPlaybackStateChanged → here). Bypass the push throttle so the
            // speaker glyph flips immediately instead of lagging up to 5s.
            _updateActivity(
                sessions: BackgroundKeepAliveManager.shared.buildLiveSessionSnapshots(),
                bypassRateLimit: bypassRateLimit
            )
        }
    }

    static func currentSoulName() -> String {
        let name = SoulStore.cachedMetadata.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "Minis" : name
    }

    /// SF Symbol for the most-recently-invoked tool across all active sessions,
    /// for the Dynamic Island minimal icon's "latest tool" frame. Empty when no
    /// active session has a tool yet.
    static func latestToolIcon() -> String {
        guard let toolName = SessionActivityTracker.shared.latestToolName() else { return "" }
        return sfSymbol(forTool: toolName)
    }

    // MARK: - Tool icon mapping

    static func sfSymbol(forTool toolName: String) -> String {
        switch toolName {
        case "browser", "browser_use":          return "globe"
        case "shell", "shell_execute":          return "terminal"
        case "file_read":                       return "doc.text"
        case "file_write":                      return "doc.text.fill"
        case "file_edit":                       return "pencil.line"
        case "read_image":                      return "photo"
        case "memory":                          return "brain.head.profile"
        case "text":                            return "bubble.left"
        case "thinking":                        return "lightbulb.max"
        case "code_interpret":                  return "chevron.left.forwardslash.chevron.right"
        default:                                return "ellipsis.circle"
        }
    }

    static func displayName(forTool toolName: String) -> String {
        switch toolName {
        case "browser", "browser_use":          return "Browser"
        case "shell", "shell_execute":          return "Shell"
        case "file_read":                       return "Read File"
        case "file_write":                      return "Write File"
        case "file_edit":                       return "Edit File"
        case "read_image":                      return "Read Image"
        case "memory":                          return "Memory"
        case "text":                            return "Responding"
        case "thinking":                        return "Thinking"
        case "code_interpret":                  return "Code"
        default:                                return "Working"
        }
    }

    // MARK: - @available implementations

    @available(iOS 16.2, *)
    private func _cleanupStaleActivities(source: String = "?") {
        // [T-ios-liveactivity-renew-bg-race] On any foreground-return cleanup,
        // first recover a Live Activity that a prior renew ended but couldn't
        // restart in the background. Runs before the renew branch below so the
        // recovered activity is the one we then (optionally) refresh.
        if source.contains("scenePhase.active") {
            resumePendingStartIfNeeded()
        }
        let enabled = ActivityAuthorizationInfo().areActivitiesEnabled
        let currentId = (currentActivity as? Activity<AgentActivityAttributes>)?.id ?? "nil"
        let stale = Activity<AgentActivityAttributes>.activities
        let staleIds = stale.map { $0.id }.joined(separator: ",")
        logger.info("[LiveActivity][cleanup src=\(source)] enabled=\(enabled) currentActivity.id=\(currentId) systemActivities.count=\(stale.count) systemIds=[\(staleIds)]")

        if currentActivity != nil {
            if awaitingDismissal {
                logger.info("[LiveActivity][cleanup src=\(source)] skip renew: awaitingDismissal — will be dismissed momentarily")
                return
            }
            let sinceLastRenew = Date().timeIntervalSince(lastRenewDate)
            // [T-ios-liveactivity-renew-kills-unregistered] Only renew an
            // activity the system has actually registered (present in
            // `Activity.activities`). ActivityKit registers + renders a freshly
            // requested activity ASYNCHRONOUSLY — for several seconds after
            // `Activity.request` returns, `systemActivities` is still empty
            // even though `currentActivity != nil`. The old condition renewed
            // purely on a 5s timer, so normal foreground/background toggling
            // while a task ran would end the just-created (not-yet-rendered)
            // activity and request a new one, which was itself killed on the
            // next foreground return — an endless churn that meant the Dynamic
            // Island NEVER appeared even though the task was active and pushes
            // "returned". Gate renew on the system having registered it first;
            // an unregistered activity has no budget to refresh anyway.
            if source.contains("scenePhase.active"),
               !stale.isEmpty,
               let prev = lastPushedState as? AgentActivityAttributes.ContentState,
               !prev.sessions.isEmpty,
               sinceLastRenew >= Self.minRenewInterval {
                // [T-ios-live-activity-task-count] Foreground-return renew must
                // reflect the CURRENT truth of who's running, not a stale copy of
                // `prev.sessions`. Filter to sessions the tracker still says are
                // active, plus explicitly-completed cards we chose to keep on
                // screen (`completedSessionSnapshots`). Anything else is stale
                // (task finished while backgrounded, VM torn down, session
                // deleted) and would otherwise inflate the count — e.g. showing
                // "8" when only 2 are really running.
                let liveIds = SessionActivityTracker.shared.activeSessions
                for sid in completedSessionSnapshots.keys where !liveIds.contains(sid) {
                    completedSessionSnapshots.removeValue(forKey: sid)
                }
                let filteredSessions = prev.sessions.filter { s in
                    liveIds.contains(s.sessionId) || completedSessionSnapshots[s.sessionId] != nil
                }
                guard !filteredSessions.isEmpty else {
                    logger.info("[LiveActivity][cleanup src=\(source)] skip renew: all \(prev.sessions.count) prev session(s) stale vs tracker (liveIds=\(liveIds.count), completed=\(self.completedSessionSnapshots.count))")
                    return
                }
                let liveCount = filteredSessions.reduce(0) { $0 + ($1.isCompleted ? 0 : 1) }
                logger.info("[LiveActivity][cleanup src=\(source)] renewing on foreground return to refresh budget (last renew \(Int(sinceLastRenew))s ago, systemActivities=\(stale.count), sessions \(prev.sessions.count)→\(filteredSessions.count) live=\(liveCount))")
                let state = AgentActivityAttributes.ContentState(
                    activeSessionCount: liveCount,
                    sessions: filteredSessions,
                    carouselIndex: carouselIndex < filteredSessions.count ? carouselIndex : 0,
                    soulName: Self.currentSoulName(),
                    latestToolIcon: prev.latestToolIcon,
                    minimalShowsTool: prev.minimalShowsTool
                )
                renewActivity(with: state)
            } else {
                logger.info("[LiveActivity][cleanup src=\(source)] skip: currentActivity is not nil — assuming this process owns it")
            }
            return
        }
        guard !stale.isEmpty else {
            logger.info("[LiveActivity][cleanup src=\(source)] nothing to clean: systemActivities is empty")
            return
        }
        logger.info("[LiveActivity][cleanup src=\(source)] ending \(stale.count) stale activity(ies) from previous session")
        let finalState = AgentActivityAttributes.ContentState(
            activeSessionCount: 0,
            sessions: [],
            carouselIndex: 0,
            soulName: ""
        )
        let finalContent = ActivityContent(state: finalState, staleDate: nil)
        for activity in stale {
            let id = activity.id
            Task {
                await activity.end(finalContent, dismissalPolicy: .immediate)
                await MainActor.run {
                    logger.info("[LiveActivity][cleanup src=\(source)] ended stale id=\(id)")
                }
            }
        }
    }

    @available(iOS 16.2, *)
    private func _startActivity(sessions: [LiveSessionSnapshot]) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            logger.info("[LiveActivity][start] skipped: Activities not enabled in Settings")
            return
        }
        // A fresh task supersedes any lingering completed activity.
        awaitingDismissal = false
        isFinishing = false

        let currentId = (currentActivity as? Activity<AgentActivityAttributes>)?.id ?? "nil"
        let stale = Activity<AgentActivityAttributes>.activities
        let staleIds = stale.map { $0.id }.joined(separator: ",")
        logger.info("[LiveActivity][start] pre: currentActivity.id=\(currentId) systemActivities.count=\(stale.count) systemIds=[\(staleIds)] sessions=\(sessions.count)")

        if !stale.isEmpty {
            logger.info("[LiveActivity][start] cleaning up \(stale.count) stale activity(ies) before start: [\(staleIds)]")
            let finalState = AgentActivityAttributes.ContentState(
                activeSessionCount: 0,
                sessions: [],
                carouselIndex: 0,
                soulName: ""
            )
            let finalContent = ActivityContent(state: finalState, staleDate: nil)
            for old in stale {
                let id = old.id
                Task {
                    await old.end(finalContent, dismissalPolicy: .immediate)
                    await MainActor.run {
                        logger.info("[LiveActivity][start] cleanup ended id=\(id)")
                    }
                }
            }
        }

        carouselIndex = 0
        lastPushedState = nil
        lastPushDate = .distantPast
        pendingPushWorkItem?.cancel()
        pendingPushWorkItem = nil
        pendingState = nil
        lastRenewDate = Date()
        let now = Date()
        minimalShowsTool = false

        // [T-ios-live-activity-task-count] Only trust sessions the tracker still
        // considers active — the caller's `sessions` list can lag when tasks
        // finished between snapshot and start. Completed carryover snapshots
        // stay for their UI card but must NOT be counted as running.
        let liveIds = SessionActivityTracker.shared.activeSessions
        let filteredInput = sessions.filter { liveIds.contains($0.sessionId) }
        let activeSids = Set(filteredInput.map { $0.sessionId })
        var merged = filteredInput
        for (sid, snap) in completedSessionSnapshots where !activeSids.contains(sid) {
            merged.append(snap)
        }
        let liveCount = merged.reduce(0) { $0 + ($1.isCompleted ? 0 : 1) }

        for sid in completedSessionSnapshots.keys where !liveIds.contains(sid) {
            completedSessionSnapshots.removeValue(forKey: sid)
        }

        // [T-ios-live-activity-audio-toggle] Stamp audio fields on the initial
        // state so an activity started purely by audio (or with audio alongside a
        // task) renders the play/pause control from the very first frame.
        let state = withAudioState(AgentActivityAttributes.ContentState(
            activeSessionCount: liveCount,
            sessions: merged,
            carouselIndex: 0,
            soulName: Self.currentSoulName(),
            latestToolIcon: Self.latestToolIcon(),
            minimalShowsTool: minimalShowsTool
        ))
        let attributes = AgentActivityAttributes(startDate: now)
        let content = ActivityContent(state: state, staleDate: nil)

        do {
            let activity = try Activity.request(
                attributes: attributes,
                content: content,
                pushType: nil
            )
            currentActivity = activity
            startTime = now
            // Record so the first _updateActivity dedup compares correctly and a
            // later audio-only end has a snapshot to reason about.
            lastPushedState = state
            lastPushDate = now
            logger.info("[LiveActivity][start] Started activity id=\(activity.id) sessions=\(sessions.count) audioLoaded=\(state.isAudioLoaded)")
        } catch {
            logger.error("[LiveActivity][start] Failed to start: \(error.localizedDescription)")
        }
    }

    /// [T-ios-live-activity-multisession-dismiss] Per-session completed state,
    /// keyed by session ID. Survives across updates so that a session marked
    /// completed stays rendered as completed even as other sessions continue
    /// to trigger updates.
    private var completedSessionSnapshots: [String: LiveSessionSnapshot] = [:]

    /// [T-ios-delete-session-live-activity] Session IDs the user has deleted.
    /// A deleted streaming session's teardown (VM cancel → endBackgroundProcessing)
    /// races with the delete cleanup and can call markSessionCompleted /
    /// finishActivity for the just-deleted session — this set makes those late
    /// calls no-ops so deleted content never reappears in the Live Activity.
    /// IDs are UUIDs, so the set stays tiny across an app run.
    private var deletedSessionIds: Set<String> = []

    @available(iOS 16.2, *)
    private func _handleSessionDeleted(_ sessionId: String) {
        deletedSessionIds.insert(sessionId)
        completedSessionSnapshots.removeValue(forKey: sessionId)

        let system = Activity<AgentActivityAttributes>.activities
        guard currentActivity != nil || !system.isEmpty else {
            logger.info("[LiveActivity][sessionDeleted] sid=\(sessionId.prefix(8)) — no activity on screen, nothing to clean")
            return
        }

        // What the activity would still represent after removing this session:
        // still-running tracker sessions plus whatever the last pushed state
        // shows (covers the soft-finished "completed" resting state, whose
        // sessions only live in lastPushedState).
        let shown = (lastPushedState as? AgentActivityAttributes.ContentState)?.sessions ?? []
        let remainingShown = shown.filter { $0.sessionId != sessionId }
        let remainingActive = SessionActivityTracker.shared.activeSessions.subtracting([sessionId])

        if !remainingActive.isEmpty {
            // Other sessions still running — the normal update path rebuilds
            // snapshots from the tracker (which no longer contains the deleted
            // id) and dedups if the pushed state wouldn't change. Also covers
            // a just-started activity whose initial content isn't recorded in
            // lastPushedState yet.
            logger.info("[LiveActivity][sessionDeleted] sid=\(sessionId.prefix(8)) — \(remainingActive.count) session(s) still active, updating in place")
            BackgroundKeepAliveManager.shared.updateLiveActivityIfNeeded(source: "sessionDeleted")
            return
        }

        if remainingShown.isEmpty && completedSessionSnapshots.isEmpty {
            logger.info("[LiveActivity][sessionDeleted] sid=\(sessionId.prefix(8)) — no sessions remain, ending activity immediately")
            _endActivity()
            return
        }

        guard shown.count != remainingShown.count else {
            // Deleted session isn't displayed; other sessions' activity stays as-is.
            logger.info("[LiveActivity][sessionDeleted] sid=\(sessionId.prefix(8)) — not displayed in activity, no-op")
            return
        }

        // Soft-finished activity showing only completed sessions — push the
        // filtered state directly so the deleted one disappears while the rest
        // keep lingering until foreground dismissal.
        guard let activity = currentActivity as? Activity<AgentActivityAttributes>,
              let prev = lastPushedState as? AgentActivityAttributes.ContentState else { return }
        logger.info("[LiveActivity][sessionDeleted] sid=\(sessionId.prefix(8)) — trimming soft-finished activity to \(remainingShown.count) session(s)")
        let state = AgentActivityAttributes.ContentState(
            activeSessionCount: remainingShown.count,
            sessions: remainingShown,
            carouselIndex: 0,
            soulName: prev.soulName,
            latestToolIcon: prev.latestToolIcon,
            minimalShowsTool: false,
            allCompleted: prev.allCompleted
        )
        carouselIndex = 0
        pushState(state, activity: activity)
    }

    @available(iOS 16.2, *)
    private func _markSessionCompleted(sessionId: String, lastMessage: String) {
        guard !deletedSessionIds.contains(sessionId) else {
            logger.info("[LiveActivity][markCompleted] sid=\(sessionId.prefix(8)) — session was deleted, skipping")
            return
        }
        let prev = (lastPushedState as? AgentActivityAttributes.ContentState)?
            .sessions.first { $0.sessionId == sessionId }
        var snap = prev ?? LiveSessionSnapshot(
            sessionId: sessionId, title: AppLocalized("Agent Task"),
            toolIcon: "checkmark.circle.fill", toolStatus: "", loopIteration: 0
        )
        snap.isCompleted = true
        snap.toolIcon = "checkmark.circle.fill"
        snap.toolStatus = AppLocalized("Completed")
        snap.lastMessage = Self.collapseLastMessage(lastMessage)
        completedSessionSnapshots[sessionId] = snap
        logger.info("[LiveActivity][markCompleted] sid=\(sessionId.prefix(8)) — other sessions still active, updating in-place")
        BackgroundKeepAliveManager.shared.updateLiveActivityIfNeeded(source: "sessionCompleted")
    }

    @available(iOS 16.2, *)
    /// - Parameter bypassRateLimit: skip the 3s/5s push throttle. Pass true ONLY
    ///   for discrete user-initiated events (the Live Activity audio toggle) that
    ///   demand immediate visual feedback. Agent-driven refreshes must leave this
    ///   false so the throttle keeps damping their high-frequency churn.
    ///   [T-live-activity-audio-toggle-latency]
    private func _updateActivity(sessions: [LiveSessionSnapshot], bypassRateLimit: Bool = false) {
        // [T-ios-live-activity-audio-toggle] Audio-only case: with no activity on
        // screen, restart if EITHER there are sessions OR audio is active (loaded).
        // The old guard was `!sessions.isEmpty` only, which blocked a pure-audio
        // Live Activity from ever (re)starting.
        if currentActivity == nil, !sessions.isEmpty || Self.isAudioActive() {
            logger.info("[LiveActivity][update] currentActivity is nil but sessions=\(sessions.count) audioActive=\(Self.isAudioActive()) — restarting")
            _startActivity(sessions: sessions)
            return
        }
        guard let activity = currentActivity as? Activity<AgentActivityAttributes> else {
            let systemCount = Activity<AgentActivityAttributes>.activities.count
            logger.info("[LiveActivity][update] skipped: currentActivity is nil but systemActivities.count=\(systemCount)")
            return
        }

        // [T-ios-live-activity-task-count] Trust the tracker for who's actually
        // running right now. The caller's `sessions` list is assembled upstream
        // and can carry entries whose tasks finished before this update landed;
        // if we forward them verbatim the Dynamic Island keeps showing a stale
        // running count (repro: 8 shown while only 2 isRunning).
        let liveIds = SessionActivityTracker.shared.activeSessions
        let filteredInput = sessions.filter { liveIds.contains($0.sessionId) }
        let activeSids = Set(filteredInput.map { $0.sessionId })
        var mergedSessions: [LiveSessionSnapshot] = filteredInput.map { s in
            guard let snap = completedSessionSnapshots[s.sessionId] else { return s }
            return snap
        }
        for (sid, snap) in completedSessionSnapshots where !activeSids.contains(sid) {
            mergedSessions.append(snap)
        }
        let liveCount = mergedSessions.reduce(0) { $0 + ($1.isCompleted ? 0 : 1) }

        // Prune completed snapshots whose sessions are no longer tracked.
        // They get one last push above, then are removed so sessions.count
        // converges to the tracker's active count instead of growing forever.
        for sid in completedSessionSnapshots.keys where !liveIds.contains(sid) {
            completedSessionSnapshots.removeValue(forKey: sid)
        }

        if mergedSessions.count > 1 {
            carouselIndex = (carouselIndex + 1) % mergedSessions.count
        } else {
            carouselIndex = 0
        }
        let toolIcon = Self.latestToolIcon()
        minimalShowsTool = toolIcon.isEmpty ? false : !minimalShowsTool

        // [T-ios-live-activity-audio-toggle] Stamp audio fields NOW so the dedup
        // comparison below sees them — otherwise a pure audio play/pause change
        // (identical sessions) would be treated as "unchanged" and skipped, and
        // the LA icon would never flip. `pushState` stamps again harmlessly
        // (idempotent — same values).
        let state = withAudioState(AgentActivityAttributes.ContentState(
            activeSessionCount: liveCount,
            sessions: mergedSessions,
            carouselIndex: carouselIndex,
            soulName: Self.currentSoulName(),
            latestToolIcon: toolIcon,
            minimalShowsTool: minimalShowsTool
        ))

        if let prev = lastPushedState as? AgentActivityAttributes.ContentState, prev == state {
            logger.info("[LiveActivity][update] #\(self.updateCount) DEDUP-SKIP (state unchanged)")
            return
        }

        // [T-live-activity-audio-toggle-latency] A push triggered by the user
        // TAPPING the audio button skips the throttle: the whole point of the
        // control is immediate feedback, and waiting out the interval left the
        // speaker glyph showing the wrong state for up to 5s in the background.
        // This is safe to exempt because it is a discrete, user-initiated event
        // (one tap = one push), not the high-frequency Agent tool-status churn
        // the throttle exists to damp — those callers still pass false and are
        // unaffected. The DEDUP-SKIP above still runs first, so an unchanged
        // state never reaches activity.update() even on this path.
        let isBackground = UIApplication.shared.applicationState != .active
        let minInterval: TimeInterval = isBackground ? 5.0 : 3.0
        let elapsed = Date().timeIntervalSince(lastPushDate)

        if elapsed < minInterval, !bypassRateLimit {
            pendingState = state
            if pendingPushWorkItem == nil {
                let delay = minInterval - elapsed
                logger.info("[LiveActivity][update] #\(self.updateCount) RATE-LIMIT defer \(String(format: "%.1f", delay))s (bg=\(isBackground))")
                let work = DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    self.pendingPushWorkItem = nil
                    guard let pending = self.pendingState as? AgentActivityAttributes.ContentState else { return }
                    self.pendingState = nil
                    self.pushState(pending, activity: activity)
                }
                pendingPushWorkItem = work
                DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
            }
            return
        }

        if bypassRateLimit, elapsed < minInterval {
            logger.info("[LiveActivity][update] #\(self.updateCount) RATE-LIMIT BYPASS (user audio toggle, elapsed=\(String(format: "%.1f", elapsed))s < \(String(format: "%.1f", minInterval))s)")
        }
        // Cancelling any queued work item + clearing pendingState here is what
        // keeps the bypass from double-pushing: a deferred push that was already
        // scheduled would otherwise fire moments after this one and flip the
        // glyph twice. pushState() refreshes lastPushDate, so the throttle window
        // restarts from this push for every subsequent (non-bypass) caller.
        pendingPushWorkItem?.cancel()
        pendingPushWorkItem = nil
        pendingState = nil
        pushState(state, activity: activity)
    }

    /// [T-ios-live-activity-audio-toggle] Overlay the current audio state onto a
    /// ContentState right before it's pushed/requested, so every live/running
    /// state (start, update, renew, delete-trim) carries fresh audio fields
    /// without editing each construction site. The end/cleanup "empty" states
    /// intentionally do NOT go through this (they represent teardown).
    @available(iOS 16.2, *)
    private func withAudioState(_ state: AgentActivityAttributes.ContentState) -> AgentActivityAttributes.ContentState {
        let audio = Self.currentAudioState()
        var s = state
        s.isAudioPlaying = audio.isPlaying
        s.isAudioLoaded = audio.isLoaded
        s.audioTitle = audio.title
        return withPrivacyRedaction(s)
    }

    /// [T-ios-live-activity-privacy-mode] Data-layer redaction: strip every
    /// conversation-derived field (session title / tool status / tool icon /
    /// last message) right before the state is pushed, so the widget degrades
    /// to "task position + status + timer" without restructuring any view.
    /// The Soul name is NOT redacted (user-chosen persona label). Chained from
    /// `withAudioState`, which is the single decorator every LIVE state passes
    /// through (both `pushState` and the initial `Activity.request`), so no
    /// construction site needs editing and no live route can bypass it.
    ///
    /// What deliberately survives redaction: `activeSessionCount`, `isCompleted`
    /// / `allCompleted` (drive the "N sessions" vs "N completed" text and the
    /// green checkmark), the audio-control fields, and `attributes.startDate`
    /// (the elapsed timer lives there, not in ContentState).
    @available(iOS 16.2, *)
    private func withPrivacyRedaction(_ state: AgentActivityAttributes.ContentState) -> AgentActivityAttributes.ContentState {
        guard BackgroundKeepAliveManager.shared.liveActivityPrivacyMode else { return state }
        var s = state
        s.privacyMode = true
        // [T-ios-live-activity-privacy-duration] soulName deliberately survives
        // redaction now: it is the user's chosen Soul persona (an identity
        // label), not conversation content — forcing "Minis" made Privacy Mode
        // gratuitously anonymous. The remaining redactions still strip
        // everything conversation-derived.
        // Neutral icon for the Dynamic Island trailing/expanded glyphs, and never
        // let the minimal icon flip to a tool glyph (it would leak which tool ran).
        s.latestToolIcon = s.allCompleted ? "checkmark.circle.fill" : Self.privacyNeutralIcon
        s.minimalShowsTool = false
        s.sessions = s.sessions.map { snap in
            var redacted = snap
            // "Agent Task" + the view's existing "1/2" index chip reads as
            // "第 1/2 个智能体任务" — task position without task content.
            redacted.title = Self.privacyTaskTitle
            // In-flight rows get a content-free "working" label; completed rows
            // show a content-free "Completed" beside the checkmark (the slot
            // where the reply summary appears outside Privacy Mode).
            redacted.toolStatus = snap.isCompleted ? "" : Self.privacyWorkingStatus
            redacted.toolIcon = snap.isCompleted ? "checkmark.circle.fill" : Self.privacyNeutralIcon
            redacted.lastMessage = snap.isCompleted ? Self.privacyCompletedStatus : ""
            return redacted
        }
        return s
    }

    /// Neutral placeholders used by Privacy Mode. Localized so the redacted
    /// strings still match the user's language.
    private static var privacyTaskTitle: String { AppLocalized("Agent Task") }
    private static var privacyWorkingStatus: String { AppLocalized("Working…") }
    private static var privacyCompletedStatus: String { AppLocalized("Completed") }
    private static let privacyNeutralIcon = "circle.dashed"

    @available(iOS 16.2, *)
    private func pushState(_ rawState: AgentActivityAttributes.ContentState, activity: Activity<AgentActivityAttributes>) {
        let state = withAudioState(rawState)
        lastPushedState = state
        lastPushDate = Date()
        updateCount += 1
        let count = updateCount
        let content = ActivityContent(state: state, staleDate: nil)
        logger.info("[LiveActivity][push] #\(count) active=\(state.activeSessionCount) sessions=\(state.sessions.count)")
        Task {
            await activity.update(content)
            logger.info("[LiveActivity][push] #\(count) activity.update() returned")
        }
    }

    @available(iOS 16.2, *)
    private func renewActivity(with state: AgentActivityAttributes.ContentState, isRetry: Bool = false) {
        guard let oldActivity = currentActivity as? Activity<AgentActivityAttributes> else { return }

        guard UIApplication.shared.applicationState == .active else {
            pendingStartState = state
            if !isRetry {
                logger.info("[LiveActivity][renew] skip: app not foreground (state=\(UIApplication.shared.applicationState.rawValue)) — keeping old id=\(oldActivity.id), scheduling retry")
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                    guard let self else { return }
                    guard UIApplication.shared.applicationState == .active else { return }
                    guard self.currentActivity != nil else { return }
                    guard !self.awaitingDismissal else { return }
                    guard self.pendingStartState != nil else { return }
                    self.renewActivity(with: state, isRetry: true)
                }
            } else {
                logger.info("[LiveActivity][renew] retry also skipped (state=\(UIApplication.shared.applicationState.rawValue)) — pendingStartState set for foreground recovery")
            }
            return
        }

        let oldId = oldActivity.id
        let sinceLastRenew = Int(Date().timeIntervalSince(lastRenewDate))
        logger.info("[LiveActivity][renew] ending old id=\(oldId) after \(sinceLastRenew)s to reset budget")

        currentActivity = nil
        let finalContent = ActivityContent(state: state, staleDate: nil)

        Task {
            await oldActivity.end(finalContent, dismissalPolicy: .immediate)
            logger.info("[LiveActivity][renew] old id=\(oldId) ended")

            let attributes = AgentActivityAttributes(startDate: self.startTime ?? Date())
            let content = ActivityContent(state: state, staleDate: nil)
            do {
                let newActivity = try Activity.request(
                    attributes: attributes,
                    content: content,
                    pushType: nil
                )
                self.currentActivity = newActivity
                self.lastRenewDate = Date()
                self.lastPushedState = state
                self.lastPushDate = Date()
                self.updateCount += 1
                self.pendingStartState = nil
                logger.info("[LiveActivity][renew] new id=\(newActivity.id) budget reset OK")
            } catch {
                // [T-ios-liveactivity-renew-bg-race] Plan B fallback: the foreground
                // guard above races with the async end (the app can background in the
                // ~27ms gap). If the restart still fails, stash the state and re-start
                // on the next foreground return so we recover instead of going dark.
                self.pendingStartState = state
                logger.error("[LiveActivity][renew] Failed to restart: \(error.localizedDescription) — pendingStart set for foreground recovery")
            }
        }
    }

    /// [T-ios-liveactivity-renew-bg-race] Re-start a Live Activity that a renew
    /// ended but couldn't restart (background race). Called on foreground return.
    @available(iOS 16.2, *)
    private func resumePendingStartIfNeeded() {
        guard let state = pendingStartState as? AgentActivityAttributes.ContentState else { return }
        guard UIApplication.shared.applicationState == .active else { return }
        guard Self.isActivityKitAvailable, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        // If something else already re-created an activity, just clear the flag.
        guard currentActivity == nil, !state.sessions.isEmpty else {
            pendingStartState = nil
            return
        }
        let attributes = AgentActivityAttributes(startDate: startTime ?? Date())
        let content = ActivityContent(state: state, staleDate: nil)
        do {
            let activity = try Activity.request(attributes: attributes, content: content, pushType: nil)
            currentActivity = activity
            lastRenewDate = Date()
            lastPushedState = state
            lastPushDate = Date()
            updateCount += 1
            pendingStartState = nil
            logger.info("[LiveActivity][renew] pendingStart recovered on foreground — new id=\(activity.id)")
        } catch {
            logger.error("[LiveActivity][renew] pendingStart recovery still failed: \(error.localizedDescription)")
        }
    }

    @available(iOS 16.2, *)
    private func _finishActivity(lastMessages provided: [String: String] = [:]) async {
        guard !isFinishing else {
            logger.info("[LiveActivity][finish] already finishing — skip duplicate call")
            return
        }
        isFinishing = true
        defer { isFinishing = false }

        guard let activity = currentActivity as? Activity<AgentActivityAttributes> else {
            logger.info("[LiveActivity][finish] no current activity — falling back to end")
            _endActivity()
            return
        }
        guard let running = lastPushedState as? AgentActivityAttributes.ContentState,
              !running.sessions.isEmpty else {
            logger.info("[LiveActivity][finish] no running snapshot — ending instead")
            _endActivity()
            return
        }

        pendingPushWorkItem?.cancel()
        pendingPushWorkItem = nil
        pendingState = nil

        // [T-ios-delete-session-live-activity] Never soft-finish into a state
        // that displays a deleted session. If every session was deleted, hard-end.
        let runningSessions = running.sessions.filter { !deletedSessionIds.contains($0.sessionId) }
        guard !runningSessions.isEmpty else {
            logger.info("[LiveActivity][finish] all sessions deleted — ending instead of soft-finish")
            _endActivity()
            return
        }
        awaitingDismissal = true

        let soul = Self.currentSoulName()
        var resolvedMessages: [String: String] = [:]
        for s in runningSessions {
            let raw: String
            if let msg = provided[s.sessionId], !msg.isEmpty {
                raw = msg
            } else {
                raw = await ChatStore.shared.getSession(s.sessionId)?.lastMessage ?? ""
            }
            resolvedMessages[s.sessionId] = Self.collapseLastMessage(raw)
        }
        let completedSessions = runningSessions.map { s -> LiveSessionSnapshot in
            var c = s
            c.isCompleted = true
            c.toolStatus = AppLocalized("Completed")
            c.lastMessage = resolvedMessages[s.sessionId] ?? ""
            return c
        }
        // [T-ios-live-activity-audio-toggle] Keep the audio control alive into the
        // completed resting state — the TTS narration of the just-finished reply
        // is often still playing, and the user should be able to pause it from the
        // lingering Live Activity.
        var finishedRaw = AgentActivityAttributes.ContentState(
            activeSessionCount: completedSessions.count,
            sessions: completedSessions,
            carouselIndex: 0,
            soulName: soul,
            latestToolIcon: "checkmark.circle.fill",
            minimalShowsTool: false,
            allCompleted: true
        )
        // [T-ios-live-activity-privacy-duration] Stamp the finish moment so the
        // widget can show the static total run time in the resting state.
        finishedRaw.finishedAt = Date()
        let finished = withAudioState(finishedRaw)
        guard self.awaitingDismissal,
              self.currentActivity as? Activity<AgentActivityAttributes> != nil else {
            logger.info("[LiveActivity][finish] dropped completed push — activity already dismissed")
            return
        }
        let content = ActivityContent(state: finished, staleDate: nil)
        await activity.update(content)
        self.lastPushedState = finished
        self.lastPushDate = Date()
        logger.info("[LiveActivity][finish] activity id=\(activity.id) flipped to completed (\(completedSessions.count) session(s)) — awaiting foreground dismissal")
    }

    /// Collapse a stored last-message body to a single trimmed line, capped.
    private static func collapseLastMessage(_ raw: String) -> String {
        // [T-bgnotif-internal-text-leak] Never display an internal bridge turn.
        // Every Live Activity text path funnels through here, including the
        // `_finishActivity` fallback that reads `ChatStore.getSession()?.lastMessage`
        // — and that DB value is the latest assistant row with no bridge
        // exclusion, so a task interrupted by a queued message would put a
        // model-facing instruction on the lock screen. Substituting a neutral
        // status keeps the capsule truthful without exposing the prompt.
        if RawMessage.isInternalBridgeText(raw) {
            return AppLocalized("Task interrupted by a new message")
        }
        let collapsed = raw
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String(collapsed.prefix(120))
    }

    @available(iOS 16.2, *)
    private func _endActivity() {
        awaitingDismissal = false
        isFinishing = false
        lastPushedState = nil
        pendingPushWorkItem?.cancel()
        pendingPushWorkItem = nil
        pendingState = nil
        completedSessionSnapshots = [:]
        let currentId = (currentActivity as? Activity<AgentActivityAttributes>)?.id ?? "nil"
        let finalState = AgentActivityAttributes.ContentState(
            activeSessionCount: 0,
            sessions: [],
            carouselIndex: 0,
            soulName: ""
        )
        let content = ActivityContent(state: finalState, staleDate: nil)

        let all = Activity<AgentActivityAttributes>.activities
        let allIds = all.map { $0.id }.joined(separator: ",")
        logger.info("[LiveActivity][end] currentActivity.id=\(currentId) systemActivities.count=\(all.count) systemIds=[\(allIds)]")
        for activity in all {
            let id = activity.id
            Task {
                await activity.end(content, dismissalPolicy: .immediate)
                await MainActor.run {
                    logger.info("[LiveActivity][end] ended id=\(id)")
                }
            }
        }

        if let current = currentActivity as? Activity<AgentActivityAttributes>,
           !all.contains(where: { $0.id == current.id }) {
            let id = current.id
            logger.warning("[LiveActivity][end] currentActivity id=\(id) missing from systemActivities — ending directly")
            Task {
                await current.end(content, dismissalPolicy: .immediate)
                await MainActor.run {
                    logger.info("[LiveActivity][end] orphan currentActivity ended id=\(id)")
                }
            }
        }

        currentActivity = nil
        startTime = nil
        carouselIndex = 0
    }
}
