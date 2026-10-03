// Tests for [T-codex-model-discovery] (issue #319) — Codex OAuth model
// discovery with three-tier degradation.
//
// Standalone (`swift CodexModelDiscoveryTests.swift`) for the same reason as
// the neighbouring files: the MinisTests target has a pre-existing compile
// break and the shipping types pull in the whole app graph.
//
// The payload parser and the merge rule are reproduced here so they can be
// exercised against real-shaped JSON; section [5] re-reads the shipping source
// so the copies cannot drift from what ships.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced from OpenAIModelsAPI.parseCodexModels

struct M: Equatable {
    var id: String; var displayName: String
    var contextWindow: Int?; var maxOutputTokens: Int?; var supportsReasoning: Bool?
}

func parseCodexModels(_ data: Data) -> [M] {
    let root = try? JSONSerialization.jsonObject(with: data)
    let items: [[String: Any]]
    if let arr = root as? [[String: Any]] { items = arr }
    else if let obj = root as? [String: Any] {
        items = (obj["models"] as? [[String: Any]]) ?? (obj["data"] as? [[String: Any]]) ?? []
    } else { items = [] }

    return items.compactMap { item -> M? in
        guard let id = (item["id"] as? String) ?? (item["slug"] as? String) ?? (item["model"] as? String),
              !id.isEmpty else { return nil }
        if let v = (item["visibility"] as? String)?.lowercased(),
           v == "hidden" || v == "internal" || v == "none" { return nil }
        for flag in ["is_visible", "visible", "enabled", "available"] {
            if let b = item[flag] as? Bool, b == false { return nil }
        }
        let display = (item["display_name"] as? String) ?? (item["displayName"] as? String)
            ?? (item["name"] as? String) ?? id
        var m = M(id: id, displayName: display, contextWindow: nil, maxOutputTokens: nil, supportsReasoning: nil)
        m.contextWindow = (item["context_window"] as? Int) ?? (item["contextWindow"] as? Int)
            ?? ((item["limit"] as? [String: Any])?["context"] as? Int)
        m.maxOutputTokens = (item["max_output_tokens"] as? Int) ?? (item["maxOutputTokens"] as? Int)
            ?? ((item["limit"] as? [String: Any])?["output"] as? Int)
        m.supportsReasoning = (item["supports_reasoning"] as? Bool) ?? (item["reasoning"] as? Bool)
        return m
    }
}

func json(_ s: String) -> Data { s.data(using: .utf8)! }

// MARK: - [1] Real reported payload

print("\n[1] Models the built-in list does not contain are discovered")
// The ids the reporter actually observed from a live account.
let reported = json("""
[{"id":"gpt-6-astra"},{"id":"gpt-reserve"},{"id":"gpt-5.6-sol"},{"id":"gpt-5.6-terra"},
 {"id":"gpt-5.6-luna"},{"id":"gpt-5.5"},{"id":"gpt-5.4-mini"},{"id":"gpt-5.3-codex-spark"},
 {"id":"codex-auto-review"}]
""")
let parsed = parseCodexModels(reported)
checkEq("all 9 reported ids parsed", parsed.count, 9)
// `gpt-5.3-codex-spark` was explicitly REMOVED from the built-in list as a
// 400 (see LLMModel.allOpenAICodexOAuth's T-codex-oauth-model-prune note), so
// discovery surfacing it is exactly the "not in the hardcoded catalog" case.
check("surfaces an id absent from the built-in list",
      parsed.contains { $0.id == "gpt-5.3-codex-spark" })
check("surfaces codex-auto-review", parsed.contains { $0.id == "codex-auto-review" })

print("\n[2] Hidden / unavailable entries stay hidden")
let mixed = json("""
{"models":[
  {"id":"visible-one"},
  {"id":"hidden-one","visibility":"hidden"},
  {"id":"internal-one","visibility":"internal"},
  {"id":"none-one","visibility":"NONE"},
  {"id":"disabled-one","enabled":false},
  {"id":"unavailable-one","available":false},
  {"id":"invisible-one","is_visible":false},
  {"id":"still-visible","visibility":"public"}
]}
""")
let vis = parseCodexModels(mixed).map(\.id)
checkEq("only visible entries survive", vis, ["visible-one", "still-visible"])

print("\n[3] Shape tolerance — an undocumented endpoint may move keys")
checkEq("bare array", parseCodexModels(json("[{\"id\":\"a\"}]")).count, 1)
checkEq("wrapped in .models", parseCodexModels(json("{\"models\":[{\"id\":\"a\"}]}")).count, 1)
checkEq("wrapped in .data", parseCodexModels(json("{\"data\":[{\"id\":\"a\"}]}")).count, 1)
checkEq("slug instead of id", parseCodexModels(json("[{\"slug\":\"b\"}]")).first?.id, "b")
checkEq("unknown shape degrades to empty, not a crash",
        parseCodexModels(json("{\"unexpected\":true}")).count, 0)
checkEq("garbage degrades to empty", parseCodexModels(json("not json")).count, 0)
checkEq("entry without any id is skipped", parseCodexModels(json("[{\"name\":\"x\"}]")).count, 0)
// Metadata, in both spellings.
let meta = parseCodexModels(json("""
[{"id":"m1","display_name":"M One","context_window":400000,"max_output_tokens":128000,"supports_reasoning":true},
 {"id":"m2","name":"M Two","limit":{"context":272000,"output":64000},"reasoning":false}]
"""))
checkEq("snake_case metadata", meta[0].contextWindow, 400000)
checkEq("display_name used", meta[0].displayName, "M One")
checkEq("nested limit{} metadata", meta[1].contextWindow, 272000)
checkEq("reasoning flag", meta[1].supportsReasoning, false)
checkEq("falls back to id when unnamed",
        parseCodexModels(json("[{\"id\":\"bare\"}]")).first?.displayName, "bare")

print("\n[4] Merge: endpoint wins, models.dev fills the gaps")
// Mirrors mergeKeepingAuthoritative.
func merge(fresh: [M], enriched: [M]) -> [M] {
    let byId = Dictionary(enriched.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    return fresh.map { auth in
        guard var m = byId[auth.id] else { return auth }
        if auth.contextWindow != nil { m.contextWindow = auth.contextWindow }
        if auth.maxOutputTokens != nil { m.maxOutputTokens = auth.maxOutputTokens }
        if auth.supportsReasoning != nil { m.supportsReasoning = auth.supportsReasoning }
        return m
    }
}
// The endpoint says 400k; a stale models.dev entry says 272k.
let fresh = [M(id: "x", displayName: "X", contextWindow: 400_000, maxOutputTokens: nil, supportsReasoning: nil)]
let stale = [M(id: "x", displayName: "X", contextWindow: 272_000, maxOutputTokens: 64_000, supportsReasoning: true)]
let merged = merge(fresh: fresh, enriched: stale)
checkEq("fresh authoritative value is NOT overwritten", merged[0].contextWindow, 400_000)
checkEq("gap is filled from models.dev", merged[0].maxOutputTokens, 64_000)
checkEq("second gap filled too", merged[0].supportsReasoning, true)
// A model models.dev has never heard of survives untouched.
let unknown = merge(fresh: [M(id: "brand-new", displayName: "New", contextWindow: nil,
                              maxOutputTokens: nil, supportsReasoning: nil)], enriched: [])
checkEq("unknown-to-models.dev model still returned", unknown.count, 1)
checkEq("…with its id intact", unknown[0].id, "brand-new")

print("\n[4b] Image models survive a successful tier-1 discovery")
// The endpoint lists CHAT models only; the image generators reach the same
// account through the image_generation tool instead. A tier-1 refresh must
// therefore union them back in, or they vanish from the picker.
let imageIds = ["gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare"]
func appendBuiltInImageModels(to discovered: [String]) -> [String] {
    let known = Set(discovered)
    return discovered + imageIds.filter { !known.contains($0) }
}
// Real endpoint payload — no image model anywhere in it.
let chatOnly = parseCodexModels(reported).map(\.id)
check("endpoint itself lists no image model", chatOnly.contains { $0.contains("image") }, false)
let withImages = appendBuiltInImageModels(to: chatOnly)
for id in imageIds { check("\(id) present after append", withImages.contains(id)) }
checkEq("chat models untouched", Array(withImages.prefix(chatOnly.count)), chatOnly)
checkEq("exactly three were added", withImages.count - chatOnly.count, 3)
// If Codex ever starts listing one, its own entry wins and is not duplicated.
let alreadyListed = appendBuiltInImageModels(to: chatOnly + ["gpt-image-2"])
checkEq("no duplicate when the endpoint already names one",
        alreadyListed.filter { $0 == "gpt-image-2" }.count, 1)
checkEq("…and the other two are still added", alreadyListed.count, chatOnly.count + 3)

print("\n[4c] Whole tier ladder (M03): discovery UNIONS, never replaces")
// [T-codex-discovery-union] The three tiers of fetchModelsOAuth, modelled end
// to end so the *relationship* between them is pinned, not just tier 1 in
// isolation. Pins 07d35e6ea + 580e287da. The regression these guard against is
// "a successful refresh came back with 11 chat models and no image model",
// i.e. tier 1 REPLACING the catalog instead of unioning the image generators
// back in. Tiers 2/3 build from `allOpenAICodexOAuth`, which already contains
// them — so the image ids must be present in EVERY outcome except a hard auth
// failure, which is the only case that is allowed to surface as an error.
enum Tier1: Equatable { case ok([String]), emptyOK, failed, authFailed }
enum Outcome: Equatable { case models([String]), thrownAuthError }

/// Mirrors OpenAIModelsAPI.fetchModelsOAuth's control flow.
func fetchModelsOAuth(tier1: Tier1,
                      primaryCache: [String]? = nil,
                      lastKnownGood: [String]? = nil,
                      builtIn: [String],
                      forceRefresh: Bool = false) -> Outcome {
    switch tier1 {
    case .authFailed:
        // `if case .invalidAPIKey = error { throw error }` — never degrade.
        return .thrownAuthError
    case .ok(let fresh):
        if !forceRefresh, let primaryCache { return .models(primaryCache) }
        if !fresh.isEmpty { return .models(appendBuiltInImageModels(to: fresh)) }
        return .models(builtIn)          // 200 with nothing usable → fall through
    case .emptyOK:
        if !forceRefresh, let primaryCache { return .models(primaryCache) }
        return .models(builtIn)
    case .failed:
        if let lastKnownGood { return .models(lastKnownGood) }
        return .models(builtIn)
    }
}

// Tiers 2/3 are `enrichModels(LLMModel.allOpenAICodexOAuth)`, which contains
// the chat ids AND the three image ids.
let builtInList = ["gpt-5.6-sol", "gpt-5.5"] + imageIds
let chatDiscovered = ["gpt-6-astra", "gpt-5.6-sol", "gpt-5.5"]

let tier1OK = fetchModelsOAuth(tier1: .ok(chatDiscovered), builtIn: builtInList, forceRefresh: true)
if case .models(let ids) = tier1OK {
    check("tier 1: the newly discovered id is present", ids.contains("gpt-6-astra"))
    for id in imageIds { check("tier 1: \(id) survives discovery", ids.contains(id)) }
    checkEq("tier 1: nothing else is invented", ids.count, chatDiscovered.count + imageIds.count)
} else { check("tier 1 returned models", false) }

// Discovery FAILURE (network / 500 / bad shape) with no cached copy → tiers 2+3.
let onFail = fetchModelsOAuth(tier1: .failed, builtIn: builtInList)
if case .models(let ids) = onFail {
    checkEq("discovery failure falls back to models.dev + built-in", ids, builtInList)
    for id in imageIds { check("failure fallback still has \(id)", ids.contains(id)) }
    check("…and does not surface as an error", true)
} else { check("failure fell back to a list", false) }

// Discovery failure WITH a last-known-good copy → that copy (which itself came
// from the union, so it carries the image ids).
let lkg = appendBuiltInImageModels(to: chatDiscovered)
if case .models(let ids) = fetchModelsOAuth(tier1: .failed, lastKnownGood: lkg, builtIn: builtInList) {
    checkEq("failure prefers the last-known-good catalog over the built-in list", ids, lkg)
    for id in imageIds { check("last-known-good carries \(id)", ids.contains(id)) }
} else { check("last-known-good served", false) }

// HTTP 200 with an unparseable / empty body is NOT an auth problem.
if case .models(let ids) = fetchModelsOAuth(tier1: .emptyOK, builtIn: builtInList) {
    checkEq("empty-but-OK response falls through to the built-in list", ids, builtInList)
} else { check("empty-OK fell back rather than throwing", false) }

// An auth failure is the ONLY outcome allowed to throw.
checkEq("401/403 is rethrown, never degraded to a healthy-looking catalog",
        fetchModelsOAuth(tier1: .authFailed, lastKnownGood: lkg, builtIn: builtInList),
        .thrownAuthError)

// The cache short-circuits tier 1 only when forceRefresh is false, and the
// cached copy is itself a union'd list.
let cached = appendBuiltInImageModels(to: chatDiscovered)
if case .models(let ids) = fetchModelsOAuth(tier1: .ok(chatDiscovered), primaryCache: cached,
                                            builtIn: builtInList, forceRefresh: false) {
    checkEq("cache hit serves the cached union", ids, cached)
} else { check("cache hit served", false) }
if case .models(let ids) = fetchModelsOAuth(tier1: .ok(["only-new"]), primaryCache: cached,
                                            builtIn: builtInList, forceRefresh: true) {
    check("forceRefresh ignores the cache and re-unions", ids.contains("only-new"))
    for id in imageIds { check("forceRefresh result still has \(id)", ids.contains(id)) }
} else { check("forceRefresh fetched", false) }

// The union is idempotent: refreshing twice must not grow the list.
checkEq("appending twice is idempotent",
        appendBuiltInImageModels(to: appendBuiltInImageModels(to: chatDiscovered)),
        appendBuiltInImageModels(to: chatDiscovered))
// And it never reorders or drops the authoritative half.
checkEq("the discovered half keeps its order and position",
        Array(appendBuiltInImageModels(to: chatDiscovered).prefix(chatDiscovered.count)),
        chatDiscovered)

print("\n[5] Shipping source matches these assumptions")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let api = source("Providers/OpenAI/OpenAIModelsAPI.swift")
let store = source("Providers/ProviderConfigStore.swift")
let provider = source("Providers/OpenAI/OpenAIProvider.swift")
if api.isEmpty {
    print("  ⏭  source not readable from this sandbox")
} else {
    check("uses the codex models endpoint",
          api.contains("https://chatgpt.com/backend-api/codex/models?client_version="))
    check("does NOT use the 403 /backend-api/models path",
          api.contains("\"https://chatgpt.com/backend-api/models"), false)
    check("sends Minis' own originator, not the reporter's 'omp'",
          api.contains("request.setValue(\"codex_cli_rs\", forHTTPHeaderField: \"Originator\")"))
    check("no 'omp' originator anywhere", api.contains("\"omp\""), false)
    check("discovery reuses the inference client version",
          api.contains("OpenAIProvider.codexClientVersion"))
    check("auth failures are rethrown, not swallowed",
          api.contains("if case .invalidAPIKey = error { throw error }"))
    check("cache key includes account id and client version",
          api.contains("\"codex-oauth|\\(accountId ?? \"-\")|\\(OpenAIProvider.codexClientVersion)|\\(token)\""))
    check("forceRefresh bypasses the cache",
          api.contains("if !forceRefresh, let cached = OpenAIModelsCache.load(credential: cacheKey)"))
    check("built-in list remains the last tier",
          api.contains("ModelsDevAPI.enrichModels(LLMModel.allOpenAICodexOAuth)"))
    check("merge helper present", api.contains("mergeKeepingAuthoritative"))
    check("tier-1 re-appends the image models",
          api.contains("let models = appendBuiltInImageModels(to: discovered)"))
    check("append helper filters by the shared image-id set",
          api.contains("LLMModel.allCodexOAuthImageModelIDs.contains($0.id)"))
    check("append helper skips ids the endpoint already named",
          api.contains("&& !known.contains($0.id)"))
    check("call site passes instance + forceRefresh",
          store.contains("fetchModelsOAuth(\n                instanceId: instance.id, forceRefresh: forceRefresh)"))
    check("manual-token OAuth branch untouched",
          store.contains("loadOAuthString(instanceId: instance.id, account: \"manual-oauth-token\")"))
    // Requirement 6: the version must clear every gated model's
    // `minimal_client_version` — 0.153.0 for gpt-6-astra, 0.155.0 for
    // gpt-6-sol / gpt-6-luna [T-gpt6-sol-luna]. The field is a floor, so the
    // highest requirement wins.
    check("client version is at least 0.155.x", provider.contains("codexClientVersion = \"0.155."))

    // [4c] drift guards — the tier ladder's SHAPE, not just its helpers.
    check("tier 1 returns the UNION, not the raw discovered list",
          api.contains("let models = appendBuiltInImageModels(to: discovered)")
            && api.contains("return models"))
    check("the raw merge result is never returned directly",
          api.contains("return discovered\n"), false)
    check("tiers 2+3 are built from the built-in list (which holds the image ids)",
          api.contains("ModelsDevAPI.enrichModels(LLMModel.allOpenAICodexOAuth)"))
    check("a 200 with no usable models falls through instead of throwing",
          api.contains("endpoint returned no usable models — falling back"))
    check("a non-auth failure falls back to the last-known-good key",
          api.contains("OpenAIModelsCache.load(credential: lastKnownGoodKey(instanceId: instanceId"))
    check("the last-known-good key is token-independent",
          api.contains("\"codex-oauth-last|\\(instanceId)|\\(accountId ?? \"-\")\""))
    check("the union'd list is what gets cached under BOTH keys", {
        guard let r = api.range(of: "let models = appendBuiltInImageModels(to: discovered)") else { return false }
        let after = String(api[r.upperBound...]).prefix(700)
        return after.contains("OpenAIModelsCache.save(models, credential: cacheKey)")
            && after.contains("OpenAIModelsCache.save(models, credential: lastKnownGoodKey(")
    }())
    check("the image-id union is a named step, not folded into the merge",
          api.contains("private static func appendBuiltInImageModels(to discovered: [LLMModel])"))
    check("merge keeps endpoint-stated fields and fills only nil gaps",
          api.contains("if authoritative.contextWindow != nil { merged.contextWindow = authoritative.contextWindow }")
            && api.contains("if authoritative.modalityOverride != nil"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
