import SwiftUI

/// One backup run: what it produced, where it went, and what it logged.
struct BackupHistoryDetailView: View {
    let record: BackupHistory.Record

    @ObservedObject private var runController = BackupRunController.shared
    @State private var showDeleteConfirm = false
    /// Mirrors the live idle-timer state. Seeded in .onAppear rather than
    /// defaulted, so reopening this screen mid-run shows what is actually in
    /// effect instead of resetting the switch to off under the user.
    @State private var keepScreenAwake = false
    @Environment(\.dismiss) private var dismiss

    /// True when THIS record is the run currently in flight.
    private var isLive: Bool {
        record.status == .running && runController.runningRecordId == record.id
    }

    var body: some View {
        Form {
            if isLive { liveControlsSection }
            summarySection
            if !record.destinations.isEmpty { destinationsSection }
            logSection
        }
        .navigationTitle(record.startedAt.formatted(date: .abbreviated, time: .shortened))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { keepScreenAwake = BackupScreenAwake.isEnabled }
        .toolbar {
            // The list offers this as a swipe action, which is invisible until
            // discovered — and a record opened from a notification or a deep
            // link is reachable without ever seeing the list. Deleting from
            // the screen you are already on is the obvious path.
            //
            // Hidden while the run is live: a delete that dropped the record
            // while the upload kept running would strand a job with nothing
            // tracking it. Stop it first — this button is then right here.
            if !isLive {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        showDeleteConfirm = true
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        }
        // Confirmed, unlike the list's swipe action: a swipe is deliberate and
        // undoable-by-redoing-the-backup, but a toolbar button sits next to
        // Back and is easy to hit by accident on the way out.
        .confirmationDialog("Delete this backup record?",
                            isPresented: $showDeleteConfirm,
                            titleVisibility: .visible) {
            Button(AppLocalized("Delete Record Only"), role: .destructive) {
                removeRecord()
            }
            // [T-backup-delete-files-too] Offered only when there is something
            // to reach for: a package name to match on AND at least one
            // destination it was delivered to.
            if canDeleteRemoteFiles {
                Button(AppLocalized("Delete Record and Files"), role: .destructive) {
                    Task { await removeRecordAndFiles() }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            // Says exactly what each choice touches. Someone clearing a long
            // history needs to know the first option does NOT reach into their
            // NAS, and that the second one does and cannot be undone.
            Text(canDeleteRemoteFiles
                 ? AppLocalized("“Delete Record Only” removes this entry and leaves the backup files on your destinations. “Delete Record and Files” also deletes the package from each destination — that cannot be undone.")
                 : AppLocalized("This removes the record from this list. The backup files already saved to your destinations are not deleted."))
        }
    }

    /// Stop, shown only while this run is actually in flight.
    private var liveControlsSection: some View {
        Section {
            // ONE button. This used to offer "Stop Backup" and "Stop and
            // Delete" side by side, which made the user decide the fate of the
            // partial work at the same moment they wanted the run to end —
            // about staging they cannot see, under two near-identical red
            // labels.
            //
            // The two decisions are now sequential, which is also their real
            // order: stop, and then decide what to do with what is left. Once
            // stopped the run becomes an ordinary stopped record, and the
            // toolbar's Delete (which discards the staging with it) is right
            // there for anyone who wants it gone.
            Button(role: .destructive) {
                BackupRunController.shared.stop()
            } label: {
                Label("Stop Backup", systemImage: "stop.circle")
            }

            // Opt-in, default OFF, and per-run rather than a stored setting —
            // it exists to get THIS backup finished, and a preference that
            // silently stopped the phone locking would be worse than the
            // problem it solves. BackupRunController clears it when the run
            // ends, on every exit path.
            //
            // Offered even though the audio keep-alive already extends
            // background time: iOS can still suspend or jetsam a backgrounded
            // app under memory pressure, which a large export is exactly the
            // thing to trigger. This trades screen-on time for certainty.
            Toggle(isOn: Binding(
                get: { keepScreenAwake },
                set: { on in
                    keepScreenAwake = on
                    BackupScreenAwake.set(on)
                }
            )) {
                Label("Keep Screen Awake", systemImage: "sun.max")
            }
        } footer: {
            Text("Stopping keeps what has been backed up so far, and this run can be resumed later. Deleting the record discards it.")
        }
    }

    private var summarySection: some View {
        Section {
            LabeledContent("Status") {
                // An HStack, NOT a Label. `Label` reserves an icon column and
                // sizes it from the environment; dropped into LabeledContent's
                // value slot that column stretched, making this one row 245pt
                // tall against its siblings' 54pt — a screen-height gap under
                // "Completed", with the row separator drawn across the middle
                // of it. Measured on device (iPhone 11) before and after.
                HStack(spacing: 4) {
                    Image(systemName: BackupHistoryRow.statusIcon(record.status))
                    Text(BackupHistoryRow.statusText(record.status))
                }
                .foregroundStyle(BackupHistoryRow.statusColour(record.status))
            }
            LabeledContent("Started",
                           value: record.startedAt.formatted(date: .abbreviated, time: .shortened))
            if let d = record.duration {
                LabeledContent("Duration", value: durationText(d))
            }
            if record.totalBytes > 0 {
                LabeledContent("Size", value: ByteCountFormatter.string(
                    fromByteCount: record.totalBytes, countStyle: .file))
            }
            // `value:` takes a plain String, which does NOT route through the
            // string catalog the way a bare `Text("…")` literal does — so the
            // Yes/No here has to be localized explicitly or it stays English
            // in every locale.
            LabeledContent("Encrypted",
                           value: record.encrypted ? AppLocalized("Yes") : AppLocalized("No"))
            if let name = record.packageName {
                // A hand-built HStack, NOT `LabeledContent`.
                //
                // [T-backup-file-row-two-column] `LabeledContent` reflows to a
                // VERTICAL stack of its own accord once the value cannot sit
                // comfortably beside the label, and a package name — now
                // `iPhone-17-Pro-20260823-m0pyx0fq1dg.minisbak`, longer than
                // before the device prefix landed — is always past that
                // threshold. Asking it to wrap the value (`.fixedSize` for
                // vertical growth) made the value taller and so pushed it
                // further past, which is why the row still rendered as
                // label-above-value on device. The reflow is LabeledContent's
                // behaviour, not something the value's modifiers can override.
                //
                // So the two columns are built directly: a fixed-width label
                // that will not compress, and the value taking the rest and
                // wrapping inside it. `layoutPriority` is what stops SwiftUI
                // solving the width conflict by shrinking the label instead of
                // wrapping the value.
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text("File")
                    Spacer(minLength: 0)
                    Text(name)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .layoutPriority(1)
                }
            }
            if record.skippedFiles > 0 {
                // §3.4 tombstones — the package is deliberately incomplete, and
                // that is worth surfacing next to the size rather than only in
                // the log.
                //
                // Not orange any more, and not "too large": these files were
                // left out because the user's own size cap said so, which is
                // the setting working. Colouring it as a problem made every
                // backup with one big attachment look broken.
                //
                // Tappable when the list is available: a bare count answers
                // "how many" but never "which ones", which is the actual
                // question. Older records predate the list and stay plain.
                if record.skippedEntries.isEmpty {
                    LabeledContent("Files excluded", value: "\(record.skippedFiles) file(s)")
                } else {
                    NavigationLink {
                        BackupSkippedFilesView(record: record)
                    } label: {
                        LabeledContent("Files excluded",
                                       value: "\(record.skippedFiles) file(s)")
                    }
                }
            }
            if let e = record.errorMessage {
                Text(e).font(.footnote).foregroundStyle(.red)
            }
        } header: {
            Text("Summary")
        } footer: {
            Text("Included: \(record.categories.map(displayName).joined(separator: ", "))")
        }
    }

    /// [T-backup-delete-files-too] Whether "Delete Record and Files" makes
    /// sense: we need the package name to identify the file on each server,
    /// and at least one destination that actually received it.
    private var canDeleteRemoteFiles: Bool {
        (record.packageName?.isEmpty == false) && record.destinations.contains(where: \.succeeded)
    }

    /// Forget the record. Also discards this run's half-finished staging —
    /// deleting the record used to leave that tree on disk for good, and this
    /// is now the only way to throw a stopped run away.
    ///
    /// Matched on backupId first: the journal keeps ONE marker, and clearing
    /// it blindly would delete a different run's staging when the record being
    /// deleted is an old one.
    private func removeRecord() {
        if let marker = BackupExportJournal.interrupted(),
           marker.backupId == record.backupId, !record.backupId.isEmpty {
            BackupExportJournal.finish(backupId: marker.backupId)
        }
        BackupHistory.shared.remove(record.id)
        dismiss()
    }

    /// Delete the package from every destination that received it, then forget
    /// the record.
    ///
    /// Best-effort per destination: one unreachable server must not prevent
    /// the others from being cleaned, and the record is removed regardless —
    /// keeping it would leave the user with an entry whose files are already
    /// half-gone, which is a worse state to reason about than no entry.
    /// Failures are logged rather than surfaced, since the screen dismisses.
    private func removeRecordAndFiles() async {
        guard let name = record.packageName, !name.isEmpty else {
            removeRecord()
            return
        }
        let remotes = RcloneRemoteStore.remotes
        for outcome in record.destinations where outcome.succeeded {
            guard let remote = remotes.first(where: { $0.name == outcome.name }) else { continue }
            do {
                RcloneRemoteStore.syncToRclone()
                try await Task.detached(priority: .utility) {
                    // Reconstruct the key the same way listPackages does, so a
                    // path-style remote (SFTP absolute paths, server-root
                    // WebDAV) resolves identically.
                    let pkg = RcloneTransfer.RemotePackage(
                        key: remote.join(name), displayName: name, size: 0, modified: nil)
                    try RcloneTransfer.deletePackage(pkg, from: remote)
                }.value
            } catch {
                Self.logger.error(
                    "[Backup] deleting '\(name)' from '\(outcome.name)' failed: \(error.localizedDescription)")
            }
        }
        removeRecord()
    }

    private static let logger = AppLogger(category: "BackupHistoryDetail")

    /// "SMB · /volume1/backups" — backend and folder, whichever are known.
    ///
    /// The backend tag is upper-cased because these are initialisms (smb,
    /// sftp, s3, webdav) and read as noise in lower case. Returns nil when a
    /// record predates these fields, so old rows render exactly as before.
    private func destinationSubtitle(_ d: BackupHistory.DestinationOutcome) -> String? {
        let kind = d.kind?.trimmingCharacters(in: .whitespaces).uppercased()
        let path = d.path?.trimmingCharacters(in: .whitespaces)
        switch (kind?.isEmpty == false ? kind : nil, path?.isEmpty == false ? path : nil) {
        case let (k?, p?): return "\(k) · \(p)"
        case let (k?, nil): return k
        case let (nil, p?): return p
        case (nil, nil):   return nil
        }
    }

    private var destinationsSection: some View {
        Section {
            ForEach(record.destinations) { d in
                // [T-backup-destination-browse] Tapping a destination opens its
                // file browser, so "did this actually land?" is answerable
                // without hunting through Settings for the server. Only when
                // the saved destination still exists — a row for a server the
                // user has since removed has nowhere to go.
                if let remote = remote(for: d) {
                    NavigationLink {
                        BackupDestinationDetailView(target: .remote(remote), onChanged: {})
                    } label: {
                        destinationRow(d)
                    }
                } else {
                    destinationRow(d)
                }
            }
        } header: {
            Text("Destinations")
        }
    }

    private func destinationRow(_ d: BackupHistory.DestinationOutcome) -> some View {
        // Centre-aligned, not `.firstTextBaseline`. The status icon describes
        // the destination as a whole, and the text beside it is always at
        // least two lines (name + backend/folder, sometimes a failure detail
        // as well) — pinning the icon to the first line's baseline left it
        // sitting high against that stack instead of centred on the row.
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: d.succeeded ? "checkmark.circle.fill"
                                          : "exclamationmark.triangle.fill")
                .foregroundStyle(d.succeeded ? .green : .orange)
            VStack(alignment: .leading, spacing: 2) {
                Text(d.name)
                // [T-backup-destination-detail] The saved name alone
                // ("HomeLab") doesn't say where the file went. Show the
                // backend and the folder underneath, so a user checking
                // a months-old record can find the package by hand.
                if let where_ = destinationSubtitle(d) {
                    Text(where_)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                if let detail = d.detail {
                    Text(detail).font(.caption).foregroundStyle(.orange)
                }
            }
        }
    }

    /// The saved remote this outcome refers to, if it still exists.
    private func remote(for d: BackupHistory.DestinationOutcome) -> RcloneRemoteStore.Remote? {
        RcloneRemoteStore.remotes.first { $0.name == d.name }
    }

    private var logSection: some View {
        Section {
            if record.log.isEmpty {
                Text("No log for this run.").foregroundStyle(.secondary)
            } else {
                // [T-backup-log-newest-first] Newest first. A finished run's
                // log is long (one line per category, plus per-destination
                // transfer lines), and what a user opens this screen to see —
                // how it ended — was at the very bottom.
                ForEach(record.log.reversed()) { e in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(e.at.formatted(date: .omitted, time: .standard))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.tertiary)
                        Text(e.message)
                            .font(.caption)
                            .foregroundStyle(e.isProblem ? .red : .secondary)
                    }
                }
            }
        } header: {
            Text("Log")
        }
    }

    private func durationText(_ d: TimeInterval) -> String {
        d < 60 ? String(format: "%.0fs", d)
               : String(format: "%dm %ds", Int(d) / 60, Int(d) % 60)
    }

    private func displayName(_ raw: String) -> String {
        guard let c = BackupCategory(rawValue: raw) else { return raw }
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
}

/// A self-restarting "in progress" indicator for a history row.
///
/// [T-ios-backup-running-spinner-stops] A bare `ProgressView()` in this row
/// stopped animating after the row had been scrolled off screen and back a few
/// times, leaving a running backup looking idle. A Form/List re-realises its
/// rows on scroll rather than reusing them UIKit-style, and an indeterminate
/// `ProgressView`'s animation is owned by the platform view it wraps — when the
/// row is torn down and rebuilt, the rebuilt one can come back with its
/// animation not running. Nothing in the row's own state changes at that point,
/// so nothing ever kicks it back into motion.
///
/// [T-ios-backup-spinner-accelerates] The first fix for that drove a
/// `repeatForever` rotation from an `@State` flag, flipped false→true in
/// `onAppear` and back in `onDisappear`. It restarted reliably, but it SPED UP:
/// scroll the row off and back and it turned about twice as fast, then three
/// times, gaining a little each cycle.
///
/// The cause is that a `repeatForever` animation is attached by the state
/// change, not owned by the view. `onAppear` for the re-realised row can run
/// before `onDisappear` for the outgoing one, so each cycle attached ANOTHER
/// infinite rotation to the same `rotationEffect` while the previous one was
/// still live. Concurrent rotations on one effect sum, so the observed speed
/// was a count of how many were still running — and `onDisappear` setting the
/// flag back to false could not cancel an animation that a different instance
/// had started.
///
/// So the angle is now COMPUTED from the current time instead of animated
/// towards: `TimelineView(.animation)` re-evaluates each frame and the
/// rotation is a pure function of the timestamp. There is no animation object
/// to duplicate, cancel, or leave running, which makes both the original
/// stall and the acceleration unrepresentable rather than merely handled.
/// Deriving from an absolute clock also means a row that scrolls back into
/// view picks up at the phase it would have been at, instead of jumping back
/// to zero. The view exists only while `status == .running`, so completed and
/// failed rows still pay nothing.
private struct BackupRunningIndicator: View {
    /// One rotation per second, matching the `.linear(duration: 1)` this
    /// replaces.
    private static let secondsPerTurn: Double = 1

    var body: some View {
        // The angle is COMPUTED from the current time rather than animated
        // towards. `TimelineView(.animation)` re-evaluates the body each frame
        // and the rotation is a pure function of the timestamp, so there is no
        // animation in flight to duplicate, restart, or leave running — the
        // failure mode below is unrepresentable rather than merely avoided.
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            let turns = t / Self.secondsPerTurn
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .rotationEffect(.degrees(turns.truncatingRemainder(dividingBy: 1) * 360))
        }
        .frame(width: 28, height: 28)
        .background(BackupHistoryRow.statusColour(.running), in: Circle())
        // Matches the bare ProgressView it replaces for anyone relying on
        // the row's accessibility description.
        .accessibilityLabel(BackupHistoryRow.statusText(.running))
    }
}

/// One row in the history list, shared by the list and its styling helpers.
struct BackupHistoryRow: View {
    let record: BackupHistory.Record

    var body: some View {
        HStack(spacing: 12) {
            if record.status == .running {
                BackupRunningIndicator()
            } else {
                Image(systemName: Self.statusIcon(record.status))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(Self.statusColour(record.status),
                                in: Circle())
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(record.startedAt.formatted(date: .abbreviated, time: .shortened))
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(record.status == .failed ? .red : .secondary)
                    .lineLimit(1)
            }
        }
    }

    /// While running, the subtitle is the live progress line; afterwards it is
    /// the outcome. Same row, so a finishing backup doesn't jump around.
    private var subtitle: String {
        switch record.status {
        case .running:
            return record.log.last?.message ?? AppLocalized("Working…")
        case .failed:
            return record.errorMessage ?? AppLocalized("Failed")
        case .succeeded, .completedWithIssues:
            var bits: [String] = []
            if record.totalBytes > 0 {
                bits.append(ByteCountFormatter.string(fromByteCount: record.totalBytes,
                                                      countStyle: .file))
            }
            let ok = record.destinations.filter(\.succeeded).count
            if !record.destinations.isEmpty {
                bits.append(AppLocalized("\(ok)/\(record.destinations.count) destinations"))
            }
            if record.skippedFiles > 0 {
                bits.append(AppLocalized("\(record.skippedFiles) excluded"))
            }
            return bits.joined(separator: " · ")
        }
    }

    static func statusIcon(_ s: BackupHistory.Status) -> String {
        switch s {
        case .running: return "arrow.triangle.2.circlepath"
        case .succeeded: return "checkmark"
        case .completedWithIssues: return "exclamationmark"
        case .failed: return "xmark"
        }
    }

    static func statusColour(_ s: BackupHistory.Status) -> Color {
        switch s {
        case .running: return .blue
        case .succeeded: return .green
        case .completedWithIssues: return .orange
        case .failed: return .red
        }
    }

    static func statusText(_ s: BackupHistory.Status) -> String {
        switch s {
        case .running: return AppLocalized("In progress")
        case .succeeded: return AppLocalized("Completed")
        case .completedWithIssues: return AppLocalized("Completed with issues")
        case .failed: return AppLocalized("Failed")
        }
    }
}
