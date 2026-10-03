// Tests for [T-thinking-passback-matrix] — backlog item T18, iOS half.
//
// After a tool call, captured reasoning has to go BACK to the model, and
// every protocol wants it somewhere else. Nine field reports (#1, #16, #22,
// #70, #171, #368, #361, #87, #356) were all this one seam. Pins ff60c818a,
// c5efeb1ed, 69be65763, d87a7b151, 20c074454, 275d99d51.
//
// Matrix rows, each snapshotting the request fragment the provider emits:
//   1. OpenAI chat (DeepSeek native): `reasoning_content` is a TOP-LEVEL
//      field of the assistant message, never inside `content[]`.
//   2. Responses API: a `reasoning` item with `encrypted_content` and an
//      always-present `summary` array, placed BEFORE the turn's function_call.
//   3. Anthropic protocol via a DeepSeek-compat proxy: an unsigned
//      `{type:"thinking"}` block spliced at content[0] — and NOT on the
//      official endpoint or for Claude-class models.
//   4. Reasoning captured from another model / family is dropped
//      (Responses: modelId mismatch; Mistral: field forbidden → 422).
//   5. Claude 5 with thinking off → no `thinking.type=disabled` literal
//      (Claude 4.6–4.x still gets it).
//   6. `deepseek-flash` hits the DeepSeek sibling rule, not the generic
//      reasoning_effort default.
//
// Ports:
//   convertSingleMessageChatCompletions (reasoning part) — OpenAIAgentProvider.swift ~L1600
//   flattenChatCompletionsMessages gate                  — OpenAIAgentProvider.swift ~L1346
//   convertMessagesResponsesAPI (reasoning replay)       — OpenAIAgentProvider.swift ~L1731
//   Anthropic echo gate + injectThinkingBlocksForCompatProxy
//                     — AnthropicAgentProvider.swift ~L166 / OAuthHTTPClient.swift ~L1302
//   anthropicThinkingShape + parseClaudeVersion          — ThinkingRuleResolver.swift ~L820 / AnthropicProvider.swift ~L72
//   ThinkingRule.Scope.glob + the DeepSeek rules         — ThinkingRule.swift / ThinkingRuleResolver.swift ~L320
//
// Standalone (`swift ThinkingPassbackMatrixTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func json(_ obj: Any) -> String {
    let d = try! JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    return String(data: d, encoding: .utf8)!
}

// MARK: - Minimal models

enum Role { case user, assistant }
enum Part { case text(String); case toolUse(id: String, name: String, input: [String: Any]); case toolResult(id: String, content: String) }
enum EchoItem { case openaiReasoning(id: String, encrypted: String?, summary: [String]) }
struct ReasoningEcho { let providerKind: String; let modelId: String; let items: [EchoItem] }
struct AgentMessage {
    var role: Role; var parts: [Part]
    var reasoningContent: String? = nil
    var reasoningEcho: ReasoningEcho? = nil
}
enum ThinkingLevel { case off, low, medium, high, xhigh, max; var isEnabled: Bool { self != .off } }
let encryptedReasoningMarker = "Reasoning: "

// MARK: - Row 1/4: OpenAI Chat Completions

struct ChatModel { let supportsReasoning: Bool?; let interleavedReasoningField: String? }

func chatGate(model: ChatModel, thinkingLevel: ThinkingLevel, isMistral: Bool,
              isCerebras: Bool = false) -> (include: Bool, placeholder: Bool, forbid: Bool) {
    let modelAlwaysReasons = model.supportsReasoning == true
    let modelMayReason = model.supportsReasoning ?? true
    let includeReasoning = (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason
    // [T-ios-cerebras-reasoning-400] Mirrors OpenAIAgentProvider:
    //   let forbidReasoningField = provider.isMistral || provider.isCerebras
    let forbid = isMistral || isCerebras
    let placeholderAllowed = includeReasoning && (modelAlwaysReasons || model.interleavedReasoningField != nil)
    return (includeReasoning, placeholderAllowed, forbid)
}

func convertAssistantChat(_ msg: AgentMessage, includeReasoning: Bool, injectPlaceholder: Bool, forbidReasoningField: Bool) -> [String: Any] {
    var result: [String: Any] = ["role": "assistant"]
    if !forbidReasoningField {
        if let rc = msg.reasoningContent {
            result["reasoning_content"] = rc.hasPrefix(encryptedReasoningMarker) ? "" : rc
        } else if includeReasoning, injectPlaceholder {
            result["reasoning_content"] = ""
        }
    }
    let toolUses = msg.parts.compactMap { p -> (String, String, [String: Any])? in if case .toolUse(let id, let n, let i) = p { return (id, n, i) }; return nil }
    if !toolUses.isEmpty {
        result["tool_calls"] = toolUses.map { id, name, input in
            ["id": id, "type": "function", "function": ["name": name, "arguments": json(input)]] as [String: Any]
        }
        let text = msg.parts.compactMap { p -> String? in if case .text(let t) = p { return t }; return nil }.joined()
        if !text.isEmpty { result["content"] = text }
    } else {
        result["content"] = msg.parts.compactMap { p -> [String: Any]? in if case .text(let t) = p { return ["type": "text", "text": t] }; return nil }
    }
    return result
}

// MARK: - Row 2/4: Responses API

let responsesAPIProviderKind = "openai-responses"
func convertAssistantResponses(_ msg: AgentMessage, currentModel: String) -> [[String: Any]] {
    var result: [[String: Any]] = []
    if msg.role == .assistant, let echo = msg.reasoningEcho, echo.providerKind == responsesAPIProviderKind, echo.modelId == currentModel {
        for item in echo.items {
            if case .openaiReasoning(let id, let encrypted, let summary) = item {
                var entry: [String: Any] = ["type": "reasoning", "id": id, "summary": summary.map { ["type": "summary_text", "text": $0] }]
                if let encrypted, !encrypted.isEmpty { entry["encrypted_content"] = encrypted }
                result.append(entry)
            }
        }
    }
    for part in msg.parts {
        switch part {
        case .text(let t): result.append(["role": msg.role == .user ? "user" : "assistant", "content": t])
        case .toolUse(let id, let name, let input): result.append(["type": "function_call", "call_id": id, "name": name, "arguments": json(input)])
        case .toolResult(let id, let content): result.append(["type": "function_call_output", "call_id": id, "output": content])
        }
    }
    return result
}

// MARK: - Row 3: Anthropic protocol via compat proxy

/// The echo gate in AnthropicAgentProvider + the chronological history array.
func anthropicReasoningHistory(_ messages: [AgentMessage], supportsReasoning: Bool?, interleavedField: String?, isOfficial: Bool) -> [String?]? {
    let modelMayReason = supportsReasoning ?? true
    let modelRequiresInterleave = interleavedField != nil
    let echoReasoning = modelMayReason && modelRequiresInterleave
    guard echoReasoning, !isOfficial, messages.contains(where: { $0.role == .assistant }) else { return nil }
    var history: [String?] = []
    var lastRole: Role?
    for msg in messages {
        if msg.role == .assistant {
            if lastRole == .assistant {
                let prev = history.last ?? nil
                let merged: String?
                switch (prev, msg.reasoningContent) {
                case (nil, nil): merged = nil
                case (let p?, nil): merged = p
                case (nil, let r?): merged = r
                case (let p?, let r?): merged = p + "\n" + r
                }
                if !history.isEmpty { history[history.count - 1] = merged }
            } else {
                history.append(msg.reasoningContent)
            }
        }
        lastRole = msg.role
    }
    return history
}

/// RequestBodyPatcher.injectThinkingBlocksForCompatProxy on an on-wire body.
func injectThinkingBlocks(messages: [[String: Any]], history: [String?]?, injectPlaceholder: Bool) -> [[String: Any]] {
    guard history != nil || injectPlaceholder else { return messages }
    let h = history ?? []
    var messages = messages
    var assistantIdx = 0
    for mi in messages.indices {
        guard (messages[mi]["role"] as? String) == "assistant" else { continue }
        defer { assistantIdx += 1 }
        let reasoning: String? = assistantIdx < h.count ? h[assistantIdx] : nil
        let hasReal = reasoning?.isEmpty == false
        if !hasReal && !injectPlaceholder { continue }
        var contentArray: [[String: Any]]
        if let arr = messages[mi]["content"] as? [[String: Any]] { contentArray = arr }
        else if let str = messages[mi]["content"] as? String { contentArray = str.isEmpty ? [] : [["type": "text", "text": str]] }
        else { contentArray = [] }
        if let first = contentArray.first, first["type"] as? String == "thinking" { continue }
        contentArray.insert(["type": "thinking", "thinking": hasReal ? reasoning! : ""], at: 0)
        messages[mi]["content"] = contentArray
    }
    return messages
}

// MARK: - Row 5: Anthropic thinking shape

func parseClaudeVersion(_ modelId: String) -> (major: Int, minor: Int)? {
    let lower = modelId.lowercased()
    guard lower.contains("claude") else { return nil }
    let pattern = #"[-/]?(\d+)(?:[-.](\d+))?(?:\b|[^0-9])"#
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
    let range = NSRange(lower.startIndex..., in: lower)
    guard let match = regex.firstMatch(in: lower, range: range), match.numberOfRanges >= 3,
          let majorRange = Range(match.range(at: 1), in: lower), let major = Int(lower[majorRange]) else { return nil }
    var minor = 0
    if let minorRange = Range(match.range(at: 2), in: lower), let parsed = Int(lower[minorRange]) { minor = parsed }
    return (major, minor)
}
func modelUsesAdaptiveThinking(_ id: String) -> Bool { guard let v = parseClaudeVersion(id) else { return false }; return v.major > 4 || (v.major == 4 && v.minor >= 6) }
func modelAcceptsExplicitThinkingDisabled(_ id: String) -> Bool { guard let v = parseClaudeVersion(id) else { return false }; return v.major == 4 && v.minor >= 6 }
func thinkingEffort(for level: ThinkingLevel) -> String { switch level { case .off, .low: return "low"; case .medium: return "medium"; case .high: return "high"; case .xhigh: return "xhigh"; case .max: return "max" } }
func anthropicThinkingShape(modelId: String, supportsReasoning: Bool?, level: ThinkingLevel, maxTokens: Int) -> [String: Any] {
    let adaptive = modelUsesAdaptiveThinking(modelId)
    if level.isEnabled, supportsReasoning ?? false {
        if adaptive { return ["effort": thinkingEffort(for: level)] }
        return ["budget_tokens": min(32768, maxTokens - 1)]
    }
    return modelAcceptsExplicitThinkingDisabled(modelId) ? ["disabled": true] : [:]
}
/// The wire-side re-derivation in RequestBodyPatcher.injectThinkingConfig.
func wireThinkingField(modelId: String, disabledIntent: Bool) -> [String: Any]? {
    guard disabledIntent, modelUsesAdaptiveThinking(modelId), modelAcceptsExplicitThinkingDisabled(modelId) else { return nil }
    return ["type": "disabled"]
}

// MARK: - Row 6: DeepSeek rule matching

enum Scope { case allModels, modelPattern(String)
    func matches(_ modelId: String) -> Bool {
        switch self {
        case .allModels: return true
        case .modelPattern(let p): return Self.glob(p.lowercased().replacingOccurrences(of: ".", with: "-"), matches: modelId.lowercased().replacingOccurrences(of: ".", with: "-"))
        }
    }
    static func glob(_ pattern: String, matches input: String) -> Bool {
        let parts = pattern.components(separatedBy: "*")
        if parts.count == 1 { return input == pattern }
        var cursor = input.startIndex
        for (i, part) in parts.enumerated() {
            if part.isEmpty { continue }
            if i == 0 { guard input.hasPrefix(part) else { return false }; cursor = input.index(cursor, offsetBy: part.count); continue }
            if i == parts.count - 1 && !pattern.hasSuffix("*") {
                guard input.hasSuffix(part), input.distance(from: cursor, to: input.endIndex) >= part.count else { return false }
                continue
            }
            guard let found = input.range(of: part, range: cursor..<input.endIndex) else { return false }
            cursor = found.upperBound
        }
        return true
    }
}
enum Wire: Equatable { case deepSeekSibling, reasoningEffort, qwenRootOnly }
struct Rule { let scope: Scope; let wire: Wire; let label: String }
/// The built-in registry, reduced to the rules that compete for a DeepSeek id
/// on a plain OpenAI-compatible endpoint (ordering preserved).
let builtIn: [Rule] = [
    Rule(scope: .modelPattern("*qwen*"), wire: .qwenRootOnly, label: "qwen-root-only"),
    Rule(scope: .modelPattern("*deepseek-v4*"), wire: .deepSeekSibling, label: "deepseek-v4-official"),
    Rule(scope: .modelPattern("deepseek-flash*"), wire: .deepSeekSibling, label: "deepseek-flash-official"),
    Rule(scope: .allModels, wire: .reasoningEffort, label: "openai-compatible-default"),
]
func resolve(_ modelId: String) -> Rule { builtIn.first { $0.scope.matches(modelId) }! }
func emit(_ rule: Rule, level: ThinkingLevel) -> [String: Any] {
    var body: [String: Any] = [:]
    switch rule.wire {
    case .deepSeekSibling:
        if level.isEnabled { body["thinking"] = ["type": "enabled"]; body["reasoning_effort"] = "high" } else { body["thinking"] = ["type": "disabled"] }
    case .reasoningEffort: if level.isEnabled { body["reasoning_effort"] = "high" }
    case .qwenRootOnly: body["enable_thinking"] = level.isEnabled
    }
    return body
}

// MARK: - Fixture: a tool round with captured reasoning

let toolTurn = AgentMessage(role: .assistant, parts: [.toolUse(id: "call_1", name: "shell_execute", input: ["command": "ls"])], reasoningContent: "I should list the directory first.")
let toolReply = AgentMessage(role: .user, parts: [.toolResult(id: "call_1", content: "a.txt")])

print("▶️  1. DeepSeek native (chat): reasoning_content is top-level, not in content[]")
do {
    let deepseek = ChatModel(supportsReasoning: true, interleavedReasoningField: "reasoning_content")
    let g = chatGate(model: deepseek, thinkingLevel: .off, isMistral: false)
    check("forced-reasoning model echoes even with the toggle off", g.include && g.placeholder)
    let wire = convertAssistantChat(toolTurn, includeReasoning: g.include, injectPlaceholder: g.placeholder, forbidReasoningField: g.forbid)
    checkEq("snapshot", json(wire),
            #"{"reasoning_content":"I should list the directory first.","role":"assistant","tool_calls":[{"function":{"arguments":"{\"command\":\"ls\"}","name":"shell_execute"},"id":"call_1","type":"function"}]}"#)
    check("no content[] carries the reasoning", wire["content"] == nil)
    // A tool turn captured WITHOUT reasoning still needs the field present.
    var noRc = toolTurn; noRc.reasoningContent = nil
    checkEq("placeholder: empty string, never a fabricated marker", convertAssistantChat(noRc, includeReasoning: true, injectPlaceholder: true, forbidReasoningField: false)["reasoning_content"] as? String, "")
    var empty = toolTurn; empty.reasoningContent = ""
    checkEq("a genuine empty value round-trips as empty", convertAssistantChat(empty, includeReasoning: false, injectPlaceholder: false, forbidReasoningField: false)["reasoning_content"] as? String, "")
    // MiMo shape: unknown capability, toggle off, but the value was captured → still echoed.
    let mimo = ChatModel(supportsReasoning: nil, interleavedReasoningField: nil)
    let gm = chatGate(model: mimo, thinkingLevel: .off, isMistral: false)
    check("presence is the trigger: captured value echoed even when the gate is off", !gm.include && convertAssistantChat(toolTurn, includeReasoning: gm.include, injectPlaceholder: gm.placeholder, forbidReasoningField: false)["reasoning_content"] as? String == toolTurn.reasoningContent)
    check("…but no placeholder is synthesised when the gate is off", convertAssistantChat(noRc, includeReasoning: gm.include, injectPlaceholder: gm.placeholder, forbidReasoningField: false)["reasoning_content"] == nil)
}

print("▶️  2. Responses API: reasoning item replayed before the function_call")
do {
    var turn = toolTurn
    turn.reasoningEcho = ReasoningEcho(providerKind: responsesAPIProviderKind, modelId: "gpt-5.5-codex", items: [.openaiReasoning(id: "rs_1", encrypted: "ENC", summary: ["List first"])])
    let items = convertAssistantResponses(turn, currentModel: "gpt-5.5-codex")
    checkEq("two items", items.count, 2)
    checkEq("snapshot", items.map(json).joined(separator: "\n"),
            #"{"encrypted_content":"ENC","id":"rs_1","summary":[{"text":"List first","type":"summary_text"}],"type":"reasoning"}"# + "\n" +
            #"{"arguments":"{\"command\":\"ls\"}","call_id":"call_1","name":"shell_execute","type":"function_call"}"#)
    checkEq("reasoning precedes the function_call", items[0]["type"] as? String, "reasoning")
    turn.reasoningEcho = ReasoningEcho(providerKind: responsesAPIProviderKind, modelId: "gpt-5.5-codex", items: [.openaiReasoning(id: "rs_2", encrypted: nil, summary: [])])
    let bare = convertAssistantResponses(turn, currentModel: "gpt-5.5-codex")[0]
    check("summary is always an array, even empty", (bare["summary"] as? [Any])?.isEmpty == true)
    check("no encrypted_content key when nothing was captured", bare["encrypted_content"] == nil)
}

print("▶️  3. Anthropic protocol + DeepSeek proxy: interleaved thinking echoed verbatim")
do {
    let msgs = [AgentMessage(role: .user, parts: [.text("ls the dir")]), toolTurn, toolReply,
                AgentMessage(role: .assistant, parts: [.text("done")], reasoningContent: nil)]
    let h = anthropicReasoningHistory(msgs, supportsReasoning: true, interleavedField: "reasoning_content", isOfficial: false)
    checkEq("one entry per assistant turn, chronological", h?.count, 2)
    checkEq("captured reasoning kept verbatim", h?[0], "I should list the directory first.")
    let wireMsgs: [[String: Any]] = [
        ["role": "user", "content": "ls the dir"],
        ["role": "assistant", "content": [["type": "tool_use", "id": "call_1", "name": "shell_execute", "input": ["command": "ls"]]]],
        ["role": "user", "content": [["type": "tool_result", "tool_use_id": "call_1", "content": "a.txt"]]],
        ["role": "assistant", "content": "done"],
    ]
    let patched = injectThinkingBlocks(messages: wireMsgs, history: h, injectPlaceholder: true)
    let a1 = patched[1]["content"] as! [[String: Any]]
    checkEq("thinking block at content[0] of the tool turn", json(a1[0]), #"{"thinking":"I should list the directory first.","type":"thinking"}"#)
    checkEq("tool_use follows it untouched", a1[1]["type"] as? String, "tool_use")
    let a2 = patched[3]["content"] as! [[String: Any]]
    checkEq("a turn without captured reasoning gets an empty placeholder (proxy still 400s otherwise)", json(a2[0]), #"{"thinking":"","type":"thinking"}"#)
    checkEq("string content is materialised into a block array", a2[1]["text"] as? String, "done")
    check("user turns untouched", (patched[0]["content"] as? String) == "ls the dir")
    // Gates.
    check("official api.anthropic.com → no unsigned echo", anthropicReasoningHistory(msgs, supportsReasoning: true, interleavedField: "reasoning_content", isOfficial: true) == nil)
    check("Claude-class model (no interleaved field) → no echo even on a proxy", anthropicReasoningHistory(msgs, supportsReasoning: true, interleavedField: nil, isOfficial: false) == nil)
    check("a non-reasoning model → no echo", anthropicReasoningHistory(msgs, supportsReasoning: false, interleavedField: "reasoning_content", isOfficial: false) == nil)
    // Adjacent assistant messages collapse into one wire turn → one merged entry.
    let adjacent = [AgentMessage(role: .user, parts: [.text("q")]), AgentMessage(role: .assistant, parts: [.text("a")], reasoningContent: "r1"), AgentMessage(role: .assistant, parts: [.text("b")], reasoningContent: "r2")]
    checkEq("adjacent assistant turns merge their reasoning", anthropicReasoningHistory(adjacent, supportsReasoning: true, interleavedField: "x", isOfficial: false) ?? [], ["r1\nr2"])
}

print("▶️  4. reasoning from another model is not sent")
do {
    var turn = toolTurn
    turn.reasoningEcho = ReasoningEcho(providerKind: responsesAPIProviderKind, modelId: "gpt-5.5-codex", items: [.openaiReasoning(id: "rs_1", encrypted: "ENC", summary: [])])
    let switched = convertAssistantResponses(turn, currentModel: "gpt-6-astra")
    check("Responses: a different model's encrypted item is stripped", switched.allSatisfy { ($0["type"] as? String) != "reasoning" })
    turn.reasoningEcho = ReasoningEcho(providerKind: "anthropic", modelId: "gpt-5.5-codex", items: [.openaiReasoning(id: "rs_1", encrypted: "ENC", summary: [])])
    check("Responses: a different provider family's item is stripped", convertAssistantResponses(turn, currentModel: "gpt-5.5-codex").allSatisfy { ($0["type"] as? String) != "reasoning" })
    // Mistral (issue #87): the field is categorically forbidden.
    let g = chatGate(model: ChatModel(supportsReasoning: true, interleavedReasoningField: nil), thinkingLevel: .high, isMistral: true)
    let wire = convertAssistantChat(toolTurn, includeReasoning: g.include, injectPlaceholder: g.placeholder, forbidReasoningField: g.forbid)
    check("Mistral: no reasoning_content even when captured and thinking is on", wire["reasoning_content"] == nil)
    check("…and no placeholder either", convertAssistantChat(AgentMessage(role: .assistant, parts: [.text("x")]), includeReasoning: true, injectPlaceholder: true, forbidReasoningField: true)["reasoning_content"] == nil)
    // The Codex UI-only summary string is neutralised for other providers.
    var codexTurn = toolTurn; codexTurn.reasoningContent = "Reasoning: 512 tokens (encrypted)"
    checkEq("the encrypted-summary placeholder is sent as empty, not as text", convertAssistantChat(codexTurn, includeReasoning: true, injectPlaceholder: true, forbidReasoningField: false)["reasoning_content"] as? String, "")
    // [T-ios-cerebras-reasoning-400] Cerebras (issue #361): same closed assistant
    // schema as Mistral. `400 … property 'messages.N.assistant.reasoning_content'
    // is unsupported` — turn 1 passes, turn 2 onwards always fails.
    let cb = chatGate(model: ChatModel(supportsReasoning: true, interleavedReasoningField: nil),
                      thinkingLevel: .high, isMistral: false, isCerebras: true)
    let cbWire = convertAssistantChat(toolTurn, includeReasoning: cb.include,
                                      injectPlaceholder: cb.placeholder, forbidReasoningField: cb.forbid)
    check("Cerebras: no reasoning_content even when captured and thinking is on",
          cbWire["reasoning_content"] == nil)
    // The regression is specifically multi-turn, so assert the history shape the
    // 400 quoted: an assistant turn carrying captured reasoning must go out bare.
    var cbHistory = AgentMessage(role: .assistant, parts: [.text("turn 1 reply")])
    cbHistory.reasoningContent = "some captured thinking"
    check("Cerebras: a prior assistant turn is emitted without the field",
          convertAssistantChat(cbHistory, includeReasoning: cb.include,
                               injectPlaceholder: cb.placeholder,
                               forbidReasoningField: cb.forbid)["reasoning_content"] == nil)
    check("Cerebras: no placeholder either",
          convertAssistantChat(AgentMessage(role: .assistant, parts: [.text("x")]),
                               includeReasoning: true, injectPlaceholder: true,
                               forbidReasoningField: cb.forbid)["reasoning_content"] == nil)
    // Thinking OFF must not change the answer — the suppression is endpoint-scoped,
    // not level-scoped (the injection below is deliberately level-independent).
    let cbOff = chatGate(model: ChatModel(supportsReasoning: true, interleavedReasoningField: nil),
                         thinkingLevel: .off, isMistral: false, isCerebras: true)
    check("Cerebras: still suppressed with thinking off",
          convertAssistantChat(cbHistory, includeReasoning: cbOff.include,
                               injectPlaceholder: cbOff.placeholder,
                               forbidReasoningField: cbOff.forbid)["reasoning_content"] == nil)

    // CONTROL GROUP — the vendors that 400 when the field is ABSENT must be
    // untouched. This is the regression the narrow endpoint scope exists to
    // prevent (MiMo V2.5 "Param Incorrect"; DeepSeek V4 history validation).
    let mimo = chatGate(model: ChatModel(supportsReasoning: nil, interleavedReasoningField: nil),
                        thinkingLevel: .off, isMistral: false, isCerebras: false)
    checkEq("control: MiMo-style endpoint still round-trips captured reasoning",
            convertAssistantChat(cbHistory, includeReasoning: mimo.include,
                                 injectPlaceholder: mimo.placeholder,
                                 forbidReasoningField: mimo.forbid)["reasoning_content"] as? String,
            "some captured thinking")
    let dsk = chatGate(model: ChatModel(supportsReasoning: true, interleavedReasoningField: "reasoning_content"),
                       thinkingLevel: .high, isMistral: false, isCerebras: false)
    checkEq("control: DeepSeek-style endpoint still gets the placeholder",
            convertAssistantChat(AgentMessage(role: .assistant, parts: [.text("x")]),
                                 includeReasoning: dsk.include, injectPlaceholder: dsk.placeholder,
                                 forbidReasoningField: dsk.forbid)["reasoning_content"] as? String, "")

    // A model the catalog says cannot reason vetoes everything.
    let veto = chatGate(model: ChatModel(supportsReasoning: false, interleavedReasoningField: nil), thinkingLevel: .high, isMistral: false)
    check("supportsReasoning == false vetoes the echo gate", !veto.include && !veto.placeholder)
}

print("▶️  5. Claude 5 with thinking off: no `thinking.type=disabled`")
do {
    checkEq("claude-fable-5 off → empty shape (absent field = adaptive default)", anthropicThinkingShape(modelId: "claude-fable-5", supportsReasoning: true, level: .off, maxTokens: 8192).keys.sorted(), [])
    checkEq("claude-opus-5 off → empty shape", anthropicThinkingShape(modelId: "claude-opus-5", supportsReasoning: true, level: .off, maxTokens: 8192).keys.sorted(), [])
    checkEq("claude-fable-5-1 off → empty shape", anthropicThinkingShape(modelId: "claude-fable-5-1", supportsReasoning: true, level: .off, maxTokens: 8192).keys.sorted(), [])
    checkEq("claude-opus-4-6 off → explicit disabled (server default is ON)", anthropicThinkingShape(modelId: "claude-opus-4-6", supportsReasoning: true, level: .off, maxTokens: 8192).keys.sorted(), ["disabled"])
    checkEq("claude-opus-4.8 (dotted) off → explicit disabled", anthropicThinkingShape(modelId: "anthropic/claude-opus-4.8", supportsReasoning: true, level: .off, maxTokens: 8192).keys.sorted(), ["disabled"])
    checkEq("claude-sonnet-4-5 off → nothing (legacy default is off)", anthropicThinkingShape(modelId: "claude-sonnet-4-5", supportsReasoning: true, level: .off, maxTokens: 8192).keys.sorted(), [])
    check("wire re-derivation agrees: Claude 5 never gets the literal", wireThinkingField(modelId: "claude-fable-5", disabledIntent: true) == nil)
    checkEq("wire re-derivation agrees: 4.6 gets it", json(wireThinkingField(modelId: "claude-opus-4-6", disabledIntent: true)!), #"{"type":"disabled"}"#)
    checkEq("claude 5 ON → adaptive effort", anthropicThinkingShape(modelId: "claude-fable-5", supportsReasoning: true, level: .high, maxTokens: 8192)["effort"] as? String, "high")
    checkEq("claude 4.5 ON → budget", anthropicThinkingShape(modelId: "claude-sonnet-4-5", supportsReasoning: true, level: .medium, maxTokens: 8192).keys.sorted(), ["budget_tokens"])
    check("non-Claude id on the Anthropic protocol (MiniMax-M3) → no disabled literal", anthropicThinkingShape(modelId: "MiniMax-M3", supportsReasoning: true, level: .off, maxTokens: 8192).isEmpty)
}

print("▶️  6. deepseek-flash hits the DeepSeek rule")
do {
    checkEq("deepseek-flash → sibling rule", resolve("deepseek-flash").label, "deepseek-flash-official")
    checkEq("deepseek-flash-lite → sibling rule (prefix glob)", resolve("deepseek-flash-lite").label, "deepseek-flash-official")
    checkEq("deepseek-v4-flash → v4 rule", resolve("deepseek-v4-flash").label, "deepseek-v4-official")
    checkEq("DeepSeek-V4-Flash (case) → v4 rule", resolve("DeepSeek-V4-Flash").label, "deepseek-v4-official")
    checkEq("a relay's vendor-prefixed id does not match the bare prefix rule", resolve("amd/deepseek-flash").label, "openai-compatible-default")
    checkEq("wire: thinking + reasoning_effort as root siblings", json(emit(resolve("deepseek-flash"), level: .high)), #"{"reasoning_effort":"high","thinking":{"type":"enabled"}}"#)
    checkEq("wire: off → explicit disabled (DeepSeek defaults ON)", json(emit(resolve("deepseek-flash"), level: .off)), #"{"thinking":{"type":"disabled"}}"#)
    checkEq("the generic default would have sent only reasoning_effort (the pre-fix shape)", json(emit(builtIn.last!, level: .high)), #"{"reasoning_effort":"high"}"#)
}

print("▶️  7. shipping sources still carry the pinned lines")
do {
    let oai = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    let anth = source("Providers/Anthropic/AnthropicAgentProvider.swift")
    let http = source("Providers/Anthropic/OAuthHTTPClient.swift")
    let ap = source("Providers/Anthropic/AnthropicProvider.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    let op = source("Providers/OpenAI/OpenAIProvider.swift")
    if oai.isEmpty || anth.isEmpty || http.isEmpty || ap.isEmpty || res.isEmpty || op.isEmpty { print("  ⏭  sources not readable") } else {
        check("chat: reasoning_content is a message-level field", oai.contains("result[\"reasoning_content\"] = rc"))
        check("chat: placeholder is an empty string", oai.contains("} else if includeReasoning, injectReasoningPlaceholder {\n                // No reasoning") || oai.contains("result[\"reasoning_content\"] = \"\"\n            }"))
        check("chat: Mistral forbids the field entirely", oai.contains("let forbidReasoningField = provider.isMistral"))
        // [T-responses-reasoning-inherit issue #368] Was: "replay only for the
        // same model and family", pinning `echo.modelId == self.model.id` in the
        // gate's condition list. That test was correct for the old contract and
        // is what the #368 fix deliberately changed: hard-gating on model id
        // dropped the whole reasoning HEAD on a model-group fallback hop while
        // the turn's function_call items were still emitted, which is the shape a
        // validating relay answers with
        //   400 The `reasoning_text` in the thinking mode must be passed back.
        // The family gate stays; identity moved to the minting UPSTREAM, and a
        // mismatch now keeps the item and drops only the undecryptable blob.
        // Full coverage lives in ResponsesReasoningPassbackTests.swift.
        check("Responses: reasoning items replay within the family", oai.contains("echo.providerKind == Self.responsesAPIProviderKind {"))
        check("Responses: the blob is keyed on the minting upstream, not the model id", oai.contains("return recorded == self.reasoningUpstreamIdentity"))
        check("Responses: summary is always emitted", oai.contains("\"summary\": summary.map { [\"type\": \"summary_text\", \"text\": $0] }"))
        check("Anthropic: echo gated on the interleaved field, not the toggle", anth.contains("let echoReasoning           = modelMayReason && modelRequiresInterleave"))
        check("Anthropic: never on the official endpoint", anth.contains("!provider.isOfficialAnthropicEndpoint,"))
        check("patcher splices thinking at content[0]", http.contains("contentArray.insert([\n                \"type\": \"thinking\",\n                \"thinking\": thinkingText,\n            ], at: 0)"))
        check("Claude 5 excluded from the disabled literal (resolver)", res.contains("return AnthropicProvider.modelAcceptsExplicitThinkingDisabled(modelId)\n            ? [\"disabled\": true]\n            : [:]"))
        check("Claude 5 excluded from the disabled literal (wire)", http.contains("AnthropicProvider.modelAcceptsExplicitThinkingDisabled(modelId),"))
        check("accepts-disabled is 4.6 ≤ v < 5", ap.contains("return v.major == 4 && v.minor >= 6"))
        check("deepseek-flash sibling rule registered", res.contains("scope: .modelPattern(\"deepseek-flash*\")"))
        // [T-ios-cerebras-reasoning-400] issue #361.
        check("chat: Cerebras also forbids the field",
              oai.contains("let forbidReasoningField = provider.isMistral || provider.isCerebras"))
        check("the endpoint predicate is host-scoped",
              op.contains("return base.contains(\"cerebras.ai\")"))
        check("cerebras endpoint rule registered", res.contains("label: \"cerebras-official\""))
        // CROSS CASE (cerebras x qwen): the endpoint rule must sit ABOVE the
        // *qwen* model-name rule, or a Cerebras-hosted qwen-3.8-27b is handed
        // enable_thinking. Stage A stops at the first scope match, so position
        // IS the behaviour.
        if let cerebrasIdx = res.range(of: "label: \"cerebras-official\"")?.lowerBound,
           let qwenIdx = res.range(of: ".modelPattern(\"*qwen*\")")?.lowerBound {
            check("cerebras rule outranks the *qwen* rule", cerebrasIdx < qwenIdx)
        } else {
            check("found both the cerebras and qwen rules", false)
        }
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
