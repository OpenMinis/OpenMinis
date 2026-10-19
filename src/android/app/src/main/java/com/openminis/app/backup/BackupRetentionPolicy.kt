package com.openminis.app.backup

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-android-backup-retention-policy] Manages retention and rotation of automated backup snapshots.
 *
 * Implements acceptance criteria of Issue #476:
 * 1. Configurable retention limit: Retains the latest N successful automated snapshots.
 * 2. Automated pruning: Automatically prunes the oldest scheduled snapshots exceeding the retention quota.
 * 3. Failure resilience: Failed runs (`FAILED`) do NOT consume the retention quota, preventing
 *    intermittent errors from displacing older valid backups.
 * 4. Strict preservation of manual backups: User-created manual snapshots are never pruned automatically.
 */
object BackupRetentionPolicy {

    private const val TAG = "BackupRetentionPolicy"

    /** Default number of scheduled backups to retain. */
    const val DEFAULT_RETENTION_COUNT = 5

    /** Result of executing a retention pruning pass. */
    data class PruneResult(
        val evaluatedCount: Int,
        val prunedCount: Int,
        val prunedRecordIds: List<String>,
        val freedBytes: Long,
    )

    /**
     * Pure selection logic: identifies which records should be pruned.
     *
     * Invariants:
     * - Manual backups (`isScheduled == false`) are strictly omitted.
     * - Successful automated backups (`SUCCEEDED` or `COMPLETED_WITH_ISSUES`) are evaluated
     *   against [maxScheduledToKeep]. Older valid backups beyond the quota are pruned.
     * - Failed automated backups do NOT consume valid backup quota; excess failed records are cleaned up.
     */
    fun selectRecordsToPrune(
        records: List<BackupHistory.Record>,
        maxScheduledToKeep: Int,
    ): List<BackupHistory.Record> {
        if (maxScheduledToKeep <= 0) return emptyList()

        val scheduled = records.filter { it.isScheduled }
        if (scheduled.isEmpty()) return emptyList()

        // 1. Valid snapshots that produced packages (SUCCEEDED or COMPLETED_WITH_ISSUES)
        val validScheduled = scheduled.filter {
            it.status == BackupHistory.Status.SUCCEEDED || it.status == BackupHistory.Status.COMPLETED_WITH_ISSUES
        }.sortedByDescending { it.startedAt }

        val toPrune = mutableListOf<BackupHistory.Record>()

        if (validScheduled.size > maxScheduledToKeep) {
            toPrune.addAll(validScheduled.drop(maxScheduledToKeep))
        }

        // 2. Clean up excess failed records so they don't grow unbounded, without touching valid quota
        val failedScheduled = scheduled.filter { it.status == BackupHistory.Status.FAILED }
            .sortedByDescending { it.startedAt }
        if (failedScheduled.size > maxScheduledToKeep) {
            toPrune.addAll(failedScheduled.drop(maxScheduledToKeep))
        }

        return toPrune
    }

    /**
     * Enforces the retention policy against persistent storage and history.
     */
    fun enforce(
        context: Context,
        maxScheduledToKeep: Int = DEFAULT_RETENTION_COUNT,
        history: BackupHistory = BackupHistory.get(context),
    ): PruneResult {
        if (maxScheduledToKeep <= 0) {
            return PruneResult(0, 0, emptyList(), 0L)
        }

        val allRecords = history.records()
        val toPrune = selectRecordsToPrune(allRecords, maxScheduledToKeep)
        if (toPrune.isEmpty()) {
            return PruneResult(allRecords.size, 0, emptyList(), 0L)
        }

        val backupsDir = BackupExporter.backupsDirectory(context)
        var freedBytes = 0L
        val prunedIds = mutableListOf<String>()

        for (record in toPrune) {
            // 1. Clean up local package file if present
            record.packageName?.let { name ->
                val file = File(backupsDir, name)
                if (file.exists()) {
                    val len = file.length()
                    if (file.delete()) {
                        freedBytes += len
                    }
                }
            }

            prunedIds.add(record.id)
            AppLogger.info(TAG, "[Retention] Pruned old scheduled backup id=${record.id}, startedAt=${record.startedAt}, status=${record.status}")
        }

        // 2. Batch remove records from persistent history (single disk write)
        if (prunedIds.isNotEmpty()) {
            history.removeAll(prunedIds)
        }

        return PruneResult(
            evaluatedCount = allRecords.size,
            prunedCount = prunedIds.size,
            prunedRecordIds = prunedIds,
            freedBytes = freedBytes,
        )
    }
}
