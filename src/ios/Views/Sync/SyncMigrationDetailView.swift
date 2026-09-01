import SwiftUI

/// iCloud Sync status sheet. Long-lived destination — used during the
/// v1→v2 migration to show migration progress, and after migration to
/// show ongoing sync activity (pending push, last send/fetch, totals).
///
/// The "Migration" sections are conditional on
/// `MigrationEngine.progressSummary()` returning non-nil; once status is
/// `completed` they disappear and only the day-to-day sync view remains.
struct SyncMigrationDetailView: View {
    @State private var vm: VM?
    @State private var showPauseDialog = false
    @State private var showRequestMigrationConfirm = false
    @State private var showCancelMigrationConfirm = false
    @State private var showForceDeleteV1Confirm = false
    @State private var showResetMigrationConfirm = false
    @State private var forceDeleteV1Status: String? = nil
    @State private var forceDeleteV1InProgress = false
    // iCloud Zones inventory section
    @State private var zonesList: [ZoneRow] = []
    @State private var zonesLoading = false
    @State private var zonesLoadError: String? = nil
    @State private var pendingZoneDelete: ZoneRow? = nil
    /// Second-stage confirmation. First dialog ("are you sure?") flips
    /// pendingZoneDelete → zoneSecondConfirm so the user has to
    /// acknowledge a more pointed warning before the actual API call
    /// fires. Two distinct sheets prevent muscle-memory taps from
    /// silently nuking a v2 zone that v2 sync is actively writing to.
    @State private var zoneSecondConfirm: ZoneRow? = nil
    @State private var zoneDeleteInProgress: String? = nil
    /// Persists the "v1 zone has been force-deleted" state so the
    /// confirmation row stays visible across app launches and the
    /// destructive button doesn't reappear after a successful delete.
    /// Reset by the "Delete iCloud Data" recovery flow if it ever
    /// re-creates a v1 zone.
    @AppStorage("cloudSync.v2.v1ZoneForceDeletedAt") private var v1ZoneForceDeletedAtTs: Double = 0
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("cloudSync.v2.tipsDismissed") private var tipsDismissed: Bool = false
    // [T-ios-migration-timer-sessionlist-uaf-crash] The 5s refresh cadence is
    // driven by a `.task` async loop (see refreshLoop), NOT a process-lived
    // `Timer.publish(every:5).autoconnect()` subscribed via `.onReceive`. A
    // graph-bound Combine publisher's sink is a SwiftUI attribute AttributeGraph
    // re-creates on every body transaction; a tick delivered while that attribute
    // is torn down/rebuilt releases the dangling `SubscriptionView` sink closure →
    // use-after-free (SubscriptionView.Subscriber.updateValue → MutableBox.value.setter
    // → destroy for SubscriptionView → swift_release_dealloc, CODESIGNING Invalid Page,
    // TestFlight crash1/crash2, 1.10(43)). This is the same mechanism 665c15fb fixed
    // in ContentView; that commit only converted the ContentView binding point, so
    // this sheet (and CloudSyncSettingsV2View) remained on the crashing pattern.
    private static let refreshIntervalSeconds: UInt64 = 5

    struct VM {
        var v2Enabled: Bool = false
        var isRunning: Bool = false
        var transportName: String = ""
        var pendingPush: Int = 0          // current V2 dirty count (sum)
        var pendingPushNew: Int = 0       // priority=0 (user-driven)
        var pendingPushMigration: Int = 0 // priority=1 (history backfill)
        var totalSent: Int = 0
        var totalReceived: Int = 0
        var lastSendAt: Date?
        var lastFetchAt: Date?
        var pausedUntil: Date?
        var bootDelayUntil: Date?
        var throttledUntil: Date?

        var migration: MigrationVM?
        // Opt-in migration: history left local until the user requests.
        var migrationRequested: Bool = false
        var unmigratedHistoryCount: Int = 0
        var lastFailureMessage: String?
        var isCanceledByUser: Bool = false
        var throttleLabel: String = ""
        var ratePerSecond: Double = 0
        var network: String = ""

        static var empty: VM { VM() }
    }
    struct MigrationVM {
        let phase: String
        let status: String
        let pushDone: Int
        let pushTotal: Int
        let v1Deleted: Int
        let v1DeletePending: Int
        // Removed: v1ZoneTotalAtStart / v1ZoneRemaining — the displayed
        // count was an unreliable estimate (snapshot at migration start
        // minus reclaimed, with the rolling cloud-side query layered on
        // top), and gave users a misleading sense of progress. The new
        // v1-zone control is a manual "Force Delete V1 Zone" button.
        let lastError: String?
        /// [T-icloud-migration-suspended-ui] Nothing is driving the migration
        /// until the app is relaunched. Distinct from `status`, which stays
        /// "in_progress" across a deferral.
        let isSuspended: Bool
    }

    var body: some View {
        Form {
            if !tipsDismissed, vm?.migration != nil {
                Section {
                    upgradeTipsBanner
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        // Zero out the Form Section's default cell
                        // insets so the banner sits flush against the
                        // sheet edges. Banner provides its own 16pt
                        // horizontal + 12pt vertical padding inside the
                        // rounded yellow background.
                        .listRowInsets(EdgeInsets())
                }
            }
            if vm == nil {
                Section {
                    HStack {
                        ProgressView().controlSize(.small)
                        Text("Loading…").foregroundStyle(.secondary)
                    }
                }
            }
            let vm = vm ?? .empty
            // Top status — always visible while v2 is enabled.
            Section("Status") {
                LabeledContent("iCloud Sync") {
                    Text(vm.v2Enabled ? "On" : "Off")
                        .foregroundStyle(vm.v2Enabled ? .green : .secondary)
                }
                if vm.v2Enabled {
                    HStack {
                        Text("Engine")
                        Spacer()
                        if let until = vm.pausedUntil {
                            Text(pauseRemaining(until: until)).foregroundStyle(.secondary)
                        } else if let until = vm.bootDelayUntil, until > Date() {
                            Text(bootDelayRemaining(until: until)).foregroundStyle(.secondary)
                        } else if !vm.isRunning {
                            // boot deadline passed but SyncCore.start() is still
                            // mid-flight (CKDatabase ensureZonesExist + initial
                            // sendChanges take a few seconds after the delay
                            // expires). Show 'Starting…' until isRunning flips.
                            Text("Starting…").foregroundStyle(.secondary)
                        } else if let until = vm.throttledUntil, until > Date() {
                            Text(throttleRemaining(until: until)).foregroundStyle(.orange)
                        } else {
                            Text("Running").foregroundStyle(.primary)
                        }
                        if vm.isRunning {
                            Spacer().frame(width: 12)
                            if vm.pausedUntil != nil {
                                Button {
                                    if #available(iOS 17.0, *) { SyncCore.shared.resume() }
                                    Task { await refresh() }
                                } label: {
                                    Image(systemName: "play.fill")
                                        .font(.caption2.weight(.semibold))
                                        .frame(width: 16, height: 16)
                                }
                                .buttonStyle(.borderedProminent)
                                .clipShape(Circle())
                            } else {
                                Button {
                                    showPauseDialog = true
                                } label: {
                                    Image(systemName: "pause.fill")
                                        .font(.caption2.weight(.semibold))
                                        .frame(width: 16, height: 16)
                                }
                                .buttonStyle(.bordered)
                                .clipShape(Circle())
                            }
                        }
                    }
                    if !vm.transportName.isEmpty {
                        LabeledContent("Transport", value: vm.transportName)
                    }
                    if !vm.network.isEmpty {
                        LabeledContent(AppLocalized("Network"), value: vm.network)
                    }
                    if !vm.throttleLabel.isEmpty {
                        LabeledContent(AppLocalized("Throttle"), value: vm.throttleLabel)
                    }
                    LabeledContent(AppLocalized("Rate (last 1 min)"), value: formatRate(vm.ratePerSecond))
                }
            }

            if vm.v2Enabled {
                Section {
                    if vm.pendingPush > 0 {
                        LabeledContent(AppLocalized("Pending push"), value: "\(vm.pendingPush)")
                        if vm.pendingPushNew > 0 {
                            LabeledContent {
                                Text("\(vm.pendingPushNew)").foregroundStyle(.secondary)
                            } label: {
                                Text("· New writes").foregroundStyle(.secondary).font(.callout)
                            }
                        }
                        if vm.pendingPushMigration > 0 {
                            LabeledContent {
                                Text("\(vm.pendingPushMigration)").foregroundStyle(.secondary)
                            } label: {
                                Text("· Migration backlog").foregroundStyle(.secondary).font(.callout)
                            }
                        }
                    } else {
                        LabeledContent(AppLocalized("Pending push")) {
                            Text("Up to date").foregroundStyle(.secondary)
                        }
                    }
                    LabeledContent(AppLocalized("Sent this session"), value: "\(vm.totalSent)")
                    LabeledContent(AppLocalized("Received this session"), value: "\(vm.totalReceived)")
                    LabeledContent(AppLocalized("Last send"), value: relative(vm.lastSendAt))
                    LabeledContent(AppLocalized("Last fetch"), value: relative(vm.lastFetchAt))
                } header: {
                    Text("Sync Activity")
                }
            }

            // Opt-in section: V2 is enabled but the user has never
            // tapped "Request Migration", and there is local pre-v2
            // history sitting unsynced. Without this entry point, an
            // upgrading user has NO way to backfill their old chats
            // into V2 from the UI — progressSummary() short-circuits on
            // !isMigrationRequested, the Recovery section below requires
            // a prior failure/cancel, and CloudSyncSettingsV2View
            // doesn't surface migration directly. Surface a single
            // "Migrate Now" prompt with the eligible count so the user
            // can decide.
            if vm.migration == nil, vm.v2Enabled,
               !vm.migrationRequested,
               vm.lastFailureMessage == nil, !vm.isCanceledByUser,
               vm.unmigratedHistoryCount > 0 {
                Section {
                    LabeledContent {
                        Text("\(vm.unmigratedHistoryCount)")
                            .foregroundStyle(.secondary)
                    } label: {
                        Text("Eligible records")
                    }
                    Button {
                        showRequestMigrationConfirm = true
                    } label: {
                        Text("Migrate History to iCloud")
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                } header: {
                    Text("Historical Data")
                } footer: {
                    Text("Local chats and files created before this build are not pushed to iCloud automatically. Tap above to backfill them — this only needs to run once per device. Already-pushed records are skipped, so it's safe to re-run.")
                        .font(.caption)
                }
            }

            // Recovery section: appears when migration is not running
            // and either (a) the last attempt failed, or (b) the user
            // canceled and there's still backlog to migrate. Lets them
            // resume.
            if vm.migration == nil, vm.v2Enabled,
               vm.lastFailureMessage != nil || vm.isCanceledByUser {
                Section {
                    if let err = vm.lastFailureMessage {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Last attempt failed")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.red)
                            Text(err)
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                        }
                    } else if vm.isCanceledByUser {
                        Text("Migration canceled. Tap to resume backfilling local history.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Button {
                        showRequestMigrationConfirm = true
                    } label: {
                        Text(vm.lastFailureMessage != nil ? "Retry Migration" : "Resume Migration")
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                } header: {
                    Text("Migration")
                }
            }

            if let m = vm.migration {
                Section {
                    LabeledContent("Phase", value: m.phase)
                    LabeledContent("Status", value: m.status)
                    progressRow(
                        title: AppLocalized("Records pushed"),
                        done: m.pushDone, total: m.pushTotal
                    )
                    // [T-icloud-migration-suspended-ui] A deferred phase leaves
                    // `status` at "in_progress" even though nothing is running,
                    // so the sheet used to show an active status over a frozen
                    // counter plus an ETA computed from a rate of zero. Say
                    // plainly that it is paused and name the one action that
                    // resumes it, and drop the meaningless estimate.
                    if m.isSuspended {
                        HStack(spacing: 8) {
                            Image(systemName: "pause.circle.fill")
                                .foregroundStyle(.orange)
                            Text("Paused — reopen Minis to continue")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        LabeledContent(AppLocalized("Estimated time"), value: estimateETA(remaining: max(0, m.pushTotal - m.pushDone), rps: vm.ratePerSecond))
                    }
                    Button(role: .destructive) {
                        showCancelMigrationConfirm = true
                    } label: {
                        Text("Cancel Migration")
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                    // [T-icloud-migration-reset] Escape hatch for a migration
                    // that no retry can un-stick (OpenMinis#154). Distinct from
                    // "Force Delete V1 Zone" below: this only discards LOCAL
                    // progress, nothing on the server.
                    Button {
                        showResetMigrationConfirm = true
                    } label: {
                        Text("Reset Migration Progress")
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                } header: {
                    Text("Migration")
                } footer: {
                    Text("Backfilling pre-v2 history into v2's shared zone. Already-uploaded records stay on iCloud if you cancel.\n\nIf migration is stuck and retrying doesn't help, Reset Migration Progress clears the local progress so it can start over. Your iCloud data is not deleted.")
                        .font(.caption)
                }

                Section {
                    LabeledContent(AppLocalized("Reclaimed (v1 records deleted)"), value: "\(m.v1Deleted)")
                    LabeledContent(AppLocalized("Pending delete"), value: "\(m.v1DeletePending)")
                    if v1ZoneForceDeletedAtTs > 0 {
                        // Permanent confirmation row — once the user has
                        // force-deleted, the destructive action is no
                        // longer relevant on this device, so we hide
                        // the button and show a green confirmation
                        // instead. Survives relaunches via @AppStorage.
                        HStack(spacing: 8) {
                            Image(systemName: "checkmark.seal.fill")
                                .foregroundStyle(.green)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("V1 zone deleted")
                                    .foregroundStyle(.primary)
                                Text(AppLocalized("Deleted \(deletedAtRelativeString())"))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    } else {
                        Button(role: .destructive) {
                            showForceDeleteV1Confirm = true
                        } label: {
                            Group {
                                if forceDeleteV1InProgress {
                                    HStack(spacing: 8) {
                                        ProgressView().controlSize(.small)
                                        Text("Deleting v1 zone…")
                                    }
                                } else {
                                    Text("Force Delete V1 Zone of This Device")
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .center)
                        }
                        .disabled(forceDeleteV1InProgress)
                        if let status = forceDeleteV1Status {
                            Text(status)
                                .font(.caption)
                                .foregroundStyle(status.hasPrefix("⚠️") ? .red : .secondary)
                        }
                    }
                } header: {
                    Text("Reclaim v1 space")
                } footer: {
                    Text("v1 records are deleted in batches of 100 as their v2 counterparts are confirmed saved, so iCloud usage doesn't double-up during migration.\n\n⚠️ Force-deleting the v1 zone permanently removes ALL legacy records from iCloud and CANNOT be undone. The cloud copy is gone for good. Only proceed if you've confirmed v2 push is at 100% AND every other device of yours has also finished migrating — peers that haven't yet may lose access to legacy data they hadn't received locally.")
                        .font(.caption)
                }

                if let err = m.lastError {
                    Section("Last error") {
                        Text(err).font(.caption.monospaced()).foregroundStyle(.red)
                    }
                }
            }

            // iCloud Zones inventory. Lists every CKRecordZone the user's
            // private database holds for this app, classified by kind
            // (v2 / v1 / system). Lets the user reclaim space by
            // deleting whole zones they no longer need.
            iCloudZonesSection
        }
        .confirmationDialog("Request Migration", isPresented: $showRequestMigrationConfirm, titleVisibility: .visible) {
            Button("Start Migration") {
                if #available(iOS 17.0, *) {
                    MigrationEngine.shared.requestMigration()
                    Task {
                        await MigrationEngine.shared.runIfNeeded()
                        await refresh()
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            let count = vm?.unmigratedHistoryCount ?? 0
            let etaH = max(1, count / (50 * 60))   // 50 records/min ballpark, hours
            Text(AppLocalized("About \(count) local records will upload to iCloud. Estimated time ~\(etaH) hr depending on iCloud throttling. You can pause or cancel any time."))
        }
        .confirmationDialog("Cancel Migration?", isPresented: $showCancelMigrationConfirm, titleVisibility: .visible) {
            Button("Cancel Migration", role: .destructive) {
                if #available(iOS 17.0, *) {
                    Task {
                        await MigrationEngine.shared.cancelMigration()
                        await refresh()
                    }
                }
            }
            Button("Keep Migrating", role: .cancel) {}
        } message: {
            Text("Already-uploaded records stay on iCloud. Pending history backfill stops; you can request again later.")
        }
        .confirmationDialog("Reset Migration Progress?", isPresented: $showResetMigrationConfirm, titleVisibility: .visible) {
            Button("Reset Progress", role: .destructive) {
                if #available(iOS 17.0, *) {
                    Task {
                        await MigrationEngine.shared.resetMigrationProgress()
                        await refresh()
                    }
                }
            }
            Button("Keep Current Progress", role: .cancel) {}
        } message: {
            Text("Clears this device's migration progress so it can start over — the phase, the pushed-record ledger and the backfill position.\n\nYour iCloud data is NOT deleted and the v1 zone is untouched. Records already uploaded may be re-sent, which iCloud absorbs harmlessly. Use this when migration is stuck and retrying doesn't help.")
        }
        .confirmationDialog(
            "Force Delete V1 Zone?",
            isPresented: $showForceDeleteV1Confirm,
            titleVisibility: .visible
        ) {
            Button("Delete V1 Zone", role: .destructive) {
                if #available(iOS 17.0, *) {
                    forceDeleteV1InProgress = true
                    forceDeleteV1Status = nil
                    Task {
                        do {
                            let myDeviceId = DeviceIdentity.deviceId
                            try await V1FetcherShim.deleteOwnZone(zoneName: "device-\(myDeviceId)")
                            forceDeleteV1Status = nil
                            v1ZoneForceDeletedAtTs = Date().timeIntervalSince1970
                        } catch {
                            forceDeleteV1Status = "⚠️ Failed: \(error.localizedDescription)"
                        }
                        forceDeleteV1InProgress = false
                        await refresh()
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This permanently deletes ALL legacy v1 records from iCloud. The cloud copy is gone for good — there is no undo. Other devices that haven't finished migrating yet will lose access to any legacy data they hadn't already pulled locally.\n\nOnly proceed if v2 push is at 100% AND every device of yours has finished migrating.")
        }
        .sheet(isPresented: $showPauseDialog) {
            PauseSyncSheet { hours in
                if #available(iOS 17.0, *) {
                    SyncCore.shared.pause(for: TimeInterval(hours * 3600))
                }
                showPauseDialog = false
                Task { await refresh() }
            } onCancel: {
                showPauseDialog = false
            } pauseLabel: { pauseLabel(hours: $0) }
        }
        .safeAreaInset(edge: .bottom) {
            // Bottom breathing room so the trailing section footer doesn't
            // butt up against the sheet edge / show-through content.
            Color.clear.frame(height: 24)
        }
        .navigationTitle("iCloud Sync")
#if !os(macOS)
        .navigationBarTitleDisplayMode(.inline)
#endif
        // [T-ios-migration-timer-sessionlist-uaf-crash] `.task` async refresh loop
        // replaces the old `.task { await refresh() }` + `.onReceive(timer)` pair.
        // SwiftUI owns this Task by view identity and cancels it deterministically on
        // teardown, so there is no graph-bound Combine sink to be released
        // mid-transaction (the crash that pattern caused — see refreshIntervalSeconds).
        .task { await refreshLoop() }
        .onAppear {
            if #available(iOS 17.0, *) { SyncCore.shared.userOnSyncSheet = true }
            updateIdleTimer()
        }
        .onDisappear {
            if #available(iOS 17.0, *) { SyncCore.shared.userOnSyncSheet = false }
            // Always release the idle-timer hold when the sheet goes away.
            setIdleTimerDisabled(false)
        }
        .onChange(of: scenePhase) { phase in
            // App backgrounded → release the hold so the OS can sleep
            // normally. Re-applies on return based on current activity.
            if phase != .active {
                setIdleTimerDisabled(false)
            } else {
                updateIdleTimer()
            }
        }
        .onChange(of: vm?.pendingPush ?? 0) { _ in
            updateIdleTimer()
        }
        .task { await loadZonesIfNeeded() }
        .confirmationDialog(
            zoneDeleteDialogTitle,
            isPresented: Binding(
                get: { pendingZoneDelete != nil },
                set: { if !$0 { pendingZoneDelete = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingZoneDelete
        ) { row in
            // First confirmation — explains what's about to happen. Tap
            // "Continue" to advance to the second, stronger dialog.
            Button("Continue", role: .destructive) {
                let captured = row
                pendingZoneDelete = nil
                // Defer to next runloop so the first sheet is fully
                // dismissed before the second one appears (SwiftUI
                // refuses to present overlapping confirmationDialogs).
                DispatchQueue.main.async {
                    zoneSecondConfirm = captured
                }
            }
            Button("Cancel", role: .cancel) { pendingZoneDelete = nil }
        } message: { row in
            Text(zoneDeleteMessage(for: row))
        }
        .confirmationDialog(
            zoneSecondConfirmTitle,
            isPresented: Binding(
                get: { zoneSecondConfirm != nil },
                set: { if !$0 { zoneSecondConfirm = nil } }
            ),
            titleVisibility: .visible,
            presenting: zoneSecondConfirm
        ) { row in
            // Final hard-confirm. Spell out what cannot be undone.
            Button("Permanently Delete", role: .destructive) {
                zoneSecondConfirm = nil
                deletePendingZone(row)
            }
            Button("Cancel", role: .cancel) { zoneSecondConfirm = nil }
        } message: { row in
            Text(zoneSecondConfirmMessage(for: row))
        }
    }

    // MARK: - iCloud Zones inventory

    /// Plain-Swift snapshot of one CKRecordZone for the UI. Mirrors
    /// MigrationEngine.ZoneInfo but lives here so the section doesn't
    /// have to drag the engine type through SwiftUI conformances.
    struct ZoneRow: Identifiable, Hashable {
        let name: String
        let kind: Kind
        let isOwn: Bool
        var id: String { name }
        enum Kind: String { case v2, v1, system, other }
    }

    @ViewBuilder
    private var iCloudZonesSection: some View {
        Section {
            if zonesLoading && zonesList.isEmpty {
                HStack {
                    ProgressView().controlSize(.small)
                    Text("Loading zones…").foregroundStyle(.secondary)
                }
            } else if let err = zonesLoadError {
                Text(err).font(.caption).foregroundStyle(.red)
            } else if zonesList.isEmpty {
                Text("No zones found.").foregroundStyle(.secondary)
            } else {
                ForEach(zonesList) { row in
                    zoneRowView(row)
                }
            }
            Button {
                Task { await refreshZones(force: true) }
            } label: {
                HStack(spacing: 6) {
                    if zonesLoading {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                    Text("Refresh")
                }
                .frame(maxWidth: .infinity, alignment: .center)
            }
            .disabled(zonesLoading)
        } header: {
            Text("iCloud Zones")
        } footer: {
            Text("Every zone Minis has created in your iCloud private database. **V2** holds the current sync engine's data; **V1** holds legacy per-device backups from older builds. Deleting a zone is permanent and removes everything inside (records + assets). Use this to reclaim space after migration completes.")
                .font(.caption)
        }
    }

    @ViewBuilder
    private func zoneRowView(_ row: ZoneRow) -> some View {
        let inFlight = zoneDeleteInProgress == row.name
        HStack(spacing: 10) {
            // Color-coded badge by kind.
            Text(badgeText(for: row.kind))
                .font(.caption2.weight(.semibold))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(badgeColor(for: row.kind).opacity(0.18))
                .foregroundStyle(badgeColor(for: row.kind))
                .clipShape(RoundedRectangle(cornerRadius: 4))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(row.name)
                        .font(.system(.subheadline, design: .monospaced))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if row.isOwn {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.caption2)
                            .foregroundStyle(.blue)
                    }
                }
                Text(zoneDescription(for: row))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            if inFlight {
                ProgressView().controlSize(.small)
            } else {
                Button(role: .destructive) {
                    pendingZoneDelete = row
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                // The default _defaultZone is system-managed and can't
                // safely be deleted; gray it out.
                .disabled(row.kind == .system)
            }
        }
    }

    /// Human-readable explanation of what each zone stores. Branches on
    /// well-known zone names first (V2 zones have stable, code-defined
    /// names) and falls back to kind for V1 / other.
    private func zoneDescription(for row: ZoneRow) -> String {
        switch row.name {
        case "minis-shared":
            return AppLocalized("V2 sync · chat sessions, messages, compact markers, attachments, skills. Shared by all your devices.")
        case "minis-devices":
            return AppLocalized("V2 sync · per-device presence records so each device knows the others online.")
        case "minis-secrets":
            return AppLocalized("V2 sync · environment variables (API keys, etc.) used by your AI tools.")
        default:
            break
        }
        switch row.kind {
        case .v1:
            if row.isOwn {
                return AppLocalized("V1 sync (legacy) · this device's pre-v2 chat backup. Safe to delete after V2 migration completes.")
            }
            return AppLocalized("V1 sync (legacy) · another device's pre-v2 backup. Safe to delete if that device has migrated to V2.")
        case .system:
            return AppLocalized("CloudKit built-in zone. Not used by Minis.")
        case .other:
            return AppLocalized("Legacy or unknown zone. Inspect before deleting.")
        case .v2:
            return AppLocalized("V2 sync zone.")
        }
    }

    private func badgeText(for kind: ZoneRow.Kind) -> String {
        switch kind {
        case .v2:     return "V2"
        case .v1:     return "V1"
        case .system: return "SYS"
        case .other:  return "?"
        }
    }
    private func badgeColor(for kind: ZoneRow.Kind) -> Color {
        switch kind {
        case .v2:     return .green
        case .v1:     return .orange
        case .system: return .gray
        case .other:  return .secondary
        }
    }

    private var zoneDeleteDialogTitle: String {
        guard let row = pendingZoneDelete else { return "" }
        return AppLocalized("Delete zone \"\(row.name)\"?")
    }

    private func zoneDeleteMessage(for row: ZoneRow) -> String {
        var lines: [String] = []
        lines.append(AppLocalized("This deletes every record + asset inside the zone. It cannot be undone."))
        if row.isOwn {
            lines.append(AppLocalized("⚠️ This is THIS device's own zone."))
        }
        if row.kind == .v2 {
            lines.append(AppLocalized("⚠️ This is a V2 sync zone. Deleting it will stop iCloud sync for the affected category and other devices will lose access to its data."))
        }
        return lines.joined(separator: "\n\n")
    }

    /// Second-stage hard-confirm title. Mirrors the destructive verb
    /// of the final button so the user sees the same wording twice.
    private var zoneSecondConfirmTitle: String {
        guard let row = zoneSecondConfirm else { return "" }
        return AppLocalized("Permanently delete \"\(row.name)\"?")
    }

    /// Second-stage hard-confirm message. Spells out the irreversible
    /// nature in plain language + explicitly warns about live-sync
    /// disruption for V2 zones (the case most likely to surprise the
    /// user — V1 is legacy + likely already abandoned).
    private func zoneSecondConfirmMessage(for row: ZoneRow) -> String {
        var lines: [String] = []
        lines.append(AppLocalized("Last chance. Permanently deleting this zone removes every record and asset Apple holds for it. There is no recovery — even Apple cannot restore this data."))
        if row.kind == .v2 {
            lines.append(AppLocalized("⚠️ Live sync warning: this is a V2 zone that this device may currently be writing to. Any in-flight backup, new chat data, or attachment upload could fail or appear corrupted on this device and its peers until the next full sync rebuilds state."))
        }
        if row.isOwn {
            lines.append(AppLocalized("This device created this zone. Other devices of yours that haven't fetched everything yet will lose access to whatever they haven't pulled locally."))
        }
        return lines.joined(separator: "\n\n")
    }

    private func loadZonesIfNeeded() async {
        if zonesList.isEmpty { await refreshZones(force: false) }
    }

    private func refreshZones(force: Bool) async {
        guard #available(iOS 17.0, *) else { return }
        guard !zonesLoading else { return }
        zonesLoading = true
        zonesLoadError = nil
        defer { zonesLoading = false }
        do {
            let zones = try await V1FetcherShim.listAllZones()
            zonesList = zones
                // _defaultZone is CloudKit's built-in zone present in every
                // private database. Minis never writes to it and CK refuses
                // to delete it, so showing it just adds noise + a disabled
                // trash button. Hide it.
                .filter { $0.name != "_defaultZone" }
                .map { ZoneRow(
                    name: $0.name,
                    kind: ZoneRow.Kind(rawValue: $0.kind.rawValue) ?? .other,
                    isOwn: $0.isOwn
                ) }
            .sorted { (a, b) in
                // v2 first, then v1, then system, then other; secondary
                // sort by name so output is stable.
                let order: (ZoneRow.Kind) -> Int = { k in
                    switch k {
                    case .v2: return 0
                    case .v1: return 1
                    case .other: return 2
                    case .system: return 3
                    }
                }
                if order(a.kind) != order(b.kind) {
                    return order(a.kind) < order(b.kind)
                }
                return a.name < b.name
            }
        } catch {
            zonesLoadError = error.localizedDescription
        }
    }

    private func deletePendingZone(_ row: ZoneRow) {
        guard #available(iOS 17.0, *) else { return }
        pendingZoneDelete = nil
        zoneDeleteInProgress = row.name
        Task {
            do {
                try await V1FetcherShim.deleteOwnZone(zoneName: row.name)
            } catch {
                zonesLoadError = error.localizedDescription
            }
            zoneDeleteInProgress = nil
            await refreshZones(force: true)
        }
    }

    /// Decide whether the screen should stay awake based on whether sync
    /// has work to do. While the sheet is the active foreground context
    /// AND there are pending pushes, hold the idle timer. Otherwise let
    /// the system sleep normally.
    private func updateIdleTimer() {
        let hasWork = (vm?.pendingPush ?? 0) > 0
        setIdleTimerDisabled(hasWork && scenePhase == .active)
    }

    private func setIdleTimerDisabled(_ disabled: Bool) {
#if canImport(UIKit) && !os(macOS)
        UIApplication.shared.isIdleTimerDisabled = disabled
#endif
    }

    @ViewBuilder
    private var upgradeTipsBanner: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lightbulb.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 4) {
                Text("iCloud Sync upgraded to v2")
                    .font(.caption.weight(.semibold))
                Text("To improve sync experience and reduce iCloud storage, we've upgraded the sync engine. Historical data is being migrated automatically — this may consume some extra battery or cellular data. You can cancel migration any time below.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Button {
                tipsDismissed = true
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 20, height: 20)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color.yellow.opacity(0.18))
        )
    }

    @ViewBuilder
    private func actionRowLabel(
        systemImage: String,
        tint: Color,
        title: LocalizedStringKey,
        titleColor: Color? = nil
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 20, height: 20)
                .background(tint, in: Circle())
            Text(title)
                .foregroundStyle(titleColor ?? Color.primary)
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }

    private func progressRow(title: String, done: Int, total: Int) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title)
                Spacer()
                if total > 0 {
                    let pct = Int(Double(min(done, total)) * 100 / Double(total))
                    Text("\(done) / \(total) (\(pct)%)").foregroundStyle(.secondary)
                } else {
                    Text("\(done)").foregroundStyle(.secondary)
                }
            }
            if total > 0 {
                ProgressView(value: Double(min(done, total)), total: Double(total))
            }
        }
        .padding(.vertical, 4)
    }

    private func pauseLabel(hours: Int) -> String {
        if hours == 1 { return AppLocalized("1 hour") }
        if hours == 24 { return AppLocalized("1 day") }
        return AppLocalized("\(hours) hours")
    }

    private func throttleRemaining(until: Date) -> String {
        let secs = max(0, Int(until.timeIntervalSinceNow))
        if secs < 60 { return AppLocalized("Throttled · \(secs)s") }
        let mins = (secs + 30) / 60
        return AppLocalized("Throttled · \(mins)m")
    }

    /// ETA from current 1-min average rate. Returns "—" when rate is
    /// effectively zero (heavy throttle window). Formats: "<1 min",
    /// "12 min", "3 hr", "2 day".
    private func estimateETA(remaining: Int, rps: Double) -> String {
        guard remaining > 0 else { return AppLocalized("Done") }
        guard rps > 0.05 else { return "—" }
        let secs = Int(Double(remaining) / rps)
        if secs < 60 { return AppLocalized("<1 min") }
        let mins = secs / 60
        if mins < 60 { return AppLocalized("\(mins) min") }
        let hours = mins / 60
        if hours < 24 { return AppLocalized("\(hours) hr") }
        let days = hours / 24
        return AppLocalized("\(days) day")
    }

    private func formatRate(_ rps: Double) -> String {
        if rps < 0.05 { return AppLocalized("0 rec/s") }
        if rps < 10 { return String(format: "%.1f rec/s", rps) }
        return String(format: "%.0f rec/s", rps)
    }

    private func bootDelayRemaining(until: Date) -> String {
        let secs = max(0, Int(until.timeIntervalSinceNow))
        if secs < 60 { return AppLocalized("Starting in \(secs) sec") }
        let mins = (secs + 30) / 60
        return AppLocalized("Starting in \(mins) min")
    }

    private func pauseRemaining(until: Date) -> String {
        let secs = Int(until.timeIntervalSinceNow)
        if secs < 60 { return AppLocalized("Resume in <1 min") }
        let mins = secs / 60
        if mins < 60 { return AppLocalized("Resume in \(mins) min") }
        let hours = (mins + 30) / 60
        return AppLocalized("Resume in \(hours) hr")
    }

    private func relative(_ date: Date?) -> String {
        guard let date else { return AppLocalized("Never") }
        let secs = Int(Date().timeIntervalSince(date))
        if secs < 60 { return AppLocalized("Just now") }
        let mins = secs / 60
        if mins < 60 { return AppLocalized("\(mins) min ago") }
        let hours = mins / 60
        if hours < 24 { return AppLocalized("\(hours) hr ago") }
        let days = hours / 24
        return AppLocalized("\(days) day ago")
    }

    /// Renders the persisted v1-zone-deleted timestamp as a relative
    /// string (e.g. "2 min ago"). Falls back to "just now" when the
    /// timestamp is in the future or missing.
    private func deletedAtRelativeString() -> String {
        guard v1ZoneForceDeletedAtTs > 0 else { return AppLocalized("Just now") }
        return relative(Date(timeIntervalSince1970: v1ZoneForceDeletedAtTs))
    }

    /// [T-ios-migration-timer-sessionlist-uaf-crash] Self-cancelling 5s refresh
    /// loop driven by `.task`, replacing the graph-bound `Timer.publish().autoconnect()`
    /// + `.onReceive` that AttributeGraph could tear down mid-transaction (UAF).
    /// SwiftUI cancels this Task on the sheet's teardown, so no dangling subscription
    /// survives. Refreshes once immediately (mirroring the old `.task { await refresh() }`)
    /// then every 5s. Skips the refresh while backgrounded — the sheet has nothing to
    /// display then, and it resumes on the next tick after return to foreground.
    @MainActor
    private func refreshLoop() async {
        await refresh()
        while !Task.isCancelled {
            do {
                try await Task.sleep(nanoseconds: Self.refreshIntervalSeconds * 1_000_000_000)
            } catch {
                return  // cancelled during sleep
            }
            let backgrounded: Bool
            if #available(iOS 17.0, *) {
                backgrounded = SyncCore.shared.isAppInBackground
            } else {
                backgrounded = false
            }
            if !backgrounded {
                await refresh()
            }
        }
    }

    @MainActor
    private func refresh() async {
        guard #available(iOS 17.0, *) else { return }
        var next = VM()
        next.v2Enabled = SyncV2Bootstrap.isEnabled
        next.isRunning = SyncCore.shared.isRunning
        next.transportName = SyncCore.shared.transports.first?.name ?? ""
        next.totalSent = SyncCore.shared.totalSent
        next.totalReceived = SyncCore.shared.totalReceived
        next.lastSendAt = SyncCore.shared.lastSendAt
        next.lastFetchAt = SyncCore.shared.lastFetchAt
        next.pausedUntil = SyncCore.shared.pausedUntil
        next.bootDelayUntil = SyncCore.shared.bootDelayUntil
        next.throttledUntil = SyncCore.shared.nextEarliestSendAt
        next.migrationRequested = SyncV2Bootstrap.isMigrationRequested
        next.unmigratedHistoryCount = await MigrationEngine.shared.unmigratedHistoryCount()
        next.lastFailureMessage = await MigrationEngine.shared.lastErrorIfFailed()
        next.isCanceledByUser = await MigrationEngine.shared.isCanceledByUser()
        next.throttleLabel = SyncCore.shared.throttleLabel
        next.ratePerSecond = SyncCore.shared.recentRatePerSecond
        switch SyncCore.shared.networkClass {
        case .wifi: next.network = "Wi-Fi"
        case .cellular: next.network = "Cellular"
        case .other: next.network = "Other"
        }
        let counts = await ChatStore.shared.countDirtyRecords()
        next.pendingPush = counts.byType.filter { $0.key.hasSuffix("V2") }.values.reduce(0, +)
        let split = await ChatStore.shared.countDirtyV2ByPriority()
        next.pendingPushNew = split.newCount
        next.pendingPushMigration = split.migrationCount
        if let s = await MigrationEngine.shared.progressSummary() {
            next.migration = MigrationVM(
                phase: String(describing: s.phase),
                status: s.status.rawValue,
                pushDone: s.pushDone,
                pushTotal: s.pushTotal,
                v1Deleted: s.v1Deleted,
                v1DeletePending: s.v1DeletePending,
                lastError: s.lastError,
                isSuspended: s.isSuspended
            )
        }
        vm = next
    }
}

private struct PauseSyncSheet: View {
    let onPick: (Int) -> Void
    let onCancel: () -> Void
    let pauseLabel: (Int) -> String
    @Environment(\.dismiss) private var dismiss

    private let hoursOptions = [1, 3, 6, 12, 24]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(hoursOptions, id: \.self) { hours in
                        Button {
                            onPick(hours)
                        } label: {
                            HStack {
                                Text(pauseLabel(hours))
                                    .foregroundStyle(.primary)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                    }
                } footer: {
                    Text("Pending changes are preserved locally and pushed once the pause ends.")
                }
            }
            .navigationTitle("Pause iCloud Sync")
#if !os(macOS)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}
