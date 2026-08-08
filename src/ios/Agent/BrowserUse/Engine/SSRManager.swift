import Foundation
import UIKit
import os.log

private let ssrLog = AppLogger(category: "SSREngine")

// MARK: - SSR Manager (P1: 服务器端渲染降级)

/// Headless content-retrieval engine. When the device can't render a page
/// (WebKit too old / Blink unavailable or crashed), the agent still gets the
/// page's content via server-side rendering:
///
/// 1. **r.jina.ai** — renders the page to markdown on their servers. Must go
///    through curl (their TLS-fingerprint ACL rejects Python urllib/URLSession);
///    runs inside OpenMinis's embedded iSH when the kernel is booted.
/// 2. **Raw HTML** — plain URLSession fetch + lightweight extraction.
/// 3. **Wayback Machine** — archive.org snapshot of the URL.
///
/// Interactive actions (click/type/scroll) return explanatory errors — this
/// mode is for content retrieval, not automation.
@MainActor
final class SSRManager {

    struct SSRPage {
        let url: String
        let title: String
        let markdown: String
        let source: String  // "r.jina.ai" | "raw-html" | "wayback"
        let truncated: Bool
    }

    private(set) var currentPage: SSRPage?
    private var currentHTML: String = ""

    /// Session id used for iSH command execution.
    private let ishSessionId = "ssr-engine"

    // MARK: - Fetch chain

    func navigate(to urlString: String) async throws -> SSRPage {
        let normalized = urlString.contains("://") ? urlString : "https://\(urlString)"
        guard let url = URL(string: normalized), let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme) else {
            throw SSRManagerError.invalidURL
        }

        // 1. r.jina.ai via iSH curl (TLS-fingerprint safe).
        if isISHBooted() {
            ssrLog.info("SSR: trying r.jina.ai for \(url.absoluteString)")
            if let page = try? await fetchViaJina(url) {
                currentPage = page
                return page
            }
            ssrLog.info("SSR: r.jina.ai failed/blocked — falling back")
        }

        // 2. Raw HTML.
        ssrLog.info("SSR: fetching raw HTML for \(url.absoluteString)")
        if let page = try? await fetchRawHTML(url) {
            currentPage = page
            return page
        }

        // 3. Wayback.
        ssrLog.info("SSR: trying Wayback for \(url.absoluteString)")
        if let page = try? await fetchViaWayback(url) {
            currentPage = page
            return page
        }

        throw SSRManagerError.allSourcesFailed
    }

    // MARK: r.jina.ai

    private func isISHBooted() -> Bool {
        ISHKernel.shared.isBooted
    }

    private func fetchViaJina(_ url: URL) async throws -> SSRPage {
        let target = "https://r.jina.ai/\(url.absoluteString)"
        let command = "curl -sL --max-time 40 -A 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.2 Mobile/15E148 Safari/604.1' '\(target)' 2>/dev/null | head -c 400000"

        let result: ISHCommandResult
        do {
            result = try await ISHExecutionCoordinator.shared.execute(
                sessionId: ishSessionId,
                command: command,
                timeout: 60,
                lineCallback: { _ in },
                pidCallback: { _ in }
            )
        } catch {
            throw SSRManagerError.jinaUnavailable(error.localizedDescription)
        }

        let output = result.output
        // Cloudflare interstitial detection.
        if output.contains("Just a moment") || output.contains("cf-challenge") || output.count < 200 {
            throw SSRManagerError.jinaBlocked
        }
        return makePage(url: url.absoluteString, content: output, source: "r.jina.ai")
    }

    // MARK: Raw HTML

    private func fetchRawHTML(_ url: URL) async throws -> SSRPage {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.2 Mobile/15E148 Safari/604.1",
                         forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            throw SSRManagerError.rawFetchFailed
        }
        currentHTML = html
        let extracted = Self.extractReadable(from: html)
        return makePage(url: url.absoluteString, content: extracted.text, source: "raw-html", title: extracted.title)
    }

    // MARK: Wayback

    private func fetchViaWayback(_ url: URL) async throws -> SSRPage {
        let apiURL = "https://archive.org/wayback/available?url=\(url.absoluteString)"
        guard let api = URL(string: apiURL) else { throw SSRManagerError.waybackUnavailable }
        let (data, _) = try await URLSession.shared.data(from: api)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let snapshots = json["archived_snapshots"] as? [String: Any],
              let closest = snapshots["closest"] as? [String: Any],
              let snapshotURL = closest["url"] as? String,
              let snap = URL(string: snapshotURL) else {
            throw SSRManagerError.waybackUnavailable
        }
        var request = URLRequest(url: snap, timeoutInterval: 25)
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        let (htmlData, _) = try await URLSession.shared.data(for: request)
        guard let html = String(data: htmlData, encoding: .utf8) else {
            throw SSRManagerError.waybackUnavailable
        }
        currentHTML = html
        let extracted = Self.extractReadable(from: html)
        return makePage(url: snapshotURL, content: extracted.text, source: "wayback", title: extracted.title)
    }

    // MARK: Page assembly

    private func makePage(url: String, content: String, source: String, title: String? = nil) -> SSRPage {
        let truncated = content.count > 60_000
        let trimmed = String(content.prefix(60_000))
        let resolvedTitle = title ?? Self.extractTitle(from: trimmed) ?? url
        return SSRPage(url: url, title: resolvedTitle, markdown: trimmed, source: source, truncated: truncated)
    }

    // MARK: - Text extraction (lightweight, no dependencies)

    static func extractTitle(from html: String) -> String? {
        guard let range = html.range(of: "<title[^>]*>", options: .regularExpression),
              let end = html.range(of: "</title>", range: range.upperBound..<html.endIndex) else {
            return nil
        }
        let title = String(html[range.upperBound..<end.lowerBound])
        return Self.stripTags(title).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? nil : Self.stripTags(title).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Minimal HTML→text extraction: title, meta description, body paragraphs.
    static func extractReadable(from html: String) -> (title: String?, text: String) {
        let title = extractTitle(from: html)

        // Meta description.
        var description: String? = nil
        if let range = html.range(of: "<meta[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']*)[\"']", options: .regularExpression) {
            let tag = String(html[range])
            if let c = tag.range(of: "content=[\"']([^\"']*)[\"']", options: .regularExpression) {
                let content = String(tag[c])
                description = Self.stripTags(content.replacingOccurrences(of: "content=[\"']", with: "").replacingOccurrences(of: "\"'", with: ""))
            }
        }

        var body = html
        // Drop scripts/styles/head/noscript/svg.
        for pattern in ["<script[^>]*>[\\s\\S]*?</script>", "<style[^>]*>[\\s\\S]*?</style>",
                        "<noscript[^>]*>[\\s\\S]*?</noscript>", "<svg[^>]*>[\\s\\S]*?</svg>",
                        "<head[^>]*>[\\s\\S]*?</head>"] {
            body = body.replacingOccurrences(of: pattern, with: " ", options: .regularExpression)
        }
        // Block tags → newlines.
        body = body.replacingOccurrences(of: "</(p|div|h1|h2|h3|h4|h5|h6|li|tr|br|section|article|blockquote)>",
                                         with: "\n", options: .regularExpression)
        let stripped = Self.stripTags(body)
        // Collapse whitespace per line, drop empties, keep meaningful lines.
        let lines = stripped.components(separatedBy: "\n")
            .map { $0.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                     .trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.count >= 2 }
        let text = lines.joined(separator: "\n")

        var parts: [String] = []
        if let title { parts.append("# \(title)") }
        if let description, !description.isEmpty { parts.append("> \(description)") }
        parts.append(text.isEmpty ? "(页面无可提取文本)" : text)
        return (title, parts.joined(separator: "\n\n"))
    }

    private static func stripTags(_ s: String) -> String {
        s.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
    }
}

enum SSRManagerError: LocalizedError {
    case invalidURL
    case jinaUnavailable(String)
    case jinaBlocked
    case rawFetchFailed
    case waybackUnavailable
    case allSourcesFailed

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "SSR: 无效 URL"
        case .jinaUnavailable(let msg): return "SSR: r.jina.ai 不可用 (\(msg))"
        case .jinaBlocked: return "SSR: r.jina.ai 返回验证页（Cloudflare）"
        case .rawFetchFailed: return "SSR: 原始 HTML 抓取失败"
        case .waybackUnavailable: return "SSR: Wayback 无存档"
        case .allSourcesFailed: return "SSR: 所有取数源均失败（r.jina.ai / 原始 HTML / Wayback）"
        }
    }
}

// MARK: - SSR placeholder screenshot

extension UIImage {
    /// A generated "SSR mode" placeholder so the screenshot action contract
    /// (returns an image) holds even though SSR has no live viewport.
    static func ssrPlaceholder(text: String) -> UIImage {
        let size = CGSize(width: 390, height: 300)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            UIColor.systemBackground.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let paragraph = NSMutableParagraphStyle()
            paragraph.lineBreakMode = .byWordWrapping
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 14),
                .foregroundColor: UIColor.label,
                .paragraphStyle: paragraph
            ]
            let snippet = String(text.prefix(1200))
            (snippet as NSString).draw(in: CGRect(x: 16, y: 16, width: size.width - 32, height: size.height - 32),
                                       withAttributes: attrs)
        }
    }
}
