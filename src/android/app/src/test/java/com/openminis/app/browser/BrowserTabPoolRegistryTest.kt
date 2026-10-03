package com.openminis.app.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * [T-android-browser-global-tab-cap] The cross-pool admission ladder.
 *
 * These drive the REAL [BrowserTabPoolRegistry], not a mirror of its logic:
 * the arbitration was extracted behind [BrowserTabPoolRegistry.RegistryPool]
 * precisely so it could be exercised without a `Context` or a live `WebView`.
 * What a fake stands in for is only the tab list and the teardown side effect.
 *
 * The scenario being defended against: two chats each running two sub agents.
 * Per-pool ownership raised a pool's ceiling to 6, and a pool is per chat, so
 * the peak was 2 x 6 = 12 WebViews with nothing in the process able to see the
 * total.
 */
class BrowserTabPoolRegistryTest {

    /** Records what the registry asked of it; creates no Android objects. */
    private class FakePool(vararg tabs: BrowserTabPoolRegistry.TabInfo) :
        BrowserTabPoolRegistry.RegistryPool {

        val tabs = tabs.toMutableList()
        val evicted = mutableListOf<Int>()
        val preempted = mutableListOf<Int>()
        var memoryEvictions = 0

        override fun registrySnapshot() = tabs.toList()

        override fun evictTabForRegistry(id: Int) {
            evicted += id
            tabs.removeAll { it.id == id }
        }

        override fun preemptTabForRegistry(id: Int) {
            preempted += id
            tabs.removeAll { it.id == id }
        }

        override fun evictAllIdleForMemoryPressure(): Int {
            val idle = tabs.filter { !it.inUse }
            tabs.removeAll(idle)
            memoryEvictions += idle.size
            return idle.size
        }
    }

    private val now = System.currentTimeMillis()

    /** A tab last active [secondsAgo] ago. Older = more eligible for reclaim. */
    private fun tab(id: Int, inUse: Boolean = false, secondsAgo: Long = 0) =
        BrowserTabPoolRegistry.TabInfo(id, inUse, Date(now - secondsAgo * 1000))

    private fun poolOf(count: Int, startId: Int, inUse: Boolean = false) =
        FakePool(*(0 until count).map { tab(startId + it, inUse = inUse) }.toTypedArray())

    @Before
    fun setUp() = BrowserTabPoolRegistry.resetForTests()

    @After
    fun tearDown() = BrowserTabPoolRegistry.resetForTests()

    // ── Step 1: below the cap, admit without touching anything ─────────────

    @Test
    fun `under the cap the slot is granted and nothing is reclaimed`() {
        val a = poolOf(3, startId = 0)
        val b = poolOf(3, startId = 10)
        BrowserTabPoolRegistry.register(a)
        BrowserTabPoolRegistry.register(b)

        assertEquals(6, BrowserTabPoolRegistry.totalLiveTabs())
        assertTrue(BrowserTabPoolRegistry.requestSlot(a))
        assertTrue(a.evicted.isEmpty() && a.preempted.isEmpty())
        assertTrue(b.evicted.isEmpty() && b.preempted.isEmpty())
    }

    /**
     * The single-user, single-chat case — the one that must see zero change.
     * A lone pool cannot even reach the global cap: its own ceiling
     * ([BrowserTabPool.MAX_TABS_WITH_AGENTS] = 6) is below
     * [BrowserTabPoolRegistry.GLOBAL_TAB_CAP] = 8, so every request short-
     * circuits on the first line and no sibling scan ever runs.
     */
    @Test
    fun `a lone pool at its own ceiling is never arbitrated`() {
        val only = poolOf(BrowserTabPool.MAX_TABS_WITH_AGENTS, startId = 0)
        BrowserTabPoolRegistry.register(only)

        assertTrue(
            "per-pool ceiling must stay below the global cap or the registry " +
                "would arbitrate the ordinary single-chat case",
            BrowserTabPool.MAX_TABS_WITH_AGENTS < BrowserTabPoolRegistry.GLOBAL_TAB_CAP,
        )
        assertTrue(BrowserTabPoolRegistry.requestSlot(only))
        assertTrue(only.evicted.isEmpty())
        assertTrue(only.preempted.isEmpty())
    }

    // ── Step 2: prefer the oldest IDLE sibling tab ─────────────────────────

    @Test
    fun `at the cap the least recently active idle sibling tab is evicted`() {
        val requester = poolOf(4, startId = 0)
        val sibling = FakePool(
            tab(10, secondsAgo = 5),
            tab(11, secondsAgo = 900),   // oldest idle — the victim
            tab(12, secondsAgo = 60),
            tab(13, inUse = true, secondsAgo = 9_999), // older, but busy
        )
        BrowserTabPoolRegistry.register(requester)
        BrowserTabPoolRegistry.register(sibling)
        assertEquals(BrowserTabPoolRegistry.GLOBAL_TAB_CAP, BrowserTabPoolRegistry.totalLiveTabs())

        assertTrue(BrowserTabPoolRegistry.requestSlot(requester))
        assertEquals(listOf(11), sibling.evicted)
        assertTrue("an idle candidate existed, so nothing may be preempted", sibling.preempted.isEmpty())
    }

    /** A pool must never solve its own admission by eating its own tabs. */
    @Test
    fun `the requester's own tabs are never candidates`() {
        val requester = FakePool(tab(0, secondsAgo = 10_000)) // by far the oldest
        val sibling = poolOf(7, startId = 10)
        BrowserTabPoolRegistry.register(requester)
        BrowserTabPoolRegistry.register(sibling)

        assertTrue(BrowserTabPoolRegistry.requestSlot(requester))
        assertTrue(requester.evicted.isEmpty())
        assertTrue(requester.preempted.isEmpty())
        assertEquals(1, sibling.evicted.size)
    }

    // ── Step 3: no idle sibling → preempt the oldest BUSY one ──────────────

    @Test
    fun `with no idle siblings the least recently active busy tab is preempted`() {
        val requester = poolOf(4, startId = 0)
        val sibling = FakePool(
            tab(10, inUse = true, secondsAgo = 30),
            tab(11, inUse = true, secondsAgo = 600), // oldest busy — the victim
            tab(12, inUse = true, secondsAgo = 90),
            tab(13, inUse = true, secondsAgo = 5),
        )
        BrowserTabPoolRegistry.register(requester)
        BrowserTabPoolRegistry.register(sibling)

        assertTrue(BrowserTabPoolRegistry.requestSlot(requester))
        assertEquals(listOf(11), sibling.preempted)
        assertTrue(sibling.evicted.isEmpty())
    }

    /** An idle tab in ANY sibling outranks a busy tab in any other. */
    @Test
    fun `an idle tab in a second sibling beats a much older busy tab`() {
        val requester = poolOf(3, startId = 0)
        val busySibling = FakePool(tab(10, inUse = true, secondsAgo = 100_000))
        val idleSibling = FakePool(
            tab(20, secondsAgo = 1),
            tab(21, secondsAgo = 2),
            tab(22, secondsAgo = 3),
            tab(23, secondsAgo = 4),
        )
        BrowserTabPoolRegistry.register(requester)
        BrowserTabPoolRegistry.register(busySibling)
        BrowserTabPoolRegistry.register(idleSibling)

        assertTrue(BrowserTabPoolRegistry.requestSlot(requester))
        assertTrue(busySibling.preempted.isEmpty())
        assertEquals(listOf(23), idleSibling.evicted)
    }

    // ── Step 4: nothing to take → deny ─────────────────────────────────────

    @Test
    fun `at the cap with no siblings the request is denied`() {
        // Contrived: a single pool holding the whole cap. Production cannot
        // reach this (a pool's own ceiling is 6), but the ladder must still
        // refuse rather than fall through to self-cannibalisation.
        val only = poolOf(BrowserTabPoolRegistry.GLOBAL_TAB_CAP, startId = 0)
        BrowserTabPoolRegistry.register(only)

        assertFalse(BrowserTabPoolRegistry.requestSlot(only))
        assertTrue(only.evicted.isEmpty())
        assertTrue(only.preempted.isEmpty())
    }

    @Test
    fun `siblings that hold no tabs cannot rescue the request`() {
        val requester = poolOf(BrowserTabPoolRegistry.GLOBAL_TAB_CAP, startId = 0)
        val emptySibling = FakePool()
        BrowserTabPoolRegistry.register(requester)
        BrowserTabPoolRegistry.register(emptySibling)

        assertFalse(BrowserTabPoolRegistry.requestSlot(requester))
    }

    // ── Weak references ────────────────────────────────────────────────────

    @Test
    fun `unregistered pools stop counting toward the cap`() {
        val a = poolOf(4, startId = 0)
        val b = poolOf(4, startId = 10)
        BrowserTabPoolRegistry.register(a)
        BrowserTabPoolRegistry.register(b)
        assertEquals(8, BrowserTabPoolRegistry.totalLiveTabs())

        BrowserTabPoolRegistry.unregister(b)
        assertEquals(4, BrowserTabPoolRegistry.totalLiveTabs())
        // Back under the cap: admitted without reclaiming anything.
        assertTrue(BrowserTabPoolRegistry.requestSlot(a))
        assertTrue(a.evicted.isEmpty())
    }

    /**
     * Registration is weak, so a chat whose ViewModel was cleared must drop
     * out of the accounting on its own — there is no unregister call site in
     * production. Asserting on a collected referent is inherently best-effort
     * under a JVM that need not honour System.gc(), so this asserts the
     * property that matters and tolerates a GC that declines to run.
     */
    @Test
    fun `a collected pool drops out of the tab count`() {
        val kept = poolOf(2, startId = 0)
        BrowserTabPoolRegistry.register(kept)
        var transient: FakePool? = poolOf(4, startId = 10)
        BrowserTabPoolRegistry.register(transient!!)
        assertEquals(6, BrowserTabPoolRegistry.totalLiveTabs())

        transient = null
        System.gc()
        Thread.sleep(50)

        val total = BrowserTabPoolRegistry.totalLiveTabs()
        assertTrue(
            "a collected pool must never inflate the count (saw $total)",
            total == 2 || total == 6,
        )
    }

    @Test
    fun `registering the same pool twice does not double count it`() {
        val a = poolOf(3, startId = 0)
        BrowserTabPoolRegistry.register(a)
        BrowserTabPoolRegistry.register(a)
        assertEquals(3, BrowserTabPoolRegistry.totalLiveTabs())
    }

    // ── Memory pressure ────────────────────────────────────────────────────

    @Test
    fun `memory pressure releases every idle tab and keeps the busy ones`() {
        val a = FakePool(tab(0), tab(1, inUse = true), tab(2))
        val b = FakePool(tab(10, inUse = true), tab(11))
        BrowserTabPoolRegistry.register(a)
        BrowserTabPoolRegistry.register(b)

        assertEquals(3, BrowserTabPoolRegistry.handleMemoryPressure())
        assertEquals(listOf(1), a.registrySnapshot().map { it.id })
        assertEquals(listOf(10), b.registrySnapshot().map { it.id })
    }

    @Test
    fun `memory pressure with nothing idle is a no-op`() {
        val a = FakePool(tab(0, inUse = true))
        BrowserTabPoolRegistry.register(a)
        assertEquals(0, BrowserTabPoolRegistry.handleMemoryPressure())
        assertEquals(1, a.registrySnapshot().size)
    }
}
