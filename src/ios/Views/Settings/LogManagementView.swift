import SwiftUI
import UIKit

// MARK: - View Model

class LogManagementViewModel: ObservableObject {
    struct LogFile: Identifiable {
        let id: String
        let name: String
        let url: URL
        let date: Date
        let size: Int64
        let type: String  // "running" | "crash" | "other"
    }

    @Published var logFiles: [LogFile] = []
    @Published var totalSize: Int64 = 0

    /// Running + other logs (non-crash). Shown in the "Log Files" section.
    var runningFiles: [LogFile] { logFiles.filter { $0.type != "crash" } }
    /// Crash reports (crash-*.log). Shown in a separate section only if non-empty.
    var crashFiles: [LogFile] { logFiles.filter { $0.type == "crash" } }

    private let formatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.allowedUnits = [.useBytes, .useKB, .useMB, .useGB]
        f.countStyle = .file
        return f
    }()

    func format(_ bytes: Int64) -> String {
        formatter.string(fromByteCount: bytes)
    }

    func load() {
        let files = LoggingManager.shared.logFiles()
        logFiles = files.map {
            LogFile(id: $0.name, name: $0.name, url: $0.url, date: $0.date, size: $0.size, type: $0.type)
        }
        totalSize = LoggingManager.shared.totalLogSize()
    }
}

// MARK: - Log Management View

struct LogManagementView: View {
    @StateObject private var vm = LogManagementViewModel()
    @ObservedObject private var loggingManager = LoggingManager.shared
    @State private var showDeleteAllConfirm = false
    @State private var showShareSheet = false

    /// Selected top-level tab. Bound to a deep-link query string so
    /// `minis://settings/logs?tab=config-audit` lands users straight on
    /// the audit list. Stored as a String so the Picker can drive it.
    @State var initialTab: String = "logs"
    @State private var tab: String = "logs"

    var body: some View {
        VStack(spacing: 0) {
            Picker("Tab", selection: $tab) {
                Text("Logs").tag("logs")
                Text("Config Changes").tag("config-audit")
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)

            if tab == "config-audit" {
                ConfigAuditView()
            } else {
                logsBody
            }
        }
        .navigationTitle("Logs")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // "crashes" is no longer a tab — crash reports now live in a
            // section of the Logs body. Fall back to "logs" for stale deep links.
            tab = (initialTab == "crashes") ? "logs" : initialTab
            vm.load()
        }
    }

    @ViewBuilder
    private var logsBody: some View {
        List {
            Section {
                Toggle("Enable Logging", isOn: $loggingManager.isEnabled)
                if loggingManager.isEnabled {
                    // [T-ios-log-verbose-tier] Verbose turns on the
                    // high-frequency traces (per-syscall filesystem, per-render
                    // tool lifecycle, per-request context diagnostics). They are
                    // off by default because they are enormous: two measured
                    // days ran to 775 MB and 171 MB, of which 73% and 99% came
                    // from a handful of such traces.
                    Picker("Detail Level", selection: $loggingManager.level) {
                        // Two fixed cases, so the localized keys are literals
                        // (AppLocalized needs a literal, not a runtime String).
                        Text("Info").tag(AppLogger.Level.info)
                        Text("Verbose").tag(AppLogger.Level.verbose)
                    }
                }
            } footer: {
                if loggingManager.isEnabled && loggingManager.level == .verbose {
                    Text("Verbose adds high-frequency traces (filesystem, rendering, context). Useful when reproducing a specific problem — logs grow very quickly, so switch back to Info afterwards.")
                } else {
                    Text("When enabled, all console output is captured to daily log files.")
                }
            }

            Section("Log Files") {
                if vm.runningFiles.isEmpty {
                    Text("No log files")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(vm.runningFiles) { file in
                        NavigationLink {
                            LogDetailView(url: file.url, name: file.name)
                        } label: {
                            HStack {
                                Text(file.name)
                                    .lineLimit(1)
                                Spacer()
                                Text(vm.format(file.size))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { indexSet in
                        let files = vm.runningFiles
                        for index in indexSet {
                            LoggingManager.shared.deleteLog(at: files[index].url)
                        }
                        vm.load()
                    }
                }
            }

            if !vm.crashFiles.isEmpty {
                Section("Crash Reports") {
                    ForEach(vm.crashFiles) { file in
                        NavigationLink {
                            LogDetailView(url: file.url, name: file.name)
                        } label: {
                            HStack {
                                Text("⚠️ \(file.name)")
                                    .lineLimit(1)
                                Spacer()
                                Text(vm.format(file.size))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { indexSet in
                        let files = vm.crashFiles
                        for index in indexSet {
                            LoggingManager.shared.deleteLog(at: files[index].url)
                        }
                        vm.load()
                    }
                }
            }

            if #available(iOS 17.0, *) {
                Section {
                    NavigationLink {
                        SyncLogView()
                    } label: {
                        HStack {
                            Label("iCloud Sync Logs", systemImage: "icloud.fill")
                            Spacer()
                            Text("\(SyncLogStore.shared.entries.count)")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if !vm.logFiles.isEmpty {
                Section {
                    Button(role: .destructive) {
                        showDeleteAllConfirm = true
                    } label: {
                        HStack {
                            Label("Delete All Logs", systemImage: "trash")
                            Spacer()
                            Text(vm.format(vm.totalSize))
                                .foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text("Total log storage: \(vm.format(vm.totalSize))")
                }
            }
        }
        .toolbar {
            if !vm.logFiles.isEmpty && tab == "logs" {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showShareSheet = true
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
        }
        .sheet(isPresented: $showShareSheet) {
            LogShareSheet(urls: vm.logFiles.map(\.url))
        }
        .alert("Delete All Logs?", isPresented: $showDeleteAllConfirm) {
            Button("Delete All", role: .destructive) {
                LoggingManager.shared.deleteAllLogs()
                vm.load()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will delete \(vm.format(vm.totalSize)) of log files. This action cannot be undone.")
        }
    }
}

// MARK: - Log Detail View

struct LogDetailView: View {
    let url: URL
    let name: String
    @State private var content: String = ""
    @State private var isLoading = true

    var body: some View {
        Group {
            if isLoading {
                ProgressView()
            } else {
                LogTextView(text: content)
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: url)
            }
        }
        .task {
            let fileURL = url
            let loaded = await Task.detached(priority: .userInitiated) {
                LoggingManager.shared.readLog(at: fileURL)
            }.value
            content = loaded
            isLoading = false
        }
    }
}

// MARK: - UITextView wrapper (TextKit 2 lazy layout)

private struct LogTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView(usingTextLayoutManager: true)
        textView.isEditable = false
        textView.isSelectable = true
        textView.backgroundColor = .clear
        textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        textView.textColor = .label
        textView.textContainerInset = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        textView.dataDetectorTypes = []
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        if textView.text != text {
            textView.text = text
        }
    }
}

// MARK: - Share Sheet

private struct LogShareSheet: UIViewControllerRepresentable {
    let urls: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        // [T-share-sheet-uti] See MinisShareSheet.sanitizedShareURL.
        let safe = urls.map { MinisShareSheet.sanitizedShareURL($0) ?? $0 }
        return UIActivityViewController(activityItems: safe, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
