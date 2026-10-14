package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-msg-version-restore] The recovered transcript is a timeline, not an
 * append. Restored rows go back where their created_at puts them; a live row
 * that shares a timestamp stays ahead of the restored one, because a retry
 * re-persists the kept turn at the same moment and the discarded predecessor
 * must not jump in front of it.
 */
class RestoreOrderTest {

    private fun live(id: String, at: Long) = ChatRepository.SortEntry(id, at)
    private fun back(id: String, at: Long) = ChatRepository.SortEntry(id, at, restored = true)

    @Test
    fun `restored rows slot in by time instead of trailing the live tail`() {
        val order = ChatRepository.chronologicalRestoreOrder(
            existing = listOf(live("kept-early", 100), live("typed-later", 300)),
            restored = listOf(back("lost", 200)),
        )
        assertEquals(listOf("kept-early", "lost", "typed-later"), order.map { it.id })
    }

    @Test
    fun `a shared timestamp keeps the live row ahead of the restored one`() {
        val order = ChatRepository.chronologicalRestoreOrder(
            existing = listOf(live("retried-turn", 100)),
            restored = listOf(back("discarded-turn", 100)),
        )
        assertEquals(listOf("retried-turn", "discarded-turn"), order.map { it.id })
    }

    @Test
    fun `nothing to restore leaves the live order untouched`() {
        val order = ChatRepository.chronologicalRestoreOrder(
            existing = listOf(live("a", 2), live("b", 1)),
            restored = emptyList(),
        )
        assertEquals(listOf("b", "a"), order.map { it.id })
    }
}
