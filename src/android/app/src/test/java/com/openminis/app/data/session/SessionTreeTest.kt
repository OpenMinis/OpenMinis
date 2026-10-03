package com.openminis.app.data.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** [T-android-child-session-delete-storage] Tree walk + root aggregation. */
class SessionTreeTest {
    // P → {A, B}; A → {A1}; orphan X (parent missing); self-loop L
    private val children = mapOf("P" to listOf("A", "B"), "A" to listOf("A1"), "L" to listOf("L"))
    private val parentOf = mapOf<String, String?>(
        "P" to null, "A" to "P", "B" to "P", "A1" to "A", "X" to "missing", "L" to "L",
    )

    @Test
    fun `descendants are deepest first and cycle safe`() = runBlocking {
        assertEquals(listOf("A1", "A", "B"), SessionTree.descendantsDeepestFirst("P") { children[it] ?: emptyList() })
        assertEquals(listOf("A1", "A", "B", "P"), SessionTree.subtreeDeepestFirst("P") { children[it] ?: emptyList() })
        assertEquals(listOf("L"), SessionTree.subtreeDeepestFirst("L") { children[it] ?: emptyList() })
    }

    @Test
    fun `rootOf climbs to the top and tolerates dangling or looping parents`() {
        assertEquals("P", SessionTree.rootOf("A1", parentOf))
        assertEquals("P", SessionTree.rootOf("P", parentOf))
        assertEquals("X", SessionTree.rootOf("X", parentOf))
        assertEquals("L", SessionTree.rootOf("L", parentOf))
    }

    @Test
    fun `aggregateToRoots counts every byte exactly once under a root`() {
        val sizes = mapOf("P" to 10L, "A" to 5L, "B" to 3L, "A1" to 2L, "X" to 7L, "L" to 1L)
        val agg = SessionTree.aggregateToRoots(parentOf, sizes)
        assertEquals(setOf("P", "X", "L"), agg.keys)
        assertEquals(20L, agg["P"])
        assertEquals(7L, agg["X"])
        assertEquals(sizes.values.sum(), agg.values.sum())
        assertEquals(setOf("P", "A", "B", "A1"), SessionTree.membersOf("P", parentOf).toSet())
    }
}
