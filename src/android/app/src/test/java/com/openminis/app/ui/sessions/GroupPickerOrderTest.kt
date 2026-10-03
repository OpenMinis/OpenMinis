package com.openminis.app.ui.sessions

import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-group-picker-recent] The Move to Group picker lists the most
 * recently active group first: the later of its newest member session's
 * updatedAt and its own record time; ties keep the incoming order.
 */
class GroupPickerOrderTest {

    private fun folder(id: String, created: Long, updated: Long = created) =
        FolderEntity(id = id, name = id, createdAt = created, updatedAt = updated)

    private fun ids(fs: List<FolderEntity>) = fs.map { it.id }

    @Test
    fun `the group whose sessions changed last comes first`() {
        val work = folder("work", created = 100)
        val home = folder("home", created = 200)
        val trip = folder("trip", created = 300)
        val activity = mapOf("work" to 9_000L, "home" to 5_000L, "trip" to 1_000L)
        assertEquals(listOf("work", "home", "trip"), ids(groupPickerOrder(listOf(trip, home, work), activity)))
    }

    @Test
    fun `a group edited after its last session change ranks by the edit`() {
        val renamed = folder("renamed", created = 100, updated = 8_000)
        val busy = folder("busy", created = 100)
        val activity = mapOf("renamed" to 1_000L, "busy" to 5_000L)
        assertEquals(listOf("renamed", "busy"), ids(groupPickerOrder(listOf(busy, renamed), activity)))
    }

    @Test
    fun `an empty group ranks by its own record time`() {
        val emptyNew = folder("emptyNew", created = 7_000)
        val emptyOld = folder("emptyOld", created = 50)
        val used = folder("used", created = 10)
        val activity = mapOf("used" to 4_000L)
        assertEquals(listOf("emptyNew", "used", "emptyOld"), ids(groupPickerOrder(listOf(emptyOld, used, emptyNew), activity)))
    }

    @Test
    fun `ties keep the incoming order`() {
        val a = folder("a", created = 100)
        val b = folder("b", created = 100)
        val c = folder("c", created = 100)
        assertEquals(listOf("b", "a", "c"), ids(groupPickerOrder(listOf(b, a, c), emptyMap())))
        val same = mapOf("a" to 500L, "b" to 500L)
        assertEquals(listOf("a", "b", "c"), ids(groupPickerOrder(listOf(a, b, c), same)))
    }

    @Test
    fun `the input list is not reordered in place`() {
        val input = listOf(folder("old", created = 1), folder("new", created = 2))
        groupPickerOrder(input, emptyMap())
        assertEquals(listOf("old", "new"), ids(input))
    }
}
