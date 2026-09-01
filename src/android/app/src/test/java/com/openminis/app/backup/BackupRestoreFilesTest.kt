package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The restore-side file writer.
 *
 * Two of these tests cover defences whose absence is silent rather than loud:
 * a package can direct writes outside its category root via index CONTENT (the
 * ZIP guard does not see that), and a naive delete-then-copy destroys the
 * user's existing file if the process dies mid-copy.
 */
class BackupRestoreFilesTest {

    private lateinit var tmp: File
    private lateinit var pkg: File
    private lateinit var dest: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("minisbak-restore", "").apply { delete(); mkdirs() }
        pkg = File(tmp, "package").apply { mkdirs() }
        dest = File(tmp, "dest").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    /** Put content into the package's blob store and return its digest. */
    private fun blob(content: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content).joinToString("") { "%02x".format(it) }
        File(File(pkg, "blobs"), sha.take(2)).mkdirs()
        File(File(File(pkg, "blobs"), sha.take(2)), sha).writeBytes(content)
        return sha
    }

    private fun restore(entries: List<BackupFileIndexEntry>) =
        BackupRestoreFiles.restore(pkg, entries, BackupCategory.SHARED_FILES, dest) { path ->
            if (!path.startsWith("shared/")) null else File(dest, path.removePrefix("shared/"))
        }

    @Test
    fun `writes blob content back to its logical path`() {
        val sha = blob("hello".toByteArray())
        val r = restore(listOf(
            BackupFileIndexEntry.file("shared/notes/a.md", 5, sha, BackupCategory.SHARED_FILES)
        ))
        assertEquals(1, r.written)
        assertEquals(5L, r.bytes)
        assertEquals("hello", File(dest, "notes/a.md").readText())
    }

    /**
     * Path traversal via index content. The archive's own entry names are all
     * benign (`blobs/<xx>/<sha>`); the hostile path lives in files.index, which
     * is attacker-controlled whenever a user restores a package someone sent
     * them.
     */
    @Test
    fun `refuses an index path that escapes the category root`() {
        val sha = blob("pwned".toByteArray())
        val r = restore(listOf(
            BackupFileIndexEntry.file("shared/../../escaped.txt", 5, sha, BackupCategory.SHARED_FILES)
        ))
        assertEquals("nothing may be written", 0, r.written)
        assertEquals(1, r.rejectedPaths)
        assertFalse("the file must not exist outside the root", File(tmp, "escaped.txt").exists())
    }

    @Test
    fun `refuses a deeply nested traversal`() {
        val sha = blob("pwned".toByteArray())
        val r = restore(listOf(
            BackupFileIndexEntry.file(
                "shared/a/b/../../../../evil.txt", 5, sha, BackupCategory.SHARED_FILES
            )
        ))
        assertEquals(0, r.written)
        assertEquals(1, r.rejectedPaths)
        assertFalse(File(tmp, "evil.txt").exists())
    }

    /**
     * S7: the index says the file is in the package but its blob is not. This
     * must be counted and surfaced — as a silent skip, the file simply never
     * appears and the restore still reports success.
     */
    @Test
    fun `a missing blob is counted, not silently skipped`() {
        val r = restore(listOf(
            BackupFileIndexEntry.file(
                "shared/gone.bin", 42,
                "deadbeef00000000000000000000000000000000000000000000000000000000",
                BackupCategory.SHARED_FILES,
            )
        ))
        assertEquals(0, r.written)
        assertEquals(1, r.missingBlobs)
    }

    /** §3.4 tombstones carry no content by design and are reported separately. */
    @Test
    fun `tombstones are counted by kind and never written`() {
        val r = restore(listOf(
            BackupFileIndexEntry.sizeSkipped("shared/big.zip", 999, BackupCategory.SHARED_FILES),
            BackupFileIndexEntry("shared/cloud.pdf", 100, null,
                BackupCategory.SHARED_FILES.key, skipped = "not_downloaded"),
        ))
        assertEquals(0, r.written)
        assertEquals(1, r.sizeSkippedInPackage)
        assertEquals(1, r.notDownloadedInPackage)
    }

    @Test
    fun `recreates empty directories`() {
        val r = restore(listOf(
            BackupFileIndexEntry.directory("shared/empty", BackupCategory.SHARED_FILES)
        ))
        assertEquals(0, r.written)
        assertTrue(File(dest, "empty").isDirectory)
    }

    /**
     * Overwriting must go through a staged swap. Delete-then-copy leaves the
     * user's file non-existent for the whole copy, so a kill in that window
     * destroys it permanently — and merge idempotency cannot help a file that
     * was deleted and never rewritten.
     */
    @Test
    fun `overwrites an existing file and leaves no scratch behind`() {
        File(dest, "notes").mkdirs()
        val existing = File(dest, "notes/a.md").apply { writeText("old content") }
        val sha = blob("new content".toByteArray())

        val r = restore(listOf(
            BackupFileIndexEntry.file("shared/notes/a.md", 11, sha, BackupCategory.SHARED_FILES)
        ))
        assertEquals(1, r.written)
        assertEquals("new content", existing.readText())
        // The staged temp file must never survive a successful write.
        val leftovers = File(dest, "notes").list()!!.filter { it.startsWith(".restore-") }
        assertTrue("scratch files must be cleaned up, found: $leftovers", leftovers.isEmpty())
    }

    /** Entries belonging to another category must be left entirely alone. */
    @Test
    fun `ignores entries from other categories`() {
        val sha = blob("chat file".toByteArray())
        val r = restore(listOf(
            BackupFileIndexEntry.file("chats/sid/a.png", 9, sha, BackupCategory.CHATS)
        ))
        assertEquals(0, r.written)
        assertEquals(0, r.rejectedPaths)
    }

    /** A destination mapper returning null means "not mine" — not an error. */
    @Test
    fun `skips entries the mapper does not claim`() {
        val sha = blob("x".toByteArray())
        val r = restore(listOf(
            BackupFileIndexEntry.file("unexpected/prefix.txt", 1, sha, BackupCategory.SHARED_FILES)
        ))
        assertEquals(0, r.written)
        assertEquals(0, r.rejectedPaths)
    }
}
