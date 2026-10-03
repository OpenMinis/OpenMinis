package com.openminis.app.backup

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-android-backup-webdav-deadline] Two field problems, both fixed on iOS
 * first (ceac9df0b, c82fe8eb4) and reproduced here on a Pixel 4a:
 *  - backups to a slow WebDAV server always failed: rclone's `Timeout` is a
 *    flat response deadline counted from the end of the upload, and 45 s
 *    failed every server that needs longer to answer;
 *  - Stop could not interrupt an upload in flight: the upload was one
 *    blocking call and the run's cancellation never reached it.
 */
class BackupStopAndDeadlineTest {

    private val tmp: File = Files.createTempDirectory("backup-stop").toFile()

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    // ── Stop during the file-tree export (behaviour) ──────────────────────

    private fun treeExporter(isCancelled: () -> Boolean): BackupFileTreeExporter {
        val staging = File(tmp, "staging").apply { mkdirs() }
        return BackupFileTreeExporter(
            BackupBlobStore(staging, null),
            BackupFileIndexWriter(File(staging, "files.index.jsonl")),
            isCancelled = isCancelled,
        )
    }

    private fun sourceTree(files: Int): File {
        val root = File(tmp, "source").apply { mkdirs() }
        repeat(files) { File(root, "f$it.txt").writeText("x$it") }
        return root
    }

    @Test
    fun `Stop ends a large tree walk at a file boundary`() {
        val root = sourceTree(200)
        var polls = 0
        try {
            treeExporter { polls++; true }.export(root, "shared", BackupCategory.SHARED_FILES)
            fail("expected the walk to stop")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Checked every CANCEL_CHECK_EVERY entries, not on every file.
            assertEquals(1, polls)
        }
    }

    @Test
    fun `without Stop the walk exports everything`() {
        val root = sourceTree(100)
        val r = treeExporter { false }.export(root, "shared", BackupCategory.SHARED_FILES)
        assertEquals(100, r.filesIncluded)
    }

    // ── Wiring (source) ───────────────────────────────────────────────────

    @Test
    fun `the upload runs as an async rclone job that Stop can end`() {
        val up = src("src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt")
        val upload = up.substringAfter("    fun upload(").substringBefore("    private fun runCopyJob(")
        assertTrue(upload.contains("runCopyJob("))
        assertTrue(upload.contains("pauseCancellably(RETRY_BACKOFF_MS, isCancelled)"))
        val job = up.substringAfter("    private fun runCopyJob(").substringBefore("    private fun pauseCancellably(")
        assertTrue(job.contains("\"_async\" to true, \"_group\" to group"))
        assertTrue(job.contains("\"job/stop\""))
        assertTrue(job.contains("\"core/stats\", mapOf(\"group\" to group)"))
        assertFalse("process-wide byte counter is gone", up.contains("private fun statsBytes()"))
    }

    @Test
    fun `a backup run hands its cancellation to every delivery path`() {
        val run = src("src/main/java/com/openminis/app/backup/BackupRunController.kt")
        assertTrue(run.contains("isCancelled = { job?.isActive == false },"))
        val deliver = run.substringAfter("private fun deliverToDestinations(")
        assertTrue(deliver.contains("uploader.upload(packageFile, remote, backupId, isCancelled = isCancelled)"))
        assertTrue(deliver.contains("localDelivery.deliver(packageFile, treeUri, isCancelled)"))
        assertTrue("stops between destinations", deliver.contains("if (isCancelled()) {"))
        val export = src("src/main/java/com/openminis/app/backup/BackupExporter.kt")
        assertTrue(export.contains("isCancelled = { runJob?.isActive == false },"))
    }

    @Test
    fun `retry pause and tree checkpoint match iOS`() {
        assertEquals(3_000L, com.openminis.app.backup.remote.RcloneChunkedUpload.RETRY_BACKOFF_MS)
        assertEquals(32, BackupFileTreeExporter.CANCEL_CHECK_EVERY)
    }
}
