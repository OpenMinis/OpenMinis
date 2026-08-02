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

    /// Decompress a gzip member using a streaming Compression decode. Streaming
    /// is required because a rootfs can expand many times over; a single-shot
    /// compression_decode_buffer would fail if the output buffer were too small.
    private static func gunzip(_ data: Data) throws -> Data {
        var output = Data()
        let streamOp = COMPRESSION_STREAM_DECODE
        let alg: compression_algorithm = COMPRESSION_ZLIB
        let result = data.withUnsafeBytes { (srcRaw: UnsafeRawBufferPointer) -> Int in
            guard let srcBase = srcRaw.baseAddress else { return -1 }
            var stream = compression_stream(dst_ptr: nil,
                                            dst_size: 0,
                                            src_ptr: srcBase.assumingMemoryBound(to: UInt8.self),
                                            src_size: data.count)
            let initStatus = compression_stream_init(&stream, streamOp, alg)
            guard initStatus != COMPRESSION_STATUS_ERROR else { return -1 }
            defer { compression_stream_destroy(&stream) }

            let window = 1 << 16
            var buffer = [UInt8](repeating: 0, count: window)
            var status: compression_status = COMPRESSION_STATUS_OK
            while status == COMPRESSION_STATUS_OK {
                let written = buffer.withUnsafeMutableBytes { (dstPtr: UnsafeMutableRawBufferPointer) -> Int in
                    stream.dst_ptr = dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self)
                    stream.dst_size = window
                    status = compression_stream_process(&stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                    return window - Int(stream.dst_size)
                }
                if written > 0 { output.append(buffer, count: written) }
                if status == COMPRESSION_STATUS_END { break }
            }
            return output.count
        }
        guard result > 0 else { throw RootfsArchiveError.inflateFailed }
        return output
    }
}
