package com.openminis.app.ui.settings.backup

import com.openminis.app.ProductionSources
import com.openminis.app.backup.BackupImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-report-severity] Restore-report lines are split into
 * errors (red) and notices (amber).
 *
 * Reported: files the source device deliberately left out of the backup — over
 * the size cap, or never downloaded there — showed in the error colour after a
 * restore, reading as if the restore had broken.
 *
 * The classification is tested as data. The composable only maps it to strings
 * and colours; the source guards at the bottom pin that mapping.
 */
class RestoreReportSeverityTest {

    private fun report(vararg cats: BackupImporter.CategoryReport) =
        BackupImporter.Report(backupId = "b", createdAt = null, sourcePlatform = null)
            .also { r -> r.categories.addAll(cats) }

    private fun severities(r: BackupImporter.Report) =
        restoreIssues(r).associate { it.kind to it.severity }

    // ── the reported case ────────────────────────────────────────────────

    @Test
    fun `size-capped files are a notice, not an error`() {
        val r = report(BackupImporter.CategoryReport("chats", sizeSkippedInPackage = 65))
        val issue = restoreIssues(r).single()
        assertEquals(RestoreIssueKind.SIZE_SKIPPED, issue.kind)
        assertEquals(RestoreIssueSeverity.NOTICE, issue.severity)
        assertEquals(65, issue.count)
    }

    @Test
    fun `files never downloaded on the source are a notice`() {
        val r = report(BackupImporter.CategoryReport("chats", notDownloadedInPackage = 3))
        assertEquals(RestoreIssueSeverity.NOTICE, restoreIssues(r).single().severity)
    }

    // ── what stays an error ──────────────────────────────────────────────

    @Test
    fun `a category that threw is an error and keeps its message`() {
        val r = report(BackupImporter.CategoryReport("skills", failed = "disk full"))
        val issue = restoreIssues(r).single()
        assertEquals(RestoreIssueSeverity.ERROR, issue.severity)
        assertEquals("disk full", issue.detail)
    }

    @Test
    fun `unreadable records are an error`() {
        val r = report(BackupImporter.CategoryReport("chats", unreadable = 2))
        assertEquals(RestoreIssueSeverity.ERROR, restoreIssues(r).single().severity)
    }

    @Test
    fun `a blob missing from the package is an error, not a notice`() {
        // The easy mistake. It is "a file not being there", like the two
        // notices, but the index lists it with NO tombstone: nobody chose to
        // leave it out, the package is damaged. Calling it amber would tell
        // the user a broken backup is fine.
        val r = report(BackupImporter.CategoryReport("chats", missingBlobs = 4))
        assertEquals(RestoreIssueSeverity.ERROR, restoreIssues(r).single().severity)
    }

    // ── mixed reports ────────────────────────────────────────────────────

    @Test
    fun `every counter lands in the right bucket in one report`() {
        val r = report(
            BackupImporter.CategoryReport(
                "chats",
                failed = "boom",
                unreadable = 1,
                missingBlobs = 1,
                sizeSkippedInPackage = 1,
                notDownloadedInPackage = 1,
            )
        )
        assertEquals(
            mapOf(
                RestoreIssueKind.FAILED to RestoreIssueSeverity.ERROR,
                RestoreIssueKind.MISSING_FROM_PACKAGE to RestoreIssueSeverity.ERROR,
                RestoreIssueKind.UNREADABLE to RestoreIssueSeverity.ERROR,
                RestoreIssueKind.SIZE_SKIPPED to RestoreIssueSeverity.NOTICE,
                RestoreIssueKind.NOT_DOWNLOADED to RestoreIssueSeverity.NOTICE,
            ),
            severities(r),
        )
    }

    @Test
    fun `within a category the most serious comes first`() {
        val r = report(
            BackupImporter.CategoryReport(
                "chats", sizeSkippedInPackage = 1, unreadable = 1, failed = "x", missingBlobs = 1,
            )
        )
        assertEquals(
            listOf(
                RestoreIssueKind.FAILED,
                RestoreIssueKind.MISSING_FROM_PACKAGE,
                RestoreIssueKind.UNREADABLE,
                RestoreIssueKind.SIZE_SKIPPED,
            ),
            restoreIssues(r).map { it.kind },
        )
    }

    @Test
    fun `categories keep report order and carry their own key`() {
        val r = report(
            BackupImporter.CategoryReport("chats", sizeSkippedInPackage = 1),
            BackupImporter.CategoryReport("skills", unreadable = 1),
        )
        assertEquals(listOf("chats", "skills"), restoreIssues(r).map { it.categoryKey })
    }

    @Test
    fun `a clean report has no issues`() {
        val r = report(BackupImporter.CategoryReport("chats", imported = 1000))
        assertTrue(restoreIssues(r).isEmpty())
    }

    @Test
    fun `zero counts produce no lines`() {
        // Every counter defaults to 0; none of them may leak a "0 files" line.
        val r = report(BackupImporter.CategoryReport("chats"))
        assertTrue(restoreIssues(r).isEmpty())
    }

    // ── the screen's mapping ─────────────────────────────────────────────

    private val screen by lazy {
        ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    }

    @Test
    fun `the screen classifies through restoreIssues, not its own rules`() {
        assertTrue(screen.contains("val issues = restoreIssues(report)"))
        // The old single list painted everything in the error colour.
        assertTrue("the single problems list must be gone", !screen.contains("val problems = buildList"))
    }

    @Test
    fun `errors are red and notices are amber, in separate sections`() {
        assertTrue(
            "errors must use the theme error colour",
            screen.contains("ReportLine(it, Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)"),
        )
        assertTrue(
            "notices must use the amber helper",
            screen.contains("notices.forEach { ReportLine(it, Icons.Outlined.Warning, amber) }"),
        )
        assertTrue("notices get their own header", screen.contains("R.string.backup_report_notices"))
    }

    @Test
    fun `report warnings read as notices, not errors`() {
        val notices = screen.substringAfter("val notices =").substringBefore("if (errors.isNotEmpty())")
        assertTrue(notices.contains("report.warnings"))
        // The expression line only: the comment that follows it names
        // report.warnings, and a region check would match the comment.
        val errors = screen.substringAfter("val errors =").substringBefore("\n")
        assertTrue(!errors.contains("report.warnings"))
    }

    @Test
    fun `the amber stays readable in light mode`() {
        // #E6A23C measures 2.19:1 on a light surface, far under the 4.5:1 that
        // small text needs. The light-mode value must be the darker amber.
        val fn = screen.substringAfter("private fun reportNoticeColor()")
        assertTrue("light-mode amber must be #B45309", fn.contains("Color(0xFFB45309)"))
        assertTrue("dark-mode amber must be #FFB74D", fn.contains("Color(0xFFFFB74D)"))
        assertTrue("the too-light amber must not be used", !fn.contains("0xFFE6A23C"))
    }
}
