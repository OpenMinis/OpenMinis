package com.openminis.app.backup.scheduled

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.openminis.app.logging.AppLogger
import java.util.concurrent.TimeUnit

/**
 * [T-android-scheduled-backup-scheduler] Schedules and manages WorkManager tasks for automated backups.
 * Respects power, charging, and network constraints specified in Issue #476.
 */
object ScheduledBackupScheduler {

    private const val TAG = "ScheduledBackupScheduler"
    const val UNIQUE_WORK_NAME = "openminis_scheduled_backup"

    /**
     * Updates or enqueues unique periodic work based on [config].
     * If [config.enabled] is false, any existing scheduled work is cancelled.
     */
    fun applyConfig(context: Context, config: ScheduledBackupConfig) {
        if (!config.enabled) {
            cancel(context)
            return
        }

        schedule(context, config)
    }

    /**
     * Enqueues or updates periodic work with the given constraints and interval.
     */
    fun schedule(context: Context, config: ScheduledBackupConfig) {
        val constraintsBuilder = Constraints.Builder()

        if (config.requireCharging) {
            constraintsBuilder.setRequiresCharging(true)
        }

        if (config.requireUnmeteredNetwork) {
            constraintsBuilder.setRequiredNetworkType(NetworkType.UNMETERED)
        }

        val constraints = constraintsBuilder.build()

        val intervalHours = config.intervalHours.coerceAtLeast(1L)
        val workRequest = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
            intervalHours,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest,
        )

        AppLogger.info(
            TAG,
            "[ScheduledBackup] Enqueued periodic backup every ${intervalHours}h " +
                "(charging=${config.requireCharging}, unmetered=${config.requireUnmeteredNetwork})"
        )
    }

    /**
     * Cancels existing scheduled backup work.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        AppLogger.info(TAG, "[ScheduledBackup] Cancelled scheduled backup periodic work.")
    }
}
