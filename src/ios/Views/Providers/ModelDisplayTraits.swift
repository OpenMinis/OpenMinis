import SwiftUI

// MARK: - ModelDisplayTraits (Phase A of System-Provider unification)
//
// Presentation facts about a model ENTRY, derived from the model's own data
// rather than from "is this the System engine?" branching sprinkled across the
// picker. The picker reads these traits to draw icons / badges / subtitles /
// availability uniformly for every provider — built-in or cloud. As the System
// engine becomes a real Provider (Phase B/C), the derivation moves to the model's
// stored attributes but the call sites stay the same.
//
// Everything here is COMPUTED (no stored/Codable/synced fields), so it adds no
// sync-schema surface.

struct ModelDisplayTraits {
    /// True when the model runs locally with no network / no API key (the built-in
    /// Apple TTS/ASR today; any future on-device engine).
    let isOffline: Bool
    /// True when the model needs a provider credential to be usable. Offline
    /// built-ins need none, so they're always "available".
    let requiresCredential: Bool
    /// One-line subtitle under the model name (e.g. "iOS built-in, works offline"),
    /// or nil to fall back to the model id.
    let subtitle: String?
    /// SF Symbol shown as the model's leading glyph.
    let iconSymbol: String
    /// Tint for the leading glyph.
    let tint: Color
}

extension ModelEntry {
    /// Presentation traits for this entry — the single source the picker renders
    /// from. Built-in System entries are recognised by their provider sentinel and
    /// marked offline / credential-free; everything else derives from modality.
    @MainActor
    var displayTraits: ModelDisplayTraits {
        let isSystem = VoiceProviderResolver.isSystemEntry(providerInstanceId)
        let m = model.capabilities.supportedModalities
        let isTTS = m.contains(.audioOutput)
        let isASR = m.contains(.audioInput)

        if isSystem {
            // System ASR has an Online (cloud) and an Offline (on-device) variant;
            // reflect that in the subtitle so "works offline" isn't shown for the
            // cloud one. Recognised by the entry id suffix.
            let isOnlineASR = id.hasSuffix("/system-asr-online")
            let subtitle: String
            if isOnlineASR {
                subtitle = AppLocalized("iOS built-in, needs network", comment: "Cloud built-in ASR subtitle")
            } else {
                subtitle = AppLocalized("iOS built-in, works offline", comment: "Built-in engine subtitle")
            }
            return ModelDisplayTraits(
                isOffline: !isOnlineASR,
                requiresCredential: false,
                subtitle: subtitle,
                iconSymbol: isTTS ? "speaker.wave.2.fill" : (isASR ? "mic.fill" : "cpu"),
                tint: .blue)
        }

        // Cloud / regular models: glyph by primary modality; credentialed.
        let symbol: String
        if isTTS { symbol = "speaker.wave.2" }
        else if isASR { symbol = "waveform.badge.mic" }
        else if m.contains(.imageOutput) { symbol = "photo" }
        else { symbol = "circle.fill" }
        return ModelDisplayTraits(
            isOffline: false,
            requiresCredential: true,
            subtitle: nil,
            iconSymbol: symbol,
            tint: Self.providerTint(model.provider))
    }

    /// Brand-ish tint for a provider name (was `providerColor` inline in the picker).
    static func providerTint(_ provider: String) -> Color {
        switch provider {
        case "Anthropic": return .purple
        case "Google", "Google Gemini": return .blue
        case "OpenAI": return .green
        case "Antigravity": return .orange
        case "OpenRouter": return .cyan
        default: return .gray
        }
    }
}
