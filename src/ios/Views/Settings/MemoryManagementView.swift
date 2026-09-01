//
//  MemoryManagementView.swift
//  MinisApp
//
//  Settings-level memory management: unified file list with edit/delete.
//

import SwiftUI

struct MemoryManagementView: View {
    @State private var memoryFiles: [MemoryFileItem] = []
    @State private var forceSyncToast: String?
    @AppStorage("cloudSync.v2.enabled") private var iCloudSyncEnabled: Bool = false
    /// [T-memory-global-toggle-settings-ui-ios] Global default for whether
    /// a NEW session starts with memory enabled. Default true preserves
    /// today's behavior. Already-created sessions keep their own per-
    /// session memoryEnabled value (toggled via the /memory slash command)
    /// and are not affected when this global is changed.
    @AppStorage("memory.global.enabled") private var memoryGlobalEnabled: Bool = true

    var body: some View {
        List {
            Section {
                Toggle(AppLocalized("settings_memory_global_enabled"), isOn: $memoryGlobalEnabled)
            } footer: {
                Text(AppLocalized("settings_memory_global_enabled_footer"))
            }

            ForEach(memoryFiles) { file in
                NavigationLink {
                    MemoryFileEditView(fileName: file.name, isGlobal: file.isGlobal)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(file.name)
                                .font(.body)
                            if !file.fileSize.isEmpty {
                                Text(file.fileSize)
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                            Spacer()
                            Text(file.modifiedDate)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        if !file.preview.isEmpty {
                            Text(file.preview)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
            }
            .onDelete { offsets in
                deleteFiles(at: offsets)
            }
        }
        .navigationTitle("Memory")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if #available(iOS 17.0, *), iCloudSyncEnabled {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            Task { await forceSyncMemory() }
                        } label: {
                            Label(AppLocalized("Force iCloud Sync"),
                                  systemImage: "arrow.triangle.2.circlepath.icloud")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
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
        .onAppear { loadFiles() }
        // Refresh when an inbound iCloud merge / memory_write tool / another
        // view writes a memory file. Cheap operation (directory list +
        // per-file mtime/size attrs), safe to run on every notification.
        .onReceive(NotificationCenter.default.publisher(for: .memoryFilesDidChange)) { _ in
            loadFiles()
        }
    }

    @available(iOS 17.0, *)
    private func forceSyncMemory() async {
        let count = await ForceSyncHelper.markMemoryDirty()
        await ForceSyncHelper.bidirectionalSync(
            recordTypes: ["MemoryGlobalV2", "MemoryDailyV2"])
        forceSyncToast = AppLocalized("Synced \(count) memory files")
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            forceSyncToast = nil
        }
    }

    private func loadFiles() {
        let fm = FileManager.default
        let memDir = AIChatViewModel.minisMemoryPersistentDir
        try? fm.createDirectory(at: memDir, withIntermediateDirectories: true)

        // GLOBAL.md always first
        let globalURL = memDir.appendingPathComponent("GLOBAL.md")
        var items: [MemoryFileItem] = []

        let globalModDate = (try? fm.attributesOfItem(atPath: globalURL.path)[.modificationDate] as? Date) ?? Date()
        let globalContent = (try? String(contentsOf: globalURL, encoding: .utf8)) ?? ""
        let globalPreview = firstMemoryLine(from: globalContent)
        let globalSize = (try? fm.attributesOfItem(atPath: globalURL.path)[.size] as? Int) ?? 0
        items.append(MemoryFileItem(name: "GLOBAL.md", isGlobal: true, modifiedDate: formatDate(globalModDate), fileSize: formatFileSize(globalSize), preview: globalPreview))

        // Daily logs sorted by name descending
        if let files = try? fm.contentsOfDirectory(at: memDir, includingPropertiesForKeys: [.contentModificationDateKey], options: [.skipsHiddenFiles]) {
            let dailyFiles = files
                .filter { $0.pathExtension == "md" && $0.lastPathComponent != "GLOBAL.md" }
                .sorted { $0.lastPathComponent > $1.lastPathComponent }

            for url in dailyFiles {
                let name = url.lastPathComponent
                let modDate = (try? fm.attributesOfItem(atPath: url.path)[.modificationDate] as? Date) ?? Date()
                let size = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
                let content = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
                let preview = firstMemoryLine(from: content)
                items.append(MemoryFileItem(name: name, isGlobal: false, modifiedDate: formatDate(modDate), fileSize: formatFileSize(size), preview: preview))
            }
        }

        memoryFiles = items
    }

    private func deleteFiles(at offsets: IndexSet) {
        let fm = FileManager.default
        let memDir = AIChatViewModel.minisMemoryPersistentDir
        for idx in offsets {
            let file = memoryFiles[idx]
            if file.isGlobal { continue }
            let url = memDir.appendingPathComponent(file.name)
            try? fm.removeItem(at: url)
        }
        let deletable = IndexSet(offsets.filter { !memoryFiles[$0].isGlobal })
        memoryFiles.remove(atOffsets: deletable)
    }

    private func firstMemoryLine(from content: String) -> String {
        let line = content
            .components(separatedBy: "\n")
            .first { !$0.hasPrefix("<!--") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        return String((line ?? "").prefix(100))
    }

    private func formatFileSize(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        let kb = Double(bytes) / 1024.0
        if kb < 1024 { return String(format: "%.1f KB", kb) }
        let mb = kb / 1024.0
        return String(format: "%.1f MB", mb)
    }

    private func formatDate(_ date: Date) -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd HH:mm"
        return fmt.string(from: date)
    }
}

// MARK: - Data Model

private struct MemoryFileItem: Identifiable {
    let name: String
    let isGlobal: Bool
    let modifiedDate: String
    let fileSize: String
    let preview: String
    var id: String { name }
}

// MARK: - Memory File Edit View

private struct MemoryFileEditView: View {
    let fileName: String
    let isGlobal: Bool
    @State private var content: String = ""
    @State private var hasChanges = false
    @State private var saveError: String?
    /// One-shot suppression flag so `content = …` driven by external
    /// refreshes (onAppear / inbound sync) doesn't trip the
    /// `onChange { hasChanges = true }` and leave the Save button stuck.
    @State private var suppressNextChange = false

    private var fileURL: URL {
        AIChatViewModel.minisMemoryPersistentDir.appendingPathComponent(fileName)
    }

    var body: some View {
        VStack(spacing: 0) {
            if isGlobal {
                Text("Persistent instructions and preferences visible to the agent in every session. You can edit it here, or just ask the agent to help organize and update this memory.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
            }

            if let saveError {
                Text(saveError)
                    .foregroundStyle(.red)
                    .font(.caption)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }

            TextEditor(text: $content)
                .font(.system(.caption, design: .monospaced))
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onChange(of: content) { _ in
                    if suppressNextChange {
                        suppressNextChange = false
                    } else {
                        hasChanges = true
                    }
                }
        }
        // Daily-log path renders only the TextEditor inside the VStack —
        // without an explicit container max-height the TextEditor's
        // intrinsic content size collapses to 0 on iOS 26 and the file
        // body never appears (user screenshot 2026-05-23 17:02 — fully
        // blank pane under "2026-05-21.md" nav title). Anchor the VStack
        // to the available space so the TextEditor has somewhere to live.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle(fileName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // [T-global-memory-save-always-visible] Save is ALWAYS visible
            // once the editor is open. Previously it was gated on `hasChanges`,
            // which only flips via `onChange(of: content)`. On macOS a paste
            // (and some drag/IME inserts) doesn't reliably fire that onChange
            // until the next keystroke, so the button stayed hidden and the
            // user had to press Return to reveal it. Keeping it always present
            // sidesteps every input-source quirk. `save()` is a harmless
            // no-op rewrite when nothing changed.
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { save() }
            }
        }
        .onAppear {
            let fm = FileManager.default
            let exists = fm.fileExists(atPath: fileURL.path)
            let attrs = try? fm.attributesOfItem(atPath: fileURL.path)
            let sz = (attrs?[.size] as? Int) ?? -1
            let raw = (try? String(contentsOf: fileURL, encoding: .utf8)) ?? ""
            AppLogger(category: "MemoryEdit").info("[MemoryEdit] onAppear file=\(fileName) isGlobal=\(isGlobal) exists=\(exists) bytes=\(sz) contentChars=\(raw.count) path=\(fileURL.path)")
            suppressNextChange = true
            content = raw
            hasChanges = false
        }
        // Inbound sync / tool writes may update this file while the user is
        // viewing it. Re-read from disk so the editor doesn't stay stuck on
        // a stale snapshot. If the user has unsaved local edits we skip the
        // refresh to avoid clobbering their typing — they keep editing the
        // pre-merge text and can decide later whether to overwrite. The
        // post on .save() will fire this listener too, but hasChanges is
        // already false at that point so re-reading is a harmless no-op.
        .onReceive(NotificationCenter.default.publisher(for: .memoryFilesDidChange)) { _ in
            guard !hasChanges else { return }
            let fresh = (try? String(contentsOf: fileURL, encoding: .utf8)) ?? ""
            if fresh != content {
                suppressNextChange = true
                content = fresh
            }
        }
    }

    private func save() {
        let fm = FileManager.default
        try? fm.createDirectory(at: AIChatViewModel.minisMemoryPersistentDir, withIntermediateDirectories: true)
        do {
            try content.data(using: .utf8)?.write(to: fileURL)
            hasChanges = false
            saveError = nil
            // [T-toast-feedback] Confirm the save succeeded — the screen
            // doesn't dismiss or change, so without this the user gets no
            // signal that the write landed.
            MinisToast.show(AppLocalized("Saved"))
            // Enqueue for iCloud v2 sync. GLOBAL.md uses its own singleton
            // record; per-day logs use the dateKey from the filename.
            if isGlobal {
                Task {
                    await ChatStore.shared.markDirty(recordType: "MemoryGlobalV2",
                                                     recordId: "memory-global")
                }
            } else {
                let stem = (fileName as NSString).deletingPathExtension
                Task {
                    await ChatStore.shared.markDirty(recordType: "MemoryDailyV2",
                                                     recordId: stem)
                }
            }
            NotificationCenter.default.post(name: .memoryFilesDidChange, object: nil)
        } catch {
            saveError = error.localizedDescription
        }
    }
}

extension Notification.Name {
    /// Posted after a memory file (GLOBAL.md or daily log) is written
    /// locally — either by the user editing in MemoryManagementView, by
    /// memory_write tool, or by an inbound iCloud sync merge. Observers
    /// can use this to refresh their in-memory snapshot of memory files.
    static let memoryFilesDidChange = Notification.Name("com.openminis.app.memoryFilesDidChange")
}
