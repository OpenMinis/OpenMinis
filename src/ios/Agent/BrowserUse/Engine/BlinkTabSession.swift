import Foundation
import UIKit
import os.log

private let blinkLog = AppLogger(category: "BlinkTab")

// MARK: - Blink Web View

/// A UIView that hosts the Blink-rendered page for one tab.
/// Talks to the embedded `content_shell_framework` through `BlinkEngineBridge`.
@MainActor
final class BlinkWebView: UIView {

    /// Engine-side handle (`void*` from BlinkBridgeCreateView).
    private(set) var engineHandle: UnsafeMutableRawPointer?

    // MARK: State mirror (drives BrowserUseManager's published state)

    var currentURL: String = ""
    var pageTitle: String = ""
    var isLoading: Bool = false
    var canGoBack: Bool = false
    var canGoForward: Bool = false

    /// Fired on engine events (navigation state / title / URL / errors).
    var onStateChange: ((_ url: String?, _ title: String?, _ loading: Bool?) -> Void)?
    /// Fired when a page navigation fails (DNS/TLS/timeout).
    var onLoadFailed: ((_ url: String?, _ error: String?) -> Void)?
    /// Fired when the engine blocks a window.open / target=_blank popup.
    var onWindowOpenBlocked: ((_ url: String?) -> Void)?
    /// Fired when the Blink renderer/browser process died (usually OOM).
    var onRenderProcessGone: (() -> Void)?

    private var pendingEvalCompletions: [UInt64: (Any?, Error?) -> Void] = [:]
    private var nextEvalID: UInt64 = 0

    // MARK: Init

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        backgroundColor = .white
        createEngineView()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        clipsToBounds = true
        backgroundColor = .white
        createEngineView()
    }

    deinit {
        if let handle = engineHandle {
            BlinkEngineBridge.destroyViewFromDeinit(handle)
            engineHandle = nil
        }
    }

    private func createEngineView() {
        guard let handle = BlinkEngineBridge.createView(width: bounds.width, height: bounds.height) else {
            blinkLog.error("BlinkBridgeCreateView failed")
            return
        }
        engineHandle = handle
        BlinkEngineBridge.setEventCallback(handle) { [weak self] payload in
            Task { @MainActor [weak self] in
                self?.handleBridgeEvent(payload)
            }
        }
        refreshState()
    }

    // MARK: Layout

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let handle = engineHandle, bounds.width > 0, bounds.height > 0 else { return }
        BlinkEngineBridge.setViewFrame(handle, x: 0, y: 0, width: bounds.width, height: bounds.height)
    }

    // MARK: Navigation API

    func loadURL(_ urlString: String) {
        guard let handle = engineHandle else { return }
        currentURL = urlString
        isLoading = true
        BlinkEngineBridge.loadURL(handle, urlString)
    }

    func goBack() { guard let h = engineHandle else { return }; BlinkEngineBridge.goBack(h); refreshState() }
    func goForward() { guard let h = engineHandle else { return }; BlinkEngineBridge.goForward(h); refreshState() }
    func reload() { guard let h = engineHandle else { return }; BlinkEngineBridge.reload(h) }
    func stopLoading() { guard let h = engineHandle else { return }; BlinkEngineBridge.stop(h) }
    func setUserAgent(_ ua: String) { guard let h = engineHandle else { return }; BlinkEngineBridge.setUserAgent(h, ua) }

    // MARK: JS Evaluation

    /// Evaluate JS in the main world with a timeout. `result` is the
    /// JSON-decoded value (string/number/bool/array/object) or nil.
    func evaluateJavaScript(_ js: String, timeout: TimeInterval = 10) async throws -> Any? {
        guard let handle = engineHandle else {
            throw BlinkBridgeError.notLoaded
        }
        let id = nextEvalID
        nextEvalID += 1

        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Any?, Error>) in
            // Thread-safe one-shot gate: exactly one of {eval completion, timer}
            // may resume the continuation (callbacks and the wall-clock timer
            // race on different threads).
            let gate = EvalGate()
            let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
            timer.schedule(deadline: .now() + timeout)
            timer.setEventHandler { [weak self] in
                guard gate.markDone() else { return }
                Task { @MainActor [weak self] in
                    self?.pendingEvalCompletions.removeValue(forKey: id)
                    cont.resume(throwing: JSEvalTimeout())
                }
            }
            pendingEvalCompletions[id] = { value, error in
                guard gate.markDone() else { return }
                timer.cancel()
                if let error {
                    cont.resume(throwing: error)
                } else {
                    cont.resume(returning: value)
                }
            }
            timer.resume()

            BlinkEngineBridge.evaluateJavaScript(handle, js) { [weak self] value, error in
                Task { @MainActor [weak self] in
                    self?.pendingEvalCompletions[id]?(value, error)
                }
            }
        }
    }

    // MARK: Snapshot

    func captureSnapshot() async throws -> UIImage {
        guard let handle = engineHandle else {
            throw BlinkBridgeError.notLoaded
        }
        let data = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            BlinkEngineBridge.captureSnapshot(handle) { data, error in
                if let data {
                    cont.resume(returning: data)
                } else {
                    cont.resume(throwing: error ?? BlinkBridgeError.snapshotFailed)
                }
            }
        }
        guard let image = UIImage(data: data) else {
            throw BlinkBridgeError.snapshotFailed
        }
        return image
    }

    // MARK: Events

    private func handleBridgeEvent(_ payload: BlinkEngineBridge.BridgeEventPayload) {
        switch payload.event {
        case .didStartProvisionalNavigation:
            isLoading = true
            if let url = payload.url { currentURL = url }
            onStateChange?(currentURL, nil, true)

        case .didFinishNavigation:
            isLoading = false
            refreshState()
            onStateChange?(currentURL, pageTitle, false)

        case .didFailNavigation:
            isLoading = false
            onLoadFailed?(payload.url ?? currentURL, payload.error)

        case .titleChanged:
            if let title = payload.title {
                pageTitle = title
            }
            onStateChange?(nil, pageTitle, nil)

        case .urlChanged:
            if let url = payload.url {
                currentURL = url
            }
            onStateChange?(currentURL, nil, nil)

        case .loadingStateChanged:
            if let loading = payload.loading {
                isLoading = loading
            }
            onStateChange?(nil, nil, isLoading)

        case .windowOpenBlocked:
            onWindowOpenBlocked?(payload.url)

        case .renderProcessGone:
            onRenderProcessGone?()
        }
        refreshState()
    }

    private func refreshState() {
        guard let handle = engineHandle else { return }
        let url = BlinkEngineBridge.getURL(handle)
        if !url.isEmpty { currentURL = url }
        let title = BlinkEngineBridge.getTitle(handle)
        if !title.isEmpty { pageTitle = title }
        canGoBack = BlinkEngineBridge.canGoBack(handle)
        canGoForward = BlinkEngineBridge.canGoForward(handle)
        isLoading = BlinkEngineBridge.isLoading(handle)
    }
}

struct JSEvalTimeout: LocalizedError {
    var errorDescription: String? { "JS 执行超时" }
}

/// Thread-safe one-shot gate for eval/timeout races.
final class EvalGate: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func markDone() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

// MARK: - Blink Tab Session

/// One Blink tab's runtime state + agent-facing helpers, owned by
/// `BrowserEngineCoordinator` (one per `BrowserUseManager`).
@MainActor
final class BlinkTabSession {

    let webView: BlinkWebView

    /// Closures the manager wires in to keep its @Published state in sync.
    var onURLChange: ((String) -> Void)?
    var onTitleChange: ((String) -> Void)?
    var onLoadingChange: ((Bool) -> Void)?
    var onBackForwardChange: ((Bool, Bool) -> Void)?
    var onLoadFailed: ((String?, String?) -> Void)?
    var onWindowOpenBlocked: ((String?) -> Void)?

    /// JS injected at document start for desktop viewport emulation (mirrors
    /// the WKWebView path's desktopViewportScript).
    private static let desktopViewportScript: (Int) -> String = { width in
        """
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
    }

    init(frame: CGRect, userAgent: String) {
        let wv = BlinkWebView(frame: frame)
        self.webView = wv
        wv.setUserAgent(userAgent)
        wv.onStateChange = { [weak self] url, title, loading in
            guard let self else { return }
            if let url { self.onURLChange?(url) }
            if let title { self.onTitleChange?(title) }
            if let loading { self.onLoadingChange?(loading) }
            self.onBackForwardChange?(wv.canGoBack, wv.canGoForward)
        }
        wv.onLoadFailed = { [weak self] url, error in
            self?.onLoadFailed?(url, error)
        }
        wv.onWindowOpenBlocked = { [weak self] url in
            self?.onWindowOpenBlocked?(url)
        }
    }

    func loadURL(_ urlString: String) {
        webView.loadURL(urlString)
    }

    /// Apply desktop/mobile viewport emulation for the current page.
    func applyViewportEmulation(desktop: Bool, width: Int) {
        let js = desktop ? Self.desktopViewportScript(width) : """
        (function() {
            var m = document.createElement('meta');
            m.name = 'viewport';
            m.content = 'width=device-width, initial-scale=1.0';
            (document.head || document.documentElement).appendChild(m);
        })();
        """
        Task {
            try? await webView.evaluateJavaScript(js, timeout: 3)
        }
    }
}
