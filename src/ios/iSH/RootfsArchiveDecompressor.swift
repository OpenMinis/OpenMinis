//
//  RootfsArchiveDecompressor.swift
//  MinisApp
//
//  Detects and transparently decompresses a tar archive based on the magic
//  bytes of the supplied file, independent of its filename extension. Used by
//  RootfsManager to import .tar.gz / .tgz mini-rootfs tarballs.
//
//  Supported containers:
//    - gzip (RFC 1952)   -> decompressed via the Compression framework
//    - plain .tar        -> passed through
//
//  Apple's Compression framework decodes both the zlib and gzip wrappers for
//  the COMPRESSION_ZLIB algorithm, so a full gzip member (including its
//  header) can be passed straight to compression_decode_buffer.
//

import Foundation
import Compression

enum RootfsArchiveError: Error, LocalizedError {
    case unsupportedFormat
    case readFailed
    case inflateFailed
    case emptyInput

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat: return "Unsupported archive format (only .tar and .tar.gz currently supported)."
        case .readFailed: return "Could not read the archive file."
        case .inflateFailed: return "Failed to decompress the archive."
        case .emptyInput: return "The archive is empty."
        }
    }
}

/// Result of decompressing a rootfs container.
final class DecompressedTarData {
    let data: Data
    let wasCompressed: Bool
    init(data: Data, wasCompressed: Bool) {
        self.data = data
        self.wasCompressed = wasCompressed
    }
}

enum RootfsArchiveDecompressor {

    /// Decompress (if needed) the supplied archive into raw tar bytes.
    static func decompress(url: URL) throws -> DecompressedTarData {
        let raw: Data
        do { raw = try Data(contentsOf: url) }
        catch { throw RootfsArchiveError.readFailed }
        guard !raw.isEmpty else { throw RootfsArchiveError.emptyInput }

        // gzip magic 1f 8b
        if raw.count >= 2, raw[0] == 0x1f, raw[1] == 0x8b {
            return DecompressedTarData(data: try gunzip(raw), wasCompressed: true)
        }
        // xz magic FD 37 7A 58 5A 00
        if raw.count >= 6, raw[0] == 0xFD, raw[1] == 0x37, raw[2] == 0x7A {
            throw RootfsArchiveError.unsupportedFormat
        }
        return DecompressedTarData(data: raw, wasCompressed: false)
    }

    /// Decompress a gzip member. Uses compression_decode_buffer (the same
    /// primitive the app already uses for zip entries), growing the output
    /// buffer until the whole member decodes. COMPRESSION_ZLIB decodes the
    /// gzip wrapper transparently.
    private static func gunzip(_ data: Data) throws -> Data {
        var capacity = max(data.count * 4, 1 << 20) // start generously
        var output = Data(count: capacity)
        var produced = -1

        // Grow the destination buffer until the gzip member fits. Rootfs
        // archives can expand many times, so don't assume a fixed size.
        for _ in 0..<24 {
            let r = output.withUnsafeMutableBytes { dstPtr -> Int in
                data.withUnsafeBytes { srcPtr -> Int in
                    compression_decode_buffer(
                        dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self),
                        capacity,
                        srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self),
                        data.count,
                        nil,
                        COMPRESSION_ZLIB)
                }
            }
            if r > 0 {
                produced = r
                break
            }
            if capacity > (1 << 30) { break }
            output = Data(count: capacity * 2)
            capacity *= 2
        }

        guard produced > 0 else { throw RootfsArchiveError.inflateFailed }
        output.count = produced
        return output
    }
}
