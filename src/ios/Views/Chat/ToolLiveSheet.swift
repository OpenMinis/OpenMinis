//
//  ToolLiveSheet.swift
//  MinisApp
//
//  Expandable live tool preview — the floating toolbar above the input
//  bar plus the full-screen sheet that shows tool arguments, streaming
//  shell output, file edits/writes, browser snapshots, memory edits and
//  image tools with full scrubbing and zoom. Extracted from
//  AIChatView.swift so the chat view can focus on message layout.
//

import Combine
import SwiftUI
import UIKit
import WebKit

/// Detects http(s) URLs in a line of shell output and returns an
/// AttributedString with each URL marked as a `.link` plus single
/// underline. Tapping one routes through the SwiftUI `\.openURL`
/// environment — callers override that to present MinisLinkPreviewView.
///
/// Uses NSDataDetector (the same engine UITextView uses for its built-in
/// link detection) so it handles URLs with or without surrounding
/// punctuation, trailing commas, etc.
private let _shellURLDetector: NSDataDetector? = {
    try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
}()

/// Maximum characters allowed on a single rendered line. CoreText's glyph
/// fallback loop is super-linear in line length, so one pathological line
/// (e.g. `cat`-ing a minified file or a binary) can hang the main thread
/// long enough to trip the 10s scene-update watchdog (0x8BADF00D).
private let _maxRenderedLineLength = 2000

/// Strips ANSI CSI/OSC escape sequences and replaces control characters
/// (except `\n` and `\t`) with U+FFFD. Also hard-wraps any line longer
/// than `_maxRenderedLineLength` so per-line layout stays bounded.
///
/// Shell tool output can legitimately contain ANSI color codes, NUL bytes,
/// partial UTF-8 and absurdly long single lines — feeding that directly
/// into `Text` / `NSAttributedString` can hang CoreText for many seconds.
fileprivate func sanitizeForDisplay(_ text: String) -> String {
    guard !text.isEmpty else { return text }

    var out = String()
    out.reserveCapacity(text.count)
    var i = text.unicodeScalars.startIndex
    let end = text.unicodeScalars.endIndex
    let scalars = text.unicodeScalars

    while i < end {
        let s = scalars[i]
        let v = s.value

        // ANSI escape: ESC (0x1B) followed by either '[' (CSI) or ']' (OSC)
        // or a single-char sequence. Swallow the whole thing.
        if v == 0x1B {
            let next = scalars.index(after: i)
            if next < end {
                let n = scalars[next].value
                if n == 0x5B { // '[' — CSI: ESC [ ... final-byte (0x40..0x7E)
                    var j = scalars.index(after: next)
                    while j < end {
                        let c = scalars[j].value
                        j = scalars.index(after: j)
                        if c >= 0x40 && c <= 0x7E { break }
                    }
                    i = j
                    continue
                } else if n == 0x5D { // ']' — OSC: ESC ] ... BEL or ESC \
                    var j = scalars.index(after: next)
                    while j < end {
                        let c = scalars[j].value
                        if c == 0x07 { // BEL
                            j = scalars.index(after: j)
                            break
                        }
                        if c == 0x1B { // ESC — check for ST (ESC \)
                            let k = scalars.index(after: j)
                            if k < end && scalars[k].value == 0x5C {
                                j = scalars.index(after: k)
                                break
                            }
                        }
                        j = scalars.index(after: j)
                    }
                    i = j
                    continue
                } else {
                    // Two-char escape (e.g. ESC M, ESC =) — drop both.
                    i = scalars.index(after: next)
                    continue
                }
            }
            // Lone trailing ESC — drop.
            i = next
            continue
        }

        // Keep \n (0x0A) and \t (0x09); drop/replace other C0 and DEL.
        if (v < 0x20 && v != 0x0A && v != 0x09) || v == 0x7F {
            // Silently drop \r to avoid double line breaks from CRLF.
            if v != 0x0D {
                out.unicodeScalars.append(Unicode.Scalar(0xFFFD)!)
            }
            i = scalars.index(after: i)
            continue
        }

        // C1 control block (0x80..0x9F) — rare but CoreText fallback-heavy.
        if v >= 0x80 && v <= 0x9F {
            out.unicodeScalars.append(Unicode.Scalar(0xFFFD)!)
            i = scalars.index(after: i)
            continue
        }

        out.unicodeScalars.append(s)
        i = scalars.index(after: i)
    }

    // Hard-wrap any line longer than the cap. We measure in UTF-16 code
    // units (what CoreText ultimately consumes) to stay cheap and safe
    // against grapheme-cluster walks on huge strings.
    var needsWrap = false
    var run = 0
    for u in out.utf16 {
        if u == 0x0A {
            run = 0
        } else {
            run += 1
            if run > _maxRenderedLineLength { needsWrap = true; break }
        }
    }
    guard needsWrap else { return out }

    var wrapped = String()
    wrapped.reserveCapacity(out.count)
    for line in out.split(separator: "\n", omittingEmptySubsequences: false) {
        if line.utf16.count <= _maxRenderedLineLength {
            wrapped.append(contentsOf: line)
        } else {
            // Break on grapheme boundaries so we don't split a cluster.
            var col = 0
            for ch in line {
                let w = ch.utf16.count
                if col + w > _maxRenderedLineLength {
                    wrapped.append("\n")
                    col = 0
                }
                wrapped.append(ch)
                col += w
            }
        }
        wrapped.append("\n")
    }
    if !out.hasSuffix("\n") { wrapped.removeLast() }
    return wrapped
}

fileprivate func attributedShellLine(_ text: String) -> AttributedString {
    // Build the AttributedString from an NSAttributedString so we can use
    // absolute NSRange offsets from NSDataDetector directly. This avoids
    // ambiguity when the same URL appears twice in the same line.
    let text = sanitizeForDisplay(text)
    guard !text.isEmpty else { return AttributedString(text) }
    guard let detector = _shellURLDetector else { return AttributedString(text) }
    let ns = text as NSString
    let matches = detector.matches(in: text, range: NSRange(location: 0, length: ns.length))
    guard !matches.isEmpty else { return AttributedString(text) }

    let mutable = NSMutableAttributedString(string: text)
    for m in matches {
        guard let url = m.url,
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else { continue }
        // NSDataDetector fabricates http:// for bare domains like "github.com/foo",
        // which matches path fragments in shell output (e.g. "/Users/.../github.com/OpenMinis/...").
        // Require the matched substring itself to begin with an explicit http:// or https:// scheme.
        let matched = ns.substring(with: m.range)
        let lower = matched.lowercased()
        guard lower.hasPrefix("http://") || lower.hasPrefix("https://") else { continue }
        mutable.addAttributes([
            .link: url,
            .underlineStyle: NSUnderlineStyle.single.rawValue,
            .foregroundColor: UIColor.cyan,
        ], range: m.range)
    }
    return AttributedString(mutable)
}

/// [T-ios-tool-sheet-watchdog] Shared tuning for lazily-revealed tool output.
/// One source of truth for the sheet's own textContent window and the
/// LazyRevealChunks component below — the two must agree on chunk size.
fileprivate enum LazyRenderTuning {
    /// Lines per chunk — must match chunkedLines' default.
    static let chunkLines = 40
    /// Initial reveal: ~200 lines = 5 chunks.
    static let initialChunks = 5
    /// Each subsequent batch: 200 more lines = 5 chunks.
    static let batchChunks = 5
    /// Byte cap for the initial reveal — clamps the initial chunk count so a
    /// few very long lines (< 200 lines but > 10KB) still load incrementally.
    static let initialByteCap = 10 * 1024

    /// Initial number of chunks to reveal: min(initialChunks, count), further
    /// clamped so the revealed text stays under `initialByteCap`.
    static func initialRevealCount(_ chunks: [(id: Int, text: String)]) -> Int {
        guard !chunks.isEmpty else { return 0 }
        var count = 0
        var bytes = 0
        for chunk in chunks.prefix(initialChunks) {
            bytes += chunk.text.utf8.count
            count += 1
            if bytes >= initialByteCap { break }
        }
        return max(1, count)
    }
}

/// [T-ios-tool-sheet-watchdog] Self-contained lazily-revealed chunk list with
/// a "Load more / Load all" footer.
///
/// Several render paths in this sheet (generic text snapshot, browser result
/// with and without screenshot, file/memory editor cards, JS script card,
/// diff halves) used to hand the COMPLETE tool output to Text in one pass. A
/// ~50KB mixed-script result then spends >10s inside CoreText glyph encoding
/// and per-run font fallback on the main thread, and FrontBoard kills the app
/// with 0x8BADF00D — five such .ips were captured on 2026-08-30 alone. The
/// textContent path already had a reveal window (`revealedChunkCount`) but
/// none of its siblings did. This view packages the same window so every path
/// can share it; reveal state lives here, so several cards inside one sheet
/// never fight over a single counter.
///
/// `resetKey` must change when the displayed block changes (pass `block.id`)
/// so next/prev navigation re-collapses to the initial window.
private struct LazyRevealChunks<ChunkView: View>: View {
    let chunks: [(id: Int, text: String)]
    let resetKey: UUID
    @ViewBuilder let chunkView: (String) -> ChunkView

    @State private var revealed: Int = 0
    @State private var revealedFor: UUID?

    var body: some View {
        Group {
            ForEach(Array(chunks.prefix(max(revealed, 1))), id: \.id) { chunk in
                chunkView(chunk.text)
            }
            if revealed < chunks.count {
                footer
            }
        }
        .onAppear { resetIfNeeded() }
        .onChange(of: resetKey) { _ in resetIfNeeded() }
    }

    private func resetIfNeeded() {
        guard revealedFor != resetKey else { return }
        revealedFor = resetKey
        revealed = LazyRenderTuning.initialRevealCount(chunks)
    }

    private var footer: some View {
        let remaining = chunks.count - revealed
        let nextBatch = min(LazyRenderTuning.batchChunks, remaining)
        return HStack(spacing: 16) {
            Button {
                revealed = min(revealed + LazyRenderTuning.batchChunks, chunks.count)
            } label: {
                Label("Load more (\(nextBatch * LazyRenderTuning.chunkLines) lines)", systemImage: "chevron.down")
                    .font(.system(size: 13, weight: .medium))
            }
            Button {
                revealed = chunks.count
            } label: {
                Text("Load all")
                    .font(.system(size: 13, weight: .medium))
            }
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        // Auto-load when the footer scrolls into view so the user can just
        // keep scrolling to reveal more without tapping.
        .onAppear {
            revealed = min(revealed + LazyRenderTuning.batchChunks, chunks.count)
        }
    }
}

/// The floating toolbar: collapsed shows thumbnail + status bar + snapshot chips, expanded opens a full-screen live sheet.
struct FloatingToolBar: View {
    let toolBlocks: [AssistantBlock]
    var toolSnapshots: [ToolSnapshotItem] = []
    var browserPool: BrowserTabPool?
    var onBrowserTakeover: (() -> Void)?
    var onTakeoverDone: (() -> Void)?
    @State private var selectedIdx: Int? = nil
    /// [T-agent-follow-change] Which sub agent the bar is following, and when
    /// it was last allowed to change.
    ///
    /// Sub agents run their own tool loops in parallel, and each step of each
    /// loop is a candidate to show. Following every one of them made the bar
    /// flick between three agents several times a second — unreadable, and
    /// nothing stayed up long enough to read. So a switch BETWEEN agents is
    /// rate-limited: once the bar moves to an agent it stays for
    /// `agentFollowWindow`, and whatever changed most recently when that
    /// window closes is what it moves to next. Updates from the agent it is
    /// already showing pass through immediately — that is the same agent's
    /// preview refreshing, not a switch.
    @State private var followedAgentId: UUID?
    @State private var followedAt: Date = .distantPast
    @State private var lastAgentActivity: [UUID: String] = [:]
    /// The most recent agent the window turned away, applied once it closes.
    @State private var pendingAgentId: UUID?
    private static let agentFollowWindow: TimeInterval = 5
    /// [T-agent-follow-expiry] How long the bar stays on a sub agent after its
    /// last activity before falling back to the newest tool.
    ///
    /// Following an agent is meant to show what it is doing NOW. Once it stops
    /// producing activity — because it finished, or because it is between
    /// turns — holding the bar there strands the preview on a task that is
    /// over while newer tools run underneath it. One minute is the cap on how
    /// long any single sub agent may hold the bar: long enough to read what it
    /// was doing, bounded so a quiet agent cannot own the preview.
    /// No timer backs this. The expiry is a comparison against `now` inside
    /// `displayedIdx`, so it cannot double-schedule, cannot leak, and has
    /// nothing to cancel — the state it reads (`followedAt`) has exactly one
    /// writer. The one edge it does have is the clock: `now` only advances
    /// while some agent runs (the TimelineView in `body`), so if the LAST
    /// agent finishes inside the window, the tick stops and the comparison
    /// freezes. `Self.isActive` in the same condition covers that — a
    /// finished agent fails the running check regardless of the clock, so the
    /// bar still falls through on the next repaint.
    private static let agentFocusExpiry: TimeInterval = 60

    /// [T-agent-manual-hold] When the user last picked a tool with the arrows.
    /// A manual choice suppresses auto-follow for `manualHoldSeconds` so the
    /// bar stops sliding out from under someone who is reading it; after that
    /// the automatic rule takes over again.
    @State private var selectedAt: Date = .distantPast
    private static let manualHoldSeconds: TimeInterval = 60
    @State private var expanded = false
    /// [T-agent-thumbnail-accent] The sub agent's currently running tool, when
    /// its tile was tapped. Presented as its own sheet so the child's block and
    /// browser pool are what the sheet sees.
    @State private var childToolTarget: ChildToolTarget?
    /// [T-agent-inner-tool-front] Observed so the bar redraws the moment a sub
    /// agent moves to a different tool.
    ///
    /// `sessionToolInfo` is @Published and updated by the child's own loop, so
    /// the preview follows the child's work by event rather than by polling —
    /// no timer, and no lag between the child switching tools and the tile
    /// showing it. (The 1 Hz TimelineView below stays: the 5-second hold rule
    /// is driven by elapsed time, which no event announces.)
    @ObservedObject private var activity = SessionActivityTracker.shared
    @AppStorage("toolPreviewEnabled") private var toolPreviewEnabled: Bool = true
    // [T-p2-agent-in-toolbar] A tool that just finished stays on top for a
    // few seconds so its result is seen, then the bar falls back to the
    // background agent block that is still running (kept `.running` by
    // HelperRunner's mirror), showing the user that work continues.
    //
    // Stateless on purpose: this view does not observe every block, so an
    // `onChange` keyed on block statuses missed the transition (device run
    // 19:56). The hold is derived from the finished block's own start time
    // + duration against a clock that ticks only while an agent is running.
    /// The clock the hold rule reads. Fed by a TimelineView while an agent
    /// runs (see `body`); a plain `Date()` otherwise. It used to be @State
    /// driven by a Timer publisher, but that publisher is a stored property
    /// recreated on every parent rebuild, so its ticks never reached the
    /// on-screen instance (device run 20:02: the log said held=1, the bar
    /// showed the agent).
    var now = Date()
    private static let holdSeconds: TimeInterval = 5

    private static func isActive(_ b: AssistantBlock) -> Bool {
        if case .streaming = b.toolStatus { return true }
        if case .running = b.toolStatus { return true }
        return false
    }

    /// [T-toolbar-published-read-uaf] Plain-value snapshot of the block fields
    /// this bar's layout math needs.
    ///
    /// Reading a `@Published` property goes through `Published.access`, which
    /// messages the ENCLOSING ObservableObject to register the dependency —
    /// `objc_msgSend` on `objectWillChange`. `AssistantBlock` is a `final
    /// class`, so when the agent loop drops a block (blocks are removed and
    /// reassigned in ~100 places across AIChatViewModel, none of them actor
    /// isolated) that publisher can be gone while this body is mid-evaluation,
    /// and the message lands on a freed object — the crash was inside
    /// `Published.access` → `objc_msgSend` → `objc_class::isInitialized`, with
    /// a garbage isa, reached from `runningAgentIdx`.
    ///
    /// Reading each field ONCE into a struct, before any of the index math
    /// runs, means the derivation below touches no publisher at all. It also
    /// makes the math consistent: it previously re-read `kind`/`toolStatus`
    /// several times per body pass and could see them change mid-computation.
    private struct BlockFacts {
        let id: UUID
        let isAgent: Bool
        let isActive: Bool
        let toolStartTime: Date?
        let toolDuration: TimeInterval?
        let helperChildSessionId: String?
    }

    /// Snapshot taken once per body evaluation. Cheap — a handful of scalar
    /// reads per block, no allocation beyond the array itself.
    private var blockFacts: [BlockFacts] {
        toolBlocks.map { b in
            let kind = b.kind
            let status = b.toolStatus
            let isActive: Bool = {
                if case .streaming = status { return true }
                if case .running = status { return true }
                return false
            }()
            var isAgent = false
            if case .delegateTool = kind { isAgent = true }
            return BlockFacts(
                id: b.id,
                isAgent: isAgent,
                isActive: isActive,
                toolStartTime: b.toolStartTime,
                toolDuration: b.toolDuration,
                helperChildSessionId: b.helperChildSessionId
            )
        }
    }

    /// Index of the last agent block that is still running, if any.
    private var runningAgentIdx: Int? {
        blockFacts.lastIndex { $0.isAgent && $0.isActive }
    }

    /// The most recently finished non-agent tool whose completion is less
    /// than `holdSeconds` old — only meaningful while an agent still runs.
    private var recentlyFinishedIdx: Int? {
        let facts = blockFacts
        guard facts.contains(where: { $0.isAgent && $0.isActive }) else { return nil }
        return facts.indices.last { i in
            let b = facts[i]
            guard !b.isActive, let start = b.toolStartTime, let dur = b.toolDuration else { return false }
            return now.timeIntervalSince(start.addingTimeInterval(dur)) < Self.holdSeconds
        }
    }

    private var displayedIdx: Int {
        // [T-agent-manual-hold] A manual pick wins outright, but only for a
        // minute. Previously it won forever: once the user touched an arrow
        // the bar never auto-followed again for the life of the view, which
        // silently disabled the whole live preview. Expiring it keeps both
        // halves — read in peace now, live updates later.
        if let idx = selectedIdx, idx < toolBlocks.count,
           now.timeIntervalSince(selectedAt) < Self.manualHoldSeconds {
            return idx
        }
        // [T-agent-inner-tool-front] Live work a level down outranks the
        // 5-second hold. That hold exists so a tool THIS conversation just
        // finished is seen before a background agent takes the bar back
        // (8f09afa45); it was never meant to sit on top of an agent that is
        // mid-tool right now — and because it only skips `isActive` blocks, a
        // just-finished AGENT triggered it too and hid a sibling doing real
        // work. Ordering it after this rule keeps both behaviours.
        //
        // [T-agent-follow-change] Whichever sub agent just changed activity is
        // what the bar shows. Resolved from the block id each time rather than
        // held as an index, so a delegation arriving mid-run cannot re-point
        // the bar at someone else.
        //
        // [T-agent-follow-release] …but only while that agent is still running.
        // `followedAgentId` was previously honoured for as long as the block
        // existed, and nothing ever cleared it, so the FIRST agent the bar
        // followed held the bar for the life of the view: a finished agent
        // outranked a tool running right now, and the three fallbacks below
        // became unreachable. Reported as a failed agent ("no deliverable")
        // sitting on the bar for minutes while the pager counted up around it —
        // the count grows because new tool blocks keep being appended to the
        // session while this pointer stays frozen on the dead one.
        //
        // The staleness check belongs HERE rather than only in the 1 Hz tick:
        // that tick runs only while some agent is running, so when the LAST
        // agent finishes it stops firing — which is exactly the reported case.
        // `tickFollow` still drops the id so the state does not linger, but
        // this guard is what makes the bar correct the moment it repaints.
        // [T-agent-follow-expiry] Two conditions now, not one: the followed
        // agent must still be running AND have moved within the last 15s.
        // "Still running" alone was not enough — an agent that is alive but
        // idle (between turns, or waiting on a long tool) kept the bar on a
        // preview that had stopped changing, which is the same stranding the
        // running-check was added to fix, one state further along.
        // [T-toolbar-published-read-uaf] Same snapshot as above — no
        // `@Published` reads inside the index math.
        let facts = blockFacts
        if let followed = followedAgentId,
           let idx = facts.firstIndex(where: { $0.id == followed }),
           facts[idx].isActive,
           now.timeIntervalSince(followedAt) < Self.agentFocusExpiry {
            return idx
        }
        if let held = recentlyFinishedIdx { return held }
        if let activeIdx = facts.lastIndex(where: { $0.isActive }) { return activeIdx }
        // [T-agent-follow-expiry] Nothing running: the NEWEST block wins.
        //
        // This used to land on the last SUB AGENT instead, so that reopening a
        // chat with three finished agents did not point at a plain tool nobody
        // was looking at. But it also meant that when a fan-out ended, the bar
        // stayed on the last agent for good — the reported "preview and index
        // stuck on the final sub agent after the task finished". The newest
        // block is the honest answer to "what happened last" in both cases,
        // and after a fan-out the agents ARE the newest blocks, so the reason
        // the old rule existed is satisfied without pinning.
        return toolBlocks.count - 1
    }

    private var displayedIsAgent: Bool {
        guard let block = displayedBlock else { return false }
        if case .delegateTool = block.kind { return true }
        return false
    }

    /// [T-toolbar-published-read-uaf] Optional, and bounds-checked.
    ///
    /// `displayedIdx` ends with `toolBlocks.count - 1`, which is **-1** for an
    /// empty array — an out-of-bounds trap. The bar is only built when
    /// `allToolBlocks` is non-empty, but that check happens in the parent's
    /// body, one SwiftUI evaluation earlier: the agent loop can clear the
    /// blocks in between (`messages[i].blocks = …` / `removeAll` run from many
    /// non-isolated sites), and a `selectedIdx` captured before a truncation is
    /// stale in the same way.
    private var displayedBlock: AssistantBlock? {
        let idx = displayedIdx
        guard toolBlocks.indices.contains(idx) else { return nil }
        return toolBlocks[idx]
    }

    private static let thumbnailWidth: CGFloat = 100

    /// The snapshot corresponding to the currently displayed tool block, matched by ID.
    private var displayedSnapshot: ToolSnapshotItem? {
        guard let blockId = displayedBlock?.toolUseId else { return nil }
        return toolSnapshots.first(where: { $0.id == blockId })
    }

    /// [T-agent-follow-change] Each sub agent's current activity, keyed by its
    /// block id. Per-agent rather than one combined string, so the handler can
    /// tell WHICH agent moved — a combined one only says that something did.
    private var agentActivity: [UUID: String] {
        var out: [UUID: String] = [:]
        // [T-toolbar-published-read-uaf] Snapshot, not live `@Published` reads.
        for b in blockFacts {
            guard b.isAgent, let child = b.helperChildSessionId,
                  let info = activity.sessionToolInfo[child] else { continue }
            out[b.id] = "\(info.toolName):\(info.toolStatus)"
        }
        return out
    }

    /// Flattened for `onChange`, which needs an Equatable value.
    private var agentActivityStamp: String {
        agentActivity.map { "\($0.key):\($0.value)" }.sorted().joined(separator: "|")
    }

    /// [T-agent-follow-change] Point the bar at whichever sub agent just moved,
    /// subject to the 5-second window. Called on every activity change.
    private func followLatestAgent() {
        let current = agentActivity
        var moved: [UUID] = []
        for (id, act) in current where lastAgentActivity[id] != act {
            moved.append(id)
            lastAgentActivity[id] = act
        }
        guard let latest = moved.last else { return }
        // The agent already on the bar refreshing itself is not a switch — it
        // is that agent's own preview updating, which the tile does anyway.
        if latest == followedAgentId { return }
        let waited = Date().timeIntervalSince(followedAt)
        guard followedAgentId == nil || waited >= Self.agentFollowWindow else {
            // Held back. Remember it and re-check when the window closes: a
            // busy agent may not produce another event for a while, and
            // without this the bar could sit on a stale agent long after the
            // window expired.
            pendingAgentId = latest
            return
        }
        // [T-agent-follow-flash] A switch has to be worth a second of the
        // user's attention. Two sub agents of the same fan-out routinely carry
        // the SAME title — the model names them after the shared job, and the
        // bar shows that title — so moving between them repainted an
        // identical-looking bar every few seconds: on screen a one-second
        // flash with nothing to read, and no way to tell it apart from a
        // rendering glitch. Measured: two children alternating produced three
        // "switches" in six seconds, all rendering the same string.
        //
        // Compare what the user will actually SEE, not the identity behind it.
        // Same title means stay put; the tile keeps live-updating either way,
        // so nothing is lost by not moving the pointer.
        let currentTitle = followedAgentId.flatMap { id in
            toolBlocks.first { $0.id == id }?.toolSummary
        }
        let nextTitle = toolBlocks.first { $0.id == latest }?.toolSummary
        if let currentTitle, let nextTitle, currentTitle == nextTitle { return }
        followedAgentId = latest
        followedAt = Date()
        pendingAgentId = nil
    }

    /// [T-agent-follow-change] One second of follow bookkeeping: pick up any
    /// activity change since the last tick, then release a deferred switch
    /// whose window has closed. Ordered this way so a change arriving during
    /// the window is recorded before the release reads `pendingAgentId`.
    private func tickFollow() {
        dropStaleFollow()
        followLatestAgent()
        releaseDeferredFollow()
    }

    /// [T-agent-follow-release] Forget an agent that has stopped running (or
    /// whose block is gone), so the bar is free to follow someone else.
    ///
    /// Without this the id survives as `@State` for the life of the view and
    /// keeps winning in `displayedIdx`. Also clears a deferred switch that has
    /// gone stale while it waited out the window — releasing it later would
    /// point the bar at an agent that finished in the meantime.
    private func dropStaleFollow() {
        let stillRunning: (UUID) -> Bool = { id in
            guard let idx = toolBlocks.firstIndex(where: { $0.id == id }) else { return false }
            return Self.isActive(toolBlocks[idx])
        }
        if let followed = followedAgentId, !stillRunning(followed) {
            followedAgentId = nil
        }
        if let pending = pendingAgentId, !stillRunning(pending) {
            pendingAgentId = nil
        }
    }

    /// Release a switch the window deferred.
    private func releaseDeferredFollow() {
        guard let pending = pendingAgentId,
              Date().timeIntervalSince(followedAt) >= Self.agentFollowWindow else { return }
        let currentTitle = followedAgentId.flatMap { id in
            toolBlocks.first { $0.id == id }?.toolSummary
        }
        let nextTitle = toolBlocks.first { $0.id == pending }?.toolSummary
        // Same rule as the immediate path: a deferred switch that would look
        // identical is not worth taking when the window finally opens.
        if let currentTitle, let nextTitle, currentTitle == nextTitle {
            pendingAgentId = nil
            return
        }
        followedAgentId = pending
        followedAt = Date()
        pendingAgentId = nil
    }

    var body: some View {
        Group {
            if runningAgentIdx != nil {
                // 1 Hz re-evaluation while any agent runs. This tick is also
                // what drives the follow decision — see below.
                TimelineView(.periodic(from: Date(), by: 1)) { ctx in
                    collapsedBar(now: ctx.date)
                        .onChange(of: ctx.date) { _ in tickFollow() }
                }
            } else {
                collapsedBar(now: Date())
            }
        }
        // [T-agent-follow-change] The follow decision is driven by the 1 Hz
        // tick above, NOT by `onChange(of: agentActivityStamp)`.
        //
        // `onChange` compares its value only when the body it is attached to
        // is re-evaluated. This view observes the tracker, but the part of the
        // body that reads `sessionToolInfo` sits inside the TimelineView
        // closure, so SwiftUI had no reason to re-evaluate the OUTER body when
        // a child changed tools — and the comparison never happened. On device
        // that showed up starkly: two agents produced ~20 activity changes over
        // 92 seconds and `[follow]` fired exactly once. Meanwhile the tile kept
        // redrawing (it reads the tracker directly), so the preview text moved
        // while the choice of agent stood still — "the preview I just switched
        // to gets overwritten by another agent's summary".
        //
        // The tick already runs exactly when it matters (only while an agent is
        // running) and is immune to how SwiftUI schedules re-evaluation, so a
        // change can be missed for at most one second.
        .onAppear { lastAgentActivity = agentActivity }
        .sheet(isPresented: $expanded) {
            // [T-agent-tool-sheet-unified] An agent block is a tool like any
            // other: the same ToolLiveSheet (HelperDetailCard inside), which
            // links on to the full-screen transcript page.
            ToolLiveSheet(
                toolBlocks: toolBlocks,
                initialIdx: displayedIdx,
                toolSnapshots: toolSnapshots,
                browserPool: browserPool,
                onBrowserTakeover: onBrowserTakeover,
                onTakeoverDone: onTakeoverDone
            )
        }
        .sheet(item: $childToolTarget) { target in
            // The sub agent shares the parent's browser pool by design, so the
            // live page and its takeover control work unchanged here.
            ToolLiveSheet(
                toolBlocks: [target.block],
                initialIdx: 0,
                toolSnapshots: toolSnapshots,
                browserPool: browserPool,
                onBrowserTakeover: onBrowserTakeover,
                onTakeoverDone: onTakeoverDone
            )
            .environment(\.chatSessionId, target.sessionId)
        }
    }

    /// [T-agent-thumbnail-accent] A tile showing a tool the SUB AGENT is
    /// running opens that tool's live sheet — the child's browser page with its
    /// takeover control — instead of the parent's tool sheet.
    ///
    /// The tile is already displaying the child's work, so opening the parent's
    /// sheet showed a different tool than the one just tapped. The child's
    /// blocks live in its own view model, which is why this reaches through
    /// ViewModelCache rather than using `toolBlocks`.
    private func openTool(_ block: AssistantBlock) {
        guard case .delegateTool = block.kind else {
            withAnimation { expanded = true }
            return
        }
        let log = AppLogger(category: "ToolLiveSheet")
        guard let childId = block.helperChildSessionId else {
            log.info("[agent-tap] no childId — opening the agent sheet")
            withAnimation { expanded = true }
            return
        }
        guard let childVM = ViewModelCache.shared.get(for: childId) else {
            log.info("[agent-tap] child \(childId.prefix(8)) has no live VM — opening the agent sheet")
            withAnimation { expanded = true }
            return
        }
        guard let live = liveChildToolBlock(childSessionId: childId) else {
            // Between tools, starting up, or finished: the agent's own sheet is
            // the right destination then — there is no tool to show.
            log.info("[agent-tap] child \(childId.prefix(8)) has no running tool — opening the agent sheet")
            withAnimation { expanded = true }
            return
        }
        log.info("[agent-tap] child \(childId.prefix(8)) running a tool — opening its live view")
        childToolTarget = ChildToolTarget(sessionId: childId, block: live.block)
    }

}

/// [T-agent-inner-tool-front] The tool block a sub agent is executing right
/// now, or nil when it is between tools / starting / finished.
///
/// Scans newest-first: `.reversed()` on `messages` alone still walks each
/// message's blocks forward, which would return the FIRST active block of the
/// newest message rather than the one the agent is on now.
@MainActor
func liveChildToolBlock(childSessionId: String?) -> (block: AssistantBlock, snapshot: ToolSnapshotItem?)? {
    guard let childSessionId, let vm = ViewModelCache.shared.get(for: childSessionId) else { return nil }
    for msg in vm.messages.reversed() {
        for b in msg.blocks.reversed() where b.toolStatus != nil {
            switch b.toolStatus {
            case .streaming, .running:
                // The child's own snapshots — the parent's array is keyed by
                // the parent's tool ids and would never match.
                return (b, vm.toolSnapshots.first { $0.id == b.toolUseId })
            default: continue
            }
        }
    }
    return nil
}

extension FloatingToolBar {
    /// The collapsed bar, evaluated against an explicit clock.
    ///
    /// [T-toolbar-collapsed-bounds] Bounds-checked like `displayedBlock`: the
    /// parent only builds the bar for a non-empty list, but that check is one
    /// SwiftUI evaluation earlier, and the agent loop can clear the blocks in
    /// between. An out-of-range index renders nothing instead of trapping.
    fileprivate func collapsedBar(now: Date) -> some View {
        var copy = self
        copy.now = now
        let idx = copy.displayedIdx
        return Group {
            if copy.toolBlocks.indices.contains(idx) {
                collapsedBarContent(block: copy.toolBlocks[idx], idx: idx)
            }
        }
    }

    private func collapsedBarContent(block: AssistantBlock, idx: Int) -> some View {
        ZStack(alignment: .bottomLeading) {
            ToolStatusBar(
                block: block,
                toolBlocks: toolBlocks,
                displayedIdx: idx,
                expanded: $expanded,
                selectedIdx: $selectedIdx,
                selectedAt: $selectedAt,
                leadingInset: toolPreviewEnabled ? Self.thumbnailWidth + 18 : 0
            )

            if toolPreviewEnabled {
                ToolPreviewThumbnail(
                    block: block,
                    snapshot: toolSnapshots.first(where: { $0.id == block.toolUseId }),
                    browserPool: browserPool,
                    onTap: { openTool(block) }
                )
                .offset(x: 10)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: idx)
    }
}

/// Tappable URL capsule that copies to clipboard and shows a brief "Copied" tooltip.
private struct CopyableURLCapsule: View {
    let url: String
    @State private var showCopied = false

    var body: some View {
        Text(showCopied ? "Copied!" : url)
            .font(.system(size: 12, weight: showCopied ? .semibold : .regular))
            .foregroundStyle(showCopied ? Color.white : Color(white: 0.35))
            .lineLimit(1)
            .truncationMode(.middle)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(showCopied ? Color.green : Color(white: 0.85))
            .clipShape(Capsule())
            .animation(.easeInOut(duration: 0.2), value: showCopied)
            .onTapGesture {
                UIPasteboard.general.string = url
                showCopied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    showCopied = false
                }
            }
    }
}

/// Full-screen live sheet — layout with a nav bar, live content, and bottom status/navigation.
struct ToolLiveSheet: View {
    let toolBlocks: [AssistantBlock]
    @State var currentIdx: Int
    var toolSnapshots: [ToolSnapshotItem] = []
    var browserPool: BrowserTabPool?
    var onBrowserTakeover: (() -> Void)?
    var onTakeoverDone: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.chatSessionId) private var sessionId

    /// [T-sub-agents-resume] True for an agent block whose run the app lost —
    /// the payload still says "running" but no job is alive to back it.
    private func isInterruptedAgent(_ b: AssistantBlock) -> Bool {
        guard case .delegateTool = b.kind else { return false }
        if case .finished(let status, _, _, _, _, _, _, _) = HelperBlockInfo.parse(b).phase {
            return status == "interrupted"
        }
        return false
    }

    @State private var browserSnapshot: UIImage?
    @State private var snapshotTimer: Timer?
    @State private var showTerminal = false

    @State private var navCopyDone = false
    /// Incremented when the current block publishes changes, forcing SwiftUI to re-render.
    @State private var blockUpdateTick = 0
    /// Unified secondary-sheet state. A single `.sheet(item:)` routes both the
    /// browser takeover and the tapped-URL link preview through one modifier,
    /// sidestepping SwiftUI's "only one .sheet per view" limitation when
    /// ToolLiveSheet is itself hosted inside a sheet.
    @State private var activeSheet: ActiveSecondarySheet?
    /// The sheet that was last presented through `activeSheet`, remembered so
    /// `onDismiss` (which runs after `activeSheet` is already nil) can tell
    /// which one closed. [T-agent-transcript-halfsheet]
    @State private var lastPresentedSheet: ActiveSecondarySheet?

    /// Describes which secondary sheet is currently presented on top of
    /// ToolLiveSheet. Cases are mutually exclusive by construction — each is
    /// opened by a different, single tap.
    enum ActiveSecondarySheet: Identifiable {
        case takeoverBrowser
        case linkPreview(URL)
        /// [T-agent-transcript-halfsheet] The agent's live conversation,
        /// stacked on top of this tool sheet so closing it comes back here.
        case agentTranscript(HelperSheetTarget)

        var id: String {
            switch self {
            case .takeoverBrowser: return "takeoverBrowser"
            case .linkPreview(let url): return "linkPreview:\(url.absoluteString)"
            case .agentTranscript(let t): return "agentTranscript:\(t.id)"
            }
        }
    }
    @StateObject private var resourceMonitor = SystemResourceMonitor()

    /// [T-ios-tool-result-lazy-render] Number of 40-line chunks currently
    /// revealed in the non-live (detail) text view. Large tool results
    /// (e.g. a big memory_get / file read) used to render every chunk eagerly
    /// inside a plain VStack+ForEach — a ScrollView does NOT virtualize a
    /// VStack, so all N Text views laid out at once and the sheet janked on
    /// open. We now reveal an initial batch (~200 lines / 10KB, whichever is
    /// fewer) and append `lazyRenderBatchChunks` more each time the user
    /// reaches the bottom or taps "Load more". Reset to the initial batch
    /// whenever the displayed block changes. Live streaming output is
    /// unaffected (it already caps via liveChunkedLines).
    @State private var revealedChunkCount: Int = 0
    /// The block id `revealedChunkCount` was last initialized for, so switching
    /// blocks (next/prev tool) re-collapses to the initial batch.
    @State private var revealedForBlockId: UUID?

    /// Aliases into the shared tuning (see LazyRenderTuning) so the sheet's
    /// own textContent window and LazyRevealChunks can never drift apart.
    private static let lazyRenderChunkLines = LazyRenderTuning.chunkLines
    private static let lazyRenderInitialChunks = LazyRenderTuning.initialChunks
    private static let lazyRenderBatchChunks = LazyRenderTuning.batchChunks
    private static let lazyRenderInitialByteCap = LazyRenderTuning.initialByteCap

    init(toolBlocks: [AssistantBlock], initialIdx: Int, toolSnapshots: [ToolSnapshotItem] = [], browserPool: BrowserTabPool?,
         onBrowserTakeover: (() -> Void)? = nil, onTakeoverDone: (() -> Void)? = nil) {
        self.toolBlocks = toolBlocks
        self._currentIdx = State(initialValue: initialIdx)
        self.toolSnapshots = toolSnapshots
        self.browserPool = browserPool
        self.onBrowserTakeover = onBrowserTakeover
        self.onTakeoverDone = onTakeoverDone
    }

    /// Get snapshot for the current block (matched by toolUseId).
    ///
    /// [T-ios-toollivesheet-newest-snapshot] `last`, not `first`: the array can
    /// hold several snapshots for one tool_use id (a re-run, or a tool that
    /// snapshots more than once), and they are appended in order. Taking the
    /// first handed back the OLDEST — so when a tool finished and the view
    /// switched from live output to the snapshot, the content could visibly
    /// revert to an earlier run.
    private var currentSnapshot: ToolSnapshotItem? {
        guard let blockId = block.toolUseId else { return nil }
        return toolSnapshots.last(where: { $0.id == blockId })
    }

    private var block: AssistantBlock {
        let idx = min(currentIdx, toolBlocks.count - 1)
        guard idx >= 0 else { return AssistantBlock(kind: .text, content: "") }
        return toolBlocks[idx]
    }

    private var isLive: Bool {
        guard !toolBlocks.isEmpty else { return false }
        if case .streaming = block.toolStatus { return true }
        if case .running = block.toolStatus { return true }
        return false
    }

    /// [T-step-timestamp v2 aa8b1128] Human-readable elapsed-or-final
    /// duration for the current block. Pulls block.toolDuration when the
    /// tool has finished (set in runStreamProcessing on tool result),
    /// otherwise computes live elapsed from `started`. Format matches
    /// the cross-platform contract:
    ///   < 60s        → "3s"
    ///   60-3600s     → "2m30s"
    ///   ≥ 3600s      → "1h12m"
    /// While still running the live value is suffixed "…".
    private func durationLabel(started: Date) -> String {
        let secs: TimeInterval
        let stillRunning: Bool
        if let dur = block.toolDuration {
            secs = dur
            stillRunning = false
        } else {
            secs = max(0, Date().timeIntervalSince(started))
            stillRunning = isLive
        }
        return MinisStepTimestampFormatter.duration(seconds: secs, stillRunning: stillRunning)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top nav bar
            sheetNavBar

            Divider().foregroundStyle(Color(white: 0.2))

            // Content area — `blockUpdateTick` dependency ensures live refresh
            let _ = blockUpdateTick
            liveContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Bottom bar: tool info + navigation
            bottomBar
        }
        // On iPad `.sheet` is presented as a form sheet that sizes itself to
        // the content's ideal size. The inner VStack has no intrinsic height
        // (liveContent uses `maxHeight: .infinity`), so without an explicit
        // size the form sheet clamps to a default and clips the nav / bottom
        // bars. Pin the body to the full form sheet bounds so SwiftUI can
        // distribute space between the fixed chrome and the flexible content.
        // On iPhone `.infinity` just fills the sheet detent as before.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(UIColor.systemGroupedBackground))
        .onAppear {
            startBrowserTimer()
            if isLive && isCurrentShell { resourceMonitor.start() }
            MinisOpenURLBroker.shared.toolSheetVisible = true
        }
        .onDisappear {
            stopBrowserTimer()
            resourceMonitor.stop()
            MinisOpenURLBroker.shared.toolSheetVisible = false
        }
        // Auto-present an in-app browser preview when a shell tool emits an
        // OSC MinisOpenURL marker while this sheet is visible.
        //
        // Only intercepts http/https/about: URLs — `minis://` chat-resource
        // previews (images, markdown, QuickLook, ...) are left for
        // AIChatView to handle since this sheet cannot host file previews.
        //
        // `.dropFirst()` skips the current value that `@Published` delivers
        // to new subscribers on first attach — without it, opening the
        // sheet after a previous OSC capture would immediately re-present
        // a stale URL.
        .onReceive(MinisOpenURLBroker.shared.$pendingURL.dropFirst().compactMap { $0 }) { url in
            guard MinisOpenURLBroker.isWebScheme(url.scheme) else { return }
            activeSheet = .linkPreview(url)
            MinisOpenURLBroker.shared.consume()
        }
        .onChange(of: isLive) { live in
            if live && isCurrentShell { resourceMonitor.start() } else { resourceMonitor.stop() }
        }
        .onReceive(block.objectWillChange) { _ in
            blockUpdateTick += 1
        }
        .presentationDragIndicator(.hidden)
        // Tapping a URL in shell output (underlined via attributedShellLine)
        // routes through `activeSheet` so it shares one `.sheet(item:)`
        // modifier with the browser takeover below.
        .environment(\.openURL, OpenURLAction { url in
            if let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                activeSheet = .linkPreview(url)
                return .handled
            }
            return .systemAction
        })
        // Unified secondary-sheet presentation. SwiftUI only reliably presents
        // one `.sheet` per view, and ToolLiveSheet is itself hosted inside a
        // sheet — so we collapse the browser takeover and the URL preview
        // into a single `.sheet(item:)` driven by `activeSheet`. The two
        // cases are mutually exclusive by construction (takeover = button tap,
        // link preview = URL tap).
        .sheet(item: $activeSheet, onDismiss: {
            // [T-agent-transcript-halfsheet] `onTakeoverDone` resumes a paused
            // browser-takeover continuation in the agent loop. It must not fire
            // when the agent transcript closes — nothing was taken over, and
            // resuming would release a continuation that belongs to a real
            // takeover. The link preview keeps its existing behaviour.
            let closed = lastPresentedSheet
            lastPresentedSheet = nil
            if case .agentTranscript = closed { return }
            onTakeoverDone?()
        }) { sheet in
            switch sheet {
            case .takeoverBrowser:
                if let pool = browserPool {
                    BrowserSheetView(pool: pool, isAgentBusy: false)
                }
            case .linkPreview(let url):
                MinisLinkPreviewView(url: url, browserPool: browserPool)
            case .agentTranscript(let target):
                HelperTranscriptPage(target: target)
                    .helperTranscriptSheetStyle()
            }
        }
        .onChange(of: activeSheet?.id) { _ in
            if let s = activeSheet { lastPresentedSheet = s }
        }
    }

    private var isCurrentShell: Bool {
        if case .shellTool = block.kind { return true }
        return false
    }

    // MARK: - Top nav bar: X + title + device icon

    private var sheetNavBar: some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ChatColors.primaryText)
                        .frame(width: 32, height: 32)
                        .background(ChatColors.secondaryBg)
                        .clipShape(Circle())
                }

                Spacer()

                // [T-step-timestamp v3 aa8b1128] Sheet nav bar reverted to a
                // single-line title. The HH:mm:ss · duration line was moved
                // out to the step pill's trailing column (under the
                // elapsed-duration "5s" text) so it lives next to where the
                // user is already scanning timing info.
                Text("Minis Computer")
                    .font(.system(size: 15, weight: .semibold))

                Spacer()

                if isLive, onBrowserTakeover != nil, case .browserTool = block.kind {
                    Button {
                        onBrowserTakeover?()
                        activeSheet = .takeoverBrowser
                    } label: {
                        ZStack {
                            Image(systemName: "hand.point.up.left")
                                .font(.system(size: 13, weight: .semibold))
                                .offset(x: -2, y: -1)
                            Image(systemName: "globe")
                                .font(.system(size: 8, weight: .bold))
                                .offset(x: 5, y: 5)
                        }
                        .foregroundStyle(ChatColors.primaryText)
                        .frame(width: 32, height: 32)
                        .background(ChatColors.secondaryBg)
                        .clipShape(Circle())
                    }
                } else if case .browserTool = block.kind, browserPool != nil {
                    Button { activeSheet = .takeoverBrowser } label: {
                        toolIcon
                            .font(.system(size: 14))
                            .foregroundStyle(ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                } else if case .fileWriteTool = block.kind {
                    Button {
                        let text = block.streamingFileContent
                            ?? extractWriteContent()
                            ?? block.content
                        UIPasteboard.general.string = text
                        navCopyDone = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { navCopyDone = false }
                    } label: {
                        Image(systemName: navCopyDone ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 14))
                            .foregroundStyle(navCopyDone ? Color.green : ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                } else if case .fileReadTool = block.kind {
                    Button {
                        UIPasteboard.general.string = block.content
                        navCopyDone = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { navCopyDone = false }
                    } label: {
                        Image(systemName: navCopyDone ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 14))
                            .foregroundStyle(navCopyDone ? Color.green : ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                } else if case .fileEditTool = block.kind {
                    Button {
                        let editStrings = extractEditStrings()
                        let text = editStrings.map { "OLD:\n\($0.oldString)\n\nNEW:\n\($0.newString)" } ?? block.content
                        UIPasteboard.general.string = text
                        navCopyDone = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { navCopyDone = false }
                    } label: {
                        Image(systemName: navCopyDone ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 14))
                            .foregroundStyle(navCopyDone ? Color.green : ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                } else if case .readImageTool = block.kind {
                    Button {
                        if let path = block.imageFilePath, let img = UIImage(contentsOfFile: path) {
                            UIPasteboard.general.image = img
                        }
                        navCopyDone = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { navCopyDone = false }
                    } label: {
                        Image(systemName: navCopyDone ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 14))
                            .foregroundStyle(navCopyDone ? Color.green : ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                } else if case .memoryTool = block.kind {
                    Button {
                        let text = memoryWriteContentFromArgs() ?? block.content
                        UIPasteboard.general.string = text
                        navCopyDone = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { navCopyDone = false }
                    } label: {
                        Image(systemName: navCopyDone ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 14))
                            .foregroundStyle(navCopyDone ? Color.green : ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                } else if case .delegateTool = block.kind {
                    // [T-sub-agents-resume] Only for a run the app lost when it
                    // was killed. A finished / cancelled / timed-out agent is
                    // not offered this: those ended by decision, and a button
                    // inviting the user around that decision would be wrong.
                    if isInterruptedAgent(block) {
                        Button {
                            let childId = block.helperChildSessionId
                                ?? (AIChatViewModel.parseDelegateResult(block.content)?["child_session_id"] as? String)
                            guard let childId, let sid = sessionId,
                                  let parent = ViewModelCache.shared.get(for: sid) else { return }
                            AppLogger(category: "ToolLiveSheet").info("[agent] resume tapped child=\(childId.prefix(8))")
                            dismiss()
                            Task { @MainActor in
                                if let why = await parent.resumeInterruptedHelper(childSessionId: childId) {
                                    AppLogger(category: "ToolLiveSheet").warning("[agent] resume refused — \(why)")
                                }
                            }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(HelperAccent.color)
                                .frame(width: 32, height: 32)
                                .background(ChatColors.secondaryBg)
                                .clipShape(Circle())
                        }
                        .accessibilityLabel(Text("Resume sub agent", comment: "VoiceOver label for the button that restarts an interrupted sub agent"))
                    }
                    // [T-agent-detail-buttons] An agent block has no shell
                    // command to prefill; its top-right action is the child's
                    // live conversation, behind a chat-bubble glyph.
                    Button {
                        let childId = block.helperChildSessionId
                            ?? (AIChatViewModel.parseDelegateResult(block.content)?["child_session_id"] as? String)
                        AppLogger(category: "ToolLiveSheet").info("[agent] transcript button tapped child=\(childId?.prefix(8) ?? "nil")")
                        guard let childId else { return }
                        // [T-agent-transcript-halfsheet] Stack the transcript ON
                        // TOP of this tool sheet, so closing it returns here.
                        //
                        // This used to dismiss this sheet and have the chat page
                        // present a full-screen cover, which is exactly what
                        // users hit: going "back" landed on the chat, not on the
                        // tool sheet they had opened it from.
                        //
                        // The earlier nested attempt failed because the cover was
                        // attached to a view inside the per-block content, which
                        // is torn down whenever a running agent's block
                        // re-renders. `activeSheet` is ToolLiveSheet's own
                        // root-level @State and its `.sheet(item:)` sits on the
                        // root of the body, so a block re-render cannot reach it.
                        let title = block.toolSummary ?? block.toolDescription
                        activeSheet = .agentTranscript(HelperSheetTarget(id: childId, title: title))
                    } label: {
                        Image(systemName: "text.bubble")
                            .font(.system(size: 14))
                            .foregroundStyle(ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                    .accessibilityLabel(AppLocalized("Open live agent conversation"))
                    .accessibilityIdentifier("agentTranscriptButton")
                } else {
                    Button { showTerminal = true } label: {
                        toolIcon
                            .font(.system(size: 14))
                            .foregroundStyle(ChatColors.primaryText)
                            .frame(width: 32, height: 32)
                            .background(ChatColors.secondaryBg)
                            .clipShape(Circle())
                    }
                }
            }

        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(UIColor.systemBackground))
        .fullScreenCover(isPresented: $showTerminal) {
            NavigationStack {
                // Pre-fill the shell command the tool is currently running,
                // WITHOUT a trailing newline — the user can review/edit and
                // press Enter themselves (intentional, not auto-run).
                ISHTerminalView(
                    sessionId: sessionId,
                    showCloseButton: true,
                    initCommand: terminalPrefillCommand
                )
            }
        }
    }

    /// Command to pre-fill in the terminal when the top-right terminal button
    /// is tapped. Only shell_execute tools carry a runnable command; all other
    /// tool kinds fall through to no pre-fill so the terminal just opens on a
    /// fresh prompt.
    private var terminalPrefillCommand: String? {
        if case .shellTool(let cmd) = block.kind, !cmd.isEmpty {
            return cmd
        }
        return nil
    }

    /// Tool name + truncated parameters for the nav bar subtitle.
    private var navToolDetail: String {
        switch block.kind {
        case .text, .thinking:
            return ""
        case .shellTool(let cmd):
            return "shell_execute(\(truncateParam(cmd)))"
        case .fileReadTool(let path):
            return "file_read(\(truncateParam(path)))"
        case .fileWriteTool(let path):
            return "file_write(\(truncateParam(path)))"
        case .fileEditTool(let path):
            return "file_edit(\(truncateParam(path)))"
        case .browserTool(let action):
            return "browser_use(\(truncateParam(action)))"
        case .readImageTool(let path):
            return "read_image(\(truncateParam(path)))"
        case .memoryTool(let action):
            return "\(truncateParam(action))"
        case .delegateTool(let title):
            return "delegate_task(\(truncateParam(title)))"
        case .info:
            return ""
        }
    }

    private func truncateParam(_ s: String) -> String {
        s.count > 100 ? String(s.prefix(100)) + "..." : s
    }

    @ViewBuilder
    private var toolIcon: some View {
        switch block.kind {
        case .shellTool: Image(systemName: "terminal")
        case .fileReadTool: Image(systemName: "doc.text")
        case .fileWriteTool: Image(systemName: "doc.text.fill")
        case .fileEditTool: Image(systemName: "square.and.pencil")
        case .browserTool: Image(systemName: "globe")
        case .readImageTool: Image(systemName: "photo")
        case .memoryTool: Image(systemName: "brain.head.profile")
        case .delegateTool: Image(systemName: HelperAccent.icon)
        case .info: Image(systemName: "arrow.triangle.2.circlepath")
        case .text: Image(systemName: "text.alignleft")
        case .thinking: Image("ThinkingIcon")
        }
    }

    // MARK: - Live content area

    @ViewBuilder
    private var liveContent: some View {
        if isLive {
            // Currently executing — show live content
            switch block.kind {
            case .browserTool: browserContent
            case .fileWriteTool:
                let liveText = block.streamingFileContent.flatMap { $0.isEmpty ? nil : $0 } ?? block.content
                fileEditorContent(liveText, isStreaming: true)
            case .fileEditTool:
                fileDiffContent(isStreaming: true)
            case .fileReadTool: fileEditorContent(block.content)
            case .memoryTool:
                let content = memoryWriteContentFromArgs() ?? block.content
                memoryEditorContent(content, action: memoryActionName(), isStreaming: true)
            case .delegateTool:
                HelperDetailCard(block: block)
            default: textContent
            }
        } else if case .delegateTool = block.kind {
            // A helper's persisted snapshot is the result JSON — never show it raw.
            HelperDetailCard(block: block)
        } else if let snap = currentSnapshot {
            // Completed with a persisted snapshot — render it
            snapshotContent(snap)
        } else {
            // Fallback to block content
            switch block.kind {
            case .browserTool:
                if let img = block.imageFilePath.flatMap({ UIImage(contentsOfFile: $0) }) {
                    browserResultContent(image: img, text: block.content)
                } else {
                    browserTextResultContent(block.content)
                }
            case .readImageTool:
                if let img = block.imageFilePath.flatMap({ UIImage(contentsOfFile: $0) }) {
                    browserResultContent(image: img, text: block.content)
                } else {
                    textContent
                }
            case .fileEditTool:
                fileDiffContent()
            case .fileWriteTool(let path), .fileReadTool(let path):
                // Try reading actual file content from disk
                let hostURL = RootfsManager.shared.dataPath.appendingPathComponent(String(path.dropFirst()))
                let diskContent = (try? String(contentsOf: hostURL, encoding: .utf8)) ?? ""
                if !diskContent.isEmpty {
                    fileEditorContent(diskContent, toolResult: block.content)
                } else {
                    fileEditorContent(block.content)
                }
            case .memoryTool:
                let content = memoryWriteContentFromArgs() ?? block.content
                memoryEditorContent(content, action: memoryActionName(), resultText: block.content)
            case .delegateTool:
                HelperDetailCard(block: block)
            default:
                textContent
            }
        }
    }

    /// Render a persisted snapshot (image or text).
    @ViewBuilder
    private func snapshotContent(_ item: ToolSnapshotItem) -> some View {
        switch item.snapshot.type {
        case .image:
            if let ref = item.snapshot.mediaRef {
                let url = item.mediaResolver(ref)
                if let img = UIImage(contentsOfFile: url.path) {
                    browserResultContent(image: img, text: block.content)
                } else {
                    textContent
                }
            } else {
                textContent
            }
        case .text:
            if case .fileWriteTool = block.kind, let text = item.snapshot.text, !text.isEmpty {
                fileEditorContent(text, toolResult: block.content)
            } else if case .fileEditTool = block.kind {
                fileDiffContent()
            } else if case .fileReadTool = block.kind, let text = item.snapshot.text, !text.isEmpty {
                fileEditorContent(text)
            } else if case .memoryTool = block.kind {
                let content = memoryWriteContentFromArgs() ?? item.snapshot.text ?? block.content
                memoryEditorContent(content, action: memoryActionName(), resultText: block.content)
            } else if case .browserTool = block.kind, let text = item.snapshot.text, !text.isEmpty {
                browserTextResultContent(text)
            } else if let text = item.snapshot.text, !text.isEmpty {
                snapshotTextContent(text)
            } else {
                textContent
            }
        }
    }

    /// Combined browser result: action capsules + screenshot image + result text card.
    private func browserResultContent(image: UIImage, text: String) -> some View {
        let action: String = {
            if case .browserTool(let a) = block.kind { return a }
            return ""
        }()
        let url: String = resolvedBrowserURL
        let script: String? = browserScriptFromArgs()

        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // Action + URL capsules
                if !action.isEmpty || !url.isEmpty {
                    HStack(spacing: 8) {
                        if !action.isEmpty {
                            Text(action)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(Color.blue)
                                .clipShape(Capsule())
                        }
                        if !url.isEmpty {
                            CopyableURLCapsule(url: url)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 12)
                }

                // JS script card for execute_js
                if let script {
                    jsScriptCard(script)
                }

                // Screenshot — shadowed card matching read_image style.
                // Use .scaledToFit + frame(maxWidth:.infinity) so the image
                // shrinks to the container width regardless of its intrinsic
                // pixel size; previously a GeometryReader-based explicit
                // .frame(width:height:) sometimes ended up applying a
                // proposed-size that was larger than the visible viewport,
                // leaving the right edge clipped.
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10)
                        .stroke(Color(UIColor.separator).opacity(0.5), lineWidth: 0.5))
                    .shadow(color: .black.opacity(0.18), radius: 8, x: 0, y: 3)
                    .contextMenu {
                        Button { UIPasteboard.general.image = image } label: {
                            Label("Copy Image", systemImage: "doc.on.doc")
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, action.isEmpty && url.isEmpty ? 12 : 0)

                    // Result text — file editor style card
                    if !text.isEmpty {
                        VStack(spacing: 0) {
                            // Title bar
                            HStack(spacing: 6) {
                                Image(systemName: "globe")
                                    .font(.system(size: 12))
                                    .foregroundStyle(Color(UIColor.secondaryLabel))
                                Text("Result")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(Color(UIColor.label))
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.92, alpha: 1) }))

                            Divider()

                            // Result content — chunked + windowed, and now
                            // sanitized: this path used to feed raw text
                            // (ANSI codes, unbounded line length) straight
                            // into CoreText.
                            VStack(alignment: .leading, spacing: 0) {
                                LazyRevealChunks(chunks: Self.chunkedLines(text), resetKey: block.id) { t in
                                    Text(t)
                                        .font(.system(size: 13, design: .monospaced))
                                        .foregroundStyle(Color(UIColor.label))
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.horizontal, 14)
                                }
                            }
                            .textSelection(.enabled)
                            .padding(.vertical, 14)
                        }
                        .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.10, alpha: 1) : UIColor(white: 0.94, alpha: 1) }))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                        .padding(.horizontal, 12)
                    }
            }
            .padding(.bottom, 16)
        }
    }

    /// Browser text-only result: action + URL capsules + file-editor-style result card.
    private func browserTextResultContent(_ text: String) -> some View {
        let action: String = {
            if case .browserTool(let a) = block.kind { return a }
            return ""
        }()
        let url: String = resolvedBrowserURL
        let script: String? = browserScriptFromArgs()

        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // Action + URL capsules
                if !action.isEmpty || !url.isEmpty {
                    HStack(spacing: 8) {
                        if !action.isEmpty {
                            Text(action)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(Color.blue)
                                .clipShape(Capsule())
                        }
                        if !url.isEmpty {
                            CopyableURLCapsule(url: url)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 12)
                }

                // JS script card for execute_js
                if let script {
                    jsScriptCard(script)
                }

                // Result text — file editor style card
                if !text.isEmpty {
                    VStack(spacing: 0) {
                        // Title bar
                        HStack(spacing: 6) {
                            Image(systemName: "globe")
                                .font(.system(size: 12))
                                .foregroundStyle(Color(UIColor.secondaryLabel))
                            Text("Result")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(Color(UIColor.label))
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.92, alpha: 1) }))

                        Divider()

                        // Result content — chunked + windowed (was one Text
                        // holding the whole result).
                        VStack(alignment: .leading, spacing: 0) {
                            LazyRevealChunks(chunks: Self.chunkedLines(text), resetKey: block.id) { t in
                                Text(t)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color(UIColor.label))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                            }
                        }
                        .textSelection(.enabled)
                        .padding(.vertical, 14)
                    }
                    .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.10, alpha: 1) : UIColor(white: 0.94, alpha: 1) }))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                    .padding(.horizontal, 12)
                }
            }
            .padding(.bottom, 16)
        }
    }

    /// Zoomable image: fits width, supports pinch-to-zoom and drag.
    private func zoomableImage(_ img: UIImage) -> some View {
        ZoomableImageView(image: img)
    }

    /// Rendered snapshot text (last N lines of tool output).
    private func snapshotTextContent(_ text: String) -> some View {
        GeometryReader { geo in
            let cardWidth = geo.size.width - 24 // 12pt horizontal padding each side
            let cardMinHeight = cardWidth * 3.0 / 4.0
            Group {
                if case .shellTool(let cmd) = block.kind {
                    // Shell: command header + chunked output. Chunking avoids the
                    // SwiftUI `Text` soft-truncation ceiling on long output.
                    let cmdPrefix = "$ \(cmd)\n"
                    let output = text.hasPrefix(cmdPrefix) ? String(text.dropFirst(cmdPrefix.count)) : text
                    let chunks = Self.chunkedLines(output.isEmpty ? " " : output)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            Text("$ \(cmd)")
                                .font(.system(size: 13, weight: .bold, design: .monospaced))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 14)
                                .padding(.top, 14)

                            LazyRevealChunks(chunks: chunks, resetKey: block.id) { text in
                                Text(attributedShellLine(text))
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundColor(accentColor)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                            }
                        }
                        .textSelection(.enabled)
                        .padding(.bottom, 14)
                        .frame(maxWidth: .infinity, minHeight: cardMinHeight, alignment: .topLeading)
                        .background(Color.black)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                        .padding(.horizontal, 12)
                        .padding(.top, 12)
                        .padding(.bottom, 16)
                    }
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            if !contentHeader.isEmpty {
                                Text(contentHeader)
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(ChatColors.secondaryText)
                                    .padding(.horizontal, 16)
                                    .padding(.top, 16)
                                    .padding(.bottom, 8)
                                    .frame(maxWidth: .infinity, alignment: .center)
                            }

                            // [T-ios-tool-sheet-watchdog] Chunked + windowed:
                            // this generic snapshot path is where any tool
                            // without a dedicated renderer lands, so a 50KB
                            // subagent result used to be one giant Text.
                            VStack(alignment: .leading, spacing: 0) {
                                LazyRevealChunks(chunks: Self.chunkedLines(text.isEmpty ? " " : text), resetKey: block.id) { t in
                                    Text(t)
                                        .font(.system(size: 13, design: .monospaced))
                                        .foregroundStyle(accentColor)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.horizontal, 14)
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: cardMinHeight, alignment: .topLeading)
                            .textSelection(.enabled)
                            .padding(.vertical, 14)
                            .background(Color(white: 0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .padding(.horizontal, 12)
                        }
                        .padding(.bottom, 16)
                    }
                }
            }
        }
    }

    /// Minimal info stub for file_read in snapshot view — avoids duplicating content already shown in chat.
    private func fileReadInfoView(fileName: String, charCount: Int) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "doc.text")
                .font(.system(size: 12))
                .foregroundStyle(Color(UIColor.secondaryLabel))
            Text(fileName)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Color(UIColor.label))
                .lineLimit(1)
            Text("(\(Self.formatCharCount(charCount)))")
                .font(.system(size: 11))
                .foregroundStyle(Color(UIColor.tertiaryLabel))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    /// Editor-style preview for file_read / file_write tool results.
    /// - Parameter fileContent: The actual file content to display in the editor.
    /// - Parameter toolResult: Optional tool result info (minis_url etc.) shown below the editor.
    // MARK: - File Edit Diff Helpers

    /// Extracts `old_string` and `new_string` from the tool input args JSON for file_edit.
    private func extractEditStrings() -> (oldString: String, newString: String)? {
        guard let json = block.toolInputArgs,
              let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let old = dict["old_string"] as? String,
              let new = dict["new_string"] as? String else { return nil }
        return (old, new)
    }

    /// Extract the written file content from toolInputArgs for file_write blocks.
    private func extractWriteContent() -> String? {
        guard let json = block.toolInputArgs,
              let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = dict["content"] as? String else { return nil }
        return content
    }

    /// Diff-style card for file_edit results showing removed lines (red) and added lines (green).
    private func fileDiffContent(isStreaming: Bool = false) -> some View {
        let filePath: String = {
            if case .fileEditTool(let p) = block.kind { return p }
            return "file"
        }()
        let fileName = (filePath as NSString).lastPathComponent
        let editStrings = extractEditStrings()
        let oldText = editStrings?.oldString ?? block.streamingFileContent ?? ""
        let newText = editStrings?.newString ?? ""
        let hasEditData = editStrings != nil || (block.streamingFileContent != nil && !(block.streamingFileContent?.isEmpty ?? true))
        let toolResult = isStreaming ? nil : block.content

        // Compute size label for title bar (just byte size, not the result message)
        let sizeLabel: String = {
            if isStreaming { return "streaming…" }
            let totalBytes = oldText.utf8.count + newText.utf8.count
            return Self.formatBytes(totalBytes)
        }()

        // Parse replacement count from tool result (e.g. "Edited /root/test.txt (1 replacement, 234 bytes)")
        let resultDetail: String? = {
            guard let result = toolResult, !result.isEmpty else { return nil }
            // Extract parenthesized detail like "(1 replacement, 234 bytes)"
            if let range = result.range(of: #"\(([^)]+)\)"#, options: .regularExpression) {
                return String(result[range])
            }
            return nil
        }()

        return Group {
            if hasEditData {
                GeometryReader { geo in
                    let cardWidth = geo.size.width - 24 // 12pt horizontal padding each side
                    let cardMinHeight = cardWidth * 3.0 / 4.0
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            // Diff card
                            VStack(spacing: 0) {
                            // Title bar — filename + byte size only
                            HStack(spacing: 6) {
                                Image(systemName: "square.and.pencil")
                                    .font(.system(size: 12))
                                    .foregroundStyle(.orange)
                                Text(fileName)
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(Color(UIColor.label))
                                    .lineLimit(1)
                                Text("(\(sizeLabel))")
                                    .font(.system(size: 11))
                                    .foregroundStyle(isStreaming ? Color.orange.opacity(0.8) : Color(UIColor.tertiaryLabel))
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.92, alpha: 1) }))

                            Divider()

                            // Diff body — lines are flush against each other
                            // and against the title divider so the red/green
                            // bands read as a continuous ribbon. `spacing: 0`
                            // on the outer VStack drops the SwiftUI default
                            // line spacing; the per-line backgrounds own all
                            // the visible padding.
                            VStack(alignment: .leading, spacing: 0) {
                                // Removed lines (red)
                                if !oldText.isEmpty {
                                    let oldChunks = Self.chunkedDiffLines(oldText, prefix: "- ")
                                    LazyRevealChunks(chunks: oldChunks, resetKey: block.id) { text in
                                        Text(text)
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 1, green: 0.4, blue: 0.4, alpha: 1) : UIColor(red: 0.8, green: 0.1, blue: 0.1, alpha: 1) }))
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 2)
                                            .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.3, green: 0.08, blue: 0.08, alpha: 1) : UIColor(red: 1, green: 0.9, blue: 0.9, alpha: 1) }))
                                    }
                                }
                                // Added lines (green)
                                if !newText.isEmpty {
                                    let newChunks = Self.chunkedDiffLines(newText, prefix: "+ ")
                                    LazyRevealChunks(chunks: newChunks, resetKey: block.id) { text in
                                        Text(text)
                                            .font(.system(size: 13, design: .monospaced))
                                            .foregroundStyle(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.4, green: 1, blue: 0.4, alpha: 1) : UIColor(red: 0.1, green: 0.6, blue: 0.1, alpha: 1) }))
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 2)
                                            .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.08, green: 0.2, blue: 0.08, alpha: 1) : UIColor(red: 0.9, green: 1, blue: 0.9, alpha: 1) }))
                                    }
                                }
                            }
                            .textSelection(.enabled)

                            // Result info section (inside the card, separated by divider).
                            // Spacer pushes the footer to the bottom of the card when
                            // the diff body is shorter than cardMinHeight. Keep both the
                            // Spacer and the Divider inside `if !isStreaming` so the
                            // streaming layout (no footer) is unchanged.
                            if !isStreaming {
                                Spacer(minLength: 0)
                                Divider()

                                // Status-aware footer: success → "Edited <path> (1 replacement, …)";
                                // failed → "Failed to edit <path>" + full error message from
                                // toolResult (e.g. "old_string not found … First 20 lines: …");
                                // cancelled → "Cancelled".
                                let (label, labelColor): (String, Color) = {
                                    switch block.toolStatus {
                                    case .failed:    return (AppLocalized("Failed to edit"), .red)
                                    case .cancelled: return (AppLocalized("Cancelled"),     .orange)
                                    default:         return (AppLocalized("Edited"),        Color(UIColor.label))
                                    }
                                }()
                                let isFailure: Bool = {
                                    if case .failed = block.toolStatus { return true }
                                    if case .cancelled = block.toolStatus { return true }
                                    return false
                                }()
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(spacing: 4) {
                                        Text(label)
                                            .font(.system(size: 13, weight: .semibold))
                                            .foregroundStyle(labelColor)
                                        Text(filePath)
                                            .font(.system(size: 12, design: .monospaced))
                                            .foregroundStyle(Color(UIColor.secondaryLabel))
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                        Spacer()
                                    }
                                    if isFailure, let errText = toolResult, !errText.isEmpty {
                                        // Show the model-visible error text so the user
                                        // sees the same explanation the agent is acting on.
                                        // Trim leading "Error: " prefix to avoid the redundant
                                        // "Failed to edit … Error: …" stacking, and cap at 6
                                        // lines to keep the card height bounded.
                                        Text(Self.trimmedErrorMessage(errText))
                                            .font(.system(size: 12, design: .monospaced))
                                            .foregroundStyle(Color(UIColor.secondaryLabel))
                                            .lineLimit(6)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .textSelection(.enabled)
                                    } else if let detail = resultDetail {
                                        Text(detail)
                                            .font(.system(size: 12))
                                            .foregroundStyle(Color(UIColor.tertiaryLabel))
                                    }
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                            }
                        }
                            .frame(maxWidth: .infinity, minHeight: cardMinHeight, maxHeight: .infinity, alignment: .topLeading)
                            .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.10, alpha: 1) : UIColor(white: 0.94, alpha: 1) }))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                            .padding(.horizontal, 12)
                            .padding(.top, 12)
                        }
                        .padding(.bottom, 16)
                    }
                }
            } else {
                fileEditorContent(block.content)
            }
        }
    }

    // MARK: - Memory Tool Helpers

    /// Extracts the `content` field from the tool input args JSON for memory_write.
    private func memoryWriteContentFromArgs() -> String? {
        guard let argsJson = block.toolInputArgs,
              let data = argsJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = obj["content"] as? String else { return nil }
        return content
    }

    /// Returns the action name from the memoryTool kind (e.g. "memory_write", "memory_get").
    private func memoryActionName() -> String {
        if case .memoryTool(let action) = block.kind { return action }
        return "memory"
    }

    // MARK: - Browser Helper Methods

    /// Resolves the browser URL for the current block. Checks (in order):
    /// 1. `browserURL` on the block (set at runtime or restored from persisted `pageURL`)
    /// 2. `url` field in persisted `toolInputArgs` (for navigate/new_tab/fetch)
    /// 3. Inherited from the nearest preceding browser block that has a URL
    ///
    /// Returns an empty string for any block that is not a browser tool —
    /// otherwise other tool kinds (e.g. `readImageTool`) that reuse
    /// `browserResultContent` for their image+text rendering would pick up
    /// an unrelated URL from a previous browser step and display it above
    /// the read-image result.
    private var resolvedBrowserURL: String {
        guard case .browserTool = block.kind else { return "" }
        if let url = block.browserURL, !url.isEmpty { return url }
        // Try extracting url from this block's args
        if let url = extractURLFromArgs(block) { return url }
        // Inherit from preceding browser block
        let idx = min(currentIdx, toolBlocks.count - 1)
        if idx > 0 {
            for i in stride(from: idx - 1, through: 0, by: -1) {
                let prev = toolBlocks[i]
                if let url = prev.browserURL, !url.isEmpty { return url }
                if let url = extractURLFromArgs(prev) { return url }
            }
        }
        return ""
    }

    private func extractURLFromArgs(_ blk: AssistantBlock) -> String? {
        guard let json = blk.toolInputArgs,
              let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let url = dict["url"] as? String,
              !url.isEmpty else { return nil }
        return url
    }

    /// Extracts the `script` field from the tool input args JSON for execute_js.
    private func browserScriptFromArgs() -> String? {
        guard let json = block.toolInputArgs,
              let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let script = dict["script"] as? String,
              !script.isEmpty else { return nil }
        return script
    }

    /// Code card showing the JS script content for execute_js actions.
    private func jsScriptCard(_ script: String) -> some View {
        VStack(spacing: 0) {
            // Title bar
            HStack(spacing: 6) {
                Image(systemName: "chevron.left.forwardslash.chevron.right")
                    .font(.system(size: 11))
                    .foregroundStyle(.orange.opacity(0.7))
                Text("JavaScript")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.orange)
                    .lineLimit(1)
                Spacer()
                Text(Self.formatBytes(script.utf8.count))
                    .font(.system(size: 11))
                    .foregroundStyle(Color(UIColor.tertiaryLabel))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.92, alpha: 1) }))

            Divider()

            // Script content — chunked + windowed; execute_js scripts are
            // usually small but nothing bounds them.
            VStack(alignment: .leading, spacing: 0) {
                LazyRevealChunks(chunks: Self.chunkedLines(script), resetKey: block.id) { t in
                    Text(t)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color(UIColor.label))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                }
            }
            .textSelection(.enabled)
            .padding(.vertical, 12)
        }
        .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.10, alpha: 1) : UIColor(white: 0.94, alpha: 1) }))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
        .padding(.horizontal, 12)
    }

    /// Editor-style card for memory tool content, matching `fileEditorContent` visual style.
    private func memoryEditorContent(_ memoryContent: String, action: String, resultText: String? = nil, isStreaming: Bool = false) -> some View {
        let byteCount = memoryContent.utf8.count
        let sizeLabel = isStreaming
            ? "\(Self.formatBytes(byteCount)) received"
            : Self.formatBytes(byteCount)

        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(spacing: 0) {
                    // Title bar
                    HStack(spacing: 6) {
                        Image(systemName: "brain.head.profile")
                            .font(.system(size: 12))
                            .foregroundStyle(.pink.opacity(0.6))
                        Text(action)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(.pink)
                            .lineLimit(1)
                        Text("(\(sizeLabel))")
                            .font(.system(size: 11))
                            .foregroundStyle(isStreaming ? Color.orange.opacity(0.8) : .pink.opacity(0.5))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.92, alpha: 1) }))

                    Divider()

                    // Memory content body. Streaming keeps the full live tail
                    // (liveChunkedLines already caps at 500 lines); completed
                    // views get the reveal window.
                    VStack(alignment: .leading, spacing: 0) {
                        if isStreaming {
                            ForEach(Self.liveChunkedLines(memoryContent.isEmpty ? " " : memoryContent), id: \.id) { chunk in
                                Text(chunk.text)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(.pink.opacity(0.85))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                            }
                        } else {
                            LazyRevealChunks(chunks: Self.chunkedLines(memoryContent.isEmpty ? " " : memoryContent), resetKey: block.id) { text in
                                Text(text)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(.pink.opacity(0.85))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                            }
                        }
                    }
                    .textSelection(.enabled)
                    .padding(.vertical, 14)
                }
                .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.10, alpha: 1) : UIColor(white: 0.94, alpha: 1) }))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                .padding(.horizontal, 12)
                .padding(.top, 12)
            }
            .padding(.bottom, 16)
        }
    }

    private func fileEditorContent(_ fileContent: String, toolResult: String? = nil, isStreaming: Bool = false) -> some View {
        let fileName: String = {
            if case .fileWriteTool(let p) = block.kind { return (p as NSString).lastPathComponent }
            if case .fileEditTool(let p) = block.kind { return (p as NSString).lastPathComponent }
            if case .fileReadTool(let p) = block.kind { return (p as NSString).lastPathComponent }
            return "file"
        }()
        let isRead = { if case .fileReadTool = block.kind { return true }; return false }()
        let byteCount = fileContent.utf8.count
        let sizeLabel = isStreaming
            ? "\(Self.formatBytes(byteCount)) received"
            : Self.formatBytes(byteCount)

        let chunks = isStreaming
            ? Self.liveChunkedLines(fileContent.isEmpty ? " " : fileContent)
            : Self.chunkedLines(fileContent.isEmpty ? " " : fileContent)

        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Editor card
                VStack(spacing: 0) {
                    // Title bar
                    HStack(spacing: 6) {
                        Image(systemName: isRead ? "doc.text" : "doc.text.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(Color(UIColor.secondaryLabel))
                        Text(fileName)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Color(UIColor.label))
                            .lineLimit(1)
                        Text("(\(sizeLabel))")
                            .font(.system(size: 11))
                            .foregroundStyle(isStreaming ? Color.orange.opacity(0.8) : Color(UIColor.tertiaryLabel))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.92, alpha: 1) }))

                    Divider()

                    // File content — chunked rendering. Streaming keeps the
                    // live tail (already capped at 500 lines); completed
                    // views get the reveal window.
                    VStack(alignment: .leading, spacing: 0) {
                        if isStreaming {
                            ForEach(chunks, id: \.id) { chunk in
                                Text(chunk.text)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color(UIColor.label))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                            }
                        } else {
                            LazyRevealChunks(chunks: chunks, resetKey: block.id) { text in
                                Text(text)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color(UIColor.label))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                            }
                        }
                    }
                    .textSelection(.enabled)
                    .padding(.vertical, 14)
                }
                .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.10, alpha: 1) : UIColor(white: 0.94, alpha: 1) }))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                .padding(.horizontal, 12)
                .padding(.top, 12)

                // File info tips — shown for completed file_write / file_read
                let footerPath: String? = {
                    if isStreaming { return nil }
                    if case .fileWriteTool(let p) = block.kind { return p }
                    if case .fileReadTool(let p) = block.kind { return p }
                    return nil
                }()
                if let fullPath = footerPath {
                    HStack(spacing: 6) {
                        Image(systemName: "info.circle")
                            .font(.system(size: 12))
                            .foregroundStyle(Color(UIColor.secondaryLabel))
                        Text(fullPath)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color(UIColor.label))
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer(minLength: 4)
                        Text(sizeLabel)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color(UIColor.tertiaryLabel))
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.15, alpha: 1) : UIColor(white: 0.95, alpha: 1) }))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                }
            }
            .padding(.bottom, 16)
        }
    }

    private static func formatCharCount(_ count: Int) -> String {
        if count < 1000 { return "\(count) chars" }
        if count < 1_000_000 { return String(format: "%.1fK chars", Double(count) / 1000.0) }
        return String(format: "%.1fM chars", Double(count) / 1_000_000.0)
    }

    private static func formatBytes(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return String(format: "%.1f KB", Double(bytes) / 1024.0) }
        return String(format: "%.1f MB", Double(bytes) / (1024.0 * 1024.0))
    }

    /// Strip the `Error: ` prefix that the file_edit handler injects so the
    /// footer doesn't read "Failed to edit … Error: …". Trims trailing
    /// whitespace too. Preserves the trailing "First 20 lines:" body so the
    /// user has the same context the model sees.
    private static func trimmedErrorMessage(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("Error: ") { s.removeFirst("Error: ".count) }
        return s
    }

    /// Splits text into chunks of `chunkSize` lines for virtualized rendering.
    private static func chunkedLines(_ text: String, chunkSize: Int = 40) -> [(id: Int, text: String)] {
        let sanitized = sanitizeForDisplay(text)
        let allLines = sanitized.split(separator: "\n", omittingEmptySubsequences: false)
        return stride(from: 0, to: max(allLines.count, 1), by: chunkSize).map { i in
            let end = min(i + chunkSize, allLines.count)
            return (i, allLines[i..<end].joined(separator: "\n"))
        }
    }

    /// [T-ios-tool-result-lazy-render] Initial number of chunks to reveal for a
    /// given chunk list: min(lazyRenderInitialChunks, count), further clamped so
    /// the revealed text stays under `lazyRenderInitialByteCap` — covers the case
    /// of a few very long lines that fit in < 5 chunks but exceed 10KB.
    private static func initialRevealCount(_ chunks: [(id: Int, text: String)]) -> Int {
        guard !chunks.isEmpty else { return 0 }
        var count = 0
        var bytes = 0
        for chunk in chunks.prefix(lazyRenderInitialChunks) {
            bytes += chunk.text.utf8.count
            count += 1
            if bytes >= lazyRenderInitialByteCap { break }
        }
        return max(1, count)
    }

    /// "Load more" / "Load all" footer shown under a partially-revealed result.
    /// Tapping bumps `revealedChunkCount`; the bottom sentinel also auto-bumps
    /// when it scrolls into view so reaching the end keeps loading without a tap.
    @ViewBuilder
    private func loadMoreFooter(totalChunks: Int) -> some View {
        if revealedChunkCount < totalChunks {
            let remaining = totalChunks - revealedChunkCount
            let nextBatch = min(Self.lazyRenderBatchChunks, remaining)
            HStack(spacing: 16) {
                Button {
                    revealedChunkCount = min(revealedChunkCount + Self.lazyRenderBatchChunks, totalChunks)
                } label: {
                    Label("Load more (\(nextBatch * Self.lazyRenderChunkLines) lines)", systemImage: "chevron.down")
                        .font(.system(size: 13, weight: .medium))
                }
                Button {
                    revealedChunkCount = totalChunks
                } label: {
                    Text("Load all")
                        .font(.system(size: 13, weight: .medium))
                }
            }
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            // Auto-load when this footer scrolls into view so the user can just
            // keep scrolling to reveal more without tapping.
            .onAppear {
                revealedChunkCount = min(revealedChunkCount + Self.lazyRenderBatchChunks, totalChunks)
            }
        }
    }

    /// Reset / initialize the revealed window for the currently displayed block.
    private func resetRevealWindow(for chunks: [(id: Int, text: String)]) {
        guard revealedForBlockId != block.id else { return }
        revealedForBlockId = block.id
        revealedChunkCount = Self.initialRevealCount(chunks)
    }

    /// Like chunkedLines but caps visible output at ~500 lines during live streaming.
    private static func liveChunkedLines(_ text: String, chunkSize: Int = 40, maxLines: Int = 500) -> [(id: Int, text: String)] {
        let sanitized = sanitizeForDisplay(text)
        let allLines = sanitized.split(separator: "\n", omittingEmptySubsequences: false)
        let start = allLines.count > maxLines ? allLines.count - maxLines : 0
        let visibleLines = allLines[start...]
        return stride(from: 0, to: max(visibleLines.count, 1), by: chunkSize).map { i in
            let sliceStart = visibleLines.startIndex + i
            let sliceEnd = min(sliceStart + chunkSize, visibleLines.endIndex)
            return (start + i, visibleLines[sliceStart..<sliceEnd].joined(separator: "\n"))
        }
    }

    /// Splits diff text into chunks with a per-line prefix (e.g. "- " or "+ ") for lazy rendering.
    private static func chunkedDiffLines(_ text: String, prefix: String, chunkSize: Int = 40) -> [(id: Int, text: String)] {
        let sanitized = sanitizeForDisplay(text)
        let allLines = sanitized.components(separatedBy: "\n")
        return stride(from: 0, to: max(allLines.count, 1), by: chunkSize).map { i in
            let end = min(i + chunkSize, allLines.count)
            let chunk = allLines[i..<end].map { prefix + $0 }.joined(separator: "\n")
            return (i, chunk)
        }
    }

    private var textContent: some View {
        GeometryReader { geo in
            let cardWidth = geo.size.width - 24 // 12pt horizontal padding each side
            let cardMinHeight = cardWidth * 3.0 / 4.0
            ScrollViewReader { proxy in
            ScrollView {
                if case .shellTool(let cmd) = block.kind {
                    // Shell: command header + chunked output (cap at 500 lines while streaming)
                    // [T-ios-toollivesheet-dup-command] Strip the `$ cmd\n`
                    // prefix before chunking. `block.content` already opens with
                    // it, and the header below renders it again — so during
                    // streaming the command appeared twice, once as the header
                    // and once as the first output line. `snapshotTextContent`
                    // already stripped it; this path did not.
                    let cmdPrefix = "$ \(cmd)\n"
                    let displayContent = block.content.hasPrefix(cmdPrefix)
                        ? String(block.content.dropFirst(cmdPrefix.count))
                        : block.content
                    let allChunks = isLive
                        ? Self.liveChunkedLines(displayContent.isEmpty ? " " : displayContent)
                        : Self.chunkedLines(displayContent.isEmpty ? " " : displayContent)
                    // [T-ios-tool-result-lazy-render] In the detail (non-live)
                    // view reveal only an initial window and grow on scroll.
                    let chunks = isLive ? allChunks : Array(allChunks.prefix(max(revealedChunkCount, 1)))
                    VStack(alignment: .leading, spacing: 0) {
                        Text("$ \(cmd)")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.top, 14)

                        ForEach(chunks, id: \.id) { chunk in
                            Text(attributedShellLine(chunk.text))
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(accentColor)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 14)
                        }

                        if !isLive {
                            loadMoreFooter(totalChunks: allChunks.count)
                        }

                        Color.clear.frame(height: 1).id("end")
                    }
                    .onAppear { if !isLive { resetRevealWindow(for: allChunks) } }
                    .textSelection(.enabled)
                    .padding(.bottom, isLive ? 24 : 14)
                    .frame(maxWidth: .infinity, minHeight: cardMinHeight, alignment: .topLeading)
                    .background(Color.black)
                    .overlay(alignment: .bottom) {
                        if isLive {
                            HStack(spacing: 12) {
                                Text(resourceMonitor.formattedCPU)
                                    .foregroundStyle(.green)
                                Text(resourceMonitor.formattedMem())
                                    .foregroundStyle(.green)
                            }
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 5)
                            .background(Color(white: 0.08))
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }), lineWidth: 0.5))
                    .padding(.horizontal, 12)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
                } else {
                    // Non-shell: header + chunked content card
                    VStack(alignment: .leading, spacing: 0) {
                        if !contentHeader.isEmpty {
                            Text(contentHeader)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(ChatColors.secondaryText)
                                .padding(.horizontal, 16)
                                .padding(.top, 16)
                                .padding(.bottom, 8)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }

                        let allChunks = isLive
                            ? Self.liveChunkedLines(block.content.isEmpty ? " " : block.content)
                            : Self.chunkedLines(block.content.isEmpty ? " " : block.content)
                        // [T-ios-tool-result-lazy-render] Reveal an initial
                        // window in the detail view; grow on scroll / tap.
                        let chunks = isLive ? allChunks : Array(allChunks.prefix(max(revealedChunkCount, 1)))
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(chunks, id: \.id) { chunk in
                                Text(chunk.text)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundColor(accentColor)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                            }
                            if !isLive {
                                loadMoreFooter(totalChunks: allChunks.count)
                            }
                            Color.clear.frame(height: 1).id("end")
                        }
                        .onAppear { if !isLive { resetRevealWindow(for: allChunks) } }
                        .textSelection(.enabled)
                        .padding(.vertical, 14)
                        .frame(maxWidth: .infinity, minHeight: cardMinHeight, alignment: .topLeading)
                        .background(Color(white: 0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .padding(.horizontal, 12)
                    }
                    .padding(.bottom, 16)
                }
            }
            .onChange(of: block.content.count) { _ in
                if isLive {
                    proxy.scrollTo("end", anchor: .bottom)
                }
            }
            // [T-ios-tool-result-lazy-render] Switching to another tool block
            // (next/prev) re-collapses the lazy window to its initial batch.
            .onChange(of: block.id) { _ in
                guard !isLive else { return }
                let chunks = Self.chunkedLines(block.content.isEmpty ? " " : block.content)
                revealedForBlockId = block.id
                revealedChunkCount = Self.initialRevealCount(chunks)
            }
            }
        }
    }

    private var browserContent: some View {
        let action: String = {
            if case .browserTool(let a) = block.kind { return a }
            return ""
        }()
        let url: String = resolvedBrowserURL
        let script: String? = browserScriptFromArgs()

        return VStack(spacing: 0) {
            if let img = browserSnapshot ?? block.imageFilePath.flatMap({ UIImage(contentsOfFile: $0) }) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        // Action + URL capsules
                        if !action.isEmpty || !url.isEmpty {
                            HStack(spacing: 8) {
                                if !action.isEmpty {
                                    Text(action)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 5)
                                        .background(Color.blue)
                                        .clipShape(Capsule())
                                }
                                if !url.isEmpty {
                                    CopyableURLCapsule(url: url)
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.top, 12)
                        }

                        // JS script card for execute_js
                        if let script {
                            jsScriptCard(script)
                        }

                        // Live screenshot — shadowed card. Use scaledToFit +
                        // frame(maxWidth:.infinity) instead of a GeometryReader-
                        // computed explicit frame: the latter could over-propose
                        // the image size during sheet animation on Mac Catalyst /
                        // iPad and let the right edge clip outside the viewport.
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10)
                                .stroke(Color(UIColor.separator).opacity(0.5), lineWidth: 0.5))
                            .shadow(color: .black.opacity(0.18), radius: 8, x: 0, y: 3)
                            .contextMenu {
                                Button { UIPasteboard.general.image = img } label: {
                                    Label("Copy Image", systemImage: "doc.on.doc")
                                }
                            }
                            .padding(.horizontal, 12)
                    }
                    .padding(.bottom, 16)
                }
            } else {
                VStack(spacing: 0) {
                    // Action + URL capsules pinned to top
                    if !action.isEmpty || !url.isEmpty {
                        HStack(spacing: 8) {
                            if !action.isEmpty {
                                Text(action)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(Color.blue)
                                    .clipShape(Capsule())
                            }
                            if !url.isEmpty {
                                CopyableURLCapsule(url: url)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    // JS script card for execute_js (no screenshot yet)
                    if let script {
                        jsScriptCard(script)
                            .padding(.top, 12)
                    }
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "globe")
                            .font(.system(size: 32))
                            .foregroundStyle(ChatColors.tertiaryText)
                        Text("Loading...")
                            .font(.system(size: 13))
                            .foregroundStyle(ChatColors.tertiaryText)
                    }
                    Spacer()
                }
            }
        }
        .onChange(of: block.imageFilePath) { path in
            if let path, let img = UIImage(contentsOfFile: path) {
                browserSnapshot = img
            }
        }
    }

    // MARK: - Bottom bar: tool status + navigation

    private var bottomBar: some View {
        VStack(spacing: 0) {
            Divider()

            // Tool info row
            HStack(spacing: 8) {
                statusIcon
                VStack(alignment: .leading, spacing: 1) {
                    Text(toolTitle)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(ChatColors.primaryText)
                        .lineLimit(1)
                    Text(toolSubtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(ChatColors.secondaryText)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Spacer()
                // [T-step-timestamp v3-fix aa8b1128] Trailing column in the
                // detail-view bottom bar: duration on top, the step's
                // HH:mm:ss start time on a smaller / lighter line right
                // beneath it. This is where the user goes to inspect a
                // single step, so the timestamp lives here instead of on
                // every pill in the message list.
                let dur = block.toolDuration ?? currentSnapshot?.snapshot.duration
                if dur != nil || block.toolStartTime != nil {
                    VStack(alignment: .trailing, spacing: 1) {
                        if let dur {
                            Text(Self.formatDuration(dur))
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(ChatColors.tertiaryText)
                        }
                        if let started = block.toolStartTime {
                            Text(MinisStepTimestampFormatter.string(from: started))
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundStyle(ChatColors.tertiaryText.opacity(0.75))
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            // Navigation row: |< ... Live ... >|
            HStack {
                Button {
                    if currentIdx > 0 { withAnimation { currentIdx -= 1 } }
                } label: {
                    Image(systemName: "backward.end.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(currentIdx > 0 ? ChatColors.primaryText : ChatColors.tertiaryText)
                }
                .disabled(currentIdx <= 0)

                Spacer()

                if isLive {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(.green)
                            .frame(width: 7, height: 7)
                        Text("Live")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(ChatColors.primaryText)
                    }
                } else {
                    Text("\(currentIdx + 1) / \(toolBlocks.count)")
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(ChatColors.secondaryText)
                }

                Spacer()

                Button {
                    if currentIdx < toolBlocks.count - 1 { withAnimation { currentIdx += 1 } }
                } label: {
                    Image(systemName: "forward.end.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(currentIdx < toolBlocks.count - 1 ? ChatColors.primaryText : ChatColors.tertiaryText)
                }
                .disabled(currentIdx >= toolBlocks.count - 1)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
        }
        .background(Color(UIColor.systemBackground))
    }

    // MARK: - Helpers

    @ViewBuilder
    private var statusIcon: some View {
        switch block.toolStatus {
        case .streaming, .running:
            ProgressView()
                .scaleEffect(0.7)
                .frame(width: 20, height: 20)
        case .success:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 18))
                .foregroundStyle(.green)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 18))
                .foregroundStyle(.red)
        case .cancelled:
            Image(systemName: "stop.circle.fill")
                .font(.system(size: 18))
                .foregroundStyle(.yellow)
        case nil:
            EmptyView()
        }
    }

    private var toolTitle: String {
        switch block.kind {
        case .shellTool: return "Minis is using Shell"
        case .fileReadTool: return "Minis is reading File"
        case .fileWriteTool: return "Minis is using Editor"
        case .fileEditTool: return "Minis is editing File"
        case .browserTool: return "Minis is using Browser"
        case .readImageTool: return "Minis is reading Image"
        case .memoryTool: return "Minis is using Memory"
        case .delegateTool: return "Minis is using an Agent"
        case .info: return "Minis"
        case .text: return "Minis"
        case .thinking: return "Minis"
        }
    }

    private var toolSubtitle: String {
        let raw = if let s = block.toolSummary, !s.isEmpty { s } else { block.toolDescription }
        return raw.replacingOccurrences(of: "\n", with: " ")
    }

    private var contentHeader: String {
        switch block.kind {
        case .shellTool(let cmd): return cmd
        case .fileReadTool(let p): return (p as NSString).lastPathComponent
        case .fileWriteTool(let p): return (p as NSString).lastPathComponent
        case .fileEditTool(let p): return (p as NSString).lastPathComponent
        case .browserTool(let a): return a
        case .readImageTool(let p): return (p as NSString).lastPathComponent
        case .memoryTool(let a): return a
        default: return ""
        }
    }

    private var accentColor: Color {
        switch block.kind {
        case .shellTool: return .green
        case .fileReadTool: return .primary
        case .fileWriteTool: return .blue
        case .fileEditTool: return .orange
        case .browserTool: return .blue
        case .readImageTool: return .purple
        case .memoryTool: return .pink
        case .delegateTool: return HelperAccent.color
        case .info: return .secondary
        case .text: return .primary
        case .thinking: return .blue
        }
    }

    private static func formatDuration(_ dur: TimeInterval) -> String {
        if dur < 1 { return String(format: "%.1fs", dur) }
        if dur < 60 { return String(format: "%.0fs", dur) }
        let mins = Int(dur) / 60
        let secs = Int(dur) % 60
        return "\(mins)m \(secs)s"
    }

    // MARK: - Browser snapshot timer

    private func startBrowserTimer() {
        guard case .browserTool = block.kind else { return }
        takeBrowserSnapshot()
        let timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in
            takeBrowserSnapshot()
        }
        snapshotTimer = timer
    }

    private func stopBrowserTimer() {
        snapshotTimer?.invalidate()
        snapshotTimer = nil
    }

    private func takeBrowserSnapshot() {
        guard let manager = browserPool?.activeManager else { return }
        Task { @MainActor in
            let config = WKSnapshotConfiguration()
            if let img = try? await manager.webView.takeSnapshot(configuration: config) {
                browserSnapshot = img
            }
        }
    }
}

/// Small preview thumbnail for the collapsed state.
private struct ToolPreviewThumbnail: View {
    @ObservedObject var block: AssistantBlock
    var snapshot: ToolSnapshotItem?
    var browserPool: BrowserTabPool?
    var onTap: () -> Void
    @State private var browserSnapshot: UIImage?
    @State private var snapshotTimer: Timer?
    @StateObject private var resourceMonitor = SystemResourceMonitor()

    /// True while the SUB AGENT IS RUNNING A TOOL — any tool, not just the
    /// browser.
    ///
    /// Deliberately not "is a delegate block": an agent that is starting up or
    /// has finished needs no marking, and its own thumbnail is already
    /// unmistakable. What needs marking is a tile standing for work happening
    /// one level down — the tap goes to that child's tool, not to the parent's.
    /// Keyed on the child having a live tool at all, because the earlier
    /// `== "browser_use"` test left the glow off for every other tool the
    /// child runs (shell_execute, text, file_read …), which is most of them.
    private var isSubAgentTool: Bool {
        // Same question the preview asks: is this agent inside a tool right
        // now. One source of truth, so the ring and the content never disagree.
        guard case .delegateTool = block.kind else { return false }
        return liveChildToolBlock(childSessionId: block.helperChildSessionId) != nil
    }

    private var isLive: Bool {
        if case .streaming = block.toolStatus { return true }
        if case .running = block.toolStatus { return true }
        return false
    }

    var body: some View {
        Group {
            // [T-agent-thumbnail-json] An agent block always renders through
            // its own thumbnail: its tool_result is a JSON payload (and the
            // wait→background conversion persists one as a text snapshot),
            // which the generic text-snapshot branch below would print raw.
            if case .delegateTool = block.kind {
                // [T-agent-inner-tool-front] One preview per sub agent, whose
                // CONTENT follows its state: while it is inside a tool, show
                // that tool exactly as the parent's own tools are shown (the
                // live browser page for browser_use, snapshot/diff/text for the
                // rest); otherwise — starting, between tools, finished — its
                // own card. The agent shares the parent's browser pool, so the
                // live page needs nothing extra here.
                if let inner = liveChildToolBlock(childSessionId: block.helperChildSessionId) {
                    ToolPreviewThumbnail(block: inner.block, snapshot: inner.snapshot,
                                         browserPool: browserPool, onTap: onTap)
                } else {
                    HelperThumbnailView(block: block)
                }
            } else if let snapshot, let snapshotImage = loadSnapshotImage(snapshot) {
                Image(uiImage: snapshotImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 100, height: 65)
                    .clipped()
            } else if let snapshot, case .text = snapshot.snapshot.type, let text = snapshot.snapshot.text {
                if case .fileEditTool = block.kind {
                    diffPreview
                } else {
                    snapshotTextPreview(text)
                }
            } else {
                switch block.kind {
                case .browserTool:
                    browserPreview
                case .fileEditTool:
                    diffPreview
                default:
                    textPreview
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        // [T-agent-thumbnail-accent] A sub agent's tile is marked as one
        // whatever it happens to be showing. The agent block's preview swaps to
        // the live browser page while the child is inside browser_use, and that
        // branch used to look exactly like the parent's own browser tool — same
        // frame, same neutral hairline — so the one tile whose tap goes
        // somewhere else was the one tile you could not identify.
        //
        // The gradient reaches in from the edges rather than tinting the whole
        // tile: the preview underneath is the content, and a flat wash over a
        // live page or a text snapshot would hurt legibility for no gain.
        .overlay {
            if isSubAgentTool {
                // A ring hugging the edge, not a wash over the tile: an inset
                // stroke blurred just enough to read as a glow. At ~6pt on a
                // 100x65 tile it covers about a tenth of the area and leaves
                // the preview underneath legible.
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(HelperAccent.color.opacity(0.55), lineWidth: 6)
                    .blur(radius: 4)
                    // The blur spreads past the corner radius; clip it back to
                    // the tile so the glow stays inside and the rounded corners
                    // are not smeared square.
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .allowsHitTesting(false)
            }
        }
        // The sub agent marker is the inner glow alone — the tile keeps the
        // same hairline every other tool has. A violet border on top of the
        // glow read as an alert rather than an accent.
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color(UIColor.separator).opacity(0.4), lineWidth: 0.5)
        )
        .overlay(alignment: .bottom) {
            if isLive && isShell {
                Text("\(resourceMonitor.formattedCPU)  \(resourceMonitor.formattedMem(compact: true))")
                    .font(.system(size: 5, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.white.opacity(0.8))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.6))
                    .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 8, bottomTrailingRadius: 8))
            }
        }
        .shadow(color: .black.opacity(0.3), radius: 10, x: 0, y: 3)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onAppear {
            if isLive && isShell { resourceMonitor.start() }
        }
        .onDisappear { resourceMonitor.stop() }
        .onChange(of: isLive) { live in
            if live && isShell { resourceMonitor.start() } else { resourceMonitor.stop() }
        }
    }

    /// Text preview from snapshot data (persisted last N lines).
    private func snapshotTextPreview(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(previewHeader)
                .font(.system(size: 6, weight: .bold, design: isShell ? .monospaced : .default))
                .foregroundStyle(isShell ? .white : .white.opacity(0.45))
                .lineLimit(1)
                .padding(.horizontal, 4)
                .padding(.top, 4)
                .padding(.bottom, 1)

            Text(lastLines(text, count: 12))
                .font(.system(size: 4, design: .monospaced))
                .foregroundStyle(accentColor.opacity(0.85))
                .lineLimit(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 4)
                .padding(.bottom, 3)
        }
        .frame(width: 100, height: 65, alignment: .topLeading)
        .clipped()
        .background(Color(red: 0.12, green: 0.12, blue: 0.14))
    }

    /// Load image from a snapshot's mediaRef.
    private func loadSnapshotImage(_ item: ToolSnapshotItem) -> UIImage? {
        guard case .image = item.snapshot.type,
              let ref = item.snapshot.mediaRef else { return nil }
        let url = item.mediaResolver(ref)
        // Use cached thumbnail if available (200px max for 100pt @2x)
        let cacheKey = "thumb:\(url.path)"
        if let cached = NativeMediaImageCache.shared.image(for: cacheKey) {
            return cached
        }
        // Downsample synchronously on first access (small target = fast),
        // then cache for subsequent scrolls.
        guard let data = try? Data(contentsOf: url),
              let thumb = downsampleImageData(data, maxPixelSize: 200) else {
            return nil
        }
        NativeMediaImageCache.shared.set(thumb, for: cacheKey)
        return thumb
    }

    private var textPreview: some View {
        let displayText: String = {
            let isFileStreamKind: Bool = {
                if case .fileWriteTool = block.kind { return true }
                if case .fileEditTool = block.kind { return true }
                return false
            }()
            if isFileStreamKind, let liveContent = block.streamingFileContent, !liveContent.isEmpty {
                return liveContent
            }
            return block.content
        }()
        return VStack(alignment: .leading, spacing: 0) {
            Text(previewHeader)
                .font(.system(size: 6, weight: .bold, design: isShell ? .monospaced : .default))
                .foregroundStyle(isShell ? .white : .white.opacity(0.45))
                .lineLimit(1)
                .padding(.horizontal, 4)
                .padding(.top, 4)
                .padding(.bottom, 1)

            Text(lastLines(displayText, count: 12))
                .font(.system(size: 4, design: .monospaced))
                .foregroundStyle(accentColor.opacity(0.85))
                .lineLimit(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 4)
                .padding(.bottom, 3)
        }
        .frame(width: 100, height: 65, alignment: .topLeading)
        .clipped()
        .background(Color(red: 0.12, green: 0.12, blue: 0.14))
    }

    /// Diff-style mini preview for file_edit thumbnails.
    private var diffPreview: some View {
        let editStrings: (oldString: String, newString: String)? = {
            guard let json = block.toolInputArgs,
                  let data = json.data(using: .utf8),
                  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let old = dict["old_string"] as? String,
                  let new = dict["new_string"] as? String else { return nil }
            return (old, new)
        }()
        let oldText = editStrings?.oldString ?? block.streamingFileContent ?? ""
        let newText = editStrings?.newString ?? ""

        return VStack(alignment: .leading, spacing: 0) {
            Text(previewHeader)
                .font(.system(size: 6, weight: .bold))
                .foregroundStyle(.white.opacity(0.45))
                .lineLimit(1)
                .padding(.horizontal, 4)
                .padding(.top, 4)
                .padding(.bottom, 1)

            if !oldText.isEmpty {
                Text(lastLines(oldText, count: 6).split(separator: "\n", omittingEmptySubsequences: false).map { "- " + $0 }.joined(separator: "\n"))
                    .font(.system(size: 4, design: .monospaced))
                    .foregroundStyle(Color(red: 1, green: 0.4, blue: 0.4).opacity(0.85))
                    .lineLimit(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 4)
            }
            if !newText.isEmpty {
                Text(lastLines(newText, count: 6).split(separator: "\n", omittingEmptySubsequences: false).map { "+ " + $0 }.joined(separator: "\n"))
                    .font(.system(size: 4, design: .monospaced))
                    .foregroundStyle(Color(red: 0.4, green: 1, blue: 0.4).opacity(0.85))
                    .lineLimit(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 4)
            }
        }
        .frame(width: 100, height: 65, alignment: .topLeading)
        .clipped()
        .background(Color(red: 0.12, green: 0.12, blue: 0.14))
    }

    private var browserPreview: some View {
        Group {
            if let img = browserSnapshot {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 100, height: 65)
                    .clipped()
            } else {
                ZStack {
                    Color(red: 0.12, green: 0.12, blue: 0.14)
                    Image(systemName: "globe")
                        .font(.system(size: 16))
                        .foregroundStyle(.white.opacity(0.2))
                }
                .frame(width: 100, height: 65)
            }
        }
        .onAppear {
            startBrowserTimer()
            loadThumbnailIfNeeded()
        }
        .onDisappear { stopBrowserTimer() }
        .onChange(of: block.imageFilePath) { _ in
            loadThumbnailIfNeeded()
        }
    }

    /// Load a small thumbnail for the tool capsule preview asynchronously.
    /// Downsample to 2x display size (200x130) to avoid GPU overdraw.
    private func loadThumbnailIfNeeded() {
        guard browserSnapshot == nil, let path = block.imageFilePath else { return }
        // Check cache first
        let cacheKey = "thumb:\(path)"
        if let cached = NativeMediaImageCache.shared.image(for: cacheKey) {
            browserSnapshot = cached
            return
        }
        Task.detached(priority: .utility) {
            guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)) else { return }
            // 200px = 100pt × 2x retina
            guard let thumb = downsampleImageData(data, maxPixelSize: 200) else { return }
            NativeMediaImageCache.shared.set(thumb, for: cacheKey)
            await MainActor.run {
                self.browserSnapshot = thumb
            }
        }
    }

    private func startBrowserTimer() {
        takeBrowserSnapshot()
        let timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { _ in
            takeBrowserSnapshot()
        }
        snapshotTimer = timer
    }

    private func stopBrowserTimer() {
        snapshotTimer?.invalidate()
        snapshotTimer = nil
    }

    private func takeBrowserSnapshot() {
        guard let manager = browserPool?.activeManager else { return }
        Task { @MainActor in
            let config = WKSnapshotConfiguration()
            if let img = try? await manager.webView.takeSnapshot(configuration: config) {
                browserSnapshot = img
            }
        }
    }

    private var previewHeader: String {
        switch block.kind {
        case .shellTool(let cmd): return cmd.isEmpty ? "$ shell" : "$ \(cmd)"
        case .fileReadTool(let p):
            let name = (p as NSString).lastPathComponent
            return (!p.isEmpty && name != "/" && name.contains(".")) ? name : AppLocalized("Read file")
        case .fileWriteTool(let p):
            let name = (p as NSString).lastPathComponent
            return (!p.isEmpty && name != "/" && name.contains(".")) ? name : AppLocalized("Write file")
        case .fileEditTool(let p):
            let name = (p as NSString).lastPathComponent
            return (!p.isEmpty && name != "/" && name.contains(".")) ? name : AppLocalized("Edit file")
        case .browserTool(let a): return a
        case .readImageTool(let p):
            let name = (p as NSString).lastPathComponent
            return (!p.isEmpty && name != "/" && name.contains(".")) ? name : AppLocalized("Read image")
        case .memoryTool(let a): return a
        default: return ""
        }
    }

    private var isShell: Bool {
        if case .shellTool = block.kind { return true }
        return false
    }

    private var accentColor: Color {
        switch block.kind {
        case .shellTool: return .green
        case .fileReadTool: return .cyan
        case .fileWriteTool: return .blue
        case .fileEditTool: return .orange
        case .browserTool: return .blue
        case .readImageTool: return .purple
        case .memoryTool: return .pink
        case .delegateTool: return HelperAccent.color
        case .info: return .secondary
        case .text: return .primary
        case .thinking: return .blue
        }
    }

    private func lastLines(_ text: String, count: Int) -> String {
        let lines = text.components(separatedBy: "\n")
        return lines.suffix(count).joined(separator: "\n")
    }
}

/// Bottom status bar: status icon + description + counter + expand chevron.
/// Background for the collapsed floating tool-status bar.
///
/// iOS 26+: Liquid Glass. This bar floats over the live message list, so the
/// material has real, moving content to sample — the case glass is actually for,
/// and the opposite of `FolderSurface`, which had to fall back to a sampled
/// constant precisely because it had nothing but flat list background behind it.
///
/// The hairline stroke and the hand-rolled shadow are both dropped on the glass
/// path: the material renders its own edge and shadow, and stacking the old ones
/// on top reads as a dark halo plus a doubled border (same finding as the FAB and
/// composer conversions). Sub-26 keeps the original fill + stroke + shadow
/// byte-for-byte.
///
/// The bar's content (status icon, title, pager) is *inside* the modified view,
/// not `.overlay`-ed on it — `.glassEffect` composites the material above the
/// view it modifies, so an overlay would be painted under the glass and vanish.
/// That was the FAB regression; keeping the content as the modifier's `content`
/// is what avoids it here.
private struct ToolStatusBarSurface: ViewModifier {
    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
    }

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: shape)
        } else {
            content
                .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.15, alpha: 1) : UIColor.systemBackground }))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color(UIColor.separator).opacity(0.3), lineWidth: 0.5)
                )
                .shadow(color: Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.08, alpha: 0.75) : UIColor(white: 0, alpha: 0.12) }), radius: 8, x: 0, y: 4)
        }
    }
}

private struct ToolStatusBar: View {
    @ObservedObject var block: AssistantBlock
    let toolBlocks: [AssistantBlock]
    let displayedIdx: Int
    @Binding var expanded: Bool
    @Binding var selectedIdx: Int?
    /// [T-agent-manual-hold] Stamped when the user picks with the arrows, so
    /// the owner can expire the manual choice after a minute.
    @Binding var selectedAt: Date
    var leadingInset: CGFloat = 0

    var body: some View {
        HStack(spacing: 2) {
            statusIcon

            // [T-step-timestamp v2 aa8b1128] Inline HH:mm:ss prefix removed
            // — see ToolLiveSheet.sheetNavBar for the new "start +
            // elapsed" display, surfaced only inside the tapped-open
            // detail sheet so the always-visible status bar stays clean.

            Text(displayTitle)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(ChatColors.secondaryText)
                .lineLimit(1)
                .truncationMode(.tail)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation { expanded = true } }

            Spacer(minLength: 1)

            if toolBlocks.count > 1 {
                HStack(spacing: 2) {
                    // [T-tool-pager-longpress] Tap steps one, long-press jumps
                    // to the end. A conversation routinely runs past a hundred
                    // tool calls (reported at 61/109), and reaching either end
                    // by tapping meant dozens of taps.
                    pagerButton(
                        glyph: "chevron.left",
                        enabled: displayedIdx > 0,
                        step: { move(to: (selectedIdx ?? displayedIdx) - 1) },
                        jump: { move(to: 0) })

                    // [T-tool-pager-label] The pager walks every tool block in
                    // the CONVERSATION — a browser, not a progress bar. A rule
                    // and a wrench glyph used to sit here saying so; both were
                    // dropped as visual noise (they cost width on a one-line
                    // bar and the glyph named nothing a reader could act on).
                    // The accessibility label is now the only place that
                    // spells out what the count means, so it has to stay.
                    Text("\(displayedIdx + 1)/\(toolBlocks.count)")
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(ChatColors.secondaryText)
                        .accessibilityLabel(
                            AppLocalized("Tool call") + " \(displayedIdx + 1) / \(toolBlocks.count)")

                    pagerButton(
                        glyph: "chevron.right",
                        enabled: displayedIdx < toolBlocks.count - 1,
                        step: { move(to: (selectedIdx ?? displayedIdx) + 1) },
                        jump: { move(to: toolBlocks.count - 1) })
                }
            }
        }
        .padding(.leading, leadingInset > 0 ? leadingInset : 12)
        .padding(.trailing, 12)
        .padding(.vertical, 5)
        .frame(minHeight: 38)
        .frame(maxWidth: .infinity)
        .modifier(ToolStatusBarSurface())
    }

    /// [T-tool-pager-longpress] The one place the pager's manual selection is
    /// written, so a step and a jump cannot drift apart.
    ///
    /// Clamped rather than guarded: every caller already computes an in-range
    /// target and the buttons are disabled at the ends, but a clamp here means
    /// no future caller can walk the index out of bounds.
    private func move(to idx: Int) {
        guard !toolBlocks.isEmpty else { return }
        let target = min(max(idx, 0), toolBlocks.count - 1)
        guard target != displayedIdx else { return }
        withAnimation {
            selectedIdx = target
            // [T-agent-manual-hold] Stamping this is what suppresses auto-follow
            // for the next minute — a jump is a manual pick like any other, so
            // it must not skip the stamp or the bar would slide out from under
            // the user immediately after they arrived.
            selectedAt = Date()
        }
    }

    /// [T-tool-pager-longpress] One pager arrow: tap steps, long-press jumps to
    /// that end.
    ///
    /// Raw gestures on the glyph rather than a `Button` + `simultaneousGesture`.
    /// A Button's own tap and an attached long press both fire on a long hold,
    /// so the jump would land with a stray one-step move on top of it;
    /// `onTapGesture` and `onLongPressGesture` on the same view are mutually
    /// exclusive — the tap only completes if the press ends before the
    /// long-press threshold.
    ///
    /// The trade of losing `Button` is that `.disabled` no longer suppresses
    /// input for us (it gates Buttons and controls, not bare gesture
    /// modifiers), so `enabled` is checked INSIDE both handlers. That is the
    /// out-of-range guard: at either end the arrow is inert, not clamping to
    /// where it already sits. `move(to:)` clamps as well, so the index cannot
    /// go out of bounds even if this check were ever dropped.
    @ViewBuilder
    private func pagerButton(glyph: String,
                             enabled: Bool,
                             step: @escaping () -> Void,
                             jump: @escaping () -> Void) -> some View {
        Image(systemName: glyph)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(enabled ? ChatColors.primaryText : ChatColors.tertiaryText)
            .frame(width: 20, height: 20)
            .contentShape(Rectangle())
            .onTapGesture { if enabled { step() } }
            .onLongPressGesture(minimumDuration: 0.4) {
                guard enabled else { return }
                // Light, matching the codebase's other confirmatory long-press
                // (FileBrowserView's copy-path). The jump moves the bar a long
                // way with no travel to watch, so the tick is what tells the
                // user it took.
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                jump()
            }
            .accessibilityAddTraits(.isButton)
            .accessibilityRespondsToUserInteraction(enabled)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch block.toolStatus {
        case .streaming, .running:
            ProgressView()
                .scaleEffect(0.6)
                .frame(width: 16, height: 16)
        case .success:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 15))
                .foregroundStyle(.green)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 15))
                .foregroundStyle(.red)
        case .cancelled:
            Image(systemName: "stop.circle.fill")
                .font(.system(size: 15))
                .foregroundStyle(.yellow)
        case nil:
            EmptyView()
        }
    }

    private var displayTitle: String {
        if let s = block.toolSummary, !s.isEmpty { return s }
        return block.toolDescription
    }
}

// MARK: - Step Timestamp Formatter

/// [T-step-timestamp aa8b1128] Shared HH:mm:ss formatter for the "this
/// step started at..." annotation that lives on every tool capsule and
/// on the bottom Tool Status Bar. Single static instance so we don't
/// pay the DateFormatter alloc on every recompose. Locale-independent
/// numeric format ("posix" + HH:mm:ss) so a CJK locale renders the same
/// glyphs as an English locale.
enum MinisStepTimestampFormatter {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    static func string(from date: Date) -> String {
        formatter.string(from: date)
    }

    /// [T-step-timestamp v2 aa8b1128] Short "elapsed-or-final duration"
    /// label for the tool detail header. Format:
    ///   < 60s   → "3s"
    ///   < 1h    → "2m30s" (drops the seconds suffix when seconds == 0)
    ///   ≥ 1h    → "1h12m"
    /// When `stillRunning` is true the result is suffixed "…" so the
    /// header reads "12s…" while the tool is in flight.
    /// Negative values clamp to 0; non-finite values render as "0s".
    static func duration(seconds: TimeInterval, stillRunning: Bool = false) -> String {
        let safe = (seconds.isFinite && seconds > 0) ? seconds : 0
        let totalSecs = Int(safe.rounded())
        let base: String
        if totalSecs < 60 {
            base = "\(totalSecs)s"
        } else if totalSecs < 3600 {
            let m = totalSecs / 60
            let s = totalSecs % 60
            base = s == 0 ? "\(m)m" : "\(m)m\(s)s"
        } else {
            let h = totalSecs / 3600
            let m = (totalSecs % 3600) / 60
            base = m == 0 ? "\(h)h" : "\(h)h\(m)m"
        }
        return stillRunning ? "\(base)…" : base
    }
}

/// [T-agent-thumbnail-accent] A sub agent's live tool, addressed by the child
/// session it belongs to. `Identifiable` on the block's id so `.sheet(item:)`
/// re-presents when the child moves on to a different tool.
struct ChildToolTarget: Identifiable {
    let sessionId: String
    let block: AssistantBlock
    var id: String { block.toolUseId ?? block.id.uuidString }
}
