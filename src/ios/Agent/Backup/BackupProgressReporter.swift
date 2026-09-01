import Foundation

/// Turns "Exporting chats…" into a line that changes while it works.
///
/// The log used to get ONE line per category, emitted as the category began.
/// For everything except chats that is fine — they finish inside a second. But
/// a large chat history takes a minute or more, during which the newest log
/// line said "Exporting chats…" and nothing moved. There is no way to tell a
/// slow backup from a stuck one, which is exactly when a user force-quits the
/// thing that was working.
///
/// So a long category reports `n/total` plus a remaining-time estimate, at
/// most once a second.
///
/// ## Why the estimate is deliberately crude
///
/// It is a straight linear extrapolation from the average time per item so
/// far. Sessions vary enormously — one with 3 messages and no attachments
/// against one with 900 messages and a video — so early estimates swing.
/// Anything cleverer (per-item weighting, a decaying average) would still be
/// wrong, just less legibly. What the user actually needs is "minutes, not
/// hours", and the estimate settles quickly once a representative sample of
/// sessions has gone through.
struct BackupProgressReporter {

    /// Human label for the unit being counted, e.g. "conversations".
    private let noun: String
    private let total: Int
    private let started: Date
    /// `transient` marks a line the next one should REPLACE — see
    /// BackupHistory.LogEntry.isTransient.
    private let emit: (String, _ transient: Bool) -> Void

    /// Throttle state. `nil` until the first throttled update is emitted.
    private var lastEmit: Date?
    private var done = 0

    /// Minimum gap between progress lines.
    ///
    /// The exporter's history log collapses consecutive duplicates, but these
    /// lines differ every time (the count moves), so without a throttle a
    /// 2000-session export would write 2000 rows into the record and the log
    /// section would become unreadable — and each write hits UserDefaults.
    private static let interval: TimeInterval = 1.0

    init(noun: String, total: Int,
         emit: @escaping (String, _ transient: Bool) -> Void) {
        self.noun = noun
        self.total = total
        self.started = Date()
        self.emit = emit
    }

    /// Announce the category with the amount of work it involves.
    ///
    /// No estimate here on purpose: with zero items done there is nothing to
    /// extrapolate from, and a made-up number at the start is the one most
    /// likely to be believed.
    func begin(_ label: String) {
        emit(total > 0
             ? AppLocalized("\(label) — \(total) \(noun)")
             : label, false)
    }

    /// Record one completed item, emitting at most once a second.
    mutating func step() {
        done += 1
        guard total > 0 else { return }
        let now = Date()
        if let last = lastEmit, now.timeIntervalSince(last) < Self.interval { return }
        // Skip the very first item: one sample makes for a wild extrapolation
        // (often minutes off), and it would be the first thing the user sees.
        guard done > 1 else { lastEmit = now; return }
        lastEmit = now

        let elapsed = now.timeIntervalSince(started)
        let perItem = elapsed / Double(done)
        let remaining = perItem * Double(max(0, total - done))
        emit(AppLocalized("\(done)/\(total) \(noun) · about \(Self.durationText(remaining)) left"),
             true)   // replaced by the next progress line
    }

    /// Final line for the category: what it produced and how long it took.
    func finish(_ label: String, detail: String? = nil) {
        let elapsed = Date().timeIntervalSince(started)
        let took = Self.durationText(elapsed)
        if let detail {
            emit(AppLocalized("\(label) — \(detail) in \(took)"), false)
        } else {
            emit(AppLocalized("\(label) — \(total) \(noun) in \(took)"), false)
        }
    }

    /// Coarse on purpose: seconds below a minute, whole minutes above. A
    /// "1m 47s left" that is wrong by a minute reads worse than "about 2m".
    static func durationText(_ seconds: TimeInterval) -> String {
        if seconds < 1 { return AppLocalized("less than a second") }
        if seconds < 60 { return AppLocalized("\(Int(seconds.rounded()))s") }
        let minutes = Int((seconds / 60).rounded())
        return AppLocalized("\(minutes)m")
    }
}
