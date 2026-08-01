import AVFoundation
import Combine
import CryptoKit
import Foundation
import ImageIO
import SQLite3
import UIKit
import os.log

private let logger = AppLogger(category: "AIChatVM")

// MARK: - View Model

@MainActor
final class AIChatViewModel: ObservableObject, SpeechControlling {

    /// Maximum characters returned in a single tool result to the model
    static let kMaxToolResultChars = 15000

    /// Maximum number of images retained in agent history when building the
    /// context sent to the model. Older images are replaced with a text
    /// placeholder (containing dimensions + byte size + persisted path) so
    /// they can be re-viewed via the `read_image` tool.
    static let kImageContextKeepCount = 20

    // MARK: - Image byte budgets (T-imgsize-13b7d81c)
    //
    // Anthropic rejects requests whose total inline image payload exceeds
    // 30 MB ("Downloaded image content cannot exceed 30MB" — the 30 MB
    // applies to decoded image bytes, NOT the base64-inflated wire form,
    // so we budget in raw bytes too). Other providers have similar but
    // undocumented caps. We enforce a tighter, pre-encoding budget so the
    // request never reaches the wire too big:
    //
    //   - Per image: 5 MB after JPEG re-encode. The Anthropic SDK's own
    //     per-image soft hint; well under the 30 MB request cap and
    //     ensures a single huge photo can't gobble the whole budget.
    //     Anything bigger runs the compressor ladder (smaller max edge +
    //     lower JPEG quality) until it fits or until we've shrunk it
    //     past readability.
    //   - Per message: 25 MB cumulative across all inlined images.
    //     Leaves ~5 MB headroom under Anthropic's 30 MB request cap for
    //     other parts of the payload (tool results, prior message
    //     attachments echoed back, etc.).
    //   - Above the message cap, additional images are dropped to a text
    //     placeholder (same fallback the count cap already uses).

    /// Hard ceiling per single image after compression. Matches the
    /// Anthropic SDK's per-image hint; well below the 30 MB request cap.
    static let kPerImageMaxBytes = 5 * 1024 * 1024

    /// Hard ceiling across all inlined images in one user message.
    /// 5 MB under Anthropic's 30 MB request cap, leaving headroom for
    /// tool results and prior-turn attachments still in context.
    static let kMessageImageMaxBytes = 25 * 1024 * 1024

    /// Hard ceiling on cumulative inline-image bytes across the FULL
    /// request body (all messages, all tool results). When a long
    /// session accumulates browser screenshots + attachments + read_image
    /// results past this cap, gpt-5.5 via factory.pub silently returns
    /// 200 + empty SSE + `finish_reason=stop` and the client treats the
    /// empty turn as completion (T-request-imgsize). Eldest images are
    /// elided to text placeholders here.
    static let kRequestImageMaxBytes = 25 * 1024 * 1024

    /// Hard ceiling on agent loop iterations within a single user turn.
    /// Backstop against runaway tool-call cycles that slip past
    /// `ToolLoopDetector` (e.g. visited args/results vary just enough to
    /// dodge the global circuit breaker). On reaching the limit the loop
    /// finalizes as resumable — see runAgentLoop's tail — so the user gets
    /// an inline explanation + Resume affordance rather than an unbounded
    /// runaway. Mirrors Android ChatViewModel.MAX_AGENT_TURNS.
    static let maxAgentTurns = 200
    /// [T-chat-auto-compact-inloop] Max in-loop auto-compactions per single
    /// user turn. Beyond this the loop stops as exhausted rather than
    /// compacting endlessly (which would defeat the maxAgentTurns backstop).
    static let maxInLoopCompactions = 3

    /// If non-nil, this VM is in read-only mode viewing a remote device's session.
    var remoteDeviceId: String?

    /// Short unique ID for this ViewModel instance (for debugging lifecycle).
    let vmInstanceId = UUID().uuidString.prefix(6)

    /// [T-deferred-sync-reload] Set to true when a
    /// `.cloudSyncDidFetchChanges` notification fires WHILE
    /// `isProcessing == true`. The `$isProcessing` sink drains it on
    /// the false-edge by replaying exactly one `reloadMessagesFromDB`
    /// so the user sees the post-send DB state without waiting for the
    /// next 60s sync timer.
    var pendingSyncReloadOnIdle = false

    // MARK: - Post-stop sync hold (T-ios-defer-icloud-sync-after-stop)

    /// While the agent loop runs (`isProcessing`), and for a grace window AFTER
    /// it stops, BOTH iCloud directions are held: no push to cloud, no pull-
    /// driven local reload. This stops a deferred sync from overwriting the
    /// just-finished candidate the instant the loop ends (field repro: content
    /// vanished + a brief loading flash right after Stop). The hold is released
    /// — and a single catch-up sync (flush dirty push + replay reload) runs —
    /// when the earliest of these happens: the 60s timer fires, the user leaves
    /// the session (back to the list), or the app is backgrounded.
    static let postStopSyncHoldSeconds: TimeInterval = 60
    /// Deadline of the current hold; nil when not held.
    private(set) var postStopSyncHoldUntil: Date?
    private var postStopSyncHoldTimer: Timer?

    /// True while the post-stop grace window is active.
    var isSyncHeld: Bool {
        guard let until = postStopSyncHoldUntil else { return false }
        return until > Date()
    }

    /// Enter the post-stop hold: pause SyncCore (blocks push) for the grace
    /// window and arm a timer that releases at the deadline. Called when the
    /// agent loop transitions to idle.
    @MainActor
    private func beginPostStopSyncHold() {
        let until = Date().addingTimeInterval(Self.postStopSyncHoldSeconds)
        postStopSyncHoldUntil = until
        SyncCore.shared.pause(for: Self.postStopSyncHoldSeconds)
        postStopSyncHoldTimer?.invalidate()
        postStopSyncHoldTimer = Timer.scheduledTimer(withTimeInterval: Self.postStopSyncHoldSeconds, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.releasePostStopSyncHold(reason: "timer-60s") }
        }
        logger.info("[SyncHold] BEGIN hold until \(until) (\(Int(Self.postStopSyncHoldSeconds))s) sid=\(self.sessionId?.prefix(8) ?? "nil")")
    }

    /// Release the hold early/at-deadline and run one catch-up sync: resume
    /// SyncCore, flush deferred dirty (push), and replay any pending reload
    /// (pull). Idempotent — safe to call from any of the release triggers.
    @MainActor
    func releasePostStopSyncHold(reason: String) {
        guard postStopSyncHoldUntil != nil else { return }
        postStopSyncHoldUntil = nil
        postStopSyncHoldTimer?.invalidate()
        postStopSyncHoldTimer = nil
        logger.info("[SyncHold] RELEASE reason=\(reason) sid=\(self.sessionId?.prefix(8) ?? "nil") — resuming sync + catch-up")
        SyncCore.shared.resume()
        // Push: flush whatever the agent turn marked dirty.
        Task { await ChatStore.shared.flushPendingSyncDirty() }
        // Pull: replay a deferred reload now that it's safe.
        if pendingSyncReloadOnIdle, !isProcessing, sessionId != nil {
            pendingSyncReloadOnIdle = false
            Task { @MainActor in await self.reloadMessagesFromDB(reason: "syncHoldReleased(\(reason))") }
        }
    }

    /// Cancellable for tracking `isProcessing` → `SessionActivityTracker`.
    private var activityTrackingCancellable: AnyCancellable?
    private var captureSuppressCancellable: AnyCancellable?
    private var mediaPreemptCancellable: AnyCancellable?
    private var voiceStateCancellable: AnyCancellable?
    /// Observer for font-size changes to invalidate cached attributed strings.
    private var fontChangeObserver: Any?
    /// Observer for iCloud sync changes to reload messages.
    private var syncChangeObserver: Any?
    /// Observer for group binding changes to update in-memory context limit.
    /// Observer that releases the post-stop sync hold when the app backgrounds.
    private var backgroundSyncHoldObserver: Any?
    /// [T-tool-bg-suspended-hint] Observer firing on foreground return; stamps
    /// still-running browser tool blocks that have spanned a background period
    /// and exceeded the long-run threshold so the chat capsule can hint at the
    /// likely background slowdown. In-memory only (not persisted).
    private var bgHintForegroundObserver: Any?
    private var snapshotForegroundObserver: Any?
    private var detachedLoopEndObserver: Any?
    /// Timestamp of the most recent background-entry, used to detect that a
    /// long-running browser task actually spanned a background period.
    private var bgHintBackgroundEntryDate: Date?
    /// A browser tool running longer than this when the app returns to the
    /// foreground (after having been backgrounded) gets the suspension hint.
    private static let bgHintBrowserThreshold: TimeInterval = 5 * 60

    init() {
        logger.info("🔄SESSION [vm=\(self.vmInstanceId)] init — new AIChatViewModel created")

        fontChangeObserver = NotificationCenter.default.addObserver(
            forName: .fontSettingsMessageBaseChanged, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in self.invalidateAttributedStringCaches() }
        }

        // [T-ios-defer-icloud-sync-after-stop] Backgrounding is a release
        // trigger for the post-stop sync hold — let the held catch-up sync run
        // before the app suspends, instead of waiting out the 60s window.
        backgroundSyncHoldObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            // [T-tool-bg-suspended-hint] Remember when we went to background so
            // a foreground return can tell a browser task actually spanned it.
            self.bgHintBackgroundEntryDate = Date()
            Task { @MainActor in self.releasePostStopSyncHold(reason: "background") }
        }

        // [T-tool-bg-suspended-hint] On foreground return, flag any still-running
        // browser tool that spanned the background period and has now exceeded the
        // long-run threshold — the WebView/JS is likely throttled or stalled while
        // backgrounded, so the capsule surfaces the "enable enhanced background"
        // hint. In-memory only; mirrors the BackgroundInterruptionTracker gate
        // (skip when enhanced background is already effective).
        bgHintForegroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in self.markLongBackgroundBrowserTasks() }
        }

        // [T-ios-bg-blocks-lost] On foreground return, replay the deferred
        // snapshot rebuild if isProcessing went false while backgrounded.
        // When the app is in background with streamingUIUpdatesSuspended=false,
        // bind4b may call applySnapshot but UIKit can silently drop or
        // incompletely apply UICollectionView diffs while not in .active.
        // The isProcessing didSet sets needsSnapshotReloadOnResume=true for
        // this case, but clearStreamingUIUpdatesSuspended won't fire (the
        // flag was never set). This observer is the missing trigger.
        // [T-ios-bg-blocks-lost] Replay the deferred snapshot rebuild when the
        // app returns to .active. Uses didBecomeActiveNotification (not
        // willEnterForegroundNotification) because the deferred path triggers
        // for BOTH .background and .inactive states — inactive→active is
        // common (e.g. Control Center swipe, notification shade pull) and
        // willEnterForeground only fires from background, missing the
        // inactive case entirely.
        snapshotForegroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                // [T-blockslost-defensive defect ②] Gate on being the
                // CURRENTLY-DISPLAYED session. This observer was written
                // (02970b7b) when the flag could only mean suspended /
                // backgrounded — cases where foreground-return implies this
                // VM's view is mounted. b5f6a1e1 later added the
                // "no subscriber" deferral (user viewing ANOTHER session):
                // for that case a foreground-return must NOT consume the
                // flag — the replay signal would fire with no CollectionView
                // subscriber bound and be lost, starving
                // consumeDeferredSnapshotIfNeeded (the in-app switch-back
                // path) of the flag it exists to consume.
                let sid = self.sessionId
                let gatePassed = (sid != nil && Self.activeSessionId == sid)
                let needsFlag = self.needsSnapshotReloadOnResume
                logger.info("[BlocksLost] didBecomeActive gate sid=\(sid?.prefix(8) ?? "nil") vm=\(self.vmInstanceId) activeSessionId=\(Self.activeSessionId?.prefix(8) ?? "nil") gatePassed=\(gatePassed) needsFlag=\(needsFlag)")
                guard needsFlag else { return }
                guard gatePassed else {
                    // Keep the flag: the user is on another session; the
                    // onAppear-reuse path consumes it when they switch back.
                    return
                }
                self.needsSnapshotReloadOnResume = false
                let totalBlocks = self.messages.reduce(0) { $0 + $1.blocks.count }
                let lastBlocks = self.messages.last?.blocks.count ?? 0
                logger.info("[BlocksLost] REPLAY retrySnapshot — app became active. sid=\(sid?.prefix(8) ?? "nil") totalBlocks=\(totalBlocks) lastMsgBlocks=\(lastBlocks) msgCount=\(self.messages.count)")
                self.retrySnapshotReloadSignal.send()
            }
        }

        // [T-ios-detached-vm-loop-end] When the agent loop finishes on a
        // detached VM (user navigated away mid-loop, or VM was evicted and
        // recreated), that VM posts .sessionAgentLoopDidEnd. The currently-
        // displayed VM for the same session picks it up here and reloads
        // from DB so the final tool results / assistant text appear.
        detachedLoopEndObserver = NotificationCenter.default.addObserver(
            forName: .sessionAgentLoopDidEnd, object: nil, queue: .main
        ) { [weak self] notification in
            guard let self, let sid = self.sessionId else { return }
            guard let notifSid = notification.object as? String, notifSid == sid else { return }
            guard Self.activeSessionId == sid else { return }
            guard !self.isProcessing else { return }
            logger.info("[BlocksLost] DETACHED reload — agent loop ended on another VM, reloading. sid=\(sid.prefix(8)) vm=\(self.vmInstanceId)")
            Task { @MainActor in
                await self.reloadMessagesFromDB(reason: "detachedVMLoopEnd")
            }
        }

        syncChangeObserver = NotificationCenter.default.addObserver(
            forName: .cloudSyncDidFetchChanges, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, let sid = self.sessionId else { return }
            // Only the foreground-visible VM reacts. ViewModelCache holds
            // every session the user has ever opened in this launch, and
            // each instance is a notification subscriber — without this
            // gate, one inbound batch fans out into N concurrent
            // reloadMessagesFromDB calls, each of which loadMessages-es
            // its session (potentially thousands of rows + JSON decode).
            // The hidden VMs catch up on their own via the
            // AIChatView.onAppear REUSING branch, which already does the
            // same hash+count comparison before reloading.
            if Self.activeSessionId != sid { return }
            // [T-deferred-sync-reload] If a send is in flight, don't
            // reload mid-stream — that would re-snapshot messages and
            // could clobber the streaming assistant block / hide the
            // "Minis is thinking" indicator. Flip a flag instead; the
            // $isProcessing observer below picks it up and replays one
            // reload as soon as the send completes. This avoids waiting
            // the full sync-timer cycle (~60s) for the next refresh.
            if self.isProcessing {
                self.pendingSyncReloadOnIdle = true
                logger.info("[ReloadTrace] DEFER reason=cloudSyncDidFetchChanges sid=\(sid.prefix(8)) isProcessing=true — will reload on idle")
                return
            }
            Task { @MainActor in
                await self.reloadMessagesFromDB(reason: "cloudSyncDidFetchChanges")
            }
        }

        activityTrackingCancellable = $isProcessing
            .removeDuplicates()
            .sink { [weak self] processing in
                guard let self else { return }
                // Key selection:
                //   - Before the draft has a real session (sessionId == nil),
                //     fall back to draftId so the sidebar proxy row still
                //     reflects activity.
                //   - Once sessionId exists, use ONLY it; mixing in draftId
                //     caused leaks — ensureSession migrates draft→real but
                //     draftId stays set for sidebar UX, and a second send()
                //     would setActive(draftId) again while the first send's
                //     setInactive(sessionId) wasn't reliably paired.
                let activeKey: String
                if let sid = self.sessionId {
                    activeKey = sid
                } else if let did = self.draftId {
                    activeKey = did
                } else {
                    return
                }
                let src = "vm=\(self.vmInstanceId) isProcessing=\(processing)"
                logger.info("🔄SESSION [vm=\(self.vmInstanceId)] isProcessing=\(processing) key=\(activeKey) draftId=\(self.draftId ?? "nil") sessionId=\(self.sessionId ?? "nil")")
                if processing {
                    SessionActivityTracker.shared.setActive(activeKey, source: src)
                    if let sid = self.sessionId {
                        Task {
                            if let session = await ChatStore.shared.getSession(sid),
                               let title = session.title, !title.isEmpty {
                                await MainActor.run {
                                    SessionActivityTracker.shared.updateSessionTitle(sid, title: title)
                                }
                            }
                        }
                    }
                    // [T-ios-session-running-shows-pause-badge] Starting/resuming
                    // a loop means this session is no longer paused — drop any
                    // stale ⏸ immediately, independent of the canResume didSet
                    // (which only fires on a value transition and can miss a queue
                    // entry seeded by the launch reconcile or a prior VM).
                    if let sid = self.sessionId {
                        SessionBadgeStore.shared.remove(.paused, for: sid)
                    }
                    // [T-ios-ipad-sidebar-spinner-stale-after-resend] Re-assert
                    // the draft→real alias on every activation. On iPad the
                    // sidebar keeps showing the new chat's row under its DRAFT
                    // id (ContentView.displaySessions builds a proxy with
                    // id=draftId so List(selection:) stays valid), and that row
                    // reads `isActive(draftId)`. The alias was registered once at
                    // draft→real migration, but `setInactive(real)` CLEARS it
                    // (it drops aliases pointing at a now-inactive session). So
                    // after the first stop the alias is gone; a resend calls
                    // setActive(real) but the sidebar proxy — still keyed by
                    // draftId — no longer resolves, and its spinner never spins
                    // again until the user navigates away (which collapses the
                    // proxy to the real id). Re-registering the alias here on
                    // each true-transition keeps the draft-keyed row in sync.
                    if let did = self.draftId, did != activeKey, self.sessionId != nil {
                        SessionActivityTracker.shared.setDraftAlias(draft: did, real: activeKey)
                    }
                } else {
                    SessionActivityTracker.shared.setInactive(activeKey, source: src)
                    // [T-ios-session-completed-badge-not-cleared] The loop just
                    // stopped. If it didn't stop in a resumable state, the session
                    // completed normally — clear any ⏸ that lingers. The canResume
                    // didSet handles the true→false transition, but a turn that
                    // finished with canResume ALREADY false (the common completion
                    // path: line ~4044 sets false even when it was false → no
                    // didSet) leaves a stale badge (seeded by reconcile / a prior
                    // VM) untouched. Gating on !canResume preserves a genuine
                    // interruption (Stop / bg-suspend) that ends the loop with
                    // canResume=true — that badge must stay.
                    if !self.canResume, let sid = self.sessionId {
                        SessionBadgeStore.shared.remove(.paused, for: sid)
                    }
                    // Safety net: if the draft id ever ended up in the tracker
                    // (e.g. from a prior send() that started before ensureSession
                    // returned), scrub it on this transition too so it can't
                    // orphan.
                    if let did = self.draftId, did != activeKey {
                        SessionActivityTracker.shared.setInactive(did, source: src + " (stray draftId)")
                    }
                    self.browserTabPool.releaseAllTabs()
                    // [T-deferred-sync-reload] Drain any sync reloads that
                    // arrived during the send so the UI catches up with the DB
                    // now that the stream finished.
                    // [T-ios-defer-icloud-sync-after-stop] BUT not while the
                    // post-stop hold is active — replaying a reload here is the
                    // exact overwrite we're preventing. Keep the flag armed; the
                    // hold release (timer / leave / background) replays it.
                    if self.pendingSyncReloadOnIdle, let sid = self.sessionId {
                        if self.isSyncHeld {
                            logger.info("[ReloadTrace] HOLD reason=isProcessing→false sid=\(sid.prefix(8)) — deferred reload kept until post-stop hold releases")
                        } else {
                            self.pendingSyncReloadOnIdle = false
                            logger.info("[ReloadTrace] DRAIN reason=isProcessing→false sid=\(sid.prefix(8)) — replaying deferred sync reload")
                            Task { @MainActor in
                                await self.reloadMessagesFromDB(reason: "deferredSyncReloadOnIdle")
                            }
                        }
                    }
                }
            }

        // When the mic starts capturing, immediately silence any in-progress
        // reply TTS (capture and playback are mutually exclusive — no echo). New
        // speech is already suppressed by `canSpeakNow`; this stops the current
        // System utterance too (the cloud queue is stopped by the panel).
        captureSuppressCancellable = VoiceModePreference.shared.$isCapturing
            .removeDuplicates()
            .sink { [weak self] capturing in
                if capturing { self?.stopSpeech() }
            }

        // A media attachment preempted reply TTS — stop the System synthesizer
        // (cloud queue already stopped by the coordinator). Don't auto-resume.
        mediaPreemptCancellable = NotificationCenter.default
            .publisher(for: AudioSessionCoordinator.replyTTSPreemptedNotification)
            .sink { [weak self] _ in
                guard let self else { return }
                if self.speechSynthesizer.isSpeaking {
                    self.speechSynthesizer.stopSpeaking(at: .immediate)
                }
                self.isReadingAloud = false
                self.speechPaused = false
            }

        // Read-replies is now GLOBAL state (VoiceOutputState.shared). Forward its
        // changes so views observing `vm.speakEnabled` refresh, and stop this VM's
        // System TTS the moment it's turned off anywhere.
        voiceStateCancellable = VoiceOutputState.shared.$isEnabled
            .dropFirst()                 // skip the synchronous current-value emit
            .removeDuplicates()
            .sink { [weak self] enabled in
                guard let self else { return }
                self.objectWillChange.send()
                if !enabled { self.stopSpeech() }
            }
    }

    deinit {
        let vmId = vmInstanceId
        // Snapshot tracker-relevant state at deinit so we can spot leaks: if
        // isProcessing was still true when the vm is about to die, the
        // corresponding sessionId/draftId keys will remain stuck in
        // `SessionActivityTracker.activeSessions` because the `$isProcessing`
        // sink won't fire once self is gone. Fields are MainActor-isolated
        // and deinit is nonisolated; `_deinitSnapshot` is updated on the
        // main actor during normal flow so we can read it here safely.
        logger.info("🔄SESSION [vm=\(vmId)] deinit lastKnown: \(self._deinitSnapshot)")
        if let observer = fontChangeObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = syncChangeObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = backgroundSyncHoldObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = bgHintForegroundObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = snapshotForegroundObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = detachedLoopEndObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    /// [T-tool-bg-suspended-hint] On foreground return, mark still-running
    /// browser tool blocks that spanned the just-ended background period and
    /// have now run longer than `bgHintBrowserThreshold`. Skips entirely when
    /// enhanced background is already effective (nothing to suggest) — same
    /// gate as the BackgroundInterruptionTracker banner. In-memory only.
    @MainActor
    func markLongBackgroundBrowserTasks() {
        defer { bgHintBackgroundEntryDate = nil }
        guard let _ = bgHintBackgroundEntryDate else { return }
        guard !BackgroundKeepAliveManager.shared.enhancedBackgroundEffective else { return }
        let now = Date()
        for msg in messages where msg.role == .assistant {
            for block in msg.blocks {
                guard case .browserTool = block.kind else { continue }
                guard case .running = block.toolStatus else { continue }
                guard let start = block.toolStartTime,
                      now.timeIntervalSince(start) > Self.bgHintBrowserThreshold else { continue }
                block.wasBackgroundSuspended = true
            }
        }
    }

    /// Clear all cached attributed strings so they re-render with the new font size.
    /// Called by the coordinator before reconfiguring cells to guarantee ordering.
    @MainActor
    func invalidateAttributedStringCaches() {
        for msg in messages {
            for block in msg.blocks {
                if block.cachedAttributedString != nil {
                    block.cachedAttributedString = nil
                }
            }
        }
        if !transitionSuspended { objectWillChange.send() }
    }

    // MARK: - Published State

    @Published var messages: [ChatMessage] = []
    /// [T-voice-input-mode-preference-ios] True when a voice session
    /// contributed to the CURRENT composition (set on recording start by any
    /// path: mic tap, quick action, preference auto-entry). Cleared whenever
    /// the composer empties, so send() can detect "composed without voice
    /// since the composer was last cleared" and flip the remembered
    /// input-mode preference back to "text".
    var voiceUsedInComposition = false
    @Published var inputText = "" {
        didSet {
            if inputText.isEmpty && !oldValue.isEmpty {
                voiceUsedInComposition = false
                #if DEBUG
                logger.info("🔑DRAFT [vm=\(self.vmInstanceId)] inputText CLEARED (was '\(String(oldValue.prefix(30)))') sessionId=\(self.sessionId ?? "nil") draftId=\(self.draftId ?? "nil") isProcessing=\(self.isProcessing)")
                #else
                logger.info("🔑DRAFT [vm=\(self.vmInstanceId)] inputText CLEARED (was \(oldValue.count)ch) sessionId=\(self.sessionId ?? "nil") draftId=\(self.draftId ?? "nil") isProcessing=\(self.isProcessing)")
                #endif
            }
            updateSlashMenuState()
            updateMentionMenuState()
        }
    }
    /// Caret position (character offset) published by the input text view.
    /// Used by the `@` mention detector to locate the active token.
    @Published var inputCaret: Int = 0 {
        didSet {
            updateMentionMenuState()
            // If the caret arrived at the desired location, clear the pending
            // request so subsequent edits aren't forced back.
            if let p = pendingCaret, p == inputCaret {
                pendingCaret = nil
            }
        }
    }
    /// One-shot request to move the caret to a specific offset. Cleared once
    /// the text view publishes a matching caret position via `inputCaret`.
    @Published var pendingCaret: Int?
    /// Plain string capture of the vm state for diagnostic logging from deinit
    /// (which is nonisolated and can't touch @Published MainActor properties).
    /// Updated on every isProcessing flip and on send entry.
    nonisolated(unsafe) var _deinitSnapshot: String = "isProcessing=false session=nil draft=nil"

    @Published var isProcessing = false {
        didSet {
            _deinitSnapshot = "isProcessing=\(isProcessing) session=\(sessionId ?? "nil") draft=\(draftId ?? "nil")"
            if isProcessing && !oldValue {
                // Agent loop starting — defer iCloud sync sends until completion
                Task { await ChatStore.shared.setSyncSendDeferred(true) }
                // [T-ios-defer-icloud-sync-after-stop] A new turn supersedes any
                // pending post-stop hold; clear it WITHOUT a catch-up sync (the
                // new turn re-pauses sync via setSyncSendDeferred anyway).
                if postStopSyncHoldUntil != nil {
                    postStopSyncHoldUntil = nil
                    postStopSyncHoldTimer?.invalidate()
                    postStopSyncHoldTimer = nil
                    logger.info("[SyncHold] CLEAR — new turn started, superseding post-stop hold")
                }
                // Begin watching for main-thread hangs while streaming is
                // active. Captures a backtrace whenever the main runloop
                // doesn't drain for >1s, plus a snapshot of streamed text
                // around the stall, so we can confirm whether the 0x8BADF00D
                // watchdog lands inside _fillLayoutHole, SwiftUI Layout, or
                // elsewhere — and correlate with the content being rendered.
                StreamingHangLogger.shared.acquire(reason: "isProcessing=true session=\(sessionId ?? "nil")")
            } else if !isProcessing && oldValue {
                // [T-ios-defer-icloud-sync-after-stop] Agent loop finished. Do
                // NOT flush the deferred iCloud push or replay a pull reload now
                // — that immediate sync is what overwrote the just-finished
                // candidate. Instead enter the post-stop hold: both directions
                // stay paused for the grace window, then a single catch-up sync
                // runs on release (60s timer / leave session / background).
                beginPostStopSyncHold()
                StreamingHangLogger.shared.release(reason: "isProcessing=false session=\(sessionId ?? "nil")")
                // [T-ios-ui-frozen-on-tool-while-loop-runs] Force one snapshot
                // re-apply from the CURRENT in-memory messages at loop end.
                //
                // Why this is needed: during a multi-turn agent loop, tool blocks
                // are appended to the EXISTING last-assistant ChatMessage. The
                // messages ARRAY doesn't change, so the Coordinator's `$messages`
                // sink never re-fires and the per-message block-count subscription
                // can miss the appends — the new tool capsules are in the data
                // model but never get cells. The loop-end full reload that should
                // rebuild them goes through `reloadMessagesFromDB`, which is held
                // by the post-stop SyncHold and then SKIP-LOADs (`hashSame=true`,
                // because the DB baseline already advanced as the loop persisted),
                // so it never repaints. Result: the UI froze on the last-rendered
                // capsule for minutes while many turns ran un-rendered.
                //
                // `retrySnapshotReloadSignal` already does exactly the right thing
                // — re-applies the diffable snapshot from `vm.messages` even when
                // the array identity is unchanged (its original use: in-place
                // block mutation on retry). It is a pure UI re-render from memory:
                // it does NOT touch the DB / reload / SyncHold, so it can't
                // overwrite unpersisted content. Idempotent (the snapshot diff is
                // a no-op when nothing changed).
                //
                // CRITICAL guard — only fire when FOREGROUND and not UI-suspended:
                // the Coordinator's applySnapshot does a synchronous
                // `layoutIfNeeded()` (cell creation + self-sizing) on the main
                // thread. For a large history that is heavy work; running it while
                // backgrounded risks the 0x8BADF00D background-render watchdog
                // SIGKILL that `streamingUIUpdatesSuspended` exists to prevent. If
                // the loop ends while suspended/backgrounded, the Coordinator
                // already has `hasPendingSnapshot=true` and the suspend-resume hook
                // (T-ios-ui-blocks-lost-suspend-resume) flushes it on the next
                // foreground transition — so we must NOT force-apply here. We only
                // cover the foreground case (the actual freeze: block-count sub
                // missed in-place appends with NO suspend involved). Also gated on
                // `!transitionSuspended` so a session-switch outgoing VM doesn't
                // poke a tearing-down view graph (same guard as the manual
                // objectWillChange.send sites).
                // [T-blockslost-defensive defect ③] The flag ASSIGNMENT is no
                // longer wrapped in the `!transitionSuspended` gate: setting
                // `needsSnapshotReloadOnResume` touches no view, so the gate —
                // which exists to keep view-graph pokes out of the 0.4s
                // session-switch animation window — was pure collateral here.
                // A loop that ended inside that window previously left NO flag
                // behind, so nothing ever replayed for it. The gate still
                // protects the only view-touching action (the immediate
                // signal send below), whose conditions are unchanged.
                let inTransitionWindow = transitionSuspended
                if !inTransitionWindow,
                   !streamingUIUpdatesSuspended,
                   UIApplication.shared.applicationState == .active,
                   let sid = sessionId, Self.activeSessionId == sid {
                    retrySnapshotReloadSignal.send()
                } else {
                    needsSnapshotReloadOnResume = true
                    let totalBlocks = messages.reduce(0) { $0 + $1.blocks.count }
                    let lastBlocks = messages.last?.blocks.count ?? 0
                    let reason: String
                    if streamingUIUpdatesSuspended {
                        reason = "suspended"
                    } else if UIApplication.shared.applicationState != .active {
                        reason = "backgrounded"
                    } else if let sid = sessionId, Self.activeSessionId != sid {
                        reason = "no subscriber (active=\(Self.activeSessionId?.prefix(8) ?? "nil"))"
                    } else {
                        reason = "transition window"
                    }
                    logger.info("[BlocksLost] DEFERRED retrySnapshot — \(reason) at loop end. sid=\(sessionId?.prefix(8) ?? "nil") vm=\(vmInstanceId) transitionWindow=\(inTransitionWindow) totalBlocks=\(totalBlocks) lastMsgBlocks=\(lastBlocks) msgCount=\(messages.count)")
                }
                // [T-ios-detached-vm-loop-end] When an agent loop finishes on
                // a VM that is NOT the currently-displayed one (user navigated
                // away mid-loop), the final DB writes are invisible to the
                // displayed VM. Post a notification so the active VM for this
                // session reloads from DB and picks up the new data.
                if let sid = sessionId, Self.activeSessionId != sid {
                    logger.info("[BlocksLost] DETACHED loop end — posting reload for sid=\(sid.prefix(8)) (active=\(Self.activeSessionId?.prefix(8) ?? "nil"))")
                    NotificationCenter.default.post(name: .sessionAgentLoopDidEnd, object: sid)
                }
                // Also drain any skill-filesystem rescan that the AI's
                // shell tools may have queued during this turn. We hold
                // the rescan off the hot path so it runs once at quiet
                // time, not interleaved with every iSH write event.
                if #available(iOS 17.0, *) {
                    SkillFilesystemNotifier.shared.drainIfDirty(reason: "agent turn finished")
                }
            }
        }
    }
    @Published var canResume = false {
        didSet {
            // [T-session-paused-badge-active-false-positive] Drive the session-
            // list PAUSED badge directly off canResume — the authoritative "this
            // session is interrupted (tap Resume)" flag and the single chokepoint
            // over every canResume setter. true → badge on; false (resumed / new
            // turn / completed) → badge off. Replaces the prior push at the
            // background-interruption point + clear-on-open, so a session merely
            // glanced at keeps its badge and a running/resolved session never
            // shows one. (Mirrors the Android canResume collector.)
            if oldValue != canResume, let sid = sessionId {
                if canResume {
                    SessionBadgeStore.shared.pushFront(.paused, for: sid)
                } else {
                    SessionBadgeStore.shared.remove(.paused, for: sid)
                }
            }
            // [T-ios-cancel-reload-overwrite] reloadMessagesFromDB defers a
            // sync reload while `canResume` is true and the in-memory candidate
            // holds unpersisted interrupted content (so the reload can't
            // overwrite it). Once the interrupted state clears (Resume finished
            // / a normal turn completed and persisted everything), replay the
            // deferred reload so iCloud-synced changes finally land.
            if oldValue && !canResume && pendingSyncReloadOnIdle, let sid = sessionId {
                // [T-ios-retry-usermsg-vanish] Don't replay the deferred reload
                // if canResume is being cleared as the FIRST step of a retry
                // truncation — the reload would race the in-flight
                // messages/DB truncation and can drop the retried user message
                // from the UI. Leave pendingSyncReloadOnIdle armed; the retry's
                // own completion (isProcessing→false) drains it safely.
                if isTruncatingForRetry {
                    logger.info("[ReloadTrace] SKIP reason=canResume→false sid=\(sid.prefix(8)) — retry truncation in progress; deferred reload stays armed")
                } else {
                    pendingSyncReloadOnIdle = false
                    logger.info("[ReloadTrace] DRAIN reason=canResume→false sid=\(sid.prefix(8)) — replaying deferred sync reload after interrupted state cleared")
                    Task { @MainActor [weak self] in
                        await self?.reloadMessagesFromDB(reason: "deferredAfterResumeCleared")
                    }
                }
            }
        }
    }
    @Published var isSuspended = false
    @Published var selectedModel: LLMModel = .claudeHaiku45
    @Published var isLoadingSession = false
    /// False until the FIRST loadSession() for this vm completes. Lets the view
    /// distinguish "entering the session, nothing rendered yet" (show the soft
    /// centered loading card) from every later reload — iCloud sync, compaction,
    /// stale-cache refresh — which must keep the unobtrusive spinner and never
    /// cover content the user is already reading. Not @Published on purpose: it
    /// only ever changes alongside isLoadingSession, whose publish already
    /// re-evaluates the view.
    var hasCompletedInitialLoad = false
    @Published var errorMessage: String?
    /// Transient banner / toast message for in-loop notices (e.g. "older
    /// N image(s) elided from request to fit 25MB budget"). Set by the
    /// request-level image budget pass when it had to drop history images;
    /// UI clears it after a short display window.
    @Published var transientNotice: String?
    @Published var autoRetryAttempt: Int = 0
    @Published var autoRetryCountdown: Int = 0
    /// [T-ios-empty-after-toolresult-reminder] Guards the one-shot
    /// `<system-reminder>` retry when the server returns an empty response
    /// right after a tool result. Set the first time we inject the reminder in
    /// a given agent loop; if the reminder round is ALSO empty we report an
    /// error instead of injecting again — so it can never loop. Reset at the
    /// start of each agent loop (a fresh user turn).
    var didInjectEmptyToolReminderThisRun = false
    /// Incremented when a model fallback occurs so the UI can animate the model name change.
    @Published var fallbackTrigger: Int = 0
    /// Signal to trigger scroll-to-bottom in views (not @Published to avoid full view diff).
    let scrollToBottomSignal = PassthroughSubject<Void, Never>()
    /// Fired when the view model wants the composer to grab keyboard focus
    /// (e.g. after a `/skill` tap fills the input — the user expects to
    /// immediately type their question without an extra tap on the field).
    let requestComposerFocusSignal = PassthroughSubject<Void, Never>()
    /// Force scroll-to-bottom AND re-enable auto-scroll. Used only for explicit user actions
    /// (send, enqueue, session reuse) — NOT during streaming, so user scroll position is respected.
    let forceScrollToBottom = PassthroughSubject<Void, Never>()
    /// Force scroll-to-top (first message). Used by the floating "scroll to
    /// first message" button — a counterpart to forceScrollToBottom that does
    /// NOT change auto-scroll mode (jumping to the top is always a browse action).
    let forceScrollToTop = PassthroughSubject<Void, Never>()
    /// Signal sent when a text block transitions from empty to non-empty content.
    /// The coordinator reconfigures the affected cell so UIHostingConfiguration
    /// re-measures its height (UIKit doesn't always trigger preferredLayoutAttributesFitting
    /// reliably when the SwiftUI body changes inside a UIHostingConfiguration).
    let blockContentFilledSignal = PassthroughSubject<(messageId: UUID, blockId: UUID), Never>()
    /// Content offset Y published on each scroll event (for debug jitter monitoring).
    let contentOffsetYSignal = PassthroughSubject<CGFloat, Never>()
    /// Content size height delta signal (for debug jitter monitoring).
    let contentSizeDeltaSignal = PassthroughSubject<CGFloat, Never>()
    /// Fired after a retry truncation to force the message list to re-apply its
    /// diffable snapshot. Needed because `retryFromToolBlock`'s sub-message path
    /// mutates `messages[i].blocks` in-place without changing the messages
    /// array count, so `$messages` won't fire a new value and the UIKit-backed
    /// `CollectionViewMessageListV3` (which doesn't observe `objectWillChange`)
    /// would otherwise keep showing the pre-cut block identifiers.
    let retrySnapshotReloadSignal = PassthroughSubject<Void, Never>()
    /// Whether the message list is scrolled near the bottom. Updated by CollectionViewMessageList.
    @Published var isNearBottom: Bool = true

    /// Whether the viewport is already at the very first user turn's top — i.e.
    /// the up-button ("scroll to previous turn") has nowhere further up to go.
    /// Drives ONLY the up-button's visibility (the down-button still keys off
    /// isNearBottom): once the user has walked all the way up, the up-button
    /// hides. Updated by CollectionViewMessageList on scroll/jump.
    @Published var isAtFirstTurn: Bool = false

    /// [ScrollDiag] View-layer record of a scroll-jump button's actual SwiftUI
    /// mount/unmount, so a ring dump (debug.scrollMetrics) can be diffed against
    /// the VM-side buttonsVisible. `up` picks which button; `visible` is true on
    /// appear, false on disappear. DEBUG-only; compiled out in Release.
    func recordScrollButtonRender(up: Bool, visible: Bool) {
        #if DEBUG
        ScrollMetricsRecorder.shared.record(
            kind: "btnRender",
            a: visible ? 1 : 0,
            pos: "\(up ? "up" : "down")-\(visible ? "appear" : "disappear") vmNearBottom=\(isNearBottom)")
        #endif
    }

    // Note: Stream throttle timestamps (lastTextDeltaFlush, lastFileWriteStreamUpdate,
    // lastToolInputStreamUpdate) are now local vars inside processStreamEvents().
    @Published var kernelStatus: KernelStatus = .notBooted
    /// When enabled, agent responses and tool descriptions are spoken aloud.
    /// Initialized from the persisted "Read replies" preference so an already-on
    /// toggle drives streaming TTS for new chats.
    /// Read-replies on/off. NOT per-session state — proxies the GLOBAL
    /// `VoiceOutputState.shared` so toggling in any session (or the home screen)
    /// is reflected everywhere. `voiceStateCancellable` (set in init) forwards the
    /// global object's changes to this VM so views observing `vm.speakEnabled`
    /// still refresh.
    var speakEnabled: Bool {
        get { VoiceOutputState.shared.isEnabled }
        set { VoiceOutputState.shared.isEnabled = newValue }
    }
    /// When enabled, uses 1-hour cache TTL instead of 5-minute for Anthropic requests.
    @Published var enhancedCacheEnabled = false

    /// Whether the Enhanced Cache toggle should be visible. Only Anthropic
    /// official API (no custom base URL) supports the cache TTL protocol.
    var showEnhancedCacheToggle: Bool {
        guard let entry = resolveCurrentEntry(),
              let inst = ProviderConfigStore.shared.instance(for: entry.providerInstanceId)
        else { return false }
        guard inst.providerType == .anthropic else { return false }
        if let url = inst.customBaseURL, !url.isEmpty { return false }
        return true
    }

    // MARK: - Compact State
    /// Whether the slash command menu should be shown.
    @Published var showSlashMenu = false
    /// Filter text after "/" in the input.
    @Published var slashFilter = ""
    /// Currently highlighted index in the slash menu (-1 = none selected).
    @Published var slashMenuSelectedIndex = -1
    /// Whether a compaction is in progress.
    @Published var isCompacting = false
    /// When true, shows a prompt asking user to compact before sending.
    @Published var showCompactBeforeSendPrompt = false
    /// [T-chat-auto-compact-opt-in] Global (cross-session) opt-in: when the
    /// context-capacity threshold fires, compact automatically instead of
    /// prompting — the same no-UI path shortcut sessions already use. Set
    /// from the confirm dialog's "Compact & Enable Auto-Compact" button or
    /// the "..." menu's Auto Compact toggle; persisted in UserDefaults so
    /// future conversations inherit it.
    @Published var autoCompactEnabled: Bool = UserDefaults.standard.bool(forKey: "autoCompactOnThreshold") {
        didSet {
            guard autoCompactEnabled != oldValue else { return }
            UserDefaults.standard.set(autoCompactEnabled, forKey: "autoCompactOnThreshold")
        }
    }
    /// When true, shows a prompt telling user to start a new session or clear chat.
    @Published var showContextExhaustedPrompt = false
    /// Message text held back while the compact-before-send prompt is shown.
    var pendingSendText: String?
    /// Attachments held back while the compact-before-send prompt is shown.
    var pendingSendAttachments: [InputAttachment] = []
    /// When true, skip the needsCompactBeforeSend check (used after compactAndSend to avoid loop).
    var skipCompactCheck = false
    /// When false, memory_write tool calls are skipped (returns "Memory disabled") in this session.
    @Published var memoryEnabled = true

    // MARK: - Session Stats
    /// Accumulated streaming duration (seconds) for output token speed calculation.
    var sessionStreamDuration: TimeInterval = 0
    /// Total output tokens produced during streaming (for speed calculation).
    var sessionStreamOutputTokens: Int = 0
    /// Cumulative cache read tokens across all API calls in this session.
    var sessionCacheReadTokens: Int = 0
    /// Cumulative cache write (creation) tokens across all API calls in this session.
    var sessionCacheWriteTokens: Int = 0
    /// Cumulative input tokens (excl. cache) across all API calls in this session.
    var sessionInputTokens: Int = 0
    /// Cumulative output tokens across all API calls in this session.
    var sessionOutputTokens: Int = 0

    /// Session-level token totals.
    var sessionTokenStats: (input: Int, output: Int, cacheRead: Int, cacheWrite: Int, context: Int, loopCount: Int) {
        var context = 0
        var toolCalls = 0
        for msg in messages where msg.role == .assistant {
            if let u = msg.usage, u.latestContextTokens > 0 {
                context = u.latestContextTokens
            }
            for block in msg.blocks {
                switch block.kind {
                case .text, .info: break
                default: toolCalls += 1
                }
            }
        }
        let assistantCount = messages.filter({ $0.role == .assistant }).count
        return (sessionInputTokens, sessionOutputTokens, sessionCacheReadTokens, sessionCacheWriteTokens, context, max(toolCalls, assistantCount))
    }

    /// Context window size of the current model (from models.dev or heuristic).
    /// Resolved via `resolveCurrentEntry()` so the UI shows the same window the
    /// send path and capacity checks use (availability re-routing included).
    var currentModelContextWindow: Int? {
        guard let entry = resolveCurrentEntry() else { return nil }
        return effectiveContextWindow(for: entry.model)
    }

    /// Whether the current model (or any model in the group) supports reasoning/thinking.
    ///
    /// Tri-state handling of `model.supportsReasoning`:
    /// - `true`  → supported
    /// - `false` → not supported
    /// - `nil`   → unknown (e.g. DeepSeek V4 fetched from `/v1/models`, which
    ///            returns no capability metadata). Treat as allowed so users
    ///            can still opt into thinking manually; sending the thinking
    ///            params to a non-reasoning model is generally a no-op on the
    ///            server side, whereas hiding the toggle on a reasoning-capable
    ///            model makes the feature unreachable.
    var currentModelSupportsReasoning: Bool {
        let store = ProviderConfigStore.shared

        // Try session binding first
        if let sid = sessionId, let binding = store.binding(for: sid) {
            return checkBindingSupportsReasoning(binding, store: store)
        }

        // No session yet — resolve from default group
        if let entry = resolveCurrentEntry() {
            return Self.entryAllowsReasoning(entry, store: store)
        }

        // Fallback: if user already set pending thinking, honor it
        return (pendingThinkingLevel ?? .off) != .off
    }

    var availableThinkingLevels: [ThinkingLevel] {
        let maxLevel: ThinkingLevel
        if let entry = resolveCurrentEntry() {
            maxLevel = entry.effectiveMaxThinkingLevel
        } else {
            maxLevel = .xhigh
        }
        return ThinkingLevel.allCases.filter { $0 != .off && $0 <= maxLevel }
    }

    /// Whether a model entry should expose the thinking toggle.
    ///
    /// Base rule (tri-state, unchanged): `supportsReasoning ?? true` — `true`
    /// and unknown (`nil`) both allow the toggle, only an explicit `false`
    /// hides it.
    ///
    /// OpenAI-compatible providers additionally OVERRIDE an explicit `false`:
    /// their backend usually accepts thinking params even when models.dev
    /// tagged the model non-reasoning, so we let the user opt in. Two groups:
    ///   - `.openAI` / `.openAIResponses` ONLY when they have a `customBaseURL`
    ///     (Ollama / LM Studio / DashScope / self-hosted gateways). Official
    ///     OpenAI (no custom base) keeps the strict `?? true` rule so a model
    ///     genuinely marked `false` there still hides the toggle.
    ///   - `.openRouter` / `.xAI` always — these are inherently third-party /
    ///     aggregator OpenAI-compatible backends (no official-vs-custom split;
    ///     their `customBaseURL` is nil because they use a fixed built-in base),
    ///     and their thinking injection (`reasoning.effort` / OpenAIProvider
    ///     path) is model-id driven, not gated on `supportsReasoning`.
    /// All other provider types (anthropic, gemini, …) keep the strict rule,
    /// so a model genuinely marked `false` there still hides the toggle.
    static func entryAllowsReasoning(_ entry: ModelEntry, store: ProviderConfigStore) -> Bool {
        if entry.model.supportsReasoning != false {
            return true  // true or nil → allowed
        }
        // Explicit false: allow for OpenAI-compatible providers.
        guard let instance = store.instance(for: entry.providerInstanceId) else { return false }
        switch instance.providerType {
        case .openAI, .openAIResponses:
            // Custom/local base only — never official OpenAI.
            return instance.customBaseURL?.isEmpty == false
        case .openRouter, .xAI, .kimiCode:
            return true
        case .anthropic, .gemini, .antigravity, .unsupported:
            return false
        }
    }

    private func checkBindingSupportsReasoning(_ binding: SessionModelBinding, store: ProviderConfigStore) -> Bool {
        switch binding.primarySource {
        case .directEntry(let id, _):
            guard let entry = store.entry(for: id) else { return false }
            return Self.entryAllowsReasoning(entry, store: store)
        case .group(let groupId, let resolvedId):
            // Check resolved model first
            if let entry = store.entry(for: resolvedId),
               Self.entryAllowsReasoning(entry, store: store) {
                return true
            }
            // Check any model in the group
            guard let group = store.group(for: groupId) else { return false }
            return group.memberEntryIds.contains { memberId in
                guard let entry = store.entry(for: memberId) else { return false }
                return Self.entryAllowsReasoning(entry, store: store)
            }
        }
    }

    /// Max output tokens of the current model (from models.dev).
    var currentModelMaxOutputTokens: Int? {
        guard let sid = sessionId,
              let binding = ProviderConfigStore.shared.binding(for: sid) else { return nil }
        let entryId: String? = switch binding.primarySource {
        case .directEntry(let id, _): id
        case .group(_, let resolvedId): resolvedId
        }
        guard let entryId, let entry = ProviderConfigStore.shared.entry(for: entryId) else { return nil }
        return entry.model.maxOutputTokens
    }

    /// Thinking mode info for the current model.
    var currentModelThinkingInfo: (supported: Bool, enabled: Bool, level: String)? {
        guard let sid = sessionId,
              let binding = ProviderConfigStore.shared.binding(for: sid) else { return nil }
        // Resolve entry: for groups, try resolved first, fallback to first member
        let entryId: String? = switch binding.primarySource {
        case .directEntry(let id, _): id
        case .group(_, let resolvedId): resolvedId
        }
        guard let entryId else { return nil }
        // If resolved entry is gone (stale UUID), try first group member
        let entry: ModelEntry?
        if let e = ProviderConfigStore.shared.entry(for: entryId) {
            entry = e
        } else if case .group(let gid, _) = binding.primarySource,
                  let group = ProviderConfigStore.shared.group(for: gid),
                  let firstId = group.memberEntryIds.first {
            entry = ProviderConfigStore.shared.entry(for: firstId)
        } else {
            entry = nil
        }
        guard let entry,
              let instance = ProviderConfigStore.shared.instance(for: entry.providerInstanceId) else { return nil }
        let model = entry.model
        // Use the same allow-rule as `currentModelSupportsReasoning` so the
        // thinking info card stays consistent with the slash-menu toggle
        // availability: `nil` allowed, and custom-base OpenAI-compatible
        // providers also allowed even when explicitly `false`.
        let supported = Self.entryAllowsReasoning(entry, store: ProviderConfigStore.shared)
        let cfg = ProviderConfigStore.shared.inferenceConfig(for: sid) ?? SessionInferenceConfig()
        let enabled = cfg.thinkingEnabled

        let level: String
        if !enabled {
            level = "—"
        } else {
            let thinkLvl = cfg.thinkingLevel
            switch instance.providerType {
            case .anthropic:
                level = "budget: \(AnthropicAgentProvider.thinkingBudget(for: model, maxTokens: 64000, level: thinkLvl))"
            case .gemini:
                level = thinkLvl.displayName
            case .openAI, .openAIResponses, .openRouter, .xAI, .kimiCode:
                level = OpenAIAgentProvider.reasoningEffort(for: model, level: thinkLvl) ?? "—"
            case .unsupported:
                level = "—"
            case .antigravity:
                let lid = model.id.lowercased()
                if lid.contains("claude") { level = "budget: \(AnthropicAgentProvider.thinkingBudget(for: model, maxTokens: 64000, level: thinkLvl))" }
                else { level = thinkLvl.displayName }
            }
        }
        return (supported, enabled, level)
    }

    /// Average output tokens per second across this session.
    var sessionOutputTokensPerSecond: Double {
        guard sessionStreamDuration > 0 else { return 0 }
        return Double(sessionStreamOutputTokens) / sessionStreamDuration
    }

    // Tool snapshots
    @Published var toolSnapshots: [ToolSnapshotItem] = []

    // Attachments
    @Published var attachments: [InputAttachment] = []
    /// Number of videos currently being imported from the photo picker.
    @Published var loadingVideoCount = 0

    /// Old iOS releases visibly jitter when the actively streaming markdown block
    /// re-self-sizes during user scroll/deceleration. While this flag is set, the
    /// newest streamed text snapshot is buffered and only applied after scrolling settles.
    private struct DeferredStreamingTextUpdate {
        let messageId: UUID
        let blockId: UUID
        let text: String
        let parsedMarkdown: MarkdownContent?
        let cacheAttributedString: Bool
    }
    private var deferredStreamingTextUpdates: [DeferredStreamingTextUpdate] = []
    private(set) var streamingUIUpdatesSuspended = false

    /// Set when `isProcessing` transitions to false but `retrySnapshotReloadSignal`
    /// cannot fire because `streamingUIUpdatesSuspended` is true. Cleared when the
    /// suspend lifts — at that point the snapshot rebuild is safe and is replayed.
    private var needsSnapshotReloadOnResume = false

    /// [T-ios-ui-blocks-lost-suspend-resume] Fired on the main thread whenever
    /// `streamingUIUpdatesSuspended` transitions from true → false, from ANY
    /// path (scroll-settle resume, transition resume, explicit clear). The
    /// message-list Coordinator registers this so it can rebuild a snapshot it
    /// deferred while suspended (`hasPendingSnapshot`). Without it, a suspend
    /// window that opens during a multi-tool agent loop and closes after the
    /// stream ends has no subscription left to trigger the deferred flush, so
    /// the trailing assistant blocks never render even though they're in the DB.
    var onStreamingUIUpdatesResumed: (() -> Void)?

    /// Single chokepoint for clearing `streamingUIUpdatesSuspended`. Sets the
    /// flag false (idempotent) and, if it actually transitioned, fires the
    /// resume hook so deferred consumers (the Coordinator snapshot) can catch
    /// up. Callers that need the VM-side deferred-text flush still call it
    /// themselves; this only owns the flag + hook.
    private func clearStreamingUIUpdatesSuspended() {
        guard streamingUIUpdatesSuspended else { return }
        streamingUIUpdatesSuspended = false
        onStreamingUIUpdatesResumed?()
        if needsSnapshotReloadOnResume {
            needsSnapshotReloadOnResume = false
            let totalBlocks = messages.reduce(0) { $0 + $1.blocks.count }
            let lastBlocks = messages.last?.blocks.count ?? 0
            logger.info("[BlocksLost] REPLAY retrySnapshot — suspend cleared. sid=\(sessionId?.prefix(8) ?? "nil") totalBlocks=\(totalBlocks) lastMsgBlocks=\(lastBlocks) msgCount=\(messages.count)")
            retrySnapshotReloadSignal.send()
        }
    }

    /// Called from AIChatView.onAppear REUSING path. When the agent loop ended
    /// while the user was away (no CollectionView subscriber), the
    /// `retrySnapshotReloadSignal` fired into the void and
    /// `needsSnapshotReloadOnResume` may still be set. The `didBecomeActive`
    /// observer only covers background→foreground; this covers the in-app
    /// navigation case (user switches between sessions while the app stays
    /// active). Sends `retrySnapshotReloadSignal` after a short delay so the
    /// newly-mounted CollectionView's `bindViewModel` subscription is already
    /// installed when the signal arrives.
    @MainActor
    func consumeDeferredSnapshotIfNeeded() {
        // [T-blockslost-defensive] Entry state is logged even when there is
        // nothing to consume — a silent no-op here is indistinguishable from
        // "flag was consumed into the void earlier" (defect ②'s signature)
        // when triaging from device logs.
        logger.info("[BlocksLost] consumeDeferred onAppear-reuse sid=\(sessionId?.prefix(8) ?? "nil") vm=\(vmInstanceId) needsFlag=\(needsSnapshotReloadOnResume)")
        guard needsSnapshotReloadOnResume else { return }
        needsSnapshotReloadOnResume = false
        let totalBlocks = messages.reduce(0) { $0 + $1.blocks.count }
        let lastBlocks = messages.last?.blocks.count ?? 0
        logger.info("[BlocksLost] REPLAY retrySnapshot — onAppear reuse consumed deferred. sid=\(sessionId?.prefix(8) ?? "nil") totalBlocks=\(totalBlocks) lastMsgBlocks=\(lastBlocks) msgCount=\(messages.count)")
        DispatchQueue.main.async { [weak self] in
            self?.retrySnapshotReloadSignal.send()
        }
    }

    /// [T-ios-scroll-suspend-leak] Compress a `Thread.callStackSymbols` frame to
    /// just the demangled Swift function name (drops the module load address,
    /// index, and image name that make raw frames unreadable in logs). Used only
    /// for the [SuspendState] transition trace.
    private static func shortFrame(_ raw: String) -> String {
        // Raw frame looks like:
        // "3   Minis   0x0000000104abcd12 $s5Minis... mangled ... + 40"
        // Prefer the human name after the mangled symbol if present, else the
        // whitespace-collapsed tail.
        let parts = raw.split(separator: " ", omittingEmptySubsequences: true)
        // The mangled/demangled symbol is typically the 4th token onward.
        guard parts.count >= 4 else { return raw.trimmingCharacters(in: .whitespaces) }
        let symbol = parts[3...].joined(separator: " ")
        // Strip the trailing " + <offset>" and cap length.
        let trimmed = symbol.split(separator: "+").first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? symbol
        return String(trimmed.prefix(80))
    }

    /// True while this vm is the OUTGOING side of a session-switch transition.
    /// During the brief window when the UICollectionView/SwiftUI host views of
    /// the previous session are being torn down and the new session's views
    /// are being mounted, both ViewGraphs share the same AsyncRenderer thread.
    /// A concurrent mutation on the outgoing vm here can cause
    /// AG::Subgraph::NodeCache::~NodeCache to deref a dangling pointer
    /// (FB13213926, recurring), evidenced by build-48 crash
    /// Minis-2026-06-01-134710.ips: user switched sessions while both vms had
    /// `isProcessing=true`, EXC_BAD_ACCESS in
    /// `_UIHostingView.isHiddenForReuse.setter → ViewGraphHost.updateRemovedState
    /// → AG::Subgraph::invalidate_now → AG::Subgraph::NodeCache::~NodeCache`.
    /// While `transitionSuspended` is true:
    ///   * `streamingUIUpdatesSuspended` is force-true (text-block flush defer)
    ///   * Manual `objectWillChange.send()` callers consult `isTransitionSuspended`
    ///     and elide the publish when true (retry / edit / thinking-level
    ///     paths). The next genuine state change after resume re-publishes.
    private(set) var transitionSuspended = false
    /// Public read so the manual `objectWillChange.send()` call sites can gate.
    var isTransitionSuspended: Bool { transitionSuspended }

    /// True for the synchronous window of `retryFromMessage` /
    /// `retryFromToolBlock`: from the moment we start clearing `canResume` /
    /// truncating `messages` until the DB truncation (`deleteMessagesAfter`)
    /// has completed. While set, a deferred iCloud-sync `reloadMessagesFromDB`
    /// must NOT run — it would rebuild `messages` from a DB snapshot taken
    /// mid-truncation (before/around the delete) and can drop the retried user
    /// message from the UI even though it survives in agentHistory (so the
    /// reply is based on it but its bubble vanishes). Reload calls skip+rearm
    /// while this is true, and the canResume didSet does not replay the
    /// deferred reload; the next safe idle drains it. [T-ios-retry-usermsg-vanish]
    var isTruncatingForRetry = false

    // MARK: - Browser
    let browserTabPool = BrowserTabPool()
    /// When true, the agent loop pauses at the next checkpoint to let the user operate the browser.
    @Published var browserTakeoverActive = false

    // MARK: - Prompt Queue
    /// Prompts queued by the user while the agent loop is running.
    @Published var promptQueue: [QueuedPrompt] = []

    // MARK: - Speech

    private let speechSynthesizer = AVSpeechSynthesizer()
    private lazy var speechDelegate = SpeechFinishedDelegate()
    /// True when speech is paused (not stopped). Toggled by the floating speech button.
    @Published var speechPaused = false

    /// Accumulates reasons for provider fallbacks within a single attempt, consumed by runAgentLoop to insert info blocks.
    var fallbackReasons: [(model: String, instance: String, reason: String)] = []

    /// True when the synthesizer is actively speaking or has queued utterances.
    var isSpeaking: Bool {
        speechSynthesizer.isSpeaking
    }

    /// Current read-replies speed multiplier, mirrored for the player control.
    /// Source of truth is VoiceOutputPreferences.speedMultiplier.
    @Published var speechSpeed: Float = VoiceOutputPreferences.speedMultiplier

    /// True once the first reply utterance has been handed to a TTS engine — used
    /// by the player control to collapse from expanded to compact on first speak.
    @Published var isReadingAloud = false

    /// Whether the floating speech player control is expanded (vs compact). Lives
    /// here so the chat view can overlay a tap-to-dismiss layer while expanded.
    /// Defaults to COMPACT — expansion is only initiated by an explicit user action
    /// that turns reading on (so a new session / relaunch shows compact).
    @Published var speechPlayerExpanded = false

    /// Explicitly set the playback speed (player control speed chip).
    func setSpeechSpeed(_ s: Float) {
        VoiceOutputPreferences.speedMultiplier = s
        speechSpeed = s
        // Apply to the currently-playing cloud audio in real time (System TTS
        // can't re-rate a queued utterance, so it takes effect on the next one).
        VoiceOutputPlayer.shared.setRate(s)
        syncSpeechStateToGlobal()
    }

    /// Cycle to the next speed step (1.0→1.25→1.5→2.0→1.0).
    func nextSpeechSpeed() {
        let steps = VoiceOutputPreferences.speedSteps
        let cur = VoiceOutputPreferences.speedMultiplier
        let idx = steps.firstIndex(of: cur) ?? 0
        setSpeechSpeed(steps[(idx + 1) % steps.count])
    }

    /// Pause or resume speech playback (handles both System + cloud engines).
    func toggleSpeechPause() {
        if speechPaused {
            // Resume
            if speechSynthesizer.isPaused { speechSynthesizer.continueSpeaking() }
            VoiceOutputPlayer.shared.resume()
            speechPaused = false
        } else {
            // Pause
            if speechSynthesizer.isSpeaking { speechSynthesizer.pauseSpeaking(at: .word) }
            VoiceOutputPlayer.shared.pause()
            speechPaused = true
        }
        syncSpeechStateToGlobal()
    }

    /// Read an entire assistant reply aloud FROM THE START. Clears any in-progress
    /// TTS (so it doesn't overlap), enables read-replies, then queues the whole
    /// reply split into sentences. No-op while that reply is still streaming (the
    /// caller greys the menu item out, but we guard here too).
    func readReplyFromStart(_ message: ChatMessage) {
        // Build the reply's plain text from its text blocks.
        let fullText = message.blocks
            .compactMap { block -> String? in
                if case .text = block.kind { return block.content }
                return nil
            }
            .joined(separator: "\n\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !fullText.isEmpty else { return }

        // Clear whatever THIS session is playing/queuing so the replay starts
        // clean — other concurrent sessions' queued speech is left alone.
        stopSpeechForThisSession()
        // Ensure read-replies is on (and reflected in prefs/voice VM).
        if !speakEnabled {
            speakEnabled = true
            VoiceOutputPreferences.isEnabled = true
        }
        isReadingAloud = true
        // Split into sentences and queue each — reuses the streaming segmenter so
        // sizing / dynamic window behave the same as live playback.
        let (sentences, _) = extractNewSentences(from: fullText, spokenOffset: 0)
        let units = sentences.isEmpty ? [fullText] : sentences
        for s in units { speakQueued(s) }
    }

    /// Turn read-replies off entirely, stopping playback.
    func disableSpeech(resetSpeed: Bool) {
        stopSpeech()
        speakEnabled = false
        VoiceOutputPreferences.isEnabled = false
        if resetSpeed {
            VoiceOutputPreferences.speedMultiplier = 1.0
            speechSpeed = 1.0
        }
    }

    /// Push this VM's live read-aloud state up to the global VoiceOutputState so
    /// the home-screen capsule reflects it. Also registers this VM as the active
    /// controller so global-capsule actions forward here.
    func syncSpeechStateToGlobal(registerActive: Bool = false) {
        if registerActive { VoiceOutputState.shared.registerActive(self) }
        VoiceOutputState.shared.syncState(reading: isReadingAloud,
                                          paused: speechPaused,
                                          speed: speechSpeed)
    }

    /// Stop all speech and reset paused state.
    func stopSpeech() {
        speechSynthesizer.stopSpeaking(at: .immediate)
        VoiceOutputPlayer.shared.stopAll()
        speechPaused = false
        isReadingAloud = false
        // End the reply-TTS intent (coordinator drops .replyTTS; if nothing else
        // needs audio it deactivates the session).
        AudioSessionCoordinator.shared.end(.replyTTS)
        syncSpeechStateToGlobal()
    }

    /// Session-scoped stop: clears only THIS session's units from the shared
    /// cloud TTS queue (other concurrent chats' queued speech keeps playing) and
    /// stops the System synthesizer (engine-global — it has no queue to scope).
    /// Used at reply start / read-from-start so one chat starting a new reply
    /// can't wipe another chat's in-flight speech, which the old global
    /// `stopSpeech()` did (queue hijack + text loss across concurrent sessions).
    func stopSpeechForThisSession() {
        speechSynthesizer.stopSpeaking(at: .immediate)
        VoiceOutputPlayer.shared.stopSession(sessionId ?? "")
        speechPaused = false
        isReadingAloud = false
        if !useCloudTTS {
            // System engine path: nothing else shares the intent, release it.
            // The cloud path releases inside stopSession only when the WHOLE
            // queue is idle — ending it here would tear the audio session out
            // from under another session's still-playing audio.
            AudioSessionCoordinator.shared.end(.replyTTS)
        }
        syncSpeechStateToGlobal()
    }

    /// Speak text aloud if speakEnabled is on. Interrupts any current speech.
    /// When Background Speak is enabled, configures audio session for .playback
    /// so TTS continues in the background and keeps the process alive.
    func speakText(_ rawText: String) {
        // Strip emoji / non-speakable glyphs before TTS.
        let text = VoiceTextSanitizer.sanitize(rawText)
        guard canSpeakNow, !text.isEmpty else { return }
        isReadingAloud = true
        syncSpeechStateToGlobal(registerActive: true)
        // Reply TTS (System + cloud are the SAME source/intent) — declare it once
        // up front; the resolved provider then picks the engine.
        AudioSessionCoordinator.shared.begin(.replyTTS)
        if useCloudTTS {
            VoiceOutputPlayer.shared.enqueue(text, sessionId: sessionId ?? "")
            return
        }
        if BackgroundKeepAliveManager.shared.backgroundSpeakEnabled {
            BackgroundKeepAliveManager.shared.stopSilentAudio()
            speechSynthesizer.delegate = speechDelegate
        }
        if speechSynthesizer.isSpeaking {
            speechSynthesizer.stopSpeaking(at: .word)
        }
        let utterance = makeUtterance(text)
        speechSynthesizer.speak(utterance)
    }

    /// Dynamic TTS window: decide how much of the accumulated `buffer` to send to
    /// cloud TTS now, returning the LEFTOVER text the caller should keep buffering.
    /// Rules:
    ///  • Idle (no buffered/playing audio) → send the FIRST batch ASAP (low latency).
    ///  • Otherwise wait until the buffer reaches the player's recommended minimum
    ///    (grows with buffered audio → longer, more coherent batches mid/late reply).
    ///  • PARAGRAPH-COHERENT: once eligible, prefer cutting at a PARAGRAPH boundary
    ///    (blank line / newline) so a whole paragraph is synthesized in ONE request
    ///    and doesn't get split across batches (which made Gemini render the two
    ///    halves in slightly different timbres). Only fall back to a sentence cut
    ///    if the paragraph would exceed `maxBatchChars` (synth-timeout guard).
    ///  • `force` (stream end / tool) → send everything left.
    /// System TTS has no synth metrics → it just sends immediately, whole buffer.
    /// Returns the unsent leftover (caller stores it back into the buffer).
    func feedDynamicTTS(_ buffer: String, force: Bool) -> String {
        let trimmed = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        // REAL-TIME global gate: if read-replies was turned off mid-stream (e.g.
        // the user muted from the global capsule), drop any remaining buffered /
        // force-flushed text instead of speaking it. `speakEnabled` is a live proxy
        // for VoiceOutputState.shared, so this reflects the latest global state —
        // not a stream-start snapshot.
        guard canSpeakNow else {
            VoiceLog.log("feedDynamicTTS DROP — read-replies off (speakEnabled=\(speakEnabled) capturing=\(VoiceModePreference.shared.isCapturing))")
            return ""
        }

        // System path: no dynamic window, speak each batch as it arrives.
        guard useCloudTTS else {
            speakQueued(trimmed)
            return ""
        }

        let player = VoiceOutputPlayer.shared
        let minChars = player.recommendedMinChars
        let maxChars = VoiceOutputPlayer.maxBatchChars
        let idle = !player.hasPendingAudio
        let len = trimmed.count

        if force {
            VoiceLog.log("dyn-window FLUSH(force) len=\(len)")
            speakQueued(trimmed)
            return ""
        }

        // Not yet eligible → keep accumulating (unless idle: send ASAP for latency).
        guard idle || len >= minChars else {
            VoiceLog.log("dyn-window HOLD len=\(len) < minChars=\(minChars)")
            return trimmed
        }

        // Eligible. Send the largest whole-PARAGRAPH prefix that fits maxChars; if a
        // single paragraph already exceeds maxChars, fall back to a sentence cut.
        let cut = Self.coherentCutIndex(in: trimmed, minChars: idle ? 1 : minChars, maxChars: maxChars)
        let head = String(trimmed[trimmed.startIndex..<cut]).trimmingCharacters(in: .whitespacesAndNewlines)
        let tail = String(trimmed[cut...]).trimmingCharacters(in: .whitespacesAndNewlines)
        if head.isEmpty {
            // No boundary found within maxChars and below min → keep waiting.
            VoiceLog.log("dyn-window HOLD(no-boundary) len=\(len)")
            return trimmed
        }
        VoiceLog.log("dyn-window FLUSH len=\(head.count) (of \(len)) minChars=\(minChars) idle=\(idle) leftover=\(tail.count)")
        speakQueued(head)
        return tail
    }

    /// Find the index to cut `text` at: the LAST paragraph boundary (newline) at or
    /// before `maxChars` and at/after `minChars`; else the last sentence-ender in
    /// that window; else `maxChars` (hard cap) if the text already exceeds it; else
    /// `endIndex` (send all — it's a complete short unit).
    nonisolated private static func coherentCutIndex(in text: String, minChars: Int, maxChars: Int) -> String.Index {
        let chars = Array(text)
        let n = chars.count
        if n <= maxChars {
            // Whole buffer fits a batch — prefer ending on a paragraph break if the
            // buffer doesn't already end at one; otherwise send it all as one unit.
            // (Sending all keeps a multi-sentence paragraph intact.)
            return text.endIndex
        }
        // Buffer exceeds maxChars: must cut. Search [minChars, maxChars] for the
        // best boundary — paragraph (newline) first, then sentence-ender.
        let lo = max(1, min(minChars, maxChars))
        var paragraphCut = -1, sentenceCut = -1
        let enders = AIChatViewModel.speechEndersForCut
        var i = lo - 1
        while i < min(maxChars, n) {
            let c = chars[i]
            if c == "\n" { paragraphCut = i + 1 }
            else if enders.contains(c) { sentenceCut = i + 1 }
            i += 1
        }
        let idx = paragraphCut > 0 ? paragraphCut : (sentenceCut > 0 ? sentenceCut : maxChars)
        return text.index(text.startIndex, offsetBy: idx)
    }

    /// Sentence-ender set used by the coherent-cut fallback (mirrors the SSE
    /// segmenter's tier-1 enders).
    nonisolated static let speechEndersForCut: Set<Character> = ["。", "！", "？", ".", "!", "?", "；", ";"]

    /// Enqueue text for speech without interrupting current utterance.
    /// Used for streaming text playback — sentences are queued in order.
    /// AVSpeechSynthesizer automatically queues multiple speak() calls; the cloud
    /// player keeps its own ordered queue + synthesize pipeline.
    func speakQueued(_ rawText: String) {
        // Strip emoji / non-speakable glyphs before TTS.
        let text = VoiceTextSanitizer.sanitize(rawText)
        guard canSpeakNow, !text.isEmpty else {
            VoiceLog.log("speakQueued SKIP — speakEnabled=\(speakEnabled) isCapturing=\(AudioSessionCoordinator.shared.isCapturing) empty=\(text.isEmpty)")
            return
        }
        isReadingAloud = true
        syncSpeechStateToGlobal(registerActive: true)
        AudioSessionCoordinator.shared.begin(.replyTTS)
        if useCloudTTS {
            VoiceOutputPlayer.shared.enqueue(text, sessionId: sessionId ?? "")
            return
        }
        if BackgroundKeepAliveManager.shared.backgroundSpeakEnabled {
            BackgroundKeepAliveManager.shared.stopSilentAudio()
            speechSynthesizer.delegate = speechDelegate
        }
        let utterance = makeUtterance(text)
        speechSynthesizer.speak(utterance)  // queues automatically, does NOT interrupt
    }

    /// Declare the reply-TTS audio intent before System TTS speaks. The
    /// coordinator applies the `.playback/.spokenAudio/.duckOthers` profile (audible
    /// even on silent switch) with a FULL category+mode+options reconfigure — fixing
    /// the bug where a stale `.playback/.mixWithOthers` profile (from the keep-alive
    /// track) was inherited and left System TTS silent / un-ducked.
    @MainActor
    static func ensureSpeechPlaybackSession() {
        AudioSessionCoordinator.shared.begin(.replyTTS)
    }

    /// Whether reply TTS may speak right now: enabled AND the mic isn't capturing
    /// (capture and playback are mutually exclusive — no echo / session fight).
    private var canSpeakNow: Bool {
        // Enabled AND not muted AND not capturing. `canPlay` folds in the temporary
        // mute (capsule speaker) on top of the full on/off `isEnabled`.
        VoiceOutputState.shared.canPlay && !VoiceModePreference.shared.isCapturing
    }

    /// True when a configured cloud TTS model is selected (not the offline System
    /// provider). Snapshotted once per reply (`replyUsesCloudTTS`) so a mid-reply
    /// provider change can't split one reply across two engines playing at once.
    private var useCloudTTS: Bool {
        if let snap = replyUsesCloudTTS { return snap }
        let v = !(VoiceProviderResolver.outputProvider() is SystemVoiceProvider)
        replyUsesCloudTTS = v
        return v
    }
    /// Per-reply snapshot of the TTS engine choice; cleared at each reply start.
    private var replyUsesCloudTTS: Bool?

    /// Reset the per-reply TTS engine snapshot (call at the start of each reply).
    func resetReplyTTSEngine() { replyUsesCloudTTS = nil }

    /// True once THIS turn (one user send, spanning ALL agent-loop iterations)
    /// has cleared the previous turn's TTS leftovers. Lives on the vm — NOT on
    /// StreamResult, which is recreated per loop iteration: a per-iteration
    /// flag made every iteration's first textDelta call stopSession and cut
    /// off the previous iteration's still-playing speech. One turn = one
    /// read-aloud context; iterations append and play back-to-back.
    var hasClearedTTSForCurrentTurn = false

    /// Create an AVSpeechUtterance with the user's speech settings applied.
    private func makeUtterance(_ text: String) -> AVSpeechUtterance {
        let mgr = BackgroundKeepAliveManager.shared
        let utterance = AVSpeechUtterance(string: text)
        let lang = text.range(of: "\\p{Han}", options: .regularExpression) != nil ? "zh-CN" : "en-US"
        // Stage 1: if the user picked a SPECIFIC System voice model in the Voice
        // Output selection, honor it directly (one voice for all languages). Only
        // fall back to the legacy per-language auto-selection when no specific
        // voice is chosen — so existing users see no behavior change.
        if let vid = VoiceProviderResolver.resolvedSystemOutputVoiceId(),
           let picked = AVSpeechSynthesisVoice(identifier: vid) {
            utterance.voice = picked
        } else {
            utterance.voice = mgr.voiceForLanguage(lang)
        }
        // Apply the read-replies speed multiplier on top of the user's base rate,
        // clamped to AVSpeechUtterance's valid range.
        let mult = VoiceOutputPreferences.speedMultiplier
        utterance.rate = min(AVSpeechUtteranceMaximumSpeechRate,
                             max(AVSpeechUtteranceMinimumSpeechRate, mgr.speechRate * mult))
        utterance.pitchMultiplier = SystemVoicePreferences.pitch
        utterance.volume = SystemVoicePreferences.volume
        return utterance
    }

    // MARK: - Auto-Play Audio

    /// Regex to find Markdown image syntax with audio extensions and ?auto_play=true query.
    /// Matches: ![...](minis://attachments/file.mp3?auto_play=true)
    private static let autoPlayAudioPattern: NSRegularExpression? = {
        // Match ![alt](url) where url ends with audio extension and has auto_play=true/1
        try? NSRegularExpression(
            pattern: #"!\[[^\]]*\]\(([^)]+\.(?:mp3|m4a|wav|aac|ogg|flac)\?[^)]*auto_play=(?:true|1)[^)]*)\)"#,
            options: .caseInsensitive
        )
    }()

    /// Scan assistant text for audio URLs with ?auto_play=true and trigger playback
    /// directly via GlobalAudioPlayer. Works even in background (Shortcuts intents).
    static func triggerAutoPlayAudio(in text: String) {
        guard !text.isEmpty, let regex = autoPlayAudioPattern else { return }
        let range = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: range)
        guard let firstMatch = matches.first, firstMatch.numberOfRanges >= 2,
              let urlRange = Range(firstMatch.range(at: 1), in: text) else { return }
        let rawURL = String(text[urlRange])

        // Strip query parameters to get clean file URL
        guard let url = URL(string: rawURL),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        components.queryItems = nil
        guard let cleanURL = components.url else { return }

        // Resolve minis:// to file URL
        let fileURL: URL?
        if cleanURL.scheme == "minis" {
            fileURL = resolveMinisFileURLForAutoPlay(url: cleanURL)
        } else {
            fileURL = cleanURL
        }

        guard let resolved = fileURL else {
            logger.warning("⏵ auto_play: could not resolve URL \(rawURL)")
            return
        }

        logger.info("⏵ auto_play: triggering playback for \(resolved.lastPathComponent)")
        // GlobalAudioPlayer.play() configures the audio session itself (without
        // .mixWithOthers) so that it appears in Control Center / lock screen.
        GlobalAudioPlayer.shared.play(url: resolved)
    }

    /// Resolve minis:// URL to local file URL (standalone, no UI dependency).
    private static func resolveMinisFileURLForAutoPlay(url: URL) -> URL? {
        guard url.scheme == "minis", let host = url.host else { return nil }
        // Tolerate double-encoded links alongside single-encoded.
        // [T-fix-double-encoding]
        let subPaths = MinisURLPathDecoding.subPathCandidates(for: url)
        let fm = FileManager.default

        // Global directories resolve without session ID
        let globalDirs = ["memory", "skills", "shared"]
        if globalDirs.contains(host) {
            let library = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
            for subPath in subPaths {
                let globalURL = library.appendingPathComponent("MinisChat/\(host)", isDirectory: true)
                    .appendingPathComponent(subPath)
                if fm.fileExists(atPath: globalURL.path) { return globalURL }
            }
        }

        if let sid = activeSessionId {
            for subPath in subPaths {
                let persistURL = minisPersistentBase
                    .appendingPathComponent(sid, isDirectory: true)
                    .appendingPathComponent(host, isDirectory: true)
                    .appendingPathComponent(subPath)
                if fm.fileExists(atPath: persistURL.path) { return persistURL }
            }
        }

        // [T-ios-minisurl-cross-session-isolation] Fallback to the iSH-visible
        // /var/minis data dir ONLY for the global, non-session-scoped
        // namespaces (memory/skills/shared). For a session-scoped host like
        // `attachments`, /var/minis/<host> binds to whichever session is
        // currently MOUNTED — which may not be the session whose message is
        // being rendered — so reaching it here would be a cross-session leak.
        // Session-scoped resolution already happened above against
        // activeSessionId; not-found is correct beyond that.
        if globalDirs.contains(host) {
            let dataPath = RootfsManager.shared.dataPath
            for subPath in subPaths {
                let linuxPath = "/var/minis/\(host)/\(subPath)"
                let hostPath = dataPath.appendingPathComponent(linuxPath.hasPrefix("/") ? String(linuxPath.dropFirst()) : linuxPath)
                if fm.fileExists(atPath: hostPath.path) { return hostPath }
            }
        }

        return nil
    }

    /// Detect image MIME type from magic bytes; defaults to image/jpeg.
    /// Internal-access so concurrent tool extensions can call it. [T-concurrent-tools]
    static func detectImageMime(_ data: Data) -> String {
        guard data.count >= 4 else { return "image/jpeg" }
        let b = [UInt8](data.prefix(4))
        if b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 { return "image/png" }
        if b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38 { return "image/gif" }
        if b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 { return "image/webp" }
        return "image/jpeg"
    }

    // MARK: - Private

    /// Approximate current time string, rounded to the hour for cache stability.
    private var _cachedTimeString: String = ""
    private var _cachedTimeDate: Date = .distantPast
    private var approximateTimeString: String {
        let now = Date()
        if now.timeIntervalSince(_cachedTimeDate) > 3600 {
            let fmt = DateFormatter()
            fmt.dateFormat = "yyyy-MM-dd HH:00"
            fmt.timeZone = .current
            _cachedTimeString = fmt.string(from: now)
            _cachedTimeDate = now
        }
        return _cachedTimeString
    }

    private var baseSystemPrompt: String {
        // [T-soul-md] Layer 1 is rendered by SystemPromptBuilder, which
        // owns the "You are <name>, a capable AI assistant running on an
        // iOS device ..." identity sentence (parametric on SOUL.md's
        // `name` field) and optionally appends a clearly-labeled
        // Personality section from SOUL.md's body. The original wording
        // is preserved inside SystemPromptBuilder.identityTemplate so we
        // don't regress model behavior that depended on it.
        SystemPromptBuilder.identitySection()
            + "You should proactively use shell commands to accomplish the user's tasks — installing packages (apk add), "
            + "writing and running scripts, managing files, networking, and any other operations a Linux terminal can perform.\n\n"
            + "Available tools:\n"
            + "- shell_execute: Run any shell command. Each invocation is an isolated process with stdout/stderr captured. "
            + "Prefer this for most tasks — it is a real Linux environment with persistent filesystem. "
            + "Common tools (python3, pip, curl, wget, git, ssh, etc.) can be installed via apk add; Python packages via pip install. "
            + "Use `which <cmd>` to check if a tool is already installed before running apk add — many packages persist across sessions. "
            + "When you need to wait before checking results (e.g. polling, waiting for a process), use the `delay` parameter instead of `sleep` in the command — "
            + "delay blocks the agent flow without occupying the iSH shell, so other concurrent tasks can use it during the wait. This avoids resource contention. "
            // [T-agent-prompt-no-phantom-checkins] Device log 2026-07-17: the agent
            // promised "I'll check every 1-2 minutes" after dispatching a long task,
            // then went silent — it assumed some scheduler would wake it. Recurred
            // 2026-07-20 in a variant the old wording didn't name: one status check,
            // then "let's keep waiting" and the turn ended. Rewritten borrowing
            // Hermes Agent's execution-discipline phrasing (act immediately instead
            // of describing intentions / keep working until complete / never end a
            // turn with a promise of future action), plus an HONEST off-ramp —
            // Minis has no in-app scheduler (see 'Scheduled tasks'), so the only
            // truthful alternatives are poll-now or tell-the-user-nothing-runs.
            + "Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. "
            + "Without a scheduler or timed-callback tool, `delay` is your ONLY wait mechanism within a turn — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. "
            + "NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. "
            + "If polling to completion is genuinely not worth blocking the turn, close honestly instead: state that the task keeps running in the background, that you will only learn its outcome when the user next messages (or they ask you to check), and — if it must fire on a schedule beyond this conversation — point them to an Apple Shortcuts automation per 'Scheduled tasks' later in this prompt.\n"
            + "- file_read: Read file contents (faster than cat).\n"
            + "- file_write: Create new files or overwrite existing files (faster than echo/tee).\n"
            + "- file_edit: Edit existing files with exact string replacement (old_string → new_string). Preferred over file_write for modifications — always file_read first.\n"
            + "- browser_use: Web browsing (navigate, screenshot, click, type, get_text, scroll, scroll_and_collect, get_readable, get_backbone, fetch, etc.). "
            + "Starts with a desktop Safari user agent. Use screenshot to see the page.\n"
            + "- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.\n"
            + "- memory_get: Recall memories with keyword search. Check memory at the start of new topics to leverage past knowledge.\n\n"
            + "Current time (approximate): \(approximateTimeString) (\(TimeZone.current.identifier)). "
            + "Device languages: \((UserDefaults.standard.object(forKey: "AppleLanguages") as? [String] ?? Locale.preferredLanguages).joined(separator: ", ")).\n\n"
            + "Shared directory /var/minis/ (bidirectional read/write between shell and app):\n"
            + "  /var/minis/attachments/ — Media files (images, audio, video). Display inline with ![desc](minis://attachments/filename).\n"
            + "  /var/minis/workspace/   — Working files (scripts, data, configs). Link with [name](minis://workspace/filename).\n"
            + "  /var/minis/offloads/    — Auto-saved large outputs. Read with file_read.\n"
            + "  /var/minis/browser/     — Browser screenshots and extracts.\n"
            + "  /var/minis/shared/      — Cross-session shared storage for artifacts and documents. Organize by project or topic (e.g. shared/myproject/, shared/datasets/). Do NOT store temporary files here.\n"
            + "  /var/minis/memory/GLOBAL.md    — Persistent global memory (read-only, user-maintained via Settings).\n"
            + "  /var/minis/memory/YYYY-MM-DD.md — Daily memory log.\n"
            + "  /var/minis/mounts/<name>/ — User-mounted external folders from iOS Files (e.g. an Obsidian vault, Downloads, another app's iCloud container). Presence and names vary per user. Check this directory first when the task references external/user files. Some mounts may be read-only; write tools will reject writes with a clear error.\n\n"
            + "The minis:// URL scheme:\n"
            + "  minis://attachments/file.png  →  /var/minis/attachments/file.png\n"
            + "  minis://workspace/data.csv    →  /var/minis/workspace/data.csv\n"
            + "  minis://shared/project/f.txt  →  /var/minis/shared/project/f.txt\n\n"
            + "IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. "
            + "However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. "
            + "The built-in browser fully supports minis:// — HTML pages and all sub-resources (JS, CSS, images, fonts, etc.) referenced via minis:// absolute URLs or relative paths resolve correctly within the current session. "
            + "When building multi-file web projects, use file_write to create files in the same directory (e.g. /var/minis/workspace/myapp/), "
            + "then reference sub-resources with relative paths in HTML (e.g. <link href=\"style.css\">, <script src=\"app.js\">, <img src=\"logo.png\">). "
            + "The browser resolves relative paths against the minis:// base URL automatically. "
            + "Cross-directory references also work with absolute minis:// URLs (e.g. <img src=\"minis://attachments/photo.png\"> from a workspace HTML page). "
            + "Navigate to the entry HTML to preview, e.g. minis://workspace/myapp/index.html.\n"
            + "To display a minis:// URL in chat, write it as a Markdown link or image (e.g. [name](minis://...)) — the app handles it when the user taps it.\n"
            + "IMPORTANT: minis:// URLs MUST be percent-encoded. Non-ASCII characters (Chinese, emoji, spaces, etc.) in filenames will break Markdown rendering if not encoded. "
            + "Use the minis_url from tool results directly — it is already encoded. "
            + "If you construct a minis:// URL manually, percent-encode the filename (e.g. %E4%B8%AD%E6%96%87 for non-ASCII characters).\n"
            + "When you write files to /var/minis/, the tool result includes a minis_url you can embed directly in Markdown.\n"
            + "Supported inline types: images (.png/.jpg/.gif/.webp), audio (.mp3/.m4a/.wav), video (.mp4/.mov/.m4v).\n"
            + "Audio auto-play: append ?auto_play=true to an audio minis:// URL to auto-play when rendered (e.g. ![audio](minis://attachments/song.mp3?auto_play=true)). Use sparingly — only when the user explicitly asks to hear audio immediately.\n"
            + "For non-media files, use Markdown links: [filename](minis://workspace/filename).\n"
            + "Tappable link previews: text/code (.py/.json/.md/etc), images, audio, video, HTML, and PDF files open native previews when the user taps a [name](minis://...) link.\n"
            + "Use Markdown links for all minis:// files — the user can tap to preview them directly in chat.\n\n"
            + "File creation guidelines:\n"
            + "- Use file_write to CREATE new files. Use file_edit to MODIFY existing files. "
            + "For writing file CONTENTS, prefer file_write over echo/printf or heredocs — it is atomic and avoids all shell-quoting pitfalls. "
            + "Heredocs (cat << EOF, python3 << 'EOF') work reliably, including when the command ends right at the terminator. "
            + "When you hit escaping or parsing errors with long inline content, write the content to a file first (file_write), then pass or execute the file (e.g. `python3 /tmp/script.py`).\n"
            + "- file_write and file_edit are atomic, preserve formatting, and make it easy to fix errors or update content later.\n"
            + "- shell_execute is for RUNNING commands, not for writing files.\n"
            + "- shell_execute supports multi-line commands directly — quoting and special characters are handled automatically. "
            + "However, commands MUST NOT exceed 1000 characters. If longer, write a script file with file_write first, then run it.\n"
            + "- The default shell is BusyBox ash. `**` recursive glob (globstar) is not supported — use `find <dir> -name '*.ext'` for recursive search, piped to `xargs` for tools like `wc`. "
            + "You do NOT need to hand-rewrite bash-specific syntax to POSIX: when a command uses bashisms (arrays arr=(...)/${arr[@]}, [[ ]], (( )), brace ranges {1..9}, process substitution, etc.), Minis automatically installs and runs it under bash. Write the script naturally in whichever shell dialect is clearest; only globstar has no automatic fallback.\n"
            + "- Python packages: many PyPI packages (numpy, pandas, scipy, pillow, etc.) lack musllinux_aarch64 wheels and will fail to build from source. "
            + "Use Alpine's native packages instead: `apk search py3-<name>` then `apk add py3-numpy py3-pandas py3-matplotlib py3-pillow py3-scipy py3-requests`. "
            + "Only fall back to `pip install` for pure-Python packages not available via apk. "
            + "For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in iSH.\n"
            + "- Background services: each shell_execute runs in an isolated process. "
            + "When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: "
            + "`python3 -m http.server 8765 > /dev/null 2>&1 &`. "
            + "Without redirection the server dies silently after the command finishes.\n"
            + "- File search: when looking for user files, do NOT scan the whole filesystem. Search under /var/minis/ first (workspace/attachments/shared for the current session, mounts/* for user-provided external folders). Only widen the scope if the file is clearly not under /var/minis/.\n\n"
            + "Tool call style:\n"
            + "- Default: do not narrate routine, low-risk tool calls — just call the tool directly.\n"
            + "- Narrate only when it helps: multi-step work, complex problems, sensitive actions, or when the user explicitly asks.\n"
            + "- Keep narration brief and value-dense; avoid repeating obvious steps.\n"
            + "- When a tool exists for an action, use it directly instead of explaining what you plan to do or asking the user to confirm.\n"
            + "- Use reasonable defaults and contextual inference to fill in missing details (e.g. 'tonight' means today, 'remind me' implies creating a reminder immediately). Only ask for clarification when genuinely ambiguous.\n\n"
            + "Tone and style:\n"
            + "- Reply in the language that best matches the user's input. Only switch languages when the user explicitly asks.\n"
            + "- Be concise. Prefer action over explanation — when the user asks for something that can be done via shell, do it directly.\n\n"
            + "Native Apple framework tools:\n"
            + "CLI tools at /usr/local/bin with the apple- prefix give you access to iOS frameworks (alarm, bluetooth, calendar, clipboard, device, healthkit, homekit, location, maps, media, nfc, nlp, notification, open, photos, player, reminders, speak, speech, vision, weather). "
            + "All output JSON (--compact to minify, -q for data-only). Run any tool with --help for full usage. "
            + "apple-maps supports search (find nearby POIs), route (directions), and eta (travel time). "
            + "apple-open <url> opens a URL via the system handler. Use shell_execute to run `apple-open <url>` when you want to open something immediately. To offer a tappable link instead, write a standard Markdown link with the URL directly (e.g. [Open in Maps](maps://?daddr=lat,lon)) — the app handles system URL schemes like maps://, tel:, https:// natively. "
            + "apple-player play <file> opens a native audio/video player and returns a session_id; use pause/resume/seek/status/stop to control playback. "
            + "apple-healthkit covers the full HealthKit catalog — 100+ quantity types (body, vitals, cardio fitness, mobility, sleep, audio exposure, nutrition, ...), 60+ category types (symptoms, reproductive, sleep, cardio events), characteristics (sex, DOB, blood type), and special samples (workouts, ECG, audiogram, vision-rx, GAD-7/PHQ-9, state-of-mind). Run `apple-healthkit types` to discover every supported metric with one-line descriptions, then use `apple-healthkit batch --types t1,t2,... --days N` to fetch MULTIPLE metrics in a single call (one authorization prompt, one envelope). Prefer batch over multiple per-metric calls. Use `log --type ... --value ...` to write samples.\n"
            + "apple-homekit controls HomeKit smart home devices. Use progressive disclosure: list (compact overview) → search --query/--type/--room (filter) → get --name (full detail with characteristics). set --name --characteristic --value to control devices. scenes lists scenes, trigger --name executes one.\n"
            + "apple-alarm sets alarms and timers via AlarmKit (iOS 26+). Alarms can only be viewed in the Minis home screen (alarm icon in the top-right toolbar) or by opening minis://views/alarm. "
            + "After setting an alarm, tell the user it is visible on the Minis home screen and they can tap the alarm icon or open minis://views/alarm to manage it.\n"
            + "apple-vision provides image analysis via the Vision framework. Subcommands: ocr (text recognition, --lang, --level fast/accurate), barcode (QR/barcode detection), classify (image classification), detect (rectangle detection), faces (face detection), analyze (combined ocr+classify+barcode+faces), "
            + "similarity <img1> <img2> [img3...] (feature-print based image similarity comparison with --threshold 0.0-1.0, returns pairwise distance/similarity scores and duplicate groups), "
            + "overlap <img1> <img2> [img3...] (detect vertical overlapping regions between consecutive image pairs — uses anchor row scan + multi-row verification to find exact stitch points; returns overlap_px, confidence, and region coordinates). Both similarity and overlap accept --threshold 0.0-1.0 (default 0.9). overlap also accepts --skip-top <px> and --skip-bottom <px> to exclude fixed UI (status bar, tab bar) that would cause false matches.\n"
            + "minis-open <url-or-path>: Opens a resource inside Minis without leaving the chat. Accepts http/https URLs (→ built-in WebKit preview) and chat-resource file paths under /var/minis/** (→ built-in file preview, routed by extension: images to the image viewer, .md to markdown preview, .html to HTML preview, .pdf/office docs to QuickLook, audio/video to the media player, else share sheet). Examples: `minis-open https://example.com`, `minis-open /var/minis/workspace/report.md`, `minis-open /var/minis/attachments/chart.png`. Prefer this over `apple-open` for anything that can be previewed in-app so the user doesn't lose conversation context. Use `apple-open` for non-web schemes (tel:, mailto:, maps://, settings, etc.) or when the user explicitly wants the system handler.\n"
            + "minis-sessions-cli: Manage chat sessions. `list` recent or by date range, `search --keywords` cross-session, `messages --id` to read, `send` to create/continue a session, `retry` to re-run, `status` to check, `open` to navigate the app UI. Run --help for full options.\n"
            + "minis-model-use: Invoke other LLM models pre-configured by the user. "
            + "You have \(ProviderConfigStore.shared.resolvedAgentLoopEntries.count) model(s) available. "
            + "Use `minis-model-use list` to see them (includes each model's modality capabilities like image_output, audio_output, etc.), "
            + "`search <query>` to filter by name/provider. "
            + "`run --model <id_or_name>` sends an OpenAI Chat Completions request; pass input via --input <json_file> or stdin, "
            + "output goes to stdout or --output <path>. "
            // [T-agent-prompt-consistency-pass] Synced with the passthrough-era CLI
            // (T-model-use-passthrough-mode): the old unconditional "Never hand-write
            // provider-native bodies" predated extra_body/--endpoint/passthrough and
            // blocked the model from ever discovering them via --help.
            + "The input JSON is OpenAI Chat Completions shape — a `messages` array — as the PRIMARY input for every model and modality; standard params are auto-converted to the underlying provider, so do not hand-write provider-native bodies as the primary input. "
            + "For provider-specific extras the standard schema doesn't model (web-search plugins, image-to-image fields, TTS/video or other custom endpoints), escape hatches exist for OpenAI-compatible providers (they error or are ignored on Anthropic/Gemini models): `extra_body` (object merged verbatim into the request body), `--endpoint <kind|/custom/path>`, and a top-level `passthrough` envelope for fully verbatim requests with RAW (unparsed) responses. "
            + "Results may carry `warnings` (fields that were ignored/downgraded and why) and `applied_extras` (which extras actually took effect) — read them to self-correct. Run --help for the full contract before using these. "
            + "Models may support multimodal output (image generation, TTS/audio, video) — check the modalities field in list output. "
            + "For image_output models, put the prompt in the user message and image params under `generation_config`: OpenAI image models use `generation_config.{n,size,quality}`, Gemini image models use `generation_config.{aspect_ratio,image_size,number_of_images,person_generation}`. "
            + "Example: {\"messages\":[{\"role\":\"user\",\"content\":\"<prompt>\"}],\"generation_config\":{\"size\":\"1024x1024\",\"n\":1}}. "
            + "IMPORTANT: image generation is SLOW (typically 1-5 min) — a single long blocking call with a large timeout (e.g. timeout: 600) is correct here (one render, one wait); use `delay` chains only when repeatedly CHECKING on something, not for one slow command. "
            + "Run with --help for full usage.\n"
            + "minis-browser-use: CLI wrapper around the in-app browser_use tool — accepts the exact same actions and parameters as the browser_use tool call, just exposed as `<action> --flag value` pairs (or `--json '<obj>'`). "
            + "Run `minis-browser-use` with no arguments (or --help) for the full action list. "
            + "Example: `minis-browser-use navigate --url https://example.com`. "
            + "Prefer this over the browser_use tool call when you need multi-step or batch browser flows: write a bash script that chains multiple minis-browser-use invocations (scrape N pages in a loop, click-through forms, navigate→extract→navigate pipelines) and run it with shell_execute. "
            + "Output is JSON, same shape as the tool call result.\n"
            + "Interactive terminal: minis://open_terminal opens a terminal for tasks that require interactive stdin (passwords, ssh, TUI apps like htop/vi). "
            + "Write it as a Markdown link in your response — the app opens it when tapped. "
            + "The optional init_command parameter pre-fills (NOT executes) a command; it MUST be fully percent-encoded (spaces → %20, & → %26, | → %7C, etc.). "
            + "Only use this for genuinely interactive sessions — for everything else, use shell_execute. "
            + "Examples: [Open Terminal](minis://open_terminal), [Login to SSH](minis://open_terminal?init_command=ssh%20user%40host).\n\n"
            + "minis-config: read or change Minis settings programmatically. Run `minis-config --help` to see subcommands and `minis-config topic-help <topic>` for details on a specific area. For array-valued fields (e.g. `models`, `groups`, `envvars`, `defaults.agentLoopEntries`) the `get` subcommand accepts `--filter <keywords>` (whitespace-AND, case-insensitive substring match against each element's JSON) and `--page <N> --page-size <N>` (default 20, max 100) — use these instead of dumping the full list when you only need a subset, and check the response's `pagination` / `agent_hint` fields for the next-page command. Every write triggers a confirmation sheet in-app and is logged to a revertable audit (1000-entry rolling log). After a successful change the response includes a `user_message` field — relay it (or paraphrase) so the user knows how to review or revert via Logs → Config Changes. If the call returns `permission_denied`, the user has disabled minis-config in Settings → Permissions; relay that message and don't retry. You CAN add a provider (`add providers` with providerType + label, optional customBaseURL/appendV1Suffix/imageEndpointMode) and write its API key — set `providers.<id>.apiKey` (or include `apiKey` in the add payload) with either a literal key or a `$$ENV_VAR` reference resolved from the user's environment variables. Secrets are write-only: `get` never echoes an API key, and reading `providers.<id>.apiKey` / `.oauthToken` still returns permission_denied. Do not try to read API keys/OAuth tokens, or to set OAuth tokens (minted by the in-app login), permission levels, or environment-variable values — those stay locked.\n\n"
            + "Environment variables:\n"
            + "- Shell environment variables may contain sensitive API keys, tokens, or passwords. "
            + "NEVER echo, print, cat, or otherwise output their values to stdout/stderr. "
            + "Always reference them by variable name (e.g. $API_KEY) inside scripts or commands — never inline the literal value.\n"
            + "- When a skill or task requires an environment variable that is not set, "
            + "tell the user which variable is missing and provide a tappable deep link to create it: "
            + "[Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=&create_note=Used%20by%20XYZ) — "
            + "the user can tap it to open the Environment Variables page with the key and optional note pre-filled. "
            + "create_note is optional; fill it with a brief description of what the variable is used for (e.g. 'API key for OpenAI', 'Used by XYZ skill'); URL-encode it.\n"
            + "- Settings deep links: when you tell the user \"go to Settings → X\" or want to point them at a specific setting, prefer a Markdown link `[Label](minis://settings/<path>)` over plain prose. Available paths: providers (list), providers/<instanceId> (one provider), model-groups (incl. Agent Loop), model-groups/<groupId>, usage (token usage), skills, memory, storage, shared-folders (Shared Folders: /var/minis/{shared,skills,memory}), mount-external (Mount External Folders), logs, appearance, background, scheduled-tasks, about, permissions, environments[?create_key=K&create_value=V[&create_note=N]], rootfs (also reachable as mirrors). Unknown paths fall back to Settings home, but prefer the exact path so users land where they want. These settings/action links are app deep links — render them as Markdown links in chat (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use).\n"
            + "- To check if a variable is set, use `[ -n \"$VAR\" ] && echo 'set' || echo 'not set'`. "
            + "NEVER use echo $VAR, printenv VAR, or any command that would output the actual value into the conversation context.\n\n"
            + "Memory system:\n"
            + "- memory_write writes to today's daily log (YYYY-MM-DD.md) — use it for session notes, key facts, project context, things learned, and action items.\n"
            + "- GLOBAL.md (/var/minis/memory/GLOBAL.md) stores persistent preferences, settings, and general-purpose conventions. To read it, use file_read (NOT memory_get). To update it, use file_read first then file_edit. If GLOBAL.md does not exist yet, use file_write to create it directly.\n"
            + "- IMPORTANT: Only write to GLOBAL.md when the user explicitly asks (e.g. 'remember this globally', 'save to global memory'). Before editing, deduplicate and clean up — avoid ambiguity, repetition, or daily-log-style entries. GLOBAL.md should contain only concise, reusable knowledge (preferences, settings, conventions), NOT session logs or transient context.\n"
            + "- Use memory_get to recall past knowledge before starting tasks — check if there are relevant memories that can help.\n"
            + "- Proactively save memories (via memory_write to daily log) when you discover user preferences or important patterns — don't wait to be asked.\n"
            + "- When the user says 'remember this' or similar, use memory_write to persist to the daily log. Only write to GLOBAL.md if the user specifically asks for global/persistent storage.\n"
            + "- What NOT to remember: passwords, API keys, tokens, secrets, or any sensitive credentials. Warn the user about the risk first; only proceed if they explicitly confirm.\n"
            + "- Keep memories concise, factual, and general-purpose — avoid noise that won't be useful later.\n\n"
            + "Scheduled tasks: crontab / at / nohup loops will stop when the app is suspended, so in-app scheduled scripts may not run as expected. "
            + "For recurring tasks that must fire beyond the current conversation, explain that iOS uses an Apple Shortcuts Personal Automation to trigger Minis, then provide the tappable setup link [Set Up Scheduled Tasks](minis://settings/scheduled-tasks). Do not claim Minis can create or inspect the user's Personal Automations. "
            + "(Waiting or polling WITHIN the current turn is different — that is what shell_execute `delay` chains are for, per the shell_execute notes above.)"
    }

    /// [T-memory-toggle-gates-injection-and-tools-ios]
    /// One-paragraph addendum that tells the model the current memory
    /// gate state. Appended to baseSystemPrompt at every assemble site.
    ///
    /// When memory is OFF, the earlier "Available tools" and "Memory system"
    /// sections in baseSystemPrompt still describe memory_get / memory_write
    /// as if they existed. We don't surgically rewrite those (a giant
    /// string concat that's already on the type-checker's edge); instead
    /// we land an authoritative override at the end of the prompt so the
    /// model knows the tools won't be registered, the memory files won't
    /// be injected, and what to tell the user if they ask about memory.
    private var memoryStatusFragment: String {
        if memoryEnabled {
            return "\n\nMemory status: ENABLED for this session. GLOBAL.md and recent daily logs have been injected above (if non-empty), and memory_get / memory_write are available in the tool list."
        } else {
            return "\n\nMemory status: DISABLED for this session. "
                + "The user has turned off memory for this conversation. "
                + "Despite any earlier mentions in this prompt:\n"
                + "- GLOBAL.md and daily logs have NOT been injected — you do not have access to past memories.\n"
                + "- The memory_get and memory_write tools are NOT registered — do not attempt to call them; they will not appear in your tool list.\n"
                + "- If the user asks you to recall memories, save a memory, or wonders why earlier memories aren't visible, tell them memory is currently disabled for this session and they can re-enable it via the /memory slash command or in Settings.\n"
                + "- This toggle is per-session. Other sessions may still have memory enabled.\n"
                + "- SOUL.md (your persona / identity) is unaffected and remains active above."
        }
    }

    /// Session ID for persistence integration. Set by the view on appear.
    var sessionId: String? {
        didSet { browserTabPool.sessionId = sessionId }
    }

    /// The draft ID assigned by the parent view (e.g. "__new__<UUID>").
    /// Included in `.sessionDidCreate` notification so the parent can correlate.
    var draftId: String?

    /// Source tag written to the session record on creation (e.g. "shortcut").
    var sessionSource: String?

    /// Model group to bind when creating a new session (from long-press FAB).
    var initialGroupId: String?

    /// Currently active session ID — accessible from anywhere for minis:// URL resolution.
    /// Updated whenever a session is loaded.
    nonisolated(unsafe) static var activeSessionId: String?
    /// REPRO-DIAG(2026-05-16): global round counter — each runAgentLoop
    /// invocation bumps this. Use `ROUND \d+` to slice the log by attempt.
    nonisolated(unsafe) private static var diagRoundCounter: Int = 0
    nonisolated static func bumpDiagRound() -> Int {
        // Not atomic because runAgentLoop is invoked from main actor and
        // serialised per ViewModel; concurrent rounds across VMs may race
        // and skip/dup a number, which is acceptable for a diagnostic id.
        diagRoundCounter &+= 1
        return diagRoundCounter
    }
    nonisolated static func currentDiagRound() -> Int { diagRoundCounter }
    /// Timestamp when onAppear fires — used to measure end-to-end load latency.
    nonisolated(unsafe) static var onAppearTimestamp: CFAbsoluteTime = 0

    /// Number of title generation attempts for this session (max 3).
    var titleGenAttempts = 0
    /// Whether a title generation request is currently in-flight.
    var isTitleGenerating = false

    /// Unified conversation history used by both Anthropic and Gemini agent loops.
    var agentHistory: [AgentMessage] = []

    #if DEBUG
    /// [T-ios-log-noise-reduction] High-water mark of how many agentHistory
    /// entries the Debug `📋 agentHistory[i]` dump has already printed. The
    /// per-API-call dump only NSLogs entries at index ≥ this value (the delta
    /// since the previous call) instead of re-printing the whole growing
    /// history every iteration. Reset to 0 when the history shrinks.
    var lastDumpedAgentHistoryCount: Int = 0
    #endif

    /// Detects tool-call loops (repeated unknown tools, no-progress polling, runaway
    /// retries) and either injects warnings or short-circuits execution.
    let toolLoopDetector = ToolLoopDetector()

    // MARK: - Cache Keep-Alive State
    /// Snapshot of agent loop state for cache keep-alive warmup requests.
    /// Updated each iteration so the manager can replay the same prefix.
    private(set) var keepAliveHistory: [AgentMessage] = []
    private(set) var keepAliveSystemPrompt: String?
    private(set) var keepAliveTools: [AgentToolDefinition] = []

    // MARK: - Compact Marker (Phase B)
    /// Cached latest compact marker for this session. Phase B agent loop uses this
    /// (via effectiveAgentHistory) to inject the summary at inference time instead
    /// of mutating agentHistory. Updated by loadSession, compactBefore, and any
    /// path that writes a new marker.
    var cachedLatestMarker: CompactMarker?
    /// The session's persisted model id (from ChatStore.getSession). Cached at
    /// loadSession time so resolveCurrentEntry can fall back to it without an
    /// async hop to the actor. Empty string means "not yet loaded" — treat as
    /// no fallback.
    var cachedSessionModelId: String = ""
    /// The ModelEntry used in the current/last agent loop, for rebuilding the provider.
    var keepAliveEntry: ModelEntry?
    /// Pending thought signatures loaded from persisted session, keyed by tool call ID.
    /// Applied to GeminiAgentProvider when the agent loop starts.
    var pendingThoughtSignatures: [String: ToolCallMetadata] = [:]
    // `internal` (not `private`): the +Compaction extension's
    // schedulePostCompactDrain() runs the post-compact queue drain on this
    // task so cancel() can stop it. [T-compact-queued-drain]
    var currentTask: Task<Void, Never>?
    var compactTask: Task<Void, Never>?
    var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    /// Tracks how many blocks on the current assistant message have been fully committed
    /// to agentHistory. Used by retry()/resume() to trim partial iteration content.
    var committedBlockCount: Int = 0
    /// The committedBlockCount before the current/last iteration started.
    /// Used by handleUserCancelledCleanup to roll back the last iteration.
    private var prevCommittedBlockCount: Int = 0
    /// Set by cancel() so the task's error handler knows this was a user stop.
    /// Internal-access so concurrent tool extensions can read it. [T-concurrent-tools]
    var userDidCancel = false
    /// Reentrancy guard for `drainQueuedPrompts()`. After a Stop with queued
    /// prompts, `cancel()` hands off to a fresh Task via `resumeQueueAfterCancel()`,
    /// but the original (superseded) send/retry/resume Task ALSO reaches its own
    /// post-loop `drainQueuedPrompts()` call. Without a guard both Tasks could run
    /// the queued turn, firing the next LLM request — and thus every tool call +
    /// reply — TWICE. Set/cleared only on @MainActor, so the check-and-set is
    /// atomic and spans the `await runAgentLoop()` suspension where the second
    /// drainer would otherwise slip in. [T-ios-concurrent-stop-double-request]
    private var isDrainingQueue = false
    /// [T-compact-queued-drain] True while a post-compact queue drain has been
    /// scheduled (task created) but not finished. Dedup guard so the two
    /// schedulers (compactBefore's success tail + compactAndSend's failure
    /// backstop) can't spawn two racing drain tasks for the same compact.
    var postCompactDrainPending = false
    /// When non-nil, the next send() acts as an edit: truncates conversation from this
    /// message index and re-sends. Cleared after the edit is applied.
    @Published var editingMessageIndex: Int?
    /// Set when the background task expires mid-agent-loop; cleared on resume.
    var backgroundSuspended = false
    /// Continuation used to pause the agent loop while suspended in background.
    var foregroundContinuation: CheckedContinuation<Void, Never>?
    /// Continuation used to pause the agent loop during browser takeover (between turns).
    var takeoverContinuation: CheckedContinuation<Void, Never>?
    /// Continuation used to pause mid-action when the user takes over during a browser action.
    var midActionTakeoverContinuation: CheckedContinuation<Void, Never>?
    /// Timeout task for mid-action takeover (5 minutes).
    var takeoverTimeoutTask: Task<Void, Never>?
    var foregroundObserver: NSObjectProtocol?
    /// Timer that logs tool execution status while the app is backgrounded.
    var backgroundStatusTimer: Timer?
    let defaultCommandTimeout: TimeInterval = 900 // 15 minutes
    /// PID of the currently running shell command (0 = none).
    @Published var runningCommandPid: Int32 = 0
    /// Start time of the currently running command (nil = no command running).
    @Published var commandStartTime: Date?
    /// Set to `true` when the user explicitly stops a running command via the stop button.
    /// Checked after command completion so the tool_result can inform the model.
    var commandCancelledByUser = false

    /// Number of shell_execute tools currently in their pre-execution `delay`
    /// countdown. Lets `stopCurrentCommand()` treat the stop button as a valid
    /// cancel during the wait even though no iSH process exists yet.
    /// [T-delay-stop-latency] A COUNTER, not a Bool: under concurrent tool
    /// execution a second delaying tool finishing (and running its `defer`)
    /// would otherwise flip a shared Bool back to false while a sibling was
    /// still counting down, making the stop button a NOOP for that sibling.
    var toolDelayWaitCount = 0

    /// True while at least one shell_execute tool is in its pre-execution
    /// `delay` countdown.
    var toolDelayWaitActive: Bool { toolDelayWaitCount > 0 }

    /// Whether at least one enabled provider instance exists (has selectable models).
    /// Credential validity is checked at send time, not here — avoids blocking the
    /// send button due to Keychain/OAuth state that SwiftUI can't observe.
    var isReady: Bool {
        ProviderConfigStore.shared.instances.contains { $0.isEnabled }
    }

    // MARK: - Send Message

    func send() {
        // Read-only mode — cannot send messages
        guard remoteDeviceId == nil else { return }

        // [T-ios-photo-pick-placeholder] Drop any non-ready attachments (failed
        // photo loads, or a stray still-loading placeholder) so only fully-loaded
        // files are sent. `canSend` already blocks sending while anything loads,
        // so in practice this just prunes failed chips the user left in place.
        attachments.removeAll { $0.loadState != .ready }

        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        let pendingAttachments = attachments
        #if DEBUG
        logger.info("🔑DRAFT [vm=\(self.vmInstanceId)] send() text=\(text.count)ch attachments=\(pendingAttachments.count) isProcessing=\(self.isProcessing) sessionId=\(self.sessionId ?? "nil") draftId=\(self.draftId ?? "nil") inputText='\(String(self.inputText.prefix(30)))'")
        #else
        logger.info("🔑DRAFT [vm=\(self.vmInstanceId)] send() text=\(text.count)ch attachments=\(pendingAttachments.count) isProcessing=\(self.isProcessing) sessionId=\(self.sessionId ?? "nil") draftId=\(self.draftId ?? "nil")")
        #endif
        guard !text.isEmpty || !pendingAttachments.isEmpty, !isProcessing else {
            logger.warning("🔑DRAFT [vm=\(self.vmInstanceId)] send() GUARD FAILED — text.isEmpty=\(text.isEmpty) attachments.isEmpty=\(pendingAttachments.isEmpty) isProcessing=\(self.isProcessing)")
            return
        }

        // Don't stop TTS here — let the previous reply finish playing. The stream
        // handler will clear the queue on the FIRST textDelta of the new reply, so
        // the old audio plays until actual new content starts arriving.
        resetReplyTTSEngine()
        // Re-arm the once-per-turn TTS clear: the agent loop creates a fresh
        // StreamResult per iteration, so the "stopped previous TTS" flag must
        // live HERE (per-send), not on the StreamResult — otherwise every loop
        // iteration's first textDelta re-cleared the queue and cut off the
        // previous iteration's still-playing speech mid-sentence.
        hasClearedTTSForCurrentTurn = false

        // [T-voice-mode-memory-refine-ios] The SEND commits the composer mode.
        // "voice" is recorded ONLY here — when a voice-composed message is
        // actually sent — so an accidental mic tap that never leads to a send
        // can't flip the default (mic-start no longer writes "voice"). A typed
        // send with no voice session records "text". Attachment-only sends say
        // nothing about typing. (text is also written eagerly on switch-to-text
        // in the composer; saveInputModePreference no-ops when unchanged.)
        if !text.isEmpty {
            SpeechRecognitionManager.saveInputModePreference(voiceUsedInComposition ? "voice" : "text")
        }

        // [T-voice-correction-productionize] Harvest typed vocabulary from sent
        // messages (cold-start data for the voice corrector, design §3.3).
        // Fire-and-forget at background priority — buildIfNeeded() throttles
        // itself (1h min interval + batch threshold) and the write layer is
        // consent-gated (VoiceCorrectionCollectionConsent, default OFF), so
        // without opt-in this is a cheap early return.
        if !text.isEmpty {
            Task.detached(priority: .background) {
                await TypedVocabularyBuilder.shared.buildIfNeeded()
            }
        }

        // Check if context is near capacity
        if skipCompactCheck {
            skipCompactCheck = false
        } else {
            switch checkContextBeforeSend() {
            case .ok:
                break
            case .needsCompact:
                if sessionSource == "shortcut" || autoCompactEnabled {
                    // Shortcut sessions can't show UI prompts; auto-compact
                    // opt-in [T-chat-auto-compact-opt-in] rides the same
                    // no-prompt path — compact and send without asking.
                    logger.info("[Context] Near capacity — auto-compacting (\(sessionSource == "shortcut" ? "shortcut session" : "autoCompactEnabled"))")
                    pendingSendText = text
                    pendingSendAttachments = pendingAttachments
                    inputText = ""
                    attachments = []
                    compactAndSend()
                    return
                }
                pendingSendText = text
                pendingSendAttachments = pendingAttachments
                inputText = ""
                attachments = []
                showCompactBeforeSendPrompt = true
                logger.info("[Context] Near capacity — prompting user to compact before send")
                return
            case .exhausted:
                if sessionSource == "shortcut" {
                    // Shortcut sessions can't show UI — set inline error
                    logger.info("[Context] Exhausted — shortcut session cannot continue")
                    let errMsg = ChatMessage(role: .assistant, content: "", blocks: [])
                    errMsg.error = String(localized: "Context full. Start a new session to continue.")
                    messages.append(errMsg)
                    return
                }
                pendingSendText = text
                pendingSendAttachments = pendingAttachments
                inputText = ""
                attachments = []
                showContextExhaustedPrompt = true
                logger.info("[Context] Exhausted — prompting user to start new session or clear chat")
                return
            }
        }

        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        autoRetryAttempt = 0
        autoRetryCountdown = 0
        canResume = false
        committedBlockCount = 0
        prevCommittedBlockCount = 0
        userDidCancel = false

        inputText = ""

        // If editing a previous message, truncate conversation from that point first
        if let editIdx = editingMessageIndex {
            editingMessageIndex = nil
            // Remove the edited message and everything after it from UI
            if editIdx < messages.count {
                messages.removeSubrange(editIdx...)
            }
            // Trim agentHistory: count remaining user BUBBLES (not synthetic
            // user-role entries — see isUserBubbleEntry), then keep all
            // history entries through the assistant reply (+ tool results)
            // that follow the last remaining user bubble.
            let remainingUserCount = messages.filter { $0.role == .user }.count
            var usersSeen = 0
            var keepUpTo = -1
            for (i, entry) in agentHistory.enumerated() {
                if Self.isUserBubbleEntry(entry) {
                    usersSeen += 1
                    if usersSeen > remainingUserCount {
                        // This is the edited message's entry — stop before it
                        break
                    }
                }
                keepUpTo = i
            }
            if remainingUserCount == 0 {
                agentHistory.removeAll()
            } else if keepUpTo + 1 < agentHistory.count {
                agentHistory.removeSubrange((keepUpTo + 1)...)
            }
            // Trim persisted messages.
            // Phase B: agentHistory is no longer mutated by compact, so agentHistory.count
            // is 1:1 with the DB row count; passing it as keepCount is semantically correct.
            let persistedKeepCount = agentHistory.count
            let hasMarker = cachedLatestMarker != nil
            logger.info("[DeleteAfter] edit path: sid=\(sessionId?.prefix(8) ?? "nil") keepCount=\(persistedKeepCount) hasMarker=\(hasMarker)")
            if let sessionId {
                Task { await ChatStore.shared.deleteMessagesAfter(sessionId: sessionId, keepCount: persistedKeepCount) }
            }
            // Rebuild toolSnapshots from remaining messages
            toolSnapshots = messages
                .filter { $0.role == .assistant }
                .flatMap { msg in
                    msg.blocks.compactMap { block -> ToolSnapshotItem? in
                        guard let tuId = block.toolUseId else { return nil }
                        return toolSnapshots.first(where: { $0.id == tuId })
                    }
                }
            logger.info("✏️ send() edit applied: truncated to \(self.messages.count) messages, \(self.agentHistory.count) history entries")
        }

        // Close any stale tool blocks still showing as streaming/running from a
        // previous interrupted response so the UI stops showing loading indicators.
        for msg in messages where msg.role == .assistant {
            for block in msg.blocks {
                if let status = block.toolStatus {
                    switch status {
                    case .streaming, .running:
                        block.toolStatus = .cancelled
                    default: break
                    }
                }
            }
        }

        // Note: toolSnapshots are NOT cleared here so the floating toolbar shows the full session history
        let displayText = text.isEmpty && pendingAttachments.isEmpty ? "" : text
        let userMsg = ChatMessage(role: .user, content: displayText)
        messages.append(userMsg)

        // Snapshot and clear attachments synchronously so the UI clears immediately
        clearAttachments()
        scrollToBottomSignal.send()

        isProcessing = true
        errorMessage = nil
        logger.info("🔄SESSION [vm=\(self.vmInstanceId)] send START session=\(self.sessionId ?? "nil")")

        // Ensure kernel is booted before running
        ensureKernelBooted()
        beginBackgroundProcessing()

        currentTask = Task { [weak self] in
            guard let self else { return }

            // Lazily create session before persisting anything
            await self.ensureSession()

            // Defensive: ensure tracker reflects the real session ID after draft→real migration.
            if let sid = self.sessionId, self.isProcessing {
                SessionActivityTracker.shared.setActive(sid, source: "vm=\(self.vmInstanceId) send/ensureSession draft→real")
            }

            // Copy attachment files to session uploads dir and build metadata
            let fm = FileManager.default
            let sid = self.sessionId ?? "unknown"
            let uploadsDir = Self.minisUploadsDir(for: sid)
            try? fm.createDirectory(at: uploadsDir, withIntermediateDirectories: true)

            let isoFormatter = ISO8601DateFormatter()
            isoFormatter.formatOptions = [.withInternetDateTime]
            let now = Date()
            let nowStr = isoFormatter.string(from: now)

            var attachmentMetas: [AttachmentMeta] = []
            var userParts: [AgentContentPart] = []

            // User attachments always take priority over history images — see
            // the same rationale in buildUserMessageParts. Stale history
            // images will be evicted by the trim pass before the next API
            // call.
            let inlineBudget = Self.kImageContextKeepCount
            var inlinedImages = 0
            // Cumulative bytes of all inlined image payloads in this message.
            // Once this passes kMessageImageMaxBytes the remaining images are
            // dropped to a text placeholder instead of being base64-inlined.
            // T-imgsize-13b7d81c.
            var cumulativeImageBytes = 0

            logger.info("📎[SEND-ASYNC] Processing \(pendingAttachments.count) attachments, uploadsDir=\(uploadsDir.path), imageBudget=\(inlineBudget) byteBudget=\(Self.kMessageImageMaxBytes)")
            for (i, attachment) in pendingAttachments.enumerated() {
                let fileExists = fm.fileExists(atPath: attachment.cacheURL.path)
                logger.info("📎[SEND-ASYNC] attachment[\(i)] kind=\(String(describing: attachment.kind)) file=\(attachment.fileName) cacheExists=\(fileExists)")

                guard fileExists else {
                    logger.error("📎[SEND-ASYNC]   FAILED — source missing at \(attachment.cacheURL.path)")
                    continue
                }

                // [T-ios-attachment-oom-bg-kill] Read file metadata (date + size)
                // WITHOUT loading the file into memory. Previously this did
                // `Data(contentsOf:)` on every attachment just to copy it to the
                // uploads dir and record its size — for a large attachment (e.g. a
                // 368 MB sysdiagnose) that spiked the app footprint by the file's
                // full size (~370 MB → ~690 MB total) for several seconds per send.
                // Backgrounding inside that window let iOS jetsam-SIGKILL the
                // process (it reclaims high-memory background apps first), which
                // looked like "the message stopped instantly / was never sent".
                let attrs = try? fm.attributesOfItem(atPath: attachment.cacheURL.path)
                // Preserve original file date if available (e.g. PHAsset creation date)
                let fileDate: Date = (attrs?[.modificationDate] as? Date) ?? now
                let fileSize = (attrs?[.size] as? Int) ?? 0

                let safeName = Self.uniqueFileName(for: attachment.fileName, in: uploadsDir)
                let destURL = uploadsDir.appendingPathComponent(safeName)
                // Stream the file on disk (near-zero RAM) instead of materializing
                // it as Data and writing it back out. copyItem won't overwrite, and
                // uniqueFileName already guarantees a fresh name, so no clobber.
                do {
                    try fm.copyItem(at: attachment.cacheURL, to: destURL)
                } catch {
                    logger.error("📎[SEND-ASYNC]   FAILED to copy \(attachment.cacheURL.lastPathComponent) → \(destURL.path): \(error.localizedDescription)")
                    continue
                }
                try? fm.setAttributes([.creationDate: fileDate, .modificationDate: fileDate], ofItemAtPath: destURL.path)
                let linuxPath = "/var/minis/attachments/uploads/\(safeName)"
                let meta = AttachmentMeta(path: linuxPath, size: fileSize, modified: fileDate)
                attachmentMetas.append(meta)
                logger.info("📎[SEND-ASYNC]   saved \(safeName): \(fileSize) bytes → \(linuxPath)")

                // [T-ios-attachment-oom-bg-kill] Only IMAGES need the bytes in
                // memory (for resize/compress/inline below). Non-image attachments
                // are already persisted via the streaming copy above — never load
                // them into RAM. Images are small (camera/screenshot/photo), so
                // loading them here is bounded and safe.
                guard attachment.kind == .image else { continue }
                guard let data = try? Data(contentsOf: attachment.cacheURL) else {
                    logger.error("📎[SEND-ASYNC]   image load failed (kept on disk) \(attachment.cacheURL.path)")
                    continue
                }

                // For images: send a resized + size-budgeted copy to the
                // model. Two budgets layered on top of the existing 20-image
                // count cap:
                //   1. Per-image: if the resize-to-2000px output is still
                //      over 5 MB, run the compressor ladder (smaller edges,
                //      lower JPEG quality) until it fits or until we've
                //      shrunk it as far as we usefully can.
                //   2. Per-message: cap cumulative inlined bytes at 20 MB.
                //      Once that's hit, remaining images fall through to
                //      the same text placeholder the count cap uses.
                // T-imgsize-13b7d81c.
                // (Reaching here implies .image — the non-image guard above
                // already `continue`d. Kept as an explicit guard for clarity.)
                if attachment.kind == .image {
                    if inlinedImages < inlineBudget {
                        let resized = await MainActor.run { Self.resizedImageData(data, maxLongEdge: 2000) } ?? data
                        let compressed: Data = await MainActor.run {
                            Self.compressedImageDataUnderBudget(resized, targetMaxBytes: Self.kPerImageMaxBytes).data
                        }
                        let stillOversize = compressed.count > Self.kPerImageMaxBytes
                        let wouldOverflowMessage = (cumulativeImageBytes + compressed.count) > Self.kMessageImageMaxBytes
                        if wouldOverflowMessage {
                            // Drop to placeholder rather than risk a 413 on
                            // the whole message — the user keeps the file on
                            // disk and can still reference it via shell tools.
                            let placeholder = Self.imagePlaceholderText(data: data, originalPath: linuxPath, snapshotPath: nil)
                            userParts.append(.text(placeholder))
                            logger.warning("📎[SEND-ASYNC]   image \(i) dropped to placeholder — cumulative \(cumulativeImageBytes) + this \(compressed.count) > \(Self.kMessageImageMaxBytes) message budget")
                        } else {
                            let ext = attachment.cacheURL.pathExtension.lowercased()
                            let mime: String
                            if compressed.count != data.count {
                                // We re-encoded (resize or compressor ladder
                                // ran). Output is always JPEG.
                                mime = "image/jpeg"
                            } else {
                                switch ext {
                                case "png":  mime = "image/png"
                                case "gif":  mime = "image/gif"
                                case "webp": mime = "image/webp"
                                default:     mime = "image/jpeg"
                                }
                            }
                            userParts.append(.text("[attached image: \(linuxPath)]"))
                            userParts.append(.imageData(data: compressed, mimeType: mime, linuxPath: linuxPath))
                            inlinedImages += 1
                            cumulativeImageBytes += compressed.count
                            logger.info("📎[SEND-ASYNC]   image \(inlinedImages)/\(inlineBudget) inlined: orig=\(data.count) resized=\(resized.count) final=\(compressed.count) cumulative=\(cumulativeImageBytes)/\(Self.kMessageImageMaxBytes) mime=\(mime) stillOversize=\(stillOversize)")
                            if stillOversize {
                                logger.warning("📎[SEND-ASYNC]   image \(i) still over per-image budget after compression ladder — Provider may 413; sent anyway")
                            }
                        }
                    } else {
                        let placeholder = Self.imagePlaceholderText(data: data, originalPath: linuxPath, snapshotPath: nil)
                        userParts.append(.text(placeholder))
                        logger.info("📎[SEND-ASYNC]   image not inlined (count budget \(inlineBudget) exhausted), placeholder for \(linuxPath)")
                    }
                }
            }

            // Clean up cached attachment files now that data has been copied
            Self.cleanupAttachmentFiles(pendingAttachments)

            // Update ChatMessage with structured attachment metadata for UI display
            await MainActor.run { userMsg.attachments = attachmentMetas }

            // Build <user-attached-files> XML metadata for the model (no base64 data)
            if !attachmentMetas.isEmpty {
                var xml = "<user-attached-files>\n"
                for meta in attachmentMetas {
                    xml += "  <file path=\"\(meta.path)\" url=\"\(meta.minisURL)\" size=\"\(meta.size)\" modified=\"\(nowStr)\" />\n"
                }
                xml += "</user-attached-files>"
                userParts.append(.text(xml))
            }

            // Batch-reading tip when images were omitted
            let totalImageAttachments = pendingAttachments.filter { $0.kind == .image }.count
            if totalImageAttachments > inlinedImages {
                let omitted = totalImageAttachments - inlinedImages
                userParts.append(.text(
                    "<system-reminder>Only \(inlinedImages) of \(totalImageAttachments) images are inlined above."
                    + " The remaining \(omitted) are saved to disk — use read_image to view them."
                    + " To stay within the context image limit (\(Self.kImageContextKeepCount)),"
                    + " process images in batches: read a batch, analyze, then summarize your findings"
                    + " before reading the next batch.</system-reminder>"
                ))
            }

            if !text.isEmpty {
                userParts.append(.text(text))
            }
            let userMessage = AgentMessage(role: .user, parts: userParts)
            let userIdx = self.agentHistory.count
            self.agentHistory.append(userMessage)

            // Persist user message and write the DB id back into agentHistory so
            // compact can later resolve boundaries by id.
            if let persistedId = await self.persistAgentMessage(userMessage), userIdx < self.agentHistory.count {
                self.agentHistory[userIdx].dbMessageId = persistedId
            }

            // Wait for kernel to finish booting
            while self.kernelStatus == .booting {
                try? await Task.sleep(nanoseconds: 100_000_000) // 100ms
            }

            if case .failed(let msg) = self.kernelStatus {
                self.errorMessage = "Kernel not available: \(msg)"
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }

            // Concurrency gate: wait for a processing slot
            let concurrency = SessionConcurrencyManager.shared
            self.isSuspended = concurrency.runningSessions.count >= concurrency.maxConcurrent
            do {
                try await concurrency.acquireSlot(sessionId: sid)
                self.isSuspended = false
            } catch {
                self.isSuspended = false
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }
            defer { concurrency.releaseSlot(sessionId: sid) }

            do {
                try await self.runAgentLoop()
            } catch is CancellationError {
                logger.info("Agent loop cancelled")
                self.handleUserCancelledCleanup()
            } catch {
                let rawDesc = String(describing: error)
                logger.error("Agent loop error: \(rawDesc)")
                if let llmErr = error as? LLMError {
                    logger.error("Agent loop LLMError detail: \(llmErr.errorDescription ?? "nil")")
                }
                if self.userDidCancel {
                    self.handleUserCancelledCleanup()
                } else {
                    let displayDesc = Self.friendlyErrorMessage((error as? LocalizedError)?.errorDescription ?? rawDesc)
                    // [T-ios-retry-tool-blocks-invisible / T-stream-empty-banner-trace]
                    // Resolve the streaming assistant by the last ASSISTANT row,
                    // not messages.last. A queued .user message (enqueuePrompt
                    // appends one mid-tool) sits AFTER the candidate, so
                    // messages.last would be that user row — the error then fell
                    // through to the top red banner (vm.errorMessage) instead of
                    // attaching to the assistant bubble. Same root cause as the
                    // Stop-loses-content fix.
                    if let last = self.messages.last(where: { $0.role == .assistant }) {
                        last.error = displayDesc
                        last.blocks.removeAll { $0.kind == .text && $0.content.isEmpty }
                        // Cancel stale tool blocks left in streaming/running state
                        for block in last.blocks {
                            if case .streaming = block.toolStatus { block.toolStatus = .cancelled }
                            else if case .running = block.toolStatus { block.toolStatus = .cancelled }
                        }
                        Task { await self.persistErrorInfo(displayDesc) }  // [T-error-persist-ios]
                    } else {
                        self.errorMessage = displayDesc
                    }
                }
            }
            // If the user tapped Stop while the loop was finishing, still mark as interrupted.
            if self.userDidCancel { self.handleUserCancelledCleanup() }

            // [T-stop-with-queue-render-desync] Stop hands ownership to cancel():
            // it already set isProcessing=false / endBackgroundProcessing(), and
            // with queued prompts it spawned a fresh drain task that sets
            // isProcessing=true for the continued run. Running this epilogue from
            // the superseded task would drain concurrently and force
            // isProcessing=false mid-run — the chat stops rendering the live tool
            // loop while the preview bar / Live Activity keep updating.
            guard !Task.isCancelled else {
                logger.info("🔄SESSION [vm=\(self.vmInstanceId)] send epilogue skipped (task cancelled) session=\(self.sessionId ?? "nil")")
                return
            }

            await self.drainQueuedPrompts()

            // Refresh lastKnownDbSortOrder from the actual DB so iCloud sync
            // doesn't mistake our own persisted messages for remote ones.
            // The in-memory += 1 tracking can drift if messages are batched or retried.
            if let sid = self.sessionId {
                let dbMessages = await ChatStore.shared.loadMessages(sessionId: sid)
                self.lastKnownDbSortOrder = dbMessages.last?.sortOrder ?? self.lastKnownDbSortOrder
                self.lastKnownDbCount = dbMessages.count
                // T-baseline-hash-drift: also refresh lastKnownDbOrderHash —
                // otherwise reloadMessagesFromDB's hasReorderedMessages check
                // sees the old (pre-INSERT) hash vs the new (post-INSERT)
                // row set, flags a fake reorder, and fires loadSession()
                // (spinner ON ~460ms) on the next iCloud fetch notification.
                self.lastKnownDbOrderHash = Self.computeOrderHash(of: dbMessages)
            }

            logger.info("🔄SESSION [vm=\(self.vmInstanceId)] send DONE session=\(self.sessionId ?? "nil")")
            self.playCompletionHaptic()
            self.isProcessing = false
            self.endBackgroundProcessing()
        }
    }

    /// Retry the next agent loop iteration (continue from where the error occurred,
    /// keeping all previously completed tool blocks and conversation history).
    func retry() {
        guard !isProcessing else { return }
        guard let lastMsg = messages.last, lastMsg.role == .assistant else { return }

        autoRetryAttempt = 0
        autoRetryCountdown = 0
        canResume = false
        userDidCancel = false

        // Clear the error on the existing assistant message
        lastMsg.error = nil
        lastMsg.streamInterruptCount = 0

        // Remove blocks from the incomplete/interrupted iteration.
        // committedBlockCount tracks how many blocks were fully committed before the
        // interruption. Anything beyond that is partial streaming content that should
        // be discarded so the retry starts cleanly from the last committed state.
        //
        // [T-ios-retry-wipes-prior-text] Use clearUncommittedStreamTail instead of a
        // blind `removeSubrange(committedBlockCount...)`. committedBlockCount is a
        // shared instance property; error-interrupted (non-cancel) paths don't update
        // it, so on retry it can LAG behind the message's actual committed+persisted
        // block count. A blind removeSubrange then wipes already-committed non-empty
        // text/terminal-tool blocks the user had on screen (the "retry makes all prior
        // output vanish" report; DB intact since retry only mutates the in-memory
        // array). The shared helper drops only the uncommitted tail's partial text +
        // streaming/running tools and ALWAYS keeps non-empty text and terminal tool
        // results — identical to the blind trim in the normal case (tail is empty/
        // partial text + in-flight tools), but it never removes committed output.
        AppLogger(category: "RetryDiag").info("[RetryDiag] retry() pre-trim committedBlockCount=\(self.committedBlockCount) blocks=\(lastMsg.blocks.count) kinds=[\(lastMsg.blocks.map { $0.kind == .text ? "t\($0.content.isEmpty ? "0" : "\($0.content.count)")" : "tool(\($0.toolStatus.map { String(describing: $0) } ?? "nil"))" }.joined(separator: ","))]")
        Self.clearUncommittedStreamTail(lastMsg, committedBlockCount: committedBlockCount)
        // Also remove any trailing empty text blocks left by prior iterations
        lastMsg.blocks.removeAll { $0.kind == .text && $0.content.isEmpty }

        // Roll back only the last failed assistant entry from conversation history.
        // The history at this point looks like:
        //   ... user(tool_results) assistant(failed/partial)
        // We need to remove just the trailing assistant entry so the API call retries
        // with the tool results as the last message.
        if agentHistory.last?.role == .assistant {
            agentHistory.removeLast()
        }

        // Clean orphaned tool_results that reference tool_uses no longer in history.
        // This fixes API 400: "unexpected tool_use_id found in tool_result blocks"
        // which occurs after partial cancellation or interrupted streaming.
        var allRetryToolUseIds = Set<String>()
        for msg in agentHistory { for part in msg.parts {
            if case .toolUse(let id, _, _) = part { allRetryToolUseIds.insert(id) }
        }}
        for i in (0..<agentHistory.count).reversed() {
            guard agentHistory[i].role == .user else { continue }
            let cleaned = agentHistory[i].parts.filter { part in
                if case .toolResult(let id, _, _, _, _, _, _, _) = part { return allRetryToolUseIds.contains(id) }
                return true
            }
            if cleaned.isEmpty { agentHistory.remove(at: i) }
            else if cleaned.count < agentHistory[i].parts.count {
                agentHistory[i] = AgentMessage(role: .user, parts: cleaned)
            }
        }

        let existingMsgIdx = messages.count - 1
        let existingBlockCount = lastMsg.blocks.count
        errorMessage = nil
        isProcessing = true
        isNearBottom = true
        forceScrollToBottom.send()

        ensureKernelBooted()
        beginBackgroundProcessing()

        currentTask = Task { [weak self] in
            guard let self else { return }

            while self.kernelStatus == .booting {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            if case .failed(let msg) = self.kernelStatus {
                self.errorMessage = "Kernel not available: \(msg)"
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }

            // Concurrency gate: wait for a processing slot
            let retrySid = self.sessionId ?? "unknown"
            let concurrency = SessionConcurrencyManager.shared
            self.isSuspended = concurrency.runningSessions.count >= concurrency.maxConcurrent
            do {
                try await concurrency.acquireSlot(sessionId: retrySid)
                self.isSuspended = false
            } catch {
                self.isSuspended = false
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }
            defer { concurrency.releaseSlot(sessionId: retrySid) }

            do {
                try await self.runAgentLoop(resumingAt: existingMsgIdx, committedBlocks: existingBlockCount)
            } catch is CancellationError {
                logger.info("Agent loop cancelled (retry)")
                self.handleUserCancelledCleanup()
            } catch {
                let rawDesc = String(describing: error)
                logger.error("Agent loop error (retry): \(rawDesc)")
                if let llmErr = error as? LLMError {
                    logger.error("Agent loop LLMError detail: \(llmErr.errorDescription ?? "nil")")
                }
                if self.userDidCancel {
                    self.handleUserCancelledCleanup()
                } else {
                    let displayDesc = Self.friendlyErrorMessage((error as? LocalizedError)?.errorDescription ?? rawDesc)
                    // [T-ios-retry-tool-blocks-invisible / T-stream-empty-banner-trace]
                    // Resolve the streaming assistant by the last ASSISTANT row,
                    // not messages.last. A queued .user message (enqueuePrompt
                    // appends one mid-tool) sits AFTER the candidate, so
                    // messages.last would be that user row — the error then fell
                    // through to the top red banner (vm.errorMessage) instead of
                    // attaching to the assistant bubble. Same root cause as the
                    // Stop-loses-content fix.
                    if let last = self.messages.last(where: { $0.role == .assistant }) {
                        last.error = displayDesc
                        Task { await self.persistErrorInfo(displayDesc) }  // [T-error-persist-ios]
                        last.blocks.removeAll { $0.kind == .text && $0.content.isEmpty }
                        for block in last.blocks {
                            if case .streaming = block.toolStatus { block.toolStatus = .cancelled }
                            else if case .running = block.toolStatus { block.toolStatus = .cancelled }
                        }
                    } else {
                        self.errorMessage = displayDesc
                    }
                }
            }
            if self.userDidCancel { self.handleUserCancelledCleanup() }
            // [T-stop-with-queue-render-desync] See send(): Stop's cancel() owns
            // state teardown; a superseded task must not drain or flip isProcessing.
            guard !Task.isCancelled else {
                logger.info("🔄SESSION [vm=\(self.vmInstanceId)] retry epilogue skipped (task cancelled) session=\(self.sessionId ?? "nil")")
                return
            }
            await self.drainQueuedPrompts()
            logger.info("🔄SESSION [vm=\(self.vmInstanceId)] retry DONE session=\(self.sessionId ?? "nil")")
            self.playCompletionHaptic()
            self.isProcessing = false
            self.endBackgroundProcessing()
        }
    }

    /// Resume an interrupted agent loop. Injects a "Continue" user message
    /// into agentHistory so the model knows to pick up where it left off.
    func resume() {
        guard !isProcessing, canResume else { return }
        guard let lastMsg = messages.last, lastMsg.role == .assistant else { return }

        canResume = false
        userDidCancel = false

        // Safety: trim any uncommitted blocks (should be no-op with new cancel flow).
        // [T-ios-retry-wipes-prior-text] Same fix as retry(): the blind
        // removeSubrange(committedBlockCount...) here wiped committed post-tool text
        // when committedBlockCount lagged (the warning below literally predicted it).
        // clearUncommittedStreamTail preserves non-empty text + terminal tools while
        // still dropping the uncommitted partial/streaming tail.
        if committedBlockCount < lastMsg.blocks.count {
            logger.info("⏹️[StopDiag] resume() pre-trim committed=\(self.committedBlockCount) blocks=\(lastMsg.blocks.count) — preserving committed text/tools via clearUncommittedStreamTail")
            Self.clearUncommittedStreamTail(lastMsg, committedBlockCount: committedBlockCount)
        } else {
            logger.info("⏹️[StopDiag] resume() no trim (committed=\(self.committedBlockCount) >= blocks=\(lastMsg.blocks.count))")
        }

        // Mark any remaining tool blocks with nil status as cancelled
        for block in lastMsg.blocks {
            if block.toolStatus == nil, block.kind != .text {
                block.toolStatus = .cancelled
            }
        }

        // Inject a "Continue" user message only when history ends with assistant
        // (Case 2a: text streaming cancel). For Case 1 (tool cancel), history already
        // ends with tool_result (user role) and is valid for the next API call.
        if agentHistory.last?.role == .assistant {
            let continueMsg = AgentMessage(role: .user, parts: [
                .text("<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>")
            ])
            let continueIdx = agentHistory.count
            agentHistory.append(continueMsg)
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let pid = await self.persistAgentMessage(continueMsg), continueIdx < self.agentHistory.count {
                    self.agentHistory[continueIdx].dbMessageId = pid
                }
            }
        }

        let existingMsgIdx = messages.count - 1
        let existingBlockCount = lastMsg.blocks.count
        errorMessage = nil
        isProcessing = true

        ensureKernelBooted()
        beginBackgroundProcessing()

        currentTask = Task { [weak self] in
            guard let self else { return }

            while self.kernelStatus == .booting {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            if case .failed(let msg) = self.kernelStatus {
                self.errorMessage = "Kernel not available: \(msg)"
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }

            let resumeSid = self.sessionId ?? "unknown"
            let concurrency = SessionConcurrencyManager.shared
            self.isSuspended = concurrency.runningSessions.count >= concurrency.maxConcurrent
            do {
                try await concurrency.acquireSlot(sessionId: resumeSid)
                self.isSuspended = false
            } catch {
                self.isSuspended = false
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }
            defer { concurrency.releaseSlot(sessionId: resumeSid) }

            do {
                try await self.runAgentLoop(resumingAt: existingMsgIdx, committedBlocks: existingBlockCount)
            } catch is CancellationError {
                logger.info("Agent loop cancelled (resume)")
                self.handleUserCancelledCleanup()
            } catch {
                let rawDesc = String(describing: error)
                logger.error("Agent loop error (resume): \(rawDesc)")
                if let llmErr = error as? LLMError {
                    logger.error("Agent loop LLMError detail: \(llmErr.errorDescription ?? "nil")")
                }
                if self.userDidCancel {
                    self.handleUserCancelledCleanup()
                } else {
                    let displayDesc = Self.friendlyErrorMessage((error as? LocalizedError)?.errorDescription ?? rawDesc)
                    // [T-ios-retry-tool-blocks-invisible / T-stream-empty-banner-trace]
                    // Resolve the streaming assistant by the last ASSISTANT row,
                    // not messages.last. A queued .user message (enqueuePrompt
                    // appends one mid-tool) sits AFTER the candidate, so
                    // messages.last would be that user row — the error then fell
                    // through to the top red banner (vm.errorMessage) instead of
                    // attaching to the assistant bubble. Same root cause as the
                    // Stop-loses-content fix.
                    if let last = self.messages.last(where: { $0.role == .assistant }) {
                        last.error = displayDesc
                        Task { await self.persistErrorInfo(displayDesc) }  // [T-error-persist-ios]
                        last.blocks.removeAll { $0.kind == .text && $0.content.isEmpty }
                        for block in last.blocks {
                            if case .streaming = block.toolStatus { block.toolStatus = .cancelled }
                            else if case .running = block.toolStatus { block.toolStatus = .cancelled }
                        }
                    } else {
                        self.errorMessage = displayDesc
                    }
                }
            }
            if self.userDidCancel { self.handleUserCancelledCleanup() }
            // [T-stop-with-queue-render-desync] See send(): Stop's cancel() owns
            // state teardown; a superseded task must not drain or flip isProcessing.
            guard !Task.isCancelled else {
                logger.info("🔄SESSION [vm=\(self.vmInstanceId)] resume epilogue skipped (task cancelled) session=\(self.sessionId ?? "nil")")
                return
            }
            await self.drainQueuedPrompts()
            logger.info("🔄SESSION [vm=\(self.vmInstanceId)] resume DONE session=\(self.sessionId ?? "nil")")
            self.playCompletionHaptic()
            self.isProcessing = false
            self.endBackgroundProcessing()
        }
    }

    /// Retry from a specific user message: roll back UI and history to the state
    /// just after that user message was sent, then re-run the agent loop.
    /// [T-ios-retry-anchor-synthetic-user] True when this agentHistory entry
    /// corresponds to a user bubble in `messages`. The agent loop also appends
    /// user-role entries that have NO bubble: pure tool_result carriers, the
    /// resume() stop-continue `<system-reminder>` message (see resume()), and
    /// the browser-takeover note (text + screenshot, AIChatViewModel+
    /// BackgroundTask.waitIfBrowserTakeover). Counting those against the UI
    /// bubble ordinal made retry/edit truncation anchor one user message too
    /// early — silently dropping the retried message plus the whole last turn,
    /// so the model "re-answered" the previous instruction instead.
    /// A user entry counts as a bubble iff it carries user-visible payload:
    /// a non-synthetic text part, or a non-tool payload (image/attachment)
    /// not accompanied by synthetic text.
    static func isUserBubbleEntry(_ entry: AgentMessage) -> Bool {
        guard entry.role == .user else { return false }
        var hasVisibleText = false
        var hasSyntheticText = false
        var hasNonToolPayload = false
        for part in entry.parts {
            switch part {
            case .text(let t):
                let trimmed = t.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty { continue }
                if trimmed.hasPrefix("<system-reminder>") || trimmed.hasPrefix("[Browser takeover]") {
                    hasSyntheticText = true
                } else {
                    hasVisibleText = true
                }
            case .imageData:
                hasNonToolPayload = true
            case .toolUse, .toolResult:
                continue
            }
        }
        return hasVisibleText || (hasNonToolPayload && !hasSyntheticText)
    }

    func retryFromMessage(_ messageId: UUID, replacementAttachments: [InputAttachment]? = nil) {
        guard !isProcessing else { return }
        // [T-ios-retry-usermsg-vanish] Guard the whole truncation window so a
        // deferred iCloud-sync reload can't rebuild `messages` from a
        // mid-truncation DB snapshot and drop the retried user message. Cleared
        // once the DB truncation Task below finishes; isProcessing=true (set in
        // launchRerunAgentLoop) keeps suppressing reloads afterward.
        isTruncatingForRetry = true
        canResume = false
        userDidCancel = false
        guard let idx = messages.firstIndex(where: { $0.id == messageId }),
              messages[idx].role == .user else {
            isTruncatingForRetry = false
            return
        }

        // Remove everything AFTER the selected user message (keep the user message itself)
        if idx + 1 < messages.count {
            messages.removeSubrange((idx + 1)...)
        }
        // [T-ios-retry-ui-clear] symmetric with retryFromToolBlock's paths —
        // force a top-level publish so the UI clears synchronously on this
        // tick. The vm.$messages sink already fires off-tick (main-queue
        // receive), so without this the stale cells linger one runloop.
        if !transitionSuspended { objectWillChange.send() }
        AppLogger(category: "RetryDiag").info("[RetryDiag] retryFromMessage truncated userIdx=\(idx) messagesCount=\(self.messages.count)")

        // Trim agentHistory: count how many user BUBBLES (not synthetic
        // user-role entries — see isUserBubbleEntry) up to and including this
        // one, then find the matching entry in agentHistory and remove
        // everything after it.
        let targetUserCount = messages[...idx].filter { $0.role == .user }.count
        var usersSeen = 0
        var keepUpTo = -1  // index of the last entry to keep (inclusive)
        for (i, entry) in agentHistory.enumerated() {
            if Self.isUserBubbleEntry(entry) {
                usersSeen += 1
            }
            if usersSeen == targetUserCount {
                keepUpTo = i
                break
            }
        }
        // [T-ios-retry-anchor-synthetic-user] Fail OPEN on a UI↔history
        // mismatch: anchor to the end (truncation becomes a no-op) and let the
        // rerun proceed with full history. Keeping too much is recoverable;
        // the old behavior (keepUpTo = 0) silently nuked the session down to
        // one entry.
        if keepUpTo < 0 {
            logger.error("[RetryDiag] retryFromMessage anchor NOT FOUND (targetUserCount=\(targetUserCount), history=\(self.agentHistory.count)) — keeping full history")
            keepUpTo = agentHistory.count - 1
        }
        // Keep entries 0...keepUpTo, remove the rest
        if keepUpTo + 1 < agentHistory.count {
            agentHistory.removeSubrange((keepUpTo + 1)...)
        }

        // Replace attachments in the last user message of agentHistory if new ones are provided
        if let newAttachments = replacementAttachments, !newAttachments.isEmpty {
            let uploadsDir = Self.minisUploadsDir(for: sessionId ?? "draft")
            let fm = FileManager.default
            try? fm.createDirectory(at: uploadsDir, withIntermediateDirectories: true)
            let nowStr = ISO8601DateFormatter().string(from: Date())
            let (newParts, newMetas) = processAttachments(newAttachments, uploadsDir: uploadsDir, nowStr: nowStr)
            Self.cleanupAttachmentFiles(newAttachments)

            // Replace parts in the last user agentHistory entry:
            // keep only .text parts that are NOT <user-attached-files>, then append new parts
            if !agentHistory.isEmpty {
                var lastEntry = agentHistory[keepUpTo]
                let filteredParts = lastEntry.parts.filter { part in
                    if case .text(let t) = part, t.contains("<user-attached-files>") { return false }
                    if case .imageData = part { return false }
                    return true
                }
                lastEntry.parts = filteredParts + newParts
                agentHistory[keepUpTo] = lastEntry
            }

            // Update the ChatMessage's attachments for display
            messages[idx].attachments = newMetas
        }

        // Trim persisted messages to match.
        // Phase B: agentHistory is no longer mutated by compact, so keepCount is valid.
        let persistedKeepCount = agentHistory.count
        let hasMarkerResend = cachedLatestMarker != nil
        logger.info("[DeleteAfter] resend path: sid=\(sessionId?.prefix(8) ?? "nil") keepCount=\(persistedKeepCount) hasMarker=\(hasMarkerResend)")
        if let sessionId {
            Task { @MainActor [weak self] in
                await ChatStore.shared.deleteMessagesAfter(sessionId: sessionId, keepCount: persistedKeepCount)
                // DB truncation done — the truncation window is closed. By now
                // launchRerunAgentLoop has set isProcessing=true, so reloads
                // stay suppressed through the rerun; this just releases the
                // retry-specific guard.
                self?.isTruncatingForRetry = false
            }
        } else {
            isTruncatingForRetry = false
        }

        rebuildToolSnapshotsFromMessages()
        launchRerunAgentLoop(label: "retryFromMessage")
    }

    /// Rebuild `toolSnapshots` to only those still referenced by a tool_use
    /// block in the (post-truncation) messages list. Shared by the user-
    /// message retry path and the tool-block re-run path.
    private func rebuildToolSnapshotsFromMessages() {
        toolSnapshots = messages
            .filter { $0.role == .assistant }
            .flatMap { msg in
                msg.blocks.compactMap { block -> ToolSnapshotItem? in
                    guard let tuId = block.toolUseId else { return nil }
                    return toolSnapshots.first(where: { $0.id == tuId })
                }
            }
    }

    /// Shared re-run tail used by retryFromMessage and retryFromToolBlock:
    /// reset transient UI state, flip isProcessing, boot the kernel, and
    /// launch the agent loop on `currentTask`. Caller must have already
    /// truncated messages / agentHistory / persisted rows to the desired
    /// re-entry point. [T-ios-rerun-from-tool-block-position]
    ///
    /// `resumeAt`/`resumeBlocks`: when the truncated tail still ends in an
    /// assistant message that the new generation should CONTINUE (the
    /// block-boundary re-run keeps the surviving earlier blocks of that
    /// turn), pass its index + surviving block count so runAgentLoop reuses
    /// it instead of appending a fresh assistant message — otherwise the UI
    /// shows two consecutive assistant headers (the trimmed turn + the new
    /// stream). The user-message retry path leaves `messages.last` as the
    /// user message and passes nil, so a fresh assistant is appended as
    /// before. [T-ios-retry-double-header]
    ///
    /// Immediate thinking UI [T-ios-retry-thinking-ui]: the agent loop's own
    /// assistant-message creation runs inside the async Task (after kernel
    /// boot + concurrency gate), so without preparing UI here the user sees
    /// no feedback for hundreds of ms after pressing "Retry" / "Re-run From
    /// Here". Synchronously prepare the message that the TypingIndicator
    /// keys off:
    ///   * resumeAt == nil → append a placeholder assistant ChatMessage and
    ///     set `isAwaitingModelResponse = true`. runAgentLoop is then
    ///     instructed to RESUME into that placeholder (resumingAt =
    ///     placeholderIdx, committedBlocks = 0) so it does NOT append a
    ///     second assistant message and we don't double-create.
    ///   * resumeAt != nil → caller already has a surviving assistant turn
    ///     they want to continue (sub-message cut); just flip its
    ///     `isAwaitingModelResponse = true` so the typing indicator shows on
    ///     it until the new content starts streaming.
    /// TypingIndicator's gate is
    /// `isLast && vm.isProcessing && (!hasVisibleContent || isAwaiting…)`
    /// — covered for both cases since `isProcessing = true` is set below and
    /// `messages.last` is the prepared assistant in each case.
    private func launchRerunAgentLoop(label: String, resumeAt: Int? = nil, resumeBlocks: Int? = nil) {
        AppLogger(category: "RetryDiag").info("[RetryDiag] launchRerunAgentLoop called label=\(label) resumeAt=\(resumeAt.map(String.init) ?? "nil") resumeBlocks=\(resumeBlocks.map(String.init) ?? "nil") messagesCount=\(self.messages.count)")
        errorMessage = nil
        // Clear interrupt badge on the last assistant message if present
        if let lastAssistant = messages.last(where: { $0.role == .assistant }) {
            lastAssistant.streamInterruptCount = 0
        }

        // Immediate thinking-state prep (see doc comment above).
        let effectiveResumeAt: Int?
        let effectiveResumeBlocks: Int?
        if let r = resumeAt {
            let inBounds = r < messages.count
            let isAsst = inBounds && messages[r].role == .assistant
            if inBounds, isAsst {
                messages[r].isAwaitingModelResponse = true
            } else {
                // [RetryDiag] resumeAt points outside the array or at a non-
                // assistant row — an off-by-one in the caller's index math. The
                // loop will still try to resume at r; surface it loudly.
                AppLogger(category: "RetryDiag").warning("[RetryDiag] launchRerunAgentLoop SUSPECT resumeAt=\(r) inBounds=\(inBounds) isAssistant=\(isAsst) messagesCount=\(self.messages.count) — index may be off-by-one")
            }
            effectiveResumeAt = r
            effectiveResumeBlocks = resumeBlocks
        } else {
            // Append placeholder assistant so the typing indicator can latch
            // on instantly; tell runAgentLoop to resume into it.
            let placeholder = ChatMessage(role: .assistant, content: "", blocks: [])
            placeholder.isAwaitingModelResponse = true
            messages.append(placeholder)
            effectiveResumeAt = messages.count - 1
            effectiveResumeBlocks = 0
        }

        isProcessing = true
        forceScrollToBottom.send()

        ensureKernelBooted()
        beginBackgroundProcessing()

        currentTask = Task { [weak self] in
            guard let self else { return }

            while self.kernelStatus == .booting {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            if case .failed(let msg) = self.kernelStatus {
                self.errorMessage = "Kernel not available: \(msg)"
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }

            // Concurrency gate: wait for a processing slot
            let retryFromSid = self.sessionId ?? "unknown"
            let concurrency = SessionConcurrencyManager.shared
            self.isSuspended = concurrency.runningSessions.count >= concurrency.maxConcurrent
            do {
                try await concurrency.acquireSlot(sessionId: retryFromSid)
                self.isSuspended = false
            } catch {
                self.isSuspended = false
                self.isProcessing = false
                self.endBackgroundProcessing()
                return
            }
            defer { concurrency.releaseSlot(sessionId: retryFromSid) }

            do {
                try await self.runAgentLoop(resumingAt: effectiveResumeAt, committedBlocks: effectiveResumeBlocks)
            } catch is CancellationError {
                logger.info("Agent loop cancelled (\(label))")
                self.handleUserCancelledCleanup()
            } catch {
                let rawDesc = String(describing: error)
                logger.error("Agent loop error (\(label)): \(rawDesc)")
                if let llmErr = error as? LLMError {
                    logger.error("Agent loop LLMError detail: \(llmErr.errorDescription ?? "nil")")
                }
                if self.userDidCancel {
                    self.handleUserCancelledCleanup()
                } else {
                    let displayDesc = Self.friendlyErrorMessage((error as? LocalizedError)?.errorDescription ?? rawDesc)
                    // [T-ios-retry-tool-blocks-invisible / T-stream-empty-banner-trace]
                    // Resolve the streaming assistant by the last ASSISTANT row,
                    // not messages.last. A queued .user message (enqueuePrompt
                    // appends one mid-tool) sits AFTER the candidate, so
                    // messages.last would be that user row — the error then fell
                    // through to the top red banner (vm.errorMessage) instead of
                    // attaching to the assistant bubble. Same root cause as the
                    // Stop-loses-content fix.
                    if let last = self.messages.last(where: { $0.role == .assistant }) {
                        last.error = displayDesc
                        Task { await self.persistErrorInfo(displayDesc) }  // [T-error-persist-ios]
                        last.blocks.removeAll { $0.kind == .text && $0.content.isEmpty }
                        for block in last.blocks {
                            if case .streaming = block.toolStatus { block.toolStatus = .cancelled }
                            else if case .running = block.toolStatus { block.toolStatus = .cancelled }
                        }
                    } else {
                        self.errorMessage = displayDesc
                    }
                }
            }
            if self.userDidCancel { self.handleUserCancelledCleanup() }
            // [T-stop-with-queue-render-desync] See send(): Stop's cancel() owns
            // state teardown; a superseded task must not drain or flip isProcessing.
            guard !Task.isCancelled else {
                logger.info("🔄SESSION [vm=\(self.vmInstanceId)] \(label) epilogue skipped (task cancelled) session=\(self.sessionId ?? "nil")")
                return
            }
            await self.drainQueuedPrompts()
            logger.info("🔄SESSION [vm=\(self.vmInstanceId)] \(label) DONE session=\(self.sessionId ?? "nil")")
            self.playCompletionHaptic()
            self.isProcessing = false
            self.endBackgroundProcessing()
        }
    }

    /// [T-ios-rerun-from-tool-block-position] Re-run the agent loop from the
    /// point a specific tool_use was about to be issued. Block-boundary cut:
    /// keep the blocks BEFORE the target tool_use in its assistant turn, drop
    /// the target block + later blocks in that turn + all later messages, then
    /// re-run so the model re-decides from that point.
    ///
    /// Anchor is the block's `toolUseId` (stable, unique) — NOT positional
    /// counting — so streaming / multi-entry-per-UI-message alignment can't
    /// drift.
    ///
    /// Degenerate case: if the target is the FIRST block of its assistant
    /// message and nothing precedes it in that turn, this is equivalent to
    /// truncating at the preceding user message — delegate to
    /// retryFromMessage(precedingUser) and skip the sub-message rewrite.
    func retryFromToolBlock(blockId: UUID) {
        guard !isProcessing else { return }

        // Locate the assistant UI message + the target block's index within it.
        guard let asstMsgIdx = messages.firstIndex(where: { m in
            m.role == .assistant && m.blocks.contains(where: { $0.id == blockId })
        }) else { return }
        let asstMsg = messages[asstMsgIdx]
        guard let blockIdx = asstMsg.blocks.firstIndex(where: { $0.id == blockId }) else { return }
        guard let targetToolUseId = asstMsg.blocks[blockIdx].toolUseId, !targetToolUseId.isEmpty else {
            // Not a tool block — nothing to anchor on.
            return
        }

        // Degenerate: target is the first block and no real content precedes it
        // in this turn → fall back to the preceding-user-message truncation.
        let hasPrecedingContent = asstMsg.blocks[..<blockIdx].contains { blk in
            switch blk.kind {
            case .text: return !blk.content.isEmpty
            default: return true  // any earlier tool/info block counts
            }
        }
        if !hasPrecedingContent {
            if let userIdx = messages[..<asstMsgIdx].lastIndex(where: { $0.role == .user }) {
                // Normal degenerate case: a user message precedes this turn —
                // truncate at it and re-run (existing whole-turn path).
                logger.info("[RerunToolBlock] degenerate → retryFromMessage(precedingUser) tuId=\(targetToolUseId.prefix(12))")
                retryFromMessage(messages[userIdx].id)
                return
            }
            // [T-ios-retry-double-header] No preceding user message (session
            // opens with an assistant turn — e.g. SOUL greeting / system-
            // triggered). Don't silently no-op: truncate from this assistant
            // message's START (drop the whole message + everything after, drop
            // its agentHistory entry + everything after) and re-run fresh.
            canResume = false
            userDidCancel = false
            messages.removeSubrange(asstMsgIdx...)
            // [T-ios-retry-ui-clear] mirror the sub-message path: force a
            // top-level publish so the UI clears immediately on this tick
            // even when the snapshot binding's main-queue receive hasn't
            // fired yet.
            if !transitionSuspended { objectWillChange.send() }
            retrySnapshotReloadSignal.send()
            AppLogger(category: "RetryDiag").info("[RetryDiag] degenerate(noPrecedingUser) truncated asstMsgIdx=\(asstMsgIdx) messagesCount=\(self.messages.count)")
            // Find this assistant turn's agentHistory entry via the target's
            // toolUseId and drop it + all later entries.
            var dropFromEntry: Int? = nil
            outer0: for (ei, entry) in agentHistory.enumerated() where entry.role == .assistant {
                for part in entry.parts {
                    if case .toolUse(let id, _, _) = part, id == targetToolUseId {
                        dropFromEntry = ei
                        break outer0
                    }
                }
            }
            if let ei = dropFromEntry, ei < agentHistory.count {
                agentHistory.removeSubrange(ei...)
            }
            let keepCount = agentHistory.count
            logger.info("[RerunToolBlock] degenerate (no precedingUser) → truncate from assistant start tuId=\(targetToolUseId.prefix(12)) keepCount=\(keepCount)")
            if let sessionId {
                Task { await ChatStore.shared.deleteMessagesAfter(sessionId: sessionId, keepCount: keepCount) }
            }
            rebuildToolSnapshotsFromMessages()
            // No resume target — a fresh assistant message is appended by the
            // agent loop (messages.last is now whatever preceded this turn).
            launchRerunAgentLoop(label: "retryFromToolBlock")
            return
        }

        canResume = false
        userDidCancel = false

        // --- Sub-message cut ---
        // 1. UI: trim this assistant message's blocks to before the target,
        //    and drop all later messages.
        // [T-ios-retry-ui-clear] Mutating `messages[asstMsgIdx].blocks` only
        // fires the *nested* ChatMessage.$blocks publisher; the collection
        // view's `lastMessageBlockCountSub` is wired to the CURRENT last
        // message (which at this moment is something AFTER asstMsgIdx, not
        // asstMsgIdx itself), so that publisher fires on an unrelated
        // message — no snapshot rebuild keys off the trim. The
        // removeSubrange below DOES publish on `vm.$messages` and triggers
        // applySnapshot, but the in-snapshot identifiers come from
        // `block.id` so the stale block items can linger one runloop tick
        // before the cells are dropped. Force a top-level publish so SwiftUI
        // / the collection-view bindings see the cut synchronously.
        messages[asstMsgIdx].blocks = Array(asstMsg.blocks[..<blockIdx])
        if asstMsgIdx + 1 < messages.count {
            messages.removeSubrange((asstMsgIdx + 1)...)
        }
        if !transitionSuspended { objectWillChange.send() }
        retrySnapshotReloadSignal.send()
        AppLogger(category: "RetryDiag").info("[RetryDiag] blocks truncated asstMsgIdx=\(asstMsgIdx) blockIdx=\(blockIdx) remainingBlocks=\(self.messages[asstMsgIdx].blocks.count) messagesCount=\(self.messages.count)")

        // 2. agentHistory: find the assistant entry carrying the target
        //    .toolUse(id==targetToolUseId), trim its parts to before that
        //    part, and drop every entry after it.
        var cutEntryIdx: Int? = nil
        var cutPartIdx: Int? = nil
        outer: for (ei, entry) in agentHistory.enumerated() where entry.role == .assistant {
            for (pi, part) in entry.parts.enumerated() {
                if case .toolUse(let id, _, _) = part, id == targetToolUseId {
                    cutEntryIdx = ei
                    cutPartIdx = pi
                    break outer
                }
            }
        }
        guard let entryIdx = cutEntryIdx, let partIdx = cutPartIdx else {
            // Anchor not found in agentHistory — abort without mutating further
            // to avoid a half-applied truncation. (Shouldn't happen: UI block
            // implies a persisted tool_use entry.)
            logger.warning("[RerunToolBlock] toolUseId \(targetToolUseId.prefix(12)) not found in agentHistory — aborting sub-message cut")
            return
        }

        // [RetryDiag] Index/parts alignment for the sub-message cut. asstMsgIdx
        // is the UI message; entryIdx/partIdx are the agentHistory cut point.
        // Under merged rendering one UI message maps to MANY agentHistory
        // entries, so blockIdx (UI) and partIdx (history) are NOT expected to
        // match — log both to confirm the anchor resolved to the right entry.
        AppLogger(category: "RetryDiag").info("[RetryDiag] sub-cut anchor tuId=\(targetToolUseId.prefix(12)) UI(asstMsgIdx=\(asstMsgIdx) blockIdx=\(blockIdx) blocks=\(self.messages[asstMsgIdx].blocks.count)) history(entryIdx=\(entryIdx) partIdx=\(partIdx) entryParts=\(self.agentHistory[entryIdx].parts.count) totalEntries=\(self.agentHistory.count))")

        dumpHistoryPairing("subcut-BEFORE entryIdx=\(entryIdx) partIdx=\(partIdx) targetTU=\(targetToolUseId.prefix(8))")
        let trimmedEntryDbId = agentHistory[entryIdx].dbMessageId
        let partsBefore = agentHistory[entryIdx].parts.count
        // Log exactly which parts the cut removes from this entry (partIdx...).
        // A tool_use here whose tool_result lives in a kept entry → orphan.
        let removedParts = agentHistory[entryIdx].parts[partIdx...].map { p -> String in
            switch p {
            case .text(let t): return "text(\(t.count)ch)"
            case .toolUse(let id, let name, _): return "TU(\(name),\(id.prefix(8)))"
            case .toolResult(let id, _, _, _, _, _, _, _): return "TR(\(id.prefix(8)))"
            case .imageData: return "image"
            }
        }
        AppLogger(category: "PairDiag").info("[PairDiag] subcut removes entry[\(entryIdx)].parts[\(partIdx)...]: [\(removedParts.joined(separator: ", "))]")
        agentHistory[entryIdx].parts = Array(agentHistory[entryIdx].parts[..<partIdx])
        let droppedEntries = (entryIdx + 1 < agentHistory.count) ? (agentHistory.count - (entryIdx + 1)) : 0
        if entryIdx + 1 < agentHistory.count {
            agentHistory.removeSubrange((entryIdx + 1)...)
        }
        AppLogger(category: "RetryDiag").info("[RetryDiag] sub-cut applied entry[\(entryIdx)] parts \(partsBefore)→\(self.agentHistory[entryIdx].parts.count), droppedTrailingEntries=\(droppedEntries), newHistoryCount=\(self.agentHistory.count), trimmedRowDbId=\(trimmedEntryDbId?.prefix(8) ?? "nil")")
        // If this fires ORPHANS, the sub-cut stranded a tool_result — i.e. the
        // (entryIdx, partIdx) cut point removed a tool_use whose tool_result
        // sits in an entry that was NOT dropped. That's the bug to chase.
        dumpHistoryPairing("subcut-AFTER")

        // 3. Persist: delete rows after the trimmed assistant entry, then
        //    rewrite the trimmed entry's row in place.
        let persistedKeepCount = agentHistory.count
        logger.info("[RerunToolBlock] sub-message cut tuId=\(targetToolUseId.prefix(12)) keepCount=\(persistedKeepCount) trimmedRow=\(trimmedEntryDbId?.prefix(8) ?? "nil")")
        if let sessionId {
            // [T-ios-retry-from-tool-block-oob] Snapshot the whole trimmed entry
            // (a value type) SYNCHRONOUSLY, before the Task suspends. The Task
            // below first awaits deleteMessagesAfter; during that suspension the
            // re-run agent loop (launchRerunAgentLoop, started further down) runs
            // runAgentLoop's "remove orphaned tool_result" pass, which can shrink
            // agentHistory (e.g. 19→18). If we then read `agentHistory[entryIdx]`
            // after the await, entryIdx is stale and the subscript traps with
            // "Index out of range" (ContiguousArrayBuffer.swift:692, crash
            // 2026-05-31). buildRawMessage takes an AgentMessage by value, so the
            // captured snapshot is all we need — never index the live array here.
            let trimmedEntry = agentHistory[entryIdx]
            let trimmedParts = trimmedEntry.parts
            let dbId = trimmedEntryDbId
            Task {
                await ChatStore.shared.deleteMessagesAfter(sessionId: sessionId, keepCount: persistedKeepCount)
                if let dbId {
                    let raw = await self.buildRawMessage(trimmedEntry)
                    await ChatStore.shared.updateMessageParts(messageId: dbId, parts: raw?.parts ?? Self.contentPartsFallback(trimmedParts))
                }
            }
        }

        // 4. toolSnapshots + re-run.
        // [T-ios-retry-double-header] The trimmed assistant message survives
        // at asstMsgIdx (now the last message) with `blockIdx` surviving
        // blocks. Resume INTO it so the new generation continues that turn
        // rather than appending a second assistant message (which rendered a
        // duplicate header). Mark its still-streaming/running tool blocks
        // cancelled first (none should remain before the cut point, but guard).
        for block in messages[asstMsgIdx].blocks {
            if case .streaming = block.toolStatus { block.toolStatus = .cancelled }
            else if case .running = block.toolStatus { block.toolStatus = .cancelled }
        }
        rebuildToolSnapshotsFromMessages()
        launchRerunAgentLoop(label: "retryFromToolBlock", resumeAt: asstMsgIdx, resumeBlocks: blockIdx)
    }

    /// Fallback ContentPart serialization if buildRawMessage returns nil
    /// (e.g. empty session id). Encodes only text/toolUse/toolResult parts —
    /// imageData parts are dropped (they're re-fetchable and not part of the
    /// truncation invariant). [T-ios-rerun-from-tool-block-position]
    private static func contentPartsFallback(_ parts: [AgentContentPart]) -> [ContentPart] {
        parts.compactMap { p in
            switch p {
            case .text(let t): return .text(t)
            default: return nil
            }
        }
    }

    /// Append text to the chat input, separated by a single space on each side
    /// of an existing draft so quoted snippets stay visually distinct. A
    /// trailing space is always appended so the caret lands after a separator
    /// when the input regains focus. Whitespace-only inserts are dropped.
    ///
    /// Wired up to the `chatInputAppendRequested` notification posted by the
    /// long-press selection menu's "Add to Chat Input" action across markdown
    /// bubbles, table cells, etc.
    func appendToInputText(_ snippet: String) {
        let trimmed = snippet.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let existing = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        if existing.isEmpty {
            inputText = trimmed + " "
        } else {
            inputText = existing + " " + trimmed + " "
        }
    }

    /// Populate the input bar with the content of a user message for editing.
    /// The next call to send() will truncate the conversation from this message
    /// and re-run the agent loop with the edited content.
    func editMessage(_ messageId: UUID) {
        guard !isProcessing else { return }
        guard let idx = messages.firstIndex(where: { $0.id == messageId }),
              messages[idx].role == .user else { return }

        let msg = messages[idx]

        // Strip <user-attached-files> XML from content to get clean user text
        var text = msg.content
        if let start = text.range(of: "<user-attached-files>") {
            let end = text.range(of: "</user-attached-files>")
            let endBound = end?.upperBound ?? text.endIndex
            text = String(text[text.startIndex..<start.lowerBound] + text[endBound...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        inputText = text
        editingMessageIndex = idx

        // Restore attachments: convert AttachmentMeta back to InputAttachment
        // by locating the files in the session uploads directory.
        if !msg.attachments.isEmpty, let sid = sessionId {
            let uploadsDir = Self.minisUploadsDir(for: sid)
            var restored: [InputAttachment] = []
            for meta in msg.attachments {
                let fileName = (meta.path as NSString).lastPathComponent
                let fileURL = uploadsDir.appendingPathComponent(fileName)
                guard FileManager.default.fileExists(atPath: fileURL.path) else { continue }
                // Copy to Caches so the normal send() flow works
                let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("InputAttachments")
                try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
                let cacheURL = cacheDir.appendingPathComponent(fileName)
                try? FileManager.default.removeItem(at: cacheURL)
                try? FileManager.default.copyItem(at: fileURL, to: cacheURL)

                let ext = (fileName as NSString).pathExtension.lowercased()
                let kind: InputAttachment.Kind
                if ["jpg", "jpeg", "png", "gif", "webp", "heic"].contains(ext) {
                    kind = .image
                } else if ["mp4", "mov", "m4v"].contains(ext) {
                    kind = .video
                } else {
                    kind = .document
                }
                restored.append(InputAttachment(fileName: fileName, cacheURL: cacheURL, kind: kind))
            }
            attachments = restored
        }

        scrollToBottomSignal.send()
        logger.info("✏️ editMessage idx=\(idx) text=\(text.count)ch attachments=\(msg.attachments.count)")
    }

    func cancelEdit() {
        editingMessageIndex = nil
        inputText = ""
        attachments = []
        logger.info("✏️ cancelEdit")
    }

    func cancel() {
        let lastBlocks = (messages.last?.role == .assistant) ? messages.last!.blocks.count : -1
        let lastRole = messages.last.map { $0.role == .assistant ? "assistant" : "user" } ?? "nil"
        logger.info("⏹️ cancel() START session=\(self.sessionId ?? "nil") isProcessing=\(self.isProcessing) lastRole=\(lastRole) lastBlocks=\(lastBlocks) currentTask=\(self.currentTask != nil)")
        dumpCandidateState("cancel-START")
        // [T-ios-queued-candidate-not-onscreen] Snapshot the queue + on-screen
        // messages at Stop time, and again 500ms later, so we can see whether a
        // queued candidate that drains after Stop actually lands on screen.
        dumpQueueSnapshot("stop-BEFORE")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.dumpQueueSnapshot("stop-AFTER+500ms")
        }
        isSuspended = false
        if let sid = sessionId {
            SessionConcurrencyManager.shared.cancelWait(sessionId: sid)
        }
        stopCurrentCommand()
        // If the loop is suspended waiting for foreground, resume it so
        // Task cancellation can propagate through the continuation.
        backgroundSuspended = false
        if let c = foregroundContinuation {
            foregroundContinuation = nil
            c.resume()
        }
        // If the loop is suspended for browser takeover, resume it.
        browserTakeoverActive = false
        takeoverTimeoutTask?.cancel()
        takeoverTimeoutTask = nil
        if let c = takeoverContinuation {
            takeoverContinuation = nil
            c.resume()
        }
        if let c = midActionTakeoverContinuation {
            midActionTakeoverContinuation = nil
            c.resume()
        }
        if let obs = foregroundObserver {
            NotificationCenter.default.removeObserver(obs)
            foregroundObserver = nil
        }
        userDidCancel = true
        currentTask?.cancel()
        currentTask = nil
        autoRetryAttempt = 0
        autoRetryCountdown = 0
        if let sid = sessionId {
            logger.info("🔥 cache keep-alive: cancelling on user cancel — session=\(sid.prefix(8))")
            CacheKeepAliveManager.shared.cancelKeepAlive(sessionId: sid)
        }
        // Don't modify blocks here — the cancelled task may still be writing to them.
        // The task's error handler checks userDidCancel to mark as interrupted and
        // set canResume. We set isProcessing = false immediately for responsive UI.
        isProcessing = false
        // Cancel compact task if running — the task's CancellationError handler
        // will update the status message and clean up state.
        if isCompacting {
            compactTask?.cancel()
            compactTask = nil
            // Restore all queued compact-and-send messages back to input
            // (use the last one for inputText since it's the most recent)
            for queued in promptQueue {
                messages.removeAll { $0.queuedPromptId == queued.id }
            }
            if let last = promptQueue.last {
                inputText = last.text
                attachments = last.attachments
            }
            promptQueue.removeAll()
        }
        endBackgroundProcessing()

        // If there are queued prompts remaining, spawn a new task to continue
        // processing them. The old task is cancelled (stops the current agent
        // loop) but the queue should keep draining.
        if !promptQueue.isEmpty && !isCompacting {
            logger.info("⏹️ cancel() — \(promptQueue.count) queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

    /// Resume queue processing after the user stopped the current task.
    /// Spawns a fresh Task (not cancelled) so the drain loop can call runAgentLoop().
    private func resumeQueueAfterCancel() {
        // Small delay to let the cancelled task's error handler finish cleanup.
        currentTask = Task { @MainActor [weak self] in
            guard let self else { return }
            // Wait briefly for the previous task to complete cleanup
            try? await Task.sleep(nanoseconds: 200_000_000) // 200ms
            self.handleUserCancelledCleanup()
            self.userDidCancel = false
            // [T-stop-with-queue-render-desync] handleUserCancelledCleanup (run
            // here or by the cancelled task's own catch) set canResume=true —
            // correct for a plain Stop, but this drain immediately continues the
            // conversation with the queued prompt, so the "Interrupted — tap
            // Resume" banner / ⏸ badge must not stay up over a live run. Clearing
            // it also lets the didSet replay any sync reload it deferred.
            self.canResume = false
            self.isProcessing = true
            self.beginBackgroundProcessing()
            logger.info("📨[DRAIN] Resuming queue in new task — \(self.promptQueue.count) prompt(s)")
            await self.drainQueuedPrompts()

            // [T-stop-with-queue-render-desync] A second Stop during this drained
            // run cancels THIS task and may spawn yet another drain task — same
            // handover as send()'s epilogue, so don't clobber its state either.
            guard !Task.isCancelled else {
                logger.info("📨[DRAIN] queue-resume epilogue skipped (task cancelled) session=\(self.sessionId ?? "nil")")
                return
            }

            if let sid = self.sessionId {
                let dbMessages = await ChatStore.shared.loadMessages(sessionId: sid)
                self.lastKnownDbSortOrder = dbMessages.last?.sortOrder ?? self.lastKnownDbSortOrder
                self.lastKnownDbCount = dbMessages.count
                // T-baseline-hash-drift: see send DONE path for why hash
                // must be refreshed alongside count/sortOrder.
                self.lastKnownDbOrderHash = Self.computeOrderHash(of: dbMessages)
            }
            self.isProcessing = false
            self.endBackgroundProcessing()
        }
    }

    /// Drain any queued prompts after an agent loop finishes.
    /// Called from send(), retry(), resume(), and retryFromMessage() before setting isProcessing=false.
    /// Uses a while loop + final yield to catch prompts queued during the last streaming response.
    /// Reentrancy-guarded entry point. Only ONE drain may be active at a time:
    /// a Stop with queued prompts spawns a fresh resume Task whose drain races
    /// the superseded original Task's post-loop drain, and without this guard
    /// both would run the queued turn and double-fire the next request (every
    /// tool call + reply twice). The guard is set/cleared only on @MainActor so
    /// check-and-set is atomic and held across the inner `await runAgentLoop()`.
    /// [T-ios-concurrent-stop-double-request]
    /// `internal` (not `private`) so `compactAndSend` in the +Compaction
    /// extension file can drain through this same path after a compact.
    func drainQueuedPrompts() async {
        guard !self.isDrainingQueue else {
            logger.info("📨[DRAIN] Already draining in another task — skipping duplicate drain")
            return
        }
        self.isDrainingQueue = true
        defer { self.isDrainingQueue = false }
        await drainQueuedPromptsInner()
    }

    /// The actual drain loop. Unguarded so its own tail-recursion for late
    /// arrivals (below) doesn't trip the reentrancy guard — that guard only
    /// gates cross-Task duplicate entry, which all goes through the wrapper.
    private func drainQueuedPromptsInner() async {
        while !self.userDidCancel && !self.promptQueue.isEmpty {
            // If the Task itself is cancelled (e.g. user tapped stop), don't
            // consume queue items — resumeQueueAfterCancel() handles them
            // in a fresh Task.
            guard !Task.isCancelled else {
                logger.info("📨[DRAIN] Task cancelled — leaving \(self.promptQueue.count) prompt(s) in queue")
                break
            }
            let queued = self.promptQueue
            self.promptQueue.removeAll()
            logger.info("📨[DRAIN] Draining \(queued.count) queued prompt(s)")

            let queuedIds = Set(queued.map(\.id))
            for msg in self.messages where msg.isQueued {
                if let pid = msg.queuedPromptId, queuedIds.contains(pid) {
                    msg.isQueued = false
                }
            }
            self.scrollToBottomSignal.send()

            let sid = self.sessionId ?? "unknown"
            let uploadsDir = Self.minisUploadsDir(for: sid)
            let isoFmt = ISO8601DateFormatter()
            isoFmt.formatOptions = [.withInternetDateTime]
            let nowStr = isoFmt.string(from: Date())
            try? FileManager.default.createDirectory(at: uploadsDir, withIntermediateDirectories: true)

            var combinedParts: [AgentContentPart] = []
            for prompt in queued {
                if !prompt.attachments.isEmpty {
                    let (attParts, attMetas) = self.processAttachments(prompt.attachments, uploadsDir: uploadsDir, nowStr: nowStr)
                    combinedParts.append(contentsOf: attParts)
                    Self.cleanupAttachmentFiles(prompt.attachments)
                    if let chatMsg = self.messages.first(where: { $0.queuedPromptId == prompt.id }) {
                        chatMsg.attachments = attMetas
                        chatMsg.inputAttachments = []
                    }
                }
                if !prompt.text.isEmpty {
                    combinedParts.append(.text(prompt.text))
                }
            }

            let queueAgentMsg = AgentMessage(role: .user, parts: combinedParts)
            let qIdx = self.agentHistory.count
            self.agentHistory.append(queueAgentMsg)
            if let pid = await self.persistAgentMessage(queueAgentMsg), qIdx < self.agentHistory.count {
                self.agentHistory[qIdx].dbMessageId = pid
            }

            do {
                try await self.runAgentLoop()
            } catch is CancellationError {
                logger.info("Agent loop (queued-drain) cancelled")
                self.handleUserCancelledCleanup()
                // Don't consume remaining queue items — resumeQueueAfterCancel()
                // will pick them up in a fresh Task.
                break
            } catch {
                let rawDesc = String(describing: error)
                logger.error("Agent loop (queued-drain) error: \(rawDesc)")
                if self.userDidCancel {
                    self.handleUserCancelledCleanup()
                } else {
                    let displayDesc = Self.friendlyErrorMessage((error as? LocalizedError)?.errorDescription ?? rawDesc)
                    // [T-stream-empty-banner-trace] Diagnostic: log the
                    // messages-tail state when an agent-loop error
                    // surfaces so we can tell whether the placeholder
                    // assistant message survived the failure (bubble
                    // path) or got pruned beforehand (banner path).
                    // Catalyst users have reported the loading
                    // indicator flashing once and the error red banner
                    // appearing at the top of the chat with no
                    // placeholder bubble — that's the "else" branch
                    // below and it shouldn't happen if runAgentLoop's
                    // line 5124 placeholder is still in `messages`.
                    let lastRoleStr = self.messages.last.map { "\($0.role)" } ?? "nil"
                    let lastBlocks = self.messages.last?.blocks.count ?? -1
                    let lastErr = self.messages.last?.error ?? "nil"
                    let tail3 = self.messages.suffix(3).map { "\($0.role)(\($0.blocks.count)blk err=\($0.error ?? "nil"))" }.joined(separator: " > ")
                    AppLogger(category: "StreamError").error("[StreamError] catch displayDesc=\"\(displayDesc.prefix(120))\" msgCount=\(self.messages.count) lastRole=\(lastRoleStr) lastBlocks=\(lastBlocks) lastErr=\(lastErr) tail=\(tail3)")
                    // [T-ios-retry-tool-blocks-invisible / T-stream-empty-banner-trace]
                    // Resolve the streaming assistant by the last ASSISTANT row,
                    // not messages.last. A queued .user message (enqueuePrompt
                    // appends one mid-tool) sits AFTER the candidate, so
                    // messages.last would be that user row — the error then fell
                    // through to the top red banner (vm.errorMessage) instead of
                    // attaching to the assistant bubble. Same root cause as the
                    // Stop-loses-content fix.
                    if let last = self.messages.last(where: { $0.role == .assistant }) {
                        last.error = displayDesc
                        Task { await self.persistErrorInfo(displayDesc) }  // [T-error-persist-ios]
                        AppLogger(category: "StreamError").error("[StreamError] → bubble path (set last.error)")
                    } else {
                        self.errorMessage = displayDesc
                        AppLogger(category: "StreamError").error("[StreamError] → banner path (vm.errorMessage set)")
                    }
                }
                break
            }
        }

        // Yield once to let any last-moment enqueuePrompt() calls execute
        await Task.yield()
        if !Task.isCancelled && !self.userDidCancel && !self.promptQueue.isEmpty {
            logger.info("📨[DRAIN] Late arrivals after yield: \(self.promptQueue.count)")
            // Recurse on the INNER worker (we already hold the reentrancy guard)
            // so late-arrival handling isn't skipped by our own guard.
            await drainQueuedPromptsInner()
        }
    }

    /// [T-ios-queued-message-continues-prev-turn] Drain any prompts the user
    /// queued mid-run and append them as ONE standalone user turn, then start a
    /// fresh assistant message so the next loop iteration produces a dedicated
    /// response. Called ONLY from the agent loop's finish branch (after the
    /// current turn's tool loop has fully converged to a no-tool-use assistant
    /// reply), so the queued prompt never gets folded into a mid-loop tool_result
    /// (which made the model treat it as in-loop context for the previous turn).
    ///
    /// Returns true if a prompt was injected (caller should `continue` the loop
    /// instead of breaking). `msgIdx` / `committedBlockCount` are advanced to the
    /// new assistant message. The caller must have already persisted the just-
    /// finished assistant reply before calling this.
    private func injectQueuedPromptsAsNewTurn(
        msgIdx: inout Int,
        committedBlockCount: inout Int,
        turnUsage: TokenUsage
    ) async -> Bool {
        guard !promptQueue.isEmpty else { return false }
        let queued = promptQueue
        promptQueue.removeAll()
        // Mark corresponding chat messages as no longer queued (remove dashed border + X)
        let queuedIds = Set(queued.map(\.id))
        for msg in messages where msg.isQueued {
            if let pid = msg.queuedPromptId, queuedIds.contains(pid) {
                msg.isQueued = false
            }
        }

        // Build combined parts from all queued prompts (text + attachments)
        let qSid = sessionId ?? "unknown"
        let qUploadsDir = Self.minisUploadsDir(for: qSid)
        let qIsoFormatter = ISO8601DateFormatter()
        qIsoFormatter.formatOptions = [.withInternetDateTime]
        let qNowStr = qIsoFormatter.string(from: Date())
        try? FileManager.default.createDirectory(at: qUploadsDir, withIntermediateDirectories: true)

        var qCombinedParts: [AgentContentPart] = []
        for prompt in queued {
            if !prompt.attachments.isEmpty {
                let (attParts, attMetas) = processAttachments(prompt.attachments, uploadsDir: qUploadsDir, nowStr: qNowStr)
                qCombinedParts.append(contentsOf: attParts)
                Self.cleanupAttachmentFiles(prompt.attachments)
                // Update the corresponding ChatMessage so attachment thumbnails appear in the UI
                if let chatMsg = messages.first(where: { $0.queuedPromptId == prompt.id }) {
                    chatMsg.attachments = attMetas
                    chatMsg.inputAttachments = []
                }
            }
            if !prompt.text.isEmpty {
                qCombinedParts.append(.text(prompt.text))
            }
        }

        // Guard: if every queued prompt was empty (no text, no attachments) the
        // combined parts are empty — an empty user message is a 400. Skip.
        guard !qCombinedParts.isEmpty else {
            logger.warning("injectQueuedPromptsAsNewTurn: \(queued.count) queued prompt(s) produced no content, skipping")
            return false
        }

        // [T-ios-queued-message-interrupt-on-toolclose] When we interrupt mid-tool-
        // loop (right after a tool call closes), the agentHistory tail is
        // assistant(tool_use) → user(tool_result). Appending the queued user
        // message directly would make two consecutive .user entries that
        // mergeConsecutiveSameRole folds into one (tool_result + queued text) —
        // exactly the bug where the model reads the queued prompt as in-loop
        // context instead of a new turn (#579). Insert a minimal assistant bridge
        // so the sequence is …user(tool_result) → assistant(interrupted) →
        // user(queued) → assistant(responds). Only needed when the tail is .user;
        // in the no-tool finish-branch the tail is already an assistant reply.
        if agentHistory.last?.role == .user {
            // Text MUST stay byte-identical to RawMessage.internalBridgeText —
            // that constant is how the session-reload path recognizes this row
            // as internal and keeps it out of the visible chat.
            // [T-bridge-message-ui-leak]
            let bridge = AgentMessage(role: .assistant, parts: [.text(RawMessage.internalBridgeText)])
            let bridgeIdx = agentHistory.count
            agentHistory.append(bridge)
            if let pid = await persistAgentMessage(bridge, snapshots: [:]), bridgeIdx < agentHistory.count {
                agentHistory[bridgeIdx].dbMessageId = pid
            }
        }

        let queueMsg = AgentMessage(role: .user, parts: qCombinedParts)
        let queueIdx = agentHistory.count
        agentHistory.append(queueMsg)
        if let pid = await persistAgentMessage(queueMsg, snapshots: [:]), queueIdx < agentHistory.count {
            agentHistory[queueIdx].dbMessageId = pid
        }
        // Cache markdown on the completed assistant message before starting a new one
        if msgIdx < messages.count {
            for i in messages[msgIdx].blocks.indices {
                if case .text = messages[msgIdx].blocks[i].kind, !messages[msgIdx].blocks[i].content.isEmpty {
                    messages[msgIdx].blocks[i].cachedMarkdown = MarkdownContent(prepareMarkdownForRender(messages[msgIdx].blocks[i].content))
                    cacheAttributedString(for: messages[msgIdx].blocks[i])
                }
            }
            // Clear "thinking" indicator + store usage on the just-finished assistant message
            messages[msgIdx].isAwaitingModelResponse = false
            messages[msgIdx].usage = turnUsage
        }
        // Start a new assistant message so the next response appears below the queued user message
        messages.append(ChatMessage(role: .assistant, content: "", blocks: []))
        msgIdx = messages.count - 1
        committedBlockCount = 0
        self.committedBlockCount = 0
        scrollToBottomSignal.send()
        logger.info("Injected \(queued.count) queued prompt(s) as new turn after tool loop, new msgIdx=\(msgIdx)")
        return true
    }

    /// Handle post-cancellation cleanup on the assistant message.
    /// Called from task error handlers after the agent loop has stopped writing to blocks.
    /// Preserves visible content after user cancel and enables Resume.
    ///
    /// Case 1 (tool execution cancel): The tool loop already committed blocks and
    ///   tool_result to agentHistory — just mark non-terminal tool blocks as .cancelled.
    /// Case 2 (text/parameter streaming cancel): Preserve partial text, remove incomplete
    ///   tool blocks, commit to agentHistory, and inject a "Continue" user message.
    /// [T-ios-retry-orphan-toolresult] Compact dump of the agentHistory
    /// tool_use ↔ tool_result pairing. One line per entry showing role + the
    /// tool_use ids it issues (TU:) and tool_result ids it answers (TR:), then a
    /// summary flagging every orphan: a TR whose TU is absent, or a TU with no
    /// answering TR. Grep `[PairDiag]`. Used around retryFromToolBlock's
    /// sub-message cut to see whether the cut leaves a tool_result stranded
    /// (which runAgentLoop's orphan-removal pass then has to delete, and which
    /// is the suspected source of the "AI discusses douban but the user prompt
    /// is gone" state).
    private func dumpHistoryPairing(_ tag: String) {
        var tuIds = Set<String>()
        var trIds = Set<String>()
        var lines: [String] = []
        for (i, msg) in agentHistory.enumerated() {
            var tus: [String] = []
            var trs: [String] = []
            for part in msg.parts {
                if case .toolUse(let id, _, _) = part { tus.append(String(id.prefix(8))); tuIds.insert(id) }
                if case .toolResult(let id, _, _, _, _, _, _, _) = part { trs.append(String(id.prefix(8))); trIds.insert(id) }
            }
            var hasText = false
            for part in msg.parts { if case .text(let t) = part, !t.isEmpty { hasText = true } }
            let tuStr = tus.isEmpty ? "" : " TU:[\(tus.joined(separator: ","))]"
            let trStr = trs.isEmpty ? "" : " TR:[\(trs.joined(separator: ","))]"
            let textStr = hasText ? " text" : ""
            lines.append("  [\(i)] \(msg.role.rawValue)\(textStr)\(tuStr)\(trStr)")
        }
        // Orphan detection (full-id compare).
        var orphanTR: [String] = []
        var orphanTU: [String] = []
        for (_, msg) in agentHistory.enumerated() {
            for part in msg.parts {
                if case .toolResult(let id, _, _, _, _, _, _, _) = part, !tuIds.contains(id) {
                    orphanTR.append(String(id.prefix(8)))
                }
                if case .toolUse(let id, _, _) = part, !trIds.contains(id) {
                    orphanTU.append(String(id.prefix(8)))
                }
            }
        }
        let verdict = (orphanTR.isEmpty && orphanTU.isEmpty)
            ? "PAIRING-OK"
            : "ORPHANS orphanTR=[\(orphanTR.joined(separator: ","))] orphanTU=[\(orphanTU.joined(separator: ","))]"
        AppLogger(category: "PairDiag").info("[PairDiag] \(tag) count=\(self.agentHistory.count) \(verdict)\n\(lines.joined(separator: "\n"))")
    }

    /// [T-ios-queued-candidate-not-onscreen] Snapshot the prompt queue
    /// (`promptQueue`, the pending queued sends) and the on-screen message array
    /// (`messages`) — last 20 of each as compact summaries — to trace the
    /// "queued candidate executed but never rendered" bug. Grep `[QueueDiag]`.
    func dumpQueueSnapshot(_ tag: String) {
        // On-screen message array (last 20).
        let msgTail = messages.suffix(20)
        let msgStartIdx = messages.count - msgTail.count
        var msgLines: [String] = []
        for (offset, m) in msgTail.enumerated() {
            let idx = msgStartIdx + offset
            let roleStr: String = {
                switch m.role {
                case .user: return "user"
                case .assistant: return "asst"
                case .compactDivider: return "divider"
                case .systemInfo: return "sysinfo"
                }
            }()
            let queuedMark = m.isQueued ? " QUEUED(\(m.queuedPromptId?.uuidString.prefix(8) ?? "?"))" : ""
            let text = m.content.isEmpty
                ? (m.blocks.isEmpty ? "" : m.blocks.compactMap { $0.content.isEmpty ? nil : $0.content }.first ?? "")
                : m.content
            let preview = text.replacingOccurrences(of: "\n", with: "⏎").prefix(50)
            msgLines.append("  msg[\(idx)] \(roleStr) blocks=\(m.blocks.count)\(queuedMark) id=\(m.id.uuidString.prefix(8)) \"\(preview)\"")
        }
        // Prompt queue (last 20).
        let queueTail = promptQueue.suffix(20)
        var queueLines: [String] = []
        for p in queueTail {
            let preview = p.text.replacingOccurrences(of: "\n", with: "⏎").prefix(50)
            queueLines.append("  q[\(p.id.uuidString.prefix(8))] att=\(p.attachments.count) \"\(preview)\"")
        }
        logger.info("📋[QueueDiag] \(tag) — promptQueue=\(self.promptQueue.count) messages=\(self.messages.count) isProcessing=\(self.isProcessing)\nPROMPT-QUEUE (last \(queueTail.count)):\n\(queueLines.joined(separator: "\n"))\nMESSAGES (last \(msgTail.count)):\n\(msgLines.joined(separator: "\n"))")
    }

    /// [T-ios-stop-candidate-message-lost] One-line-per-block dump of the
    /// candidate (last assistant) message + the committed-count cursors. Use
    /// `xcrun simctl spawn booted log stream | grep StopDiag` (or read the
    /// daily LoggingManager file) to trace which blocks survive a Stop.
    private func dumpCandidateState(_ tag: String) {
        let so = self.committedBlockCount
        let pso = self.prevCommittedBlockCount
        let histLast = agentHistory.last.map { "\($0.role.rawValue)[\($0.parts.count)p]" } ?? "nil"
        // Resolve the candidate by the last ASSISTANT row (a queued .user
        // message can sit after it), not messages.last.
        guard let candidateIdx = messages.lastIndex(where: { $0.role == .assistant }) else {
            logger.info("⏹️[StopDiag] \(tag) — no assistant message at all (messagesLast=\(self.messages.last?.role == .user ? "user" : "nil")) committed=\(so) prevCommitted=\(pso) histLast=\(histLast) canResume=\(self.canResume)")
            return
        }
        let last = messages[candidateIdx]
        let trailingQueued = candidateIdx < messages.count - 1
        logger.info("⏹️[StopDiag] \(tag) — candidate idx=\(candidateIdx) trailingQueued=\(trailingQueued) blocks=\(last.blocks.count) committed=\(so) prevCommitted=\(pso) histLast=\(histLast) canResume=\(self.canResume) msgId=\(last.id.uuidString.prefix(8))")
        for (i, b) in last.blocks.enumerated() {
            let kindStr: String = {
                switch b.kind {
                case .text: return "text"
                case .thinking: return "thinking"
                case .shellTool: return "shellTool"
                case .fileReadTool: return "fileRead"
                case .fileWriteTool: return "fileWrite"
                case .fileEditTool: return "fileEdit"
                case .browserTool: return "browser"
                case .readImageTool: return "readImage"
                case .memoryTool: return "memory"
                case .info: return "info"
                }
            }()
            let statusStr: String = {
                guard let s = b.toolStatus else { return "-" }
                switch s {
                case .streaming: return "streaming"
                case .running: return "running"
                case .cancelled: return "cancelled"
                default: return "\(s)"
                }
            }()
            let committedMark = i < so ? "C" : "·"  // C = already committed to agentHistory
            let preview = b.content.replacingOccurrences(of: "\n", with: "⏎").prefix(40)
            logger.info("⏹️[StopDiag]   [\(committedMark)] block[\(i)] kind=\(kindStr) status=\(statusStr) len=\(b.content.count) tuId=\(b.toolUseId?.prefix(8) ?? "-") \"\(preview)\"")
        }
    }

    private func playCompletionHaptic() {
        UIImpactFeedbackGenerator(style: .soft).impactOccurred()
    }

    private func handleUserCancelledCleanup() {
        let blockCount = (messages.last?.role == .assistant) ? messages.last!.blocks.count : -1
        let hasContent = (messages.last?.role == .assistant) ? messages.last!.blocks.contains(where: { !$0.content.isEmpty }) : false
        let lastRole = messages.last.map { $0.role == .assistant ? "assistant" : "user" } ?? "nil"
        let historyLastRole = agentHistory.last?.role.rawValue ?? "nil"
        logger.info("⏹️ handleUserCancelledCleanup userDidCancel=\(self.userDidCancel) lastRole=\(lastRole) blocks=\(blockCount) hasContent=\(hasContent) canResume=\(self.canResume) historyLast=\(historyLastRole)")
        dumpCandidateState("cleanup-ENTER")
        if userDidCancel {
            userDidCancel = false
            // [T-ios-retry-tool-blocks-invisible] The candidate (streaming)
            // assistant message is NOT necessarily messages.last. If the user
            // types + sends while tools are running, enqueuePrompt() appends a
            // queued `.user` ChatMessage AFTER the candidate, so `messages.last`
            // is that user row. The old `guard messages.last == .assistant`
            // then bailed out entirely — the candidate's streamed text + tool
            // blocks were never committed to agentHistory and committedBlockCount
            // never advanced, so on Stop the just-generated answer vanished from
            // the list (it only survived in the preview bar, which flatMaps
            // vm.messages live). Resolve the candidate by the last *assistant*
            // row and operate on its index, mirroring the collection view's
            // `subscribeToLastMessage` (which already uses last(where: assistant)).
            guard let candidateIdx = messages.lastIndex(where: { $0.role == .assistant }) else { return }
            let last = messages[candidateIdx]

            // Case 1: Cancel during tool execution — the tool loop already committed
            // blocks and appended tool_result to agentHistory.
            let lastHistoryIsToolResult = agentHistory.last?.role == .user
                && agentHistory.last?.parts.contains(where: { if case .toolResult = $0 { return true }; return false }) == true
            logger.info("⏹️[StopDiag] branch-decision lastHistoryIsToolResult=\(lastHistoryIsToolResult) → \(lastHistoryIsToolResult ? "Case 1 (tool cancel)" : "Case 2 (text/param cancel)")")
            if lastHistoryIsToolResult {
                for block in last.blocks {
                    if let status = block.toolStatus {
                        switch status {
                        case .streaming, .running:
                            block.toolStatus = .cancelled
                        default: break
                        }
                    }
                }

                // [T-ios-stop-candidate-message-lost] Merged rendering accumulates
                // every agentHistory iteration into ONE UI message (messages[msgIdx]).
                // After a tool_result lands, the NEXT iteration can stream fresh text
                // blocks into this same message before the user taps Stop — those
                // blocks live past `committedBlockCount` and have NOT been committed
                // to agentHistory yet (the iteration's commit at the end of the loop
                // never ran). Without this flush, Case 1 returned immediately and that
                // post-tool text was dropped from history; since `committedBlockCount`
                // stayed behind it, a later resume/persist would also trim it from the
                // UI — i.e. the just-generated answer vanished on Stop. Commit the
                // uncommitted tail (text only — the cancelled tool blocks above are not
                // re-issued) so it's both kept on screen and written to history.
                let toolCancelStart = min(committedBlockCount, last.blocks.count)
                logger.info("⏹️[StopDiag] Case1 flush-scan range=[\(toolCancelStart)..<\(last.blocks.count)] (committed=\(self.committedBlockCount))")
                if toolCancelStart < last.blocks.count {
                    var tailTextParts: [AgentContentPart] = []
                    for i in toolCancelStart..<last.blocks.count {
                        let block = last.blocks[i]
                        if case .text = block.kind, !block.content.isEmpty {
                            tailTextParts.append(.text(block.content))
                            logger.info("⏹️[StopDiag]   Case1 flush-include block[\(i)] len=\(block.content.count)")
                        } else {
                            logger.info("⏹️[StopDiag]   Case1 flush-skip block[\(i)] (not non-empty text)")
                        }
                    }
                    if !tailTextParts.isEmpty {
                        tailTextParts.append(.text("<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"))
                        let assistantMsg = AgentMessage(role: .assistant, parts: tailTextParts)
                        let asstIdx = agentHistory.count
                        agentHistory.append(assistantMsg)
                        Task { @MainActor [weak self] in
                            guard let self else { return }
                            if let pid = await self.persistAgentMessage(assistantMsg), asstIdx < self.agentHistory.count {
                                self.agentHistory[asstIdx].dbMessageId = pid
                            }
                        }
                        // Advance committed counts so the kept blocks survive a later
                        // resume's `removeSubrange(committedBlockCount...)` trim.
                        self.prevCommittedBlockCount = self.committedBlockCount
                        self.committedBlockCount = last.blocks.count
                        logger.info("⏹️ Case 1: flushed \(tailTextParts.count) post-tool text part(s) to agentHistory before stopping")
                    }
                }

                canResume = true
                logger.info("⏹️ Case 1 (tool cancel): preserved \(last.blocks.count) blocks, canResume=true")
                dumpCandidateState("cleanup-EXIT-case1")
                return
            }

            // Case 0 (T-stop-clear-thinking-and-partial-ios): Stop fired in
            // the pre-first-chunk thinking gap — the candidate has no real
            // content, no committed prior iteration, and history does not
            // end with a tool_result (would mean Case 1 owns it). The
            // placeholder ChatMessage runAgentLoop pushed would otherwise
            // render as a bare "Minis" header bubble with the typing
            // indicator hosted on it. Drop the placeholder so the UI snaps
            // back to idle. Boundary against #566/#569 8bb0bf83: a candidate
            // with any non-empty text block or any tool_use block is kept
            // (drops through to Case 2 below); only a thinking-only / 0-block
            // placeholder is removed here. The placeholder is not persisted
            // by runAgentLoop entry — agentHistory is written only when an
            // iteration commits — so removing from messages alone is enough;
            // no DB rollback needed.
            let hasNonEmptyText = last.blocks.contains { block in
                if case .text = block.kind, !block.content.isEmpty { return true }
                return false
            }
            let hasAnyToolUse = last.blocks.contains { $0.toolStatus != nil }
            if !hasNonEmptyText, !hasAnyToolUse, prevCommittedBlockCount == 0 {
                logger.info("⏹️[StopDiag] Case0 REMOVING thinking-only placeholder at idx=\(candidateIdx) blocks=\(last.blocks.count) (no text, no tool_use, prevCommitted=0)")
                messages.remove(at: candidateIdx)
                dumpCandidateState("cleanup-EXIT-case0")
                return
            }

            // Case 2: Cancel during text or parameter streaming.
            // Remove incomplete tool blocks (parameters still streaming).
            last.blocks.removeAll { block in
                if case .streaming = block.toolStatus { return true }
                return false
            }

            // A SSE chunk can flip a block from .streaming to .running between
            // the user's tap and this cleanup running. Any .running block left
            // here has no tool_result yet (Case 1 already returned) — mark it
            // cancelled so the shimmer overlay stops, regardless of whether
            // we keep or drop the surrounding blocks below.
            for block in last.blocks {
                if case .running = block.toolStatus {
                    block.toolStatus = .cancelled
                }
            }

            let hasVisibleContent = last.blocks.contains(where: { !$0.content.isEmpty })

            logger.info("⏹️[StopDiag] Case2 hasVisibleContent=\(hasVisibleContent) prevCommitted=\(self.prevCommittedBlockCount) committed=\(self.committedBlockCount) blocks=\(last.blocks.count)")
            if hasVisibleContent {
                // Collect text from current iteration's blocks
                var textParts: [AgentContentPart] = []
                let safeStart = min(prevCommittedBlockCount, last.blocks.count)
                for i in safeStart..<last.blocks.count {
                    let block = last.blocks[i]
                    if case .text = block.kind, !block.content.isEmpty {
                        textParts.append(.text(block.content))
                    }
                }

                // Commit partial text to agentHistory with a truncation marker
                // so the model knows the response was interrupted.
                let histLastIsAsst = agentHistory.last?.role == .assistant
                logger.info("⏹️[StopDiag] Case2 collect textParts=\(textParts.count) safeStart=\(safeStart) histLastIsAsst=\(histLastIsAsst) → \(!textParts.isEmpty && !histLastIsAsst ? "WILL COMMIT" : "SKIP commit")")
                if !textParts.isEmpty, agentHistory.last?.role != .assistant {
                    textParts.append(.text("<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"))
                    let assistantMsg = AgentMessage(role: .assistant, parts: textParts)
                    let asstIdx = agentHistory.count
                    agentHistory.append(assistantMsg)
                    Task { @MainActor [weak self] in
                        guard let self else { return }
                        if let pid = await self.persistAgentMessage(assistantMsg), asstIdx < self.agentHistory.count {
                            self.agentHistory[asstIdx].dbMessageId = pid
                        }
                    }
                    logger.info("⏹️ Case 2: committed partial text (\(textParts.count) parts) with truncation marker to agentHistory")
                }

                // Update committed counts to preserve current blocks
                self.prevCommittedBlockCount = self.committedBlockCount
                self.committedBlockCount = last.blocks.count

                canResume = true
                logger.info("⏹️ Case 2 (text cancel): preserved \(last.blocks.count) blocks, canResume=true")
            } else if prevCommittedBlockCount > 0 {
                // No visible content in current iteration but prior iterations exist.
                logger.info("⏹️[StopDiag] Case2-branchB no-visible-content, prevCommitted=\(self.prevCommittedBlockCount)>0 → trim blocks[\(self.prevCommittedBlockCount)...], drop trailing asst history if present")
                if prevCommittedBlockCount < last.blocks.count {
                    last.blocks.removeSubrange(prevCommittedBlockCount...)
                }
                committedBlockCount = prevCommittedBlockCount
                if agentHistory.last?.role == .assistant {
                    agentHistory.removeLast()
                }
                canResume = true
                logger.info("⏹️ No visible content but prior iterations exist — canResume=true")
            } else {
                logger.warning("⏹️[StopDiag] Case2-branchC REMOVING empty candidate message at idx=\(candidateIdx) (no visible content, prevCommitted=0)")
                // Remove the candidate by index — NOT removeLast() — because a
                // queued `.user` message may sit after it (enqueued mid-tool).
                messages.remove(at: candidateIdx)
                if agentHistory.last?.role == .assistant {
                    agentHistory.removeLast()
                }
                logger.info("⏹️ Removed empty assistant message")
            }
            dumpCandidateState("cleanup-EXIT")
        } else {
            // Non-user cancellation (e.g. concurrency gate) — just remove empty message
            if let last = messages.last, last.role == .assistant,
               !last.blocks.contains(where: { !$0.content.isEmpty }) {
                messages.removeLast()
            }
        }
    }

    // MARK: - Session Persistence

    /// Load an existing session's messages from the database and rebuild UI + agent history.
    ///
    /// The DB stores one RawMessage per API turn: user, assistant, user(tool-results), assistant, ...
    /// Reload messages from DB after iCloud sync brings new data.
    /// Only reloads if there are new messages (avoids disrupting active sessions).
    /// Tracks the highest DB sortOrder seen after each load, so iCloud sync reload
    /// only triggers when genuinely new messages arrive from another device.
    var lastKnownDbSortOrder: Int = 0
    /// Tracks the DB message count so we can detect remote deletions (retry propagation).
    var lastKnownDbCount: Int = 0
    /// Hash of the (id, sort_order) pairs from the last DB load. Catches a
    /// silent reorder by another sync path (HEAL on LWW skip) where neither
    /// the count nor the max sort_order changed.
    var lastKnownDbOrderHash: Int = 0
    /// Number of assistant turns in `agentHistory` at the moment we LAST marked
    /// this session unread. `endBackgroundProcessing()` is called from ~15 sites
    /// (normal completions, kernel-failure early-returns, cancel cleanup, the
    /// bgTask-expiration handler, resumeQueueAfterCancel, …), and it used to
    /// push `.unread` unconditionally on every one of them. That made a late or
    /// duplicate call — e.g. a background keep-alive bgTask expiring after the
    /// user already read and left the session — re-add the red dot with NO new
    /// content. We now only re-badge when the assistant-turn count has actually
    /// grown since the last badge, i.e. this run genuinely produced a new
    /// completion. [T-ios-unread-dot-reappears]
    var lastBadgedAssistantTurnCount: Int = -1
    // MARK: - Agent Loop

    /// Clear ONLY the uncommitted streaming tail of an assistant message after a
    /// mid-stream failure, preserving every block already committed+persisted in
    /// an earlier round of the same loop. [T-ios-stream-retry-text-disappear]
    ///
    /// Blocks at index `[0, committedBlockCount)` are the committed prefix (text
    /// and terminal tool results from prior rounds, already in the DB) and are
    /// kept verbatim. From `committedBlockCount` onward we drop the in-flight
    /// failed stream: text blocks (partial output that the retry will re-stream)
    /// and tool blocks stuck in `.streaming` / `.running` (which would otherwise
    /// stay orphaned in the UI, never reaching a terminal state). A completed
    /// tool block in the tail (rare) is left alone so its result still shows.
    @MainActor
    static func clearUncommittedStreamTail(_ message: ChatMessage, committedBlockCount: Int) {
        let lowerBound = max(0, min(committedBlockCount, message.blocks.count))
        guard lowerBound < message.blocks.count else { return }
        var kept = Array(message.blocks[0..<lowerBound])
        for block in message.blocks[lowerBound...] {
            if block.kind == .text { continue }
            // [T-dup-thinking-block] Drop uncommitted thinking blocks — not persisted, retry stream will regenerate them.
            if block.kind == .thinking { continue }
            if case .streaming = block.toolStatus { continue }
            if case .running = block.toolStatus { continue }
            kept.append(block)
        }
        message.blocks = kept
    }

    private func runAgentLoop(resumingAt existingMsgIdx: Int? = nil, committedBlocks: Int? = nil) async throws {
        // REPRO-DIAG(2026-05-16): bump global round counter and emit a clear
        // BEGIN/END marker so the user can grep `ROUND \d+` to slice the log
        // by attempt. A "round" = one runAgentLoop invocation, which is
        // triggered by a new user message send or a retry.
        let _diagRound = Self.bumpDiagRound()
        AppLogger(category: "RoundMarker").warning("══════════════ ROUND BEGIN \(_diagRound) ══════════════ vm=\(self.vmInstanceId) session=\(self.sessionId ?? "nil") history=\(self.agentHistory.count) resuming=\(existingMsgIdx != nil)")
        defer { AppLogger(category: "RoundMarker").warning("══════════════ ROUND END \(_diagRound) ════════════════ vm=\(self.vmInstanceId) session=\(self.sessionId ?? "nil") history=\(self.agentHistory.count) estimated ~\(self.estimateContextTokens()) tokens") }
        logger.info("🔄SESSION [vm=\(self.vmInstanceId)] runAgentLoop START session=\(self.sessionId ?? "nil") history=\(self.agentHistory.count) resuming=\(existingMsgIdx != nil)")
        defer { logger.info("🔄SESSION [vm=\(self.vmInstanceId)] runAgentLoop END session=\(self.sessionId ?? "nil") history=\(self.agentHistory.count) estimated ~\(self.estimateContextTokens()) tokens") }

        let loopSetupStart = CFAbsoluteTimeGetCurrent()

        // [T-ios-empty-after-toolresult-reminder] Fresh turn → allow one reminder retry.
        didInjectEmptyToolReminderThisRun = false

        // Resolve provider from ProviderConfigStore
        guard let entry = resolveCurrentEntry() else {
            let errMsg = ChatMessage(role: .assistant, content: "", blocks: [])
            errMsg.error = String(localized: "No model configured. Add a provider in Settings.")
            messages.append(errMsg)
            return
        }
        var provider = await makeAgentProvider(for: entry)

        // Set extended cache TTL globally for Anthropic request patching
        RequestBodyPatcher.setExtendedCacheTTL(self.enhancedCacheEnabled)

        // Restore persisted thought signatures for Gemini session resume
        if let geminiProvider = provider as? GeminiAgentProvider, !pendingThoughtSignatures.isEmpty {
            geminiProvider.restoreToolCallMetadata(pendingThoughtSignatures)
            pendingThoughtSignatures = [:]
        }

        var activeGroupId: String?  // non-nil when using a group (enables fallback)
        var activeEntryId: String? = entry.id

        // Check if this entry came from a group binding
        if let sid = sessionId,
           let binding = ProviderConfigStore.shared.binding(for: sid),
           case .group(let gid, _) = binding.primarySource {
            activeGroupId = gid
        }

        // When resolved via cachedSessionModelId (no binding at all — e.g. an
        // iCloud-synced session whose binding row didn't sync), the entry may
        // still belong to the default group. Enable fallback by discovering
        // the group. Deliberately NOT applied when a .directEntry binding
        // exists: that is an explicit user pin, and enabling group fallback
        // would both switch models behind the user's back and overwrite the
        // pin with a group binding on fallback success.
        if activeGroupId == nil,
           sessionId.flatMap({ ProviderConfigStore.shared.binding(for: $0) }) == nil {
            let store = ProviderConfigStore.shared
            if let gid = store.defaultPrimaryGroupId,
               let group = store.group(for: gid),
               group.memberEntryIds.contains(entry.id) {
                activeGroupId = gid
            }
        }
        let tools = makeAgentTools()

        var userSystemPrompt = baseSystemPrompt
        let activeModel = ProviderConfigStore.shared.entry(for: entry.id)?.model ?? selectedModel
        if let capFragment = activeModel.capabilityPromptFragment {
            userSystemPrompt += "\n\n" + capFragment
        }
        if let behaviorFragment = activeModel.agentBehaviorPromptFragment {
            userSystemPrompt += "\n\n" + behaviorFragment
        }

        // Inject enabled skill metadata into system prompt
        if let sid = sessionId,
           let skillFragment = SkillStore.shared.skillPromptFragment(for: sid) {
            userSystemPrompt += "\n\n" + skillFragment
        }

        // [T-mcp-integration-ios] Inject Top-20 enabled MCP server metadata.
        if let sid = sessionId,
           let mcpFragment = MCPStore.shared.systemPromptSnippet(for: sid) {
            userSystemPrompt += "\n\n" + mcpFragment
        }

        // [T-memory-toggle-gates-injection-and-tools-ios] Memory injection
        // (GLOBAL.md + recent daily logs) is gated by the per-session
        // memoryEnabled toggle. SOUL.md (identity / persona) is rendered
        // by SystemPromptBuilder.identitySection() above and is NOT
        // affected by this toggle.
        // [T-memory-enabled-new-session-bug DIAG] vm.memoryEnabled is the
        // value the injection actually keys off. Trace it against the
        // session so a repro shows whether loadSession seeded it from the
        // global default.
        AppLogger(category: "MemDiag").info("[MemDiag] inject-decision sid=\(self.sessionId?.prefix(8) ?? "nil") vm.memoryEnabled=\(self.memoryEnabled)")
        if memoryEnabled {
            if let memoryFragment = Self.loadGlobalMemoryFragment() {
                userSystemPrompt += "\n\n" + memoryFragment
            }
            if let dailyFragment = Self.loadRecentDailyMemoryFragment() {
                userSystemPrompt += "\n\n" + dailyFragment
            }
        }
        // Authoritative memory-status footer (overrides any earlier
        // baseSystemPrompt mentions when memory is disabled).
        userSystemPrompt += memoryStatusFragment

        let promptBuildMs = (CFAbsoluteTimeGetCurrent() - loopSetupStart) * 1000
        logger.info("⏱️ [runAgentLoop] prompt build elapsed=\(String(format: "%.1f", promptBuildMs))ms history=\(self.agentHistory.count)")
        await Task.yield()

        // When resuming after error, reuse the existing assistant message; otherwise create new
        var msgIdx: Int
        if let existingMsgIdx {
            msgIdx = existingMsgIdx
            let okBounds = existingMsgIdx >= 0 && existingMsgIdx < messages.count
            let role = okBounds ? (messages[existingMsgIdx].role == .assistant ? "assistant" : "user") : "OOB"
            let blocks = okBounds ? messages[existingMsgIdx].blocks.count : -1
            AppLogger(category: "RetryDiag").info("[RetryDiag] runAgentLoop RESUME existingMsgIdx=\(existingMsgIdx) role=\(role) blocks=\(blocks) committedBlocks=\(committedBlocks ?? -1) messagesCount=\(self.messages.count)")
        } else {
            messages.append(ChatMessage(role: .assistant, content: "", blocks: []))
            msgIdx = messages.count - 1
        }
        // Capture the assistant message's stable id so we can re-find it
        // when the `messages` array gets mutated mid-stream (user delete,
        // reloadMessagesFromDB rebuild, compactBefore removeAll, etc.). On
        // any retry / fallback catch path we resync `msgIdx` via this id
        // before touching `messages[msgIdx]`; if the message is gone we
        // bail out cleanly instead of crashing with Index out of range.
        let runMsgId: UUID = msgIdx < messages.count ? messages[msgIdx].id : UUID()
        /// Track how many blocks were already committed to agentHistory
        /// so subsequent iterations only add new blocks.
        var committedBlockCount = committedBlocks ?? 0
        self.committedBlockCount = committedBlockCount
        var turnUsage = TokenUsage()

        // Log context baseline for offload diagnostics
        let estimatedTokens = estimateContextTokens()
        logger.info("📐 Context baseline: agentHistory=\(self.agentHistory.count) msgs, estimated ~\(estimatedTokens) tokens, turnUsage.latestContextTokens=\(turnUsage.latestContextTokens) (model window: \(ProviderConfigStore.shared.entry(for: entry.id)?.model.contextWindowTokens ?? 0))")

        // Safety: scan the entire agentHistory for assistant messages with tool_use
        // that lack corresponding tool_result messages. This can happen when:
        // - User cancelled mid-tool-execution and cleanup missed an entry
        // - Session was restored from persistence with incomplete state
        // - A race condition between cancel cleanup and retry left orphaned tool_uses
        //
        // IMPORTANT: skip injection when the assistant message was stream-interrupted.
        // A mid-stream network drop leaves tool_use blocks with empty/partial inputs
        // (partialJson never completed). Injecting a tool_result for such a tool_use
        // causes API 400 "unexpected tool_use_id" or "invalid input" errors.
        // Instead, the interrupted assistant message is dropped so the model retries
        // cleanly from the previous user/tool_result turn.
        //
        // First handle the last message specially (may need to be dropped entirely if interrupted).
        if let lastAssistant = agentHistory.last, lastAssistant.role == .assistant, lastAssistant.isInterrupted {
            agentHistory.removeLast()
            logger.warning("Dropped interrupted assistant message with partial tool_use(s) — will retry from previous turn")
        }

        // [T-ios-retry-orphan-toolresult] Snapshot the pairing as runAgentLoop
        // sees it on entry — BEFORE the orphan-removal below mutates anything.
        // If this shows ORPHANS, the entry came in already-broken (the cut /
        // a prior cancel / merged-render reorder stranded a tool_result); the
        // removal pass is only papering over it. Compare against subcut-AFTER.
        dumpHistoryPairing("runAgentLoop-entry (pre-orphan-scan)")
        await Task.yield()

        // Now scan ALL messages for orphaned tool_uses and orphaned tool_results.
        // Build sets of all tool_use IDs and tool_result IDs.
        var allToolUseIds = Set<String>()
        var allToolResultIds = Set<String>()
        for msg in agentHistory {
            for part in msg.parts {
                if case .toolUse(let id, _, _) = part {
                    allToolUseIds.insert(id)
                }
                if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                    allToolResultIds.insert(id)
                }
            }
        }

        // 1. Remove orphaned tool_results (tool_result without matching tool_use).
        //    These cause API 400: "unexpected tool_use_id found in tool_result blocks".
        var removedOrphanedResults = 0
        for i in (0..<agentHistory.count).reversed() {
            let msg = agentHistory[i]
            guard msg.role == .user else { continue }
            let beforeCount = msg.parts.count
            let cleanedParts = msg.parts.filter { part in
                if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                    if !allToolUseIds.contains(id) {
                        logger.warning("Removing orphaned tool_result id=\(id) at history[\(i)]")
                        removedOrphanedResults += 1
                        return false
                    }
                }
                return true
            }
            if cleanedParts.count < beforeCount {
                if cleanedParts.isEmpty {
                    // All parts were orphaned tool_results — remove entire message
                    agentHistory.remove(at: i)
                } else {
                    agentHistory[i] = AgentMessage(role: .user, parts: cleanedParts)
                }
            }
        }
        if removedOrphanedResults > 0 {
            logger.warning("Removed \(removedOrphanedResults) orphaned tool_result(s) without matching tool_use")
        }

        // 2. Inject placeholder tool_results for orphaned tool_uses (tool_use without matching tool_result).
        // Rebuild allToolResultIds after removals above.
        allToolResultIds.removeAll()
        for msg in agentHistory {
            for part in msg.parts {
                if case .toolResult(let id, _, _, _, _, _, _, _) = part {
                    allToolResultIds.insert(id)
                }
            }
        }

        var insertions: [(index: Int, message: AgentMessage)] = []
        for (i, msg) in agentHistory.enumerated() {
            guard msg.role == .assistant else { continue }
            let orphanedToolUses = msg.parts.compactMap { part -> (String, String)? in
                if case .toolUse(let id, let name, _) = part, !allToolResultIds.contains(id) {
                    return (id, name)
                }
                return nil
            }
            guard !orphanedToolUses.isEmpty else { continue }

            let insertIdx = i + 1
            let placeholderParts = orphanedToolUses.map { (id, name) in
                AgentContentPart.toolResult(
                    id: id, name: name,
                    content: "Tool execution was interrupted by an unexpected error.",
                    isError: true
                )
            }
            let placeholderMsg = AgentMessage(role: .user, parts: placeholderParts)
            insertions.append((index: insertIdx, message: placeholderMsg))
            logger.warning("Found \(orphanedToolUses.count) orphaned tool_use(s) in assistant message at index \(i)")
        }
        for insertion in insertions.reversed() {
            agentHistory.insert(insertion.message, at: insertion.index)
        }
        if !insertions.isEmpty {
            logger.warning("Injected placeholder tool results for \(insertions.count) assistant message(s) with orphaned tool_use(s)")
        }

        let orphanScanMs = (CFAbsoluteTimeGetCurrent() - loopSetupStart) * 1000
        logger.info("⏱️ [runAgentLoop] setup complete elapsed=\(String(format: "%.1f", orphanScanMs))ms (prompt+orphanScan)")
        await Task.yield()

        // Counted instead of `while true` so we have a hard backstop against
        // runaway tool loops that slip past ToolLoopDetector. The post-loop
        // tail uses `hitTurnLimit` (not `turnCount >= maxAgentTurns`) to
        // distinguish "exhausted the ceiling" from any internal `break`:
        // the defer below increments turnCount on *every* iteration end,
        // including breaks, so a value-based check would false-positive
        // when the happy-path break fires on the last allowed iteration.
        // hitTurnLimit stays true only when the while-condition itself
        // becomes false, never set false by any break path.
        var turnCount = 0
        var hitTurnLimit = true
        // [T-chat-auto-compact-inloop] Count in-loop auto-compactions so a loop
        // that keeps hitting the threshold can't compact forever; when the cap
        // is reached and we're still near capacity, the loop stops as exhausted.
        var compactionsThisLoop = 0
        loopLabel: while turnCount < Self.maxAgentTurns {
            defer { turnCount += 1 }
            try Task.checkCancellation()
            if let sid = self.sessionId {
                SessionActivityTracker.shared.updateLoopIteration(sid, iteration: turnCount + 1)
            }

            // Safety: if messages were mutated externally (retry/delete/resend) while
            // the agent loop was running, msgIdx may be out of bounds. Bail out.
            guard msgIdx >= 0, msgIdx < messages.count else {
                logger.error("⚠️ Agent loop: msgIdx \(msgIdx) out of bounds (messages.count=\(messages.count)), breaking")
                hitTurnLimit = false
                break
            }

            // Trim old images: keep only the most recent `kImageContextKeepCount`
            // images in history; older ones become text placeholders so the model
            // can re-view them via read_image. Runs before token-based offload so
            // image bytes are removed first.
            trimOldImagesFromHistory()

            // Context window management: offload old tool content if approaching limit
            let activeModelForOffload = ProviderConfigStore.shared.entry(for: activeEntryId ?? "")?.model ?? selectedModel
            offloadContextIfNeeded(model: activeModelForOffload, lastContextTokens: turnUsage.latestContextTokens)

            // [T-chat-auto-compact-inloop] In-loop context guard. checkContext-
            // BeforeSend only runs at the SEND entry point; a single turn that
            // fans out into many tool iterations can blow past the compact/
            // exhausted thresholds mid-loop, where offload alone (which only
            // moves tool payloads to files) can't recover if the bulk is the
            // model's own text. Re-evaluate the policy each iteration, AFTER
            // offload had its chance:
            //   • .needsCompact → compact in place (allowDuringProcessing) and
            //     continue; the next API call reads the freshly-compacted
            //     effectiveAgentHistory() automatically, so no resume handoff.
            //   • .exhausted (compaction couldn't get us under the ceiling) →
            //     stop the loop with a resumable, user-visible notice; the loop
            //     cannot present a modal and wait for a tap.
            // Turn-budget rule: a compaction iteration is a space-management
            // step, NOT a task iteration, so it must NOT consume a turnCount
            // slot (`continue` runs the defer, so we pre-decrement to cancel
            // it) — but the overall maxAgentTurns ceiling is NEVER reset by a
            // compaction, or a loop that keeps compacting could run forever and
            // defeat the runaway backstop.
            switch checkContextBeforeSend() {
            case .ok:
                break
            case .needsCompact:
                let compactAnchorId = messages.last(where: {
                    $0.role != .compactDivider && $0.role != .systemInfo && !$0.isCompactedHistory
                })?.id
                if compactionsThisLoop < Self.maxInLoopCompactions,
                   let anchorId = compactAnchorId {
                    compactionsThisLoop += 1
                    logger.info("[Context] In-loop near capacity — auto-compacting (\(compactionsThisLoop)/\(Self.maxInLoopCompactions)) turnCount=\(turnCount)")
                    await compactBefore(anchorId, allowDuringProcessing: true)
                    turnCount -= 1   // cancel this iteration's defer increment
                    continue
                }
                // Already compacted the cap this loop and still near capacity —
                // fall through to the same stop path as exhaustion.
                logger.info("[Context] In-loop still near capacity after \(compactionsThisLoop) compaction(s) — stopping loop as exhausted")
                fallthrough
            case .exhausted:
                logger.info("[Context] In-loop exhausted — stopping loop with resumable notice (turnCount=\(turnCount))")
                appendSystemInfo(String(localized: "Context is full and could not be compacted further. Start a new session or clear the chat to continue."), icon: "exclamationmark.triangle")
                canResume = true
                hitTurnLimit = false
                break loopLabel
            }

            CrashReporter.shared.setLastAPI(provider: provider.name, model: activeModelForOffload.id)
            logger.info("Calling \(provider.name) API (streaming) with \(self.agentHistory.count) messages")

            #if DEBUG
            // Begin a new agent request trace
            AgentRequestTrace.shared.begin()
            AgentRequestTrace.shared.step("agentLoop.start", detail: "provider=\(provider.name) messages=\(agentHistory.count)")

            // Log agent history summary for debugging tool result delivery.
            // [T-ios-log-noise-reduction] The full dump ran on every agent-loop
            // API call, re-printing the entire (growing) history each iteration
            // — the single biggest Debug-log noise source (~13.6k lines). The
            // structured AgentRequestTrace still records every entry (cheap,
            // in-memory, not NSLog), but the NSLog `📋 agentHistory[i]` line now
            // only fires for entries NEW since the last dump (delta), preceded
            // by a one-line summary. `lastDumpedAgentHistoryCount` resets to 0
            // whenever the history shrinks (retry truncation / new session) so
            // the next dump re-prints from the cut point.
            if agentHistory.count < lastDumpedAgentHistoryCount {
                lastDumpedAgentHistoryCount = 0
            }
            let deltaStart = lastDumpedAgentHistoryCount
            logger.debug("📋 agentHistory dump: total=\(agentHistory.count) newSince=\(deltaStart)")
            for (i, msg) in agentHistory.enumerated() {
                var partSummaries: [String] = []
                for part in msg.parts {
                    switch part {
                    case .text(let t):
                        let preview = String(t.prefix(200))
                        partSummaries.append("text(\(t.count)ch): \"\(preview)\(t.count > 200 ? "..." : "")\"")
                    case .toolUse(let id, let name, _):
                        // Anthropic ids start with the literal 8-char prefix
                        // `toolu_01`, so `prefix(8)` collapses every Anthropic
                        // tool_use to the same string in the trace and masks
                        // real id differences between parallel tool_uses
                        // (T-ios-concurrent-toolcall-dup-id). Use prefix(20)
                        // so concurrent calls are visibly distinct.
                        partSummaries.append("tool_use(\(name), id:\(id.prefix(20)))")
                    case .toolResult(let id, let name, let content, let isError, let imgData, _, _, _):
                        let head = String(content.prefix(200))
                        let tail = content.count > 400 ? String(content.suffix(200)) : ""
                        let errTag = isError ? " [ERROR]" : ""
                        let imgTag = imgData != nil ? " [IMG]" : ""
                        // Same prefix(20) as the .toolUse branch above — see note there.
                        partSummaries.append("tool_result(\(name), id:\(id.prefix(20)), \(content.count)ch\(errTag)\(imgTag)): head=\"\(head)\(content.count > 200 ? "..." : "")\"" + (tail.isEmpty ? "" : " tail=\"...\(tail)\""))
                    case .imageData(_, let mime, _):
                        partSummaries.append("image(\(mime))")
                    }
                }
                AgentRequestTrace.shared.step("agentHistory[\(i)]", detail: "\(msg.role.rawValue): \(partSummaries.joined(separator: " | "))")
                if i >= deltaStart {
                    logger.debug("📋 agentHistory[\(i)] \(msg.role.rawValue): \(partSummaries.joined(separator: " | "))")
                }
            }
            lastDumpedAgentHistoryCount = agentHistory.count
            #endif

            #if DEBUG
            let iterationStart = Date()
            var iterationUsage = TokenUsage()
            AgentRequestTrace.shared.step("streamAgentMessage.call", detail: "maxTokens=\(provider.defaultMaxTokens)")
            #endif
            let iterationStreamStart = Date()
            let prevEntryId = activeEntryId
            fallbackReasons.removeAll()
            // Phase B: route through effectiveAgentHistory() so compact summary is
            // synthesized at inference time instead of baked into agentHistory.
            let contextHistory = effectiveAgentHistory()
            let stream = try await streamWithGroupFallback(
                provider: provider,
                messages: contextHistory,
                baseSystemPrompt: baseSystemPrompt,
                systemPrompt: userSystemPrompt,
                tools: tools,
                model: activeModel,
                lastContextTokens: turnUsage.latestContextTokens,
                chatMessage: messages[msgIdx],
                activeGroupId: &activeGroupId,
                activeEntryId: &activeEntryId
            )

            // Helper: update provider & system prompt when fallback switched entries.
            // Returns true if a switch occurred.
            @discardableResult
            func applyFallbackSwitch() async -> Bool {
                guard activeEntryId != prevEntryId, let newEntryId = activeEntryId,
                      let newEntry = ProviderConfigStore.shared.entry(for: newEntryId) else {
                    return false
                }
                logger.info("🔀AGENT_LOOP provider updated after fallback: \(prevEntryId ?? "nil") → \(newEntryId)")
                provider = await makeAgentProvider(for: newEntry)
                userSystemPrompt = baseSystemPrompt
                if let capFragment = newEntry.model.capabilityPromptFragment {
                    userSystemPrompt += "\n\n" + capFragment
                }
                if let behaviorFragment = newEntry.model.agentBehaviorPromptFragment {
                    userSystemPrompt += "\n\n" + behaviorFragment
                }
                if let sid = sessionId,
                   let skillFragment = SkillStore.shared.skillPromptFragment(for: sid) {
                    userSystemPrompt += "\n\n" + skillFragment
                }
                // [T-mcp-integration-ios] Inject Top-20 enabled MCP metadata.
                if let sid = sessionId,
                   let mcpFragment = MCPStore.shared.systemPromptSnippet(for: sid) {
                    userSystemPrompt += "\n\n" + mcpFragment
                }
                // [T-memory-toggle-gates-injection-and-tools-ios] Mirror
                // the gate from the first injection site — fallback to a
                // new provider must respect the per-session memoryEnabled
                // toggle the same way the initial system prompt did.
                if memoryEnabled {
                    if let memoryFragment = Self.loadGlobalMemoryFragment() {
                        userSystemPrompt += "\n\n" + memoryFragment
                    }
                    if let dailyFragment = Self.loadRecentDailyMemoryFragment() {
                        userSystemPrompt += "\n\n" + dailyFragment
                    }
                }
                userSystemPrompt += memoryStatusFragment
                fallbackTrigger += 1
                if !fallbackReasons.isEmpty {
                    // Resync the assistant message index by its stable id before
                    // mutating `messages`. applyFallbackSwitch performs several
                    // awaits above (makeAgentProvider, fragment loads), during
                    // which a concurrent path (user delete, reloadMessagesFromDB,
                    // compactBefore, session switch) may have shrunk the array —
                    // the captured `msgIdx` then points past the end and the raw
                    // `messages[msgIdx]` subscript traps with "Index out of range"
                    // (crash on AsyncRenderer-adjacent path, AIChatViewModel.swift
                    // 2855). Every other messages[msgIdx] write in this loop already
                    // resyncs-or-guards; this site was the lone unguarded one
                    // (T-ios-fallback-switch-msgidx-oob). If the message is gone,
                    // skip the notice — it's a non-critical UI hint.
                    if let resynced = messages.firstIndex(where: { $0.id == runMsgId }) {
                        msgIdx = resynced
                        let trailLines = fallbackReasons.map { "⚠️ \($0.model) (\($0.instance)): \($0.reason)" }
                        let instanceLabel = ProviderConfigStore.shared.instance(for: newEntry.providerInstanceId)?.label ?? newEntry.model.provider
                        let noticeText = trailLines.joined(separator: "\n") + "\n" + String(localized: "Switched to \(newEntry.model.displayName) (\(instanceLabel))")
                        let infoBlock = AssistantBlock(kind: .info, content: noticeText)
                        messages[msgIdx].blocks.insert(infoBlock, at: 0)
                        fallbackReasons.removeAll()
                    } else {
                        logger.error("🔀AGENT_LOOP applyFallbackSwitch: assistant message id=\(runMsgId) no longer in messages (count=\(self.messages.count)) — skipping fallback notice")
                        fallbackReasons.removeAll()
                    }
                }
                return true
            }

            await applyFallbackSwitch()
            scrollToBottomSignal.send()

            // Helper: check if a StreamResult is empty (no content produced).
            // Catches both the nil-stopReason path (Anthropic SSE error inside
            // HTTP 200) and the endTurn-with-zero-output path (OpenAI gpt-5.5
            // on long contexts: `finish_reason=stop` with no text/tool/reasoning).
            func isEmptyResponse(_ r: StreamResult) -> Bool {
                let hasReasoning = !(r.reasoningContent ?? "").isEmpty
                return r.assistantText.isEmpty && r.toolEntries.isEmpty
                    && !hasReasoning && !r.isStreamInterrupted
                    && r.stopReason != .maxTokens
                    // A refusal (Anthropic safety classifier decline) is deterministic:
                    // the content is empty but retrying the identical request just gets
                    // declined again and burns tokens. Don't route it through the
                    // transient-retry / group-fallback path; let the .refusal branch in
                    // the stop-reason handler surface it directly. [T-ios-fable5-empty-response]
                    && r.stopReason != .refusal
            }

            // Process SSE stream events on a background thread to keep
            // CFNetwork HTTP/2 HPACK decoding off the main RunLoop.
            // If a retryable error (transient 5xx / network) occurs mid-stream,
            // retry the entire request via streamWithAutoRetry before giving up.
            // If auto-retry is also exhausted, re-enter streamWithGroupFallback
            // to try the next model in the group.
            let streamResult: StreamResult
            do {
                let result = try await processStreamEvents(
                    stream: stream,
                    msgIdx: msgIdx,
                    provider: provider
                )
                // Detect empty responses — the stream completed without producing any
                // content blocks or a stop reason.  This happens when the Anthropic API
                // returns an SSE `event: error` (e.g. overloaded_error) inside an HTTP 200
                // response: the SDK silently terminates the stream instead of throwing.
                // Treat as a transient error so it enters the auto-retry path below.
                if isEmptyResponse(result) {
                    // [T-ios-empty-after-toolresult-reminder] Special case: the
                    // server returned nothing right after we handed it a tool
                    // result. The model owes a follow-up (next tool call or a
                    // final answer) but stalled — from the user's side the chat
                    // "stops" with no explanation. Inject a one-shot
                    // <system-reminder> into the last tool result and retry a
                    // SINGLE round. The per-run flag ensures this fires at most
                    // once, so it can never loop; if the reminder round is also
                    // empty we report an error (below) instead of a silent stall.
                    guard !didInjectEmptyToolReminderThisRun, lastEffectiveMessageIsToolResult() else {
                        logger.error("🔁STREAM empty response (no content, no stop reason) — treating as transient error")
                        throw LLMError.transientError(message: "Server returned an empty response (overloaded or upstream error)")
                    }
                    didInjectEmptyToolReminderThisRun = true
                    logger.error("🔁STREAM empty after tool result — injecting <system-reminder> and retrying one round")
                    let reminderStream = try await streamWithAutoRetry(
                        provider: provider,
                        messages: applyRequestImageBudget(historyWithEmptyToolResultReminder()),
                        systemPrompt: userSystemPrompt,
                        tools: tools,
                        maxTokens: dynamicMaxTokens(provider: provider, model: activeModel, lastContextTokens: turnUsage.latestContextTokens),
                        chatMessage: messages[msgIdx]
                    )
                    let reminderResult = try await processStreamEvents(
                        stream: reminderStream,
                        msgIdx: msgIdx,
                        provider: provider
                    )
                    if isEmptyResponse(reminderResult) {
                        // Still empty after the nudge — a genuine failure, not a
                        // transient blip. Surface it clearly instead of silently
                        // looping so the user knows why the chat stopped.
                        logger.error("🔁STREAM still empty after <system-reminder> retry — reporting error")
                        throw LLMError.providerError(message: "The model returned no response after a tool result, even after a reminder. It may be overloaded — please retry or switch models.")
                    }
                    // Recovered — proceed with the reminder round's content.
                    streamResult = reminderResult
                } else {
                    streamResult = result
                }
            } catch let streamError as LLMError where streamError.isRetryable {
                logger.error("🔁STREAM mid-stream retryable error, entering autoRetry: \(streamError.localizedDescription)")
                // Resync msgIdx by stable id before touching messages — the
                // array may have been shrunk by a concurrent path (user
                // delete, reloadMessagesFromDB, compactBefore) during the
                // failed stream. If the assistant message is gone, bail.
                if let resynced = messages.firstIndex(where: { $0.id == runMsgId }) {
                    msgIdx = resynced
                } else {
                    logger.error("🔁STREAM mid-stream catch: assistant message id=\(runMsgId) no longer in messages (count=\(messages.count)) — aborting retry")
                    throw streamError
                }
                // Clear text blocks AND incomplete tool blocks from the interrupted stream
                // before retrying. Tool blocks stuck in .streaming or .running would otherwise
                // remain orphaned in the UI forever (never transition to a terminal state).
                //
                // [T-ios-stream-retry-text-disappear] ONLY clear the UNCOMMITTED tail
                // (blocks at index >= committedBlockCount). Earlier rounds of this same
                // resumed loop already committed+persisted their text/tool blocks to the
                // DB (committedBlockCount marks that boundary). The old unbounded
                // `removeAll { kind == .text }` wiped those committed text blocks from the
                // UI too, so a mid-stream retry made all prior post-tool text vanish on
                // screen even though it was still in the DB (reappearing only on reload).
                // Mirror resume()/Stop's `removeSubrange(committedBlockCount...)` boundary.
                await MainActor.run {
                    guard msgIdx < messages.count else { return }
                    Self.clearUncommittedStreamTail(messages[msgIdx], committedBlockCount: committedBlockCount)
                    messages[msgIdx].streamInterruptCount += 1
                }
                // If group uses "always" fallback strategy, skip auto-retry and go straight
                // to group fallback for mid-stream errors too.
                let midStreamFbStrategy = activeGroupId
                    .flatMap { ProviderConfigStore.shared.group(for: $0) }?.fallbackStrategy ?? .limited
                if midStreamFbStrategy == .always {
                    logger.error("🔀STREAM always-strategy, skipping autoRetry — direct group fallback: \(streamError.localizedDescription)")
                    if let eid = activeEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
                        let inst = ProviderConfigStore.shared.instance(for: entry.providerInstanceId)?.label ?? entry.model.provider
                        fallbackReasons.append((model: entry.model.displayName, instance: inst, reason: streamError.fallbackReason))
                    }
                    // Re-resync after the await above — another task may have
                    // mutated `messages` during the MainActor.run suspension.
                    guard let resynced = messages.firstIndex(where: { $0.id == runMsgId }) else {
                        logger.error("🔀STREAM always-strategy: assistant message id=\(runMsgId) vanished post-await (count=\(messages.count)) — aborting fallback")
                        throw streamError
                    }
                    msgIdx = resynced
                    streamResult = try await streamWithGroupFallbackUntilContent(
                        provider: provider,
                        messages: applyRequestImageBudget(effectiveAgentHistory()),
                        baseSystemPrompt: baseSystemPrompt,
                        systemPrompt: userSystemPrompt,
                        tools: tools,
                        model: activeModel,
                        lastContextTokens: turnUsage.latestContextTokens,
                        chatMessage: messages[msgIdx],
                        msgIdx: msgIdx,
                        activeGroupId: &activeGroupId,
                        activeEntryId: &activeEntryId
                    )
                    await applyFallbackSwitch()
                } else {
                    // Re-resync after the MainActor.run above before the next
                    // messages[msgIdx] read in the retry call.
                    guard let resynced = messages.firstIndex(where: { $0.id == runMsgId }) else {
                        logger.error("🔁STREAM autoRetry path: assistant message id=\(runMsgId) vanished post-await (count=\(messages.count)) — aborting retry")
                        throw streamError
                    }
                    msgIdx = resynced
                    do {
                        let retryStream = try await streamWithAutoRetry(
                            provider: provider,
                            messages: applyRequestImageBudget(effectiveAgentHistory()),
                            systemPrompt: userSystemPrompt,
                            tools: tools,
                            maxTokens: dynamicMaxTokens(provider: provider, model: activeModel, lastContextTokens: turnUsage.latestContextTokens),
                            chatMessage: messages[msgIdx]
                        )
                        let retryResult = try await processStreamEvents(
                            stream: retryStream,
                            msgIdx: msgIdx,
                            provider: provider
                        )
                        // If the retried stream is also empty, the provider is persistently
                        // failing (e.g. Anthropic overloaded).  Trigger group fallback.
                        if isEmptyResponse(retryResult) {
                            logger.error("🔁STREAM autoRetry also returned empty — triggering group fallback")
                            throw LLMError.providerError(message: "Server is overloaded")
                        }
                        streamResult = retryResult
                    } catch let retryError as LLMError where retryError.isFallbackable || retryError.isRetryable {
                        // Auto-retry exhausted or still empty — attempt group fallback.
                        logger.error("🔀STREAM retries exhausted, attempting group fallback: \(retryError.localizedDescription)")
                        if let eid = activeEntryId, let entry = ProviderConfigStore.shared.entry(for: eid) {
                            let inst = ProviderConfigStore.shared.instance(for: entry.providerInstanceId)?.label ?? entry.model.provider
                            fallbackReasons.append((model: entry.model.displayName, instance: inst, reason: retryError.fallbackReason))
                        }
                        // Resync before mutating — same rationale as outer catch.
                        if let resynced = messages.firstIndex(where: { $0.id == runMsgId }) {
                            msgIdx = resynced
                        } else {
                            logger.error("🔀STREAM retry-exhausted catch: assistant message id=\(runMsgId) gone (count=\(messages.count)) — aborting fallback")
                            throw retryError
                        }
                        await MainActor.run {
                            guard msgIdx < messages.count else { return }
                            // [T-ios-stream-retry-text-disappear] Same committed-tail
                            // guard as the outer catch — preserve persisted prior-round
                            // blocks, clear only the failed in-flight tail.
                            Self.clearUncommittedStreamTail(messages[msgIdx], committedBlockCount: committedBlockCount)
                            messages[msgIdx].streamInterruptCount += 1
                        }
                        // Re-resync after MainActor.run before the messages[msgIdx]
                        // read below — see outer catch for rationale.
                        guard let resynced2 = messages.firstIndex(where: { $0.id == runMsgId }) else {
                            logger.error("🔀STREAM retry-exhausted fallback: assistant message id=\(runMsgId) vanished post-await (count=\(messages.count)) — aborting")
                            throw retryError
                        }
                        msgIdx = resynced2
                        // Use streamWithGroupFallbackUntilContent to keep cycling through
                        // group entries until one returns non-empty content.
                        streamResult = try await streamWithGroupFallbackUntilContent(
                            provider: provider,
                            messages: applyRequestImageBudget(effectiveAgentHistory()),
                            baseSystemPrompt: baseSystemPrompt,
                            systemPrompt: userSystemPrompt,
                            tools: tools,
                            model: activeModel,
                            lastContextTokens: turnUsage.latestContextTokens,
                            chatMessage: messages[msgIdx],
                            msgIdx: msgIdx,
                            activeGroupId: &activeGroupId,
                            activeEntryId: &activeEntryId
                        )
                        await applyFallbackSwitch()
                    }
                }
            }

            let streamEnd = Date()
            let assistantText = streamResult.assistantText
            let toolEntries = streamResult.toolEntries
            let stopReason = streamResult.stopReason
            turnUsage = streamResult.turnUsage
            logger.info("📐 Context after API: latestContextTokens=\(turnUsage.latestContextTokens) (in:\(turnUsage.inputTokens) cache_read:\(turnUsage.cacheReadTokens) cache_create:\(turnUsage.cacheCreationTokens))")

            // Track session-level token stats
            let iterationStreamDuration = streamEnd.timeIntervalSince(iterationStreamStart)
            let iterUsage = streamResult.turnUsage
            sessionInputTokens += iterUsage.inputTokens
            sessionOutputTokens += iterUsage.outputTokens
            sessionCacheReadTokens += iterUsage.cacheReadTokens
            sessionCacheWriteTokens += iterUsage.cacheCreationTokens
            if iterationStreamDuration > 0 && iterUsage.outputTokens > 0 {
                sessionStreamDuration += iterationStreamDuration
                sessionStreamOutputTokens += iterUsage.outputTokens
            }
            #if DEBUG
            iterationUsage = streamResult.iterationUsage
            #endif

            // Flush any throttled text delta that hasn't been pushed to the UI yet.
            // Find the last text block (processStreamEvents may have left un-flushed text).
            // Must also update cachedMarkdown — the render path uses it when present,
            // so stale cachedMarkdown causes the final tokens to not display.
            // Guard msgIdx again — messages may have been mutated during streaming.
            if !assistantText.isEmpty, msgIdx >= 0, msgIdx < messages.count,
               let textBlockIdx = messages[msgIdx].blocks.indices.last(where: { messages[msgIdx].blocks[$0].kind == .text }),
               messages[msgIdx].blocks[textBlockIdx].content != assistantText {
                // [DIAG-FINAL-1] Final text flush — this is the last chance to push
                // content to the UI before streaming ends. If this is skipped or
                // deferred (e.g. streamingUIUpdatesSuspended), the cell stays empty.
                logger.info("[StreamEnd] FINAL FLUSH text len=\(assistantText.count) blockContent len=\(messages[msgIdx].blocks[textBlockIdx].content.count) uiSuspended=\(streamingUIUpdatesSuspended) appState=\(UIApplication.shared.applicationState.rawValue)")
                setStreamingTextBlockContent(
                    msgIdx: msgIdx,
                    blockIdx: textBlockIdx,
                    text: assistantText,
                    parsedMarkdown: MarkdownContent(prepareMarkdownForRender(assistantText)),
                    cacheAttributedString: true,
                    requestScroll: true
                )
            } else if !assistantText.isEmpty, msgIdx >= 0, msgIdx < messages.count {
                // [DIAG-FINAL-2] Text already matches — no flush needed.
                // But if the cell is visually empty despite content being set,
                // it means SwiftUI/UIKit didn't re-render after the last @Published update.
                let textBlockIdx = messages[msgIdx].blocks.indices.last(where: { messages[msgIdx].blocks[$0].kind == .text })
                let blockContent = textBlockIdx.map { messages[msgIdx].blocks[$0].content } ?? "<no text block>"
                logger.info("[StreamEnd] SKIP FLUSH — content already matches. text len=\(assistantText.count) blockContent len=\(blockContent.count) hasCachedAttr=\(textBlockIdx.map { messages[msgIdx].blocks[$0].cachedAttributedString != nil } ?? false) hasCachedMD=\(textBlockIdx.map { messages[msgIdx].blocks[$0].cachedMarkdown != nil } ?? false)")
            } else if assistantText.isEmpty {
                // [DIAG-FINAL-3] No assistant text at all — tool-only response.
                logger.info("[StreamEnd] NO TEXT — tool-only response, nothing to flush")
            }

            // Auto-play: scan assistant text for audio URLs with ?auto_play=true
            // This works even when the app is in the background (e.g. Shortcuts intent)
            // because it triggers GlobalAudioPlayer directly, bypassing UI rendering.
            Self.triggerAutoPlayAudio(in: assistantText)

            #if DEBUG
            logger.debug("📊 Turn usage — in: \(turnUsage.inputTokens), out: \(turnUsage.outputTokens), cache_create: \(turnUsage.cacheCreationTokens), cache_read: \(turnUsage.cacheReadTokens)")
            let elapsed = Int(Date().timeIntervalSince(iterationStart) * 1000)
            LastAPIRequestBody.shared.updateLatest(
                usage: CapturedUsage(
                    inputTokens: iterationUsage.inputTokens,
                    outputTokens: iterationUsage.outputTokens,
                    cacheCreationTokens: iterationUsage.cacheCreationTokens,
                    cacheReadTokens: iterationUsage.cacheReadTokens
                ),
                durationMs: elapsed
            )
            #endif

            // Generate session title early — after first LLM response (text or tool call)
            generateSessionTitleIfNeeded(toolEntries: toolEntries.map { (name: $0.name, args: $0.args) })

            // Re-check bounds — messages may have been mutated during streaming.
            guard msgIdx >= 0, msgIdx < messages.count else {
                logger.error("⚠️ Agent loop: msgIdx \(msgIdx) out of bounds after streaming, breaking")
                hitTurnLimit = false
                break
            }

            // Flush any deferred UI updates so block.content is up-to-date before
            // we read it for persistence. Without this, content deferred due to
            // streamingUIUpdatesSuspended would be lost from the saved message.
            flushDeferredStreamingTextUpdateIfNeeded()

            // Build assistant content for agentHistory — only new blocks from this iteration
            var assistantParts: [AgentContentPart] = []
            let allBlocks = messages[msgIdx].blocks
            for i in min(committedBlockCount, allBlocks.count)..<allBlocks.count {
                let block = allBlocks[i]
                if case .text = block.kind {
                    // Prefer the fully-accumulated assistantText over block.content for the
                    // LAST text block — block.content may be stale due to throttled UI updates.
                    let isLastTextBlock = !allBlocks[(i+1)..<allBlocks.count].contains(where: { $0.kind == .text })
                    let content = (isLastTextBlock && assistantText.count > block.content.count)
                        ? assistantText : block.content
                    if !content.isEmpty {
                        assistantParts.append(.text(content))
                        // Also fix up the block so UI and persistence stay in sync
                        if content.count > block.content.count {
                            block.content = content
                        }
                    }
                }
            }
            for tu in toolEntries {
                assistantParts.append(.toolUse(id: tu.id, name: tu.name, input: tu.args))
            }
            self.prevCommittedBlockCount = self.committedBlockCount
            committedBlockCount = allBlocks.count
            self.committedBlockCount = committedBlockCount
            var assistantMessage = AgentMessage(role: .assistant, parts: assistantParts)
            assistantMessage.isInterrupted = streamResult.isStreamInterrupted
            assistantMessage.reasoningContent = streamResult.reasoningContent
            assistantMessage.reasoningEcho = streamResult.reasoningEcho
            let assistantAgentIdx = agentHistory.count
            agentHistory.append(assistantMessage)

            // Snapshot state for cache keep-alive warmup requests.
            // Phase B: must use effectiveAgentHistory so the keep-alive prefix matches
            // what we actually send to Anthropic (summary-prepended view).
            if provider is AnthropicAgentProvider, let sid = sessionId {
                keepAliveHistory = effectiveAgentHistory()
                keepAliveSystemPrompt = userSystemPrompt
                keepAliveTools = tools
                keepAliveEntry = ProviderConfigStore.shared.entry(for: activeEntryId ?? "")
                logger.info("🔥 cache keep-alive: snapshot updated — session=\(sid.prefix(8)) history=\(self.agentHistory.count) tools=\(tools.count) sysPromptLen=\(userSystemPrompt.count) enhancedCache=\(self.enhancedCacheEnabled) cacheRead=\(turnUsage.cacheReadTokens) cacheCreate=\(turnUsage.cacheCreationTokens)")
                CacheKeepAliveManager.shared.recordRequest(sessionId: sid, vm: self)
            }

            // Build thoughtSignature map from tool entries for persistence
            var sigMap: [String: String] = [:]
            for tu in toolEntries {
                if let sig = tu.metadata?.thoughtSignature { sigMap[tu.id] = sig }
            }

            // If no tool uses, this turn is done — persist and break.
            guard !toolEntries.isEmpty else {
                logger.info("Agent loop ending — no tool calls. stopReason=\(String(describing: stopReason))")

                // [T-ios-stale-resume-banner] This turn produced a complete
                // assistant reply, so the conversation is no longer in an
                // interrupted/resumable state. canResume is only cleared at the
                // send/retry ENTRY points — if an earlier turn in this session
                // set it true (a prior interruption, a Stop, a transient empty
                // response) and was then continued, a normally-finished turn
                // never reset it, leaving the "Interrupted — tap Resume" banner
                // dangling under a fully-answered message (seen on the weibo
                // hot-search turn). Clear it here; the genuinely-incomplete
                // sub-cases below re-set it to true.
                canResume = false

                // Surface unexpected stops: nil stopReason or maxTokens with no text
                if stopReason == nil {
                    logger.warning("Agent loop ended with nil stopReason (stream closed unexpectedly)")
                    // [T-stream-drop-silent] A nil stopReason on the no-tool exit
                    // means the SSE ended WITHOUT the server sending a terminal
                    // finish_reason/message_stop — the stream returned instead of
                    // throwing (a mid-flight throw would go through the fallback/
                    // retry path, not here). This is an abnormal interruption, not a
                    // normal completion: a normally-finished turn always carries a
                    // concrete stopReason (endTurn/stop/…). Previously we only
                    // surfaced this when the text was empty, so a *partial* reply
                    // (bytes arrived, then the connection dropped on weak/handoff
                    // cellular — the "断流无提示" report) was persisted silently as if
                    // complete. Surface BOTH sub-cases now with a Resume entry, and
                    // distinguish the wording so a partial reply reads as truncated
                    // rather than empty.
                    if assistantText.isEmpty {
                        messages[msgIdx].error = String(localized: "Response ended unexpectedly (no content received)")
                    } else {
                        messages[msgIdx].error = String(localized: "The connection dropped — this reply may be incomplete. Tap Resume to continue.")
                    }
                    canResume = true
                } else if stopReason == .maxTokens {
                    logger.warning("Agent loop ended at max_tokens with no tool calls")
                    messages[msgIdx].error = String(localized: "Response truncated (max tokens reached)")
                    canResume = true
                } else if stopReason == .refusal {
                    // [T-ios-fable5-empty-response] Anthropic safety classifier declined the
                    // request (HTTP 200, stop_reason="refusal", input tokens billed, empty
                    // content). On Fable 5 this fires as a false-positive on ordinary turns
                    // that carry the large Claude Code agentic system prompt + tool set — the
                    // exact reason the model detail page's Quick Test (no system prompt, no
                    // tools) succeeds while a real conversation returns empty. Retrying the
                    // identical request just gets declined again, so we surface it directly
                    // and steer the user to switch models rather than looping.
                    logger.warning("Agent loop ended with stop_reason=refusal — model declined the request")
                    messages[msgIdx].error = String(localized: "The model declined to respond to this request. Try rephrasing, or switch to a different model.")
                    canResume = true
                } else if stopReason == .endTurn && assistantText.isEmpty
                            && (streamResult.reasoningContent ?? "").isEmpty {
                    // Empty endTurn that survived all upstream retries — typically
                    // OpenAI gpt-5.5 on long contexts returning `finish_reason=stop`
                    // with no text/tool/reasoning. Surface it instead of silently
                    // dropping (ChatStore's empty-assistant filter would otherwise
                    // erase the row entirely).
                    logger.warning("Agent loop ended at endTurn with empty content")
                    // [T-error-persist-ios] If the context is nearly full, the empty
                    // response is most likely a context-overflow symptom — steer the
                    // user toward compacting rather than a blind retry.
                    // Re-fetch the entry from the store: `entry` was captured at
                    // loop start, so a context-window edit made during a long
                    // agent run would otherwise judge by the stale value here.
                    let freshModel = ProviderConfigStore.shared.entry(for: entry.id)?.model ?? entry.model
                    let maxCtx = effectiveContextWindow(for: freshModel)
                    let curCtx = estimateContextTokens()
                    if maxCtx > 0 && curCtx > Int(Double(maxCtx) * 0.7) {
                        messages[msgIdx].error = String(localized: "The model returned an empty response. The conversation context may be too large — try compacting or starting a new session.")
                    } else {
                        messages[msgIdx].error = String(localized: "Model returned an empty response. Please try again or switch models.")
                    }
                    canResume = true
                }

                // Flush any remaining unspoken text (streaming TTS already spoke most sentences)
                let remainingSpeak: String
                if streamResult.spokenTextOffset < streamResult.assistantText.count {
                    let startIdx = streamResult.assistantText.index(streamResult.assistantText.startIndex, offsetBy: streamResult.spokenTextOffset)
                    remainingSpeak = String(streamResult.assistantText[startIdx...]).trimmingCharacters(in: .whitespacesAndNewlines)
                } else {
                    remainingSpeak = ""
                }
                if !remainingSpeak.isEmpty { speakQueued(remainingSpeak) }
                let interruptCount = await MainActor.run { messages[msgIdx].streamInterruptCount }
                let uiBlockCount = await MainActor.run { msgIdx < messages.count ? messages[msgIdx].blocks.count : -1 }
                logger.info("[BlocksLost] PERSIST final (no-tool) sid=\(sessionId?.prefix(8) ?? "nil") agentParts=\(assistantMessage.parts.count) uiBlocks=\(uiBlockCount)")
                if turnCount > 0 && streamResult.assistantText.count < 10 {
                    // [T-log-noise-privacy 2026-07-18] Content excerpt dropped —
                    // the char count + token counts carry the diagnostic signal.
                    logger.warning("[ModelDiag] SUSPICIOUS short final text after \(turnCount) tool turn(s): \(streamResult.assistantText.count) chars, sid=\(sessionId?.prefix(8) ?? "nil") outputTokens=\(turnUsage.outputTokens) contextTokens=\(turnUsage.latestContextTokens ?? 0)")
                }
                if let persistedId = await persistAgentMessage(assistantMessage, tokenUsage: turnUsage, thoughtSignatures: sigMap, streamInterruptCount: interruptCount),
                   assistantAgentIdx < agentHistory.count {
                    agentHistory[assistantAgentIdx].dbMessageId = persistedId
                    // [T-error-persist-ios] Persist this turn's error state AFTER the
                    // row exists + dbMessageId is assigned, keyed by that id, so the
                    // indicator survives reload. Write UNCONDITIONALLY (incl. nil) so a
                    // successful (re)stream CLEARS a previously-persisted error rather
                    // than leaving the stale one in the DB (retry-then-succeed, edge #5).
                    if msgIdx < messages.count {
                        await ChatStore.shared.updateMessageErrorInfo(messageId: persistedId, errorInfo: messages[msgIdx].error)
                    }
                }
                hitTurnLimit = false

                // [T-ios-queued-message-continues-prev-turn] The current turn's
                // tool loop has fully converged (no tool calls this iteration) and
                // its final assistant reply is now persisted. If the user queued a
                // message mid-run, NOW inject it as a clean standalone user turn and
                // keep looping so the model produces a dedicated response — instead
                // of the old mid-loop drain that folded it into a tool_result and
                // made it read as in-loop context for this turn. Errors above
                // (canResume true) still inject: the user's new message takes
                // precedence over a stale interrupted-banner from this finished turn.
                if await injectQueuedPromptsAsNewTurn(
                    msgIdx: &msgIdx,
                    committedBlockCount: &committedBlockCount,
                    turnUsage: turnUsage
                ) {
                    // We're continuing with the user's new turn, so this session is
                    // no longer in an interrupted/resumable state. Reset per-turn
                    // usage for the fresh assistant message.
                    canResume = false
                    turnUsage = TokenUsage()
                    continue
                }
                break
            }

            // Build assistant RawMessage now but defer DB write until tool results
            // are ready, so both can be persisted in a single SQLite transaction.
            let deferredAssistantRaw = await buildRawMessage(assistantMessage, thoughtSignatures: sigMap)
            // Phase B: write the DB id back into agentHistory immediately — even though
            // we defer the actual appendMessages to batch with the tool results below,
            // the id is deterministic and compact lookups rely on it.
            if let raw = deferredAssistantRaw, assistantAgentIdx < agentHistory.count {
                agentHistory[assistantAgentIdx].dbMessageId = raw.id
            }
            // Flush any remaining unspoken text (streaming TTS already spoke most sentences)
            do {
                let remainingSpeak: String
                if streamResult.spokenTextOffset < streamResult.assistantText.count {
                    let startIdx = streamResult.assistantText.index(streamResult.assistantText.startIndex, offsetBy: streamResult.spokenTextOffset)
                    remainingSpeak = String(streamResult.assistantText[startIdx...]).trimmingCharacters(in: .whitespacesAndNewlines)
                } else {
                    remainingSpeak = ""
                }
                if !remainingSpeak.isEmpty { speakQueued(remainingSpeak) }
            }

            // Execute each tool use and collect results
            var toolResultParts: [AgentContentPart] = []
            var pendingSnapshots: [String: (toolName: String, snapshot: ToolSnapshot)] = [:]
            var cancelledDuringToolExecution = false

            // Image budget for this batch of tool results.
            //
            // Was: `kImageContextKeepCount - existingImages` (double-count with
            // trimOldImagesFromHistory). When trim left agentHistory near the
            // 20-image cap, a fan-out batch of 5+ read_image calls would race
            // for the few remaining slots and emit text placeholders for the
            // losers — even though the next turn's trim would have evicted
            // the old images anyway. Net effect: concurrent read_image
            // silently lost frames for the user.
            //
            // Now: budget the full `kImageContextKeepCount` per batch. The
            // single-turn cap is still 20 (worst case), and
            // trimOldImagesFromHistory() at the top of the next turn handles
            // historical pressure. [T-image-budget-double-count]
            let imageBudgetActor = BatchImageBudget(initial: Self.kImageContextKeepCount)

            #if DEBUG
            // [T-ios-concurrent-toolcall-dup-id] Per-batch dispatch trace —
            // logs each tool_use's full id + blockIdx so we can see whether
            // parallel calls share ids or have collisions on toolUseId
            // when the cancel-bubble bug reproduces.
            let _batchBlockCount = (msgIdx >= 0 && msgIdx < self.messages.count) ? self.messages[msgIdx].blocks.count : -1
            logger.info("[ConcurrentTools] BATCH START — entries=\(toolEntries.count) msgIdx=\(msgIdx) blocks=\(_batchBlockCount)")
            for (idx, tu) in toolEntries.enumerated() {
                logger.info("[ConcurrentTools]   DISPATCH [\(idx)] tu.id=\(tu.id) name=\(tu.name) blockIdx=\(tu.blockIdx)")
            }
            // toolUseId collision check across blocks in this assistant msg
            if msgIdx < self.messages.count {
                var seen: [String: Int] = [:]
                for blk in self.messages[msgIdx].blocks {
                    guard let tid = blk.toolUseId else { continue }
                    seen[tid, default: 0] += 1
                }
                let dupes = seen.filter { $0.value > 1 }
                if !dupes.isEmpty {
                    logger.error("[ToolUseId] COLLISION! Duplicate toolUseId across blocks: \(dupes)")
                }
            }
            #endif

            // Reset the global stop-signal once for the whole batch.
            // Concurrent tool tasks (below) only READ this flag; resetting
            // inside any single task would race-clear a stop initiated for
            // a sibling. [T-concurrent-tools 2026-05-25]
            commandCancelledByUser = false

            // Concurrent tool dispatch with maxConcurrentTools in-flight cap.
            // Anthropic requires tool_result blocks in the same order as
            // the originating tool_use blocks, so we tag each child task
            // with its source index and stitch results back in order after
            // every task completes.
            let toolsSnapshot = tools
            var outcomesByIndex: [Int: ToolExecOutcome] = [:]
            await withTaskGroup(of: (Int, ToolExecOutcome).self) { group in
                var added = 0
                var harvested = 0
                for (idx, tu) in toolEntries.enumerated() {
                    // Rate-limit: hold here until we're below the in-flight cap.
                    while added - harvested >= Self.maxConcurrentTools {
                        if let pair = await group.next() {
                            outcomesByIndex[pair.0] = pair.1
                            harvested += 1
                        } else {
                            break
                        }
                    }
                    logger.info("[ToolLifecycle] DISPATCHED toolId=\(tu.id.prefix(20)) tool=\(tu.name) sid=\(self.sessionId?.prefix(8) ?? "nil") appState=\(UIApplication.shared.applicationState == .active ? "fg" : "bg") suspended=\(self.streamingUIUpdatesSuspended) isProcessing=\(self.isProcessing)")
                    group.addTask { [weak self] in
                        guard let self else {
                            // Self torn down mid-batch: synthesize a cancelled outcome
                            let cancelMsg = "<system-reminder>The session was torn down before this tool could execute.</system-reminder>"
                            return (idx, ToolExecOutcome(
                                toolId: tu.id, toolName: tu.name,
                                resultPart: .toolResult(id: tu.id, name: tu.name, content: cancelMsg, isError: true),
                                snapshotEntry: nil, snapshotItem: nil, cancelled: true
                            ))
                        }
                        let outcome = await self.executeSingleToolUse(
                            tu: tu, msgIdx: msgIdx, tools: toolsSnapshot, batchBudget: imageBudgetActor
                        )
                        return (idx, outcome)
                    }
                    added += 1
                }
                // Drain the remainder.
                for await pair in group {
                    outcomesByIndex[pair.0] = pair.1
                    harvested += 1
                }
            }

            // Stitch outcomes back in original tool_use order so the
            // tool_result blocks pair correctly with the assistant
            // message's tool_use blocks for the next API call.
            for idx in 0..<toolEntries.count {
                guard let outcome = outcomesByIndex[idx] else { continue }
                toolResultParts.append(outcome.resultPart)
                if let se = outcome.snapshotEntry {
                    pendingSnapshots[outcome.toolId] = se
                }
                if let si = outcome.snapshotItem {
                    toolSnapshots.append(si)
                }
                if outcome.cancelled {
                    cancelledDuringToolExecution = true
                }
            }

            // If any tool images were stripped due to budget, append a batch-reading tip
            let strippedImageCount = await imageBudgetActor.strippedSoFar
            if strippedImageCount > 0 {
                toolResultParts.append(.text(
                    "<system-reminder>\(strippedImageCount) image(s) from read_image were replaced with text placeholders"
                    + " because the context image limit (\(Self.kImageContextKeepCount)) was reached."
                    + " Summarize your findings so far, then use read_image again"
                    + " to view the remaining images in a new batch.</system-reminder>"
                ))
            }

            // Add tool results as a user message
            let toolResultMessage = AgentMessage(role: .user, parts: toolResultParts)
            logger.info("Appending tool result message with \(toolResultParts.count) part(s) to agentHistory (now \(self.agentHistory.count + 1) messages)")
            let toolResultAgentIdx = agentHistory.count
            agentHistory.append(toolResultMessage)

            // Collect terminal tool statuses from the assistant blocks so persistence can
            // distinguish failed vs cancelled (both have success == false).
            var toolStatusStrings: [String: String] = [:]
            if msgIdx < messages.count {
                for block in messages[msgIdx].blocks {
                    guard let id = block.toolUseId, let status = block.toolStatus else { continue }
                    switch status {
                    case .success: toolStatusStrings[id] = "success"
                    case .failed: toolStatusStrings[id] = "failed"
                    case .cancelled: toolStatusStrings[id] = "cancelled"
                    case .streaming, .running: break
                    }
                }
            }

            // Batch-persist the deferred assistant message + tool results in one transaction
            let uiBlockCountMid = await MainActor.run { msgIdx < messages.count ? messages[msgIdx].blocks.count : -1 }
            logger.info("[BlocksLost] PERSIST mid-loop (tool) sid=\(sessionId?.prefix(8) ?? "nil") agentParts=\(assistantMessage.parts.count) uiBlocks=\(uiBlockCountMid)")
            if let toolResultRaw = await buildRawMessage(toolResultMessage, snapshots: pendingSnapshots, toolStatuses: toolStatusStrings) {
                var batch: [RawMessage] = []
                if let assistantRaw = deferredAssistantRaw { batch.append(assistantRaw) }
                batch.append(toolResultRaw)
                await ChatStore.shared.appendMessages(batch)
                // Phase B: write the DB id back into agentHistory entries so compact
                // can resolve boundaries by id.
                if toolResultAgentIdx < agentHistory.count {
                    agentHistory[toolResultAgentIdx].dbMessageId = toolResultRaw.id
                }
            } else if let assistantRaw = deferredAssistantRaw {
                await ChatStore.shared.appendMessage(assistantRaw)
                // (assistantAgentIdx was already populated above via deferredAssistantRaw path)
            }

            // Signal that tools are done and we're waiting for the model's next response.
            guard msgIdx < messages.count else { break }
            messages[msgIdx].isAwaitingModelResponse = true

            // If user cancelled during tool execution, commit everything and stop.
            // History is now properly paired: assistant(tool_use) + user(tool_result).
            if cancelledDuringToolExecution || self.userDidCancel {
                logger.info("⏹️ User cancelled during tool execution — committed \(toolResultParts.count) tool result(s), stopping agent loop")
                self.prevCommittedBlockCount = self.committedBlockCount
                committedBlockCount = messages[msgIdx].blocks.count
                self.committedBlockCount = committedBlockCount
                messages[msgIdx].usage = turnUsage
                hitTurnLimit = false
                break
            }

            // [T-ios-queued-message-interrupt-on-toolclose] If the user queued a
            // prompt while tools were running, interrupt as soon as the CURRENT
            // tool call closes (its tool_result is now built + persisted) — don't
            // keep grinding through the rest of the running plan. We break out of
            // the tool loop here; the post-loop drainQueuedPrompts() then lands the
            // queued prompt as a clean STANDALONE user turn and the model responds
            // to it next.
            //
            // Why break instead of appending inline: appending the queued user
            // message right after the tool_result lets mergeConsecutiveSameRole
            // fold it into that tool_result as a trailing text block (Anthropic
            // requires consecutive same-role messages merged), so the model read
            // it as in-loop context for the previous turn and never gave it its
            // own response (the #579 bug). Breaking → fresh turn avoids the merge
            // entirely while still inserting/un-queuing the message immediately.
            if !promptQueue.isEmpty {
                logger.info("📨[QueueInterrupt] \(self.promptQueue.count) queued prompt(s) — interrupting after current tool call to start a standalone turn")
                self.prevCommittedBlockCount = self.committedBlockCount
                committedBlockCount = messages[msgIdx].blocks.count
                self.committedBlockCount = committedBlockCount
                // injectQueuedPromptsAsNewTurn un-queues the on-screen message
                // (drops dashed border + X) so it appears inserted immediately,
                // persists it as a standalone user turn, caches the just-finished
                // assistant blocks, and starts a fresh assistant message. We then
                // `continue` so the next iteration produces a dedicated response to
                // the queued prompt — abandoning the rest of the running plan.
                if await injectQueuedPromptsAsNewTurn(
                    msgIdx: &msgIdx,
                    committedBlockCount: &committedBlockCount,
                    turnUsage: turnUsage
                ) {
                    canResume = false
                    turnUsage = TokenUsage()
                    continue
                }
            }

            // If the background task expired while executing tools, pause here
            // until the app returns to the foreground before making the next API call.
            await waitIfBackgroundSuspended()

            // If the user activated browser takeover, pause until they're done.
            await waitIfBrowserTakeover()

            // If stop reason was end_turn (not tool_use), break
            if stopReason != .toolUse {
                // maxTokens: the model ran out of output tokens mid-response.
                // Show a warning and enable Resume so the user can continue.
                if stopReason == .maxTokens {
                    logger.warning("Agent loop stopped: max_tokens reached")
                    messages[msgIdx].error = String(localized: "Response truncated (max tokens reached)")
                    canResume = true
                }
                // [T-stream-drop-silent] nil stopReason after tool turns means the
                // stream ended without a proper terminal event — same abnormal
                // interruption as the no-tool exit above. Surface with content-aware
                // wording + Resume instead of the old bare "ended unexpectedly".
                if stopReason == nil && toolEntries.isEmpty {
                    logger.warning("Agent loop stopped: stream ended without stop reason")
                    if assistantText.isEmpty {
                        messages[msgIdx].error = String(localized: "Response ended unexpectedly (no content received)")
                    } else {
                        messages[msgIdx].error = String(localized: "The connection dropped — this reply may be incomplete. Tap Resume to continue.")
                    }
                    canResume = true
                }
                hitTurnLimit = false
                break
            }
        }

        // hitTurnLimit stays true only when the while-condition itself failed
        // — meaning the model kept producing tool calls for maxAgentTurns
        // iterations straight. Surface why we stopped + arm canResume so the
        // user can continue from here. Mirrors Android ChatViewModel.
        // finalizeAtTurnLimit. (Earlier draft used `turnCount >= max` which
        // false-positived: the `defer { turnCount += 1 }` runs even on break,
        // so a happy-path break on the final allowed iteration would inflate
        // turnCount to max and slap a fake "200 turns hit" error on every
        // ordinary completion — exactly the v1.4.0-dev bug user hit.)
        if hitTurnLimit, msgIdx >= 0, msgIdx < messages.count {
            logger.warning("⚠️ runAgentLoop hit maxAgentTurns=\(Self.maxAgentTurns) — finalizing as resumable")
            messages[msgIdx].error =
                "Stopped after \(Self.maxAgentTurns) agent turns to prevent runaway tool use. " +
                "The model kept calling tools without finishing — tap Resume to continue from here, " +
                "or send a new message to start over."
            canResume = true
        }

        // Store accumulated usage on the assistant message
        guard msgIdx < messages.count else { return }
        messages[msgIdx].isAwaitingModelResponse = false
        messages[msgIdx].usage = turnUsage

        // Safety net: force-close any tool blocks still stuck in non-terminal status.
        // This can happen when stream interruptions, retries, or edge cases leave
        // tool blocks in .streaming or .running without transitioning to success/failed/cancelled.
        for block in messages[msgIdx].blocks {
            switch block.toolStatus {
            case .streaming:
                // [T-ios-concurrent-toolcall-dup-id] PRIMARY SUSPECT for the
                // concurrent cancel-bubble bug: if outcome stitching drops an
                // entry, that block stays .streaming and lands here.
                logger.warning("[SafetyNet] force-closing .streaming → .cancelled toolUseId=\(block.toolUseId ?? "nil") kind=\(block.kind) content_len=\(block.content.count)")
                block.toolStatus = .cancelled
            case .running:
                logger.warning("[SafetyNet] force-closing .running → .cancelled toolUseId=\(block.toolUseId ?? "nil") kind=\(block.kind) content_len=\(block.content.count)")
                block.toolStatus = .cancelled
            default:
                break
            }
        }

        // Cache parsed MarkdownContent and NSAttributedString on completed text blocks
        // to avoid re-parsing and re-rendering on cell recycling / SwiftUI refreshes.
        for i in messages[msgIdx].blocks.indices {
            if case .text = messages[msgIdx].blocks[i].kind, !messages[msgIdx].blocks[i].content.isEmpty {
                messages[msgIdx].blocks[i].cachedMarkdown = MarkdownContent(prepareMarkdownForRender(messages[msgIdx].blocks[i].content))
                cacheAttributedString(for: messages[msgIdx].blocks[i])
            }
        }

    }

    /// Builds the final NSAttributedString for a completed text block and stores it
    /// on the block. Must be called on the MainActor. No-op if content is empty.
    @MainActor
    func cacheAttributedString(for block: AssistantBlock) {
        guard !block.content.isEmpty else { return }
        // [T-ios-markdown-rerender-burst] Persist the parsed MarkdownContent back
        // onto the block, not just the rendered attributed string. Previously
        // this parsed `MarkdownContent(prepared)` to render the attributed
        // string but threw the parse away, leaving `block.cachedMarkdown` nil
        // for messages loaded from DB (loadSession calls this for every text
        // block). The SelectableMarkdownView render path then re-ran cmark on
        // EVERY updateUIView — and for table-containing messages (which
        // [TablePathFix] forces through fresh render, bypassing
        // cachedAttributedString) that re-parse fired 6–12× per scroll-decel
        // second, dropping 5–8 frames. Caching the parse makes the render path
        // hit `cachedContent` (skip re-parse) AND keys its content-hash off the
        // stable `blocks.hashValue` so the per-coordinator hash-skip can land.
        // No extra work: we were already computing this MarkdownContent here.
        let content = block.cachedMarkdown ?? MarkdownContent(prepareMarkdownForRender(block.content))
        block.cachedMarkdown = content
        block.cachedAttributedString = autoreleasepool {
            renderMarkdownBlocks(content.blocks)
        }
    }

    @MainActor
    func setStreamingUIUpdatesSuspended(_ suspended: Bool) {
        guard streamingUIUpdatesSuspended != suspended else { return }
        // [T-ios-scroll-suspend-leak] Full lifecycle trace of every true↔false
        // transition. When UI/DB inconsistency is reported, this lets us see
        // whether a SUSPEND ever got a matching RESUME (the leak = a SUSPEND with
        // no RESUME before the session was switched away). `caller` is the direct
        // caller frame (scrollViewWillBeginDragging / scenePhase / openSession …).
        let caller = Thread.callStackSymbols.count > 1 ? Self.shortFrame(Thread.callStackSymbols[1]) : "?"
        logger.info("[SuspendState] \(suspended ? "SUSPEND" : "RESUME") sid=\(sessionId?.prefix(8).description ?? "nil") caller=\(caller)")
        if suspended {
            streamingUIUpdatesSuspended = true
        } else {
            // [T-ios-ui-blocks-lost-suspend-resume] Flush VM-side deferred text
            // FIRST so `messages` holds the freshest content, then clear the
            // flag + fire the resume hook so the Coordinator rebuilds any
            // snapshot it deferred during the suspend window.
            flushDeferredStreamingTextUpdateIfNeeded()
            clearStreamingUIUpdatesSuspended()
            // [T-ios-tool-status-desync] Republish so tool-status views
            // (FloatingToolBar, ToolStatusBar) pick up any toolBlocks /
            // tool_use mutations that landed while suspended. Without this,
            // @Published only fires on assignment — in-place mutations to
            // `messages` during the suspend window are invisible to
            // subscribers. Mirrors setSuspendedForTransition(false).
            objectWillChange.send()
        }
    }

    /// Gate UI invalidation while this vm is on the outgoing side of a
    /// session-switch. ContentView calls `setSuspendedForTransition(true)` on
    /// the previously-selected vm immediately before `selectedSessionId`
    /// flips, then schedules a `setSuspendedForTransition(false)` after a
    /// short delay so the next time the user comes back to this session the
    /// streaming UI catches up.
    /// See `transitionSuspended` doc-comment for the full crash motivation.
    @MainActor
    func setSuspendedForTransition(_ suspended: Bool) {
        guard transitionSuspended != suspended else { return }
        transitionSuspended = suspended
        // While transition-suspended, also keep the streaming text path in
        // defer mode. On resume, fall back to whatever explicit value the
        // scroll-suppression callers had requested (we conservatively clear,
        // since the most common case during a transition is no scroll).
        if suspended {
            streamingUIUpdatesSuspended = true
        } else {
            flushDeferredStreamingTextUpdateIfNeeded()
            // [T-ios-ui-blocks-lost-suspend-resume] Clear via the chokepoint so
            // the Coordinator's resume hook fires too (rebuilds any deferred
            // snapshot), in addition to the republish below.
            clearStreamingUIUpdatesSuspended()
            // Republish so the new (or returning) message-list binding picks
            // up the current `messages` snapshot — Published only fires on
            // assignment, so any mutations that occurred during suspend are
            // now reflected in `messages` but no subscriber has been notified.
            objectWillChange.send()
        }
    }

    @MainActor
    func setStreamingTextBlockContent(
        msgIdx: Int,
        blockIdx: Int,
        text: String,
        parsedMarkdown: MarkdownContent?,
        cacheAttributedString shouldCacheAttributedString: Bool,
        requestScroll: Bool
    ) {
        guard msgIdx >= 0, msgIdx < messages.count,
              blockIdx >= 0, blockIdx < messages[msgIdx].blocks.count else { return }
        let message = messages[msgIdx]
        let block = message.blocks[blockIdx]

        // Record the streaming flush into the hang-logger's ring buffer so
        // any main-thread stall that occurs during streaming is logged
        // together with the most recent on-screen content tail. Cheap: a
        // substring + struct push on a background queue. No-op when no
        // streaming session is currently active.
        StreamingHangLogger.shared.recordStreamingFlush(
            messageId: message.id,
            blockId: block.id,
            text: text,
            finalFlush: shouldCacheAttributedString
        )

        if streamingUIUpdatesSuspended {
            // [DIAG-SUSPEND] UI updates are suspended (e.g. tool detail sheet is presented).
            // If this is the FINAL flush (cacheAttributedString=true), the content will be
            // deferred until the sheet is dismissed. If the sheet never dismisses properly,
            // the cell stays empty.
            logger.info("[StreamText] DEFERRED — uiSuspended=true text len=\(text.count) cacheAttr=\(shouldCacheAttributedString) blockId=\(block.id)")
            let existing = deferredStreamingTextUpdates.first(where: {
                $0.messageId == message.id && $0.blockId == block.id
            })
            let mergedCacheFlag = shouldCacheAttributedString || (existing?.cacheAttributedString == true)
            let pending = DeferredStreamingTextUpdate(
                messageId: message.id,
                blockId: block.id,
                text: text,
                parsedMarkdown: parsedMarkdown,
                cacheAttributedString: mergedCacheFlag
            )
            if let existingIdx = deferredStreamingTextUpdates.firstIndex(where: {
                $0.messageId == message.id && $0.blockId == block.id
            }) {
                deferredStreamingTextUpdates[existingIdx] = pending
            } else {
                deferredStreamingTextUpdates.append(pending)
            }
            return
        }

        applyStreamingTextBlockContent(
            block,
            text: text,
            parsedMarkdown: parsedMarkdown,
            cacheAttributedString: shouldCacheAttributedString
        )
        if requestScroll && isNearBottom {
            scrollToBottomSignal.send()
        }
    }

    @MainActor
    private func applyStreamingTextBlockContent(
        _ block: AssistantBlock,
        text: String,
        parsedMarkdown: MarkdownContent?,
        cacheAttributedString shouldCacheAttributedString: Bool
    ) {
        let wasEmpty = block.content.isEmpty
        block.content = text
        if let parsedMarkdown {
            block.cachedMarkdown = parsedMarkdown
        } else if block.cachedMarkdown == nil {
            block.cachedMarkdown = MarkdownContent(prepareMarkdownForRender(text))
        }
        if shouldCacheAttributedString {
            cacheAttributedString(for: block)
        }
        // When content transitions from empty to non-empty, signal the collection
        // view to reconfigure the cell. UIHostingConfiguration doesn't always
        // trigger preferredLayoutAttributesFitting when the SwiftUI body changes
        // from emitting nothing (EmptyView) to emitting content — the cell can
        // stay at ~0 height indefinitely without this explicit nudge.
        if wasEmpty && !text.isEmpty,
           let message = messages.first(where: { $0.blocks.contains(where: { $0.id == block.id }) }) {
            let prefix = String(text.prefix(30))
            let blockIdx = message.blocks.firstIndex(where: { $0.id == block.id }) ?? -1
            let prevBlockKind: String = blockIdx > 0 ? "\(message.blocks[blockIdx - 1].kind)" : "none"
            logger.info("[TextDrift] empty→filled blockIdx=\(blockIdx) prevBlockKind=\(prevBlockKind) len=\(text.count) text=\"\(prefix)\"")
            blockContentFilledSignal.send((messageId: message.id, blockId: block.id))
        }
    }

    @MainActor
    private func flushDeferredStreamingTextUpdateIfNeeded() {
        guard !deferredStreamingTextUpdates.isEmpty else { return }
        let pendingUpdates = deferredStreamingTextUpdates
        deferredStreamingTextUpdates.removeAll()
        for pending in pendingUpdates {
            guard let message = messages.first(where: { $0.id == pending.messageId }),
                  let block = message.blocks.first(where: { $0.id == pending.blockId }) else { continue }
            applyStreamingTextBlockContent(
                block,
                text: pending.text,
                parsedMarkdown: pending.parsedMarkdown,
                cacheAttributedString: pending.cacheAttributedString
            )
        }
        if isNearBottom {
            scrollToBottomSignal.send()
        }
    }

    // MARK: - Slash Commands (stored state)
    // Method bodies live in AIChatViewModel+SlashCommands.swift; stored
    // properties must stay on the class itself (extensions can't add them).

    /// Set by the slash-command handler to ask the view to show the
    /// "Clear Chat?" confirmation alert. The view mirrors this into its
    /// local @State and resets the flag back to false on observation.
    @Published var clearChatConfirmRequested = false

    /// Text saved before showing slash menu over existing input.
    /// When non-nil, the menu was opened via the "/" button while text existed —
    /// executing a command should restore this text instead of clearing it.
    var savedInputBeforeSlash: String?
    /// Caret offset saved alongside `savedInputBeforeSlash`.
    var savedCaretBeforeSlash: Int?

    // MARK: - File Mention (`@`) Menu

    /// Whether the `@`-file menu is visible.
    @Published var showMentionMenu = false
    /// Currently highlighted row index (-1 = none).
    @Published var mentionSelectedIndex: Int = -1
    /// Live filter extracted from the active `@<token>` in the input.
    @Published var mentionFilter: String = ""
    /// The `@` character's UTF-16 offset in `inputText` for the currently active trigger.
    /// Used to replace the token on selection. -1 when inactive.
    var mentionAnchor: Int = -1



    /// In-memory thinking level for sessions not yet persisted (no sessionId yet).
    /// nil = user hasn't explicitly set one; fall back to the default group's level.
    private var pendingThinkingLevel: ThinkingLevel?

    /// Set thinking level — persists if session exists, otherwise holds in memory.
    func setThinkingLevel(_ level: ThinkingLevel) {
        let maxLevel = resolveCurrentEntry()?.effectiveMaxThinkingLevel ?? .xhigh
        let clamped = level == .off ? level : min(level, maxLevel)
        if let sid = sessionId {
            var cfg = ProviderConfigStore.shared.inferenceConfig(for: sid) ?? SessionInferenceConfig()
            cfg.thinkingLevel = clamped
            ProviderConfigStore.shared.setInferenceConfig(cfg, for: sid)
        } else {
            pendingThinkingLevel = clamped
        }
        if !transitionSuspended { objectWillChange.send() }
    }

    /// Default thinking level inherited from the active default primary group, or .off.
    private var defaultGroupThinkingLevel: ThinkingLevel {
        let store = ProviderConfigStore.shared
        guard let groupId = store.defaultPrimaryGroupId,
              let group = store.group(for: groupId) else { return .off }
        return group.defaultThinkingLevel ?? .off
    }

    /// Current thinking level for this session.
    ///
    /// [T-ios-stale-thinking-level-display] The stored value can exceed the
    /// active model's current ceiling — e.g. a session/group config persisted
    /// `.ultra` (or `.max`) while a model's `effectiveMaxThinkingLevel` was higher,
    /// then the catalog cap was tightened (sol/terra .ultra→.max, etc.). The write
    /// path (`setThinkingLevel`) clamps, but a value persisted under a looser cap
    /// is never rewritten, so a raw read would surface a level the model no longer
    /// supports — the "is thinking…" pill would show "Ultra/极限" when the user's
    /// effective (and wire-sent) level is really "Max". Clamp on read so the
    /// displayed level always matches what will actually be requested (the request
    /// path re-clamps per-model too — AgentProvider: min(level, catalogMax)).
    var currentThinkingLevel: ThinkingLevel {
        let stored: ThinkingLevel = {
            if let sid = sessionId, let cfg = ProviderConfigStore.shared.inferenceConfig(for: sid) {
                return cfg.thinkingLevel
            }
            return pendingThinkingLevel ?? defaultGroupThinkingLevel
        }()
        guard stored.isEnabled else { return stored }
        let maxLevel = resolveCurrentEntry()?.effectiveMaxThinkingLevel ?? .xhigh
        return min(stored, maxLevel)
    }

    /// Flush pending thinking level to the store once sessionId is available.
    /// Returns `true` if a user-chosen level was written, so the caller can
    /// keep the group-default seeding (createInitialBinding) from clobbering it.
    ///
    /// [T-first-msg-thinking-level-clobber] Note the `pending != .off` guard:
    /// `.off` is indistinguishable from "unset" in SessionInferenceConfig, so a
    /// user who explicitly picks Off before the first send still inherits the
    /// group default. That pre-existing limitation is unchanged here — this fix
    /// only stops a real (enabled) override from being overwritten.
    @discardableResult
    func flushPendingThinkingLevel() -> Bool {
        guard let pending = pendingThinkingLevel, pending != .off, let sid = sessionId else { return false }
        var cfg = ProviderConfigStore.shared.inferenceConfig(for: sid) ?? SessionInferenceConfig()
        cfg.thinkingLevel = pending
        ProviderConfigStore.shared.setInferenceConfig(cfg, for: sid)
        pendingThinkingLevel = nil
        return true
    }

    /// Append an ephemeral system info message (not sent to LLM).
    func appendSystemInfo(_ text: String, icon: String) {
        let msg = ChatMessage(role: .systemInfo, content: text)
        msg.systemIcon = icon
        messages.append(msg)
    }

}

enum LLMProviderError: LocalizedError {
    case noCredentials

    var errorDescription: String? {
        switch self {
        case .noCredentials: return "No API credentials available"
        }
    }
}

