package com.openminis.app.backup

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * Guards the Stop button added in 0f289f9ab (T-android-restore-ui) on top of
 * the batched importer from faa0fdd88 / bb63c7978 (T-android-restore-perf).
 *
 * 0f289f9ab put `coroutineContext.ensureActive()` inside importChats' loops and
 * a CancellationException clause in BackupViewModel.startRestore. It did NOT
 * touch BackupImporter.importBody, whose per-category wrapper is
 *
 *     val categoryReport = try { when (category) { … } }
 *                          catch (e: Exception) { CategoryReport(…, failed = …) }
 *
 * CancellationException IS an Exception, so the throw from ensureActive() is
 * caught there: chats is reported "failed", and the loop goes on to restore
 * shared files, skills, memory, MCP servers, providers and env vars — none of
 * which check for cancellation — after the user pressed Stop. The outer
 * withContext(Dispatchers.IO) cannot return until that block finishes, so the
 * old job's `finally` (isRunning=false, restoreJob=null) runs minutes later —
 * by which time stopRunningRestore() has already re-enabled the button and a
 * second restore may be running; the stale finally then nulls the NEW job's
 * handle and flips isRunning off under it (Stop becomes a no-op).
 *
 * Proposed fix:
 *   1. importBody: `catch (e: CancellationException) { throw e }` before the
 *      generic catch (and ensureActive() between categories).
 *   2. startRestore: capture the launched Job and only clear restoreJob /
 *      isRunning in `finally` when `restoreJob === thisJob`.
 *
 * Tests prefixed BUG fail on current code by design.
 */
class RestoreCancellationPropagationTest {

    private val importer by lazy { ProductionSources.read("backup/BackupImporter.kt") }
    private val vm by lazy { ProductionSources.read("ui/settings/backup/BackupViewModel.kt") }

    /** The try/catch that wraps each category in importBody. */
    private val categoryWrapper: String by lazy {
        importer.substringAfter("val categoryReport = try {")
            .substringBefore("categoryReport?.let {")
    }

    // -- why it matters: the language rule the wrapper trips over --------------

    @Test
    fun `a generic catch of Exception swallows coroutine cancellation`() {
        // Port of importBody's category loop, reduced to its control flow.
        val ran = mutableListOf<String>()
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                for (cat in listOf("chats", "skills", "providers")) {
                    try {
                        ran += cat
                        if (cat == "chats") {
                            // The user pressed Stop mid-chats.
                            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                            coroutineContext.ensureActive()
                        }
                    } catch (e: Exception) {
                        // importBody's generic clause: logs "category failed", continues.
                    }
                }
            }
            job.start(); job.join()
        }
        assertEquals(
            "documents the current wrapper's behaviour: later categories still run",
            listOf("chats", "skills", "providers"), ran,
        )
    }

    @Test
    fun `rethrowing CancellationException first stops the loop`() {
        val ran = mutableListOf<String>()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                for (cat in listOf("chats", "skills", "providers")) {
                    try {
                        ran += cat
                        if (cat == "chats") {
                            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                            coroutineContext.ensureActive()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // failure path
                    }
                }
            }
            job.join()
        }
        assertEquals(listOf("chats"), ran)
    }

    @Test
    fun `withContext cannot return before a non-cooperative block finishes`() {
        // Why the stale `finally` runs late: cancelling the outer job does not
        // interrupt the IO block; withContext waits for it.
        var blockFinished = false
        var finallyRanAfterBlock = false
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try {
                    withContext(Dispatchers.IO) {
                        // Non-cooperative work (like importSkills/importProviders).
                        val until = System.nanoTime() + 150_000_000
                        while (System.nanoTime() < until) { /* busy */ }
                        blockFinished = true
                    }
                } finally {
                    finallyRanAfterBlock = blockFinished
                }
            }
            yield(); Thread.sleep(20)
            job.cancel()
            job.join()
        }
        assertTrue(blockFinished)
        assertTrue(finallyRanAfterBlock)
    }

    // -- the shipping code ------------------------------------------------------

    @Test
    fun `the category wrapper was located`() {
        assertTrue("importBody's category try/catch not found", categoryWrapper.contains("catch (e: Exception)"))
        assertTrue(categoryWrapper.contains("BackupCategory.CHATS -> importChats("))
    }

    @Test
    fun `BUG - importBody rethrows cancellation before its generic per-category catch`() {
        val generic = categoryWrapper.indexOf("catch (e: Exception)")
        val cancel = Regex("""catch \(e: (kotlinx\.coroutines\.)?CancellationException\)""")
            .find(categoryWrapper)?.range?.first ?: -1
        assertTrue(
            "importBody's per-category `catch (e: Exception)` swallows the CancellationException " +
                "thrown by importChats' ensureActive(): Stop marks chats failed and restores every " +
                "remaining category anyway",
            cancel in 0 until generic,
        )
    }

    @Test
    fun `BUG - a superseded restore job's finally cannot clear a newer job's state`() {
        val launched = vm.substringAfter("restoreJob = viewModelScope.launch {")
            .substringBefore("fun stopRunningRestore()")
        val fin = launched.substringAfter("} finally {").substringBefore("\n            }")
        val clearsJob = fin.contains("restoreJob = null")
        val clearsRunning = fin.contains("_isRunning.value = false")
        val guarded = fin.contains("===") || fin.contains("restoreJob == ")
        assertTrue(
            "startRestore's finally clears restoreJob/isRunning unconditionally; after Stop the " +
                "button is re-enabled at once, and the old job's late finally clobbers a newer restore",
            !(clearsJob || clearsRunning) || guarded,
        )
    }

    @Test
    fun `stopRunningRestore re-enables the button immediately (the precondition of the race)`() {
        val stop = vm.substringAfter("fun stopRunningRestore()").substringBefore("\n    }")
        assertTrue(stop.contains("_isRunning.value = false"))
        val start = vm.substringAfter("fun startRestore(").substringBefore("restoreJob = viewModelScope.launch")
        assertTrue("startRestore gates only on isRunning", start.contains("if (isRunning.value) return"))
    }
}
