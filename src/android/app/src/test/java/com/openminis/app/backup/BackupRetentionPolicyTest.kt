package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * [T-android-backup-retention-test] Verifies the retention pruning algorithm (Issue #476).
 *
 * Ensures:
 * 1. Rolling retention: Keeps the latest N scheduled backups.
 * 2. Pruning targets the oldest scheduled backups only.
 * 3. Manual backups are never pruned, regardless of age or count.
 * 4. Boundary cases (0 retention, empty list, exact quota) behave safely.
 */
class BackupRetentionPolicyTest {

    private fun mockRecord(
        startedAt: Long,
        isScheduled: Boolean,
        id: String = UUID.randomUUID().toString(),
    ): BackupHistory.Record = BackupHistory.Record(
        id = id,
        backupId = "backup-$startedAt",
        startedAt = startedAt,
        status = BackupHistory.Status.SUCCEEDED,
        isScheduled = isScheduled,
    )

    @Test
    fun `empty records returns empty prune list`() {
        val toPrune = BackupRetentionPolicy.selectRecordsToPrune(emptyList(), maxScheduledToKeep = 5)
        assertTrue(toPrune.isEmpty())
    }

    @Test
    fun `when scheduled backups are under limit nothing is pruned`() {
        val records = listOf(
            mockRecord(startedAt = 3000L, isScheduled = true),
            mockRecord(startedAt = 2000L, isScheduled = true),
            mockRecord(startedAt = 1000L, isScheduled = true),
        )
        val toPrune = BackupRetentionPolicy.selectRecordsToPrune(records, maxScheduledToKeep = 5)
        assertTrue(toPrune.isEmpty())
    }

    @Test
    fun `when scheduled backups exactly equal limit nothing is pruned`() {
        val records = listOf(
            mockRecord(startedAt = 3000L, isScheduled = true),
            mockRecord(startedAt = 2000L, isScheduled = true),
            mockRecord(startedAt = 1000L, isScheduled = true),
        )
        val toPrune = BackupRetentionPolicy.selectRecordsToPrune(records, maxScheduledToKeep = 3)
        assertTrue(toPrune.isEmpty())
    }

    @Test
    fun `when scheduled backups exceed limit the oldest are pruned`() {
        val r1 = mockRecord(startedAt = 1000L, isScheduled = true, id = "oldest-1")
        val r2 = mockRecord(startedAt = 2000L, isScheduled = true, id = "oldest-2")
        val r3 = mockRecord(startedAt = 3000L, isScheduled = true, id = "keep-1")
        val r4 = mockRecord(startedAt = 4000L, isScheduled = true, id = "keep-2")
        val r5 = mockRecord(startedAt = 5000L, isScheduled = true, id = "keep-3")

        val records = listOf(r1, r2, r3, r4, r5)
        val toPrune = BackupRetentionPolicy.selectRecordsToPrune(records, maxScheduledToKeep = 3)

        assertEquals(2, toPrune.size)
        // Should prune oldest first: r2 (2000L) and r1 (1000L)
        val prunedIds = toPrune.map { it.id }.toSet()
        assertEquals(setOf("oldest-1", "oldest-2"), prunedIds)
    }

    @Test
    fun `manual backups are NEVER pruned even when older than scheduled backups`() {
        val manualOld = mockRecord(startedAt = 500L, isScheduled = false, id = "manual-old")
        val manualRecent = mockRecord(startedAt = 6000L, isScheduled = false, id = "manual-recent")

        val s1 = mockRecord(startedAt = 1000L, isScheduled = true, id = "sched-1")
        val s2 = mockRecord(startedAt = 2000L, isScheduled = true, id = "sched-2")
        val s3 = mockRecord(startedAt = 3000L, isScheduled = true, id = "sched-3")
        val s4 = mockRecord(startedAt = 4000L, isScheduled = true, id = "sched-4")

        val records = listOf(manualOld, s1, s2, s3, s4, manualRecent)
        // Keep only 2 scheduled backups
        val toPrune = BackupRetentionPolicy.selectRecordsToPrune(records, maxScheduledToKeep = 2)

        assertEquals(2, toPrune.size)
        val prunedIds = toPrune.map { it.id }.toSet()
        assertEquals(setOf("sched-1", "sched-2"), prunedIds)
        // Neither manualOld nor manualRecent is ever pruned
        assertTrue(!prunedIds.contains("manual-old"))
        assertTrue(!prunedIds.contains("manual-recent"))
    }

    @Test
    fun `zero or negative retention limit returns empty prune list safely`() {
        val records = listOf(
            mockRecord(startedAt = 1000L, isScheduled = true),
            mockRecord(startedAt = 2000L, isScheduled = true),
        )
        val toPruneZero = BackupRetentionPolicy.selectRecordsToPrune(records, maxScheduledToKeep = 0)
        assertTrue(toPruneZero.isEmpty())

        val toPruneNegative = BackupRetentionPolicy.selectRecordsToPrune(records, maxScheduledToKeep = -1)
        assertTrue(toPruneNegative.isEmpty())
    }
}
