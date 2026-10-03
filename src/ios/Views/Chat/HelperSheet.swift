import SwiftUI

// [T-agent-transcript-page] Full-screen, read-only mirror of an agent's
// (child session's) transcript. Read-only by construction: it hosts the
// message list with every interaction callback nil and mounts NO composer.
// The only action is Stop, which calls the child's own `cancel()`.
//
// This used to be a draggable half-sheet (HelperSheet). The user found it
// easy to dismiss by accident and hard to scroll, so it is now a full-screen
// page (fullScreenCover with its own NavigationStack) that looks like the
// main chat — same CollectionViewMessageListV3 renderer — with a navigation
// bar that says what it is: "Agent · <task>", tier badge, elapsed while
// running, and a Stop button. It is reached from the agent's tool sheet
// ("Open live agent conversation"), from the insurance notification tap and
// from the debug RPC; the tool sheet itself is the same ToolLiveSheet every
// other tool gets.

extension Notification.Name {
    /// Ask the on-screen chat to present the agent transcript page for a
    /// child session. userInfo: `childSessionId`, `title`, optional
    /// `parentSessionId`. Posted by the debug RPC and the insurance
    /// notification tap path.
    static let openHelperSheet = Notification.Name("openHelperSheet")
}

struct HelperSheetTarget: Identifiable, Equatable {
    let id: String          // child session id
    let title: String
}

/// [T-agent-transcript-halfsheet] Presentation for the agent transcript: a
/// resizable sheet stacked ON TOP of whatever opened it, instead of a
/// full-screen cover that required closing the opener first.
///
/// This page was a draggable half-sheet once before and was turned full-screen
/// because it was "easy to dismiss by accident and hard to scroll" (see the
/// header above). Both are addressed explicitly here rather than hoping the
/// defaults behave:
///
/// - Accidental dismiss: `interactiveDismissDisabled()`. Dragging down only
///   shrinks the sheet to its smallest detent; closing takes the back button.
///   A transcript is something you read while it streams, so a stray downward
///   swipe throwing it away was the worst failure.
/// - Scroll vs resize: `presentationContentInteraction(.scrolls)` (iOS 16.4+)
///   makes a swipe inside the message list scroll the list, instead of the
///   default where a swipe at the list's top edge resizes the sheet. Resizing
///   is done from the grabber / nav bar. On iOS 16.0–16.3 the modifier does
///   not exist and the system default applies.
///
/// Three detents so the height is adjustable, opening at `.medium` so the
/// opener stays visible behind it.
struct HelperTranscriptSheetStyle: ViewModifier {
    @State private var detent: PresentationDetent = .medium

    static let detents: Set<PresentationDetent> = [.fraction(0.35), .medium, .large]

    func body(content: Content) -> some View {
        let base = content
            .presentationDetents(Self.detents, selection: $detent)
            .presentationDragIndicator(.visible)
            .interactiveDismissDisabled()
        if #available(iOS 16.4, *) {
            base.presentationContentInteraction(.scrolls)
        } else {
            base
        }
    }
}

extension View {
    func helperTranscriptSheetStyle() -> some View {
        modifier(HelperTranscriptSheetStyle())
    }
}



/// Agent transcript page. Present with `.sheet(item:)` +
/// `.helperTranscriptSheetStyle()` from the view that opened it, so closing it
/// returns there. [T-agent-transcript-halfsheet]
struct HelperTranscriptPage: View {
    let target: HelperSheetTarget
    @Environment(\.dismiss) private var dismiss
    @State private var childVM: AIChatViewModel?
    @State private var startedAt = Date()
    /// [T-agent-transcript-navbar-lost] Mirrors `childVM.isProcessing`.
    ///
    /// `childVM` is @State, not @ObservedObject, so this page's body does NOT
    /// re-evaluate when the child publishes. That used to be masked: the 1 Hz
    /// clock wrote `now` into the body every second, so the running -> finished
    /// transition was picked up incidentally within a second. Now that the
    /// elapsed counter times itself and the clock no longer writes to the body,
    /// the transition has to be tracked explicitly -- the clock samples it and
    /// only writes on a real change, so idle ticks still leave the body (and
    /// the toolbar key) untouched.
    @State private var isRunning = false
    @State private var topSafeAreaInset: CGFloat = 59
    /// [T-agent-model-identity] tier + effective model for the second title
    /// row. Live from the registry while the job runs; recovered from the
    /// parent's persisted payload (or the child's own rows) after a restart.
    @State private var identity: HelperModelIdentity?

    /// [T-helpersheet-deferred-release] Pending render-state release, so a
    /// reopen can cancel it. See `.onDisappear`.
    @State private var releaseTask: Task<Void, Never>?

    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            // [T-agent-transcript-navbar-lost] The ZStack is load-bearing, not
            // cosmetic: everything below — the nav bar style and, critically,
            // the `.background` toolbar host — must attach to a node whose
            // IDENTITY never changes.
            //
            // The if/else inside swaps branch on every single open of this
            // page (ProgressView until `loadSession()` returns, then
            // HelperTranscript). This used to be a bare `Group`, which has no
            // identity of its own -- it takes on its content's -- so the whole
            // modifier chain below, `.toolbar` included, was torn down and
            // re-created with the branch. The ZStack gives that chain one
            // stable identity across the swap.
            ZStack {
                if let vm = childVM {
                    HelperTranscript(vm: vm)
                        .environmentObject(vm)
                        .environment(\.chatSessionId, target.id)
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(ChatColors.background)
            // [T-agent-transcript-navbar] The same nav bar the main chat has:
            // liquid-glass with content behind the bar on iOS 26, opaque
            // ChatColors.background on iOS 16–18 (NavBarStyleModifier), and
            // the 46 pt principal frame so the two-row title is never clipped
            // (NavTitleFrameModifier).
            .modifier(NavBarStyleModifier(topSafeAreaInset: $topSafeAreaInset, measuresSafeArea: false))
            .navigationBarTitleDisplayMode(.inline)
            // [T-agent-transcript-navbar-lost] Pin the bar visible. This page
            // is the ROOT of its NavigationStack, and SwiftUI auto-hides a
            // root's bar when it computes nothing to show there (a pushed
            // destination never auto-hides -- it needs its back button, which
            // is why the main chat is immune). Without this the bar exists
            // only for as long as that automatic computation stays non-empty;
            // the nested tool-sheet host re-bridges an EMPTY state to this same
            // navigation controller when its sheet dismisses, and the bar is
            // hidden with animated:false and never restored.
            .toolbar(.visible, for: .navigationBar)
            .toolbar {
                // [T-agent-transcript-navbar-lost] Declared INLINE, not from a
                // `.background` host.
                //
                // Device evidence (iPhone 8 / iOS 16.1, debug.viewTree): in the
                // failure state this NavigationStack's UILayoutContainerView has
                // NO UIKitNavigationBar subview at all -- not hidden, not with
                // emptied items; the bar view is absent, while the main chat's
                // stack right behind it still has its own. SwiftUI drops the bar
                // when a stack ends up with no toolbar registration.
                //
                // Hosting the toolbar in an `.equatable()`-gated zero-frame
                // `.background` child is what let that happen, and made it
                // permanent: once the registration was dropped, every later pass
                // computed an EQUAL key, so the builder never re-ran and nothing
                // ever re-registered. That is precisely why the bar never came
                // back and the user was trapped on the page.
                //
                // The gate is not needed here anyway. It exists on the main chat
                // to keep an OPEN "..." UIMenu stable during streaming; this page
                // has no menu, and its body no longer re-evaluates per second
                // (the elapsed counter times itself in HelperElapsedLabel). What
                // remains is gated at the CONTENT level via EquatableTitle below,
                // which cannot take the registration down with it.
                ToolbarItem(placement: .principal) {
                    HelperTranscriptTitle(key: titleKey) {
                        titleView.modifier(NavTitleFrameModifier())
                    }
                    .equatable()
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 16, weight: .semibold))
                    }
                    .accessibilityLabel(AppLocalized("Close"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if isRunning {
                        Button {
                            childVM?.cancel()
                        } label: {
                            Image(systemName: "stop.circle")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(.red)
                        }
                        .accessibilityLabel(AppLocalized("Stop"))
                    }
                }
            }
        }
        .task {
            // [T-vmcache-pools] Child pool — this sheet is the one place a
            // child conversation is displayed, and viewing it must not spend
            // the user's normal-session budget.
            let (vm, fresh) = ViewModelCache.shared.getOrCreate(for: target.id, kind: .child)
            if fresh || vm.messages.isEmpty { await vm.loadSession() }
            childVM = vm
            isRunning = vm.isProcessing
            if let job = AgentJobRegistry.shared.list().first(where: { $0.runSessionId == target.id }),
               let s = job.startedAt {
                startedAt = s
            }
            identity = await HelperModelIdentity.recover(childSessionId: target.id)
        }
        .onDisappear {
            // [T-vmcache-release] A child that is no longer on screen keeps its
            // messages (the job may still be running and writing to them) but
            // drops its RENDER state — parsed markdown, laid-out attributed
            // strings and the per-message renderers. Those exist only to put
            // pixels on a screen nobody is looking at; they rebuild lazily if
            // the sheet is reopened.
            //
            // Not an evict: the VM stays cached so a running job keeps writing
            // to the same instance, and nothing about the job is touched.
            //
            // [T-helpersheet-deferred-release] DEFERRED, not immediate. Doing
            // it synchronously here lands inside the sheet's ~350ms dismiss
            // animation, while the view is still on screen: clearing every
            // block's attributed string forces TextKit to re-typeset the whole
            // transcript on the main thread mid-transition, which shows up as
            // dropped frames or a flash of re-laid-out text on the way out.
            //
            // A streaming child is worse still — the release would race the
            // token deltas that are writing those same caches, so the work is
            // immediately redone and the user sees it flicker. So: wait out the
            // animation, and skip entirely while the agent is mid-generation;
            // that child's caches are churning anyway and will be released by
            // the ordinary pool sweep once it finishes.
            scheduleRenderStateRelease()
        }
        .onAppear {
            // Reopened (or re-entered the view tree) before the pending release
            // fired — keep the render state we were about to throw away rather
            // than dropping it and immediately rebuilding it.
            releaseTask?.cancel()
            releaseTask = nil
        }
        .onReceive(clock) { _ in
            let running = childVM?.isProcessing ?? false
            if running != isRunning { isRunning = running }
            // A running agent confirms its model mid-run; pick that up. This
            // is the ONLY thing the page-level clock still drives: it writes
            // `identity` at most once per run, so ticks leave the toolbar key
            // equal and the bar is never re-pushed. The elapsed counter times
            // itself inside HelperElapsedLabel.
            if isRunning, let job = AgentJobRegistry.shared.list().first(where: { $0.runSessionId == target.id }),
               let vm = childVM {
                job.modelIdentity?.merge(vm.lastEffectiveModel)
                if let live = job.modelIdentity, live != identity { identity = live }
            }
        }
    }

    /// [T-helpersheet-deferred-release] Drop this child's render state once the
    /// sheet has really gone, and only if the agent is not mid-generation.
    ///
    /// Deliberately a cancellable `Task` rather than `asyncAfter`: reopening the
    /// sheet must be able to call it off (`.onAppear`), otherwise a user
    /// flicking in and out pays a full re-typeset every time.
    ///
    /// The delay clears iOS's ~350ms sheet dismissal with margin. If the child
    /// is still streaming when the timer fires we skip rather than reschedule:
    /// its caches are being rewritten continuously, so releasing them buys
    /// nothing and races the writer — and the VM is released anyway by the
    /// ordinary pool sweep once the run ends.
    @MainActor
    private func scheduleRenderStateRelease() {
        releaseTask?.cancel()
        guard let vm = childVM else { return }
        releaseTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 700_000_000)   // 0.7s
            guard !Task.isCancelled else { return }
            guard !vm.isProcessing else { return }
            vm.releaseRenderState()
            releaseTask = nil
        }
    }

    private var titleKey: HelperTranscriptTitleKey {
        HelperTranscriptTitleKey(
            title: AgentJobRegistry.childSessionTitle(target.title),
            isRunning: isRunning,
            tier: tierBadge,
            modelLine: identity?.thumbnailLine(max: 28),
            hasEffective: identity?.hasEffective ?? false
        )
    }

    private var tierBadge: String? {
        if let t = HelperBlockInfo.tierLabel(identity, fallback: nil) { return t }
        guard let job = AgentJobRegistry.shared.list().first(where: { $0.runSessionId == target.id }) else { return nil }
        // [T-sub-agents-v1] "pinned" / "inherited" replaced the old tier here.
        return job.modelOrigin
    }

    /// Navigation title: status glyph, "Agent · <task>", tier badge, timer.
    private var titleView: some View {
        VStack(spacing: 1) {
            HStack(spacing: 6) {
                if isRunning {
                    ReattachingSpinner(size: 12, lineWidth: 1.8, color: HelperAccent.color)
                } else {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.system(size: 13))
                }
                Text(AgentJobRegistry.childSessionTitle(target.title))
                    .font(.system(size: 15, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            HStack(spacing: 6) {
                if let tier = tierBadge {
                    Text(tier)
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(ChatColors.secondaryBg)
                        .clipShape(Capsule())
                        .fixedSize()
                }
                // [T-agent-model-identity] Effective model once confirmed,
                // resolved name until then — middle-truncated so it never
                // widens the 46 pt principal frame or hides the timer.
                if let model = identity, let line = model.thumbnailLine(max: 28) {
                    Text(line)
                        .font(.system(size: 11))
                        .foregroundStyle(model.hasEffective ? ChatColors.secondaryText : ChatColors.tertiaryText)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .layoutPriority(-1)
                }
                if isRunning {
                    HelperElapsedLabel(startedAt: startedAt)
                }
            }
        }
    }

}


/// [T-agent-transcript-navbar-lost] The elapsed counter, timing ITSELF.
///
/// This exists so the one value on this nav bar that changes every second does
/// not force the toolbar to be rebuilt every second. It owns its own timer and
/// its own `now`, so a tick re-evaluates this leaf and nothing above it: the
/// page body does not re-run, the title's gate holds, and
/// the ToolbarContent is never re-pushed to the UINavigationItem.
///
/// That re-push is what the bar was being lost to -- dismissing a tool sheet
/// hosted OUTSIDE this NavigationStack (SheetOverlayView, a child VC of the
/// message list) raced the next tick's re-push, and on iOS 16-18 the bar lost
/// that race with nothing left to restore it.
private struct HelperElapsedLabel: View {
    let startedAt: Date

    @State private var now = Date()
    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Text(text)
            .font(.system(size: 11, design: .monospaced))
            .foregroundStyle(ChatColors.secondaryText)
            .fixedSize()
            .onReceive(clock) { now = $0 }
    }

    private var text: String {
        let s = max(0, Int(now.timeIntervalSince(startedAt)))
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// [T-agent-transcript-navbar-lost] The inputs the transcript page's nav TITLE
/// renders. Gating the title's CONTENT is safe; gating the toolbar
/// REGISTRATION was not -- see the `.toolbar` comment above.
private struct HelperTranscriptTitleKey: Equatable {
    let title: String
    let isRunning: Bool
    let tier: String?
    let modelLine: String?
    let hasEffective: Bool
    // The elapsed counter is deliberately ABSENT: it changes every second and
    // would defeat the gate. It times itself in HelperElapsedLabel.
}

/// [T-agent-transcript-navbar-lost] Value-keyed wrapper for the principal
/// title, mirroring EquatableByValue on the main chat. Skipping a re-render of
/// the title cannot remove the navigation bar; skipping a rebuild of the whole
/// ToolbarContent could, and did.
private struct HelperTranscriptTitle<Content: View>: View, Equatable {
    let key: HelperTranscriptTitleKey
    @ViewBuilder let content: () -> Content

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.key == rhs.key }

    var body: some View { content() }
}

/// The read-only transcript: `CollectionViewMessageListV3` with every
/// callback left nil. Wrapped so the page can size it with a GeometryReader
/// without re-creating the platform controller on every tick.
private struct HelperTranscript: View {
    @ObservedObject var vm: AIChatViewModel

    var body: some View {
        GeometryReader { geo in
            CollectionViewMessageListV3(
                vm: vm,
                inputFocused: false,
                onBrowserTakeover: { vm.browserTakeoverActive = true },
                onTakeoverDone: { vm.resumeFromBrowserTakeover() },
                maxContentWidth: geo.size.width,
                floatingBarHeight: 0,
                inputBarHeight: 0
            )
        }
    }
}
