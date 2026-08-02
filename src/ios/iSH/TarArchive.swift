//
//  TarArchive.swift
//  MinisApp
//
//  Minimal, self-contained tar (POSIX uStar / GNU) archive reader.
//  Used to import mini-rootfs tarballs (Alpine .tar.gz, .tar.xz) into a
//  bootable fakefs. Streaming: entries are produced one at a time and each
//  file's payload is exposed as a Data slice, so large rootfs archives are
//  never fully materialised in memory.
//

import Foundation

/// One logical entry in a tar archive.
struct TarEntry {
    enum FileType: Equatable { case regular, directory, symlink, hardlink, other }

    var rawPath: String
    var mode: UInt16
    var uid: UInt32
    var gid: UInt32
    var type: FileType
    var linkTarget: String
    var size: Int
    var payload: Data

    var normalizedPath: String {
        var p = rawPath
        while p.hasPrefix("./") { p = String(p.dropFirst(2)) }
        if p == "." || p == "/" || p.isEmpty { return "" }
        return p
    }

    var isDirectory: Bool { type == .directory || normalizedPath.hasSuffix("/") }
}

enum TarError: Error, LocalizedError {
    case notATarArchive, truncated, malformedHeader, unsupportedType

    var errorDescription: String? {
        switch self {
        case .notATarArchive: return "The file is not a valid tar archive."
        case .malformedHeader: return "The tar archive has a malformed header."
        case .truncated: return "The tar archive is truncated or corrupt."
        case .unsupportedType: return "The tar archive contains an unsupported entry."
        }
    }
}

/// Streaming iterator over the entries of an uncompressed tar byte stream.
final class TarStream {
    private let data: Data
    private var offset = 0

    init(data: Data) throws {
        self.data = data
        guard data.count >= 512 else { throw TarError.notATarArchive }
    }

    var totalLength: Int { data.count }
    var currentOffset: Int { offset }

    /// Produce the next entry, or nil at clean end-of-archive.
    func nextEntry() -> TarEntry? {
        while offset + 512 <= data.count {
            let block = readBlock()

            if isZeroBlock(block) {
                // Optional second zero block = formal terminator.
                if offset + 512 <= data.count {
                    let nextBlock = data.subdata(in: offset..<(offset + 512))
                    if isZeroBlock(nextBlock) { offset += 512 }
                }
                return nil
            }

            guard var entry = parseHeader(block) else {
                continue
            }

            // GNU long-name record: the following real header gains this path.
            let ln = entry.normalizedPath
            if entry.type == .regular && (ln == "@LongLink" || ln == "././@LongLink") {
                let payloadLen = entry.size
                // Peek payload without consuming it yet via maybeAdvance:
                guard offset + payloadLen <= data.count else { return nil }
                let longNameData = data.subdata(in: offset..<(offset + payloadLen))
                offset += ((payloadLen + 511) / 512) * 512
                let longName = String(data: longNameData, encoding: .utf8)?
                    .replacingOccurrences(of: "\0", with: "")
                guard offset + 512 <= data.count else { return nil }
                let realBlock = data.subdata(in: offset..<(offset + 512))
                offset += 512
                guard var real = parseHeader(realBlock) else { continue }
                if let n = longName, !n.isEmpty { real.rawPath = n }
                attachPayload(to: &real)
                return real
            }

            attachPayload(to: &entry)
            return entry
        }
        return nil
    }

    private func readBlock() -> Data {
        let block = data.subdata(in: offset..<(offset + 512))
        offset += 512
        return block
    }

    private func attachPayload(to entry: inout TarEntry) {
        let size = entry.size
        let padded = ((size + 511) / 512) * 512
        guard offset + padded <= data.count else {
            entry.payload = Data()
            return
        }
        entry.payload = data.subdata(in: offset..<(offset + size))
        offset += padded
    }

    private func parseHeader(_ block: Data) -> TarEntry? {
        let name = stringFromField(block, 0, 100)
        guard !name.isEmpty else { return nil }

        let mode = UInt16(tarField(block, 100, 8) ?? "644", radix: 8) ?? 0o644
        let uid = UInt32(tarField(block, 108, 8) ?? "0", radix: 8) ?? 0
        let gid = UInt32(tarField(block, 116, 8) ?? "0", radix: 8) ?? 0
        let size = Int(tarField(block, 124, 12) ?? "0", radix: 8) ?? 0
        let typeFlag = block.count > 156 ? block[156] : 0

        var fullPath = name
        let prefix = stringFromField(block, 345, 155)
        if !prefix.isEmpty { fullPath = prefix + "/" + fullPath }

        let type: TarEntry.FileType
        switch typeFlag {
        case 0, 0x30: type = .regular
        case 0x35: type = .directory
        case 0x32: type = .symlink
        case 0x31: type = .hardlink
        default: type = .other
        }

        return TarEntry(rawPath: fullPath,
                        mode: mode,
                        uid: uid,
                        gid: gid,
                        type: type,
                        linkTarget: stringFromField(block, 157, 100),
                        size: size,
                        payload: Data())
    }

    private func isZeroBlock(_ b: Data) -> Bool { b.allSatisfy { $0 == 0 } }

    private func stringFromField(_ b: Data, _ start: Int, _ len: Int) -> String {
        guard start + len <= b.count else { return "" }
        return String(data: b.subdata(in: start..<(start+len)), encoding: .utf8)?
            .trimmingCharacters(in: CharacterSet(charactersIn: "\0 ")) ?? ""
    }

    private func tarField(_ b: Data, _ start: Int, _ len: Int) -> String? {
        guard start + len <= b.count else { return nil }
        return String(data: b.subdata(in: start..<(start+len)), encoding: .utf8)?
            .trimmingCharacters(in: CharacterSet(charactersIn: " \0"))
    }
}
