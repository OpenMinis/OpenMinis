package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fresh-exit-status-file] The fresh-process runner reports the
 * command's exit out of band, so completion no longer depends on the output
 * pipe reaching EOF.
 *
 * Reported on a Pixel 4a: `minis-mcp-cli tools amap-maps` printed its result,
 * yet the shell tool stayed "running" for minutes. On device: proot had no
 * children left, and the daemon the CLI detached (plus the amap MCP server it
 * started) had TracerPid = that proot. proot must keep tracing them for their
 * paths to work, and proot's own fd 1 was the only writer of the host pipe —
 * so EOF could never arrive, and only the timeout ended the call.
 *
 * These tests run the real [ForegroundCommandGroup.FRESH_RUNNER] under the
 * host's /bin/sh (no proot, no setsid — neither changes what the script does
 * with its arguments or files).
 */
class FreshRunnerScriptTest {

    private val dir: File = Files.createTempDirectory("fresh-runner").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun start(command: String, status: File): Process =
        ProcessBuilder("/bin/sh", "-c", ForegroundCommandGroup.FRESH_RUNNER, "sh", command, status.path)
            .redirectErrorStream(true)
            .start()

    private fun run(command: String): Triple<String, Int, File> {
        val status = File(dir, "s")
        val p = start(command, status)
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return Triple(out, p.exitValue(), status)
    }

    @Test
    fun `the command's exit code is written and returned`() {
        val (out, code, status) = run("echo hi; exit 3")
        assertEquals("hi\n", out)
        assertEquals(3, code)
        assertEquals("3", status.readText())
    }

    @Test
    fun `a successful command reports 0`() {
        val (_, code, status) = run("true")
        assertEquals(0, code)
        assertEquals("0", status.readText())
    }

    @Test
    fun `exit inside the command still lets the runner write the status`() {
        // Why the command runs in a child shell: a bare `exit` would otherwise
        // end the runner before it could report anything.
        val (_, _, status) = run("exit 7; echo unreachable")
        assertEquals("7", status.readText())
    }

    @Test
    fun `a command killed by a signal reports 128 plus the signal`() {
        val (_, _, status) = run("kill -9 \$\$")
        assertEquals("137", status.readText())
    }

    @Test
    fun `the runner records its own pid for the group kill`() {
        val status = File(dir, "s")
        val p = start("true", status)
        p.inputStream.readBytes()
        p.waitFor(10, TimeUnit.SECONDS)
        val pid = File(status.path + ".pid").readText().trim().toIntOrNull()
        assertNotNull("pid file must hold a number", pid)
        assertTrue(pid!! > 1)
    }

    @Test
    fun `the command is passed through literally`() {
        val (out, _, _) = run("printf '%s|' 'a&b' \"x y\" '\$HOME'")
        assertEquals("a&b|x y|\$HOME|", out)
    }

    @Test
    fun `the status is written while a leftover process still holds stdout open`() {
        // The shape of the MCP hang: the command is finished, something it
        // left behind keeps the output pipe open, so EOF is far away. The
        // status file must not wait for it.
        val status = File(dir, "s")
        val p = start("(sleep 3; echo late) & echo done", status)
        val deadline = System.currentTimeMillis() + 1_500
        while (!status.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("status must appear long before the pipe closes", status.exists())
        assertEquals("0", status.readText())
        val firstLine = p.inputStream.bufferedReader().readLine()
        assertEquals("done", firstLine)
        p.destroyForcibly()
    }

    // ── When the host may stop reading ────────────────────────────────────

    @Test
    fun `the stream is closed only once output has gone quiet after the status`() {
        val seen = 10_000L
        assertFalse(
            "not while output is still arriving",
            FreshProcessShell.outputSettled(now = seen + 100, statusSeenAt = seen, lastReadAt = seen + 90),
        )
        assertFalse(
            "not before the quiet window has passed since the status",
            FreshProcessShell.outputSettled(now = seen + 50, statusSeenAt = seen, lastReadAt = seen - 5_000),
        )
        assertTrue(
            FreshProcessShell.outputSettled(
                now = seen + FreshProcessShell.OUTPUT_QUIET_MS,
                statusSeenAt = seen,
                lastReadAt = seen,
            ),
        )
        assertTrue(
            "a detached process that keeps writing cannot hold the call past the cap",
            FreshProcessShell.outputSettled(
                now = seen + FreshProcessShell.OUTPUT_DRAIN_CAP_MS,
                statusSeenAt = seen,
                lastReadAt = seen + FreshProcessShell.OUTPUT_DRAIN_CAP_MS,
            ),
        )
    }
}
