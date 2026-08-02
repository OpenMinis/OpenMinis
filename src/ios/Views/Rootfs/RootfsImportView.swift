//
//  RootfsImportView.swift
//  MinisApp
//
//  Rootfs profiles + tar.gz import UI surfaced from the terminal. Lets the
//  user view installed rootfs "profiles", switch the active one, delete
//  imported profiles, and import a new mini-rootfs (.tar.gz / .tgz / .tar)
//  via the Files document picker.
//

import SwiftUI
import UIKit

struct RootfsImportView: View {
    @StateObject private var model = RootfsImportViewModel()

    var body: some View {
        List {
            Section {
                Text("Rootfs (Linux environment)")
                    .font(.headline)
                Text("Import a mini-rootfs tarball (e.g. Alpine .tar.gz) to add a second Linux distribution, or switch between installed ones.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Section("Installed rootfs") {
                if model.profiles.isEmpty {
                    Text("No rootfs profiles found")
                        .foregroundColor(.secondary)
                } else {
                    ForEach(model.profiles, id: \.self) { name in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(name)
                                    .fontWeight(model.active == name ? .semibold : .regular)
                                if model.active == name {
                                    Text("Active — booted on next launch")
                                        .font(.caption2)
                                        .foregroundColor(.green)
                                }
                            }
                            Spacer()
                            if model.active == name {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                            } else {
                                Button("Switch") {
                                    model.switchProfile(to: name)
                                }
                                .font(.caption)
                                .buttonStyle(.bordered)
                            }
                            if name != "alpine-rootfs" && model.active != name {
                                Button(role: .destructive) {
                                    model.deleteProfile(name)
                                } label: {
                                    Image(systemName: "trash")
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                    }
                }
            }

            Section("Import") {
                Button {
                    model.presentPicker = true
                } label: {
                    Label {
                        Text(model.isImporting ? "Importing…" : "Import rootfs (tar.gz)")
                    } icon: {
                        Image(systemName: model.isImporting ? "arrow.triangle.2.circlepath" : "square.and.arrow.down")
                    }
                }
                .disabled(model.isImporting)

                if model.isImporting {
                    HStack {
                        ProgressView()
                            .controlSize(.small)
                        Text(model.importStatus)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            if let message = model.message {
                Section {
                    Text(message.text)
                        .font(.callout)
                        .foregroundColor(message.isError ? .red : .green)
                }
            }
        }
        .navigationTitle("Rootfs")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.refresh() }
        .sheet(isPresented: $model.presentPicker) {
            RootfsDocumentPicker { url in
                model.startImport(of: url)
            }
            .ignoresSafeArea()
        }
    }
}

/// Wraps UIDocumentPickerViewController for selecting a tar.gz rootfs archive.
struct RootfsDocumentPicker: UIViewControllerRepresentable {
    let picked: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item])
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let parent: RootfsDocumentPicker
        init(_ parent: RootfsDocumentPicker) { self.parent = parent }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            parent.picked(url)
        }
    }
}

@MainActor
class RootfsImportViewModel: ObservableObject {
    @Published var profiles: [String] = []
    @Published var active: String = "alpine-rootfs"
    @Published var isImporting = false
    @Published var importStatus = ""
    @Published var presentPicker = false
    @Published var message: (text: String, isError: Bool)?
    @Published var requestRelaunch = false

    func refresh() {
        let mgr = RootfsManager.shared
        profiles = mgr.installedProfiles
        active = mgr.activeProfile
        message = nil
    }

    func switchProfile(to name: String) {
        let mgr = RootfsManager.shared
        guard mgr.setActiveProfile(name) else { return }
        active = name
        requestRelaunch = mgr.didResetWhileBooted
        message = (text: "Switched active rootfs to '\(name)'. Restart the app to boot it.", isError: false)
    }

    func deleteProfile(_ name: String) {
        do {
            try RootfsManager.shared.deleteProfile(name)
            refresh()
            message = (text: "Deleted rootfs '\(name)'.", isError: false)
        } catch {
            message = (text: "Delete failed: \(error.localizedDescription)", isError: true)
        }
    }

    func startImport(of url: URL) {
        presentPicker = false
        isImporting = true
        importStatus = "Reading archive…"
        message = nil

        Task.detached(priority: .userInitiated) {
            // Security-scoped access is needed for Files-app URLs.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }

            // Stage file so the import has a stable local copy.
            do {
                let staged = try RootfsImportViewModel.stageFile(from: url)
                let base = url.deletingPathExtension().lastPathComponent
                let name = try RootfsManager.shared.importFromTarGz(sourceURL: staged,
                                                                    displayName: base,
                                                                    activate: false)
                try? FileManager.default.removeItem(at: staged)
                await MainActor.run {
                    self.isImporting = false
                    self.refresh()
                    self.message = (text: "✅ Imported rootfs '\(name)'. Switch to it and relaunch to boot.", isError: false)
                }
            } catch {
                await MainActor.run {
                    self.isImporting = false
                    self.message = (text: "❌ Import failed: \(error.localizedDescription)", isError: true)
                }
            }
        }
    }

    /// Copy the picked (possibly iCloud) file into our sandbox as a stable
    /// .tar file so the importer can read it after the security scope is gone.
    /// Marked nonisolated so it can run from a background (non-main) task.
    nonisolated private static func stageFile(from url: URL) throws -> URL {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("rootfs-import", isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let ext = url.pathExtension
        let dest = dir.appendingPathComponent("import-\(UUID().uuidString)" + (ext.isEmpty ? "" : ".\(ext)"))
        try? fm.removeItem(at: dest)
        try fm.copyItem(at: url, to: dest)
        return dest
    }
}

#Preview {
    NavigationStack { RootfsImportView() }
}
