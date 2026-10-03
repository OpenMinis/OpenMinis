// Tests for [T-toolcall-delta-accumulation] — backlog item T20, iOS half.
//
// Four providers reported the same symptom ("the model sent full arguments,
// the app received {}"): #61, #83, #146, #310. The SSE reducer in the OpenAI
// Chat Completions path must
//   * key tool calls by `index`, not by arrival position;
//   * never let a delta chunk carrying id:"" / name:"" (DashScope, DeepSeek
//     V4 Flash) overwrite the accumulator entry started by the first chunk;
//   * concatenate `arguments` fragments across chunks;
//   * ignore an empty-string finish_reason (intern-s2) so the call is not
//     flushed before its arguments finished;
//   * NOT silently turn a cut-off argument stream into valid JSON — an
//     unparsable tail must surface as empty args plus a "truncated"
//     diagnosis, so the preflight/repair layer can decide.
//
// Port of the `tool_calls` delta handler and the finish_reason flush,
// OpenAIAgentProvider.swift ~L359–433, plus the diagEmptyToolArgs
// classification ~L2083.
//
// Standalone (`swift ToolCallDeltaAccumulationTests.swift`) like its neighbours.
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

// MARK: - Port of the reducer

enum Event: Equatable {
    case start(id: String, name: String)
    case delta(name: String, accumulated: String)
    case complete(id: String, name: String, argsJSON: String, rawJSON: String)
    case done(reason: String)
}

func parseJsonToDict(_ s: String) -> [String: Any] {
    guard let data = s.data(using: .utf8), let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
    return dict
}
func canonical(_ d: [String: Any]) -> String {
    guard !d.isEmpty, let data = try? JSONSerialization.data(withJSONObject: d, options: [.sortedKeys]) else { return "{}" }
    return String(data: data, encoding: .utf8)!
}

/// diagEmptyToolArgs' three-way classification.
enum EmptyArgsKind: Equatable { case noDeltaEverArrived, literalEmptyObject, truncatedMidJSON, schemaMismatch }
func classifyEmptyArgs(raw: String) -> EmptyArgsKind {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return .noDeltaEverArrived }
    if trimmed == "{}" { return .literalEmptyObject }
    let parseOk = (try? JSONSerialization.jsonObject(with: Data(raw.utf8))) != nil
    return parseOk ? .schemaMismatch : .truncatedMidJSON
}

struct Reducer {
    var toolCallAccum: [Int: (id: String, name: String, json: String)] = [:]
    var hasToolCalls = false
    var events: [Event] = []
    var emptyArgsDiagnoses: [(tool: String, kind: EmptyArgsKind)] = []

    /// One SSE chunk's `choices[0].delta` + `finish_reason`.
    mutating func consume(delta: [String: Any], finishReason: String?) {
        if let toolCalls = delta["tool_calls"] as? [[String: Any]] {
            for tc in toolCalls {
                guard let index = tc["index"] as? Int else { continue }
                let fn = tc["function"] as? [String: Any] ?? [:]
                if let id = tc["id"] as? String, !id.isEmpty,
                   let name = fn["name"] as? String, !name.isEmpty {
                    hasToolCalls = true
                    toolCallAccum[index] = (id: id, name: name, json: "")
                    events.append(.start(id: id, name: name))
                }
                if let argDelta = fn["arguments"] as? String, var entry = toolCallAccum[index] {
                    entry.json += argDelta
                    toolCallAccum[index] = entry
                    events.append(.delta(name: entry.name, accumulated: entry.json))
                }
            }
        }
        if let fr = finishReason, !fr.isEmpty {
            for (_, entry) in toolCallAccum.sorted(by: { $0.key < $1.key }) {
                let args = parseJsonToDict(entry.json)
                if args.isEmpty { emptyArgsDiagnoses.append((entry.name, classifyEmptyArgs(raw: entry.json))) }
                events.append(.complete(id: entry.id, name: entry.name, argsJSON: canonical(args), rawJSON: entry.json))
            }
            toolCallAccum.removeAll()
            let reason: String
            switch fr {
            case "tool_calls": reason = "toolUse"
            case "length": reason = "maxTokens"
            default: reason = hasToolCalls ? "toolUse" : "endTurn"
            }
            events.append(.done(reason: reason))
        }
    }

    var completions: [Event] { events.filter { if case .complete = $0 { return true }; return false } }
}

func tc(_ index: Int, id: String? = nil, name: String? = nil, args: String? = nil) -> [String: Any] {
    var d: [String: Any] = ["index": index]
    if let id { d["id"] = id }
    var fn: [String: Any] = [:]
    if let name { fn["name"] = name }
    if let args { fn["arguments"] = args }
    d["function"] = fn
    return d
}

print("▶️  1. empty id/name on later chunks must not overwrite the first (DashScope / DeepSeek V4 Flash)")
do {
    var r = Reducer()
    r.consume(delta: ["tool_calls": [tc(0, id: "call_abc", name: "shell_execute", args: "")]], finishReason: nil)
    r.consume(delta: ["tool_calls": [tc(0, id: "", name: "", args: "{\"tool_title\": \"ls\",")]], finishReason: nil)
    r.consume(delta: ["tool_calls": [tc(0, id: "", name: "", args: " \"command\": \"ls -la\"}")]], finishReason: nil)
    r.consume(delta: [:], finishReason: "tool_calls")
    checkEq("exactly one call completed", r.completions.count, 1)
    checkEq("id and name from the first chunk survive", r.completions.first, .complete(id: "call_abc", name: "shell_execute", argsJSON: #"{"command":"ls -la","tool_title":"ls"}"#, rawJSON: #"{"tool_title": "ls", "command": "ls -la"}"#))
    checkEq("only one start event (no phantom new call per delta)", r.events.filter { if case .start = $0 { return true }; return false }.count, 1)
    // The pre-fix reducer, for contrast: `"" as? String` binds, so each delta restarted the entry.
    struct Buggy {
        var acc: [Int: (id: String, name: String, json: String)] = [:]
        mutating func consume(_ t: [String: Any]) {
            let index = t["index"] as! Int; let fn = t["function"] as? [String: Any] ?? [:]
            if let id = t["id"] as? String, let name = fn["name"] as? String { acc[index] = (id, name, "") }
            if let a = fn["arguments"] as? String, var e = acc[index] { e.json += a; acc[index] = e }
        }
    }
    var b = Buggy()
    b.consume(tc(0, id: "call_abc", name: "shell_execute", args: ""))
    b.consume(tc(0, id: "", name: "", args: "{\"tool_title\": \"ls\","))
    b.consume(tc(0, id: "", name: "", args: " \"command\": \"ls -la\"}"))
    check("PRE-FIX: id/name wiped and arguments restarted on every delta", b.acc[0]?.id == "" && b.acc[0]?.json == " \"command\": \"ls -la\"}")
    // OpenAI style: id/name simply absent after the first chunk.
    var o = Reducer()
    o.consume(delta: ["tool_calls": [tc(0, id: "call_1", name: "file_read", args: "")]], finishReason: nil)
    o.consume(delta: ["tool_calls": [tc(0, args: "{\"path\":")]], finishReason: nil)
    o.consume(delta: ["tool_calls": [tc(0, args: "\"/tmp/a\"}")]], finishReason: nil)
    o.consume(delta: [:], finishReason: "tool_calls")
    checkEq("absent id/name after the first chunk also accumulates", o.completions.first, .complete(id: "call_1", name: "file_read", argsJSON: #"{"path":"\/tmp\/a"}"#, rawJSON: #"{"path":"/tmp/a"}"#))
}

print("▶️  2. two parallel calls (index 0 / 1) interleaved → each complete, ordered by index")
do {
    var r = Reducer()
    r.consume(delta: ["tool_calls": [tc(1, id: "call_b", name: "file_read", args: "")]], finishReason: nil)   // index 1 arrives first
    r.consume(delta: ["tool_calls": [tc(0, id: "call_a", name: "shell_execute", args: "")]], finishReason: nil)
    r.consume(delta: ["tool_calls": [tc(0, args: "{\"tool_title\":\"t\","), tc(1, args: "{\"path\":")]], finishReason: nil)
    r.consume(delta: ["tool_calls": [tc(1, args: "\"/x\"}")]], finishReason: nil)
    r.consume(delta: ["tool_calls": [tc(0, args: "\"command\":\"pwd\"}")]], finishReason: nil)
    r.consume(delta: [:], finishReason: "tool_calls")
    checkEq("two completions", r.completions.count, 2)
    checkEq("sorted by index, not arrival", r.completions, [
        .complete(id: "call_a", name: "shell_execute", argsJSON: #"{"command":"pwd","tool_title":"t"}"#, rawJSON: #"{"tool_title":"t","command":"pwd"}"#),
        .complete(id: "call_b", name: "file_read", argsJSON: #"{"path":"\/x"}"#, rawJSON: #"{"path":"/x"}"#),
    ])
    check("no cross-contamination between the two argument buffers", r.completions.allSatisfy { if case .complete(_, _, _, let raw) = $0 { return !raw.contains("pwd") || !raw.contains("path") }; return false })
    check("delta events name the right tool for each index", r.events.contains(.delta(name: "file_read", accumulated: "{\"path\":")) && r.events.contains(.delta(name: "shell_execute", accumulated: "{\"tool_title\":\"t\",")))
}

print("▶️  3. arguments split over 5 fragments → parses")
do {
    let full = #"{"tool_title":"count","command":"wc -l /var/minis/workspace/notes.md","timeout":30}"#
    let pieces = stride(from: 0, to: full.count, by: max(1, full.count / 5 + 1)).map { start -> String in
        let s = full.index(full.startIndex, offsetBy: start)
        let e = full.index(s, offsetBy: min(full.count / 5 + 1, full.distance(from: s, to: full.endIndex)))
        return String(full[s..<e])
    }
    check("fixture really is 5 fragments", pieces.count == 5 && pieces.joined() == full)
    var r = Reducer()
    r.consume(delta: ["tool_calls": [tc(0, id: "call_5", name: "shell_execute")]], finishReason: nil)
    for p in pieces { r.consume(delta: ["tool_calls": [tc(0, args: p)]], finishReason: nil) }
    r.consume(delta: [:], finishReason: "tool_calls")
    guard case .complete(_, _, let args, let raw)? = r.completions.first else { check("completed", false); exit(1) }
    checkEq("raw is the exact concatenation", raw, full)
    checkEq("parsed", args, #"{"command":"wc -l \/var\/minis\/workspace\/notes.md","timeout":30,"tool_title":"count"}"#)
    check("no empty-args diagnosis", r.emptyArgsDiagnoses.isEmpty)
    // Multi-byte content split at an arbitrary byte-safe character boundary still joins.
    var m = Reducer()
    m.consume(delta: ["tool_calls": [tc(0, id: "c", name: "file_write")]], finishReason: nil)
    for p in ["{\"path\":\"/x\",\"content\":\"中", "文内容 ✅", "\"}"] { m.consume(delta: ["tool_calls": [tc(0, args: p)]], finishReason: nil) }
    m.consume(delta: [:], finishReason: "stop")
    check("CJK/emoji fragments join intact", { if case .complete(_, _, let a, _)? = m.completions.first { return a.contains("中文内容 ✅") }; return false }())
}

print("▶️  4. a stream cut mid-arguments is flagged truncated, not repaired into valid JSON")
do {
    var r = Reducer()
    r.consume(delta: ["tool_calls": [tc(0, id: "call_t", name: "file_write")]], finishReason: nil)
    r.consume(delta: ["tool_calls": [tc(0, args: "{\"path\":\"/x\",\"content\":\"import os\\nimport sy")]], finishReason: nil)
    r.consume(delta: [:], finishReason: "length")
    guard case .complete(_, _, let args, let raw)? = r.completions.first else { check("completed", false); exit(1) }
    checkEq("args are EMPTY (strict parse), never a silently closed object", args, "{}")
    check("the raw tail is preserved for the repair/refusal layer", raw.hasSuffix("import sy"))
    checkEq("diagnosed as truncated mid-JSON", r.emptyArgsDiagnoses.map(\.kind), [.truncatedMidJSON])
    checkEq("stop reason is maxTokens", r.events.last, .done(reason: "maxTokens"))
    checkEq("no delta ever arrived → distinct diagnosis", classifyEmptyArgs(raw: ""), .noDeltaEverArrived)
    checkEq("literal {} → distinct diagnosis", classifyEmptyArgs(raw: "{}"), .literalEmptyObject)
    checkEq("valid JSON with no keys we expect → schema mismatch", classifyEmptyArgs(raw: "{\"foo\":1}"), .schemaMismatch)
}

print("▶️  5. an empty-string finish_reason does not flush early (intern-s2)")
do {
    var r = Reducer()
    r.consume(delta: ["tool_calls": [tc(0, id: "call_i", name: "shell_execute", args: "")]], finishReason: "")
    r.consume(delta: ["tool_calls": [tc(0, args: "{\"tool_title\":\"t\",")]], finishReason: "")
    r.consume(delta: ["tool_calls": [tc(0, args: "\"command\":\"ls\"}")]], finishReason: "")
    check("nothing completed while finish_reason is \"\"", r.completions.isEmpty)
    r.consume(delta: [:], finishReason: "tool_calls")
    checkEq("completed once, with full args", r.completions, [.complete(id: "call_i", name: "shell_execute", argsJSON: #"{"command":"ls","tool_title":"t"}"#, rawJSON: #"{"tool_title":"t","command":"ls"}"#)])
    // A chunk without `index` is ignored rather than guessed at.
    var n = Reducer()
    n.consume(delta: ["tool_calls": [["id": "x", "function": ["name": "f", "arguments": "{}"]]]], finishReason: "tool_calls")
    check("a delta without index is skipped", n.completions.isEmpty)
    // An arguments delta for an index that never started is dropped, not adopted.
    var s = Reducer()
    s.consume(delta: ["tool_calls": [tc(3, args: "{\"a\":1}")]], finishReason: "tool_calls")
    check("orphan arguments (no start) are not turned into a call", s.completions.isEmpty)
}

print("▶️  6. shipping sources still carry the pinned lines")
do {
    let oai = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    if oai.isEmpty { print("  ⏭  source not readable") } else {
        check("accumulator keyed by index", oai.contains("var toolCallAccum: [Int: (id: String, name: String, json: String)] = [:]"))
        check("index is required", oai.contains("guard let index = tc[\"index\"] as? Int else { continue }"))
        check("empty id/name guard", oai.contains("if let id = tc[\"id\"] as? String, !id.isEmpty,\n                                   let name = fn[\"name\"] as? String, !name.isEmpty {"))
        check("arguments appended, not replaced", oai.contains("entry.json += argDelta"))
        check("empty finish_reason ignored", oai.contains("if let fr = finishReason, !fr.isEmpty {"))
        check("flush sorted by index", oai.contains("for (_, entry) in toolCallAccum.sorted(by: { $0.key < $1.key }) {"))
        check("strict parse — no repair at the provider layer", oai.contains("let args = Self.parseJsonToDict(entry.json)") && !oai.contains("repairToolArgs("))
        check("empty args are diagnosed, not hidden", oai.contains("Self.diagEmptyToolArgs(rawJson: entry.json, toolName: entry.name, toolId: entry.id, model: self.model.id, source: \"chatCompletions.finishReason\")"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
