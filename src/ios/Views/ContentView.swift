import SwiftUI

private let shareLog = AppLogger(category: "Share")
private let draftLog = AppLogger(category: "DraftSession")

// MARK: - Session context-menu action channel

/// [T-ios-crash-contextmenu-uaf] Action relay for the sidebar session context
/// menu. Stored as @State in ContentView (stable lifetime); the concrete
/// SessionContextMenu view holds a reference to it but Equatable ignores it,
/// so SwiftUI's attribute-graph copy/release of the view struct only
/// retains/releases this single class reference — no closure captures that
/// could dangle.
@MainActor
private final class SessionMenuActionChannel {
    var handler: ((SessionMenuAction) -> Void)?
    func send(_ action: SessionMenuAction) { handler?(action) }
}

private enum SessionMenuAction {
    case togglePin(String)
    case exportJSON(String)
    case exportText(String)
    case editTitle(String)
    case regenerateTitle(String)
    case lockSession(String)
    case unlockSession(String)
    case duplicate(String)
    case forceSync(String)
    case forcePull(String)
    case select(String)
    case delete(String)
}

#if DEBUG
// TEMPORARY: SessionRow height probe to confirm List cell-height estimation
// jitter. Logs each distinct measured row height once (deduped) so we know
// the true height distribution before pinning a fixed .frame. Remove after.
private struct RowHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
private let rowHeightLog = AppLogger(category: "RowHeight")

// Availability-gated scroll-phase probe (onScrollPhaseChange is iOS 18+).
private struct ScrollPhaseProbe: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { old, new in
                rowHeightLog.info("[SCROLL] \(old) -> \(new)")
            }
        } else {
            content
        }
    }
}

private let rowHeightSeenLock = NSLock()
nonisolated(unsafe) private var rowHeightSeen = Set<Int>()
private func probeRowHeight(_ h: CGFloat, _ tag: String) {
    // Ignore the pre-layout zero/garbage pass — only real measured heights.
    guard h > 1, h.isFinite else { return }
    let key = Int((h * 2).rounded())  // 0.5pt buckets
    rowHeightSeenLock.lock()
    let isNew = rowHeightSeen.insert(key).inserted
    rowHeightSeenLock.unlock()
    if isNew {
        rowHeightLog.info("[ROWH] \(tag) height=\(String(format: "%.1f", h))pt")
    }
}
#endif

/// Sheets triggered from the toolbar menu, consolidated into a single `.sheet(item:)`.
enum ToolSheet: String, Identifiable {
    case settings
    case rootfsManagement
    case browser
    case browserManagement
    case syncMigrationDetail
    var id: String { rawValue }
}

struct ContentView: View {
    /// Prefix for draft session IDs. Each new chat gets a unique suffix
    /// so SwiftUI's `.id()` correctly destroys old views and creates new ones.
    private static let newSessionPrefix = "__new__"

    /// Whether a session ID represents an unsaved draft session.
    private static func isNewSessionId(_ id: String?) -> Bool {
        id?.hasPrefix(newSessionPrefix) == true
    }

    /// Generate a unique draft session ID.
    private static func makeNewSessionId() -> String {
        "\(newSessionPrefix)\(UUID().uuidString)"
    }

    private static let groupSeparator = "__grp__"

    /// Generate a draft session ID that also carries a model group selection.
    private static func makeNewSessionId(groupId: String) -> String {
        "\(newSessionPrefix)\(UUID().uuidString)\(groupSeparator)\(groupId)"
    }

    /// Extract the group ID embedded in a draft session ID, if present.
    private static func extractGroupId(from id: String) -> String? {
        guard let range = id.range(of: groupSeparator) else { return nil }
        let groupId = String(id[range.upperBound...])
        return groupId.isEmpty ? nil : groupId
    }

    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject var shareCoordinator: ShareCoordinator
    @ObservedObject private var deepLink = DeepLinkCoordinator.shared
    /// Subscribe to the router so changes to its `@Published` fields are
    /// observed by SwiftUI — without this, `onChange(of: router.newChatTrigger)`
    /// reads a static value and never fires after the view mounts. Quick
    /// Actions that arrive before this view's `.onReceive(.newChatRequested)`
    /// publisher is attached (typical cold-launch race) would otherwise be
    /// dropped.
    @ObservedObject private var quickActionRouter = QuickActionRouter.shared
    // [T-ios-ipad-sidebar-running-indicator-stale] Observe the activity /
    // concurrency trackers AT THE LIST LEVEL too (SessionRow already observes
    // them, but the #584 id-list ForEach reuses an already-materialized cell
    // for the running session and swallows the row's @ObservedObject change
    // when only the running flag flips — id list & resolved ChatSession value
    // are unchanged). Observing here re-evaluates the list body when
    // activeSessions / suspended flips, which regenerates each row's composite
    // diff key (see sessionRowKeys) so the ForEach can't skip re-materializing
    // the row whose running state changed.
    @ObservedObject private var sidebarActivityTracker = SessionActivityTracker.shared
    @ObservedObject private var sidebarConcurrencyManager = SessionConcurrencyManager.shared
    @State private var sessions: [ChatSession] = []
    /// [T-ios-session-list-equatable-jank] id → ChatSession lookup backing the
    /// sidebar rows. Held in @State (not a per-body-eval computed `[String:
    /// ChatSession]`) on purpose: a plain `let byId = computedDict` inside the
    /// List builder was captured as an AttributeGraph node input, so EVERY
    /// transaction flush deep-compared the whole dictionary
    /// (Dictionary.== → ChatSession.== × N) — the dominant scroll-deceleration
    /// CPU cost (HangDetector: flushTransactions → AGGraphSetOutputValue →
    /// Dictionary.== → ChatSession.==; Instruments: app-update phase 60–587ms
    /// spikes). @State is diffed by storage-box identity, not by deep value, so
    /// reading it from the row closure costs nothing per frame. Rebuilt only
    /// when `sessions` changes (see .onChange below).
    @State private var sessionsByIdCache: [String: ChatSession] = [:]
    /// Soul name shown as the sidebar title. Sourced from SOUL.md, falls
    /// back to "Minis". Refreshed whenever SoulStore posts .soulMdChanged.
    @State private var soulName: String = SoulStore.cachedMetadata.name.isEmpty
        ? "Minis" : SoulStore.cachedMetadata.name
    /// Subtitle state shown under the "Minis" sidebar title. nil hides the
    /// row; otherwise it renders as small capsules per type or a single
    /// status string. Refreshed by a 5s timer.
    @State private var migrationSubtitle: SyncSubtitleState?

    enum SyncSubtitleState: Equatable {
        case paused
        case migrating(percent: Int, byType: [(label: String, count: Int)])
        case syncing(byType: [(label: String, count: Int)])
        case upToDate
        /// Engine is not running yet (boot delay / transport still
        /// connecting / coming back from background). Title shows a
        /// neutral 'waiting' icon — not an error.
        case waiting

        static func == (a: SyncSubtitleState, b: SyncSubtitleState) -> Bool {
            switch (a, b) {
            case (.paused, .paused): return true
            case (.upToDate, .upToDate): return true
            case (.waiting, .waiting): return true
            case (.migrating(let p1, let t1), .migrating(let p2, let t2)):
                return p1 == p2 && t1.map { $0.label + "\($0.count)" } == t2.map { $0.label + "\($0.count)" }
            case (.syncing(let t1), .syncing(let t2)):
                return t1.map { $0.label + "\($0.count)" } == t2.map { $0.label + "\($0.count)" }
            default: return false
            }
        }
    }
    // [T-ios-migration-timer-sessionlist-uaf-crash] The migration-subtitle
    // refresh cadence used to be a process-lived `Timer.publish(every:5).autoconnect()`
    // subscribed via `.onReceive` on the sidebar Group (see sessionList). A
    // process-lived Combine publisher whose sink attribute AttributeGraph rebuilds
    // on every body transaction is a use-after-free hazard: a 5s tick delivered into
    // the sink while the graph tears down/rebuilds that attribute releases a dangling
    // sink closure (crash below). Replaced with a `.task`-driven async loop whose
    // lifetime SwiftUI owns and cancels deterministically — no graph-bound publisher.
    // See migrationSubtitleRefreshInterval / migrationSubtitleLoop.
    @State private var remoteDeviceSessions: [(device: SyncDevice, sessions: [ChatSession])] = []
    // showSettings consolidated into activeToolSheet (.settings)
    @State private var showTerminal = false
    @State private var showAlarmList = false
    @State private var hasAlarms = false
    @State private var activeToolSheet: ToolSheet?
    #if DEBUG
    // [debug] Keep the screen awake (disable the idle/auto-lock timer) while the
    // app is in the foreground. Memory-only on purpose — NOT persisted, so it
    // resets to off on every app launch. iOS automatically clears
    // `isIdleTimerDisabled` when the app leaves the foreground, so this is
    // re-applied on scenePhase == .active below; "foreground only" falls out for
    // free. Toggled from the DEBUG-only menu item in the sidebar more-menu.
    @State private var keepScreenAwake = false
    #endif
    @StateObject private var browserPool = BrowserTabPool()
    @State private var selectedSessionId: String?
    /// Shadow of the previously-selected session id, used to identify the
    /// outgoing vm in `onChange(selectedSessionId)` so we can suspend it
    /// for the AttributeGraph race window. SwiftUI's iOS 16 onChange API
    /// only delivers `newValue`; we maintain the previous value ourselves.
    @State private var previousSelectedSessionId: String?
    /// [T-ios-session-coldload-listsessions-block] Coalesces the delayed
    /// outgoing-session preview refresh so rapid session switching schedules
    /// one trailing listSessions() (after the incoming load settles) instead
    /// of stacking full scans onto the serialized ChatStore actor.
    @State private var outgoingPreviewRefreshWork: DispatchWorkItem?
    // [T-ios-listsessions-refresh-coalesce] Serialize session-list refreshes so
    // the ~12 refresh call sites never run listSessions() concurrently. A second
    // request that lands while one is in flight sets `pending` and is served by a
    // single trailing run — instead of each site spawning its own Task and
    // assigning its own array snapshot (the memgraph showed 3 live [ChatSession]
    // arrays from concurrent refreshes). Combined with the ChatStore-side cache
    // (T-ios-listsessions-cache), a clean refresh now returns the same cached
    // array, so this mainly prevents redundant Task churn.
    @State private var sessionRefreshInFlight = false
    @State private var sessionRefreshPending = false
    /// Whether the initial session load has completed (prevents showing the list before we decide to auto-navigate).
    @State private var didInitialLoad = false
    /// Controls sidebar visibility on iPad (automatic handles iPhone collapse).
    @State private var columnVisibility: NavigationSplitViewVisibility = .automatic

    /// Launch screen preference: 0=Auto, 1=Last Session, 2=New Chat.
    @AppStorage("launchScreen") private var launchScreen: Int = 0
    /// FAB position preference: false = right (default), true = left.
    @AppStorage("fabOnLeft") private var fabOnLeft = false
    /// Mirror of `SyncV2Bootstrap.isEnabled` so SwiftUI re-evaluates
    /// the iCloud-gated menu entries (per-session Force Sync / Force
    /// Pull and the multi-select Force Sync) the moment the user
    /// flips the toggle in Settings — without this, calling the
    /// static getter inline would only re-read on the next unrelated
    /// state change. `SyncV2Bootstrap.setEnabled` writes the same key.
    @AppStorage("cloudSync.v2.enabled") private var iCloudSyncEnabled: Bool = false
    /// Current drag offset of the FAB (reset to 0 on drop).
    @State private var fabDragOffset: CGFloat = 0

    // Multi-select
    @State private var isSelecting = false
    @State private var selectedIds: Set<String> = []
    /// Transient banner shown after a Force Sync action runs against
    /// a selection. Cleared after a few seconds via DispatchQueue.
    @State private var forceSyncToast: String? = nil
    @State private var forceSyncInFlight = false
    @State private var scrollToId: String?
    @State private var showDeleteConfirm = false
    @State private var isComputingDelete = false
    @State private var showExportPreview = false
    @State private var isExporting = false
    @State private var exportFileURL: URL?
    @State private var exportProgress: (done: Int, total: Int)? = nil
    @State private var exportSummary: ExportSummary? = nil
    @State private var deleteInfo: DeleteInfo?

    // Single delete
    @State private var sessionToDelete: ChatSession?
    @State private var singleDeleteInfo: DeleteInfo?

    // Title regeneration
    @State private var regeneratingTitleSessionId: String?

    // Edit session
    @State private var sessionToEdit: ChatSession?

    // [T-ios-crash-contextmenu-uaf] Stable action relay for session context menus.
    @State private var menuActions = SessionMenuActionChannel()

    // Search
    @State private var showSearchBar = false
    private var isSearching: Bool { showSearchBar && !searchText.isEmpty }
    @State private var searchText = ""
    /// Session IDs that matched the current search query (nil = no active filter).
    @State private var searchMatchedIds: Set<String>?
    /// Per-session matched-content snippet for sessions whose match was on
    /// message body rather than title — surfaced in the row's subtitle slot
    /// in place of the generic `lastMessage` preview. Empty when no active
    /// search or for title-only matches. Mirrors Android
    /// `SessionListViewModel.searchSnippets` (commit 545d585).
    /// T-search-highlight 8edb74f2.
    @State private var searchMatchSnippets: [String: String] = [:]
    @State private var searchTask: Task<Void, Never>?

    /// Width threshold below which the layout collapses to single-column (iPhone-style).
    private let compactThreshold: CGFloat = 700
    /// Whether the device is an iPad (iPhones always use stack layout regardless of width).
    private let isIPad = UIDevice.current.userInterfaceIdiom == .pad
    /// Whether the current window is wide enough for two-column layout.
    @State private var isWideLayout = false
    /// Navigation path for stack (compact) layout.
    @State private var navigationPath = NavigationPath()
    /// Tracks the session ID currently visible on the compact navigation stack.
    @State private var currentStackSessionId: String?
    /// The real session ID after a draft session is persisted (iPad only).
    /// While set, the AIChatView for this session stays alive even though
    /// selectedSessionId may still be a draft ID.
    @State private var newSessionRealId: String?
    /// The draft ID that owns the current new-session view (iPad only).
    /// Used to redirect back when the user taps the real session row.
    @State private var activeDraftId: String?

    /// Last `QuickActionRouter.newChatTrigger` value we've already routed.
    /// SwiftUI's `onChange` fires for transitions, not for initial values,
    /// so we use this to decide whether `onAppear` (or `onChange`) still
    /// owes a `handleNewChatRequest()` call.
    @State private var consumedQuickActionTrigger: Int = 0

    /// Set when `handleNewChatRequest()` had to pop the iPhone
    /// NavigationStack before it could open the new draft session.
    /// `onChange(of: navigationPath.count == 0)` watches this and
    /// dispatches the new-draft open once the pop has fully settled.
    @State private var pendingNewChatAfterPop: Bool = false
    /// Pre-computed session id for the pending pop+open dance, so the
    /// id we hand to `QuickActionWorkflow.attachTargetSession` here
    /// matches the one `openSession` will use after the pop commits.
    @State private var pendingNewChatTargetId: String? = nil

    var body: some View {
        GeometryReader { geo in
            let wide = isIPad && geo.size.width >= compactThreshold
            Group {
                if wide {
                    splitLayout
                } else {
                    stackLayout
                }
            }
            .overlay(alignment: .top) {
                if let toast = forceSyncToast {
                    ForceSyncToastBanner(text: toast)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .padding(.top, 8)
                }
            }
            .animation(.easeInOut(duration: 0.25), value: forceSyncToast)
            .onChange(of: wide) { newWide in
                isWideLayout = newWide
            }
            .onAppear {
                isWideLayout = wide
                wireMenuActions()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .newChatRequested)) { _ in
            handleNewChatRequest()
        }
        // Cold-launch belt-and-braces: a Home Screen Quick Action that
        // fires before `.onReceive(.newChatRequested)` is attached
        // (WindowGroup still mounting) would otherwise be lost. The
        // router bumps `newChatTrigger` whenever it handles a shortcut.
        //
        // `quickActionRouter` is an `@ObservedObject`, so SwiftUI re-runs
        // body on every bump and `onChange` actually fires. The
        // `consumedQuickActionTrigger` state below tracks the last value
        // we've already routed — on cold launch the router might bump to
        // 1 (or higher, if the user invokes shortcuts multiple times
        // before the WindowGroup mounts) BEFORE our `.onAppear` runs;
        // we therefore also check the initial value on appear and route
        // any unconsumed bumps then.
        .onChange(of: quickActionRouter.newChatTrigger) { newValue in
            guard newValue != consumedQuickActionTrigger else { return }
            consumedQuickActionTrigger = newValue
            handleNewChatRequest()
        }
        .onReceive(QuickActionWorkflow.shared.$state) { newState in
            // Workflow advanced to pendingDispatch (either same-runloop
            // because we were already home, or after markHome fired
            // from a navigation change). Open the new session now.
            if case .pendingDispatch = newState {
                openSessionForPendingQuickAction()
            }
        }
        .onAppear {
            if quickActionRouter.newChatTrigger != consumedQuickActionTrigger {
                consumedQuickActionTrigger = quickActionRouter.newChatTrigger
                // Defer one runloop so the NavigationStack body has a
                // chance to attach `$navigationPath` before we append to
                // it — otherwise the append on a freshly-mounted stack
                // can be lost.
                DispatchQueue.main.async {
                    handleNewChatRequest()
                }
            }
            // The Appearance language picker wrote "pendingSettingsReopen"
            // right before changing appLanguage, which forced the root
            // `.id(appLanguage)` rebuild that just dropped + re-mounted us.
            // Reopen the Settings sheet so the user lands back where they
            // were instead of stranded on the chat list. SettingsSheet's
            // own onAppear pushes the saved destination onto its navPath.
            if UserDefaults.standard.string(forKey: "pendingSettingsReopen") != nil {
                DispatchQueue.main.async {
                    activeToolSheet = .settings
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .sessionDidCreate)) { note in
            guard isWideLayout, let newId = note.object as? String else { return }
            let noteDraftId = (note.userInfo as? [String: String])?["draftId"]
            draftLog.info("🔑DRAFT sessionDidCreate realId=\(newId) noteDraftId=\(noteDraftId ?? "nil") selId=\(selectedSessionId ?? "nil") curReal=\(newSessionRealId ?? "nil") curDraft=\(activeDraftId ?? "nil")")
            // Verify this notification came from the currently active draft.
            // A late notification from a previous (now-destroyed) draft must be ignored.
            guard let selId = selectedSessionId, Self.isNewSessionId(selId),
                  noteDraftId == selId else {
                draftLog.info("🔑DRAFT sessionDidCreate IGNORED (draftId mismatch or not a draft)")
                // [T-ios-state-publish-offmain-crash] ChatStore (an actor) posts
                // .sessionDidCreate/.sessionDidUpdate from its background
                // executor; NotificationCenter delivers synchronously on that
                // thread, so this onReceive closure can run off-main. A bare
                // Task{} started here inherits the (background) execution context,
                // so `sessions =` (a @State write) lands off-main → "Publishing
                // changes from background threads" + AttributeGraph corruption of
                // the [ChatSession]/[String:ChatSession] state it deep-compares,
                // crashing in ChatSession.== / deinit during flushTransactions.
                // This onReceive can be delivered off-main, so hop explicitly —
                // refreshSessionList's @State writes must land on the main thread.
                Task { @MainActor in
                    refreshSessionList()
                }
                return
            }
            newSessionRealId = newId
            activeDraftId = selId
            draftLog.info("🔑DRAFT sessionDidCreate ACCEPTED newSessionRealId=\(newId) activeDraftId=\(selId)")
            // [T-ios-state-publish-offmain-crash] force main-thread @State write
            Task { @MainActor in
                refreshSessionList()
            }
        }
        .onReceive(
            // Throttle (not debounce): session-list updates are infrequent (one
            // per agent tool round, seconds apart), but a long multi-tool task
            // emits a steady stream of them. `.debounce` was reset by every new
            // event, so during a continuously-running task the list NEVER
            // refreshed until the task fully stopped — the row stayed on its
            // stale preview ("No messages yet") the whole time. `.throttle`
            // fires the first event right away and then at most once per second,
            // so the preview keeps up with each tool round without thrashing
            // `listSessions`.
            NotificationCenter.default.publisher(for: .sessionDidUpdate)
                .throttle(for: .seconds(1), scheduler: RunLoop.main, latest: true)
        ) { _ in
            // [T-ios-state-publish-offmain-crash] force main-thread @State write
            Task { @MainActor in
                refreshSessionList()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .moveInputToSession)) { note in
            guard let targetId = (note.userInfo as? [String: String])?["targetId"] else { return }
            // Skip navigation if the target session is already visible
            if isWideLayout {
                guard selectedSessionId != targetId && newSessionRealId != targetId else { return }
                // Wide layout replaces selection — no stack to worry about
                openSession(targetId)
            } else {
                guard currentStackSessionId != targetId else { return }
                // Pop current session off the navigation stack first, then push the target
                navigationPath.removeLast(navigationPath.count)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    openSession(targetId)
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .openSessionFromIntent)) { note in
            guard let sessionId = (note.userInfo as? [String: String])?["sessionId"] else { return }
            // [T-notification-tap-vs-launch-session] Warm path owns this
            // navigation: drop the cold-launch buffer copy and stamp the
            // handling time so an in-flight launch `.task` (the post can land
            // during its `await listSessions()`) doesn't clobber the target
            // session with the Launch Session default afterwards.
            NotificationNavigationStore.shared.markHandled()
            // Skip navigation if the target session is already visible
            if isWideLayout {
                guard selectedSessionId != sessionId && newSessionRealId != sessionId else { return }
                openSession(sessionId)
            } else {
                guard currentStackSessionId != sessionId else { return }
                // Pop entire stack back to root first, then push the target session
                navigationPath.removeLast(navigationPath.count)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    openSession(sessionId)
                }
            }
        }
        .fullScreenCover(isPresented: $showTerminal) {
            NavigationStack {
                ISHTerminalView(showCloseButton: true)
            }
        }
        .sheet(isPresented: $showAlarmList, onDismiss: { fetchAlarmsIfNeeded() }) {
            AlarmListView()
        }
        .sheet(item: $activeToolSheet) { sheet in
            switch sheet {
            case .settings:
                SettingsSheet(showTerminal: $showTerminal)
            case .rootfsManagement:
                NavigationStack {
                    RootfsManagementView()
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("Done") { activeToolSheet = nil }
                            }
                        }
                }
            case .browser:
                BrowserSheetView(pool: browserPool)
            case .browserManagement:
                NavigationStack {
                    BrowserManagementView(pool: browserPool)
                }
            case .syncMigrationDetail:
                NavigationStack {
                    SyncMigrationDetailView()
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("Done") { activeToolSheet = nil }
                            }
                        }
                }
            }
        }
        .sheet(item: $sessionToDelete) { session in
            DeleteConfirmSheet(info: $singleDeleteInfo, isLoading: false) {
                print("[DELETE] onDelete called for session: \(session.id)")
                deleteSession(session)
                sessionToDelete = nil
                singleDeleteInfo = nil
            }
            .onAppear {
                print("[DELETE] Sheet appeared. singleDeleteInfo is \(singleDeleteInfo == nil ? "nil" : "non-nil, sessionCount=\(singleDeleteInfo!.sessionCount)")")
            }
            .presentationDetents([.medium])
        }
        .sheet(item: $sessionToEdit) { session in
            SessionEditSheet(session: session) { newTitle, newCategory in
                // [T-ios-state-publish-offmain-crash] @MainActor so the @State
                // write after the actor-hop await stays on the main thread.
                Task { @MainActor in
                    await ChatStore.shared.updateSessionTitle(session.id, title: newTitle, category: newCategory)
                    refreshSessionList()
                }
                sessionToEdit = nil
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showDeleteConfirm, onDismiss: {
            if deleteInfo == nil {
                // Deletion was performed — reset selection mode after sheet is fully dismissed
                isSelecting = false
                selectedIds.removeAll()
            }
        }) {
            DeleteConfirmSheet(info: $deleteInfo, isLoading: isComputingDelete) {
                deleteSelectedSessions()
                showDeleteConfirm = false
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showExportPreview) {
            ExportPreviewSheet(fileURL: exportFileURL, summary: exportSummary)
        }
        .overlay {
            if isExporting {
                ZStack {
                    Color.black.opacity(0.3).ignoresSafeArea()
                    VStack(spacing: 12) {
                        ProgressView()
                            .controlSize(.large)
                        if let p = exportProgress, p.total > 0 {
                            Text(String(localized: "Exporting… \(p.done) / \(p.total)"))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        } else {
                            Text(String(localized: "Exporting…"))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(24)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
                .transition(.opacity)
                .animation(.easeInOut(duration: 0.2), value: isExporting)
            }
        }
        .task {
            sessions = await ChatStore.shared.listSessions()
            let shareAlreadyHandled = shareCoordinator.bufferVersion > 0
            // A Home Screen Quick Action that fired during launch will
            // open the right session itself via `quickActionRouter.newChatTrigger`.
            // Skip the Launch Session logic so we don't open a second,
            // conflicting session (the "last session" / "new chat"
            // launchScreen branch races the shortcut and the user ends
            // up watching one view replaced by the other).
            // Two signals indicate a quick-action launch is in flight:
            //   1. Router bumped newChatTrigger but ContentView hasn't
            //      consumed it yet (race: .task runs before .onAppear).
            //   2. QuickActionWorkflow is past .idle — router already
            //      called start(), workflow owns the next session to
            //      open. Even if (1) flipped because .onAppear already
            //      ran and consumed the trigger, the workflow is still
            //      mid-flight and the launch session would clobber it.
            let workflowActive: Bool = {
                if case .idle = QuickActionWorkflow.shared.state { return false }
                return true
            }()
            let quickActionPending = quickActionRouter.newChatTrigger != consumedQuickActionTrigger || workflowActive
            shareLog.info("[Share] .task: hasPendingShare=\(shareCoordinator.hasPendingShare) launchScreen=\(launchScreen) sessions=\(sessions.count) bufferVersion=\(shareCoordinator.bufferVersion) shareAlreadyHandled=\(shareAlreadyHandled) quickActionPending=\(quickActionPending) workflowActive=\(workflowActive)")

            // [T-notification-tap-vs-launch-session] A notification tap's
            // explicit target session outranks every launch-screen default.
            // Cold launch: didReceive fired before our .onReceive subscriber
            // existed, so the post was lost — the buffered copy is the only
            // surviving signal. Consume it and navigate. Warm-ish overlap: the
            // post arrived while this .task was awaiting listSessions() and
            // .onReceive already navigated — handledRecently suppresses the
            // launch-screen default so it can't clobber that navigation.
            if let notificationTarget = NotificationNavigationStore.shared.takePending() {
                shareLog.info("[Share] .task: notification tap target=\(notificationTarget.prefix(8)) — overriding launchScreen logic")
                var tx = Transaction()
                tx.disablesAnimations = true
                withTransaction(tx) { openSession(notificationTarget) }
            } else if NotificationNavigationStore.shared.handledRecently {
                shareLog.info("[Share] .task: notification navigation just handled — skipping launchScreen logic")
            } else if quickActionPending {
                shareLog.info("[Share] .task: quick action pending — deferring launchScreen logic to QuickActionRouter")
            } else if shareAlreadyHandled {
                // onChange(hasPendingShare) already processed the share and
                // opened a new session before .task ran. Skip normal launch
                // screen logic so we don't clobber it with a different session.
                shareLog.info("[Share] .task: share already handled by onChange — skipping launchScreen logic")
            } else if shareCoordinator.hasPendingShare {
                // onChange hasn't fired yet (e.g. onOpenURL arrived during await).
                // Process share here and open a new session for it.
                shareLog.info("[Share] .task: processing pending share")
                processPendingShare()
                shareLog.info("[Share] .task: buffer stored, bufferVersion=\(shareCoordinator.bufferVersion) buffer=\(shareCoordinator.pendingShareBuffer != nil)")
                var tx = Transaction()
                tx.disablesAnimations = true
                withTransaction(tx) { openSession(Self.makeNewSessionId()) }
            } else {
                // No share — normal launch screen behavior
                switch launchScreen {
                case 1:
                    if let latest = sessions.first {
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { openSession(latest.id) }
                    }
                case 2:
                    var tx = Transaction()
                    tx.disablesAnimations = true
                    withTransaction(tx) { openSession(Self.makeNewSessionId()) }
                case 3:
                    break
                default:
                    if !sessions.isEmpty,
                       let latest = sessions.first,
                       Date().timeIntervalSince(latest.updatedAt) > 15 * 60 {
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { openSession(Self.makeNewSessionId()) }
                    } else if isWideLayout, let latest = sessions.first {
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { openSession(latest.id) }
                    }
                }
            }
            didInitialLoad = true
            fetchAlarmsIfNeeded()
            await refreshRemoteDeviceSessions()
        }
        .onReceive(NotificationCenter.default.publisher(for: .cloudSyncDidFetchChanges)) { _ in
            // [T-ios-state-publish-offmain-crash] cloud-sync fetch fires off-main;
            // force the @State write onto the main thread.
            Task { @MainActor in
                refreshSessionList()
            }
        }
        // [T-ios-session-list-equatable-jank] Keep the id→session lookup cache
        // in sync with `sessions`. Rebuilding here (on actual list mutation)
        // instead of per body-eval is what removes the per-frame Dictionary.==
        // / ChatSession.== diff from the scroll transaction.
        .onChange(of: sessions) { _ in
            rebuildSessionsByIdCache()
        }
        .onChange(of: selectedSessionId) { newValue in
            // [T-ios-session-switch-attributegraph-race] Hosting-view race
            // mitigation: when the user switches between two sessions while
            // both vms are mid-stream, the outgoing AIChatView's UIHostingView
            // subgraph is being torn down at the same instant the new
            // session's hosting views are mounting. A concurrent mutation on
            // the outgoing vm (any @Published delta, scroll signal, etc.)
            // races with `AG::Subgraph::NodeCache::~NodeCache` on the same
            // AsyncRenderer thread → EXC_BAD_ACCESS (build-48 crash
            // Minis-2026-06-01-134710.ips). Suspend the outgoing vm here,
            // then schedule a resume on a short delay so when the user comes
            // back to that session everything catches up. Run before the
            // redirect/tracking-clear logic so we always pin the right id.
            // [T-ios-session-coldload-listsessions-block] Were we leaving a real
            // (persisted, non-new) session? Only then can its list-row preview
            // have drifted and need a navigation-time refresh. Captured before
            // previousSelectedSessionId is overwritten below.
            let hadOutgoingRealSession: Bool = {
                guard let outgoing = previousSelectedSessionId, outgoing != newValue else { return false }
                return !Self.isNewSessionId(outgoing)
            }()
            if let outgoingId = previousSelectedSessionId, outgoingId != newValue {
                if let outgoingVm = ViewModelCache.shared.get(for: outgoingId) {
                    outgoingVm.setSuspendedForTransition(true)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak outgoingVm] in
                        outgoingVm?.setSuspendedForTransition(false)
                    }
                }
            }
            previousSelectedSessionId = newValue
            if let sid = newValue {
                SessionBadgeStore.shared.remove(.unread, for: sid)
                AIChatViewModel.activeSessionId = sid
            } else {
                AIChatViewModel.activeSessionId = nil
            }
            draftLog.info("🔑DRAFT onChange(selectedSessionId) newValue=\(newValue ?? "nil") realId=\(newSessionRealId ?? "nil") draftId=\(activeDraftId ?? "nil")")
            // If user taps the real session row that was created from a draft,
            // redirect back to the draft ID to preserve the AIChatView instance.
            if let realId = newSessionRealId, newValue == realId,
               let draftId = activeDraftId {
                draftLog.info("🔑DRAFT onChange REDIRECT to draftId=\(draftId)")
                selectedSessionId = draftId
                return
            }
            // Clear new-session tracking when navigating to a different session
            if !Self.isNewSessionId(newValue) {
                draftLog.info("🔑DRAFT onChange CLEAR tracking (non-draft)")
                newSessionRealId = nil
                activeDraftId = nil
            }
            // iPad: detail cleared (newValue == nil) — if a quick-action
            // workflow is ensuring-home, advance it now.
            if newValue == nil, case .ensuringHome = QuickActionWorkflow.shared.state {
                QuickActionWorkflow.shared.markHome()
            }
            // [T-ios-search-focus-sticky] iPad split: selecting a session (or
            // new chat) with an empty search drops the sticky search bar.
            if newValue != nil {
                dismissSearchIfEmptyOnNavigate()
            }
            // [T-ios-session-coldload-listsessions-block] Only refresh the list
            // when LEAVING a real session — that outgoing session's preview may
            // have changed while the user was inside it. Entering never changes
            // existing rows. Even so, this listSessions() is a ~1.4s full scan
            // on a large DB and, on the serialized ChatStore actor, is
            // non-preemptible — if it starts before the incoming session's
            // loadSession() reaches its 2nd actor hop (getMemoryEnabled →
            // loadMessages), that hop waits the whole scan out and the spinner
            // hangs ~1.5s. So DELAY the outgoing-preview refresh well past the
            // incoming load (which completes in ~50-150ms) instead of racing it
            // onto the actor. Content changes are already covered by
            // .sessionDidUpdate / .sessionDidCreate / .cloudSyncDidFetchChanges
            // / pin / delete / edit, so this delayed refresh is belt-and-braces.
            if hadOutgoingRealSession {
                scheduleOutgoingPreviewRefresh()
            }
        }
        .onChange(of: navigationPath) { _ in
            // [T-ios-search-focus-sticky] iPhone stack: pushing into a session
            // (or new chat) with an empty search drops the sticky search bar.
            // Only on push (path non-empty) — popping back must NOT clear an
            // active search the user is returning to.
            if !navigationPath.isEmpty {
                dismissSearchIfEmptyOnNavigate()
            }
            if navigationPath.isEmpty {
                currentStackSessionId = nil
                AIChatViewModel.activeSessionId = nil
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                // If a quick-action workflow asked us to ensure-home,
                // we're there now — let it advance to pendingDispatch.
                if case .ensuringHome = QuickActionWorkflow.shared.state {
                    QuickActionWorkflow.shared.markHome()
                }
            }
            // [T-ios-session-coldload-listsessions-block] Only refresh when
            // popping back to home (path emptied) — that's when a preview that
            // changed inside the session the user just left needs to show. On
            // PUSH (entering a session, the cold-open hot path) this scan only
            // head-of-line-blocked loadSession on the ChatStore actor. Delayed
            // so it never races the incoming load's actor hops.
            if navigationPath.isEmpty {
                scheduleOutgoingPreviewRefresh()
            }
            fetchAlarmsIfNeeded()
        }
        .onChange(of: shareCoordinator.hasPendingShare) { hasPending in
            if hasPending {
                processPendingShare()
                // If currently on home screen, navigate to new session for the share
                if navigationPath.isEmpty {
                    shareLog.info("[Share] onChange: Home screen — opening new session for share")
                    var tx = Transaction()
                    tx.disablesAnimations = true
                    withTransaction(tx) { openSession(Self.makeNewSessionId()) }
                }
            }
        }
        .onChange(of: deepLink.showEnvironmentVariables) { show in
            if show {
                activeToolSheet = .settings
            }
        }
        .onChange(of: deepLink.showPermissions) { show in
            if show {
                activeToolSheet = .settings
            }
        }
        .onChange(of: deepLink.showAlarmList) { show in
            if show {
                showAlarmList = true
                deepLink.showAlarmList = false
            }
        }
        // Open the SettingsSheet whenever a deep link sets a settings
        // target. SettingsSheet itself reads `deepLink.pendingSettingsTarget`
        // in onAppear/onChange to push the right destination, then clears it.
        .onChange(of: deepLink.pendingSettingsTarget) { target in
            guard target != nil else { return }
            if activeToolSheet != .settings {
                activeToolSheet = .settings
            }
        }
        .onChange(of: deepLink.pendingRootfsManagement) { pending in
            if pending {
                activeToolSheet = .rootfsManagement
                deepLink.pendingRootfsManagement = false
            }
        }
        .onChange(of: deepLink.pendingSessionId) { sid in
            guard let sid, !sid.isEmpty else { return }
            // Switch to the requested session if it exists in the loaded
            // list. If not loaded yet (cold launch with deep link), set
            // it now — the session-load path will pick it up once the
            // list refresh completes.
            selectedSessionId = sid
            deepLink.pendingSessionId = nil
        }
        .onChange(of: scenePhase) { phase in
            // [T-ios-scenephase-active-sigkill] Defer ALL .active work off the
            // synchronous callback. Writing @Published (SyncCore.isAppInBackground)
            // here triggers objectWillChange → SwiftUI view invalidation in the
            // same runloop tick as the foreground view-graph re-evaluation → SIGTRAP.
            if phase == .active {
                Task { @MainActor in
                    await Task.yield()
                    // Guard against rapid bg→fg→bg: if scenePhase already
                    // changed back, skip the stale .active work.
                    guard scenePhase == .active else { return }
                    fetchAlarmsIfNeeded()
                    if #available(iOS 17.0, *) {
                        SyncCore.shared.isAppInBackground = false
                    }
                    #if DEBUG
                    UIApplication.shared.isIdleTimerDisabled = keepScreenAwake
                    #endif
                    // [T-home-fab-keyboard-inset] Defense-in-depth behind the
                    // structural .ignoresSafeArea immunity on the session lists:
                    // on foreground return with the HOME screen actually showing
                    // (no pushed chat on compact, no selected session on split —
                    // a split chat column may legitimately hold composer focus)
                    // and the inline search bar not focused, no responder is
                    // legitimate. Resign whatever UIKit resurrected during the
                    // background snapshot pass so its stale keyboard inset can't
                    // inflate the window's bottom safe area.
                    if !searchFocused, navigationPath.isEmpty, selectedSessionId == nil {
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.resignFirstResponder),
                            to: nil, from: nil, for: nil)
                    }
                }
            } else {
                if #available(iOS 17.0, *) {
                    SyncCore.shared.isAppInBackground = (phase != .active)
                }
            }
        }
    }

    // MARK: - Split Layout (iPad / wide window)

    private var splitLayout: some View {
        // [T-ios-gh29-font-scale-split-column] App-base font scale for the iPad
        // split view. `appFontScale()` is environment-based (`.dynamicTypeSize`),
        // and SwiftUI environment does NOT bridge across NavigationSplitView's
        // per-column UIHostingController boundaries — applying it on the
        // container (the previous GH#29 fix) left the column contents unscaled,
        // which is why the font slider still didn't take effect on iPad/Mac.
        // Apply it INSIDE each column instead so the modifier lands on the
        // hosting boundary's inner side and reaches the content. The earlier
        // session-switch / tap lag is a separate issue (ChatSession Array `==`
        // in SwiftUI's transaction flush; an A/B test confirmed the font
        // injection is not its cause), so per-column injection is safe here.
        NavigationSplitView(columnVisibility: $columnVisibility) {
            sessionList(useNavigationLinks: false)
                .appFontScale()
        } detail: {
            detailView
                .appFontScale()
        }
    }

    // MARK: - Stack Layout (iPhone / narrow window)

    private var stackLayout: some View {
        NavigationStack(path: $navigationPath) {
            sessionList(useNavigationLinks: true)
                .navigationDestination(for: String.self) { id in
                    // `.id(id)` mirrors detailView (iPad): navigationDestination
                    // views are identified by stack depth, not path value, so
                    // replacing the top element in place (menu "New Chat" swaps
                    // [current] → [draft]) would otherwise reuse the old view's
                    // @StateObject vm and nothing visibly changes.
                    if id.hasPrefix("remote:") {
                        let parts = id.split(separator: ":", maxSplits: 2)
                        if parts.count == 3 {
                            AIChatView(sessionId: String(parts[2]), remoteDeviceId: String(parts[1]))
                                .id(id)
                        }
                    } else {
                        AIChatView(sessionId: Self.isNewSessionId(id) ? nil : id, draftId: Self.isNewSessionId(id) ? id : nil, initialGroupId: Self.extractGroupId(from: id))
                            .id(id)
                            .onAppear {
                                if currentStackSessionId != id { currentStackSessionId = id }
                                SessionBadgeStore.shared.remove(.unread, for: id)
                                shareLog.info("🔄SESSION stackNav APPEAR id=\(id)")
                            }
                            .onDisappear { shareLog.info("🔄SESSION stackNav DISAPPEAR id=\(id)") }
                    }
                }
        }
    }

    // MARK: - Detail View

    @ViewBuilder
    private var detailView: some View {
        if let id = selectedSessionId {
            // Draft sessions always pass nil — the VM creates a real session internally
            // on first send. The .id() ensures each draft gets its own View lifecycle.
            let isDraft = Self.isNewSessionId(id)
            let effectiveId: String? = isDraft ? nil : id
            AIChatView(sessionId: effectiveId, draftId: isDraft ? id : nil, initialGroupId: Self.extractGroupId(from: id))
                .id(id)
                .onAppear {
                    draftLog.info("🔑DRAFT detailView APPEAR id=\(id) effectiveId=\(effectiveId ?? "nil") isDraft=\(isDraft)")
                }
                .onDisappear {
                    draftLog.info("🔑DRAFT detailView DISAPPEAR id=\(id)")
                }
        } else {
            VStack(spacing: 12) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 48))
                    .foregroundStyle(.secondary)
                Text("No Conversation Selected")
                    .font(.title3.bold())
                Text("Select a conversation or start a new one")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    // MARK: - Display Sessions

    /// Sessions filtered by active search query, or all sessions if not searching.
    private var filteredSessions: [ChatSession] {
        guard let matchedIds = searchMatchedIds else { return sessions }
        return sessions.filter { matchedIds.contains($0.id) }
    }

    /// Group sessions by date period for section display, with pinned sessions at the top.
    private func groupedSessions(_ list: [ChatSession]) -> [(label: String, sessions: [ChatSession])] {
        let calendar = Calendar.current
        let now = Date()
        var buckets: [(label: String, sessions: [ChatSession])] = []
        var pinned: [ChatSession] = []
        var today: [ChatSession] = []
        var yesterday: [ChatSession] = []
        var thisWeek: [ChatSession] = []
        var thisMonth: [ChatSession] = []
        var earlier: [ChatSession] = []

        let weekAgo = calendar.date(byAdding: .day, value: -7, to: now) ?? now
        let monthAgo = calendar.date(byAdding: .month, value: -1, to: now) ?? now

        for session in list {
            if session.isPinned {
                pinned.append(session)
                continue
            }
            let d = session.updatedAt
            if calendar.isDateInToday(d) {
                today.append(session)
            } else if calendar.isDateInYesterday(d) {
                yesterday.append(session)
            } else if d > weekAgo {
                thisWeek.append(session)
            } else if d > monthAgo {
                thisMonth.append(session)
            } else {
                earlier.append(session)
            }
        }

        // Sort pinned by last-modified first, then pin time as tiebreaker
        pinned.sort {
            if $0.updatedAt != $1.updatedAt { return $0.updatedAt > $1.updatedAt }
            return ($0.pinnedAt ?? .distantPast) > ($1.pinnedAt ?? .distantPast)
        }

        if !pinned.isEmpty    { buckets.append(("Pinned", pinned)) }
        if !today.isEmpty     { buckets.append(("Today", today)) }
        if !yesterday.isEmpty { buckets.append(("Yesterday", yesterday)) }
        if !thisWeek.isEmpty  { buckets.append(("This Week", thisWeek)) }
        if !thisMonth.isEmpty { buckets.append(("This Month", thisMonth)) }
        if !earlier.isEmpty   { buckets.append(("Earlier", earlier)) }
        return buckets
    }

    // MARK: - Sidebar diff cost (T-ios-session-list-equatable-jank)
    //
    // The sidebar `ForEach(groups)` fed SwiftUI the full `(label, [ChatSession])`
    // tuples. SwiftUI deep-compares the ForEach data on every transaction flush,
    // so it ran `Array<ChatSession>.==` over the whole list — O(session count) ×
    // a per-element ChatSession compare. On a device with many sessions that
    // pinned the main thread for ~1s (Debug only; Release optimizes the compare
    // away, and a small-library iPad never hit the threshold). Cheapening
    // ChatSession.== helped but didn't remove the O(N) array compare.
    //
    // These two helpers let the ForEach diff a `[String]` id list instead of a
    // `[ChatSession]` value array: the outer ForEach carries only labels + ids,
    // and rows look the session up by id. Grouping/sorting logic is unchanged —
    // groupedSessionIDs() just projects groupedSessions() down to ids.

    /// Grouped sidebar sections carrying only session IDs (cheap to diff).
    private func groupedSessionIDs(_ list: [ChatSession]) -> [(label: String, ids: [String])] {
        groupedSessions(list).map { (label: $0.label, ids: $0.sessions.map(\.id)) }
    }


    /// [T-ios-session-list-equatable-jank] Rebuild the id→ChatSession cache.
    /// Called from `.onChange(of: sessions)` — NOT per body eval — so the
    /// dictionary value is stable between session-list mutations and the row
    /// closures never trigger a per-frame Dictionary.== / ChatSession.== diff.
    private func rebuildSessionsByIdCache() {
        sessionsByIdCache = Dictionary(sessions.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    }

    /// Resolve a session for a sidebar row by id, reading the @State cache.
    /// `displaySessions` may prepend a transient draft proxy (id ==
    /// selectedSessionId) that isn't in `sessions`; overlay that one case here
    /// so the iPad split list still resolves the New-Chat placeholder row.
    private func sessionForRow(_ id: String) -> ChatSession? {
        if let cached = sessionsByIdCache[id] { return cached }
        // Draft proxy / placeholder rows live only in displaySessions, never in
        // `sessions` — fall back to a linear scan of that (tiny: base + 1).
        return displaySessions.first(where: { $0.id == id })
    }

    /// Sessions to display in the sidebar. Prepends a placeholder entry
    /// in iPad split mode when the user has opened a new (unsaved) session.
    /// The placeholder keeps the draft ID as its tag so `List(selection:)` stays matched.
    private var displaySessions: [ChatSession] {
        let base = filteredSessions
        guard isWideLayout, let selId = selectedSessionId, Self.isNewSessionId(selId) else {
            return base
        }
        if let realId = newSessionRealId {
            // Session has been persisted — show it under the draft tag so the selection stays valid.
            // Use the real session's data (title, lastMessage) but with the draft ID.
            if let realSession = sessions.first(where: { $0.id == realId }) {
                let proxy = ChatSession(
                    id: selId,
                    title: realSession.title,
                    category: realSession.category,
                    modelId: realSession.modelId,
                    createdAt: realSession.createdAt,
                    updatedAt: realSession.updatedAt,
                    lastMessage: realSession.lastMessage
                )
                // Replace the real session with the proxy so there's no duplicate
                return [proxy] + base.filter { $0.id != realId }
            }
        }
        // Not yet persisted — show a "New Chat" placeholder
        let placeholder = ChatSession(
            id: selId,
            title: "New Chat",
            category: nil,
            modelId: "",
            createdAt: Date(),
            updatedAt: Date(),
            lastMessage: nil
        )
        return [placeholder] + base
    }

    // MARK: - Session List

    @ViewBuilder
    private func sessionList(useNavigationLinks: Bool) -> some View {
        Group {
            if useNavigationLinks {
                stackList
            } else {
                splitList
            }
        }
        // Hardware ⌘F → focus search, available while the session list is on
        // screen (iPad/Mac keyboards). A zero-opacity button carries the
        // shortcut without affecting layout; it lives in the list's view tree so
        // the command only fires when the list is visible, not inside a chat.
        .background {
            Button(action: focusSearch) { EmptyView() }
                .keyboardShortcut("f", modifiers: .command)
                .opacity(0)
                .accessibilityHidden(true)
        }
        // [T-ios-migration-timer-toolbar-uaf-crash / T-ios-migration-timer-sessionlist-uaf-crash]
        // Own the migration-subtitle refresh driver here, on the stable sidebar list
        // body, instead of on the churny toolbar principal item (see `titleLabel`).
        // This Group has identity-stable lifetime across the sidebar's life — the
        // inner if/else only swaps which List renders, the wrapper itself is never
        // torn down — so the driver isn't rebuilt when the list content changes.
        //
        // The driver is a `.task` async loop, NOT a `Timer.publish().autoconnect()`
        // subscribed via `.onReceive`. The earlier `.onReceive(migrationProgressTimer)`
        // form still crashed (build 1.10(1)): a process-lived publisher's sink is a
        // SwiftUI attribute that AttributeGraph re-creates on every `body` transaction,
        // and a 5s tick delivered while that attribute is being torn down/rebuilt
        // releases the dangling sink closure → use-after-free (KERN_PROTECTION_FAILURE
        // in _AppearanceActionModifier.MergedCallbacks.updateValue → swift_release_dealloc).
        // The `.task` loop has no graph-bound publisher: SwiftUI owns the Task's
        // lifetime by view identity and cancels it deterministically on teardown, so
        // there is no sink to release mid-transaction. The loop also parks while the
        // app is backgrounded (the crash reproduced with the app in the background).
        // refreshMigrationSubtitle is idempotent (Task{@MainActor} + diff-before-assign).
        .task { await migrationSubtitleLoop() }
        // [T-ios-soul-name-sidebar-stale] Refresh the sidebar title from SOUL.md.
        // Moved here off the churny toolbar `titleLabel` Text (which rebuilds on
        // every canOpenSync/soulName/migrationSubtitle/isSelecting change) for the
        // same reason as the migration timer above: this Group's identity is stable
        // across the sidebar's life, so the sink is never torn down mid-transaction
        // and can't drop a .soulMdChanged notification arriving during reconstruction.
        .onReceive(NotificationCenter.default.publisher(for: .soulMdChanged)) { _ in
            let n = SoulStore.cachedMetadata.name
            soulName = n.isEmpty ? "Minis" : n
        }
    }

    /// Plain List with NavigationLink for stack (iPhone) layout.
    private var stackList: some View {
        List {
            // [T-ios-session-list-equatable-jank] id-list projection — see splitList.
            let groups = groupedSessionIDs(filteredSessions)
            ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
                Section {
                    ForEach(group.ids, id: \.self) { sessionId in
                        // [T-ios-session-list-equatable-jank] Resolve via the
                        // @State cache (sessionForRow), NOT a captured
                        // [String:ChatSession] — see sessionsByIdCache.
                        if let session = sessionForRow(sessionId) {
                        if isSelecting {
                            selectableRow(session)
                                .id("select-\(session.id)")
                                .listRowInsets(EdgeInsets())
                        } else {
                            SessionRow(
                                session: session,
                                // [T-ios-ipad-sidebar-running-indicator-stale]
                                // Pass running/suspended as VALUES so a flip
                                // changes the SessionRow value → body re-evals
                                // in place (same identity, no cell rebuild).
                                isActive: sidebarActivityTracker.isActive(session.id),
                                isSuspended: sidebarConcurrencyManager.isSuspended(session.id),
                                highlightQuery: isSearching ? searchText : nil,
                                matchSnippet: isSearching ? searchMatchSnippets[session.id] : nil
                            )
                                // [T-ios-session-list-equatable-jank] Gate
                                // parent-driven re-eval on SessionRow's cheap
                                // custom == (rendered fields only), so a
                                // transaction flush from unrelated ContentView
                                // state churn doesn't deep-compare ChatSession.
                                .equatable()
                                .overlay {
                                    if regeneratingTitleSessionId == session.id {
                                        ZStack {
                                            Color(.systemBackground).opacity(0.7)
                                            ProgressView()
                                        }
                                    }
                                }
                                .background(
                                    NavigationLink(value: session.id) { EmptyView() }
                                        .opacity(0)
                                )
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color(.systemBackground))
                            .contextMenu {
                                // [T-ios-crash-contextmenu-uaf] Value-only menu view,
                                // no closure captures — see SessionContextMenu.
                                SessionContextMenu(
                                    key: MenuKey(sid: session.id, pinned: session.isPinned, title: session.title),
                                    actions: menuActions
                                )
                                .equatable()
                            }
                        }
                        }  // if let session
                    }
                } header: {
                    sectionHeader(index: index, group: group)
                }
            }

        }
        .listStyle(.plain)
        #if DEBUG
        // TEMPORARY scroll-phase markers to bracket the jitter window in the
        // log. Pair with the [ROWH] probe: a [ROWH] line appearing during
        // `decelerating`/`idle` = a self-size correction mid/post-scroll =
        // the visible jump. Remove with the probe. (iOS 18+ only.)
        .modifier(ScrollPhaseProbe())
        #endif
        .opacity(didInitialLoad ? 1 : 0)
        .overlay { if didInitialLoad, filteredSessions.isEmpty, !isSearching { emptyState } }
        .safeAreaInset(edge: .bottom) { if isSelecting { selectionToolbar } else { fabRow } }
        // [T-home-fab-keyboard-inset] Mirror of the voice panel's structural
        // immunity (604a9947 / T-voice-bg-fg-gap): with the inline search bar
        // closed, nothing down here accepts text — any keyboard inset reaching
        // this list is a stale/zombie one (stranded responder, interrupted
        // bg-snapshot dismiss) and must not push the 新建/搜索 FABs up. With
        // the search bar open its TextField legitimately rises with the
        // keyboard, so normal avoidance is restored.
        .ignoresSafeArea(.keyboard, edges: showSearchBar ? [] : .bottom)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { sidebarToolbarContent }
    }

    /// Selection-bound List for split (iPad) layout.
    private var splitList: some View {
        List(selection: $selectedSessionId) {
            // [T-ios-session-list-equatable-jank] Diff a (label, ids) projection
            // so SwiftUI compares a [String] id list, not a [ChatSession] value
            // array. Rows resolve the model via displaySessionsById.
            let groups = groupedSessionIDs(displaySessions)
            ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
                Section {
                    ForEach(group.ids, id: \.self) { sessionId in
                        // [T-ios-session-list-equatable-jank] Resolve via the
                        // @State cache (sessionForRow), NOT a captured
                        // [String:ChatSession] — see sessionsByIdCache.
                        if let session = sessionForRow(sessionId) {
                        if isSelecting {
                            selectableRow(session)
                                .id("select-\(session.id)")
                                .listRowInsets(EdgeInsets())
                        } else {
                            SessionRow(
                                session: session,
                                isHighlighted: isSessionHighlighted(session.id),
                                // [T-ios-ipad-sidebar-running-indicator-stale]
                                // Pass running/suspended as VALUES so a flip
                                // changes the SessionRow value → body re-evals
                                // in place (same identity, no cell rebuild).
                                isActive: sidebarActivityTracker.isActive(session.id),
                                isSuspended: sidebarConcurrencyManager.isSuspended(session.id),
                                highlightQuery: isSearching ? searchText : nil,
                                matchSnippet: isSearching ? searchMatchSnippets[session.id] : nil
                            )
                                // [T-ios-session-list-equatable-jank] Gate
                                // parent-driven re-eval on SessionRow's cheap
                                // custom == (rendered fields only), so a
                                // transaction flush from unrelated ContentView
                                // state churn doesn't deep-compare ChatSession.
                                .equatable()
                                .overlay {
                                    if regeneratingTitleSessionId == session.id {
                                        ZStack {
                                            Color(.systemBackground).opacity(0.7)
                                            ProgressView()
                                        }
                                    }
                                }
                                // [T-ios-selected-session-contextmenu] Attach the
                                // contextMenu to the row CONTENT, not to the list-row
                                // modifier chain. On iPadOS / macOS this List uses
                                // `List(selection:)` + `.tag()`; the currently-selected
                                // row's selection gesture swallows the long-press (iPad)
                                // / right-click (mac), so a contextMenu placed at the
                                // list-row level never fires for the open session
                                // (GH#30 / TG36272). Binding it to the SessionRow view
                                // itself puts it below the selection layer, so it
                                // triggers on every row regardless of selection state.
                                // iPhone uses `stackList` (no selection:) and is
                                // unaffected.
                                .contextMenu {
                                    // [T-ios-ipad-new-session-contextmenu-broken / GH#30]
                                    // The selected new-chat row keeps the DRAFT id as its
                                    // tag even after the user sends a message and the
                                    // session is persisted (displaySessions swaps in a
                                    // proxy with realSession data under the draft id, so
                                    // List(selection:) stays matched). The old guard
                                    // `if !isNewSessionId(session.id)` then suppressed the
                                    // context menu for that row forever — until the user
                                    // switched away and the id resolved to the real one.
                                    // Fix: a draft row that has already been persisted
                                    // (newSessionRealId != nil) is a real, operable
                                    // session — surface the menu, targeting the REAL id.
                                    // Only a truly empty, never-sent draft (no realId)
                                    // still has no menu.
                                    // [T-ios-crash-contextmenu-uaf] Value-only menu, no closure.
                                    let menuSid = Self.isNewSessionId(session.id)
                                        ? newSessionRealId
                                        : session.id
                                    if let menuSid {
                                        SessionContextMenu(
                                            key: MenuKey(sid: menuSid, pinned: session.isPinned, title: session.title),
                                            actions: menuActions
                                        )
                                        .equatable()
                                    }
                                }
                                .tag(session.id)
                                .listRowInsets(EdgeInsets())
                                .listRowSeparator(.hidden)
                                .listRowBackground(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .fill(isSessionHighlighted(session.id)
                                              ? Color(red: 183/255.0, green: 175/255.0, blue: 150/255.0).opacity(0.3)
                                              : Color.clear)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                )
                        }
                        }  // if let session
                    }
                } header: {
                    sectionHeader(index: index, group: group)
                }
            }

        }
        .listStyle(.plain)
        .navigationSplitViewColumnWidth(min: 340, ideal: 380, max: 500)
        .opacity(didInitialLoad ? 1 : 0)
        .overlay { if didInitialLoad, displaySessions.isEmpty, !isSearching { emptyState } }
        .safeAreaInset(edge: .bottom) { if isSelecting { selectionToolbar } else { fabRow } }
        // [T-home-fab-keyboard-inset] Same structural immunity as the compact
        // list above — see that call site for the full rationale. On iPad the
        // sidebar column never hosts a keyboard unless the inline search bar
        // is open (the chat column's composer avoidance is its own subtree).
        .ignoresSafeArea(.keyboard, edges: showSearchBar ? [] : .bottom)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { sidebarToolbarContent }
    }
    // MARK: - Sidebar Toolbar

    /// Refresh cadence for the sidebar migration subtitle, in seconds.
    private static let migrationSubtitleRefreshInterval: UInt64 = 5

    /// [T-ios-migration-timer-sessionlist-uaf-crash] Self-cancelling refresh loop
    /// for `migrationSubtitle`, driven by `.task` on the identity-stable sidebar
    /// Group. Replaces the process-lived `Timer.publish().autoconnect()` +
    /// `.onReceive` that AttributeGraph could tear down mid-transaction (UAF).
    ///
    /// SwiftUI cancels this Task when the Group's identity ends, so the loop stops
    /// cleanly with no dangling subscription. It parks (no refresh, cheap poll)
    /// while the app is backgrounded — the crash reproduced with the app in the
    /// background, and a hidden sidebar has nothing to display anyway.
    @MainActor
    private func migrationSubtitleLoop() async {
        // Mirror the old `.onAppear { refreshMigrationSubtitle() }`: refresh once
        // immediately so the subtitle is correct as soon as the sidebar appears.
        refreshMigrationSubtitle()
        while !Task.isCancelled {
            do {
                try await Task.sleep(nanoseconds: Self.migrationSubtitleRefreshInterval * 1_000_000_000)
            } catch {
                return  // cancelled during sleep
            }
            // Skip the refresh while backgrounded; keep looping so it resumes on
            // return to foreground without needing a separate scene-phase wake.
            // Read the process-level flag (kept current by the scenePhase observer)
            // rather than the `@Environment(\.scenePhase)` captured in this `.task`
            // closure's `self` snapshot, which would be stale.
            let backgrounded: Bool
            if #available(iOS 17.0, *) {
                backgrounded = SyncCore.shared.isAppInBackground
            } else {
                backgrounded = false
            }
            if !backgrounded {
                refreshMigrationSubtitle()
            }
        }
    }

    private func refreshMigrationSubtitle() {
        guard #available(iOS 17.0, *), SyncV2Bootstrap.isEnabled else {
            if migrationSubtitle != nil { migrationSubtitle = nil }
            return
        }
        Task { @MainActor in
            // Engine reports paused first regardless of dirty queue.
            if SyncCore.shared.pausedUntil != nil {
                if migrationSubtitle != .paused { migrationSubtitle = .paused }
                return
            }
            let counts = await ChatStore.shared.countDirtyRecords()
            let byType = Self.formatV2DirtyByType(counts.byType)
            let engineUp = SyncCore.shared.isRunning
            // Engine down but dirty queue is non-empty → stalled, not
            // syncing. The rotating icon would lie about progress.
            if !engineUp, !byType.isEmpty {
                if migrationSubtitle != .waiting { migrationSubtitle = .waiting }
                return
            }
            if let summary = await MigrationEngine.shared.progressSummary() {
                let pct = summary.pushTotal > 0 ? Int(Double(summary.pushDone) * 100.0 / Double(summary.pushTotal)) : 0
                let next: SyncSubtitleState = engineUp
                    ? .migrating(percent: pct, byType: byType)
                    : .waiting
                if migrationSubtitle != next { migrationSubtitle = next }
                return
            }
            if !byType.isEmpty {
                let next: SyncSubtitleState = engineUp ? .syncing(byType: byType) : .waiting
                if migrationSubtitle != next { migrationSubtitle = next }
            } else {
                // No active sync work — hide the subtitle entirely so the
                // "Minis" title sits at its normal size.
                if migrationSubtitle != nil { migrationSubtitle = nil }
            }
        }
    }

    /// Map v2 dirty-by-type counts → display capsules. Only types with
    /// dirty>0 are returned, sorted by count desc, top 3.
    private static func formatV2DirtyByType(_ byType: [String: Int]) -> [(label: String, count: Int)] {
        let labels: [String: String] = [
            "SessionV2": "Ses",
            "MessageV2": "Msg",
            "SessionFileV2": "Fil",
            "CompactMarkerV2": "Cmp",
            "ProviderConfigV2": "Prv",
            "EnvVarV2": "Env",
            "SyncDeviceV2": "Dev",
            "SkillV2": "Skl",
        ]
        return byType
            .compactMap { (k, v) -> (String, Int)? in
                guard v > 0, let label = labels[k] else { return nil }
                return (label, v)
            }
            .sorted { $0.1 > $1.1 }
            .prefix(3)
            .map { (label: $0.0, count: $0.1) }
    }

    /// Tiny indicator next to the "Minis" title showing the sync state.
    @ViewBuilder
    private func titleSyncIndicator(for state: SyncSubtitleState?) -> some View {
        switch state {
        case .none, .upToDate:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.green)
        case .paused:
            Image(systemName: "pause.circle.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.orange)
        case .migrating, .syncing:
            PulseRotateIcon()
        case .waiting:
            Image(systemName: "clock")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func syncSubtitleView(_ state: SyncSubtitleState) -> some View {
        HStack(spacing: 5) {
            switch state {
            case .paused:
                Image(systemName: "pause.circle.fill")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("Sync paused")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            case .migrating(let pct, let byType):
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("\(pct)%")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.secondary)
                ForEach(byType.indices, id: \.self) { i in
                    syncTypeChip(label: byType[i].label, count: byType[i].count)
                }
            case .syncing(let byType):
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                ForEach(byType.indices, id: \.self) { i in
                    syncTypeChip(label: byType[i].label, count: byType[i].count)
                }
            case .upToDate:
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.green)
                Text("Up to date")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            case .waiting:
                Image(systemName: "clock")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("Waiting")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Color.secondary.opacity(0.12), in: Capsule())
    }

    @ViewBuilder
    private func syncTypeChip(label: String, count: Int) -> some View {
        HStack(spacing: 3) {
            Text(label)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(formatCompact(count))
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    /// Compact integer formatting: 89,401 → "89k", 1,234 → "1.2k", 740 → "740".
    private func formatCompact(_ n: Int) -> String {
        if n < 1000 { return "\(n)" }
        if n < 10_000 {
            let v = Double(n) / 1000.0
            return String(format: "%.1fk", v)
        }
        return "\(n / 1000)k"
    }

    @ToolbarContentBuilder
    private var sidebarToolbarContent: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            if isSelecting {
                Text(selectedIds.isEmpty ? "Select Sessions" : "\(selectedIds.count) Selected")
                    .font(.headline)
            } else {
                let canOpenSync: Bool = {
                    if #available(iOS 17.0, *) { return SyncV2Bootstrap.isEnabled }
                    return false
                }()
                // Title text comes from SOUL.md (falls back to "Minis"). The
                // leading sync indicator floats as an overlay so it doesn't
                // take layout space — title stays perfectly centered in the
                // navigation bar regardless of whether the indicator is visible.
                // When iCloud sync isn't enabled the title is a plain Text;
                // wrapping it in a `.disabled` Button would drain SwiftUI's
                // default disabled-button tint into the label and render the
                // SOUL name grey, which read as a styling bug rather than the
                // intended "no sync detail to open" state.
                // [T-ios-soul-name-sidebar-stale] The `.onReceive(soulMdChanged)`
                // that refreshes `soulName` used to live HERE. It was moved to the
                // stable `sessionList(useNavigationLinks:)` body for the SAME reason
                // the migration timer was (see the T-ios-migration-timer note below):
                // this toolbar principal item rebuilds on every canOpenSync /
                // soulName / migrationSubtitle / isSelecting change, so a sink
                // attached here gets torn down and re-created constantly and can
                // drop a .soulMdChanged notification that arrives during the gap.
                // This Text now only READS `soulName`.
                let titleLabel = Text(soulName)
                    .font(.system(size: 18.5, weight: .semibold))
                    .foregroundStyle(.primary)
                    .overlay(alignment: .leading) {
                        if canOpenSync {
                            Button {
                                activeToolSheet = .syncMigrationDetail
                            } label: {
                                titleSyncIndicator(for: migrationSubtitle)
                                    .contentShape(Rectangle())
                                    .padding(4)
                            }
                            .buttonStyle(.plain)
                            .offset(x: -27)
                        }
                    }
                // [T-ios-migration-timer-toolbar-uaf-crash] The migration-subtitle
                // refresh driver used to live HERE, on this `titleLabel` Text inside
                // the churny toolbar principal item. That item rebuilds whenever
                // canOpenSync / soulName / migrationSubtitle / isSelecting change, so
                // AttributeGraph repeatedly tore down the driver's sink — a
                // use-after-free release in the setBody transaction (EXC_BAD_ACCESS,
                // build 309). The driver now lives on the stable
                // `sessionList(useNavigationLinks:)` body (identity-stable across the
                // sidebar lifetime) as a `.task` async loop, not a Combine timer sink
                // (see migrationSubtitleLoop, T-ios-migration-timer-sessionlist-uaf-crash);
                // this Text only READS the `migrationSubtitle` @State.

                if canOpenSync {
                    Button {
                        activeToolSheet = .syncMigrationDetail
                    } label: {
                        titleLabel
                    }
                    .buttonStyle(.plain)
                } else {
                    titleLabel
                }
            }
        }
        ToolbarItem(placement: .topBarLeading) {
            if isSelecting {
                Button("Cancel") {
                    isSelecting = false
                    selectedIds.removeAll()
                }
            } else {
                Button {
                    activeToolSheet = .settings
                } label: {
                    Image(systemName: "gear")
                }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            if isSelecting {
                Button(selectedIds.count == sessions.count ? "Deselect All" : "Select All") {
                    if selectedIds.count == sessions.count {
                        selectedIds.removeAll()
                    } else {
                        selectedIds = Set(sessions.map(\.id))
                    }
                }
            } else if hasAlarms {
                Button {
                    showAlarmList = true
                } label: {
                    Image(systemName: "alarm")
                        .font(.system(size: 15, weight: .medium))
                }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            if !isSelecting {
                Menu {
                    Button {
                        deepLink.pendingSettingsTarget = .scheduledTasks
                    } label: {
                        Label("Scheduled Tasks", systemImage: "clock.badge.checkmark")
                    }
                    Button {
                        showTerminal = true
                    } label: {
                        Label("Shell Terminal", systemImage: "terminal")
                    }
                    Button {
                        activeToolSheet = .rootfsManagement
                    } label: {
                        Label("Rootfs Management", systemImage: "externaldrive")
                    }
                    Divider()
                    Button {
                        activeToolSheet = .browser
                    } label: {
                        Label("Open Browser", systemImage: "globe")
                    }
                    Button {
                        activeToolSheet = .browserManagement
                    } label: {
                        Label("Browser Settings", systemImage: "globe.badge.chevron.backward")
                    }
                    #if DEBUG
                    Divider()
                    // [debug] Keep Screen Awake — disables auto-lock while the app
                    // is foregrounded. Tap toggles; a checkmark shows the current
                    // state. Memory-only (not persisted). DEBUG builds only.
                    Button {
                        keepScreenAwake.toggle()
                        UIApplication.shared.isIdleTimerDisabled = keepScreenAwake
                    } label: {
                        Label("Keep Screen Awake", systemImage: keepScreenAwake ? "checkmark.circle.fill" : "sun.max")
                    }
                    #endif
                } label: {
                    Image("TerminalCircle")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 24, height: 24)
                }
            }
        }
    }

    // MARK: - Selection Helpers

    /// Whether a session row should be highlighted as selected.
    private func isSessionHighlighted(_ sessionId: String) -> Bool {
        if selectedSessionId == sessionId { return true }
        // Highlight the real session when a draft is selected and it has been persisted
        if let selId = selectedSessionId, Self.isNewSessionId(selId), sessionId == newSessionRealId { return true }
        return false
    }

    private func fetchAlarmsIfNeeded() {
        // AlarmOffloadBridge references AlarmKit types (AlarmMetadata).
        // On OS versions where AlarmKit doesn't exist (e.g. macOS 14),
        // merely referencing the class triggers Swift runtime type resolution
        // that crashes in swift_getTypeByMangledNameInContextImpl.
        // Use a helper that isolates the reference behind @available.
        guard #available(iOS 26.0, *) else { return }
        _fetchAlarmsImpl()
    }

    @available(iOS 26.0, *)
    private func _fetchAlarmsImpl() {
        AlarmOffloadBridge.listAlarms { arr, _ in
            DispatchQueue.main.async {
                hasAlarms = (arr?.count ?? 0) > 0
            }
        }
    }

    // MARK: - Navigation Helper

    /// Handle a "new chat" request (notification or Quick Action trigger).
    ///
    /// Contract: "land on a fresh new chat, never hijack an existing one."
    /// Concretely:
    ///
    ///   1. If any chat is currently open (real OR draft, on iPad detail
    ///      OR iPhone navigation stack), unwind back to the session list
    ///      root WITHOUT animation — animated transitions race the
    ///      subsequent `openSession()` and the new chat sometimes never
    ///      appears (push coalesced into the running pop).
    ///   2. Once the unwind has settled (NavigationStack path empty on
    ///      iPhone, `selectedSessionId == nil` on iPad), open the new
    ///      draft session.
    ///   3. AIChatView's onAppear then consumes any
    ///      `QuickActionRouter.pendingChatAction` (.startVoice / .openCamera)
    ///      so voice / camera fires *inside* the new chat — never inside
    ///      the previous one.
    private func handleNewChatRequest() {
        let newId = Self.makeNewSessionId()
        // Clear any stale flags left over from a prior quick-action
        // request that didn't run to completion (user backgrounded the
        // app, the pop-then-push dance got interrupted, etc).
        pendingNewChatAfterPop = false
        pendingNewChatTargetId = nil
        // If a workflow is mid-flight in ensuringHome, ContentView's
        // observers (`onChange(navigationPath)` / `onChange(selectedSessionId)`)
        // drive the home-then-open sequence. Otherwise this call came
        // from a non-quick-action source (in-app menu "New Chat") and
        // we just open directly.
        let workflowEnsuringHome: Bool = {
            if case .ensuringHome = QuickActionWorkflow.shared.state { return true }
            return false
        }()
        if workflowEnsuringHome {
            popToHomeForQuickAction()
            return
        }
        if isWideLayout {
            openSession(newId)
        } else {
            var tx = Transaction()
            tx.disablesAnimations = true
            withTransaction(tx) {
                navigationPath = NavigationPath([newId])
            }
            currentStackSessionId = newId
        }
    }

    /// Drive the quick-action workflow's `ensuringHome → pendingDispatch
    /// → waitingForChatMount` sequence. Pops whatever session is on top
    /// without animation; observers (see below) call `markHome` once
    /// the unwind is observable, and a separate observer for
    /// `pendingDispatch` opens the new session.
    private func popToHomeForQuickAction() {
        let alreadyHome = isWideLayout ? (selectedSessionId == nil) : navigationPath.isEmpty
        if alreadyHome {
            // Same-runloop advance — no SwiftUI commit needed.
            QuickActionWorkflow.shared.markHome()
            return
        }
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) {
            if isWideLayout {
                selectedSessionId = nil
            } else {
                navigationPath = NavigationPath()
                currentStackSessionId = nil
            }
        }
        // `onChange(navigationPath)` / `onChange(selectedSessionId)`
        // will fire next runloop with the empty state and call
        // `markHome` from there.
    }

    /// React to workflow state advancing past ensuringHome. When the
    /// workflow says it's ready for a session, mint a fresh draft id
    /// and open it; attach to the workflow so AIChatView's modifier
    /// can match.
    fileprivate func openSessionForPendingQuickAction() {
        guard case .pendingDispatch = QuickActionWorkflow.shared.state else { return }
        let newId = Self.makeNewSessionId()
        if isWideLayout {
            openSession(newId)
        } else {
            var tx = Transaction()
            tx.disablesAnimations = true
            withTransaction(tx) {
                navigationPath = NavigationPath([newId])
            }
            currentStackSessionId = newId
        }
        QuickActionWorkflow.shared.attachTargetSession(newId)
    }

    /// Opens a session in the appropriate layout mode.
    private func openSession(_ id: String) {
        draftLog.info("🔑DRAFT openSession id=\(id) isWide=\(isWideLayout) prevSel=\(selectedSessionId ?? "nil") prevReal=\(newSessionRealId ?? "nil") prevDraft=\(activeDraftId ?? "nil")")
        // Dismiss keyboard when navigating to a session
        searchFocused = false
        if isWideLayout {
            // Reset new-session tracking when navigating away
            if Self.isNewSessionId(id) {
                newSessionRealId = nil
                activeDraftId = nil
            } else if let current = selectedSessionId, Self.isNewSessionId(current) {
                newSessionRealId = nil
                activeDraftId = nil
            }
            selectedSessionId = id
            draftLog.info("🔑DRAFT openSession DONE selectedSessionId=\(id)")
        } else {
            navigationPath.append(id)
            currentStackSessionId = id
        }
    }

    // MARK: - Session Context Menu

    /// Cached, language-aware "Lock with <biometry>" menu label.
    ///
    /// [T-ios-contextmenu-localized-mainthread-hang] `String(localized:)` is an
    /// EAGER Foundation catalog lookup (unlike `LocalizedStringKey`, which
    /// SwiftUI resolves lazily at render). The lock label is the only eager
    /// `String(localized:)` on the contextMenu body-build path. With ~2000
    /// sessions, every AttributeGraph recompute re-ran this lookup once per
    /// cell on the main thread, accumulating into multi-hundred-second hangs
    /// (measured 226s / 106s). The string depends only on the active app
    /// language and the device biometry name — both fixed for a given render
    /// generation — so we compute it once and reuse it.
    ///
    /// Invalidation: keyed on `appLanguage` (the UserDefaults source of truth
    /// the language picker writes; switching it also re-keys the root via
    /// `.id(appLanguage)`, so the whole tree re-mounts) plus the biometry name.
    /// When the key changes the value is recomputed under the new
    /// `languageBundle`, so a language switch is never locked to the old string.
    private static let lockLabelLock = NSLock()
    private nonisolated(unsafe) static var cachedLockLabel: String?
    private nonisolated(unsafe) static var cachedLockLabelKey: String?

    fileprivate static func lockWithBiometryLabel() -> String {
        let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? ""
        let biometry = BiometricAuth.biometryDisplayName
        let key = "\(lang)\u{1F}\(biometry)"
        lockLabelLock.lock()
        defer { lockLabelLock.unlock() }
        if cachedLockLabelKey == key, let cached = cachedLockLabel {
            return cached
        }
        let value = String(localized: "Lock with \(biometry)")
        cachedLockLabel = value
        cachedLockLabelKey = key
        return value
    }

    // MARK: - Menu action wiring

    /// [T-ios-crash-contextmenu-uaf] Connect the stable action channel to
    /// ContentView's instance methods. Called once from onAppear; the closure
    /// captures `self` whose @State backing is stable for the view-graph
    /// lifetime, so the handler remains valid even if body re-evaluates.
    private func wireMenuActions() {
        menuActions.handler = { [self] action in
            switch action {
            case .togglePin(let sid):
                Task { @MainActor in
                    await ChatStore.shared.toggleSessionPin(sid)
                    refreshSessionList()
                }
            case .exportJSON(let sid):
                exportSessions(ids: [sid], format: .json)
            case .exportText(let sid):
                exportSessions(ids: [sid], format: .plainText)
            case .editTitle(let sid):
                if let current = sessions.first(where: { $0.id == sid }) {
                    sessionToEdit = current
                }
            case .regenerateTitle(let sid):
                regenerateTitle(sessionId: sid)
            case .lockSession(let sid):
                SessionLockStore.shared.lock(sid)
            case .unlockSession(let sid):
                Task {
                    let reason = String(localized: "Unlock this session to remove \(BiometricAuth.biometryDisplayName) protection")
                    let ok = await BiometricAuth.authenticate(reason: reason)
                    if ok { SessionLockStore.shared.unlockPermanently(sid) }
                }
            case .duplicate(let sid):
                Task { @MainActor in
                    if let dup = await SessionForkManager.shared.duplicateSession(sessionId: sid) {
                        refreshSessionList()
                        selectedSessionId = dup.id
                    }
                }
            case .forceSync(let sid):
                runForceSyncOnSelection(singleSession: sid)
            case .forcePull(let sid):
                runForcePullOnSession(sid)
            case .select(let sid):
                isSelecting = true
                selectedIds = [sid]
                scrollToId = sid
            case .delete(let sid):
                print("[DELETE] Context menu tapped for session: \(sid)")
                let info = Self.computeDeleteInfo(for: [sid], totalSessions: sessions.count)
                print("[DELETE] computeDeleteInfo returned: sessionCount=\(info.sessionCount), fileCount=\(info.totalFileCount), size=\(info.totalSize)")
                singleDeleteInfo = info
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) {
                    print("[DELETE] singleDeleteInfo set, now setting sessionToDelete")
                    if let current = sessions.first(where: { $0.id == sid }) {
                        sessionToDelete = current
                    }
                    print("[DELETE] sessionToDelete set, sheet should appear")
                }
            }
        }
    }

    // MARK: - Remote Device Sessions

    /// [T-ios-session-list-equatable-jank] id→remote ChatSession lookup, keyed
    /// "deviceId:sessionId". Lets the remote-session rows resolve their model by
    /// id so the ForEach below diffs `[String]`, not `[ChatSession]`.
    private var remoteSessionsById: [String: ChatSession] {
        var d: [String: ChatSession] = [:]
        for entry in remoteDeviceSessions {
            for s in entry.sessions { d["\(entry.device.id):\(s.id)"] = s }
        }
        return d
    }

    @ViewBuilder
    private var remoteDeviceSessionSections: some View {
        let _ = syncLog.info("[iCloud] remoteDeviceSessionSections evaluated: \(remoteDeviceSessions.count) devices, total sessions=\(remoteDeviceSessions.reduce(0) { $0 + $1.sessions.count })")
        let byId = remoteSessionsById
        // [T-ios-session-list-equatable-jank] Project to (deviceId, deviceName,
        // sessionIds) so SwiftUI diffs id strings instead of deep-comparing the
        // [ChatSession] value array on every transaction flush — same fix as the
        // local sidebar list. (This remote section was the path still triggering
        // Array<ChatSession>.== in the HangDetector stacks after the local-list
        // fix landed.)
        let deviceSections: [(deviceId: String, name: String, ids: [String])] =
            remoteDeviceSessions.map { e in
                (deviceId: e.device.id, name: e.device.deviceName, ids: e.sessions.map(\.id))
            }
        ForEach(deviceSections, id: \.deviceId) { entry in
            let _ = syncLog.info("[iCloud] rendering section for device=\(entry.deviceId) name=\(entry.name) sessions=\(entry.ids.count)")
            Section {
                ForEach(entry.ids, id: \.self) { sessionId in
                    if let session = byId["\(entry.deviceId):\(sessionId)"] {
                        NavigationLink(value: "remote:\(entry.deviceId):\(session.id)") {
                            RemoteSessionRow(session: session)
                        }
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                        .contextMenu {
                            Button {
                                // [T-ios-state-publish-offmain-crash] @MainActor
                                // so the @State write stays on the main thread.
                                let sid = session.id
                                let deviceId = entry.deviceId
                                Task { @MainActor in
                                    if let forked = await SessionForkManager.shared.forkSession(
                                        remoteSessionId: sid, remoteDeviceId: deviceId
                                    ) {
                                        refreshSessionList()
                                        selectedSessionId = forked.id
                                    }
                                }
                            } label: {
                                Label("Fork Session", systemImage: "arrow.branch")
                            }
                        }
                    }
                }
            } header: {
                Label(entry.name, systemImage: "iphone")
            }
        }
    }

    // [T-ios-state-publish-offmain-crash] @MainActor: writes `remoteDeviceSessions`
    // (@State) after several ChatStore (actor) awaits; without this the
    // continuation could resume off-main and publish state from a background
    // thread (the [ChatSession] AttributeGraph-compare crash path).
    @MainActor
    private func refreshRemoteDeviceSessions() async {
        let allDevices = await ChatStore.shared.listSyncDevices()
        let devices = allDevices.filter { $0.id != DeviceIdentity.deviceId }
        syncLog.info("[RemoteSync] refreshRemoteDeviceSessions: \(allDevices.count) total devices, \(devices.count) remote (self=\(DeviceIdentity.deviceId))")
        var result: [(device: SyncDevice, sessions: [ChatSession])] = []
        for device in devices {
            let remoteSessions = await ChatStore.shared.listRemoteSessions(deviceId: device.id)
            syncLog.info("[RemoteSync] device \(device.id) (\(device.deviceName)): \(remoteSessions.count) sessions")
            if !remoteSessions.isEmpty {
                result.append((device: device, sessions: remoteSessions))
            }
        }
        syncLog.info("[RemoteSync] Final result: \(result.count) devices with sessions")
        remoteDeviceSessions = result
    }

    private let syncLog = AppLogger(category: "RemoteSync")

    // MARK: - Share Extension Handling

    private func processPendingShare() {
        shareLog.info("[Share] processPendingShare called")

        guard let pending = SharedContainerStore.loadPendingShare() else {
            shareLog.warning("[Share] loadPendingShare returned nil — no data from extension")
            shareCoordinator.hasPendingShare = false
            return
        }

        shareLog.info("[Share] Loaded \(pending.items.count) items into buffer — launch flow unchanged")
        for (i, item) in pending.items.enumerated() {
            shareLog.info("[Share]   item[\(i)] kind=\(item.kind.rawValue) value=\(String(item.value.prefix(100)))")
        }

        SharedContainerStore.clearPendingShare()
        shareCoordinator.hasPendingShare = false
        shareCoordinator.storeBuffer(pending)
        // Navigation is NOT touched here. The normal launch screen logic runs independently.
        // AIChatView will consume the buffer when a new chat session is created (cached.isNew=true).
    }

    // MARK: - Empty State

    @StateObject private var providerStore = ProviderConfigStore.shared
    @State private var showAddProvider = false
    @State private var showSelectModels = false

    private var emptyState: some View {
        let hasProviders = !providerStore.instances.isEmpty
        let hasGroups = !providerStore.modelGroups.isEmpty

        return VStack(spacing: 32) {
            Spacer()
            // App icon / hero
            Image(systemName: "sparkles")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
                .padding(.bottom, 4)

            VStack(spacing: 8) {
                Text("Welcome to Minis")
                    .font(.title2.bold())
                Text("Your first On-Device Agent is almost ready.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            // Setup steps
            VStack(spacing: 16) {
                // Step 1 – Add Provider
                setupStep(
                    number: 1,
                    title: "Add a Provider",
                    subtitle: hasProviders ? "Done" : "Configure an API key or sign in with OAuth",
                    isDone: hasProviders
                ) {
                    if !hasProviders {
                        showAddProvider = true
                    }
                }

                // Step 2 – Select Models
                setupStep(
                    number: 2,
                    title: "Select Models",
                    subtitle: hasGroups ? "Done" : (hasProviders ? "Choose the models you want to use" : "Complete step 1 first"),
                    isDone: hasGroups
                ) {
                    if hasProviders && !hasGroups {
                        showSelectModels = true
                    }
                }

                // Step 3 – Start chatting
                setupStep(
                    number: 3,
                    title: "Start a Conversation",
                    subtitle: hasGroups ? "Tap the button below to begin" : "Complete step 2 first",
                    isDone: false
                ) {
                    if hasGroups {
                        openSession(Self.makeNewSessionId())
                    }
                }
            }
            .padding(.horizontal, 8)
            .frame(maxWidth: 400)
            Spacer()
        }
        .frame(maxHeight: .infinity)
        .padding(.horizontal, 32)
        .sheet(isPresented: $showAddProvider) {
            NavigationStack {
                AddProviderView()
            }
        }
        .sheet(isPresented: $showSelectModels) {
            NavigationStack {
                OnboardingModelSelectionView()
            }
        }
    }

    private func setupStep(
        number: Int,
        title: String,
        subtitle: String,
        isDone: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                // Step indicator
                ZStack {
                    Circle()
                        .fill(isDone ? Color.green : Color.accentColor)
                        .frame(width: 32, height: 32)
                    if isDone {
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                    } else {
                        Text("\(number)")
                            .font(.system(size: 15, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(isDone ? .secondary : .primary)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if !isDone {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(UIColor.secondarySystemGroupedBackground))
            )
        }
        .buttonStyle(.plain)
        .disabled(isDone)
    }

    // MARK: - Search Bar

    private func scheduleSearch() {
        searchTask?.cancel()
        let query = searchText.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            searchMatchedIds = nil
            searchMatchSnippets = [:]
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000) // 300ms debounce
            guard !Task.isCancelled else { return }
            let results = await ChatStore.shared.searchSessions(query: query)
            if !Task.isCancelled {
                searchMatchedIds = Set(results.map(\.session.id))
                // Build a sessionId → snippet map for body-only matches
                // (title matches don't need a snippet — the highlighted
                // title already shows the hit). T-search-highlight 8edb74f2.
                var snippets: [String: String] = [:]
                for r in results {
                    if !r.titleMatched, let snip = r.matchSnippet, !snip.isEmpty {
                        snippets[r.session.id] = snip
                    }
                }
                searchMatchSnippets = snippets
            }
        }
    }

    private func dismissSearch() {
        withAnimation(.easeInOut(duration: 0.2)) { showSearchBar = false }
        searchText = ""
        searchMatchedIds = nil
        searchMatchSnippets = [:]
        searchTask?.cancel()
        searchFocused = false
    }

    /// [T-ios-search-focus-sticky] Auto-exit the search bar when the user
    /// navigates into a session (or starts a new chat) WITHOUT having typed an
    /// actual query. Rationale: tapping the search field then tapping a chat
    /// without searching used to leave the field revealed + focused on return,
    /// forcing a manual tap on the ✕. If there IS a non-blank query, we keep the
    /// search state intact so the user returns to their results. Whitespace-only
    /// text counts as empty. No-op when the search bar isn't shown.
    private func dismissSearchIfEmptyOnNavigate() {
        guard showSearchBar else { return }
        guard searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        dismissSearch()
    }

    /// [T-ios-session-coldload-listsessions-block] Refresh the session list to
    /// pick up any preview change in the session the user just left, but only
    /// AFTER the incoming session's loadSession() has claimed and released the
    /// ChatStore actor for its DB reads. Firing listSessions() synchronously on
    /// navigation raced it onto the (non-preemptible, serialized) actor and hung
    /// the incoming spinner ~1.5s. A 0.6s debounce clears the incoming load
    /// (~50-150ms) with margin and coalesces bursts of session switching into a
    /// single trailing scan.
    private func scheduleOutgoingPreviewRefresh() {
        outgoingPreviewRefreshWork?.cancel()
        let work = DispatchWorkItem {
            refreshSessionList()
        }
        outgoingPreviewRefreshWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: work)
    }

    /// [T-ios-listsessions-refresh-coalesce] Single serialized entry point for
    /// refreshing `sessions` from the store. If a refresh is already running,
    /// mark one trailing run pending instead of spawning a concurrent Task —
    /// collapsing bursts (navigation + sync + streaming ticks) into at most one
    /// in-flight + one queued run. The ChatStore cache makes a clean run cheap.
    @MainActor
    private func refreshSessionList() {
        guard !sessionRefreshInFlight else {
            sessionRefreshPending = true
            return
        }
        sessionRefreshInFlight = true
        Task(priority: .utility) { @MainActor in
            sessions = await ChatStore.shared.listSessions()
            sessionRefreshInFlight = false
            if sessionRefreshPending {
                sessionRefreshPending = false
                refreshSessionList()
            }
        }
    }

    /// Reveal the search bar (if hidden) and focus its text field. Bound to the
    /// hardware ⌘F shortcut while the session list is on screen. Focusing inside
    /// the reveal animation is unreliable, so set focus on the next runloop tick
    /// once the field exists in the view tree.
    private func focusSearch() {
        if !showSearchBar {
            withAnimation(.easeInOut(duration: 0.2)) { showSearchBar = true }
        }
        DispatchQueue.main.async { searchFocused = true }
    }

    // MARK: - FAB Row (New Chat + Search)

    @State private var fabDidDrag = false
    @FocusState private var searchFocused: Bool

    @State private var searchDragOffset: CGFloat = 0
    @State private var searchDidDrag = false

    private var fabRow: some View {
        ZStack {
            // New chat FAB (draggable)
            DraggableFAB(
                fabOnLeft: $fabOnLeft,
                dragOffset: $fabDragOffset,
                didDrag: $fabDidDrag
            ) {
                if !fabDidDrag { openSession(Self.makeNewSessionId()) }
            } label: {
                Circle()
                    .fill(Color(UIColor { $0.userInterfaceStyle == .dark
                        ? UIColor(red: 80/255, green: 76/255, blue: 66/255, alpha: 1)
                        : UIColor(red: 183/255, green: 175/255, blue: 150/255, alpha: 1) }))
                    .overlay {
                        Image(systemName: {
                            if #available(iOS 17.0, *) { return "bubble.left.and.text.bubble.right" }
                            return "plus.message.fill"
                        }())
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                    .shadow(color: .black.opacity(0.2), radius: 8, x: 0, y: 4)
                    .contextMenu {
                        let groups = Array(ProviderConfigStore.shared.config.modelGroups.prefix(10))
                        if !groups.isEmpty {
                            Section(String(localized: "New Chat with Group")) {
                                ForEach(groups) { group in
                                    Button {
                                        openSession(Self.makeNewSessionId(groupId: group.id))
                                    } label: {
                                        Label(group.name, systemImage: "square.stack.3d.up")
                                    }
                                }
                            }
                        }
                    }
            }

            // Search FAB or inline search bar (hidden when no sessions)
            if !sessions.isEmpty {
                if showSearchBar {
                    // Inline search bar — fills space between edges, leaving room for New Chat
                    GeometryReader { geo in
                        let fabSize: CGFloat = 56
                        let edgePad: CGFloat = 16
                        let gap: CGFloat = 10
                        let barX: CGFloat = fabOnLeft
                            ? edgePad + fabSize + gap
                            : edgePad
                        let barWidth: CGFloat = geo.size.width - edgePad * 2 - fabSize - gap

                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(.secondary)
                            TextField("Search chats...", text: $searchText)
                                .textFieldStyle(.plain)
                                .autocorrectionDisabled()
                                .focused($searchFocused)
                                .onChange(of: searchText) { _ in scheduleSearch() }
                            Button { dismissSearch() } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.horizontal, 18)
                        .frame(width: barWidth, height: fabSize)
                        .background(Color(UIColor.secondarySystemBackground))
                        .clipShape(Capsule())
                        .shadow(color: .black.opacity(0.1), radius: 6, x: 0, y: 2)
                        .position(x: barX + barWidth / 2, y: fabSize / 2)
                    }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity),
                        removal: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity)
                    ))
                    .onAppear { searchFocused = true }
                } else {
                    // Search FAB (draggable, inverted side)
                    DraggableFAB(
                        fabOnLeft: $fabOnLeft,
                        dragOffset: $searchDragOffset,
                        didDrag: $searchDidDrag,
                        inverted: true
                    ) {
                        if !searchDidDrag {
                            withAnimation(.easeInOut(duration: 0.2)) { showSearchBar = true }
                        }
                    } label: {
                        Circle()
                            .fill(Color(UIColor.secondarySystemBackground))
                            .overlay {
                                Image(systemName: "magnifyingglass")
                                    .font(.system(size: 22, weight: .semibold))
                                    .foregroundStyle(Color(UIColor.label))
                            }
                            .shadow(color: .black.opacity(0.15), radius: 8, x: 0, y: 4)
                    }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity),
                        removal: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity)
                    ))
                }
            }
        }
        .frame(height: 56)
        .padding(.bottom, 20)
    }

    // MARK: - Selectable Row

    private func selectableRow(_ session: ChatSession) -> some View {
        Button {
            if selectedIds.contains(session.id) {
                selectedIds.remove(session.id)
            } else {
                selectedIds.insert(session.id)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: selectedIds.contains(session.id) ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(selectedIds.contains(session.id) ? Color.accentColor : Color(UIColor.tertiaryLabel))
                SessionRow(session: session)
            }
            .padding(.leading, 16)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Section Header

    @ViewBuilder
    private func sectionHeader(index: Int, group: (label: String, ids: [String])) -> some View {
        if isSelecting {
            let groupIds = Set(group.ids)
            let allSelected = groupIds.isSubset(of: selectedIds)
            Button {
                if allSelected {
                    selectedIds.subtract(groupIds)
                } else {
                    selectedIds.formUnion(groupIds)
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: allSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 18))
                        .foregroundStyle(allSelected ? Color.accentColor : Color(UIColor.tertiaryLabel))
                    // group.label is a stable English key (used for logic like
                    // == "Pinned"); localize only at display via LocalizedStringKey.
                    Text(LocalizedStringKey(group.label))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color(UIColor.secondaryLabel))
                }
                .textCase(nil)
            }
            .buttonStyle(.plain)
        } else if index > 0 || group.label == "Pinned" {
            HStack(spacing: 4) {
                if group.label == "Pinned" {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(UIColor.secondaryLabel))
                }
                // group.label stays an English key for logic; LocalizedStringKey
                // resolves the display string per system language.
                Text(LocalizedStringKey(group.label))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color(UIColor.secondaryLabel))
            }
            .textCase(nil)
        }
    }

    // MARK: - Selection Toolbar

    private var selectionToolbar: some View {
        HStack(spacing: 0) {
            Menu {
                Button {
                    exportSessions(ids: selectedIds, format: .json)
                } label: {
                    Label("JSON", systemImage: "doc.text")
                }
                Button {
                    exportSessions(ids: selectedIds, format: .plainText)
                } label: {
                    Label("Plain Text", systemImage: "text.alignleft")
                }
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 20))
                    Text("Export")
                        .font(.caption2)
                }
                .frame(maxWidth: .infinity)
            }
            .disabled(selectedIds.isEmpty)

            // Force Sync is gated to iOS 17+ — CloudKit features (queryable
            // record fields, fetchRecordZoneChanges async APIs) used by the
            // v2 sync engine require iOS 17. iOS 16 users see the action bar
            // without this button rather than seeing it hit a no-op.
            // Same gate as the single-session menu — drop the action bar
            // entry entirely when iCloud sync is off.
            if #available(iOS 17.0, *), iCloudSyncEnabled {
                Button {
                    runForceSyncOnSelection()
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: forceSyncInFlight ? "arrow.triangle.2.circlepath" : "icloud.and.arrow.up")
                            .font(.system(size: 20))
                        Text("Force Sync")
                            .font(.caption2)
                    }
                    .frame(maxWidth: .infinity)
                }
                .disabled(selectedIds.isEmpty || forceSyncInFlight)
            }

            Button(role: .destructive) {
                deleteInfo = nil
                isComputingDelete = true
                showDeleteConfirm = true
                let ids = selectedIds
                let totalSessions = sessions.count
                Task { @MainActor in
                    let info = await Task.detached {
                        Self.computeDeleteInfo(for: ids, totalSessions: totalSessions)
                    }.value
                    deleteInfo = info
                    isComputingDelete = false
                }
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: "trash")
                        .font(.system(size: 20))
                    Text("Delete")
                        .font(.caption2)
                }
                .frame(maxWidth: .infinity)
                .foregroundStyle(selectedIds.isEmpty ? Color.gray : Color.red)
            }
            .disabled(selectedIds.isEmpty)
        }
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    /// Force-sync the given sessions: bumps each session, its messages,
    /// compact markers, and SessionFiles into the v2 dirty queue, and
    /// tells SyncCore to push immediately. Shows a transient
    /// confirmation toast with the total record count and auto-
    /// dismisses selection mode if engaged.
    ///
    /// Pass `singleSession` for the row-level swipe-action / context-
    /// menu path; falls back to `selectedIds` for the multi-select
    /// toolbar.
    private func runForceSyncOnSelection(singleSession: String? = nil) {
        let ids: Set<String> = singleSession.map { Set([$0]) } ?? selectedIds
        guard !ids.isEmpty, !forceSyncInFlight else { return }
        forceSyncInFlight = true
        Task {
            var totalMarked = 0
            for sid in ids {
                let n = await ChatStore.shared.forceSyncSession(sid)
                totalMarked += n
            }
            // Kick the v2 transport so the freshly-marked rows leave
            // local SQLite in this minute rather than waiting for the
            // ambient debounce.
            if #available(iOS 17.0, *) {
                await MainActor.run { SyncCore.shared.scheduleSend(delay: 0.5) }
            }
            await MainActor.run {
                let sessionCount = ids.count
                forceSyncToast = String(localized: "Marked \(sessionCount) sessions (\(totalMarked) records) for sync. iCloud is syncing now.")
                forceSyncInFlight = false
                if singleSession == nil {
                    // Multi-select path also exits selection mode for the user.
                    isSelecting = false
                    selectedIds.removeAll()
                }
            }
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            await MainActor.run {
                if forceSyncToast != nil { forceSyncToast = nil }
            }
        }
    }

    /// Force Pull a single session from iCloud: queries cloud for the
    /// session's MessageV2 / CompactMarkerV2 / SessionV2 records, and
    /// only commits the local swap if cloud returned a non-empty set.
    /// Reuses the toast banner from runForceSyncOnSelection.
    private func runForcePullOnSession(_ sessionId: String) {
        guard !forceSyncInFlight else { return }
        forceSyncInFlight = true
        forceSyncToast = String(localized: "Pulling from iCloud…")
        // [T-ios-sessionrow-destroy-crash] Isolate the whole Task to @MainActor.
        // This is the site 6687cf1a (T-ios-state-publish-offmain-crash) MISSED:
        // a bare `Task {}` started from this non-isolated func inherits a
        // background cooperative thread, so the `sessions =` @State write after
        // the `forcePullSession` actor-hop await lands off-main → it concurrently
        // mutates the [ChatSession] AttributeGraph state while the main thread is
        // mid-transaction destroying the old SessionRow value graph (the crash:
        // BodyAccessor.setBody → assignWithCopy for Button → release →
        // `outlined destroy of SessionRow` → freed ChatSession backing). Forcing
        // the Task onto MainActor makes the @State write main-thread, matching
        // every other session-write site fixed in 6687cf1a.
        Task { @MainActor in
            let outcome = await ChatStore.shared.forcePullSession(sessionId: sessionId)
            switch outcome {
            case .applied(let pulled, let deleted):
                forceSyncToast = String(localized: "Pulled \(pulled) records · removed \(deleted) local")
            case .cloudEmpty:
                forceSyncToast = String(localized: "iCloud has no records for this chat — local messages preserved")
            case .failed(let msg):
                forceSyncToast = String(localized: "Force Pull failed: \(msg) — local messages preserved")
            }
            forceSyncInFlight = false
            // Refresh sessions so any title/updatedAt that came down from
            // the SessionV2 record reflects in the list.
            refreshSessionList()
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if forceSyncToast != nil { forceSyncToast = nil }
        }
    }

    // MARK: - Delete Info

    struct DeleteInfo {
        let sessionCount: Int
        let fileNames: [String]   // first 3 file names
        let totalFileCount: Int
        let totalSize: Int64      // bytes

        var formattedSize: String {
            ByteCountFormatter.string(fromByteCount: totalSize, countStyle: .file)
        }
    }

    private nonisolated static func computeDeleteInfo(for ids: Set<String>, totalSessions: Int) -> DeleteInfo {
        let fm = FileManager.default
        let libBase = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let minisBase = libBase.appendingPathComponent("MinisChat/minis", isDirectory: true)
        let dbPath = libBase.appendingPathComponent("MinisChat/minis.db")

        var totalSize: Int64 = 0
        var allFileNames: [String] = []
        var totalFileCount = 0

        // Estimate DB size contribution (rough: divide DB size by total sessions)
        let totalSessions = max(totalSessions, 1)
        if let dbAttrs = try? fm.attributesOfItem(atPath: dbPath.path),
           let dbSize = dbAttrs[.size] as? Int64 {
            totalSize += dbSize * Int64(ids.count) / Int64(totalSessions)
        }

        for id in ids {
            let sessionDir = minisBase.appendingPathComponent(id, isDirectory: true)
            if let enumerator = fm.enumerator(at: sessionDir, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) {
                for case let fileURL as URL in enumerator {
                    let vals = try? fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                    if vals?.isRegularFile == true {
                        totalSize += Int64(vals?.fileSize ?? 0)
                        totalFileCount += 1
                        if allFileNames.count < 3 {
                            allFileNames.append(fileURL.lastPathComponent)
                        }
                    }
                }
            }
        }

        return DeleteInfo(
            sessionCount: ids.count,
            fileNames: allFileNames,
            totalFileCount: totalFileCount,
            totalSize: totalSize
        )
    }

    // MARK: - Actions

    private func deleteSession(_ session: ChatSession) {
        // "Currently displayed" must use the same matching as the sidebar
        // highlight (isSessionHighlighted): a session created from a draft this
        // run keeps selectedSessionId at the DRAFT id while the sidebar row
        // carries the real id (newSessionRealId). Comparing only
        // selectedSessionId == session.id missed that case, so deleting the
        // conversation being viewed left the detail pane showing dead content
        // (user report: iPad/macOS split view). Clearing the selection resets
        // the detail pane to the empty state; onChange(selectedSessionId)
        // clears the draft tracking ids.
        if isSessionHighlighted(session.id) {
            selectedSessionId = nil
        }
        ViewModelCache.shared.remove(sessionId: session.id)
        BrowserUseOffloadBridge.releasePool(forSession: session.id)
        withAnimation {
            sessions.removeAll { $0.id == session.id }
        }
        Task {
            await ChatStore.shared.deleteSession(session.id)
            deleteSessionFiles(session.id)
        }
    }

    private func deleteSelectedSessions() {
        let ids = selectedIds
        // Same draft-id-aware matching as deleteSession: clear the selection
        // when ANY deleted id is the one currently displayed (including a
        // draft-created session whose selection id differs from its row id).
        if ids.contains(where: { isSessionHighlighted($0) }) {
            selectedSessionId = nil
        }
        for id in ids {
            ViewModelCache.shared.remove(sessionId: id)
            BrowserUseOffloadBridge.releasePool(forSession: id)
        }
        withAnimation {
            sessions.removeAll { ids.contains($0.id) }
        }
        // Clear deleteInfo to signal onDismiss that deletion occurred;
        // isSelecting and selectedIds are reset in sheet's onDismiss to avoid animation conflicts.
        deleteInfo = nil
        Task {
            for id in ids {
                await ChatStore.shared.deleteSession(id)
                deleteSessionFiles(id)
            }
        }
    }

    /// Remove persistent minis files for a session (Library/MinisChat/minis/<sessionId>/).
    private func deleteSessionFiles(_ sessionId: String) {
        let fm = FileManager.default
        let base = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
            .appendingPathComponent("MinisChat/minis", isDirectory: true)
            .appendingPathComponent(sessionId, isDirectory: true)
        try? fm.removeItem(at: base)
        BrowserTabPool.deletePersistedData(for: sessionId)
    }

    private func regenerateTitle(for session: ChatSession) {
        regenerateTitle(sessionId: session.id)
    }

    private func regenerateTitle(sessionId: String) {
        guard regeneratingTitleSessionId == nil else { return }
        regeneratingTitleSessionId = sessionId

        // [T-ios-state-publish-offmain-crash] @MainActor for the @State writes
        Task { @MainActor in
            defer { regeneratingTitleSessionId = nil }
            do {
                try await AIChatViewModel.regenerateSessionTitle(sessionId: sessionId)
                refreshSessionList()
            } catch {
                AppLogger(category: "RegenerateTitle").error(
                    "session=\(sessionId) failed: \(error.localizedDescription) — \(String(describing: error))"
                )
            }
        }
    }

    enum ExportFormat { case json, plainText }

    private func exportSessions(ids: Set<String>, format: ExportFormat) {
        let targetSessions = sessions.filter { ids.contains($0.id) }
        guard !targetSessions.isEmpty else { return }

        isExporting = true
        exportProgress = (0, 0)

        let isMulti = targetSessions.count > 1

        Task.detached(priority: .userInitiated) {
            let ext: String
            switch format {
            case .json: ext = "json"
            case .plainText: ext = "txt"
            }

            // Per-export workspace under tmp
            let tmpRoot = FileManager.default.temporaryDirectory
                .appendingPathComponent("minis-export-\(UUID().uuidString)", isDirectory: true)
            let workDir = tmpRoot.appendingPathComponent("payload", isDirectory: true)
            do {
                try FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
            } catch {
                await MainActor.run { isExporting = false; exportProgress = nil }
                return
            }

            let baseName: String
            if ids.count == 1, let title = targetSessions.first?.title {
                baseName = title.prefix(60)
                    .replacingOccurrences(of: "/", with: "-")
                    .replacingOccurrences(of: ":", with: "-")
            } else {
                baseName = "minis-sessions-\(ids.count)"
            }
            let payloadURL = workDir.appendingPathComponent("\(baseName).\(ext)")

            // No cheap per-session count available; progress total grows as
            // each session loads (handled inside the stream functions).
            await MainActor.run { exportProgress = (0, 0) }

            let summary: ExportSummary
            do {
                switch format {
                case .json:
                    summary = try await Self.streamExportAsJSON(
                        targetSessions, to: payloadURL,
                        progress: { done, total in
                            Task { @MainActor in exportProgress = (done, total) }
                        }
                    )
                case .plainText:
                    summary = try await Self.streamExportAsPlainText(
                        targetSessions, to: payloadURL,
                        progress: { done, total in
                            Task { @MainActor in exportProgress = (done, total) }
                        }
                    )
                }
            } catch {
                try? FileManager.default.removeItem(at: tmpRoot)
                await MainActor.run { isExporting = false; exportProgress = nil }
                return
            }

            // Zip the workDir (single file inside, but zip wrapping keeps spec contract
            // and shrinks large text payloads significantly).
            let zipURL = tmpRoot.appendingPathComponent("\(baseName).zip")
            let finalURL: URL
            do {
                try await Self.createZip(from: workDir, to: zipURL)
                finalURL = zipURL
            } catch {
                // Fall back to raw payload if zipping failed.
                finalURL = payloadURL
            }

            // Compute summary metadata
            var summaryWithSize = summary
            summaryWithSize.format = ext.uppercased()
            summaryWithSize.sessionCount = targetSessions.count
            if let attrs = try? FileManager.default.attributesOfItem(atPath: finalURL.path),
               let size = attrs[.size] as? Int64 {
                summaryWithSize.fileSizeBytes = size
            }

            // Delay so context menu dismiss animation finishes
            try? await Task.sleep(nanoseconds: 600_000_000)

            await MainActor.run {
                isExporting = false
                exportProgress = nil
                exportFileURL = finalURL
                exportSummary = isMulti ? summaryWithSize : nil
                showExportPreview = true
            }
        }
    }

    /// Wrap a directory into a zip via NSFileCoordinator(.forUploading).
    private static func createZip(from sourceDir: URL, to zipURL: URL) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let coordinator = NSFileCoordinator()
            var nsError: NSError?
            coordinator.coordinate(readingItemAt: sourceDir,
                                   options: .forUploading,
                                   error: &nsError) { zippedURL in
                do {
                    if FileManager.default.fileExists(atPath: zipURL.path) {
                        try FileManager.default.removeItem(at: zipURL)
                    }
                    try FileManager.default.copyItem(at: zippedURL, to: zipURL)
                    cont.resume()
                } catch {
                    cont.resume(throwing: error)
                }
            }
            if let nsError { cont.resume(throwing: nsError) }
        }
    }

    /// Stream JSON export to disk in batches of 50 messages — avoids loading
    /// the full payload into memory. Returns aggregate summary stats.
    private static func streamExportAsJSON(
        _ targetSessions: [ChatSession],
        to fileURL: URL,
        progress: @escaping (Int, Int) -> Void
    ) async throws -> ExportSummary {
        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        guard let handle = FileHandle(forWritingAtPath: fileURL.path) else {
            throw NSError(domain: "ExportStream", code: 1)
        }
        defer { try? handle.close() }

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let isoFmt = ISO8601DateFormatter()

        var summary = ExportSummary()
        var done = 0

        try handle.write(contentsOf: Data("[\n".utf8))
        var firstSession = true

        for session in targetSessions {
            let allMessages = await ChatStore.shared.loadMessages(sessionId: session.id)
            guard let firstUserIdx = allMessages.firstIndex(where: { $0.role == .user }) else { continue }
            let messages = Array(allMessages[firstUserIdx...])

            // Total is best-effort: at minimum we know what we've processed plus
            // this session's pending messages.
            let total = done + messages.count
            progress(done, total)

            // Per-session header
            if !firstSession { try handle.write(contentsOf: Data(",\n".utf8)) }
            firstSession = false

            var sessionHeader: [String: Any] = [
                "id": session.id,
                "modelId": session.modelId,
                "createdAt": isoFmt.string(from: session.createdAt),
                "updatedAt": isoFmt.string(from: session.updatedAt)
            ]
            if let title = session.title { sessionHeader["title"] = title }
            if let category = session.category { sessionHeader["category"] = category }

            // Write the session object opening, then stream messages array.
            let headerData = try JSONSerialization.data(withJSONObject: sessionHeader, options: [.prettyPrinted, .sortedKeys])
            // Strip the trailing "}" so we can append "messages": [...].
            guard var headerStr = String(data: headerData, encoding: .utf8) else { continue }
            if headerStr.hasSuffix("}") { headerStr.removeLast() }
            try handle.write(contentsOf: Data(headerStr.utf8))
            try handle.write(contentsOf: Data(",\n  \"messages\": [\n".utf8))

            var firstMsg = true
            var batchBuffer: [[String: Any]] = []
            batchBuffer.reserveCapacity(50)

            func flushBatch() throws {
                guard !batchBuffer.isEmpty else { return }
                for dict in batchBuffer {
                    let d = try JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys])
                    if !firstMsg { try handle.write(contentsOf: Data(",\n".utf8)) }
                    firstMsg = false
                    try handle.write(contentsOf: d)
                }
                batchBuffer.removeAll(keepingCapacity: true)
            }

            for msg in messages {
                done += 1
                if msg.isToolResultOnly { continue }

                var cleanParts: [[String: Any]] = []
                for part in msg.parts {
                    switch part {
                    case .text(let text):
                        cleanParts.append(["type": "text", "text": text])
                    case .toolUse(let tu):
                        var toolDict: [String: Any] = ["type": "tool_use", "name": tu.name]
                        if let desc = tu.description { toolDict["description"] = desc }
                        if !tu.input.isEmpty { toolDict["input"] = tu.input }
                        cleanParts.append(toolDict)
                    case .toolResult(let tr):
                        var resultDict: [String: Any] = [
                            "type": "tool_result",
                            "tool": tr.toolUseId,
                            "success": tr.success
                        ]
                        if !tr.output.isEmpty {
                            let maxLen = 2000
                            if tr.output.count > maxLen {
                                resultDict["output"] = String(tr.output.prefix(maxLen)) + "\n…[truncated]"
                            } else {
                                resultDict["output"] = tr.output
                            }
                        }
                        if let snap = tr.snapshot {
                            if let text = snap.text { resultDict["snapshot"] = text }
                            if let dur = snap.duration { resultDict["duration"] = dur }
                        }
                        cleanParts.append(resultDict)
                    case .mediaRef:
                        cleanParts.append(["type": "media", "note": "image/file attachment"])
                        summary.attachmentCount += 1
                    }
                }

                guard !cleanParts.isEmpty else { continue }

                var dict: [String: Any] = [
                    "role": msg.role.rawValue,
                    "parts": cleanParts,
                    "createdAt": isoFmt.string(from: msg.createdAt)
                ]
                if let usage = msg.tokenUsage,
                   let usageData = try? encoder.encode(usage),
                   let usageJSON = try? JSONSerialization.jsonObject(with: usageData) {
                    dict["tokenUsage"] = usageJSON
                }
                if let reasoning = msg.reasoningContent, !reasoning.isEmpty {
                    dict["reasoning"] = reasoning
                }
                batchBuffer.append(dict)

                summary.totalMessages += 1
                if summary.earliest == nil || msg.createdAt < summary.earliest! {
                    summary.earliest = msg.createdAt
                }
                if summary.latest == nil || msg.createdAt > summary.latest! {
                    summary.latest = msg.createdAt
                }

                if batchBuffer.count >= 50 {
                    try flushBatch()
                    progress(done, total)
                }
            }
            try flushBatch()

            try handle.write(contentsOf: Data("\n  ]\n}".utf8))
            progress(done, total)
        }

        try handle.write(contentsOf: Data("\n]\n".utf8))
        return summary
    }

    /// Stream plain-text export to disk — writes each message immediately
    /// so memory stays bounded regardless of session size.
    private static func streamExportAsPlainText(
        _ targetSessions: [ChatSession],
        to fileURL: URL,
        progress: @escaping (Int, Int) -> Void
    ) async throws -> ExportSummary {
        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        guard let handle = FileHandle(forWritingAtPath: fileURL.path) else {
            throw NSError(domain: "ExportStream", code: 2)
        }
        defer { try? handle.close() }

        let dateFmt = DateFormatter()
        dateFmt.dateStyle = .medium
        dateFmt.timeStyle = .short
        let timeFmt = DateFormatter()
        timeFmt.dateFormat = "HH:mm"

        var summary = ExportSummary()
        var done = 0

        for (i, session) in targetSessions.enumerated() {
            if i > 0 {
                try handle.write(contentsOf: Data(("\n\n" + String(repeating: "=", count: 60) + "\n\n").utf8))
            }
            var header = "# \(session.title ?? "Untitled")\n"
            header += "Model: \(session.modelId)\n"
            header += "Created: \(dateFmt.string(from: session.createdAt))\n"
            header += String(repeating: "-", count: 40) + "\n"
            try handle.write(contentsOf: Data(header.utf8))

            let allMessages = await ChatStore.shared.loadMessages(sessionId: session.id)
            guard let firstUserIdx = allMessages.firstIndex(where: { $0.role == .user }) else { continue }
            let messages = Array(allMessages[firstUserIdx...])
            let total = done + messages.count
            progress(done, total)

            var batchWritten = 0
            for msg in messages {
                done += 1
                if msg.isToolResultOnly { continue }

                let role = msg.role == .user ? "User" : "Minis"
                let time = timeFmt.string(from: msg.createdAt)
                var parts: [String] = []
                for part in msg.parts {
                    switch part {
                    case .text(let text):
                        if !text.isEmpty { parts.append(text) }
                    case .toolUse(let tu):
                        parts.append("[Tool: \(tu.description ?? tu.name)]")
                    case .toolResult(let tr):
                        if let snap = tr.snapshot, let text = snap.text, !text.isEmpty {
                            let preview = text.count > 200 ? String(text.prefix(200)) + "…" : text
                            parts.append("[Result: \(preview)]")
                        } else {
                            parts.append("[Result: \(tr.success ? "ok" : "failed")]")
                        }
                    case .mediaRef:
                        parts.append("[Attachment]")
                        summary.attachmentCount += 1
                    }
                }
                let body = parts.joined(separator: "\n")
                if !body.isEmpty {
                    let chunk = "\n[\(role)] \(time)\n\(body)\n---\n"
                    try handle.write(contentsOf: Data(chunk.utf8))
                    summary.totalMessages += 1
                    if summary.earliest == nil || msg.createdAt < summary.earliest! {
                        summary.earliest = msg.createdAt
                    }
                    if summary.latest == nil || msg.createdAt > summary.latest! {
                        summary.latest = msg.createdAt
                    }
                }
                batchWritten += 1
                if batchWritten >= 50 {
                    batchWritten = 0
                    progress(done, total)
                }
            }
            progress(done, total)
        }
        return summary
    }

}

// MARK: - Export Summary

struct ExportSummary {
    var format: String = ""
    var sessionCount: Int = 0
    var totalMessages: Int = 0
    var attachmentCount: Int = 0
    var earliest: Date? = nil
    var latest: Date? = nil
    var fileSizeBytes: Int64 = 0
}

// MARK: - Delete Confirm Sheet

private struct DeleteConfirmSheet: View {
    @Binding var info: ContentView.DeleteInfo?
    let isLoading: Bool
    let onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if isLoading || info == nil {
                    Spacer()
                    ProgressView("Calculating…")
                        .controlSize(.large)
                    Spacer()
                    let _ = print("[DELETE] DeleteConfirmSheet showing ProgressView. isLoading=\(isLoading), info is \(info == nil ? "nil" : "non-nil")")
                } else if let info {
                    let _ = print("[DELETE] DeleteConfirmSheet showing info. sessionCount=\(info.sessionCount)")
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            // Sessions
                            infoRow(
                                title: "Sessions",
                                value: "\(info.sessionCount) session\(info.sessionCount == 1 ? "" : "s") and all messages"
                            )

                            // Files
                            if info.totalFileCount > 0 {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Associated Files")
                                        .font(.subheadline.bold())
                                        .foregroundStyle(.primary)
                                    ForEach(info.fileNames, id: \.self) { name in
                                        HStack(spacing: 6) {
                                            Image(systemName: "doc")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                            Text(name)
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    if info.totalFileCount > info.fileNames.count {
                                        Text("and \(info.totalFileCount - info.fileNames.count) more file\(info.totalFileCount - info.fileNames.count == 1 ? "" : "s")")
                                            .font(.caption)
                                            .foregroundStyle(.tertiary)
                                    }
                                }
                            }

                            // Storage
                            infoRow(title: "Releases Storage", value: info.formattedSize)
                        }
                        .padding(20)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Divider()

                    // Buttons
                    VStack(spacing: 10) {
                        Button(role: .destructive) {
                            onDelete()
                            dismiss()
                        } label: {
                            Text("Delete (\(info.formattedSize))")
                                .font(.body.bold())
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.red)

                        Button {
                            dismiss()
                        } label: {
                            Text("Cancel")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                }
            }
            .navigationTitle("Confirm Deletion")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func infoRow(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.subheadline.bold())
                .foregroundStyle(.primary)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Export Preview Sheet

private struct ExportPreviewSheet: View {
    let fileURL: URL?
    let summary: ExportSummary?
    @Environment(\.dismiss) private var dismiss
    @State private var showShareSheet = false
    @State private var showFilePicker = false
    @State private var copied = false
    @State private var previewText: String = ""
    @State private var fileSize: String = ""
    @State private var isLoadingPreview = true

    private let previewLimit = 10000

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Preview — summary for multi-select, full content for single.
                if let summary {
                    summaryView(summary)
                } else if isLoadingPreview {
                    Spacer()
                    ProgressView()
                    Spacer()
                } else {
                    ScrollView {
                        Text(previewText)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color(UIColor.label))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .textSelection(.enabled)
                    }
                    .background(Color(UIColor.secondarySystemBackground))
                }

                Divider()

                // File size info
                if !fileSize.isEmpty {
                    Text(fileSize)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .padding(.top, 6)
                }

                // Action buttons
                HStack(spacing: 0) {
                    // Copy only makes sense for non-zip text payloads (single-session export).
                    if summary == nil, fileURL?.pathExtension.lowercased() != "zip" {
                        actionButton(icon: "doc.on.doc", label: copied ? String(localized: "Copied") : String(localized: "Copy")) {
                            if let url = fileURL, let content = try? String(contentsOf: url, encoding: .utf8) {
                                UIPasteboard.general.string = content
                            }
                            copied = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                        }
                    }
                    actionButton(icon: "square.and.arrow.up", label: String(localized: "Share")) {
                        showShareSheet = true
                    }
                    actionButton(icon: "folder", label: String(localized: "Save to Files")) {
                        showFilePicker = true
                    }
                }
                .padding(.vertical, 12)
                .background(Color(UIColor.systemBackground))
            }
            .navigationTitle(String(localized: "Export Preview"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "Done")) { dismiss() }
                }
            }
            .sheet(isPresented: $showShareSheet) {
                if let url = fileURL {
                    ShareSheet(url: url)
                }
            }
            .sheet(isPresented: $showFilePicker) {
                if let url = fileURL {
                    DocumentExportPicker(url: url)
                }
            }
            .task {
                if summary == nil {
                    await loadPreview()
                } else {
                    // Still compute file size for the action bar.
                    if let url = fileURL,
                       let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                       let size = attrs[.size] as? Int64 {
                        let formatter = ByteCountFormatter()
                        formatter.countStyle = .file
                        fileSize = formatter.string(fromByteCount: size)
                    }
                    isLoadingPreview = false
                }
            }
        }
    }

    @ViewBuilder
    private func summaryView(_ s: ExportSummary) -> some View {
        let dateFmt: DateFormatter = {
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .short
            return f
        }()
        let sizeStr: String = {
            let f = ByteCountFormatter()
            f.countStyle = .file
            return f.string(fromByteCount: s.fileSizeBytes)
        }()
        let timeRange: String = {
            guard let e = s.earliest, let l = s.latest else { return "—" }
            return "\(dateFmt.string(from: e)) ~ \(dateFmt.string(from: l))"
        }()

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                summaryRow(String(localized: "Format"), s.format)
                summaryRow(String(localized: "Sessions"), "\(s.sessionCount)")
                summaryRow(String(localized: "Messages"), "\(s.totalMessages)")
                summaryRow(String(localized: "Time range"), timeRange)
                summaryRow(String(localized: "Attachments"), "\(s.attachmentCount)")
                summaryRow(String(localized: "Estimated size"), sizeStr)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(UIColor.secondarySystemBackground))
    }

    private func summaryRow(_ key: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(key)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.trailing)
        }
    }

    private func loadPreview() async {
        guard let url = fileURL else {
            isLoadingPreview = false
            return
        }

        // Load file size
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = attrs[.size] as? Int64 {
            let formatter = ByteCountFormatter()
            formatter.countStyle = .file
            fileSize = formatter.string(fromByteCount: size)
        }

        // Read only the preview portion to avoid loading huge files into memory
        await Task.detached(priority: .utility) {
            var preview = ""
            if let handle = FileHandle(forReadingAtPath: url.path) {
                // Read at most previewLimit bytes worth of data
                let data = handle.readData(ofLength: previewLimit * 4) // UTF-8 can be up to 4 bytes per char
                handle.closeFile()
                if let text = String(data: data, encoding: .utf8) {
                    if text.count > previewLimit {
                        preview = String(text.prefix(previewLimit)) + "\n\n…"
                    } else {
                        preview = text
                    }
                }
            }
            await MainActor.run {
                previewText = preview
                isLoadingPreview = false
            }
        }.value
    }

    private func actionButton(icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                Text(label)
                    .font(.caption2)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Share Sheet

private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        // [T-share-sheet-uti] Defense against ShareKit's
        // UTTypeGetForIdentifier assert on Mac Catalyst — see
        // MinisShareSheet.sanitizedShareURL for context.
        let safeURL = MinisShareSheet.sanitizedShareURL(url) ?? url
        return UIActivityViewController(activityItems: [safeURL], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Document Export Picker

private struct DocumentExportPicker: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [url])
        picker.shouldShowFileExtensions = true
        return picker
    }
    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}
}

// MARK: - Draggable FAB

/// A floating action button that can be dragged horizontally and snaps to the left or right edge.
/// Uses UIKit's UIPanGestureRecognizer via UIViewRepresentable for reliable, low-latency drag tracking
/// that doesn't conflict with SwiftUI's Button/tap gestures.
private struct DraggableFAB<Label: View>: View {
    @Binding var fabOnLeft: Bool
    @Binding var dragOffset: CGFloat
    @Binding var didDrag: Bool
    /// When true, this FAB sits on the opposite side of `fabOnLeft` and inverts the snap logic.
    var inverted: Bool = false
    var onTap: () -> Void
    @ViewBuilder var label: () -> Label

    private let fabSize: CGFloat = 56
    private let edgePadding: CGFloat = 16

    var body: some View {
        GeometryReader { geo in
            let screenWidth = geo.size.width
            let leftX = edgePadding + fabSize / 2
            let rightX = screenWidth - edgePadding - fabSize / 2
            let onLeft = inverted ? !fabOnLeft : fabOnLeft
            let restingX = onLeft ? leftX : rightX

            label()
                .frame(width: fabSize, height: fabSize)
                .position(x: restingX + dragOffset, y: fabSize / 2)
                .onTapGesture {
                    onTap()
                }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 10)
                        .onChanged { value in
                            didDrag = true
                            dragOffset = value.translation.width
                        }
                        .onEnded { value in
                            let currentCenter = restingX + value.translation.width
                            let droppedOnLeft = currentCenter < screenWidth / 2
                            // For inverted FAB: dropping on left means the *other* FAB goes right
                            let newFabOnLeft = inverted ? !droppedOnLeft : droppedOnLeft
                            let changed = fabOnLeft != newFabOnLeft
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.75)) {
                                fabOnLeft = newFabOnLeft
                                dragOffset = 0
                            }
                            if changed {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            }
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                                didDrag = false
                            }
                        }
                )
        }
        .frame(height: fabSize)
    }
}

// MARK: - Equatable-gated context menu

/// [T-ios-sidebar-contextmenu-memory] + [T-ios-crash-contextmenu-uaf]
///
/// Concrete, closure-free replacement for the old generic EquatableMenuContent.
/// The previous design stored a `@ViewBuilder () -> Content` closure that
/// captured ContentView's `self`; Equatable compared only the key, so SwiftUI
/// retained the OLD instance (and its stale closure) when the key was unchanged.
/// During attribute-graph transactions, `assignWithCopy` of the struct
/// retain/released the closure whose captures had already been deallocated →
/// use-after-free → KERN_PROTECTION_FAILURE (appears as CODESIGNING/Invalid
/// Page because the CPU jumped to a freed stack address).
///
/// This replacement stores ONLY value types (MenuKey) plus a stable class
/// reference (SessionMenuActionChannel, backed by @State in ContentView — alive
/// for the view-graph lifetime). `body` builds the menu from key fields +
/// singleton data; actions go through the channel. No escaping closure, no
/// captured `self`, nothing to dangle.
///
/// Memory optimization preserved: `.equatable()` at the call site still skips
/// `body` when the key is unchanged, so the 8+ Button/Menu/Label tree is NOT
/// rebuilt on every sidebar body pass.
private struct SessionContextMenu: View, Equatable {
    let key: MenuKey
    let actions: SessionMenuActionChannel

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.key == rhs.key }

    var body: some View {
        Button {
            actions.send(.togglePin(key.sid))
        } label: {
            Label(LocalizedStringKey(key.pinned ? "Unpin" : "Pin"),
                  systemImage: key.pinned ? "pin.slash" : "pin")
        }
        Menu {
            Button {
                actions.send(.exportJSON(key.sid))
            } label: {
                Label("JSON", systemImage: "doc.text")
            }
            Button {
                actions.send(.exportText(key.sid))
            } label: {
                Label("Plain Text", systemImage: "text.alignleft")
            }
        } label: {
            Label("Export", systemImage: "square.and.arrow.up")
        }
        Button {
            actions.send(.editTitle(key.sid))
        } label: {
            Label("Edit Title & Category", systemImage: "square.and.pencil")
        }
        Button {
            actions.send(.regenerateTitle(key.sid))
        } label: {
            Label("Regenerate Title", systemImage: "arrow.triangle.2.circlepath")
        }
        if BiometricAuth.isAvailable {
            if SessionLockStore.shared.isLocked(key.sid) {
                Button {
                    actions.send(.unlockSession(key.sid))
                } label: {
                    Label("Remove \(BiometricAuth.biometryDisplayName) Lock", systemImage: "lock.open")
                }
            } else if SessionLockStore.shared.globalEnabled {
                Button {
                    actions.send(.lockSession(key.sid))
                } label: {
                    Label(ContentView.lockWithBiometryLabel(), systemImage: "lock.fill")
                }
            }
        }
        Button {
            actions.send(.duplicate(key.sid))
        } label: {
            Label("Duplicate", systemImage: "doc.on.doc")
        }
        if iCloudSyncVisible {
            Button {
                actions.send(.forceSync(key.sid))
            } label: {
                Label("Force iCloud Sync", systemImage: "icloud.and.arrow.up")
            }
            Button {
                actions.send(.forcePull(key.sid))
            } label: {
                Label("Force Pull Messages", systemImage: "icloud.and.arrow.down")
            }
        }
        Button {
            actions.send(.select(key.sid))
        } label: {
            Label("Select", systemImage: "checkmark.circle")
        }
        Button {
            let title = (key.title ?? "Untitled").prefix(60)
            let subject = "Content Report: \(title)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            let msgBody = "Session: \(key.sid)\n\nPlease describe the issue:\n".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            if let url = URL(string: "mailto:dev@openminis.app?subject=\(subject)&body=\(msgBody)") {
                UIApplication.shared.open(url)
            }
        } label: {
            Label("Report Content", systemImage: "exclamationmark.bubble")
        }
        Button(role: .destructive) {
            actions.send(.delete(key.sid))
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    private var iCloudSyncVisible: Bool {
        if #available(iOS 17.0, *) {
            return UserDefaults.standard.bool(forKey: "cloudSync.v2.enabled")
        }
        return false
    }
}

/// Value key for the sidebar session context menu — only these fields change what
/// the menu renders (Pin/Unpin label, title in Report/Delete). A change here is
/// the only reason to rebuild the menu tree.
private struct MenuKey: Equatable {
    let sid: String
    let pinned: Bool
    let title: String?
}

// MARK: - Session Row

private struct SessionRow: View, Equatable {
    // [T-ios-session-list-equatable-jank] Custom Equatable so `.equatable()` at
    // the call site gates parent-driven re-evaluation on a CHEAP compare of just
    // the fields this row actually renders — instead of SwiftUI deep-comparing
    // the whole `ChatSession` value (the `let session` input). Post the
    // sessionsByIdCache fix, the HangDetector scroll stack still showed
    // AGGraphSetOutputValue → ChatSession.== recursion: it was THIS row's
    // whole-struct input being compared per transaction × visible rows.
    // Comparing only the rendered fields (all short) collapses that to a handful
    // of cheap, short-circuiting comparisons. The @ObservedObject trackers below
    // still drive in-place re-eval on running/lock/font changes (Equatable only
    // gates the parent-push path, not observed-object invalidation).
    static func == (lhs: SessionRow, rhs: SessionRow) -> Bool {
        lhs.session.id == rhs.session.id
            && lhs.session.title == rhs.session.title
            && lhs.session.lastMessage == rhs.session.lastMessage
            && lhs.session.updatedAt == rhs.session.updatedAt
            && lhs.session.pinnedAt == rhs.session.pinnedAt
            && lhs.session.category == rhs.session.category
            && lhs.session.source == rhs.session.source
            && lhs.session.remoteDeviceId == rhs.session.remoteDeviceId
            && lhs.isHighlighted == rhs.isHighlighted
            && lhs.highlightQuery == rhs.highlightQuery
            && lhs.matchSnippet == rhs.matchSnippet
            && lhs.isActiveOverride == rhs.isActiveOverride
            && lhs.isSuspendedOverride == rhs.isSuspendedOverride
    }

    let session: ChatSession
    var isHighlighted: Bool = false
    var highlightQuery: String? = nil
    /// When set, replaces the generic `lastMessage` preview in the subtitle
    /// slot with this body-extracted snippet (~100 chars around the first
    /// hit). Only populated when the search hit was on message body —
    /// title matches keep the highlighted title in the title slot and fall
    /// back to `lastMessage` below. (T-search-highlight 8edb74f2)
    var matchSnippet: String? = nil
    /// Running / suspended state passed in as VALUES by the sidebar list so a
    /// flip changes this view's value and re-evaluates `body` in place (same
    /// ForEach identity — no cell rebuild / scroll jump). When nil (e.g. the
    /// bare `SessionRow(session:)` call site) we fall back to reading the
    /// observed trackers directly. [T-ios-ipad-sidebar-running-indicator-stale]
    var isActiveOverride: Bool? = nil
    var isSuspendedOverride: Bool? = nil
    @ObservedObject private var activityTracker = SessionActivityTracker.shared
    @ObservedObject private var concurrencyManager = SessionConcurrencyManager.shared
    @ObservedObject private var lockStore = SessionLockStore.shared
    // [T-ios-session-paused-badge] Observe the badge-state queue so a session's
    // "!" paused badge appears/clears in place (same row identity) when the
    // queue changes. The custom Equatable above gates only the parent-push
    // path; @ObservedObject still drives in-place re-eval on store changes.
    @ObservedObject private var badgeStore = SessionBadgeStore.shared
    // Observe App Base font scale so the row re-evaluates when the user moves
    // the slider. The row's fonts use hardcoded `.system(size:)` which ignore
    // Dynamic Type, so we must explicitly multiply by the App Base scale.
    @ObservedObject private var fontSettings = FontSettings.shared

    init(session: ChatSession,
         isHighlighted: Bool = false,
         isActive: Bool? = nil,
         isSuspended: Bool? = nil,
         highlightQuery: String? = nil,
         matchSnippet: String? = nil) {
        self.session = session
        self.isHighlighted = isHighlighted
        self.isActiveOverride = isActive
        self.isSuspendedOverride = isSuspended
        self.highlightQuery = highlightQuery
        self.matchSnippet = matchSnippet
    }

    private var isActive: Bool {
        isActiveOverride ?? activityTracker.isActive(session.id)
    }

    private var isSuspended: Bool {
        isSuspendedOverride ?? concurrencyManager.isSuspended(session.id)
    }

    /// True when the row should render the lock affordance — global
    /// toggle on, biometric supported, this session is in the locked set,
    /// AND the user hasn't unlocked it in the current run. Without the
    /// `isCurrentlyUnlocked` check, an already-unlocked session keeps
    /// rendering its title + last-message line blurred even though the
    /// detail view is freely accessible — the row blur outlived its
    /// purpose for the rest of the session.
    private var isVisuallyLocked: Bool {
        lockStore.isVisuallyLocked(session.id)
    }

    /// The transient badge to render for this row, after suppressing states that
    /// contradict the row's live activity.
    ///
    /// [T-ios-session-running-shows-pause-badge] A session that is actively
    /// running its agent loop (`isActive`, drawn with the SpinningRing) is by
    /// definition NOT paused — yet a transient `canResume=true` flip mid-run
    /// (interrupted-tail detection at load, a Stop case that then resumes, a
    /// background-suspend that's already back in-flight) can leave a stale
    /// `.paused` in the queue. The launch/foreground reconcile already excludes
    /// active sessions; do the same continuously at render so a spinning row can
    /// never also show ⏸. Non-`.paused` queue states are unaffected.
    private var visibleBadge: SessionBadgeState? {
        guard let badge = badgeStore.topCornerBadge(for: session.id) else { return nil }
        if badge == .paused && isActive { return nil }
        return badge
    }

    /// [T-ios-session-unread-badge] Whether to draw the top-trailing red unread
    /// dot — a background task finished and posted its notification, and the user
    /// hasn't opened the session yet. Independent of `visibleBadge` (the
    /// bottom-trailing corner badge), so an unread session can also show ⏸.
    private var showsUnreadDot: Bool {
        guard !isHighlighted else { return false }
        return badgeStore.hasUnread(for: session.id)
    }

    var body: some View {
        HStack(spacing: 8) {
            // Provider icon with optional spinning/suspended ring
            providerIcon
                .frame(width: 44, height: 44)
                .background(iconBackgroundColor.opacity(isHighlighted ? 0.35 : 0.18))
                .clipShape(Circle())
                .overlay {
                    if isSuspended {
                        SuspendedRing(color: .yellow)
                            .frame(width: 42, height: 42)
                    } else if isActive {
                        SpinningRing(color: iconBackgroundColor)
                            .frame(width: 42, height: 42)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    // Lock badge takes precedence over source / remote
                    // badges: the locked state is the most important
                    // affordance for the user when scanning the list.
                    if isVisuallyLocked {
                        badgeCircle(icon: "lock.fill", color: .gray)
                            .offset(x: 2, y: 2)
                    } else if let badge = visibleBadge {
                        // [T-ios-session-paused-badge] Transient queue states win
                        // over the source / iCloud badges. Head-of-queue is shown.
                        badgeCircle(for: badge)
                            .offset(x: 2, y: 2)
                    } else if session.source == "shortcut" {
                        badgeCircle(icon: "bolt.fill", color: .orange)
                            .offset(x: 2, y: 2)
                    } else if session.isRemote {
                        badgeCircle(icon: "icloud.fill", color: .blue, iconSize: 7)
                            .offset(x: 2, y: 2)
                    }
                }
                // [T-ios-session-unread-badge] Top-trailing red unread dot,
                // independent of the bottom-trailing corner badge above so an
                // unread session can also show ⏸ / iCloud without conflict.
                .overlay(alignment: .topTrailing) {
                    if showsUnreadDot {
                        Circle()
                            .fill(Color.red)
                            .frame(width: 8, height: 8)
                            .offset(x: -1, y: 1)
                    }
                }

            // Title + subtitle. For locked sessions both lines are blurred
            // so neither the conversation topic nor the last message reads
            // as plain text in the list. The avatar and trailing lock-icon
            // overlay sit outside this VStack so they stay crisp.
            VStack(alignment: .leading, spacing: 4) {
                highlightedText(
                    session.title ?? "New Chat",
                    font: .system(size: fontSettings.scaledApp(16), weight: .semibold),
                    color: Color(UIColor.label)
                )
                .lineLimit(1)

                if let snippet = matchSnippet, !snippet.isEmpty {
                    // During active search, prefer the body-match snippet over
                    // the generic lastMessage preview so the user can see WHY
                    // this session matched. Mirrors Android SessionListScreen
                    // 545d585. The snippet itself is also highlighted so the
                    // keyword stands out inside the ~100-char window.
                    highlightedText(
                        snippet,
                        font: .system(size: fontSettings.scaledApp(14)),
                        color: Color(UIColor.secondaryLabel)
                    )
                    .lineLimit(2)
                } else {
                    highlightedText(
                        session.lastMessage ?? "No messages yet",
                        font: .system(size: fontSettings.scaledApp(14)),
                        color: Color(UIColor.secondaryLabel)
                    )
                    .lineLimit(1)
                }
            }
            // Blur the locked content. Compared to the earlier hard
            // `.clipped()` rectangle, the rounded-rectangle clip + a
            // gentler radius + a soft horizontal fade at the trailing
            // edge let the obfuscated text dissolve into the row
            // background instead of presenting a crisp blurred block.
            //
            // [T-ios16-title-color] The modifier chain (blur+clipShape+mask)
            // is only applied while the session is actually locked. iOS 16's
            // SwiftUI renderer drains color saturation through a stack of
            // even-no-op effect modifiers (mask{Rectangle()} included), which
            // showed up as the session title rendering pale grey on iOS 16
            // while iOS 17+ kept it crisp black. Gating the whole effect
            // chain on the lock flag is the cheapest universal fix.
            .modifier(LockedRowEffect(isLocked: isVisuallyLocked))

            Spacer(minLength: 1)

            VStack(alignment: .trailing, spacing: 4) {
                // Date
                Text(relativeDate(session.updatedAt))
                    .font(.system(size: fontSettings.scaledApp(13)))
                    .foregroundStyle(Color(UIColor.tertiaryLabel))
                if isVisuallyLocked {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(UIColor.tertiaryLabel))
                } else if session.isPinned {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(UIColor.tertiaryLabel))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        #if DEBUG
        // TEMPORARY height probe — confirms List self-sizing jitter source.
        // PreferenceKey fires on every real layout (incl. the self-size
        // correction), unlike onAppear which fires once pre-layout.
        .background(GeometryReader { g in
            Color.clear.preference(key: RowHeightKey.self, value: g.size.height)
        })
        .onPreferenceChange(RowHeightKey.self) { h in
            probeRowHeight(h, matchSnippet == nil ? "plain" : "snippet")
        }
        #endif
    }

    private func badgeCircle(icon: String, color: Color, iconSize: CGFloat = 9, size: CGFloat = 16) -> some View {
        Image(systemName: icon)
            .font(.system(size: iconSize, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color)
            .clipShape(Circle())
    }

    /// [T-ios-session-paused-badge] Maps a queued badge state to its corner
    /// badge. Reuses the same `badgeCircle` layout/size/position as the iCloud
    /// and lock badges so all session badges sit identically on the icon corner.
    @ViewBuilder
    private func badgeCircle(for state: SessionBadgeState) -> some View {
        switch state {
        case .paused:
            // Pause glyph (⏸), orange to distinguish from the blue iCloud badge.
            badgeCircle(icon: "pause.fill", color: .orange, iconSize: 8)
        case .iCloudSyncing:
            badgeCircle(icon: "icloud.fill", color: .blue, iconSize: 7)
        case .unread:
            // [T-ios-session-unread-badge] `.unread` renders as a separate
            // top-trailing red dot (see the .topTrailing overlay), never through
            // this bottom-trailing corner path. `topCornerBadge` filters it out
            // upstream, so this case is unreachable — render nothing defensively.
            EmptyView()
        }
    }

    @ViewBuilder
    private func highlightedText(_ text: String, font: Font, color: Color) -> some View {
        if let query = highlightQuery, !query.isEmpty,
           text.range(of: query, options: .caseInsensitive) != nil {
            Text(Self.highlightedAttributedString(text: text, query: query, baseColor: color))
                .font(font)
        } else {
            Text(text).font(font).foregroundStyle(color)
        }
    }

    /// Build a SwiftUI `AttributedString` with `backgroundColor` painted on
    /// every case-insensitive occurrence of `query`. Matches Android
    /// `highlightedAnnotatedString` (commit 545d585) — same theme-aware
    /// tint, same case-insensitive match, same span-style approach.
    /// (T-search-highlight 8edb74f2)
    private static func highlightedAttributedString(text: String, query: String, baseColor: Color) -> AttributedString {
        PerfTrace.measure("SessionRow.highlightedAttributedString") {
            highlightedAttributedStringImpl(text: text, query: query, baseColor: baseColor)
        }
    }

    private static func highlightedAttributedStringImpl(text: String, query: String, baseColor: Color) -> AttributedString {
        var attr = AttributedString(text)
        attr.foregroundColor = baseColor
        guard !query.isEmpty else { return attr }
        let highlightBg = Color.accentColor.opacity(0.25)
        let totalChars = text.count
        var searchStart = text.startIndex
        while searchStart < text.endIndex,
              let range = text.range(of: query, options: .caseInsensitive, range: searchStart..<text.endIndex) {
            let lo = text.distance(from: text.startIndex, to: range.lowerBound)
            let hi = text.distance(from: text.startIndex, to: range.upperBound)
            // Clamp to attr length; `AttributedString.index(_:offsetByCharacters:)`
            // traps on out-of-range, so we guard up front. AttributedString
            // built from a Swift `String` shares the same character count
            // as `text.count` (grapheme-cluster clusters identical), so a
            // single bounds check is enough.
            guard lo >= 0, hi <= totalChars, lo < hi else {
                searchStart = range.upperBound
                continue
            }
            let attrLo = attr.index(attr.startIndex, offsetByCharacters: lo)
            let attrHi = attr.index(attr.startIndex, offsetByCharacters: hi)
            let r = attrLo..<attrHi
            attr[r].backgroundColor = highlightBg
            attr[r].foregroundColor = Color.accentColor
            attr[r].inlinePresentationIntent = .stronglyEmphasized
            searchStart = range.upperBound
        }
        return attr
    }


    private var categoryIcon: (systemName: String, color: Color) {
        switch session.category {
        case "code":         return ("terminal.fill", .orange)
        case "writing":      return ("doc.text.fill", .blue)
        case "research":     return ("globe.americas.fill", .teal)
        case "analysis":     return ("chart.pie.fill", .indigo)
        case "creative":     return ("paintbrush.pointed.fill", .pink)
        case "chat":         return ("bubble.left.fill", .green)
        case "math":         return ("number.circle.fill", .purple)
        case "translation":  return ("character.bubble", .cyan)
        case "health":       return ("heart.fill", .red)
        case "finance":      return ("banknote.fill", .mint)
        case "travel":       return ("map.fill", .orange)
        case "education":    return ("book.closed.fill", .blue)
        case "design":       return ("paintpalette.fill", .pink)
        case "productivity": return ("calendar.badge.checkmark", .yellow)
        case "support":      return ("gearshape.fill", .brown)
        case "other":        return ("square.grid.2x2.fill", .gray)
        default:             return ("bubble.left.fill", .gray)
        }
    }

    @ViewBuilder
    private var providerIcon: some View {
        let icon = categoryIcon
        let size: CGFloat = (icon.systemName == "bubble.left.fill" || icon.systemName == "terminal.fill") ? 18 : 20
        Image(systemName: icon.systemName)
            .font(.system(size: size))
            .foregroundStyle(icon.color)
    }

    private var iconBackgroundColor: Color {
        categoryIcon.color
    }

    private func relativeDate(_ date: Date) -> String {
        PerfTrace.measure("SessionRow.relativeDate") {
            Self.relativeDateImpl(date)
        }
    }

    // [T-ios-sessionlist-time-i18n] Row timestamps were hardcoded English
    // ("Yesterday", "N min ago", raw "M/d") while the section headers around
    // them localize via LocalizedStringKey — a zh UI showed "昨天" headers
    // next to "Yesterday" row times. All four phrase keys already exist in
    // Localizable.xcstrings (8 locales); the weekday/short-date fall-backs
    // use localized format templates so e.g. zh renders 7月8日 instead of 7/8.
    private static func relativeDateImpl(_ date: Date) -> String {
        let now = Date()
        let calendar = Calendar.current
        let seconds = Int(now.timeIntervalSince(date))
        if calendar.isDateInToday(date) {
            if seconds < 60 {
                return String(localized: "Just now")
            } else if seconds < 3600 {
                let mins = seconds / 60
                return String(localized: "\(mins) min ago")
            } else {
                let hrs = seconds / 3600
                return String(localized: "\(hrs) hr ago")
            }
        } else if calendar.isDateInYesterday(date) {
            return String(localized: "Yesterday")
        } else {
            let diff = calendar.dateComponents([.day], from: date, to: now)
            let formatter = DateFormatter()
            if let days = diff.day, days < 7 {
                formatter.setLocalizedDateFormatFromTemplate("EEEE")
            } else {
                formatter.setLocalizedDateFormatFromTemplate("Md")
            }
            return formatter.string(from: date)
        }
    }
}

/// Gate the blur + clip + gradient-mask trio on the lock flag so iOS 16
/// doesn't pay the always-on `.mask { Rectangle() }` color-drain cost
/// for unlocked rows (T-ios16-title-color). When unlocked, the row
/// renders with no extra effect modifiers at all.
private struct LockedRowEffect: ViewModifier {
    let isLocked: Bool

    func body(content: Content) -> some View {
        if isLocked {
            content
                .blur(radius: 4)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .mask {
                    LinearGradient(
                        stops: [
                            .init(color: .black, location: 0.0),
                            .init(color: .black, location: 0.82),
                            .init(color: .black.opacity(0.0), location: 1.0),
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                }
        } else {
            content
        }
    }
}

/// Row for a session synced from another device, with an iCloud badge on the icon.
private struct RemoteSessionRow: View {
    let session: ChatSession

    var body: some View {
        HStack(spacing: 8) {
            // Category icon with iCloud badge
            providerIcon
                .frame(width: 44, height: 44)
                .background(iconColor.opacity(0.18))
                .clipShape(Circle())
                .overlay(alignment: .bottomTrailing) {
                    Image(systemName: "icloud.fill")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 16, height: 16)
                        .background(Color.blue)
                        .clipShape(Circle())
                        .offset(x: 2, y: 2)
                }

            VStack(alignment: .leading, spacing: 4) {
                Text(session.title ?? "Untitled")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color(UIColor.label))
                    .lineLimit(1)
                Text(session.updatedAt, style: .relative)
                    .font(.system(size: 14))
                    .foregroundStyle(Color(UIColor.secondaryLabel))
                    .lineLimit(1)
            }

            Spacer(minLength: 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }

    private var categoryIcon: (systemName: String, color: Color) {
        switch session.category {
        case "code":         return ("terminal.fill", .orange)
        case "writing":      return ("doc.text.fill", .blue)
        case "research":     return ("globe.americas.fill", .teal)
        case "analysis":     return ("chart.pie.fill", .indigo)
        case "creative":     return ("paintbrush.pointed.fill", .pink)
        case "chat":         return ("bubble.left.fill", .green)
        case "math":         return ("number.circle.fill", .purple)
        case "translation":  return ("character.bubble", .cyan)
        case "health":       return ("heart.fill", .red)
        case "finance":      return ("banknote.fill", .mint)
        case "travel":       return ("map.fill", .orange)
        case "education":    return ("book.closed.fill", .blue)
        case "design":       return ("paintpalette.fill", .pink)
        case "productivity": return ("calendar.badge.checkmark", .yellow)
        case "support":      return ("gearshape.fill", .brown)
        case "other":        return ("square.grid.2x2.fill", .gray)
        default:             return ("bubble.left.fill", .gray)
        }
    }

    private var iconColor: Color { categoryIcon.color }

    @ViewBuilder
    private var providerIcon: some View {
        let icon = categoryIcon
        let size: CGFloat = (icon.systemName == "bubble.left.fill" || icon.systemName == "terminal.fill") ? 18 : 20
        Image(systemName: icon.systemName)
            .font(.system(size: size))
            .foregroundStyle(icon.color)
    }
}

/// A dashed circle ring indicating a session is suspended (waiting for a concurrency slot).
private struct SuspendedRing: View {
    let color: Color

    var body: some View {
        Circle()
            .stroke(color, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
    }
}

// MARK: - Session Edit Sheet

struct SessionEditSheet: View {
    let session: ChatSession
    let onSave: (String, String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var editTitle: String = ""
    @State private var editCategory: String = ""
    @State private var isRegenerating = false

    private static let categories: [(key: String, label: String, icon: String, color: Color)] = [
        ("code",         "Code",         "terminal.fill",               .orange),
        ("writing",      "Writing",      "doc.text.fill",               .blue),
        ("research",     "Research",     "globe.americas.fill",         .teal),
        ("analysis",     "Analysis",     "chart.pie.fill",              .indigo),
        ("creative",     "Creative",     "paintbrush.pointed.fill",     .pink),
        ("chat",         "Chat",         "bubble.left.fill",            .green),
        ("math",         "Math",         "number.circle.fill",          .purple),
        ("translation",  "Translation",  "character.bubble",            .cyan),
        ("health",       "Health",       "heart.fill",                  .red),
        ("finance",      "Finance",      "banknote.fill",               .mint),
        ("travel",       "Travel",       "map.fill",                    .orange),
        ("education",    "Education",    "book.closed.fill",            .blue),
        ("design",       "Design",       "paintpalette.fill",           .pink),
        ("productivity", "Productivity", "calendar.badge.checkmark",    .yellow),
        ("support",      "Support",      "gearshape.fill",              .brown),
        ("other",        "Other",        "square.grid.2x2.fill",       .gray),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section("Title") {
                    TextField("Session title", text: $editTitle)
                }

                Section("Category") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 12)], spacing: 12) {
                        ForEach(Self.categories, id: \.key) { cat in
                            Button {
                                editCategory = cat.key
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: cat.icon)
                                        .font(.system(size: 20))
                                        .foregroundStyle(editCategory == cat.key ? .white : cat.color)
                                        .frame(width: 44, height: 44)
                                        .background(
                                            Circle()
                                                .fill(editCategory == cat.key ? cat.color : cat.color.opacity(0.12))
                                        )
                                    Text(LocalizedStringKey(cat.label))
                                        .font(.caption2)
                                        .foregroundStyle(editCategory == cat.key ? cat.color : .secondary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 8)
                }

                Section {
                    Button {
                        regenerate()
                    } label: {
                        HStack {
                            if isRegenerating {
                                SpinningRing(color: .accentColor)
                                    .frame(width: 18, height: 18)
                            } else {
                                Image(systemName: "arrow.triangle.2.circlepath")
                            }
                            Text("Regenerate Title")
                        }
                        .frame(maxWidth: .infinity, alignment: .center)
                    }
                    .disabled(isRegenerating)
                }
            }
            .navigationTitle("Edit Session")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let title = editTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !title.isEmpty else { return }
                        onSave(title, editCategory.isEmpty ? nil : editCategory)
                    }
                    .bold()
                    .disabled(editTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                editTitle = session.title ?? ""
                editCategory = session.category ?? ""
            }
        }
    }

    private func regenerate() {
        guard !isRegenerating else { return }
        isRegenerating = true
        Task { @MainActor in
            defer { isRegenerating = false }
            do {
                try await AIChatViewModel.regenerateSessionTitle(sessionId: session.id)
                if let updated = await ChatStore.shared.getSession(session.id) {
                    if let title = updated.title { editTitle = title }
                    editCategory = updated.category ?? editCategory
                }
            } catch {
                AppLogger(category: "RegenerateTitle").error(
                    "session=\(session.id) failed: \(error.localizedDescription) — \(String(describing: error))"
                )
            }
        }
    }
}

/// A self-contained spinning arc that uses TimelineView to avoid
/// stacking animations on repeated onAppear calls.
private struct SpinningRing: View {
    let color: Color

    var body: some View {
        TimelineView(.animation) { timeline in
            let angle = timeline.date.timeIntervalSinceReferenceDate.remainder(dividingBy: 1.0) * 360
            Circle()
                .trim(from: 0, to: 0.3)
                .stroke(color, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                .rotationEffect(.degrees(angle))
        }
    }
}

// MARK: - Appearance Settings

private struct AppIconOption: Identifiable {
    let id: Int
    let title: String
    let subtitle: String
    let iconName: String?  // nil = automatic (default asset catalog icon)
    let imageName: String  // bundle image file name for preview
}

private struct LanguageOption: Identifiable {
    let id: String   // language code, "" = system
    let name: String  // native name
    let flag: String
}

private let supportedLanguages: [LanguageOption] = [
    LanguageOption(id: "",       name: "System", flag: ""),
    LanguageOption(id: "en",     name: "English", flag: "🇺🇸"),
    LanguageOption(id: "zh-Hans", name: "简体中文", flag: "🇨🇳"),
    LanguageOption(id: "ja",     name: "日本語", flag: "🇯🇵"),
    LanguageOption(id: "ko",     name: "한국어", flag: "🇰🇷"),
    LanguageOption(id: "fr",     name: "Français", flag: "🇫🇷"),
    LanguageOption(id: "de",     name: "Deutsch", flag: "🇩🇪"),
    LanguageOption(id: "ru",     name: "Русский", flag: "🇷🇺"),
]

private struct FontScaleRow: View {
    let label: String
    @Binding var level: FontScaleLevel

    private static let cases = FontScaleLevel.allCases
    private let stepCount = FontScaleRow.cases.count  // 5

    private var currentIndex: Int {
        Self.cases.firstIndex(of: level) ?? 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // `label` is a fixed English identifier ("Chat Input" etc.);
            // wrap in LocalizedStringKey so SwiftUI looks it up in the
            // String Catalog instead of rendering the verbatim English.
            Text(LocalizedStringKey(label))
            HStack(spacing: 12) {
                Button {
                    let idx = currentIndex - 1
                    if idx >= 0 {
                        level = Self.cases[idx]
                    }
                } label: {
                    Text("A")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)

                SteppedSlider(value: currentIndex, steps: stepCount) { newIndex in
                    if newIndex >= 0, newIndex < Self.cases.count {
                        let newLevel = Self.cases[newIndex]
                        if newLevel != level { level = newLevel }
                    }
                }

                Button {
                    let idx = currentIndex + 1
                    if idx < Self.cases.count {
                        level = Self.cases[idx]
                    }
                } label: {
                    Text("A")
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 2)
    }
}

/// A custom stepped slider that supports both drag and tap-to-snap.
/// Uses local gesture state to avoid layout feedback loops from SwiftUI's Slider.
private struct SteppedSlider: View {
    let value: Int
    let steps: Int
    let onChanged: (Int) -> Void

    private let trackHeight: CGFloat = 4
    private let thumbSize: CGFloat = 22
    private let tickSize: CGFloat = 6

    @State private var dragValue: Int?

    private var displayValue: Int { dragValue ?? value }

    var body: some View {
        GeometryReader { geo in
            let totalW = geo.size.width
            let midY = geo.size.height / 2
            let maxStep = CGFloat(steps - 1)

            ZStack(alignment: .leading) {
                // Track background
                Capsule()
                    .fill(Color(.systemFill))
                    .frame(height: trackHeight)
                    .position(x: totalW / 2, y: midY)

                // Filled portion
                let fillW = maxStep > 0 ? totalW * CGFloat(displayValue) / maxStep : 0
                Capsule()
                    .fill(Color.accentColor)
                    .frame(width: fillW, height: trackHeight)
                    .position(x: fillW / 2, y: midY)

                // Tick marks
                ForEach(0..<steps, id: \.self) { i in
                    let x = maxStep > 0 ? totalW * CGFloat(i) / maxStep : 0
                    Circle()
                        .fill(i <= displayValue ? Color.accentColor : Color(.systemFill))
                        .frame(width: tickSize, height: tickSize)
                        .position(x: x, y: midY)
                }

                // Thumb
                let thumbX = maxStep > 0 ? totalW * CGFloat(displayValue) / maxStep : 0
                Circle()
                    .fill(.white)
                    .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
                    .frame(width: thumbSize, height: thumbSize)
                    .position(x: thumbX, y: midY)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { drag in
                        let step = stepFromX(drag.location.x, width: totalW)
                        if step != dragValue { dragValue = step }
                    }
                    .onEnded { drag in
                        let step = stepFromX(drag.location.x, width: totalW)
                        dragValue = nil
                        onChanged(step)
                    }
            )
        }
        .frame(height: thumbSize + 8)
    }

    private func stepFromX(_ x: CGFloat, width: CGFloat) -> Int {
        guard width > 0, steps > 1 else { return 0 }
        let fraction = x / width
        let clamped = min(max(fraction, 0), 1)
        return Int((clamped * CGFloat(steps - 1)).rounded())
    }
}

private struct AppearanceSettingsView: View {
    @AppStorage("appearanceMode") private var appearanceMode: Int = 0
    @AppStorage("appIconMode") private var appIconMode: Int = 0
    @AppStorage("appLanguage") private var appLanguage: String = ""
    @AppStorage("launchScreen") private var launchScreen: Int = 0  // 0=Auto, 1=Last Session, 2=New Chat, 3=Home
    @AppStorage("toolPreviewEnabled") private var toolPreviewEnabled: Bool = true
    /// 0 = Return inserts a newline (default), 1 = Return sends the message.
    @AppStorage("returnKeyBehavior") private var returnKeyBehavior: Int = 0
    /// When true, holds `UIApplication.isIdleTimerDisabled` while any session
    /// is running a task. See `KeepScreenAwakeController`.
    @AppStorage("keepScreenAwakeDuringTasks") private var keepScreenAwakeDuringTasks: Bool = false
    /// [T-keyboard-auto-pop default flip] When true, the input field becomes
    /// the first responder ~1.5 s after the model finishes a reply (the
    /// historical behavior). ON by default — most users want the composer
    /// ready for a follow-up immediately. Existing users who explicitly
    /// toggled OFF keep their stored false; users who never opened the
    /// toggle get the new ON default via @AppStorage's fallback.
    @AppStorage("chat.autoFocusAfterReply") private var autoFocusAfterReply: Bool = true
    /// [T-thinking-auto-expand-toggle] When true (default, historical
    /// behavior) a NEW streaming thinking block auto-expands while reasoning
    /// streams. When false it stays collapsed until tapped. Read at
    /// block-mount time in ThinkingBlockView.
    @AppStorage("chat.autoExpandThinking") private var autoExpandThinking: Bool = true
    @ObservedObject private var fontSettings = FontSettings.shared

    private let iconOptions: [AppIconOption] = [
        AppIconOption(id: 0, title: "Automatic", subtitle: "Follows system", iconName: nil, imageName: "AlternateIcons/AppIcon-Light"),
        AppIconOption(id: 1, title: "Light", subtitle: "Always light", iconName: "AppIcon-Light", imageName: "AlternateIcons/AppIcon-Light"),
        AppIconOption(id: 2, title: "Dark", subtitle: "Always dark", iconName: "AppIcon-Dark", imageName: "AlternateIcons/AppIcon-Dark"),
        AppIconOption(id: 3, title: "Light (Legacy)", subtitle: "Classic light icon", iconName: "AppIcon-LegacyLight", imageName: "AlternateIcons/AppIcon-LegacyLight"),
        AppIconOption(id: 4, title: "Dark (Legacy)", subtitle: "Classic dark icon", iconName: "AppIcon-LegacyDark", imageName: "AlternateIcons/AppIcon-LegacyDark"),
    ]

    var body: some View {
        List {
            Section {
                Picker("Theme", selection: $appearanceMode) {
                    Text("System").tag(0)
                    Text("Light").tag(1)
                    Text("Dark").tag(2)
                }
                .pickerStyle(.segmented)
            } header: {
                Text("Theme")
            } footer: {
                Text("Override the system appearance for this app.")
            }
            .onChange(of: appearanceMode) { _ in
                guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
                windowScene.windows.forEach { window in
                    window.overrideUserInterfaceStyle = appearanceMode == 1 ? .light : appearanceMode == 2 ? .dark : .unspecified
                }
            }

            Section {
                Picker("Launch Session", selection: $launchScreen) {
                    Text("Auto").tag(0)
                    Text("Last Session").tag(1)
                    Text("New Chat").tag(2)
                    Text("Home").tag(3)
                }
            } header: {
                Text("Launch Session")
            } footer: {
                Text("Choose what to show when the app starts. \"Auto\" opens a new chat if the last session is older than 15 minutes.")
            }

            Section {
                Picker(String(localized: "Return Key"), selection: $returnKeyBehavior) {
                    Text(String(localized: "Newline")).tag(0)
                    Text(String(localized: "Send")).tag(1)
                }
                .pickerStyle(.segmented)
            } header: {
                Text("Return Key")
            } footer: {
                Text("Choose whether the Return key in the chat input inserts a newline or sends the message. Hardware Shift+Return always inserts a newline.")
            }

            Section {
                Toggle(String(localized: "Keep Screen Awake"), isOn: $keepScreenAwakeDuringTasks)
            } header: {
                Text("Keep Screen Awake")
            } footer: {
                Text("Prevent the screen from sleeping while any session is running a task. May increase battery drain.")
            }

            Section {
                Toggle(String(localized: "Auto-Focus Input After Reply"), isOn: $autoFocusAfterReply)
            } header: {
                Text("Auto-Focus Input After Reply")
            } footer: {
                Text("When on, the keyboard pops up automatically after the model finishes replying so the input is ready for a follow-up. On by default; turn off if you prefer to read the response without an unexpected keyboard.")
            }

            Section {
                Toggle(String(localized: "Tool Preview Window"), isOn: $toolPreviewEnabled)
            } header: {
                Text("Tool Status Bar")
            } footer: {
                Text("Show a live preview thumbnail alongside the tool status bar during agent execution.")
            }

            // [T-thinking-auto-expand-toggle] Whether a NEW streaming thinking
            // block opens expanded (historical behavior, default) or stays
            // collapsed. Only affects the streaming auto-expand; manual taps
            // always work either way.
            Section {
                Toggle(String(localized: "Expand Thinking While Streaming"), isOn: $autoExpandThinking)
            } header: {
                Text("Deep Thinking")
            } footer: {
                Text("When on, a new thinking block expands automatically while the model is reasoning and collapses when it finishes. When off, thinking blocks stay collapsed — tap one to read it.")
            }

            Section {
                FontScaleRow(
                    label: "Chat Input",
                    level: $fontSettings.chatInputScale
                )
                FontScaleRow(
                    label: "Message Text",
                    level: $fontSettings.messageBaseScale
                )
                FontScaleRow(
                    label: "App Base",
                    level: $fontSettings.appBaseScale
                )
                if fontSettings.isModified {
                    Button("Reset to Defaults") {
                        fontSettings.resetToDefaults()
                    }
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .center)
                }
            } header: {
                Text("Font Size")
            } footer: {
                Text("Scale fonts for chat input, message content, and general app text. Changes apply immediately.")
            }

            if UIApplication.shared.supportsAlternateIcons {
                Section {
                    ForEach(iconOptions) { option in
                        Button {
                            guard appIconMode != option.id else { return }
                            appIconMode = option.id
                            UIApplication.shared.setAlternateIconName(option.iconName) { error in
                                if let error = error {
                                    print("[AppIcon] Failed to set icon: \(error.localizedDescription)")
                                }
                            }
                        } label: {
                            HStack(spacing: 14) {
                                if let img = UIImage(named: option.imageName) {
                                    Image(uiImage: img)
                                        .resizable()
                                        .aspectRatio(contentMode: .fill)
                                        .frame(width: 60, height: 60)
                                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                                .stroke(Color.primary.opacity(0.15), lineWidth: 0.5)
                                        )
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    // `option.title` / `option.subtitle` are fixed English
                                    // keys ("Automatic", "Light", "Always light", etc.).
                                    // Wrap in LocalizedStringKey so the catalog lookup kicks
                                    // in — Text(String) would render them verbatim.
                                    Text(LocalizedStringKey(option.title))
                                        .foregroundStyle(.primary)
                                    Text(LocalizedStringKey(option.subtitle))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if appIconMode == option.id {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.blue)
                                        .font(.title3)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                } header: {
                    Text("App Icon")
                } footer: {
                    Text("\"Automatic\" uses the system icon which adapts to Dark Mode on iOS 18+.")
                }
            }

            Section {
                ForEach(supportedLanguages) { lang in
                    Button {
                        // Persist a reopen-hint BEFORE flipping appLanguage —
                        // the @AppStorage write triggers the root
                        // `.id(appLanguage)` rebuild in MinisApp.swift, which
                        // drops the entire view tree including the Settings
                        // sheet. ContentView/SettingsSheet read this flag on
                        // re-mount and reopen the sheet + push back to the
                        // Appearance page so the user lands where they were
                        // with all strings rendered in the new language.
                        UserDefaults.standard.set("appearance", forKey: "pendingSettingsReopen")
                        appLanguage = lang.id
                        Bundle.setLanguage(lang.id.isEmpty ? nil : lang.id)
                    } label: {
                        HStack(spacing: 12) {
                            if !lang.flag.isEmpty {
                                Text(lang.flag).font(.title2)
                            } else {
                                Image(systemName: "globe")
                                    .font(.title3)
                                    .foregroundStyle(.secondary)
                                    .frame(width: 28)
                            }
                            Text(lang.name)
                                .foregroundStyle(.primary)
                            Spacer()
                            if appLanguage == lang.id {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.blue)
                                    .fontWeight(.semibold)
                            }
                        }
                    }
                }
            } header: {
                Text("Language")
            } footer: {
                Text("Override the display language for this app. \"System\" follows your device language.")
            }
        }
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
        .background(InteractivePopGestureDisabler())
    }
}

/// Disables the navigation controller's interactive pop gesture on the hosting page
/// to prevent conflict with horizontal Slider drag.
private struct InteractivePopGestureDisabler: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = GestureDisablerView()
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) {}

    private class GestureDisablerView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            setPopGesture(enabled: false)
        }
        override func willMove(toWindow newWindow: UIWindow?) {
            super.willMove(toWindow: newWindow)
            if newWindow == nil { setPopGesture(enabled: true) }
        }
        private func setPopGesture(enabled: Bool) {
            // Walk the responder chain to find the UINavigationController
            var responder: UIResponder? = self
            while let r = responder {
                if let nav = r as? UINavigationController {
                    nav.interactivePopGestureRecognizer?.isEnabled = enabled
                    return
                }
                responder = r.next
            }
        }
    }
}

// MARK: - Settings Sheet

private enum SettingsDestination: Hashable {
    case providers
    case providerDetail(instanceId: String)
    case modelGroups
    case modelGroupDetail(groupId: String)
    case usage
    case skills
    case memory
    case storage
    case mountedFolders
    case sharedFolders
    case logs
    case appearance
    case background
    case scheduledTasks
    case about
    case permissions
    case environments
    // [T-mcp-oauth-deeplink]
    case mcpIntegrations
    case mcpServerDetail(serverId: String)
}

private struct SettingsSheet: View {
    @Binding var showTerminal: Bool
    @AppStorage("appearanceMode") private var appearanceMode: Int = 0
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var deepLink = DeepLinkCoordinator.shared
    @State private var navPath = NavigationPath()
    @State private var showFeedbackDialog = false

    var body: some View {
        NavigationStack(path: $navPath) {
            List {
                Section {
                    NavigationLink {
                        ProviderInstancesView()
                    } label: {
                        if #available(iOS 26, *) {
                            Label("Manage Providers", systemImage: "key.circle.fill")
                        } else {
                            Label("Manage Providers", systemImage: "lock.circle.fill")
                        }
                    }

                    NavigationLink {
                        ModelGroupsView()
                    } label: {
                        Label("Model Groups", systemImage: "gearshape.circle.fill")
                    }

                    NavigationLink {
                        UsageStatsView()
                    } label: {
                        Label("Token Usage", systemImage: "chart.line.uptrend.xyaxis.circle.fill")
                    }
                } header: {
                    Text("LLM Providers")
                } footer: {
                    Text("Configure which models the agent uses, manage API keys & OAuth for each provider, and create model groups for fallback or load balancing.")
                }

                Section("Appearance") {
                    NavigationLink {
                        AppearanceSettingsView()
                    } label: {
                        Label {
                            Text("Appearance")
                        } icon: {
                            Image(systemName: "paintbrush.fill")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                }

                Section("Agent Runtime") {
                    NavigationLink {
                        ScheduledTasksSettingsView()
                    } label: {
                        Label {
                            Text("Scheduled Tasks")
                        } icon: {
                            Image(systemName: "clock.badge.checkmark")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.orange, in: Circle())
                        }
                    }
                    NavigationLink {
                        SkillsManagementView()
                    } label: {
                        Label {
                            Text("Skills")
                        } icon: {
                            Image(systemName: "puzzlepiece.extension")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.blue, in: Circle())
                        }
                    }
                    NavigationLink {
                        SoulSettingsView()
                    } label: {
                        Label {
                            Text("Soul")
                        } icon: {
                            Image(systemName: "sparkles")
                                .font(.system(size: 11))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.pink, in: Circle())
                        }
                    }
                    NavigationLink {
                        MemoryManagementView()
                    } label: {
                        Label {
                            Text("Memory")
                        } icon: {
                            Image(systemName: "brain.head.profile")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.purple, in: Circle())
                        }
                    }
                    NavigationLink {
                        MCPIntegrationsView()
                    } label: {
                        Label {
                            Text("MCP Integrations")
                        } icon: {
                            Image(systemName: "square.stack.3d.up")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.teal, in: Circle())
                        }
                    }
                    NavigationLink {
                        EnvironmentVariablesView()
                    } label: {
                        Label {
                            Text("Environment Variables")
                        } icon: {
                            Image(systemName: "terminal")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.green, in: Circle())
                        }
                    }
                }

                Section("Storage") {
                    NavigationLink {
                        StorageManagementView()
                    } label: {
                        Label {
                            Text("Storage")
                        } icon: {
                            Image(systemName: "archivebox")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.blue, in: Circle())
                        }
                    }
                    NavigationLink {
                        SharedFoldersSettingsView()
                    } label: {
                        Label {
                            Text("Shared Folders")
                        } icon: {
                            Image(systemName: "folder.fill.badge.person.crop")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.green, in: Circle())
                        }
                    }
                    NavigationLink {
                        MountedFoldersSettingsView()
                    } label: {
                        Label {
                            Text("Mount External Folders")
                        } icon: {
                            Image(systemName: "externaldrive.badge.plus")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.orange, in: Circle())
                        }
                    }
                    if #available(iOS 17.0, *) {
                        NavigationLink {
                            // v2 is the default sync engine; legacy v1
                            // settings page is unreachable from here.
                            CloudSyncSettingsV2View()
                        } label: {
                            Label {
                                Text("iCloud Sync")
                            } icon: {
                                Image(systemName: "icloud")
                                    .font(.system(size: 9))
                                    .foregroundStyle(.white)
                                    .frame(width: 21, height: 21)
                                    .background(.cyan, in: Circle())
                            }
                        }
                    }
                }

                Section("Permissions") {
                    NavigationLink {
                        OffloadPermissionSettingsView()
                    } label: {
                        Label {
                            Text("Permissions")
                        } icon: {
                            Image(systemName: "lock.shield")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.red, in: Circle())
                        }
                    }
                    if BiometricAuth.isAvailable {
                        NavigationLink {
                            FaceIDProtectionSettingsView()
                        } label: {
                            Label {
                                Text("\(BiometricAuth.biometryDisplayName) Protection")
                            } icon: {
                                // Match SF Symbol to the device's actual sensor — Touch ID
                                // devices showed a Face ID glyph here before.
                                Image(systemName: BiometricAuth.biometryIconName)
                                    .font(.system(size: 11))
                                    .foregroundStyle(.white)
                                    .frame(width: 21, height: 21)
                                    .background(.teal, in: Circle())
                            }
                        }
                    }
                }

                Section("Logs") {
                    NavigationLink {
                        LogManagementView()
                    } label: {
                        Label {
                            Text("Logs")
                        } icon: {
                            Image(systemName: "doc.text")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.gray, in: Circle())
                        }
                    }
                }

                Section("About") {
                    NavigationLink {
                        AboutView()
                    } label: {
                        Label {
                            Text("About Minis")
                        } icon: {
                            Image(systemName: "info")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                    Link(destination: URL(string: "https://openminis.github.io/privacy-policy.html")!) {
                        Label {
                            Text("Privacy Policy")
                        } icon: {
                            Image(systemName: "hand.raised")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.teal, in: Circle())
                        }
                    }
                    Button {
                        showFeedbackDialog = true
                    } label: {
                        Label {
                            Text("Feedback")
                        } icon: {
                            Image(systemName: "bubble.left.and.bubble.right.fill")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                    .foregroundStyle(.primary)
                    .confirmationDialog("Feedback", isPresented: $showFeedbackDialog, titleVisibility: .visible) {
                        Button("Report a Bug (GitHub)") {
                            if let url = Self.makeBugReportURL() { UIApplication.shared.open(url) }
                        }
                        Button("Feedback (Telegram)") {
                            if let url = URL(string: "https://t.me/+2NzhOJuzRyI1YmM1") { UIApplication.shared.open(url) }
                        }
                        Button("Feedback (Email)") {
                            if let url = Self.makeFeedbackEmailURL() { UIApplication.shared.open(url) }
                        }
                        Button("Cancel", role: .cancel) {}
                    }
                }

            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .navigationDestination(for: SettingsDestination.self) { dest in
                switch dest {
                case .providers:
                    ProviderInstancesView()
                case .providerDetail(let id):
                    ProviderInstanceDetailView(instanceId: id)
                case .modelGroups:
                    ModelGroupsView()
                case .modelGroupDetail(let id):
                    ModelGroupDetailView(groupId: id)
                case .usage:
                    UsageStatsView()
                case .skills:
                    SkillsManagementView()
                case .memory:
                    MemoryManagementView()
                case .storage:
                    StorageManagementView()
                case .mountedFolders:
                    MountedFoldersSettingsView()
                case .sharedFolders:
                    SharedFoldersSettingsView()
                case .logs:
                    // Pull a one-shot tab hint from the deep link router
                    // (e.g. `?tab=config-audit`). LogManagementView clears
                    // its local state independently; the published value
                    // here is consumed once and reset to nil.
                    LogManagementView(initialTab: deepLink.pendingLogsTab ?? "logs")
                        .onAppear { deepLink.pendingLogsTab = nil }
                case .appearance:
                    AppearanceSettingsView()
                case .background:
                    EnhancedBackgroundSettingsView()
                case .scheduledTasks:
                    ScheduledTasksSettingsView()
                case .about:
                    AboutView()
                case .environments:
                    EnvironmentVariablesView()
                case .permissions:
                    OffloadPermissionSettingsView()
                // [T-mcp-oauth-deeplink] Detail = the list view told to open
                // the server's edit sheet on appear; a deleted/unknown server
                // just lands on the list (no crash, sensible fallback).
                case .mcpIntegrations:
                    MCPIntegrationsView()
                case .mcpServerDetail(let serverId):
                    MCPIntegrationsView(initialEditServerId: serverId)
                }
            }
            .onAppear {
                applyPendingDeepLink()
                // Legacy flags — kept so older call sites keep working.
                if deepLink.showEnvironmentVariables {
                    navPath.append(SettingsDestination.environments)
                    deepLink.showEnvironmentVariables = false
                }
                if deepLink.showPermissions {
                    navPath.append(SettingsDestination.permissions)
                    deepLink.showPermissions = false
                }
                // Restore the user's location after a language-change rebuild.
                // AppearanceSettingsView's language picker writes this flag
                // right before flipping `appLanguage`, knowing the root
                // `.id(appLanguage)` will tear the whole tree down. ContentView
                // re-opens the sheet on re-mount; here we push back to the
                // destination so the user lands where they were, now rendered
                // in the new language.
                if let dest = UserDefaults.standard.string(forKey: "pendingSettingsReopen") {
                    UserDefaults.standard.removeObject(forKey: "pendingSettingsReopen")
                    switch dest {
                    case "appearance":
                        navPath.append(SettingsDestination.appearance)
                    default:
                        break
                    }
                }
            }
            .onChange(of: deepLink.pendingSettingsTarget) { _ in
                applyPendingDeepLink()
            }
        }
        .preferredColorScheme(appearanceMode == 1 ? .light : appearanceMode == 2 ? .dark : nil)
        .appFontScale()
    }

    /// Translate `DeepLinkCoordinator.pendingSettingsTarget` into a
    /// NavigationStack push and clear the pending value. Called from
    /// `onAppear` (cold-start deep link) and `onChange` (deep link
    /// arriving while the sheet is already open).
    ///
    /// `.environments` keeps its existing prefill semantics — the
    /// environments view consumes `pendingEnvVarCreate` separately on
    /// appear, so we only have to navigate here.
    private func applyPendingDeepLink() {
        guard let target = deepLink.pendingSettingsTarget else { return }
        // Reset path so deep links are predictable: a deep link always
        // lands on the requested destination as the only stack entry,
        // not on top of whatever the user was browsing earlier.
        navPath = NavigationPath()
        switch target {
        case .home:
            break // already at Settings root
        case .providers:
            navPath.append(SettingsDestination.providers)
        case .providerDetail(let id):
            navPath.append(SettingsDestination.providers)
            navPath.append(SettingsDestination.providerDetail(instanceId: id))
        case .modelGroups:
            navPath.append(SettingsDestination.modelGroups)
        case .modelGroupDetail(let id):
            navPath.append(SettingsDestination.modelGroups)
            navPath.append(SettingsDestination.modelGroupDetail(groupId: id))
        case .usage:
            navPath.append(SettingsDestination.usage)
        case .skills:
            navPath.append(SettingsDestination.skills)
        case .memory:
            navPath.append(SettingsDestination.memory)
        case .storage:
            navPath.append(SettingsDestination.storage)
        case .mountedFolders:
            navPath.append(SettingsDestination.mountedFolders)
        case .sharedFolders:
            navPath.append(SettingsDestination.sharedFolders)
        case .logs:
            navPath.append(SettingsDestination.logs)
        case .appearance:
            navPath.append(SettingsDestination.appearance)
        case .background:
            navPath.append(SettingsDestination.background)
        case .scheduledTasks:
            navPath.append(SettingsDestination.scheduledTasks)
        case .about:
            navPath.append(SettingsDestination.about)
        case .permissions:
            navPath.append(SettingsDestination.permissions)
        case .environments:
            navPath.append(SettingsDestination.environments)
        case .mcpIntegrations:
            navPath.append(SettingsDestination.mcpIntegrations)
        case .mcpServerDetail(let id):
            navPath.append(SettingsDestination.mcpServerDetail(serverId: id))
        }
        deepLink.pendingSettingsTarget = nil
    }

    /// Compose the feedback mailto URL with a prefilled body that includes
    /// app version, iOS version, and a machine identifier, plus a prompt
    /// asking the user to attach a screenshot manually (mailto:// can't
    /// auto-attach). Using URLComponents so the subject and body go through
    /// proper URL encoding without hand-rolling addingPercentEncoding calls.
    fileprivate static func makeFeedbackEmailURL() -> URL? {
        let bundle = Bundle.main
        let appVersion = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        let iosVersion = UIDevice.current.systemVersion
        let device = machineIdentifier()

        let body = """
        Please describe your feedback:


        ---
        App Version: \(appVersion) (\(build))
        iOS Version: \(iosVersion)
        Device: \(device)

        Screenshot (optional): Please attach a screenshot if relevant.
        """

        var components = URLComponents()
        components.scheme = "mailto"
        components.path = "dev@openminis.app"
        components.queryItems = [
            URLQueryItem(name: "subject", value: "Minis Feedback"),
            URLQueryItem(name: "body", value: body),
        ]
        return components.url
    }

    /// Build the GitHub Issue URL with a bilingual bug-report template
    /// pre-filled with platform / OS / app / device info. SwiftUI `Link`
    /// hands the URL to UIApplication.shared.open, which routes to Safari.
    fileprivate static func makeBugReportURL() -> URL? {
        let bundle = Bundle.main
        let appVersion = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        let iosVersion = UIDevice.current.systemVersion
        let device = machineIdentifier()

        let body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | iOS |
        | OS Version | iOS \(iosVersion) |
        | Minis Version | \(appVersion) (build \(build)) |
        | Device Model | \(device) |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

        """

        var components = URLComponents(string: "https://github.com/OpenMinis/OpenMinis/issues/new")
        components?.queryItems = [
            URLQueryItem(name: "template", value: "bug_report.md"),
            URLQueryItem(name: "title", value: "[Bug] "),
            URLQueryItem(name: "body", value: body),
        ]
        return components?.url
    }

    /// Returns the hardware model identifier, e.g. "iPhone16,2".
    /// `UIDevice.current.model` returns the generic "iPhone" / "iPad" and
    /// isn't useful in a bug report, so we fall back to utsname.
    private static func machineIdentifier() -> String {
        var sys = utsname()
        uname(&sys)
        let id = withUnsafePointer(to: &sys.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) {
                String(cString: $0)
            }
        }
        return id.isEmpty ? UIDevice.current.model : id
    }
}

#Preview {
    ContentView()
}

/// Rotate-and-pause animation for the "syncing" title indicator. Period
/// scales with the active throttle so the user can tell at a glance how
/// fast sync is going: full speed = quick pulses, background = slow.
///
/// Loop is driven by a `.task` whose Task is cancelled automatically when
/// the view disappears. Earlier versions used `onAppear { tick() }` with
/// a self-rescheduling `DispatchQueue.main.asyncAfter` chain, which never
/// stopped the old chain — so each tab-switch back to home spawned a new
/// chain on top of the previous one, doubling/quadrupling perceived
/// rotation speed until the view tree rebuilt.
private struct PulseRotateIcon: View {
    @State private var rotation: Double = 0
    var body: some View {
        Image(systemName: "arrow.triangle.2.circlepath")
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.primary)
            .rotationEffect(.degrees(rotation))
            .task { await pulseLoop() }
    }
    /// Reads SyncCore's currentSendDelay (5s sync sheet → 60s background)
    /// and converts to an animation cadence: animation phase ≈ delay/4,
    /// hold phase ≈ delay/4. Clamped so it never feels frozen or frantic.
    private func currentPeriod() -> (anim: TimeInterval, hold: TimeInterval) {
        let delay: TimeInterval
        if #available(iOS 17.0, *) {
            delay = SyncCore.shared.currentSendDelay
        } else {
            delay = 10
        }
        // 5s → 1.0s anim + 1.0s hold → ~2s period (active)
        // 15s → 1.5s anim + 1.5s hold
        // 30s → 2.0s anim + 2.0s hold
        // 60s → 2.5s anim + 2.5s hold (slow)
        let anim = max(0.8, min(2.5, delay / 12 + 0.5))
        let hold = anim
        return (anim, hold)
    }
    @MainActor
    private func pulseLoop() async {
        while !Task.isCancelled {
            let (anim, hold) = currentPeriod()
            withAnimation(.easeInOut(duration: anim)) {
                rotation += 180
            }
            let total = UInt64((anim + hold) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: total)
        }
    }
}

/// Transient confirmation banner shown after a Force Sync runs against
/// a multi-session selection. Floats at the top of the home view, fades
/// in and out over ~4s. Mirrors the iOS system "Now playing" pill.
private struct ForceSyncToastBanner: View {
    let text: String
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "icloud.and.arrow.up")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
            Text(text)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.accentColor.opacity(0.92))
        )
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
        .padding(.horizontal, 16)
        .frame(maxWidth: 480)
    }
}
