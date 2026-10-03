// Tests for [T-ios-context-usage-hint] on the voice panel — the same usage
// request the text composer gets is shown in InlineVoiceInputView's status
// line for a fixed window, tinted for the panel's dark surface.
//
// Pins the AttributedString highlighting (pure Foundation) and greps the
// wiring: the parameter, the call site, the debounce/window task keyed on
// the generation, the recording interrupt, and the dark-surface tints.
//
// Standalone (`swift VoiceContextUsageHintTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

print("▶️  AttributedString segment location (as used by highlightedUsageLine)")
let text = "上下文已用 62% · 124k / 200k"
var attributed = AttributedString(text)
var tinted = 0
for segment in ["62%", "124k / 200k"] {
    if let r = attributed.range(of: segment) {
        attributed[r].inlinePresentationIntent = .stronglyEmphasized   // stand-in for the tint
        tinted += 1
    }
}
checkEq("both segments found in a zh-Hans line", tinted, 2)
checkEq("untouched runs stay plain", attributed.runs.filter { $0.inlinePresentationIntent == nil }.count > 0, true)
checkEq("plain text preserved", String(attributed.characters), text)

print("\n▶️  source invariants")
let root = "../../"
func src(_ p: String) -> String { (try? String(contentsOfFile: root + p, encoding: .utf8)) ?? "" }
let voice = src("Views/Chat/Voice/InlineVoiceInputView.swift")
let view = src("Views/Chat/AIChatView.swift")
let bar = src("Views/Chat/ChatInputBar.swift")
check("sources readable", !voice.isEmpty && !view.isEmpty && !bar.isEmpty)

check("voice panel takes the hint as a parameter", voice.contains("    var contextUsageHint: ContextUsageHint? = nil"))
check("AIChatView passes vm.contextUsageHint to the voice panel", view.contains("                    contextUsageHint: vm.contextUsageHint\n                )"))
check("status line swaps to the usage text", voice.contains("if let hint = voiceUsageHint {\n            Text(Self.highlightedUsageLine(hint))"))
check("…and otherwise shows stateLabel (follows viewModel.state on revert)", voice.contains("        } else {\n            Text(stateLabel)"))
check("fixed window 3.5 s, debounce 0.4 s", voice.contains("static let usageHintDuration: TimeInterval = 3.5") && voice.contains("static let usageHintDebounce: TimeInterval = 0.4"))
check("task keyed on the generation (newer request cancels the older window)", voice.contains(".task(id: contextUsageHint?.generation) {"))
check("window revert only if the same generation is still showing", voice.contains("guard !Task.isCancelled, voiceUsageHint?.generation == hint.generation else { return }"))
check("starting a recording interrupts the line", voice.contains("if newState == .recording, voiceUsageHint != nil {"))
check("dark-surface tints are the panel's own, not the composer's UIColors",
      voice.contains("case .warning: return Color(UIColor.systemOrange).opacity(0.70)") && voice.contains("case .critical: return Color(UIColor.systemRed).opacity(0.78)"))
check("normal tier is untinted on the panel too", voice.contains("case .normal: return nil"))
check("highlight runs use medium weight", voice.contains("attributed[range].font = .subheadline.weight(.medium)"))
check("no stored closure capturing the view (no DispatchWorkItem/Timer in the panel hint path)", !voice.contains("DispatchWorkItem") && !voice.contains("usageHintTimer"))

print("\n▶️  requirement A: composer warning tier is darker than before")
check("composer warning alpha lowered to 0.50", bar.contains("case .warning: return UIColor.systemOrange.withAlphaComponent(0.50)"))
check("composer critical alpha unchanged at 0.60", bar.contains("case .critical: return UIColor.systemRed.withAlphaComponent(0.60)"))

print(failures == 0 ? "\n✅ All voice context-usage hint tests passed" : "\n❌ \(failures) failure(s)")
exit(failures == 0 ? 0 : 1)
