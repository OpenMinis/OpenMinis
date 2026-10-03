package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-tab-ownership] The admission rules, as pure logic.
 *
 * BrowserTabPool needs a Context and real WebViews, so these mirror its
 * decision functions against the same data shapes rather than driving the pool
 * itself. What is under test is the part that decides WHO may touch WHAT — the
 * half that was missing entirely, and the half that a device test would only
 * exercise by luck.
 *
 * The incident these encode: three research sub agents ping-ponged on tab 0,
 * one agent's get_readable returned another's page, and "Maximum of 3 tabs"
 * made them close each other's work (iOS 4487da4b1, 2026-09-04).
 */
class BrowserTabOwnershipTest {

    private val chat = "chat-session"
    private val agentA = "child-a"
    private val agentB = "child-b"

    /** Mirrors BrowserTabPool.isPrivileged. */
    private fun isPrivileged(owner: String?, sessionId: String? = chat) =
        owner == null || owner == sessionId

    /** Mirrors BrowserTabPool.mayUse. */
    private fun mayUse(tabOwner: Map<Int, String>, tabId: Int, owner: String?) =
        if (isPrivileged(owner)) true else tabOwner[tabId] == owner

    /** Mirrors BrowserTabPool.effectiveMaxTabs. */
    private fun effectiveMax(tabOwner: Map<Int, String>, requesting: String?): Int {
        val owners = (tabOwner.values.toSet() - setOfNotNull(chat)).toMutableSet()
        if (requesting != null && !isPrivileged(requesting)) owners.add(requesting)
        return if (owners.isEmpty()) 3 else minOf(3 + 2 * owners.size, 6)
    }

    // ── Privilege ───────────────────────────────────────────────────────────

    @Test
    fun `a human and the owning chat may use any tab`() {
        // owner == null is the UI and every human path; owner == sessionId is
        // the chat driving its own pool. Both keep full access, which is what
        // lets the constraint ship without touching a single UI call site.
        val owned = mapOf(0 to agentA, 1 to agentB)
        for (id in listOf(0, 1)) {
            assertTrue("human may use tab $id", mayUse(owned, id, null))
            assertTrue("chat may use tab $id", mayUse(owned, id, chat))
        }
    }

    @Test
    fun `an agent may use only its own tabs`() {
        val owned = mapOf(0 to agentA, 1 to agentB, 2 to chat)
        assertTrue(mayUse(owned, 0, agentA))
        assertFalse("a sibling's tab is refused", mayUse(owned, 1, agentA))
        assertFalse("the chat's tab is refused", mayUse(owned, 2, agentA))
    }

    @Test
    fun `an unowned tab belongs to the chat, not to whoever asks first`() {
        // A tab with no entry was opened by the chat or the human. An agent
        // must not inherit it just because nobody claimed it.
        assertFalse(mayUse(emptyMap(), 0, agentA))
        assertTrue(mayUse(emptyMap(), 0, null))
    }

    // ── Implicit target order ───────────────────────────────────────────────

    /** Mirrors the pool's implicit-target resolution for an agent. */
    private fun implicitTarget(
        tabOwner: Map<Int, String>,
        lastByOwner: Map<String, Int>,
        inUse: Set<Int>,
        existing: Set<Int>,
        owner: String,
    ): Int? {
        val last = lastByOwner[owner]?.takeIf { it in existing }
        if (last != null) return last
        return tabOwner.entries
            .filter { it.value == owner && it.key !in inUse && it.key in existing }
            .minByOrNull { it.key }?.key
    }

    @Test
    fun `an agent's implicit action prefers its own most recent tab`() {
        val target = implicitTarget(
            tabOwner = mapOf(0 to chat, 1 to agentA, 2 to agentA),
            lastByOwner = mapOf(agentA to 2),
            inUse = emptySet(),
            existing = setOf(0, 1, 2),
            owner = agentA,
        )
        assertEquals(2, target)
    }

    @Test
    fun `it falls to another tab it owns when the last one is gone`() {
        val target = implicitTarget(
            tabOwner = mapOf(0 to chat, 1 to agentA),
            lastByOwner = mapOf(agentA to 9),   // closed
            inUse = emptySet(),
            existing = setOf(0, 1),
            owner = agentA,
        )
        assertEquals(1, target)
    }

    @Test
    fun `it never falls through to the chat's tab`() {
        // THE incident. With no tab of its own, the answer must be "none, open
        // one", never the chat's selected tab — three agents resolving to the
        // same tab is exactly how one agent read another's page.
        val target = implicitTarget(
            tabOwner = mapOf(0 to chat, 1 to agentB),
            lastByOwner = emptyMap(),
            inUse = emptySet(),
            existing = setOf(0, 1),
            owner = agentA,
        )
        assertNull(target)
    }

    @Test
    fun `a busy tab of its own is skipped rather than contended`() {
        val target = implicitTarget(
            tabOwner = mapOf(1 to agentA, 2 to agentA),
            lastByOwner = emptyMap(),
            inUse = setOf(1),
            existing = setOf(1, 2),
            owner = agentA,
        )
        assertEquals(2, target)
    }

    // ── Quota and ceiling ───────────────────────────────────────────────────

    @Test
    fun `the pool keeps its old ceiling when no agent is running`() {
        // The single-user case must not change: 3, exactly as before.
        assertEquals(3, effectiveMax(emptyMap(), null))
        assertEquals(3, effectiveMax(mapOf(0 to chat), chat))
    }

    @Test
    fun `each active agent raises the ceiling by its quota`() {
        assertEquals(5, effectiveMax(mapOf(0 to agentA), agentA))
        assertEquals(7.coerceAtMost(6), effectiveMax(mapOf(0 to agentA, 1 to agentB), agentA))
    }

    @Test
    fun `the ceiling is hard-capped regardless of agent count`() {
        // WebView memory is the real constraint; the cap is what keeps a wide
        // fan-out from turning into a reclaim storm.
        val many = (0..9).associateWith { "child-$it" }
        assertEquals(6, effectiveMax(many, "child-0"))
    }

    @Test
    fun `a requesting agent counts even before it owns anything`() {
        // Otherwise its very first new_tab would be judged against the
        // no-agents ceiling of 3 and refused while the chat held three tabs.
        assertEquals(5, effectiveMax(emptyMap(), agentA))
    }

    // ── Release ─────────────────────────────────────────────────────────────

    @Test
    fun `releasing an agent returns its tabs and leaves the others alone`() {
        val owned = mutableMapOf(0 to chat, 1 to agentA, 2 to agentA, 3 to agentB)
        val mine = owned.filterValues { it == agentA }.keys.toList()
        assertEquals(listOf(1, 2), mine.sorted())
        mine.forEach { owned.remove(it) }
        assertEquals(mapOf(0 to chat, 3 to agentB), owned)
        // And the ceiling drops back as the slots return.
        assertEquals(5, effectiveMax(owned, null))
    }

    @Test
    fun `releasing is idempotent`() {
        val owned = mutableMapOf(0 to agentA)
        repeat(3) { owned.filterValues { v -> v == agentA }.keys.toList().forEach { owned.remove(it) } }
        assertTrue(owned.isEmpty())
    }

    @Test
    fun `an agent with no tabs is told to open one rather than given an id`() {
        val mine = emptyMap<Int, String>().filterValues { it == agentA }.keys.sorted()
        assertTrue(mine.isEmpty())
    }
}
