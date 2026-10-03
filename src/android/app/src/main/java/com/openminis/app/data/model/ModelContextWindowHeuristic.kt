package com.openminis.app.data.model

/**
 * T-ctxslider 54ab8e93: heuristic context-window inference used by the
 * model-group "Limit Context Window" slider when a member's
 * `LLMModel.contextWindow` field is missing or non-positive.
 *
 * IMPORTANT: this is intentionally NOT folded into [LLMModel.contextWindowTokens]
 * — that getter is read by the agent loop / token accounting paths and we
 * don't want to silently overwrite source-of-truth metadata. This helper is
 * used ONLY when computing the slider's "Unlimited" ceiling so that a group
 * whose members lack contextWindow metadata still gets a reasonable cap
 * instead of degrading to the 64K floor.
 *
 * Both functions return the model's own `contextWindow` when it is set, so
 * neither body runs for a model models.dev (or OpenRouter / Codex / Copilot /
 * a manual edit) has already given a window. They only ever see an id no
 * catalog has shipped yet — a just-released model, or a name a relay invented.
 *
 * SEPARATE IMPLEMENTATIONS, MATCHING VALUES. Keeping the two bodies apart is
 * deliberate (above), but where both know a family they must return the same
 * number, and the slider must never exceed the loop: a ceiling above the real
 * window lets a user pick a limit the model will reject. `ContextWindowDefaultsTest`
 * asserts that agreement over every family, so adding a branch to one function
 * and not the other fails there instead of shipping.
 */
internal fun inferContextWindowTokens(model: LLMModel): Int {
    model.contextWindow?.takeIf { it > 0 }?.let { return it }
    val idLower = model.id.lowercase()

    // 1M-class models — match before the generic claude-* / gemini-* fall-throughs.
    val millionClassPatterns = listOf(
        Regex("claude-.*-1m"),
        Regex("claude-opus-4-[5678]"),
        Regex("claude-sonnet-4-[567]"),
    )
    if (millionClassPatterns.any { it.containsMatchIn(idLower) }) return 1_000_000
    if (idLower.contains("gemini-2.5-pro") ||
        idLower.contains("gemini-2.0-pro") ||
        idLower.contains("gemini-1.5-pro") ||
        idLower.contains("gemini-2.5-flash") ||
        idLower.contains("gemini-3-pro") ||
        idLower.contains("gemini-3-flash")
    ) return 1_000_000

    if (idLower.startsWith("claude-") || idLower.contains("/claude-")) return 200_000

    if (idLower.startsWith("gpt-4o") || idLower.contains("/gpt-4o")) return 128_000
    if (idLower.startsWith("gpt-4-turbo") || idLower.contains("/gpt-4-turbo")) return 128_000
    // gpt-5 stays ahead of the bare gpt-4 / codex rows below, matching the
    // loop's order, so `gpt-5.3-codex` is a 400K gpt-5 rather than a 200K codex.
    if (idLower.startsWith("gpt-5") || idLower.contains("/gpt-5")) return 400_000

    // Bare gpt-4 is the 8K original. This row used to say 16_000 — the ONE
    // place the slider sat ABOVE the loop, i.e. offering a ceiling higher than
    // the real window, which is the harmful direction.
    if (idLower.startsWith("gpt-4") || idLower.contains("/gpt-4")) return 8_000
    if (idLower.startsWith("gpt-3.5") || idLower.contains("/gpt-3.5")) return 16_000

    // o-series reasoning models and Codex: 200K on both sides. Absent here
    // before, so an uncatalogued `o4-mini` collapsed to the trailing 128K
    // default and the slider could not be raised to the window the loop uses.
    if (idLower.startsWith("o3") || idLower.contains("/o3")) return 200_000
    if (idLower.startsWith("o4") || idLower.contains("/o4")) return 200_000
    if (idLower.startsWith("codex") || idLower.contains("/codex")) return 200_000

    if (idLower.startsWith("deepseek-") || idLower.contains("/deepseek-")) return 128_000

    // xAI Grok. [T-android-grok-context-underestimate] 33b028477 added this
    // branch to LLMModel.contextWindowTokens but not here, so the slider's
    // "Unlimited" ceiling for an uncatalogued Grok collapsed to the trailing
    // 128K default while the agent loop was already using 256K — a group limit
    // could not be raised to the window the loop actually honours. The two
    // heuristics are deliberately separate implementations (see the header),
    // but where they both know a family they must agree, so the values here
    // mirror the loop's exactly: Grok 2/3 are the 131K generation, Grok 4 and
    // later floor at 256K.
    if (idLower.startsWith("grok") || idLower.contains("/grok")) {
        if (idLower.contains("grok-2") || idLower.contains("grok-3")) return 131_072
        return 256_000
    }

    return 128_000
}
