package com.openminis.app.backup

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * [T-android-restore-cancel-empty-sessions] importChats commits sessions and
 * messages in separate transactions; a Stop during the messages loop rolled
 * the messages back and left the freshly-inserted sessions behind, empty.
 */
class RestoreCancelEmptySessionsTest {

    /** In-memory stand-in for the sessions/messages tables. */
    private class FakeDb(sessions: Map<String, Int>) {
        val messages = sessions.toMutableMap()
        suspend fun count(id: String): Int = messages[id] ?: 0
        suspend fun delete(id: String) { messages.remove(id) }
    }

    @Test
    fun `deletes only empty sessions among those passed in`() = runBlocking {
        val db = FakeDb(mapOf("new-empty" to 0, "new-full" to 3, "old-empty" to 0))
        val deleted = BackupImporter.deleteEmptySessions(
            listOf("new-empty", "new-full"), db::count, db::delete,
        )
        assertEquals(listOf("new-empty"), deleted)
        assertTrue("session with messages kept", "new-full" in db.messages)
        // A pre-existing empty session is never in the id list, so never touched.
        assertTrue("pre-existing empty session kept", "old-empty" in db.messages)
    }

    @Test
    fun `one failing lookup does not stop the rest`() = runBlocking {
        val deleted = mutableListOf<String>()
        val out = BackupImporter.deleteEmptySessions(
            listOf("boom", "a", "b"),
            messageCount = { if (it == "boom") error("db closed") else 0 },
            delete = { deleted += it },
        )
        assertEquals(listOf("a", "b"), out)
        assertEquals(listOf("a", "b"), deleted)
    }

    /**
     * Control-flow port of importChats' cancel path: the cleanup must still run
     * suspending DAO calls although the job is already cancelled, which only
     * works under NonCancellable, and the cancellation must still propagate.
     */
    @Test
    fun `cleanup runs under NonCancellable after Stop and cancellation propagates`() {
        val db = FakeDb(mapOf("s1" to 0, "s2" to 0))
        var propagated = false
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val newlyInserted = listOf("s1", "s2")
                try {
                    // Messages loop: the user presses Stop.
                    coroutineContext[Job]!!.cancel()
                    coroutineContext.ensureActive()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        BackupImporter.deleteEmptySessions(newlyInserted, {
                            yield() // a real suspending DAO call
                            db.count(it)
                        }, db::delete)
                    }
                    propagated = true
                    throw e
                }
            }
            job.start(); job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(propagated)
        assertTrue("both empty new sessions removed", db.messages.isEmpty())
    }

    // -- source guards: the wiring in importChats ------------------------------

    private val importer by lazy { ProductionSources.read("backup/BackupImporter.kt") }
    private val chats by lazy {
        importer.substringAfter("private suspend fun importChats(").substringBefore("// MARK: - Shared files")
    }

    @Test
    fun `only newly inserted sessions are recorded for cleanup`() {
        assertTrue(chats.contains("if (d.isNew) newlyInsertedSessionIds.add(id)"))
        // The Stale branch (pre-existing, locally newer) must not record it.
        val stale = chats.substringAfter("is BackupRecordMapper.Decoded.Stale -> {").substringBefore("}")
        assertFalse(stale.contains("newlyInsertedSessionIds"))
    }

    @Test
    fun `cancel handler wraps the messages loop and cleans up under NonCancellable`() {
        val tryStart = chats.indexOf("try {\n        // The manifest counts MESSAGES")
        val messagesLoop = chats.indexOf("readJsonl(dataDir, \"messages\")")
        val handler = chats.indexOf("} catch (e: kotlinx.coroutines.CancellationException) {")
        assertTrue(tryStart in 0 until messagesLoop)
        assertTrue(handler > messagesLoop)
        val body = chats.substring(handler).substringBefore("throw e")
        assertTrue(body.contains("withContext(NonCancellable)"))
        assertTrue(body.contains("deleteEmptySessions(\n                    newlyInsertedSessionIds"))
    }

    @Test
    fun `staging DB claim is gone`() {
        assertFalse(importer.contains("already runs into a staging DB"))
        val vm = ProductionSources.read("ui/settings/backup/BackupViewModel.kt")
        assertFalse(vm.contains("What survives: every batch the importer has already committed."))
    }
}
