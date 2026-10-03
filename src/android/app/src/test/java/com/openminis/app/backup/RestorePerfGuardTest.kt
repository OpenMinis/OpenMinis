package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-perf] The importer's hot loops.
 *
 * Measured on a Pixel 4a restoring a 202,705-message package: 119 messages/s,
 * 8.4 ms each, with the app at ~135% CPU and the four Room IO threads each
 * burning ~1.0 s per 10 s of wall clock. 8.4 ms is flash commit latency, not
 * work — every insert was its own implicit transaction, so SQLite fsynced once
 * per row.
 *
 * A perfetto trace of the same restore also showed RenderThread at 2.49 s per
 * 10 s, repainting the full 1080x2340 surface 478 times, because the button's
 * indeterminate spinner animates every frame regardless of progress.
 *
 * These are source guards. The loops need a real Room database and a package
 * on disk, which this module has no infrastructure for, and the failure mode
 * is silent: dropping a transaction does not break a test, it just makes the
 * restore slow again months later.
 */
class RestorePerfGuardTest {

    private val src by lazy { ProductionSources.read("backup/BackupImporter.kt") }
    private val ui by lazy {
        ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    }

    @Test
    fun `no per-record session query survives in the hot loops`() {
        // Each of these ran once per MESSAGE — 202,705 queries on the measured
        // package — to answer a question restoredSessionIds already holds.
        // Worse, getSession is `SELECT *`, so each call deserialised a whole
        // row only to compare it against null.
        assertEquals(
            "dao.getSession(sessionId) must not appear in a per-record loop",
            0,
            Regex("""dao\.getSession\(sessionId\)""").findAll(src).count(),
        )
        assertTrue(
            "the parent check must be an in-memory set lookup",
            src.contains("sessionId !in restoredSessionIds"),
        )
    }

    @Test
    fun `every bulk loop runs inside a transaction`() {
        // Sessions, messages, compact markers, and the last-message rebuild.
        // Without a transaction each insert is its own fsync.
        assertEquals(
            "all four bulk loops must be batched",
            4,
            Regex("""db\.withTransaction""").findAll(src).count(),
        )
    }

    @Test
    fun `the messages loop specifically is batched`() {
        // The hottest one: a regression here alone costs most of the win.
        val head = src.substringBefore("""readJsonl(dataDir, "messages")""")
        assertTrue(
            "a transaction must open before the messages loop",
            head.trimEnd().endsWith("db.withTransaction {"),
        )
    }

    @Test
    fun `the last-message rebuild is batched too`() {
        // Three queries and a write PER restored session — its own multi-minute
        // stretch on a package with thousands of sessions.
        val i = src.indexOf("for (sid in restoredSessionIds)")
        assertTrue("preview rebuild loop not found", i > 0)
        assertTrue(
            "the rebuild loop must sit inside a transaction",
            src.substring(0, i).trimEnd().endsWith("db.withTransaction {"),
        )
    }

    @Test
    fun `the busy indicator is determinate, not an infinite animation`() {
        // The indeterminate form redraws every frame for as long as it is
        // shown: 478 full-surface repaints in 10 s, 2.49 s of RenderThread CPU,
        // on a restore that was already I/O-bound.
        assertTrue(
            "the indicator must take a progress lambda",
            ui.contains("progress = { fraction ?: 0f }"),
        )
        // The fraction moved into RestoreProgressSection with
        // [T-android-restore-ui]; what matters is that one is computed from
        // done/total and fed to the arc, not where the expression lives.
        assertTrue(
            "a fraction must be computed from done/total",
            ui.contains("p.done.toFloat() / total"),
        )
    }

    @Test
    fun `progress still throttles to every 200 records`() {
        // The batching must not tempt anyone into emitting per record: at this
        // scale that would post more frames than the UI can draw.
        // Matched loosely: [T-android-restore-ui] turned this into a
        // multi-line block when it added the cancellation check on the same
        // beat, and pinning the old one-liner made this fail on a change that
        // kept the behaviour exactly.
        assertTrue("the 200-record beat must remain", src.contains("++seen % 200 == 0"))
        assertTrue("it must still drive the progress emit", src.contains("onCount?.invoke(seen)"))
    }

    @Test
    fun `the WAL is truncated after the big transactions commit`() {
        // Batching's cost is WAL growth: measured at 635 MB peak for a 633 MB
        // database, because nothing can checkpoint while the transaction is
        // open. It is reclaimed on commit, but without an explicit checkpoint
        // the file can sit at its high water mark for the rest of the restore
        // — and a restore is exactly when a user has least disk to spare.
        assertTrue(
            "a TRUNCATE checkpoint must run after the chats import",
            src.contains("PRAGMA wal_checkpoint(TRUNCATE)"),
        )
        // Best-effort: losing the checkpoint costs disk, never data, so it
        // must never fail the import.
        val i = src.indexOf("PRAGMA wal_checkpoint(TRUNCATE)")
        assertTrue(
            "the checkpoint must be wrapped in runCatching",
            src.lastIndexOf("runCatching", i) > src.lastIndexOf("applyFileResult", i),
        )
    }

    @Test
    fun `transactions do not swallow the orphan diagnostics`() {
        // The orphan warning is the importer's most load-bearing log line —
        // it is how a "sessions restored empty" report gets diagnosed. It must
        // stay OUTSIDE the transaction so it still runs after a commit.
        val warn = src.indexOf("dropped \$orphanedMessages message(s)")
        val close = src.indexOf("if (orphanedMessages > 0)")
        assertTrue("orphan warning missing", warn > 0)
        assertTrue("orphan check missing", close > 0)
        assertTrue("the warning must follow the messages loop", close < warn)
    }
}
