import SwiftUI

private let logger = AppLogger(category: "Backup")

/// Minimal user-facing entry point for creating a backup package
/// (docs/backup-restore-design.md §6.2 path 1).
///
/// Pick categories, optionally set a passphrase, export, then hand the package
/// to the share sheet / "Save to Files". Restore lives in `BackupRestoreView`,
/// linked from here.
struct BackupSettingsView: View {
    /// True when hosted inside `BackupAndRestoreView`, which owns the
    /// navigation title. Without this the child would overwrite the
    /// container's "Backup & Restore" with its own "Backup".
    var embedded = false

    @State private var selected: Set<BackupCategory> = Set(BackupCategory.backupable)
    /// The Include toggles are styled like the iCloud settings rows, and rows
    /// that look like settings are expected to SURVIVE leaving the screen —
    /// a fresh launch used to silently reset a trimmed selection back to
    /// everything, so the next backup was much bigger than the user chose.
    /// Stored as raw values joined by comma; unknown values from a future
    /// build are dropped on load.
    @AppStorage("backup.selectedCategories") private var selectedStore: String = ""
    @State private var passphrase = ""
    @State private var confirmPassphrase = ""

    /// §3.4 per-file cap, in MB. Persisted so the choice survives leaving the
    /// screen — it is a preference, not a per-run decision.
    ///
    /// Default 100 MB rather than §3.4's "unlimited": an unlimited backup of a
    /// chat history with large attachments can run to many GB, which is slow to
    /// produce and awkward to move around. A file over the cap is NOT silently
    /// dropped — it leaves a tombstone the restore surfaces — so the trade-off
    /// is visible rather than lossy-and-quiet.
    @AppStorage("backup.maxFileSizeMB") private var maxFileSizeMB: Int = 100

    /// Sentinel for "no cap" (§3.4's `maxFileBytes == nil`).
    static let unlimitedTag = 0

    /// Sentinel for "skip file contents entirely" — messages and metadata only.
    ///
    /// Negative rather than 0 because 0 already means unlimited, and the stored
    /// value is a plain MB Int in AppStorage that predates this option.
    ///
    /// Maps to `maxFileBytes = 0`, so it rides the EXISTING cap logic rather
    /// than adding a second way to skip a file: every file is over the cap, so
    /// each one is counted and gets a §3.4 tombstone. That matters — the
    /// package still records which files existed, so a restore reports them as
    /// deliberately excluded instead of silently missing.
    static let noFilesTag = -1

    /// Whether to encrypt. Defaults OFF, so the common case — back up, keep the
    /// file — takes no passphrase and nothing to forget.
    ///
    /// The trade-off is stated rather than hidden: with encryption off the
    /// package carries no credentials (secrets.json is base64, an encoding, not
    /// protection), and the footer says so whenever Providers is selected. A
    /// user who wants their API keys to travel turns this on.
    @AppStorage("backup.encrypt") private var encryptBackup: Bool = false

    @State private var isExporting = false
    @State private var statusText = ""
    @State private var errorText: String?
    @State private var result: BackupExporter.Summary?
    @State private var shareURL: URL?
    /// True when the local package was deleted after every destination
    /// received a verified copy — the result section changes shape.
    @State private var localCopyRemoved = false
    @State private var showShare = false
    @State private var showSaveToFiles = false

    /// §6.2 path 2 — mounted folders this backup is also delivered into.
    /// Mirrored into @State so toggling redraws; the source of truth stays in
    /// `BackupDestinations` (UserDefaults).
    @State private var destinationIds: [UUID] = []
    /// Per-destination outcome of the last export.
    @State private var deliveryResults: [BackupDestinations.DeliveryResult] = []
    /// Set when a just-picked destination fails its writability probe.
    @State private var destinationWarning: String?
    @State private var showAddDestination = false
    @ObservedObject private var history = BackupHistory.shared
    @ObservedObject private var runController = BackupRunController.shared
    @ObservedObject private var transfer = BackupTransferStatus.shared
    /// [T-ios-backup-transient-success] True once this view instance has shown
    /// the current finished transfer to the user.
    ///
    /// `onAppear` also fires when returning from a pushed detail (history
    /// record, destination), and clearing the card then would delete it mid-
    /// visit — the user taps a row, comes back, and the result they were
    /// reading is gone. Set when the rows are first displayed here, so the
    /// clear on a LATER visit (fresh view instance, flag back to false) still
    /// happens while a same-visit return is left alone.
    @State private var didPresentFinishedTransfer = false
    /// Configured rclone remotes, mirrored for redraw.
    @State private var remotes: [RcloneRemoteStore.Remote] = []
    /// Server destination whose detail screen should be pushed. Drives an
    /// explicit navigationDestination so the row can be tappable without a
    /// NavigationLink drawing a chevron next to the row's switch.
    ///
    /// Holds the remote's NAME rather than the struct: navigationDestination
    /// wants Hashable, and making a shared model type conform just to satisfy
    /// a navigation detail is the wrong direction. Re-looking the remote up on
    /// push also means the pushed screen sees current data rather than a copy
    /// captured when the row was tapped.
    @State private var openedRemoteName: String?
    /// Folder destination whose detail screen should be pushed — see above.
    @State private var openedFolderId: UUID?

    /// [T-backup-device-name-setting] Working copy of the user's device name.
    ///
    /// Held in @State and committed on change rather than bound straight to
    /// `DeviceIdentity.customName`: the field must be able to be empty WHILE
    /// being edited (the user clears it to retype), and writing an empty
    /// string through immediately would clear the override and make the
    /// placeholder jump to the automatic name mid-keystroke.
    @State private var deviceNameDraft = ""

    var body: some View {
        Form {
            deviceNameSection
            Section {
                // Row styling mirrors the iCloud Sync category list
                // (CloudSyncSettingsV2View): a 28pt rounded-square badge with a
                // white glyph. Same kind of choice, so it should look the same
                // rather than inventing a second visual language for it.
                ForEach(BackupCategory.backupable, id: \.self) { category in
                    Toggle(isOn: binding(for: category)) {
                        HStack(spacing: 12) {
                            BackupCategoryIcon(category: category)
                            Text(displayName(category))
                        }
                    }
                }

                // §3.4's cap, surfaced. Only meaningful when a category that
                // actually carries files is selected — with only DB-backed
                // categories on, nothing would ever hit it.
                if selected.contains(where: \.carriesFileTree) {
                    // Ordered smallest to largest, with "no files at all" at
                    // the top as the extreme of the same axis.
                    Picker(selection: $maxFileSizeMB) {
                        Text("Don't back up files").tag(BackupSettingsView.noFilesTag)
                        Text("1 MB").tag(1)
                        Text("2 MB").tag(2)
                        Text("5 MB").tag(5)
                        Text("10 MB").tag(10)
                        Text("50 MB").tag(50)
                        Text("100 MB").tag(100)
                        Text("500 MB").tag(500)
                        Text("Unlimited").tag(BackupSettingsView.unlimitedTag)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "doc.zipper")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 28, height: 28)
                                .background(Color.gray, in: Circle())
                            Text("Max Per-File Size")
                        }
                    }
                    .pickerStyle(.menu)
                }
            } header: {
                Text("Include")
            } footer: {
                // The cap's consequence is stated, because §3.4's whole point is
                // that a skipped file leaves a tombstone rather than silently
                // vanishing — the user should know that before it happens, not
                // discover it at restore time.
                if selected.contains(where: \.carriesFileTree),
                   maxFileSizeMB == Self.noFilesTag {
                    // Worth stating plainly: this is the one setting that makes
                    // the backup unable to restore a conversation's files at
                    // all, so it should not read like just another size.
                    Text("Chats includes every file in each conversation, not just the messages. File contents are not being backed up — the backup lists which files existed, but restoring won't bring them back.")
                } else if selected.contains(where: \.carriesFileTree),
                          maxFileSizeMB != Self.unlimitedTag {
                    Text("Chats includes every file in each conversation, not just the messages. Files larger than \(maxFileSizeMB) MB are listed in the backup but not included.")
                } else {
                    Text("Chats includes every file in each conversation, not just the messages.")
                }
            }

            // Encryption is a CHOICE; the passphrase only appears once it's on.
            //
            // The constraint that actually exists is narrower than "always
            // encrypt": credentials must never ship in the clear, because
            // secrets.json is base64 — an encoding, not protection. So
            // encryption is optional, and turning it OFF drops credentials from
            // the package rather than exposing them. The exporter enforces the
            // same rule independently (it refuses credentials without a
            // passphrase), so this is the UI expressing a real invariant, not
            // inventing one.
            Section {
                Toggle(isOn: $encryptBackup.animation()) {
                    HStack(spacing: 12) {
                        Image(systemName: encryptBackup ? "lock.fill" : "lock.open.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 28, height: 28)
                            .background(encryptBackup ? Color.green : Color.gray,
                                        in: Circle())
                        Text("Encrypt Backup")
                    }
                }
                if encryptBackup {
                    SecureField("Passphrase", text: $passphrase)
                        .textContentType(.newPassword)
                    if !passphrase.isEmpty {
                        SecureField("Confirm passphrase", text: $confirmPassphrase)
                            .textContentType(.newPassword)
                        if passphrase != confirmPassphrase {
                            Text("Passphrases don't match.")
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                    }
                }
            } header: {
                Text("Encryption")
            } footer: {
                if encryptBackup {
                    // Both halves are load-bearing: what the passphrase covers,
                    // and that losing it is final. A user who doesn't
                    // understand the second can lose everything.
                    Text("This passphrase encrypts the entire backup, including chats, files, and credentials. There is no way to recover a forgotten passphrase — the backup cannot be opened without it.")
                } else if selected.contains(.providers) {
                    // Say what turning it off actually costs, in the one case
                    // where it costs something.
                    Text("Without encryption the backup is not protected, and API keys are left out — restored providers will need their keys entered again. Anyone with the file can read everything else in it.")
                } else {
                    Text("Without encryption the backup is not protected — anyone with the file can read its contents.")
                }
            }

            destinationSection

            if !embedded {
                Section {
                    NavigationLink {
                        BackupRestoreView()
                    } label: {
                        Label {
                            Text("Restore from Backup…")
                        } icon: {
                            Image(systemName: "arrow.down.doc")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                } footer: {
                    Text("Restoring merges a backup into your existing data. Nothing is deleted.")
                }
            }

            Section {
                // ONE button that toggles. While a backup runs it says "Stop
                // Backup" and stops it — nothing else about this row changes.
                //
                // It used to do three things at once: the button turned into a
                // disabled spinner captioned with the live status, and a
                // SECOND Stop button appeared under it. That put the progress
                // report inside the control, so the row a user reaches for
                // moved and greyed out at the moment they wanted to act, and
                // the section grew a row every time a run started. Progress
                // belongs in the run's own entry in Backup History below,
                // which already shows the status text and the full log; the
                // control stays a control.
                Button(role: runController.isRunning ? .destructive : nil) {
                    if runController.isRunning {
                        BackupRunController.shared.stop()
                        return
                    }
                    // The Task is handed to the controller so Stop (here or
                    // in the run's detail view) can cancel it — a view can be
                    // dismissed mid-backup, and a running task nobody holds is
                    // one nobody can stop.
                    let t = Task { await runExport() }
                    BackupRunController.shared.started(task: t)
                } label: {
                    // [T-restore-primary-action] Badged like every other
                    // action row in the backup screens, and matching Start
                    // Restore on the other tab — a bare glyph here was the odd
                    // one out. Red while running, so Stop reads as the
                    // destructive action it is.
                    HStack(spacing: 10) {
                        BackupActionIcon(
                            systemName: runController.isRunning
                                ? "stop.fill" : "externaldrive.badge.timemachine",
                            tint: runController.isRunning ? .red : .blue)
                        Text(runController.isRunning ? "Stop Backup" : "Start Backup")
                    }
                    // Without maxWidth the row sizes to its content and sits
                    // left; this is what centres it in the Form row.
                    .frame(maxWidth: .infinity)
                    .fontWeight(.medium)
                }
                // The start-time requirements gate STARTING only. Applying
                // them while running would disable the button mid-run and
                // leave no way to stop the backup from this screen.
                // A destination is now a REQUIREMENT, not a nice-to-have. With
                // none configured the package would only ever reach the app's
                // own sandbox, where it dies with the app it exists to
                // protect — that is not a backup, so the button says so
                // instead of producing one.
                .disabled(!runController.isRunning
                          && (selected.isEmpty || !passphraseValid || !hasDestination))
                // List aligns a row's separator to its first Text's leading
                // edge. These rows centre their content, so the text starts
                // mid-row and the separator under "Exporting sessions…" only
                // spanned the middle of the card (user report: the divider
                // above Stop Backup looked broken). Pin it to the row's
                // leading edge so it runs the full card width.
                .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }

                // A disabled button with no explanation is a dead end — say
                // which requirement is unmet rather than leaving the user to
                // guess why nothing happens when they tap.
                if !isExporting {
                    // Same centred-row separator fix as the buttons above:
                    // without the guide these hint rows inherit a mid-card
                    // separator start.
                    if !hasDestination {
                        // First, because it is the requirement a new user is
                        // most likely to be missing — the categories arrive
                        // already selected.
                        Text("Add a storage destination to continue.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                            .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
                    } else if selected.isEmpty {
                        Text("Choose at least one thing to include.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                            .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
                    } else if encryptBackup && passphrase.isEmpty {
                        Text("Set a passphrase to encrypt this backup.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                            .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
                    } else if encryptBackup && passphrase != confirmPassphrase {
                        Text("Confirm the passphrase to continue.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                            .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
                    }
                }

                transferRows

                if let errorText {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }

            historySection

            if let result {
                Section {
                    LabeledContent("Size", value: byteText(result.totalBytes))
                    if result.skippedFiles > 0 {
                        // §3.4: a size-capped export has gaps, and the user is
                        // told here rather than discovering it at restore time.
                        // "too large" would be wrong when files were left out
                        // on purpose — nothing exceeded anything.
                        LabeledContent(maxFileSizeMB == Self.noFilesTag
                                       ? "Files not included"
                                       : "Excluded (too large)",
                                       value: "\(result.skippedFiles) file(s)")
                    }
                    // Share / Save need the local file — gone once it was
                    // removed after full delivery. The destinations list below
                    // is what remains relevant then.
                    if shareURL != nil {
                        Button {
                            showShare = true
                        } label: {
                            Label("Share…", systemImage: "square.and.arrow.up")
                        }
                        Button {
                            showSaveToFiles = true
                        } label: {
                            Label("Save to Files…", systemImage: "folder")
                        }
                    }

                    // Per-destination outcome. Reported individually rather
                    // than as one combined status: "2 of 3 saved" is only
                    // actionable if the user can see WHICH one failed.
                    ForEach(deliveryResults) { r in
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Image(systemName: r.succeeded
                                  ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                                .foregroundStyle(r.succeeded ? .green : .orange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.folderName)
                                if let error = r.error {
                                    Text(error)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .font(.footnote)
                    }
                } header: {
                    Text("Backup Ready")
                } footer: {
                    if localCopyRemoved {
                        Text("Delivered to all \(deliveryResults.count) destination(s) and verified. The copy on this iPhone was removed to save space.")
                    } else if deliveryResults.isEmpty {
                        Text("Saved in Minis ▸ Backups. Use Save to Files to copy it to iCloud Drive, a connected server, or another cloud provider.")
                    } else {
                        let ok = deliveryResults.filter(\.succeeded).count
                        // The local copy is stated explicitly so a user whose
                        // NAS was offline can see the backup still exists.
                        Text("Saved in Minis ▸ Backups, and copied to \(ok) of \(deliveryResults.count) selected folder(s).")
                    }
                }
            }
        }
        .modifier(StandaloneTitle(title: AppLocalized("Backup"),
                                 active: !embedded))
        .onAppear {
            // [T-ios-backup-transient-success] Clear a previous run's finished
            // transfer rows on the way IN, not on the way out.
            //
            // `BackupTransferStatus` is a process-wide singleton and nothing
            // ever called `reset()`, so a successful run's 100% / size /
            // duration card stayed on screen for the life of the app — every
            // later visit to Backup opened onto a stale result. Clearing here
            // means the card is fully visible for as long as the user is
            // watching the backup finish (this view does not re-appear while
            // they sit on it), and simply is not there next time they come
            // back. The same facts remain in Backup History.
            //
            // Why not `onDisappear`: this screen is a Form inside a
            // UIKit-backed NavigationView, so pushing a history record or a
            // destination detail fires `onDisappear` on it too — clearing
            // there would yank the card out from under a user who just tapped
            // into the run they were reading about, and it would be gone when
            // they came back. `onAppear` fires in that return case as well,
            // but by then the visit that produced the result has ended, which
            // is exactly when the card has stopped being useful.
            //
            // Guarded so this only ever drops a SETTLED, SUCCESSFUL transfer:
            // a run in flight, and any run with a failed destination, are left
            // untouched (see clearIfSettledAndSuccessful).
            //
            // `didPresentFinishedTransfer` keeps a same-visit return from a
            // pushed detail from counting as "next time" — see its declaration.
            if !didPresentFinishedTransfer {
                transfer.clearIfSettledAndSuccessful()
            }
            // Any rows still standing after the clear above belong to THIS
            // visit from here on: either a live run, or a finished one that
            // was deliberately kept (a failure). Marking them presented is
            // what makes a return from a pushed detail leave them alone.
            if !transfer.destinations.isEmpty { didPresentFinishedTransfer = true }
            loadSelectedCategories()
            destinationIds = BackupDestinations.selectedIds
            remotes = RcloneRemoteStore.remotes
            // rclone's config is in-memory only, so it must be rebuilt each
            // time this screen appears (and after a relaunch).
            RcloneRemoteStore.syncToRclone()
        }
        // Destination rows push their details through these hidden links
        // rather than wrapping the row in a visible NavigationLink, which would
        // draw a disclosure chevron immediately left of the row's toggle.
        //
        // NavigationLink(isActive:) rather than navigationDestination(item:):
        // the app target is iOS 16 and that modifier is 17+.
        .background {
            NavigationLink(isActive: Binding(
                get: { openedRemoteName != nil },
                set: { if !$0 { openedRemoteName = nil } }
            )) {
                // Resolved on push. If the remote vanished (removed by a swipe
                // while this was open) show nothing rather than a stale
                // snapshot of a destination that no longer exists.
                if let name = openedRemoteName,
                   let r = RcloneRemoteStore.remotes.first(where: { $0.name == name }) {
                    BackupDestinationDetailView(target: .remote(r)) {
                        remotes = RcloneRemoteStore.remotes
                    }
                }
            } label: { EmptyView() }
                .opacity(0)

            NavigationLink(isActive: Binding(
                get: { openedFolderId != nil },
                set: { if !$0 { openedFolderId = nil } }
            )) {
                if let id = openedFolderId,
                   let folder = BackupDestinations.eligibleFolders.first(where: { $0.id == id }) {
                    BackupDestinationDetailView(target: .folder(folder)) {
                        destinationIds = BackupDestinations.selectedIds
                    }
                }
            } label: { EmptyView() }
                .opacity(0)
        }
        .sheet(isPresented: $showAddDestination) {
            BackupDestinationPicker {
                destinationIds = BackupDestinations.selectedIds
                remotes = RcloneRemoteStore.remotes
                destinationWarning = nil
            }
        }
        .sheet(isPresented: $showShare) {
            if let shareURL {
                BackupShareSheet(url: shareURL)
            }
        }
        .sheet(isPresented: $showSaveToFiles) {
            if let shareURL {
                BackupDocumentExportPicker(url: shareURL)
            }
        }
        // [review S14] The `pendingPackage` sheet used to live here. It is now
        // mounted at the WindowGroup root (MinisApp.swift) so opening a
        // .minisbak from Files works from ANY screen — this view is the one
        // place the user is least likely to already be standing when they
        // migrate to a new device.
    }

    // MARK: - Validation

    /// A passphrase is now unconditionally required.
    ///
    /// §3.3 made it conditional on credentials being included; credentials are
    /// no longer optional, so the condition collapsed. The exporter enforces
    /// this too — this only stops the user reaching a button that would fail.
    /// Whether anywhere outside this app's sandbox has been configured to
    /// receive the backup.
    ///
    /// Deliberately "configured", not "enabled": a user who toggles their only
    /// destination off for one run is making a temporary choice about that
    /// run, and disabling the whole screen underneath them would be a
    /// surprise. Having none at all is the state that makes a backup
    /// pointless, and that is what this gates.
    private var hasDestination: Bool {
        !remotes.isEmpty || !BackupDestinations.eligibleFolders.isEmpty
    }

    private var passphraseValid: Bool {
        // A passphrase is required ONLY when encryption is on. With it off the
        // package simply ships without credentials, which is a legitimate
        // choice — blocking it made "Start Backup" permanently un-tappable for
        // anyone who left encryption off (the default).
        guard encryptBackup else { return true }
        return !passphrase.isEmpty && passphrase == confirmPassphrase
    }

    private func binding(for category: BackupCategory) -> Binding<Bool> {
        Binding(
            get: { selected.contains(category) },
            set: { on in
                if on { selected.insert(category) } else { selected.remove(category) }
                selectedStore = selected.map(\.rawValue).sorted().joined(separator: ",")
            })
    }

    /// Restore the persisted Include selection. An empty store means the user
    /// never changed anything — keep the all-on default.
    func loadSelectedCategories() {
        guard !selectedStore.isEmpty else { return }
        let stored = Set(selectedStore.split(separator: ",")
            .compactMap { BackupCategory(rawValue: String($0)) })
        // Categories that exist on this build but aren't in the stored string
        // stay OFF only if the user turned them off; a brand-new category
        // (added in an upgrade) defaults ON by not being representable in the
        // old string — but we can't tell those apart, so prefer the user's
        // explicit selection as saved.
        if !stored.isEmpty { selected = stored }
    }

    /// Backup runs, newest first — the live one plus the past month.
    ///
    /// Sits under the button because that is where the user's attention already
    /// is after tapping it. Before this, a finished run's per-destination
    /// results lived in view state and vanished on dismissal, so "did last
    /// night's backup reach the NAS?" had no answer.
    /// Per-destination upload progress, and the cleanup outcome afterwards.
    ///
    /// Sending the package is usually the longest part of a backup and used to
    /// report nothing at all — the screen said "Copying to destinations…" for
    /// the whole transfer, so a slow link and a stalled one looked identical.
    @ViewBuilder
    private var transferRows: some View {
        // Reading `tick` here is what subscribes this section to the
        // once-a-second redraw; the clock-derived figures below would
        // otherwise only move when the byte counter did.
        let _ = transfer.tick
        ForEach(transfer.destinations) { d in
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Image(systemName: d.error != nil ? "exclamationmark.triangle.fill"
                          : d.finishedAt != nil ? "checkmark.circle.fill"
                          : "arrow.up.circle")
                        .foregroundStyle(d.error != nil ? .orange
                                         : d.finishedAt != nil ? .green : .blue)
                    Text(d.name).font(.callout)
                    Spacer()
                    Text("\(Int(d.fraction * 100))%")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                if d.error == nil {
                    ProgressView(value: d.fraction)
                }
                // Bytes / rate / elapsed / remaining — the four numbers that
                // together answer "is this working, and how long more?".
                if let error = d.error {
                    Text(error).font(.caption).foregroundStyle(.orange)
                } else {
                    Text(transferDetail(d))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 2)
        }

        if let note = transfer.cleanupNote {
            Label {
                Text(note).font(.caption)
            } icon: {
                Image(systemName: "sparkles").foregroundStyle(.green)
            }
            .foregroundStyle(.secondary)
        }

    }

    private func transferDetail(_ d: BackupTransferStatus.Destination) -> String {
        let sent = ByteCountFormatter.string(fromByteCount: d.bytesSent, countStyle: .file)
        let total = ByteCountFormatter.string(fromByteCount: d.totalBytes, countStyle: .file)
        let elapsed = BackupTransferStatus.durationText(d.elapsed)
        if d.finishedAt != nil {
            return AppLocalized("\(total) in \(elapsed)")
        }
        let rate = BackupTransferStatus.rateText(d.bytesPerSecond)
        if let remaining = d.remaining {
            return AppLocalized("\(sent) of \(total) · \(rate) · \(elapsed) elapsed · about \(BackupTransferStatus.durationText(remaining)) left")
        }
        return AppLocalized("\(sent) of \(total) · \(elapsed) elapsed")
    }

    /// Whether this record is the interrupted run whose staging is still on
    /// disk, and so can actually be continued.
    ///
    /// There is at most one such run — the journal keeps a single marker — so
    /// this matches on `backupId` rather than offering Resume on every failed
    /// record. A run that failed for any other reason has nothing to resume,
    /// and a Resume that quietly started a fresh backup would be a lie.
    ///
    /// Not shown while a backup is running: starting a second one is refused,
    /// and offering the action anyway invites a tap that does nothing.
    private func isResumable(_ r: BackupHistory.Record) -> Bool {
        guard !runController.isRunning, r.status == .failed,
              !r.backupId.isEmpty,
              let marker = BackupExportJournal.interrupted(),
              marker.backupId == r.backupId
        else { return false }
        return FileManager.default.fileExists(
            atPath: BackupExportJournal.stagingRoot(backupId: marker.backupId).path)
    }

    /// [T-backup-device-name-setting] Lets the user name this device, which is
    /// what the backup filename leads with and what the iCloud sync device
    /// list shows.
    ///
    /// This is not cosmetic. Since iOS 16 `UIDevice.current.name` returns the
    /// MODEL ("iPhone") unless the app holds the user-assigned-device-name
    /// entitlement, so two iPhones backing up to one shared folder produce the
    /// same device token and remain indistinguishable — exactly the problem
    /// putting the device in the filename was meant to solve. Typing a name
    /// solves it without an entitlement.
    private var deviceNameSection: some View {
        Section {
            HStack(spacing: 12) {
                Image(systemName: "iphone")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(Color.blue, in: Circle())
                // The placeholder is the automatic name, so an untouched field
                // shows the user what will be used rather than sitting blank.
                TextField(DeviceIdentity.automaticName, text: $deviceNameDraft)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .onSubmit { commitDeviceName() }
                    // Committed on every change, not only on submit: this
                    // screen is usually left by swiping back or by tapping
                    // Create Backup, neither of which fires onSubmit — and a
                    // name that silently failed to save would be worse than
                    // no field at all.
                    //
                    // Single-parameter `onChange`, because the deployment
                    // target is iOS 16 and the two-parameter overload is 17+.
                    .onChange(of: deviceNameDraft) { _ in commitDeviceName() }
            }
        } header: {
            Text("Device Name")
        } footer: {
            // Says what the name is FOR — the whole point is the filename —
            // and how to get back to the default, which an empty field does
            // not otherwise advertise.
            Text("Used to identify this device in backup filenames and in the iCloud sync device list. Leave empty to use “\(DeviceIdentity.automaticName)”.")
        }
        // Seeded in onAppear rather than defaulted, so reopening the screen
        // shows what is actually stored instead of an empty field over a set
        // name.
        .onAppear { deviceNameDraft = DeviceIdentity.customName ?? "" }
    }

    /// Persists the draft. Empty (or whitespace) clears the override and falls
    /// back to the automatic name — `DeviceIdentity.customName` trims and
    /// length-caps, so nothing unusable is stored.
    private func commitDeviceName() {
        DeviceIdentity.customName = deviceNameDraft
    }

    @ViewBuilder
    private var historySection: some View {
        if !history.records.isEmpty {
            Section {
                ForEach(history.records) { r in
                    NavigationLink {
                        BackupHistoryDetailView(record: r)
                    } label: {
                        BackupHistoryRow(record: r)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            BackupHistory.shared.remove(r.id)
                        } label: {
                            Label("Remove", systemImage: "trash")
                        }
                        // Continuing an interrupted run is now an explicit act
                        // on the run itself, so the user can see WHICH backup
                        // they are continuing. Start Backup no longer adopts it
                        // silently — that produced a package pinned to the old
                        // run's snapshot, missing everything written since.
                        if isResumable(r) {
                            Button {
                                let t = Task { await runExport(resuming: true) }
                                BackupRunController.shared.started(task: t)
                            } label: {
                                Label("Resume", systemImage: "play.circle")
                            }
                            .tint(.blue)
                        }
                    }
                }
            } header: {
                Text("Backup History")
            } footer: {
                Text("Records from the past month. Older ones are removed automatically.")
            }
        }
    }

    // MARK: - Destinations (§6.2 path 2)

    /// Lets the user send every backup to one or more mounted folders.
    ///
    /// No networking lives behind this: an SMB / AFP / WebDAV / cloud folder
    /// mounted in Files and authorised in Minis is just a directory, and
    /// iOS's FileProvider does the protocol work. That is why the same list
    /// covers a NAS and iCloud Drive without either being special-cased.
    @ViewBuilder
    private var destinationSection: some View {
        let eligible = BackupDestinations.eligibleFolders
        Section {
            // Network drives added in-app (SMB / WebDAV / SFTP / S3 / FTP).
            // Listed first: a user who went to the trouble of typing in a
            // server address is more invested in it than in a folder they
            // happened to pick from Files.
            ForEach(remotes) { r in
                // Toggle enables/disables delivery; the row itself opens the
                // details. Disabling keeps the server and its credential, so
                // skipping one destination for a while costs nothing to undo.
                // A NavigationLink used to sit INSIDE the Toggle's label, which
                // drew the link's disclosure chevron wedged between the text
                // and the switch. Pinched there it read as a stray glyph rather
                // than "there is more behind this row" — the position a
                // chevron needs to mean that is the trailing edge, and the
                // switch already owns it.
                //
                // The row is still tappable: navigation moves to an explicit
                // hidden link driven by state, so the label area pushes the
                // detail view and the switch keeps its own hit area.
                HStack(spacing: 12) {
                    Image(systemName: RcloneBackendCatalog.backend(for: r.backend)?.icon
                          ?? "externaldrive.connected.to.line.below")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 28, height: 28)
                        .background(Color.blue, in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(r.name)
                        Text("\(r.backend.uppercased()) · /\(r.path)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    // Claims the gap between the text and the switch, so the
                    // whole left side of the row opens the details.
                    Spacer(minLength: 8)
                        .contentShape(Rectangle())
                    Toggle("", isOn: Binding(
                        get: { r.enabled },
                        set: { on in
                            RcloneRemoteStore.setEnabled(r.name, on)
                            remotes = RcloneRemoteStore.remotes
                        }
                    ))
                    .labelsHidden()
                    .fixedSize()
                }
                .contentShape(Rectangle())
                .onTapGesture { openedRemoteName = r.name }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        RcloneRemoteStore.remove(name: r.name)
                        remotes = RcloneRemoteStore.remotes
                    } label: {
                        Label("Remove", systemImage: "trash")
                    }
                }
            }

            if eligible.isEmpty && remotes.isEmpty {
                // Empty is the NORMAL starting state, so this reads as the
                // next step rather than a problem to fix.
                //
                // It used to say the backup "is saved on this device", which
                // offered the one outcome a backup must never have: the
                // package lands in the app's own sandbox, so it dies with the
                // app it is meant to protect — uninstall, a wiped device or a
                // lost phone takes the backup with it. Presenting that as a
                // working fallback invited the user to consider themselves
                // backed up when nothing had left the device.
                Text("No destinations yet — add one to back up.")
                    .foregroundStyle(.secondary)
            } else if !eligible.isEmpty {
                // Same badge treatment as the category rows above, so the two
                // lists read as one screen rather than two styles.
                ForEach(eligible) { folder in
                    // Same shape as the server rows above — no NavigationLink
                    // inside the Toggle, so no chevron pinched against the
                    // switch.
                    HStack(spacing: 12) {
                        Image(systemName: "folder.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 28, height: 28)
                            .background(Color.indigo, in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(folder.name)
                            Text(folder.sourceDisplayName)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                            .contentShape(Rectangle())
                        Toggle("", isOn: destinationBinding(for: folder))
                            .labelsHidden()
                            .fixedSize()
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { openedFolderId = folder.id }
                    .swipeActions(edge: .trailing) {
                        // Removes it from THIS list only. The underlying mount
                        // survives, because the user may also have added it for
                        // the agent and deleting that from here would be a
                        // surprise.
                        Button(role: .destructive) {
                            BackupDestinations.forget(folder.id)
                            destinationIds = BackupDestinations.selectedIds
                        } label: {
                            Label("Remove", systemImage: "trash")
                        }
                    }
                }
            }
            // ONE entry point. Two buttons ("Add Folder" / "Add Server")
            // asked the user to know which mechanism they wanted before they
            // knew what was on offer — and a server they had already set up
            // was invisible until they guessed correctly. The picker shows
            // saved servers first and offers both ways to add a new one.
            Button {
                showAddDestination = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "plus")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 28, height: 28)
                        .background(Color.green, in: Circle())
                    Text("Add Backup Destination…")
                }
            }

            if let destinationWarning {
                Text(destinationWarning)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        } header: {
            Text("Backup Destinations")
        } footer: {
            if eligible.isEmpty {
                Text("Add a folder to also copy each backup there — including a server connected in the Files app (SMB, WebDAV) or a cloud provider.")
            } else {
                Text("Each new backup is also copied to the selected folders — including servers connected in the Files app (SMB, WebDAV) and cloud providers. A folder that is offline is reported and skipped; the local copy is always kept.")
            }
        }
    }

    private func destinationBinding(for folder: MountedFolderEntry) -> Binding<Bool> {
        Binding(
            get: { destinationIds.contains(folder.id) },
            set: { on in
                destinationWarning = nil
                if on {
                    // Probe before accepting the choice. A mount can be listed
                    // and authorised but still unwritable right now — the share
                    // is offline, or the provider dropped the scope. Finding
                    // that out here beats finding out at the end of a
                    // multi-hundred-MB export.
                    if let root = MountedFoldersManager.shared.resolvedURL(for: folder.id) {
                        if !MountedFoldersManager.probeWritable(at: root) {
                            destinationWarning = String(
                                format: AppLocalized("“%@” isn't writable right now. It may need to be reconnected in the Files app."),
                                folder.name)
                            return
                        }
                    } else {
                        destinationWarning = String(
                            format: AppLocalized("“%@” isn't available right now. Open it once in the Files app, then try again."),
                            folder.name)
                        return
                    }
                }
                BackupDestinations.toggle(folder.id, on: on)
                destinationIds = BackupDestinations.selectedIds
            })
    }

    // MARK: - Export

    /// - Parameter resuming: continue the interrupted run instead of starting a
    ///   new one. Only ever true from the Resume action on that run's history
    ///   row — Start Backup always means "back up the data as it is now".
    private func runExport(resuming: Bool = false) async {
        isExporting = true
        errorText = nil
        result = nil
        shareURL = nil
        deliveryResults = []
        localCopyRemoved = false
        defer { isExporting = false }

        let options = BackupExporter.Options(
            categories: selected,
            // nil = no cap; 0 = every file is over the cap, i.e. contents are
            // skipped and only tombstones are recorded.
            maxFileBytes: maxFileSizeMB == Self.unlimitedTag ? nil
                : maxFileSizeMB == Self.noFilesTag ? 0
                : Int64(maxFileSizeMB) * 1024 * 1024,
            // Credentials only ship when the package is encrypted. secrets.json
            // is base64 — an encoding, not protection — so an unencrypted
            // package carrying keys would be a plaintext copy of them. The
            // exporter refuses that combination independently; this makes the
            // UI agree rather than walk into that error.
            includeCredentials: encryptBackup,
            passphrase: encryptBackup && !passphrase.isEmpty ? passphrase : nil,
            allowResume: resuming)

        // Opened BEFORE the work starts, so a run that crashes mid-flight still
        // leaves a record — that is exactly the case a user needs to see.
        let runId = BackupHistory.shared.begin(
            backupId: "", categories: selected.map(\.rawValue).sorted(),
            encrypted: encryptBackup)
        // Attach the real record id to the task the caller already registered,
        // and make sure the running state clears on EVERY exit path —
        // including cancellation.
        BackupRunController.shared.attach(recordId: runId)
        // Token-guarded: a start that was REFUSED (because another backup is
        // already running) still reaches this defer, and an unguarded call
        // would tear down the state — and the keep-alive audio session — of the
        // run that is legitimately in flight.
        let token = BackupRunController.shared.currentToken
        defer { BackupRunController.shared.finished(token: token) }

        do {
            // [review I5] Hold a background assertion so switching apps
            // mid-export doesn't get the process suspended ~30s later.
            let summary = try await BackupBackgroundAssertion.run("BackupExport") {
                try await BackupExporter().export(options: options) { id in
                    // Ties this record to its staging tree, so an interrupted
                    // run can be identified and offered for Resume.
                    Task { @MainActor in
                        BackupHistory.shared.setBackupId(runId, id)
                    }
                } progressDetailed: { text, transient in
                    // `transient` is a live counter line ("120/400 · about 40s
                    // left"); the history log replaces the previous one instead
                    // of stacking a new row every second.
                    Task { @MainActor in
                        statusText = text
                        BackupRunController.shared.update(status: text)
                        BackupHistory.shared.log(runId, text, isTransient: transient)
                    }
                }
            }
            // Out of tmp/ before anything else: iOS can purge that directory,
            // and the share sheet may be dismissed and re-opened later.
            let stable = try BackupDelivery.moveToVisibleStorage(summary.packageURL)
            result = summary
            shareURL = stable

            // §6.2 path 2. Runs AFTER the local copy is safely in place, so a
            // dead network share can never cost the user the backup itself.
            // Guard covers BOTH kinds of destination — checking only the
            // folder selection silently skipped delivery for a user whose
            // sole destination was a server.
            if !BackupDestinations.selectedIds.isEmpty
                || !RcloneRemoteStore.enabledRemotes.isEmpty {
                statusText = AppLocalized("Copying to destinations…")
                let packageBytes = (try? FileManager.default.attributesOfItem(
                    atPath: stable.path)[.size] as? Int64) ?? summary.totalBytes
                let names = BackupDestinations.selectedFolders.map(\.name)
                    + RcloneRemoteStore.enabledRemotes.map(\.name)
                // Mirror transfer events into this run's record. The live rows
                // disappear when the run ends, so without this the log jumped
                // from "Export complete" straight to a finished backup with no
                // trace of the upload — which is where most of the time goes.
                BackupTransferStatus.shared.logSink = { text, transient in
                    Task { @MainActor in
                        BackupHistory.shared.log(runId, text, isTransient: transient)
                    }
                }
                BackupHistory.shared.log(runId, String(
                    localized: "Sending \(ByteCountFormatter.string(fromByteCount: packageBytes, countStyle: .file)) to \(names.count) destination(s)…"))
                BackupTransferStatus.shared.begin(names: names, totalBytes: packageBytes)
                // [T-ios-backup-transient-success] This run's result belongs to
                // the visit the user is in right now. Marking it presented here
                // means opening a history record mid-run and coming back does
                // NOT count as the "next visit" that clears the finished rows;
                // only actually leaving and returning does.
                didPresentFinishedTransfer = true
                deliveryResults = await BackupDestinations.deliver(
                    packageURL: stable, backupId: summary.backupId)
                BackupTransferStatus.shared.end()
                // The sink stays connected past this point on purpose: the
                // local-copy cleanup note below is emitted after delivery and
                // belongs in the record too.
            }

            // The local package is a fallback, not an archive. Once every
            // enabled destination has a VERIFIED copy, keeping a third one on
            // the phone just spends the user's storage — 236 MB per run adds
            // up fast. Any failure (or having no destinations at all) keeps
            // it, so the backup always exists somewhere.
            if !deliveryResults.isEmpty, deliveryResults.allSatisfy(\.succeeded) {
                let freed = (try? FileManager.default.attributesOfItem(
                    atPath: stable.path)[.size] as? Int64) ?? 0
                try? FileManager.default.removeItem(at: stable)
                shareURL = nil
                localCopyRemoved = true
                logger.info("[Backup] local copy removed after all destinations verified")
                // Say so in the UI as well. Deleting the local package is the
                // right call once every destination has a verified copy, but
                // silently is the wrong way to do it: the user has no way to
                // tell "cleaned up" from "the backup is gone".
                BackupTransferStatus.shared.noteCleanup(String(
                    localized: "Local copy removed — \(ByteCountFormatter.string(fromByteCount: freed, countStyle: .file)) freed on this iPhone"))
            } else if !deliveryResults.isEmpty {
                BackupTransferStatus.shared.noteCleanup(String(
                    localized: "Local copy kept — not every destination was verified"))
            }
            // Detached only now that the cleanup note has been recorded.
            BackupTransferStatus.shared.logSink = nil

            BackupHistory.shared.finish(
                runId,
                totalBytes: summary.totalBytes,
                skippedFiles: summary.skippedFiles,
                packageName: stable.lastPathComponent,
                destinations: deliveryResults.map {
                    .init(name: $0.folderName, succeeded: $0.succeeded, detail: $0.error,
                          kind: $0.kind, path: $0.remotePath)
                },
                // Turn each skipped path into something recognisable. Paths are
                // `chats/<sessionId>/…`, and the title map was captured during
                // the export so a conversation deleted later still resolves.
                skippedEntries: summary.skippedPaths.map { item in
                    let parts = item.path.split(separator: "/")
                    let sid = parts.count > 1 && parts[0] == "chats"
                        ? String(parts[1]) : nil
                    return .init(path: item.path, size: item.size,
                                 sessionTitle: sid.flatMap { summary.sessionTitles[$0] })
                })

            statusText = ""
            logger.info("[Backup] user export complete bytes=\(summary.totalBytes) delivered=\(deliveryResults.filter(\.succeeded).count)/\(deliveryResults.count)")
        } catch is CancellationError {
            // The user pressed Stop. That is an ACTION they took, not a failure
            // that happened to them — reporting it in red as an error (with
            // Foundation's generic "operation couldn't be completed" text) says
            // something went wrong when nothing did. The record notes it was
            // stopped and that the next run resumes.
            errorText = nil
            BackupHistory.shared.fail(runId, message: AppLocalized("Stopped — the next backup picks up from here."))
            logger.info("[Backup] user export stopped by user")
        } catch {
            errorText = error.localizedDescription
            BackupHistory.shared.fail(runId, message: error.localizedDescription)
            logger.error("[Backup] user export failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Display

    private func displayName(_ c: BackupCategory) -> String {
        switch c {
        case .chats: return AppLocalized("Chats")
        case .sharedFiles: return AppLocalized("Shared Files")
        case .skills: return AppLocalized("Skills")
        case .memory: return AppLocalized("Memory & Soul")
        case .providers: return AppLocalized("Providers")
        case .mcpServers: return AppLocalized("MCP Servers")
        case .voiceCorrections: return AppLocalized("Voice Corrections")
        case .environmentVariables: return AppLocalized("Environment Variables")
        }
    }

    /// Glyph + colour per category, matching the iCloud Sync list's visual
    /// language (and reusing its choices where the categories correspond).


    private func byteText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
