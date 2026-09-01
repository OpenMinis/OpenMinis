import Foundation
import UIKit

private let logger = AppLogger(category: "Backup")

/// Keeps the app running long enough to finish (or safely abandon) a backup
/// when the user switches away mid-operation (review finding I5).
///
/// Without an assertion iOS suspends the app roughly 30 seconds after it
/// backgrounds. A suspended Swift task is FROZEN, not cancelled — no `defer`
/// runs, no cleanup happens — so an export interrupted this way used to leave
/// its staging orphaned and lose all its work. The app already uses this
/// mechanism for the agent loop (`AIChatViewModel+BackgroundTask`), so this
/// follows the same shape, including tolerating a refused grant.
///
/// This buys time; it does not guarantee completion. A multi-GB export will
/// still outlive any grant, which is exactly why it is paired with the
/// resumable staging in `BackupExportJournal` — the assertion widens the
/// window, and resume covers what falls outside it.
@MainActor
enum BackupBackgroundAssertion {

    /// Run `body` while holding a background-task assertion.
    static func run<T>(_ name: String, _ body: () async throws -> T) async rethrows -> T {
        var id = UIApplication.shared.beginBackgroundTask(withName: name) {
            // Expiry: nothing to roll back here. The export's own staging is
            // persistent and resumable, so being cut short costs time, not
            // data. Logged because "why did my backup stop" is otherwise
            // invisible.
            logger.warning("[Backup] background assertion for \(name) expired")
        }
        if id == .invalid {
            // A refused grant is not fatal — the operation still runs, it just
            // has no protection if the user leaves.
            logger.warning("[Backup] beginBackgroundTask REFUSED for \(name) — no OS grant held")
        }
        defer {
            if id != .invalid {
                UIApplication.shared.endBackgroundTask(id)
                id = .invalid
            }
        }
        return try await body()
    }
}

/// Process-wide exclusion for backup operations (review finding I3).
///
/// Neither `BackupExporter` nor `BackupImporter` provided any: every call site
/// constructs a **fresh** actor instance, and actor isolation only serialises
/// calls on one instance — so two restores, or an export and a restore, could
/// run at the same time. That is reachable in practice: the root-level restore
/// sheet can be opened from any screen (including while a restore started from
/// Settings is still running), and the debug RPC path is independent of both.
///
/// Concurrent restores are the dangerous case. They share mutable destinations
/// (the same session directories, the same provider config) and, before the
/// per-run staging fix, the same rollback directory — so one run's cleanup
/// deleted the other's only recovery state.
///
/// Deliberately an advisory in-process lock, not a file lock: it exists to stop
/// the app racing *itself*, which is the whole exposure. A crashed process
/// leaves nothing to clean up, because the lock dies with it.
actor BackupActivityLock {
    static let shared = BackupActivityLock()

    enum Activity: String {
        case export
        case restore
    }

    struct Busy: LocalizedError {
        let current: Activity
        var errorDescription: String? {
            switch current {
            case .export:
                return AppLocalized("A backup is already in progress. Wait for it to finish and try again.")
            case .restore:
                return AppLocalized("A restore is already in progress. Wait for it to finish and try again.")
            }
        }
    }

    private var current: Activity?

    /// Run `body` exclusively, or throw `Busy` if something else holds the lock.
    ///
    /// Refuses rather than queueing: a user who taps Create Backup twice wants
    /// to be told it is already running, not to silently start a second
    /// multi-hundred-MB export the moment the first ends.
    func withLock<T>(_ activity: Activity,
                     _ body: () async throws -> T) async throws -> T {
        if let current {
            logger.warning("[Backup] refusing \(activity.rawValue): \(current.rawValue) already running")
            throw Busy(current: current)
        }
        current = activity
        defer { current = nil }
        return try await body()
    }

    var isBusy: Bool { current != nil }
}
