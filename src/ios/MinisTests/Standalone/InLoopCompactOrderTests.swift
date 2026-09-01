// Regression test for GH#235 — in-loop compaction must not leave the run's
// subsequent output ABOVE the "messages compacted" divider.
//
// Standalone (run with `swift InLoopCompactOrderTests.swift`) because the
// MinisTests target has a pre-existing compile break — same rationale as
// SkillDescriptionStaleTests.swift.
//
// What is modelled here, and why this is a meaningful test rather than a
// tautology: the two production rules that interact to produce the bug are
// reproduced verbatim from the source, and the fix is applied on top of them.
//
//   1. `compactBefore` (AIChatViewModel+Compaction.swift, `dividerInsertIdx`)
//      inserts the divider immediately AFTER the anchor row and marks every
//      row at index < dividerInsertIdx as `isCompactedHistory`.
//   2. The in-loop call site (AIChatViewModel.swift, `case .needsCompact`)
//      picks the anchor as `messages.last(where: active)` — which during a
//      run IS the assistant bubble currently being streamed into.
//
// Composing (1) and (2) is what puts the divider *after* the live bubble; the
// loop then `continue`s and keeps writing into it, so new thinking/tool blocks
// land above the divider inside the greyed-out region.

import Foundation

// MARK: - Minimal stand-ins for the production types

enum Role { case user, assistant, compactDivider, systemInfo }

final class Msg {
    let id = UUID()
    let role: Role
    var blocks: [String]
    var content: String
    var isCompactedHistory = false
    var isAwaitingModelResponse = false
    init(_ role: Role, blocks: [String] = [], content: String = "") {
        self.role = role
        self.blocks = blocks
        self.content = content
    }
}

/// Verbatim port of the anchor predicate at the `.needsCompact` call site.
func inLoopAnchorId(_ messages: [Msg]) -> UUID? {
    messages.last(where: {
        $0.role != .compactDivider && $0.role != .systemInfo && !$0.isCompactedHistory
    })?.id
}

/// Verbatim port of `compactBefore`'s divider placement + greying, reduced to
/// the in-session case (anchor located by identity).
func applyCompactBefore(_ messages: inout [Msg], anchorId: UUID) {
    messages.removeAll { $0.role == .compactDivider }
    let dividerInsertIdx: Int
    if let idx = messages.firstIndex(where: { $0.id == anchorId }) {
        dividerInsertIdx = idx + 1          // divider goes AFTER the anchor
    } else {
        dividerInsertIdx = messages.count
    }
    messages.insert(Msg(.compactDivider, content: "N messages compacted"), at: dividerInsertIdx)
    for i in 0..<dividerInsertIdx where messages[i].role != .compactDivider && messages[i].role != .systemInfo {
        messages[i].isCompactedHistory = true
    }
}

// MARK: - Harness

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

/// Index of the single divider, or nil.
func dividerIdx(_ m: [Msg]) -> Int? { m.firstIndex { $0.role == .compactDivider } }

/// Build the state at the moment in-loop compaction fires: a finished turn,
/// then the current run's assistant bubble which has already streamed some
/// content and is still open.
func makeRunningState() -> (msgs: [Msg], runMsgId: UUID) {
    let live = Msg(.assistant, blocks: ["thinking-1", "tool_use-1"])
    live.isAwaitingModelResponse = true
    let msgs = [
        Msg(.user, content: "do a long task"),
        Msg(.assistant, blocks: ["earlier answer"]),
        Msg(.user, content: "keep going"),
        live,
    ]
    return (msgs, live.id)
}

// MARK: - 1. The bug, as it behaves WITHOUT the fix

print("GH#235 — without the fix: post-compaction output lands above the divider")
do {
    var (msgs, runMsgId) = makeRunningState()
    let anchor = inLoopAnchorId(msgs)!

    check("anchor is the LIVE streaming bubble (the root of the bug)", anchor == runMsgId, true)

    applyCompactBefore(&msgs, anchorId: anchor)

    // Old behaviour: loop `continue`s and keeps appending into the same bubble.
    let liveIdx = msgs.firstIndex { $0.id == runMsgId }!
    msgs[liveIdx].blocks.append("thinking-2-after-compact")
    msgs[liveIdx].blocks.append("final-answer")

    let dIdx = dividerIdx(msgs)!
    check("live bubble sits ABOVE the divider", liveIdx < dIdx, true)
    check("live bubble is greyed as compacted history", msgs[liveIdx].isCompactedHistory, true)
    check("post-compaction blocks are in that greyed bubble", msgs[liveIdx].blocks.contains("final-answer"), true)
    check("nothing at all below the divider", dIdx == msgs.count - 1, true)
}

// MARK: - 2. With the fix

print("\nWith the fix: the bubble is sealed and output continues below the divider")
do {
    var (msgs, runMsgId) = makeRunningState()
    let anchor = inLoopAnchorId(msgs)!
    applyCompactBefore(&msgs, anchorId: anchor)

    // --- the fix, mirroring the production edit ---
    if let sealedIdx = msgs.firstIndex(where: { $0.id == runMsgId }) {
        let sealed = msgs[sealedIdx]
        sealed.isAwaitingModelResponse = false
        if sealed.blocks.isEmpty && sealed.content.isEmpty { msgs.remove(at: sealedIdx) }
    }
    let fresh = Msg(.assistant)
    fresh.isAwaitingModelResponse = true
    msgs.append(fresh)
    let newRunMsgId = fresh.id
    // -------------------------------------------------

    let newIdx = msgs.firstIndex { $0.id == newRunMsgId }!
    msgs[newIdx].blocks.append("thinking-2-after-compact")
    msgs[newIdx].blocks.append("final-answer")

    let dIdx = dividerIdx(msgs)!
    check("post-compaction bubble sits BELOW the divider", newIdx > dIdx, true)
    check("post-compaction bubble is NOT greyed", !msgs[newIdx].isCompactedHistory, true)
    check("its blocks are the new output", msgs[newIdx].blocks == ["thinking-2-after-compact", "final-answer"], true)

    // The pre-compaction bubble keeps its own content, above the line.
    let sealed = msgs.first { $0.id == runMsgId }!
    let sealedIdx = msgs.firstIndex { $0.id == runMsgId }!
    check("sealed bubble stays above the divider", sealedIdx < dIdx, true)
    check("sealed bubble keeps its pre-compaction blocks", sealed.blocks == ["thinking-1", "tool_use-1"], true)
    check("sealed bubble is no longer awaiting a response", !sealed.isAwaitingModelResponse, true)
}

// MARK: - 3. Empty-bubble edge case

print("\nEdge case: compaction fires before the run produced anything")
do {
    var msgs = [
        Msg(.user, content: "hi"),
        Msg(.assistant, blocks: ["prior answer"]),
    ]
    let live = Msg(.assistant)                 // no blocks, no content
    live.isAwaitingModelResponse = true
    msgs.append(live)
    let runMsgId = live.id

    let anchor = inLoopAnchorId(msgs)!
    applyCompactBefore(&msgs, anchorId: anchor)

    if let sealedIdx = msgs.firstIndex(where: { $0.id == runMsgId }) {
        let sealed = msgs[sealedIdx]
        sealed.isAwaitingModelResponse = false
        if sealed.blocks.isEmpty && sealed.content.isEmpty { msgs.remove(at: sealedIdx) }
    }
    let fresh = Msg(.assistant)
    msgs.append(fresh)

    check("empty sealed bubble is dropped (no blank grey row)", !msgs.contains { $0.id == runMsgId }, true)
    check("fresh bubble is below the divider", msgs.firstIndex { $0.id == fresh.id }! > dividerIdx(msgs)!, true)
}

// MARK: - 4. Invariants that must survive

print("\nInvariants")
do {
    var (msgs, runMsgId) = makeRunningState()
    let anchor = inLoopAnchorId(msgs)!
    applyCompactBefore(&msgs, anchorId: anchor)
    if let i = msgs.firstIndex(where: { $0.id == runMsgId }) { msgs[i].isAwaitingModelResponse = false }
    msgs.append(Msg(.assistant))

    check("exactly one divider", msgs.filter { $0.role == .compactDivider }.count == 1, true)
    check("everything above the divider is greyed",
          msgs[0..<dividerIdx(msgs)!].allSatisfy { $0.role == .compactDivider || $0.role == .systemInfo || $0.isCompactedHistory }, true)
    check("nothing below the divider is greyed",
          msgs[(dividerIdx(msgs)!+1)...].allSatisfy { !$0.isCompactedHistory }, true)
}

print(failures == 0 ? "\n✅ all checks passed" : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
