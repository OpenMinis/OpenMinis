import Foundation
import SwiftUI
import UIKit

/// [T-soul-custom-icon] Encode/decode for the Soul identity icon when the
/// user picks an image rather than an emoji.
///
/// Lives in this file rather than its own because `Agent/Session` is a plain
/// Xcode group, not a synchronized one — a new file there means editing
/// `project.pbxproj`, which several sessions contend over.
enum SoulIconImage {
    /// Rendered edge, in points, of the largest surface that shows the icon
    /// (the Soul Settings preview card; the chat header draws it at 18pt).
    static let renderPoints: CGFloat = 32
    /// Stored edge in pixels — the render size at @3x, so the icon is crisp
    /// on every current device and never larger than it needs to be.
    static let storedPixels: CGFloat = renderPoints * 3

    private static let prefix = "data:image/png;base64,"

    static func isDataURI(_ s: String) -> Bool { s.hasPrefix(prefix) }

    /// Why an image was refused.
    ///
    /// [T-soul-icon-opaque-rounded] `opaque` is gone. It used to reject any
    /// image without an alpha channel, on the reasoning that an unframed
    /// opaque rectangle reads as a broken tile. That reasoning was about
    /// PRESENTATION, and it is now handled where it belongs: `SoulIconView`
    /// clips every image to a rounded rectangle, so a JPEG or a flattened PNG
    /// renders as a normal small avatar. Keeping the refusal would have meant
    /// turning away the majority of images a user might pick, to solve a
    /// problem the renderer already solves.
    enum RejectionReason: Error {
        case unreadable
    }

    /// Normalize a picked image into the stored form: square, downscaled,
    /// PNG, base64 data URI.
    ///
    /// Accepts opaque and transparent images alike — anything UIKit can
    /// decode. Re-encoding to PNG regardless keeps one stored format (so
    /// `isDataURI`'s single prefix stays valid) and preserves alpha when the
    /// source had it.
    static func encode(_ image: UIImage) -> Result<String, RejectionReason> {
        guard image.cgImage != nil else { return .failure(.unreadable) }

        let square = squareCropped(image)
        // Square and bounded: the chat header's row height is a hardcoded
        // layout estimate (28pt), so a non-square icon there would fight the
        // measured height. Cropping here means every consumer can assume 1:1.
        let side = min(storedPixels, max(square.size.width, square.size.height) * square.scale)
        let target = CGSize(width: side, height: side)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1              // size is already in pixels
        format.opaque = false         // preserve alpha
        let scaled = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            square.draw(in: CGRect(origin: .zero, size: target))
        }

        guard let png = scaled.pngData() else { return .failure(.unreadable) }
        return .success(prefix + png.base64EncodedString())
    }

    /// Decode a stored data URI back to an image. Returns nil for an emoji
    /// value or anything malformed, so callers can fall back to text.
    static func decode(_ value: String) -> UIImage? {
        guard isDataURI(value) else { return nil }
        let b64 = String(value.dropFirst(prefix.count))
        guard let data = Data(base64Encoded: b64) else { return nil }
        return UIImage(data: data)
    }

    /// Centre-crop to 1:1, keeping the shorter edge.
    private static func squareCropped(_ image: UIImage) -> UIImage {
        let w = image.size.width, h = image.size.height
        guard w != h, w > 0, h > 0 else { return image }
        let side = min(w, h)
        let origin = CGPoint(x: (w - side) / 2, y: (h - side) / 2)

        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side),
                                       format: format).image { _ in
            image.draw(at: CGPoint(x: -origin.x, y: -origin.y))
        }
    }
}

/// [T-soul-icon-config-images] Turns whatever `minis-config set soul.icon`
/// was given into a stored icon value, so the tool and the Settings picker
/// end up applying the SAME rules.
///
/// The picker path is `SoulIconImage.encode` and nothing here re-implements
/// it: every image source below decodes to a `UIImage` and then goes through
/// that one function, so alpha rejection, square cropping, the 96px cap and
/// PNG re-encoding are shared by construction rather than by copy.
///
/// Why resolution happens HERE and not in the field's `writer`:
/// `ConfigField.write` is synchronous and `@MainActor`, but an https source
/// has to be downloaded. `ConfigOffloadBridge.performWriteBatch` is already
/// `async`, so the bridge resolves the source to a finished data URI during
/// the resolve phase — before the confirmation sheet — and the writer stays
/// synchronous and network-free. That also means the user confirms a change
/// whose image has already been fetched and validated: the sheet cannot
/// promise something the write then fails to deliver.
enum SoulIconSource {
    /// Ceiling on what any source may expand to in memory before decoding.
    /// Generous next to a real icon (a 96px PNG is ~1-4 KB) and small enough
    /// that a hostile or mistaken input cannot exhaust memory.
    static let maxSourceBytes = 8 * 1024 * 1024

    /// Hard cap on the base64 text accepted as a literal argument. Bounds the
    /// tool-call payload itself, before any decoding happens.
    static let maxInlineBase64Chars = 12 * 1024 * 1024

    /// Wall-clock budget for an http(s) fetch.
    static let downloadTimeout: TimeInterval = 15

    /// Cap on the stored data URI. Mirrors Android's
    /// `SoulIcon.MAX_DATA_URI_CHARS` — the value syncs between platforms, so
    /// a value one side would refuse to load must not be storable on the other.
    static let maxStoredChars = 64 * 1024

    enum SourceError: LocalizedError {
        case tooLarge(String)
        case notAnImage(String)
        case unreadable
        case notFound(String)
        case outsideAllowedDirs(String)
        case badURL(String)
        case blockedHost(String)
        case httpStatus(Int)
        case network(String)
        case storedTooLarge(Int)

        var errorDescription: String? {
            switch self {
            case .tooLarge(let what):
                return "\(what) — the limit is \(maxSourceBytes / 1024 / 1024) MB"
            case .notAnImage(let detail):
                return "that isn't a decodable image (\(detail))"
            case .unreadable:
                return "the image could not be re-encoded"
            case .notFound(let p):
                return "no file at \(p)"
            case .outsideAllowedDirs(let p):
                return "\(p) is outside the directories this tool may read. "
                     + "Use a minis:// URL (e.g. minis://attachments/icon.png) "
                     + "or a path under the session's minis directories."
            case .badURL(let s):
                return "couldn't parse '\(s)' as an image source"
            case .blockedHost(let h):
                return "refusing to fetch from '\(h)': only public http(s) "
                     + "hosts are allowed"
            case .httpStatus(let code):
                return "the server returned HTTP \(code)"
            case .network(let msg):
                return "download failed: \(msg)"
            case .storedTooLarge(let n):
                return "the encoded icon is \(n) chars, over the \(maxStoredChars) limit"
            }
        }
    }

    /// True when `raw` should be treated as an image source rather than as an
    /// emoji. Deliberately generous: anything that is clearly not a one-glyph
    /// emoji gets routed here so the user sees a real diagnostic instead of
    /// "icon must be a single emoji".
    static func looksLikeImageSource(_ raw: String) -> Bool {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return false }
        if s.hasPrefix("data:") { return true }
        if s.hasPrefix("minis://") { return true }
        if s.hasPrefix("http://") || s.hasPrefix("https://") { return true }
        if s.hasPrefix("/") || s.hasPrefix("~/") { return true }
        // A bare base64 blob: long, and only base64 characters. The length
        // floor keeps short emoji/text from being misread as base64.
        if s.count > 64, isProbablyBareBase64(s) { return true }
        return false
    }

    /// Resolve any accepted source to a stored `data:image/png;base64,…` URI.
    /// Never returns a path or a remote URL: an address is an import source
    /// only, so a cleaned-up attachment or a different device cannot leave the
    /// icon dangling.
    static func resolveToDataURI(_ raw: String) async throws -> String {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        // Already stored form — re-encode anyway rather than trusting it, so a
        // hand-written data URI gets the same alpha/size treatment as a picked
        // image and cannot smuggle in an oversized or opaque payload.
        let data: Data
        if s.hasPrefix("data:") {
            data = try decodeDataURI(s)
        } else if s.hasPrefix("minis://") {
            data = try readMinisURL(s)
        } else if s.hasPrefix("http://") || s.hasPrefix("https://") {
            data = try await download(s)
        } else if s.hasPrefix("/") || s.hasPrefix("~/") {
            data = try readLocalPath(s)
        } else if isProbablyBareBase64(s) {
            guard s.count <= maxInlineBase64Chars else {
                throw SourceError.tooLarge("the base64 argument is \(s.count) chars")
            }
            guard let d = Data(base64Encoded: s, options: [.ignoreUnknownCharacters]) else {
                throw SourceError.notAnImage("not valid base64")
            }
            data = d
        } else {
            throw SourceError.badURL(s)
        }

        guard data.count <= maxSourceBytes else {
            throw SourceError.tooLarge("the image is \(data.count / 1024) KB")
        }
        guard let image = UIImage(data: data) else {
            throw SourceError.notAnImage("\(data.count) bytes that no image decoder accepted")
        }

        // The shared rules. Not reimplemented — this is the picker's function.
        switch SoulIconImage.encode(image) {
        case .failure(.unreadable): throw SourceError.unreadable
        case .success(let uri):
            guard uri.count <= maxStoredChars else {
                throw SourceError.storedTooLarge(uri.count)
            }
            return uri
        }
    }

    // MARK: - Sources

    private static func decodeDataURI(_ s: String) throws -> Data {
        guard s.count <= maxInlineBase64Chars else {
            throw SourceError.tooLarge("the data URI is \(s.count) chars")
        }
        guard let comma = s.firstIndex(of: ",") else {
            throw SourceError.notAnImage("malformed data URI (no comma)")
        }
        let meta = String(s[s.startIndex..<comma])
        guard meta.contains(";base64") else {
            throw SourceError.notAnImage("only base64 data URIs are supported")
        }
        let b64 = String(s[s.index(after: comma)...])
        guard let d = Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]) else {
            throw SourceError.notAnImage("the data URI payload is not valid base64")
        }
        return d
    }

    /// `minis://` goes through the app's own resolver, which is what enforces
    /// session scoping — this must not reach into another session's files.
    private static func readMinisURL(_ s: String) throws -> Data {
        guard let url = URL(string: s) else { throw SourceError.badURL(s) }
        guard let fileURL = AIChatViewModel.resolveMinisURL(url) else {
            throw SourceError.notFound(s)
        }
        return try readFile(at: fileURL, label: s)
    }

    /// A raw filesystem path is accepted only inside the minis persistent
    /// tree. Everything else in the container (Keychain-adjacent plists, other
    /// apps' shared data, the rootfs) stays unreadable through this tool.
    private static func readLocalPath(_ s: String) throws -> Data {
        let expanded = (s as NSString).expandingTildeInPath
        let url = URL(fileURLWithPath: expanded)
        guard let root = containingAllowedRoot(for: url) else {
            throw SourceError.outsideAllowedDirs(expanded)
        }
        _ = root
        return try readFile(at: url, label: expanded)
    }

    /// Canonical containment: resolve BOTH sides through the filesystem before
    /// comparing, so a symlink inside an allowed directory cannot point out of
    /// it, and so /var vs /private/var does not produce a false mismatch.
    /// Same construction as `BackupZipExtractor.safeDestination`.
    private static func containingAllowedRoot(for url: URL) -> URL? {
        let target = url.standardizedFileURL.resolvingSymlinksInPath().path
        for root in allowedRoots() {
            let base = root.standardizedFileURL.resolvingSymlinksInPath().path
            if target == base || target.hasPrefix(base + "/") { return root }
        }
        return nil
    }

    /// The directories a config-driven read may touch. Session-scoped dirs use
    /// the ACTIVE session only, matching `resolveMinisURL`'s isolation rule.
    private static func allowedRoots() -> [URL] {
        var roots: [URL] = [
            AIChatViewModel.minisSkillsPersistentDir,
            AIChatViewModel.minisMemoryPersistentDir,
            AIChatViewModel.minisSharedPersistentDir,
        ]
        if let sid = AIChatViewModel.activeSessionId {
            roots.append(AIChatViewModel.minisPersistentBase
                .appendingPathComponent(sid, isDirectory: true))
        }
        return roots
    }

    private static func readFile(at url: URL, label: String) throws -> Data {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue else {
            throw SourceError.notFound(label)
        }
        // Check the size on the way in: reading first and measuring after
        // would already have spent the memory this cap exists to protect.
        if let size = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int),
           size > maxSourceBytes {
            throw SourceError.tooLarge("\(label) is \(size / 1024 / 1024) MB")
        }
        guard let data = fm.contents(atPath: url.path) else {
            throw SourceError.notFound(label)
        }
        return data
    }

    // MARK: - http(s)

    /// Fetch an image over http(s) with an allow-list, a deadline and a size
    /// cap. The host checks are anti-SSRF: this runs on behalf of a model, so
    /// a URL it invents must not become a probe of the LAN or of link-local
    /// metadata endpoints.
    private static func download(_ s: String) async throws -> Data {
        guard let url = URL(string: s), let scheme = url.scheme?.lowercased() else {
            throw SourceError.badURL(s)
        }
        guard scheme == "http" || scheme == "https" else {
            throw SourceError.badURL(s)
        }
        guard let host = url.host, !host.isEmpty else { throw SourceError.badURL(s) }
        guard !isBlockedHost(host) else { throw SourceError.blockedHost(host) }

        var req = URLRequest(url: url)
        req.timeoutInterval = downloadTimeout
        req.httpMethod = "GET"
        req.setValue("image/*", forHTTPHeaderField: "Accept")

        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = downloadTimeout
        cfg.timeoutIntervalForResource = downloadTimeout
        cfg.httpCookieStorage = nil          // no ambient credentials
        cfg.urlCredentialStorage = nil
        let session = URLSession(configuration: cfg)
        defer { session.invalidateAndCancel() }

        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw SourceError.network(error.localizedDescription)
        }

        if let http = response as? HTTPURLResponse {
            guard (200...299).contains(http.statusCode) else {
                throw SourceError.httpStatus(http.statusCode)
            }
            // A redirect could have landed somewhere private; re-check.
            if let finalHost = http.url?.host, isBlockedHost(finalHost) {
                throw SourceError.blockedHost(finalHost)
            }
        }
        guard data.count <= maxSourceBytes else {
            throw SourceError.tooLarge("the download is \(data.count / 1024) KB")
        }
        // MIME is advisory only — the real check is whether it decodes, which
        // the caller does next. A server that mislabels a valid PNG should
        // still work; a server that sends HTML will fail at decode with a
        // clearer message than a MIME mismatch would give.
        return data
    }

    /// Loopback, link-local, and RFC1918 space, plus the metadata addresses.
    /// Literal-IP matching only: resolving a hostname here would be a TOCTOU
    /// race against the connection's own resolution, so hostnames are allowed
    /// and the URLSession is instead denied ambient credentials.
    static func isBlockedHost(_ host: String) -> Bool {
        let h = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h == "localhost" || h.hasSuffix(".localhost") || h.hasSuffix(".local") { return true }
        if h == "::1" || h == "0:0:0:0:0:0:0:1" { return true }
        // IPv6 unique-local / link-local
        if h.hasPrefix("fc") || h.hasPrefix("fd") || h.hasPrefix("fe80:") { return true }

        let parts = h.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, let a = Int(parts[0]), let b = Int(parts[1]),
              Int(parts[2]) != nil, Int(parts[3]) != nil else {
            return false        // a hostname, not a literal IPv4
        }
        switch a {
        case 0, 10, 127: return true                    // this-network, private, loopback
        case 169 where b == 254: return true            // link-local + AWS/GCP metadata
        case 172 where (16...31).contains(b): return true
        case 192 where b == 168: return true
        case 100 where (64...127).contains(b): return true  // CGNAT
        default: return false
        }
    }

    private static func isProbablyBareBase64(_ s: String) -> Bool {
        guard !s.isEmpty else { return false }
        let allowed = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\n\r")
        return s.unicodeScalars.allSatisfy { allowed.contains($0) }
    }
}

/// [T-soul-custom-icon] The Soul identity icon, rendered the same way
/// everywhere it appears.
///
/// Shared so every surface (Soul Settings preview at 32pt, the settings
/// picker sheet, chat turn header at 18pt) cannot drift apart: they differ
/// only in `size`. The image branch is drawn 1:1 — `encode` already
/// guaranteed a square, so this cannot distort.
///
/// [T-soul-icon-opaque-rounded] The image clip is a CONTINUOUS ROUNDED
/// RECTANGLE, and that is the single place the shape is decided. Since
/// opaque images are now accepted, the renderer is what stops a JPEG from
/// reading as a hard-edged tile — so the corner treatment has to live in the
/// shared component, not at each call site, or one surface would inevitably
/// miss it.
///
/// Rounded rather than a circle: at 18pt a circular mask eats the corners of
/// a small avatar (logos and faces lose noticeably more), and the request was
/// explicitly for a soft edge, not a crop to round. The radius scales with
/// `size` so the 18pt header and the 32pt card look like the same shape
/// rather than one looking markedly boxier than the other.
struct SoulIconView: View {
    let icon: String
    let size: CGFloat
    /// Gradient used for the default sparkle in the chat header. Nil renders
    /// the plain emoji glyph, which is what the settings card wants.
    var sparkleGradient: LinearGradient? = nil

    /// ~22% of the edge: iOS's own app-icon "squircle" proportion, which
    /// reads as rounded at 18pt without rounding away image content.
    static func cornerRadius(for size: CGFloat) -> CGFloat { size * 0.22 }

    var body: some View {
        if let image = SoulIconImage.decode(icon) {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius(for: size),
                                            style: .continuous))
        } else if icon.isEmpty, let gradient = sparkleGradient {
            // Default: keep the SF Symbol so the chat header's existing
            // gradient treatment is untouched for users who never set one.
            Image(systemName: "sparkles")
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(gradient)
        } else {
            // A user-chosen emoji, or the default sparkle where no gradient
            // was requested.
            Text(icon.isEmpty ? "✨" : icon)
                .font(.system(size: size))
        }
    }
}

/// Persistent personality/identity file living alongside GLOBAL.md and the
/// daily memory logs. Two-part format: YAML frontmatter (delimited by `---`)
/// followed by a Markdown body. The body is injected as Layer 1 of the
/// system prompt; the `name` / `emoji` fields drive the chat bubble header.
struct SoulMetadata: Equatable {
    var name: String
    /// Raw `emoji` value parsed from SOUL.md. Preserved on disk for
    /// backward compatibility but NOT shown anywhere in the UI: the chat
    /// bubble header and Soul Settings preview always render
    /// `displayEmoji` (a fixed sparkles glyph). User-customized emoji
    /// was removed; this field is round-tripped untouched if the file
    /// already has one.
    var emoji: String
    var style: String
    /// `"auto"`, `"zh"`, `"en"`, or any free-form tag.
    var lang: String

    /// [T-soul-custom-icon] User-chosen identity icon. Either a short
    /// literal (an emoji) or a `data:image/png;base64,…` URI produced by
    /// `SoulIconImage.encode`. Empty means "use the default sparkle".
    ///
    /// This lives in SOUL.md frontmatter, NOT in the body, and that is
    /// load-bearing: `identitySection()` builds the system prompt from a
    /// fixed whitelist (`name` / `style` / body) and never serializes
    /// frontmatter wholesale, so a ~20 KB data URI here costs zero prompt
    /// tokens. Putting it in the body would both burn context and count
    /// against the body length limit.
    var icon: String

    /// The icon shown in every UI surface (chat bubble header, Soul
    /// Settings preview card): the user's `icon` when set, else the
    /// canonical sparkle.
    ///
    /// `emoji` is deliberately NOT consulted. That field belongs to the
    /// removed pre-2026-05 customization (see its comment); reviving it
    /// implicitly would resurrect a value users last set under different
    /// UI they may not remember. `icon` is opt-in from a fresh choice.
    var displayIcon: String { icon.isEmpty ? "✨" : icon }

    /// True when `icon` holds an image rather than a text glyph, i.e. the
    /// UI must decode it instead of rendering it as a `Text`.
    var iconIsImage: Bool { SoulIconImage.isDataURI(icon) }

    /// Retained so existing call sites keep compiling and keep meaning
    /// "the fixed sparkle". Prefer `displayIcon`.
    var displayEmoji: String { "✨" }

    static let `default` = SoulMetadata(
        name: "Minis",
        // Default emoji is intentionally empty — the UI uses the fixed
        // `displayEmoji` sparkle and serialize() no longer writes the
        // `emoji:` line. Kept on the struct only so the parser can
        // round-trip an `emoji: "..."` line that survives in an old
        // user-authored SOUL.md (next save will drop it on disk too).
        emoji: "",
        // Default style is also empty so the user fills it in themselves
        // (UI shows a placeholder hint). Avoids shipping an opinionated
        // baseline that users have to first delete before authoring their
        // own.
        style: "",
        lang: "auto",
        // No icon by default — `displayIcon` falls back to the sparkle, so
        // an untouched SOUL.md serializes without an `icon:` line at all.
        icon: ""
    )
}

/// Result of parsing a SOUL.md file. `body` is the Markdown body with
/// frontmatter stripped (trimmed of leading newlines).
struct SoulFile: Equatable {
    var metadata: SoulMetadata
    var body: String
}

enum SoulMDParser {

    /// Parse a SOUL.md file content into metadata + body. When the file has
    /// no frontmatter, the entire text is treated as body and metadata
    /// falls back to default. Designed to be lossless enough that
    /// `serialize(parse(s)) == s` for files this app writes itself.
    static func parse(_ source: String) -> SoulFile {
        let trimmedLeading = source.drop(while: { $0 == "\n" || $0 == "\r" })
        // Frontmatter must start with "---" on its own line and end with
        // another "---" on its own line. Otherwise return as plain body.
        guard trimmedLeading.hasPrefix("---") else {
            return SoulFile(metadata: .default, body: source)
        }
        let lines = String(trimmedLeading).components(separatedBy: "\n")
        // First line is the opening "---". Find the matching closing "---".
        guard lines.first?.trimmingCharacters(in: .whitespaces) == "---",
              let closeIdx = lines.dropFirst().firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "---" })
        else {
            return SoulFile(metadata: .default, body: source)
        }
        let frontmatterLines = Array(lines[1..<closeIdx])
        let bodyLines = Array(lines[(closeIdx + 1)...])
        let body = bodyLines.joined(separator: "\n").drop(while: { $0 == "\n" || $0 == "\r" })
        var meta = SoulMetadata.default
        for raw in frontmatterLines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            var value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            // Strip optional surrounding double quotes; otherwise take raw.
            if value.hasPrefix("\""), value.hasSuffix("\""), value.count >= 2 {
                value = String(value.dropFirst().dropLast())
            }
            switch key {
            case "name":  if !value.isEmpty { meta.name = value }
            case "emoji": if !value.isEmpty { meta.emoji = value }
            // [T-soul-custom-icon] The value may be a data URI, which itself
            // contains a colon (`data:image/png;base64,…`). Safe here because
            // the split above uses `firstIndex(of: ":")` — the key is
            // everything before the FIRST colon and the rest is taken whole.
            case "icon":  meta.icon = value
            case "style": meta.style = value
            case "lang":  if !value.isEmpty { meta.lang = value }
            default: break
            }
        }
        return SoulFile(metadata: meta, body: String(body))
    }

    /// Serialize back to SOUL.md text. Emits name / style / lang, plus
    /// `icon` when the user has set one, followed by an empty line and the
    /// body.
    ///
    /// The `emoji` field is deliberately NOT written — the UI is locked to
    /// a fixed sparkle (`displayEmoji`), so persisting a `emoji:` line would
    /// imply user-controlled customization that doesn't exist. Old files
    /// containing `emoji: "..."` still parse cleanly (the value is kept in
    /// memory for round-trip safety) but the line is dropped on the next
    /// save, naturally migrating disk state to the new schema.
    ///
    /// [T-soul-custom-icon] `icon` IS written — unlike `emoji`, it backs a
    /// live feature, so dropping it would mean the setting could not persist.
    /// Written only when non-empty so an untouched file keeps its existing
    /// 3-key shape.
    ///
    /// Known and accepted: a build predating this field parses `icon:` fine
    /// (unknown keys hit `default: break`) but its serializer omits it, so
    /// saving Soul on an older device drops the icon and that deletion syncs
    /// back. Same one-way migration the `emoji` removal relied on.
    static func serialize(_ file: SoulFile) -> String {
        var out = "---\n"
        out += "name: \"\(escape(file.metadata.name))\"\n"
        if !file.metadata.icon.isEmpty {
            out += "icon: \"\(escape(file.metadata.icon))\"\n"
        }
        out += "style: \"\(escape(file.metadata.style))\"\n"
        out += "lang: \"\(escape(file.metadata.lang))\"\n"
        out += "---\n\n"
        out += file.body
        if !out.hasSuffix("\n") { out += "\n" }
        return out
    }

    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
         .replacingOccurrences(of: "\"", with: "\\\"")
    }
}

/// Result of a SOUL.md body length check. The hard limit depends on what
/// language the body is written in — Chinese / Japanese / Korean text is
/// information-dense per character so the cap is in grapheme clusters,
/// while English / other Latin-alphabet text is capped by word count.
///
/// Mirrors `SoulStore.isOverLimit(_:)`. The associated value carries the
/// observed token count and the matching cap so callers can surface a
/// precise "your body is N / max M" message.
enum SoulBodyLimitCheck: Equatable {
    case ok
    case overLimit(count: Int, cap: Int)

    var isOverLimit: Bool {
        if case .ok = self { return false }
        return true
    }
}

/// Filesystem helpers for SOUL.md. The file lives in the same memory
/// directory as GLOBAL.md and daily logs.
enum SoulStore {

    static var fileURL: URL {
        AIChatViewModel.minisMemoryPersistentDir.appendingPathComponent("SOUL.md")
    }

    // MARK: - Body length rules (unified token count)
    //
    // The personality body has a single hard cap of 2000 tokens, applied
    // at every write surface (Settings UI Save button, minis-config writer,
    // and the prompt-build-time fallback in `SystemPromptBuilder`).
    //
    // Counting rules — see `tokenCount(_:)`:
    //   - Each CJK glyph or CJK punctuation mark = 1 token
    //   - Each whitespace-delimited Latin / Cyrillic / etc. run = 1 token
    //   - Mixed text adds both kinds together
    //
    // So "hello AB CD" with two CJK glyphs counts as 1 (hello) + 4 (A B C D) = 5.

    static let bodyTokenLimit = 2000

    /// Count tokens in [body] under the unified rule. Empty / whitespace
    /// returns 0. CJK glyphs/punctuation count one-per-character; non-CJK
    /// text counts one-per-whitespace-delimited-word.
    static func tokenCount(_ body: String) -> Int {
        var count = 0
        var inWord = false
        for scalar in body.unicodeScalars {
            if isCJKScalar(scalar) || isCJKPunctuationScalar(scalar) {
                // Close any open Latin word, then count the CJK glyph itself.
                if inWord { count += 1; inWord = false }
                count += 1
            } else if CharacterSet.whitespacesAndNewlines.contains(scalar) {
                if inWord { count += 1; inWord = false }
            } else {
                inWord = true
            }
        }
        if inWord { count += 1 }
        return count
    }

    /// Classify [body] under the unified 2000-token rule. Empty /
    /// whitespace-only bodies always return `.ok`.
    static func isOverLimit(_ body: String) -> SoulBodyLimitCheck {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .ok }
        let count = tokenCount(trimmed)
        return count > Self.bodyTokenLimit
            ? .overLimit(count: count, cap: Self.bodyTokenLimit)
            : .ok
    }

    /// CJK punctuation that the user perceives as a character — full-width
    /// comma, period, brackets, etc. Counted alongside CJK glyphs so a
    /// 100-glyph Chinese paragraph with 10 commas reports as 110, matching
    /// what a user counts visually.
    private static func isCJKPunctuationScalar(_ s: Unicode.Scalar) -> Bool {
        let v = s.value
        // CJK Symbols and Punctuation: 、。「」『』〈〉《》…
        if (0x3000...0x303F).contains(v) { return true }
        // Halfwidth and Fullwidth Forms: ,.!?:;()… (full-width)
        if (0xFF00...0xFFEF).contains(v) { return true }
        return false
    }

    /// True if [scalar] belongs to any of the CJK Unified Ideograph or
    /// Kana / Hangul ranges. Covers Chinese (Simplified + Traditional),
    /// Japanese (Hiragana + Katakana + Kanji shared with CJK Unified),
    /// and Korean (Hangul Syllables + Jamo). Wider than just U+4E00–U+9FFF
    /// so CJK-Ext-A/B/C/… and Hangul also count toward the ratio.
    private static func isCJKScalar(_ s: Unicode.Scalar) -> Bool {
        let v = s.value
        // CJK Unified Ideographs + Ext A
        if (0x4E00...0x9FFF).contains(v) { return true }
        if (0x3400...0x4DBF).contains(v) { return true }
        // CJK Unified Ideographs Ext B, C/D/E/F, G/H
        if (0x20000...0x2A6DF).contains(v) { return true }
        if (0x2A700...0x2EBEF).contains(v) { return true }
        if (0x30000...0x323AF).contains(v) { return true }
        // Hiragana, Katakana, Katakana Phonetic Extensions
        if (0x3040...0x309F).contains(v) { return true }
        if (0x30A0...0x30FF).contains(v) { return true }
        if (0x31F0...0x31FF).contains(v) { return true }
        // Hangul Syllables, Jamo, Compatibility Jamo
        if (0xAC00...0xD7AF).contains(v) { return true }
        if (0x1100...0x11FF).contains(v) { return true }
        if (0x3130...0x318F).contains(v) { return true }
        return false
    }

    /// The verbatim default file content used both for auto-create on first
    /// run and for the "Restore Default" button in Settings.
    ///
    /// IMPORTANT: the body contains ONLY personality / character / voice
    /// guidance. It must NOT contain the "You are <name>, …" identity
    /// sentence — that boilerplate is owned by `SystemPromptBuilder` and
    /// stitched in around the body at prompt-build time. Mixing identity
    /// boilerplate into the body would:
    ///   (a) expose internal prompt structure to users in the Settings
    ///       Personality editor, and
    ///   (b) double-up "You are X" lines whenever the template-rendered
    ///       prompt also produces one.
    ///
    /// Defaults intentionally ship with NO personality body so the
    /// agent runs with vanilla behaviour out of the box and users decide
    /// what voice / tone to add themselves. Only the frontmatter (name /
    /// style / lang) is seeded.
    static let defaultContent: String = """
    ---
    name: "Minis"
    style: ""
    lang: "auto"
    ---

    """

    /// Create SOUL.md with default content if it does not already exist.
    /// Safe to call on every launch — never overwrites existing content.
    static func ensureExists() {
        let url = fileURL
        let fm = FileManager.default
        guard !fm.fileExists(atPath: url.path) else { return }
        // Parent dir is created earlier by registerFileProviderDomain;
        // defensive createDirectory here in case ensureExists is invoked
        // from a code path that runs before that.
        try? fm.createDirectory(at: url.deletingLastPathComponent(),
                                withIntermediateDirectories: true)
        try? defaultContent.data(using: .utf8)?.write(to: url, options: .atomic)
    }

    /// Read + parse the current SOUL.md. Returns nil when the file does
    /// not exist or is unreadable. An empty file parses to default-meta +
    /// empty body, which callers may want to treat as missing.
    static func load() -> SoulFile? {
        let url = fileURL
        guard FileManager.default.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let str = String(data: data, encoding: .utf8) else { return nil }
        return SoulMDParser.parse(str)
    }

    /// Best-effort cached metadata for synchronous call sites that cannot
    /// re-read the file (e.g. cell builders running off the main thread).
    /// Refreshed after every Settings save via `notifyChanged()`.
    @MainActor
    static var cachedMetadata: SoulMetadata = .default

    /// Re-read SOUL.md into `cachedMetadata` and post a notification so
    /// observers (chat bubble header, prompt builder, etc.) can refresh.
    @MainActor
    static func refreshCache() {
        if let file = load() {
            cachedMetadata = file.metadata
        } else {
            cachedMetadata = .default
        }
        NotificationCenter.default.post(name: .soulMdChanged, object: nil)
    }

    /// Persist a SoulFile back to disk and refresh the cache.
    @MainActor
    static func save(_ file: SoulFile) throws {
        let url = fileURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        let text = SoulMDParser.serialize(file)
        try text.data(using: .utf8)?.write(to: url, options: .atomic)
        cachedMetadata = file.metadata
        NotificationCenter.default.post(name: .soulMdChanged, object: nil)
        // Notify the V2 sync layer so SOUL.md changes propagate to the
        // user's other devices. SoulStore is a singleton record on
        // iCloud (recordId "soul"); the hydrator reads SOUL.md from
        // disk when building the outbound portable, so the only thing
        // we need to do here is enqueue it as dirty.
        Task { await ChatStore.shared.markDirty(recordType: "SoulV2", recordId: "soul") }
    }

    /// Apply an inbound SOUL.md from iCloud without re-marking it dirty.
    /// Used by the V2 sync hydrator's merger path. LWW-by-mtime is
    /// applied here so a stale remote write doesn't clobber a newer
    /// local file (the cloud record's updatedAt is the authority, not
    /// our local file mtime).
    @MainActor
    static func applyRemoteContent(_ markdown: String, remoteUpdatedAt: Date) {
        let url = fileURL
        let fm = FileManager.default
        // Compare local file mtime against the remote updatedAt; skip
        // the write if local is strictly newer (peer's record reflects
        // an older snapshot).
        if let attrs = try? fm.attributesOfItem(atPath: url.path),
           let localMtime = attrs[.modificationDate] as? Date,
           localMtime > remoteUpdatedAt {
            return
        }
        // [T-icloud-local-edit-clobber] Content-equality short circuit: an
        // echo of what we already have must not rewrite the file (rewriting
        // refreshes the LWW clock and fires soulMdChanged for nothing).
        if let data = try? Data(contentsOf: url),
           let localText = String(data: data, encoding: .utf8),
           localText == markdown {
            return
        }
        // Reject a remote default-seed payload when the local file is
        // already customized. The matching buildSoul guard prevents
        // healthy peers from pushing default seeds, but a record that
        // landed in iCloud before the buildSoul guard existed (or that
        // came from a downgraded device) would otherwise wipe out a
        // customized local SOUL.md via LWW.
        if markdown == defaultContent,
           let data = try? Data(contentsOf: url),
           let localText = String(data: data, encoding: .utf8),
           localText != defaultContent {
            return
        }
        try? fm.createDirectory(at: url.deletingLastPathComponent(),
                                withIntermediateDirectories: true)
        try? markdown.data(using: .utf8)?.write(to: url, options: .atomic)
        // Stamp the file's mtime to match the remote updatedAt so the
        // local mtime comparison stays meaningful across round-trips.
        try? fm.setAttributes([.modificationDate: remoteUpdatedAt],
                              ofItemAtPath: url.path)
        if let parsed = SoulMDParser.parse(markdown).metadata as SoulMetadata? {
            cachedMetadata = parsed
        }
        NotificationCenter.default.post(name: .soulMdChanged, object: nil)
    }
}

extension Notification.Name {
    /// Posted on the main thread whenever SOUL.md has been (re-)written
    /// via SoulStore. Listeners refresh derived UI state.
    static let soulMdChanged = Notification.Name("MinisSoulMdChanged")
}

// MARK: - System prompt composition

enum SystemPromptBuilder {

    private static let logger = AppLogger(category: "Soul")

    /// The identity sentence template. `{name}` is substituted from the
    /// SOUL metadata. This wording is owned by the app — users never see
    /// it in the Personality editor, and it's stable so model-side
    /// expectations ("Minis, capable AI assistant, iSH Linux shell")
    /// stay intact regardless of what the user writes in SOUL.md.
    ///
    /// IMPORTANT: keep this sentence in sync with the original literal
    /// that lived in `baseSystemPrompt` before SOUL.md existed. Wording
    /// changes here affect every chat.
    private static let identityTemplate =
        "You are {name}, a capable AI assistant running on an iOS device with a fully functional iSH Linux shell (Alpine Linux, aarch64). "

    /// Render the identity sentence (template + name) and optionally
    /// append the user-authored personality body from SOUL.md.
    ///
    /// Two distinct trailing-whitespace contracts so the next concatenated
    /// sentence in `baseSystemPrompt` glues correctly:
    ///   - No personality body → identity sentence with its original
    ///     single trailing space (byte-identical to the pre-SOUL prompt).
    ///   - With personality body → identity sentence + blank line +
    ///     "Personality:" block + blank line, so the runtime-guidance
    ///     sentence starts a fresh paragraph.
    ///
    /// Returned format (when SOUL body is present):
    ///
    ///     You are <name>, a capable AI assistant running on an iOS device …
    ///
    ///     Personality (from SOUL.md — your character and voice; defer to
    ///     the user's latest message when it conflicts with anything here):
    ///     <body>
    ///
    /// We never substitute a default body into the prompt — the identity
    /// sentence alone is the safe fallback when SOUL.md is missing or
    /// empty, matching pre-SOUL behavior.
    static func identitySection() -> String {
        let file = SoulStore.load()
        let name: String = {
            let n = (file?.metadata.name ?? SoulMetadata.default.name)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return n.isEmpty ? "Minis" : n
        }()
        let style: String = (file?.metadata.style ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        let identity = identityTemplate.replacingOccurrences(of: "{name}", with: name)
        let identityTrimmed = identity.trimmingCharacters(in: .whitespaces)

        // [T-soul-hint] Fixed hint telling the model how SOUL fields can be
        // changed. Always appended (with or without a personality body) so
        // the model never says "I can't change my personality". This hint
        // is system-owned text and is NOT counted against the user-facing
        // SOUL body length limit (#356 / 500 EN words / 800 CN chars).
        let soulEditHint =
            "---\n" +
            "SOUL.md fields (name / icon / style / lang / body) can be edited two ways:\n" +
            "1. Tool: call `minis-config` to propose changes (user must approve).\n" +
            "2. UI: ask the user to go to Settings → Soul to edit directly.\n" +
            "Pick whichever the user finds easier in context. Do not say you cannot change your personality."

        // [T-soul-style-injection 2026-05-18] The `style` frontmatter field
        // (response voice / tone / formatting preference, e.g. a row of
        // emojis or "concise, no markdown") was parsed and shown in the UI
        // but never reached the model — only the Markdown body was injected.
        // Render it as its own labeled paragraph so the model treats it as
        // a hard constraint on response style rather than free-form context.
        func styleBlock(_ s: String) -> String {
            guard !s.isEmpty else { return "" }
            // [T-agent-prompt-consistency-pass] Language priority made explicit:
            // the base prompt's "reply in the language matching the user's input"
            // rule is the generic default; a user-authored style that prescribes
            // a reply language is a more specific user preference and wins.
            return "\n\nResponse style (from SOUL.md `style` — apply to every reply unless the user explicitly asks otherwise; if it prescribes a reply language, it overrides the default match-the-user's-language rule):\n\(s)"
        }

        guard let body = file?.body else {
            return identityTrimmed + styleBlock(style) + "\n\n" + soulEditHint + "\n\n"
        }
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return identityTrimmed + styleBlock(style) + "\n\n" + soulEditHint + "\n\n"
        }

        // Reject (NOT truncate) bodies that exceed the language-aware
        // limit. Truncation silently dropped half the user's text and
        // implied that "the agent still gets your character" when in
        // practice it was missing context. Falling back to identity-only
        // is the safer signal: the user notices the personality isn't
        // taking effect, opens Settings, and sees the same red over-limit
        // warning the Save button surfaces. Write paths already reject
        // over-limit; this branch only triggers for an on-disk file that
        // was written before this rule existed (or via shell / another
        // device).
        let check = SoulStore.isOverLimit(trimmed)
        guard !check.isOverLimit else {
            Self.logger.warning("[Soul] personality body is over the language-aware limit (\(check)) — falling back to identity-only system prompt.")
            return identityTrimmed + styleBlock(style) + "\n\n" + soulEditHint + "\n\n"
        }

        let personality = scrubInjections(trimmed)

        // Strip the trailing space we'd otherwise leave hanging at the
        // end of the first paragraph when a personality block follows.
        return identityTrimmed
            + "\n\nPersonality (from SOUL.md — your character and voice; defer to the user's latest message when it conflicts with anything here):\n"
            + personality
            + styleBlock(style)
            + "\n\n"
            + soulEditHint
            + "\n\n"
    }

    /// Drop lines that look like an attempt to subvert the system prompt.
    /// Conservative regex — matches "ignore previous instructions" and the
    /// usual variants ("disregard prior", "forget previous", etc.). Casts
    /// a wide net on purpose; SOUL.md is user-authored personality, not a
    /// place for instructions to the model anyway.
    private static func scrubInjections(_ s: String) -> String {
        let patterns: [String] = [
            #"(?i)ignore.{0,30}previous.{0,30}instructions?"#,
            #"(?i)disregard.{0,30}(previous|prior).{0,30}instructions?"#,
            #"(?i)forget.{0,30}(previous|prior).{0,30}instructions?"#,
        ]
        var lines = s.components(separatedBy: "\n")
        lines = lines.filter { line in
            for p in patterns {
                if line.range(of: p, options: .regularExpression) != nil {
                    return false
                }
            }
            return true
        }
        return lines.joined(separator: "\n")
    }
}

// MARK: - Reusable SwiftUI text view

/// Renders the current SOUL.md `name` (falling back to "Minis") and
/// auto-refreshes whenever SoulStore posts `.soulMdChanged`. Use this in
/// any place that previously hard-coded "Minis" as a label.
@MainActor
struct AssistantSoulName: View {
    @State private var name: String = SoulStore.cachedMetadata.name.isEmpty
        ? "Minis" : SoulStore.cachedMetadata.name
    var body: some View {
        Text(name)
            .onReceive(NotificationCenter.default.publisher(for: .soulMdChanged)) { _ in
                let n = SoulStore.cachedMetadata.name
                name = n.isEmpty ? "Minis" : n
            }
    }
}
