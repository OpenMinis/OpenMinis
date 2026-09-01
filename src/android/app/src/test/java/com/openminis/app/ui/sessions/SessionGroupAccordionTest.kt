package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-group-accordion] At most one session group is open at a time.
 *
 * The bug this pins: `collapsedFolderIds` stores the INVERSE — which groups are
 * shut — so an empty set means "collapse nothing", and every group on the home
 * screen unfolded at once. That is the state a fresh install starts in, and the
 * state a restore lands in, because neither has ever written the preference.
 *
 * It matters beyond tidiness: the floating mini-bar resolves its header with
 * `firstOrNull { !isCollapsed }`, which names the right group only while there
 * is exactly one. With several open, scrolling through the second group showed
 * the first group's name.
 */
class SessionGroupAccordionTest {

    private fun folder(id: String, at: Long) =
        FolderEntity(id = id, name = "F-$id", createdAt = at, updatedAt = at)

    private fun session(id: String, folderId: String?, at: Long) =
        ChatSessionEntity(
            id = id, modelId = "m", createdAt = at, updatedAt = at, folderId = folderId,
        )

    /** Ids of the blocks that came back expanded. */
    private fun openIds(blocks: List<FolderGroupBlock>) =
        blocks.filter { !it.isCollapsed }.map { it.folder.id }

    @Test
    fun `an untouched preference does not unfold every group`() {
        // The reported state: three groups, nothing ever collapsed by hand.
        val folders = listOf(folder("a", 30), folder("b", 20), folder("c", 10))
        val sessions = listOf(
            session("s1", "a", 300),
            session("s2", "b", 200),
            session("s3", "c", 100),
        )

        val (blocks, _) = partitionByFolder(sessions, folders, collapsedIds = emptySet())

        assertEquals("exactly one group may be open", 1, openIds(blocks).size)
    }

    @Test
    fun `the most recently active group is the one left open`() {
        val folders = listOf(folder("old", 10), folder("recent", 20))
        // partitionByFolder takes sessions in recency order; "recent" is first
        // seen, so it is the group whose content the user is closest to.
        val sessions = listOf(
            session("s1", "recent", 900),
            session("s2", "old", 100),
        )

        val (blocks, _) = partitionByFolder(sessions, folders, collapsedIds = emptySet())

        assertEquals(listOf("recent"), openIds(blocks))
    }

    @Test
    fun `an explicit collapse of the open group opens the next one, not none`() {
        val folders = listOf(folder("a", 30), folder("b", 20))
        val sessions = listOf(session("s1", "a", 300), session("s2", "b", 200))

        // "a" was open; the user collapsed it.
        val (blocks, _) = partitionByFolder(sessions, folders, collapsedIds = setOf("a"))

        assertEquals(listOf("b"), openIds(blocks))
    }

    @Test
    fun `a collapsed group carries no member rows`() {
        val folders = listOf(folder("a", 30), folder("b", 20))
        val sessions = listOf(session("s1", "a", 300), session("s2", "b", 200))

        val (blocks, _) = partitionByFolder(sessions, folders, collapsedIds = emptySet())

        val closed = blocks.filter { it.isCollapsed }
        assertTrue("expected at least one collapsed group", closed.isNotEmpty())
        closed.forEach {
            assertTrue("a collapsed group must not render rows", it.ids.isEmpty())
            // The count survives so the header can still say how many are inside.
            assertEquals(1, it.totalCount)
        }
    }

    @Test
    fun `groups with no members still render`() {
        // A group that vanishes when its last session leaves reads as data loss.
        val folders = listOf(folder("a", 30), folder("empty", 20))
        val sessions = listOf(session("s1", "a", 300))

        val (blocks, _) = partitionByFolder(sessions, folders, collapsedIds = emptySet())

        assertEquals(setOf("a", "empty"), blocks.map { it.folder.id }.toSet())
        assertEquals("exactly one group may be open", 1, openIds(blocks).size)
    }

    @Test
    fun `sessions outside any group stay ungrouped`() {
        val folders = listOf(folder("a", 30))
        val sessions = listOf(session("s1", "a", 300), session("loose", null, 200))

        val (_, ungrouped) = partitionByFolder(sessions, folders, collapsedIds = emptySet())

        assertEquals(listOf("loose"), ungrouped.map { it.id })
    }
}
