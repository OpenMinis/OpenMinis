package com.openminis.app.perf

import com.openminis.app.ProductionSources
import com.openminis.app.ui.chat.SystemResourceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two main-thread hotspots found by profiling a task on a Pixel 6 perf
 * build: Compose content capture (10.45% of app CPU) and the shell tile's
 * resource readout parsing /proc/self/smaps on Main every 2 s (5%).
 */
class MainThreadHotspotsTest {

    // ── [T-android-resource-hud-offmain] ─────────────────────────────────────

    private val rollup = """
        00400000-7fffffff ---p 00000000 00:00 0                          [rollup]
        Rss:              412340 kB
        Pss:              301224 kB
        Pss_Anon:         210000 kB
        Pss_File:          80000 kB
        Pss_Shmem:         11224 kB
        Shared_Clean:      90000 kB
        Private_Dirty:    200000 kB
        Swap:              50000 kB
        SwapPss:           40100 kB
        Locked:                0 kB
    """.trimIndent()

    @Test
    fun `total PSS is Pss plus SwapPss, like Debug MemoryInfo getTotalPss`() {
        assertEquals(301224L + 40100L, SystemResourceMonitor.parseTotalPssKb(rollup))
    }

    @Test
    fun `sub-fields like Pss_Anon are not mistaken for the total`() {
        val onlySubfields = "Pss_Anon: 5 kB\nPss_File: 6 kB\n"
        assertNull(SystemResourceMonitor.parseTotalPssKb(onlySubfields))
    }

    @Test
    fun `no swap line means no swap`() {
        assertEquals(1000L, SystemResourceMonitor.parseTotalPssKb("Rss: 2000 kB\nPss: 1000 kB\n"))
    }

    @Test
    fun `sampling runs off the main thread and reads the rollup first`() {
        val src = ProductionSources.read("ui/chat/SystemResourceMonitor.kt")
        assertEquals(2, Regex("""withContext\(Dispatchers\.IO\) \{ monitor\.sampleOnce\(context\) \}""").findAll(src).count())
        assertFalse("no bare sample on the composition's dispatcher", Regex("""\n\s+monitor\.sampleOnce\(context\)\n""").containsMatchIn(src))
        val mem = src.substringAfter("private fun sampleMemory(").substringBefore("\n    companion object")
        assertTrue(mem.indexOf("smaps_rollup") in 0 until mem.indexOf("Debug.getMemoryInfo(mi)"))
    }

    // ── [T-android-content-capture-off] ──────────────────────────────────────

    @Test
    fun `content capture is switched off once, first thing in onCreate`() {
        val src = ProductionSources.read("MinisApp.kt")
        val body = src.substringAfter("override fun onCreate() {")
        val off = body.indexOf("androidx.compose.ui.contentcapture.ContentCaptureManager.isEnabled = false")
        assertTrue(off in 0 until body.indexOf("AgentToolSwitch.migrateLegacyIfNeeded"))
        // Never flipped back on anywhere: Compose 1.9.1 cannot resume after a pause.
        val all = ProductionSources.mainRoot()!!.walkTopDown().filter { it.extension == "kt" }
            .map { it.readText() }.joinToString("\n")
        assertFalse(all.contains("ContentCaptureManager.isEnabled = true"))
    }
}
