package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Content-addressed storage and the §3.4 size cap.
 *
 * The cap's boundary and its tombstone behaviour are called out explicitly in
 * the design doc's test plan, because both have a silent-data-loss failure mode:
 * an off-by-one at the threshold drops a file the user expected to keep, and a
 * skipped file with no tombstone vanishes without trace.
 */
class BackupBlobStoreTest {

    private lateinit var tmp: File
    private lateinit var staging: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("minisbak-blob", "").apply { delete(); mkdirs() }
        staging = File(tmp, "staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(tmp, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }

    @Test
    fun `identical content is stored once and reported as a duplicate`() {
        val store = BackupBlobStore(staging, maxFileBytes = null)
        val content = "shared attachment".toByteArray()
        val a = store.addFile(file("a.txt", content), "chats/s1/attachments/a.txt")
        val b = store.addFile(file("b.txt", content), "chats/s2/attachments/b.txt")

        assertTrue(a is BackupBlobStore.Outcome.Stored)
        assertTrue("the second copy must dedup, not re-store", b is BackupBlobStore.Outcome.Duplicate)
        assertEquals(
            (a as BackupBlobStore.Outcome.Stored).sha256,
            (b as BackupBlobStore.Outcome.Duplicate).sha256,
        )
        // One blob on disk, and the index carries one entry for it.
        assertEquals(1, store.blobIndex.size)
        assertEquals(content.size.toLong(), store.totalBytesStored)
    }

    @Test
    fun `blobs fan out two levels by digest prefix`() {
        val store = BackupBlobStore(staging, maxFileBytes = null)
        val outcome = store.addFile(file("x.bin", byteArrayOf(1, 2, 3)), "shared_files/x.bin")
        val sha = (outcome as BackupBlobStore.Outcome.Stored).sha256
        val expected = File(File(File(staging, "blobs"), sha.take(2)), sha)
        assertTrue("blob should live at blobs/<2 hex>/<sha256>", expected.exists())
    }

    /**
     * The design doc pins this boundary: "恰好等于阈值的文件必须纳入（边界取 `>`
     * 而非 `>=`）". A `>=` here would drop a file that is exactly at the limit.
     */
    @Test
    fun `a file exactly at the cap is included, one byte over is not`() {
        val store = BackupBlobStore(staging, maxFileBytes = 100)
        val atLimit = store.addFile(file("at.bin", ByteArray(100)), "chats/s1/at.bin")
        val overLimit = store.addFile(file("over.bin", ByteArray(101)), "chats/s1/over.bin")

        assertTrue("a file exactly at the cap must be included", atLimit is BackupBlobStore.Outcome.Stored)
        assertTrue("one byte over must be skipped", overLimit is BackupBlobStore.Outcome.SkippedTooLarge)
        assertEquals(1, store.skippedFiles)
        assertEquals(101L, store.skippedBytes)
        assertEquals("chats/s1/over.bin", store.skippedPaths.single().path)
    }

    /** Unlimited is the default, so nothing is dropped from a migration package. */
    @Test
    fun `an unlimited store keeps everything`() {
        val store = BackupBlobStore(staging, maxFileBytes = null)
        store.addFile(file("huge.bin", ByteArray(5_000_000)), "shared_files/huge.bin")
        assertEquals(0, store.skippedFiles)
        assertEquals(null, store.maxFileBytesForManifest)
    }

    /**
     * Resume: a digest whose blob is missing must NOT be re-registered, or the
     * resumed package references content it does not contain — a package that
     * passes its own integrity check and restores empty files.
     */
    @Test
    fun `rehydrate skips index entries whose blob is gone`() {
        val first = BackupBlobStore(staging, maxFileBytes = null)
        val present = first.addFile(file("p.bin", byteArrayOf(9, 9, 9)), "chats/s1/p.bin")
        val presentSha = (present as BackupBlobStore.Outcome.Stored).sha256

        // A files.index from an interrupted run: one entry whose blob landed,
        // one whose blob never did.
        val index = File(staging, "files.index.jsonl")
        index.writeText(
            listOf(
                BackupFileIndexEntry.file("chats/s1/p.bin", 3, presentSha, BackupCategory.CHATS),
                BackupFileIndexEntry.file(
                    "chats/s1/lost.bin", 42,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    BackupCategory.CHATS,
                ),
            ).joinToString("\n") {
                BackupFormat.json.encodeToString(BackupFileIndexEntry.serializer(), it)
            } + "\n"
        )

        val resumed = BackupBlobStore(staging, maxFileBytes = null)
        resumed.rehydrateFromFileIndex(index)
        assertEquals("only the blob actually on disk may be re-registered", 1, resumed.blobIndex.size)
        assertEquals(presentSha, resumed.blobIndex.single().sha256)

        // And the missing one must be re-stored rather than skipped as "seen".
        val again = resumed.addFile(file("lost.bin", ByteArray(42)), "chats/s1/lost.bin")
        assertTrue("a blob missing from disk must be stored again", again is BackupBlobStore.Outcome.Stored)
    }

    @Test
    fun `tombstones and directories are ignored when rehydrating`() {
        val index = File(staging, "files.index.jsonl")
        index.writeText(
            listOf(
                BackupFormat.json.encodeToString(
                    BackupFileIndexEntry.serializer(),
                    BackupFileIndexEntry.sizeSkipped("chats/s1/big.zip", 999, BackupCategory.CHATS),
                ),
                BackupFormat.json.encodeToString(
                    BackupFileIndexEntry.serializer(),
                    BackupFileIndexEntry.directory("chats/s1/empty", BackupCategory.CHATS),
                ),
                "{ not json at all",
            ).joinToString("\n") + "\n"
        )
        val store = BackupBlobStore(staging, maxFileBytes = null)
        store.rehydrateFromFileIndex(index)
        assertEquals(0, store.blobIndex.size)
    }

    @Test
    fun `sha256 matches a known vector`() {
        // Streaming digest must equal the standard SHA-256 of "abc".
        val f = file("abc.txt", "abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupBlobStore.sha256OfFile(f),
        )
    }
}
