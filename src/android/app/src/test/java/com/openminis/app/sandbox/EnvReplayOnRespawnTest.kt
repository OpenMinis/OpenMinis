package com.openminis.app.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-env-first-turn] Guards the two source-level invariants behind the
 * "first tool call has no environment variables" report.
 *
 * The real failure needs a cold-start shell death under proot + SELinux, which
 * a JVM unit test cannot stage. What it CAN do is pin the two properties whose
 * absence caused it, both of which were silent regressions waiting to happen:
 *
 *  1. PRootKernel.boot() must serialize. It sets `isBooted` only at the end,
 *     so a bare check-then-act let a second caller spawn a shell while
 *     PROOT_LOADER was still unset — proot then bare-execve'd busybox and
 *     SELinux killed it.
 *  2. applyEnvironment() must record its snapshot BEFORE the liveness check,
 *     and ensureStarted() must replay it. Otherwise exports written to a shell
 *     that is already dead are lost, and the respawn that actually runs the
 *     command starts bare.
 *
 * Asserting on source text is deliberate: these are ordering properties of code
 * that needs a device to execute, and a comment alone would not fail the build
 * when someone reinstates `if (!isAlive) return` at the top.
 */
class EnvReplayOnRespawnTest {

    private fun read(rel: String): String {
        val f = File("src/main/java/com/openminis/app/sandbox/$rel")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `boot is serialized by a mutex, not a bare isBooted check`() {
        val src = read("PRootKernel.kt")
        assertTrue(
            "PRootKernel.boot() must hold bootMutex — a bare isBooted check is a " +
                "check-then-act race, because isBooted flips only after the slow " +
                "install work finishes.",
            src.contains("bootMutex.withLock"),
        )
        assertTrue(
            "boot() must re-check isBooted once it holds the lock, or the caller " +
                "queued behind a completed boot will redo it.",
            src.contains("Already booted (post-lock)"),
        )
    }

    @Test
    fun `applyEnvironment records the snapshot before the liveness check`() {
        val src = read("PersistentShell.kt")
        // Strip comments first: the method's own KDoc QUOTES the old
        // `if (!isAlive) return` line while explaining the bug, and a naive
        // search finds that prose before it finds the real statement.
        val body = src.substringAfter("suspend fun applyEnvironment(")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        val recordAt = body.indexOf("pendingEnv = envVars")
        val bailAt = body.indexOf("if (!isAlive) return")
        assertTrue("applyEnvironment must assign pendingEnv", recordAt >= 0)
        assertTrue("applyEnvironment must still bail out when dead", bailAt >= 0)
        assertTrue(
            "pendingEnv must be recorded BEFORE the !isAlive bail-out. If the " +
                "bail-out comes first, exports aimed at a dead shell are dropped " +
                "with no record, and the respawn runs the command bare — the " +
                "original first-turn bug.",
            recordAt < bailAt,
        )
    }

    @Test
    fun `ensureStarted replays the remembered environment`() {
        val src = read("PersistentShell.kt")
        assertTrue(
            "ensureStarted() must call replayEnvironmentIfAny(): a freshly spawned " +
                "/bin/sh has an empty environment, so without a replay the exports " +
                "written to the previous incarnation are gone.",
            src.contains("replayEnvironmentIfAny()"),
        )
        assertTrue(
            "the replay must re-export the snapshot",
            src.contains("writeEnvironment(pendingEnv"),
        )
    }
}
