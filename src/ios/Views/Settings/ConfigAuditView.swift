import SwiftUI

/// Logs → Config Changes tab. Lists every config write attempt
/// recorded in `ConfigAuditLog` (rolling cap: 1000 rows). Tap an
/// applied entry to revert it via the same ConfirmationGate.
struct ConfigAuditView: View {
    @ObservedObject private var log = ConfigAuditLog.shared
    @State private var entries: [ConfigAuditEntry] = []
    @State private var usage: (count: Int, capacity: Int) = (0, 1000)
    @State private var revertingId: String?
    @State private var revertResult: AlertMessage?
    /// Entry the user just tapped Revert on; non-nil drives the
    /// confirmation dialog. We use a dialog (not the gate's sheet)
    /// because Logs is itself a sheet, and iOS won't stack two sheets
    /// in one scene. Confirmation dialogs render via UIAlertController
    /// and aren't subject to that restriction.
    @State private var revertCandidate: ConfigAuditEntry?

    var body: some View {
        List {
            Section {
                HStack {
                    Text("\(usage.count) / \(usage.capacity) used")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                    if !entries.isEmpty {
                        Button("Clear All", role: .destructive) {
                            ConfigAuditLog.shared.clearAll()
                            reload()
                        }
                        .font(.footnote)
                    }
                }
            }

            if entries.isEmpty {
                Text("No config changes recorded yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(entries) { entry in
                    auditRow(entry)
                }
            }
        }
        .listStyle(.insetGrouped)
        .onAppear { reload() }
        .onChange(of: log.revision) { _ in reload() }
        .alert(item: $revertResult) { msg in
            Alert(title: Text(msg.title), message: Text(msg.body),
                  dismissButton: .default(Text("OK")))
        }
        .confirmationDialog(
            "Revert this change?",
            isPresented: Binding(
                get: { revertCandidate != nil },
                set: { if !$0 { revertCandidate = nil } }
            ),
            titleVisibility: .visible,
            presenting: revertCandidate
        ) { entry in
            Button("Revert", role: .destructive) {
                performRevert(entry)
            }
            Button("Cancel", role: .cancel) { }
        } message: { entry in
            Text("\(entry.key)\n\(displayJSON(entry.newValueJSON)) → \(displayJSON(entry.oldValueJSON))")
        }
    }

    private func reload() {
        entries = ConfigAuditLog.shared.recent(limit: 200)
        usage = ConfigAuditLog.shared.usage()
    }

    @ViewBuilder
    private func auditRow(_ entry: ConfigAuditEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                statusBadge(entry.status)
                Text(entry.actor.rawValue)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(coarseRelativeTime(entry.at))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Text(entry.key)
                .font(.system(.subheadline, design: .monospaced))
            HStack(alignment: .top, spacing: 6) {
                Text(displayJSON(entry.oldValueJSON))
                    .lineLimit(1)
                    .foregroundStyle(.secondary)
                Image(systemName: "arrow.right")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                Text(displayJSON(entry.newValueJSON))
                    .lineLimit(1)
                    .foregroundStyle(entry.status == .applied ? .primary : .secondary)
            }
            .font(.system(.caption, design: .monospaced))

            if let caption = entry.caption, !caption.isEmpty {
                Label {
                    Text(caption)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                } icon: {
                    Image(systemName: "text.quote")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            if entry.status == .applied {
                Button {
                    revert(entry)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.uturn.backward")
                        Text("Revert")
                    }
                    .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(revertingId == entry.id)
                .padding(.top, 2)
            } else if entry.status == .reverted {
                Label("Reverted", systemImage: "arrow.uturn.backward.circle.fill")
                    .font(.caption2)
                    .foregroundStyle(.purple)
                    .padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func statusBadge(_ status: ConfigAuditStatus) -> some View {
        Text(status.rawValue.uppercased())
            .font(.system(.caption2, design: .monospaced))
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(badgeColor(status), in: Capsule())
    }

    private func badgeColor(_ status: ConfigAuditStatus) -> Color {
        switch status {
        case .applied: return .green
        case .rejected: return .gray
        case .timeout: return .orange
        case .reverted: return .purple
        }
    }

    /// Coarse, static relative time. Computed once per `reload()` —
    /// no Timer-driven updates. Buckets: just-now / N min ago /
    /// N hr ago / yesterday / absolute date.
    private func coarseRelativeTime(_ date: Date) -> String {
        let now = Date()
        let secs = max(0, Int(now.timeIntervalSince(date)))
        if secs < 60 {
            return AppLocalized("Just now")
        }
        if secs < 3600 {
            let m = secs / 60
            return AppLocalized("\(m) min ago")
        }
        if secs < 86_400 {
            let h = secs / 3600
            return AppLocalized("\(h) hr ago")
        }
        let cal = Calendar.current
        if cal.isDateInYesterday(date) {
            return AppLocalized("Yesterday")
        }
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .none
        return f.string(from: date)
    }

    private func displayJSON(_ json: String) -> String {
        if json.isEmpty { return "—" }
        // Strip outer quotes for plain strings so the row reads cleaner.
        if json.hasPrefix("\""), json.hasSuffix("\""), json.count >= 2 {
            return String(json.dropFirst().dropLast())
        }
        return json
    }

    /// Stage a revert — the `.confirmationDialog` bound to
    /// `revertCandidate` then asks the user Yes/No. We use a dialog
    /// (not the gate's ConfigConfirmSheet) because Logs is itself a
    /// sheet and iOS won't stack two sheets in one scene; the bridge
    /// is invoked with `skipConfirmation: true` since the user has
    /// already confirmed here.
    private func revert(_ entry: ConfigAuditEntry) {
        revertCandidate = entry
    }

    private func performRevert(_ entry: ConfigAuditEntry) {
        revertingId = entry.id
        Task {
            let bridgeResult = await Task.detached { () -> NSDictionary in
                ConfigOffloadBridge.auditRevert(
                    id: entry.id,
                    actorRaw: "user-revert",
                    sessionId: nil,
                    skipConfirmation: true
                )
            }.value
            await MainActor.run {
                revertingId = nil
                if (bridgeResult["ok"] as? Bool) == true {
                    revertResult = AlertMessage(title: "Reverted",
                                                body: "Setting restored.")
                } else {
                    let reason = bridgeResult["reason"] as? String ?? "Unknown error"
                    revertResult = AlertMessage(title: "Revert Failed",
                                                body: reason)
                }
                reload()
            }
        }
    }
}

private struct AlertMessage: Identifiable {
    let id = UUID()
    let title: String
    let body: String
}
