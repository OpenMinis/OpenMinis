package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the two properties that were missing when `file_write` / `file_edit`
 * "reported success but nothing landed".
 */
class AtomicFileWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writeVerifiesAndReturnsLength() {
        val f = File(tmp.root, "a.txt")
        val n = AtomicFileWrite.write(f, "hello world")
        assertEquals(11L, n)
        assertEquals("hello world", f.readText())
    }

    @Test
    fun overwriteReplacesRatherThanAppends() {
        val f = File(tmp.root, "b.txt")
        AtomicFileWrite.write(f, "first")
        AtomicFileWrite.write(f, "second")
        assertEquals("second", f.readText())
    }

    @Test
    fun appendKeepsExistingBytes() {
        val f = File(tmp.root, "c.txt")
        AtomicFileWrite.write(f, "one")
        AtomicFileWrite.write(f, "-two", append = true)
        assertEquals("one-two", f.readText())
    }

    /**
     * [T-append-verify-vacuous] The append check used to sample `file.length()`
     * AFTER the append and pass it as the "expected" length, then skip the byte
     * comparison for appends — `length == length`, always true, so a short append
     * was reported as a success. Growth and tail bytes are verified now, so a
     * write that lands fewer bytes must come back null (= failure).
     */
    @Test
    fun appendVerificationCatchesShortWrite() {
        val f = File(tmp.root, "append-short.txt")
        AtomicFileWrite.write(f, "seed-") // 5 bytes
        // 5 + len("appended") = 13 — the healthy append reports the full length.
        assertEquals(13L, AtomicFileWrite.write(f, "appended", append = true))

        // Second append would land at 13 + 8 = 21; truncate to 19 to simulate the
        // silent short write (2 of 8 bytes never reached the disk).
        AtomicFileWrite.truncateAfterAppendForTest = 19L
        try {
            assertNull(
                "a short append must be reported as a failure, not as success",
                AtomicFileWrite.write(f, "appended", append = true),
            )
        } finally {
            AtomicFileWrite.truncateAfterAppendForTest = -1L
        }
    }

    /** Multi-byte content: the growth check is in BYTES, not chars. */
    @Test
    fun appendVerifyUsesByteLength() {
        val f = File(tmp.root, "append-utf8.txt")
        AtomicFileWrite.write(f, "a")
        val n = AtomicFileWrite.write(f, "é😀", append = true)
        // "a" (1) + é (2) + 😀 (4) = 7 bytes on disk.
        assertEquals(7L, n)
        assertEquals(7L, f.length())
        assertEquals("aé😀", f.readText())
    }

    @Test
    fun concurrentWritesAllLand() {
        // N writers, distinct content, same file. Without the per-path lock a
        // writer can observe a snapshot another is mid-way through replacing.
        val f = File(tmp.root, "race.txt")
        AtomicFileWrite.write(f, "seed")
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(40)
        val applied = AtomicInteger()
        repeat(40) { i ->
            pool.submit {
                try {
                    // read-modify-write: each task appends its own marker to
                    // whatever is currently there. A lost update shows up as a
                    // missing marker.
                    AtomicFileWrite.readModifyWrite(f) { cur ->
                        val next = if (cur.isEmpty()) "m$i" else "$cur,m$i"
                        AtomicFileWrite.write(f, next)
                        next
                    }
                    applied.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("tasks did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val markers = f.readText().split(",").filter { it.startsWith("m") }.toSet()
        assertEquals("lost updates: ${f.readText()}", 40, markers.size)
        assertEquals(40, applied.get())
    }

    @Test
    fun concurrentEditsDoNotLoseEachOthersChange() {
        // The file_edit shape specifically: many readers computing a distinct
        // replacement over a shared base. Every change must survive.
        val f = File(tmp.root, "base.txt")
        val base = (1..50).joinToString("\n") { "line-$it" }
        AtomicFileWrite.write(f, base)

        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(50)
        repeat(50) { i ->
            pool.submit {
                try {
                    AtomicFileWrite.readModifyWrite(f) { cur ->
                        cur.replaceFirst("line-1", "line-1 edited-$i")
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("tasks did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val lines = f.readText().lines()
        // Exactly one edited line-1 (the last writer to win), and every other
        // line still present — no edit may have reverted a sibling's change.
        assertEquals(1, lines.count { it.startsWith("line-1 edited-") })
        assertEquals(50, lines.size)
        (2..50).forEach { assertTrue("line-$it missing", lines.contains("line-$it")) }
    }

    @Test
    fun readModifyWriteReturnsNullWhenWriteCannotLand() {
        val f = File(tmp.root, "locked.txt")
        AtomicFileWrite.write(f, "orig")
        // A failed replace must not delete the destination first. OS read-only
        // bits are not reliable here (Windows ignores them), so force the miss.
        AtomicFileWrite.failReplaceForTest = true
        try {
            val out = AtomicFileWrite.readModifyWrite(f) { it + "!" }
            assertNull("a failed write must not look like success", out)
            assertEquals("original content must survive a failed write", "orig", f.readText())
        } finally {
            AtomicFileWrite.failReplaceForTest = false
        }
    }

    @Test
    fun readModifyWritePersistsPayloadImplementingHasPersistableText() {
        // The reported bug: FileEditTool's ReplaceOutcome fell through the
        // no-write branch — readModifyWrite returned it verbatim, the tool
        // reported "Edited N replacements", and the file never changed.
        val f = File(tmp.root, "payload.txt")
        AtomicFileWrite.write(f, "base")
        val out = AtomicFileWrite.readModifyWrite(f) { cur ->
            object : HasPersistableText {
                override fun persistableText(): String? = cur + " edited"
            }
        }
        assertNotNull(out)
        assertEquals("base edited", f.readText())
    }

    @Test
    fun readModifyWriteDoesNotTouchFileWhenPayloadDeclinesPersist() {
        // A failure payload (persistableText = null) must NOT write anything —
        // previously FileEditTool's failure path returned a bare String that
        // readModifyWrite interpreted as new content and wrote over the file.
        val f = File(tmp.root, "decline.txt")
        AtomicFileWrite.write(f, "orig")
        val out = AtomicFileWrite.readModifyWrite(f) { cur ->
            object : HasPersistableText {
                override fun persistableText(): String? = null
            }
        }
        assertNotNull("declined payload still returns itself", out)
        assertEquals("orig", f.readText())
    }

    @Test
    fun readReturnsCurrentBytesUnderLock() {
        val f = File(tmp.root, "r.txt")
        AtomicFileWrite.write(f, "v1")
        assertEquals("v1", AtomicFileWrite.read(f))
        AtomicFileWrite.write(f, "v2")
        assertEquals("v2", AtomicFileWrite.read(f))
        assertNotNull(AtomicFileWrite.read(File(tmp.root, "missing.txt")))
    }

    // ── [T-fileedit-binary-guard] / [T-fileedit-size-cap] / [T-fileedit-bom] ──

    @Test
    fun readModifyWriteRejectsBinaryContentWithoutTouchingTheFile() {
        // The old readText() path replaced the NUL with U+FFFD and wrote the
        // corrupted text back; verify() compared against the same corrupted
        // string and saw nothing wrong.
        val f = File(tmp.root, "bin.dat")
        val bytes = byteArrayOf(0x68, 0x69, 0x00, 0x62)
        f.writeBytes(bytes)
        val out = AtomicFileWrite.readModifyWrite(f) { "transform must not run: $it" }
        assertNull("binary file must be refused", out)
        assertTrue("file bytes must survive untouched", bytes.contentEquals(f.readBytes()))
    }

    @Test
    fun readModifyWriteRejectsInvalidUtf8WithoutTouchingTheFile() {
        val f = File(tmp.root, "invalid.txt")
        val bytes = byteArrayOf(0x68, 0xC3.toByte(), 0x28) // 0xC3 expects a continuation byte
        f.writeBytes(bytes)
        assertNull(AtomicFileWrite.readModifyWrite(f) { "x" })
        assertTrue(bytes.contentEquals(f.readBytes()))
    }

    @Test
    fun readModifyWriteRejectsOversizedFileBeforeReadingItIntoMemory() {
        val f = File(tmp.root, "big.txt")
        f.writeBytes(ByteArray(AtomicFileWrite.MAX_EDIT_BYTES + 1) { 'a'.code.toByte() })
        assertNull(AtomicFileWrite.readModifyWrite(f) { "x" })
        assertEquals(AtomicFileWrite.MAX_EDIT_BYTES + 1L, f.length())
    }

    @Test
    fun readModifyWriteStripsBomForMatchingAndRestoresItOnWrite() {
        // A BOM-prefixed file used to fail all three match tiers because the
        // old_string (written without a BOM) never matched the U+FEFF head.
        val f = File(tmp.root, "bom.txt")
        f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello world".toByteArray(Charsets.UTF_8))
        val out = AtomicFileWrite.readModifyWrite(f) { current ->
            assertEquals("transform sees BOM-stripped text", "hello world", current)
            current.replace("hello", "goodbye")
        }
        assertEquals("goodbye world", out)
        val onDisk = f.readBytes()
        assertEquals(0xEF.toByte(), onDisk[0])
        assertEquals(0xBB.toByte(), onDisk[1])
        assertEquals(0xBF.toByte(), onDisk[2])
        assertEquals("\uFEFFgoodbye world", String(onDisk, Charsets.UTF_8))
    }

    // ── [T-checkpoint-restore-atomic] moveIntoPlace ──

    @Test
    fun moveIntoPlaceReplacesTargetAndRemovesTmp() {
        val target = File(tmp.root, "t.txt").apply { writeText("old") }
        val staged = File(tmp.root, "t.tmp").apply { writeText("new-bytes") }
        assertTrue(AtomicFileWrite.moveIntoPlace(staged, target))
        assertEquals("new-bytes", target.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun moveIntoPlaceFailureKeepsPreviousBytes() {
        // The old checkpoint-restore dance deleted the target before the retry
        // rename; when the retry failed too, the only copy was gone. This must
        // never happen: failure returns false and the previous bytes survive.
        val target = File(tmp.root, "k.txt").apply { writeText("precious") }
        val staged = File(tmp.root, "k.tmp").apply { writeText("replacement") }
        AtomicFileWrite.failReplaceForTest = true
        try {
            assertFalse(AtomicFileWrite.moveIntoPlace(staged, target))
        } finally {
            AtomicFileWrite.failReplaceForTest = false
        }
        assertEquals("precious", target.readText())
    }
}
