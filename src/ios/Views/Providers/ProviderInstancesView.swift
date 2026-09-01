import SwiftUI
import UniformTypeIdentifiers

/// Lists all configured provider instances grouped by provider type.
/// Replaces the old ProvidersView for the new multi-instance model.
struct ProviderInstancesView: View {
    @ObservedObject private var store = ProviderConfigStore.shared
    @State private var showAddProvider = false
    @State private var showImportFile = false
    @State private var importMessage: String?
    @State private var showImportResult = false
    @State private var forceSyncToast: String?
    @AppStorage("cloudSync.v2.enabled") private var iCloudSyncEnabled: Bool = false
    /// [T-provider-group-swipe-actions] Swipe-to-edit target. Value-driven so
    /// the swipe opens the SAME detail screen the row's NavigationLink does,
    /// rather than a second editor that could drift from it.
    @State private var editingInstanceId: String?
    /// Swipe-to-delete target. Deletion goes through the existing confirmation
    /// alert — a provider carries API keys and every model entry under it, so
    /// it must never be a one-swipe irreversible act.
    @State private var pendingDeleteInstance: ProviderInstance?

    var body: some View {
        List {
            // [T-mimo-shadow-voice] EVERY instance appears under its providerType
            // section now (no voice-only exclusion) — a mixed vendor like MiMo
            // shows its text models here AND a shadow voice row below ("dual
            // visibility"). Pure-voice vendors (ElevenLabs etc.) still appear here
            // too; they just have no usable text models, which is expected.
            ForEach(ProviderType.allCases, id: \.self) { type in
                let instancesOfType = store.instances.filter { $0.providerType == type }
                if !instancesOfType.isEmpty {
                    Section(type.displayName) {
                        ForEach(instancesOfType) { instance in
                            NavigationLink {
                                ProviderInstanceDetailView(instanceId: instance.id)
                            } label: {
                                InstanceRow(instance: instance)
                            }
                            // [T-provider-group-swipe-actions] Swipe actions and
                            // `.onMove` coexist without extra work: UIKit routes a
                            // horizontal drag to the row's swipe actions and a
                            // vertical one to the reorder engine, and in edit mode
                            // the drag handles take over entirely. No custom
                            // gesture is introduced here precisely so that
                            // arbitration stays UIKit's.
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                // Destructive first = rightmost, the platform's
                                // position for Delete. `allowsFullSwipe: false`
                                // so a long swipe cannot delete a provider (and
                                // its keys) without the confirmation below.
                                Button(role: .destructive) {
                                    pendingDeleteInstance = instance
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                Button {
                                    editingInstanceId = instance.id
                                } label: {
                                    Label("Edit", systemImage: "pencil")
                                }
                                .tint(.blue)
                            }
                        }
                        .onMove { source, destination in
                            moveInstances(in: type, from: source, to: destination)
                        }
                    }
                }
            }
            // [T-mimo-shadow-voice] Voice Services = a read-only SHADOW view of any
            // instance that has audio-modality models, FOLDED by base URL so two
            // instances on the same host show one row (shares that instance's
            // credential/endpoint). Tapping opens the shadow detail (voice models
            // + a "disable this voice row" toggle).
            let shadows = store.shadowVoiceProviders()
            if !shadows.isEmpty {
                Section("Voice Services") {
                    if store.hasFoldedShadowDuplicates() {
                        // Non-destructive hint: same MiMo service configured twice.
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "info.circle")
                                .foregroundStyle(.secondary)
                            Text("A voice service is configured more than once. Voice is merged into a single row here; you can delete the extra provider above if you want.",
                                 comment: "Duplicate voice provider hint")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    ForEach(shadows) { shadow in
                        NavigationLink {
                            ShadowVoiceProviderDetailView(instanceId: shadow.instanceId)
                        } label: {
                            ShadowVoiceRow(shadow: shadow)
                        }
                    }
                }
            }

            if store.instances.isEmpty {
                Section {
                    VStack(spacing: 8) {
                        Image(systemName: "key.slash")
                            .font(.system(size: 32))
                            .foregroundStyle(.quaternary)
                        Text("No providers configured")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text("Add a provider to get started.")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                }
            }
        }
        .navigationTitle("Providers")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !store.instances.isEmpty {
                ToolbarItem(placement: .topBarLeading) {
                    EditButton()
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        showAddProvider = true
                    } label: {
                        Label(AppLocalized("Add Provider"), systemImage: "plus")
                    }
                    Button {
                        showImportFile = true
                    } label: {
                        Label(AppLocalized("Import Provider"), systemImage: "square.and.arrow.down")
                    }
                    if #available(iOS 17.0, *), iCloudSyncEnabled {
                        Divider()
                        Button {
                            Task { await forceSyncProviders() }
                        } label: {
                            Label(AppLocalized("Force iCloud Sync"),
                                  systemImage: "arrow.triangle.2.circlepath.icloud")
                        }
                    }
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showAddProvider) {
            NavigationStack {
                AddProviderView()
            }
        }
        .fileImporter(isPresented: $showImportFile, allowedContentTypes: [.json]) { result in
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
                } else {
                    importMessage = AppLocalized("Invalid provider configuration file.")
                }
                showImportResult = true
            case .failure:
                break
            }
        }
        // [T-provider-group-swipe-actions] Swipe-Edit lands on the very screen
        // the row taps into, so there is one provider editor, not two.
        //
        // `NavigationLink(isActive:)` rather than `navigationDestination(item:)`:
        // this target still deploys to iOS 16 and that modifier is 17+ (same
        // reason BackupSettingsView uses the hidden-link form). Kept in a
        // `.background` so it adds no visible row.
        .background {
            NavigationLink(isActive: Binding(
                get: { editingInstanceId != nil },
                set: { if !$0 { editingInstanceId = nil } }
            )) {
                // Resolved at push time: if the provider was deleted while this
                // was open, show nothing rather than a detail screen bound to a
                // vanished id.
                if let id = editingInstanceId,
                   store.instances.contains(where: { $0.id == id }) {
                    ProviderInstanceDetailView(instanceId: id)
                }
            } label: { EmptyView() }
            .opacity(0)
        }
        // Same wording and same call (`store.removeInstance`) as the Delete
        // button inside ProviderInstanceDetailView — the swipe is a shortcut to
        // the existing flow, not a second deletion path with its own semantics.
        .alert(
            AppLocalized("Delete Provider"),
            isPresented: Binding(
                get: { pendingDeleteInstance != nil },
                set: { if !$0 { pendingDeleteInstance = nil } }
            ),
            presenting: pendingDeleteInstance
        ) { instance in
            Button("Delete", role: .destructive) {
                store.removeInstance(instance.id)
                pendingDeleteInstance = nil
            }
            Button("Cancel", role: .cancel) { pendingDeleteInstance = nil }
        } message: { _ in
            Text("This will remove the provider and all its model entries. API keys will be deleted from the Keychain.")
        }
        .alert(AppLocalized("Import"), isPresented: $showImportResult) {
            Button("OK") {}
        } message: {
            if let msg = importMessage { Text(msg) }
        }
        .overlay(alignment: .top) {
            if let msg = forceSyncToast {
                Text(msg)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.accentColor, in: Capsule())
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.3), value: forceSyncToast)
    }

    @available(iOS 17.0, *)
    private func forceSyncProviders() async {
        // [T-provider-forcesync-v3] markProvidersDirty now re-marks every v3
        // instance/entry/group/rule row (not just the v2 whole-file record that
        // v3 peers drop) and resets the pull-side full-history anchors.
        _ = await ForceSyncHelper.markProvidersDirty()
        await ForceSyncHelper.bidirectionalSync(recordTypes: [
            "ProviderConfig", "ProviderConfigV2",
            "ProviderInstanceV3", "ProviderModelEntryV3",
            "ProviderModelGroupV3", "ProviderThinkingRuleV3",
        ])
        forceSyncToast = AppLocalized("Syncing providers via iCloud")
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            forceSyncToast = nil
        }
    }

    // Section .onMove hands us section-relative indices. Map them back onto the
    // global store.instances order so the new order persists to disk and flows
    // through to SessionModelPicker.
    private func moveInstances(in type: ProviderType, from source: IndexSet, to destination: Int) {
        let all = store.instances
        // [T-ios-provider-reorder] The section's ForEach renders llmInstances
        // (voice-only providers filtered OUT into their own section), so the
        // section-relative indices from onMove address THAT list. The filter
        // here must match exactly — including voice-only instances of the same
        // providerType shifted every index and made drops land on the wrong
        // element (item jumps elsewhere / bounces back).
        // [T-mimo-shadow-voice] All instances of this type are section members
        // now (voice-only exclusion removed) — the section renders the full list.
        let isSectionMember: (ProviderInstance) -> Bool = {
            $0.providerType == type
        }
        let sectionIds = all.filter(isSectionMember).map(\.id)
        var reorderedSectionIds = sectionIds
        reorderedSectionIds.move(fromOffsets: source, toOffset: destination)

        // Rebuild global order: walk `all`, and whenever we hit a member of
        // this section, emit the next id from the reordered section list.
        var sectionIter = reorderedSectionIds.makeIterator()
        let newGlobalIds: [String] = all.map { inst in
            isSectionMember(inst) ? (sectionIter.next() ?? inst.id) : inst.id
        }
        store.reorderInstances(newGlobalIds)
    }
}

// MARK: - Instance Row

/// [T-ios-provider-row-keychain-in-body] Per-row credential display state, resolved
/// ONCE per (instance, authRevision) instead of on every SwiftUI body evaluation.
///
/// `isConfigured` and `credentialSummary` were each reading Keychain directly from
/// `body`. Every `SecItemCopyMatching` is a synchronous XPC round-trip to
/// `securityd`, so one body pass over N provider rows cost 2N blocking IPCs on the
/// MAIN THREAD — a real user log shows 110 `caller=isConfigured` +
/// 110 `caller=credentialSummary` reads in a single session (655 Keychain reads
/// overall). A crash report from that same session caught the main thread parked
/// inside `SecItemCopyMatching` under `InstanceRow.body`.
///
/// This is the same hazard `ProviderCredentialCache`
/// ([T-new-session-hang-credential-cache]) already exists for; the row simply
/// bypassed it. A separate cache is used rather than `hasAnyCredential` because
/// the row needs strictly more than a Bool — it renders the MASKED KEY, and its
/// OAuth notion of "authenticated" is per-provider-manager, not the router's
/// any-credential test. Reusing `hasAnyCredential` here would silently change
/// what the row displays.
///
/// Keying on `authRevision` (bumped by `notifyAuthChanged` on every credential
/// write/delete) makes invalidation exact: the UI still updates immediately after
/// the user adds or removes a key. The TTL is only a backstop for credential
/// changes that happen outside the app (Keychain iCloud sync).
final class ProviderRowCredentialCache: @unchecked Sendable {
    static let shared = ProviderRowCredentialCache()

    struct Display {
        let isConfigured: Bool
        let summary: String
    }

    /// Backstop only — `authRevision` is the primary invalidation signal.
    private static let ttl: TimeInterval = 15

    private let lock = NSLock()
    private var entries: [String: (value: Display, revision: UInt, at: Date)] = [:]

    private init() {}

    /// Drop everything. Called from the SAME places that clear
    /// `ProviderCredentialCache`, because those events (Keychain iCloud
    /// `view-change`, app foreground) change credentials WITHOUT bumping
    /// `authRevision` — so the revision key alone would not notice them.
    func invalidateAll() {
        lock.lock()
        entries.removeAll()
        lock.unlock()
    }

    func value(for instanceId: String, revision: UInt, probe: () -> Display) -> Display {
        let now = Date()
        lock.lock()
        if let e = entries[instanceId], e.revision == revision,
           now.timeIntervalSince(e.at) < Self.ttl {
            lock.unlock()
            return e.value
        }
        lock.unlock()

        // Probe runs OUTSIDE the lock — it does Keychain XPC and must not
        // serialize concurrent probes for different instances.
        let fresh = probe()

        lock.lock()
        entries[instanceId] = (fresh, revision, now)
        lock.unlock()
        return fresh
    }
}

private struct InstanceRow: View {
    let instance: ProviderInstance
    @ObservedObject private var store = ProviderConfigStore.shared

    /// Both display properties come from one cached probe, so a body pass costs
    /// zero Keychain round-trips once warm.
    private var credentialDisplay: ProviderRowCredentialCache.Display {
        ProviderRowCredentialCache.shared.value(for: instance.id, revision: store.authRevision) {
            let configured: Bool
            let summary: String
            switch instance.credentialType {
            case .apiKey:
                // Single Keychain read serves BOTH the dot and the masked subtitle;
                // previously these were two independent reads of the same item.
                let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id)
                // [T-empty-key-compat-endpoints] A keyless third-party
                // compatible endpoint is configured-by-definition; say so
                // instead of the alarming "No API key".
                if key == nil, instance.allowsEmptyAPIKey {
                    configured = true
                    summary = AppLocalized("No key required")
                } else {
                    configured = key != nil
                    summary = key.map(Self.maskKey) ?? AppLocalized("No API key")
                }
            case .oauth:
                let authed = Self.probeOAuthAuthenticated(instance)
                configured = authed
                summary = authed ? AppLocalized("Authenticated")
                                 : AppLocalized("Not authenticated")
            }
            return .init(isConfigured: configured, summary: summary)
        }
    }

    private var isConfigured: Bool { credentialDisplay.isConfigured }

    private var credentialSummary: String { credentialDisplay.summary }

    /// The uncached OAuth probe. `static` so the cache closure can call it without
    /// capturing `self` (the row is a short-lived struct; the cache outlives it).
    private static func probeOAuthAuthenticated(_ instance: ProviderInstance) -> Bool {
        // Manual OAuth token is always considered authenticated
        if ProviderKeychainHelper.loadOAuthString(instanceId: instance.id, account: "manual-oauth-token") != nil {
            return true
        }
        switch instance.providerType {
        case .anthropic: return ClaudeOAuthManager.shared.isAuthenticated(instanceId: instance.id)
        case .gemini: return GeminiOAuthManager.shared.isAuthenticated(instanceId: instance.id)
        case .openAI: return CodexOAuthManager.shared.isAuthenticated(instanceId: instance.id)
        case .antigravity: return AntigravityOAuthManager.shared.isAuthenticated(instanceId: instance.id)
        case .openRouter: return OpenRouterOAuthManager.shared.isAuthenticated(instanceId: instance.id)
        case .openAIResponses: return false // API key only
        case .xAI: return XAIOAuthManager.shared.isAuthenticated(instanceId: instance.id)
        case .kimiCode: return KimiOAuthManager.shared.isAuthenticated(instanceId: instance.id)
        case .unsupported: return false // synced from newer build
        }
    }

    private var modelCount: Int {
        store.visibleEntries(for: instance.id).count
    }

    var body: some View {
        let _ = store.authRevision  // subscribe to OAuth state changes
        HStack(spacing: 12) {
            Circle()
                .fill(isConfigured && instance.isEnabled ? Color.green : Color(UIColor.quaternaryLabel))
                .frame(width: 8, height: 8)

            VStack(alignment: .leading, spacing: 2) {
                Text(instance.label)
                    .font(.body.weight(.medium))
                HStack(spacing: 6) {
                    Text(instance.credentialType == .apiKey ? "API Key" : "OAuth")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("·")
                        .font(.caption)
                        .foregroundStyle(.quaternary)
                    Text(credentialSummary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if modelCount > 0 {
                    Text(AppLocalized("\(modelCount) models"))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            Spacer()

            if !instance.isEnabled {
                Text("Disabled")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color(UIColor.quaternarySystemFill))
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 2)
    }

    /// `static` (was an instance method) so the credential-cache probe closure can
    /// call it without capturing `self`. Pure function — no behaviour change.
    private static func maskKey(_ key: String) -> String {
        guard key.count > 8 else { return "****" }
        return String(key.prefix(6)) + "..." + String(key.suffix(4))
    }
}

// MARK: - Shadow Voice Row [T-mimo-shadow-voice]

/// A Voice Services row backed by another instance's audio-modality models.
private struct ShadowVoiceRow: View {
    let shadow: ProviderConfigStore.ShadowVoiceProvider

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "waveform")
                .foregroundStyle(.secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(shadow.displayName)
                    .font(.body)
                let asr = shadow.inputModels.count
                let tts = shadow.outputModels.count
                Text(voiceSummary(asr: asr, tts: tts))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            Spacer()
        }
        .padding(.vertical, 2)
    }

    private func voiceSummary(asr: Int, tts: Int) -> String {
        var parts: [String] = []
        if asr > 0 { parts.append(AppLocalized("\(asr) speech-to-text", comment: "ASR model count")) }
        if tts > 0 { parts.append(AppLocalized("\(tts) text-to-speech", comment: "TTS model count")) }
        return parts.joined(separator: " · ")
    }
}
