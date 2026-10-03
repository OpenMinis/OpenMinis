package com.openminis.app.ui.settings

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.session.SessionStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-android-storage-usage-cache] Settings › Storage measures once per visit
 * and afterwards only re-measures the sessions an action touched. These pin
 * the rules that make that safe: an incremental value is exactly what a full
 * rescan would show, untouched rows are left alone, deleted sessions drop out,
 * and the order (largest first) and the total follow every change.
 */
class StorageUsageCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) { f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(bytes)) }

    private fun session(id: String, parent: String? = null) = ChatSessionEntity(
        id = id, title = "T-$id", modelId = "m", createdAt = 0, updatedAt = 0, parentSessionId = parent,
    )

    /** P (root) with hidden child C; A and B are plain roots. */
    private val sessions = listOf(session("P"), session("C", parent = "P"), session("A"), session("B"))

    private fun seed(filesDir: File) {
        write(File(filesDir, "minis-sessions/P/workspace/a.txt"), 100)
        write(File(filesDir, "minis-sessions/C/workspace/b.txt"), 40)
        write(File(filesDir, "media/2026/09/01/P/img.png"), 1000)
        write(File(filesDir, "media/2026/09/04/C/img3.png"), 20)
        write(File(filesDir, "minis-sessions/A/workspace/x.bin"), 300)
        write(File(filesDir, "media/2026/09/04/B/y.png"), 5)
    }

    private fun fullScan(storage: SessionStorage): List<SessionStorageInfo> =
        SessionStorageRows.sorted(
            aggregateSessionStorage(sessions, storage).map { (s, minis, media) ->
                SessionStorageInfo(s.id, s.title, minis, media, childCount = if (s.id == "P") 1 else 0)
            },
        )

    private fun info(id: String, total: Long) = SessionStorageInfo(id, id, total, 0)

    @Test
    fun `an incremental measurement equals the full scan for that root, children folded in`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val storage = SessionStorage(filesDir)
        val full = fullScan(storage).associateBy { it.id }
        val measured = SessionStorageRows.measureRoots(listOf("P", "A", "B"), sessions, storage)
        for (id in listOf("P", "A", "B")) assertEquals(full.getValue(id), measured.getValue(id))
        val p = measured.getValue("P")!!
        assertEquals(140L, p.minisSize); assertEquals(1020L, p.mediaSize); assertEquals(1, p.childCount)
    }

    @Test
    fun `a child id is measured as the root row it folds into`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val measured = SessionStorageRows.measureRoots(listOf("C"), sessions, SessionStorage(filesDir))
        assertEquals(setOf("P"), measured.keys)
        assertEquals(1160L, measured.getValue("P")!!.totalSize)
    }

    @Test
    fun `clearing one session updates only its row, re-sorts, and moves the total`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val storage = SessionStorage(filesDir)
        val before = fullScan(storage)
        assertEquals(listOf("P", "A", "B"), before.map { it.id })
        assertEquals(1465L, SessionStorageRows.total(before))

        // Clear P's tree (root and child), as the batch clear does.
        listOf("P", "C").forEach { storage.deleteFiles(it) }
        val after = SessionStorageRows.applyMeasurements(
            before, SessionStorageRows.measureRoots(listOf("P"), sessions, storage),
        )

        assertEquals("P drops to the bottom once empty", listOf("A", "B", "P"), after.map { it.id })
        assertEquals(0L, after.first { it.id == "P" }.totalSize)
        assertSame("untouched rows are the same objects", before.first { it.id == "A" }, after.first { it.id == "A" })
        assertEquals(305L, SessionStorageRows.total(after))
        assertEquals("equals a fresh full scan", fullScan(storage), after)
    }

    @Test
    fun `a deleted session drops out of the list and the total`() {
        val filesDir = tmp.newFolder("files"); seed(filesDir)
        val storage = SessionStorage(filesDir)
        val before = fullScan(storage)
        val remaining = sessions.filter { it.id != "A" }
        storage.deleteFiles("A")
        val measured = SessionStorageRows.measureRoots(listOf("A"), remaining, storage)
        assertNull("gone from the database means null", measured.getValue("A"))
        val after = SessionStorageRows.applyMeasurements(before, measured)
        assertEquals(listOf("P", "B"), after.map { it.id })
        assertEquals(1165L, SessionStorageRows.total(after))
    }

    @Test
    fun `batch measurements apply together`() {
        val rows = SessionStorageRows.sorted(listOf(info("a", 10), info("b", 20), info("c", 30)))
        val after = SessionStorageRows.applyMeasurements(rows, mapOf("c" to info("c", 1), "b" to null))
        assertEquals(listOf("a", "c"), after.map { it.id })
        assertEquals(11L, SessionStorageRows.total(after))
    }

    @Test
    fun `no measurements leaves the list untouched`() {
        val rows = SessionStorageRows.sorted(listOf(info("a", 10), info("b", 20)))
        assertSame(rows, SessionStorageRows.applyMeasurements(rows, emptyMap()))
        assertTrue(SessionStorageRows.measureRoots(emptyList(), sessions, SessionStorage(tmp.newFolder("f"))).isEmpty())
    }

    @Test
    fun `equal sizes keep a stable order`() {
        val rows = SessionStorageRows.sorted(listOf(info("b", 5), info("a", 5), info("c", 9)))
        assertEquals(listOf("c", "a", "b"), rows.map { it.id })
    }
}
