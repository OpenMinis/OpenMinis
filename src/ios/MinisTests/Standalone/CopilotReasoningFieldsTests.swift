// Tests for [T-copilot-reasoning-fields] — Copilot states its reasoning
// capability in fields the parser did not read, and streams reasoning under a
// delta name the chat-completions branch did not know.
//
// Problem analysis referenced from Android commit 317475ab4, which traced all
// of this on the wire with the debug server. Each claim there was re-verified
// against iOS source before anything was changed here; see section [4], which
// also pins the two claims that turned out NOT to apply to iOS, so a future
// reader does not "fix" them again.
//
// Standalone (`swift CopilotReasoningFieldsTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced: the capability derivation

struct Caps {
    var thinking: Bool? = nil          // supports.thinking — Copilot never sends it
    var adaptiveThinking: Bool? = nil  // capabilities.adaptive_thinking
    var maxThinkingBudget: Int? = nil  // capabilities.max_thinking_budget
    var reasoningEffort: [String]? = nil
}

func derive(_ c: Caps) -> (reasoning: Bool, tiers: [String]?) {
    let tiers = c.reasoningEffort?.filter { !$0.isEmpty }
    let reasoning = (c.thinking ?? false)
        || (c.adaptiveThinking ?? false)
        || ((c.maxThinkingBudget ?? 0) > 0)
        || !(tiers?.isEmpty ?? true)
    return (reasoning, tiers)
}

print("\n[1] The real Copilot payload is recognised")
do {
    // Captured shape, verbatim from the Android investigation.
    let real = Caps(adaptiveThinking: true, maxThinkingBudget: 32000,
                    reasoningEffort: ["low", "medium", "high", "xhigh", "max"])
    let r = derive(real)
    check("a reasoning model reads as capable", r.reasoning)
    checkEq("its declared tiers are carried through", r.tiers,
            ["low", "medium", "high", "xhigh", "max"])

    // This is the regression: the OLD rule read supports.thinking alone.
    let oldRule = real.thinking          // nil
    check("the old rule returned nil — 'unknown'", oldRule == nil)
    // Every thinking gate in the app tests `== true`, so nil behaves as off.
    check("…which every `== true` gate treats as NOT capable", (oldRule ?? false) == false)
}

print("\n[2] Any one signal is enough — they are alternatives, not a conjunction")
do {
    check("adaptive_thinking alone", derive(Caps(adaptiveThinking: true)).reasoning)
    check("max_thinking_budget alone", derive(Caps(maxThinkingBudget: 32000)).reasoning)
    check("reasoning_effort alone", derive(Caps(reasoningEffort: ["high"])).reasoning)
    check("supports.thinking alone still works (kept as fallback)",
          derive(Caps(thinking: true)).reasoning)
}

print("\n[3] A non-reasoning model is not promoted")
do {
    check("nothing declared → not capable", derive(Caps()).reasoning, false)
    check("explicit false stays false", derive(Caps(thinking: false)).reasoning, false)
    check("adaptive_thinking false stays false",
          derive(Caps(adaptiveThinking: false)).reasoning, false)
    check("a zero budget is not a capability",
          derive(Caps(maxThinkingBudget: 0)).reasoning, false)
    check("an EMPTY effort list is not a capability",
          derive(Caps(reasoningEffort: [])).reasoning, false)
    check("a list of empty strings is not a capability",
          derive(Caps(reasoningEffort: ["", ""])).reasoning, false)
    // The result must be a definite false, never nil: nil is what caused the bug.
    checkEq("tiers are nil when undeclared", derive(Caps()).tiers == nil, true)
}

print("\n[4] Stream deltas: which field names carry reasoning")
do {
    // Copilot's actual delta, from the Android capture.
    let copilotDelta: [String: Any] = [
        "content": NSNull(), "role": "assistant",
        "reasoning_text": " this is a standard mod"
    ]
    func collect(_ delta: [String: Any], interleaved: String? = nil) -> String {
        var out = ""
        if let rc = delta["reasoning_content"] as? String { out += rc }
        if let rc = delta["reasoning"] as? String { out += rc }
        if let rc = delta["reasoning_text"] as? String { out += rc }
        if let f = interleaved, f != "reasoning_content", f != "reasoning",
           f != "reasoning_text", let rc = delta[f] as? String { out += rc }
        return out
    }
    checkEq("reasoning_text is collected", collect(copilotDelta), " this is a standard mod")
    checkEq("reasoning_content still works", collect(["reasoning_content": "a"]), "a")
    checkEq("reasoning still works", collect(["reasoning": "b"]), "b")
    // The double-count guard: a model whose interleaved field names one of the
    // three consumed above must not have its delta added twice.
    checkEq("interleaved=reasoning_text is not double counted",
            collect(copilotDelta, interleaved: "reasoning_text"), " this is a standard mod")
    checkEq("interleaved=reasoning is not double counted",
            collect(["reasoning": "b"], interleaved: "reasoning"), "b")
    checkEq("interleaved=reasoning_content is not double counted",
            collect(["reasoning_content": "a"], interleaved: "reasoning_content"), "a")
    // A genuinely different interleaved field still works.
    checkEq("a distinct interleaved field is still collected",
            collect(["reasoning_details": "d"], interleaved: "reasoning_details"), "d")
}

print("\n[5] Shipping sources match these assumptions")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let models = source("Providers/Copilot/CopilotModelsAPI.swift")
let agent  = source("Providers/OpenAI/OpenAIAgentProvider.swift")
let view   = source("Views/Chat/AssistantBlockView.swift")

if models.isEmpty || agent.isEmpty { print("  ⏭  sources not readable") } else {
    // Fix 1 — the parser.
    check("the real fields are decoded",
          models.contains("adaptiveThinking = \"adaptive_thinking\"")
          && models.contains("maxThinkingBudget = \"max_thinking_budget\"")
          && models.contains("reasoningEffort = \"reasoning_effort\""))
    check("capability is no longer `supports?.thinking` alone",
          !models.contains("supportsReasoning: supports?.thinking"))
    check("the effort tiers reach LLMModel so clampEffort can use them",
          models.contains("reasoningEffortValues: effortTiers"))

    // Fix 2 — the stream.
    check("the chat-completions branch reads reasoning_text",
          agent.contains("if let rc = delta[\"reasoning_text\"] as? String {"))
    check("…and the interleaved fallback excludes the names consumed above",
          agent.contains("field != \"reasoning\",") && agent.contains("field != \"reasoning_text\","))

    // Fix 3 — the header layout.
    check("the count is grouped with the chevron, after the Spacer",
          view.contains("HStack(spacing: 4) {"))
    if let sp = view.range(of: "Spacer()\n                // [T-thinking-count-beside-chevron]"),
       let cnt = view.range(of: "charCount > 1000 ? ", range: sp.upperBound..<view.endIndex) {
        check("the count now follows the Spacer", sp.upperBound < cnt.lowerBound)
    } else {
        check("the count now follows the Spacer", false)
    }

    // Claims from the Android commit that do NOT apply to iOS. Pinned so the
    // next reader does not "fix" a bug this platform never had.
    let store = source("Providers/ProviderConfigStore.swift")
    check("iOS model fetch dispatches on (type, credential) — no apiKey gate",
          store.contains("case (.githubCopilot, _):")
          && store.contains("CopilotOAuthManager.shared.validSessionToken"))
    let factory = source("Providers/LLMProviderFactory.swift")
    check("the UA override already refuses to clobber an explicit UA",
          factory.contains("if provider.extraHeaders[\"User-Agent\"] == nil {"))
    check("…and Copilot never routes through it anyway",
          !factory.contains("applyCustomUserAgent(makeCopilotProvider"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
