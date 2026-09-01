import Foundation
import AVFoundation
import SwiftUI

// MARK: - System Voice Preferences (Stage 1)
//
// Global TTS parameters for the built-in Apple synthesizer, applicable to every
// scene that uses System TTS (Chat read-aloud + background speak). Only pitch
// and volume live here — by design (see system-voice-provider-design.md §5.1):
//   • rate  → carried via VoiceOutputPreferences.speedMultiplier (per-playback)
//   • voice → decided by the ModelEntry selected in the Voice Output group
//             (= a specific AVSpeechSynthesisVoice.identifier)
//
// These were previously read from BackgroundKeepAliveManager.speechPitch /
// .speechVolume. To avoid a settings-migration regression, the accessors read
// the SAME UserDefaults keys the manager persisted ("speechPitch"/"speechVolume"),
// so existing user values carry over transparently. The manager's @Published
// mirrors remain the source the settings UI binds to (untouched in Stage 1); this
// enum is just the read side used by the synthesis paths.
enum SystemVoicePreferences {
    private static let pitchKey  = "speechPitch"
    private static let volumeKey = "speechVolume"

    /// Pitch multiplier (0.5–2.0). Default 1.0.
    static var pitch: Float {
        get { UserDefaults.standard.object(forKey: pitchKey) as? Float ?? 1.0 }
        set { UserDefaults.standard.set(newValue, forKey: pitchKey) }
    }

    /// Volume (0.0–1.0). Default 1.0.
    static var volume: Float {
        get { UserDefaults.standard.object(forKey: volumeKey) as? Float ?? 1.0 }
        set { UserDefaults.standard.set(newValue, forKey: volumeKey) }
    }
}

// MARK: - System Voice Catalog (Stage 1)
//
// Turns the filtered Apple TTS voice roster into dynamic per-voice models so the
// built-in System provider participates in the Voice Output picker exactly like a
// cloud provider — one AVSpeechSynthesisVoice = one selectable model. The list is
// DYNAMIC (users download Enhanced/Premium packs, create Personal Voices), so it
// is regenerated on demand and refreshed on availableVoicesDidChangeNotification.
//
// A voice model's `id` is the full voice identifier (e.g.
// "com.apple.voice.compact.zh-CN.Tingting"); its `provider` field carries the
// built-in sentinel so VoiceProviderResolver.isSystemEntry keeps recognising it.

@MainActor
enum SystemVoiceCatalog {

    private static let logger = AppLogger(category: "SystemVoiceCatalog")

    /// Quality rank for sorting: Premium (best) → Enhanced → Default.
    private static func qualityRank(_ q: AVSpeechSynthesisVoiceQuality) -> Int {
        switch q {
        case .premium:  return 0
        case .enhanced: return 1
        default:        return 2   // .default and any future case
        }
    }

    /// Star suffix marking the download tier: Premium ★★★, Enhanced ★★, Default none.
    private static func qualityStars(_ q: AVSpeechSynthesisVoiceQuality) -> String {
        switch q {
        case .premium:  return " ★★★"
        case .enhanced: return " ★★"
        default:        return ""
        }
    }

    /// Base language codes worth showing. The full roster has ~60 languages, which
    /// is overwhelming; restrict to the app's supported UI locales UNION the user's
    /// system-preferred languages (so someone whose phone is set to Spanish still
    /// sees Spanish voices even though the app has no Spanish UI yet).
    ///   • App UI locales: de/en/fr/ja/ko/ru/zh (from Localizable.xcstrings).
    ///   • User preference: Locale.preferredLanguages base codes.
    static func allowedLanguageCodes() -> Set<String> {
        let appUI: Set<String> = ["de", "en", "fr", "ja", "ko", "ru", "zh"]
        let preferred = Locale.preferredLanguages.compactMap { tag -> String? in
            Locale(identifier: tag).language.languageCode?.identifier.lowercased()
        }
        return appUI.union(preferred)
    }

    /// Base language code of a voice ("zh-CN" → "zh"), lowercased.
    private static func baseLang(_ voice: AVSpeechSynthesisVoice) -> String {
        String(voice.language.prefix(while: { $0 != "-" && $0 != "_" })).lowercased()
    }

    /// Filter out Novelty + Eloquence voices and languages outside the allowed set,
    /// then sort by quality → language → name. Novelty uses the iOS 17 trait when
    /// available, with an id-prefix fallback for older systems; Eloquence is matched
    /// by id substring.
    static func filteredAndSortedVoices() -> [AVSpeechSynthesisVoice] {
        let all = AVSpeechSynthesisVoice.speechVoices()
        let allowed = allowedLanguageCodes()

        let usable = all.filter { voice in
            // 1. Drop Novelty voices (robotic / joke voices).
            if #available(iOS 17, *) {
                if voice.voiceTraits.contains(.isNoveltyVoice) { return false }
            } else if voice.identifier.hasPrefix("com.apple.speech.synthesis.voice.") {
                // Pre-iOS-17 fallback: the legacy id prefix is the Novelty family.
                return false
            }
            // 2. Drop Eloquence robot voices (low quality, heavily duplicated names).
            if voice.identifier.lowercased().contains("eloquence") { return false }
            // 3. Keep only the app-supported + user-preferred languages.
            if !allowed.contains(baseLang(voice)) { return false }
            return true
        }

        return usable.sorted { a, b in
            let qa = qualityRank(a.quality), qb = qualityRank(b.quality)
            if qa != qb { return qa < qb }
            if a.language != b.language { return a.language < b.language }
            return a.name < b.name
        }
    }

    /// Gender word, LOCALIZED to the current UI language (e.g. "男声"/"女声" in zh,
    /// "Male"/"Female" in en). nil when the voice doesn't report a gender.
    private static func genderWord(for voice: AVSpeechSynthesisVoice) -> String? {
        guard #available(iOS 17, *) else { return nil }
        switch voice.gender {
        case .male:   return AppLocalized("Male voice", comment: "TTS voice gender label")
        case .female: return AppLocalized("Female voice", comment: "TTS voice gender label")
        default:      return nil
        }
    }

    /// Human-readable model name: "{name} ({localized language}, {localized gender}
    /// {quality★})". The language + gender are rendered in the CURRENT UI language —
    /// e.g. with a Chinese UI a Chinese voice reads "婷婷 (中文, 女声)" and an English
    /// one "Samantha (英语, 女声)". Falls back to the language TAG if the OS can't
    /// localize the code.
    static func displayName(for voice: AVSpeechSynthesisVoice) -> String {
        let langName = Locale.current.localizedString(forLanguageCode: baseLang(voice))?
            .capitalized(with: Locale.current) ?? voice.language.uppercased()
        var bits: [String] = [langName]
        if let g = genderWord(for: voice) { bits.append(g) }
        let inner = bits.joined(separator: ", ")
        return "\(voice.name) (\(inner))\(qualityStars(voice.quality))"
    }

    /// Dynamic TTS models — one per filtered/sorted voice. Regenerated each call.
    static func ttsModels() -> [LLMModel] {
        filteredAndSortedVoices().map { voice in
            LLMModel(
                id: voice.identifier,
                displayName: displayName(for: voice),
                provider: SystemVoiceProvider.builtinProviderId,
                modalityOverride: [.audioOutput]
            )
        }
    }

    // MARK: - Synthesize a ModelEntry from a System composite id
    //
    // The single source of truth that turns a System engine id
    // ("__builtin_system_speech__" or "…/<voice|asr>") into a virtual ModelEntry.
    // Used by BOTH the store's entry(for:) (so System group members resolve for
    // fail-over) and the picker (so a group lists real voice names). System entries
    // are never stored — they're synthesized on demand here.

    /// Prefix a model id into a System composite entry id.
    private static func systemEntry(modelId: String, displayName: String,
                                    modality: ModelModality) -> ModelEntry {
        ModelEntry(
            providerInstanceId: SystemVoiceProvider.builtinProviderId,
            model: LLMModel(id: modelId, displayName: displayName,
                            provider: SystemVoiceProvider.builtinProviderId,
                            modalityOverride: modality),
            isHidden: true)
    }

    /// Resolve a System engine composite id to its virtual ModelEntry, or nil if
    /// the id isn't a System id. Handles: a specific voice ("…/com.apple.voice.…"),
    /// the TTS auto default ("…/system-tts"), ASR online/offline ("…/system-asr-
    /// online|offline"), and the legacy/bare ASR sentinel.
    static func entry(forCompositeId id: String) -> ModelEntry? {
        let sentinel = SystemVoiceProvider.builtinProviderId
        guard id == sentinel || id.hasPrefix(sentinel + "/") else { return nil }
        let suffix = id == sentinel ? "" : String(id.dropFirst(sentinel.count + 1))

        // A specific TTS voice id (contains a dot).
        if suffix.contains(".") {
            let voiceId = suffix
            let name: String
            if let voice = AVSpeechSynthesisVoice(identifier: voiceId) {
                name = displayName(for: voice)
            } else {
                // Not installed here (e.g. a synced group named a voice this device
                // lacks): derive "Tingting (zh-CN) · not installed" from the id.
                let parts = voiceId.split(separator: ".").map(String.init)
                let leaf = parts.last ?? voiceId
                let lang = parts.count >= 2 ? parts[parts.count - 2] : ""
                let notInstalled = AppLocalized("not installed", comment: "voice pack not downloaded")
                name = lang.isEmpty ? "\(leaf) · \(notInstalled)" : "\(leaf) (\(lang)) · \(notInstalled)"
            }
            return systemEntry(modelId: voiceId, displayName: name, modality: [.audioOutput])
        }

        switch suffix {
        case "system-tts", "output":
            return systemEntry(modelId: "system-tts",
                               displayName: AppLocalized("System Voice (Auto)", comment: "Built-in TTS auto-by-language option"),
                               modality: [.audioOutput])
        case "system-asr-online":
            return systemEntry(modelId: "system-asr-online",
                               displayName: AppLocalized("System Recognition (Online)", comment: "Built-in cloud ASR option"),
                               modality: [.audioInput])
        case "system-asr-offline":
            return systemEntry(modelId: "system-asr-offline",
                               displayName: AppLocalized("System Recognition (Offline)", comment: "Built-in on-device ASR option"),
                               modality: [.audioInput])
        default:
            // Bare sentinel / "system-asr" / "input" → legacy default ASR entry.
            return systemEntry(modelId: "system-asr",
                               displayName: AppLocalized("System Speech Recognition", comment: "Built-in ASR option"),
                               modality: [.audioInput])
        }
    }

    /// Install the availableVoicesDidChangeNotification observer once. Idempotent.
    /// Bumps `SystemVoiceRoster.shared.revision` so SwiftUI views observing it
    /// refresh their System model list when packs are downloaded / removed.
    static func startObservingVoiceChanges() {
        SystemVoiceRoster.shared.startObserving()
    }
}

// MARK: - Observable roster revision
//
// A tiny ObservableObject SwiftUI views can watch so the Voice Output picker
// rebuilds its System voice rows when the available-voices roster changes
// (Enhanced/Premium download, Personal Voice creation). Kept separate from the
// stateless catalog because @Published requires a class instance.
@MainActor
final class SystemVoiceRoster: ObservableObject {
    static let shared = SystemVoiceRoster()

    /// Bumped each time the available-voices roster changes.
    @Published private(set) var revision = 0

    private let logger = AppLogger(category: "SystemVoiceRoster")
    private var observerInstalled = false

    private init() {}

    func startObserving() {
        guard !observerInstalled else { return }
        // availableVoicesDidChangeNotification is iOS 17+. On older systems the
        // roster simply never refreshes live (the picker still rebuilds on open).
        guard #available(iOS 17, *) else { observerInstalled = true; return }
        observerInstalled = true
        NotificationCenter.default.addObserver(
            forName: AVSpeechSynthesizer.availableVoicesDidChangeNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.revision &+= 1
                self.logger.info("available voices changed → revision \(self.revision)")
            }
        }
    }
}
