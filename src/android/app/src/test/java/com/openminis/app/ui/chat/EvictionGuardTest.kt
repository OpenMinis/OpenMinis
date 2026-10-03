package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vm-evict-busy] / [T-android-vm-evict-orphan]
 *
 * The bounded ChatViewModel cache (478470224, 552d90538) decided "busy" from
 * `SessionActivityTracker.activeSessions` alone. That flag is raised inside the
 * stream job AFTER `acquireSlot()`, so a send waiting for a concurrency slot
 * read as idle and could be evicted — its `viewModelScope` cancelled, the
 * message gone with no reply and no error. A parent whose background sub
 * agents were still running read as idle too; evicting it unregistered the
 * queued-delegation starter and orphaned the child's finished callback.
 *
 * Separately, `HeadlessChatRunner` cached a ViewModelProvider per session and
 * only dropped it on session delete, so after an eviction it rebuilt a VM in
 * a cleared, untracked store — a second instance beside the UI's.
 *
 * The decision is pinned directly; the wiring as source facts (the store and
 * the runner are Android-runtime objects this JVM test cannot instantiate),
 * following ChatViewModelStoreEvictionTest.
 */
class EvictionGuardTest {

    // ---- the decision ------------------------------------------------------

    @Test
    fun `a send still waiting for a concurrency slot is busy`() {
        // Tracker idle (setActive not yet called), but the stream job is alive.
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = true, streamJobActive = true, hasAgentWork = false, hasQueuedPrompts = false))
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = true, hasAgentWork = false, hasQueuedPrompts = false))
    }

    @Test
    fun `a parent with running or queued sub agents is busy`() {
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = false, hasAgentWork = true, hasQueuedPrompts = false))
    }

    @Test
    fun `a session holding queued prompts is busy`() {
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = false, hasAgentWork = false, hasQueuedPrompts = true))
    }

    @Test
    fun `a genuinely idle session is evictable`() {
        assertFalse(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = false, hasAgentWork = false, hasQueuedPrompts = false))
    }

    // ---- the wiring --------------------------------------------------------

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val storeSrc by lazy { src("src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt") }
    private val vmSrc by lazy { src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }
    private val runnerSrc by lazy { src("src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt") }

    @Test
    fun `both eviction paths consult the same busy set, and it asks the view model`() {
        val trim = storeSrc.substringAfter("private fun trimToCapacity(").substringBefore("fun handleMemoryPressure(")
        val pressure = storeSrc.substringAfter("fun handleMemoryPressure(").substringBefore("fun release(")
        assertTrue("trimToCapacity must use busyKeys()", trim.contains("val busy = busyKeys()"))
        assertTrue("handleMemoryPressure must use busyKeys()", pressure.contains("val busy = busyKeys()"))
        val busy = storeSrc.substringAfter("private fun busyKeys(").substringBefore("fun ownerFor(")
        assertTrue("busyKeys must keep the tracker signal", busy.contains("SessionActivityTracker.activeSessions"))
        assertTrue("busyKeys must also ask each live VM", busy.contains("hasWorkInFlight()"))
    }

    @Test
    fun `the view model probe covers the stream job, agent work and queued prompts`() {
        val probe = vmSrc.substringAfter("fun hasWorkInFlight(): Boolean").substringBefore("fun selectGroup(")
        assertTrue(probe.contains("streamJob?.isActive == true"))
        assertTrue(probe.contains("hasAgentWork("))
        assertTrue(probe.contains("_promptQueue.value.isNotEmpty()"))
    }

    @Test
    fun `the headless runner validates its cached store before trusting it`() {
        assertTrue(
            "providerFor must go through liveCached, not read providers[] directly",
            runnerSrc.substringAfter("private fun providerFor(").substringBefore("private fun viewModel(")
                .contains("liveCached(sessionId)"),
        )
        assertTrue(
            "liveCached must ask ChatViewModelStore.isLiveStore",
            runnerSrc.substringAfter("private fun liveCached(").substringBefore("private fun providerFor(")
                .contains("ChatViewModelStore.isLiveStore(sessionId, cached.store)"),
        )
        assertTrue(
            "isLiveStore must be an identity check on the live map",
            storeSrc.contains("stores[resolveKey(sessionId)] === store"),
        )
    }

    @Test
    fun `the store being inserted is never the one evicted`() {
        // Measured on a Pixel 4a: with five concurrency slots held, opening a
        // sixth session logged "allocate store for <sid>" and "evict store for
        // <sid>" — the SAME id — 2ms apart, and the send died. trimToCapacity
        // runs inside ownerFor, BEFORE the caller builds the ChatViewModel, so
        // the new store answers no busy signal (there is no VM to ask yet) and
        // is also the least-recently-used entry. Excluding it is what makes
        // the pool run over to 6 instead of killing the arriving session.
        val trim = storeSrc.substringAfter("private fun trimToCapacity(").substringBefore("fun handleMemoryPressure(")
        assertTrue(
            "trimToCapacity must identify the key just inserted",
            trim.contains("val justInserted = inPool.lastOrNull()"),
        )
        assertTrue(
            "the just-inserted key must be excluded from the evictable set",
            trim.contains("it != justInserted"),
        )
    }

    @Test
    fun `existing() takes the same lock as the writers`() {
        val decl = storeSrc.substringBefore("fun existing(sessionId: String)").takeLast(80)
        assertTrue("existing() must be @Synchronized", decl.contains("@Synchronized"))
        assertFalse("existing() must not lock on `stores`", storeSrc.contains("synchronized(stores)"))
    }
}
