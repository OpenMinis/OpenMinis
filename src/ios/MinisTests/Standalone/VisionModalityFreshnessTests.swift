// Tests for [T-ios-vision-modality-freshness] (M09, issue #340) — Vision-Group
// membership and `hasImageInput` are re-evaluated from the LIVE catalog entry
// after a refresh, never from the snapshot taken when the model was added.
//
// The field case: DeepSeek V4.1 Flash gained image input, the user refreshed the
// model list, and the app kept routing its images through the Vision Group and
// kept emitting the "here is a path, use shell_execute" placeholder — because
// the capability being consulted was the one frozen at add time.
//
// On iOS the freshness comes from three facts that have to hold TOGETHER, and
// each is a separate way to reintroduce the bug:
//
//   1. `VisionGroupResolver.candidates()` (src/ios/Providers/VisionGroupResolver.swift
//      ~120-137) looks the member up by id in the store on every call and reads
//      `entry.model.capabilities` — no cached array, no captured list.
//   2. `ProviderConfigStore.replaceEntries` (~1611-1690) rebuilds each entry's
//      `baseModel` from the freshly fetched LLMModel while REUSING the prior
//      `uuid`, so the new capability lands under the id the group already holds
//      — membership survives a refresh without the user re-adding anything.
//   3. `ModelEntry.model` layers user `overrides` over that fresh base, so a
//      user's explicit choice still wins in both directions.
//
// This is the iOS twin of Android's VisionGroupResolverTest. The candidate
// filter, replaceEntries' entry rebuild and the capability resolution are ported
// verbatim (the real ones are @MainActor and read ProviderConfigStore.shared);
// section [6] re-reads the shipping sources so the copies cannot drift.
//
// Standalone (`swift VisionModalityFreshnessTests.swift`) like its neighbours.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported types

struct Modality: OptionSet, Equatable {
    let rawValue: Int
    static let textInput  = Modality(rawValue: 1 << 0)
    static let imageInput = Modality(rawValue: 1 << 1)
    static let pdfInput   = Modality(rawValue: 1 << 2)
    static let textOutput = Modality(rawValue: 1 << 3)
    static let textOnly: Modality = [.textInput, .textOutput]
    static let vision: Modality = [.textInput, .imageInput, .pdfInput, .textOutput]
}

struct LLMModel {
    var id: String
    var displayName: String
    var provider: String
    var modalityOverride: Modality?
    /// Mirrors LLMModel.capabilities: an explicit modality wins, else the
    /// provider table, else textOnly.
    var capabilities: Modality {
        if let o = modalityOverride { return o }
        return ["Anthropic": .vision, "Google": .vision, "OpenAI": .vision][provider] ?? .textOnly
    }
}

struct Overrides: Equatable { var modalityOverride: Modality? = nil; var displayName: String? = nil
    var isEmpty: Bool { modalityOverride == nil && displayName == nil } }

struct ModelEntry {
    var uuid: String
    var providerInstanceId: String
    var baseModel: LLMModel
    var overrides = Overrides()
    var isCustom = false
    var isHidden = false

    /// The composite id the group's memberEntryIds hold.
    var id: String { uuid }
    /// Verbatim from ModelEntry.model — overrides layered over the fresh base.
    var model: LLMModel {
        guard !overrides.isEmpty else { return baseModel }
        var m = baseModel
        m.modalityOverride = overrides.modalityOverride ?? baseModel.modalityOverride
        m.displayName = overrides.displayName ?? baseModel.displayName
        return m
    }
    var hasImageInput: Bool { model.capabilities.contains(.imageInput) }
}

struct Instance { var id: String; var isEnabled = true }
enum Strategy { case fallback, loadBalance }
struct Group { var id: String; var memberEntryIds: [String]; var strategy: Strategy = .fallback }

final class Store {
    var entries: [ModelEntry] = []
    var instances: [Instance] = []
    var groups: [Group] = []
    var visionGroupId: String?

    func entry(for id: String) -> ModelEntry? { entries.first { $0.id == id } }
    func instance(for id: String) -> Instance? { instances.first { $0.id == id } }
    func group(for id: String) -> Group? { groups.first { $0.id == id } }

    /// Verbatim from VisionGroupResolver.candidates(seed:).
    func candidates(seed: Int = 0) -> [ModelEntry] {
        guard let gid = visionGroupId, let group = group(for: gid) else { return [] }
        var members = group.memberEntryIds.compactMap { entryId -> ModelEntry? in
            guard let entry = entry(for: entryId),
                  !entry.isHidden,
                  entry.model.capabilities.contains(.imageInput),
                  let instance = instance(for: entry.providerInstanceId),
                  instance.isEnabled else { return nil }
            return entry
        }
        if group.strategy == .loadBalance, members.count > 1 {
            let offset = abs(seed) % members.count
            members = Array(members[offset...] + members[..<offset])
        }
        return members
    }
    var isConfigured: Bool { !candidates().isEmpty }

    /// The part of ProviderConfigStore.replaceEntries that matters here: the
    /// entry is REBUILT from the freshly fetched model, reusing the prior uuid,
    /// overrides, isHidden and userModifiedAt.
    func replaceEntries(for instanceId: String, models: [LLMModel]) {
        let existing = entries.filter { $0.providerInstanceId == instanceId }
        var existingByModelId: [String: ModelEntry] = [:]
        for e in existing {
            if let prev = existingByModelId[e.baseModel.id], e.isCustom && !prev.isCustom { continue }
            existingByModelId[e.baseModel.id] = e
        }
        entries.removeAll { $0.providerInstanceId == instanceId }
        let refreshedIds = Set(models.map(\.id))
        entries.append(contentsOf: models.map { model in
            let prior = existingByModelId[model.id]
            return ModelEntry(uuid: prior?.uuid ?? UUID().uuidString,
                              providerInstanceId: instanceId,
                              baseModel: model,
                              overrides: prior?.overrides ?? Overrides(),
                              isCustom: false,
                              isHidden: prior?.isHidden ?? false)
        })
        // Custom entries the refreshed list did not cover are kept as-is.
        entries.append(contentsOf: existing.filter { $0.isCustom && !refreshedIds.contains($0.baseModel.id) })
    }
}

// MARK: - Fixture

let instanceId = "inst-deepseek"
func makeStore(flashSeesImages: Bool) -> Store {
    let s = Store()
    s.instances = [Instance(id: instanceId)]
    // The reported model, catalogued as TEXT-ONLY when it was added.
    let flash = LLMModel(id: "deepseek-v4.1-flash", displayName: "DeepSeek V4.1 Flash",
                         provider: "DeepSeek",
                         modalityOverride: flashSeesImages ? .vision : .textOnly)
    let claude = LLMModel(id: "claude-opus-5", displayName: "Claude Opus 5",
                          provider: "Anthropic", modalityOverride: nil)
    s.entries = [
        ModelEntry(uuid: "u-flash", providerInstanceId: instanceId, baseModel: flash),
        ModelEntry(uuid: "u-claude", providerInstanceId: instanceId, baseModel: claude),
    ]
    s.groups = [Group(id: "g-vision", memberEntryIds: ["u-flash", "u-claude"])]
    s.visionGroupId = "g-vision"
    return s
}

// ---------------------------------------------------------------------------

print("\n[1] The reported case: a refresh that ADDS vision flips the resolver")

let store = makeStore(flashSeesImages: false)
check("before the refresh, Flash is text-only", store.entry(for: "u-flash")!.hasImageInput, false)
checkEq("…so it is not a Vision-Group candidate",
        store.candidates().map(\.uuid), ["u-claude"])

// The vendor catalogues the new capability; the user taps Refresh. No group edit,
// no re-adding the model.
store.replaceEntries(for: instanceId, models: [
    LLMModel(id: "deepseek-v4.1-flash", displayName: "DeepSeek V4.1 Flash",
             provider: "DeepSeek", modalityOverride: .vision),
    LLMModel(id: "claude-opus-5", displayName: "Claude Opus 5", provider: "Anthropic"),
])
check("after the refresh, Flash reports image input", store.entry(for: "u-flash")!.hasImageInput)
checkEq("…and is now a candidate, in the group's own order",
        store.candidates().map(\.uuid), ["u-flash", "u-claude"])
check("the user never touched the group", store.group(for: "g-vision")!.memberEntryIds == ["u-flash", "u-claude"])

print("\n[2] Membership survives because the uuid is REUSED, not regenerated")

// This is what makes (1) possible at all. A refresh that minted a new uuid would
// leave the group pointing at a deleted entry — the capability would be fresh
// and the member would be gone, which is the same user-visible symptom.
checkEq("the entry keeps its uuid across a refresh", store.entry(for: "u-flash")?.uuid, "u-flash")
check("…so the group's member id still resolves", store.entry(for: "u-flash") != nil)
checkEq("the base model was genuinely replaced, not merged",
        store.entry(for: "u-flash")?.baseModel.modalityOverride, .vision)

// A model the provider DROPS does leave a dangling member, and candidates()
// skips it rather than trapping.
let dropped = makeStore(flashSeesImages: true)
dropped.replaceEntries(for: instanceId, models: [
    LLMModel(id: "claude-opus-5", displayName: "Claude Opus 5", provider: "Anthropic"),
])
check("a dropped model leaves its member id dangling", dropped.entry(for: "u-flash") == nil)
checkEq("…and candidates() skips it instead of failing",
        dropped.candidates().map(\.uuid), ["u-claude"])
check("the group still reads as configured thanks to the surviving member", dropped.isConfigured)

print("\n[3] A refresh that REMOVES vision flips it back")

// The reverse direction matters just as much: a model re-catalogued as text-only
// must stop being a describer, or read_image silently sends pixels nowhere useful.
let losing = makeStore(flashSeesImages: true)
checkEq("initially both members are candidates",
        losing.candidates().map(\.uuid), ["u-flash", "u-claude"])
losing.replaceEntries(for: instanceId, models: [
    LLMModel(id: "deepseek-v4.1-flash", displayName: "DeepSeek V4.1 Flash",
             provider: "DeepSeek", modalityOverride: .textOnly),
    LLMModel(id: "claude-opus-5", displayName: "Claude Opus 5", provider: "Anthropic"),
])
checkEq("after the refresh only the vision model remains",
        losing.candidates().map(\.uuid), ["u-claude"])
// And when the LAST vision-capable member loses it, the group reads unconfigured.
losing.replaceEntries(for: instanceId, models: [
    LLMModel(id: "deepseek-v4.1-flash", displayName: "DeepSeek V4.1 Flash",
             provider: "DeepSeek", modalityOverride: .textOnly),
    LLMModel(id: "claude-opus-5", displayName: "Claude Opus 5",
             provider: "Anthropic", modalityOverride: .textOnly),
])
check("a group with no image-capable member reads as NOT configured", losing.isConfigured, false)
checkEq("…and offers no candidates", losing.candidates().count, 0)

print("\n[4] A user override still wins over the live catalog, in both directions")

// Freshness must not trample explicit user intent: overrides are carried across
// the refresh by replaceEntries and re-applied by ModelEntry.model.
let overridden = makeStore(flashSeesImages: false)
overridden.entries[0].overrides.modalityOverride = .vision
check("a user-forced vision override makes a text-only model a candidate",
      overridden.candidates().map(\.uuid).contains("u-flash"))
overridden.replaceEntries(for: instanceId, models: [
    LLMModel(id: "deepseek-v4.1-flash", displayName: "DeepSeek V4.1 Flash",
             provider: "DeepSeek", modalityOverride: .textOnly),
    LLMModel(id: "claude-opus-5", displayName: "Claude Opus 5", provider: "Anthropic"),
])
checkEq("the override survives the refresh",
        overridden.entry(for: "u-flash")?.overrides.modalityOverride, .vision)
check("…and still wins over the refreshed text-only base",
      overridden.entry(for: "u-flash")!.hasImageInput)
checkEq("…while the BASE still records what the catalog said",
        overridden.entry(for: "u-flash")?.baseModel.modalityOverride, .textOnly)

// The other direction: a user who turned image input OFF keeps it off even after
// the catalog starts claiming vision.
let forcedOff = makeStore(flashSeesImages: true)
forcedOff.entries[0].overrides.modalityOverride = .textOnly
check("a user-forced text-only override excludes an otherwise vision model",
      forcedOff.candidates().map(\.uuid).contains("u-flash"), false)
forcedOff.replaceEntries(for: instanceId, models: [
    LLMModel(id: "deepseek-v4.1-flash", displayName: "DeepSeek V4.1 Flash",
             provider: "DeepSeek", modalityOverride: .vision),
    LLMModel(id: "claude-opus-5", displayName: "Claude Opus 5", provider: "Anthropic"),
])
check("…and the refresh does not undo the user's choice",
      forcedOff.entry(for: "u-flash")!.hasImageInput, false)

print("\n[5] The other filter conditions are also read live, not snapshotted")

// Disabling the instance, or hiding the entry, must take effect on the very next
// candidates() call — there is no cached member array to invalidate.
let live = makeStore(flashSeesImages: true)
checkEq("both members are candidates", live.candidates().count, 2)
live.instances[0].isEnabled = false
checkEq("disabling the instance empties the candidate list immediately",
        live.candidates().count, 0)
check("…so the group reads as not configured", live.isConfigured, false)
live.instances[0].isEnabled = true
checkEq("re-enabling restores it with no refresh", live.candidates().count, 2)
live.entries[0].isHidden = true
checkEq("hiding an entry removes just that member",
        live.candidates().map(\.uuid), ["u-claude"])
live.entries[0].isHidden = false
// Hidden survives a refresh too (prior?.isHidden is carried), so a user who hid
// a model does not get it back as a describer on the next refresh.
live.entries[0].isHidden = true
live.replaceEntries(for: instanceId, models: [
    LLMModel(id: "deepseek-v4.1-flash", displayName: "F", provider: "DeepSeek", modalityOverride: .vision),
    LLMModel(id: "claude-opus-5", displayName: "C", provider: "Anthropic"),
])
check("isHidden survives the refresh", live.entry(for: "u-flash")?.isHidden == true)
checkEq("…so the hidden member stays out", live.candidates().map(\.uuid), ["u-claude"])

// Load-balance rotation reads the same live list.
let lb = makeStore(flashSeesImages: true)
lb.groups[0].strategy = .loadBalance
checkEq("seed 0 keeps author order", lb.candidates(seed: 0).map(\.uuid), ["u-flash", "u-claude"])
checkEq("seed 1 rotates", lb.candidates(seed: 1).map(\.uuid), ["u-claude", "u-flash"])
lb.entries[0].overrides.modalityOverride = .textOnly
checkEq("a member that drops out changes the rotation base, not just the order",
        lb.candidates(seed: 1).map(\.uuid), ["u-claude"])
// No group pointer → nothing, regardless of what the catalog says.
let unbound = makeStore(flashSeesImages: true)
unbound.visionGroupId = nil
checkEq("no vision group pointer → no candidates", unbound.candidates().count, 0)
check("…and not configured", unbound.isConfigured, false)

print("\n[6] Source-grep drift guard")

func source(_ rel: String) -> String {
    var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    while !FileManager.default.fileExists(atPath: root.appendingPathComponent(rel).path),
          root.pathComponents.count > 1 { root.deleteLastPathComponent() }
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let resolver = source("src/ios/Providers/VisionGroupResolver.swift")
let storeSrc = source("src/ios/Providers/ProviderConfigStore.swift")
let entrySrc = source("src/ios/Providers/ModelEntry.swift")
check("VisionGroupResolver read", !resolver.isEmpty)
check("ProviderConfigStore read", !storeSrc.isEmpty)
check("ModelEntry read", !entrySrc.isEmpty)

// 1 — candidates() resolves live, per call.
check("candidates() looks each member up in the store on every call",
      resolver.contains("guard let entry = store.entry(for: entryId),"))
check("…and reads the EFFECTIVE model's modalities, not baseModel's",
      resolver.contains("entry.model.capabilities.supportedModalities.contains(.imageInput)"))
check("…never entry.baseModel.capabilities",
      resolver.contains("entry.baseModel.capabilities"), false)
check("the store is read fresh inside candidates(), not captured",
      resolver.contains("let store = ProviderConfigStore.shared"))
check("there is no memoized candidate array",
      resolver.contains("cachedCandidates") || resolver.contains("private static var candidatesCache"), false)
check("isConfigured is derived from candidates() every read",
      resolver.contains("let configured = !candidates().isEmpty"))
// The one thing that IS cached is the cross-actor mirror of isConfigured, and it
// is refreshed from the live value — worst case one turn stale, and only for
// placeholder wording.
check("the only cached value is the isConfigured mirror, set from the live read",
      resolver.contains("ConfiguredMirror.shared.set(configured)"))
check("…and it is refreshable from a MainActor context",
      resolver.contains("static func refreshConfiguredMirror()"))
// Credentials are deliberately NOT part of the capability question.
// (The doc comment above candidates() explains at length why it must NOT, so
// grep the function BODY rather than the file.)
check("candidates()' body still does not probe the Keychain", {
    guard let r = resolver.range(of: "static func candidates(seed: Int = 0) -> [ModelEntry] {") else { return true }
    let body = String(resolver[r.upperBound...]).prefix(900)
    return body.contains("hasAnyCredential")
}(), false)

// 2 — replaceEntries rebuilds the base model under the SAME uuid.
check("replaceEntries reuses the prior uuid", storeSrc.contains("uuid: prior?.uuid ?? UUID().uuidString"))
check("…rebuilds the model from the freshly fetched one",
      storeSrc.contains("var resolved = model.withInferredModality()")
        && storeSrc.contains("model: resolved,"))
check("…and carries the user's overrides forward",
      storeSrc.contains("overrides: prior?.overrides ?? ModelOverrides(),"))
check("…and isHidden", storeSrc.contains("isHidden: prior?.isHidden ?? false,"))
check("the lookup that pairs old and new is by baseModel.id",
      storeSrc.contains("existingByModelId[entry.baseModel.id] = entry"))

// 3 — ModelEntry.model layers overrides over the fresh base.
check("ModelEntry.model applies the modality override over baseModel",
      entrySrc.contains("modalityOverride: overrides.modalityOverride ?? baseModel.modalityOverride"))
check("…and short-circuits to baseModel when there are no overrides",
      entrySrc.contains("guard !overrides.isEmpty else { return baseModel }"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
