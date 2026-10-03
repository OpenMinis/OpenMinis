package com.openminis.app.provider

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-image-downscale-parity] DeepSeek 400 "unsupported image" on every
 * turn after a restart, for a 1264×23545 long screenshot stored at 1.6 MB.
 *
 * Reproduced on a Pixel 4a: first send (fitted within 2000 px) → 200; after
 * force-stop + reopen the history replayed the full-size original, which the
 * byte budget does not touch under 3.75 MB → 400. iOS never hits this: it
 * persists the reduced copy it sent and replays that.
 *
 * Fix: first send and reload go through the same ImageBudget.downscaleForModel,
 * the send gate fits anything still oversize, and a note tells the model the
 * original dimensions whenever it gets a reduced copy.
 */
class ImageDownscaleParityTest {

    // -- sizing arithmetic --

    @Test fun `the reporter's long screenshot fits to 107x2000`() {
        assertEquals(Pair(107, 2000), ImageBudget.fitWithin(1264, 23545, 2000))
    }

    @Test fun `landscape and square images scale on their longest side`() {
        assertEquals(Pair(2000, 1500), ImageBudget.fitWithin(4000, 3000, 2000))
        assertEquals(Pair(2000, 2000), ImageBudget.fitWithin(3000, 3000, 2000))
    }

    @Test fun `an image that already fits is left alone`() {
        assertNull(ImageBudget.fitWithin(2000, 1000, 2000))
        assertNull(ImageBudget.fitWithin(1264, 2000, 2000))
        assertNull(ImageBudget.fitWithin(10, 10, 2000))
    }

    @Test fun `unknown or degenerate sizes are not scaled, extreme ratios keep one pixel`() {
        assertNull(ImageBudget.fitWithin(0, 5000, 2000))
        assertNull(ImageBudget.fitWithin(-1, -1, 2000))
        assertEquals(Pair(1, 2000), ImageBudget.fitWithin(1, 100_000, 2000))
    }

    // -- the note --

    private fun downscaled() = ImageBudget.Downscaled(ByteArray(0), "image/jpeg", 1264, 23545, 107, 2000)

    @Test fun `the note states both sizes in W x H and points at the original`() {
        assertEquals(
            "[Image downscaled before sending: the original is 1264×23545 px, shown here at 107×2000 px, " +
                "so fine detail and small text may be unreadable. " +
                "The full-size original is saved at /var/minis/attachments/uploads/shot.jpg.]",
            ImageBudget.downscaleNote(downscaled(), "/var/minis/attachments/uploads/shot.jpg"),
        )
    }

    @Test fun `without a saved path the note just drops that sentence`() {
        assertEquals(
            "[Image downscaled before sending: the original is 1264×23545 px, shown here at 107×2000 px, " +
                "so fine detail and small text may be unreadable.]",
            ImageBudget.downscaleNote(downscaled(), null),
        )
    }

    // -- the send gate: format, then fit, then byte budget --

    private fun jpeg(tag: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), tag.toByte()) + ByteArray(24)

    @Test fun `an oversized accepted image is fitted before the budget sees it`() {
        val tall = jpeg(1)
        val fitted = jpeg(2)
        var budgetSaw: ByteArray? = null
        val out = ImageBudget.prepareForUpload(
            tall, "image/jpeg",
            transcode = { error("accepted formats are never transcoded") },
            budget = { budgetSaw = it; it },
            fit = { if (it === tall) fitted else null },
        )!!
        assertSame(fitted, budgetSaw)
        assertSame(fitted, out.bytes)
        assertEquals("image/jpeg", out.mimeType)
    }

    @Test fun `an image that already fits passes through unchanged`() {
        val small = jpeg(3)
        val out = ImageBudget.prepareForUpload(small, "image/jpeg", { null }, { it }, fit = { null })!!
        assertSame(small, out.bytes)
    }

    @Test fun `a transcoded image is fitted from the transcode, not the source`() {
        val heic = byteArrayOf(0, 0, 0, 0x20) + "ftypheic".toByteArray() + ByteArray(24)
        val transcoded = jpeg(4)
        var fitSaw: ByteArray? = null
        ImageBudget.prepareForUpload(heic, "image/heic", { transcoded }, { it }, fit = { fitSaw = it; null })
        assertSame(transcoded, fitSaw)
    }

    // -- source guards: every path uses the one downscale, and the note --

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    @Test fun `first send and reload share downscaleForModel`() {
        assertTrue("first send", vm.contains("val downscaled = ImageBudget.downscaleForModel(rawBytes)"))
        assertTrue("reload", vm.contains("val downscaled = ImageBudget.downscaleForModel(file, original)"))
        assertFalse("the old full-decode resize is gone", vm.contains("fun resizeImageBytes("))
    }

    @Test fun `reload replays the reduced copy and rebuilds the note`() {
        assertTrue(vm.contains("val bytes = downscaled?.bytes ?: original"))
        assertTrue(vm.contains("contentParts.add(AgentContentPart.ImageData(bytes, sendMime,"))
        assertTrue(vm.contains("downscaled?.let { contentParts.add(AgentContentPart.Text(ImageBudget.downscaleNote(it, restoredPath))) }"))
    }

    @Test fun `every send path emits the image through addModelImage with its note`() {
        assertEquals(3, Regex("""\.addModelImage\(part, prepared\.imageUploadPaths\.getOrNull\(idx\), prepared\.imageModelNotes\.getOrNull\(idx\)\)""").findAll(vm).count())
        assertTrue(vm.contains("imageModelNotes.add(downscaled?.let { ImageBudget.downscaleNote(it, linuxPath) })"))
        assertTrue("notes stay parallel when the budget drops images",
            vm.contains("while (imageModelNotes.size > newSize) imageModelNotes.removeAt(imageModelNotes.size - 1)"))
    }

    @Test fun `the send gate fits images by default`() {
        val src = ProductionSources.read("provider/ImageBudget.kt")
        assertTrue(src.contains("prepareForUpload(input, declaredMime, ::transcodeCached, ::compressUnderBudget, ::fitCached)"))
    }
}
