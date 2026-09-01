import SwiftUI

// MARK: - VoiceDirection
//
// Shared helper describing a voice direction (ASR input / TTS output) and the
// modality test that decides whether a model is a dedicated voice model for it.
// Used by VoiceProviderResolver (filter a group's members) and the
// create-voice-group flow (restrict the model picker to voice models).

enum VoiceDirection {
    case input   // ASR — audio-in models
    case output  // TTS — audio-out models

    var pickerTitle: LocalizedStringKey {
        self == .input ? "Voice Input" : "Voice Output"
    }

    /// Explanation of the modality filter shown at the top of the model picker,
    /// so the user understands why only certain models qualify (chat LLMs that
    /// merely accept audio don't appear).
    var filterNote: String {
        self == .input
            ? AppLocalized("Only dedicated speech-to-text models are listed — those whose only input is audio (e.g. Whisper). Multimodal chat models that can also hear audio are not shown.", comment: "Voice input filter explanation")
            : AppLocalized("Only dedicated text-to-speech models are listed — those whose only output is audio (e.g. TTS voices). Multimodal chat models are not shown.", comment: "Voice output filter explanation")
    }

    /// Whether `model` is a dedicated voice model for THIS direction.
    ///
    /// ASR engines (Whisper, Doubao bigmodel, …) take audio in and emit text;
    /// the signal that separates them from a multimodal chat LLM (Gemini, Qwen-VL)
    /// that merely *accepts* audio is that their INPUT is audio-only. TTS engines
    /// emit audio with text-only input. So:
    ///   - input  (ASR): input modalities == {audioInput} exactly.
    ///   - output (TTS): output modalities == {audioOutput} exactly, no audio in.
    func isVoiceModel(_ model: LLMModel) -> Bool {
        let m = model.capabilities.supportedModalities
        switch self {
        case .input:
            return m.inputs == .audioInput
        case .output:
            return m.outputs == .audioOutput && !m.contains(.audioInput)
        }
    }
}
