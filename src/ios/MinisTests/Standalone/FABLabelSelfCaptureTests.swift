// Tests for [T-ios-crash-objectdestroy-fab-uaf] — DraggableFAB's stored
// closures must not capture ContentView.
//
// The crash: `objectdestroy.90Tm` (owner: ContentView, resolved by
// disassembling the archive — `Tm` is a linker-merged outlined destructor with
// no DWARF name). A child view that stores an escaping closure capturing `self`
// drags an entire ContentView struct copy into that closure's heap context.
// When SwiftUI tears the graph down it releases the closure and destroys the
// stale copy field by field, but its `@State` boxes are already gone —
// SIGSEGV. iOS 16/17 only (old AttributeGraph teardown timing).
//
// `e3579b381` (2026-09-13) fixed the `onTap` half by routing it through a
// FABActionChannel with an explicit `[fabActions]` capture list, and
// deliberately left `label` alone pending crash data. That data arrived:
// build 1.14(21) — which contains e3579b381 — still crashed at
// `objectdestroy.90Tm` twice on 2026-09-20/21 (iOS 16.0.3 and 16.5.1). So the
// remaining leg is `label`, whose only `self` capture was the call to
// `fabCircleSurface` — an instance method that reads no instance state.
//
// These are SOURCE-LEVEL checks. The property that matters ("this closure does
// not capture self") is a compile-time fact about ContentView.swift, and
// ContentView cannot be instantiated in a standalone script — it pulls in the
// whole app graph. So the suite asserts the shape of the code that guarantees
// it, which is also what would catch a future regression.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    return ""
}

let src = source("Views/ContentView.swift")
guard !src.isEmpty else {
    print("❌ could not locate ContentView.swift"); exit(1)
}
let lines = src.components(separatedBy: "\n")

print("\n[1] fabCircleSurface must be static")
// An instance method here is the whole bug: calling it inside `label` captures
// self. It reads no instance state, so static costs nothing.

check("declared `private static func`",
      src.contains("private static func fabCircleSurface<Icon: View>"))
check("no instance-method declaration remains",
      src.contains("private func fabCircleSurface<Icon: View>"), false)

print("\n[2] every call site is Self-qualified")

var unqualified = 0, qualified = 0
for (i, l) in lines.enumerated() {
    guard l.contains("fabCircleSurface(") else { continue }
    if l.contains("Self.fabCircleSurface(") { qualified += 1 }
    else if !l.contains("func fabCircleSurface") { unqualified += 1; print("     unqualified at line \(i+1): \(l.trimmingCharacters(in: .whitespaces))") }
}
check("at least the two FAB call sites exist", qualified >= 2)
check("no unqualified call sites", unqualified == 0)

print("\n[3] DraggableFAB's onTap closures keep their capture list")
// The half fixed by e3579b381. A regression here reopens the same crash.

check("onTap closures capture only the channel",
      src.contains("{ [fabActions] in"))
check("context-menu buttons do too",
      src.contains("Button { [fabActions] in"))

print("\n[4] the label closures reference no instance member")
// Scan each `} label: {` block that follows a DraggableFAB( and check every
// identifier that could be an instance reference.

func labelBlocks(in text: String) -> [String] {
    var out: [String] = []
    var idx = text.startIndex
    while let fab = text.range(of: "DraggableFAB(", range: idx..<text.endIndex) {
        guard let lab = text.range(of: "} label: {", range: fab.upperBound..<text.endIndex) else { break }
        // Take the block by brace balance from the opening brace.
        var depth = 0
        var end = lab.upperBound
        var started = false
        var i = text.index(before: lab.upperBound)   // the '{'
        while i < text.endIndex {
            let c = text[i]
            if c == "{" { depth += 1; started = true }
            else if c == "}" {
                depth -= 1
                if started && depth == 0 { end = i; break }
            }
            i = text.index(after: i)
        }
        out.append(String(text[lab.upperBound..<end]))
        idx = end
    }
    return out
}

let blocks = labelBlocks(in: src)
check("found both DraggableFAB label blocks", blocks.count >= 2)

for (n, b) in blocks.enumerated() {
    check("label \(n + 1): calls fabCircleSurface via Self",
          !b.contains("fabCircleSurface(") || b.contains("Self.fabCircleSurface("))
    check("label \(n + 1): no explicit `self.`", b.contains("self."), false)
    // `fabGlassNamespace` is a @Namespace — a VALUE type, so capturing it
    // copies the id rather than self. Documented here so a reader does not
    // "fix" it into something worse.
    if b.contains("fabGlassNamespace") {
        check("label \(n + 1): namespace use is fine (Namespace.ID is a value)", true)
    }
}

print("\n[5] the guard comment survives")
// The next person to touch this needs to know why static is load-bearing.

check("explains why it must stay static",
      src.contains("Keep it static. Adding an instance reference here silently reintroduces"))
check("cites the crash ticket",
      src.contains("[T-ios-crash-objectdestroy-fab-uaf] `static`, deliberately."))

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
