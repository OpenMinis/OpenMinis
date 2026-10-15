package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpStdioCap.Decision
import com.openminis.app.mcp.client.McpStdioCap.Entry
import com.openminis.app.mcp.client.McpStdioCap.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-stdio-cap] Contract tests for the live-STDIO-server cap decision
 * core (upstream 1.15-beta.2 parity). Pure JVM, no Android types.
 */
class McpStdioCapTest {

    private fun entry(id: String, used: Long, busy: Boolean = false) = Entry(id, used, busy)

    @Test
    fun `under cap - no eviction`() {
        val live = (1..3).map { entry("s$it", used = it * 100L) }
        assertEquals(Decision.Evict(emptyList()), McpStdioCap.decide(live, cap = 8))
    }

    @Test
    fun `at cap minus one - no eviction`() {
        val live = (1..7).map { entry("s$it", used = it * 100L) }
        assertEquals(Decision.Evict(emptyList()), McpStdioCap.decide(live, cap = 8))
    }

    @Test
    fun `at cap - evicts single LRU idle server`() {
        val live = listOf(
            entry("oldest", used = 100L),
            entry("newest", used = 900L),
        )
        assertEquals(Decision.Evict(listOf("oldest")), McpStdioCap.decide(live, cap = 2))
    }

    @Test
    fun `over cap - evicts LRU idle servers in age order`() {
        val live = listOf(
            entry("busy", used = 50L, busy = true),
            entry("old1", used = 100L),
            entry("old2", used = 200L),
            entry("old3", used = 300L),
        )
        // 4 live + 1 new = 5, cap 3 → need 2 evictions; busy one is protected.
        assertEquals(Decision.Evict(listOf("old1", "old2")), McpStdioCap.decide(live, cap = 3))
    }

    @Test
    fun `all busy at cap - refuses instead of killing a call in flight`() {
        val live = (1..8).map { entry("s$it", used = it * 100L, busy = true) }
        val decision = McpStdioCap.decide(live, cap = 8)
        assertTrue(decision is Decision.Refused)
        assertEquals(Refusal.ALL_BUSY, (decision as Decision.Refused).reason)
    }

    @Test
    fun `not enough idle - refuses`() {
        val live = listOf(
            entry("busy1", used = 100L, busy = true),
            entry("busy2", used = 200L, busy = true),
            entry("idle", used = 300L),
        )
        // 3 live + 1 new = 4, cap 2 → need 2 evictions, only 1 idle.
        assertTrue(McpStdioCap.decide(live, cap = 2) is Decision.Refused)
    }

    @Test
    fun `empty live set - never evicts`() {
        assertEquals(Decision.Evict(emptyList()), McpStdioCap.decide(emptyList(), cap = 1))
    }

    @Test
    fun `cap zero or negative - refuses`() {
        assertTrue(McpStdioCap.decide(emptyList(), cap = 0) is Decision.Refused)
    }

    @Test
    fun `default cap is 8`() {
        assertEquals(8, McpStdioCap.DEFAULT_MAX_LIVE)
    }
}
