import SwiftUI
import UIKit
import Combine
import UniformTypeIdentifiers

extension Notification.Name {
    /// Posted by toggleUsage to tell the Coordinator to re-snapshot so the
    /// footer cell is inserted or removed on demand.
    static let messageListNeedsResnapshot = Notification.Name("messageListNeedsResnapshot")

    /// [T-ios-user-attach-mount-invalidate #182-followup] Posted by the send
    /// queue's drain when it mounts processed attachments onto an
    /// already-inserted user message (`chatMsg.attachments = attMetas`).
    /// `object` is the message's UUID.
    ///
    /// The mount is a +70pt-per-row in-place content change on a cell whose
    /// item identity does not move, so nothing on the normal invalidation path
    /// (reconfigure / prepareForReuse) runs. Device forensics (msg 5956CCDD,
    /// log hash 1BFDAB45, 2026-08-08) showed the failure chain when the cell's
    /// FIRST real self-size lands inside the pre-mount window: the tile-less
    /// height (~68pt) enters `heightCache`, every later correct re-seed is
    /// rejected by `setPrecalcHeight`'s occupied-slot guard, and the
    /// width-matched cell cache + streaming-window short-circuit serve the
    /// stale height for the rest of the turn — the bubble truncates to one
    /// line with the tile seemingly detached above it. The Coordinator's
    /// handler breaks the chain with a TARGETED invalidation of that one cell.
    static let minisUserAttachmentsMounted = Notification.Name("minisUserAttachmentsMounted")
}

// MARK: - CollectionViewMessageListV3

/// V3 message list: builds on V2's cell-per-block model but simplifies
/// the scroll stability architecture:
///
/// - **Single height source**: UIKit self-sizing only (no GeometryReader)
/// - **3-tier height resolution**: measured cache → precalc (NSAttributedString) → estimate
/// - **2-state scroll model**: autoScrolling / userBrowsing (replaces V2's 6+ mechanisms)
/// - **O(1) message lookup**: UUID index rebuilt on each snapshot
struct CollectionViewMessageListV3: UIViewControllerRepresentable {
    @ObservedObject var vm: AIChatViewModel
    var inputFocused: Bool
    var onRetryMessage: ((UUID) -> Void)?
    var onRetryLast: (() -> Void)?
    /// [T-ios-assistant-header-open-soul] Tap on the assistant identity row.
    var onOpenSoulSettings: (() -> Void)?
    var onEdit: ((UUID) -> Void)?
    var onDeleteFrom: ((UUID) -> Void)?
    var onWithdraw: ((UUID) -> Void)?
    var onResume: (() -> Void)?
    var onStop: (() -> Void)?
    var onCompact: ((UUID) -> Void)?
    var onRevertCompact: (() -> Void)?
    var onForceSync: (() -> Void)?
    var onScreenshotImage: ((UIImage) -> Void)?
    var maxContentWidth: CGFloat
    var floatingBarHeight: CGFloat
    var inputBarHeight: CGFloat

    func makeUIViewController(context: Context) -> MessageListViewController {
        let vc = MessageListViewController()
        context.coordinator.attach(to: vc, vm: vm)
        // Drops are handled exclusively by the SwiftUI `.onDrop` on the chat
        // root view. Previously a UIDropInteraction here intercepted drops on
        // top of the collection view, which (a) suppressed SwiftUI's
        // isTargeted highlight on the first drop and (b) on iPadOS 26.5 left
        // SwiftUI's drop session state half-tracked, sticking the highlight
        // on the second drag. See T-filedrop GH#12.
        return vc
    }

    func updateUIViewController(_ vc: MessageListViewController, context: Context) {
        let coord = context.coordinator
        // [T-ios-scrollbtn-vm-split] The coordinator captured `vm` once in
        // makeUIViewController → attach. SwiftUI reuses this representable's
        // platform VC (and coordinator) across AIChatView re-creations, so when
        // the ViewModelCache hands the re-created view a NEW AIChatViewModel
        // instance for the same session, the coordinator kept driving the OLD
        // one: every isNearBottom write landed on a VM nothing was observing,
        // while the overlay's VM stayed at its initial isNearBottom=true — the
        // floating ↑/↓ buttons never appeared again for the whole session
        // (device ring trace: coordinator vm read false at every sync while
        // the buttons stayed hidden at diff≈2700pt from the bottom).
        coord.rebindViewModelIfNeeded(vm)
        coord.onRetryMessage = onRetryMessage
        coord.onOpenSoulSettings = onOpenSoulSettings
        coord.onRetryLast = onRetryLast
        coord.onEdit = onEdit
        coord.onDeleteFrom = onDeleteFrom
        coord.onWithdraw = onWithdraw
        coord.onResume = onResume
        coord.onStop = onStop
        coord.onCompact = onCompact
        coord.onRevertCompact = onRevertCompact
        coord.onForceSync = onForceSync
        coord.onScreenshotImage = onScreenshotImage
        coord.maxContentWidth = maxContentWidth

        // Bottom inset base = input bar + tool bar overlay height + breathing room.
        // applySubViewportCompensation is the only writer of cv.contentInset.bottom;
        // it reads coord.baseBottomInset and adds overflow on top when needed.
        //
        // [T-bottom-occluded 0a6d3c92] introduced +16 to fix a perceived
        // clip of the last bubble's bottom text. That report turned out
        // to be the cell-height bug fixed in [ClipFix 2026-05-16]
        // (boundingRect / mtv missing textContainerInset). With cell
        // heights now accurate the +16 breathing room becomes pure
        // dead space — observed as ~100pt gap between the last bubble
        // and the composer top. Restored to the historical +8.
        let bottomPad: CGFloat = inputBarHeight + floatingBarHeight + 8
        let baseChanged = abs(coord.baseBottomInset - bottomPad) > 0.5
        if baseChanged {
            let cv = vc.collectionView!
            let previousBase = coord.baseBottomInset
            let wasSmaller = previousBase < bottomPad
            // Capture follow-intent BEFORE mutating the inset: enlarging the
            // inset enlarges maxOffset, so a user pinned at the bottom would
            // measure as >20pt away from it and fail the isNearBottom() guard.
            let wasNearBottomBefore = coord.isNearBottom()
            coord.baseBottomInset = bottomPad
            // Apply the base immediately so contentInset.bottom reflects the
            // new base before applySubViewportCompensation runs (which may
            // further inflate it). Compensation will overwrite if needed.
            cv.contentInset.bottom = bottomPad
            // [T-inputbar-shrink-content-bump] Re-pin on BOTH directions, not just
            // growth.
            //
            // The old condition was `wasSmaller && …`, so only an ENLARGING inset
            // re-pinned. A shrink was left to UIKit — and UIKit's response to
            // `maxContentOffset` dropping below the current `contentOffset` is to
            // clamp it, which reads on screen as the whole conversation jerking
            // UPWARD by exactly the shrink amount, then settling back when the next
            // scroll/settle re-pins it.
            //
            // Field trace (first message + tool card): inputBarHeight went
            // 115.36 → 193.36 → 115.36 as the tool-execution card expanded and
            // collapsed. baseBottomInset therefore moved 123.36 → 201.36 → 123.36,
            // i.e. maxContentOffset fell by 78pt on the way back down. Pinned at the
            // bottom, the offset was 78pt past the new maximum and got clamped —
            // the reported "content bumps up a bit, then returns".
            //
            // Growth still needs the re-pin for the documented reason (a larger
            // inset enlarges maxOffset, so a pinned user would otherwise be left
            // 78pt short of the bottom). Shrink needs it to hide the clamp. The
            // follow-intent gate is unchanged, so a user who has scrolled away is
            // still never yanked to the bottom by a composer resize.
            if coord.scrollMode == .autoScrolling || wasNearBottomBefore {
                if !wasSmaller {
                    AppLogger(category: "ScrollDiag").info("[ScrollDiag][baseInsetShrink] \(String(format: "%.2f", previousBase))→\(String(format: "%.2f", bottomPad)) inputBar=\(String(format: "%.2f", inputBarHeight)) offset=\(String(format: "%.1f", cv.contentOffset.y)) — re-pinning to hide the UIKit clamp")
                }
                coord.scrollMode = .autoScrolling
                coord.scrollToBottomNow(animated: false)
            }
        }

        // [T-first-msg-top-gap 2026-07-18] Apply the top inset. User report
        // (iOS 16 screenshot): the first user bubble sat flush against the
        // opaque navbar — common to ALL of iOS 16-18, because
        // `baseContentInsetTop` (designed as "8pt manual padding on older
        // iOS", see its doc) was defined but never written into
        // cv.contentInset.top anywhere; the legacy nav layout doesn't extend
        // content behind the bar, so the collection view's own top edge IS
        // the navbar bottom and the first row rendered with zero breathing
        // room. iOS 26 returns 0 here (liquid-glass + .automatic adjustment
        // already position content), so this is a no-op there.
        let cvTop = vc.collectionView!
        if abs(cvTop.contentInset.top - coord.baseContentInsetTop) > 0.5 {
            cvTop.contentInset.top = coord.baseContentInsetTop
        }

        coord.applySubViewportCompensation(caller: "updateUIVC")

        // [T-message-list-empty-on-foreground] Backstop: if a snapshot was
        // deferred during a load (bind1) but binding 3's true→false edge was
        // missed (removeDuplicates collapse, or $isLoadingSession settling in
        // the same runloop cycle the snapshot was deferred), the list would be
        // stranded empty while the VM has messages. updateUIViewController runs
        // on every SwiftUI re-render (e.g. the toolbar updating — exactly the
        // observed "toolbar updates, list blank" symptom), so flush here once
        // loading has finished. Gated on NOT being in a streaming-suspend window
        // so we never pre-empt the suspend-resume machinery (which intentionally
        // holds hasPendingSnapshot during scroll-settle / background streaming);
        // that path has its own flush on resume. flushPendingSnapshotIfNeeded
        // no-ops when nothing is pending, so this is free on the happy path.
        if !vm.isLoadingSession && !vm.streamingUIUpdatesSuspended {
            coord.flushPendingSnapshotIfNeeded(caller: "updateUIVC-backstop")
        }

        if inputFocused != coord.lastInputFocused {
            let wasNearBottom = coord.scrollMode == .autoScrolling && coord.isNearBottom()
            coord.lastInputFocused = inputFocused
            if wasNearBottom {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak coord] in
                    coord?.scrollToBottomNow(animated: true)
                }
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    enum Section { case main }
}

// MARK: - V3 Bridged Cell Views (no GeometryReader)

/// Header: "✦ Minis" label at the top of each assistant turn.
/// Name comes from SOUL.md (user-editable in Soul Settings).
///
/// [T-ios-assistant-header-open-soul] Tapping the row opens Soul Settings.
/// The row shows the identity the user configured there, so it is the
/// obvious place to reach for when changing it.
private struct BridgedAssistantHeaderV3: View {
    @ObservedObject var message: ChatMessage
    var maxWidth: CGFloat = 0
    @State private var soulMeta: SoulMetadata = SoulStore.cachedMetadata
    /// [T-ios-assistant-header-open-soul] Injected, NOT read from the
    /// environment.
    ///
    /// `\.openMinisURL` is set on AIChatView, but each cell here is hosted in
    /// its own `UIHostingConfiguration`, which starts a fresh SwiftUI hierarchy
    /// rather than inheriting that one. Reading the environment inside a cell
    /// therefore silently yields the key's default — an action that returns
    /// `.systemAction` and does nothing. (Verified on device: the tap fired, no
    /// navigation happened, and no deep-link log line appeared.) So the handler
    /// is threaded down the same way every other cell callback already is.
    var onOpenSoulSettings: (() -> Void)?
    var body: some View {
        HStack(spacing: 6) {
            // [T-soul-custom-icon] Honours a user-chosen emoji or image;
            // falls back to the gradient sparkle when unset, so headers for
            // users who never customized are pixel-identical to before.
            // 18pt keeps the measured 28pt row height below intact — the
            // icon is square by construction (SoulIconImage.encode crops),
            // so an image cannot make this row taller than a glyph does.
            SoulIconView(
                icon: soulMeta.icon,
                size: 18,
                sparkleGradient: LinearGradient(
                    colors: [Color(red: 0.72, green: 0.69, blue: 0.59),
                             Color(red: 0.6, green: 0.6, blue: 0.55)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            Text(soulMeta.name.isEmpty ? "Minis" : soulMeta.name)
                .font(.body.weight(.semibold))
                .foregroundStyle(ChatColors.primaryText)
        }
        // [T-ios-assistant-header-open-soul] Hit area and tap, applied to the
        // icon+name HStack only — NOT to the full-width row below it.
        //
        // The `.frame(maxWidth: .infinity)` modifiers further down stretch this
        // header across the whole message column; making that tappable would
        // turn the empty space beside the name into a button and swallow taps
        // meant for the transcript.
        //
        // `contentShape` makes the whole icon+name box (including the 6pt gap)
        // hittable rather than only the glyph and text pixels.
        //
        // On the 44pt HIG target: the row is deliberately NOT grown to 44pt.
        // The comment above records that this header measures 28pt and that
        // the cell height is UIKit-self-sized, so enlarging the frame would
        // shift every assistant message in the transcript — the task explicitly
        // asks not to disturb layout. Instead the touch target is widened
        // horizontally (the tappable box spans icon + gap + full name, well
        // over 44pt across) and the 28pt height is left alone. That is the
        // trade the surrounding code forces; growing it would need the height
        // constant re-measured, which is out of scope here.
        .contentShape(Rectangle())
        .onTapGesture { onOpenSoulSettings?() }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(AppLocalized("Open Soul settings"))
        .accessibilityHint(AppLocalized("Change the assistant's name, icon and personality"))
        .onReceive(NotificationCenter.default.publisher(for: .soulMdChanged)) { _ in
            soulMeta = SoulStore.cachedMetadata
        }
        .padding(.top, 4)
        .padding(.bottom, 0)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(maxWidth: maxWidth > 0 ? maxWidth : .infinity, alignment: .leading)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .opacity(message.isCompactedHistory ? 0.5 : 1.0)
        .accessibilityIdentifier("assistantHeader")
    }
}

/// Single block within an assistant turn — text, tool capsule, or info.
/// V3: No GeometryReader — UIKit self-sizing is the sole height source.
private struct BridgedAssistantBlockV3: View {
    @ObservedObject var block: AssistantBlock
    @ObservedObject var message: ChatMessage
    @ObservedObject var bridge: CellStateBridgeV2
    var maxWidth: CGFloat

    private var blockAccessibilityId: String {
        switch block.kind {
        case .text: return "assistantTextBlock"
        case .thinking: return "assistantThinkingBlock"
        case .shellTool: return "assistantShellBlock"
        case .fileReadTool: return "assistantFileReadBlock"
        case .fileWriteTool: return "assistantFileWriteBlock"
        case .fileEditTool: return "assistantFileEditBlock"
        case .browserTool: return "assistantBrowserBlock"
        case .readImageTool: return "assistantReadImageBlock"
        case .memoryTool: return "assistantMemoryBlock"
        case .info: return "assistantInfoBlock"
        }
    }

    var body: some View {
        AssistantBlockView(
            block: block,
            message: message,
            isActiveMessage: bridge.isActiveMessage,
            commandStartTime: bridge.isActiveMessage ? bridge.commandStartTime : nil,
            onStop: bridge.isActiveMessage ? bridge.onStop : nil,
            onTapBlank: { _ in
                toggleUsage()
            },
            onCopyScreenshot: { bridge.onCopyScreenshot?() },
            // [T-selection-menu-minis-tts] Selection-menu TTS: whole-reply
            // replay is suppressed while this reply is still streaming (same
            // rule as the overlay context menu); Read Selection stays available.
            onReadAloud: bridge.isStreaming ? nil : bridge.onReadAloud,
            onSpeakText: bridge.onSpeakText,
            browserPool: bridge.browserPool,
            toolSnapshots: bridge.toolSnapshots,
            highlightedBlockId: .constant(nil),
            detailBlock: $bridge.detailBlock
        )
        .frame(maxWidth: maxWidth > 0 ? maxWidth : nil, alignment: .leading)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .opacity(message.isCompactedHistory ? 0.5 : 1.0)
        .accessibilityIdentifier(blockAccessibilityId)
        // Context menu on zero-size overlay so it doesn't inflate self-sizing
        // (same pattern as BridgedAssistantFooterV3).
        .overlay {
            Color.clear.frame(width: 0, height: 0)
                .contextMenu {
                    Button {
                        let text = message.blocks
                            .filter { if case .text = $0.kind { return true }; return false }
                            .map(\.content).joined(separator: "\n\n")
                        UIPasteboard.general.string = text
                    } label: {
                        Label(AppLocalized("Copy All"), systemImage: "doc.on.doc")
                    }
                    Button {
                        let text = message.blocks
                            .filter { if case .text = $0.kind { return true }; return false }
                            .map(\.content).joined(separator: "\n\n")
                        UIPasteboard.general.string = text
                    } label: {
                        Label(AppLocalized("Copy Markdown"), systemImage: "text.quote")
                    }
                    if let onReadAloud = bridge.onReadAloud {
                        Button {
                            onReadAloud()
                        } label: {
                            Label(AppLocalized("Read from Start"), systemImage: "play.circle")
                        }
                        // Greyed out while streaming so it can't clash with the
                        // live streaming TTS of the same reply.
                        .disabled(bridge.isStreaming)
                    }
                    if let onCopyScreenshot = bridge.onCopyScreenshot {
                        Button {
                            onCopyScreenshot()
                        } label: {
                            Label(AppLocalized("Copy Screenshot"), systemImage: "camera.viewfinder")
                        }
                    }
                    if let onForceSync = bridge.onForceSync {
                        Divider()
                        Button {
                            onForceSync()
                        } label: {
                            Label(AppLocalized("Force Sync"), systemImage: "arrow.triangle.2.circlepath.icloud")
                        }
                    }
                    if let onCompact = bridge.onCompact {
                        Divider()
                        Button(role: .destructive) {
                            onCompact()
                        } label: {
                            Label(AppLocalized("Compact Above"), systemImage: "arrow.down.right.and.arrow.up.left")
                        }
                    }
                } preview: {
                    // [T-ios-longpress-menu-preview-background] This .contextMenu
                    // is on a zero-size Color.clear overlay (kept zero-size to
                    // avoid inflating self-sizing), so without an explicit preview
                    // SwiftUI snapshots that transparent overlay → see-through
                    // preview. Supply an opaque card of the message text.
                    MessageContextMenuPreview(text: message.blocks
                        .filter { if case .text = $0.kind { return true }; return false }
                        .map(\.content).joined(separator: "\n\n"))
                }
        }
    }

    private func toggleUsage() {
        guard message.usage != nil else { return }
        if bridge.showUsage {
            withAnimation(.easeInOut(duration: 0.15)) { bridge.usageContentVisible = false }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                bridge.showUsage = false
                // The footer cell may have been omitted from the snapshot
                // (no content at build time). Notify the coordinator so it
                // re-snapshots and removes the now-empty footer.
                NotificationCenter.default.post(name: .messageListNeedsResnapshot, object: nil)
            }
        } else {
            bridge.showUsage = true
            // The footer cell may not exist yet — notify the coordinator to
            // re-snapshot and insert it.
            NotificationCenter.default.post(name: .messageListNeedsResnapshot, object: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                withAnimation(.easeInOut(duration: 0.2)) { bridge.usageContentVisible = true }
            }
        }
    }
}

/// Footer: typing indicator, error, resume banner, token usage.
/// V3: No GeometryReader. Sheet moved to zero-size overlay to prevent
/// inflated self-sizing from sheet-capable view modifiers.
private struct BridgedAssistantFooterV3: View {
    @ObservedObject var message: ChatMessage
    @ObservedObject var bridge: CellStateBridgeV2
    var maxWidth: CGFloat

    // showUsage and usageContentVisible are on bridge, toggled by double-tap on block cells.

    /// [T-ios-typing-indicator-scope] Single source of truth on `ChatMessage`
    /// — see `shouldShowTypingIndicator` for the per-round semantics.
    private var showsTypingIndicator: Bool {
        bridge.isActiveMessage && message.shouldShowTypingIndicator
    }

    /// True when any of the footer's conditional sections will actually
    /// render. When false the footer should collapse to zero height so it
    /// doesn't insert a phantom 4pt gap between the last assistant block
    /// and the next user message.
    private var hasFooterContent: Bool {
        let showTyping = showsTypingIndicator
        let showError = message.error != nil
        let showResume = bridge.canResume && message.error == nil
        let showUsageRow = message.streamInterruptCount > 0 || bridge.showUsage
        return showTyping || showError || showResume || showUsageRow
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Typing indicator — "request out, nothing back yet", evaluated per
            // ROUND. See `ChatMessage.shouldShowTypingIndicator`.
            if showsTypingIndicator {
                TypingIndicator()
            }

            // Inline error
            if let error = message.error {
                inlineError(error)
            }

            // Resume banner
            if bridge.canResume && message.error == nil {
                resumeBanner
            }

            // Token usage
            if message.streamInterruptCount > 0 || bridge.showUsage {
                HStack(spacing: 6) {
                    if message.streamInterruptCount > 0 {
                        streamInterruptBadge(message.streamInterruptCount)
                    }
                    if bridge.showUsage, let usage = message.usage {
                        usageCapsule(usage)
                            .opacity(bridge.usageContentVisible ? 1 : 0)
                    }
                    Spacer()
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(maxWidth: maxWidth > 0 ? maxWidth : nil, alignment: .leading)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, hasFooterContent ? 16 : 0)
        .padding(.vertical, hasFooterContent ? 2 : 0)
        .opacity(message.isCompactedHistory ? 0.5 : 1.0)
        .accessibilityIdentifier("assistantFooter")
        // Sheet + contextMenu on zero-size overlay so they don't inflate self-sizing.
        // Both .sheet and .contextMenu wrap the view in interaction containers that
        // report inflated heights to systemLayoutSizeFitting, causing height oscillation.
        .overlay {
            Color.clear.frame(width: 0, height: 0)
                .contextMenu {
                    Button {
                        let text = message.blocks
                            .filter { if case .text = $0.kind { return true }; return false }
                            .map(\.content).joined(separator: "\n\n")
                        UIPasteboard.general.string = text
                    } label: {
                        Label(AppLocalized("Copy All"), systemImage: "doc.on.doc")
                    }
                    Button {
                        let text = message.blocks
                            .filter { if case .text = $0.kind { return true }; return false }
                            .map(\.content).joined(separator: "\n\n")
                        UIPasteboard.general.string = text
                    } label: {
                        Label(AppLocalized("Copy Markdown"), systemImage: "text.quote")
                    }
                    if let onReadAloud = bridge.onReadAloud {
                        Button {
                            onReadAloud()
                        } label: {
                            Label(AppLocalized("Read from Start"), systemImage: "play.circle")
                        }
                        // Greyed out while streaming so it can't clash with the
                        // live streaming TTS of the same reply.
                        .disabled(bridge.isStreaming)
                    }
                    if let onCopyScreenshot = bridge.onCopyScreenshot {
                        Button {
                            onCopyScreenshot()
                        } label: {
                            Label(AppLocalized("Copy Screenshot"), systemImage: "camera.viewfinder")
                        }
                    }
                    if let onForceSync = bridge.onForceSync {
                        Divider()
                        Button {
                            onForceSync()
                        } label: {
                            Label(AppLocalized("Force Sync"), systemImage: "arrow.triangle.2.circlepath.icloud")
                        }
                    }
                    if let onCompact = bridge.onCompact {
                        Divider()
                        Button(role: .destructive) {
                            onCompact()
                        } label: {
                            Label(AppLocalized("Compact Above"), systemImage: "arrow.down.right.and.arrow.up.left")
                        }
                    }
                } preview: {
                    // [T-ios-longpress-menu-preview-background] Opaque preview
                    // for the footer's zero-size Color.clear contextMenu overlay.
                    MessageContextMenuPreview(text: message.blocks
                        .filter { if case .text = $0.kind { return true }; return false }
                        .map(\.content).joined(separator: "\n\n"))
                }
        }
    }

    // MARK: - Footer sub-views

    @ViewBuilder
    private func inlineError(_ error: String) -> some View {
        HStack(alignment: .center, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption).foregroundStyle(.red)
                Text(error).font(.caption).foregroundStyle(.red).lineLimit(2)
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
            if bridge.autoRetryAttempt > 0 {
                Text("Retry in \(bridge.autoRetryCountdown)s (\(bridge.autoRetryAttempt)/\(AIChatViewModel.retryDelays.count))")
                    .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Color.secondary.opacity(0.12)).clipShape(Capsule())
            } else if let onRetry = bridge.onRetry {
                Button(action: onRetry) {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.clockwise").font(.caption.weight(.semibold))
                        Text(AppLocalized("Retry")).font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(ChatColors.primaryText)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(ChatColors.primaryText.opacity(0.15)).clipShape(Capsule())
                }
            }
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.12)).clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var resumeBanner: some View {
        HStack(alignment: .center, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "pause.circle.fill").font(.caption).foregroundStyle(.orange)
                Text(AppLocalized("Interrupted — tap Resume to continue")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let onResume = bridge.onResume {
                Button(action: onResume) {
                    HStack(spacing: 4) {
                        Image(systemName: "play.fill").font(.caption.weight(.semibold))
                        Text(AppLocalized("Resume")).font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Color.orange).clipShape(Capsule())
                }
            }
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius: 10))
    }

    @ViewBuilder
    private func usageCapsule(_ usage: TokenUsage) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "speedometer").font(.system(size: 9))
            Text(usageSummary(usage)).font(.system(size: 10, design: .monospaced))
        }
        .foregroundStyle(ChatColors.tertiaryText)
        .padding(.horizontal, 8).padding(.vertical, 3)
        .background(ChatColors.userBubble.opacity(0.6)).clipShape(Capsule())
        .transition(.opacity.combined(with: .scale(scale: 0.8)))
    }

    @ViewBuilder
    private func streamInterruptBadge(_ count: Int) -> some View {
        HStack(spacing: 3) {
            Image(systemName: "arrow.clockwise").font(.system(size: 9, weight: .semibold))
            Text("\(count)").font(.system(size: 10, design: .monospaced))
        }
        .foregroundStyle(Color.orange.opacity(0.8))
        .padding(.horizontal, 7).padding(.vertical, 3)
        .background(Color.orange.opacity(0.12)).clipShape(Capsule())
    }

    private func usageSummary(_ u: TokenUsage) -> String {
        var parts: [String] = []
        if u.latestContextTokens > 0 { parts.append("ctx:\(fmtTok(u.latestContextTokens))") }
        parts.append("in:\(fmtTok(u.inputTokens))")
        parts.append("out:\(fmtTok(u.outputTokens))")
        if u.cacheReadTokens > 0 { parts.append("cache:\(fmtTok(u.cacheReadTokens))") }
        if u.cacheCreationTokens > 0 { parts.append("+cache:\(fmtTok(u.cacheCreationTokens))") }
        return parts.joined(separator: " ")
    }
    private func fmtTok(_ c: Int) -> String {
        c >= 1000 ? (c >= 10000 ? "\(c/1000)k" : String(format: "%.1fk", Double(c)/1000)) : "\(c)"
    }
}

/// Wraps the existing ChatMessageRow for user / compactDivider / systemInfo messages.
/// V3: No GeometryReader.
private struct BridgedWholeMessageV3: View {
    @ObservedObject var message: ChatMessage
    @ObservedObject var bridge: CellStateBridgeV2
    var maxWidth: CGFloat

    var body: some View {
        ChatMessageRow(
            message: message,
            isActiveMessage: false,
            commandStartTime: nil,
            onStop: nil,
            onRetry: bridge.onRetry,
            onEdit: bridge.onEdit,
            onDeleteFrom: bridge.onDeleteFrom,
            onWithdraw: bridge.onWithdraw,
            autoRetryAttempt: 0,
            autoRetryCountdown: 0,
            canResume: false,
            onResume: nil,
            onCompact: bridge.onCompact,
            onCopyScreenshot: bridge.onCopyScreenshot,
            onShowCompactSummary: bridge.onShowCompactSummary,
            browserPool: nil,
            toolSnapshots: []
        )
        .frame(maxWidth: maxWidth > 0 ? maxWidth : nil)
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier(message.role == .user ? "userMessage" : "wholeMessage")
    }
}

// MARK: - Per-type Cell Subclasses (enable correct reuse)

private final class WholeMessageCellV3: SelfSizingCell {}
private final class AssistantHeaderCellV3: SelfSizingCell {}
private final class AssistantBlockCellV3: SelfSizingCell {}
private final class AssistantFooterCellV3: SelfSizingCell {}

// MARK: - V3 Coordinator

extension CollectionViewMessageListV3 {

    /// Scroll mode: the only two states V3 needs.
    enum ScrollMode {
        /// Content growth pins offset to bottom. Self-sizing accepted normally.
        case autoScrolling
        /// User is reading above the bottom. Layout changes preserve offset.
        case userBrowsing
    }

    final class Coordinator: NSObject, UICollectionViewDelegate, UIScrollViewDelegate, UICollectionViewDataSourcePrefetching {
        // Callbacks
        var onRetryMessage: ((UUID) -> Void)?
        var onOpenSoulSettings: (() -> Void)?
        var onRetryLast: (() -> Void)?
        var onEdit: ((UUID) -> Void)?
        var onDeleteFrom: ((UUID) -> Void)?
        var onWithdraw: ((UUID) -> Void)?
        var onResume: (() -> Void)?
        var onStop: (() -> Void)?
        var onCompact: ((UUID) -> Void)?
        var onRevertCompact: (() -> Void)?
        var onForceSync: (() -> Void)?
        var onScreenshotImage: ((UIImage) -> Void)?
        var maxContentWidth: CGFloat = 0
        var lastInputFocused: Bool = false

        #if DEBUG
        deinit {
            // [DecelDisplayLink] CADisplayLink retains its target — must
            // invalidate on Coordinator teardown to break the cycle.
            decelDisplayLink?.invalidate()
        }
        #endif

        // === Scroll mode (replaces V2's 6+ mechanisms) ===
        var scrollMode: ScrollMode = .autoScrolling {
            didSet {
                // Mirror to the layout so its invalidationContext can glue the
                // streaming cell's bottom to the viewport while pinned (smooth
                // grow) and skip that adjustment while the user browses.
                // [T-ios-stream-natural-transitions]
                viewController?.messageListLayout?.isAutoScrollPinning = (scrollMode == .autoScrolling)
            }
        }
        /// True during settleAfterInteraction flush — suppresses contentSize KVO offset changes.
        private var isFlushing = false
        /// True while an animated setContentOffset is in flight. KVO grow events must not
        /// directly mutate contentOffset during this window — UIKit animation interpolation
        /// can produce a corrupted final offset. Instead, defer via scheduleCoalescedScroll().
        private var isAnimatingScrollToBottom = false

        // === O(1) Lookup Indices ===
        private var messageIndex: [UUID: Int] = [:]
        private var itemToIndex: [MessageListItem: Int] = [:]

        // === Snapshot State ===
        private var previousSnapshotIds: [MessageListItem] = []

        /// [T-ios-first-block-footer-gap] Per-message block count as of the last
        /// applied snapshot, used to detect the 0 -> >=1 edge that collapses the
        /// footer. Tracked in the DATA layer on purpose: the first attempt hung
        /// this off `blockContentFilledSignal`, which only fires for TEXT blocks
        /// going empty -> non-empty. A tool block is created already populated
        /// and never takes that path, so the footer was not invalidated until
        /// some later text block happened to stream — long after the gap was on
        /// screen. Counting blocks covers every kind (tool, thinking, text).
        private var lastBlockCountByMessage: [UUID: Int] = [:]

        /// [T-ios-first-block-footer-gap] Footer shape as of the previous
        /// `updateBridge` call, per message. Needed because the shape now
        /// includes fields read from `ChatMessage`, which this function does not
        /// mutate — the transition to detect happens between calls, not within
        /// one. Pruned alongside `cellBridges`.
        private var lastFooterShapeByMessage: [UUID: FooterHeightShape] = [:]
        private var snapshotMessages: [ChatMessage] = []

        // === Bridges ===
        private var cellBridges: [UUID: CellStateBridgeV2] = [:]

        // === Height Measurement Cache ===
        /// Caches measureAttributedStringHeight results by NSAttributedString identity.
        /// Avoids redundant TextKit layout on repeated snapshots for the same content.
        private var attrStringHeightCache: [ObjectIdentifier: CGFloat] = [:]
        /// [T-ios-user-msg-estimate-tail-jitter] Caches accurate user-bubble
        /// heights keyed by "content|width|fontsize" (user text has no
        /// cachedAttributedString identity to key on). Cleared alongside
        /// attrStringHeightCache on session change / font change.
        private var userBubbleHeightCache: [String: CGFloat] = [:]

        /// [T-ios-earlier-stream-seed-freeze] Memo for
        /// `liveAssistantIds(in:isProcessing:)`. `configureCell` runs per cell
        /// and the underlying rule walks every message's blocks, so without
        /// this the seed path would go O(cells x messages x blocks) on exactly
        /// the busiest frames (mid-stream, many visible cells). Keyed by the
        /// cheap (identity of last message, message count, isProcessing) tuple
        /// — every transition that can change the answer moves at least one of
        /// them, because a message becoming/ceasing to be live is always
        /// accompanied by `isProcessing` flipping or the block set changing
        /// within the same streaming generation, which `streamGeneration`
        /// tracks.
        private var liveIdsMemo: (key: String, ids: Set<UUID>)?

        /// Ids of assistant messages whose content can still grow. Thin
        /// memoizing wrapper over the shared static rule.
        private func liveAssistantIds(in messages: [ChatMessage], isProcessing: Bool) -> Set<UUID> {
            guard isProcessing else { return [] }
            let key = "\(messages.last?.id.uuidString ?? "-")|\(messages.count)|\(streamGeneration)"
            if let memo = liveIdsMemo, memo.key == key { return memo.ids }
            let ids = Self.liveAssistantMessageIds(messages: messages, isProcessing: isProcessing)
            liveIdsMemo = (key, ids)
            return ids
        }

        /// Bumped whenever block content/status changes in a way that can flip
        /// which messages are live, so `liveIdsMemo` recomputes.
        private var streamGeneration: Int = 0

        // === Thinking Block Toggle ===
        private var thinkingToggleSub: AnyCancellable?

        // === Attachment mount invalidation ===
        // [T-ios-user-attach-mount-invalidate] Drain mounts attachments onto an
        // already-inserted user message; see .minisUserAttachmentsMounted.
        private var attachMountSub: AnyCancellable?

        // === Async attachment size invalidate ===
        // [T-attachment-size-invalidate 2026-05-21] Image / video / thumbnail
        // async loads cause cell SwiftUI body to grow from placeholder to fit
        // size, but SelfSizingCell's broad-width cache short-circuits
        // preferredLayoutAttributesFitting so the cell never re-measures —
        // bottom of content gets clipped until the chat is re-entered.
        private var attachmentSizeChangedSub: AnyCancellable?
        /// [T-ios-image-squish-probe §2] Same-runloop-tick coalescing for
        /// `.minisAttachmentSizeChanged` bursts (a six-image message fires up
        /// to 12: header probe + decode completion per image). The handler is
        /// a full-list invalidate + reconfigure, so one pass per tick is the
        /// budget.
        private var attachmentSizeRefreshScheduled = false
        private var pendingAttachmentSizeUrls: [String] = []

        /// The actual `.minisAttachmentSizeChanged` work, run at most once per
        /// runloop tick with every URL that arrived in that tick.
        private func handleAttachmentSizeChanged(coalescedUrls: [String]) {
            let alog = AppLogger(category: "AttachmentSize")
            guard let cv = self.viewController?.collectionView,
                  let layout = cv.collectionViewLayout as? MessageListLayout else {
                alog.warning("[AttachmentSize] infra missing")
                return
            }
            let visibleIPs = cv.indexPathsForVisibleItems.sorted()
            var cleared = 0
            for ip in visibleIPs {
                if let cell = cv.cellForItem(at: ip) as? SelfSizingCell {
                    let before = cell.frame.size.height
                    cell.clearCachedHeight()
                    layout.invalidateHeight(at: ip.item)
                    cleared += 1
                    alog.info("[AttachmentSize] clear idx=\(ip.item) frameH=\(String(format: "%.1f", before))")
                }
            }
            // [T-attachment-cell-offscreen 2026-05-24]
            // A markdown-inline video/image whose initial estimate
            // was too small (e.g. text-only block estimator
            // returning ~50pt) is pushed off-screen by the cold
            // scroll-to-bottom — the visible-cells-only invalidate
            // above then misses it, and the cell stays at the
            // 200pt placeholder until an unrelated reconfigure
            // runs many seconds later. Always invalidate the
            // layout's height cache for ALL items so the next
            // measurement pass picks up the new attachmentBounds.
            let totalItems = self.dataSource?.snapshot().itemIdentifiers.count ?? 0
            for idx in 0..<totalItems {
                layout.invalidateHeight(at: idx)
            }
            if let snapshot = self.dataSource?.snapshot() {
                // Reconfigure every item — cheap (no diff), forces
                // every cell that gets re-displayed to re-measure.
                // We still see a visible reflow only on cells in
                // viewport; off-screen cells just get a corrected
                // cached height for next display.
                var snap = snapshot
                snap.reconfigureItems(snapshot.itemIdentifiers)
                self.dataSource?.apply(snap, animatingDifferences: false)
            }
            // [T-attachment-defer-invalidate 2026-05-24]
            // Defer invalidateLayout to the next runloop. reconfigureItems
            // schedules a SwiftUI hosting-config update on the next
            // runloop tick; calling invalidateLayout synchronously
            // makes UIKit re-query PLAF while the cell's
            // UIHostingConfiguration still wraps the STALE
            // attributedString (with the 200pt video placeholder),
            // so systemLayoutSizeFitting returns the old 375pt
            // height, caches it as lastComputedHeight, and locks
            // the cell at the wrong size until something else
            // triggers another reconfigure (10+ seconds later
            // when the user navigates back). Letting the runloop
            // turn first ensures the new attributedString is
            // mounted in the hosting view before we re-measure.
            DispatchQueue.main.async {
                // Clear cell-side caches AGAIN after the
                // reconfigure has propagated, then ask UIKit to
                // re-measure.
                for ip in cv.indexPathsForVisibleItems {
                    (cv.cellForItem(at: ip) as? SelfSizingCell)?.clearCachedHeight()
                }
                layout.invalidateLayout()
                // While we're still in the session-load clamp
                // window, re-pin to bottom — the corrected
                // heights shift the content offset and otherwise
                // leave the user looking at the wrong region.
                if self.clampAfterSessionLoad {
                    self.scrollToLastItem()
                }
            }
            alog.info("[AttachmentSize] coalesced=\(coalescedUrls.count) urls=\(coalescedUrls.map { $0.suffix(40) }.joined(separator: ",")) cleared=\(cleared)/\(visibleIPs.count) totalInvalidated=\(totalItems) clamp=\(self.clampAfterSessionLoad)")
        }

        // === Foreground/Background Content Tracking ===
        /// Total character count of the last assistant message's blocks when the app entered background.
        /// Used to decide whether layout invalidation is needed on foreground return.
        private var contentLengthAtBackground: Int?

        // === Streaming ===
        private var lastMessageId: UUID?
        private var lastMessageSub: AnyCancellable?
        private var lastMessageBlockCountSub: AnyCancellable?

        /// [T-ios-ui-blocks-lost-suspend-resume] Last observed value of the vm's
        /// `streamingUIUpdatesSuspended`, tracked so the vm-state subscription can
        /// detect a true→false transition and flush a snapshot deferred during the
        /// suspend window (see `suspendResumeSub`).
        private var lastSuspendedValue: Bool = false
        /// [T-ios-ui-blocks-lost-suspend-resume] Watches the vm for the moment
        /// `streamingUIUpdatesSuspended` clears, then flushes the pending snapshot.
        private var suspendResumeSub: AnyCancellable?

        /// Adaptive streaming throttle: last time flushStreamingLayout actually ran.
        private var lastStreamingLayoutTime: Date = .distantPast
        /// Trailing-edge work item so the final streaming update isn't lost.
        private var pendingStreamingLayout: DispatchWorkItem?


        /// Scroll coalescing: batches rapid scroll signals into one 80ms action.
        private var pendingScrollWork: DispatchWorkItem?

        // === Session Load ===
        /// When true, contentSize KVO keeps pinning to bottom until layout stabilizes.
        private var clampAfterSessionLoad: Bool = false
        /// Hard deadline to stop clamping (2s after session load).
        private var clampDeadline: Date = .distantPast

        // === Sheet Presenters ===
        private let sheetPresenter = ToolSheetPresenter()
        private let compactSummaryPresenter = CompactSummaryPresenter()
        private var sheetOverlayHost: UIHostingController<SheetOverlayView>?

        // === References ===
        private weak var viewController: MessageListViewController?
        private weak var vm: AIChatViewModel?
        private var dataSource: UICollectionViewDiffableDataSource<Section, MessageListItem>!
        private var subscriptions = Set<AnyCancellable>()
        private var contentSizeObservation: NSKeyValueObservation?

        // MARK: - Setup

        func attach(to vc: MessageListViewController, vm: AIChatViewModel) {
            self.viewController = vc
            self.vm = vm
            vc.loadViewIfNeeded()
            guard let collectionView = vc.collectionView else { return }
            collectionView.delegate = self
            collectionView.prefetchDataSource = self
            // Disable UIKit's main-thread cell prefetch. Profile (Time Profiler)
            // showed -[UICollectionView _prefetchItemsForPrefetchingContext:]
            // chains through _createPreparedCellForItemAtIndexPath → +UIView
            // performWithoutAnimation → _checkForPreferredAttributesInView →
            // SelfSizingCell.preferredLayoutAttributesFitting →
            // UIHostingContentView.systemLayoutSizeFitting, eating ~44ms (30%)
            // of one main-thread sample for a single off-screen BIG-CELL.
            // That's the 2-3 frame hitch users perceive as a sudden stutter
            // right before scrolling exposes a large text/table cell.
            // Disabling prefetch lets cells be sized only when they actually
            // come on-screen. Heights are still pre-estimated via setEstimatedHeight
            // in applySnapshot / configureCell, so layout positions don't shift.
            collectionView.isPrefetchingEnabled = false

            let wholeReg = UICollectionView.CellRegistration<WholeMessageCellV3, MessageListItem> {
                [weak self] cell, indexPath, item in
                self?.configureCell(cell, item: item, indexPath: indexPath)
            }
            let headerReg = UICollectionView.CellRegistration<AssistantHeaderCellV3, MessageListItem> {
                [weak self] cell, indexPath, item in
                self?.configureCell(cell, item: item, indexPath: indexPath)
            }
            let blockReg = UICollectionView.CellRegistration<AssistantBlockCellV3, MessageListItem> {
                [weak self] cell, indexPath, item in
                self?.configureCell(cell, item: item, indexPath: indexPath)
            }
            let footerReg = UICollectionView.CellRegistration<AssistantFooterCellV3, MessageListItem> {
                [weak self] cell, indexPath, item in
                self?.configureCell(cell, item: item, indexPath: indexPath)
            }

            dataSource = UICollectionViewDiffableDataSource<Section, MessageListItem>(
                collectionView: collectionView
            ) { cv, indexPath, item in
                switch item {
                case .wholeMessage:
                    return cv.dequeueConfiguredReusableCell(using: wholeReg, for: indexPath, item: item)
                case .assistantHeader:
                    return cv.dequeueConfiguredReusableCell(using: headerReg, for: indexPath, item: item)
                case .assistantBlock:
                    return cv.dequeueConfiguredReusableCell(using: blockReg, for: indexPath, item: item)
                case .assistantFooter:
                    return cv.dequeueConfiguredReusableCell(using: footerReg, for: indexPath, item: item)
                }
            }

            // Install sheet overlay (outside UIHostingConfiguration cell tree)
            if sheetOverlayHost == nil {
                let overlay = SheetOverlayView(toolPresenter: sheetPresenter, compactPresenter: compactSummaryPresenter)
                let host = UIHostingController(rootView: overlay)
                host.view.backgroundColor = .clear
                host.view.isUserInteractionEnabled = true
                host.view.frame = .zero
                vc.addChild(host)
                vc.view.addSubview(host.view)
                host.didMove(toParent: vc)
                sheetOverlayHost = host

                sheetPresenter.onDismiss = { [weak self] in
                    self?.flushPendingSnapshotIfNeeded(caller: "sheetDismiss")
                }
            }

            // ContentSize KVO — pin to bottom during autoScrolling
            contentSizeObservation = collectionView.observe(\.contentSize, options: [.new, .old]) {
                [weak self] cv, change in
                guard let self, !self.isFlushing else { return }
                let newH = change.newValue?.height ?? 0
                let oldH = change.oldValue?.height ?? 0
                guard newH != oldH else { return }
                let delta = newH - oldH

                // Session load stability: keep pinning to bottom until layout settles.
                // Use time-based deadline only — fire-count was too fragile on iPad
                // where width changes (sidebar) and async image loads produce many
                // contentSize KVO fires before the layout truly stabilizes.
                if self.clampAfterSessionLoad {
                    let maxOff = max(0, newH - cv.bounds.height + cv.adjustedContentInset.bottom)
                    if maxOff > 0 { cv.contentOffset.y = maxOff }
                    if Date() >= self.clampDeadline {
                        self.clampAfterSessionLoad = false
                    }
                    self.vm?.contentSizeDeltaSignal.send(delta)
                    return
                }

                if delta > 0 && self.scrollMode == .autoScrolling {
                    // Pin to bottom — but if an animated scroll is in flight, directly
                    // mutating contentOffset corrupts UIKit's animation interpolation.
                    // Defer to a coalesced scroll that fires after the animation ends.
                    if self.isAnimatingScrollToBottom {
                        self.scheduleCoalescedScroll()
                    } else {
                        let maxOff = newH - cv.bounds.height + cv.adjustedContentInset.bottom
                        if maxOff > 0 {
                            // [DIAG-3] If tracking/decel, auto-scroll mode is fighting user gesture
                            if cv.isTracking || cv.isDecelerating {
                                AppLogger(category: "StreamDiag").info("[KVO] ⚠️ PIN-WHILE-SCROLLING contentSize grew +\(String(format: "%.0f", delta)) offset \(String(format: "%.0f", cv.contentOffset.y))→\(String(format: "%.0f", maxOff)) tracking=\(cv.isTracking) decel=\(cv.isDecelerating) — MODE SHOULD BE userBrowsing")
                            }
                            cv.contentOffset.y = max(cv.contentOffset.y, maxOff)
                        }
                    }
                } else if delta > 0 && self.scrollMode == .userBrowsing {
                    // [DIAG-3b] Content grew while user is browsing — no pin.
                    // If a cell ABOVE viewport grew, all cells below shift down.
                    AppLogger(category: "StreamDiag").info("[KVO] contentSize grew +\(String(format: "%.0f", delta)) IN BROWSE MODE — no pin. offset=\(String(format: "%.0f", cv.contentOffset.y)) tracking=\(cv.isTracking) decel=\(cv.isDecelerating)")
                } else if delta < 0 && self.scrollMode == .autoScrolling {
                    // Content shrank while auto-scrolling — clamp to prevent overscroll.
                    // Skip during deceleration: settleAfterInteraction will handle it.
                    let maxOff = max(0, newH - cv.bounds.height + cv.adjustedContentInset.bottom)
                    if cv.contentOffset.y > maxOff && !cv.isDecelerating {
                        cv.contentOffset.y = maxOff
                    }
                } else if delta < 0 && self.scrollMode != .autoScrolling {
                    // Content shrank while user is browsing — clamp to prevent overscroll.
                    let maxOff = max(0, newH - cv.bounds.height + cv.adjustedContentInset.bottom)
                    if cv.contentOffset.y > maxOff && !cv.isTracking && !cv.isDecelerating {
                        cv.contentOffset.y = maxOff
                    }
                }
                // After settle, deferred heights are flushed and UIKit re-layouts
                // with real heights. If contentSize shrinks, the current offset may
                // exceed the new max — UIKit will hard-clamp it, causing a visible jump.
                // Pre-emptively clamp with performWithoutAnimation to avoid the jitter.
                if self.settleJustFinished && delta < -1 && !cv.isTracking && !cv.isDecelerating {
                    let maxOff = max(0, newH - cv.bounds.height + cv.adjustedContentInset.bottom)
                    if cv.contentOffset.y > maxOff {
                        AppLogger(category: "Settle").debug("[Settle] POST-SETTLE SHRINK clamp offset \(String(format: "%.1f→%.1f", cv.contentOffset.y, maxOff)) contentSize \(String(format: "%.1f→%.1f", oldH, newH))")
                        UIView.performWithoutAnimation {
                            cv.contentOffset.y = maxOff
                        }
                    }
                }
                // Forward contentSize changes to downstream observers.
                self.vm?.contentSizeDeltaSignal.send(delta)

                // Re-evaluate sub-viewport overflow compensation. When content
                // overflows the visible area but UIKit thinks no scroll is
                // needed, this inflates inset.top so we can pin past UIKit's
                // default clamp.
                self.applySubViewportCompensation(caller: "contentSizeKVO")

                // Sync vm.isNearBottom on every contentSize change. UIKit only
                // dispatches scrollViewDidScroll when contentOffset moves; in
                // sub-viewport streaming (offset clamped at -insetTop while
                // contentSize grows past the visible area), the published value
                // would otherwise stay stuck at its prior reading and the
                // scroll-to-bottom button never appears.
                if let vm = self.vm {
                    let nb = self.isNearBottom()
                    if vm.isNearBottom != nb {
                        vm.isNearBottom = nb
                    }
                }
                self.syncScrollFlags()
            }

            bindViewModel(vm)
        }

        // MARK: - Cell Configuration

        private func currentIndex(for item: MessageListItem) -> Int? {
            return itemToIndex[item]
        }

        private static let cellLogger = AppLogger(category: "CellPerf")
        private let screenshotLogger = AppLogger(category: "TurnScreenshot")
        /// [T-ios-copy-screenshot-diag-logs] Verbose stage-by-stage trace of
        /// captureScrollingTurnScreenshot used to chase the "top blank when
        /// long-press from middle/bottom" symptom that the first repair
        /// (5e3c6160) didn't fully eliminate. Separate category from
        /// TurnScreenshot so users can `grep ChatScreenshot` cleanly to
        /// pull just the diagnostic frames without the noisier turn-level
        /// summary lines.
        private let chatScreenshotLogger = AppLogger(category: "ChatScreenshot")
        /// Counts cells configured since last applySnapshot to detect first-cell timing.
        private var cellConfigCount: Int = 0
        private var firstCellConfigTime: CFAbsoluteTime = 0

        private func configureCell(_ cell: SelfSizingCell, item: MessageListItem, indexPath: IndexPath) {
            guard let vm else { return }
            let configStart = CACurrentMediaTime()
            cellConfigCount += 1
            if cellConfigCount == 1 {
                firstCellConfigTime = CFAbsoluteTimeGetCurrent()
            }
            let messages = snapshotMessages.isEmpty ? vm.messages : snapshotMessages
            let width = maxContentWidth

            // [T-viewgraph-race-lifetime 2026-05-20] All 4 cell config sites
            // route through `cell.applyContentConfiguration(_:)` (not raw
            // `cell.contentConfiguration =`) so the SelfSizingCell bumps its
            // configGeneration and clears the height cache when the hosting
            // configuration is swapped. Without it, the preferredLayoutAttributesFitting
            // generation guard never trips (gen never changes) and the
            // broad cache may return a stale height for the swapped content.
            // Both reduce the window for the ViewGraphGeometryObservers race.
            switch item {
            case .wholeMessage(let msgId):
                guard let msgIdx = messageIndex[msgId], msgIdx < messages.count else { return }
                let message = messages[msgIdx]
                let bridge = getOrCreateBridge(for: message, in: messages)
                cell.backgroundColor = .clear
                let config = UIHostingConfiguration {
                    BridgedWholeMessageV3(
                        message: message,
                        bridge: bridge,
                        maxWidth: width
                    )
                    // Suppress SwiftUI async display-link geometry observation
                    // to prevent use-after-free in ViewGraphGeometryObservers
                    // when UICollectionView recycles the cell.
                    .transaction { $0.disablesAnimations = true }
                    // UIHostingConfiguration creates an independent SwiftUI
                    // environment — it does NOT inherit EnvironmentObjects
                    // from the outer AIChatView. Descendant views inside the
                    // cell (notably ToolCapsuleView, which reads
                    // `@EnvironmentObject vm: AIChatViewModel` for its long-
                    // press menu's `vm.isProcessing` check) would trigger
                    // SwiftUI's "No ObservableObject of type AIChatViewModel
                    // found" fatal (EXC_BREAKPOINT) the moment the body
                    // evaluates — symptom: app crashes when waking from
                    // lock + tapping a session-complete notification, since
                    // the deep-link forces an immediate cell rebuild before
                    // any other view layer can re-inject the vm. Re-inject
                    // explicitly on every hosting config.
                    // [T-ios-tool-capsule-vm-envobject-crash]
                    .environmentObject(vm)
                }.minSize(width: 0, height: 0).margins(.all, 0)
                cell.applyContentConfiguration(config)

            case .assistantHeader(let msgId):
                guard let msgIdx = messageIndex[msgId], msgIdx < messages.count else { return }
                let message = messages[msgIdx]
                cell.backgroundColor = .clear
                let config = UIHostingConfiguration {
                    BridgedAssistantHeaderV3(message: message, maxWidth: width,
                                            onOpenSoulSettings: onOpenSoulSettings)
                        .transaction { $0.disablesAnimations = true }
                        .environmentObject(vm)
                }.minSize(width: 0, height: 0).margins(.all, 0)
                cell.applyContentConfiguration(config)

            case .assistantBlock(let msgId, let blockId):
                guard let msgIdx = messageIndex[msgId], msgIdx < messages.count else {
                    AppLogger(category: "SnapshotDiag").warning("[SnapshotDiag] configureCell MISS msgId=\(msgId.uuidString.prefix(8)) — messageIndex miss or OOB")
                    return
                }
                let message = messages[msgIdx]
                guard let block = message.blocks.first(where: { $0.id == blockId }) else {
                    AppLogger(category: "SnapshotDiag").warning("[SnapshotDiag] configureCell MISS blockId=\(blockId.uuidString.prefix(8)) in msg blocks=\(message.blocks.count) — block ID mismatch (vm.messages mutated since snapshot?)")
                    return
                }
                let bridge = getOrCreateBridge(for: message, in: messages)
                cell.backgroundColor = .clear
                let config = UIHostingConfiguration {
                    BridgedAssistantBlockV3(
                        block: block,
                        message: message,
                        bridge: bridge,
                        maxWidth: width
                    )
                    .transaction { $0.disablesAnimations = true }
                    .environmentObject(vm)
                }.minSize(width: 0, height: 0).margins(.all, 0)
                cell.applyContentConfiguration(config)

            case .assistantFooter(let msgId):
                guard let msgIdx = messageIndex[msgId], msgIdx < messages.count else { return }
                let message = messages[msgIdx]
                let bridge = getOrCreateBridge(for: message, in: messages)
                cell.backgroundColor = .clear
                let config = UIHostingConfiguration {
                    BridgedAssistantFooterV3(
                        message: message,
                        bridge: bridge,
                        maxWidth: width
                    )
                    .transaction { $0.disablesAnimations = true }
                    .environmentObject(vm)
                }.minSize(width: 0, height: 0).margins(.all, 0)
                cell.applyContentConfiguration(config)
            }

            // [T-ios-scroll-decel-height-drift] Seed this cell with the real
            // height the SAME content measured on a prior appearance (if any),
            // so its first self-size after reuse skips the 2.5–3.8ms
            // systemLayoutSizeFitting that produced the decel "settle jitter".
            // Must run AFTER applyContentConfiguration (which clears the seed).
            // Width-matched inside the memo so a stale portrait height isn't
            // reused after rotation. No measurement is performed here.
            if let layout = viewController?.messageListLayout {
                let cvW = viewController?.collectionView.bounds.width ?? 390
                let key = Self.contentKey(for: item, messages: messages, messageIndex: messageIndex)

                // [T-ios-scroll-decel-height-drift] Register the index→key map
                // on EVERY cell config, not just snapshot apply. The memo write
                // in invalidationContext keys off contentKeyByIndex[index]; if
                // it's only set during updateMessages (snapshot change), then
                // pure scrolling — where snapshot is unchanged and the jitter
                // actually happens — leaves the map stale/empty, so the measured
                // height is never memoized (observed: memoForKey=none on every
                // re-entry). Setting it here, where index↔item is authoritative
                // for the cell about to be measured, makes the memo actually
                // capture the self-size that follows.
                layout.setContentKey(key, at: indexPath.item)

                #if DEBUG
                // [T-ios-scroll-metrics-ring] Stamp the stable cell tag on the
                // cell and the layout's index map (only while recording — the
                // hash costs a string scan per configure).
                if ScrollMetricsRecorder.shared.isEnabled {
                    let tag = Self.stableCellTag(for: item, messages: messages, messageIndex: messageIndex)
                    cell.metricsTag = tag
                    layout.setMetricsTag(tag, at: indexPath.item)
                }
                #endif

                // [T-ios-scroll-decel-height-drift] STREAMING EXCLUSION. A cell
                // belonging to the actively-streaming (last assistant) message
                // changes height every token (tool capsule + code block grow as
                // output arrives). It must NEVER be seeded or memoized: a seed
                // would short-circuit self-size and freeze the cell at a stale
                // mid-stream height, leaving the tool cell / code block overlapping
                // adjacent content until the NEXT turn reconfigures it (observed
                // regression). The content-length key already differs per token,
                // but seeding ANY prior length is wrong while it's still growing —
                // so gate it out entirely. Non-streaming history cells keep the
                // seed benefit. Clearing contentKey also disables the memo write
                // on this cell's self-size path.
                // [T-ios-earlier-stream-seed-freeze] The exclusion must cover
                // EVERY message that can still grow, not just `messages.last`.
                // A tool block on an earlier assistant turn keeps producing
                // output (status .running/.streaming) while a newer turn
                // streams; that message's cells were therefore treated as
                // settled history here and seeded/memoized at a mid-stream
                // height. The seed short-circuit in
                // `SelfSizingCell.preferredLayoutAttributesFitting` then RETURNS
                // that height and skips the real measure, so the block froze
                // too short and the following blocks were stacked on top of it
                // — the reported "文字与工具卡片重叠交叉". Same rule (and now
                // the same code) as the layout's `streamingCellRanges`.
                let itemMsgId = Self.messageId(of: item)
                let isStreamingCell = itemMsgId.map { liveAssistantIds(in: messages, isProcessing: vm.isProcessing).contains($0) } ?? false
                if isStreamingCell {
                    cell.contentKey = nil
                    layout.invalidateMemo(forKey: key)
                }

                // [T-ios-memo-key-ignores-render-state] Look the memo up under
                // the SAME render-qualified key the measure path writes. Without
                // this the read would use the bare content key and the write the
                // qualified one, so the memo would never hit at all — the seed
                // optimisation would silently die rather than be corrected.
                //
                // Qualifying by render state is what makes the lookup honest: a
                // height measured while this block's formulas were still blank
                // placeholders lives under a DIFFERENT key than the finished
                // layout, so it can no longer be seeded onto a fully rendered
                // cell. That was the 989pt deficit measured on device (cell
                // 6166pt vs canvas 7154.7pt) — the cell was seeded with a
                // pre-render height and the seed short-circuit then skipped the
                // real measure forever.
                let memoKey = SelfSizingCell.renderQualifiedKey(key, for: cell)
                let memo = isStreamingCell ? nil : layout.measuredHeight(forKey: memoKey, width: cvW)
                if !isStreamingCell {
                    cell.contentKey = key
                }
                if let memo {
                    cell.seedMeasuredHeight(memo, width: cvW)
                }
                // [T-ios-user-msg-truncate-seed-starve] DO NOT seed from precalc.
                //
                // The seed short-circuit in SelfSizingCell.preferredLayoutAttributes-
                // Fitting RETURNS the seeded height and SKIPS the real
                // systemLayoutSizeFitting — so the seed value becomes the cell's
                // hard height. That is only safe for a height we ACTUALLY MEASURED
                // before (the `memo` above, from a prior real self-size at this
                // width). `precalcHeight` is an ESTIMATE (boundingRect /
                // char-count) that runs ~4-7pt SHORT of the real height for user
                // bubbles (TextKit line height is tighter than SwiftUI Text's
                // rendered line box). Seeding it locked
                // the cell at the short value, so the SwiftUI Text inside was
                // clipped to "…" while processing, and only expanded once a later
                // reconfigure forced a real measure — exactly the user-reported
                // truncate-then-expand. precalc still serves as prepare()'s
                // layout height (set via setPrecalcHeight in applySnapshot) so the
                // cell's initial POSITION is approximately right, but the cell's
                // real height is always decided by an actual measure, never frozen
                // at an estimate. (The first-mount-measure-storm 方案 D was trading
                // correctness for fewer measures here; correctness wins — a too-
                // short estimate that truncates content is worse than a measure.)
            }

            // Height estimation for items not yet measured — skip if we already
            // have a measured height OR a precalculated height from applySnapshot.
            if let layout = viewController?.messageListLayout,
               layout.cachedHeight(at: indexPath.item) == nil,
               !layout.hasPrecalcHeight(at: indexPath.item) {
                let est = Self.estimateItemHeight(item, messages: messages,
                                                  width: viewController?.collectionView.bounds.width ?? 390)
                layout.setEstimatedHeight(est, at: indexPath.item)
            }
        }

        /// Subscriptions for bridge detailBlock → sheet presenter forwarding.
        private var bridgeSheetSubs: [UUID: AnyCancellable] = [:]

        private func getOrCreateBridge(for message: ChatMessage, in messages: [ChatMessage]) -> CellStateBridgeV2 {
            if let existing = cellBridges[message.id] { return existing }
            let bridge = CellStateBridgeV2()
            cellBridges[message.id] = bridge
            updateBridge(bridge, message: message, in: messages, isInitialBridgeSetup: true)
            // Forward detailBlock changes to the VC-level sheet presenter
            bridgeSheetSubs[message.id] = bridge.$detailBlock
                .dropFirst()
                .receive(on: DispatchQueue.main)
                .sink { [weak self] block in
                    guard let self else { return }
                    if let block {
                        let toolBlocks = message.blocks.filter { $0.toolStatus != nil }
                        self.sheetPresenter.sheetData = ToolSheetPresenter.SheetData(
                            id: block.id,
                            block: block,
                            toolBlocks: toolBlocks,
                            toolSnapshots: bridge.toolSnapshots,
                            browserPool: bridge.browserPool
                        )
                    }
                    // Dismiss is handled by sheetPresenter.onDismiss (set in attach)
                }
            return bridge
        }

        // MARK: - Bridge Updates

        private func updateBridge(_ bridge: CellStateBridgeV2, message: ChatMessage, in messages: [ChatMessage],
                                  isInitialBridgeSetup: Bool = false) {
            guard let vm else { return }
            let isLast = message.id == messages.last?.id
            let isActive = isLast && vm.isProcessing

            // [T-ios-plaf-cache-footer-staleness] Snapshot the height-BEARING
            // subset before writing. These four decide whether the footer shows
            // a typing indicator / resume banner / retry row at all — i.e. the
            // difference between a ~4pt quiet footer and a ~48pt prominent one —
            // yet they are written here with no snapshot apply, no reconfigure
            // and no cache invalidation, so `SelfSizingCell`'s unbounded height
            // cache would keep reporting the pre-flip height forever.
            //
            // Compared as a BOOLEAN SHAPE, not by raw value: `autoRetryCountdown`
            // ticks once a second while only its zero/non-zero transition changes
            // the rendered height, and invalidating on every tick would re-enter
            // the hosting graph 30× per retry — exactly what the FB13213926
            // series exists to avoid.
            // [T-ios-first-block-footer-gap] Compare against the shape recorded
            // on the PREVIOUS call, not one taken from `message` at the top of
            // this one.
            //
            // The original code snapshotted `bridge` here and re-read it below,
            // which works because the lines in between mutate `bridge`. The two
            // new fields come from `message`, which this function never mutates
            // — comparing it with itself could never see a change. The edge we
            // need (`hasVisibleContent` false -> true) happens BETWEEN calls, as
            // the first block lands, so the previous call's shape is the only
            // valid baseline.
            let beforeShape = lastFooterShapeByMessage[message.id]
                ?? FooterHeightShape(bridge: bridge, message: message)

            bridge.isActiveMessage = isActive
            bridge.commandStartTime = vm.commandStartTime
            bridge.onStop = isActive ? { [weak self] in self?.onStop?() } : nil
            bridge.browserPool = vm.browserTabPool
            bridge.toolSnapshots = vm.toolSnapshots
            bridge.autoRetryAttempt = isLast ? vm.autoRetryAttempt : 0
            bridge.autoRetryCountdown = isLast ? vm.autoRetryCountdown : 0
            // [T-ios-session-status-mismatch] Defense-in-depth: even if vm.canResume
            // is somehow stale-true and vm.isProcessing hasn't flipped yet, do NOT
            // show the "Interrupted — tap Resume" banner while SessionActivityTracker
            // still lists this session as active (same signal the home list spinner
            // reads). This closes the residual gap where a new/reloaded VM sees a
            // toolResult-tail during a live loop's tool_result → next-model-call gap.
            let trackerActive: Bool = {
                guard isLast, let sid = vm.sessionId else { return false }
                return SessionActivityTracker.shared.isActive(sid)
            }()
            bridge.canResume = isLast ? (vm.canResume && !vm.isProcessing && !trackerActive) : false
            bridge.onResume = isLast ? { [weak self] in self?.onResume?() } : nil
            bridge.onWithdraw = message.isQueued ? { [weak self] in self?.onWithdraw?(message.id) } : nil
            // "Read from Start": replay this whole reply via TTS. Only for assistant
            // messages; disabled while it's the actively-streaming reply.
            bridge.isStreaming = isActive
            bridge.onReadAloud = (message.role == .assistant)
                ? { [weak vm] in vm?.readReplyFromStart(message) }
                : nil
            // [T-selection-menu-minis-tts] "Read Selection" from the text
            // selection menu — speaks the selected snippet via the Minis TTS
            // stack (sanitizer + provider voices + fail-over).
            bridge.onSpeakText = { [weak vm] text in vm?.speakText(text) }
            bridge.onCopyScreenshot = { [weak self, weak vm] in
                guard let self, let vm else { return }
                let msgs = vm.messages
                guard let anchorIdx = msgs.firstIndex(where: { $0.id == message.id }) else { return }
                let anchor = msgs[anchorIdx]
                let userId: UUID?
                let assistantId: UUID?
                switch anchor.role {
                case .user:
                    userId = anchor.id
                    assistantId = msgs[(anchorIdx + 1)...].first(where: { $0.role == .assistant })?.id
                case .assistant:
                    userId = msgs[..<anchorIdx].reversed().first(where: { $0.role == .user })?.id
                    assistantId = anchor.id
                default:
                    return
                }
                guard let userId else { return }
                // [T-ios-copy-screenshot-diag-logs] Entry-trace from the
                // menu callback so the user's grep of "ChatScreenshot"
                // catches the FULL chain, including which message they
                // long-pressed and the resolved user→assistant pair.
                self.chatScreenshotLogger.info("[Entry-MenuCallback] anchor=\(message.id.uuidString.prefix(8)) role=\(anchor.role) anchorIdx=\(anchorIdx)/\(msgs.count) → userId=\(userId.uuidString.prefix(8)) assistantId=\(assistantId?.uuidString.prefix(8) ?? "nil")")
                if let image = self.captureScrollingTurnScreenshot(userMessageId: userId, assistantMessageId: assistantId) {
                    self.onScreenshotImage?(image)
                }
            }

            let retryMsg = onRetryMessage
            let retryLast = onRetryLast
            let edit = onEdit
            let deleteFrom = onDeleteFrom
            let compact = onCompact
            let forceSync = onForceSync
            bridge.onForceSync = forceSync

            bridge.onShowCompactSummary = { [weak self] summary in
                guard let self else { return }
                // Update the presenter's revert callback every time the sheet
                // opens so it always points at the current vm. Captured weakly
                // so a closed-over coordinator that's been discarded can't
                // pin the view-model's lifetime.
                self.compactSummaryPresenter.onRevert = { [weak self] in
                    self?.onRevertCompact?()
                }
                self.compactSummaryPresenter.summary = summary
            }

            if message.isCompactedHistory || message.role == .compactDivider || message.role == .systemInfo {
                bridge.onRetry = nil; bridge.onEdit = nil; bridge.onCompact = nil
                bridge.onDeleteFrom = nil
            } else if !vm.isProcessing && !vm.isCompacting {
                if message.role == .user {
                    bridge.onRetry = { retryMsg?(message.id) }
                } else if isLast {
                    bridge.onRetry = { retryLast?() }
                } else {
                    bridge.onRetry = nil
                }
                bridge.onEdit = message.role == .user ? { edit?(message.id) } : nil
                // [T-ios-delete-from-message] Same gate as Edit/Retry: user
                // bubbles only, never while streaming or compacting, never on
                // already-compacted history (its agentHistory anchor is gone).
                // A QUEUED bubble isn't in agentHistory/DB yet — Withdraw is
                // its removal path, so this stays off for it.
                bridge.onDeleteFrom = (message.role == .user && !message.isQueued)
                    ? { deleteFrom?(message.id) } : nil
                bridge.onCompact = { compact?(message.id) }
            } else {
                bridge.onRetry = nil; bridge.onEdit = nil; bridge.onCompact = nil
                bridge.onDeleteFrom = nil
            }

            // [T-ios-plaf-cache-footer-staleness] If the footer's rendered SHAPE
            // changed, the cached height belongs to the old shape — drop it.
            // No-op on the overwhelmingly common path where nothing flipped.
            //
            // Skipped for a freshly-created bridge: that call comes from inside
            // the cell provider during `dataSource.apply`, and reaching back
            // into `cellForItem` mid-apply is a re-entrancy hazard for no gain —
            // a new bridge's cell is being configured from scratch anyway, so
            // `applyContentConfiguration` has already cleared its cache.
            let afterShape = FooterHeightShape(bridge: bridge, message: message)
            lastFooterShapeByMessage[message.id] = afterShape
            if !isInitialBridgeSetup, afterShape != beforeShape {
                invalidateFooterHeightCaches(messageId: message.id)
                AppLogger(category: "BottomGapDiag").info(
                    "[BottomGapDiag][footer-shape] msg=\(message.id.uuidString.prefix(8)) changed "
                    + "active=\(beforeShape.isActiveMessage)→\(afterShape.isActiveMessage) "
                    + "typing=\(beforeShape.showsTypingIndicator)→\(afterShape.showsTypingIndicator) "
                    + "— footer height caches dropped")
            }
        }

        /// [T-ios-plaf-cache-footer-staleness] The bridge fields that decide
        /// whether a footer row is rendered at all, reduced to the booleans the
        /// footer body actually branches on (see `hasFooterContent`).
        private struct FooterHeightShape: Equatable {
            let isActiveMessage: Bool
            let canResume: Bool
            let showUsage: Bool
            let hasRetryRow: Bool
            // [T-ios-first-block-footer-gap] The typing indicator's OTHER
            // input. This shape once tracked only `isActiveMessage`, so when
            // the indicator stopped being drawn the shape still compared EQUAL,
            // the cache was not dropped, and the footer kept the ~48pt height
            // it had measured while the indicator was on screen — a footer
            // occupying indicator-sized space with nothing in it, i.e. the
            // blank strip users reported.
            //
            // [T-ios-typing-indicator-scope] Now tracks the rendered predicate
            // itself rather than its ingredients. It toggles both ways within a
            // turn (each tool round: waiting -> producing -> waiting), and the
            // footer's height must follow every one of those edges.
            let showsTypingIndicator: Bool

            init(bridge: CellStateBridgeV2, message: ChatMessage) {
                isActiveMessage = bridge.isActiveMessage
                canResume = bridge.canResume
                showUsage = bridge.showUsage
                // Zero/non-zero only — see the note in updateBridge about the
                // per-second countdown tick.
                hasRetryRow = bridge.autoRetryAttempt != 0
                showsTypingIndicator = bridge.isActiveMessage
                    && message.shouldShowTypingIndicator
            }
        }


        private func updateLastCellBridge() {
            guard let vm else { return }
            let messages = vm.messages
            guard let lastMsg = messages.last else { return }
            if let bridge = cellBridges[lastMsg.id] {
                updateBridge(bridge, message: lastMsg, in: messages)
            }
            if messages.count >= 2 {
                let prev = messages[messages.count - 2]
                if let prevBridge = cellBridges[prev.id], prevBridge.canResume {
                    prevBridge.canResume = false
                    prevBridge.onResume = nil
                }
            }
            for msg in messages.dropLast() {
                if msg.role == .assistant, let b = cellBridges[msg.id], b.isActiveMessage {
                    b.isActiveMessage = false
                }
            }
            for msg in messages where msg.role == .user {
                if let b = cellBridges[msg.id] {
                    updateBridge(b, message: msg, in: messages)
                }
            }
        }

        // MARK: - Bindings

        /// [T-ios-scrollbtn-vm-split] Re-point the coordinator at a NEW view
        /// model instance when SwiftUI hands the (reused) representable a
        /// different AIChatViewModel for the same mounted chat view. All
        /// Combine subscriptions live in bindViewModel and are keyed to the
        /// old instance — cancel them wholesale and re-subscribe, then push
        /// the current snapshot + scroll flags into the new VM so its
        /// isNearBottom/messages state reflects reality instead of the
        /// initial defaults nobody would otherwise ever update.
        func rebindViewModelIfNeeded(_ newVM: AIChatViewModel) {
            if let current = vm, current === newVM { return }
            let hadOld = vm != nil
            #if DEBUG
            ScrollMetricsRecorder.shared.record(
                kind: "rebind", a: hadOld ? 1 : 0,
                note: Self.nbDump(viewController?.collectionView))
            #endif
            AppLogger(category: "ScrollDiag").info("[ScrollDiag][rebind] coordinator vm \(hadOld ? "changed" : "was nil") → rebinding subscriptions + resyncing flags")
            subscriptions.removeAll()
            lastMessageSub?.cancel(); lastMessageSub = nil
            lastMessageBlockCountSub?.cancel(); lastMessageBlockCountSub = nil
            lastMessageId = nil
            vm = newVM
            bindViewModel(newVM)
            // Re-apply from the new VM's authoritative messages (identity may
            // be equal content-wise; applySnapshot no-ops on identical ids)
            // and push the CURRENT geometry into the new VM's published flags.
            if !newVM.isLoadingSession {
                applySnapshot(messages: newVM.messages, caller: "rebind")
            }
            syncScrollFlags()
        }

        private func bindViewModel(_ vm: AIChatViewModel) {
            // 1. Messages → snapshot
            //    Skip while isLoadingSession — binding 3 handles the post-load apply.
            //    This prevents a redundant apply when $messages and $isLoadingSession
            //    dispatch to main queue in the same runloop cycle.
            vm.$messages
                .receive(on: DispatchQueue.main)
                .sink { [weak self] msgs in
                    guard let self else { return }
                    // [T-message-list-empty-on-foreground] If a load is in flight,
                    // DON'T silently drop this update — applySnapshot would no-op
                    // (it early-returns while isLoadingSession). Record it as
                    // pending so binding 3's true→false edge flushes it. Dropping
                    // it was the empty-list bug: when $messages changed during a
                    // foreground-recovery reload but binding 3's edge was collapsed
                    // by removeDuplicates (or $messages landed just after loading
                    // flipped false in the same cycle), nothing ever re-applied —
                    // the list stayed on its stale/empty snapshot while the VM
                    // (and toolbar) had the full messages.
                    if self.vm?.isLoadingSession == true {
                        self.hasPendingSnapshot = true
                        return
                    }
                    self.flushPendingSnapshotIfNeeded(caller: "bind1-messages")
                    self.applySnapshot(messages: msgs, caller: "bind1-messages")
                }
                .store(in: &subscriptions)

            // 1c. [T-ios-ui-blocks-lost-suspend-resume] Suspend-resume flush.
            //     `streamingUIUpdatesSuspended` is a plain (private(set)) var on
            //     the vm — not @Published — but every state change that clears it
            //     (scroll-settle resume, session-transition resume, the SSE final
            //     flush) runs through the vm's @Published/objectWillChange surface.
            //     We piggy-back on `vm.objectWillChange` and edge-detect the
            //     true→false transition ourselves via `lastSuspendedValue`.
            //
            //     Why this is the missing path: during a long multi-tool agent
            //     loop, blocks appended while suspended only set
            //     `hasPendingSnapshot = true` (bind4b); the stream then ends and
            //     cancels the per-message subs, and the terminal rescue at
            //     stream-end skips the flush because suspend is still true (see
            //     the "STREAM-END left pending (still suspended)" log). After that
            //     nothing re-applies — `$messages` won't fire (blocks grew, the
            //     array didn't) and the block-count sub is gone. This hook fires
            //     the instant suspend lifts. Gated on `!isProcessing` so a
            //     mid-stream resume is left to the live subscriptions / settle
            //     path; only the stranded post-stream case is rescued here.
            lastSuspendedValue = vm.streamingUIUpdatesSuspended
            suspendResumeSub = vm.objectWillChange
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    guard let self, let vm = self.vm else { return }
                    let now = vm.streamingUIUpdatesSuspended
                    let was = self.lastSuspendedValue
                    self.lastSuspendedValue = now
                    // Only act on a genuine true→false transition.
                    guard was, !now else { return }
                    // [T-ios-ui-frozen-on-tool-while-loop-runs] Flush REGARDLESS
                    // of isProcessing. The old `isProcessing == false` guard left
                    // a mid-loop foreground-resume unflushed: when the app returns
                    // to foreground (resumeAllStreamingUI clears the flag) while
                    // the agent loop is STILL running, the deferred snapshot was
                    // skipped and — because the loop never hits stream-end — the
                    // UI stayed frozen on the last-rendered tool for minutes while
                    // many loop turns ran un-rendered, until an unrelated
                    // sheetDismiss/settle happened to flush. Resume always runs
                    // foreground, so applying the snapshot here is safe.
                    guard self.hasPendingSnapshot else { return }
                    AppLogger(category: "PendingSnap").info("[PendingSnap] suspend-resume flush applied (hasPendingSnapshot=true, isProcessing=\(vm.isProcessing))")
                    self.flushPendingSnapshotIfNeeded(caller: "suspend-resume")
                }
            suspendResumeSub?.store(in: &subscriptions)

            // 1c. [T-ios-ui-blocks-lost-suspend-resume] Resume hook. When the vm
            //     clears `streamingUIUpdatesSuspended` (true→false) from ANY path,
            //     rebuild a snapshot that was deferred during the suspend window.
            //     Failure chain this fixes: during a multi-tool agent loop, new
            //     blocks append while suspended → blockCountSub only sets
            //     hasPendingSnapshot=true; the stream then ends and cancels the
            //     per-message subs; the terminal rescue sees still-suspended and
            //     skips the flush; nothing else can fire it. This hook is the
            //     missing trigger when suspend finally lifts. Only flush when the
            //     loop is no longer processing (a mid-stream resume is handled by
            //     the live subscriptions / settle path).
            vm.onStreamingUIUpdatesResumed = { [weak self] in
                guard let self else { return }
                // Always main-thread: the chokepoint runs on the main actor, but
                // hop defensively so flush never races a background caller.
                let work = {
                    // [T-ios-ui-frozen-on-tool-while-loop-runs] Flush regardless
                    // of isProcessing — see the objectWillChange sub above. A
                    // foreground-resume while the loop is still running must
                    // re-apply the deferred snapshot, or the UI freezes on the
                    // last-rendered tool until an unrelated sheetDismiss/settle.
                    guard self.hasPendingSnapshot else { return }
                    AppLogger(category: "PendingSnap").info("[PendingSnap] suspend-resume flush (hasPendingSnapshot=true, isProcessing=\(self.vm?.isProcessing == true))")
                    self.flushPendingSnapshotIfNeeded(caller: "suspend-resume")
                }
                if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
            }

            // 1b. Retry truncation signal — `retryFromToolBlock`'s sub-message
            //     path mutates `messages[i].blocks` in place without changing
            //     the messages array count, so `$messages` won't fire and the
            //     diffable snapshot keeps its pre-cut block identifiers.
            //     This signal forces a re-apply so stale blocks are removed.
            vm.retrySnapshotReloadSignal
                .receive(on: DispatchQueue.main)
                .sink { [weak self] in
                    guard let self, let msgs = self.vm?.messages else { return }
                    let oldCount = self.dataSource?.snapshot().itemIdentifiers.count ?? 0
                    self.applySnapshot(messages: msgs, caller: "retrySnapshotReload")
                    let newCount = self.dataSource?.snapshot().itemIdentifiers.count ?? 0
                    if newCount != oldCount {
                        let totalBlocks = msgs.reduce(0) { $0 + $1.blocks.count }
                        AppLogger(category: "BlocksLost").info("[BlocksLost] SNAPSHOT REBUILT snapshotItems \(oldCount)→\(newCount) memoryBlocks=\(totalBlocks)")
                    }
                }
                .store(in: &subscriptions)

            // 2. Scroll signals
            vm.scrollToBottomSignal
                .receive(on: DispatchQueue.main)
                .sink { [weak self] in
                    guard let self, self.scrollMode == .autoScrolling else { return }
                    self.scrollToBottomNow()
                }
                .store(in: &subscriptions)

            // 2b. Force scroll
            vm.forceScrollToBottom
                .receive(on: DispatchQueue.main)
                .sink { [weak self] in
                    guard let self else { return }
                    let cv = self.viewController?.collectionView
                    let layout = self.viewController?.messageListLayout
                    let cachedCount = layout?.debugCachedHeightCount ?? 0
                    let precalcCount = layout?.debugPrecalcHeightCount ?? 0
                    AppLogger(category: "ScrollDiag").info("[ScrollDiag][forceScrollToBottom] window=\(cv?.window != nil) contentSize=\(String(format: "%.0f", cv?.contentSize.height ?? 0)) bounds=\(String(format: "%.0f", cv?.bounds.height ?? 0)) adjInsetBot=\(String(format: "%.0f", cv?.adjustedContentInset.bottom ?? 0)) heightCache=\(cachedCount) precalc=\(precalcCount) visibleCells=\(cv?.visibleCells.count ?? 0)")
                    self.vm?.setStreamingUIUpdatesSuspended(false)
                    self.scrollMode = .autoScrolling
                    // Jumping to the bottom resets the up-button's turn-walk.
                    self.lastJumpedUserId = nil
                    self.scrollToBottomNow(animated: true)
                }
                .store(in: &subscriptions)

            // 2c. Up-button: walk back one user turn at a time. Browse action —
            // do NOT re-enable auto-scroll (leave scrollMode as-is so streaming
            // doesn't yank the user back down after they jump up).
            vm.forceScrollToTop
                .receive(on: DispatchQueue.main)
                .sink { [weak self] in
                    self?.scrollToPreviousUserTurn(animated: true)
                }
                .store(in: &subscriptions)

            // 3. Session load
            vm.$isLoadingSession
                .removeDuplicates()
                .dropFirst()   // skip initial false — only react to true→false after actual load
                .receive(on: DispatchQueue.main)
                .sink { [weak self] isLoading in
                    guard let self, !isLoading else { return }
                    // Switching into a session resets the up-button turn-walk.
                    self.lastJumpedUserId = nil
                    let isFirstLoad = self.previousSnapshotIds.isEmpty
                    // Only clear caches when items will actually change — prevents
                    // a duplicate dispatch from clearing caches that won't be rebuilt.
                    if let msgs = self.vm?.messages {
                        self.viewController?.messageListLayout?.clearHeightCache()
                        self.attrStringHeightCache.removeAll()
                        self.userBubbleHeightCache.removeAll()
                        // This is the authoritative post-load apply; it carries the
                        // latest messages, so any snapshot deferred during the load
                        // window (bind1 set hasPendingSnapshot) is now satisfied.
                        // [T-message-list-empty-on-foreground]
                        self.hasPendingSnapshot = false
                        self.applySnapshot(messages: msgs, caller: "bind3-sessionLoad")
                    }
                    if isFirstLoad {
                        // First load: scroll to bottom and clamp.
                        // 8s window (was 2s) so async video thumbnail
                        // generation can complete and the cell-height
                        // correction reaches the user before we release
                        // the bottom pin.
                        self.scrollMode = .autoScrolling
                        self.clampAfterSessionLoad = true
                        self.clampDeadline = Date().addingTimeInterval(8.0)
                        self.scrollToLastItem()
                    }
                    // Sync reload: keep current scroll position, just update data
                }
                .store(in: &subscriptions)

            // 4. Streaming: subscribe to last assistant message + block count changes.
            // Use the last assistant message (not msgs.last) because queued user
            // messages can be appended while an assistant is still streaming.
            vm.$messages
                .receive(on: DispatchQueue.main)
                .sink { [weak self] msgs in
                    let lastAssistant = msgs.last(where: { $0.role == .assistant })
                    self?.subscribeToLastMessage(lastAssistant)
                }
                .store(in: &subscriptions)

            // 4b. Re-subscribe when isProcessing transitions to true (retry/resume).
            // Retry reuses the existing assistant message without mutating the messages
            // array, so $messages won't fire. The block-count subscription was torn down
            // when processing stopped; re-establish it here.
            vm.$isProcessing
                .removeDuplicates()
                .filter { $0 }   // only on false → true transition
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    guard let self else { return }
                    self.lastMessageId = nil  // force re-subscribe
                    let lastAssistant = self.vm?.messages.last(where: { $0.role == .assistant })
                    self.subscribeToLastMessage(lastAssistant)
                }
                .store(in: &subscriptions)

            // 5. State changes → bridge updates
            Publishers.CombineLatest4(
                vm.$isProcessing, vm.$autoRetryAttempt,
                vm.$autoRetryCountdown, vm.$canResume
            )
            .removeDuplicates { a, b in a.0 == b.0 && a.1 == b.1 && a.2 == b.2 && a.3 == b.3 }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.updateLastCellBridge() }
            .store(in: &subscriptions)


            // 6. Compact finished → reconfigure all cells to update opacity/divider
            vm.$isCompacting
                .removeDuplicates()
                .dropFirst()   // skip initial value — only react to actual true→false transitions
                .receive(on: DispatchQueue.main)
                .sink { [weak self] isCompacting in
                    guard let self, !isCompacting else { return }
                    // Compact just ended — apply snapshot for new divider/removed messages,
                    // then reconfigure visible cells so opacity updates. Do NOT clear height
                    // cache — compacted messages retain their measured heights to prevent
                    // scroll jitter when scrolling back into the compacted zone.
                    if let msgs = self.vm?.messages { self.applySnapshot(messages: msgs, caller: "bind6-compact") }
                    self.reconfigureVisibleCells()
                }
                .store(in: &subscriptions)

            // 7. Font size changes
            NotificationCenter.default.publisher(for: .fontSettingsMessageBaseChanged)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    guard let self else { return }
                    self.vm?.invalidateAttributedStringCaches()
                    self.reconfigureAllCells()
                }
                .store(in: &subscriptions)

            // 8a. Background entry — snapshot content length so we can detect
            //     whether significant content accumulated while backgrounded.
            NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    guard let self else { return }
                    // [T-ios-bg-resume-collapse] Must be the SAME metric the
                    // foreground handler compares against (`liveContentLength`),
                    // or the delta subtracts two different quantities and the
                    // gate fires on noise / misses real growth.
                    let len = self.liveContentLength()
                    self.contentLengthAtBackground = len
                    AppLogger(category: "FGLayout").info("[FGLayout] → background, snapshotLen=\(len)")
                }
                .store(in: &subscriptions)

            // 8b. Foreground return — re-measure the visible cells whose content
            //     grew while the app was backgrounded.
            //
            // [T-ios-bg-resume-collapse] Field report 2026-08-10: "it was
            // running, I switched to another app, came back, and got this" —
            // a screenshot with the collapsed "深度思考" chip printed on top of
            // the prose. Three defects in the original handler, all of which
            // let a stale mid-stream height survive the round-trip:
            //
            //  1. It cleared only the LAYOUT's height cache and relied on
            //     `reconfigureVisibleCells` for the rest. But the CELL's own
            //     `lastComputedHeight` is checked FIRST in PLAF and is only
            //     nil'd when the provider actually re-runs — see
            //     `remeasureVisibleCells`. This is the same gap 232e4194 /
            //     9dedf0db closed for `blockContentFilledSignal`; it was never
            //     applied here.
            //  2. The `accumulated >= 200` gate. Overlap needs about ONE line
            //     of growth (~20 chars at this width) — and a tool block
            //     finishing or a thinking block collapsing changes the
            //     rendered height while adding little or no content at all.
            //     A 200-char threshold skips exactly the small deltas that
            //     still shift layout. Any change now re-measures; unchanged
            //     content still short-circuits, so a plain
            //     background/foreground with no streaming stays free.
            //  3. `lastAssistantContentLength()` measured only `messages.last`,
            //     so growth on an EARLIER still-live message (6cf9f58c's
            //     running-tool case, i.e. any multi-turn agent loop) was
            //     invisible to the delta. Now `liveContentLength()`.
            //
            // Deliberately on `willEnterForeground` rather than
            // `didBecomeActive`: this must land before the first post-resume
            // layout pass, and it touches no VM state that the
            // didBecomeActive replay owns.
            NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    guard let self else { return }
                    let currentLen = self.liveContentLength()
                    let bgLen = self.contentLengthAtBackground ?? currentLen
                    self.contentLengthAtBackground = nil
                    let accumulated = currentLen - bgLen
                    AppLogger(category: "FGLayout").info("[FGLayout] → foreground, bgLen=\(bgLen) currentLen=\(currentLen) accumulated=\(accumulated)")
                    // Any change at all — including a SHRINK, which a collapsed
                    // thinking block or a replaced tool placeholder produces.
                    guard accumulated != 0 else {
                        AppLogger(category: "FGLayout").info("[FGLayout] skip layout (content unchanged)")
                        return
                    }
                    self.remeasureVisibleCells(reason: "foreground(delta=\(accumulated))")
                }
                .store(in: &subscriptions)

            // 9. Text block filled — reconfigure the cell so UIHostingConfiguration
            //    re-measures height after transitioning from empty to non-empty.
            vm.blockContentFilledSignal
                .receive(on: DispatchQueue.main)
                .sink { [weak self] (messageId, blockId) in
                    guard let self,
                          let ds = self.dataSource else { return }
                    let item = MessageListItem.assistantBlock(messageId, blockId)
                    var snapshot = ds.snapshot()
                    let items = snapshot.itemIdentifiers
                    guard let idx = items.firstIndex(of: item) else { return }

                    // Invalidate the height cache BEFORE reconfiguring so the
                    // layout doesn't reuse the stale empty-content height.
                    // Without this, the cell briefly keeps its ~0pt height from
                    // the empty block, causing overlap with the tool block above.
                    self.viewController?.messageListLayout?.invalidateHeight(at: idx)

                    // [T-ios-empty-block-fill-cell-cache] Clear on BOTH sides of
                    // the apply — same shape as the `.thinkingBlockToggled`
                    // handler below (see the `DispatchQueue.main.async` re-clear
                    // there). Before, so no layout pass between the
                    // `invalidateHeight` above and the provider re-run can answer
                    // from the stale cache; after, for the cases where the
                    // provider never ran (below).
                    if let cv = self.viewController?.collectionView {
                        let ip = IndexPath(item: idx, section: 0)
                        (cv.cellForItem(at: ip) as? SelfSizingCell)?.clearCachedHeight()
                    }

                    snapshot.reconfigureItems([item])
                    ds.apply(snapshot, animatingDifferences: false)

                    // Why the cell-side clear is needed at all: `SelfSizingCell`
                    // keeps its OWN `lastComputedHeight`, and since b4268586
                    // dropped that cache's 50ms bound it is reused forever while
                    // the width matches — its dedup short-circuit is the FIRST
                    // thing `preferredLayoutAttributesFitting` checks, ahead of
                    // the interaction (e9d167c2) and streaming (81e43b58) guards.
                    // A cell still holding the height it measured while this
                    // block was empty therefore keeps answering with it, and
                    // `contentView.clipsToBounds` crops the arriving text while
                    // the following blocks stack from the too-short frame — the
                    // reported tool-capsule / prose overlap.
                    //
                    // Scope honestly: the reconfigure usually fixes this by
                    // itself, since the cell provider calls
                    // `applyContentConfiguration`, which nils that cache. This
                    // second clear covers the case where the provider never ran —
                    // `configureCell`'s `.assistantBlock` branch returns early on
                    // a messageIndex / blockId miss (the SnapshotDiag MISS logs),
                    // leaving the old configuration and its height in place.
                    //
                    // Cost is a `cellForItem` lookup plus three nil writes, at
                    // most once per text block per turn: both senders gate on a
                    // `wasEmpty` edge and `block.content` is a monotonic
                    // full-text replacement, so this is one-shot per block, never
                    // per token. It cannot loop — a re-measure writes only
                    // cell-private fields and two unobserved layout dictionaries,
                    // and nothing on that path can re-emit this signal.
                    if let cv = self.viewController?.collectionView {
                        let ip = IndexPath(item: idx, section: 0)
                        (cv.cellForItem(at: ip) as? SelfSizingCell)?.clearCachedHeight()
                    }

                }
                .store(in: &subscriptions)

            // Re-snapshot when toggleUsage inserts/removes a footer cell.
            //
            // [T-ios-plaf-cache-footer-staleness] `applySnapshot` alone is only
            // enough when the toggle INSERTS or REMOVES the footer — diffable
            // re-runs the cell provider (and so `applyContentConfiguration`,
            // which nils the height cache) for inserted/moved items only. When
            // the footer cell ALREADY exists — the common case, since
            // `needsFooter` is true for every last assistant message — the item
            // identifier is unchanged, the diff is empty, and UIKit never
            // touches the cell: no prepareForReuse, no applyContentConfiguration,
            // no clearCachedHeight. The SwiftUI body still re-renders (showUsage
            // is @Published) so the content grows, but
            // `preferredLayoutAttributesFitting` keeps returning the cached
            // height and `contentView.clipsToBounds` crops the usage row.
            //
            // Before b4268586 the 50ms bound on that cache healed this within a
            // frame or two; with the bound gone it is permanent. So invalidate
            // the footer cells explicitly here, mirroring the
            // `.thinkingBlockToggled` handler below.
            NotificationCenter.default.publisher(for: .messageListNeedsResnapshot)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    guard let self, let vm = self.vm else { return }
                    self.invalidateFooterHeightCaches()
                    self.applySnapshot(messages: vm.messages)
                }
                .store(in: &subscriptions)
        }

        // MARK: - Footer height invalidation

        /// [T-ios-plaf-cache-footer-staleness] Drop the cached height of every
        /// VISIBLE assistant-footer cell, both cell-side and layout-side.
        ///
        /// Needed because the footer's height is driven by `@Published` state on
        /// `CellStateBridgeV2` (`showUsage`, `isActiveMessage`, `canResume`,
        /// the auto-retry fields) rather than by the message content that forms
        /// the diffable item identity. Those flips re-render the SwiftUI body
        /// in place without changing the snapshot, so nothing on the normal
        /// invalidation path (applyContentConfiguration / prepareForReuse) ever
        /// runs and `SelfSizingCell`'s now-unbounded height cache goes stale.
        ///
        /// Deliberately scoped to `visibleCells`: an off-screen footer will be
        /// configured from scratch when it scrolls back in (cell provider →
        /// applyContentConfiguration), so it needs nothing here.
        ///
        /// `messageId == nil` invalidates every visible footer; passing an id
        /// narrows it to that message's footer.
        private func invalidateFooterHeightCaches(messageId: UUID? = nil) {
            guard let cv = viewController?.collectionView,
                  let layout = viewController?.messageListLayout,
                  let ds = dataSource else { return }
            let items = ds.snapshot().itemIdentifiers
            for (i, item) in items.enumerated() {
                guard case .assistantFooter(let mid) = item else { continue }
                if let messageId, mid != messageId { continue }
                let ip = IndexPath(item: i, section: 0)
                let cellForFooter = cv.cellForItem(at: ip) as? SelfSizingCell

                // [T-ios-footer-extra-gap] DEFENSIVE, not a root-cause fix.
                //
                // `invalidateHeight` needs no cell, so it should not sit behind
                // the cell lookup — the old `guard let cell … else { continue }`
                // skipped the layout-side invalidate whenever the footer was not
                // currently realised. Field logs (2026-08-10 09:30:06, idx=179)
                // confirm that branch does fire, though in that instance nothing
                // was cached and the skip was harmless.
                //
                // Kept separate deliberately: the cell clear still requires a
                // cell, the layout invalidate no longer does. This closes the
                // asymmetry without claiming it explains the reported gap.
                cellForFooter?.clearCachedHeight()
                layout.invalidateHeight(at: i)
            }
        }

        // MARK: - Content Length Helper

        /// Total character count across all blocks of the last assistant message.
        private func lastAssistantContentLength() -> Int {
            guard let msg = vm?.messages.last(where: { $0.role == .assistant }) else { return 0 }
            return msg.blocks.reduce(0) { $0 + $1.content.count }
        }

        /// [T-ios-bg-resume-collapse] Total content length across EVERY message
        /// that can still be growing, not just `messages.last`.
        ///
        /// The background/foreground gate used to measure only the last
        /// assistant message. But `liveAssistantMessageIds` (6cf9f58c)
        /// established that an EARLIER assistant turn keeps receiving output
        /// through a running/streaming tool block while a newer turn streams —
        /// growth on such a message contributed 0 to the delta, so the gate
        /// concluded "nothing changed" and skipped the re-measure for exactly
        /// the multi-turn agent loop this bug is reported against.
        private func liveContentLength() -> Int {
            guard let vm else { return 0 }
            let msgs = vm.messages
            let liveIds = Self.liveAssistantMessageIds(messages: msgs,
                                                       isProcessing: vm.isProcessing)
            // When the loop is not processing, `liveIds` is empty by design;
            // fall back to the last assistant message so a turn that ENDED
            // while backgrounded (the SIGKILL / detached-loop case) is still
            // compared against something meaningful.
            let considered = liveIds.isEmpty
                ? msgs.last(where: { $0.role == .assistant }).map { [$0] } ?? []
                : msgs.filter { liveIds.contains($0.id) }
            return considered.reduce(0) { acc, m in
                acc + m.blocks.reduce(0) { $0 + $1.content.count }
            }
        }

        /// [T-ios-bg-resume-collapse] Re-measure every visible cell from
        /// scratch: drop BOTH height layers, then reconfigure.
        ///
        /// Clearing the layout's index cache alone is not enough, and this is
        /// the defect the field report ("run a loop, switch away, come back →
        /// blocks printed on top of each other") lands on. `SelfSizingCell`
        /// keeps its OWN `lastComputedHeight`, and since b4268586 dropped that
        /// cache's 50ms bound it is reused forever while the width matches —
        /// its dedup short-circuit is the FIRST thing
        /// `preferredLayoutAttributesFitting` checks, ahead of the layout's
        /// caches and of the interaction/streaming guards. A cell that
        /// measured mid-stream before the app was backgrounded therefore keeps
        /// answering with that too-short height after the content grew, so
        /// `prepare()` stacks every following cell from it (the overlap) and
        /// `contentView.clipsToBounds` crops the cell's own text (the
        /// "cut off at the bottom").
        ///
        /// `reconfigureItems` does NOT reliably fix this by itself: it only
        /// nils the cell cache when the provider actually re-runs, and
        /// `configureCell`'s `.assistantBlock` branch returns early on a
        /// messageIndex / blockId miss — the same escape hatch documented on
        /// the `blockContentFilledSignal` handler. Clearing explicitly on both
        /// sides of the apply covers the provider-never-ran case.
        ///
        /// Scope: VISIBLE cells only (FB13213926 discipline — every entry into
        /// the hosting graph is a crash surface, so no whole-list sweep), and
        /// only on an actual foreground/resume edge, i.e. at most once per
        /// background round-trip. Off-screen cells are re-measured when they
        /// scroll in, because their layout entry was invalidated too.
        private func remeasureVisibleCells(reason: String) {
            guard let cv = viewController?.collectionView,
                  let layout = viewController?.messageListLayout else { return }
            let visible = cv.indexPathsForVisibleItems
            for ip in visible {
                layout.invalidateHeight(at: ip.item)
                (cv.cellForItem(at: ip) as? SelfSizingCell)?.clearCachedHeight()
            }
            layout.invalidateLayout()
            reconfigureVisibleCells()
            // Second clear, after the reconfigure has propagated — the mirror
            // of the `blockContentFilledSignal` handler's both-sides clear.
            for ip in cv.indexPathsForVisibleItems {
                (cv.cellForItem(at: ip) as? SelfSizingCell)?.clearCachedHeight()
            }
            AppLogger(category: "FGLayout").info(
                "[FGLayout] remeasure reason=\(reason) visibleCells=\(visible.count)")
        }

        // MARK: - Streaming Subscription

        private func subscribeToLastMessage(_ message: ChatMessage?) {
            guard let message, message.id != lastMessageId else { return }
            lastMessageId = message.id
            lastMessageSub?.cancel()
            lastMessageBlockCountSub?.cancel()

            guard vm?.isProcessing == true else { return }

            // Watch objectWillChange for content updates (text streaming)
            lastMessageSub = message.objectWillChange
                .receive(on: DispatchQueue.main)
                .sink { [weak self, weak message] _ in
                    guard let self else { return }
                    guard self.vm?.isProcessing == true else {
                        // [DIAG-SUB-END] Streaming ended — this is the last
                        // objectWillChange notification. Cancel subs and do final flush.
                        // If flushStreamingLayout() is throttled here, the pending
                        // work item fires later but no more objectWillChange comes,
                        // so it's the very last chance to invalidate layout.
                        AppLogger(category: "StreamDiag").info("[StreamSub] END — isProcessing=false, cancelling subs, final flush. pendingLayout=\(self.pendingStreamingLayout != nil) tracking=\(self.viewController?.collectionView?.isTracking ?? false) decel=\(self.viewController?.collectionView?.isDecelerating ?? false)")
                        self.lastMessageSub?.cancel()
                        self.lastMessageSub = nil
                        self.lastMessageBlockCountSub?.cancel()
                        self.lastMessageBlockCountSub = nil
                        self.flushStreamingLayout()
                        // [T-ios-tooluibatch-stuck-pending-snapshot] Terminal
                        // rescue for a snapshot deferred during a streaming-UI
                        // suspend window. While `streamingUIUpdatesSuspended` was
                        // true, the blocks.count sub (bind4b) only set
                        // `hasPendingSnapshot = true` without rebuilding the
                        // diffable snapshot, so blocks added during the suspend
                        // (e.g. tools 63–80 of a long agent loop) had no cells.
                        // flushStreamingLayout() above tries to flush it, but if
                        // it is still throttled (its trailing work item) OR the
                        // suspend overlapped this terminal tick, the pending
                        // snapshot could be stranded forever once these subs are
                        // cancelled (no further objectWillChange will arrive).
                        // Force one direct flush here — this fires exactly once
                        // at stream end, so there is no per-tick / large-data
                        // cost, and applySnapshot rebuilds from the authoritative
                        // current `messages`, so no row mix-up. No-op unless a
                        // snapshot is actually pending. Gated on NOT-suspended:
                        // applying a snapshot during an active session-transition
                        // suspend is exactly what the suspend guards against, so
                        // if we're still suspended we leave `hasPendingSnapshot`
                        // set for the resume path / next $messages apply to clear.
                        AppLogger(category: "PendingSnap").info("[PendingSnap] STREAM-END terminal rescue check — hasPendingSnapshot=\(self.hasPendingSnapshot) suspended=\(self.vm?.streamingUIUpdatesSuspended == true)")
                        if self.hasPendingSnapshot, self.vm?.streamingUIUpdatesSuspended != true {
                            self.flushPendingSnapshotIfNeeded(caller: "stream-end")
                        } else if self.hasPendingSnapshot {
                            // Still suspended at stream-end: cannot safely apply here.
                            // Left for the resume path; flag it so the freeze is traceable.
                            // [T-ios-scroll-suspend-leak] This is the key symptom line:
                            // content is written to DB but the UI is waiting on a
                            // suspend that must be released (scroll settle / foreground
                            // return / the new openSession clear) before it flushes.
                            AppLogger(category: "PendingSnap").warning("[PendingSnap] ⚠️ STREAM-END left pending (still suspended) — content in DB, UI frozen until suspend-resume (or next openSession clears it)")
                        }
                        return
                    }
                    self.flushStreamingLayout()
                }

            // Watch blocks.count → rebuild snapshot when new blocks appear
            lastMessageBlockCountSub = message.$blocks
                .map(\.count)
                .removeDuplicates()
                .receive(on: DispatchQueue.main)
                .dropFirst()
                .sink { [weak self] count in
                    guard let self, let msgs = self.vm?.messages else { return }
                    if self.vm?.streamingUIUpdatesSuspended == true {
                        // [PendingSnap] Deferring snapshot rebuild while suspended;
                        // blocks added now (count) won't get cells until a flush.
                        AppLogger(category: "PendingSnap").info("[PendingSnap] DEFER blockCount=\(count) — suspended, set hasPendingSnapshot=true (was \(self.hasPendingSnapshot))")
                        self.hasPendingSnapshot = true
                        return
                    }
                    self.applySnapshot(messages: msgs, caller: "bind4b-blockCount")
                }
        }

        /// Flush any snapshot that was deferred while streaming UI updates were suspended.
        // fileprivate so updateUIViewController (struct, same file) can use it as
        // a backstop flush. [T-message-list-empty-on-foreground]
        fileprivate func flushPendingSnapshotIfNeeded(caller: String = "?") {
            guard hasPendingSnapshot else { return }
            hasPendingSnapshot = false
            let blocks = vm?.messages.last(where: { $0.role == .assistant })?.blocks.count ?? -1
            // [T-ios-scroll-suspend-leak] Include blockCount + isProcessing so a
            // FLUSH can be matched against the DEFER that queued it — a DEFER with
            // no corresponding FLUSH (see the [SessionLoad] stale-suspend clear) is
            // the leak signature.
            AppLogger(category: "PendingSnap").info("[PendingSnap] FLUSH caller=\(caller) blockCount=\(blocks) isProcessing=\(vm?.isProcessing == true) — applying deferred snapshot")
            if let msgs = vm?.messages { applySnapshot(messages: msgs, caller: "flushPending(\(caller))") }
        }

        private func flushStreamingLayout() {
            // Check if a deferred snapshot can now be applied (sheet dismissed).
            flushPendingSnapshotIfNeeded(caller: "flushStreamingLayout")

            guard let cv = viewController?.collectionView,
                  let layout = viewController?.messageListLayout else { return }

            // Adaptive throttle: 100ms when auto-scrolling (user watching),
            // 3s when user has scrolled away (reading earlier content).
            let interval: TimeInterval = scrollMode == .autoScrolling ? 0.1 : 3.0
            let now = Date()
            if now.timeIntervalSince(lastStreamingLayoutTime) < interval {
                // Schedule a trailing fire so the final update isn't lost.
                if pendingStreamingLayout == nil {
                    let work = DispatchWorkItem { [weak self] in
                        self?.pendingStreamingLayout = nil
                        self?.doFlushStreamingLayout()
                    }
                    pendingStreamingLayout = work
                    DispatchQueue.main.asyncAfter(deadline: .now() + interval, execute: work)
                }
                return
            }
            pendingStreamingLayout?.cancel()
            pendingStreamingLayout = nil
            doFlushStreamingLayout()
        }

        /// Actually perform the streaming layout flush + scroll.
        private func doFlushStreamingLayout() {
            lastStreamingLayoutTime = Date()
            guard let layout = viewController?.messageListLayout else { return }
            // Always invalidate layout — even during scrolling. The layout's
            // invalidationContext already skips contentOffsetAdjustment when
            // isTracking/isDecelerating, so this won't cause scroll jitter.
            // Skipping invalidation entirely caused text blocks that transitioned
            // from empty to non-empty (via streaming bypass) to stay at ~0 height.
            layout.invalidateLayout()

            if scrollMode == .autoScrolling {
                scheduleCoalescedScroll()
            }
        }

        /// Coalesce rapid scroll signals into a single scroll action.
        /// Uses a short 80ms delay — just enough to batch multiple near-simultaneous
        /// signals without noticeable visual lag during streaming.
        private func scheduleCoalescedScroll() {
            pendingScrollWork?.cancel()
            let work = DispatchWorkItem { [weak self] in
                guard let self else { return }
                let isTracking = self.viewController?.collectionView?.isTracking ?? false
                if self.scrollMode != .autoScrolling || isTracking { return }
                self.scrollToBottomNow()
            }
            pendingScrollWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08, execute: work)
        }

        // MARK: - Snapshot

        /// When a snapshot is deferred (e.g. streaming UI updates suspended),
        /// set to true so we know to re-apply later.
        private var hasPendingSnapshot: Bool = false

        private func applySnapshot(messages rawMessages: [ChatMessage], caller: String = "?") {
            guard let vm, !vm.isLoadingSession else { return }

            // [T-bridge-message-ui-leak] Single UI-collection sink for EVERY
            // path that pushes messages to the list (loadSession, live inject,
            // compact rebuild, snapshot reload, rebind…). Filter the internal
            // role-alternation bridge (#579) here so it can never surface as a
            // chat bubble regardless of which path produced it — replacing the
            // former reliance on a single filter inside loadSession, which other
            // rebuild paths bypassed (the leak seen 2026-07-24).
            let messages = rawMessages.contains(where: { $0.isInternalBridge })
                ? rawMessages.filter { !$0.isInternalBridge }
                : rawMessages

            // Content changed (new/removed messages) — the up-button's turn-walk
            // anchor may no longer line up, so restart it on the next tap.
            lastJumpedUserId = nil

            // Subscribe to thinking block toggle notifications (once)
            if attachmentSizeChangedSub == nil {
                attachmentSizeChangedSub = NotificationCenter.default.publisher(for: .minisAttachmentSizeChanged)
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] notification in
                        // [T-ios-image-squish-probe §2] Coalesce a same-tick
                        // burst into ONE refresh pass. A six-image message now
                        // fires up to 12 of these (header probe + decode
                        // completion per image), and the handler below is a
                        // full-list invalidate + reconfigureItems — running it
                        // per notification is 12 back-to-back relayout storms.
                        // Notifications already arrive on main; the pending
                        // flag folds every arrival within the current runloop
                        // tick into the single async pass scheduled below.
                        guard let self else { return }
                        self.pendingAttachmentSizeUrls.append((notification.object as? String) ?? "<nil>")
                        guard !self.attachmentSizeRefreshScheduled else { return }
                        self.attachmentSizeRefreshScheduled = true
                        DispatchQueue.main.async { [weak self] in
                            guard let self else { return }
                            self.attachmentSizeRefreshScheduled = false
                            let urls = self.pendingAttachmentSizeUrls
                            self.pendingAttachmentSizeUrls.removeAll()
                            self.handleAttachmentSizeChanged(coalescedUrls: urls)
                        }
                    }
            }

            if attachMountSub == nil {
                attachMountSub = NotificationCenter.default.publisher(for: .minisUserAttachmentsMounted)
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] notification in
                        let alog = AppLogger(category: "AttachMount")
                        guard let self,
                              let msgId = notification.object as? UUID,
                              let cv = self.viewController?.collectionView,
                              let layout = self.viewController?.messageListLayout,
                              let ds = self.dataSource else { return }
                        // Targeted, O(1): only the mounted message's own cell.
                        // Mirrors the .thinkingBlockToggled handler — clear BOTH
                        // height layers (the layout's index cache, so the next
                        // seed's corrected precalc can land past the
                        // occupied-slot guard, and the cell's width-matched
                        // cache, so PLAF stops short-circuiting to the stale
                        // tile-less height), then reconfigure so the hosting
                        // view re-measures with the tiles actually mounted.
                        // Deliberately NOT a visible-cells sweep — one message
                        // mounted, one cell invalidated (FB13213926 discipline:
                        // no extra hosting-graph re-entries).
                        let snapshot = ds.snapshot()
                        let item = MessageListItem.wholeMessage(msgId)
                        guard let idx = snapshot.itemIdentifiers.firstIndex(of: item) else {
                            alog.info("[AttachMount] msg=\(msgId.uuidString.prefix(8)) not in snapshot — nothing to invalidate")
                            return
                        }
                        let ip = IndexPath(item: idx, section: 0)
                        (cv.cellForItem(at: ip) as? SelfSizingCell)?.clearCachedHeight()
                        layout.invalidateHeight(at: idx)
                        var snap = snapshot
                        snap.reconfigureItems([item])
                        ds.apply(snap, animatingDifferences: false)
                        layout.invalidateLayout()
                        alog.info("[AttachMount] msg=\(msgId.uuidString.prefix(8)) idx=\(idx) invalidated + reconfigured")
                    }
            }

            if thinkingToggleSub == nil {
                thinkingToggleSub = NotificationCenter.default.publisher(for: .thinkingBlockToggled)
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] notification in
                        let tlog = AppLogger(category: "ThinkingCollapse")
                        guard let self else {
                            tlog.warning("[ThinkingCollapse] sink fired but self gone")
                            return
                        }
                        guard let blockId = notification.object as? UUID else {
                            tlog.warning("[ThinkingCollapse] notif object not UUID: \(String(describing: notification.object))")
                            return
                        }
                        guard let cv = self.viewController?.collectionView,
                              let layout = cv.collectionViewLayout as? MessageListLayout,
                              let snapshot = self.dataSource?.snapshot() else {
                            tlog.warning("[ThinkingCollapse] block=\(blockId.uuidString.prefix(8)) infra missing: cv=\(self.viewController?.collectionView != nil) ds=\(self.dataSource != nil)")
                            return
                        }
                        // [T-thinking-collapse-blank] The layout-side
                        // `invalidateHeight` only wipes the MessageListLayout
                        // `heightCache`, not the per-cell `lastComputedHeight`
                        // on SelfSizingCell. Without also clearing the
                        // cell-side cache, the next
                        // `preferredLayoutAttributesFitting` short-circuits
                        // via the broad-width cache and returns the OLD
                        // expanded height (~300pt) for a cell whose SwiftUI
                        // body now renders just the 36pt header — producing
                        // a tall empty gap below the collapsed pill (user
                        // screenshot 2026-05-19). Clear both caches here
                        // and reconfigure so UIHostingConfiguration
                        // re-measures from scratch. Trace each step so a
                        // future report can be diagnosed from logs alone.
                        var matched = false
                        for (i, item) in snapshot.itemIdentifiers.enumerated() {
                            if case .assistantBlock(_, let bid) = item, bid == blockId {
                                matched = true
                                let beforeLayout = layout.cachedHeight(at: i)
                                let beforePrecalc = layout.precalcHeight(at: i)
                                let ip = IndexPath(item: i, section: 0)
                                let cellOpt = cv.cellForItem(at: ip) as? SelfSizingCell
                                let frameH = cellOpt?.frame.size.height ?? -1
                                if let cell = cellOpt {
                                    cell.clearCachedHeight()
                                }
                                layout.invalidateHeight(at: i)
                                var snap = snapshot
                                snap.reconfigureItems([item])
                                self.dataSource?.apply(snap, animatingDifferences: false)
                                layout.invalidateLayout()
                                tlog.info("[ThinkingCollapse] HIT block=\(blockId.uuidString.prefix(8)) idx=\(i) cellFound=\(cellOpt != nil) frameH=\(String(format: "%.1f", frameH)) beforeLayout=\(beforeLayout.map { String(format: "%.1f", $0) } ?? "nil") beforePrecalc=\(beforePrecalc.map { String(format: "%.1f", $0) } ?? "nil")")
                                // Defer one runloop tick to capture the
                                // post-reconfigure measurement and confirm
                                // the cell actually shrank/grew. If the
                                // delta is small the cache invalidation
                                // didn't take — that's the case to escalate.
                                DispatchQueue.main.async { [weak cv, weak layout] in
                                    let cv = cv
                                    let layout = layout
                                    let after = cv?.cellForItem(at: ip)?.frame.size.height ?? -1
                                    let afterLayout = layout?.cachedHeight(at: i)
                                    tlog.info("[ThinkingCollapse] POST block=\(blockId.uuidString.prefix(8)) idx=\(i) frameH=\(String(format: "%.1f", after)) layoutCache=\(afterLayout.map { String(format: "%.1f", $0) } ?? "nil") delta=\(String(format: "%.1f", after - frameH))")
                                }
                                break
                            }
                        }
                        if !matched {
                            tlog.warning("[ThinkingCollapse] MISS block=\(blockId.uuidString.prefix(8)) not in snapshot (items=\(snapshot.itemIdentifiers.count))")
                        }
                    }
            }

            // Rebuild O(1) lookup indices
            messageIndex.removeAll(keepingCapacity: true)
            for (i, msg) in messages.enumerated() {
                messageIndex[msg.id] = i
            }

            // Clean up bridges
            let currentIds = Set(messages.map(\.id))
            let removedIds = Set(cellBridges.keys).subtracting(currentIds)
            for id in removedIds { bridgeSheetSubs.removeValue(forKey: id) }
            cellBridges = cellBridges.filter { currentIds.contains($0.key) }
            lastFooterShapeByMessage = lastFooterShapeByMessage.filter { currentIds.contains($0.key) }

            // Build items
            var newItems: [MessageListItem] = []
            // [T-ios-orphan-user-tail GH#262/#263] The Resume banner lives in
            // the assistant FOOTER cell, and a user row never gets one — so a
            // transcript ending on a user turn that never received a reply had
            // canResume=true with nowhere to draw the button. Emit a footer
            // after that trailing user row, reusing the cell that already knows
            // how to size and render the banner rather than teaching the user
            // bubble (whose contentKey and height estimate are content-only) to
            // grow one. Scoped to the LAST message and to canResume, so an
            // ordinary user turn mid-history is untouched.
            let orphanTailUserId: UUID? = {
                guard vm.canResume, !vm.isProcessing,
                      let last = messages.last, last.role == .user else { return nil }
                return last.id
            }()
            for message in messages {
                switch message.role {
                case .user, .compactDivider, .systemInfo:
                    newItems.append(.wholeMessage(message.id))
                    if message.id == orphanTailUserId {
                        newItems.append(.assistantFooter(message.id))
                    }
                case .assistant:
                    newItems.append(.assistantHeader(message.id))
                    for block in message.blocks {
                        newItems.append(.assistantBlock(message.id, block.id))
                    }
                    // Only emit a footer cell when it will actually render
                    // content. A footer with zero visible content (no typing
                    // indicator, no error, no resume, no usage) was leaving a
                    // phantom 4pt gap above the next user message. When the
                    // footer IS needed later (e.g. showUsage toggled by
                    // double-tap), the toggle triggers a snapshot refresh
                    // which re-evaluates this condition.
                    let isLastAssistant = (message.id == messages.last(where: { $0.role == .assistant })?.id)
                    let needsFooter = isLastAssistant
                        || message.error != nil
                        || message.streamInterruptCount > 0
                        || (cellBridges[message.id]?.showUsage == true)
                    if needsFooter {
                        newItems.append(.assistantFooter(message.id))
                    }
                }
            }

            // Remap height cache + set accurate estimates for ALL item types
            if let layout = viewController?.messageListLayout {
                layout.updateCacheForSnapshot(oldIds: previousSnapshotIds, newIds: newItems)

                // [T-ios-first-block-footer-gap] Collapse the footer the moment
                // a turn's FIRST block appears.
                //
                // While the block set is empty the footer legitimately renders a
                // TypingIndicator and measures ~48pt. As soon as any block
                // exists, `hasVisibleContent` flips true — its `default` arm
                // covers tool and thinking blocks — so `showTyping` goes false
                // and the footer body renders nothing, padding included. Nothing
                // told the layout: the existing invalidation (127b546c9) waits
                // for the `isProcessing` true->false edge, which is still far
                // away mid-turn. The stale ~48pt frame is the blank strip
                // reported under the first tool card, confirmed in the view
                // debugger as an `AssistantFooterCellV3` on an empty frame, and
                // in the log as `stale-height held=48.0 needs=22.0`.
                //
                // Detected here, in the data layer, rather than from a SwiftUI
                // observer: a previous attempt hung this off
                // `blockContentFilledSignal`, which only fires for TEXT blocks
                // transitioning empty -> non-empty. Tool blocks are created
                // already populated and never take that path, so the footer was
                // only fixed later, when some unrelated text block streamed —
                // by which point the gap had been visible for seconds. Counting
                // blocks covers every block kind and cannot be skipped by a
                // same-update-cycle observer (the failure mode behind the
                // thinking-collapse bug).
                for message in messages where message.role == .assistant {
                    let count = message.blocks.count
                    let previous = lastBlockCountByMessage[message.id]
                    lastBlockCountByMessage[message.id] = count
                    guard previous == 0, count >= 1,
                          let footerIdx = newItems.firstIndex(of: .assistantFooter(message.id))
                    else { continue }
                    layout.invalidateHeight(at: footerIdx)
                    if let cv = viewController?.collectionView {
                        let fip = IndexPath(item: footerIdx, section: 0)
                        (cv.cellForItem(at: fip) as? SelfSizingCell)?.clearCachedHeight()
                    }
                    AppLogger(category: "BottomGapDiag").info(
                        "[BottomGapDiag][footer-collapse] msg=\(message.id.uuidString.prefix(8)) "
                        + "blocks 0→\(count) footerIdx=\(footerIdx) — invalidated (typing indicator just unmounted)")

                    // [T-ios-first-block-footer-gap] Clearing the caches is not
                    // enough on its own — the cell must also be told to
                    // re-measure, or UIKit keeps presenting the frame it already
                    // has and nothing ever asks SwiftUI for a new size.
                    //
                    // Three earlier attempts each fixed a real but insufficient
                    // piece: the seed (an estimate, only consulted when no
                    // measured height exists), the block-count edge (this hook,
                    // which cleared caches), and `FooterHeightShape` (which never
                    // ran at all — `updateBridge` is driven by
                    // `CombineLatest4(isProcessing, autoRetryAttempt,
                    // autoRetryCountdown, canResume)`, and a block arriving moves
                    // none of those four, so the comparison was dead code for
                    // this transition; that is why `[footer-shape]` logged zero
                    // times in the last capture).
                    //
                    // Deferred to the next runloop turn: this runs inside
                    // `applySnapshot`, and calling `reconfigureItems` +
                    // `dataSource.apply` re-entrantly during an apply is exactly
                    // the hazard the surrounding code documents. By then the
                    // footer cell is realised, so `invalidateFooterHeightCaches`
                    // can also clear the cell-side `lastComputedHeight` — which
                    // it could not do a moment ago, since `cellForItem` returns
                    // nil for a cell that has not been dequeued yet.
                    // Also refresh the bridge so `FooterHeightShape` sees this
                    // transition. Without it the shape comparison added for this
                    // bug can never fire on a block arrival, since nothing else
                    // calls `updateBridge` at that moment.
                    if let bridge = cellBridges[message.id] {
                        updateBridge(bridge, message: message, in: messages)
                    }

                    let mid = message.id
                    DispatchQueue.main.async { [weak self] in
                        guard let self, let ds = self.dataSource else { return }
                        self.invalidateFooterHeightCaches(messageId: mid)
                        var snap = ds.snapshot()
                        let footerItem = MessageListItem.assistantFooter(mid)
                        guard snap.itemIdentifiers.contains(footerItem) else { return }
                        snap.reconfigureItems([footerItem])
                        ds.apply(snap, animatingDifferences: false)
                        AppLogger(category: "BottomGapDiag").info(
                            "[BottomGapDiag][footer-reconfig] msg=\(mid.uuidString.prefix(8)) "
                            + "— caches cleared + cell reconfigured, footer must re-measure now")
                    }
                }
                // Drop entries for messages no longer present so the map cannot
                // grow without bound across a long session.
                let liveIds = Set(messages.map(\.id))
                lastBlockCountByMessage = lastBlockCountByMessage.filter { liveIds.contains($0.key) }

                // [T-ios-earlier-stream-seed-freeze] New snapshot => block set
                // / tool statuses may have changed, so the live-message memo
                // must not be reused across it. This runs once per flush, not
                // per cell.
                streamGeneration &+= 1

                let cvWidth = viewController?.collectionView.bounds.width ?? 390
                for (i, item) in newItems.enumerated() {
                    // [T-ios-scroll-decel-height-drift] Register the stable
                    // content key for this index so a later self-size writes the
                    // real height back under the key, and seed precalc from any
                    // previously-measured real height. This kills the estimate→
                    // real "settle jitter" for .wholeMessage (user) cells, whose
                    // estimate is a coarse char-count heuristic (±13–52pt off),
                    // on every re-entry after the first measurement. NO new
                    // measurement happens here — we reuse the height UIKit
                    // already computed (see MessageListLayout.invalidationContext).
                    let contentKey = Self.contentKey(for: item, messages: messages, messageIndex: messageIndex)
                    layout.setContentKey(contentKey, at: i)

                    // [T-ios-footer-hug] Content-aware spacing policy — must run
                    // BEFORE the cachedHeight guard below, or footers that
                    // already measured would skip it and fall back to the
                    // default hug, re-gluing a prominent banner on the next
                    // prepare(). Hug ONLY when the footer shows the quiet
                    // meta/usage row (or nothing); error banner / resume banner
                    // / typing indicator keep the full itemSpacing (user report
                    // 2026-07-21). Mirrors BridgedAssistantFooterV3's sections.
                    var footerProminent = false
                    if case .assistantFooter(let footerMsgId) = item {
                        let footerMsg = messageIndex[footerMsgId].flatMap {
                            $0 < messages.count ? messages[$0] : nil
                        }
                        let isLastMsg = messages.last?.id == footerMsgId
                        let hasError = footerMsg?.error != nil
                        let showsResume = isLastMsg && !hasError
                            && vm.canResume && !vm.isProcessing
                        // [T-ios-first-block-footer-gap] `isActive` must mirror
                        // the VIEW's typing-indicator condition VERBATIM, which
                        // is `bridge.isActiveMessage && !hasVisibleContent`.
                        // The `hasVisibleContent` half was missing here, so this
                        // seed said "prominent" (48pt) for the whole turn while
                        // `BridgedAssistantFooterV3` had already stopped drawing
                        // the indicator the moment the first block appeared.
                        //
                        // That mismatch is why invalidating the footer was not
                        // enough: the log shows `[footer-collapse] … invalidated`
                        // immediately followed by `[footer] seeded=48
                        // prominent=true` in the SAME pass — the seed re-applied
                        // the stale 48pt a few lines later, so the frame never
                        // actually shrank and `stale-height held=48 needs=22`
                        // kept firing.
                        //
                        // [T-ios-typing-indicator-scope] Same single source of
                        // truth the view uses, so the seeded height and the
                        // rendered row can never disagree.
                        let isActive = isLastMsg && vm.isProcessing
                            && (footerMsg?.shouldShowTypingIndicator ?? false)
                        footerProminent = hasError || showsResume || isActive
                        layout.setFooterHug(!footerProminent, at: i)

                    }

                    guard layout.cachedHeight(at: i) == nil else { continue }

                    // Re-entry seed: if we have a real measured height for this
                    // exact content key at this width, use it as precalc — the
                    // cell lands at its true height with zero on-screen jump.
                    if let memo = layout.measuredHeight(forKey: contentKey, width: cvWidth) {
                        layout.setPrecalcHeight(memo, at: i)
                        continue
                    }

                    switch item {
                    case .assistantHeader:
                        // Header is always a fixed "sparkles Minis" label row (measured: 28pt)
                        layout.setEstimatedHeight(28, at: i)

                    case .assistantFooter:
                        // Prominent banners (error/resume/typing) measure
                        // ~44-56pt; the quiet meta footer is ~0-4pt. Seeding
                        // closer to the real height keeps the first-display
                        // correction (and its scroll shift) small.
                        // (footerProminent computed above, pre-guard.)
                        layout.setEstimatedHeight(footerProminent ? 48 : 4, at: i)
                        #if DEBUG
                        // [BottomGapDiag] The footer is the item DIRECTLY BELOW
                        // the last block of the live assistant turn — i.e.
                        // exactly where the user reports the gap, under "the
                        // first tool card after sending".
                        //
                        // It is also the only item whose height is driven by
                        // transient state rather than content: 48pt while
                        // `isProcessing` (typing indicator / resume / error
                        // banner), ~4pt once quiet. If it is seeded at 48 and
                        // the real content turns out shorter — or the estimate
                        // is never corrected because the cell is off-screen when
                        // the state flips — the surplus reads as dead space
                        // under that first card, and stays until something else
                        // forces a re-measure. That matches "only the first one
                        // is wrong, later ones are fine": later blocks push the
                        // footer down and out of the suspect position.
                        //
                        // Log the seed, the state that chose it, and whatever
                        // real height the layout already holds, so the estimate
                        // can be compared against the truth.
                        AppLogger(category: "BottomGapDiag").info(
                            "[BottomGapDiag][footer] idx=\(i) seeded=\(footerProminent ? 48 : 4) "
                            + "prominent=\(footerProminent) isProcessing=\(vm.isProcessing) "
                            + "cached=\(layout.cachedHeight(at: i).map { String(format: "%.1f", $0) } ?? "-") "
                            + "precalc=\(layout.precalcHeight(at: i).map { String(format: "%.1f", $0) } ?? "-")")
                        #endif

                    case .assistantBlock(let msgId, let blockId):
                        guard let msgIdx = messageIndex[msgId], msgIdx < messages.count else { continue }
                        let msg = messages[msgIdx]
                        guard let block = msg.blocks.first(where: { $0.id == blockId }) else { continue }

                        switch block.kind {
                        case .text:
                            // Use TextKit layout for accurate height pre-calculation.
                            // Results are cached by NSAttributedString identity to avoid
                            // redundant TextKit layout on repeated snapshots.
                            if let attrStr = block.cachedAttributedString {
                                let textWidth = max(cvWidth - 32, 100)
                                let key = ObjectIdentifier(attrStr)
                                let height: CGFloat
                                if let cached = attrStringHeightCache[key] {
                                    height = cached
                                } else {
                                    height = Self.measureAttributedStringHeight(attrStr, width: textWidth)
                                    attrStringHeightCache[key] = height
                                }
                                // height includes the text view's 8pt insets;
                                // +4 = the SwiftUI wrapper's .padding(.vertical, 2)
                                // that the historical "+8" never accounted for
                                // (the constant +5 first-measure growth in the
                                // debug.scrollMetrics traces = this 4pt + ceil).
                                layout.setPrecalcHeight(height + 4, at: i)
                                #if DEBUG
                                // [T-ios-scroll-metrics-ring] Record content
                                // features at the seed site (the only place the
                                // block object and its precalc height coexist) so
                                // the pulled trace can attribute ct-precalc
                                // mismatches to specific markdown constructs.
                                if ScrollMetricsRecorder.shared.isEnabled {
                                    let c = block.content
                                    let feats = "code=\(c.contains("```") ? 1 : 0)|tbl=\(c.contains("|") ? 1 : 0)|list=\(c.range(of: #"(?m)^\s*[-*+] "#, options: .regularExpression) != nil ? 1 : 0)|head=\(c.range(of: #"(?m)^#{1,6} "#, options: .regularExpression) != nil ? 1 : 0)|icode=\(c.contains("`") ? 1 : 0)|bold=\(c.contains("**") ? 1 : 0)|nl=\(c.filter { $0 == "\n" }.count)|n=\(c.count)"
                                    ScrollMetricsRecorder.shared.record(
                                        kind: "seed", idx: i, a: height + 4,
                                        key: contentKey, src: "tv", note: feats,
                                        tag: Self.stableCellTag(for: item, messages: messages, messageIndex: messageIndex))
                                }
                                #endif
                            } else if block.content.isEmpty {
                                // [T-ios-empty-textblock-gap] A content-less text
                                // block renders EmptyView (AssistantBlockView gates
                                // on !content.isEmpty), so it must occupy 0pt — the
                                // heuristic's one-line floor made it a phantom
                                // blank row above the next tool capsule while
                                // streaming defers the corrective measure. When
                                // content arrives, blockContentFilledSignal
                                // invalidates the height and reconfigures the cell.
                                layout.setEstimatedHeight(0, at: i)
                            } else {
                                // Streaming text — no cachedAttributedString yet,
                                // so use the char-count heuristic.
                                //
                                // [T-ios-stream-bubble-spacing] The measured path
                                // above adds +8 (the bubble's .padding(.vertical, 2)
                                // plus the TextKit↔SwiftUI line-height delta). The
                                // heuristic's estimateTextBlockHeight only seeds
                                // `total = 4`, so a streaming text cell came out
                                // ~4pt short on its vertical padding — the bubble
                                // looked cramped/edge-to-edge until a later layout
                                // pass ran the real systemLayoutSizeFitting measure
                                // (which includes the padding) and "snapped" the
                                // spacing back. Add the same +4 here so the first
                                // streamed frame already reserves that padding.
                                // The +4 padding compensation now lives inside
                                // estimateTextBlockHeight, so all estimate paths
                                // agree — no per-call-site fudge here.
                                let est = Self.estimateItemHeight(item, messages: messages, width: cvWidth)
                                layout.setEstimatedHeight(est, at: i)
                            }
                        case .thinking:
                            // Estimator returns 36pt collapsed (matches header-only rendering),
                            // or header + capped content height when expanded. Hard-coded 88
                            // caused a -48pt correction per cell on first render, producing a
                            // contentSize-shrink jitter chain when scrolling through history.
                            let est = Self.estimateItemHeight(item, messages: messages, width: cvWidth)
                            layout.setEstimatedHeight(est, at: i)
                        case .readImageTool:
                            // In V3 block cells, readImageTool renders as a
                            // ToolCapsuleView (36pt), same as other tool types.
                            layout.setEstimatedHeight(36, at: i)
                        case .info:
                            let infoLines = max(1, block.content.components(separatedBy: "\n").count)
                            layout.setEstimatedHeight(CGFloat(20 + infoLines * 16), at: i)
                        default:
                            // Tool capsules (execute, browser, etc.) are 36pt
                            layout.setEstimatedHeight(36, at: i)
                        }

                    case .wholeMessage(let msgId):
                        // [T-ios-user-msg-estimate-tail-jitter] Accurate TextKit
                        // precalc for USER message bubbles. The "stops then
                        // twitches" decel-tail jank traced to user (wholeMessage)
                        // cells whose coarse
                        // char-count estimate was ~10pt short (idx150: est 77 →
                        // real 87): when such a cell scrolled into view during the
                        // decel tail it re-measured taller, grew contentSize, and
                        // misaligned UIKit's already-computed decel target → 30fps
                        // micro-correction near the stop + a POST-SETTLE offset
                        // jump. The user bubble is a plain SwiftUI Text (no
                        // cachedAttributedString), so it never got the accurate
                        // precalc the .text blocks do. Measure it with the SAME
                        // font + wrap width + paddings as the real render so the
                        // cell lands at its true height and contentSize stays
                        // stable through the glide. Falls back to the coarse
                        // estimator for compactDivider / systemInfo / empty.
                        if let msg = messages.first(where: { $0.id == msgId }),
                           msg.role == .user {
                            // Real wrap is bounded by maxContentWidth (the
                            // .frame(maxWidth:) on BridgedWholeMessageV3); using
                            // the wider cvWidth would under-wrap → under-estimate
                            // on iPad. Use the effective width.
                            let effWidth = maxContentWidth > 0 ? min(cvWidth, maxContentWidth) : cvWidth
                            let h = Self.measureUserBubbleHeight(for: msg, cvWidth: effWidth,
                                                                 cache: &userBubbleHeightCache)
                            if let h {
                                layout.setPrecalcHeight(h, at: i)
                                #if DEBUG
                                // [T-ios-scroll-metrics-ring] Feature capture for
                                // the +7 user-bubble subset attribution.
                                if ScrollMetricsRecorder.shared.isEnabled {
                                    let c = msg.content
                                    let cjk = c.unicodeScalars.contains { $0.value >= 0x4E00 && $0.value <= 0x9FFF }
                                    let feats = "cjk=\(cjk ? 1 : 0)|nl=\(c.filter { $0 == "\n" }.count)|n=\(c.count)|att=\(msg.attachments.count)|inAtt=\(msg.inputAttachments.count)|tag=\(c.contains("<user-attached-files>") ? 1 : 0)"
                                    ScrollMetricsRecorder.shared.record(
                                        kind: "seed", idx: i, a: h,
                                        key: Self.contentKey(for: item, messages: messages, messageIndex: messageIndex),
                                        src: "bubble", note: feats,
                                        tag: Self.stableCellTag(for: item, messages: messages, messageIndex: messageIndex))
                                }
                                #endif
                            } else {
                                // empty text (attachment-only) — coarse estimate
                                layout.setEstimatedHeight(
                                    Self.estimateItemHeight(item, messages: messages, width: cvWidth), at: i)
                            }
                        } else {
                            // compactDivider / systemInfo — coarse estimate is fine
                            let est = Self.estimateItemHeight(item, messages: messages, width: cvWidth)
                            layout.setEstimatedHeight(est, at: i)
                        }
                    }
                }

            }

            // Rebuild item→index lookup
            itemToIndex.removeAll(keepingCapacity: true)
            for (i, item) in newItems.enumerated() {
                itemToIndex[item] = i
            }

            // Track streaming cell ranges so layout doesn't adjust offset for
            // them. [T-stream-hover-earlier-streaming] Not just the LAST
            // assistant message: with queued-turn interrupts an EARLIER
            // assistant message can still be receiving content (running /
            // streaming tool blocks) while the newest turn streams text. Every
            // such message's item span must be excluded from the visible-cell
            // offset adjustment, or browse-mode hovering over it gets dragged
            // down by each flush's growth delta.
            if vm.isProcessing {
                // [T-ios-earlier-stream-seed-freeze] Shared with the seed/memo
                // exclusion in configureCell — these two had drifted (that one
                // still tested only `messages.last`), which is what let a
                // growing earlier message be frozen at a stale height. One
                // rule, one implementation.
                let streamingIds = Array(Self.liveAssistantMessageIds(messages: messages,
                                                                     isProcessing: true))
                var ranges: [Range<Int>] = []
                for id in streamingIds {
                    guard let start = newItems.firstIndex(of: .assistantHeader(id)) else { continue }
                    var end = start + 1
                    while end < newItems.count, Self.item(newItems[end], belongsTo: id) {
                        end += 1
                    }
                    ranges.append(start..<end)
                }
                ranges.sort { $0.lowerBound < $1.lowerBound }
                viewController?.messageListLayout?.streamingCellRanges = ranges
            } else {
                viewController?.messageListLayout?.streamingCellRanges = []
            }
            // Keep the layout's pin flag in sync here too — the scrollMode
            // didSet may have fired before the layout existed (initial value).
            // [T-ios-stream-natural-transitions]
            viewController?.messageListLayout?.isAutoScrollPinning = (scrollMode == .autoScrolling)
            let oldCount = previousSnapshotIds.count
            // Lock the messages array the snapshot (and messageIndex, rebuilt
            // above) was built from. configureCell / prefetch MUST read this
            // same array: reading vm.messages there instead can race a
            // concurrent loadSession() that replaced the array with freshly
            // created ChatMessage/AssistantBlock objects (new UUIDs) — the
            // snapshot's block IDs then match nothing and the cell configures
            // EMPTY (silent `guard ... else return`). Updated BEFORE the
            // skip-return below so it stays in lockstep with messageIndex.
            // [T-ios-blocks-lost-render]
            snapshotMessages = messages
            // Skip diffable-data-source apply when items haven't changed —
            // avoids expensive UIKit cell creation/layout on redundant snapshots.
            if newItems == previousSnapshotIds {
                AppLogger(category: "SnapshotDiag").info("[SnapshotDiag] SKIP caller=\(caller) items unchanged (count=\(newItems.count)) — no re-apply")
                // [T-ios-bg-resume-collapse] …but a SKIP is exactly wrong for a
                // flush that was DEFERRED across a suspend window. While
                // suspended, block content grows IN PLACE: the text of an
                // existing block gets longer, a tool capsule's placeholder is
                // replaced by its output. None of that changes the item
                // identifiers, so `newItems == previousSnapshotIds` holds and
                // this early return fires — no diff, no `reconfigureItems`, no
                // `applyContentConfiguration`, and therefore nothing that would
                // drop the visible cells' `lastComputedHeight`. Those cells go
                // on answering PLAF with the height they measured BEFORE the
                // app was backgrounded, while their SwiftUI content is now
                // taller: `prepare()` stacks the following cells from the stale
                // frame (the reported overlap) and `clipsToBounds` crops the
                // cell's own text.
                //
                // [T-ios-double-loadsession-stale-cells] `bind3-sessionLoad` is
                // the SAME hazard, arriving by a different route, and the field
                // log (sid=A57908B6, 2026-08-10) shows it is the one that
                // actually fired. loadSession() ran TWICE 90s apart on one
                // session: a cold start at 16:02:49 that built cells from
                // INCOMPLETE data (lastTextLen=23, the agent loop was still
                // running server-side), then a reload at 16:04:35 after the
                // iCloud sync-hold released, with the complete data
                // (lastTextLen=226). Both times the trace is identical:
                //
                //   16:02:49.722  caller=flushPending(updateUIVC-backstop) totalItems=77
                //   16:02:49.818  SKIP caller=bind3-sessionLoad (count=77)
                //   16:04:35.504  caller=flushPending(updateUIVC-backstop) totalItems=83
                //   16:04:35.536  SKIP caller=bind3-sessionLoad (count=83)
                //
                // The b897a71a backstop wins the race to apply the item list by
                // 30-100ms, so by the time bind3 — the AUTHORITATIVE post-load
                // apply — runs, `previousSnapshotIds` already matches and it
                // SKIPs. That is destructive here in a way it is not for other
                // callers: bind3 has just called `clearHeightCache()` on the
                // line above, wiping heightCache / precalcHeights /
                // measuredHeightByContentKey. The seed loop upstream re-seeds
                // the layout side, but the SKIP returns before any
                // `reconfigureItems`, so `applyContentConfiguration` never runs
                // and the CELLS keep the `lastComputedHeight` they measured
                // against the PREVIOUS generation's content. PLAF checks that
                // cell-side cache FIRST, so those cells keep answering with the
                // 23-char height while the data source holds the 226-char
                // content — cross-generation reuse, and the collapse the user
                // photographed at 16:08:37.
                //
                // Gated to the callers that can leave stale cells behind — the
                // deferred flushes and the post-load apply — so the
                // redundant-snapshot fast path this SKIP exists for (many per
                // second mid-stream, caller=bind1-messages / bind4b) keeps
                // returning immediately and pays nothing.
                if caller.hasPrefix("flushPending") || caller.hasPrefix("suspend-resume")
                    || caller == "bind3-sessionLoad" {
                    remeasureVisibleCells(reason: "skip-after-defer(\(caller))")
                }
                return
            }
            let oldSet = Set(previousSnapshotIds)
            let inserted = newItems.filter { !oldSet.contains($0) }
            previousSnapshotIds = newItems

            // Per-block dump of the last assistant message. `debug` rather than
            // `info` for two reasons: it fired on every apply (1,503 times on
            // 2026-08-11, most of them several lines each), and `tail` puts RAW
            // message content — shell commands, tool output, anything the user
            // or a tool produced — into a log file that gets exported.
            //
            // `AppLogger.debug` is `#if DEBUG` internally AND takes an
            // @autoclosure, so in Release neither the log nor the string
            // interpolation that builds it exists.
            let snapLog = AppLogger(category: "SnapshotDiag")
            let blockItems = newItems.filter { if case .assistantBlock = $0 { return true }; return false }
            if let lastAssistant = messages.last(where: { $0.role == .assistant }) {
                snapLog.debug("[SnapshotDiag] caller=\(caller) totalItems=\(newItems.count) blockItems=\(blockItems.count) lastAssistantBlocks=\(lastAssistant.blocks.count) msgCount=\(messages.count)")
                for (i, block) in lastAssistant.blocks.enumerated() {
                    let contentLen = block.content.count
                    let tail = String(block.content.suffix(20))
                    snapLog.debug("[SnapshotDiag]   block[\(i)] kind=\(block.kind) id=\(block.id.uuidString.prefix(8)) contentLen=\(contentLen) tail=\"\(tail)\"")
                }
            }

            var snapshot = NSDiffableDataSourceSnapshot<Section, MessageListItem>()
            snapshot.appendSections([.main])
            snapshot.appendItems(newItems, toSection: .main)
            // Suppress contentOffsetAdjustment during snapshot application so that
            // rapid cell self-sizing doesn't cause visible content jumps. After layout
            // settles, pin to bottom if autoScrolling.
            let snapshotLayout = viewController?.messageListLayout
            snapshotLayout?.suppressContentOffsetAdjustment = true
            isFlushing = true
            cellConfigCount = 0
            // [T-ios-preapply-endediting] End editing BEFORE the structural
            // apply, outside the batch-update transaction. Both TestFlight
            // crash families need a live first responder inside the list at
            // apply time: the AttributeGraph re-entry (synchronous
            // super.resignFirstResponder mid-transaction, C4wXLBpem 1.13(4))
            // and the _resignOrRebaseFirstResponderViewWithIndexPathMapping:
            // assertion (deferred resign returning false, 1.13(15)). Resigning
            // here removes the shared precondition for both. Cost: an active
            // text selection is dismissed when a structural update lands (new
            // block/message); reconfigure-only applies don't come through here
            // and leave selection alone.
            //
            // Deliberately NOT excluded for callers that run inside a SwiftUI
            // render pass (flushPending(updateUIVC-backstop), rebind). On
            // those paths the whole `dataSource.apply` below ALREADY runs in
            // the render pass — pre-8/17 code did the responder-chain walk
            // INSIDE the resulting batch update there, which is the precise
            // context of the C4wXLBpem stack. Moving the walk here keeps it in
            // the same pass but outside the batch-update/hosted-cell update
            // window, which is strictly earlier and strictly safer than what
            // shipped before; excluding a caller instead would keep a live
            // responder into the batch update — the exact window both crash
            // families live in. Verified live on updateUIVC-backstop with an
            // active selection (2026-08-22, iPhone 11/iOS 27).
            if let cv = viewController?.collectionView as? NoAnimationCollectionView,
               let responder = cv.trackedTextResponder, responder.isFirstResponder {
                AppLogger(category: "Responder").info("[Responder] pre-apply endEditing (caller=\(caller))")
                cv.endEditing(true)
            }
            (viewController?.collectionView as? NoAnimationCollectionView)?.isApplyingSnapshot = true
            dataSource.apply(snapshot, animatingDifferences: false)
            (viewController?.collectionView as? NoAnimationCollectionView)?.isApplyingSnapshot = false
            // [ToolLifecycle] Stage 5: log newly-inserted tool blocks now in the
            // applied snapshot. Only fires for inserts, so volume stays proportional
            // to actual new tool blocks appearing in the chat UI.
            if !inserted.isEmpty {
                let sid = vm.sessionId?.prefix(8) ?? "nil"
                let appState = UIApplication.shared.applicationState == .active ? "fg" : "bg"
                let suspended = vm.streamingUIUpdatesSuspended
                let processing = vm.isProcessing
                for item in inserted {
                    if case .assistantBlock(let msgId, let blockId) = item,
                       let msgIdx = messageIndex[msgId],
                       msgIdx < messages.count,
                       let block = messages[msgIdx].blocks.first(where: { $0.id == blockId }),
                       block.toolUseId != nil {
                        let toolName: String = switch block.kind {
                        case .shellTool: "shell_execute"
                        case .fileReadTool: "file_read"
                        case .fileWriteTool: "file_write"
                        case .fileEditTool: "file_edit"
                        case .browserTool: "browser_use"
                        case .readImageTool: "read_image"
                        case .memoryTool: "memory"
                        default: "unknown"
                        }
                        AppLogger(category: "ToolLC").info("[ToolLifecycle] RENDERED_CHATUI toolId=\(block.toolUseId?.prefix(20) ?? "nil") tool=\(toolName) sid=\(sid) appState=\(appState) suspended=\(suspended) isProcessing=\(processing) caller=\(caller)")
                    }
                }
            }
            // Re-mark layout dirty: isApplyingSnapshot suppressed the layoutSubviews()
            // that dataSource.apply() triggered internally, so UIKit thinks layout is
            // already done. Without this, layoutIfNeeded() below is a no-op and cells
            // won't be created until the next touch/scroll.
            viewController?.collectionView.setNeedsLayout()

            if let cv = viewController?.collectionView {
                // Clear stale height cache for newly inserted items. During
                // dataSource.apply(), UIKit may trigger preferredLayoutAttributesFitting
                // on OLD cells still occupying the indices where new items will go,
                // writing the old cell's height into heightCache at the new item's index.
                // This causes the new cell to inherit the old cell's height (e.g. a 28pt
                // footer height applied to a new text block), leading to overlap.
                if !inserted.isEmpty, let layout = snapshotLayout {
                    for item in inserted {
                        if let idx = newItems.firstIndex(of: item) {
                            layout.invalidateHeight(at: idx)
                        }
                    }
                }

                // [T-ios-plaf-cache-footer-typing-staleness] Fourth footer gap.
                //
                // The typing indicator's visibility is
                //   `bridge.isActiveMessage && (!hasVisibleContent || message.isAwaitingModelResponse)`
                // and BOTH of those inner terms are derived from `message`, not
                // from the bridge — `hasVisibleContent` walks `message.blocks`.
                // `FooterHeightShape` only samples bridge fields, and
                // `updateBridge` is not called when the trigger is a new block
                // arriving, so the shape comparison never runs for this
                // transition.
                //
                // Meanwhile the footer's item identity is `.assistantFooter(msgId)`,
                // which does not change when a block is appended, so the diff is
                // empty for it and UIKit never re-configures the cell (same
                // mechanism as gap 1 in cd50865c). The footer therefore keeps the
                // TALLER height it measured while "Minis is thinking…" was
                // showing, and once the first tool block lands the indicator
                // disappears but the reserved space does not — the blank strip
                // above the tool row that the user reported. It healed only on
                // re-entering the session, i.e. on a cold re-measure.
                //
                // Targeted on purpose: only the footer of the message that just
                // gained a block is invalidated (O(1) per insert, visible cells
                // only, via the same clearCachedHeight + invalidateHeight pair
                // used by the other three gaps). No new full traversal — that
                // would add main-thread hosting-graph re-entries and cut against
                // the FB13213926 series (b4268586 / 81e43b58 / e9d167c2).
                var footerDirtyMessageIds = Set<UUID>()
                for item in inserted {
                    if case .assistantBlock(let msgId, _) = item {
                        footerDirtyMessageIds.insert(msgId)
                    }
                }
                for msgId in footerDirtyMessageIds {
                    invalidateFooterHeightCaches(messageId: msgId)
                }

                cv.layoutIfNeeded()
                snapshotLayout?.suppressContentOffsetAdjustment = false
                isFlushing = false
                let rawMaxOff = cv.contentSize.height - cv.bounds.height + cv.adjustedContentInset.bottom
                let maxOff = max(0, rawMaxOff)
                if newItems.count < oldCount && cv.contentOffset.y > maxOff {
                    cv.setContentOffset(CGPoint(x: 0, y: maxOff), animated: false)
                    // After shrink, content may fall into the sub-viewport
                    // overflow region — re-evaluate compensation so any
                    // previously-inflated top inset relaxes back to base.
                    // Shrink (delete/clear) — contentSize dropped, any inset
                    // accumulated from streaming-time GROW is no longer needed
                    // and would leave a permanent blank gap at the bottom.
                    // forceRelax bypasses the monotonic guard.
                    applySubViewportCompensation(caller: "snapshot-shrink", forceRelax: true)
                } else if scrollMode == .autoScrolling && maxOff > 0 {
                    cv.contentOffset.y = maxOff
                    // Deferred pin-to-bottom: UIHostingConfiguration self-sizing may
                    // complete asynchronously after layoutIfNeeded(), updating contentSize
                    // via KVO. But if another applySnapshot starts before that KVO fires,
                    // isFlushing suppresses it. The coalesced scroll (80ms) catches the
                    // final contentSize and corrects any undershoot.
                    scheduleCoalescedScroll()
                } else {
                    // Sub-viewport (rawMaxOff <= 0) or non-autoScrolling: keep
                    // the top-inset compensation tracking contentSize so the
                    // last cell stays pinned to the overlay top.
                    applySubViewportCompensation(caller: "snapshot-subVP")
                }
                // Sync isNearBottom after snapshot apply. applySnapshot runs
                // under isFlushing=true, which suppresses the contentSize KVO
                // observer — so the KVO-side sync we added doesn't run here.
                // Without this, contentSize can grow past the visible area
                // (sub-viewport overflow) without anyone updating vm.isNearBottom,
                // leaving the scroll-to-bottom button hidden when it should appear.
                if let vm = self.vm {
                    let nb = isNearBottom()
                    if vm.isNearBottom != nb {
                        vm.isNearBottom = nb
                    }
                }
                syncScrollFlags()
            } else {
                snapshotLayout?.suppressContentOffsetAdjustment = false
                isFlushing = false
            }
        }

        private func reconfigureAllCells() {
            viewController?.messageListLayout?.clearHeightCache()
            attrStringHeightCache.removeAll()
            userBubbleHeightCache.removeAll()
            reconfigureVisibleCells()
        }

        /// Reconfigure all cells without clearing the height cache.
        /// Used after compact to update opacity without losing measured heights.
        private func reconfigureVisibleCells() {
            var snapshot = dataSource.snapshot()
            let items = snapshot.itemIdentifiers
            guard !items.isEmpty else { return }
            snapshot.reconfigureItems(items)
            (viewController?.collectionView as? NoAnimationCollectionView)?.isApplyingSnapshot = true
            dataSource.apply(snapshot, animatingDifferences: false)
            (viewController?.collectionView as? NoAnimationCollectionView)?.isApplyingSnapshot = false
        }

        // MARK: - Accurate Height Measurement

        /// Measure the rendered height of an NSAttributedString using TextKit's
        /// full layout engine (NSLayoutManager + NSTextContainer). This correctly
        /// handles NSTextAttachment subclasses (tables, code blocks, images) by
        /// calling attachmentBounds() during layout, unlike boundingRect() which
        /// uses the attachment's 1×1px placeholder image.
        /// [T-ios-decel-inv-estimate-calibration] Measure with the REAL render
        /// engine: an offscreen SelectableMarkdownTextView (MinisLayoutManager +
        /// 4/4 textContainerInset), exactly what the live cell hosts. The
        /// previous bare-NSLayoutManager measure drifted +4..+21pt on ~30% of
        /// blocks (multi-paragraph / emoji-heading content) — debug.measureCompare
        /// ground truth showed the drift is engine-internal (usesFontLeading was
        /// ruled out: identical results), so no formula over the bare engine can
        /// close it. Measuring with the same engine is exact BY CONSTRUCTION.
        /// Cost: avg 1.4ms/block (vs 0.67ms bare), cached per attributed string
        /// in attrStringHeightCache, main-thread only (seed loop already is).
        ///
        /// RETURNS the full text-view height INCLUDING its 8pt vertical insets
        /// (4 top + 4 bottom) — callers add only the SwiftUI wrapper's
        /// .padding(.vertical, 2) (+4), NOT the historical +8.
        static func measureAttributedStringHeight(_ attrStr: NSAttributedString, width: CGFloat) -> CGFloat {
            // Watchdog guard: UITextView.sizeThatFits is near-O(n²) in line
            // count (the 29k-char / 8-10s SLOW MEASURE history,
            // T-ios-longreply-boundingRect-watchdog). Above the cap, fall back
            // to the linear bare-NSLayoutManager measure and accept its small
            // drift — on a 10000pt+ cell a ±20pt error is invisible.
            guard attrStr.length <= 8000 else {
                let storage = NSTextStorage(attributedString: attrStr)
                let container = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
                container.lineFragmentPadding = 0
                let lm = NSLayoutManager()
                lm.addTextContainer(container)
                storage.addLayoutManager(lm)
                lm.ensureLayout(for: container)
                // +8 = the text view insets the tv path includes natively.
                return ceil(lm.usedRect(for: container).height) + 8
            }
            measureTextView.attributedText = attrStr
            let size = measureTextView.sizeThatFits(
                CGSize(width: width, height: .greatestFiniteMagnitude))
            return ceil(size.height)
        }

        /// Shared offscreen measurement text view (main-thread only).
        private static let measureTextView = SelectableMarkdownTextView()

        /// [T-ios-user-msg-estimate-tail-jitter] Accurate height for a USER
        /// message cell, measured with the SAME font / wrap width / paddings as
        /// `ChatMessageRow.userRow` renders, so the precalc lands the cell at its
        /// true height (the coarse char-count estimator was ~10pt short, which
        /// grew contentSize mid-decel and caused the stop-then-twitch tail jank).
        /// Returns nil for attachment-only / empty text (caller falls back to the
        /// coarse estimate). Cached by content|width|fontsize.
        ///
        /// Layout constants mirror AIChatView.userRow:
        ///   - font: .system(size: scaledMessage(16.5))
        ///   - bubble: .padding(.horizontal,14) .padding(.vertical,10)  → +20 height, -28 width
        ///   - row:   HStack { Spacer(minLength: 60); VStack(spacing:6){ attachments?; bubble } }
        ///   - cell:  .padding(.horizontal, 16) on the whole row
        /// No on-screen view is touched; pure TextKit measurement (usedRect).
        static func measureUserBubbleHeight(for message: ChatMessage, cvWidth: CGFloat,
                                            cache: inout [String: CGFloat]) -> CGFloat? {
            // userDisplayText: strip the <user-attached-files> block, trim.
            var text = message.content
            if let start = text.range(of: "<user-attached-files>") {
                let end = text.range(of: "</user-attached-files>")
                let endBound = end?.upperBound ?? text.endIndex
                text = String(text[text.startIndex..<start.lowerBound] + text[endBound...])
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            }

            let attachCount = message.attachments.isEmpty
                ? message.inputAttachments.count : message.attachments.count

            // [T-ios-user-attach-estimate-mismatch] Attachment tile block, from
            // the SAME constants the renderer uses (UserAttachmentList).
            //
            // This was `rows * 70 + 6` with a `70`-pitch row count, while the
            // renderer draws 64pt tiles with 6pt FlowLayout spacing — a +6pt
            // over-estimate per row on EVERY image bubble, and a row-count
            // disagreement near width boundaries. `70` appeared nowhere in the
            // rendering code; it was estimator-only drift.
            let fontSize = FontSettings.shared.scaledMessage(16.5)
            // [T-ios-queued-bubble-withdraw-width] A QUEUED message renders a
            // withdraw button beside the bubble:
            //
            //     HStack(spacing: 6) { Text(...).padding(.horizontal, 14)
            //                          if isQueued { Button(xmark, size 22) } }
            //
            // (ChatMessageViews.swift userRow). That button and its spacing eat
            // ~28pt of the row, so the text actually wraps 28pt NARROWER than
            // this estimate assumed — the estimate counted fewer lines than the
            // render produces, the cell was laid out too short, and SwiftUI
            // truncated the Text to fit (one line + ellipsis, with the
            // attachment tiles spilling over neighbouring rows).
            //
            // This is why the bug looked intermittent and "self-healed": the
            // button exists ONLY while the message is queued. Once the send
            // queue drains, `isQueued` flips false, the button disappears, and
            // any later re-measure lands on the correct height — so scrolling
            // or re-entering the session appeared to fix it, while every newly
            // sent message reproduced it.
            //
            // Under-estimates are the harmful direction (over-estimates merely
            // leave a gap); subtract the button when it is actually rendered.
            let withdrawButtonWidth: CGFloat = message.isQueued ? (22 /*icon*/ + 6 /*HStack spacing*/) : 0
            let usableTextWidth = max(cvWidth - 32 /*cell*/ - 60 /*Spacer*/ - 28 /*bubble pad*/ - withdrawButtonWidth, 80)

            let attachH = UserAttachmentTileMetrics.blockHeight(
                count: attachCount,
                availableWidth: cvWidth - 32 /*cell*/ - 60 /*Spacer*/
            )

            guard !text.isEmpty else {
                // Attachment-only message: return tiles height if any, else nil
                // so the caller uses the coarse estimate (covers oddball cases).
                return attachCount > 0 ? attachH : nil
            }

            // `usableTextWidth` is part of the key, so the queued vs sent forms
            // (which differ by the withdraw button's 28pt) can't share a cached
            // measurement — the entry is invalidated implicitly when the queue
            // drains. Stated here because that is load-bearing, not incidental.
            let key = "\(text.count)|\(Int(usableTextWidth))|\(Int(fontSize))|\(attachCount)|\(text.hashValue)"
            if let cached = cache[key] {
                return cached
            }

            // [T-ios-user-msg-estimate-tail-jitter] Measure with UILabel, NOT raw
            // NSLayoutManager.usedRect. SwiftUI `Text` lays each line at the
            // font's full line-height box (with default leading); a bare
            // NSLayoutManager usedRect is ~2-3pt/line TIGHTER, so it
            // under-estimated by ~7pt on a multi-line bubble — and an
            // UNDER-estimate is exactly what makes the cell lay out short, so
            // SwiftUI Text truncates with "..." during scroll then visibly
            // expands at settle (the user-reported symptom). UILabel
            // (numberOfLines=0, byWordWrapping) reproduces SwiftUI Text's
            // Cheap TextKit measurement (boundingRect). NOTE: this gives the
            // font's TextKit line height (~fontSize×1.21), which is ~2.4pt/line
            // SHORTER than SwiftUI Text's rendered line box — so it slightly
            // under-estimates multi-line bubbles. We accept that here because the
            // real "…→expand" jitter is NOT primarily this residual (proven: every
            // measurement engine still left the symptom); the actual cause is
            // being investigated separately. Keep the measurement cheap.
            let font = UIFont.systemFont(ofSize: fontSize)
            let attr = NSAttributedString(string: text, attributes: [.font: font])
            let bounds = attr.boundingRect(
                with: CGSize(width: usableTextWidth, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                context: nil
            )
            let textH = ceil(bounds.height)
            // [T-ios-decel-inv-estimate-calibration → superseded] There used to
            // be a CJK-gated flat +7 here, calibrated from CJK samples that
            // measured +6.3–7.0 taller than this estimate and attributed to
            // "SwiftUI Text gives PingFang-fallback lines a taller box". The
            // row-chrome measurement below shows that reading was wrong: those
            // bubbles were short by the script-independent row constant, and
            // gating the correction on CJK is precisely what left Latin bubbles
            // 7pt short. Superseded by `rowChrome`, applied unconditionally.
            //
            // [T-ios-user-bubble-row-chrome] The row wrapper costs a CONSTANT
            // 7pt that this estimator never accounted for, so a Latin user
            // bubble was seeded 7pt SHORT — the harmful direction (the cell
            // lays out short, so SwiftUI Text truncates / the layout
            // under-reserves, and the correction on re-measure is the blank
            // strip around the message the user reported).
            //
            // Measured on device (iPhone 11), estimate vs the height the cell
            // actually needs, via [BottomGapDiag][user-bubble-estimate] paired
            // with [BottomGapDiag][seed-drift]:
            //
            //   Latin   18ch  1 line : est   42.0 → cell   49.0  (-7.0)
            //   Latin  500ch 20 lines: est  439.0 → cell  445.5  (-6.5)
            //   Latin 1135ch 49 lines: est 1065.0 → cell 1072.0  (-7.0)
            //
            // CONSTANT, not per-line. A throwaway probe isolated where it comes
            // from: boundingRect == UILabel (engineDelta +0.0) and the isolated
            // SwiftUI bubble == textH + 20 exactly (chromeMiss +0.0), while the
            // REAL ChatMessageRow measured textH + 20 + 7 (rowVsEst +7.0). The
            // text engine and the bubble padding were already correct; the miss
            // is entirely the row structure around them.
            //
            // This also explains — and replaces — the old "CJK correction". That
            // was a flat +7 gated on CJK presence, calibrated from a handful of
            // CJK samples that landed +7 taller than the estimate. It was never
            // about the script: those bubbles were short by the SAME row chrome
            // every bubble is short by, and gating it on CJK is exactly why
            // Latin bubbles kept the -7 and produced this report. Proven by
            // adding the row constant while the CJK term was still in: a CJK
            // bubble then over-reserved by precisely 7pt
            // (est 870 → cell 863, `dir=RESERVED-TOO-MUCH`), i.e. the two terms
            // were double-counting one 7pt. So the CJK special case is dropped
            // and the correction becomes unconditional.
            //
            // Why the report says "long messages": the shortfall is the same 7pt
            // at every length, but on a screen-filling bubble the re-measure
            // relayouts a full-height row (visible blank strip), while on a 42pt
            // bubble it is absorbed silently.
            let rowChrome: CGFloat = 7
            // bubble vertical padding (10*2) + row chrome + attachment block + 1pt safety.
            let total = textH + 20 + rowChrome + attachH + 1
            cache[key] = total
            #if DEBUG
            // [BottomGapDiag][user-bubble-estimate] Cheap trace of what this
            // estimator produced; pairs with [BottomGapDiag][seed-drift] at the
            // PLAF site, which prints the height the cell ACTUALLY needs. The
            // two together are what localised the constant 7pt row chrome, and
            // they stay as the regression tripwire (a future +N drift shows up
            // as a non-zero delta immediately).
            AppLogger(category: "BottomGapDiag").info(
                "[BottomGapDiag][user-bubble-estimate] chars=\(text.count) "
                + "textH=\(String(format: "%.1f", textH)) attachH=\(String(format: "%.1f", attachH)) "
                + "total=\(String(format: "%.1f", total)) "
                + "w=\(String(format: "%.0f", usableTextWidth))")
            #endif
            return total
        }


        // MARK: - Height Estimation

        /// [T-ios-scroll-decel-height-drift] The owning message id for a list
        /// item (used to detect the actively streaming message so its cells are
        /// excluded from the height memo).
        static func messageId(of item: MessageListItem) -> UUID? {
            switch item {
            case .wholeMessage(let id): return id
            case .assistantHeader(let id): return id
            case .assistantFooter(let id): return id
            case .assistantBlock(let mid, _): return mid
            }
        }

        /// [T-ios-earlier-stream-seed-freeze] Ids of every assistant message
        /// whose content can still GROW right now — not just the newest one.
        ///
        /// This is the same rule the layout's `streamingCellRanges` uses
        /// (612dddf7, "[T-stream-hover-earlier-streaming]"): with queued-turn
        /// interrupts, an EARLIER assistant message can still be receiving
        /// content through a running/streaming tool block while a newer turn
        /// streams text. Extracted into one helper because the two places that
        /// need it had drifted apart — the seed/memo exclusion in
        /// `configureCell` still tested only `messages.last`, so a growing
        /// earlier message was treated as settled history and got frozen at a
        /// stale height (see the call site for the resulting overlap).
        static func liveAssistantMessageIds(messages: [ChatMessage], isProcessing: Bool) -> Set<UUID> {
            guard isProcessing else { return [] }
            var ids: Set<UUID> = []
            if let last = messages.last, last.role == .assistant {
                ids.insert(last.id)
            }
            for msg in messages where msg.role == .assistant && msg.id != messages.last?.id {
                let hasLiveTool = msg.blocks.contains { blk in
                    switch blk.toolStatus {
                    case .running, .streaming: return true
                    default: return false
                    }
                }
                if hasLiveTool { ids.insert(msg.id) }
            }
            return ids
        }

        /// [T-ios-scroll-decel-height-drift] Stable per-content key for the
        /// measured-height memo, keyed by item identity AND a cheap content
        /// fingerprint (content length / attachment count). The fingerprint makes
        /// the key CHANGE when the content changes height without changing
        /// identity — streaming append, async image/attachment load, tool output
        /// growth — so a stale (shorter, mid-stream) height can't be seeded onto
        /// a now-taller cell. Combined with the width-gated lookup (rotation) and
        /// clearHeightCache (session switch / font-size change), this closes the
        /// memo's staleness paths. Font size is NOT in the key because a font
        /// change routes through clearHeightCache (wipes the whole memo).
        #if DEBUG
        /// [T-ios-scroll-metrics-ring] Stable content-hash identity for a cell.
        /// Unlike contentKey (built on runtime UUIDs, regenerated when history
        /// paging reloads message objects), this survives object regeneration:
        /// same rendered content → same tag. Used ONLY by the scroll-metrics
        /// ring so one cell's height trajectory can be followed across
        /// seed/measure/inv/flush events and across reload generations.
        static func stableCellTag(for item: MessageListItem, messages: [ChatMessage], messageIndex: [UUID: Int]) -> String {
            func digest(_ s: String) -> String {
                var h: UInt32 = 5381
                for b in s.prefix(96).utf8 { h = (h &* 33) &+ UInt32(b) }
                return String(h, radix: 36)
            }
            func msg(_ id: UUID) -> ChatMessage? {
                guard let i = messageIndex[id], i < messages.count else { return nil }
                return messages[i]
            }
            switch item {
            case .wholeMessage(let id):
                let c = msg(id)?.content ?? ""
                return "w#\(digest(c))#\(c.count)"
            case .assistantHeader(let id):
                let c = msg(id)?.blocks.first?.content ?? ""
                return "h#\(digest(c))"
            case .assistantFooter(let id):
                let c = msg(id)?.blocks.first?.content ?? ""
                return "f#\(digest(c))"
            case .assistantBlock(let mid, let bid):
                guard let b = msg(mid)?.blocks.first(where: { $0.id == bid }) else { return "b#?" }
                let c = b.content
                return "b#\(digest(c))#\(c.count)#\(b.kind)"
            }
        }
        #endif

        static func contentKey(for item: MessageListItem, messages: [ChatMessage], messageIndex: [UUID: Int]) -> String {
            func msg(_ id: UUID) -> ChatMessage? {
                guard let i = messageIndex[id], i < messages.count else { return nil }
                return messages[i]
            }
            switch item {
            case .wholeMessage(let id):
                let n = msg(id)?.content.count ?? 0
                let a = msg(id)?.attachments.count ?? 0
                return "w:\(id.uuidString):\(n):\(a)"
            case .assistantHeader(let id):
                return "h:\(id.uuidString)"
            case .assistantFooter(let id):
                return "f:\(id.uuidString)"
            case .assistantBlock(let mid, let bid):
                let block = msg(mid)?.blocks.first(where: { $0.id == bid })
                let n = block?.content.count ?? 0
                return "b:\(mid.uuidString):\(bid.uuidString):\(n)"
            }
        }

        static func estimateItemHeight(_ item: MessageListItem, messages: [ChatMessage], width: CGFloat) -> CGFloat {
            let scale = FontSettings.shared.scaledMessage(16)
            let lineHeight = scale * 1.4
            // [T-ios-user-attach-estimate-mismatch] The former `hPad` local died
            // with the `.user` branch's coarse tile arithmetic — the attachment
            // block now comes from UserAttachmentTileMetrics, which derives its
            // own available width. `estimateTextBlockHeight` keeps its own copy.

            switch item {
            case .wholeMessage(let msgId):
                guard let msg = messages.first(where: { $0.id == msgId }) else { return 44 }
                switch msg.role {
                case .compactDivider: return 44
                case .systemInfo: return 36
                case .user:
                    // [T-ios-user-msg-estimate-tail-jitter] Use the SAME accurate
                    // UILabel-based measurement as the precalc path, NOT the coarse
                    // char-count heuristic. The heuristic (cpl = tw/(scale*0.55))
                    // under-counts lines — a 3-line message estimated as 2 lines —
                    // so when this estimate is the height used during a fast scroll
                    // / offscreen pass (before precalc lands, or via the
                    // configureCell/prefetch estimate fallback), the
                    // UIHostingConfiguration lays the Text into a 2-line box and
                    // SwiftUI truncates it to "2 lines + …"; at settle the real
                    // measure returns 3 lines and the cell visibly expands, jolting
                    // the whole page. Routing the estimate through the accurate
                    // measurement makes the offscreen height already 3 lines, so the
                    // Text never truncates and there is nothing to expand.
                    var throwaway: [String: CGFloat] = [:]
                    if let h = Self.measureUserBubbleHeight(for: msg, cvWidth: width, cache: &throwaway) {
                        return h
                    }
                    // Fallback (empty/attachment-only): coarse heuristic.
                    //
                    // [T-ios-user-attach-estimate-mismatch] Two fixes here:
                    //  1. Same 70-vs-64 correction as measureUserBubbleHeight —
                    //     use the shared tile metrics rather than a local guess.
                    //  2. Count `inputAttachments` when `attachments` is empty,
                    //     matching measureUserBubbleHeight. A queued image-only
                    //     message (attachments not yet populated) previously
                    //     estimated 0 + 44 = 44pt against a real ~70pt — a 26pt
                    //     UNDER-estimate, the harmful direction and by far the
                    //     largest of the three errors in this path.
                    let attachCount = msg.attachments.isEmpty
                        ? msg.inputAttachments.count : msg.attachments.count
                    let imgH = UserAttachmentTileMetrics.blockHeight(
                        count: attachCount,
                        availableWidth: width - 32 /*cell*/ - 60 /*Spacer*/
                    )
                    return imgH + 44
                case .assistant: return 200
                }
            case .assistantHeader:
                return 28
            case .assistantBlock(let msgId, let blockId):
                guard let msg = messages.first(where: { $0.id == msgId }),
                      let block = msg.blocks.first(where: { $0.id == blockId }) else { return 44 }
                switch block.kind {
                case .text:
                    if let attrStr = block.cachedAttributedString {
                        let textWidth = max(width - 32, 100)
                        // [T-ios-longreply-boundingRect-watchdog] CTFramesetter instead
                        // of NSAttributedString.boundingRect. estimateItemHeight runs
                        // in prefetch/scroll-back for every not-yet-measured cell;
                        // boundingRect's TextKit1 layout is near-O(n²) in line count
                        // and could burn hundreds of ms per cell (watchdog risk on
                        // long history). CTFramesetter is 70–700× faster with the same
                        // height (±1pt). This is only a layout ESTIMATE, and the .text
                        // block's cachedAttributedString is attachment-free markdown
                        // text (attachments render as separate blocks), so it's safe.
                        let textH = SelectableMarkdownView.ctFramesetterHeight(for: attrStr, width: textWidth)
                        // +12 = text view insets (8) + SwiftUI wrapper vertical
                        // padding (4). This CT path is the coarse prefetch
                        // fallback; the seed loop's precalc uses the exact
                        // render-engine measure (measureAttributedStringHeight).
                        return textH + 12
                    }
                    return Self.estimateTextBlockHeight(block.content, width: width, scale: scale, lineHeight: lineHeight)
                case .thinking:
                    // Header (icon 14pt or label 13pt) + vPad 10·2 + capsule border ≈ 36pt,
                    // + 4pt block-wrapper vertical padding = 40 (same calibration as tool
                    // capsules, [T-ios-decel-inv-estimate-calibration]).
                    // When expanded, content area adds up to 300pt of inner text + 10pt bottom pad.
                    let headerH: CGFloat = 40
                    guard block.isThinkingExpanded, !block.content.isEmpty else {
                        return headerH
                    }
                    let innerWidth = max(width - 24, 100)         // 12pt horizontal pad each side
                    let innerLineHeight: CGFloat = 13 * 1.4 + 3   // .lineSpacing(3) on 13pt font
                    let lines = block.content.split(separator: "\n", omittingEmptySubsequences: false)
                    var textH: CGFloat = 0
                    let cpl = max(1, innerWidth / 7)              // ~7pt per CJK/wide glyph at 13pt
                    for line in lines {
                        let wrapped = max(1, ceil(CGFloat(line.count) / cpl))
                        textH += wrapped * innerLineHeight
                    }
                    let contentH = min(textH + 10, 300)            // ScrollView capped at 300pt
                    let total = headerH + contentH
                    return total
                // [T-ios-decel-inv-estimate-calibration] Tool capsules measure exactly
                // 36pt: ToolCapsuleView has NO vertical padding (unlike .text/.thinking,
                // which add .padding(.vertical, 2) → +4). The debug.scrollMetrics ring
                // proved the split: with 40 here, every real tool capsule corrected
                // 40→36 (-4) mid-decel; the +4 growths that motivated 40 were actually
                // collapsed THINKING blocks (handled above with headerH = 40).
                case .readImageTool: return 36
                case .info:
                    let lineCount = max(1, block.content.components(separatedBy: "\n").count)
                    return CGFloat(20 + lineCount * 16)
                default: return 36  // Tool capsules (execute, browser, etc.): no wrapper pad
                }
            case .assistantFooter:
                return 4
            }
        }

        static func estimateTextBlockHeight(_ content: String, width: CGFloat, scale: CGFloat, lineHeight: CGFloat) -> CGFloat {
            // [T-ios-empty-textblock-gap] An empty text block renders nothing
            // (EmptyView) — the one-line floor below is only for non-empty
            // streaming placeholders whose first delta is about to land.
            guard !content.isEmpty else { return 0 }
            let hPad: CGFloat = 80
            let tw = max(width - hPad, 100)
            let cpl = tw / (scale * 0.5)
            var total: CGFloat = 4

            let lines = content.split(separator: "\n", omittingEmptySubsequences: false)
            var i = 0
            while i < lines.count {
                let line = lines[i]
                let trimmed = line.trimmingCharacters(in: .whitespaces)

                // Fenced code block
                if trimmed.hasPrefix("```") {
                    var codeLines = 0
                    i += 1
                    while i < lines.count {
                        let cl = lines[i].trimmingCharacters(in: .whitespaces)
                        if cl.hasPrefix("```") { i += 1; break }
                        codeLines += 1
                        i += 1
                    }
                    total += 32 + CGFloat(max(codeLines, 1)) * 18 + 24
                    continue
                }

                // Table
                if trimmed.hasPrefix("|") && trimmed.contains("|") {
                    var tableRows = 0
                    while i < lines.count {
                        let tl = lines[i].trimmingCharacters(in: .whitespaces)
                        if tl.hasPrefix("|") && tl.contains("|") {
                            if !tl.contains("---") { tableRows += 1 }
                            i += 1
                        } else {
                            break
                        }
                    }
                    total += CGFloat(max(tableRows, 1)) * 36 + 16
                    continue
                }

                // Horizontal rule
                if trimmed == "---" || trimmed == "***" || trimmed == "___" {
                    total += 16
                    i += 1
                    continue
                }

                // Image
                if trimmed.hasPrefix("![") && trimmed.contains("](") {
                    total += Self.estimateMarkdownImageHeight(line: trimmed, containerWidth: tw)
                    i += 1
                    continue
                }

                // Math block
                if trimmed == "$$" {
                    i += 1
                    while i < lines.count && lines[i].trimmingCharacters(in: .whitespaces) != "$$" {
                        i += 1
                    }
                    i += 1
                    total += 80
                    continue
                }

                // Regular text
                if trimmed.isEmpty {
                    total += lineHeight * 0.5
                } else {
                    let charCount = CGFloat(trimmed.count)
                    let wrappedLines = max(1, ceil(charCount / cpl))
                    total += wrappedLines * lineHeight
                }
                i += 1
            }

            // [T-ios-stream-bubble-spacing] +4 to match the measured-path baseline
            // (cachedAttributedString branch returns usedRect + 8; this heuristic
            // seeds total = 4, so it ran 4pt short). Centralizing the compensation
            // HERE — rather than at one applySnapshot call site — means EVERY
            // consumer (applySnapshot precalc, configureCell's estimate, and the
            // prefetch estimate) gets the padded height. The earlier per-call-site
            // +4 didn't stick because configureCell re-set the estimate from this
            // function WITHOUT the +4, overwriting it; a streaming cell then laid
            // out ~4pt short and SwiftUI clipped the bubble's .padding(.vertical, 2),
            // so the text looked glued to the bubble edges until a real measure ran.
            return max(total, lineHeight + 4) + 4
        }

        /// Estimate height for a Markdown image line by checking the image cache.
        /// Falls back to 200pt placeholder if the image hasn't been loaded yet.
        private static func estimateMarkdownImageHeight(line: String, containerWidth: CGFloat) -> CGFloat {
            let maxImageWidth: CGFloat = 400
            // Extract URL from ![alt](url)
            guard let openParen = line.range(of: "]("),
                  let closeParen = line.range(of: ")", range: openParen.upperBound..<line.endIndex) else {
                return 200
            }
            let src = String(line[openParen.upperBound..<closeParen.lowerBound])
            if let cached = NativeMediaImageCache.shared.image(for: src) {
                let imgWidth = min(min(containerWidth, cached.size.width), maxImageWidth)
                let aspect = cached.size.height / max(cached.size.width, 1)
                let maxH = UIScreen.main.bounds.height / 2
                return min(imgWidth * aspect, maxH) + 6
            }
            return 200
        }

        // MARK: - Scroll

        private let nearBottomThreshold: CGFloat = 20

        /// Tighter threshold for *re-acquiring* `.autoScrolling` after a
        /// deliberate user interaction settles. `nearBottomThreshold` (20pt)
        /// is right for *maintaining* the bottom-pin during streaming, but
        /// reusing it to re-arm auto-scroll caused trackpad jitter on iPad:
        /// a tiny two-finger scroll up leaves the offset well within 20pt of
        /// the bottom, so settle flipped straight back to `.autoScrolling`,
        /// the contentSize KVO re-pinned to the bottom, and that fought the
        /// next small scroll — the scrollbar darted up and down. Requiring the
        /// content to be essentially flush with the bottom (≤4pt) means a
        /// deliberate small scroll stays in `.userBrowsing`, while a scroll
        /// that genuinely lands at the bottom still resumes follow-along.
        /// [T-ios-trackpad-scroll-jitter]
        private let reacquireAutoScrollThreshold: CGFloat = 4

        /// Whether a list item is part of the given assistant message's span
        /// (block or footer — the header is the span's start, matched
        /// separately). [T-stream-hover-earlier-streaming]
        static func item(_ item: MessageListItem, belongsTo messageId: UUID) -> Bool {
            switch item {
            case .assistantBlock(let mid, _): return mid == messageId
            case .assistantFooter(let mid): return mid == messageId
            case .wholeMessage, .assistantHeader: return false
            }
        }

        func isNearBottom() -> Bool {
            return isWithinBottom(nearBottomThreshold)
        }

        /// True when the viewport is at (or within `threshold` of) the very top
        /// of the content — the first message's top is showing. Drives the
        /// up-button's hide: once the user has walked all the way up, there's
        /// nowhere further to jump. The top resting offset is
        /// -adjustedContentInset.top (content pinned under the nav bar).
        func isAtContentTop(_ threshold: CGFloat = 8) -> Bool {
            guard let cv = viewController?.collectionView else { return false }
            return cv.contentOffset.y <= -cv.adjustedContentInset.top + threshold
        }

        /// True when the content offset is within `threshold` pt of the
        /// maximum (bottom) offset.
        func isWithinBottom(_ threshold: CGFloat) -> Bool {
            guard let cv = viewController?.collectionView else { return true }
            // Use adjustedContentInset to account for input bar + tool bar overlay insets.
            let maxOffset = cv.contentSize.height - cv.bounds.height + cv.adjustedContentInset.bottom
            return maxOffset - cv.contentOffset.y < threshold
        }

        func scrollToLastItem(animated: Bool = false) {
            guard let cv = viewController?.collectionView else { return }
            let section = cv.numberOfSections - 1
            guard section >= 0 else { return }
            let item = cv.numberOfItems(inSection: section) - 1
            guard item >= 0 else { return }
            cv.scrollToItem(at: IndexPath(item: item, section: section), at: .bottom, animated: animated)
        }

        /// The user-message id the up-button last jumped to, so a repeated tap
        /// walks one turn further back. Reset (nil) whenever the anchoring
        /// context changes — manual drag, jump-to-bottom, snapshot change, or
        /// session load — so the next tap re-anchors to the current viewport
        /// instead of continuing from a stale turn.
        var lastJumpedUserId: UUID?

        /// Diagnostics: which user turn the current viewport TOP is in.
        /// Returns (turnIndex, totalTurns); turnIndex is 0-based into the ordered
        /// list of user messages, -1 if unresolved. Cheap enough to call from a
        /// throttled scroll sampler.
        func currentViewportUserTurn() -> (index: Int, total: Int) {
            guard let cv = viewController?.collectionView,
                  let vm, let ds = dataSource else { return (-1, 0) }
            let msgs = vm.messages
            let userIds = msgs.compactMap { $0.role == .user ? $0.id : nil }
            guard !userIds.isEmpty else { return (-1, 0) }
            let topItemMsgId: UUID? = cv.indexPathsForVisibleItems.min()
                .flatMap { ds.itemIdentifier(for: $0) }
                .flatMap { Self.messageId(of: $0) }
            let topMsgIdx = topItemMsgId.flatMap { id in msgs.firstIndex(where: { $0.id == id }) } ?? 0
            let anchor = msgs[...topMsgIdx].reversed().first(where: { $0.role == .user })?.id ?? userIds.first!
            return (userIds.firstIndex(of: anchor) ?? -1, userIds.count)
        }

        /// Up-button action: walk backwards through the conversation one user
        /// turn at a time. Replaces the old "jump to the very first message":
        ///   - First tap: scroll to the user message of the turn the viewport is
        ///     currently in (nearest role==.user at or above the top visible item).
        ///   - Repeated taps (no intervening drag / new message / etc): each tap
        ///     goes to the PREVIOUS turn's user message.
        ///   - At the first turn already: stay put, no overscroll.
        /// A browse action — must NOT touch scrollMode (same rationale as the old
        /// scrollToTopNow: leave auto-scroll as-is so streaming doesn't yank the
        /// user back down after they jump up).
        func scrollToPreviousUserTurn(animated: Bool = true) {
            guard let cv = viewController?.collectionView,
                  let vm, let ds = dataSource else { return }
            let msgs = vm.messages
            guard !msgs.isEmpty else { return }

            // Ordered list of user-message ids as they appear in the conversation.
            let userIds = msgs.compactMap { $0.role == .user ? $0.id : nil }
            guard !userIds.isEmpty else {
                // No user turns (rare) — fall back to the top of the list.
                if cv.numberOfSections > 0, cv.numberOfItems(inSection: 0) > 0 {
                    cv.scrollToItem(at: IndexPath(item: 0, section: 0), at: .top, animated: animated)
                }
                return
            }

            // Resolve the message id at the top of the viewport → its message
            // index → the nearest user message at or above it (the current turn's
            // anchor).
            let topItemMsgId: UUID? = cv.indexPathsForVisibleItems.min()
                .flatMap { ds.itemIdentifier(for: $0) }
                .flatMap { Self.messageId(of: $0) }
            let topMsgIdx = topItemMsgId.flatMap { id in msgs.firstIndex(where: { $0.id == id }) } ?? 0
            let currentAnchor = msgs[...topMsgIdx].reversed().first(where: { $0.role == .user })?.id ?? userIds.first!

            // Decide the target: if the viewport is already at the anchor we last
            // jumped to (user has seen this turn's start), step to the previous
            // turn; otherwise land on the current turn's anchor first.
            let target: UUID
            if lastJumpedUserId == currentAnchor,
               let pos = userIds.firstIndex(of: currentAnchor), pos > 0 {
                target = userIds[pos - 1]
            } else {
                target = currentAnchor
            }

            guard let targetItem = ds.snapshot().itemIdentifiers.first(where: {
                if case .wholeMessage(let id) = $0, id == target { return true }
                return false
            }), let ip = ds.indexPath(for: targetItem) else {
                AppLogger(category: "ScrollDiag").info("[ScrollDiag][prevUserTurn] ABORT target item not found target=\(target.uuidString.prefix(8))")
                return
            }

            lastJumpedUserId = target
            let curTurn = userIds.firstIndex(of: currentAnchor) ?? -1
            let tgtTurn = userIds.firstIndex(of: target) ?? -1
            AppLogger(category: "ScrollDiag").info("[ScrollDiag][prevUserTurn][CALL] curTurn=\(curTurn) targetTurn=\(tgtTurn)/\(userIds.count) topMsgIdx=\(topMsgIdx) currentAnchor=\(currentAnchor.uuidString.prefix(8)) target=\(target.uuidString.prefix(8)) ip=\(ip.section).\(ip.item) \(Self.nbDump(cv))")
            // Also record to the in-memory ring (debug.scrollMetrics) — the
            // on-device FILE log pipeline can stall, but the ring is live.
            // idx=target user-turn index, a=current viewport turn, b=total turns,
            // pos encodes the resulting button visibility for this frame.
            #if DEBUG
            let nbNow = isNearBottom()
            ScrollMetricsRecorder.shared.record(kind: "prevUserTurn", idx: tgtTurn,
                a: Double(curTurn), b: Double(userIds.count),
                pos: "call buttonsVisible=\(!msgs.isEmpty && !nbNow)",
                offset: Double(cv.contentOffset.y), note: Self.nbDump(cv))
            #endif
            cv.scrollToItem(at: ip, at: .top, animated: animated)
            // Re-sync isNearBottom so the up/down buttons reflect the new
            // position. Two syncs on purpose:
            //  - the immediate main.async catches the common case;
            //  - the delayed one covers UIKit's animated scrollToItem, whose
            //    internal animation may not fire a terminal scrollViewDidScroll
            //    at the settled offset. 0.4s covers the default scroll animation.
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                let cv2 = self.viewController?.collectionView
                let nb = self.isNearBottom()
                #if DEBUG
                ScrollMetricsRecorder.shared.record(kind: "prevUserTurn",
                    a: nb ? 1 : 0, b: (self.vm?.isNearBottom == true) ? 1 : 0,
                    pos: "sync-immediate", offset: Double(cv2?.contentOffset.y ?? -9999),
                    note: Self.nbDump(cv2))
                #endif
                _ = nb
                self.syncScrollFlags()
            }
            if animated {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                    guard let self else { return }
                    let cv2 = self.viewController?.collectionView
                    let nb = self.isNearBottom()
                    #if DEBUG
                    ScrollMetricsRecorder.shared.record(kind: "prevUserTurn",
                        a: nb ? 1 : 0, b: (self.vm?.isNearBottom == true) ? 1 : 0,
                        pos: "sync-0.4s", offset: Double(cv2?.contentOffset.y ?? -9999),
                        note: Self.nbDump(cv2))
                    #endif
                    _ = nb
                    self.syncScrollFlags()
                }
            }
        }

        /// Compact dump of the isWithinBottom inputs for ScrollDiag logs.
        static func nbDump(_ cv: UICollectionView?) -> String {
            guard let cv else { return "cv=nil→isWithinBottom RETURNS TRUE" }
            let maxOff = cv.contentSize.height - cv.bounds.height + cv.adjustedContentInset.bottom
            let diff = maxOff - cv.contentOffset.y
            return "off=\(String(format: "%.0f", cv.contentOffset.y)) cs=\(String(format: "%.0f", cv.contentSize.height)) bh=\(String(format: "%.0f", cv.bounds.height)) adjBot=\(String(format: "%.0f", cv.adjustedContentInset.bottom)) maxOff=\(String(format: "%.0f", maxOff)) diff=\(String(format: "%.0f", diff))"
        }

        /// Base contentInset.top (without sub-viewport overflow compensation).
        /// iOS 26+ relies on system; older iOS uses an 8pt manual padding.
        var baseContentInsetTop: CGFloat {
            if #available(iOS 26, *) { return 0 } else { return 8 }
        }

        /// Base contentInset.bottom = inputBarHeight + floatingBarHeight + 8.
        /// Set by updateUIViewController; the actual cv.contentInset.bottom
        /// equals this plus the current sub-viewport compensation (if any).
        /// `applySubViewportCompensation` is the only place that writes
        /// contentInset.bottom — it reads this base and adds overflow.
        var baseBottomInset: CGFloat = 0

        /// When content overflows the visible area but is still smaller than the
        /// UIKit-scrollable threshold, UIKit clamps maxOffset at 0 and the
        /// bottom of the content gets hidden behind the bottom overlay.
        ///
        /// Compensate by inflating `contentInset.bottom` by the overflow
        /// amount so UIKit's natural maxOffset moves down (= contentSize -
        /// bounds + bottomInset becomes positive). The user can then scroll
        /// up to reveal the bottom rows that were hidden by the floating bar.
        /// `contentInset.top` stays at `baseContentInsetTop` so the first row
        /// is always glued to the safe area — no blank above the user message.
        ///
        /// `applySubViewportCompensation` is the only writer of
        /// `cv.contentInset.bottom`. updateUIViewController writes
        /// `coord.baseBottomInset`; this function reads it and applies
        /// `baseBottomInset + (needs ? overflow : 0)`.
        ///
        /// Returns true if compensation is currently active.
        @discardableResult
        func applySubViewportCompensation(caller: String = "?", forceRelax: Bool = false) -> Bool {
            guard let cv = viewController?.collectionView else { return false }
            let safeAreaTop = cv.safeAreaInsets.top
            // Use baseBottomInset (set by updateUIViewController) as the truth
            // for the non-compensated bottom. Reading cv.adjustedContentInset.bottom
            // here would create a feedback loop: we'd be measuring the inset we
            // just inflated and concluding we don't need to inflate any more.
            let bottomInsetBase = baseBottomInset
            let visibleH = cv.bounds.height - safeAreaTop - bottomInsetBase
            let overflow = cv.contentSize.height - visibleH
            // Active compensation: content overflows but UIKit thinks no scroll
            // is needed. UIKit's natural maxContentOffset is
            //   contentSize - bounds + adjustedContentInset.top + adjustedContentInset.bottom
            // safeAreaTop becomes adjustedContentInset.top, so include it here —
            // omitting it under-counts maxOffset by safeAreaTop (~116pt) and
            // wrongly engages compensation when content already fits naturally.
            let rawMaxOff = cv.contentSize.height - cv.bounds.height
                          + bottomInsetBase + safeAreaTop
            let needsCompensation = overflow > 0 && rawMaxOff <= 0
            let prevBottomInset = cv.contentInset.bottom

            // [B-fix] Monotonic compensation: during streaming, contentSize
            // grows token-by-token. The naive `desired = needs ? base+overflow
            // : base` jumps the inset back to base the moment `rawMaxOff`
            // crosses 0 (~content reaches bounds.height), and the PIN below
            // chases the inset down — visually the streaming cell snaps from
            // mid-screen back to the bottom by ~overflow pt (observed: 114pt
            // jump at 17:35:39, log [CHANGE] bottomInset 302.7→188.4 RELAX).
            //
            // Hold the inflated inset until either (a) caller explicitly asks
            // for a RELAX (e.g. mode change, snapshot-shrink, clear), or (b)
            // the cell can naturally scroll past `prevBottomInset - base` —
            // i.e. rawMaxOff has caught up to the existing inflation, so
            // releasing it produces no geometric jump.
            let priorInflation = max(0, prevBottomInset - bottomInsetBase)
            let canSafelyRelax = rawMaxOff >= priorInflation - 0.5
            let desiredBottomInset: CGFloat
            if needsCompensation {
                // Grow only — never let the inset shrink mid-stream.
                desiredBottomInset = max(prevBottomInset, bottomInsetBase + overflow)
            } else if forceRelax || canSafelyRelax {
                desiredBottomInset = bottomInsetBase
            } else {
                desiredBottomInset = prevBottomInset
            }
            let bottomInsetWillChange = abs(prevBottomInset - desiredBottomInset) > 0.5
            if bottomInsetWillChange {
                let reason: String
                if desiredBottomInset < prevBottomInset {
                    reason = forceRelax ? "RELAX-FORCED" : "RELAX-SAFE (rawMaxOff≥priorInfl)"
                } else if needsCompensation && prevBottomInset <= bottomInsetBase + 0.5 {
                    reason = "ENGAGE (overflow=\(String(format: "%.0f", overflow)))"
                } else {
                    reason = "GROW (overflow=\(String(format: "%.0f", overflow)))"
                }
                AppLogger(category: "SubViewport").info("[CHANGE] from=\(caller) bottomInset \(String(format: "%.1f", prevBottomInset))→\(String(format: "%.1f", desiredBottomInset)) Δ\(String(format: "%+.1f", desiredBottomInset - prevBottomInset)) — \(reason)")
                cv.contentInset.bottom = desiredBottomInset
            } else if priorInflation > 0.5 {
                // [BottomGapDiag] The inset did NOT change but is still inflated
                // above base — i.e. compensation is being HELD by the [B-fix]
                // monotonic rule (`canSafelyRelax == false`). This is invisible to
                // the `[CHANGE]` log above, which only fires on movement, yet it
                // is precisely the state that leaves reserved blank space under
                // the last bubble. Logging it is the whole point of this probe:
                // if the reported gap correlates with HOLD lines, the monotonic
                // relax condition is the root cause.
                AppLogger(category: "BottomGapDiag").info(
                    "[BottomGapDiag][HOLD] from=\(caller) inflation=\(String(format: "%.1f", priorInflation)) "
                    + "inset=\(String(format: "%.1f", prevBottomInset)) base=\(String(format: "%.1f", bottomInsetBase)) "
                    + "rawMaxOff=\(String(format: "%.1f", rawMaxOff)) overflow=\(String(format: "%.1f", overflow)) "
                    + "needsComp=\(needsCompensation) canRelax=\(canSafelyRelax) — inflated inset retained")
            }
            // Pin offset so content's bottom reaches the overlay top
            // (= bounds - bottomInset in viewport coords). Active in both the
            // compensation and the natural-scroll regime, as long as we are in
            // autoScrolling mode and there's something to pin to. Read the
            // INFLATED inset (cv.adjustedContentInset.bottom) for the pin
            // target so we glue to the just-applied bottom.
            if scrollMode == .autoScrolling {
                // [PIN-fix] Pin against the BASE bottom inset, not the
                // inflated one. Reading inflated `cv.adjustedContentInset.bottom`
                // here would push the cell up by `bottomI - base` (= the
                // sub-viewport compensation amount, up to ~90pt observed),
                // because the inflated inset effectively reserves blank space
                // BELOW the input bar that nobody can see. The compensation's
                // sole job is to make UIKit's natural maxOffset positive so
                // the user can scroll up to reveal the last cell — it is NOT
                // meant to define the resting position. Pinning to base keeps
                // the last cell glued to the input-bar top.
                //
                // adjustedContentInset.top still applies (safe-area /
                // navigation bar) — keep that as-is.
                let pinBottomInset = bottomInsetBase + cv.safeAreaInsets.bottom
                let rawTarget = cv.contentSize.height - cv.bounds.height + pinBottomInset
                // Clamp: don't let target go below -adjustedTop (would scroll
                // past the top edge), and don't exceed the inflated-inset
                // maxOff (would over-scroll past content end).
                let inflatedMaxOff = cv.contentSize.height - cv.bounds.height + cv.adjustedContentInset.bottom
                let topLimit = -cv.adjustedContentInset.top
                let target = min(max(rawTarget, topLimit), inflatedMaxOff)
                if target > topLimit - 0.5 && abs(cv.contentOffset.y - target) > 0.5 {
                    cv.contentOffset.y = target
                }
            }
            return needsCompensation
        }

        func scrollToBottomNow(animated: Bool = false) {
            guard let cv = viewController?.collectionView else { return }
            // Sub-viewport overflow path: re-evaluate compensation, then pin.
            if applySubViewportCompensation(caller: "scrollToBottom") {
                syncScrollFlags()
                return
            }
            let maxOff = cv.contentSize.height - cv.bounds.height + cv.adjustedContentInset.bottom
            guard maxOff > 0 else { return }
            let target = CGPoint(x: 0, y: maxOff)
            if animated {
                isAnimatingScrollToBottom = true
                cv.setContentOffset(target, animated: true)
                // The animated set's final scrollViewDidScroll should land within
                // the near-bottom threshold, but an interrupted animation or
                // sub-pixel/inset rounding can leave vm.isNearBottom stuck false,
                // so the scroll-to-bottom button keeps showing while already at
                // the bottom. Re-sync once the animation settles.
                DispatchQueue.main.async { [weak self] in self?.syncScrollFlags() }
            } else {
                cv.contentOffset = target
                syncScrollFlags()
            }
        }

        /// Push the current near-bottom / far-from-top readings to the view
        /// model (change-gated to avoid @Published churn). Call after any
        /// programmatic scroll whose terminal scrollViewDidScroll may not fire
        /// or may land just outside a threshold.
        /// Re-sync isNearBottom after a programmatic jump (up-turn walk /
        /// jump-to-bottom) so the floating up/down buttons — both gated on
        /// !isNearBottom — reflect the settled position even when the terminal
        /// scrollViewDidScroll lands just outside the threshold. Called on the
        /// main thread after a jump settles.
        func syncScrollFlags() {
            guard let vm else { return }
            let nb = isNearBottom()
            if vm.isNearBottom != nb {
                AppLogger(category: "ScrollDiag").info("[ScrollDiag][syncScrollFlags] isNearBottom \(vm.isNearBottom)→\(nb) \(Self.nbDump(viewController?.collectionView))")
                vm.isNearBottom = nb
            }
            let atTop = isAtContentTop()
            if vm.isAtFirstTurn != atTop { vm.isAtFirstTurn = atTop }
        }

        // MARK: - Scrolling Screenshot Capture

        /// [T-ios-copy-screenshot-diag-logs] Locate the UITextView descendant
        /// (if any) that currently holds an active text-range selection in
        /// the collection view subtree. Used only by the screenshot
        /// diagnostic trace — non-destructive (does NOT clear selection).
        /// Returns the first textView found with a non-zero selectedRange,
        /// matching the depth-first walk order of `clearTextSelection`.
        @MainActor
        private func activeSelectableTextView(in cv: UICollectionView) -> UITextView? {
            var found: UITextView? = nil
            func walk(_ view: UIView) {
                if found != nil { return }
                if let tv = view as? UITextView, tv.selectedRange.length > 0 {
                    found = tv
                    return
                }
                for sub in view.subviews { walk(sub) }
            }
            walk(cv)
            return found
        }

        /// Walk all subviews of `cv` and clear any active text selection so
        /// selection handles, magnifier loupes, focus rings and tinted ranges
        /// don't show up in screenshots.
        @MainActor
        private func clearTextSelection(in cv: UICollectionView) {
            // Dismiss keyboard / global edit menu first.
            cv.window?.endEditing(true)
            UIMenuController.shared.hideMenu()

            func walk(_ view: UIView) {
                if let tv = view as? UITextView {
                    if tv.isFirstResponder { tv.resignFirstResponder() }
                    // Collapse selection to a zero-length range (nil doesn't
                    // always clear UITextView's internal selection state).
                    if let start = tv.position(from: tv.beginningOfDocument, offset: 0) {
                        tv.selectedTextRange = tv.textRange(from: start, to: start)
                    }
                    tv.selectedTextRange = nil
                }
                for sub in view.subviews { walk(sub) }
            }
            walk(cv)
        }

        /// Capture a conversation turn by scrolling the live collection view
        /// page by page and stitching the snapshots into a single image.
        ///
        /// Uses fixed-height pages (`usableH`). Each page is cropped to
        /// exclude the nav bar (top) and input bar (bottom) overlay areas.
        /// Original `contentOffset` is restored before returning.
        @MainActor
        func captureScrollingTurnScreenshot(userMessageId: UUID, assistantMessageId: UUID?) -> UIImage? {
            // [T-ios-copy-screenshot-diag-logs] All ChatScreenshot logs share
            // this monotonic anchor so users can read elapsed-ms deltas
            // between stages without reasoning about wall-clock timestamps.
            let t0 = CACurrentMediaTime()
            func ms() -> String { String(format: "%6.1fms", (CACurrentMediaTime() - t0) * 1000) }

            guard let cv = viewController?.collectionView,
                  let snapshot = dataSource?.snapshot() else {
                screenshotLogger.info("captureScrolling: no cv or snapshot")
                chatScreenshotLogger.info("[Stage1-Entry] \(ms()) ABORT — no cv or snapshot")
                return nil
            }
            let items = snapshot.itemIdentifiers

            guard let startIdx = items.firstIndex(where: {
                if case .wholeMessage(let id) = $0, id == userMessageId { return true }
                return false
            }) else {
                screenshotLogger.info("captureScrolling: user item not found")
                chatScreenshotLogger.info("[Stage1-Entry] \(ms()) ABORT — user message id=\(userMessageId.uuidString.prefix(8)) not found in snapshot")
                return nil
            }

            // [T-ios-copy-screenshot-missing-assistant] End the capture at the
            // LAST item belonging to the assistant message, whatever kind it is
            // — not specifically its `.assistantFooter`.
            //
            // An assistant turn is expanded into header + one item per block +
            // (conditionally) a footer. `needsFooter` is only true for the LAST
            // assistant message, or when the turn has an error / interrupt /
            // usage row (see the snapshot builder). So for every earlier turn
            // there is no `.assistantFooter` item at all, `lastIndex(where:)`
            // returned nil, and `endIdx` fell back to `startIdx` — the range
            // collapsed onto the user message alone, which is exactly the
            // reported bug: screenshotting any non-latest turn produced the
            // user's text with the model's reply missing.
            //
            // Matching on the owning message id covers all three item kinds, so
            // the range ends at the true bottom of the turn whether or not a
            // footer was emitted.
            let endIdx: Int
            if let assistantMessageId,
               let i = items.lastIndex(where: {
                   Coordinator.messageId(of: $0) == assistantMessageId
               }) {
                endIdx = i
            } else {
                endIdx = startIdx
            }

            guard endIdx >= startIdx else { return nil }

            // [Stage1-Entry] full pre-capture state dump. The most important
            // fields for the top-blank symptom are contentOffset.y vs the
            // measured user-message frame.minY — if these don't align after
            // the warming pass, the page loop's first iteration crops from
            // the wrong starting Y.
            let savedOffset = cv.contentOffset
            // [T-ios-copy-screenshot-top-blank] Suspend the auto-scroll
            // pinning machinery for the duration of the capture. When
            // the session is in `.autoScrolling` mode, the contentSize
            // KVO observer (around line 695) reacts to any layout
            // change — including the stream-driven cell-height
            // measurements that fire while we hold the runloop — by
            // resetting `cv.contentOffset.y = max(cv.contentOffset.y,
            // maxOff)`. That silently undoes our setContentOffset
            // writes during the page loop and the top of the turn
            // ends up missing from the canvas. Switch to
            // `.userBrowsing` for the capture (the same flag scrollViewWillBeginDragging
            // uses) and also clear `clampAfterSessionLoad`.
            // Restored before returning.
            let savedScrollMode = self.scrollMode
            let savedClampAfterSessionLoad = self.clampAfterSessionLoad
            self.scrollMode = .userBrowsing
            self.clampAfterSessionLoad = false
            chatScreenshotLogger.info("[Stage1-Entry] \(ms()) userMsg=\(userMessageId.uuidString.prefix(8)) startIdx=\(startIdx) endIdx=\(endIdx) totalItems=\(items.count) scrollMode=\(savedScrollMode == .autoScrolling ? "auto" : "browse")→browse(suspended) clampAfterSessionLoad=\(savedClampAfterSessionLoad)→false")
            chatScreenshotLogger.info("[Stage1-Entry] \(ms()) cv.contentOffset=(\(String(format: "%.1f", savedOffset.x)),\(String(format: "%.1f", savedOffset.y)))")
            chatScreenshotLogger.info("[Stage1-Entry] \(ms()) cv.bounds=\(String(format: "%.0fx%.0f", cv.bounds.width, cv.bounds.height)) cv.contentSize=\(String(format: "%.0fx%.0f", cv.contentSize.width, cv.contentSize.height))")
            chatScreenshotLogger.info("[Stage1-Entry] \(ms()) cv.adjustedContentInset=(t=\(String(format: "%.1f", cv.adjustedContentInset.top)) b=\(String(format: "%.1f", cv.adjustedContentInset.bottom)))")
            // Pre-warming frame readback — these may be ESTIMATED if the
            // user-message cell was off-screen at long-press time.
            let entryFirstFrame = cv.layoutAttributesForItem(at: IndexPath(item: startIdx, section: 0))?.frame
            let entryLastFrame = cv.layoutAttributesForItem(at: IndexPath(item: endIdx, section: 0))?.frame
            chatScreenshotLogger.info("[Stage1-Entry] \(ms()) PRE-warm firstFrame=\(entryFirstFrame.map { String(format: "y=%.1f h=%.1f", $0.minY, $0.height) } ?? "nil") lastFrame=\(entryLastFrame.map { String(format: "y=%.1f h=%.1f", $0.minY, $0.height) } ?? "nil")")
            // Is the user message currently visible? If yes, no scroll
            // adjustment needed; if not, warming has to do real work.
            let visibleRange: ClosedRange<CGFloat>? = {
                let bounds = cv.bounds
                return savedOffset.y...(savedOffset.y + bounds.height)
            }()
            if let f = entryFirstFrame, let vr = visibleRange {
                let onScreen = f.maxY >= vr.lowerBound && f.minY <= vr.upperBound
                chatScreenshotLogger.info("[Stage1-Entry] \(ms()) userMessage visibleAtLongPress=\(onScreen) frameRange=[\(String(format: "%.1f", f.minY)),\(String(format: "%.1f", f.maxY))] viewportRange=[\(String(format: "%.1f", vr.lowerBound)),\(String(format: "%.1f", vr.upperBound))]")
            }
            // Active text selection state — text-selection menu path enters
            // here with selection ranges held by some UITextView descendant.
            // Knowing whether selection is held and which direction it was
            // dragged is the spec's prime suspect for the top-blank symptom.
            let activeSel = self.activeSelectableTextView(in: cv)
            if let tv = activeSel {
                let r = tv.selectedRange
                chatScreenshotLogger.info("[Stage1-Entry] \(ms()) selection ACTIVE on textView=\(String(format: "%p", Int(bitPattern: Unmanaged.passUnretained(tv).toOpaque()))) range=(loc=\(r.location), len=\(r.length)) textLen=\(tv.textStorage.length)")
            } else {
                chatScreenshotLogger.info("[Stage1-Entry] \(ms()) no active text selection")
            }

            clearTextSelection(in: cv)
            cv.layoutIfNeeded()
            // [T-ios-copy-screenshot-top-blank] After clearing the active
            // text selection, give UIKit one runloop tick to finish
            // releasing the selection state. Without this short wait,
            // scrollToItem below sometimes no-ops or partial-commits — the
            // cv is still inside its selection-locked autoscroll state from
            // the long-press gesture that opened the menu — and the
            // measured firstFrame.minY ends up offset from where the
            // page-loop expects it, dropping the top of the user message
            // off the final canvas.
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))

            let viewportH = cv.bounds.height
            let bottomMask = max(0, cv.adjustedContentInset.bottom)
            let topMask = max(0, cv.adjustedContentInset.top)
            let navSafetyGap: CGFloat = 10
            let cropTop = topMask + navSafetyGap
            let usableH = max(1, viewportH - cropTop - bottomMask)

            chatScreenshotLogger.info("[Stage2-Warming] \(ms()) BEGIN viewportH=\(String(format: "%.1f", viewportH)) topMask=\(String(format: "%.1f", topMask)) bottomMask=\(String(format: "%.1f", bottomMask)) cropTop=\(String(format: "%.1f", cropTop)) usableH=\(String(format: "%.1f", usableH))")
            // --- Warming pass ---
            // On first session load, cells above the viewport haven't been
            // configured yet (UICollectionView lazy-loads). First scroll the
            // user message into view so its cell is created and measured,
            // then scroll page-by-page through the assistant response.
            // After this pass all layout frames reflect measured heights.
            do {
                // Step 1: bring the user message cell on screen.
                let beforeStep1 = cv.contentOffset.y
                cv.scrollToItem(at: IndexPath(item: startIdx, section: 0), at: .top, animated: false)
                cv.layoutIfNeeded()
                RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.15))
                let afterStep1 = cv.contentOffset.y
                chatScreenshotLogger.info("[Stage2-Warming] \(ms()) STEP1 scrollToItem(startIdx) offset: \(String(format: "%.1f", beforeStep1)) → \(String(format: "%.1f", afterStep1)) Δ=\(String(format: "%.1f", afterStep1 - beforeStep1))")

                // Step 2: scroll through the rest of the turn so all cells
                // between startIdx and endIdx are configured.
                let estLast = cv.layoutAttributesForItem(at: IndexPath(item: endIdx, section: 0))?.frame.maxY
                    ?? (cv.contentOffset.y + viewportH * 3)
                var warmY = cv.contentOffset.y + cropTop
                var warmCount = 0
                chatScreenshotLogger.info("[Stage2-Warming] \(ms()) STEP2 begin estLast=\(String(format: "%.1f", estLast)) initialWarmY=\(String(format: "%.1f", warmY))")
                while warmY < estLast && warmCount < 200 {
                    warmCount += 1
                    cv.contentOffset = CGPoint(x: 0, y: warmY - cropTop)
                    cv.layoutIfNeeded()
                    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.1))
                    let actual = cv.contentOffset.y + cropTop
                    if actual + usableH >= estLast { break }
                    if actual <= warmY - 0.5 && warmCount > 1 { break }
                    warmY = actual + usableH
                }
                cv.layoutIfNeeded()
                screenshotLogger.info("captureScrolling: warming pass complete (\(warmCount) steps)")
                chatScreenshotLogger.info("[Stage2-Warming] \(ms()) END steps=\(warmCount) finalOffset=\(String(format: "%.1f", cv.contentOffset.y)) estLast=\(String(format: "%.1f", estLast))")
            }

            // [T-ios-copy-screenshot-top-blank] After the warming pass
            // re-anchor scroll to the user message's measured top. The
            // warming pass advances contentOffset turn-by-turn, leaving
            // the cv parked deep in the assistant response. Subsequent
            // page-loop code reads `firstAttrs.frame` for turnTopY but
            // never repositions the scroll view — so the first page
            // capture would start from wherever the warming pass left
            // off (= bottom of the turn) rather than from the user
            // message top. The page loop's per-page scrollToY corrects
            // this once it iterates, but the first frame readback
            // happens before that and prior code paths assumed
            // contentOffset already matched. Make the assumption hold
            // by explicitly scrolling startIdx back to top here.
            let beforeReAnchor = cv.contentOffset.y
            cv.scrollToItem(at: IndexPath(item: startIdx, section: 0), at: .top, animated: false)
            cv.layoutIfNeeded()
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))
            chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) scrollToItem(startIdx, .top) offset: \(String(format: "%.1f", beforeReAnchor)) → \(String(format: "%.1f", cv.contentOffset.y)) Δ=\(String(format: "%.1f", cv.contentOffset.y - beforeReAnchor))")

            // --- Read final layout frames (now measured, not estimated) ---
            let firstAttrs = cv.layoutAttributesForItem(at: IndexPath(item: startIdx, section: 0))
            let lastAttrs = cv.layoutAttributesForItem(at: IndexPath(item: endIdx, section: 0))
            guard let firstFrame = firstAttrs?.frame, let lastFrame = lastAttrs?.frame else {
                screenshotLogger.info("captureScrolling: missing layout attributes")
                chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) ABORT — missing layout attributes (firstAttrs=\(firstAttrs == nil ? "nil" : "ok") lastAttrs=\(lastAttrs == nil ? "nil" : "ok"))")
                cv.contentOffset = savedOffset
                self.scrollMode = savedScrollMode
                self.clampAfterSessionLoad = savedClampAfterSessionLoad
                return nil
            }
            let turnTopY = firstFrame.minY - 10
            let turnBottomY = lastFrame.maxY
            let totalH = turnBottomY - turnTopY
            chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) MEASURED firstFrame=\(String(format: "y=%.1f h=%.1f maxY=%.1f", firstFrame.minY, firstFrame.height, firstFrame.maxY)) lastFrame=\(String(format: "y=%.1f h=%.1f maxY=%.1f", lastFrame.minY, lastFrame.height, lastFrame.maxY))")
            chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) DERIVED turnTopY=\(String(format: "%.1f", turnTopY)) turnBottomY=\(String(format: "%.1f", turnBottomY)) totalH=\(String(format: "%.1f", totalH))")
            // KEY DIAGNOSTIC: alignment of cv.contentOffset.y vs turnTopY
            // matters because the first iteration of the page loop ASSUMES
            // a scroll has placed nextContentY (= turnTopY) at the cropTop
            // line. If contentOffset.y is far off from (turnTopY - cropTop),
            // the first scrollToY may clamp or no-op and the top of the
            // capture starts from elsewhere.
            let expectedOffsetY = turnTopY - cropTop
            let offsetDelta = cv.contentOffset.y - expectedOffsetY
            chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) ALIGN cv.contentOffset.y=\(String(format: "%.1f", cv.contentOffset.y)) expected=(turnTopY - cropTop)=\(String(format: "%.1f", expectedOffsetY)) DELTA=\(String(format: "%.1f", offsetDelta)) (positive=scrolled past top)")
            // [T-ios-copy-screenshot-top-blank] Diagnostic dump of every
            // mechanism that could be pinning the cv to bottom and
            // overriding our setContentOffset writes — without these
            // we cannot tell whether scrollMode/autoScrolling, the
            // clamp-after-session-load timer, an in-flight animated
            // scroll, an active gesture, deceleration, or some other
            // observer is rejecting the offset write.
            let maxOff = max(0, cv.contentSize.height - cv.bounds.height + cv.adjustedContentInset.bottom)
            let nearBottom = (maxOff - cv.contentOffset.y) < 60
            chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) PIN-STATE scrollMode=\(self.scrollMode == .autoScrolling ? "auto" : "browse") clampAfterSessionLoad=\(self.clampAfterSessionLoad) isAnimatingScrollToBottom=\(self.isAnimatingScrollToBottom) maxOff=\(String(format: "%.1f", maxOff)) currentOffset.y=\(String(format: "%.1f", cv.contentOffset.y)) nearBottom=\(nearBottom) tracking=\(cv.isTracking) decel=\(cv.isDecelerating) dragging=\(cv.isDragging) GRs=\((cv.gestureRecognizers ?? []).map { "\(type(of: $0)):\($0.state.rawValue)" }.joined(separator: ","))")
            // [T-ios-copy-screenshot-top-blank] Probe each scroll write
            // separately so the log records which one (if any) actually
            // moves the offset. Reading cv.contentOffset.y BEFORE every
            // setter + RIGHT AFTER (no runloop) shows whether UIKit
            // accepted the write synchronously; one runloop hop later
            // tells whether some observer rewrote it. Without this
            // breakdown we can't distinguish "setter rejected" from
            // "setter accepted but reverted".
            if abs(offsetDelta) > 1 {
                self.clearTextSelection(in: cv)
                // Disable scroll gestures temporarily — these are the
                // most visible suspect for blocking writes during a
                // selection long-press.
                let savedGRStates: [(UIGestureRecognizer, Bool)] = (cv.gestureRecognizers ?? []).map { ($0, $0.isEnabled) }
                for (gr, _) in savedGRStates { gr.isEnabled = false }

                // Attempt A: setContentOffset(animated:false)
                let pA0 = cv.contentOffset.y
                cv.setContentOffset(CGPoint(x: 0, y: max(0, expectedOffsetY)), animated: false)
                let pA1 = cv.contentOffset.y     // immediate readback (pre-runloop)
                cv.layoutIfNeeded()
                let pA2 = cv.contentOffset.y     // after layoutIfNeeded
                RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.1))
                let pA3 = cv.contentOffset.y     // after one runloop hop — if pA3 != pA2 someone wrote it back

                // Attempt B (only if A didn't reach target): direct assignment + runloop
                var pB0: CGFloat = -1, pB1: CGFloat = -1, pB2: CGFloat = -1
                if abs(pA3 - expectedOffsetY) > 1 {
                    pB0 = cv.contentOffset.y
                    cv.contentOffset = CGPoint(x: 0, y: max(0, expectedOffsetY))
                    pB1 = cv.contentOffset.y
                    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.1))
                    pB2 = cv.contentOffset.y
                }

                for (gr, wasEnabled) in savedGRStates { gr.isEnabled = wasEnabled }
                chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) FORCE-COMMIT-A setContentOffset(animated:false) target=\(String(format: "%.1f", expectedOffsetY)) trace: pre=\(String(format: "%.1f", pA0)) postSet=\(String(format: "%.1f", pA1)) postLayout=\(String(format: "%.1f", pA2)) postRunloop=\(String(format: "%.1f", pA3))")
                if pB0 >= 0 {
                    chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) FORCE-COMMIT-B contentOffset= assignment target=\(String(format: "%.1f", expectedOffsetY)) trace: pre=\(String(format: "%.1f", pB0)) postSet=\(String(format: "%.1f", pB1)) postRunloop=\(String(format: "%.1f", pB2))")
                }
                // Final state for the page loop
                let postFinal = cv.contentOffset.y
                let postDelta = postFinal - expectedOffsetY
                chatScreenshotLogger.info("[Stage3-ReAnchor] \(ms()) FORCE-COMMIT FINAL offset=\(String(format: "%.1f", postFinal)) postDelta=\(String(format: "%.1f", postDelta)) (postDelta=0 means commit succeeded)")
            }
            guard totalH > 0 else {
                cv.contentOffset = savedOffset
                self.scrollMode = savedScrollMode
                self.clampAfterSessionLoad = savedClampAfterSessionLoad
                return nil
            }

            screenshotLogger.info("captureScrolling: turn=[\(Int(turnTopY)),\(Int(turnBottomY))] totalH=\(Int(totalH)) usableH=\(Int(usableH))")

            let scale = UIScreen.main.scale
            let cvWidth = cv.bounds.width

            // --- DEBUG: log appearance state ---
            let currentStyle = cv.traitCollection.userInterfaceStyle
            let windowStyle = cv.window?.traitCollection.userInterfaceStyle
            let windowOverride = cv.window?.overrideUserInterfaceStyle
            let cvOverride = cv.overrideUserInterfaceStyle
            screenshotLogger.info("captureScrolling [DEBUG] cv.traitCollection.userInterfaceStyle=\(currentStyle.rawValue) (0=unspec,1=light,2=dark)")
            screenshotLogger.info("captureScrolling [DEBUG] window.traitCollection.userInterfaceStyle=\(windowStyle?.rawValue ?? -1)")
            screenshotLogger.info("captureScrolling [DEBUG] window.overrideUserInterfaceStyle=\(windowOverride?.rawValue ?? -1)")
            screenshotLogger.info("captureScrolling [DEBUG] cv.overrideUserInterfaceStyle=\(cvOverride.rawValue)")
            screenshotLogger.info("captureScrolling [DEBUG] cv.backgroundColor=\(String(describing: cv.backgroundColor))")

            // Check superview/host backgrounds
            var sv = cv.superview
            var depth = 0
            while let v = sv, depth < 5 {
                let resolvedBg = v.backgroundColor?.resolvedColor(with: v.traitCollection)
                screenshotLogger.info("captureScrolling [DEBUG] superview[\(depth)] \(type(of: v)) bg=\(String(describing: resolvedBg)) overrideStyle=\(v.overrideUserInterfaceStyle.rawValue)")
                sv = v.superview
                depth += 1
            }

            // Resolve bg color and log it
            let bgResolved = UIColor.systemBackground.resolvedColor(with: cv.traitCollection)
            var bgR: CGFloat = 0; var bgG: CGFloat = 0; var bgB: CGFloat = 0; var bgA: CGFloat = 0
            bgResolved.getRed(&bgR, green: &bgG, blue: &bgB, alpha: &bgA)
            screenshotLogger.info("captureScrolling [DEBUG] resolved systemBackground RGBA=(\(bgR),\(bgG),\(bgB),\(bgA))")

            // Lock the collection view's appearance to its current mode
            let savedOverride = cv.overrideUserInterfaceStyle
            cv.overrideUserInterfaceStyle = currentStyle == .dark ? .dark : .light

            // The CV and its immediate parent have backgroundColor set to
            // black-transparent (UIColor(white:0, alpha:0)). On screen this
            // is invisible because the SwiftUI HostingView behind it provides
            // the real background. But drawHierarchy renders the CV subtree
            // in isolation — the black bg composites on top of our white fill,
            // producing a black screenshot. Temporarily swap to the correct
            // background color for the capture.
            let savedCvBg = cv.backgroundColor
            let savedParentBg = cv.superview?.backgroundColor
            cv.backgroundColor = bgResolved
            cv.superview?.backgroundColor = bgResolved

            // Final canvas — Use standard-range sRGB (8 bpc) — the default
            // UIGraphicsImageRendererFormat creates a wide-color
            // ExtendedSRGB 16 bpc bitmap, and many apps (WeChat, etc.)
            // render 16 bpc PNGs as black/white.
            // opaque = true ensures a solid background (no alpha channel)
            // so pasted images don't show as transparent/white.
            let format = UIGraphicsImageRendererFormat()
            format.scale = scale
            format.opaque = true
            format.preferredRange = .standard
            let finalSize = CGSize(width: cvWidth, height: totalH)
            let bg = bgResolved

            // Scroll through the turn range one page at a time. Each page:
            //   1. Scroll so `nextContentY` lands at the `cropTop` line
            //      (= topMask + navSafetyGap from the viewport top).
            //   2. Snapshot the full cv bounds.
            //   3. Crop the snapshot to [cropTop, cropTop + sliceH].
            //   4. Blit the crop into the final canvas at dstY.
            //   5. Advance by `usableH`.
            //
            // `cropTop` is the y-coordinate inside the viewport where the
            // usable (nav-bar-free) area starts. By scrolling so that
            // `nextContentY` sits exactly at `cropTop`, the crop always
            // starts exactly where the previous page's crop ended — no gap,
            // no overlap.
            // Snapshot each page into memory BEFORE entering the final
            // canvas renderer. Nesting `drawHierarchy(afterScreenUpdates:true)`
            // inside an outer `UIGraphicsImageRenderer.image { }` block can
            // produce black snapshots: the outer context is the current
            // graphics context, and `afterScreenUpdates:true` defers
            // rendering to a screen-update cycle that doesn't reliably
            // complete inside the nested renderer. Symptom was a stitched
            // image whose top pages were solid bg-color/black with only the
            // last page (drawn via the tail-clamp recovery path) showing
            // real content. Decouple the two phases: pass 1 collects page
            // snapshots with no outer graphics context active; pass 2
            // composites the collected snapshots into the final canvas.
            struct PageSlice {
                let snap: UIImage
                let viewportContentY: CGFloat
                let contentStart: CGFloat
                let contentEnd: CGFloat
            }
            var slices: [PageSlice] = []
            var drawnEndY: CGFloat = turnTopY

            /// Snapshot the current cv viewport, fill bg first.
            func snapshotViewport() -> UIImage {
                let pageSize = cv.bounds.size
                let pageFmt = UIGraphicsImageRendererFormat()
                pageFmt.scale = scale; pageFmt.opaque = true
                pageFmt.preferredRange = .standard
                let pr = UIGraphicsImageRenderer(size: pageSize, format: pageFmt)
                return pr.image { snapCtx in
                    bg.setFill()
                    snapCtx.fill(CGRect(origin: .zero, size: pageSize))
                    cv.drawHierarchy(in: CGRect(origin: .zero, size: pageSize), afterScreenUpdates: true)
                }
            }

            chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) BEGIN turnTopY=\(String(format: "%.1f", turnTopY)) turnBottomY=\(String(format: "%.1f", turnBottomY)) finalCanvas=\(String(format: "%.0fx%.0f", finalSize.width, finalSize.height)) scale=\(scale)")
            do {
                var nextContentY = turnTopY
                var lastActualY: CGFloat = -.greatestFiniteMagnitude
                var pageCount = 0

                while nextContentY < turnBottomY && pageCount < 200 {
                    pageCount += 1
                    let pageStart = CACurrentMediaTime()

                    // Place nextContentY at the cropTop line in the viewport.
                    let targetOffset = nextContentY - cropTop
                    let beforeOffset = cv.contentOffset.y
                    cv.contentOffset = CGPoint(x: 0, y: targetOffset)
                    cv.layoutIfNeeded()
                    cv.layer.displayIfNeeded()
                    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.15))
                    self.clearTextSelection(in: cv)

                    // Read back actual offset (UIKit clamps near scroll edges).
                    let actualY = cv.contentOffset.y + cropTop
                    let clampDelta = (cv.contentOffset.y) - targetOffset
                    chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) page=\(pageCount) nextContentY=\(String(format: "%.1f", nextContentY)) target=(offset=\(String(format: "%.1f", targetOffset))) before=\(String(format: "%.1f", beforeOffset)) after=\(String(format: "%.1f", cv.contentOffset.y)) clampDelta=\(String(format: "%.1f", clampDelta)) actualY=\(String(format: "%.1f", actualY))")
                    if actualY <= lastActualY + 0.5 && pageCount > 1 {
                        chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) page=\(pageCount) BREAK — actualY=\(String(format: "%.1f", actualY)) <= lastActualY=\(String(format: "%.1f", lastActualY))+0.5 (no progress)")
                        break
                    }
                    lastActualY = actualY

                    let pageEndY = min(turnBottomY, actualY + usableH)
                    let sliceH = pageEndY - actualY
                    if sliceH <= 0 {
                        chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) page=\(pageCount) BREAK — sliceH=\(String(format: "%.1f", sliceH)) <= 0")
                        break
                    }

                    let snapStart = CACurrentMediaTime()
                    let snap = snapshotViewport()
                    let snapMs = (CACurrentMediaTime() - snapStart) * 1000
                    chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) page=\(pageCount) slice=[\(String(format: "%.1f", actualY)),\(String(format: "%.1f", pageEndY))] sliceH=\(String(format: "%.1f", sliceH)) snap=\(String(format: "%.0fx%.0f", snap.size.width, snap.size.height)) snapTime=\(String(format: "%.1f", snapMs))ms pageTime=\(String(format: "%.1f", (CACurrentMediaTime() - pageStart) * 1000))ms")

                    if pageCount <= 2, let cg = snap.cgImage,
                       let dp = cg.dataProvider, let data = dp.data,
                       let ptr = CFDataGetBytePtr(data) {
                        let bpp = cg.bitsPerPixel / 8
                        let bpr = cg.bytesPerRow
                        let sampleY = Int((cropTop + 10) * scale)
                        let sampleX = Int(10 * scale)
                        if sampleY < cg.height && sampleX < cg.width {
                            let offset = sampleY * bpr + sampleX * bpp
                            let r = ptr[offset]; let g = ptr[offset+1]; let b = ptr[offset+2]
                            let a = bpp >= 4 ? ptr[offset+3] : 255
                            self.screenshotLogger.info("captureScrolling [DEBUG] page\(pageCount) snap pixel(\(sampleX),\(sampleY)) RGBA=(\(r),\(g),\(b),\(a)) bpp=\(bpp) size=\(cg.width)x\(cg.height) colorSpace=\(String(describing: cg.colorSpace?.name))")
                        }
                    }

                    slices.append(PageSlice(snap: snap, viewportContentY: actualY - cropTop,
                                            contentStart: actualY, contentEnd: pageEndY))
                    drawnEndY = max(drawnEndY, pageEndY)

                    if pageEndY >= turnBottomY { break }
                    nextContentY = actualY + usableH
                }

                // Tail-clamp recovery (see commit 97af7f35): if contentOffset
                // clamping prevented the loop from reaching turnBottomY,
                // scroll to maxScrollY and snapshot the missing range.
                if drawnEndY < turnBottomY - 0.5 {
                    let maxScrollY = max(0, cv.contentSize.height - cv.bounds.height)
                    chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) tail-clamp NEEDED drawnEndY=\(String(format: "%.1f", drawnEndY)) < turnBottomY=\(String(format: "%.1f", turnBottomY)) maxScrollY=\(String(format: "%.1f", maxScrollY))")
                    cv.contentOffset = CGPoint(x: 0, y: maxScrollY)
                    cv.layoutIfNeeded()
                    cv.layer.displayIfNeeded()
                    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.15))
                    self.clearTextSelection(in: cv)

                    let snap = snapshotViewport()
                    let tailContentEnd = min(turnBottomY, maxScrollY + cv.bounds.height)
                    slices.append(PageSlice(snap: snap, viewportContentY: maxScrollY,
                                            contentStart: drawnEndY, contentEnd: tailContentEnd))
                    self.screenshotLogger.info("captureScrolling: tail-clamp recovery added [\(Int(drawnEndY)),\(Int(tailContentEnd))] (was missing)")
                    drawnEndY = max(drawnEndY, tailContentEnd)
                }

                self.screenshotLogger.info("captureScrolling: \(pageCount) pages → \(Int(finalSize.width))x\(Int(finalSize.height)) drawnEnd=\(Int(drawnEndY))/turnBottom=\(Int(turnBottomY))")
                chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) END pages=\(pageCount) slices=\(slices.count) drawnEnd=\(String(format: "%.1f", drawnEndY)) turnBottom=\(String(format: "%.1f", turnBottomY)) coverageGap=\(String(format: "%.1f", turnBottomY - drawnEndY))")
                // SLICE-LEVEL DIAGNOSTIC: dump every slice's content
                // start/end + viewport origin. If the symptom is top-blank,
                // the first slice's contentStart should equal turnTopY. If
                // it's > turnTopY, we know the gap exactly.
                for (i, s) in slices.enumerated() {
                    chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) slice[\(i)] contentStart=\(String(format: "%.1f", s.contentStart)) contentEnd=\(String(format: "%.1f", s.contentEnd)) viewportContentY=\(String(format: "%.1f", s.viewportContentY)) snap=\(String(format: "%.0fx%.0f", s.snap.size.width, s.snap.size.height))")
                }
                if let firstSlice = slices.first {
                    let topGap = firstSlice.contentStart - turnTopY
                    chatScreenshotLogger.info("[Stage4-PageLoop] \(ms()) TOP-GAP=\(String(format: "%.1f", topGap)) (first slice content start minus turnTopY; positive = top of turn missing from canvas)")
                }
            }

            // Pass 2: composite the collected page snapshots into the final
            // canvas. All page bitmaps already exist as independent
            // UIImages — no nested drawHierarchy here.
            chatScreenshotLogger.info("[Stage5-Composite] \(ms()) BEGIN finalCanvas=\(String(format: "%.0fx%.0f", finalSize.width, finalSize.height)) sliceCount=\(slices.count)")
            let image = renderer(size: finalSize, format: format, bg: bg) { _ in
                for (i, slice) in slices.enumerated() {
                    let sliceH = slice.contentEnd - slice.contentStart
                    guard sliceH > 0, let cg = slice.snap.cgImage else {
                        self.chatScreenshotLogger.info("[Stage5-Composite] \(ms()) slice[\(i)] SKIP sliceH=\(String(format: "%.1f", sliceH)) hasCG=\(slice.snap.cgImage != nil)")
                        continue
                    }
                    let viewportYStart = slice.contentStart - slice.viewportContentY
                    let srcPx = CGRect(
                        x: 0,
                        y: viewportYStart * scale,
                        width: cvWidth * scale,
                        height: sliceH * scale
                    )
                    let dstY = slice.contentStart - turnTopY
                    if let cropped = cg.cropping(to: srcPx) {
                        let dst = CGRect(x: 0, y: dstY,
                                         width: cvWidth, height: sliceH)
                        UIImage(cgImage: cropped, scale: scale, orientation: .up).draw(in: dst)
                        self.chatScreenshotLogger.info("[Stage5-Composite] \(ms()) slice[\(i)] BLIT viewportYStart=\(String(format: "%.1f", viewportYStart)) srcPx=\(String(format: "%.0fx%.0f@%.0f,%.0f", srcPx.width, srcPx.height, srcPx.minX, srcPx.minY)) dstY=\(String(format: "%.1f", dstY)) dstH=\(String(format: "%.1f", sliceH))")
                    } else {
                        self.chatScreenshotLogger.info("[Stage5-Composite] \(ms()) slice[\(i)] CROP FAILED srcPx=\(String(format: "%.0fx%.0f@%.0f,%.0f", srcPx.width, srcPx.height, srcPx.minX, srcPx.minY)) cgSize=\(cg.width)x\(cg.height)")
                    }
                }
            }
            chatScreenshotLogger.info("[Stage5-Composite] \(ms()) END image=\(String(format: "%.0fx%.0f", image.size.width, image.size.height)) scale=\(image.scale)")

            cv.overrideUserInterfaceStyle = savedOverride
            cv.backgroundColor = savedCvBg
            cv.superview?.backgroundColor = savedParentBg

            // DEBUG: sample final image pixel
            if let cg = image.cgImage,
               let dp = cg.dataProvider, let data = dp.data,
               let ptr = CFDataGetBytePtr(data) {
                let bpp = cg.bitsPerPixel / 8
                let bpr = cg.bytesPerRow
                // Sample at (10, 10) — should be the background
                let offset = 10 * bpr + 10 * bpp
                let r = ptr[offset]; let g = ptr[offset+1]; let b = ptr[offset+2]
                let a = bpp >= 4 ? ptr[offset+3] : 255
                screenshotLogger.info("captureScrolling [DEBUG] FINAL image pixel(10,10) RGBA=(\(r),\(g),\(b),\(a)) bpp=\(bpp) size=\(cg.width)x\(cg.height)")
            }

            cv.contentOffset = savedOffset
            cv.layoutIfNeeded()
            self.scrollMode = savedScrollMode
            self.clampAfterSessionLoad = savedClampAfterSessionLoad
            chatScreenshotLogger.info("[Stage6-Exit] \(ms()) restored cv.contentOffset=\(String(format: "%.1f", savedOffset.y)) scrollMode=\(savedScrollMode == .autoScrolling ? "auto" : "browse") returning image=\(String(format: "%.0fx%.0f", image.size.width, image.size.height))")

            return image
        }

        private func renderer(size: CGSize, format: UIGraphicsImageRendererFormat, bg: UIColor,
                              actions: @escaping (UIGraphicsImageRendererContext) -> Void) -> UIImage {
            let r = UIGraphicsImageRenderer(size: size, format: format)
            return r.image { ctx in
                bg.setFill()
                ctx.fill(CGRect(origin: .zero, size: size))
                actions(ctx)
            }
        }

        // MARK: - UICollectionViewDataSourcePrefetching

        func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
            guard let vm, let layout = viewController?.messageListLayout else { return }
            // Items below come from previousSnapshotIds (snapshot-locked) — the
            // messages they're resolved against must be the SAME locked array,
            // or a concurrent loadSession() swap makes every lookup miss and
            // estimates collapse to the 44pt fallback. [T-ios-blocks-lost-render]
            let messages = snapshotMessages.isEmpty ? vm.messages : snapshotMessages
            let width = maxContentWidth
            for indexPath in indexPaths {
                let index = indexPath.item
                // Skip if we already have a measured or precalculated height.
                // estimateItemHeight calls NSAttributedString.boundingRect which invokes
                // CoreText font shaping (GSUB lookups) — for complex markdown blocks this
                // can take hundreds of ms per cell and accumulate into a watchdog crash
                // when the user scrolls back through long history. See 3a9fb98 for the
                // matching configureCell guard.
                guard layout.cachedHeight(at: index) == nil,
                      !layout.hasPrecalcHeight(at: index) else { continue }
                guard index < previousSnapshotIds.count else { continue }
                let item = previousSnapshotIds[index]
                let est = Self.estimateItemHeight(item, messages: messages, width: width)
                layout.setEstimatedHeight(est, at: index)
            }
        }

        // MARK: - UIScrollViewDelegate

        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
            AppLogger(category: "ScrollDiag").info("[ScrollDiag][willBeginDragging] offset=\(String(format: "%.0f", scrollView.contentOffset.y)) contentSize=\(String(format: "%.0f", scrollView.contentSize.height)) visibleCells=\(viewController?.collectionView?.visibleCells.count ?? -1)")
            #if DEBUG
            // [DecelDisplayLink] Safety: if the user grabs again mid-decel, stop
            // the previous link so it doesn't keep ticking under the new gesture.
            stopDecelDisplayLink()
            #endif
            scrollMode = .userBrowsing
            // Cancel session-load clamp immediately — user has started interacting.
            clampAfterSessionLoad = false
            // A manual drag breaks the up-button's turn-walk chain: the next tap
            // should re-anchor to wherever the user landed, not continue the old
            // sequence.
            lastJumpedUserId = nil
            viewController?.messageListLayout?.deferSelfSizing = true
            vm?.setStreamingUIUpdatesSuspended(true)
        }

        func scrollViewWillEndDragging(
            _ scrollView: UIScrollView,
            withVelocity velocity: CGPoint,
            targetContentOffset: UnsafeMutablePointer<CGPoint>
        ) {
            // [ScrollDecel] Fires the instant the finger lifts and UIKit has
            // projected where the deceleration will land. Logs the launch
            // velocity + projected target so the upcoming decel can be
            // correlated to the content region it's about to render.
            decelTargetOffset = targetContentOffset.pointee.y
            #if DEBUG
            AppLogger(category: "ScrollDecel").info("[ScrollDecel][willEndDragging] vy=\(String(format: "%.0f", velocity.y))pt/s offset=\(String(format: "%.0f", scrollView.contentOffset.y)) → target=\(String(format: "%.0f", decelTargetOffset)) projDist=\(String(format: "%.0f", decelTargetOffset - scrollView.contentOffset.y)) cs=\(String(format: "%.0f", scrollView.contentSize.height))")
            #endif
        }

        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
            if !decelerate {
                settleAfterInteraction(scrollView)
            }
        }

        func scrollViewWillBeginDecelerating(_ scrollView: UIScrollView) {
            // [ScrollDecel] DECELERATION START — the marker the analysis hangs
            // off. Every subsequent slow frame is logged as "+Xms into decel".
            let now = CACurrentMediaTime()
            decelStartTime = now
            decelLastFrameTime = now
            decelStartOffset = scrollView.contentOffset.y
            decelFrameCount = 0
            decelSlowFrameCount = 0
            #if DEBUG
            let visibleN = viewController?.collectionView?.visibleCells.count ?? -1
            AppLogger(category: "ScrollDecel").info("[ScrollDecel][BEGIN] offset=\(String(format: "%.0f", decelStartOffset)) target=\(String(format: "%.0f", decelTargetOffset)) decelDist=\(String(format: "%.0f", decelTargetOffset - decelStartOffset)) visibleCells=\(visibleN) deferSelfSizing=\((viewController?.messageListLayout?.deferSelfSizing ?? false) ? 1 : 0)")
            // [DecelDisplayLink] Start the real-frame-rate meter only for this
            // decel phase. Drops/summary go to the log; CADisplayLink is removed
            // in scrollViewDidEndDecelerating below.
            startDecelDisplayLink()
            #endif
        }

        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
            // [ScrollDecel] DECELERATION END — summarize the phase: total
            // duration, frames, how many were slow (dropped), final offset.
            if decelStartTime > 0 {
                #if DEBUG
                let dur = (CACurrentMediaTime() - decelStartTime) * 1000
                AppLogger(category: "ScrollDecel").info("[ScrollDecel][END] dur=\(String(format: "%.0f", dur))ms frames=\(self.decelFrameCount) slowFrames=\(self.decelSlowFrameCount) offset \(String(format: "%.0f→%.0f", self.decelStartOffset, scrollView.contentOffset.y)) (target was \(String(format: "%.0f", self.decelTargetOffset)))")
                // [DecelDisplayLink] Print the REAL frame-rate summary for this
                // decel and stop the link. If actualFps≈vsync and drops=0 the
                // visual "jitter" wasn't a dropped frame at all.
                logDecelDisplayLinkSummary()
                stopDecelDisplayLink()
                #endif
                decelStartTime = 0
            }
            DispatchQueue.main.async { [weak self] in
                self?.settleAfterInteraction(scrollView)
            }
        }

        /// [ScrollDecel] Called from scrollViewDidScroll. While decelerating,
        /// measures the gap since the previous frame; a gap above the threshold
        /// is a dropped/janky frame and gets logged with how far into the decel
        /// it happened, the current offset/scroll direction, and how many cells
        /// are on screen (heavy cells = expensive sizeThatFits). This is the
        /// "which stage of the decel started the costly render" signal.
        private func logDecelFrameIfSlow(_ scrollView: UIScrollView) {
            #if DEBUG
            guard decelStartTime > 0, scrollView.isDecelerating else { return }
            let now = CACurrentMediaTime()
            let gap = now - decelLastFrameTime
            decelLastFrameTime = now
            decelFrameCount += 1
            guard gap > decelSlowFrameThreshold else { return }
            decelSlowFrameCount += 1
            let sinceStart = (now - decelStartTime) * 1000
            let off = scrollView.contentOffset.y
            let dir = off >= decelStartOffset ? "↓" : "↑"
            let visibleN = viewController?.collectionView?.visibleCells.count ?? -1
            AppLogger(category: "ScrollDecel").warning("[ScrollDecel][SLOW-FRAME] +\(String(format: "%.0f", sinceStart))ms into decel gap=\(String(format: "%.0f", gap * 1000))ms offset=\(String(format: "%.0f", off))\(dir) remainToTarget=\(String(format: "%.0f", self.decelTargetOffset - off)) visibleCells=\(visibleN) frame#\(self.decelFrameCount)")
            #endif
        }

        // MARK: - [DecelDisplayLink] CADisplayLink frame meter (DEBUG only)
        //
        // The existing `[ScrollDecel] SLOW-FRAME` log measures the wall-clock gap
        // between consecutive `scrollViewDidScroll` callbacks. That is NOT the
        // same as a real dropped frame: during the tail of a decel UIKit stops
        // calling scrollViewDidScroll for the frames where contentOffset doesn't
        // visually change (snap-to-target), so a 250ms "gap" can happen with the
        // main thread completely idle and CADisplayLink ticking 16.7ms steady —
        // a false positive for jank. Run a CADisplayLink only during the decel
        // and measure actual frame intervals; >1.5× the expected vsync interval
        // is a real dropped frame.
        #if DEBUG
        private var decelDisplayLink: CADisplayLink?
        private var decelDLLastTimestamp: CFTimeInterval = 0
        private var decelDLFrames: Int = 0
        private var decelDLDrops: Int = 0
        private var decelDLMinGapMs: Double = .infinity
        private var decelDLMaxGapMs: Double = 0
        private var decelDLExpectedMs: Double = 16.67

        @objc private func decelDisplayLinkTick(_ link: CADisplayLink) {
            // The first tick has no prior timestamp — establish the baseline + expected interval.
            if decelDLLastTimestamp == 0 {
                decelDLLastTimestamp = link.timestamp
                decelDLExpectedMs = (link.targetTimestamp - link.timestamp) * 1000
                return
            }
            let gapMs = (link.timestamp - decelDLLastTimestamp) * 1000
            decelDLLastTimestamp = link.timestamp
            decelDLFrames += 1
            if gapMs < decelDLMinGapMs { decelDLMinGapMs = gapMs }
            if gapMs > decelDLMaxGapMs { decelDLMaxGapMs = gapMs }
            // A real dropped frame: gap exceeds 1.5× the expected vsync interval.
            // At 120Hz expected≈8.33ms → threshold≈12.5ms; at 60Hz ≈16.67ms → 25ms.
            let dropThreshold = decelDLExpectedMs * 1.5
            if gapMs > dropThreshold {
                decelDLDrops += 1
                let cv = viewController?.collectionView
                let off = cv?.contentOffset.y ?? 0
                let sinceStart = (CACurrentMediaTime() - decelStartTime) * 1000
                let dropped = Int((gapMs / decelDLExpectedMs).rounded()) - 1
                AppLogger(category: "ScrollDecel").warning("[DecelDisplayLink][DROP] +\(String(format: "%.0f", sinceStart))ms into decel gap=\(String(format: "%.1f", gapMs))ms (expected \(String(format: "%.1f", self.decelDLExpectedMs))ms, ~\(dropped) frame(s) dropped) offset=\(String(format: "%.0f", off)) remainToTarget=\(String(format: "%.0f", self.decelTargetOffset - off)) frame#\(self.decelDLFrames)")
            }
        }

        private func startDecelDisplayLink() {
            stopDecelDisplayLink()
            decelDLLastTimestamp = 0
            decelDLFrames = 0
            decelDLDrops = 0
            decelDLMinGapMs = .infinity
            decelDLMaxGapMs = 0
            let link = CADisplayLink(target: self, selector: #selector(decelDisplayLinkTick(_:)))
            link.add(to: .main, forMode: .common)
            decelDisplayLink = link
        }

        private func stopDecelDisplayLink() {
            decelDisplayLink?.invalidate()
            decelDisplayLink = nil
        }

        private func logDecelDisplayLinkSummary() {
            guard decelDLFrames > 0 else { return }
            let dur = (CACurrentMediaTime() - decelStartTime) * 1000
            let actualFps = decelDLFrames > 0 ? Double(decelDLFrames) / (dur / 1000) : 0
            let minGap = decelDLMinGapMs.isFinite ? decelDLMinGapMs : 0
            AppLogger(category: "ScrollDecel").info("[DecelDisplayLink][SUMMARY] dur=\(String(format: "%.0f", dur))ms frames=\(self.decelDLFrames) drops=\(self.decelDLDrops) actualFps=\(String(format: "%.1f", actualFps)) gapRange=[\(String(format: "%.1f", minGap))..\(String(format: "%.1f", self.decelDLMaxGapMs))]ms expected=\(String(format: "%.1f", self.decelDLExpectedMs))ms")
        }
        #endif

        /// Track last logged offset to detect jumps after settle.
        private var lastLoggedOffset: CGFloat = 0
        private var settleJustFinished = false

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            // [ScrollDecel] Detect dropped frames during the deceleration phase
            // (cheap: early-returns unless decelerating + frame gap exceeded).
            logDecelFrameIfSlow(scrollView)
            #if DEBUG
            // [T-ios-scroll-metrics-ring] Per-frame offset/contentSize sample —
            // lets the pulled trace reconstruct exactly when visible content
            // shifted vs when the offset moved (jitter = the two disagreeing).
            ScrollMetricsRecorder.shared.record(
                kind: "scroll", a: scrollView.contentOffset.y,
                b: scrollView.contentSize.height,
                pos: scrollView.isDecelerating ? "decel" : (scrollView.isTracking ? "drag" : "idle"),
                offset: scrollView.contentOffset.y)
            #endif
            if let vm {
                // IMPORTANT: only write when the value actually changes.
                // `isNearBottom` is `@Published`, and every write broadcasts
                // `objectWillChange` to every SwiftUI view observing the VM —
                // triggering a full AttributeGraph invalidation+re-evaluation
                // pass on the SwiftUI update thread. On iPhone 8 this was
                // burning 100% of one CPU core every scroll frame.
                let nb = isNearBottom()
                if vm.isNearBottom != nb {
                    #if DEBUG
                    // Diagnostics: record every isNearBottom TRANSITION with the
                    // scroll position and the user-turn the viewport is in, plus
                    // the resulting up/down-button visibility (!messages.empty &&
                    // !isNearBottom). Only fires on change, so it stays off the
                    // per-frame hot path. ScrollMetricsRecorder is DEBUG-only.
                    let turn = currentViewportUserTurn()
                    ScrollMetricsRecorder.shared.record(kind: "nbChange",
                        idx: turn.index, a: nb ? 1 : 0, b: Double(turn.total),
                        pos: "buttonsVisible=\(!vm.messages.isEmpty && !nb)",
                        offset: Double(scrollView.contentOffset.y),
                        note: Self.nbDump(viewController?.collectionView))
                    #endif
                    vm.isNearBottom = nb
                }
                let atTop = isAtContentTop()
                if vm.isAtFirstTurn != atTop { vm.isAtFirstTurn = atTop }
                vm.contentOffsetYSignal.send(scrollView.contentOffset.y)
            }
            // Detect offset jumps right after settle
            if settleJustFinished {
                let delta = scrollView.contentOffset.y - lastLoggedOffset
                if abs(delta) > 2 {
                    AppLogger(category: "Settle").debug("[Settle] POST-SETTLE JUMP offset \(String(format: "%.1f→%.1f", lastLoggedOffset, scrollView.contentOffset.y)) delta=\(String(format: "%.1f", delta)) tracking=\(scrollView.isTracking) decel=\(scrollView.isDecelerating) contentSize=\(String(format: "%.1f", scrollView.contentSize.height))")
                    settleJustFinished = false
                }
            }
            // [ScrollStall] Per-second scroll trajectory: offset, distance moved
            // since last flush, mode, tracking/decel state. Helps correlate
            // hitches with offset bursts.
            scrollSamples += 1
            let now = CACurrentMediaTime()
            if now - lastScrollFlush > 1.0 {
                let off = scrollView.contentOffset.y
                let dist = abs(off - lastScrollFlushOffset)
                AppLogger(category: "ScrollStall").debug("[SCR] last1s samples=\(scrollSamples) offset=\(String(format: "%.0f", off)) Δdist=\(String(format: "%.0f", dist)) cs=\(String(format: "%.0f", scrollView.contentSize.height)) mode=\(self.scrollMode == .autoScrolling ? "auto" : "browse") tracking=\(scrollView.isTracking ? 1 : 0) decel=\(scrollView.isDecelerating ? 1 : 0) deferred=\((self.viewController?.messageListLayout?.deferSelfSizing ?? false) ? 1 : 0)")
                scrollSamples = 0
                lastScrollFlushOffset = off
                lastScrollFlush = now
            }
        }
        private var scrollSamples = 0
        private var lastScrollFlushOffset: CGFloat = 0
        private var lastScrollFlush: CFTimeInterval = 0

        // [ScrollDecel] Deceleration-phase instrumentation. Lets us pin a
        // dropped frame to "Xms into the decel, at offset Y" so the
        // scroll-jank trace's micro-hangs can be aligned to the exact moment
        // and content region where the costly render started.
        private var decelStartTime: CFTimeInterval = 0
        private var decelStartOffset: CGFloat = 0
        private var decelTargetOffset: CGFloat = 0
        private var decelLastFrameTime: CFTimeInterval = 0
        private var decelFrameCount = 0
        private var decelSlowFrameCount = 0
        /// A frame gap above this (seconds) during deceleration is a dropped
        /// frame worth logging. ~32ms ≈ two missed frames at 60Hz / several at
        /// 120Hz — low enough to catch the jitter, high enough to stay quiet
        /// on smooth scrolls.
        private let decelSlowFrameThreshold: CFTimeInterval = 0.032

        func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
            isAnimatingScrollToBottom = false
            // Final clamp: ensure offset doesn't exceed actual content bounds
            // after the animation completes (contentSize may have changed mid-flight).
            guard let cv = viewController?.collectionView else { return }
            let maxOff = max(0, cv.contentSize.height - cv.bounds.height + cv.adjustedContentInset.bottom)
            if cv.contentOffset.y > maxOff {
                cv.contentOffset.y = maxOff
            }
        }

        func scrollViewDidScrollToTop(_ scrollView: UIScrollView) {
            scrollMode = .userBrowsing
        }

        /// [T-ios-defer-retry-never-consumed] Recursively walks a cell's subview
        /// tree and applies `body` to every hosted `SelectableMarkdownTextView`.
        /// Mirrors `invalidateTableAttachmentsInSubviews` in
        /// MessageListInfrastructure — the markdown view is nested several
        /// levels inside the UIHostingConfiguration's view tree, so there is no
        /// direct handle on it from the cell.
        private static func forEachMarkdownTextView(in view: UIView,
                                                    _ body: (SelectableMarkdownTextView) -> Void) {
            if let tv = view as? SelectableMarkdownTextView { body(tv) }
            for sub in view.subviews { forEachMarkdownTextView(in: sub, body) }
        }

        private func settleAfterInteraction(_ scrollView: UIScrollView) {
            // Re-acquire auto-scroll only when the user settled essentially at
            // the bottom (tight threshold), not merely "near" it. This stops
            // small trackpad scrolls on iPad from snapping back to the bottom
            // and jittering. [T-ios-trackpad-scroll-jitter]
            let atBottom = isWithinBottom(reacquireAutoScrollThreshold)
            let nearBottom = isNearBottom()
            if atBottom {
                scrollMode = .autoScrolling
            }
            guard let layout = viewController?.messageListLayout,
                  let cv = viewController?.collectionView else { return }

            let deferredCount = layout.deferredHeightCount
            AppLogger(category: "Settle").debug("[Settle] BEGIN mode=\(scrollMode == .autoScrolling ? "auto" : "browse") nearBottom=\(nearBottom) offset=\(String(format: "%.1f", cv.contentOffset.y)) contentSize=\(String(format: "%.1f", cv.contentSize.height)) deferredHeights=\(deferredCount) hasPendingSnapshot=\(hasPendingSnapshot)")

            // Re-enable self-sizing so future layout passes use real heights.
            layout.deferSelfSizing = false

            // [T-ios-defer-retry-never-consumed] Now that the defer window is
            // genuinely closed, let every visible markdown view apply a
            // correction it had to skip while the window was open.
            //
            // `SelectableMarkdownTextView.invalidateCellSizeIfNeeded` bails out
            // when `deferSelfSizing` is true, logging "will retry" and arming a
            // 0.6s timer. That timer is not a reliable consumer: it can fire
            // while the user is still dragging (landing back in the same bail),
            // and once the stream stops mutating the text storage the
            // fingerprint early-exit swallows the re-measure entirely — the
            // correction is then lost until the session is re-entered. Field
            // repro 2026-08-09: three skips in one session, none consumed; the
            // 11:01:27 one left a ~197pt error on screen for the remaining 38s
            // of the log, which is why the user could screenshot the overlap.
            //
            // Only views that actually owe a correction do any work — the call
            // is an early `guard` on a Bool for everyone else, so this adds no
            // measurement to the normal settle path.
            for cell in cv.visibleCells {
                Self.forEachMarkdownTextView(in: cell) { $0.consumeDeferredCorrectionIfNeeded() }
            }

            // [SettleJitter] Evidence capture (H1): the flush below re-flows
            // frames from corrected heights but restores only the numeric
            // offset — if cells ABOVE the viewport changed height, the same
            // offset now shows different content. Anchor on the top visible
            // cell's screen-relative y before/after to quantify the jump the
            // user actually sees at decel end.
            let anchorIP = cv.indexPathsForVisibleItems.min()
            let anchorPreY = anchorIP.flatMap { layout.layoutAttributesForItem(at: $0)?.frame.minY }

            let savedOffset = cv.contentOffset.y
            isFlushing = true
            layout.applyDeferredHeights()

            layout.suppressContentOffsetAdjustment = true
            layout.invalidateLayout()
            layout.suppressContentOffsetAdjustment = false
            isFlushing = false

            // Restore offset
            if scrollMode == .userBrowsing {
                cv.contentOffset.y = savedOffset
            }

            // [SettleJitter] Post-flush: same anchor item, new frame. screenΔ
            // is the on-screen displacement of the content the user is looking
            // at (0 = no visible jitter from the settle flush).
            if let ip = anchorIP, let preY = anchorPreY {
                cv.layoutIfNeeded()
                let postY = layout.layoutAttributesForItem(at: ip)?.frame.minY ?? preY
                let screenDelta = (postY - cv.contentOffset.y) - (preY - savedOffset)
                #if DEBUG
                // [T-ios-scroll-metrics-ring]
                ScrollMetricsRecorder.shared.record(
                    kind: "settle", idx: ip.item, a: screenDelta, b: postY - preY,
                    offset: Double(cv.contentOffset.y),
                    note: String(format: "savedOff=%.0f", savedOffset))
                #endif
                if abs(screenDelta) > 0.5 {
                    AppLogger(category: "SettleJitter").info("[SettleJitter][settle-shift] anchor=idx\(ip.item) screenΔ=\(String(format: "%+.1f", screenDelta)) frameY \(String(format: "%.0f", preY))→\(String(format: "%.0f", postY)) offset \(String(format: "%.0f", savedOffset))→\(String(format: "%.0f", cv.contentOffset.y))")
                }
            }

            vm?.setStreamingUIUpdatesSuspended(false)

            if hasPendingSnapshot {
                DispatchQueue.main.async { [weak self] in
                    self?.flushPendingSnapshotIfNeeded(caller: "settle")
                }
            }

            lastLoggedOffset = cv.contentOffset.y
            settleJustFinished = true
            // Auto-clear after 500ms
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.settleJustFinished = false
            }
            AppLogger(category: "Settle").debug("[Settle] END offset=\(String(format: "%.1f", cv.contentOffset.y)) contentSize=\(String(format: "%.1f", cv.contentSize.height))")
        }

    }
}

// MARK: - Tool Detail Sheet Presenter

/// Holds the state for the tool detail sheet, presented from a transparent overlay
/// on MessageListViewController — outside the UIHostingConfiguration cell tree.
/// This avoids the cell's `.transaction { disablesAnimations = true }` which would
/// suppress the sheet's slide-up animation, and prevents the double-present crash
/// caused by multiple cell bindings re-evaluating on shared state changes.
private final class ToolSheetPresenter: ObservableObject {
    struct SheetData: Identifiable {
        let id: UUID
        let block: AssistantBlock
        let toolBlocks: [AssistantBlock]
        let toolSnapshots: [ToolSnapshotItem]
        let browserPool: BrowserTabPool?
    }

    @Published var sheetData: SheetData?
    var onDismiss: (() -> Void)?
}

// MARK: - Compact Summary Sheet Presenter

/// Same overlay pattern as ToolSheetPresenter — presents the compact summary
/// sheet outside the cell tree so `.transaction { disablesAnimations }` does
/// not suppress the sheet's slide-up animation.
private final class CompactSummaryPresenter: ObservableObject {
    @Published var summary: String?
    /// Forwarded into CompactSummarySheet so its "Revert Compact" button can
    /// invoke the view-model's revertCompact() without the sheet importing it.
    var onRevert: (() -> Void)?
}

/// Transparent SwiftUI view that owns the .sheet() modifier.
/// Installed as a child UIHostingController on MessageListViewController,
/// outside any UIHostingConfiguration cell tree.
private struct SheetOverlayView: View {
    @ObservedObject var toolPresenter: ToolSheetPresenter
    @ObservedObject var compactPresenter: CompactSummaryPresenter

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .sheet(item: $toolPresenter.sheetData) { data in
                ToolLiveSheet(
                    toolBlocks: data.toolBlocks,
                    initialIdx: data.toolBlocks.firstIndex(where: { $0.id == data.block.id }) ?? 0,
                    toolSnapshots: data.toolSnapshots,
                    browserPool: data.browserPool
                )
            }
            .onChange(of: toolPresenter.sheetData?.id) { newVal in
                if newVal == nil {
                    toolPresenter.onDismiss?()
                }
            }
            .sheet(isPresented: Binding(
                get: { compactPresenter.summary != nil },
                set: { if !$0 { compactPresenter.summary = nil } }
            )) {
                CompactSummarySheet(
                    summary: compactPresenter.summary ?? "",
                    onRevert: compactPresenter.onRevert
                )
            }
    }
}
