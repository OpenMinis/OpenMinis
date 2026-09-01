import UIKit
import WebKit

private let logger = AppLogger(category: "KaTeXRenderer")

/// Renders LaTeX math formulas to UIImage using an offscreen WKWebView with KaTeX.
final class KaTeXRenderer: NSObject {
    static let shared = KaTeXRenderer()

    private var webView: WKWebView?
    private var isReady = false

    /// Cache rendered images keyed by "D:latex" or "I:latex".
    private let cache = NSCache<NSString, KaTeXRenderResult>()

    /// Pending render request awaiting JS callback.
    private var pendingCompletion: ((UIImage?, CGSize) -> Void)?
    private var pendingTimeout: DispatchWorkItem?

    /// Queued render requests.
    private var requestQueue: [(latex: String, displayMode: Bool, fontSize: CGFloat, completion: (UIImage?, CGSize) -> Void)] = []
    private var isRendering = false

    /// CSS font size in points for rendered math. Matches the chat theme base font.
    static let defaultFontSize: CGFloat = 17

    override private init() {
        cache.countLimit = 200
        super.init()
    }

    // MARK: - Public API

    /// Pre-create the WKWebView and load KaTeX so first render is instant.
    func warmUp() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.webView == nil else { return }
            self.setupWebView()
        }
    }

    /// Render a LaTeX formula to a UIImage. Calls completion on main thread.
    func render(latex: String, displayMode: Bool, fontSize: CGFloat = KaTeXRenderer.defaultFontSize, completion: @escaping (UIImage?, CGSize) -> Void) {
        let key = cacheKey(latex: latex, displayMode: displayMode, fontSize: fontSize)
        if let cached = cache.object(forKey: key as NSString) {
            DispatchQueue.main.async { completion(cached.image, cached.size) }
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.requestQueue.append((latex, displayMode, fontSize, completion))
            self.processNextRequest()
        }
    }

    /// Render with automatic caching.
    func renderCached(latex: String, displayMode: Bool, fontSize: CGFloat = KaTeXRenderer.defaultFontSize, completion: @escaping (UIImage?, CGSize) -> Void) {
        let key = cacheKey(latex: latex, displayMode: displayMode, fontSize: fontSize)
        if let cached = cache.object(forKey: key as NSString) {
            DispatchQueue.main.async { completion(cached.image, cached.size) }
            return
        }
        render(latex: latex, displayMode: displayMode, fontSize: fontSize) { [weak self] image, size in
            if let image, let self {
                let result = KaTeXRenderResult(image: image, size: size)
                self.cache.setObject(result, forKey: key as NSString)
            }
            completion(image, size)
        }
    }

    // MARK: - Internal

    private func processNextRequest() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !isRendering, !requestQueue.isEmpty else { return }

        if webView == nil {
            setupWebView()
            return
        }
        guard isReady else { return }

        isRendering = true
        let req = requestQueue.removeFirst()
        executeRender(latex: req.latex, displayMode: req.displayMode, fontSize: req.fontSize, completion: req.completion)
    }

    private func setupWebView() {
        let config = WKWebViewConfiguration()
        let contentController = WKUserContentController()
        contentController.add(self, name: "mathRendered")
        config.userContentController = contentController
        // [T-ios-mac-uncaught-nsexception] Undocumented WKPreferences KVC key —
        // NSUnknownKeyException if a WebKit release drops it, and Swift cannot catch an
        // ObjC exception, so it would take the process down. This site is the riskiest of
        // the two: `warmUp()` dispatches it onto the main queue from MinisApp.init(), so
        // it runs during startup on a plain runloop turn — exactly the shape of the two
        // macOS 27 crashes (NSApplicationMain → -[NSApplication run], no frame of ours).
        // If the key is gone, KaTeX simply loses local-file access (SwiftMath still
        // renders); losing the app is not an acceptable alternative.
        SafeKVCSetTrue.apply(config.preferences, key: "allowFileAccessFromFileURLs")

        // Large initial frame so KaTeX has room to lay out; we crop to content before snapshot.
        // WKWebView must be part of a visible view hierarchy for takeSnapshot to produce
        // non-blank images on iOS 16+. We hide it visually with alpha=0 and keep it
        // non-interactive so it doesn't interfere with the UI.
        // Position offscreen instead of alpha=0 — iOS skips rendering/compositing
        // for near-invisible views, causing takeSnapshot to return blank images.
        let wv = WKWebView(frame: CGRect(x: -2048, y: -2048, width: 1024, height: 256), configuration: config)
        wv.isOpaque = false
        wv.backgroundColor = .clear
        wv.scrollView.backgroundColor = .clear
        wv.isUserInteractionEnabled = false
        wv.navigationDelegate = self
        self.webView = wv

        // Attach to the key window so takeSnapshot has a live rendering context.
        attachToWindow(wv)

        guard let htmlURL = Bundle.main.url(forResource: "katex-render", withExtension: "html", subdirectory: "KaTeX") else {
            logger.error("katex-render.html not found in bundle")
            drainQueueWithError()
            return
        }
        let katexDir = htmlURL.deletingLastPathComponent()
        wv.loadFileURL(htmlURL, allowingReadAccessTo: katexDir)
    }

    private func executeRender(latex: String, displayMode: Bool, fontSize: CGFloat, completion: @escaping (UIImage?, CGSize) -> Void) {
        guard let wv = webView else {
            isRendering = false
            completion(nil, .zero)
            processNextRequest()
            return
        }

        pendingCompletion = completion

        let timeout = DispatchWorkItem { [weak self] in
            guard let self else { return }
            DispatchQueue.main.async {
                logger.warning("render timeout for: \(latex.prefix(50))")
                self.finishRender(image: nil, size: .zero)
            }
        }
        pendingTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0, execute: timeout)

        let escapedLatex = latex
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "")
        // Detect dark mode and pass text color explicitly — the WKWebView's
        // @media (prefers-color-scheme) may not match the host app's trait.
        // Always render black text — takeSnapshot renders transparent bg as white,
        // so white text becomes invisible. We invert the image in dark mode instead.
        let js = "renderMath('\(escapedLatex)', \(displayMode), \(Int(fontSize)))"
        wv.evaluateJavaScript(js) { _, error in
            if let error {
                logger.error("JS error: \(error.localizedDescription)")
            }
        }
    }

    private func finishRender(image: UIImage?, size: CGSize) {
        dispatchPrecondition(condition: .onQueue(.main))
        pendingTimeout?.cancel()
        pendingTimeout = nil
        let completion = pendingCompletion
        pendingCompletion = nil
        isRendering = false
        completion?(image, size)
        processNextRequest()
    }

    private func cacheKey(latex: String, displayMode: Bool, fontSize: CGFloat) -> String {
        let isDark = UITraitCollection.current.userInterfaceStyle == .dark
        return (displayMode ? "D:" : "I:") + "\(Int(fontSize)):" + (isDark ? "dk:" : "lt:") + latex
    }

    /// Attach the WKWebView to the app's key window. If no window is available yet
    /// (e.g. during app launch), retry after a short delay.
    private func attachToWindow(_ wv: WKWebView, retries: Int = 10) {
        if let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
           let keyWindow = windowScene.windows.first(where: { $0.isKeyWindow }) ?? windowScene.windows.first {
            keyWindow.insertSubview(wv, at: 0)
            logger.info("WKWebView attached to window")
        } else if retries > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self, weak wv] in
                guard let wv else { return }
                self?.attachToWindow(wv, retries: retries - 1)
            }
        } else {
            logger.warning("no window available for WKWebView — snapshots may fail")
        }
    }

    private func drainQueueWithError() {
        let queued = requestQueue
        requestQueue.removeAll()
        for req in queued {
            DispatchQueue.main.async { req.completion(nil, .zero) }
        }
    }
}

// MARK: - WKNavigationDelegate

extension KaTeXRenderer: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        isReady = true
        processNextRequest()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        logger.error("webView didFail: \(error.localizedDescription)")
        isReady = false
        drainQueueWithError()
    }
}

// MARK: - WKScriptMessageHandler

extension KaTeXRenderer: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "mathRendered",
              let body = message.body as? [String: Any] else { return }

        if let errorMsg = body["error"] as? String {
            logger.error("render error: \(errorMsg)")
            finishRender(image: nil, size: .zero)
            return
        }

        let cssWidth = (body["width"] as? CGFloat) ?? (body["width"] as? Int).map { CGFloat($0) } ?? 0
        let cssHeight = (body["height"] as? CGFloat) ?? (body["height"] as? Int).map { CGFloat($0) } ?? 0

        guard cssWidth > 0, cssHeight > 0, let wv = webView else {
            finishRender(image: nil, size: .zero)
            return
        }

        // Resize the webview to exactly the content size so the snapshot is tight.
        wv.frame = CGRect(x: -2048, y: -2048, width: cssWidth, height: cssHeight)

        // Use device screen scale for crisp retina rendering
        let scale = UIScreen.main.scale
        let snapshotConfig = WKSnapshotConfiguration()
        snapshotConfig.rect = CGRect(origin: .zero, size: CGSize(width: cssWidth, height: cssHeight))
        // afterScreenUpdates ensures the resized frame is applied before capture
        snapshotConfig.afterScreenUpdates = true

        wv.takeSnapshot(with: snapshotConfig) { [weak self] snapshotImage, error in
            guard let self else { return }

            // Restore the webview to full size offscreen for the next render
            self.webView?.frame = CGRect(x: -2048, y: -2048, width: 1024, height: 256)

            if let error {
                logger.error("snapshot error: \(error.localizedDescription)")
                self.finishRender(image: nil, size: .zero)
                return
            }

            guard let snapshotImage else {
                self.finishRender(image: nil, size: .zero)
                return
            }

            // The snapshot image pixel size = cssWidth * deviceScale, cssHeight * deviceScale.
            // Create UIImage at device scale so it renders at correct point size.
            let pointSize = CGSize(width: cssWidth, height: cssHeight)
            logger.info("snapshot OK \(Int(cssWidth))x\(Int(cssHeight)) scale=\(Int(scale))")
            if let cg = snapshotImage.cgImage {
                logger.info("cgImage: \(cg.width)x\(cg.height) bpc=\(cg.bitsPerComponent) bpp=\(cg.bitsPerPixel) bpr=\(cg.bytesPerRow) alphaInfo=\(cg.alphaInfo.rawValue) colorSpace=\(cg.colorSpace?.name ?? "nil" as CFString)")
                var finalImage = UIImage(cgImage: cg, scale: scale, orientation: .up)
                // In dark mode, invert the black-on-white snapshot to white-on-black,
                // then make the background transparent.
                // Debug: sample some pixels from the raw snapshot
                if let dp = cg.dataProvider, let data = dp.data, let ptr = CFDataGetBytePtr(data) {
                    let bpr = cg.bytesPerRow
                    let midY = cg.height / 2
                    // Sample pixel at (10, midY) and (width/2, midY)
                    let off1 = midY * bpr + 10 * (cg.bitsPerPixel / 8)
                    let off2 = midY * bpr + (cg.width / 2) * (cg.bitsPerPixel / 8)
                    let bpp = cg.bitsPerPixel / 8
                    if off1 + bpp <= CFDataGetLength(data) && off2 + bpp <= CFDataGetLength(data) {
                        logger.info("pixel@(10,\(midY)): \(ptr[off1]),\(ptr[off1+1]),\(ptr[off1+2]),\(ptr[off1+3])  pixel@(\(cg.width/2),\(midY)): \(ptr[off2]),\(ptr[off2+1]),\(ptr[off2+2]),\(ptr[off2+3])")
                    }
                }
                let isDark = UITraitCollection.current.userInterfaceStyle == .dark
                if isDark, let inverted = Self.invertAndTransparentize(cg, scale: scale) {
                    finalImage = inverted
                } else if let transparent = Self.makeWhiteTransparent(cg, scale: scale) {
                    // Light mode: just make white background transparent.
                    finalImage = transparent
                }
                self.finishRender(image: finalImage, size: pointSize)
            } else {
                self.finishRender(image: snapshotImage, size: pointSize)
            }
        }
    }

    // MARK: - Image Processing

    /// Invert colors (black→white) and make the background transparent.
    /// Used for dark mode: snapshot is black text on white bg → white text on clear bg.
    private static func invertAndTransparentize(_ cgImage: CGImage, scale: CGFloat) -> UIImage? {
        let w = cgImage.width, h = cgImage.height
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let data = ctx.data else { return nil }
        let pixels = data.bindMemory(to: UInt8.self, capacity: w * h * 4)
        for i in stride(from: 0, to: w * h * 4, by: 4) {
            let r = pixels[i], g = pixels[i+1], b = pixels[i+2]
            // Compute luminance — white (255,255,255) → alpha=0, black (0,0,0) → alpha=255
            let lum = (Int(r) + Int(g) + Int(b)) / 3
            let alpha = UInt8(255 - lum)
            // Premultiplied: store color * alpha / 255. Color is white (255).
            let pm = alpha  // 255 * alpha / 255 = alpha
            pixels[i]   = pm
            pixels[i+1] = pm
            pixels[i+2] = pm
            pixels[i+3] = alpha
        }
        guard let result = ctx.makeImage() else { return nil }
        return UIImage(cgImage: result, scale: scale, orientation: .up)
    }

    /// Make white/near-white pixels transparent for light mode.
    private static func makeWhiteTransparent(_ cgImage: CGImage, scale: CGFloat) -> UIImage? {
        let w = cgImage.width, h = cgImage.height
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let data = ctx.data else { return nil }
        let pixels = data.bindMemory(to: UInt8.self, capacity: w * h * 4)
        for i in stride(from: 0, to: w * h * 4, by: 4) {
            let r = pixels[i], g = pixels[i+1], b = pixels[i+2]
            let lum = (Int(r) + Int(g) + Int(b)) / 3
            // White → transparent, dark → opaque
            let alpha = UInt8(255 - lum)
            // Premultiply: store color * alpha / 255
            pixels[i]   = UInt8(Int(r) * Int(alpha) / 255)
            pixels[i+1] = UInt8(Int(g) * Int(alpha) / 255)
            pixels[i+2] = UInt8(Int(b) * Int(alpha) / 255)
            pixels[i+3] = alpha
        }
        guard let result = ctx.makeImage() else { return nil }
        return UIImage(cgImage: result, scale: scale, orientation: .up)
    }
}

// MARK: - Cache Result

final class KaTeXRenderResult: NSObject {
    let image: UIImage
    let size: CGSize

    init(image: UIImage, size: CGSize) {
        self.image = image
        self.size = size
    }
}
