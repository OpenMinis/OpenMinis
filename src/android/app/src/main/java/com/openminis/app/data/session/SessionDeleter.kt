package com.openminis.app.data.session

import android.content.Context
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-child-session-delete-storage] THE session-delete funnel.
 *
 * Before this, four entry points (home list single/multi delete, delete-
 * folder-with-sessions, empty-session cleanup on exit, the debug RPC) each
 * did `chatRepository.deleteSession` plus whatever cache clears that call
 * site remembered — and none of them touched the session's files, so every
 * deleted session leaked its `minis-sessions/<id>` workspace and dated media
 * forever. With helper child sessions the leak multiplied: a parent delete
 * dropped the children's DB rows but left their directories, their cached
 * ViewModels and their badges behind.
 *
 * One call now does the whole tree, in the only safe order:
 *   1. resolve self + every descendant (deepest first);
 *   2. per session, deepest first: cancel its running helper job, stop any
 *      live stream and drop its ViewModel, delete messages + row, delete its
 *      files, drop its badge entries;
 *   3. the root last.
 *
 * Lives in `data/session` and takes a Context only for `filesDir` — the
 * repository stays Context-free, and the UI/service singletons it touches
 * (ViewModel store, badge store, headless runner) are process-wide objects,
 * not screen state. Every delete entry point MUST route through here.
 */
object SessionDeleter {
    private const val TAG = "SessionDeleter"

    data class Report(val deletedIds: List<String>, val bytesFreed: Long, val jobsCancelled: Int)

    suspend fun deleteTree(
        context: Context,
        repo: ChatRepository,
        rootId: String,
        reason: String,
    ): Report = withContext(Dispatchers.IO) {
        val storage = SessionStorage(context.applicationContext.filesDir)
        val ids = SessionTree.subtreeDeepestFirst(rootId) { repo.dao.childSessionIds(it) }
        var freed = 0L
        var jobs = 0
        for (id in ids) {
            // 1. Stop work first. A helper still running in a child would keep
            //    writing rows/files into what we are about to delete.
            //    [T-agent-port-round2] Drop `then` first: a cancelled child's
            //    completion must not post an <agent_callback> into a session
            //    that is being deleted — the headless submit would re-create
            //    rows for it (a resurrected parent).
            AgentJobRegistry.jobForSession(id)?.let {
                if (it.isActive) {
                    AgentJobRegistry.setThen(it.id, com.openminis.app.agent.jobs.AgentJobThen.None)
                    AgentJobRegistry.cancel(it.id, "session-deleted:$reason"); jobs++
                }
            }
            for (child in AgentJobRegistry.activeChildren(id)) {
                AgentJobRegistry.setThen(child.id, com.openminis.app.agent.jobs.AgentJobThen.None)
            }
            // [T-sub-agents-queue] Drop the backlog too — a queued task
            // would otherwise start into a session that no longer exists.
            AgentJobRegistry.dropQueuedDelegations(id, "parent-deleted:$reason")
            AgentJobRegistry.cancelAll(id, "parent-deleted:$reason")
            runCatching { HeadlessChatRunner.cancel(context, id) }
            // 2. Drop the live ViewModel (cancels its scope) — on Main, it owns
            //    a ViewModelStore.
            withContext(Dispatchers.Main) { ChatViewModelStore.release(id) }
            HeadlessChatRunner.forget(id)
            // 3. DB rows. deleteSessionOnly is the non-cascading primitive; the
            //    cascade is this loop, which already walked the subtree.
            repo.deleteSessionOnly(id)
            // 4. Files, then badges.
            freed += storage.deleteFiles(id)
            SessionBadgeStore.clear(id)
            // [T-android-browser-cli-own-pool] A pool minis-browser-use made
            // for this session while it had no live view model.
            com.openminis.app.sandbox.offload.BrowserCliPools.release(id)
        }
        AppLogger.info(
            TAG,
            "deleteTree root=${rootId.take(8)} reason=$reason sessions=${ids.size} " +
                "jobsCancelled=$jobs freed=${freed}B",
        )
        Report(ids, freed, jobs)
    }

    /**
     * Clear a session tree's FILES only (storage screen "clear files"): every
     * descendant's workspace and media go too, the DB rows stay.
     */
    suspend fun clearTreeFiles(context: Context, repo: ChatRepository, rootId: String): Long =
        withContext(Dispatchers.IO) {
            val storage = SessionStorage(context.applicationContext.filesDir)
            val ids = SessionTree.subtreeDeepestFirst(rootId) { repo.dao.childSessionIds(it) }
            ids.sumOf { storage.deleteFiles(it) }
        }
}
