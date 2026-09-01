#if DEBUG
import SwiftUI

struct ICloudBackupView: View {
    @StateObject private var manager = ICloudBackupManager.shared
    @State private var showError: String?
    @State private var showRestoreConfirm: ICloudBackupManager.BackupEntry?

    var body: some View {
        List {
            // MARK: - Status & Backup Actions
            Section {
                if !manager.isICloudAvailable {
                    HStack(spacing: 12) {
                        Image(systemName: "exclamationmark.icloud")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.white)
                            .frame(width: 30, height: 30)
                            .background(Color.red)
                            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                        Text("iCloud is not available")
                            .foregroundColor(.primary)
                    }
                } else {
                    HStack(spacing: 12) {
                        Image(systemName: "checkmark.icloud.fill")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.white)
                            .frame(width: 30, height: 30)
                            .background(Color.green)
                            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                        Text("iCloud connected")
                            .foregroundColor(.green)
                    }
                }
            }

            if manager.isICloudAvailable {
                Section("Backup") {
                    ForEach(ICloudBackupManager.BackupCategory.allCases) { category in
                        Button {
                            performBackup(category: category)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: category.systemImage)
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundColor(.white)
                                    .frame(width: 30, height: 30)
                                    .background(category.iconColor)
                                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(category.displayName)
                                        .foregroundColor(.primary)
                                    Text(category.description)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                if manager.isBackingUp {
                                    ProgressView()
                                        .scaleEffect(0.8)
                                }
                            }
                        }
                        .disabled(manager.isBackingUp || manager.isRestoring)
                    }

                    if manager.isBackingUp {
                        VStack(alignment: .leading, spacing: 6) {
                            ProgressView(value: manager.progress)
                            Text(manager.statusMessage)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            if manager.isRestoring {
                Section("Restoring") {
                    VStack(alignment: .leading, spacing: 6) {
                        ProgressView(value: manager.progress)
                        Text(manager.statusMessage)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }

            // MARK: - Available Backups
            let grouped = Dictionary(grouping: manager.availableBackups, by: \.deviceName)
            let sortedDevices = grouped.keys.sorted()

            ForEach(sortedDevices, id: \.self) { device in
                Section(device) {
                    ForEach(grouped[device] ?? []) { entry in
                        backupRow(entry)
                    }
                }
            }

            if manager.availableBackups.isEmpty && manager.isICloudAvailable {
                Section {
                    Text("No backups found")
                        .foregroundColor(.secondary)
                }
            }

            Section {
                // empty section for footer
            } footer: {
                Text("Backups are stored in iCloud Drive and sync across your devices. Restoring will replace your current local data.")
            }
        }
        .navigationTitle("iCloud Backup")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await manager.listBackups()
        }
        .refreshable {
            await manager.listBackups()
        }
        .alert("Restore Backup", isPresented: .init(
            get: { showRestoreConfirm != nil },
            set: { if !$0 { showRestoreConfirm = nil } }
        )) {
            Button("Cancel", role: .cancel) { showRestoreConfirm = nil }
            Button("Restore", role: .destructive) {
                if let entry = showRestoreConfirm {
                    performRestore(entry: entry)
                }
            }
        } message: {
            if let entry = showRestoreConfirm {
                Text("This will replace your current \(entry.category.displayName.lowercased()) data with the backup from \(entry.date.formatted(date: .abbreviated, time: .shortened)). This cannot be undone.")
            }
        }
        .alert("Error", isPresented: .init(
            get: { showError != nil },
            set: { if !$0 { showError = nil } }
        )) {
            Button("OK") { showError = nil }
        } message: {
            if let error = showError {
                Text(error)
            }
        }
    }

    // MARK: - Backup Row

    private func backupRow(_ entry: ICloudBackupManager.BackupEntry) -> some View {
        HStack {
            Image(systemName: entry.category.systemImage)
                .font(.system(size: 15, weight: .medium))
                .foregroundColor(.white)
                .frame(width: 30, height: 30)
                .background(entry.category.iconColor)
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.category.displayName)
                    .font(.body)
                HStack(spacing: 8) {
                    Text(entry.date.formatted(date: .abbreviated, time: .shortened))
                    Text(entry.fileSize.formattedFileSize)
                }
                .font(.caption)
                .foregroundColor(.secondary)
            }
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                deleteBackup(entry)
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
        .swipeActions(edge: .leading) {
            Button {
                showRestoreConfirm = entry
            } label: {
                Label("Restore", systemImage: "arrow.counterclockwise")
            }
            .tint(.blue)
        }
        .contextMenu {
            Button {
                showRestoreConfirm = entry
            } label: {
                Label("Restore", systemImage: "arrow.counterclockwise")
            }
            .disabled(manager.isRestoring || manager.isBackingUp)

            Button(role: .destructive) {
                deleteBackup(entry)
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    // MARK: - Actions

    private func performBackup(category: ICloudBackupManager.BackupCategory) {
        Task {
            do {
                try await manager.backup(category: category)
            } catch {
                showError = error.localizedDescription
            }
        }
    }

    private func performRestore(entry: ICloudBackupManager.BackupEntry) {
        Task {
            do {
                try await manager.restore(from: entry)
            } catch {
                showError = error.localizedDescription
            }
        }
    }

    private func deleteBackup(_ entry: ICloudBackupManager.BackupEntry) {
        do {
            try manager.deleteBackup(entry)
        } catch {
            showError = error.localizedDescription
        }
    }
}
#endif
