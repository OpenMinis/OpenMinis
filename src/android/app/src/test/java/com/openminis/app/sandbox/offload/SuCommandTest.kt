package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuCommandTest {

    @Test
    fun emptyArgvIsHelp() {
        val p = SuCommand.parse(listOf("su"))
        assertEquals(SuCommand.Kind.HELP, p.kind)
        assertNull(p.command)
    }

    @Test
    fun dashCCapturesSingleCommandToken() {
        val p = SuCommand.parse(listOf("su", "-c", "id"))
        assertEquals(SuCommand.Kind.EXEC, p.kind)
        assertEquals("id", p.command)
        assertEquals(SuCommand.DEFAULT_TIMEOUT_MS, p.timeoutMs)
    }

    @Test
    fun dashCWithQuotedPayloadStaysOneArgvSlot() {
        val p = SuCommand.parse(listOf("android-su", "-c", "ls /data"))
        assertEquals(SuCommand.Kind.EXEC, p.kind)
        assertEquals("ls /data", p.command)
    }

    @Test
    fun execJoinsRemainingTokens() {
        val p = SuCommand.parse(listOf("android-su", "exec", "getprop", "ro.build.version.release"))
        assertEquals(SuCommand.Kind.EXEC, p.kind)
        assertEquals("getprop ro.build.version.release", p.command)
    }

    /**
     * [T-su-exec-quoting] A token the guest shell delivered as one argv slot
     * because the user quoted it must stay ONE argument on the host `su -c`.
     */
    @Test
    fun execQuotesTokenContainingSpace() {
        val p = SuCommand.parse(listOf("android-su", "exec", "cat", "/data/x a.txt"))
        assertEquals(SuCommand.Kind.EXEC, p.kind)
        assertEquals("cat '/data/x a.txt'", p.command)
    }

    @Test
    fun execEscapesEmbeddedSingleQuote() {
        val p = SuCommand.parse(listOf("android-su", "exec", "echo", "it's"))
        assertEquals(SuCommand.Kind.EXEC, p.kind)
        // POSIX: 'it'\''s' — close-quote, escaped quote, reopen-quote.
        // As a Kotlin literal: 'it' + \(from \\) + '' + s + ' = "echo 'it'\\''s'".
        assertEquals("echo 'it'\\''s'", p.command)
    }

    @Test
    fun timeoutFlagIsParsedBeforeCommand() {
        val p = SuCommand.parse(listOf("su", "--timeout", "15", "-c", "id"))
        assertEquals(SuCommand.Kind.EXEC, p.kind)
        assertEquals("id", p.command)
        assertEquals(15_000L, p.timeoutMs)
    }

    @Test
    fun timeoutAboveTwoMinutesIsClamped() {
        val p = SuCommand.parse(listOf("su", "--timeout", "999999", "-c", "id"))
        assertEquals(120_000L, p.timeoutMs)
        val confined = SuCommand.confineHost("id")
        assertFalse(confined.contains("|| nice"))
        assertEquals(1, confined.split("id").size - 1)
    }

    @Test
    fun statusAndPingSkipExec() {
        assertEquals(SuCommand.Kind.STATUS, SuCommand.parse(listOf("su", "status")).kind)
        assertEquals(SuCommand.Kind.STATUS, SuCommand.parse(listOf("android-su", "ping")).kind)
    }

    @Test
    fun missingDashCValueIsInvalid() {
        assertEquals(SuCommand.Kind.INVALID, SuCommand.parse(listOf("su", "-c")).kind)
        assertEquals(SuCommand.Kind.INVALID, SuCommand.parse(listOf("android-su", "exec")).kind)
    }

    @Test
    fun candidateListCoversMagiskAndKernelSU() {
        assertTrue(SuCommand.SU_CANDIDATES.contains("/system/bin/su"))
        assertTrue(SuCommand.SU_CANDIDATES.contains("/system/bin/ksu"))
        assertTrue(SuCommand.SU_CANDIDATES.contains("/debug_ramdisk/su"))
        assertTrue(SuCommand.SU_CANDIDATES.contains("/debug_ramdisk/ksu"))
        assertTrue(SuCommand.SU_CANDIDATES.contains("/data/adb/ksud"))
        assertTrue(SuCommand.SU_CANDIDATES.contains("/data/adb/magisk/magisk"))
    }

    @Test
    fun elevationFailureMatchesMagiskDeny() {
        assertTrue(SuCommand.looksLikeElevationFailure(1, "Permission denied\n"))
        assertTrue(SuCommand.looksLikeElevationFailure(1, ""))
        assertTrue(SuCommand.looksLikeElevationFailure(255, "su: authentication failed"))
        assertTrue(SuCommand.looksLikeElevationFailure(1, "su: failed to execute"))
    }

    @Test
    fun elevationFailureIgnoresCommandErrorsAndTimeouts() {
        org.junit.Assert.assertFalse(SuCommand.looksLikeElevationFailure(0, "uid=0(root)\n"))
        org.junit.Assert.assertFalse(SuCommand.looksLikeElevationFailure(124, "android-su: timed out after 60000ms\n"))
        org.junit.Assert.assertFalse(
            SuCommand.looksLikeElevationFailure(
                1,
                "rm: cannot remove '/data/app/foo': Permission denied\nmore output that means the command ran\n",
            ),
        )
        org.junit.Assert.assertFalse(SuCommand.looksLikeElevationFailure(2, "ls: cannot access '/nope': No such file or directory\n"))
    }
}
