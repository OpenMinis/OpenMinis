// Tests for [T-copilot-model-filter] (M02) — the Copilot /models response is
// filtered by EXACTLY TWO conditions and nothing else.
//
//   1. `model_picker_enabled == false`  → hidden by account policy.
//   2. `capabilities.type` present and != "chat" → not a conversational model.
//
// Any other field — an unknown capability key, a missing `capabilities` object,
// a missing `name`, `preview: true`, a policy block, an unfamiliar vendor — must
// NOT remove the entry. Pins fe625d5fd: the field case was gpt-6-astra being
// enabled for the account mid-day and never appearing; the risk when loosening
// the refresh gate is that a *filter* eats the newly enabled model instead, and
// that failure looks identical ("no new model") from the outside. There is no
// fallback behind this filter: the built-in Copilot list is deliberately empty
// and models.dev has no api.githubcopilot.com channel, so an over-eager drop is
// a permanent "model missing".
//
// The filter body and the reasoning derivation are reproduced verbatim from
// CopilotModelsAPI.fetchModels (src/ios/Providers/Copilot/CopilotModelsAPI.swift
// ~84-197) because the shipping decode types are `private` and the enclosing
// file pulls in the whole app graph. Section [6] re-reads the shipping source so
// the copy cannot drift.
//
// Standalone (`swift CopilotModelFilterTests.swift`) like its neighbours.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func warn(_ l: String) { print("  ⚠️ KNOWN GAP: \(l)") }

// MARK: - Reproduced decode surface (CopilotModelsAPI.ModelsResponse)

struct Limits: Decodable {
    let maxContextWindowTokens: Int?
    let maxOutputTokens: Int?
    enum CodingKeys: String, CodingKey {
        case maxContextWindowTokens = "max_context_window_tokens"
        case maxOutputTokens = "max_output_tokens"
    }
}
struct Supports: Decodable { let vision: Bool?; let thinking: Bool? }
struct Capabilities: Decodable {
    let type: String?
    let limits: Limits?
    let supports: Supports?
    let adaptiveThinking: Bool?
    let maxThinkingBudget: Int?
    let reasoningEffort: [String]?
    enum CodingKeys: String, CodingKey {
        case type, limits, supports
        case adaptiveThinking = "adaptive_thinking"
        case maxThinkingBudget = "max_thinking_budget"
        case reasoningEffort = "reasoning_effort"
    }
}
struct Entry: Decodable {
    let id: String
    let name: String?
    let modelPickerEnabled: Bool?
    let capabilities: Capabilities?
    enum CodingKeys: String, CodingKey {
        case id, name, capabilities
        case modelPickerEnabled = "model_picker_enabled"
    }
}
struct ModelsResponse: Decodable { let data: [Entry] }

/// What the filter produces, flattened to the fields the picker reads.
struct Selectable: Equatable {
    var id: String
    var displayName: String
    var vision: Bool
    var contextWindow: Int?
    var maxOutputTokens: Int?
    var supportsReasoning: Bool
    var effortTiers: [String]?
}

struct FilterResult {
    var models: [Selectable] = []
    var hiddenByPolicy: [String] = []
    var droppedByType: [String] = []
    var reasoningCapable: [String] = []
}

/// Verbatim port of the `compactMap` in CopilotModelsAPI.fetchModels.
func filter(_ entries: [Entry]) -> FilterResult {
    var out = FilterResult()
    var seen = Set<String>()
    out.models = entries.compactMap { entry in
        guard entry.modelPickerEnabled ?? true else {
            out.hiddenByPolicy.append(entry.id)
            return nil
        }
        if let type = entry.capabilities?.type, type != "chat" {
            out.droppedByType.append("\(entry.id):\(type)")
            return nil
        }
        guard seen.insert(entry.id).inserted else { return nil }
        let supports = entry.capabilities?.supports
        let caps = entry.capabilities
        let effortTiers = caps?.reasoningEffort?.filter { !$0.isEmpty }
        let reasoning: Bool = (supports?.thinking ?? false)
            || (caps?.adaptiveThinking ?? false)
            || ((caps?.maxThinkingBudget ?? 0) > 0)
            || !(effortTiers?.isEmpty ?? true)
        if reasoning { out.reasoningCapable.append(entry.id) }
        return Selectable(id: entry.id,
                          displayName: entry.name ?? entry.id,
                          vision: supports?.vision ?? false,
                          contextWindow: caps?.limits?.maxContextWindowTokens,
                          maxOutputTokens: caps?.limits?.maxOutputTokens,
                          supportsReasoning: reasoning,
                          effortTiers: effortTiers)
    }
    return out
}

func decode(_ json: String) -> [Entry] {
    guard let d = json.data(using: .utf8),
          let parsed = try? JSONDecoder().decode(ModelsResponse.self, from: d) else { return [] }
    return parsed.data
}

// ---------------------------------------------------------------------------

print("\n[1] The two drop conditions, and only those two")

let twoDrops = """
{"data":[
  {"id":"gpt-5","name":"GPT-5","model_picker_enabled":true,"capabilities":{"type":"chat"}},
  {"id":"hidden-preview","name":"Hidden","model_picker_enabled":false,"capabilities":{"type":"chat"}},
  {"id":"text-embedding-3-small","name":"Embed","model_picker_enabled":true,"capabilities":{"type":"embeddings"}}
]}
"""
let r1 = filter(decode(twoDrops))
checkEq("3 entries in, 1 selectable out", r1.models.count, 1)
checkEq("the chat model survives", r1.models.first?.id, "gpt-5")
checkEq("model_picker_enabled=false is recorded as a policy hide", r1.hiddenByPolicy, ["hidden-preview"])
checkEq("a non-chat type is recorded with its type", r1.droppedByType, ["text-embedding-3-small:embeddings"])

print("\n[2] Every other field shape must NOT drop the entry")

// Each of these is a lone entry that must survive. They cover the shapes that
// a future Copilot response change could plausibly introduce.
let survivors: [(String, String)] = [
    ("model_picker_enabled absent (older response shape)",
     #"{"id":"a","name":"A","capabilities":{"type":"chat"}}"#),
    ("capabilities object absent entirely",
     #"{"id":"b","name":"B","model_picker_enabled":true}"#),
    ("capabilities.type absent (unknown shape, not 'not chat')",
     #"{"id":"c","name":"C","capabilities":{"limits":{"max_output_tokens":4096}}}"#),
    ("name absent → id is used as the display name",
     #"{"id":"d","capabilities":{"type":"chat"}}"#),
    ("unknown sibling keys are ignored, not fatal",
     #"{"id":"e","name":"E","preview":true,"policy":{"state":"enabled"},"billing":{"multiplier":0},"capabilities":{"type":"chat","family":"e","tokenizer":"o200k","unknown_new_key":{"x":1}}}"#),
    ("unfamiliar vendor id with no metadata at all",
     #"{"id":"some-vendor/brand-new-2","capabilities":{"type":"chat"}}"#),
    ("supports:{} present but empty",
     #"{"id":"f","name":"F","capabilities":{"type":"chat","supports":{}}}"#),
    ("vision=false does not drop a text model",
     #"{"id":"g","name":"G","capabilities":{"type":"chat","supports":{"vision":false}}}"#),
]
for (label, entryJSON) in survivors {
    let r = filter(decode(#"{"data":["# + entryJSON + "]}"))
    check("survives: \(label)", r.models.count == 1)
}
// Every survivor above must be free of drop bookkeeping too.
for (label, entryJSON) in survivors {
    let r = filter(decode(#"{"data":["# + entryJSON + "]}"))
    check("…and is not recorded as dropped: \(label)",
          r.hiddenByPolicy.isEmpty && r.droppedByType.isEmpty)
}

print("\n[3] type == \"chat\" is the only accepted type; others are named")

for t in ["embeddings", "completions", "image", "moderation", "CHAT"] {
    let r = filter(decode(#"{"data":[{"id":"x","capabilities":{"type":"# + "\"\(t)\"}}]}"))
    // Note: the comparison is case-SENSITIVE in the shipping code, so "CHAT"
    // is dropped. Pinned deliberately — a case-insensitive loosening would be a
    // behaviour change, not a refactor.
    check("type=\(t) is dropped", r.models.isEmpty)
}
let chatOK = filter(decode(#"{"data":[{"id":"x","capabilities":{"type":"chat"}}]}"#))
check("type=chat is kept", chatOK.models.count == 1)

print("\n[4] gpt-6-astra — the field case — passes the filter unchanged")

// Shape taken from the live payload the report was filed against: a brand new
// id, chat type, adaptive thinking, an effort ladder including "max".
let astra = """
{"data":[{
  "id":"gpt-6-astra","name":"GPT-6 Astra","model_picker_enabled":true,
  "capabilities":{
    "type":"chat","family":"gpt-6",
    "limits":{"max_context_window_tokens":400000,"max_output_tokens":128000},
    "supports":{"vision":true,"tool_calls":true,"streaming":true},
    "adaptive_thinking":true,"max_thinking_budget":32000,
    "reasoning_effort":["low","medium","high","xhigh","max"]
  }
}]}
"""
let r4 = filter(decode(astra))
checkEq("gpt-6-astra is selectable", r4.models.count, 1)
let astraModel = r4.models.first
checkEq("id preserved", astraModel?.id, "gpt-6-astra")
checkEq("display name from `name`", astraModel?.displayName, "GPT-6 Astra")
check("vision carried through", astraModel?.vision == true)
checkEq("context window carried through", astraModel?.contextWindow, 400_000)
checkEq("max output carried through", astraModel?.maxOutputTokens, 128_000)
check("reasoning-capable (adaptive_thinking / budget / effort tiers)",
      astraModel?.supportsReasoning == true)
checkEq("effort tiers carried verbatim, including max",
        astraModel?.effortTiers, ["low", "medium", "high", "xhigh", "max"])
checkEq("logged as reasoning-capable", r4.reasoningCapable, ["gpt-6-astra"])

print("\n[5] Reasoning derivation is an OR of four alternative signals")

func reasoningOf(_ capsJSON: String) -> Bool? {
    let r = filter(decode(#"{"data":[{"id":"m","capabilities":{"type":"chat","# + capsJSON + "}}]}"))
    return r.models.first?.supportsReasoning
}
check("supports.thinking=true alone", reasoningOf(#""supports":{"thinking":true}"#) == true)
check("adaptive_thinking=true alone", reasoningOf(#""adaptive_thinking":true"#) == true)
check("max_thinking_budget>0 alone", reasoningOf(#""max_thinking_budget":1"#) == true)
check("reasoning_effort non-empty alone", reasoningOf(#""reasoning_effort":["low"]"#) == true)
check("none of them → definite false, never nil",
      reasoningOf(#""supports":{"vision":true}"#) == false)
check("max_thinking_budget=0 is not a signal", reasoningOf(#""max_thinking_budget":0"#) == false)
check("reasoning_effort=[] is not a signal", reasoningOf(#""reasoning_effort":[]"#) == false)
check("reasoning_effort with only empty strings is filtered away",
      reasoningOf(#""reasoning_effort":["",""]"#) == false)
let emptyTiers = filter(decode(#"{"data":[{"id":"m","capabilities":{"type":"chat","reasoning_effort":[]}}]}"#))
checkEq("…and the tier list is preserved as empty, not nil-ed",
        emptyTiers.models.first?.effortTiers, [])

print("\n[6] Duplicate ids are de-duplicated AFTER both drop checks")

let dupes = """
{"data":[
  {"id":"dup","name":"first","capabilities":{"type":"chat"}},
  {"id":"dup","name":"second","capabilities":{"type":"chat"}},
  {"id":"dup2","name":"hidden-dup","model_picker_enabled":false,"capabilities":{"type":"chat"}},
  {"id":"dup2","name":"visible-dup","capabilities":{"type":"chat"}}
]}
"""
let r6 = filter(decode(dupes))
checkEq("one entry per id", r6.models.count, 2)
checkEq("the FIRST occurrence wins", r6.models.first?.displayName, "first")
// The hidden duplicate is dropped before `seen` is touched, so the visible
// twin later in the list still gets through. A dedup-before-policy ordering
// would have swallowed it.
check("a hidden duplicate does not poison the visible twin",
      r6.models.contains { $0.id == "dup2" && $0.displayName == "visible-dup" })

print("\n[7] Empty / broken payloads degrade to an empty list, not a crash")

checkEq("empty data array", filter(decode(#"{"data":[]}"#)).models.count, 0)
checkEq("garbage is not decodable → no entries", decode("not json").count, 0)
checkEq("missing data key → no entries", decode(#"{"models":[]}"#).count, 0)
checkEq("entry with no id is not decodable → whole payload refused",
        decode(#"{"data":[{"name":"anonymous"}]}"#).count, 0)

print("\n[8] Source-grep drift guard (CopilotModelsAPI.fetchModels)")

let srcPath = "src/ios/Providers/Copilot/CopilotModelsAPI.swift"
var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
while !FileManager.default.fileExists(atPath: root.appendingPathComponent(srcPath).path),
      root.pathComponents.count > 1 { root.deleteLastPathComponent() }
let src = (try? String(contentsOf: root.appendingPathComponent(srcPath), encoding: .utf8)) ?? ""
check("source read", !src.isEmpty)

check("policy drop: `entry.modelPickerEnabled ?? true` (nil means visible)",
      src.contains("guard entry.modelPickerEnabled ?? true else"))
check("type drop is conditional on the key being PRESENT",
      src.contains("if let type = entry.capabilities?.type, type != \"chat\""))
check("the type comparison is against the literal \"chat\"",
      src.contains("type != \"chat\""))
// Exactly two `return nil` drops inside the compactMap, plus the dedup guard.
let bodyRange = src.range(of: "let models: [LLMModel] = parsed.data.compactMap")
let body = bodyRange.map { String(src[$0.lowerBound...]).prefix(2600) } ?? ""
checkEq("exactly three early exits in the filter body (policy, type, dedup)",
        body.components(separatedBy: "return nil").count - 1, 3)
check("dedup happens after both drops", {
    guard let p = body.range(of: "modelPickerEnabled"),
          let t = body.range(of: "type != \"chat\""),
          let d = body.range(of: "seen.insert") else { return false }
    return p.lowerBound < t.lowerBound && t.lowerBound < d.lowerBound
}())
check("no other capability is used as a gate (no `guard` on supports/limits)",
      !body.contains("guard let supports") && !body.contains("guard entry.capabilities"))
check("reasoning is an OR of four signals",
      src.contains("(supports?.thinking ?? false)")
        && src.contains("|| (caps?.adaptiveThinking ?? false)")
        && src.contains("|| ((caps?.maxThinkingBudget ?? 0) > 0)")
        && src.contains("|| !(effortTiers?.isEmpty ?? true)"))
check("both drops are logged with ids so an empty list is diagnosable",
      src.contains("hidden by model_picker_enabled=false")
        && src.contains("dropped by capabilities.type != chat"))
check("an empty post-filter list is logged at error level (no fallback exists)",
      src.contains("model list is EMPTY after filtering"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
