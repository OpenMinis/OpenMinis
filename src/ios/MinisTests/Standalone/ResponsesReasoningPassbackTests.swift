#!/usr/bin/env swift
// [T-responses-reasoning-inherit, T-responses-reasoning-fallback, T-error-detail-visible]
// issue #368 — an OpenAI Responses-API relay that validates reasoning passback
// answered
//     400 The `reasoning_text` in the thinking mode must be passed back to the API.
// in long tool-heavy sessions, and the UI showed only
//     ⚠️ <entry>: Retries exhausted
// because the real reason sat on a clipped line.
//
// Two independent defects, pinned here:
//
//  1. The replay gate in `convertMessagesResponsesAPI` required
//     `echo.modelId == self.model.id`, so a model-group fallback hop dropped the
//     whole reasoning HEAD while still emitting the turn's `function_call`
//     items — the exact shape the relay rejects. The gate now keys on the
//     UPSTREAM that minted the blob, and on a mismatch keeps the item and drops
//     only `encrypted_content` (which that endpoint could not decrypt anyway).
//  2. `ReasoningEcho` is in-memory only, so a cold start / session reopen
//     rehydrates a tool turn with no echo at all. A synthetic id-only head now
//     covers that, mirroring AnthropicAgentProvider's `injectPlaceholder`.
//
// Run: swift ResponsesReasoningPassbackTests.swift
//
// Convention: a bare `swift` script, because `deps/libs/libish_emu.a` is
// device-arm64 only and the app cannot link for the simulator. The gate logic is
// ported verbatim from the shipping source with file citations, and section [5]
// greps the real files so a rewrite fails here instead of silently passing a
// stale copy.
import Foundation

var failures = 0
func check(_ label: String, _ cond: Bool) {
    print(cond ? "  ✅ \(label)" : "  ❌ \(label) — expected true, got false")
    if !cond { failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    print(a == b ? "  ✅ \(label)" : "  ❌ \(label) — expected \(b), got \(a)")
    if a != b { failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Ported model types (Providers/AgentProvider.swift:115-137)

struct ReasoningEcho {
    let providerKind: String
    let modelId: String
    var upstreamIdentity: String? = nil
    let items: [Item]
    enum Item { case openaiReasoning(id: String, encryptedContent: String?, summary: [String]) }
}

enum Part {
    case text(String)
    case toolUse(id: String, name: String)
    case toolResult(id: String, content: String)
}

struct Msg {
    enum Role { case user, assistant }
    let role: Role
    var parts: [Part]
    var reasoningEcho: ReasoningEcho? = nil
}

let responsesKind = "openai-responses"

// MARK: - Port: convertMessagesResponsesAPI reasoning head + tool round trip
// OpenAIAgentProvider.swift — the gate (~:1786) and the synthetic head (~:1830).

/// `capResponsesId` (OpenAIAgentProvider.swift:2255) caps at 64 chars; the ids
/// used here are far shorter, so the cap is a no-op and the suffix(24) below is
/// what shapes the synthetic id.
func capResponsesId(_ id: String) -> String { String(id.prefix(64)) }

func buildResponsesInput(
    _ messages: [Msg],
    currentModelId: String,
    currentUpstream: String
) -> [[String: Any]] {
    var result: [[String: Any]] = []
    for msg in messages {
        var emittedReasoningForTurn = false

        if msg.role == .assistant,
           let echo = msg.reasoningEcho,
           echo.providerKind == responsesKind {
            let sameUpstream: Bool = {
                if let recorded = echo.upstreamIdentity { return recorded == currentUpstream }
                return echo.modelId == currentModelId
            }()
            for item in echo.items {
                if case .openaiReasoning(let id, let encrypted, let summary) = item {
                    var entry: [String: Any] = [
                        "type": "reasoning",
                        "id": id,
                        "summary": summary.map { ["type": "summary_text", "text": $0] },
                    ]
                    if sameUpstream, let encrypted, !encrypted.isEmpty {
                        entry["encrypted_content"] = encrypted
                    }
                    result.append(entry)
                    emittedReasoningForTurn = true
                }
            }
        }

        // [T-responses-no-synthetic-item-id] The placeholder head carries NO
        // `id`. It used to synthesize `rs_syn_<call_id>`, which 404'd against
        // any real endpoint (see upstreamRejection's Rule 2).
        if msg.role == .assistant, !emittedReasoningForTurn,
           msg.parts.contains(where: { if case .toolUse = $0 { return true }; return false }) {
            result.append([
                "type": "reasoning",
                "summary": [] as [[String: Any]],
            ])
        }

        for part in msg.parts {
            switch part {
            case .text(let t):
                result.append(["role": msg.role == .user ? "user" : "assistant", "content": t])
            case .toolUse(let id, let name):
                result.append([
                    "type": "function_call", "call_id": id, "name": name, "arguments": "{}",
                    "id": id.hasPrefix("fc_") ? id : "fc_syn_\(id.suffix(24))",
                ])
            case .toolResult(let id, let content):
                result.append(["type": "function_call_output", "call_id": id, "output": content])
            }
        }
    }
    return result
}

// MARK: - Port: the upstream's rules (issue #368 + the rs_syn_ 404 regression)

/// Ids this fake upstream has actually minted. A `reasoning.id` that is not in
/// here was never created server-side, which under `store: false` can only be a
/// dangling reference.
var mintedReasoningIds: Set<String> = []

/// Models the TWO independent rules a Responses endpoint enforces on the
/// reasoning head. The second one is why this file exists a second time.
///
/// Rule 1 — passback (issue #368): a validating relay rejects a
/// `function_call` that has no reasoning item ahead of it.
///
/// Rule 2 — item identity (THIS regression): `reasoning.id` is a SERVER-OWNED
/// resource key. With `store: false` nothing is persisted, so an id the server
/// never minted resolves to nothing and the request fails with
///   404 Item with id '…' not found. Items are not persisted when `store` is
///       set to false.
/// An item with NO `id` is fine — it asserts "reasoning submitted with this
/// request" rather than "look up this stored item".
///
/// Both rules are measured, not guessed: probed against the live ChatGPT Codex
/// backend (gpt-5.6-sol, store:false) on 2026-09-22 —
///   synthetic `rs_syn_…` id → 404 · foreign well-formed id → 404
///   no id at all            → 200 · real minted id (no blob) → 200
///   no reasoning item       → 200
func upstreamRejection(_ input: [[String: Any]]) -> String? {
    var sawReasoning = false
    for item in input {
        let type = item["type"] as? String
        if type == "reasoning" {
            sawReasoning = true
            // Rule 2. `id` is OPTIONAL; when present it must be real.
            if let id = item["id"] as? String, !mintedReasoningIds.contains(id) {
                return "Item with id '\(id)' not found. Items are not persisted when `store` is set to false."
            }
            continue
        }
        if type == "function_call", !sawReasoning {
            return "The `reasoning_text` in the thinking mode must be passed back to the API."
        }
        if item["role"] as? String == "user" { sawReasoning = false }
    }
    return nil
}

func itemTypes(_ input: [[String: Any]]) -> [String] {
    input.map { ($0["type"] as? String) ?? ($0["role"] as? String) ?? "?" }
}

// MARK: - Port: persistence round trip
// buildRawMessage (AIChatViewModel+Persistence.swift:1499) has no echo
// parameter and ChatStore.toAgentMessage (ChatStore.swift:5979) restores only
// `reasoningContent`, so a DB round trip drops the echo by construction.
func afterDatabaseRoundTrip(_ m: Msg) -> Msg {
    Msg(role: m.role, parts: m.parts, reasoningEcho: nil)
}

// MARK: - Fixtures

// The fixtures' reasoning ids stand in for ids the upstream really minted.
let _ = { mintedReasoningIds.formUnion(["rs_691", "rs_x"]) }()

let relayA = "https://relay-a.example.com"
let relayB = "https://relay-b.example.com"

func toolTurn(model: String, upstream: String?, withEcho: Bool = true) -> [Msg] {
    var assistant = Msg(role: .assistant, parts: [.toolUse(id: "call_abc123", name: "shell_execute")])
    if withEcho {
        assistant.reasoningEcho = ReasoningEcho(
            providerKind: responsesKind,
            modelId: model,
            upstreamIdentity: upstream,
            items: [.openaiReasoning(id: "rs_691", encryptedContent: "gAAAAAB_opaque", summary: ["Listing files"])]
        )
    }
    return [
        Msg(role: .user, parts: [.text("what files are here?")]),
        assistant,
        Msg(role: .user, parts: [.toolResult(id: "call_abc123", content: "app.py")]),
    ]
}

print("══ [1] baseline: same upstream, same model ══")
do {
    let input = buildResponsesInput(toolTurn(model: "gpt-5.3", upstream: relayA),
                                   currentModelId: "gpt-5.3", currentUpstream: relayA)
    checkEq("reasoning precedes the function_call",
            itemTypes(input), ["user", "reasoning", "function_call", "function_call_output"])
    check("the encrypted blob is replayed", input[1]["encrypted_content"] as? String == "gAAAAAB_opaque")
    check("upstream accepts", upstreamRejection(input) == nil)
}

print("\n══ [2] fallback hop — the #368 regression ══")
do {
    // Same relay, different model entry: this is the common group-fallback hop
    // and it used to drop the head entirely because modelId no longer matched.
    let input = buildResponsesInput(toolTurn(model: "gpt-5.3", upstream: relayA),
                                   currentModelId: "gpt-5.1-fallback", currentUpstream: relayA)
    check("a reasoning item is STILL emitted after the hop", itemTypes(input).contains("reasoning"))
    check("the blob is replayed — same upstream can decrypt it",
          input[1]["encrypted_content"] as? String == "gAAAAAB_opaque")
    check("upstream accepts (pre-fix this was a 400)", upstreamRejection(input) == nil)

    // Different relay: keep the item, drop the payload. A blob minted elsewhere
    // is undecryptable and is what a strict endpoint rejects outright.
    let cross = buildResponsesInput(toolTurn(model: "gpt-5.3", upstream: relayA),
                                    currentModelId: "gpt-5.3", currentUpstream: relayB)
    check("a cross-upstream hop still emits the item", itemTypes(cross).contains("reasoning"))
    check("…but WITHOUT the foreign encrypted_content", cross[1]["encrypted_content"] == nil)
    check("…and the summary survives, so the turn is not bare", (cross[1]["summary"] as? [Any])?.isEmpty == false)
    check("upstream accepts", upstreamRejection(cross) == nil)
}

print("\n══ [3] cold start / session reopen — the synthetic head ══")
do {
    let reloaded = toolTurn(model: "gpt-5.3", upstream: relayA).map(afterDatabaseRoundTrip)
    check("the DB round trip drops the echo (unchanged, by design)", reloaded[1].reasoningEcho == nil)
    let input = buildResponsesInput(reloaded, currentModelId: "gpt-5.3", currentUpstream: relayA)
    checkEq("a synthetic reasoning head is emitted",
            itemTypes(input), ["user", "reasoning", "function_call", "function_call_output"])
    // [T-responses-no-synthetic-item-id] The head must carry NO id. A
    // synthesized one ("rs_syn_…") is a dangling server reference and 404s.
    check("it carries NO id at all", input[1]["id"] == nil)
    check("…specifically not a synthesized rs_syn_ id",
          !((input[1]["id"] as? String) ?? "").hasPrefix("rs_syn_"))
    check("no fabricated encrypted_content", input[1]["encrypted_content"] == nil)
    checkEq("summary is an empty array, never absent", (input[1]["summary"] as? [[String: Any]])?.count, 0)
    check("upstream accepts (pre-fix this was a 400)", upstreamRejection(input) == nil)

    // Stability matters for prompt caching: rebuilding the same history must
    // produce byte-identical ids.
    let again = buildResponsesInput(reloaded, currentModelId: "gpt-5.3", currentUpstream: relayA)
    checkEq("the synthetic id is stable across rebuilds",
            again[1]["id"] as? String, input[1]["id"] as? String)
}

print("\n══ [4] the placeholder must not over-fire ══")
do {
    // A text-only assistant turn has no function_call, so it needs no head.
    let textOnly = [
        Msg(role: .user, parts: [.text("hi")]),
        Msg(role: .assistant, parts: [.text("hello")]),
    ]
    let input = buildResponsesInput(textOnly, currentModelId: "gpt-5.3", currentUpstream: relayA)
    check("no reasoning item for a text-only assistant turn", !itemTypes(input).contains("reasoning"))
    checkEq("…and nothing else is added", itemTypes(input), ["user", "assistant"])

    // A real echo suppresses the synthetic one — never both.
    let withEcho = buildResponsesInput(toolTurn(model: "gpt-5.3", upstream: relayA),
                                       currentModelId: "gpt-5.3", currentUpstream: relayA)
    checkEq("exactly one reasoning item when a real echo exists",
            itemTypes(withEcho).filter { $0 == "reasoning" }.count, 1)

    // A foreign provider family is still dropped outright, then the synthetic
    // head covers the turn — so the request stays valid either way.
    var foreign = toolTurn(model: "gpt-5.3", upstream: relayA)
    foreign[1].reasoningEcho = ReasoningEcho(
        providerKind: "openai-chat", modelId: "gpt-5.3", upstreamIdentity: relayA,
        items: [.openaiReasoning(id: "rs_x", encryptedContent: "blob", summary: [])])
    let f = buildResponsesInput(foreign, currentModelId: "gpt-5.3", currentUpstream: relayA)
    check("a foreign providerKind falls through to the id-less head",
          f[1]["type"] as? String == "reasoning" && f[1]["id"] == nil)
    check("…carrying no foreign blob", f[1]["encrypted_content"] == nil)
    check("upstream accepts", upstreamRejection(f) == nil)

    // Legacy echo with no recorded upstream keeps the old model-id semantics.
    let legacy = buildResponsesInput(toolTurn(model: "gpt-5.3", upstream: nil),
                                     currentModelId: "gpt-5.3", currentUpstream: relayA)
    check("legacy echo (no upstream recorded) still replays on a model-id match",
          legacy[1]["encrypted_content"] as? String == "gAAAAAB_opaque")
    let legacyHop = buildResponsesInput(toolTurn(model: "gpt-5.3", upstream: nil),
                                        currentModelId: "other-model", currentUpstream: relayA)
    check("…and drops the blob on a model-id mismatch, as before",
          legacyHop[1]["encrypted_content"] == nil)
    check("…while still emitting the item so the turn is not bare",
          itemTypes(legacyHop).contains("reasoning"))
}

print("\n══ [5] shipping sources still carry the fix ══")
do {
    let agent = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    let proto = source("Providers/AgentProvider.swift")
    let row = source("Views/Chat/ChatMessageViews.swift")
    let banner = source("Views/Chat/AIChatView.swift")
    if agent.isEmpty || proto.isEmpty || row.isEmpty || banner.isEmpty {
        print("  ⏭  sources not readable from \(#filePath)")
    } else {
        check("the echo records the minting upstream",
              proto.contains("var upstreamIdentity: String? = nil"))
        check("the provider exposes an upstream identity",
              agent.contains("var reasoningUpstreamIdentity: String {"))
        check("capture stamps it",
              agent.contains("upstreamIdentity: self.reasoningUpstreamIdentity"))
        // The load-bearing change: the gate must NOT require a model-id match any
        // more, or the fallback hop regresses.
        // Punctuation-independent: the gate's `if` condition list must not
        // contain a model-id test in ANY form. An earlier version of this check
        // matched a trailing comma and so missed a mutation that re-added the
        // test as the final condition (`… { `), which is exactly the regression
        // this pins.
        let gateCondition: String = {
            guard let start = agent.range(of: "if msg.role == .assistant,\n               let echo = msg.reasoningEcho,") else { return "" }
            guard let brace = agent.range(of: " {", range: start.lowerBound..<agent.endIndex) else { return "" }
            return String(agent[start.lowerBound..<brace.upperBound])
        }()
        check("the gate's condition list was located", !gateCondition.isEmpty)
        check("the gate no longer hard-gates on model id",
              !gateCondition.contains("echo.modelId"))
        check("…and gates only on the provider family there",
              gateCondition.contains("echo.providerKind == Self.responsesAPIProviderKind"))
        check("…and decides the blob by upstream instead",
              agent.contains("return recorded == self.reasoningUpstreamIdentity"))
        check("the blob is conditional, the item is not",
              agent.contains("if sameUpstream, let encrypted, !encrypted.isEmpty {"))
        // [T-responses-no-synthetic-item-id] The load-bearing invariant of
        // this regression: the source must not fabricate a reasoning item id
        // ANYWHERE. `rs_` is a server-owned key, unlike `fc_` (a per-request
        // label whose real pairing key is `call_id`), so `fc_syn_` staying is
        // correct and must not be "tidied up" along with it.
        // Checked against CODE, not prose: the comment above the fix quotes the
        // old id and the 404 it caused, and that documentation must stay
        // legal. What must never come back is an `"id":` line building one.
        let agentCode = agent.split(separator: "\n")
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
            .joined(separator: "\n")
        check("the source never synthesizes a reasoning item id",
              !agentCode.contains("rs_syn_"))
        check("a tool turn with no echo still gets a head (issue #368 preserved)",
              agent.contains("if msg.role == .assistant, !emittedReasoningForTurn,"))
        check("…and that head carries no id",
              agent.contains("""
                result.append([
                    "type": "reasoning",
                    "summary": [] as [[String: Any]],
                ])
"""))
        check("…and never a fabricated encrypted_content",
              !agent.contains("\"encrypted_content\": \"rs_syn"))
        check("the function_call label trick is untouched (fc_ is not rs_)",
              agent.contains("fc_syn_"))
        // [T-error-detail-visible] Both error surfaces must be able to show the
        // appended upstream description, not just the trail lines.
        check("the bubble error is no longer clamped to 2 lines",
              row.contains(".lineLimit(8)") && !row.contains(".lineLimit(2)"))
        check("the banner is no longer clamped to 2 lines",
              banner.contains(".lineLimit(8)") && !banner.contains(".lineLimit(2)"))
        check("the banner top-aligns now that its text wraps",
              banner.contains("HStack(alignment: .top, spacing: 6) {"))
    }
}

print("\n══ [6] REGRESSION: Codex OAuth (\"OpenAI PLUS\") — the reported 404 ══")
// [T-responses-codex-oauth-404] Dedicated regression for THIS field report,
// pinned to the exact authorization scenario the user was running:
//
//     provider type .openAIResponses  +  Codex OAuth login
//     (isOAuth == true, customBaseURL == nil)  — shown as "OpenAI PLUS(iCloud)"
//
// Symptom after a TestFlight update (gone on rollback):
//     Provider error: [404] Item with id 'rs_syn_da20ae406016ab2468097788'
//     not found. Items are not persisted when store is set to false.
//
// Introduced by 04c80e68e, which synthesized `rs_syn_<call_id>` for a
// tool-calling turn with no reasoning echo. This section is deliberately NOT a
// generic provider mock: the classification below is the reason the first fix
// proposal was discarded, so it is pinned here.
do {
    // The provider fields for this instance, mirroring
    // LLMProviderFactory.makeOpenAIResponsesProvider's .oauth branch, which
    // sets forceResponsesAPI = true on ALL paths regardless of base URL.
    let codexOAuth = (forceResponsesAPI: true, isOAuth: true, customBaseURL: String?.none)

    // Why no provider-classification switch may gate the fix: this official
    // instance is INDISTINGUISHABLE from the #368 third-party relay by
    // `forceResponsesAPI`, and its `reasoningUpstreamIdentity` is the shared
    // constant, not a URL.
    check("Codex OAuth still sets forceResponsesAPI (so it cannot mean \"third-party\")",
          codexOAuth.forceResponsesAPI)
    check("…while having no custom base URL", codexOAuth.customBaseURL == nil)
    let upstreamIdentity = codexOAuth.customBaseURL ?? "openai-official"
    checkEq("…so its upstream identity is the official constant",
            upstreamIdentity, "openai-official")

    // A tool turn whose echo did not survive — the shape that reaches the
    // builder after a cold start, or when the turn produced no reasoning.
    let coldTurn = toolTurn(model: "gpt-5.6-sol", upstream: upstreamIdentity)
        .map(afterDatabaseRoundTrip)
    check("the turn arrives with no echo", coldTurn[1].reasoningEcho == nil)

    let input = buildResponsesInput(coldTurn, currentModelId: "gpt-5.6-sol",
                                    currentUpstream: upstreamIdentity)

    // The regression itself. `mintedReasoningIds` holds only ids this upstream
    // really issued, so a fabricated one is rejected exactly as the live
    // endpoint rejected it (verified 2026-09-22 against the ChatGPT Codex
    // backend: HTTP 404, same message).
    check("no reasoning item carries a fabricated id",
          !input.contains { ($0["type"] as? String) == "reasoning"
                            && (($0["id"] as? String)?.hasPrefix("rs_syn_") ?? false) })
    checkEq("the request is accepted by the upstream (was 404 before the fix)",
            upstreamRejection(input), nil)

    // Falsification: restoring the pre-fix emission must reproduce the user's
    // 404 verbatim, so this section cannot pass vacuously.
    var oldShape = input
    let anchorCallId = "call_abc123"
    oldShape.insert(["type": "reasoning",
                     "id": "rs_syn_\(anchorCallId.suffix(24))",
                     "summary": [] as [[String: Any]]], at: 1)
    let rejection = upstreamRejection(oldShape)
    check("PRE-FIX shape is rejected (falsification)", rejection != nil)
    check("…with the 404 the user reported",
          rejection?.contains("not found. Items are not persisted") == true)

    // #368 must stay fixed for this same path: the head is still present.
    checkEq("a reasoning head is still emitted before the function_call",
            itemTypes(input), ["user", "reasoning", "function_call", "function_call_output"])
}

print("\n══ [7] echo persistence — the hole the synthetic id was plugging ══")
// [T-responses-echo-persist] The placeholder only exists because the echo used
// to die on a DB round trip. Persisting it means a reopened session replays a
// REAL id, which is the only thing that may legally appear as `reasoning.id`.
do {
    let captured = ReasoningEcho(
        providerKind: responsesKind, modelId: "gpt-5.6-sol",
        upstreamIdentity: "openai-official",
        items: [.openaiReasoning(id: "rs_real_9", encryptedContent: "gAAAA_blob",
                                 summary: ["Checking the clock"])])
    mintedReasoningIds.insert("rs_real_9")

    // Port of ReasoningEcho.persistableJSON / init(persistedJSON:)
    // (Providers/AgentProvider.swift).
    struct WireItem: Codable { let id: String; let summary: [String] }
    struct Wire: Codable {
        let providerKind: String; let modelId: String
        let upstreamIdentity: String?; let items: [WireItem]
    }
    let wire = Wire(providerKind: captured.providerKind, modelId: captured.modelId,
                    upstreamIdentity: captured.upstreamIdentity,
                    items: captured.items.compactMap {
                        if case .openaiReasoning(let id, _, let sum) = $0 {
                            return WireItem(id: id, summary: sum)
                        }
                        return nil
                    })
    let json = String(data: try! JSONEncoder().encode(wire), encoding: .utf8)!
    check("the blob is NOT persisted (large, endpoint-bound, undecryptable elsewhere)",
          !json.contains("gAAAA_blob"))
    check("the server-minted id IS persisted", json.contains("rs_real_9"))
    check("…as is the upstream that minted it", json.contains("openai-official"))

    let back = try! JSONDecoder().decode(Wire.self, from: json.data(using: .utf8)!)
    let restored = ReasoningEcho(
        providerKind: back.providerKind, modelId: back.modelId,
        upstreamIdentity: back.upstreamIdentity,
        items: back.items.map { .openaiReasoning(id: $0.id, encryptedContent: nil, summary: $0.summary) })

    var reopened = toolTurn(model: "gpt-5.6-sol", upstream: "openai-official")
        .map(afterDatabaseRoundTrip)
    reopened[1].reasoningEcho = restored
    let input = buildResponsesInput(reopened, currentModelId: "gpt-5.6-sol",
                                    currentUpstream: "openai-official")
    checkEq("a reopened session replays the REAL id", input[1]["id"] as? String, "rs_real_9")
    check("…and sends no encrypted_content for it", input[1]["encrypted_content"] == nil)
    checkEq("…and the upstream accepts it", upstreamRejection(input), nil)

    // Source wiring — the three points that must move together.
    let store = source("Agent/Chat/ChatStore.swift")
    let agentP = source("Providers/AgentProvider.swift")
    let persist = source("Agent/Chat/AIChatViewModel+Persistence.swift")
    check("the column is created idempotently",
          store.contains("addColumnIfMissing(table: \"messages\", column: \"reasoning_echo\""))
    check("…is nullable with no DEFAULT (old rows stay distinguishable)",
          store.contains("column: \"reasoning_echo\", definition: \"TEXT\")"))
    check("it is written on persist", persist.contains("raw.reasoningEchoJSON = msg.reasoningEcho?.persistableJSON"))
    check("…and restored on load", store.contains("msg.reasoningEcho = ReasoningEcho(persistedJSON: reasoningEchoJSON)"))
    check("the codec refuses to persist encrypted_content",
          agentP.contains("encryptedContent") && !agentP.contains("let encryptedContent: String?\n            let summary"))
}

print("\n══ [8] id-only echo items are kept, not discarded ══")
// [T-responses-keep-id-only-echo] The capture filter used to drop an item with
// no blob and no summary as "nothing useful to echo". That emptied the echo and
// pushed the turn onto the placeholder path. What such an item carries is the
// real id — measured 200 when replayed under store:false.
do {
    let agent = source("Providers/OpenAI/OpenAIAgentProvider.swift")
    check("the drop-if-empty filter is gone",
          !agent.contains("if encrypted == nil && summary.isEmpty { return nil }"))
    check("…while a missing id still disqualifies an item (no id = nothing to replay)",
          agent.contains("guard let id = item[\"id\"] as? String else { return nil }"))
}

print("")
if failures == 0 {
    print("✅ ALL PASSED")
} else {
    print("❌ \(failures) FAILURE(S)")
}
exit(failures == 0 ? 0 : 1)
