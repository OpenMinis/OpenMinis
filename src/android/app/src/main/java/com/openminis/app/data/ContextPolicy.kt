package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage

/**
 * Pure-logic policy that decides, given a token estimate and the model's
 * context window, whether the agent loop should offload big tool results,
 * trigger a compact/summarize pass, or surface "context exhausted" to the UI.
 *
 * Mirrors iOS `ContextPolicy` (ContextPolicy.swift): 4-tier thresholds keyed
 * off the model's context window, with no summarization algorithm embedded —
 * the actual compact + offload execution lives in the agent loop, this struct
 * only answers "what state are we in?".
 *
 * Thresholds intentionally leave headroom (10k/20k/40k below the ceiling) so
 * one more agent turn can still fit before the user sees any disruption.
 */
data class ContextPolicy(
    /** Above this token count, next tool result should be written to disk. 0 disables. */
    val offloadThreshold: Int,
    /** After offload, shrink context toward this target (lower than threshold). */
    val offloadTarget: Int,
    /** Above this, trigger a compact/summarize pass. 0 disables. */
    val compactThreshold: Int,
    /** When true, the tier is too small for auto-compact; only surface .exhausted. */
    val exhaustedOnly: Boolean,
    /** Whether the "Compact now" button is offered in the UI. */
    val manualCompactAllowed: Boolean,
) {
    enum class CheckResult { OK, NEEDS_COMPACT, EXHAUSTED }

    /**
     * Classify the current turn's token pressure. Priority:
     *   1. If compact is available and tokens crossed [compactThreshold] → NEEDS_COMPACT.
     *   2. If this is a small-window tier (`exhaustedOnly`) and we're past the
     *      offload line or 90% of the window → EXHAUSTED.
     *   3. Otherwise OK.
     */
    fun check(estimatedTokens: Int, contextWindow: Int): CheckResult {
        if (compactThreshold > 0 && estimatedTokens >= compactThreshold) {
            return CheckResult.NEEDS_COMPACT
        }
        // [T-ctx-overflow-hard-stop] Already at or past the ceiling. Whatever the
        // tier says about headroom economics is moot — the next request does not
        // fit. Compact if this tier can (manualCompactAllowed being the signal
        // that a summary is viable at all), otherwise report exhausted so the
        // caller prompts instead of sending a request that cannot succeed.
        // Without this, an exhaustedOnly tier returned OK up to its advisory line
        // and kept returning OK past 100%. iOS parity: ContextPolicy.swift.
        if (contextWindow > 0 && estimatedTokens >= contextWindow) {
            return if (manualCompactAllowed) CheckResult.NEEDS_COMPACT else CheckResult.EXHAUSTED
        }
        if (exhaustedOnly) {
            val exhaustLine = if (offloadThreshold > 0) offloadThreshold else (contextWindow * 9 / 10)
            if (estimatedTokens >= exhaustLine) return CheckResult.EXHAUSTED
        }
        return CheckResult.OK
    }

    /** Whether the next tool result should be offloaded to disk. */
    fun shouldOffload(estimatedTokens: Int): Boolean =
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold

    /** What the agent loop does with its next request (see [inLoopStep]). */
    enum class InLoopStep {
        /** Under the compact line — send. */
        PROCEED,
        /** Compact in place, then re-check. */
        COMPACT,
        /** Compaction can do no more, but the request fits the window — send. */
        SEND_WITHIN_WINDOW,
        /** Over the window only by the calibrated extrapolation — send once for the provider to decide. */
        SEND_UNCALIBRATED_ONCE,
        /** Does not fit and nothing left to try. */
        STOP,
    }

    companion object {
        /**
         * [T-ctx-measure-outbound] The in-loop guard's decision table, shared
         * with iOS `ContextPolicy.inLoopStep`. Every branch that is not COMPACT
         * or STOP exists to keep a session from wedging: above the compact
         * line is still sendable, and an "over the window" that rests only on a
         * ratio (learned on other content, or borrowed from another model) gets
         * one real request, whose answer or rejection then corrects the ratio.
         *
         * [canCompact] = compaction budget left AND the last pass made progress.
         * [rawTokens] = the same request with no calibration applied.
         * A window of 0 (unknown) never stops a request.
         */
        fun inLoopStep(
            verdict: CheckResult,
            measured: Int,
            rawTokens: Int,
            window: Int,
            canCompact: Boolean,
            ratio: Double,
            uncalibratedSendUsed: Boolean,
        ): InLoopStep = when (verdict) {
            CheckResult.OK -> InLoopStep.PROCEED
            CheckResult.EXHAUSTED -> InLoopStep.STOP
            CheckResult.NEEDS_COMPACT -> when {
                canCompact -> InLoopStep.COMPACT
                window <= 0 || measured < window -> InLoopStep.SEND_WITHIN_WINDOW
                !uncalibratedSendUsed && ratio > 1.0 && rawTokens < window -> InLoopStep.SEND_UNCALIBRATED_ONCE
                else -> InLoopStep.STOP
            }
        }

        /**
         * Produce the policy for a given context window size. Four tiers:
         *   - `<32K`   → offload/compact disabled; UI tells user to start a new chat.
         *   - `32K–64K` → offload only; exhaust line = ctx − 10k.
         *   - `64K–128K` → offload + compact; headroom 10k for compact.
         *   - `≥128K`  → generous offload + compact; headroom 20k.
         */
        /**
         * [T-ctx-user-cap] A user-chosen group cap is NOT a model's native
         * window, and the tier table only makes sense for the latter. The fixed
         * 10k/20k/40k headroom subtractions — and disabling auto-compact under
         * 64K — exist because a genuinely small model cannot afford a summary
         * plus re-appended warm-up turns. When the user caps a 1M model at 32K
         * none of that holds: the model has room to run the compaction call, and
         * "keep this group under 32K" is an instruction to compact, not a reason
         * to stop compacting. iOS parity: ContextPolicy.swift `isUserCap`.
         */
        fun forUserCap(contextWindow: Int): ContextPolicy = ContextPolicy(
            offloadThreshold = (contextWindow * 0.70).toInt(),
            offloadTarget = (contextWindow * 0.55).toInt(),
            compactThreshold = (contextWindow * 0.85).toInt(),
            exhaustedOnly = false,
            manualCompactAllowed = true,
        )

        fun forContextWindow(contextWindow: Int): ContextPolicy = when {
            contextWindow < 32_000 -> ContextPolicy(
                offloadThreshold = 0,
                offloadTarget = 0,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = false,
            )
            contextWindow < 64_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 10_000,
                offloadTarget = contextWindow - 15_000,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = true,
            )
            contextWindow < 128_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 20_000,
                offloadTarget = contextWindow - 30_000,
                compactThreshold = contextWindow - 10_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
            else -> ContextPolicy(
                offloadThreshold = contextWindow - 40_000,
                offloadTarget = contextWindow - 60_000,
                compactThreshold = contextWindow - 20_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
        }
    }
}

/**
 * [T-ctx-measure-outbound] Sizes the request that is ABOUT to be sent, so every
 * capacity decision (compact, offload, `max_tokens`) judges the context the
 * model will actually receive. Mirrors iOS `ContextSizeMeter`
 * (ContextPolicy.swift) weight-for-weight.
 *
 * Why: the guards judged `_lastTurnContextTokens`, the provider's count for
 * the PREVIOUS request, which only another successful call ever replaced. After
 * a send-time or manual compaction nothing marked it stale, so the first check
 * of the next turn compacted again on the pre-compaction number — an extra
 * summary that folded the user's just-sent message in. After a revert it kept
 * the COMPACTED context's number and under-read the restored history. The
 * in-loop path papered over half of this with a one-shot "stale" flag.
 *
 * The provider's count is now used for what it is good at — calibration:
 *
 *     size(now) = ratio × (estimate(outbound history) + estimate(system prompt + tools))
 *
 * with `ratio = reported / estimate(that same request)`. Both sides describe
 * one request, so the ratio stays valid across compaction, offload, revert and
 * relaunch, and whatever changes the history changes the measurement at once.
 */
object ContextSizeMeter {

    /**
     * Token estimate by character class. Fitted against cl100k_base on
     * Chinese, Japanese, English prose, source code, JSON, git log and `ls`
     * output: 0.66–1.18x of the real count, versus 0.30x for CJK under a flat
     * chars/3.5. The residual is absorbed by the per-session calibration.
     */
    fun estimateTokens(text: String): Int {
        var letters = 0
        var digits = 0
        var asciiOther = 0
        var nonAscii = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp >= 128 -> nonAscii++
                cp in 65..90 || cp in 97..122 -> letters++
                cp in 48..57 -> digits++
                else -> asciiOther++
            }
            i += Character.charCount(cp)
        }
        val tokens = letters / 4.5 + digits / 2.0 + asciiOther * 0.35 + nonAscii
        return kotlin.math.ceil(tokens).toInt()
    }

    const val PER_MESSAGE_OVERHEAD = 4
    const val PER_TOOL_PART_OVERHEAD = 4

    fun estimateTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> estimateTokens(part.text)
        is AgentContentPart.ToolUse ->
            estimateTokens(part.name) + estimateTokens(part.input.toString()) + PER_TOOL_PART_OVERHEAD
        is AgentContentPart.ToolResult ->
            estimateTokens(part.name) + estimateTokens(part.content) + PER_TOOL_PART_OVERHEAD +
                (part.imageData?.let { imageTokens(it) } ?: 0)
        is AgentContentPart.ImageData -> imageTokens(part.data)
    }

    /** Providers serialise `contentParts` when present and `content` otherwise. */
    fun estimateTokens(message: LLMMessage): Int {
        val body = if (message.contentParts.isNotEmpty()) {
            message.contentParts.sumOf { estimateTokens(it) }
        } else {
            estimateTokens(message.content)
        }
        return PER_MESSAGE_OVERHEAD + body + message.imageParts.sumOf { imageTokens(it.data) }
    }

    /**
     * Persisted messages are cached: without it every measurement re-scans the
     * whole history several times per iteration (debug builds run interpreted).
     * The key carries the content SHAPE as well as the id, because offload
     * rewrites a persisted message in place — a changed length or offload flag
     * is a new key, so a shrunk message is re-measured. In-flight messages (no
     * dbMessageId yet) are always measured.
     */
    fun estimateTokens(messages: List<LLMMessage>): Int = messages.sumOf { estimateCached(it) }

    private fun estimateCached(message: LLMMessage): Int {
        val id = message.dbMessageId ?: return estimateTokens(message)
        val shape = buildString {
            append(id).append('|').append(message.content.length).append('|').append(message.contentParts.size)
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> append("|t").append(part.text.length)
                    is AgentContentPart.ToolUse -> append("|u").append(part.input.length()).append(if (part.isOffloadedArgument) "o" else "")
                    is AgentContentPart.ToolResult -> append("|r").append(part.content.length).append('/').append(part.imageData?.size ?: 0)
                    is AgentContentPart.ImageData -> append("|i").append(part.data.size)
                }
            }
            for (img in message.imageParts) append("|p").append(img.data.size)
        }
        synchronized(messageEstimateCache) { messageEstimateCache[shape]?.let { return it } }
        val tokens = estimateTokens(message)
        synchronized(messageEstimateCache) { messageEstimateCache[shape] = tokens }
        return tokens
    }

    private val messageEstimateCache = object : LinkedHashMap<String, Int>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 20_000
    }

    /** System prompt plus tool schemas — the part the history-only estimate omitted. */
    fun estimateFixedTokens(systemPrompt: String?, tools: List<AgentToolDefinition>): Int {
        var total = systemPrompt?.let { estimateTokens(it) } ?: 0
        for (tool in tools) {
            total += estimateTokens(tool.name) + estimateTokens(tool.description) + 10
            for ((name, param) in tool.parameters) {
                total += estimateTokens(name) + estimateTokens(param.description) + 4
                param.enumValues?.forEach { total += estimateTokens(it) + 1 }
            }
        }
        return total
    }

    /**
     * Bounds on reported / estimated. The floor is tight on purpose: the
     * estimator's worst measured over-read is 1.18x, so a genuine ratio never
     * falls much below 0.85; a lower one means an under-reporting upstream, and
     * following it down would let an over-length request through. The ceiling
     * is loose because under-reads are real (denser tokenizers, and content we
     * do not measure such as reasoning echoes).
     */
    const val CALIBRATION_MIN = 0.8
    const val CALIBRATION_MAX = 3.0

    fun calibrationRatio(reported: Int, estimated: Int): Double? {
        if (reported <= 0 || estimated <= 0) return null
        return (reported.toDouble() / estimated).coerceIn(CALIBRATION_MIN, CALIBRATION_MAX)
    }

    fun calibrated(estimated: Int, ratio: Double): Int = kotlin.math.ceil(estimated * ratio).toInt()

    /**
     * Applied when the current model has no calibration of its own and we are
     * borrowing another model's ratio: tokenizers differ by roughly 10–30%, so
     * judging a newly selected model by the previous one's ratio under-reads it.
     * Leaning high for that one request costs at worst an early compaction; its
     * first response replaces the borrowed ratio.
     */
    const val UNCALIBRATED_MODEL_MARGIN = 1.2

    /** Where [ratioFor] got its answer, for logs. */
    fun ratioSource(modelId: String?, known: Map<String, Double>, lastLearned: Double?): String = when {
        modelId != null && known.containsKey(modelId) -> "own"
        lastLearned == null -> "default"
        else -> "borrowed"
    }

    /**
     * [T-ctx-ratio-smoothing] Share of the gap closed per sample when a sample
     * says the ratio should come DOWN (recent samples weigh 0.3, 0.21, 0.147…).
     */
    const val CALIBRATION_FALL_RATE = 0.3

    /**
     * Fold one sample into a model's ratio — an asymmetric weighted average.
     * Mirrors iOS `ContextSizeMeter.smoothed`. A too-LOW ratio under-reads and
     * can send a request over the window; a too-high one only compacts early.
     * So a higher sample applies at once, and a lower one moves the ratio only
     * part of the way: one anomalous under-count barely moves it, a real drop
     * settles within 3–4 responses.
     */
    fun smoothed(previous: Double?, sample: Double): Double {
        if (previous == null || sample >= previous) return sample
        return previous + CALIBRATION_FALL_RATE * (sample - previous)
    }

    /** The model's own ratio; else the newest learned one with the margin (never below 1.0); else 1.0. */
    fun ratioFor(modelId: String?, known: Map<String, Double>, lastLearned: Double?): Double {
        if (modelId != null) known[modelId]?.let { return it }
        val last = lastLearned ?: return 1.0
        return (maxOf(last, 1.0) * UNCALIBRATED_MODEL_MARGIN).coerceAtMost(CALIBRATION_MAX)
    }

    /**
     * The token count a context-length rejection says the request had, when it
     * states one: the larger of the numbers (the request overflowed the limit).
     * Numbers under 1000 are status codes and the like.
     */
    fun requestedTokens(overflowMessage: String): Int? {
        val cleaned = overflowMessage.replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        return Regex("\\d{4,}").findAll(cleaned).mapNotNull { it.value.toIntOrNull() }.filter { it >= 1000 }.maxOrNull()
    }

    /**
     * Ratio after a rejection: the stated count ÷ our estimate of that request
     * (trusted only within 0.9–4x of the window, so a request id cannot pin the
     * ratio at its ceiling), else just past the window. Never lowered.
     */
    fun ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int): Double {
        if (estimated <= 0) return current
        val plausible = requested?.takeIf { window <= 0 || (it >= window * 0.9 && it <= window * 4.0) }
        val target = plausible?.toDouble() ?: (maxOf(window, 1) * 1.02)
        return maxOf(current, target / estimated).coerceAtMost(CALIBRATION_MAX)
    }

    // ── Session calibration: replay and reload ────────────────────────────

    /** One persisted (report, estimate) pair — a usage row. Same keys as iOS StoredTokenUsage. */
    data class CalibrationSample(val reported: Int, val estimated: Int, val fixedTokens: Int, val modelId: String?)

    /** A session's calibration: per-model ratios, the newest learned one, and the fixed share. */
    data class CalibrationState(
        val ratios: Map<String, Double> = emptyMap(),
        val lastLearned: Double? = null,
        val fixedTokens: Int = 0,
        val samples: Int = 0,
    ) {
        /**
         * Reloading the SAME session keeps what was learned in memory: a ratio
         * raised by a rejection exists nowhere else (a rejected request stamps
         * no pair), and in-memory values are never older than the rows'. So
         * they win, key by key; the rows fill in models memory has not seen.
         */
        fun carryingOver(learned: CalibrationState): CalibrationState = copy(
            ratios = ratios + learned.ratios,
            lastLearned = learned.lastLearned ?: lastLearned,
            fixedTokens = if (learned.fixedTokens > 0) learned.fixedTokens else fixedTokens,
        )
    }

    /** The pair in a usage row's JSON, or null for a row written before the pair existed. */
    fun calibrationSample(usageJson: String): CalibrationSample? {
        val o = runCatching { org.json.JSONObject(usageJson) }.getOrNull() ?: return null
        val reported = o.optInt("latestContextTokens", 0)
        val estimated = o.optInt("estimatedRequestTokens", 0)
        if (reported <= 0 || estimated <= 0) return null
        val model = o.optString("calibrationModelId", "").takeIf { it.isNotEmpty() }
        return CalibrationSample(reported, estimated, o.optInt("estimatedFixedTokens", 0), model)
    }

    /**
     * [T-ctx-ratio-smoothing] Rebuild calibration from persisted samples in
     * conversation order, through the same [smoothed] rule the live path
     * uses, so a reload reproduces the ratio the session had in memory rather
     * than jumping to the newest raw sample. A sample with no model id (older
     * rows) informs only [CalibrationState.lastLearned].
     */
    fun replayCalibration(samples: List<CalibrationSample>): CalibrationState {
        val ratios = mutableMapOf<String, Double>()
        var last: Double? = null
        var fixed = 0
        var count = 0
        for (s in samples) {
            val sample = calibrationRatio(s.reported, s.estimated) ?: continue
            count++
            last = if (s.modelId != null) smoothed(ratios[s.modelId], sample).also { ratios[s.modelId] = it } else sample
            fixed = s.fixedTokens
        }
        return CalibrationState(ratios, last, fixed, count)
    }

    /**
     * [T-ctx-warmup-fit] How many leading warm-up messages to drop so that
     * warm-up + [restTokens] is under [budget]. Drops whole turns: after each
     * cut it keeps dropping until the slice starts on a user-TEXT message
     * ([startsTurn]), so a tool call is never separated from its result.
     * 0 when it already fits; [sizes].size when nothing does.
     */
    fun warmUpDrop(sizes: List<Int>, startsTurn: List<Boolean>, restTokens: Int, budget: Int): Int {
        require(sizes.size == startsTurn.size)
        var total = sizes.sum()
        var drop = 0
        while (drop < sizes.size && total + restTokens >= budget) {
            total -= sizes[drop++]
            while (drop < sizes.size && !startsTurn[drop]) total -= sizes[drop++]
        }
        return drop
    }

    /**
     * `countImageTokens` decodes the image header; the measurement runs
     * several times per loop iteration, so results are cached by a cheap
     * content fingerprint.
     */
    private val imageTokenCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 256
    }

    fun imageTokens(data: ByteArray): Int {
        val head = data.copyOfRange(0, minOf(64, data.size)).contentHashCode()
        val tail = data.copyOfRange(maxOf(0, data.size - 64), data.size).contentHashCode()
        val key = "${data.size}-$head-$tail"
        synchronized(imageTokenCache) { imageTokenCache[key]?.let { return it } }
        val tokens = BPETokenizer.countImageTokens(data)
        synchronized(imageTokenCache) { imageTokenCache[key] = tokens }
        return tokens
    }
}
