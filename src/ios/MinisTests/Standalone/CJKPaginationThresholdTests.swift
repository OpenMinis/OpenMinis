// Regression test for the 2026-08-20 0x8BADF00D watchdog kill (crash
// 6B7E545B) — CJK-heavy markdown previews must paginate at a LOWER
// character threshold than Latin ones.
//
// Standalone (`swift CJKPaginationThresholdTests.swift`) because the
// MinisTests target has a pre-existing compile break — same rationale and
// directory as SkillDescriptionStaleTests / InLoopCompactOrderTests.
//
// The two functions below are copied VERBATIM from
// PaginatedMarkdownView.swift so this pins the shipped logic, not a
// paraphrase of it.

import Foundation

let paginationThreshold: Int = 50_000
let cjkPaginationThreshold: Int = 20_000
let cjkRatioTrigger: Double = 0.30

func cjkRatio(of text: String) -> Double {
    guard !text.isEmpty else { return 0 }
    var cjk = 0
    var total = 0
    for scalar in text.unicodeScalars {
        total += 1
        let v = scalar.value
        if (0x4E00...0x9FFF).contains(v)
            || (0x3400...0x4DBF).contains(v)
            || (0x3000...0x303F).contains(v)
            || (0x3040...0x309F).contains(v)
            || (0x30A0...0x30FF).contains(v)
            || (0xAC00...0xD7AF).contains(v)
            || (0xFF00...0xFFEF).contains(v)
        {
            cjk += 1
        }
    }
    return total == 0 ? 0 : Double(cjk) / Double(total)
}

func effectiveThreshold(for text: String) -> Int {
    cjkRatio(of: text) > cjkRatioTrigger ? cjkPaginationThreshold : paginationThreshold
}

/// What the view will actually do for this document.
enum Path: String { case single = "single-shot", paginated = "paginated" }
func path(for text: String) -> Path {
    text.count <= effectiveThreshold(for: text) ? .single : .paginated
}

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkPath(_ label: String, _ text: String, _ want: Path) {
    let got = path(for: text)
    let pct = String(format: "%.0f%%", cjkRatio(of: text) * 100)
    if got == want { print("  ✅ \(label)  [chars=\(text.count) cjk=\(pct) → \(got.rawValue)]") }
    else { print("  ❌ \(label) — expected \(want.rawValue), got \(got.rawValue) [chars=\(text.count) cjk=\(pct)]"); failures += 1 }
}

// Builders. Each returns a string of a KNOWN character count.
func chinese(_ n: Int) -> String { String(repeating: "中", count: n) }
func latin(_ n: Int) -> String { String(repeating: "a", count: n) }
/// Chinese prose carrying the usual markdown scaffolding + CJK punctuation.
func mixedZh(_ n: Int) -> String {
    // "## 标题\n\n这是一段中文内容，用于测试。\n\n" ≈ 30% syntax / 70% CJK
    let unit = "## 标题\n\n这是一段中文内容，用于测试。\n\n"
    var s = ""
    while s.count < n { s += unit }
    return String(s.prefix(n))
}

print("CJK ratio detection")
check("pure Chinese > trigger", cjkRatio(of: chinese(100)) > cjkRatioTrigger, true)
check("pure Latin ≤ trigger", cjkRatio(of: latin(100)) > cjkRatioTrigger, false)
check("markdown-scaffolded Chinese > trigger", cjkRatio(of: mixedZh(1000)) > cjkRatioTrigger, true)
check("empty string is 0", cjkRatio(of: "") == 0, true)
check("Japanese kana > trigger", cjkRatio(of: String(repeating: "あア", count: 50)) > cjkRatioTrigger, true)
check("Korean hangul > trigger", cjkRatio(of: String(repeating: "한글", count: 50)) > cjkRatioTrigger, true)
check("CJK punctuation counts", cjkRatio(of: "、。「」（）") > cjkRatioTrigger, true)
// 10% Chinese inside mostly-Latin prose must NOT trip the lower threshold.
check("10% Chinese ≤ trigger", cjkRatio(of: latin(900) + chinese(100)) > cjkRatioTrigger, false)

print("\nThe reported crash case: 40K Chinese now paginates")
checkPath("40K pure Chinese", chinese(40_000), .paginated)          // was single-shot before the fix
checkPath("40K Latin stays single-shot", latin(40_000), .single)    // unchanged

print("\nBoundary case 1 — <20K all-Chinese must STILL be single-shot (no regression)")
checkPath("5K Chinese", chinese(5_000), .single)
checkPath("19,999 Chinese", chinese(19_999), .single)
checkPath("exactly 20,000 Chinese (≤ is single)", chinese(20_000), .single)
checkPath("20,001 Chinese", chinese(20_001), .paginated)

print("\nBoundary case 2 — >50K any language must STILL paginate (no regression)")
checkPath("50,001 Latin", latin(50_001), .paginated)
checkPath("60K Latin", latin(60_000), .paginated)
checkPath("60K Chinese", chinese(60_000), .paginated)
checkPath("exactly 50,000 Latin (≤ is single)", latin(50_000), .single)

print("\nLatin path is untouched between the two thresholds")
checkPath("30K Latin (between 20K and 50K)", latin(30_000), .single)
checkPath("30K markdown-scaffolded Chinese", mixedZh(30_000), .paginated)

print("\nEdge")
checkPath("empty document", "", .single)

print(failures == 0 ? "\n✅ all checks passed" : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
