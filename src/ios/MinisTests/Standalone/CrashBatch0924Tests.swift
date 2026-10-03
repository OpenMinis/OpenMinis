// Guards for the fixes from the 2026-09-24 TestFlight crash batch (build 25).
//
//   [1] T-ios-crash-objectdestroy-composer-uaf
//       `objectdestroy.89Tm` / `117Tm` under `destroy for PastableTextView`
//       (iOS 16.0.3 stock, iOS 17.3.1) and `destroy for FloatingToolBar`
//       (iOS 16.6). Same family as the DraggableFAB fixes (e3579b381,
//       31cc74e1a): a child view's stored escaping closure captured a whole
//       AIChatView copy. The closures must capture only class references.
//   [2] T-messagelist-snapshot-unique-ids
//       `dataSource.apply` aborted inside applySnapshot (iOS 16.0.3 stock);
//       a duplicated item id is the usual cause. Items are de-duplicated,
//       first occurrence wins, before the apply.
//
// Standalone: `cd src/ios/MinisTests/Standalone && swift CrashBatch0924Tests.swift`

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func src(_ rel: String) -> String {
    (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let chat = src("Views/Chat/AIChatView.swift")
let list = src("Agent/MessageList/CollectionViewMessageListV3.swift")
check("sources found", !chat.isEmpty && !list.isEmpty)

func slice(_ text: String, from start: String, to end: String) -> String {
    guard let a = text.range(of: start),
          let b = text.range(of: end, range: a.upperBound..<text.endIndex) else { return "" }
    return String(text[a.lowerBound..<b.upperBound])
}

// MARK: - [1] no AIChatView capture in stored child-view closures

print("\n▶️  1. composer / tool-bar closures capture no AIChatView copy")
let field = slice(chat, from: "let field = PastableTextView(", to: "maxHeightOverride: composerTextHeight\n        )")
check("PastableTextView call site located", !field.isEmpty)
for method in ["handleReturnKey", "handleArrowUp", "handleArrowDown", "handleTabKey", "handleCaretChange"] {
    check("no instance-method reference `\(method)` passed as a stored closure",
          field.contains("\(method),") || field.contains("\(method)\n"), false)
}
for (name, action) in [("onReturnKey", ".returnKey"), ("onArrowUp", ".arrowUp"), ("onArrowDown", ".arrowDown"),
                       ("onTab", ".tab")] {
    check("\(name) routes through the channel with an explicit capture",
          field.contains("\(name): { [composerActions] in") && field.contains("composerActions.send(\(action))"))
}
check("onCaretChange routes through the channel",
      field.contains("onCaretChange: { [composerActions] caret in _ = composerActions.send(.caret(caret)) }"))
for name in ["onPasteImage", "onPasteFile", "onPasteLongText"] {
    check("\(name) captures `vm` explicitly", field.contains("\(name): { [vm] "))
}
// Every `vm.` use inside a closure of the call site must sit behind a [vm]
// capture. Arguments evaluated at the call site (vm.contextUsageHint, …) are
// fine: they are values, not closures.
let closureBodies = field.components(separatedBy: "{ ").dropFirst().map { $0.components(separatedBy: "}").first ?? "" }
let unguarded = closureBodies.filter { $0.contains("vm.") && !$0.hasPrefix("[vm]") }
check("no closure body reads vm through self (found \(unguarded.count))", unguarded.isEmpty)

let bar = slice(chat, from: "FloatingToolBar(toolBlocks: allToolBlocks", to: "vm.resumeFromBrowserTakeover()\n            })")
check("FloatingToolBar call site located", !bar.isEmpty)
check("onBrowserTakeover captures vm only", bar.contains("onBrowserTakeover: { [vm] in"))
check("onTakeoverDone captures vm only", bar.contains("onTakeoverDone: { [vm] in"))
check("vm is bound to a local before the tool bar",
      chat.contains("let vm = self.vm\n            FloatingToolBar(toolBlocks:"))

let channel = slice(chat, from: "final class ComposerActionChannel {", to: "\n}")
check("channel type exists and is a class", !channel.isEmpty)
check("unwired channel answers 'not consumed' (keys keep default behaviour)",
      channel.contains("func send(_ action: Action) -> Bool { handler?(action) ?? false }"))
check("channel is @State-held", chat.contains("@State private var composerActions = ComposerActionChannel()"))
check("channel is wired on appear", chat.contains(".onAppear {\n            wireComposerActions()"))
let wire = slice(chat, from: "private func wireComposerActions() {", to: "\n    }\n")
for call in ["handleReturnKey()", "handleArrowUp()", "handleArrowDown()", "handleTabKey()", "handleCaretChange(caret)"] {
    check("wiring dispatches to \(call)", wire.contains(call))
}

/// Behavioural port of the channel: arrow/tab keys must return the handler's
/// answer so the text view falls back to its default when nothing consumed it.
final class Channel {
    enum Action { case returnKey, arrowUp, arrowDown, tab, caret(Int) }
    var handler: ((Action) -> Bool)?
    func send(_ a: Action) -> Bool { handler?(a) ?? false }
}
do {
    let c = Channel()
    check("unwired: arrow key not consumed", c.send(.arrowUp) == false)
    var menuOpen = false
    c.handler = { a in
        switch a { case .arrowUp, .arrowDown, .tab: return menuOpen; default: return true }
    }
    check("wired, no menu: arrow key not consumed", c.send(.arrowDown) == false)
    menuOpen = true
    check("wired, menu open: arrow key consumed", c.send(.arrowDown) == true)
}

// MARK: - [2] unique snapshot items

print("\n▶️  2. snapshot items are unique before the diffable apply")
enum Item: Hashable { case whole(Int), header(Int), block(Int, Int), footer(Int) }
func uniqueItems(_ items: [Item]) -> [Item] {
    var seen = Set<Item>()
    return items.filter { seen.insert($0).inserted }
}
check("duplicate-free list is unchanged",
      uniqueItems([.whole(1), .header(2), .block(2, 1), .footer(2)]) == [.whole(1), .header(2), .block(2, 1), .footer(2)])
check("a message listed twice keeps its first position",
      uniqueItems([.whole(1), .header(2), .block(2, 1), .whole(1), .footer(2)]) == [.whole(1), .header(2), .block(2, 1), .footer(2)])
check("a duplicated block id inside one message collapses",
      uniqueItems([.header(2), .block(2, 7), .block(2, 7), .footer(2)]) == [.header(2), .block(2, 7), .footer(2)])
check("same block id under different messages is NOT a duplicate",
      uniqueItems([.block(1, 7), .block(2, 7)]).count == 2)
check("source: helper keeps first occurrence",
      list.contains("static func uniqueItems(_ items: [MessageListItem], caller: String) -> [MessageListItem] {")
      && list.contains("if seen.insert(item).inserted { return true }"))
check("source: returns the input untouched when nothing was dropped",
      list.contains("guard !dropped.isEmpty else { return items }"))
let build = slice(list, from: "var newItems: [MessageListItem] = []", to: "dataSource.apply(snapshot, animatingDifferences: false)")
check("source: dedup runs after the item list is built and before the apply",
      build.contains("newItems = Self.uniqueItems(newItems, caller: caller)")
      && (build.range(of: "newItems = Self.uniqueItems")!.lowerBound < build.range(of: "snapshot.appendItems(newItems")!.lowerBound))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
