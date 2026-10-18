package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-log-cap] Progress-line ceiling and the identity contract that
 * makes the load-time trim measurable.
 *
 * A per-file destination wrote one line per file: a 20 000-file workspace meant
 * a multi-megabyte `records.json` re-encoded on every single `note()` call, twice
 * (upsert + publishRunning). The cap lives here rather than in the ViewModel so
 * both the live path and the load path share one policy.
 */
class BackupHistoryLogCapTest {

    private fun lines(n: Int) = (1..n).map {
        BackupHistory.LogEntry(at = it.toLong(), message = "line $it")
    }

    @Test
    fun shortLogIsReturnedByIdentity() {
        // `cappedLog` must not copy when nothing is dropped: the load-time trim
        // detects "no change" by identity (===), so a needless copy would force
        // a rewrite of the whole history file on every app start.
        val log = lines(5)
        assertSame(log, BackupHistory.cappedLog(log))
    }

    @Test
    fun exactCeilingIsNotTrimmed() {
        val log = lines(BackupHistory.MAX_LOG_ENTRIES)
        assertSame(log, BackupHistory.cappedLog(log))
        assertEquals(BackupHistory.MAX_LOG_ENTRIES, log.size)
    }

    @Test
    fun overlongLogIsCappedToTheCeiling() {
        val capped = BackupHistory.cappedLog(lines(5_000))
        assertEquals(BackupHistory.MAX_LOG_ENTRIES, capped.size)
    }

    @Test
    fun capKeepsTheOpeningLines() {
        // The first lines say WHAT was backed up and WHERE to; losing them makes
        // the history useless for the question users actually ask.
        val capped = BackupHistory.cappedLog(lines(5_000))
        assertEquals("line 1", capped.first().message)
        assertEquals("line 2", capped[1].message)
    }

    @Test
    fun capKeepsTheNewestLines() {
        // ...and the tail says how it ended.
        val capped = BackupHistory.cappedLog(lines(5_000))
        assertEquals("line 5000", capped.last().message)
        assertEquals("line 4999", capped[capped.lastIndex - 1].message)
    }

    @Test
    fun capInsertsOneExplicitMarker() {
        // A silently shortened log reads as a complete run, so the dropped count
        // is stated. Exactly ONE marker: repeated trimming must not stack them.
        val capped = BackupHistory.cappedLog(lines(5_000))
        val markers = capped.filter { it.message.contains("dropped") }
        assertEquals(1, markers.size)
        val expectedDropped = 5_000 - 20 - (BackupHistory.MAX_LOG_ENTRIES - 20 - 1)
        assertTrue(
            "marker should say $expectedDropped lines went missing, got '${markers.first().message}'",
            markers.first().message.contains(expectedDropped.toString()),
        )
    }

    @Test
    fun timestampsStayOrderedAndMarkerIsTimestamped() {
        val capped = BackupHistory.cappedLog(lines(5_000))
        val times = capped.map { it.at }
        assertEquals("log must stay chronological", times.sorted(), times)
    }

    @Test
    fun appendLogCapsInPlaceWithoutGrowing() {
        // The in-memory list is what publishRunning copies into the record, so it
        // needs the same ceiling as the persisted one.
        val target = mutableListOf<BackupHistory.LogEntry>()
        repeat(1_000) { i -> BackupHistory.appendLog(target, "line $i") }
        assertTrue(
            "in-memory log must stay bounded, got ${target.size}",
            target.size <= BackupHistory.MAX_LOG_ENTRIES,
        )
    }

    @Test
    fun markerDoesNotStackAcrossRepeatedAppends() {
        val target = mutableListOf<BackupHistory.LogEntry>()
        repeat(5_000) { i -> BackupHistory.appendLog(target, "line $i") }
        assertTrue(
            "in-memory log must stay bounded, got ${target.size}",
            target.size <= BackupHistory.MAX_LOG_ENTRIES,
        )
        val markers = target.count { it.message.contains("dropped") }
        assertEquals("repeated trimming must not stack markers", 1, markers)
    }

    @Test
    fun appendLogKeepsProblemLinesVisible() {
        // A problem line is the reason the run reports COMPLETED_WITH_ISSUES; the
        // cap must never be the reason it vanished from the tail.
        val target = mutableListOf<BackupHistory.LogEntry>()
        repeat(500) { i -> BackupHistory.appendLog(target, "line $i") }
        BackupHistory.appendLog(target, "upload failed: quota", isProblem = true)
        assertEquals("upload failed: quota", target.last().message)
        assertTrue(target.last().isProblem)
    }
}
