// Tests for [T-model-absence-grace] — a catalog model the provider stops
// listing must not be deleted on the spot, taking the user's overrides with it.
//
// The report: `gemini-3.8-flash-high` disappeared entirely from the CPA-Mini2
// provider, and separately a context-window override on that same model
// "reverted" after a refresh. Those were the same event seen from two sides.
//
// `ProviderConfigStore.replaceEntries` rebuilds an instance's entries from the
// API response. For a model STILL in the list it already does the right thing —
// it looks `prior` up by model id and carries uuid / overrides / isHidden /
// userModifiedAt forward, replacing only the base. The bug was the other branch:
// a catalog entry (`isCustom == false`) missing from the response was deleted
// immediately. `isCustom` entries were exempt; provider-supplied ones — which is
// where user overrides actually live — were not.
//
// Neither existing guard covers it:
//   * fetchModelsWithFallback rescues only an EMPTY list or an unreachable
//     endpoint. A relay returning a valid list that merely omits one model is
//     neither.
//   * suspiciousShrink needs the list to roughly halve (`after*2 < before`), so
//     losing 1 of 12 never trips it — and it protects only group references,
//     never the entry, which `removeAll` has already dropped.
//
// Standalone (`swift ModelAbsenceGraceTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func ck(_ l: String, _ ok: Bool) {
    if ok { print("  ✅ \(l)") } else { print("  ❌ \(l)"); failures += 1 }
}
func ckEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Model of the entry + the refresh rule

let gracePeriod: TimeInterval = 7 * 24 * 60 * 60

struct Entry: Equatable {
    var modelId: String
    var contextWindowOverride: Int?      // stands in for ModelOverrides
    var isCustom: Bool = false
    var absentSince: Date? = nil
    var baseContextWindow: Int = 1_048_576

    var isUnavailableFromProvider: Bool { absentSince != nil }
    /// effective = override ?? base, the rule ModelEntry.model applies
    var effectiveContextWindow: Int { contextWindowOverride ?? baseContextWindow }
}

/// Mirrors the FIXED replaceEntries.
func replaceEntries(existing: [Entry], returned: [String], now: Date,
                    newBase: Int = 1_048_576) -> [Entry] {
    let returnedSet = Set(returned)
    var out: [Entry] = []
    // Models present in the response: rebuild, carrying prior state forward.
    for id in returned {
        let prior = existing.first { $0.modelId == id }
        out.append(Entry(modelId: id,
                         contextWindowOverride: prior?.contextWindowOverride,
                         isCustom: false,
                         absentSince: nil,                 // listed again ⇒ clear
                         baseContextWindow: newBase))
    }
    // Catalog models NOT in the response: keep within grace, else drop.
    for e in existing where !e.isCustom && !returnedSet.contains(e.modelId) {
        let since = e.absentSince ?? now
        if now.timeIntervalSince(since) > gracePeriod { continue }   // expired → drop
        var kept = e
        kept.absentSince = since
        out.append(kept)
    }
    // Custom entries are kept unconditionally (pre-existing behaviour).
    for e in existing where e.isCustom && !returnedSet.contains(e.modelId) {
        out.append(e)
    }
    return out
}

/// The OLD rule, kept so every behavioural claim below is falsifiable.
func replaceEntriesOLD(existing: [Entry], returned: [String], newBase: Int = 1_048_576) -> [Entry] {
    let returnedSet = Set(returned)
    var out: [Entry] = []
    for id in returned {
        let prior = existing.first { $0.modelId == id }
        out.append(Entry(modelId: id, contextWindowOverride: prior?.contextWindowOverride,
                         baseContextWindow: newBase))
    }
    for e in existing where e.isCustom && !returnedSet.contains(e.modelId) { out.append(e) }
    return out   // catalog entries not returned are simply gone
}

let t0 = Date(timeIntervalSince1970: 1_800_000_000)
func find(_ list: [Entry], _ id: String) -> Entry? { list.first { $0.modelId == id } }

// MARK: - 1. The reported case

print("\n▶️  a catalog model the provider skips once is NOT deleted")
let target = "gemini-3.8-flash-high"
let before = [
    Entry(modelId: target, contextWindowOverride: 400_000),
    Entry(modelId: "gemini-3.8-flash"),
    Entry(modelId: "gpt-4o"),
]
// The relay returns a perfectly valid list that merely omits `target`.
let after1 = replaceEntries(existing: before, returned: ["gemini-3.8-flash", "gpt-4o"], now: t0)
ck("the entry survives the refresh", find(after1, target) != nil)
ck("it is marked unavailable", find(after1, target)?.isUnavailableFromProvider == true)
ckEq("and its override is intact", find(after1, target)?.contextWindowOverride, 400_000)

print("\n▶️  the OLD rule fails both (so the test cannot pass vacuously)")
let old1 = replaceEntriesOLD(existing: before, returned: ["gemini-3.8-flash", "gpt-4o"])
ck("OLD: the entry is gone", find(old1, target) == nil)
ck("OLD: the override went with it", find(old1, target)?.contextWindowOverride == nil)

// MARK: - 2. Coming back

print("\n▶️  when the provider lists it again, it is fully restored")
let after2 = replaceEntries(existing: after1, returned: [target, "gemini-3.8-flash", "gpt-4o"],
                            now: t0.addingTimeInterval(3600))
ck("absence mark cleared", find(after2, target)?.absentSince == nil)
ck("no longer unavailable", find(after2, target)?.isUnavailableFromProvider == false)
ckEq("override still 400000", find(after2, target)?.contextWindowOverride, 400_000)
ckEq("effective value is the override, not the vendor's", find(after2, target)?.effectiveContextWindow, 400_000)

print("\n▶️  the base IS refreshed for models that are listed")
let bumped = replaceEntries(existing: before, returned: [target, "gpt-4o"], now: t0, newBase: 2_000_000)
ckEq("base picked up the vendor's new value", find(bumped, target)?.baseContextWindow, 2_000_000)
ckEq("but the user's override still wins", find(bumped, target)?.effectiveContextWindow, 400_000)
// A model with no override follows the vendor, which is the point of not
// freezing entries wholesale.
ckEq("an un-overridden model follows the vendor", find(bumped, "gpt-4o")?.effectiveContextWindow, 2_000_000)

// MARK: - 3. The clock starts once and does not drift

print("\n▶️  repeated absences keep the FIRST timestamp")
var rolling = before
var t = t0
for i in 0..<5 {
    t = t0.addingTimeInterval(Double(i) * 6 * 3600)          // a refresh every 6h
    rolling = replaceEntries(existing: rolling, returned: ["gemini-3.8-flash", "gpt-4o"], now: t)
}
ckEq("absentSince is still the first miss", find(rolling, target)?.absentSince, t0)
ck("still present after 5 consecutive misses", find(rolling, target) != nil)
ckEq("override untouched throughout", find(rolling, target)?.contextWindowOverride, 400_000)

// MARK: - 4. A genuinely removed model is eventually dropped

print("\n▶️  absence beyond the grace window finally deletes the entry")
let justInside = replaceEntries(existing: rolling, returned: ["gemini-3.8-flash", "gpt-4o"],
                                now: t0.addingTimeInterval(gracePeriod - 60))
ck("still kept 1 minute before the deadline", find(justInside, target) != nil)
let past = replaceEntries(existing: rolling, returned: ["gemini-3.8-flash", "gpt-4o"],
                          now: t0.addingTimeInterval(gracePeriod + 60))
ck("dropped 1 minute after the deadline", find(past, target) == nil)

print("\n▶️  the grace window spans many automatic refreshes")
// modelsRefreshWindow is 6h, so the window must cover far more than one.
ck("7d grace ≫ 6h refresh window", gracePeriod / (6 * 3600) >= 20)

// MARK: - 5. No collateral change

print("\n▶️  everything else behaves as before")
let normal = replaceEntries(existing: before, returned: [target, "gemini-3.8-flash", "gpt-4o"], now: t0)
ckEq("a full response keeps every entry", normal.count, 3)
ck("and marks none of them absent", normal.allSatisfy { !$0.isUnavailableFromProvider })

let withCustom = [
    Entry(modelId: "my-custom", contextWindowOverride: 123, isCustom: true),
    Entry(modelId: "gpt-4o"),
]
let customKept = replaceEntries(existing: withCustom, returned: ["gpt-4o"], now: t0)
ck("custom entries are still kept unconditionally", find(customKept, "my-custom") != nil)
ck("and are NOT marked absent (they never were listed)",
   find(customKept, "my-custom")?.isUnavailableFromProvider == false)

// MARK: - 6. Source invariants

print("\n▶️  source invariants")
func read(_ p: String) -> String? { try? String(contentsOfFile: p, encoding: .utf8) }
guard let store = read("../../Providers/ProviderConfigStore.swift"),
      let entryS = read("../../Providers/ModelEntry.swift"),
      let picker = read("../../Views/Providers/UnifiedModelPicker.swift") else {
    print("  ❌ could not read sources"); failures += 1; exit(1)
}

ck("the field exists", entryS.contains("var absentSince: Date?"))
ck("and a readable accessor", entryS.contains("var isUnavailableFromProvider: Bool { absentSince != nil }"))
// Absence is a local observation; it must not make an untouched entry sync.
let umRange = entryS.range(of: "var isUserModified: Bool {").map {
    String(entryS[$0.lowerBound...].prefix(160))
} ?? ""
ck("absence is NOT part of isUserModified", !umRange.contains("absentSince"))
// Equality must see it, or the DB row-dirty check skips persisting the change.
ck("equality includes it", entryS.contains("lhs.absentSince == rhs.absentSince"))

ck("the grace constant exists", store.contains("static let modelAbsenceGracePeriod"))
ck("unlisted catalog entries are kept, not dropped",
   store.contains("let absentCatalog = existing.filter { !$0.isCustom && !refreshedModelIds.contains($0.baseModel.id) }"))
ck("being listed again clears the mark", store.contains("absentSince: nil"))
ck("expiry is measured against the grace period",
   store.contains("now.timeIntervalSince(since) > Self.modelAbsenceGracePeriod"))

// Routing must skip an unavailable model while it is retained.
ck("the picker reports it as unavailable",
   picker.contains("if entry.isUnavailableFromProvider { return AppLocalized(\"Not listed by provider\") }"))

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All model-absence-grace tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
