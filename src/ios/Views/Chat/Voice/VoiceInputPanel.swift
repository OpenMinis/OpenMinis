import SwiftUI
import UIKit
import AVFoundation
import Speech

// MARK: - VoiceInputViewModel
//
// Shared VAD + ASR state machine. Drives the inline voice input
// (InlineVoiceInputView); resolves the ASR provider via VoiceProviderResolver.

/// [T-voice-input-failure-feedback] (#144/#134/#142) Why a voice utterance
/// produced no text.
///
/// Every case here used to be an early `return` with at most a `VoiceLog` line:
/// the spinner stopped, the transcript did not change, and the user could not
/// tell "I spoke too briefly" from "the ASR server 401'd" from "the text was
/// dropped because I was editing". Naming the failures is what makes those
/// distinguishable — both to the user (toast) and to us (log grep).
///
/// Modelled on Android's `RecognitionError`
/// (SystemSpeechRecognitionEngine.kt:345-382), which already had this
/// distinction — notably a real `NO_MATCH` state, which iOS lacked.
enum VoiceInputFailure: Equatable {
    /// Capture stopped with less than `minSegmentSeconds` of audio.
    case tooShort(seconds: Double)
    /// Audio was sent and the request SUCCEEDED, but the transcript was empty.
    /// The single most confusing failure: nothing errored, nothing appeared.
    case noSpeechRecognized
    /// Recognition result arrived while the user was hand-editing the
    /// transcript, so appending would have clobbered their edit.
    case droppedWhileEditing
    /// Microphone permission denied.
    case micPermissionDenied
    /// Mic is granted but Speech recognition is not — on-device ASR cannot run.
    /// Previously indistinguishable from a generic transcribe error.
    case speechPermissionDenied
    /// No usable ASR provider could be constructed (missing API key / bad
    /// config). Previously surfaced as a bogus *network* error.
    case noUsableProvider
    /// The provider chain ran and every candidate threw.
    case transcribeFailed(String)
    /// Starting the audio engine failed.
    case startFailed(String)

    /// User-facing, already-localized. Kept short — these are toast lines.
    var message: String {
        switch self {
        case .tooShort:
            return AppLocalized("Too short — hold on and speak a little longer",
                          comment: "Voice input: recording below the minimum length, discarded")
        case .noSpeechRecognized:
            return AppLocalized("No speech recognized — please try again",
                          comment: "Voice input: ASR succeeded but returned an empty transcript")
        case .droppedWhileEditing:
            return AppLocalized("Speech ignored while editing — finish editing first",
                          comment: "Voice input: result discarded because the user was editing the transcript")
        case .micPermissionDenied:
            return AppLocalized("Microphone access denied — enable it in Settings",
                          comment: "Voice input: microphone permission denied")
        case .speechPermissionDenied:
            return AppLocalized("Speech recognition access denied — enable it in Settings",
                          comment: "Voice input: Speech recognition permission denied")
        case .noUsableProvider:
            return AppLocalized("No usable speech-to-text model — check the voice settings",
                          comment: "Voice input: no ASR provider could be built (missing key/config)")
        case .transcribeFailed(let detail):
            return detail
        case .startFailed(let detail):
            return detail
        }
    }

    /// SF Symbol for the toast.
    var systemImage: String {
        switch self {
        case .noSpeechRecognized, .tooShort: return "waveform.slash"
        case .micPermissionDenied, .speechPermissionDenied: return "mic.slash.fill"
        case .droppedWhileEditing: return "pencil.slash"
        default: return "exclamationmark.triangle.fill"
        }
    }

    /// Stable tag for log grepping — the field diagnosis handle for #134/#142.
    var logTag: String {
        switch self {
        case .tooShort: return "tooShort"
        case .noSpeechRecognized: return "noSpeechRecognized"
        case .droppedWhileEditing: return "droppedWhileEditing"
        case .micPermissionDenied: return "micPermissionDenied"
        case .speechPermissionDenied: return "speechPermissionDenied"
        case .noUsableProvider: return "noUsableProvider"
        case .transcribeFailed: return "transcribeFailed"
        case .startFailed: return "startFailed"
        }
    }
}

/// [T-voice-input-failure-feedback] (#144) Thrown when every ASR candidate was
/// skipped because no provider could be constructed (missing API key / bad
/// config). A distinct type so the catch site can classify it as
/// `.noUsableProvider` rather than a generic transcribe failure — this used to
/// surface as `URLError(.cannotLoadFromNetwork)`, i.e. a *network* error for
/// what is really a *configuration* one.
struct NoUsableASRProviderError: LocalizedError {
    var errorDescription: String? { VoiceInputFailure.noUsableProvider.message }
}

@MainActor
final class VoiceInputViewModel: ObservableObject {

    enum State: Equatable {
        case waiting     // panel shown, awaiting speech
        case recording   // VAD detected speech
        case processing  // audio sent, awaiting ASR
        case result      // recognition complete
    }

    @Published var state: State = .waiting {
        didSet {
            if state != .recording {
                cancelTipCycle()
            }
            if (state == .waiting && !transcript.isEmpty) || state == .result {
                beginResultTipCycle()
            }
        }
    }
    @Published var transcript: String = ""
    @Published var waveformLevels: [Float] = Array(repeating: 0.1, count: 20)
    @Published var rippleScale: [CGFloat] = [1.0, 1.0, 1.0]
    /// Inline mode: user double-tapped the transcript to correct it by keyboard.
    /// While editing, VAD is paused so new speech doesn't clobber manual edits.
    @Published var isEditingTranscript = false

    /// [voice-correction §5] Transcript as it was when the user entered edit mode. The diff
    /// against the post-edit text is the correction signal.
    /// [T-voice-correction-productionize] Promoted from DEBUG-only: persistence
    /// is opt-in via VoiceCorrectionCollectionConsent (write-layer gate).
    private var editSnapshot: String = ""
    /// True when microphone permission was denied — surfaced so the UI can
    /// prompt the user to enable it in Settings instead of silently doing nothing.
    @Published var permissionDenied = false
    /// Non-nil when starting the mic engine failed (e.g. AVAudioSession / VAD
    /// init error) — surfaced so a silent failure isn't a dead, mute button.
    @Published var startError: String?
    /// Non-nil when the last transcription request failed — shown as a tip so the
    /// user sees WHY (e.g. HTTP 404 / auth) instead of silent no-text.
    @Published var transcribeError: String?
    @Published var retryCountdown: Int?
    @Published var canManualRetry = false

    /// [T-voice-input-failure-feedback] (#144) Last reason a voice utterance
    /// produced no text. Published so the panel can reflect it inline; also
    /// toasted by `report(_:)` so it is visible in COMPACT mode, where the
    /// existing `stateLabel` and error pill do not render at all (that
    /// expanded-only limitation is why these failures read as "nothing
    /// happened").
    @Published var lastFailure: VoiceInputFailure?

    /// Surface a failure: log it with a greppable tag and toast it.
    ///
    /// Toast rather than an inline row on purpose — an inline pill inserts a
    /// row into the panel VStack and shifts the mic button, which users already
    /// complained about (see the note in InlineVoiceInputView's error pill).
    ///
    /// `silent: true` records the failure for diagnostics without a toast, for
    /// paths that are expected during normal use and would otherwise nag.
    func report(_ failure: VoiceInputFailure, silent: Bool = false) {
        lastFailure = failure
        VoiceLog.log("[voice-failure] \(failure.logTag): \(failure.message)")
        guard !silent else { return }
        MinisToast.show(failure.message, duration: 2.2, systemImage: failure.systemImage)
    }
    private var retryAudioData: Data?
    private var retryTimer: Timer?
    private var isAutoRetry = false

    /// Total recording session cap (5 minutes). When continuous recording
    /// (from first mic start) exceeds this, force a full auto-stop.
    private static let maxTotalRecordingSeconds: TimeInterval = 300
    private var totalRecordingTimer: Timer?
    /// Preferred recognition language code (e.g. "zh", "en"). Defaults to the
    /// last language the user picked (persisted), falling back to the system's
    /// first preferred language. Persisted on change so a later cold start
    /// reuses the user's choice instead of reverting to the system order — the
    /// device's preferred order can put Cantonese (zh-HK) first and garble
    /// Mandarin, so we honour the explicit pick.
    @Published var language: String = VoiceLanguages.lastUsed {
        didSet {
            guard language != oldValue else { return }
            VoiceLanguages.lastUsed = language
        }
    }
    /// Recording-label phase: on recording start we first show "Tap to pause"
    /// (so the pause affordance is obvious), then after a short delay switch to
    /// the steady "Listening…" label and keep it there. False = "Tap to pause".
    @Published var recordingTipIndex: Int = 0
    @Published var resultTipIndex: Int = 0
    private var tipCycleTimer: Timer?
    /// Monotonic token so a stale 3s delay can't flip the label after the user
    /// stopped and restarted recording in the meantime.
    private var listeningLabelToken = 0
    /// Bumped after a send (clearAndRearm) so the inline view can collapse from
    /// expand → compact mode once the recognized text has been dispatched.
    @Published var collapseAfterSendToken = 0

    private let logger = AppLogger(category: "VoicePanel")
    private let vad = VoiceActivityDetector()
    private var inputProvider: (any VoiceInputCapable)?
    /// The resolved model entry — passed to VoiceInputRequest so the provider can
    /// detect chat-based ASR (audio+text multimodal models vs dedicated Whisper).
    private var inputResolvedModel: LLMModel?
    /// Ordered ASR fail-over candidates from the configured input group (override
    /// first, then group members per the group's routing strategy). Empty = use
    /// the offline System engine.
    private var inputCandidates: [ModelEntry] = []
    /// STICKY fail-over (mirrors VoiceOutputPlayer and the text agent loop): once
    /// a segment succeeds on a model, later segments start there — only advance
    /// when IT fails. Cleared when the model leaves the candidate set.
    private var stickyInputEntryId: String?
    /// Per-capture-session seed for the group's loadBalance rotation, re-rolled
    /// each time the panel prepares so sessions spread across group members.
    private var inputLoadBalanceSeed = 0

    /// Auto-stop the mic after this many seconds with no detected speech, so a
    /// forgotten-open mic doesn't record (and drain battery) indefinitely.
    private static let idleTimeout: TimeInterval = 30
    /// Auto-stop the mic this long after the app is backgrounded.
    private static let backgroundTimeout: TimeInterval = 15
    private var idleTimer: Timer?
    private var backgroundTimer: Timer?
    private var didObserveLifecycle = false

    /// Inline mode: append each VAD segment to the running transcript instead of
    /// replacing, and notify the host so the input box can mirror the text.
    var accumulate = false
    var onTranscript: ((String) -> Void)?

    init() {
        vad.delegate = self
        observeLifecycle()
    }

    deinit {
        idleTimer?.invalidate()
        backgroundTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Idle / background auto-stop

    private func observeLifecycle() {
        guard !didObserveLifecycle else { return }
        didObserveLifecycle = true
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(appDidEnterBackground),
                       name: UIApplication.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(appWillEnterForeground),
                       name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    /// (Re)start the no-speech idle timer. Called when capture starts and reset
    /// every time speech is detected, so the 30s only elapses during silence.
    private func resetIdleTimer() {
        idleTimer?.invalidate()
        guard vad.isRunning else { return }
        idleTimer = Timer.scheduledTimer(withTimeInterval: Self.idleTimeout, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.vad.isRunning else { return }
                if self.vad.isSpeaking {
                    VoiceLog.log("idle timer fired but speech is active (t=\(String(format: "%.1f", self.vad.runningDuration))s) — rescheduling")
                    self.resetIdleTimer()
                    return
                }
                VoiceLog.log("idle \(Int(Self.idleTimeout))s with no speech — auto-stopping mic")
                self.stopListening()
            }
        }
    }

    private func cancelIdleTimer() {
        idleTimer?.invalidate(); idleTimer = nil
    }

    @objc private func appDidEnterBackground() {
        guard vad.isRunning else { return }
        backgroundTimer?.invalidate()
        backgroundTimer = Timer.scheduledTimer(withTimeInterval: Self.backgroundTimeout, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.vad.isRunning else { return }
                VoiceLog.log("backgrounded \(Int(Self.backgroundTimeout))s — auto-stopping mic")
                self.stopListening()
            }
        }
    }

    @objc private func appWillEnterForeground() {
        backgroundTimer?.invalidate(); backgroundTimer = nil
    }

    // MARK: - Control

    /// Re-resolve the ASR provider (e.g. after the user switches engine).
    func refreshInputProvider() {
        resolveInputCandidates(reseed: false)
    }

    /// Resolve the fail-over candidate chain and point `inputProvider` /
    /// `inputResolvedModel` at the ACTIVE candidate (sticky first). `reseed`
    /// re-rolls the loadBalance rotation — done once per panel prepare so a
    /// load-balanced group picks a (possibly) different starting member per
    /// capture session, while mid-session refreshes keep the current rotation.
    private func resolveInputCandidates(reseed: Bool) {
        if reseed { inputLoadBalanceSeed = Int.random(in: 0..<Int.max) }
        inputCandidates = VoiceProviderResolver.resolvedInputCandidates(loadBalanceSeed: inputLoadBalanceSeed)
        if let sticky = stickyInputEntryId, !inputCandidates.contains(where: { $0.id == sticky }) {
            stickyInputEntryId = nil
        }
        let active = activeInputEntry()
        inputProvider = active.flatMap { VoiceProviderResolver.inputProvider(for: $0) }
            ?? SystemVoiceProvider.shared
        inputResolvedModel = active?.model
        VoiceLog.log("ASR candidates: [\(inputCandidates.map { $0.model.displayName }.joined(separator: ", "))] active=\(active?.model.displayName ?? "System") sticky=\(stickyInputEntryId ?? "none")")
    }

    /// The candidate the next segment will try FIRST (sticky, else chain head).
    private func activeInputEntry() -> ModelEntry? {
        if let sticky = stickyInputEntryId,
           let e = inputCandidates.first(where: { $0.id == sticky }) { return e }
        return inputCandidates.first
    }

    /// Candidates in try-order for one transcription: the sticky model first,
    /// then the rest of the chain wrapped around (one full round per segment).
    private func orderedInputCandidates() -> [ModelEntry] {
        guard let sticky = stickyInputEntryId,
              let i = inputCandidates.firstIndex(where: { $0.id == sticky }), i > 0 else {
            return inputCandidates
        }
        return Array(inputCandidates[i...] + inputCandidates[..<i])
    }

    /// Called when the inline voice view appears. We do NOT auto-start the mic:
    /// the panel shows "Tap to speak" and the user taps the mic to begin (so the
    /// tap is meaningful and any permission prompt fires on an explicit action).
    /// Here we only resolve the ASR provider and pre-check permission state.
    func prepare() {
        // First entry into voice mode with nothing configured → auto-create default
        // Voice Input (System ASR online+offline) and Voice Output (System Voice
        // Auto) groups and bind them, so there's an explicit, editable selection
        // instead of a silent fallback. No-op once the user has configured either.
        ProviderConfigStore.shared.ensureDefaultVoiceInputGroup()
        ProviderConfigStore.shared.ensureDefaultVoiceOutputGroup()
        resolveInputCandidates(reseed: true)
        permissionDenied = (VoiceActivityDetector.microphonePermission == .denied)
        state = .waiting
        // Warm up the offline recognizer so the first utterance isn't dropped.
        prewarmIfSystem()
    }

    /// Pre-warm the on-device recognizer when the active ASR is the System
    /// provider (no-op for cloud providers). Avoids the cold-start miss where
    /// the first request or two produce no result.
    private func prewarmIfSystem() {
        if let sys = inputProvider as? SystemVoiceProvider {
            sys.prewarm(language: language)
        }
    }

    /// True when the resolved ASR is Apple's on-device recognizer — the only
    /// case where Speech permission matters. Mirrors `prewarmIfSystem`'s test.
    /// A nil `inputProvider` also counts: `transcribeWithFailover` falls back to
    /// `SystemVoiceProvider.shared` when the candidate chain is empty.
    private var willUseSystemASR: Bool {
        inputProvider == nil || inputProvider is SystemVoiceProvider
    }

    func stopListening() {
        vad.stop()
        state = .waiting
        listeningLabelToken &+= 1
        cancelTipCycle()
        recordingTipIndex = 0
        cancelIdleTimer()
        backgroundTimer?.invalidate(); backgroundTimer = nil
        // Capture stopped — reply TTS may resume.
        VoiceModePreference.shared.isCapturing = false
    }

    /// Mic button: start listening on the first tap (requesting permission if
    /// needed), then toggle pause/resume. The VAD is otherwise always on
    /// (continuous dictation) — segments transcribe as silence is detected while
    /// capture keeps running, so we never stop it just to transcribe.
    func handleMainButtonTap() {
        VoiceLog.log("[VoiceInputDebug] handleMainButtonTap called, state=\(state), vad.isRunning=\(vad.isRunning), isSpeaking=\(vad.isSpeaking), runningDuration=\(String(format: "%.1f", vad.runningDuration))s, ttsPlaying=\(VoiceOutputPlayer.shared.isPlaying), micPerm=\(VoiceActivityDetector.microphonePermission)")
        if vad.isRunning {
            let wasSpeaking = vad.isSpeaking
            let duration = vad.runningDuration
            // Pausing: flush any in-progress speech to recognition first (so the
            // audio captured up to the pause isn't lost). vad.flush delivers its
            // segment asynchronously onto the main queue, so flush the merged
            // pending buffer on the next tick — after that segment has landed.
            vad.flush()
            // FALLBACK: If VAD never detected speech but mic ran >1s, use raw audio
            if !wasSpeaking && duration > 1.0 {
                VoiceLog.log("VAD fallback: isSpeaking was false but mic ran \(String(format: "%.1f", duration))s — flushing raw buffer")
                vad.flushRawFallback()
            }
            DispatchQueue.main.async { [weak self] in self?.flushPendingSegments() }
            vad.stop()
            state = .waiting
            listeningLabelToken &+= 1
            cancelTipCycle()
        recordingTipIndex = 0
            VoiceModePreference.shared.isCapturing = false
            cancelTotalRecordingTimer()
            return
        }
        let mic = VoiceActivityDetector.microphonePermission
        VoiceLog.log("mic permission: \(mic), speech permission: \(SystemVoiceProvider.speechAuthStatus)")
        switch mic {
        case .granted:
            // System (offline) ASR also needs Speech permission — request it
            // (no-op once granted) before starting capture.
            Task {
                await self.ensureSpeechAuthReportingDenial()
                self.prewarmIfSystem()
                self.startVAD()
            }
        case .undetermined:
            Task { await requestPermissionAndStart() }
        case .denied:
            permissionDenied = true
            report(.micPermissionDenied)
        }
    }

    /// Stop capture and return the panel to idle.
    ///
    /// [T-ios-voice-keyboard-text-carry] `clearTranscript: false` is used when
    /// switching voice → keyboard: the composer's inputText mirrors the
    /// transcript live, so clearing the transcript here would wipe the
    /// just-dictated text out of the input box before the panel unmounts.
    /// Leaving it lets the text carry over; the next voice-mode entry re-seeds
    /// the transcript from inputText anyway (InlineVoiceInputView.onAppear).
    func reset(clearTranscript: Bool = true) {
        vad.stop()
        if clearTranscript { transcript = "" }
        // [T-voice-panel-gap-after-edit] Leaving voice mode (mic/"T" toggle) while
        // the transcript editor was open used to leave this flag TRUE, so the next
        // entry into voice mode came back straight into edit mode with a stale
        // keyboard. The panel's isEditingTranscript observer also turns this into
        // the authoritative "release the keyboard" signal, so clearing it here is
        // what un-strands the phantom keyboard inset on that path.
        // [T-voice-bg-fg-gap] Site-tagged: the observer log alone can't tell
        // WHICH path flipped the flag (the 12:12:41/43 flap in the device log
        // was unattributable). Every assignment now names its site.
        if isEditingTranscript { VoiceLog.log("[voice-edit-site] reset(clearTranscript:\(clearTranscript)) true→false") }
        isEditingTranscript = false
        state = .waiting
        cancelIdleTimer()
        cancelTotalRecordingTimer()
        backgroundTimer?.invalidate(); backgroundTimer = nil
        // Capture stopped — reply TTS may resume.
        VoiceModePreference.shared.isCapturing = false
    }

    // MARK: - Private

    private func startVAD() {
        // Mutually exclusive with TTS playback: stop any reply being read aloud so
        // it doesn't echo into the mic / fight the audio session, and mark capture
        // active so further reply TTS is suppressed until we stop.
        // [VoiceInputDebug] Record whether TTS was actually speaking when the mic
        // was tapped — that is the case where the session has to switch
        // .playback → .record, which is what used to lose the first tap.
        let ttsWasPlaying = VoiceOutputPlayer.shared.isPlaying
        VoiceLog.log("[VoiceInputDebug] startVAD enter ttsPlaying=\(ttsWasPlaying) vad.isRunning=\(vad.isRunning)")
        VoiceOutputPlayer.shared.stopAll()
        VoiceLog.log("[VoiceInputDebug] TTS stopAll() returned (synchronous); session release is async")
        VoiceModePreference.shared.isCapturing = true
        do {
            // System ASR (SFSpeechRecognizer) limits each recognition request
            // to ~60s — flush at 59s (safety margin) so each chunk stays within
            // Apple's limit, but keep the mic running for the next chunk.
            // Cloud providers handle long audio natively — no per-segment cap;
            // audio accumulates until 5s silence or the 5-minute total cap.
            let isSystemASR = inputProvider is SystemVoiceProvider
            vad.maxSegmentSeconds = isSystemASR ? 59 : Self.maxTotalRecordingSeconds
            try vad.start()
            state = .recording
            startError = nil
            transcribeError = nil
            lastFailure = nil
            beginRecordingLabelCycle()
            startRippleAnimation()
            resetIdleTimer()
            startTotalRecordingTimer()
            VoiceLog.log("[VoiceInputDebug] startVAD OK — state=.recording")
        } catch {
            VoiceModePreference.shared.isCapturing = false
            logger.error("Failed to start VAD: \(error.localizedDescription)")
            // [VoiceInputDebug] This is the branch that produced the reported
            // "first tap only stopped the speech" symptom: TTS was stopped above,
            // then vad.start() threw, so no recording began and the panel fell
            // back to .waiting with no visible error state change.
            VoiceLog.log("[VoiceInputDebug] startVAD FAILED — recording NOT started, ttsWasPlaying=\(ttsWasPlaying), error=\(error.localizedDescription)")
            startError = error.localizedDescription
            state = .waiting
            // [T-voice-input-failure-feedback] (#144) `startError` only renders
            // in expandedBody; in compact mode this failed start looked exactly
            // like a dead mic button. Toast so it is visible either way.
            report(.startFailed(error.localizedDescription))
        }
    }

    private func requestPermissionAndStart() async {
        let granted = await VoiceActivityDetector.requestMicrophonePermission()
        VoiceLog.log("mic permission request result: granted=\(granted)")
        guard granted else {
            permissionDenied = true
            report(.micPermissionDenied)
            return
        }
        await ensureSpeechAuthReportingDenial()
        prewarmIfSystem()
        startVAD()
    }

    /// [T-voice-input-failure-feedback] (#144) Request Speech authorization and
    /// SAY SO when it is refused.
    ///
    /// The result of `ensureSpeechAuthorization()` used to be discarded at both
    /// call sites. With the mic granted but Speech denied, capture started
    /// normally and only the on-device recognizer failed — every utterance
    /// surfaced the generic `unsupported("Speech recognition permission not
    /// granted")` string, never an actionable "grant Speech permission".
    ///
    /// Only reported when the system ASR is actually the one that would run;
    /// with a remote (Whisper-style) provider configured, Speech permission is
    /// irrelevant and warning about it would be noise.
    private func ensureSpeechAuthReportingDenial() async {
        let ok = await SystemVoiceProvider.ensureSpeechAuthorization()
        guard !ok, willUseSystemASR else { return }
        report(.speechPermissionDenied)
    }

    private func beginRecordingLabelCycle() {
        recordingTipIndex = 0
        listeningLabelToken &+= 1
        let token = listeningLabelToken
        tipCycleTimer?.invalidate()
        tipCycleTimer = Timer.scheduledTimer(withTimeInterval: 4.0, repeats: true) { [weak self] _ in
            guard let self, self.listeningLabelToken == token, self.state == .recording else {
                self?.tipCycleTimer?.invalidate()
                return
            }
            withAnimation(.easeInOut(duration: 0.25)) {
                self.recordingTipIndex += 1
            }
        }
    }

    func beginResultTipCycle() {
        resultTipIndex = 0
        tipCycleTimer?.invalidate()
        tipCycleTimer = Timer.scheduledTimer(withTimeInterval: 4.0, repeats: true) { [weak self] _ in
            guard let self, self.state == .result || (!self.transcript.isEmpty && self.state == .waiting) else {
                self?.tipCycleTimer?.invalidate()
                return
            }
            withAnimation(.easeInOut(duration: 0.25)) {
                self.resultTipIndex += 1
            }
        }
    }

    private func cancelTipCycle() {
        tipCycleTimer?.invalidate()
        tipCycleTimer = nil
    }

    private func startRippleAnimation() {
        for i in 0..<3 {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.3) { [weak self] in
                withAnimation(.easeOut(duration: 1.2).repeatForever(autoreverses: false)) {
                    self?.rippleScale[i] = 1.4
                }
            }
        }
    }

    /// Count of in-flight transcription tasks. Drives the `isTranscribing`
    /// loading indicator WITHOUT changing the capture state — so the waveform
    /// keeps showing (mic never stops) while a loading ring spins over it.
    private var pendingTranscriptions = 0 {
        didSet { isTranscribing = pendingTranscriptions > 0 }
    }
    /// True while ≥1 transcription is running — overlays a spinner on the mic.
    @Published var isTranscribing = false
    private var pendingTranscriptionTasks: [Task<Void, Never>] = []

    /// Minimum ACCUMULATED speech before a (2.5s-silence-closed) segment is sent
    /// to recognition. When a silence gap closes a segment: if the accumulated
    /// audio is ≥2s we transcribe; otherwise we hold it and wait for the next
    /// silence gap to merge more — unless the hold reaches the force-flush window.
    private static let minSegmentSeconds: Double = 2.0
    /// If pending (sub-5s) audio is held this long with no new speech, force it
    /// through recognition anyway (ignoring the 5s minimum) so it isn't stuck.
    private static let pendingForceFlushSeconds: TimeInterval = 5.0
    /// Sub-threshold (very short) WAV segments held to merge with the next.
    private var pendingSegments: [Data] = []
    /// Fires `pendingForceFlushSeconds` after the last held segment to force it.
    private var pendingFlushTimer: Timer?
    /// Bumped on send/clear; in-flight transcriptions tagged with an older value
    /// discard their result so they can't repopulate the emptied composer.
    private var transcriptGeneration = 0

    #if DEBUG
    /// VAD context for the debug voice-input capture (`debug.voiceInputs`),
    /// stashed at each merge site because `pendingSegments` is cleared before
    /// `transcribeAudio` runs. [T-debug-voice-input-capture]
    private var lastMergeSegmentCount = 0
    private var lastMergeEndReason: String?
    #endif

    /// A VAD segment arrived (5 s silence closed it). Auto-stop: flush all
    /// pending audio to recognition and return to waiting, equivalent to a
    /// manual tap-to-stop. Only fires the full stop if accumulated speech
    /// meets the minimum-length gate; otherwise the short audio is discarded
    /// and the panel simply returns to idle.
    private func handleSegment(_ audioData: Data, reason: SegmentEndReason) {
        guard !audioData.isEmpty else { return }
        if isEditingTranscript { return }

        pendingSegments.append(audioData)
        let totalSeconds = pendingSegments.reduce(0.0) { $0 + Self.wavDurationSeconds($1) }
        VoiceLog.log("segment received reason=\(reason); pending speech=\(String(format: "%.1f", totalSeconds))s (need \(Int(Self.minSegmentSeconds))s)")

        cancelPendingForceFlush()

        switch reason {
        case .silenceDetected:
            vad.stop()
            state = .waiting
            listeningLabelToken &+= 1
            cancelTipCycle()
        recordingTipIndex = 0
            VoiceModePreference.shared.isCapturing = false
            cancelTotalRecordingTimer()

            guard totalSeconds >= Self.minSegmentSeconds else {
                VoiceLog.log("silence auto-stop: below min threshold, discarding \(String(format: "%.1f", totalSeconds))s")
                pendingSegments.removeAll(keepingCapacity: true)
                // [T-voice-input-failure-feedback] (#134) Speaking under
                // minSegmentSeconds then falling silent used to discard the
                // audio with no UI change at all — the single likeliest cause of
                // "I tapped the mic, said something, and got nothing".
                // Only speak up if the user actually produced some audio;
                // a bare mic-open/mic-close with ~zero capture is not a failure.
                if totalSeconds > 0.3 { report(.tooShort(seconds: totalSeconds)) }
                return
            }
            #if DEBUG
            noteMergeContext(count: pendingSegments.count, reason: reason)
            #endif
            let merged = Self.mergeWavSegments(pendingSegments)
            pendingSegments.removeAll(keepingCapacity: true)
            transcribeMerged(merged)

        case .maxLengthReached:
            #if DEBUG
            noteMergeContext(count: pendingSegments.count, reason: reason)
            #endif
            let merged = Self.mergeWavSegments(pendingSegments)
            pendingSegments.removeAll(keepingCapacity: true)
            transcribeMerged(merged)

        case .manualFlush:
            guard totalSeconds >= Self.minSegmentSeconds else {
                schedulePendingForceFlush()
                return
            }
            #if DEBUG
            noteMergeContext(count: pendingSegments.count, reason: reason)
            #endif
            let merged = Self.mergeWavSegments(pendingSegments)
            pendingSegments.removeAll(keepingCapacity: true)
            transcribeMerged(merged)
        }
    }

    #if DEBUG
    /// Stash the VAD merge context for the debug capture, read back inside
    /// `transcribeAudio`. DEBUG-only — the whole voice-input capture feature is
    /// compiled out of Release. [T-debug-voice-input-capture]
    private func noteMergeContext(count: Int, reason: SegmentEndReason?) {
        lastMergeSegmentCount = count
        lastMergeEndReason = reason.map { "\($0)" }
    }
    #endif

    // MARK: - Total Recording Cap

    private func startTotalRecordingTimer() {
        totalRecordingTimer?.invalidate()
        totalRecordingTimer = Timer.scheduledTimer(withTimeInterval: Self.maxTotalRecordingSeconds, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.vad.isRunning else { return }
                VoiceLog.log("total recording cap (\(Int(Self.maxTotalRecordingSeconds))s) reached — full auto-stop")
                self.vad.flush()
                DispatchQueue.main.async { [weak self] in self?.flushPendingSegments() }
                self.vad.stop()
                self.state = .waiting
                self.listeningLabelToken &+= 1
                self.cancelTipCycle()
                self.recordingTipIndex = 0
                VoiceModePreference.shared.isCapturing = false
                self.totalRecordingTimer = nil
            }
        }
    }

    private func cancelTotalRecordingTimer() {
        totalRecordingTimer?.invalidate()
        totalRecordingTimer = nil
    }

    // MARK: - Network Error Retry

    private static func isTransientNetworkError(_ error: Error) -> Bool {
        let nsError = error as NSError
        guard nsError.domain == NSURLErrorDomain else { return false }
        switch nsError.code {
        case NSURLErrorNetworkConnectionLost, NSURLErrorNotConnectedToInternet,
             NSURLErrorTimedOut, NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost,
             NSURLErrorDNSLookupFailed:
            return true
        default:
            return false
        }
    }

    private func startRetryCountdown() {
        retryCountdown = 5
        canManualRetry = false
        retryTimer?.invalidate()
        retryTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] timer in
            Task { @MainActor in
                guard let self else { timer.invalidate(); return }
                guard let count = self.retryCountdown, count > 1 else {
                    timer.invalidate()
                    self.retryCountdown = nil
                    self.executeRetry()
                    return
                }
                self.retryCountdown = count - 1
            }
        }
    }

    private func executeRetry() {
        guard let data = retryAudioData else { return }
        isAutoRetry = true
        retryAudioData = nil
        transcribeError = nil
        transcribeAudio(data)
    }

    func manualRetry() {
        guard let data = retryAudioData else { return }
        canManualRetry = false
        isAutoRetry = false
        retryAudioData = nil
        transcribeError = nil
        transcribeAudio(data)
    }

    private func schedulePendingForceFlush() {
        cancelPendingForceFlush()
        pendingFlushTimer = Timer.scheduledTimer(withTimeInterval: Self.pendingForceFlushSeconds, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.pendingSegments.isEmpty else { return }
                VoiceLog.log("pending held \(Int(Self.pendingForceFlushSeconds))s with no new speech — force-flushing")
                self.flushPendingSegments()
            }
        }
    }

    private func cancelPendingForceFlush() {
        pendingFlushTimer?.invalidate()
        pendingFlushTimer = nil
    }

    /// Flush whatever sub-threshold audio is pending NOW (e.g. on pause / send),
    /// regardless of the 5 s minimum, so trailing speech isn't lost.
    private func flushPendingSegments() {
        VoiceLog.log("flushPendingSegments: \(pendingSegments.count) pending, total bytes=\(pendingSegments.reduce(0) { $0 + $1.count })")
        cancelPendingForceFlush()
        guard !pendingSegments.isEmpty else { return }
        #if DEBUG
        noteMergeContext(count: pendingSegments.count, reason: nil)
        #endif
        let merged = Self.mergeWavSegments(pendingSegments)
        pendingSegments.removeAll(keepingCapacity: true)
        transcribeMerged(merged)
    }

    /// [T-voice-input-failure-feedback] (#144) Send a merged utterance, or say
    /// so when the merge produced nothing.
    ///
    /// `mergeWavSegments` returns nil when every segment is header-only (≤44
    /// bytes). All four call sites used to be a bare `if let merged { … }` with
    /// no `else`, so that outcome discarded the utterance in total silence.
    private func transcribeMerged(_ merged: Data?) {
        guard let merged else {
            report(.noSpeechRecognized)
            return
        }
        transcribeAudio(merged)
    }

    /// Transcribe ONE (possibly merged) utterance. The VAD engine keeps running
    /// the whole time, so while this is transcribed the next utterance is still
    /// captured. Results append in completion order.
    private func transcribeAudio(_ audioData: Data) {
        guard !audioData.isEmpty else { return }
        // While the user hand-edits the transcript, drop new speech so it can't
        // overwrite their corrections.
        if isEditingTranscript { return }

        // Do NOT change `state` here. Transcription runs in the background while
        // the mic keeps capturing, so the button's visual must keep following the
        // CAPTURE state (waveform while recording), not the recognition state.
        // Only an explicit pause (user taps the mic) changes the visual.
        let feedTs = ProcessInfo.processInfo.systemUptime
        let feedDur = VoiceInputViewModel.wavDurationSeconds(audioData)
        VoiceLog.log(String(format: "feed inference | ts=%.3f dur=%.3fs bytes=%d", feedTs, feedDur, audioData.count))
        pendingTranscriptions += 1
        // Pass the resolved entry's model id EXPLICITLY: without it the
        // multipart body fell back to defaultVoiceInputModel() ("whisper-1"),
        // so the model the user picked never reached the request — an
        // OpenAI-compatible ASR server (Speaches etc.) then resolved the
        // "whisper-1" alias to a model it may not have installed → 404.
        // System ASR: translate the selected Online/Offline model into the
        // on-device flag (nil = auto → provider prefers on-device when supported).
        // Ignored by cloud ASR providers.
        let onDevice: Bool?
        switch VoiceProviderResolver.resolvedSystemInputMode() {
        case .offline: onDevice = true
        case .online:  onDevice = false
        case .auto:    onDevice = nil
        }

        #if DEBUG
        // [T-debug-voice-input-capture] Snapshot the EXACT payload audio about to
        // be sent to the ASR model — the final request.audioData, not a VAD
        // intermediate. Bounded ring (≤5), in-memory only, read via
        // debug.voiceInputs. One lock-guarded append; does not touch the realtime
        // capture/transcription path.
        VoiceInputCapture.shared.record(
            audioData: audioData,
            model: inputResolvedModel?.id,
            language: language,
            onDeviceRecognition: onDevice,
            vadSegmentCount: lastMergeSegmentCount,
            vadEndReason: lastMergeEndReason,
            vadRunningDuration: vad.runningDuration
        )
        #endif

        let generation = transcriptGeneration
        let candidates = orderedInputCandidates()
        let requestLanguage = language
        let task = Task { [weak self] in
            guard let self else { return }
            var text = ""
            // Scoped to THIS attempt on purpose. `self.transcribeError` is only
            // cleared on success, so a failure from an earlier utterance is
            // still set here — testing it instead would suppress the
            // "no speech recognized" report on the next empty-but-successful
            // attempt, re-creating the exact silent failure this fixes.
            var attemptFailed = false
            do {
                try Task.checkCancellation()
                text = try await self.transcribeWithFailover(audioData: audioData,
                                                             candidates: candidates,
                                                             language: requestLanguage,
                                                             onDevice: onDevice)
                VoiceLog.log("transcript: \"\(text)\"")
                self.transcribeError = nil
            } catch is CancellationError {
                VoiceLog.log("transcription task cancelled")
                return
            } catch {
                if Task.isCancelled {
                    VoiceLog.log("transcription task cancelled (post-error)")
                    return
                }
                VoiceLog.log("ERROR transcription failed: \(error.localizedDescription)")
                self.logger.error("Transcription failed: \(error.localizedDescription)")
                self.transcribeError = error.localizedDescription
                attemptFailed = true
                // [T-voice-input-failure-feedback] (#144) The error pill this
                // feeds renders only in expandedBody, so in compact mode the
                // failure was invisible. Toast it too. Suppressed while a retry
                // is pending — the countdown UI already explains that state and
                // a toast per attempt would nag.
                let failure: VoiceInputFailure = error is NoUsableASRProviderError
                    ? .noUsableProvider
                    : .transcribeFailed(error.localizedDescription)
                if !Self.isTransientNetworkError(error) {
                    self.report(failure)
                } else {
                    self.lastFailure = failure
                }
                if Self.isTransientNetworkError(error) && !self.isAutoRetry {
                    self.retryAudioData = audioData
                    self.startRetryCountdown()
                } else if Self.isTransientNetworkError(error) && self.isAutoRetry {
                    self.isAutoRetry = false
                    self.canManualRetry = true
                    self.retryAudioData = audioData
                } else {
                    self.retryAudioData = nil
                    self.canManualRetry = false
                }
            }
            if generation == self.transcriptGeneration,
               !text.isEmpty, !self.isEditingTranscript {
                self.transcript = self.transcript.isEmpty ? text : self.transcript + " " + text
                VoiceLog.log("UI update: text=\"\(self.transcript)\"")
                self.onTranscript?(self.transcript)
            } else if generation != self.transcriptGeneration {
                // Superseded by clearAndRearm()/cancelTranscription() (e.g. the
                // user sent the message while this was in flight). Expected —
                // record it, but don't nag.
                VoiceLog.log("[voice-failure] staleGeneration: result dropped (gen \(generation) != \(self.transcriptGeneration))")
            } else if self.isEditingTranscript {
                self.report(.droppedWhileEditing)
            } else if !attemptFailed {
                // [T-voice-input-failure-feedback] (#134) The request SUCCEEDED
                // and returned an empty string. This is the path that used to
                // vanish without a trace: no throw, no error pill, no transcript
                // change. Whisper returning "", the chat-ASR prompt honoring
                // "output an empty string", and SystemVoiceProvider's 8s
                // watchdog salvaging an empty result all land here.
                self.report(.noSpeechRecognized)
            }
            if self.pendingTranscriptions > 0 {
                self.pendingTranscriptions -= 1
            }
        }
        pendingTranscriptionTasks.removeAll { $0.isCancelled }
        pendingTranscriptionTasks.append(task)
    }

    /// Transcribe one utterance with SEAMLESS fail-over across the candidate
    /// chain (mirrors VoiceOutputPlayer's synth fail-over and the text agent
    /// loop's nextFallback): a candidate that throws advances to the next, one
    /// full round max, and the first success becomes the STICKY model for
    /// subsequent segments. Empty chain = the offline System engine (single
    /// attempt, unchanged behavior). Throws the LAST error only when every
    /// candidate failed — the caller's transient-retry machinery then retries
    /// the whole chain.
    private func transcribeWithFailover(audioData: Data,
                                        candidates: [ModelEntry],
                                        language: String,
                                        onDevice: Bool?) async throws -> String {
        guard !candidates.isEmpty else {
            let request = VoiceInputRequest(audioData: audioData,
                                            model: nil,
                                            language: language,
                                            resolvedModel: nil,
                                            onDeviceRecognition: onDevice)
            return try await SystemVoiceProvider.shared.transcribe(request).text
        }
        var lastError: Error?
        var skippedCandidates = 0
        for (i, entry) in candidates.enumerated() {
            try Task.checkCancellation()
            guard let provider = VoiceProviderResolver.inputProvider(for: entry) else {
                // [T-voice-input-failure-feedback] (#144) A candidate whose
                // provider can't be built (missing API key / bad config) used to
                // be skipped invisibly; if EVERY candidate skipped, `lastError`
                // stayed nil and we threw a bogus URLError(.cannotLoadFromNetwork)
                // — reporting a *network* problem for a *configuration* one.
                skippedCandidates += 1
                VoiceLog.log("ASR fail-over: \(entry.model.displayName) skipped — provider unavailable (missing key/config)")
                continue
            }
            let request = VoiceInputRequest(audioData: audioData,
                                            model: entry.model.id,
                                            language: language,
                                            resolvedModel: entry.model,
                                            onDeviceRecognition: onDevice)
            do {
                let text = try await provider.transcribe(request).text
                if stickyInputEntryId != entry.id {
                    stickyInputEntryId = entry.id
                    inputProvider = provider
                    inputResolvedModel = entry.model
                    if i > 0 {
                        VoiceLog.log("ASR fail-over: switched to \(entry.model.displayName) (sticky)")
                    }
                }
                return text
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
                VoiceLog.log("ASR fail-over: \(entry.model.displayName) failed (\(error.localizedDescription))" +
                             (i + 1 < candidates.count ? " — trying next candidate" : " — chain exhausted"))
            }
        }
        if let lastError { throw lastError }
        // Every candidate was skipped for want of a usable provider — a config
        // problem, so name it as one instead of blaming the network.
        VoiceLog.log("[voice-failure] noUsableProvider: all \(skippedCandidates) candidate(s) unavailable")
        throw NoUsableASRProviderError()
    }

    func cancelTranscription() {
        for task in pendingTranscriptionTasks { task.cancel() }
        pendingTranscriptionTasks.removeAll()
        transcriptGeneration &+= 1
        pendingTranscriptions = 0
        VoiceLog.log("transcription cancelled by user tap")
    }

    /// After a send: clear the accumulated transcript and STOP capturing. The
    /// user stays in voice mode (the inline panel remains), but the mic is no
    /// longer listening — they tap the mic again to dictate the next message.
    func clearAndRearm() {
        // Invalidate any in-flight transcription so a late result can't refill the
        // field after the send (residual-text bug).
        transcriptGeneration &+= 1
        transcript = ""
        if isEditingTranscript { VoiceLog.log("[voice-edit-site] clearAndRearm(send) true→false") }
        isEditingTranscript = false
        // The message was sent — drop any sub-threshold audio still pending so it
        // doesn't transcribe into the next (now-empty) composition.
        cancelPendingForceFlush()
        pendingSegments.removeAll(keepingCapacity: true)
        // Stop the mic on send (and cancel idle/background timers via stopListening).
        if vad.isRunning { stopListening() }
        state = .waiting
        // Signal the inline view to collapse to compact mode after a send.
        collapseAfterSendToken &+= 1
    }

    // MARK: - Inline editing

    /// Enter keyboard-edit mode: pause VAD so manual edits aren't overwritten.
    func beginEditing() {
        // [T-voice-bg-fg-gap] Idempotency guard + site log. The only caller is
        // the read-only transcript's double-tap, but the device-log flap
        // (isEditingTranscript true→false→true, 12:12:41→43) needs every entry
        // attributed before it can be pinned.
        guard !isEditingTranscript else {
            VoiceLog.log("[voice-edit-site] beginEditing() ignored — already editing")
            return
        }
        VoiceLog.log("[voice-edit-site] beginEditing(double-tap) false→true state=\(state)")
        isEditingTranscript = true
        // [voice-correction §5] Snapshot the pre-edit text. Whatever the user changes from
        // here is the training signal — see endEditing.
        editSnapshot = transcript
        vad.stop()
        state = .result
        // Capture paused — reply TTS may resume.
        VoiceModePreference.shared.isCapturing = false
    }

    /// Leave keyboard-edit mode and resume listening.
    func endEditing(resume: Bool) {
        if isEditingTranscript { VoiceLog.log("[voice-edit-site] endEditing(resume:\(resume)) true→false") }
        isEditingTranscript = false
        let before = editSnapshot
        let after = transcript
        editSnapshot = ""

        if resume {
            state = .waiting
            startVAD()   // the user's "resume recording" must happen NOW…
        }

        // …and the learning happens entirely off this call stack (design §12.2). Diff,
        // segmentation and the SQLite write all run in the detached task; nothing above
        // waits on any of it, and a failure in here can't reach the UI.
        //
        // [T-voice-correction-productionize] Promoted from #if DEBUG to
        // production: persistence is opt-in — recordEdit's write layer is
        // gated on VoiceCorrectionCollectionConsent (default OFF, one-time
        // prompt + Settings → Permissions toggle), so without consent this
        // call is a no-op.
        if before != after, !before.isEmpty, !after.isEmpty {
            let locale = PhoneticNormalizerRegistry.normalizedLocaleKey(language)
            Task.detached(priority: .utility) {
                await VoiceCorrectionRecorder.shared.recordEdit(before: before, after: after, locale: locale)
            }
        }
    }

    /// Sync external (input-box) edits back into the transcript.
    func setTranscript(_ text: String) {
        transcript = text
    }

    /// Delete the last character of the transcript (backspace).
    func deleteLastCharacter() {
        guard !transcript.isEmpty else { return }
        transcript.removeLast()
    }

    /// Clear the whole transcript (the "clear all" affordance).
    func clearTranscript() {
        guard !transcript.isEmpty else { return }
        transcript = ""
    }
}

// MARK: - VoiceActivityDelegate

extension VoiceInputViewModel: VoiceActivityDelegate {
    func voiceActivityDidStart() {
        VoiceLog.log("delegate voiceActivityDidStart (state=\(state), editing=\(isEditingTranscript))")
        // Ignore detected speech while the user is hand-editing the transcript.
        guard !isEditingTranscript else { return }
        state = .recording
        // Speech detected — push the no-speech idle deadline back out.
        resetIdleTimer()
    }

    func voiceActivityDidEnd(audioData: Data, reason: SegmentEndReason) {
        handleSegment(audioData, reason: reason)
    }

    func voiceActivityDidUpdate(pcmData: Data) {
        waveformLevels = Self.computeWaveformLevels(from: pcmData)
    }

    /// Capture was interrupted (call/Siri/route) and couldn't auto-resume — reset
    /// to idle so the mic button reflects "stopped" instead of a frozen waveform.
    func voiceActivityInterrupted() {
        // Flush any audio captured before the interruption, then return to idle.
        flushPendingSegments()
        state = .waiting
        listeningLabelToken &+= 1
        cancelTipCycle()
        recordingTipIndex = 0
        cancelIdleTimer()
        backgroundTimer?.invalidate(); backgroundTimer = nil
        VoiceModePreference.shared.isCapturing = false
        startError = AppLocalized("Recording was interrupted — tap the mic to resume", comment: "Voice capture interrupted")
    }

    private static func computeWaveformLevels(from pcmData: Data) -> [Float] {
        let count = 20
        let samples = pcmData.withUnsafeBytes { ptr -> [Float] in
            guard let base = ptr.baseAddress?.assumingMemoryBound(to: Float.self) else { return [] }
            return Array(UnsafeBufferPointer(start: base, count: pcmData.count / 4))
        }
        guard !samples.isEmpty else { return Array(repeating: 0.1, count: count) }

        let chunkSize = max(1, samples.count / count)
        return (0..<count).map { i in
            let start = i * chunkSize
            let end = min(start + chunkSize, samples.count)
            guard start < end else { return 0.05 }
            let chunk = samples[start..<end]
            let rms = sqrt(chunk.map { $0 * $0 }.reduce(0, +) / Float(chunk.count))
            // Perceptual curve: quiet speech has a tiny linear RMS, so `rms * 8`
            // saturated to the floor and the bars looked frozen. Take a sqrt to
            // lift low amplitudes, apply a higher gain, and use a very small floor
            // so silence reads as near-flat while any sound visibly moves.
            let level = sqrt(min(1.0, rms * 14)) * 0.95
            return min(1.0, max(0.04, level))
        }
    }

    // MARK: - WAV helpers (duration / merge for the 5 s speech gate)

    /// Standard 44-byte PCM WAV header size used by both the VAD library output
    /// and our own flush encoder.
    private static let wavHeaderSize = 44

    /// Approximate duration (seconds) of a 16-bit-PCM WAV by reading its header.
    static func wavDurationSeconds(_ wav: Data) -> Double {
        guard wav.count > wavHeaderSize else { return 0 }
        // byteRate is at offset 28 (little-endian UInt32).
        let byteRate = wav.withUnsafeBytes { ptr -> UInt32 in
            ptr.loadUnaligned(fromByteOffset: 28, as: UInt32.self)
        }
        guard byteRate > 0 else { return 0 }
        let dataBytes = Double(wav.count - wavHeaderSize)
        return dataBytes / Double(UInt32(littleEndian: byteRate))
    }

    /// Merge several 16-bit-PCM WAVs (assumed same format) into one by keeping the
    /// first header, concatenating PCM bodies, and fixing the size fields.
    static func mergeWavSegments(_ segments: [Data]) -> Data? {
        let valid = segments.filter { $0.count > wavHeaderSize }
        guard let first = valid.first else { return nil }
        if valid.count == 1 { return first }

        var out = first.prefix(wavHeaderSize) + first.suffix(from: wavHeaderSize)
        for seg in valid.dropFirst() {
            out.append(seg.suffix(from: wavHeaderSize))
        }
        // Patch RIFF chunk size (offset 4) and data chunk size (offset 40).
        let dataSize = UInt32(out.count - wavHeaderSize)
        let riffSize = UInt32(out.count - 8)
        func patch(_ value: UInt32, at offset: Int) {
            var le = value.littleEndian
            withUnsafeBytes(of: &le) { src in
                for i in 0..<4 { out[offset + i] = src[i] }
            }
        }
        patch(riffSize, at: 4)
        patch(dataSize, at: 40)
        return out
    }
}

// MARK: - Recognition languages

enum VoiceLanguages {
    struct Option: Identifiable, Hashable {
        let code: String       // full recognizer locale id, e.g. "zh-CN", "en-US"
        let label: String      // localized display name, e.g. "中文（中国）"
        let shortLabel: String // compact chip, e.g. "中", "EN"
        var id: String { code }
    }

    /// Persisted last-picked language (full locale id). Empty until the user
    /// chooses one, in which case `lastUsed` falls back to the system default.
    private static let lastUsedKey = "voice.recognition.lastLanguage"
    static var lastUsed: String {
        get {
            let saved = UserDefaults.standard.string(forKey: lastUsedKey) ?? ""
            // Only honour a saved value that's still a supported recognizer
            // locale; otherwise fall back to the system-preferred default.
            if !saved.isEmpty, supportedIds.contains(saved) { return saved }
            return systemDefault
        }
        set { UserDefaults.standard.set(newValue, forKey: lastUsedKey) }
    }

    /// All locale identifiers the on-device recognizer supports.
    private static var supportedIds: Set<String> {
        Set(SFSpeechRecognizer.supportedLocales().map(\.identifier))
    }

    /// Candidate languages, ordered: the user's SYSTEM-PREFERRED languages first
    /// (each mapped to the closest supported recognizer locale), then every other
    /// supported recognizer locale. So the picker leads with what the user set in
    /// iOS Settings, then offers the rest.
    static var options: [Option] {
        let supported = SFSpeechRecognizer.supportedLocales()
            .map(\.identifier)
            .sorted()
        var seen = Set<String>()
        var out: [Option] = []

        // 1) System-preferred languages, matched to a supported recognizer locale.
        for pref in Locale.preferredLanguages {
            guard let id = bestSupportedMatch(for: pref, in: supported),
                  !seen.contains(id) else { continue }
            seen.insert(id)
            out.append(option(for: id))
        }

        // 2) Everything else the recognizer supports.
        for id in supported where !seen.contains(id) {
            seen.insert(id)
            out.append(option(for: id))
        }

        if out.isEmpty { out = [option(for: "en-US")] }
        return out
    }

    /// First system-preferred language that maps to a supported recognizer locale.
    static var systemDefault: String {
        let supported = SFSpeechRecognizer.supportedLocales().map(\.identifier)
        for pref in Locale.preferredLanguages {
            if let id = bestSupportedMatch(for: pref, in: supported) { return id }
        }
        return supported.contains("en-US") ? "en-US" : (supported.sorted().first ?? "en-US")
    }

    /// Map a preferred-language id (e.g. "zh-Hans-CN", "en") to the best matching
    /// supported recognizer locale id (exact > region-agnostic > script-agnostic).
    private static func bestSupportedMatch(for preferred: String, in supported: [String]) -> String? {
        let loc = Locale(identifier: preferred)
        // Exact identifier hit.
        if supported.contains(where: { $0.caseInsensitiveCompare(preferred) == .orderedSame }) {
            return supported.first { $0.caseInsensitiveCompare(preferred) == .orderedSame }
        }
        // language+region (drops script), e.g. zh-Hans-CN -> zh-CN.
        if let lang = loc.language.languageCode?.identifier {
            if let region = loc.region?.identifier {
                let target = "\(lang)-\(region)"
                if let hit = supported.first(where: { $0.caseInsensitiveCompare(target) == .orderedSame }) {
                    return hit
                }
            }
            // language-only prefix, e.g. "en" -> first "en-*".
            if let hit = supported.first(where: { $0.lowercased().hasPrefix(lang.lowercased() + "-") || $0.caseInsensitiveCompare(lang) == .orderedSame }) {
                return hit
            }
        }
        return nil
    }

    static func option(for code: String) -> Option {
        let loc = Locale(identifier: code)
        let current = Locale.current
        // Prefer the full localized locale name (e.g. "中文（中国）"); fall back to
        // the bare language name, then the raw code.
        let name = current.localizedString(forIdentifier: code)
            ?? loc.language.languageCode.map { current.localizedString(forLanguageCode: $0.identifier) ?? $0.identifier }
            ?? code
        let primary = loc.language.languageCode?.identifier ?? String(code.prefix(2))
        let short = primary == "zh" ? "中"
            : primary == "ja" ? "日"
            : primary == "ko" ? "한"
            : String(primary.prefix(2)).uppercased()
        return Option(code: code, label: name, shortLabel: short)
    }
}
