// Tests for [T-modelsdev-suffix-alias] (M04) — a vendor-suffixed, namespaced or
// date-stamped model id must resolve to its catalog entry by LONGEST PREFIX,
// and must never resolve to a different model.
//
// Pins 73b7471cb 28c1b4094 4a5d1578c 631b00dcf. The resolution ladder lives in
// ModelReleaseIndex.resolve (src/ios/Providers/ModelReleaseIndex.swift ~95-170)
// and is four ordered steps: full id → tail (vendor/ and vendor. stripped) →
// tail minus a -20YYMMDD stamp → family walk, one token at a time. Two ordering
// facts are load-bearing and were each wrong once:
//
//   * Full id BEFORE tail. `aion-labs/aion-2.0` and `amazon/nova-lite-v1` exist
//     in the catalog ONLY under their namespaced id; going tail-first loses
//     them (measured 58% → 80% resolution on a real 868-model catalog).
//   * The family walk removes ONE token at a time and there is deliberately no
//     substring/fuzzy tier. A prototype that scanned for a shared family prefix
//     matched `us.anthropic.claude-opus-4-5-20251101-v1:0` onto `claude-opus-5`
//     — a different model, six months off. Unknown must stay unknown, because a
//     wrong date promotes a stale model to the top of the picker.
//
// `clean`, `stripVendorDotPrefix`, `stripDateSuffix` and `resolve` are `private`
// to the enum and the file pulls in the whole app graph, so they are ported
// verbatim here; section [7] re-reads the shipping source so the copies cannot
// drift. Standalone (`swift ModelReleaseAliasTests.swift`).

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported verbatim from ModelReleaseIndex (id normalization)

func clean(_ raw: String) -> String {
    var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if let i = s.firstIndex(of: ":") { s = String(s[s.startIndex..<i]) }
    if let i = s.firstIndex(of: "@") { s = String(s[s.startIndex..<i]) }
    for region in ["us.", "eu.", "apac.", "global."] where s.hasPrefix(region) {
        s = String(s.dropFirst(region.count))
        break
    }
    return s
}

func stripVendorDotPrefix(_ s: String) -> String {
    guard let dot = s.firstIndex(of: ".") else { return s }
    let head = s[s.startIndex..<dot]
    guard !head.isEmpty, head.allSatisfy({ $0.isLetter }) else { return s }
    return String(s[s.index(after: dot)...])
}

func stripDateSuffix(_ s: String) -> String {
    guard s.count > 9 else { return s }
    let tail = s.suffix(9)
    guard tail.first == "-" else { return s }
    let digits = tail.dropFirst()
    guard digits.count == 8, digits.allSatisfy(\.isNumber), digits.hasPrefix("20") else { return s }
    return String(s.dropLast(9))
}

// MARK: - Ported resolve() over a stand-in index

enum Tier: String { case exact, tail, prefix, miss }
struct Entry: Equatable { var id: String; var day: Int; var outputCost: Double?; var context: Int? }
struct Index { var byFullId: [String: Entry]; var byTail: [String: Entry] }
struct Hit: Equatable { var entry: Entry?; var tier: Tier }

func resolve(_ raw: String, _ index: Index) -> Hit {
    let cleaned = clean(raw)
    if let e = index.byFullId[cleaned] { return Hit(entry: e, tier: .exact) }
    var tail = cleaned.split(separator: "/").last.map(String.init) ?? cleaned
    tail = stripVendorDotPrefix(tail)
    if let e = index.byTail[tail] { return Hit(entry: e, tier: .tail) }
    let undated = stripDateSuffix(tail)
    if undated != tail, let e = index.byTail[undated] { return Hit(entry: e, tier: .tail) }
    var parts = undated.split(separator: "-").map(String.init)
    while parts.count > 1 {
        parts.removeLast()
        if let e = index.byTail[parts.joined(separator: "-")] { return Hit(entry: e, tier: .prefix) }
    }
    return Hit(entry: nil, tier: .miss)
}

/// Build an index exactly the way ModelsDevAPI.releaseIndex() does
/// (src/ios/Providers/ModelsDevAPI.swift ~618-649): every record is keyed by its
/// lowercased full id AND by its last `/` segment — NO vendor-dot strip on the
/// key side — and on a key collision the NEWEST day wins, because the same
/// model is republished by many providers with disagreeing dates.
func makeIndex(_ records: [(String, Int)]) -> Index {
    var full: [String: Entry] = [:], tail: [String: Entry] = [:]
    for (rawId, day) in records {
        let id = rawId.lowercased()
        let e = Entry(id: id, day: day, outputCost: nil, context: nil)
        if let existing = full[id], existing.day >= day {} else { full[id] = e }
        let t = id.split(separator: "/").last.map(String.init) ?? id
        if let existing = tail[t], existing.day >= day {} else { tail[t] = e }
    }
    return Index(byFullId: full, byTail: tail)
}

let index = makeIndex([
    ("gpt-5.6", 20_400),
    ("gpt-5.6-sol", 20_410),
    ("claude-opus-4-5", 20_300),
    ("claude-opus-5", 20_480),
    ("claude-sonnet-5", 20_470),
    ("kimi-k2.5", 20_350),
    ("aion-labs/aion-2.0", 20_100),
    ("amazon/nova-lite-v1", 20_050),
    ("qwen3-max", 20_200),
    ("deepseek-v4", 20_360),
])

// ---------------------------------------------------------------------------

print("\n[1] Tier 1 — the full namespaced id is tried FIRST")

// Entries such as these exist in the catalog only under their namespaced id,
// and the exact tier is what answers for them.
let aion = resolve("aion-labs/aion-2.0", index)
checkEq("aion-labs/aion-2.0 resolves", aion.entry?.id, "aion-labs/aion-2.0")
checkEq("…via the exact tier, not the tail", aion.tier, .exact)
let nova = resolve("amazon/nova-lite-v1", index)
checkEq("amazon/nova-lite-v1 resolves", nova.entry?.id, "amazon/nova-lite-v1")
checkEq("…via the exact tier", nova.tier, .exact)

// WHY the order matters, concretely. `byTail` is keyed on the last `/` segment
// only, and on a collision the NEWEST day wins — so one provider's republish
// can own a tail key that a different provider's namespaced entry also maps to.
// Asking for the namespaced id must answer with THAT entry, not the tail owner's.
let collided = makeIndex([
    ("vendor-a/shared-model", 20_000),   // the id being asked for
    ("vendor-b/shared-model", 20_500),   // newer, so it owns byTail["shared-model"]
])
checkEq("byTail is owned by the newer republish",
        collided.byTail["shared-model"]?.day, 20_500)
let exactHit = resolve("vendor-a/shared-model", collided)
checkEq("the exact id answers with its OWN entry", exactHit.entry?.day, 20_000)
checkEq("…via the exact tier", exactHit.tier, .exact)
// And an id that only exists as a tail still resolves through tier 2.
checkEq("a bare tail falls through to the tail tier",
        resolve("shared-model", collided).tier, .tail)
checkEq("…and gets the newest republish", resolve("shared-model", collided).entry?.day, 20_500)

print("\n[2] Tier 2 — vendor/ and vendor. prefixes are dropped")

for id in ["openrouter/gpt-5.6-sol", "someproxy/gpt-5.6-sol",
           "crossmodel/moonshot/gpt-5.6-sol", "anthropic.gpt-5.6-sol"] {
    let h = resolve(id, index)
    checkEq("\(id) → gpt-5.6-sol", h.entry?.id, "gpt-5.6-sol")
    checkEq("…via the tail tier", h.tier, .tail)
}
// A vendor prefix must not change WHICH entry is chosen.
checkEq("the namespaced form and the bare form agree on the date",
        resolve("openrouter/gpt-5.6-sol", index).entry?.day,
        resolve("gpt-5.6-sol", index).entry?.day)

print("\n[3] stripVendorDotPrefix must not eat a VERSION dot")

// The whole point of the `allSatisfy(isLetter)` guard: `gpt-5.6`'s dot is
// preceded by "gpt-5", which is not a bare word token.
checkEq("gpt-5.6 is untouched", stripVendorDotPrefix("gpt-5.6"), "gpt-5.6")
checkEq("gpt-4.1 is untouched", stripVendorDotPrefix("gpt-4.1"), "gpt-4.1")
checkEq("kimi-k2.5 is untouched", stripVendorDotPrefix("kimi-k2.5"), "kimi-k2.5")
checkEq("anthropic.claude-x loses the namespace",
        stripVendorDotPrefix("anthropic.claude-x"), "claude-x")
checkEq("a leading dot is left alone (empty head)", stripVendorDotPrefix(".weird"), ".weird")
checkEq("digits in the head disqualify the strip",
        stripVendorDotPrefix("v2.claude-x"), "v2.claude-x")
// TWO dots: only the FIRST segment is a candidate, and only one is removed.
checkEq("two dots — one namespace segment removed, the rest kept",
        stripVendorDotPrefix("anthropic.claude-3.5-sonnet"), "claude-3.5-sonnet")
checkEq("two dots where the second head is not a word: stops after one",
        stripVendorDotPrefix("vendor.model-1.5"), "model-1.5")
// An id whose version dot comes FIRST must survive intact.
checkEq("gpt-5.6.1 keeps both dots", stripVendorDotPrefix("gpt-5.6.1"), "gpt-5.6.1")
// The full-id tier means a real two-dot catalog id still resolves exactly.
checkEq("kimi-k2.5 resolves (its dot is not stripped)",
        resolve("moonshot/kimi-k2.5", index).entry?.id, "kimi-k2.5")

print("\n[4] Tier 3 — a trailing -20YYMMDD snapshot stamp")

checkEq("-20260115 is stripped", stripDateSuffix("claude-opus-4-5-20260115"), "claude-opus-4-5")
checkEq("-20251101 is stripped", stripDateSuffix("claude-opus-4-5-20251101"), "claude-opus-4-5")
// Guard rails around the stamp shape — each of these must NOT be stripped.
checkEq("a 7-digit tail is not a stamp", stripDateSuffix("model-2026011"), "model-2026011")
checkEq("a 9-digit tail is not a stamp", stripDateSuffix("model-202601155"), "model-202601155")
checkEq("a non-20xx year is not a stamp", stripDateSuffix("model-19991231"), "model-19991231")
checkEq("letters in the tail are not a stamp", stripDateSuffix("model-2026011a"), "model-2026011a")
checkEq("no hyphen before the digits", stripDateSuffix("model20260115"), "model20260115")
checkEq("an id that IS only a stamp is too short to strip",
        stripDateSuffix("20260115"), "20260115")
checkEq("exactly 9 chars is refused (count > 9 guard)",
        stripDateSuffix("-20260115"), "-20260115")
// End to end, including the Bedrock form with region + vendor dot + :version.
let dated = resolve("claude-opus-4-5-20260115", index)
checkEq("dated id resolves to the undated entry", dated.entry?.id, "claude-opus-4-5")
checkEq("…reported as a tail match", dated.tier, .tail)
let bedrock = resolve("us.anthropic.claude-opus-4-5-20251101-v1:0", index)
// `:0` is cut by clean(), `us.` by clean(), `anthropic.` by the vendor strip,
// leaving `claude-opus-4-5-20251101-v1`; the stamp is not at the end, so the
// family walk drops `-v1` and then hits the undated family id.
checkEq("the Bedrock form resolves to claude-opus-4-5", bedrock.entry?.id, "claude-opus-4-5")
check("…and NOT to claude-opus-5 (the wrong-model prototype bug)",
      bedrock.entry?.id != "claude-opus-5")

print("\n[5] Tier 4 — longest prefix wins, one token at a time")

// gpt-5.6-sol has its own entry, so the walk is never reached.
checkEq("an id with its own entry does not fall back to the family",
        resolve("gpt-5.6-sol", index).tier, .exact)
// An unknown sibling falls back to the nearest ANCESTOR, not to a cousin.
let unknownSibling = resolve("gpt-5.6-nova", index)
checkEq("gpt-5.6-nova → gpt-5.6 (one token dropped)", unknownSibling.entry?.id, "gpt-5.6")
checkEq("…reported as a prefix match", unknownSibling.tier, .prefix)
check("…and NOT gpt-5.6-sol (a cousin, not an ancestor)",
      unknownSibling.entry?.id != "gpt-5.6-sol")
// Longest prefix: with both gpt-5.6-sol and gpt-5.6 present, a 3-token id must
// stop at the LONGER one when it exists.
let longer = makeIndex([("gpt-5.6", 1), ("gpt-5.6-sol", 2)])
checkEq("gpt-5.6-sol-preview stops at gpt-5.6-sol, not gpt-5.6",
        resolve("gpt-5.6-sol-preview", longer).entry?.id, "gpt-5.6-sol")
// And the walk stops before emptying the id: a single token with no entry misses.
checkEq("a single unknown token misses rather than matching anything",
        resolve("mysterymodel", index).tier, .miss)
checkEq("…with no entry", resolve("mysterymodel", index).entry, nil)

print("\n[6] No fuzzy tier — a near-miss must stay a miss")

// Each of these shares a prefix fragment with a real entry but is NOT a
// descendant of it. Resolving any of them to that entry would put a stale
// model at the top of the picker with a confidently wrong date.
let nearMisses = [
    "claude-opus-6",        // newer generation, no entry
    "gpt-6",                // no gpt entry at all in this index
    "deepseek-v5",          // v4 exists; v5 is a different model
    "qwen4-max",            // qwen3-max exists
    "nova-lite-v2",         // amazon/nova-lite-v1 exists, only namespaced
]
for id in nearMisses {
    let h = resolve(id, index)
    checkEq("\(id) stays unresolved", h.tier, .miss)
}
// Concretely: claude-opus-6 must not borrow claude-opus-5's date.
check("claude-opus-6 does not inherit claude-opus-5's date",
      resolve("claude-opus-6", index).entry?.day == nil)
// A miss is ranked last but NOT hidden — custom/local/relay models live here.
check("a miss yields a nil entry, which the Rank comparator sinks (never drops)",
      resolve("my-local-llama", index).entry == nil)

print("\n[7] clean(): the decorations that are cut before any lookup")

checkEq("`:free` suffix", clean("kimi-k2-6:free"), "kimi-k2-6")
checkEq("`@default` suffix", clean("claude-sonnet-5@default"), "claude-sonnet-5")
checkEq("uppercase is lowered", clean("GPT-5.6-Sol"), "gpt-5.6-sol")
checkEq("surrounding whitespace", clean("  gpt-5.6  "), "gpt-5.6")
for region in ["us.", "eu.", "apac.", "global."] {
    checkEq("\(region) Bedrock region prefix", clean(region + "anthropic.x"), "anthropic.x")
}
checkEq("only ONE region prefix is removed",
        clean("us.us.anthropic.x"), "us.anthropic.x")
checkEq("a version dot is not mistaken for a region", clean("gpt-5.6"), "gpt-5.6")
// `:` is cut at the FIRST occurrence, which is what makes the Bedrock `:0` work.
checkEq("everything from the first colon is cut",
        clean("us.anthropic.claude-opus-4-5-20251101-v1:0"),
        "anthropic.claude-opus-4-5-20251101-v1")
// Case-insensitivity holds through the whole ladder.
checkEq("an upper-cased dated Bedrock id resolves like its lowercase twin",
        resolve("US.ANTHROPIC.CLAUDE-OPUS-4-5-20251101-V1:0", index).entry?.id,
        resolve("us.anthropic.claude-opus-4-5-20251101-v1:0", index).entry?.id)

print("\n[8] Source-grep drift guard (ModelReleaseIndex.swift)")

let rel = "src/ios/Providers/ModelReleaseIndex.swift"
var root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
while !FileManager.default.fileExists(atPath: root.appendingPathComponent(rel).path),
      root.pathComponents.count > 1 { root.deleteLastPathComponent() }
let src = (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
check("source read", !src.isEmpty)

check("tier 1 is the full id", src.contains("if let e = index.byFullId[cleaned] { return hit(e, .exact) }"))
check("tier 2 drops vendor/ then vendor.",
      src.contains("cleaned.split(separator: \"/\").last")
        && src.contains("tail = stripVendorDotPrefix(tail)"))
check("tier 3 strips the date stamp", src.contains("let undated = stripDateSuffix(tail)"))
check("tier 3 only re-looks-up when the strip changed something",
      src.contains("if undated != tail, let e = index.byTail[undated]"))
check("tier 4 removes ONE token at a time", src.contains("parts.removeLast()")
        && src.contains("while parts.count > 1"))
check("the walk never empties the id (count > 1, not >= 1)",
      src.contains("while parts.count > 1"))
check("the ladder order is exact → tail → undated → family walk", {
    guard let a = src.range(of: "index.byFullId[cleaned]"),
          let b = src.range(of: "tail = stripVendorDotPrefix(tail)"),
          let c = src.range(of: "let undated = stripDateSuffix(tail)"),
          let d = src.range(of: "while parts.count > 1") else { return false }
    return a.lowerBound < b.lowerBound && b.lowerBound < c.lowerBound && c.lowerBound < d.lowerBound
}())
check("the vendor-dot guard still requires an all-letter head",
      src.contains("guard !head.isEmpty, head.allSatisfy({ $0.isLetter }) else { return s }"))
check("the date guard still requires 8 digits starting 20",
      src.contains("guard digits.count == 8, digits.allSatisfy(\\.isNumber), digits.hasPrefix(\"20\")"))
check("no fuzzy/substring tier was added",
      src.contains("Deliberately NO fuzzy/substring fallback"))
check("a miss returns .miss with every field nil",
      src.contains("return Hit(date: nil, day: nil, outputCost: nil, context: nil, tier: .miss)"))
check("an undated model is sunk, not hidden, by the comparator",
      src.contains("case (nil, _?): return false"))
// The index-build side, which the harness above mirrors.
let apiRel = "src/ios/Providers/ModelsDevAPI.swift"
let api = (try? String(contentsOf: root.appendingPathComponent(apiRel), encoding: .utf8)) ?? ""
check("ModelsDevAPI source read", !api.isEmpty)
check("byFullId is keyed on the LOWERCASED full id", api.contains("let full = rawId.lowercased()"))
check("byTail is keyed on the last / segment, with no vendor-dot strip",
      api.contains("let tail = full.split(separator: \"/\").last.map(String.init) ?? full"))
check("both keys keep the NEWEST day on a collision",
      api.contains("if let existing = byFullId[full], existing.day >= entry.day {} else { byFullId[full] = entry }")
        && api.contains("if let existing = byTail[tail], existing.day >= entry.day {} else { byTail[tail] = entry }"))
check("records with no parseable release date are skipped, not defaulted",
      api.contains("guard let raw = model.releaseDate,")
        && api.contains("let parsed = ModelReleaseIndex.parseReleaseDate(raw) else { continue }"))
check("the index is memoized on cacheTimestamp so a refresh rebuilds it",
      api.contains("if let cached = cachedReleaseIndex, releaseIndexBuiltFrom == cacheTimestamp"))

check("clean() still cuts : and @ and the four Bedrock regions",
      src.contains("if let i = s.firstIndex(of: \":\")")
        && src.contains("if let i = s.firstIndex(of: \"@\")")
        && src.contains("for region in [\"us.\", \"eu.\", \"apac.\", \"global.\"]"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILED") }
exit(failures == 0 ? 0 : 1)
