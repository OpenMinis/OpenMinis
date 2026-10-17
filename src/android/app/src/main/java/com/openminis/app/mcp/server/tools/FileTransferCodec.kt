package com.openminis.app.mcp.server.tools

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * MCP-FT-1: encode a pulled file in chunks instead of `readBytes()` +
 * one-shot base64.
 *
 * The old path held the whole file (up to 8 MB) plus a 4/3 base64 string plus
 * the JSON envelope in memory at once (~30 MB peak). This streams the file in
 * [DEFAULT_CHUNK_BYTES] chunks, hashing and encoding incrementally; the only
 * retained buffer is the final base64 string the protocol still requires.
 */
internal object FileTransferCodec {

    const val DEFAULT_CHUNK_BYTES = 256 * 1024

    data class Encoded(
        val size: Long,
        val sha256: String,
        val base64: String,
    )

    fun encode(file: File, chunkBytes: Int = DEFAULT_CHUNK_BYTES): Encoded {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        val size = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        val estimated = (size * 4 / 3 + 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val out = StringBuilder(estimated)
        // Base64 padding is part of the FINAL quantum only: encoding each
        // chunk independently and concatenating produces `=` mid-stream.
        // `Encoder.wrap` keeps the carry across chunk boundaries.
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                out.append(b.toChar())
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                out.append(String(b, off, len, Charsets.US_ASCII))
            }

            override fun close() = Unit
        }
        val encoder = Base64.getEncoder().wrap(sink)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(chunkBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                encoder.write(buffer, 0, read)
            }
        }
        encoder.close()
        return Encoded(
            size = size,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            base64 = out.toString(),
        )
    }
}
