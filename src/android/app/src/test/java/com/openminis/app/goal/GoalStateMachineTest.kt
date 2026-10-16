package com.openminis.app.goal

import com.openminis.app.data.db.GoalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalStateMachineTest {
    @Test fun `create validates user objective and budget`() {
        val goal = GoalStateMachine.create("s1", "  Build app  ", 100, 1)
        assertEquals("Build app", goal.objective)
        assertEquals(100L, goal.tokenBudget)
        expectThrows<IllegalArgumentException> { GoalStateMachine.create("s", "", null, 1) }
        expectThrows<IllegalArgumentException> { GoalStateMachine.create("s", "x", 0, 1) }
    }

    @Test fun `blocked requires same condition three consecutive times`() {
        var goal = GoalStateMachine.create("s", "finish", null, 1)
        goal = GoalStateMachine.modelUpdate(goal, GoalStatus.BLOCKED, "missing api", 2)
        assertEquals(GoalStatus.ACTIVE, goal.status)
        assertEquals(1, goal.blockedCount)
        goal = GoalStateMachine.modelUpdate(goal, GoalStatus.BLOCKED, "missing api", 3)
        assertEquals(GoalStatus.ACTIVE, goal.status)
        goal = GoalStateMachine.modelUpdate(goal, GoalStatus.BLOCKED, "missing api", 4)
        assertEquals(GoalStatus.BLOCKED, goal.status)
        expectThrows<IllegalArgumentException> {
            GoalStateMachine.modelUpdate(goal, GoalStatus.BLOCKED, "other", 5)
        }
    }

    @Test fun `blocked condition is clipped before persistence and comparison`() {
        val oversized = "x".repeat(GoalStateMachine.MAX_BLOCKED_CONDITION_LENGTH + 500)
        var goal = GoalStateMachine.create("s", "finish", null, 1)
        goal = GoalStateMachine.modelUpdate(goal, GoalStatus.BLOCKED, oversized, 2)
        assertEquals(GoalStateMachine.MAX_BLOCKED_CONDITION_LENGTH, goal.blockedCondition?.length)
    }

    @Test fun `changing blocked condition before terminal resets confirmation count`() {
        var goal = GoalStateMachine.create("s", "finish", null, 1)
        goal = GoalStateMachine.modelUpdate(goal, GoalStatus.BLOCKED, "first", 2)
        goal = GoalStateMachine.modelUpdate(goal, GoalStatus.BLOCKED, "other", 3)
        assertEquals(1, goal.blockedCount)
        assertEquals(GoalStatus.ACTIVE, goal.status)
    }

    @Test fun `model cannot pause resume or rewrite goal`() {
        val goal = GoalStateMachine.create("s", "finish", null, 1)
        expectThrows<IllegalArgumentException> {
            GoalStateMachine.modelUpdate(goal, GoalStatus.PAUSED, null, 2)
        }
    }

    @Test fun `budget accounting saturates and disables automatic continuation`() {
        val goal = GoalStateMachine.create("s", "finish", 100, 1)
        val almost = GoalStateMachine.recordProgress(goal, 90, 10, 2)
        assertTrue(GoalStateMachine.canContinue(almost, usefulActivity = true))
        val spent = GoalStateMachine.recordProgress(almost, Long.MAX_VALUE, 10, 3)
        assertEquals(Long.MAX_VALUE, spent.tokensUsed)
        assertEquals(GoalStatus.BUDGET_LIMITED, spent.status)
        assertFalse(GoalStateMachine.canContinue(spent, usefulActivity = true))
    }

    @Test fun `lowering budget below usage limits active goal and blocks resume`() {
        val goal = GoalStateMachine.create("s", "finish", null, 1)
        val spent = GoalStateMachine.recordProgress(goal, 100, 1_000, 2)
        val limited = GoalStateMachine.setBudget(spent, 50, 3)
        assertEquals(GoalStatus.BUDGET_LIMITED, limited.status)
        assertFalse(GoalStateMachine.canContinue(limited, usefulActivity = true))
        expectThrows<IllegalArgumentException> { GoalStateMachine.resume(limited, 4) }
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    @Test fun `pause and resume preserve goal without continuation while paused`() {
        val goal = GoalStateMachine.create("s", "finish", null, 1)
        val paused = GoalStateMachine.pause(goal, 2)
        assertFalse(GoalStateMachine.canContinue(paused, usefulActivity = true))
        assertEquals(GoalStatus.ACTIVE, GoalStateMachine.resume(paused, 3).status)
    }

    @Test fun `paused goal does not accumulate ordinary request usage`() {
        val goal = GoalStateMachine.create("s", "ship it", 500, 1)
        val paused = GoalStateMachine.pause(goal, 2)
        assertEquals(paused, GoalStateMachine.recordProgress(paused, 120, 900, 3))
    }
}
