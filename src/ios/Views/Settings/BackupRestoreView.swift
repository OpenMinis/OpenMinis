import SwiftUI
import UniformTypeIdentifiers

private let logger = AppLogger(category: "Backup")

/// User-facing restore flow (docs/backup-restore-design.md §8.1).
///
/// Follows the documented order exactly: pick a package → read its manifest and
/// show a summary → choose categories → passphrase if encrypted → confirm →
/// import → report.
///
/// Only **Merge** is offered. Replace / Skip-existing are stage 5, and shipping
/// a mode picker with one option would imply choices that don't exist. What the
/// mode does is stated in the UI rather than left for the user to infer — the
/// task flagged the risk that "restore" reads as "overwrite", and Merge is the
/// opposite: nothing local is deleted, and a newer local copy wins.
struct BackupRestoreView: View {
    /// Optional package to open immediately — set when the flow is entered by
    /// opening a `.minisbak` file from Files rather than from this screen.
    var initialPackageURL: URL?
    /// True when hosted inside `BackupAndRestoreView`, which owns the title.
    var embedded = false

    @State private var packageURL: URL?
    /// A copy we own. A URL from the document picker is security-scoped and
    /// only valid inside a start/stopAccessing pair, so the file is copied into
    /// tmp/ once and everything afterwards reads from that copy.
    @State private var localCopyURL: URL?
    @State private var manifest: BackupManifest?
    @State private var selected: Set<BackupCategory> = []
    @State private var passphrase = ""

    @State private var showPicker = false
    /// Adding / browsing a server this device has not saved yet.
    @State private var showServerPicker = false
    /// Saved server destinations, mirrored for redraw — a server added through
    /// the sheet has to appear in the destinations list without leaving here.
    @State private var remotes: [RcloneRemoteStore.Remote] = []
    /// True between picking a package and its manifest being read. Drives the
    /// "Opening backup…" row; without it a slow share looks like a dead tap.
    @State private var isInspecting = false
    /// [T-restore-inspect-cancel] Rotating detail under "Opening backup…".
    @State private var inspectStage = ""
    /// The in-flight inspect, so Cancel can stop it.
    @State private var inspectTask: Task<Void, Never>?
    @State private var isWorking = false
    @State private var statusText = ""
    @State private var errorText: String?
    @State private var report: BackupImporter.Report?
    @State private var showConfirm = false

    var body: some View {
        Form {
            if report == nil {
                // Chosen package FIRST. Everything below is about finding one;
                // once found, what it is and the button that acts on it are
                // what the user came for, and burying them under the pickers
                // meant scrolling past the whole browse UI to reach Restore.
                if let manifest {
                    selectedPackageSection(manifest)
                    categorySection(manifest)
                    if manifest.encryption != nil { passphraseSection }
                    actionSection
                    // [T-restore-hide-pickers] Nothing else. Once a package is
                    // chosen the screen is about THAT package, and leaving the
                    // destination list and "Other Sources" below it invited a
                    // second pick that would silently replace the first — and
                    // pushed Start Restore up into the middle of the screen
                    // with browse UI underneath. The way back is the card's own
                    // "Choose a Different Backup", which is where someone who
                    // has changed their mind will look.
                } else if isInspecting {
                    // Also alone: an open in progress owns the screen, and its
                    // Cancel is the way out.
                    inspectingSection
                } else {
                    destinationsSection
                    otherSourcesSection
                }
            } else if let report {
                reportSection(report)
            }

            if let errorText {
                Section {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
        .modifier(StandaloneTitle(title: AppLocalized("Restore"),
                                 active: !embedded))
        .fileImporter(isPresented: $showPicker,
                      allowedContentTypes: [BackupDelivery.contentType, .data],
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { load(url) }
            case .failure(let error):
                errorText = error.localizedDescription
            }
        }
        .sheet(isPresented: $showServerPicker) {
            ServerRestorePickerSheet { url in
                showServerPicker = false
                load(url)
            }
            // A server added inside the sheet becomes a destination; pick it
            // up on dismiss so it appears in the list above straight away
            // rather than only after leaving and re-entering the screen.
            .onDisappear { remotes = RcloneRemoteStore.remotes }
        }
        .confirmationDialog("Restore this backup?", isPresented: $showConfirm, titleVisibility: .visible) {
            Button("Restore") { Task { await runImport() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Items from the backup are merged into your existing data. Nothing is deleted, and anything you've changed more recently is kept.")
        }
        .onAppear {
            remotes = RcloneRemoteStore.remotes
            if let initialPackageURL, packageURL == nil { load(initialPackageURL) }
        }
    }

    // MARK: - Sections

    /// The destinations this device already backs up to, listed directly.
    ///
    /// [T-restore-destinations-first] This screen used to open on nothing but
    /// two "Choose…" buttons: the destinations the user had already configured
    /// on the Backup tab were reachable only by opening a picker that then
    /// listed them. But those destinations ARE the answer in the common case —
    /// the backup being restored is almost always one this device (or its
    /// predecessor) wrote to a folder or server the user set up. So they are
    /// shown up front, one row each, and tapping one browses its packages.
    ///
    /// Folders and servers are deliberately in ONE list rather than split by
    /// mechanism. To the user they are both "the place my backups go"; which
    /// of them happens to be a Files bookmark and which an rclone remote is an
    /// implementation detail, and grouping by it would make the screen read as
    /// two half-empty lists.
    @ViewBuilder
    private var destinationsSection: some View {
        let folders = BackupDestinations.selectedFolders
        if !folders.isEmpty || !remotes.isEmpty {
            Section {
                ForEach(folders) { folder in
                    NavigationLink {
                        FolderPackageListView(folder: folder, onPicked: load)
                    } label: {
                        destinationRow(
                            name: folder.name,
                            subtitle: AppLocalized("Shared Folder"),
                            systemName: "externaldrive.connected.to.line.below.fill",
                            tint: .teal)
                    }
                    .disabled(isWorking)
                }
                ForEach(remotes) { r in
                    NavigationLink {
                        ServerPackageListView(remote: r, onPicked: load)
                    } label: {
                        destinationRow(
                            name: r.name,
                            subtitle: "\(r.backend.uppercased()) · /\(r.path)",
                            systemName: "network",
                            tint: .indigo)
                    }
                    .disabled(isWorking)
                }
            } header: {
                Text("Backup Destinations")
            } footer: {
                Text("Tap a destination to browse its folders and pick a backup.")
            }
        }
    }

    private func destinationRow(name: String, subtitle: String,
                                systemName: String, tint: Color) -> some View {
        HStack(spacing: 12) {
            BackupActionIcon(systemName: systemName, tint: tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
    }

    /// Everywhere else a package might be, including destinations this device
    /// has never seen.
    ///
    /// Separate from the list above because the two answer different
    /// questions: that one is "which of my places", this one is "somewhere
    /// else". The audience here is a NEW device — nothing configured, backups
    /// sitting on a NAS it has never connected to — so "Browse Other
    /// Servers…" leads to a sheet that can add one, and anything added there
    /// becomes a destination and moves up into the list above.
    private var otherSourcesSection: some View {
        Section {
            Button {
                showPicker = true
            } label: {
                Label {
                    Text("Choose from Files…")
                } icon: {
                    BackupActionIcon(systemName: "folder.fill", tint: .blue)
                }
            }
            .disabled(isWorking)

            Button {
                showServerPicker = true
            } label: {
                Label {
                    Text("Browse Other Servers…")
                } icon: {
                    BackupActionIcon(systemName: "globe", tint: .purple)
                }
            }
            .disabled(isWorking)
        } header: {
            Text("Other Sources")
        } footer: {
            Text("Pick a .minisbak file from Files, iCloud Drive, or any connected storage — or add a server your backups were saved to.")
        }
    }

    /// Shown while the picked package is being opened and its manifest read.
    ///
    /// Without it, tapping a package on a slow share left the screen looking
    /// unchanged for several seconds — the summary simply had not appeared
    /// yet — which reads as a tap that did nothing.
    /// Shown while the picked package is being opened and its manifest read.
    ///
    /// [T-restore-inspect-cancel] Opening a multi-GB package means unzipping
    /// it, which can run for a long time with nothing to show — a single
    /// static "Opening backup…" reads as a hung screen. Two additions:
    ///
    ///  - a rotating detail line, in a smaller face beneath the title, naming
    ///    the stage the open is actually at;
    ///  - a Cancel, because until now the only way out of a slow open was to
    ///    kill the app, and doing that leaves the scratch copy behind.
    private var inspectingSection: some View {
        Section {
            HStack(spacing: 12) {
                ProgressView()
                VStack(alignment: .leading, spacing: 2) {
                    Text("Opening backup…")
                        .foregroundStyle(.secondary)
                    // Deliberately smaller and dimmer than the title: it is
                    // reassurance that work is happening, not information the
                    // user has to read.
                    Text(inspectStage)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                        // The stage text changes underneath a fixed layout, so
                        // the row must not resize as the wording changes.
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .animation(.easeInOut(duration: 0.2), value: inspectStage)
                }
                Spacer(minLength: 8)
                Button {
                    cancelInspect()
                } label: {
                    Text("Cancel")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .layoutPriority(1)
            }
        }
    }

    /// [T-restore-destinations-first] The chosen package, at the very top:
    /// what it is, how big, and how much is in it.
    ///
    /// The per-category breakdown below this is the detail; this is the
    /// answer to "is this the right file?", which is the question a user has
    /// immediately after picking one — especially when several packages in a
    /// folder differ only by date.
    private func selectedPackageSection(_ m: BackupManifest) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 12) {
                    BackupActionIcon(systemName: "doc.zipper", tint: .green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(packageURL?.lastPathComponent ?? AppLocalized("Backup"))
                            .font(.callout.weight(.medium))
                            .lineLimit(2)
                            .truncationMode(.middle)
                        Text(packageHeadline(m))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            LabeledContent("Created",
                           value: m.createdAt.formatted(date: .abbreviated, time: .shortened))
            // The data cut-off, when the package records one. Distinct from
            // "Created": anything changed after this instant is deliberately
            // not in the package, and the user should see that before
            // deciding this is the backup they want.
            if let snapshotAt = m.snapshotAt, snapshotAt != m.createdAt {
                LabeledContent("Data as of",
                               value: snapshotAt.formatted(date: .abbreviated, time: .shortened))
            }
            LabeledContent("From", value: "\(m.deviceName) · \(m.app.platform) \(m.app.version)")
            if m.encryption != nil {
                LabeledContent("Encrypted", value: AppLocalized("Yes"))
            }
            if let limits = m.limits.maxFileBytes, limits == 0 {
                // The package was made with file contents switched off, so it
                // can restore conversations but none of their attachments.
                // Saying "size limit" here would be misleading — nothing was
                // too big, the user chose to leave files out.
                LabeledContent("File contents",
                               value: "Not included (\(m.limits.skippedFiles) file(s) listed)")
            } else if let limits = m.limits.maxFileBytes, limits > 0 {
                // §3.4 — the package is known-incomplete, and the user should
                // learn that here rather than after restoring.
                LabeledContent("Excluded (size limit)",
                               value: "\(m.limits.skippedFiles) file(s)")
            }

            // Escape hatch, kept with the thing it replaces rather than in the
            // pickers below: having chosen the wrong package, "choose another"
            // is the next action, and it should be where the user is looking.
            Button {
                clearSelection()
            } label: {
                Label {
                    Text("Choose a Different Backup")
                } icon: {
                    BackupActionIcon(systemName: "arrow.triangle.2.circlepath", tint: .gray)
                }
            }
            .disabled(isWorking)
        } header: {
            Text("Selected Backup")
        }
    }

    /// One line of totals for the package: size, then what is in it.
    ///
    /// Summed from the manifest's per-category stats rather than measured off
    /// the file, so it describes the CONTENT (what would be restored) and not
    /// the archive's compressed size on disk — the number the user is trying
    /// to sanity-check is "does this look like my data".
    private func packageHeadline(_ m: BackupManifest) -> String {
        let bytes = m.categories.values.reduce(Int64(0)) { $0 + $1.bytes }
        var bits = [ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)]
        bits.append(countLabel(m.categories.count,
                               one: AppLocalized("1 category"),
                               many: AppLocalized("%d categories")))
        let items = m.categories.values.reduce(0) { $0 + $1.entries }
        if items > 0 {
            bits.append(countLabel(items,
                                   one: AppLocalized("1 item"),
                                   many: AppLocalized("%d items")))
        }
        return bits.joined(separator: " · ")
    }

    /// [T-restore-inspect-cancel] Stage wording for the open. Not measured
    /// progress — `inspect` unzips in one synchronous call and reports nothing
    /// — so these describe what it is doing rather than how far along it is,
    /// and the last one is held once reached instead of looping back to the
    /// start, which would suggest the work had restarted.
    static let inspectStages: [String] = [
        AppLocalized("Reading the package…"),
        AppLocalized("Expanding files…"),
        AppLocalized("Checking contents…"),
        AppLocalized("Almost there…"),
    ]

    /// Abandon an open that is taking too long, and leave nothing behind.
    private func cancelInspect() {
        inspectTask?.cancel()
        inspectTask = nil
        resetSelection()
    }

    /// Drop the chosen package and go back to the pickers.
    private func clearSelection() { resetSelection() }

    /// [T-restore-reset-state] The ONE place selection state is torn down, so
    /// Cancel and "Choose a Different Backup" cannot clear different subsets
    /// and leave the screen wedged.
    ///
    /// Every field set while a package is selected is cleared here, including
    /// `isInspecting` and `report`: leaving `isInspecting` true would keep the
    /// spinner row on screen with no task behind it, and a stale `report`
    /// would hide the pickers entirely — both states the user cannot get out
    /// of without leaving the screen.
    private func resetSelection() {
        // Cancel first: a running open would otherwise finish and write its
        // manifest into the state we are about to clear, re-selecting a
        // package the user just dismissed.
        inspectTask?.cancel()
        inspectTask = nil

        // The local copy is ours and can be gigabytes; leaving it in tmp/ for
        // iOS to purge "eventually" is how the sweeper's whole class of bug
        // starts. BackupExportJournal.sweep() would catch it on next launch,
        // but there is no reason to wait.
        if let localCopyURL { try? FileManager.default.removeItem(at: localCopyURL) }
        // Remove the enclosing scratch directory too when the copy came from a
        // server download, for the same reason as `discardScratch`.
        if let packageURL,
           packageURL.path.hasPrefix(FileManager.default.temporaryDirectory.path),
           packageURL.deletingLastPathComponent().lastPathComponent.hasPrefix("server-restore-") {
            try? FileManager.default.removeItem(at: packageURL.deletingLastPathComponent())
        }

        packageURL = nil
        localCopyURL = nil
        manifest = nil
        selected = []
        passphrase = ""
        errorText = nil
        isInspecting = false
        inspectStage = ""
        report = nil
        statusText = ""
    }

    private func categorySection(_ m: BackupManifest) -> some View {
        Section {
            ForEach(availableCategories(m), id: \.self) { category in
                Toggle(isOn: binding(for: category)) {
                    // Same badge as the Backup tab, so a category is
                    // recognisable across both screens rather than only by
                    // its wording.
                    HStack(spacing: 12) {
                        BackupCategoryIcon(category: category)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(displayName(category))
                            if let stat = m.categories[category.rawValue] {
                                Text(detailText(category, stat))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        } header: {
            Text("Restore")
        } footer: {
            Text("Merge: items are matched by id. Existing items are only replaced when the backup's copy is newer. Nothing is deleted.")
        }
    }

    private var passphraseSection: some View {
        Section {
            SecureField("Passphrase", text: $passphrase)
                .textContentType(.password)
        } header: {
            Text("Encryption")
        } footer: {
            Text("This backup is encrypted. Without its passphrase it cannot be opened.")
        }
    }

    private var actionSection: some View {
        Section {
            Button {
                showConfirm = true
            } label: {
                // [T-restore-primary-action] Centred icon + label, matching
                // Start Backup on the other tab. This is the screen's primary
                // action and was a left-aligned "Restore" that read like one
                // more row in the list rather than the thing to press.
                HStack(spacing: 10) {
                    Spacer(minLength: 0)
                    if isWorking {
                        ProgressView()
                            .frame(width: 28, height: 28)
                        Text(statusText.isEmpty ? AppLocalized("Working…") : statusText)
                            .foregroundStyle(.secondary)
                    } else {
                        BackupActionIcon(systemName: "arrow.down.doc.fill", tint: .indigo)
                        Text("Start Restore")
                            .fontWeight(.semibold)
                    }
                    Spacer(minLength: 0)
                }
            }
            .disabled(isWorking || selected.isEmpty || !passphraseReady)
        }
    }

    private func reportSection(_ r: BackupImporter.Report) -> some View {
        Section {
            LabeledContent("Restored", value: "\(r.totalImported)")
            if r.totalUpdated > 0 { LabeledContent("Updated", value: "\(r.totalUpdated)") }
            // "Skipped" is the expected outcome for anything already present,
            // so it is labelled as such rather than looking like a failure.
            LabeledContent("Already up to date", value: "\(r.totalSkipped)")
            if r.totalUnreadable > 0 {
                LabeledContent("Unreadable", value: "\(r.totalUnreadable)")
            }
            let credsRestored = r.categories.reduce(0) { $0 + $1.credentialsRestored }
            if credsRestored > 0 {
                LabeledContent("API keys restored", value: "\(credsRestored)")
            }
            ForEach(r.categories, id: \.category) { c in
                if let failed = c.failed {
                    Text("\(displayNameRaw(c.category)): \(failed)")
                        .font(.caption)
                        .foregroundStyle(.red)
                } else {
                    if c.sizeSkippedInPackage > 0 {
                        Text("\(displayNameRaw(c.category)): \(c.sizeSkippedInPackage) file(s) weren't in the backup (size limit)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    // [review S9] Different remedy from the size cap, so it gets
                    // its own line rather than being folded into "skipped":
                    // these files exist, they just weren't on the device that
                    // made the backup.
                    if c.notDownloadedInPackage > 0 {
                        Text("\(displayNameRaw(c.category)): \(c.notDownloadedInPackage) file(s) weren't in the backup (not downloaded from iCloud on the source device)")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                    // [review S7] The package's own index referenced content it
                    // did not contain — the backup is incomplete. Shown in red:
                    // this is the case that used to be silently swallowed and
                    // reported as a clean success.
                    if c.missingBlobs > 0 {
                        Text("\(displayNameRaw(c.category)): \(c.missingBlobs) file(s) were listed in the backup but missing from it — the backup is incomplete")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }
            if !r.rolledBack.isEmpty {
                Text("Rolled back: \(r.rolledBack.joined(separator: ", "))")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        } header: {
            Text("Restore Complete")
        } footer: {
            if let prov = r.categories.first(where: { $0.category == BackupCategory.providers.rawValue }) {
                if prov.credentialsRestored > 0 {
                    // Credentials now always travel with Providers, so this is
                    // the normal outcome rather than a bonus.
                    Text("API keys were restored for \(prov.credentialsRestored) provider(s) along with their settings. Existing keys on this device were kept. OAuth logins may still need to be renewed if their tokens have expired.")
                } else if prov.credentialsKept > 0 {
                    // [T-backup-credentials-message-inverted] Nothing was
                    // WRITTEN, but only because every key in the package is
                    // already on this device — `credentialsRestored` counts
                    // keys actually written (BackupSecretsImporter increments
                    // it only `if wrote`), while an existing key increments
                    // `providersSkippedExisting` instead.
                    //
                    // This is the normal outcome when restoring onto the device
                    // that made the backup, and it used to fall into the branch
                    // below — telling the user "this backup was created without
                    // API keys… enter each API key again" on a fully
                    // credentialed device, which is exactly backwards.
                    // `credentialsKept` was already being computed for this and
                    // simply never read by any view.
                    Text("Your existing API keys were kept — the backup's keys matched credentials already on this device, so nothing needed to be overwritten.")
                } else {
                    // Reachable for OLDER packages: exports used to offer a
                    // credential-free "share copy", and those must keep
                    // restoring. Says why nothing arrived instead of implying
                    // the restore misbehaved.
                    Text("This backup was created without API keys, so restored providers have no credentials — enter each API key or sign in again before use.")
                }
            }
        }
    }

    // MARK: - Actions

    private func load(_ url: URL) {
        errorText = nil
        report = nil
        manifest = nil
        selected = []

        // Copy out of the security scope before doing anything else: the
        // picker's URL is only usable inside start/stopAccessing, and the
        // import runs async on another actor well after that window.
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("restore-\(UUID().uuidString).\(BackupFormat.fileExtension)")
        do {
            try? FileManager.default.removeItem(at: dest)
            // A package sitting in a mounted folder needs the coordinated read
            // path, not a bare copyItem: on a NAS or a cloud provider the file
            // may still be a placeholder, and MountedFolderCoordinator.copy
            // both materialises it (ensureDownloaded) and coordinates with the
            // owning FileProvider. A plain copy would either fail or produce a
            // truncated package — the second being far worse, since it would
            // surface later as a corrupt archive.
            if MountedFolderCoordinator.isUnderMount(url) {
                try MountedFolderCoordinator.copy(from: url, to: dest)
            } else {
                try FileManager.default.copyItem(at: url, to: dest)
            }
        } catch {
            errorText = "Couldn't read that file: \(error.localizedDescription)"
            return
        }

        packageURL = url
        localCopyURL = dest
        isInspecting = true
        inspectStage = Self.inspectStages.first ?? ""

        // [T-restore-download-scratch-leak] A package downloaded from a server
        // was already written into our own tmp scratch, so the copy above just
        // made a SECOND full-size copy and left the first sitting there —
        // briefly needing twice the free space for a package that can be
        // several GB. The scratch directory is ours and the copy now supersedes
        // it, so drop it.
        if url.path.hasPrefix(FileManager.default.temporaryDirectory.path),
           url.deletingLastPathComponent().lastPathComponent.hasPrefix("server-restore-") {
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }

        // [T-restore-inspect-cancel] Rotate the detail line while the open
        // runs. `inspect` unzips the whole package in one synchronous call and
        // reports nothing along the way, so these are the stages it genuinely
        // moves through rather than measured progress — worded as such
        // ("Reading…", "Expanding…") instead of implying a percentage.
        let ticker = Task { @MainActor in
            var i = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_400_000_000)
                guard !Task.isCancelled else { break }
                i += 1
                inspectStage = Self.inspectStages[min(i, Self.inspectStages.count - 1)]
            }
        }

        inspectTask = Task {
            defer { ticker.cancel() }
            do {
                let m = try await BackupImporter().inspect(packageURL: dest)
                // The work is not interruptible mid-unzip, so a cancel that
                // lands while it runs is honoured HERE — the result is thrown
                // away rather than replacing a screen the user has left.
                if Task.isCancelled { return }
                await MainActor.run {
                    manifest = m
                    isInspecting = false
                    inspectStage = ""
                    // Default to everything the package actually contains.
                    selected = Set(m.categories.keys.compactMap(BackupCategory.init(rawValue:)))
                }
            } catch {
                if Task.isCancelled { return }
                await MainActor.run {
                    errorText = error.localizedDescription
                    // [T-restore-download-scratch-leak] DELETE it, don't just
                    // forget it. Nilling the state alone stranded the copy —
                    // which for a package that failed to open is still a
                    // full-size file — until iOS got round to purging tmp/.
                    try? FileManager.default.removeItem(at: dest)
                    localCopyURL = nil
                    // Also clear packageURL: leaving it set would show the
                    // summary's filename for a package that failed to open,
                    // and leave "Choose a Different Backup" as the only route
                    // back from a state with no manifest behind it.
                    packageURL = nil
                    isInspecting = false
                    inspectStage = ""
                }
            }
        }
    }

    private func runImport() async {
        guard let localCopyURL else { return }
        isWorking = true
        errorText = nil
        defer { isWorking = false }

        var options = BackupImporter.Options()
        options.categories = selected
        options.passphrase = passphrase.isEmpty ? nil : passphrase

        do {
            // [review I5] Same protection for restore — being suspended
            // part-way through writing user data is the worse of the two.
            let r = try await BackupBackgroundAssertion.run("BackupRestore") {
                try await BackupImporter().import(from: localCopyURL, options: options) { text in
                    Task { @MainActor in statusText = text }
                }
            }
            report = r
            statusText = ""
            logger.info("[Restore] user restore complete imported=\(r.totalImported) skipped=\(r.totalSkipped)")
        } catch {
            errorText = error.localizedDescription
            logger.error("[Restore] user restore failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Helpers

    private var passphraseReady: Bool {
        manifest?.encryption == nil || !passphrase.isEmpty
    }

    private func availableCategories(_ m: BackupManifest) -> [BackupCategory] {
        BackupCategory.allCases.filter { m.categories[$0.rawValue] != nil }
    }

    private func binding(for category: BackupCategory) -> Binding<Bool> {
        Binding(
            get: { selected.contains(category) },
            set: { on in
                if on { selected.insert(category) } else { selected.remove(category) }
            })
    }

    /// Singular/plural for a counted noun. Kept explicit rather than using a
    /// String Catalog plural rule because these keys are added by
    /// `scripts/add_localization.py`, which writes simple key/value entries —
    /// a `.stringsdict`-style variation would have to be hand-edited into the
    /// catalog and would not survive the next scripted addition.
    private func countLabel(_ n: Int, one: @autoclosure () -> String,
                            many: @autoclosure () -> String) -> String {
        n == 1 ? one() : String(format: many(), n)
    }

    /// [T-backup-category-counts] Say what the number actually counts. Every
    /// branch below falls back to the generic "items" line when the package
    /// predates the field it needs, so an old backup still renders sensibly.
    private func detailText(_ c: BackupCategory, _ stat: BackupManifest.CategoryStat) -> String {
        let size = ByteCountFormatter.string(fromByteCount: stat.bytes, countStyle: .file)
        switch c {
        case .chats:
            if let messages = stat.messages, let files = stat.files {
                return "\(messages) messages · \(files) files · \(size)"
            }
        case .skills:
            let skills = countLabel(stat.entries,
                                    one: AppLocalized("1 skill"),
                                    many: AppLocalized("%d skills"))
            if let files = stat.files {
                let f = countLabel(files,
                                   one: AppLocalized("1 file"),
                                   many: AppLocalized("%d files"))
                return "\(skills) · \(f) · \(size)"
            }
            return "\(skills) · \(size)"
        case .mcpServers:
            let servers = countLabel(stat.entries,
                                     one: AppLocalized("1 server"),
                                     many: AppLocalized("%d servers"))
            return "\(servers) · \(size)"
        case .providers:
            let providers = countLabel(stat.entries,
                                       one: AppLocalized("1 provider"),
                                       many: AppLocalized("%d providers"))
            if let rules = stat.thinkingRules, rules > 0 {
                let r = countLabel(rules,
                                   one: AppLocalized("1 thinking rule"),
                                   many: AppLocalized("%d thinking rules"))
                return "\(providers) · \(r) · \(size)"
            }
            return "\(providers) · \(size)"
        default:
            break
        }
        return "\(stat.entries) items · \(size)"
    }

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

    private func displayNameRaw(_ raw: String) -> String {
        BackupCategory(rawValue: raw).map(displayName) ?? raw
    }
}

// MARK: - Browse one shared folder

/// The `.minisbak` packages inside a single mounted destination folder.
///
/// [T-restore-destinations-first] The counterpart of `ServerPackageListView`
/// for folder destinations, so both kinds of destination behave the same way:
/// tap the destination, see its backups, tap one to select it. Previously
/// folders had no per-destination view at all — one sheet listed the packages
/// from EVERY selected folder together, with the folder name relegated to a
/// subtitle, which is the wrong shape once destinations are the top-level
/// thing the user picks from.
struct FolderPackageListView: View {
    let folder: MountedFolderEntry
    var onPicked: (URL) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var packages: [BackupDestinations.FoundPackage] = []
    @State private var loading = true

    var body: some View {
        List {
            // [T-restore-breadcrumb] No breadcrumb here, unlike the server
            // browser: a folder destination is a single bookmarked directory
            // with no descent, so there is no path to walk and nothing for an
            // Up control to do. The filter is stated in the footer instead of
            // a header Section, which gave chrome the same inset-card shape as
            // the content.
            Section {
            if loading {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Looking for backups…").foregroundStyle(.secondary)
                }
            } else if packages.isEmpty {
                // Distinguishes "nothing there" from "couldn't reach it" — an
                // offline share contributes no rows, and the user needs to
                // know that is a connectivity problem, not an empty NAS.
                // Hand-rolled rather than ContentUnavailableView: the
                // deployment target is iOS 16 and that is iOS 17+.
                VStack(spacing: 10) {
                    Image(systemName: "externaldrive.badge.questionmark")
                        .font(.system(size: 40))
                        .foregroundStyle(.secondary)
                    Text("No .minisbak files in this folder.")
                        .font(.headline)
                    Text("If it's on a server, open it once in the Files app to reconnect, then pull down to refresh.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 32)
                .listRowBackground(Color.clear)
            }

            ForEach(packages) { pkg in
                Button {
                    // Pop back to the restore screen: the summary it is about
                    // to show is up there, and leaving the user on a list they
                    // have finished with would hide the result of their tap.
                    dismiss()
                    onPicked(pkg.url)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(pkg.url.lastPathComponent)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .foregroundStyle(.primary)
                        Text("\(ByteCountFormatter.string(fromByteCount: pkg.size, countStyle: .file)) · \(pkg.modified.formatted(date: .abbreviated, time: .shortened))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            } footer: {
                if !packages.isEmpty {
                    Text("Showing .minisbak files in this folder.")
                }
            }
        }
        .refreshable { await reload() }
        .navigationTitle(folder.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
    }

    private func reload() async {
        loading = true
        // Enumerating a mounted share can block on I/O (it may be a NAS that
        // has to be woken), so it is kept off the main actor — the same reason
        // the server list detaches its rclone calls.
        let id = folder.id
        let found = await Task.detached(priority: .userInitiated) {
            BackupDestinations.listPackages(folderId: id)
        }.value
        packages = found
        loading = false
    }
}

// MARK: - Restore from an rclone server

/// Pick a backup package straight off a server destination (SMB / WebDAV /
/// SFTP / S3 / FTP via rclone).
///
/// This is the read side of server delivery, and its main audience is a NEW
/// device: the packages live on a NAS, and until now the only way to reach
/// them from here was the Files app — which on a fresh device has no server
/// connected either. So the sheet both lists saved servers AND offers adding
/// one, mirroring the backup-destination picker.
struct ServerRestorePickerSheet: View {
    /// Called with a LOCAL temporary copy of the chosen package.
    var onPicked: (URL) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var remotes: [RcloneRemoteStore.Remote] = []
    @State private var showAddServer = false

    var body: some View {
        NavigationStack {
            Form {
                if !remotes.isEmpty {
                    Section {
                        ForEach(remotes) { r in
                            NavigationLink {
                                ServerPackageListView(remote: r, onPicked: onPicked)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(r.name)
                                    Text("\(r.backend.uppercased()) · /\(r.path)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    } header: {
                        Text("Servers")
                    }
                }

                Section {
                    Button {
                        showAddServer = true
                    } label: {
                        Label("Add Server…", systemImage: "plus")
                    }
                } footer: {
                    Text("Add the server your backups were saved to. Servers added here are also available as backup destinations.")
                }
            }
            .navigationTitle("Restore from Server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .sheet(isPresented: $showAddServer) {
                RcloneAddServerView { remotes = RcloneRemoteStore.remotes }
            }
            .onAppear { remotes = RcloneRemoteStore.remotes }
        }
    }
}

/// [T-restore-download-cancel] Shared cancel flag for an in-flight download.
///
/// A class so the `isCancelled` closure and the Cancel button refer to the
/// SAME storage — see the note on `cancelFlag`. `ObservableObject` so the
/// button can disable itself once tapped; the lock is because the value is
/// written on the main actor and read from the transfer's polling thread.
final class CancelFlag: ObservableObject {
    private let lock = NSLock()
    private var _v = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set {
            lock.lock(); _v = newValue; lock.unlock()
            Task { @MainActor in self.objectWillChange.send() }
        }
    }
}

/// Browse ONE directory of a server, a level at a time, and pick a
/// `.minisbak` to restore from.
///
/// [T-restore-browse-tree] This used to list every package under the
/// destination's configured folder in one shot. That is an expensive listing
/// on a large or deep share, and it could only ever reach packages inside that
/// one folder. Now it opens at the remote's root and the user walks down —
/// one cheap `operations/list` per tap — so a package anywhere on the server
/// is reachable and no directory is scanned unless it is opened.
struct ServerPackageListView: View {
    let remote: RcloneRemoteStore.Remote
    var onPicked: (URL) -> Void
    /// Directory being listed, relative to the remote root. Empty is the root.
    ///
    /// [T-restore-browse-inplace] @State, not a parameter: opening a folder
    /// used to PUSH another copy of this view, which stacked a navigation
    /// entry per level and made "back" mean "up one folder" only by accident —
    /// leaving the screen then took as many taps as the user had descended.
    /// Browsing now happens in place, with an explicit Up control and the path
    /// on screen, so Back always means "leave the browser".
    @State private var path = ""

    /// [T-restore-destinations-first] This view is now pushed from the restore
    /// screen's destination list as well as from inside the server sheet, so
    /// it pops itself after handing the package over. Leaving the user on a
    /// finished list would hide the summary their tap just produced.
    @Environment(\.dismiss) private var dismiss
    @State private var entries: [RcloneTransfer.RemoteEntry] = []
    @State private var loading = true
    @State private var downloadingKey: String?
    @State private var progressText = ""
    @State private var errorText: String?
    /// Name of the package being fetched, for the modal's title.
    @State private var downloadingName = ""
    /// Fraction complete, for the determinate bar.
    @State private var downloadFraction: Double = 0
    /// Current transfer rate, formatted for display, and the time left.
    @State private var speedText = ""
    /// [T-restore-download-confirm] Package awaiting the user's go-ahead.
    /// Holds the package itself rather than a Bool so the alert can state its
    /// size, and so a list reload cannot retarget the confirmation.
    @State private var pendingDownload: RcloneTransfer.RemotePackage?
    /// Set by Cancel; the transfer's poll loop reads it from a background
    /// thread.
    ///
    /// [T-restore-download-cancel] A REFERENCE box, not a plain `@State` Bool.
    /// The `isCancelled` closure handed to `RcloneTransfer.download` captures
    /// the view, and a SwiftUI view is a struct that is REPLACED on every
    /// state change — so a closure created before Cancel was tapped kept
    /// reading the old struct's copy, which stays false forever. Cancel
    /// therefore did nothing no matter how the flag was read. A class instance
    /// is captured by reference, so both sides see the same value.
    @StateObject private var cancelFlag = CancelFlag()

    var body: some View {
        // [T-restore-breadcrumb] Breadcrumb OUTSIDE the List, pinned above it.
        //
        // The path and Up control used to be a `Section`, which gave chrome
        // the same inset-card shape as the content and read as a strange first
        // row. Worse, it scrolled: on a directory with many entries the way
        // back up was off screen exactly when the user had scrolled down
        // looking for something and wanted to leave.
        //
        // A VStack with the bar above the List keeps it fixed while only the
        // rows scroll — the standard iOS shape for a browser's location bar.
        VStack(spacing: 0) {
            breadcrumbBar
            List {
                Section {
                    if loading {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Contacting server…").foregroundStyle(.secondary)
                        }
                    } else if entries.isEmpty, errorText == nil {
                        Text("Nothing here.").foregroundStyle(.secondary)
                    }

                    entryRows
                } footer: {
                    Text("Open folders to browse. Only .minisbak files are shown.")
                }
            }
            .refreshable { await reload() }
        }
        .navigationTitle(remote.name)
        .navigationBarTitleDisplayMode(.inline)
        // A modal, not an inline spinner. Downloading a backup can run for
        // minutes on a large package, and an inline row left the list live —
        // a second tap started a CONCURRENT download of another package, with
        // both writing progress into the same state. The sheet makes the
        // operation exclusive by construction and gives the one control that
        // was missing: a way out.
        .sheet(isPresented: Binding(
            get: { downloadingKey != nil },
            set: { if !$0 { downloadingKey = nil } })) {
            downloadSheet
        }
        // [T-restore-download-confirm] Says how big the download is before it
        // starts. Leads with the size because that is the decision — the name
        // is already on the row the user just tapped.
        .alert(item: $pendingDownload) { pkg in
            Alert(
                title: Text(AppLocalized("Download this backup?")),
                message: Text(String(
                    format: AppLocalized("“%1$@” is %2$@. It will be downloaded to this iPhone before restoring."),
                    pkg.displayName,
                    ByteCountFormatter.string(fromByteCount: pkg.size, countStyle: .file))),
                primaryButton: .default(Text(AppLocalized("Download"))) {
                    download(pkg)
                },
                secondaryButton: .cancel(Text(AppLocalized("Cancel")))
            )
        }
        .task { await reload() }
    }

    /// [T-restore-breadcrumb] The location bar: a back chevron, then the path
    /// as tappable crumbs.
    ///
    /// Crumbs rather than a static string, because in a browser with no
    /// navigation stack the path is the only way to get anywhere but one level
    /// up — tapping "backups" from `/backups/a/b` should go there directly
    /// instead of pressing Up twice. The row scrolls horizontally and is
    /// pinned to the trailing edge, so a deep path shows its DEEPEST crumbs
    /// (where the user is) while the earlier ones remain reachable by
    /// scrolling — the reason this replaced the wrapping label, which grew the
    /// bar to three lines.
    private var breadcrumbBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button {
                    goUp()
                } label: {
                    Image(systemName: "chevron.backward")
                        .font(.body.weight(.semibold))
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(path.isEmpty || loading)
                .foregroundStyle(path.isEmpty || loading ? AnyShapeStyle(.tertiary)
                                                         : AnyShapeStyle(.tint))
                .accessibilityLabel(AppLocalized("Up One Level"))

                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 2) {
                            crumb(title: remote.name, target: "", isLast: path.isEmpty)
                            ForEach(Array(crumbs.enumerated()), id: \.offset) { i, c in
                                Image(systemName: "chevron.compact.right")
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                crumb(title: c.name, target: c.path,
                                      isLast: i == crumbs.count - 1)
                            }
                        }
                        .padding(.vertical, 2)
                        .id("crumbs")
                    }
                    // Keep the current folder visible as the user descends;
                    // otherwise a deep path scrolls its tail out of sight.
                    .onChange(of: path) { _ in
                        withAnimation { proxy.scrollTo("crumbs", anchor: .trailing) }
                    }
                    // ...and on first appearance too. Without this a view
                    // restored at a deep path opens anchored to the LEADING
                    // edge, showing the topmost crumbs while the folder the
                    // user is actually in sits off the right-hand side.
                    .onAppear { proxy.scrollTo("crumbs", anchor: .trailing) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            // A bar, not a card: full-bleed background with a hairline under
            // it, which is what a pinned location bar looks like on iOS.
            .background(.bar)

            Divider()
        }
    }

    /// One crumb. The last is the current folder and is not a link.
    @ViewBuilder
    private func crumb(title: String, target: String, isLast: Bool) -> some View {
        if isLast {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
        } else {
            Button {
                jump(to: target)
            } label: {
                Text(title)
                    .font(.subheadline)
                    .lineLimit(1)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
            .disabled(loading)
        }
    }

    /// The path split into cumulative crumbs: `a/b/c` → a, a/b, a/b/c.
    private var crumbs: [(name: String, path: String)] {
        guard !path.isEmpty else { return [] }
        var acc: [(String, String)] = []
        var running = ""
        for part in path.split(separator: "/") {
            running = running.isEmpty ? String(part) : running + "/" + part
            acc.append((String(part), running))
        }
        return acc
    }

    /// Jump straight to an ancestor.
    private func jump(to target: String) {
        guard target != path else { return }
        entries = []
        errorText = nil
        path = target
        Task { await reload() }
    }

    /// Directories push a child browser; packages download and hand off.
    @ViewBuilder
    private var entryRows: some View {
        Group {
            ForEach(entries) { e in
                if e.isDirectory {
                    Button {
                        descend(into: e)
                    } label: {
                        HStack {
                            Label {
                                Text(e.name).lineLimit(1).truncationMode(.middle)
                                    .foregroundStyle(.primary)
                            } icon: {
                                Image(systemName: "folder.fill")
                                    .foregroundStyle(.tint)
                            }
                            Spacer(minLength: 8)
                            // Kept so a folder still reads as "opens
                            // something" now that it is a Button rather than
                            // a NavigationLink drawing this for us.
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .disabled(downloadingKey != nil || loading)
                } else {
                    Button {
                        // [T-restore-download-confirm] Confirm before pulling
                        // the file. A backup can be several GB, and the tap
                        // that starts it looks exactly like the tap that opens
                        // a folder — on cellular or a metered connection that
                        // is an expensive thing to trigger by accident.
                        pendingDownload = e.asPackage
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(e.name)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .foregroundStyle(.primary)
                            Text(subtitle(e.asPackage))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .disabled(downloadingKey != nil)
                }
            }

            if let errorText {
                Text(errorText)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }

    private var downloadSheet: some View {
        VStack(spacing: 20) {
            Text("Downloading Backup")
                .font(.headline)
            Text(downloadingName)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)

            // Determinate where possible: the package size is known up front,
            // so a bar beats a spinner at answering "how much longer".
            ProgressView(value: downloadFraction)
                .progressViewStyle(.linear)
            Text(progressText)
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
            // [T-restore-download-speed] Rate and time left, on their own
            // line so the byte counts above stay easy to read. Empty until
            // there is a rate to show rather than displaying a placeholder
            // zero, which would read as a stalled transfer.
            if !speedText.isEmpty {
                Text(speedText)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Button(role: .cancel) {
                // Takes effect at the next poll tick rather than instantly;
                // the partial file is removed by the transfer layer.
                cancelFlag.value = true
                progressText = AppLocalized("Cancelling…")
                speedText = ""
            } label: {
                Text("Cancel").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(cancelFlag.value)
        }
        .padding(24)
        .presentationDetents([.height(240)])
        // No swipe-to-dismiss: leaving the sheet would hide a transfer that is
        // still running, which is how the concurrency problem started.
        .interactiveDismissDisabled(true)
    }

    /// [T-restore-browse-inplace] Open a subdirectory in place.
    private func descend(into entry: RcloneTransfer.RemoteEntry) {
        // Clear the rows before the listing starts. Without this the previous
        // directory's contents stay on screen under the new path for as long
        // as the server takes to answer, which reads as the wrong folder
        // having been opened.
        entries = []
        errorText = nil
        path = entry.path
        Task { await reload() }
    }

    /// Go up one level, or nowhere if already at the root.
    private func goUp() {
        guard !path.isEmpty else { return }
        entries = []
        errorText = nil
        let parent = (path as NSString).deletingLastPathComponent
        path = parent == "/" ? "" : parent
        Task { await reload() }
    }

    /// [T-restore-download-speed] "12.4 MB/s · about 3 min left".
    ///
    /// Static so it can be unit-checked without a view. Returns "" when there
    /// is no rate yet — the caller hides the line entirely rather than showing
    /// a zero, which would read as a stalled transfer.
    static func speedLine(bytesPerSecond: Double?, secondsRemaining: Double?) -> String {
        guard let rate = bytesPerSecond, rate > 0 else { return "" }
        var bits = [ByteCountFormatter.string(fromByteCount: Int64(rate),
                                              countStyle: .file) + "/s"]
        if let left = secondsRemaining, left.isFinite, left >= 0 {
            bits.append(remainingText(left))
        }
        return bits.joined(separator: " · ")
    }

    /// Coarse, honest time-left wording.
    ///
    /// Rounded rather than exact: the rate this is derived from is a moving
    /// average, so "about 3 min" is as precise as the estimate really is and
    /// a ticking "2:47" would imply accuracy the number does not have.
    static func remainingText(_ seconds: Double) -> String {
        if seconds < 10 { return AppLocalized("almost done") }
        if seconds < 60 {
            return String(format: AppLocalized("about %d sec left"),
                          Int((seconds / 5).rounded()) * 5)
        }
        if seconds < 3600 {
            return String(format: AppLocalized("about %d min left"),
                          max(1, Int((seconds / 60).rounded())))
        }
        return String(format: AppLocalized("about %d hr left"),
                      max(1, Int((seconds / 3600).rounded())))
    }

    private func subtitle(_ pkg: RcloneTransfer.RemotePackage) -> String {
        var bits = [ByteCountFormatter.string(fromByteCount: pkg.size, countStyle: .file)]
        if let m = pkg.modified {
            bits.append(m.formatted(date: .abbreviated, time: .shortened))
        }
        return bits.joined(separator: " · ")
    }

    private func reload() async {
        loading = true
        errorText = nil
        let r = remote
        let p = path
        // [T-restore-browse-destination-dir] Push the saved remotes into
        // rclone's config before listing. `BackupDestinationDetailView` does
        // this and this screen did not, so a remote that had been edited (or
        // added in this launch) could be listed against stale config — an
        // empty list or an auth error on a server that is perfectly fine.
        RcloneRemoteStore.syncToRclone()
        do {
            // rclone RPCs are blocking — keep them off the main actor.
            let found = try await Task.detached(priority: .userInitiated) {
                try RcloneTransfer.listDirectory(remote: r, path: p)
            }.value
            entries = found
        } catch {
            errorText = error.localizedDescription
        }
        loading = false
    }

    private func download(_ pkg: RcloneTransfer.RemotePackage) {
        downloadingKey = pkg.key
        downloadingName = pkg.displayName
        downloadFraction = 0
        speedText = ""
        cancelFlag.value = false
        errorText = nil
        progressText = AppLocalized("Starting…")
        let r = remote
        // A chunked package's display name is its backupId; give the local
        // copy the package extension either way so the restore flow (and the
        // user, if they find it) can tell what it is.
        let name = pkg.displayName.hasSuffix("." + BackupFormat.fileExtension)
            ? pkg.displayName
            : pkg.displayName + "." + BackupFormat.fileExtension
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("server-restore-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent(name)
        Task {
            do {
                try FileManager.default.createDirectory(
                    at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                // Read straight off the shared box. The old version hopped to
                // the main queue to read a captured `@State` copy, which both
                // cost a synchronous hop per poll AND read a struct that was
                // never the one Cancel wrote to.
                let flag = cancelFlag
                let cancelled: @Sendable () -> Bool = { flag.value }
                try await Task.detached(priority: .userInitiated) {
                    try RcloneTransfer.download(pkg, from: r, to: dest,
                                                isCancelled: cancelled) { p in
                        let done = ByteCountFormatter.string(fromByteCount: p.bytesSent, countStyle: .file)
                        let total = ByteCountFormatter.string(fromByteCount: p.totalBytes, countStyle: .file)
                        let rate = p.bytesPerSecond
                        let remaining = p.secondsRemaining
                        Task { @MainActor in
                            progressText = "\(done) / \(total)"
                            downloadFraction = p.fraction
                            speedText = Self.speedLine(bytesPerSecond: rate,
                                                       secondsRemaining: remaining)
                        }
                    }
                }.value
                downloadingKey = nil
                dismiss()
                onPicked(dest)
            } catch is RcloneTransfer.TransferError where cancelFlag.value {
                // User's own action — dismiss quietly rather than showing red.
                downloadingKey = nil
                Self.discardScratch(dest)
            } catch {
                downloadingKey = nil
                errorText = cancelFlag.value ? nil : error.localizedDescription
                Self.discardScratch(dest)
            }
        }
    }

    /// [T-restore-download-scratch-leak] Remove the whole per-download scratch
    /// directory when a transfer does not hand its file onward.
    ///
    /// Each attempt gets its own `server-restore-<uuid>/` under tmp/, so the
    /// DIRECTORY has to go, not just the file inside it. Cancelling deleted
    /// the partial file (the transfer layer does that) but left the directory;
    /// a failure — a dropped connection, a size mismatch — left BOTH, and a
    /// partial download of a multi-GB package is exactly the kind of file that
    /// matters. Every retry stranded another one.
    ///
    /// iOS purges tmp/ eventually, but "eventually" is not good enough when a
    /// single leftover can be gigabytes on a phone the user is trying to
    /// restore onto. Only called on the paths that do NOT call `onPicked`: a
    /// successful download hands the URL to the restore flow, which owns it
    /// from then on.
    private static func discardScratch(_ fileURL: URL) {
        try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent())
    }
}
