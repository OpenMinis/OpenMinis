package com.openminis.app.backup

import com.openminis.app.backup.scheduled.ScheduledBackupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-backup-config-test] Verifies defaults and options for ScheduledBackupConfig.
 */
class ScheduledBackupConfigTest {

    @Test
    fun `default config is disabled with safe constraints`() {
        val config = ScheduledBackupConfig()
        assertFalse(config.enabled)
        assertEquals(24L, config.intervalHours)
        assertEquals(5, config.retentionCount)
        assertTrue(config.requireCharging)
        assertFalse(config.requireUnmeteredNetwork)
    }

    @Test
    fun `interval options map to expected hours`() {
        assertEquals(12L, ScheduledBackupConfig.IntervalOption.HOURLY_12.hours)
        assertEquals(24L, ScheduledBackupConfig.IntervalOption.DAILY.hours)
        assertEquals(168L, ScheduledBackupConfig.IntervalOption.WEEKLY.hours)
    }
}
