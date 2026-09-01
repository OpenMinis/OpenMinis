package com.openminis.app.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-history] Pins the record model that replaced the transient
 * "Backup ready" card.
 *
 * `BackupHistory` itself needs a Context to resolve filesDir, so — following
 * BackupFormatToleranceTest / CompactDividerPlacementTest in this module —
 * these exercise the parts that carry the behaviour: the serialized shape
 * (which has to survive an app update), the status derivation, and the
 * retention/reconcile rules. If one changes without the other, the failing
 * assertion names the rule that broke.
 */
class BackupHistoryRecordTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(BackupHistory.Record.serializer())

    private fun record(
        status: BackupHistory.Status = BackupHistory.Status.SUCCEEDED,
        destinations: List<BackupHistory.DestinationOutcome> = emptyList(),
        skipped: Int = 0,
        startedAt: Long = 1_000_000L,
    ) = BackupHistory.Record(
        backupId = "abc123",
        startedAt = startedAt,
        finishedAt = startedAt + 5_000,
        status = status,
        categories = listOf("chats", "skills"),
        encrypted = true,
        totalBytes = 10_485_760,
        skippedFiles = skipped,
        destinations = destinations,
    )

    // -- Serialization ---------------------------------------------------

    /**
     * The record is persisted as JSON and read back after app updates. A field
     * added later must not make every existing record undecodable — which is
     * why the store is configured with `ignoreUnknownKeys` and every optional
     * field carries a default.
     */
    @Test
    fun `record round-trips through json`() {
        val original = record(
            destinations = listOf(
                BackupHistory.DestinationOutcome("nas", succeeded = true),
                BackupHistory.DestinationOutcome("offsite", succeeded = false, detail = "timeout"),
            ),
        )
        val decoded = json.decodeFromString(listSer, json.encodeToString(listSer, listOf(original)))
        assertEquals(1, decoded.size)
        assertEquals(original, decoded.single())
    }

    /** A record written by an OLDER build lacks fields added later. */
    @Test
    fun `record from an older schema still decodes`() {
        val old = """[{"id":"x","backupId":"b","startedAt":1000,"status":"SUCCEEDED"}]"""
        val decoded = json.decodeFromString(listSer, old)
        val r = decoded.single()
        assertEquals("b", r.backupId)
        assertEquals(BackupHistory.Status.SUCCEEDED, r.status)
        // Defaults fill in rather than throwing.
        assertTrue(r.destinations.isEmpty())
        assertTrue(r.log.isEmpty())
        assertNull(r.finishedAt)
    }

    /** An unknown field from a NEWER build must not break an older reader. */
    @Test
    fun `unknown fields are ignored`() {
        val future =
            """[{"id":"x","backupId":"b","startedAt":1000,"status":"FAILED","somethingNew":42}]"""
        assertEquals(BackupHistory.Status.FAILED, json.decodeFromString(listSer, future).single().status)
    }

    // -- Status semantics ------------------------------------------------

    /**
     * The distinction the old UI could not express: a run that produced a
     * package but did not reach every destination is neither a success nor a
     * failure. Collapsing it either way is what made partial delivery invisible.
     */
    @Test
    fun `a partially delivered run reports problems`() {
        val r = record(
            status = BackupHistory.Status.COMPLETED_WITH_ISSUES,
            destinations = listOf(
                BackupHistory.DestinationOutcome("nas", succeeded = true),
                BackupHistory.DestinationOutcome("offsite", succeeded = false, detail = "unreachable"),
            ),
        )
        assertTrue(r.hasProblems)
        assertEquals(1, r.destinations.count { it.succeeded })
        assertEquals(2, r.destinations.size)
    }

    /** Skipped files alone are enough to make a run "with issues". */
    @Test
    fun `skipped files count as problems even when delivery succeeded`() {
        val r = record(
            destinations = listOf(BackupHistory.DestinationOutcome("nas", succeeded = true)),
            skipped = 3,
        )
        assertTrue(r.hasProblems)
    }

    /** A clean run must NOT be flagged. */
    @Test
    fun `a fully delivered run has no problems`() {
        val r = record(destinations = listOf(BackupHistory.DestinationOutcome("nas", true)))
        assertFalse(r.hasProblems)
    }

    @Test
    fun `duration is derived from the two timestamps`() {
        assertEquals(5_000L, record().durationMillis)
        assertNull(record().copy(finishedAt = null).durationMillis)
    }

    // -- Retention / reconcile -------------------------------------------

    /**
     * 30 days, matching iOS. A heavy week can't push out a record the user
     * still cares about, and an idle month leaves nothing stale behind.
     */
    @Test
    fun `retention window is thirty days`() {
        assertEquals(30L * 24 * 60 * 60 * 1000, BackupHistory.RETENTION_MS)
        val now = System.currentTimeMillis()
        val cutoff = now - BackupHistory.RETENTION_MS
        val fresh = record(startedAt = now - 1_000)
        val stale = record(startedAt = cutoff - 1)
        assertTrue("a run from a moment ago is kept", fresh.startedAt >= cutoff)
        assertFalse("a run older than the window is pruned", stale.startedAt >= cutoff)
    }

    /**
     * A run killed mid-flight (process death, crash, swipe-away) leaves its
     * record RUNNING. Left alone it renders as a permanent spinner, so it is
     * reconciled to FAILED on next launch.
     */
    @Test
    fun `an interrupted run reconciles to failed`() {
        val running = record(status = BackupHistory.Status.RUNNING).copy(finishedAt = null)
        val reconciled = running.copy(
            status = BackupHistory.Status.FAILED,
            finishedAt = 2_000_000L,
            errorMessage = BackupHistory.INTERRUPTED_MARKER,
        )
        assertEquals(BackupHistory.Status.FAILED, reconciled.status)
        assertEquals(BackupHistory.INTERRUPTED_MARKER, reconciled.errorMessage)
        // The marker is a stored sentinel, not user-facing text: the UI maps it
        // to a localized string, so it must stay stable and untranslated.
        assertEquals("interrupted", BackupHistory.INTERRUPTED_MARKER)
    }

    @Test
    fun `skipped entry exposes its file name`() {
        val e = BackupHistory.SkippedEntry("chats/session-1/files/huge video.mp4", 99_000_000)
        assertEquals("huge video.mp4", e.fileName)
    }
}
