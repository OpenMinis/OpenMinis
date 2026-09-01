import Foundation

private let logger = AppLogger(category: "Backup")

/// Owns the one backup that may be running, so anything on screen can observe
/// or stop it.
///
/// Backups are strictly SERIAL. `BackupActivityLock` already refuses a second
/// export at the engine level (review I3); this is the UI-side counterpart —
/// it makes the running state observable, so the button can show "Backing
/// up…" instead of letting the user tap into an error.
///
/// The running Task lives here rather than in a view because a view can be
/// dismissed mid-backup, and a backup that keeps running with nothing holding
/// its handle is a backup nobody can stop.
@MainActor
final class BackupRunController: ObservableObject {
    static let shared = BackupRunController()

    /// History id of the run in flight, if any.
    @Published private(set) var runningRecordId: UUID?
    /// Latest progress line, mirrored for callers that don't read history.
    @Published private(set) var statusText: String = ""

    private var task: Task<Void, Never>?

    var isRunning: Bool { runningRecordId != nil }

    private init() {}

    /// Register the running task. The record id arrives via `attach` once the
    /// run has opened its history entry — the task has to exist first so it can
    /// be cancelled even during the setup that creates that entry.
    /// Returns false if a backup is ALREADY running, in which case the caller's
    /// task is cancelled and never becomes the tracked run.
    ///
    /// Backups are strictly serial. Two concurrent exports would race on the
    /// staging tree and the history file, and this controller can only hold one
    /// task handle — a second `started` used to overwrite the first, leaving a
    /// backup running that Stop could no longer reach. The UI keeps the button
    /// out of that state anyway; this is the guard for every other caller
    /// (scheduled runs, debug RPC) and for a double-tap landing between the
    /// tap and the state update.
    /// Token identifying the run that currently owns the controller's state.
    ///
    /// A refused start still returns into `runExport`, whose `defer` calls
    /// `finished()` — so without an owner check that cancelled task tears down
    /// the state of the run that is legitimately in flight, ending its audio
    /// session mid-upload and leaving the transfer to be suspended ~30s after
    /// the user leaves the app.
    private var runToken = UUID()

    @discardableResult
    func started(task: Task<Void, Never>) -> Bool {
        guard !isRunning else {
            logger.warning("[Backup] start refused — a backup is already running")
            task.cancel()
            return false
        }
        self.task = task
        self.runToken = UUID()
        self.runningRecordId = UUID()   // provisional: marks "running" at once
        // Held for the whole run so leaving the app doesn't cut the transfer
        // short. beginBackgroundTask alone buys ~30s, which is nowhere near
        // enough to push a large package to a NAS.
        BackupKeepAlive.begin()
        return true
    }

    /// The token a caller must present to `finished(token:)`. Captured by a
    /// run right after `started` accepted it.
    var currentToken: UUID { runToken }

    /// Point the controller at the real history record for this run.
    func attach(recordId: UUID) { runningRecordId = recordId }

    func update(status: String) { statusText = status }

    /// Tear down, but only if `token` still owns the controller.
    ///
    /// A run whose start was refused holds a stale token and is ignored here —
    /// otherwise its `defer` would stop the audio session of the run that is
    /// actually uploading.
    func finished(token: UUID) {
        guard token == runToken else {
            logger.info("[Backup] ignoring finish from a superseded run")
            return
        }
        finished()
    }

    func finished() {
        runningRecordId = nil
        statusText = ""
        task = nil
        // Released on EVERY exit path — success, failure and cancellation all
        // route through here. An audio session left running after the work is
        // done keeps the app awake for nothing and shows the user a stale
        // "audio playing" indicator.
        BackupKeepAlive.end()
        // The screen-awake opt-in is per-run, not a persistent preference: it
        // exists to get THIS backup finished. Leaving the idle timer disabled
        // afterwards would quietly stop the phone ever locking again.
        if BackupScreenAwake.isEnabled {
            BackupScreenAwake.set(false)
        }
    }

    /// Stop the running backup.
    ///
    /// Cancels at the next checkpoint rather than killing mid-write: the
    /// exporter checks between categories and before packaging, which are the
    /// boundaries its resume journal already understands. Staging is left in
    /// place on purpose, so the stopped run can be continued later — but only
    /// by resuming it explicitly from its own row in Backup History. Pressing
    /// Start Backup begins a NEW run of the data as it stands then.
    func stop() {
        guard let task else { return }
        logger.info("[Backup] user requested stop")
        task.cancel()
    }

    /// Stop AND discard the partial work, so the next run starts clean.
    func stopAndDiscard() {
        stop()
        BackupExportJournal.abandonInProgress()
        logger.info("[Backup] user requested stop + discard")
    }
}
