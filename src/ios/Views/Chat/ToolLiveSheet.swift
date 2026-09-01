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

/// Wrapper that @ObservedObject's the ChatMessage so SwiftUI re-evaluates
/// when blocks are added/changed.
private struct FloatingToolPreviewContainer: View {
    @ObservedObject var message: ChatMessage
    var toolSnapshots: [ToolSnapshotItem] = []
    var browserPool: BrowserTabPool?

    private var toolBlocks: [AssistantBlock] {
        message.blocks.filter { $0.toolStatus != nil }
    }

    var body: some View {
        let tools = toolBlocks
        if !tools.isEmpty {
            FloatingToolBar(toolBlocks: tools, toolSnapshots: toolSnapshots, browserPool: browserPool)
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
    @State private var expanded = false
    @AppStorage("toolPreviewEnabled") private var toolPreviewEnabled: Bool = true

    private var displayedIdx: Int {
        if let idx = selectedIdx, idx < toolBlocks.count { return idx }
        if let activeIdx = toolBlocks.lastIndex(where: {
            if case .streaming = $0.toolStatus { return true }
            if case .running = $0.toolStatus { return true }
            return false
        }) { return activeIdx }
        return toolBlocks.count - 1
    }

    private var displayedBlock: AssistantBlock {
        toolBlocks[displayedIdx]
    }

    private static let thumbnailWidth: CGFloat = 100

    /// The snapshot corresponding to the currently displayed tool block, matched by ID.
    private var displayedSnapshot: ToolSnapshotItem? {
        guard let blockId = displayedBlock.toolUseId else { return nil }
        return toolSnapshots.first(where: { $0.id == blockId })
    }

    var body: some View {
        // Collapsed: thumbnail (optional) + status bar
        ZStack(alignment: .bottomLeading) {
            ToolStatusBar(
                block: displayedBlock,
                toolBlocks: toolBlocks,
                displayedIdx: displayedIdx,
                expanded: $expanded,
                selectedIdx: $selectedIdx,
                leadingInset: toolPreviewEnabled ? Self.thumbnailWidth + 18 : 0
            )

            if toolPreviewEnabled {
                ToolPreviewThumbnail(
                    block: displayedBlock,
                    snapshot: displayedSnapshot,
                    browserPool: browserPool,
                    onTap: { withAnimation { expanded = true } }
                )
                .offset(x: 10)
            }
        }
        // .shadow(color: .black.opacity(0.2), radius: 12, x: 0, y: -4)
        .animation(.easeInOut(duration: 0.15), value: displayedIdx)
        .sheet(isPresented: $expanded) {
            ToolLiveSheet(
                toolBlocks: toolBlocks,
                initialIdx: displayedIdx,
                toolSnapshots: toolSnapshots,
                browserPool: browserPool,
                onBrowserTakeover: onBrowserTakeover,
                onTakeoverDone: onTakeoverDone
            )
        }
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

/// Full-screen live sheet — Manus-style layout with nav bar, live content, and bottom status/navigation.
struct ToolLiveSheet: View {
    let toolBlocks: [AssistantBlock]
    @State var currentIdx: Int
    var toolSnapshots: [ToolSnapshotItem] = []
    var browserPool: BrowserTabPool?
    var onBrowserTakeover: (() -> Void)?
    var onTakeoverDone: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.chatSessionId) private var sessionId

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

    /// Describes which secondary sheet is currently presented on top of
    /// ToolLiveSheet. Cases are mutually exclusive by construction — the user
    /// either taps the browser-takeover button or taps a URL in shell output.
    enum ActiveSecondarySheet: Identifiable {
        case takeoverBrowser
        case linkPreview(URL)

        var id: String {
            switch self {
            case .takeoverBrowser: return "takeoverBrowser"
            case .linkPreview(let url): return "linkPreview:\(url.absoluteString)"
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

    /// Lines per chunk — must match chunkedLines' default.
    private static let lazyRenderChunkLines = 40
    /// Initial reveal: ~200 lines = 5 chunks.
    private static let lazyRenderInitialChunks = 5
    /// Each subsequent batch: 200 more lines = 5 chunks.
    private static let lazyRenderBatchChunks = 5
    /// Byte cap for the initial reveal — clamp the initial chunk count so a
    /// few very long lines (< 200 lines but > 10KB) still load incrementally.
    private static let lazyRenderInitialByteCap = 10 * 1024

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
    private var currentSnapshot: ToolSnapshotItem? {
        guard let blockId = block.toolUseId else { return nil }
        return toolSnapshots.first(where: { $0.id == blockId })
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
            // Only the takeover path needs to notify upstream when dismissed.
            onTakeoverDone?()
        }) { sheet in
            switch sheet {
            case .takeoverBrowser:
                if let pool = browserPool {
                    BrowserSheetView(pool: pool, isAgentBusy: false)
                }
            case .linkPreview(let url):
                MinisLinkPreviewView(url: url, browserPool: browserPool)
            }
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
            default: textContent
            }
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

                            // Result content
                            Text(text)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Color(UIColor.label))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                                .padding(14)
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

                        // Result content
                        Text(sanitizeForDisplay(text))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color(UIColor.label))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                            .padding(14)
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

                            ForEach(chunks, id: \.id) { chunk in
                                Text(attributedShellLine(chunk.text))
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

                            Text(sanitizeForDisplay(text))
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(accentColor)
                                .frame(maxWidth: .infinity, minHeight: cardMinHeight, alignment: .topLeading)
                                .textSelection(.enabled)
                                .padding(14)
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
                                    ForEach(oldChunks, id: \.id) { chunk in
                                        Text(chunk.text)
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
                                    ForEach(newChunks, id: \.id) { chunk in
                                        Text(chunk.text)
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

            // Script content
            Text(script)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color(UIColor.label))
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
                .padding(12)
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

                    // Memory content body
                    VStack(alignment: .leading, spacing: 0) {
                        let chunks = isStreaming
                            ? Self.liveChunkedLines(memoryContent.isEmpty ? " " : memoryContent)
                            : Self.chunkedLines(memoryContent.isEmpty ? " " : memoryContent)
                        ForEach(chunks, id: \.id) { chunk in
                            Text(chunk.text)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(.pink.opacity(0.85))
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

                    // File content — chunked rendering
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(chunks, id: \.id) { chunk in
                            Text(chunk.text)
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
                    let allChunks = isLive
                        ? Self.liveChunkedLines(block.content.isEmpty ? " " : block.content)
                        : Self.chunkedLines(block.content.isEmpty ? " " : block.content)
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

    private var isLive: Bool {
        if case .streaming = block.toolStatus { return true }
        if case .running = block.toolStatus { return true }
        return false
    }

    var body: some View {
        Group {
            // If we have a persisted snapshot, prefer using it for the thumbnail
            if let snapshot, let snapshotImage = loadSnapshotImage(snapshot) {
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
                    Button {
                        let prev = (selectedIdx ?? displayedIdx) - 1
                        if prev >= 0 { withAnimation { selectedIdx = prev } }
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(displayedIdx > 0 ? ChatColors.primaryText : ChatColors.tertiaryText)
                            .frame(width: 20, height: 20)
                            .contentShape(Rectangle())
                    }
                    .disabled(displayedIdx <= 0)

                    Text("\(displayedIdx + 1)/\(toolBlocks.count)")
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(ChatColors.secondaryText)

                    Button {
                        let next = (selectedIdx ?? displayedIdx) + 1
                        if next < toolBlocks.count { withAnimation { selectedIdx = next } }
                    } label: {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(displayedIdx < toolBlocks.count - 1 ? ChatColors.primaryText : ChatColors.tertiaryText)
                            .frame(width: 20, height: 20)
                            .contentShape(Rectangle())
                    }
                    .disabled(displayedIdx >= toolBlocks.count - 1)
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
