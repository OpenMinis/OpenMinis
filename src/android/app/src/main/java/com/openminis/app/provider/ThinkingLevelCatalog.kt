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
        return mapping.filter { set.contains(it.first) }.map { it.second }
    }

/**
 * [T-android-thinking-level-arch] The four-level resolution the rest of the app
 * consults: the user's manual override on the entry (highest priority) wins over
 * the catalog/default. `entry.model` already folds ModelOverrides into the base
 * model, so read the ceiling off the resolved model there.
 */
val ModelEntry.effectiveMaxThinkingLevel: ThinkingLevel
    get() = overrides.maxThinkingLevel ?: model.catalogMaxThinkingLevel
