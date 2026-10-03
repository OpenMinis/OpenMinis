package com.openminis.app.ui.settings.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-ui] Stopping a restore, and keeping progress out of the
 * screen's top-level recomposition scope.
 *
 * Both are structural properties of code that needs a running restore, a real
 * database and a Compose host to exercise, none of which this module has. They
 * also both fail silently: a swallowed cancellation shows the user "Restore
 * failed" instead of a clean stop, and a progress collect creeping back up to
 * the top level just makes the screen slow again.
 */
class RestoreStopAndScopeTest {

    private val vm by lazy {
        ProductionSources.read("ui/settings/backup/BackupViewModel.kt")
    }
    private val screen by lazy {
        ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    }
    private val importer by lazy {
        ProductionSources.read("backup/BackupImporter.kt")
    }

    // ── cancellation ─────────────────────────────────────────────────────

    @Test
    fun `the restore coroutine is retained so it can be cancelled`() {
        assertTrue("restoreJob field missing", vm.contains("private var restoreJob"))
        assertTrue(
            "startRestore must assign the job",
            vm.contains("restoreJob = viewModelScope.launch {"),
        )
    }

    @Test
    fun `stopRunningRestore cancels and clears the live state`() {
        val fn = vm.substringAfter("fun stopRunningRestore()").substringBefore("\n    }")
        assertTrue("must cancel the job", fn.contains("job.cancel()"))
        assertTrue("must clear isRunning", fn.contains("_isRunning.value = false"))
        assertTrue("must clear progress", fn.contains("_restoreProgress.value = null"))
        assertTrue("must clear the estimate", fn.contains("_restoreEtaSeconds.value = null"))
    }

    @Test
    fun `cancellation is not reported to the user as a failure`() {
        // The generic `catch (e: Exception)` would otherwise swallow
        // CancellationException and surface "Restore failed" for what the user
        // deliberately asked for. The specific clause must come FIRST and
        // rethrow so the coroutine still ends as cancelled.
        // Anchored on the RESTORE job specifically: this file also cancels an
        // export and a download, each with their own clause, and a loose
        // substringAfter lands in whichever appears first.
        val body = vm.substringAfter("restoreJob = viewModelScope.launch {")
            .substringBefore("fun stopRunningRestore()")
        val cancelAt = body.indexOf("catch (e: kotlinx.coroutines.CancellationException)")
        val genericAt = body.indexOf("catch (e: Exception)")
        assertTrue("no CancellationException clause", cancelAt > 0)
        assertTrue("it must precede the generic catch", cancelAt < genericAt)
        val clause = body.substring(cancelAt, genericAt)
        assertTrue("must rethrow", clause.contains("throw e"))
        assertTrue("must not set an error", !clause.contains("_errorText"))
    }

    @Test
    fun `the importer checks for cancellation inside its long loops`() {
        // Without this a cancelled restore keeps writing until the category
        // ends — on the measured package, minutes after the user pressed stop.
        assertTrue(
            "the messages loop must check",
            importer.contains("coroutineContext.ensureActive()"),
        )
        // Checked on the progress beat, not per record: this loop runs 200k+
        // times and the check is not free.
        val msgLoop = importer.substringAfter("""readJsonl(dataDir, "messages")""")
            .substringBefore("val createdAt")
        assertTrue(
            "the check belongs on the throttled beat",
            msgLoop.contains("if (++seen % 200 == 0)") && msgLoop.contains("ensureActive()"),
        )
    }

    // ── recomposition scope ──────────────────────────────────────────────

    @Test
    fun `progress is not collected at the screen's top level`() {
        // This is the whole point of the section composable: collecting here
        // put every switch, field and card on the screen in the same
        // recomposition scope as a counter ticking several times a second.
        val topLevel = screen.substringBefore("private fun RestoreProgressSection")
        assertEquals(
            "restoreProgress must not be collected outside RestoreProgressSection",
            0,
            Regex("""vm\.restoreProgress\.collectAsState\(\)""").findAll(topLevel).count(),
        )
    }

    @Test
    fun `the section owns both progress and eta`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "progress must be collected in the section",
            section.contains("vm.restoreProgress.collectAsState()"),
        )
        assertTrue(
            "eta must be collected in the section",
            section.contains("vm.restoreEtaSeconds.collectAsState()"),
        )
    }

    // ── the stop control and its confirmation ────────────────────────────

    @Test
    fun `the ring carries a stop target only while running`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "the stop handler must be gated on running",
            section.contains("onStopClick = { confirmStop = true }.takeIf { running }"),
        )
    }

    @Test
    fun `stopping asks for confirmation before cancelling`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue("no dialog", section.contains("AlertDialog("))
        // The tap must only open the dialog; the cancel happens on confirm.
        val confirm = section.substringAfter("confirmButton = {").substringBefore("dismissButton")
        assertTrue("confirm must call the ViewModel", confirm.contains("vm.stopRunningRestore()"))
        assertTrue(
            "the destructive action must be coloured as such",
            confirm.contains("MaterialTheme.colorScheme.error"),
        )
        val dismiss = section.substringAfter("dismissButton = {")
        assertTrue("dismiss must not cancel the restore", !dismiss.contains("stopRunningRestore"))
    }

    @Test
    fun `the stop target is big enough to hit`() {
        // The visible ring is 24dp, below the accessibility minimum, so the
        // hit rect is expanded around it rather than growing the ring.
        val content = screen.substringAfter("fun RowScope.PrimaryActionContent")
            .substringBefore("fun ")
        assertTrue("hit target must be 36dp", content.contains(".size(36.dp)"))
        assertTrue("ring should be 24dp", content.contains(".size(24.dp)"))
        assertTrue("stop glyph should be 8dp", content.contains(".size(8.dp)"))
    }

    @Test
    fun `the button is inert while a restore runs`() {
        // Only the stop target is live; a stray tap on the bar must not
        // restart or abort anything.
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "the button's enabled flag must come from the caller's guard",
            section.contains("enabled = enabled,"),
        )
    }

    // ── percent label ────────────────────────────────────────────────────

    @Test
    fun `the label leads with a percentage`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "must use the percent string",
            section.contains("R.string.backup_restoring_percent"),
        )
        assertTrue(
            "percent must be clamped",
            section.contains("coerceIn(0, 100)"),
        )
    }

    @Test
    fun `the percentage cannot overflow on a large package`() {
        // done * 100 exceeds Int range above ~21M records. The measured
        // package was 202,705, but the arithmetic must not be the thing that
        // breaks on a bigger one.
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "percent must be computed in Long",
            section.contains("p.done.toLong() * 100 / total"),
        )
    }
}
