import Compression
import CryptoKit
import Foundation
import zlib

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
    ///
    /// [T-backup-zip-stored-only] Every entry is STORED. Nothing here deflates.
    ///
    /// Both ends of a backup are phones. Compression buys package size and
    /// charges CPU twice — once to deflate on the exporting device, once to
    /// inflate on every restore — and on this workload the trade is bad in
    /// both directions:
    ///
    ///   * Restoring a 2.65 GB iPhone package on Android ran 7-8 minutes at
    ///     ~8 MB/s, single-core-bound in inflate, for 0.99 GB of saving.
    ///   * A backup is written once and read rarely. Size is paid to disk and
    ///     to the upload; time is paid by a user watching a progress bar.
    ///
    /// STORED also makes both halves cheap in memory, which matters more than
    /// bytes on a phone: a stored entry is a plain copy at both ends, so
    /// neither writer nor reader ever holds a member and its compressed twin at
    /// once. That removes the map-whole-file hazard the old deflate path
    /// carried (it read the member with `.mappedIfSafe` and then held its
    /// compressed copy, ~2x resident, uncapped), and on the reading side
    /// removes the single-allocation inflate in `BackupZipExtractor` and in
    /// Android's `BackupZip`.
    ///
    /// The cost is real and accepted: the compressible share (jsonl, db, text)
    /// no longer shrinks, so packages grow by roughly the old compressible
    /// gain. Measured on a corpus built to a real package's profile, blanket
    /// STORED wrote 468.6 MB in 0.73s where content-sniffed deflate wrote
    /// 195.3 MB in 2.24s — 2.4x the bytes, 3.1x faster, and no inflate at all
    /// on restore.
    ///
    /// `deflate` and the method-16-bit plumbing stay in place: the READER must
    /// still handle method 8, because packages written by older builds (and by
    /// the NSFileCoordinator path) contain deflated entries forever.
    @discardableResult
    func addFile(at source: URL, name: String) throws -> Bool {
        guard !writtenNames.contains(name) else { return false }
        try addStored(source: source, name: name)
        writtenNames.insert(name)
        return true
    }

    /// Append many files, computing their CRCs concurrently.
    ///
    /// [T-backup-zip-parallel-pack] ZIP entries must land in a single ordered
    /// stream, so the WRITE stays sequential — what parallelises is everything
    /// before it. With every member STORED the remaining per-member cost is a
    /// CRC pass plus a copy, and the CRC is pure CPU over the whole file: on a
    /// 260 MB / 2,000-entry corpus, hashing sequentially ran 478 MB/s against
    /// 3,669 MB/s across 12 cores — a 7.7x difference, and the reason a
    /// 23,000-entry package spent its time on one core.
    ///
    /// So: hash concurrently, write in order. Results are collected into a
    /// dictionary and then drained in the caller's order, which keeps the
    /// package byte-identical to what the sequential path produces — the
    /// resume walk depends on that order, and a reproducible package is worth
    /// keeping anyway.
    ///
    /// Each worker opens its own FileHandle and reads into its own buffer;
    /// nothing is shared but the results dictionary, which is lock-guarded.
    /// (Android hit a GC storm sharing one 4 MB buffer across operations — the
    /// per-thread buffer here is the same lesson applied up front.)
    ///
    /// Falls back to the sequential path for small batches, where the
    /// dispatch overhead would cost more than the hashing saves.
    func addFiles(_ items: [(source: URL, name: String)]) throws {
        let pending = items.filter { !writtenNames.contains($0.name) }
        guard pending.count >= Self.parallelBatchThreshold else {
            for item in pending { try addFile(at: item.source, name: item.name) }
            return
        }

        // Phase 1 — concurrent: CRC + size, the CPU-bound part.
        var digests: [String: (crc: UInt32, size: UInt64)] = [:]
        var firstError: Error?
        let lock = NSLock()
        let workers = min(ProcessInfo.processInfo.activeProcessorCount,
                          Self.maxPackWorkers)
        DispatchQueue.concurrentPerform(iterations: workers) { slot in
            var localCRC: [String: (crc: UInt32, size: UInt64)] = [:]
            var localError: Error?
            var index = slot
            while index < pending.count {
                let item = pending[index]
                index += workers
                // [T-backup-scan-jetsam] Same per-item pool as addStored: the
                // reads hand back autoreleased NSData, and 23,000 of them
                // accumulate without draining.
                autoreleasepool {
                    do {
                        let size = (try? FileManager.default.attributesOfItem(
                            atPath: item.source.path)[.size] as? UInt64) ?? 0
                        localCRC[item.name] = (try Self.crc32OfFile(at: item.source), size)
                    } catch {
                        if localError == nil { localError = error }
                    }
                }
            }
            lock.lock()
            digests.merge(localCRC) { a, _ in a }
            if let localError, firstError == nil { firstError = localError }
            lock.unlock()
        }
        if let firstError { throw firstError }

        // Phase 2 — sequential: headers and payloads, in the caller's order.
        for item in pending {
            guard !writtenNames.contains(item.name) else { continue }
            guard let d = digests[item.name] else {
                // Hashing skipped it (unreadable); let the single-file path
                // surface the real error rather than writing a bad entry.
                try addFile(at: item.source, name: item.name)
                continue
            }
            var intact = true
            try writeEntry(name: item.name, method: 0, crc: d.crc,
                           uncompressedSize: d.size, compressedSize: d.size) { h in
                intact = try Self.copyContents(of: item.source, into: h, expected: d.size)
            }
            if !intact {
                logger.warning("[Backup] '\(item.name)' shrank between hashing and packaging — entry padded to its declared size; it will fail CRC on restore")
            }
            writtenNames.insert(item.name)
        }
    }

    /// Below this many members, `concurrentPerform` costs more than it saves.
    private static let parallelBatchThreshold = 16

    /// Cap on hashing workers. Beyond a handful the run is bound by storage,
    /// not by cores, and every extra worker is another live read buffer on a
    /// device that is also holding the export's staging tree.
    private static let maxPackWorkers = 8

    /// Append in-memory bytes (manifests, indexes — always small).
    ///
    /// [T-backup-zip-stored-only] STORED like everything else. These members
    /// ARE compressible, but they are also the small ones — the manifest and
    /// the indexes — so deflating them buys kilobytes against a package
    /// measured in gigabytes, while adding an inflate step to the part of the
    /// restore that runs before anything else can start.
    @discardableResult
    func addData(_ data: Data, name: String) throws -> Bool {
        guard !writtenNames.contains(name) else { return false }
        let crc = Self.crc32(data)
        try writeEntry(name: name, method: 0, crc: crc,
                       uncompressedSize: UInt64(data.count),
                       compressedSize: UInt64(data.count)) { h in
            try h.write(contentsOf: data)
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
        var intact = true
        try writeEntry(name: name, method: 0, crc: crc,
                       uncompressedSize: size, compressedSize: size) { h in
            intact = try Self.copyContents(of: source, into: h, expected: size)
        }
        if !intact {
            logger.warning("[Backup] '\(name)' shrank while being packaged — entry padded to its declared size; it will fail CRC on restore")
        }
    }

    /// Stream a member's bytes into the package, writing EXACTLY `expected`
    /// bytes — truncating a file that grew, zero-padding one that shrank.
    ///
    /// Shared by the single-file and batch paths so both copy identically —
    /// the batch path differs only in having hashed ahead of time.
    ///
    /// [T-backup-zip-parallel-pack] The padding is not defensive noise; it is
    /// what keeps a corrupt archive impossible. A local header states the
    /// entry's size before its payload is written, and `writeEntry` advances
    /// the running `offset` by that stated size. If the file on disk changed
    /// between being measured and being copied, a raw copy would write a
    /// different number of bytes than the header promised, `offset` would
    /// desync, and EVERY later entry's recorded local-header offset — the
    /// thing the central directory is made of — would point into the middle
    /// of the preceding member. One racing file would corrupt the whole
    /// package from that point on, silently.
    ///
    /// This race is inherent to hashing and copying as separate passes, which
    /// the single-file path has always done too; splitting them across phases
    /// only widens the window. It is real here: the backup reads the user's
    /// ORIGINAL files (`BackupBlobStore` streams straight from them, making no
    /// copy), so an app writing to a file mid-export is an ordinary event, not
    /// a pathological one.
    ///
    /// Bounding the copy confines the damage to the one entry, which then
    /// fails its CRC on restore and is reported as a damaged member — a
    /// legible, per-file failure instead of an archive that unzips into
    /// garbage.
    @discardableResult
    private static func copyContents(of source: URL, into h: FileHandle,
                                     expected: UInt64) throws -> Bool {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        var remaining = expected
        while remaining > 0 {
            // [T-backup-scan-jetsam] Per-chunk pool: FileHandle.read hands
            // back autoreleased NSData, so without draining, a "streaming"
            // copy still accumulates the whole file.
            let done: Bool = try autoreleasepool {
                let want = Int(min(remaining, 4 * 1024 * 1024))
                guard let chunk = try input.read(upToCount: want), !chunk.isEmpty else {
                    return true
                }
                try h.write(contentsOf: chunk)
                remaining -= UInt64(chunk.count)
                return false
            }
            if done { break }
        }
        guard remaining > 0 else { return true }
        // The file shrank. Pad so the payload still matches the header.
        var pad = remaining
        while pad > 0 {
            let n = Int(min(pad, 4 * 1024 * 1024))
            try autoreleasepool { try h.write(contentsOf: Data(count: n)) }
            pad -= UInt64(n)
        }
        return false
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

    /// [T-backup-zip-hw-crc] CRC32 via zlib rather than a hand-rolled table.
    ///
    /// ZIP requires the CRC of every member, so this runs over every byte of
    /// every blob — and it was, by a wide margin, the most expensive thing in
    /// the packer. Measured on a 260 MB corpus: the whole per-blob cost was
    /// 251 MB/s, of which SHA-256 (algorithmically the heavier function) was
    /// only 14% at 1,847 MB/s. The other 86% was this CRC.
    ///
    /// The reason is not the algorithm, it is the implementation: CryptoKit's
    /// SHA-256 uses the ARM64 crypto extensions, while the byte-at-a-time
    /// table loop below could not. zlib's crc32 is vectorised and uses the
    /// hardware CRC instruction the device advertises (`FEAT_CRC32`); measured
    /// here at 11,504 MB/s against roughly 290 MB/s for the table — about 40x.
    ///
    /// No new dependency: libz is already linked (the package format's deflate
    /// support comes from it), and it is a system library on every Apple
    /// platform. Values are bit-identical — verified against the old
    /// implementation on empty, single-byte, text, all-zero and random inputs,
    /// and chunked accumulation verified equal to one-shot, which is what the
    /// streaming file path relies on.
    ///
    /// `zCRC32` is spelled differently from zlib's global `crc32` on purpose:
    /// this type also declares `crc32(_:)`, and an unqualified call inside the
    /// type would resolve to the member and recurse.
    private static func zCRC32(_ seed: uLong, _ data: Data) -> uLong {
        guard !data.isEmpty else { return seed }
        return data.withUnsafeBytes { buf -> uLong in
            guard let base = buf.bindMemory(to: Bytef.self).baseAddress else { return seed }
            return zlib.crc32(seed, base, uInt(buf.count))
        }
    }

    static func crc32(_ data: Data) -> UInt32 {
        UInt32(zCRC32(0, data))
    }

    static func crc32OfFile(at url: URL) throws -> UInt32 {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        // zlib's crc32 accumulates across calls, so the streaming shape is
        // unchanged — chunked equals one-shot (verified). Still 4 MB chunks in
        // a per-chunk pool: [T-backup-scan-jetsam], FileHandle.read hands back
        // autoreleased NSData and a "streaming" read without draining still
        // accumulates the whole file.
        var c: uLong = 0
        while true {
            let done: Bool = try autoreleasepool {
                guard let chunk = try h.read(upToCount: 4 * 1024 * 1024),
                      !chunk.isEmpty else { return true }
                c = zCRC32(c, chunk)
                return false
            }
            if done { break }
        }
        return UInt32(c)
    }
}
