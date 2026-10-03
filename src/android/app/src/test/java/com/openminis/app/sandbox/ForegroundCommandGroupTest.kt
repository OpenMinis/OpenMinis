package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-shell-timeout-pgid] Issue #358 — one command outrunning its
 * timeout wedged its whole session.
 *
 * Reported on a vivo V2301A (Android 16, PRoot Alpine): a long curl loop hit
 * the 180s timeout, and from then on every `shell_execute` in that session
 * timed out too — `echo alive` included — while other sessions and the
 * interactive Terminal kept working.
 *
 * The timeout path did nothing to the runaway:
 *
 *     // Timeout — cancel pending, but don't kill the shell
 *     pendingCallback = null
 *
 * so it kept running inside the session's long-lived, REUSED shell. `/bin/sh`
 * reads stdin serially, so later commands merely queued behind it and could
 * not start — hence even a no-op command burned its full timeout.
 *
 * The fix runs each foreground command under `setsid`, giving it its own
 * process group, and kills that group on timeout. These tests pin the
 * decisions; the wrapper's runtime behaviour was verified on device (see the
 * measurements quoted in ForegroundCommandGroup's KDoc).
 */
class ForegroundCommandGroupTest {

    private val marker = "abc12345"

    // ── The wrapper ────────────────────────────────────────────────────────

    @Test
    fun `the command runs in its own session via setsid`() {
        val w = ForegroundCommandGroup.wrap("echo hi", marker)
        assertTrue("must invoke setsid", w.contains(ForegroundCommandGroup.SETSID))
        assertTrue("must still emit the completion marker", w.contains("__MINIS_DONE_${marker}_EXIT_"))
        assertTrue("must report its pgid first", w.contains("__MINIS_PGID_${marker}_"))
    }

    @Test
    fun `the pgid is read from procfs, not from ps`() {
        // busybox `ps` has no `-o pgid=`, so field 5 of /proc/self/stat is the
        // only portable source inside this rootfs.
        val w = ForegroundCommandGroup.wrap("echo hi", marker)
        assertTrue(w.contains("/proc/self/stat"))
    }

    @Test
    fun `command text is passed through untouched, never parsed`() {
        // The whole reason for using a process group: these all contain `&`
        // and none of them is a background job. A string matcher would have to
        // parse shell grammar to tell them apart; the kernel already knows.
        val tricky = listOf(
            """curl 'https://x.test/a?u=1&v=2'""",
            """grep 'a & b' file.txt""",
            """awk '{print ${'$'}1 & ${'$'}2}'""",
            """echo "a && b"""",
        )
        for (cmd in tricky) {
            val w = ForegroundCommandGroup.wrap(cmd, marker)
            // Every original character survives, single quotes escaped POSIX-style.
            val expected = cmd.replace("'", "'\\''")
            assertTrue("must embed `$cmd` verbatim", w.contains(expected))
        }
    }

    @Test
    fun `an embedded single quote is escaped the POSIX way`() {
        val w = ForegroundCommandGroup.wrap("echo 'hi'", marker)
        // '\'' — close, escaped quote, reopen. Anything else would break the
        // wrapper's own quoting and mangle the user's command.
        assertTrue(w.contains("""echo '\''hi'\''"""))
    }

    @Test
    fun `no setsid means no wrapper, not a broken command`() {
        // A guest without setsid still has to run commands. Losing the
        // targeted kill is survivable; losing execution is not.
        val w = ForegroundCommandGroup.wrap("echo hi", marker, setsidAvailable = false)
        assertFalse(w.contains(ForegroundCommandGroup.SETSID))
        assertTrue(w.startsWith("echo hi"))
        assertTrue(w.contains("__MINIS_DONE_${marker}_EXIT_"))
    }

    // ── Parsing the group id ───────────────────────────────────────────────

    @Test
    fun `the reported pgid is parsed`() {
        assertEquals(24027, ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_24027__\n", marker))
    }

    @Test
    fun `a pgid embedded in surrounding output is still found`() {
        val chunk = "noise\n__MINIS_PGID_${marker}_991__\nmore output\n"
        assertEquals(991, ForegroundCommandGroup.parsePgid(chunk, marker))
    }

    @Test
    fun `an unknown pgid is null, so nothing is killed`() {
        // "Don't know" must never become "kill something else".
        assertNull(ForegroundCommandGroup.parsePgid("no marker here", marker))
        assertNull("truncated", ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_240", marker))
        assertNull("not a number", ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_abc__", marker))
        assertNull("another command's marker", ForegroundCommandGroup.parsePgid("__MINIS_PGID_other_42__", marker))
    }

    @Test
    fun `pgid 0 and 1 are refused`() {
        // kill -9 -0 targets the caller's own group; -1 is init.
        assertNull(ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_0__", marker))
        assertNull(ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_1__", marker))
        assertEquals(2, ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_2__", marker))
    }

    // ── The kill ───────────────────────────────────────────────────────────

    @Test
    fun `the kill targets the group and escalates`() {
        val k = ForegroundCommandGroup.killGroupCommand(4242)
        assertTrue("negative pid targets the group", k.contains("-4242"))
        assertTrue("TERM first", k.contains("kill -TERM -4242"))
        assertTrue("then KILL for anything that ignored it", k.contains("kill -KILL -4242"))
        assertTrue(
            "TERM must precede KILL",
            k.indexOf("kill -TERM") < k.indexOf("kill -KILL"),
        )
    }

    @Test
    fun `the kill detaches so it survives the shell it is killing`() {
        // The shell asked to run this is the one whose foreground is wedged.
        val k = ForegroundCommandGroup.killGroupCommand(7)
        assertTrue("must detach", k.contains(ForegroundCommandGroup.SETSID))
        assertTrue("and not block the shell", k.trimEnd().endsWith("&"))
    }

    // ── Recycling ──────────────────────────────────────────────────────────

    @Test
    fun `a shell is never reused after a timeout`() {
        // Even a successful kill leaves bytes the runaway wrote before dying
        // in the shell's stdout; those would be attributed to the next command.
        assertFalse(ForegroundCommandGroup.shellIsReusableAfterTimeout())
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private val shellSrc by lazy {
        val f = File("src/main/java/com/openminis/app/sandbox/PersistentShell.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    @Test
    fun `the do-nothing timeout branch is gone`() {
        assertFalse(
            "the old 'don't kill the shell' comment must not survive — it is the bug",
            shellSrc.contains("Timeout — cancel pending, but don't kill the shell"),
        )
    }

    @Test
    fun `timeout kills the group and recycles the shell`() {
        val body = shellSrc.substringAfter("if (result == null) {").substringBefore("} else {")
        assertTrue("must kill the group", body.contains("killGroupCommand(pgid)"))
        assertTrue("must recycle the shell", body.contains("stop()"))
        assertTrue(
            "must keep whatever the command produced before the deadline",
            body.contains("cb?.output?.toString()"),
        )
        assertTrue("must still report 124", body.contains("124"))
    }

    @Test
    fun `a command with no known pgid is not killed blindly`() {
        val body = shellSrc.substringAfter("if (result == null) {").substringBefore("} else {")
        assertTrue(
            "the kill must be guarded on a known pgid",
            body.contains("cb?.pgid?.let"),
        )
    }

    @Test
    fun `executeCommand wraps through the policy, not by hand`() {
        assertTrue(
            shellSrc.contains("ForegroundCommandGroup.wrap(command, marker, setsidAvailable)"),
        )
    }

    @Test
    fun `the reader carries a tail so a split marker is still matched`() {
        assertTrue("carry-over buffer exists", shellSrc.contains("private var carryOver: String"))
        assertTrue(
            "the scan must span the carry-over plus the new chunk",
            shellSrc.contains("val scan = if (carryOver.isEmpty()) text else carryOver + text"),
        )
        assertTrue("and a hold-back window", shellSrc.contains("MARKER_CARRY"))
    }

    @Test
    fun `our control markers never reach the caller`() {
        assertTrue(
            "the pgid line must be stripped from visible output",
            shellSrc.contains("stripControlMarkers("),
        )
    }
}
