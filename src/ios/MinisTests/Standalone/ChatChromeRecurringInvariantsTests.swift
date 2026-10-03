// Recurring-area guards for the iOS chat chrome, week of 2026-09-16.
//
// Each section pins an invariant that was changed more than once this week, so
// the next edit cannot silently flip it back:
//
//   A. Status-bar scroll-to-top — ab61013a0 turned it OFF on the message list,
//      65acd11e3 turned it back ON (T-chat-statusbar-scrolltotop-mistap). The
//      settled contract: the message list stays eligible, every code-block
//      scroll view opts out, and the "..." mis-tap is handled by padding the
//      button's hit area (HitPaddedButton), not by disabling the gesture.
//   B. FloatingToolBar index math — 271633fb9 (T-toolbar-published-read-uaf)
//      moved it onto a plain `BlockFacts` snapshot and made `displayedBlock`
//      bounds-checked, because `displayedIdx` is -1 for an empty list.
//      `collapsedBar(now:)` still subscripts `toolBlocks[idx]` unchecked; that
//      is only safe because the bar is never built for an empty list. Pin both.
//   C. Transcript sheet over the tool sheet — 6ab57bec5
//      (T-agent-transcript-halfsheet): closing the transcript must NOT fire
//      `onTakeoverDone` (it would resume a browser-takeover continuation that
//      was never paused); closing the takeover browser still must.
//   D. FileProvider boot breaker — 0c9875798 (T-ios-fp-mac-bootcrash): the
//      appex stamps its OWN executable's mtime, which is not the main app's
//      generation. Pin that a successful boot still un-trips the app's breaker
//      even when the two stamps differ (the existing suite always uses one gen).
//
// Standalone: `swift ChatChromeRecurringInvariantsTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ ok: Bool) {
    if ok { print("  ✅ \(label)") } else { print("  ❌ \(label)"); failures += 1 }
}

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    print("  ⚠️  could not locate \(rel)")
    return ""
}
func slice(_ src: String, from start: String, maxLen: Int = 3000) -> String {
    guard let r = src.range(of: start) else { return "" }
    return String(src[r.lowerBound...].prefix(maxLen))
}
/// Strip `//` comments so a pin cannot be satisfied (or tripped) by prose.
func code(_ s: String) -> String {
    s.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
        if let r = line.range(of: "//") { return String(line[..<r.lowerBound]) }
        return String(line)
    }.joined(separator: "\n")
}

let infra = code(source("Agent/MessageList/MessageListInfrastructure.swift"))
let markdown = code(source("Views/Chat/SelectableMarkdownView.swift"))
let chatView = code(source("Views/Chat/AIChatView.swift"))
let toolSheet = code(source("Views/Chat/ToolLiveSheet.swift"))

// MARK: - A. scroll-to-top

print("\n▶️  A. status-bar scroll-to-top (ab61013a0 → 65acd11e3)")
check("the message list does NOT opt out of scrollsToTop",
      !infra.contains("collectionView.scrollsToTop = false"))
let codeBlockMake = slice(markdown, from: "final class CodeBlockAttachment", maxLen: 12000)
check("the code-block scroll view opts out (keeps the list the sole candidate)",
      codeBlockMake.contains("scrollView.scrollsToTop = false"))
check("the \"...\" button is a HitPaddedButton",
      chatView.contains("let button = HitPaddedButton(type: .system)"))

// Model of HitPaddedButton.point(inside:) — grows the hit box, never shrinks it.
func hitRect(w: Double, h: Double, min m: Double = 44) -> (x: Double, y: Double, w: Double, h: Double) {
    let dx = Swift.min(0, (w - m) / 2), dy = Swift.min(0, (h - m) / 2)
    return (dx, dy, w - 2 * dx, h - 2 * dy)
}
let small = hitRect(w: 14, h: 4)
check("a 14x4 glyph gets a 44x44 target", small.w == 44 && small.h == 44)
let big = hitRect(w: 60, h: 50)
check("a target already over 44pt is not shrunk", big.w == 60 && big.h == 50 && big.x == 0 && big.y == 0)
let padded = slice(chatView, from: "final class HitPaddedButton", maxLen: 600)
check("shipped padding uses min(0, …) so it only ever grows",
      padded.contains("min(0, (bounds.width - Self.minimumTouchSize) / 2)")
      && padded.contains("min(0, (bounds.height - Self.minimumTouchSize) / 2)"))

// MARK: - B. FloatingToolBar

print("\n▶️  B. FloatingToolBar index safety (271633fb9)")
// Model of displayedIdx's fallbacks: manual pick (count-checked) → followed →
// held → last active → count - 1.
func displayedIdx(count: Int, selected: Int?, lastActive: Int?) -> Int {
    if let s = selected, s < count { return s }
    if let a = lastActive { return a }
    return count - 1
}
var allInRange = true
for count in 1...6 {
    for sel in [nil, -0, 0, count - 1, count, count + 3] as [Int?] {
        for act in [nil, 0, count - 1] as [Int?] {
            let i = displayedIdx(count: count, selected: sel, lastActive: act)
            if !(0..<count).contains(i) { allInRange = false }
        }
    }
}
check("for any non-empty list displayedIdx is in range (incl. a stale manual pick)", allInRange)
check("for an empty list displayedIdx is -1 — callers MUST guard", displayedIdx(count: 0, selected: nil, lastActive: nil) == -1)

let host = slice(chatView, from: "private var floatingToolPreview: some View {", maxLen: 700)
check("the bar is only built for a non-empty block list (what keeps collapsedBar's subscript safe)",
      host.contains("if !allToolBlocks.isEmpty {") && host.contains("FloatingToolBar(toolBlocks: allToolBlocks"))
check("FloatingToolBar.toolBlocks is an immutable value (no shrink between guard and body)",
      toolSheet.contains("struct FloatingToolBar: View {\n    let toolBlocks: [AssistantBlock]"))
check("displayedBlock stays bounds-checked",
      slice(toolSheet, from: "private var displayedBlock: AssistantBlock? {", maxLen: 200)
        .contains("guard toolBlocks.indices.contains(idx) else { return nil }"))
// The index math must read the snapshot, not the @Published fields.
for (name, start) in [("runningAgentIdx", "private var runningAgentIdx: Int? {"),
                      ("recentlyFinishedIdx", "private var recentlyFinishedIdx: Int? {"),
                      ("agentActivity", "private var agentActivity: [UUID: String] {")] {
    let body = slice(toolSheet, from: start, maxLen: 600)
    let end = body.range(of: "\n    }\n").map { String(body[..<$0.upperBound]) } ?? body
    check("\(name) reads BlockFacts, not live @Published fields",
          // `info.toolStatus` is the child tracker's field, not a block's.
          end.contains("blockFacts") && !end.contains("b.kind") && !end.contains("b.toolStatus")
            && !end.contains("toolBlocks") && !end.contains("Self.isActive("))
}

// MARK: - C. transcript sheet dismissal

print("\n▶️  C. transcript sheet dismissal (6ab57bec5)")
enum Sheet { case takeoverBrowser, linkPreview, agentTranscript }
func onDismissFiresTakeoverDone(_ closed: Sheet?) -> Bool {
    if case .agentTranscript = closed { return false }
    return true
}
check("closing the transcript does not resume a takeover", !onDismissFiresTakeoverDone(.agentTranscript))
check("closing the takeover browser still resumes it", onDismissFiresTakeoverDone(.takeoverBrowser))
let dismissSrc = slice(toolSheet, from: ".sheet(item: $activeSheet, onDismiss: {", maxLen: 500)
check("shipped onDismiss returns early for .agentTranscript before onTakeoverDone",
      dismissSrc.range(of: "if case .agentTranscript = closed { return }").map { g in
          dismissSrc.range(of: "onTakeoverDone?()").map { g.lowerBound < $0.lowerBound } ?? false
      } ?? false)
check("the transcript is stacked on the tool sheet, not via dismiss + notification",
      toolSheet.contains("activeSheet = .agentTranscript(HelperSheetTarget(id: childId, title: title))"))

// MARK: - D. FP breaker with differing app / appex generations

print("\n▶️  D. FileProvider breaker, appex stamp != app stamp (0c9875798)")
struct State { var generation: String; var pending: Int }
struct Breaker {
    var s: State?
    let threshold = 3
    mutating func attempt(_ gen: String) {
        let p = (s?.generation == gen ? (s?.pending ?? 0) : 0) + 1
        s = State(generation: gen, pending: p)
    }
    mutating func boot(_ gen: String) { s = State(generation: gen, pending: 0) }
    func withhold(_ gen: String) -> Bool {
        guard let s, s.generation == gen else { return false }
        return s.pending >= threshold
    }
}
let appGen = "2026-09-21T03:14:00Z", appexGen = "2026-09-21T03:14:02Z"
var b = Breaker()
b.attempt(appGen); b.attempt(appGen); b.attempt(appGen)
check("tripped after three unanswered registrations", b.withhold(appGen))
b.boot(appexGen)
check("an appex boot stamped with the APPEX's generation still un-trips the app", !b.withhold(appGen))
b.attempt(appGen)
check("…and counting restarts at 1 on the next launch", b.s?.pending == 1)

let ext = code(source("FileProvider/FileProviderExtension.swift"))
check("precondition: the appex stamps its own Bundle.main executable",
      ext.contains("let bundle = Bundle.main") && ext.contains("FileProviderBootHealth.recordSuccessfulBoot(generation: execStamp)"))

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
