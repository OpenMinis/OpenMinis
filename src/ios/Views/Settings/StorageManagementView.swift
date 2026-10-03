import SwiftUI

// MARK: - View Model

class StorageManagementViewModel: ObservableObject {
    struct SessionStorage: Identifiable {
        let id: String
        let title: String?
        var minisSize: Int64 = 0
        /// [T-child-delete-storage] Hidden agent child sessions folded into
        /// this row (their ids, for cascading "clear files").
        var descendantIds: [String] = []
        var totalSize: Int64 { minisSize }
    }

    @Published var shellContainerSize: Int64 = 0
    @Published var chatDatabaseSize: Int64 = 0
    @Published var logsAndCachesSize: Int64 = 0
    @Published var temporarySize: Int64 = 0
    @Published var orphanedSessionSize: Int64 = 0
    @Published var staleRootfsSize: Int64 = 0
    @Published var otherSize: Int64 = 0
    @Published var containerTotalSize: Int64 = 0
    @Published var sessions: [SessionStorage] = []
    @Published var isLoading = true

    private let fm = FileManager.default
    private let formatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.allowedUnits = [.useBytes, .useKB, .useMB, .useGB]
        f.countStyle = .file
        return f
    }()

    func format(_ bytes: Int64) -> String {
        formatter.string(fromByteCount: bytes)
    }

    var totalSessionSize: Int64 {
        sessions.reduce(0) { $0 + $1.totalSize }
    }

    // MARK: - Scanning
    //
    // [T-ios-storage-page-undercount] This page used to sum three whitelisted
    // paths — Documents/alpine-rootfs, Library/MinisChat/minis.db, and the
    // per-session dirs under Library/MinisChat/minis — and present the result as
    // if it were the app's footprint. Measured on a real device (iPhone 11,
    // 2026-08-29, cross-checked against
    // `xcrun devicectl device info files --domain-type appDataContainer`), those
    // three paths covered 573.70 MB of a 747.88 MB container: 23.3% of the user's
    // storage was invisible in-app, and the only way to see the real number was
    // iOS Settings. A user reported a ~11.7 GB discrepancy from exactly this.
    //
    // Four separate causes, all fixed here:
    //
    //  1. WHITELIST. Library/Logs, tmp/, Library/Caches, WebKit, HTTPStorages and
    //     a stranded `.alpine-rootfs-broken-*` tree were in no bucket at all. We
    //     now walk the WHOLE container (Documents + Library + tmp) and classify
    //     every file into exactly one bucket, so the buckets sum to the true
    //     total by construction rather than by hope.
    //  2. `.skipsHiddenFiles`. FileManager's enumerator skips an entire hidden
    //     SUBTREE, so a `.git` inside the shell container vanished — 18.95 MB on
    //     the test device, and a shell used for real work is full of
    //     `.git`/`.cache`/`.npm`/`.venv`. This is why "Shell Container" always
    //     read low. The option is gone; hidden files are storage too.
    //  3. LOGICAL vs ALLOCATED size. `fileSizeKey` is the logical length; iOS
    //     Settings reports blocks actually occupied. With 2,701 of 4,923 files
    //     under one 4 KB block the difference measured +1.6% — small, but it is a
    //     systematic undercount and it is free to fix by asking for
    //     `totalFileAllocatedSizeKey`.
    //  4. ORPHANS. Session dirs whose row is gone from the DB were skipped by an
    //     `if sessionIds.contains(sid)`, so they could never be seen OR cleared
    //     from this screen — an unbounded leak. They now get their own bucket.
    //
    // The residual bucket (`otherSize`) is what makes the guarantee hold: it is
    // computed as total-minus-classified, never summed independently, so anything
    // we failed to anticipate still shows up in the total instead of silently
    // disappearing. If a future release adds a new directory, this page stays
    // honest without being taught about it.

    /// One file's contribution, in the same units iOS Settings uses.
    ///
    /// Falls back logical-size → 0 rather than skipping the file: an
    /// unreadable attribute should cost us precision, never a whole entry.
    private func allocatedSize(_ values: URLResourceValues?) -> Int64 {
        if let a = values?.totalFileAllocatedSize { return Int64(a) }
        if let a = values?.fileAllocatedSize { return Int64(a) }
        if let s = values?.fileSize { return Int64(s) }
        return 0
    }

    private static let sizeKeys: [URLResourceKey] = [
        .totalFileAllocatedSizeKey, .fileAllocatedSizeKey, .fileSizeKey,
        .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey,
    ]

    /// Every non-directory entry under `root`, as (path relative to `root`,
    /// allocated bytes).
    ///
    /// Returns the listing instead of taking a callback so the caller's
    /// accumulators stay plain local `var`s: a closure that mutates captured vars
    /// is an error under the Swift 6 language mode, and threading `inout` state
    /// through a callback to dodge that would be harder to read than just
    /// iterating the result.
    ///
    /// Symlinks are counted at their own (tiny) size and never followed —
    /// following them would double-count `/var/minis/*`, which are symlinks into
    /// the per-session directories this same walk already visits.
    private func entries(under root: URL) -> [(rel: String, size: Int64)] {
        // Recurse explicitly, carrying the relative path DOWN as we descend,
        // instead of enumerating and deriving it from absolute paths afterwards.
        //
        // Deriving it is where this went wrong on device: `FileManager.urls(for:)`
        // hands back `/var/mobile/…`, while `FileManager.enumerator` yields the
        // resolved `/private/var/mobile/…`. Stripping the root as a string prefix
        // therefore matched nothing and every `rel` stayed absolute — so no
        // `hasPrefix("MinisChat/…")` test fired, every category rendered
        // "0 bytes", and the entire 736 MB container landed in the residual
        // bucket. Counting path COMPONENTS instead has the same defect (the
        // resolved form is one component longer), and anchoring on the root's
        // directory NAME breaks whenever a nested directory repeats it.
        //
        // Building each child from its parent sidesteps the whole class of bug:
        // the relative path is constructed, never parsed, so no prefix form can
        // affect it. Verified against a fixture containing a nested directory
        // named identically to the root.
        //
        // Symlinks are counted at their own (tiny) size and never followed —
        // following them would double-count `/var/minis/*`, which point into the
        // per-session directories this walk already visits.
        var out: [(rel: String, size: Int64)] = []
        var stack: [(dir: URL, prefix: String)] = [(root, "")]
        while let (dir, prefix) = stack.popLast() {
            let kids = (try? fm.contentsOfDirectory(
                at: dir,
                includingPropertiesForKeys: Self.sizeKeys,
                options: []      // NOT .skipsHiddenFiles — see (2) above
            )) ?? []
            for kid in kids {
                let name = kid.lastPathComponent
                let rel = prefix.isEmpty ? name : prefix + "/" + name
                let v = try? kid.resourceValues(forKeys: Set(Self.sizeKeys))
                // Descend into real directories only. `isDirectory` is true for a
                // symlink POINTING at a directory too, and `/var/minis/*` are
                // exactly that — following them would re-walk the per-session
                // trees (double-counting them) and, for any link that points at
                // an ancestor, never terminate.
                if v?.isDirectory == true, v?.isSymbolicLink != true {
                    stack.append((kid, rel))
                } else {
                    out.append((rel, allocatedSize(v)))
                }
            }
        }
        return out
    }

    func load() {
        isLoading = true
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }

            let libraryURL = self.fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
            let documentsURL = self.fm.urls(for: .documentDirectory, in: .userDomainMask).first!
            let tmpURL = self.fm.temporaryDirectory

            let chatSessions = await ChatStore.shared.listSessions()
            let sessionIds = Set(chatSessions.map(\.id))
            // [T-child-delete-storage] child → parent, to fold agent child
            // sessions onto their root parent's row.
            let parentOf: [String: String] = Dictionary(uniqueKeysWithValues:
                chatSessions.compactMap { s in s.parentSessionId.flatMap { p in p.isEmpty ? nil : (s.id, p) } })

            var shell: Int64 = 0
            var staleRootfs: Int64 = 0
            var db: Int64 = 0
            var logsCaches: Int64 = 0
            var tmpTotal: Int64 = 0
            var orphaned: Int64 = 0
            var minisSizes: [String: Int64] = [:]
            var total: Int64 = 0

            // ---- Documents ----------------------------------------------------
            // alpine-rootfs is the live shell; `.alpine-rootfs-broken-<stamp>` is a
            // tree RootfsManager could neither delete nor reuse and moved aside.
            // Each occurrence leaves a full rootfs copy behind, so it gets its own
            // line rather than being folded into "other".
            for (rel, size) in self.entries(under: documentsURL) {
                total += size
                if rel.hasPrefix("alpine-rootfs/") || rel == "alpine-rootfs" {
                    shell += size
                } else if rel.hasPrefix(".alpine-rootfs-broken-") {
                    staleRootfs += size
                }
                // anything else in Documents falls through to `other`
            }

            // ---- Library ------------------------------------------------------
            for (rel, size) in self.entries(under: libraryURL) {
                total += size
                if rel.hasPrefix("MinisChat/minis/") {
                    // Library/MinisChat/minis/<sessionId>/…
                    let parts = rel.split(separator: "/", maxSplits: 3, omittingEmptySubsequences: false)
                    guard parts.count >= 3 else { continue }
                    let sid = String(parts[2])
                    if sessionIds.contains(sid) {
                        minisSizes[sid, default: 0] += size
                    } else {
                        // No DB row for this directory: previously invisible and
                        // unclearable, because the sessions list is built from the
                        // DB and this id is not in it.
                        orphaned += size
                    }
                } else if rel.hasPrefix("MinisChat/minis.db") {
                    // Covers minis.db plus its -wal / -shm siblings, which are as
                    // much "the database" as the main file is and can be large
                    // mid-transaction.
                    db += size
                } else if rel.hasPrefix("Logs/") || rel.hasPrefix("Caches/") {
                    logsCaches += size
                }
            }

            // ---- tmp ----------------------------------------------------------
            for (_, size) in self.entries(under: tmpURL) {
                total += size
                tmpTotal += size
            }

            // Rows: top-level sessions only; each carries its own files plus
            // every descendant's. The global total below still sums raw
            // per-directory sizes once, so nothing is counted twice.
            let rolledUp = SessionTree.aggregateOntoRoots(minisSizes, parentOf: parentOf)
            var childrenOf: [String: [String]] = [:]
            for (c, p) in parentOf { childrenOf[p, default: []].append(c) }
            let sorted: [SessionStorage] = chatSessions
                .filter { !$0.isChild }
                .map { session in
                    SessionStorage(
                        id: session.id,
                        title: session.title,
                        minisSize: rolledUp[session.id] ?? 0,
                        descendantIds: Array(SessionTree.withDescendants([session.id]) { childrenOf[$0] ?? [] }.dropFirst())
                    )
                }.sorted { $0.totalSize > $1.totalSize }

            let sessionsTotal = minisSizes.values.reduce(0, +)
            let classified = shell + staleRootfs + db + logsCaches + tmpTotal + orphaned + sessionsTotal
            // Residual, never negative: a file counted into two buckets would
            // otherwise render as a nonsensical negative "Other".
            let other = max(0, total - classified)

            // Snapshot into immutable bindings before the hop. Capturing the
            // accumulator `var`s directly is an error under the Swift 6 language
            // mode, and the values are final at this point anyway.
            let finalShell = shell, finalStale = staleRootfs, finalDB = db
            let finalLogs = logsCaches, finalTmp = tmpTotal, finalOrphaned = orphaned
            let finalTotal = total

            await MainActor.run {
                self.shellContainerSize = finalShell
                self.staleRootfsSize = finalStale
                self.chatDatabaseSize = finalDB
                self.logsAndCachesSize = finalLogs
                self.temporarySize = finalTmp
                self.orphanedSessionSize = finalOrphaned
                self.otherSize = other
                self.containerTotalSize = finalTotal
                self.sessions = sorted
                self.isLoading = false
            }
        }
    }
}

// MARK: - Storage Management View

struct StorageManagementView: View {
    @StateObject private var vm = StorageManagementViewModel()

    var body: some View {
        List {
            Section {
                storageRow(icon: "terminal", color: .gray, label: "Shell Container", value: vm.format(vm.shellContainerSize))
                storageRow(icon: "cylinder", color: .blue, label: "Chat Database", value: vm.format(vm.chatDatabaseSize))
                storageRow(icon: "doc", color: .indigo, label: "Session Files", value: vm.format(vm.totalSessionSize))
                storageRow(icon: "doc.text", color: .orange, label: "Logs & Caches", value: vm.format(vm.logsAndCachesSize))
                storageRow(icon: "clock", color: .teal, label: "Temporary Files", value: vm.format(vm.temporarySize))
                // Only shown when non-zero: these two are failure residue, and a
                // permanent "0 bytes" row would read as a normal part of the app.
                if vm.orphanedSessionSize > 0 {
                    storageRow(icon: "questionmark.folder", color: .brown, label: "Orphaned Session Files", value: vm.format(vm.orphanedSessionSize))
                }
                if vm.staleRootfsSize > 0 {
                    storageRow(icon: "exclamationmark.triangle", color: .red, label: "Stale Shell Leftovers", value: vm.format(vm.staleRootfsSize))
                }
                storageRow(icon: "ellipsis", color: .gray, label: "Other", value: vm.format(vm.otherSize))
                HStack {
                    Text("Total")
                        .fontWeight(.semibold)
                    Spacer()
                    Text(vm.format(vm.containerTotalSize))
                        .fontWeight(.semibold)
                }
            } header: {
                Text("Overview")
            } footer: {
                Text("Total is the app's full container size and should match the figure iOS Settings shows for Minis. \"Other\" covers everything not itemized above, so the categories always add up to the total.")
            }

            Section("Sessions") {
                if vm.isLoading {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                } else if vm.sessions.isEmpty {
                    Text("No sessions")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(vm.sessions) { session in
                        NavigationLink {
                            SessionStorageDetailView(session: session, onFilesCleared: { vm.load() })
                        } label: {
                            HStack {
                                Text(session.title ?? "Untitled")
                                    .lineLimit(1)
                                Spacer()
                                Text(vm.format(session.totalSize))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Storage")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { vm.load() }
    }

    private func storageRow(icon: String, color: Color, label: String, value: String) -> some View {
        HStack {
            Image(systemName: icon)
                .font(.system(size: 9))
                .foregroundStyle(.white)
                .frame(width: 21, height: 21)
                .background(color, in: Circle())
            Text(label)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Session Storage Detail View

struct SessionStorageDetailView: View {
    let session: StorageManagementViewModel.SessionStorage
    var onFilesCleared: (() -> Void)?

    @State private var showClearConfirmation = false
    @State private var isClearing = false
    @State private var currentMinisSize: Int64

    init(session: StorageManagementViewModel.SessionStorage, onFilesCleared: (() -> Void)? = nil) {
        self.session = session
        self.onFilesCleared = onFilesCleared
        _currentMinisSize = State(initialValue: session.minisSize)
    }

    private let formatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.allowedUnits = [.useBytes, .useKB, .useMB, .useGB]
        f.countStyle = .file
        return f
    }()

    private var minisURL: URL {
        let lib = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        return lib.appendingPathComponent("MinisChat/minis/\(session.id)", isDirectory: true)
    }

    private var totalFileSize: Int64 { currentMinisSize }
    private var hasFiles: Bool { totalFileSize > 0 }

    var body: some View {
        List {
            Section("Minis Files") {
                if currentMinisSize > 0 {
                    NavigationLink {
                        FileBrowserView(rootPath: minisURL)
                    } label: {
                        HStack {
                            Label("Browse Files", systemImage: "folder")
                            Spacer()
                            Text(formatter.string(fromByteCount: currentMinisSize))
                                .foregroundStyle(.secondary)
                        }
                    }
                } else {
                    Text("No minis files")
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                Button(role: .destructive) {
                    showClearConfirmation = true
                } label: {
                    HStack {
                        if isClearing {
                            ProgressView()
                                .controlSize(.small)
                            Text("Clearing…")
                        } else {
                            Label("Clear Session Files", systemImage: "trash")
                        }
                        Spacer()
                        if !isClearing {
                            Text(formatter.string(fromByteCount: totalFileSize))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(!hasFiles || isClearing)
            } footer: {
                Text("Removes all files generated by this session. The conversation itself will be preserved.")
            }
        }
        .navigationTitle(session.title ?? "Session")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Clear Session Files?", isPresented: $showClearConfirmation) {
            Button("Clear \(formatter.string(fromByteCount: totalFileSize))", role: .destructive) {
                clearFiles()
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This will delete \(formatter.string(fromByteCount: totalFileSize)) of files. This action cannot be undone.")
        }
    }

    private func clearFiles() {
        isClearing = true
        let sessionId = session.id
        let descendants = session.descendantIds
        Task.detached(priority: .userInitiated) {
            let fm = FileManager.default
            let lib = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
            let minisBase = lib.appendingPathComponent("MinisChat/minis", isDirectory: true)
            // [T-child-delete-storage] The row aggregates its agent children,
            // so clearing it clears their directories too.
            for sid in [sessionId] + descendants {
                SessionStorageCleanup.removeSessionDirectory(sid, minisBase: minisBase)
            }

            await MainActor.run {
                currentMinisSize = 0
                isClearing = false
                onFilesCleared?()
            }
        }
    }
}
