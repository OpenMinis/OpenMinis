// Tests for [T-opencode-dedicated-channel] — OpenCode Go is served by its own
// dedicated channel, and every request of a conversation carries
// `x-opencode-session`.
//
// What changed from [T-opencode-session-header] (the shape this replaces):
//
//   * Membership is a property of the INSTANCE (`isOpenCodeChannel`), not a
//     base-URL sniff performed per request. Host matching survives only as the
//     auto-tag seed, so a user's own relay in front of OpenCode Go stays in the
//     channel.
//   * The id is resolved PER REQUEST via `perRequestHeaders`, not captured into
//     `extraHeaders` at construction. That is what fixes the first-turn hole: a
//     provider is built while a new chat is still a draft (sessionId nil), and
//     the real UUID only exists once `ensureSession()` persists the row. The
//     old shape sent the first turn with no header — a hard 400 from OpenCode
//     Go — and every later turn with one.
//
// Standalone (`swift OpenCodeSessionHeaderTests.swift`) for the same reason as
// the neighbouring files: the MinisTests target has a pre-existing compile
// break and the shipping types pull in the whole app graph.
//
// The rules are reproduced here; the anti-drift section re-reads the shipping
// source and fails if it changes, so this copy cannot drift.

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

// MARK: - Reproduced from OpenCodeChannel.swift

let openCodeSessionHeader = "x-opencode-session"
let draftSessionPrefix = "__new__"

func isOpenCodeBaseURL(_ base: String?) -> Bool {
    guard let raw = base?.trimmingCharacters(in: .whitespacesAndNewlines),
          !raw.isEmpty else { return false }
    let withScheme = raw.contains("://") ? raw : "https://" + raw
    guard let host = URLComponents(string: withScheme)?.host?.lowercased(),
          !host.isEmpty else { return false }
    return host == "opencode.ai" || host.hasSuffix(".opencode.ai")
}

/// Mirrors OpenCodeChannel.normalizedSessionId.
func normalizedSessionId(_ raw: String?) -> String? {
    guard let s = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
          !s.isEmpty,
          !s.hasPrefix(draftSessionPrefix) else { return nil }
    return s
}

/// Mirrors the per-request hook OpenCodeChannel.attach installs: the id is read
/// from a live resolver at request time, NOT captured when the provider is made.
func openCodeHook(inChannel: Bool,
                  resolveSessionId: @escaping () -> String?,
                  existing: (() -> [String: String])? = nil) -> () -> [String: String] {
    guard inChannel else { return { existing?() ?? [:] } }
    return {
        var headers = existing?() ?? [:]
        if headers[openCodeSessionHeader] == nil,
           let sid = normalizedSessionId(resolveSessionId()) {
            headers[openCodeSessionHeader] = sid
        }
        return headers
    }
}

let realSession = "3F71FD1D-5374-4254-8786-02C9A06FB787"

// MARK: - [1] Host matching

print("\n[1] Only OpenCode's own hosts match")
for good in [
    "https://opencode.ai/zen/go/v1",       // the documented endpoint
    "https://opencode.ai",
    "opencode.ai/zen/go/v1",               // user typed no scheme
    "  https://opencode.ai/zen/go/v1  ",   // stray whitespace
    "https://API.OpenCode.AI/zen/go/v1",   // case
    "https://api.opencode.ai/v1",          // subdomain
    "http://opencode.ai/v1",               // http
] { check("matches: \(good)", isOpenCodeBaseURL(good)) }

print("\n[2] Look-alike hosts must NOT match (this is why substring is wrong)")
for bad in [
    "https://opencode.ai.mycorp.net/v1",   // suffix attack — .contains would fire
    "https://my-opencode.ai-proxy.example/v1",
    "https://notopencode.ai/v1",           // no label boundary
    "https://opencode.aix/v1",
    "https://relay.example.com/opencode.ai/v1", // in the PATH, not the host
    "https://api.openai.com/v1",           // ordinary OpenAI
    "https://openrouter.ai/api",
    nil, "", "   ",
] { check("does NOT match: \(bad ?? "<nil>")", isOpenCodeBaseURL(bad), false) }

// Prove the substring approach actually differs — the reason for the stricter rule.
print("\n[3] The stricter rule genuinely changes behaviour")
let suffixAttack = "https://opencode.ai.mycorp.net/v1"
check("substring test WOULD have matched the look-alike", suffixAttack.contains("opencode.ai"))
check("host test does not", isOpenCodeBaseURL(suffixAttack), false)

// MARK: - Injection

// MARK: - [2] The channel decides, not the URL

print("\n[2] Membership is the gate — a relay in the channel still gets the header")
// This is the architectural change. A self-hosted relay does not look like
// OpenCode, but if the instance is in the channel the header must still go.
let relayHook = openCodeHook(inChannel: true, resolveSessionId: { realSession })
checkEq("relay in channel → header present", relayHook()[openCodeSessionHeader], realSession)

let officialButNotInChannel = openCodeHook(inChannel: false, resolveSessionId: { realSession })
check("an instance NOT in the channel never gets the header",
      officialButNotInChannel()[openCodeSessionHeader] == nil)

// MARK: - [3] Per-request resolution (the first-turn fix)

print("\n[3] A draft promoted mid-conversation is picked up with no rebuild")
// The regression this whole refactor exists for. The provider is built once,
// while the chat is still a draft; the real id appears later.
var liveId: String? = nil
let hook = openCodeHook(inChannel: true, resolveSessionId: { liveId })

check("turn 1 while still a draft → omitted, never faked", hook()[openCodeSessionHeader] == nil)
liveId = realSession                     // ensureSession() persists the row
checkEq("turn 2, same provider → header now present",
        hook()[openCodeSessionHeader], realSession)
checkEq("turn 3 → still present and unchanged",
        hook()[openCodeSessionHeader], realSession)

print("\n[4] The id is stable across every request of the conversation")
var seen = Set<String>()
for _ in 0..<50 { seen.insert(hook()[openCodeSessionHeader] ?? "<missing>") }
checkEq("one id for every request", seen, [realSession])

print("\n[5] A draft placeholder is never sent")
// Sending `__new__…` would be worse than sending nothing: OpenCode would key a
// cache entry to an id that is about to be replaced, reading one conversation
// as two.
for placeholder in ["__new__ABC", "__new__\(realSession)"] {
    var v: String? = placeholder
    let h = openCodeHook(inChannel: true, resolveSessionId: { v })
    check("placeholder \"\(placeholder.prefix(12))…\" omitted", h()[openCodeSessionHeader] == nil)
    v = realSession
    checkEq("…and the real id lands once promoted", h()[openCodeSessionHeader], realSession)
}

print("\n[6] Blank / empty ids are omitted, not sent as empty")
for empty in ["", "   ", "\n"] {
    let h = openCodeHook(inChannel: true, resolveSessionId: { empty })
    check("blank session omitted", h()[openCodeSessionHeader] == nil)
}

print("\n[7] An explicitly-set value wins over the inference")
let preset = openCodeHook(inChannel: true, resolveSessionId: { realSession },
                          existing: { [openCodeSessionHeader: "explicitly-set"] })
checkEq("pre-existing value preserved", preset()[openCodeSessionHeader], "explicitly-set")

print("\n[8] The hook composes with an existing per-request hook")
// Copilot installs its own perRequestHeaders; attaching the channel must merge
// rather than replace it.
let composed = openCodeHook(inChannel: true, resolveSessionId: { realSession },
                            existing: { ["x-other": "kept"] })
checkEq("the other hook's header survives", composed()["x-other"], "kept")
checkEq("and ours is added alongside", composed()[openCodeSessionHeader], realSession)

// MARK: - Anti-drift

print("\n[9] Shipping source still matches these assumptions")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

let ch = sourceOf("Providers/OpenAI/OpenCodeChannel.swift")
check("the dedicated channel exists", ch.contains("enum OpenCodeChannel"))
check("header constant present",
      ch.contains("static let sessionHeader = \"x-opencode-session\""))
check("host match is anchored, not a substring test",
      ch.contains("host == serviceHost || host.hasSuffix(\".\" + serviceHost)"))
// Strip doc comments first: the shipping source deliberately QUOTES the
// substring form in a comment explaining why it is wrong, and matching that
// prose would fail this check for the very reason it exists.
let chCode = ch.split(separator: "\n")
    .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
    .joined(separator: "\n")
check("does NOT use a bare substring test in CODE",
      chCode.contains("base.contains(\"opencode.ai\")"), false)
check("(sanity) the comment explaining the pitfall is still there",
      ch.contains("a substring test fires"))
check("the draft placeholder is rejected",
      ch.contains("!s.hasPrefix(Self.draftSessionPrefix)"))

// The whole point: a PER-REQUEST hook, not a construction-time snapshot.
check("attaches via perRequestHeaders", ch.contains("provider.perRequestHeaders = { body in"))
check("resolves the id lazily", ch.contains("resolveSessionId: @escaping @Sendable () -> String?"))
check("does NOT stamp extraHeaders (the bug being fixed)",
      chCode.contains("extraHeaders"), false)
check("composes with an existing hook", ch.contains("existing?(body) ?? [:]"))

// Membership is asked of the instance, not of the URL, at request time.
let inst = sourceOf("Providers/ProviderInstance.swift")
check("instances expose channel membership", inst.contains("var isOpenCodeChannel: Bool"))
check("non-OpenAI families can never join",
      inst.contains("case .anthropic, .gemini, .antigravity, .unsupported:"))

// The old API must be gone, or both shapes ship at once.
let f = sourceOf("Providers/LLMProviderFactory.swift")
check("old applyOpenCodeSession removed", f.contains("static func applyOpenCodeSession"), false)
check("old isOpenCodeBaseURL removed", f.contains("static func isOpenCodeBaseURL"), false)
check("factory gates on channel membership", f.contains("instance.isOpenCodeChannel"))
check("factory offers a live-resolver form",
      f.contains("resolveSessionId: @escaping @Sendable () -> String?"))

let vm = sourceOf("Agent/Chat/AIChatViewModel+ProviderFactory.swift")
check("the instance path passes a LIVE resolver, not a captured value",
      vm.contains("resolveSessionId: { box.value }"))
check("the static path still offers the resolver form",
      vm.contains("resolveSessionId: @escaping @Sendable () -> String?"))

// The mirror must be kept current at the single promotion choke point, or the
// first turn stays broken for a different reason.
let vmMain = sourceOf("Agent/Chat/AIChatViewModel.swift")
check("sessionId didSet updates the off-actor mirror",
      vmMain.contains("openCodeSessionBox.value = sessionId"))
check("the box is owned per conversation", vmMain.contains("let openCodeSessionBox = OpenCodeSessionBox()"))

let bridge = sourceOf("NativeOffloads/ModelUseOffloadBridge.swift")
check("model_use passes its caller session",
      bridge.contains("sessionId: ISHExecutionCoordinator.mountedSessionIdSnapshot"))

print("\n" + String(repeating: "\u{2500}", count: 60))
