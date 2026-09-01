import Foundation

/// Append-only JSONL writer with automatic shard rollover (§2).
///
/// Every record is one self-contained line, so the importer can parse
/// line-by-line and skip a bad record without losing the file (§2.2 rule 3).
/// Writes go straight to a FileHandle — nothing accumulates in memory, which is
/// what lets a 100k-message export stay flat.
final class BackupJSONLWriter {
    private let directory: URL
    /// `messages` → `messages.jsonl`, then `messages-0002.jsonl` on rollover.
    private let baseName: String
    private let maxShardBytes: Int

    private var handle: FileHandle?
    private var currentURL: URL?
    private var currentBytes = 0
    private var shardIndex = 1

    private(set) var writtenRecords = 0
    private(set) var totalBytes: Int64 = 0
    private(set) var shardPaths: [String] = []

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        // Deterministic key order keeps package diffs readable and makes the
        // integrity hash reproducible for the same input.
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    init(directory: URL, baseName: String, maxShardBytes: Int = BackupFormat.maxShardBytes) {
        self.directory = directory
        self.baseName = baseName
        self.maxShardBytes = maxShardBytes
    }

    func write<T: Codable>(_ envelope: BackupRecordEnvelope<T>) throws {
        var line = try encoder.encode(envelope)
        line.append(0x0A)  // newline — the record separator
        try append(line)
        writtenRecords += 1
    }

    private func append(_ data: Data) throws {
        if handle == nil || currentBytes + data.count > maxShardBytes {
            try rollover()
        }
        guard let handle else { throw BackupError.writeFailed("no open shard for \(baseName)", underlying: nil) }
        try handle.write(contentsOf: data)
        currentBytes += data.count
        totalBytes += Int64(data.count)
    }

    private func rollover() throws {
        try close()
        let name = shardIndex == 1 ? "\(baseName).jsonl"
                                   : String(format: "%@-%04d.jsonl", baseName, shardIndex)
        let url = directory.appendingPathComponent(name)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
        currentURL = url
        currentBytes = 0
        shardIndex += 1
        shardPaths.append(name)
    }

    func close() throws {
        try handle?.close()
        handle = nil
        currentURL = nil
    }

    /// True when nothing was ever written, so the caller can avoid emitting an
    /// empty file (and an empty category) into the package.
    var isEmpty: Bool { writtenRecords == 0 }
}

/// Small helper for the one-shot JSON files (`provider_config.json`,
/// `mcp_servers.json`, `manifest.json`).
enum BackupJSONFile {
    static func write<T: Encodable>(_ value: T, to url: URL) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes, .prettyPrinted]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(value)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }
}
