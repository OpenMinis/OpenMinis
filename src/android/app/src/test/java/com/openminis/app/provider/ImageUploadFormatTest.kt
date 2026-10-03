package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.provider.ImageBudget.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-image-upload-format] DeepSeek 400 "You have uploaded an
 * unsupported image" on every turn of a conversation holding a HEIC photo.
 *
 * Reproduced on a Pixel 4a, both paths, request bodies read back from the
 * debug server:
 *  - first send:   data:image/heic;base64,<JPEG bytes ff d8 ff e0 … JFIF> → 400
 *  - after reload: data:image/heic;base64,<HEIC bytes …ftypheic>           → 400
 *  - control:      data:image/jpeg;base64,<JPEG bytes>                      → 200
 * DeepSeek validates the declared MIME, so a correct label matters as much as
 * an accepted format.
 */
class ImageUploadFormatTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() } + ByteArray(24)
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** Exact headers captured from the device requests. */
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
    private val heic = byteArrayOf(0x00, 0x00, 0x00, 0x20) + ascii("ftypheic") + ByteArray(24)
    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val gif = ascii("GIF89a") + ByteArray(24)
    private val webp = ascii("RIFF") + byteArrayOf(0, 0, 0, 0) + ascii("WEBPVP8 ") + ByteArray(24)
    private val avif = byteArrayOf(0x00, 0x00, 0x00, 0x1C) + ascii("ftypavif") + ByteArray(24)
    private val mif1 = byteArrayOf(0x00, 0x00, 0x00, 0x1C) + ascii("ftypmif1") + ByteArray(24)
    private val bmp = ascii("BM") + ByteArray(30)
    private val tiff = ascii("II*\u0000") + ByteArray(30)
    private val junk = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)

    private val transcodedJpeg = jpeg.copyOf().also { it[20] = 7 }

    private var transcodeCalls = 0
    private val okTranscode: (ByteArray) -> ByteArray? = { transcodeCalls++; transcodedJpeg }
    private val failTranscode: (ByteArray) -> ByteArray? = { transcodeCalls++; null }
    private val passBudget: (ByteArray) -> ByteArray = { it }

    private fun prepare(
        input: ByteArray,
        declared: String?,
        transcode: (ByteArray) -> ByteArray? = okTranscode,
        budget: (ByteArray) -> ByteArray = passBudget,
    ) = ImageBudget.prepareForUpload(input, declared, transcode, budget)

    // -- sniffing --

    @Test fun `sniffs every format by its magic bytes`() {
        assertEquals(ImageFormat.JPEG, ImageBudget.sniffFormat(jpeg))
        assertEquals(ImageFormat.PNG, ImageBudget.sniffFormat(png))
        assertEquals(ImageFormat.GIF, ImageBudget.sniffFormat(gif))
        assertEquals(ImageFormat.WEBP, ImageBudget.sniffFormat(webp))
        assertEquals(ImageFormat.HEIF, ImageBudget.sniffFormat(heic))
        assertEquals(ImageFormat.HEIF, ImageBudget.sniffFormat(mif1))
        assertEquals(ImageFormat.AVIF, ImageBudget.sniffFormat(avif))
        assertEquals(ImageFormat.BMP, ImageBudget.sniffFormat(bmp))
        assertEquals(ImageFormat.TIFF, ImageBudget.sniffFormat(tiff))
        assertEquals(ImageFormat.UNKNOWN, ImageBudget.sniffFormat(junk))
        assertEquals(ImageFormat.UNKNOWN, ImageBudget.sniffFormat(ByteArray(0)))
        assertEquals(ImageFormat.UNKNOWN, ImageBudget.sniffFormat(byteArrayOf(0xFF.toByte())))
    }

    @Test fun `only the four formats vision APIs accept are uploadable`() {
        val uploadable = ImageFormat.entries.filter { it.isUploadable }.map { it.mimeType }
        assertEquals(listOf("image/jpeg", "image/png", "image/gif", "image/webp"), uploadable)
    }

    // -- the first-send case: JPEG bytes carrying the HEIC label --

    @Test fun `JPEG bytes declared image-heic go out labeled image-jpeg, bytes untouched`() {
        val out = prepare(jpeg, "image/heic")
        assertNotNull(out)
        assertEquals("image/jpeg", out!!.mimeType)
        assertSame(jpeg, out.bytes)
        assertEquals("accepted formats are never transcoded", 0, transcodeCalls)
    }

    @Test fun `an accepted format is never transcoded`() {
        for ((b, mime) in listOf(jpeg to "image/jpeg", png to "image/png", gif to "image/gif", webp to "image/webp")) {
            transcodeCalls = 0
            val out = prepare(b, "image/*")!!
            assertEquals(mime, out.mimeType)
            assertSame(b, out.bytes)
            assertEquals(0, transcodeCalls)
        }
    }

    // -- the reload case: raw HEIC --

    @Test fun `raw HEIC is transcoded to JPEG and labeled image-jpeg`() {
        val out = prepare(heic, "image/heic")!!
        assertEquals(1, transcodeCalls)
        assertSame(transcodedJpeg, out.bytes)
        assertEquals("image/jpeg", out.mimeType)
    }

    @Test fun `AVIF, BMP and TIFF are transcoded too`() {
        for (b in listOf(avif, bmp, tiff)) {
            assertEquals("image/jpeg", prepare(b, null)!!.mimeType)
        }
    }

    @Test fun `a known-unaccepted format that will not decode becomes the placeholder, not a 400`() {
        assertNull(prepare(heic, "image/heic", transcode = failTranscode))
    }

    @Test fun `unrecognized bytes that will not decode keep the old pass-through`() {
        val out = prepare(junk, "image/png", transcode = failTranscode)!!
        assertSame(junk, out.bytes)
        assertEquals("image/png", out.mimeType)
    }

    @Test fun `empty input sends nothing`() {
        assertNull(prepare(ByteArray(0), "image/jpeg"))
    }

    // -- the byte budget runs after the format gate, and the label follows it --

    @Test fun `a budget re-encode relabels to the budget's output format`() {
        val shrunk = jpeg.copyOf().also { it[21] = 9 }
        val out = prepare(png, "image/png", budget = { shrunk })!!
        assertSame(shrunk, out.bytes)
        assertEquals("image/jpeg", out.mimeType)
    }

    @Test fun `the budget sees the transcoded bytes, not the original`() {
        var seen: ByteArray? = null
        prepare(heic, "image/heic", budget = { seen = it; it })
        assertSame(transcodedJpeg, seen)
    }

    @Test fun `dataUrl is a well-formed single-line data URL`() {
        val url = prepare(png, "image/jpeg")!!.dataUrl()
        assertTrue(url, url.startsWith("data:image/png;base64,iVBORw0KGgo"))
        assertFalse(url.contains('\n'))
    }

    // -- source guards --

    private val providers = listOf(
        "provider/openai/OpenAIProvider.kt",
        "provider/anthropic/AnthropicProvider.kt",
        "provider/gemini/GeminiProvider.kt",
    )

    @Test fun `every provider inlines images only through prepareForUpload`() {
        for (p in providers) {
            val src = ProductionSources.read(p)
            assertTrue("$p must use the gate", src.contains("ImageBudget.prepareForUpload("))
            assertFalse("$p still derives a MIME from the declared type", src.contains("safeMime"))
            assertFalse("$p still calls the bare budget", src.contains("compressUnderBudget("))
        }
    }

    @Test fun `no provider inlines raw stored bytes under their stored MIME`() {
        for (p in providers) {
            val src = ProductionSources.read(p)
            assertFalse("$p", src.contains("Base64.encodeToString(part.data"))
            assertFalse("$p", src.contains("put(\"mimeType\", part.mimeType)"))
        }
    }

    @Test fun `first send labels the part by its encoded bytes`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("val inferenceMime = ImageBudget.sniffFormat(inferenceBytes).mimeType ?: attachment.mimeType"))
        assertTrue(vm.contains("LLMMessage.ImagePart(inferenceBytes, inferenceMime, linuxPath = linuxPath)"))
        assertFalse(vm.contains("LLMMessage.ImagePart(inferenceBytes, attachment.mimeType"))
    }
}
