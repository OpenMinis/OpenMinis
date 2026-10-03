// Tests for [T-ios-context-usage-hint] — the composer's context-window usage
// line and ambient glow.
//
// Pins the pure logic (tier thresholds, percent rounding, the shared token
// formatter, locale-independent number-segment location) and greps the source
// for the invariants the feature's safety depends on: the glow never
// hit-tests, its interior is masked away from the glass, the placeholder
// rotation yields to the transient line, and no timer/work item lives in
// SwiftUI @State.
//
// Standalone (`swift ContextUsageHintTests.swift`) like its neighbours:
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

// MARK: - Copies of the pure logic (src/ios/Agent/Chat/ChatModels.swift)

struct ContextUsage: Equatable {
    static let warningFraction: Double = 0.7
    static let criticalFraction: Double = 0.8
    let usedTokens: Int
    let windowTokens: Int
    var fraction: Double { windowTokens > 0 ? Double(usedTokens) / Double(windowTokens) : 0 }
    var percent: Int { Int((fraction * 100).rounded()) }
    enum Tier: Equatable { case normal, warning, critical }
    var tier: Tier {
        if fraction >= Self.criticalFraction { return .critical }
        if fraction >= Self.warningFraction { return .warning }
        return .normal
    }
}
enum TokenCountFormatter {
    static func short(_ count: Int) -> String {
        if count >= 1000 {
            let k = Double(count) / 1000.0
            return k.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(k))k" : String(format: "%.1fk", k)
        }
        return "\(count)"
    }
}
/// The label-side segment locator, same as Coordinator.attributedHint.
func highlightRanges(in text: String, segments: [String]) -> [NSRange] {
    let ns = text as NSString
    return segments.map { ns.range(of: $0) }.filter { $0.location != NSNotFound }
}

print("▶️  tiers and percent")
checkEq("0/200k → normal", ContextUsage(usedTokens: 0, windowTokens: 200_000).tier, .normal)
checkEq("139,999/200k → normal (just under 70%)", ContextUsage(usedTokens: 139_999, windowTokens: 200_000).tier, .normal)
checkEq("120k/200k → normal (60% no longer warns)", ContextUsage(usedTokens: 120_000, windowTokens: 200_000).tier, .normal)
checkEq("140k/200k → warning (exactly 70%)", ContextUsage(usedTokens: 140_000, windowTokens: 200_000).tier, .warning)
checkEq("159,999/200k → warning", ContextUsage(usedTokens: 159_999, windowTokens: 200_000).tier, .warning)
checkEq("160k/200k → critical (exactly 80%)", ContextUsage(usedTokens: 160_000, windowTokens: 200_000).tier, .critical)
checkEq("250k/200k → critical (overflow)", ContextUsage(usedTokens: 250_000, windowTokens: 200_000).tier, .critical)
checkEq("124k/200k → 62%", ContextUsage(usedTokens: 124_000, windowTokens: 200_000).percent, 62)
checkEq("1/200k rounds to 0%", ContextUsage(usedTokens: 1, windowTokens: 200_000).percent, 0)
checkEq("zero window → fraction 0, never divides by zero", ContextUsage(usedTokens: 5, windowTokens: 0).fraction, 0)

print("\n▶️  shared token formatter (must match the footer capsule)")
checkEq("850", TokenCountFormatter.short(850), "850")
checkEq("1000 → 1k", TokenCountFormatter.short(1000), "1k")
checkEq("1234 → 1.2k", TokenCountFormatter.short(1234), "1.2k")
checkEq("124000 → 124k", TokenCountFormatter.short(124_000), "124k")
checkEq("200000 → 200k", TokenCountFormatter.short(200_000), "200k")

print("\n▶️  number segments are found by exact string, in any word order")
let pct = "62%", size = "124k / 200k"
for (locale, template) in [("en", "Context %@ used · %@"), ("zh-Hans", "上下文已用 %1$@ · %2$@"), ("ja", "コンテキスト使用率 %1$@ · %2$@")] {
    let text = String(format: template, pct, size)
    let ranges = highlightRanges(in: text, segments: [pct, size])
    checkEq("\(locale): both segments located", ranges.count, 2)
    let ns = text as NSString
    checkEq("\(locale): first range is the percent", ns.substring(with: ranges[0]), pct)
    checkEq("\(locale): second range is the size", ns.substring(with: ranges[1]), size)
}
// A percent that also appears inside the size string must still resolve to
// the percent segment first (range(of:) is leftmost; the size has no '%').
let tricky = String(format: "Context %@ used · %@", "20%", "20k / 100k")
checkEq("20% is not confused with 20k", (tricky as NSString).substring(with: highlightRanges(in: tricky, segments: ["20%"])[0]), "20%")

// MARK: - Source invariants

print("\n▶️  source invariants")
let root = "../../"
func src(_ p: String) -> String { (try? String(contentsOfFile: root + p, encoding: .utf8)) ?? "" }
let bar = src("Views/Chat/ChatInputBar.swift")
let view = src("Views/Chat/AIChatView.swift")
let vm = src("Agent/Chat/AIChatViewModel.swift")
let models = src("Agent/Chat/ChatModels.swift")
let xc = src("Localizable.xcstrings")
check("sources readable", !bar.isEmpty && !view.isEmpty && !vm.isEmpty && !models.isEmpty && !xc.isEmpty)

check("rotation yields to the transient line on its first focus only", bar.contains("if hasUnconsumedTransientHint {") && bar.contains("guard hasProtectedFirstFocus else {"))
check("refresh re-asserts the transient line instead of the pool entry",
      bar.contains("if hasUnconsumedTransientHint, let hint = transientHint {"))
check("no auto-expiry timer remains: the line persists", !bar.contains("transientHintTimer") && !bar.contains("transientHintDuration") && !bar.contains("transientHintExpiresAt"))
check("persistence is a plain boolean, not a deadline", bar.contains("private(set) var hasUnconsumedTransientHint = false"))
check("Coordinator.parent is refreshed on every update (the root-cause fix)",
      bar.contains("func updateUIView(_ tv: PastableUITextView, context: Context) {\n        // [T-ios-context-usage-hint]") && bar.contains("        context.coordinator.parent = self\n"))
check("the unconsumed flag is claimed at sync time, before the debounce", bar.range(of: "hasUnconsumedTransientHint = true")!.lowerBound < bar.range(of: "DispatchQueue.main.asyncAfter(deadline: .now() + 0.4")!.lowerBound)
check("re-assertion waits for the visual apply", bar.contains("if transientHintApplied, label.text != hint.text {"))
check("focusing without typing does not dismiss the line", bar.contains("[rotate] yield — usage line keeps the label"))
check("typing consumes the line on the empty → non-empty edge", bar.contains("guard hasUnconsumedTransientHint, !textView.text.isEmpty else { return }"))
check("keystrokes/paste consume it (textViewDidChange)", bar.contains("parent.text = textView.text\n            // [T-ios-context-usage-hint]") && bar.contains("consumeTransientHintIfNeeded(in: textView)\n            textView.invalidateIntrinsicContentSize()"))
check("programmatic writes consume it too (updateUIView)", bar.contains("context.coordinator.consumeTransientHintIfNeeded(in: tv)"))
check("consumption resets to the default entry so clearing the text does not revive the figure", bar.contains("hasUnconsumedTransientHint = false\n            transientHint = nil\n            placeholderPoolIndex = 0"))
check("only a new generation shows a line again", bar.components(separatedBy: "self.showTransientHint(hint, in: textView)").count == 2 && bar.components(separatedBy: "hasUnconsumedTransientHint = true").count == 2)
check("debounce is a Coordinator work item, not @State", bar.contains("private var contextHintDebounce: DispatchWorkItem?"))
check("no DispatchWorkItem lives in composer @State", !view.contains("@State private var") || !view.contains("@State private var sessionRefreshCooldownWork"))
check("Reduce Motion skips the crossfade", bar.contains("if UIAccessibility.isReduceMotionEnabled {\n                apply()"))
check("Coordinator cleans up the debounce on deinit", bar.contains("deinit {\n            contextHintDebounce?.cancel()\n        }"))
check("highlight uses range(of:) on formatted segments, no regex", bar.contains("ns.range(of: segment)") && !bar.contains("NSRegularExpression(pattern: \"\\\\d"))
check("normal tier gets no highlight colour", bar.contains("case .normal: return nil"))
check("warning highlight is toned-down system orange", bar.contains("case .warning: return UIColor.systemOrange.withAlphaComponent(0.50)"))
check("critical highlight is toned-down system red, no full-strength colour left", bar.contains("case .critical: return UIColor.systemRed.withAlphaComponent(0.60)") && !bar.contains("case .critical: return .systemRed"))
check("number segments use medium weight, not semibold", bar.contains("let emphasis = UIFont.systemFont(ofSize: font.pointSize, weight: .medium)") && !bar.contains("let emphasis = UIFont.systemFont(ofSize: font.pointSize, weight: .semibold)"))

check("glow is placed after ComposerSurface", view.range(of: ".modifier(ComposerSurface())\n            // [T-ios-context-usage-hint]") != nil)
check("glow is an overlay, not a background", view.contains("            .overlay {\n                ComposerContextGlow(tier:"))
check("glow never hit-tests", view.contains(".allowsHitTesting(false)\n        .animation(.easeInOut(duration: 0.35), value: tier)"))
check("glow is clipped INSIDE the composer shape", view.contains("                    .clipShape(shape)\n                .blendMode("))
check("dark adds light, light tints (no .screen)", view.contains(".blendMode(colorScheme == .dark ? .plusLighter : .normal)") && !view.contains(".screen)"))
check("no destinationOut mask remains", !view.contains("blendMode(.destinationOut)"))
check("glow is one soft diffusion stroke, no outline ring", view.contains(".stroke(color, lineWidth: 13)\n                    .blur(radius: 7)") && !view.contains("outlineWeight") && !view.contains(".inset(by: 0.875)"))
check("glow does not use drawingGroup", !view.contains("ComposerContextGlow") || !view[view.range(of: "private struct ComposerContextGlow")!.lowerBound...].contains("drawingGroup"))
check("breathing respects Reduce Motion", view.contains("if tier == .critical && !reduceMotion {"))

check("hint requires user-started, uncancelled turn and empty composer",
      vm.contains("if !userDidCancel, !turnWasSilentProgrammatic, inputText.isEmpty,"))
check("usage hidden when either side is unknown", vm.contains("(used > 0 && window > 0)"))
check("publishContextUsage is called where usage is written", vm.components(separatedBy: "publishContextUsage()").count - 1 >= 4)
check("source warning threshold is 0.7 (critical 0.8)", models.contains("static let warningFraction: Double = 0.7") && models.contains("static let criticalFraction: Double = 0.8"))
check("localization key present", xc.contains("\"Context %@ used · %@\" : {"))
check("zh-Hans translation present", xc.contains("上下文已用 %1$@ · %2$@"))
check("no Android file touched by this feature", !FileManager.default.fileExists(atPath: root + "../android/CONTEXT_USAGE_MARKER"))

print(failures == 0 ? "\n✅ All context-usage hint tests passed" : "\n❌ \(failures) failure(s)")
exit(failures == 0 ? 0 : 1)
