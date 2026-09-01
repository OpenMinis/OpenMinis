import AVFoundation
import SwiftUI

struct EnhancedBackgroundSettingsView: View {
    @ObservedObject private var keepAlive = BackgroundKeepAliveManager.shared
    @ObservedObject private var deepLink = DeepLinkCoordinator.shared
    // Owns the preview synthesizer and routes its audio-session intent through
    // AudioSessionCoordinator (a SwiftUI struct can't be an AVSpeechSynthesizer
    // delegate, so the lifecycle lives in this reference-type helper).
    // [T-audio-session-preview]
    @StateObject private var previewPlayer = SpeechPreviewPlayer()
    /// [T-settings-focus-highlight] Focus directives consumed once from the
    /// deep-link coordinator (parsing lives in DeepLinkCoordinator). Each row
    /// asks `DeepLinkCoordinator.shouldFocus(_:current:in:)` whether to show its
    /// yellow border; because it reads the live toggle value the border clears
    /// reactively once the row reaches the expected state.
    @State private var focus: [String: Bool?] = [:]

    private func shouldFocus(_ key: String, current: Bool) -> Bool {
        DeepLinkCoordinator.shouldFocus(key, current: current, in: focus)
    }

    var body: some View {
        List {
            Section {
                Toggle("Enhanced Background Execution", isOn: $keepAlive.enhancedBackgroundEnabled)
                    .focusHighlight(shouldFocus("enhancedBackgroundExecution", current: keepAlive.enhancedBackgroundEnabled))
            } footer: {
                Text("Keeps agent tasks running when the app is in the background. Shows progress via Live Activity on the Lock Screen and Dynamic Island. Also turns on Background Speak so text-to-speech narration keeps playing.")
            }

            // [T-ipad16-liveactivity-restore-crash] Hidden on devices where
            // Live Activities don't exist (iPad < iPadOS 17, iOS-on-Mac,
            // failed runtime probe) — the switch would do nothing there, and
            // GH#82 showed this device class is exactly where ActivityKit
            // must never be touched.
            if AgentLiveActivityManager.isLiveActivitySupported {
                Section {
                    Toggle("Live Activity", isOn: $keepAlive.liveActivityEnabled)
                        .focusHighlight(shouldFocus("liveActivityEnabled", current: keepAlive.liveActivityEnabled))
                } footer: {
                    // [T-keepalive-survival-tier] Nudge: without a keep-alive
                    // leg the app suspends ~30s after backgrounding and the
                    // Live Activity freezes; the location heartbeat is what
                    // keeps it refreshing.
                    Text("Show agent task progress on the Lock Screen and in the Dynamic Island. Turning this off also removes the currently displayed Live Activity. Tip: enable Location Tracking below so the Live Activity keeps refreshing while the app stays in the background — without it, updates pause about 30 seconds after you leave the app.")
                }
            }

            Section {
                Toggle("Task Notifications", isOn: $keepAlive.backgroundNotificationsEnabled)
            } footer: {
                Text("Show local notifications when agent tasks start and complete, including tasks triggered by Shortcuts.")
            }

            // [T-ios-live-activity-privacy-mode] Sits right after the two
            // surfaces it governs (Live Activity above, Task Notifications
            // directly above), since it redacts both.
            Section {
                // Display name only — the persisted key stays
                // `liveActivityPrivacyMode` so existing installs keep their setting.
                Toggle("Task Status Privacy", isOn: $keepAlive.liveActivityPrivacyMode)
            } footer: {
                Text("Hide session content on the Lock Screen, in the Dynamic Island and in notifications. Only the number of completed tasks and the elapsed time are shown — no session titles, tool status or reply content.")
            }

            Section {
                Toggle("Background Speak", isOn: $keepAlive.backgroundSpeakEnabled)
                    .focusHighlight(shouldFocus("backgroundSpeakEnabled", current: keepAlive.backgroundSpeakEnabled))
            } footer: {
                Text("Enables background audio playback for text-to-speech narration and music/audio playback while the app is in the background.")
            }

            // MARK: - Speech Settings

            Section {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Rate")
                        Spacer()
                        Text(String(format: "%.2f", keepAlive.speechRate))
                            .foregroundColor(.secondary)
                            .monospacedDigit()
                    }
                    Slider(
                        value: $keepAlive.speechRate,
                        in: AVSpeechUtteranceMinimumSpeechRate...AVSpeechUtteranceMaximumSpeechRate,
                        step: 0.05
                    )
                }

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Pitch")
                        Spacer()
                        Text(String(format: "%.1f", keepAlive.speechPitch))
                            .foregroundColor(.secondary)
                            .monospacedDigit()
                    }
                    Slider(value: $keepAlive.speechPitch, in: 0.5...2.0, step: 0.1)
                }

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Volume")
                        Spacer()
                        Text(String(format: "%.1f", keepAlive.speechVolume))
                            .foregroundColor(.secondary)
                            .monospacedDigit()
                    }
                    Slider(value: $keepAlive.speechVolume, in: 0.0...1.0, step: 0.1)
                }
            } header: {
                Text("Speech")
            }

            Section {
                voicePicker(label: "English Voice", language: "en", selection: $keepAlive.speechVoiceEn)
                voicePicker(label: "中文语音", language: "zh", selection: $keepAlive.speechVoiceZh)
            } header: {
                Text("Voice")
            } footer: {
                Text("Choose a specific voice for each language. Download more voices in Settings > Accessibility > Spoken Content > Voices.")
            }

            Section {
                Button("Reset to Defaults") {
                    keepAlive.resetSpeechSettings()
                }

                Button("Preview") {
                    previewSpeech()
                }
            }

            // MARK: - Other Settings

            Section {
                Toggle("Location Tracking", isOn: $keepAlive.locationTrackingEnabled)
                    .focusHighlight(shouldFocus("locationTrackingEnabled", current: keepAlive.locationTrackingEnabled))
                    .onChange(of: keepAlive.locationTrackingEnabled) { enabled in
                        if enabled && keepAlive.locationAuthStatus == .notDetermined {
                            keepAlive.requestLocationPermission()
                        }
                    }
            } footer: {
                Text("Lets the agent keep working in the background and keeps the task Live Activity refreshing in real time. Uses coarse, low-accuracy location only as a background heartbeat — it does not track or store your precise location.")
            }

            Section {
                // [T-keepalive-survival-tier] Shows the app's background
                // SURVIVAL CAPABILITY tier, not whether a task is running:
                // ~30s system grace by default, long-lived when the location
                // heartbeat and/or background audio leg is enabled.
                HStack {
                    Text("Keep-Alive")
                    Spacer()
                    switch keepAlive.survivalTier {
                    case .short:
                        Text("Short-Lived (~30s)")
                            .foregroundColor(.secondary)
                    case .extended(let location, let audio):
                        HStack(spacing: 4) {
                            Circle()
                                .fill(.green)
                                .frame(width: 8, height: 8)
                            Text(extendedTierLabel(location: location, audio: audio))
                                .foregroundColor(.green)
                        }
                    }
                }

                HStack {
                    Text("Location Permission")
                    Spacer()
                    Text(locationStatusText)
                        .foregroundColor(locationStatusColor)
                }
            } header: {
                Text("Status")
            } footer: {
                Text("How long the app can keep working after moving to the background. Without any keep-alive mechanism, iOS suspends apps after roughly 30 seconds; enabling Location Tracking (and granting location permission) or Background Speak extends this for the duration of a task.")
            }
        }
        .navigationTitle("Background")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // [T-settings-focus-highlight] One-shot consume of the parsed focus
            // directives (parsing + decision live in DeepLinkCoordinator). Held
            // locally so a later plain navigation doesn't re-arm the borders.
            let consumed = deepLink.consumeFocus()
            if !consumed.isEmpty { focus = consumed }
            // [T-ios-scene-create-watchdog-corelocation] This screen shows the
            // location-permission row, and the status is no longer read in the
            // manager's init (that IPC is what tripped the scene-create
            // watchdog). Refresh it here so the row is accurate even if this
            // view is somehow reached before setup() ran, and so it reflects a
            // permission the user changed in Settings.app while we were away.
            keepAlive.refreshLocationAuthStatus()
        }
    }

    // MARK: - Voice Picker

    @ViewBuilder
    private func voicePicker(label: String, language: String, selection: Binding<String>) -> some View {
        let voices = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix(language) }
            .sorted { $0.name < $1.name }

        Picker(label, selection: selection) {
            Text("System Default").tag("")
            ForEach(voices, id: \.identifier) { voice in
                Text(voiceDisplayName(voice)).tag(voice.identifier)
            }
        }
    }

    private func voiceDisplayName(_ voice: AVSpeechSynthesisVoice) -> String {
        // Same verbatim-Text caveat as locationStatusText: this is interpolated
        // into a String, so localize here rather than relying on Text lookup.
        let quality: String
        switch voice.quality {
        case .enhanced: quality = AppLocalized("Enhanced", comment: "Speech voice quality")
        case .premium: quality = AppLocalized("Premium", comment: "Speech voice quality")
        default: quality = ""
        }
        let suffix = quality.isEmpty ? "" : " (\(quality))"
        return "\(voice.name)\(suffix)"
    }

    // MARK: - Preview

    private func previewSpeech() {
        let text = keepAlive.speechVoiceZh.isEmpty && keepAlive.speechVoiceEn.isEmpty
            ? "Hello, this is a speech preview."
            : {
                // If a Chinese voice is set but not English, preview in Chinese
                if !keepAlive.speechVoiceZh.isEmpty && keepAlive.speechVoiceEn.isEmpty {
                    return "你好，这是语音预览。"
                }
                return "Hello, this is a speech preview."
            }()
        let utterance = AVSpeechUtterance(string: text)
        let lang = text.range(of: "\\p{Han}", options: .regularExpression) != nil ? "zh-CN" : "en-US"
        utterance.voice = keepAlive.voiceForLanguage(lang)
        utterance.rate = keepAlive.speechRate
        utterance.pitchMultiplier = keepAlive.speechPitch
        utterance.volume = keepAlive.speechVolume
        // Audio-session intent is declared through AudioSessionCoordinator inside
        // the player (begin on speak, end on didFinish/didCancel), matching the
        // real read-replies path instead of poking AVAudioSession directly.
        previewPlayer.speak(utterance)
    }

    // MARK: - Location Status

    /// [T-keepalive-survival-tier] Label for the extended tier naming the
    /// enabled keep-alive legs.
    private func extendedTierLabel(location: Bool, audio: Bool) -> String {
        switch (location, audio) {
        case (true, true): return AppLocalized("Extended (Location + Audio)", comment: "Keep-alive tier")
        case (true, false): return AppLocalized("Extended (Location)", comment: "Keep-alive tier")
        default: return AppLocalized("Extended (Audio)", comment: "Keep-alive tier")
        }
    }

    /// Localized location-permission status. These are consumed as
    /// `Text(locationStatusText)` — a String variable, which selects SwiftUI's
    /// VERBATIM initializer and performs no catalog lookup. So the localization
    /// has to happen here via `String(localized:)`; returning bare literals kept
    /// this row English in every language.
    private var locationStatusText: String {
        switch keepAlive.locationAuthStatus {
        case .notDetermined: return AppLocalized("Not Requested", comment: "Location permission status")
        case .restricted: return AppLocalized("Restricted", comment: "Location permission status")
        case .denied: return AppLocalized("Denied", comment: "Location permission status")
        case .authorizedWhenInUse: return AppLocalized("When In Use", comment: "Location permission status")
        case .authorizedAlways: return AppLocalized("Always", comment: "Location permission status")
        @unknown default: return AppLocalized("Unknown", comment: "Location permission status")
        }
    }

    private var locationStatusColor: Color {
        switch keepAlive.locationAuthStatus {
        case .authorizedAlways: return .green
        case .authorizedWhenInUse: return .orange
        case .denied, .restricted: return .red
        default: return .secondary
        }
    }
}

// MARK: - Focus highlight

private extension View {
    /// [T-settings-focus-highlight] Draw a yellow rounded-rect border around a
    /// row recommended by a `?focus=…` deep-link. The border fades out once
    /// `focused` flips to false (i.e. the user interacted with the row).
    @ViewBuilder
    func focusHighlight(_ focused: Bool) -> some View {
        self
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(Color.yellow, lineWidth: 2)
                    // Expand the border outward so it isn't cramped against the
                    // row text — more breathing room above/below in particular.
                    .padding(.horizontal, -10)
                    .padding(.vertical, -12)
                    .opacity(focused ? 1 : 0)
                    .allowsHitTesting(false)
            )
            .animation(.easeInOut(duration: 0.3), value: focused)
    }
}

/// Owns the settings "speech preview" synthesizer and balances its
/// AudioSessionCoordinator `.replyTTS` intent. A SwiftUI `View` struct can't be
/// an `AVSpeechSynthesizerDelegate`, so the begin/end lifecycle lives here.
/// [T-audio-session-preview]
@MainActor
final class SpeechPreviewPlayer: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    /// Whether we currently hold a `.replyTTS` intent — guards against a double
    /// `end` (re-tap fires didCancel for the old utterance after we've already
    /// begun a fresh intent) and against `end` without a matching `begin`.
    private var holdingIntent = false

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(_ utterance: AVSpeechUtterance) {
        // Stop any in-progress preview first. This fires didCancel synchronously
        // on some OS versions, which would `end` the intent we're about to hold;
        // releasing it here first keeps begin/end balanced regardless of ordering.
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        releaseIntent()
        AudioSessionCoordinator.shared.begin(.replyTTS)
        holdingIntent = true
        synthesizer.speak(utterance)
    }

    private func releaseIntent() {
        guard holdingIntent else { return }
        holdingIntent = false
        AudioSessionCoordinator.shared.end(.replyTTS)
    }

    // AVSpeechSynthesizer delivers delegate callbacks on the main thread; hop to
    // the MainActor to touch the (@MainActor) coordinator + our state.
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       didFinish utterance: AVSpeechUtterance) {
        MainActor.assumeIsolated { releaseIntent() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       didCancel utterance: AVSpeechUtterance) {
        MainActor.assumeIsolated { releaseIntent() }
    }
}
