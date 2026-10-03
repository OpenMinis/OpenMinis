package com.openminis.app.logging

import com.openminis.app.ui.settings.LogsAndCachesCleaner
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-log-level] [T-android-log-size-cap] [T-storage-clear-logs-caches]
 *
 * Drives the real [AppLogger] write path against a temp directory. There is no
 * Robolectric/mocking here, so the logger's private state (log dir, enabled,
 * verbose) is set directly instead of going through init(Context).
 */
class LogRetentionAndLevelTest {

    private lateinit var dir: File
    private var savedMax = 0L
    private var savedCheck = 0L

    private fun setField(name: String, value: Any?) {
        val f = AppLogger::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(null, value)
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("applogger").toFile()
        savedMax = AppLogger.MAX_LOG_FILE_BYTES
        savedCheck = AppLogger.SIZE_CHECK_EVERY_BYTES
        setField("logDir", dir)
        setField("enabled", true)
        setField("verbose", false)
    }

    @After
    fun tearDown() {
        AppLogger.clearLogs()
        setField("enabled", false)
        setField("verbose", false)
        setField("logDir", null)
        AppLogger.MAX_LOG_FILE_BYTES = savedMax
        AppLogger.SIZE_CHECK_EVERY_BYTES = savedCheck
        dir.deleteRecursively()
    }

    private fun todayLog(): String =
        dir.listFiles { f -> f.name.startsWith("minis-") && f.extension == "log" }!!
            .single().readText()

    @Test
    fun `info level keeps info and drops debug`() {
        AppLogger.info("T", "info-line")
        AppLogger.debug("T", "debug-line")
        val text = todayLog()
        assertTrue(text.contains("info-line"))
        assertFalse(text.contains("debug-line"))
    }

    @Test
    fun `verbose level writes debug too`() {
        setField("verbose", true)
        AppLogger.info("T", "info-line")
        AppLogger.debug("T", "debug-line")
        val text = todayLog()
        assertTrue(text.contains("info-line"))
        assertTrue(text.contains("debug-line"))
    }

    @Test
    fun `a file past the cap is cut to its newest half`() {
        AppLogger.MAX_LOG_FILE_BYTES = 64 * 1024
        AppLogger.SIZE_CHECK_EVERY_BYTES = 4 * 1024
        val pad = "x".repeat(200)
        for (i in 0 until 2000) AppLogger.info("T", "line-$i $pad")   // ~500 KB written
        val file = dir.listFiles { f -> f.name.startsWith("minis-") && f.extension == "log" }!!.single()
        // Bounded: never more than the cap plus one check interval of growth.
        assertTrue("size=${file.length()}",
            file.length() <= AppLogger.MAX_LOG_FILE_BYTES + AppLogger.SIZE_CHECK_EVERY_BYTES + 1024)
        val text = file.readText()
        assertTrue(text.startsWith("[log truncated"))
        assertTrue("newest line kept", text.contains("line-1999 "))
        assertFalse("oldest line dropped", text.contains("line-0 "))
        // Logging continues into the same file after a cut.
        AppLogger.info("T", "after-cut")
        assertTrue(file.readText().contains("after-cut"))
        assertFalse(File(dir, file.name + ".truncating").exists())
    }

    @Test
    fun `cleaner removes regenerable and old transient caches and nothing live`() {
        val cache = Files.createTempDirectory("cache").toFile()
        try {
            val now = System.currentTimeMillis()
            fun mk(rel: String, ageMs: Long = 0): File =
                File(cache, rel).apply {
                    parentFile!!.mkdirs(); writeText("0123456789"); setLastModified(now - ageMs)
                }
            val models = mk("models-cache/openai/a.json")
            val modelsDev = mk("models-dev-cache/api.json")
            val oldShare = mk("share/old.zip", ageMs = 2 * 3600_000L)
            val newShare = mk("share/new.zip", ageMs = 60_000L)
            val oldExport = mk("export-staging/x/data.json", ageMs = 3 * 3600_000L)
            File(cache, "export-staging/x").setLastModified(now - 3 * 3600_000L)
            // Live state that must survive no matter how old.
            val prootTmp = mk("proot-tmp/sock", ageMs = 30L * 24 * 3600_000L)
            val pasted = mk("pasted_text/draft.txt", ageMs = 30L * 24 * 3600_000L)
            val inbound = mk("share_inbound/in.bin", ageMs = 30L * 24 * 3600_000L)
            val restore = mk("restore-work/r.bin", ageMs = 30L * 24 * 3600_000L)

            assertEquals(40L, LogsAndCachesCleaner.cachesSize(cache, now))
            LogsAndCachesCleaner.clearCaches(cache, now)

            assertFalse(models.exists()); assertFalse(modelsDev.exists())
            assertFalse(oldShare.exists()); assertFalse(oldExport.exists())
            assertTrue("fresh share kept", newShare.exists())
            assertTrue(prootTmp.exists()); assertTrue(pasted.exists())
            assertTrue(inbound.exists()); assertTrue(restore.exists())
            assertEquals(0L, LogsAndCachesCleaner.cachesSize(cache, now))
        } finally {
            cache.deleteRecursively()
        }
    }
}
