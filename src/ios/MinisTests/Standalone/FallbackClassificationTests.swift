// [T31] Fallback classification and error-chain fidelity (iOS half).
//
// Issues: OpenMinis#229 (fallback sticks to the backup model with no
// cooldown / return to primary), #368 (upstream 400 "reasoning_text must be
// passed back" shown only as "retries exhausted"), #34 (a disabled provider
// still selectable through a model group). Android counterparts: f1f07a4e8
// (5xx must trigger fallback), ac5e6e6d0 (status carried structurally).
// The existing iOS Fallback503BudgetTests is XCTest (cannot run here) and
// covers the 5xx short budget only — no cooldown, no error chain.
//
// Standalone (`swift FallbackClassificationTests.swift`): the app cannot link
// for a simulator (deps/libs/libish_emu.a is device-only arm64). LLMError,
// OpenAIProvider.mapHTTPError, the retry-budget rule, groupExhaustedError and
// ModelGroupRouter's availability filter are ported verbatim (file:line
// cited); the group-fallback catch classification is reduced to a decision
// function; section [5] re-reads the shipping sources so the ports cannot
// drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func knownGap(_ label: String, _ holds: Bool) {
    if holds { print("  ✅ \(label) (gap closed)") } else { print("  ⚠️  KNOWN GAP: \(label)") }
}

// MARK: - Ported: LLMError (Providers/LLMError.swift)

struct Underlying: Error { let text: String }

enum LLMError: LocalizedError {
    case invalidAPIKey(detail: String = "")
    case networkError(underlying: Error)
    case providerError(message: String)
    case transientError(message: String, statusCode: Int? = nil)
    case decodingError(underlying: Error)
    case rateLimited
    case cancelled
    case unknown(underlying: Error?)

    var errorDescription: String? {
        switch self {
        case .invalidAPIKey(let detail): return detail.isEmpty ? "Invalid API key" : "Invalid API key: \(detail)"
        case .networkError(let error): return "Network error: \(error.localizedDescription)"
        case .providerError(let message): return "Provider error: \(message)"
        case .transientError(let message, _): return "Service temporarily unavailable: \(message)"
        case .decodingError(let error): return "Decoding error: \(error.localizedDescription)"
        case .rateLimited: return "Rate limited — please try again later"
        case .cancelled: return "Request was cancelled"
        case .unknown(let error): return "Unknown error: \(error?.localizedDescription ?? "no details")"
        }
    }
    var isRetryable: Bool {
        switch self {
        case .networkError, .transientError: return true
        case .invalidAPIKey, .providerError, .decodingError, .rateLimited, .cancelled, .unknown: return false
        }
    }
    var fallbackReason: String {
        switch self {
        case .rateLimited: return "Rate limited"
        case .invalidAPIKey: return "Invalid API key"
        case .providerError(let msg): return "Provider error: \(String(msg.prefix(60)))"
        default: return "Error"
        }
    }
    var isFallbackable: Bool {
        switch self {
        case .rateLimited, .invalidAPIKey, .providerError: return true
        case .transientError, .networkError, .decodingError, .cancelled, .unknown: return false
        }
    }
    var httpStatusCode: Int? {
        if case .transientError(_, let code) = self { return code }
        return nil
    }
    var isServerCapacityTransient: Bool {
        guard let code = httpStatusCode else { return false }
        return (500...599).contains(code)
    }
}

// MARK: - Ported: OpenAIProvider.mapHTTPError (OpenAIProvider.swift:2203-2220)

func mapHTTPError(statusCode: Int, body: String) -> LLMError {
    if statusCode == 401 || statusCode == 403 { return .invalidAPIKey(detail: "HTTP \(statusCode): \(String(body.prefix(200)))") }
    if statusCode == 429 { return .rateLimited }
    let transientStatusCodes: Set<Int> = [500, 502, 503, 504, 529]
    if transientStatusCodes.contains(statusCode) {
        return .transientError(message: "HTTP \(statusCode): \(body.prefix(200))", statusCode: statusCode)
    }
    if let data = body.data(using: .utf8),
       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
       let error = json["error"] as? [String: Any],
       let message = error["message"] as? String {
        return .providerError(message: "[\(statusCode)] \(message)")
    }
    return .providerError(message: "HTTP \(statusCode): \(body.prefix(500))")
}

// MARK: - Ported: retry budget + exhausted error (AIChatViewModel+Fallback.swift:28-40, 125-131)

let retryDelays = [3, 5, 10, 15, 30]
let serverCapacityRetryDelays = [2, 5]
func retryDelaysFor(error: Error, hasGroup: Bool) -> [Int] {
    guard hasGroup, (error as? LLMError)?.isServerCapacityTransient == true else { return retryDelays }
    return serverCapacityRetryDelays
}

typealias Trail = [(model: String, instance: String, reason: String)]
func groupExhaustedError(fallbackTrail: Trail, finalError: Error) -> Error {
    guard !fallbackTrail.isEmpty else { return finalError }
    let trailLines = fallbackTrail.map { "⚠️ \($0.model) (\($0.instance)): \($0.reason)" }
    let finalDesc = (finalError as? LocalizedError)?.errorDescription ?? finalError.localizedDescription
    return LLMError.providerError(message: trailLines.joined(separator: "\n") + "\n" + finalDesc)
}

// MARK: - Ported: the group-fallback catch classification (streamWithGroupFallback)

enum FallbackStrategy { case limited, always }
enum FallbackDecision: Equatable {
    /// `catch let error as LLMError where error.isFallbackable` → next entry now.
    case fallbackNow(reason: String)
    /// `.limited`: same-model retry ladder first, then fall back with "Retries exhausted".
    case retryThenFallback(delays: [Int])
    /// `.limited` with a non-retryable, non-fallbackable error: streamWithAutoRetry
    /// throws on the first attempt and the outer catch still advances.
    case fallbackAfterImmediateFailure
}

func classify(_ error: LLMError, strategy: FallbackStrategy, hasFallbackTarget: Bool) -> FallbackDecision {
    if error.isFallbackable { return .fallbackNow(reason: error.fallbackReason) }
    if strategy == .always { return .fallbackNow(reason: error.fallbackReason) }
    if error.isRetryable { return .retryThenFallback(delays: retryDelaysFor(error: error, hasGroup: hasFallbackTarget)) }
    return .fallbackAfterImmediateFailure
}

/// What the loop does after a successful stream on a different entry
/// (AIChatViewModel+Fallback.swift:344-360): the session binding is rewritten
/// to the entry that answered, and stays there.
struct Binding: Equatable { var resolvedEntryId: String }
func afterSuccess(on entryId: String, activeEntryId: inout String?, binding: inout Binding) {
    if entryId != activeEntryId {
        activeEntryId = entryId
        binding.resolvedEntryId = entryId
    }
}

// MARK: - Ported: ModelGroupRouter (Providers/ModelGroupRouter.swift)

struct Instance { let id: String; let label: String; var isEnabled: Bool; var hasAnyCredential: Bool }
struct Entry { let id: String; let displayName: String; let instanceId: String; var isHidden: Bool = false }
struct Store {
    var entries: [String: Entry]
    var instances: [String: Instance]
    func entry(for id: String) -> Entry? { entries[id] }
    func instance(for id: String) -> Instance? { instances[id] }
}
struct Group { let memberEntryIds: [String] }

func availableEntryIds(group: Group, store: Store) -> [String] {
    group.memberEntryIds.filter { entryId in
        guard let entry = store.entry(for: entryId) else { return false }
        guard !entry.isHidden else { return false }
        guard let instance = store.instance(for: entry.instanceId) else { return false }
        guard instance.isEnabled else { return false }
        guard instance.hasAnyCredential else { return false }
        return true
    }
}
func resolve(group: Group, store: Store) -> String? { availableEntryIds(group: group, store: store).first }
func nextFallback(group: Group, currentEntryId: String, store: Store) -> String? {
    let available = availableEntryIds(group: group, store: store)
    guard let currentIdx = available.firstIndex(of: currentEntryId) else { return available.first }
    let remaining = available[(currentIdx + 1)...]
    if let next = remaining.first { return next }
    let before = available[..<currentIdx]
    if let wrapped = before.first { return wrapped }
    return nil
}
func unavailableMembers(group: Group, store: Store) -> Trail {
    var result: Trail = []
    for entryId in group.memberEntryIds {
        guard let entry = store.entry(for: entryId), let inst = store.instance(for: entry.instanceId) else { continue }
        if entry.isHidden { result.append((entry.displayName, inst.label, "Hidden")) }
        else if !inst.isEnabled { result.append((entry.displayName, inst.label, "Disabled")) }
        else if !inst.hasAnyCredential { result.append((entry.displayName, inst.label, "Not logged in")) }
    }
    return result
}

// MARK: - [1] HTTP 5xx triggers fallback

print("\n[1] HTTP 503 → server-capacity transient → short budget, then fallback")
do {
    let e = mapHTTPError(statusCode: 503, body: "{\"error\":{\"message\":\"no_available_workers\"}}")
    check("503 maps to a transient carrying its status", e.httpStatusCode == 503 && e.isServerCapacityTransient)
    check("…still retryable on the same model first", e.isRetryable)
    check("…not an immediate fallback (limited semantics)", e.isFallbackable, false)
    checkEq("limited + group: short 2/5 s budget then fallback",
            classify(e, strategy: .limited, hasFallbackTarget: true), .retryThenFallback(delays: [2, 5]))
    checkEq("limited + no group: full ladder (nothing to fall back to)",
            classify(e, strategy: .limited, hasFallbackTarget: false), .retryThenFallback(delays: [3, 5, 10, 15, 30]))
    checkEq("always: falls back at once", classify(e, strategy: .always, hasFallbackTarget: true), .fallbackNow(reason: "Error"))
    check("PRE-FIX: the full ladder was 63 s on a dead deployment", retryDelays.reduce(0, +) == 63 && serverCapacityRetryDelays.reduce(0, +) == 7)

    for code in [500, 502, 504, 529] {
        check("HTTP \(code) also takes the short budget", mapHTTPError(statusCode: code, body: "x").isServerCapacityTransient)
    }
    // Structural, never a substring match on the body.
    let statusless = LLMError.transientError(message: "Server returned an empty response after 5030 tokens")
    check("a body mentioning 5030 is not a status", statusless.httpStatusCode == nil && !statusless.isServerCapacityTransient)
    checkEq("a status-less transient keeps the full ladder",
            classify(statusless, strategy: .limited, hasFallbackTarget: true), .retryThenFallback(delays: retryDelays))
    check("a dead link (networkError) is retryable but never fallbackable",
          LLMError.networkError(underlying: Underlying(text: "offline")).isRetryable
          && !LLMError.networkError(underlying: Underlying(text: "offline")).isFallbackable)

    // 429 / 401 / 400 classification (the #229 decision table).
    checkEq("429 → immediate fallback", classify(mapHTTPError(statusCode: 429, body: ""), strategy: .limited, hasFallbackTarget: true), .fallbackNow(reason: "Rate limited"))
    check("401 maps to invalidAPIKey", { if case .invalidAPIKey = mapHTTPError(statusCode: 401, body: "nope") { return true }; return false }())
    check("400 maps to providerError (fallbackable on iOS)", mapHTTPError(statusCode: 400, body: "bad").isFallbackable)
    knownGap("auth / config errors (401, 400) must NOT fall back so a broken key is not masked by the backup model (#229 §3) — iOS falls back on invalidAPIKey and providerError",
             !mapHTTPError(statusCode: 401, body: "").isFallbackable && !mapHTTPError(statusCode: 400, body: "").isFallbackable)
}

// MARK: - [2] Sticky fallback vs cooldown

print("\n[2] After a fallback the session binding follows the backup model — no cooldown / return to primary")
do {
    var activeEntryId: String? = "sol"
    var binding = Binding(resolvedEntryId: "sol")
    afterSuccess(on: "deepseek", activeEntryId: &activeEntryId, binding: &binding)
    checkEq("the answering entry becomes active", activeEntryId, "deepseek")
    checkEq("…and the session binding is rewritten to it", binding.resolvedEntryId, "deepseek")
    // The next request resolves from the binding: it starts on DeepSeek, not Sol.
    let nextRequestEntry = binding.resolvedEntryId
    knownGap("next request returns to the primary once a cooldown expires (#229 R4-R6) — iOS has no cooldown; the binding stays on the backup",
             nextRequestEntry == "sol")
    // What IS pinned: a success on the same entry does not churn the binding.
    var same: String? = "sol"; var b2 = Binding(resolvedEntryId: "sol")
    afterSuccess(on: "sol", activeEntryId: &same, binding: &b2)
    checkEq("success on the active entry leaves the binding alone", b2.resolvedEntryId, "sol")
}

// MARK: - [3] The upstream cause survives into the final error

print("\n[3] An upstream 400's text reaches the final error message")
do {
    let body = "{\"error\":{\"message\":\"The `reasoning_text` in the thinking mode must be passed back to the API.\"}}"
    let e = mapHTTPError(statusCode: 400, body: body)
    check("400 with a JSON error is a providerError carrying the message",
          e.errorDescription == "Provider error: [400] The `reasoning_text` in the thinking mode must be passed back to the API.")
    check("…and it is fallbackable (advances immediately)", classify(e, strategy: .limited, hasFallbackTarget: true) == .fallbackNow(reason: e.fallbackReason))
    check("the trail reason names the upstream cause (first 60 chars)", e.fallbackReason.contains("reasoning_text"))

    // Both entries fail; the last error is the same 400.
    let trail: Trail = [("GPT-6", "official", e.fallbackReason), ("GPT-6", "relay", e.fallbackReason)]
    let final = groupExhaustedError(fallbackTrail: trail, finalError: e)
    let msg = (final as? LocalizedError)?.errorDescription ?? ""
    check("the final message keeps the FULL upstream text", msg.contains("must be passed back to the API"))
    check("…and lists each failed member", msg.contains("⚠️ GPT-6 (official)") && msg.contains("⚠️ GPT-6 (relay)"))
    check("nothing in the chain reduces it to a bare 'retries exhausted'", !msg.lowercased().contains("retries exhausted") || msg.contains("reasoning_text"))

    // The #368 shape: the relay turned the 400 into 503 → transient → retried, then
    // "Retries exhausted" is the trail reason — but the last error's text still lands.
    let relay503 = mapHTTPError(statusCode: 503, body: "No available providers (last upstream: reasoning_text must be passed back)")
    let t2: Trail = [("GPT-6", "relay", "Retries exhausted")]
    let m2 = (groupExhaustedError(fallbackTrail: t2, finalError: relay503) as? LocalizedError)?.errorDescription ?? ""
    check("even after a retry ladder the last error's body is appended", m2.contains("reasoning_text must be passed back"))
    check("…after the 'Retries exhausted' trail line", m2.range(of: "Retries exhausted")!.lowerBound < m2.range(of: "reasoning_text")!.lowerBound)

    check("an empty trail returns the original error untouched",
          (groupExhaustedError(fallbackTrail: [], finalError: e) as? LLMError)?.errorDescription == e.errorDescription)
}

// MARK: - [4] Disabled providers are skipped by the group

print("\n[4] A group member whose provider is disabled is never selected")
do {
    let store = Store(
        entries: ["a": Entry(id: "a", displayName: "Coding Plan", instanceId: "plan"),
                  "b": Entry(id: "b", displayName: "PAYG", instanceId: "payg"),
                  "c": Entry(id: "c", displayName: "Hidden one", instanceId: "payg", isHidden: true),
                  "d": Entry(id: "d", displayName: "No key", instanceId: "nokey")],
        instances: ["plan": Instance(id: "plan", label: "Plan", isEnabled: false, hasAnyCredential: true),
                    "payg": Instance(id: "payg", label: "PAYG", isEnabled: true, hasAnyCredential: true),
                    "nokey": Instance(id: "nokey", label: "NoKey", isEnabled: true, hasAnyCredential: false)])
    let group = Group(memberEntryIds: ["a", "b", "c", "d"])
    checkEq("resolve skips the disabled first member", resolve(group: group, store: store), "b")
    checkEq("only enabled, visible, credentialed members are available", availableEntryIds(group: group, store: store), ["b"])
    check("nextFallback never lands on the disabled member", nextFallback(group: group, currentEntryId: "b", store: store) == nil)
    checkEq("the exhausted trail explains why each member was skipped",
            unavailableMembers(group: group, store: store).map { "\($0.model):\($0.reason)" },
            ["Coding Plan:Disabled", "Hidden one:Hidden", "No key:Not logged in"])

    // Re-enable → it is first again (the #34 workflow: disable while quota is out, re-enable later).
    var s2 = store; s2.instances["plan"]!.isEnabled = true
    checkEq("re-enabled provider is selected first again", resolve(group: group, store: s2), "a")
    checkEq("…and fallback from it reaches the PAYG member", nextFallback(group: group, currentEntryId: "a", store: s2), "b")
    checkEq("wrap-around from the last available member", nextFallback(group: group, currentEntryId: "b", store: s2), "a")
    check("an all-unavailable group resolves to nothing",
          resolve(group: Group(memberEntryIds: ["a", "d"]), store: store) == nil)
}

// MARK: - [5] Drift guards

print("\n[5] Shipping sources match these ports")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let errSrc = source("Providers/LLMError.swift")
let fb = source("Agent/Chat/AIChatViewModel+Fallback.swift")
let oai = source("Providers/OpenAI/OpenAIProvider.swift")
let router = source("Providers/ModelGroupRouter.swift")
if errSrc.isEmpty || fb.isEmpty || oai.isEmpty || router.isEmpty {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    check("transientError carries statusCode structurally", errSrc.contains("case transientError(message: String, statusCode: Int? = nil)"))
    check("isServerCapacityTransient is 5xx on the carried status",
          errSrc.contains("guard let code = httpStatusCode else { return false }\n        return (500...599).contains(code)"))
    check("providerError / rateLimited / invalidAPIKey are the fallbackable set",
          errSrc.contains("case .rateLimited, .invalidAPIKey, .providerError:\n            return true"))
    check("fallbackReason keeps the first 60 chars of a provider message",
          errSrc.contains("case .providerError(let msg): return \"Provider error: \\(String(msg.prefix(60)))\""))
    check("mapHTTPError sets the status on 5xx", oai.contains("return .transientError(message: \"HTTP \\(statusCode): \\(body.prefix(200))\", statusCode: statusCode)"))
    check("mapHTTPError keeps the upstream JSON message on other codes", oai.contains("return .providerError(message: \"[\\(statusCode)] \\(message)\")"))
    check("the short budget applies only with a group AND a 5xx",
          fb.contains("guard hasGroup, (error as? LLMError)?.isServerCapacityTransient == true else {\n            return retryDelays"))
    check("fallbackable errors advance immediately", fb.contains("} catch let error as LLMError where error.isFallbackable {"))
    check("the always strategy advances on any error", fb.contains("if groupFallbackStrategy == .always {"))
    check("groupExhaustedError appends the final error's full description",
          fb.contains("return LLMError.providerError(message: trailLines.joined(separator: \"\\n\") + \"\\n\" + finalDesc)"))
    check("retry exhaustion is recorded as a trail reason, not as the final error",
          fb.contains("reason: AppLocalized(\"Retries exhausted\")"))
    check("a fallback success rewrites the session binding (sticky)",
          fb.contains("activeEntryId = currentEntryId") && fb.contains("primarySource: .group(groupId: groupId, resolvedEntryId: currentEntryId)"))
    knownGap("a cooldown / return-to-primary mechanism exists in the fallback path (#229)",
             fb.lowercased().contains("cooldown") || router.lowercased().contains("cooldown"))
    check("the router filters disabled instances", router.contains("guard instance.isEnabled else {"))
    check("…and hidden entries and missing credentials",
          router.contains("guard !entry.isHidden else {") && router.contains("guard instance.hasAnyCredential else {"))
    check("resolve and nextFallback both go through availableEntryIds",
          router.components(separatedBy: "availableEntryIds(group: group, store: store)").count - 1 >= 2)
    check("unavailableMembers names Disabled", router.contains("reason: AppLocalized(\"Disabled\")"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
