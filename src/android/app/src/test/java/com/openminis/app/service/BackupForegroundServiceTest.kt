package com.openminis.app.service

import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.BackupRunController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-fgs] Stage 2: a running backup survives the app being
 * backgrounded.
 *
 * Reported: a backup dies when the screen goes off or the phone hops cells.
 * Stage 1 moved the run to a process scope, which survives leaving the SCREEN
 * but not the process being frozen or reclaimed in the background. This pins
 * the foreground service that prevents that — the classification it announces
 * with (pure), and its wiring and lifecycle (source), since a Service needs a
 * device to run.
 */
class BackupForegroundServiceTest {

    private fun result(vararg ok: Boolean) = BackupRunController.RunResult(
        totalBytes = 1, skippedFiles = 0,
        destinations = ok.mapIndexed { i, s ->
            BackupHistory.DestinationOutcome("d$i", succeeded = s, kind = "webdav", path = "/")
        },
    )

    // ── What the finished notification says ───────────────────────────────

    @Test
    fun `a local-only package is never announced as delivered`() {
        // It is still inside the app; the notification is the prompt to move it.
        assertEquals(
            BackupForegroundService.Outcome.LOCAL_PENDING,
            BackupForegroundService.outcomeOf(result(), error = null),
        )
    }

    @Test
    fun `each finished shape maps to its own outcome`() {
        assertEquals(BackupForegroundService.Outcome.DELIVERED, BackupForegroundService.outcomeOf(result(true), null))
        assertEquals(
            BackupForegroundService.Outcome.ISSUES,
            BackupForegroundService.outcomeOf(result(true, false), "Saved locally, but delivery failed for: d1"),
        )
        assertEquals(BackupForegroundService.Outcome.FAILED, BackupForegroundService.outcomeOf(null, "disk full"))
        assertEquals(
            "no package and no error means the user stopped it",
            BackupForegroundService.Outcome.STOPPED,
            BackupForegroundService.outcomeOf(null, null),
        )
    }

    // ── Wiring & lifecycle ────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val svc by lazy {
        src("src/main/java/com/openminis/app/service/BackupForegroundService.kt").lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }
    private val manifest by lazy { src("src/main/AndroidManifest.xml") }

    @Test
    fun `the manifest declares a dataSync service and its permission`() {
        assertTrue(
            "Android 14+ refuses startForeground(type=dataSync) without this permission",
            manifest.contains("""android.permission.FOREGROUND_SERVICE_DATA_SYNC"""),
        )
        val decl = manifest.substringAfter("""android:name=".service.BackupForegroundService"""").substringBefore("/>")
        assertTrue("service must be declared", manifest.contains(".service.BackupForegroundService"))
        assertTrue(decl.contains("""android:foregroundServiceType="dataSync""""))
        assertTrue("not callable by other apps", decl.contains("""android:exported="false""""))
    }

    @Test
    fun `the service goes foreground as dataSync before doing anything else`() {
        val start = svc.substringAfter("override fun onStartCommand(").substringBefore("\n    }")
        val fg = start.indexOf("goForeground(")
        val locks = start.indexOf("acquireLocks()")
        assertTrue("startForeground must be answered within ~5 s — first thing", fg in 0 until locks)
        assertTrue(svc.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue("no restart for a run that no longer exists", start.contains("START_NOT_STICKY"))
    }

    @Test
    fun `it holds a partial wake lock with a cap, and releases it`() {
        assertTrue(svc.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue("a lock must never outlive a wedged run", svc.contains("acquire(WAKE_LOCK_CAP_MS)"))
        for (fn in listOf("private fun finish()", "override fun onDestroy()", "override fun onTimeout(startId: Int, fgsType: Int)")) {
            assertTrue(
                "$fn must release the locks",
                svc.substringAfter(fn).substringBefore("\n    }").contains("releaseLocks()"),
            )
        }
    }

    @Test
    fun `the dataSync time cap stops the service instead of killing the process`() {
        // Missing the grace period after onTimeout throws
        // ForegroundServiceDidNotStopInTimeException and kills the whole
        // process — the backup with it. The agent service left dataSync over
        // exactly this; here it is handled.
        val t = svc.substringAfter("override fun onTimeout(startId: Int, fgsType: Int)").substringBefore("\n    }")
        assertTrue(t.contains("stopForegroundCompat()"))
        assertTrue(t.contains("stopSelf()"))
    }

    @Test
    fun `the service follows the run and stops itself when it ends`() {
        val observe = svc.substringAfter("private fun observeRun()").substringBefore("\n    }")
        assertTrue(observe.contains("BackupRunController.isRunning"))
        assertTrue("run over → outcome, release, stop", observe.contains("finish()"))
        val finish = svc.substringAfter("private fun finish()").substringBefore("\n    }")
        assertTrue(finish.contains("postOutcome()") && finish.contains("stopSelf()"))
    }

    @Test
    fun `progress updates are throttled below the system drop rate`() {
        assertTrue(BackupForegroundService.PROGRESS_THROTTLE_MS >= 500)
        assertTrue(svc.substringAfter("private fun updateProgress(").contains("PROGRESS_THROTTLE_MS"))
    }

    @Test
    fun `the controller starts the service for every run`() {
        val start = src("src/main/java/com/openminis/app/backup/BackupRunController.kt")
            .substringAfter("fun start(context: Context, request: Request): Boolean")
            .substringBefore("\n    fun saveTo(")
        assertTrue(start.contains("BackupForegroundService.start(app)"))
    }
}
