// Tests for [T-openai-done-overwrite] — GH#263: a truncated or content-filtered
// OpenAI-compatible reply was reported as "Model returned an empty response".
//
// Root cause, two halves:
//   * Provider (OpenAIAgentProvider.streamChatCompletions): the finish_reason
//     chunk yields `.done(.maxTokens)` for "length" / `.done(.refusal)` for
//     "content_filter", and the `data: [DONE]` line that ALWAYS follows yielded
//     a second `.done(hasToolCalls ? .toolUse : .endTurn)`.
//   * Consumer (AIChatViewModel+SSEStream.swift processStreamEvents): every
//     `.done` did `result.stopReason = reason`, so the last one won.
// Together: `.maxTokens` → `.endTurn`, empty text → the empty-response branch.
// Gemini / Antigravity have the same shape (finishReason, then `.done`), so the
// consumer guard covers them too.
//
// Fix: the provider yields `.done` once (`emittedDone`); the consumer keeps a
// non-`.endTurn` reason over a later `.endTurn` (first terminal wins).
//
// This ports both halves, runs the wire sequences through old and new code,
// and greps the sources for the wiring.
// Standalone: `swift OpenAIDoneOverwriteTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

enum StopReason: Equatable { case endTurn, toolUse, maxTokens, refusal }

/// Wire lines after `data:` — either a finish_reason value or "[DONE]".
enum Wire { case finish(String), doneLine }

/// Port of the provider's `.done` emission. `guarded` = with `emittedDone`.
func providerDones(_ wire: [Wire], hasToolCalls: Bool, guarded: Bool) -> [StopReason] {
    var out: [StopReason] = []
    var emittedDone = false
    for w in wire {
        switch w {
        case .finish(let fr):
            guard !fr.isEmpty else { continue }
            let reason: StopReason
            switch fr {
            case "tool_calls": reason = .toolUse
            case "length": reason = .maxTokens
            case "content_filter": reason = .refusal
            default: reason = hasToolCalls ? .toolUse : .endTurn
            }
            if !guarded || !emittedDone { out.append(reason); emittedDone = true }
        case .doneLine:
            if !guarded || !emittedDone {
                out.append(hasToolCalls ? .toolUse : .endTurn)
                emittedDone = true
            }
            return out
        }
    }
    return out
}

/// Port of the consumer's `.done` reduction. `guarded` = first terminal wins.
func consume(_ dones: [StopReason], guarded: Bool) -> StopReason? {
    var stop: StopReason? = nil
    for reason in dones {
        if guarded, let prior = stop, prior != .endTurn, reason == .endTurn { continue }
        stop = reason
    }
    return stop
}

func final(_ wire: [Wire], tools: Bool = false, fixed: Bool) -> StopReason? {
    consume(providerDones(wire, hasToolCalls: tools, guarded: fixed), guarded: fixed)
}

print("▶️  1. the reported shape: finish_reason then [DONE]")
check("OLD: length + [DONE] collapsed to .endTurn (the bug)",
      final([.finish("length"), .doneLine], fixed: false) == .endTurn)
check("NEW: length + [DONE] → .maxTokens",
      final([.finish("length"), .doneLine], fixed: true) == .maxTokens)
check("OLD: content_filter + [DONE] collapsed to .endTurn",
      final([.finish("content_filter"), .doneLine], fixed: false) == .endTurn)
check("NEW: content_filter + [DONE] → .refusal",
      final([.finish("content_filter"), .doneLine], fixed: true) == .refusal)

print("\n▶️  2. unchanged outcomes")
check("stop + [DONE] → .endTurn", final([.finish("stop"), .doneLine], fixed: true) == .endTurn)
check("tool_calls + [DONE] → .toolUse",
      final([.finish("tool_calls"), .doneLine], tools: true, fixed: true) == .toolUse)
check("[DONE] alone (proxy sends no finish_reason) still ends the turn",
      final([.doneLine], fixed: true) == .endTurn)
check("[DONE] alone with tool calls → .toolUse", final([.doneLine], tools: true, fixed: true) == .toolUse)
check("empty-string finish_reason chunks are ignored",
      final([.finish(""), .finish(""), .finish("length"), .doneLine], fixed: true) == .maxTokens)
check("provider now yields exactly one .done for finish + [DONE]",
      providerDones([.finish("length"), .doneLine], hasToolCalls: false, guarded: true) == [.maxTokens])

print("\n▶️  3. consumer guard alone (Gemini/Antigravity: finishReason then .done)")
check("OLD: [.maxTokens, .endTurn] → .endTurn", consume([.maxTokens, .endTurn], guarded: false) == .endTurn)
check("NEW: [.maxTokens, .endTurn] → .maxTokens", consume([.maxTokens, .endTurn], guarded: true) == .maxTokens)
check("NEW: [.refusal, .endTurn] → .refusal", consume([.refusal, .endTurn], guarded: true) == .refusal)
check("NEW: [.toolUse, .endTurn] → .toolUse", consume([.toolUse, .endTurn], guarded: true) == .toolUse)
check("a later SPECIFIC reason still replaces .endTurn",
      consume([.endTurn, .maxTokens], guarded: true) == .maxTokens)
check("no .done at all stays nil (interrupted stream, [T-stream-drop-silent])",
      consume([], guarded: true) == nil)

// MARK: - Sources

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func source(_ rel: String) -> String {
    (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
/// The body of streamChatCompletions only — the Responses branch below has
/// its own `[DONE]` handling and must not satisfy these checks.
let provider = source("Providers/OpenAI/OpenAIAgentProvider.swift")
let chatBody: String = {
    guard let a = provider.range(of: "private func streamChatCompletions("),
          let b = provider.range(of: "continuation.onTermination", range: a.upperBound..<provider.endIndex)
    else { return "" }
    return String(provider[a.lowerBound..<b.lowerBound])
}()
let sse = source("Agent/Chat/AIChatViewModel+SSEStream.swift")

print("\n▶️  4. sources")
check("streamChatCompletions located", !chatBody.isEmpty)
check("emittedDone flag declared", chatBody.contains("var emittedDone = false"))
check("[DONE] yields only when nothing was emitted",
      chatBody.contains("if !emittedDone {\n                                let reason: AgentStopReason = hasToolCalls ? .toolUse : .endTurn"))
check("finish_reason branch yields once and marks it",
      chatBody.contains("if !emittedDone {\n                                continuation.yield(.done(stopReason: reason))\n                                emittedDone = true"))
check("no unguarded .done left in the chat-completions body",
      chatBody.components(separatedBy: "continuation.yield(.done(").count - 1 == 2)
check("consumer keeps a specific reason over a later .endTurn",
      sse.contains("if let prior = result.stopReason, prior != .endTurn, reason == .endTurn {"))
check("…and otherwise assigns as before", sse.contains("} else {\n                    result.stopReason = reason\n                }"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
