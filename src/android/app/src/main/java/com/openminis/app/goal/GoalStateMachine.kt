package com.openminis.app.goal

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity

/** Pure goal transition rules; database and UI are deliberately outside this class. */
object GoalStateMachine {
    const val MAX_OBJECTIVE_LENGTH = 4_000
    const val MAX_BLOCKED_CONDITION_LENGTH = 2_000
    const val BLOCKED_CONFIRMATIONS = 3

    fun create(
        sessionId: String,
        objective: String,
        tokenBudget: Long?,
        now: Long,
    ): SessionGoalEntity {
        val normalized = objective.trim()
        require(normalized.isNotEmpty()) { "Goal objective must not be empty" }
        require(normalized.length <= MAX_OBJECTIVE_LENGTH) { "Goal objective is too long" }
        require(tokenBudget == null || tokenBudget > 0) { "Token budget must be positive" }
        return SessionGoalEntity(
            sessionId = sessionId,
            objective = normalized,
            tokenBudget = tokenBudget,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun pause(goal: SessionGoalEntity, now: Long): SessionGoalEntity {
        require(goal.status == GoalStatus.ACTIVE) { "Only an active Goal can be paused" }
        return goal.copy(status = GoalStatus.PAUSED, pausedAt = now, updatedAt = now)
    }

    fun resume(goal: SessionGoalEntity, now: Long): SessionGoalEntity {
        val budgetExhausted = goal.tokenBudget?.let { goal.tokensUsed >= it } == true
        require(!budgetExhausted) { "Increase the Goal budget before resuming" }
        if (goal.status == GoalStatus.ACTIVE) return goal.copy(updatedAt = now)
        require(goal.status == GoalStatus.PAUSED || goal.status == GoalStatus.BLOCKED) {
            if (goal.status == GoalStatus.BUDGET_LIMITED) "Increase the Goal budget before resuming" else "This Goal cannot be resumed"
        }
        return goal.copy(status = GoalStatus.ACTIVE, pausedAt = null, blockedCondition = null, blockedCount = 0, updatedAt = now)
    }

    fun setBudget(goal: SessionGoalEntity, tokenBudget: Long?, now: Long): SessionGoalEntity {
        require(tokenBudget == null || tokenBudget > 0) { "Token budget must be positive" }
        val remaining = tokenBudget == null || goal.tokensUsed < tokenBudget
        val status = when {
            goal.status == GoalStatus.ACTIVE && !remaining -> GoalStatus.BUDGET_LIMITED
            goal.status == GoalStatus.BUDGET_LIMITED && remaining -> GoalStatus.ACTIVE
            else -> goal.status
        }
        return goal.copy(tokenBudget = tokenBudget, status = status, updatedAt = now)
    }

    fun modelUpdate(
        goal: SessionGoalEntity,
        status: String,
        condition: String? = null,
        now: Long,
    ): SessionGoalEntity {
        require(goal.status == GoalStatus.ACTIVE) { "Only an active Goal can be updated by the model" }
        require(status == GoalStatus.COMPLETE || status == GoalStatus.BLOCKED) {
            "The model may only mark a goal complete or blocked"
        }
        return if (status == GoalStatus.COMPLETE) {
            goal.copy(status = GoalStatus.COMPLETE, blockedCondition = null, blockedCount = 0, updatedAt = now)
        } else {
            val normalizedCondition = condition?.trim()?.takeIf(String::isNotEmpty)
                ?.take(MAX_BLOCKED_CONDITION_LENGTH) ?: "unspecified"
            val count = if (goal.blockedCondition == normalizedCondition) goal.blockedCount + 1 else 1
            goal.copy(
                status = if (count >= BLOCKED_CONFIRMATIONS) GoalStatus.BLOCKED else GoalStatus.ACTIVE,
                blockedCondition = normalizedCondition,
                blockedCount = count,
                updatedAt = now,
            )
        }
    }

    fun recordProgress(
        goal: SessionGoalEntity,
        tokens: Long,
        elapsedMs: Long,
        now: Long,
    ): SessionGoalEntity {
        require(tokens >= 0 && elapsedMs >= 0)
        if (goal.status != GoalStatus.ACTIVE) return goal
        val used = if (Long.MAX_VALUE - goal.tokensUsed < tokens) Long.MAX_VALUE else goal.tokensUsed + tokens
        return goal.copy(
            tokensUsed = used,
            elapsedMs = if (Long.MAX_VALUE - goal.elapsedMs < elapsedMs) Long.MAX_VALUE else goal.elapsedMs + elapsedMs,
            status = if (goal.tokenBudget != null && used >= goal.tokenBudget && goal.status == GoalStatus.ACTIVE) {
                GoalStatus.BUDGET_LIMITED
            } else goal.status,
            updatedAt = now,
        )
    }

    fun canContinue(goal: SessionGoalEntity, usefulActivity: Boolean): Boolean =
        goal.status == GoalStatus.ACTIVE &&
            usefulActivity &&
            (goal.tokenBudget == null || goal.tokensUsed < goal.tokenBudget)
}
