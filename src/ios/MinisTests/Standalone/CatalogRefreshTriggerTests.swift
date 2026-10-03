// Tests for [T-provider-oauth-model-discovery] + [T-ios-refresh-models-empty-key]
// (M06) — the three ways a model catalog gets a LIVE list instead of this
// build's compiled-in guess.
//
//   1. Adding / re-authenticating an OAuth provider fires a one-shot refresh,
//      gated on the declarative `ProviderType.oauthSupportsModelDiscovery` bit
//      rather than on a hardcoded provider name (752056454, GH#265: xAI OAuth
//      seeded a static catalog with no grok-4.6, and only a manual
//      Settings → provider → Models → Refresh fixed it).
//   2. A keyless self-hosted endpoint (ollama, LM Studio, LiteLLM, an internal
//      gateway) can refresh. It stores NO Keychain entry at all, so every
//      `guard let key … else { throw .noCredential }` aborted before a request
//      was built and the user saw "No API key configured for this provider
//      instance." from a server that wanted no key (2337c6562). The predicate
//      already existed — `ProviderInstance.allowsEmptyAPIKey`, the same one that
//      makes such an instance read as configured in `hasAnyCredential`; refresh
//      was the one path that never consulted it.
//   3. xAI's OAuth branch reaches the real /models endpoint rather than
//      returning `XAIModelsAPI.allModels` (656a5f0b0 / f2be9a834, GH#265).
//
// The predicates and the gate are ported verbatim from ProviderTypes.swift:76,
// ProviderInstance.swift:260, ProviderConfigStore.addInstance (~830-895) and
// fetchModelsForInstance / fetchModelsWithFallback (~2740-3050); section [6]
// re-reads all four so the copies cannot drift.
//
// Standalone (`swift CatalogRefreshTriggerTests.swift`) like its neighbours.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported model of the types under test

enum ProviderType: String, CaseIterable {
    case anthropic, gemini, openAI, openAIResponses, antigravity
    case openRouter, xAI, kimiCode, githubCopilot, unsupported

    /// Verbatim from ProviderTypes.swift:76.
    var oauthSupportsModelDiscovery: Bool {
        switch self {
        case .anthropic, .gemini, .antigravity, .openRouter, .xAI, .kimiCode, .githubCopilot:
            return true
        case .openAI:      return false   // fetchModelsOAuth() is compiled-in
        case .openAIResponses: return false
        case .unsupported: return false
        }
    }

    /// Whether `builtInModels` is non-empty — only Copilot is deliberately empty.
    var hasBuiltInModels: Bool { self != .githubCopilot && self != .unsupported }
}

enum CredentialType { case apiKey, oauth }

struct Instance {
    var id = UUID().uuidString
    var providerType: ProviderType
    var credentialType: CredentialType
    var customBaseURL: String?
    /// Keychain state, modelled explicitly: nil = no entry at all.
    var storedAPIKey: String?
    var manualOAuthToken: String?

    /// Verbatim from ProviderInstance.swift:260.
    var allowsEmptyAPIKey: Bool {
        guard credentialType == .apiKey,
              let base = customBaseURL?.trimmingCharacters(in: .whitespacesAndNewlines),
              !base.isEmpty else { return false }
        switch providerType {
        case .openAI, .openAIResponses, .anthropic: return true
        default: return false
        }
    }

    /// The `allowsEmptyAPIKey` short-circuit at the top of
    /// computeHasAnyCredential — the reason these instances read as configured
    /// everywhere else, which is what made refresh's disagreement a bug.
    var hasAnyCredential: Bool {
        if allowsEmptyAPIKey { return true }
        if let k = storedAPIKey, !k.isEmpty { return true }
        if manualOAuthToken != nil { return true }
        return false
    }
}

/// Verbatim from ProviderConfigStore.modelFetchAPIKey.
func modelFetchAPIKey(for instance: Instance) -> String? {
    if let key = instance.storedAPIKey, !key.isEmpty { return key }
    return instance.allowsEmptyAPIKey ? "" : nil
}

/// The OLD behaviour, kept as a witness.
func legacyFetchAPIKey(for instance: Instance) -> String? { instance.storedAPIKey }

/// The `addInstance` OAuth-seed gate, verbatim.
func firesOneShotRefresh(_ instance: Instance) -> Bool {
    guard instance.credentialType == .oauth else {
        // Non-OAuth instances always refresh (the api-key / voice branches).
        return true
    }
    let hasManualToken = instance.manualOAuthToken != nil
    return hasManualToken || instance.providerType.oauthSupportsModelDiscovery
}

/// The OLD gate: manual token or OpenRouter only.
func legacyFiresOneShotRefresh(_ instance: Instance) -> Bool {
    guard instance.credentialType == .oauth else { return true }
    return instance.manualOAuthToken != nil || instance.providerType == .openRouter
}

// ---------------------------------------------------------------------------

print("\n[1] OAuth sign-in fires a one-shot catalog refresh")

let xaiOAuth = Instance(providerType: .xAI, credentialType: .oauth)
check("xAI OAuth refreshes after being added", firesOneShotRefresh(xaiOAuth))
check("…which the OLD gate did not (this is GH#265)", legacyFiresOneShotRefresh(xaiOAuth), false)

// All five providers the report named, plus Copilot which has no built-in list
// at all and therefore MUST fetch.
for t in [ProviderType.xAI, .kimiCode, .antigravity, .anthropic, .gemini, .githubCopilot] {
    check("\(t.rawValue) OAuth refreshes", firesOneShotRefresh(Instance(providerType: t, credentialType: .oauth)))
}
// The two that deliberately do NOT: their OAuth branch returns a compiled-in
// list, so a refresh would spend a round-trip to arrive back at the seed.
for t in [ProviderType.openAI, .openAIResponses] {
    check("\(t.rawValue) OAuth does NOT refresh (its branch is compiled-in)",
          firesOneShotRefresh(Instance(providerType: t, credentialType: .oauth)), false)
}
// …unless the user pasted a manual token, which is a real credential against a
// real endpoint.
check("a manual OAuth token forces a refresh even for openAI",
      firesOneShotRefresh(Instance(providerType: .openAI, credentialType: .oauth,
                                   manualOAuthToken: "sk-manual")))
// An instance synced from a newer build has nothing to fetch with.
check("an unsupported (future) provider type does not refresh",
      firesOneShotRefresh(Instance(providerType: .unsupported, credentialType: .oauth)), false)
// API-key instances were never gated.
check("an API-key instance always refreshes on add",
      firesOneShotRefresh(Instance(providerType: .xAI, credentialType: .apiKey, storedAPIKey: "k")))

print("\n[2] The capability bit is declarative — every type must answer it")

// The point of putting it on the TYPE: a provider added later has to answer,
// rather than silently inheriting "no" at one call site. This iterates the whole
// enum so a new case shows up here as an unreviewed answer.
var discovery: [String: Bool] = [:]
for t in ProviderType.allCases { discovery[t.rawValue] = t.oauthSupportsModelDiscovery }
checkEq("every provider type has an explicit answer", discovery.count, ProviderType.allCases.count)
checkEq("exactly three types answer false (openAI, openAIResponses, unsupported)",
        discovery.filter { !$0.value }.keys.sorted(),
        ["openAI", "openAIResponses", "unsupported"])
// A type with NO built-in list must be able to discover, or its picker is empty.
for t in ProviderType.allCases where !t.hasBuiltInModels && t != .unsupported {
    check("\(t.rawValue) has no built-in list, so it MUST support discovery",
          t.oauthSupportsModelDiscovery)
}

print("\n[3] Keyless self-hosted endpoint: an empty key does not block refresh")

// The reported configuration: a custom base URL, API-key mode, and no Keychain
// entry whatsoever.
let ollama = Instance(providerType: .openAI, credentialType: .apiKey,
                      customBaseURL: "http://192.168.1.50:11434/v1", storedAPIKey: nil)
check("the instance reads as configured everywhere else", ollama.hasAnyCredential)
checkEq("refresh gets an empty key and builds a request", modelFetchAPIKey(for: ollama), "")
checkEq("the OLD helper returned nil → .noCredential before any request",
        legacyFetchAPIKey(for: ollama), nil)

// The same for the other two types allowsEmptyAPIKey covers.
for t in [ProviderType.openAIResponses, .anthropic] {
    let inst = Instance(providerType: t, credentialType: .apiKey,
                        customBaseURL: "https://gateway.internal/v1", storedAPIKey: nil)
    checkEq("\(t.rawValue) + custom base + no key → empty key", modelFetchAPIKey(for: inst), "")
}

print("\n[4] …and the scope stays exactly as narrow as allowsEmptyAPIKey")

// Official endpoint (no custom base URL): an empty key is a misconfiguration,
// and saying so locally beats a confusing 401.
let officialOpenAI = Instance(providerType: .openAI, credentialType: .apiKey,
                              customBaseURL: nil, storedAPIKey: nil)
checkEq("official endpoint with no key → nil (.noCredential)",
        modelFetchAPIKey(for: officialOpenAI), nil)
check("…and it does not read as configured either", officialOpenAI.hasAnyCredential, false)
checkEq("a whitespace-only base URL is not a custom base URL",
        modelFetchAPIKey(for: Instance(providerType: .openAI, credentialType: .apiKey,
                                       customBaseURL: "   ", storedAPIKey: nil)), nil)
checkEq("an empty-string base URL likewise",
        modelFetchAPIKey(for: Instance(providerType: .openAI, credentialType: .apiKey,
                                       customBaseURL: "", storedAPIKey: nil)), nil)
// Types deliberately NOT extended — routing them through the helper would read
// as support that does not exist.
for t in [ProviderType.openRouter, .xAI, .kimiCode, .gemini, .antigravity, .githubCopilot] {
    let inst = Instance(providerType: t, credentialType: .apiKey,
                        customBaseURL: "https://relay.example/v1", storedAPIKey: nil)
    checkEq("\(t.rawValue) keeps its .noCredential guard", modelFetchAPIKey(for: inst), nil)
}
// OAuth mode is untouched: the credential is the token.
checkEq("an OAuth instance with a custom base URL is still not keyless",
        modelFetchAPIKey(for: Instance(providerType: .openAI, credentialType: .oauth,
                                       customBaseURL: "https://relay.example/v1")), nil)
// A real stored key always wins over the empty-key path.
checkEq("a stored key is used verbatim",
        modelFetchAPIKey(for: Instance(providerType: .openAI, credentialType: .apiKey,
                                       customBaseURL: "http://localhost:1234/v1",
                                       storedAPIKey: "sk-real")), "sk-real")
// An empty STRING in the Keychain behaves like no entry (the field deletes on
// empty, but a legacy row could exist).
checkEq("an empty stored string falls through to the keyless path",
        modelFetchAPIKey(for: Instance(providerType: .openAI, credentialType: .apiKey,
                                       customBaseURL: "http://localhost:1234/v1",
                                       storedAPIKey: "")), "")
checkEq("…and to nil for a type that is not keyless-capable",
        modelFetchAPIKey(for: Instance(providerType: .xAI, credentialType: .apiKey,
                                       customBaseURL: "http://localhost:1234/v1",
                                       storedAPIKey: "")), nil)

print("\n[5] .noCredential short-circuits the fallback ladder — so it must not fire spuriously")

// fetchModelsWithFallback rethrows ModelRefreshError immediately ("No
// credential — don't attempt fallback"), which is why a spurious .noCredential
// could not be rescued by models.dev or the built-in list either. Modelled so
// the interaction is visible, not just the helper.
enum Source: String, Equatable { case api, modelsDev = "models.dev", builtIn = "built-in" }
enum Fallback: Equatable { case got(Source), threwNoCredential, threwNoMatch }

func fetchWithFallback(_ instance: Instance,
                       apiSucceeds: Bool,
                       modelsDevHasMatch: Bool,
                       isThirdParty: Bool) -> Fallback {
    // Only the API-key branches consult modelFetchAPIKey; an OAuth branch's
    // credential is its token, resolved by its own manager.
    if instance.credentialType == .apiKey, modelFetchAPIKey(for: instance) == nil {
        return .threwNoCredential
    }
    if apiSucceeds { return .got(.api) }
    if isThirdParty { return .threwNoMatch }       // never invent GPT models for a local server
    if modelsDevHasMatch { return .got(.modelsDev) }
    if instance.providerType.hasBuiltInModels { return .got(.builtIn) }
    return .threwNoMatch
}

checkEq("keyless endpoint, server answers → api",
        fetchWithFallback(ollama, apiSucceeds: true, modelsDevHasMatch: false, isThirdParty: true),
        .got(.api))
checkEq("keyless endpoint unreachable → no-match, NOT a built-in GPT list",
        fetchWithFallback(ollama, apiSucceeds: false, modelsDevHasMatch: true, isThirdParty: true),
        .threwNoMatch)
checkEq("official endpoint with no key still short-circuits the whole ladder",
        fetchWithFallback(officialOpenAI, apiSucceeds: true, modelsDevHasMatch: true, isThirdParty: false),
        .threwNoCredential)
checkEq("the OLD helper made the keyless case unrescuable too",
        legacyFetchAPIKey(for: ollama) == nil, true)
// The OAuth seed path relies on this ladder being non-destructive: a failed
// one-shot refresh must land back on the seed that is already on screen.
checkEq("a failed refresh for a discoverable OAuth provider falls back to built-in",
        fetchWithFallback(Instance(providerType: .xAI, credentialType: .oauth,
                                   manualOAuthToken: "t"),
                          apiSucceeds: false, modelsDevHasMatch: false, isThirdParty: false),
        .got(.builtIn))

print("\n[6] Source-grep drift guard")

func source(_ rel: String) -> String {
    var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    while !FileManager.default.fileExists(atPath: root.appendingPathComponent(rel).path),
          root.pathComponents.count > 1 { root.deleteLastPathComponent() }
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let types = source("src/ios/Providers/ProviderTypes.swift")
let inst = source("src/ios/Providers/ProviderInstance.swift")
let store = source("src/ios/Providers/ProviderConfigStore.swift")
let detail = source("src/ios/Views/Providers/ProviderInstanceDetailView.swift")
check("ProviderTypes read", !types.isEmpty)
check("ProviderInstance read", !inst.isEmpty)
check("ProviderConfigStore read", !store.isEmpty)
check("ProviderInstanceDetailView read", !detail.isEmpty)

// 1 — the capability bit and its gate.
check("oauthSupportsModelDiscovery is a property of the TYPE",
      types.contains("var oauthSupportsModelDiscovery: Bool"))
check("its true-list names the seven discovering providers",
      types.contains("case .anthropic, .gemini, .antigravity, .openRouter, .xAI, .kimiCode, .githubCopilot:"))
check("openAI answers false explicitly", types.contains("case .openAI:\n            // OpenAIModelsAPI.fetchModelsOAuth() is a compiled-in list.\n            return false"))
check("addInstance gates on the capability bit, not a provider name",
      store.contains("if hasManualToken || instance.providerType.oauthSupportsModelDiscovery {"))
check("…and the refresh is fired after the seed is committed", {
    guard let seed = store.range(of: "config.modelEntries.append(contentsOf: entries)"),
          let gate = store.range(of: "if hasManualToken || instance.providerType.oauthSupportsModelDiscovery {")
    else { return false }
    return seed.lowerBound < gate.lowerBound
}())
check("the refresh is fire-and-forget (does not block the sheet)",
      store.contains("Task { await refreshModels(for: instance) }"))
// Re-authentication of an EXISTING instance, and the Kimi device-code sheet.
checkEq("the detail view consults the same bit (re-auth + device-code paths)",
        detail.components(separatedBy: "oauthSupportsModelDiscovery").count - 1, 3)

// 2 — the keyless helper and exactly which cases route through it.
check("modelFetchAPIKey exists", store.contains("private static func modelFetchAPIKey(for instance: ProviderInstance) -> String?"))
check("it returns \"\" only when allowsEmptyAPIKey", store.contains("return instance.allowsEmptyAPIKey ? \"\" : nil"))
check("a non-empty stored key wins", store.contains("if let key = ProviderKeychainHelper.loadAPIKey(instanceId: instance.id), !key.isEmpty {\n            return key\n        }"))
checkEq("exactly three fetch cases route through it (anthropic/openAI/openAIResponses apiKey)",
        store.components(separatedBy: "guard let key = modelFetchAPIKey(for: instance) else").count - 1, 3)
check("allowsEmptyAPIKey still requires apiKey mode + a non-empty custom base URL",
      inst.contains("guard credentialType == .apiKey,")
        && inst.contains("let base = customBaseURL?.trimmingCharacters(in: .whitespacesAndNewlines),")
        && inst.contains("!base.isEmpty else { return false }"))
check("…and is limited to openAI / openAIResponses / anthropic",
      inst.contains("case .openAI, .openAIResponses, .anthropic:\n            return true"))
check("hasAnyCredential short-circuits on it (why refresh's disagreement was a bug)",
      inst.contains("if allowsEmptyAPIKey { return true }"))
check(".noCredential still short-circuits the fallback ladder",
      store.contains("throw error  // No credential — don't attempt fallback"))

// 3 — xAI OAuth reaches a real endpoint.
check("the xAI OAuth branch calls OpenAIModelsAPI.fetchModels", {
    guard let r = store.range(of: "case (.xAI, .oauth):") else { return false }
    let branch = String(store[r.upperBound...]).prefix(900)
    return branch.contains("OpenAIModelsAPI.fetchModels(apiKey: token")
        && !branch.contains("XAIModelsAPI.allModels")
}())
check("…against api.x.ai/v1 unless a custom base URL is set", {
    guard let r = store.range(of: "case (.xAI, .oauth):") else { return false }
    let branch = String(store[r.upperBound...]).prefix(900)
    return branch.contains("let xaiBase = customBase ?? \"https://api.x.ai/v1\"")
}())
check("…using the live OAuth access token when there is no manual token", {
    guard let r = store.range(of: "case (.xAI, .oauth):") else { return false }
    let branch = String(store[r.upperBound...]).prefix(900)
    return branch.contains("XAIOAuthManager.shared.validAccessToken(instanceId: instance.id)")
}())
check("xAI's built-in list is only the SEED, still available as the last tier",
      types.contains("case .xAI: return XAIModelsAPI.allModels"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
