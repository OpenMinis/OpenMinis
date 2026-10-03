// Tests for [T-config-model-id-dot] — a model id containing a DOT must still be
// addressable through ConfigRegistry.
//
// The bug: a model's real id is its compositeKey, "<instanceUUID>/<modelId>",
// and `modelId` is whatever the user typed when adding a custom model — so it
// can contain dots ("gpt-4.1", "my.model.v2"). `ConfigRegistry.resolveField`
// splits a path with `maxSplits: 2` into exactly [base, id, leaf], so
//
//   models.<uuid>/my.model.v2.displayName
//
// parses as id="<uuid>/my", leaf="model.v2.displayName". The id resolves to
// nothing and every read/write answers `unknown_path`. Reproduced on an
// iPhone 11: the dot-free control model read back fine, the dot-bearing one
// returned unknown_path for the identical operation.
//
// The first fix (09-18) escaped the id into a dot-free path segment inside the
// collection ("." → "~d", "~" → "~~"). It worked but was undiscoverable: `get
// models` printed the raw id and nothing mentioned `~d` (OpenMinis#390).
//
// [T-config-path-dotted-id] superseded it: the resolver now keeps everything
// between the first and last dot as the id (ConfigPathSplitTests), and paths
// carry the RAW id. The escape is kept only as an accepted legacy INPUT, so
// audit keys and scripts from the escaped era still resolve. This file keeps
// pinning that legacy codec: the encoder below generates legacy paths, and the
// decoder is the one ModelsCollection still uses as its fallback.
//
// Standalone (`swift ModelConfigPathDotTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Under test (mirrors ModelsCollection.swift)

enum Seg {
    static func pathSegment(_ id: String) -> String {
        var out = ""
        out.reserveCapacity(id.count)
        for ch in id {
            switch ch {
            case "~": out += "~~"
            case ".": out += "~d"
            default: out.append(ch)
            }
        }
        return out
    }
    static func realId(_ segment: String) -> String {
        var out = ""
        out.reserveCapacity(segment.count)
        var it = segment.makeIterator()
        while let ch = it.next() {
            guard ch == "~" else { out.append(ch); continue }
            switch it.next() {
            case "~": out.append("~")
            case "d": out.append(".")
            case let other?: out.append(other)
            case nil: out.append("~")
            }
        }
        return out
    }
}

/// The CURRENT resolver split (ConfigRegistry.splitCollectionPath).
func resolveSegments(_ path: String) -> (base: String, id: String, leaf: String)? {
    guard let first = path.firstIndex(of: "."), let last = path.lastIndex(of: "."), first < last else { return nil }
    let base = String(path[..<first])
    let id = String(path[path.index(after: first)..<last])
    let leaf = String(path[path.index(after: last)...])
    guard !base.isEmpty, !id.isEmpty, !leaf.isEmpty else { return nil }
    return (base, id, leaf)
}

/// The pre-#390 split (`maxSplits: 2`), kept as the regression witness.
func oldSegments(_ path: String) -> (base: String, id: String, leaf: String)? {
    let s = path.split(separator: ".", maxSplits: 2, omittingEmptySubsequences: true).map(String.init)
    guard s.count == 3 else { return nil }
    return (s[0], s[1], s[2])
}

let uuid = "84BF82A6-4533-4A9F-94D5-C4FD1CD09569"

// MARK: - 1. Round-trip

print("\n▶️  escape/unescape round-trips for every id shape")
let ids = [
    "\(uuid)/mymodelv2",             // the dot-free control
    "\(uuid)/my.model.v2",           // the reported case
    "\(uuid)/gpt-4.1",               // the realistic case
    "\(uuid)/a.b.c.d.e",             // many dots
    "\(uuid)/.leading",              // leading dot
    "\(uuid)/trailing.",             // trailing dot
    "\(uuid)/only.",                 // dot at the very end
    "\(uuid)/has~tilde",             // the escape character itself
    "\(uuid)/tilde~d~literal",       // text that LOOKS like an escape
    "\(uuid)/mixed~and.dots~d~x",    // both, interleaved
    "\(uuid)/中文.模型",              // non-ASCII either side of a dot
    "\(uuid)/",                      // empty model id
]
for id in ids {
    checkEq("round-trip \(id.suffix(22))", Seg.realId(Seg.pathSegment(id)), id)
}

print("\n▶️  the escape is itself escaped, so the mapping is injective")
// Without escaping "~" first, a model id literally containing "~d~" would
// decode into a dot and collide with a different id.
let a = "\(uuid)/tilde~d~literal"
let b = "\(uuid)/tilde.literal"
check("two ids that could collide stay distinct", Seg.pathSegment(a) != Seg.pathSegment(b))
checkEq("and each still round-trips (a)", Seg.realId(Seg.pathSegment(a)), a)
checkEq("and each still round-trips (b)", Seg.realId(Seg.pathSegment(b)), b)

// MARK: - 2. The resolver can now parse the path

print("\n▶️  every LEGACY escaped path still parses into [models, id, leaf]")
for id in ids {
    let path = "models.\(Seg.pathSegment(id)).displayName"
    guard let r = resolveSegments(path) else {
        check("resolve \(id.suffix(18))", false); continue
    }
    let ok = r.base == "models" && r.leaf == "displayName" && Seg.realId(r.id) == id
    check("resolve \(id.suffix(18)) → id recovered intact", ok)
}

print("\n▶️  the UNESCAPED form broke under the OLD split (regression witness)")
let brokenPath = "models.\(uuid)/my.model.v2.displayName"
if let r = resolveSegments(brokenPath) {
    checkEq("current split keeps the raw dotted id whole", r.id, "\(uuid)/my.model.v2")
    checkEq("…and the leaf is the last segment", r.leaf, "displayName")
}
if let r = oldSegments(brokenPath) {
    checkEq("old behaviour: id truncated at the first dot", r.id, "\(uuid)/my")
    checkEq("old behaviour: leaf swallowed the rest", r.leaf, "model.v2.displayName")
    check("so the id no longer matches the real entry", r.id != "\(uuid)/my.model.v2")
} else {
    check("the broken path still parses into 3 segments", false)
}

// MARK: - 3. Dot-free ids are untouched
//
// The fix must not change the path of any model that worked before, or every
// existing script / saved path breaks.

print("\n▶️  ids without dots or tildes keep their exact original path")
for id in ["\(uuid)/mymodelv2", "\(uuid)/gpt-4o", "\(uuid)/claude-opus-5", uuid] {
    checkEq("unchanged: \(id.suffix(18))", Seg.pathSegment(id), id)
}
// Which means the pre-existing path text is byte-identical.
checkEq("path text for a dot-free model is unchanged",
        "models.\(Seg.pathSegment("\(uuid)/gpt-4o")).displayName",
        "models.\(uuid)/gpt-4o.displayName")

// MARK: - 4. Leaves are one segment
//
// The old comment allowed a dotted LEAF (`modality.video`); no such field
// exists, and the current split reads the field from the LAST dot, so a dotted
// leaf would be misread. ConfigPathSplitTests scans every collection to keep
// leaves single-segment; this pins what a dotted leaf would turn into.

print("\n▶️  a dotted leaf is NOT supported by the current split")
let dottedLeaf = "models.\(uuid)/gpt-4o.modality.video"
if let r = resolveSegments(dottedLeaf) {
    checkEq("the field is the last segment only", r.leaf, "video")
    checkEq("…the rest joins the id", r.id, "\(uuid)/gpt-4o.modality")
} else {
    check("dotted leaf parses", false)
}

// MARK: - 4b. [M05] Escaped exactly once, decoded exactly once
//
// The iOS twin of the Android regression 2bb448166: the escaping itself was
// correct, yet dotted lookups still answered `unknown_path`, because
// `ConfigRegistry.resolveField` hands `fields(for:)` the ALREADY-ESCAPED
// segment while each field factory escapes the id again when it builds its own
// `path`. `my~dmodel~dv2` became `my~~dmodel~~dv2`, the rebuilt path matched
// nothing, and the symptom was identical to having no fix at all.
//
// On iOS the boundary is ModelsCollection.fields(for segment:) line 92 — it
// decodes ONCE (`let id = Self.realId(segment)`) and every factory below it
// re-escapes that decoded id. These cases pin both halves of that contract.

print("\n▶️  double-escaping is detectable, and a single decode undoes exactly one escape")

let dotted = "\(uuid)/my.model.v2"
let once = Seg.pathSegment(dotted)
let twice = Seg.pathSegment(once)
check("escaping twice does NOT equal escaping once (the escape is not idempotent)",
      once != twice)
checkEq("…and the doubled form is the reported shape",
        Seg.pathSegment("my.model.v2"), "my~dmodel~dv2")
checkEq("…escaped again, the tildes double", Seg.pathSegment("my~dmodel~dv2"), "my~~dmodel~~dv2")
// One decode of a doubly-escaped segment yields the ONCE-escaped form, not the
// real id — which is precisely why the rebuilt path matched nothing.
checkEq("one decode of the doubled form gives back the escaped form, not the id",
        Seg.realId(twice), once)
check("…which is not the real id", Seg.realId(twice) != dotted)

print("\n▶️  the boundary contract: decode once at entry, re-escape inside")

/// Mirrors the resolver → collection → field-factory chain.
/// `resolveField` splits the caller's path and passes segments[1] (still
/// escaped) to `fields(for:)`; `fields(for:)` decodes once; each factory
/// re-escapes when publishing its own path.
func fieldPaths(forSegment segment: String, leaves: [String]) -> [String] {
    let id = Seg.realId(segment)                     // ModelsCollection.swift:92
    return leaves.map { "models.\(Seg.pathSegment(id)).\($0)" }
}
/// The buggy variant: `forId` passed straight through without decoding.
func fieldPathsDoubleEscaped(forSegment segment: String, leaves: [String]) -> [String] {
    leaves.map { "models.\(Seg.pathSegment(segment)).\($0)" }
}

let leaves = ["displayName", "contextWindow", "modalitiesOverride"]
let callerPath = "models.\(once).displayName"
let rebuilt = fieldPaths(forSegment: once, leaves: leaves)
check("the correct chain rebuilds the caller's own path (lookup succeeds)",
      rebuilt.contains(callerPath))
check("the double-escaping chain does NOT (this is `unknown_path`)",
      fieldPathsDoubleEscaped(forSegment: once, leaves: leaves).contains(callerPath), false)
// Every leaf, not just the one the report happened to use.
for leaf in leaves {
    check("leaf \(leaf) round-trips through the chain",
          rebuilt.contains("models.\(once).\(leaf)"))
}
// A dot-free id is immune either way — which is exactly why the bug shipped.
let plainSeg = Seg.pathSegment("\(uuid)/gpt-4o")
checkEq("a dot-free id is identical under both chains (why the bug was invisible)",
        fieldPaths(forSegment: plainSeg, leaves: ["displayName"]),
        fieldPathsDoubleEscaped(forSegment: plainSeg, leaves: ["displayName"]))
// And an id containing the escape character is also caught by the same check.
let tildeSeg = Seg.pathSegment("\(uuid)/has~tilde")
check("a tilde-bearing id also distinguishes the two chains",
      fieldPaths(forSegment: tildeSeg, leaves: ["displayName"])
        != fieldPathsDoubleEscaped(forSegment: tildeSeg, leaves: ["displayName"]))

print("\n▶️  remove()/add() sit on the same boundary")

// `add()` returns an escaped segment, which a caller may hand straight back to
// `remove()`; `remove()` must decode exactly once to find the entry.
func addReturningSegment(_ realId: String) -> String { Seg.pathSegment(realId) }
func removeDecoding(_ segment: String) -> String { Seg.realId(segment) }
for id in [dotted, "\(uuid)/gpt-4.1", "\(uuid)/has~tilde", "\(uuid)/mymodelv2"] {
    checkEq("add→remove recovers \(id.suffix(16))", removeDecoding(addReturningSegment(id)), id)
}
// Decoding twice would corrupt an id that legitimately contains "~~" or "~d".
let trapId = "\(uuid)/tilde~d~literal"
checkEq("decoding once recovers a ~d-bearing id",
        Seg.realId(Seg.pathSegment(trapId)), trapId)
check("decoding twice corrupts it (so the decode must not be repeated)",
      Seg.realId(Seg.realId(Seg.pathSegment(trapId))) != trapId)

// MARK: - 5. Source invariants

print("\n▶️  source invariants")
let path = "../../Shared/Config/Collections/ModelsCollection.swift"
guard let src = try? String(contentsOfFile: path, encoding: .utf8) else {
    print("  ❌ could not read \(path)"); failures += 1; exit(1)
}
// [T-config-path-dotted-id] Paths carry the raw id; the escape survives only
// as a decoder for legacy input.
check("the legacy decoder exists", src.contains("private static func decodeLegacySegment(_ segment: String) -> String {"))
// Decoding must be a single scan, not two replacingOccurrences passes — the
// two-pass form is not injective (see the codec comment in ModelsCollection).
check("decode is a single-pass scan", src.contains("var it = segment.makeIterator()"))
check("no encoder is left in the app (paths are raw)", !src.contains("pathSegment"))
checkEq("every field path interpolates the raw id",
        src.components(separatedBy: "path: \"models.\\(id).").count - 1, 12)
check("childIds publishes raw ids", src.contains("modelEntries.map(\\.id)"))
check("add() returns the raw id", src.contains("        return entry.id\n"))
check("lookup tries the raw id before decoding",
      (src.range(of: "if let e = entry(idOrSegment) { return e }")?.lowerBound ?? src.endIndex)
        < (src.range(of: "let decoded = Self.decodeLegacySegment(idOrSegment)")?.lowerBound ?? src.startIndex))
check("fields(for:) and remove(id:) go through lookup",
      src.components(separatedBy: "lookup(idOrSegment)").count - 1 == 2)
// The decoder must be applied once per lookup, never chained.
checkEq("the decoder is never applied to its own result",
        src.components(separatedBy: "decodeLegacySegment(Self.decodeLegacySegment").count - 1, 0)

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All model config-path dot tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
