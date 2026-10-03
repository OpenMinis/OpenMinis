package com.openminis.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-android-child-session-delete-storage] Parent + child each own a
 * minis-sessions dir and dated media under two dates; sizes aggregate to the
 * parent and a tree delete leaves nothing on disk.
 */
class SessionStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) { f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(bytes)) }

    private fun seed(filesDir: File) {
        write(File(filesDir, "minis-sessions/P/workspace/a.txt"), 100)
        write(File(filesDir, "minis-sessions/C/workspace/b.txt"), 40)
        write(File(filesDir, "minis-sessions/C/attachments/c.bin"), 10)
        write(File(filesDir, "media/2026/09/01/P/img.png"), 1000)
        write(File(filesDir, "media/2026/09/04/P/img2.png"), 500)
        write(File(filesDir, "media/2026/09/04/C/img3.png"), 20)
        write(File(filesDir, "media/2026/09/04/OTHER/keep.png"), 7)
        write(File(filesDir, "minis-sessions/OTHER/keep.txt"), 3)
    }

    @Test
    fun `sizes per session and aggregated to the root parent`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val s = SessionStorage(filesDir)
        assertEquals(100L, s.minisSize("P")); assertEquals(50L, s.minisSize("C"))
        assertEquals(1500L, s.mediaSize("P")); assertEquals(20L, s.mediaSize("C"))
        assertEquals(mapOf("P" to 1500L, "C" to 20L), s.mediaSizesBySession(setOf("P", "C")))
        val parentOf = mapOf<String, String?>("P" to null, "C" to "P", "OTHER" to null)
        val minis = SessionTree.aggregateToRoots(parentOf, mapOf("P" to s.minisSize("P"), "C" to s.minisSize("C"), "OTHER" to s.minisSize("OTHER")))
        val media = SessionTree.aggregateToRoots(parentOf, s.mediaSizesBySession(parentOf.keys))
        assertEquals(150L, minis["P"]); assertEquals(1520L, media["P"])
        assertEquals(3L, minis["OTHER"]); assertEquals(7L, media["OTHER"])
        assertFalse("child must not be a root row", minis.containsKey("C"))
    }

    @Test
    fun `deleteFiles removes the workspace and every dated media dir, nothing else`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val s = SessionStorage(filesDir)
        val freed = listOf("C", "P").sumOf { s.deleteFiles(it) }
        assertEquals(100L + 50L + 1500L + 20L, freed)
        assertFalse(File(filesDir, "minis-sessions/P").exists())
        assertFalse(File(filesDir, "minis-sessions/C").exists())
        assertTrue(s.mediaDirs("P").isEmpty()); assertTrue(s.mediaDirs("C").isEmpty())
        assertFalse(s.hasFiles("P")); assertFalse(s.hasFiles("C"))
        assertTrue(File(filesDir, "media/2026/09/04/OTHER/keep.png").exists())
        assertTrue(File(filesDir, "minis-sessions/OTHER/keep.txt").exists())
        assertEquals(0L, s.deleteFiles("P"))
    }

    // ---- [T-android-storage-delete-feedback] openminis/openminis#375 --------

    @Test
    fun `a read-only directory is made writable and still deleted`() {
        // The #375 shape: Git leaves .git/objects/pack at 0555, so entries
        // inside it cannot be unlinked until the dir regains +w. Before the
        // fix this silently left the files and reported success.
        val filesDir = tmp.newFolder("files")
        val pack = File(filesDir, "minis-sessions/P/workspace/repo/.git/objects/pack")
        write(File(pack, "pack-abc.pack"), 64)
        write(File(pack, ".l2s.tmp_pack_x.0002"), 16)
        assertTrue("precondition: drop write on the pack dir", pack.setWritable(false, false))

        val s = SessionStorage(filesDir)
        val r = s.deleteFilesDetailed("P")

        assertTrue("delete must complete, got ${r.failedPaths}", r.isComplete)
        assertEquals(80L, r.freed)
        assertFalse(File(filesDir, "minis-sessions/P").exists())
        assertFalse(s.hasFiles("P"))
    }

    @Test
    fun `deleteFiles keeps its Long contract for existing callers`() {
        // SessionDeleter and the storage screen both sum this value; the
        // detailed variant must not change what the simple one returns.
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val s = SessionStorage(filesDir)
        assertEquals(s.minisSize("C") + s.mediaSize("C"), s.deleteFiles("C"))
    }

    @Test
    fun `a complete delete reports no failed paths`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val s = SessionStorage(filesDir)
        val r = s.deleteFilesDetailed("P")
        assertTrue(r.failedPaths.isEmpty())
        assertTrue(r.isComplete)
        assertEquals(1600L, r.freed)
    }

    @Test
    fun `an undeletable file is REPORTED rather than silently swallowed`() {
        // The whole point of #375's UI half: when the delete genuinely cannot
        // finish, the caller must learn about it. Here the read-only bit is on
        // the session dir's GRANDPARENT (filesDir/minis-sessions), which
        // restoreWritable() never walks because it starts at the session dir —
        // so the unlink really fails and must surface in failedPaths.
        val filesDir = tmp.newFolder("files")
        write(File(filesDir, "minis-sessions/P/workspace/a.txt"), 100)
        val sessionsRoot = File(filesDir, "minis-sessions")
        assertTrue(sessionsRoot.setWritable(false, false))
        try {
            val s = SessionStorage(filesDir)
            val r = s.deleteFilesDetailed("P")
            assertFalse("a failed delete must not claim completion", r.isComplete)
            assertTrue(
                "the surviving path must be named, got ${r.failedPaths}",
                r.failedPaths.any { it.contains("minis-sessions") && it.endsWith("P") },
            )
            // `freed` counts what really left: the inner file IS removable
            // (its own parent is writable), only the top session dir survives.
            // The contract that matters is that the caller is TOLD, not that
            // the byte count is zero.
            assertEquals("only the emptied file's bytes count", 100L, r.freed)
            assertTrue("the session dir itself survives", File(filesDir, "minis-sessions/P").exists())
        } finally {
            // Always restore, or TemporaryFolder cannot clean up.
            sessionsRoot.setWritable(true, true)
        }
    }

    @Test
    fun `deleting a session with nothing on disk is a clean no-op`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val s = SessionStorage(filesDir)
        val r = s.deleteFilesDetailed("NOBODY")
        assertEquals(0L, r.freed)
        assertTrue("absence is not a failure", r.isComplete)
    }
}
