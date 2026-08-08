import Foundation
import UIKit

// MARK: - P2: 用系统默认浏览器打开

enum OpenInSystemBrowser {

    /// Open `urlString` in the system default browser (the URL scheme handler
    /// iOS picks — Safari or the user's default / Blinker Fluid if registered).
    /// Returns an error message, or nil on success.
    @MainActor
    static func open(_ urlString: String) -> String? {
        var normalized = urlString
        if !normalized.contains("://") {
            normalized = "https://\(normalized)"
        }
        guard let url = URL(string: normalized),
              let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme) else {
            return "无法打开：无效 URL（仅支持 http/https）"
        }
        guard UIApplication.shared.canOpenURL(url) else {
            return "无法打开：系统没有可处理该 URL 的应用"
        }
        UIApplication.shared.open(url) { ok in
            if !ok {
                // Surface failure via the app's logger.
                AppLogger(category: "OpenInBrowser").error("open failed for \(urlString)")
            }
        }
        return nil
    }
}
