package com.openminis.app.ui.onboarding

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-onboarding-model-fetch-fallback] Onboarding's "Select Models" page spun
 * forever when the provider's model list could not be fetched. The page now
 * runs its own bounded fetch and, when it ends with the page still empty,
 * shows the provider's error, Retry and Add Model Manually. iOS parity:
 * 7d17d29c4 / OnboardingModelFetchTests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingModelFetchTest {

    @Test
    fun `fast providers finish with their errors and no timeout`() = runTest {
        val r = OnboardingModelFetch.run(listOf("a", "b")) { if (it == "b") "401 Unauthorized" else null }
        assertFalse(r.timedOut)
        assertEquals(listOf("401 Unauthorized"), r.errors)
    }

    @Test
    fun `a slow provider times out at the page budget, and is cancelled`() = runTest {
        var cancelled = false
        val r = OnboardingModelFetch.run(listOf("slow")) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        assertTrue(r.timedOut)
        assertEquals(OnboardingModelFetch.TIMEOUT_MS, currentTime)
        assertTrue("the loser observes cancellation", cancelled)
    }

    @Test
    fun `providers run in parallel within one budget`() = runTest {
        val r = OnboardingModelFetch.run(listOf(1, 2, 3)) { delay(15_000); null }
        assertFalse(r.timedOut)
        assertEquals("three 15 s fetches take 15 s, not 45", 15_000L, currentTime)
    }

    @Test
    fun `a thrown error is reported as its message and does not sink the others`() = runTest {
        val r = OnboardingModelFetch.run(listOf("boom", "ok")) {
            if (it == "boom") throw IllegalStateException("connect timed out") else "404 Not Found"
        }
        assertFalse(r.timedOut)
        assertEquals(setOf("connect timed out", "404 Not Found"), r.errors.toSet())
    }

    @Test
    fun `the budget is within the 15-20 s spec`() {
        assertTrue(OnboardingModelFetch.TIMEOUT_MS in 15_000L..20_000L)
    }

    // ---- wiring (Compose/network need a device) ----------------------------

    private val screen = ProductionSources.read("ui/onboarding/OnboardingModelSelectionScreen.kt")

    @Test
    fun `the page fetches on its own only while empty, and keeps the normal refresh otherwise`() {
        val effect = screen.substringAfter("LaunchedEffect(Unit) {").substringBefore("\n    }\n")
        assertTrue(effect.contains("if (allEntries.isEmpty()) {\n            runBoundedFetch()"))
        assertTrue(effect.contains("providerRepository.triggerAsyncModelReconcile(instance.id)"))
        assertTrue("no auto re-fetch after returning from Add Custom Model", effect.contains("if (fetchAttempted) return@LaunchedEffect"))
    }

    @Test
    fun `the failure section replaces the spinner only after a finished fetch left the page empty`() {
        assertTrue(screen.contains("if (allEntries.isEmpty() && !fetching && failureDetail != null) {"))
        assertTrue(screen.contains("if (fetching) return // Retry while in flight is a no-op."))
        assertTrue(screen.contains("onRetry = { runBoundedFetch() }"))
        assertTrue(screen.contains("if (visibleEntryCount(ids) == 0) {"))
    }

    @Test
    fun `manual add goes to the provider's Add Custom Model screen`() {
        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        val route = nav.substringAfter("composable(Routes.ONBOARDING_MODELS)").substringBefore("composable(")
        assertTrue(route.contains("navController.safeNavigate(Routes.addCustomModel(instanceId))"))
    }

    @Test
    fun `refreshModels reports the vendor error without changing its fallback`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(repo.contains("onVendorError: ((String) -> Unit)? = null,"))
        val catch = repo.substringAfter("android.util.Log.e(\"ProviderRepo\", \"refreshModels fetch error:")
            .substringBefore("emptyList()")
        assertTrue(catch.contains("onVendorError?.invoke("))
    }
}
