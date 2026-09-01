import Foundation

private let logger = AppLogger(category: "Backup")

/// `rescue.json` — a deliberately minimal, ALWAYS-PLAINTEXT index that survives
/// to answer one question when the rest of the package cannot be read:
/// *what was this blob, and who did it belong to?*
///
/// A `.minisbak` is a ZIP, which already survives partial damage well — every
/// member is stored independently and local headers carry a magic that can be
/// scanned for. The single points of failure are the two indexes:
///
///   - `manifest.json` — lose it and you don't know what the package held.
///   - `blobs.index.jsonl` — lose it and `blobs/9f/9f8e7d…` is an anonymous
///     blob with a self-verifying name and no idea which session's attachment
///     it was.
///
/// This file is that mapping, kept small on purpose so it stays cheap to write
/// and likely to land intact.
///
/// **Always plaintext, even in an encrypted package** — the same rule
/// `manifest.json` follows, and for a sharper reason: rescue is exactly the
/// situation where the user may have lost the passphrase. An encrypted rescue
/// index is useless to the person who needs it most. That is a deliberate,
/// bounded disclosure: it reveals file *names*, sizes and session titles, never
/// message content or credentials. Anyone unwilling to disclose that much
/// should not be handing the package to a third party at all.
struct BackupRescueIndex: Codable {

    static let filename = "rescue.json"
    /// Second copy of the manifest, written last (§2 of the rescue task).
    static let manifestCopyFilename = "manifest.rescue.json"

    var v: Int = 1
    var snapshotAt: Date?
    var backupId: String
    var blobs: [Blob]
    var sessions: [SessionSummary]

    enum CodingKeys: String, CodingKey {
        case v
        case snapshotAt = "snapshot_at"
        case backupId = "backup_id"
        case blobs, sessions
    }

    /// One stored blob: enough to attribute `blobs/<xx>/<sha256>` to a place in
    /// the user's data, and nothing more.
    struct Blob: Codable {
        var sha256: String
        var size: Int64
        var category: String
        /// nil when the content isn't session-bound (shared files, skills…).
        var sessionId: String?
        var path: String

        enum CodingKeys: String, CodingKey {
            case sha256, size, category, path
            case sessionId = "session_id"
        }
    }

    /// The smallest useful description of a conversation: enough for the user
    /// to recognise what a damaged package contained. Message CONTENT is
    /// deliberately absent — it is recoverable from `data/sessions.jsonl` and
    /// duplicating it here would defeat the point of a small index.
    struct SessionSummary: Codable {
        var id: String
        var title: String?
        var messageCount: Int

        enum CodingKeys: String, CodingKey {
            case id, title
            case messageCount = "message_count"
        }
    }

    // MARK: - Building

    /// Build the index from what the export already computed.
    ///
    /// The category for each blob comes from `files.index.jsonl` rather than
    /// being re-derived from the path: the file index is the authority on which
    /// category claimed a file, and re-deriving it from a path prefix would be
    /// a second, drifting implementation of the same rule.
    static func build(backupId: String,
                      snapshotAt: Date,
                      blobIndex: [BackupBlobIndexEntry],
                      fileIndexURL: URL,
                      sessions: [SessionSummary]) -> BackupRescueIndex {
        var categoryByPath: [String: String] = [:]
        if let data = try? Data(contentsOf: fileIndexURL) {
            let decoder = JSONDecoder()
            for line in data.split(separator: 0x0A) where !line.isEmpty {
                guard let e = try? decoder.decode(BackupFileIndexEntry.self, from: Data(line))
                else { continue }
                categoryByPath[e.path] = e.category
            }
        }

        let blobs = blobIndex.map {
            Blob(sha256: $0.sha256,
                 size: $0.size,
                 category: categoryByPath[$0.path] ?? "unknown",
                 sessionId: $0.sessionId,
                 path: $0.path)
        }

        return BackupRescueIndex(snapshotAt: snapshotAt, backupId: backupId,
                                 blobs: blobs, sessions: sessions)
    }

    /// Write the index, logging rather than throwing on failure.
    ///
    /// The rescue index is a redundancy aid: a backup that succeeded except for
    /// this file is still a good backup, and failing the whole export over it
    /// would trade a real backup for a hypothetical one. The user is told via
    /// the log, not by losing their package.
    func writeIgnoringFailure(to staging: URL) {
        do {
            try write(to: staging)
        } catch {
            logger.error("[Backup] rescue index could not be written (backup is unaffected): \(error.localizedDescription)")
        }
    }

    func write(to staging: URL) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(self)
            .write(to: staging.appendingPathComponent(Self.filename), options: .atomic)
        logger.info("[Backup] rescue index written: \(blobs.count) blob(s), \(sessions.count) session(s)")
    }

    // MARK: - Reading

    /// Read the rescue index out of a package, tolerating a damaged one.
    static func read(fromPackageAt url: URL) -> BackupRescueIndex? {
        guard let data = try? BackupPackageReader.readEntry(at: url, named: filename) ?? nil
        else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(BackupRescueIndex.self, from: data)
    }
}
