// Tests for [T-group-resolve-usable-members] + [T-disabled-provider-via-group]
// (M07, GitHub #34) — a Model Group only ever routes to a member that is
// actually usable, and a DISABLED provider instance is never reachable through
// a group even while its entry is still listed as a member.
//
// Why it matters: disabling a provider in Settings was incomplete. If any of
// its models still sat inside a Model Group, the router happily picked it and
// the request went out against a provider the user believed they had switched
// off (Android twin: 244d17009; the iOS side also dims such members in the
// group editor instead of silently hiding them — 238e2abeb). The credential
// gate has its own history: an iCloud-synced OAuth instance arrives with its
// metadata but WITHOUT its per-device token, and such an instance used to win
// the fallback race and burn a fallback slot on an immediate 403 "OAuth
// authentication is currently not allowed for this organization".
//
// So four conditions filter a member — entry exists, entry not hidden, instance
// exists and is enabled, instance has a credential — and the same filtered list
// must drive resolve(), nextFallback() and the editor's "unavailable" reasons,
// or the router accepts a member the factory then bails on.
//
// availableEntryIds / resolve / nextFallback / unavailableMembers are ported
// verbatim from src/ios/Providers/ModelGroupRouter.swift (the real ones are
// @MainActor and take a ProviderConfigStore); section [7] re-reads the shipping
// source so the copies cannot drift.
//
// Standalone (`swift GroupMemberFilterTests.swift`) like its neighbours.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported store / router

struct Inst { var id: String; var label: String; var isEnabled = true; var hasAnyCredential = true }
struct Entry { var id: String; var displayName: String; var instanceId: String; var isHidden = false }
enum Strategy: String { case fallback, loadBalance }
struct Group { var name: String; var strategy: Strategy = .fallback; var memberEntryIds: [String] }

struct Store {
    var entries: [String: Entry] = [:]
    var instances: [String: Inst] = [:]
    func entry(for id: String) -> Entry? { entries[id] }
    func instance(for id: String) -> Inst? { instances[id] }
}

/// Verbatim from ModelGroupRouter.availableEntryIds.
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

/// Verbatim from ModelGroupRouter.resolve. `hashValue` is not reproducible
/// across processes, so the load-balance index is injected instead.
func resolve(group: Group, store: Store, hash: Int) -> String? {
    let available = availableEntryIds(group: group, store: store)
    guard !available.isEmpty else { return nil }
    switch group.strategy {
    case .fallback:    return available.first
    case .loadBalance: return available[abs(hash) % available.count]
    }
}

/// Verbatim from ModelGroupRouter.nextFallback.
func nextFallback(group: Group, currentEntryId: String, store: Store) -> String? {
    let available = availableEntryIds(group: group, store: store)
    guard let currentIdx = available.firstIndex(of: currentEntryId) else { return available.first }
    if let next = available[(currentIdx + 1)...].first { return next }
    if let wrapped = available[..<currentIdx].first { return wrapped }
    return nil
}

/// Verbatim from ModelGroupRouter.unavailableMembers (localization elided).
func unavailableMembers(group: Group, store: Store) -> [(model: String, instance: String, reason: String)] {
    var result: [(model: String, instance: String, reason: String)] = []
    for entryId in group.memberEntryIds {
        guard let entry = store.entry(for: entryId) else { continue }
        guard let inst = store.instance(for: entry.instanceId) else { continue }
        if entry.isHidden {
            result.append((entry.displayName, inst.label, "Hidden"))
        } else if !inst.isEnabled {
            result.append((entry.displayName, inst.label, "Disabled"))
        } else if !inst.hasAnyCredential {
            result.append((entry.displayName, inst.label, "Not logged in"))
        }
    }
    return result
}

// MARK: - Fixture: a 4-member fallback group, one member per failure mode

func fixture(disable: String? = nil,
             stripCredential: String? = nil,
             hide: String? = nil,
             danglingMember: Bool = false) -> (Group, Store) {
    var store = Store()
    for (i, name) in ["alpha", "bravo", "charlie", "delta"].enumerated() {
        store.instances["inst-\(name)"] = Inst(id: "inst-\(name)", label: "Instance \(name)",
                                               isEnabled: disable != name,
                                               hasAnyCredential: stripCredential != name)
        store.entries["e-\(name)"] = Entry(id: "e-\(name)", displayName: "Model \(i)",
                                           instanceId: "inst-\(name)", isHidden: hide == name)
    }
    var members = ["e-alpha", "e-bravo", "e-charlie", "e-delta"]
    if danglingMember { members.insert("e-ghost-uuid", at: 1) }
    return (Group(name: "Main", memberEntryIds: members), store)
}

// ---------------------------------------------------------------------------

print("\n[1] All four filter conditions remove a member")

let (allGood, goodStore) = fixture()
checkEq("nothing filtered when everything is usable",
        availableEntryIds(group: allGood, store: goodStore),
        ["e-alpha", "e-bravo", "e-charlie", "e-delta"])

let (gDisabled, sDisabled) = fixture(disable: "bravo")
checkEq("a DISABLED provider instance is removed",
        availableEntryIds(group: gDisabled, store: sDisabled),
        ["e-alpha", "e-charlie", "e-delta"])

let (gNoCred, sNoCred) = fixture(stripCredential: "charlie")
checkEq("an instance with no credential is removed",
        availableEntryIds(group: gNoCred, store: sNoCred),
        ["e-alpha", "e-bravo", "e-delta"])

let (gHidden, sHidden) = fixture(hide: "delta")
checkEq("a hidden ENTRY is removed",
        availableEntryIds(group: gHidden, store: sHidden),
        ["e-alpha", "e-bravo", "e-charlie"])

let (gGhost, sGhost) = fixture(danglingMember: true)
checkEq("a dangling member id is skipped, not fatal",
        availableEntryIds(group: gGhost, store: sGhost),
        ["e-alpha", "e-bravo", "e-charlie", "e-delta"])

// A member whose instance row is gone (deleted provider, stale sync).
var orphanStore = goodStore
orphanStore.instances.removeValue(forKey: "inst-bravo")
checkEq("a member whose instance no longer exists is skipped",
        availableEntryIds(group: allGood, store: orphanStore),
        ["e-alpha", "e-charlie", "e-delta"])

// Order is preserved — fallback strategy depends on it.
checkEq("the surviving members keep their declared order",
        availableEntryIds(group: gDisabled, store: sDisabled).first, "e-alpha")

print("\n[2] #34 — a disabled provider is NOT reachable through a group")

// The reported shape: the group's FIRST member belongs to the provider the user
// just disabled. Before the fix, fallback routing picked it and the request went
// out against a provider Settings said was off.
let (gFirstDisabled, sFirstDisabled) = fixture(disable: "alpha")
checkEq("resolve skips the disabled first member",
        resolve(group: gFirstDisabled, store: sFirstDisabled, hash: 0), "e-bravo")
check("…and never returns it under any load-balance hash", {
    for h in 0..<64 {
        var g = gFirstDisabled; g.strategy = .loadBalance
        if resolve(group: g, store: sFirstDisabled, hash: h) == "e-alpha" { return false }
    }
    return true
}())
check("…nor as a nextFallback target from any surviving member", {
    for from in ["e-bravo", "e-charlie", "e-delta"] {
        if nextFallback(group: gFirstDisabled, currentEntryId: from, store: sFirstDisabled) == "e-alpha" {
            return false
        }
    }
    return true
}())
// Even when the disabled member is the one currently selected: the router must
// move OFF it, not treat "current not in available" as a reason to keep it.
checkEq("a disabled current entry is not returned to itself",
        nextFallback(group: gFirstDisabled, currentEntryId: "e-alpha", store: sFirstDisabled),
        "e-bravo")
// The entry still EXISTS and is still a member — disabling must not mutate the
// group (238e2abeb dims it in the editor instead of deleting it).
check("the disabled member is still listed in the group",
      gFirstDisabled.memberEntryIds.contains("e-alpha"))
check("…and still resolvable in the store", sFirstDisabled.entry(for: "e-alpha") != nil)
// Re-enabling restores it with no group edit at all.
var reenabled = sFirstDisabled
reenabled.instances["inst-alpha"]?.isEnabled = true
checkEq("re-enabling the provider makes it routable again with no group edit",
        resolve(group: gFirstDisabled, store: reenabled, hash: 0), "e-alpha")

print("\n[3] Every member unusable → nil, not a lucky pick")

let (gAll, _) = fixture()
var deadStore = goodStore
for n in ["alpha", "bravo", "charlie", "delta"] { deadStore.instances["inst-\(n)"]?.isEnabled = false }
checkEq("a wholly disabled group resolves to nil", resolve(group: gAll, store: deadStore, hash: 3), nil)
checkEq("…and offers no fallback either",
        nextFallback(group: gAll, currentEntryId: "e-alpha", store: deadStore), nil)
// Mixed reasons, all four at once.
var mixedStore = goodStore
mixedStore.instances["inst-alpha"]?.isEnabled = false
mixedStore.instances["inst-bravo"]?.hasAnyCredential = false
mixedStore.entries["e-charlie"]?.isHidden = true
mixedStore.instances.removeValue(forKey: "inst-delta")
checkEq("one member per failure mode → nil", resolve(group: gAll, store: mixedStore, hash: 9), nil)
checkEq("an empty member list resolves to nil",
        resolve(group: Group(name: "Empty", memberEntryIds: []), store: goodStore, hash: 1), nil)

print("\n[4] nextFallback cycles the FILTERED list exactly once")

// With bravo disabled the cycle is alpha → charlie → delta → alpha.
let (gCycle, sCycle) = fixture(disable: "bravo")
checkEq("alpha → charlie (bravo skipped)",
        nextFallback(group: gCycle, currentEntryId: "e-alpha", store: sCycle), "e-charlie")
checkEq("charlie → delta", nextFallback(group: gCycle, currentEntryId: "e-charlie", store: sCycle), "e-delta")
checkEq("delta wraps to alpha", nextFallback(group: gCycle, currentEntryId: "e-delta", store: sCycle), "e-alpha")
// Walking the cycle must visit each available member once and only once.
var visited: [String] = []
var cur = "e-alpha"
for _ in 0..<3 {
    guard let n = nextFallback(group: gCycle, currentEntryId: cur, store: sCycle) else { break }
    visited.append(n); cur = n
}
checkEq("one full round visits each available member exactly once",
        visited, ["e-charlie", "e-delta", "e-alpha"])
// A single-member group has nowhere to go.
let single = Group(name: "Solo", memberEntryIds: ["e-alpha"])
checkEq("a single-member group has no next fallback",
        nextFallback(group: single, currentEntryId: "e-alpha", store: goodStore), nil)
// A current entry that was filtered out mid-turn restarts at the first
// available one rather than returning nil (the request must still go somewhere).
checkEq("a filtered-out current entry restarts at the first available",
        nextFallback(group: gCycle, currentEntryId: "e-bravo", store: sCycle), "e-alpha")

print("\n[5] Load balance divides over the FILTERED count, never the member count")

// The bug shape this guards: `% memberEntryIds.count` would index past the
// filtered array (crash) or systematically favour whichever member happened to
// sit at the surviving indices.
var lb = gDisabled; lb.strategy = .loadBalance
let available = availableEntryIds(group: lb, store: sDisabled)
checkEq("3 of 4 members are available", available.count, 3)
var hit = Set<String>()
var strayPick: String? = nil
for h in 0..<300 {
    guard let r = resolve(group: lb, store: sDisabled, hash: h) else { strayPick = "nil"; break }
    if !available.contains(r) { strayPick = r; break }
    hit.insert(r)
}
checkEq("no hash in 0..<300 selects a filtered member", strayPick, nil)
checkEq("all three available members are reachable", hit.count, 3)
check("the disabled member is never selected", hit.contains("e-bravo"), false)
// Int.min guard: abs(Int.min) traps, so the real code's `abs(hashValue)` relies
// on hashValue never being Int.min. Pinned as the boundary the harness shares.
checkEq("a large positive hash still lands in range",
        available.indices.contains(abs(Int.max) % available.count), true)

print("\n[6] unavailableMembers explains each exclusion, with one reason each")

let (gReasons, sReasons) = fixture()
var rStore = sReasons
rStore.instances["inst-alpha"]?.isEnabled = false
rStore.instances["inst-bravo"]?.hasAnyCredential = false
rStore.entries["e-charlie"]?.isHidden = true
let reasons = unavailableMembers(group: gReasons, store: rStore)
checkEq("three members are reported unavailable", reasons.count, 3)
checkEq("disabled instance → Disabled", reasons.first { $0.model == "Model 0" }?.reason, "Disabled")
checkEq("no credential → Not logged in", reasons.first { $0.model == "Model 1" }?.reason, "Not logged in")
checkEq("hidden entry → Hidden", reasons.first { $0.model == "Model 2" }?.reason, "Hidden")
check("the usable member is not listed", reasons.contains { $0.model == "Model 3" }, false)
checkEq("the instance label is carried so the user can find it",
        reasons.first { $0.model == "Model 0" }?.instance, "Instance alpha")
// Precedence: hidden is reported over disabled when both are true, so the user
// sees one reason, not a pile.
var bothStore = sReasons
bothStore.entries["e-alpha"]?.isHidden = true
bothStore.instances["inst-alpha"]?.isEnabled = false
let both = unavailableMembers(group: gReasons, store: bothStore)
checkEq("hidden takes precedence over disabled (one reason, not two)",
        both.filter { $0.model == "Model 0" }.map(\.reason), ["Hidden"])
// The two lists must partition the resolvable members exactly.
let unavailableCount = unavailableMembers(group: gReasons, store: rStore).count
checkEq("available + unavailable accounts for every resolvable member",
        availableEntryIds(group: gReasons, store: rStore).count + unavailableCount,
        gReasons.memberEntryIds.count)

print("\n[7] Source-grep drift guard (ModelGroupRouter.swift)")

let rel = "src/ios/Providers/ModelGroupRouter.swift"
var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
while !FileManager.default.fileExists(atPath: root.appendingPathComponent(rel).path),
      root.pathComponents.count > 1 { root.deleteLastPathComponent() }
let src = (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
check("source read", !src.isEmpty)

check("the filter checks the entry exists", src.contains("guard let entry = store.entry(for: entryId) else"))
check("…is not hidden", src.contains("guard !entry.isHidden else"))
check("…its instance exists", src.contains("guard let instance = store.instance(for: entry.providerInstanceId) else"))
check("…the instance is ENABLED", src.contains("guard instance.isEnabled else"))
check("…and has a credential", src.contains("guard instance.hasAnyCredential else"))
checkEq("exactly five guards in availableEntryIds (no condition dropped)", {
    guard let r = src.range(of: "private static func availableEntryIds") else { return -1 }
    let body = String(src[r.upperBound...]).prefix(1400)
    return body.components(separatedBy: "guard ").count - 1
}(), 5)
// All three consumers must share the one filter — the "router accepts X, factory
// bails on X" footgun the credential gate was added for.
check("resolve() uses availableEntryIds", src.contains("let available = availableEntryIds(group: group, store: store)"))
checkEq("both resolve() and nextFallback() call it",
        src.components(separatedBy: "availableEntryIds(group: group, store: store)").count - 1, 2)
check("resolve() returns nil when the filtered list is empty",
      src.contains("guard !available.isEmpty else"))
check("load balance divides by the FILTERED count",
      src.contains("abs(sessionId.hashValue) % available.count"))
check("…and indexes the filtered array", src.contains("return available[index]"))
check("nextFallback cycles the filtered list and wraps",
      src.contains("let remaining = available[(currentIdx + 1)...]")
        && src.contains("let before = available[..<currentIdx]"))
check("a current entry that is no longer available restarts at the first",
      src.contains("return available.first"))
// The editor's reasons must be derived from the same three predicates, in the
// same precedence order, so the UI cannot disagree with the router.
check("unavailableMembers reports Hidden / Disabled / Not logged in",
      src.contains("reason: AppLocalized(\"Hidden\")")
        && src.contains("reason: AppLocalized(\"Disabled\")")
        && src.contains("reason: AppLocalized(\"Not logged in\")"))
check("…as an if/else-if chain, so exactly one reason is reported", {
    guard let r = src.range(of: "static func unavailableMembers") else { return false }
    let body = String(src[r.upperBound...]).prefix(1200)
    return body.contains("if entry.isHidden {")
        && body.contains("} else if !inst.isEnabled {")
        && body.contains("} else if !inst.hasAnyCredential {")
}())
check("the group is never mutated by the filter (members are only skipped)",
      src.contains("group.memberEntryIds.remove"), false)

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
