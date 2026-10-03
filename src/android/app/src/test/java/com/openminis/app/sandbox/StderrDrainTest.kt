package com.openminis.app.sandbox

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fresh-stderr-drain] Debug builds keep a fresh-process command's
 * stderr on its own pipe; nothing read it, so any command writing more than a
 * pipe buffer (64 KB) of stderr — wget's progress meter on a large download —
 * blocked in write() forever.
 */
class StderrDrainTest {

    /** A real child process writing [bytes] of stderr, stderr NOT merged. */
    private fun chattyStderr(bytes: Int): Process =
        ProcessBuilder("sh", "-c", "head -c $bytes /dev/zero | tr '\\0' 'x' | fold -w 99 >&2; echo done")
            .redirectErrorStream(false)
            .start()

    @Test
    fun `a command writing far more than a pipe buffer to stderr completes when drained`() {
        val p = chattyStderr(300_000)
        val drain = StderrDrain(p.errorStream) {}.start()
        val stdout = p.inputStream.bufferedReader().readText()
        assertTrue("must exit, not block in write()", p.waitFor(10, TimeUnit.SECONDS))
        val err = drain.finish(2_000)
        assertEquals("done\n", stdout)
        assertEquals("all stderr is kept", 300_000, err.count { it == 'x' })
    }

    @Test
    fun `without a drain the same command blocks (the bug)`() {
        val p = chattyStderr(300_000)
        try {
            // Read stdout only, the way FreshProcessShell used to.
            val exited = p.waitFor(3, TimeUnit.SECONDS)
            assertFalse("undrained stderr must wedge the writer", exited)
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun `proot debug lines go to the log, everything else to the output`() {
        val logged = mutableListOf<String>()
        val input = "[native_offload] handler registered\nwget: unable to resolve host\n[native_offload] call done\nreal error\n"
        val kept = StderrDrain(input.byteInputStream(), logged::add).start().finish(2_000)
        assertEquals(listOf("[native_offload] handler registered", "[native_offload] call done"), logged)
        assertEquals("wget: unable to resolve host\nreal error\n", kept)
        assertTrue(StderrDrain.isProotDebugLine("[native_offload] x"))
        assertTrue(StderrDrain.isProotDebugLine("[fake_netlink] initialized (rtnetlink -> loopback only; SO_MARK -> no-op)"))
        assertFalse("only the exact prefix", StderrDrain.isProotDebugLine("native_offload x"))
    }

    @Test
    fun `finish never waits past its bound on a writer that stays open`() {
        val p = ProcessBuilder("sh", "-c", "echo early >&2; sleep 30").redirectErrorStream(false).start()
        try {
            val drain = StderrDrain(p.errorStream) {}.start()
            val t0 = System.nanoTime()
            val kept = drain.finish(500)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("bounded wait, took ${ms}ms", ms < 3_000)
            assertEquals("early\n", kept)
        } finally {
            p.destroyForcibly()
        }
    }

    // -- Wiring --------------------------------------------------------------------

    private val shell by lazy {
        File("src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt").readText()
    }

    @Test
    fun `the fresh shell drains stderr whenever it keeps stderr separate`() {
        assertTrue(shell.contains("pb.redirectErrorStream(!SEPARATE_STDERR)"))
        assertTrue(shell.contains("val stderrDrain = if (SEPARATE_STDERR) {"))
        assertTrue(shell.contains("StderrDrain(process.errorStream)"))
        assertTrue("kept stderr reaches the output", shell.contains("stderrDrain?.finish(stderrWait)"))
        // [T-android-fresh-exit-status-file] The full wait still applies
        // whenever proot exited; only a proot left tracing a daemon (whose
        // stderr never reaches EOF) gets the short settle.
        assertTrue(shell.contains("if (closedOnStatus.get()) STDERR_SETTLE_MS else STDERR_DRAIN_WAIT_MS"))
    }

    @Test
    fun `timeout and Stop close stderr too, so the drain cannot outlive them`() {
        // [T-android-fresh-exit-status-file] Both now go through killCommand,
        // which closes it once for either.
        val kill = shell.substringAfter("private fun killCommand(").substringBefore("\n    /**")
        assertTrue(kill.contains("process.errorStream.close()"))
        assertTrue("timeout", shell.contains("killCommand(process, readEof)"))
        assertTrue("Stop", shell.contains("killCommand(p, readEof = null)"))
    }
}
