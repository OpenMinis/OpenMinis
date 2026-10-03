package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.openminis.app.ProductionSources
import org.junit.Test

/**
 * [T-android-group-accordion] At most one session group is open at a time, and
 * [T-android-groups-collapsed-on-launch] only a group the user opened.
 *
 * The user's rule: every group is folded after a cold start, and opening one
 * folds whichever was open. The old model opened a group on its own at every
 * launch (the first one not in a persisted "collapsed" set, or a restored
 * "last expanded" id), which is what this pins against.
 *
 * The one-open invariant also matters to the floating mini-bar: it resolves
 * its header with `firstOrNull { !isCollapsed }`, which names the right group
 * only while there is at most one.
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

    private val threeFolders = listOf(folder("a", 30), folder("b", 20), folder("c", 10))
    private val threeSessions = listOf(
        session("s1", "a", 300),
        session("s2", "b", 200),
        session("s3", "c", 100),
    )

    @Test
    fun `a cold start opens no group`() {
        // Nothing expanded yet: the state on every launch.
        val (blocks, _) = partitionByFolder(threeSessions, threeFolders, expandedFolderId = null)

        assertEquals("every group must be folded", emptyList<String>(), openIds(blocks))
        assertEquals(3, blocks.size)
    }

    @Test
    fun `the most recently active group is not opened on the user's behalf`() {
        // The old fallback opened the first group in activity order.
        val (blocks, _) = partitionByFolder(threeSessions, threeFolders, expandedFolderId = null)

        assertTrue(blocks.first { it.folder.id == "a" }.isCollapsed)
    }

    @Test
    fun `the group the user opened is the only one open`() {
        val (blocks, _) = partitionByFolder(threeSessions, threeFolders, expandedFolderId = "c")

        assertEquals(listOf("c"), openIds(blocks))
        assertTrue(
            "the open group must render its rows",
            blocks.first { it.folder.id == "c" }.ids.isNotEmpty(),
        )
    }

    @Test
    fun `an open id naming a group that is gone opens nothing`() {
        // A deleted or dissolved group: nothing else is opened in its place.
        val (blocks, _) = partitionByFolder(threeSessions, threeFolders, expandedFolderId = "gone")

        assertEquals(emptyList<String>(), openIds(blocks))
    }

    @Test
    fun `a collapsed group carries no member rows`() {
        val (blocks, _) = partitionByFolder(threeSessions, threeFolders, expandedFolderId = "a")

        val closed = blocks.filter { it.isCollapsed }
        assertEquals(2, closed.size)
        closed.forEach {
            assertTrue("a collapsed group must not render rows", it.ids.isEmpty())
            // The count survives so the header can still say how many are inside.
            assertEquals(1, it.totalCount)
        }
    }

    @Test
    fun `the open group is kept in memory only`() {
        // Persisting it is what made a cold start reopen a group.
        val vm = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        assertTrue(vm.contains("val expandedFolderId = MutableStateFlow<String?>(null)"))
        assertTrue(!vm.contains("putString(\"lastExpandedFolderId\""))
        assertTrue(!vm.contains("putStringSet(\"collapsedFolderIds\""))
        assertTrue(
            "tapping the open group folds everything",
            vm.contains("expandedFolderId.value = if (expandedFolderId.value == folderId) null else folderId"),
        )
    }

    @Test
    fun `a long-standing group that drained to empty is a ghost and is hidden`() {
        // [T-android-empty-group-visibility] The reported symptom: clearing the
        // last chat out of a group left a row nothing could remove.
        val now = 10_000_000_000L
        val old = now - EMPTY_GROUP_GRACE_MS * 10
        val pinnedEmpty = FolderEntity(
            id = "pinned_empty", name = "Pinned Empty",
            createdAt = old, updatedAt = old, pinnedAt = old,
        )
        val unpinnedEmpty = FolderEntity(
            id = "unpinned_empty", name = "Unpinned Empty",
            createdAt = old, updatedAt = old, pinnedAt = null,
        )
        val folders = listOf(folder("a", 30), pinnedEmpty, unpinnedEmpty)
        val sessions = listOf(session("s1", "a", 300))

        val (blocks, _) = partitionByFolder(
            sessions, folders, expandedFolderId = null, nowMs = now,
        )

        // Pinning is an explicit "keep this in front of me" and still wins.
        assertEquals(setOf("a", "pinned_empty"), blocks.map { it.folder.id }.toSet())
    }

    @Test
    fun `a just-created empty group stays visible so it can be filed into`() {
        // The regression the pinned-only rule introduced: a group the user
        // created seconds ago vanished before anything could be put in it,
        // which also reads as "my group was deleted".
        val now = 10_000_000_000L
        val fresh = FolderEntity(
            id = "fresh", name = "Fresh",
            createdAt = now - 5_000, updatedAt = now - 5_000, pinnedAt = null,
        )
        val folders = listOf(folder("a", 30), fresh)
        val sessions = listOf(session("s1", "a", 300))

        val (blocks, _) = partitionByFolder(
            sessions, folders, expandedFolderId = null, nowMs = now,
        )

        assertTrue(
            "a newly created group must be visible even while empty",
            "fresh" in blocks.map { it.folder.id },
        )
    }

    @Test
    fun `the grace window has an inclusive upper edge`() {
        val now = 10_000_000_000L
        fun at(age: Long) = FolderEntity(
            id = "f", name = "F", createdAt = now - age, updatedAt = now - age, pinnedAt = null,
        )
        assertTrue(emptyGroupIsStillRelevant(at(EMPTY_GROUP_GRACE_MS - 1), now))
        assertTrue(!emptyGroupIsStillRelevant(at(EMPTY_GROUP_GRACE_MS), now))
        assertTrue(!emptyGroupIsStillRelevant(at(EMPTY_GROUP_GRACE_MS + 1), now))
    }

    @Test
    fun `a pinned group survives regardless of age`() {
        val now = 10_000_000_000L
        val ancientPinned = FolderEntity(
            id = "p", name = "P",
            createdAt = 0, updatedAt = 0, pinnedAt = 1L,
        )
        assertTrue(emptyGroupIsStillRelevant(ancientPinned, now))
    }

    @Test
    fun `an open id naming a drained group opens nothing`() {
        // The group was emptied and is dropped by the empty-group rule.
        val now = 10_000_000_000L
        val drained = FolderEntity(
            id = "drained", name = "Drained",
            createdAt = 0, updatedAt = 0, pinnedAt = null,
        )
        val folders = listOf(folder("a", 30), drained)
        val sessions = listOf(session("s1", "a", 300))

        val (blocks, _) = partitionByFolder(
            sessions, folders, expandedFolderId = "drained", nowMs = now,
        )

        assertEquals(emptyList<String>(), openIds(blocks))
    }

    @Test
    fun `sessions outside any group stay ungrouped`() {
        val folders = listOf(folder("a", 30))
        val sessions = listOf(session("s1", "a", 300), session("loose", null, 200))

        val (_, ungrouped) = partitionByFolder(sessions, folders, expandedFolderId = null)

        assertEquals(listOf("loose"), ungrouped.map { it.id })
    }
}
