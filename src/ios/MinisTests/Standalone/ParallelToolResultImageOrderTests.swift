// Tests for [T-openai-tool-result-image] × parallel tool calls — when one of
// several parallel tool results carries an image, the synthetic user message
// that carries the image_url for OpenAI-style wire formats must not split the
// contiguous run of tool replies. DeepSeek rejects a split run with
//     [400] No tool output found for tool call call_…
// (Android fix 030d059cb + ParallelToolResultImageOrderTest; iOS had no
// parallel case — ToolResultImageWireTests covers a single result only.)
//
// Ported from src/ios/Providers/OpenAI/OpenAIAgentProvider.swift:
//   * `flattenChatCompletionsMessages` (~1345): emits each tool reply, then the
//     image carrier IMMEDIATELY after it (inside the per-result loop), and ends
//     with `sanitizeToolCallAdjacency(globallyDedupeToolCallIds(result))`.
//     The sanitizer pulls every role:"tool" entry out and re-inserts them
//     right after their assistant's tool_calls, in tool_calls order — which is
//     what keeps the Chat Completions run contiguous on iOS.
//   * `convertMessagesResponsesAPI` (~1733): emits function_call_output items
//     and BUFFERS the input_image carrier in `pendingImageCarriers`, flushing it
//     once the output run closes (at the first non-output item, at the message
//     boundary, and at the end of the conversion). It has no adjacency pass to
//     repair a split run afterwards, so deferring the carrier is the whole fix.
//
// Standalone (`swift ParallelToolResultImageOrderTests.swift`).

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(expected)\n     actual:   \(actual)"); failures += 1 }
}

// MARK: - Minimal mirror of AgentMessage

enum AgentContentPart {
    case text(String)
    case toolUse(id: String, name: String)
    case toolResult(id: String, name: String, content: String, imageData: Data?, imageMime: String?)
}
struct AgentMessage {
    enum Role { case user, assistant }
    let role: Role
    var parts: [AgentContentPart]
}

// MARK: - Port: Chat Completions

/// convertSingleMessageChatCompletions, reduced to what an assistant tool-call
/// turn produces.
func convertSingle(_ msg: AgentMessage) -> [String: Any] {
    var result: [String: Any] = ["role": msg.role == .user ? "user" : "assistant"]
    var text = ""
    var toolCalls: [[String: Any]] = []
    for p in msg.parts {
        switch p {
        case .text(let t): text += t
        case .toolUse(let id, let name):
            toolCalls.append(["id": id, "type": "function", "function": ["name": name, "arguments": "{}"]])
        case .toolResult: break
        }
    }
    result["content"] = text
    if !toolCalls.isEmpty { result["tool_calls"] = toolCalls }
    return result
}

func flattenChatCompletionsMessages(_ messages: [AgentMessage], supportsImages: Bool) -> [[String: Any]] {
    var result: [[String: Any]] = []
    for msg in messages {
        let toolResults = msg.parts.compactMap { part -> (id: String, content: String, imageData: Data?, imageMime: String?)? in
            if case .toolResult(let id, _, let content, let imageData, let imageMime) = part {
                return (id, content, imageData, imageMime)
            }
            return nil
        }
        if !toolResults.isEmpty {
            for tr in toolResults {
                result.append(["role": "tool", "tool_call_id": tr.id, "content": tr.content])
                if let data = tr.imageData, supportsImages {
                    let mime = tr.imageMime ?? "image/jpeg"
                    result.append([
                        "role": "user",
                        "content": [["type": "image_url", "image_url": ["url": "data:\(mime);base64,\(data.base64EncodedString())"]]] as [[String: Any]],
                    ])
                }
            }
        } else {
            result.append(convertSingle(msg))
        }
    }
    return sanitizeToolCallAdjacency(result)
}

/// Verbatim port (minus logging).
func sanitizeToolCallAdjacency(_ messages: [[String: Any]]) -> [[String: Any]] {
    var pendingToolReplies: [String: [String: Any]] = [:]
    var body: [[String: Any]] = []
    for msg in messages {
        if (msg["role"] as? String) == "tool", let id = msg["tool_call_id"] as? String {
            if pendingToolReplies[id] == nil { pendingToolReplies[id] = msg }
        } else {
            body.append(msg)
        }
    }
    var out: [[String: Any]] = []
    for msg in body {
        out.append(msg)
        guard (msg["role"] as? String) == "assistant",
              let toolCalls = msg["tool_calls"] as? [[String: Any]], !toolCalls.isEmpty else { continue }
        for call in toolCalls {
            guard let id = call["id"] as? String else { continue }
            if let reply = pendingToolReplies.removeValue(forKey: id) {
                out.append(reply)
            } else {
                out.append(["role": "tool", "tool_call_id": id,
                            "content": "Tool execution result is unavailable (history was truncated or interrupted)."])
            }
        }
    }
    return out
}

// MARK: - Port: Responses API (subset)

func convertMessagesResponsesAPI(_ messages: [AgentMessage], supportsImages: Bool) -> [[String: Any]] {
    var result: [[String: Any]] = []
    // The fix: carriers are buffered while a run of function_call_output items is
    // being emitted, and flushed when that run closes.
    var pendingImageCarriers: [[String: Any]] = []
    func flushImageCarriers() {
        guard !pendingImageCarriers.isEmpty else { return }
        result.append(contentsOf: pendingImageCarriers)
        pendingImageCarriers.removeAll()
    }
    for msg in messages {
        for part in msg.parts {
            switch part {
            case .text(let text):
                flushImageCarriers()
                result.append(["role": msg.role == .user ? "user" : "assistant", "content": text])
            case .toolUse(let id, let name):
                flushImageCarriers()
                result.append(["type": "function_call", "call_id": id, "name": name, "arguments": "{}"])
            case .toolResult(let id, _, let content, let imageData, let imageMime):
                result.append(["type": "function_call_output", "call_id": id, "output": content])
                if let data = imageData, supportsImages {
                    let mime = imageMime ?? "image/jpeg"
                    pendingImageCarriers.append([
                        "role": "user",
                        "content": [["type": "input_image", "image_url": "data:\(mime);base64,\(data.base64EncodedString())"]],
                    ])
                }
            }
        }
        flushImageCarriers()
    }
    flushImageCarriers()
    return result
}
/// The pre-fix shape, kept ONLY to prove this test is non-vacuous: appending the
/// carrier inline really does split the run, so the assertions below would have
/// caught the bug rather than passing by accident.
func convertMessagesResponsesAPIInlineCarrier(_ messages: [AgentMessage], supportsImages: Bool) -> [[String: Any]] {
    var result: [[String: Any]] = []
    for msg in messages {
        for part in msg.parts {
            switch part {
            case .text(let text):
                result.append(["role": msg.role == .user ? "user" : "assistant", "content": text])
            case .toolUse(let id, let name):
                result.append(["type": "function_call", "call_id": id, "name": name, "arguments": "{}"])
            case .toolResult(let id, _, let content, let imageData, let imageMime):
                result.append(["type": "function_call_output", "call_id": id, "output": content])
                if let data = imageData, supportsImages {
                    let mime = imageMime ?? "image/jpeg"
                    result.append([
                        "role": "user",
                        "content": [["type": "input_image", "image_url": "data:\(mime);base64,\(data.base64EncodedString())"]],
                    ])
                }
            }
        }
    }
    return result
}

// MARK: - Shape helpers

func shape(_ wire: [[String: Any]]) -> [String] {
    wire.map { m in
        if let t = m["type"] as? String { return t }
        let role = m["role"] as? String ?? "?"
        if role == "tool" { return "tool(\(m["tool_call_id"] as? String ?? "?"))" }
        if role == "assistant", m["tool_calls"] != nil { return "assistant+tool_calls" }
        if role == "user", let c = m["content"] as? [[String: Any]], c.first?["type"] as? String == "image_url" { return "user(image)" }
        if role == "user", let c = m["content"] as? [[String: Any]], c.first?["type"] as? String == "input_image" { return "user(input_image)" }
        return role
    }
}
/// True when every run of tool replies answering an assistant's tool_calls is
/// unbroken (no other message between the assistant and its last reply).
func toolRunContiguous(_ wire: [[String: Any]]) -> Bool {
    var i = 0
    while i < wire.count {
        let m = wire[i]
        if (m["role"] as? String) == "assistant", let calls = m["tool_calls"] as? [[String: Any]] {
            for k in 0..<calls.count {
                guard i + 1 + k < wire.count, (wire[i + 1 + k]["role"] as? String) == "tool" else { return false }
            }
            i += calls.count
        }
        i += 1
    }
    return true
}
func outputRunContiguous(_ wire: [[String: Any]]) -> Bool {
    // Responses API: after the last function_call of a turn, all its
    // function_call_output items must be consecutive.
    var expected = 0
    var i = 0
    while i < wire.count {
        if wire[i]["type"] as? String == "function_call" {
            expected = 0
            var j = i
            while j < wire.count, wire[j]["type"] as? String == "function_call" { expected += 1; j += 1 }
            for k in 0..<expected {
                guard j + k < wire.count, wire[j + k]["type"] as? String == "function_call_output" else { return false }
            }
            i = j + expected
            continue
        }
        i += 1
    }
    return true
}

let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
let history: [AgentMessage] = [
    AgentMessage(role: .user, parts: [.text("read both charts and the log")]),
    AgentMessage(role: .assistant, parts: [
        .toolUse(id: "call_01", name: "read_image"),
        .toolUse(id: "call_02", name: "read_image"),
        .toolUse(id: "call_03", name: "file_read"),
    ]),
    AgentMessage(role: .user, parts: [
        .toolResult(id: "call_01", name: "read_image", content: "Image loaded (800x600)", imageData: nil, imageMime: nil),
        .toolResult(id: "call_02", name: "read_image", content: "Image loaded (1024x768)", imageData: png, imageMime: "image/png"),
        .toolResult(id: "call_03", name: "file_read", content: "log contents", imageData: nil, imageMime: nil),
    ]),
]

// MARK: - [1] Chat Completions

print("\n[1] Chat Completions: 3 parallel results, the 2nd carries an image")
do {
    let wire = flattenChatCompletionsMessages(history, supportsImages: true)
    checkEq("shape: three contiguous tool replies, then the image carrier",
            shape(wire), ["user", "assistant+tool_calls", "tool(call_01)", "tool(call_02)", "tool(call_03)", "user(image)"])
    check("tool run is contiguous", toolRunContiguous(wire))
    check("the image carrier comes AFTER the last tool reply",
          shape(wire).lastIndex(of: "user(image)")! > shape(wire).lastIndex(of: "tool(call_03)")!)
    check("all three replies present exactly once",
          shape(wire).filter { $0.hasPrefix("tool(") }.count == 3)
    check("image carrier is present exactly once",
          shape(wire).filter { $0 == "user(image)" }.count == 1)
    check("the carrier holds the image bytes",
          ((wire.last?["content"] as? [[String: Any]])?.first?["image_url"] as? [String: Any])?["url"] as? String
            == "data:image/png;base64,\(png.base64EncodedString())")

    // The pre-sanitize order really is split — the adjacency pass is what
    // repairs it, so it must stay at the end of flatten (see [3]).
    var raw: [[String: Any]] = [convertSingle(history[1])]
    for p in history[2].parts {
        if case .toolResult(let id, _, let c, let d, let m) = p {
            raw.append(["role": "tool", "tool_call_id": id, "content": c])
            if let d { raw.append(["role": "user", "content": [["type": "image_url", "image_url": ["url": "data:\(m!);base64,\(d.base64EncodedString())"]]] as [[String: Any]]]) }
        }
    }
    check("without the adjacency pass the run WOULD be split", toolRunContiguous(raw), false)
    check("the adjacency pass restores it", toolRunContiguous(sanitizeToolCallAdjacency(raw)))

    // Two images among three: still one contiguous run, both carriers after it.
    var two = history
    two[2].parts[0] = .toolResult(id: "call_01", name: "read_image", content: "Image loaded", imageData: png, imageMime: "image/png")
    let wire2 = flattenChatCompletionsMessages(two, supportsImages: true)
    checkEq("two images: run then two carriers",
            shape(wire2), ["user", "assistant+tool_calls", "tool(call_01)", "tool(call_02)", "tool(call_03)", "user(image)", "user(image)"])

    // Non-vision model: no carrier at all, run untouched.
    let wire3 = flattenChatCompletionsMessages(history, supportsImages: false)
    checkEq("non-vision model: no image carrier",
            shape(wire3), ["user", "assistant+tool_calls", "tool(call_01)", "tool(call_02)", "tool(call_03)"])

    // Order of replies follows tool_calls order even if results arrived shuffled.
    var shuffled = history
    shuffled[2].parts.reverse()
    let wire4 = flattenChatCompletionsMessages(shuffled, supportsImages: true)
    checkEq("replies are re-ordered to tool_calls order",
            shape(wire4).filter { $0.hasPrefix("tool(") }, ["tool(call_01)", "tool(call_02)", "tool(call_03)"])
}

// MARK: - [2] Responses API

print("\n[2] Responses API: function_call_output run")
do {
    let wire = convertMessagesResponsesAPI(history, supportsImages: true)
    let s = shape(wire)
    check("all three function_call_output items present",
          s.filter { $0 == "function_call_output" }.count == 3)
    check("input_image carrier present once", s.filter { $0 == "user(input_image)" }.count == 1)
    // The carrier must be DEFERRED until the output run closes. Emitting it
    // inline (the pre-fix shape) lands it BETWEEN outputs —
    //   function_call_output(call_01), function_call_output(call_02),
    //   user(input_image), function_call_output(call_03)
    // — which a strict Responses-compatible relay rejects exactly the way
    // DeepSeek rejects a split tool run on Chat Completions (Android 030d059cb).
    // OpenAI's own endpoint happens to tolerate it, which is why this went
    // unnoticed. Chat Completions survives the identical inline emission only
    // because sanitizeToolCallAdjacency runs afterwards; the Responses path has
    // no such pass, so the deferral IS the fix.
    check("function_call_output run is contiguous, carrier after it", outputRunContiguous(wire))
    checkEq("shape", s, ["user", "function_call", "function_call", "function_call",
                         "function_call_output", "function_call_output", "function_call_output", "user(input_image)"])
    // Non-vacuous: the pre-fix inline form really does split the run, so the two
    // assertions above would have caught the bug rather than passing by accident.
    let inline = convertMessagesResponsesAPIInlineCarrier(history, supportsImages: true)
    check("the pre-fix inline carrier WOULD split the run", outputRunContiguous(inline), false)
    checkEq("…splitting it exactly where the gap was recorded", shape(inline),
            ["user", "function_call", "function_call", "function_call",
             "function_call_output", "function_call_output", "user(input_image)", "function_call_output"])

    // Two images among three results: both carriers land after the run, in
    // tool-result order (the buffer preserves emission order).
    var two = history
    two[2].parts[0] = .toolResult(id: "call_01", name: "read_image", content: "Image A", imageData: png, imageMime: "image/png")
    let wireTwo = convertMessagesResponsesAPI(two, supportsImages: true)
    check("two images: run stays contiguous", outputRunContiguous(wireTwo))
    checkEq("two images: run then two carriers", shape(wireTwo),
            ["user", "function_call", "function_call", "function_call",
             "function_call_output", "function_call_output", "function_call_output",
             "user(input_image)", "user(input_image)"])
    // Carrier ORDER must follow the results, not be reversed by the buffer.
    let carrierURLs = wireTwo.suffix(2).compactMap { m -> String? in
        ((m["content"] as? [[String: Any]])?.first)?["image_url"] as? String
    }
    checkEq("both carriers carry bytes", carrierURLs.count, 2)
    check("carrier order is tool-result order (call_01's image first)",
          carrierURLs.first == "data:image/png;base64,\(png.base64EncodedString())")

    // A following turn must not have the carrier pushed past it: the flush
    // happens at the message boundary, so the carrier stays inside its own round.
    let withFollowUp = history + [
        AgentMessage(role: .assistant, parts: [.text("Both charts show the same trend.")]),
    ]
    checkEq("the carrier stays before the next assistant turn",
            shape(convertMessagesResponsesAPI(withFollowUp, supportsImages: true)),
            ["user", "function_call", "function_call", "function_call",
             "function_call_output", "function_call_output", "function_call_output",
             "user(input_image)", "assistant"])

    // Non-vision: no carrier, run trivially contiguous.
    let wire2 = convertMessagesResponsesAPI(history, supportsImages: false)
    check("non-vision model: outputs contiguous, no carrier",
          outputRunContiguous(wire2) && !shape(wire2).contains("user(input_image)"))
    // Single result with image (the case ToolResultImageWireTests pins with
    // `body[outIdx + 1].role == "user"`) must serialise BYTE-IDENTICALLY to the
    // inline form — a one-element run closes immediately, so the deferral is a
    // no-op there and the XCTest contract is untouched.
    let single = [history[0],
                  AgentMessage(role: .assistant, parts: [.toolUse(id: "c1", name: "read_image")]),
                  AgentMessage(role: .user, parts: [.toolResult(id: "c1", name: "read_image", content: "ok", imageData: png, imageMime: "image/png")])]
    checkEq("single result: output then carrier",
            shape(convertMessagesResponsesAPI(single, supportsImages: true)),
            ["user", "function_call", "function_call_output", "user(input_image)"])
    func json(_ wire: [[String: Any]]) -> String {
        (try? String(data: JSONSerialization.data(withJSONObject: wire, options: [.sortedKeys]),
                     encoding: .utf8)) ?? "<unencodable>"
    }
    checkEq("single result serialises byte-identically to the pre-fix form",
            json(convertMessagesResponsesAPI(single, supportsImages: true)),
            json(convertMessagesResponsesAPIInlineCarrier(single, supportsImages: true)))
    // And the no-image case — every history that never attaches pixels — is
    // byte-identical too, on the parallel fixture that exposed the bug.
    checkEq("no-image history serialises byte-identically to the pre-fix form",
            json(convertMessagesResponsesAPI(history, supportsImages: false)),
            json(convertMessagesResponsesAPIInlineCarrier(history, supportsImages: false)))
}

// MARK: - [3] shipping source cross-check

print("\n[3] shipping source cross-check")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let src = sourceOf("Providers/OpenAI/OpenAIAgentProvider.swift")
check("OpenAIAgentProvider read", !src.isEmpty)
check("flatten ends with the adjacency pass (what keeps the run contiguous)",
      src.contains("return Self.sanitizeToolCallAdjacency(Self.globallyDedupeToolCallIds(result))"))
check("flatten emits the image carrier per result (relies on the pass above)",
      src.contains("// Attach the image (if any) as a synthetic user message\n                    // right after the tool reply."))
check("the pass pulls tool replies out and re-inserts them after their tool_calls", {
    guard let fn = src.range(of: "private static func sanitizeToolCallAdjacency") else { return false }
    let body = src[fn.upperBound...].prefix(3000)
    return body.contains("pendingToolReplies[id] = msg") && body.contains("if let reply = pendingToolReplies.removeValue(forKey: id) {")
}())
check("flatten gates the carrier on vision support", src.contains("if let data = tr.imageData, supportsImages {"))
check("Responses API emits function_call_output then an input_image carrier",
      src.contains("\"type\": \"function_call_output\",") && src.contains("[\"type\": \"input_image\", \"image_url\": \"data:\\(mime);base64,\\(base64)\"],"))
let responsesBody: Substring = {
    guard let fn = src.range(of: "func convertMessagesResponsesAPI("),
          let end = src.range(of: "// MARK: - Tool Conversion", range: fn.upperBound..<src.endIndex)
    else { return "" }
    return src[fn.lowerBound..<end.lowerBound]
}()
check("convertMessagesResponsesAPI body located", !responsesBody.isEmpty)
check("it defers the carrier into a buffer instead of appending it inline",
      responsesBody.contains("pendingImageCarriers.append(["))
check("…and never appends the input_image carrier straight into result",
      responsesBody.contains("result.append([\n                            \"role\": \"user\",\n                            \"content\": [\n                                [\"type\": \"input_image\""), false)
check("the buffer is flushed when the output run closes",
      responsesBody.contains("func flushImageCarriers() {")
        && responsesBody.components(separatedBy: "flushImageCarriers()").count - 1 >= 6)
check("…including at the message boundary, so a carrier stays in its own round",
      responsesBody.contains("// The run closes at the message boundary too"))
check("the reason (a split run 400s on a strict relay) is recorded in the source",
      responsesBody.contains("splits the run of"))

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
