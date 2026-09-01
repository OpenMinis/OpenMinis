import SwiftUI

@available(iOS 17.0, *)
struct CloudSyncSettingsView: View {
    @ObservedObject private var engine = CloudSyncEngine.shared
    @State private var devices: [SyncDevice] = []
    @State private var localSessionCount = 0
    @State private var showSyncConfirm = false
    @State private var showDeleteCloudStep1 = false
    @State private var showDeleteCloudStep2 = false
    @State private var isDeletingCloud = false
    @State private var deleteCloudError: String?
    @State private var showDeleteCloudError = false
    @State private var showDeleteCloudDone = false
    @State private var sessionsSize: Int64 = 0
    @State private var skillsSize: Int64 = 0
    @State private var providersSize: Int64 = 0
    @State private var envVarsSize: Int64 = 0

    var body: some View {
        List {
            syncToggleSection
            if engine.isEnabled {
                uploadSection
                syncedDevicesSection
            }
            advancedSection
            dangerZoneSection
        }
        .navigationTitle("iCloud Sync")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .cloudSyncDidFetchChanges)) { _ in
            refresh()
        }
        .alert("Delete iCloud Data?", isPresented: $showDeleteCloudStep1) {
            Button("Cancel", role: .cancel) {}
            Button("Continue") {
                // Small delay so the first alert fully dismisses before the second
                // appears — otherwise SwiftUI sometimes swallows the second alert.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                    showDeleteCloudStep2 = true
                }
            }
        } message: {
            Text("This will permanently delete everything Minis has uploaded to your iCloud account, across ALL of your devices:\n\n• Chat sessions & messages\n• Session files & attachments\n• Skills\n• Provider configurations\n• Environment variables\n\nThis device's local data will NOT be deleted — only the cloud copy. After the wipe, this device will re-upload its local content to a fresh iCloud zone.")
        }
        .alert("Are you absolutely sure?", isPresented: $showDeleteCloudStep2) {
            Button("Cancel", role: .cancel) {}
            Button("Delete iCloud Data", role: .destructive) {
                performDeleteCloudData()
            }
        } message: {
            Text("This action cannot be undone. All Minis data stored in iCloud will be erased immediately. Other devices signed into the same iCloud account will lose their synced copies on the next sync.\n\nLocal data on this device is safe.")
        }
        .alert("iCloud Data Deleted", isPresented: $showDeleteCloudDone) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("All Minis data has been removed from iCloud. This device is now re-uploading its local content to a fresh iCloud zone.")
        }
        .alert("Delete Failed", isPresented: $showDeleteCloudError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(deleteCloudError ?? "An unknown error occurred while deleting iCloud data.")
        }
    }

    // MARK: - Sections

    private var syncToggleSection: some View {
        Section {
            Toggle("Enable iCloud Sync", isOn: $engine.isEnabled)

            HStack {
                Text("Status")
                Spacer()
                statusBadge
            }

            if let date = engine.lastSyncDate {
                HStack {
                    Text("Last synced")
                    Spacer()
                    Text(date, format: .dateTime.month(.abbreviated).day().hour().minute())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch engine.syncStatus {
        case .idle:
            HStack(spacing: 4) {
                Image(systemName: "checkmark.circle.fill")
                Text("Up to date")
            }
            .foregroundStyle(.green)
            .font(.caption)
        case .syncing:
            HStack(spacing: 4) {
                ProgressView()
                    .controlSize(.mini)
                Text("Syncing...")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        case .error(let msg):
            HStack(spacing: 4) {
                Image(systemName: "exclamationmark.triangle.fill")
                Text(msg)
                    .lineLimit(1)
            }
            .foregroundStyle(.red)
            .font(.caption)
        case .disabled:
            HStack(spacing: 4) {
                Image(systemName: "minus.circle.fill")
                Text("Disabled")
            }
            .foregroundStyle(.secondary)
            .font(.caption)
        }
    }

    // MARK: - Upload (This Device)

    private var uploadSection: some View {
        Section {
            HStack {
                Text("Name")
                Spacer()
                Text(DeviceIdentity.deviceName)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Text("Local Sessions")
                Spacer()
                Text("\(localSessionCount)")
                    .foregroundStyle(.secondary)
            }

            Toggle(isOn: $engine.syncSessions) {
                HStack {
                    settingsIcon("bubble.left.and.bubble.right", color: .blue)
                    Text("Chat Sessions")
                    Spacer()
                    Text(formatSize(sessionsSize))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Picker(selection: Binding(
                get: { engine.maxSyncFileSize },
                set: { engine.maxSyncFileSize = $0 }
            )) {
                Text("Not Sync").tag(0)
                Text("512 KB").tag(512 * 1024)
                Text("1 MB").tag(1_048_576)
                Text("5 MB").tag(5 * 1_048_576)
                Text("10 MB").tag(10 * 1_048_576)
                Text("50 MB").tag(50 * 1_048_576)
                Text("Unlimited").tag(Int.max)
            } label: {
                HStack {
                    settingsIcon("arrow.up.arrow.down.circle", color: .gray)
                    Text("Max Per-File Size")
                }
            }
            Toggle(isOn: $engine.syncSkills) {
                HStack {
                    settingsIcon("puzzlepiece.extension", color: .orange)
                    Text("Skills")
                    Spacer()
                    Text(formatSize(skillsSize))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Toggle(isOn: $engine.syncProviders) {
                HStack {
                    settingsIcon("server.rack", color: .teal)
                    Text("Providers")
                    Spacer()
                    Text(formatSize(providersSize))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Toggle(isOn: $engine.syncEnvironments) {
                HStack {
                    settingsIcon("terminal", color: .mint)
                    Text("Environments")
                    Spacer()
                    Text(formatSize(envVarsSize))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Upload (This Device)")
        } footer: {
            Text("Choose which data this device pushes to iCloud. API keys and secrets are synced securely via iCloud Keychain.")
        }
    }

    // MARK: - Synced Devices (Download)

    @ViewBuilder
    private var syncedDevicesSection: some View {
        if devices.isEmpty {
            Section {
                Text("No other devices found yet. Devices appear here after they enable iCloud Sync.")
                    .foregroundStyle(.secondary)
                    .font(.callout)
            } header: {
                Text("Synced Devices (Download)")
            }
        } else {
            ForEach(devices) { device in
                DeviceSyncSection(device: device, engine: engine)
            }
        }
    }

    // MARK: - Advanced

    private var advancedSection: some View {
        Section("Advanced") {
            Button {
                showSyncConfirm = true
            } label: {
                HStack {
                    settingsIcon("arrow.triangle.2.circlepath.icloud", color: .blue)
                    Text("Force Full Sync")
                        .foregroundStyle(Color.primary)
                    if case .syncing = engine.syncStatus {
                        Spacer()
                        ProgressView()
                            .controlSize(.small)
                    }
                }
            }
            .disabled(!engine.isEnabled || engine.syncStatus == .syncing)
            .confirmationDialog("Force Full Sync?", isPresented: $showSyncConfirm) {
                Button("Sync Now") {
                    Task { await engine.forceFullSync() }
                }
            } message: {
                Text("This will re-download all sync data from iCloud, which may take 10–30 seconds and consume significant network traffic. Wi-Fi is recommended.\n\nYour local sessions, skills, and files are not affected.")
            }
        }
    }

    // MARK: - Danger Zone

    private var dangerZoneSection: some View {
        Section {
            Button {
                showDeleteCloudStep1 = true
            } label: {
                HStack {
                    settingsIcon("trash", color: .red)
                    Text("Delete iCloud Data")
                        .foregroundStyle(Color.primary)
                    if isDeletingCloud {
                        Spacer()
                        ProgressView()
                            .controlSize(.small)
                    }
                }
            }
            .disabled(isDeletingCloud)
        } header: {
            Text("Danger Zone")
        } footer: {
            Text("Permanently erase all Minis data from iCloud. Local data on this device is not affected.")
        }
    }

    private func performDeleteCloudData() {
        isDeletingCloud = true
        Task {
            do {
                try await engine.deleteAllCloudData()
                await MainActor.run {
                    isDeletingCloud = false
                    showDeleteCloudDone = true
                    refresh()
                }
            } catch {
                await MainActor.run {
                    isDeletingCloud = false
                    deleteCloudError = error.localizedDescription
                    showDeleteCloudError = true
                }
            }
        }
    }

    // MARK: - Helpers

    private func settingsIcon(_ systemName: String, color: Color) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 9))
            .foregroundStyle(.white)
            .frame(width: 21, height: 21)
            .background(color, in: Circle())
    }

    private func formatSize(_ bytes: Int64) -> String {
        if bytes >= 1_073_741_824 {
            return String(format: "%.1f GB", Double(bytes) / 1_073_741_824)
        } else if bytes >= 1_048_576 {
            return String(format: "%.1f MB", Double(bytes) / 1_048_576)
        } else if bytes >= 1024 {
            return String(format: "%.1f KB", Double(bytes) / 1024)
        } else {
            return "\(bytes) B"
        }
    }

    private func refresh() {
        Task {
            devices = await ChatStore.shared.listSyncDevices()
                .filter { $0.id != DeviceIdentity.deviceId }
            // [T-ios-session-coldload-listsessions-block] COUNT(*) not
            // listSessions().count — the latter is a full 4-subquery-per-
            // session scan just to get a number.
            localSessionCount = await ChatStore.shared.sessionCount()
            sessionsSize = await ChatStore.shared.estimateSessionsSize()
            skillsSize = await ChatStore.shared.estimateSkillsSize()
            providersSize = await ChatStore.shared.estimateProvidersSize()
            envVarsSize = await ChatStore.shared.estimateEnvVarsSize()
        }
    }
}

// MARK: - Per-Device Sync Section

@available(iOS 17.0, *)
private struct DeviceSyncSection: View {
    let device: SyncDevice
    @ObservedObject var engine: CloudSyncEngine

    private var isEnabled: Bool {
        engine.isDeviceSyncEnabled(device.id)
    }

    private struct ContentTypeInfo {
        let type: String
        let label: String
        let icon: String
        let color: Color
    }

    private static let allContentTypes: [ContentTypeInfo] = [
        ContentTypeInfo(type: "Sessions", label: "Sessions & Files", icon: "bubble.left.and.bubble.right", color: .blue),
        ContentTypeInfo(type: "Skills", label: "Skills", icon: "puzzlepiece.extension", color: .orange),
        ContentTypeInfo(type: "Providers", label: "Providers", icon: "server.rack", color: .teal),
        ContentTypeInfo(type: "Environments", label: "Environments", icon: "terminal", color: .mint),
    ]

    private var availableTypes: [ContentTypeInfo] {
        let uploaded = Set(device.uploadTypes)
        if uploaded.isEmpty {
            return Self.allContentTypes
        }
        return Self.allContentTypes.filter { uploaded.contains($0.type) }
    }

    private static func relativeTime(_ date: Date) -> String {
        let seconds = Int(Date().timeIntervalSince(date))
        if seconds < 60 { return AppLocalized("just now") }
        let minutes = seconds / 60
        if minutes < 60 { return AppLocalized("\(minutes) min ago") }
        let hours = minutes / 60
        if hours < 24 { return AppLocalized("\(hours) hr ago") }
        let days = hours / 24
        if days == 1 { return AppLocalized("\(days) day ago") }
        return AppLocalized("\(days) days ago")
    }

    private var deviceIcon: String {
        let name = device.deviceName.lowercased()
        if name.contains("ipad") { return "ipad" }
        if name.contains("mac") { return "laptopcomputer" }
        return "iphone"
    }

    private func settingsIcon(_ systemName: String, color: Color) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 9))
            .foregroundStyle(.white)
            .frame(width: 21, height: 21)
            .background(color, in: Circle())
    }

    var body: some View {
        Section {
            // Enable toggle with device info
            Toggle(isOn: Binding(
                get: { engine.isDeviceSyncEnabled(device.id) },
                set: { engine.setDeviceSyncEnabled(device.id, enabled: $0) }
            )) {
                HStack {
                    Image(systemName: deviceIcon)
                        .foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(device.deviceName)
                        HStack(spacing: 8) {
                            Text(device.osVersion)
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                            Text("Last seen: \(Self.relativeTime(device.lastSeen))")
                                .font(.caption2).foregroundStyle(.tertiary)
                        }
                    }
                }
            }

            // Content type toggles — same style as Upload section
            if isEnabled {
                ForEach(Array(availableTypes.enumerated()), id: \.offset) { _, item in
                    Toggle(isOn: Binding(
                        get: { engine.isDeviceContentEnabled(device.id, type: item.type) },
                        set: { engine.setDeviceContentEnabled(device.id, type: item.type, enabled: $0) }
                    )) {
                        HStack {
                            settingsIcon(item.icon, color: item.color)
                            Text(item.label)
                        }
                    }
                }
            }
        } header: {
            Text("Download (\(device.deviceName))")
        }
    }
}

// MARK: - Remote Device Sessions (Browse & Fork)

@available(iOS 17.0, *)
struct RemoteDeviceSessionsView: View {
    let deviceId: String
    let deviceName: String
    @State private var sessions: [ChatSession] = []
    @State private var forkingId: String?
    @State private var forkedSession: ChatSession?
    @State private var showForkedAlert = false

    var body: some View {
        List {
            if sessions.isEmpty {
                Text("No sessions synced from this device yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(sessions) { session in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.title ?? "Untitled")
                                .lineLimit(1)
                            HStack(spacing: 8) {
                                Text(session.modelId)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                Text(session.updatedAt, style: .relative)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                            if let preview = session.lastMessage {
                                Text(preview)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                        }

                        Spacer()

                        if forkingId == session.id {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Button {
                                forkSession(session)
                            } label: {
                                Label("Fork", systemImage: "arrow.branch")
                                    .font(.caption)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                    .contextMenu {
                        Button {
                            forkSession(session)
                        } label: {
                            Label("Fork Session", systemImage: "arrow.branch")
                        }
                    }
                }
            }
        }
        .navigationTitle(deviceName)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            Task { sessions = await ChatStore.shared.listRemoteSessions(deviceId: deviceId) }
        }
        .alert("Session Forked", isPresented: $showForkedAlert) {
            Button("OK") {}
        } message: {
            if let s = forkedSession {
                Text("\"\(s.title ?? AppLocalized("Untitled"))\" has been copied to your sessions. You can find it in the main session list.")
            }
        }
    }

    private func forkSession(_ session: ChatSession) {
        forkingId = session.id
        Task { @MainActor in
            if let forked = await SessionForkManager.shared.forkSession(
                remoteSessionId: session.id, remoteDeviceId: deviceId
            ) {
                forkedSession = forked
                showForkedAlert = true
            }
            forkingId = nil
        }
    }
}

// MARK: - Remote Skills List

@available(iOS 17.0, *)
struct RemoteSkillsListView: View {
    let deviceId: String
    let deviceName: String
    @State private var skills: [Skill] = []
    @State private var copiedIds: Set<String> = []

    var body: some View {
        List {
            if skills.isEmpty {
                Text("No skills synced from this device.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(skills) { skill in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(skill.name)
                                .font(.headline)
                            if !skill.description.isEmpty {
                                Text(skill.description)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                            Text("v\(skill.version)")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }

                        Spacer()

                        if copiedIds.contains(skill.id) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                        } else {
                            Button {
                                Task {
                                    let ok = await SessionForkManager.shared.copyRemoteSkill(skillId: skill.id, deviceId: deviceId)
                                    if ok { copiedIds.insert(skill.id) }
                                }
                            } label: {
                                Text("Copy to My Device")
                                    .font(.caption)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                }
            }
        }
        .navigationTitle("Skills from \(deviceName)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            Task { skills = await ChatStore.shared.listRemoteSkills(deviceId: deviceId) }
        }
    }
}

// MARK: - Remote Memories List

@available(iOS 17.0, *)
struct RemoteMemoriesListView: View {
    let deviceId: String
    let deviceName: String
    @State private var memories: [RemoteMemory] = []
    @State private var copiedIds: Set<String> = []

    var body: some View {
        List {
            if memories.isEmpty {
                Text("No memories synced from this device.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(memories) { memory in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(memory.fileName)
                                .font(.headline)
                            Text(memory.content.prefix(100))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                            Text(memory.updatedAt, style: .relative)
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }

                        Spacer()

                        if copiedIds.contains(memory.id) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                        } else {
                            Button {
                                Task {
                                    let ok = await SessionForkManager.shared.copyRemoteMemory(memoryId: memory.id, deviceId: deviceId)
                                    if ok { copiedIds.insert(memory.id) }
                                }
                            } label: {
                                Text("Copy to My Device")
                                    .font(.caption)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                }
            }
        }
        .navigationTitle("Memories from \(deviceName)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            Task { memories = await ChatStore.shared.listRemoteMemories(deviceId: deviceId) }
        }
    }
}

// MARK: - Sync Log Viewer

@available(iOS 17.0, *)
struct SyncLogView: View {
    @ObservedObject private var store = SyncLogStore.shared
    @State private var shareItem: ShareFileItem?

    var body: some View {
        List {
            Section {
                Text("Sync events are recorded as iCloud sync sends and receives data. Up to 1,000 entries are kept.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if store.entries.isEmpty {
                Text("No sync events yet.")
                    .foregroundStyle(.secondary)
                    .font(.callout)
            } else {
                ForEach(store.entries) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            directionBadge(entry.direction)
                            Text(entry.recordType)
                                .font(.system(.subheadline, weight: .semibold))
                            Spacer()
                            Text(entry.timestamp, format: .dateTime.hour().minute().second())
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        if !entry.summary.isEmpty {
                            Text(entry.summary)
                                .font(.caption)
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                        }
                        Text(formatRecordName(entry.recordName))
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                        if let deviceName = entry.deviceName {
                            HStack(spacing: 4) {
                                Image(systemName: "iphone")
                                    .font(.system(size: 9))
                                Text(deviceName)
                            }
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                        }
                        if entry.direction == .error {
                            Text(entry.detail)
                                .font(.caption2)
                                .foregroundStyle(.red)
                                .lineLimit(2)
                        } else if entry.direction == .conflict {
                            Text(entry.detail)
                                .font(.caption2)
                                .foregroundStyle(.orange)
                                .lineLimit(2)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .navigationTitle("iCloud Sync Logs")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    let report = store.exportSanitizedReport()
                    let tempURL = FileManager.default.temporaryDirectory
                        .appendingPathComponent("minis-sync-diagnostic.txt")
                    try? report.write(to: tempURL, atomically: true, encoding: .utf8)
                    shareItem = ShareFileItem(url: tempURL)
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(store.entries.isEmpty)

                Button("Clear") {
                    store.clear()
                }
                .disabled(store.entries.isEmpty)
            }
        }
        .sheet(item: $shareItem) { item in
            SyncLogShareSheet(url: item.url)
        }
    }

    private func directionBadge(_ direction: SyncLogEntry.Direction) -> some View {
        Group {
            switch direction {
            case .sent:
                Image(systemName: "arrow.up.circle.fill")
                    .foregroundStyle(.blue)
            case .received:
                Image(systemName: "arrow.down.circle.fill")
                    .foregroundStyle(.green)
            case .conflict:
                Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                    .foregroundStyle(.yellow)
            case .error:
                Image(systemName: "exclamationmark.circle.fill")
                    .foregroundStyle(.red)
            }
        }
        .font(.system(size: 16))
    }

    private func formatRecordName(_ name: String) -> String {
        // "Session:ABC-DEF-123" → "ABC-DEF-123"
        if let idx = name.firstIndex(of: ":") {
            return String(name[name.index(after: idx)...])
        }
        return name
    }
}

// MARK: - Sync Log Share Sheet

private struct ShareFileItem: Identifiable {
    let id = UUID()
    let url: URL
}

@available(iOS 17.0, *)
private struct SyncLogShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        // [T-share-sheet-uti] See MinisShareSheet.sanitizedShareURL.
        let safeURL = MinisShareSheet.sanitizedShareURL(url) ?? url
        return UIActivityViewController(activityItems: [safeURL], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
