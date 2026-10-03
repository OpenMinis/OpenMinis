// Tests for [T-codeblock-hide-idle-scrollbars] — a markdown code block must
// not show scrollbars, bounce, or steal the drag when its content fits.
//
// Reported symptom: dragging the message list felt like it caught / would not
// move, most obviously on a SINGLE-LINE code block. A one-line snippet is
// ~17pt of content inside a box that reserves up to 376pt, so there is nothing
// to scroll — yet the block's UIScrollView was created with both indicators and
// both bounces on unconditionally, its pan recognizer claimed the touch, and
// the list only started moving once the finger left the block.
//
// The fix has three parts, all keyed off "can this axis actually scroll?":
//   1. indicators + bounce per axis
//   2. `isScrollEnabled = false` when NEITHER axis can move, so the recognizer
//      leaves gesture arbitration entirely
//   3. the outer text view's `gestureRecognizerShouldBegin` yields the pan only
//      on an axis the block can really scroll
//
// Part 3 also repaired dead code: it used to test
// `findCodeTextView(...).isScrollEnabled`, but that inner UITextView is built
// with `isScrollEnabled = false` on purpose ("scrollView handles scrolling"),
// so the condition was never true and the outer view never yielded at all.
//
// Standalone (`swift CodeBlockScrollabilityTests.swift`) like its neighbours:
// the MinisTests target has a pre-existing compile break and these types pull
// in UIKit + the whole renderer.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Reproduced from CodeBlockAttachment

/// Layout constants as they appear in SelectableMarkdownView.
let kBoxCap = 400.0          // total code block height cap
let kBottomPad = 12.0
func topOffset(hasLanguage: Bool) -> Double { hasLanguage ? 28 : 12 }
func maxCodeHeight(hasLanguage: Bool) -> Double { kBoxCap - topOffset(hasLanguage: hasLanguage) - kBottomPad }

struct ScrollView {
    var contentW = 0.0, contentH = 0.0
    var boundsW = 0.0, boundsH = 0.0
    var insetBottom = kBottomPad
    var showsV = true, showsH = true          // shipped defaults: both ON
    var bounceV = true, bounceH = true        // shipped defaults: both ON
    var scrollEnabled = true
}

/// Mirrors `syncScrollability`.
func syncScrollability(_ s: inout ScrollView, scrollHeight: Double, visibleWidth: Double) {
    let canV = s.contentH > scrollHeight + 0.5
    let canH = s.contentW > visibleWidth + 0.5
    s.showsV = canV; s.showsH = canH
    s.bounceV = canV; s.bounceH = canH
    s.scrollEnabled = canV || canH
}

/// Mirrors the yield decision in `gestureRecognizerShouldBegin`.
/// Returns true when the OUTER list gives the pan to the code block.
func outerYieldsPan(_ s: ScrollView, horizontalSwipe: Bool) -> Bool {
    let usableH = s.boundsH - s.insetBottom
    let canH = s.contentW > s.boundsW + 0.5
    let canV = s.contentH > usableH + 0.5
    return horizontalSwipe ? canH : canV
}

/// Build a block the way makeView does, for `lines` of code at the default font
/// (Menlo at 16.5*0.85 ≈ 14pt → lineHeight 17, lineSpacing 4).
func block(lines: Int, longestLineWidth: Double, hasLanguage: Bool = false,
           viewWidth: Double = 340) -> (ScrollView, Double, Double) {
    let lh = 17.0, spacing = 4.0
    let contentH = Double(lines) * lh + Double(max(0, lines - 1)) * spacing
    let scrollH = min(contentH, maxCodeHeight(hasLanguage: hasLanguage))
    var s = ScrollView(contentW: longestLineWidth, contentH: contentH,
                       boundsW: viewWidth, boundsH: scrollH + kBottomPad)
    syncScrollability(&s, scrollHeight: scrollH, visibleWidth: viewWidth)
    return (s, scrollH, viewWidth)
}

print("\n[1] The reported case — a single-line code block")

let (one, _, _) = block(lines: 1, longestLineWidth: 120)
check("no vertical scrollbar", one.showsV, false)
check("no horizontal scrollbar", one.showsH, false)
check("no vertical bounce", one.bounceV, false)
check("scrolling disabled entirely (recognizer leaves arbitration)", one.scrollEnabled, false)
check("a vertical drag is NOT taken by the block", outerYieldsPan(one, horizontalSwipe: false), false)
check("a horizontal drag is NOT taken either", outerYieldsPan(one, horizontalSwipe: true), false)

print("\n[2] A single line that IS too wide — horizontal only")

let (wide, _, _) = block(lines: 1, longestLineWidth: 900)
check("horizontal scrollbar shown", wide.showsH)
check("vertical scrollbar still hidden", wide.showsV, false)
check("scrolling stays enabled", wide.scrollEnabled)
check("horizontal drag goes to the block", outerYieldsPan(wide, horizontalSwipe: true))
check("vertical drag still belongs to the list", outerYieldsPan(wide, horizontalSwipe: false), false)

print("\n[3] A long block — vertical scrolling must still work")

let (tall, _, _) = block(lines: 60, longestLineWidth: 300)
check("vertical scrollbar shown", tall.showsV)
check("scrolling enabled", tall.scrollEnabled)
check("vertical drag goes to the block", outerYieldsPan(tall, horizontalSwipe: false))

print("\n[4] Several short blocks — the everyday case")

for n in [2, 3, 5, 10] {
    let (b, _, _) = block(lines: n, longestLineWidth: 200)
    check("\(n)-line block: no scrollbars, no gesture steal",
          !b.showsV && !b.showsH && !b.scrollEnabled
            && !outerYieldsPan(b, horizontalSwipe: false))
}

print("\n[5] Boundary — exactly filling the visible height")

// 17 lines at the default font ≈ 17*17 + 16*4 = 353pt, inside the 376pt cap.
let (fits, _, _) = block(lines: 17, longestLineWidth: 300)
check("17 lines still fit → no scrollbar", fits.showsV, false)
// 19 lines ≈ 395pt, past the cap.
let (spills, _, _) = block(lines: 19, longestLineWidth: 300)
check("19 lines spill → scrollbar appears", spills.showsV)

print("\n[6] Regression — usable height must exclude the bottom inset")
// The frame is scrollHeight + bottomPadding with a matching contentInset.bottom.
// Measuring against the FRAME would treat that padding as usable and declare a
// clipped block scroll-free.
var naive = ScrollView(contentW: 100, contentH: 380, boundsW: 340, boundsH: 376 + kBottomPad)
let wrongCanV = naive.contentH > naive.boundsH + 0.5              // uses frame height
let rightCanV = naive.contentH > (naive.boundsH - naive.insetBottom) + 0.5
check("frame-height test wrongly says 'fits'", wrongCanV, false)
check("inset-aware test correctly says 'scrolls'", rightCanV)
syncScrollability(&naive, scrollHeight: 376, visibleWidth: 340)
check("shipped helper agrees with the inset-aware test", naive.showsV)

print("\n[7] Anti-drift — re-read the shipping source")

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    print("  ⚠️  could not locate \(rel) — skipping drift checks")
    return ""
}

let src = source("Views/Chat/SelectableMarkdownView.swift")
if !src.isEmpty {
    check("syncScrollability exists", src.contains("private static func syncScrollability("))
    check("it disables scrolling when neither axis moves",
          src.contains("scrollView.isScrollEnabled = canScrollV || canScrollH"))
    check("makeView calls it", src.contains("Self.syncScrollability(scrollView,"))
    check("the streaming update path calls it too",
          src.components(separatedBy: "Self.syncScrollability(").count - 1 >= 2)
    check("the pan guard uses the SCROLL VIEW, not the text view",
          src.contains("findCodeScrollView(in: subview)"))
    check("the dead isScrollEnabled-on-UITextView guard is gone",
          src.contains("findCodeTextView(in: subview), codeTV.isScrollEnabled"), false)
    check("the pan guard accounts for contentInset.bottom",
          src.contains("codeScroll.bounds.height - codeScroll.contentInset.bottom"))
}

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
