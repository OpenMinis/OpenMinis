//
//  WebPreviewSheet.swift
//  MinisApp
//
//  Safari-style full-screen web preview + half-sheet link preview,
//  plus the shared WKWebView holder that is re-parented between the
//  sheet and fullscreen presentations so the page isn't reloaded.
//  Extracted from AIChatView.swift.
//

import SwiftUI
import UIKit
import WebKit

// MARK: - Shared WebView Holder (reused across sheet ↔ fullscreen)

/// Owns a single WKWebView and publishes its navigation state.
/// Both the sheet preview and the fullscreen Safari-style view share this instance
/// so the webview is re-parented rather than re-created on mode switch.
final class WebViewHolder: NSObject, ObservableObject {
    let webView: WKWebView
    @Published var pageTitle: String = ""
    @Published var currentURL: String = ""
    @Published var isLoading: Bool = false
    /// [T-ios-webview-error-ui] Non-nil when the current navigation failed
    /// (DNS/host/offline/timeout/TLS). The hosting view shows a Safari-style
    /// error overlay + Retry. Cleared when a load starts or finishes.
    @Published var loadError: WebLoadError?

    private var observations: [NSKeyValueObservation] = []

    /// Pending navigation request — deferred until `startIfNeeded()` is
    /// called by the hosting SwiftUI view in `.onAppear`. See the comment
    /// inside `startIfNeeded()` for why we can't load at init-time.
    private var pendingURL: URL?
    private var pendingLocalFile: Bool = false
    private var didStart: Bool = false
    private var windowWaitRetries: Int = 0

    init(url: URL, localFile: Bool) {
        let config = WKWebViewConfiguration()
        // Match BrowserUseManager verbatim: shared process pool, default
        // data store, JS on, fullscreen enabled. This keeps the in-chat
        // preview in the same session (cookies, localStorage, HSTS state)
        // as the right-toolbar Open Browser and every agent tab.
        config.processPool = BrowserUseManager.sharedProcessPool
        config.websiteDataStore = .default()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.preferences.isElementFullscreenEnabled = true
        config.setURLSchemeHandler(BrowserUseManager.sharedMinisSchemeHandler, forURLScheme: "minis")

        // Bridge JS window.print() to the native print dialog, matching
        // BrowserUseManager so minis:// HTML opened from chat (which routes
        // through this holder, not the agent browser) can print too.
        config.userContentController.addUserScript(BrowserUseManager.printBridgeScript())

        if localFile {
            let prefs = config.preferences
            // [T-ios-mac-uncaught-nsexception] Both keys are UNDOCUMENTED WKPreferences
            // KVC keys. A raw setValue:forKey: throws NSUnknownKeyException the moment a
            // WebKit release stops backing one — uncatchable from Swift, so it terminates
            // the process. The sibling key below was already routed through the guard;
            // this one was not, which is the whole hazard (same object, same risk, one
            // line apart). Same treatment for both: keep the behaviour, survive removal.
            SafeKVCSetTrue.apply(prefs, key: "allowFileAccessFromFileURLs")
            SafeKVCSetTrue.apply(prefs, key: "allowUniversalAccessFromFileURLs")
        }

        webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.allowsBackForwardNavigationGestures = true

        super.init()

        // [T-ios-webview-error-ui] The holder is its own navigation delegate so
        // it can both keep the existing non-http scheme hand-off AND capture
        // load failures into `loadError` for the error overlay.
        webView.navigationDelegate = self

        // Receive the window.print() bridge message. Weak proxy avoids a
        // retain cycle (controller → holder → webView → configuration → controller).
        config.userContentController.add(
            WeakScriptMessageHandler(self),
            name: BrowserUseManager.printMessageHandlerName
        )

        // Follow whatever the user picked in Browser Settings, using the
        // same UserDefaults keys as `BrowserTabPool.init`. Without this
        // the preview uses the default Mac Catalyst desktop UA, which
        // fingerprints as a different client than the agent/toolbar
        // browser and produces inconsistent cookie state across sessions.
        if !localFile {
            let defaults = UserDefaults.standard
            let profile: UserAgentProfile = {
                if let raw = defaults.string(forKey: "BrowserUserAgentProfile"),
                   let p = UserAgentProfile(rawValue: raw) { return p }
                return .mobileSafari
            }()
            let ua: String? = {
                if profile == .custom {
                    let custom = defaults.string(forKey: "BrowserCustomUserAgentString") ?? ""
                    return custom.isEmpty ? nil : custom
                }
                return profile.userAgentString
            }()
            if let ua {
                webView.customUserAgent = ua
            }
        }

        observations = [
            webView.observe(\.title) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    if let t = wv.title, !t.isEmpty { self?.pageTitle = t }
                }
            },
            webView.observe(\.url) { [weak self] wv, _ in
                DispatchQueue.main.async {
                    self?.currentURL = wv.url?.absoluteString ?? ""
                }
            },
            webView.observe(\.isLoading) { [weak self] wv, _ in
                DispatchQueue.main.async { self?.isLoading = wv.isLoading }
            }
        ]

        // Stash the URL. Deliberately do NOT call webView.load here — see
        // startIfNeeded() for the rationale.
        pendingURL = url
        pendingLocalFile = localFile
    }

    /// Start the deferred navigation. Must be called from the hosting
    /// view's `.onAppear`. The load itself is further delayed (polling
    /// every 50ms, up to ~2s) until `webView.window != nil`.
    ///
    /// Rationale: when the preview is presented via `.sheet(item:)` from
    /// inside a `.fullScreenCover` (the iSH terminal → `minis-open` flow
    /// on Mac Catalyst), SwiftUI attaches the webview to a superview
    /// before attaching it to a window. A WKWebView that calls `load()`
    /// while detached from a window enters a pathological state: WebKit
    /// fires `didFinish` on a stub page and then keeps re-issuing the
    /// original `http://…` request in a ~20ms loop, which users see as
    /// the sheet being stuck on a perpetual spinner. Waiting for window
    /// attachment avoids that branch and works identically on regular
    /// sheets (where `window` is already non-nil at `onAppear` time).
    func startIfNeeded() {
        guard !didStart, let url = pendingURL else { return }
        if webView.window != nil {
            didStart = true
            performLoad(url: url)
            return
        }
        windowWaitRetries += 1
        if windowWaitRetries > 40 { // ~2s
            didStart = true
            performLoad(url: url)
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            self?.startIfNeeded()
        }
    }

    private func performLoad(url: URL) {
        loadError = nil  // [T-ios-webview-error-ui] clear stale error on (re)load
        if pendingLocalFile {
            loadLocalFileBypassingCache(url)
        } else {
            webView.load(URLRequest(url: url))
        }
    }

    /// [T-ios-webview-error-ui] Retry the URL that failed (or reload current).
    /// Used by the error overlay's "Try Again".
    func retryFailedLoad() {
        loadError = nil
        if let failed = pendingURL {
            performLoad(url: failed)
        } else {
            webView.reload()
        }
    }

    /// Load a local file while bypassing WebKit's cache.
    ///
    /// [T-ios-file-preview-stale-cache] When the agent rewrites a file in place
    /// (e.g. `pull_exec.py`, or an HTML report regenerated several times), the
    /// on-disk bytes change but the file:// URL string does not. WKWebView with a
    /// shared process pool + the persistent `.default()` website data store
    /// (see init) serves the previously cached body for that same file:// URL, so
    /// re-opening the preview shows an earlier version. `loadFileURL` offers no
    /// cache-policy control, so use `loadFileRequest` with
    /// `.reloadIgnoringLocalCacheData` to force WebKit to re-read the file from
    /// disk every time the preview is presented. Available iOS 15+.
    private func loadLocalFileBypassingCache(_ url: URL) {
        let readAccess = url.deletingLastPathComponent()
        if #available(iOS 15.0, *) {
            var req = URLRequest(url: url)
            req.cachePolicy = .reloadIgnoringLocalCacheData
            webView.loadFileRequest(req, allowingReadAccessTo: readAccess)
        } else {
            webView.loadFileURL(url, allowingReadAccessTo: readAccess)
        }
    }

    func reload() { loadError = nil; webView.reload() }
    func stopLoading() { webView.stopLoading() }

    /// Toggle desktop-mode UA for this preview session only. Does not touch
    /// UserDefaults — the next time the preview is opened it returns to the
    /// user's configured Browser Settings profile. Reloads the page so the
    /// server-side UA sniffing actually re-runs.
    func setDesktopMode(_ enabled: Bool) {
        if enabled {
            webView.customUserAgent = UserAgentProfile.desktopSafari.userAgentString
        } else {
            let defaults = UserDefaults.standard
            let profile: UserAgentProfile = {
                if let raw = defaults.string(forKey: "BrowserUserAgentProfile"),
                   let p = UserAgentProfile(rawValue: raw) { return p }
                return .mobileSafari
            }()
            if profile == .custom {
                let custom = defaults.string(forKey: "BrowserCustomUserAgentString") ?? ""
                webView.customUserAgent = custom.isEmpty ? nil : custom
            } else {
                webView.customUserAgent = profile.userAgentString
            }
        }
        webView.reload()
    }
}

// MARK: - window.print() bridge

extension WebViewHolder {
    /// Present the system print dialog for the current page. Called both by
    /// the JS `window.print()` bridge and the preview "More" menu's Print item.
    func printPage() {
        PrintHelper.printWebView(webView, jobName: pageTitle)
    }
}

extension WebViewHolder: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard message.name == BrowserUseManager.printMessageHandlerName else { return }
        printPage()
    }
}

// MARK: - Shared "More" menu

/// The "..." menu shared between the sheet and fullscreen previews.
/// `url` is the original tap target (used for share / open-in-Safari /
/// copy-link). `desktopMode` is a per-preview toggle; flipping it calls
/// `holder.setDesktopMode(...)` and reloads.
struct WebPreviewMoreMenu: View {
    let url: URL
    @ObservedObject var holder: WebViewHolder
    let allowOpenInSafari: Bool
    @Binding var desktopMode: Bool
    @Binding var showShareSheet: Bool
    /// When non-nil, the menu shows an "Expand" entry that calls this. Sheet
    /// preview wires it up; fullscreen passes nil so the entry is hidden.
    var onExpand: (() -> Void)? = nil
    /// Inverse of `onExpand` — fullscreen preview wires it up to return to
    /// the sheet presentation. Sheet preview passes nil.
    var onCollapse: (() -> Void)? = nil
    /// Only the minis:// HTML preview wires this up — it triggers the
    /// existing `WebAppAddToHomeSheet` flow. http(s) previews pass nil so
    /// the entry is hidden (Safari shortcut sheets aren't reachable from
    /// inside our app anyway).
    var onAddToHomeScreen: (() -> Void)? = nil

    var body: some View {
        Menu {
            if holder.isLoading {
                Button {
                    holder.stopLoading()
                } label: {
                    Label(AppLocalized("Stop"), systemImage: "xmark.circle")
                }
            } else {
                Button {
                    holder.reload()
                } label: {
                    Label(AppLocalized("Reload"), systemImage: "arrow.clockwise")
                }
            }
            if let onExpand {
                Button {
                    onExpand()
                } label: {
                    Label(AppLocalized("Fullscreen"), systemImage: "arrow.up.left.and.arrow.down.right")
                }
            }
            if let onCollapse {
                Button {
                    onCollapse()
                } label: {
                    Label(AppLocalized("Exit Fullscreen"), systemImage: "arrow.down.right.and.arrow.up.left")
                }
            }
            if let onAddToHomeScreen {
                Button {
                    onAddToHomeScreen()
                } label: {
                    Label(AppLocalized("Add to Home Screen"), systemImage: "rectangle.stack.badge.plus")
                }
            }
            Button {
                desktopMode.toggle()
                holder.setDesktopMode(desktopMode)
            } label: {
                if desktopMode {
                    Label(AppLocalized("Request Mobile Site"), systemImage: "iphone")
                } else {
                    Label(AppLocalized("Request Desktop Site"), systemImage: "desktopcomputer")
                }
            }
            if allowOpenInSafari {
                Button {
                    UIApplication.shared.open(url)
                } label: {
                    Label(AppLocalized("Open in Safari"), systemImage: "safari")
                }
            }
            Button {
                holder.printPage()
            } label: {
                Label(AppLocalized("Print"), systemImage: "printer")
            }
            Button {
                showShareSheet = true
            } label: {
                Label(AppLocalized("Share"), systemImage: "square.and.arrow.up")
            }
            Button {
                UIPasteboard.general.string = url.absoluteString
            } label: {
                Label(AppLocalized("Copy Link"), systemImage: "doc.on.doc")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }
}

// MARK: - Navigation delegate (scheme hand-off + error capture)

extension WebViewHolder: WKNavigationDelegate {
    /// Prevents non-http schemes from loading inside the webview.
    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url,
           let scheme = url.scheme?.lowercased(),
           !["http", "https", "about", "blob", "file"].contains(scheme) {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    // [T-ios-webview-error-ui] Surface load failures as a Safari-style overlay
    // instead of a blank page. `didFailProvisionalNavigation` covers the
    // common ones the user reported (DNS not found, host unreachable, offline,
    // timeout — the server response never arrives); `didFail` covers failures
    // after a response started. A real load starting/finishing clears it.
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        if loadError != nil { loadError = nil }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loadError = nil
    }

    func webView(_ webView: WKWebView,
                 didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        loadError = WebLoadError(error: error, failedURL: pendingURL)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        loadError = WebLoadError(error: error, failedURL: pendingURL)
    }
}

/// Thin representable that places an existing WKWebView into SwiftUI without re-creating it.
struct ReusableWebView: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

// MARK: - Sheet dismiss arbitration (gesture layer, synchronous)

/// [T-webview-preview-swipe-dismiss] Arbitrates the system sheet's
/// interactive-dismiss pan against the WKWebView's page content AT THE GESTURE
/// RECOGNITION LAYER — synchronously, in the same runloop, with no SwiftUI
/// round-trip.
///
/// The earlier approach flipped `.interactiveDismissDisabled` through a
/// `UILongPressGestureRecognizer → DispatchQueue.main.async → @State → SwiftUI
/// diff → UISheetPresentationController` chain. That chain adds ≥1–2 runloop
/// ticks of latency: on a FAST flick, the system dismiss pan crosses its
/// recognition threshold and `.began`s before the flag reaches the sheet, and
/// `interactiveDismissDisabled` cannot roll back an already-begun gesture — so
/// the preview dismissed mid-drag. Slow drags happened to work only because the
/// chain had time to settle first.
///
/// Instead: locate the sheet's dismiss pan and install ourselves as its
/// delegate (chaining the original so system behavior is untouched for the
/// cases we don't veto). In `gestureRecognizerShouldBegin(dismissPan)` we
/// synchronously return `false` when the touch is inside the web content — the
/// dismiss pan then fails to begin and the page owns the gesture. This decision
/// happens the instant UIKit evaluates the pan, so a flick can't outrun it.
///
/// A pull-down that STARTS on the nav bar (outside the web view) is left to the
/// original delegate → the sheet still dismisses there; the toolbar xmark and
/// the "…" menu Close are unaffected.
final class SheetDismissArbiter: NSObject, UIGestureRecognizerDelegate {
    private weak var webView: WKWebView?
    private weak var dismissPan: UIPanGestureRecognizer?
    private weak var originalDelegate: UIGestureRecognizerDelegate?
    private var retries = 0

    func attach(to webView: WKWebView) {
        self.webView = webView
        installIfPossible()
    }

    func detach() {
        // Restore the system's own delegate so a reused presentation isn't left
        // pointing at a torn-down arbiter.
        if let dismissPan, dismissPan.delegate === self {
            dismissPan.delegate = originalDelegate
        }
        dismissPan = nil
        originalDelegate = nil
        webView = nil
        retries = 0
    }

    /// The sheet's dismiss pan only exists once the presentation is on-window.
    /// Poll a few frames (like WebViewHolder.startIfNeeded) until it appears.
    private func installIfPossible() {
        guard dismissPan == nil, let webView else { return }
        if let pan = Self.findSheetDismissPan(from: webView) {
            originalDelegate = pan.delegate
            pan.delegate = self
            dismissPan = pan
            return
        }
        retries += 1
        if retries > 40 { return } // ~2s; give up quietly, .interactiveDismissDisabled fallback still armed
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            self?.installIfPossible()
        }
    }

    /// Walk from the web view up to the window, collecting pan recognizers on
    /// ancestor views, and pick the one owned by the sheet presentation
    /// machinery. The dismiss pan lives on a presentation-container ancestor
    /// (not on the web view or its scrollView) and its delegate/target is a
    /// `UISheetPresentationController`-family object — identify by that class
    /// name so we never grab the web view's own scroll/zoom pans.
    private static func findSheetDismissPan(from webView: WKWebView) -> UIPanGestureRecognizer? {
        let scrollPan = webView.scrollView.panGestureRecognizer
        var node: UIView? = webView.superview
        while let view = node {
            let hostName = String(describing: type(of: view))
            for gr in view.gestureRecognizers ?? [] {
                guard let pan = gr as? UIPanGestureRecognizer, pan !== scrollPan else { continue }
                // Identify the sheet dismiss pan by its delegate class (a
                // UISheetPresentationController-family object), the recognizer's
                // own debug description (carries its target class names), or the
                // host view's class — all PUBLIC/safe reads, no fragile KVC.
                let delegateName = pan.delegate.map { String(describing: type(of: $0)) } ?? ""
                let panDesc = pan.description
                let looksSheet =
                    delegateName.contains("Sheet") || delegateName.contains("Dismiss")
                    || panDesc.contains("Sheet") || panDesc.contains("Dismiss")
                    || hostName.contains("Sheet") || hostName.contains("Dimming")
                if looksSheet { return pan }
            }
            node = view.superview
        }
        return nil
    }

    // MARK: UIGestureRecognizerDelegate (chained to originalDelegate)

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === dismissPan, let webView {
            // Synchronous veto: if the touch is inside the live web content,
            // the page owns this gesture — don't let the sheet dismiss begin.
            let loc = gestureRecognizer.location(in: webView)
            if webView.bounds.contains(loc) {
                return false
            }
        }
        // Anything else (or a touch outside the web content, e.g. the nav bar):
        // defer to the system's own decision.
        if let originalDelegate,
           originalDelegate.responds(to: #selector(UIGestureRecognizerDelegate.gestureRecognizerShouldBegin(_:))) {
            return originalDelegate.gestureRecognizerShouldBegin?(gestureRecognizer) ?? true
        }
        return true
    }

    // Forward the other delegate callbacks the system relies on, so chaining
    // our delegate in doesn't change the sheet pan's relationship with sibling
    // recognizers (scroll views etc.).
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        originalDelegate?.gestureRecognizer?(gestureRecognizer, shouldRecognizeSimultaneouslyWith: other) ?? false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRequireFailureOf other: UIGestureRecognizer) -> Bool {
        originalDelegate?.gestureRecognizer?(gestureRecognizer, shouldRequireFailureOf: other) ?? false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
        originalDelegate?.gestureRecognizer?(gestureRecognizer, shouldBeRequiredToFailBy: other) ?? false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldReceive touch: UITouch) -> Bool {
        originalDelegate?.gestureRecognizer?(gestureRecognizer, shouldReceive: touch) ?? true
    }
}

/// Installs a `SheetDismissArbiter` on the shared WKWebView's sheet dismiss pan.
/// Zero-size overlay — purely a hook onto the presentation gesture layer.
struct WebViewDismissArbiterGate: UIViewRepresentable {
    let webView: WKWebView

    func makeCoordinator() -> SheetDismissArbiter { SheetDismissArbiter() }

    func makeUIView(context: Context) -> UIView {
        context.coordinator.attach(to: webView)
        return UIView(frame: .zero)
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.attach(to: webView)
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: SheetDismissArbiter) {
        coordinator.detach()
    }
}

// MARK: - Safari-style Full-Screen Web Preview

/// Pure immersive fullscreen preview — no chrome at all. The user enters
/// this from the sheet preview's "..." menu (Fullscreen) and exits by
/// swiping down from the top edge; the host swaps back to the sheet
/// preview via `onCollapse` so all the menu actions (share, reload,
/// desktop-mode, …) are reachable again.
struct MinisSafariView: View {
    @StateObject private var holder: WebViewHolder
    let shareURL: URL
    let isLocalFile: Bool
    let onCollapse: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearanceMode") private var appearanceMode: Int = 0

    @State private var showShareSheet: Bool = false

    init(url: URL, localFile: Bool, onCollapse: (() -> Void)? = nil) {
        _holder = StateObject(wrappedValue: WebViewHolder(url: url, localFile: localFile))
        self.shareURL = url
        self.isLocalFile = localFile
        self.onCollapse = onCollapse
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ReusableWebView(webView: holder.webView)
                .onAppear {
                    holder.startIfNeeded()
                    // Immersive mode: take over the full screen, no
                    // status-bar / home-indicator insets. The chat sheet
                    // preview path shares the same WKWebView instance,
                    // so flip the inset behavior back when we leave.
                    holder.webView.scrollView.contentInsetAdjustmentBehavior = .never
                    holder.webView.scrollView.automaticallyAdjustsScrollIndicatorInsets = false
                }
                .onDisappear {
                    holder.webView.scrollView.contentInsetAdjustmentBehavior = .automatic
                    holder.webView.scrollView.automaticallyAdjustsScrollIndicatorInsets = true
                }
                .ignoresSafeArea()

            // [T-ios-webview-error-ui] Error overlay over the blank web view.
            if let err = holder.loadError {
                WebLoadErrorOverlay(error: err) { holder.retryFailedLoad() }
                    .ignoresSafeArea()
            }

            // Always-on floating "…" menu button in the bottom-right
            // corner. Doesn't toggle on accidental taps — the user has
            // to deliberately hit the 44pt target. Menu carries the
            // four immersive actions (exit fullscreen, reload, share,
            // close).
            menuButton
                .padding(.trailing, 18)
                .padding(.bottom, 18) // clears the home-indicator gutter
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(appearanceMode == 1 ? .light : appearanceMode == 2 ? .dark : nil)
        .sheet(isPresented: $showShareSheet) {
            MinisShareSheet(url: shareURL)
        }
    }

    @ViewBuilder
    private var menuButton: some View {
        Menu {
            // Exit fullscreen — only meaningful when there's a sheet
            // to return to. In standalone presentations this would
            // duplicate Close, so we omit it.
            if onCollapse != nil {
                Button {
                    exitFullscreen(returnToSheet: true)
                } label: {
                    Label(AppLocalized("Exit Fullscreen"), systemImage: "arrow.down.right.and.arrow.up.left")
                }
            }
            Button {
                reload()
            } label: {
                Label(AppLocalized("Reload"), systemImage: "arrow.clockwise")
            }
            Button {
                holder.printPage()
            } label: {
                Label(AppLocalized("Print"), systemImage: "printer")
            }
            Button {
                showShareSheet = true
            } label: {
                Label(AppLocalized("Share"), systemImage: "square.and.arrow.up")
            }
            Divider()
            Button(role: .destructive) {
                exitFullscreen(returnToSheet: false)
            } label: {
                Label(AppLocalized("Close"), systemImage: "xmark")
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(
                    Circle()
                        .fill(.ultraThinMaterial)
                )
                .overlay(
                    Circle()
                        .strokeBorder(Color.white.opacity(0.10), lineWidth: 0.5)
                )
                .shadow(color: .black.opacity(0.28), radius: 12, y: 4)
        }
        .accessibilityLabel("More")
    }

    private func reload() {
        if let url = holder.webView.url ?? URL(string: shareURL.absoluteString) {
            // For local files reload() can race when the resource was
            // re-bundled out from under us; loadFileURL is the safer
            // fallback that re-grants read-access too.
            if isLocalFile && url.isFileURL {
                // [T-ios-file-preview-stale-cache] Bypass WebKit's cache so a
                // manual Reload of an in-place-rewritten local file shows the
                // current disk bytes, not the cached body.
                if #available(iOS 15.0, *) {
                    var req = URLRequest(url: url)
                    req.cachePolicy = .reloadIgnoringLocalCacheData
                    holder.webView.loadFileRequest(req, allowingReadAccessTo: url.deletingLastPathComponent())
                } else {
                    holder.webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
                }
            } else {
                holder.webView.reload()
            }
        }
    }

    private func exitFullscreen(returnToSheet: Bool) {
        if returnToSheet, let onCollapse {
            onCollapse()
        } else {
            dismiss()
        }
    }
}

// MARK: - Sheet Link Preview

struct MinisLinkPreviewView: View {
    let url: URL
    let browserPool: BrowserTabPool?
    var onExpand: ((WebViewHolder) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearanceMode") private var appearanceMode: Int = 0
    @StateObject private var holder: WebViewHolder
    @State private var showShareSheet = false
    @State private var desktopMode: Bool = false

    init(url: URL, browserPool: BrowserTabPool?, onExpand: ((WebViewHolder) -> Void)? = nil) {
        self.url = url
        self.browserPool = browserPool
        self.onExpand = onExpand
        _holder = StateObject(wrappedValue: WebViewHolder(url: url, localFile: false))
    }

    var body: some View {
        NavigationStack {
            ReusableWebView(webView: holder.webView)
                // [T-webview-preview-swipe-dismiss] Arbitrate the sheet's
                // interactive-dismiss pan against page content at the gesture
                // layer (synchronous, no SwiftUI round-trip) so a fast flick
                // inside the content can't be hijacked into a dismiss.
                .background(
                    WebViewDismissArbiterGate(webView: holder.webView)
                        .frame(width: 0, height: 0)
                )
                // [T-ios-webview-error-ui] Safari-style error overlay + Retry.
                .overlay {
                    if let err = holder.loadError {
                        WebLoadErrorOverlay(error: err) { holder.retryFailedLoad() }
                    }
                }
                .onAppear { holder.startIfNeeded() }
                // Only ignore the keyboard inset — keeping the top safe area
                // so the NavigationStack's navigation bar reliably pushes
                // page content down instead of floating over it. Used to be
                // `.ignoresSafeArea()`: inside a `.sheet` that happened to
                // work because the sheet chrome provided the inset anyway,
                // but inside a `.fullScreenCover` (see ToolLiveSheet's
                // `$linkPreviewURL` path) the full-screen hosting controller
                // laid the web view out under the toolbar, so the ChatGPT
                // header etc. ended up hidden behind our own toolbar.
                .ignoresSafeArea(.keyboard)
                .navigationTitle(holder.pageTitle.isEmpty ? (url.host ?? url.absoluteString) : holder.pageTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { dismiss() } label: {
                            Image(systemName: "xmark")
                        }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        WebPreviewMoreMenu(
                            url: url,
                            holder: holder,
                            allowOpenInSafari: true,
                            desktopMode: $desktopMode,
                            showShareSheet: $showShareSheet,
                            onExpand: { onExpand?(holder) }
                        )
                    }
                }
        }
        .presentationDetents([.large])
        // [T-ios-html-preview-wide-sheet] Widen to a page-style sheet on
        // iPad/Mac, reusing the shared modifier from AIChatView.swift. iPhone
        // unaffected (presentationSizing is iOS18+ and .page only affects
        // iPad/Mac form sheets).
        .modifier(WideSheetSizingModifier())
        .presentationDragIndicator(.hidden)
        // [T-webview-preview-swipe-dismiss] Interactive-dismiss arbitration is
        // now done at the gesture layer by WebViewDismissArbiterGate (above),
        // which vetoes the sheet dismiss pan synchronously when the touch is on
        // page content — fast flicks included. No `.interactiveDismissDisabled`
        // binding here: that SwiftUI round-trip was too slow to catch a flick,
        // and leaving dismiss enabled preserves nav-bar pull-down + xmark.
        .preferredColorScheme(appearanceMode == 1 ? .light : appearanceMode == 2 ? .dark : nil)
        .sheet(isPresented: $showShareSheet) {
            MinisShareSheet(url: url)
        }
    }
}
