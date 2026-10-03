import AVFoundation
import Combine
import Speech

@MainActor
final class SpeechRecognitionManager: ObservableObject {
    static let shared = SpeechRecognitionManager()

    enum State: Equatable {
        case idle
        case recording
    }

    enum SpeechProvider: String, CaseIterable, Identifiable {
        case system = "iOS System"

        var id: String { rawValue }

        var icon: String {
            switch self {
            case .system: return "apple.logo"
            }
        }
    }

    // MARK: - Available Languages

    /// All locales supported by the on-device speech recognizer,
    /// sorted with user's preferred languages first.
    private(set) var availableLocales: [Locale] = []

    @Published private(set) var state: State = .idle
    @Published var recognizedText: String = ""
    @Published private(set) var audioLevels: [Float] = Array(repeating: 0, count: 40)
    private static let savedLocaleKey = "SpeechRecognitionLocale"

    @Published var locale: Locale {
        didSet {
            speechRecognizer = SFSpeechRecognizer(locale: locale)
            UserDefaults.standard.set(locale.identifier, forKey: Self.savedLocaleKey)
        }
    }
    @Published var provider: SpeechProvider = .system
    @Published var showLanguagePicker: Bool = false

    var isAvailable: Bool {
        speechRecognizer?.isAvailable ?? false
    }

    // MARK: - Composer input-mode preference
    // [T-voice-input-mode-preference-ios] Remembers whether the user last
    // composed by voice or by typing. "voice" makes new chats / the cold-launch
    // initial chat auto-enter voice input; "text" (the default) changes nothing.
    // Written on mic-tap recording start ("voice") and on a send composed
    // without any voice session ("text").

    static let inputModePreferenceKey = "composerInputModePreference"

    static var prefersVoiceInput: Bool {
        UserDefaults.standard.string(forKey: inputModePreferenceKey) == "voice"
    }

    static func saveInputModePreference(_ mode: String) {
        guard UserDefaults.standard.string(forKey: inputModePreferenceKey) != mode else { return }
        UserDefaults.standard.set(mode, forKey: inputModePreferenceKey)
        AppLogger(category: "Speech").info("[InputModePref] preference -> \(mode)")
    }

    /// Non-prompting permission probe for the preference-driven auto-entry:
    /// true only when BOTH speech recognition and microphone access are
    /// already granted. Never triggers a system permission dialog (the
    /// auto-fire path must not open a permission storm on launch).
    var permissionsAlreadyGranted: Bool {
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else { return false }
        if #available(iOS 17.0, *) {
            return AVAudioApplication.shared.recordPermission == .granted
        } else {
            return AVAudioSession.sharedInstance().recordPermission == .granted
        }
    }

    /// Short label for the badge (e.g. "EN", "FR", or a CJK glyph for zh / ja / ko).
    var languageLabel: String {
        guard let langCode = locale.language.languageCode?.identifier else {
            return locale.identifier.prefix(2).uppercased()
        }
        // Use the language's own script for CJK, otherwise uppercase Latin code
        switch langCode {
        case "zh": return "中"
        case "ja": return "日"
        case "ko": return "한"
        default: return langCode.prefix(2).uppercased()
        }
    }

    /// Display name for a locale in its own language (e.g. "English" for en,
    /// the native endonym for zh / ja / ko / etc.).
    func displayName(for loc: Locale) -> String {
        loc.localizedString(forIdentifier: loc.identifier)?.localizedCapitalized
            ?? loc.identifier
    }

    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()
    private let logger = AppLogger(category: "Speech")

    private init() {
        let allSupported = SFSpeechRecognizer.supportedLocales()

        // Restore previously selected locale, or fall back to system language
        let resolvedLocale: Locale
        if let savedId = UserDefaults.standard.string(forKey: Self.savedLocaleKey),
           allSupported.contains(where: { $0.identifier == savedId }) {
            resolvedLocale = Locale(identifier: savedId)
        } else {
            let systemLangCode = Locale.current.language.languageCode?.identifier ?? "en"
            resolvedLocale = allSupported.first { $0.language.languageCode?.identifier == systemLangCode }
                ?? Locale(identifier: "en-US")
        }

        self.locale = resolvedLocale
        self.speechRecognizer = SFSpeechRecognizer(locale: resolvedLocale)

        // Build sorted list: user preferred languages first, then the rest alphabetically
        let preferredCodes = Locale.preferredLanguages.map { Locale(identifier: $0).language.languageCode?.identifier ?? "" }

        let sorted = allSupported.sorted { a, b in
            let aCode = a.language.languageCode?.identifier ?? ""
            let bCode = b.language.languageCode?.identifier ?? ""
            let aIdx = preferredCodes.firstIndex(of: aCode) ?? Int.max
            let bIdx = preferredCodes.firstIndex(of: bCode) ?? Int.max
            if aIdx != bIdx { return aIdx < bIdx }
            // Same preference tier — sort by display name
            let aName = displayName(for: a)
            let bName = displayName(for: b)
            return aName.localizedCaseInsensitiveCompare(bName) == .orderedAscending
        }
        self.availableLocales = sorted

        // [T-voice-interrupt-cannot-restart] Observe interruptions so a call /
        // Siri that kills the engine is reflected in `state` immediately,
        // instead of leaving the UI showing "recording" over a dead engine
        // until the user taps and nothing happens.
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil, queue: .main
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw),
                  type == .began else { return }
            Task { @MainActor [weak self] in
                guard let self, self.state == .recording else { return }
                // Deliberately no auto-resume: this is a live dictation session
                // whose partial transcript the user is watching. Tearing down
                // keeps `recognizedText` (tearDown does not clear it) and hands
                // control back, rather than silently reopening the mic after a
                // call the user may still be on.
                self.logger.info("Recording interrupted by the system — stopping")
                self.tearDown()
            }
        }
    }

    // MARK: - Language Selection

    /// Whether a language switch should automatically restart recording
    /// after the picker sheet is dismissed.
    private(set) var pendingRecordingRestart = false

    func setLanguage(_ newLocale: Locale) {
        guard newLocale.identifier != locale.identifier else { return }
        let wasRecording = state == .recording
        if wasRecording { stopRecording() }
        locale = newLocale
        logger.info("Language set to \(self.locale.identifier)")
        // Defer restart until the sheet is fully dismissed,
        // otherwise AVAudioSession conflicts with the sheet transition.
        pendingRecordingRestart = wasRecording
    }

    /// Call after the language picker sheet is dismissed to restart recording
    /// if a language switch interrupted it.
    func restartRecordingIfPending() {
        guard pendingRecordingRestart else { return }
        pendingRecordingRestart = false
        try? startRecording()
    }

    func setProvider(_ newProvider: SpeechProvider) {
        guard newProvider != provider else { return }
        let wasRecording = state == .recording
        if wasRecording { stopRecording() }
        provider = newProvider
        logger.info("Speech provider set to \(newProvider.rawValue)")
        if wasRecording {
            try? startRecording()
        }
    }

    // MARK: - Permissions

    func requestPermissions() async -> Bool {
        let speechStatus = await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status)
            }
        }
        guard speechStatus == .authorized else {
            logger.warning("Speech recognition not authorized: \(String(describing: speechStatus))")
            return false
        }

        let audioStatus: Bool
        if #available(iOS 17.0, *) {
            audioStatus = await AVAudioApplication.requestRecordPermission()
        } else {
            audioStatus = await withCheckedContinuation { cont in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    cont.resume(returning: granted)
                }
            }
        }
        guard audioStatus else {
            logger.warning("Microphone permission not granted")
            return false
        }

        return true
    }

    // MARK: - Recording

    func startRecording() throws {
        // [T-voice-interrupt-cannot-restart] This manager observes no audio
        // interruptions at all, so a call/Siri that stops the engine leaves
        // `state == .recording` with nothing running, and every later call
        // returned silently here — the same "voice input never works again
        // after an interruption" wedge as the VAD path. Reconcile against the
        // engine instead of trusting the flag.
        if state == .recording {
            guard !audioEngine.isRunning else { return }   // genuinely recording
            logger.warning("startRecording found a stale recording state (interrupted?) — tearing down first")
            tearDown()
        }
        guard state == .idle else { return }

        recognitionTask?.cancel()
        recognitionTask = nil

        let audioSession = AVAudioSession.sharedInstance()
        // No `.duckOthers`: capturing the mic shouldn't quiet other apps' audio
        // (it sometimes stayed ducked after recording stopped).
        try audioSession.setCategory(.record, mode: .measurement)
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if speechRecognizer?.supportsOnDeviceRecognition == true {
            request.requiresOnDeviceRecognition = true
        }
        recognitionRequest = request

        let inputNode = audioEngine.inputNode
        // Defensive: remove any stale tap before installing a new one.
        inputNode.removeTap(onBus: 0)
        let recordingFormat = inputNode.outputFormat(forBus: 0)

        guard recordingFormat.channelCount > 0 else {
            logger.error("Recording format has 0 channels — cannot install tap")
            throw NSError(domain: "SpeechRecognition", code: -1, userInfo: [NSLocalizedDescriptionKey: "Recording format has 0 channels"])
        }

        // [Crash 1 — shared-185B90CB 2026-05-31] `installTap(onBus:…)` throws an
        // Objective-C NSException (not a Swift Error) when the engine/format/
        // hardware is in a bad state — e.g. the audio session got grabbed by
        // another app, the input route changed mid-call, or the format is
        // rejected. That NSException blows straight through this `throws`
        // function and the caller's `try?`, landing in `abort()` (SIGABRT) via
        // AVAudioEngineImpl::InstallTapOnNode. `audioEngine.start()` can do the
        // same. Wrap both in noff_try_objc so the NSException is caught, we tear
        // the tap/engine back down, and we surface a normal Swift error the
        // caller's `try?` can swallow.
        var startError: Error?
        let installOk = noff_try_objc {
            inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { [weak self] buffer, _ in
                request.append(buffer)
                let rms = self?.computeRMS(buffer: buffer) ?? 0
                Task { @MainActor [weak self] in
                    self?.pushLevel(rms)
                }
            }

            self.audioEngine.prepare()
            do {
                try self.audioEngine.start()
            } catch {
                startError = error
            }
        }

        guard installOk, startError == nil else {
            // Roll back anything that partially succeeded so the next attempt
            // starts clean and no orphaned tap remains on the input node.
            audioEngine.stop()
            inputNode.removeTap(onBus: 0)
            recognitionRequest = nil
            state = .idle
            let reason = installOk
                ? (startError?.localizedDescription ?? "audio engine failed to start")
                : "installTap raised an exception (audio session unavailable?)"
            logger.error("startRecording failed: \(reason)")
            throw NSError(domain: "SpeechRecognition", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: reason])
        }

        recognitionTask = speechRecognizer?.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let result {
                    self.recognizedText = result.bestTranscription.formattedString
                }
                if error != nil || (result?.isFinal ?? false) {
                    self.tearDown()
                }
            }
        }

        recognizedText = ""
        state = .recording
        logger.info("Recording started (\(self.locale.identifier))")
    }

    func stopRecording() {
        guard state == .recording else { return }
        tearDown()
        logger.info("Recording stopped")
    }

    private func tearDown() {
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()
        recognitionRequest = nil
        recognitionTask?.cancel()
        recognitionTask = nil
        state = .idle

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func computeRMS(buffer: AVAudioPCMBuffer) -> Float {
        guard let channelData = buffer.floatChannelData else { return 0 }
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return 0 }

        var sum: Float = 0
        let data = channelData[0]
        for i in 0..<frames {
            let sample = data[i]
            sum += sample * sample
        }
        let rms = sqrtf(sum / Float(frames))
        return min(rms * 10, 1.0)
    }

    private func pushLevel(_ level: Float) {
        audioLevels.append(level)
        if audioLevels.count > 40 {
            audioLevels.removeFirst(audioLevels.count - 40)
        }
    }
}
