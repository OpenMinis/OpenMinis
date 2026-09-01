package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * Everything about the endpoint and the model that a rule may look at. Passed explicitly
 * rather than read off the provider so the resolver stays a pure function — which is what
 * makes it testable without a network stub.
 */
data class ThinkingResolveContext(
    val modelId: String,
    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, or null. When
     * set, the resolver prepends this instance's user-authored custom rules (from
     * [ThinkingRuleResolver.customRulesFor]) ABOVE the built-in list. Null → built-ins
     * only, byte-identical to Phase 1.
     */
    val instanceId: String? = null,
    val supportsReasoning: Boolean?,
    val declaredEffortValues: List<String>?,
    /**
     * [OpenMinis#163] The catalog affirmatively declares this model has NO effort
     * tiers (it reasons, but takes no `reasoning_effort`). Distinct from
     * `declaredEffortValues == null`, which also means "the catalog never heard
     * of it" — only the affirmative case suppresses the field. Defaults false so
     * every existing construction site keeps its current behaviour.
     *
     * Only consulted together with [isXAI] — see the skip in the reasoningEffort
     * branch.
     */
    val declaresNoEffortTiers: Boolean = false,
    val level: ThinkingLevel,
    val maxTokens: Int,
    /**
     * Vendor predicates, resolved by the caller from the base URL. The resolver never
     * parses URLs itself — that keeps URL-sniffing in one place and lets Phase 2 replace
     * these with user-authored scopes without touching this file.
     */
    val isOpenRouter: Boolean,
    val usesUnifiedReasoningEffort: Boolean,
    val isMistral: Boolean,
    val isDashScope: Boolean,
    /**
     * [OpenMinis#163] Endpoint is xAI's own API (api.x.ai), not a relay that
     * merely serves grok-named models. Scopes the empty-tier skip to the vendor
     * where the 400 was actually observed. Defaults false so existing
     * construction sites are unchanged.
     */
    val isXAI: Boolean = false,
    /**
     * The vendor's documented off tier, or null to omit the field when thinking is off.
     * Already an ALLOWLIST decision made by the caller (iOS ff60c818).
     */
    val offEffort: String?,
)

/**
 * Why a particular wire shape was chosen. Design §8 / GH OpenMinis#100: the resolved
 * outcome must be inspectable, otherwise a user-editable rule layer just replaces one
 * hidden variable with a more complicated one.
 */
data class ThinkingResolveTrace(
    val matchedRuleLabel: String,
    val matchedRuleKind: ThinkingRule.Kind,
    val formatSource: String,
    val emittedKeys: List<String>,
    val clampedFrom: String? = null,
    val clampedTo: String? = null,
) {
    /** One-line form for `AppLogger("Thinking")`. */
    val logLine: String
        get() = buildList {
            add("rule=$matchedRuleLabel")
            add("kind=$matchedRuleKind")
            add("src=$formatSource")
            if (clampedFrom != null && clampedTo != null && clampedFrom != clampedTo) {
                add("clamp=$clampedFrom->$clampedTo")
            }
            add("keys=[${emittedKeys.sorted().joinToString(",")}]")
        }.joinToString(" ")
}

/**
 * Data-driven replacement for the thinking-parameter if-return chain.
 * Mirrors iOS `ThinkingRuleResolver.swift`.
 *
 * PHASE 1 SCOPE — read before extending:
 *  • Covers the OpenAI-compatible family only. Gemini and Anthropic keep their own
 *    emitters; their formats are declared in [ThinkingWireFormat] so the vocabulary is
 *    complete, but nothing resolves to them here. Wiring them in is Phase 2.
 *  • Built-in rules only. No persistence, no user rules, no UI. [ThinkingRule.Kind.CUSTOM]
 *    and [ThinkingWireFormat.CustomPath] exist so Phase 2 need not change these types.
 *  • Behaviour must stay byte-for-byte identical to the pre-refactor chain. That is not an
 *    aspiration — ThinkingWireGoldenSnapshotTest was generated against the old
 *    implementation and committed before this file existed (fdc28e2b).
 *
 * EVALUATION MODEL (design §4), two stages:
 *  Stage A — walk the rules top to bottom, first scope match wins, stop. Ordering is
 *            priority. The PROVIDER_TYPE_DEFAULT at the bottom has AllModels scope so a
 *            match is guaranteed and stage A can never fall through.
 *  Stage B — a matched rule that leaves wireFormat null defers to the fallback chain.
 *            Kept separate from stage A on purpose: cross-rule field merging would make
 *            "why did this value come from there" unanswerable in a trace.
 */
object ThinkingRuleResolver {

    /**
     * [T-android-thinking-rules-phase2] Process-wide cache of user-authored custom
     * rules, keyed by provider-instance id, each list in stored (priority) order.
     * Mirrors iOS `ThinkingRuleCache`: [apply] is a sync call reached from the
     * provider's request builder, but rules live in Room (async), so the repository
     * publishes them here on load and on every mutation. A cache miss yields an empty
     * list ⇒ built-in-only behaviour, never a wrong shape.
     */
    @Volatile
    private var customRulesCache: Map<String, List<ThinkingRule>> = emptyMap()

    /** Replace the whole cache (called once after the repository loads config). */
    @Synchronized
    fun setAllCustomRules(byInstance: Map<String, List<ThinkingRule>>) {
        customRulesCache = byInstance
    }

    /** Replace one instance's custom rules (called after an add/edit/delete/reorder). */
    @Synchronized
    fun setCustomRules(instanceId: String, rules: List<ThinkingRule>) {
        customRulesCache = customRulesCache.toMutableMap().apply {
            if (rules.isEmpty()) remove(instanceId) else put(instanceId, rules)
        }
    }

    /** This instance's custom rules in priority order, or empty. */
    fun customRulesFor(instanceId: String?): List<ThinkingRule> =
        instanceId?.let { customRulesCache[it] } ?: emptyList()

    /**
     * The vendor rules, in priority order — deliberately not alphabetical. The most
     * specific predicate must be consulted first, and Mistral leads because its rule is a
     * total prohibition that outranks every shape below it.
     */
    fun builtInRules(ctx: ThinkingResolveContext): List<ThinkingRule> = buildList {
        // Mistral — GH OpenMinis#87 / iOS 4592ca9b / 29065ca0. Total prohibition: the
        // request rejects `reasoning` (422 extra_forbidden) and AssistantMessage is a
        // closed schema that rejects `reasoning_content`. Must outrank everything.
        if (ctx.isMistral) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER),
                    label = "mistral-official",
                ),
            )
        }

        // OpenRouter — nested `reasoning:{effort}`, OMIT when off so forced-reasoning
        // backends don't reject `effort:"none"`.
        if (ctx.isOpenRouter) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffortNested(offValue = null),
                    label = "openrouter",
                ),
            )
        }

        // ORDER IS LOAD-BEARING FROM HERE DOWN. It reproduces the pre-refactor `when`
        // chain's evaluation order exactly, which was:
        //     o*/gpt-5* → qwen||isDashScope → self-reasoning skip → generic fallback
        // with `usesUnifiedReasoningEffort` consulted only INSIDE the deepseek-v4 and
        // self-reasoning branches — never by the OpenAI-native or qwen branches.
        //
        // [T-thinking-rules-phase1] The first version of this registry hoisted the
        // unified-gateway rule ABOVE these, which silently changed two real cases: a
        // gpt-5 id on DashScope started emitting `enable_thinking`+`thinking_budget`
        // instead of `reasoning_effort`, and (on iOS, mirrored) a qwen id on
        // Ark/Azure/Venice flipped the other way. That is a user-visible
        // silent-degradation regression of exactly the kind this design exists to
        // prevent. The gateway rule must sit BELOW these two, not above.
        //
        // The golden snapshot did not catch it because every matrix row varied a single
        // dimension; the cross-product rows (qwen×unified, gpt5×dashscope, mimo×unified)
        // were added alongside this fix.

        // OpenAI native o-series / GPT-5.x — root reasoning_effort. Android's original
        // predicate was `startsWith("o") || startsWith("gpt-5")`; the broad "o" prefix is
        // preserved verbatim rather than narrowed to o1/o3/o4, because narrowing it would
        // change behaviour for any id starting with "o" and this phase must not.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("o*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("gpt-5*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )

        // Qwen / DashScope — dual-send + strict budget inequality (25165700, a5a0de20).
        // The old branch was `lid.contains("qwen") || isDashScope` with NO unified guard,
        // so both the id match and the endpoint match keep their native enable_thinking
        // mechanism even when the endpoint is Ark/Azure/Venice.
        if (ctx.isDashScope) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.QwenDual,
                    label = "qwen-dashscope",
                ),
            )
        }
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*qwen*"),
                wireFormat = ThinkingWireFormat.QwenDual,
                label = "qwen-dashscope",
            ),
        )

        // Unified gateways (Volcengine Ark / Azure / Venice) — iOS ba055121 + 84f5c9e1.
        // These re-host third-party families behind one OpenAI surface where thinking is
        // controlled ONLY by root `reasoning_effort`; the vendor-native `thinking:{}`
        // object is not honoured, and on Venice an unknown root key is a hard 400.
        // Registered as ONE concept rather than three flags so they cannot drift apart.
        //
        // Placed AFTER the OpenAI-native and qwen patterns so it claims exactly what the
        // old chain's `usesUnifiedReasoningEffort` checks claimed — the deepseek-v4
        // branch and the self-reasoning families below — and nothing more.
        if (ctx.usesUnifiedReasoningEffort) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                    label = "unified-gateway(ark|azure|venice)",
                ),
            )
        }

        // DeepSeek V4 vendor-native sibling shape (iOS 847822eb, Android df776253). Only
        // when NOT on a unified gateway — that rule above already claimed those.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*deepseek-v4*"),
                wireFormat = ThinkingWireFormat.DeepSeekSibling,
                reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY),
                label = "deepseek-v4-official",
            ),
        )

        // Fallback for the providerType: generic root reasoning_effort, subject to the
        // self-reasoning skip in stage B. AllModels guarantees stage A always matches.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                scope = ThinkingRule.Scope.AllModels,
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-compatible-default",
            ),
        )
    }

    /**
     * Resolve and apply the thinking parameters for one request. The body is mutated in
     * place so the call site stays identical to the function this replaced; the trace is
     * returned for logging.
     */
    fun apply(body: JSONObject, ctx: ThinkingResolveContext): ThinkingResolveTrace {
        val before = body.keys().asSequence().toSet()

        // ---- Stage A: first-match-wins ----
        // [T-android-thinking-rules-phase2] User-authored custom rules (stored order)
        // are prepended above the built-ins, so a custom rule can override a vendor
        // default by matching first — but never remove a built-in. An empty custom
        // list makes `rules` == `builtInRules(ctx)`, byte-identical to Phase 1.
        val rules = customRulesFor(ctx.instanceId) + builtInRules(ctx)
        val winner = rules.firstOrNull { it.scope.matches(ctx.modelId) }
            ?: return ThinkingResolveTrace(
                matchedRuleLabel = "none",
                matchedRuleKind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                formatSource = "no-match",
                emittedKeys = emptyList(),
            )

        // ---- Stage B: fill in what the rule left unspecified ----
        var formatSource = "rule"
        val format = winner.wireFormat ?: run {
            formatSource = "providerTypeDefault"
            ThinkingWireFormat.ReasoningEffort(ctx.offEffort)
        }

        val clamp = emit(format, ctx, body)

        val emitted = body.keys().asSequence().toSet() - before
        return ThinkingResolveTrace(
            matchedRuleLabel = winner.label,
            matchedRuleKind = winner.kind,
            formatSource = formatSource,
            emittedKeys = emitted.toList(),
            clampedFrom = clamp.first,
            clampedTo = clamp.second,
        )
    }

    /**
     * Write the fields for one wire format. Each branch reproduces the corresponding
     * branch of the pre-refactor chain exactly — including its guards, which are the part
     * that carries the field evidence.
     */
    private fun emit(
        format: ThinkingWireFormat,
        ctx: ThinkingResolveContext,
        body: JSONObject,
    ): Pair<String?, String?> {
        val lid = ctx.modelId.lowercase()

        // [T-thinking-off-explicit] Strict-enum families never receive an off tier:
        // sending "minimal" to MiMo/Agnes killed the whole request (iOS c5efeb1e).
        val strictEffortEnum = lid.contains("mimo") || lid.contains("agnes")
        val offEffort = if (strictEffortEnum) null else ctx.offEffort

        return when (format) {
            is ThinkingWireFormat.OmitEverything -> null to null

            is ThinkingWireFormat.ReasoningEffortNested -> {
                if (!ctx.level.isEnabled) return null to null
                val effort = wireEffort(ctx.level)
                body.put("reasoning", JSONObject().put("effort", effort))
                effort to effort
            }

            is ThinkingWireFormat.ReasoningEffort -> {
                val isOpenAINative = lid.startsWith("o") || lid.startsWith("gpt-5")
                if (!ctx.level.isEnabled) {
                    // OFF is a separate dispatch on Android, reproduced verbatim from the
                    // pre-refactor chain. Order matters: OpenAI-native ids send the tier
                    // unconditionally; the self-reasoning families only do so when the
                    // endpoint is a unified gateway OR the model declares that tier; and
                    // everything else sends it unless the model declares a set without it.
                    // Deliberately NOT clamped — clampEffort walks UP when nothing at or
                    // below is declared, so a ["high","max"] model would turn an OFF
                    // request into "high", inverting the user's intent.
                    if (offEffort == null) return null to null
                    val isSelfReasoningFamily = listOf("deepseek", "glm", "kimi", "minimax")
                        .any { lid.contains(it) }
                    when {
                        isOpenAINative -> {
                            body.put("reasoning_effort", offEffort)
                            return offEffort to offEffort
                        }
                        isSelfReasoningFamily -> {
                            if (ctx.usesUnifiedReasoningEffort ||
                                ctx.declaredEffortValues?.contains(offEffort) == true
                            ) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        ctx.supportsReasoning != false -> {
                            if (ctx.declaredEffortValues?.contains(offEffort) != false) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        else -> return null to null
                    }
                }
                if (isOpenAINative) {
                    val effort = clampEffortForModel(wireEffort(ctx.level), lid)
                    body.put("reasoning_effort", effort)
                    return effort to effort
                }
                // Generic path. The legacy self-reasoning skip stays keyed on "declares
                // nothing" rather than family name — iOS 22647505 replaced the
                // id-substring skip-list after GLM behind a relay silently received no
                // thinking field at all.
                val declaresEffort = !ctx.declaredEffortValues.isNullOrEmpty()
                if (!ctx.usesUnifiedReasoningEffort && !declaresEffort &&
                    listOf("deepseek", "glm", "kimi", "minimax").any { lid.contains(it) }
                ) {
                    return null to null
                }
                // [OpenMinis#163] xAI-scoped skip. grok-build-0.1 answers
                // reasoning_effort with "HTTP 400: Model grok-build-0.1 does not
                // support parameter reasoningEffort"; the catalog describes
                // exactly that state as "reasoning": true with
                // "reasoning_options": [] (also true of grok-4.20-0309-reasoning).
                //
                // DELIBERATELY NOT data-driven across all vendors. The same
                // empty-tier shape appears on 2090 bundled catalog entries —
                // relay-hosted Claude, GPT-5, Qwen, and grok behind poe /
                // fastrouter / anyapi — and honouring it everywhere would change
                // the wire format for all of them at once. Omitting the field is
                // arguably more correct for some of those too (Anthropic uses
                // thinking.budget_tokens, not effort), but none of those routes
                // has been verified, so the skip stays at the vendor where the
                // 400 was actually observed. Widening it later is a one-line
                // change to this condition, backed by new evidence.
                //
                // Ordered AFTER the family list on purpose: that list keys on
                // "declares nothing" and must keep firing for relay-hosted
                // deepseek/glm/kimi/minimax ids the catalog is silent about.
                // Unified-effort gateways stay exempt for the same reason as
                // above — they normalize the field and own their model list.
                if (ctx.isXAI && !ctx.usesUnifiedReasoningEffort &&
                    ctx.declaresNoEffortTiers && !declaresEffort
                ) {
                    return null to null
                }
                if (ctx.supportsReasoning == false) return null to null
                val requested = clampEffortForModel(wireEffort(ctx.level), lid)
                val clamped = clampEffort(requested, ctx.declaredEffortValues)
                body.put("reasoning_effort", clamped)
                requested to clamped
            }

            is ThinkingWireFormat.DeepSeekSibling -> {
                if (ctx.level.isEnabled) {
                    val requested = wireEffort(ctx.level)
                    val clamped = clampEffort(requested, ctx.declaredEffortValues)
                    body.put("thinking", JSONObject().put("type", "enabled"))
                    body.put("reasoning_effort", clamped)
                    requested to clamped
                } else {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                    null to null
                }
            }

            is ThinkingWireFormat.QwenDual -> {
                // [T-thinking-rules-phase1] ANDROID-SPECIFIC OFF SEMANTICS — do not
                // "align" this with iOS without a deliberate behaviour change.
                //
                // Android's pre-refactor chain dispatched OFF through a separate block
                // from the enabled path, and in that block Qwen/DashScope hit an early
                // `return` ("keep their native enable_thinking mechanism — never route
                // them through reasoning_effort"), so NOTHING was emitted at OFF. iOS
                // instead falls into its qwen branch at every level and emits
                // `enable_thinking:false` + a null-budget extra_body.
                //
                // Both platforms therefore leave Qwen thinking-off to the vendor default,
                // but by different wire shapes. Phase 1 is a pure refactor, so each
                // platform keeps its own shape and the golden snapshots pin them
                // separately. Unifying them is a real behaviour decision for Phase 2 —
                // and the fact that this discrepancy was invisible until the snapshots
                // existed is itself an argument for having built them.
                if (!ctx.level.isEnabled) return null to null

                val enabled = true
                var budget = when (ctx.level) {
                    ThinkingLevel.LOW -> 4096
                    ThinkingLevel.MEDIUM -> 16384
                    ThinkingLevel.HIGH -> 32768
                    ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 65536
                    ThinkingLevel.OFF -> 0
                }
                if (budget > 0 && ctx.maxTokens > 0) {
                    if (ctx.maxTokens < 2) {
                        budget = 0
                    } else {
                        val margin = maxOf(2048, ctx.maxTokens / 8)
                        val ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))
                        if (budget >= ceiling) budget = ceiling
                    }
                }
                body.put("enable_thinking", enabled)
                if (budget > 0) body.put("thinking_budget", budget)
                body.put(
                    "extra_body",
                    JSONObject().apply {
                        put("enable_thinking", enabled)
                        // ANDROID-SPECIFIC: OMIT the key when there is no budget, rather
                        // than sending an explicit JSON null as iOS does. Preserved
                        // verbatim from the pre-refactor chain; changing it would alter
                        // the request for the pathological maxTokens<2 case.
                        if (budget > 0) put("thinking_budget", budget)
                    },
                )
                null to null
            }

            else -> {
                // Phase 1: declared for vocabulary completeness, never resolved to on this
                // path. Reaching here means the registry named a format the OpenAI emitter
                // cannot produce — a programmer error, not a runtime condition.
                error("ThinkingWireFormat $format is not emitted on the OpenAI path in Phase 1")
            }
        }
    }

    // ---- Gemini / Anthropic (Phase 2 §1) ----
    //
    // These two providers do NOT share the OpenAI body shape — Gemini writes into
    // `generationConfig.thinkingConfig` and Anthropic builds its own `thinking` object —
    // so rather than routing them through [apply], the resolver owns their SHAPE
    // decisions as pure functions and each provider asks for the shape it needs.
    //
    // That still achieves the Phase 2 goal (one place owns every vendor's thinking
    // contract) without pretending three body formats are one. Both functions were lifted
    // verbatim from the provider-local implementations they replace and are pinned
    // byte-for-byte by ThinkingWireGeminiAnthropicSnapshotTest.
    //
    // ⚠️ These intentionally differ from the iOS versions in two places, because the
    // Android originals did: 3.x at MAX/ULTRA falls through to "low" here (iOS: "high"),
    // and unknown/latest ids return null here (iOS: a 128-floor fallback table). Phase 2
    // §1 is a pure refactor — do not "align" them without a deliberate decision.

    /**
     * The `generationConfig.thinkingConfig` object for a Gemini request, or null when the
     * model takes no thinking config at all (specialized -tts/-image/-embedding/-vision
     * modalities, 2.5 Flash Lite, and any id matching none of the families).
     */
    /**
     * [T-gemini37-minimal-400] Gemini 3.x Flash models that reject
     * `thinkingLevel: "minimal"` with a 400 and must use "low" as their OFF floor.
     *
     * Matched by minor version rather than an exact-id list: 3.7 is where Google
     * dropped the level, so anything from 3.7 up is assumed to have dropped it
     * too. Guessing "low" for a model that would have accepted "minimal" costs a
     * slightly higher thinking floor; guessing "minimal" for one that rejects it
     * makes the model unusable outright. The asymmetry decides the default.
     */
    private fun rejectsMinimalLevel(lowerId: String): Boolean {
        val m = Regex("""gemini-3\.(\d+)""").find(lowerId) ?: return false
        val minor = m.groupValues[1].toIntOrNull() ?: return false
        return minor >= 7
    }

    fun geminiThinkingConfig(modelId: String, level: ThinkingLevel): JSONObject? {
        // [T-gemini-tts-thinking-400 / OpenMinis#226] Specialized modalities take
        // precedence over EVERY family rule and over the requested level: these models
        // reject the thinking parameter outright, so sending one is a hard 400
        // ("Thinking level is not supported for this model.").
        //
        // Checked FIRST because these ids also match a family pattern —
        // `gemini-3.1-flash-tts-preview` contains "gemini-3", so any later placement is
        // shadowed. Android previously had no such test at all, so all three Gemini TTS
        // models were unusable; iOS had one but below the family branches, equally dead.
        //
        // Lowercased for the same reason iOS lowercases the whole id: catalog and live
        // API spellings differ in case. The family checks above deliberately keep their
        // original raw-`modelId` form (Phase 2 §1 is a pure refactor).
        val lowerId = modelId.lowercase()
        val noThinkingSuffixes = listOf("-tts", "-image", "-embedding", "-vision")
        if (noThinkingSuffixes.any { lowerId.endsWith(it) || lowerId.contains("$it-") }) {
            return null
        }

        val isGemini3 = modelId.contains("gemini-3")
        val is25Pro = modelId.contains("gemini-2.5-pro")
        val is25Flash = modelId.contains("gemini-2.5-flash") && !modelId.contains("lite")
        val is25FlashLite = modelId.contains("gemini-2.5-flash-lite")

        if (is25FlashLite) return null

        return when {
            isGemini3 -> JSONObject().apply {
                if (level == ThinkingLevel.OFF) {
                    // 3.x cannot fully disable thinking; the floor is the weakest
                    // level the model will accept.
                    //
                    // [T-gemini37-minimal-400] "minimal" is NOT universal across the
                    // 3.x Flash family. Verified on-device (Pixel 4a, Gemini API):
                    // gemini-3-flash-preview / 3.5-flash / 3.6-flash accept it, but
                    // gemini-3.7-flash returns a hard
                    //   400 "Thinking level MINIMAL is not supported for this model."
                    // on EVERY request — 5/5 consecutive, "all fallbacks exhausted".
                    // So with 思考 set to Off, 3.7 Flash was completely unusable, not
                    // merely un-thinking. "low" is accepted by the whole family and is
                    // the same floor 3.x Pro already used, so fall back to it for the
                    // models that reject minimal rather than probing at runtime.
                    val acceptsMinimal = modelId.contains("flash") && !rejectsMinimalLevel(lowerId)
                    put("thinkingLevel", if (acceptsMinimal) "minimal" else "low")
                } else {
                    put(
                        "thinkingLevel",
                        when (level) {
                            ThinkingLevel.LOW -> "low"
                            ThinkingLevel.MEDIUM -> "medium"
                            // MAX/ULTRA were appended to ThinkingLevel after this
                            // branch was written (for GPT-5.6) and fell through the
                            // old `else -> "low"`, silently sending the WEAKEST level
                            // when the user asked for the strongest. Gemini's ladder
                            // tops out at "high", so every tier at or above HIGH maps
                            // there. Latent today — no catalog rule lifts a Gemini
                            // ceiling above XHIGH, so the picker doesn't offer
                            // MAX/ULTRA — but a per-entry maxThinkingLevel override
                            // reaches it, and a future catalog rule would too.
                            ThinkingLevel.HIGH, ThinkingLevel.XHIGH,
                            ThinkingLevel.MAX, ThinkingLevel.ULTRA,
                            -> "high"
                            ThinkingLevel.OFF -> "low" // unreachable; OFF handled above
                        },
                    )
                    put("includeThoughts", true)
                }
            }
            is25Pro -> JSONObject().apply {
                put(
                    "thinkingBudget",
                    when (level) {
                        ThinkingLevel.OFF -> 128 // minimum; 0 is rejected (df8a823d)
                        ThinkingLevel.LOW -> 2048
                        ThinkingLevel.MEDIUM -> 8192
                        ThinkingLevel.HIGH -> 16384
                        ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 32768
                    },
                )
                if (level.isEnabled) put("includeThoughts", true)
            }
            is25Flash -> JSONObject().apply {
                put(
                    "thinkingBudget",
                    when (level) {
                        ThinkingLevel.OFF -> 0
                        ThinkingLevel.LOW -> 1024
                        ThinkingLevel.MEDIUM -> 4096
                        ThinkingLevel.HIGH -> 8192
                        ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 16384
                    },
                )
                if (level.isEnabled) put("includeThoughts", true)
            }
            else -> null
        }
    }

    /**
     * The abstract Anthropic thinking shape, as a small map the caller turns into its
     * `thinking` object:
     *   `{effort:"<tier>"}`   → adaptive thinking (Claude 4.6+)
     *   `{budget_tokens:N}`   → legacy budget thinking (≤4.5)
     *   `{disabled:true}`     → adaptive model at OFF; must be explicit because those
     *                           models think by DEFAULT when no thinking field is sent
     *   `{}`                  → send nothing
     */
    fun anthropicThinkingShape(
        modelId: String,
        supportsReasoning: Boolean?,
        level: ThinkingLevel,
        maxTokens: Int,
    ): Map<String, Any> {
        val adaptive = com.openminis.app.provider.anthropic.AnthropicProvider
            .modelUsesAdaptiveThinking(modelId)
        if (level.isEnabled && supportsReasoning != false) {
            if (adaptive) {
                return mapOf(
                    "effort" to com.openminis.app.provider.anthropic.AnthropicProvider
                        .thinkingEffort(level),
                )
            }
            val budget = com.openminis.app.provider.anthropic.AnthropicProvider
                .thinkingBudget(maxTokens, level)
            return if (budget > 0) mapOf("budget_tokens" to budget) else emptyMap()
        }
        return if (adaptive) mapOf("disabled" to true) else emptyMap()
    }

    /** UI level → wire tier, before any per-model clamp. */
    fun wireEffort(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // ULTRA is a client-side "Max + orchestration" concept and is NEVER a valid
        // server effort string (iOS b38bf3d5).
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    /**
     * [T-android-xhigh-effort-clamp] MiMo/Agnes reject xhigh (400/422); their ladder tops
     * out at high. Matches the FAMILY substring, not one spelling — the live API serves
     * `mimo-v2.5` while docs say `mimo-2.5` (iOS 72968c4f).
     */
    fun clampEffortForModel(effort: String, lid: String): String =
        if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort

    /** Snap a requested tier onto the model's declared set, walking DOWN then up. */
    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }
}
