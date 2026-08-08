import Foundation
import UIKit
import os.log

private let engineLogger = AppLogger(category: "BlinkBridge")

// MARK: - C API (blink_bridge.h, compiled into content_shell_framework.framework)
//
// All functions are called on the main thread. Callbacks fire on the main
// thread (the C side hops to the main queue before invoking them).
// Result payloads are JSON strings; see BlinkBridgeEvent parsing below.

// MARK: - Bridge Availability

/// dlopen/dlsym wrapper around the `blink_bridge` C API exported by
/// `content_shell_framework.framework` (the Chromium Blink engine built by CI).
///
/// The framework is embedded ONLY in the Blink/TrollStore build; this wrapper
/// is safe to call from any build — `isFrameworkPresent()` returns false when
/// the framework is absent and every other method is a no-op/failure.
final class BlinkEngineBridge {

    // MARK: Typealiases

    typealias EventCallback = @convention(c) (UnsafeMutableRawPointer?, UnsafePointer<CChar>?) -> Void

    // MARK: Symbols (lazily resolved)

    private struct Symbols {
        let initialize: @convention(c) (UnsafePointer<CChar>?, UnsafePointer<CChar>?) -> Int32
        let createView: @convention(c) (Int32, Int32, Int32, Int32) -> UnsafeMutableRawPointer?
        let setViewFrame: @convention(c) (UnsafeMutableRawPointer?, Int32, Int32, Int32, Int32) -> Void
        let loadURL: @convention(c) (UnsafeMutableRawPointer?, UnsafePointer<CChar>?) -> Void
        let goBack: @convention(c) (UnsafeMutableRawPointer?) -> Void
        let goForward: @convention(c) (UnsafeMutableRawPointer?) -> Void
        let reload: @convention(c) (UnsafeMutableRawPointer?) -> Void
        let stop: @convention(c) (UnsafeMutableRawPointer?) -> Void
        let evaluateJS: @convention(c) (UnsafeMutableRawPointer?, UnsafePointer<CChar>?, EventCallback?, UnsafeMutableRawPointer?) -> Void
        let captureSnapshot: @convention(c) (UnsafeMutableRawPointer?, EventCallback?, UnsafeMutableRawPointer?) -> Void
        let getURL: @convention(c) (UnsafeMutableRawPointer?) -> UnsafePointer<CChar>?
        let getTitle: @convention(c) (UnsafeMutableRawPointer?) -> UnsafePointer<CChar>?
        let isLoading: @convention(c) (UnsafeMutableRawPointer?) -> Int32
        let canGoBack: @convention(c) (UnsafeMutableRawPointer?) -> Int32
        let canGoForward: @convention(c) (UnsafeMutableRawPointer?) -> Int32
        let setUserAgent: @convention(c) (UnsafeMutableRawPointer?, UnsafePointer<CChar>?) -> Void
        let setEventCallback: @convention(c) (UnsafeMutableRawPointer?, EventCallback?, UnsafeMutableRawPointer?) -> Void
        let destroyView: @convention(c) (UnsafeMutableRawPointer?) -> Void
        let lastError: @convention(c) () -> UnsafePointer<CChar>?
    }

    // MARK: State

    /// dlopen'd symbols. `nonisolated(unsafe)`: all bridge access is
    /// main-thread by design (the C API requires it), including deinit paths.
    nonisolated(unsafe) private static var symbols: Symbols?
    nonisolated(unsafe) private static var frameworkHandle: UnsafeMutableRawPointer?

    private static let frameworkPath = Bundle.main.bundlePath + "/Frameworks/content_shell_framework.framework/content_shell_framework"

    // MARK: Availability

    /// True when the Blink framework is bundled (Blink/TrollStore build).
    static func isFrameworkPresent() -> Bool {
        FileManager.default.fileExists(atPath: frameworkPath)
    }

    /// Load the framework and resolve every symbol. Returns false on failure
    /// (framework missing, wrong arch, missing symbol — caller falls back).
    @discardableResult
    static func loadIfNeeded() -> Bool {
        if symbols != nil { return true }
        guard isFrameworkPresent() else {
            engineLogger.info("Blink framework not bundled — Blink engine unavailable")
            return false
        }
        guard let handle = dlopen(frameworkPath, RTLD_NOW | RTLD_LOCAL) else {
            if let err = dlerror() {
                engineLogger.error("dlopen content_shell_framework failed: \(String(cString: err))")
            }
            return false
        }
        frameworkHandle = handle

        func sym<T>(_ name: String) -> T? {
            guard let ptr = dlsym(handle, name) else { return nil }
            return unsafeBitCast(ptr, to: T.self)
        }

        guard let s = Symbols(
            initialize: sym("BlinkBridgeInitialize"),
            createView: sym("BlinkBridgeCreateView"),
            setViewFrame: sym("BlinkBridgeSetViewFrame"),
            loadURL: sym("BlinkBridgeLoadURL"),
            goBack: sym("BlinkBridgeGoBack"),
            goForward: sym("BlinkBridgeGoForward"),
            reload: sym("BlinkBridgeReload"),
            stop: sym("BlinkBridgeStop"),
            evaluateJS: sym("BlinkBridgeEvaluateJS"),
            captureSnapshot: sym("BlinkBridgeCaptureSnapshot"),
            getURL: sym("BlinkBridgeGetURL"),
            getTitle: sym("BlinkBridgeGetTitle"),
            isLoading: sym("BlinkBridgeIsLoading"),
            canGoBack: sym("BlinkBridgeCanGoBack"),
            canGoForward: sym("BlinkBridgeCanGoForward"),
            setUserAgent: sym("BlinkBridgeSetUserAgent"),
            setEventCallback: sym("BlinkBridgeSetEventCallback"),
            destroyView: sym("BlinkBridgeDestroyView"),
            lastError: sym("BlinkBridgeLastError")
        ) else {
            engineLogger.error("Blink bridge: missing symbol(s) in content_shell_framework")
            return false
        }
        symbols = s
        engineLogger.info("Blink bridge loaded: \(self.frameworkPath)")
        return true
    }

    // MARK: Init

    /// One-time Chromium browser-process init. Call from the main thread before
    /// creating any view. Returns true on success.
    static func initialize() -> Bool {
        guard loadIfNeeded(), let s = symbols else { return false }
        let bundle = Bundle.main.bundlePath
        let tmp = NSTemporaryDirectory()
        let rc = bundle.withCString { bp in
            tmp.withCString { tp in
                s.initialize(bp, tp)
            }
        }
        if rc != 0 {
            engineLogger.error("BlinkBridgeInitialize failed rc=\(rc) err=\(bridgeLastError())")
            return false
        }
        return true
    }

    private static func bridgeLastError() -> String {
        guard let s = symbols, let err = s.lastError() else { return "unknown" }
        return String(cString: err)
    }

    // MARK: View lifecycle

    static func createView(width: CGFloat, height: CGFloat) -> UnsafeMutableRawPointer? {
        guard let s = symbols else { return nil }
        return s.createView(Int32(width), Int32(height), Int32(width), Int32(height))
    }

    static func setViewFrame(_ handle: UnsafeMutableRawPointer, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
        guard let s = symbols else { return }
        s.setViewFrame(handle, Int32(x), Int32(y), Int32(width), Int32(height))
    }

    static func destroyView(_ handle: UnsafeMutableRawPointer) {
        guard let s = symbols else { return }
        s.destroyView(handle)
    }

    /// Nonisolated destroy for use from `deinit` (main-thread by contract).
    nonisolated(unsafe) static func destroyViewFromDeinit(_ handle: UnsafeMutableRawPointer) {
        symbols?.destroyView(handle)
    }

    // MARK: Navigation

    static func loadURL(_ handle: UnsafeMutableRawPointer, _ url: String) {
        guard let s = symbols else { return }
        url.withCString { s.loadURL(handle, $0) }
    }

    static func goBack(_ handle: UnsafeMutableRawPointer) { symbols?.goBack(handle) }
    static func goForward(_ handle: UnsafeMutableRawPointer) { symbols?.goForward(handle) }
    static func reload(_ handle: UnsafeMutableRawPointer) { symbols?.reload(handle) }
    static func stop(_ handle: UnsafeMutableRawPointer) { symbols?.stop(handle) }

    static func setUserAgent(_ handle: UnsafeMutableRawPointer, _ ua: String) {
        guard let s = symbols else { return }
        ua.withCString { s.setUserAgent(handle, $0) }
    }

    // MARK: State

    static func getURL(_ handle: UnsafeMutableRawPointer) -> String {
        guard let s = symbols, let p = s.getURL(handle) else { return "" }
        return String(cString: p)
    }

    static func getTitle(_ handle: UnsafeMutableRawPointer) -> String {
        guard let s = symbols, let p = s.getTitle(handle) else { return "" }
        return String(cString: p)
    }

    static func isLoading(_ handle: UnsafeMutableRawPointer) -> Bool {
        guard let s = symbols else { return false }
        return s.isLoading(handle) != 0
    }

    static func canGoBack(_ handle: UnsafeMutableRawPointer) -> Bool {
        guard let s = symbols else { return false }
        return s.canGoBack(handle) != 0
    }

    static func canGoForward(_ handle: UnsafeMutableRawPointer) -> Bool {
        guard let s = symbols else { return false }
        return s.canGoForward(handle) != 0
    }

    // MARK: JS evaluation (main world — agent automation)

    /// Evaluate JS in the page's main world. `completion` receives `(result, error)`.
    /// `result` is the JSON-decoded value when the script returns JSON-serializable
    /// data, or `nil`. Errors arrive as a JSON string payload from the C side.
    static func evaluateJavaScript(_ handle: UnsafeMutableRawPointer,
                                   _ js: String,
                                   completion: @escaping (Any?, Error?) -> Void) {
        guard let s = symbols else {
            completion(nil, BlinkBridgeError.notLoaded)
            return
        }
        let box = CallbackBox { payload in
            BlinkBridgeResultParser.parseEval(payload, completion: completion)
        }
        js.withCString { cstr in
            s.evaluateJS(handle, cstr, { ctx, payload in
                guard let ctx else { return }
                let box = Unmanaged<CallbackBox>.fromOpaque(ctx).takeRetainedValue()
                box.invoke(payload.map { String(cString: $0) })
            }, Unmanaged.passRetained(box).toOpaque())
        }
    }

    // MARK: Snapshot

    /// Capture the current view as PNG. `completion` receives `(pngData, error)`.
    static func captureSnapshot(_ handle: UnsafeMutableRawPointer,
                                completion: @escaping (Data?, Error?) -> Void) {
        guard let s = symbols else {
            completion(nil, BlinkBridgeError.notLoaded)
            return
        }
        let box = CallbackBox { payload in
            guard let payload,
                  let data = Data(base64Encoded: payload) else {
                completion(nil, BlinkBridgeError.snapshotFailed)
                return
            }
            completion(data, nil)
        }
        s.captureSnapshot(handle, { ctx, payload in
            guard let ctx else { return }
            let box = Unmanaged<CallbackBox>.fromOpaque(ctx).takeRetainedValue()
            box.invoke(payload.map { String(cString: $0) })
        }, Unmanaged.passRetained(box).toOpaque())
    }

    // MARK: Events

    /// Event kinds emitted by the engine (values mirror blink_bridge.h).
    enum BridgeEvent: String {
        case didStartProvisionalNavigation = "didStartProvisionalNavigation"
        case didFinishNavigation = "didFinishNavigation"
        case didFailNavigation = "didFailNavigation"
        case titleChanged = "titleChanged"
        case urlChanged = "urlChanged"
        case loadingStateChanged = "loadingStateChanged"
        case windowOpenBlocked = "windowOpenBlocked"
        case renderProcessGone = "renderProcessGone"
    }

    struct BridgeEventPayload: Sendable {
        let event: BridgeEvent
        let url: String?
        let error: String?
        let title: String?
        let loading: Bool?
    }

    /// Install the event callback for a view. The C side fires it on the main
    /// thread. Replaces any previous callback.
    static func setEventCallback(_ handle: UnsafeMutableRawPointer,
                                 handler: @escaping (BridgeEventPayload) -> Void) {
        guard let s = symbols else { return }
        let box = CallbackBox { payload in
            guard let payload,
                  let dict = try? JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any],
                  let eventRaw = dict["event"] as? String,
                  let event = BridgeEvent(rawValue: eventRaw) else { return }
            let parsed = BridgeEventPayload(
                event: event,
                url: dict["url"] as? String,
                error: dict["error"] as? String,
                title: dict["title"] as? String,
                loading: (dict["loading"] as? NSNumber)?.boolValue
            )
            handler(parsed)
        }
        s.setEventCallback(handle, { ctx, payload in
            guard let ctx else { return }
            let box = Unmanaged<CallbackBox>.fromOpaque(ctx).takeRetainedValue()
            box.invoke(payload.map { String(cString: $0) })
        }, Unmanaged.passRetained(box).toOpaque())
    }
}

// MARK: - Support Types

enum BlinkBridgeError: LocalizedError {
    case notLoaded
    case initializeFailed
    case viewCreationFailed
    case evaluationFailed(String)
    case snapshotFailed

    var errorDescription: String? {
        switch self {
        case .notLoaded: return "Blink 引擎未加载（非 Blink 构建？）"
        case .initializeFailed: return "Blink 引擎初始化失败"
        case .viewCreationFailed: return "Blink 引擎创建视图失败"
        case .evaluationFailed(let msg): return "Blink JS 执行失败: \(msg)"
        case .snapshotFailed: return "Blink 截图失败"
        }
    }
}

/// Holds a Swift closure across the C callback boundary.
final class CallbackBox {
    private let handler: (String?) -> Void
    init(handler: @escaping (String?) -> Void) { self.handler = handler }
    func invoke(_ payload: String?) { handler(payload) }
}

/// Parses BlinkBridgeEvaluateJS result payloads:
/// `{"ok":true,"value":<json>}` | `{"ok":false,"error":"..."}` | `{"ok":true}` (undefined)
enum BlinkBridgeResultParser {
    static func parseEval(_ payload: String?, completion: (Any?, Error?) -> Void) {
        guard let payload else {
            completion(nil, nil)
            return
        }
        guard let data = payload.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            // Non-JSON payload: pass through as raw string.
            completion(payload, nil)
            return
        }
        if let ok = dict["ok"] as? Bool, ok {
            completion(dict["value"], nil)
        } else if let err = dict["error"] as? String {
            completion(nil, BlinkBridgeError.evaluationFailed(err))
        } else {
            completion(nil, nil)
        }
    }
}
