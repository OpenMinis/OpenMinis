package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-android-thinking-level-arch] Declarative catalog of each model's thinking-
 * level ceiling. Adding a model = adding a rule; retiring one = removing a rule.
 * It touches no other code path — a model that matches no rule falls back to
 * [catalogMaxThinkingLevel]'s conservative supportsReasoning default.
 *
 * Kept content-aligned with iOS ThinkingLevelCatalog.swift (same understanding
 * of what each model can do), Kotlin idiom on this side.
 */
object ThinkingLevelCatalog {
    private data class Rule(val match: (String) -> Boolean, val max: ThinkingLevel)

    private val rules: List<Rule> = listOf(
        // GPT-5.6 family: sol / terra / luna all reach MAX. ULTRA is a
        // client-side "Max + orchestration" concept, never a wire effort — the
        // effort layer maps both MAX and ULTRA to "max". Keep in lockstep with
        // iOS ThinkingLevelCatalog.swift.
        Rule({ it.startsWith("gpt-5.6-sol") || it.startsWith("gpt-5.6-terra") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.6-luna") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.5") }, ThinkingLevel.XHIGH),
        // Third-party models known to top out at high.
        // MiMo ships BOTH id spellings in the wild: catalog docs say
        // "MiMo-2.5" but the live API (api.xiaomimimo.com /v1/models) returns
        // "mimo-v2.5" / "mimo-v2.5-pro" — the old "mimo-2.5" substring missed
        // those, so the clamp passed xhigh straight through to a backend that
        // 400s on it. Match the family, not one spelling (mirrors iOS 72968c4f).
        Rule({ it.contains("mimo") || it.contains("agnes") }, ThinkingLevel.HIGH),
        // ByteDance seed (Volcano Ark "seed-1.6…"/"seed-2.0…", OpenRouter
        // "bytedance-seed/…"): rejects xhigh with "Invalid reasoning_effort:
        // xhigh". Ark's ladder tops out at high.
        Rule({ it.contains("seed-") || it.contains("bytedance-seed") }, ThinkingLevel.HIGH),
        // Anthropic Opus 4.x adaptive-thinking family. The old per-version
        // startsWith("claude-opus-4.7"/"claude-opus-4.6") checks never matched:
        // LLMModel.id separates the minor version with a hyphen
        // (claude-opus-4-8 / claude-opus-4-6), not a dot, so every Claude Opus
        // fell through to the XHIGH default instead of MAX — and Opus 4.8 had no
        // rule at all. Normalize dots→hyphens first, then a single prefix match
        // covers 4.6 / 4.7 / 4.8 and future 4.x (mirrors iOS normalizedHasPrefix).
        Rule({ normalizedHasPrefix(it, "claude-opus-4") }, ThinkingLevel.MAX),
    )

    /** Prefix match that treats "." and "-" interchangeably in the version
     *  separator so a rule matches whether the id is dotted or hyphenated. */
    private fun normalizedHasPrefix(id: String, prefix: String): Boolean =
        id.replace('.', '-').startsWith(prefix)

    /** Null means the catalog doesn't cover this model — the caller should fall
     *  through to the supportsReasoning default. */
    fun declaredMaxLevel(modelId: String): ThinkingLevel? {
        val lid = modelId.lowercase()
        return rules.firstOrNull { it.match(lid) }?.max
    }

    // ── [T-thinking-levels-data-driven] how many generic rungs reach the wire ──
    //
    // The rules above answer "how high may the ceiling GO?". This table answers
    // the question the picker actually has to answer: "which of the generic
    // levels produce a DIFFERENT request for THIS model?". They are separate
    // questions and they disagree for most families — a ceiling of XHIGH is a
    // promise about reachability, a ladder is a promise about distinctness, and
    // offering XHIGH, MAX and ULTRA to a model that maps all three onto one
    // `thinking_budget` number is a slider that provably changes nothing.
    //
    // [T-thinking-levels-data-driven] Written as ONE ordered table rather than
    // scattered `if (id.contains(...))` branches so a new family is added in a
    // single obvious place: first match wins, so NoControl families must stay
    // ABOVE their collapsing siblings (see "2.5-flash-lite" below, which
    // contains "gemini-2.5-flash").
    private data class LadderRule(val match: (String) -> Boolean, val ladder: WireLadder)

    /**
     * How the generic LOW→MAX ladder survives translation to this model's wire.
     *
     * Deliberately NOT modelled here: the Anthropic `budget_tokens` collapse
     * (HIGH/XHIGH/MAX/ULTRA all land on `maxTokens` once `maxTokens <= 65536`).
     * `maxTokens` is a property of the REQUEST, not of the model, and which
     * Anthropic shape is used (legacy budget vs 4.6+ adaptive effort, where XHIGH
     * also folds into "max") depends on which provider path emits it — none of
     * which is visible from an [LLMModel]. Keying an id rule here would silently
     * cap the ~2090 relay-hosted Claude entries that declare their own effort
     * tiers, which are a different wire path entirely. Likewise the retired Zen
     * free lane, which carried its own numeric budget on a provider property
     * (baseUrl + apiKey) rather than on an [LLMModel].
     */
    sealed interface WireLadder {
        /** Every generic rung that the model declares is a distinct wire value. */
        data object Distinct : WireLadder


        /**
         * Rungs above [lastDistinct] are folded onto [lastDistinct] by the wire
         * path, so the ladder stops there instead of advertising a no-op.
         */
        data class CollapsesAbove(val lastDistinct: ThinkingLevel) : WireLadder

        /**
         * Nothing is emitted at all — every level produces byte-identical
         * requests, so the honest answer is "offer no levels" (which leaves the
         * legacy id-rule ceiling in place, exactly as before).
         */
        data object NoControl : WireLadder
    }

    private val ladderRules: List<LadderRule> = listOf(
        // [T-thinking-levels-data-driven] Families that reason but expose no
        // effort tiers at all: a plain boolean switch (models.dev
        // "reasoning_options: toggle") or nothing whatsoever. Spoken,
        // transcribable, embedded and image models emit no thinking field in any
        // branch, so every level collapses into the same request. Gemini
        // 2.5 Flash-Lite has no `thinkingConfig` support at all. MUST precede
        // the Gemini 2.5 rule below — these ids contain it.
        //
        // Matched as a DELIMITED TOKEN, not as a substring or a suffix.
        // The suffix form silently missed the real catalog ids, which carry the
        // marker in the middle and a version after it: "text-embedding-004",
        // "text-embedding-3-large", "gemini-embedding-001", "qwen3-embedding-8b".
        // A token test is also immune to a false positive like a hypothetical
        // "imagery-embed" — only a complete segment counts.
        LadderRule(
            { id -> id.hyphenTokens().any { it in NO_CONTROL_TOKENS } },
            WireLadder.NoControl,
        ),
        // MiMo / Agnes: [T-android-xhigh-effort-clamp] demotes "xhigh" → "high"
        // because the backend 400s on it, and the catalog rule above caps the
        // family at HIGH. So HIGH/XHIGH/MAX/ULTRA are one wire value. Matches the
        // FAMILY, not one spelling: docs say "mimo-2.5", the live API serves
        // "mimo-v2.5" / "mimo-v2.5-pro" (mirrors iOS 72968c4f).
        LadderRule(
            { it.contains("mimo") || it.contains("agnes") },
            WireLadder.CollapsesAbove(ThinkingLevel.HIGH),
        ),
        // ByteDance seed (Volcano Ark "seed-1.6…"/"seed-2.0…", OpenRouter
        // "bytedance-seed/…"): "Invalid reasoning_effort: xhigh" — same shape as
        // MiMo, one rung lower than the generic default.
        LadderRule(
            { it.contains("seed-") || it.contains("bytedance-seed") },
            WireLadder.CollapsesAbove(ThinkingLevel.HIGH),
        ),
        // [T-thinking-levels-data-driven] Gemini 2.5 Pro / Flash drive a NUMERIC
        // `generationConfig.thinkingConfig.thinkingBudget`, not an effort string:
        // OFF/LOW/MEDIUM/HIGH land on distinct budgets, but every level at or
        // above XHIGH saturates the model's top budget (2.5 Pro 16384, Flash
        // 8192). Three rungs, one number. (Gemini 3.x speaks `thinkingLevel`
        // strings instead, so it is deliberately absent here.)
        LadderRule(
            { it.contains("gemini-2.5-pro") || it.contains("gemini-2.5-flash") },
            WireLadder.CollapsesAbove(ThinkingLevel.HIGH),
        ),
        // Qwen / DashScope `thinking_budget` (sent at the root AND inside
        // `extra_body`): same shape as Gemini — the budget is clamped against
        // max_completion_tokens and the top rungs all saturate it, so LOW /
        // MEDIUM / HIGH are distinct and nothing above HIGH is.
        LadderRule(
            { it.contains("qwen") },
            WireLadder.CollapsesAbove(ThinkingLevel.HIGH),
        ),
    )

    /**
     * Hyphen-delimited segments of a model id, lowercased, with any
     * `provider/` or `owner/` path prefix kept as its own segment so that a
     * marker in the routing prefix cannot be mistaken for one in the model
     * name. Splitting on the delimiter rather than matching a substring is what
     * lets one rule cover every real spelling of a family marker.
     */
    private fun String.hyphenTokens(): Set<String> =
        lowercase().split('-', '/', '.', '_').filter { it.isNotEmpty() }.toSet()

    /**
     * Complete segments that mark a model as having NO thinking control on any
     * wire path. Add a family by adding one entry here — never by writing a
     * new `endsWith` branch.
     */
    private val NO_CONTROL_TOKENS = setOf(
        "tts",      // spoken output
        "image",    // image generation
        "embedding",
        "embed",
        "vision",
        // Gemini 2.5 Flash-Lite ships no `thinkingConfig` support at all. It is
        // listed here rather than as a `contains("flash-lite")` branch because
        // the id carries a DOT ("gemini-2.5-flash-lite"), so "flash-lite" is
        // not even a token — "lite" is the segment that survives splitting.
        // Every other Gemini tier (pro / flash) DOES take a thinkingBudget, so
        // "lite" must not be broadened into a `contains` test.
        "lite",
    )

    /**
     * Null means "no family opinion" — the generic declared-tier mapping applies
     * unchanged, which is what the OpenAI Chat / Anthropic adaptive families
     * want: their ladder really does top out at whatever the model declares, so
     * `["high","max"]` correctly yields HIGH + MAX, two distinct wire values.
     */
    fun declaredWireLadder(modelId: String): WireLadder? =
        ladderRules.firstOrNull { it.match(modelId.lowercase()) }?.ladder
}

/**
 * [T-android-thinking-level-arch] "How high can this model's thinking go?"
 * resolved through the built-in tiers only (no user override — see
 * [ModelEntry.effectiveMaxThinkingLevel] for that):
 *   1. supportsReasoning == false → OFF (checked BEFORE catalog rules so a
 *      broadened family rule can't lift a non-reasoning member's ceiling).
 *   2. ThinkingLevelCatalog rule.
 *   3. true/null → XHIGH (conservative default so a reasoning model isn't
 *      accidentally capped below the tiers every provider already accepted
 *      pre-GPT-5.6).
 */
val LLMModel.catalogMaxThinkingLevel: ThinkingLevel
    get() {
        // A model that can't reason has max level OFF regardless of any
        // catalog family rule — family rules match by id substring, so a
        // broadened rule (e.g. "mimo" covering mimo-v2.5) must not lift the
        // ceiling of that family's non-reasoning members (mimo-v2.5-tts/-asr).
        // [T-fallback-thinking-preclamp]
        if (supportsReasoning == false) return ThinkingLevel.OFF
        // [T-thinking-levels-data-driven] A declared effort set is a stronger
        // statement than any id-substring rule: it names the exact tiers the
        // backend accepts. Take its top tier as the ceiling so a model whose
        // declaration reaches beyond the hardcoded default (XHIGH) — e.g.
        // zhipuai glm-5.2 / deepseek-v4, both `["high","max"]` — is actually
        // reachable from the UI.
        //
        // Without this the two layers disagreed and the user was pinned to a
        // tier below what they asked for: the catalog declared "max", the wire
        // clamp would have passed "max" straight through, but the picker
        // topped out at the XHIGH default below — so the best a user could
        // request was "xhigh", which is NOT in `["high","max"]`, and
        // clampEffort snapped it DOWN to "high". Field report: deepseek-v4 and
        // GLM could never reach max on Android while iOS could.
        //
        // Deliberately fixes only the CEILING. clampEffort stays exactly as it
        // is — its job is to stop an undeclared tier reaching the backend
        // (which 400s), and loosening it would trade this bug for that one.
        // Mirrors iOS LLMTypes.swift `catalogMaxThinkingLevel` (47dc71b3).
        selectableThinkingLevels.lastOrNull()?.let { return it }
        return ThinkingLevelCatalog.declaredMaxLevel(id) ?: ThinkingLevel.XHIGH
    }

/**
 * [T-thinking-levels-data-driven] The thinking levels worth OFFERING for this
 * model, derived from the catalog's declared effort tiers.
 *
 * The wire path ([com.openminis.app.provider.thinking.ThinkingRuleResolver.clampEffort])
 * snaps any request onto the declared set, so when a model declares a sparse
 * set — `["high","max"]` is the single most common sparse shape in the bundled
 * catalog (79 of the 339 deepseek-v4/glm-5.x entries that declare effort tiers,
 * including official deepseek-v4 and zhipuai glm-5.2) — the generic UI levels
 * collapse onto one or two distinct wire values. The user then drags a slider
 * that provably changes nothing.
 *
 * Returning one level per DISTINCT declared tier makes the picker honest: every
 * option the user can pick produces a different request. Empty when nothing is
 * declared, so the legacy id-rule ceiling still applies.
 *
 * OFF is never included — it is a separate toggle, not an effort tier, and the
 * OFF wire value is chosen by the resolver's off-effort handling, not here.
 * `none`/`minimal` are likewise OFF-ish tiers owned by that toggle.
 *
 * [T-thinking-levels-data-driven] ULTRA is likewise never included, and that is
 * by design rather than an omission: ULTRA is a client-side "MAX +
 * orchestration" concept, never a wire effort (see
 * [com.openminis.app.provider.thinking.ThinkingRuleResolver.wireEffort], where
 * both MAX and ULTRA serialize as `"max"`). A second rung spelling the same wire
 * value would be precisely the lie this function exists to stop telling.
 *
 * The declared tiers say WHICH generic levels are legal; they say nothing about
 * whether those levels produce different bytes. Families whose wire path folds
 * its top rungs together — numeric thinking budgets (Gemini 2.5, Qwen), a
 * per-family tier cap (MiMo/Agnes, ByteDance seed), or no control field at all
 * (`-tts` / `-image` / `-embedding` / `-vision` / `2.5-flash-lite`) — are
 * described in [ThinkingLevelCatalog.declaredWireLadder] and truncated here, at
 * the last rung that is genuinely distinct.
 *
 * Mirrors iOS `LLMModel.selectableThinkingLevels`; the mapping list is kept in
 * the same weakest→strongest order because callers take [lastOrNull] as the
 * ceiling.
 */
val LLMModel.selectableThinkingLevels: List<ThinkingLevel>
    get() {
        val declared = reasoningEffortValues
        if (declared.isNullOrEmpty()) return emptyList()
        val mapping = listOf(
            "low" to ThinkingLevel.LOW,
            "medium" to ThinkingLevel.MEDIUM,
            "high" to ThinkingLevel.HIGH,
            "xhigh" to ThinkingLevel.XHIGH,
            "max" to ThinkingLevel.MAX,
        )
        val set = declared.map { it.lowercase() }.toSet()
        val tiers = mapping.filter { set.contains(it.first) }.map { it.second }
        val ladder = ThinkingLevelCatalog.declaredWireLadder(id) ?:
            ThinkingLevelCatalog.WireLadder.Distinct
        return when (ladder) {
            // Nothing is emitted for this family — every level is the same
            // request, so offering a gradient would be fabricating one. Empty
            // leaves [catalogMaxThinkingLevel] on its legacy id-rule ceiling.
            ThinkingLevelCatalog.WireLadder.NoControl -> emptyList()
            is ThinkingLevelCatalog.WireLadder.CollapsesAbove ->
                tiers.takeWhile { it.rank <= ladder.lastDistinct.rank }
            ThinkingLevelCatalog.WireLadder.Distinct -> tiers
        }
    }

/**
 * [T-android-thinking-level-arch] The four-level resolution the rest of the app
 * consults: the user's manual override on the entry (highest priority) wins over
 * the catalog/default. `entry.model` already folds ModelOverrides into the base
 * model, so read the ceiling off the resolved model there.
 */
val ModelEntry.effectiveMaxThinkingLevel: ThinkingLevel
    get() = overrides.maxThinkingLevel ?: model.catalogMaxThinkingLevel
