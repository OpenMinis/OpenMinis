import Foundation
import UIKit
import os.log

private let coordLog = AppLogger(category: "EngineCoord")

// MARK: - BrowserEngineCoordinator

/// Facade used by `BrowserUseManager` to route browser work to the active
/// engine (`.webkit` = the manager's own WKWebView path, untouched; `.blink` =
/// embedded Chromium; `.ssr` = server-side rendered fetch).
///
/// The manager keeps its full agent logic; only the raw web calls (load / JS /
/// snapshot / UA / viewport / cookies / fetch) go through this coordinator.
@MainActor
final class BrowserEngineCoordinator {

    let kind: BrowserEngineKind

    /// The Blink session, non-nil only when kind == .blink and init succeeded.
    private(set) var blinkSession: BlinkTabSession?

    /// SSR manager, non-nil only when kind == .ssr.
    private(set) var ssr: SSRManager?

    /// UIView to embed in the browser sheet: Blink view / SSR placeholder / nil (webkit).
    var renderView: UIView? {
        blinkSession?.webView ?? ssrPlaceholderView
    }

    /// Placeholder shown in SSR mode (the browser sheet still renders a surface).
    private var ssrPlaceholderView: UIView?

    // MARK: Manager state sync (wired by BrowserUseManager)

    var onURLChange: ((String) -> Void)?
    var onTitleChange: ((String) -> Void)?
    var onLoadingChange: ((Bool) -> Void)?
    var onBackForwardChange: ((Bool, Bool) -> Void)?
    var onLoadFailed: ((String?, String?) -> Void)?
    var onWindowOpenBlocked: ((String?) -> Void)?

    /// The effective UA string (used by Blink and SSR raw fetch).
    let userAgent: String

    // MARK: Init

    /// - Parameters:
    ///   - kind: requested engine kind (`.blink` downgrades to `.webkit` if
    ///     the framework is missing or init fails).
    ///   - frame: initial view frame.
    ///   - userAgent: UA string for Blink + SSR.
    ///   - desktop: whether the profile wants desktop layout.
    ///   - viewportSize: logical viewport (w, h).
    init(kind: BrowserEngineKind,
         frame: CGRect,
         userAgent: String,
         desktop: Bool,
         viewportSize: (width: Int, height: Int)) {
        self.userAgent = userAgent

        switch kind {
        case .blink:
            if BlinkEngineBridge.isFrameworkPresent() {
                if !BlinkEngineBridge.initialize() {
                    coordLog.error("Blink init failed — falling back to WebKit")
                    self.kind = .webkit
                    return
                }
                let session = BlinkTabSession(frame: frame, userAgent: userAgent)
                self.blinkSession = session
                self.kind = .blink
                wireBlink(session)
            } else {
                coordLog.info("Blink framework absent — falling back to WebKit")
                self.kind = .webkit
            }

        case .ssr:
            self.ssr = SSRManager()
            self.kind = .ssr
            let placeholder = UIView(frame: frame)
            placeholder.backgroundColor = .systemBackground
            let label = UILabel()
            label.text = "SSR 模式：无实时渲染\nagent 将获取页面文本内容"
            label.numberOfLines = 0
            label.textAlignment = .center
            label.textColor = .secondaryLabel
            label.font = .systemFont(ofSize: 15)
            label.translatesAutoresizingMaskIntoConstraints = false
            placeholder.addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: placeholder.centerXAnchor),
                label.centerYAnchor.constraint(equalTo: placeholder.centerYAnchor),
                label.leadingAnchor.constraint(greaterThanOrEqualTo: placeholder.leadingAnchor, constant: 24),
                label.trailingAnchor.constraint(lessThanOrEqualTo: placeholder.trailingAnchor, constant: -24)
            ])
            self.ssrPlaceholderView = placeholder

        case .webkit:
            self.kind = .webkit
        }
    }

    private func wireBlink(_ session: BlinkTabSession) {
        session.onURLChange = { [weak self] url in self?.onURLChange?(url) }
        session.onTitleChange = { [weak self] t in self?.onTitleChange?(t) }
        session.onLoadingChange = { [weak self] l in self?.onLoadingChange?(l) }
        session.onBackForwardChange = { [weak self] b, f in self?.onBackForwardChange?(b, f) }
        session.onLoadFailed = { [weak self] url, err in self?.onLoadFailed?(url, err) }
        session.onWindowOpenBlocked = { [weak self] url in self?.onWindowOpenBlocked?(url) }
    }

    // MARK: Navigation

    func navigate(to urlString: String) async throws -> String {
        switch kind {
        case .blink:
            guard let session = blinkSession else { throw BlinkBridgeError.notLoaded }
            session.loadURL(urlString)
            return try await waitForNavigation(session)

        case .ssr:
            guard let ssr else { throw SSRManagerError.allSourcesFailed }
            let page = try await ssr.navigate(to: urlString)
            onURLChange?(page.url)
            onTitleChange?(page.title)
            onLoadingChange?(false)
            return "[SSR \(page.source)] \(page.title) — \(page.url)\n\n\(page.markdown.prefix(3000))"

        case .webkit:
            // WebKit path is handled by the manager itself; this is unreachable.
            throw EngineCoordinatorError.webkitPathUnreachable
        }
    }

    /// Wait for didFinish/didFail or timeout (mirrors the WKWebView nav wait).
    private func waitForNavigation(_ session: BlinkTabSession) async throws -> String {
        let timeout: TimeInterval = 30
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !session.webView.isLoading {
                // Settle briefly so title/URL events flush through.
                try? await Task.sleep(nanoseconds: 150_000_000)
                return try await navigationSummary(session)
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return try await navigationSummary(session)
    }

    /// Build the navigate() result text (title/url/snippet) — Blink version of
    /// the manager's `navigationMetadata()`.
    private func navigationSummary(_ session: BlinkTabSession) async throws -> String {
        let js = """
        (function() {
            var h1 = document.querySelector('h1');
            var p = document.querySelector('p');
            var desc = document.querySelector('meta[name="description"]');
            var text = (document.body ? document.body.innerText : '').trim();
            return JSON.stringify({
                title: document.title || '',
                url: location.href || '',
                heading: h1 ? h1.innerText.trim().slice(0, 200) : '',
                paragraph: p ? p.innerText.trim().slice(0, 300) : '',
                textLength: text.length,
                snippet: text.slice(0, 800)
            });
        })()
        """
        let result = try? await session.webView.evaluateJavaScript(js, timeout: 5)
        var parts: [String] = []
        if let dict = result as? [String: Any] {
            if let title = dict["title"] as? String, !title.isEmpty { parts.append("**\(title)**") }
            if let url = dict["url"] as? String, !url.isEmpty { parts.append("URL: \(url)") }
            if let heading = dict["heading"] as? String, !heading.isEmpty { parts.append("H1: \(heading)") }
            if let paragraph = dict["paragraph"] as? String, !paragraph.isEmpty { parts.append(paragraph) }
            if let snippet = dict["snippet"] as? String, !snippet.isEmpty { parts.append("...\(snippet)...") }
            if let len = dict["textLength"] as? Int { parts.append("(页面文本 \(len) 字符)") }
        }
        if parts.isEmpty {
            return "已加载 \(session.webView.currentURL)"
        }
        return parts.joined(separator: "\n")
    }

    // MARK: JS evaluation

    func evaluateJavaScript(_ js: String, timeout: TimeInterval = 10) async throws -> Any? {
        switch kind {
        case .blink:
            guard let session = blinkSession else { throw BlinkBridgeError.notLoaded }
            return try await session.webView.evaluateJavaScript(js, timeout: timeout)
        case .ssr:
            throw EngineCoordinatorError.ssrNoJavaScript
        case .webkit:
            throw EngineCoordinatorError.webkitPathUnreachable
        }
    }

    // MARK: Snapshot

    func takeScreenshot() async throws -> UIImage {
        switch kind {
        case .blink:
            guard let session = blinkSession else { throw BlinkBridgeError.notLoaded }
            return try await session.webView.captureSnapshot()
        case .ssr:
            return UIImage.ssrPlaceholder(text: ssr?.currentPage?.markdown ?? "SSR 模式：无实时渲染")
        case .webkit:
            throw EngineCoordinatorError.webkitPathUnreachable
        }
    }

    // MARK: Controls

    func goBack() {
        if kind == .blink { blinkSession?.webView.goBack() }
    }

    func goForward() {
        if kind == .blink { blinkSession?.webView.goForward() }
    }

    func reload() {
        if kind == .blink { blinkSession?.webView.reload() }
    }

    func stopLoading() {
        if kind == .blink { blinkSession?.webView.stopLoading() }
        if kind == .ssr { onLoadingChange?(false) }
    }

    func setUserAgent(_ ua: String) {
        if kind == .blink { blinkSession?.webView.setUserAgent(ua) }
    }

    func applyViewport(desktop: Bool, width: Int) {
        if kind == .blink {
            blinkSession?.applyViewportEmulation(desktop: desktop, width: width)
        }
    }

    // MARK: Fetch (engine-independent)

    /// Download a resource via URLSession (works for blink/ssr; webkit uses
    /// the manager's in-page fetch path).
    func fetchData(from url: URL) async throws -> (data: Data, response: URLResponse) {
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        return try await URLSession.shared.data(for: request)
    }

    // MARK: Page info

    func currentPageInfo() -> (url: String, title: String, isLoading: Bool, canGoBack: Bool, canGoForward: Bool) {
        switch kind {
        case .blink:
            guard let wv = blinkSession?.webView else { return ("", "", false, false, false) }
            return (wv.currentURL, wv.pageTitle, wv.isLoading, wv.canGoBack, wv.canGoForward)
        case .ssr:
            let page = ssr?.currentPage
            return (page?.url ?? "", page?.title ?? "", false, false, false)
        case .webkit:
            return ("", "", false, false, false)
        }
    }

    // MARK: Teardown

    func teardown() {
        blinkSession = nil
        ssr = nil
        ssrPlaceholderView = nil
    }
}

enum EngineCoordinatorError: LocalizedError {
    case webkitPathUnreachable
    case ssrNoJavaScript

    var errorDescription: String? {
        switch self {
        case .webkitPathUnreachable: return "WebKit 路径由 manager 直接处理（协调器不应被调用）"
        case .ssrNoJavaScript: return "SSR 模式不支持执行 JavaScript（无浏览器进程）。请切换 WebKit/Blink 引擎后重试。"
        }
    }
}
