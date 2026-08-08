import Foundation
import UIKit

// MARK: - Browser Engine Kind

/// Rendering engine used by browser tabs.
///
/// - `.webkit`: system WKWebView (default, always available).
/// - `.blink`: embedded Chromium Blink+V8 (`content_shell_framework.framework`,
///   dlopen'd at runtime — only present in the TrollStore/Blink build).
/// - `.ssr`: server-side rendered content fetch (r.jina.ai / raw HTML / Wayback).
///   Headless: returns text/readable content instead of a live viewport.
enum BrowserEngineKind: String, CaseIterable, Identifiable, Sendable {
    case webkit
    case blink
    case ssr

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .webkit: return "WebKit"
        case .blink: return "Blink (Chromium)"
        case .ssr: return "SSR (服务器渲染)"
        }
    }

    var icon: String {
        switch self {
        case .webkit: return "safari"
        case .blink: return "globe.asia.australia.fill"
        case .ssr: return "text.document"
        }
    }

    /// Short badge shown in the browser toolbar.
    var badge: String {
        switch self {
        case .webkit: return "WK"
        case .blink: return "BLINK"
        case .ssr: return "SSR"
        }
    }

    var blurb: String {
        switch self {
        case .webkit: return "系统 WKWebView，与 iOS 系统同步"
        case .blink: return "真实 Chromium Blink+V8 引擎（iOS 16 越狱/TrollStore 构建）"
        case .ssr: return "服务器端渲染抓取文本内容，无需设备渲染"
        }
    }
}

// MARK: - Engine Settings

/// App-level engine selection, persisted in UserDefaults.
@MainActor
final class BrowserEngineSettings {
    static let shared = BrowserEngineSettings()

    private let defaults = UserDefaults.standard
    private let key = "browser.engine.kind"

    /// Current engine. `.blink` silently falls back to `.webkit` at tab
    /// creation time when the Blink framework isn't bundled (plain build).
    var kind: BrowserEngineKind {
        get {
            guard let raw = defaults.string(forKey: key),
                  let k = BrowserEngineKind(rawValue: raw) else {
                return .webkit
            }
            return k
        }
        set {
            defaults.set(newValue.rawValue, forKey: key)
        }
    }

    /// The kind actually usable right now (respects framework presence).
    var effectiveKind: BrowserEngineKind {
        if kind == .blink && !BlinkEngineBridge.isFrameworkPresent() {
            return .webkit
        }
        return kind
    }

    /// Blink requires the `content_shell_framework.framework` inside the app
    /// bundle — only true for the Blink/TrollStore build.
    static var isBlinkBuild: Bool {
        BlinkEngineBridge.isFrameworkPresent()
    }
}
