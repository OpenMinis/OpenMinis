package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-shell-fresh-process] Migration off in-band completion markers.
 *
 * Completion has always been detected by appending
 * `echo "__MINIS_DONE_<uuid>_EXIT_$?__"` to every command and scanning stdout
 * for it. That was forced by the warm-shell design: with a REUSED shell,
 * `Process.waitFor()` waits for the shell rather than the command, stdout has
 * no delimiters, and `$?` lives only in the guest.
 *
 * In-band signalling then failed three times in five months — a marker split
 * across two reads (command hung to its timeout), the hold-back buffer added
 * to fix that (every short output swallowed, measured on a Pixel 6), and a
 * timed-out command's bytes landing in the next command's result (#358). The
 * defect is structural: control and data share a channel the command can
 * write to.
 *
 * Measured on a Pixel 6 inside this project's PRoot Alpine guest, 20 runs:
 *
 *     sh -c "echo hi"          4.2 ms
 *     setsid sh -c "echo hi"   8.5 ms
 *     FIFO control round-trip  24.3 ms
 *
 * against 65-90 ms of per-tool-call host overhead. The cold-start objection
 * that motivated warm shells does not survive measurement.
 */
class ShellExecutionStrategyTest {

    // ── Step 1: the switch exists and changes nothing yet ──────────────────

    @Test
    fun `the fresh process path is what users now get`() {
        // Step 5. Steps 1-4 were deliberately invisible; this is the flip.
        assertEquals(ShellExecutionStrategy.FRESH_PROCESS, ShellExecutionStrategy.DEFAULT)
    }

    @Test
    fun `the warm shell survives as a rollback path`() {
        // Deleting it in the same change that adds the replacement would leave
        // no way back if the new path misbehaves on a device we cannot test.
        assertTrue(ShellExecutionStrategy.entries.contains(ShellExecutionStrategy.WARM_SHELL))
        assertTrue(ShellExecutionStrategy.entries.contains(ShellExecutionStrategy.FRESH_PROCESS))
    }

    // ── The properties that motivate the migration ─────────────────────────

    @Test
    fun `only the warm shell needs an in-band marker`() {
        // The marker exists solely because a reused shell cannot signal a
        // command boundary. A fresh process gets the code from waitFor(), so
        // appending one would be pure added risk.
        assertTrue(ShellExecutionStrategy.needsInBandMarker(ShellExecutionStrategy.WARM_SHELL))
        assertFalse(ShellExecutionStrategy.needsInBandMarker(ShellExecutionStrategy.FRESH_PROCESS))
    }

    @Test
    fun `only the warm shell must be recycled after a timeout`() {
        // A runaway keeps writing into a stdout that later commands read, so
        // even a successful kill leaves bytes that would be misattributed —
        // the #358 mechanism. A fresh process owns its pipes; they die with it.
        assertTrue(ShellExecutionStrategy.recyclesShellOnTimeout(ShellExecutionStrategy.WARM_SHELL))
        assertFalse(ShellExecutionStrategy.recyclesShellOnTimeout(ShellExecutionStrategy.FRESH_PROCESS))
    }

    // ── Step 2: the fresh-process executor ─────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val freshSrc by lazy { src("src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt") }

    @Test
    fun `the fresh path never emits a completion marker`() {
        // The entire point. If a marker appears here, every failure mode it
        // brings comes back with it.
        // Strip comments: the file DOCUMENTS what it removed, and that prose
        // must not read as the thing still being there.
        val code = freshSrc.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        assertFalse("must not append __MINIS_DONE__", code.contains("__MINIS_DONE_"))
        assertFalse("must not carry bytes between reads", code.contains("carryOver"))
    }

    @Test
    fun `completion comes from the process, not from parsing`() {
        // Process exit is the completion signal — not a marker echoed into the
        // output stream. The wait itself is bounded rather than a bare
        // `waitFor()`; see the detached-daemon tests below for why.
        assertTrue("exit status is the completion signal", freshSrc.contains("process.waitFor("))
        assertTrue("and reads run to EOF", freshSrc.contains("if (n < 0) break"))
    }

    @Test
    fun `the command is passed as an argument, never interpolated`() {
        // argv entries are not parsed by a shell, so quotes, `&`, newlines and
        // URLs in the command cannot change its structure — the injection
        // surface the wrapper approach had to defend with escaping.
        // Step 4 moved the argv tail into ForegroundCommandGroup, so assert the
        // property where it now lives rather than on the old literal.
        val wrap = src("src/main/java/com/openminis/app/sandbox/ForegroundCommandGroup.kt")
            .substringAfter("fun wrapForFreshProcess(")
            .substringBefore("\n\n")
        // [T-android-fresh-exit-status-file] The runner script is the -c
        // string; the command and the status path follow as $1 / $2.
        assertTrue("command must be its own argv entry", wrap.contains(""""sh", command, statusPath"""))
        assertFalse("and never spliced into a string", wrap.contains("\$" + "command"))
    }

    @Test
    fun `a timeout kills the process and keeps partial output`() {
        val body = freshSrc.substringAfter("if (collected == null)").substringBefore("Pair(output.toString()")
        assertTrue("must kill", body.contains("destroyForcibly()"))
        assertTrue("must report 124", body.contains("124"))
        assertTrue("must keep what the command produced", body.contains("partial"))
    }

    @Test
    fun `the sandbox is configured identically to the warm path`() {
        // Any divergence makes the two paths incomparable in step 3 and would
        // surface as a behaviour change at step 4.
        for (flag in listOf("\"-0\"", "\"--link2symlink\"", "--fake-netlink", "--native-offload=")) {
            assertTrue("fresh path must pass $flag", freshSrc.contains(flag))
        }
        for (envKey in listOf("PROOT_LOADER", "PROOT_TMP_DIR", "LD_LIBRARY_PATH", "MINIS_CHAT_SESSION_ID")) {
            assertTrue("fresh path must set $envKey", freshSrc.contains(envKey))
        }
    }

    @Test
    fun `user env vars are real environment entries, not exports on a shared stdin`() {
        assertTrue(freshSrc.contains("for ((k, v) in envVars) env[k] = v"))
    }

    // ── Step 4: killing the whole guest tree on timeout ────────────────────

    @Test
    fun `the fresh path runs its command as a process-group leader`() {
        // destroyForcibly() reaches the proot process, NOT the guest
        // descendants proot traces. Measured on a Pixel 6: proot died on
        // schedule at 5s while a libproot.so child survived holding the pipe's
        // write end, so the blocking read never saw EOF and the call outlived
        // its deadline. setsid gives the command its own group so the kill can
        // reach the whole tree.
        assertTrue(freshSrc.contains("ForegroundCommandGroup.wrapForFreshProcess(command, statusGuestPath)"))
        // The declaration's body is on the following line, so read past the
        // signature rather than stopping at the first newline.
        val wrap = src("src/main/java/com/openminis/app/sandbox/ForegroundCommandGroup.kt")
            .substringAfter("fun wrapForFreshProcess(")
            .substringBefore("\n\n")
        assertTrue("must go through setsid", wrap.contains("listOf(SETSID"))
    }

    @Test
    fun `a timeout kills the command's group, not the proot a daemon depends on`() {
        // [T-android-fresh-exit-status-file] proot may also be tracing a daemon
        // an earlier command detached; killing proot leaves that daemon running
        // untraced, and every later MCP call fails against it.
        val watchdog = freshSrc.substringAfter("val watchdog =").substringBefore("val exitCode =")
        assertTrue(watchdog.contains("killCommand(process, readEof)"))
        val kill = freshSrc.substringAfter("private fun killCommand(").substringBefore("\n    /**")
        assertTrue("the group comes from the runner's pid file", kill.contains("readPgid("))
        assertTrue(
            "a negative pid needs Os.kill: Process.sendSignal drops pid <= 0",
            kill.contains("android.system.Os.kill(-pgid"),
        )
        val known = kill.substringAfter("} else {").substringBefore("// Belt and braces")
        assertFalse("with the group known, proot is not destroyed", known.contains("destroyForcibly"))
        assertTrue(
            "and the stream is closed so a blocked read cannot outlive the deadline",
            kill.contains("process.inputStream.close()"),
        )
    }

    @Test
    fun `the pid is read in a way API 26 actually supports`() {
        // Process.pid() is Java 9+ and is NOT on Android's API 26 baseline —
        // using it compiled locally but broke the build for the device.
        val body = freshSrc.substringAfter("private fun killProcessTree").substringBefore("\n    }")
        assertFalse("must not call the Java 9 API", body.contains("process.pid()"))
        assertTrue("reads the platform field instead", body.contains("""getDeclaredField("pid")"""))
        assertTrue("and degrades rather than throwing", body.contains("?: return"))
    }

    @Test
    fun `the descendant walk is depth-bounded`() {
        // An unbounded recursion over /proc during a timeout would be a worse
        // failure than missing a great-great-grandchild.
        assertTrue(freshSrc.contains("MAX_TREE_DEPTH"))
        assertTrue(freshSrc.contains("if (depth > MAX_TREE_DEPTH) return emptyList()"))
    }

    // ── Step 3: the coordinator routes through the strategy ────────────────

    private val coordSrc by lazy { src("src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt") }

    @Test
    fun `execute dispatches on the strategy before taking a lease`() {
        // Taking a pool lease first would serialise fresh-process commands
        // behind each other for no reason — they cannot collide.
        val body = coordSrc.substringAfter("val lease = acquireShellLease")
        assertTrue("the branch must exist", coordSrc.contains("executionStrategy == ShellExecutionStrategy.FRESH_PROCESS"))
        val branchIdx = coordSrc.indexOf("executionStrategy == ShellExecutionStrategy.FRESH_PROCESS")
        val leaseIdx = coordSrc.indexOf("val lease = acquireShellLease")
        assertTrue("dispatch must come before the lease", branchIdx in 1 until leaseIdx)
        assertTrue(body.isNotEmpty())
    }

    @Test
    fun `the fresh path reuses the same bind mounts and env source`() {
        val body = coordSrc.substringAfter("private suspend fun executeFresh").substringBefore("\n    }")
        assertTrue("same mounts", body.contains("buildSessionBindMounts("))
        assertTrue("same env repository", body.contains("envVarRepository?.allAsDict()"))
        assertTrue("same sanitising", body.contains("TerminalSanitizer.sanitize("))
        assertTrue("same truncation", body.contains("TerminalSanitizer.truncateIfNeeded("))
    }

    @Test
    fun `the strategy is switchable at runtime for the comparison harness`() {
        assertTrue(coordSrc.contains("var executionStrategy: ShellExecutionStrategy"))
        assertTrue("must be safe to flip from another thread", coordSrc.contains("@Volatile"))
    }

    // ── Step 5: the flip, and the Stop button that had to come with it ─────

    @Test
    fun `a fresh command is reachable by the Stop button`() {
        // The defect this prevents: executeFresh() owns its shell in a local
        // variable, so unlike a pooled PersistentShell there was nothing for
        // stopCurrentCommand() to kill. Flipping the default without this
        // would have left Stop silently inert for every shell command.
        assertTrue("must be registered before running", coordSrc.contains("freshShells.compute(sessionId)"))
        assertTrue("and stopped when the user asks", coordSrc.contains("stopFreshShells(sessionId)"))
        assertTrue("including the stop-everything path", coordSrc.contains("ArrayList(freshShells.keys).forEach")) // [T-android-fresh-shells-concurrent]
    }

    @Test
    fun `the registry cannot leak an entry when a command fails`() {
        val body = coordSrc.substringAfter("private suspend fun executeFresh").substringBefore("\n    }")
        assertTrue("removal must be in a finally", body.contains("} finally {"))
        assertTrue(body.contains("freshShells.computeIfPresent(sessionId)"))
    }

    @Test
    fun `Stop kills the whole tree, the same way a timeout does`() {
        // Same underlying failure: SIGKILL to the proot process alone leaves
        // traced guest descendants running and holding the pipe.
        val body = freshSrc.substringAfter("    fun stop() {").substringBefore("\n    }")
        assertTrue(body.contains("killCommand(p, readEof = null)"))
        assertFalse("proot alive says nothing about the command", body.contains("if (!p.isAlive)"))
    }

    @Test
    fun `a Stop racing a starting command still takes effect`() {
        // stop() can land between spawn() and the first read. Checking the
        // flag right after the spawn closes that window.
        assertTrue(freshSrc.contains("if (stopped) {"))
        assertTrue("the live handle must be published for stop() to find", freshSrc.contains("live = process"))
        assertTrue("and both must be safe across threads", freshSrc.contains("@Volatile private var stopped"))
    }

    @Test
    fun `a cancellation is not reported as a timeout`() {
        // 124 means "exceeded its budget" to the agent loop, which retries it
        // differently from work the user deliberately stopped.
        assertTrue(freshSrc.contains("const val CANCELLED_EXIT = 130"))
        // The cancellation branch is the one guarded by `stopped` that sits
        // immediately before the timeout branch. Strip comments first: the
        // branch DOCUMENTS that it must not return 124, and that prose must
        // not read as the code doing it.
        val body = freshSrc.substringBefore("if (timedOut.get())")
            .substringAfterLast("if (stopped) {")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue("cancellation returns its own code", body.contains("CANCELLED_EXIT"))
        assertFalse("and never 124", body.contains("124"))
    }

    // ── Detached daemons: proot outlives the command ──────────────────────

    @Test
    fun `the process is never awaited without a bound`() {
        // [T-android-mcp-detached-daemon-hang] `minis-mcp-cli` forks a daemon
        // (setsid, own session) so later calls reuse warm MCP connections.
        // proot stays ptrace-attached to it, and a tracer cannot exit while
        // its tracee lives — so a bare `waitFor()` never returns even though
        // the command already printed its whole result. Measured on a Pixel 6
        // / Android 17: `minis-mcp-cli list` wrote its full output and the
        // shell call was still running 90s later.
        //
        // Every wait must therefore carry a timeout. Strip comments before
        // scanning: the fix DOCUMENTS the bare call it replaced, and that
        // prose must not read as the code still making it.
        val code = freshSrc.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        val bare = Regex("""waitFor\(\s*\)""").findAll(code).count()
        assertEquals("no unbounded waitFor() may remain", 0, bare)
        assertTrue(
            "the bounded wait must be the shared helper",
            freshSrc.contains("private fun resolveExitCode("),
        )
        assertTrue(
            "and it must pass a real deadline",
            freshSrc.contains("process.waitFor(REAP_GRACE_MS"),
        )
    }

    @Test
    fun `a finished command leaves a daemon's tracer running`() {
        // [T-android-fresh-exit-status-file] Reversed from 1ccd35c60, which
        // killed a lingering proot. That proot is the tracer of the daemon the
        // command detached; killing it broke the daemon. Completion now comes
        // from the runner's status file, and a still-running proot is left
        // alone — neither waited on nor killed.
        val body = freshSrc.substringAfter("private fun resolveExitCode(").substringBefore("\n    /**")
        assertTrue(body.contains("if (closedOnStatus) return readStatus(statusFile)"))
        assertFalse(body.contains("killProcessTree"))
        assertFalse(body.contains("destroyForcibly"))
    }

    @Test
    fun `the group kill does not depend on reading proc`() {
        // Android 17 mounts /proc hidepid=invisible (it was hidepid=2 on
        // Android 13), so an app cannot enumerate even its own descendants:
        // the /proc walk returns nothing and the sweep silently does nothing.
        // Verified on a Pixel 6 — logcat said "process tree killed" while
        // every guest process was still running 6 minutes later. The negative
        // -pgid signal is the only part that still works there, so it must be
        // issued unconditionally, not as a fallback after the walk.
        val body = freshSrc.substringAfter("private fun killProcessTree").substringBefore("\n    /**")
        val groupKill = body.indexOf("sendSignal(-pid, 9)")
        val walk = body.indexOf("descendantsOf(pid)")
        assertTrue("the group kill must be present", groupKill >= 0)
        assertTrue("the walk must be present", walk >= 0)
        assertTrue("the group kill must come first", groupKill < walk)
    }
}
