import Foundation

private let logger = AppLogger(category: "Backup")

/// Live state of the upload that follows a finished export.
///
/// Building the package reports progress in detail; sending it did not report
/// anything at all. For a 236 MB package on a slow link that is minutes of a
/// screen saying only "Copying to destinations…", with no way to tell a slow
/// upload from a stalled one — the same problem the export log had, in the
/// phase that is actually the longest.
@MainActor
final class BackupTransferStatus: ObservableObject {
    static let shared = BackupTransferStatus()

    struct Destination: Identifiable {
        let id = UUID()
        let name: String
        var bytesSent: Int64 = 0
        var totalBytes: Int64 = 0
        var startedAt = Date()
        var finishedAt: Date?
        var error: String?

        var fraction: Double {
            totalBytes > 0 ? min(1, Double(bytesSent) / Double(totalBytes)) : 0
        }
        var elapsed: TimeInterval {
            (finishedAt ?? Date()).timeIntervalSince(startedAt)
        }
        /// Bytes per second, averaged over the whole transfer.
        ///
        /// Deliberately an average rather than an instantaneous rate: rclone
        /// reports in bursts, and an instantaneous figure swings so wildly it
        /// reads as broken. The average settles quickly and is what makes the
        /// remaining-time estimate stable.
        var bytesPerSecond: Double {
            elapsed > 0.5 ? Double(bytesSent) / elapsed : 0
        }
        var remaining: TimeInterval? {
            guard finishedAt == nil, totalBytes > 0, bytesPerSecond > 0 else { return nil }
            return Double(totalBytes - bytesSent) / bytesPerSecond
        }
    }

    /// One entry per destination, in the order they are attempted.
    @Published private(set) var destinations: [Destination] = []
    /// Set once every destination is done and the local package has been
    /// removed, so the UI can say the phone is clean rather than leaving the
    /// user to wonder whether a copy is still sitting there.
    @Published private(set) var cleanupNote: String?
    @Published private(set) var isTransferring = false
    /// Bumped once a second while a transfer runs, purely to redraw.
    ///
    /// Elapsed time, rate and the remaining estimate all move with the CLOCK,
    /// not only with the byte counter. Without a tick they would freeze
    /// whenever rclone went quiet — exactly when a user is staring at the
    /// screen wondering whether the upload has died.
    @Published private(set) var tick = 0

    private var ticker: Timer?

    /// Where transfer events go in addition to the live rows.
    ///
    /// The on-screen rows vanish when the run ends, so without this the record
    /// jumped straight from "Export complete" to a finished backup with no
    /// trace of the upload — which is where most of the wall-clock time goes,
    /// and the part most likely to have been slow or to have failed.
    ///
    /// Set by the run; a nil sink just means nobody is recording.
    var logSink: ((String, Bool) -> Void)?

    /// Progress lines are emitted at most this often. The rows update every
    /// second, but a log line per second per destination would bury the rest
    /// of the record.
    private static let logInterval: TimeInterval = 5

    private var lastLogged: [String: Date] = [:]

    private init() {}

    func begin(names: [String], totalBytes: Int64) {
        destinations = names.map { .init(name: $0, totalBytes: totalBytes) }
        cleanupNote = nil
        isTransferring = !names.isEmpty
        ticker?.invalidate()
        guard isTransferring else { return }
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick &+= 1 }
        }
    }

    func update(name: String, bytesSent: Int64) {
        guard let i = destinations.firstIndex(where: { $0.name == name }) else { return }
        destinations[i].bytesSent = bytesSent

        // Throttled copy into the record, marked transient so each line
        // replaces the previous one instead of stacking one row every 5s.
        let now = Date()
        if let last = lastLogged[name], now.timeIntervalSince(last) < Self.logInterval {
            return
        }
        lastLogged[name] = now
        let d = destinations[i]
        let sent = ByteCountFormatter.string(fromByteCount: d.bytesSent, countStyle: .file)
        let total = ByteCountFormatter.string(fromByteCount: d.totalBytes, countStyle: .file)
        if let remaining = d.remaining {
            logSink?(AppLocalized("Uploading to \(name) — \(sent) of \(total) · \(Self.rateText(d.bytesPerSecond)) · about \(Self.durationText(remaining)) left"), true)
        } else {
            logSink?(AppLocalized("Uploading to \(name) — \(sent) of \(total)"), true)
        }
    }

    /// Mark a destination finished. `startedAt` is reset for the NEXT one so
    /// its rate is not skewed by time spent on this one — uploads run in
    /// sequence, not in parallel.
    func finish(name: String, error: String?) {
        guard let i = destinations.firstIndex(where: { $0.name == name }) else { return }
        destinations[i].finishedAt = Date()
        destinations[i].error = error
        if error == nil { destinations[i].bytesSent = destinations[i].totalBytes }
        if i + 1 < destinations.count { destinations[i + 1].startedAt = Date() }

        // Permanent (non-transient), so the record keeps the outcome and the
        // average rate after the live rows are gone.
        let d = destinations[i]
        if let error {
            logSink?(AppLocalized("Upload to \(name) failed — \(error)"), false)
        } else {
            let total = ByteCountFormatter.string(fromByteCount: d.totalBytes, countStyle: .file)
            logSink?(AppLocalized("Uploaded to \(name) — \(total) in \(Self.durationText(d.elapsed)) · \(Self.rateText(d.bytesPerSecond))"), false)
        }
    }

    func noteCleanup(_ text: String) {
        cleanupNote = text
        logger.info("[Backup] \(text)")
        // Also into the record: "did it clean up after itself?" is a question
        // asked long after the live note has disappeared.
        logSink?(text, false)
    }

    func end() {
        isTransferring = false
        // A repeating timer holds its target and keeps the run loop busy;
        // leaving it running after the transfer would redraw the screen once a
        // second for the life of the app.
        ticker?.invalidate()
        ticker = nil
    }

    func reset() {
        destinations = []
        cleanupNote = nil
        isTransferring = false
        ticker?.invalidate()
        ticker = nil
    }

    /// True when the last run left something the user still needs to see.
    ///
    /// [T-ios-backup-transient-success] Drives `clearIfSettledAndSuccessful()`:
    /// a clean run's rows are disposable (Backup History keeps the record), a
    /// failed one's are not — the error string lives only here, and clearing it
    /// on navigation would silently hide the reason a destination failed.
    var hasFailure: Bool { destinations.contains { $0.error != nil } }

    /// Drop the finished transfer rows so a completed backup does not leave a
    /// permanent result card on the Backup screen.
    ///
    /// [T-ios-backup-transient-success] `BackupTransferStatus` is a process-wide
    /// singleton and nothing ever called `reset()`, so the 100%/size/duration
    /// card from a successful run stayed on screen for the life of the app —
    /// through leaving and re-entering Settings, and on every later visit. The
    /// success feedback is worth showing while the user is watching it happen;
    /// it is not worth a permanent block of screen space, and the same facts
    /// are in Backup History.
    ///
    /// Deliberately narrow — it clears ONLY when all of these hold:
    ///   * nothing is transferring (never touch a live run), and
    ///   * no destination reported an error (failures stay put, see above), and
    ///   * there is actually something to clear.
    /// A multi-destination run is covered by the same rule: the rows go
    /// together once every one of them finished cleanly.
    @discardableResult
    func clearIfSettledAndSuccessful() -> Bool {
        guard !isTransferring, !destinations.isEmpty, !hasFailure else { return false }
        // A run that has begun but not finished has no `finishedAt` yet;
        // requiring it stops a reset from racing a transfer that is between
        // `begin` and its first byte.
        guard destinations.allSatisfy({ $0.finishedAt != nil }) else { return false }
        reset()
        return true
    }

    // MARK: - Formatting

    static func rateText(_ bytesPerSecond: Double) -> String {
        guard bytesPerSecond > 0 else { return "—" }
        return ByteCountFormatter.string(fromByteCount: Int64(bytesPerSecond),
                                         countStyle: .file) + "/s"
    }

    static func durationText(_ seconds: TimeInterval) -> String {
        if seconds < 1 { return AppLocalized("less than a second") }
        if seconds < 60 { return AppLocalized("\(Int(seconds.rounded()))s") }
        let m = Int(seconds) / 60, s = Int(seconds) % 60
        return s == 0 ? AppLocalized("\(m)m") : AppLocalized("\(m)m \(s)s")
    }
}
