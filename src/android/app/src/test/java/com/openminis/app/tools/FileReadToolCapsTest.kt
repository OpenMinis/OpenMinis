package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileReadToolCapsTest {

    @Test
    fun userCapWinsOverRequestedChars() {
        assertEquals(4_000, FileReadTool.resolveMaxLength(15_000, 4_000))
        assertEquals(15_000, FileReadTool.resolveMaxLength(15_000, 80_000))
        assertEquals(80_000, FileReadTool.resolveMaxLength(200_000, 80_000))
    }

    @Test
    fun zeroLineCapMeansUnlimited() {
        assertNull(FileReadTool.resolveLineLimit(null, 0))
        assertEquals(12, FileReadTool.resolveLineLimit(12, 0))
        assertEquals(50, FileReadTool.resolveLineLimit(null, 50))
        assertEquals(20, FileReadTool.resolveLineLimit(80, 20))
    }

    /** [T-binary-preview] a binary read is a type guess + bounded hex, not a dead end. */
    @Test
    fun binaryPreviewIdentifiesTypeAndStaysBounded() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        val preview = FileReadTool.binaryPreview("/tmp/x.png", 12, png)
        assertTrue(preview.contains("PNG image"))
        assertTrue(preview.contains("89 50 4e 47"))
        assertTrue(preview.contains("Hex preview"))
        assertTrue(preview.contains("read_image"))

        val unknown = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertTrue(FileReadTool.binaryPreview("/tmp/x.bin", 4, unknown).contains("unknown binary"))
    }

    /** UTF-16 BOM text is text, not binary — the NUL bytes are the encoding. */
    @Test
    fun utf16BomIsTextNotBinary() {
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x68, 0x00, 0x69, 0x00)
        assertEquals(Charsets.UTF_16LE, FileReadTool.utf16CharsetOrNull(utf16le))
        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x68, 0x00, 0x69)
        assertEquals(Charsets.UTF_16BE, FileReadTool.utf16CharsetOrNull(utf16be))
        assertNull(FileReadTool.utf16CharsetOrNull(byteArrayOf(0x68, 0x69)))
        assertNull(FileReadTool.utf16CharsetOrNull(ByteArray(0)))
    }

    @Test
    fun hexPreviewIsXxdShapedAndBounded() {
        val head = ByteArray(600) { (it % 251).toByte() }
        val text = FileReadTool.hexPreview(head)
        // 256-byte cap → 16 lines, offsets step by 0x10 up to 0xf0.
        assertTrue(text.contains("00000000"))
        assertTrue(text.contains("000000f0"))
        assertFalse(text.contains("00000100"))
        // ASCII column renders printable bytes, dots otherwise.
        val ascii = byteArrayOf(0x41, 0x00, 0x42)
        assertTrue(FileReadTool.hexPreview(ascii).contains("|A.B|"))
    }
}
