// Tests for [T-ios-foreach-lazyedits-dup-guard] — SidebarGroup.ids never hands
// SwiftUI two rows with the same identity.
//
// DEFENSIVE, NOT A DIAGNOSED FIX. The 2026-09-17 crash (EXC_BAD_ACCESS /
// KERN_PROTECTION_FAILURE at 0x24 inside `assignWithTake for
// ForEachState.LazyEdits`) carries NO app frames — frames 0-39 are libswiftCore
// / SwiftUICore / AttributeGraph / UIKitCore, and the only Minis frames are
// `MinisApp.$main()` and `main`. The audit could not find a reachable path to a
// duplicate id. This guard removes one specific, known-fatal input shape; it
// does not claim to have found the cause.
//
// What the guard must NOT do is change the list in any other way — the sidebar
// order is load-bearing (pinned-first partition, recency within a group), and a
// dedup that reordered rows would trade a rare crash for permanent visible
// breakage.
//
// Standalone (`swift SidebarGroupDedupTests.swift`) like its neighbours:
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

// MARK: - Under test (mirrors SidebarGroup in ContentView.swift)

struct SidebarGroup: Equatable {
    var label: String
    var ids: [String] {
        get { _ids }
        set { _ids = Self.dedupedPreservingOrder(newValue) }
    }
    private var _ids: [String] = []
    var folderId: String? = nil

    init(label: String, ids: [String], folderId: String? = nil) {
        self.label = label
        self.folderId = folderId
        self.ids = ids
    }

    static func dedupedPreservingOrder(_ input: [String]) -> [String] {
        var seen = Set<String>(minimumCapacity: input.count)
        var hasDuplicate = false
        for id in input where !seen.insert(id).inserted {
            hasDuplicate = true
            break
        }
        guard hasDuplicate else { return input }

        seen.removeAll(keepingCapacity: true)
        var out: [String] = []
        out.reserveCapacity(input.count)
        for id in input where seen.insert(id).inserted {
            out.append(id)
        }
        return out
    }
}

// MARK: - 1. The invariant

print("\n▶️  ids never contains a duplicate, whatever is written")

let cases: [(String, [String], [String])] = [
    ("already unique — untouched", ["a", "b", "c"], ["a", "b", "c"]),
    ("empty", [], []),
    ("single", ["a"], ["a"]),
    ("adjacent duplicate", ["a", "a", "b"], ["a", "b"]),
    ("separated duplicate", ["a", "b", "a"], ["a", "b"]),
    ("all the same", ["a", "a", "a", "a"], ["a"]),
    ("duplicate at the end", ["a", "b", "c", "c"], ["a", "b", "c"]),
    ("several distinct duplicates", ["a", "b", "a", "c", "b", "d"], ["a", "b", "c", "d"]),
    ("first occurrence wins (order preserved)", ["z", "y", "z", "x"], ["z", "y", "x"]),
]
for (name, input, expected) in cases {
    checkEq(name, SidebarGroup(label: "L", ids: input).ids, expected)
}

print("\n▶️  every write path goes through the guard")
var g = SidebarGroup(label: "Today", ids: ["a", "a", "b"])
checkEq("the initializer dedups", g.ids, ["a", "b"])
g.ids = ["c", "c", "d", "c"]
checkEq("a later assignment dedups too", g.ids, ["c", "d"])
g.ids = []
checkEq("the collapse write (ids = []) still works", g.ids, [])
g.ids = ["e"]
checkEq("and the group is reusable afterwards", g.ids, ["e"])

// MARK: - 2. What it must NOT change
//
// The sidebar's display order is meaningful: folder groups render pinned
// members first, then the rest by recency. A dedup that sorted, reversed or
// otherwise reordered would be a visible regression far worse than the crash
// it guards against.

print("\n▶️  order is never disturbed")
let pinnedFirst = ["pin1", "pin2", "recent1", "recent2", "recent3"]
checkEq("a unique pinned-first list is returned verbatim",
        SidebarGroup(label: "Folder", ids: pinnedFirst).ids, pinnedFirst)

// Identity, not just equality: a unique input must come back as the SAME array
// (the fast path returns `input` untouched), so the hot aggregation pass pays
// nothing and SwiftUI's diff sees no change.
let unique = (0..<500).map { "s\($0)" }
let returned = SidebarGroup.dedupedPreservingOrder(unique)
checkEq("unique input is returned unchanged", returned, unique)

// With a duplicate present, surviving entries must keep their relative order.
let withDup = ["pin1", "pin2", "recent1", "pin1", "recent2"]
checkEq("survivors keep relative order",
        SidebarGroup(label: "F", ids: withDup).ids,
        ["pin1", "pin2", "recent1", "recent2"])

print("\n▶️  the dropped row is the LATER one")
// Which copy survives matters: the sidebar's first occurrence carries the
// pinned-first placement, so dropping the later duplicate keeps a pinned
// session in the pinned block rather than demoting it into the recency tail.
let pinnedThenRepeated = ["pinnedSession", "other", "pinnedSession"]
checkEq("the first (pinned-position) copy is the one kept",
        SidebarGroup(label: "F", ids: pinnedThenRepeated).ids,
        ["pinnedSession", "other"])

// MARK: - 3. Cost

print("\n▶️  the common case is cheap")
let big = (0..<5000).map { "session-\($0)" }
let t0 = Date()
for _ in 0..<200 { _ = SidebarGroup.dedupedPreservingOrder(big) }
let dt = Date().timeIntervalSince(t0)
print("  ⏱  200 × 5000 unique ids: \(String(format: "%.3f", dt))s")
check("scan-only fast path stays well under a frame budget", dt < 2.0)

// MARK: - 4. The real source still carries the guard

print("\n▶️  source invariants")
let path = "../../Views/ContentView.swift"
guard let src = try? String(contentsOfFile: path, encoding: .utf8) else {
    print("  ❌ could not read \(path)"); failures += 1; exit(1)
}

check("ids is a computed property over a private backing store",
      src.contains("private var _ids: [String] = []"))
check("its setter dedups", src.contains("_ids = Self.dedupedPreservingOrder(newValue)"))
check("an explicit init exists (the synthesised one cannot see _ids)",
      src.contains("init(label: String, ids: [String], folderId: String? = nil)"))
check("the DEBUG duplicate report is present", src.contains("SidebarDupGuard"))

// The guard is worthless if the rows stop being keyed by the id — that is the
// exact shape it protects. If someone switches these to a compound key the
// guard can be revisited, but it should be a deliberate decision.
check("sidebar rows are still keyed by the session id",
      src.contains("ForEach(group.ids, id: \\.self)"))

// And the August fix this crash family already has must stay put.
check("the section ForEach is still keyed by group id, not array index",
      src.contains("ForEach(Array(groups.enumerated()), id: \\.element.id)"))
check("no section ForEach regressed to \\.offset",
      !src.contains("ForEach(Array(groups.enumerated()), id: \\.offset)"))

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All sidebar dedup guard tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
