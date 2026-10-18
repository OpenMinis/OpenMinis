package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-orphaned-running-tool-spin] A tool block left marked RUNNING pins
 * the whole UI at display refresh rate forever.
 *
 * Measured on a Pixel 6 with nothing running — no stream, no task, no user
 * interaction:
 *
 *   session with 144 messages open : 730 frames / 8s (91 fps), 85-130% of a core
 *   app backgrounded               : 0 frames, 0%
 *   small session open             : 3 frames / 10s, 0-8%
 *
 * Opening that one session took the process from 0% straight back to 85%+, and
 * it climbed the longer it stayed open. It held exactly one tool block still
 * marked RUNNING, from a sub-agent delegation whose completion never landed.
 * RUNNING draws a `rememberInfiniteTransition` shimmer, and an infinite
 * transition invalidates every frame while composed — so one dead block heats
 * the device indefinitely for no work.
 *
 * `delegateBlockOverrides` made it permanent: it is keyed by tool id, cleared
 * per-tool on completion, and NEVER cleared on session load, so the stale
 * RUNNING was re-stamped over the freshly rebuilt blocks on every reload. The
 * DB rebuild itself can only ever produce SUCCESS / FAILED / CANCELLED, so
 * nothing contradicted it.
 */
class OrphanedRunningToolGateTest {

    // ── the decision ───────────────────────────────────────────────────────

    @Test
    fun `an orphaned running block stops spinning`() {
        // The reported bug: job long gone, block still RUNNING.
        assertEquals(
            ToolBlockStatus.TIMEOUT,
            OrphanedRunningToolGate.resolve(ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = false),
        )
    }

    @Test
    fun `every spinner state is gated, not just RUNNING`() {
        // STREAMING and PENDING draw the same infinite animation.
        for (s in listOf(ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING)) {
            assertTrue("$s must be treated as a spinner", OrphanedRunningToolGate.isSpinner(s))
            assertEquals(
                "$s must be downgraded when orphaned",
                ToolBlockStatus.TIMEOUT,
                OrphanedRunningToolGate.resolve(s, isLiveRun = false, jobIsAlive = false),
            )
        }
    }

    @Test
    fun `a genuinely running job keeps spinning`() {
        // The whole point of the animation — must not be broken by the fix.
        assertEquals(
            ToolBlockStatus.RUNNING,
            OrphanedRunningToolGate.resolve(ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = true),
        )
    }

    @Test
    fun `a live turn is trusted even before the registry knows the job`() {
        // The block is created before the child starts, so during this VM's own
        // streaming turn there is a window with no registered job. Downgrading
        // there would kill the spinner on a run that really is starting.
        assertEquals(
            ToolBlockStatus.RUNNING,
            OrphanedRunningToolGate.resolve(ToolBlockStatus.RUNNING, isLiveRun = true, jobIsAlive = false),
        )
    }

    @Test
    fun `terminal states are historical facts and pass through untouched`() {
        // Re-deriving these could rewrite a correct SUCCESS into something else.
        for (s in listOf(
            ToolBlockStatus.SUCCESS, ToolBlockStatus.FAILED,
            ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT,
        )) {
            assertEquals(s, OrphanedRunningToolGate.resolve(s, isLiveRun = false, jobIsAlive = false))
            assertFalse(OrphanedRunningToolGate.isSpinner(s))
        }
    }

    @Test
    fun `a null status stays null`() {
        // Text blocks carry no tool status.
        assertEquals(null, OrphanedRunningToolGate.resolve(null, isLiveRun = false, jobIsAlive = false))
        assertFalse(OrphanedRunningToolGate.isSpinner(null))
    }

    // ── the override rule ──────────────────────────────────────────────────

    @Test
    fun `a stale spinner override must not re-stamp the rebuilt block`() {
        // This is what made the orphan immortal across reloads.
        assertFalse(
            OrphanedRunningToolGate.overrideMayApply(
                ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = false,
            ),
        )
    }

    @Test
    fun `a live override still applies`() {
        assertTrue(OrphanedRunningToolGate.overrideMayApply(ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = true))
        assertTrue(OrphanedRunningToolGate.overrideMayApply(ToolBlockStatus.RUNNING, isLiveRun = true, jobIsAlive = false))
    }

    @Test
    fun `a terminal override always applies, dead job or not`() {
        // A completion arriving for a job already reaped must still be written,
        // or the block would be stuck on its pre-completion content.
        assertTrue(OrphanedRunningToolGate.overrideMayApply(ToolBlockStatus.SUCCESS, isLiveRun = false, jobIsAlive = false))
        assertTrue(OrphanedRunningToolGate.overrideMayApply(null, isLiveRun = false, jobIsAlive = false))
    }

    // ── the wiring ─────────────────────────────────────────────────────────

    private val vmSrc by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }
}
