import CryptoKit
import Foundation

private let logger = AppLogger(category: "Backup")

/// Content-addressed blob writer for the staging directory (§2).
///
/// Deliberately built in stage 1 rather than retrofitted: the design calls this
/// out explicitly ("blob 内容寻址此时就位；后补=返工"). Retrofitting would mean
/// changing both the package layout and every exporter's write path after the
/// fact.
///
/// Two jobs:
///   1. **Dedup by content.** The same bytes referenced from several sessions —
///      or from both a message attachment and the session's file tree — are
///      stored once under their SHA-256. Callers don't coordinate; they just
///      offer files and get told which hash they landed on.
///   2. **Enforce the §3.4 size cap.** Over-limit files are refused as
///      `.skipped`, and the caller records a tombstone instead of a blob. The
///      cap is checked before any hashing or copying, so a 2GB file costs a
///      `stat`, not a read.
final class BackupBlobStore {
    /// `blobs/<first 2 hex>/<sha256>` — the two-level fan-out keeps any single
    /// directory small enough that FileManager stays fast on huge packages.
    private let blobsRoot: URL
    private let fm = FileManager.default

    /// nil = unlimited (the §3.4 default).
    private let maxFileBytes: Int64?

    /// The configured cap, for `manifest.limits.max_file_bytes`. Recorded even
    /// when nothing was skipped, so a reader can tell "unlimited" apart from
    /// "capped, but nothing happened to exceed it".
    var maxFileBytesForManifest: Int64? { maxFileBytes }

    /// Hashes already written this run. Cheap in-memory dedup so a repeated
    /// file doesn't re-hash or re-copy.
    private var seen = Set<String>()

    private(set) var blobIndex: [BackupBlobIndexEntry] = []
    private(set) var totalBytesStored: Int64 = 0
    private(set) var skippedFiles = 0
    private(set) var skippedBytes: Int64 = 0
    /// Paths dropped by the cap, surfaced on the completion screen so the user
    /// learns about the gap now rather than at restore time (§3.4).
    private(set) var skippedPaths: [(path: String, size: Int64)] = []

    /// When set, blobs are written STRAIGHT INTO the package and never copied
    /// into the staging tree.
    ///
    /// This is what keeps peak disk usage to roughly one copy of the finished
    /// package. With blobs staged first, a 3.84 GB backup needed ~11.5 GB free
    /// (staging + the system's ZIP + the copied-out result) and failed outright
    /// on a phone without the room.
    ///
    /// nil restores the staging behaviour, which the resume path still uses for
    /// packages produced by an older build.
    var packageSink: BackupZipWriter?

    init(stagingRoot: URL, maxFileBytes: Int64?) {
        self.blobsRoot = stagingRoot.appendingPathComponent("blobs", isDirectory: true)
        self.maxFileBytes = maxFileBytes
    }

    /// Entry path a blob occupies inside the package.
    static func packagePath(for digest: String) -> String {
        "blobs/\(digest.prefix(2))/\(digest)"
    }

    /// Rebuild in-memory state from a staging tree left by an interrupted run.
    ///
    /// Needed because `blobs.index.jsonl` is only written once, at the very end
    /// of an export — so a resumed run cannot read its own prior index back.
    /// `files.index.jsonl` IS written incrementally, and every stored entry
    /// carries the digest, so the index is reconstructed from there and
    /// cross-checked against the blobs actually present on disk.
    ///
    /// Entries whose blob is missing (the run died between writing the index
    /// line and copying the content) are deliberately NOT re-registered: the
    /// digest stays out of `seen`, so the next pass over that file re-stores
    /// it. That is what stops a resumed package from referencing content it
    /// does not contain.
    func rehydrate(fromFileIndexAt url: URL) {
        guard let data = try? Data(contentsOf: url) else { return }
        let decoder = JSONDecoder()
        var restored = 0
        for line in data.split(separator: 0x0A) where !line.isEmpty {
            guard let entry = try? decoder.decode(BackupFileIndexEntry.self, from: Data(line)),
                  let sha = entry.sha256, entry.skipped == nil, entry.isDirectory != true
            else { continue }
            guard !seen.contains(sha) else { continue }
            let blob = blobURL(for: sha)
            guard fm.fileExists(atPath: blob.path) else { continue }
            seen.insert(sha)
            totalBytesStored += entry.size
            blobIndex.append(BackupBlobIndexEntry(
                sha256: sha, size: entry.size, path: entry.path,
                sessionId: nil, mime: Self.mimeType(for: blob)))
            restored += 1
        }
        if restored > 0 {
            logger.info("[Backup] resume: recovered \(restored) blob(s) from a previous attempt")
        }
    }

    enum Outcome {
        case stored(sha256: String, size: Int64)
        /// Same content already in the package; no new bytes written.
        case duplicate(sha256: String, size: Int64)
        /// Excluded by the §3.4 cap. Caller must write a tombstone.
        case skippedTooLarge(size: Int64)
    }

    /// Offer a file on disk to the package.
    ///
    /// `logicalPath` is only recorded in the index — the blob's location is
    /// derived purely from its content hash.
    @discardableResult
    func addFile(at url: URL, logicalPath: String, sessionId: String? = nil) throws -> Outcome {
        let size = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0

        // Cap check FIRST — before hashing or copying. A skipped 2GB file must
        // cost a stat, not a full read.
        if let maxFileBytes, size > maxFileBytes {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.append((logicalPath, size))
            logger.info("[Backup] skip (size cap) \(logicalPath) size=\(size) > \(maxFileBytes)")
            return .skippedTooLarge(size: size)
        }

        let digest = try Self.sha256OfFile(at: url)

        if seen.contains(digest) {
            return .duplicate(sha256: digest, size: size)
        }

        if let packageSink {
            // Straight into the package, reading from the user's ORIGINAL file.
            // No copy is made at all, so there is nothing to delete afterwards
            // and nothing accumulates in staging.
            try packageSink.addFile(at: url, name: Self.packagePath(for: digest))
        } else {
            let dest = blobURL(for: digest)
            try fm.createDirectory(at: dest.deletingLastPathComponent(),
                                   withIntermediateDirectories: true)
            // A hash collision on an identical digest would mean identical
            // content, so an existing file is already correct — but `seen`
            // missing it means a prior interrupted run left it. Replace to be
            // safe.
            if fm.fileExists(atPath: dest.path) {
                try? fm.removeItem(at: dest)
            }
            try fm.copyItem(at: url, to: dest)
        }

        seen.insert(digest)
        totalBytesStored += size
        blobIndex.append(BackupBlobIndexEntry(
            sha256: digest, size: size, path: logicalPath,
            sessionId: sessionId, mime: Self.mimeType(for: url)))
        return .stored(sha256: digest, size: size)
    }

    /// Offer in-memory bytes (used for generated content that never hits disk).
    @discardableResult
    func addData(_ data: Data, logicalPath: String, sessionId: String? = nil) throws -> Outcome {
        let size = Int64(data.count)
        if let maxFileBytes, size > maxFileBytes {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.append((logicalPath, size))
            return .skippedTooLarge(size: size)
        }
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        if seen.contains(digest) {
            return .duplicate(sha256: digest, size: size)
        }
        if let packageSink {
            try packageSink.addData(data, name: Self.packagePath(for: digest))
        } else {
            let dest = blobURL(for: digest)
            try fm.createDirectory(at: dest.deletingLastPathComponent(),
                                   withIntermediateDirectories: true)
            try data.write(to: dest, options: .atomic)
        }
        seen.insert(digest)
        totalBytesStored += size
        blobIndex.append(BackupBlobIndexEntry(
            sha256: digest, size: size, path: logicalPath,
            sessionId: sessionId, mime: Self.mimeType(for: URL(fileURLWithPath: logicalPath))))
        return .stored(sha256: digest, size: size)
    }

    private func blobURL(for digest: String) -> URL {
        blobsRoot
            .appendingPathComponent(String(digest.prefix(2)), isDirectory: true)
            .appendingPathComponent(digest)
    }

    // MARK: - Hashing

    /// Streaming SHA-256 — never loads the file into memory.
    ///
    /// Media offloads run to hundreds of MB and the app has a documented jetsam
    /// history from whole-file reads, so this reads in 1MB chunks regardless of
    /// file size.
    static func sha256OfFile(at url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            // [T-backup-scan-jetsam] FileHandle.read returns AUTORELEASED
            // NSData. Without a per-chunk pool every 1MB chunk of a 400MB file
            // stays alive until the enclosing pool drains — the "streaming"
            // read still accumulated the whole file in memory, which is the
            // sawtooth footprint climb (500MB → 3.3GB) that ended in the
            // 2026-08-17 02:01 jetsam SIGKILL during a 22782-blob scan.
            let done = try autoreleasepool { () -> Bool in
                let chunk = try handle.read(upToCount: 1024 * 1024) ?? Data()
                if chunk.isEmpty { return true }
                hasher.update(data: chunk)
                return false
            }
            if done { break }
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func mimeType(for url: URL) -> String? {
        switch url.pathExtension.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "heic": return "image/heic"
        case "pdf": return "application/pdf"
        case "txt", "log": return "text/plain"
        case "md": return "text/markdown"
        case "json": return "application/json"
        case "zip": return "application/zip"
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "wav": return "audio/wav"
        default: return nil
        }
    }
}

// MARK: - [T-backup-scan-jetsam] Memory-pressure governor

/// Backpressure for long backup scans. The 2026-08-17 02:01 jetsam kill showed
/// the exporter charging on through CRITICAL system memory pressure (footprint
/// 3.3GB) — nothing in the pipeline ever looked at the pressure signal. This
/// governor watches the kernel's memory-pressure source and lets the scan
/// loops brake between units of work:
///   • warning  → one short 50ms breather per unit, giving reclaim a chance
///   • critical → hold (250ms slices, up to 10s) until pressure drops
/// Checks are a lock-guarded enum read when pressure is normal, so the
/// steady-state cost per file is nil. Deliberately synchronous (Thread.sleep):
/// callers are the blocking scan loops on background threads, and holding that
/// exact thread is the point — the agent's main thread is untouched.
final class BackupMemoryGovernor {
    static let shared = BackupMemoryGovernor()

    private enum Level { case normal, warning, critical }
    private let lock = NSLock()
    private var level: Level = .normal
    private let source: DispatchSourceMemoryPressure

    private init() {
        source = DispatchSource.makeMemoryPressureSource(
            eventMask: [.normal, .warning, .critical],
            queue: DispatchQueue.global(qos: .utility))
        source.setEventHandler { [weak self] in
            guard let self else { return }
            let event = self.source.data
            let next: Level = event.contains(.critical) ? .critical
                : event.contains(.warning) ? .warning : .normal
            self.lock.lock()
            let prev = self.level
            self.level = next
            self.lock.unlock()
            if prev != next {
                logger.info("[Backup] memory pressure \(prev) → \(next)")
            }
        }
        source.activate()
    }

    private var currentLevel: Level {
        lock.lock(); defer { lock.unlock() }
        return level
    }

    /// Call between units of work (per file, per session). Blocks the calling
    /// background thread while the system reports critical pressure.
    func throttleIfNeeded(context: @autoclosure () -> String) {
        switch currentLevel {
        case .normal:
            return
        case .warning:
            Thread.sleep(forTimeInterval: 0.05)
        case .critical:
            let start = CFAbsoluteTimeGetCurrent()
            logger.warning("[Backup] PAUSE (\(context())) — system memory pressure critical; holding scan")
            while currentLevel == .critical,
                  CFAbsoluteTimeGetCurrent() - start < 10 {
                Thread.sleep(forTimeInterval: 0.25)
            }
            let waited = CFAbsoluteTimeGetCurrent() - start
            logger.warning("[Backup] RESUME (\(context())) after \(String(format: "%.1f", waited))s (pressure now \(currentLevel == .critical ? "still critical — proceeding slowly" : "relieved"))")
        }
    }
}
