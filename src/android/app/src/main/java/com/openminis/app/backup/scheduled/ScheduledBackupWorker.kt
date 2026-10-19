package com.openminis.app.backup.scheduled

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupRetentionPolicy
import com.openminis.app.backup.BackupRunController
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-scheduled-backup-worker] WorkManager worker for performing automated backups in background.
 *
 * Implements Issue #476 requirements:
 * 1. Checks device conditions and user configuration.
 * 2. Triggers automated backup marked with `isScheduled = true`.
 * 3. Enforces rolling retention policy upon successful completion, pruning older automated snapshots.
 * 4. Updates last-run metadata in ScheduledBackupConfig.
 */
class ScheduledBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val config = ScheduledBackupConfig.load(context)

        if (!config.enabled) {
            AppLogger.info(TAG, "[ScheduledBackup] Worker triggered but scheduled backup is disabled.")
            return Result.success()
        }

        // If another backup is currently active, retry later
        if (BackupRunController.isRunning.value) {
            AppLogger.info(TAG, "[ScheduledBackup] A backup is already in progress. Retrying later.")
            return Result.retry()
        }

        AppLogger.info(TAG, "[ScheduledBackup] Starting scheduled background backup...")

        val request = BackupRunController.Request(
            categories = BackupCategory.backupable.toSet(),
            maxFileBytes = null,
            passphrase = config.passphrase,
            isScheduled = true,
        )

        val started = BackupRunController.start(context, request)
        if (!started) {
            AppLogger.warn(TAG, "[ScheduledBackup] Failed to start backup run.")
            return Result.retry()
        }

        // Wait for backup to finish (up to 20 minutes)
        val finished = withTimeoutOrNull(20 * 60 * 1000L) {
            // Give controller a moment to start
            delay(1000L)
            while (BackupRunController.isRunning.value) {
                delay(2000L)
            }
            true
        }

        val now = System.currentTimeMillis()

        if (finished != true) {
            AppLogger.error(TAG, "[ScheduledBackup] Timed out waiting for backup to complete.")
            BackupRunController.stop()
            ScheduledBackupConfig.save(
                context,
                config.copy(
                    lastRunMillis = now,
                    lastRunSuccess = false,
                    lastRunMessage = "Timed out after 20 minutes.",
                )
            )
            return Result.retry()
        }

        val error = BackupRunController.errorText.value
        val success = error.isNullOrBlank()

        if (success) {
            AppLogger.info(TAG, "[ScheduledBackup] Backup completed successfully. Enforcing retention policy...")
            val pruneResult = BackupRetentionPolicy.enforce(context, config.retentionCount)
            AppLogger.info(
                TAG,
                "[ScheduledBackup] Retention enforced: evaluated=${pruneResult.evaluatedCount}, " +
                    "pruned=${pruneResult.prunedCount}, freedBytes=${pruneResult.freedBytes}"
            )

            ScheduledBackupConfig.save(
                context,
                config.copy(
                    lastRunMillis = now,
                    lastRunSuccess = true,
                    lastRunMessage = "Backup completed successfully.",
                )
            )
            return Result.success()
        } else {
            AppLogger.error(TAG, "[ScheduledBackup] Backup finished with error: $error")
            ScheduledBackupConfig.save(
                context,
                config.copy(
                    lastRunMillis = now,
                    lastRunSuccess = false,
                    lastRunMessage = error,
                )
            )
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduledBackupWorker"
    }
}
