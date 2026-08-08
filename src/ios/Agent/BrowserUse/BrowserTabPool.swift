import Foundation
import UIKit
import WebKit
import Combine
import os.log

private let logger = AppLogger(category: "BrowserTabPool")
private let registryLogger = AppLogger(category: "BrowserTabPoolRegistry")

/// Process-wide cap on live `WKWebView` tabs across all `BrowserTabPool`s.
///
/// Each tab keeps an out-of-process WebContent (~30-80 MB resident) plus
/// shared JS heap; under multi-session concurrency the per-pool `maxTabs=3`
/// cap doesn't bound total footprint, so heavy use trips iOS VM exhaustion
/// (`mach_vm_allocate_kernel failed` / `pmap_enter resource shortage`) which
/// surfaces as null-deref crashes inside WebCore. The registry coordinates
/// across pools so creating a tab in one session can reclaim an idle tab in
/// another.
@MainActor
final class BrowserTabPoolRegistry {
    static let shared = BrowserTabPoolRegistry()

    /// Hard cap on live tabs across the whole process. Roughly 3 concurrent
    /// sessions × ~2-3 active tabs each. Tunable here if profiling says
    /// otherwise.
    static let globalTabCap = 8

    private struct WeakPool {
        weak var pool: BrowserTabPool?
    }
    private var pools: [ObjectIdentifier: WeakPool] = [:]
    private var memoryWarningObserver: NSObjectProtocol?

    private init() {
        memoryWarningObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in
                BrowserTabPoolRegistry.shared.handleMemoryWarning()
            }
        }
        installLivenessProbe()
    }

    func register(_ pool: BrowserTabPool) {
        pools[ObjectIdentifier(pool)] = WeakPool(pool: pool)
    }

    func unregister(_ pool: BrowserTabPool) {
        pools.removeValue(forKey: ObjectIdentifier(pool))
    }

    /// Total live tabs across all registered pools (drops stale weak refs).
    func totalLiveTabs() -> Int {
        compactPools()
        return pools.values.reduce(0) { $0 + ($1.pool?.tabs.count ?? 0) }
    }

    /// Try to make room for one more tab in `requester`. Prefers an idle
    /// (non-`inUse`) sibling tab; if none exist, preempts the oldest
    /// `inUse` sibling tab — the agent driving that tab will see a single
    /// "tab was reclaimed, please retry" error on its next action and the
    /// tab's URL is saved for restoration. The caller itself is excluded
    /// from eviction. Returns false only when there are zero candidate
    /// tabs in OTHER pools (e.g. only one pool exists and it's at cap).
    @discardableResult
    func requestSlot(for requester: BrowserTabPool) -> Bool {
        compactPools()
        if totalLiveTabs() < Self.globalTabCap { return true }

        let requesterId = ObjectIdentifier(requester)

        // Pass 1: prefer the oldest non-inUse tab in any sibling pool.
        var idleVictim: (pool: BrowserTabPool, tabId: Int, idle: TimeInterval)? = nil
        // Pass 2 (fallback): oldest inUse tab in any sibling pool. Tracked
        // in the same loop so we don't re-scan.
        var busyVictim: (pool: BrowserTabPool, tabId: Int, idle: TimeInterval)? = nil
        let now = Date()
        for (id, weak) in pools where id != requesterId {
            guard let pool = weak.pool else { continue }
            for tab in pool.tabs {
                let idle = now.timeIntervalSince(tab.lastActivityDate)
                if !tab.inUse {
                    if idleVictim == nil || idle > idleVictim!.idle {
                        idleVictim = (pool, tab.id, idle)
                    }
                } else {
                    if busyVictim == nil || idle > busyVictim!.idle {
                        busyVictim = (pool, tab.id, idle)
                    }
                }
            }
        }
        if let v = idleVictim {
            registryLogger.info("Global cap (\(Self.globalTabCap)) reached — evicting idle tab \(v.tabId) from sibling pool (idle \(String(format: "%.0f", v.idle))s) for requester")
            v.pool.evictTabForRegistry(id: v.tabId)
            return true
        }
        if let v = busyVictim {
            registryLogger.info("Global cap (\(Self.globalTabCap)) reached and no idle siblings — preempting in-use tab \(v.tabId) (idle \(String(format: "%.0f", v.idle))s) for requester")
            v.pool.preemptTabForRegistry(id: v.tabId)
            return true
        }
        registryLogger.info("Global cap (\(Self.globalTabCap)) reached and no sibling tabs to evict — denying slot")
        return false
    }

    /// On memory warning, evict every non-inUse tab across all pools. Tabs
    /// the agent is actively driving stay alive so in-flight actions don't
    /// fail mid-step.
    private func handleMemoryWarning() {
        compactPools()
        var evicted = 0
        for weak in pools.values {
            guard let pool = weak.pool else { continue }
            evicted += pool.evictAllIdleForMemoryWarning()
        }
        if evicted > 0 {
            registryLogger.info("Memory warning — evicted \(evicted) idle tab(s) across \(self.pools.count) pool(s)")
        }
    }

    struct TabSnapshot {
        let sessionId: String
        let tabId: Int
        let url: String
        let title: String
        let isLoading: Bool
        let inUse: Bool
        let lastActivityAgo: TimeInterval
    }

    func allTabSnapshots() -> [TabSnapshot] {
        compactPools()
        let now = Date()
        var result: [TabSnapshot] = []
        for weak in pools.values {
            guard let pool = weak.pool else { continue }
            let sid = String((pool.sessionId ?? "unknown").prefix(8))
            for tab in pool.tabs {
                result.append(TabSnapshot(
                    sessionId: sid,
                    tabId: tab.id,
                    url: String(tab.manager.currentURL.prefix(100)),
                    title: String(tab.manager.pageTitle.prefix(100)),
                    isLoading: tab.manager.isLoading,
                    inUse: tab.inUse,
                    lastActivityAgo: now.timeIntervalSince(tab.lastActivityDate)
                ))
            }
        }
        return result
    }

    private func compactPools() {
        pools = pools.filter { $0.value.pool != nil }
    }

    /// [BrowserToolDiag] Teach BrowserResourceMonitor how to tell whether a tab's
    /// WebContent process is still alive, without it having to reach into the
    /// browser layer. Installed on the registry (a single instance spanning every
    /// pool) rather than per-pool, since a per-pool closure would just overwrite
    /// its predecessor on the shared monitor.
    ///
    /// A killed WebContent leaves its WKWebView blank — url nil, title empty — so
    /// a blank view under a still-pending action is our only in-app evidence that
    /// the process died silently (its termination callback is documented as
    /// unreliable when it dies mid-render). We read the live WKWebView, not the
    /// manager's cached `currentURL`, because that cache keeps the last known
    /// value and would mask the very blanking we're testing for.
    private func installLivenessProbe() {
        BrowserResourceMonitor.shared.livenessProbe = { [weak self] tabId in
            guard let self else { return nil }
            self.compactPools()
            for weak in self.pools.values {
                guard let pool = weak.pool,
                      let tab = pool.tabs.first(where: { $0.id == tabId }) else { continue }
                let webView = tab.manager.webView
                return BrowserResourceMonitor.WebContentLiveness(
                    hasURL: webView.url != nil,
                    hasTitle: !(webView.title ?? "").isEmpty
                )
            }
            return nil
        }
    }
}

@MainActor
final class BrowserTabPool: ObservableObject {
    static let maxTabs = 3

    #if DEBUG
    /// Debug-only weak reference for the debug server to access the active pool.
    static weak var debugInstance: BrowserTabPool?
    #endif

    /// How long an idle tab survives before its WKWebView is destroyed (seconds).
    static let idleTimeout: TimeInterval = 15 * 60  // 15 minutes

    /// How often to check for idle tabs (seconds).
    private static let idleCheckInterval: TimeInterval = 60

    struct Tab: Identifiable {
        let id: Int
        let manager: BrowserUseManager
        /// True while a browser action is executing on this tab AND through a
        /// 15-second grace window after it completes. Implicit-tab requests
        /// (input.tabId == nil) skip a tab that's `inUse` — fanning out to a
        /// fresh tab when capacity allows — so back-to-back navigates can't
        /// land on tab 0 mid-load and stomp each other. The grace is re-armed
        /// by every subsequent action against this same tab (typically via
        /// explicit tab_id), so the agent's own follow-up reads/scrolls stay
        /// on its tab while another agent's fresh `navigate` fans out.
        /// [T-browser-implicit-tab-inuse-until-load-ios]
        var inUse: Bool = false
        /// Outstanding Task that will flip `inUse` back to false at the end of
        /// the current grace window. Each new completion cancels the previous
        /// task and arms a fresh 15-second timer.
        var graceExpiryTask: Task<Void, Never>? = nil
        /// When this tab was last actively used (action executed or released).
        var lastActivityDate: Date = Date()
    }

    /// Grace window kept after each action completes — see `Tab.inUse`.
    /// Matches the user's spec for #594 ("15s grace, re-armed on every action").
    static let postActionGraceSeconds: TimeInterval = 15

    /// URLs saved from evicted tabs, keyed by tab ID, for restoration on next use.
    private var savedURLs: [Int: String] = [:]

    /// Tab IDs that the registry preempted while still inUse. Each entry
    /// fires one "tab was reclaimed, please retry" error on the next
    /// `execute(action:)` for that tab and is then cleared. The agent's
    /// retry will go through `acquireTab` again, restoring the URL.
    private var preemptedTabIds: Set<Int> = []

    // MARK: - Per-tab serialization (T-browser-use-per-tab-serial-ios)

    /// Tail of each tab id's serial chain. Concurrent tool execution (TaskGroup)
    /// can call `execute(action:)` for the SAME tab id at the same time; the
    /// pool is @MainActor but each call `await`s `manager.execute`, releasing the
    /// actor and letting a sibling operate the same WKWebView mid-flight — the
    /// two stomp on each other (the "9to5Mac" red tool error). We serialize per
    /// tab id: an operation awaits the previous one for that tab before touching
    /// the manager. Different tab ids keep independent chains and stay parallel.
    /// Each tail is tagged with a monotonic token so the tail-cleanup compares by
    /// value (Task is a struct — no identity `===`).
    private var tabSerialChains: [Int: (token: UInt64, task: Task<Void, Never>)] = [:]
    private var nextSerialToken: UInt64 = 0

    /// Max time a browser_use call waits to acquire a tab id's serial slot, i.e.
    /// for the in-flight operation on that SAME tab to finish. This is the
    /// LOCK-WAIT timeout ONLY — it is deliberately separate from the per-action
    /// browser timeout (which is unchanged and applies AFTER the slot is held).
    private static let serialWaitTimeout: TimeInterval = 60

    @Published var tabs: [Tab] = []
    @Published var selectedTabId: Int = 0 {
        didSet { rebindLoadingObservation() }
    }
    @Published var userAgentProfile: UserAgentProfile = .mobileSafari
    @Published var customUserAgentString: String = ""

    /// Global custom viewport. Zero means "use the UA profile default".
    /// Persisted across launches via UserDefaults. Session-level overrides
    /// live in `sessionViewportWidth`/`sessionViewportHeight`.
    @Published var customViewportWidth: Int = 0
    @Published var customViewportHeight: Int = 0

    /// Per-session viewport override set via the `set_viewport` action.
    /// When both > 0 these shadow the global custom viewport. Persisted in
    /// the session tabs file, not UserDefaults, and cleared by
    /// `set_viewport --reset`.
    @Published private(set) var sessionViewportWidth: Int = 0
    @Published private(set) var sessionViewportHeight: Int = 0

    // MARK: - UA Defaults Keys
    private static let uaProfileKey = "BrowserUserAgentProfile"
    private static let uaCustomStringKey = "BrowserCustomUserAgentString"

    // MARK: - Viewport Defaults Keys (global)
    private static let viewportWidthKey = "BrowserCustomViewportWidth"
    private static let viewportHeightKey = "BrowserCustomViewportHeight"

    /// Session ID for disk persistence. Set by AIChatViewModel.
    var sessionId: String? {
        didSet {
            if let sessionId, sessionId != oldValue {
                loadPersistedURLs()
            }
        }
    }

    /// Timer that periodically evicts idle tabs.
    private var idleTimer: Timer?
    private var loadingObservation: AnyCancellable?

    /// Whether the active tab's webview is currently loading a page.
    @Published var isBrowserLoading: Bool = false

    /// Whether the agent is actively executing a browser action on any tab.
    var isAgentBrowsing: Bool {
        tabs.contains { $0.inUse }
    }

    /// The manager for the currently selected UI tab.
    var activeManager: BrowserUseManager? {
        tabs.first { $0.id == selectedTabId }?.manager
    }

    /// True when any tab is mid-navigation — used by the per-tool-call stop
    /// button to decide whether there's a browser load worth cancelling.
    /// [T-ios-browser-tool-stop-button]
    var hasLoadingTab: Bool {
        tabs.contains { $0.manager.isLoading }
    }

    /// Stop in-flight page loads on EVERY tab. A hung `navigate` awaits the
    /// manager's navigationContinuation, which `stopLoading()` resolves (WebKit
    /// fires didFailProvisionalNavigation/cancelled), so the awaited
    /// `execute(action:)` returns and the tool call completes. Mirrors the
    /// "one stop tap cancels every in-flight shell" semantics for the browser
    /// side. Returns the number of tabs that were actually loading.
    /// [T-ios-browser-tool-stop-button]
    @discardableResult
    func stopAllLoading() -> Int {
        var stopped = 0
        for tab in tabs where tab.manager.isLoading {
            tab.manager.stopLoading()
            stopped += 1
        }
        return stopped
    }

    init() {
        // Restore persisted UA preference
        if let raw = UserDefaults.standard.string(forKey: Self.uaProfileKey),
           let profile = UserAgentProfile(rawValue: raw) {
            userAgentProfile = profile
        }
        if let custom = UserDefaults.standard.string(forKey: Self.uaCustomStringKey) {
            customUserAgentString = custom
        }
        // Restore global custom viewport (0 = not set → fall back to UA).
        customViewportWidth = UserDefaults.standard.integer(forKey: Self.viewportWidthKey)
        customViewportHeight = UserDefaults.standard.integer(forKey: Self.viewportHeightKey)
        startIdleTimer()
        BrowserTabPoolRegistry.shared.register(self)
        #if DEBUG
        Self.debugInstance = self
        #endif
    }

    deinit {
        idleTimer?.invalidate()
        // Registry holds a weak ref and lazily compacts stale entries on
        // every operation, so we don't need to unregister explicitly here
        // (deinit is nonisolated; hopping to MainActor isn't safe).
    }

    /// Re-subscribe to the active manager's `isLoading` so `isBrowserLoading` stays in sync.
    private func rebindLoadingObservation() {
        loadingObservation?.cancel()
        guard let mgr = activeManager else {
            isBrowserLoading = false
            return
        }
        isBrowserLoading = mgr.isLoading
        loadingObservation = mgr.$isLoading
            .receive(on: DispatchQueue.main)
            .sink { [weak self] loading in
                self?.isBrowserLoading = loading
            }
    }

    // MARK: - Tab Lifecycle

    /// Acquire or create a tab for agent use. Returns the tab ID and marks it inUse.
    func acquireTab() -> Int {
        // If tab 0 exists and is available, use it
        if let idx = tabs.firstIndex(where: { $0.id == 0 }) {
            tabs[idx].inUse = true
            tabs[idx].lastActivityDate = Date()
            return 0
        }
        // Otherwise create tab 0, restoring saved URL if available.
        // Ask the registry for a global slot — may evict an idle tab in
        // another session's pool. If denied (everyone is busy), we still
        // create the tab: the agent needs *some* surface to act on, and
        // failing here would surface as an unrecoverable tool error.
        BrowserTabPoolRegistry.shared.requestSlot(for: self)
        let manager = makeManager()
        let tab = Tab(id: 0, manager: manager, inUse: true)
        tabs.append(tab)
        selectedTabId = 0
        restoreSavedURL(for: 0, manager: manager)
        logger.info("Created tab 0")
        return 0
    }

    /// Ensure tab 0 exists for UI display without marking it as agent-busy.
    func ensureTabForUI() {
        if !tabs.contains(where: { $0.id == 0 }) {
            // Pure UI prefetch — if we're at the global cap and can't evict
            // anyone, skip the prefetch. The user can still tap to create
            // it via acquireTab/newTab, which prioritise creation.
            guard BrowserTabPoolRegistry.shared.requestSlot(for: self) else {
                logger.info("Skipping UI prefetch tab — global cap reached and no idle siblings to evict")
                return
            }
            let manager = makeManager()
            let tab = Tab(id: 0, manager: manager, inUse: false)
            tabs.append(tab)
            selectedTabId = 0
            restoreSavedURL(for: 0, manager: manager)
            logger.info("Created tab 0 for UI")
        }
    }

    /// Create a new tab (up to maxTabs). Returns a result with the new tab ID or an error.
    func newTab(url: String? = nil) -> BrowserActionResult {
        guard tabs.count < Self.maxTabs else {
            return .error("Maximum of \(Self.maxTabs) tabs reached. Close a tab first with close_tab.")
        }
        // Try to make room globally. Unlike acquireTab, newTab is an
        // explicit "open another" — falling back to "load in current tab"
        // would be more confusing than a clear error, so refuse on denial.
        guard BrowserTabPoolRegistry.shared.requestSlot(for: self) else {
            return .error("Browser memory cap reached across all sessions (\(BrowserTabPoolRegistry.globalTabCap) tabs). Close a tab here or in another session first.")
        }
        let nextId = (tabs.map(\.id).max() ?? -1) + 1
        let manager = makeManager()
        let tab = Tab(id: nextId, manager: manager, inUse: true)
        tabs.append(tab)
        selectedTabId = nextId
        // If the agent supplied a URL with new_tab, load it. Previously the
        // url was silently dropped (the tool said "Opened new tab 0" but the
        // tab was blank) — restoreSavedURL only fires for tabs that had a
        // persisted URL, which a brand-new tab never has.
        // [T-ios-new-tab-url-dropped]
        let trimmedUrl = url?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let loadUrl = trimmedUrl, !loadUrl.isEmpty {
            manager.loadURL(loadUrl)
        } else {
            restoreSavedURL(for: nextId, manager: manager)
        }
        logger.info("Created new tab \(nextId), total: \(self.tabs.count)")
        let urlSuffix = (trimmedUrl?.isEmpty == false) ? " at \(trimmedUrl!)" : ""
        return BrowserActionResult(
            text: "Opened new tab \(nextId)\(urlSuffix). You now have \(tabs.count) tab(s). Use tab_id: \(nextId) to target this tab.",
            tabId: nextId)
    }

    /// Open a URL in a new tab, or in the current tab if at max capacity.
    func openURL(_ url: URL) {
        if tabs.count < Self.maxTabs {
            _ = newTab()
            activeManager?.loadURL(url.absoluteString)
        } else {
            // At capacity — load in current tab
            touchActivity()
            activeManager?.loadURL(url.absoluteString)
        }
    }

    /// P2: open a URL (or the current tab's URL) in the system default browser.
    private func openInSystemBrowser(url urlString: String?) -> BrowserActionResult {
        let target: String
        if let urlString, !urlString.isEmpty {
            target = urlString
        } else if let manager = selectedManager, !manager.currentURL.isEmpty {
            target = manager.currentURL
        } else {
            return .error("open_in_browser: 没有可打开的 URL（请先 navigate 或提供 url 参数）")
        }
        if let error = OpenInSystemBrowser.open(target) {
            return .error(error)
        }
        return BrowserActionResult(text: "已在系统默认浏览器中打开 \(target)")
    }

    /// Switch the browser engine for new tabs. Existing tabs keep their engine
    /// until closed; the current tab is re-created on the new engine at the
    /// same URL so the change is immediately visible.
    func switchEngine(to kind: BrowserEngineKind) -> BrowserActionResult {
        guard BlinkEngineBridge.isFrameworkPresent() || kind != .blink else {
            return .error("set_engine: Blink 引擎不可用（当前构建未嵌入 content_shell_framework；请安装 Blink/TrollStore 版本）")
        }
        BrowserEngineSettings.shared.kind = kind
        let oldURL = selectedManager?.currentURL

        // Re-create the selected tab on the new engine at the same URL.
        if let tab = tabs.first(where: { $0.id == selectedTabId }), tab.manager.engineKind != kind {
            let newManager = makeManager()
            wireManager(newManager)
            let replacement = Tab(id: tab.id, manager: newManager, inUse: true,
                                  graceExpiryTask: tab.graceExpiryTask,
                                  lastActivityDate: Date())
            if let idx = tabs.firstIndex(where: { $0.id == tab.id }) {
                tabs[idx] = replacement
            }
            if let oldURL, !oldURL.isEmpty {
                newManager.loadURL(oldURL)
            }
            return BrowserActionResult(
                text: "已切换到 \(kind.displayName) 引擎（新标签页生效；当前标签已在同一 URL 重建）",
                pageURL: oldURL.isEmpty ? nil : oldURL
            )
        }

        return BrowserActionResult(text: "已设置浏览器引擎为 \(kind.displayName)（新标签页生效）")
    }

    /// Create a new tab using a pre-built WKWebViewConfiguration (for window.open / OAuth popups).
    /// Returns the new tab's WKWebView so the opener retains a window.opener reference.
    /// WebKit will automatically load the navigation into the returned WKWebView.
    @discardableResult
    func openWithConfiguration(_ configuration: WKWebViewConfiguration, url: URL?) -> WKWebView? {
        guard tabs.count < Self.maxTabs else {
            // At capacity — load in current tab instead
            if let url {
                activeManager?.loadURL(url.absoluteString)
            }
            return nil
        }
        // Apply the same global gate as newTab. window.open opened by JS
        // is rare enough that returning nil (= cancel the popup) is the
        // least-bad outcome when memory is tight.
        guard BrowserTabPoolRegistry.shared.requestSlot(for: self) else {
            logger.info("Suppressing window.open popup — global tab cap reached")
            if let url {
                activeManager?.loadURL(url.absoluteString)
            }
            return nil
        }
        let nextId = (tabs.map(\.id).max() ?? -1) + 1
        let ua = resolvedUserAgentString()
        let vp = resolvedViewportSize()
        let manager = BrowserUseManager(profile: userAgentProfile, customString: ua,
                                        configuration: configuration,
                                        viewportWidth: vp.width, viewportHeight: vp.height)
        wireManager(manager)
        let tab = Tab(id: nextId, manager: manager, inUse: false)
        tabs.append(tab)
        selectedTabId = nextId
        logger.info("Created popup tab \(nextId) via window.open, total: \(self.tabs.count)")
        return manager.webView
    }

    /// Close the tab that owns the given WKWebView (called when window.close() fires).
    func closeWebView(_ webView: WKWebView) {
        if let tab = tabs.first(where: { $0.manager.webView === webView }) {
            _ = closeTab(id: tab.id)
        }
    }

    /// Close a specific tab and remove its WKWebView.
    func closeTab(id: Int) -> BrowserActionResult {
        guard let idx = tabs.firstIndex(where: { $0.id == id }) else {
            return .error("No tab with id \(id)")
        }
        // Don't save URL on explicit close — user intentionally closed it
        savedURLs.removeValue(forKey: id)
        tabs.remove(at: idx)
        logger.info("Closed tab \(id), remaining: \(self.tabs.count)")

        // If the closed tab was selected, switch to another
        if selectedTabId == id {
            selectedTabId = tabs.first?.id ?? 0
        }

        persistURLs()
        return BrowserActionResult(text: "Closed tab \(id). \(tabs.count) tab(s) remaining.")
    }

    /// List all open tabs with their URLs and titles.
    func listTabs() -> BrowserActionResult {
        if tabs.isEmpty {
            return BrowserActionResult(text: "No tabs open.")
        }
        var lines: [String] = ["Open tabs:"]
        for tab in tabs {
            let url = tab.manager.currentURL.isEmpty ? "(blank)" : tab.manager.currentURL
            let title = tab.manager.pageTitle.isEmpty ? "(untitled)" : tab.manager.pageTitle
            let marker = tab.id == selectedTabId ? " *" : ""
            lines.append("  Tab \(tab.id): \(title) — \(url)\(marker)")
        }
        return BrowserActionResult(text: lines.joined(separator: "\n"))
    }

    /// Acquire the serial slot for `tabId`, waiting at most `serialWaitTimeout`
    /// for the previous operation on that tab to finish. Returns a `release`
    /// closure on success (MUST be called when the operation completes, via
    /// `defer`), or `nil` if the wait timed out (caller should return the
    /// open-a-new-tab guidance error). The release is also what lets the NEXT
    /// queued operation for this tab proceed.
    ///
    /// Implementation: each caller appends a gated `Task` to the tab's chain and
    /// awaits the PREVIOUS tail (with a timeout). Its own gate is only completed
    /// by `release`, so the next caller blocks on it. The timeout races the
    /// predecessor await against a sleep — it does NOT cancel the predecessor's
    /// real browser work, only this caller's willingness to keep waiting.
    private func acquireSerialSlot(tabId: Int) async -> (() -> Void)? {
        let predecessor = tabSerialChains[tabId]?.task

        // Our gate: completed by `release`. The successor awaits this.
        // We previously stored the resume closure in an IUO populated INSIDE the
        // gate `Task { ... }` body. Under memory pressure the gate Task can be
        // scheduled late, so `release()` would run before the IUO was assigned
        // and crash on `nil!` (P0 SIGTRAP — 7 hits on a single iPhone 14 PM in
        // 8 minutes, all paired with mach_vm_allocate_kernel failed kernel
        // triage). [T-ios-browsertabpool-serialslot-nil-crash]
        //
        // Fix: store the gate as a one-shot box that can hold EITHER a
        // resume closure OR a pre-arrived `release()` signal. The gate Task
        // installs its continuation atomically; if release fired first, the
        // continuation resumes synchronously the moment it's installed. Either
        // ordering is safe — no IUO, no race.
        let token = nextSerialToken
        nextSerialToken &+= 1

        final class GateBox: @unchecked Sendable {
            private var lock = os_unfair_lock()
            private var resume: (() -> Void)?
            private var preFired = false

            /// Called by `release()`. If the gate Task has already installed
            /// its continuation, fire it; otherwise mark pre-fired so the
            /// installer fires immediately on arrival.
            func signal() {
                os_unfair_lock_lock(&lock)
                if let r = resume {
                    resume = nil
                    os_unfair_lock_unlock(&lock)
                    r()
                } else {
                    preFired = true
                    os_unfair_lock_unlock(&lock)
                }
            }

            /// Called from the gate Task once `withCheckedContinuation` hands
            /// it the resume closure. If release already fired, drain it now.
            func install(_ r: @escaping () -> Void) {
                os_unfair_lock_lock(&lock)
                if preFired {
                    os_unfair_lock_unlock(&lock)
                    r()
                } else {
                    resume = r
                    os_unfair_lock_unlock(&lock)
                }
            }
        }
        let gateBox = GateBox()
        let gate = Task<Void, Never> {
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                gateBox.install { cont.resume() }
            }
        }
        tabSerialChains[tabId] = (token, gate)

        var released = false
        let release: () -> Void = { [weak self] in
            guard !released else { return }
            released = true
            gateBox.signal()
            // Clear the chain entry if we're still the tail (no one queued after
            // us) — compare by token since Task is a struct.
            Task { @MainActor in
                guard let self else { return }
                if self.tabSerialChains[tabId]?.token == token {
                    self.tabSerialChains.removeValue(forKey: tabId)
                }
            }
        }

        guard let predecessor else {
            // No one ahead — slot is free immediately.
            return release
        }

        // Wait for the predecessor, but no longer than serialWaitTimeout.
        let waited = await withTaskGroup(of: Bool.self) { group -> Bool in
            group.addTask { await predecessor.value; return true }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(Self.serialWaitTimeout * 1_000_000_000))
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }

        if waited {
            return release
        } else {
            // Timed out waiting for the slot. Detach our gate from the chain so
            // we don't block successors, and signal the caller to bail.
            logger.warning("[SerialDiag] tab \(tabId): serial-wait timed out after \(Int(Self.serialWaitTimeout))s — releasing own gate, returning open-new-tab guidance")
            release()
            return nil
        }
    }

    /// Execute a browser action on the specified tab (or default tab 0).
    ///
    /// `singleTab` (T-browser-executejs-stale-context-ios): when true, the
    /// implicit-tab fan-out below is bypassed and an action with no explicit
    /// `tab_id` always targets `selectedTabId` (the current / most-recently
    /// navigated tab), even while that tab is inside its inUse post-action
    /// grace window. This is the `minis-browser-use` CLI path: a human/script
    /// drives a SERIAL sequence (navigate → execute_js → navigate → …) and
    /// expects "read the page I just navigated to", so spreading the read
    /// across fan-out tabs (which the grace-based fan-out does, to keep the
    /// agent's CONCURRENT navigates from stomping each other) is wrong here.
    /// The per-tab serial slot below is still honoured, so concurrent calls
    /// on the same tab stay safe. Explicit `--tab-id` still routes normally.
    /// The agent tool path leaves this false → fan-out behaviour unchanged.
    /// [BrowserToolDiag] Resource/lifecycle tracing wrapper for the
    /// backgrounded-hang investigation (a browser action was observed not
    /// returning for ~287 minutes). This is the single chokepoint every browser
    /// action passes through, so instrumenting it covers the whole tool surface.
    ///
    /// The real body lives in `executeInner`. Wrapping rather than sprinkling
    /// `defer`s inside it is deliberate: `executeInner` has a dozen early
    /// `return`s plus a `throw` path, and a wrapper cannot be escaped by a
    /// future one — whereas an in-body flag would silently mis-report the day
    /// someone adds another `return`.
    func execute(
        action input: BrowserActionInput,
        singleTab: Bool = false
    ) async throws -> BrowserActionResult {
        let diagId = BrowserResourceMonitor.shared.actionWillStart(
            action: input.action.rawValue,
            url: input.url,
            tabId: input.tabId
        )
        do {
            let result = try await executeInner(action: input, singleTab: singleTab)
            BrowserResourceMonitor.shared.actionDidFinish(
                diagId,
                success: result.success,
                // A failed action carries its reason in `text` — worth keeping,
                // since a background-suspension failure will read differently
                // from an ordinary page error.
                error: result.success ? nil : result.text
            )
            return result
        } catch {
            BrowserResourceMonitor.shared.actionDidFinish(
                diagId,
                success: false,
                error: "throw: \(error.localizedDescription)"
            )
            throw error
        }
    }

    private func executeInner(
        action input: BrowserActionInput,
        singleTab: Bool
    ) async throws -> BrowserActionResult {
        let poolStart = CFAbsoluteTimeGetCurrent()
        logger.info("[PoolTiming] enter action=\(input.action.rawValue) tab_id=\(input.tabId.map(String.init) ?? "nil") singleTab=\(singleTab)")

        // Handle tab management actions
        switch input.action {
        case .newTab:
            // Pass input.url through so the new tab actually loads it — Android
            // already does this (BrowserTabPool.kt NEW_TAB branch).
            // [T-ios-new-tab-url-dropped]
            return newTab(url: input.url)
        case .closeTab:
            let tabId = input.tabId ?? selectedTabId
            return closeTab(id: tabId)
        case .listTabs:
            return listTabs()
        case .openInBrowser:
            return openInSystemBrowser(url: input.url)
        case .setEngine:
            guard let kind = input.engine else {
                return .error("set_engine: 需要 engine 参数（webkit / blink / ssr）")
            }
            return switchEngine(to: kind)
        default:
            break
        }

        // Determine which tab to target.
        // Always go through ensureDefaultTab() when no tabs exist, even if
        // the model explicitly sends tab_id: 0 (which makes input.tabId non-nil).
        //
        // `explicitTabId`: did the model pin a specific tab? When it did NOT and
        // two such calls run concurrently (TaskGroup), both would otherwise land
        // on the SAME default tab and stomp / serialize-into-a-near-deadlock.
        // For an implicit-tab content action, if the default tab is already busy
        // with a sibling op AND we can still open another tab, give this call its
        // own fresh tab so the two navigates run in parallel and produce two tabs
        // (T-browser-use-per-tab-serial-ios — user's "should create two tabs, not
        // deadlock"). Only same explicit tab_id gets serialized below.
        let explicitTabId = input.tabId != nil
        // [T-ios-browser-readaction-tabid-monotonic-leak] Only *open-page*
        // actions (navigate) may fan out / create a fresh tab. Every other
        // action — read (get_readable, screenshot, get_text, get_page_info,
        // find_elements, get_backbone, get_cookies), exec (execute_js), and
        // operate (click, type, scroll, hover, …) — must run on an EXISTING
        // tab: the explicit tab_id if given, else the current / most-recently
        // active tab. Without this, a read action issued right after a
        // navigate saw tab 0 still `inUse` inside its 15s post-action grace,
        // found "no free tab", and fanned out to a blank new tab (the
        // monotonic 0→1→2 leak that returned empty text / blank screenshots).
        let isOpenPageAction = (input.action == .navigate)
        let targetId: Int
        // Set when an implicit navigate REUSES an idle tab that held a different
        // page — surfaced in the result so the model isn't left thinking the tab
        // still shows its old site. [T-browser-tab-reuse-silent-switch]
        var reusedTabSwitch: (tabId: Int, oldURL: String)? = nil
        if let requestedId = input.tabId, tabs.contains(where: { $0.id == requestedId }) {
            targetId = requestedId
        } else if !isOpenPageAction {
            // Read / exec / operate action without a valid explicit tab_id:
            // follow the current tab (selectedTabId) when it exists; otherwise
            // open the default tab. NEVER fan out to a fresh blank tab and
            // never block on a serial slot to acquire one — just target the
            // tab the agent is already looking at.
            let probe = input.tabId ?? selectedTabId
            if preemptedTabIds.remove(probe) != nil {
                return .error("Browser tab \(probe) was reclaimed under memory pressure (another session needed the slot). Please retry — the page URL has been saved and will be restored.")
            }
            targetId = tabs.contains(where: { $0.id == selectedTabId })
                ? selectedTabId
                : ensureDefaultTab()
        } else {
            // Before creating a default tab, surface a one-shot preempt
            // notice if the requested-or-default id was just reclaimed.
            // The agent's retry of this same action will sail through.
            let probe = input.tabId ?? 0
            if preemptedTabIds.remove(probe) != nil {
                return .error("Browser tab \(probe) was reclaimed under memory pressure (another session needed the slot). Please retry — the page URL has been saved and will be restored.")
            }
            // Implicit-tab content action: per spec [T-browser-implicit-tab-inuse-until-load-ios]
            // NEVER dispatch onto a tab that's still inUse (action in flight OR
            // within the 15s post-action grace). Otherwise back-to-back
            // navigates land on tab 0 and stomp each other's URLs. Try:
            //   1. Pick any existing tab whose `inUse=false` (prefers reuse).
            //   2. Otherwise, if capacity remains, fan out to a fresh tab.
            //   3. Otherwise, fall back to ensureDefaultTab() — at full
            //      capacity with everything busy, the agent will hit the
            //      serial-slot wait below or return a busy hint.
            if singleTab {
                // CLI single-tab mode: never fan out. Stick to the current /
                // most-recently navigated tab so a serial navigate → read
                // sequence reads the page it just loaded. ensureDefaultTab()
                // already returns selectedTabId when that tab exists, and
                // creates tab 0 (selecting it) when the pool is empty.
                targetId = tabs.contains(where: { $0.id == selectedTabId })
                    ? selectedTabId
                    : ensureDefaultTab()
            } else if !explicitTabId {
                if let freeTab = tabs.first(where: { !$0.inUse }) {
                    targetId = freeTab.id
                    // [T-browser-tab-reuse-silent-switch] We're about to REUSE an
                    // idle tab that already holds a page (not a blank fresh tab)
                    // for a new navigate. Its id stays the same but its content is
                    // about to change out from under any earlier task that still
                    // thinks "tab N == the old site". Capture the old URL so the
                    // navigate result can tell the model the tab was repurposed.
                    let oldURL = freeTab.manager.currentURL
                    if !oldURL.isEmpty, oldURL != "about:blank",
                       let newURL = input.url, oldURL != newURL {
                        reusedTabSwitch = (tabId: freeTab.id, oldURL: oldURL)
                    }
                } else if tabs.count < Self.maxTabs,
                          BrowserTabPoolRegistry.shared.requestSlot(for: self) {
                    let nextId = (tabs.map(\.id).max() ?? -1) + 1
                    let manager = makeManager()
                    tabs.append(Tab(id: nextId, manager: manager, inUse: true))
                    restoreSavedURL(for: nextId, manager: manager)
                    logger.info("[SerialDiag] implicit-tab action — all existing tabs inUse (action/grace), fanned out to fresh tab \(nextId)")
                    targetId = nextId
                } else {
                    targetId = ensureDefaultTab()
                }
            } else {
                targetId = ensureDefaultTab()
            }
        }
        if preemptedTabIds.remove(targetId) != nil {
            return .error("Browser tab \(targetId) was reclaimed under memory pressure (another session needed the slot). Please retry — the page URL has been saved and will be restored.")
        }
        guard let idx = tabs.firstIndex(where: { $0.id == targetId }) else {
            return .error("No tab with id \(targetId). Use list_tabs to see open tabs.")
        }

        let tabResolvedMs = Int((CFAbsoluteTimeGetCurrent() - poolStart) * 1000)
        logger.info("[PoolTiming] tab_resolved elapsed=\(tabResolvedMs)ms targetId=\(targetId) action=\(input.action.rawValue)")

        // Mark in use, touch activity, and select for UI. inUse stays true
        // through the post-action grace window — `armPostActionGrace` cancels
        // any previous timer for this tab and arms a fresh 15s one. That
        // window is what blocks back-to-back implicit-tab navigates from
        // stomping each other on tab 0 (T-browser-implicit-tab-inuse-until-load-ios).
        tabs[idx].inUse = true
        tabs[idx].graceExpiryTask?.cancel()
        tabs[idx].graceExpiryTask = nil
        tabs[idx].lastActivityDate = Date()
        selectedTabId = targetId
        defer {
            if let i = tabs.firstIndex(where: { $0.id == targetId }) {
                tabs[i].lastActivityDate = Date()
                armPostActionGrace(tabId: targetId)
            }
        }

        // Sync user-agent changes through the pool so all tabs + future tabs update.
        // Agent-driven switches are temporary — don't persist to UserDefaults.
        if input.action == .setUserAgent {
            let profile = input.userAgent ?? .mobileSafari
            setUserAgentProfile(profile, persist: false)
            let vp = profile.viewportSize
            return BrowserActionResult(text: "Switched to \(profile.rawValue) (\(vp.width)x\(vp.height))")
        }

        // Session-scoped viewport override. --reset clears, otherwise set.
        if input.action == .setViewport {
            if input.viewportReset == true {
                resetSessionViewport()
                let vp = resolvedViewportSize()
                return BrowserActionResult(
                    text: "Viewport reset to default (\(vp.width)x\(vp.height))"
                )
            }
            guard let w = input.viewportWidth, let h = input.viewportHeight,
                  w > 0, h > 0 else {
                return .error("set_viewport requires positive viewport_width and viewport_height, or reset=true")
            }
            setSessionViewport(width: w, height: h)
            return BrowserActionResult(
                text: "Viewport set to \(w)x\(h) for this session"
            )
        }

        // [T-browser-use-per-tab-serial-ios] Serialize per tab id. Acquire the
        // tab's serial slot, waiting at most serialWaitTimeout (60s) for any
        // in-flight op on this SAME tab. On timeout, tell the model to open a
        // fresh tab instead of contending. This wait is independent of the
        // per-action browser timeout below (manager.execute), which is unchanged.
        let serialWaitStart = CFAbsoluteTimeGetCurrent()
        guard let releaseSerialSlot = await acquireSerialSlot(tabId: targetId) else {
            let serialWaitMs = Int((CFAbsoluteTimeGetCurrent() - serialWaitStart) * 1000)
            logger.info("[PoolTiming] serial_slot_timeout elapsed=\(serialWaitMs)ms tab=\(targetId) action=\(input.action.rawValue)")
            return .error(String(localized: "Multiple actions are operating on browser tab \(targetId) at the same time and it could not be acquired in time. Open a new tab (action: new_tab, or pass a different tab_id) and retry this action there so it can run in parallel."))
        }
        let serialAcquiredMs = Int((CFAbsoluteTimeGetCurrent() - serialWaitStart) * 1000)
        if serialAcquiredMs > 100 {
            logger.info("[PoolTiming] serial_slot_waited elapsed=\(serialAcquiredMs)ms tab=\(targetId)")
        }
        defer { releaseSerialSlot() }

        // Re-resolve the tab by id — `idx` was computed before the serial-slot
        // await, during which the tabs array may have changed (idle reclaim,
        // close, registry preempt). Indexing the stale `idx` could trap.
        guard let liveIdx = tabs.firstIndex(where: { $0.id == targetId }) else {
            return .error("No tab with id \(targetId). Use list_tabs to see open tabs.")
        }

        // Delegate to the tab's BrowserUseManager.
        // Capture the manager reference before await — the tabs array may mutate
        // (e.g. closeTab / idle reclaim / registry preempt) while we're suspended.
        let manager = tabs[liveIdx].manager
        let managerStart = CFAbsoluteTimeGetCurrent()
        logger.info("[PoolTiming] manager_execute_start elapsed=\(Int((managerStart - poolStart) * 1000))ms tab=\(targetId) action=\(input.action.rawValue)")
        let result: BrowserActionResult
        do {
            // [T-browser-bg-stuck-diag] Hard wall-clock ceiling on a single
            // browser action. Beyond this the tab is considered DEAD, not slow:
            // on-device testing proved a WebContent process whose JS main thread
            // is pinned cannot be recovered by stopLoading / reload / loadBlank
            // (those messages queue behind the wedged thread and never run) — the
            // ONLY recovery is to drop the whole WKWebView and open a fresh tab
            // (a new tab gets its own WebContent process, verified responsive in
            // ~3ms). So on timeout we do exactly that and hand the model the new
            // tab id plus the reason, letting it decide whether to retry there.
            var r = try await withDeadOnTimeout(
                targetId: targetId,
                action: input.action.rawValue,
                url: input.url ?? manager.currentURL
            ) {
                try await manager.execute(action: input)
            }
            let managerMs = Int((CFAbsoluteTimeGetCurrent() - managerStart) * 1000)
            logger.info("[PoolTiming] manager_execute_done elapsed=\(managerMs)ms tab=\(targetId) action=\(input.action.rawValue)")
            // If the registry preempted this tab while we were awaiting,
            // surface the friendly retry hint instead of whatever WebKit
            // produced as the final state. Consume the flag so the agent's
            // retry sails through.
            if preemptedTabIds.remove(targetId) != nil {
                return .error("Browser tab \(targetId) was reclaimed under memory pressure (another session needed the slot). Please retry — the page URL has been saved and will be restored.")
            }
            let pageURL = manager.webView.url?.absoluteString ?? manager.currentURL
            if !pageURL.isEmpty {
                r.pageURL = pageURL
            }
            // Stamp the verified targetId — NOT selectedTabId or anything else
            // the manager might infer from global state — onto both the
            // structured result and the human-readable text. Fan-out paths can
            // mutate selectedTabId mid-flight, so only `targetId` (computed at
            // dispatch time) is guaranteed to identify the tab this action
            // actually ran on. [T-ios-browser-result-tab-id]
            // [T-browser-tab-reuse-silent-switch] If this navigate repurposed an
            // idle tab that had a different page, prepend an explicit heads-up so
            // the model updates its mental model (tab N is now this URL, no longer
            // the old one) rather than operating on stale assumptions.
            if let sw = reusedTabSwitch, sw.tabId == targetId, r.success {
                r.text = "[Note] Reused existing tab \(targetId): it previously showed \(sw.oldURL) and has now been navigated to the new URL. Any earlier content on tab \(targetId) is gone; use tab_id \(targetId) to operate on THIS page.\n" + r.text
            }
            result = Self.stampTabId(r, targetId: targetId)
        } catch is ActionDeadTimeout {
            // [T-browser-bg-stuck-diag] The action blew past actionDeadTimeout.
            // The tab is wedged and unrecoverable in place; drop it and open a
            // fresh one, then tell the model exactly what happened, on which new
            // tab it can continue, and (if known) the URL that hung — so it can
            // decide to retry there, try a different approach, or give up.
            let elapsed = Int(CFAbsoluteTimeGetCurrent() - managerStart)
            let (newId, deadURL) = rebuildDeadTab(oldId: targetId)
            logger.warning("[PoolTiming] action_DEAD elapsed=\(elapsed)s tab=\(targetId) action=\(input.action.rawValue) → rebuilt as tab \(newId)")
            let urlPart = deadURL.isEmpty ? "" : " while on \(deadURL)"
            var msg = "Browser tab \(targetId) became unresponsive: the '\(input.action.rawValue)' action ran for over \(Int(Self.actionDeadTimeout))s without completing\(urlPart) — its web page most likely wedged (an infinite script, a never-completing navigation, or a background-suspended renderer). The tab could not be recovered and was closed. A fresh tab (id \(newId)) has been opened for you. To continue, retry on tab_id \(newId)"
            if !deadURL.isEmpty { msg += " (e.g. navigate it to \(deadURL) again, or take a different approach if that page keeps hanging)" }
            msg += "."
            var r = BrowserActionResult(text: msg, success: false, tabId: newId)
            r.pageURL = deadURL.isEmpty ? nil : deadURL
            return r
        } catch {
            // Same check on the error path — preempt may have torn the
            // WebView out from under the action.
            if preemptedTabIds.remove(targetId) != nil {
                return .error("Browser tab \(targetId) was reclaimed under memory pressure (another session needed the slot). Please retry — the page URL has been saved and will be restored.")
            }
            throw error
        }
        persistURLs()
        let totalMs = Int((CFAbsoluteTimeGetCurrent() - poolStart) * 1000)
        logger.info("[PoolTiming] total elapsed=\(totalMs)ms tab=\(targetId) action=\(input.action.rawValue) success=\(result.success)")
        return result
    }

    /// Hard ceiling for a single browser action. Past this the tab is treated as
    /// DEAD (WebContent wedged / navigation never completing / JS thread pinned),
    /// not merely slow, and is dropped-and-rebuilt. Chosen well above any
    /// legitimate action: navigation's own timeout is 30s and the serial wait is
    /// 60s, so 300s only ever trips on a genuine wedge. [T-browser-bg-stuck-diag]
    #if DEBUG
    /// Test override so device tests can trip the dead-tab path without waiting
    /// the full 300s. Set via debug.browser.setDeadTimeout; nil → the real value.
    static var actionDeadTimeoutOverride: TimeInterval?
    static var actionDeadTimeout: TimeInterval { actionDeadTimeoutOverride ?? 300 }
    #else
    static let actionDeadTimeout: TimeInterval = 300
    #endif

    /// Thrown internally by `withDeadOnTimeout` when the wall clock elapses.
    private struct ActionDeadTimeout: Error {}

    /// One-shot race arbiter: exactly one of {operation completes, timer fires}
    /// resumes the awaiting continuation; the other's `finish` is dropped. The
    /// two racers land on different threads (a Task vs a GCD timer queue), hence
    /// the lock + `@unchecked Sendable`. `finish` may be called before `attach`
    /// (timer wins instantly), so a pending result is buffered until attach.
    private final class RaceBox: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false
        private var cont: CheckedContinuation<BrowserActionResult, Error>?
        private var pending: Result<BrowserActionResult, Error>?
        var onFinish: (() -> Void)?

        func attach(_ c: CheckedContinuation<BrowserActionResult, Error>) {
            lock.lock()
            if let p = pending {                 // result already arrived
                lock.unlock(); c.resume(with: p); return
            }
            cont = c
            lock.unlock()
        }

        func finish(_ result: Result<BrowserActionResult, Error>) {
            lock.lock()
            if done { lock.unlock(); return }
            done = true
            let c = cont; cont = nil
            if c == nil { pending = result }     // finished before attach
            let cleanup = onFinish; onFinish = nil
            lock.unlock()
            cleanup?()
            c?.resume(with: result)
        }
    }

    /// Run `operation` with a wall-clock deadline. If it completes first, return
    /// its result. If the deadline hits first, the underlying WKWebView is
    /// unrecoverable (proven on device: stopLoading/reload/loadBlank all fail to
    /// unwedge a pinned WebContent) — so close the poisoned tab, open a fresh one
    /// (fresh WebContent process), and return an informative result carrying the
    /// NEW tab id and the reason, so the model can decide whether to retry there.
    ///
    /// The timer is a GCD wall-clock source, NOT `Task.sleep`: on-device testing
    /// showed Task timers get stretched several-fold under background CPU
    /// throttling even when the app is kept alive by audio, whereas a GCD timer
    /// on a background queue keeps firing on schedule.
    private func withDeadOnTimeout(
        targetId: Int,
        action: String,
        url: String,
        _ operation: @escaping () async throws -> BrowserActionResult
    ) async throws -> BrowserActionResult {
        let start = CFAbsoluteTimeGetCurrent()
        // Read the MainActor-isolated timeout once, here, so the nonisolated
        // timer task below captures a plain value.
        let deadline = Self.actionDeadTimeout
        logger.info("[DeadTimeout] armed deadline=\(Int(deadline))s tab=\(targetId) action=\(action)")
        // A structured TaskGroup will NOT work here: it implicitly awaits ALL
        // child tasks before returning, and the wedged `operation()` doesn't
        // honour cooperative cancellation (it's parked on a WebKit callback that
        // never fires), so the group would block on it for the full 40s+ anyway
        // — verified on device. Instead race two independent tasks through a
        // one-shot box: whoever resumes first wins, and we DON'T wait for the
        // loser. The wedged operation task is abandoned (it leaks until its
        // WebView is torn down by the tab rebuild, which is exactly what we do).
        let box = RaceBox()
        let opTask = Task {
            do { let r = try await operation(); box.finish(.success(r)) }
            catch { box.finish(.failure(error)) }
        }
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + deadline)
        timer.setEventHandler {
            logger.warning("[DeadTimeout] FIRED tab=\(targetId) action=\(action)")
            box.finish(.failure(ActionDeadTimeout()))
        }
        timer.resume()
        box.onFinish = { timer.cancel(); opTask.cancel() }
        let raced: BrowserActionResult = try await withCheckedThrowingContinuation { cont in
            box.attach(cont)
        }
        return raced
    }

    /// Drop a wedged tab and open a fresh replacement. Returns the new tab id.
    /// Separated so both the timeout path and future callers can reuse it.
    /// Returns (newTabId, deadURL). deadURL is the last-known URL of the wedged
    /// tab, surfaced to the model so it can choose to re-navigate.
    private func rebuildDeadTab(oldId: Int) -> (newId: Int, deadURL: String) {
        // Force-remove the poisoned tab. Its WKWebView is unrecoverable (proven
        // on device), so we simply drop it — removing it from `tabs` frees its
        // slot in the registry, which counts live tabs directly (no separate
        // release call). Stash the last-known URL for the model.
        let deadURL = tabs.first(where: { $0.id == oldId })?.manager.currentURL ?? ""
        tabs.removeAll { $0.id == oldId }
        if selectedTabId == oldId { selectedTabId = tabs.first?.id ?? 0 }

        // Open a fresh tab (its own WebContent process). newTab selects it.
        let r = newTab(url: nil)
        let newId = r.tabId ?? selectedTabId
        logger.warning("[PoolTiming] tab_rebuilt oldTab=\(oldId) newTab=\(newId) deadURL=\(deadURL.prefix(60)) — dead tab dropped, fresh WebContent opened")
        return (newId, deadURL)
    }

    /// Release all tabs (called when isProcessing → false). Keeps WKWebViews alive
    /// but stamps lastActivityDate so the idle timer can reclaim them later.
    func releaseAllTabs() {
        let now = Date()
        for i in tabs.indices {
            tabs[i].graceExpiryTask?.cancel()
            tabs[i].graceExpiryTask = nil
            tabs[i].inUse = false
            tabs[i].lastActivityDate = now
        }
        persistURLs()
        logger.info("Released all tabs (\(self.tabs.count) total)")
    }

    /// Stamp `targetId` onto a tab-contextual result so the Agent can see
    /// exactly which tab it just operated on, with no need to guess for
    /// follow-up reads/scrolls. Sets the structured `tabId` field and appends
    /// a `  tab_id: <N>` line to the human-readable text in the same indent
    /// style as the existing `Title:` / `Viewport:` lines. Idempotent — if
    /// the text already mentions `tab_id:` (e.g. `newTab` already embeds it
    /// in its narrative), skip the append. [T-ios-browser-result-tab-id]
    private static func stampTabId(_ result: BrowserActionResult, targetId: Int) -> BrowserActionResult {
        var r = result
        r.tabId = targetId
        if !r.text.contains("tab_id:") {
            // Append on its own indented line, matching the existing key:value
            // style. Adds a leading newline iff the text isn't empty and
            // doesn't already end with one.
            let needsNewline = !r.text.isEmpty && !r.text.hasSuffix("\n")
            r.text = r.text + (needsNewline ? "\n" : "") + "  tab_id: \(targetId)"
        }
        return r
    }

    /// Arm a 15-second grace window during which `inUse` stays true for `tabId`.
    /// Re-arming cancels any earlier in-flight grace task for the same tab, so
    /// a back-to-back action against this tab simply pushes the release
    /// further out — the agent's follow-up reads/scrolls keep the tab to
    /// themselves while another agent's parallel implicit-tab `navigate` will
    /// see `inUse=true` and fan out to a fresh tab.
    /// [T-browser-implicit-tab-inuse-until-load-ios]
    private func armPostActionGrace(tabId: Int) {
        guard let idx = tabs.firstIndex(where: { $0.id == tabId }) else { return }
        tabs[idx].graceExpiryTask?.cancel()
        let graceNs = UInt64(Self.postActionGraceSeconds * 1_000_000_000)
        tabs[idx].graceExpiryTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: graceNs)
            guard !Task.isCancelled, let self else { return }
            if let i = self.tabs.firstIndex(where: { $0.id == tabId }) {
                self.tabs[i].inUse = false
                self.tabs[i].graceExpiryTask = nil
            }
        }
    }

    /// Touch the activity timestamp for the selected tab (called by UI interactions).
    func touchActivity() {
        if let idx = tabs.firstIndex(where: { $0.id == selectedTabId }) {
            tabs[idx].lastActivityDate = Date()
        }
    }

    // MARK: - Idle Eviction

    private func startIdleTimer() {
        idleTimer = Timer.scheduledTimer(withTimeInterval: Self.idleCheckInterval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.evictIdleTabs()
            }
        }
    }

    /// Remove tabs that have been idle (not in use) for longer than `idleTimeout`.
    /// Saves their URLs so they can be restored on next use.
    private func evictIdleTabs() {
        let now = Date()
        var evicted = 0
        tabs.removeAll { tab in
            guard !tab.inUse else { return false }
            let idle = now.timeIntervalSince(tab.lastActivityDate)
            if idle >= Self.idleTimeout {
                // Save URL for restoration
                let url = tab.manager.currentURL
                if !url.isEmpty {
                    savedURLs[tab.id] = url
                }
                logger.info("Evicting idle tab \(tab.id) (idle \(String(format: "%.0f", idle))s, url=\(url.prefix(60)))")
                evicted += 1
                return true
            }
            return false
        }
        if evicted > 0 {
            // Fix selected tab if it was evicted
            if !tabs.contains(where: { $0.id == selectedTabId }) {
                selectedTabId = tabs.first?.id ?? 0
            }
            persistURLs()
            logger.info("Evicted \(evicted) idle tab(s), \(self.tabs.count) remaining")
        }
    }

    // MARK: - Registry-driven Eviction

    /// Evict a specific tab on behalf of the registry (cross-pool reclaim).
    /// Saves the URL like the idle timer does so it restores on next use.
    /// Caller has already confirmed the tab is non-inUse.
    func evictTabForRegistry(id: Int) {
        guard let idx = tabs.firstIndex(where: { $0.id == id }) else { return }
        let tab = tabs[idx]
        guard !tab.inUse else { return } // safety net
        let url = tab.manager.currentURL
        if !url.isEmpty {
            savedURLs[id] = url
        }
        tabs.remove(at: idx)
        if selectedTabId == id {
            selectedTabId = tabs.first?.id ?? 0
        }
        persistURLs()
        logger.info("Evicted tab \(id) at registry request (url=\(url.prefix(60)))")
    }

    /// Preempt an in-use tab on behalf of the registry. Saves the URL,
    /// stops any ongoing load, and tears the WKWebView down. The agent
    /// driving this tab is currently `await`ing inside execute() — it
    /// will throw or return whatever WebKit produces when its webView is
    /// dropped. The tab id is added to `preemptedTabIds` so the agent's
    /// next call into `execute(action:)` returns a friendly retry hint
    /// instead of silently re-creating the tab.
    func preemptTabForRegistry(id: Int) {
        guard let idx = tabs.firstIndex(where: { $0.id == id }) else { return }
        let tab = tabs[idx]
        let url = tab.manager.currentURL
        if !url.isEmpty {
            savedURLs[id] = url
        }
        tab.manager.stopLoading()
        tabs.remove(at: idx)
        preemptedTabIds.insert(id)
        if selectedTabId == id {
            selectedTabId = tabs.first?.id ?? 0
        }
        persistURLs()
        logger.info("Preempted in-use tab \(id) at registry request (url=\(url.prefix(60)))")
    }

    /// Evict all non-inUse tabs in response to a memory warning. Returns
    /// the number evicted so the registry can log a single summary line.
    func evictAllIdleForMemoryWarning() -> Int {
        var evicted = 0
        tabs.removeAll { tab in
            guard !tab.inUse else { return false }
            let url = tab.manager.currentURL
            if !url.isEmpty {
                savedURLs[tab.id] = url
            }
            evicted += 1
            return true
        }
        if evicted > 0 {
            if !tabs.contains(where: { $0.id == selectedTabId }) {
                selectedTabId = tabs.first?.id ?? 0
            }
            persistURLs()
        }
        return evicted
    }

    // MARK: - Private

    /// Create a BrowserUseManager wired to this pool.
    private func makeManager() -> BrowserUseManager {
        let ua = resolvedUserAgentString()
        let vp = resolvedViewportSize()
        let manager = BrowserUseManager(profile: userAgentProfile, customString: ua,
                                        viewportWidth: vp.width, viewportHeight: vp.height)
        wireManager(manager)
        return manager
    }

    /// Wire a manager's handlers to this pool (window.open, window.close, downloads).
    private func wireManager(_ manager: BrowserUseManager) {
        manager.newWindowHandler = { [weak self] configuration, url in
            self?.openWithConfiguration(configuration, url: url)
        }
        manager.closeHandler = { [weak self] webView in
            self?.closeWebView(webView)
        }
        // Downloads land in this session's /var/minis/workspace/ so the agent
        // can read and operate on them in follow-up turns.
        manager.sessionIdProvider = { [weak self] in self?.sessionId }
        // [BrowserToolDiag] Let the manager name its own tab when its WebContent
        // process dies. Resolved by identity at call time rather than captured:
        // wireManager runs before the Tab is appended, and ids don't move once
        // assigned, so a lookup stays correct across close/reclaim/fan-out.
        manager.tabIdProvider = { [weak self, weak manager] in
            guard let self, let manager else { return nil }
            return self.tabs.first(where: { $0.manager === manager })?.id
        }
    }

    /// The effective UA string for the current profile.
    func resolvedUserAgentString() -> String? {
        if userAgentProfile == .custom {
            return customUserAgentString.isEmpty ? nil : customUserAgentString
        }
        return userAgentProfile.userAgentString
    }

    /// Resolved viewport for new WKWebViews and `desktopViewportScript` width.
    /// Priority: session override > global custom > UA profile default.
    func resolvedViewportSize() -> (width: Int, height: Int) {
        if sessionViewportWidth > 0 && sessionViewportHeight > 0 {
            return (sessionViewportWidth, sessionViewportHeight)
        }
        if customViewportWidth > 0 && customViewportHeight > 0 {
            return (customViewportWidth, customViewportHeight)
        }
        return userAgentProfile.viewportSize
    }

    /// Whether a custom viewport (global or session) is currently active.
    func hasCustomViewport() -> Bool {
        return (sessionViewportWidth > 0 && sessionViewportHeight > 0)
            || (customViewportWidth > 0 && customViewportHeight > 0)
    }

    /// Set the global custom viewport. Persists to UserDefaults and rebuilds
    /// all existing tab WebViews so UI-visible tabs pick up the new size
    /// immediately. Pass width=height=0 to clear.
    func setGlobalViewport(width: Int, height: Int) {
        customViewportWidth = max(0, width)
        customViewportHeight = max(0, height)
        UserDefaults.standard.set(customViewportWidth, forKey: Self.viewportWidthKey)
        UserDefaults.standard.set(customViewportHeight, forKey: Self.viewportHeightKey)
        applyViewportToAllTabs()
    }

    /// Set a session-scoped viewport override. Persisted alongside the
    /// session's tab URL file so reopening the session restores it.
    /// Rebuilds all existing tab WebViews.
    func setSessionViewport(width: Int, height: Int) {
        sessionViewportWidth = max(0, width)
        sessionViewportHeight = max(0, height)
        applyViewportToAllTabs()
        persistURLs()
    }

    /// Clear the session viewport override so tabs fall back to the global
    /// setting. Does not touch UserDefaults.
    func resetSessionViewport() {
        sessionViewportWidth = 0
        sessionViewportHeight = 0
        applyViewportToAllTabs()
        persistURLs()
    }

    /// Re-apply the current resolved viewport to every live tab's manager.
    private func applyViewportToAllTabs() {
        let vp = resolvedViewportSize()
        let ua = resolvedUserAgentString()
        for tab in tabs {
            tab.manager.setViewport(width: vp.width, height: vp.height,
                                    profile: userAgentProfile, customUA: ua)
        }
    }

    /// Switch user-agent profile for all existing tabs and future ones.
    /// - Parameter persist: When `true` (default), saves to UserDefaults as the new default.
    ///   Pass `false` for temporary agent-driven switches that should not outlive the session.
    func setUserAgentProfile(_ profile: UserAgentProfile, persist: Bool = true) {
        userAgentProfile = profile
        if persist {
            UserDefaults.standard.set(profile.rawValue, forKey: Self.uaProfileKey)
            // Always persist the latest custom string so edits made while already
            // on the custom profile survive across launches.
            UserDefaults.standard.set(customUserAgentString, forKey: Self.uaCustomStringKey)
        }
        let ua = resolvedUserAgentString()
        // Resolve the new viewport via the same precedence the rest of the
        // pool uses (session > global > profile default). Without this, a
        // mobile→desktop UA flip kept the old 390×844 frame, so
        // `screenshot()`'s `WKSnapshotConfiguration.rect = (0, 0, 390, 844)`
        // captured only the left half of pages that laid out at desktop
        // width — the user-visible "screenshot is cropped" bug.
        let vp = resolvedViewportSize()
        for tab in tabs {
            tab.manager.setUserAgent(profile: profile, customString: ua)
            tab.manager.setViewport(width: vp.width, height: vp.height,
                                    profile: profile, customUA: ua)
        }
    }

    /// Restore a previously saved URL into a newly created tab.
    private func restoreSavedURL(for tabId: Int, manager: BrowserUseManager) {
        if let url = savedURLs.removeValue(forKey: tabId) {
            logger.info("Restoring saved URL for tab \(tabId): \(url.prefix(60))")
            manager.loadURL(url)
            persistURLs()
        }
    }

    /// Ensure tab 0 exists and return its ID.
    private func ensureDefaultTab() -> Int {
        if tabs.contains(where: { $0.id == 0 }) {
            return 0
        }
        return acquireTab()
    }

    // MARK: - Disk Persistence

    /// On-disk model for saved tab URLs.
    private struct PersistedTabs: Codable {
        var tabURLs: [Int: String]    // tab ID → URL (live + evicted)
        var selectedTabId: Int
        /// Session-scoped viewport override. nil = no override, fall back to global.
        var sessionViewportWidth: Int?
        var sessionViewportHeight: Int?
    }

    private static func storeURL(for sessionId: String) -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("MinisChat/browser_tabs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(sessionId).json")
    }

    /// Collect all known URLs (live tabs + savedURLs) and write to disk.
    private func persistURLs() {
        guard let sessionId else { return }
        var allURLs = savedURLs
        for tab in tabs {
            let url = tab.manager.currentURL
            if !url.isEmpty {
                allURLs[tab.id] = url
            }
        }
        // A session viewport override is worth persisting even when there are
        // no tabs yet, so set_viewport survives an empty-session reopen.
        let hasSessionViewport = sessionViewportWidth > 0 && sessionViewportHeight > 0
        guard !allURLs.isEmpty || hasSessionViewport else {
            // Nothing to persist — remove file if it exists
            let url = Self.storeURL(for: sessionId)
            try? FileManager.default.removeItem(at: url)
            return
        }
        let data = PersistedTabs(
            tabURLs: allURLs,
            selectedTabId: selectedTabId,
            sessionViewportWidth: hasSessionViewport ? sessionViewportWidth : nil,
            sessionViewportHeight: hasSessionViewport ? sessionViewportHeight : nil
        )
        do {
            let encoded = try JSONEncoder().encode(data)
            try encoded.write(to: Self.storeURL(for: sessionId), options: .atomic)
            logger.info("Persisted \(allURLs.count) tab URL(s) for session \(sessionId.prefix(8))")
        } catch {
            logger.error("Failed to persist tab URLs: \(error.localizedDescription)")
        }
    }

    /// Load persisted tab URLs into savedURLs for lazy restoration.
    private func loadPersistedURLs() {
        guard let sessionId else { return }
        let url = Self.storeURL(for: sessionId)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            let data = try Data(contentsOf: url)
            let persisted = try JSONDecoder().decode(PersistedTabs.self, from: data)
            // Merge persisted URLs into savedURLs (don't overwrite already-live tabs)
            let liveIds = Set(tabs.map(\.id))
            for (tabId, tabURL) in persisted.tabURLs where !liveIds.contains(tabId) {
                savedURLs[tabId] = tabURL
            }
            if !tabs.contains(where: { $0.id == persisted.selectedTabId }) {
                selectedTabId = persisted.selectedTabId
            }
            if let w = persisted.sessionViewportWidth, let h = persisted.sessionViewportHeight,
               w > 0, h > 0 {
                sessionViewportWidth = w
                sessionViewportHeight = h
                // Re-apply to any live tabs that predate load (rare: usually
                // sessionId is set before tabs exist, but be safe).
                if !tabs.isEmpty {
                    applyViewportToAllTabs()
                }
            }
            logger.info("Loaded \(persisted.tabURLs.count) persisted tab URL(s) for session \(sessionId.prefix(8))")
        } catch {
            logger.error("Failed to load persisted tab URLs: \(error.localizedDescription)")
        }
    }

    /// Delete persisted tab data for a session (called on session delete).
    static func deletePersistedData(for sessionId: String) {
        let url = storeURL(for: sessionId)
        try? FileManager.default.removeItem(at: url)
    }
}
