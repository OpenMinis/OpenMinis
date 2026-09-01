import SwiftUI

// MARK: - Voice Chat Provider templates (single source of truth)
//
// Voice-specialised vendors (MiniMax / Alibaba / Doubao / iFlytek) are NOT
// distinct ProviderType cases — they ride on an OpenAI/Anthropic-compatible
// instance identified by its base URL (see VoiceProviderFactory). A template:
//   1. Preseeds the Add-Provider flow (underlying type + base URL).
//   2. Carries a list of mock voice models (`mockModels`) tagged with
//      audioInput / audioOutput modality. These vendors have no OpenAI-style
//      /models endpoint, so addInstance seeds these entries directly instead
//      of relying on an API fetch. Once seeded they are ordinary ModelEntry
//      values — indistinguishable from a Whisper entry — so the Model Group
//      voice pickers reuse the exact Agent-Loop selection + modality-badge UI.
//
// Lives in the data layer (not the View) because both ProviderConfigStore
// (seeding) and AddProviderView (the picker) depend on it.
//
// [T-mimo-shadow-voice] A template's `baseURLMarkers` now serve TWO narrow roles
// only: (1) seed the mock voice models at add-time, (2) route voice REQUESTS to
// the right vendor adapter in VoiceProviderFactory. They no longer classify an
// entire instance as "voice-only" (that misfiled mixed vendors like MiMo). Voice
// visibility is per-model-modality; Voice Services shows a runtime SHADOW view of
// any instance that has audio-modality models (ProviderConfigStore.shadowVoiceProviders()).

struct VoiceProviderTemplate: Identifiable {
    let id: String
    /// Display name in the picker (e.g. "MiniMax").
    let name: String
    /// Underlying protocol the vendor's endpoint speaks.
    let providerType: ProviderType
    /// Base URL prefilled into the configure step (and used for detection).
    let baseURL: String
    /// Whether to auto-append "/v1" to the base URL.
    let appendV1: Bool
    /// Short capability tag shown under the name.
    let capability: String
    /// Substrings that, when present in an instance's base URL, identify it as
    /// this template (mirrors VoiceProviderFactory's base-URL matching).
    let baseURLMarkers: [String]
    /// Mock voice models seeded as ModelEntry on add. Each carries an
    /// audioInput / audioOutput modalityOverride so the Group voice pickers and
    /// modality badges treat them like any other multimodal model.
    let mockModels: [LLMModel]
    /// SF Symbol for the row icon.
    let symbol: String
    let tint: Color
    /// Optional caveat surfaced in the section footer (e.g. extra credentials).
    let note: String?

    /// The template whose base-URL markers match `baseURL`, if any.
    static func template(forBaseURL baseURL: String?) -> VoiceProviderTemplate? {
        guard let base = baseURL?.lowercased(), !base.isEmpty else { return nil }
        return all.first { tpl in tpl.baseURLMarkers.contains { base.contains($0) } }
    }

    /// Build the seed ModelEntry list for an instance that matched a template.
    static func mockEntries(for instance: ProviderInstance) -> [ModelEntry] {
        guard let tpl = template(forBaseURL: instance.effectiveCustomBaseURL) else { return [] }
        return tpl.mockModels.map { ModelEntry(providerInstanceId: instance.id, model: $0) }
    }

    static let all: [VoiceProviderTemplate] = [
        VoiceProviderTemplate(
            id: "elevenlabs",
            name: "ElevenLabs",
            providerType: .openAI,
            baseURL: "https://api.elevenlabs.io",
            appendV1: false,
            capability: AppLocalized("Speech synthesis (TTS)", comment: "Voice provider capability"),
            baseURLMarkers: ["elevenlabs"],
            mockModels: [
                // The model `id` is the ElevenLabs voice_id.
                LLMModel(id: "21m00Tcm4TlvDq8ikWAM", displayName: "Rachel (EN, F)", provider: "elevenlabs", modalityOverride: .audioOutput),
                LLMModel(id: "pNInz6obpgDQGcFmaJgB", displayName: "Adam (EN, M)", provider: "elevenlabs", modalityOverride: .audioOutput),
                LLMModel(id: "EXAVITQu4vr4xnSDxMaL", displayName: "Bella (EN, F)", provider: "elevenlabs", modalityOverride: .audioOutput),
                LLMModel(id: "ErXwobaYiN019PkySvjV", displayName: "Antoni (EN, M)", provider: "elevenlabs", modalityOverride: .audioOutput),
            ],
            symbol: "waveform",
            tint: .black,
            note: nil
        ),
        VoiceProviderTemplate(
            id: "deepgram",
            name: "Deepgram",
            providerType: .openAI,
            baseURL: "https://api.deepgram.com",
            appendV1: false,
            capability: AppLocalized("Recognition + synthesis", comment: "Voice provider capability"),
            baseURLMarkers: ["deepgram"],
            mockModels: [
                LLMModel(id: "nova-2", displayName: "Nova-2 (ASR)", provider: "deepgram", modalityOverride: .audioInput),
                LLMModel(id: "nova-3", displayName: "Nova-3 (ASR)", provider: "deepgram", modalityOverride: .audioInput),
                LLMModel(id: "aura-asteria-en", displayName: "Aura Asteria (TTS, EN F)", provider: "deepgram", modalityOverride: .audioOutput),
                LLMModel(id: "aura-luna-en", displayName: "Aura Luna (TTS, EN F)", provider: "deepgram", modalityOverride: .audioOutput),
                LLMModel(id: "aura-orion-en", displayName: "Aura Orion (TTS, EN M)", provider: "deepgram", modalityOverride: .audioOutput),
            ],
            symbol: "mic.and.signal.meter",
            tint: .green,
            note: nil
        ),
        VoiceProviderTemplate(
            id: "azure-tts",
            name: "Azure TTS",
            providerType: .openAI,
            baseURL: "https://eastasia.tts.speech.microsoft.com",
            appendV1: false,
            capability: AppLocalized("Speech synthesis (TTS)", comment: "Voice provider capability"),
            baseURLMarkers: ["tts.speech.microsoft.com"],
            mockModels: [
                // Chinese (Mandarin)
                LLMModel(id: "zh-CN-XiaoxiaoNeural", displayName: "Xiaoxiao (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunxiNeural", displayName: "Yunxi (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunyangNeural", displayName: "Yunyang (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaoyiNeural", displayName: "Xiaoyi (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaochenNeural", displayName: "Xiaochen (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaohanNeural", displayName: "Xiaohan (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaomengNeural", displayName: "Xiaomeng (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaomoNeural", displayName: "Xiaomo (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaoruiNeural", displayName: "Xiaorui (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaoshuangNeural", displayName: "Xiaoshuang (ZH, F, Child)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaoyouNeural", displayName: "Xiaoyou (ZH, F, Child)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-XiaozhenNeural", displayName: "Xiaozhen (ZH, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunfengNeural", displayName: "Yunfeng (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunhaoNeural", displayName: "Yunhao (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunjianNeural", displayName: "Yunjian (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunxiaNeural", displayName: "Yunxia (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunyeNeural", displayName: "Yunye (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-CN-YunzeNeural", displayName: "Yunze (ZH, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                // Chinese (Cantonese)
                LLMModel(id: "zh-HK-HiuMaanNeural", displayName: "HiuMaan (HK, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-HK-WanLungNeural", displayName: "WanLung (HK, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-HK-HiuGaaiNeural", displayName: "HiuGaai (HK, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                // Chinese (Taiwanese)
                LLMModel(id: "zh-TW-HsiaoChenNeural", displayName: "HsiaoChen (TW, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-TW-YunJheNeural", displayName: "YunJhe (TW, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "zh-TW-HsiaoYuNeural", displayName: "HsiaoYu (TW, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                // English (US) — popular picks
                LLMModel(id: "en-US-JennyNeural", displayName: "Jenny (EN, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "en-US-GuyNeural", displayName: "Guy (EN, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "en-US-AriaNeural", displayName: "Aria (EN, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "en-US-DavisNeural", displayName: "Davis (EN, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "en-US-AvaNeural", displayName: "Ava (EN, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "en-US-AndrewNeural", displayName: "Andrew (EN, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "en-US-EmmaNeural", displayName: "Emma (EN, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "en-US-BrianNeural", displayName: "Brian (EN, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                // Japanese
                LLMModel(id: "ja-JP-NanamiNeural", displayName: "Nanami (JA, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "ja-JP-KeitaNeural", displayName: "Keita (JA, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "ja-JP-AoiNeural", displayName: "Aoi (JA, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "ja-JP-DaichiNeural", displayName: "Daichi (JA, M)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "ja-JP-ShioriNeural", displayName: "Shiori (JA, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                // Korean
                LLMModel(id: "ko-KR-SunHiNeural", displayName: "SunHi (KO, F)", provider: "azure-tts", modalityOverride: .audioOutput),
                LLMModel(id: "ko-KR-InJoonNeural", displayName: "InJoon (KO, M)", provider: "azure-tts", modalityOverride: .audioOutput),
            ],
            symbol: "cloud",
            tint: .blue,
            note: AppLocalized("Enter the Azure Speech Services subscription key. Set the base URL to your region, e.g. https://eastasia.tts.speech.microsoft.com", comment: "Azure TTS note")
        ),
        VoiceProviderTemplate(
            id: "minimax",
            name: "MiniMax",
            providerType: .anthropic,
            baseURL: "https://api.minimax.io",
            appendV1: false,
            capability: AppLocalized("Speech synthesis (TTS)", comment: "Voice provider capability"),
            baseURLMarkers: ["minimax"],
            mockModels: [
                LLMModel(id: "speech-2.8-hd", displayName: "MiniMax Speech 2.8 HD",
                         provider: "minimax", modalityOverride: .audioOutput),
                LLMModel(id: "speech-2.8-turbo", displayName: "MiniMax Speech 2.8 Turbo",
                         provider: "minimax", modalityOverride: .audioOutput),
            ],
            symbol: "waveform",
            tint: .pink,
            note: nil
        ),
        VoiceProviderTemplate(
            id: "alibaba",
            name: AppLocalized("Alibaba Bailian", comment: "Voice provider name"),
            providerType: .openAI,
            baseURL: "https://dashscope.aliyuncs.com/compatible-mode",
            appendV1: true,
            capability: AppLocalized("Recognition + synthesis", comment: "Voice provider capability"),
            baseURLMarkers: ["dashscope"],
            mockModels: [
                LLMModel(id: "paraformer-realtime-v2", displayName: "Paraformer Realtime v2",
                         provider: "alibaba", modalityOverride: .audioInput),
                LLMModel(id: "cosyvoice-v2", displayName: "CosyVoice v2",
                         provider: "alibaba", modalityOverride: .audioOutput),
            ],
            symbol: "mic.and.signal.meter",
            tint: .orange,
            note: nil
        ),
        VoiceProviderTemplate(
            id: "doubao",
            name: AppLocalized("Doubao (Volcano)", comment: "Voice provider name"),
            providerType: .openAI,
            baseURL: "https://openspeech.bytedance.com",
            appendV1: false,
            capability: AppLocalized("Recognition + synthesis", comment: "Voice provider capability"),
            baseURLMarkers: ["openspeech.bytedance", "volcano"],
            mockModels: [
                LLMModel(id: "bigmodel", displayName: "Doubao ASR (bigmodel)",
                         provider: "doubao", modalityOverride: .audioInput),

                // Seed TTS 2.0 (big model, uranus)
                LLMModel(id: "zh_female_cancan_uranus_bigtts", displayName: "灿灿 (通用, 女)",
                         provider: "doubao", modalityOverride: .audioOutput),
                LLMModel(id: "zh_female_vv_uranus_bigtts", displayName: "Vivi (表现力, 女)",
                         provider: "doubao", modalityOverride: .audioOutput),
                LLMModel(id: "zh_male_liufei_uranus_bigtts", displayName: "刘飞 (通用, 男)",
                         provider: "doubao", modalityOverride: .audioOutput),
                LLMModel(id: "zh_male_m191_uranus_bigtts", displayName: "云舟 (清爽, 男)",
                         provider: "doubao", modalityOverride: .audioOutput),

                // Seed TTS 1.0 (big model, moon)
                LLMModel(id: "zh_female_shuangkuaisisi_moon_bigtts", displayName: "爽快思思 (爽朗, 女)",
                         provider: "doubao", modalityOverride: .audioOutput),
                LLMModel(id: "zh_female_sajiaonvyou_moon_bigtts", displayName: "撒娇女友 (撒娇, 女)",
                         provider: "doubao", modalityOverride: .audioOutput),
                LLMModel(id: "zh_female_gaolengyujie_moon_bigtts", displayName: "高冷御姐 (御姐, 女)",
                         provider: "doubao", modalityOverride: .audioOutput),
                LLMModel(id: "multi_female_shuangkuaisisi_moon_bigtts", displayName: "爽快思思 (多语, 女)",
                         provider: "doubao", modalityOverride: .audioOutput),
            ],
            symbol: "mic.and.signal.meter",
            tint: .blue,
            note: AppLocalized("Enter the API Key from the Volcano Engine new console.", comment: "Voice provider credential note")
        ),
        VoiceProviderTemplate(
            id: "xunfei",
            name: AppLocalized("iFlytek (Xunfei)", comment: "Voice provider name"),
            providerType: .openAI,
            baseURL: "https://iat-api.xfyun.cn",
            appendV1: false,
            capability: AppLocalized("Recognition + synthesis", comment: "Voice provider capability"),
            baseURLMarkers: ["xfyun"],
            mockModels: [
                LLMModel(id: "iat", displayName: "iFlytek IAT (ASR)",
                         provider: "xunfei", modalityOverride: .audioInput),
                LLMModel(id: "xiaoyan", displayName: "讯飞·晓燕 (TTS, 中文女)",
                         provider: "xunfei", modalityOverride: .audioOutput),
                LLMModel(id: "aisjiuxu", displayName: "讯飞·许久 (TTS, 中文男)",
                         provider: "xunfei", modalityOverride: .audioOutput),
            ],
            symbol: "mic",
            tint: .indigo,
            note: AppLocalized("iFlytek needs App ID and API Secret — enter them as \"appId;apiKey;apiSecret\" in the API Key field.", comment: "Voice provider credential note")
        ),
        VoiceProviderTemplate(
            id: "mimo",
            name: AppLocalized("Xiaomi MiMo", comment: "Voice provider name"),
            providerType: .openAI,
            baseURL: "https://api.xiaomimimo.com",
            appendV1: true,
            capability: AppLocalized("Recognition + synthesis", comment: "Voice provider capability"),
            baseURLMarkers: ["xiaomimimo"],
            mockModels: [
                LLMModel(id: "mimo-v2.5-asr", displayName: "MiMo ASR v2.5",
                         provider: "mimo", modalityOverride: .audioInput),
                LLMModel(id: "mimo_default", displayName: "MiMo 默认 (Auto)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "冰糖", displayName: "冰糖 (中文, 女)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "茉莉", displayName: "茉莉 (中文, 女)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "苏打", displayName: "苏打 (中文, 男)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "白桦", displayName: "白桦 (中文, 男)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "Mia", displayName: "Mia (EN, F)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "Chloe", displayName: "Chloe (EN, F)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "Milo", displayName: "Milo (EN, M)",
                         provider: "mimo", modalityOverride: .audioOutput),
                LLMModel(id: "Dean", displayName: "Dean (EN, M)",
                         provider: "mimo", modalityOverride: .audioOutput),
            ],
            symbol: "mic.and.signal.meter",
            tint: .orange,
            note: nil
        ),
    ]
}
