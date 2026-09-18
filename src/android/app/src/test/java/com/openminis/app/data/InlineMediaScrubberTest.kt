package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [GH#352] A 1MB PNG inlined as text/base64 was measured at ~960k tokens and
 * permanently 400'd the session. These tests lock the detector + rewrite so
 * that blob never reaches the provider as text.
 */
class InlineMediaScrubberTest {

    @Test
    fun dataUriPngIsExtractedAndRemovedFromText() {
        val png = fakePng(6_000)
        val b64 = Base64.getEncoder().encodeToString(png)
        val text = "here is a shot data:image/png;base64,$b64 thanks"
        val result = InlineMediaScrubber.scrub(text)
        assertTrue(result.changed)
        assertFalse(result.text.contains(b64.take(40)))
        assertTrue(result.text.contains("inline image extracted"))
        assertTrue(result.text.startsWith("here is a shot "))
        assertTrue(result.text.endsWith(" thanks"))
        assertEquals(1, result.images.size)
        assertEquals("image/png", result.images[0].mimeType)
        assertEquals(png.size, result.images[0].bytes.size)
        assertEquals(0x89.toByte(), result.images[0].bytes[0])
    }

    @Test
    fun rawPngBase64WithoutDataUriIsStripped() {
        val png = fakePng(12_000)
        val b64 = Base64.getEncoder().encodeToString(png)
        assertTrue(b64.length >= InlineMediaScrubber.MIN_RAW_BLOB_CHARS)
        val result = InlineMediaScrubber.scrub("ignore data: $b64 reply OK")
        assertTrue(result.changed)
        assertFalse(result.text.contains("iVBORw0KGgo"))
        assertTrue(result.text.endsWith(" reply OK"))
        assertEquals(1, result.images.size)
        assertEquals("image/png", result.images[0].mimeType)
    }

    @Test
    fun longHexDumpIsNotTreatedAsAnImage() {
        val hex = "0123456789ABCDEF".repeat(600)
        assertTrue(hex.length > InlineMediaScrubber.MIN_RAW_BLOB_CHARS)
        val result = InlineMediaScrubber.scrub(hex)
        assertFalse(result.changed)
        assertEquals(hex, result.text)
    }

    @Test
    fun shortBase64IsLeftAlone() {
        val tiny = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val text = "icon: $tiny"
        val result = InlineMediaScrubber.scrub(text)
        assertFalse(result.changed)
        assertEquals(text, result.text)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun jpegMagicSniff() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", InlineMediaScrubber.sniffImageMime(jpeg))
        assertEquals("image/png", InlineMediaScrubber.sniffImageMime(fakePng(16)))
    }

    @Test
    fun forceOffloadIgnoresProtectedTailBombs() {
        assertFalse(ContextOffload.isForceOffloadContent("hello"))
        assertTrue(ContextOffload.isForceOffloadContent("x".repeat(ContextOffload.HARD_OFFLOAD_CHARS)))
        val stub = ContextOffload.stub(10, 100, "/var/minis/offloads/tools/x.txt")
        assertTrue(stub.length < ContextOffload.HARD_OFFLOAD_CHARS)
        assertFalse(ContextOffload.isForceOffloadContent(stub))
        val wrapped = "[/var/minis/offloads/tools/x.txt | 40000 bytes | 1 lines | showing 1-1 of 1]\n" +
            stub + "x".repeat(ContextOffload.HARD_OFFLOAD_CHARS)
        assertFalse(ContextOffload.isForceOffloadContent(wrapped))
    }

    @Test
    fun previewNeverReturnsTheBlob() {
        val png = fakePng(8_000)
        val b64 = Base64.getEncoder().encodeToString(png)
        val preview = InlineMediaScrubber.preview("data:image/png;base64,$b64")
        assertFalse(preview.contains(b64.take(32)))
        assertTrue(preview.contains("inline image extracted"))
    }

    private fun fakePng(size: Int): ByteArray {
        val bytes = ByteArray(size.coerceAtLeast(8))
        bytes[0] = 0x89.toByte()
        bytes[1] = 0x50
        bytes[2] = 0x4E
        bytes[3] = 0x47
        bytes[4] = 0x0D
        bytes[5] = 0x0A
        bytes[6] = 0x1A
        bytes[7] = 0x0A
        for (i in 8 until bytes.size) bytes[i] = (i % 251).toByte()
        return bytes
    }
}
