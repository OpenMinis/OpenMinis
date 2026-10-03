// Tests for [T-model-release-ranking] — round-2 item M22, iOS half.
//
// A picker whose first entries are stale models is an active hazard, not just
// untidy: OpenMinis#83 was filed as "GPT-5.3 CodeX Spark cannot call tools", but
// the Codex backend simply refuses that model on a ChatGPT account and the
// refusal renders as an EMPTY assistant turn. Alphabetical order put a dead
// model at the top, so a new user read the app as broken. Ranking by release
// date puts the callable models first with no hand-maintained list.
//
// What this pins:
//   * the Rank ordering — newest first, then output price, then context window,
//     then display name; an UNDATED model sorts last but is never hidden
//     (custom / local / relay entries legitimately have no catalog row);
//   * the comparator is a TOTAL order with an id tiebreak, so `sorted` cannot
//     reshuffle equal elements between reads and make the list jitter;
//   * id resolution order — full id BEFORE tail (namespaced-only entries such
//     as `aion-labs/aion-2.0` are lost by a tail-first lookup), then a
//     `-20YYMMDD` strip, then a token-by-token family walk, and NO fuzzy
//     fallback (an earlier prototype matched a Bedrock opus-4-5 id onto
//     `claude-opus-5`, six months off);
//   * index dedup — the same bare id is republished by many vendors with
//     disagreeing dates, and the NEWEST wins, so a lagging relay cannot drag a
//     current model down the list;
//   * rank-once-per-sort. Android 710b43a64 fixed an ANR here: its comparator
//     ranked BOTH sides of every comparison, i.e. O(n log n) rank calls (~18,600
//     for a 939-entry device) on the main thread. Section 5 measures iOS's own
//     comparator the same way and records where it stands.
//
// Ports: ModelReleaseIndex.Rank / resolve / clean / stripVendorDotPrefix /
// stripDateSuffix / parseReleaseDate (ModelReleaseIndex.swift), the index build
// (ModelsDevAPI.swift ~L623-645), ProviderConfigStore.releaseRankOrder (~L1486).
//
// Standalone (`swift ModelReleaseRankingTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func gap(_ l: String, _ closed: Bool) {
    if closed { print("  ✅ \(l) (gap closed)") } else { print("  ⚠️ KNOWN GAP: \(l)") }
}
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Ports

struct ReleaseEntry { let day: Int; let outputCost: Double?; let context: Int? }
struct ReleaseIndex { var byFullId: [String: ReleaseEntry]; var byTail: [String: ReleaseEntry] }

enum MatchTier: String { case exact, tail, prefix, miss }

struct Rank: Comparable {
    let releaseDay: Int?
    let outputCostPerMTok: Double
    let contextWindow: Int
    let displayName: String

    static func < (lhs: Rank, rhs: Rank) -> Bool {
        switch (lhs.releaseDay, rhs.releaseDay) {
        case let (l?, r?) where l != r: return l > r
        case (nil, _?): return false
        case (_?, nil): return true
        default: break
        }
        if lhs.outputCostPerMTok != rhs.outputCostPerMTok { return lhs.outputCostPerMTok > rhs.outputCostPerMTok }
        if lhs.contextWindow != rhs.contextWindow { return lhs.contextWindow > rhs.contextWindow }
        return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
    }
}

func clean(_ raw: String) -> String {
    var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if let i = s.firstIndex(of: ":") { s = String(s[s.startIndex..<i]) }
    if let i = s.firstIndex(of: "@") { s = String(s[s.startIndex..<i]) }
    for region in ["us.", "eu.", "apac.", "global."] where s.hasPrefix(region) {
        s = String(s.dropFirst(region.count)); break
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

var resolveCalls = 0
func resolve(_ raw: String, in index: ReleaseIndex?) -> (entry: ReleaseEntry?, tier: MatchTier) {
    resolveCalls += 1
    guard let index else { return (nil, .miss) }
    let cleaned = clean(raw)
    if let e = index.byFullId[cleaned] { return (e, .exact) }
    var tail = cleaned.split(separator: "/").last.map(String.init) ?? cleaned
    tail = stripVendorDotPrefix(tail)
    if let e = index.byTail[tail] { return (e, .tail) }
    let undated = stripDateSuffix(tail)
    if undated != tail, let e = index.byTail[undated] { return (e, .tail) }
    var parts = undated.split(separator: "-").map(String.init)
    while parts.count > 1 {
        parts.removeLast()
        if let e = index.byTail[parts.joined(separator: "-")] { return (e, .prefix) }
    }
    return (nil, .miss)
}
func rank(modelId: String, displayName: String, contextWindow: Int?, index: ReleaseIndex?) -> Rank {
    let hit = resolve(modelId, in: index)
    return Rank(releaseDay: hit.entry?.day, outputCostPerMTok: hit.entry?.outputCost ?? 0,
                contextWindow: contextWindow ?? hit.entry?.context ?? 0, displayName: displayName)
}

/// ModelReleaseIndex.parseReleaseDate — accepts YYYY-MM-DD and YYYY-MM.
let gregorian = Calendar(identifier: .gregorian)
let epoch = DateComponents(calendar: gregorian, timeZone: TimeZone(secondsFromGMT: 0), year: 1970, month: 1, day: 1).date!
func parseReleaseDate(_ raw: String) -> (date: Date, day: Int)? {
    let parts = raw.split(separator: "-")
    guard parts.count == 2 || parts.count == 3,
          let year = Int(parts[0]), parts[0].count == 4,
          let month = Int(parts[1]), (1...12).contains(month) else { return nil }
    var day = 1
    if parts.count == 3 {
        guard let d = Int(parts[2]), (1...31).contains(d) else { return nil }
        day = d
    }
    var c = DateComponents(); c.year = year; c.month = month; c.day = day
    c.timeZone = TimeZone(secondsFromGMT: 0)
    guard let date = gregorian.date(from: c) else { return nil }
    return (date, Int(date.timeIntervalSince(epoch) / 86_400))
}

/// The index build, including the "newest wins" dedup across vendor prefixes.
func buildIndex(_ rows: [(id: String, releaseDate: String?, outputCost: Double?, context: Int?)]) -> ReleaseIndex {
    var byFullId: [String: ReleaseEntry] = [:]
    var byTail: [String: ReleaseEntry] = [:]
    for row in rows {
        guard let raw = row.releaseDate, let parsed = parseReleaseDate(raw) else { continue }
        let entry = ReleaseEntry(day: parsed.day, outputCost: row.outputCost, context: row.context)
        let full = row.id.lowercased()
        if let existing = byFullId[full], existing.day >= entry.day {} else { byFullId[full] = entry }
        let tail = full.split(separator: "/").last.map(String.init) ?? full
        if let existing = byTail[tail], existing.day >= entry.day {} else { byTail[tail] = entry }
    }
    return ReleaseIndex(byFullId: byFullId, byTail: byTail)
}

/// A ModelEntry, reduced to what the comparator reads.
struct Entry: Equatable { let id: String; let displayName: String; let contextWindow: Int? }

/// ProviderConfigStore.releaseRankOrder — ranks inside the comparator.
func releaseRankOrder(_ a: Entry, _ b: Entry, index: ReleaseIndex?) -> Bool {
    let ra = rank(modelId: a.id, displayName: a.displayName, contextWindow: a.contextWindow, index: index)
    let rb = rank(modelId: b.id, displayName: b.displayName, contextWindow: b.contextWindow, index: index)
    if ra < rb { return true }
    if rb < ra { return false }
    return a.id < b.id
}
/// Decorate-sort-undecorate, i.e. what Android 710b43a64 changed to.
func rankedOnce(_ entries: [Entry], index: ReleaseIndex?) -> [Entry] {
    entries.map { (e: $0, r: rank(modelId: $0.id, displayName: $0.displayName, contextWindow: $0.contextWindow, index: index)) }
        .sorted { a, b in
            if a.r < b.r { return true }
            if b.r < a.r { return false }
            return a.e.id < b.e.id
        }
        .map(\.e)
}

// A catalog shaped like the real one: the same bare id republished by several
// vendors with disagreeing dates, dated and undated rows, namespaced-only rows.
let catalog = buildIndex([
    ("gpt-5.6-sol", "2026-08-14", 30, 400_000),
    ("gpt-5.6", "2026-07-01", 20, 400_000),
    ("gpt-5.3-codex-spark", "2025-11-02", 4, 272_000),
    ("openai/gpt-5.6-sol", "2026-08-01", 31, 400_000),          // relay lags the vendor
    ("aion-labs/aion-2.0", "2026-03-10", 12, 131_072),          // namespaced-only
    ("amazon/nova-lite-v1", "2025-01-20", 0.6, 300_000),
    ("claude-opus-4-5", "2025-11-01", 75, 200_000),
    ("claude-sonnet-5", "2026-06-11", 15, 1_000_000),
    ("glm-5.2", "2026-02-01", 2, 200_000),
    ("zai/glm-5.2", "2026-05-20", 2.2, 200_000),                // newer republish
    ("kimi-k2-6", "2026-04-04", 5, 262_144),
    ("no-date-model", nil, 99, 8_000),
])

print("▶️  1. resolution order: full id, then tail, then date strip, then family walk")
do {
    checkEq("exact full id wins", resolve("gpt-5.6-sol", in: catalog).tier, .exact)
    // The measured regression: a namespaced-only entry is LOST by tail-first.
    checkEq("aion-labs/aion-2.0 resolves (full id, not tail)", resolve("aion-labs/aion-2.0", in: catalog).tier, .exact)
    checkEq("amazon/nova-lite-v1 resolves", resolve("amazon/nova-lite-v1", in: catalog).tier, .exact)
    checkEq("a vendor-prefixed id not in the index falls to the tail", resolve("someproxy/gpt-5.6-sol", in: catalog).tier, .tail)
    checkEq("…and gets the SAME entry as the bare id", resolve("someproxy/gpt-5.6-sol", in: catalog).entry?.day, resolve("gpt-5.6-sol", in: catalog).entry?.day)
    // Decorations: `:free`, `@default`, Bedrock region prefixes.
    checkEq("kimi-k2-6:free → the `:free` suffix is cleaned off, then an exact hit", resolve("kimi-k2-6:free", in: catalog).tier, .exact)
    checkEq("…same entry as the undecorated id", resolve("kimi-k2-6:free", in: catalog).entry?.day, resolve("kimi-k2-6", in: catalog).entry?.day)
    checkEq("openrouter/kimi-k2-6:free → both decorations handled", resolve("openrouter/kimi-k2-6:free", in: catalog).tier, .tail)
    checkEq("claude-sonnet-5@default → exact after cleaning", resolve("claude-sonnet-5@default", in: catalog).tier, .exact)
    checkEq("us.anthropic.claude-opus-4-5-20251101-v1:0 resolves to claude-opus-4-5",
            resolve("us.anthropic.claude-opus-4-5-20251101-v1:0", in: catalog).entry?.day, catalog.byTail["claude-opus-4-5"]?.day)
    checkEq("global./eu./apac. region prefixes too", resolve("eu.anthropic.claude-opus-4-5-20251101", in: catalog).entry?.day, catalog.byTail["claude-opus-4-5"]?.day)
    checkEq("a plain dated id strips its snapshot stamp", resolve("claude-opus-4-5-20251101", in: catalog).tier, .tail)
    // The family walk, one token at a time.
    checkEq("gpt-5.6-terra (unknown) walks up to gpt-5.6", resolve("gpt-5.6-terra", in: catalog).entry?.day, catalog.byTail["gpt-5.6"]?.day)
    checkEq("…and is reported as a prefix match", resolve("gpt-5.6-terra", in: catalog).tier, .prefix)
    // NO fuzzy fallback: the prototype that matched a Bedrock opus-4-5 onto
    // claude-opus-5 put a six-month-old model at the top of the list.
    checkEq("claude-opus-5 is not invented from claude-opus-4-5", resolve("claude-opus-5", in: catalog).tier, .miss)
    checkEq("a wholly unknown id misses rather than guessing", resolve("llama-9-titan", in: catalog).tier, .miss)
    checkEq("an undated catalog row is absent from the index (miss, not day 0)", resolve("no-date-model", in: catalog).tier, .miss)
    checkEq("empty id misses", resolve("", in: catalog).tier, .miss)
    check("a nil index makes everything a miss", resolve("gpt-5.6-sol", in: nil).entry == nil)

    // stripVendorDotPrefix must not eat a version dot.
    checkEq("gpt-5.6 keeps its version dot", stripVendorDotPrefix("gpt-5.6"), "gpt-5.6")
    checkEq("anthropic.claude-x loses the namespace", stripVendorDotPrefix("anthropic.claude-x"), "claude-x")
    checkEq("a leading dot is not a namespace", stripVendorDotPrefix(".weird"), ".weird")
    checkEq("gpt-4.1-mini keeps its dot", stripVendorDotPrefix("gpt-4.1-mini"), "gpt-4.1-mini")
    // stripDateSuffix is exact about the shape.
    checkEq("-20260115 is stripped", stripDateSuffix("claude-opus-9-20260115"), "claude-opus-9")
    checkEq("-19991231 is NOT (must start 20)", stripDateSuffix("model-19991231"), "model-19991231")
    checkEq("-2026011 (7 digits) is NOT", stripDateSuffix("model-2026011"), "model-2026011")
    checkEq("-2026-01-15 is NOT (not one token)", stripDateSuffix("model-2026-01-15"), "model-2026-01-15")
    checkEq("a bare date-looking id is left alone", stripDateSuffix("-20260115"), "-20260115")
    // parseReleaseDate takes the short form (181 bundled entries use it).
    check("YYYY-MM parses", parseReleaseDate("2026-08") != nil)
    check("YYYY-MM-DD parses", parseReleaseDate("2026-08-14") != nil)
    check("YYYY alone does not", parseReleaseDate("2026") == nil)
    check("month 13 does not", parseReleaseDate("2026-13-01") == nil)
    check("day 32 does not", parseReleaseDate("2026-08-32") == nil)
    check("a two-digit year does not", parseReleaseDate("26-08-14") == nil)
    checkEq("YYYY-MM is day 1 of that month", parseReleaseDate("2026-08")!.day, parseReleaseDate("2026-08-01")!.day)
    check("a later date is a larger day number", parseReleaseDate("2026-08-14")!.day > parseReleaseDate("2026-07-01")!.day)
}

print("▶️  2. dedup across vendor prefixes keeps the NEWEST date")
do {
    // openai/gpt-5.6-sol (2026-08-01) and gpt-5.6-sol (2026-08-14) share a tail.
    checkEq("the shared tail carries the newer of the two", catalog.byTail["gpt-5.6-sol"]?.day, parseReleaseDate("2026-08-14")?.day)
    checkEq("each full id keeps its own date", catalog.byFullId["openai/gpt-5.6-sol"]?.day, parseReleaseDate("2026-08-01")?.day)
    // zai/glm-5.2 (2026-05-20) is NEWER than the bare glm-5.2 (2026-02-01).
    checkEq("a newer republish raises the shared tail", catalog.byTail["glm-5.2"]?.day, parseReleaseDate("2026-05-20")?.day)
    checkEq("…but the bare full id is untouched", catalog.byFullId["glm-5.2"]?.day, parseReleaseDate("2026-02-01")?.day)
    // Order-independence: the dedup must not depend on dictionary iteration order.
    let rows: [(id: String, releaseDate: String?, outputCost: Double?, context: Int?)] = [
        ("a/m", "2026-01-01", 1, 100), ("b/m", "2026-09-09", 2, 200), ("c/m", "2025-05-05", 3, 300),
    ]
    let forward = buildIndex(rows), backward = buildIndex(rows.reversed())
    checkEq("newest wins whichever order rows arrive in", forward.byTail["m"]?.day, backward.byTail["m"]?.day)
    checkEq("…and it is the 2026-09-09 row", forward.byTail["m"]?.day, parseReleaseDate("2026-09-09")?.day)
    // An undated row must never displace a dated one.
    let mixed = buildIndex([("x/m2", "2026-01-01", 1, 100), ("y/m2", nil, 9, 900)])
    checkEq("an undated republish does not erase the dated entry", mixed.byTail["m2"]?.day, parseReleaseDate("2026-01-01")?.day)
    checkEq("…and its own full id is absent entirely", mixed.byFullId["y/m2"]?.day, nil)
}

print("▶️  3. Rank ordering, and undated entries sort last without being hidden")
do {
    func r(_ day: Int?, _ cost: Double = 0, _ ctx: Int = 0, _ name: String = "m") -> Rank {
        Rank(releaseDay: day, outputCostPerMTok: cost, contextWindow: ctx, displayName: name)
    }
    check("newer date sorts first", r(200) < r(100))
    check("…and not the reverse", !(r(100) < r(200)))
    check("a dated model outranks an undated one", r(1) < r(nil))
    check("…symmetrically", !(r(nil) < r(1)))
    check("two undated fall through to price", r(nil, 10) < r(nil, 1))
    check("same date → dearer (≈ larger) first", r(100, 30) < r(100, 5))
    check("same date and price → bigger context first", r(100, 5, 400_000) < r(100, 5, 128_000))
    check("all equal → case-insensitive name order", r(100, 5, 1000, "alpha") < r(100, 5, 1000, "Beta"))
    check("…and the reverse is false", !(r(100, 5, 1000, "Beta") < r(100, 5, 1000, "alpha")))
    check("a Rank is never less than itself", !(r(100, 5, 1000, "m") < r(100, 5, 1000, "m")))

    // #83 in miniature: the alphabetical list led with the model that 400s.
    let codex = [
        Entry(id: "gpt-5.3-codex-spark", displayName: "GPT-5.3 CodeX Spark", contextWindow: 272_000),
        Entry(id: "gpt-5.6-sol", displayName: "GPT-5.6 Sol", contextWindow: 400_000),
        Entry(id: "gpt-5.6", displayName: "GPT-5.6", contextWindow: 400_000),
    ]
    checkEq("PRE-FIX alphabetical put the uncallable model first", codex.sorted { $0.id < $1.id }.first?.id, "gpt-5.3-codex-spark")
    let ranked = rankedOnce(codex, index: catalog)
    checkEq("ranked: newest first", ranked.map(\.id), ["gpt-5.6-sol", "gpt-5.6", "gpt-5.3-codex-spark"])

    // Undated custom / local / relay entries stay in the list, at the end.
    let withCustom = codex + [
        Entry(id: "my-local-llama", displayName: "My Local Llama", contextWindow: 8192),
        Entry(id: "another-local", displayName: "Another Local", contextWindow: 8192),
    ]
    let order = rankedOnce(withCustom, index: catalog)
    checkEq("nothing is dropped", order.count, withCustom.count)
    checkEq("undated entries are the tail, alphabetical among themselves", Array(order.suffix(2)).map(\.id), ["another-local", "my-local-llama"])
    check("every dated entry precedes every undated one", order.prefix(3).allSatisfy { $0.id.hasPrefix("gpt-") })
}

print("▶️  4. the comparator is a total order — the list cannot jitter")
do {
    // Two entries that rank IDENTICALLY (both undated, no price, same context):
    // without the id tiebreak `sorted` may return either order.
    let a = Entry(id: "zeta-local", displayName: "Same Name", contextWindow: 8192)
    let b = Entry(id: "alpha-local", displayName: "Same Name", contextWindow: 8192)
    check("a < b by id", releaseRankOrder(b, a, index: catalog))
    check("…and not the reverse", !releaseRankOrder(a, b, index: catalog))
    check("irreflexive", !releaseRankOrder(a, a, index: catalog))
    // Repeated sorts of a shuffled input must produce one identical order.
    let pool = [
        Entry(id: "gpt-5.6-sol", displayName: "GPT-5.6 Sol", contextWindow: 400_000),
        Entry(id: "claude-sonnet-5", displayName: "Claude Sonnet 5", contextWindow: 1_000_000),
        Entry(id: "glm-5.2", displayName: "GLM-5.2", contextWindow: 200_000),
        Entry(id: "kimi-k2-6:free", displayName: "Kimi K2.6", contextWindow: nil),
        Entry(id: "aion-labs/aion-2.0", displayName: "Aion 2.0", contextWindow: nil),
        a, b,
        Entry(id: "amazon/nova-lite-v1", displayName: "Nova Lite", contextWindow: nil),
    ]
    let reference = pool.sorted { releaseRankOrder($0, $1, index: catalog) }.map(\.id)
    var stable = true
    for seed in 0..<12 {
        var shuffled = pool
        // A deterministic rotation rather than a random shuffle, so a failure is
        // reproducible.
        shuffled = Array(shuffled[(seed % pool.count)...] + shuffled[..<(seed % pool.count)])
        if shuffled.sorted(by: { releaseRankOrder($0, $1, index: catalog) }).map(\.id) != reference { stable = false }
    }
    check("12 different input orders all sort to the same output", stable)
    checkEq("…and that output is newest-first", reference.first, "gpt-5.6-sol")
    // The decorate-sort-undecorate form must produce a BIT-IDENTICAL order.
    checkEq("rank-once ordering == rank-in-comparator ordering", rankedOnce(pool, index: catalog).map(\.id), reference)
}

print("▶️  5. rank cost per sort (Android 710b43a64's ANR, measured on iOS's shape)")
do {
    let n = 200
    let entries = (0..<n).map { Entry(id: "model-\($0)", displayName: "Model \($0)", contextWindow: 8192) }
    resolveCalls = 0
    _ = entries.sorted { releaseRankOrder($0, $1, index: catalog) }
    let inComparator = resolveCalls
    resolveCalls = 0
    _ = rankedOnce(entries, index: catalog)
    let onceEach = resolveCalls
    checkEq("decorate-sort-undecorate ranks exactly once per entry", onceEach, n)
    check("ranking inside the comparator costs O(n log n) instead", inComparator > 4 * n)
    print("     (n=\(n): in-comparator \(inComparator) resolve calls vs rank-once \(onceEach))")
    // iOS used to rank both sides inside the comparator, the exact shape Android
    // 710b43a64 replaced after a Pixel 4a ANR'd opening a chat (939 entries →
    // ~18,600 rank calls on the main thread, the first of which also paid for the
    // lazy catalog parse). `sortedByReleaseRank` now ranks each entry once; the
    // ordering is bit-for-bit unchanged — section 4 asserts the two forms agree.
    let store = source("Providers/ProviderConfigStore.swift")
    if store.isEmpty { print("  ⏭  source not readable") } else {
        check("iOS ranks each entry once per sort, not once per comparison",
              store.contains("static func sortedByReleaseRank(") && store.contains("ModelReleaseIndex.rank("))
        check("…with the early return for <2 elements",
              store.contains("guard entries.count > 1 else { return entries }"))
        check("…and the id tiebreak preserved inside the decorated sort",
              store.contains("return a.entry.baseModel.id < b.entry.baseModel.id"))
    }
}

print("▶️  6. shipping sources still carry the pinned lines")
do {
    let idx = source("Providers/ModelReleaseIndex.swift")
    let api = source("Providers/ModelsDevAPI.swift")
    let store = source("Providers/ProviderConfigStore.swift")
    if idx.isEmpty || api.isEmpty || store.isEmpty { print("  ⏭  sources not readable") } else {
        check("undated sorts last", idx.contains("case (nil, _?): return false") && idx.contains("case (_?, nil): return true"))
        check("newest first", idx.contains("case let (l?, r?) where l != r: return l > r"))
        check("then price, then context, then name", idx.contains("return lhs.outputCostPerMTok > rhs.outputCostPerMTok")
              && idx.contains("return lhs.contextWindow > rhs.contextWindow")
              && idx.contains("localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending"))
        check("full id is tried BEFORE the tail", (idx.range(of: "index.byFullId[cleaned]")?.lowerBound ?? idx.endIndex)
              < (idx.range(of: "index.byTail[tail]")?.lowerBound ?? idx.startIndex))
        check("…with the measured reason recorded", idx.contains("going tail-first silently loses them"))
        check("family walk drops one token at a time", idx.contains("while parts.count > 1 {"))
        check("no fuzzy fallback", idx.contains("Deliberately NO fuzzy/substring fallback"))
        check("the miss path returns nil rather than a guess", idx.contains("return Hit(date: nil, day: nil, outputCost: nil, context: nil, tier: .miss)"))
        check("date suffix must be -20YYMMDD", idx.contains("digits.count == 8, digits.allSatisfy(\\.isNumber), digits.hasPrefix(\"20\")"))
        check("vendor dot prefix requires an all-letter head", idx.contains("guard !head.isEmpty, head.allSatisfy({ $0.isLetter }) else { return s }"))
        check("YYYY-MM is accepted", idx.contains("guard parts.count == 2 || parts.count == 3,"))
        check("index dedup keeps the newest for byFullId", api.contains("if let existing = byFullId[full], existing.day >= entry.day {} else { byFullId[full] = entry }"))
        check("…and for byTail", api.contains("if let existing = byTail[tail], existing.day >= entry.day {} else { byTail[tail] = entry }"))
        check("…with the reason recorded", api.contains("Keep the NEWEST"))
        check("rows without a parseable date are skipped", api.contains("guard let raw = model.releaseDate,\n                      let parsed = ModelReleaseIndex.parseReleaseDate(raw) else { continue }"))
        check("the comparator has an id tiebreak for a total order", store.contains("return a.baseModel.id < b.baseModel.id"))
        check("both entry getters share that one sort", store.contains("Self.sortedByReleaseRank("))
        checkEq("…and neither has drifted to another sort", store.components(separatedBy: "Self.sortedByReleaseRank(").count - 1, 2)
        // The pairwise comparator survives only as a tiebreak inside another
        // comparator (the picker's search-relevance sort), never as a whole-list
        // sort — that is the shape that cost O(n log n) rank calls.
        checkEq("no caller sorts a whole list through the pairwise comparator",
                (store + source("Views/Providers/UnifiedModelPicker.swift"))
                    .components(separatedBy: "sorted(by: ProviderConfigStore.releaseRankOrder)").count - 1, 0)
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
