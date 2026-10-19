package com.openminis.app.backup

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-android-backup-retention-policy] Manages retention and rotation of automated backup snapshots.
 *
 * Implements acceptance criteria of Issue #476:
 * 1. Configurable retention limit: Retains the latest N automated snapshots.
 * 2. Automated pruning: Automatically prunes the oldest scheduled snapshots exceeding the retention quota.
 * 3. Strict preservation of manual backups: User-created manual snapshots are never pruned automatically.
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
     * Keeps newest [maxScheduledToKeep] scheduled backups and marks older ones for removal.
     * Manual backups (`isScheduled == false`) are strictly omitted from pruning candidates.
     */
    fun selectRecordsToPrune(
        records: List<BackupHistory.Record>,
        maxScheduledToKeep: Int,
    ): List<BackupHistory.Record> {
        if (maxScheduledToKeep <= 0) return emptyList()

        // Filter scheduled records only, ordered newest first
        val scheduled = records.filter { it.isScheduled }
            .sortedByDescending { it.startedAt }

        if (scheduled.size <= maxScheduledToKeep) {
            return emptyList()
        }

        // Drop the kept newest ones; the remainder (oldest) are pruned
        return scheduled.drop(maxScheduledToKeep)
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

            // 2. Remove record from persistent history
            history.remove(record.id)
            prunedIds.add(record.id)
            AppLogger.info(TAG, "[Retention] Pruned old scheduled backup id=${record.id}, startedAt=${record.startedAt}")
        }

        return PruneResult(
            evaluatedCount = allRecords.size,
            prunedCount = prunedIds.size,
            prunedRecordIds = prunedIds,
            freedBytes = freedBytes,
        )
    }
}
