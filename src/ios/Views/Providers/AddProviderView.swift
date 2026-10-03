import SwiftUI
import UniformTypeIdentifiers

// MARK: - AI Data Sharing Consent

private let aiDataSharingConsentKey = "aiDataSharingConsentAccepted"

/// Full-screen consent view shown before the user can add their first AI provider.
struct AIDataSharingConsentView: View {
    var onAccept: () -> Void
    var onDecline: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("AI Data Sharing Notice", systemImage: "hand.raised.fill")
                            .font(.title2.bold())
                        Text("Please review how your data is handled before adding an AI provider.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    GroupBox {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("We Do Not Collect Your Data")
                                .font(.headline)
                            Text("Minis does not operate any server and does not collect, store, or process any of your personal data. All data stays on your device.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text("However, when you add a third-party AI provider and use it for conversations, the following data may be sent directly from your device to that provider's servers:")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            VStack(alignment: .leading, spacing: 6) {
                                dataItem("Your chat messages and conversation history")
                                dataItem("Files, images, and documents you attach or share")
                                dataItem("System information you authorize (calendar, health, contacts, etc.) when using agent features")
                            }
                        }
                    }

                    GroupBox {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Third-Party AI Providers")
                                .font(.headline)
                            Text("Depending on which provider you configure, your data will be sent directly to one or more of the following third-party services:")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            VStack(alignment: .leading, spacing: 8) {
                                providerRow("Anthropic", description: "Claude models (anthropic.com)", color: .purple)
                                providerRow("OpenAI", description: "GPT and o-series models (openai.com)", color: .green)
                                providerRow("Google", description: "Gemini models (google.com)", color: .blue)
                                providerRow("OpenRouter", description: "Multi-provider routing (openrouter.ai)", color: .cyan)
                                providerRow("Custom Endpoints", description: "Self-hosted or third-party compatible APIs you configure", color: .gray)
                            }

                            Text("Each provider has its own privacy policy and terms of service that govern how they handle your data.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    GroupBox {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Your Control")
                                .font(.headline)

                            VStack(alignment: .leading, spacing: 6) {
                                dataItem("API keys and tokens are stored only in the iOS Keychain on your device and are never sent to us")
                                dataItem("Data is sent only to the specific provider you choose for each conversation")
                                dataItem("You can remove any provider and its credentials at any time from Settings")
                                dataItem("No data is shared with Minis or any other party beyond the provider you select")
                            }
                        }
                    }

                    Link("Read our full Privacy Policy", destination: URL(string: "https://openminis.github.io/privacy-policy.html")!)
                        .font(.footnote)

                    Spacer(minLength: 80)
                }
                .padding()
            }
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 10) {
                    Button {
                        UserDefaults.standard.set(true, forKey: aiDataSharingConsentKey)
                        onAccept()
                    } label: {
                        Text("I Understand & Agree")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                    Button("Cancel", role: .cancel) {
                        onDecline()
                    }
                    .font(.body)
                }
                .padding()
                .background(.bar)
            }
        }
    }

    private func dataItem(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("•")
                .foregroundStyle(.secondary)
            Text(text)
                .font(.subheadline)
        }
    }

    private func providerRow(_ name: String, description: String, color: Color) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(.subheadline.weight(.medium))
                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// Flow to add a new provider instance: pick type → credential → enter key/OAuth → done.
struct AddProviderView: View {
    @ObservedObject private var store = ProviderConfigStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var selectedType: ProviderType?
    @State private var selectedCredential: ProviderCredential?
    @State private var apiKeyInput = ""
    @State private var showApiKeyPlaintext = false
    @State private var labelInput = ""
    /// True once the user has manually edited the label. We use this to
    /// stop `defaultLabel(for:)` from clobbering a label the user just
    /// typed (often a Chinese / non-ASCII name) when they go back and
    /// re-tap the provider-type row. The user report
    /// `T-provider-name-chinese` originated from this footgun:
    /// users perceived the new-instance label as "ASCII only" because
    /// re-touching the provider type silently overwrote whatever they
    /// had typed.
    @State private var labelEdited = false
    /// [T-provider-label-stale-type] True for the duration of a programmatic
    /// `labelInput` seed, so the field's `onChange` can tell our own write from
    /// the user typing. Without it the seed marked the label user-edited and
    /// froze it at the first provider type picked.
    @State private var isSeedingLabel = false
    /// True when the configure step was entered via a Voice Chat Provider
    /// template (credential picker was skipped). Back should then return
    /// straight to the type list, not to a credential step the user never saw.
    @State private var enteredViaVoiceTemplate = false
    /// [T-ios-voice-provider-title] Vendor name to show in the configure step's
    /// title, supplied by the step that opened it. Nil means "the provider type
    /// is the vendor", and the title falls back to `selectedType.displayName`.
    @State private var configuringVendorName: String?
    @State private var customBaseURLInput = ""
    @State private var appendV1SuffixInput = true
    @State private var useResponsesAPI = false
    @State private var manualOAuthTokenInput = ""
    @State private var isSaving = false
    @State private var errorMessage: String?
    @State private var pendingInstanceId = UUID().uuidString
    @State private var pendingOAuthDone = false
    @State private var oauthMaskedToken: String?
    // [T-kimi-oauth] Present the device-code login sheet (user code +
    // verification URL + polling) for Kimi's RFC 8628 flow.
    @State private var showKimiLogin = false
    /// [T-copilot-provider] Same device-code shape as Kimi's.
    @State private var showCopilotLogin = false
    @State private var oauthAuthTime: Date?
    @State private var showDataSharingConsent = false
    @State private var showImportFile = false
    @State private var importMessage: String?
    @State private var showImportResult = false
    @State private var importSucceeded = false
    // [T-add-provider-unsaved-exit-confirm]
    @State private var showUnsavedExitDialog = false

    /// Whether the data-sharing consent has been accepted (persisted in UserDefaults).
    private var consentAccepted: Bool {
        UserDefaults.standard.bool(forKey: aiDataSharingConsentKey)
    }

    private enum Step: Int { case type = 0, credential = 1, configure = 2 }
    private var currentStep: Step {
        if selectedType == nil { return .type }
        if selectedCredential == nil { return .credential }
        return .configure
    }

    @ViewBuilder
    private var steppedContent: some View {
        Group {
            if selectedType == nil {
                typePickerSection
            } else if selectedCredential == nil {
                credentialPickerSection
            } else {
                configureSection
            }
        }
        .transition(.asymmetric(
            insertion: .move(edge: .trailing).combined(with: .opacity),
            removal: .move(edge: .leading).combined(with: .opacity)
        ))
        .id(currentStep)
    }

    // [T-add-provider-unsaved-exit-confirm] Whether the user has typed /
    // authorized anything that would be silently lost on exit. Prefilled
    // defaults don't count: the label only counts once the user edited it,
    // and appendV1Suffix / Responses-API toggles alone carry no secret.
    private var hasUnsavedInput: Bool {
        if pendingOAuthDone { return true }
        if !apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        if !manualOAuthTokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        if labelEdited && !labelInput.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        if !customBaseURLInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        return false
    }

    /// Whether the current input is complete enough that "Save & Exit" can
    /// perform a real save (mirrors the enabled conditions of the visible
    /// save buttons for the three credential paths).
    /// [T-empty-key-compat-endpoints] An empty API key is a valid input when the
    /// instance targets a third-party OpenAI/Anthropic-compatible endpoint
    /// (custom base URL filled in): ollama / LM Studio / LiteLLM / private
    /// relays commonly need no key. Mirrors ProviderInstance.allowsEmptyAPIKey —
    /// OAuth flows and official endpoints keep requiring a credential.
    private var emptyKeyAllowedForCurrentInput: Bool {
        guard selectedCredential == .apiKey,
              !customBaseURLInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let type = selectedType else { return false }
        switch type {
        case .openAI, .openAIResponses, .anthropic:
            return true
        default:
            return false
        }
    }

    private var canSaveCurrentInput: Bool {
        if pendingOAuthDone { return true }
        if selectedCredential == .apiKey,
           !apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || emptyKeyAllowedForCurrentInput { return true }
        if !manualOAuthTokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        return false
    }

    private func saveCurrentInputAndExit() {
        if pendingOAuthDone {
            saveOAuthInstance()
        } else if selectedCredential == .apiKey,
                  !apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || emptyKeyAllowedForCurrentInput {
            saveApiKeyInstance()
        } else if !manualOAuthTokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            saveManualOAuthInstance()
        }
    }

    /// Exit intent chokepoint: confirm when input would be lost, else leave.
    private func requestExit() {
        if hasUnsavedInput {
            showUnsavedExitDialog = true
        } else {
            cleanupAndDismiss()
        }
    }

    var body: some View {
        List {
            steppedContent
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.88), value: currentStep)
        .navigationTitle(navigationTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if selectedType != nil {
                    Button("Back") { goBack() }
                } else {
                    Button("Cancel") { requestExit() }
                }
            }
            // [T-add-provider-unsaved-exit-confirm] Explicit close affordance
            // on the in-flow steps (the leading slot is taken by Back there).
            // Paired with interactiveDismissDisabled below: the pull-down
            // gesture bounces instead of silently discarding, and this button
            // is the visible path to the save/discard choice — the same
            // pattern as Mail's compose sheet.
            ToolbarItem(placement: .topBarTrailing) {
                if selectedType != nil {
                    Button {
                        requestExit()
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel(Text("Cancel"))
                }
            }
        }
        // Block the interactive pull-down while input would be lost; exits
        // then go through requestExit()'s confirmation.
        .interactiveDismissDisabled(hasUnsavedInput)
        .confirmationDialog(
            AppLocalized("You have unsaved provider settings."),
            isPresented: $showUnsavedExitDialog,
            titleVisibility: .visible
        ) {
            if canSaveCurrentInput {
                Button(AppLocalized("Save & Exit")) {
                    saveCurrentInputAndExit()
                }
            }
            Button(AppLocalized("Discard Changes"), role: .destructive) {
                cleanupAndDismiss()
            }
            Button(AppLocalized("Keep Editing"), role: .cancel) {}
        }
        .sheet(isPresented: $showDataSharingConsent) {
            AIDataSharingConsentView(
                onAccept: {
                    showDataSharingConsent = false
                },
                onDecline: {
                    showDataSharingConsent = false
                    cleanupAndDismiss()
                }
            )
            .interactiveDismissDisabled()
        }
        .sheet(isPresented: $showKimiLogin) {
            // [T-kimi-oauth] RFC 8628 device-code login. On success, mark the
            // OAuth step done + surface the masked token, matching startOAuth().
            KimiDeviceLoginSheet(instanceId: pendingInstanceId) { success in
                if success {
                    oauthAuthTime = Date()
                    oauthMaskedToken = loadMaskedToken(type: .kimiCode)
                    pendingOAuthDone = true
                }
            }
        }
        .sheet(isPresented: $showCopilotLogin) {
            // [T-copilot-provider] Device-code login, gated behind the risk
            // notice inside the sheet.
            CopilotDeviceLoginSheet(instanceId: pendingInstanceId) { success in
                if success {
                    oauthAuthTime = Date()
                    oauthMaskedToken = loadMaskedToken(type: .githubCopilot)
                    pendingOAuthDone = true
                }
            }
        }
        .fileImporter(isPresented: $showImportFile, allowedContentTypes: [.json]) { result in
            handleImport(result)
        }
        .alert(AppLocalized("Import"), isPresented: $showImportResult) {
            Button("OK") {
                if importSucceeded { dismiss() }
            }
        } message: {
            if let msg = importMessage { Text(msg) }
        }
        .onAppear {
            if !consentAccepted && store.instances.isEmpty {
                showDataSharingConsent = true
            }
        }
    }

    private func handleImport(_ result: Result<URL, Error>) {
        importSucceeded = false
        switch result {
        case .success(let url):
            guard url.startAccessingSecurityScopedResource() else {
                importMessage = AppLocalized("Cannot access the selected file.")
                showImportResult = true
                return
            }
            defer { url.stopAccessingSecurityScopedResource() }
            guard let data = try? Data(contentsOf: url),
                  let json = String(data: data, encoding: .utf8) else {
                importMessage = AppLocalized("Failed to read file.")
                showImportResult = true
                return
            }
            if let label = store.importInstanceJSON(json) {
                importMessage = AppLocalized("Imported provider \"\(label)\" successfully.")
                importSucceeded = true
            } else {
                importMessage = AppLocalized("Invalid provider configuration file.")
            }
            showImportResult = true
        case .failure:
            break
        }
    }

    private var navigationTitle: String {
        if selectedType != nil, selectedCredential != nil {
            // [T-ios-voice-provider-title] Render the name the CALLER supplied;
            // never re-derive it from the provider type here.
            //
            // `selectedType` is the wire PROTOCOL, not the vendor. Every voice
            // vendor in VoiceProviderTemplate.all — ElevenLabs, Deepgram,
            // Azure TTS — speaks the OpenAI-compatible API, so they all carry
            // `providerType: .openAI` and `type.displayName` reported "OpenAI"
            // for all of them: the sheet said "Configure OpenAI" over a form
            // labelled ElevenLabs with an api.elevenlabs.io base URL.
            //
            // Switching on the vendor here would only move the guess inside
            // this view and would need editing for every vendor added later.
            // The step that KNOWS which vendor was tapped sets
            // `configuringDisplayName` instead, and this just renders it.
            return AppLocalized("Configure \(configuringDisplayName)")
        } else if selectedType != nil {
            return AppLocalized("Auth Method")
        }
        return AppLocalized("Add Provider")
    }

    /// Vendor name for the configure step's title, set by whichever step
    /// advanced into it. Falls back to the provider type's own display name,
    /// which is correct whenever the type IS the vendor (the plain
    /// "Add Provider" path, where the user picked OpenAI/Anthropic/… directly).
    private var configuringDisplayName: String {
        if let explicit = configuringVendorName, !explicit.isEmpty {
            return explicit
        }
        return selectedType?.displayName ?? ""
    }

    // MARK: - Step 1: Pick Provider Type

    private var visibleProviderTypes: [ProviderType] {
        ProviderType.allCases.filter {
            // [T-copilot-provider] The kill switch. Hiding the entry here is
            // [T-copilot-row-consent-only] Copilot is a plain row, like every
            // other OAuth provider here (Claude Code, Codex, xAI, Kimi Code):
            // tap it and the sign-in sheet opens on its consent page, which
            // must be accepted before any request is made.
            //
            // It used to be hidden behind a `copilotProviderEnabled` opt-in
            // switch as well. That was removed: the switch and the consent page
            // carried the SAME warning, so the user read it twice and tapped
            // twice to reach one result, and the switch's own section occupied a
            // permanent block of this screen to control the visibility of a
            // single row. The row's subtitle already states "unofficial — use at
            // your own risk", and `CopilotDeviceLoginSheet`'s consent page is
            // the gate that actually matters, since it stands between the user
            // and the first network call.
            return $0 != .openAIResponses && $0 != .antigravity
                && !$0.isUnsupported
        }
    }

    @ViewBuilder
    private var typePickerSection: some View {
        Section {
            ForEach(visibleProviderTypes, id: \.self) { type in
                Button {
                    selectedType = type
                    // Only seed the label when the user hasn't typed
                    // their own. Without this guard, switching back and
                    // forth between provider types silently overwrites
                    // a Chinese / custom label the user just entered.
                    if !labelEdited {
                        // [T-provider-label-stale-type] Suppress the onChange
                        // that this assignment triggers, so seeding does not
                        // mark the field as user-edited. Set before the write
                        // and cleared by the observer.
                        isSeedingLabel = true
                        labelInput = defaultLabel(for: type)
                    }
                } label: {
                    HStack(spacing: 12) {
                        providerIcon(type)
                            .frame(width: 32, height: 32)
                            .background(providerColor(type).opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 8))

                        VStack(alignment: .leading, spacing: 2) {
                            Text(providerPickerLabel(type))
                                .font(.body.weight(.medium))
                                .foregroundStyle(Color(UIColor.label))
                            Text(type.pickerSubtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }
        } header: {
            Text("Choose Provider")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("You can add multiple instances of the same provider (e.g. work and personal accounts).")
                Text("OpenAI and Anthropic also work with compatible third-party endpoints — set a custom API Base URL after choosing the matching protocol.")
            }
        }

        voiceProviderSection

        Section {
            Button {
                showImportFile = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "square.and.arrow.down")
                        .font(.body)
                        .frame(width: 32, height: 32)
                        .foregroundStyle(Color.accentColor)
                        .background(Color.accentColor.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 8))

                    Text("Or Import Provider from File")
                        .font(.body.weight(.medium))
                        .foregroundStyle(Color(UIColor.label))

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
        } footer: {
            Text("Import a provider configuration that was exported from another device.")
        }
    }

    /// Voice-specialised vendor templates. Tapping one preseeds the standard
    /// add-provider flow (underlying type + base URL) and jumps to the key step.
    @ViewBuilder
    private var voiceProviderSection: some View {
        let templates = VoiceProviderTemplate.all
        let notes = templates.compactMap { $0.note }
        Section {
            ForEach(templates) { template in
                Button {
                    applyVoiceTemplate(template)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: template.symbol)
                            .font(.body)
                            .foregroundStyle(template.tint)
                            .frame(width: 32, height: 32)
                            .background(template.tint.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 8))

                        VStack(alignment: .leading, spacing: 2) {
                            Text(template.name)
                                .font(.body.weight(.medium))
                                .foregroundStyle(Color(UIColor.label))
                            Text(template.capability)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }
        } header: {
            Text("Voice Chat Providers")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Speech-to-text and text-to-speech vendors. The provider type and base URL are prefilled — just add your API key.")
                ForEach(notes, id: \.self) { note in
                    Text(note)
                }
            }
        }
    }

    /// Preseed the flow from a voice template: pick the underlying protocol,
    /// prefill the base URL / label, and advance straight to the configure step.
    private func applyVoiceTemplate(_ template: VoiceProviderTemplate) {
        selectedType = template.providerType
        customBaseURLInput = template.baseURL
        appendV1SuffixInput = template.appendV1
        if !labelEdited {
            labelInput = template.name
            labelEdited = true   // keep the vendor name from being overwritten by defaultLabel
        }
        selectedCredential = .apiKey
        enteredViaVoiceTemplate = true
        // [T-ios-voice-provider-title] This is the one place that knows which
        // vendor was tapped — `template.providerType` is only the protocol it
        // speaks. Hand the name to the configure step rather than letting it
        // guess from the type.
        configuringVendorName = template.name
    }

    // MARK: - Step 2: Pick Credential Type

    private var credentialPickerSection: some View {
        Group {
            Section {
                if let type = selectedType {
                    let creds = availableCredentials(for: type)
                    ForEach(creds, id: \.self) { cred in
                        Button {
                            selectedCredential = cred
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: cred == .apiKey ? "key" : "person.badge.shield.checkmark")
                                    .font(.body)
                                    .frame(width: 28)
                                    .foregroundStyle(Color(UIColor.label))

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(cred == .apiKey ? "API Key" : "OAuth")
                                        .font(.body.weight(.medium))
                                        .foregroundStyle(Color(UIColor.label))
                                    Text(credentialDescription(type: type, credential: cred))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }

                                Spacer()

                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                    }
                }
            } header: {
                Text("Authentication")
            }

            // "Responses API" is now integrated as API Format picker in Step 3
        }
    }

    // MARK: - Step 3: Configure

    private var configureSection: some View {
        Group {
            Section("Label") {
                TextField("Provider label", text: $labelInput)
                    // [T-provider-label-keyboard] Declare the keyboard EXPLICITLY.
                    // A TextField that sets no keyboardType does not get a
                    // guaranteed default — UIKit reuses the type from the input
                    // session that was active a moment ago, so arriving here right
                    // after a numeric field (Context Window / Max Output Tokens,
                    // both `.numberPad`) or a credential field could raise a number
                    // pad on what is plain prose. Saying `.default` costs nothing
                    // and removes the dependence on whatever was focused before.
                    .keyboardType(.default)
                    // [T-provider-label-keyboard] `.none` alone did NOT fix the
                    // reported "Label opens the password keyboard": AutoFill
                    // decides the username/password pairing from the PASSWORD
                    // field, so the real opt-out lives on the SecureField in
                    // `apiKeySection` (and the Bearer Token one). Kept here as
                    // the matching half, paired with `.username` being explicitly
                    // NOT used, so nothing re-associates it.
                    //
                    // [issue #364] Upgraded from `.none` to `.nickname`. `.none`
                    // compiles to nil — byte identical to declaring nothing — so it
                    // only withheld an explicit type and left the heuristic free to
                    // guess. A positive non-credential marker states what the field
                    // is. Defence in depth: the credential-side `.oneTimeCode`
                    // below remains the load-bearing half, but #364 was reported on
                    // iOS 27 against a build that already had it, so the Label side
                    // should not merely be silent.
                    .textContentType(.nickname)
                    // A label is a proper noun the user is naming, and AutoFill
                    // must never offer to save it as a credential.
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.words)
                    .onChange(of: labelInput) { _ in
                        // Mark the field as user-edited so subsequent
                        // provider-type taps no longer clobber it.
                        //
                        // [T-provider-label-stale-type] This must ignore OUR OWN
                        // writes. `onChange` cannot tell typing from a
                        // programmatic assignment, and the type-tap handler
                        // assigns `labelInput` too — so seeding marked the field
                        // "edited" immediately, and every LATER type tap was
                        // refused by the `!labelEdited` guard. Result: pick
                        // Anthropic, go back, pick GitHub Copilot, and the label
                        // still read "Anthropic 5" under a "GitHub Copilot" title
                        // — then saved the instance under that wrong name.
                        //
                        // An explicit flag rather than comparing the new value
                        // against `defaultLabel`: a user who deliberately types
                        // the default text ("GitHub Copilot") means it, and
                        // value-comparison would read that as not-edited and let
                        // the next type tap overwrite it — the very clobbering
                        // `labelEdited` exists to prevent.
                        if isSeedingLabel {
                            isSeedingLabel = false
                            return
                        }
                        labelEdited = true
                    }
            }

            if selectedCredential == .apiKey {
                apiKeySection
            } else {
                oauthSection
            }

            if let error = errorMessage {
                Section {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                }
            }
        }
    }

    private var apiKeySection: some View {
        Group {
            Section {
                    HStack {
                        if showApiKeyPlaintext {
                            TextField(keyPlaceholder, text: $apiKeyInput)
                                .font(.system(.body, design: .monospaced))
                                // [T-provider-label-keyboard] Same opt-out as the
                                // SecureField below — the plaintext branch is the
                                // SAME field, so leaving it undeclared would let
                                // AutoFill re-attach the moment the user taps the
                                // eye toggle.
                                .textContentType(.oneTimeCode)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        } else {
                            SecureField(keyPlaceholder, text: $apiKeyInput)
                                // [T-provider-label-keyboard] Opt the credential
                                // field OUT of AutoFill's password association.
                                //
                                // An undeclared SecureField carries `.password`
                                // semantics, which makes iOS treat the enclosing
                                // form as a login form and go looking for the
                                // matching USERNAME field. It picks the nearest
                                // preceding text field — here that is "Label" —
                                // and hangs the "密码 / Password" AutoFill bar on
                                // it, which is what the user sees and reports as
                                // "the Label field opens a password keyboard".
                                //
                                // The earlier attempt at this fixed the wrong end:
                                // `.textContentType(.none)` on the Label field does
                                // NOT opt out of being chosen as the username half
                                // of a pair — the association is driven by the
                                // password field, so the declaration has to go
                                // here. `.oneTimeCode` is the reliable "this is a
                                // credential, but not a saveable account password"
                                // marker: it suppresses the strong-password /
                                // save-to-Keychain flow and the username pairing,
                                // while SecureField keeps doing the masking.
                                .textContentType(.oneTimeCode)
                        }
                        Button {
                            showApiKeyPlaintext.toggle()
                        } label: {
                            Image(systemName: showApiKeyPlaintext ? "eye.slash" : "eye")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("API Key")
                } footer: {
                    Text("Your key is stored securely in the iOS Keychain and never leaves the device.")
                }

            if selectedType != .antigravity {
                customBaseURLSection
            }

            // API Format picker for OpenAI provider (hidden for voice-only templates)
            if !enteredViaVoiceTemplate, selectedType == .openAI || selectedType == .openAIResponses {
                Section {
                    Picker("API Format", selection: $useResponsesAPI) {
                        Text("Chat Completions").tag(false)
                        Text("Responses API").tag(true)
                    }
                } footer: {
                    Text(useResponsesAPI
                        ? "Uses /v1/responses endpoint format. Required for some Responses-API-only services."
                        : "Standard /v1/chat/completions format. Compatible with most OpenAI-compatible services.")
                }
            }

            Section {
                Button {
                    saveApiKeyInstance()
                } label: {
                    HStack {
                        Spacer()
                        if isSaving {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Text("Add Provider")
                                .font(.body.weight(.semibold))
                        }
                        Spacer()
                    }
                }
                .disabled((apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !emptyKeyAllowedForCurrentInput) || isSaving)
            }
        }
    }

    @ViewBuilder
    private var oauthSection: some View {
        Section {
            if pendingOAuthDone {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text("Authenticated")
                        .font(.body.weight(.medium))
                }

                if let masked = oauthMaskedToken {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Token")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(masked)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }

                if let authTime = oauthAuthTime {
                    HStack {
                        Text("Authorized")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(authTime, style: .date)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(authTime, style: .time)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                Button {
                    // Kimi uses a device-code sheet (RFC 8628); every other
                    // provider drives its redirect/PKCE flow inline via startOAuth.
                    if selectedType == .kimiCode {
                        showKimiLogin = true
                    } else if selectedType == .githubCopilot {
                        showCopilotLogin = true
                    } else {
                        Task { await startOAuth() }
                    }
                } label: {
                    HStack {
                        Spacer()
                        Text(oauthSignInLabel)
                            .font(.body.weight(.semibold))
                        Spacer()
                    }
                }
            }
        } header: {
            Text("OAuth")
        }

        // Manual OAuth entry — available for all providers (supports proxy services, Coding Plan tokens, etc.)
        //
        // [T-copilot-oauth-only] …except Copilot, which is excluded for the same
        // reason Antigravity is: its factory branch never looks the token up.
        // Most providers read the `manual-oauth-token` Keychain entry this block
        // writes; `makeCopilotProvider` does not — it wires `oauthTokenProvider`
        // to the session-token minter unconditionally. So a token pasted here was
        // stored, shown as a configured credential, and then ignored on every
        // request. There is also no token a user could paste: the credential is
        // minted from a GitHub OAuth token and lives ~25 minutes.
        if selectedType != .antigravity && selectedType != .githubCopilot && !pendingOAuthDone {
            Section {
                TextField(defaultBaseURL, text: $customBaseURLInput)
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)

                SecureField("Bearer Token", text: $manualOAuthTokenInput)
                    .font(.system(.body, design: .monospaced))
                    // [T-provider-label-keyboard] Same AutoFill opt-out as the
                    // API Key field — this SecureField is in the same form as
                    // the Label field and would otherwise trigger the identical
                    // username/password pairing.
                    .textContentType(.oneTimeCode)

                Button {
                    saveManualOAuthInstance()
                } label: {
                    HStack {
                        Spacer()
                        Text("Add Provider")
                            .font(.body.weight(.semibold))
                        Spacer()
                    }
                }
                .disabled(manualOAuthTokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } header: {
                Text("Or Configure Manually")
            } footer: {
                Text("For third-party Coding Plans (e.g. MiniMax, Kimi) or compatible proxy endpoints, enter the API base URL and bearer token manually.")
            }
        }

        if pendingOAuthDone {
            Section {
                Button {
                    saveOAuthInstance()
                } label: {
                    HStack {
                        Spacer()
                        Text("Save Provider")
                            .font(.body.weight(.semibold))
                        Spacer()
                    }
                }
            }
        }
    }

    // MARK: - Custom Base URL Section

    private var customBaseURLSection: some View {
        Section {
            TextField(defaultBaseURL, text: $customBaseURLInput)
                .font(.system(.body, design: .monospaced))
                .foregroundStyle(.secondary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)

            if !enteredViaVoiceTemplate, selectedType != .gemini {
                Toggle("Auto Append \"/v1\"", isOn: $appendV1SuffixInput)
            }
        } header: {
            Text("Custom API Base (Optional)")
        } footer: {
            if enteredViaVoiceTemplate {
                Text("The voice service endpoint URL.")
            } else if selectedType == .gemini {
                Text("Leave empty to use the default Google endpoint. Enter the full base URL including version path.")
            } else {
                Text(appendV1SuffixInput
                     ? "Leave empty to use the default endpoint. \"/v1\" is appended automatically — enter the base host only."
                     : "The URL is used verbatim. Include the full path up to (but not including) the endpoint.")
            }
        }
    }

    private var defaultBaseURL: String {
        switch selectedType {
        case .anthropic: return "https://api.anthropic.com"
        case .openAI: return "https://api.openai.com"
        case .gemini: return "https://generativelanguage.googleapis.com/v1beta"
        case .openRouter: return "https://openrouter.ai/api/v1"
        case .openAIResponses: return "https://api.openai.com"
        case .xAI: return "https://api.x.ai/v1"
        case .kimiCode: return "https://api.kimi.com/coding"
        case .githubCopilot: return CopilotConstants.apiBaseURL
        default: return "https://api.example.com"
        }
    }

    // MARK: - Actions

    private func saveApiKeyInstance() {
        guard let type = selectedType else { return }
        let trimmedKey = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedLabel = labelInput.trimmingCharacters(in: .whitespaces)
        let trimmedBase = customBaseURLInput.trimmingCharacters(in: .whitespacesAndNewlines)
        // [T-empty-key-compat-endpoints] Empty key is allowed for third-party
        // compatible endpoints (see emptyKeyAllowedForCurrentInput).
        guard !trimmedKey.isEmpty || emptyKeyAllowedForCurrentInput else { return }

        // Map OpenAI + Responses API format to .openAIResponses provider type
        let effectiveType: ProviderType = (type == .openAI && useResponsesAPI) ? .openAIResponses : type

        isSaving = true
        let base = trimmedBase.isEmpty ? nil : trimmedBase
        let instance = ProviderInstance(
            label: trimmedLabel.isEmpty ? defaultLabel(for: effectiveType) : trimmedLabel,
            providerType: effectiveType,
            credentialType: .apiKey,
            customBaseURL: base,
            appendV1Suffix: appendV1SuffixInput
        )
        // Nothing to store for a keyless endpoint — and never write an empty
        // string to the Keychain (hasAnyCredential treats empty as absent).
        if !trimmedKey.isEmpty {
            ProviderKeychainHelper.saveAPIKey(trimmedKey, instanceId: instance.id)
        }
        store.addInstance(instance)
        isSaving = false
        dismiss()
    }

    private func saveOAuthInstance() {
        guard let type = selectedType else { return }
        let trimmedLabel = labelInput.trimmingCharacters(in: .whitespaces)

        let instance = ProviderInstance(
            id: pendingInstanceId,
            label: trimmedLabel.isEmpty ? defaultLabel(for: type) : trimmedLabel,
            providerType: type,
            credentialType: .oauth
        )
        store.addInstance(instance)
        dismiss()
    }

    private func saveManualOAuthInstance() {
        guard let type = selectedType else { return }
        let trimmedToken = manualOAuthTokenInput.components(separatedBy: .whitespacesAndNewlines).joined()
        let trimmedLabel = labelInput.trimmingCharacters(in: .whitespaces)
        let trimmedBase = customBaseURLInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedToken.isEmpty else { return }

        let instance = ProviderInstance(
            id: pendingInstanceId,
            label: trimmedLabel.isEmpty ? defaultLabel(for: type) : trimmedLabel,
            providerType: type,
            credentialType: .oauth,
            customBaseURL: trimmedBase.isEmpty ? nil : trimmedBase,
            appendV1Suffix: appendV1SuffixInput
        )
        ProviderKeychainHelper.saveOAuthString(trimmedToken, instanceId: instance.id, account: "manual-oauth-token")
        store.addInstance(instance)
        dismiss()
    }

    @MainActor
    private func startOAuth() async {
        guard let type = selectedType else { return }
        errorMessage = nil
        do {
            switch type {
            case .anthropic: try await ClaudeOAuthManager.shared.login(instanceId: pendingInstanceId)
            case .gemini: try await GeminiOAuthManager.shared.login(instanceId: pendingInstanceId)
            case .openAI: try await CodexOAuthManager.shared.login(instanceId: pendingInstanceId)
            case .antigravity: try await AntigravityOAuthManager.shared.login(instanceId: pendingInstanceId)
            case .openRouter: try await OpenRouterOAuthManager.shared.login(instanceId: pendingInstanceId)
            case .openAIResponses: break // API key only, no OAuth
            case .xAI: try await XAIOAuthManager.shared.login(instanceId: pendingInstanceId)
            case .kimiCode: break // device-code flow runs in KimiDeviceLoginSheet, not here
            // [T-copilot-provider] Same shape — the device flow lives in
            // CopilotDeviceLoginSheet so the risk notice is shown before it starts.
            case .githubCopilot: break
            case .unsupported: break // free / unsupported — no OAuth
            }
            oauthAuthTime = Date()
            oauthMaskedToken = loadMaskedToken(type: type)
            pendingOAuthDone = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Loads the access token from Keychain and masks the middle third.
    private func loadMaskedToken(type: ProviderType) -> String? {
        let token: String?
        switch type {
        case .anthropic:
            token = ProviderKeychainHelper.loadOAuthToken(instanceId: pendingInstanceId, as: ClaudeTokenStorage.self)?.accessToken
        case .gemini:
            token = ProviderKeychainHelper.loadOAuthToken(instanceId: pendingInstanceId, as: GeminiTokenStorage.self)?.accessToken
        case .openAI:
            token = ProviderKeychainHelper.loadOAuthToken(instanceId: pendingInstanceId, as: CodexTokenStorage.self)?.accessToken
        case .antigravity:
            token = ProviderKeychainHelper.loadOAuthToken(instanceId: pendingInstanceId, as: AntigravityTokenStorage.self)?.accessToken
        case .openRouter:
            // OpenRouter OAuth produces a permanent API key, not an OAuth token
            token = ProviderKeychainHelper.loadAPIKey(instanceId: pendingInstanceId)
        case .openAIResponses:
            token = nil // API key only, no OAuth
        case .xAI:
            token = ProviderKeychainHelper.loadOAuthToken(instanceId: pendingInstanceId, as: XAITokenStorage.self)?.accessToken
        case .kimiCode:
            token = ProviderKeychainHelper.loadOAuthToken(instanceId: pendingInstanceId, as: KimiTokenStorage.self)?.accessToken
        case .githubCopilot:
            // The GitHub token is the credential worth showing; the session
            // token is derived and rotates every ~25 minutes.
            token = ProviderKeychainHelper.loadOAuthToken(instanceId: pendingInstanceId, as: CopilotTokenStorage.self)?.githubToken
        case .unsupported:
            token = nil // free / unsupported — no token
        }
        guard let t = token, !t.isEmpty else { return nil }
        return ClaudeOAuthManager.maskToken(t)
    }

    private func goBack() {
        // Voice-template entry skipped the credential step, so Back returns
        // all the way to the type list (and clears the prefilled fields) rather
        // than dropping the user onto a credential picker they never saw.
        if enteredViaVoiceTemplate {
            enteredViaVoiceTemplate = false
            configuringVendorName = nil
            selectedType = nil
            selectedCredential = nil
            apiKeyInput = ""
            customBaseURLInput = ""
            appendV1SuffixInput = true
            labelInput = ""
            labelEdited = false
            errorMessage = nil
            return
        }
        if selectedCredential != nil {
            // If we started OAuth but haven't saved, clean up the pending token
            if selectedCredential == .oauth && pendingOAuthDone {
                ProviderKeychainHelper.deleteOAuthToken(instanceId: pendingInstanceId)
                ProviderKeychainHelper.deleteOAuthString(instanceId: pendingInstanceId, account: "oauth-email")
                ProviderKeychainHelper.deleteOAuthString(instanceId: pendingInstanceId, account: "oauth-gcp-project")
                ProviderKeychainHelper.deleteOAuthString(instanceId: pendingInstanceId, account: "manual-oauth-token")
                // OpenRouter OAuth stores a permanent API key, not an OAuth token
                if selectedType == .openRouter {
                    ProviderKeychainHelper.deleteAPIKey(instanceId: pendingInstanceId)
                }
                pendingOAuthDone = false
                oauthMaskedToken = nil
                oauthAuthTime = nil
                pendingInstanceId = UUID().uuidString
            }
            selectedCredential = nil
            useResponsesAPI = false
            apiKeyInput = ""
            customBaseURLInput = ""
            manualOAuthTokenInput = ""
            errorMessage = nil
        } else {
            selectedType = nil
            selectedCredential = nil
        }
    }

    private func cleanupAndDismiss() {
        // If we started OAuth but never saved, clean up
        if pendingOAuthDone {
            ProviderKeychainHelper.deleteOAuthToken(instanceId: pendingInstanceId)
            ProviderKeychainHelper.deleteOAuthString(instanceId: pendingInstanceId, account: "oauth-email")
            ProviderKeychainHelper.deleteOAuthString(instanceId: pendingInstanceId, account: "oauth-gcp-project")
            if selectedType == .openRouter {
                ProviderKeychainHelper.deleteAPIKey(instanceId: pendingInstanceId)
            }
            ProviderKeychainHelper.deleteOAuthString(instanceId: pendingInstanceId, account: "manual-oauth-token")
        }
        dismiss()
    }

    // MARK: - Helpers

    private var oauthSignInLabel: String {
        guard let type = selectedType else { return AppLocalized("Sign In") }
        switch type {
        case .anthropic: return AppLocalized("Sign in with Claude")
        case .gemini: return AppLocalized("Sign in with Google")
        case .openAI: return AppLocalized("Sign in with OpenAI")
        case .antigravity: return AppLocalized("Sign in with Google")
        case .openRouter: return AppLocalized("Sign in with OpenRouter")
        case .openAIResponses: return AppLocalized("Sign In") // Not reachable — API key only
        case .xAI: return AppLocalized("Sign in with xAI")
        case .kimiCode: return AppLocalized("Sign in with Kimi Code")
        case .githubCopilot: return AppLocalized("Sign in with GitHub")
        case .unsupported: return AppLocalized("Sign In")
        }
    }

    private var keyPlaceholder: String {
        guard let type = selectedType else { return "API Key" }
        switch type {
        case .anthropic: return "sk-ant-..."
        case .gemini: return "Gemini API Key..."
        case .openAI: return "sk-..."
        case .antigravity: return "API Key..."
        case .openRouter: return "sk-or-..."
        case .openAIResponses: return "sk-..."
        case .xAI: return "xai-..."
        case .kimiCode: return "" // OAuth only
        case .githubCopilot: return "" // OAuth only
        case .unsupported: return ""
        }
    }

    private func providerPickerLabel(_ type: ProviderType) -> String {
        switch type {
        case .anthropic: return AppLocalized("Anthropic / Compatible API")
        case .openAI:    return AppLocalized("OpenAI / Compatible API")
        case .gemini:    return type.displayName
        case .antigravity: return type.displayName
        case .openRouter: return "OpenRouter"
        case .openAIResponses: return "Responses API"
        case .xAI: return "xAI (Grok)"
        case .kimiCode: return "Kimi Code"
        case .githubCopilot: return "GitHub Copilot"
        case .unsupported: return AppLocalized("Unsupported")
        }
    }

    private func defaultLabel(for type: ProviderType) -> String {
        let existingCount = store.instances.filter { $0.providerType == type }.count
        if existingCount == 0 {
            return type.displayName
        }
        return "\(type.displayName) \(existingCount + 1)"
    }

    private func availableCredentials(for type: ProviderType) -> [ProviderCredential] {
        switch type {
        case .antigravity:
            return [.oauth]
        // [T-copilot-oauth-only] Copilot has no API-key form. There is no key a
        // user can obtain for `api.githubcopilot.com`: the only credential is a
        // short-lived session token minted from a GitHub OAuth token, which is
        // why `makeCopilotProvider` wires `oauthTokenProvider` unconditionally
        // and never reads `credentialType`, and why the model fetch is a single
        // `case (.githubCopilot, _)` OAuth branch.
        //
        // Falling into the `default` below offered an "Or configure manually"
        // Base URL + Bearer Token block that could never work — anything typed
        // there was ignored by the factory. Offering a credential the provider
        // cannot use is worse than offering none: it looks like the supported
        // path and fails with no explanation.
        case .githubCopilot:
            return [.oauth]
        case .openAIResponses, .gemini:
            return [.apiKey]
        default:
            return [.apiKey, .oauth]
        }
    }

    private func credentialDescription(type: ProviderType, credential: ProviderCredential) -> String {
        switch (type, credential) {
        case (.openAI, .apiKey):
            return AppLocalized("Supports OpenAI official API and third-party services like OpenRouter, MiniMax, etc.")
        case (.openAIResponses, .apiKey):
            return AppLocalized("Use an API key for a Responses API endpoint")
        case (_, .apiKey):
            return AppLocalized("Use an API key from your \(type.displayName) account")
        case (.anthropic, .oauth):
            return AppLocalized("Sign in with your Claude account")
        case (.gemini, .oauth):
            return AppLocalized("Sign in with Google for Cloud Code Assist")
        case (.openAI, .oauth):
            return AppLocalized("Sign in with OpenAI Codex")
        case (.antigravity, .oauth):
            return AppLocalized("Sign in with Google for Antigravity Cloud Code")
        case (.openRouter, .oauth):
            return AppLocalized("Sign in with your OpenRouter account")
        case (.openAIResponses, .oauth):
            return "" // Not reachable — API key only
        case (.xAI, .oauth):
            return AppLocalized("Sign in with your SuperGrok / X Premium+ subscription. xAI may restrict API access on some plans — if you hit HTTP 403, switch to an API key.")
        case (.kimiCode, .oauth):
            return AppLocalized("Sign in with your Kimi Code / Coding Plan subscription.")
        case (.kimiCode, .apiKey):
            return AppLocalized("Use a Kimi Coding API key.")
        case (.githubCopilot, _):
            // [T-copilot-provider] The risk warning belongs where the user
            // decides, not only inside the sign-in sheet.
            return AppLocalized("Unofficial integration. Using it may get your GitHub Copilot account restricted — use at your own risk.")
        case (.unsupported, _):
            return AppLocalized("This provider isn't supported in this app version.")
        }
    }

    @ViewBuilder
    private func providerIcon(_ type: ProviderType) -> some View {
        switch type {
        case .anthropic:
            Image(systemName: "sparkles")
                .foregroundStyle(.purple)
        case .gemini:
            Image(systemName: "diamond")
                .foregroundStyle(.blue)
        case .openAI:
            Image(systemName: "circle.hexagongrid")
                .foregroundStyle(.green)
        case .antigravity:
            Image(systemName: "ant")
                .foregroundStyle(.orange)
        case .openRouter:
            Image(systemName: "arrow.triangle.branch")
                .foregroundStyle(.cyan)
        case .openAIResponses:
            Image(systemName: "arrow.trianglehead.2.counterclockwise")
                .foregroundStyle(.mint)
        case .xAI:
            Image(systemName: "x.circle")
                .foregroundStyle(.gray)
        case .kimiCode:
            Image(systemName: "moon.stars")
                .foregroundStyle(.indigo)
        case .githubCopilot:
            Image(systemName: "chevron.left.forwardslash.chevron.right")
                .foregroundStyle(.primary)
        case .unsupported:
            Image(systemName: "questionmark.circle")
                .foregroundStyle(.gray)
        }
    }

    private func providerColor(_ type: ProviderType) -> Color {
        switch type {
        case .anthropic: return .purple
        case .gemini: return .blue
        case .openAI: return .green
        case .antigravity: return .orange
        case .openRouter: return .cyan
        case .openAIResponses: return .mint
        case .xAI: return .gray
        case .kimiCode: return .indigo
        case .githubCopilot: return .primary
        case .unsupported: return .gray
        }
    }
}

