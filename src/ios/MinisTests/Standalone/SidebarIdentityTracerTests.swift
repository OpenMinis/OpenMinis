// Tests for [T-ios-foreach-lazyedits-dup-guard] — the sidebar identity tracer.
//
// This is INSTRUMENTATION, not a fix. The `ForEachState.LazyEdits` crash
// (EXC_BAD_ACCESS / KERN_PROTECTION_FAILURE at 0x24, CODESIGNING/Invalid Page)
// has struck three times with ZERO app frames in the stack, and the one
// structural guess already tried — duplicate session ids — was falsified when
// the crash recurred on a build that shipped the dedup guard. So instead of
// guessing a fourth time, the tracer records the identity sequence ForEach is
// handed, and logs only the TRANSITIONS (which is when an edit list is actually
// produced, i.e. the only passes that can fault this way).
//
// What the tests pin down: the transition classification is correct (a pure
// reorder must be reported as a reorder, since an index-keyed reorder is what
// c86add406 actually crashed on), and the tracer is silent when nothing moved —
// the sidebar body re-evaluates on every activity tick and under a display-link
// driven render, so an un-gated log would flood and would itself drag the path
// being diagnosed.
//
// Standalone (`swift SidebarIdentityTracerTests.swift`) like its neighbours:
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

// MARK: - Model of the tracer's classification (mirrors ContentView.swift)

struct Transition: Equatable {
    var sectionsRemoved: Set<String> = []
    var sectionsInserted: Set<String> = []
    var rowsRemoved: Set<String> = []
    var rowsInserted: Set<String> = []
    var sectionsReordered = false
    var rowsReordered = false
    var duplicateRows = false
    /// nil = nothing changed, so the tracer stays silent.
    var silent = false
}

func classify(old: (sections: [String], rows: [String]),
              new: (sections: [String], rows: [String])) -> Transition {
    guard old.sections != new.sections || old.rows != new.rows else {
        return Transition(silent: true)
    }
    let oldS = Set(old.sections), newS = Set(new.sections)
    let oldR = Set(old.rows), newR = Set(new.rows)
    var t = Transition()
    t.sectionsRemoved = oldS.subtracting(newS)
    t.sectionsInserted = newS.subtracting(oldS)
    t.rowsRemoved = oldR.subtracting(newR)
    t.rowsInserted = newR.subtracting(oldR)
    t.sectionsReordered = t.sectionsRemoved.isEmpty && t.sectionsInserted.isEmpty
        && old.sections != new.sections
    t.rowsReordered = t.rowsRemoved.isEmpty && t.rowsInserted.isEmpty && old.rows != new.rows
    t.duplicateRows = new.rows.count != newR.count
    return t
}

// MARK: - 1. Silence when nothing moved

print("\n▶️  the tracer is silent on an unchanged pass")
let stable = (sections: ["b:Today", "f:abc"], rows: ["s1", "s2", "s3"])
check("identical state produces no log", classify(old: stable, new: stable).silent)
// The sidebar re-evaluates constantly (activity ticks, badge publishes, and in
// the 2026-09-17 report a display-link driven render). If this ever stopped
// being gated it would flood the log and slow the very path being diagnosed.
var noisy = 0
for _ in 0..<1000 where !classify(old: stable, new: stable).silent { noisy += 1 }
checkEq("1000 no-op re-evaluations log nothing", noisy, 0)

// MARK: - 2. The reorder case — the one that historically crashed

print("\n▶️  a pure reorder is reported AS a reorder")
// c86add406: the section ForEach was keyed by array index, so a reorder made
// SwiftUI rebuild sections as remove+insert. Reorders must be visible in the
// log or that class of bug stays invisible.
let beforeReorder = (sections: ["b:Pinned", "b:Today", "f:work"], rows: ["s1", "s2", "s3"])
let afterReorder = (sections: ["b:Today", "f:work", "b:Pinned"], rows: ["s1", "s2", "s3"])
let ro = classify(old: beforeReorder, new: afterReorder)
check("not silent", !ro.silent)
check("flagged as a section reorder", ro.sectionsReordered)
check("nothing reported as removed", ro.sectionsRemoved.isEmpty)
check("nothing reported as inserted", ro.sectionsInserted.isEmpty)
check("rows unaffected", !ro.rowsReordered)

print("\n▶️  a row-level reorder too")
let rowsBefore = (sections: ["b:Today"], rows: ["s1", "s2", "s3"])
let rowsAfter = (sections: ["b:Today"], rows: ["s3", "s1", "s2"])
let rro = classify(old: rowsBefore, new: rowsAfter)
check("flagged as a row reorder", rro.rowsReordered)
check("no spurious removals", rro.rowsRemoved.isEmpty)
check("no spurious insertions", rro.rowsInserted.isEmpty)

// MARK: - 3. Genuine insert / remove

print("\n▶️  real insertions and removals are named")
let t1 = classify(old: (["b:Today"], ["s1", "s2"]),
                  new: (["b:Today", "f:new"], ["s1", "s2", "s9"]))
checkEq("the new section is named", t1.sectionsInserted, ["f:new"])
checkEq("the new row is named", t1.rowsInserted, ["s9"])
check("and it is NOT called a reorder", !t1.sectionsReordered && !t1.rowsReordered)

let t2 = classify(old: (["b:Pinned", "b:Today"], ["s1", "s2", "s3"]),
                  new: (["b:Today"], ["s2", "s3"]))
checkEq("the dropped section is named", t2.sectionsRemoved, ["b:Pinned"])
checkEq("the dropped row is named", t2.rowsRemoved, ["s1"])

print("\n▶️  a session moving between groups is an insert+remove at section level, not a row change")
// Filing a session into a folder: the row identity survives, its section does not.
let moved = classify(old: (["b:Today"], ["s1", "s2"]),
                     new: (["f:work"], ["s1", "s2"]))
checkEq("section removed", moved.sectionsRemoved, ["b:Today"])
checkEq("section inserted", moved.sectionsInserted, ["f:work"])
check("rows are untouched", moved.rowsRemoved.isEmpty && moved.rowsInserted.isEmpty)

// MARK: - 4. Duplicate detection — the falsified hypothesis, still checked

print("\n▶️  a duplicate row identity would be reported unmissably")
// SidebarGroup.ids' setter makes this unreachable, but the guard was already
// falsified once as a CAUSE; if it is ever bypassed, that fact is the whole
// answer and must not be silently swallowed.
let dup = classify(old: (["b:Today"], ["s1"]), new: (["b:Today"], ["s1", "s2", "s1"]))
check("duplicate flagged", dup.duplicateRows)
let clean = classify(old: (["b:Today"], ["s1"]), new: (["b:Today"], ["s1", "s2"]))
check("a clean insert is not flagged", !clean.duplicateRows)

// MARK: - 5. Cost of the gate

print("\n▶️  the no-op gate is cheap")
let bigOld = (sections: (0..<40).map { "b:\($0)" }, rows: (0..<1772).map { "s\($0)" })
let t0 = Date()
for _ in 0..<500 { _ = classify(old: bigOld, new: bigOld) }
let dt = Date().timeIntervalSince(t0)
print("  ⏱  500 × (40 sections, 1772 rows) no-op: \(String(format: "%.3f", dt))s")
check("array compare short-circuits fast enough for a body pass", dt < 1.0)

// MARK: - 6. Source invariants

print("\n▶️  source invariants")
let path = "../../Views/ContentView.swift"
guard let src = try? String(contentsOfFile: path, encoding: .utf8) else {
    print("  ❌ could not read \(path)"); failures += 1; exit(1)
}

check("the tracer exists", src.contains("enum SidebarIdentityTracer"))
check("both call sites report", src.contains("site: \"sidebar\"") && src.contains("site: \"selection\""))

// It MUST be DEBUG-only: this runs inside the hot sidebar body, and the
// 2026-09-17 crash was display-link driven, i.e. every frame.
check("the tracer type is DEBUG-gated",
      src.contains("#if DEBUG\nenum SidebarIdentityTracer") || src.contains("#if DEBUG\n/// Records"))
let sidebarCall = src.range(of: "SidebarIdentityTracer.note(groups, site: \"sidebar\")")
if let r = sidebarCall {
    let before = src[src.index(r.lowerBound, offsetBy: -220, limitedBy: src.startIndex)! ..< r.lowerBound]
    check("the sidebar call site is #if DEBUG wrapped", before.contains("#if DEBUG"))
    // `let _ =` keeps the ViewBuilder's result type identical — verified
    // separately against the compiler — so the view tree shape is unchanged.
    check("it is discarded via `let _ =` (no view inserted)", before.contains("let _ ="))
} else {
    check("found the sidebar call site", false)
}

// Instrumentation must not have disturbed the fixes this family already has.
check("c86add406's section keying is intact",
      src.contains("ForEach(Array(groups.enumerated()), id: \\.element.id)"))
check("the dedup guard is still present (now belt-and-braces)",
      src.contains("dedupedPreservingOrder"))

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All sidebar identity tracer tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
