package com.openminis.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Crash-time persistence must never throw and must not let a runaway report
 * fill the device. These tests exercise the writer without ACRA/Android.
 */
class CrashReportWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesReportAtomically() {
        val dir = File(tmp.root, "logs")
        val out = CrashReportWriter.write(dir, "crash-1.log", "hello crash")
        assertEquals("hello crash", out?.readText())
    }

    @Test
    fun oversizedReportIsCapped() {
        val dir = File(tmp.root, "logs")
        val body = "x".repeat(CrashReportWriter.MAX_CHARS + 4096)
        val out = CrashReportWriter.write(dir, "crash-big.log", body)!!
        val text = out.readText()
        assertTrue(text.length > CrashReportWriter.MAX_CHARS)
        assertTrue(text.length < body.length)
        assertTrue(text.endsWith("[report truncated at ${CrashReportWriter.MAX_CHARS} chars]"))
    }

    @Test
    fun unwritableDirectoryReturnsNullInsteadOfThrowing() {
        // A regular file cannot contain a child directory.
        val blocker = tmp.newFile("blocker")
        val result = CrashReportWriter.write(File(blocker, "logs"), "crash.log", "body")
        assertNull(result)
    }

    @Test
    fun failedWriteLeavesNoTempFile() {
        val dir = File(tmp.root, "logs")
        // A directory named like the temp target makes FileOutputStream fail.
        File(dir, ".crash-2.log.tmp").mkdirs()
        val result = CrashReportWriter.write(dir, "crash-2.log", "body")
        assertNull(result)
        assertTrue(File(dir, "crash-2.log").exists().not())
    }
}
