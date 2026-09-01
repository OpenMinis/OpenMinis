package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive has to stay readable by iOS's hand-rolled parser, which is
 * stricter than any ZIP library: STORED only, classic 32-bit central directory,
 * no ZIP64. These tests pin the properties that parser depends on.
 */
class BackupZipTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("minisbak-zip", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun staging(): File = File(tmp, "staging").apply {
        mkdirs()
        File(this, "manifest.json").writeText("""{"format":"minisbak/1"}""")
        File(this, "data").mkdirs()
        File(this, "data/sessions.jsonl").writeText("{\"t\":\"session\",\"v\":1,\"d\":{}}\n")
        File(this, "blobs/ab").mkdirs()
        File(this, "blobs/ab/abcdef").writeBytes(ByteArray(4096) { it.toByte() })
    }

    /**
     * [T-android-zip-store-by-content] A LARGE member must be STORED, whatever
     * its content.
     *
     * This test used to require STORED for every entry. The reason was sound
     * and still is: iOS inflates a DEFLATE member as one in-memory unit
     * (`compression_decode_buffer`, BackupZipExtractor.swift:364), and this app
     * has a jetsam history. But the blanket rule also stored the jsonl
     * families, which are the bulk of a text-heavy package and compress to
     * about a sixth — on a measured package `data/messages.jsonl` was 7.11 MB
     * of 10.4 MB, shipped raw and re-uploaded on every backup.
     *
     * So the invariant is now a SIZE one, which is what the jetsam concern
     * actually was: members above the cap stay STORED; small compressible ones
     * may deflate.
     */
    @Test
    fun `a large member is stored regardless of how well it would compress`() {
        val st = staging()
        // 40 MB of zeros: maximally compressible, and over the deflate cap.
        File(st, "data/huge.jsonl").writeBytes(ByteArray(40 * 1024 * 1024))

        val out = File(tmp, "p.minisbak")
        BackupZip.archive(st, out)

        java.util.zip.ZipFile(out).use { zf ->
            val huge = zf.entries().toList().first { it.name == "data/huge.jsonl" }
            assertEquals(
                "a member past the cap must stay STORED — iOS inflates whole",
                ZipEntry.STORED.toLong(), huge.method.toLong(),
            )
        }
    }

    /** Names are package-relative and slash-separated, matching the manifest's integrity keys. */
    @Test
    fun `entry names are package-relative with forward slashes`() {
        val out = File(tmp, "p.minisbak")
        BackupZip.archive(staging(), out)
        val names = BackupZip.listEntries(out).toSet()
        assertEquals(setOf("manifest.json", "data/sessions.jsonl", "blobs/ab/abcdef"), names)
    }

    @Test
    fun `round-trips content through archive and extract`() {
        val out = File(tmp, "p.minisbak")
        BackupZip.archive(staging(), out)
        val dest = File(tmp, "extracted")
        BackupZip.extract(out, dest)

        assertEquals("""{"format":"minisbak/1"}""", File(dest, "manifest.json").readText())
        assertTrue(File(dest, "blobs/ab/abcdef").readBytes().contentEquals(ByteArray(4096) { it.toByte() }))
    }

    /**
     * The ZIP64 guard. Without it, ZipOutputStream promotes the archive on its
     * own and iOS reads the saturated 0xFFFF entry count as 65 535 — extracting
     * a subset and calling the restore a success. Refusing up front is the only
     * safe behaviour, because the damage is invisible on the writing side.
     */
    @Test
    fun `refuses an archive with more entries than the classic EOCD can count`() {
        val dir = File(tmp, "many").apply { mkdirs() }
        // Build the file list cheaply — 65 536 empty files, one over the limit.
        for (i in 0..65_535) File(dir, "f$i").writeBytes(ByteArray(0))

        var message: String? = null
        try {
            BackupZip.archive(dir, File(tmp, "too-many.minisbak"))
        } catch (e: BackupZip.ZipException) {
            message = e.message
        }
        assertTrue(
            "an over-large entry count must be refused before writing, got: $message",
            message?.contains("65535") == true,
        )
    }

    /** §5.5: a malicious package must not write outside the destination. */
    @Test
    fun `refuses path traversal on extract`() {
        val evil = File(tmp, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            val payload = "pwned".toByteArray()
            val crc = CRC32().apply { update(payload) }
            val e = ZipEntry("../../escaped.txt").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(e); zos.write(payload); zos.closeEntry()
        }

        var threw = false
        try {
            BackupZip.extract(evil, File(tmp, "dest"))
        } catch (e: BackupZip.ZipException) {
            threw = true
        }
        assertTrue("a `..` entry must be refused", threw)
        assertTrue("nothing may be written outside the destination",
            !File(tmp, "escaped.txt").exists())
    }

    /**
     * iOS zips through NSFileCoordinator, which wraps the tree in an outer
     * folder, so a restored package root is one level down. Both shapes must
     * resolve, or every iOS-written package fails to find its manifest.
     */
    @Test
    fun `finds the package root whether or not iOS wrapped it`() {
        val flat = File(tmp, "flat").apply { mkdirs() }
        File(flat, "manifest.json").writeText("{}")
        assertEquals(flat, BackupZip.packageRoot(flat))

        val wrapped = File(tmp, "wrapped").apply { mkdirs() }
        val inner = File(wrapped, "minisbak-1234").apply { mkdirs() }
        File(inner, "manifest.json").writeText("{}")
        assertEquals(inner, BackupZip.packageRoot(wrapped))
    }

    /** Reading the manifest must work for both the flat and iOS-wrapped names. */
    @Test
    fun `reads a single entry by bare name even when iOS wrapped it`() {
        val wrapped = File(tmp, "w.zip")
        ZipOutputStream(wrapped.outputStream()).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            val payload = """{"format":"minisbak/1"}""".toByteArray()
            val crc = CRC32().apply { update(payload) }
            val e = ZipEntry("minisbak-abcd/manifest.json").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(e); zos.write(payload); zos.closeEntry()
        }
        val bytes = BackupZip.readEntry(wrapped, "manifest.json")
        assertEquals("""{"format":"minisbak/1"}""", bytes?.toString(Charsets.UTF_8))
    }
}
