package com.openminis.app.data.session

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger

/**
 * [T-android-zombie-child-sweep] Cold-start cleanup of hidden sub agent
 * sessions whose parent no longer exists.
 *
 * A sub agent runs in a child session that is deliberately invisible: every
 * picker filters rows whose `parent_session_id` is set. That is fine while the
 * parent is around — deleting the parent cascades through SessionDeleter. It
 * is not fine when the process is killed mid-run, or when a parent disappears
 * by some path that did not cascade: the child then sits in the table forever,
 * holding its messages and its workspace, with no user-reachable way to remove
 * it. They accumulate.
 *
 * This deletes user data, so the predicate is deliberately narrow and each
 * deletion is justified in the log rather than merely counted.
 */
object OrphanChildSweeper {
    private const val TAG = "OrphanSweep"

    /**
     * Rows touched more recently than this are never candidates.
     *
     * Two things hide behind that. The obvious one is a run in flight. The
     * subtler one is a backup restore: BackupImporter streams sessions row by
     * row rather than in one transaction, so there is a window where a child is
     * present and its parent is not yet — and because the importer preserves
     * the backup's own `updatedAt`, such a child can already be older than any
     * plausible grace period. The age floor does NOT cover that case; the
     * re-check in [sweep] does. The floor's job is the ordinary one of not
     * racing live writes.
     */
    private const val GRACE_MS = 24L * 60 * 60 * 1000

    data class Report(val examined: Int, val deleted: Int, val spared: Int, val bytesFreed: Long)

    /**
     * Runs once at cold start. Safe to call when there is nothing to do — it
     * logs a baseline either way, so "the sweeper ran and found nothing" is
     * distinguishable in a log from "the sweeper never ran".
     */
    suspend fun sweep(context: Context, repo: ChatRepository): Report {
        val cutoff = System.currentTimeMillis() - GRACE_MS
        val totalChildren = runCatching { repo.dao.childSessionCount() }.getOrDefault(-1)
        val candidates = runCatching { repo.dao.orphanChildSessionIds(cutoff) }.getOrElse {
            AppLogger.warning(TAG, "sweep aborted — candidate query failed: ${it.message}")
            return Report(0, 0, 0, 0)
        }
        AppLogger.info(
            TAG,
            "sweep start: childSessions=$totalChildren candidates=${candidates.size} graceHours=${GRACE_MS / 3_600_000}",
        )
        if (candidates.isEmpty()) return Report(0, 0, 0, 0)

        var deleted = 0
        var spared = 0
        var bytes = 0L
        for (id in candidates) {
            val row = runCatching { repo.getSession(id) }.getOrNull()
            if (row == null) {
                // Someone else removed it between the query and now.
                spared++
                AppLogger.info(TAG, "SPARED child=${id.take(8)} — row gone before delete")
                continue
            }
            val parentId = row.parentSessionId
            if (parentId.isNullOrEmpty()) {
                spared++
                AppLogger.warning(TAG, "SPARED child=${id.take(8)} — no parent id on re-read")
                continue
            }
            // Re-check by id immediately before deleting. This is what protects
            // an in-flight restore: the parent may have been imported in the
            // moments since the candidate query ran, and a child whose parent
            // now exists is simply not an orphan.
            val parent = runCatching { repo.getSession(parentId) }.getOrNull()
            if (parent != null) {
                spared++
                AppLogger.warning(
                    TAG,
                    "SPARED child=${id.take(8)} — parent ${parentId.take(8)} exists after all",
                )
                continue
            }
            val ageDays = (System.currentTimeMillis() - row.updatedAt) / 86_400_000
            AppLogger.info(
                TAG,
                "DELETE child=${id.take(8)} parent=${parentId.take(8)} (missing) " +
                    "age=${ageDays}d title='${row.title?.take(40).orEmpty()}'",
            )
            // Through the normal funnel: a zombie can itself have children, and
            // only this path also removes the workspace, media, jobs and badges.
            val report = runCatching {
                SessionDeleter.deleteTree(context, repo, id, "orphan-sweep")
            }
            val done = report.getOrNull()
            if (done == null) {
                spared++
                AppLogger.warning(
                    TAG,
                    "SPARED child=${id.take(8)} — delete failed: ${report.exceptionOrNull()?.message}",
                )
            } else {
                deleted += done.deletedIds.size
                bytes += done.bytesFreed
            }
        }
        AppLogger.info(TAG, "sweep done: deleted=$deleted spared=$spared freed=${bytes}B")
        return Report(candidates.size, deleted, spared, bytes)
    }
}
