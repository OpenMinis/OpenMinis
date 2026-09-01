import Foundation

private let logger = AppLogger(category: "Backup")

/// A record of every backup run, kept for one month.
///
/// Exists because a backup is a long, mostly-invisible operation whose outcome
/// the user has to take on trust otherwise. "Did last night's backup reach the
/// NAS?" was previously unanswerable once the screen was dismissed — the
/// per-destination results lived in view state and died with it.
///
/// Stored as one JSON file in Application Support rather than UserDefaults: the
/// log lines make each record a few KB, and UserDefaults is the wrong place for
/// something that grows.
@MainActor
final class BackupHistory: ObservableObject {
    static let shared = BackupHistory()

    /// How long a finished run stays visible.
    ///
    /// The task asked for a month. Pruning is by AGE, not by count, so a busy
    /// week can't push out a record the user still cares about, and an idle
    /// month leaves nothing stale behind.
    static let retention: TimeInterval = 30 * 24 * 60 * 60

    enum Status: String, Codable {
        case running
        case succeeded
        /// Finished, but something the user should know about — a destination
        /// that was unreachable, files skipped by the size cap.
        case completedWithIssues
        case failed
    }

    /// One line of the run's log.
    struct LogEntry: Codable, Identifiable, Sendable {
        var id: UUID = UUID()
        var at: Date
        var message: String
        /// Marks a line as a problem so the detail view can highlight it
        /// without re-parsing the text.
        var isProblem: Bool = false
        /// A live progress line ("120/400 conversations · about 40s left")
        /// that the NEXT progress line replaces instead of following.
        ///
        /// Without this, a 400-session export at one update a second would
        /// leave ~60 near-identical rows in the record — a log the user has to
        /// scroll past to find what actually happened, and 60 rewrites of the
        /// whole history file while the backup is trying to work.
        var isTransient: Bool = false

        /// See Record's decoder: a default value does NOT make a key optional
        /// to the synthesized Decodable, and `isTransient` was added after
        /// records already existed in the wild.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
            at = try c.decode(Date.self, forKey: .at)
            message = try c.decode(String.self, forKey: .message)
            isProblem = try c.decodeIfPresent(Bool.self, forKey: .isProblem) ?? false
            isTransient = try c.decodeIfPresent(Bool.self, forKey: .isTransient) ?? false
        }

        init(id: UUID = UUID(), at: Date, message: String,
             isProblem: Bool = false, isTransient: Bool = false) {
            self.id = id
            self.at = at
            self.message = message
            self.isProblem = isProblem
            self.isTransient = isTransient
        }
    }

    /// One file left out of a package by the per-file size cap.
    struct SkippedEntry: Codable, Identifiable, Sendable {
        var id: String { path }
        /// Package-relative logical path, e.g. `chats/<sessionId>/uploads/a.mp4`.
        var path: String
        var size: Int64
        /// Resolved at export time, while the session is still to hand.
        /// Looking it up later would fail for a conversation since deleted —
        /// and a list of bare UUIDs answers none of "which chat was that?".
        var sessionTitle: String?

        var fileName: String { (path as NSString).lastPathComponent }
    }

    /// Cap on how many skipped entries a record keeps. The COUNT is always
    /// exact; this only bounds the list.
    static let maxStoredSkippedPaths = 500

    struct DestinationOutcome: Codable, Identifiable, Sendable {
        var id: UUID = UUID()
        var name: String
        var succeeded: Bool
        var detail: String?
        /// [T-backup-destination-detail] Backend tag (`smb`, `webdav`, `sftp`,
        /// `s3`, …) and the folder the package was written to, so the row can
        /// say WHERE a backup went rather than only which saved destination was
        /// picked. A mounted-folder destination has a kind but no remote path.
        ///
        /// Both optional: records written before this existed decode with nil
        /// and the UI simply omits the subtitle.
        var kind: String?
        var path: String?

        enum CodingKeys: String, CodingKey {
            case id, name, succeeded, detail, kind, path
        }

        /// Hand-written so an older record — which has no `kind` / `path`, and
        /// whose `id` predates nothing — still decodes. Swift's synthesized
        /// decoder throws `keyNotFound` for a missing key even when the
        /// property has a default, which would discard the whole history file.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
            name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
            succeeded = try c.decodeIfPresent(Bool.self, forKey: .succeeded) ?? false
            detail = try c.decodeIfPresent(String.self, forKey: .detail)
            kind = try c.decodeIfPresent(String.self, forKey: .kind)
            path = try c.decodeIfPresent(String.self, forKey: .path)
        }

        init(id: UUID = UUID(), name: String, succeeded: Bool,
             detail: String? = nil, kind: String? = nil, path: String? = nil) {
            self.id = id
            self.name = name
            self.succeeded = succeeded
            self.detail = detail
            self.kind = kind
            self.path = path
        }
    }

    struct Record: Codable, Identifiable, Sendable {
        var id: UUID = UUID()
        /// The exporter's own backupId, so a record can be tied to the package
        /// it produced (and to a resumed run).
        var backupId: String
        var startedAt: Date
        var finishedAt: Date?
        var status: Status
        var categories: [String]
        var encrypted: Bool
        var totalBytes: Int64
        var skippedFiles: Int
        /// What was left out, so the summary's count can be opened up.
        ///
        /// Truncated to `maxStoredSkippedPaths` — the count above stays exact,
        /// but a "don't back up files" run skips every attachment the user has
        /// (1,310 on the test device) and storing all of them would bloat a
        /// file that is rewritten on every log line.
        var skippedEntries: [SkippedEntry] = []
        var packageName: String?
        var destinations: [DestinationOutcome]
        var log: [LogEntry]
        var errorMessage: String?

        var duration: TimeInterval? {
            finishedAt.map { $0.timeIntervalSince(startedAt) }
        }

        // MARK: Decoding

        /// Hand-written so a field added later does not invalidate records
        /// written by an older build.
        ///
        /// Swift's synthesized Decodable throws `keyNotFound` for a missing
        /// key EVEN when the property has a default value — and `load()`
        /// decodes the whole array with `try?`, so one older record would take
        /// the user's entire backup history with it. Verified: decoding
        /// `{"a":1}` into a type with `var b: [String] = []` fails outright.
        ///
        /// Only genuinely required fields are decoded strictly; everything
        /// that has a sensible empty value is optional.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
            backupId = try c.decodeIfPresent(String.self, forKey: .backupId) ?? ""
            startedAt = try c.decode(Date.self, forKey: .startedAt)
            finishedAt = try c.decodeIfPresent(Date.self, forKey: .finishedAt)
            status = try c.decode(Status.self, forKey: .status)
            categories = try c.decodeIfPresent([String].self, forKey: .categories) ?? []
            encrypted = try c.decodeIfPresent(Bool.self, forKey: .encrypted) ?? false
            totalBytes = try c.decodeIfPresent(Int64.self, forKey: .totalBytes) ?? 0
            skippedFiles = try c.decodeIfPresent(Int.self, forKey: .skippedFiles) ?? 0
            skippedEntries = try c.decodeIfPresent([SkippedEntry].self,
                                                   forKey: .skippedEntries) ?? []
            packageName = try c.decodeIfPresent(String.self, forKey: .packageName)
            destinations = try c.decodeIfPresent([DestinationOutcome].self,
                                                 forKey: .destinations) ?? []
            log = try c.decodeIfPresent([LogEntry].self, forKey: .log) ?? []
            errorMessage = try c.decodeIfPresent(String.self, forKey: .errorMessage)
        }

        init(id: UUID = UUID(), backupId: String, startedAt: Date, finishedAt: Date?,
             status: Status, categories: [String], encrypted: Bool, totalBytes: Int64,
             skippedFiles: Int, packageName: String?,
             destinations: [DestinationOutcome], log: [LogEntry],
             errorMessage: String?, skippedEntries: [SkippedEntry] = []) {
            self.id = id
            self.backupId = backupId
            self.startedAt = startedAt
            self.finishedAt = finishedAt
            self.status = status
            self.categories = categories
            self.encrypted = encrypted
            self.totalBytes = totalBytes
            self.skippedFiles = skippedFiles
            self.skippedEntries = skippedEntries
            self.packageName = packageName
            self.destinations = destinations
            self.log = log
            self.errorMessage = errorMessage
        }
    }

    @Published private(set) var records: [Record] = []

    private var storeURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory,
                                           in: .userDomainMask)[0]
            .appendingPathComponent("BackupHistory", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }

    private init() {
        load()
        pruneExpired()
    }

    // MARK: - Persistence

    private func load() {
        guard let data = try? Data(contentsOf: storeURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        records = (try? decoder.decode([Record].self, from: data)) ?? []
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        do {
            try encoder.encode(records).write(to: storeURL, options: .atomic)
        } catch {
            // History is a convenience; losing it must never fail a backup.
            logger.warning("[Backup] couldn't save history: \(error.localizedDescription)")
        }
    }

    /// Drop runs older than the retention window.
    ///
    /// A run still marked `.running` is only pruned once it is also older than
    /// the window — that state means the app died mid-backup, and keeping it
    /// briefly is how the user finds out rather than seeing the attempt vanish.
    func pruneExpired() {
        let cutoff = Date().addingTimeInterval(-Self.retention)
        let before = records.count
        records.removeAll { ($0.finishedAt ?? $0.startedAt) < cutoff }
        if records.count != before {
            logger.info("[Backup] pruned \(before - records.count) expired history record(s)")
            save()
        }
    }

    // MARK: - Recording a run

    /// Open a record for a run that is starting. Returns its id.
    @discardableResult
    func begin(backupId: String, categories: [String], encrypted: Bool) -> UUID {
        pruneExpired()
        let r = Record(backupId: backupId, startedAt: Date(), finishedAt: nil,
                       status: .running, categories: categories, encrypted: encrypted,
                       totalBytes: 0, skippedFiles: 0, packageName: nil,
                       destinations: [], log: [], errorMessage: nil)
        records.insert(r, at: 0)   // newest first
        save()
        return r.id
    }

    /// Record the exporter's backupId once the run has one.
    ///
    /// `begin` happens before the export starts — deliberately, so a crash
    /// still leaves a record — and the id only exists after the exporter has
    /// decided whether it is starting fresh or adopting an interrupted run.
    /// Without this the field stayed "", and nothing could match a record to
    /// the staging tree it owns (which is what makes Resume offerable).
    func setBackupId(_ id: UUID, _ backupId: String) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        records[i].backupId = backupId
        save()
    }

    func log(_ id: UUID, _ message: String,
             isProblem: Bool = false, isTransient: Bool = false) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        // Collapse consecutive duplicates: the exporter emits the same status
        // string repeatedly while a long category runs, and a log of forty
        // identical lines is worse than useless.
        if records[i].log.last?.message == message { return }
        // A progress line REPLACES the previous progress line rather than
        // stacking under it, so the log keeps one moving row per long category
        // instead of one row per second.
        if records[i].log.last?.isTransient == true {
            records[i].log.removeLast()
        }
        records[i].log.append(LogEntry(at: Date(), message: message,
                                       isProblem: isProblem, isTransient: isTransient))
        save()
    }

    func finish(_ id: UUID, totalBytes: Int64, skippedFiles: Int,
                packageName: String?, destinations: [DestinationOutcome],
                skippedEntries: [SkippedEntry] = []) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        records[i].finishedAt = Date()
        records[i].totalBytes = totalBytes
        records[i].skippedFiles = skippedFiles
        // Largest first: if the list is truncated, the files worth knowing
        // about are the ones that cost the most to leave out.
        records[i].skippedEntries = Array(
            skippedEntries.sorted { $0.size > $1.size }.prefix(Self.maxStoredSkippedPaths))
        records[i].packageName = packageName
        records[i].destinations = destinations

        // "Succeeded" has to mean the whole job worked. A package that was
        // built but never reached a chosen destination is NOT a clean success,
        // and saying so is the difference between a user who re-runs it and one
        // who finds out months later.
        //
        // Skipped files deliberately do NOT count against this. They are the
        // per-file size cap doing exactly what the user set it to do — with the
        // default 100 MB cap, one large attachment turned every backup amber
        // forever, which trains people to ignore a warning that is supposed to
        // mean something went wrong. The count is still reported in the
        // summary, and now links to the list of what was left out.
        let anyDestinationFailed = destinations.contains { !$0.succeeded }
        records[i].status = anyDestinationFailed ? .completedWithIssues : .succeeded
        save()
    }

    func fail(_ id: UUID, message: String) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        records[i].finishedAt = Date()
        records[i].status = .failed
        records[i].errorMessage = message
        records[i].log.append(LogEntry(at: Date(), message: message, isProblem: true))
        save()
    }

    /// Mark runs left `.running` by a killed process, so the list doesn't show
    /// a spinner forever for a backup that will never finish.
    func reconcileInterrupted() {
        var changed = false
        for i in records.indices where records[i].status == .running {
            records[i].status = .failed
            records[i].finishedAt = records[i].finishedAt ?? Date()
            records[i].errorMessage = AppLocalized("Interrupted — the app closed before this backup finished.")
            changed = true
        }
        if changed {
            logger.info("[Backup] marked interrupted run(s) as failed")
            save()
        }
    }

    func remove(_ id: UUID) {
        records.removeAll { $0.id == id }
        save()
    }
}
