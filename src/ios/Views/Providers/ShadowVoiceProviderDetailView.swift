import SwiftUI

// MARK: - Shadow Voice Provider detail [T-mimo-shadow-voice]
//
// A read-only mirror of a Chat/OpenAI instance's voice capability, shown in
// Voice Services. It does NOT own credentials or a base URL — those come from
// the underlying instance (edit them in that instance's detail page). This view
// lists the instance's audio-modality models (ASR / TTS) and offers a per-user
// toggle to hide this shadow row from Voice Services.

struct ShadowVoiceProviderDetailView: View {
    let instanceId: String
    @ObservedObject private var store = ProviderConfigStore.shared
    @State private var quickTestEntry: ModelEntry?

    private var instance: ProviderInstance? { store.instance(for: instanceId) }
    private var shadow: ProviderConfigStore.ShadowVoiceProvider? {
        store.shadowVoiceProviders().first { $0.instanceId == instanceId }
    }

    var body: some View {
        List {
            Section {
                LabeledContent {
                    Text(instance?.label ?? "—")
                        .foregroundStyle(.secondary)
                } label: {
                    Label {
                        Text("Credential from", comment: "Shadow voice credential label")
                    } icon: {
                        styledIcon("key.fill", color: .blue)
                    }
                }
                if let base = instance?.effectiveCustomBaseURL {
                    LabeledContent {
                        Text(base)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    } label: {
                        Label {
                            Text("Endpoint", comment: "Shadow voice endpoint label")
                        } icon: {
                            styledIcon("link", color: .teal)
                        }
                    }
                }
            } footer: {
                Text("This voice service shares the API key and endpoint of its provider. To change them, edit that provider.",
                     comment: "Shadow voice provenance note")
            }

            if let asr = shadow?.inputModels, !asr.isEmpty {
                Section(AppLocalized("Speech to Text", comment: "ASR section")) {
                    ForEach(asr) { entry in
                        modelRow(entry, systemImage: "waveform.and.mic", color: .purple)
                    }
                }
            }

            if let tts = shadow?.outputModels, !tts.isEmpty {
                Section(AppLocalized("Text to Speech", comment: "TTS section")) {
                    ForEach(tts) { entry in
                        modelRow(entry, systemImage: "speaker.wave.2.fill", color: .green)
                    }
                }
            }

            Section {
                Toggle(isOn: Binding(
                    get: { !store.isVoiceShadowDisabled(instanceId) },
                    set: { store.setVoiceShadowDisabled(!$0, for: instanceId) }
                )) {
                    Text("Show in Voice Services", comment: "Shadow voice enable toggle")
                }
            } footer: {
                Text("Turn off to hide this voice service. The provider's text models are unaffected.",
                     comment: "Shadow voice toggle note")
            }
        }
        .navigationTitle(shadow?.displayName ?? instance?.label ?? AppLocalized("Voice Service"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $quickTestEntry) { entry in
            // [T-quicktest-stale-session] Fresh identity per model — see
            // UnifiedModelPicker: swipe-dismiss could reuse the previous
            // sheet identity and its stale TestSession.
            ModelQuickTestSheet(entry: entry)
                .id(entry.id)
                .presentationDetents([.medium, .large])
        }
    }

    private func styledIcon(_ systemName: String, color: Color) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 9))
            .foregroundStyle(.white)
            .frame(width: 21, height: 21)
            .background(color, in: Circle())
    }

    private func modelRow(_ entry: ModelEntry, systemImage: String, color: Color) -> some View {
        Button {
            quickTestEntry = entry
        } label: {
            HStack(spacing: 10) {
                styledIcon(systemImage, color: color)
                Text(entry.baseModel.displayName)
                    .foregroundStyle(ChatColors.primaryText)
                Spacer()
                Image(systemName: "bolt.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }
}
