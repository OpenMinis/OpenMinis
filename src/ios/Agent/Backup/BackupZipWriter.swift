import Compression
import CryptoKit
import Foundation

private let logger = AppLogger(category: "Backup")

/// Sequential ZIP writer: appends entries to the package as they are produced.
///
/// ## Why this exists
///
/// Packaging used to hand the whole staging tree to
/// `NSFileCoordinator(.forUploading)`, which builds a complete ZIP in its own
/// temp location and hands it back to be copied out. Three full copies of the
/// data therefore existed at once — staging, the system's ZIP, and the final
/// package — so a 3.84 GB backup needed roughly 11.5 GB of free space, with no
/// free-space check anywhere to say so. A user without the room got a generic
/// I/O error minutes into `Packaging…`.
///
/// Writing the archive ourselves means each member can be appended as soon as
/// it exists, and its temporary copy deleted immediately afterwards.
///
/// No new dependency: the app already hand-rolls ZIP *reading* in three places
/// (iOS ships no ZIP API), and `Compression` provides deflate.
///
/// ## Deliberately no data descriptors
///
/// ZIP allows an entry's CRC and sizes to be deferred to a trailer (flag bit
/// 3). We never do, because resume depends on walking the local-header chain:
/// with sizes deferred a header cannot say how far to jump, so the walk would
/// have to hunt for the next `PK\x03\x04` signature — a byte sequence that
/// occurs by chance inside compressed data. Every entry here is fully measured
/// before its header is written, so the real values go in the header.
///
/// ## ZIP64
///
/// Emitted from the start rather than "once we need it": the bug it prevents
/// is invisible on small packages and appears only on large ones, which are
/// exactly the packages a user cannot afford to lose. See
/// `BackupZipExtractor` for the reader half.
final class BackupZipWriter {

    enum WriteError: LocalizedError {
        case cannotCreate(String)
        case compressionFailed

        var errorDescription: String? {
            switch self {
            case .cannotCreate(let p): return "Couldn't create the package at \(p)"
            case .compressionFailed: return "Compression failed while writing the package"
            }
        }
    }

    /// One entry, as recorded for the central directory.
    private struct Record {
        let name: String
        let crc: UInt32
        let compressedSize: UInt64
        let uncompressedSize: UInt64
        let method: UInt16
        let localHeaderOffset: UInt64
    }

    let url: URL
    private let handle: FileHandle
    private var records: [Record] = []
    private var offset: UInt64 = 0

    /// Entry names already written — the resume set, and dedup for repeated
    /// blobs within one run.
    private(set) var writtenNames: Set<String> = []

    /// Extensions whose contents are already compressed. Deflating them costs
    /// full CPU to save ~0-2%, and a large backup is mostly these: on the last
    /// real run, 29,886 files against 174,905 messages.
    private static let incompressible: Set<String> = [
        "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "avif", "bmp",
        "mp4", "mov", "m4v", "avi", "mkv", "webm",
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav",
        "zip", "gz", "bz2", "xz", "7z", "rar", "pdf",
        "minisbak", "enc",
    ]

    static func shouldStore(name: String) -> Bool {
        incompressible.contains((name as NSString).pathExtension.lowercased())
    }

    // MARK: - Lifecycle

    /// Open a package for writing. Pass `resumingAt` to append to a partial
    /// package (see `resumeState`).
    init(url: URL, resumingAt: UInt64? = nil, existingNames: Set<String> = []) throws {
        self.url = url
        let fm = FileManager.default
        if resumingAt == nil || !fm.fileExists(atPath: url.path) {
            try? fm.removeItem(at: url)
            guard fm.createFile(atPath: url.path, contents: nil) else {
                throw WriteError.cannotCreate(url.path)
            }
        }
        handle = try FileHandle(forWritingTo: url)
        if let resumingAt {
            try handle.truncate(atOffset: resumingAt)
            try handle.seek(toOffset: resumingAt)
            offset = resumingAt
            writtenNames = existingNames
        }
    }

    /// Append a file. `name` is the entry path inside the package.
    ///
    /// Returns false when the name was already present, so callers can treat
    /// "already in the package" and "just added" alike.
    @discardableResult
    func addFile(at source: URL, name: String) throws -> Bool {
        guard !writtenNames.contains(name) else { return false }

        if Self.shouldStore(name: name) {
            try addStored(source: source, name: name)
        } else {
            try addDeflated(source: source, name: name)
        }
        writtenNames.insert(name)
        return true
    }

    /// Append in-memory bytes (manifests, indexes — always small).
    @discardableResult
    func addData(_ data: Data, name: String) throws -> Bool {
        guard !writtenNames.contains(name) else { return false }
        let crc = Self.crc32(data)
        let compressed = Self.shouldStore(name: name) ? nil : Self.deflate(data)
        let payload = compressed ?? data
        let method: UInt16 = compressed == nil ? 0 : 8
        try writeEntry(name: name, method: method, crc: crc,
                       uncompressedSize: UInt64(data.count),
                       compressedSize: UInt64(payload.count)) { h in
            try h.write(contentsOf: payload)
        }
        writtenNames.insert(name)
        return true
    }

    /// Finish the package: central directory + (ZIP64) end records.
    func close() throws {
        let cdStart = offset
        var cd = Data()
        for r in records { cd.append(centralHeader(for: r)) }
        try handle.write(contentsOf: cd)
        offset += UInt64(cd.count)

        let needsZip64 = cdStart >= 0xFFFF_FFFF
            || UInt64(cd.count) >= 0xFFFF_FFFF
            || records.count >= 0xFFFF
            || records.contains { $0.localHeaderOffset >= 0xFFFF_FFFF }

        if needsZip64 {
            var z = Data()
            append32(&z, 0x0606_4B50)                       // ZIP64 EOCD
            append64(&z, 44)                                // size of remainder
            append16(&z, 45); append16(&z, 45)              // made by / needed
            append32(&z, 0); append32(&z, 0)                // disk numbers
            append64(&z, UInt64(records.count))
            append64(&z, UInt64(records.count))
            append64(&z, UInt64(cd.count))
            append64(&z, cdStart)
            append32(&z, 0x0706_4B50)                       // locator
            append32(&z, 0)
            append64(&z, offset)                            // ZIP64 EOCD offset
            append32(&z, 1)                                 // total disks
            try handle.write(contentsOf: z)
            offset += UInt64(z.count)
        }

        var e = Data()
        append32(&e, 0x0605_4B50)
        append16(&e, 0); append16(&e, 0)
        let count16 = UInt16(min(records.count, 0xFFFF))
        append16(&e, count16); append16(&e, count16)
        append32(&e, UInt32(min(UInt64(cd.count), 0xFFFF_FFFF)))
        append32(&e, UInt32(min(cdStart, 0xFFFF_FFFF)))
        append16(&e, 0)                                     // comment length
        try handle.write(contentsOf: e)
        offset += UInt64(e.count)

        try handle.close()
    }

    // MARK: - Entry writing

    /// Stored: no temporary copy needed. Two passes over the source (CRC, then
    /// copy) rather than one pass plus a compressed scratch file — cheaper in
    /// both disk and, for already-compressed media, CPU.
    private func addStored(source: URL, name: String) throws {
        let size = (try? FileManager.default.attributesOfItem(
            atPath: source.path)[.size] as? UInt64) ?? 0
        let crc = try Self.crc32OfFile(at: source)
        try writeEntry(name: name, method: 0, crc: crc,
                       uncompressedSize: size, compressedSize: size) { h in
            let input = try FileHandle(forReadingFrom: source)
            defer { try? input.close() }
            while true {
                // [T-backup-scan-jetsam] Per-chunk pool: FileHandle.read hands
                // back autoreleased NSData, so without draining, a "streaming"
                // copy still accumulates the whole file.
                let done: Bool = try autoreleasepool {
                    guard let chunk = try input.read(upToCount: 4 * 1024 * 1024),
                          !chunk.isEmpty else { return true }
                    try h.write(contentsOf: chunk)
                    return false
                }
                if done { break }
            }
        }
    }

    /// Deflate through a scratch file, so the compressed size is known before
    /// the header is written (no data descriptor — see the type comment).
    /// Only used for compressible members, which in a backup are the small
    /// `.jsonl` records rather than the multi-hundred-MB blobs.
    private func addDeflated(source: URL, name: String) throws {
        let data = try Data(contentsOf: source, options: .mappedIfSafe)
        let crc = Self.crc32(data)
        guard let deflated = Self.deflate(data) else {
            // Incompressible in practice — store it rather than failing.
            try addStored(source: source, name: name)
            return
        }
        try writeEntry(name: name, method: 8, crc: crc,
                       uncompressedSize: UInt64(data.count),
                       compressedSize: UInt64(deflated.count)) { h in
            try h.write(contentsOf: deflated)
        }
    }

    private func writeEntry(name: String, method: UInt16, crc: UInt32,
                            uncompressedSize: UInt64, compressedSize: UInt64,
                            _ body: (FileHandle) throws -> Void) throws {
        let localOffset = offset
        let nameBytes = Array(name.utf8)
        // A member only needs the ZIP64 extra field if one of ITS values
        // overflows, or if it starts past the 4 GB mark.
        let big = uncompressedSize >= 0xFFFF_FFFF || compressedSize >= 0xFFFF_FFFF
            || localOffset >= 0xFFFF_FFFF

        var h = Data()
        append32(&h, 0x0403_4B50)
        append16(&h, big ? 45 : 20)                 // version needed
        append16(&h, 0)                             // flags — never bit 3
        append16(&h, method)
        append16(&h, 0); append16(&h, 0)            // mod time / date
        append32(&h, crc)
        append32(&h, big ? 0xFFFF_FFFF : UInt32(compressedSize))
        append32(&h, big ? 0xFFFF_FFFF : UInt32(uncompressedSize))
        append16(&h, UInt16(nameBytes.count))
        append16(&h, big ? 20 : 0)                  // extra length
        h.append(contentsOf: nameBytes)
        if big {
            // Local headers carry sizes only (no offset field), in the
            // uncompressed-then-compressed order the spec fixes.
            append16(&h, 0x0001); append16(&h, 16)
            append64(&h, uncompressedSize)
            append64(&h, compressedSize)
        }
        try handle.write(contentsOf: h)
        offset += UInt64(h.count)

        try body(handle)
        offset += compressedSize

        records.append(Record(name: name, crc: crc,
                              compressedSize: compressedSize,
                              uncompressedSize: uncompressedSize,
                              method: method, localHeaderOffset: localOffset))
    }

    private func centralHeader(for r: Record) -> Data {
        let nameBytes = Array(r.name.utf8)
        // The central header's extra field carries whichever fields overflow,
        // in the spec's fixed order: uncompressed, compressed, offset.
        var extra = Data()
        if r.uncompressedSize >= 0xFFFF_FFFF { append64(&extra, r.uncompressedSize) }
        if r.compressedSize >= 0xFFFF_FFFF { append64(&extra, r.compressedSize) }
        if r.localHeaderOffset >= 0xFFFF_FFFF { append64(&extra, r.localHeaderOffset) }

        var h = Data()
        append32(&h, 0x0201_4B50)
        append16(&h, 45)                            // version made by
        append16(&h, extra.isEmpty ? 20 : 45)       // version needed
        append16(&h, 0)
        append16(&h, r.method)
        append16(&h, 0); append16(&h, 0)
        append32(&h, r.crc)
        append32(&h, r.compressedSize >= 0xFFFF_FFFF ? 0xFFFF_FFFF : UInt32(r.compressedSize))
        append32(&h, r.uncompressedSize >= 0xFFFF_FFFF ? 0xFFFF_FFFF : UInt32(r.uncompressedSize))
        append16(&h, UInt16(nameBytes.count))
        append16(&h, extra.isEmpty ? 0 : UInt16(extra.count + 4))
        append16(&h, 0)                             // comment
        append16(&h, 0)                             // disk
        append16(&h, 0); append32(&h, 0)            // attrs
        append32(&h, r.localHeaderOffset >= 0xFFFF_FFFF ? 0xFFFF_FFFF : UInt32(r.localHeaderOffset))
        h.append(contentsOf: nameBytes)
        if !extra.isEmpty {
            append16(&h, 0x0001)
            append16(&h, UInt16(extra.count))
            h.append(extra)
        }
        return h
    }

    // MARK: - Primitives

    private func append16(_ d: inout Data, _ v: UInt16) {
        withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) }
    }
    private func append32(_ d: inout Data, _ v: UInt32) {
        withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) }
    }
    private func append64(_ d: inout Data, _ v: UInt64) {
        withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) }
    }

    /// Raw deflate (no zlib wrapper) — what ZIP method 8 expects.
    ///
    /// Returns nil for empty input so the caller STORES it. Returning an empty
    /// `Data` wrote a method-8 entry whose compressed size was 0, and a
    /// zero-byte deflate stream is not valid — `unzip -t` rejected the whole
    /// archive with "invalid compressed data to inflate", and the importer
    /// reported "Archive is truncated". Empty files are not hypothetical here:
    /// `blobs.index.jsonl` is empty for any package with no blobs, which is
    /// every backup of a metadata-only category.
    static func deflate(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        let cap = data.count + 64 * 1024
        var out = Data(count: cap)
        let n: Int = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src in
                guard let d = dst.bindMemory(to: UInt8.self).baseAddress,
                      let s = src.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_encode_buffer(d, cap, s, data.count, nil,
                                                 COMPRESSION_ZLIB)
            }
        }
        guard n > 0, n < data.count else { return nil }   // no gain → store
        out.removeSubrange(n...)
        return out
    }

    // MARK: - CRC32

    private static let crcTable: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 { c = (c & 1) == 1 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
            return c
        }
    }()

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { buf in
            for b in buf.bindMemory(to: UInt8.self) {
                c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8)
            }
        }
        return c ^ 0xFFFF_FFFF
    }

    static func crc32OfFile(at url: URL) throws -> UInt32 {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        var c: UInt32 = 0xFFFF_FFFF
        while true {
            let done: Bool = try autoreleasepool {
                guard let chunk = try h.read(upToCount: 4 * 1024 * 1024),
                      !chunk.isEmpty else { return true }
                chunk.withUnsafeBytes { buf in
                    for b in buf.bindMemory(to: UInt8.self) {
                        c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8)
                    }
                }
                return false
            }
            if done { break }
        }
        return c ^ 0xFFFF_FFFF
    }
}
