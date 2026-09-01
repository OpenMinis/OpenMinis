import Compression
import Foundation

/// Minimal read-side access to a `.minisbak` archive.
///
/// Scope is deliberately narrow: list entry names, and pull ONE small entry
/// (manifest / index) by name. It exists so stage 1's export can be verified —
/// the real importer is a later task and will need streaming extraction of
/// large blobs, which this does not attempt.
///
/// Why not reuse `SkillStore.readZipEntries`: that one decodes the entire
/// archive into `[ZipEntry]` in memory, which is right for a small skill bundle
/// and wrong for a backup that can run to gigabytes. This reads the central
/// directory by seeking, so listing a 2GB package costs a few KB.
enum BackupPackageReader {

    struct Entry {
        let name: String
        let compressedSize: Int
        let uncompressedSize: Int
        let isCompressed: Bool
        let localHeaderOffset: Int
    }

    enum ReaderError: LocalizedError {
        case notAZip
        case unsupportedCompression(UInt16)
        case entryNotFound(String)
        case truncated

        var errorDescription: String? {
            switch self {
            case .notAZip: return "Not a ZIP archive (no end-of-central-directory record)"
            case .unsupportedCompression(let m): return "Unsupported ZIP compression method \(m)"
            case .entryNotFound(let n): return "Entry not found in package: \(n)"
            case .truncated: return "Package is truncated"
            }
        }
    }

    /// Entry names, read from the central directory only.
    static func listEntries(at url: URL) throws -> [String] {
        try readCentralDirectory(at: url).map(\.name)
    }

    /// Read a single entry's bytes.
    ///
    /// `name` is matched on suffix as well as equality, because
    /// `NSFileCoordinator(.forUploading)` wraps the staging directory in an
    /// outer folder — entries arrive as `minisbak-<uuid>/manifest.json`, not
    /// bare `manifest.json`.
    static func readEntry(at url: URL, named name: String) throws -> Data? {
        let entries = try readCentralDirectory(at: url)
        guard let entry = entries.first(where: { $0.name == name || $0.name.hasSuffix("/" + name) })
        else { return nil }

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        // Local file header: the name/extra field lengths there can differ from
        // the central directory's, so the data offset must be computed from the
        // LOCAL header rather than assumed.
        try handle.seek(toOffset: UInt64(entry.localHeaderOffset))
        guard let header = try handle.read(upToCount: 30), header.count == 30 else {
            throw ReaderError.truncated
        }
        let nameLen = Int(readU16(header, 26))
        let extraLen = Int(readU16(header, 28))
        try handle.seek(toOffset: UInt64(entry.localHeaderOffset + 30 + nameLen + extraLen))
        guard let raw = try handle.read(upToCount: entry.compressedSize) else {
            throw ReaderError.truncated
        }

        if !entry.isCompressed { return raw }
        // Deflate — NSFileCoordinator's zips are typically stored, but handle
        // the compressed case so this doesn't silently return garbage.
        return try inflate(raw, expectedSize: entry.uncompressedSize)
    }

    // MARK: - Central directory

    /// Delegates to `BackupZipExtractor`, which parses the same structure.
    ///
    /// This used to be a second, byte-identical copy of that parser — and when
    /// ZIP64 support was added, the copy here was the one that mattered most:
    /// this type reads the MANIFEST, so a >4 GB package was rejected as "not a
    /// ZIP" before a restore could even begin. Two hand-rolled parsers of the
    /// same format will drift again, so there is now one.
    private static func readCentralDirectory(at url: URL) throws -> [Entry] {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        do {
            return try BackupZipExtractor.centralDirectory(handle: handle).map {
                Entry(name: $0.name, compressedSize: $0.compressedSize,
                      uncompressedSize: $0.uncompressedSize,
                      isCompressed: $0.isCompressed,
                      localHeaderOffset: $0.localHeaderOffset)
            }
        } catch {
            // Keep this type's own error vocabulary: callers (and the rescue
            // path that falls back to a forward scan) branch on it.
            throw ReaderError.notAZip
        }
    }

    // MARK: - Inflate

    // MARK: - Forward scan (damaged central directory)

    /// One member recovered by scanning local file headers.
    struct ScannedEntry {
        let name: String
        let data: Data?
        /// Set when the member was located but its bytes could not be read
        /// (truncated payload, unsupported compression).
        let problem: String?
    }

    /// Recover what can be read by scanning `PK\03\04` local headers from the
    /// front of the file.
    ///
    /// Every other read path in this type goes through the central directory,
    /// which lives at the very END of a ZIP — so a truncated package (the most
    /// common real damage, and what a failed upload or a full disk produces)
    /// makes all of them fail at once. That is exactly the case rescue exists
    /// for, so it cannot depend on the structure most likely to be missing.
    ///
    /// ZIP is well suited to this: each member is stored independently and
    /// begins with a magic number, so the archive can be walked front-to-back
    /// with no index at all.
    ///
    /// Deliberately best-effort — it returns what it can and records why the
    /// rest failed, rather than throwing. A partial answer is the entire point.
    ///
    /// `maxBytes` caps how much is read per member so a corrupt length field
    /// cannot make this allocate wildly.
    static func forwardScan(at url: URL, maxBytes: Int = 8 * 1024 * 1024) -> [ScannedEntry] {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return [] }
        defer { try? handle.close() }
        guard let whole = try? handle.readToEnd() else { return [] }

        var out: [ScannedEntry] = []
        var i = 0
        let magic: [UInt8] = [0x50, 0x4B, 0x03, 0x04]   // PK\03\04

        while i + 30 <= whole.count {
            // Find the next local header.
            guard whole[i] == magic[0], whole[i + 1] == magic[1],
                  whole[i + 2] == magic[2], whole[i + 3] == magic[3] else {
                i += 1
                continue
            }
            let header = whole.subdata(in: i..<(i + 30))
            let flags = readU16(header, 6)
            let method = readU16(header, 8)
            let compSize = Int(readU32(header, 18))
            let uncompSize = Int(readU32(header, 22))
            let nameLen = Int(readU16(header, 26))
            let extraLen = Int(readU16(header, 28))

            let nameStart = i + 30
            guard nameLen > 0, nameStart + nameLen <= whole.count else { break }
            let name = String(decoding: whole.subdata(in: nameStart..<(nameStart + nameLen)),
                              as: UTF8.self)
            let dataStart = nameStart + nameLen + extraLen

            // Streamed member: general-purpose flag bit 3 means the sizes were
            // not known when the header was written, so both are 0 here and the
            // real values sit in a trailing data descriptor — which, in a
            // truncated file, may be gone. This is NOT an edge case: the system
            // zipper (`NSFileCoordinator(.forUploading)`) writes every member
            // this way, so treating a 0 length as "unrecoverable" made the
            // whole forward scan useless on real packages. Verified on device —
            // the first attempt found 8 members and recovered 0 of them.
            //
            // Recovery: the payload runs to just before the next local header
            // (or EOF). That span includes the data descriptor's own 12–16
            // bytes, which the inflater stops short of on its own, so it costs
            // nothing to include them.
            var payloadEnd = dataStart + compSize
            let streamed = (flags & 0x0008) != 0 && compSize == 0
            if streamed {
                payloadEnd = Self.nextLocalHeader(in: whole, after: dataStart) ?? whole.count
            }

            guard payloadEnd > dataStart, payloadEnd <= whole.count else {
                out.append(ScannedEntry(
                    name: name, data: nil,
                    problem: name.hasSuffix("/") ? nil : "payload truncated or size unknown"))
                i = dataStart
                continue
            }
            let available = payloadEnd - dataStart

            if available > maxBytes {
                out.append(ScannedEntry(name: name, data: nil,
                                        problem: "member larger than the scan limit"))
            } else {
                let raw = whole.subdata(in: dataStart..<payloadEnd)
                if method == 0 {
                    out.append(ScannedEntry(name: name, data: raw, problem: nil))
                } else if let inflated = try? inflate(
                    raw, expectedSize: uncompSize > 0 ? uncompSize : max(available * 8, 4096)) {
                    out.append(ScannedEntry(name: name, data: inflated, problem: nil))
                } else {
                    out.append(ScannedEntry(name: name, data: nil,
                                            problem: "could not decompress"))
                }
            }
            i = payloadEnd
        }
        return out
    }

    /// Offset of the next `PK\03\04` at or after `from`, if any.
    private static func nextLocalHeader(in d: Data, after from: Int) -> Int? {
        var j = from
        while j + 4 <= d.count {
            if d[j] == 0x50, d[j + 1] == 0x4B, d[j + 2] == 0x03, d[j + 3] == 0x04 { return j }
            j += 1
        }
        return nil
    }

    private static func inflate(_ data: Data, expectedSize: Int) throws -> Data {
        // Raw deflate stream (no zlib header) — wrap for Compression's zlib
        // decoder via the raw algorithm.
        var out = Data(count: max(expectedSize, 1))
        let written: Int = out.withUnsafeMutableBytes { dst -> Int in
            data.withUnsafeBytes { src -> Int in
                guard let d = dst.bindMemory(to: UInt8.self).baseAddress,
                      let s = src.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_decode_buffer(d, max(expectedSize, 1), s, data.count,
                                                 nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0 else { throw ReaderError.unsupportedCompression(8) }
        out.removeSubrange(written...)
        return out
    }

    // MARK: - Little-endian helpers

    private static func readU16(_ d: Data, _ off: Int) -> UInt16 {
        let i = d.startIndex + off
        guard i + 1 < d.endIndex else { return 0 }
        return UInt16(d[i]) | (UInt16(d[i + 1]) << 8)
    }

    private static func readU32(_ d: Data, _ off: Int) -> UInt32 {
        let i = d.startIndex + off
        guard i + 3 < d.endIndex else { return 0 }
        return UInt32(d[i]) | (UInt32(d[i + 1]) << 8)
             | (UInt32(d[i + 2]) << 16) | (UInt32(d[i + 3]) << 24)
    }
}
