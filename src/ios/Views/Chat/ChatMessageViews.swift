import SwiftUI

// MARK: - Context-menu preview

/// Opaque rounded-card preview shown under a message's long-press context menu.
///
/// [T-ios-longpress-menu-preview-background] The message cells are hosted with a
/// clear background (host.view / cell backgroundColor = .clear), and the message
/// `.contextMenu`s are attached either to a zero-size `Color.clear` overlay
/// (assistant block/footer) or to a bubble whose fill is the translucent
/// `tertiarySystemFill` (user). Without an explicit `preview:`, SwiftUI snapshots
/// that (transparent) attached view, so the long-press preview platter shows
/// through to the messages below — the reported "transparent preview" bug.
///
/// Supplying this as the `.contextMenu(preview:)` gives the platter an opaque
/// `systemBackground` rounded card with the message text, so it reads as a
/// floating card. System colors only, so dark mode stays correct.
///
/// [T-ios-longpress-preview-full-content-card] The earlier version hard-capped
/// the text at 1500 chars with a trailing "…", which is what users saw as a
/// "truncated" preview on long assistant messages. It now renders the FULL
/// message content inside a vertical ScrollView: short messages size to their
/// content, long ones are capped to a fraction of the screen height and scroll
/// inside the platter, so the preview is both opaque
/// and complete. An explicit measured height is required because a ScrollView
/// has no intrinsic height — without it the context-menu platter would collapse.
struct MessageContextMenuPreview: View {
    let text: String

    /// [T-ios-usermsg-preview-adaptive-width] Width is now a CAP, not a fixed
    /// size. A short user bubble ("ok") previously lifted into a full 360pt
    /// platter; the platter now hugs the laid-out text width (measured by the
    /// same GeometryReader that already measured height) and only long lines
    /// wrap at this cap, iMessage-style.
    private let maxCardWidth: CGFloat = 360
    /// Long messages cap the platter to this fraction of screen height and
    /// scroll inside; short messages size to content (min of the two).
    private var maxCardHeight: CGFloat { UIScreen.main.bounds.height * 0.55 }

    @State private var contentSize: CGSize = .zero

    /// [T-ios-ctxmenu-preview-watchdog] Hard cap on the characters handed to
    /// the preview's `Text`.
    ///
    /// This platter is a lift-to-peek preview: it is capped to 55% of screen
    /// height and scrolls, so only the first screenful is ever readable. But
    /// the `Text` was given the WHOLE reply, and the `GeometryReader` below
    /// forces SwiftUI to typeset all of it through CoreText — on the main
    /// thread — just to produce `contentSize`. Cost is super-linear in length.
    /// Measured at this exact font (16.5pt) and wrap width (360 - 2*16 = 328pt):
    ///
    ///      4,000 chars   0.25s
    ///      8,000 chars   0.98s
    ///     12,000 chars   1.63s
    ///     20,000 chars   4.50s
    ///     24,000 chars   6.48s     <- the reported 5591ms hang lands here
    ///     30,000 chars  10.14s
    ///     40,000 chars  17.96s
    ///
    /// which is the issue #284 foreground SIGKILL: a long reply, a main-thread
    /// hang of 5591ms in CTLineCreateWithAttributedString ->
    /// ResolvedStyledText.draw, watchdog kill, memory normal (not an OOM).
    ///
    /// Note the cost is driven by LENGTH, not by LaTeX: raw LaTeX source
    /// measured FASTER than prose at equal length (40,000 chars of formula
    /// source = 0.178s), because its long unspaced backslash runs produce far
    /// fewer line-break opportunities than CJK, where nearly every character is
    /// a break candidate. The reporter's LaTeX-heavy reply was simply a long
    /// one. Capping the string keeps this flat at ~0.06s for any input size.
    private static let maxPreviewChars = 2000

    var body: some View {
        let shown = text.isEmpty ? " " : String(text.prefix(Self.maxPreviewChars))
        ScrollView(.vertical, showsIndicators: true) {
            // No `.frame(maxWidth: .infinity)` here — the Text must report its
            // NATURAL width so short messages yield a narrow platter. The wrap
            // width comes from the ScrollView's width (the outer .frame below),
            // which starts at the cap and shrinks to the measured content.
            Text(shown)
                .font(.system(size: FontSettings.shared.scaledMessage(16.5)))
                .foregroundStyle(ChatColors.primaryText)
                .multilineTextAlignment(.leading)
                .padding(16)
                .background(
                    // Measure the laid-out text size so the platter can hug
                    // content (short) or cap + wrap/scroll (long).
                    GeometryReader { geo in
                        Color.clear
                            .preference(key: PreviewContentSizeKey.self, value: geo.size)
                    }
                )
        }
        .onPreferenceChange(PreviewContentSizeKey.self) { contentSize = $0 }
        // Size = content size, capped. Falls back to the caps until the first
        // measurement lands so the platter never collapses to zero. The small
        // width floor keeps an (attachment-only) empty-text preview from
        // rendering as a sliver.
        .frame(
            width: contentSize.width > 0 ? min(max(contentSize.width, 60), maxCardWidth) : maxCardWidth,
            height: contentSize.height > 0 ? min(contentSize.height, maxCardHeight) : maxCardHeight
        )
        .modifier(ContextMenuPreviewSurface())
    }
}

/// Background for the long-press preview platter.
///
/// Matches `UserBubbleSurface` so lifting a bubble doesn't jump from Liquid
/// Glass to a flat colour card — the shape (`RoundedRectangle(cornerRadius: 18)`,
/// non-`.continuous`) is deliberately the same one the bubble and the row's
/// `.contentShape(.contextMenuPreview, …)` already use.
///
/// **The opaque base stays.** [T-ios-longpress-menu-preview-background] exists
/// because the platter was showing through to the messages underneath: cells are
/// hosted on a clear background and the menus hang off either a zero-size
/// `Color.clear` overlay or a `tertiarySystemFill` bubble, so SwiftUI's snapshot
/// of the attached view was effectively transparent. Glass is a translucent
/// material, so using it ALONE would risk re-opening exactly that bug. Painting
/// it over `ChatColors.background` keeps the platter opaque no matter what the
/// system composites behind it, while the material still supplies the glass
/// highlight and edge that make it read as continuous with the bubble.
private struct ContextMenuPreviewSurface: ViewModifier {
    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 18)
    }

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                // Opaque floor first — see the note above.
                .background(shape.fill(ChatColors.background))
                .glassEffect(.regular, in: shape)
        } else {
            content.background(ChatColors.background)
        }
    }
}

/// Preference key carrying the laid-out preview text size up to the platter.
/// Background for the user message bubble.
///
/// Two states that must stay tellable apart at a glance:
///
///  - **Sent** (`isQueued == false`) — Liquid Glass on iOS 26+. These bubbles
///    scroll over other messages, images and code blocks, so the material has
///    genuinely varied content to sample; this is the case glass is for, the
///    same as the tool-status bar and unlike `FolderSurface`, which had to fall
///    back to a sampled constant for want of anything behind it.
///  - **Queued** (`isQueued == true`) — deliberately NOT glass, on either OS.
///    Glass reads as a settled, physical surface, which is the opposite of what
///    a not-yet-sent message means. It keeps the existing empty fill + dashed
///    border, so "queued" still looks provisional next to the solid glass of
///    everything already sent.
///
/// The text is the modified content rather than an `.overlay`, which is what
/// keeps it above the material: `.glassEffect` composites the material over the
/// view it modifies, so an overlaid label would be painted underneath it and
/// disappear (the FAB regression).
private struct UserBubbleSurface: ViewModifier {
    let isQueued: Bool

    /// Matches `.contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 18))`
    /// on the row exactly — including the default (non-`.continuous`) corner
    /// style. A `.continuous` bubble against a circular-arc preview clip would
    /// show the corners subtly change shape as the long-press lift begins.
    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 18)
    }

    func body(content: Content) -> some View {
        if isQueued {
            // Provisional look, identical on every OS version.
            content
                .background(shape.fill(Color.clear))
                .overlay(
                    shape
                        .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                        .foregroundStyle(ChatColors.secondaryText.opacity(0.5))
                )
        } else if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: shape)
        } else {
            content.background(shape.fill(ChatColors.userBubble))
        }
    }
}

private struct PreviewContentSizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        let next = nextValue()
        value = CGSize(width: max(value.width, next.width), height: max(value.height, next.height))
    }
}

// MARK: - Chat Message Row

struct ChatMessageRow: View {
    @ObservedObject var message: ChatMessage
    /// Only the actively streaming message needs vm access (for typing indicator & stop button).
    let isActiveMessage: Bool
    var commandStartTime: Date?
    var onStop: (() -> Void)?
    var onRetry: (() -> Void)?
    var onEdit: (() -> Void)?
    /// [T-ios-delete-from-message] Delete this user message and everything
    /// after it. Only set for non-queued user bubbles when idle.
    var onDeleteFrom: (() -> Void)?
    var onWithdraw: (() -> Void)?
    var autoRetryAttempt: Int = 0
    var autoRetryCountdown: Int = 0
    var canResume: Bool = false
    var onResume: (() -> Void)?
    var onCompact: (() -> Void)?
    var onCopyScreenshot: (() -> Void)?
    /// Read this whole reply aloud from the start (clears any in-progress TTS).
    /// Wired from AIChatView (which owns the view model). Disabled while the
    /// message is still streaming to avoid fighting the live streaming TTS.
    var onReadAloud: (() -> Void)?
    /// When set, presents the compact summary sheet outside the cell tree (V3).
    /// Falls back to a local .sheet when nil (non-V3 / standalone usage).
    var onShowCompactSummary: ((String) -> Void)?
    /// Triggers AIChatViewModel.revertCompact(). Wired from AIChatView so the
    /// row + sheet stay free of view-model imports.
    var onRevertCompact: (() -> Void)?
    var browserPool: BrowserTabPool?
    var toolSnapshots: [ToolSnapshotItem] = []
    @State private var showUsage = false
    @State private var showCompactSummary = false
    /// [T-ios-delete-from-message] Confirmation gate for the suffix delete.
    @State private var showDeleteFromConfirm = false
    /// Two-phase token usage reveal: space expands first, then content fades in.
    @State private var usageContentVisible = false
    /// Row frame in window coordinates — used to gate token-usage tap to bottom zone.
    @State private var rowFrameInWindow: CGRect = .zero
    private let usageTapLogger = AppLogger(category: "UsageTap")
    /// ID of the block currently highlighted after a copy action.
    @State private var highlightedBlockId: UUID?
    /// The tool block whose detail sheet is currently presented.
    /// Lifted out of ToolCapsuleView so ForEach item changes don't reset it.
    @State private var detailBlock: AssistantBlock?

    /// All text block contents joined, for "Copy All".
    private var fullReplyText: String {
        message.blocks
            .filter { if case .text = $0.kind { return true }; return false }
            .map(\.content)
            .joined(separator: "\n\n")
    }

    /// Full message including tool calls and their results, for clipboard export.
    private var fullMessageText: String {
        var parts: [String] = []
        for block in message.blocks {
            switch block.kind {
            case .text:
                if !block.content.isEmpty { parts.append(block.content) }
            case .shellTool(let command):
                let cmd = command.isEmpty ? block.toolDescription : command
                var s = "$ \(cmd)"
                if !block.content.isEmpty {
                    // content is "$ cmd\noutput…", strip the command line
                    let output = block.content.hasPrefix("$ ") ?
                        String(block.content.drop(while: { $0 != "\n" }).dropFirst()) : block.content
                    if !output.isEmpty { s += "\n\(output)" }
                }
                parts.append(s)
            case .fileReadTool(let path):
                parts.append("Read: \(path)\n\(block.content)")
            case .fileWriteTool(let path):
                parts.append("Write: \(path)\n\(block.content)")
            case .fileEditTool(let path):
                parts.append("Edit: \(path)\n\(block.content)")
            case .browserTool(let action):
                parts.append("Browser: \(action)\n\(block.content)")
            case .readImageTool(let path):
                parts.append("Image: \(path)")
            case .memoryTool(let action):
                parts.append("Memory: \(action)\n\(block.content)")
            case .delegateTool(let title):
                parts.append("Agent: \(title)\n\(block.content)")
            case .thinking:
                if !block.content.isEmpty { parts.append("[Thinking]\n\(block.content)") }
            case .info:
                if !block.content.isEmpty { parts.append(block.content) }
            }
        }
        return parts.joined(separator: "\n\n")
    }

    var body: some View {
        switch message.role {
        case .user:
            if let callback = message.agentCallback {
                AgentCallbackCellView(callback: callback, sessionId: nil)
                    .opacity(message.isCompactedHistory ? 0.5 : 1.0)
            } else {
                userRow
                    .opacity(message.isCompactedHistory ? 0.5 : 1.0)
            }
        case .assistant:
            assistantRow
                .opacity(message.isCompactedHistory ? 0.5 : 1.0)
        case .compactDivider:
            compactDividerRow
        case .systemInfo:
            systemDividerRow(icon: message.isCompactLoading ? nil : (message.systemIcon ?? "info.circle"),
                             loading: message.isCompactLoading, compact: false)
        }
    }

    // MARK: Compact Divider Row

    private var compactDividerRow: some View {
        HStack(spacing: 10) {
            VStack { Divider() }
            HStack(spacing: 5) {
                if message.isCompactLoading {
                    ProgressView()
                        .scaleEffect(0.6)
                        .frame(width: 12, height: 12)
                } else {
                    Image(systemName: "arrow.down.right.and.arrow.up.left")
                        .font(.system(size: 10))
                }
                Text(message.content)
                    .font(.system(size: 12, weight: .medium))
                if !message.isCompactLoading && message.compactSummary != nil {
                    Button {
                        let summary = message.compactSummary ?? ""
                        if let onShowCompactSummary {
                            onShowCompactSummary(summary)
                        } else {
                            showCompactSummary = true
                        }
                    } label: {
                        Image(systemName: "info.circle")
                            .font(.system(size: 12))
                    }
                    .buttonStyle(.plain)
                }
            }
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .fixedSize()
            VStack { Divider() }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
        .sheet(isPresented: $showCompactSummary) {
            CompactSummarySheet(summary: message.compactSummary ?? "", onRevert: onRevertCompact)
        }
    }

    // MARK: System Divider Row

    /// Shared divider-style row for system info messages.
    private func systemDividerRow(icon: String?, loading: Bool, compact: Bool) -> some View {
        HStack(spacing: 10) {
            VStack { Divider() }
            HStack(spacing: 5) {
                if loading {
                    ProgressView()
                        .scaleEffect(0.6)
                        .frame(width: 12, height: 12)
                } else if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 10))
                }
                Text(message.content)
                    .font(.system(size: 12, weight: .medium))
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(.secondary)
            // [T-browser-download-ux-v4] No .lineLimit(1).fixedSize() here:
            // fixedSize forced the text to its full ideal width, so a long
            // systemInfo line (e.g. pre-v3 "Downloaded … to /var/minis/… —
            // minis://…" rows persisted in history) overflowed BOTH screen
            // edges from this centered row. layoutPriority keeps the flexible
            // dividers from squeezing the text; past the available width the
            // text wraps (UIKit breaks unspaced tokens like paths/URLs
            // mid-word) instead of clipping.
            .layoutPriority(1)
            VStack { Divider() }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, compact ? 16 : 6)
    }

    // MARK: User Row

    /// User message text with `<user-attached-files>` metadata stripped out.
    private var userDisplayText: String {
        var text = message.content
        if let start = text.range(of: "<user-attached-files>") {
            let end = text.range(of: "</user-attached-files>")
            let endBound = end?.upperBound ?? text.endIndex
            text = String(text[text.startIndex..<start.lowerBound] + text[endBound...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text
    }

    private var userRow: some View {
        HStack {
            Spacer(minLength: 60)

            VStack(alignment: .trailing, spacing: 6) {
                // User-attached files above the text bubble
                if !message.attachments.isEmpty {
                    UserAttachmentList(attachments: message.attachments)
                } else if !message.inputAttachments.isEmpty {
                    // Queued message: show previews from cache before queue drain
                    QueuedAttachmentPreview(attachments: message.inputAttachments)
                }

                if !userDisplayText.isEmpty {
                    HStack(spacing: 6) {
                        Text(userDisplayText)
                            .font(.system(size: FontSettings.shared.scaledMessage(16.5)))
                            .foregroundStyle(message.isQueued ? ChatColors.secondaryText : ChatColors.primaryText)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .modifier(UserBubbleSurface(isQueued: message.isQueued))

                        if message.isQueued {
                            if let onWithdraw {
                                Button {
                                    onWithdraw()
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.system(size: 22))
                                        .foregroundStyle(.red)
                                }
                            }
                        }
                    }
                }
            }
            .modifier(MinisOpenURLHandler())
            .contentShape(Rectangle())
            // [T-ios-usermsg-contextmenu-preview-shape] The hit-test shape above
            // stays a full Rectangle (so the whole row is long-pressable), but
            // the context-menu PREVIEW must clip to the bubble's rounded shape —
            // otherwise the lifted preview shows square corners while the bubble
            // is RoundedRectangle(cornerRadius: 18). iOS 16+ lets us specify the
            // preview clip shape independently from the interaction shape.
            .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 18))
            .contextMenu {
                Button {
                    UIPasteboard.general.string = message.content
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }
                if let onCopyScreenshot {
                    Button {
                        onCopyScreenshot()
                    } label: {
                        Label(AppLocalized("Copy Screenshot"), systemImage: "camera.viewfinder")
                    }
                }
                if let onEdit {
                    Button {
                        onEdit()
                    } label: {
                        Label("Edit", systemImage: "square.and.pencil")
                    }
                }
                if let onRetry {
                    Button {
                        onRetry()
                    } label: {
                        Label("Retry", systemImage: "arrow.counterclockwise")
                    }
                }
                if onDeleteFrom != nil || onCompact != nil {
                    Divider()
                }
                if onDeleteFrom != nil {
                    Button(role: .destructive) {
                        showDeleteFromConfirm = true
                    } label: {
                        Label("Delete From Here", systemImage: "trash")
                    }
                }
                if let onCompact {
                    Button(role: .destructive) {
                        onCompact()
                    } label: {
                        Label("Compact Above", systemImage: "arrow.down.right.and.arrow.up.left")
                    }
                }
            } preview: {
                // [T-ios-longpress-menu-preview-background] Opaque card so the
                // long-press preview isn't transparent (see MessageContextMenuPreview).
                MessageContextMenuPreview(text: message.content)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        // [T-ios-delete-from-message] Suffix deletion is irreversible and takes
        // the following replies with it, so it gets an explicit confirmation
        // rather than firing straight off the menu.
        .alert(AppLocalized("Delete Message?"), isPresented: $showDeleteFromConfirm) {
            Button(AppLocalized("Cancel"), role: .cancel) {}
            Button(AppLocalized("Delete"), role: .destructive) {
                onDeleteFrom?()
            }
        } message: {
            Text("This message and all messages after it will be deleted. This cannot be undone.")
        }
    }

    // MARK: Assistant Row

    private var assistantRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Assistant label
            HStack(spacing: 6) {
                Image(systemName: "sparkles")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [Color(red: 0.72, green: 0.69, blue: 0.59),
                                     Color(red: 0.6, green: 0.6, blue: 0.55)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                AssistantSoulName()
                    .font(.body.weight(.semibold))
                    .foregroundStyle(ChatColors.primaryText)
            }
            .padding(.top, 4)

            ForEach(message.blocks) { block in
                AssistantBlockView(
                    block: block,
                    message: message,
                    isActiveMessage: isActiveMessage,
                    commandStartTime: commandStartTime,
                    onStop: onStop,
                    onTapBlank: message.usage != nil ? { windowPoint in
                        // Only respond to taps in the bottom 100pt of the message row
                        let bottomZoneTop = rowFrameInWindow.maxY - 100
                        let inZone = windowPoint.y >= bottomZoneTop
                        usageTapLogger.debug("[usageTap] windowPt=\(String(format: "%.0f,%.0f", windowPoint.x, windowPoint.y)) rowBottom=\(String(format: "%.0f", rowFrameInWindow.maxY)) zoneTop=\(String(format: "%.0f", bottomZoneTop)) inZone=\(inZone) showUsage=\(showUsage)")
                        guard inZone else { return }

                        if showUsage {
                            withAnimation(.easeInOut(duration: 0.15)) { usageContentVisible = false }
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                                showUsage = false
                            }
                        } else {
                            showUsage = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                                withAnimation(.easeInOut(duration: 0.2)) { usageContentVisible = true }
                            }
                        }
                    } : nil,
                    onCopyScreenshot: onCopyScreenshot,
                    browserPool: browserPool,
                    toolSnapshots: toolSnapshots,
                    highlightedBlockId: $highlightedBlockId,
                    detailBlock: $detailBlock
                )
            }

            // Typing indicator — "request out, nothing back yet", evaluated per
            // ROUND. See `ChatMessage.shouldShowTypingIndicator`.
            if isActiveMessage && message.shouldShowTypingIndicator {
                TypingIndicator()
            }

            // Inline error + retry
            if let error = message.error {
                inlineError(error)
            }

            // Resume banner for interrupted sessions
            if canResume && message.error == nil {
                resumeBanner
            }

            // Token usage — two-phase reveal: space expands first, then content fades in
            if message.streamInterruptCount > 0 || showUsage {
                HStack(spacing: 6) {
                    if message.streamInterruptCount > 0 {
                        streamInterruptBadge(message.streamInterruptCount)
                    }
                    if showUsage, let usage = message.usage {
                        usageCapsule(usage)
                            .opacity(usageContentVisible ? 1 : 0)
                    }
                    Spacer()
                    // [T-usage-capsule-time] Right-aligned, outside the
                    // capsule. Kept byte-for-byte in step with the SAME row in
                    // BridgedAssistantFooterV3 (CollectionViewMessageListV3),
                    // which is the copy an ASSISTANT reply actually renders —
                    // this one only ever draws user / compactDivider /
                    // systemInfo rows. Editing one without the other is how
                    // the clock shipped twice without ever being on screen.
                    if showUsage, let completedAt = message.completedAt {
                        Text(Self.completedAtFormatter.string(from: completedAt))
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(ChatColors.tertiaryText)
                            .opacity(usageContentVisible ? 1 : 0)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        // [T-ios-geometry-observer-crash] THE crasher from the v1.8b13 IPS:
        // onChange(of: geo.frame(in: .global)) wrote state inside the
        // geometry-observer path and tripped the async renderer
        // (ViewGraphGeometryObservers.needsUpdate SIGTRAP). onGeometryChange
        // measures the same row bounds the background GeometryReader did,
        // and its initial fire covers the old onAppear seed.
        .onGeometryChange(for: CGRect.self) { proxy in
            proxy.frame(in: .global)
        } action: { rowFrameInWindow = $0 }
        .background {
            // Context menu on the background layer so it only fires on
            // blank areas — UITextView link taps in the foreground take priority.
            Color.clear
                .contentShape(Rectangle())
                .contextMenu {
                    // [T-ios-msg-contextmenu-recursion-crash] Gate the eager menu
                    // tree behind an Equatable key so the cell body churn during
                    // `gh`/shell streaming output doesn't rebuild + re-diff the
                    // whole menu subtree every frame — the deep ContextMenuModifier
                    // / UnwrapConditional recursion that blew the update stack into
                    // unmapped heap (incident 98E8805F). Copy actions read
                    // `fullReplyText` lazily at TAP time, so the menu structure
                    // only needs rebuilding when the key changes: which optional
                    // actions exist + the streaming-disabled state.
                    EquatableMenuGate(key: AssistantMenuKey(
                        messageId: message.id,
                        hasReadAloud: onReadAloud != nil,
                        hasCopyScreenshot: onCopyScreenshot != nil,
                        hasCompact: onCompact != nil,
                        isActive: isActiveMessage
                    )) {
                        Button {
                            UIPasteboard.general.string = fullReplyText
                        } label: {
                            Label("Copy All", systemImage: "doc.on.doc")
                        }
                        Button {
                            UIPasteboard.general.string = fullReplyText
                        } label: {
                            Label(AppLocalized("Copy as Markdown"), systemImage: "text.quote")
                        }
                        if let onReadAloud {
                            Button {
                                onReadAloud()
                            } label: {
                                Label(AppLocalized("Read from Start"), systemImage: "play.circle")
                            }
                            // Greyed out while streaming so it can't clash with the
                            // live streaming TTS of the same reply.
                            .disabled(isActiveMessage)
                        }
                        if let onCopyScreenshot {
                            Button {
                                onCopyScreenshot()
                            } label: {
                                Label(AppLocalized("Copy Screenshot"), systemImage: "camera.viewfinder")
                            }
                        }
                        if let onCompact {
                            Divider()
                            Button(role: .destructive) {
                                onCompact()
                            } label: {
                                Label("Compact Above", systemImage: "arrow.down.right.and.arrow.up.left")
                            }
                        }
                    }
                    .equatable()
                } preview: {
                    // [T-ios-longpress-menu-preview-background] Opaque card for
                    // this Color.clear-attached contextMenu (see
                    // MessageContextMenuPreview).
                    MessageContextMenuPreview(text: fullReplyText)
                }
        }
        .sheet(item: $detailBlock) { block in
            // [T-agent-tool-sheet-unified] The agent block takes the same
            // live sheet as every other tool.
            ToolLiveSheet(toolBlocks: message.blocks.filter { $0.toolStatus != nil },
                          initialIdx: message.blocks.filter({ $0.toolStatus != nil }).firstIndex(where: { $0.id == block.id }) ?? 0,
                          toolSnapshots: toolSnapshots, browserPool: browserPool)
        }
    }

    /// [T-usage-capsule-time] 24-hour HH:mm for the capsule's completion clock.
    ///
    /// `en_US_POSIX` is not cosmetic: a bare `dateFormat = "HH:mm"` is still
    /// resolved against the device locale, and a region on a 12-hour clock
    /// renders it as 12-hour — so on those devices "22:30" would come out
    /// "10:30" with no AM/PM to tell them apart. Pinning the locale is what
    /// makes 24-hour actually mean 24-hour.
    ///
    /// Static because a DateFormatter is expensive to build and this runs per
    /// message per render pass.
    private static let completedAtFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm"
        return f
    }()

    @ViewBuilder
    private func usageCapsule(_ usage: TokenUsage) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "speedometer")
                .font(.system(size: 9))
            Text(usageSummary(usage))
                .font(.system(size: 10, design: .monospaced))
        }
        .foregroundStyle(ChatColors.tertiaryText)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(ChatColors.userBubble.opacity(0.6))
        .clipShape(Capsule())
        .transition(.opacity.combined(with: .scale(scale: 0.8)))
    }

    @ViewBuilder
    private func streamInterruptBadge(_ count: Int) -> some View {
        HStack(spacing: 3) {
            Image(systemName: "arrow.clockwise")
                .font(.system(size: 9, weight: .semibold))
            Text("\(count)")
                .font(.system(size: 10, design: .monospaced))
        }
        .foregroundStyle(Color.orange.opacity(0.8))
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Color.orange.opacity(0.12))
        .clipShape(Capsule())
    }

    private func usageSummary(_ u: TokenUsage) -> String {
        var parts: [String] = []
        if u.latestContextTokens > 0 {
            parts.append("ctx:\(formatTokenCount(u.latestContextTokens))")
        }
        parts.append("in:\(formatTokenCount(u.inputTokens))")
        parts.append("out:\(formatTokenCount(u.outputTokens))")
        if u.cacheReadTokens > 0 {
            parts.append("cache:\(formatTokenCount(u.cacheReadTokens))")
        }
        if u.cacheCreationTokens > 0 {
            parts.append("+cache:\(formatTokenCount(u.cacheCreationTokens))")
        }
        return parts.joined(separator: " ")
    }

    /// [T-ios-context-usage-hint] Shared with the composer usage line; see
    /// TokenCountFormatter.
    private func formatTokenCount(_ count: Int) -> String {
        TokenCountFormatter.short(count)
    }

    @ViewBuilder
    private func inlineError(_ error: String) -> some View {
        HStack(alignment: .center, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
                // [T-error-detail-visible issue #368] Was lineLimit(2), which
                // could not show the reason at all: a group failure composes one
                // "⚠️ <model> (<instance>): <reason>" line per attempted entry and
                // appends the real upstream description LAST
                // (groupExhaustedError, AIChatViewModel+Fallback.swift). Two lines
                // were spent on trail lines, so an upstream 400 such as
                // "reasoning_text … must be passed back" was always clipped —
                // measured on-simulator at 280pt and 340pt, where even a
                // single-entry trail cut the sentence mid-phrase.
                //
                // 8 lines fits a 3-4 entry trail plus the description at .caption.
                // Not tap-to-expand: these rows live in a self-sizing collection
                // view whose layout caches heights, so growing a cell in place
                // needs an explicit invalidateHeight round trip (see the
                // stale-height family in CollectionViewMessageListV3). A static
                // limit is measured correctly at first layout and cannot desync.
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(8)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            .contentShape(Rectangle())
            .contextMenu {
                Button {
                    UIPasteboard.general.string = error
                } label: {
                    Label(AppLocalized("Copy Error"), systemImage: "doc.on.doc")
                }
            }

            Spacer()

            if autoRetryAttempt > 0 {
                Text("Retry in \(autoRetryCountdown)s (\(autoRetryAttempt)/\(AIChatViewModel.retryDelays.count))")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.secondary.opacity(0.12))
                    .clipShape(Capsule())
            } else if let onRetry {
                Button(action: onRetry) {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.clockwise")
                            .font(.caption.weight(.semibold))
                        Text("Retry")
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(ChatColors.primaryText)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(ChatColors.primaryText.opacity(0.15))
                    .clipShape(Capsule())
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var resumeBanner: some View {
        HStack(alignment: .center, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "pause.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                Text("Interrupted — tap Resume to continue")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if let onResume {
                Button(action: onResume) {
                    HStack(spacing: 4) {
                        Image(systemName: "play.fill")
                            .font(.caption.weight(.semibold))
                        Text("Resume")
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.orange)
                    .clipShape(Capsule())
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // [T-ios-typing-indicator-scope] The local `hasVisibleContent` that used to
    // live here is gone: the typing indicator's predicate now has exactly one
    // definition, `ChatMessage.shouldShowTypingIndicator`. Keeping a private
    // copy per view is what let the four call sites drift apart in the first
    // place.
}


