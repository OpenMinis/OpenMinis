package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fake-netlink-tty-parity] The two ways into the sandbox must build
 * the same proot command.
 *
 * There are two builders, and they are easy to edit in isolation:
 *   - `PRootKernel.buildProotCommand` — the agent's shell_execute / offload
 *     path, non-interactive, plain pipes.
 *   - `TerminalSession.buildInteractiveCommand` — the Terminal screen, a real
 *     PTY via PtyBridge.
 *
 * c9318f42f added `--fake-netlink` to the first one only. The sandbox then
 * behaved differently depending on which screen the command was launched
 * from: a tool run by the agent got proot's emulated (empty) rtnetlink reply,
 * while the same tool typed into the Terminal hit the real AF_NETLINK refusal
 * from Android's untrusted_app SELinux domain. That asymmetry is invisible
 * from either screen alone and makes any field report ambiguous — the first
 * question becomes "which shell did you use?".
 *
 * These are source-fact checks: both builders assemble an argv for a native
 * binary that cannot run in a JVM test, so what is verifiable here is that the
 * isolation-affecting flags stay in step.
 */
class ProotFlagParityTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val kernelSrc by lazy { src("src/main/java/com/openminis/app/sandbox/PRootKernel.kt") }
    private val terminalSrc by lazy { src("src/main/java/com/openminis/app/sandbox/TerminalSession.kt") }

    /** The body of one builder, so a flag mentioned elsewhere in the file does not count. */
    private fun builderBody(src: String, marker: String, end: String): String {
        val after = src.substringAfter(marker)
        assertTrue("could not locate $marker", after.isNotEmpty() && after != src)
        return after.substringBefore(end)
    }

    private val kernelBuilder by lazy {
        builderBody(kernelSrc, "fun buildProotCommand", "\n    fun ")
    }

    private val terminalBuilder by lazy {
        builderBody(terminalSrc, "private fun buildInteractiveCommand", "\n}")
    }

    @Test
    fun `both builders emulate rtnetlink`() {
        // The regression: one had it, the other did not, and `ssh` behaved
        // differently in the Terminal than under the agent.
        assertTrue(
            "PRootKernel.buildProotCommand must pass --fake-netlink",
            kernelBuilder.contains("""cmd.add("--fake-netlink")"""),
        )
        assertTrue(
            "TerminalSession.buildInteractiveCommand must pass --fake-netlink too, " +
                "or the Terminal and the agent disagree about whether the sandbox " +
                "has network interfaces",
            terminalBuilder.contains("""cmd.add("--fake-netlink")"""),
        )
    }

    @Test
    fun `the isolation flags match across both builders`() {
        // Beyond netlink: root emulation and the hardlink translation change
        // what guest programs can do at all. `apk add binutils` needs
        // --link2symlink; -0 is what makes the guest believe it is root.
        for (flag in listOf("\"-0\"", "\"--link2symlink\"")) {
            assertTrue("PRootKernel must pass $flag", kernelBuilder.contains("cmd.add($flag)"))
            assertTrue("TerminalSession must pass $flag", terminalBuilder.contains("cmd.add($flag)"))
        }
    }

    @Test
    fun `both builders bind the same kernel filesystems and the shared mounts`() {
        // A mount present in one and not the other means a path that resolves
        // under the agent and 404s in the Terminal (or the reverse).
        for (path in listOf("\"/dev\"", "\"/proc\"", "\"/sys\"")) {
            assertTrue("PRootKernel must bind $path", kernelBuilder.contains(path))
            assertTrue("TerminalSession must bind $path", terminalBuilder.contains(path))
        }
        // PRootKernel replays its (global-only) table. The terminal builds its
        // mounts with SessionMounts — the builder the agent's shells use — so
        // a mounted folder and a chat's workspace appear in both
        // ([T-android-session-private-mounts]; replaying the global table
        // showed whichever chat last built a shell).
        assertTrue(
            "PRootKernel must replay the user bind mounts",
            kernelBuilder.contains("in bindMounts"),
        )
        assertTrue(
            "TerminalSession must build its mounts with SessionMounts, like the agent's shells",
            terminalBuilder.contains("SessionMounts.toProotArgs(SessionMounts.forContext(context, sessionId).mounts)"),
        )
        assertTrue(
            "TerminalSession must not replay the global table",
            !terminalBuilder.contains("in PRootKernel.bindMounts)"),
        )
    }

    @Test
    fun `both builders register the same native-offload handlers`() {
        for (body in listOf(kernelBuilder, terminalBuilder)) {
            assertTrue(
                "--native-offload must be wired in both, or minis-open / offload " +
                    "tools work in one shell only",
                body.contains("--native-offload="),
            )
        }
    }

    @Test
    fun `every place that assembles a proot argv is accounted for`() {
        // Drift guard. This used to scan only two files and assert "exactly
        // two builders" — which silently missed the third one added by
        // [T-android-shell-fresh-process] (FreshProcessShell.spawn), since a
        // file the test never read could not fail it. Enumerate the known
        // builders by name instead, so a NEW one has to be added here
        // deliberately rather than slipping past.
        val known = listOf(
            "PRootKernel.kt" to "fun buildProotCommand",
            "TerminalSession.kt" to "fun buildInteractiveCommand",
            "FreshProcessShell.kt" to "fun spawn(",
        )
        for ((file, decl) in known) {
            val body = src("src/main/java/com/openminis/app/sandbox/$file")
            assertTrue("$file must still declare its builder ($decl)", body.contains(decl))
        }
        val all = kernelSrc + terminalSrc
        val builders = Regex("""fun build(Proot|Interactive)Command""").findAll(all).count()
        assertEquals("the two named builders", 2, builders)
    }

    @Test
    fun `the fresh-process builder carries the isolation flags too`() {
        // [T-android-shell-fresh-process] The third builder is subject to the
        // same rule as the other two: a sandbox that behaves differently
        // depending on which path launched the command is a debugging trap.
        val fresh = src("src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt")
        for (flag in listOf("\"-0\"", "\"--link2symlink\"", "--fake-netlink", "--native-offload=")) {
            assertTrue("FreshProcessShell must pass $flag", fresh.contains(flag))
        }
        for (path in listOf("\"/dev\"", "\"/proc\"", "\"/sys\"")) {
            assertTrue("FreshProcessShell must bind $path", fresh.contains(path))
        }
        assertTrue(
            "and replay the session bind mounts",
            fresh.contains("in sessionBindMounts"),
        )
    }
}
