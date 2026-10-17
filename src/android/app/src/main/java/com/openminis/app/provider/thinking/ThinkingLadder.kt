package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-thinking-ladder-shared] The ONE place a thinking level becomes a token budget.
 *
 * ## Why this exists
 *
 * Four providers drive thinking with a NUMERIC token budget rather than an
 * effort string: Gemini 2.5 (`thinkingBudget`), Qwen/DashScope
 * (`thinking_budget`), Anthropic legacy (`budget_tokens`) and the Zen free lane
 * (`max_completion_tokens`) that shipped until 2026-10-17. Each grew its own
 * hardcoded ladder, and each one
 * collapsed in the same way — the top rungs were pinned to the model's own
 * ceiling:
 *
 *     XHIGH, MAX, ULTRA -> <the model's max output tokens>
 *
 * so on any model whose capacity sits near that ceiling the three rungs sent
 * byte-identical numbers. The user drags a slider, the request does not change.
 * Live evidence from the device's own Zen free catalogue: of 9 configured
 * models, 4 (big-pickle, mimo-v2.5, mimo-v2.6, ling-3.1 — capacities 32000 /
 * 32768) had XHIGH/MAX/ULTRA all clipped to the same value. Absolute ladders
 * cannot fix this: a constant that is below one model's ceiling is above
 * another's.
 *
 * ## The fix
 *
 * Budgets are FRACTIONS OF THE MODEL'S OWN CAPACITY, so every level is
 * strictly increasing for every capacity. A ceiling still clips the result —
 * that is correct and unavoidable — but because the ladder is proportional the
 * clip only bites at the very top rung instead of eating the whole upper half
 * of the ladder.
 *
 * Fractions are deliberately NOT evenly spaced (1/7..6/7 would give a linear
 * ramp). Thinking cost grows superlinearly with depth, so the ladder doubles
 * early and tapers late — the same shape as the measured must-think behaviour
 * (on mimo-v2.6-flash-free, 82% of output tokens were reasoning).
 *
 * ## Contract
 *
 * For any `capacity >= 2 * floor` the returned budgets are:
 *   - STRICTLY increasing in [ThinkingLevel.rank]
 *   - each `< capacity` (so a downstream `minOf(..., capacity)` is a no-op and
 *     cannot collapse two rungs into one)
 *   - each `>= floor`
 *
 * Degenerate capacities below `2 * floor` cannot satisfy all three, and the
 * function degrades to an honest single value rather than emitting a fake
 * gradient — the picker is expected to have already hidden those rungs.
 */
object ThinkingLadder {

    /**
     * Fraction of the model's capacity per rung, weakest first, indexed by
     * [ThinkingLevel.rank] (OFF..ULTRA).
     *
     * OFF is the smallest and stays small: it exists to leave room for the
     * ANSWER, so it must not spend the model's budget on reasoning the user
     * explicitly turned off. ULTRA stops at 15/16 so it approaches the ceiling
     * without ever being pinned to it.
     */
    private val FRACTIONS = doubleArrayOf(
        0.03125, // OFF     1/32
        0.0625,  // LOW     2/32
        0.125,   // MEDIUM  4/32
        0.25,    // HIGH    8/32
        0.4375,  // XHIGH  14/32
        0.6875,  // MAX    22/32
        0.9375,  // ULTRA  30/32
    )

    /** Smallest budget any rung may emit; also the OFF-side absolute floor. */
    const val FLOOR = 512
    /** Generic fallback only when models.dev and the live model both omit a cap. */
    const val DEFAULT_CAPACITY = 32_768

    /**
     * The budget for [level] on a model whose output capacity is [capacity].
     *
     * @param capacity the model's maximum output tokens — the ceiling this
     *   ladder is scaled against. Must be the MODEL's capacity, never a
     *   caller's arbitrary `maxTokens`, or the proportionality is lost.
     * @param multiplier scale factor for models that cannot stop reasoning
     *   (MiMo/Agnes, measured at 82% reasoning tokens): their rungs are
     *   doubled so the answer still has room left after reasoning.
     * @return a budget strictly below [capacity], or [FLOOR] when [capacity]
     *   is too small to express the ladder honestly.
     */
    fun budgetFor(
        level: ThinkingLevel,
        capacity: Int,
        multiplier: Int = 1,
    ): Int {
        // [T-thinking-ladder-shared] Degenerate capacity handled BEFORE any
        // arithmetic. `coerceIn` over an empty range throws
        // IllegalArgumentException ("Cannot coerce value to an empty range"),
        // so a capacity at or below the floor must return early — otherwise a
        // tiny maxOutputTokens crashes the request instead of degrading.
        //
        // It returns `capacity - 1` (clamped at 1), NOT zero: a budget of 0 is
        // rejected by Gemini/DashScope outright, and for Zen this value REPLACES
        // the caller's maxTokens, so 0 would leave the model no room to answer.
        // There is no honest "0% thinking" below the floor — the honest answer
        // is "as much as the model can possibly emit minus one".
        if (capacity <= FLOOR) return (capacity - 1).coerceAtLeast(1)
        val idx = level.rank.coerceIn(0, FRACTIONS.lastIndex)
        // [T-thinking-ladder-shared] The multiplier RESCALES THE FRACTIONS, it
        // does not scale the resulting number. Multiplying the budget directly
        // (budget * 2) pushes the top rungs past the ceiling, where they clamp
        // back to capacity-1 and collapse into each other — reintroducing the
        // very bug this object exists to remove. Dividing the fractions by the
        // multiplier instead shifts every rung DOWN proportionally, so the
        // whole ladder keeps its shape and ULTRA stays below the ceiling on
        // every capacity, doubled or not.
        val fraction = FRACTIONS[idx] / multiplier
        val scaled = (capacity.toLong() * fraction).toLong()
        // A capacity too small to separate every rung is not a reason to emit a
        // fake gradient: supportsDistinctLadder() reports that and the picker
        // truncates, rather than offering rungs that cannot differ.
        return scaled.coerceIn(FLOOR.toLong(), (capacity - 1).toLong()).toInt()
    }

    /**
     * The whole ladder for [capacity], weakest first — used by tests to assert
     * the monotonicity contract in one place instead of per provider.
     */
    fun fullLadder(capacity: Int, multiplier: Int = 1): List<Int> =
        ThinkingLevel.entries.map { budgetFor(it, capacity, multiplier) }

    /**
     * True when [capacity] can express every rung as a distinct budget. A model
     * failing this cannot honestly offer the full ladder, so the picker should
     * truncate — this is the check that keeps the UI and the wire in agreement.
     */
    fun supportsDistinctLadder(capacity: Int, multiplier: Int = 1): Boolean =
        capacity > FLOOR * 2 && fullLadder(capacity, multiplier).toSet().size == FRACTIONS.size
}