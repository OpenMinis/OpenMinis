// Tests for [T-config-path-dotted-id] — OpenMinis#390: `minis-config` could not
// address a model whose id contains a dot.
//
// Root cause: ConfigRegistry.resolveField split a path with `maxSplits: 2` into
// exactly [base, id, leaf], so an id was cut at its FIRST dot:
//
//   models.<uuid>/mimo-v2.6-pro.contextWindow
//     → id "<uuid>/mimo-v2", leaf "6-pro.contextWindow" → unknown_path
//
// Most model ids have a dot (glm-5.1, gpt-4.1, …). A 09-18 workaround escaped
// dots as `~d`, but `get models` printed the raw id, the help never mentioned
// `~d`, and the reporter tried four escape styles without guessing it.
//
// Fix: the topic runs to the FIRST dot, the field starts after the LAST dot,
// and the id is everything between (splitCollectionPath). Fields are matched by
// leaf, ModelsCollection publishes raw ids and still accepts the legacy `~d`
// form, and unknown_path explains which part was wrong.
//
// Part 1 ports the splitter and runs the fixture table (shared with Android's
// ConfigPathSplitTest). Part 2 runs the ported lookup and messages. Part 3 pins
// them to the shipping source, including the one-segment-leaf invariant the
// splitter depends on.
//
// Standalone: `swift ConfigPathSplitTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Port of ConfigRegistry.splitCollectionPath / leaf(of:)

func splitCollectionPath(_ path: String) -> (base: String, id: String, leaf: String)? {
    guard let first = path.firstIndex(of: "."),
          let last = path.lastIndex(of: "."),
          first < last else { return nil }
    let base = String(path[..<first])
    let id = String(path[path.index(after: first)..<last])
    let leaf = String(path[path.index(after: last)...])
    guard !base.isEmpty, !id.isEmpty, !leaf.isEmpty else { return nil }
    return (base, id, leaf)
}

/// The pre-#390 splitter, for the regression witness.
func oldSplit(_ path: String) -> (base: String, id: String, leaf: String)? {
    let s = path.split(separator: ".", maxSplits: 2, omittingEmptySubsequences: true).map(String.init)
    guard s.count == 3 else { return nil }
    return (s[0], s[1], s[2])
}

let U = "84BF82A6-4533-4A9F-94D5-C4FD1CD09569"

print("▶️  1. split fixture table")
let table: [(String, (String, String, String)?)] = [
    ("models.\(U)/mimo-v2.6-pro.contextWindow", ("models", "\(U)/mimo-v2.6-pro", "contextWindow")),
    ("models.\(U)/kimi-k3.contextWindow", ("models", "\(U)/kimi-k3", "contextWindow")),
    ("models.\(U)/glm-5.1.isHidden", ("models", "\(U)/glm-5.1", "isHidden")),
    ("models.\(U)/01-ai/yi-large.displayName", ("models", "\(U)/01-ai/yi-large", "displayName")),
    ("models.\(U)/my.model.v2.displayName", ("models", "\(U)/my.model.v2", "displayName")),
    ("subagents.builtin.general.instructions", ("subagents", "builtin.general", "instructions")),
    ("models.\(U)/mimo-v2~d6-pro.contextWindow", ("models", "\(U)/mimo-v2~d6-pro", "contextWindow")),
    ("models", nil),
    ("models.\(U)", nil),
    ("models..x", nil),
    ("models.\(U).", nil),
    (".x.y", nil),
]
for (path, want) in table {
    let got = splitCollectionPath(path)
    let ok: Bool
    switch (got, want) {
    case (nil, nil): ok = true
    case let (g?, w?): ok = g.base == w.0 && g.id == w.1 && g.leaf == w.2
    default: ok = false
    }
    check("\(path.replacingOccurrences(of: U, with: "U")) → \(want.map { "(\($0.0), \($0.1.replacingOccurrences(of: U, with: "U")), \($0.2))" } ?? "nil")", ok)
}
do {
    let reported = "models.\(U)/mimo-v2.6-pro.contextWindow"
    let old = oldSplit(reported)
    check("OLD splitter cut the reported id at its first dot (the bug)",
          old?.id == "\(U)/mimo-v2" && old?.leaf == "6-pro.contextWindow")
}

// MARK: - Port of the ModelsCollection lookup + registry resolve

func decodeLegacySegment(_ segment: String) -> String {
    var out = ""
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

let entries = ["\(U)/mimo-v2.6-pro", "\(U)/kimi-k3", "\(U)/glm-5.1", "\(U)/lit~deral"]
let leaves = ["providerInstanceId", "modelId", "isCustom", "displayName", "maxOutputTokens", "isHidden",
              "modalities", "modalitiesOverride", "contextWindow", "contextWindowOverride",
              "supportsTools", "supportsVision"]

func lookup(_ idOrSegment: String) -> String? {
    if entries.contains(idOrSegment) { return idOrSegment }
    let decoded = decodeLegacySegment(idOrSegment)
    guard decoded != idOrSegment else { return nil }
    return entries.contains(decoded) ? decoded : nil
}
func fieldsFor(_ id: String) -> [String] {
    guard let real = lookup(id) else { return [] }
    return leaves.map { "models.\(real).\($0)" }
}
func leafOf(_ p: String) -> String { p.lastIndex(of: ".").map { String(p[p.index(after: $0)...]) } ?? p }
/// Resolve → the canonical field path, or nil (unknown_path).
func resolve(_ path: String) -> String? {
    guard let (base, id, leaf) = splitCollectionPath(path), base == "models" else { return nil }
    return fieldsFor(id).first { leafOf($0) == leaf }
}

print("\n▶️  2. resolve through the collection")
check("dotted id resolves (the #390 acceptance case)",
      resolve("models.\(U)/mimo-v2.6-pro.contextWindow") == "models.\(U)/mimo-v2.6-pro.contextWindow")
check("glm-5.1 resolves", resolve("models.\(U)/glm-5.1.isHidden") == "models.\(U)/glm-5.1.isHidden")
check("dot-free control still resolves", resolve("models.\(U)/kimi-k3.contextWindow") != nil)
check("legacy ~d path resolves to the canonical raw path",
      resolve("models.\(U)/mimo-v2~d6-pro.contextWindow") == "models.\(U)/mimo-v2.6-pro.contextWindow")
check("an id literally containing ~d is found raw, not decoded",
      resolve("models.\(U)/lit~deral.displayName") == "models.\(U)/lit~deral.displayName")
check("unknown leaf → nil", resolve("models.\(U)/glm-5.1.nope") == nil)
check("two-part path → nil", resolve("models.\(U)/glm-5.1") == nil)
do {
    // The half-fix: splitting right but still comparing whole paths fails
    // whenever the collection spells the id differently (here: legacy input).
    let path = "models.\(U)/mimo-v2~d6-pro.contextWindow"
    let (_, id, _) = splitCollectionPath(path)!
    check("whole-path comparison would still miss it (why leaf matching is needed)",
          fieldsFor(id).contains(path), false)
}

// MARK: - Port of explainUnknownPath (models only; text must match the source)

func noEntry(_ base: String, _ id: String) -> String {
    "No entry '\(id)' under '\(base)'. Run `minis-config get \(base)` and use an entry_id verbatim — ids may contain dots and slashes, no escaping needed: \(base).<entry_id>.<field>."
}
func explain(_ path: String) -> String {
    let base = path.firstIndex(of: ".").map { String(path[..<$0]) } ?? path
    guard base == "models" else { return "No topic '\(base)'. Run `minis-config list-topics`." }
    guard let dot = path.firstIndex(of: ".") else { return "No registered field at '\(path)'." }
    let rest = String(path[path.index(after: dot)...])
    let el = fieldsFor(rest).map(leafOf)
    if !el.isEmpty {
        return "'\(path)' names an entry, not a field. Append a field, e.g. \(base).\(rest).\(el[0]). Fields: \(el.joined(separator: ", "))."
    }
    if let (_, id, leaf) = splitCollectionPath(path) {
        let il = fieldsFor(id).map(leafOf)
        if !il.isEmpty { return "Unknown field '\(leaf)' for \(base) entry '\(id)'. Fields: \(il.joined(separator: ", "))." }
        return noEntry(base, id)
    }
    return noEntry(base, rest)
}

print("\n▶️  3. guided unknown_path messages")
let e1 = explain("models.\(U)/mimo-v2.6-pro")
check("two-part path says it names an entry and gives an example",
      e1.hasPrefix("'models.\(U)/mimo-v2.6-pro' names an entry, not a field. Append a field, e.g. models.\(U)/mimo-v2.6-pro.providerInstanceId."))
check("…and lists the fields", e1.contains("Fields: providerInstanceId, modelId,"))
check("unknown field names the entry and lists fields",
      explain("models.\(U)/glm-5.1.nope").hasPrefix("Unknown field 'nope' for models entry '\(U)/glm-5.1'. Fields: "))
check("unknown entry points at `get models` and says no escaping",
      explain("models.\(U)/nosuch-1.0.contextWindow") == noEntry("models", "\(U)/nosuch-1.0"))
check("unknown topic points at list-topics",
      explain("modles.x.y") == "No topic 'modles'. Run `minis-config list-topics`.")

// MARK: - Sources

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func source(_ rel: String) -> String {
    (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let registry = source("Shared/Config/ConfigRegistry.swift")
let models = source("Shared/Config/Collections/ModelsCollection.swift")
let bridge = source("NativeOffloads/ConfigOffloadBridge.swift")
let objc = source("NativeOffloads/ConfigOffload.m")

print("\n▶️  4. sources")
check("the three-way split is gone", !registry.contains("maxSplits: 2,"))
check("splitter as ported",
      registry.contains("nonisolated static func splitCollectionPath(_ path: String) -> (base: String, id: String, leaf: String)? {")
        && registry.contains("first < last else { return nil }")
        && registry.contains("guard !base.isEmpty, !id.isEmpty, !leaf.isEmpty else { return nil }"))
check("resolveField matches by leaf",
      registry.contains("return coll.fields(for: id).first { Self.leaf(of: $0.path) == leaf }"))
check("message texts match the port",
      registry.contains("return \"No topic '\\(base)'. Run `minis-config list-topics`.\"")
        && registry.contains("names an entry, not a field. Append a field, e.g. \\(base).\\(rest).\\(entryLeaves[0]). Fields: ")
        && registry.contains("\"Unknown field '\\(leaf)' for \\(base) entry '\\(id)'. Fields: ")
        && registry.contains("and use an entry_id verbatim — ids may contain dots and slashes, no escaping needed: \\(base).<entry_id>.<field>."))
check("both bridge unknown_path exits use the explanation",
      bridge.components(separatedBy: "ConfigRegistry.shared.explainUnknownPath(").count - 1 == 2)
check("models.remove pre-check also accepts a legacy id",
      bridge.contains("|| !collection.fields(for: childId).isEmpty else {"))
check("ModelsCollection paths carry the raw id",
      models.components(separatedBy: "path: \"models.\\(id).").count - 1 == 12
        && !models.contains("pathSegment"))
check("childIds and add() publish raw ids",
      models.contains("modelEntries.map(\\.id)") && models.contains("        return entry.id\n"))
check("lookup: raw first, legacy ~d second",
      models.contains("if let e = entry(idOrSegment) { return e }")
        && models.contains("let decoded = Self.decodeLegacySegment(idOrSegment)"))
check("fields(for:) and remove(id:) go through lookup",
      models.contains("guard let entry = lookup(idOrSegment) else { return [] }")
        && models.contains("guard let entry = lookup(idOrSegment) else {\n            throw ConfigError.unknownPath"))
check("--help documents raw entry_id paths",
      objc.contains("\"PATHS:\\n\"") && objc.contains("get models.<uuid>/glm-5.1.contextWindow"))
check("topic-help carries a path note for collections",
      objc.contains("payload[@\"path_note\"] = pathNote;") && bridge.contains("@objc public static func pathNoteForTopic("))

print("\n▶️  5. every collection field has a one-segment leaf")
// splitCollectionPath takes the field from the LAST dot, so a leaf like
// "modality.video" would be misread as id "…modality", leaf "video". Any
// `path: "<base>.\(…).<leaf>"` template must end in a single segment.
do {
    let dir = root.appendingPathComponent("Shared/Config/Collections")
    let files = (try? FileManager.default.contentsOfDirectory(atPath: dir.path))?.filter { $0.hasSuffix(".swift") } ?? []
    let rx = try! NSRegularExpression(pattern: #"path: "([a-z]+\.\\\(.*\))(\.[^"]*)""#)
    var templates = 0
    var bad: [String] = []
    for f in files {
        let text = (try? String(contentsOf: dir.appendingPathComponent(f), encoding: .utf8)) ?? ""
        for m in rx.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            templates += 1
            let tail = String(text[Range(m.range(at: 2), in: text)!])
            if tail.dropFirst().contains(".") || tail.count < 2 { bad.append("\(f): \(tail)") }
        }
    }
    check("collection path templates found (\(templates))", templates >= 30)
    check("no leaf contains a dot\(bad.isEmpty ? "" : ": \(bad)")", bad.isEmpty)
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
