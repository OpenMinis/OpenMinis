#!/usr/bin/env swift
// [T-agent-callback-card-width] On iPad the agent-callback card did not fill the
// reply column: it sat 16pt in on each side compared with the delegate-tool card
// that launched the very sub-agent it was reporting back from.
//
// Both cards are capped by the same `maxContentWidth` (AIChatView: 900 in the
// regular size class, nil in compact). The difference was WHERE the 16pt inset
// landed relative to that cap:
//
//   assistant cell (delegate card)   CollectionViewMessageListV3 :344-346
//       .frame(maxWidth: 900) -> .frame(maxWidth: .infinity) -> .padding(16)
//       inset applied AFTER the cap  => card fills 900
//
//   whole-message cell (callback)    :738-740, before this fix
//       .frame(maxWidth: 900) -> .frame(maxWidth: .infinity)
//       inset came from AgentCallbackCellView's own .padding(16), INSIDE the cap
//       => card measures 868
//
// Compact has no cap, so both forms resolve to `screen - 32` and the gap only
// ever showed on iPad / landscape — which is why it survived so long.
//
// [T-ios-wholemessage-leading] had already diagnosed this exact arithmetic
// ("the content measures 868 and gets centred in the 900 column") but only
// fixed the centring half by adding `alignment: .leading`; the missing 32pt
// stayed. This pins both halves.
//
// Run: swift AgentCallbackCardWidthTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator. The geometry is modelled
// below; section [4] re-reads the shipping source so a rewrite fails here
// rather than silently passing a stale copy.
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

// MARK: - Geometry model

let inset: CGFloat = 16
/// AIChatView.maxContentWidth — 900 in regular, nil (uncapped) in compact.
func contentCap(regular: Bool) -> CGFloat? { regular ? 900 : nil }

/// Inset applied AFTER the cap: the card gets the whole capped column.
func cardWidthPaddedOutsideCap(cvWidth: CGFloat, cap: CGFloat?) -> CGFloat {
    let column = max(0, cvWidth - inset * 2)
    return min(cap ?? column, column)
}

/// Inset applied INSIDE the cap (the pre-fix callback shape): the card loses
/// 32pt that the cap has already accounted for.
func cardWidthPaddedInsideCap(cvWidth: CGFloat, cap: CGFloat?) -> CGFloat {
    let column = min(cap ?? cvWidth, cvWidth)
    return max(0, column - inset * 2)
}

print("▶️  1. iPad (regular, cap 900): the two cards now match")
do {
    let cv: CGFloat = 1024
    let cap = contentCap(regular: true)
    let delegateCard = cardWidthPaddedOutsideCap(cvWidth: cv, cap: cap)
    let callbackCard = cardWidthPaddedOutsideCap(cvWidth: cv, cap: cap)   // post-fix
    checkEq("delegate card fills the capped column", delegateCard, 900)
    checkEq("callback card now matches it", callbackCard, 900)
    checkEq("no gap", delegateCard - callbackCard, 0)
}

print("\n▶️  2. the bug this replaces: 868 vs 900")
do {
    let cv: CGFloat = 1024
    let cap = contentCap(regular: true)
    let delegateCard = cardWidthPaddedOutsideCap(cvWidth: cv, cap: cap)
    let preFixCallback = cardWidthPaddedInsideCap(cvWidth: cv, cap: cap)
    checkEq("PRE-FIX the callback measured 868", preFixCallback, 868)
    checkEq("…a 32pt deficit, 16 per side", delegateCard - preFixCallback, 32)
    // The number 868 is quoted verbatim in the T-ios-wholemessage-leading
    // comment; if this model ever stops producing it, the model is wrong.
    check("…which is the number the shipping comment records", preFixCallback == 868)
}

print("\n▶️  3. iPhone portrait (compact, no cap): both forms were always equal")
do {
    // This is why the bug never showed on a phone — and why a fix must not
    // change compact behaviour at all.
    let cv: CGFloat = 414
    let cap = contentCap(regular: false)
    let outside = cardWidthPaddedOutsideCap(cvWidth: cv, cap: cap)
    let inside = cardWidthPaddedInsideCap(cvWidth: cv, cap: cap)
    checkEq("padded-outside = screen - 32", outside, 382)
    checkEq("padded-inside is identical in compact", inside, 382)
    checkEq("so the fix is a no-op on iPhone portrait", outside - inside, 0)
}

print("\n▶️  4. shipping source carries the fix")
do {
    let cell = codeOnly(source("Agent/MessageList/CollectionViewMessageListV3.swift"))
    let card = codeOnly(source("Views/Chat/AgentCallbackCellView.swift"))
    if cell.isEmpty || card.isEmpty { print("  ⏭  sources not readable") } else {
        // The inset is now a named constant owned by the card…
        check("the card exposes its inset as a constant",
              card.contains("static let horizontalInset: CGFloat = 16"))
        // …and the card no longer applies it to itself.
        check("…and no longer pads itself horizontally",
              !card.contains(".padding(.horizontal, 16)"))

        // The cell applies it AFTER both frames — order is the whole fix.
        let padLine = ".padding(.horizontal, message.agentCallback != nil ? AgentCallbackCellView.horizontalInset : 0)"
        check("the cell applies the inset", cell.contains(padLine))

        // Scope the ordering check to THIS cell's body. The cap pattern occurs
        // three times in the file (header, block, footer wrappers all use it),
        // so searching the whole file finds an earlier one and the comparison
        // is vacuously true — an earlier draft of this test made exactly that
        // mistake and passed while the padding sat before the cap.
        let body: String = {
            guard let start = cell.range(of: "private struct BridgedWholeMessageV3") else { return "" }
            let rest = cell[start.lowerBound...]
            guard let end = rest.range(of: "\n}") else { return String(rest) }
            return String(rest[rest.startIndex..<end.upperBound])
        }()
        check("the cell's body was located", !body.isEmpty)
        let capIdx = body.range(of: ".frame(maxWidth: .infinity)")
        let padIdx = body.range(of: padLine)
        check("…AFTER the content cap, not before",
              (capIdx?.upperBound).flatMap { c in (padIdx?.lowerBound).map { $0 >= c } } ?? false)
        checkEq("…and the inset appears exactly once in that body",
                body.components(separatedBy: padLine).count - 1, 1)

        // Scoped to the callback: user bubbles keep their own padding, because
        // their trailing look and the height precalc both assume it.
        check("user bubbles are excluded from the cell-level inset",
              cell.contains("message.agentCallback != nil ?"))
        let views = codeOnly(source("Views/Chat/ChatMessageViews.swift"))
        check("…and userRow still pads itself", views.contains(".padding(.horizontal, 16)"))

        // The leading alignment from the earlier half-fix must survive — without
        // it the card would sit centred in the column again. Scoped to this
        // cell's body for the same reason the ordering check is: the pattern
        // also occurs in the header / block / footer wrappers.
        check("the leading alignment is still there",
              body.contains(".frame(maxWidth: maxWidth > 0 ? maxWidth : nil, alignment: .leading)"))

        // Height is precalculated from a constant, so the width change must not
        // have disturbed it.
        check("the fixed row height is unchanged",
              card.contains("static let cardHeight: CGFloat = 56")
              && card.contains("static let rowHeight: CGFloat = cardHeight + 8"))
    }
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
