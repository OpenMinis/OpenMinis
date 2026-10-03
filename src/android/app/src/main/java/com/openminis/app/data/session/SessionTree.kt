package com.openminis.app.data.session

/**
 * [T-android-child-session-delete-storage] Parent/child session tree helpers.
 *
 * Helper (delegate_task) sessions are hidden children of the session that
 * spawned them (`ChatSessionEntity.parentSessionId`). Every operation that
 * treats a session as a unit — delete, storage accounting, cache release —
 * has to cover the whole subtree, not one level: a child can itself have
 * children in a later design, and hardcoding one hop is exactly the bug
 * class this exists to prevent. These are pure functions over an id graph so
 * the walk is unit-testable without Room.
 */
object SessionTree {

    /**
     * Ids of every descendant of [rootId], deepest first, root excluded.
     * Deepest-first is the delete order: a child row must go before its
     * parent so a crash mid-way never leaves an orphan pointing at a
     * missing parent. Cycle-safe (a corrupt parent link cannot loop).
     */
    suspend fun descendantsDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> {
        val seen = mutableSetOf(rootId)
        val order = mutableListOf<String>()
        suspend fun walk(id: String) {
            for (child in childrenOf(id)) {
                if (!seen.add(child)) continue
                walk(child)
                order += child
            }
        }
        walk(rootId)
        return order
    }

    /** [rootId] plus all descendants, deepest first (root last). */
    suspend fun subtreeDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> = descendantsDeepestFirst(rootId, childrenOf) + rootId

    /**
     * Resolve each session to its top-level ancestor given a parent map
     * (`id → parentId?`). A dangling parent (id not in the map) is treated as
     * the root so a child whose parent row was lost still surfaces somewhere
     * rather than vanishing from accounting.
     */
    fun rootOf(id: String, parentOf: Map<String, String?>): String {
        var cur = id
        val seen = mutableSetOf(cur)
        while (true) {
            val p = parentOf[cur] ?: return cur
            if (p !in parentOf || !seen.add(p)) return cur
            cur = p
        }
    }

    /**
     * Aggregate per-session sizes onto their root ancestor. Result contains
     * ONLY roots (sessions with no parent, or an unresolvable one); every
     * size is counted exactly once. This is what the storage screen shows.
     */
    fun aggregateToRoots(
        parentOf: Map<String, String?>,
        sizes: Map<String, Long>,
    ): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        for (id in parentOf.keys) {
            val root = rootOf(id, parentOf)
            out[root] = (out[root] ?: 0L) + (sizes[id] ?: 0L)
        }
        return out
    }

    /** Ids in [parentOf] whose root is [rootId] (root included). */
    fun membersOf(rootId: String, parentOf: Map<String, String?>): List<String> =
        parentOf.keys.filter { rootOf(it, parentOf) == rootId }
}
