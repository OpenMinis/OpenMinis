package com.openminis.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePathGuardTest {

    @Test
    fun parseSplitsAndNormalizes() {
        val paths = WritePathGuard.parse("/var/minis/workspace/a/, /tmp/out; /var/minis/workspace/a")
        assertEquals(listOf("/var/minis/workspace/a", "/tmp/out"), paths)
    }

    @Test
    fun denyOutsidePrefix() {
        val prev = WritePathGuard.swap(listOf("/var/minis/workspace/proj"))
        try {
            assertNull(WritePathGuard.denyReason("/var/minis/workspace/proj/src/Main.kt"))
            val denied = WritePathGuard.denyReason("/var/minis/workspace/other/x")
            assertTrue(denied!!.contains("write_paths"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun swapNoneStillDeniesWrites() {
        val prev = WritePathGuard.swap(listOf("none"))
        try {
            val denied = WritePathGuard.denyReason("/tmp/out")
            assertTrue(denied!!.contains("none"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun unrestrictedWhenEmpty() {
        val prev = WritePathGuard.swap(emptyList())
        try {
            assertNull(WritePathGuard.denyReason("/etc/passwd"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    // ─── [T-write-guard-fail-closed] present-but-unusable ≠ absent ─────────

    @Test
    fun parseKeepsThePoisonState() {
        // The three states the guard promises must survive the parse step, which
        // is where they used to collapse: `src`/`../out` filtered down to nothing
        // returned the SAME empty list as "write_paths absent".
        assertTrue("absent stays unrestricted", WritePathGuard.parse(null).isEmpty())
        assertTrue("blank stays unrestricted", WritePathGuard.parse("   ").isEmpty())
        assertEquals(listOf(WritePathGuard.NO_WRITE), WritePathGuard.parse("none"))
        assertEquals(
            "every entry unusable must yield the deny marker, not an empty list",
            listOf(WritePathGuard.INVALID),
            WritePathGuard.parse("src, ../out, ./relative"),
        )
        assertTrue(WritePathGuard.hasInvalidOnly("src, ../out"))
        assertFalse(
            "one usable prefix among garbage still allow-lists that prefix",
            WritePathGuard.hasInvalidOnly("src, /var/minis/workspace/out"),
        )
    }

    @Test
    fun presentButInvalidDeniesEveryWrite() {
        // End-to-end through the real production shape (raw model argument →
        // parse → guard installed): the worker must be denied, not unrestricted.
        val poison = WritePathGuard.parse("src/Main.kt, ../elsewhere")
        assertTrue(WritePathGuard.isPoisoned(poison))
        val prev = WritePathGuard.swap(poison)
        try {
            assertTrue(WritePathGuard.denyReason("/var/minis/workspace/any/file")!!.contains("write_paths"))
            assertTrue(WritePathGuard.denyReason("/tmp/x")!!.contains("no usable absolute prefix"))
            // The marker is a state flag, never a printable prefix.
            assertTrue(WritePathGuard.usablePrefixes(poison).isEmpty())
            assertEquals(
                "poison must not export a nonsense MINIS_WRITE_PATHS",
                "echo hi",
                WritePathGuard.wrapShellCommand("echo hi"),
            )
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun withPathsDeniesWhenEveryPrefixWasInvalid() = runBlocking {
        WritePathGuard.withPaths(WritePathGuard.parse("relative/only")) {
            withContext(Dispatchers.Default) {
                assertTrue(WritePathGuard.denyReason("/var/minis/workspace/x.kt") != null)
            }
        }
        assertNull(WritePathGuard.denyReason("/var/minis/workspace/x.kt"))
    }

    @Test
    fun wrapShellExportsAllowList() {
        val prev = WritePathGuard.swap(listOf("/tmp/out"))
        try {
            val wrapped = WritePathGuard.wrapShellCommand("echo hi")
            assertTrue(wrapped.contains("MINIS_WRITE_PATHS='/tmp/out'"))
            assertTrue(wrapped.contains("echo hi"))
        } finally {
            WritePathGuard.restore(prev)
        }
        assertEquals("echo hi", WritePathGuard.wrapShellCommand("echo hi"))
    }

    @Test
    fun withPathsSurvivesDispatcherHop() = runBlocking {
        WritePathGuard.withPaths(listOf("/tmp/out")) {
            withContext(Dispatchers.Default) {
                assertNull(WritePathGuard.denyReason("/tmp/out/a.txt"))
                assertTrue(WritePathGuard.denyReason("/etc/passwd")!!.contains("write_paths"))
                val wrapped = WritePathGuard.wrapShellCommand("true")
                assertTrue(wrapped.contains("MINIS_WRITE_PATHS='/tmp/out'"))
            }
        }
        assertNull(WritePathGuard.denyReason("/etc/passwd"))
    }
}
