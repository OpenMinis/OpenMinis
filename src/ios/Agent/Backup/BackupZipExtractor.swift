import Compression
import Foundation

private let logger = AppLogger(category: "Backup")

/// Full ZIP extraction for the backup importer.
///
/// `BackupPackageReader` (stage 1) can only pull one small entry by name — it
/// exists to read a manifest. Restoring needs every entry on disk, including
/// multi-hundred-MB blobs, so this writes each entry out in chunks rather than
/// materialising it in memory. iOS ships no unzip API, and no ZIP library is
/// linked, so this is hand-rolled like the app's other two ZIP paths.
enum BackupZipExtractor {

    enum ExtractError: LocalizedError {
        case notAZip
        case truncated
        case unsupportedCompression(UInt16)
        case unsafePath(String)

        var errorDescription: String? {
            switch self {
            case .notAZip: return "Not a ZIP archive"
            case .truncated: return "Archive is truncated"
            case .unsupportedCompression(let m): return "Unsupported compression method \(m)"
            case .unsafePath(let p): return "Refusing unsafe entry path: \(p)"
            }
        }
    }

    /// Extract every entry of `zipURL` under `destination`.
    static func extract(_ zipURL: URL, to destination: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: destination, withIntermediateDirectories: true)

        let handle = try FileHandle(forReadingFrom: zipURL)
        defer { try? handle.close() }

        for entry in try centralDirectory(handle: handle) {
            guard let out = try safeDestination(for: entry.name, under: destination) else {
                throw ExtractError.unsafePath(entry.name)
            }

            if entry.name.hasSuffix("/") {
                try fm.createDirectory(at: out, withIntermediateDirectories: true)
                continue
            }
            try fm.createDirectory(at: out.deletingLastPathComponent(),
                                   withIntermediateDirectories: true)
            try writeEntry(entry, from: handle, to: out)
        }
    }

    /// Where `name` may be written, or `nil` if it would land outside `root`.
    ///
    /// [T-ios-backup-zip-canonical-containment] §5.5's path-traversal rule, in
    /// two layers.
    ///
    /// The lexical layer (absolute paths and any `..` component are refused
    /// outright, rather than normalised) is unchanged and still does most of
    /// the work. What it cannot see is a path with no `..` in it at all whose
    /// PARENT is a symlink out of the tree: `esc/victim.txt` is neither
    /// absolute nor contains `..`, so it passes a purely textual check and
    /// still resolves outside. Verified with a real symlink on disk — the old
    /// guard admitted it and the write landed on a file outside the root.
    ///
    /// So the second layer resolves against the filesystem. The subtlety is
    /// WHICH path to resolve: the entry's own leaf normally does not exist yet,
    /// and `resolvingSymlinksInPath()` leaves a non-existent trailing component
    /// untouched, so resolving the full path would silently skip exactly the
    /// symlinked parent we are trying to catch. Walk up to the deepest ancestor
    /// that does exist and canonicalise that instead.
    ///
    /// Both sides are resolved, not just the target: on iOS the temporary
    /// directory sits behind `/var -> /private/var`, so canonicalising only the
    /// candidate would leave it comparing `/private/var/…` against `/var/…` and
    /// reject every entry.
    ///
    /// Note this is defence in depth rather than a live exploit today: the
    /// extractor writes regular files only (it never materialises a symlink
    /// from an entry), and both callers extract into a freshly created
    /// `…-\(UUID())` directory, so there is no attacker-controlled symlink for
    /// entry N to follow. It guards the invariant for the day either of those
    /// stops being true.
    private static func safeDestination(for name: String, under root: URL) throws -> URL? {
        let components = name.split(separator: "/").map(String.init)
        guard !name.hasPrefix("/"), !components.contains("..") else { return nil }
        let out = components.reduce(root) { $0.appendingPathComponent($1) }

        let fm = FileManager.default
        let canonicalRoot = root.standardizedFileURL.resolvingSymlinksInPath().path
        var probe = out
        while !fm.fileExists(atPath: probe.path) {
            let parent = probe.deletingLastPathComponent()
            if parent.path == probe.path { break }   // reached "/", stop
            probe = parent
        }
        let canonical = probe.standardizedFileURL.resolvingSymlinksInPath().path
        guard canonical == canonicalRoot || canonical.hasPrefix(canonicalRoot + "/") else {
            return nil
        }
        return out
    }

    private static func writeEntry(_ entry: Entry, from handle: FileHandle, to out: URL) throws {
        // The local header repeats name/extra lengths and they may differ from
        // the central directory's, so the payload offset must come from there.
        try handle.seek(toOffset: UInt64(entry.localHeaderOffset))
        guard let header = try handle.read(upToCount: 30), header.count == 30 else {
            throw ExtractError.truncated
        }
        let nameLen = Int(readU16(header, 26))
        let extraLen = Int(readU16(header, 28))
        try handle.seek(toOffset: UInt64(entry.localHeaderOffset + 30 + nameLen + extraLen))

        FileManager.default.createFile(atPath: out.path, contents: nil)
        let sink = try FileHandle(forWritingTo: out)
        defer { try? sink.close() }

        if entry.isCompressed {
            // Deflate has to be decoded as a unit, so a compressed entry is
            // bounded by memory. NSFileCoordinator writes stored entries, so in
            // practice the streaming path below is the one that runs for blobs.
            guard let raw = try handle.read(upToCount: entry.compressedSize) else {
                throw ExtractError.truncated
            }
            try sink.write(contentsOf: inflate(raw, expectedSize: entry.uncompressedSize))
            return
        }

        // Stored: copy in 4MB chunks so a 500MB media file never lands in RAM.
        // [T-backup-scan-jetsam] Per-chunk pool — the chunks are autoreleased
        // NSData, so without draining, "streaming" still accumulated the whole
        // member until the outer pool turned.
        var remaining = entry.compressedSize
        while remaining > 0 {
            try autoreleasepool {
                let want = min(remaining, 4 * 1024 * 1024)
                guard let chunk = try handle.read(upToCount: want), !chunk.isEmpty else {
                    throw ExtractError.truncated
                }
                try sink.write(contentsOf: chunk)
                remaining -= chunk.count
            }
        }
    }

    // MARK: - Central directory

    struct Entry {
        let name: String
        let compressedSize: Int
        let uncompressedSize: Int
        let isCompressed: Bool
        let localHeaderOffset: Int
    }

    static func centralDirectory(handle: FileHandle) throws -> [Entry] {
        let fileSize = Int((try? handle.seekToEnd()) ?? 0)
        guard fileSize >= 22 else { throw ExtractError.notAZip }

        let tailLen = min(fileSize, 65_557)
        try handle.seek(toOffset: UInt64(fileSize - tailLen))
        let tail = try handle.read(upToCount: tailLen) ?? Data()

        var eocd = -1
        var i = tail.count - 22
        while i >= 0 {
            if tail[tail.startIndex + i] == 0x50, tail[tail.startIndex + i + 1] == 0x4B,
               tail[tail.startIndex + i + 2] == 0x05, tail[tail.startIndex + i + 3] == 0x06 {
                eocd = i
                break
            }
            i -= 1
        }
        guard eocd >= 0 else { throw ExtractError.notAZip }

        var count = Int(readU16(tail, eocd + 10))
        var cdOffset = Int(readU32(tail, eocd + 16))
        var cdSize = Int(readU32(tail, eocd + 12))

        // ZIP64. The classic EOCD holds 16- and 32-bit fields, so a package
        // over 4 GB (or past 65535 entries) stores `0xFFFF` / `0xFFFFFFFF`
        // sentinels there and the real values in a ZIP64 record.
        //
        // Without this, such a package extracted to garbage: cdOffset read as
        // 0xFFFFFFFF seeks past EOF and the entry loop finds nothing.
        // NSFileCoordinator emits ZIP64 correctly once a backup crosses 4 GB,
        // so those packages exported and uploaded looking healthy and could
        // not be restored — the failure landing at the one moment a backup has
        // to work. The most recent real backup was 3.84 GB, i.e. 4% below the
        // boundary.
        if count == 0xFFFF || cdOffset == 0xFFFF_FFFF || cdSize == 0xFFFF_FFFF {
            // The locator sits immediately before the EOCD and points at the
            // ZIP64 EOCD record.
            let locatorPos = eocd - 20
            guard locatorPos >= 0, readU32(tail, locatorPos) == 0x0706_4B50 else {
                throw ExtractError.notAZip
            }
            let z64Offset = Int(readU64(tail, locatorPos + 8))
            guard z64Offset >= 0, z64Offset + 56 <= fileSize else {
                throw ExtractError.truncated
            }
            try handle.seek(toOffset: UInt64(z64Offset))
            guard let z64 = try handle.read(upToCount: 56), z64.count == 56,
                  readU32(z64, 0) == 0x0606_4B50 else {
                throw ExtractError.notAZip
            }
            count = Int(readU64(z64, 32))
            cdSize = Int(readU64(z64, 40))
            cdOffset = Int(readU64(z64, 48))
        }

        guard cdOffset >= 0, cdSize >= 0, cdOffset + cdSize <= fileSize else {
            throw ExtractError.truncated
        }
        try handle.seek(toOffset: UInt64(cdOffset))
        let cd = try handle.read(upToCount: cdSize) ?? Data()

        var entries: [Entry] = []
        var pos = 0
        for _ in 0..<count {
            guard pos + 46 <= cd.count, readU32(cd, pos) == 0x0201_4B50 else { break }
            let method = readU16(cd, pos + 10)
            var compSize = Int(readU32(cd, pos + 20))
            var uncompSize = Int(readU32(cd, pos + 24))
            let nameLen = Int(readU16(cd, pos + 28))
            let extraLen = Int(readU16(cd, pos + 30))
            let commentLen = Int(readU16(cd, pos + 32))
            var localOffset = Int(readU32(cd, pos + 42))
            let nameStart = cd.startIndex + pos + 46
            let name = String(decoding: cd[nameStart..<(nameStart + nameLen)], as: UTF8.self)

            // Per-entry ZIP64. Each oversized field is `0xFFFFFFFF` here and
            // its true 64-bit value lives in the 0x0001 extra field, packed in
            // a FIXED ORDER and present ONLY for the fields that overflowed —
            // so they must be consumed in sequence, not read at fixed offsets.
            if compSize == 0xFFFF_FFFF || uncompSize == 0xFFFF_FFFF
                || localOffset == 0xFFFF_FFFF {
                var p = pos + 46 + nameLen
                let extraEnd = p + extraLen
                while p + 4 <= extraEnd && p + 4 <= cd.count {
                    let tag = readU16(cd, p)
                    let size = Int(readU16(cd, p + 2))
                    guard tag == 0x0001 else { p += 4 + size; continue }
                    var q = p + 4
                    if uncompSize == 0xFFFF_FFFF, q + 8 <= extraEnd {
                        uncompSize = Int(readU64(cd, q)); q += 8
                    }
                    if compSize == 0xFFFF_FFFF, q + 8 <= extraEnd {
                        compSize = Int(readU64(cd, q)); q += 8
                    }
                    if localOffset == 0xFFFF_FFFF, q + 8 <= extraEnd {
                        localOffset = Int(readU64(cd, q))
                    }
                    break
                }
            }

            entries.append(Entry(name: name, compressedSize: compSize,
                                 uncompressedSize: uncompSize,
                                 isCompressed: method != 0,
                                 localHeaderOffset: localOffset))
            pos += 46 + nameLen + extraLen + commentLen
        }
        return entries
    }

    private static func inflate(_ data: Data, expectedSize: Int) throws -> Data {
        let cap = max(expectedSize, 1)
        var out = Data(count: cap)
        let written: Int = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src -> Int in
                guard let d = dst.bindMemory(to: UInt8.self).baseAddress,
                      let s = src.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_decode_buffer(d, cap, s, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0 else { throw ExtractError.unsupportedCompression(8) }
        out.removeSubrange(written...)
        return out
    }

    private static func readU16(_ d: Data, _ off: Int) -> UInt16 {
        let i = d.startIndex + off
        guard i + 1 < d.endIndex else { return 0 }
        return UInt16(d[i]) | (UInt16(d[i + 1]) << 8)
    }

    private static func readU64(_ d: Data, _ off: Int) -> UInt64 {
        let i = d.startIndex + off
        guard i + 7 < d.endIndex else { return 0 }
        var v: UInt64 = 0
        for b in (0..<8).reversed() { v = (v << 8) | UInt64(d[i + b]) }
        return v
    }

    private static func readU32(_ d: Data, _ off: Int) -> UInt32 {
        let i = d.startIndex + off
        guard i + 3 < d.endIndex else { return 0 }
        return UInt32(d[i]) | (UInt32(d[i + 1]) << 8)
             | (UInt32(d[i + 2]) << 16) | (UInt32(d[i + 3]) << 24)
    }
}
