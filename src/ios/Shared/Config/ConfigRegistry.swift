import Foundation

/// Single source of truth for every configurable setting in the app.
///
/// Add a new setting in three steps:
///   1. Pick a dot-path id (`appearance.theme`, `browser.uaProfile`, …).
///   2. Construct a `ConfigField` (or `ConfigCollection` for dynamic
///      children) and register it in `ConfigRegistry+Builtins.swift`.
///   3. Done — the offload bridge, confirmation sheet, audit log,
///      revert flow, and `--help` output all derive from this registry.
///
/// Threading: the registry is `@MainActor` because every reader/writer
/// closure may touch UI-bound state. Registration happens once at app
/// launch; mutation after that is rare (only for collections whose
/// child set changes at runtime — those work via `childIds()` not
/// re-registration).
@MainActor
final class ConfigRegistry {
    static let shared = ConfigRegistry()

    private var fields: [String: ConfigField] = [:]
    private var collections: [String: ConfigCollection] = [:]
    private var didRegisterBuiltins = false

    /// Idempotent. Called from `MinisApp.onAppear`. Splitting initial
    /// load from the singleton init avoids touching managers (whose
    /// own init may have side effects) before the app is ready.
    func registerBuiltinsIfNeeded() {
        guard !didRegisterBuiltins else { return }
        didRegisterBuiltins = true
        Self.registerBuiltins(into: self)
    }

    func register(_ field: ConfigField) {
        fields[field.path] = field
    }

    func register(_ collection: ConfigCollection) {
        collections[collection.basePath] = collection
    }

    /// Look up a field by path. Handles both flat fields and collection
    /// children (`<base>.<id>.<leaf>`). Returns nil for unknown paths.
    func resolveField(path: String) -> ConfigField? {
        if let f = fields[path] { return f }
        guard let (base, id, leaf) = Self.splitCollectionPath(path),
              let coll = collections[base] else { return nil }
        // [T-config-path-dotted-id] OpenMinis#390. Match by LEAF, not by the
        // whole path. The collection builds its canonical path from its own
        // spelling of the id (an alias such as subagents' `general`, or the
        // pre-#390 `~d`-escaped model id), which the caller's spelling need not
        // equal — whole-path comparison would turn a correctly split path back
        // into `unknown_path`. Leaves are unique within one child.
        return coll.fields(for: id).first { Self.leaf(of: $0.path) == leaf }
    }

    /// [T-config-path-dotted-id] OpenMinis#390. Split a collection child path
    /// into (base, id, leaf): the topic runs to the FIRST dot, the field starts
    /// after the LAST dot, and everything in between is the entry id — dots and
    /// slashes included.
    ///
    /// Before this, the path was split with `maxSplits: 2` into exactly three
    /// parts, so an id was cut at its first dot: `models.<uuid>/mimo-v2.6-pro
    /// .contextWindow` became id `<uuid>/mimo-v2`, leaf `6-pro.contextWindow`,
    /// and every model id with a dot (most of them: glm-5.1, gpt-4.1, …)
    /// answered `unknown_path`. The earlier workaround escaped dots as `~d`,
    /// which no caller could guess; `get models` prints the raw id.
    ///
    /// Relies on leaves being a single segment, which every collection honours
    /// (guarded by ConfigPathSplitTests). nil when any part would be empty or
    /// there are fewer than two dots (`topic.entry` names an entry, not a
    /// field). Empty segments are NOT collapsed, so `models..x` stays invalid.
    nonisolated static func splitCollectionPath(_ path: String) -> (base: String, id: String, leaf: String)? {
        guard let first = path.firstIndex(of: "."),
              let last = path.lastIndex(of: "."),
              first < last else { return nil }
        let base = String(path[..<first])
        let id = String(path[path.index(after: first)..<last])
        let leaf = String(path[path.index(after: last)...])
        guard !base.isEmpty, !id.isEmpty, !leaf.isEmpty else { return nil }
        return (base, id, leaf)
    }

    /// The part of a field path after its last dot.
    nonisolated static func leaf(of path: String) -> String {
        guard let last = path.lastIndex(of: ".") else { return path }
        return String(path[path.index(after: last)...])
    }

    /// [T-config-path-dotted-id] OpenMinis#390. The `reason` for an
    /// `unknown_path` answer, saying which part was wrong and how to find the
    /// right one. The error code stays `unknown_path`; only the text changes.
    /// Keep the wording in step with Android `ConfigRegistry.explainUnknownPath`.
    func explainUnknownPath(_ path: String) -> String {
        let base = path.firstIndex(of: ".").map { String(path[..<$0]) } ?? path
        let topicKnown = collections[base] != nil
            || fields.values.contains { $0.access != .hidden && ($0.path == base || $0.path.hasPrefix(base + ".")) }
        guard !base.isEmpty, topicKnown else {
            return "No topic '\(base)'. Run `minis-config list-topics`."
        }
        guard let coll = collections[base],
              let dot = path.firstIndex(of: ".") else {
            return "No registered field at '\(path)'."
        }
        let rest = String(path[path.index(after: dot)...])
        func leaves(_ id: String) -> [String] {
            coll.fields(for: id).filter { $0.access != .hidden }.map { Self.leaf(of: $0.path) }
        }
        // The whole remainder is an entry id (dots allowed): no field given.
        let entryLeaves = leaves(rest)
        if !entryLeaves.isEmpty {
            return "'\(path)' names an entry, not a field. Append a field, e.g. \(base).\(rest).\(entryLeaves[0]). Fields: \(entryLeaves.joined(separator: ", "))."
        }
        if let (_, id, leaf) = Self.splitCollectionPath(path) {
            let idLeaves = leaves(id)
            if !idLeaves.isEmpty {
                return "Unknown field '\(leaf)' for \(base) entry '\(id)'. Fields: \(idLeaves.joined(separator: ", "))."
            }
            return Self.noEntryReason(base: base, id: id)
        }
        return Self.noEntryReason(base: base, id: rest)
    }

    nonisolated private static func noEntryReason(base: String, id: String) -> String {
        "No entry '\(id)' under '\(base)'. Run `minis-config get \(base)` and use an entry_id verbatim — ids may contain dots and slashes, no escaping needed: \(base).<entry_id>.<field>."
    }

    func collection(basePath: String) -> ConfigCollection? {
        collections[basePath]
    }

    /// All registered top-level field paths (excluding hidden), used by
    /// `minis-config list-all` and the `--help` topic enumerator.
    func allVisibleFieldPaths() -> [String] {
        fields.values
            .filter { $0.access != .hidden }
            .map { $0.path }
            .sorted()
    }

    /// Topic names = unique first segments of every visible path /
    /// collection base path. Order: alphabetical.
    func topics() -> [String] {
        var set = Set<String>()
        for f in fields.values where f.access != .hidden {
            if let head = f.path.split(separator: ".").first {
                set.insert(String(head))
            }
        }
        for c in collections.values {
            set.insert(c.basePath)
        }
        return set.sorted()
    }

    /// All visible fields whose path equals `<topic>` (the bare topic
    /// name — e.g. an aggregate `providers` read-only summary) or starts
    /// with `<topic>.`. When `topic` matches a registered collection, a
    /// representative child's fields (using the first child id) are also
    /// included so `topic-help <collection>` surfaces the per-child
    /// schema instead of an empty list. Used by the per-topic `--help`
    /// output.
    func fields(forTopic topic: String) -> [ConfigField] {
        var out: [ConfigField] = fields.values.filter {
            $0.access != .hidden
            && ($0.path == topic || $0.path.hasPrefix("\(topic)."))
        }
        if let coll = collections[topic],
           let firstChildId = coll.childIds().first {
            out.append(contentsOf: coll.fields(for: firstChildId)
                .filter { $0.access != .hidden })
        }
        return out.sorted { $0.path < $1.path }
    }
}
