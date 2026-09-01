import Foundation
import WebKit
import UIKit
import os.log

private let logger = AppLogger(category: "BrowserUse")

@MainActor
final class BrowserUseManager: NSObject, ObservableObject {

    // MARK: - Published State

    @Published var currentURL: String = ""
    @Published var pageTitle: String = ""
    @Published var isLoading: Bool = false
    /// [T-ios-webview-error-ui] Non-nil when the last navigation failed
    /// (DNS/host/offline/timeout/TLS). Drives a Safari-style error overlay in
    /// BrowserSheetView so an agent-driven navigation failure isn't a blank
    /// page. The error is still thrown to the agent via navigate() as before.
    @Published var loadError: WebLoadError?
    /// Last URL handed to `navigate()`, so the error overlay's Retry and the
    /// WebLoadError copy can reference the exact address that failed.
    private var lastRequestedURL: URL?
    @Published var canGoBack: Bool = false
    @Published var canGoForward: Bool = false
    @Published var lastScreenshot: UIImage?

    // MARK: - Internal

    private(set) var webView: WKWebView
    private var navigationContinuation: CheckedContinuation<Void, Error>?
    @Published private(set) var currentProfile: UserAgentProfile = .mobileSafari

    /// Called when a page requests a new window (target="_blank" / window.open).
    /// The pool creates a new tab using the provided configuration to share the session.
    /// Returns the new WKWebView so the opener retains a reference (window.opener).
    var newWindowHandler: ((_ configuration: WKWebViewConfiguration, _ url: URL?) -> WKWebView?)?

    /// Called when a child window calls window.close() — the pool should close this tab.
    var closeHandler: ((_ webView: WKWebView) -> Void)?

    // MARK: - Downloads

    /// BrowserDownloadCenter entry ids for this tab's in-flight WKDownloads.
    /// [T-browser-download-ux] Download state now lives in the session-scoped
    /// center (drives the chat's floating banner + the agent-facing report),
    /// not in per-tab published state.
    private var downloadCenterIds: [ObjectIdentifier: UUID] = [:]

    /// True while this tab has WKDownloads that have started but not yet
    /// finished/failed. Used by the tool-result path to detect a download
    /// that was triggered by the action it is currently reporting on.
    var hasInflightDownloads: Bool { !inflightDownloads.isEmpty }

    /// Returns the chat session id owning this browser — wired by BrowserTabPool.
    /// Downloads are saved into that session's /var/minis/workspace/ directory.
    var sessionIdProvider: (() -> String?)?

    /// Returns this tab's pool id — wired by BrowserTabPool. Diagnostics only,
    /// so WebContent-process death can be attributed to a tab. [BrowserToolDiag]
    var tabIdProvider: (() -> Int?)?

    /// Destination host URLs of in-flight downloads keyed by download identity.
    private var downloadDestinations: [ObjectIdentifier: URL] = [:]

    /// Keeps in-flight WKDownload objects alive (delegate is weak on WKDownload).
    private var inflightDownloads: [ObjectIdentifier: WKDownload] = [:]

    private static let navigationTimeout: TimeInterval = 30

    /// [T-browser-executejs-unbounded-ios] GH#234. Wall-clock bound on a single
    /// `execute_js`. Deliberately BELOW BrowserTabPool.serialWaitTimeout (60s):
    /// the timeout ladder must be innermost-shortest, so a wedged script releases
    /// the tab's serial slot *before* the next caller's 60s wait expires. With the
    /// old unbounded call the ordering was inverted (action ∞ / slot 60s / CLI
    /// 90s), which is what let a single stuck script stall every later call.
    /// 45s still comfortably clears the slowest legitimate scripts — the in-tree
    /// bounded JS paths use 10s, and navigation itself only allows 30s.
    private static let executeJSTimeout: TimeInterval = 45

    /// [T-browser-executejs-unbounded-ios] Bound on the shared read-action JS
    /// helper (`evaluateAndReturn`: get_page_info and friends). Much shorter
    /// than `executeJSTimeout` because these are fixed, self-contained,
    /// synchronous scripts that take milliseconds on a healthy page — the only
    /// way one runs long is a JS main thread pinned by someone else, and in that
    /// case failing fast with a clear message beats blocking the caller. Matches
    /// the 10s already used by the other bounded eval in this file.
    private static let readActionTimeout: TimeInterval = 10

    /// Shared process pool so all tabs share cookies/sessions (needed for OAuth flows).
    static let sharedProcessPool = WKProcessPool()

    // MARK: - Init

    /// Current effective viewport. Defaults track the UA profile; the pool
    /// rebuilds the webView via `setViewport(...)` when a custom size is set.
    private(set) var currentViewport: (width: Int, height: Int) = (390, 844)

    init(profile: UserAgentProfile = .mobileSafari, customString: String? = nil,
         viewportWidth: Int? = nil, viewportHeight: Int? = nil) {
        self.currentProfile = profile
        let vp = Self.resolveViewport(profile: profile, width: viewportWidth, height: viewportHeight)
        self.currentViewport = vp
        self.webView = Self.makeWebView(profile: profile, customString: customString,
                                        viewportWidth: vp.width, viewportHeight: vp.height)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        registerPrintHandler(on: webView)
    }

    /// Init with a pre-built configuration (used for window.open popups that share the opener's session).
    init(profile: UserAgentProfile, customString: String?, configuration: WKWebViewConfiguration,
         viewportWidth: Int? = nil, viewportHeight: Int? = nil) {
        self.currentProfile = profile
        let vp = Self.resolveViewport(profile: profile, width: viewportWidth, height: viewportHeight)
        self.currentViewport = vp
        let wv = WKWebView(frame: CGRect(x: 0, y: 0, width: vp.width, height: vp.height), configuration: configuration)
        wv.customUserAgent = Self.resolveUA(profile: profile, customString: customString)
        wv.allowsBackForwardNavigationGestures = true
        self.webView = wv
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        registerPrintHandler(on: webView)
    }

    private static func resolveViewport(profile: UserAgentProfile,
                                        width: Int?, height: Int?) -> (width: Int, height: Int) {
        if let w = width, let h = height, w > 0, h > 0 {
            return (w, h)
        }
        return profile.viewportSize
    }

    // MARK: - WebView Factory

    /// Shared scheme handler for `minis://` URLs across all browser tabs
    /// and in-app previews that embed a WKWebView.
    static let sharedMinisSchemeHandler = MinisURLSchemeHandler()

    /// Resolve the effective UA string. For `.custom` with an empty custom string,
    /// fall back to mobile Safari so WebKit never reverts to its default UA.
    private static func resolveUA(profile: UserAgentProfile, customString: String?) -> String {
        if let custom = customString, !custom.isEmpty {
            return custom
        }
        return profile.userAgentString ?? UserAgentProfile.mobileSafari.userAgentString!
    }

    private static func makeWebView(profile: UserAgentProfile,
                                    customString: String? = nil,
                                    viewportWidth: Int? = nil,
                                    viewportHeight: Int? = nil) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.processPool = sharedProcessPool
        config.websiteDataStore = .default()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.setURLSchemeHandler(Self.sharedMinisSchemeHandler, forURLScheme: "minis")

        // Bridge JS window.print() to the native iOS print dialog. Injected on
        // every page (minis:// and external http/https) at document start so a
        // print button works even if it fires before the page finishes loading.
        config.userContentController.addUserScript(printBridgeScript())

        let vp = resolveViewport(profile: profile, width: viewportWidth, height: viewportHeight)

        // Force a fixed CSS viewport width when:
        //   - the profile is desktop (original behavior), OR
        //   - the caller supplied a custom viewport (so pages lay out at the
        //     requested width instead of the container-derived size).
        let hasCustom = (viewportWidth ?? 0) > 0 && (viewportHeight ?? 0) > 0
        let wantsDesktop = (profile == .desktopSafari) || (hasCustom && vp.width >= 1024)
        if profile == .desktopSafari || hasCustom {
            config.userContentController.addUserScript(desktopViewportScript(width: vp.width))
        }
        // preferredContentMode is what WebKit actually uses to decide
        // mobile-vs-desktop layout (UA string + viewport meta-injection alone
        // are not enough — many sites still negotiate by content mode and the
        // WebKit-default device-width media query). Set the default here so
        // the very first navigation honors it; the navigation delegate
        // overrides per-request as well.
        config.defaultWebpagePreferences.preferredContentMode = wantsDesktop ? .desktop : .mobile

        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: vp.width, height: vp.height), configuration: config)
        webView.customUserAgent = resolveUA(profile: profile, customString: customString)
        webView.allowsBackForwardNavigationGestures = true
        return webView
    }

    /// Force a desktop-width viewport by replacing any `<meta name="viewport">` tag
    /// with a fixed-width one. Injected at document start so the page's layout engine
    /// reads the desktop width before first paint.
    private static func desktopViewportScript(width: Int) -> WKUserScript {
        let js = """
        (function() {
            var w = \(width);
            function apply() {
                var metas = document.querySelectorAll('meta[name="viewport"]');
                metas.forEach(function(m) { m.parentNode.removeChild(m); });
                var m = document.createElement('meta');
                m.name = 'viewport';
                m.content = 'width=' + w + ', initial-scale=1.0, user-scalable=yes';
                (document.head || document.documentElement).appendChild(m);
            }
            apply();
            document.addEventListener('DOMContentLoaded', apply);
        })();
        """
        return WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: false)
    }

    // MARK: - Print Support

    nonisolated static let printMessageHandlerName = "minisPrint"

    /// Override `window.print()` to message the native side so we can present the
    /// iOS system print dialog (WKWebView ignores `window.print()` by default).
    /// Shared with `WebViewHolder` (in-chat preview) so both bridges behave identically.
    /// `nonisolated` so non-main-actor callers (WebViewHolder.init) can reuse it.
    nonisolated static func printBridgeScript() -> WKUserScript {
        let js = """
        (function() {
            window.print = function() {
                try {
                    window.webkit.messageHandlers.\(printMessageHandlerName).postMessage(null);
                } catch (e) {}
            };
        })();
        """
        return WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true)
    }

    /// Register (idempotently) the print message handler on a web view's content controller.
    /// Uses a weak proxy so the content controller doesn't retain the manager
    /// (controller → self → webView → configuration → controller would leak).
    private func registerPrintHandler(on webView: WKWebView) {
        let controller = webView.configuration.userContentController
        controller.removeScriptMessageHandler(forName: Self.printMessageHandlerName)
        controller.add(WeakScriptMessageHandler(self), name: Self.printMessageHandlerName)
    }

    /// Present the iOS system print dialog for the current page contents.
    private func presentPrintDialog() {
        let printController = UIPrintInteractionController.shared
        let printInfo = UIPrintInfo.printInfo()
        printInfo.outputType = .general
        printInfo.jobName = pageTitle.isEmpty ? "Minis" : pageTitle
        printController.printInfo = printInfo
        printController.printFormatter = webView.viewPrintFormatter()
        printController.present(animated: true) { _, completed, error in
            if let error = error {
                logger.error("Print dialog failed: \(error.localizedDescription)")
            } else {
                logger.info("Print dialog completed=\(completed)")
            }
        }
    }

    // MARK: - Execute Action

    /// Actions that visually change the page — after these we wait for DOM to settle
    /// and attach a DOM-change summary (plus a snapshot for UI preview).
    private static let visualChangeActions: Set<BrowserAction> = [
        .navigate, .click, .scroll, .scrollAndCollect, .hover, .type
    ]

    func execute(action input: BrowserActionInput) async throws -> BrowserActionResult {
        let mgrStart = CFAbsoluteTimeGetCurrent()
        logger.info("[MgrTiming] enter action=\(input.action.rawValue)")
        // Backup + re-inject cookies (throttled to once per 60s) so login
        // cookies survive WebKit ITP's site-data wipes. Runs before the
        // action so a restored cookie is already in place for a navigate.
        await CookieBackupStore.shared.syncIfNeeded()
        var result: BrowserActionResult
        switch input.action {
        case .navigate:
            result = try await navigate(to: input.url)
        case .screenshot:
            return try await screenshot(fullPage: input.fullPage ?? false) // already produces a screenshot
        case .click:
            result = try await click(selector: input.selector, x: input.coordinateX, y: input.coordinateY)
        case .type:
            result = try await type(selector: input.selector, text: input.text)
        case .getText:
            return try await getText(selector: input.selector)
        case .scroll:
            result = try await scroll(selector: input.selector, direction: input.direction, amount: input.amount)
        case .scrollAndCollect:
            result = try await scrollAndCollect(selector: input.selector, direction: input.direction, amount: input.amount, scrollCount: input.scrollCount, itemSelector: input.itemSelector)
        case .getPageInfo:
            return try await getPageInfo()
        case .executeJS:
            return try await executeJS(script: input.script)
        case .findElements:
            return try await findElements(selector: input.selector)
        case .hover:
            result = try await hover(selector: input.selector)
        case .getReadable:
            return try await getReadable()
        case .setUserAgent:
            return setUserAgent(profile: input.userAgent)
        case .setViewport:
            // Routed by BrowserTabPool.execute — pool mutates all tabs; this
            // path only hits if execute is called directly on a detached manager.
            return .error("set_viewport must be routed through BrowserTabPool")
        case .getBackbone:
            return try await getBackbone(maxDepth: input.maxDepth)
        case .fetch:
            return try await fetch(url: input.url)
        case .newTab, .closeTab, .listTabs:
            return .error("Tab management actions must be routed through BrowserTabPool")
        case .getCookies:
            return await getCookies(keywords: input.keywords, fuzzy: input.fuzzy ?? true)
        case .setCookies:
            return await setCookies(input.cookies)
        case .waitForDomStable:
            return try await waitForDomStable(timeout: input.timeout)
        }

        let actionMs = Int((CFAbsoluteTimeGetCurrent() - mgrStart) * 1000)
        logger.info("[MgrTiming] action_done elapsed=\(actionMs)ms action=\(input.action.rawValue)")

        // After visual-change actions: wait for DOM to settle, append change summary,
        // and take a snapshot for UI preview (not sent to model).
        if result.success, Self.visualChangeActions.contains(input.action) {
            let settleStart = CFAbsoluteTimeGetCurrent()
            result = await waitForDomSettleAndSnapshot(result: result)
            let settleMs = Int((CFAbsoluteTimeGetCurrent() - settleStart) * 1000)
            logger.info("[MgrTiming] dom_settle_snapshot elapsed=\(settleMs)ms action=\(input.action.rawValue)")
        }
        let totalMs = Int((CFAbsoluteTimeGetCurrent() - mgrStart) * 1000)
        logger.info("[MgrTiming] total elapsed=\(totalMs)ms action=\(input.action.rawValue)")
        return result
    }

    /// Wait for DOM mutations to settle after a visual-change action, then append a
    /// concise change summary to the result text and take a snapshot for UI preview.
    /// The DOM summary IS sent to the model (appended to text); the snapshot is NOT
    /// (only imageFilePath is set, no base64Image).
    private func waitForDomSettleAndSnapshot(result: BrowserActionResult) async -> BrowserActionResult {
        let prevURL = webView.url
        let settleStart = CFAbsoluteTimeGetCurrent()
        // 1. Install MutationObserver and collect a DOM change summary
        let domSummary = await collectDomChangeSummary(timeout: 5.0)
        let domMs = Int((CFAbsoluteTimeGetCurrent() - settleStart) * 1000)
        logger.info("[SettleTiming] dom_summary elapsed=\(domMs)ms")

        // 2. Take snapshot for UI preview (DOM is now stable, better screenshot)
        //    Match the frame-resize approach in `screenshot()` so custom
        //    viewports render at their intended size even when the browser
        //    sheet is stretching the WKWebView to fill a different container.
        let snapStart = CFAbsoluteTimeGetCurrent()
        var snapshotPath: String? = nil
        let targetSize = CGSize(width: CGFloat(currentViewport.width),
                                height: CGFloat(currentViewport.height))
        let savedFrame = webView.frame
        let shouldResize = abs(savedFrame.size.width - targetSize.width) > 0.5 ||
                           abs(savedFrame.size.height - targetSize.height) > 0.5
        if shouldResize {
            webView.frame = CGRect(origin: savedFrame.origin, size: targetSize)
            webView.setNeedsLayout()
            webView.layoutIfNeeded()
            try? await Task.sleep(nanoseconds: 120_000_000)
        }
        do {
            let config = WKSnapshotConfiguration()
            config.rect = CGRect(origin: .zero, size: targetSize)
            let image = try await webView.takeSnapshot(configuration: config)
            lastScreenshot = image
            if let jpegData = image.jpegData(compressionQuality: 0.7) {
                let filename = "snapshot_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
                let fileURL = Self.screenshotsDir.appendingPathComponent(filename)
                try jpegData.write(to: fileURL)
                snapshotPath = fileURL.path
            }
        } catch {
            logger.warning("Post-action snapshot failed: \(error.localizedDescription)")
        }
        if shouldResize {
            // Restore to the configured viewport (not necessarily savedFrame,
            // which may itself be a sheet-polluted 620 width) and force a
            // reflow so the page is left laid out at the intended width.
            // (T-ios-browser-sheet-viewport-pollution)
            restoreViewportFrame()
        }

        let snapMs = Int((CFAbsoluteTimeGetCurrent() - snapStart) * 1000)
        logger.info("[SettleTiming] snapshot elapsed=\(snapMs)ms")

        // 3. Append DOM summary to result text
        var text = result.text
        if !domSummary.isEmpty {
            text += "\n" + domSummary
        }

        // 4. Detect URL change (ignore hash-only changes)
        let newURL = webView.url
        if let prev = prevURL, let cur = newURL {
            let prevNoHash = prev.absoluteString.components(separatedBy: "#").first ?? prev.absoluteString
            let curNoHash = cur.absoluteString.components(separatedBy: "#").first ?? cur.absoluteString
            if prevNoHash != curNoHash {
                text += "\n[URL Changed] Page navigated: \(prev.absoluteString) -> \(cur.absoluteString). Take a screenshot to see the current state before continuing."
            }
        }

        return BrowserActionResult(
            text: text,
            success: result.success,
            base64Image: result.base64Image,
            imageFilePath: snapshotPath ?? result.imageFilePath
        )
    }

    /// Observe DOM mutations for up to `timeout` seconds, settling when mutation
    /// count gradient is stable (non-increasing) for 3 consecutive 0.5s intervals.
    /// Returns a concise text summary of what changed: added/removed element counts,
    /// URL changes, and notable new text content.
    private func collectDomChangeSummary(timeout: Double) async -> String {
        let setupJS = """
        window.__postActionDom = {
            mutations: 0,
            addedTags: {},
            removedCount: 0,
            textChanges: 0,
            attrChanges: 0,
            newTexts: [],
            urlBefore: location.href,
            titleBefore: document.title
        };
        var d = window.__postActionDom;
        window.__postActionObserver = new MutationObserver(function(muts) {
            for (var i = 0; i < muts.length; i++) {
                var m = muts[i];
                d.mutations++;
                if (m.type === 'childList') {
                    for (var j = 0; j < m.addedNodes.length; j++) {
                        var n = m.addedNodes[j];
                        if (n.nodeType === 1) {
                            var tag = n.tagName;
                            d.addedTags[tag] = (d.addedTags[tag] || 0) + 1;
                            var txt = (n.innerText || '').trim();
                            if (txt.length > 5 && txt.length < 200 && d.newTexts.length < 5) {
                                d.newTexts.push(txt.substring(0, 100));
                            }
                        }
                    }
                    d.removedCount += m.removedNodes.length;
                } else if (m.type === 'characterData') {
                    d.textChanges++;
                } else if (m.type === 'attributes') {
                    d.attrChanges++;
                }
            }
        });
        window.__postActionObserver.observe(document.documentElement, {
            childList: true, subtree: true, attributes: true, characterData: true
        });
        return 'ok';
        """
        _ = try? await webView.callAsyncJavaScript(setupJS, arguments: [:], contentWorld: .page)

        // Poll until stable: read cumulative mutation count each interval,
        // compute delta (new mutations since last poll). When delta is
        // non-increasing for 3 consecutive intervals, consider it stable.
        let pollNs: UInt64 = 500_000_000
        let stableNeeded = 3
        var lastTotal = 0
        var lastDelta = Int.max
        var stableRuns = 0
        let startTime = CFAbsoluteTimeGetCurrent()

        while true {
            try? await Task.sleep(nanoseconds: pollNs)
            let elapsed = CFAbsoluteTimeGetCurrent() - startTime
            if elapsed >= timeout { break }

            let countResult = try? await webView.callAsyncJavaScript(
                "return window.__postActionDom ? window.__postActionDom.mutations : 0;",
                arguments: [:], contentWorld: .page
            )
            let total: Int
            if let n = countResult as? Int { total = n }
            else if let n = countResult as? Double { total = Int(n) }
            else { break } // page context lost

            let delta = total - lastTotal
            if delta <= lastDelta {
                stableRuns += 1
            } else {
                stableRuns = 0
            }
            lastTotal = total
            lastDelta = delta

            if stableRuns >= stableNeeded { break }
        }

        // Read the summary
        let readJS = """
        if (window.__postActionObserver) {
            window.__postActionObserver.disconnect();
            window.__postActionObserver = null;
        }
        var d = window.__postActionDom || {};
        return JSON.stringify({
            mutations: d.mutations || 0,
            addedTags: d.addedTags || {},
            removedCount: d.removedCount || 0,
            textChanges: d.textChanges || 0,
            attrChanges: d.attrChanges || 0,
            newTexts: d.newTexts || [],
            urlChanged: (location.href !== (d.urlBefore || '')),
            urlNow: location.href,
            titleNow: document.title
        });
        """
        guard let jsonStr = try? await webView.callAsyncJavaScript(readJS, arguments: [:], contentWorld: .page) as? String,
              let jsonData = jsonStr.data(using: .utf8),
              let info = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            return ""
        }

        // Build concise summary
        let mutations = info["mutations"] as? Int ?? 0
        if mutations == 0 { return "DOM: no changes detected" }

        var parts: [String] = []

        // URL change
        if info["urlChanged"] as? Bool == true, let urlNow = info["urlNow"] as? String {
            parts.append("navigated to \(urlNow)")
        }

        // Added elements
        if let tags = info["addedTags"] as? [String: Int], !tags.isEmpty {
            let sorted = tags.sorted { $0.value > $1.value }.prefix(5)
            let tagStr = sorted.map { "\($0.value) \($0.key)" }.joined(separator: ", ")
            parts.append("added: \(tagStr)")
        }

        // Removed
        let removed = info["removedCount"] as? Int ?? 0
        if removed > 0 { parts.append("\(removed) nodes removed") }

        // Attribute/text changes
        let attr = info["attrChanges"] as? Int ?? 0
        let text = info["textChanges"] as? Int ?? 0
        if attr > 0 || text > 0 {
            var changeParts: [String] = []
            if attr > 0 { changeParts.append("\(attr) attr") }
            if text > 0 { changeParts.append("\(text) text") }
            parts.append("\(changeParts.joined(separator: "+")) updates")
        }

        // New text content preview
        if let newTexts = info["newTexts"] as? [String], !newTexts.isEmpty {
            let preview = newTexts.prefix(3).joined(separator: " | ")
            parts.append("new content: \(preview)")
        }

        // Title
        if let title = info["titleNow"] as? String, !title.isEmpty {
            parts.append("title: \(title)")
        }

        return "DOM: \(mutations) mutations — \(parts.joined(separator: "; "))"
    }

    /// Enrich navigate result with viewport metadata (no image data, just info text).
    /// Error thrown when a bounded `evaluateJavaScript` overruns its deadline.
    struct JSEvalTimeout: Error {}

    /// Run `evaluateJavaScript` with a wall-clock deadline.
    ///
    /// `WKWebView.evaluateJavaScript` has NO timeout of its own: if the
    /// WebContent JS main thread is pinned (a busy page, an infinite loop, or a
    /// script blocked on a stalled sub-resource) the call never returns and the
    /// awaiting Task hangs forever. This is a prime suspect for the backgrounded
    /// browser hang — the app itself stays alive (audio keep-alive), so nothing
    /// external unblocks it. Racing the eval against `Task.sleep` bounds it;
    /// whichever finishes first wins. [T-browser-bg-stuck-diag]
    ///
    /// The timer is a `DispatchSourceTimer`, NOT `Task.sleep`. This matters and
    /// was proven on device: with `Task.sleep` a 10s bound fired at ~42s while
    /// backgrounded, because the Swift-concurrency scheduler is CPU-throttled in
    /// the background (the app is kept alive by silent audio, so it is not
    /// suspended — but it IS throttled, which stretches Task timers). A GCD timer
    /// on a background queue is wall-clock and keeps firing on time regardless.
    private func evaluateJavaScriptBounded(_ js: String, timeout: TimeInterval = 10) async throws -> Any? {
        // Bridge WKWebView's completion-handler API to a continuation, and race
        // it against a wall-clock GCD timer. The first to resume the continuation
        // wins; the loser's resume is dropped via the `done` flag (a continuation
        // must resume exactly once).
        let box = ContinuationBox()
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + timeout)
        timer.setEventHandler { box.finish(.failure(JSEvalTimeout())) }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Any?, Error>) in
            box.cont = cont
            box.onFinish = { timer.cancel() }
            timer.resume()
            self.webView.evaluateJavaScript(js) { value, error in
                if let error { box.finish(.failure(error)) }
                else { box.finish(.success(value)) }
            }
        }
    }

    /// `callAsyncJavaScript` counterpart of `evaluateJavaScriptBounded`.
    ///
    /// [T-browser-executejs-unbounded-ios] Same wall-clock-deadline construction,
    /// same rationale, same GCD-timer-not-Task.sleep reasoning as the sibling
    /// above — but wrapping `callAsyncJavaScript`, which `execute_js` needs for
    /// its `await` / top-level-`return` semantics and its automatic promise
    /// resolution. The promise resolution is precisely the extra hang mode:
    /// `evaluateJavaScript` returns as soon as the expression yields (a pending
    /// promise resolves to an opaque object), whereas `callAsyncJavaScript`
    /// keeps waiting until the promise settles, which it may never do.
    ///
    /// Note the timeout only unblocks OUR await — WebKit keeps running the
    /// script. That is acceptable and matches `evaluateJavaScriptBounded`: it
    /// frees the serial slot so unrelated actions proceed, and a genuinely
    /// wedged WebContent is still caught by the pool's dead-tab ceiling, which
    /// replaces the whole WKWebView.
    private func callAsyncJavaScriptBounded(_ js: String, timeout: TimeInterval) async throws -> Any? {
        let box = ContinuationBox()
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + timeout)
        timer.setEventHandler { box.finish(.failure(JSEvalTimeout())) }
        // The completion-handler overload of `callAsyncJavaScript` requires an
        // explicit `completionHandler:` label and still collides with the async
        // one during overload resolution, so drive the async overload from a
        // detached task and funnel both outcomes through the same one-shot box.
        // On timeout this task is abandoned (WebKit keeps running the script) —
        // identical to how `withDeadOnTimeout` abandons its wedged operation,
        // and for the same reason: a call parked on a WebKit callback that never
        // fires cannot honour cooperative cancellation.
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Any?, Error>) in
            box.cont = cont
            let evalTask = Task { @MainActor in
                do {
                    let value = try await self.webView.callAsyncJavaScript(js, arguments: [:], contentWorld: .page)
                    box.finish(.success(value))
                } catch {
                    box.finish(.failure(error))
                }
            }
            box.onFinish = { timer.cancel(); evalTask.cancel() }
            timer.resume()
        }
    }

    /// One-shot guard so exactly one of {eval-completion, timeout} resumes the
    /// continuation. `@unchecked Sendable` + a lock because the two racers land
    /// on different threads (WebKit callback thread vs the detached timer).
    private final class ContinuationBox: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false
        var cont: CheckedContinuation<Any?, Error>?
        /// Cleanup run once, on the winning side (used to cancel the GCD timer
        /// when the eval completes, or drop nothing when the timer itself fires).
        var onFinish: (() -> Void)?
        func finish(_ result: Result<Any?, Error>) {
            lock.lock()
            if done { lock.unlock(); return }
            done = true
            let c = cont; cont = nil
            let cleanup = onFinish; onFinish = nil
            lock.unlock()
            cleanup?()
            switch result {
            case .success(let v): c?.resume(returning: v)
            case .failure(let e): c?.resume(throwing: e)
            }
        }
    }

    private func navigationMetadata() async -> String {
        let vp = currentViewport
        let url = webView.url?.absoluteString ?? currentURL
        let title = webView.title ?? pageTitle

        // [T-browser-bg-stuck-diag] Instrument + bound this JS eval. It runs at
        // the tail of EVERY navigate, and an un-timed evaluateJavaScript here is
        // the concrete point where a navigate whose page pins the JS thread hangs
        // forever (the app is alive, so nothing else rescues it).
        let metaStart = CFAbsoluteTimeGetCurrent()
        logger.info("[NavMeta] evalJS_start url=\(url.prefix(80))")
        var scrollX = 0, scrollY = 0, pageW = 0, pageH = 0
        do {
            if let js = try await evaluateJavaScriptBounded(
                "JSON.stringify({sx:window.scrollX,sy:window.scrollY,pw:document.documentElement.scrollWidth,ph:document.documentElement.scrollHeight})",
                timeout: 10
            ) as? String,
               let data = js.data(using: .utf8),
               let info = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                scrollX = info["sx"] as? Int ?? 0
                scrollY = info["sy"] as? Int ?? 0
                pageW = info["pw"] as? Int ?? 0
                pageH = info["ph"] as? Int ?? 0
            }
            let ms = Int((CFAbsoluteTimeGetCurrent() - metaStart) * 1000)
            logger.info("[NavMeta] evalJS_done elapsed=\(ms)ms url=\(url.prefix(80))")
        } catch is JSEvalTimeout {
            let ms = Int((CFAbsoluteTimeGetCurrent() - metaStart) * 1000)
            logger.warning("[NavMeta] evalJS_TIMEOUT elapsed=\(ms)ms url=\(url.prefix(80)) — WebContent JS thread pinned; returning metadata without scroll/size. This is the backgrounded-hang chokepoint.")
        } catch {
            logger.warning("[NavMeta] evalJS_error \(error.localizedDescription) url=\(url.prefix(80))")
        }

        var lines: [String] = []
        lines.append("Navigated to \(url)")
        if !title.isEmpty { lines.append("  Title: \(title)") }
        lines.append("  Viewport: \(vp.width)x\(vp.height)")
        if pageW > 0 || pageH > 0 {
            lines.append("  Page size: \(pageW)x\(pageH)")
        }
        lines.append("  Scroll position: (\(scrollX), \(scrollY))")
        return lines.joined(separator: "\n")
    }

    // MARK: - Navigate

    private func navigate(to urlString: String?) async throws -> BrowserActionResult {
        let navStart = CFAbsoluteTimeGetCurrent()

        guard let urlString, !urlString.isEmpty else {
            return .error("Missing 'url' parameter")
        }

        // Support bare domains like "google.com" → "https://google.com"
        var normalized = urlString
        if !normalized.contains("://") {
            normalized = "https://\(normalized)"
        }

        guard let url = URL(string: normalized) else {
            return .error("Invalid URL: \(urlString)")
        }

        // Only allow HTTP(S) and minis:// schemes for direct navigation
        let scheme = url.scheme?.lowercased() ?? ""
        if !["http", "https", "minis"].contains(scheme) {
            return .error("Cannot navigate to \(scheme):// URLs. Only http://, https://, and minis:// are supported.")
        }

        logger.info("[NavTiming] load_start url=\(url.absoluteString.prefix(100))")

        // Record the visit so this domain's cookie backup stays alive (keeps
        // it out of the 30-day retention sweep in CookieBackupStore).
        if scheme == "http" || scheme == "https" {
            CookieBackupStore.shared.noteVisit(url: url)
        }

        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Self.navigationTimeout)
        lastRequestedURL = url          // [T-ios-webview-error-ui]
        loadError = nil                 // clear any prior failure overlay
        webView.load(request)
        isLoading = true

        // Wait for navigation to complete
        // [T-browser-navigate-timeout-reports-success-ios] GH#234. Track whether
        // we left the wait via the delegate or via the timeout, so the RESULT can
        // say so. Previously the timeout resumed and navigate returned a normal
        // BrowserActionResult (success defaults to true), so a frozen renderer
        // reported `ok: true` while the DOM never updated — the caller then ran
        // execute_js against the OLD page with no signal anything was wrong. The
        // only trace was a log line the CLI user never sees.
        var timedOut = false

        // [T-browser-navigate-timeout-gcd-ios] GCD wall-clock timer, not
        // Task.sleep. Same reason documented on evaluateJavaScriptBounded: a
        // Task timer is stretched several-fold by background CPU throttling
        // (a 10s bound measured firing at ~42s on device), which would let this
        // 30s nav bound overrun the pool's 60s serial wait and even the CLI's
        // 90s ceiling — reintroducing the pile-up this fix exists to prevent.
        let navTimer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        navTimer.schedule(deadline: .now() + Self.navigationTimeout)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.navigationContinuation = continuation

            navTimer.setEventHandler {
                Task { @MainActor in
                    if let cont = self.navigationContinuation {
                        self.navigationContinuation = nil
                        self.isLoading = false
                        timedOut = true
                        cont.resume()  // Don't fail on timeout, page may be partially loaded
                        // [T-browser-bg-stuck-diag] Distinguish the two ways navigate
                        // leaves the wait: this WARN = timed out (didFinish/didFail
                        // never came — network stall or the load never completed);
                        // absence of it = the delegate fired normally.
                        logger.warning("[NavTiming] navigate_TIMEOUT after \(Int(Self.navigationTimeout))s — no didFinish/didFail; url=\(url.absoluteString.prefix(80))")
                    }
                }
            }
            navTimer.resume()
        }
        navTimer.cancel()

        let navDoneMs = Int((CFAbsoluteTimeGetCurrent() - navStart) * 1000)
        logger.info("[NavTiming] wait_returned elapsed=\(navDoneMs)ms url=\(url.absoluteString.prefix(100))")

        currentURL = webView.url?.absoluteString ?? normalized
        pageTitle = webView.title ?? ""
        isLoading = false

        let meta = await navigationMetadata()
        let totalMs = Int((CFAbsoluteTimeGetCurrent() - navStart) * 1000)
        logger.info("[NavTiming] total elapsed=\(totalMs)ms url=\(url.absoluteString.prefix(100))")
        // [T-browser-navigate-timeout-reports-success-ios] Report the timeout to
        // the CALLER, not just the log. The page may still be partially usable,
        // so this stays a non-error result carrying the metadata — but it now
        // says the load did not complete, so a script can decide whether to
        // retry or re-read rather than silently operating on a stale DOM.
        if timedOut {
            return BrowserActionResult(text: "Navigation did not complete within \(Int(Self.navigationTimeout))s — the page may be partially loaded or the renderer may be stalled. Content below reflects the current (possibly stale) state.\n\n\(meta)")
        }
        return BrowserActionResult(text: meta)
    }

    // MARK: - Screenshot

    /// Directory for saving browser screenshots.
    private static let screenshotsDir: URL = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("browser_screenshots", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    /// Max captured page height in CSS pixels — guards against runaway memory
    /// allocation for infinite-scroll or pathologically tall pages. 32768 px ×
    /// 1280 px wide × 4 bytes (ARGB) ≈ 160 MB raw bitmap; JPEG-80 keeps the
    /// output file much smaller, but the in-memory snapshot is the limit.
    private static let fullPageMaxHeight: CGFloat = 32_768

    private func screenshot(fullPage: Bool = false) async throws -> BrowserActionResult {
        let ssStart = CFAbsoluteTimeGetCurrent()
        logger.info("[ScreenshotTiming] enter fullPage=\(fullPage)")
        // If the webView's current bounds don't match the intended viewport
        // (e.g. the browser sheet is open and the WKWebView was stretched to
        // fill the sheet frame), the page's visual viewport is whatever the
        // container decided — so `takeSnapshot` only captures that cropped
        // region. Re-apply the configured viewport to the frame for the
        // duration of the snapshot so the snapshot covers the full intended
        // layout area, then restore.
        let viewportSize = CGSize(width: CGFloat(currentViewport.width),
                                  height: CGFloat(currentViewport.height))
        let savedFrame = webView.frame

        // Resolve target capture height: viewport height (default) or
        // scrollHeight (full_page). Width is always the viewport width.
        var originalPageHeight: CGFloat = 0
        var truncated = false
        var captureHeight = viewportSize.height
        if fullPage {
            if let h = try? await webView.evaluateJavaScript(
                "Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)"
            ) as? NSNumber, h.doubleValue > 0 {
                originalPageHeight = CGFloat(h.doubleValue)
                captureHeight = max(viewportSize.height, originalPageHeight)
                if captureHeight > Self.fullPageMaxHeight {
                    captureHeight = Self.fullPageMaxHeight
                    truncated = true
                }
            } else {
                logger.warning("full_page screenshot: scrollHeight unavailable; falling back to viewport height")
            }
        }

        let targetSize = CGSize(width: viewportSize.width, height: captureHeight)
        let shouldResize = abs(savedFrame.size.width - targetSize.width) > 0.5 ||
                           abs(savedFrame.size.height - targetSize.height) > 0.5
        if shouldResize {
            webView.frame = CGRect(origin: savedFrame.origin, size: targetSize)
            webView.setNeedsLayout()
            webView.layoutIfNeeded()
            // Let the page re-flow at the new visual viewport before snapshotting.
            try? await Task.sleep(nanoseconds: 120_000_000) // 120ms
        }
        defer {
            if shouldResize {
                // Restore to the configured viewport + force reflow rather
                // than blindly reverting to savedFrame (which may be a
                // sheet-polluted width). (T-ios-browser-sheet-viewport-pollution)
                restoreViewportFrame()
            }
        }

        // For full_page, force any `loading="lazy"` images to load eagerly and
        // wait two RAFs so IntersectionObserver-driven decoders kick in before
        // the snapshot. Best-effort; ignore failures.
        if fullPage {
            let lazyEager = """
            (async () => {
              const imgs = document.querySelectorAll('img[loading="lazy"]');
              imgs.forEach(i => { i.loading = 'eager'; });
              await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
              return imgs.length;
            })()
            """
            _ = try? await webView.evaluateJavaScript(lazyEager)
            // Extra settle window for image decode after eager flip.
            try? await Task.sleep(nanoseconds: 250_000_000) // 250ms
        }

        let config = WKSnapshotConfiguration()
        config.rect = CGRect(origin: .zero, size: targetSize)
        let takeSnapStart = CFAbsoluteTimeGetCurrent()
        let image = try await webView.takeSnapshot(configuration: config)
        let takeSnapMs = Int((CFAbsoluteTimeGetCurrent() - takeSnapStart) * 1000)
        logger.info("[ScreenshotTiming] takeSnapshot elapsed=\(takeSnapMs)ms")
        lastScreenshot = image

        guard let jpegData = image.jpegData(compressionQuality: 0.8) else {
            return .error("Failed to encode screenshot as JPEG")
        }

        // Save to disk
        let filename = "screenshot_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
        let fileURL = Self.screenshotsDir.appendingPathComponent(filename)
        try jpegData.write(to: fileURL)

        let base64 = jpegData.base64EncodedString()
        let size = image.size
        let totalSsMs = Int((CFAbsoluteTimeGetCurrent() - ssStart) * 1000)
        logger.info("[ScreenshotTiming] total elapsed=\(totalSsMs)ms \(Int(size.width))x\(Int(size.height)) \(jpegData.count) bytes fullPage=\(fullPage) truncated=\(truncated)")

        let meta = await viewportMetadata(
            imageSize: size,
            fileSize: jpegData.count,
            fullPage: fullPage,
            truncated: truncated,
            originalHeight: Int(originalPageHeight)
        )

        return BrowserActionResult(
            text: meta,
            base64Image: base64,
            imageFilePath: fileURL.path
        )
    }

    /// Collect viewport and page metadata for screenshot results.
    private func viewportMetadata(
        imageSize: CGSize,
        fileSize: Int,
        fullPage: Bool = false,
        truncated: Bool = false,
        originalHeight: Int = 0
    ) async -> String {
        let vp = currentViewport
        let url = webView.url?.absoluteString ?? currentURL
        let title = webView.title ?? pageTitle

        // Get scroll position and page dimensions via JS
        var scrollX = 0, scrollY = 0, pageW = 0, pageH = 0
        if let js = try? await webView.evaluateJavaScript(
            "JSON.stringify({sx:window.scrollX,sy:window.scrollY,pw:document.documentElement.scrollWidth,ph:document.documentElement.scrollHeight})"
        ) as? String,
           let data = js.data(using: .utf8),
           let info = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            scrollX = info["sx"] as? Int ?? 0
            scrollY = info["sy"] as? Int ?? 0
            pageW = info["pw"] as? Int ?? 0
            pageH = info["ph"] as? Int ?? 0
        }

        var lines: [String] = []
        lines.append("Screenshot captured")
        lines.append("  URL: \(url)")
        if !title.isEmpty { lines.append("  Title: \(title)") }
        lines.append("  Image: \(Int(imageSize.width))x\(Int(imageSize.height)) (\(fileSize / 1024)KB)")
        lines.append("  Viewport: \(vp.width)x\(vp.height)")
        if pageW > 0 || pageH > 0 {
            lines.append("  Page size: \(pageW)x\(pageH)")
        }
        lines.append("  Scroll position: (\(scrollX), \(scrollY))")
        if fullPage {
            lines.append("  Full page: true")
            if truncated && originalHeight > 0 {
                lines.append("  Truncated: true (original height \(originalHeight)px capped at \(Int(Self.fullPageMaxHeight))px)")
            }
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - Click

    private func click(selector: String?, x: Int?, y: Int?) async throws -> BrowserActionResult {
        let js: String
        if let selector {
            js = BrowserUseJS.click(selector: selector)
        } else if let x, let y {
            js = BrowserUseJS.clickCoordinate(x: x, y: y)
        } else {
            return .error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
        }
        return try await evaluateAndReturn(js)
    }

    // MARK: - Type

    private func type(selector: String?, text: String?) async throws -> BrowserActionResult {
        guard let selector else { return .error("type requires 'selector'") }
        guard let text else { return .error("type requires 'text'") }
        let result = try await evaluateAndReturn(BrowserUseJS.type(selector: selector, text: text))
        // [T-ios-webview-phantom-keyboard] The type just focused a web input,
        // raising a remote keyboard on this offscreen webView. Release it so it
        // doesn't leave a window-wide phantom keyboard placeholder that SwiftUI's
        // avoidance reserves (black gap below the composer / home FAB on all
        // screens until the user toggles a real keyboard).
        releaseWebViewKeyboard()
        return result
    }

    // MARK: - Get Text

    private func getText(selector: String?) async throws -> BrowserActionResult {
        let js = BrowserUseJS.getText(selector: selector)
        do {
            let raw = try await webView.evaluateJavaScript(js)
            if let str = raw as? String,
               let data = str.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let debug = json["debug"] as? [String: Any] {
                    print("[getText] ===== DEBUG =====")
                    print("[getText] readyState: \(debug["readyState"] ?? "?")")
                    print("[getText] url: \(debug["url"] ?? "?")")
                    print("[getText] title: \(debug["title"] ?? "?")")
                    print("[getText] innerTextLength: \(debug["innerTextLength"] ?? "?")")
                    print("[getText] textContentLength: \(debug["textContentLength"] ?? "?")")
                    print("[getText] scrollHeight: \(debug["scrollHeight"] ?? "?"), viewportHeight: \(debug["viewportHeight"] ?? "?")")
                    print("[getText] domElementCount: \(debug["domElementCount"] ?? "?")")
                    print("[getText] visible/hidden sample: \(debug["visibleSample"] ?? "?")/\(debug["hiddenSample"] ?? "?")")
                    print("[getText] innerText preview: \(debug["innerTextPreview"] ?? "")")
                    print("[getText] textContent preview: \(debug["textContentPreview"] ?? "")")
                    print("[getText] ===================")
                }
                if let error = json["error"] as? String {
                    return .error(error)
                }
                return BrowserActionResult(text: Self.formatJSONResult(json))
            }
            return BrowserActionResult(text: String(describing: raw ?? "null"))
        } catch {
            return .error("JavaScript error: \(error.localizedDescription)")
        }
    }

    // MARK: - Get Readable

    private func getReadable() async throws -> BrowserActionResult {
        let js = BrowserUseJS.getReadable()
        do {
            let raw = try await webView.evaluateJavaScript(js)
            if let str = raw as? String,
               let data = str.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let debug = json["debug"] {
                    print("[getReadable] debug: \(debug)")
                }
                if let error = json["error"] as? String {
                    return .error(error)
                }
                return BrowserActionResult(text: Self.formatJSONResult(json))
            }
            return BrowserActionResult(text: String(describing: raw ?? "null"))
        } catch {
            return .error("JavaScript error: \(error.localizedDescription)")
        }
    }

    // MARK: - Scroll

    private func scroll(selector: String?, direction: BrowserActionInput.ScrollDirection?, amount: Int?) async throws -> BrowserActionResult {
        let dir = direction ?? .down
        let px = amount ?? 500
        let js = BrowserUseJS.scroll(direction: dir, amount: px, selector: selector)
        do {
            let raw = try await webView.callAsyncJavaScript(js, arguments: [:], contentWorld: .page)
            if let str = raw as? String,
               let data = str.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let error = json["error"] as? String {
                    return .error(error)
                }
                return BrowserActionResult(text: Self.formatJSONResult(json))
            }
            return BrowserActionResult(text: String(describing: raw ?? "null"))
        } catch {
            return .error("JavaScript error: \(error.localizedDescription)")
        }
    }

    // MARK: - Scroll And Collect

    private func scrollAndCollect(selector: String?, direction: BrowserActionInput.ScrollDirection?, amount: Int?, scrollCount: Int?, itemSelector: String?) async throws -> BrowserActionResult {
        let dir = direction ?? .down
        let px = amount ?? 500
        let count = min(scrollCount ?? 10, 20)
        let js = BrowserUseJS.scrollAndCollect(direction: dir, amount: px, scrollCount: count, selector: selector, itemSelector: itemSelector)
        do {
            let raw = try await webView.callAsyncJavaScript(js, arguments: [:], contentWorld: .page)
            if let str = raw as? String,
               let data = str.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let error = json["error"] as? String {
                    return .error(error)
                }
                return BrowserActionResult(text: Self.formatScrollAndCollectResult(json))
            }
            return BrowserActionResult(text: String(describing: raw ?? "null"))
        } catch {
            return .error("JavaScript error: \(error.localizedDescription)")
        }
    }

    /// Format scroll_and_collect result as numbered items with metadata header.
    private static func formatScrollAndCollectResult(_ json: [String: Any]) -> String {
        let collected = json["itemsCollected"] as? Int ?? 0
        let totalSeen = json["totalItemsSeen"] as? Int ?? 0
        let steps = json["scrollSteps"] as? Int ?? 0
        let scrollPos = json["scrollPosition"] as? Int ?? 0
        let selector = json["itemSelector"] as? String ?? "?"

        var lines: [String] = []
        lines.append("Collected \(collected) unique items (\(totalSeen) total seen) over \(steps) scroll steps")
        lines.append("  Item selector: \(selector)")
        lines.append("  Final scroll position: \(scrollPos)")
        lines.append("")

        if let items = json["items"] as? [String] {
            for (i, item) in items.enumerated() {
                // Compact: replace excessive newlines
                let compact = item.replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
                lines.append("[\(i + 1)] \(compact)")
            }
        }

        return lines.joined(separator: "\n")
    }

    // MARK: - Get Page Info

    private func getPageInfo() async throws -> BrowserActionResult {
        return try await evaluateAndReturn(BrowserUseJS.getPageInfo())
    }

    // MARK: - Get Cookies

    private func getCookies(keywords: [String]?, fuzzy: Bool) async -> BrowserActionResult {
        guard let pageURL = webView.url else {
            return .error("No page is currently loaded")
        }

        // Extract host and root domain
        let host = pageURL.host ?? ""
        let rootDomain: String
        let parts = host.split(separator: ".").map(String.init)
        if parts.count >= 2 {
            rootDomain = parts.suffix(2).joined(separator: ".")
        } else {
            rootDomain = host
        }

        let store = webView.configuration.websiteDataStore.httpCookieStore
        let allCookies = await store.allCookies()

        // Restrict to current host / root domain only
        var filtered = allCookies.filter { cookie in
            let cookieDomain = cookie.domain.hasPrefix(".") ? String(cookie.domain.dropFirst()) : cookie.domain
            return cookieDomain == host || cookieDomain == rootDomain || host.hasSuffix("." + cookieDomain)
        }

        // Apply keywords filter on cookie name
        if let keywords, !keywords.isEmpty {
            let lowerKeywords = keywords.map { $0.lowercased() }
            filtered = filtered.filter { cookie in
                let name = cookie.name.lowercased()
                if fuzzy {
                    return lowerKeywords.allSatisfy { name.contains($0) }
                } else {
                    return lowerKeywords.contains(name)
                }
            }
        }

        if filtered.isEmpty {
            let msg: String
            if let keywords, !keywords.isEmpty {
                msg = "No cookies found for \(host) matching keywords=\"\(keywords.joined(separator: " "))\" (fuzzy=\(fuzzy))."
            } else {
                msg = "No cookies found for \(host)."
            }
            return BrowserActionResult(text: msg, pageURL: pageURL.absoluteString)
        }

        // Sanitize cookie name → shell-safe env var name
        func sanitizeEnvName(_ name: String) -> String {
            let upper = name.uppercased()
            var result = ""
            for ch in upper {
                if ch.isLetter || ch.isNumber || ch == "_" {
                    result.append(ch)
                } else {
                    result.append("_")
                }
            }
            return "COOKIE_\(result)"
        }

        // Shell-escape a value for single-quoted context
        func shellEscape(_ value: String) -> String {
            return value.replacingOccurrences(of: "'", with: "'\\''")
        }

        // Write env file
        let timestamp = Int(Date().timeIntervalSince1970)
        let safeDomain = rootDomain.replacingOccurrences(of: ".", with: "_")
        let envFilename = "env_cookies_\(safeDomain)_\(timestamp).sh"
        let offloadsDir = RootfsManager.shared.dataPath
            .appendingPathComponent("var/minis/offloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: offloadsDir, withIntermediateDirectories: true)
        let envFileURL = offloadsDir.appendingPathComponent(envFilename)
        let envGuestPath = "/var/minis/offloads/\(envFilename)"

        var envLines: [String] = ["#!/bin/sh"]
        var cookieHeaderParts: [String] = []
        var varEntries: [(originalName: String, envName: String, valueLength: Int)] = []

        for cookie in filtered {
            let envName = sanitizeEnvName(cookie.name)
            let escaped = shellEscape(cookie.value)
            envLines.append("export \(envName)='\(escaped)'")
            cookieHeaderParts.append("\(cookie.name)=\(cookie.value)")
            varEntries.append((originalName: cookie.name, envName: envName, valueLength: cookie.value.count))
        }

        let combinedHeader = shellEscape(cookieHeaderParts.joined(separator: "; "))
        envLines.append("export BROWSER_COOKIE_HEADER='\(combinedHeader)'")

        let envContent = envLines.joined(separator: "\n") + "\n"
        try? envContent.write(to: envFileURL, atomically: true, encoding: .utf8)

        // Build response (no raw values)
        var lines: [String] = []
        if let keywords, !keywords.isEmpty {
            lines.append("Retrieved \(filtered.count) cookie(s) for current site \(host) with filter keywords=\"\(keywords.joined(separator: " "))\" (fuzzy=\(fuzzy)).")
        } else {
            lines.append("Retrieved \(filtered.count) cookie(s) for current site \(host).")
        }
        lines.append("")
        lines.append("Stored env file:")
        lines.append("  \(envGuestPath)")
        lines.append("")
        lines.append("To use them in shell commands:")
        lines.append("  . \(envGuestPath) && command")
        lines.append("")
        lines.append("Available cookie variables:")
        for entry in varEntries {
            lines.append("- cookie: \(entry.originalName)")
            lines.append("  env: \(entry.envName)")
            lines.append("  value_length: \(entry.valueLength)")
            lines.append("")
        }
        lines.append("You may define your own alias variables when needed for compatibility, for example:")
        lines.append("  . \(envGuestPath) && MY_VAR=\"$\(varEntries.first?.envName ?? "COOKIE_NAME")\" && command")
        lines.append("")
        lines.append("The raw cookie values are stored only in the env file and are not included in this tool response.")

        return BrowserActionResult(text: lines.joined(separator: "\n"), pageURL: pageURL.absoluteString)
    }

    // MARK: - Set Cookies

    /// Write cookies into the WebView's cookie store via the native
    /// `WKHTTPCookieStore`, so even HttpOnly cookies (which JS cannot set) land.
    /// Symmetric with `getCookies`. Each entry needs `name` + `value`; `domain`
    /// defaults to the current page host, `path` to "/".
    private func setCookies(_ cookies: [[String: Any]]?) async -> BrowserActionResult {
        guard let pageURL = webView.url else {
            return .error("set_cookies: no page is loaded (navigate first)")
        }
        // Distinguish "field omitted" from "field present but unparseable". The
        // latter is the common failure: the tool schema types `cookies` as a
        // string, so a model may send a JSON STRING that failed to re-parse, or
        // the CLI's shell-escaping mangled the array. Guide the caller in both
        // cases instead of returning a confusing empty result.
        guard let cookies else {
            return .error("set_cookies: 'cookies' must be a JSON array of cookie objects "
                + "(e.g. [{\"name\":\"foo\",\"value\":\"bar\"}]). It was missing or could not be "
                + "parsed — if you passed it as a string, ensure it is valid JSON; from the CLI "
                + "prefer --cookies-file <path> to avoid shell escaping.")
        }
        guard !cookies.isEmpty else {
            return .error("set_cookies: 'cookies' array is empty — provide at least one {name, value} object.")
        }

        let defaultDomain = pageURL.host ?? ""
        let store = webView.configuration.websiteDataStore.httpCookieStore

        var setNames: [String] = []
        var domainsTouched = Set<String>()
        var failures: [String] = []

        for raw in cookies {
            // Accept the field-name variants that common cookie exports use
            // (browser extensions like EditThisCookie / Cookie-Editor, and
            // Playwright / Puppeteer storage state). Look up each concept across
            // its known aliases, case-insensitively, so a model can paste cookies
            // verbatim from any of those sources. [set-cookies-formats]
            let str = { (aliases: [String]) in Self.cookieString(raw, aliases) }
            let bool = { (aliases: [String]) in Self.cookieBool(raw, aliases) }

            guard let name = str(["name"]), !name.isEmpty,
                  let value = str(["value"]) else {
                failures.append("(missing name/value)")
                continue
            }
            // Domain defaults to the current page host; path defaults to "/".
            let domain = str(["domain"]).flatMap { $0.isEmpty ? nil : $0 } ?? defaultDomain
            let path = str(["path"]).flatMap { $0.isEmpty ? nil : $0 } ?? "/"

            var properties: [HTTPCookiePropertyKey: Any] = [
                .name: name,
                .value: value,
                .domain: domain,
                .path: path,
            ]
            if bool(["secure"]) == true {
                properties[.secure] = "TRUE"
            }
            // HTTPCookie supports HttpOnly via the private-ish "HttpOnly" key;
            // WKHTTPCookieStore honors it when present. Accept camelCase
            // `httpOnly` (extensions / Playwright) and snake_case `http_only`.
            if bool(["http_only", "httpOnly"]) == true {
                properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE"
            }
            // Expiry in Unix seconds (optional). Aliases: `expires` (our form,
            // Puppeteer) and `expirationDate` (EditThisCookie / Cookie-Editor,
            // often fractional). Absent / <= 0 (e.g. Puppeteer's -1, or 0) → a
            // session cookie (no expiry).
            if let expires = Self.cookieNumber(raw, ["expires", "expirationDate"]), expires > 0 {
                properties[.expires] = Date(timeIntervalSince1970: expires)
            }
            // sameSite is accepted (Lax / Strict / None, any case) so exports
            // that include it aren't rejected, but is NOT yet applied: HTTPCookie
            // has no public SameSite property key honored by WKHTTPCookieStore.
            // TODO [set-cookies-samesite]: wire through once a write path exists.
            _ = str(["sameSite", "same_site"])

            guard let cookie = HTTPCookie(properties: properties) else {
                failures.append(name)
                continue
            }
            await store.setCookie(cookie)
            setNames.append(name)
            domainsTouched.insert(domain)
        }

        if setNames.isEmpty {
            return .error("set_cookies: no cookies were set (every entry was invalid: \(failures.joined(separator: ", ")))")
        }

        let domainLabel = domainsTouched.count == 1
            ? (domainsTouched.first ?? defaultDomain)
            : domainsTouched.sorted().joined(separator: ", ")
        var text = "Set \(setNames.count) cookie(s) for \(domainLabel): \(setNames.joined(separator: ", "))"
        if !failures.isEmpty {
            text += "\nSkipped \(failures.count) invalid entry(ies): \(failures.joined(separator: ", "))"
        }
        return BrowserActionResult(text: text, pageURL: pageURL.absoluteString)
    }

    // MARK: - Cookie field readers (format-tolerant)

    /// Look up `aliases` in `dict`: exact match first, then a case-insensitive
    /// scan, so `httpOnly` / `HttpOnly` / `http_only` all resolve. Returns the
    /// raw value for the first alias that matches.
    private static func cookieValue(_ dict: [String: Any], _ aliases: [String]) -> Any? {
        for key in aliases {
            if let v = dict[key] { return v }
        }
        let lowered = Set(aliases.map { $0.lowercased() })
        for (k, v) in dict where lowered.contains(k.lowercased()) {
            return v
        }
        return nil
    }

    /// String reader across aliases. Numbers are stringified so `value: 123`
    /// (some exports emit numeric values) still works.
    private static func cookieString(_ dict: [String: Any], _ aliases: [String]) -> String? {
        switch cookieValue(dict, aliases) {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }

    /// Bool reader across aliases. Tolerates JSON bool, NSNumber 0/1, and the
    /// stringified "true"/"false"/"TRUE" some extensions emit.
    private static func cookieBool(_ dict: [String: Any], _ aliases: [String]) -> Bool? {
        switch cookieValue(dict, aliases) {
        case let b as Bool: return b
        case let n as NSNumber: return n.boolValue
        case let s as String: return ["true", "1", "yes"].contains(s.lowercased())
        default: return nil
        }
    }

    /// Numeric (seconds) reader across aliases. Accepts JSON number or a numeric
    /// string. Used for expiry, where `expirationDate` is often fractional.
    private static func cookieNumber(_ dict: [String: Any], _ aliases: [String]) -> Double? {
        switch cookieValue(dict, aliases) {
        case let n as NSNumber: return n.doubleValue
        case let s as String: return Double(s)
        default: return nil
        }
    }

    // MARK: - Execute JS

    private func executeJS(script: String?) async throws -> BrowserActionResult {
        guard let script, !script.isEmpty else {
            return .error("execute_js requires 'script'")
        }
        // Use callAsyncJavaScript so `await` and top-level `return` work natively.
        // Unlike evaluateJavaScript, callAsyncJavaScript:
        //   1. Wraps the script body in an async function — `await` is valid
        //   2. Automatically resolves returned Promises before delivering the result
        //   3. Supports top-level `return` without an explicit IIFE wrapper
        //
        // [T-browser-executejs-unbounded-ios] GH#234. Point 2 is exactly why this
        // call MUST be bounded: awaiting the returned promise means a script that
        // returns a never-settling promise (or whose page pins the JS main thread)
        // hangs the completion handler FOREVER. This was the single unbounded JS
        // call site left in this file, and it is the one the CLI drives in tight
        // loops, so it is where the tab actually wedged. A wedged action here holds
        // the pool's per-tab serial slot until the 300s dead ceiling, and every
        // later call — even a trivial get_page_info — then burns the full 60s
        // serial wait. That queueing is the reported "hangs past 60s, recovers
        // after 60-90s".
        do {
            let result = try await callAsyncJavaScriptBounded(
                Self.wrapExecuteJSReturn(script), timeout: Self.executeJSTimeout)
            return BrowserActionResult(text: Self.stringifyJSReturn(result))
        } catch is JSEvalTimeout {
            logger.warning("[ExecJS] timeout after \(Int(Self.executeJSTimeout))s — script pinned the JS thread or returned a non-settling promise")
            return .error("JavaScript timed out after \(Int(Self.executeJSTimeout))s. The script may be blocking the page's main thread or awaiting a promise that never settles. Simplify the script, or split long work into smaller calls.")
        } catch {
            let nsError = error as NSError
            let detail = nsError.userInfo["WKJavaScriptExceptionMessage"] as? String
                ?? error.localizedDescription
            return .error("JavaScript error: \(detail)")
        }
    }

    // MARK: - Wait for DOM Stable

    /// Polls the page every 0.5s using a MutationObserver to count DOM changes,
    /// then waits until the change-rate gradient has been stable (non-increasing)
    /// for 3 consecutive intervals or the timeout is reached.
    private func waitForDomStable(timeout: Int?) async throws -> BrowserActionResult {
        let timeoutSec = Double(timeout ?? 10)
        let pollInterval: UInt64 = 500_000_000 // 0.5s in nanoseconds
        let stableThreshold = 3 // consecutive stable readings needed

        // Inject MutationObserver that accumulates a count we can read each poll
        let setupJS = """
        if (!window.__domStableObserver) {
            window.__domStableCounter = 0;
            window.__domStableObserver = new MutationObserver(function(mutations) {
                for (var i = 0; i < mutations.length; i++) {
                    window.__domStableCounter += mutations[i].addedNodes.length
                        + mutations[i].removedNodes.length
                        + (mutations[i].type === 'attributes' ? 1 : 0)
                        + (mutations[i].type === 'characterData' ? 1 : 0);
                }
            });
            window.__domStableObserver.observe(document.documentElement, {
                childList: true, subtree: true, attributes: true, characterData: true
            });
        }
        window.__domStableCounter = 0;
        return 'ok';
        """
        _ = try await webView.callAsyncJavaScript(setupJS, arguments: [:], contentWorld: .page)

        let startTime = CFAbsoluteTimeGetCurrent()
        var previousCount = Int.max
        var stableRuns = 0
        var samples: [(elapsed: Double, count: Int)] = []

        while true {
            try await Task.sleep(nanoseconds: pollInterval)

            let elapsed = CFAbsoluteTimeGetCurrent() - startTime
            if elapsed >= timeoutSec {
                // Cleanup observer
                _ = try? await webView.callAsyncJavaScript(
                    "if (window.__domStableObserver) { window.__domStableObserver.disconnect(); window.__domStableObserver = null; } return 'ok';",
                    arguments: [:], contentWorld: .page
                )
                let totalChanges = samples.map(\.count).reduce(0, +)
                return BrowserActionResult(
                    text: "DOM wait timed out after \(String(format: "%.1f", timeoutSec))s "
                        + "(still changing, \(totalChanges) total mutations in \(samples.count) samples)"
                )
            }

            // Read and reset the counter (may fail if page navigated away)
            let readJS = "var c = window.__domStableCounter || 0; window.__domStableCounter = 0; return c;"
            let count: Int
            do {
                let result = try await webView.callAsyncJavaScript(readJS, arguments: [:], contentWorld: .page)
                if let n = result as? Int {
                    count = n
                } else if let n = result as? Double {
                    count = Int(n)
                } else {
                    count = 0
                }
            } catch {
                // Page likely navigated — observer is gone, treat as stable
                return BrowserActionResult(
                    text: "DOM stable (page context changed after \(String(format: "%.1f", elapsed))s)"
                )
            }

            samples.append((elapsed: elapsed, count: count))

            // Stable = current interval's mutation count <= previous interval's count
            if count <= previousCount {
                stableRuns += 1
            } else {
                stableRuns = 0
            }
            previousCount = count

            if stableRuns >= stableThreshold && count == 0 {
                // Fully quiet: no mutations for 3 consecutive intervals
                _ = try? await webView.callAsyncJavaScript(
                    "if (window.__domStableObserver) { window.__domStableObserver.disconnect(); window.__domStableObserver = null; } return 'ok';",
                    arguments: [:], contentWorld: .page
                )
                let totalChanges = samples.map(\.count).reduce(0, +)
                return BrowserActionResult(
                    text: "DOM stable after \(String(format: "%.1f", elapsed))s "
                        + "(\(totalChanges) total mutations settled over \(samples.count) samples)"
                )
            }

            if stableRuns >= stableThreshold {
                // Gradient stable: mutations are non-increasing for 3+ intervals
                _ = try? await webView.callAsyncJavaScript(
                    "if (window.__domStableObserver) { window.__domStableObserver.disconnect(); window.__domStableObserver = null; } return 'ok';",
                    arguments: [:], contentWorld: .page
                )
                let totalChanges = samples.map(\.count).reduce(0, +)
                return BrowserActionResult(
                    text: "DOM stable after \(String(format: "%.1f", elapsed))s "
                        + "(\(totalChanges) total mutations, gradient settled at \(count)/interval over \(samples.count) samples)"
                )
            }
        }
    }

    // MARK: - Find Elements

    private func findElements(selector: String?) async throws -> BrowserActionResult {
        guard let selector else { return .error("find_elements requires 'selector'") }
        return try await evaluateAndReturn(BrowserUseJS.findElements(selector: selector))
    }

    // MARK: - Hover

    private func hover(selector: String?) async throws -> BrowserActionResult {
        guard let selector else { return .error("hover requires 'selector'") }
        return try await evaluateAndReturn(BrowserUseJS.hover(selector: selector))
    }

    // MARK: - Get Backbone

    private func getBackbone(maxDepth: Int?) async throws -> BrowserActionResult {
        let depth = maxDepth ?? 5
        let js = BrowserUseJS.getBackbone(maxDepth: depth)
        do {
            let result = try await webView.evaluateJavaScript(js)
            guard let str = result as? String,
                  let data = str.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return .error("Failed to parse backbone result")
            }
            if let error = json["error"] as? String {
                return .error(error)
            }
            return BrowserActionResult(text: Self.formatBackboneResult(json))
        } catch {
            return .error("JavaScript error: \(error.localizedDescription)")
        }
    }

    /// Format backbone result as a compact readable tree.
    private static func formatBackboneResult(_ json: [String: Any]) -> String {
        let nodeCount = json["nodeCount"] as? Int ?? 0
        let depth = json["depth"] as? Int ?? 0
        let merged = json["merged"] as? Int ?? 0
        var lines: [String] = []
        lines.append("Page backbone: \(nodeCount) nodes, depth \(depth), \(merged) merged")

        if let backbone = json["backbone"] as? [[String: Any]] {
            for node in backbone {
                formatBackboneNode(node, indent: 0, lines: &lines)
            }
        }
        return lines.joined(separator: "\n")
    }

    /// [T-ios-browseruse-sanitize-recursion] Same unbounded-recursion exposure
    /// as `sanitizeForJSON`, found while auditing this file: the tree walked
    /// here is parsed straight out of `getBackbone`'s JS return value. The JS
    /// takes a `maxDepth`, but that cap is enforced *by the page's own
    /// JavaScript* — a page that returns a deeper tree than it was asked for
    /// is exactly the input we cannot trust. Cap the native walk too.
    private static func formatBackboneNode(_ node: [String: Any], indent: Int, lines: inout [String]) {
        if indent > maxSanitizeDepth {
            lines.append("\(String(repeating: "  ", count: indent))\(sanitizeTruncationMarker)")
            return
        }
        let pad = String(repeating: "  ", count: indent)
        let tag = node["tag"] as? String ?? "?"
        let sel = node["sel"] as? String ?? ""
        let rect = node["rect"] as? String ?? ""

        // Compact single-line: <TAG> #id .cls [role] | sel | rect
        var header = "\(pad)<\(tag)>"
        if let id = node["id"] as? String, !id.isEmpty { header += " #\(id)" }
        if let cls = node["cls"] as? String, !cls.isEmpty { header += " .\(cls.replacingOccurrences(of: " ", with: "."))" }
        if let role = node["role"] as? String { header += " [\(role)]" }
        header += " | \(sel) | \(rect)"
        lines.append(header)

        // Content details on indented lines, only if present
        if let text = node["text"] as? String, !text.isEmpty {
            lines.append("\(pad)  \"\(text)\"")
        }
        if let href = node["href"] as? String, !href.isEmpty {
            lines.append("\(pad)  → \(href)")
        }
        if let img = node["img"] as? String, !img.isEmpty {
            lines.append("\(pad)  img: \(img)")
        }
        if let input = node["input"] as? String, !input.isEmpty {
            lines.append("\(pad)  input: \(input)")
        }

        if let children = node["children"] as? [[String: Any]] {
            for child in children {
                formatBackboneNode(child, indent: indent + 1, lines: &lines)
            }
        }
    }

    // MARK: - Fetch

    private func fetch(url urlString: String?) async throws -> BrowserActionResult {
        guard let urlString, !urlString.isEmpty else {
            return .error("fetch requires 'url' parameter")
        }

        logger.info("Fetching resource: \(urlString)")

        // Top-level script body — NO manual async-IIFE wrapper.
        // callAsyncJavaScript already wraps the script in an async function,
        // resolves a returned Promise, and supports top-level `return` (see the
        // executeJS notes above). The old `(async function(){…})()` wrapper
        // returned its Promise to a wrapper expression that itself returned
        // nothing, so the call always resolved to undefined and every fetch
        // failed with "Failed to parse fetch result".
        let js = """
        try {
            const resp = await fetch(\(Self.jsStringLiteral(urlString)));
            const buf = await resp.arrayBuffer();
            const bytes = new Uint8Array(buf);
            let binary = '';
            for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
            const b64 = btoa(binary);
            const ct = resp.headers.get('content-type') || '';
            const cd = resp.headers.get('content-disposition') || '';
            return JSON.stringify({
                base64: b64,
                contentType: ct,
                contentDisposition: cd,
                status: resp.status,
                url: resp.url,
                size: bytes.length
            });
        } catch(e) {
            return JSON.stringify({error: e.message});
        }
        """

        let rawResult: Any?
        do {
            rawResult = try await webView.callAsyncJavaScript(js, arguments: [:], contentWorld: .page)
        } catch {
            return .error("JavaScript fetch failed: \(error.localizedDescription)")
        }

        guard let jsonStr = rawResult as? String,
              let jsonData = jsonStr.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            return .error("Failed to parse fetch result")
        }

        if let error = json["error"] as? String {
            return .error("Fetch failed: \(error)")
        }

        guard let base64 = json["base64"] as? String,
              let data = Data(base64Encoded: base64) else {
            return .error("Failed to decode fetched data")
        }

        let contentType = json["contentType"] as? String ?? ""
        let contentDisposition = json["contentDisposition"] as? String ?? ""
        let status = json["status"] as? Int ?? 0
        let finalURL = json["url"] as? String ?? urlString
        let size = json["size"] as? Int ?? data.count

        // Determine filename
        let filename: String
        if let cdFilename = Self.parseContentDispositionFilename(contentDisposition) {
            let timestamp = Int(Date().timeIntervalSince1970)
            filename = "\(timestamp)_\(cdFilename)"
        } else if let urlObj = URL(string: finalURL), !urlObj.lastPathComponent.isEmpty, urlObj.lastPathComponent != "/" {
            let timestamp = Int(Date().timeIntervalSince1970)
            filename = "\(timestamp)_\(urlObj.lastPathComponent)"
        } else {
            let ext = Self.extensionForMimeType(contentType)
            filename = "fetch_\(Int(Date().timeIntervalSince1970)).\(ext)"
        }

        var lines: [String] = []
        lines.append("Fetched \(finalURL)")
        lines.append("  Status: \(status)")
        lines.append("  Content-Type: \(contentType)")
        lines.append("  Size: \(Self.formatBytes(size))")
        lines.append("  Filename: \(filename)")

        return BrowserActionResult(
            text: lines.joined(separator: "\n"),
            fetchedFileData: data,
            fetchedFileName: filename
        )
    }

    /// Escape a Swift string into a JS string literal (with quotes).
    private static func jsStringLiteral(_ s: String) -> String {
        let escaped = s
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
        return "\"\(escaped)\""
    }

    /// Parse filename from Content-Disposition header value.
    private static func parseContentDispositionFilename(_ cd: String) -> String? {
        guard !cd.isEmpty else { return nil }
        // Try filename*= (RFC 5987) first, then filename=
        let patterns = ["filename\\*=(?:UTF-8''|utf-8'')([^;\\s]+)", "filename=\"([^\"]+)\"", "filename=([^;\\s]+)"]
        for pattern in patterns {
            if let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
               let match = regex.firstMatch(in: cd, range: NSRange(cd.startIndex..., in: cd)),
               match.numberOfRanges > 1,
               let range = Range(match.range(at: 1), in: cd) {
                let name = String(cd[range]).removingPercentEncoding ?? String(cd[range])
                if !name.isEmpty { return name }
            }
        }
        return nil
    }

    /// Map common MIME types to file extensions.
    private static func extensionForMimeType(_ mime: String) -> String {
        let lower = mime.lowercased().components(separatedBy: ";").first?.trimmingCharacters(in: .whitespaces) ?? ""
        switch lower {
        case "text/html": return "html"
        case "text/plain": return "txt"
        case "text/css": return "css"
        case "text/csv": return "csv"
        case "application/json": return "json"
        case "application/xml", "text/xml": return "xml"
        case "application/pdf": return "pdf"
        case "image/png": return "png"
        case "image/jpeg": return "jpg"
        case "image/gif": return "gif"
        case "image/webp": return "webp"
        case "image/svg+xml": return "svg"
        case "application/zip": return "zip"
        case "application/gzip": return "gz"
        default: return "bin"
        }
    }

    /// Format byte count as human-readable string.
    private static func formatBytes(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return String(format: "%.1f KB", Double(bytes) / 1024) }
        return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
    }

    // MARK: - Set User Agent

    @discardableResult
    func setUserAgent(profile: UserAgentProfile?, customString: String? = nil) -> BrowserActionResult {
        let newProfile = profile ?? .mobileSafari

        currentProfile = newProfile
        // Preserve the current viewport across UA switches so a user-set
        // custom viewport survives an agent-driven UA flip.
        let vp = currentViewport
        let newView = Self.makeWebView(profile: newProfile, customString: customString,
                                       viewportWidth: vp.width, viewportHeight: vp.height)
        newView.navigationDelegate = self
        newView.uiDelegate = self

        // If there's a current URL, reload in the new webview
        let oldURL = webView.url

        webView = newView

        if let url = oldURL {
            webView.load(URLRequest(url: url))
        }

        return BrowserActionResult(text: "Switched to \(newProfile.rawValue) (\(vp.width)x\(vp.height))")
    }

    // MARK: - Set Viewport

    /// Rebuild the WebView with a new viewport. Called by the pool when the
    /// global or session custom viewport changes.
    func setViewport(width: Int, height: Int,
                     profile: UserAgentProfile, customUA: String?) {
        currentProfile = profile
        currentViewport = (width, height)

        let newView = Self.makeWebView(profile: profile, customString: customUA,
                                       viewportWidth: width, viewportHeight: height)
        newView.navigationDelegate = self
        newView.uiDelegate = self

        let oldURL = webView.url
        webView = newView
        if let url = oldURL {
            webView.load(URLRequest(url: url))
        }
    }

    /// Restore the offscreen webView's frame to the configured viewport and
    /// force the page to re-layout at that width.
    ///
    /// The browser sheet (`BrowserSheetView`) hosts this same offscreen
    /// `webView` and stretches it to fill the sheet (~620pt wide). When the
    /// sheet closes, the frame is left at the sheet's width, so the page's CSS
    /// visual viewport stays reflowed to ~620 even though `currentViewport` is
    /// still 390. A later screenshot resizes the frame back to 390 but the
    /// page was already laid out at 620 and won't reflow on a bare frame
    /// change — producing a 620-wide layout squeezed into a 390 capture rect.
    ///
    /// This pins the frame back to `currentViewport`, lays it out
    /// synchronously, and dispatches a `resize` event so any width-dependent
    /// CSS / JS recomputes against 390. Safe to call repeatedly.
    /// (T-ios-browser-sheet-viewport-pollution)
    @MainActor
    func restoreViewportFrame() {
        // [T-ios-webview-phantom-keyboard] This runs when the browser sheet
        // closes / before a screenshot — i.e. the webView is going back
        // offscreen. Release any focused input's remote keyboard here too, so a
        // focus left by a `click` on a field (not just `type`) can't strand a
        // window-wide phantom keyboard placeholder.
        releaseWebViewKeyboard()
        let target = CGSize(width: CGFloat(currentViewport.width),
                            height: CGFloat(currentViewport.height))
        let current = webView.frame.size
        let needsResize = abs(current.width - target.width) > 0.5 ||
                          abs(current.height - target.height) > 0.5
        guard needsResize else { return }

        webView.frame = CGRect(origin: webView.frame.origin, size: target)
        webView.setNeedsLayout()
        webView.layoutIfNeeded()
        // Force the page's CSS visual viewport to recompute at the restored
        // width — a frame change alone does not reliably reflow an
        // already-laid-out page.
        webView.evaluateJavaScript("window.dispatchEvent(new Event('resize'));", completionHandler: nil)
        logger.info("restoreViewportFrame: \(Int(current.width))x\(Int(current.height)) -> \(currentViewport.width)x\(currentViewport.height)")
    }

    /// [T-ios-webview-phantom-keyboard] Release any keyboard the offscreen
    /// agent webView is holding. When a browser action focuses a web input
    /// (`type`, or a `click` that lands on a field), WebKit raises a REMOTE
    /// keyboard for this webView. Because the webView is parked offscreen (and
    /// stays in the window's view hierarchy for rendering), that remote keyboard
    /// leaves a full-height `UIKeyboardItemContainerView` / assistant-bar
    /// placeholder in the scene — which SwiftUI's keyboard avoidance then
    /// reserves window-wide, compressing EVERY screen's content and leaving a
    /// black gap below the composer / home FAB row. It self-heals only after the
    /// user toggles a real keyboard (which recomputes the avoidance inset to 0).
    /// Blurring the focused element + `endEditing` here dismisses that phantom at
    /// the source so it never leaks to the SwiftUI layer. Safe/idempotent.
    @MainActor
    func releaseWebViewKeyboard() {
        webView.evaluateJavaScript(
            "if (document.activeElement && document.activeElement.blur) { document.activeElement.blur(); }",
            completionHandler: nil)
        webView.endEditing(true)
    }

    // MARK: - User Navigation

    func goBack() {
        guard webView.canGoBack else { return }
        webView.goBack()
    }

    func goForward() {
        guard webView.canGoForward else { return }
        webView.goForward()
    }

    func reload() {
        webView.reload()
    }

    func stopLoading() {
        webView.stopLoading()
        isLoading = false
    }

    func loadURL(_ urlString: String) {
        var normalized = urlString
        if !normalized.contains("://") {
            normalized = "https://\(normalized)"
        }
        guard let url = URL(string: normalized) else { return }
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Self.navigationTimeout)
        lastRequestedURL = url          // [T-ios-webview-error-ui]
        loadError = nil
        webView.load(request)
        isLoading = true
    }

    /// [T-ios-webview-error-ui] Retry the last requested URL after a failure.
    func retryFailedLoad() {
        loadError = nil
        if let url = lastRequestedURL {
            let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Self.navigationTimeout)
            webView.load(request)
            isLoading = true
        } else {
            webView.reload()
        }
    }

    // MARK: - JS Evaluation Helper

    private func evaluateAndReturn(_ js: String) async throws -> BrowserActionResult {
        do {
            // [T-browser-executejs-unbounded-ios] GH#234 follow-up. Bound the
            // shared read-action helper (get_page_info and friends). These run
            // self-contained synchronous scripts with no user code and no
            // promises, so they can only stall when the JS thread is ALREADY
            // pinned by something else — which device testing confirmed: after
            // an execute_js wedge was correctly bounded at 45s and released its
            // serial slot, a following get_page_info still sat 69.6s inside its
            // OWN unbounded eval waiting for the pinned thread. The slot was
            // free (manager_execute_start at 0ms), so this is a separate,
            // narrower gap than the one that caused the reported pile-up.
            // `readActionTimeout` is short: these scripts are milliseconds on a
            // healthy page, so a long wait only ever means a pinned thread.
            let result = try await evaluateJavaScriptBounded(js, timeout: Self.readActionTimeout)
            if let str = result as? String {
                // Check if the JS returned an error object
                if let data = str.data(using: .utf8),
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    if let error = json["error"] as? String {
                        return .error(error)
                    }
                    return BrowserActionResult(text: Self.formatJSONResult(json))
                }
                return BrowserActionResult(text: str)
            }
            return BrowserActionResult(text: String(describing: result ?? "null"))
        } catch is JSEvalTimeout {
            logger.warning("[ReadAction] JS timeout after \(Int(Self.readActionTimeout))s — the page's JS main thread appears pinned")
            return .error("Timed out after \(Int(Self.readActionTimeout))s reading the page: its JavaScript main thread appears to be blocked by a long-running script. Wait for it to finish, reload the page, or open a new tab.")
        } catch {
            // Extract detailed exception message from WKError userInfo.
            // WKError.javaScriptExceptionOccurred only says "A JavaScript
            // exception occurred" in localizedDescription; the actual JS error
            // message is in the "WKJavaScriptExceptionMessage" userInfo key.
            let nsError = error as NSError
            let detail = nsError.userInfo["WKJavaScriptExceptionMessage"] as? String
                ?? error.localizedDescription
            return .error("JavaScript error: \(detail)")
        }
    }

    /// Serialize whatever `callAsyncJavaScript` handed back into a string
    /// that round-trips as JSON whenever the JS side returned JSON-shaped
    /// data.
    ///
    /// Previous behaviour (now deleted) tried to be clever: when the JS
    /// script returned a string that happened to parse as a JSON object, we
    /// re-ran it through `formatJSONResult` and produced `"  key: value\n"`
    /// text; when the JS side returned a real object we used
    /// `String(describing:)` which produced NSDictionary's
    /// `"{\n    key = value;\n}"` plist-ish description. Either way the
    /// caller couldn't `JSON.parse` / `json.loads` the result. That's wrong
    /// for a tool whose whole job is to run user code and return whatever
    /// it produced.
    ///
    /// New rules:
    ///   - `String` → returned verbatim. A script that did
    ///     `return JSON.stringify(obj)` gets its exact string back.
    ///   - JSON-serialisable object/array (`NSDictionary` / `NSArray` /
    ///     `NSNumber` / `NSNull`) → serialised with `JSONSerialization`
    ///     (with `.fragmentsAllowed` so naked numbers/booleans round-trip
    ///     to `"42"` / `"true"` / `"null"` rather than NSNumber's
    ///     `String(describing:)` "1"/"0").
    ///   - Anything else (extremely rare — `callAsyncJavaScript` only
    ///     returns JSON-like types per WebKit's docs) → fall back to
    ///     `String(describing:)` so at least something is printed.
    ///
    /// Note: we no longer special-case `{"error": "..."}` as a tool-level
    /// failure. Users who want to signal a failure from their JS should
    /// `throw` — the surrounding do/catch already maps that to `.error(...)`
    /// via `WKJavaScriptExceptionMessage`. Silently hijacking any `error`
    /// key was a footgun: a script that happened to return
    /// `{error: null, data: ...}` or fetched a response body containing an
    /// `error` field turned into a fake tool failure.
    /// Wraps an `execute_js` script so its return value is converted to plain
    /// JSON-able data *inside the page*, before WebKit serializes it across the
    /// WebContent→UI IPC boundary.
    ///
    /// [T-ios-browseruse-domrect-clone] Six crash reports segfault in
    /// `WebCore::CloneDeserializer::readDOMRect` ← `readTerminal` ←
    /// `deserialize`, reached from
    /// `-[WKWebView _evaluateJavaScript:asAsyncFunction:…]`'s completion — i.e.
    /// while decoding the value a script RETURNED. Only `execute_js` can reach
    /// it: every in-tree script returns a `JSON.stringify` string, so the
    /// offending value is always LLM-authored (`return el.getBoundingClientRect()`
    /// is the obvious way to write it, and the tool schema never said not to).
    ///
    /// The fix has to live in JS. The crash is *inside* the deserializer, so it
    /// happens before any Swift code — `stringifyJSReturn` included — ever sees
    /// the value; nothing on our side can intercept it.
    ///
    /// Measured against a current WebKit (probe, 2026-08-22), a returned
    /// `DOMRect` does not crash but deserializes to **nil**, and
    /// `[DOMRect]`/`{r: DOMRect}` to `[null]`/`{}` — so on every device this
    /// silently drops the data the script was trying to report, while on the
    /// affected versions it segfaults. `.toJSON()` round-trips correctly. That
    /// makes this a data-loss fix first and a crash fix second.
    ///
    /// Rather than enumerate the DOM types with the bug, normalize structurally:
    /// anything exposing `toJSON()` is asked for it, and any remaining non-plain
    /// object is reduced to its own enumerable properties. Plain
    /// objects/arrays/primitives pass through untouched, so well-behaved scripts
    /// are unaffected. Cycles are tracked with a `WeakSet`, depth is capped, and
    /// the whole thing is wrapped in try/catch that falls back to the raw value
    /// — a normalizer that could itself throw would turn a working script into a
    /// tool error, which is a worse failure than the one being fixed.
    static func wrapExecuteJSReturn(_ script: String) -> String {
        // The user script keeps top-level `return`/`await` by staying the body
        // of its own async function, which we then await.
        """
        const __minisNormalize = (function () {
            const MAX_DEPTH = 32;
            function norm(v, depth, seen) {
                if (v === null || v === undefined) return null;
                const t = typeof v;
                if (t === 'number') return isFinite(v) ? v : String(v);
                if (t === 'string' || t === 'boolean') return v;
                if (t === 'bigint') return String(v);
                if (t === 'function' || t === 'symbol') return undefined;
                if (depth >= MAX_DEPTH) return '[max depth]';
                if (seen.has(v)) return '[circular]';
                seen.add(v);
                try {
                    if (Array.isArray(v)) {
                        const out = [];
                        for (let i = 0; i < v.length; i++) out.push(norm(v[i], depth + 1, seen));
                        return out;
                    }
                    // DOMRect/DOMPoint/DOMMatrix and friends all implement
                    // toJSON(); so do Date and URL, whose JSON form is the
                    // useful one anyway.
                    if (typeof v.toJSON === 'function') {
                        return norm(v.toJSON(), depth + 1, seen);
                    }
                    if (v instanceof Error) {
                        return { name: v.name, message: v.message, stack: String(v.stack || '') };
                    }
                    if (typeof Node !== 'undefined' && v instanceof Node) {
                        return { nodeName: v.nodeName, id: v.id || null,
                                 text: String(v.textContent || '').substring(0, 200) };
                    }
                    if (typeof v[Symbol.iterator] === 'function' && !(v instanceof String)) {
                        const out = [];
                        for (const item of v) {
                            out.push(norm(item, depth + 1, seen));
                            if (out.length >= 1000) break;
                        }
                        return out;
                    }
                    // Plain object, or a host object whose own enumerable
                    // properties are the best available description.
                    const out = {};
                    for (const k in v) {
                        const got = norm(v[k], depth + 1, seen);
                        if (got !== undefined) out[k] = got;
                    }
                    return out;
                } finally {
                    seen.delete(v);
                }
            }
            return function (v) {
                try { return norm(v, 0, new WeakSet()); } catch (e) { return v; }
            };
        })();
        const __minisResult = await (async () => {
        \(script)
        })();
        return __minisNormalize(__minisResult);
        """
    }

    static func stringifyJSReturn(_ value: Any?) -> String {
        guard let value else { return "null" }
        if let str = value as? String { return str }
        // `isValidJSONObject` accepts dictionaries/arrays containing NaN or
        // ±Infinity, but `JSONSerialization.data(...)` then raises an
        // NSInvalidArgumentException at write time. That's an Objective-C
        // exception, not a Swift error, so `try?` cannot catch it and the
        // app aborts. Replace any non-finite numbers with NSNull before
        // serialising.
        let sanitized = sanitizeForJSON(value)
        if JSONSerialization.isValidJSONObject(sanitized) {
            if let data = try? JSONSerialization.data(withJSONObject: sanitized, options: []),
               let s = String(data: data, encoding: .utf8) {
                return s
            }
        }
        // `isValidJSONObject` requires the top level to be an array or
        // dictionary, so naked scalars (NSNumber, NSNull, Bool) fall here.
        // Use `.fragmentsAllowed` which accepts any JSON value at the root.
        if let data = try? JSONSerialization.data(
            withJSONObject: sanitized, options: [.fragmentsAllowed]
        ), let s = String(data: data, encoding: .utf8) {
            return s
        }
        return String(describing: value)
    }

    /// [T-ios-browseruse-sanitize-recursion] Maximum nesting depth walked by
    /// `sanitizeForJSON`. The input is a web page's JS return value, i.e.
    /// attacker-influenceable, and the recursion overflowed the stack in the
    /// field (crash DPiWjaIX, ~2900 frames). Real return values nest a handful
    /// of levels; 64 matches the cap `OpenAIProvider.estimateSerializedSize`
    /// uses for the same class of untrusted-nesting problem.
    private static let maxSanitizeDepth = 64

    /// Placeholder substituted for a subtree deeper than `maxSanitizeDepth`.
    /// This value is read by an LLM as a tool result, so it says what happened
    /// rather than silently becoming null.
    private static let sanitizeTruncationMarker = "[truncated: nesting deeper than \(maxSanitizeDepth) levels]"

    /// Recursively replace non-finite Doubles/Floats (NaN, ±Infinity) with
    /// NSNull so the result is safe to hand to `JSONSerialization`. Other
    /// values pass through unchanged.
    ///
    /// [T-ios-browseruse-sanitize-recursion] Bounded by `maxSanitizeDepth`:
    /// past the cap the subtree is replaced with a readable marker instead of
    /// being walked. A stack overflow is a SIGSEGV on the guard page, which no
    /// `catch` can intercept, so the limit has to live inside the recursion.
    private static func sanitizeForJSON(_ value: Any, depth: Int = 0) -> Any {
        if depth > maxSanitizeDepth {
            logger.warning("[JS] sanitizeForJSON hit the \(maxSanitizeDepth)-level depth cap — truncating the deeper subtree")
            return sanitizeTruncationMarker
        }
        if let dict = value as? [String: Any] {
            var out: [String: Any] = [:]
            out.reserveCapacity(dict.count)
            for (k, v) in dict { out[k] = sanitizeForJSON(v, depth: depth + 1) }
            return out
        }
        if let array = value as? [Any] {
            return array.map { sanitizeForJSON($0, depth: depth + 1) }
        }
        if let number = value as? NSNumber {
            // CFNumber represents Bool as a distinct type; leave booleans
            // alone. For floats, swap non-finite values for null.
            let typeID = CFNumberGetType(number as CFNumber)
            if typeID == .float32Type || typeID == .float64Type || typeID == .cgFloatType {
                let d = number.doubleValue
                if !d.isFinite { return NSNull() }
            }
            return number
        }
        return value
    }

    /// Format a JSON result dict from browser JS into human-readable lines.
    private static func formatJSONResult(_ json: [String: Any]) -> String {
        var lines: [String] = []

        // Click results
        if json["clicked"] as? Bool == true {
            let tag = json["tag"] as? String ?? "?"
            lines.append("Clicked <\(tag)>")
            if let x = json["x"], let y = json["y"] {
                lines.append("  Position: (\(x), \(y))")
            }
            if let text = json["text"] as? String, !text.isEmpty {
                lines.append("  Text: \(text.prefix(200))")
            }
        }
        // Type results
        else if json["typed"] as? Bool == true {
            let sel = json["selector"] as? String ?? "?"
            let len = json["length"] as? Int ?? 0
            lines.append("Typed \(len) chars into \(sel)")
        }
        // Scroll results
        else if json["scrolled"] as? Bool == true {
            let dir = json["direction"] as? String ?? "?"
            let amt = json["amount"] as? Int ?? 0
            lines.append("Scrolled \(dir) \(amt)px")
            if let y = json["scrollY"] { lines.append("  Scroll Y: \(y)") }
            if let h = json["scrollHeight"] { lines.append("  Page height: \(h)") }
            if let vh = json["viewportHeight"] { lines.append("  Viewport height: \(vh)") }
        }
        // Scroll to element
        else if let sel = json["scrolledTo"] as? String {
            let tag = json["tag"] as? String ?? "?"
            lines.append("Scrolled to <\(tag)> (\(sel))")
        }
        // Hover results
        else if json["hovered"] as? Bool == true {
            let tag = json["tag"] as? String ?? "?"
            lines.append("Hovered <\(tag)>")
            if let text = json["text"] as? String, !text.isEmpty {
                lines.append("  Text: \(text.prefix(200))")
            }
        }
        // getText results
        else if let text = json["text"] as? String, json["length"] != nil {
            if let title = json["title"] as? String, !title.isEmpty {
                lines.append("Title: \(title)")
            }
            let len = json["length"] as? Int ?? text.count
            lines.append("Text (\(len) chars):")
            lines.append(String(text.prefix(10000)))
        }
        // findElements results
        else if let count = json["count"] as? Int, let elements = json["elements"] as? [[String: Any]] {
            let shown = json["shown"] as? Int ?? elements.count
            lines.append("Found \(count) element(s) (showing \(shown)):")
            for el in elements {
                let idx = el["index"] as? Int ?? 0
                let tag = el["tag"] as? String ?? "?"
                let text = (el["text"] as? String ?? "").prefix(80)
                var desc = "  [\(idx)] <\(tag)>"
                if let id = el["id"] as? String, !id.isEmpty { desc += " #\(id)" }
                if !text.isEmpty { desc += " \"\(text)\"" }
                if let href = el["href"] as? String, !href.isEmpty { desc += " → \(href)" }
                lines.append(desc)
            }
        }
        // getPageInfo / generic
        else {
            // Fallback: format each key-value pair
            for (key, value) in json.sorted(by: { $0.key < $1.key }) {
                lines.append("  \(key): \(value)")
            }
        }

        return lines.joined(separator: "\n")
    }
}

// MARK: - WKNavigationDelegate

extension BrowserUseManager: WKNavigationDelegate {
    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { @MainActor in
            self.isLoading = false
            self.loadError = nil  // [T-ios-webview-error-ui]
            self.currentURL = webView.url?.absoluteString ?? self.currentURL
            self.pageTitle = webView.title ?? ""
            self.canGoBack = webView.canGoBack
            self.canGoForward = webView.canGoForward

            // Record in browsing history
            BrowserHistoryStore.shared.record(url: self.currentURL, title: self.pageTitle)

            if let cont = self.navigationContinuation {
                self.navigationContinuation = nil
                cont.resume()
            }
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        Task { @MainActor in
            self.isLoading = false
            // [T-ios-webview-error-ui] Show a Safari-style overlay in the
            // browser sheet instead of a blank page. Still thrown to the agent.
            self.loadError = WebLoadError(error: error, failedURL: self.lastRequestedURL)
            logger.error("Navigation failed: \(error.localizedDescription)")

            if let cont = self.navigationContinuation {
                self.navigationContinuation = nil
                cont.resume(throwing: error)
            }
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        Task { @MainActor in
            self.isLoading = false
            // [T-ios-webview-error-ui] see didFail above.
            self.loadError = WebLoadError(error: error, failedURL: self.lastRequestedURL)
            logger.error("Provisional navigation failed: \(error.localizedDescription)")

            if let cont = self.navigationContinuation {
                self.navigationContinuation = nil
                cont.resume(throwing: error)
            }
        }
    }

    nonisolated func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        preferences: WKWebpagePreferences,
        decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void
    ) {
        let allowedSchemes: Set<String> = ["http", "https", "about", "blob", "minis"]

        // <a download> anchors (including blob: URLs) mark the action as a
        // download — route it through WKDownload instead of navigating.
        if navigationAction.shouldPerformDownload {
            decisionHandler(.download, preferences)
            return
        }

        // Block all non-web URL schemes (e.g. itms-apps://, tel://, mailto://).
        // Covers redirects, JS-initiated navigations, and link clicks.
        if let url = navigationAction.request.url,
           let scheme = url.scheme?.lowercased(),
           !allowedSchemes.contains(scheme) {
            decisionHandler(.cancel, preferences)
            // Resolve the pending navigation continuation so navigate() doesn't hang
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isLoading = false
                self.currentURL = webView.url?.absoluteString ?? self.currentURL
                if let cont = self.navigationContinuation {
                    self.navigationContinuation = nil
                    cont.resume(throwing: URLError(.unsupportedURL, userInfo: [
                        NSLocalizedDescriptionKey: "Blocked navigation to \(scheme):// — only http(s) and minis:// are allowed in the browser."
                    ]))
                }
            }
            return
        }

        // Use _WKNavigationActionPolicy.allowWithoutTryingAppLink (rawValue 2) to
        // prevent WKWebView from opening universal links in external apps.
        let allowWithoutAppLink = WKNavigationActionPolicy(rawValue: WKNavigationActionPolicy.allow.rawValue + 2) ?? .allow

        // Override preferredContentMode per current UA profile + viewport.
        // navigation-delegate callbacks run on the main thread per the
        // WebKit contract, so MainActor.assumeIsolated is safe here.
        let mode: WKWebpagePreferences.ContentMode = MainActor.assumeIsolated {
            let profile = self.currentProfile
            let vp = self.currentViewport
            let wantsDesktop = (profile == .desktopSafari) || (vp.width >= 1024)
            return wantsDesktop ? .desktop : .mobile
        }
        preferences.preferredContentMode = mode
        decisionHandler(allowWithoutAppLink, preferences)
    }

    nonisolated func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
    ) {
        // Server-driven downloads: Content-Disposition: attachment, or a MIME
        // type WebKit can't render inline (zip, octet-stream, …).
        if let http = navigationResponse.response as? HTTPURLResponse,
           let disposition = http.value(forHTTPHeaderField: "Content-Disposition")?.lowercased(),
           disposition.contains("attachment") {
            decisionHandler(.download)
            return
        }
        if !navigationResponse.canShowMIMEType {
            decisionHandler(.download)
            return
        }
        decisionHandler(.allow)
    }

    nonisolated func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        // Delegate callbacks arrive on the main thread per the WebKit contract.
        // Set download.delegate synchronously — decideDestinationUsing fires
        // right after this returns and needs the delegate in place.
        MainActor.assumeIsolated { self.attachDownload(download) }
    }

    nonisolated func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        MainActor.assumeIsolated { self.attachDownload(download) }
    }

    /// Adopt a WKDownload: become its delegate, keep it alive, and unblock any
    /// pending navigate() continuation (the "navigation" turned into a download).
    private func attachDownload(_ download: WKDownload) {
        download.delegate = self
        inflightDownloads[ObjectIdentifier(download)] = download
        isLoading = false
        if let cont = navigationContinuation {
            navigationContinuation = nil
            cont.resume()
        }
    }

    /// The WebContent process backing this tab was killed by the system (jetsam
    /// under memory pressure is the usual cause, and a backgrounded app is a
    /// prime target). Previously unimplemented, so this was silent: the WKWebView
    /// just went blank and any in-flight `navigate()` continuation was stranded
    /// forever — a strong candidate for the ~287-minute hang under investigation.
    ///
    /// Two things happen here. We log it (the one unambiguous "the system killed
    /// the browser" signal; every other diagnostic is inference), and we resume
    /// the stranded continuation with an error so the action fails fast instead
    /// of hanging until the agent's own timeout — or never. [BrowserToolDiag]
    nonisolated func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        MainActor.assumeIsolated {
            let url = self.currentURL.isEmpty ? nil : self.currentURL
            let tabId = self.tabIdProvider?()
            BrowserResourceMonitor.shared.webContentProcessDidTerminate(tabId: tabId, url: url)

            self.isLoading = false
            let error = NSError(
                domain: "BrowserUse",
                code: -3001,
                userInfo: [NSLocalizedDescriptionKey:
                    "The browser's web content process was terminated by the system "
                    + "(likely out of memory, or suspended in the background). Retry the action."]
            )
            self.loadError = WebLoadError(error: error, failedURL: self.lastRequestedURL)
            if let cont = self.navigationContinuation {
                self.navigationContinuation = nil
                cont.resume(throwing: error)
            }
        }
    }
}

// MARK: - WKDownloadDelegate

extension BrowserUseManager: WKDownloadDelegate {
    nonisolated func download(
        _ download: WKDownload,
        decideDestinationUsing response: URLResponse,
        suggestedFilename: String,
        completionHandler: @escaping (URL?) -> Void
    ) {
        Task { @MainActor in
            guard let sid = self.sessionIdProvider?(), !sid.isEmpty else {
                logger.warning("Download rejected — no session id to resolve a destination")
                self.inflightDownloads.removeValue(forKey: ObjectIdentifier(download))
                completionHandler(nil)
                return
            }
            let dir = AIChatViewModel.minisWorkspacePersistentDir(for: sid)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

            // Unique filename: name.ext, name-1.ext, name-2.ext, …
            // WKDownload requires that the destination file does not exist.
            var name = suggestedFilename.isEmpty ? "download" : suggestedFilename
            let base = (name as NSString).deletingPathExtension
            let ext = (name as NSString).pathExtension
            var dest = dir.appendingPathComponent(name)
            var counter = 1
            while FileManager.default.fileExists(atPath: dest.path) {
                name = ext.isEmpty ? "\(base)-\(counter)" : "\(base)-\(counter).\(ext)"
                dest = dir.appendingPathComponent(name)
                counter += 1
            }

            let key = ObjectIdentifier(download)
            self.downloadDestinations[key] = dest
            // [T-browser-download-ux] Progress now surfaces via the floating
            // chat download button (BrowserDownloadCenter) — no "Downloading…"
            // chat row. The cancel closure lets the panel abort the WKDownload.
            self.downloadCenterIds[key] = BrowserDownloadCenter.shared.began(
                sessionId: sid, filename: name, progress: download.progress,
                onCancel: { [weak self, weak download] in
                    guard let self, let download else { return }
                    self.cancelDownload(download)
                })
            logger.info("⬇️ Download started: \(name) → session \(sid.prefix(8)) workspace")
            completionHandler(dest)
        }
    }

    nonisolated func downloadDidFinish(_ download: WKDownload) {
        Task { @MainActor in
            let key = ObjectIdentifier(download)
            self.inflightDownloads.removeValue(forKey: key)
            guard let dest = self.downloadDestinations.removeValue(forKey: key) else { return }
            let name = dest.lastPathComponent
            let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
            let sizeText = ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
            if let entryId = self.downloadCenterIds.removeValue(forKey: key) {
                BrowserDownloadCenter.shared.finished(id: entryId, sizeText: sizeText)
            }
            logger.info("✅ Download finished: \(name) (\(sizeText))")
            if let sid = self.sessionIdProvider?(), !sid.isEmpty {
                // [T-browser-download-ux-v3] Name + size only — the full
                // path/link made the bubble wrap badly, and path navigation
                // is the downloads panel's "Show in Files" job now.
                self.notifySessionChat(
                    sid: sid,
                    text: "Downloaded \(name.middleTruncated()) (\(sizeText))",
                    icon: "checkmark.circle"
                )
            }
        }
    }

    nonisolated func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        Task { @MainActor in
            let key = ObjectIdentifier(download)
            self.inflightDownloads.removeValue(forKey: key)
            let dest = self.downloadDestinations.removeValue(forKey: key)
            let name = dest?.lastPathComponent ?? "file"
            if let entryId = self.downloadCenterIds.removeValue(forKey: key) {
                BrowserDownloadCenter.shared.failed(id: entryId, reason: error.localizedDescription)
            }
            // Remove any partial file so a retried download can reuse the name.
            if let dest { try? FileManager.default.removeItem(at: dest) }
            logger.error("Download failed: \(name) — \(error.localizedDescription)")
            if let sid = self.sessionIdProvider?(), !sid.isEmpty {
                self.notifySessionChat(sid: sid, text: "Download failed: \(name.middleTruncated()) — \(error.localizedDescription)", icon: "xmark.circle")
            }
        }
    }

    /// Post an ephemeral system-info notice into the owning session's chat.
    private func notifySessionChat(sid: String, text: String, icon: String) {
        ViewModelCache.shared.get(for: sid)?.appendSystemInfo(text, icon: icon)
    }

    /// User-initiated cancel (downloads panel). Cancels the WKDownload and
    /// tears down the tracking maps FIRST so a racing delegate callback for
    /// the same download finds nothing to double-report, then removes the
    /// partial file. WKDownload does not call delegate methods after
    /// cancel(_:), so this is the only cleanup path. [T-browser-download-ux]
    private func cancelDownload(_ download: WKDownload) {
        let key = ObjectIdentifier(download)
        inflightDownloads.removeValue(forKey: key)
        downloadCenterIds.removeValue(forKey: key)
        let dest = downloadDestinations.removeValue(forKey: key)
        download.cancel { _ in }
        if let dest {
            try? FileManager.default.removeItem(at: dest)
            logger.info("🛑 Download cancelled by user: \(dest.lastPathComponent) — partial file removed")
        }
    }
}

// MARK: - WKUIDelegate (target="_blank" / window.open)

extension BrowserUseManager: WKUIDelegate {
    /// Called on main thread by WebKit when window.open() / target="_blank" is triggered.
    /// Must return synchronously so the opener retains a window.opener reference.
    @MainActor
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        // Create a new tab using the provided configuration so the child shares
        // the opener's session (cookies, window.opener) for OAuth flows.
        let url = navigationAction.request.url
        if let handler = self.newWindowHandler {
            return handler(configuration, url)
        }
        // No pool attached — fall back to loading in current webview
        webView.load(navigationAction.request)
        return nil
    }

    nonisolated func webViewDidClose(_ webView: WKWebView) {
        // The child window called window.close() (e.g. OAuth popup finishing).
        Task { @MainActor in
            self.closeHandler?(webView)
        }
    }
}

// MARK: - WeakScriptMessageHandler

/// Forwards script messages to a target without retaining it. Needed because a
/// WKUserContentController retains its message handlers strongly, which would
/// otherwise create a retain cycle through the web view's configuration.
final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?

    init(_ target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

// MARK: - WKScriptMessageHandler (window.print bridge)

extension BrowserUseManager: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        guard message.name == Self.printMessageHandlerName else { return }
        presentPrintDialog()
    }
}

// MARK: - MinisURLSchemeHandler

/// Handles `minis://` URLs in WKWebView by resolving them to local files.
/// Supports `minis://workspace/file.html`, `minis://shared/...`, etc.
final class MinisURLSchemeHandler: NSObject, WKURLSchemeHandler {
    private let logger = AppLogger(category: "MinisScheme")

    func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask) {
        let url = urlSchemeTask.request.url!
        logger.info("[MinisScheme] start \(url.absoluteString)")

        guard let fileURL = AIChatViewModel.resolveMinisURL(url) else {
            logger.warning("[MinisScheme] not found: \(url.absoluteString)")
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist))
            return
        }

        guard let data = try? Data(contentsOf: fileURL) else {
            logger.warning("[MinisScheme] read failed: \(fileURL.path)")
            urlSchemeTask.didFailWithError(URLError(.cannotOpenFile))
            return
        }

        let mimeType = Self.mimeType(for: fileURL.pathExtension)
        let response = URLResponse(
            url: url,
            mimeType: mimeType,
            expectedContentLength: data.count,
            textEncodingName: mimeType.hasPrefix("text/") ? "utf-8" : nil
        )
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
        logger.info("[MinisScheme] served \(url.absoluteString) → \(fileURL.lastPathComponent) (\(data.count) bytes, \(mimeType))")
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
        // No async work to cancel.
    }

    private static func mimeType(for ext: String) -> String {
        switch ext.lowercased() {
        case "html", "htm": return "text/html"
        case "css":         return "text/css"
        case "js":          return "application/javascript"
        case "json":        return "application/json"
        case "xml":         return "application/xml"
        case "svg":         return "image/svg+xml"
        case "png":         return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif":         return "image/gif"
        case "webp":        return "image/webp"
        case "ico":         return "image/x-icon"
        case "pdf":         return "application/pdf"
        case "mp3":         return "audio/mpeg"
        case "mp4":         return "video/mp4"
        case "webm":        return "video/webm"
        case "woff":        return "font/woff"
        case "woff2":       return "font/woff2"
        case "ttf":         return "font/ttf"
        case "txt", "log":  return "text/plain"
        case "md":          return "text/markdown"
        case "csv":         return "text/csv"
        case "wasm":        return "application/wasm"
        default:            return "application/octet-stream"
        }
    }
}

// MARK: - BrowserDownloadCenter

/// Session-scoped registry of native browser (WKDownload) file downloads.
/// [T-browser-download-ux] Two consumers:
///
///  1. **Chat UI** — `AIChatView` overlays a floating download card (icon +
///     live progress + tap-to-preview) driven by `entries`, replacing the old
///     "Downloading xxx…" systemInfo row in the message list.
///  2. **Agent loop** — browser_use tool results append `agentReport(for:)`
///     so the LLM knows a page interaction triggered a native download and
///     must NOT re-download the same file via shell curl/wget (observed
///     failure mode: browser downloads moomoo.apk natively, model curls it
///     again because nothing in the tool result mentioned the download).
///
/// Entries are keyed by session (a session may spread downloads across
/// multiple browser tabs); completed/failed cards linger briefly for the
/// user, then auto-dismiss. Agent event lines are queued per session and
/// consumed exactly once by the next browser_use tool result.
@MainActor
final class BrowserDownloadCenter: ObservableObject {

    static let shared = BrowserDownloadCenter()

    enum DownloadState: Equatable {
        case downloading
        case completed(sizeText: String)
        case failed(reason: String)
    }

    struct Entry: Identifiable {
        let id: UUID
        let sessionId: String
        let filename: String
        /// The WKDownload's own NSProgress — KVO-observable for live UI.
        let progress: Progress
        var state: DownloadState
        /// When the download started — the panel lists newest first.
        let startedAt: Date
        /// The user has viewed this entry in the downloads panel. Unseen
        /// terminal entries count toward the floating button's badge.
        var seen: Bool = false
        /// Cancels the underlying WKDownload (wired by BrowserUseManager).
        let onCancel: (() -> Void)?
    }

    @Published private(set) var entries: [Entry] = []

    /// Agent-facing event lines not yet delivered in a browser_use tool
    /// result, per session. One line per filename — a later event for the
    /// same file (completed/failed) replaces its pending "started" line.
    private var pendingAgentEvents: [String: [(filename: String, line: String)]] = [:]

    func downloads(for sessionId: String) -> [Entry] {
        entries.filter { $0.sessionId == sessionId }
    }

    /// Floating-button badge: in-flight downloads plus terminal entries the
    /// user hasn't viewed in the panel yet. 0 ⇒ the button is hidden.
    func badgeCount(for sessionId: String) -> Int {
        downloads(for: sessionId).filter { $0.state == .downloading || !$0.seen }.count
    }

    func began(sessionId: String, filename: String, progress: Progress,
               onCancel: (() -> Void)? = nil) -> UUID {
        let id = UUID()
        entries.append(Entry(id: id, sessionId: sessionId, filename: filename,
                             progress: progress, state: .downloading,
                             startedAt: Date(), onCancel: onCancel))
        queueAgentEvent(sessionId: sessionId, filename: filename,
                        line: "\(filename): download started → saving to minis://workspace/\(filename)")
        return id
    }

    func finished(id: UUID, sizeText: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].state = .completed(sizeText: sizeText)
        entries[idx].seen = false
        let e = entries[idx]
        queueAgentEvent(sessionId: e.sessionId, filename: e.filename,
                        line: "\(e.filename): download COMPLETED (\(sizeText)) → minis://workspace/\(e.filename) (shell path: /var/minis/workspace/\(e.filename))")
    }

    func failed(id: UUID, reason: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].state = .failed(reason: reason)
        entries[idx].seen = false
        let e = entries[idx]
        queueAgentEvent(sessionId: e.sessionId, filename: e.filename,
                        line: "\(e.filename): download FAILED — \(reason)")
    }

    /// User-initiated cancel from the downloads panel. Tears down the
    /// WKDownload via the manager-wired closure (which also removes the
    /// partial file), then records the terminal state here.
    func cancel(id: UUID) {
        guard let idx = entries.firstIndex(where: { $0.id == id }),
              entries[idx].state == .downloading else { return }
        entries[idx].onCancel?()
        entries[idx].state = .failed(reason: AppLocalized("Cancelled", comment: "Download cancelled by user"))
        entries[idx].seen = true
        let e = entries[idx]
        queueAgentEvent(sessionId: e.sessionId, filename: e.filename,
                        line: "\(e.filename): download CANCELLED by the user — the partial file was removed. Do not assume this file exists.")
    }

    func dismiss(id: UUID) {
        entries.removeAll { $0.id == id }
    }

    /// Panel-open: everything currently listed has been viewed (clears the
    /// badge). Records persist until the user clears them — the floating
    /// button hides only when the entry list for the session is empty.
    func markAllSeen(for sessionId: String) {
        for idx in entries.indices where entries[idx].sessionId == sessionId {
            entries[idx].seen = true
        }
    }

    /// Panel "Clear" action: removes every finished record (completed AND
    /// failed); in-flight downloads are untouched. [T-browser-download-ux-v3]
    func clearFinished(for sessionId: String) {
        entries.removeAll { $0.sessionId == sessionId && $0.state != .downloading }
    }

    /// Consumes queued events and snapshots in-flight progress into a block
    /// the browser_use tool result appends for the LLM. Returns nil when
    /// there is nothing to report (the overwhelmingly common case).
    func agentReport(for sessionId: String) -> String? {
        let events = pendingAgentEvents.removeValue(forKey: sessionId) ?? []
        let inflight = downloads(for: sessionId).filter { $0.state == .downloading }
        let inflightNames = Set(inflight.map(\.filename))

        var lines: [String] = inflight.map { e in
            let p = e.progress
            let pct = p.totalUnitCount > 0 ? "\(Int(p.fractionCompleted * 100))%" : "…"
            let done = ByteCountFormatter.string(fromByteCount: p.completedUnitCount, countStyle: .file)
            let total = p.totalUnitCount > 0
                ? ByteCountFormatter.string(fromByteCount: p.totalUnitCount, countStyle: .file) : "?"
            return "\(e.filename): downloading \(pct) (\(done) / \(total)) → saving to minis://workspace/\(e.filename)"
        }
        // A live progress line supersedes the same file's queued "started" line.
        lines += events.filter { !inflightNames.contains($0.filename) }.map(\.line)
        guard !lines.isEmpty else { return nil }

        return "[browser_downloads] The browser is handling file download(s) NATIVELY "
            + "(triggered by page navigation/click, saved into this session's workspace):\n"
            + lines.map { "- \($0)" }.joined(separator: "\n")
            + "\nDo NOT re-download these files with curl/wget in shell_execute. "
            + "Completed files are already fully saved at the given path; for in-progress "
            + "downloads, wait and check again (e.g. via a later browser_use call or "
            + "shell `ls -l /var/minis/workspace/`) instead of downloading in parallel."
    }

    private func queueAgentEvent(sessionId: String, filename: String, line: String) {
        var list = pendingAgentEvents[sessionId] ?? []
        list.removeAll { $0.filename == filename }
        list.append((filename: filename, line: line))
        pendingAgentEvents[sessionId] = list
    }
}

// MARK: - Middle-truncation helper

extension String {
    /// Head…tail truncation for long filenames in space-constrained text
    /// (chat notices, logs) — mirrors the Files-app look where the extension
    /// stays visible: "com.tigerbrokers.stock-9.5.8.1-APK4….8.1.apk".
    /// SwiftUI rows should keep using .truncationMode(.middle) (width-adaptive);
    /// this is for plain strings that render without a line limit.
    /// [T-browser-download-ux-v3]
    func middleTruncated(head: Int = 22, tail: Int = 10) -> String {
        guard count > head + tail + 1 else { return self }
        return "\(prefix(head))…\(suffix(tail))"
    }
}
