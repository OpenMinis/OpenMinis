package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-subagent-turn-countdown] A sub-agent used to learn its tool budget was
 * spent only on the wrap-up turn — the round where tools are already withdrawn.
 * A child that had spent its rounds investigating could then no longer write
 * the file it was delegated to produce, which is the reported failure.
 *
 * These pin the warning that now lands a few rounds earlier, while tools still
 * work.
 */
class SubAgentTurnCountdownTest {

    @Test
    fun `warning states the remaining count and is singular at one`() {
        assertTrue(HelperRunner.turnBudgetWarning(3).contains("3 tool rounds left"))
        assertTrue(HelperRunner.turnBudgetWarning(2).contains("2 tool rounds left"))
        // Singular, because "1 tool rounds left" is exactly the sort of seam
        // that makes a generated instruction read as boilerplate and get
        // skimmed — this is the most urgent one to be read.
        assertTrue(HelperRunner.turnBudgetWarning(1).contains("1 tool round left"))
        assertFalse(HelperRunner.turnBudgetWarning(1).contains("1 tool rounds"))
    }

    @Test
    fun `warning tells the child to finish, not to stop`() {
        val w = HelperRunner.turnBudgetWarning(2)
        // Tools still work at this point, so it must push toward using them to
        // land the artifact — the opposite of the wrap-up prompt, which tells
        // the child to write text because tools are gone.
        assertTrue("must redirect effort to finishing", w.contains("Stop investigating"))
        assertTrue("must name the write-the-artifact action", w.contains("write a file"))
        assertFalse(
            "must NOT claim tools are gone — they are not, and saying so would " +
                "stop the child from doing the very write this exists to rescue",
            w.contains("no longer available"),
        )
    }

    @Test
    fun `lead time leaves room to act before the cliff`() {
        assertTrue(HelperRunner.TURN_WARNING_LEAD >= 2)
        assertTrue(HelperRunner.TURN_WARNING_LEAD < HelperRunner.MAX_TURNS)
    }

    /**
     * The injection lives in ChatViewModel's agent loop, which needs an Android
     * runtime to execute. Pin its two load-bearing properties as source facts
     * so a refactor cannot quietly drop them.
     */
    @Test
    fun `loop injects the warning before the wrap-up and only once`() {
        val src = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        assertTrue("loop must call turnBudgetWarning", src.contains("HelperRunner.turnBudgetWarning"))
        assertTrue(
            "must be guarded by a once-only flag, or every remaining round " +
                "would repeat the nudge and drown the actual work",
            src.contains("helperTurnWarningInjected = true"),
        )
        assertTrue(
            "must be skipped once the wrap-up fired — at that point zero rounds " +
                "remain and the wrap-up is the stronger instruction",
            src.contains("!helperWrapUpInjected && !helperTurnWarningInjected"),
        )
    }

    @Test
    fun `the cap matches the main chat's own ceiling`() {
        // [T-subagent-turn-parity] Raised 25 -> 200 to match the parent
        // conversation. The point of pinning it is unchanged: the number is a
        // deliberate choice, so it should move only when someone means it —
        // never as a side effect of editing the countdown around it.
        //
        // Asserting the literal rather than reading MAX_AGENT_TURNS keeps this
        // a real check: ChatViewModel is an Android-runtime class this JVM test
        // cannot load, and a test that compared the constant to itself would
        // pass no matter what either value became.
        assertEquals(200, HelperRunner.MAX_TURNS)
    }
}
