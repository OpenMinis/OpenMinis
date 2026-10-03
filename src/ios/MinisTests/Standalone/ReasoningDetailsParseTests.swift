// Tests for [T-openrouter-reasoning-details] — GH#263: OpenRouter's structured
// `delta.reasoning_details` array was never parsed.
//
// OpenAIAgentProvider.streamChatCompletions read reasoning only from STRING
// fields (`reasoning_content`, `reasoning`, `reasoning_text`, the model's
// interleaved field). OpenRouter also sends reasoning as
//
//   "reasoning_details":[{"type":"reasoning.text","text":"…"},
//                        {"type":"reasoning.summary","summary":"…"},
//                        {"type":"reasoning.encrypted","data":"…"}]
//
// and some models send ONLY that. A model that spent its budget reasoning this
// way ended with text empty AND reasoning empty, which is the "empty response"
// branch.
//
// Fix: read text/summary items into reasoningContent, count encrypted ones and
// mark that reasoning was seen. Skip the array when the same chunk already had
// non-empty string reasoning, since OpenRouter then sends the text twice.
//
// Standalone: `swift ReasoningDetailsParseTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

struct Parsed { var reasoning = ""; var sawField = false; var encrypted = 0 }

/// Port of the reasoning reads for one chunk (string fields + details).
func parse(_ deltaJSON: String, into p: inout Parsed, withDetails: Bool = true) {
    let delta = (try? JSONSerialization.jsonObject(with: Data(deltaJSON.utf8))) as? [String: Any] ?? [:]
    for key in ["reasoning_content", "reasoning", "reasoning_text"] {
        if let rc = delta[key] as? String { p.sawField = true; p.reasoning += rc }
    }
    guard withDetails else { return }
    let chunkHadStringReasoning = ["reasoning_content", "reasoning", "reasoning_text"]
        .contains { ((delta[$0] as? String) ?? "").isEmpty == false }
    if !chunkHadStringReasoning, let details = delta["reasoning_details"] as? [[String: Any]] {
        for item in details {
            let text: String?
            switch item["type"] as? String {
            case "reasoning.text": text = item["text"] as? String
            case "reasoning.summary": text = item["summary"] as? String
            case "reasoning.encrypted": p.sawField = true; p.encrypted += 1; text = nil
            default: text = nil
            }
            guard let rc = text else { continue }
            p.sawField = true
            p.reasoning += rc
        }
    }
}

func run(_ chunks: [String], withDetails: Bool = true) -> Parsed {
    var p = Parsed()
    for c in chunks { parse(c, into: &p, withDetails: withDetails) }
    return p
}

let detailsOnly = [
    #"{"reasoning_details":[{"type":"reasoning.text","text":"Let me think. ","index":0}]}"#,
    #"{"reasoning_details":[{"type":"reasoning.text","text":"Done.","index":0}]}"#,
]

print("▶️  1. details-only reasoning")
check("OLD: reasoning_details ignored → reasoning empty (the bug)",
      run(detailsOnly, withDetails: false).reasoning.isEmpty)
check("NEW: reasoning.text items are accumulated",
      run(detailsOnly).reasoning == "Let me think. Done.")
check("reasoning.summary is read from `summary`",
      run([#"{"reasoning_details":[{"type":"reasoning.summary","summary":"Plan: X"}]}"#]).reasoning == "Plan: X")
do {
    let p = run([#"{"reasoning_details":[{"type":"reasoning.encrypted","data":"gAAAA"}]}"#,
                 #"{"reasoning_details":[{"type":"reasoning.encrypted","data":"gBBBB"}]}"#])
    check("reasoning.encrypted adds no text", p.reasoning.isEmpty)
    check("…but marks reasoning as seen and counts it", p.sawField && p.encrypted == 2)
}
check("unknown item types are ignored",
      run([#"{"reasoning_details":[{"type":"reasoning.future","x":1}]}"#]).reasoning.isEmpty)
check("mixed items in one chunk, in order",
      run([#"{"reasoning_details":[{"type":"reasoning.summary","summary":"S "},{"type":"reasoning.text","text":"T"}]}"#]).reasoning == "S T")

print("\n▶️  2. no double counting")
do {
    // OpenRouter's usual shape: the same text in `reasoning` AND in details.
    let both = [#"{"reasoning":"Hmm. ","reasoning_details":[{"type":"reasoning.text","text":"Hmm. "}]}"#,
                #"{"reasoning":"Ok.","reasoning_details":[{"type":"reasoning.text","text":"Ok."}]}"#]
    check("string + details for the same text counts it once", run(both).reasoning == "Hmm. Ok.")
}
check("an EMPTY string field does not suppress the details",
      run([#"{"reasoning":"","reasoning_details":[{"type":"reasoning.text","text":"real"}]}"#]).reasoning == "real")
check("reasoning_content present also suppresses the details",
      run([#"{"reasoning_content":"A","reasoning_details":[{"type":"reasoning.text","text":"A"}]}"#]).reasoning == "A")
check("plain string reasoning is unchanged", run([#"{"reasoning":"x"}"#, #"{"reasoning":"y"}"#]).reasoning == "xy")

// MARK: - Sources

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let provider = (try? String(contentsOf: root.appendingPathComponent("Providers/OpenAI/OpenAIAgentProvider.swift"), encoding: .utf8)) ?? ""

print("\n▶️  3. sources")
check("the double-count guard is as ported",
      provider.contains(#"let chunkHadStringReasoning = ["reasoning_content", "reasoning", "reasoning_text"]"#)
        && provider.contains("if !chunkHadStringReasoning,\n                           let details = delta[\"reasoning_details\"] as? [[String: Any]] {"))
check("text / summary / encrypted handled",
      provider.contains(#"case "reasoning.text": text = item["text"] as? String"#)
        && provider.contains(#"case "reasoning.summary": text = item["summary"] as? String"#)
        && provider.contains(#"case "reasoning.encrypted":"#)
        && provider.contains("encryptedReasoningChunks += 1"))
check("the finish log reports encrypted chunks", provider.contains("encryptedReasoningChunks=\\(encryptedReasoningChunks)"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
