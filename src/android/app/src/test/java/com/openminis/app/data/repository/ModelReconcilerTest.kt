package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-refresh-outlives-screen] (GH#265) A model refresh started by a
 * screen must finish even when that screen is popped right after.
 */
class ModelReconcilerTest {

    private val xai = ProviderInstance(id = "x1", label = "xAI", providerType = ProviderType.xAI,
        credentialType = com.openminis.app.data.model.ProviderCredential.oauth)

    @Test
    fun `the refresh completes after the screen that started it is gone`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = CoroutineScope(SupervisorJob() + dispatcher)
        val screenScope = CoroutineScope(Job() + dispatcher)
        val network = CompletableDeferred<Unit>()
        var refreshed: Pair<String, Boolean>? = null
        val reconciler = ModelReconciler(repoScope, { if (it == "x1") xai else null }) { inst, force ->
            network.await() // the fetch is in flight...
            refreshed = inst.id to force
        }

        // Save: start the refresh, then onSaved() pops the screen.
        var job: Job? = null
        screenScope.launch { job = reconciler.trigger("x1", forceRefresh = false) }
        testScheduler.runCurrent()
        screenScope.cancel() // composition disposed

        network.complete(Unit) // ...and the response arrives afterwards
        testScheduler.advanceUntilIdle()
        assertEquals("x1" to false, refreshed)
        assertTrue(job!!.isCompleted && !job!!.isCancelled)
        repoScope.cancel()
    }

    @Test
    fun `the old shape - launching on the screen scope - loses the refresh (the bug)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val screenScope = CoroutineScope(Job() + dispatcher)
        val network = CompletableDeferred<Unit>()
        var refreshed = false
        screenScope.launch { network.await(); refreshed = true }
        testScheduler.runCurrent()
        screenScope.cancel()
        network.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(refreshed)
    }

    @Test
    fun `the instance is looked up when the job runs, and a deleted one is skipped`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = CoroutineScope(SupervisorJob() + dispatcher)
        var calls = 0
        val reconciler = ModelReconciler(repoScope, { null }) { _, _ -> calls++ }
        reconciler.trigger("gone", forceRefresh = true)
        testScheduler.advanceUntilIdle()
        assertEquals(0, calls)
        repoScope.cancel()
    }

    @Test
    fun `a failing refresh is contained and does not break the next one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = CoroutineScope(SupervisorJob() + dispatcher)
        var calls = 0
        val reconciler = ModelReconciler(repoScope, { xai }) { _, _ ->
            calls++
            if (calls == 1) error("401 from provider")
        }
        reconciler.trigger("x1", forceRefresh = true)
        reconciler.trigger("x1", forceRefresh = true)
        testScheduler.advanceUntilIdle()
        assertEquals(2, calls)
        repoScope.cancel()
    }

    @Test
    fun `save and sign-in paths use the repository scope, and sign-in forces a refresh`() {
        fun src(p: String) = File("src/main/java/com/openminis/app/$p").readText()
        val add = src("ui/settings/AddProviderScreen.kt")
        assertFalse(add.contains("scope.launch { providerRepository.refreshModels(instance) }"))
        assertEquals(3, Regex("""providerRepository\.triggerAsyncModelReconcile\(instance\.id\)\s*\n\s*onSaved\(\)""").findAll(add).count())
        val detail = src("ui/settings/ProviderDetailScreen.kt")
        val signedIn = detail.substringAfter("if (token != null) {").substringBefore("} catch (e: Exception)")
        assertTrue(signedIn.contains("providerRepository.triggerAsyncModelReconcile(instance.id, forceRefresh = true)"))
    }
}
