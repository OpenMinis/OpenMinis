import AVFoundation
import AVKit
import Combine
import GameController
import PhotosUI
import QuickLook
import QuickLook
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import WebKit

private let minisLogger = AppLogger(category: "MinisURL")

/// Wrapper that resolves an AIChatViewModel from the cache.
/// Used as @StateObject so SwiftUI creates it once per AIChatView lifetime,
/// but the underlying `vm` is the cached (possibly still-running) instance.
@MainActor
private final class CachedViewModel: ObservableObject {
    let vm: AIChatViewModel
    /// True if this ViewModel was freshly created (not found in cache).
    let isNew: Bool
    /// Forwards the VM's objectWillChange to this wrapper so SwiftUI
    /// observes all @Published changes through the @StateObject.
    private var cancellable: AnyCancellable?

    init(sessionId: String?) {
        if let sessionId {
            let (cached, isNew) = ViewModelCache.shared.getOrCreate(for: sessionId)
            self.vm = cached
            self.isNew = isNew
            // If the cached VM is stale (iCloud merged new MessageV2 rows
            // for this session while we were on a DIFFERENT session and
            // the foreground reload signal never reached us), trigger a
            // fresh loadSession so the bubble list matches SQLite
            // (T-inbound-message-cached-vm-stale). Cache HIT only —
            // `isNew` already loads from scratch.
            if !isNew, ViewModelCache.shared.consumeStaleFlag(sessionId: sessionId) {
                minisLogger.info("🔑DRAFT CachedViewModel.init sessionId=\(sessionId) — STALE flag consumed, scheduling reload")
                Task { @MainActor [weak vm = cached] in
                    await vm?.loadSession()
                }
            }
            minisLogger.info("🔑DRAFT CachedViewModel.init sessionId=\(sessionId) vm=\(cached.vmInstanceId) isNew=\(isNew)")
        } else {
            let draft = ViewModelCache.shared.createDraft()
            self.vm = draft
            self.isNew = true
            minisLogger.info("🔑DRAFT CachedViewModel.init DRAFT vm=\(draft.vmInstanceId)")
        }
        // Forward VM's objectWillChange → this wrapper's objectWillChange
        // so that SwiftUI re-renders when any @Published property on `vm` changes.
        cancellable = vm.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }

        // iOS 16 fix: When NavigationStack recreates the @StateObject, the new
        // CachedViewModel may wrap a VM that already has messages loaded. Force
        // a re-render so SwiftUI picks up the current state, since objectWillChange
        // events between the old and new subscription may have been lost.
        if !isNew {
            DispatchQueue.main.async { [weak self] in
                self?.objectWillChange.send()
            }
        }
    }
}

// MARK: - Color Palette (clean light theme)

enum ChatColors {
    static let background = Color(UIColor.systemBackground)
    static let secondaryBg = Color(UIColor.secondarySystemBackground)
    static let inputIconBg = Color(UIColor.secondarySystemBackground)
    static let inputIconBorder = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.35, alpha: 1) : UIColor(white: 0, alpha: 0) })
    static let inputBg = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.12, alpha: 1) : .white })
    static let inputBorder = Color(UIColor.separator)
    static let primaryText = Color(UIColor.label)
    static let secondaryText = Color(UIColor.secondaryLabel)
    static let tertiaryText = Color(UIColor.tertiaryLabel)
    static let userBubble = Color(UIColor.tertiarySystemFill)
    static let toolBg = Color(UIColor.tertiarySystemGroupedBackground)
    static let toolBorder = Color(UIColor.separator).opacity(0.5)
    static let accent = Color(UIColor.label)
    static let sendButton = Color(UIColor.label)
    static let sendButtonDisabled = Color(UIColor.quaternaryLabel)
}

// MARK: - System Resource Monitor

class SystemResourceMonitor: ObservableObject {
    @Published var cpuUsage: Double = 0     // 0-100
    @Published var memUsed: UInt64 = 0      // bytes
    @Published var memTotal: UInt64 = 0     // bytes
    private var timer: Timer?
    private var prevCPUTicks: (user: UInt32, system: UInt32, idle: UInt32, nice: UInt32)?

    func start() {
        sampleCPU() // prime the previous ticks
        updateMemory()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            DispatchQueue.main.async {
                self?.sampleCPU()
                self?.updateMemory()
            }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func sampleCPU() {
        var loadInfo = host_cpu_load_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<host_cpu_load_info_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &loadInfo) { ptr in
            ptr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { intPtr in
                host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, intPtr, &count)
            }
        }
        guard result == KERN_SUCCESS else { return }

        let cur = (user: loadInfo.cpu_ticks.0, system: loadInfo.cpu_ticks.1, idle: loadInfo.cpu_ticks.2, nice: loadInfo.cpu_ticks.3)
        if let prev = prevCPUTicks {
            let userD = Double(cur.user &- prev.user)
            let sysD = Double(cur.system &- prev.system)
            let idleD = Double(cur.idle &- prev.idle)
            let niceD = Double(cur.nice &- prev.nice)
            let total = userD + sysD + idleD + niceD
            if total > 0 {
                cpuUsage = ((userD + sysD + niceD) / total) * 100
            }
        }
        prevCPUTicks = cur
    }

    private func updateMemory() {
        // App memory: internal + compressed (excludes external mappings like mmap/IOKit)
        // This matches Xcode's Memory Gauge more closely than phys_footprint
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { ptr in
            ptr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { intPtr in
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), intPtr, &count)
            }
        }
        if result == KERN_SUCCESS {
            memUsed = UInt64(info.internal) + UInt64(info.compressed)
        }
        memTotal = ProcessInfo.processInfo.physicalMemory
    }

    var formattedCPU: String {
        String(format: "CPU %2.0f%%", cpuUsage)
    }

    func formattedMem(compact: Bool = false) -> String {
        let usedGB = Double(memUsed) / 1_073_741_824
        if compact {
            return String(format: "MEM %.1fG", usedGB)
        }
        let totalGB = Double(memTotal) / 1_073_741_824
        return String(format: "MEM %.1f/%.1f GB", usedGB, totalGB)
    }
}

// MARK: - Main View

struct AIChatView: View {
    var sessionId: String? = nil
    /// The draft ID from the parent (e.g. "__new__<UUID>") for notification correlation.
    var draftId: String? = nil
    /// If non-nil, this is a read-only view of a remote device's session.
    var remoteDeviceId: String? = nil
    /// Model group to bind when creating a new session (from long-press FAB).
    var initialGroupId: String? = nil
    @EnvironmentObject var shareCoordinator: ShareCoordinator
    @StateObject private var cached: CachedViewModel

    /// The actual ViewModel — always derived from the @StateObject to avoid
    /// the @ObservedObject re-init-on-body-recompute problem.
    /// CachedViewModel is ObservableObject and forwards objectWillChange from its vm,
    /// so SwiftUI observes changes through `cached`.
    private var vm: AIChatViewModel { cached.vm }

    /// Whether this view is in read-only mode (remote session).
    private var isReadOnly: Bool { remoteDeviceId != nil }

    /// Binding to vm.inputText, routed through the stable @StateObject.
    private var inputTextBinding: Binding<String> {
        Binding(
            get: { cached.vm.inputText },
            set: { cached.vm.inputText = $0 }
        )
    }

    init(sessionId: String? = nil, draftId: String? = nil, remoteDeviceId: String? = nil, initialGroupId: String? = nil) {
        self.sessionId = sessionId
        self.draftId = draftId
        self.remoteDeviceId = remoteDeviceId
        self.initialGroupId = initialGroupId
        // [T-ios-aichatview-eager-cachedvm] Build the CachedViewModel INSIDE the
        // StateObject autoclosure so SwiftUI only constructs it the first time
        // this view's StateObject is created — NOT on every struct re-init.
        // ContentView re-evaluates its body (and thus re-inits AIChatView)
        // whenever ANYTHING it observes changes — a sync recentFetchTimer fire,
        // a scenePhase.active transition, an unrelated @Published — none of
        // which should rebuild the view model. The previous `let c =
        // CachedViewModel(...)` ran eagerly before the autoclosure, so every
        // such re-init did a ViewModelCache lookup + Combine sink + an
        // objectWillChange.send (which itself re-triggered updateUIViewController
        // / applySubViewportCompensation). The MLTRACE idle-window capture showed
        // AIChatView.init firing 8× with no user navigation, each tracking a
        // recentFetchTimer / scenePhase event. Deferring construction makes the
        // re-init a no-op when the StateObject already exists. CachedViewModel.init
        // logs its own creation (🔑DRAFT), so the redundant per-init [SessionLoad]
        // line is dropped.
        _cached = StateObject(wrappedValue: CachedViewModel(sessionId: sessionId))
        AIChatViewModel.onAppearTimestamp = CFAbsoluteTimeGetCurrent()
    }
    @StateObject private var oauth = ClaudeOAuthManager.shared
    @StateObject private var geminiOAuth = GeminiOAuthManager.shared
    // Face ID lock state moved into `SessionLockGateOverlay` (separate
    // struct) — keeping the @State / @ObservedObject here pushed the
    // body's generic-type depth past iOS 26's runtime metadata budget
    // and triggered an EXC_BAD_ACCESS stack-overflow during
    // `__swift_instantiateConcreteTypeFromMangledNameV2`.
    @StateObject private var speechManager = SpeechRecognitionManager.shared
    @ObservedObject private var mentionIndex = FileMentionIndex.shared
    @ObservedObject private var configStore = ProviderConfigStore.shared
    @ObservedObject private var fontSettings = FontSettings.shared
    @ObservedObject private var deepLink = DeepLinkCoordinator.shared
    @Environment(\.dismiss) private var dismiss
    @State private var inputFocused: Bool = false
    @State private var inputHasSelection: Bool = false
    @State private var inputIsScrollable: Bool = false
    /// [T-ios-composer-swipe-send-at-bottom] True when the composer's text is
    /// scrolled to its end (or is short enough not to scroll). Combined with
    /// `inputIsScrollable` to decide whether a vertical drag belongs to the
    /// text's inner scroll or to swipe-to-send. Starts `true` because an empty
    /// composer has nothing to scroll.
    @State private var inputAtScrollBottom: Bool = true
    @State private var inputBarHeight: CGFloat = 0
    @State private var inputBarHeightDebounce: Task<Void, Never>?
    /// [T-voice-inputbar-anim-tail] The most recent ON-SCREEN composer height
    /// reported by onGeometryChange. The debounced commit reads THIS instead of
    /// the height captured when its 300ms timer was armed: the panel's
    /// expand/collapse + transcript-band animations are 280ms, so the value that
    /// arms the timer is routinely a mid-animation sample, and committing it
    /// froze the bottom inset at a transitional height (log 07-14 15:34:
    /// settled=312.67 / 363 while the panel was really 232 → 210). Writing every
    /// callback here and committing the freshest one makes the debounce a
    /// genuine trailing edge.
    @State private var latestInputBarFrameH: CGFloat = 0
    /// [T-voice-inputbar-collapse-selfheal] Liveness signal for the composer's
    /// geometry reporting: bumped on EVERY onGeometryChange callback (including
    /// off-screen ones, which still prove the host is laying out), plus the wall
    /// clock of the last one. The foreground health probe compares the tick
    /// across a 900ms window to tell "the composer woke up" apart from "the host
    /// is silent and the bottom of the screen is blank" — the 2026-08-18 01:29
    /// failure, where the last callback preceded the blank bar by minutes.
    @State private var inputBarGeometryTick: Int = 0
    @State private var inputBarLastGeometryAt: CFAbsoluteTime?
    @State private var inputBarHealthProbe: Task<Void, Never>?
    /// [T-voice-inputbar-stale-height] False until the FIRST non-zero
    /// inputBarHeight lands. The first write (session open / initial composer
    /// layout) is applied IMMEDIATELY (leading edge) so the message list computes
    /// the correct bottom inset on its very first pass and snaps straight to the
    /// bottom — no visible "scroll, pause, scroll" from a late debounced correction
    /// (regression from pure trailing-edge debounce). Only SUBSEQUENT height
    /// changes (voice compact↔expanded transitions that sample mid-animation
    /// frames) go through the 300ms trailing debounce that fixes the stuck
    /// stale-height voice-panel-blank bug.
    @State private var didSeedInputBarHeight = false
    /// Swipe-up-to-send drag progress in 0...1. Drives the floating send-arrow
    /// hint opacity / scale and acts as the threshold check on gesture end.
    /// Only updated while the input field has non-empty text.
    @State private var sendSwipeProgress: CGFloat = 0
    /// Live finger location within the input-bar coordinate space while a
    /// swipe-up-to-send drag is in progress. Drives the floating send-arrow
    /// hint position so it visually follows the user's finger.
    @State private var sendSwipeLocation: CGPoint = .zero
    /// [T-ios-composer-swipe-send-at-bottom] Drag translation at the moment
    /// swipe-to-send took over from the composer's inner scroll, so send
    /// progress is measured from THERE rather than from the drag's origin.
    ///
    /// Without this, one continuous drag that scrolls a long value to its end
    /// would hand off to send with the whole scroll distance already banked in
    /// `value.translation.height` — instantly past the 120pt threshold, sending
    /// a message the user never asked to send. Reset to nil whenever the gesture
    /// ends or the send branch is not active, so each hand-off re-anchors.
    @State private var sendSwipeAnchor: CGFloat?
    /// Vertical drag distance (points) above which a swipe-up release fires
    /// `performSend()`. Below this, the hint animates away with no action.
    private static let kSendSwipeThreshold: CGFloat = 120
    /// Progress fraction (0...1) at which the release-to-send threshold
    /// engages: haptic tick fires, capsule is fully opaque, and lifting the
    /// finger sends. Set below 1.0 so users get earlier confirmation that
    /// they've reached the trigger zone.
    private static let kSendSwipeArmFraction: CGFloat = 0.8

    /// [T-ios-composer-swipe-send-at-bottom] Whether a vertical drag on the
    /// input bar belongs to swipe-to-send rather than the composer text's own
    /// inner scroll.
    ///
    /// Previously this was just `!inputIsScrollable`: the moment the text
    /// overflowed, swipe-to-send was disabled outright and every vertical drag
    /// went to the scroll view — even once the user had already read to the end
    /// and further dragging could not move the content. Now the scroll gets
    /// first claim only while it still has somewhere to go: the user scrolls the
    /// long text through to the bottom, and from there another upward drag
    /// arms the send, matching how a short (non-overflowing) value behaves.
    ///
    /// `inputAtScrollBottom` is trivially true when the text does not overflow,
    /// so the short-text path is unchanged by construction.
    private var swipeToSendAllowed: Bool {
        !inputIsScrollable || inputAtScrollBottom
    }

    @State private var floatingBarHeight: CGFloat = 0
    @State private var showFileBrowser = false
    // [T-browser-download-ux-v2] Downloads panel + "Show in Files" locate target.
    @State private var showDownloadsPanel = false
    @State private var locateDownloadTarget: DownloadLocateTarget?
    /// Total session count snapshot (persisted in DB) — used to decide
    /// whether to show the New-Chat onboarding directory grid. Only the
    /// "first-time, no sessions yet" case shows it; returning users with
    /// any prior chat skip the grid even when starting a new draft.
    /// Populated lazily on first appearance.
    @State private var totalSessionCount: Int? = nil
    @State private var showBrowserSheet = false
    @State private var showTerminal = false
    @State private var terminalInitCommand: String?
    @State private var showAttachmentMenu = false
    @State private var isDropTargeted = false
    @State private var showCamera = false
    @State private var showPhotoPicker = false
    @State private var showDocumentPicker = false
    @State private var showMoveToSheet = false
    @State private var showClearChatConfirm = false
    /// [T-new-chat-menu-entry] Confirmation gate for "New Chat" from the "…"
    /// menu while the current session is still streaming: stopping the task is
    /// destructive enough to warrant an explicit confirm.
    @State private var showNewChatStopConfirm = false
    @State private var showForcePullConfirm = false
    // [inline-voice] Inline voice input mode: the composer's text area becomes a
    // VAD waveform + live transcript when active. Backed by a shared in-memory
    // preference so the voice/text choice carries across sessions (reset on cold
    // start), like an input method's current state.
    @ObservedObject private var voiceMode = VoiceModePreference.shared
    private var voiceInputActive: Bool {
        get { voiceMode.isVoiceActive }
        nonmutating set { voiceMode.isVoiceActive = newValue }
    }
    @StateObject private var voiceVM = VoiceInputViewModel()
    /// Global read-replies state — observed so the voice-mode "Read replies" toggle
    /// reflects enabled/muted in lockstep with the global voice-output capsule.
    @ObservedObject private var voiceOutput = VoiceOutputState.shared
    @AppStorage("cloudSync.v2.enabled") private var iCloudSyncEnabled: Bool = false
    /// [T-codex-fast-mode] Codex Fast Mode toggle ("..." menu, OpenAI OAuth
    /// only). Same key OpenAIProvider reads at request-build time; @AppStorage
    /// so the toolbar (menu checkmark + nav bolt badge) re-renders on flip.
    @AppStorage(OpenAIProvider.fastModeDefaultsKey) private var codexFastModeEnabled: Bool = false
    // Observe the in-app language so the view's body re-runs the moment
    // the user switches it. Without this, parts of the chat surface that
    // hold UIKit-bridged strings (PastableTextView placeholder, etc.)
    // keep their captured-at-makeUIView text until the next unrelated
    // state change forces a re-evaluate. The body re-eval propagates the
    // new `String(localized:)` result through to `updateUIView`, which
    // already syncs the placeholder UILabel via its viewWithTag(999) path.
    @AppStorage("appLanguage") private var observedAppLanguage: String = ""
    @State private var isForcePulling = false
    @State private var forcePullToast: String?
    @State private var compactConfirmMessageId: UUID?
    /// True while unsent share-extension content is in the input bar.
    @State private var hasInjectedShareContent = false
    @State private var showModelPicker = false
    @State private var showThinkingLevelSheet = false
    @State private var showSessionSkills = false
    @State private var showSessionMCPs = false
    @State private var showSessionMemory = false
    @State private var showEnhancedCacheAlert = false
    @State private var showTokenUsage = false
    // [T-ios-json-open-provider-import-prompt] A shared/opened JSON file that
    // looks like a Provider export (providerType + credentialType + models)
    // prompts the user to choose: import as a provider, or add as a chat
    // attachment. Captured here while the confirmationDialog is up — the JSON
    // string for import + a temp-copied file URL for the attachment path
    // (the original shared file is cleaned up right after ingestion).
    @State private var pendingProviderImport: PendingProviderImport?
    @State private var providerImportResult: String?
    @State private var screenshotPreview: ChatScreenshotPreview?
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var attachmentGridHeight: CGFloat = 0
    @State private var transcriptHeight: CGFloat = 0
    /// Tracks how much of recognizedText has already been appended to inputText.
    @State private var lastRecognizedLength: Int = 0
    /// Intent-based auto-scroll: stays true until the user actively scrolls up
    @State private var topSafeAreaInset: CGFloat = 59
    @Environment(\.horizontalSizeClass) private var hSizeClass
    @Environment(\.scenePhase) private var scenePhase

    // MARK: - [T-ipad-composer-resize] Draggable composer height (iPad only)

    /// Persisted composer height as a FRACTION of screen height (e.g. 0.5).
    /// 0 = never resized, so the composer keeps its natural content-driven
    /// height. Stored as a fraction rather than points so the preference
    /// survives rotation, Split View resizes and a move to another iPad.
    @AppStorage("chat.composer.heightFraction") private var composerHeightFraction: Double = 0
    /// Live delta while a drag is in flight, in POINTS. Kept separate from the
    /// persisted fraction so an in-progress drag never writes to defaults and a
    /// cancelled gesture leaves no trace.
    @State private var composerDragOffset: CGFloat = 0

    /// The drag handle is iPad-only: on iPhone the composer is already close to
    /// the keyboard and a 50%-height composer would leave no readable
    /// transcript, so the affordance is not offered there.
    private var composerResizeEnabled: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    /// Upper bound for the composer: half the screen. Beyond this the chat
    /// transcript stops being usable, which is the point of the feature's cap.
    private var composerMaxHeight: CGFloat { UIScreen.main.bounds.height * 0.5 }

    /// The resolved text-view height, or nil to let the composer size itself.
    ///
    /// nil is the untouched default — the composer grows with its content up to
    /// the built-in ~120pt cap, exactly as before this feature. Once the user
    /// drags, the stored fraction becomes a FLOOR that the text view is pinned
    /// to, clamped to [default, 50% of screen].
    private var composerTextHeight: CGFloat? {
        guard composerResizeEnabled else { return nil }
        let base = composerHeightFraction > 0
            ? UIScreen.main.bounds.height * composerHeightFraction
            : 0
        // A drag in flight applies even before anything is persisted, so the
        // very first drag has something to move away from.
        guard base > 0 || composerDragOffset != 0 else { return nil }
        let start = base > 0 ? base : Self.composerDefaultHeight
        return min(max(start + composerDragOffset, Self.composerDefaultHeight), composerMaxHeight)
    }

    /// The composer's natural cap when it has never been resized. Also the
    /// LOWER bound for a drag, per the requirement that the default height is
    /// the minimum.
    static var composerDefaultHeight: CGFloat { PastableUITextView.defaultMaxHeight }
    /// Face ID lock state — observed so the navbar title can blur in
    /// lockstep with the chat-body SessionLockGateOverlay (both read
    /// `lockStore.isVisuallyLocked(sid)` rather than recomputing the
    /// 4-guard expression independently).
    @ObservedObject private var lockStore = SessionLockStore.shared
    /// Opacity for the fallback capsule highlight (animated 3× pulse on model switch).
    @State private var fallbackPulseOpacity: Double = 0

    /// Snapshot of the current session's title + category, refreshed when the
    /// session updates (e.g. auto-generated title arrives). Nil until first load.
    @State private var titlePillSession: ChatSession?
    /// Session being edited via the title-pill tap. Drives the SessionEditSheet.
    @State private var titlePillEditSession: ChatSession?
    /// Default chat title for sessions without a generated title. Sourced
    /// from SOUL.md (`name`), falls back to "Minis". Refreshed on .soulMdChanged.
    @State private var soulName: String = SoulStore.cachedMetadata.name.isEmpty
        ? "Minis" : SoulStore.cachedMetadata.name

    /// True when any sheet or fullScreenCover is presented (suppress auto-focus to avoid keyboard bugs).
    private var hasOverlayPresented: Bool {
        showFileBrowser || showBrowserSheet || showTerminal || showCamera
            || showPhotoPicker || showDocumentPicker || showModelPicker
    }

    /// Tracks whether this ChatView is the currently visible screen.
    /// Set to false in onDisappear (e.g. when another page is pushed),
    /// used to suppress auto-focus so the keyboard doesn't pop up behind other pages.
    @State private var isChatViewVisible: Bool = false


    // minis:// link preview sheet state — hoisted from MinisOpenURLHandler so
    // sheet presentation originates from a stable window-hierarchy root and
    // plays its slide-up transition correctly. See MinisOpenURLHandler for rationale.
    @State private var previewImageFile: URL?
    @State private var imageGallery: GalleryPresentation?
    @State private var previewVideoFile: URL?
    @State private var previewAudioFile: URL?
    @State private var previewTextFile: URL?
    @State private var previewMarkdownFile: URL?
    @State private var previewHTMLFile: URL?
    @State private var previewDocumentFile: URL?
    @State private var shareFile: URL?
    @State private var safariURL: URL?
    @State private var fullBrowserURL: URL?
    @State private var fullBrowserIsLocal = false
    /// Filename to show in the "file missing" alert. Set by `handleMinisURLTap`
    /// when `resolveMinisFileURL` returns nil for a tapped `minis://` link
    /// (file was deleted, the session was pruned, or iCloud hasn't synced yet).
    @State private var missingMinisFileName: String?

    var body: some View {
        ZStack {
            // Messages — floating tool preview overlaid at bottom
            messagesArea
                .safeAreaInset(edge: .top, spacing: 0) {
                    // Error banner
                    if let error = vm.errorMessage {
                        errorBanner(error)
                    }
                    // Transient notice (e.g. request-level image budget
                    // elision). Auto-dismisses after ~4s.
                    if let notice = vm.transientNotice {
                        Text(notice)
                            .font(.system(size: 12))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.black.opacity(0.75))
                            .onAppear {
                                let captured = notice
                                DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                                    if vm.transientNotice == captured {
                                        vm.transientNotice = nil
                                    }
                                }
                            }
                    }
                }
                .overlay(alignment: .bottom) {
                    // Tool preview + input bar stacked at the bottom.
                    // Both overlay on top of the message list for immersive scrolling.
                    //
                    // The slash / mention popup is attached to THIS stack as
                    // a `.overlay(alignment: .bottom)` on a placeholder
                    // sitting just above the input bar — that way SwiftUI's
                    // own layout pins the popup's bottom edge to the input
                    // bar's top edge directly, with no `inputBarHeight`
                    // @State round-trip and no screen-bottom math. The
                    // popup follows the input bar automatically when
                    // attachments / waveform expand it, when the keyboard
                    // animates, or when safe-area insets change.
                    //
                    // Popup uses the EXACT same constraint toolbar uses
                    // with inputBar: bottom edge of popup = top edge of
                    // inputBar, with the SAME gap (i.e. whatever VStack
                    // spacing produces between toolbar and inputBar —
                    // which is 0 here, the visual gap toolbar exhibits
                    // comes from its own internal padding).
                    //
                    // To achieve this without re-implementing layout
                    // math: popup is an `.overlay(alignment: .top)` on
                    // the inputBar with `alignmentGuide(.top){$0[.bottom]}`
                    // — this anchors popup's BOTTOM to inputBar's TOP.
                    // The popup card carries its OWN bottom padding
                    // equal to what the toolbar's own internal bottom
                    // spacing produces, so the visual gap is identical
                    // whether toolbar is visible or not. Popup ALWAYS
                    // sits flush with inputBar top, covering the toolbar
                    // when it's also there.
                    // Wrap input stack in a ZStack so the popup can have a
                    // sibling-level frame (full ZStack size) with proper hit
                    // testing — `.overlay(alignment: .top)` on inputBar was
                    // failing to receive touches for the popup card because
                    // SwiftUI doesn't hit-test overlay content drawn outside
                    // the host's own frame.
                    //
                    // Layout structure (z-order bottom→top inside ZStack):
                    //   1. VStack { toolbar; inputBar } — anchored .bottom
                    //   2. Popup with alignmentGuide pinning its BOTTOM
                    //      edge to inputBar's TOP edge — also anchored
                    //      .bottom, but `alignmentGuide(.bottom) { d in
                    //      d[.bottom] + inputBarHeight }` would require
                    //      reading inputBar height again. Instead we use
                    //      `.padding(.bottom, inputBarHeight)` so the
                    //      popup's bottom edge sits exactly at inputBar's
                    //      top edge. inputBarHeight here is just the input
                    //      bar (NOT including toolbar) so popup covers
                    //      toolbar when both visible.
                    ZStack(alignment: .bottom) {
                        // Tap-outside catcher placed UNDER the popup (declared
                        // first → lower z-order). When the popup is visible,
                        // the catcher fills the ZStack and absorbs taps that
                        // land outside the popup card. Taps on the popup
                        // itself naturally fall through to the popup view
                        // because it sits on top in z-order.
                        if vm.showSlashMenu || vm.showMentionMenu {
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    if vm.showSlashMenu { vm.dismissSlashMenu() }
                                    else if vm.showMentionMenu { vm.dismissMentionMenu() }
                                }
                        }
                        VStack(spacing: 0) {
                            floatingToolPreview
                                .shadow(color: Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0, alpha: 0.25) : UIColor(white: 0, alpha: 0) }), radius: 6, x: 0, y: 4)
                            #if DEBUG
                            if isReadOnly {
                                forkBanner
                            } else {
                                inputBar
                            }
                            #else
                            inputBar
                            #endif
                        }
                        // Register the composer (tool preview + input bar) as a
                        // region the global speech capsule must not cover.
                        .capsuleProtectedFrame("inputBar")
                        inputPopupOverlay
                            .padding(.bottom, inputBarHeight)
                    }
                }
                // Collapse the expanded speech player on a tap anywhere in the chat
                // area. Attached as a SIMULTANEOUS TapGesture directly on the content
                // (no full-screen hit-test overlay, which blocked scrolling) so a tap
                // collapses the player while scroll/drag still work normally. The tap
                // only acts when the player is expanded; otherwise it's a no-op.
                .simultaneousGesture(
                    TapGesture().onEnded {
                        if vm.speakEnabled && vm.speechPlayerExpanded {
                            withAnimation(.easeInOut(duration: 0.16)) {
                                vm.speechPlayerExpanded = false
                            }
                        }
                    }
                )
                .overlay(alignment: .bottomTrailing) {
                    floatingSpeechButton
                }
                .overlay {
                    // Session-loading indicator, two flavors by scenario:
                    //  • ENTERING the session (first load, nothing rendered yet)
                    //    → centered soft loading card with a gentle fade+scale
                    //    exit, replacing the abrupt bare system spinner.
                    //  • Any LATER reload (iCloud sync, compaction, stale-cache
                    //    refresh) while content is on screen → keep the small
                    //    unobtrusive spinner; the big card must never cover
                    //    content the user is already reading.
                    ZStack {
                        // Pre-load window: isLoadingSession only flips once
                        // loadSession() actually begins (onAppear → Task →
                        // repairSessionIfNeeded → loadSession), so gating the
                        // card on it alone left a blank screen for that gap.
                        // Treat "real session, nothing loaded yet" as loading
                        // from the very first frame of the push.
                        let enteringUnloaded = sessionId != nil
                            && vm.messages.isEmpty
                            && !vm.hasCompletedInitialLoad
                        if (vm.isLoadingSession || enteringUnloaded) && vm.messages.isEmpty {
                            // Entering: nothing rendered yet — show the dots.
                            // In-place reloads with content on screen (iCloud
                            // sync fetch → reloadMessagesFromDB → loadSession
                            // right after entry is the common case) show NO
                            // overlay at all: the content stays visible and
                            // interactive, and a centered gray "…" right after
                            // the blue entry dots read as a glitch, not
                            // feedback (user report 2026-07-06).
                            SessionLoadingCard()
                                .transition(.opacity.combined(with: .scale(scale: 0.92)))
                                .accessibilityIdentifier("sessionLoadingCard")
                        }
                    }
                    .animation(.easeOut(duration: 0.28), value: vm.isLoadingSession)
                    .allowsHitTesting(false)
                }
                .overlay {
                    SessionLockGateOverlay(sessionId: vm.sessionId)
                }
                .onChange(of: vm.isLoadingSession) { newValue in
                    // [SpinnerTrace] Every transition of the
                    // session-loading overlay logged, so we can confirm
                    // whether a center-spinner report corresponds to
                    // this overlay or some other ProgressView in the
                    // chat.
                    minisLogger.warning("[SpinnerTrace] isLoadingSession → \(newValue) sid=\(vm.sessionId?.prefix(8) ?? "nil") msgs=\(vm.messages.count)")
                }

            // Full-screen kernel boot overlay
            kernelBootOverlay
        }
        .background(ChatColors.background)
        .onDrop(of: [.image, .movie, .fileURL, .data], isTargeted: $isDropTargeted) { providers in
            handleDropProviders(providers)
            return true
        }
        .overlay {
            if isDropTargeted {
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [8]))
                    .background(Color.accentColor.opacity(0.08).clipShape(RoundedRectangle(cornerRadius: 16)))
                    .padding(8)
                    .allowsHitTesting(false)
            }
        }
        .environment(\.chatSessionId, vm.sessionId)
        .modifier(NavBarStyleModifier(topSafeAreaInset: $topSafeAreaInset))
        .navigationBarTitleDisplayMode(.inline)
        // [T-ios-navbar-toolbar-host] The ENTIRE toolbar now lives inside an
        // equatable-gated host child. Root cause of the mid-streaming "..."
        // menu refresh (4th attempt, this one from instrumentation): with
        // .toolbar attached on this (per-token re-evaluated) chain, the
        // ToolbarContent builder itself re-ran on EVERY streaming tick
        // (NavbarEval toolbarPass=34/stream) and handed the bridge a brand-new
        // ToolbarContent value each time — which it re-pushed to the
        // UINavigationItem, refreshing the OPEN UIMenu, even while both inner
        // Equatable gates provably held (titleEval=3, menuEval=2). Hosting the
        // toolbar in a child whose body is gated on the displayed inputs means
        // the builder simply never re-runs during a stream: no new
        // ToolbarContent, nothing to re-push, and the native SwiftUI Menu
        // (with the system's round Liquid-Glass chrome) can stay.
        // .equatable() is MANDATORY here, not an optimization hint: device
        // instrumentation (2026-07-17 23:53, == FALSE count 0 while
        // hostEval == toolbarPass) proved SwiftUI does NOT consult a plain
        // Equatable conformance for this generic closure-holding view in the
        // .background position — the gate silently never engaged. EquatableView
        // wrapping is the only guaranteed path to ==.
        .background(
            ChatToolbarHost(key: chatToolbarKey, title: {
                chatNavPrincipalContent
            }, trailing: {
                trailingMenu
            })
            .equatable()
        )
        .sheet(item: $titlePillEditSession) { session in
            SessionEditSheet(session: session) { newTitle, newCategory in
                Task {
                    await ChatStore.shared.updateSessionTitle(session.id, title: newTitle, category: newCategory)
                    refreshTitlePillSession()
                }
                titlePillEditSession = nil
            }
        }
        .alert(AppLocalized("Force Pull Messages"), isPresented: $showForcePullConfirm) {
            Button(AppLocalized("Pull from iCloud"), role: .destructive) {
                runForcePull()
            }
            Button(AppLocalized("Cancel"), role: .cancel) {}
        } message: {
            Text(AppLocalized("This will delete all local messages for this chat and re-download them from iCloud. Local changes that haven't synced yet will be lost. Continue?"))
        }
        // [T-ios-json-open-provider-import-prompt] Shared/opened Provider-export
        // JSON: let the user choose import-as-provider vs add-as-attachment.
        // Extracted into a single modifier so the body's type-check stays cheap.
        .modifier(ProviderImportPromptModifier(
            pending: $pendingProviderImport,
            result: $providerImportResult,
            onImport: { json in configStore.importInstanceJSON(json) },
            onAttach: { url in vm.addFileAttachment(from: url) }
        ))
        .overlay(alignment: .top) {
            if let msg = forcePullToast {
                Text(msg)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.accentColor, in: Capsule())
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.3), value: forcePullToast)
        // [T-browser-download-ux-v2] Downloads panel opened from the floating
        // download button (mounted next to the scroll buttons, see
        // downloadFloatingButton). Completed rows jump to the file browser
        // located at the downloaded file in the session workspace.
        .sheet(isPresented: $showDownloadsPanel) {
            BrowserDownloadPanelSheet(sessionId: vm.sessionId ?? "") { filename in
                showDownloadsPanel = false
                locateDownloadTarget = DownloadLocateTarget(filename: filename)
            }
        }
        .sheet(item: $locateDownloadTarget) { target in
            if let sid = vm.sessionId {
                NavigationStack {
                    FileBrowserView(
                        rootPath: AIChatViewModel.minisWorkspacePersistentDir(for: sid),
                        rootLabel: "/var/minis/workspace",
                        highlightFileName: target.filename
                    )
                }
            }
        }
        .alert("Enhanced Cache", isPresented: $showEnhancedCacheAlert) {
            Button("Enable") {
                UserDefaults.standard.set(true, forKey: "enhancedCacheConfirmed")
                cached.vm.enhancedCacheEnabled = true
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Enhanced cache extends the cache TTL from 5 minutes to 1 hour. Cache writes cost more (2x vs 1.25x base price), but cache reads remain cheap (0.1x). Recommended for long coding sessions where request gaps may exceed 5 minutes.")
        }
        .alert(AppLocalized("Context Near Capacity"), isPresented: Binding(get: { vm.showCompactBeforeSendPrompt }, set: { vm.showCompactBeforeSendPrompt = $0 })) {
            Button(AppLocalized("Compact & Send")) {
                vm.compactAndSend()
            }
            // [T-chat-auto-compact-opt-in] One-tap opt-in: compact now AND
            // remember (globally, UserDefaults "autoCompactOnThreshold") to
            // auto-compact without prompting whenever the threshold fires in
            // future conversations.
            Button(AppLocalized("Compact & Enable Auto-Compact")) {
                vm.autoCompactEnabled = true
                vm.compactAndSend()
            }
            Button(AppLocalized("Cancel"), role: .cancel) {
                vm.cancelCompactBeforeSend()
            }
        } message: {
            Text(AppLocalized("Conversation context is nearly full. Compact the history to free up space before sending. Enabling auto-compact will do this automatically from now on."))
        }
        .alert(AppLocalized("Context Full"), isPresented: Binding(get: { vm.showContextExhaustedPrompt }, set: { vm.showContextExhaustedPrompt = $0 })) {
            Button(AppLocalized("New Session")) {
                vm.showContextExhaustedPrompt = false
                vm.cancelCompactBeforeSend()
                NotificationCenter.default.post(name: .newChatRequested, object: nil)
            }
            Button(AppLocalized("Clear Chat"), role: .destructive) {
                vm.showContextExhaustedPrompt = false
                vm.cancelCompactBeforeSend()
                vm.clearChat()
            }
            Button(AppLocalized("Cancel"), role: .cancel) {
                vm.cancelCompactBeforeSend()
            }
        } message: {
            Text(AppLocalized("The conversation context has reached its limit. Start a new session or clear the chat to continue."))
        }
        // [T-new-chat-menu-entry] Streaming guard for the "…" menu's New Chat:
        // confirm → stop the running task, then create; cancel → stay put.
        .alert(AppLocalized("Task Running"), isPresented: $showNewChatStopConfirm) {
            Button(AppLocalized("Stop & New Chat"), role: .destructive) {
                vm.cancel()
                NotificationCenter.default.post(name: .newChatRequested, object: nil)
            }
            Button(AppLocalized("Cancel"), role: .cancel) {}
        } message: {
            Text(AppLocalized("A task is running in this chat. Starting a new chat will stop it."))
        }
        .alert(AppLocalized("Clear Chat"), isPresented: $showClearChatConfirm) {
            Button(AppLocalized("Clear"), role: .destructive) { vm.clearChat() }
            Button(AppLocalized("Cancel"), role: .cancel) {}
        } message: {
            Text(AppLocalized("All messages in this session will be permanently deleted."))
        }
        // Bridge VM's slash-command "/clear" request into the local @State that
        // drives the confirmation alert above, so the menu and slash-command
        // entry points share one alert instance.
        .onChange(of: vm.clearChatConfirmRequested) { requested in
            if requested {
                showClearChatConfirm = true
                vm.clearChatConfirmRequested = false
            }
        }
        .alert(AppLocalized("Compact Above"), isPresented: Binding(
            get: { compactConfirmMessageId != nil },
            set: { if !$0 { compactConfirmMessageId = nil } }
        )) {
            Button(AppLocalized("Compact"), role: .destructive) {
                if let id = compactConfirmMessageId {
                    Task { await vm.compactBefore(id) }
                }
            }
            Button(AppLocalized("Cancel"), role: .cancel) {}
        } message: {
            Text(AppLocalized("Messages above this point will be compacted into a summary. This cannot be undone."))
        }
        .offloadPermissionDialog()
        .environment(\.openMinisURL, OpenMinisURLAction { url in
            handleMinisURLTap(url)
        })
        .environment(\.openImageGallery, OpenImageGalleryAction { presentation in
            imageGallery = presentation
        })
        .fullScreenCover(item: $previewImageFile) { fileURL in
            MinisImageFilePreviewView(fileURL: fileURL)
        }
        .alert(
            AppLocalized("File Not Found"),
            isPresented: Binding(
                get: { missingMinisFileName != nil },
                set: { if !$0 { missingMinisFileName = nil } }
            ),
            presenting: missingMinisFileName
        ) { _ in
            Button(AppLocalized("OK"), role: .cancel) { missingMinisFileName = nil }
        } message: { name in
            Text(AppLocalized("\(name) is unavailable. It may have been deleted or not yet synced from iCloud."))
        }
        .fullScreenCover(item: $imageGallery) { presentation in
            MessageImageGallery(items: presentation.items, startIndex: presentation.startIndex)
        }
        .onReceive(NotificationCenter.default.publisher(for: MarkdownImageTapRouter.tappedNotification)) { note in
            handleMarkdownImageTap(note)
        }
        .modifier(DismissImmersiveCoversListener(onDismiss: dismissAllOwnedImmersiveCovers))
        .onReceive(vm.requestComposerFocusSignal) {
            // VM fires this after filling the composer programmatically
            // (e.g. /skill tap) so the keyboard pops up immediately and
            // the user can keep typing without an extra tap on the field.
            inputFocused = true
        }
        .modifier(ChatInputAppendListener(vm: vm, inputFocused: $inputFocused))
        .modifier(RerunFromToolBlockListener(vm: vm))
        .fullScreenCover(item: $previewVideoFile) { fileURL in
            MinisVideoFullscreenPlayer(fileURL: fileURL)
        }
        .sheet(item: $previewAudioFile) { fileURL in
            MinisAudioPreviewView(fileURL: fileURL)
                .presentationDetents([.large])
                .presentationDragIndicator(.hidden)
        }
        .sheet(item: $previewTextFile) { fileURL in
            MinisTextPreviewView(fileURL: fileURL)
        }
        .sheet(item: $previewMarkdownFile) { fileURL in
            MinisMarkdownPreviewView(fileURL: fileURL)
        }
        .sheet(item: $previewDocumentFile) { fileURL in
            MinisDocumentPreviewView(fileURL: fileURL)
        }
        .sheet(item: $shareFile) { fileURL in
            MinisShareSheet(url: fileURL)
        }
        .sheet(item: $safariURL) { url in
            MinisLinkPreviewView(url: url, browserPool: vm.browserTabPool, onExpand: { _ in
                fullBrowserIsLocal = false
                let targetURL = url
                safariURL = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    fullBrowserURL = targetURL
                }
            })
        }
        // Auto-present the in-app preview when a shell tool's stdout emits
        // an OSC MinisOpenURL marker (via /usr/local/bin/minis-open).
        //
        // Dispatch by scheme:
        //  - http/https/about: in-chat WKWebView preview. ToolLiveSheet
        //    observes the same broker and takes priority for web URLs when
        //    it is on top (broker.toolSheetVisible).
        //  - minis://...: chat-resource file preview (image/markdown/html/
        //    pdf/...). Routed through handleMinisURLTap which already
        //    picks the right sheet by extension. Always dispatched here
        //    because ToolLiveSheet cannot host fullscreen file previews.
        //
        // `.dropFirst()` skips the current value that `@Published` delivers
        // to new subscribers on first attach — without it, a chat view
        // created after a previous OSC capture would re-present a stale URL.
        .onReceive(MinisOpenURLBroker.shared.$pendingURL.dropFirst().compactMap { $0 }) { url in
            if MinisOpenURLBroker.isWebScheme(url.scheme) {
                // Skip when a topmost host wants to present the web preview
                // itself: ToolLiveSheet (`toolSheetVisible`) or the
                // full-screen iSH terminal (`terminalVisible`). Otherwise
                // our sheet fights their presentation and the terminal's
                // fullScreenCover gets dismissed mid-animation.
                guard !MinisOpenURLBroker.shared.toolSheetVisible,
                      !MinisOpenURLBroker.shared.terminalVisible else { return }
                withAnimation { safariURL = url }
            } else {
                _ = handleMinisURLTap(url)
            }
            MinisOpenURLBroker.shared.consume()
        }
        .sheet(item: $previewHTMLFile) { fileURL in
            MinisHTMLPreviewView(fileURL: fileURL, onExpand: { _ in
                fullBrowserIsLocal = true
                let targetURL = fileURL
                previewHTMLFile = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    fullBrowserURL = targetURL
                }
            })
        }
        .fullScreenCover(item: $fullBrowserURL) { url in
            let wasLocal = fullBrowserIsLocal
            MinisSafariView(url: url, localFile: wasLocal, onCollapse: {
                fullBrowserURL = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    if wasLocal {
                        previewHTMLFile = url
                    } else {
                        safariURL = url
                    }
                }
            })
        }
        .sheet(isPresented: $showFileBrowser) {
            NavigationStack {
                let base = RootfsManager.shared.dataPath
                FileBrowserView(rootPath: base, initialPath: base.appendingPathComponent("var/minis"), rootLabel: "/")
            }
        }
        .sheet(isPresented: $showBrowserSheet) {
            BrowserSheetView(pool: vm.browserTabPool, isAgentBusy: vm.browserTabPool.isAgentBrowsing, onTakeover: {
                vm.browserTakeoverActive = true
            })
        }
        .sheet(isPresented: $showModelPicker) {
            NavigationStack {
                SessionModelPicker(sessionId: vm.sessionId) {
                    await vm.ensureSessionReturningId()
                }
            }
            .presentationDetents([.large])
        }
        .sheet(isPresented: $showTokenUsage) {
            TokenUsageSheet(vm: cached.vm)
                .presentationDetents([.fraction(0.8), .large])
        }
        .sheet(item: $screenshotPreview) { preview in
            ChatScreenshotPreviewSheet(image: preview.image)
        }
        .sheet(isPresented: $showSessionSkills) {
            // Pass the real id (nil for a draft) plus an ensure-session closure
            // so a pre-first-message toggle binds its override to the session
            // the chat will actually persist under, instead of the "" phantom
            // key (T-ios-session-skill-override-init-timing).
            SessionSkillsView(sessionId: vm.sessionId) {
                await vm.ensureSessionReturningId()
            }
        }
        .sheet(isPresented: $showSessionMCPs) {
            SessionMCPsView(sessionId: vm.sessionId) {
                await vm.ensureSessionReturningId()
            }
        }
        .sheet(isPresented: $showSessionMemory) {
            SessionMemoryView(vm: cached.vm)
        }
        .sheet(isPresented: $showMoveToSheet) {
            MoveToSessionSheet(currentSessionId: vm.sessionId) { targetId in
                // [T-ios-moveto-transfer-race] Stash bound to the target, so
                // only that session can consume it.
                let movedText = vm.inputText
                let movedAttachments = vm.attachments
                let stash = ViewModelCache.PendingTransfer(
                    targetId: targetId,
                    inputText: movedText,
                    attachments: movedAttachments
                )
                ViewModelCache.pendingTransfer = stash
                // Clear the source composer optimistically so the move reads as
                // instant, but keep a copy: if the target never consumes the
                // stash (navigation swallowed, wrong session opened), restore it
                // here rather than letting the user's content vanish. Attachment
                // files are NOT deleted on this path — both the stash and this
                // restore reference the same cacheURLs.
                vm.inputText = ""
                vm.attachments.removeAll()
                // The share content is no longer here — reset the flag that
                // gates the "Move to…" pill. Currently `hasMovableShareContent`
                // hides the pill anyway now the composer is empty, so this is
                // not user-visible, but leaving it true is a latent trap for
                // anything else that reads it.
                hasInjectedShareContent = false
                DispatchQueue.main.asyncAfter(deadline: .now() + ViewModelCache.PendingTransfer.staleAfter) {
                    // Match the exact stash we staged, not just its target: a
                    // second move to the same target within the stale window
                    // replaces the slot, and this timer must not restore that
                    // newer content out from under it.
                    guard let stranded = ViewModelCache.pendingTransfer,
                          stranded.targetId == targetId,
                          stranded.createdAt == stash.createdAt else { return }
                    // Still sitting in the slot → nobody consumed it.
                    ViewModelCache.pendingTransfer = nil
                    minisLogger.info("[MoveTo] Transfer to \(targetId) was never consumed — restoring content to source session")
                    if vm.inputText.isEmpty {
                        vm.inputText = movedText
                    } else if !movedText.isEmpty {
                        vm.inputText += "\n" + movedText
                    }
                    vm.attachments.append(contentsOf: movedAttachments)
                }
                // Dismiss keyboard first so it doesn't linger during transition
                inputFocused = false
                // Post navigation after sheet dismiss animation completes
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    NotificationCenter.default.post(
                        name: .moveInputToSession,
                        object: nil,
                        userInfo: ["targetId": targetId]
                    )
                }
            }
        }
        .fullScreenCover(isPresented: $showTerminal) {
            terminalInitCommand = nil
        } content: {
            NavigationStack {
                ISHTerminalView(sessionId: vm.sessionId, showCloseButton: true, initCommand: terminalInitCommand)
                    .onAppear {
                        if let sid = vm.sessionId {
                            minisLogger.info("🔍MOUNT Terminal onAppear — re-mounting minis for session \(sid)")
                            vm.mountMinis(for: sid)
                        } else {
                            minisLogger.info("🔍MOUNT Terminal onAppear — no sessionId, skipping mount")
                        }
                    }
            }
        }
        .fullScreenCover(isPresented: $showCamera, onDismiss: {
            minisLogger.info("[QuickAction] fullScreenCover(camera) onDismiss showCamera=\(showCamera)")
        }) {
            // AnyView erases the camera sheet's view tree from the
            // outer body's generic chain. Without it, AIChatView.body's
            // mangled type pushes Swift's runtime type decoder past its
            // recursion limit on cold launch via the share/quick-action
            // path — SIGSEGV in __swift_instantiateConcreteTypeFromMangledNameV2
            // with `attachmentMenuButton.getter` at the stack top (a
            // bystander; whichever leaf the decoder reached when the
            // stack ran out gets named). Same class of crash as the
            // 2026-04-17 `inputBar.getter` blow-up — the
            // `.observingQuickActions(modifier:)` modifier added by
            // T-quick-action-workflow tipped a chain that was already
            // sitting at the threshold. AnyView here is the minimal,
            // surgical cut: it sinks the largest closure in this
            // modifier (CameraPicker + .ignoresSafeArea + .onAppear)
            // into an erased subtree so the outer body type stays
            // demangle-able. Keep until the body is structurally split
            // into separate View structs (v1.10 follow-up).
            AnyView(
                CameraPicker { image in
                    minisLogger.info("[QuickAction] CameraPicker onCapture size=\(Int(image.size.width))x\(Int(image.size.height))")
                    vm.addImageAttachment(image)
                }
                .ignoresSafeArea()
                .onAppear {
                    minisLogger.info("[QuickAction] fullScreenCover(camera) content onAppear — notifying workflow")
                    QuickActionWorkflow.shared.markCoverPresented()
                }
            )
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItems,
                      maxSelectionCount: 50, matching: .any(of: [.images, .videos]))
        .onChange(of: selectedPhotoItems) { items in
            guard !items.isEmpty else { return }
            // [T-ios-photo-pick-placeholder] 1) Insert a loading placeholder chip
            // for every picked item RIGHT NOW (one main-actor batch update), so the
            // user immediately sees how many they picked instead of watching photos
            // trickle in one-by-one. 2) Load all of them CONCURRENTLY via a
            // TaskGroup. 3) Resolve each placeholder in place as its bytes arrive
            // (success → real thumbnail, failure → error chip). The send button is
            // gated on `hasLoadingAttachments` until every item settles.
            let kinds = items.map { item -> InputAttachment.Kind in
                item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) ? .video : .image
            }
            let placeholderIDs = vm.addLoadingPlaceholders(kinds: kinds)

            // Snapshot per-item metadata synchronously (PHAsset fetch + UTI) so the
            // concurrent loaders don't touch SwiftUI state or PhotosUI mid-flight.
            struct PickJob { let id: UUID; let item: PhotosPickerItem; let isVideo: Bool; let ext: String?; let date: Date? }
            let jobs: [PickJob] = zip(placeholderIDs, items).map { pid, item in
                let isVideo = item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) })
                var assetDate: Date?
                if let aid = item.itemIdentifier,
                   let asset = PHAsset.fetchAssets(withLocalIdentifiers: [aid], options: nil).firstObject {
                    assetDate = asset.creationDate
                }
                let ext = item.supportedContentTypes
                    .first(where: { $0.conforms(to: .image) })?
                    .preferredFilenameExtension
                return PickJob(id: pid, item: item, isVideo: isVideo, ext: ext, date: assetDate)
            }
            selectedPhotoItems = []

            Task {
                await withTaskGroup(of: Void.self) { group in
                    for job in jobs {
                        group.addTask {
                            if job.isVideo {
                                if let videoURL = try? await job.item.loadTransferable(type: VideoFileTransferable.self) {
                                    await MainActor.run {
                                        vm.finalizeVideoPlaceholder(id: job.id, from: videoURL.url, originalDate: job.date)
                                    }
                                } else {
                                    await MainActor.run { vm.markPlaceholderFailed(id: job.id) }
                                }
                            } else if let data = try? await job.item.loadTransferable(type: Data.self) {
                                // Preserve original encoded bytes (PNG transparency,
                                // HEIC, animated GIFs, EXIF) — written verbatim.
                                await MainActor.run {
                                    vm.finalizeImagePlaceholder(id: job.id, data: data, fileExtension: job.ext, originalDate: job.date)
                                }
                            } else {
                                await MainActor.run { vm.markPlaceholderFailed(id: job.id) }
                            }
                        }
                    }
                }
            }
        }
        .fileImporter(
            isPresented: $showDocumentPicker,
            allowedContentTypes: [.image, .pdf, .plainText, .json, .sourceCode, .presentation, .spreadsheet, .data],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls):
                for url in urls {
                    vm.addFileAttachment(from: url)
                }
            case .failure(let error):
                minisLogger.error("File import failed: \(error.localizedDescription)")
            }
        }
        .onAppear {
            let sinceInit = (CFAbsoluteTimeGetCurrent() - AIChatViewModel.onAppearTimestamp) * 1000
            minisLogger.info("[SessionLoad] onAppear T+\(String(format: "%.0f", sinceInit))ms isNew=\(cached.isNew) msgs=\(vm.messages.count)")
            // [T-inputbar-stale-across-reentry] Re-arm the leading-edge seed on
            // EVERY appear. AIChatView is keyed `.id(sessionId)` in ContentView,
            // so re-entering the SAME session reuses the same SwiftUI identity
            // and its @State survives — `inputBarHeight` keeps whatever (possibly
            // wrong) value it last held, and because `didSeedInputBarHeight` is
            // still true the synchronous seed never re-fires. If the composer's
            // geometry doesn't change on re-entry, onGeometryChange emits no new
            // callback either, so nothing ever reconciles it: a too-tall height
            // survives indefinitely across re-renders. Clearing the flag makes
            // the next (guaranteed) geometry callback re-seed authoritatively.
            didSeedInputBarHeight = false
            inputBarHeightDebounce?.cancel()
            inputBarHeightDebounce = nil
            AppLogger(category: "InputBarLayout").info("inputBarHeight re-arm seed on appear (was \(inputBarHeight))")
            vm.sessionId = sessionId
            vm.draftId = draftId
            vm.remoteDeviceId = remoteDeviceId
            vm.initialGroupId = initialGroupId
            // Snapshot total session count once so the New-Chat onboarding
            // grid only appears for first-time users (no prior sessions).
            // [T-ios-session-coldload-listsessions-block] Use the bare
            // COUNT(*) query, NOT listSessions().count — the latter ran 4
            // un-indexable parts_json LIKE subqueries per session (~0.5s cold
            // on a 100k-message DB) and, on the serialized ChatStore actor,
            // head-of-line-blocked the loadSession() dispatched below, which
            // was the reported ~3s cold-open stall. The comment used to claim
            // "single SQLite COUNT" — now the code actually does one.
            if totalSessionCount == nil {
                Task.detached(priority: .utility) {
                    let count = await ChatStore.shared.sessionCount()
                    await MainActor.run { totalSessionCount = count }
                }
            }
            if let sessionId { AIChatViewModel.activeSessionId = sessionId }
            vm.ensureKernelBooted()
            if let sessionId {
                if cached.isNew || (vm.messages.isEmpty && !vm.isLoadingSession) {
                    // Load session if: (a) VM is freshly created, or (b) cache hit but messages
                    // are empty — this can happen on iOS 16 where NavigationStack may recreate
                    // @StateObject unexpectedly, causing isNew=false but an empty VM.
                    minisLogger.info("🔄SESSION AIChatView.onAppear loading session \(sessionId) isNew=\(cached.isNew) msgs=\(vm.messages.count)")
                    // [T-ios-session-coldload-listsessions-block] .userInitiated
                    // so the actual session-open work wins the serialized
                    // ChatStore actor queue over background sidebar-refresh
                    // listSessions() scans that would otherwise starve it.
                    Task(priority: .userInitiated) {
                        // Phase A: auto-repair any sortOrder / legacy marker anomalies
                        // before loading. Cheap no-op if the session is healthy.
                        _ = await ChatStore.shared.repairSessionIfNeeded(sessionId: sessionId)
                        await vm.loadSession()
                    }
                } else if vm.messages.isEmpty && vm.isLoadingSession {
                    // iOS 16 edge case: a previous view instance started loadSession()
                    // but this view appeared before it completed. Wait for it to finish,
                    // then reload if messages are still empty (objectWillChange may have
                    // fired between old and new CachedViewModel subscriptions).
                    minisLogger.info("🔄SESSION AIChatView.onAppear WAITING for in-flight load session=\(sessionId)")
                    Task {
                        // Wait for the in-flight load to complete (poll at short intervals)
                        for _ in 0..<20 {
                            try? await Task.sleep(nanoseconds: 100_000_000) // 100ms
                            if !vm.isLoadingSession { break }
                        }
                        // If messages are still empty after the load completed, retry
                        if vm.messages.isEmpty && !vm.isLoadingSession {
                            minisLogger.warning("🔄SESSION AIChatView.onAppear RETRY load — messages still empty after in-flight load for \(sessionId)")
                            await vm.loadSession()
                        }
                    }
                } else {
                    let reuseStart = CFAbsoluteTimeGetCurrent()
                    minisLogger.info("🔄SESSION AIChatView.onAppear REUSING cached vm for \(sessionId) isProcessing=\(vm.isProcessing) msgs=\(vm.messages.count)")
                    // Remount minis for this session (in case another session took over)
                    vm.mountMinis(for: sessionId)
                    // While the user was off this view, an iCloud / LAN
                    // sync may have landed new messages (or applied a
                    // tombstone). reloadMessagesFromDB internally compares
                    // SQLite count + max(sort_order) + (id, sort_order)
                    // hash against the VM's last-known baseline and only
                    // re-renders on diff. Skip when isProcessing — the
                    // active stream owns messages right now and a reload
                    // would clobber the in-flight assistant turn.
                    if !vm.isProcessing && !vm.isCompacting {
                        Task { @MainActor in await vm.reloadMessagesFromDB(reason: "AIChatView.onAppear-reuse") }
                    }
                    vm.consumeDeferredSnapshotIfNeeded()
                    let mountElapsed = (CFAbsoluteTimeGetCurrent() - reuseStart) * 1000
                    // Cached VM already has messages — trigger scroll-to-bottom
                    // since isLoadingSession won't transition and its onChange won't fire.
                    if !vm.messages.isEmpty {
                        vm.forceScrollToBottom.send()
                    }
                    let totalElapsed = (CFAbsoluteTimeGetCurrent() - reuseStart) * 1000
                    minisLogger.info("[SessionLoad] \(sessionId) — REUSE: \(String(format: "%.1f", totalElapsed))ms [mount: \(String(format: "%.1f", mountElapsed)) | msgs: \(vm.messages.count)]")
                }
            } else {
                minisLogger.info("🔄SESSION AIChatView.onAppear nil sessionId — draft mode")
                // Draft session — auto-focus input for immediate typing
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    guard !hasOverlayPresented, isChatViewVisible else { return }
                    inputFocused = true
                }
            }
            minisLogger.info("[Share] AIChatView.onAppear: sessionId=\(sessionId ?? "nil") bufferVersion=\(shareCoordinator.bufferVersion) hasBuffer=\(shareCoordinator.pendingShareBuffer != nil)")
            injectPendingShareIfNeeded()
            injectPendingTransferIfNeeded()
            isChatViewVisible = true
            refreshTitlePillSession()
            // Notify workflow that THIS view is mounted + visible. If
            // the workflow is waiting for our session id, it will
            // transition to chatReady — our state observer below picks
            // that up and flips `showCamera`. Transient draft views
            // (sessionId=nil) won't match the workflow's target and
            // are correctly skipped.
            tryMarkWorkflowChatReady(reason: "onAppear")
        }
        .observingQuickActions(modifier: quickActionObserver)
        .onChange(of: vm.sessionId) { _ in
            refreshTitlePillSession()
        }
        .onReceive(NotificationCenter.default.publisher(for: .sessionDidUpdate)) { note in
            guard let sid = vm.sessionId,
                  let updatedId = note.object as? String,
                  updatedId == sid else { return }
            refreshTitlePillSession()
        }
        .onChange(of: shareCoordinator.bufferVersion) { newVersion in
            // Warm start: user is already in a session when share arrives
            minisLogger.info("[Share] AIChatView.onChange(bufferVersion)=\(newVersion) sessionId=\(sessionId ?? "nil") draftId=\(draftId ?? "nil") hasBuffer=\(shareCoordinator.pendingShareBuffer != nil)")
            injectPendingShareIfNeeded()
        }
        .onDisappear {
            isChatViewVisible = false
            // [T-voice-inputbar-collapse-selfheal] The health probe must not
            // outlive the view — its report would describe a composer that no
            // longer exists.
            inputBarHealthProbe?.cancel()
            inputBarHealthProbe = nil
            if speechManager.state == .recording {
                speechManager.stopRecording()
            }
            // [T-inputbar-keyboard-leaks-to-home] Cut the ONLY channel by which
            // chat composer geometry can reach the HOME screen.
            //
            // `inputBarHeight` is per-view @State and only feeds the message
            // list's bottom inset — it cannot touch home. What CAN is a stranded
            // first responder: leaving the chat while the composer (or the voice
            // transcript editor) still holds focus leaves the keyboard's inset
            // reserved on the WINDOW, and SwiftUI's automatic keyboard avoidance
            // applies that window-wide. The home screen's 新建/搜索 FAB row is a
            // `.safeAreaInset(edge: .bottom)` (ContentView 1213/1326) with no
            // `.ignoresSafeArea(.keyboard)` opt-out, so it reads that inflated
            // bottom safe area and floats upward — the reported symptom. Same
            // failure class as the offscreen-WebView phantom keyboard (8232308a)
            // and the voice-editor stranded responder (4c530bf4); this closes the
            // remaining door, the chat→home transition itself.
            //
            // Blanket-disabling keyboard avoidance on the home FAB row would be
            // the wrong fix — its inline search field legitimately needs to rise
            // with the keyboard. Releasing the responder at the source is correct
            // and is a no-op when nothing is focused.
            inputFocused = false
            let keyWindow = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
            if let keyWindow, keyWindow.endEditing(true) {
                AppLogger(category: "InputBarLayout").info("chat onDisappear — released a lingering first responder (would have left a phantom keyboard inset on the window)")
            }
            // Capsule auto-shows whenever audio is loaded — no manual activation needed.
            minisLogger.info("🔑DRAFT AIChatView.onDisappear vm=\(vm.vmInstanceId) sessionId=\(sessionId ?? "nil") draftId=\(draftId ?? "nil") vm.sessionId=\(vm.sessionId ?? "nil") vm.isProcessing=\(vm.isProcessing)")
        }
        // [T-voice-bg-fg-gap] Structural immunity: while the voice panel is up
        // and the transcript editor is NOT open, there is no legitimate keyboard
        // in this subtree — so ignore the keyboard safe-area entirely in that
        // state. Device log 2026-07-17 12:12 showed the panel shifting from
        // y=686…778 to y=638…730 (exactly 48pt, the keyboard-accessory height)
        // during the didEnterBackground layout pass: iOS re-asserted a keyboard
        // inset while snapshotting, the app suspended before the matching hide
        // was processed, and no keyboard event fires on foreground return — the
        // zombie inset persisted and the panel floated (the reported bottom
        // gap). Ignoring the keyboard edge in this state makes ANY such stale
        // inset harmless; edit mode (edges: []) keeps normal avoidance so the
        // panel still lifts above the keyboard while editing, and text mode is
        // untouched.
        .ignoresSafeArea(
            .keyboard,
            edges: (voiceInputActive && !voiceVM.isEditingTranscript) ? .bottom : []
        )
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                // [T-voice-inputbar-fg-stale-debounce] iOS changes safe area
                // insets while backgrounded, causing onGeometryChange to fire
                // with shifted geometry. That measurement spawns a debounce
                // Task carrying stale height. Cancel it so the stale value
                // never lands. The post-foreground onGeometryChange re-fires
                // with the correct geometry and enters a fresh debounce.
                // NOTE: do NOT reset didSeedInputBarHeight here. The seed path
                // is synchronous (no debounce) so it would capture a mid-
                // animation frame if the panel is expanding/collapsing when the
                // app returns to foreground — the debounce path is the correct
                // one for an already-visible view.
                inputBarHeightDebounce?.cancel()
                inputBarHeightDebounce = nil
                // [T-voice-inputbar-collapse-selfheal] …EXCEPT when the composer
                // has stopped reporting geometry altogether, which is the one
                // state the note above cannot cover.
                //
                // Device 2026-08-18 01:29 (iPhone 17 Pro, no lock, no manual app
                // switch): a transcript landed and collapsed the panel band in a
                // single frame (transcriptContentHeight 262→42, an intermediate
                // panel frame reaching y=1119 on an 874pt window). The system
                // then drove six inactive↔active cycles in 20s on its own
                // (snapshot / audio-session interruption right after
                // `end(.capture)`), each logging `hadResponder=true`. After
                // `inputBarHeight settled=282.0` at 01:29:15.597 the composer's
                // onGeometryChange NEVER fired again: its SwiftUI host survived
                // with alpha=1 and zero subviews (confirmed live via
                // debug.viewTree — FloatingBarHostingView nkids=0), so the whole
                // bottom of the screen was empty black with no input bar.
                //
                // Nothing could wake it: this subtree deliberately ignores the
                // keyboard safe area while the voice panel is up
                // ([T-voice-bg-fg-gap], see the .ignoresSafeArea above), so the
                // inset churn that would normally force a re-layout is filtered
                // out by design, and onGeometryChange is the ONLY writer of
                // inputBarHeight. The user's own workaround — leave the session
                // and re-enter — worked precisely because `.onAppear` re-arms
                // the seed ([T-inputbar-stale-across-reentry]); this is that
                // same recovery, applied without making the user find it.
                //
                // Re-arm only when the composer is provably not reporting: the
                // last on-screen sample disagrees with the committed height, or
                // no sample was ever recorded. In the healthy case both values
                // match and this is a no-op, so the mid-animation hazard the
                // note above warns about is not reintroduced — a re-seed can
                // only happen where the alternative is a permanently wrong (or
                // missing) bar.
                let committedH = inputBarHeight
                let latestH = latestInputBarFrameH
                let sinceReport = inputBarLastGeometryAt.map { CFAbsoluteTimeGetCurrent() - $0 } ?? -1
                if didSeedInputBarHeight,
                   latestH <= 0 || abs(latestH - committedH) > 0.5 {
                    didSeedInputBarHeight = false
                    AppLogger(category: "InputBarLayout").info("[voice-bgfg] scene active — composer geometry stale (committed=\(committedH) latest=\(latestH) lastReport=\(String(format: "%.1f", sinceReport))s ago); re-arming seed so the next callback re-measures")
                }

                // [T-voice-inputbar-collapse-selfheal] Re-arming only makes the
                // seed ELIGIBLE to fire; it cannot make a host that has stopped
                // reporting geometry start again. In the observed failure that
                // host had zero subviews and emitted nothing for minutes, so
                // verify the wake-up actually happened and say so loudly when it
                // did not — this is the line that tells the next investigation
                // "the self-heal ran and was not enough" instead of leaving them
                // to re-derive it from a silent log.
                inputBarHealthProbe?.cancel()
                let probeBaseline = inputBarGeometryTick
                inputBarHealthProbe = Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(900))
                    guard !Task.isCancelled else { return }
                    let ticked = inputBarGeometryTick != probeBaseline
                    let age = inputBarLastGeometryAt.map { CFAbsoluteTimeGetCurrent() - $0 } ?? -1
                    if ticked {
                        AppLogger(category: "InputBarLayout").info("[InputBarHealth] OK after foreground — composer re-reported geometry (h=\(latestInputBarFrameH) committed=\(inputBarHeight))")
                    } else {
                        AppLogger(category: "InputBarLayout").error("[InputBarHealth] STALLED — no geometry callback 900ms after foreground. committed=\(inputBarHeight) latest=\(latestInputBarFrameH) lastReport=\(String(format: "%.1f", age))s ago voice=\(voiceInputActive) editing=\(voiceVM.isEditingTranscript) seeded=\(didSeedInputBarHeight). The composer host is not laying out; expect a blank bottom area. Leaving and re-entering the session rebuilds it.")
                    }
                }
                // [T-voice-bg-fg-gap] Foreground reseal: if we return to a
                // voice-mode-not-editing state, no responder should be armed.
                // Releasing here is a no-op when nothing is focused and clears
                // any responder UIKit resurrected during the background pass.
                if voiceInputActive && !voiceVM.isEditingTranscript {
                    let keyWindow = UIApplication.shared.connectedScenes
                        .compactMap { $0 as? UIWindowScene }
                        .flatMap(\.windows)
                        .first(where: \.isKeyWindow)
                    if let keyWindow, keyWindow.endEditing(true) {
                        AppLogger(category: "InputBarLayout").info("[voice-bgfg] scene active — released a resurrected responder (voice mode, not editing)")
                    }
                }
            }
            if phase != .active, voiceInputActive, !voiceVM.isEditingTranscript {
                // [T-voice-bg-fg-gap] Complete any in-flight keyboard dismissal
                // BEFORE the snapshot/suspend layout pass — but ONLY in the
                // voice-mode-not-editing state, where no responder is ever
                // legitimate (text mode keeps its focused composer across app
                // switches; edit mode keeps its editor keyboard — resigning
                // those would be a visible regression). If the edit→send
                // teardown's dismiss animation is still running when the app
                // backgrounds, iOS can freeze the window mid-dismissal with a
                // partial keyboard inset that survives into the next foreground.
                // Synchronous resign + no-animation layout guarantees the window
                // geometry is final before UIKit snapshots it.
                let keyWindow = UIApplication.shared.connectedScenes
                    .compactMap { $0 as? UIWindowScene }
                    .flatMap(\.windows)
                    .first(where: \.isKeyWindow)
                if let keyWindow {
                    let hadResponder = keyWindow.endEditing(true)
                    UIView.performWithoutAnimation { keyWindow.layoutIfNeeded() }
                    AppLogger(category: "InputBarLayout").info("[voice-bgfg] scene \(phase == .inactive ? "inactive" : "background") — forced keyboard-dismiss completion (hadResponder=\(hadResponder)) voice=\(voiceInputActive) editing=\(voiceVM.isEditingTranscript)")
                }
            }
            if phase != .active, speechManager.state == .recording {
                speechManager.stopRecording()
            }
        }
        .onChange(of: deepLink.showTerminal) { show in
            if show {
                terminalInitCommand = deepLink.terminalInitCommand
                showTerminal = true
                deepLink.showTerminal = false
                deepLink.terminalInitCommand = nil
            }
        }
        // [T-ios-voiceover-announce] Announce the end of a turn to VoiceOver.
        // Deliberately a SEPARATE observer from the auto-focus handler below:
        // that one returns early when the auto-focus setting is off, and a
        // blind user must still hear that the reply ended regardless of an
        // unrelated keyboard preference. The outcome closure is evaluated at
        // the end edge so it sees the final cancel/error state.
        .announceChatTurnEnd(isProcessing: vm.isProcessing) {
            if vm.userDidCancel { return .stopped }
            if vm.errorMessage != nil { return .failed }
            return .finished
        }
        .onChange(of: vm.isProcessing) { processing in
            if !processing {
                // Reply reading is handled INCREMENTALLY during streaming (the
                // SSE loop splits into sentences/titles + tool announcements and
                // queues them as they arrive — see speakQueued/extractNewSentences).
                // No whole-reply speak here; that would double-read everything.
                // [T-keyboard-auto-pop default flip] Gate behind a Settings
                // toggle. Default ON — most users want the composer ready
                // for a follow-up immediately. `object(forKey:)` lets us
                // distinguish "never toggled" (nil → use new ON default)
                // from "explicitly set OFF" (NSNumber(false) → respect).
                // Existing length-based skip is still applied so very long
                // replies still don't trigger the auto-focus.
                let autoFocusEnabled = (UserDefaults.standard.object(forKey: "chat.autoFocusAfterReply") as? Bool) ?? true
                guard autoFocusEnabled else { return }
                // [T-ios-retry-keyboard] Don't treat the end of a RETRIED turn
                // as "a reply arrived".
                //
                // Trigger chain being cut: the user taps Retry on a failure from
                // a while back -> retry() sets isProcessing=true -> the re-sent
                // request fails fast (kernel down, no concurrency slot, or an
                // immediate provider error) -> isProcessing flips back to false
                // -> this observer fires. The edge is indistinguishable from a
                // successful reply, and the delayed block's guards now all pass
                // (the user IS looking at the chat, nothing is queued, and the
                // errored message is short so the <600 length check succeeds) —
                // so the keyboard rises seconds after a tap that only meant
                // "try that again".
                //
                // Scoped to the turn's ORIGIN, not its outcome: auto-focus when
                // a fresh send fails is existing, accepted behaviour and is
                // deliberately left alone. Consumed (not just read) so it
                // governs exactly one turn.
                let wasRetry = vm.turnStartedByRetry
                vm.turnStartedByRetry = false
                guard !wasRetry else { return }
                if GCKeyboard.coalesced != nil {
                    inputFocused = true
                } else {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        guard !hasOverlayPresented, isChatViewVisible else { return }
                        guard !vm.isProcessing, vm.promptQueue.isEmpty else { return }
                        // Re-check: a retry started during the 1.5s window would
                        // otherwise be focused by this older timer.
                        guard !vm.turnStartedByRetry else { return }
                        let lastAssistantLength = vm.messages.last.flatMap {
                            $0.role == .assistant ? $0.blocks.reduce(0) { $0 + $1.content.count } : nil
                        } ?? 0
                        if lastAssistantLength < 600 {
                            inputFocused = true
                        }
                    }
                }
            }
        }
        // [T-ios-retry-hide-when-processing] Inject the view model so deep
        // descendants (e.g. ToolCapsuleView's long-press menu) can react to
        // `vm.isProcessing` without threading the vm through every level.
        .environmentObject(vm)
    }

    // MARK: - Home Screen Quick Actions

    /// Stable id this view represents in the navigation stack. For
    /// draft sessions (`__new__…`) this is the draftId; for opened
    /// sessions it's the real sessionId. `vm.sessionId` cannot be used
    /// because it stays nil for drafts until the first message is sent,
    /// which is too late for the workflow handoff.
    private var quickActionMatchId: String? { sessionId ?? draftId }

    private var quickActionObserver: QuickActionObserverModifier {
        QuickActionObserverModifier(
            sessionId: quickActionMatchId,
            isVisible: isChatViewVisible,
            onWaitingMatch: { tryMarkWorkflowChatReady(reason: "modifier-waiting") },
            onChatReady: { action in applyQuickAction(action) }
        )
    }

    /// Tell the workflow this view is alive + showing the right
    /// session, so it can promote `waitingForChatMount → chatReady`.
    /// Idempotent: workflow ignores the call unless its target id
    /// matches and the state is `waitingForChatMount`.
    private func tryMarkWorkflowChatReady(reason: String) {
        guard let sid = quickActionMatchId, isChatViewVisible else { return }
        if case .waitingForChatMount(_, let target) = QuickActionWorkflow.shared.state, target == sid {
            minisLogger.info("[QuickAction] tryMarkWorkflowChatReady reason=\(reason) sid=\(sid)")
            QuickActionWorkflow.shared.markChatReady(sessionId: sid)
        }
    }

    /// Apply a one-shot launch action delivered by the workflow's
    /// `chatReady` state. Flips the relevant @State so SwiftUI mounts
    /// the camera sheet / starts speech recognition.
    private func applyQuickAction(_ action: ChatLaunchAction) {
        minisLogger.info("[QuickAction] applyQuickAction action=\(String(describing: action)) showCamera=\(showCamera) hasOverlay=\(hasOverlayPresented) chatVisible=\(isChatViewVisible)")
        // Guard against late deliveries to a view that already
        // disappeared — workflow re-publishes chatReady on retry and
        // the modifier's onReceive can fire after our onDisappear set
        // isChatViewVisible=false. In that case let the workflow's
        // retry timer re-publish so the next live view picks it up.
        guard isChatViewVisible else {
            minisLogger.info("[QuickAction] applyQuickAction skipped — view not visible")
            return
        }
        switch action {
        case .startVoice:
            // Mirror the mic button's action: ask for speech +
            // microphone permission, then start recording. Drop
            // input-focus so the keyboard doesn't fight the speech
            // session for focus.
            Task { @MainActor in
                guard speechManager.state == .idle else { return }
                let granted = await speechManager.requestPermissions()
                guard granted else { return }
                inputFocused = false
                lastRecognizedLength = 0
                try? speechManager.startRecording()
                // [T-voice-input-mode-preference-ios] Mark the composition as
                // voice-assisted so the send doesn't flip the preference to
                // "text". (No preference write here — the quick action is its
                // own explicit intent, not a mic tap.)
                vm.voiceUsedInComposition = true
            }
            // No cover for voice — close the workflow loop immediately
            // so the retry timer doesn't fire and re-trigger the mic.
            QuickActionWorkflow.shared.markCoverPresented()
        case .openCamera:
            // Drop the keyboard so the system camera UI animates in
            // without fighting the input bar for the screen. The
            // workflow guarantees we're only invoked when the view is
            // visible + the cover binding is attached, so no runloop
            // hop is needed.
            inputFocused = false
            showCamera = true
            minisLogger.info("[QuickAction] openCamera — showCamera=true")
        }
    }

    // MARK: - Share Injection

    /// A shared/opened JSON file detected as a Provider export, pending the
    /// user's import-vs-attach choice. [T-ios-json-open-provider-import-prompt]
    struct PendingProviderImport: Identifiable {
        let id = UUID()
        /// The raw JSON string, fed directly to `importInstanceJSON`.
        let json: String
        /// A temp copy of the file for the "add as attachment" branch (the
        /// shared original is cleaned up right after ingestion).
        let fileURL: URL
    }

    /// Returns the file's JSON string iff it is a Provider export — i.e. its
    /// top-level object carries `providerType` (a valid `ProviderType`),
    /// `credentialType` (String), and `models` (array). Same required fields
    /// `ProviderConfigStore.importInstanceJSON` keys on, so a hit reliably
    /// imports. Returns nil for non-JSON or ordinary JSON. [T-ios-json-open-provider-import-prompt]
    static func providerExportJSON(at url: URL) -> String? {
        guard url.pathExtension.lowercased() == "json",
              let data = try? Data(contentsOf: url),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let providerTypeRaw = dict["providerType"] as? String,
              ProviderType(rawValue: providerTypeRaw) != nil,
              dict["credentialType"] is String,
              dict["models"] is [Any] else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func injectPendingShareIfNeeded() {
        // [T-share-routes-to-background-session] Only the session the share was
        // ROUTED TO may consume it. Both entry points (onAppear and the
        // bufferVersion observer) funnel through here, so the check sits at the
        // single place that actually takes the buffer.
        //
        // Previously any mounted chat consumed the buffer on a version bump.
        // Device log 2026-08-18 23:55:42: a share arriving while the user was
        // looking at session 87B79110 — a conversation running an agent loop in
        // the BACKGROUND — was injected straight into its composer, because
        // that view simply happened to be on screen. ContentView now stamps the
        // destination on the buffer before navigating; a nil stamp (cold
        // launch, where the launch flow owns the choice) still passes.
        guard shareCoordinator.bufferTargets(sessionId, draftId: draftId) else {
            minisLogger.info("[Share] injectPendingShareIfNeeded — buffer is addressed to another session (mine: sessionId=\(sessionId ?? "nil") draftId=\(draftId ?? "nil")); leaving it")
            return
        }
        guard let pending = shareCoordinator.consumeBuffer() else {
            minisLogger.info("[Share] injectPendingShareIfNeeded — no buffer or expired")
            return
        }
        minisLogger.info("[Share] Injecting \(pending.items.count) items into chat")

        for (i, item) in pending.items.enumerated() {
            switch item.kind {
            case .inlineText:
                minisLogger.info("[Share] item[\(i)] inlineText: \(String(item.value.prefix(100)))")
                if !vm.inputText.isEmpty { vm.inputText += "\n" }
                let text = item.value
                // Append a trailing space to URLs so the cursor doesn't stick to the link
                if text.hasPrefix("http://") || text.hasPrefix("https://") {
                    vm.inputText += text + " "
                } else {
                    vm.inputText += text
                }
            case .attachment:
                guard let dir = SharedContainerStore.sharedFileDirectory else {
                    minisLogger.error("[Share] item[\(i)] sharedFileDirectory is nil!")
                    continue
                }
                let fileURL = dir.appendingPathComponent(item.value)
                let exists = FileManager.default.fileExists(atPath: fileURL.path)
                minisLogger.info("[Share] item[\(i)] attachment: \(item.value) exists=\(exists) path=\(fileURL.path)")
                guard exists else { continue }
                // [T-ios-json-open-provider-import-prompt] If this is a JSON
                // Provider export, don't silently attach it — ask the user
                // whether to import it as a provider or add it as an attachment.
                // We only intercept the FIRST such file (the prompt is modal);
                // capture the JSON string (for import) + a temp copy of the file
                // (for the attachment path, since cleanSharedFiles() below will
                // delete the shared original) and defer to the dialog.
                if pendingProviderImport == nil, let json = Self.providerExportJSON(at: fileURL) {
                    let tempURL = FileManager.default.temporaryDirectory
                        .appendingPathComponent("provider-share-\(UUID().uuidString).json")
                    try? FileManager.default.copyItem(at: fileURL, to: tempURL)
                    pendingProviderImport = PendingProviderImport(json: json, fileURL: tempURL)
                    minisLogger.info("[Share] item[\(i)] detected Provider export JSON — prompting import vs attachment")
                    continue
                }
                vm.addFileAttachment(from: fileURL)
                minisLogger.info("[Share] item[\(i)] addFileAttachment done, attachments count=\(self.vm.attachments.count)")
            }
        }

        // Clean up shared files after ingestion
        SharedContainerStore.cleanSharedFiles()

        minisLogger.info("[Share] Injection complete. inputText='\(String(self.vm.inputText.prefix(100)))' attachments=\(self.vm.attachments.count)")

        hasInjectedShareContent = true

        // Focus input if we injected text
        if !vm.inputText.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                guard self.isChatViewVisible else { return }
                inputFocused = true
            }
        }
    }

    // MARK: - Pending Transfer Injection

    private func injectPendingTransferIfNeeded() {
        guard let transfer = ViewModelCache.pendingTransfer else { return }

        // [T-ios-moveto-transfer-race] Only the intended target may consume the
        // stash. Previously ANY session that appeared first took it, so a
        // swallowed push meant the content surfaced in whatever session the
        // user opened next. A mismatch is not an error — it just means we are
        // not the target — so leave the stash alone and let the real target
        // claim it. Time-based cleanup is what stops it lingering forever.
        //
        // [T-ios-moveto-new-session] The target may be a NOT-YET-CREATED
        // session. "Move to… → New Chat" stages the synthetic `__new__<UUID>`
        // draft id that ContentView is about to push, but that view is built as
        // `AIChatView(sessionId: nil, draftId: "__new__…")` — the real session
        // row only exists once the user sends. Matching `vm.sessionId` alone
        // therefore NEVER matched for a new-chat target: the stash sat
        // unclaimed until it went stale and was deleted, so the moved text and
        // images vanished with no restore (the source view that owns the
        // restore timer has already been popped by then). Match `draftId` too.
        let isTarget = transfer.targetId == vm.sessionId || transfer.targetId == draftId
        guard isTarget else {
            if transfer.isStale {
                ViewModelCache.discardPendingTransfer(reason: "stale, target \(transfer.targetId) never appeared")
            }
            return
        }
        guard !transfer.isStale else {
            ViewModelCache.discardPendingTransfer(reason: "target \(transfer.targetId) appeared too late")
            return
        }

        ViewModelCache.pendingTransfer = nil
        minisLogger.info("[MoveTo] Injecting transfer: text='\(String(transfer.inputText.prefix(50)))' attachments=\(transfer.attachments.count) existing=\(vm.attachments.count)")
        // Clean up any stale unsent attachments on the target VM before injecting
        if !vm.attachments.isEmpty {
            minisLogger.info("[MoveTo] Clearing \(vm.attachments.count) stale attachments from target session")
            for a in vm.attachments { try? FileManager.default.removeItem(at: a.cacheURL) }
            vm.attachments.removeAll()
        }
        if !transfer.inputText.isEmpty {
            if !vm.inputText.isEmpty { vm.inputText += "\n" }
            vm.inputText += transfer.inputText
        }
        vm.attachments = transfer.attachments
        // Focus input after the view is fully settled and keyboard from
        // the source session has dismissed
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            guard self.isChatViewVisible else { return }
            inputFocused = true
        }
    }

    // MARK: - Toolbar Trailing Menu

    @ViewBuilder
    private var trailingMenu: some View {
        // [T-ios-navbar-uikit-menu] FINAL: UIKit-owned button+menu. The
        // SwiftUI route is exhausted — with .equatable() the toolbar host
        // provably stopped ALL ToolbarContent rebuilds (device log
        // 2026-07-18 01:33: toolbarHostEval=1 across an entire session)
        // and the open menu STILL refreshed on tool-render/streaming
        // passes: SwiftUI's toolbar bridge re-pushes bar items on host
        // updates unconditionally, independent of content diffing. Only a
        // persistent UIKit anchor (the UIButton instance survives SwiftUI
        // updates; the presented UIMenu hangs off it) is immune — user-
        // verified to stop the refresh on 2026-07-17. sizeThatFits reports
        // the intrinsic icon size so the system wraps it in the same round
        // glass as a plain toolbar icon (fixes the earlier capsule).
        ChatTrailingMenuButton(
            messagesEmpty: vm.messages.isEmpty,
            hasSession: vm.sessionId != nil,
            isForcePulling: isForcePulling,
            iCloudSyncEnabled: iCloudSyncEnabled,
            memoryEnabled: vm.memoryEnabled,
            speakEnabled: cached.vm.speakEnabled,
            showEnhancedCacheToggle: cached.vm.showEnhancedCacheToggle,
            enhancedCacheEnabled: cached.vm.enhancedCacheEnabled,
            showFastModeToggle: activeModelSupportsFastMode,
            fastModeEnabled: codexFastModeEnabled,
            onNewChat: { requestNewChatFromMenu() },
            // [T-chat-menu-compact-entry] Same effect as the /compact slash
            // command (AIChatViewModel+SlashCommands case "compact").
            onCompact: { vm.compactAll() },
            onClearChat: { showClearChatConfirm = true },
            onForceSync: { runForceSync() },
            onForcePull: { showForcePullConfirm = true },
            onOpenTerminal: { showTerminal = true },
            onOpenBrowser: { showBrowserSheet = true },
            onBrowseFiles: { showFileBrowser = true },
            onSkills: { showSessionSkills = true },
            onMCPs: { showSessionMCPs = true },
            onMemories: { showSessionMemory = true },
            setSpeakEnabled: { enabled in
                cached.vm.speakEnabled = enabled
                // Keep the persisted "Read replies" preference in lockstep.
                VoiceOutputPreferences.isEnabled = enabled
                if enabled {
                    // Explicit enable from the "…" menu → open expanded.
                    cached.vm.speechPlayerExpanded = true
                } else {
                    cached.vm.stopSpeech()
                }
            },
            setEnhancedCache: { newValue in
                if newValue && !UserDefaults.standard.bool(forKey: "enhancedCacheConfirmed") {
                    showEnhancedCacheAlert = true
                } else {
                    cached.vm.enhancedCacheEnabled = newValue
                }
            },
            // [T-codex-fast-mode] Persisted via @AppStorage; OpenAIProvider
            // reads the same key at request-build time, so the flip applies
            // to the very next Codex request.
            setFastMode: { enabled in codexFastModeEnabled = enabled },
            onTokenUsage: { showTokenUsage = true },
            // LastAPIRequestBody + copySessionDataToClipboard are DEBUG-only (the
            // menu buttons that invoke these closures are #if DEBUG too); guard the
            // bodies so Release/archive builds — where those symbols are compiled
            // out — still link. Release keeps the closures as no-ops.
            onCopyRequests: {
                #if DEBUG
                let requests = LastAPIRequestBody.shared.getAll()
                if requests.isEmpty {
                    UIPasteboard.general.string = "(no requests captured)"
                } else {
                    let all = requests.enumerated().map { idx, req in
                        req.formatted(index: idx + 1)
                    }.joined(separator: "\n\n")
                    UIPasteboard.general.string = all
                }
                #endif
            },
            onCopySessionData: {
                #if DEBUG
                copySessionDataToClipboard()
                #endif
            }
        )
    }

    // MARK: - New Chat (menu entry)

    /// [T-new-chat-menu-entry] "New Chat" from the "…" menu. Streaming sessions
    /// get a confirm first (stopping the task is destructive); idle sessions go
    /// straight to the shared `.newChatRequested` path, which replaces the
    /// current chat with a fresh draft. Drafts aren't persisted until the first
    /// message, so leaving an empty/draft session this way leaves no residue,
    /// and a double-tap just re-mints the draft instead of creating two rows.
    private func requestNewChatFromMenu() {
        if vm.isProcessing {
            showNewChatStopConfirm = true
        } else {
            NotificationCenter.default.post(name: .newChatRequested, object: nil)
        }
    }

    // MARK: - Force Sync / Pull

    private func runForceSync() {
        guard let sid = vm.sessionId, !isForcePulling else { return }
        isForcePulling = true
        forcePullToast = AppLocalized("Marking for upload…")
        Task {
            let n = await ChatStore.shared.forceSyncSession(sid)
            if #available(iOS 17.0, *) {
                await MainActor.run { SyncCore.shared.scheduleSend(delay: 0.5) }
            }
            await MainActor.run {
                isForcePulling = false
                forcePullToast = AppLocalized("Marked \(n) records for sync. iCloud is uploading now.")
                DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                    forcePullToast = nil
                }
            }
        }
    }

    private func runForcePull() {
        guard let sid = vm.sessionId, !isForcePulling else { return }
        isForcePulling = true
        forcePullToast = AppLocalized("Pulling from iCloud…")
        Task {
            let outcome = await ChatStore.shared.forcePullSession(sessionId: sid)
            // Inbound hydration runs asynchronously after applyPortables
            // hands batches off to MainActor.processInbound, so wait a
            // beat before reloading. Two reloads cover the case where
            // the first one lands mid-batch.
            try? await Task.sleep(nanoseconds: 800_000_000)
            await vm.reloadMessagesFromDB(reason: "forcePull-tick1")
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            await vm.reloadMessagesFromDB(reason: "forcePull-tick2")
            await MainActor.run {
                isForcePulling = false
                switch outcome {
                case .applied(let pulled, let deleted):
                    forcePullToast = AppLocalized("Pulled \(pulled) records · removed \(deleted) local")
                case .cloudEmpty:
                    forcePullToast = AppLocalized("iCloud has no records for this chat — local messages preserved")
                case .failed(let msg):
                    forcePullToast = AppLocalized("Force Pull failed: \(msg) — local messages preserved")
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                    forcePullToast = nil
                }
            }
        }
    }

    // MARK: - Navigation Title + Model Selector

    /// [T-ios-navbar-toolbar-host] Principal toolbar content, extracted from
    /// the old inline ToolbarItem so ChatToolbarHost can render it. Layout
    /// preserved verbatim:
    /// [T-navbar-title-centering] iOS 18 and below let the principal toolbar
    /// item shrink to its content width and anchor it left-of-center; iOS 19+
    /// centers natively — wrap in an infinite-width HStack with bookend
    /// Spacers on legacy iOS, and cap the width to (screen − ~140pt chrome)
    /// so long titles truncate instead of pushing through the trailing menu.
    @ViewBuilder
    private var chatNavPrincipalContent: some View {
        // Cap the title width to (screen − chrome) on ALL versions.
        //
        // [NavTitleGestureTruncation 2026-07-24] iOS 19+ previously left the
        // titleView unconstrained, letting the system principal toolbar decide
        // the truncation width from whatever space the left/right bar items
        // leave. During an INTERACTIVE pop (edge-swipe), the system snapshots
        // the nav bar and interpolates bar-item positions; at some drag/cancel
        // frames the principal's available width widens, so lineLimit(1) stops
        // truncating and a long title momentarily "un-truncates" to its full
        // form — a jarring jump when the user cancels the swipe. Pinning an
        // explicit, version-stable cap makes the truncation point identical in
        // the steady state and every transition frame, so the title never
        // pops to full. Does not affect normal-length titles (they're under
        // the cap). This is a MITIGATION of the system snapshot behavior, not
        // a fix of it: the top-crop of the snapshot itself is UIKit-level and
        // out of SwiftUI's reach.
        let cap = max(120, UIScreen.main.bounds.width - 140)
        if #available(iOS 19, *) {
            titleView
                .frame(maxWidth: cap)
        } else {
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                titleView
                    .frame(maxWidth: cap)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity)
        }
    }

    /// [T-ios-navbar-toolbar-host] Everything the toolbar DISPLAYS (principal
    /// title + trailing menu), snapshotted as one Equatable key gating the
    /// whole ToolbarContent rebuild.
    private var chatToolbarKey: ChatToolbarKey {
        ChatToolbarKey(
            navTitle: navTitleKey,
            messagesEmpty: vm.messages.isEmpty,
            hasSession: vm.sessionId != nil,
            isForcePulling: isForcePulling,
            iCloudSyncEnabled: iCloudSyncEnabled,
            memoryEnabled: vm.memoryEnabled,
            speakEnabled: cached.vm.speakEnabled,
            showEnhancedCacheToggle: cached.vm.showEnhancedCacheToggle,
            enhancedCacheEnabled: cached.vm.enhancedCacheEnabled,
            showFastModeToggle: activeModelSupportsFastMode,
            fastModeEnabled: codexFastModeEnabled
        )
    }

    /// [T-codex-fast-mode] True when the session's active model can use Fast
    /// Mode. Broadened per user request from Codex-OAuth-only to: ANY
    /// Responses-API provider + a gpt-family model. Concretely the request
    /// must travel the Responses API path — providerType .openAIResponses
    /// (any credential/base, e.g. sub2api relays, which pass service_tier
    /// through), or the genuine Codex OAuth route (.openAI + OAuth + no
    /// custom base) — and the model id must contain "gpt" (mirrors the
    /// official fast catalog: gpt-5.6-sol/terra/luna, gpt-5.5, gpt-5.4).
    private var activeModelSupportsFastMode: Bool {
        let display = SessionModelDisplay(store: configStore, draftGroupId: vm.initialGroupId)
        guard let (instance, modelId) = display.resolvedInstanceAndModel(for: vm.sessionId) else { return false }
        guard modelId.lowercased().contains("gpt") else { return false }
        if instance.providerType == .openAIResponses { return true }
        return instance.providerType == .openAI
            && instance.credentialType == .oauth
            && (instance.customBaseURL?.isEmpty ?? true)
    }

    /// [T-ios-navbar-principal-streaming-stability] Snapshot of everything
    /// titleView displays — must stay in lockstep with titleView's reads
    /// (see NavTitleKey doc). Cheap to compute per body pass.
    private var navTitleKey: NavTitleKey {
        #if DEBUG
        NavbarEvalStats.toolbarPass += 1
        #endif
        let display = SessionModelDisplay(store: configStore, draftGroupId: vm.initialGroupId)
        let resolved = display.resolvedDetail(for: vm.sessionId)
        let sessionTitle: String? = (titlePillSession?.title?.trimmingCharacters(in: .whitespacesAndNewlines))
            .flatMap { $0.isEmpty ? nil : $0 }
        return NavTitleKey(
            sessionTitle: sessionTitle,
            canEditTitle: titlePillSession != nil,
            soulName: soulName,
            modelName: display.displayName(for: vm.sessionId),
            isGroupBound: display.isGroupBound(for: vm.sessionId),
            showFastBolt: codexFastModeEnabled && activeModelSupportsFastMode,
            resolvedText: resolved.map { "\($0.providerLabel) · \($0.modelName)" },
            hasBinding: vm.sessionId != nil && configStore.binding(for: vm.sessionId!) != nil,
            hasProviders: !configStore.instances.isEmpty,
            showThinkingBadge: !vm.availableThinkingLevels.isEmpty
                && (vm.currentThinkingLevel.isEnabled || vm.currentModelSupportsReasoning),
            thinkingLevelName: vm.currentThinkingLevel.displayName,
            fallbackTrigger: vm.fallbackTrigger,
            fallbackPulse: fallbackPulseOpacity,
            titleLocked: titleIsVisuallyLocked
        )
    }

    private var titleView: some View {
        let display = SessionModelDisplay(store: configStore, draftGroupId: vm.initialGroupId)
        let modelName = display.displayName(for: vm.sessionId)
        let isGroupBound = display.isGroupBound(for: vm.sessionId)
        let resolved = display.resolvedDetail(for: vm.sessionId)
        let hasBinding = vm.sessionId != nil && configStore.binding(for: vm.sessionId!) != nil
        let hasProviders = !configStore.instances.isEmpty

        let isAuthed = hasBinding || hasProviders

        let legacyLayout = !ProcessInfo.processInfo.isiOSAppOnMac && {
            if #available(iOS 26, *) { return false }
            return true
        }()

        // Use the current session's title in place of the default name
        // when one exists (auto-generated or user-renamed). Tap opens the
        // same SessionEditSheet used from the home screen so users can
        // rename / re-categorize without leaving the chat. Falls back to
        // the SOUL.md `name` (or "Minis") for draft sessions or before a
        // title has been generated.
        let sessionTitle: String? = (titlePillSession?.title?.trimmingCharacters(in: .whitespacesAndNewlines))
            .flatMap { $0.isEmpty ? nil : $0 }
        let canEditTitle = titlePillSession != nil

        // [NavTitleClip 2026-05-16] On iOS 26 the principal toolbar
        // container clipped the top of the title and the bottom of the
        // resolved-detail subtitle when a 3-line stack was used. Adding
        // a small top inset shifts the whole VStack downward into the
        // visible band; the toolbar happily renders it without
        // truncation. Legacy (iOS < 26) layout already used a -6 stack
        // spacing trick and doesn't need the inset.
        //
        // [NavTitleDescenderClip 2026-05-18] Tighten the outer VStack
        // from 0 to -2pt on iOS 26 so the three rows pack more closely
        // and free up vertical room for descenders ("g", "p", "y") on
        // the resolved-detail line (e.g. "Fake Hang 1" was clipping the
        // final "1" / "g" baseline at the navbar bottom). -2pt is the
        // sweet spot: overlap is invisible at 9pt body text but recovers
        // ~2pt of descender clearance below the third row.
        // [T-navbar-title-clip 2026-05-21] After shrinking the legacy
        // title to 13pt the stack was tall enough to clear the top of
        // the navbar but visually it now sat *too high* — the title
        // row clipped at the status-bar edge while the model-name row
        // had a tall empty gap below it before the navbar's bottom
        // separator. Root cause: SwiftUI doesn't strictly center the
        // `.principal` item on iOS 16-18; it baseline-aligns to the
        // navigation bar's title text baseline. Padding the OUTER
        // stack downward (`.padding(.top, …)` on the VStack itself)
        // pushes the whole title region down within the band without
        // changing its rendered height. Legacy-only — iOS 26 already
        // sits correctly in the liquid-glass band.
        // [T-navbar-title-model-gap 2026-07-18] Legacy spacing -8 → -3. The
        // 2026-05-20 tightening overlapped row 2 into the title's line box by
        // 8pt — more than the 13pt font's entire descender zone (~3.1pt), so
        // any title containing g/p/y/z visually fused with the model pill
        // (only descender-less titles like "Minis" hid it). -3 keeps a ~2pt
        // visible gap for descender titles. HEIGHT-NEUTRAL: the 5pt spent
        // here is reclaimed inside row 2 (provider-row bottom clearance
        // 4 → 1 and group vertical padding 3 → 2 on legacy), so the stack's
        // total height — the thing the May-18/19/20 clip saga fought — is
        // unchanged and the top-clip fix does not regress.
        return VStack(spacing: legacyLayout ? -3 : -2) {
            Button {
                if let s = titlePillSession { titlePillEditSession = s }
            } label: {
                // [T-navbar-title-size 2026-05-18] iOS 18 and below render
                // the principal toolbar in a tighter band than iOS 26's
                // liquid-glass navbar — long CJK titles (e.g. a
                // "Download and merge Twitter video" style CJK string)
                // overflowed at 16pt on iPhone Mini/SE and
                // wrapped under the trailing "…" button. Drop one point
                // on legacy iOS so they fit cleanly; keep the iOS 26
                // size unchanged.
                Text(sessionTitle ?? soulName)
                    // [T-navbar-title-size 2026-05-18 / -19 / -20] iOS 16-18
                    // principal toolbar is a fixed ~44pt band sitting just
                    // below the status bar with very little reserved padding.
                    // A 3-line title stack at 16pt/.caption2/9pt overflowed
                    // the band's top against the status-bar / Dynamic Island
                    // cutoff (a tall CJK glyph clipped its top, then "?"
                    // appeared because the glyph rect was outside the navbar
                    // viewport). Iterative tightening:
                    //   2026-05-18: 16 → 15pt + `padding(.top, 2)`
                    //   2026-05-19: 15 → 13.5pt + `minimumScaleFactor(0.85)`
                    //                 + `fixedSize(...)` + `padding(.top, 3)`
                    //   2026-05-20: 13.5 → 13pt + drop padding(.top) to 0 +
                    //                 outer VStack spacing -6 → -8. Earlier
                    //                 attempts to push the stack DOWN with
                    //                 more padding.top failed because SwiftUI
                    //                 vertically CENTERS the .principal item
                    //                 in the navbar — extra height clips
                    //                 BOTH top and bottom equally, not just
                    //                 the bottom. Net result: shrink the
                    //                 total stack height instead, so it
                    //                 naturally has breathing room above
                    //                 and below.
                    // iOS 26 path unchanged (16pt works inside liquid-glass).
                    .font(.system(size: legacyLayout ? 13 : 16, weight: .semibold))
                    .minimumScaleFactor(legacyLayout ? 0.85 : 1.0)
                    .foregroundStyle(ChatColors.primaryText)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .fixedSize(horizontal: false, vertical: true)
                    // [NavTitleTopClip 2026-07-23] Was 4pt on iOS 26 to shove
                    // the title into the visible band, but combined with the
                    // now-centered 46pt frame it pushed the title's top past
                    // the clip line again. 4 → 2: the centered frame already
                    // positions the stack correctly, and 2pt keeps a little
                    // breathing room above the semibold cap height without
                    // re-introducing the top crop.
                    .padding(.top, legacyLayout ? 0 : 2)
                    .onReceive(NotificationCenter.default.publisher(for: .soulMdChanged)) { _ in
                        let n = SoulStore.cachedMetadata.name
                        soulName = n.isEmpty ? "Minis" : n
                    }
            }
            .buttonStyle(.plain)
            .disabled(!canEditTitle)

            // [T-navbar-group-provider-gap 2026-07-25] Legacy row gap 1 → -1:
            // on iOS 16-18 the provider·model line (row 2) sat flush against
            // the navbar band's bottom edge (field screenshot: group name and
            // row 2 had a visibly larger gap than row 2 had bottom clearance).
            // Tightening ONLY the group↔provider gap moves row 2 up inside
            // the band, giving the last line breathing room from the bottom.
            // -1 still leaves ~2pt visual space (caption2's descender zone +
            // the 9pt row's cap headroom); the calibrated title↔group overlap
            // (outer -3) and the group pill's vertical padding are untouched.
            // iOS 26 keeps spacing 1 — the liquid-glass band has no crowding.
            VStack(spacing: legacyLayout ? -1 : 1) {
                HStack(spacing: 4) {
                    Button {
                        showModelPicker = true
                    } label: {
                        HStack(spacing: 4) {
                            Circle()
                                .fill(isAuthed ? Color.green : Color.orange)
                                .frame(width: 6, height: 6)
                            if isGroupBound {
                                Image(systemName: "square.stack.3d.up")
                                    .font(.system(size: 8))
                                    .foregroundStyle(ChatColors.tertiaryText)
                            }
                            Text(modelName)
                                .font(.caption2)
                                .foregroundStyle(ChatColors.secondaryText)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 8, weight: .semibold))
                                .foregroundStyle(ChatColors.tertiaryText)
                        }
                        .layoutPriority(-1)
                    }
                    .buttonStyle(.plain)
                    // [T-ios-voiceover-labels] This label is assembled from a
                    // status dot, an optional group glyph, the model name and a
                    // chevron. Left alone VoiceOver reads those as disconnected
                    // fragments ("square stack 3d up", "chevron down"), so
                    // combine the row into one element and state what it is.
                    // The connection state and group binding are carried as the
                    // VALUE rather than folded into the label, so VoiceOver
                    // keeps them distinguishable from the control's name.
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text("Model", comment: "VoiceOver label for the model picker button"))
                    .accessibilityValue(Text(
                        isGroupBound
                            ? (isAuthed
                                ? AppLocalized("\(modelName), group, signed in", comment: "VoiceOver value: model picker, group-bound, authenticated")
                                : AppLocalized("\(modelName), group, not signed in", comment: "VoiceOver value: model picker, group-bound, not authenticated"))
                            : (isAuthed
                                ? AppLocalized("\(modelName), signed in", comment: "VoiceOver value: model picker, authenticated")
                                : AppLocalized("\(modelName), not signed in", comment: "VoiceOver value: model picker, not authenticated"))
                    ))
                    .accessibilityHint(Text("Opens the model picker", comment: "VoiceOver hint for the model picker button"))
                    .accessibilityAddTraits(.isButton)
                }

                if let detail = resolved {
                    // Row 2: "provider · model" + the thinking-level badge as a
                    // SEPARATE tappable sibling (not nested in the model-picker
                    // Button) so the badge opens ThinkingLevelSheetView while the
                    // text still opens the model picker — mirroring how row 1's
                    // model-name Button and badge were laid out. The model name
                    // truncates first (layoutPriority(-1) + lineLimit(1)); the
                    // badge keeps its intrinsic size and always shows in full.
                    HStack(spacing: 4) {
                        // [T-codex-fast-mode] Circular-background ⚡ badge in
                        // front of the resolved model line (row 3) while Fast
                        // Mode is active (Codex OAuth only). User-requested
                        // placement: this row names the actual model; row 2
                        // may show a group name instead.
                        if codexFastModeEnabled && activeModelSupportsFastMode {
                            // Sized to the row's 9pt text cap height so the
                            // badge reads as an inline glyph, not a button.
                            Image(systemName: "bolt.fill")
                                .font(.system(size: 5, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 9, height: 9)
                                .background(Circle().fill(Color.orange))
                                // Match the row text's descender clearance so
                                // the badge centers on the same visual band.
                                .padding(.bottom, legacyLayout ? 1 : 4)
                        }
                        Button {
                            showModelPicker = true
                        } label: {
                            Text("\(detail.providerLabel) · \(detail.modelName)")
                                .font(.system(size: 9))
                                .foregroundStyle(ChatColors.secondaryText)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                // [NavTitleClip 2026-05-16] Reserve clearance
                                // under the resolved-detail line so the final
                                // glyph descenders ("g","p","y") don't touch
                                // the navbar's bottom safe-area cutoff on
                                // iOS 26. 2 → 4pt: 2 wasn't enough when text
                                // included descenders ("Fake Hang 1" clipped
                                // the lowered "1" stroke).
                                // [T-navbar-title-model-gap] Legacy needs only
                                // 1pt — the 4pt clearance is an iOS 26
                                // liquid-glass-band requirement; on 16-18 it
                                // was pure height waste, now traded into the
                                // title→model gap (see the outer VStack).
                                .padding(.bottom, legacyLayout ? 1 : 4)
                                // Force vertical sizing to fit the full
                                // glyph including descender so the line's
                                // own frame doesn't crop before the navbar
                                // gets a chance to lay it out.
                                .fixedSize(horizontal: false, vertical: true)
                                .layoutPriority(-1)
                        }
                        .buttonStyle(.plain)
                        // Show the badge whenever thinking is enabled, and ALSO
                        // when it's Off but the active model supports deep
                        // thinking — the icon + "Off" pill is then a discoverable
                        // entry point for turning it on (tap opens the level
                        // sheet). The Off pill is gated on
                        // `currentModelSupportsReasoning` so non-reasoning models
                        // don't grow a dead toggle; an enabled level still shows
                        // unconditionally (user may have opted in on an
                        // unknown-capability model).
                        if !vm.availableThinkingLevels.isEmpty,
                           vm.currentThinkingLevel.isEnabled || vm.currentModelSupportsReasoning {
                            thinkingLevelBadge
                                .fixedSize()
                                // Match the model-name line's descender
                                // clearance so the badge sits on the same
                                // baseline band and isn't clipped at the
                                // navbar's bottom cutoff.
                                // [T-navbar-title-model-gap] Match the
                                // provider text's legacy clearance.
                                .padding(.bottom, legacyLayout ? 1 : 4)
                        }
                    }
                }
            }
            .padding(.horizontal, 8)
            // [T-navbar-title-model-gap] 3 → 2 on legacy: part of the
            // height-neutral trade that funds the wider title→model gap.
            .padding(.vertical, legacyLayout ? 2 : 3)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.red.opacity(0.35 * fallbackPulseOpacity))
            )
            .onChange(of: vm.fallbackTrigger) { _ in
                // 3× pulse: fade in then out, repeated 3 times
                fallbackPulseOpacity = 0
                withAnimation(.easeInOut(duration: 0.35)) {
                    fallbackPulseOpacity = 1
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    withAnimation(.easeInOut(duration: 0.35)) { fallbackPulseOpacity = 0 }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                    withAnimation(.easeInOut(duration: 0.35)) { fallbackPulseOpacity = 1 }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.05) {
                    withAnimation(.easeInOut(duration: 0.35)) { fallbackPulseOpacity = 0 }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                    withAnimation(.easeInOut(duration: 0.35)) { fallbackPulseOpacity = 1 }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.75) {
                    withAnimation(.easeInOut(duration: 0.35)) { fallbackPulseOpacity = 0 }
                }
            }
        }
        // [T-navbar-title-clip 2026-05-21] Nudge the whole legacy title
        // stack down so the first row clears the status-bar / Dynamic
        // Island band. iOS 16-18 baseline-aligns the `.principal` item
        // to the toolbar title baseline — adding top padding on the
        // OUTER stack shifts its rendered position downward without
        // resizing any individual row. iOS 26 leaves at 0 (liquid
        // glass centers correctly).
        .padding(.top, legacyLayout ? 6 : 0)
        // iOS 26+: fix height to match liquid-glass navbar; older OS: let it size naturally
        .modifier(NavTitleFrameModifier())
        // [T-navbar-title-fontsize] Lock the entire principal-toolbar
        // title stack to the standard (.large) Dynamic Type size. Users
        // with system / app font scaling cranked up were having the
        // model name + "provider · model" subtitle overflow the navbar
        // band and clip. The numeric sizes used above (16/.caption2/9)
        // still scale with Dynamic Type by default; clamping the
        // environment here freezes them at the design baseline without
        // having to rewrite every Text modifier individually.
        .dynamicTypeSize(.large)
        // Blur the title (and model/provider subtitle) while the session
        // is locked, mirroring the chat body's SessionLockGateOverlay.
        // `SessionLockStore.isVisuallyLocked` is the single source of
        // truth — the same helper drives the gate overlay and the chat
        // list row, so all three sites lock and unlock in lockstep.
        .blur(radius: titleIsVisuallyLocked ? 8 : 0)
        .allowsHitTesting(!titleIsVisuallyLocked)
        .animation(.easeInOut(duration: 0.15), value: titleIsVisuallyLocked)
    }

    @ViewBuilder
    private var thinkingLevelBadge: some View {
        let level = vm.currentThinkingLevel
        // Sits on nav-bar row 2 (the 9pt "provider · model" line). Kept a
        // touch smaller than that text (8pt / 6px icon) so it stays the most
        // compact label in the title and never larger than the "is thinking…"
        // intensity pill in the message list.
        //
        // Also rendered when the level is Off (reasoning-capable model with
        // thinking disabled): the pill then reads icon + "Off" as a tap target
        // for enabling deep thinking. Dim the icon in that state — matches the
        // 0.4 Off-row convention in ThinkingLevelSheetView.
        HStack(spacing: 2) {
            Image("ThinkingIcon")
                .resizable()
                .frame(width: 6, height: 6)
                .opacity(level.isEnabled ? 1.0 : 0.4)
            Text(level.displayName)
                .font(.system(size: 8, weight: .medium))
        }
        .foregroundStyle(ChatColors.secondaryText)
        .padding(.horizontal, 4)
        .padding(.vertical, 1)
        .background(Capsule().fill(Color.secondary.opacity(0.10)))
        .onTapGesture {
            showThinkingLevelSheet = true
        }
        .sheet(isPresented: $showThinkingLevelSheet) {
            ThinkingLevelSheetView(
                currentLevel: level,
                availableLevels: vm.availableThinkingLevels,
                onSelect: { newLevel in
                    vm.setThinkingLevel(newLevel)
                    showThinkingLevelSheet = false
                }
            )
            .presentationDetents([.medium])
        }
    }

    /// True when the current session's navbar title must render blurred —
    /// observed via `@ObservedObject lockStore` so toggles to the lock
    /// state push a re-render of the toolbar synchronously with the gate.
    private var titleIsVisuallyLocked: Bool {
        guard let sid = vm.sessionId, !sid.isEmpty else { return false }
        return lockStore.isVisuallyLocked(sid)
    }

    // MARK: - Session Title Snapshot

    private func refreshTitlePillSession() {
        guard let sid = vm.sessionId else {
            titlePillSession = nil
            return
        }
        // ChatStore is an actor — must await off the main actor. Bouncing
        // back to the main actor before mutating @State avoids the strlen /
        // SQLite data race that crashed when called synchronously from the
        // view body (concurrent with sync engine writes).
        Task {
            let next = await ChatStore.shared.getSession(sid)
            await MainActor.run {
                guard sid == vm.sessionId else { return }
                if next?.id != titlePillSession?.id
                    || next?.title != titlePillSession?.title
                    || next?.category != titlePillSession?.category {
                    // Direct replace — animating the toolbar title makes the
                    // old "Minis" string slide before the real title swaps
                    // in, which looks broken. SwiftUI's default crossfade
                    // for non-animated text changes is what we want.
                    titlePillSession = next
                }
            }
        }
    }

    // MARK: - Kernel Boot Overlay

    @ViewBuilder
    private var kernelBootOverlay: some View {
        switch vm.kernelStatus {
        case .booting:
            ZStack {
                ChatColors.background
                    .ignoresSafeArea()
                VStack(spacing: 16) {
                    // Same three-dot language as SessionLoadingCard — this
                    // overlay can appear right after the loading card on a
                    // cold entry, so a system spinner here read as "the old
                    // 菊花 came back".
                    LoadingDotsView(dotSize: 9, color: ChatColors.secondaryText)
                        .frame(height: 20)
                    Text("Booting Kernel")
                        .font(.subheadline)
                        .foregroundStyle(ChatColors.secondaryText)
                }
            }
            .transition(.opacity)
        case .failed(let msg):
            ZStack {
                ChatColors.background
                    .ignoresSafeArea()
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.red)
                    Text(msg)
                        .font(.subheadline)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                }
            }
            .transition(.opacity)
        default:
            EmptyView()
        }
    }

    // MARK: - Error Banner

    private func errorBanner(_ error: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.caption)
                .foregroundStyle(.red)
            Text(error)
                .font(.caption)
                .foregroundStyle(.red)
                .lineLimit(2)
            Spacer()
            Button { vm.errorMessage = nil } label: {
                Image(systemName: "xmark")
                    .font(.caption)
                    .foregroundStyle(ChatColors.secondaryText)
            }
            // [T-ios-voiceover-labels] Otherwise announced as "xmark".
            .accessibilityLabel(Text("Dismiss error", comment: "VoiceOver label for the button that dismisses the error banner"))
        }
        .contentShape(Rectangle())
        .onLongPressGesture {
            UIPasteboard.general.string = error
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.red.opacity(0.12))
    }

    #if DEBUG
    /// Build a JSON representation of the full agent conversation history and copy it.
    private func copySessionDataToClipboard() {
        Task {
            guard let sid = vm.sessionId else {
                UIPasteboard.general.string = "(no session)"
                return
            }
            let rawMessages = await ChatStore.shared.loadMessages(sessionId: sid)
            var entries: [[String: Any]] = []
            for msg in rawMessages {
                var partsJson: [[String: Any]] = []
                for part in msg.parts {
                    switch part {
                    case .text(let text):
                        partsJson.append(["type": "text", "text": text])
                    case .toolUse(let tu):
                        // Parse input JSON string back to dict for clean output
                        let inputObj = (try? JSONSerialization.jsonObject(
                            with: Data(tu.input.utf8))) ?? tu.input
                        partsJson.append(["type": "tool_use", "id": tu.toolUseId,
                                          "name": tu.name, "input": inputObj])
                    case .toolResult(let tr):
                        partsJson.append(["type": "tool_result", "tool_use_id": tr.toolUseId,
                                          "output": tr.output, "is_error": !tr.success])
                    case .mediaRef(let ref):
                        partsJson.append(["type": "media", "mime": ref.mimeType,
                                          "file": ref.originalFileName ?? ref.relativePath])
                    }
                }
                var entry: [String: Any] = [
                    "role": msg.role.rawValue,
                    "parts": partsJson,
                    "created": ISO8601DateFormatter().string(from: msg.createdAt)
                ]
                if let usage = msg.tokenUsage {
                    entry["usage"] = [
                        "input_tokens": usage.inputTokens,
                        "output_tokens": usage.outputTokens,
                        "cache_creation_tokens": usage.cacheCreationTokens,
                        "cache_read_tokens": usage.cacheReadTokens
                    ]
                }
                entries.append(entry)
            }
            if let data = try? JSONSerialization.data(withJSONObject: entries,
                                                      options: [.prettyPrinted, .sortedKeys]),
               let str = String(data: data, encoding: .utf8) {
                UIPasteboard.general.string = str
            }
        }
    }
    #endif

    // MARK: - Messages

    private var messagesArea: some View {
        ZStack {
            CollectionViewMessageListV3(
                vm: vm,
                inputFocused: inputFocused,
                onRetryMessage: { vm.retryFromMessage($0); vm.forceScrollToBottom.send() },
                onRetryLast: { vm.retry(); vm.forceScrollToBottom.send() },
                // [T-ios-assistant-header-open-soul] Routed through the same
                // handler every `minis://` link in the transcript uses, so the
                // identity row and an agent-authored
                // `[Soul](minis://settings/soul)` link land identically —
                // no second navigation path to keep in sync.
                onOpenSoulSettings: {
                    guard let url = URL(string: "minis://settings/soul") else { return }
                    _ = handleMinisURLTap(url)
                },
                onEdit: { [self] msgId in
                    vm.editMessage(msgId)
                    inputFocused = true
                },
                onDeleteFrom: { vm.deleteFromMessage($0) },
                onWithdraw: { vm.withdrawQueuedMessage($0) },
                onResume: { vm.resume(); vm.forceScrollToBottom.send() },
                onStop: { vm.stopCurrentCommand() },
                onCompact: { msgId in compactConfirmMessageId = msgId },
                onRevertCompact: { Task { await vm.revertCompact() } },
                onForceSync: { [self] in
                    guard let sid = vm.sessionId else { return }
                    Task {
                        let count = await ChatStore.shared.forceSyncSession(sid)
                        if #available(iOS 17.0, *), count > 0 {
                            SyncCore.shared.scheduleSend(delay: 1)
                        }
                    }
                },
                onScreenshotImage: { image in
                    screenshotPreview = ChatScreenshotPreview(image: image)
                },
                maxContentWidth: maxContentWidth ?? 0,
                floatingBarHeight: floatingBarHeight,
                inputBarHeight: inputBarHeight
            )
            // Empty/loading overlay for tap-to-dismiss-keyboard.
            // Placed BEFORE the directory timeline in the ZStack so the
            // timeline can sit on top and receive its own taps (folder-card
            // arrow buttons). Taps that miss the timeline content fall
            // through to this background overlay.
            if vm.messages.isEmpty || vm.isLoadingSession {
                Color.clear
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture { inputFocused = false }
            }
            // Empty-chat onboarding tip: a single card explaining the
            // per-session vs cross-session /var/minis/ layout. Only shown
            // on a true draft (vm.sessionId == nil) — opening an existing
            // session would otherwise flash the card during the brief
            // window before its messages load. Returning users with any
            // prior chat skip the tip even on a fresh draft (so it's a
            // genuine first-launch hint).
            if vm.sessionId == nil
                && vm.messages.isEmpty
                && !vm.isLoadingSession
                && (totalSessionCount ?? Int.max) == 0 {
                EmptyChatDirectoryTimeline(onBrowse: { showFileBrowser = true })
                    .padding(.horizontal, 24)
            }
        }
        .overlay(alignment: .bottom) {
            // Open the overlay only when at least one button will actually
            // render — i.e. the gate is the OR of the two buttons' own
            // conditions. Otherwise an in-between state (e.g. far from top but
            // within one screen of the bottom, at rest) would mount an empty
            // VStack.
            if !vm.messages.isEmpty && !vm.isNearBottom {
                VStack(spacing: 10) {
                    // Up-button: walk back one user turn at a time. Visible when
                    // off the bottom AND there's still an earlier turn to jump to
                    // (isAtFirstTurn == false). Once the user has walked all the
                    // way up to the first turn, it hides — nothing further up.
                    if !vm.isAtFirstTurn {
                        Button {
                            vm.forceScrollToTop.send()
                        } label: {
                            // arrow.up.to.line: "jump to a top anchor" reads truer
                            // for the turn-walk / scroll-to-top action than a plain
                            // chevron, and distinguishes it from the down button's
                            // chevron.down. Available since iOS 16 (both our legacy
                            // and iOS 26 floors).
                            scrollFloatingButtonLabel("arrow.up.to.line")
                        }
                        .transition(.opacity.combined(with: .scale(scale: 0.8)))
                    }
                    // Scroll to bottom — ORIGINAL behavior: shown whenever not
                    // near the bottom (within the 20pt nearBottom threshold), so
                    // it appears as soon as you leave the bottom.
                    Button {
                        vm.forceScrollToBottom.send()
                    } label: {
                        scrollFloatingButtonLabel("chevron.down")
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.8)))
                }
                .padding(.trailing, 4)
                .frame(maxWidth: maxContentWidth ?? .infinity, alignment: .trailing)
                .padding(.horizontal, 12)
                .padding(.bottom, inputBarHeight + (hasFloatingPreview ? 80 : 12))
                .animation(.easeInOut(duration: 0.2), value: vm.isNearBottom)
                .animation(.easeInOut(duration: 0.2), value: vm.isAtFirstTurn)
                .animation(.easeInOut(duration: 0.2), value: hasFloatingPreview)
                // The capsule must not cover these floating scroll-jump buttons.
                .capsuleProtectedFrame("scrollButtons")
            }
        }
        // [T-browser-download-ux-v2] Floating download button — same visual
        // family as the scroll buttons, stacked ABOVE their slot (the scroll
        // group can show up to two 36pt buttons + spacing, so offset by 92pt)
        // so the two never overlap regardless of scroll state. Hidden while
        // the session has no download records.
        .overlay(alignment: .bottom) {
            BrowserDownloadFloatingButton(sessionId: vm.sessionId ?? "") {
                showDownloadsPanel = true
            }
            .padding(.trailing, 4)
            .frame(maxWidth: maxContentWidth ?? .infinity, alignment: .trailing)
            .padding(.horizontal, 12)
            .padding(.bottom, inputBarHeight + (hasFloatingPreview ? 80 : 12) + 92)
            .animation(.easeInOut(duration: 0.2), value: hasFloatingPreview)
            .capsuleProtectedFrame("downloadButton")
        }
    }

    /// Shared label style for the floating scroll buttons (up / down), matching
    /// the original scroll-to-bottom button look.
    private func scrollFloatingButtonLabel(_ systemName: String) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.secondary)
            .frame(width: 36, height: 36)
            .background { ScrollToBottomBackground().clipShape(Circle()) }
            // Border + shadow give the near-opaque disc its edge on a white
            // page — at 0.25/0.12 the button had no readable outline over
            // plain reply text. [T-ios-scrollbtn-invisible-lightmode]
            .overlay(Circle().stroke(Color.gray.opacity(0.35), lineWidth: 0.5))
            .shadow(color: .black.opacity(0.18), radius: 5, y: 2)
    }

    /// Background color for the scroll-to-bottom button.
    /// [T-ios-scrollbtn-invisible-lightmode] Light mode was
    /// systemBackground.opacity(0.5) — a half-transparent WHITE disc over the
    /// white reply background, leaving only the hairline border + secondary
    /// glyph: effectively invisible whenever the content behind the button is
    /// plain text (the top of a conversation especially). Device screenshots
    /// confirmed the buttons were mounted and tappable while unreadable.
    /// Use a near-opaque disc in light mode (dark was already fine at 0.8).
    private struct ScrollToBottomBackground: View {
        @Environment(\.colorScheme) private var colorScheme
        var body: some View {
            Color(.systemBackground).opacity(colorScheme == .dark ? 0.8 : 0.92)
        }
    }

    // MARK: - Floating Tool Preview

    @ViewBuilder
    private var floatingToolPreview: some View {
        let allToolBlocks = vm.messages
            .filter { $0.role == .assistant && !$0.isCompactedHistory }
            .flatMap { $0.blocks.filter { $0.toolStatus != nil } }
        if !allToolBlocks.isEmpty {
            FloatingToolBar(toolBlocks: allToolBlocks, toolSnapshots: vm.toolSnapshots, browserPool: vm.browserTabPool, onBrowserTakeover: {
                vm.browserTakeoverActive = true
            }, onTakeoverDone: {
                vm.resumeFromBrowserTakeover()
            })
                .frame(maxWidth: maxContentWidth)
                .padding(.horizontal, 12)
                // [T-ios-geometry-observer-crash] onGeometryChange replaces the
                // GeometryReader+onAppear+onChange scaffold: writing state from
                // onChange(of: geo.*) inside the geometry-observer path re-drives
                // layout and trips a precondition on the iOS 18 async renderer
                // (ViewGraphGeometryObservers.needsUpdate SIGTRAP). The action
                // also fires with the initial value, covering the old onAppear.
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.size.height
                } action: { newH in
                    floatingBarHeight = newH
                }
                .onDisappear {
                    floatingBarHeight = 0
                }
        }
    }

    /// Whether the floating tool preview is visible.
    private var hasAnyToolBlocks: Bool {
        vm.messages.contains(where: {
            $0.role == .assistant && !$0.isCompactedHistory && $0.blocks.contains { $0.toolStatus != nil }
        })
    }

    // MARK: - Floating Speech Button

    /// Floating read-replies player control (compact ↔ expanded capsule), shown
    /// only when read-replies is enabled.
    @ViewBuilder
    // The read-replies speech capsule is now a SINGLE app-root instance (mounted
    // in MinisApp beside AudioPiPCapsule) so it persists across chat → home. No
    // per-session copy is rendered here anymore.
    private var floatingSpeechButton: some View { EmptyView() }

    /// Height the floating scroll-jump button stack occupies right now (0 when
    /// hidden), used to lift the speech control so the two never overlap. Mirrors
    /// the visibility conditions of the jump-button overlay above.
    private var scrollJumpButtonsHeight: CGFloat {
        guard !vm.messages.isEmpty else { return 0 }
        // Up and down buttons now share one condition (!isNearBottom): both are
        // present together or both hidden.
        guard !vm.isNearBottom else { return 0 }
        // Sized for the TALLEST case (both buttons: 2×36 + 10 spacing = 82pt). The
        // control already rests +48pt above the buttons' baseline, so lift the
        // excess over that head start, plus a 10pt gap.
        let maxStack: CGFloat = 2 * 36 + 10
        return max(0, maxStack + 10 - 48)
    }

    // MARK: - Fork Banner (Read-Only Mode)

    #if DEBUG
    private var forkBanner: some View {
        VStack(spacing: 0) {
            Divider()
            HStack {
                Image(systemName: "eye")
                    .foregroundStyle(.secondary)
                Text("Read-only session from another device")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    guard let sid = sessionId, let devId = remoteDeviceId else { return }
                    Task {
                        if let forked = await SessionForkManager.shared.forkSession(
                            remoteSessionId: sid, remoteDeviceId: devId
                        ) {
                            // Navigate to the forked session
                            NotificationCenter.default.post(
                                name: .sessionDidCreate,
                                object: forked.id,
                                userInfo: ["navigate": true]
                            )
                        }
                    }
                } label: {
                    Label("Fork to Continue", systemImage: "arrow.branch")
                        .font(.subheadline.weight(.medium))
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial)
        }
    }
    #endif

    // MARK: - Input Bar

    // MARK: - Drag & Drop Import

    /// Handle items dropped onto the chat from outside the app (Files, Photos, Safari, etc.).
    /// Handle a minis:// or http(s):// URL tap forwarded from a cell-level
    /// view. Routes deep links and opens the appropriate file preview sheet
    /// (state lives on AIChatView so sheet animations play correctly).
    /// Route a tap on a markdown-embedded image into a paged gallery covering
    /// **every** inline image in every assistant message in the current
    /// session, in chronological order. The tapped image determines only
    /// the starting page — the user can swipe freely across the whole
    /// conversation's image history. The `messageId` carried by the tap
    /// notification narrows the starting-index lookup so that duplicate
    /// `src` values in different messages still resolve to the right page.
    /// Clears every fullScreenCover state this view owns. Called when
    /// a more important presentation (incoming share, WebApp deep link)
    /// is about to take over the screen — iOS only allows one
    /// fullScreenCover per host at a time, so without this the
    /// new content would silently fail to present.
    private func dismissAllOwnedImmersiveCovers() {
        if previewImageFile != nil { previewImageFile = nil }
        if imageGallery != nil { imageGallery = nil }
        if previewVideoFile != nil { previewVideoFile = nil }
        if fullBrowserURL != nil { fullBrowserURL = nil }
        if showTerminal { showTerminal = false }
        if showCamera { showCamera = false }
    }

    private func handleMarkdownImageTap(_ note: Notification) {
        guard let info = note.userInfo,
              let tappedMessageId = info[MarkdownImageTapRouter.messageIdKey] as? UUID,
              let tappedSource = info[MarkdownImageTapRouter.sourceURLKey] as? String
        else { return }

        // Collect every `![alt](src)` occurrence from every assistant
        // message's text blocks, in chronological order. Tool-call blocks
        // are skipped. Video/audio extensions are filtered out so the
        // gallery only contains still images.
        let imagePattern = try? NSRegularExpression(pattern: #"!\[([^\]]*)\]\(([^)\s]+)\)"#)
        struct MarkdownImageRef {
            let messageId: UUID
            let source: String
            let title: String
        }
        var refs: [MarkdownImageRef] = []
        for message in vm.messages {
            guard message.role == .assistant else { continue }
            for block in message.blocks {
                guard case .text = block.kind else { continue }
                let content = block.content as NSString
                let range = NSRange(location: 0, length: content.length)
                imagePattern?.enumerateMatches(in: block.content, options: [], range: range) { match, _, _ in
                    guard let match, match.numberOfRanges >= 3 else { return }
                    let src = content.substring(with: match.range(at: 2))
                    let alt = content.substring(with: match.range(at: 1))
                    let ext = (URL(string: src)?.pathExtension ?? (src as NSString).pathExtension).lowercased()
                    if minisVideoExtensions.contains(ext) || minisAudioExtensions.contains(ext) { return }
                    let title = alt.isEmpty ? ((src as NSString).lastPathComponent) : alt
                    refs.append(MarkdownImageRef(messageId: message.id, source: src, title: title))
                }
            }
        }
        guard !refs.isEmpty else { return }

        // Prefer an exact `(messageId, source)` match so duplicate src values
        // across different messages resolve correctly. Fall back to the first
        // source match, then to 0.
        let startIndex: Int = {
            if let i = refs.firstIndex(where: { $0.messageId == tappedMessageId && $0.source == tappedSource }) {
                return i
            }
            if let i = refs.firstIndex(where: { $0.source == tappedSource }) {
                return i
            }
            return 0
        }()

        let items: [GalleryItem] = refs.enumerated().map { (index, ref) in
            // Include index + messageId + fingerprinted canonical source
            // in the id so:
            //   - duplicate sources across messages stay distinct pages in
            //     TabView's ForEach identity, and
            //   - a rewrite of the underlying file (size/mtime change)
            //     produces a new id and invalidates the cached TabView page.
            // Canonicalize here so relative-path `src` values from the
            // regex scan are resolved the same way the inline markdown
            // renderer resolves them.
            let canonical = ImageAttachment.canonicalizeMarkdownImageSource(ref.source)
            let fpKey = minisMediaCacheKey(for: canonical)
            return GalleryItem(
                id: "\(index)|\(ref.messageId)|\(fpKey)",
                title: ref.title,
                load: { await Self.loadMarkdownImage(source: canonical, fingerprintKey: fpKey) }
            )
        }
        imageGallery = GalleryPresentation(items: items, startIndex: startIndex)
    }

    /// Load a markdown image source (already canonicalized to minis:// or
    /// http(s)://) off the main thread. Caches in the shared
    /// `NativeMediaImageCache` using a fingerprint-derived key so
    /// in-place rewrites of local files are picked up on the next render.
    private static func loadMarkdownImage(source: String, fingerprintKey: String) async -> UIImage? {
        let cacheKey = "gallery:\(fingerprintKey)"
        if let cached = NativeMediaImageCache.shared.image(for: cacheKey) {
            return cached
        }
        guard let url = URL(string: source) else { return nil }
        let img: UIImage?
        if url.scheme == "minis" {
            guard let fileURL = resolveMinisFileURLCached(url: url),
                  let data = try? Data(contentsOf: fileURL) else { return nil }
            img = downsampleImageData(data, maxPixelSize: 2048)
        } else {
            guard let (data, _) = try? await URLSession.shared.data(from: url) else { return nil }
            img = downsampleImageData(data, maxPixelSize: 2048)
        }
        if let img { NativeMediaImageCache.shared.set(img, for: cacheKey) }
        return img
    }

    private func handleMinisURLTap(_ url: URL) -> OpenURLAction.Result {
        if let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
            withAnimation { safariURL = url }
            return .handled
        }
        guard url.scheme == "minis" else { return .systemAction }

        // Terminal deep link: minis://open_terminal?init_command=...
        if url.host == "open_terminal" {
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
            let initCmd = comps?.queryItems?.first(where: { $0.name == "init_command" })?.value
            let dl = DeepLinkCoordinator.shared
            dl.terminalInitCommand = initCmd
            dl.showTerminal = true
            return .handled
        }
        // View deep links: minis://views/alarm
        if url.host == "views" && url.path == "/alarm" {
            DeepLinkCoordinator.shared.showAlarmList = true
            return .handled
        }
        // Any minis://settings/<path> goes through DeepLinkRouter so all
        // supported settings sub-paths (logs, providers, model-groups,
        // usage, skills, memory, storage, mounts, shared-folders,
        // appearance, background, about, permissions, environments,
        // rootfs, …) reach Settings instead of falling through to the
        // file-resource resolver below — which would treat e.g. `logs`
        // as a missing chat file and surface the "file not found" alert.
        if url.host == "settings" {
            DeepLinkRouter.handle(url: url, shareCoordinator: shareCoordinator)
            return .handled
        }
        if let fileURL = resolveMinisFileURL(url: url) {
            let ext = url.pathExtension.lowercased()
            withAnimation {
                if minisImageExtensions.contains(ext) {
                    previewImageFile = fileURL
                } else if minisVideoExtensions.contains(ext) {
                    previewVideoFile = fileURL
                } else if minisAudioExtensions.contains(ext) {
                    previewAudioFile = fileURL
                } else if minisMarkdownExtensions.contains(ext) {
                    previewMarkdownFile = fileURL
                } else if minisTextExtensions.contains(ext) {
                    previewTextFile = fileURL
                } else if minisHTMLExtensions.contains(ext) {
                    previewHTMLFile = fileURL
                } else if minisDocumentExtensions.contains(ext) {
                    previewDocumentFile = fileURL
                } else {
                    shareFile = fileURL
                }
            }
        } else {
            // File doesn't exist on disk — likely deleted, from a pruned
            // session, or iCloud sync hasn't brought it down yet. Surface
            // a dismissable alert so the tap isn't silently ignored.
            let name = url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent
            missingMinisFileName = name.isEmpty ? url.absoluteString : name
        }
        return .handled
    }

    private func handleDropProviders(_ providers: [NSItemProvider]) {
        minisLogger.info("[Drop] handleDropProviders called with \(providers.count) provider(s)")
        for (index, provider) in providers.enumerated() {
            let types = provider.registeredTypeIdentifiers
            minisLogger.info("[Drop] provider[\(index)] types=\(types) suggestedName=\(provider.suggestedName ?? "nil")")

            // Determine if this provider represents a file (vs. inline text).
            let hasFileType = types.contains { id in
                guard let ut = UTType(id) else { return false }
                return ut.conforms(to: .fileURL) || ut.conforms(to: .image)
                    || ut.conforms(to: .movie) || ut.conforms(to: .pdf)
                    || (ut.conforms(to: .data) && !ut.conforms(to: .plainText))
            }
            let hasFileName = provider.suggestedName.map {
                !($0 as NSString).pathExtension.isEmpty
            } ?? false

            minisLogger.info("[Drop] provider[\(index)] hasFileType=\(hasFileType) hasFileName=\(hasFileName)")

            guard hasFileType || hasFileName else {
                minisLogger.info("[Drop] provider[\(index)] SKIPPED — no file type or filename")
                continue
            }

            // 1. File URL (most common on iPad — files, images, videos, documents)
            let hasFileURL = provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
            let canLoadImage = provider.canLoadObject(ofClass: UIImage.self)
            minisLogger.info("[Drop] provider[\(index)] hasFileURL=\(hasFileURL) canLoadImage=\(canLoadImage)")

            if hasFileURL {
                provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier) { item, error in
                    if let error {
                        minisLogger.error("[Drop] provider[\(index)] loadItem fileURL error: \(error.localizedDescription)")
                        return
                    }
                    guard let data = item as? Data,
                          let url = URL(dataRepresentation: data, relativeTo: nil) else {
                        minisLogger.error("[Drop] provider[\(index)] loadItem fileURL failed to parse — item type: \(type(of: item))")
                        return
                    }
                    minisLogger.info("[Drop] provider[\(index)] fileURL loaded: \(url.path)")
                    DispatchQueue.main.async { vm.addFileAttachment(from: url) }
                }
                continue
            }
            // 2. Image not delivered as file URL (e.g. dragged from Photos)
            if canLoadImage {
                provider.loadObject(ofClass: UIImage.self) { item, error in
                    if let error {
                        minisLogger.error("[Drop] provider[\(index)] loadObject UIImage error: \(error.localizedDescription)")
                        return
                    }
                    guard let image = item as? UIImage else {
                        minisLogger.error("[Drop] provider[\(index)] loadObject UIImage returned nil")
                        return
                    }
                    minisLogger.info("[Drop] provider[\(index)] UIImage loaded: \(image.size)")
                    DispatchQueue.main.async { vm.addImageAttachment(image) }
                }
                continue
            }
            // 3. Any other file content — load via file representation
            if let contentType = types.first(where: { id in
                guard let ut = UTType(id) else { return false }
                return ut.conforms(to: .data)
            }) {
                minisLogger.info("[Drop] provider[\(index)] fallback loadFileRepresentation contentType=\(contentType)")
                provider.loadFileRepresentation(forTypeIdentifier: contentType) { url, error in
                    if let error {
                        minisLogger.error("[Drop] provider[\(index)] loadFileRepresentation error: \(error.localizedDescription)")
                        return
                    }
                    guard let url else {
                        minisLogger.error("[Drop] provider[\(index)] loadFileRepresentation returned nil URL")
                        return
                    }
                    let tmp = FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString.prefix(8) + "_" + url.lastPathComponent)
                    do {
                        try FileManager.default.copyItem(at: url, to: tmp)
                        minisLogger.info("[Drop] provider[\(index)] file copied to: \(tmp.path)")
                        DispatchQueue.main.async { vm.addFileAttachment(from: tmp) }
                    } catch {
                        minisLogger.error("[Drop] provider[\(index)] copyItem failed: \(error.localizedDescription)")
                    }
                }
                continue
            }
            minisLogger.info("[Drop] provider[\(index)] NO handler matched — dropped")
        }
    }

    /// Attachment "+" button.
    /// iOS 16's Menu has a vertical alignment bug inside HStack that causes it
    /// to sit higher than sibling buttons when the keyboard is up.
    /// Use confirmationDialog on iOS 16, Menu on iOS 17+.
    @ViewBuilder
    private var attachmentMenuButton: some View {
        // [T-ios-voiceover-labels] Labelled on the shared `icon` so both the
        // iOS 17 Menu branch and the iOS 16 confirmationDialog branch below
        // announce the same thing; otherwise VoiceOver reads "plus".
        let icon = Image(systemName: "plus")
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(ChatColors.secondaryText)
            .frame(width: 34, height: 34)
            .accessibilityLabel(Text("Add attachment", comment: "VoiceOver label for the attachment button"))
            .background(ChatColors.inputIconBg)
            .clipShape(Circle())
            .overlay(Circle().stroke(ChatColors.inputIconBorder, lineWidth: 0.5))

        if #available(iOS 17, *) {
            Menu {
                Button { showCamera = true } label: { Label("Take Photo", systemImage: "camera") }
                Button { showPhotoPicker = true } label: { Label("Choose Photos & Videos", systemImage: "photo.on.rectangle") }
                Button { showDocumentPicker = true } label: { Label("Add File", systemImage: "doc") }
            } label: {
                icon
            }
        } else {
            Button { showAttachmentMenu = true } label: {
                icon
            }
            .confirmationDialog("Add Attachment", isPresented: $showAttachmentMenu) {
                Button { showCamera = true } label: { Label("Take Photo", systemImage: "camera") }
                Button { showPhotoPicker = true } label: { Label("Choose Photos & Videos", systemImage: "photo.on.rectangle") }
                Button { showDocumentPicker = true } label: { Label("Add File", systemImage: "doc") }
            }
        }
    }

    /// Bottom toolbar under the text field (+ / edit-exit / mic / send).
    /// Returns AnyView to keep `inputBar`'s generic type compact; SwiftUI
    /// runtime demangle chokes on deep nested types otherwise.
    private var inputBottomRow: AnyView {
        let row = HStack(spacing: 12) {
            attachmentMenuButton
            slashMenuButton
            if vm.editingMessageIndex != nil { editExitButton }
            Spacer()
            // Mutually exclusive with editExitButton: while editing a past
            // message the exit capsule owns this row — showing both capsules
            // overflows the row and they render overlapped.
            if voiceInputActive, vm.editingMessageIndex == nil {
                readAloudToolbarToggle
                Spacer()
            }
            micButtonContainer
            sendButton
        }
        return AnyView(row)
    }

    /// "Read replies aloud" toggle shown centered in the toolbar during voice
    /// input (TTS on/off). Styled like the other secondary toolbar controls.
    private var readAloudToolbarToggle: some View {
        // Source of truth = vm.speakEnabled (also driven by the floating speaker's
        // tap-cycle / long-press-off), so this toggle reflects those changes too.
        // Three states, in lockstep with the global voice-output capsule:
        //  • disabled  (!isEnabled)        → gray, slashed speaker
        //  • muted     (isEnabled & muted) → accent, slashed speaker
        //  • active    (isEnabled & !muted)→ accent, wave speaker
        let on = voiceOutput.isEnabled
        let muted = voiceOutput.isMuted
        return Button {
            if on && !muted {
                // Active → mute (temporary silence, capsule stays visible).
                voiceOutput.isMuted = true
            } else if on && muted {
                // Muted → fully off (capsule hides).
                vm.speakEnabled = false
                VoiceOutputPreferences.isEnabled = false
            } else {
                // Off → turn on (resume last speed, un-muted).
                voiceOutput.isMuted = false
                vm.speakEnabled = true
                VoiceOutputPreferences.isEnabled = true
            }
        } label: {
            HStack(spacing: 5) {
                // Fixed-width icon slot so the size stays constant when the glyph
                // swaps between wave / slash.
                // [T-ios-voiceover-labels] The glyph only mirrors the on/off
                // state that the accessibilityValue below already announces,
                // and the row carries visible text — so it is pure decoration
                // for VoiceOver and would otherwise be read as
                // "speaker wave 2 fill".
                Image(systemName: (on && !muted) ? "speaker.wave.2.fill" : "speaker.slash.fill")
                    .font(.system(size: 12))
                    .frame(width: 16)
                    .accessibilityHidden(true)
                Text("Read replies", comment: "Voice TTS toggle (compact)")
                    .font(.subheadline)
            }
            .foregroundStyle(on ? Color.accentColor : .secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                Capsule().fill(on ? Color.accentColor.opacity(0.15)
                                  : Color.secondary.opacity(0.10))
            )
            .fixedSize()
        }
        .buttonStyle(.plain)
        // [T-ios-voiceover-labels] State goes in the VALUE, not the label:
        // folding "on"/"off" into the label would lose VoiceOver's own
        // "on/off" semantics and make the control's name change as it toggles.
        // Muted is a third state the glyph distinguishes visually, so it is
        // announced too rather than being flattened into "on".
        .accessibilityValue(Text(
            on ? (muted
                    ? AppLocalized("On, muted", comment: "VoiceOver value for the read-replies toggle when enabled but muted")
                    : AppLocalized("On", comment: "VoiceOver value for the read-replies toggle when enabled"))
               : AppLocalized("Off", comment: "VoiceOver value for the read-replies toggle when disabled")
        ))
        .accessibilityHint(Text("Toggles reading replies aloud", comment: "VoiceOver hint for the read-replies toggle"))
    }

    /// `/` button that opens the slash command menu.
    private var slashMenuButton: some View {
        Button {
            if vm.showSlashMenu {
                vm.dismissSlashMenu()
            } else {
                vm.showSlashMenuOverInput()
                inputFocused = true
            }
        } label: {
            Text("/")
                .font(.system(size: 18, weight: .semibold, design: .rounded))
                .italic()
                .foregroundStyle(ChatColors.secondaryText)
                .frame(width: 34, height: 34)
                .background(ChatColors.inputIconBg)
                .clipShape(Circle())
                .overlay(Circle().stroke(ChatColors.inputIconBorder, lineWidth: 0.5))
        }
    }

    /// "Exit Edit Mode" capsule shown while editing a past message.
    private var editExitButton: some View {
        Button {
            vm.cancelEdit()
        } label: {
            Text("Exit Edit Mode", comment: "Cancel message editing")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(ChatColors.secondaryText)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(ChatColors.inputIconBg)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(ChatColors.inputIconBorder, lineWidth: 0.5))
        }
    }

    /// Speech language badge shown only while recording.
    private var languageBadgeButton: some View {
        Button {
            speechManager.showLanguagePicker = true
        } label: {
            Text(speechManager.languageLabel)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(ChatColors.secondaryText)
                .frame(width: 34, height: 34)
                .background(ChatColors.inputIconBg)
                .clipShape(Circle())
                .overlay(Circle().stroke(ChatColors.inputIconBorder, lineWidth: 0.5))
        }
    }

    /// Mic button plus the attached language-picker sheet.
    private var micButtonContainer: some View {
        MicButton(speechManager: speechManager, inputFocused: $inputFocused, onTap: {
            if voiceInputActive {
                // Already in voice mode — the "T" button switches back to text.
                // [T-ios-voice-keyboard-text-carry] Keep the transcript: the
                // composer mirrors it, so clearing here would empty the input
                // box and lose the dictated text on the way back to keyboard.
                voiceVM.reset(clearTranscript: false)
                withAnimation(.easeInOut(duration: 0.2)) { voiceInputActive = false }
            } else {
                // Mark the composition as voice-assisted (committed to the
                // "voice" input-mode preference only at send time). Switch the
                // composer into inline voice mode — the single voice entry point.
                vm.voiceUsedInComposition = true
                // Switching text→voice opens the panel expanded this one time;
                // afterwards it resumes the remembered expand/compact state.
                VoiceModePreference.shared.enteredFromText = true
                withAnimation(.easeInOut(duration: 0.2)) { voiceInputActive = true }
            }
        }, isVoiceActive: voiceInputActive)
    }

    /// Send / Enqueue / Stop circular button.
    ///
    /// [T-ios-voiceover-labels] All three states are icon-only, and two of them
    /// (send / enqueue) use the SAME glyph, so without explicit labels
    /// VoiceOver reads "arrow up circle fill" for both and a blind user cannot
    /// tell what pressing it will do. Each branch therefore names its own
    /// action; the destructive one also carries a hint.
    @ViewBuilder
    private var sendButton: some View {
        if vm.isProcessing && canEnqueue {
            Button { performEnqueue() } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(ChatColors.sendButton)
            }
            .keyboardShortcut(.return, modifiers: .command)
            .accessibilityLabel(Text("Add to queue", comment: "VoiceOver label for the send button while a reply is generating"))
            .accessibilityHint(Text("Queues this message to send after the current reply finishes", comment: "VoiceOver hint for the queue button"))
        } else if vm.isProcessing {
            Button { vm.cancel() } label: {
                Image(systemName: "stop.circle.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(.red)
            }
            .accessibilityLabel(Text("Stop generating", comment: "VoiceOver label for the stop button"))
            .accessibilityHint(Text("Stops the reply that is being generated", comment: "VoiceOver hint for the stop button"))
        } else {
            Button { performSend() } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(canSend ? ChatColors.sendButton : ChatColors.sendButtonDisabled)
            }
            .disabled(!canSend)
            .keyboardShortcut(.return, modifiers: .command)
            .accessibilityLabel(Text("Send", comment: "VoiceOver label for the send button"))
        }
    }

    /// Either the multi-line text field or the audio waveform, depending on
    /// whether speech recording is active. Returning `AnyView` erases the
    /// nested `PastableTextView` generic so SwiftUI's runtime type metadata
    /// decoder doesn't recurse past its stack limit (see crash 2026-04-17
    /// where `AIChatView.inputBar.getter` blew the stack via
    /// `__swift_instantiateConcreteTypeFromMangledNameV2`).
    private var inputFieldOrWaveform: AnyView {
        let topPadding: CGFloat = (vm.attachments.isEmpty && vm.loadingVideoCount == 0) ? 16 : 11
        if voiceInputActive {
            return AnyView(
                // [voice-correction §6] Recent conversation turns, straight from the
                // already-loaded in-memory list (§12.1 forbids a DB query on this path),
                // budgeted by CorrectionContextBuilder.
                InlineVoiceInputView(
                    viewModel: voiceVM,
                    inputText: inputTextBinding,
                    onPasteImage: { image in vm.addImageAttachment(image) },
                    onPasteFile: { url in vm.addFileAttachment(from: url) },
                    conversationContext: { voiceCorrectionContext(from: vm.messages) }
                )
            )
        }
        if speechManager.state == .recording {
            // Transcript area grows with content up to a cap (~5 lines),
            // then scrolls internally. ScrollView is greedy in its scroll
            // axis, so we measure the Text's intrinsic height via a
            // GeometryReader preference and clamp the ScrollView frame to
            // min(measured, cap).
            let transcriptMaxHeight: CGFloat = 100
            let waveform = VStack(spacing: 6) {
                AudioWaveformView(levels: speechManager.audioLevels)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 8)
                if !speechManager.recognizedText.isEmpty {
                    ScrollViewReader { proxy in
                        ScrollView(.vertical, showsIndicators: true) {
                            Text(speechManager.recognizedText)
                                .font(.subheadline)
                                .foregroundStyle(ChatColors.secondaryText)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                                .background(GeometryReader { geo in
                                    Color.clear.preference(key: TranscriptHeightKey.self, value: geo.size.height)
                                })
                                .id("transcriptTail")
                        }
                        .frame(height: min(max(transcriptHeight, 22), transcriptMaxHeight))
                        .onPreferenceChange(TranscriptHeightKey.self) { transcriptHeight = $0 }
                        .onReceive(speechManager.$recognizedText) { _ in
                            withAnimation(.linear(duration: 0.1)) {
                                proxy.scrollTo("transcriptTail", anchor: .bottom)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, topPadding)
            .padding(.bottom, 10)
            return AnyView(waveform)
        }
        let field = PastableTextView(
            text: inputTextBinding,
            isFocused: $inputFocused,
            hasSelection: $inputHasSelection,
            isScrollable: $inputIsScrollable,
            isAtScrollBottom: $inputAtScrollBottom,
            // `String(localized:)` with an interpolation extracts the
            // `%@` form ("Message %@ (@ to mention files)") as the lookup
            // key in Localizable.xcstrings, so translators get one
            // parameterized entry per locale instead of one per soul name.
            placeholder: AppLocalized("Message \(soulName) (@ to mention files)"),
            onPasteImage: { image in vm.addImageAttachment(image) },
            onPasteFile: { url in vm.addFileAttachment(from: url) },
            onReturnKey: handleReturnKey,
            onArrowUp: handleArrowUp,
            onArrowDown: handleArrowDown,
            onTab: handleTabKey,
            onCaretChange: handleCaretChange,
            onSelectionReplace: { before, after in
                // [T-text-input-correction-source] Feed composer select-and-
                // replace edits into the SAME correction learner as voice
                // transcript fixes, tagged source=text_input. Fire-and-forget
                // off the keystroke path (design §12.2). The recorder applies
                // the phonetic-admission + consent gate, so a plain reword is
                // dropped and nothing is stored without opt-in. locale "zh" is
                // the only phonetic normalizer currently active; the composer
                // has no ASR language context, and a non-normalizable locale
                // would just be discarded downstream.
                Task.detached(priority: .utility) {
                    await VoiceCorrectionRecorder.shared.recordEdit(
                        before: before, after: after,
                        locale: "zh", source: "text_input")
                }
            },
            desiredCaret: vm.pendingCaret,
            // [T-ipad-composer-resize] Raise the text view's growth cap in
            // lockstep with the dragged frame, otherwise text would scroll at
            // 120pt inside a taller box and the extra space would be dead.
            maxHeightOverride: composerTextHeight
        )
        return AnyView(composerBody(field: field, topPadding: topPadding))
    }

    /// [T-ipad-composer-resize] Wraps the text field with its (optional) fixed
    /// height and the iPad drag handle.
    ///
    /// `.fixedSize(vertical:)` and an explicit `.frame(height:)` are mutually
    /// exclusive: fixedSize asks the view for its intrinsic height, which is
    /// exactly what we're overriding once the user has resized. So the two
    /// modes are separate branches rather than modifiers stacked on one view.
    private func composerBody(field: PastableTextView, topPadding: CGFloat) -> some View {
        Group {
            if let height = composerTextHeight {
                field.frame(height: height)
            } else {
                field.fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, topPadding)
        .padding(.bottom, 10)
        .overlay(alignment: .top) {
            if composerResizeEnabled { composerResizeHandle }
        }
    }

    /// The grab affordance. Dragging UP makes the composer taller, so the drag
    /// translation is negated. Double-tapping toggles between the two extremes.
    private var composerResizeHandle: some View {
        Capsule()
            .fill(ChatColors.secondaryText.opacity(0.35))
            .frame(width: 36, height: 5)
            .padding(.top, 4)
            .contentShape(Rectangle().inset(by: -12))  // enlarge the hit area
            // Order matters: the double-tap must be registered BEFORE the drag,
            // otherwise the drag (minimumDistance 2) claims the touch sequence
            // first and the second tap never forms.
            .onTapGesture(count: 2) { toggleComposerHeight() }
            .gesture(
                DragGesture(minimumDistance: 2)
                    .onChanged { value in composerDragOffset = -value.translation.height }
                    .onEnded { value in
                        let resolved = min(
                            max(currentComposerHeight - value.translation.height,
                                Self.composerDefaultHeight),
                            composerMaxHeight)
                        composerDragOffset = 0
                        persistComposerHeight(resolved)
                    }
            )
            .accessibilityLabel(AppLocalized("Resize input box"))
            .accessibilityHint(AppLocalized("Drag to resize, double tap to toggle"))
    }

    /// Double-tap: jump between the 50% cap and the default height. Anything
    /// short of the cap counts as "not expanded" and expands, so a partially
    /// dragged composer grows rather than collapsing unexpectedly.
    private func toggleComposerHeight() {
        let isExpanded = currentComposerHeight >= composerMaxHeight - 0.5
        withAnimation(.easeOut(duration: 0.2)) {
            persistComposerHeight(isExpanded ? Self.composerDefaultHeight : composerMaxHeight)
        }
    }

    /// Store a resolved composer height as a screen fraction. Collapsing to the
    /// default clears the preference entirely, so the composer returns to
    /// content-driven sizing rather than being pinned at the default.
    private func persistComposerHeight(_ height: CGFloat) {
        composerHeightFraction = height <= Self.composerDefaultHeight + 0.5
            ? 0
            : height / UIScreen.main.bounds.height
    }

    /// The composer's height right now, used as the drag's starting point.
    private var currentComposerHeight: CGFloat {
        composerHeightFraction > 0
            ? min(max(UIScreen.main.bounds.height * composerHeightFraction,
                      Self.composerDefaultHeight), composerMaxHeight)
            : Self.composerDefaultHeight
    }

    // MARK: - Input keyboard handlers (extracted to avoid inflating the
    // generic type of `inputBar` past what Swift can demangle at runtime).

    private func handleReturnKey() {
        if vm.showMentionMenu && vm.executeSelectedMention() { return }
        if vm.showSlashMenu && vm.executeSelectedSlashCommand() { return }
        if canEnqueue { performEnqueue() }
        else if canSend { performSend() }
    }

    private func handleArrowUp() -> Bool {
        if vm.showMentionMenu { vm.mentionMenuUp(); return true }
        guard vm.showSlashMenu else { return false }
        vm.slashMenuUp()
        return true
    }

    private func handleArrowDown() -> Bool {
        if vm.showMentionMenu { vm.mentionMenuDown(); return true }
        guard vm.showSlashMenu else { return false }
        vm.slashMenuDown()
        return true
    }

    private func handleTabKey() -> Bool {
        if vm.showMentionMenu { return vm.executeSelectedMention() }
        guard vm.showSlashMenu else { return false }
        vm.slashMenuAutocomplete()
        return true
    }

    private func handleCaretChange(_ caret: Int) {
        vm.inputCaret = caret
    }

    private var inputBar: some View {
        VStack(spacing: 0) {
            if vm.isSuspended {
                HStack(spacing: 8) {
                    ProgressView()
                        .tint(.yellow)
                    Text("Waiting for other tasks to complete...")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(Color.yellow.opacity(0.08))
            }

            VStack(spacing: 5) {
                // Attachment preview chips — 3 per row
                if !vm.attachments.isEmpty || vm.loadingVideoCount > 0 {
                    ScrollView(.vertical, showsIndicators: true) {
                        inputAttachmentGrid
                            .background(GeometryReader { geo in
                                Color.clear.preference(key: AttachmentGridHeightKey.self, value: geo.size.height)
                            })
                    }
                    .frame(height: min(attachmentGridHeight, 160))
                    .onPreferenceChange(AttachmentGridHeightKey.self) { attachmentGridHeight = $0 }
                    .padding(.top, 8)
                }

                inputFieldOrWaveform

                inputBottomRow
                    .padding(.horizontal, 12)
                    .padding(.bottom, 10)
            }
            .contentShape(RoundedRectangle(cornerRadius: 20))
            .onTapGesture { inputFocused = true }
            .onReceive(speechManager.$recognizedText) { text in
                guard speechManager.state == .recording || !text.isEmpty else { return }
                // Append only the new delta to preserve existing input text
                let newLength = text.count
                if newLength > lastRecognizedLength {
                    let delta = String(text.dropFirst(lastRecognizedLength))
                    vm.inputText += delta
                }
                lastRecognizedLength = newLength
            }
            .overlay(alignment: .topTrailing) {
                // Only offer "Move to…" when there is actually something to
                // move. hasInjectedShareContent is flipped true after EVERY
                // share ingestion, even when the share produced no text and no
                // attachments (e.g. an unrecognized item, or all items diverted
                // to Provider-import). Gate on real movable content so the pill
                // doesn't linger over an empty composer. [T-ios-moveto-empty-content]
                if hasInjectedShareContent && hasMovableShareContent {
                    Button { showMoveToSheet = true } label: {
                        Label("Move to…", systemImage: "arrow.right.circle")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(ChatColors.secondaryText)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(.ultraThinMaterial)
                            .clipShape(Capsule())
                    }
                    .padding(.top, 6)
                    .padding(.trailing, 10)
                }
            }
            .modifier(ComposerSurface())
            .frame(maxWidth: maxContentWidth)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            // [T-ios-geometry-observer-crash] onGeometryChange replaces the
            // GeometryReader scaffold (async-renderer SIGTRAP — see the
            // floating-bar site). Fires with the initial value too, so the
            // old onAppear seeding AND its diagnostic log are preserved as
            // a single unified line.
            .onGeometryChange(for: CGRect.self) { proxy in
                proxy.frame(in: .global)
            } action: { frame in
                let newH = frame.size.height
                // [voice-inputbar-padding-zero] Guard against transient 0.
                guard newH > 0 else { return }

                // [T-voice-inputbar-cross-session-bleed] During session
                // transition animations, SwiftUI fires onGeometryChange for
                // BOTH the outgoing and incoming AIChatView. The outgoing
                // view's frame slides offscreen. Discard measurements from
                // offscreen views to prevent a stale cross-session height from
                // overwriting the correct one.
                //
                // [T-ipad-inputbar-height-zero] The test is "is this frame
                // horizontally inside the WINDOW", not "is its minX near zero".
                // The original form compared minX against ±25% of
                // UIScreen.main.bounds.width, which silently assumed the chat
                // pane starts at global x≈0 — true only on iPhone. In a split
                // view (iPad / Mac Catalyst with the sidebar open) the pane is
                // inset by the sidebar width, so minX is legitimately large,
                // EVERY measurement failed the guard, the leading-edge seed
                // never fired, and inputBarHeight stayed 0 forever. Since it is
                // the message list's bottom inset, the list reserved no space
                // and the last message was permanently stuck under the composer
                // — unable to scroll into view. Compare against the host
                // window's bounds (falling back to the screen) and accept any
                // frame that overlaps it horizontally: an outgoing view mid-
                // slide is pushed a full pane-width out and still fails, which
                // is the behaviour the guard was actually written for.
                // Measure the slide RELATIVE TO THE PANE, not to the screen. The
                // outgoing view is pushed by ~a full pane width, so its offset
                // from the settled x is huge; a genuine mid-animation frame of
                // the incoming view is only tens of points off. Keeping the same
                // ±25% ratio the original used reproduces the iPhone behaviour
                // exactly (there, pane == screen), while a sidebar-inset pane on
                // iPad / Mac now compares against its OWN left edge instead of
                // global x≈0 — which is what made every frame look "offscreen".
                let hostWindow = inputBarWindowBounds()
                let paneW = frame.width > 1 ? frame.width : hostWindow.width
                let tolerance = paneW * 0.25
                let onscreen = frame.minX >= hostWindow.minX - tolerance
                    && frame.maxX <= hostWindow.maxX + tolerance

                // [T-voice-inputbar-stale-height] Leading-edge first write,
                // trailing-edge thereafter. The FIRST non-zero height (session
                // open / initial composer layout) is applied synchronously so the
                // message list's bottom inset is correct on its first pass and it
                // snaps straight to the bottom — the pure-trailing debounce made
                // the list scroll once to a "fake bottom" (small/old height), then
                // again 300ms later when the real height landed ("scroll, pause,
                // scroll" on session open).
                // [T-voice-inputbar-anim-tail] Record the freshest ON-SCREEN
                // height BEFORE either commit path runs — both the seed's and the
                // debounce's settle-confirm read this, so it must already reflect
                // the current callback (writing it after the seed's `return`
                // would leave the confirm reading a stale/zero value).
                if onscreen { latestInputBarFrameH = newH }
                // [T-voice-inputbar-collapse-selfheal] Liveness, recorded for
                // EVERY callback — an off-screen sample is still proof the host
                // is laying out, which is exactly what the health probe asks.
                inputBarGeometryTick &+= 1
                inputBarLastGeometryAt = CFAbsoluteTimeGetCurrent()

                if !didSeedInputBarHeight, onscreen {
                    didSeedInputBarHeight = true
                    inputBarHeightDebounce?.cancel()
                    inputBarHeight = newH
                    AppLogger(category: "InputBarLayout").info("inputBarHeight seeded=\(newH) x=\(Int(frame.minX))")
                    // [T-inputbar-stale-across-reentry] The seed is applied
                    // SYNCHRONOUSLY (the message list needs a bottom inset on its
                    // very first pass, else it scrolls to a fake bottom). That
                    // means it can capture a mid-animation frame — the exact
                    // hazard the old code avoided by never re-arming the seed,
                    // at the cost of letting a stale height live forever. Keep
                    // the synchronous seed AND make it self-correcting: confirm
                    // against the freshest reported geometry once the panel's
                    // 280ms animation has provably finished. No new measurement
                    // is taken; we only re-read what onGeometryChange reported.
                    let voiceAtSeed = voiceInputActive
                    inputBarHeightDebounce = Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(380))
                        guard !Task.isCancelled, voiceAtSeed == voiceInputActive else { return }
                        let settled = latestInputBarFrameH
                        if settled > 0, abs(settled - newH) > 0.5 {
                            inputBarHeight = settled
                            AppLogger(category: "InputBarLayout").info("inputBarHeight SEED-CORRECTED \(newH)→\(settled) — seed had caught an animation frame")
                        }
                    }
                    return
                }
                // Subsequent changes debounce: onGeometryChange fires during
                // SwiftUI layout animation frames, sampling mid-transition
                // heights. If the height then stabilizes (e.g. compact voice
                // mode = fixed size), no further callback fires to correct it —
                // inputBarHeight gets stuck at the animation mid-frame value.
                // Wait 300ms (> animation duration 200ms) to capture the settled
                // post-animation value.
                guard onscreen else {
                    AppLogger(category: "InputBarLayout").info("inputBarHeight discarded=\(newH) offscreen x=\(Int(frame.minX))…\(Int(frame.maxX)) win=\(Int(hostWindow.minX))…\(Int(hostWindow.maxX))")
                    return
                }
                inputBarHeightDebounce?.cancel()
                let voiceAtCapture = voiceInputActive
                inputBarHeightDebounce = Task { @MainActor in
                    // [T-voice-inputbar-anim-tail] 380ms, not 300ms. The panel's
                    // expand/collapse and transcript-band animations are
                    // .easeInOut(duration: 0.28) — the old 300ms window left only
                    // 20ms of margin (its comment claimed the animation was
                    // 200ms, which is stale), so the timer routinely expired
                    // while the panel was still moving and SwiftUI's final
                    // geometry callback had not landed yet.
                    try? await Task.sleep(for: .milliseconds(380))
                    guard !Task.isCancelled else { return }
                    // [T-voice-inputbar-branch-swap] During rapid streaming
                    // re-renders, voiceInputActive can glitch for one frame,
                    // swapping the AnyView branch from InlineVoiceInputView
                    // to PastableTextView. The text view's height (~115pt)
                    // is wrong for the voice panel (~161pt compact). Discard
                    // if the branch flipped back by the time debounce fires.
                    if voiceAtCapture != voiceInputActive {
                        AppLogger(category: "InputBarLayout").info("inputBarHeight discarded=\(newH) branchSwap voice=\(voiceAtCapture)→\(voiceInputActive)")
                        return
                    }
                    // Commit the LATEST height, not the one that armed the timer.
                    let committed = latestInputBarFrameH > 0 ? latestInputBarFrameH : newH
                    inputBarHeight = committed
                    let armed = newH
                    AppLogger(category: "InputBarLayout").info("inputBarHeight settled=\(committed) x=\(Int(frame.minX))\(abs(committed - armed) > 0.5 ? " (armedWith=\(armed), used latest)" : "")")

                    // [T-voice-inputbar-anim-tail] Settle-confirm. Even the
                    // latest recorded frame can be a tail sample if the final
                    // callback lands after this task wakes. Re-check once more
                    // after another animation-length beat and correct if the
                    // panel moved — this is the backstop that guarantees the
                    // bottom inset can never stay frozen on a transitional
                    // height, which is the bottom-gap symptom. No new
                    // measurement is taken: we only re-read what
                    // onGeometryChange already reported.
                    try? await Task.sleep(for: .milliseconds(320))
                    guard !Task.isCancelled else { return }
                    guard voiceAtCapture == voiceInputActive else { return }
                    let settled = latestInputBarFrameH
                    if settled > 0, abs(settled - committed) > 0.5 {
                        inputBarHeight = settled
                        AppLogger(category: "InputBarLayout").info("inputBarHeight CORRECTED \(committed)→\(settled) — commit had caught an animation-tail frame")
                    }
                }
            }
            .overlay(alignment: .topLeading) {
                // Extracted into a named struct + AnyView-erased so the
                // overlay does not deepen `inputBar`'s already-fragile
                // generic chain. See crash 2026-04-17 (line ~1981): SwiftUI's
                // runtime type-metadata decoder recurses into every nested
                // modifier, and an inline `.overlay { HStack { Image...; Text
                // .padding.frame.background(Capsule).shadow.opacity } ...}`
                // blew the stack on `inputBottomRow.getter` during a swipe.
                SwipeToSendHint(progress: sendSwipeProgress,
                                armFraction: Self.kSendSwipeArmFraction,
                                location: sendSwipeLocation,
                                // While the agent is mid-stream the gesture
                                // queues the prompt instead of sending it,
                                // so the capsule advertises that — matches
                                // the send-button's send/enqueue toggle.
                                isEnqueue: vm.isProcessing && !canSend)
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 20, coordinateSpace: .local)
                    .onChanged { value in
                        // [T-ios-composer-swipe-send-at-bottom] While the inner
                        // scroll still owns the drag, keep the anchor cleared so
                        // that the instant it hands off (user reaches the bottom
                        // mid-drag) progress re-anchors HERE instead of counting
                        // the scrolling that came before.
                        guard !inputHasSelection, swipeToSendAllowed else {
                            sendSwipeAnchor = nil
                            return
                        }
                        // Priority in voice mode: component gestures > collapse/
                        // expand > swipe-to-send. The panel's own drags (collapse/
                        // expand, delete control) are handled inside the panel; the
                        // swipe-to-send only engages once the finger has been
                        // dragged ABOVE the input container (location.y < 0), i.e.
                        // out toward the message area — making it the lowest
                        // priority and never colliding with in-panel drags.
                        if voiceInputActive && value.location.y >= 0 {
                            if sendSwipeProgress != 0 { sendSwipeProgress = 0 }
                            return
                        }
                        let isVertical = abs(value.translation.height) > abs(value.translation.width)
                        guard isVertical, value.translation.height < 0 else {
                            if sendSwipeProgress != 0 { sendSwipeProgress = 0 }
                            return
                        }
                        // Empty input: no swipe-to-send hint. Keyboard
                        // activation (if collapsed) is handled in onEnded so
                        // we don't pop the keyboard during scroll-like drags.
                        let hasText = !vm.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        guard hasText else {
                            if sendSwipeProgress != 0 { sendSwipeProgress = 0 }
                            return
                        }
                        // [T-ios-composer-swipe-send-at-bottom] Anchor progress
                        // at the hand-off point. For a plain short-text drag
                        // this is the drag origin (0) and behaves exactly as
                        // before; for a drag that first scrolled a long value to
                        // its end, it discards the scrolling part so only the
                        // EXTRA pull past the bottom counts toward sending.
                        if sendSwipeAnchor == nil { sendSwipeAnchor = value.translation.height }
                        let dragUp = -(value.translation.height - (sendSwipeAnchor ?? 0))
                        guard dragUp > 0 else {
                            if sendSwipeProgress != 0 { sendSwipeProgress = 0 }
                            return
                        }
                        let newProgress = max(0, min(1, dragUp / Self.kSendSwipeThreshold))
                        // Haptic tick the first time we cross the arming
                        // fraction (0.8) — matches the capsule's full-opacity
                        // point so the cue and the release zone agree.
                        let arm = Self.kSendSwipeArmFraction
                        if newProgress >= arm && sendSwipeProgress < arm {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        }
                        sendSwipeProgress = newProgress
                        sendSwipeLocation = value.location
                    }
                    .onEnded { value in
                        // [T-ios-composer-swipe-send-at-bottom] Consume the
                        // hand-off anchor: whatever happens below, the next
                        // gesture must re-anchor from scratch.
                        let anchor = sendSwipeAnchor ?? 0
                        sendSwipeAnchor = nil
                        guard !inputHasSelection, swipeToSendAllowed else {
                            sendSwipeProgress = 0
                            return
                        }
                        // Voice mode: only a release dragged OUT of the input
                        // container (toward the message area) sends — in-panel
                        // drags belong to collapse/expand and the delete control.
                        //
                        // [T-voice-scroll-gesture-priority] This location test is
                        // also what already satisfies "scroll must win over
                        // swipe-to-send" inside the voice panel's transcript, in
                        // BOTH the editing and read-only modes: any drag whose
                        // release lands inside the input container (y >= 0) —
                        // which is every drag that stays within the transcript
                        // ScrollView — is rejected here before it can send. So a
                        // scroll gesture can never be reinterpreted as a send,
                        // and no separate isScrollable/at-bottom channel needs to
                        // be plumbed from the panel to this gesture. Send only
                        // engages once the finger has genuinely left the panel and
                        // moved up toward the message list, which is exactly the
                        // required behaviour.
                        if voiceInputActive && value.location.y >= 0 {
                            if sendSwipeProgress != 0 { sendSwipeProgress = 0 }
                            return
                        }
                        let isVertical = abs(value.translation.height) > abs(value.translation.width)
                        let endY = value.location.y
                        let hasText = !vm.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        // Measure the send-relevant travel from the hand-off
                        // anchor, not the drag origin, so the scrolling portion
                        // of a scroll-then-pull drag doesn't read as a swipe-up.
                        let anchoredHeight = value.translation.height - anchor
                        let swipedUp = isVertical && anchoredHeight < 0
                        if swipedUp && hasText {
                            // Swipe-up release with text: send if past
                            // arming fraction (0.8). When the agent is
                            // mid-stream the request is queued instead —
                            // mirrors the send-button which falls back to
                            // performEnqueue while `vm.isProcessing` makes
                            // `canSend` false. Without this branch a long
                            // run blocked the swipe gesture entirely.
                            if sendSwipeProgress >= Self.kSendSwipeArmFraction {
                                // Match the tap/return-key precedence:
                                // when the agent is busy AND a queue slot
                                // is available, ENQUEUE first. Previously
                                // this branch checked `canSend` first —
                                // and `canSend = vm.isReady && hasText`
                                // doesn't consult `isProcessing`, so a
                                // busy run with text in the composer
                                // bypassed enqueue and started a fresh
                                // send that got dropped (T-drag-send-queue).
                                if canEnqueue {
                                    performEnqueue()
                                } else if canSend {
                                    performSend()
                                }
                            }
                            withAnimation(.easeOut(duration: 0.18)) {
                                sendSwipeProgress = 0
                            }
                        } else if swipedUp && !hasText && !inputFocused {
                            // Swipe-up release with empty input + collapsed
                            // keyboard -> activate the keyboard (enter input
                            // mode). If the keyboard is already up, do
                            // nothing so the gesture isn't misinterpreted.
                            inputFocused = true
                            sendSwipeProgress = 0
                        } else if isVertical && inputFocused && (endY > inputBarHeight || endY < 0) {
                            // Swipe-down (or out of bounds) while focused
                            // still dismisses the keyboard — unchanged.
                            inputFocused = false
                            sendSwipeProgress = 0
                        } else {
                            sendSwipeProgress = 0
                        }
                    }
            )
        }
        .background(
            // Fade from transparent (top) to solid background (bottom half)
            // so scrolling content doesn't bleed through below the input bar,
            // while keeping the gap between tool status bar and input bar clear.
            LinearGradient(
                stops: [
                    .init(color: ChatColors.background.opacity(0), location: 0),
                    .init(color: ChatColors.background, location: 0.8),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    /// Single overlay containing whichever input popup is active (slash or
    /// `@`-mention). Kept in one overlay slot to avoid compounding SwiftUI
    /// generic types on the body, which previously caused a runtime
    /// type-metadata recursion crash.
    /// [T-slash-picker-constant-band] Fixed top reserve for the slash /
    /// mention popup. Dynamic Island + navbar + a comfortable visual
    /// gap below the title block. Using a constant beats reading
    /// `topSafeAreaInset` because that value reports the bare safe-area
    /// inset (status bar + Dynamic Island, ~59pt) and skips the
    /// principal-toolbar title — so any computation off it leaves the
    /// popup overlapping the title row. 260pt covers the worst case
    /// (iOS 18, 3-line custom titleView).
    private static let slashPickerTopReserve: CGFloat = 260
    private static let slashPickerBottomMargin: CGFloat = 16
    /// [T-slash-picker-fixed-height] Locked popup height: shows up to
    /// 4 rows, then scrolls. Row visual height ≈ 46pt (13pt title +
    /// 11pt subtitle + per-row vertical padding); +8pt accounts for
    /// the inner VStack's first/last cap. The popup is now strictly
    /// `slashPickerRowHeight * 4 + 8 = 192pt` regardless of how many
    /// commands match, so the band is predictable and rows beyond the
    /// 4th are reachable via the visible scrollbar.
    // 54pt covers the tallest standard slash row including the `/thinking`
    // entry — its inline ThinkingLevel picker pushes the row above the
    // 44pt minHeight baseline. Earlier 46pt + visible=4 → 192pt clipped
    // the 4th row's tail. ×4 + 8pt bottom breathing = 224pt.
    private static let slashPickerRowHeight: CGFloat = 54
    private static let slashPickerVisibleRows: Int = 4
    private static let slashPickerFixedHeight: CGFloat =
        slashPickerRowHeight * CGFloat(slashPickerVisibleRows) + 8

    @ViewBuilder
    private var inputPopupOverlay: some View {
        ZStack {
            if vm.showSlashMenu && !vm.filteredSlashCommands.isEmpty {
                popupOverlayContainer(onDismiss: { vm.dismissSlashMenu() }) {
                    slashCommandMenu
                }
            } else if vm.showMentionMenu {
                popupOverlayContainer(onDismiss: { vm.dismissMentionMenu() }) {
                    mentionMenu
                }
            }
        }
        // Single spring drives both popups' show/hide transitions. Short
        // snappy curve (~0.22s) — instant on `/` / `@` keypress with a
        // touch of overshoot to read as iOS 26-native.
        .animation(.spring(response: 0.18, dampingFraction: 0.82), value: vm.showSlashMenu)
        .animation(.spring(response: 0.18, dampingFraction: 0.82), value: vm.showMentionMenu)
    }

    /// [T-ipad-inputbar-height-zero] Bounds of the window hosting the chat, in
    /// the same GLOBAL space `onGeometryChange(.global)` reports.
    ///
    /// `UIScreen.main.bounds` is NOT a valid stand-in: under Mac Catalyst and in
    /// iPad multitasking the app's window is a sub-rect of the screen, and the
    /// chat pane is further inset by the sidebar — so screen-relative math
    /// misjudges a perfectly on-screen composer as offscreen. Falls back to the
    /// screen when no key window is available (early layout / previews).
    private func inputBarWindowBounds() -> CGRect {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        guard let window else { return UIScreen.main.bounds }
        // SwiftUI's .global space is the window's own coordinate space, so the
        // window's bounds (origin .zero) — not its frame — is the right rect.
        return window.bounds
    }

    /// Shared transition modifier for the `/` and `@` popups. Layout (popup
    /// position relative to input bar top) is now driven by SwiftUI itself
    /// — popup sits as `.overlay(alignment: .bottom)` on a catcher view
    /// directly above the input bar, so the bottom edge of the popup is
    /// pinned to the input bar's top edge. No more `inputBarHeight` @State
    /// math, no more screen-bottom relative padding.
    ///
    /// What this container does:
    /// - Wraps `content()` with the slide-from-bottom + fade transition
    ///   so the popup shows / hides smoothly.
    /// - Sets `.frame(maxHeight: .infinity, alignment: .bottom)` so when
    ///   the catcher's frame is larger than the popup card, the card
    ///   stays bottom-aligned (matches input bar top).
    @ViewBuilder
    private func popupOverlayContainer<Content: View>(
        onDismiss: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) -> some View {
        content()
            // .compositingGroup() flattens the popup card (chrome + ScrollView
            // + rows) into a single rendering layer BEFORE the transition
            // applies. Without this, SwiftUI animates each child view
            // independently — the text rows often appear instantly at their
            // final position while the chrome background slides up behind
            // them ("text shows first, card catches up"). Forcing a single
            // compositing pass makes the whole card move/fade as one unit.
            .compositingGroup()
            .transition(.asymmetric(
                insertion: .move(edge: .bottom).combined(with: .opacity),
                removal: .opacity
            ))
    }

    /// Slash command popup — matches FloatingToolBar width/radius/shadow, overlays on top of it.
    /// [T-slash-picker-scroll] Wrapped in ScrollView with a half-screen height
    /// cap so long command lists (skills + builtin actions) stay reachable
    /// even when their natural height would push the bottom rows behind the
    /// input bar / keyboard. Tap-outside dismiss lives in `inputPopupOverlay`.
    private var slashCommandMenu: some View {
        InputBarPopupChrome(maxContentWidth: maxContentWidth) {
            ScrollView {
                VStack(spacing: 0) {
                    let commands = vm.filteredSlashCommands
                    ForEach(Array(commands.enumerated()), id: \.element.id) { index, cmd in
                        let isSelected = index == vm.slashMenuSelectedIndex
                        // [T-slash-picker-section-divider] Hairline between
                        // builtin commands and user-installed Skills so the
                        // two groups are visually distinct. Fires when this
                        // row is the first skill row in the list.
                        if cmd.isSkill && index > 0 && !commands[index - 1].isSkill {
                            Divider()
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                        }
                        if cmd.id == "thinking" {
                            let supported = vm.currentModelSupportsReasoning
                            SlashCommandRow(
                                cmd: cmd,
                                isSelected: isSelected,
                                memoryEnabled: vm.memoryEnabled,
                                thinkingLevel: vm.currentThinkingLevel,
                                thinkingSupported: supported,
                                availableLevels: vm.availableThinkingLevels,
                                onSetThinkingLevel: supported ? { level in
                                    vm.setThinkingLevel(level)
                                } : nil,
                                onToggleThinking: supported ? {
                                    let newLevel: ThinkingLevel = vm.currentThinkingLevel.isEnabled ? .off : .medium
                                    vm.setThinkingLevel(newLevel)
                                    vm.dismissSlashMenu()
                                } : nil
                            )
                        } else {
                            Button {
                                vm.executeSlashCommand(cmd)
                            } label: {
                                SlashCommandRow(
                                    cmd: cmd,
                                    isSelected: isSelected,
                                    memoryEnabled: vm.memoryEnabled
                                )
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(SlashMenuButtonStyle(isSelected: isSelected))
                        }
                    }
                }
            }
            .scrollIndicators(.visible)
            .frame(height: Self.slashPickerFixedHeight)
        }
    }

    /// `@` file-mention popup — mirrors slashCommandMenu styling.
    private var mentionMenu: some View {
        let rows = vm.filteredMentionEntries
        #if DEBUG
        let preview = rows.prefix(6).map { $0.basename }.joined(separator: ",")
        print("[MentionRender] mentionMenu body filter=\"\(vm.mentionFilter)\" rows.count=\(rows.count) first6=[\(preview)]")
        #endif
        return InputBarPopupChrome(maxContentWidth: maxContentWidth) {
            VStack(spacing: 0) {
                if rows.isEmpty {
                    // Lock empty-state height to slashPickerFixedHeight so
                    // the popup card stays the same size whether matches
                    // are present or not — otherwise it collapses to a
                    // single-line height and the visual top edge jumps
                    // toward the input bar, breaking position parity with
                    // the slash popup (which is always 4-rows tall).
                    VStack {
                        HStack(spacing: 8) {
                            if FileMentionIndex.shared.isScanning {
                                ProgressView().scaleEffect(0.7)
                            }
                            Text(FileMentionIndex.shared.isScanning
                                 ? AppLocalized("Scanning files…")
                                 : AppLocalized("No matching files"))
                                .font(.system(size: 13))
                                .foregroundStyle(ChatColors.secondaryText)
                            Spacer()
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        Spacer(minLength: 0)
                    }
                    .frame(height: Self.slashPickerFixedHeight)
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                // The LazyVStack outer `.id(vm.mentionFilter)`
                                // forces a full re-create on every query change,
                                // which dodges any stale-row reuse pathology;
                                // inside, the per-row id can stay simple
                                // (positional index).
                                ForEach(Array(rows.enumerated()), id: \.offset) { index, entry in
                                    let isSelected = index == vm.mentionSelectedIndex
                                    Button {
                                        vm.selectMention(entry)
                                    } label: {
                                        MentionRow(entry: entry, isSelected: isSelected, query: vm.mentionFilter)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(SlashMenuButtonStyle(isSelected: isSelected))
                                    .id(index)
                                }
                            }
                            // Tag the LazyVStack with the current filter so
                            // SwiftUI throws away its layout state when the
                            // search query changes. Without this, the lazy
                            // stack sometimes reused stale body content keyed
                            // off the previous query (the `@codex showing
                            // mteam-hub` symptom).
                            .id(vm.mentionFilter)
                        }
                        // [T-slash-picker-fixed-height] Match slash popup:
                        // exactly 4 rows tall, scrolls on overflow with the
                        // visible indicator above.
                        .scrollIndicators(.visible)
                        .frame(height: Self.slashPickerFixedHeight)
                        .onChange(of: vm.mentionSelectedIndex) { newIndex in
                            guard newIndex >= 0, newIndex < rows.count else { return }
                            withAnimation(.easeOut(duration: 0.12)) {
                                proxy.scrollTo(newIndex, anchor: .center)
                            }
                        }
                    }
                    if FileMentionIndex.shared.isScanning {
                        HStack(spacing: 6) {
                            ProgressView().scaleEffect(0.6)
                            Text("Scanning more locations…")
                                .font(.system(size: 11))
                                .foregroundStyle(ChatColors.secondaryText)
                            Spacer()
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                    }
                }
            }
        }
    }

    /// Shared chrome wrapper for the `/` and `@` input-bar popups — locks
    /// the visual styling (dark/light background, 10pt rounded corners,
    /// 0.5pt border, soft shadow, width clamp via `maxWidth`, 12pt
    /// horizontal inset) so the two pickers stay pixel-identical. The
    /// caller supplies the content (ScrollView, LazyVStack, etc.) and
    /// owns its own height. We deliberately do NOT wrap the content in
    /// a ScrollView here because the two pickers want different scroll
    /// reader / lazy semantics — chrome only.
    ///
    /// Putting both popups behind the same chrome means matchedGeometryEffect
    /// in `inputPopupOverlay` interpolates the same outer frame regardless
    /// of which popup is firing, so the pop-from-`/`-button animation
    /// reads identically across the two.
    private struct InputBarPopupChrome<Content: View>: View {
        let maxContentWidth: CGFloat?
        @ViewBuilder let content: () -> Content

        var body: some View {
            content()
                .frame(maxWidth: .infinity)
                .background(Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.15, alpha: 1) : UIColor.systemBackground }))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color(UIColor.separator).opacity(0.3), lineWidth: 0.5)
                )
                .shadow(color: Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.08, alpha: 0.75) : UIColor(white: 0, alpha: 0.12) }), radius: 8, x: 0, y: 4)
                .frame(maxWidth: maxContentWidth)
                .padding(.horizontal, 12)
        }
    }

    /// A single row in the file-mention menu.
    private struct MentionRow: View {
        let entry: FileMentionEntry
        let isSelected: Bool
        let query: String

        var body: some View {
            HStack(spacing: 10) {
                Image(systemName: iconName)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(isSelected ? Color.white : ChatColors.secondaryText)
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.basename)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(isSelected ? Color.white : ChatColors.primaryText)
                        .lineLimit(1)
                    Text(entry.displayPath)
                        .font(.system(size: 11))
                        .foregroundStyle(isSelected ? Color.white.opacity(0.75) : ChatColors.secondaryText)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer()
                Text(entry.mountName ?? entry.scope.displayLabel)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(isSelected ? Color.white.opacity(0.9) : ChatColors.secondaryText)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(
                        Capsule().fill(
                            isSelected
                            ? Color.white.opacity(0.2)
                            : Color(UIColor.systemGray5)
                        )
                    )
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }

        private var iconName: String {
            if entry.isDirectory { return "folder" }
            switch entry.scope {
            case .workspace:   return "doc.text"
            case .attachments: return "paperclip"
            case .shared:      return "folder.badge.person.crop"
            case .skills:      return "sparkles"
            case .memory:      return "brain.head.profile"
            case .mount:       return "externaldrive"
            }
        }
    }

    /// Attachment grid extracted to help the Swift type-checker.
    @ViewBuilder
    private var inputAttachmentGrid: some View {
        InputAttachmentGridView(
            attachments: vm.attachments,
            loadingVideoCount: vm.loadingVideoCount,
            onRemove: { attachment in vm.removeAttachment(attachment) },
            onMove: { fromID, toID in
                withAnimation(.easeInOut(duration: 0.2)) {
                    vm.moveAttachment(fromID: fromID, toID: toID)
                }
            }
        )
    }

    /// Button style for slash menu items — shows highlight on press.
    private struct SlashMenuButtonStyle: ButtonStyle {
        let isSelected: Bool
        func makeBody(configuration: Configuration) -> some View {
            configuration.label
                .background((isSelected || configuration.isPressed) ? Color.accentColor : Color.clear)
        }
    }

    /// Single row in the slash command menu.
    private struct SlashCommandRow: View {
        let cmd: AIChatViewModel.SlashCommand
        let isSelected: Bool
        let memoryEnabled: Bool
        var thinkingLevel: ThinkingLevel = .off
        var thinkingSupported: Bool = true
        var availableLevels: [ThinkingLevel] = ThinkingLevel.allCases
        var onSetThinkingLevel: ((ThinkingLevel) -> Void)?
        var onToggleThinking: (() -> Void)?

        var body: some View {
            HStack(spacing: 8) {
                // Label area — tap to toggle thinking on/off and dismiss
                HStack(spacing: 8) {
                    Group {
                        if cmd.id == "thinking" {
                            Image("ThinkingIcon")
                                .resizable()
                                .frame(width: 16, height: 16)
                        } else {
                            Image(systemName: cmd.icon)
                                .font(.system(size: 14, weight: .medium))
                        }
                    }
                    .foregroundStyle(thinkingIconColor)
                    .frame(width: 20)
                    VStack(alignment: .leading, spacing: 1) {
                        let isThinkingActive = cmd.id == "thinking" && thinkingLevel.isEnabled && thinkingSupported
                        let titleColor: Color = isThinkingActive
                            ? .blue : (isSelected ? .white : ChatColors.primaryText)
                        let subtitleText = (cmd.id == "thinking" && !thinkingSupported)
                            ? AppLocalized("Not supported by current model")
                            : cmd.subtitle
                        let subtitleColor: Color = (cmd.id == "thinking" && !thinkingSupported)
                            ? .secondary
                            : (isThinkingActive ? .blue.opacity(0.7) : (isSelected ? .white.opacity(0.7) : ChatColors.secondaryText))
                        // [T-slash-picker-product-rules] Title + subtitle
                        // each clamped to a single line. Long skill names
                        // and descriptions used to wrap and pump the row
                        // height past 60pt, which then snowballed into a
                        // multi-screen picker.
                        Text("/\(cmd.title.lowercased())")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(titleColor)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Text(subtitleText)
                            .font(.system(size: 11))
                            .foregroundStyle(subtitleColor)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
                .allowsHitTesting(cmd.id == "thinking")
                .onTapGesture { onToggleThinking?() }
                if cmd.id == "memory" {
                    // [T-ios-voiceover-labels] Status glyph, not a control:
                    // give it the on/off meaning in words instead of letting
                    // VoiceOver read "checkmark circle fill" / "slash circle".
                    Image(systemName: memoryEnabled ? "checkmark.circle.fill" : "slash.circle")
                        .font(.system(size: 16))
                        .foregroundStyle(memoryEnabled ? (isSelected ? .white : .green) : (isSelected ? .white.opacity(0.6) : .secondary))
                        .accessibilityLabel(Text(
                            memoryEnabled
                                ? AppLocalized("Memory on", comment: "VoiceOver label for the memory status icon when enabled")
                                : AppLocalized("Memory off", comment: "VoiceOver label for the memory status icon when disabled")
                        ))
                }
                if cmd.id == "thinking" && thinkingSupported {
                    thinkingLevelPicker
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(minHeight: 44)
        }

        private var thinkingIconColor: Color {
            guard cmd.id == "thinking" else {
                return isSelected ? .white.opacity(0.8) : ChatColors.secondaryText
            }
            if !thinkingSupported { return .secondary }
            return thinkingLevel.isEnabled ? .blue : (isSelected ? .white.opacity(0.8) : ChatColors.secondaryText)
        }

        private var thinkingLevelPicker: some View {
            let maxAvailable = availableLevels.last
            let isClamped = thinkingLevel.isEnabled && maxAvailable != nil && thinkingLevel > (maxAvailable ?? thinkingLevel)

            return HStack(spacing: 0) {
                ForEach(availableLevels, id: \.self) { level in
                    let isExactMatch = thinkingLevel == level
                    let isClampedHighlight = isClamped && level == maxAvailable
                    let isHighlighted = isExactMatch || isClampedHighlight

                    HStack(spacing: 1) {
                        Text(level.displayName)
                            .font(.system(size: 11, weight: isHighlighted ? .bold : .regular))
                        if isClampedHighlight {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 8, weight: .bold))
                        }
                    }
                    .foregroundStyle(isHighlighted ? .white : .secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
                    .background(
                        isHighlighted
                            ? (isClampedHighlight
                                ? Color.orange.opacity(0.75)
                                : Color.blue)
                            : Color.clear
                    )
                    .contentShape(Rectangle())
                    .onTapGesture { onSetThinkingLevel?(isHighlighted ? .off : level) }
                    .id(level)
                }
            }
            .background(Color.secondary.opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
    }

    private var canSend: Bool {
        let hasText = !vm.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let ready = vm.isReady
        // [T-ios-photo-pick-placeholder] Block sending while any picked photo is
        // still loading. Only count `.ready` attachments as sendable content, so
        // a composer holding ONLY failed/loading chips (and no text) can't send.
        let hasReadyAttachment = vm.attachments.contains { $0.loadState == .ready }
        return ready && !vm.hasLoadingAttachments && (hasText || hasReadyAttachment)
    }

    private var canEnqueue: Bool {
        vm.isProcessing && !vm.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Whether the composer actually holds something worth moving to another
    /// session — non-blank text or at least one attachment. Gates the
    /// "Move to…" pill so it never appears over an empty composer.
    private var hasMovableShareContent: Bool {
        !vm.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !vm.attachments.isEmpty
    }


    private func performEnqueue() {
        // Dismiss the software keyboard on enqueue. With a hardware keyboard,
        // keep focus so the user can continue typing the next candidate.
        if GCKeyboard.coalesced == nil {
            inputFocused = false
            UIApplication.shared.sendAction(
                #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
        vm.forceScrollToBottom.send()
        if speechManager.state == .recording {
            speechManager.stopRecording()
        }
        speechManager.recognizedText = ""
        lastRecognizedLength = 0
        vm.enqueuePrompt()
        // [inline-voice] Enqueue consumed the text — clear the voice transcript
        // too, otherwise it lingers in the panel (the "residual after swipe-send"
        // bug). performSend already does this; the enqueue path (taken while the
        // agent is processing) was missing it.
        if voiceInputActive {
            voiceVM.clearAndRearm()
        }
    }

    /// Dismiss keyboard (commit dictation/marked text) before sending.
    private func performSend() {
        // Intercept slash commands — execute instead of sending to LLM
        if vm.tryExecuteInputAsSlashCommand() { return }

        minisLogger.info("🔑DRAFT performSend vm=\(vm.vmInstanceId) vm.sessionId=\(vm.sessionId ?? "nil") draftId=\(draftId ?? "nil") inputText='\(String(vm.inputText.prefix(50)))' isProcessing=\(vm.isProcessing)")
        // Keep SwiftUI focus when a hardware keyboard is connected — dropping
        // it would force the user to tap the field again before typing the
        // next message. With only the software keyboard, clear focus and
        // dismiss it so the @FocusState binding stays in sync.
        let hasHardwareKeyboard = GCKeyboard.coalesced != nil
        if !hasHardwareKeyboard {
            inputFocused = false
            UIApplication.shared.sendAction(
                #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
        // User actively sending — force scroll-to-bottom so the response scrolls into view
        vm.forceScrollToBottom.send()
        // Stop recording and clear recognized text so it doesn't linger after send
        if speechManager.state == .recording {
            speechManager.stopRecording()
        }
        speechManager.recognizedText = ""
        lastRecognizedLength = 0
        hasInjectedShareContent = false
        // Send FIRST while inputText still holds the recognized text. Clearing the
        // voice transcript here (clearAndRearm) mirrors "" into inputText via the
        // onChange binding, so doing it before send() left send() with empty text
        // → "send() GUARD FAILED" and nothing was sent. send() consumes and clears
        // inputText itself; we re-arm voice listening only AFTER it has run.
        vm.send()
        // [inline-voice] Stay in voice mode after sending — clear the recognized
        // text and re-arm listening for the next utterance (close with ✕).
        if voiceInputActive {
            voiceVM.clearAndRearm()
        }
    }

    /// Whether the floating tool preview is visible.
    private var hasFloatingPreview: Bool {
        hasAnyToolBlocks
    }

    /// Max content width — unconstrained in compact (portrait iPhone),
    /// capped in regular (landscape / iPad) for readability.
    private var maxContentWidth: CGFloat? {
        hSizeClass == .regular ? 900 : nil
    }

}

// MARK: - Composer Surface

/// Background + shadow for the composer's outer rounded container.
///
/// **[T-popup-white-patch 7a0e3d62] — the invariant both branches must keep.**
/// The fill is painted INSIDE a rounded shape, never as a rectangular
/// `.background(color)` + `.clipShape(...)` pair. With that older pairing the
/// host CALayer carried a rectangular backing colour which the system
/// text-selection / edit-menu pop animation snapshotted *before* SwiftUI's mask
/// applied, flashing a square of colour around the rounded corners mid-animation.
/// Filling the shape directly leaves the host layer's `backgroundColor` at
/// `.clear`, so the snapshot is already rounded. `clipShape` is still applied so
/// child views (text view, attachment grid) are clipped to the same rect.
///
/// `glassEffect(in:)` composites the material into the shape it is given, for
/// the same reason: it does not set a rectangular layer background, so it
/// preserves the invariant rather than reintroducing the bug. Verified on device
/// by long-pressing composer text to raise the edit menu — see the task notes.
///
/// The two hand-rolled shadows are dropped on the glass path (Liquid Glass
/// renders its own edge and shadow; stacking the old ones reads as a dark halo —
/// the same finding as the FAB conversion). The sub-26 branch keeps the original
/// fill and BOTH shadows byte-for-byte, including the dark-mode-only top shadow
/// that lifts the bar off the message list.
private struct ComposerSurface: ViewModifier {
    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
    }

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .glassEffect(.regular, in: shape)
                .clipShape(shape)
        } else {
            content
                .background(shape.fill(ChatColors.inputBg))
                .clipShape(shape)
                .shadow(color: Color.black.opacity(0.12), radius: 8, x: 0, y: 2)
                .shadow(color: Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0, alpha: 0.5) : UIColor(white: 0, alpha: 0) }), radius: 8, x: 0, y: -4)
        }
    }
}

// MARK: - Provider Import Prompt

/// Presents the import-vs-attach choice for a shared/opened Provider-export
/// JSON, plus a result alert. Extracted from AIChatView.body so the (already
/// large) body's type-checker stays cheap. [T-ios-json-open-provider-import-prompt]
private struct ProviderImportPromptModifier: ViewModifier {
    @Binding var pending: AIChatView.PendingProviderImport?
    @Binding var result: String?
    /// Import the provider from JSON; returns the new instance label on success.
    let onImport: (String) -> String?
    /// Add the (temp-copied) file to the chat as an attachment.
    let onAttach: (URL) -> Void

    func body(content: Content) -> some View {
        content
            // [T-ios-provider-import-prompt-bottom-sheet] Use a real bottom
            // sheet, not .confirmationDialog. On iPad / regular width SwiftUI
            // renders .confirmationDialog (and a UIAlertController .actionSheet)
            // as a SOURCE-ANCHORED POPOVER; with no anchor it floats a centered
            // rounded card over the messages — the "not a system dialog" look
            // the user reported. A `.sheet` with detents is a genuine
            // bottom-pinned sheet on BOTH iPhone and iPad.
            .sheet(item: $pending) { item in
                ProviderImportSheet(
                    onImport: {
                        pending = nil
                        if let label = onImport(item.json) {
                            result = AppLocalized("Imported provider \"\(label)\".")
                        } else {
                            result = AppLocalized("Could not import this provider configuration.")
                        }
                        try? FileManager.default.removeItem(at: item.fileURL)
                    },
                    onAttach: {
                        pending = nil
                        onAttach(item.fileURL)
                    },
                    onCancel: {
                        try? FileManager.default.removeItem(at: item.fileURL)
                        pending = nil
                    }
                )
            }
            .alert(
                AppLocalized("Provider Import"),
                isPresented: Binding(
                    get: { result != nil },
                    set: { if !$0 { result = nil } }
                )
            ) {
                Button(AppLocalized("OK"), role: .cancel) { result = nil }
            } message: {
                Text(result ?? "")
            }
    }
}

// MARK: - Provider Import Bottom Sheet

/// Bottom sheet offering "Import as Provider" vs "Add as Chat Attachment" for a
/// shared/opened Provider-export JSON. A real sheet (not .confirmationDialog)
/// so it pins to the bottom on iPad too, instead of floating a centered popover
/// card. [T-ios-provider-import-prompt-bottom-sheet]
private struct ProviderImportSheet: View {
    let onImport: () -> Void
    let onAttach: () -> Void
    let onCancel: () -> Void

    /// Set once the user taps any explicit button, so the `.onDisappear`
    /// safety-cleanup only fires for a genuine swipe-to-dismiss (no choice).
    @State private var chose = false

    var body: some View {
        VStack(spacing: 20) {
            VStack(spacing: 8) {
                Image(systemName: "doc.badge.gearshape")
                    .font(.system(size: 40))
                    .foregroundStyle(.tint)
                    .padding(.top, 8)
                Text(AppLocalized("Provider Configuration Detected"))
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text(AppLocalized("This JSON file looks like an exported provider configuration. Import it as a new provider, or add it to the chat as a file attachment?"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(spacing: 10) {
                Button(action: { chose = true; onImport() }) {
                    Text(AppLocalized("Import as Provider"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

                Button(action: { chose = true; onAttach() }) {
                    Text(AppLocalized("Add as Chat Attachment"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                Button(role: .cancel, action: { chose = true; onCancel() }) {
                    Text(AppLocalized("Cancel"))
                        .frame(maxWidth: .infinity)
                }
                .controlSize(.large)
            }
        }
        .padding(24)
        .presentationDetents([.height(360), .medium])
        .presentationDragIndicator(.visible)
        // Swipe-to-dismiss without tapping a button still needs cleanup.
        .onDisappear { if !chose { onCancel() } }
    }
}

// MARK: - Swipe-to-Send Hint

/// Floating "send arrow + Release to send" hint shown while the user is
/// dragging the input bar upward. Extracted out of `inputBar` because
/// inlining `.overlay { HStack { … } .offset(…).animation(…) }` deepens
/// SwiftUI's already-fragile generic chain on `inputBar.body` and crashes
/// the runtime type-metadata decoder via deep recursion
/// (`swift_getTypeByMangledName...`). Keeping the overlay as a single named
/// struct lets the parent's generic signature collapse to one slot.
///
/// - `progress` 0...1: drag distance as a fraction of the send threshold.
///   Arrow opacity = `min(1, progress * 1.4)`. Capsule opacity ramps in
///   over `[0.4, 0.8]` so it reaches full opacity exactly at the arming
///   fraction (`kSendSwipeArmFraction = 0.8`), where the haptic tick
///   fires and lifting the finger sends.
/// - `armFraction`: the threshold at which release fires send. Drives the
///   spring "pop" animation so the visual confirmation matches behavior.
/// - `location`: live fingertip position in the input bar's coordinate
///   space. The hint sits ~60pt above the finger (about one thumb-tip).

// MARK: - Nav Bar Modifiers

private struct NavTitleFrameModifier: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            // [NavTitleTopClip 2026-07-23] The principal titleView aligns to the
            // liquid-glass navbar's ~44pt band. Give it 46pt centered so tall
            // title glyphs (semibold 16pt + CJK/caps ascenders) render in full.
            //
            // [NavTitleTransitionClip 2026-07-23] Do NOT `.clipped()`. During a
            // push/pop transition the system interpolates the principal item's
            // frame/scale/alpha; a hard clip rectangle cuts the mid-transition
            // frames along the 46pt box far more aggressively than the steady
            // state (users saw the title sliced to half a line during the
            // animation). The 46pt frame only *sizes* the item for band
            // alignment — the 3-row stack's natural height already fits within
            // it at steady state (spacing -2, ~44pt), so there is nothing to clip
            // away, and letting transition frames render uncropped restores the
            // standard system title transition.
            content
                .frame(height: 46, alignment: .center)
        } else {
            content
        }
    }
}

private struct NavBarStyleModifier: ViewModifier {
    @Binding var topSafeAreaInset: CGFloat

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            // iOS 26: extend content behind nav bar so liquid-glass has
            // something to show through; the collection view's
            // contentInsetAdjustmentBehavior = .automatic keeps messages
            // starting below the bar.
            content
                .ignoresSafeArea(.container, edges: .top)
                .toolbarBackgroundVisibility(.visible, for: .navigationBar)
        } else {
            // iOS 16–18: opaque navbar background
            content
                .toolbarBackground(ChatColors.background, for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
                .overlay(alignment: .top) {
                    // [T-ios-geometry-observer-crash] onGeometryChange replaces
                    // the GeometryReader scaffold (async-renderer SIGTRAP — see
                    // the floating-bar site). The proxy measures the same
                    // Color.clear the reader wrapped; ignoresSafeArea/frame
                    // stay outside it exactly as before, and the action's
                    // initial fire covers the old onAppear seed.
                    Color.clear
                        .onGeometryChange(for: CGFloat.self) { proxy in
                            proxy.safeAreaInsets.top
                        } action: { topSafeAreaInset = $0 }
                        .ignoresSafeArea()
                        .frame(height: 0)
                }
        }
    }
}


// MARK: - Chat Trailing "…" Menu

/// [T-ios-trailing-menu-streaming-stability] The chat page's "…" menu,
/// extracted from AIChatView into an Equatable child so an OPEN menu stays
/// stable during streaming. AIChatView.body re-evaluates on every streaming
/// tick (the whole view observes the view model); with the Menu declared
/// inline, each tick pushed rebuilt menu content into the presented UIMenu,
/// which could reshuffle/reset it right as the user tapped — the reported
/// mis-taps on menu items mid-stream.
///
/// `.equatable()` at the call site makes SwiftUI diff with `==` below, which
/// compares ONLY the values that change what the menu displays — none of
/// which mutate during a stream — so streaming ticks stop invalidating this
/// subtree entirely. Action closures are recreated by every parent body pass
/// but never change behavior, so they're deliberately excluded from `==`.
/// [T-ios-navbar-principal-streaming-stability] Generic value-keyed equatable
/// wrapper. SwiftUI skips re-evaluating `content` while `key` compares equal —
/// the same guard ChatTrailingMenu gets from its custom `==`, made reusable
/// for views (like the navbar titleView) that are too entangled with local
/// @State/onChange plumbing to extract wholesale. The content closure is
/// recreated every parent pass but is excluded from equality, exactly like
/// ChatTrailingMenu's action closures. Internal @State / onReceive updates
/// inside `content` still fire on their own — only parent-driven invalidation
/// is gated. Anything the content READS from the parent/vm must therefore be
/// represented in `key`, or its changes will not render.
#if DEBUG
/// [T-ios-navbar-principal-streaming-stability] Eval counters proving (or
/// disproving) that the toolbar equatable gates actually hold during
/// streaming. toolbarPass ≈ one per streaming tick (counted in navTitleKey,
/// which the toolbar builder computes every pass); titleEval / menuEval
/// should stay ~1 while a stream runs IF the gates work. Grep: [NavbarEval].
enum NavbarEvalStats {
    static var toolbarPass = 0
    static var titleEval = 0
    static var menuEval = 0
    static let logger = AppLogger(category: "NavbarEval")
}
#endif

private struct EquatableByValue<Key: Equatable, Content: View>: View, Equatable {
    let key: Key
    @ViewBuilder let content: () -> Content

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.key == rhs.key }

    @ViewBuilder
    var body: some View {
        #if DEBUG
        let _ = {
            NavbarEvalStats.titleEval += 1
            NavbarEvalStats.logger.info("[NavbarEval] titleEval=\(NavbarEvalStats.titleEval) (toolbarPass=\(NavbarEvalStats.toolbarPass))")
        }()
        #endif
        content()
    }
}

/// [T-ios-navbar-principal-streaming-stability] Every display input the navbar
/// principal titleView renders, snapshotted as an Equatable key. While this is
/// unchanged (the entire streaming case), SwiftUI proves the principal toolbar
/// item unchanged and does NOT re-push the navigation bar's item set — which
/// was what kept rebuilding the OPEN "..." UIMenu each token even after
/// 59b31441 equatable-guarded the trailing menu itself: the guard covered the
/// trailing subtree, but the un-guarded principal item invalidated the whole
/// bar every tick, and UIKit refreshes the presented menu when bar items are
/// re-set (items shifting under the user's finger → mis-taps).
private struct NavTitleKey: Equatable {
    let sessionTitle: String?
    let canEditTitle: Bool
    let soulName: String
    let modelName: String
    let isGroupBound: Bool
    /// [T-codex-fast-mode] Circular ⚡ badge before the model name while
    /// Fast Mode is enabled on a Codex OAuth model.
    let showFastBolt: Bool
    let resolvedText: String?
    let hasBinding: Bool
    let hasProviders: Bool
    let showThinkingBadge: Bool
    let thinkingLevelName: String
    let fallbackTrigger: Int
    /// The fallback-pulse @State lives on AIChatView but is RENDERED inside
    /// the gated titleView — each discrete set (6 over ~1.75s) must pierce
    /// the gate or the red pulse freezes at its first frame.
    let fallbackPulse: Double
    /// [T-ios-navtitle-blur-stuck-after-unlock] Face ID lock state — the
    /// title's `.blur(radius: titleIsVisuallyLocked ? 8 : 0)` renders inside
    /// the gated toolbar content, so the unlock transition must pierce the
    /// gate too. Without this field the chat body unblurred on unlock while
    /// the navbar title stayed frozen at blur 8 (user report 2026-07-24).
    let titleLocked: Bool
}

/// [T-ios-navbar-toolbar-host] Combined display key for the WHOLE toolbar:
/// the principal title's inputs plus the trailing menu's eight displayed
/// values. While this is unchanged, ChatToolbarHost.body — and therefore the
/// ToolbarContent builder — never re-runs.
private struct ChatToolbarKey: Equatable {
    let navTitle: NavTitleKey
    let messagesEmpty: Bool
    let hasSession: Bool
    let isForcePulling: Bool
    let iCloudSyncEnabled: Bool
    let memoryEnabled: Bool
    let speakEnabled: Bool
    let showEnhancedCacheToggle: Bool
    let enhancedCacheEnabled: Bool
    /// [T-codex-fast-mode] Menu row shown only for Codex OAuth models.
    let showFastModeToggle: Bool
    let fastModeEnabled: Bool
}

/// [T-ios-navbar-toolbar-host] Zero-footprint host that owns the navigation
/// toolbar. Attached via .background on the chat content, so it sits inside
/// the NavigationStack destination (toolbar registers from any descendant),
/// occupies no layout, and — being Equatable on the toolbar's displayed
/// inputs — its body (the ToolbarContent builder) only re-runs when something
/// the toolbar actually shows changes. Streaming ticks leave the key equal,
/// the builder never re-runs, the bridge gets no new ToolbarContent to push,
/// and the OPEN native SwiftUI Menu stays untouched. Content closures are
/// recreated by every parent pass (excluded from ==, same pattern as
/// EquatableByValue/ChatTrailingMenu) so a key change always renders through
/// the freshest parent state.
private struct ChatToolbarHost<Title: View, Trailing: View>: View, Equatable {
    let key: ChatToolbarKey
    @ViewBuilder let title: () -> Title
    @ViewBuilder let trailing: () -> Trailing

    static func == (lhs: Self, rhs: Self) -> Bool {
        let eq = lhs.key == rhs.key
        #if DEBUG
        if !eq {
            // Name the exact field that broke equality so a false-negative
            // key can be pinned from device logs.
            var diffs: [String] = []
            if lhs.key.navTitle != rhs.key.navTitle {
                let l = lhs.key.navTitle, r = rhs.key.navTitle
                if l.sessionTitle != r.sessionTitle { diffs.append("sessionTitle") }
                if l.canEditTitle != r.canEditTitle { diffs.append("canEditTitle") }
                if l.soulName != r.soulName { diffs.append("soulName") }
                if l.modelName != r.modelName { diffs.append("modelName") }
                if l.isGroupBound != r.isGroupBound { diffs.append("isGroupBound") }
                if l.resolvedText != r.resolvedText { diffs.append("resolvedText") }
                if l.hasBinding != r.hasBinding { diffs.append("hasBinding") }
                if l.hasProviders != r.hasProviders { diffs.append("hasProviders") }
                if l.showThinkingBadge != r.showThinkingBadge { diffs.append("showThinkingBadge") }
                if l.thinkingLevelName != r.thinkingLevelName { diffs.append("thinkingLevelName") }
                if l.fallbackTrigger != r.fallbackTrigger { diffs.append("fallbackTrigger") }
                if l.fallbackPulse != r.fallbackPulse { diffs.append("fallbackPulse") }
                if l.titleLocked != r.titleLocked { diffs.append("titleLocked") }
            }
            if lhs.key.messagesEmpty != rhs.key.messagesEmpty { diffs.append("messagesEmpty") }
            if lhs.key.hasSession != rhs.key.hasSession { diffs.append("hasSession") }
            if lhs.key.isForcePulling != rhs.key.isForcePulling { diffs.append("isForcePulling") }
            if lhs.key.iCloudSyncEnabled != rhs.key.iCloudSyncEnabled { diffs.append("iCloudSyncEnabled") }
            if lhs.key.memoryEnabled != rhs.key.memoryEnabled { diffs.append("memoryEnabled") }
            if lhs.key.speakEnabled != rhs.key.speakEnabled { diffs.append("speakEnabled") }
            if lhs.key.showEnhancedCacheToggle != rhs.key.showEnhancedCacheToggle { diffs.append("showEnhancedCacheToggle") }
            if lhs.key.enhancedCacheEnabled != rhs.key.enhancedCacheEnabled { diffs.append("enhancedCacheEnabled") }
            if lhs.key.showFastModeToggle != rhs.key.showFastModeToggle { diffs.append("showFastModeToggle") }
            if lhs.key.fastModeEnabled != rhs.key.fastModeEnabled { diffs.append("fastModeEnabled") }
            NavbarEvalStats.logger.info("[NavbarEval] host == FALSE diffs=[\(diffs.joined(separator: ","))]")
        }
        #endif
        return eq
    }

    var body: some View {
        #if DEBUG
        let _ = {
            NavbarEvalStats.titleEval += 1
            NavbarEvalStats.logger.info("[NavbarEval] toolbarHostEval=\(NavbarEvalStats.titleEval) (toolbarPass=\(NavbarEvalStats.toolbarPass))")
        }()
        #endif
        Color.clear
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
            .toolbar {
                ToolbarItem(placement: .principal) { title() }
                ToolbarItem(placement: .topBarTrailing) { trailing() }
            }
    }
}

/// [T-ios-navbar-uikit-menu] UIKit-owned replacement for ChatTrailingMenu.
///
/// Instrumented device run (NavbarEval, 2026-07-17 19:35): during a streaming
/// reply the toolbar builder ran 34 times while BOTH equatable gates held
/// (titleEval=3, menuEval=2) — yet the presented menu still refreshed. The
/// leak is below SwiftUI: the toolbar bridge re-pushes the navigation item's
/// bar buttons every host pass even when the content views compare equal, and
/// UIKit refreshes/re-anchors a presented UIMenu whenever its bar's items are
/// re-set. No amount of view-level Equatable can stop that.
///
/// Fix: own the button AND the menu in UIKit. The UIViewRepresentable's
/// UIButton instance survives SwiftUI updates, the presented UIMenu is
/// anchored to that stable instance, and updateUIView only touches
/// `button.menu` when the DISPLAYED state actually changed — a no-op SwiftUI
/// pass physically cannot reach the menu. Field names/order are identical to
/// ChatTrailingMenu so the call site only swaps the type name. The DEBUG
/// request-count rows use UIDeferredMenuElement.uncached to stay fresh at
/// each open, matching the SwiftUI Menu's lazy content read.
/// [T-ios-navbar-toolbar-host] Native SwiftUI "..." menu, restored. The
/// UIKit ChatTrailingMenuButton below remains as the proven fallback (it
/// verifiably stops the mid-stream refresh but loses the system's round
/// Liquid-Glass chrome); with the toolbar now hosted in the equatable-gated
/// ChatToolbarHost the ToolbarContent is never rebuilt during streaming, so
/// the native Menu — and its native appearance — should be stable. Item set
/// mirrors the UIKit buildMenu exactly (incl. Compact Messages + the divider
/// between Clear Chat and the iCloud actions from T-chat-menu-compact-entry).
private struct ChatTrailingMenu: View, Equatable {
    let messagesEmpty: Bool
    let hasSession: Bool
    let isForcePulling: Bool
    let iCloudSyncEnabled: Bool
    let memoryEnabled: Bool
    let speakEnabled: Bool
    let showEnhancedCacheToggle: Bool
    let enhancedCacheEnabled: Bool
    /// [T-codex-fast-mode] Mirrors ChatTrailingMenuButton.
    let showFastModeToggle: Bool
    let fastModeEnabled: Bool

    let onNewChat: () -> Void
    let onCompact: () -> Void
    let onClearChat: () -> Void
    let onForceSync: () -> Void
    let onForcePull: () -> Void
    let onOpenTerminal: () -> Void
    let onOpenBrowser: () -> Void
    let onBrowseFiles: () -> Void
    let onSkills: () -> Void
    let onMCPs: () -> Void
    let onMemories: () -> Void
    let setSpeakEnabled: (Bool) -> Void
    let setEnhancedCache: (Bool) -> Void
    let setFastMode: (Bool) -> Void
    let onTokenUsage: () -> Void
    let onCopyRequests: () -> Void
    let onCopySessionData: () -> Void

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.messagesEmpty == rhs.messagesEmpty
            && lhs.hasSession == rhs.hasSession
            && lhs.isForcePulling == rhs.isForcePulling
            && lhs.iCloudSyncEnabled == rhs.iCloudSyncEnabled
            && lhs.memoryEnabled == rhs.memoryEnabled
            && lhs.speakEnabled == rhs.speakEnabled
            && lhs.showEnhancedCacheToggle == rhs.showEnhancedCacheToggle
            && lhs.enhancedCacheEnabled == rhs.enhancedCacheEnabled
            && lhs.showFastModeToggle == rhs.showFastModeToggle
            && lhs.fastModeEnabled == rhs.fastModeEnabled
    }

    var body: some View {
        #if DEBUG
        let _ = {
            NavbarEvalStats.menuEval += 1
            NavbarEvalStats.logger.info("[NavbarEval] menuEval=\(NavbarEvalStats.menuEval) (toolbarPass=\(NavbarEvalStats.toolbarPass))")
        }()
        #endif
        return Menu {
            Button { onNewChat() } label: {
                Label(AppLocalized("New Chat"), systemImage: "square.and.pencil")
            }

            Divider()

            // [T-chat-menu-compact-entry] Compact above Clear Chat.
            Button { onCompact() } label: {
                Label(AppLocalized("Compact Messages"), systemImage: "arrow.down.right.and.arrow.up.left")
            }
            .disabled(messagesEmpty)

            Button(role: .destructive) { onClearChat() } label: {
                Label(AppLocalized("Clear Chat"), systemImage: "trash")
            }
            .disabled(messagesEmpty)

            Divider()

            // iCloud sync actions: iOS 17+ (v2 sync engine) AND the user's
            // iCloud Sync toggle on.
            if #available(iOS 17.0, *), iCloudSyncEnabled {
                Button { onForceSync() } label: {
                    Label(AppLocalized("Force iCloud Sync"), systemImage: "icloud.and.arrow.up")
                }
                .disabled(!hasSession || isForcePulling)

                Button { onForcePull() } label: {
                    Label(AppLocalized("Force Pull Messages"), systemImage: "icloud.and.arrow.down")
                }
                .disabled(!hasSession || isForcePulling)

                Divider()
            }

            Button { onOpenTerminal() } label: {
                Label(AppLocalized("Open Terminal"), systemImage: "terminal")
            }

            Button { onOpenBrowser() } label: {
                Label(AppLocalized("Open Browser"), systemImage: "globe")
            }

            Button { onBrowseFiles() } label: {
                Label(AppLocalized("Browse Chat Files"), systemImage: "folder")
            }

            Divider()

            Button { onSkills() } label: {
                Label(AppLocalized("Skills in Session"), systemImage: "puzzlepiece.extension")
            }

            Button { onMCPs() } label: {
                Label(AppLocalized("MCPs in Session"), systemImage: "wrench.and.screwdriver")
            }

            if memoryEnabled {
                Button { onMemories() } label: {
                    Label(AppLocalized("Memories in Session"), systemImage: "brain.head.profile")
                }
            }

            Toggle(isOn: Binding(
                get: { speakEnabled },
                set: { setSpeakEnabled($0) }
            )) {
                Label(AppLocalized("Speak Responses"), systemImage: "speaker.wave.2")
            }

            // [T-codex-fast-mode-menu-group] Model-control toggles in their
            // own divider-separated section (mirrors the UIKit buildMenu).
            if showEnhancedCacheToggle || showFastModeToggle {
                Divider()

                if showEnhancedCacheToggle {
                    Toggle(isOn: Binding(
                        get: { enhancedCacheEnabled },
                        set: { setEnhancedCache($0) }
                    )) {
                        Label(AppLocalized("Enhanced Cache"), systemImage: "clock.arrow.circlepath")
                    }
                }

                if showFastModeToggle {
                    Toggle(isOn: Binding(
                        get: { fastModeEnabled },
                        set: { setFastMode($0) }
                    )) {
                        Label(AppLocalized("Enable Fast Mode"), systemImage: "bolt.fill")
                    }
                }
            }

            Divider()

            Button { onTokenUsage() } label: {
                Label(AppLocalized("Token Usage"), systemImage: "number")
            }

            #if DEBUG
            Divider()

            Button { onCopyRequests() } label: {
                let n = LastAPIRequestBody.shared.getAll().count
                Label("Copy Requests (\(n))", systemImage: "arrow.up.doc")
            }

            Button { onCopySessionData() } label: {
                Label("Copy Session Data", systemImage: "tray.and.arrow.up")
            }
            #endif
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(ChatColors.primaryText)
        }
        // [T-ios-voiceover-labels] Matches the label the UIKit ellipsis button
        // elsewhere in this file already sets, so both read the same.
        .accessibilityLabel(Text("More options", comment: "VoiceOver label for the overflow menu button"))
    }
}

/// [T-ios-navbar-uikit-menu] PROVEN FALLBACK, currently unused: swaps in for
/// ChatTrailingMenu at the trailingMenu call site if the ChatToolbarHost
/// approach ever regresses. Verified on-device (2026-07-17) to stop the
/// mid-stream menu refresh; its remaining flaw was appearance (no native
/// round glass chrome — mitigated here by sizeThatFits reporting the
/// button's intrinsic size so the system container wraps it like a plain
/// toolbar icon).
private struct ChatTrailingMenuButton: UIViewRepresentable {
    let messagesEmpty: Bool
    let hasSession: Bool
    let isForcePulling: Bool
    let iCloudSyncEnabled: Bool
    let memoryEnabled: Bool
    let speakEnabled: Bool
    let showEnhancedCacheToggle: Bool
    let enhancedCacheEnabled: Bool
    /// [T-codex-fast-mode] Shown only when the active model's provider is a
    /// Codex OAuth instance (OpenAI OAuth, no custom base).
    let showFastModeToggle: Bool
    let fastModeEnabled: Bool

    let onNewChat: () -> Void
    let onCompact: () -> Void
    let onClearChat: () -> Void
    let onForceSync: () -> Void
    let onForcePull: () -> Void
    let onOpenTerminal: () -> Void
    let onOpenBrowser: () -> Void
    let onBrowseFiles: () -> Void
    let onSkills: () -> Void
    let onMCPs: () -> Void
    let onMemories: () -> Void
    let setSpeakEnabled: (Bool) -> Void
    let setEnhancedCache: (Bool) -> Void
    let setFastMode: (Bool) -> Void
    let onTokenUsage: () -> Void
    let onCopyRequests: () -> Void
    let onCopySessionData: () -> Void

    /// The displayed-state snapshot — the ONLY trigger for a menu rebuild.
    struct Key: Equatable {
        let messagesEmpty: Bool
        let hasSession: Bool
        let isForcePulling: Bool
        let iCloudSyncEnabled: Bool
        let memoryEnabled: Bool
        let speakEnabled: Bool
        let showEnhancedCacheToggle: Bool
        let enhancedCacheEnabled: Bool
        let showFastModeToggle: Bool
        let fastModeEnabled: Bool
    }

    private var key: Key {
        Key(messagesEmpty: messagesEmpty, hasSession: hasSession,
            isForcePulling: isForcePulling, iCloudSyncEnabled: iCloudSyncEnabled,
            memoryEnabled: memoryEnabled, speakEnabled: speakEnabled,
            showEnhancedCacheToggle: showEnhancedCacheToggle,
            enhancedCacheEnabled: enhancedCacheEnabled,
            showFastModeToggle: showFastModeToggle,
            fastModeEnabled: fastModeEnabled)
    }

    final class Coordinator {
        var lastKey: Key?
        var parent: ChatTrailingMenuButton
        init(parent: ChatTrailingMenuButton) { self.parent = parent }
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        let cfg = UIImage.SymbolConfiguration(pointSize: 14, weight: .medium)
        button.setImage(UIImage(systemName: "ellipsis", withConfiguration: cfg), for: .normal)
        button.tintColor = UIColor(ChatColors.primaryText)
        button.showsMenuAsPrimaryAction = true
        button.accessibilityLabel = AppLocalized("More options")
        button.menu = Self.buildMenu(key: key, coordinator: context.coordinator)
        context.coordinator.lastKey = key
        return button
    }

    /// Report the button's intrinsic size so the toolbar proposes an
    /// icon-sized footprint and the system wraps it in the same round glass
    /// as a plain toolbar icon (a representable otherwise accepts the full
    /// proposed width -> stretched capsule, the 2026-07-17 regression).
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIButton, context: Context) -> CGSize? {
        uiView.intrinsicContentSize
    }

    func updateUIView(_ button: UIButton, context: Context) {
        // Closures capture view state -- refresh them every pass so actions
        // always see the latest values (excluded from the rebuild decision,
        // exactly like ChatTrailingMenu's ==).
        context.coordinator.parent = self
        guard context.coordinator.lastKey != key else { return }
        context.coordinator.lastKey = key
        button.menu = Self.buildMenu(key: key, coordinator: context.coordinator)
        #if DEBUG
        NavbarEvalStats.menuEval += 1
        NavbarEvalStats.logger.info("[NavbarEval] uikitMenuRebuild=\(NavbarEvalStats.menuEval) (toolbarPass=\(NavbarEvalStats.toolbarPass))")
        #endif
    }

    private static func buildMenu(key: Key, coordinator: Coordinator) -> UIMenu {
        var groups: [UIMenuElement] = []

        groups.append(UIMenu(options: .displayInline, children: [
            UIAction(title: AppLocalized("New Chat"),
                     image: UIImage(systemName: "square.and.pencil")) { _ in coordinator.parent.onNewChat() },
        ]))

        // [T-chat-menu-compact-entry] Compact sits ABOVE Clear Chat in its own
        // group with the destructive clear; the iCloud actions moved to a
        // separate inline group so a divider lands between Clear Chat and the
        // sync rows (user-requested layout).
        groups.append(UIMenu(options: .displayInline, children: [
            UIAction(title: AppLocalized("Compact Messages"),
                     image: UIImage(systemName: "arrow.down.right.and.arrow.up.left"),
                     attributes: key.messagesEmpty ? [.disabled] : []) { _ in coordinator.parent.onCompact() },
            UIAction(title: AppLocalized("Clear Chat"),
                     image: UIImage(systemName: "trash"),
                     attributes: key.messagesEmpty ? [.destructive, .disabled] : [.destructive]) { _ in coordinator.parent.onClearChat() },
        ]))

        // Same gate as before: iOS 17+ (v2 sync engine) AND the user's
        // iCloud Sync toggle on.
        if #available(iOS 17.0, *), key.iCloudSyncEnabled {
            let disabled: UIMenuElement.Attributes = (!key.hasSession || key.isForcePulling) ? [.disabled] : []
            groups.append(UIMenu(options: .displayInline, children: [
                UIAction(title: AppLocalized("Force iCloud Sync"),
                         image: UIImage(systemName: "icloud.and.arrow.up"),
                         attributes: disabled) { _ in coordinator.parent.onForceSync() },
                UIAction(title: AppLocalized("Force Pull Messages"),
                         image: UIImage(systemName: "icloud.and.arrow.down"),
                         attributes: disabled) { _ in coordinator.parent.onForcePull() },
            ]))
        }

        groups.append(UIMenu(options: .displayInline, children: [
            UIAction(title: AppLocalized("Open Terminal"),
                     image: UIImage(systemName: "terminal")) { _ in coordinator.parent.onOpenTerminal() },
            UIAction(title: AppLocalized("Open Browser"),
                     image: UIImage(systemName: "globe")) { _ in coordinator.parent.onOpenBrowser() },
            UIAction(title: AppLocalized("Browse Chat Files"),
                     image: UIImage(systemName: "folder")) { _ in coordinator.parent.onBrowseFiles() },
        ]))

        var sessionGroup: [UIMenuElement] = [
            UIAction(title: AppLocalized("Skills in Session"),
                     image: UIImage(systemName: "puzzlepiece.extension")) { _ in coordinator.parent.onSkills() },
            UIAction(title: AppLocalized("MCPs in Session"),
                     image: UIImage(systemName: "wrench.and.screwdriver")) { _ in coordinator.parent.onMCPs() },
        ]
        if key.memoryEnabled {
            sessionGroup.append(UIAction(title: AppLocalized("Memories in Session"),
                                         image: UIImage(systemName: "brain.head.profile")) { _ in coordinator.parent.onMemories() })
        }
        sessionGroup.append(UIAction(title: AppLocalized("Speak Responses"),
                                     image: UIImage(systemName: "speaker.wave.2"),
                                     state: key.speakEnabled ? .on : .off) { _ in
            coordinator.parent.setSpeakEnabled(!key.speakEnabled)
        })
        groups.append(UIMenu(options: .displayInline, children: sessionGroup))

        // [T-codex-fast-mode-menu-group] Model-control toggles (they shape how
        // the MODEL is invoked, unlike the session-scoped rows above) live in
        // their own inline group so dividers separate them on both sides.
        var modelControlGroup: [UIMenuElement] = []
        if key.showEnhancedCacheToggle {
            modelControlGroup.append(UIAction(title: AppLocalized("Enhanced Cache"),
                                              image: UIImage(systemName: "clock.arrow.circlepath"),
                                              state: key.enhancedCacheEnabled ? .on : .off) { _ in
                coordinator.parent.setEnhancedCache(!key.enhancedCacheEnabled)
            })
        }
        // [T-codex-fast-mode] Only for Codex OAuth models. Injects
        // service_tier=priority (the wire value codex_cli_rs sends for its
        // Fast mode) into Codex requests while enabled; 2x credit burn.
        if key.showFastModeToggle {
            modelControlGroup.append(UIAction(title: AppLocalized("Enable Fast Mode"),
                                              image: UIImage(systemName: "bolt.fill"),
                                              state: key.fastModeEnabled ? .on : .off) { _ in
                coordinator.parent.setFastMode(!key.fastModeEnabled)
            })
        }
        if !modelControlGroup.isEmpty {
            groups.append(UIMenu(options: .displayInline, children: modelControlGroup))
        }

        var tailGroup: [UIMenuElement] = [
            UIAction(title: AppLocalized("Token Usage"),
                     image: UIImage(systemName: "number")) { _ in coordinator.parent.onTokenUsage() },
        ]
        #if DEBUG
        tailGroup.append(UIDeferredMenuElement.uncached { completion in
            let n = LastAPIRequestBody.shared.getAll().count
            completion([
                UIAction(title: "Copy Requests (\(n))",
                         image: UIImage(systemName: "arrow.up.doc")) { _ in coordinator.parent.onCopyRequests() },
                UIAction(title: "Copy Session Data",
                         image: UIImage(systemName: "tray.and.arrow.up")) { _ in coordinator.parent.onCopySessionData() },
            ])
        })
        #endif
        groups.append(UIMenu(options: .displayInline, children: tailGroup))

        return UIMenu(children: groups)
    }
}


// MARK: - Move To Session Sheet

private struct MoveToSessionSheet: View {
    let currentSessionId: String?
    let onSelect: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var sessions: [ChatSession] = []
    @State private var searchText = ""
    @State private var searchMatchedIds: Set<String>?
    @State private var searchTask: Task<Void, Never>?

    /// Prefix used by ContentView for draft session IDs.
    private static let newSessionPrefix = "__new__"

    private var isSearching: Bool { !searchText.isEmpty }

    private var displayedSessions: [ChatSession] {
        let filtered = sessions.filter { $0.id != currentSessionId }
        guard let matchedIds = searchMatchedIds else { return filtered }
        return filtered.filter { matchedIds.contains($0.id) }
    }

    var body: some View {
        NavigationStack {
            List {
                if !isSearching {
                    Button {
                        let newId = "\(Self.newSessionPrefix)\(UUID().uuidString)"
                        dismiss()
                        onSelect(newId)
                    } label: {
                        Label(AppLocalized("New Chat"), systemImage: "plus.bubble")
                    }
                }

                Section(isSearching ? AppLocalized("Results") : AppLocalized("Recent")) {
                    ForEach(displayedSessions) { session in
                        Button {
                            dismiss()
                            onSelect(session.id)
                        } label: {
                            HStack(spacing: 8) {
                                VStack(alignment: .leading, spacing: 4) {
                                    highlightedText(
                                        session.title ?? AppLocalized("New Chat"),
                                        font: .system(size: 16, weight: .semibold),
                                        color: Color(UIColor.label)
                                    )
                                    .lineLimit(1)
                                    highlightedText(
                                        session.lastMessage ?? AppLocalized("No messages yet"),
                                        font: .system(size: 14),
                                        color: Color(UIColor.secondaryLabel)
                                    )
                                    .lineLimit(1)
                                }
                                Spacer(minLength: 1)
                                Text(relativeDate(session.updatedAt))
                                    .font(.system(size: 13))
                                    .foregroundStyle(Color(UIColor.tertiaryLabel))
                            }
                        }
                    }
                }
            }
            .searchable(text: $searchText, prompt: AppLocalized("Search chats..."))
            .onChange(of: searchText) { _ in scheduleSearch() }
            .navigationTitle(AppLocalized("Move to…"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { dismiss() }
                }
            }
        }
        .task {
            sessions = ChatStore.shared.listSessions()
        }
    }

    // MARK: - Search

    private func scheduleSearch() {
        searchTask?.cancel()
        let query = searchText.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            searchMatchedIds = nil
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            let results = await ChatStore.shared.searchSessions(query: query)
            if !Task.isCancelled {
                searchMatchedIds = Set(results.map(\.session.id))
            }
        }
    }

    // MARK: - Highlight

    @ViewBuilder
    private func highlightedText(_ text: String, font: Font, color: Color) -> some View {
        if isSearching, let query = Optional(searchText.trimmingCharacters(in: .whitespaces)),
           !query.isEmpty, text.range(of: query, options: .caseInsensitive) != nil {
            buildHighlighted(text, query: query, font: font, color: color)
        } else {
            Text(text).font(font).foregroundStyle(color)
        }
    }

    private func buildHighlighted(_ text: String, query: String, font: Font, color: Color) -> Text {
        let lower = text.lowercased()
        let lowerQ = query.lowercased()
        var result = Text("")
        var current = text.startIndex

        while current < text.endIndex,
              let range = lower.range(of: lowerQ, range: current..<lower.endIndex) {
            if current < range.lowerBound {
                result = result + Text(text[current..<range.lowerBound])
                    .font(font).foregroundColor(color)
            }
            result = result + Text(text[range])
                .font(font).foregroundColor(.accentColor).bold()
            current = range.upperBound
        }
        if current < text.endIndex {
            result = result + Text(text[current..<text.endIndex])
                .font(font).foregroundColor(color)
        }
        return result
    }

    // MARK: - Relative Date

    private func relativeDate(_ date: Date) -> String {
        let now = Date()
        let calendar = Calendar.current
        let seconds = Int(now.timeIntervalSince(date))
        if calendar.isDateInToday(date) {
            if seconds < 60 {
                return AppLocalized("Just now")
            } else if seconds < 3600 {
                let mins = seconds / 60
                return "\(mins) min ago"
            } else {
                let hrs = seconds / 3600
                return "\(hrs) hr ago"
            }
        } else if calendar.isDateInYesterday(date) {
            return AppLocalized("Yesterday")
        } else {
            let diff = calendar.dateComponents([.day], from: date, to: now)
            if let days = diff.day, days < 7 {
                let formatter = DateFormatter()
                formatter.dateFormat = "EEEE"
                return formatter.string(from: date)
            }
            let formatter = DateFormatter()
            formatter.dateFormat = "M/d"
            return formatter.string(from: date)
        }
    }
}

// MARK: - UIView helpers

private extension UIView {
    /// Walk the responder chain to find the nearest UIViewController.
    var nearestViewController: UIViewController? {
        var responder: UIResponder? = self
        while let r = responder {
            if let vc = r as? UIViewController { return vc }
            responder = r.next
        }
        return nil
    }
}

// MARK: - Face ID Session Lock Overlay
//
// Encapsulates the entire Face ID gate (state + overlay UI + lifecycle
// observers) into a self-contained `View` so that AIChatView's body
// only references it as a single opaque type. This keeps the chat
// body's generic-type depth bounded — earlier inlined attempts pushed
// `__swift_instantiateConcreteTypeFromMangledNameV2` into runtime
// metadata recursion and crashed with EXC_BAD_ACCESS at the stack
// guard region on iOS 26.
private struct SessionLockGateOverlay: View {
    let sessionId: String?

    @ObservedObject private var store = SessionLockStore.shared
    @Environment(\.scenePhase) private var scenePhase
    /// True while an LAContext prompt is in flight — suppresses repeated
    /// auto-prompts when the system overlay itself transiently resigns
    /// the chat view.
    @State private var promptInFlight = false
    /// Set after a failed / cancelled prompt so the overlay renders a
    /// tap-to-retry affordance instead of immediately re-prompting.
    @State private var attemptFailed = false

    var body: some View {
        Group {
            if shouldShow {
                gateView
            }
        }
        .onAppear { evaluate(reason: "onAppear") }
        .onDisappear {
            // [T-faceid-lock-on-exit 2026-05-21] "Lock on exit" mode
            // (idleTimeoutSeconds < 0): clear this session's unlock as
            // soon as the chat view leaves the screen (user popped back
            // to the sidebar / pushed a settings page / navigated to
            // another session). Without this, the unlock stamp survives
            // until the app backgrounds, which doesn't match the user's
            // intent of "locking the moment I leave this chat".
            //
            // Positive idle windows are NOT cleared here — they keep
            // the stamp so navigating away and back within the window
            // doesn't re-prompt for Face ID.
            if let sid = sessionId, store.isLocked(sid), store.idleTimeoutSeconds < 0 {
                store.clearUnlock(sid)
                attemptFailed = false
            }
        }
        .onChange(of: scenePhase) { newPhase in
            handleScene(newPhase)
        }
        .onChange(of: sessionId) { _ in
            attemptFailed = false
            promptInFlight = false
            evaluate(reason: "sessionIdChanged")
        }
    }

    private var shouldShow: Bool {
        guard let sid = sessionId, !sid.isEmpty else { return false }
        return store.isVisuallyLocked(sid)
    }

    private var gateView: some View {
        ZStack {
            Rectangle()
                .fill(.regularMaterial)
                .ignoresSafeArea()
                .overlay {
                    Color(UIColor.systemBackground).opacity(0.4)
                        .ignoresSafeArea()
                }

            VStack(spacing: 18) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 56, weight: .light))
                    .foregroundStyle(.secondary)

                Text("This session is locked")
                    .font(.system(size: 18, weight: .semibold))

                Text(attemptFailed
                     ? "Authentication failed. Tap to try again."
                     : "Tap to unlock with \(BiometricAuth.biometryDisplayName)")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Button {
                    promptForBiometricUnlock()
                } label: {
                    Label(attemptFailed
                          ? AppLocalized("Try again")
                          : AppLocalized("Unlock"),
                          systemImage: "faceid")
                        .font(.system(size: 16, weight: .semibold))
                        .padding(.horizontal, 22).padding(.vertical, 10)
                        .background(.tint, in: Capsule())
                        .foregroundStyle(.white)
                }
            }
            .padding(.horizontal, 32)
        }
        .contentShape(Rectangle())
        .onTapGesture { promptForBiometricUnlock() }
        .transition(.opacity)
    }

    private func evaluate(reason: String) {
        guard let sid = sessionId, !sid.isEmpty else { return }
        guard store.globalEnabled, BiometricAuth.isAvailable else { return }
        guard store.isLocked(sid) else { return }
        store.relockIfIdleExpired(sid)
        guard !store.isCurrentlyUnlocked(sid) else { return }
        guard !promptInFlight, !attemptFailed else { return }
        promptForBiometricUnlock()
    }

    private func promptForBiometricUnlock() {
        guard let sid = sessionId, !sid.isEmpty else { return }
        guard !promptInFlight else { return }
        promptInFlight = true
        Task { @MainActor in
            let reason = AppLocalized("Unlock this chat session")
            let ok = await BiometricAuth.authenticate(reason: reason)
            promptInFlight = false
            if ok {
                store.noteUnlock(sid)
                attemptFailed = false
            } else {
                attemptFailed = true
            }
        }
    }

    private func handleScene(_ phase: ScenePhase) {
        switch phase {
        case .background, .inactive:
            // [T-faceid-lock-background-timeout 2026-05-21] Previously
            // cleared the per-session unlock stamp unconditionally on
            // background/inactive, which made the idle timeout setting
            // (e.g. "10 minutes") behave as if it were "Lock on exit":
            // any backgrounding instantly re-locked the chat. Honor the
            // user's chosen idle window now — only clear the unlock if
            // they explicitly chose "Lock on exit" (idle < 0). For
            // positive idle values the stamp is kept and re-evaluated
            // on .active via evictIdleExpired() + evaluate().
            if let sid = sessionId, store.isLocked(sid) {
                if store.idleTimeoutSeconds < 0 {
                    store.clearUnlock(sid)
                }
                attemptFailed = false
            }
        case .active:
            store.evictIdleExpired()
            evaluate(reason: "scenePhase.active")
        @unknown default:
            break
        }
    }
}

// MARK: - Mic Button (touch-release activation)

/// Mic button that triggers on touch-release (finger lift) to avoid accidental activation.
/// Press highlight is provided via GestureState.
private struct MicButton: View {
    @ObservedObject var speechManager: SpeechRecognitionManager
    @Binding var inputFocused: Bool
    /// Opens the voice-input (VAD) panel. The mic button no longer drives the
    /// in-bar SFSpeech live dictation directly — the panel (with the configured
    /// ASR provider, or the offline System fallback) is the single voice entry
    /// point. The SpeechRecognitionManager path stays in place but is no longer
    /// triggered from here.
    var onTap: () -> Void = {}
    /// True while inline voice mode is active — the button flips to a "T" glyph
    /// that switches back to text input.
    var isVoiceActive: Bool = false
    /// Press feedback for the custom-gesture button.
    @State private var micPressed = false

    private static let diameter: CGFloat = 34

    var body: some View {
        // NOT a Button: SwiftUI's Button has a generous system touch-slop / touch-
        // up tolerance that fires when the finger lifts slightly outside, or moves
        // a little during a scroll/drag — which made this toggle very easy to
        // mis-tap. We use a DragGesture(minimumDistance: 0) and only activate when
        // BOTH the press and release land inside the circle AND movement is tiny.
        // In voice mode: a keyboard glyph = switch back to text input. The keyboard
        // glyph is wider than the mic, so render it ~4pt smaller for parity.
        Image(systemName: isVoiceActive ? "keyboard" : "mic")
            .font(.system(size: isVoiceActive ? 15 : 18, weight: .medium))
            .foregroundStyle(ChatColors.secondaryText)
            .frame(width: Self.diameter, height: Self.diameter)
            .background(ChatColors.inputIconBg)
            .clipShape(Circle())
            .overlay(Circle().stroke(ChatColors.inputIconBorder, lineWidth: 0.5))
            .scaleEffect(micPressed ? 0.9 : 1.0)
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        micPressed = Self.isInside(value.location)
                    }
                    .onEnded { value in
                        let started = Self.isInside(value.startLocation)
                        let ended = Self.isInside(value.location)
                        let moved = hypot(value.translation.width, value.translation.height)
                        micPressed = false
                        guard started, ended, moved < 10 else { return }
                        inputFocused = false
                        onTap()
                    }
            )
            .accessibilityAddTraits(.isButton)
            .accessibilityLabel(isVoiceActive
                ? Text("Switch to text input", comment: "Mic button exits voice mode")
                : Text("Voice input", comment: "Mic button opens voice panel"))
    }

    /// Whether a point (in the button's local space) is within the visible circle.
    private static func isInside(_ p: CGPoint) -> Bool {
        let r = diameter / 2
        let dx = p.x - r, dy = p.y - r
        return (dx * dx + dy * dy) <= r * r
    }
}

// MARK: - Speech Language Picker Sheet

private struct SpeechLanguagePickerSheet: View {
    @ObservedObject var speechManager: SpeechRecognitionManager
    @Environment(\.dismiss) private var dismiss
    @State private var searchText = ""

    private var filteredLocales: [Locale] {
        if searchText.isEmpty { return speechManager.availableLocales }
        let query = searchText.lowercased()
        return speechManager.availableLocales.filter { loc in
            let name = speechManager.displayName(for: loc).lowercased()
            let id = loc.identifier.lowercased()
            return name.contains(query) || id.contains(query)
        }
    }

    /// Indices where the preferred/non-preferred boundary lies for section headers.
    private var preferredCodes: Set<String> {
        Set(Locale.preferredLanguages.map { Locale(identifier: $0).language.languageCode?.identifier ?? "" })
    }

    var body: some View {
        NavigationStack {
            List {
                let preferred = filteredLocales.filter { preferredCodes.contains($0.language.languageCode?.identifier ?? "") }
                let others = filteredLocales.filter { !preferredCodes.contains($0.language.languageCode?.identifier ?? "") }

                if !preferred.isEmpty {
                    Section(AppLocalized("Preferred", comment: "Section header for preferred speech languages")) {
                        ForEach(preferred, id: \.identifier) { loc in
                            languageRow(loc)
                        }
                    }
                }

                if !others.isEmpty {
                    Section(AppLocalized("All Languages", comment: "Section header for all speech languages")) {
                        ForEach(others, id: \.identifier) { loc in
                            languageRow(loc)
                        }
                    }
                }
            }
            .searchable(text: $searchText, prompt: Text("Search Languages", comment: "Search field placeholder for speech language picker"))
            .navigationTitle(Text("Voice Language", comment: "Navigation title for speech language picker"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Done", comment: "Dismiss speech language picker")) {
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func languageRow(_ loc: Locale) -> some View {
        Button {
            speechManager.setLanguage(loc)
            dismiss()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(speechManager.displayName(for: loc))
                        .foregroundStyle(.primary)
                    Text(loc.identifier)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if loc.identifier == speechManager.locale.identifier {
                    Image(systemName: "checkmark")
                        .foregroundStyle(Color.accentColor)
                        .fontWeight(.semibold)
                }
            }
        }
    }
}

// MARK: - Compact Summary Sheet

struct CompactSummarySheet: View {
    let summary: String
    /// Optional revert action. When provided, a "Revert Compact" button is shown
    /// below the summary; tapping it invokes the closure (typically wired to
    /// AIChatViewModel.revertCompact()) and dismisses the sheet.
    var onRevert: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var showRevertConfirm = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                SelectableTextView(text: summary)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                if onRevert != nil {
                    Divider()
                    Button(role: .destructive) {
                        showRevertConfirm = true
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.uturn.backward")
                            Text("Revert Compact")
                        }
                        .font(.system(size: 15, weight: .medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.red)
                }
            }
            .navigationTitle("Compact Summary")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        UIPasteboard.general.string = summary
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                    } label: {
                        Image(systemName: copied ? "checkmark.circle.fill" : "doc.on.doc.fill")
                            .foregroundStyle(copied ? .green : .secondary)
                    }
                }
            }
            .alert("Revert this compact?", isPresented: $showRevertConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Revert", role: .destructive) {
                    let action = onRevert
                    dismiss()
                    action?()
                }
            } message: {
                Text("The summary will be discarded and the messages it covered will become active again. This may push the conversation past the model's context window — if that happens, long-press a message to re-compact from that point.")
            }
        }
        .presentationDetents([.large])
    }
}

/// UITextView wrapper for efficient rendering of long selectable text.
private struct SelectableTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.isEditable = false
        tv.isSelectable = true
        tv.font = .systemFont(ofSize: 14)
        tv.textColor = .label
        tv.backgroundColor = .clear
        tv.textContainerInset = UIEdgeInsets(top: 0, left: 0, bottom: 20, right: 0)
        tv.textContainer.lineFragmentPadding = 0
        tv.showsVerticalScrollIndicator = true
        tv.alwaysBounceVertical = true
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        if tv.text != text {
            tv.text = text
        }
    }
}

// MARK: - Token Usage Sheet

private struct TokenUsageSheet: View {
    @ObservedObject var vm: AIChatViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                let s = vm.sessionTokenStats

                Section("Context") {
                    StatRow(label: "Context Used", value: formatted(s.context), icon: "text.alignleft")
                    if let window = vm.currentModelContextWindow {
                        StatRow(label: "Context Window", value: formatted(window), icon: "arrow.left.and.right")
                    }
                    if let maxOut = vm.currentModelMaxOutputTokens {
                        StatRow(label: "Max Output", value: formatted(maxOut), icon: "arrow.up.to.line")
                    }
                }

                if let thinkingInfo = vm.currentModelThinkingInfo {
                    Section("Thinking") {
                        StatRow(label: "Thinking", value: thinkingInfo.enabled ? "On" : "Off", icon: "lightbulb", customIcon: Image("ThinkingIcon"))
                        if thinkingInfo.enabled {
                            StatRow(label: "Level", value: thinkingInfo.level, icon: "slider.horizontal.3")
                        }
                        StatRow(label: "Supported", value: thinkingInfo.supported ? "Yes" : "No", icon: "checkmark.circle")
                    }
                }

                Section("Tokens (Session Total)") {
                    let inputTotal = s.input + s.cacheRead + s.cacheWrite
                    StatRow(label: "Input (incl. cache)", value: formatted(inputTotal), icon: "arrow.down.circle")
                    StatRow(label: "Output", value: formatted(s.output), icon: "arrow.up.circle")
                }

                Section("Cache (Session Total)") {
                    StatRow(label: "Cache Read", value: formatted(s.cacheRead), icon: "arrow.triangle.2.circlepath")
                    StatRow(label: "Cache Write", value: formatted(s.cacheWrite), icon: "square.and.arrow.down")
                    // [T-ios-token-usage-cache-hit-rate] Cache hit rate = cache read
                    // over total input (incl. cache) — the same denominator as the
                    // "Input (incl. cache)" row above (input + cacheRead + cacheWrite).
                    // Shows how much of this session's input was served from cache.
                    let totalInput = s.input + s.cacheRead + s.cacheWrite
                    if totalInput > 0 && s.cacheRead > 0 {
                        let hitRate = Double(s.cacheRead) / Double(totalInput) * 100
                        StatRow(label: "Cache Hit Rate", value: String(format: "%.1f%%", hitRate), icon: "percent")
                    }
                }

                Section("Speed") {
                    let speed = vm.sessionOutputTokensPerSecond
                    StatRow(label: "Output Speed", value: speed > 0 ? String(format: "%.1f tok/s", speed) : "—", icon: "speedometer")
                }

                Section("Agent Loop") {
                    StatRow(label: "Total Loops", value: "\(s.loopCount)", icon: "repeat")
                }
            }
            .navigationTitle("Session Token Usage")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func formatted(_ n: Int) -> String {
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.1fK", Double(n) / 1_000) }
        return "\(n)"
    }
}

private struct StatRow: View {
    let label: LocalizedStringKey
    let value: String
    let icon: String
    var customIcon: Image?

    var body: some View {
        HStack {
            if let customIcon {
                Label {
                    Text(label)
                } icon: {
                    customIcon
                        .resizable()
                        .frame(width: 14, height: 14)
                }
            } else {
                Label(label, systemImage: icon)
            }
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }
}

// MARK: - Empty Chat Directory Timeline

/// Onboarding view rendered in the center of a New Chat (no messages yet).
/// Shows the per-session vs cross-session directory layout under
/// `/var/minis/` as a 2-column folder grid so the user understands what's
/// available before typing.
///
/// Source-of-truth for the descriptions: AIChatViewModel.swift system-prompt
/// directory listing (`Shared directory /var/minis/ ...`). Keep them aligned
/// when either side changes.
private struct EmptyChatDirectoryTimeline: View {
    var onBrowse: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "lightbulb")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(ChatColors.secondaryText)
                Text("Workspace layout")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ChatColors.secondaryText)
            }
            Text("Each chat gets its own **workspace**, **attachments**, **offloads**, and **browser** folders — wiped when the session ends.")
                .font(.system(size: 12))
                .foregroundStyle(ChatColors.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            Text("All chats share **shared**, **skills**, **memory**, and **mounts** — persistent across sessions.")
                .font(.system(size: 12))
                .foregroundStyle(ChatColors.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(action: onBrowse) {
                    HStack(spacing: 4) {
                        Image(systemName: "folder")
                            .font(.system(size: 10, weight: .semibold))
                        Text("Browse Chat Files")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundStyle(ChatColors.primaryText)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(
                        Capsule().fill(ChatColors.secondaryBg)
                    )
                    .overlay(
                        Capsule().stroke(ChatColors.toolBorder, lineWidth: 0.5)
                    )
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 2)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: 460, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(ChatColors.secondaryBg.opacity(0.6))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(ChatColors.toolBorder, lineWidth: 0.5)
        )
        .fixedSize(horizontal: false, vertical: true)
    }
}


// MARK: - Session Loading Card

/// Three-dot carousel indicator: dots pulse in a staggered wave. The single
/// loading language for the session-entry path (loading card, in-place reload,
/// kernel boot) — replaces every system ProgressView spinner there, so the
/// old "菊花" never appears after / alongside the new loading UI.
struct LoadingDotsView: View {
    var dotSize: CGFloat = 10
    var color: Color = .accentColor
    @State private var animating = false

    var body: some View {
        HStack(spacing: dotSize * 0.7) {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .fill(color)
                    .frame(width: dotSize, height: dotSize)
                    .scaleEffect(animating ? 1.0 : 0.55)
                    .opacity(animating ? 0.95 : 0.3)
                    .animation(
                        .easeInOut(duration: 0.5)
                            .repeatForever(autoreverses: true)
                            .delay(Double(i) * 0.16),
                        value: animating
                    )
            }
        }
        .onAppear { animating = true }
    }
}

/// Loading placeholder shown while ENTERING a session whose messages haven't
/// loaded yet — replaces the bare system spinner for that first-open moment
/// (black screen → lone spinner → content snap). Just the bare three-dot
/// carousel, centered — no card container or label (visual-simplification
/// feedback: the rounded material box + "Loading conversation…" text read as
/// too heavy). The container's transition fades/scales it out when loading
/// completes. Reload paths with content on screen never show this — see the
/// isLoadingSession overlay in AIChatView.
private struct SessionLoadingCard: View {
    var body: some View {
        LoadingDotsView(dotSize: 8)
            .frame(height: 24)
    }
}

/// [voice-correction §6] Build the conversation context for a correction: the last user
/// message and the agent reply that followed it.
///
/// Reads the in-memory `messages` the chat is already rendering — §12.1 explicitly forbids
/// a DB query here, since this runs on the correction path. Both halves are truncated by
/// `ConversationContextTruncator` (500-char soft cap, extended to the next sentence
/// terminator so a quoted fragment never ends mid-sentence).
///
/// This is what lets correction work on a FRESH INSTALL. With both learned tables empty and
/// no context, the prompt has no evidence at all and the model correctly refuses to change
/// anything — which is exactly why the suggestion icon never appeared on a new device.
func voiceCorrectionContext(from messages: [ChatMessage]) -> ConversationContext {
    // Budgeted builder (2026-07-16): sentence-split + segment + rarity-rank the
    // recent turns, expanding the message count under a 5k-char budget, instead
    // of the original "last user + reply, first 500 chars each". §12.1 still
    // holds — this reads only the in-memory messages the chat is rendering.
    // Only real conversation turns feed the context. compactDivider and
    // systemInfo rows would otherwise be classified as "assistant" and leak
    // UI copy into the rare-terms digest.
    let source = messages
        .filter { $0.role == .user || $0.role == .assistant }
        .map { msg -> CorrectionSourceMessage in
            // Assistant turns keep their prose in `blocks` (text blocks), not
            // in `content` — reading only `content` silently dropped every AI
            // reply from the correction context. `content` can also hold a
            // stale FRAGMENT alongside full blocks (observed: a 34-char title
            // while the reply lived in blocks), so for assistant turns the
            // text blocks are the source of truth and `content` is only the
            // fallback. Tool/thinking/info blocks stay out — their payloads
            // are commands and traces, not conversational vocabulary.
            var text = msg.content
            if msg.role == .assistant, !msg.blocks.isEmpty {
                let blockText = msg.blocks
                    .filter { $0.kind == .text }
                    .map(\.content)
                    .joined(separator: "\n")
                if !blockText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    text = blockText
                }
            }
            return CorrectionSourceMessage(role: msg.role == .user ? .user : .assistant,
                                           text: TypedVocabularyBuilder.stripAttachmentMarkup(text))
        }
    return CorrectionContextBuilder.build(messages: source)
}

/// [T-browser-download-ux-v2] Sheet item for "Show in Files" from the
/// downloads panel: opens the file browser rooted at the session workspace
/// and highlights this filename.
struct DownloadLocateTarget: Identifiable {
    let filename: String
    var id: String { filename }
}
