package com.openminis.app.mcp.server.tools

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTransferCodecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun chunkedEncodingMatchesOneShotBase64AndHash() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val file = tmp.newFile("payload.bin").apply { writeBytes(bytes) }
        val encoded = FileTransferCodec.encode(file, chunkBytes = 64 * 1024)

        assertEquals(bytes.size.toLong(), encoded.size)
        assertArrayEquals(bytes, Base64.getDecoder().decode(encoded.base64))
        assertEquals(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) },
            encoded.sha256,
        )
    }

    @Test
    fun emptyFileEncodesToEmptyPayload() {
        val file = tmp.newFile("empty.bin")
        val encoded = FileTransferCodec.encode(file)
        assertEquals(0L, encoded.size)
        assertEquals("", encoded.base64)
    }
}
