package com.openminis.app.backup

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-android-zip-store-by-content] The packer must decide STORED vs DEFLATE
 * from the member's CONTENT.
 *
 * Android used to store EVERY member unconditionally. That is right for media
 * blobs, which carry their own compression, but it also applied to the jsonl
 * members that are the bulk of a text-heavy package: a measured 10.4 MB package
 * held 7.11 MB of raw `data/messages.jsonl`, and every byte of it was
 * re-uploaded on each backup.
 *
 * iOS had the opposite half of the same bug (fixed in 1fbcf5886): it decided
 * from the filename extension, and blobs are content-addressed with no
 * extension, so it deflated 22,845 of 23,206 entries — all the photos and
 * video included. One writer stored everything, the other deflated everything.
 *
 * The round-trip cases matter as much as the decision ones: a mixed-method
 * archive is a different code path from an all-STORED one, and it has to come
 * back byte-identical.
 */
class BackupZipStoreByContentTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Realistic jsonl: varied prose, distinct ids — not a repeated line. */
    private fun jsonlBytes(records: Int = 1200): ByteArray {
        val words = listOf(
            "analysis", "config", "request", "retry", "check", "log", "session",
            "分析", "配置", "网络", "请求", "失败", "重试", "检查", "日志",
        )
        val rnd = java.util.Random(7)
        return buildString {
            repeat(records) { i ->
                val text = (0 until 40).joinToString(" ") { words[rnd.nextInt(words.size)] }
                append("""{"t":"MessageV2","v":1,"d":{"id":"${rnd.nextLong()}",""")
                append(""""role":"user","parts":[{"type":"text","value":"$text"}],""")
                append(""""sortOrder":$i}}""").append('\n')
            }
        }.toByteArray()
    }

    private fun jpegBytes(size: Int = 200_000): ByteArray {
        val rnd = java.util.Random(11)
        val b = ByteArray(size).also { rnd.nextBytes(it) }
        // JPEG magic, so the decision is made by magic and not by the probe.
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte(); b[2] = 0xFF.toByte()
        return b
    }

    @Test
    fun `a jsonl member is deflated`() {
        assertFalse(
            "text must compress — this is the 7.11 MB that used to ship raw",
            BackupZip.shouldStoreBytes(jsonlBytes()),
        )
    }

    @Test
    fun `an extension-less JPEG blob is stored`() {
        // Blobs are `blobs/<2hex>/<sha256>` — the name says nothing, which is
        // exactly what defeated the iOS extension lookup.
        assertTrue(BackupZip.shouldStoreBytes(jpegBytes()), "JPEG magic must win")
    }

    @Test
    fun `ISO base media is recognised at offset four`() {
        // `ftyp` sits at byte 4, not 0, so it cannot be a leading-bytes match.
        val mp4 = ByteArray(64).also {
            it[4] = 0x66; it[5] = 0x74; it[6] = 0x79; it[7] = 0x70 // ftyp
        }
        assertTrue(BackupZip.shouldStoreBytes(mp4))
    }

    @Test
    fun `headerless high-entropy data is stored by the probe`() {
        // No magic to match; only the entropy probe can answer.
        val random = ByteArray(200_000).also { java.util.Random(3).nextBytes(it) }
        assertTrue(BackupZip.shouldStoreBytes(random))
    }

    @Test
    fun `empty and tiny members are stored`() {
        assertTrue(BackupZip.shouldStoreBytes(ByteArray(0)))
        assertTrue(BackupZip.shouldStoreBytes(ByteArray(1)))
    }

    @Test
    fun `highly compressible data is deflated even without a header`() {
        assertFalse(BackupZip.shouldStoreBytes(ByteArray(64 * 1024)))
    }

    @Test
    fun `a mixed archive round-trips byte-for-byte`() {
        val staging = tmp.newFolder("staging")
        val data = File(staging, "data").apply { mkdirs() }
        val blobs = File(staging, "blobs/ff").apply { mkdirs() }

        val jsonl = jsonlBytes()
        val jpeg = jpegBytes()
        File(data, "messages.jsonl").writeBytes(jsonl)
        File(blobs, "ff".repeat(32)).writeBytes(jpeg)
        File(staging, "manifest.json").writeBytes("""{"backupId":"x"}""".toByteArray())

        val pkg = File(tmp.root, "out.minisbak")
        BackupZip.archive(staging, pkg)

        // Both methods must be present — an all-STORED archive would pass a
        // round-trip check while proving nothing about the mixed path.
        ZipFile(pkg).use { zf ->
            val byName = zf.entries().toList().associateBy { it.name }
            assertEquals(
                "jsonl should be deflated",
                ZipEntry.DEFLATED, byName["data/messages.jsonl"]!!.method,
            )
            assertEquals(
                "blob should be stored",
                ZipEntry.STORED, byName["blobs/ff/${"ff".repeat(32)}"]!!.method,
            )
        }

        val out = tmp.newFolder("out")
        BackupZip.extract(pkg, out)
        assertTrue(
            "jsonl must survive the deflate round-trip unchanged",
            File(out, "data/messages.jsonl").readBytes().contentEquals(jsonl),
        )
        assertTrue(
            "blob must survive unchanged",
            File(out, "blobs/ff/${"ff".repeat(32)}").readBytes().contentEquals(jpeg),
        )
    }

    @Test
    fun `deflating the jsonl actually shrinks the package`() {
        val staging = tmp.newFolder("s2")
        File(staging, "data").mkdirs()
        val jsonl = jsonlBytes(4000)
        File(staging, "data/messages.jsonl").writeBytes(jsonl)

        val pkg = File(tmp.root, "s2.minisbak")
        BackupZip.archive(staging, pkg)
        assertTrue(
            "package (${pkg.length()}) should be well under the raw ${jsonl.size}",
            pkg.length() < jsonl.size / 2,
        )
    }
}

private fun assertTrue(condition: Boolean, message: String) =
    org.junit.Assert.assertTrue(message, condition)
