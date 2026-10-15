package com.openminis.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.MessageVersionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-restore-txn-integration] The archive/restore pipeline against a REAL
 * in-memory Room database.
 *
 * RestoreOrderTest proves [ChatRepository.chronologicalRestoreOrder] as a pure
 * function, but the report's gap was the transaction itself:
 *  - `deleteMessagesAfter` archives + truncates in ONE `db.withTransaction`
 *    (an archiving failure must abort the truncation, not orphan it);
 *  - `restoreArchivedVersions` renumbers live rows through a scratch range so
 *    the UNIQUE (session_id, sort_order) index never sees two rows claiming
 *    one slot mid-flight — only a real SQLite can catch a violation;
 *  - only the RESTORED archive rows are retired; the pre-edit history of live
 *    rows must survive;
 *  - a repeat restore is a no-op (message_ids now exist live).
 *
 * Robolectric (not instrumented) so it runs in the plain JVM unit-test task
 * on every PR, same as the rest of the suite.
 */
@RunWith(RobolectricTestRunner::class)
// Plain android.app.Application, NOT MinisApp: the real app's onCreate wires
// RootfsManager against sandbox paths that don't exist under Robolectric
// (File(<null>) NPE at RootfsManager.kt:202). This test only needs a Context
// to hand Room an in-memory database — nothing from the app class.
@Config(sdk = [35], application = android.app.Application::class)
class RestoreArchivedVersionsIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var sessionId: String

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = null,
            versionDao = db.messageVersionDao(),
            db = db,
        )
        sessionId = repo.createSession(modelId = "test-model").id
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun msg(id: String, order: Int, at: Long, role: String = "user") = MessageEntity(
        id = id,
        sessionId = sessionId,
        role = role,
        partsJson = """[{"type":"text","text":"$id"}]""",
        createdAt = at,
        sortOrder = order,
    )

    private suspend fun liveOrder(): List<Pair<String, Int>> =
        db.chatDao().sortMetaForSession(sessionId)
            .sortedBy { it.sortOrder }
            .map { it.id to it.sortOrder }

    @Test
    fun `archive truncates and restore rebuilds chronology in one transaction each`() = runBlocking {
        val t0 = 1_000_000L
        // Five chronological messages; m3..m5 will be truncated away.
        val seeds = listOf(
            msg("m1", 0, t0 + 1, "user"),
            msg("m2", 1, t0 + 2, "assistant"),
            msg("m3", 2, t0 + 3, "user"),
            msg("m4", 3, t0 + 4, "assistant"),
            msg("m5", 4, t0 + 5, "user"),
        )
        seeds.forEach { db.chatDao().insertMessage(it) }

        // Pre-edit history of a LIVE row (m1) — the archive's real purpose.
        // Restore must retire ONLY the rows it resurrects, never this one.
        db.messageVersionDao().insertAll(
            listOf(
                MessageVersionEntity(
                    id = "v-m1-preedit",
                    messageId = "m1",
                    sessionId = sessionId,
                    versionIndex = 1,
                    role = "user",
                    partsJson = """[{"type":"text","text":"m1 old draft"}]""",
                    sourceCreatedAt = t0,
                    archivedAt = t0 + 1,
                )
            )
        )

        // ── Truncation: archive + delete in one transaction ──
        repo.deleteMessagesAfter(sessionId, keepCount = 2)

        assertEquals(listOf("m1" to 0, "m2" to 1), liveOrder())
        // 3 truncation archives (m3..m5) + the seeded pre-edit of m1.
        assertEquals(4, db.messageVersionDao().countForSession(sessionId))
        assertEquals(1, db.messageVersionDao().versionsFor("m1").size)

        // The user typed since the truncation.
        db.chatDao().insertMessage(msg("m6", 2, t0 + 6, "user"))
        assertEquals(3, db.chatDao().messageCountForSession(sessionId))
        assertEquals(
            3,
            repo.restorableArchivedMessageCount(sessionId),
        ) // m3/m4/m5 have archived versions and no live row

        // ── Restore ──
        val result = repo.restoreArchivedVersions(sessionId)

        assertEquals(3, result.restoredCount)
        assertNull(result.error)
        assertEquals(4, result.archiveCount) // 3 resurrected + 1 pre-edit

        // Chronology rebuilt: restored rows slot back where created_at puts
        // them, m6 stays last — NOT appended after it.
        assertEquals(
            listOf("m1" to 0, "m2" to 1, "m3" to 2, "m4" to 3, "m5" to 4, "m6" to 5),
            liveOrder(),
        )
        // Contiguous 0..5 — the scratch-range renumbering left no gaps and
        // never tripped the unique (session_id, sort_order) index.
        assertEquals((0..5).toList(), liveOrder().map { it.second })

        // Only the restored archive rows were retired; m1's pre-edit survives.
        assertEquals(0, db.messageVersionDao().versionsFor("m3").size)
        assertEquals(0, db.messageVersionDao().versionsFor("m4").size)
        assertEquals(0, db.messageVersionDao().versionsFor("m5").size)
        assertEquals(1, db.messageVersionDao().versionsFor("m1").size)

        // Restored rows carry a preview (no 64KB cell-scan fallback).
        val restored = db.chatDao().loadMessagesPage(sessionId, 2, 1).single()
        assertEquals("m3", restored.id)
        assertNotNull(restored.preview)

        // ── Repeat restore is a no-op: ids exist live now ──
        val again = repo.restoreArchivedVersions(sessionId)
        assertEquals(0, again.restoredCount)
        assertNull(again.error)
        assertEquals(listOf("m1" to 0, "m2" to 1, "m3" to 2, "m4" to 3, "m5" to 4, "m6" to 5), liveOrder())
    }

    @Test
    fun `truncation without a db handle still fails closed on archive failure`() = runBlocking {
        // Old JVM shape (no db): archiving failure must abort the truncation
        // rather than delete unarchived rows. Simulated by a versionDao that
        // throws on the grouped MAX lookup.
        val throwingDao = object : com.openminis.app.data.db.MessageVersionDao {
            override suspend fun insertAll(versions: List<MessageVersionEntity>) = error("unused")
            override suspend fun maxVersionIndex(messageId: String): Int = error("unused")
            override suspend fun maxVersionIndexesForSession(sessionId: String): List<com.openminis.app.data.db.MessageVersionIndexRow> = error("archive store broken")
            override suspend fun versionsFor(messageId: String): List<MessageVersionEntity> = error("unused")
            override suspend fun countForSession(sessionId: String): Int = error("unused")
            override suspend fun versionsForSession(sessionId: String): List<MessageVersionEntity> = error("unused")
            override suspend fun restorableMessageIds(sessionId: String): List<String> = error("unused")
            override suspend fun deleteByIds(ids: List<String>) = error("unused")
        }
        val noDbRepo = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = null,
            versionDao = throwingDao,
            db = null,
        )
        db.chatDao().insertMessage(msg("a", 0, 1L))
        db.chatDao().insertMessage(msg("b", 1, 2L))

        noDbRepo.deleteMessagesAfter(sessionId, keepCount = 1)

        // Truncation aborted: both rows still live, nothing lost unarchived.
        assertEquals(2, db.chatDao().messageCountForSession(sessionId))
    }
}
