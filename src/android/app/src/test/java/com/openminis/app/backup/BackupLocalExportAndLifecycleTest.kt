package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-local-export] [T-android-backup-run-controller] Stage 1 of
 * the Android backup rework.
 *
 * Two user reports drove it:
 *  - "No way to back up locally on Android": Start refused outright unless an
 *    rclone / folder destination was configured, and there was no Save or
 *    Share exit for the package.
 *  - "Can't leave the backup screen": the run lived on the Backup screen's
 *    `viewModelScope`, so leaving the screen cancelled it mid-package.
 *
 * The run semantics live on [BackupRunController.RunResult] (pure, tested
 * directly). The wiring — which scope runs the job, whether Start is gated,
 * when Save / Share appear — needs Compose and a Context, so it is pinned
 * against the source, as the neighbouring backup tests do.
 */
class BackupLocalExportAndLifecycleTest {

    private fun outcome(name: String, ok: Boolean) =
        BackupHistory.DestinationOutcome(name, succeeded = ok, kind = "webdav", path = "/")

    // ── Run semantics ─────────────────────────────────────────────────────

    @Test
    fun `a run with no destination is local-only and not settled`() {
        val r = BackupRunController.RunResult(totalBytes = 10, skippedFiles = 0, destinations = emptyList())
        assertTrue(r.localOnly)
        assertFalse("nothing delivered it anywhere", r.allDelivered)
        assertFalse(
            "a local-only run nobody has saved must keep its card — it is the only prompt " +
                "to get the package out of the app",
            r.settled,
        )
    }

    @Test
    fun `saving a local-only run to the device settles it`() {
        val r = BackupRunController.RunResult(10, 0, emptyList(), savedAs = "backup-x.minisbak")
        assertTrue(r.settled)
    }

    @Test
    fun `a fully delivered run is settled and a partial one is not`() {
        assertTrue(BackupRunController.RunResult(1, 0, listOf(outcome("a", true))).settled)
        val partial = BackupRunController.RunResult(1, 0, listOf(outcome("a", true), outcome("b", false)))
        assertFalse("a failed destination is what the user must come back to", partial.settled)
        assertFalse("and it is not local-only — Save/Share are not offered", partial.localOnly)
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val vm by lazy { src("src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt") }
    private val screen by lazy { src("src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt") }
    private val controller by lazy { src("src/main/java/com/openminis/app/backup/BackupRunController.kt") }

    /** Source with comment lines dropped, so prose never satisfies a code check. */
    private fun code(s: String) = s.lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `the export no longer runs on the screen's viewModelScope`() {
        val body = code(vm).substringAfter("fun startExport(").substringBefore("\n    fun ")
        assertFalse("startExport must not launch on viewModelScope", body.contains("viewModelScope"))
        assertTrue("it must hand the run to the controller", body.contains("runner.start("))
        assertFalse("the VM must not hold its own export job any more", code(vm).contains("exportJob"))
    }

    @Test
    fun `the controller runs on its own process scope`() {
        val c = code(controller)
        assertTrue(c.contains("object BackupRunController"))
        assertTrue(
            "a SupervisorJob scope, so a run outlives any screen and one failure can't poison the next",
            c.contains("CoroutineScope(SupervisorJob()"),
        )
        assertTrue("the run must launch on that scope", c.contains("runJob = scope.launch"))
        assertTrue(
            "Save to Device must also run there, or a large copy dies when the user leaves",
            c.substringAfter("fun saveTo(").contains("scope.launch"),
        )
    }

    @Test
    fun `starting no longer requires a destination`() {
        val start = code(vm).substringAfter("fun startExport(").substringBefore("\n    fun ")
        assertFalse("the VM must not refuse on hasDestination", start.contains("hasDestination"))
        assertFalse(start.contains("backup_needs_destination"))
        val button = code(screen).substringAfter("if (running) vm.stopExport()").substringBefore("colors =")
        assertFalse(
            "the Start button must not be disabled for lack of a destination",
            button.contains("destinations.isNotEmpty()"),
        )
    }

    @Test
    fun `a local-only run offers Save and Share, and a delivered one does not`() {
        val s = code(screen)
        assertTrue(
            "Save / Share gated on THIS run having gone nowhere (iOS: deliveryResults.isEmpty)",
            s.contains("if (r.localOnly && pkg != null && pkg.exists())"),
        )
        assertTrue("Save uses SAF CreateDocument", s.contains("ActivityResultContracts.CreateDocument("))
        assertTrue("with the .minisbak MIME, so the picker keeps the extension", s.contains("BackupFormat.MIME_TYPE"))
        assertTrue("Share uses the system share sheet", s.contains("Intent.ACTION_SEND"))
    }

    @Test
    fun `a local-only result says it is not saved yet instead of reading as success`() {
        val footer = code(screen).substringAfter("private fun backupResultFooter(").substringBefore("\n}")
        assertTrue(footer.contains("backup_result_local_pending"))
        assertFalse(
            "the old 'Saved on this device only' reads as a finished backup",
            footer.contains("backup_result_local_only"),
        )
    }

    @Test
    fun `the backups directory is shareable through the file provider`() {
        val paths = src("src/main/res/xml/file_provider_paths.xml")
        assertTrue(
            "Backups/ must be a FileProvider root or getUriForFile throws on Share",
            paths.contains("""<files-path name="backups" path="Backups/" />"""),
        )
        // And it must be the directory the exporter actually writes to.
        assertTrue(
            src("src/main/java/com/openminis/app/backup/BackupFileTreeExporter.kt")
                .contains("""const val BACKUPS_DIR_NAME = "Backups""""),
        )
    }

    @Test
    fun `restore cannot start while a backup runs`() {
        // The two used to share one busy flag. The backup's now lives in the
        // controller, so restore's guard must read the union.
        val v = code(vm)
        assertTrue(v.contains("combine(_isRunning, runner.isRunning)"))
        assertFalse("no guard may read only the restore half", v.contains("if (_isRunning.value) return"))
    }
}
