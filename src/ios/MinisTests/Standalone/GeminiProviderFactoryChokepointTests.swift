// Tests for [T-gemini-signature-restore] / [T-gemini3-parallel-thoughtsig] /
// [T-gemini-tts-thinking-400] — backlog item T21, iOS half.
//
// Pins 5f679b45e, 63e39b01d, 1a33fc85f, e7cf8331a, 431d2de5f.
// Issues #155, #179, #226.
//
//   * The agent loop rebuilds its provider in at least five places (initial,
//     three group-fallback branches, model switch during a retry countdown).
//     Gemini thought signatures are per-instance state, so the restore MUST
//     happen at the one factory choke point — asserted from the sources: no
//     concrete `XxxAgentProvider(` construction outside the factory file, and
//     every rebuild site calls `makeAgentProvider`.
//   * Gemini 3.x signs ONE functionCall per parallel batch. Replay is
//     all-or-nothing per assistant message: a batch with any unsigned call
//     is narrated as text in full, never split (400 "Corrupted thought
//     signature").
//   * TTS / image / embedding ids get no thinkingConfig at any level.
//
// Ports: the unsigned-batch scan + part mapping (GeminiAgentProvider.swift
// ~L148–230), geminiThinkingConfig (ThinkingRuleResolver.swift ~L710).
//
// Standalone (`swift GeminiProviderFactoryChokepointTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
let iosRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func source(_ rel: String) -> String {
    (try? String(contentsOf: iosRoot.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func productionSources() -> [(String, String)] {
    var out: [(String, String)] = []
    guard let e = FileManager.default.enumerator(at: iosRoot, includingPropertiesForKeys: nil) else { return out }
    for case let url as URL in e {
        guard url.pathExtension == "swift" else { continue }
        let rel = url.path.replacingOccurrences(of: iosRoot.path + "/", with: "")
        if rel.hasPrefix("MinisTests/") { continue }
        if let s = try? String(contentsOf: url, encoding: .utf8) { out.append((rel, s)) }
    }
    return out
}

print("▶️  1. source grep: every concrete AgentProvider is built inside makeAgentProvider")
do {
    let factoryRel = "Agent/Chat/AIChatViewModel+ProviderFactory.swift"
    let ctor = try! NSRegularExpression(pattern: #"\b(Anthropic|Gemini|OpenAI|Antigravity)AgentProvider\("#)
    var outside: [String] = []
    var insideFactory = 0
    for (rel, text) in productionSources() {
        for line in text.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("//") || trimmed.hasPrefix("///") { continue }
            let hits = ctor.numberOfMatches(in: line, range: NSRange(line.startIndex..., in: line))
            guard hits > 0 else { continue }
            if rel == factoryRel { insideFactory += hits } else { outside.append("\(rel): \(trimmed)") }
        }
    }
    checkEq("no constructor call outside the factory file", outside, [])
    check("the factory builds all four concrete providers", insideFactory >= 4)
    let factory = source(factoryRel)
    check("the instance factory hands the session's signatures to every provider it builds", factory.contains("applyPendingThoughtSignatures(to: provider)"))
    check("…for Gemini and Antigravity alike", factory.contains("gemini.restoreToolCallMetadata(pendingThoughtSignatures)") && factory.contains("antigravity.restoreToolCallMetadata(pendingThoughtSignatures)"))
    check("the pending map is NOT cleared on apply (a later rebuild needs it too)", factory.contains("Deliberately does NOT clear `pendingThoughtSignatures`"))
    // Every rebuild site goes through the choke point.
    let fb = source("Agent/Chat/AIChatViewModel+Fallback.swift")
    let vm = source("Agent/Chat/AIChatViewModel.swift")
    checkEq("three group-fallback rebuilds", fb.components(separatedBy: "currentProvider = await makeAgentProvider(for: nextEntry)").count - 1, 3)
    check("retry-countdown model switch rebuilds via the factory", fb.contains("let newProvider = await makeAgentProvider(for: entry)"))
    check("the loop's initial provider comes from the factory", vm.contains("var provider = await makeAgentProvider(for: entry)"))
    check("mid-loop model switch too", vm.contains("provider = await makeAgentProvider(for: newEntry)"))
    // The gap this closes: a fresh provider used to start with an empty map.
    check("the map is per-instance state (why the choke point matters)", source("Providers/Gemini/GeminiAgentProvider.swift").contains("func restoreToolCallMetadata("))
}

// MARK: - Port: all-or-nothing batch signature handling

enum Part: Equatable { case text(String); case toolUse(id: String, name: String); case toolResult(id: String, content: String) }
struct Msg { let role: String; let parts: [Part] }
enum WirePart: Equatable { case text(String); case functionCall(name: String, signed: Bool); case functionResponse(name: String); case narratedCall(name: String); case narratedResult(name: String) }

func convert(_ messages: [Msg], modelId: String, signatures: [String: String]) -> [[WirePart]] {
    var toolNameMap: [String: String] = [:]
    for m in messages { for p in m.parts { if case .toolUse(let id, let name) = p { toolNameMap[id] = name } } }
    let requiresSig = modelId.lowercased().contains("gemini-3")
    var unsigned: Set<String> = []
    if requiresSig {
        for m in messages {
            var ids: [String] = []; var anyUnsigned = false
            for p in m.parts { if case .toolUse(let id, _) = p { ids.append(id); if signatures[id] == nil { anyUnsigned = true } } }
            if anyUnsigned { unsigned.formUnion(ids) }
        }
    }
    return messages.map { m in
        m.parts.compactMap { p -> WirePart? in
            switch p {
            case .text(let t): return t.isEmpty ? nil : .text(t)
            case .toolUse(let id, let name):
                return unsigned.contains(id) ? .narratedCall(name: name) : .functionCall(name: name, signed: signatures[id] != nil)
            case .toolResult(let id, _):
                let name = toolNameMap[id] ?? "unknown"
                return unsigned.contains(id) ? .narratedResult(name: name) : .functionResponse(name: name)
            }
        }
    }
}

let batch = [Msg(role: "user", parts: [.text("do three things")]),
             Msg(role: "model", parts: [.toolUse(id: "a", name: "subagent_task"), .toolUse(id: "b", name: "subagent_task"), .toolUse(id: "c", name: "subagent_task")]),
             Msg(role: "user", parts: [.toolResult(id: "a", content: "1"), .toolResult(id: "b", content: "2"), .toolResult(id: "c", content: "3")])]

print("▶️  2. a parallel batch with only its first call signed is handled as a whole")
do {
    let onlyFirst = convert(batch, modelId: "gemini-3-pro-preview", signatures: ["a": "SIG"])
    let calls = onlyFirst[1]
    check("no functionCall part survives (would be a split batch → 400)", !calls.contains { if case .functionCall = $0 { return true }; return false })
    checkEq("all three are narrated as text", calls, [.narratedCall(name: "subagent_task"), .narratedCall(name: "subagent_task"), .narratedCall(name: "subagent_task")])
    checkEq("…and their results too", onlyFirst[2], [.narratedResult(name: "subagent_task"), .narratedResult(name: "subagent_task"), .narratedResult(name: "subagent_task")])
    let fully = convert(batch, modelId: "gemini-3-pro-preview", signatures: ["a": "S1", "b": "S2", "c": "S3"])
    checkEq("a fully-signed batch keeps the fast path", fully[1], [.functionCall(name: "subagent_task", signed: true), .functionCall(name: "subagent_task", signed: true), .functionCall(name: "subagent_task", signed: true)])
    checkEq("…with functionResponse parts", fully[2], [.functionResponse(name: "subagent_task"), .functionResponse(name: "subagent_task"), .functionResponse(name: "subagent_task")])
    // Per-message: an older unsigned turn does not drag down a later signed one.
    let two = batch + [Msg(role: "model", parts: [.toolUse(id: "d", name: "file_read")]), Msg(role: "user", parts: [.toolResult(id: "d", content: "x")])]
    let mixed = convert(two, modelId: "gemini-3-flash", signatures: ["d": "S4"])
    check("older unsigned batch narrated", mixed[1].allSatisfy { if case .narratedCall = $0 { return true }; return false })
    checkEq("later fully-signed turn replays as a real functionCall", mixed[3], [.functionCall(name: "file_read", signed: true)])
    // Non-3.x models never require signatures.
    let g25 = convert(batch, modelId: "gemini-2.5-pro", signatures: [:])
    check("gemini-2.5: unsigned calls replay as functionCalls", g25[1].allSatisfy { if case .functionCall(_, false) = $0 { return true }; return false })
    // The per-call rule (pre-fix), for contrast.
    func perCall(_ sigs: [String: String]) -> [WirePart] {
        batch[1].parts.compactMap { p in if case .toolUse(let id, let n) = p { return sigs[id] == nil ? .narratedCall(name: n) : .functionCall(name: n, signed: true) }; return nil }
    }
    let split = perCall(["a": "SIG"])
    check("PRE-FIX: the per-call rule split the batch (1 real call + 2 narrated)", split.filter { if case .functionCall = $0 { return true }; return false }.count == 1)
    // Rebuilt provider with an EMPTY map (the bug the choke point fixes) downgrades everything.
    let rebuiltEmpty = convert(batch, modelId: "gemini-3-pro-preview", signatures: [:])
    check("a provider rebuilt with an empty map narrates a fully-signable history", rebuiltEmpty[1].allSatisfy { if case .narratedCall = $0 { return true }; return false })
}

// MARK: - Port: geminiThinkingConfig

enum Level { case off, low, medium, high, xhigh, max; var isEnabled: Bool { self != .off } }
func geminiThinkingConfig(modelId: String, level: Level) -> [String: Any] {
    let id = modelId.lowercased()
    let noThinkingSuffixes = ["-tts", "-image", "-embedding", "-vision"]
    if noThinkingSuffixes.contains(where: { id.hasSuffix($0) || id.contains("\($0)-") }) { return [:] }
    if level.isEnabled {
        if id.contains("gemini-3") {
            let l: String = { switch level { case .off: return "minimal"; case .low: return "low"; case .medium: return "medium"; default: return "high" } }()
            return ["thinkingLevel": l, "includeThoughts": true]
        }
        if id.contains("2.5-pro") { return ["thinkingBudget": 8192, "includeThoughts": true] }
        if id.contains("2.5-flash") && !id.contains("lite") { return ["thinkingBudget": 4096, "includeThoughts": true] }
        return ["thinkingBudget": 4096, "includeThoughts": true]
    }
    if id.contains("gemini-3") { return id.contains("flash") ? ["thinkingLevel": "minimal"] : ["thinkingLevel": "low"] }
    if id.contains("2.5-pro") { return ["thinkingBudget": 128] }
    if id.contains("2.5-flash-lite") { return [:] }
    return ["thinkingBudget": 0]
}

print("▶️  3. audio_output / TTS models get no thinkingConfig at any level")
do {
    for id in ["gemini-3.1-flash-tts-preview", "gemini-2.5-pro-preview-tts", "gemini-2.5-flash-preview-tts", "gemini-3-pro-image-preview", "gemini-embedding-001"] {
        for level in [Level.off, .low, .high, .max] {
            check("\(id) @\(level) → {}", geminiThinkingConfig(modelId: id, level: level).isEmpty)
        }
    }
    // The family rule that used to shadow it.
    check("a 3.x text model still gets a thinkingLevel", geminiThinkingConfig(modelId: "gemini-3.1-flash", level: .off)["thinkingLevel"] as? String == "minimal")
    check("`-tts-` in the middle of an id also matches", geminiThinkingConfig(modelId: "gemini-3-tts-preview", level: .high).isEmpty)
    // The declared-modality gate on the provider side.
    let gp = source("Providers/Gemini/GeminiProvider.swift")
    check("systemInstruction is dropped for audioOutput models too", gp.contains("model.capabilities.supportedModalities.contains(.audioOutput)"))
    check("responseModalities AUDIO for audioOutput", gp.contains("config[\"responseModalities\"] = [\"AUDIO\"]"))
}

print("▶️  4. shipping sources still carry the pinned lines")
do {
    let g = source("Providers/Gemini/GeminiAgentProvider.swift")
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    if g.isEmpty || res.isEmpty { print("  ⏭  sources not readable") } else {
        check("signature requirement keyed on gemini-3", g.contains("let requiresSig = model.id.lowercased().contains(\"gemini-3\")"))
        check("unit is the message: anyUnsigned condemns the whole batch", g.contains("if anyUnsigned { unsignedToolCallIds.formUnion(idsInMessage) }"))
        check("unsigned calls are narrated, not dropped", g.contains("parts.append([\"text\": Self.narratedToolCall(name: name, input: input)])"))
        check("…and so are their results", g.contains("parts.append([\"text\": Self.narratedToolResult(name: resolvedName, content: content)])"))
        check("specialized-modality suffix list is checked FIRST", res.contains("let noThinkingSuffixes = [\"-tts\", \"-image\", \"-embedding\", \"-vision\"]"))
        check("…before the level check", res.range(of: "noThinkingSuffixes.contains(where:")!.lowerBound < res.range(of: "if level.isEnabled {\n            if id.contains(\"gemini-3\") {")!.lowerBound)
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
