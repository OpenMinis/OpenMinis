package com.openminis.app.backup.scheduled

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-android-scheduled-backup-config] Configuration and persistence for automated scheduled backups.
 * Addresses Issue #476 requirements: schedule intervals, execution constraints, and retention limits.
 */
data class ScheduledBackupConfig(
    val enabled: Boolean = false,
    val intervalHours: Long = 24L,
    val retentionCount: Int = 5,
    val requireCharging: Boolean = true,
    val requireUnmeteredNetwork: Boolean = false,
    val passphrase: String? = null,
    val lastRunMillis: Long? = null,
    val lastRunSuccess: Boolean? = null,
    val lastRunMessage: String? = null,
) {
    enum class IntervalOption(val hours: Long, val title: String) {
        HOURLY_12(12L, "Every 12 hours"),
        DAILY(24L, "Daily"),
        WEEKLY(168L, "Weekly"),
    }

    companion object {
        private const val PREFS_NAME = "openminis_scheduled_backup"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_RETENTION_COUNT = "retention_count"
        private const val KEY_REQUIRE_CHARGING = "require_charging"
        private const val KEY_REQUIRE_UNMETERED = "require_unmetered"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val KEY_LAST_RUN_MILLIS = "last_run_millis"
        private const val KEY_LAST_RUN_SUCCESS = "last_run_success"
        private const val KEY_LAST_RUN_MESSAGE = "last_run_message"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun load(context: Context): ScheduledBackupConfig {
            val p = prefs(context)
            val hasLastRun = p.contains(KEY_LAST_RUN_MILLIS)
            return ScheduledBackupConfig(
                enabled = p.getBoolean(KEY_ENABLED, false),
                intervalHours = p.getLong(KEY_INTERVAL_HOURS, 24L),
                retentionCount = p.getInt(KEY_RETENTION_COUNT, 5),
                requireCharging = p.getBoolean(KEY_REQUIRE_CHARGING, true),
                requireUnmeteredNetwork = p.getBoolean(KEY_REQUIRE_UNMETERED, false),
                passphrase = p.getString(KEY_PASSPHRASE, null)?.takeIf { it.isNotBlank() },
                lastRunMillis = if (hasLastRun) p.getLong(KEY_LAST_RUN_MILLIS, 0L) else null,
                lastRunSuccess = if (p.contains(KEY_LAST_RUN_SUCCESS)) p.getBoolean(KEY_LAST_RUN_SUCCESS, false) else null,
                lastRunMessage = p.getString(KEY_LAST_RUN_MESSAGE, null),
            )
        }

        fun save(context: Context, config: ScheduledBackupConfig) {
            prefs(context).edit().apply {
                putBoolean(KEY_ENABLED, config.enabled)
                putLong(KEY_INTERVAL_HOURS, config.intervalHours)
                putInt(KEY_RETENTION_COUNT, config.retentionCount)
                putBoolean(KEY_REQUIRE_CHARGING, config.requireCharging)
                putBoolean(KEY_REQUIRE_UNMETERED, config.requireUnmeteredNetwork)
                putString(KEY_PASSPHRASE, config.passphrase)
                if (config.lastRunMillis != null) {
                    putLong(KEY_LAST_RUN_MILLIS, config.lastRunMillis)
                } else {
                    remove(KEY_LAST_RUN_MILLIS)
                }
                if (config.lastRunSuccess != null) {
                    putBoolean(KEY_LAST_RUN_SUCCESS, config.lastRunSuccess)
                } else {
                    remove(KEY_LAST_RUN_SUCCESS)
                }
                putString(KEY_LAST_RUN_MESSAGE, config.lastRunMessage)
                apply()
            }
        }
    }
}
