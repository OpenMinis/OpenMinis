package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [T-android-parity-fixes] Shell-tool fixes from the iOS/Android parity review
 * of the scheduled prefilled-command work: the exit status is reported once,
 * the timeout ceiling is stated rather than silent, and a command reading
 * stdin gets EOF instead of hanging until the timeout.
 */
class ShellParityFixesTest {

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    // ── exit code: once, in one format ──────────────────────────────────────

    @Test
    fun `the exit status is appended once, as (exit code N)`() {
        // Coordinator layer, then chat layer — the path every failure takes.
        val fromCoordinator = ShellExitCode.ensureSuffix("boom", 1)
        assertEquals("boom\n(exit code: 1)", fromCoordinator)
        val seenByModel = ShellExitCode.ensureSuffix(fromCoordinator, 1)
        assertEquals("boom\n(exit code: 1)", seenByModel)
        assertEquals(1, Regex("""exit code""").findAll(seenByModel).count())
    }

    @Test
    fun `a timeout, which the coordinator leaves unannotated, is still labelled once`() {
        val out = ShellExitCode.ensureSuffix("partial\n[Command timed out after 5s]", 124)
        assertEquals("partial\n[Command timed out after 5s]\n(exit code: 124)", out)
        assertEquals(out, ShellExitCode.ensureSuffix(out, 124))
    }

    @Test
    fun `trailing whitespace does not defeat the duplicate check`() {
        assertEquals("x\n(exit code: 2)\n", ShellExitCode.ensureSuffix("x\n(exit code: 2)\n", 2))
    }

    @Test
    fun `neither layer writes the old second format`() {
        assertFalse(vm.contains("\" (exit code \${result.exitCode})\""))
        assertTrue(vm.contains("ShellExitCode.ensureSuffix(output, result.exitCode)"))
        val coord = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        assertTrue(coord.contains("ShellExitCode.ensureSuffix(truncated, exitCode)"))
        assertFalse(coord.contains("\\n(exit code: \$exitCode)"))
    }

    // ── timeout ceiling ─────────────────────────────────────────────────────

    @Test
    fun `the timeout ceiling is one hour and the schema says so`() {
        assertTrue(vm.contains("args.optInt(\"timeout\", 900).coerceIn(1, SHELL_TIMEOUT_MAX_SEC)"))
        assertTrue(vm.contains("const val SHELL_TIMEOUT_MAX_SEC = 3600"))
        assertFalse("no silent 900 s cap left", vm.contains("coerceIn(1, 900)"))
        val tools = ProductionSources.read("tools/AgentTools.kt")
        assertTrue(tools.contains("Timeout in seconds (default: 900, maximum: 3600 — larger values are capped at 3600)."))
    }

    // ── stdin reads /dev/null ───────────────────────────────────────────────

    private fun run(vararg cmd: String): Pair<String, Int> {
        val pb = FreshProcessShell.configureStdin(ProcessBuilder(*cmd)).redirectErrorStream(true)
        val p = pb.start()
        val finished = p.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            throw AssertionError("${cmd.toList()} was still waiting on stdin after 5 s")
        }
        return p.inputStream.bufferedReader().readText() to p.exitValue()
    }

    @Test
    fun `a command that reads stdin gets EOF at once instead of hanging`() {
        // With ProcessBuilder's default stdin (an open pipe nobody writes to)
        // each of these blocks until the tool's timeout.
        assertEquals("" to 0, run("cat"))
        val (out, _) = run("/bin/sh", "-c", "read x; echo \"got:[\$x]\"")
        assertEquals("got:[]\n", out)
    }

    @Test
    fun `redirecting stdin leaves stdout, stderr and the exit status alone`() {
        val (out, code) = run("/bin/sh", "-c", "echo out; echo err 1>&2; exit 3")
        assertTrue(out.contains("out") && out.contains("err"))
        assertEquals(3, code)
    }

    @Test
    fun `the fresh-process spawn uses that stdio setup`() {
        val fresh = ProductionSources.read("sandbox/FreshProcessShell.kt")
        assertTrue(fresh.contains("val pb = ProcessBuilder(cmd)\n        configureStdin(pb)"))
        assertTrue(fresh.contains("pb.redirectInput(ProcessBuilder.Redirect.from(File(\"/dev/null\")))"))
    }
}
