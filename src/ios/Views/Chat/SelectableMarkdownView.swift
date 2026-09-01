import AVFoundation
import Combine
import CoreText
import Photos
import SwiftUI
import UIKit

private let imgLogger = AppLogger(category: "MinisImage")
private let attachLogger = AppLogger(category: "AttachDebug")
/// [T-ios-resign-first-responder-graph-reentry] Deferred responder-chain walks.
private let responderLogger = AppLogger(category: "MarkdownResponder")

// MARK: - minis:// URL encoding [T-minis-url-fullwidth-pipe-ios]

/// Percent-encode the path of a `minis://` destination that contains raw,
/// unencoded bytes (e.g. a filename with U+FF5C `｜`, CJK, emoji the LLM emitted
/// unencoded inside a minis:// link), returning a parseable URL (or nil if it
/// still can't be formed). Used by both the link path (SelectableMarkdownTheme)
/// and the image path (ImageAttachment), so it lives at file scope.
func encodeRawMinisURL(_ destination: String) -> URL? {
    let prefix = "minis://"
    guard destination.hasPrefix(prefix) else { return URL(string: destination) }
    let rest = String(destination.dropFirst(prefix.count))
    let encoded = rest.split(separator: "/", omittingEmptySubsequences: false)
        .map { percentEncodePathSegmentIdempotent(String($0)) }
        .joined(separator: "/")
    return URL(string: prefix + encoded)
}

/// Encode a single path segment idempotently: DECODE any existing percent escapes
/// first, then re-encode once. Normalizes mixed inputs (partly raw, partly
/// already-encoded, e.g. `cover%EF%BD%9C某某`) to a single clean encoding instead
/// of double-encoding the already-escaped bytes (`%EF…`→`%25EF…`). Fully-raw and
/// fully-encoded inputs both round-trip to the same result.
func percentEncodePathSegmentIdempotent(_ seg: String) -> String {
    let decoded = seg.removingPercentEncoding ?? seg
    return decoded.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? seg
}

// MARK: - MinisToast

/// [T-toast-feedback] Lightweight, self-dismissing toast for one-shot action
/// feedback ("Saved", "Table copied", …). Window-level so it works from both
/// SwiftUI screens and UIKit views (e.g. the markdown table copy actions,
/// which run in a UILabel subclass with no SwiftUI context). Mirrors the
/// capsule style FileBrowserView already uses for its "Copied" toast.
enum MinisToast {
    /// Show a toast with an already-localized message. Safe from any thread.
    /// `systemImage` sets the leading icon (default: success checkmark); pass an
    /// error glyph like "exclamationmark.triangle.fill" for failures.
    static func show(_ message: String, duration: TimeInterval = 1.6,
                     systemImage: String = "checkmark.circle.fill") {
        if Thread.isMainThread {
            present(message, duration: duration, systemImage: systemImage)
        } else {
            DispatchQueue.main.async { present(message, duration: duration, systemImage: systemImage) }
        }
    }

    private static func present(_ message: String, duration: TimeInterval,
                                systemImage: String = "checkmark.circle.fill") {
        guard let window = keyWindow() else { return }

        let label = UILabel()
        label.text = message
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = .white
        label.numberOfLines = 1
        label.translatesAutoresizingMaskIntoConstraints = false

        let check = UIImageView(image: UIImage(systemName: systemImage))
        check.tintColor = .white
        check.contentMode = .scaleAspectFit
        check.translatesAutoresizingMaskIntoConstraints = false

        let capsule = UIView()
        capsule.backgroundColor = UIColor.black.withAlphaComponent(0.8)
        capsule.layer.cornerRadius = 18
        capsule.layer.cornerCurve = .continuous
        capsule.translatesAutoresizingMaskIntoConstraints = false
        capsule.alpha = 0

        capsule.addSubview(check)
        capsule.addSubview(label)
        window.addSubview(capsule)

        NSLayoutConstraint.activate([
            check.leadingAnchor.constraint(equalTo: capsule.leadingAnchor, constant: 16),
            check.centerYAnchor.constraint(equalTo: capsule.centerYAnchor),
            check.widthAnchor.constraint(equalToConstant: 16),
            check.heightAnchor.constraint(equalToConstant: 16),
            label.leadingAnchor.constraint(equalTo: check.trailingAnchor, constant: 8),
            label.trailingAnchor.constraint(equalTo: capsule.trailingAnchor, constant: -16),
            label.topAnchor.constraint(equalTo: capsule.topAnchor, constant: 10),
            label.bottomAnchor.constraint(equalTo: capsule.bottomAnchor, constant: -10),
            capsule.centerXAnchor.constraint(equalTo: window.centerXAnchor),
            capsule.bottomAnchor.constraint(equalTo: window.safeAreaLayoutGuide.bottomAnchor, constant: -48),
            capsule.leadingAnchor.constraint(greaterThanOrEqualTo: window.leadingAnchor, constant: 24),
            capsule.trailingAnchor.constraint(lessThanOrEqualTo: window.trailingAnchor, constant: -24),
        ])

        UIView.animate(withDuration: 0.25) { capsule.alpha = 1 }
        UIView.animate(withDuration: 0.3, delay: duration, options: [.curveEaseIn]) {
            capsule.alpha = 0
        } completion: { _ in
            capsule.removeFromSuperview()
        }
    }

    private static func keyWindow() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        return windows.first { $0.isKeyWindow } ?? windows.first
    }
}

// MARK: - Custom Attributed String Keys

extension NSAttributedString.Key {
    static let inlineCodeBackground = NSAttributedString.Key("minisInlineCodeBackground")
    static let inlineCodeText = NSAttributedString.Key("minisInlineCodeText")
    static let blockquoteDepth = NSAttributedString.Key("minisBlockquoteDepth")
}

// MARK: - Media Extension Sets

private let nativeImageExts: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg"]
private let nativeVideoExts: Set<String> = ["mp4", "mov", "m4v", "avi", "mkv", "webm"]
private let nativeAudioExts: Set<String> = ["mp3", "m4a", "wav", "aac", "ogg", "flac"]

// MARK: - Media Image Cache

final class NativeMediaImageCache {
    static let shared = NativeMediaImageCache()
    private let cache = NSCache<NSString, UIImage>()
    /// Persistent record of image pixel sizes, keyed by canonical source URL.
    /// Used by `ImageAttachment.attachmentBounds` to compute the correct
    /// attachment height *before* the bitmap is loaded, so the cell self-sizes
    /// to the final layout on the very first pass — instead of locking at the
    /// 200pt placeholder, then losing the trailing markdown text below the
    /// image to clipsToBounds when the late `invalidateCellSizeIfNeeded` grow
    /// is rejected by SwiftUI's UIHostingConfiguration cache.
    ///
    /// Backed by UserDefaults so cold starts (the symptom case: app killed →
    /// re-entered conversation, or message-list session re-load from disk)
    /// already have the size on the very first attachmentBounds call.
    /// [AttachHang preload-bounds]
    private static let defaultsKey = "MinisImageSizes_v1"
    private var sizes: [String: CGSize]
    private let sizesLock = NSLock()
    private init() {
        cache.countLimit = 50
        // Hydrate from UserDefaults. Stored as ["src": "wxh"] (string-encoded
        // so the dictionary serialises cleanly through plist encoding).
        var loaded: [String: CGSize] = [:]
        if let raw = UserDefaults.standard.dictionary(forKey: Self.defaultsKey) as? [String: String] {
            for (k, v) in raw {
                let parts = v.split(separator: "x")
                if parts.count == 2,
                   let w = Double(parts[0]),
                   let h = Double(parts[1]) {
                    loaded[k] = CGSize(width: w, height: h)
                }
            }
        }
        self.sizes = loaded
    }

    func image(for key: String) -> UIImage? { cache.object(forKey: key as NSString) }
    func set(_ image: UIImage, for key: String) { cache.setObject(image, forKey: key as NSString) }

    // MARK: - [T-ios-failed-image-refetch-storm] Negative (failure) cache
    //
    // A broken HTTP(S) image URL has no per-instance retry budget across cell
    // reuse: scrolling recycles the cell, CACHE-WIPE recreates the
    // ImageAttachment with retriesRemaining reset, so the SAME failing URL
    // re-fetches on every recycle. Each failed fetch fires onLoad → updateAttachment
    // Views → re-render → re-fetch, a main-thread storm that shows up as the
    // residual scroll-deceleration jank on image-heavy sessions (device log:
    // 28 MinisImage + 19 AttachHotPath events on a single 115ms landing frame,
    // all the same FAILED url). Record failures process-wide with a short TTL
    // so a recycled cell doesn't re-hit the network; the TTL lets a transient
    // network error recover later (a fresh attempt after the window).
    private var failedSources: [String: CFAbsoluteTime] = [:]
    private let failedLock = NSLock()
    /// How long a failed source stays suppressed before a retry is allowed.
    private let failureTTL: CFAbsoluteTime = 60

    /// True if `src` failed to load within the last `failureTTL` seconds.
    func isRecentlyFailed(_ src: String) -> Bool {
        failedLock.lock(); defer { failedLock.unlock() }
        guard let t = failedSources[src] else { return false }
        if CFAbsoluteTimeGetCurrent() - t > failureTTL {
            failedSources.removeValue(forKey: src)  // expired — allow a fresh attempt
            return false
        }
        return true
    }

    /// Record that `src` failed to produce an image (negative-cache it).
    func recordFailure(_ src: String) {
        failedLock.lock(); failedSources[src] = CFAbsoluteTimeGetCurrent(); failedLock.unlock()
    }

    /// Clear a source's failure record (call on a successful load so a URL that
    /// recovered isn't suppressed by a stale entry).
    func clearFailure(_ src: String) {
        failedLock.lock(); failedSources.removeValue(forKey: src); failedLock.unlock()
    }

    /// Lookup the recorded pixel size for a source URL (canonical form, no
    /// fingerprint suffix). Returns nil if we've never loaded this URL.
    func size(forSource src: String) -> CGSize? {
        sizesLock.lock(); defer { sizesLock.unlock() }
        return sizes[src]
    }
    /// Record a source URL → pixel-size mapping. Call once after a successful
    /// image decode. Persists to UserDefaults so cold starts can use it.
    func recordSize(_ size: CGSize, forSource src: String) {
        sizesLock.lock()
        if sizes[src] == size {
            sizesLock.unlock()
            return
        }
        sizes[src] = size
        let snapshot = sizes
        sizesLock.unlock()
        // Encode and persist outside the lock (UserDefaults can be slow).
        let encoded: [String: String] = snapshot.mapValues { "\($0.width)x\($0.height)" }
        UserDefaults.standard.set(encoded, forKey: Self.defaultsKey)
    }
}

// MARK: - Downsample Helper

/// Downsample image data. maxPixelSize controls the longest edge in pixels.
func downsampleImageData(_ data: Data, maxPixelSize: CGFloat = 2048) -> UIImage? {
    let opts: [CFString: Any] = [kCGImageSourceShouldCache: false]
    guard let source = CGImageSourceCreateWithData(data as CFData, opts as CFDictionary) else { return nil }
    let downsampleOpts: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
    ]
    guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, downsampleOpts as CFDictionary) else {
        return UIImage(data: data)
    }
    return UIImage(cgImage: cg)
}

/// [T-ios-image-single-writer] The `maxPixelSize` for decoding a chat-transcript
/// image at DISPLAY size, computed from the header dimensions in `data`.
///
/// Chat images render at ≤ `ImageAttachment.maxImageWidth` points wide, so the
/// sharpest bitmap the screen can ever show is `maxWidth × screenScale` pixels
/// wide. Decoding straight to that target replaces the old two-stage pipeline
/// (decode at a generic 2048 cap, then re-decode from disk at ~2× display width
/// from inside `attachmentBounds`) whose second stage was both a memory
/// double-spend and the placeholder-stuck race described at that call site.
///
/// `CGImageSourceThumbnailMaxPixelSize` caps the LONGEST side, so a tall
/// portrait image capped naively at the width target would come out soft: for
/// aspect > 1 the cap is scaled up so the WIDTH still lands on the target,
/// bounded by the old 2048 ceiling so a pathological strip image cannot blow
/// the decode budget. Wide images cap at the width target directly — strictly
/// less memory than the old 2048 first stage, and at 3× scale strictly sharper
/// than the old second stage (which targeted 2× display width).
///
/// `screenScale` is passed in (not read from UIScreen here) so callers off the
/// main thread stay warning-free; capture it before detaching.
func displayTargetPixels(for data: Data, screenScale: CGFloat) -> CGFloat {
    let widthTargetPx = 400.0 * max(screenScale, 1)   // ImageAttachment.maxImageWidth
    let hardCap: CGFloat = 2048
    let opts = [kCGImageSourceShouldCache: false] as CFDictionary
    guard let src = CGImageSourceCreateWithData(data as CFData, opts),
          let props = CGImageSourceCopyPropertiesAtIndex(src, 0, opts) as? [CFString: Any],
          let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
          let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
          w > 0, h > 0 else {
        // Unreadable header — fall back to the historical generic cap.
        return hardCap
    }
    // EXIF orientations 5-8 swap displayed axes; use the displayed width.
    var dispW = w, dispH = h
    if let o = (props[kCGImagePropertyOrientation] as? NSNumber)?.uint32Value, (5...8).contains(o) {
        swap(&dispW, &dispH)
    }
    let aspect = dispH / dispW
    let longestForSharpWidth = aspect > 1 ? widthTargetPx * aspect : widthTargetPx
    return min(longestForSharpWidth, hardCap)
}

// MARK: - SelectableMarkdownTheme

/// [T-inline-code-dark-bg-ios] Inline-code span background, shared by the
/// theme and the layout manager's fillBackgroundRectArray (the actual paint
/// site) so the two can never drift. Light stays EXACTLY .systemGray6
/// (#F2F2F7); dark lifts to #3A3A3C (systemGray4's dark value) — systemGray6
/// resolves to #1C1C1E in dark, indistinguishable from the near-black chat
/// background, which made inline code read as bare orange text.
private let minisInlineCodeBackgroundColor = UIColor { traits in
    traits.userInterfaceStyle == .dark
        ? UIColor(red: 0x3A / 255.0, green: 0x3A / 255.0, blue: 0x3C / 255.0, alpha: 1)
        : .systemGray6
}

/// Mirrors the `.minisChat` MarkdownUI theme using UIKit types.
struct SelectableMarkdownTheme {
    let baseFontSize: CGFloat
    let codeBlockCornerRadius: CGFloat = 8
    let inlineCodeCornerRadius: CGFloat = 5

    init(baseFontSize: CGFloat? = nil) {
        self.baseFontSize = baseFontSize ?? 16.5
    }

    var baseFont: UIFont { .systemFont(ofSize: baseFontSize) }
    var labelColor: UIColor { .label }
    var secondaryLabelColor: UIColor { .secondaryLabel }
    var accentColor: UIColor { .systemOrange }
    var linkColor: UIColor { .systemBlue }
    var codeBlockBackground: UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.15, alpha: 1) : .black }
    }
    var codeBlockTextColor: UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.55, green: 0.95, blue: 0.55, alpha: 1) : .systemGreen }
    }
    var inlineCodeBackground: UIColor { minisInlineCodeBackgroundColor }
    var inlineCodeColor: UIColor { .systemOrange }
    var blockquoteBarColor: UIColor { UIColor.systemOrange.withAlphaComponent(0.5) }
    var tableBorderColor: UIColor { UIColor.label.withAlphaComponent(0.25) }

    func headingFont(level: Int) -> UIFont {
        let size: CGFloat
        let weight: UIFont.Weight
        switch level {
        case 1: size = baseFontSize * 1.5; weight = .bold
        case 2: size = baseFontSize * 1.3; weight = .bold
        case 3: size = baseFontSize * 1.15; weight = .semibold
        case 5: size = baseFontSize * 0.875; weight = .semibold
        case 6: size = baseFontSize * 0.85; weight = .semibold
        default: size = baseFontSize; weight = .semibold // H4 and fallback
        }
        return .systemFont(ofSize: size, weight: weight)
    }

    var inlineCodeFont: UIFont {
        let size = baseFontSize * 0.845
        if let menlo = UIFont(name: "Menlo", size: size) {
            let descriptor = menlo.fontDescriptor.addingAttributes([
                .cascadeList: [UIFontDescriptor(fontAttributes: [.name: "PingFang SC"])]
            ])
            return UIFont(descriptor: descriptor, size: size)
        }
        return .monospacedSystemFont(ofSize: size, weight: .regular)
    }

    var codeBlockFont: UIFont {
        let size = baseFontSize * 0.85
        // Use Menlo as the base monospaced font, with PingFang SC as CJK fallback.
        // PingFang SC's CJK glyphs are exactly 2x the width of Menlo's ASCII glyphs
        // at the same point size, enabling proper table alignment in code blocks.
        if let menlo = UIFont(name: "Menlo", size: size) {
            let descriptor = menlo.fontDescriptor.addingAttributes([
                .cascadeList: [UIFontDescriptor(fontAttributes: [.name: "PingFang SC"])]
            ])
            return UIFont(descriptor: descriptor, size: size)
        }
        return .monospacedSystemFont(ofSize: size, weight: .regular)
    }
}

/// Renders markdown blocks into an NSAttributedString using a fresh MarkdownNSRenderer.
/// Intended for one-shot rendering of completed messages (e.g. caching in AIChatViewModel).
@MainActor
func renderMarkdownBlocks(_ blocks: [BlockNode]) -> NSAttributedString {
    let renderer = MarkdownNSRenderer(baseFontSize: FontSettings.shared.scaledMessage(16.5))
    return renderer.render(blocks: blocks)
}

// MARK: - MarkdownNSRenderer

/// Converts `[BlockNode]` → `NSMutableAttributedString` for display in a UITextView.
fileprivate final class MarkdownNSRenderer {
    private(set) var theme: SelectableMarkdownTheme

    /// Assistant message this renderer belongs to. Set by `SelectableMarkdownView`
    /// before each `render(blocks:)` call so inline image attachments can route
    /// tap gestures back to a paged gallery of all sibling images in the message.
    /// nil for contexts that don't provide a message (e.g. standalone previews).
    var messageId: UUID?
    /// Block identity for table slot-cache disambiguation. Set per render()
    /// call; when non-nil the slot key is simply messageId:blockId — immune
    /// to content collisions between tables with identical rows.
    var blockId: UUID?

    init(baseFontSize: CGFloat? = nil) {
        self.theme = SelectableMarkdownTheme(baseFontSize: baseFontSize)
    }

    func updateFontSize(_ size: CGFloat) {
        theme = SelectableMarkdownTheme(baseFontSize: size)
    }
    private var bodyLineSpacing: CGFloat { theme.baseFontSize * 0.25 }
    /// minisChat list item: .em(0.25) top margin
    private var listItemTopMargin: CGFloat { theme.baseFontSize * 0.25 }
    /// minisChat headings: .em(1) top and bottom
    private var headingTopMargin: CGFloat { theme.baseFontSize }
    private var headingBottomMargin: CGFloat { theme.baseFontSize }

    /// Reuse existing media attachments across re-renders to avoid reload flicker during streaming.
    var imageAttachmentCache: [String: ImageAttachment] = [:]
    var videoAttachmentCache: [String: VideoAttachment] = [:]
    var audioAttachmentCache: [String: AudioAttachment] = [:]
    /// Reuse code block attachments by index to preserve ObjectIdentifier across streaming re-renders.
    var codeBlockAttachmentCache: [Int: CodeBlockAttachment] = [:]
    /// Content hashes for code blocks, keyed by sequential index.
    var codeBlockContentHashes: [Int: Int] = [:]
    /// Reuse table attachments by content hash so the same table layout
    /// can be reused even when re-rendered in a different message slot
    /// or after the renderer was wiped for an unrelated reason.
    ///
    /// HangFix(2026-05-14): the previous `[Int: …]` keyed by streaming
    /// index meant that whenever `isNewMessage` triggered a renderer wipe
    /// (e.g. when the cell is reused with a different assistant message),
    /// every TableAttachment was rebuilt and its `cachedLayout` reset to
    /// nil — forcing typesetter to run a full per-cell `boundingRect`
    /// pass on each `attachmentBounds` query. On a 6-table message that
    /// was 27 s of main-thread typesetter pin (ips Minis-2026-05-14-094312).
    /// Keying by contentHash lets re-rendered identical tables hit the
    /// previously-built attachment + its `cachedLayout`.
    var tableAttachmentCache: [Int: TableAttachment] = [:]
    /// LRU eviction order for `tableAttachmentCache` so the cache can't
    /// grow without bound when the user scrolls through long history.
    /// Most-recently-touched contentHash sits at the end.
    var tableAttachmentLRU: [Int] = []
    private let tableAttachmentCacheCap = 64
    /// HangFix(2026-05-14) — per-slot identity preservation for in-progress
    /// streaming tables. When a table at the same `tableIndex` keeps
    /// growing row-by-row, its content hash changes every flush but it's
    /// the *same* visual table. Calling `update()` on the existing
    /// TableAttachment instance preserves NSTextAttachment identity, which
    /// keeps `updateUIView`'s prefix-equality fast path alive and lets
    /// `updateAttachmentViews` swap only the affected TableScrollView in
    /// place rather than tearing down every attachment view. Without this,
    /// every streamed token rebuilds every attachment subview → flicker +
    /// O(N) work per flush.
    /// Key: "<messageUUID>:<tableIndex>". Without messageId, two different
    /// messages that both have a table at idx=2 would falsely share a
    /// slot during render — the new message's first render would call
    /// `update()` on the previous message's table data. Tracking by
    /// (messageId, idx) keeps streaming-growth detection scoped to the
    /// single message that's actually growing.
    var tableSlotCache: [String: TableAttachment] = [:]
    /// Map TableAttachment instance → its current contentHash key in
    /// `tableAttachmentCache` so we can re-key when `update()` mutates an
    /// in-place attachment. Keyed by ObjectIdentifier hashValue.
    var tableAttachmentReverseKey: [ObjectIdentifier: Int] = [:]
    var mathAttachmentCache: [String: MathAttachment] = [:]
    private var codeBlockIndex = 0
    private var tableIndex = 0

    private struct RenderedBlock {
        let attributed: NSAttributedString
        let topMargin: CGFloat?
        let bottomMargin: CGFloat?
        let isBlockAttachment: Bool

        init(attributed: NSAttributedString, topMargin: CGFloat?, bottomMargin: CGFloat?, isBlockAttachment: Bool = false) {
            self.attributed = attributed
            self.topMargin = topMargin
            self.bottomMargin = bottomMargin
            self.isBlockAttachment = isBlockAttachment
        }
    }

    func render(blocks: [BlockNode]) -> NSAttributedString {
        codeBlockIndex = 0
        tableIndex = 0
        let t0 = CFAbsoluteTimeGetCurrent()
        let tableCountBefore = tableAttachmentCache.count

        let result = renderBlockSequence(blocks, listDepth: 0, quoteDepth: 0, tightSpacing: false).attributed
        let elapsed = (CFAbsoluteTimeGetCurrent() - t0) * 1000

        // Only log noisy cases to keep signal-to-noise high: slow renders, or
        // renders that touched tables (streaming table bug suspect).
        if elapsed >= 20 || tableIndex > 0 {
            let level = elapsed >= 100 ? "🔥" : ""
            // [T-ios-markdown-rerender-burst] Include messageId + rendererPtr so
            // we can distinguish "1 textView rendering N times" from "N
            // different textViews rendering the same content" (cell reuse vs
            // SwiftUI re-invalidation of the same coordinator).
            let mid = messageId?.uuidString.prefix(8) ?? "?"
            let rId = String(ObjectIdentifier(self).hashValue & 0xFFFFFF, radix: 16)
            Self.rendererLogger.info("[RND]\(level) mid=\(mid) rnd=\(rId) blocks=\(blocks.count) tables=\(tableIndex) codeBlocks=\(codeBlockIndex) attrLen=\(result.length) \(String(format: "%.1f", elapsed))ms tableCache=\(tableCountBefore)→\(tableAttachmentCache.count)")
        }
        return result
    }

    fileprivate static let rendererLogger = AppLogger(category: "MarkdownRenderer")

    // MARK: Block Rendering

    private func renderBlock(_ block: BlockNode, listDepth: Int, quoteDepth: Int) -> RenderedBlock {
        switch block {
        case .paragraph(let content):
            return renderParagraph(content, quoteDepth: quoteDepth)
        case .heading(let level, let content):
            return renderHeading(level: level, content: content, quoteDepth: quoteDepth)
        case .codeBlock(let fenceInfo, let content):
            return renderCodeBlockAttachment(fenceInfo: fenceInfo, content: content, quoteDepth: quoteDepth)
        case .blockquote(let children):
            return renderBlockquote(children: children, listDepth: listDepth, quoteDepth: quoteDepth)
        case .bulletedList(let isTight, let items):
            return renderBulletedList(isTight: isTight, items: items, listDepth: listDepth, quoteDepth: quoteDepth)
        case .numberedList(let isTight, let start, let items):
            return renderNumberedList(isTight: isTight, start: start, items: items, listDepth: listDepth, quoteDepth: quoteDepth)
        case .taskList(let isTight, let items):
            return renderTaskList(isTight: isTight, items: items, listDepth: listDepth, quoteDepth: quoteDepth)
        case .table(let alignments, let rows):
            return renderTableAttachment(alignments: alignments, rows: rows)
        case .thematicBreak:
            return renderThematicBreakAttachment()
        case .htmlBlock(let content):
            return renderHTMLBlock(content, quoteDepth: quoteDepth)
        case .mathBlock(let content):
            return renderMathBlockAttachment(latex: content)
        }
    }

    private func renderBlockSequence(_ blocks: [BlockNode], listDepth: Int, quoteDepth: Int, tightSpacing: Bool) -> RenderedBlock {
        mergeRenderedBlocks(
            blocks.map { renderBlock($0, listDepth: listDepth, quoteDepth: quoteDepth) },
            tightSpacing: tightSpacing
        )
    }

    private func mergeRenderedBlocks(_ blocks: [RenderedBlock], tightSpacing: Bool) -> RenderedBlock {
        guard let first = blocks.first else {
            return RenderedBlock(attributed: NSAttributedString(), topMargin: nil, bottomMargin: nil)
        }

        let result = NSMutableAttributedString(attributedString: first.attributed)
        for i in 1..<blocks.count {
            let previous = blocks[i - 1]
            let current = blocks[i]
            let suppressBottom = tightSpacing && !previous.isBlockAttachment && !current.isBlockAttachment
            let previousBottom = suppressBottom ? 0 : (previous.bottomMargin ?? 0)
            let spacing = max(current.topMargin ?? 0, previousBottom)
            // Apply spacing as paragraphSpacing on the last paragraph of the accumulated result
            addSpacingAfterBlock(result, spacing: spacing)
            result.append(blockSeparator(height: spacing))
            result.append(current.attributed)
        }

        return RenderedBlock(
            attributed: result,
            topMargin: first.topMargin,
            bottomMargin: blocks.last?.bottomMargin
        )
    }

    private func blockSeparator(height: CGFloat) -> NSAttributedString {
        // Strategy: inject paragraphSpacing (after) on the previous block's trailing \n,
        // rather than trying to create an independent spacer paragraph.
        // This is handled by addSpacingAfterLastParagraph on the previous block.
        // Here we just return a plain \n to separate paragraphs.
        let separator = NSMutableAttributedString(string: "\n")
        let style = NSMutableParagraphStyle()
        style.minimumLineHeight = 0
        style.maximumLineHeight = 0.01
        style.lineSpacing = 0
        separator.addAttributes([
            .font: UIFont.systemFont(ofSize: 0.01),
            .paragraphStyle: style,
        ], range: NSRange(location: 0, length: 1))
        return separator
    }

    /// Sets `paragraphSpacing` on the last paragraph of the attributed string
    /// so TextKit adds space after it.
    private func addSpacingAfterBlock(_ attrStr: NSMutableAttributedString, spacing: CGFloat) {
        guard attrStr.length > 0 else { return }
        // Find the last paragraph's range
        let text = attrStr.string as NSString
        let lastParaRange = text.paragraphRange(for: NSRange(location: max(text.length - 1, 0), length: 0))
        let style: NSMutableParagraphStyle
        if let existing = attrStr.attribute(.paragraphStyle, at: lastParaRange.location, effectiveRange: nil) as? NSParagraphStyle {
            style = existing.mutableCopy() as! NSMutableParagraphStyle
        } else {
            style = NSMutableParagraphStyle()
        }
        style.paragraphSpacing = spacing
        attrStr.addAttribute(.paragraphStyle, value: style, range: lastParaRange)
    }

    // MARK: Paragraph

    private func renderParagraph(_ inlines: [InlineNode], quoteDepth: Int) -> RenderedBlock {
        var baseAttrs = baseAttributes(quoteDepth: quoteDepth)
        let style: NSMutableParagraphStyle
        if let existing = baseAttrs[.paragraphStyle] as? NSParagraphStyle {
            style = existing.mutableCopy() as! NSMutableParagraphStyle
        } else {
            style = NSMutableParagraphStyle()
        }
        style.lineSpacing = bodyLineSpacing
        baseAttrs[.paragraphStyle] = style
        return RenderedBlock(
            attributed: renderInlines(inlines, baseAttributes: baseAttrs),
            topMargin: 0,
            bottomMargin: 12
        )
    }

    // MARK: Heading

    private func renderHeading(level: Int, content: [InlineNode], quoteDepth: Int) -> RenderedBlock {
        var attrs = baseAttributes(quoteDepth: quoteDepth)
        attrs[.font] = theme.headingFont(level: level)
        let style = NSMutableParagraphStyle()
        // minisChat doesn't set relativeLineSpacing for headings
        style.lineSpacing = 0
        if quoteDepth > 0 {
            style.headIndent = CGFloat(quoteDepth) * Self.quoteIndentPerLevel
            style.firstLineHeadIndent = style.headIndent
        }
        attrs[.paragraphStyle] = style
        return RenderedBlock(
            attributed: renderInlines(content, baseAttributes: attrs),
            topMargin: headingTopMargin,
            bottomMargin: headingBottomMargin
        )
    }

    // MARK: Code Block (NSTextAttachment)

    private func renderCodeBlockAttachment(fenceInfo: String?, content: String, quoteDepth: Int = 0) -> RenderedBlock {
        // [T-ios-codeblock-default-text-lang] A fence with no info string (a bare
        // ``` block) has no language. Default it to "text" so every code block
        // shows a language label (and reserves the header space) uniformly —
        // an unlabeled block now reads as "text" instead of being blank.
        let parsedLang = fenceInfo?.split(separator: " ").first.map(String.init)
        let language = (parsedLang?.isEmpty == false) ? parsedLang : "text"
        let trimmed = content.hasSuffix("\n") ? String(content.dropLast()) : content
        let idx = codeBlockIndex
        codeBlockIndex += 1

        var hasher = Hasher()
        hasher.combine(trimmed)
        hasher.combine(language)
        let contentHash = hasher.finalize()

        let attachment: CodeBlockAttachment
        if let cached = codeBlockAttachmentCache[idx], codeBlockContentHashes[idx] == contentHash {
            // Exact same content — reuse object so TextKit skips attachmentBounds recalc
            cached.blockIndex = idx
            attachment = cached
        } else {
            // Content changed or first time — create fresh object so TextKit recalculates bounds.
            // The previous view will be updated in-place by updateAttachmentViews via codeBlockViewCache.
            attachment = CodeBlockAttachment(code: trimmed, language: language, theme: theme, quoteDepth: quoteDepth, contentFingerprint: contentHash)
            attachment.blockIndex = idx
            codeBlockAttachmentCache[idx] = attachment
            codeBlockContentHashes[idx] = contentHash
        }

        let result = NSMutableAttributedString(attachment: attachment)
        result.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: result.length))
        return RenderedBlock(
            attributed: result,
            topMargin: 6,
            bottomMargin: 4,
            isBlockAttachment: true
        )
    }

    // MARK: Blockquote

    private func renderBlockquote(children: [BlockNode], listDepth: Int, quoteDepth: Int) -> RenderedBlock {
        let newDepth = quoteDepth + 1
        let renderedChildren = renderBlockSequence(children, listDepth: listDepth, quoteDepth: newDepth, tightSpacing: false)
        let result = NSMutableAttributedString(attributedString: renderedChildren.attributed)
        // Mark the entire range with blockquote depth for bar drawing
        let fullRange = NSRange(location: 0, length: result.length)
        if fullRange.length > 0 {
            // Only fill in ranges that are missing a depth or carry a
            // shallower one. Children have already been rendered at
            // `newDepth + 1`…, so a blanket addAttribute (replace semantics)
            // used to stomp a nested quote's depth=2 back down to the outer
            // depth=1 — the text still indented for two levels (paragraph
            // style records that separately) but only one bar was drawn.
            var deeperRanges: [NSRange] = []
            result.enumerateAttribute(.blockquoteDepth, in: fullRange, options: []) { value, range, _ in
                let existing = (value as? Int) ?? 0
                if existing < newDepth { deeperRanges.append(range) }
            }
            for range in deeperRanges {
                result.addAttribute(.blockquoteDepth, value: newDepth, range: range)
            }
            // minisChat blockquote uses secondaryText color
            result.addAttribute(.foregroundColor, value: theme.secondaryLabelColor, range: fullRange)
        }
        // Blockquote inherits margins from children (no explicit markdownMargin in minisChat)
        return RenderedBlock(
            attributed: result,
            topMargin: renderedChildren.topMargin,
            bottomMargin: renderedChildren.bottomMargin
        )
    }

    // MARK: Bulleted List

    private func renderBulletedList(isTight: Bool, items: [RawListItem], listDepth: Int, quoteDepth: Int) -> RenderedBlock {
        var renderedItems: [RenderedBlock] = []
        let itemStartIndent = CGFloat(quoteDepth) * Self.quoteIndentPerLevel + CGFloat(listDepth) * Self.listIndentPerLevel
        let itemContentIndent = itemStartIndent + Self.listIndentPerLevel

        // HangFix(2026-05-13, re-restored 2026-05-14 with .ips evidence) —
        // Bullet glyphs MUST be Unicode characters, NOT NSTextAttachments.
        // Build 13 reverted bullets to SF-symbol NSTextAttachments per
        // user request to restore visual size — within 90s of install the
        // device hit a fresh 0x8BADF00D watchdog (ips Minis-2026-05-14-
        // 163330). Main-thread frame #1: `NSConcreteTextStorage
        // attribute:atIndex:effectiveRange:` — fillLayoutHole scanning
        // attribute runs. A 6-item list produces 6 image-attachment runs;
        // typesetter cost is O(glyphs × runs) and explodes whenever
        // setSize: forces a full re-layout.
        //
        // To keep bullets visually prominent (the original complaint about
        // "list got smaller") without paying the attachment-run tax, render the
        // Unicode glyph at 1.25× baseFont with bold weight + a small
        // baseline offset so it reads as an emphasized punctuation mark.
        // Depth → glyph:
        //   0  filled circle   "•"  U+2022
        //   1  hollow circle   "◦"  U+25E6
        //   2+ filled square   "▪"  U+25AA
        let bulletGlyph: String
        switch listDepth {
        case 0: bulletGlyph = "\u{2022}"   // •
        case 1: bulletGlyph = "\u{25E6}"   // ◦
        default: bulletGlyph = "\u{25AA}"  // ▪
        }
        let bulletColor = quoteDepth > 0 ? theme.secondaryLabelColor : theme.labelColor
        var bulletAttrs = baseAttributes(quoteDepth: quoteDepth)
        bulletAttrs[.foregroundColor] = bulletColor
        // 1.25× bold makes the Unicode bullet read at roughly the same
        // visual weight as the prior SF-symbol filled circle.
        bulletAttrs[.font] = UIFont.systemFont(ofSize: theme.baseFontSize * 1.25, weight: .bold)
        // Small negative baseline offset so the bullet sits near the
        // body text's x-height rather than its cap-height.
        bulletAttrs[.baselineOffset] = -theme.baseFontSize * 0.05

        for item in items {
            let itemResult = NSMutableAttributedString()
            itemResult.append(NSAttributedString(string: bulletGlyph, attributes: bulletAttrs))
            itemResult.append(NSAttributedString(string: "  ", attributes: baseAttributes(quoteDepth: quoteDepth)))
            let child = renderListItemChildren(item.children, listDepth: listDepth + 1, quoteDepth: quoteDepth, isTight: isTight)
            itemResult.append(child.attributed)
            applyListIndent(
                to: itemResult,
                firstLineIndent: itemStartIndent,
                continuationIndent: itemContentIndent
            )
            renderedItems.append(
                RenderedBlock(
                    attributed: itemResult,
                    topMargin: listItemTopMargin,
                    bottomMargin: child.bottomMargin
                )
            )
        }
        let merged = mergeRenderedBlocks(renderedItems, tightSpacing: isTight)
        return RenderedBlock(
            attributed: merged.attributed,
            topMargin: merged.topMargin,
            bottomMargin: max(merged.bottomMargin ?? 0, 16)
        )
    }

    // MARK: Numbered List

    private func renderNumberedList(isTight: Bool, start: Int, items: [RawListItem], listDepth: Int, quoteDepth: Int) -> RenderedBlock {
        var renderedItems: [RenderedBlock] = []
        let itemStartIndent = CGFloat(quoteDepth) * Self.quoteIndentPerLevel + CGFloat(listDepth) * Self.listIndentPerLevel
        let itemContentIndent = itemStartIndent + Self.listIndentPerLevel

        for (i, item) in items.enumerated() {
            let number = "\(start + i). "
            let itemResult = NSMutableAttributedString(string: number, attributes: baseAttributes(quoteDepth: quoteDepth))
            let child = renderListItemChildren(item.children, listDepth: listDepth + 1, quoteDepth: quoteDepth, isTight: isTight)
            itemResult.append(child.attributed)
            applyListIndent(
                to: itemResult,
                firstLineIndent: itemStartIndent,
                continuationIndent: itemContentIndent
            )
            renderedItems.append(
                RenderedBlock(
                    attributed: itemResult,
                    topMargin: listItemTopMargin,
                    bottomMargin: child.bottomMargin
                )
            )
        }
        let merged = mergeRenderedBlocks(renderedItems, tightSpacing: isTight)
        return RenderedBlock(
            attributed: merged.attributed,
            topMargin: merged.topMargin,
            bottomMargin: max(merged.bottomMargin ?? 0, 16)
        )
    }

    // MARK: Task List

    private func renderTaskList(isTight: Bool, items: [RawTaskListItem], listDepth: Int, quoteDepth: Int) -> RenderedBlock {
        var renderedItems: [RenderedBlock] = []
        let itemStartIndent = CGFloat(quoteDepth) * Self.quoteIndentPerLevel + CGFloat(listDepth) * Self.listIndentPerLevel
        let itemContentIndent = itemStartIndent + Self.listIndentPerLevel

        for item in items {
            let checkbox = item.isCompleted ? "☑ " : "☐ "
            let itemResult = NSMutableAttributedString(string: checkbox, attributes: baseAttributes(quoteDepth: quoteDepth))
            let child = renderListItemChildren(item.children, listDepth: listDepth + 1, quoteDepth: quoteDepth, isTight: isTight)
            itemResult.append(child.attributed)
            applyListIndent(
                to: itemResult,
                firstLineIndent: itemStartIndent,
                continuationIndent: itemContentIndent
            )
            renderedItems.append(
                RenderedBlock(
                    attributed: itemResult,
                    topMargin: listItemTopMargin,
                    bottomMargin: child.bottomMargin
                )
            )
        }
        let merged = mergeRenderedBlocks(renderedItems, tightSpacing: isTight)
        return RenderedBlock(
            attributed: merged.attributed,
            topMargin: merged.topMargin,
            bottomMargin: max(merged.bottomMargin ?? 0, 16)
        )
    }

    private func renderListItemChildren(_ children: [BlockNode], listDepth: Int, quoteDepth: Int, isTight: Bool) -> RenderedBlock {
        renderBlockSequence(children, listDepth: listDepth, quoteDepth: quoteDepth, tightSpacing: isTight)
    }

    private func applyListIndent(to attributed: NSMutableAttributedString, firstLineIndent: CGFloat, continuationIndent: CGFloat) {
        guard attributed.length > 0 else { return }

        let text = attributed.string as NSString
        let total = NSRange(location: 0, length: text.length)
        var cursor = total.location
        var isFirstParagraph = true

        while cursor < NSMaxRange(total) {
            let paragraphRange = text.paragraphRange(for: NSRange(location: cursor, length: 0))
            let effectiveRange = NSIntersectionRange(paragraphRange, total)
            guard effectiveRange.length > 0 else { break }

            let style: NSMutableParagraphStyle
            if let existing = attributed.attribute(.paragraphStyle, at: effectiveRange.location, effectiveRange: nil) as? NSParagraphStyle {
                style = existing.mutableCopy() as! NSMutableParagraphStyle
            } else {
                style = NSMutableParagraphStyle()
            }
            style.lineSpacing = max(style.lineSpacing, bodyLineSpacing)
            style.headIndent = max(style.headIndent, continuationIndent)
            if isFirstParagraph {
                style.firstLineHeadIndent = max(style.firstLineHeadIndent, firstLineIndent)
                isFirstParagraph = false
            } else {
                style.firstLineHeadIndent = max(style.firstLineHeadIndent, continuationIndent)
            }
            attributed.addAttribute(.paragraphStyle, value: style, range: effectiveRange)

            cursor = NSMaxRange(paragraphRange)
        }
    }

    // MARK: Table (NSTextAttachment)

    private func renderTableAttachment(alignments: [RawTableColumnAlignment], rows: [RawTableRow]) -> RenderedBlock {
        let idx = tableIndex
        tableIndex += 1

        var hasher = Hasher()
        hasher.combine(alignments)
        hasher.combine(rows)
        let contentHash = hasher.finalize()

        // [ios_session_open_last_cell_occluded] Slot key. The slot cache (Step 2
        // below) keeps a TableAttachment's identity stable while its content GROWS
        // during streaming, so a still-arriving table updates in place instead of
        // rebuilding every token. It must therefore be keyed by something that:
        //   (a) stays the SAME as one table streams in, but
        //   (b) DIFFERS between two genuinely distinct tables in the same message.
        //
        // The old key was "messageId:tableIndex". `tableIndex` resets to 0 at the
        // top of every `render(blocks:)` call — and in the V3 cell-per-block list
        // each assistant block (each table) is rendered by its OWN render() call,
        // so every table got tableIndex == 0. Two different tables in one message
        // therefore collided on "messageId:0", shared one attachment via Step 2,
        // and `update()`-overwrote each other → the on-screen table showed another
        // table's content (correct on first paint, swapped after a reuse pass):
        // two distinct tables in one message both keyed "<msgId>:0", same slot,
        // same shared TableAttachment, content flipping between them.
        //
        // Fix: always include tableIndex in the slot key. tableIndex
        // increments within a single render() call, so two tables in the
        // same block (same render pass) always get distinct keys — even if
        // their content is byte-for-byte identical.
        //
        // When blockId is available (V3 cell-per-block), include it to
        // also disambiguate tables across blocks that happen to share the
        // same tableIndex (e.g. each block's render() resets idx to 0).
        //
        // tableIndex resets per render() call, which is the right scope:
        // a streaming re-render of the same block sees the same table at
        // the same idx, so the slot key stays stable for in-place update.
        let slotKey = "\(messageId?.uuidString ?? "nil"):\(blockId?.uuidString ?? "_"):\(idx)"

        let attachment: TableAttachment
        // Step 1: same-content cache (any slot, any message). Lets the
        // SAME identical table that already lives in another cell share
        // its precomputed `cachedLayout` — the historical-message hang
        // fix from the 27s ips capture.
        if let cached = tableAttachmentCache[contentHash] {
            attachment = cached
            // Refresh LRU position.
            if let pos = tableAttachmentLRU.firstIndex(of: contentHash) {
                tableAttachmentLRU.remove(at: pos)
            }
            tableAttachmentLRU.append(contentHash)
            // Also pin this attachment to the current (message,slot) so
            // subsequent streaming growth at this slot updates it in
            // place rather than creating a new attachment instance.
            tableSlotCache[slotKey] = cached
            tableAttachmentReverseKey[ObjectIdentifier(cached)] = contentHash
            Self.rendererLogger.info("[RND] table#\(idx) CACHE HIT rows=\(rows.count) cols=\(alignments.count) hash=\(contentHash)")
        } else if let slotAttachment = tableSlotCache[slotKey] {
            // Step 2: streaming growth — same (message,slot), new content
            // hash. Mutate in place to keep NSTextAttachment identity
            // stable so the updateUIView prefix-equality fast path stays
            // alive and updateAttachmentViews swaps only this attachment's
            // TableScrollView (not every attachment view in the cell).
            // Without this, every streamed token would rebuild every
            // attachment subview — flicker + O(N) work per flush.
            slotAttachment.update(alignments: alignments, rows: rows)
            attachment = slotAttachment
            // Re-key the by-content cache: drop the old hash entry (if
            // any), insert under the new hash, refresh LRU.
            if let oldHash = tableAttachmentReverseKey[ObjectIdentifier(slotAttachment)] {
                if let pos = tableAttachmentLRU.firstIndex(of: oldHash) {
                    tableAttachmentLRU.remove(at: pos)
                }
                tableAttachmentCache.removeValue(forKey: oldHash)
            }
            tableAttachmentCache[contentHash] = slotAttachment
            tableAttachmentLRU.append(contentHash)
            tableAttachmentReverseKey[ObjectIdentifier(slotAttachment)] = contentHash
            Self.rendererLogger.info("[RND] table#\(idx) CACHE UPDATE rows=\(rows.count) cols=\(alignments.count) newHash=\(contentHash)")
        } else {
            // Step 3: brand new — neither same content nor same slot
            // recognized. Create a fresh TableAttachment.
            attachment = TableAttachment(alignments: alignments, rows: rows, theme: theme)
            tableAttachmentCache[contentHash] = attachment
            tableAttachmentLRU.append(contentHash)
            tableSlotCache[slotKey] = attachment
            tableAttachmentReverseKey[ObjectIdentifier(attachment)] = contentHash
            // LRU evict — keep the by-content cache bounded.
            while tableAttachmentLRU.count > tableAttachmentCacheCap {
                let evict = tableAttachmentLRU.removeFirst()
                if let dropped = tableAttachmentCache.removeValue(forKey: evict) {
                    tableAttachmentReverseKey.removeValue(forKey: ObjectIdentifier(dropped))
                    // Drop matching slot entries that point at the same
                    // attachment we just evicted — keeps tableSlotCache
                    // consistent with tableAttachmentCache.
                    for (slot, att) in tableSlotCache where att === dropped {
                        tableSlotCache.removeValue(forKey: slot)
                    }
                }
            }
            Self.rendererLogger.info("[RND] table#\(idx) CACHE MISS rows=\(rows.count) cols=\(alignments.count) newHash=\(contentHash) cacheSize=\(tableAttachmentCache.count)")
        }

        // Wrap the table attachment with U+2028 (LINE SEPARATOR) on both
        // sides. This forces TextKit1 to place the attachment in its own
        // line fragment and prevents `_fillLayoutHole` from trying to merge
        // it with adjacent content. Without these separators, when a tall
        // table (observed at ≥ 9 rows ~ 400pt) arrives via streaming,
        // TextKit1's line-break-fitting routine enters an infinite loop
        // inside `_performTextKit1LayoutCalculation` and hangs the main
        // thread long enough to trip the 10s scene-update watchdog
        // (0x8BADF00D). The U+2028 wrap was verified to work around this
        // on iOS 26 TextKit1. Replace with TextKit2 long-term.
        let result = NSMutableAttributedString()
        let separatorPara = NSMutableParagraphStyle()
        separatorPara.paragraphSpacing = 0
        separatorPara.paragraphSpacingBefore = 0
        separatorPara.lineSpacing = 0
        // Tiny font collapses the U+2028 line fragment to ~0pt. The separator's
        // only job is to force TextKit1 to place the table attachment in its own
        // line fragment (workaround for the 9+-row _fillLayoutHole hang); it
        // should not contribute visible vertical space. Previously used
        // theme.baseFont (16.5pt), which ate ~20pt above and below every table.
        let sepAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 0.01),
            .paragraphStyle: separatorPara,
        ]
        result.append(NSAttributedString(string: "\u{2028}", attributes: sepAttrs))
        let attachPart = NSMutableAttributedString(attachment: attachment)
        attachPart.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: attachPart.length))
        result.append(attachPart)
        result.append(NSAttributedString(string: "\u{2028}", attributes: sepAttrs))
        return RenderedBlock(
            attributed: result,
            topMargin: theme.baseFontSize * 0.6,
            bottomMargin: theme.baseFontSize * 0.6,
            isBlockAttachment: true
        )
    }

    // MARK: Thematic Break (NSTextAttachment)

    private func renderThematicBreakAttachment() -> RenderedBlock {
        let attachment = ThematicBreakAttachment(theme: theme)
        let result = NSMutableAttributedString(attachment: attachment)
        result.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: result.length))
        return RenderedBlock(attributed: result, topMargin: 16, bottomMargin: 16, isBlockAttachment: true)
    }

    // MARK: HTML Block

    private func renderHTMLBlock(_ content: String, quoteDepth: Int) -> RenderedBlock {
        var attrs = baseAttributes(quoteDepth: quoteDepth)
        let style: NSMutableParagraphStyle
        if let existing = attrs[.paragraphStyle] as? NSParagraphStyle {
            style = existing.mutableCopy() as! NSMutableParagraphStyle
        } else {
            style = NSMutableParagraphStyle()
        }
        style.lineSpacing = bodyLineSpacing
        attrs[.paragraphStyle] = style
        return RenderedBlock(
            attributed: NSAttributedString(string: content, attributes: attrs),
            topMargin: 0,
            bottomMargin: 16
        )
    }

    // MARK: Media Attachments (inline .image nodes rendered as block-level attachments)

    private func renderImageAttachment(source: String) -> NSAttributedString {
        let attachment: ImageAttachment
        if let cached = imageAttachmentCache[source] {
            AppLogger(category: "AttachHotPath").info("[IMG][RENDER] REUSE src=\(ImageAttachment.shortSrc(source)) ptr=\(ObjectIdentifier(cached).hashValue & 0xFFFFFF) loaded=\(cached.loadedImage != nil)")
            imgLogger.info("[MinisImage][RenderAttach] REUSE cached attachment src=\(source) loaded=\(cached.loadedImage != nil) imgSize=\(cached.loadedImage.map { "\($0.size.width)x\($0.size.height)" } ?? "nil")")
            // Keep messageId fresh on reused attachments — the same renderer
            // instance may survive across different messages during cell reuse.
            cached.messageId = messageId
            // Fingerprint check: if the underlying file has been rewritten
            // since we last loaded (size or mtime changed), drop the
            // cached bitmap so the next draw re-reads from disk.
            let currentFp = minisMediaCacheKey(for: source)
            if let loadedFp = cached.loadedFingerprint, loadedFp != currentFp {
                AppLogger(category: "AttachHotPath").info("[IMG][RENDER] FINGERPRINT-DROP src=\(ImageAttachment.shortSrc(source)) old=\(loadedFp) new=\(currentFp) — bitmap invalidated, will reload")
                imgLogger.info("[MinisImage][RenderAttach] FINGERPRINT CHANGED src=\(source) old=\(loadedFp) new=\(currentFp) — invalidating cached image")
                cached.invalidateLoadedImage()
            }
            attachment = cached
        } else {
            AppLogger(category: "AttachHotPath").info("[IMG][RENDER] CREATE src=\(ImageAttachment.shortSrc(source)) cacheCount=\(self.imageAttachmentCache.count) — fresh ImageAttachment, ObjectIdentifier changed, view WILL be rebuilt")
            imgLogger.info("[MinisImage][RenderAttach] CREATE new ImageAttachment src=\(source) cacheCount=\(self.imageAttachmentCache.count)")
            attachment = ImageAttachment(source: source, theme: theme, messageId: messageId)
            imageAttachmentCache[source] = attachment
        }
        let result = NSMutableAttributedString(attachment: attachment)
        result.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: result.length))
        return result
    }

    private func renderVideoAttachment(source: String) -> NSAttributedString {
        let attachment: VideoAttachment
        if let cached = videoAttachmentCache[source] {
            attachment = cached
        } else {
            attachment = VideoAttachment(source: source, theme: theme)
            videoAttachmentCache[source] = attachment
        }
        let result = NSMutableAttributedString(attachment: attachment)
        result.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: result.length))
        return result
    }

    private func renderAudioAttachment(source: String) -> NSAttributedString {
        let attachment: AudioAttachment
        if let cached = audioAttachmentCache[source] {
            attachment = cached
        } else {
            attachment = AudioAttachment(source: source, theme: theme)
            audioAttachmentCache[source] = attachment
        }
        let result = NSMutableAttributedString(attachment: attachment)
        result.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: result.length))
        return result
    }

    // MARK: Inline Rendering

    /// Build the URL stored in a markdown link's `.link` attribute, undoing a
    /// double percent-encoding when present.
    ///
    /// [T-ios-file-preview-stale-cache] file_write returns a singly-encoded
    /// minis_url (e.g. `minis://workspace/GitHub%E7%83%AD….md`). When the model
    /// embeds it in `[text](url)` and it passes through the markdown render
    /// pipeline, the literal `%` can get re-encoded to `%25`, yielding a
    /// double-encoded destination (`…%25E7…`). `URL(string:).path` then only
    /// peels one layer, leaving `%E7…`, which matches no file on disk — so the
    /// tap falls through to the parent workspace folder (the reported `.bin`
    /// folder symptom) instead of previewing the file. Detect that case and
    /// decode one extra layer before building the URL, so the `.link` carries
    /// the correct singly-encoded URL the tap handler / resolver expects. The
    /// resolver-side `subPathCandidates` tolerance stays as a backstop.
    private func normalizedLinkURL(from destination: String) -> URL? {
        let fallback = URL(string: destination)
        // Only minis:// links are affected; everything else is untouched.
        guard destination.hasPrefix("minis://") else {
            return fallback
        }
        // [T-minis-url-fullwidth-pipe-ios] URL(string:) returned nil — on iOS
        // Foundation this happens when the destination carries raw non-ASCII in
        // the path (e.g. a filename with U+FF5C `｜`, CJK, emoji that the LLM
        // emitted unencoded inside a minis:// link). The link would otherwise be
        // dead (no .link attribute → not tappable). Percent-encode the path
        // portion segment-by-segment and retry. Idempotent: a segment that is
        // ALREADY validly encoded is left untouched, so an already-%EF%BD%9C URL
        // is never double-encoded (it also wouldn't reach here, since URL(string:)
        // accepts it — this is belt-and-suspenders).
        guard let base = fallback else {
            return encodeRawMinisURL(destination)
        }
        // A correctly singly-encoded minis_url decodes cleanly: `base.path`
        // contains no stray `%`. A double-encoded one (`%25E7…`) peels only one
        // layer here, leaving a literal `%E7…` in `.path`. That residual `%` is
        // the double-encoding signal — decode one more layer. A filename that
        // legitimately contains `%` is recovered to the same path either way
        // (the `%XX` of a real byte can't reappear), and the resolve-time
        // existence check (subPathCandidates) remains the final disambiguator.
        guard base.path.contains("%"),
              let once = destination.removingPercentEncoding,
              once.hasPrefix("minis://"),
              let fixed = URL(string: once) else {
            return base
        }
        return fixed
    }


    private func renderInlines(_ inlines: [InlineNode], baseAttributes attrs: [NSAttributedString.Key: Any]) -> NSAttributedString {
        let result = NSMutableAttributedString()
        for inline in inlines {
            result.append(renderInline(inline, attributes: attrs))
        }
        return result
    }

    /// [T-ios-inline-code-long-path-wrap] Insert zero-width spaces after the
    /// separators inside an inline code span so TextKit can wrap a long token.
    ///
    /// Problem: a path like `/tmp/android_backup_tg_feedback_triage_2026.md` is
    /// a single unbreakable "word" under `.byWordWrapping` except at `/`. On a
    /// narrow screen TextKit therefore breaks after `/tmp/` — leaving half the
    /// first line empty and a full-width background pill around four visible
    /// characters — and then, since the 45-char remainder still overflows, it
    /// falls back to breaking mid-token, stranding a character or two on a
    /// third line. That is exactly the reported rendering.
    ///
    /// Fix: give TextKit legal break opportunities at the separators a reader
    /// already perceives as segment boundaries (`/ _ - .`), so a long span
    /// fills each line and breaks where a human would. U+200B is zero-width, so
    /// the rendered span is pixel-identical when it does NOT need to wrap —
    /// verified equal to 3 decimal places against the unmodified string — and
    /// short spans keep laying out on one line exactly as before.
    ///
    /// Copy safety: `plainTextWithTables` (the single choke point for selection
    /// copy, Read Selection and Add to Chat Input) already strips U+200B and
    /// U+200A, because the CJK emphasis pre-pass [T-md-cjk-emphasis-pairs]
    /// injects the same character. Tap-to-copy is unaffected either way — it
    /// reads the pristine string from `.inlineCodeText`, which is deliberately
    /// still set from the ORIGINAL `code`, not this padded form.
    ///
    /// Scope: body inline code only. Table-cell inline code (`renderCellInline`)
    /// is left alone — cell width is driven by measured content rather than a
    /// fixed container, so it has neither the symptom nor the same wrap model.
    static func breakableInlineCode(_ code: String) -> String {
        // Nothing to gain on spans that cannot overflow a line; skip the work
        // and keep the string byte-identical for the overwhelmingly common case.
        guard code.count > 24 else { return code }
        var out = ""
        out.reserveCapacity(code.count * 2)
        for ch in code {
            out.append(ch)
            // Only after a separator, never before: breaking before `/` would
            // orphan the separator onto the next line, which reads worse.
            if ch == "/" || ch == "_" || ch == "-" || ch == "." {
                out.append("\u{200B}")
            }
        }
        return out
    }

    private func renderInline(_ node: InlineNode, attributes attrs: [NSAttributedString.Key: Any]) -> NSAttributedString {
        switch node {
        case .text(let text):
            return NSAttributedString(string: text, attributes: attrs)

        case .code(let code):
            var codeAttrs = attrs
            codeAttrs[.font] = theme.inlineCodeFont
            codeAttrs[.foregroundColor] = theme.inlineCodeColor
            codeAttrs[.inlineCodeBackground] = true
            codeAttrs[.inlineCodeText] = code
            // Set .backgroundColor to trigger fillBackgroundRectArray in MinisLayoutManager
            // The actual color is drawn there with rounded corners; this just triggers the callback.
            codeAttrs[.backgroundColor] = theme.inlineCodeBackground
            // Add hair spaces for visual padding inside the background highlight.
            return NSAttributedString(string: "\u{200A}\(Self.breakableInlineCode(code))\u{200A}",
                                      attributes: codeAttrs)

        case .emphasis(let children):
            var emphAttrs = attrs
            if let font = attrs[.font] as? UIFont {
                emphAttrs[.font] = font.withTraits(.traitItalic)
            }
            return renderInlines(children, baseAttributes: emphAttrs)

        case .strong(let children):
            var strongAttrs = attrs
            if let font = attrs[.font] as? UIFont {
                let descriptor = font.fontDescriptor.addingAttributes([
                    .traits: [UIFontDescriptor.TraitKey.weight: UIFont.Weight.bold]
                ])
                strongAttrs[.font] = UIFont(descriptor: descriptor, size: font.pointSize)
            }
            return renderInlines(children, baseAttributes: strongAttrs)

        case .strikethrough(let children):
            var strikeAttrs = attrs
            strikeAttrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
            return renderInlines(children, baseAttributes: strikeAttrs)

        case .link(let destination, let children):
            var linkAttrs = attrs
            if let url = normalizedLinkURL(from: destination) {
                linkAttrs[.link] = url
            }
            linkAttrs[.foregroundColor] = theme.linkColor
            linkAttrs[.underlineStyle] = NSUnderlineStyle.single.rawValue
            return renderInlines(children, baseAttributes: linkAttrs)

        case .image(let source, let children):
            let ext = URL(string: source)?.pathExtension.lowercased() ?? ""
            let altText = children.plainText
            imgLogger.info("[MinisImage][InlineParse] .image node src=\(source) ext=\(ext) alt=\(altText)")
            if nativeAudioExts.contains(ext) {
                return renderAudioAttachment(source: source)
            } else if nativeVideoExts.contains(ext) {
                return renderVideoAttachment(source: source)
            } else if nativeImageExts.contains(ext) || ext.isEmpty {
                imgLogger.info("[MinisImage][InlineParse] routing to IMAGE attachment src=\(source)")
                return renderImageAttachment(source: source)
            } else {
                // Unknown extension — render as image attachment (best guess)
                imgLogger.info("[MinisImage][InlineParse] unknown ext=\(ext), routing to IMAGE attachment (best guess) src=\(source)")
                return renderImageAttachment(source: source)
            }

        case .html(let html):
            return NSAttributedString(string: html, attributes: attrs)

        case .softBreak:
            return NSAttributedString(string: " ", attributes: attrs)

        case .lineBreak:
            return NSAttributedString(string: "\n", attributes: attrs)

        case .inlineMath(let latex):
            return renderInlineMathAttachment(latex: latex)
        }
    }

    // MARK: Helpers

    /// Per-depth indent: 3pt bar + 10pt leading padding (matches minisChat blockquote padding)
    private static let quoteIndentPerLevel: CGFloat = 13
    private static let listIndentPerLevel: CGFloat = 24

    private func baseAttributes(quoteDepth: Int) -> [NSAttributedString.Key: Any] {
        var attrs: [NSAttributedString.Key: Any] = [
            .font: theme.baseFont,
            .foregroundColor: quoteDepth > 0 ? theme.secondaryLabelColor : theme.labelColor,
        ]
        if quoteDepth > 0 {
            attrs[.blockquoteDepth] = quoteDepth
            let style = NSMutableParagraphStyle()
            let indent = CGFloat(quoteDepth) * Self.quoteIndentPerLevel
            style.headIndent = indent
            style.firstLineHeadIndent = indent
            style.lineSpacing = bodyLineSpacing
            attrs[.paragraphStyle] = style
        }
        return attrs
    }

    // MARK: Math Attachments

    private func renderMathBlockAttachment(latex: String) -> RenderedBlock {
        let key = "D:\(Int(theme.baseFontSize)):" + latex
        let attachment: MathAttachment
        if let cached = mathAttachmentCache[key] {
            attachment = cached
        } else {
            attachment = MathAttachment(latex: latex, isBlock: true, theme: theme)
            mathAttachmentCache[key] = attachment
        }
        let result = NSMutableAttributedString(attachment: attachment)
        result.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: result.length))
        return RenderedBlock(attributed: result, topMargin: bodyLineSpacing, bottomMargin: bodyLineSpacing, isBlockAttachment: true)
    }

    private func renderInlineMathAttachment(latex: String) -> NSAttributedString {
        let key = "I:\(Int(theme.baseFontSize)):" + latex
        let attachment: MathAttachment
        if let cached = mathAttachmentCache[key] {
            attachment = cached
        } else {
            attachment = MathAttachment(latex: latex, isBlock: false, theme: theme)
            mathAttachmentCache[key] = attachment
        }
        let result = NSMutableAttributedString(attachment: attachment)
        result.addAttribute(.font, value: theme.baseFont, range: NSRange(location: 0, length: result.length))
        return result
    }
}

// MARK: - UIFont+Traits

private extension UIView {
    /// Walk the responder chain to find the nearest UIViewController.
    func nearestViewController() -> UIViewController? {
        var responder: UIResponder? = self
        while let r = responder {
            if let vc = r as? UIViewController { return vc }
            responder = r.next
        }
        return nil
    }

    /// Walk the superview chain to find the nearest UIScrollView.
    func enclosingScrollView() -> UIScrollView? {
        var current = self.superview
        while let v = current {
            if let sv = v as? UIScrollView { return sv }
            current = v.superview
        }
        return nil
    }
}

private extension UIFont {
    func withTraits(_ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        let combined = fontDescriptor.symbolicTraits.union(traits)
        guard let descriptor = fontDescriptor.withSymbolicTraits(combined) else { return self }
        return UIFont(descriptor: descriptor, size: 0)
    }
}

// MARK: - Code Block Attachment

final class CodeBlockAttachment: NSTextAttachment {
    var code: String
    let language: String?
    let theme: SelectableMarkdownTheme
    let quoteDepth: Int
    /// Sequential index for view reuse across re-renders (set by MarkdownNSRenderer).
    var blockIndex: Int = 0
    /// [CodeGenDedup] Hash of (code, language), computed once by the renderer
    /// at creation. Folded into the invalidateCellSizeIfNeeded dedupe
    /// fingerprint so in-place code growth during streaming — which changes
    /// neither textStorage.length nor width — still defeats [SKIP-DEDUPE]
    /// and re-invalidates the host cell's height (the TableGenDedup
    /// counterpart for code blocks).
    let contentFingerprint: Int

    /// Left inset for blockquote nesting (matches text indent).
    var leftInset: CGFloat { CGFloat(quoteDepth) * 13 }

    init(code: String, language: String?, theme: SelectableMarkdownTheme, quoteDepth: Int = 0, contentFingerprint: Int = 0) {
        self.code = code
        self.language = language
        self.theme = theme
        self.quoteDepth = quoteDepth
        self.contentFingerprint = contentFingerprint
        super.init(data: nil, ofType: nil)
        // Provide a transparent image so UIKit doesn't draw a default placeholder
        self.image = Self.transparentImage
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private static let transparentImage: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
    }()

    static let topMargin: CGFloat = 6
    static let bottomMargin: CGFloat = 4

    /// Associated-object key retaining the copy-tap gesture target
    /// for the button's lifetime (see makeView).
    static var copyTapHandlerKey: UInt8 = 0

    /// Target object for the fallback tap gesture on the copy button —
    /// UITapGestureRecognizer needs an @objc target/action pair.
    final class CodeCopyTapHandler: NSObject {
        private let perform: () -> Void
        init(perform: @escaping () -> Void) { self.perform = perform }
        @objc func handleTap() { perform() }
    }

    /// [T-ios17-codeblock-copy-dead] Reference box for the double-fire guard:
    /// with both the control event AND the fallback gesture bound (see
    /// makeView), a version where the control path still works would invoke
    /// the copy twice per tap without it.
    final class CopyDebounce { var last = Date.distantPast }

    /// Cached content height from sizeThatFits, keyed by code hash + width.
    private var cachedContentHeight: (hash: Int, height: CGFloat)?

    /// Measure actual code text height using the same approach as makeView.
    private func measureCodeHeight() -> CGFloat {
        let codeHash = code.hashValue
        if let cached = cachedContentHeight, cached.hash == codeHash {
            return cached.height
        }
        let codeStyle = NSMutableParagraphStyle()
        codeStyle.lineSpacing = 4
        codeStyle.lineBreakMode = .byClipping
        let attrStr = NSAttributedString(string: code, attributes: [
            .font: theme.codeBlockFont,
            .paragraphStyle: codeStyle,
        ])
        let tv = UITextView()
        tv.isScrollEnabled = false
        tv.textContainerInset = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: 8)
        tv.textContainer.lineFragmentPadding = 0
        tv.textContainer.lineBreakMode = .byClipping
        tv.attributedText = attrStr
        let h = tv.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)).height
        cachedContentHeight = (hash: codeHash, height: h)
        return h
    }

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        let width = lineFrag.width
        let topOffset: CGFloat = (language != nil && !language!.isEmpty) ? 28 : 12
        let contentHeight = measureCodeHeight()
        let bottomPadding: CGFloat = 12
        let maxCodeHeight: CGFloat = 400 - topOffset - bottomPadding
        let scrollHeight = min(contentHeight, maxCodeHeight)
        let totalHeight = topOffset + scrollHeight + bottomPadding
        let height = totalHeight + Self.topMargin + Self.bottomMargin
        return CGRect(x: 0, y: 0, width: width, height: height)
    }

    func makeView(width: CGFloat) -> UIView {
        let wrapper = UIView()
        wrapper.backgroundColor = .clear

        let inset = leftInset
        let contentWidth = width - inset

        let container = UIView()
        container.backgroundColor = theme.codeBlockBackground
        container.layer.cornerRadius = theme.codeBlockCornerRadius
        container.clipsToBounds = true

        var topOffset: CGFloat = 12

        // Language label
        if let language, !language.isEmpty {
            let langLabel = UILabel()
            langLabel.text = language.lowercased()
            langLabel.font = .systemFont(ofSize: 11, weight: .medium)
            langLabel.textColor = .white.withAlphaComponent(0.4)
            langLabel.frame = CGRect(x: 12, y: 8, width: contentWidth - 60, height: 16)
            container.addSubview(langLabel)
            topOffset = 28
        }

        // Scrollable code area (own the pan gesture here for reliable horizontal scroll)
        let scrollView = UIScrollView()
        scrollView.showsHorizontalScrollIndicator = true
        scrollView.showsVerticalScrollIndicator = true
        scrollView.alwaysBounceHorizontal = true
        scrollView.alwaysBounceVertical = true
        scrollView.clipsToBounds = true

        let codeTextView = UITextView()
        codeTextView.isEditable = false
        codeTextView.isSelectable = true
        codeTextView.isScrollEnabled = false // scrollView handles scrolling
        codeTextView.backgroundColor = .clear
        codeTextView.textContainerInset = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: 8)
        codeTextView.textContainer.lineFragmentPadding = 0
        codeTextView.textContainer.lineBreakMode = .byClipping

        let codeStyle = NSMutableParagraphStyle()
        codeStyle.lineSpacing = 4
        codeStyle.lineBreakMode = .byClipping
        let codeAttr = NSAttributedString(string: code, attributes: [
            .font: theme.codeBlockFont,
            .foregroundColor: theme.codeBlockTextColor,
            .paragraphStyle: codeStyle,
        ])
        codeTextView.attributedText = codeAttr

        // Measure content size (unconstrained width)
        let fitting = codeTextView.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude))
        let contentHeight = fitting.height
        let fittingWidth = fitting.width
        codeTextView.frame = CGRect(x: 0, y: 0, width: fittingWidth, height: contentHeight)

        let maxCodeHeight: CGFloat = 400 - topOffset - 12
        let scrollHeight = min(contentHeight, maxCodeHeight)
        let bottomPadding: CGFloat = 12
        scrollView.frame = CGRect(x: 0, y: topOffset, width: contentWidth, height: scrollHeight + bottomPadding)
        scrollView.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: bottomPadding, right: 0)
        scrollView.scrollIndicatorInsets = .zero
        scrollView.addSubview(codeTextView)
        scrollView.contentSize = CGSize(width: fittingWidth, height: contentHeight)

        // Auto-scroll to bottom during streaming
        let bottomY = scrollView.contentSize.height - scrollView.bounds.height
        if bottomY > 0 {
            scrollView.contentOffset = CGPoint(x: 0, y: bottomY)
        }

        container.addSubview(scrollView)

        let totalHeight = topOffset + scrollHeight + bottomPadding
        container.frame = CGRect(x: inset, y: 0, width: contentWidth, height: totalHeight)

        // Copy button — 44x44 hit area per Apple HIG, icon stays small visually
        let iconConfig = UIImage.SymbolConfiguration(pointSize: 9, weight: .medium)
        let copyButton = UIButton(type: .system)
        copyButton.setImage(UIImage(systemName: "doc.on.doc", withConfiguration: iconConfig), for: .normal)
        copyButton.tintColor = .white.withAlphaComponent(0.5)
        copyButton.frame = CGRect(x: contentWidth - 44, y: 0, width: 44, height: 44)
        let debounce = CopyDebounce()
        let performCopy: () -> Void = { [weak codeTextView, weak copyButton] in
            // [T-ios17-codeblock-copy-dead] Double-fire guard: on versions
            // where the control event still works, a single tap now arrives
            // through BOTH the control path and the fallback gesture below.
            // The copy itself is idempotent, but guard anyway so the action
            // runs exactly once per physical tap.
            guard Date().timeIntervalSince(debounce.last) > 0.3 else { return }
            debounce.last = Date()
            // Read directly from the UITextView so we always copy the latest
            // content, even after updateExistingView replaced the attributed text.
            UIPasteboard.general.string = codeTextView?.text
            copyButton?.setImage(UIImage(systemName: "checkmark", withConfiguration: iconConfig), for: .normal)
            copyButton?.tintColor = .systemGreen
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                copyButton?.setImage(UIImage(systemName: "doc.on.doc", withConfiguration: iconConfig), for: .normal)
                copyButton?.tintColor = .white.withAlphaComponent(0.5)
            }
        }
        copyButton.addAction(UIAction { _ in performCopy() }, for: .touchUpInside)
        // [T-ios16-codeblock-copy-regression] [T-ios17-codeblock-copy-dead]
        // ALWAYS also bind an independent tap gesture on the button, on every
        // OS version. History of this line:
        //   • iOS 16: .touchUpInside stopped firing (regressed between TF26
        //     and TF28) — the host text view's gesture/interaction graph
        //     cancels the control's touches. Fallback added, gated <17 on the
        //     assumption "iOS 17+ button works".
        //   • iOS 17: field report proved that assumption wrong — same dead
        //     button. The likely toucher is visible one screen up:
        //     addInteraction(_:) DROPS UIContextMenuInteraction on iOS 16 but
        //     KEEPS it on 17+, so 17 is exactly the version where the
        //     interaction's gesture graph exists AND no fallback was bound
        //     (18+ reworked the text-interaction stack and doesn't cancel).
        // A UITapGestureRecognizer attached to the button recognizes through
        // the gesture system rather than the control-event path, so it
        // survives the cancellation on every affected version. The version
        // gate is gone for good: it encoded a per-version guess that has now
        // been wrong twice, and the debounce in performCopy makes the
        // double-bind harmless where the control path does work.
        let tapHandler = CodeCopyTapHandler(perform: performCopy)
        let tap = UITapGestureRecognizer(target: tapHandler, action: #selector(CodeCopyTapHandler.handleTap))
        tap.cancelsTouchesInView = false
        copyButton.addGestureRecognizer(tap)
        objc_setAssociatedObject(copyButton, &Self.copyTapHandlerKey, tapHandler, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        container.addSubview(copyButton)

        container.frame.origin.y = Self.topMargin
        wrapper.addSubview(container)
        wrapper.frame = CGRect(x: 0, y: 0, width: width, height: totalHeight + Self.topMargin + Self.bottomMargin)

        return wrapper
    }

    /// Update the code text in an existing view created by `makeView(width:)` without recreating.
    func updateExistingView(_ wrapper: UIView) {
        guard let container = wrapper.subviews.first else { return }
        guard let scrollView = container.subviews.first(where: { $0 is UIScrollView }) as? UIScrollView else { return }
        guard let codeTextView = scrollView.subviews.first(where: { $0 is UITextView }) as? UITextView else { return }

        let codeStyle = NSMutableParagraphStyle()
        codeStyle.lineSpacing = 4
        codeStyle.lineBreakMode = .byClipping
        let codeAttr = NSAttributedString(string: code, attributes: [
            .font: theme.codeBlockFont,
            .foregroundColor: theme.codeBlockTextColor,
            .paragraphStyle: codeStyle,
        ])
        codeTextView.attributedText = codeAttr

        let fitting = codeTextView.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude))
        codeTextView.frame.size = fitting
        scrollView.contentSize = fitting

        // Recalculate heights to match attachmentBounds logic
        let topOffset: CGFloat = (language != nil && !language!.isEmpty) ? 28 : 12
        let maxCodeHeight: CGFloat = 400 - topOffset - 12
        let scrollHeight = min(fitting.height, maxCodeHeight)
        let bottomPadding: CGFloat = 12
        let newScrollFrame = CGRect(x: 0, y: topOffset, width: container.frame.width, height: scrollHeight + bottomPadding)
        let totalHeight = topOffset + scrollHeight + bottomPadding

        // [CodeBlockGrow] Animate the visible code-frame growing taller as more
        // lines stream in, instead of snapping. The wrapper's LOGICAL height is
        // set immediately (synchronously) because the caller reads
        // `view.frame.size.height` right after this returns to lay out the
        // TextKit attachment slot + the enclosing cell — animating that would
        // desync the code frame from the text flow. Only the wrapper's visible
        // children (the rounded background container + its scroll view) animate
        // their height, so the box smoothly extends while layout stays exact.
        let heightGrew = totalHeight > container.frame.size.height + 0.5
        wrapper.frame.size.height = totalHeight + Self.topMargin + Self.bottomMargin
        let applyChildFrames = {
            scrollView.frame = newScrollFrame
            container.frame.size.height = totalHeight
        }
        if heightGrew {
            UIView.animate(withDuration: 0.20,
                           delay: 0,
                           options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState],
                           animations: applyChildFrames)
        } else {
            applyChildFrames()
        }

        // Auto-scroll to bottom during streaming
        let bottomY = scrollView.contentSize.height - scrollView.bounds.height
        if bottomY > 0 {
            scrollView.contentOffset = CGPoint(x: scrollView.contentOffset.x, y: bottomY)
        }
    }
}

// MARK: - Table Attachment

final class TableAttachment: NSTextAttachment {
    private(set) var alignments: [RawTableColumnAlignment]
    private(set) var rows: [RawTableRow]
    let theme: SelectableMarkdownTheme
    /// Handler for opening URLs from links in table cells.
    var onOpenURL: ((URL) -> Void)?

    /// Shared NSDataDetector used to find bare URLs that the markdown
    /// parser left as `.text` (cmark-gfm autolink misses on CJK / full-
    /// width adjacency). Compiled once — initializer can throw, hence
    /// the `try?`. Reused across every table cell in the app.
    static let linkDataDetector: NSDataDetector? = {
        try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    }()
    /// Set when `update(...)` mutates content in place. Tells the parent
    /// updateAttachmentViews path that the existing rendered TableScrollView
    /// needs to be rebuilt to reflect the new rows/alignments. Mirrors
    /// `ImageAttachment.needsViewRebuild`. Reset by the consumer after the
    /// view is recreated.
    var needsViewRebuild: Bool = false
    /// Set when the content/layout changed in place; updateUIView reads this
    /// to invalidate just the attachment's character range in the parent
    /// UITextView's layoutManager (instead of the whole document).
    var needsLayoutInvalidation: Bool = false

    /// Horizontal scroll offset the user last reached on this table,
    /// re-applied inside every freshly-built view so the left/right scroll
    /// position survives streaming redraws. In-memory only (lives on this
    /// attachment, which keeps its object identity across streaming
    /// `update(...)` calls); never persisted.
    ///
    /// [T-ios-table-hscroll-offset-lost] This is PERSISTENT, not one-shot:
    /// the previous consume-once `pendingScrollOffset` + async restore lost
    /// the position whenever the next streaming rebuild fired before the
    /// async restore landed — the capture then read a transient offset of 0,
    /// declined to re-capture, and the restore targeted the already-removed
    /// old view. Now the live TableScrollView pushes every user-driven
    /// offset change here (see `onUserScroll` wiring in `makeView`), and
    /// `makeView` re-applies it synchronously without clearing it.
    var preservedScrollOffset: CGPoint?

    /// Walk a `makeView`-produced wrapper to its inner TableScrollView so the
    /// rebuild path can read/restore `contentOffset`. The wrapper holds exactly
    /// one TableScrollView subview (see `makeView`).
    static func tableScrollView(in wrapper: UIView) -> TableScrollView? {
        if let direct = wrapper as? TableScrollView { return direct }
        return wrapper.subviews.compactMap { $0 as? TableScrollView }.first
    }

    /// Monotonic counter bumped on every `update(...)`. The parent textView's
    /// `invalidateCellSizeIfNeeded` includes the sum of all TableAttachments'
    /// generations in its dedup fingerprint — without this, a streaming
    /// table whose rows/cols change but whose surrounding text (and thus
    /// `textStorage.length`) doesn't would be skipped by the
    /// "storageLen+width unchanged → no remeasure" early-exit, leaving the
    /// layout manager with the original (smaller) attachment bounds and
    /// producing the "table renders single-column / extra rows missing"
    /// symptom we hit during the watchdog repro.
    private(set) var contentGeneration: UInt64 = 0

    init(alignments: [RawTableColumnAlignment], rows: [RawTableRow], theme: SelectableMarkdownTheme) {
        self.alignments = alignments
        self.rows = rows
        self.theme = theme
        super.init(data: nil, ofType: nil)
        self.image = Self.transparentImage
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    /// Replace the table's content while keeping the same NSTextAttachment
    /// object identity. Crucial during streaming markdown: when only the table
    /// grows (e.g. one more cell character per token), preserving identity
    /// keeps the SelectableMarkdownView fast-path's prefix `isEqual` check
    /// happy, so we no longer fall back to `ensureLayout` over the entire
    /// message every chunk.
    func update(alignments: [RawTableColumnAlignment], rows: [RawTableRow]) {
        // [T-ios-table-hscroll-stream-interrupt] No-op when the content is
        // identical. The caller's hash-based routing normally sends equal
        // content to the cache-hit path instead of here, but if it ever
        // lands here, setting needsViewRebuild would tear down (and cancel
        // any in-flight horizontal scroll on) an already up-to-date
        // TableScrollView for nothing.
        if alignments == self.alignments && rows == self.rows { return }
        // HangFix(2026-05-14) — During streaming, this method fires on every
        // token chunk while a partial table is on screen. Wiping cachedLayout
        // unconditionally means each subsequent attachmentBounds callback
        // (TextKit1 calls it many times per fillLayoutHole pass) re-runs a
        // full per-cell NSAttributedString.boundingRect measurement. Even
        // a 4-row × 2-col table at small attrLen amplifies into seconds of
        // main-thread time when the typesetter loops on width probing.
        //
        // The previous-pass layout is still visually accurate if no new row
        // arrived (only cell content grew), AND it's still close-enough to
        // accurate even when one row was appended (the typesetter will fix
        // up the final size on the next real layout). We retain it in both
        // cases — the cell will re-measure naturally when `computeLayout`
        // gets called on a different width or when an actual size change
        // forces it. The transient stale height for one frame is invisible
        // versus a 1-2s hang on every streaming chunk.
        let oldRowCount = self.rows.count
        let oldColCount = self.alignments.count
        self.alignments = alignments
        self.rows = rows
        let structureChanged = rows.count != oldRowCount || alignments.count != oldColCount

        // [T-ios-table-streaming-width] Check whether the cached column widths
        // are stale. The HangFix (2026-05-14) only invalidated on row/column
        // COUNT changes, but during streaming a row's content grows from
        // partial to complete (same row count). The cached column widths from
        // the partial content are too narrow → columns wrap / emoji get
        // clipped. We track the max character count per column at cache time;
        // when any column's widest cell grows longer, the cache is stale.
        // This is O(rows × cols) integer comparisons — no hashing, no layout,
        // no measurement — so it stays cheap on every streaming token.
        let colCount = alignments.count
        var currentMax = [Int](repeating: 0, count: colCount)
        for row in rows {
            for (ci, cell) in row.cells.enumerated() where ci < colCount {
                currentMax[ci] = max(currentMax[ci], cell.content.plainText.count)
            }
        }
        let contentGrew: Bool
        if cachedLayout == nil {
            contentGrew = false
        } else if currentMax.count != cachedMaxCharPerCol.count {
            contentGrew = true
        } else {
            contentGrew = zip(currentMax, cachedMaxCharPerCol).contains { $0 > $1 }
        }
        if structureChanged || contentGrew {
            self.cachedLayout = nil
        }
        // Always mark for view rebuild so SelectableTableView's row VStack
        // can re-create row subviews with new cell content. Bounds reuse
        // is independent of view rebuild.
        self.needsViewRebuild = true
        // Layout invalidation needed when structure changes (rowHeights array
        // shape) or content grew wider (column widths stale) — even when
        // cachedLayout was already nil, the textView layout manager needs to
        // know the attachment bounds changed.
        self.needsLayoutInvalidation = structureChanged || contentGrew
        self.contentGeneration &+= 1
    }

    /// Tab-separated plain text representation of the table (rows joined by `\n`).
    func plainText() -> String {
        let colCount = alignments.count
        return rows.map { row in
            row.cells.prefix(colCount).map { $0.content.plainText }.joined(separator: "\t")
        }.joined(separator: "\n")
        // [T-md-cjk-emphasis-pairs] Strip flanking-fix spacers (see
        // plainTextWithTables) so table copies stay clean too.
        .replacingOccurrences(of: "\u{200B}", with: "")
        .replacingOccurrences(of: "\u{200A}", with: "")
    }

    private static let transparentImage: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
    }()

    /// Spacing controlled by RenderedBlock margins; no extra padding inside attachment
    static let verticalMargin: CGFloat = 0
    static let minRowHeight: CGFloat = 36
    static let cellPaddingH: CGFloat = 16
    static let cellPaddingV: CGFloat = 8

    /// Narrowest real (non-probe) attachment width observed process-wide, used as
    /// the height-measurement fallback for a first-appearance probe that races
    /// ahead of any real-width pass. Seeded below any real chat-bubble width so a
    /// probe never returns a height that clips the last table row.
    /// [ios_session_open_last_cell_occluded]
    nonisolated(unsafe) static var narrowestRealWidth: CGFloat = 380

    /// Cached layout computed for a given width, reused by both attachmentBounds and makeView.
    private var cachedLayout: TableLayout?
    /// Per-column maximum cell character count at the time `cachedLayout` was
    /// computed. When any column's widest cell grows longer during streaming,
    /// the cached column widths may be too narrow → must recompute. Using
    /// character counts (not a full content hash) keeps the check O(cells)
    /// per token — cheaper than Hasher over all plainText.
    private var cachedMaxCharPerCol: [Int] = []

    // MARK: - [T-ios-attachbounds-cold-cache] Process-wide layout cache
    //
    // `cachedLayout` above is PER-INSTANCE. On scroll deceleration the
    // collection view recycles cells; the per-cell renderer wipes/rebuilds
    // attachments (isNewMessage CACHE-WIPE), so a TableAttachment landing into
    // view at decel-stop often starts with `cachedLayout == nil` and every
    // `attachmentBounds` typesetter probe is a cold miss → a burst of per-cell
    // `boundingRect` measurement on the landing frame (the 100–190ms decel
    // jank, confirmed via [ScrollDecel]+[AttachBoundsHot] device logs).
    //
    // `computeLayout` is a PURE function of (rows, alignments, theme font,
    // bucketed width). Cache its result process-wide keyed by that signature
    // so an identical table — same content, same width — that was measured by
    // ANY earlier cell is reused instantly instead of cold-measured again.
    // LRU-bounded; pure layout values (no view refs) so it's cheap to retain.
    private struct LayoutCacheKey: Hashable {
        let contentSig: Int
        let widthBucket: Int   // width snapped to 4pt to fold typesetter probe jitter
    }
    nonisolated(unsafe) private static var sharedLayoutCache: [LayoutCacheKey: TableLayout] = [:]
    nonisolated(unsafe) private static var sharedLayoutLRU: [LayoutCacheKey] = []
    private static let sharedLayoutCacheCap = 256
    nonisolated(unsafe) private static var sharedLayoutHits: Int = 0
    nonisolated(unsafe) private static var sharedLayoutMisses: Int = 0
    nonisolated(unsafe) private static var sharedLayoutLastFlush: CFAbsoluteTime = 0

    /// Stable content signature for the shared layout cache. Folds the full
    /// cell text + alignments + header-font size (layout depends on all three).
    private var layoutContentSignature: Int {
        var h = Hasher()
        h.combine(alignments)
        h.combine(theme.baseFontSize)
        for row in rows {
            for cell in row.cells { h.combine(cell.content.plainText) }
            h.combine(0x1E)  // row separator so cell regrouping changes the hash
        }
        return h.finalize()
    }

    /// Discard the cached layout so the next `computeLayout` / `attachmentBounds`
    /// recomputes column widths and row heights for whatever width is current.
    /// Called from `layoutSubviews` when the textContainer width changes
    /// (rotation, size-class change) — without it the table reuses the original
    /// portrait-width columns and the visible table frame stays at the old
    /// width on the new orientation.
    func invalidateCachedLayoutForWidthChange() {
        cachedLayout = nil
        needsViewRebuild = true
        needsLayoutInvalidation = true
        contentGeneration &+= 1
    }

    struct TableLayout {
        let width: CGFloat
        let columnWidths: [CGFloat]
        let rowHeights: [CGFloat]
        let tableWidth: CGFloat
        var totalHeight: CGFloat { rowHeights.reduce(0, +) + 2 }
    }

    func computeLayout(for width: CGFloat, persist: Bool = true) -> TableLayout {
        if let cached = cachedLayout, cached.width == width {
            return cached
        }

        // HangFix(2026-05-13) — if we already have a cached layout at a
        // *different* width, return the cached totalHeight at the new
        // width. The typesetter explicitly asks for attachment bounds at
        // many slightly different proposed widths during fillLayoutHole;
        // every miss currently runs a full per-cell `NSAttributedString
        // boundingRect` measurement, and a 6-table cell can clock several
        // hundred ms per pass. When fillLayoutHole loops on width probing
        // those passes stack up into the 1+second hang the user sees
        // whenever a table is on screen. Returning the cached height at
        // any width-near-enough satisfies the typesetter cheaply —
        // visually identical because table content didn't change.
        //
        // The only path that *needs* a fresh measurement is when the
        // attachment's data (`rows`/`alignments`) has been mutated, which
        // we handle by setting `cachedLayout = nil` inside `update(...)`.
        if let cached = cachedLayout, !cached.columnWidths.isEmpty {
            // [RotationTableFix] Only reuse the cached column widths when
            // the requested width is "close enough" to the canonical cached
            // width — within 25%. This preserves the HangFix's typesetter-
            // probe optimisation (those probes are within a few points of
            // the real width) while ensuring a real rotation (portrait 397
            // → landscape 899 ≈ 2.2×) recomputes columns. Without this
            // bound, a table cached at portrait width keeps the narrow
            // columns when the bubble expands to landscape, so the table
            // visually stays at the old width even though the cell bubble
            // is now twice as wide.
            let canonical = cached.width
            let relDelta = abs(canonical - width) / max(canonical, 1)
            if relDelta < 0.25 {
                // Reuse: build a new TableLayout that pretends to be sized for
                // the requested width but shares row heights and column ratios
                // with the cached one. Don't persist — keep the canonical
                // cached version intact.
                return TableLayout(
                    width: width,
                    columnWidths: cached.columnWidths,
                    rowHeights: cached.rowHeights,
                    tableWidth: cached.tableWidth
                )
            }
            // Fall through to a fresh column/row computation below — the
            // cached columns are too narrow/wide for the new requested
            // width (a rotation has happened).
        }

        // [T-ios-attachbounds-cold-cache] Per-instance cachedLayout missed
        // (this is a freshly-built / recycled attachment). Before paying for a
        // full per-cell measurement, try the process-wide cache: an identical
        // table at this width measured by any earlier cell can be reused as-is.
        // computeLayout is a pure function of content+width, so this is safe.
        let sharedKey = Self.LayoutCacheKey(contentSig: layoutContentSignature, widthBucket: Int((width / 4).rounded()))
        if let shared = Self.sharedLayoutCache[sharedKey] {
            Self.sharedLayoutHits &+= 1
            Self.flushSharedLayoutStatsIfNeeded()
            // Rebuild a layout at the exact requested width sharing the cached
            // column widths / row heights (width bucket folds ≤4pt jitter).
            let reused = TableLayout(width: width, columnWidths: shared.columnWidths, rowHeights: shared.rowHeights, tableWidth: shared.tableWidth)
            if persist {
                cachedLayout = reused
                Self.touchSharedLayoutLRU(sharedKey)
            }
            return reused
        }
        Self.sharedLayoutMisses &+= 1

        let colCount = alignments.count
        var columnWidths = [CGFloat](repeating: 80, count: colCount)

        // [TableColumnCap] Per-column soft maximum at 500% of available width.
        // Only wrap when a single column requests more than 5× the container
        // — typical tables stay on one line per row even when much wider than
        // the viewport (horizontal scroll handles overflow). Without any cap
        // a stray prose line misclassified as a continuation row could blow
        // the column up to tens of thousands of points. Tighter caps (100%,
        // 200%) caused well-formed tables to wrap and produce uneven row
        // heights — user feedback 2026-05-13.

        // Calculate column widths based on single-line content measurement
        for (rowIdx, row) in rows.enumerated() {
            for (colIdx, cell) in row.cells.enumerated() where colIdx < colCount {
                let text = cell.content.plainText
                let font: UIFont = rowIdx == 0
                    ? .systemFont(ofSize: theme.baseFontSize, weight: .semibold)
                    : .systemFont(ofSize: theme.baseFontSize)
                let size = (text as NSString).size(withAttributes: [.font: font])
                let requested = ceil(size.width) + Self.cellPaddingH * 2 + 12
                columnWidths[colIdx] = min(width * 5, max(columnWidths[colIdx], requested))
            }
        }

        let contentWidth = columnWidths.reduce(0, +)
        let tableWidth = max(contentWidth, width)
        if tableWidth > contentWidth, colCount > 0 {
            columnWidths[colCount - 1] += tableWidth - contentWidth
        }

        // Calculate per-row heights based on text wrapping within column widths
        var rowHeights = [CGFloat](repeating: Self.minRowHeight, count: rows.count)
        for (rowIdx, row) in rows.enumerated() {
            for (colIdx, cell) in row.cells.enumerated() where colIdx < colCount {
                let font: UIFont = rowIdx == 0
                    ? .systemFont(ofSize: theme.baseFontSize, weight: .semibold)
                    : .systemFont(ofSize: theme.baseFontSize)
                let cellWidth = columnWidths[colIdx] - Self.cellPaddingH * 2
                let attrStr = renderCellInlines(cell.content, baseFont: font)
                let boundingRect = attrStr.boundingRect(
                    with: CGSize(width: cellWidth, height: .greatestFiniteMagnitude),
                    options: [.usesLineFragmentOrigin, .usesFontLeading],
                    context: nil
                )
                let needed = ceil(boundingRect.height) + Self.cellPaddingV * 2
                rowHeights[rowIdx] = max(rowHeights[rowIdx], needed)
            }
        }

        let layout = TableLayout(width: width, columnWidths: columnWidths, rowHeights: rowHeights, tableWidth: tableWidth)
        if persist {
            cachedLayout = layout
            // Snapshot per-column max char counts so `update()` can detect
            // when streaming content grew wider than what was measured here.
            var maxChars = [Int](repeating: 0, count: colCount)
            for row in rows {
                for (ci, cell) in row.cells.enumerated() where ci < colCount {
                    maxChars[ci] = max(maxChars[ci], cell.content.plainText.count)
                }
            }
            cachedMaxCharPerCol = maxChars
            // [T-ios-attachbounds-cold-cache] Populate the process-wide cache
            // (only for real, persisted widths — never the 32_768 probe, which
            // computeLayout never persists) so the NEXT recycled cell showing
            // this same table at this width skips the measurement entirely.
            Self.storeSharedLayout(layout, key: sharedKey)
        }
        Self.flushSharedLayoutStatsIfNeeded()
        return layout
    }

    // MARK: - [T-ios-attachbounds-cold-cache] Shared layout cache helpers

    private static func touchSharedLayoutLRU(_ key: LayoutCacheKey) {
        if let pos = sharedLayoutLRU.firstIndex(of: key) { sharedLayoutLRU.remove(at: pos) }
        sharedLayoutLRU.append(key)
    }

    private static func storeSharedLayout(_ layout: TableLayout, key: LayoutCacheKey) {
        sharedLayoutCache[key] = layout
        touchSharedLayoutLRU(key)
        while sharedLayoutLRU.count > sharedLayoutCacheCap, let oldest = sharedLayoutLRU.first {
            sharedLayoutLRU.removeFirst()
            sharedLayoutCache.removeValue(forKey: oldest)
        }
    }

    /// [T-ios-attachbounds-cold-cache] 1Hz hit/miss summary so we can confirm
    /// the cold-cache storm is gone (high hit ratio during decel) without
    /// per-call log spam. DEBUG-only.
    private static func flushSharedLayoutStatsIfNeeded() {
        #if DEBUG
        let now = CACurrentMediaTime()
        guard now - sharedLayoutLastFlush > 1.0, (sharedLayoutHits + sharedLayoutMisses) > 0 else { return }
        AppLogger(category: "AttachBoundsHot").info("[SharedLayoutCache] last1s hits=\(sharedLayoutHits) misses=\(sharedLayoutMisses) size=\(sharedLayoutCache.count)/\(sharedLayoutCacheCap)")
        sharedLayoutHits = 0
        sharedLayoutMisses = 0
        sharedLayoutLastFlush = now
        #endif
    }

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        // Use a slightly reduced width to prevent UIKit NSLayoutManager from
        // entering an infinite _fillLayoutHole loop when the attachment spans
        // the full line fragment width.  Also snap to 1pt grid so tiny floating-
        // point variations between layout passes hit the cached layout.
        let usableWidth = floor(lineFrag.width) - 1
        guard usableWidth > 0 else {
            return CGRect(x: 0, y: 0, width: lineFrag.width, height: Self.minRowHeight)
        }
        // [TableProbeFix] SwiftUI / UIHostingConfiguration's intrinsic-size
        // probing pass calls `attachmentBounds` with a synthesized lineFrag
        // width of `CGFloat.greatestFiniteMagnitude` (10_000_000pt). If we
        // cache the resulting layout — whose last column has been stretched
        // to fill ~10M points — the typesetter produces a glyph rect of
        // width 9_999_999 for the attachment, which then feeds `makeView`
        // and `updateAttachmentViews`. Clamp to 32_768pt and don't persist
        // the probe layout so subsequent real-width measurements aren't
        // poisoned.
        let isOversizedProbe = lineFrag.width >= 100_000
        let clampedWidth = isOversizedProbe ? min(usableWidth, 32_768) : usableWidth
        // [TableProbeHeightFix] The HEIGHT we return must reflect how the table
        // wraps at the REAL display width, not at the probe's 32_768pt. At 32_768
        // every column is wide enough that no cell wraps, so totalHeight collapses
        // to the single-line minimum — e.g. a 6-row table that is 238pt at the real
        // 369pt width measures only 218pt under the probe (one row's worth short).
        // SwiftUI's intrinsic-size probe consumes THIS returned height for the
        // cell's self-size, so on first appearance the table cell is laid out one
        // row too short and the bottom row is clipped until a scroll forces a
        // re-measure at the real width. Fix: on a probe, compute the height at a
        // realistic width, NOT the probe's 32_768pt. Width sources, in order:
        //   1. the real textContainer width — valid even during a probe (only the
        //      proposed lineFragment is synthesized to 10M, the container keeps its
        //      true width). This is the crucial first-appearance source: on the
        //      VERY first probe there is no cached layout yet, and the previous
        //      fallback (clampedWidth=32_768) still collapsed wrapping → one row
        //      short → the persistent "missing a row" the user still saw.
        //   2. the last real (non-probe) width we measured (cachedLayout).
        //   3. a conservative bubble-width cap as a last resort.
        // The RETURNED width stays the probe's clampedWidth so SwiftUI's max-width
        // discovery is unaffected. [ios_session_open_last_cell_occluded]
        let containerRealWidth: CGFloat? = {
            guard let w = textContainer?.size.width, w > 0, w < 100_000 else { return nil }
            return floor(w) - 1
        }()
        let lastRealWidth = cachedLayout.map { $0.width }.flatMap { $0 < 100_000 ? $0 : nil }
        // Remember the narrowest real width this attachment has ever been asked
        // about, across instances — the first non-probe pass records it, and any
        // probe that races AHEAD of that pass (the first-appearance case where
        // cachedLayout is still nil and textContainer reports no usable width)
        // falls back to it. A narrower width can only ADD wrapping (taller table),
        // so erring narrow guarantees we never return a height that is one row too
        // short — at worst the first frame reserves a little extra space, which a
        // later real-width measure trims. The seed (380) is < any real chat bubble
        // width on phone or pad, so it is a safe "never clip" floor.
        // [ios_session_open_last_cell_occluded]
        if !isOversizedProbe, usableWidth > 0 {
            Self.narrowestRealWidth = min(Self.narrowestRealWidth, usableWidth)
        }
        let heightWidth = isOversizedProbe
            ? (containerRealWidth ?? lastRealWidth ?? Self.narrowestRealWidth)
            : clampedWidth
        // TEMP(2026-05-13) — count attachmentBounds invocations + elapsed
        // time so a 1Hz hang on a 6-table cell shows up as e.g.
        // "[AttachBoundsHot] tables=6 calls=180 totalMs=850" — 6 tables ×
        // 30 calls/sec × ~4ms each. This pinpoints the typesetter feedback
        // loop: each fillLayoutHole inside `setFrame` re-asks every
        // attachment for its bounds, and our cache only helps when width
        // didn't change.
        let t0 = CACurrentMediaTime()
        // REPRO-DIAG(2026-05-16): record cache hit BEFORE the call (computeLayout
        // mutates cachedLayout on first miss, so we must check first).
        let _diagCacheHit = (cachedLayout?.width == heightWidth)
        // Persist only for genuine real-width measurements (never for a probe,
        // and never when we substituted a different width for height-only use).
        let layout = computeLayout(for: heightWidth, persist: !isOversizedProbe)
        let elapsed = CACurrentMediaTime() - t0
        Self.attachBoundsHotPath_record(elapsed: elapsed, cacheHit: _diagCacheHit, rows: rows.count, cols: alignments.count)
        let height = layout.totalHeight + Self.verticalMargin * 2
        let returnWidth = isOversizedProbe ? clampedWidth : usableWidth
        return CGRect(x: 0, y: 0, width: returnWidth, height: height)
    }

    // TEMP(2026-05-13) — bucket per-second invocation counts for
    // attachmentBounds. Flushed when the 1Hz fillLayoutHole loop hits.
    nonisolated(unsafe) private static var hotCallCount: Int = 0
    nonisolated(unsafe) private static var hotTotalMs: Double = 0
    nonisolated(unsafe) private static var hotLastFlush: CFAbsoluteTime = 0
    // REPRO-DIAG(2026-05-16): finer-grained counters — cache hit/miss and
    // max single-call ms so we can tell whether each call is fast (cache hit)
    // or expensive (computeLayout cell measurement on every call).
    nonisolated(unsafe) private static var hotCacheHits: Int = 0
    nonisolated(unsafe) private static var hotCacheMisses: Int = 0
    nonisolated(unsafe) private static var hotMaxMs: Double = 0
    nonisolated(unsafe) private static var hotSlowEmittedThisSec: Int = 0
    static func attachBoundsHotPath_record(elapsed: CFTimeInterval, cacheHit: Bool = false, rows: Int = 0, cols: Int = 0) {
        hotCallCount &+= 1
        let ms = elapsed * 1000
        hotTotalMs += ms
        if ms > hotMaxMs { hotMaxMs = ms }
        if cacheHit { hotCacheHits &+= 1 } else { hotCacheMisses &+= 1 }
        // REPRO-DIAG(2026-05-16): single-call slow path — anything ≥5ms is
        // unusual for cache-hit and worth investigating. Cap to 8 events/sec
        // so a true hang storm doesn't flood the log.
        if ms >= 5 && hotSlowEmittedThisSec < 8 {
            hotSlowEmittedThisSec &+= 1
            LoggingManager.shared.writeRawLine(
                category: "AttachBoundsHot",
                level: "WARN",
                message: "[TEMP-DIAG][AttachBoundsSlow] ms=\(String(format: "%.2f", ms)) cacheHit=\(cacheHit) rows=\(rows) cols=\(cols)"
            )
        }
        let now = CFAbsoluteTimeGetCurrent()
        if now - hotLastFlush >= 1.0, hotCallCount > 0 {
            // Emit when ≥20 calls OR ≥30ms cumulative in the last window.
            if hotCallCount >= 20 || hotTotalMs >= 30 {
                LoggingManager.shared.writeRawLine(
                    category: "AttachBoundsHot",
                    level: "WARN",
                    message: "[HangFix][AttachBoundsHot] TableAttachment.attachmentBounds calls=\(hotCallCount) totalMs=\(String(format: "%.1f", hotTotalMs)) avg=\(String(format: "%.2f", hotTotalMs / Double(hotCallCount)))ms max=\(String(format: "%.2f", hotMaxMs))ms hit=\(hotCacheHits) miss=\(hotCacheMisses) in last \(String(format: "%.1f", now - hotLastFlush))s"
                )
                LoggingManager.shared.flush()
            }
            hotCallCount = 0
            hotTotalMs = 0
            hotMaxMs = 0
            hotCacheHits = 0
            hotCacheMisses = 0
            hotSlowEmittedThisSec = 0
            hotLastFlush = now
        }
    }

    // MARK: - Inline rendering for table cells

    /// Renders an array of InlineNode into an NSAttributedString with proper styling
    /// (bold, italic, links, code, etc.) matching the project's standard link style.
    private func renderCellInlines(_ inlines: [InlineNode], baseFont: UIFont) -> NSAttributedString {
        let result = NSMutableAttributedString()
        for node in inlines {
            result.append(renderCellInline(node, font: baseFont))
        }
        return result
    }

    private func renderCellInline(_ node: InlineNode, font: UIFont) -> NSAttributedString {
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.label]
        switch node {
        case .text(let text):
            return NSAttributedString(string: text, attributes: attrs)
        case .softBreak:
            return NSAttributedString(string: " ", attributes: attrs)
        case .lineBreak:
            return NSAttributedString(string: "\n", attributes: attrs)
        case .code(let code):
            var codeAttrs = attrs
            codeAttrs[.font] = theme.inlineCodeFont
            codeAttrs[.foregroundColor] = theme.inlineCodeColor
            // [T-ios-table-inline-code-rounded] Mark inline code with BOTH the
            // custom `.inlineCodeBackground` flag (drives the rounded painter)
            // and the standard `.backgroundColor` (the rect range the painter
            // keys off). Two cell render paths consume this:
            //   - Link cells -> TableCellTextView, now backed by a
            //     MinisLayoutManager whose fillBackgroundRectArray draws the
            //     same rounded rect as body inline code (theme.inlineCodeCornerRadius=5).
            //   - Link-inert cells -> TableCellLabel, which has no layout
            //     manager; it strips `.backgroundColor` (to avoid the square
            //     fill) and paints the rounded background itself in drawText.
            // Both resolve minisInlineCodeBackgroundColor at draw time and
            // repaint on trait changes, so light/dark stay correct.
            codeAttrs[.inlineCodeBackground] = true
            codeAttrs[.backgroundColor] = minisInlineCodeBackgroundColor
            return NSAttributedString(string: code, attributes: codeAttrs)
        case .html(let h):
            return NSAttributedString(string: h, attributes: attrs)
        case .emphasis(let children):
            let italicFont = font.withTraits(.traitItalic)
            return renderCellInlines(children, baseFont: italicFont)
        case .strong(let children):
            let descriptor = font.fontDescriptor.addingAttributes([
                .traits: [UIFontDescriptor.TraitKey.weight: UIFont.Weight.bold]
            ])
            let boldFont = UIFont(descriptor: descriptor, size: font.pointSize)
            return renderCellInlines(children, baseFont: boldFont)
        case .strikethrough(let children):
            let result = NSMutableAttributedString(attributedString: renderCellInlines(children, baseFont: font))
            result.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: NSRange(location: 0, length: result.length))
            return result
        case .link(let destination, let children):
            let result = NSMutableAttributedString(attributedString: renderCellInlines(children, baseFont: font))
            let range = NSRange(location: 0, length: result.length)
            if let url = URL(string: destination) {
                result.addAttribute(.link, value: url, range: range)
            }
            result.addAttribute(.foregroundColor, value: theme.linkColor, range: range)
            result.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: range)
            return result
        case .image(_, let children):
            return renderCellInlines(children, baseFont: font)
        case .inlineMath(let latex):
            // Render via MathAttachment so the formula appears as an inline
            // image. The attachment starts with a transparent 1×1 image;
            // MathRenderScheduler fills in `NSTextAttachment.image` on the
            // main queue (because `inlineDrawable = true`) and the hosting
            // cell view re-assigns its `attributedText` in `onLoad` so
            // UIKit redraws with the bitmap.
            let attach = MathAttachment(latex: latex, isBlock: false, theme: theme)
            attach.inlineDrawable = true
            let result = NSMutableAttributedString(attachment: attach)
            result.addAttribute(.font, value: font, range: NSRange(location: 0, length: result.length))
            return result
        }
    }

    /// Walks an attributed string and collects every `MathAttachment`
    /// it contains. Used to post-wire async `onLoad` refreshes after the
    /// cell view is created.
    private static func collectMathAttachments(in attrStr: NSAttributedString) -> [MathAttachment] {
        var out: [MathAttachment] = []
        attrStr.enumerateAttribute(.attachment,
                                   in: NSRange(location: 0, length: attrStr.length),
                                   options: []) { value, _, _ in
            if let math = value as? MathAttachment { out.append(math) }
        }
        return out
    }

    /// Kick off async rendering for every math attachment and arrange for
    /// `refresh` to fire once on the main queue after renders complete.
    /// Multiple onLoad callbacks are coalesced via a shared flag so hundreds
    /// of completions only trigger a single `refresh` per runloop tick.
    private static func wireMathAttachments(_ attachments: [MathAttachment],
                                            cellWidth: CGFloat,
                                            rowHeight: CGFloat,
                                            attrStr: NSAttributedString,
                                            refresh: @escaping () -> Void) {
        guard !attachments.isEmpty else { return }
        // Box holding a pending-refresh flag shared by all attachments in
        // this cell. Captured by each onLoad closure.
        final class RefreshState { var pending: Bool = false }
        let state = RefreshState()
        for attachment in attachments {
            attachment.onLoad = {
                guard !state.pending else { return }
                state.pending = true
                DispatchQueue.main.async {
                    state.pending = false
                    refresh()
                }
            }
            attachment.beginRenderingIfNeeded()
        }
    }

    /// Whether an InlineNode tree contains any `.link` nodes.
    private static func containsLinks(_ inlines: [InlineNode]) -> Bool {
        for node in inlines {
            switch node {
            case .link: return true
            case .emphasis(let ch), .strong(let ch), .strikethrough(let ch), .image(_, let ch):
                if containsLinks(ch) { return true }
            default: break
            }
        }
        return false
    }

    func makeView(width: CGFloat) -> UIView {
        let wrapper = UIView()
        wrapper.backgroundColor = .clear

        // CALayer.borderColor is a CGColor and CGColor doesn't track dynamic
        // UIColor traits — once we resolve `.cgColor`, it's frozen at the
        // light/dark mode that was active *at resolution time*. When this
        // attachment view is created off-window (typical for the first
        // streaming render of a table), the trait collection is the default
        // (light mode) and resolving `UIColor.label` at 25% alpha gives a
        // near-black border that disappears against the dark message
        // background. Re-entering the session creates the view while the
        // textView is on-window with the right trait collection, which is
        // why the border returns. Use a UIScrollView subclass that
        // re-applies the layer.borderColor in `traitCollectionDidChange`.
        let scrollView = TableScrollView()
        scrollView.borderColorProvider = { [theme] traits in
            theme.tableBorderColor.resolvedColor(with: traits).cgColor
        }
        scrollView.showsHorizontalScrollIndicator = true
        scrollView.showsVerticalScrollIndicator = false
        scrollView.alwaysBounceHorizontal = false
        scrollView.alwaysBounceVertical = false
        scrollView.isScrollEnabled = true

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 0

        let layout = computeLayout(for: width)
        let colCount = alignments.count
        let columnWidths = layout.columnWidths
        let rowHeights = layout.rowHeights
        let tableWidth = layout.tableWidth

        // Build a closure that copies the entire table as tab-separated text.
        let copyTable: () -> Void = { [weak self] in
            guard let self else { return }
            UIPasteboard.general.string = self.plainText()
            // [T-toast-feedback] Confirm the copy succeeded.
            MinisToast.show(AppLocalized("Table copied"))
        }

        // [T-ios-copy-table-image] Render the FULL table (all rows/cols, not
        // just the visible scroll region) to a PNG and put it on the pasteboard.
        // We snapshot the `stack` view — it lays out every row at the real
        // `tableWidth × stackHeight`, so long/wide tables come out complete.
        // Captured weakly to avoid retaining the view tree past its lifetime;
        // a fresh image-renderer draws its layer hierarchy at screen scale over
        // the resolved table background + rounded border.
        // No dedicated table-background in the theme; use the system background
        // so the copied image is legible (adapts to light/dark) behind the
        // cell text, which carries its own foreground colors.
        let tableBg = UIColor.systemBackground
        let tableBorder = theme.tableBorderColor
        let copyTableImage: () -> Void = { [weak stack] in
            guard let stack, stack.bounds.width > 0, stack.bounds.height > 0 else { return }

            // [T-ios-copy-table-image] Clear any active text selection first so
            // the snapshot doesn't capture the blue selection highlight. The
            // selection lives in a promoted TableCellTextView somewhere under
            // `stack`; walk the subtree, drop its selection, and force a synchronous
            // redraw before rendering.
            func clearSelections(_ view: UIView) {
                if let tv = view as? UITextView {
                    tv.selectedTextRange = nil
                    tv.resignFirstResponder()
                }
                view.subviews.forEach(clearSelections)
            }
            clearSelections(stack)
            stack.layoutIfNeeded()

            let bounds = stack.bounds
            // Opaque + resolve the bg for the CURRENT light/dark trait so the
            // rounded corners (and any sub-pixel gaps) are filled with the right
            // color instead of leaving transparent corners that show white on a
            // light paste target.
            let bg = tableBg.resolvedColor(with: stack.traitCollection)
            let border = tableBorder.resolvedColor(with: stack.traitCollection)
            let fmt = UIGraphicsImageRendererFormat.default()
            fmt.opaque = true
            let renderer = UIGraphicsImageRenderer(bounds: bounds, format: fmt)
            let image = renderer.image { ctx in
                // Fill the ENTIRE rect (incl. the four corners outside the
                // rounded path) so there are no transparent/white corners.
                bg.setFill()
                ctx.fill(bounds)
                // Draw the table content (rows, cells, separators).
                stack.layer.render(in: ctx.cgContext)
                // Rounded outer border as visual chrome.
                let path = UIBezierPath(roundedRect: bounds.insetBy(dx: 0.25, dy: 0.25), cornerRadius: 8)
                border.setStroke()
                path.lineWidth = 0.5
                path.stroke()
            }
            UIPasteboard.general.image = image
            // [T-toast-feedback] Confirm the image copy succeeded.
            MinisToast.show(AppLocalized("Table image copied"))
        }

        var yOffset: CGFloat = 0
        for (rowIdx, row) in rows.enumerated() {
            let rowHeight = rowHeights[rowIdx]
            let rowView = UIView()
            rowView.translatesAutoresizingMaskIntoConstraints = false

            var x: CGFloat = 0
            for (colIdx, cell) in row.cells.enumerated() where colIdx < colCount {
                let cellContentWidth = columnWidths[colIdx] - Self.cellPaddingH * 2
                let cellFrame = CGRect(x: x + Self.cellPaddingH, y: 0, width: cellContentWidth, height: rowHeight)

                let alignment: NSTextAlignment
                switch alignments[colIdx] {
                case .center: alignment = .center
                case .right: alignment = .right
                default: alignment = .left
                }

                let baseFont: UIFont = rowIdx == 0
                    ? .systemFont(ofSize: theme.baseFontSize, weight: .semibold)
                    : .systemFont(ofSize: theme.baseFontSize)

                let attrStr = NSMutableAttributedString(attributedString: renderCellInlines(cell.content, baseFont: baseFont))
                let paraStyle = NSMutableParagraphStyle()
                paraStyle.alignment = alignment
                attrStr.addAttribute(.paragraphStyle, value: paraStyle, range: NSRange(location: 0, length: attrStr.length))

                // Detect tappable links and ensure they carry an `.link`
                // attribute on the rendered string. cmark-gfm autolink
                // usually upgrades bare URLs to `.link` nodes, but boundary
                // cases (URL adjacent to CJK characters, full-width
                // punctuation, table cell edges) can leave them as `.text`
                // — in which case the AST-based `containsLinks` returns
                // false AND there's no `.link` attribute in attrStr, so we
                // fall through to TableCellLabel (link-inert) and taps die.
                // Run NSDataDetector as a belt-and-suspenders pass to add
                // `.link` attributes for any URL the parser missed, then
                // route the cell to TableCellTextView so taps work.
                // [T-table-cell-link-bug 2026-05-25]
                var hasLinks = Self.containsLinks(cell.content)
                if !hasLinks {
                    attrStr.enumerateAttribute(.link,
                                                in: NSRange(location: 0, length: attrStr.length),
                                                options: []) { value, _, stop in
                        if value != nil { hasLinks = true; stop.pointee = true }
                    }
                }
                if !hasLinks, let detector = Self.linkDataDetector {
                    let plain = attrStr.string
                    let fullRange = NSRange(location: 0, length: (plain as NSString).length)
                    let matches = detector.matches(in: plain, options: [], range: fullRange)
                    for m in matches {
                        guard let url = m.url else { continue }
                        // Don't clobber an existing `.link` attribute (e.g.
                        // from a `[text](url)` node that happened to match).
                        if attrStr.attribute(.link, at: m.range.location, effectiveRange: nil) != nil {
                            continue
                        }
                        attrStr.addAttribute(.link, value: url, range: m.range)
                        attrStr.addAttribute(.foregroundColor, value: theme.linkColor, range: m.range)
                        attrStr.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: m.range)
                        hasLinks = true
                    }
                }

                // Collect math attachments so we can re-kick the cell's view
                // when the async SwiftMath render finishes. Without this the
                // cell shows a 1×1 transparent placeholder forever.
                let mathAttachments = Self.collectMathAttachments(in: attrStr)

                if hasLinks {
                    let tv = TableCellTextView()
                    tv.isEditable = false
                    tv.isScrollEnabled = false
                    tv.backgroundColor = .clear
                    tv.textContainerInset = .zero
                    tv.textContainer.lineFragmentPadding = 0
                    tv.linkTextAttributes = [
                        .foregroundColor: theme.linkColor,
                        .underlineStyle: NSUnderlineStyle.single.rawValue,
                    ]
                    tv.attributedText = attrStr

                    if let onOpenURL {
                        tv.onOpenURL = onOpenURL
                    }

                    tv.frame = cellFrame
                    let textSize = tv.sizeThatFits(CGSize(width: cellFrame.width, height: .greatestFiniteMagnitude))
                    tv.frame.origin.y = (rowHeight - textSize.height) / 2
                    rowView.addSubview(tv)

                    Self.wireMathAttachments(mathAttachments, cellWidth: cellFrame.width, rowHeight: rowHeight, attrStr: attrStr) { [weak tv] in
                        guard let tv else { return }
                        tv.attributedText = attrStr
                        let ts = tv.sizeThatFits(CGSize(width: cellFrame.width, height: .greatestFiniteMagnitude))
                        tv.frame.origin.y = (rowHeight - ts.height) / 2
                    }
                } else {
                    let label = TableCellLabel()
                    label.backgroundColor = .clear
                    label.numberOfLines = 0
                    label.attributedText = attrStr
                    label.selectableAttributedText = attrStr
                    label.copyTableAction = copyTable
                    label.copyTableImageAction = copyTableImage
                    label.frame = cellFrame
                    let textSize = label.sizeThatFits(CGSize(width: cellFrame.width, height: .greatestFiniteMagnitude))
                    label.frame.size.height = textSize.height
                    label.frame.origin.y = (rowHeight - textSize.height) / 2
                    rowView.addSubview(label)

                    Self.wireMathAttachments(mathAttachments, cellWidth: cellFrame.width, rowHeight: rowHeight, attrStr: attrStr) { [weak label] in
                        guard let label else { return }
                        label.attributedText = attrStr
                        label.selectableAttributedText = attrStr
                        let ts = label.sizeThatFits(CGSize(width: cellFrame.width, height: .greatestFiniteMagnitude))
                        label.frame.size.height = ts.height
                        label.frame.origin.y = (rowHeight - ts.height) / 2
                        label.setNeedsDisplay()
                    }
                }

                // Vertical separator — full height to connect across rows
                if colIdx < colCount - 1 {
                    let sep = UIView()
                    sep.backgroundColor = theme.tableBorderColor
                    sep.frame = CGRect(x: x + columnWidths[colIdx] - 0.25, y: 0, width: 0.5, height: rowHeight)
                    rowView.addSubview(sep)
                }
                x += columnWidths[colIdx]
            }

            // Horizontal separator between all rows (bottom edge of each row except last)
            if rowIdx < rows.count - 1 {
                let hSep = UIView()
                hSep.backgroundColor = theme.tableBorderColor
                hSep.frame = CGRect(x: 0, y: rowHeight - 0.25, width: tableWidth, height: 0.5)
                rowView.addSubview(hSep)
            }

            rowView.frame = CGRect(x: 0, y: yOffset, width: tableWidth, height: rowHeight)
            stack.addArrangedSubview(rowView)
            NSLayoutConstraint.activate([
                rowView.heightAnchor.constraint(equalToConstant: rowHeight),
                rowView.widthAnchor.constraint(equalToConstant: tableWidth),
            ])
            yOffset += rowHeight
        }

        let stackHeight = rowHeights.reduce(0, +)
        stack.frame = CGRect(x: 0, y: 0, width: tableWidth, height: stackHeight)
        scrollView.addSubview(stack)
        scrollView.contentSize = CGSize(width: tableWidth, height: stackHeight)
        scrollView.frame = CGRect(x: 0, y: Self.verticalMargin, width: width, height: stackHeight)

        // Border — borderColor is re-applied by TableScrollView on every
        // traitCollectionDidChange via `borderColorProvider`, so dark-mode
        // hosts always get the right resolved color even when the view was
        // initially created off-window.
        scrollView.layer.cornerRadius = 8
        scrollView.layer.borderWidth = 0.5
        scrollView.applyBorderColorForCurrentTraits()
        scrollView.clipsToBounds = true

        wrapper.addSubview(scrollView)
        wrapper.frame = CGRect(x: 0, y: 0, width: width, height: stackHeight + Self.verticalMargin * 2)

        // [T-ios-table-hscroll-offset-lost] Keep the attachment's persistent
        // offset in sync with every user-driven scroll on this view, then
        // restore it into the freshly-built view. Restore SYNCHRONOUSLY —
        // frame and contentSize are already final at this point — so a rapid
        // follow-up streaming rebuild reads the real position instead of a
        // transient 0 (the old consume-once + async-only restore lost the
        // offset exactly that way). The async re-clamp is belt-and-suspenders
        // for hosts that adjust bounds after attach. Never cleared: the next
        // rebuild needs the value again.
        scrollView.onUserScroll = { [weak self] offset in
            self?.preservedScrollOffset = offset
        }
        if let saved = preservedScrollOffset, saved.x > 0 {
            let applyClamped = { [weak scrollView] in
                guard let scrollView else { return }
                let maxX = max(0, scrollView.contentSize.width - scrollView.bounds.width)
                let x = min(max(0, saved.x), maxX)
                scrollView.contentOffset = CGPoint(x: x, y: 0)
            }
            applyClamped()
            DispatchQueue.main.async(execute: applyClamped)
        }

        return wrapper
    }

}

/// UIScrollView subclass that re-resolves its `layer.borderColor` whenever
/// the active trait collection changes. CGColor values frozen from a
/// dynamic UIColor at view-creation time would otherwise lock in whichever
/// appearance was active when the attachment was first laid out — typically
/// before the view enters the window, so light-mode `UIColor.label`
/// resolves and renders nearly invisible against a dark message background.
final class TableScrollView: UIScrollView, UIScrollViewDelegate {
    /// Closure called every time the trait collection changes; should
    /// return a CGColor resolved for the supplied trait collection.
    var borderColorProvider: ((UITraitCollection) -> CGColor)?

    /// [T-ios-table-hscroll-stream-interrupt] One-shot callback fired when a
    /// user scroll interaction fully ends (finger lifted with no momentum, or
    /// deceleration settled). Armed by updateAttachmentViews when a streaming
    /// rebuild of this table was deferred because the view was mid-gesture;
    /// the callback replays the deferred rebuild. Cleared before invocation
    /// so a later interaction with no pending rebuild fires nothing.
    var onInteractionEnded: (() -> Void)?

    /// [T-ios-table-hscroll-offset-lost] Reports every USER-driven offset
    /// change (finger down or momentum) so the owning TableAttachment can
    /// persist the position across streaming rebuilds. Programmatic
    /// setContentOffset calls (e.g. the makeView restore) don't fire this —
    /// they run with isTracking/isDragging/isDecelerating all false — so a
    /// clamped restore can never overwrite the user's real position.
    var onUserScroll: ((CGPoint) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if isTracking || isDragging || isDecelerating {
            onUserScroll?(contentOffset)
        }
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate { fireInteractionEnded() }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        fireInteractionEnded()
    }

    private func fireInteractionEnded() {
        guard let callback = onInteractionEnded else { return }
        onInteractionEnded = nil
        callback()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyBorderColorForCurrentTraits()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // The trait collection becomes meaningful only after the view is
        // attached to a window; resolve once at that moment so the border
        // is correct on the very first frame the user sees.
        applyBorderColorForCurrentTraits()
    }

    func applyBorderColorForCurrentTraits() {
        guard let provider = borderColorProvider else { return }
        layer.borderColor = provider(traitCollection)
    }
}

/// Minimal UITextView subclass for table cells with tappable links.
/// Intercepts link taps and routes them through the same openURL handler
/// used by the main markdown text view.
private final class TableCellTextView: UITextView, UITextViewDelegate {
    var onOpenURL: ((URL) -> Void)?
    /// Called when the text view resigns first responder (selection dismissed).
    var onResignFirstResponder: (() -> Void)?
    /// Closure that copies the entire table. Set by TableCellLabel when promoting.
    var copyTableAction: (() -> Void)?
    /// [T-ios-copy-table-image] Closure that copies the full table as a PNG image.
    var copyTableImageAction: (() -> Void)?

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        self.delegate = self
    }

    /// [T-ios-table-inline-code-rounded] TextKit1 stack backed by a
    /// MinisLayoutManager so inline-code spans (marked with
    /// `.inlineCodeBackground`) draw the same rounded background as body
    /// inline code via fillBackgroundRectArray. The default UITextView layout
    /// manager only paints square `.backgroundColor`.
    convenience init() {
        let textStorage = NSTextStorage()
        let layoutManager = MinisLayoutManager()
        let textContainer = NSTextContainer()
        textContainer.lineFragmentPadding = 0
        textContainer.widthTracksTextView = true
        layoutManager.addTextContainer(textContainer)
        textStorage.addLayoutManager(layoutManager)
        self.init(frame: .zero, textContainer: textContainer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    // [T-ios-table-cell-image-menu] iOS 16 ONLY: drop the system
    // UIContextMenuInteraction so a promoted table cell never pops the
    // "Copy Image" / "Save to Camera Roll" preview menu for the cell's inline
    // attachments. Same rationale as SelectableMarkdownTextView.addInteraction;
    // iOS 17+ keeps the (handled) system menu.
    override func addInteraction(_ interaction: any UIInteraction) {
        if #unavailable(iOS 17.0), interaction is UIContextMenuInteraction {
            return
        }
        super.addInteraction(interaction)
    }

    // [T-ios-preapply-endediting] Responder changes are synchronous again.
    // The deferred-resign guard that used to live here (c1fd59e1f) returned
    // `false` mid-batch-update, which violates UIKit's rebase contract and
    // fired the NSInternalInconsistencyException in
    // _resignOrRebaseFirstResponderViewWithIndexPathMapping: (TestFlight
    // 1.13(15)). The invariant is now enforced one level up: applySnapshot
    // calls endEditing BEFORE the structural apply, so by the time a batch
    // update runs there is no first responder to resign or rebase — which
    // also removes the AttributeGraph re-entry the deferral was built for.
    // `become` registers this view as the tracked responder so that gate
    // knows when it must act.
    override func becomeFirstResponder() -> Bool {
        let cv = findCollectionView()
        cv?.offsetLocked = true
        let result = super.becomeFirstResponder()
        cv?.offsetLocked = false
        if result { cv?.trackedTextResponder = self }
        return result
    }

    override func resignFirstResponder() -> Bool {
        let cv = findCollectionView()
        if cv?.isApplyingSnapshot == true {
            // Should be unreachable: applySnapshot ends editing before the
            // apply. If some path still gets here, run `super` synchronously —
            // the pre-c1fd59e1f behavior. In this window a synchronous super
            // is an OCCASIONAL AttributeGraph crash while returning false is a
            // GUARANTEED rebase assertion, so synchronous strictly dominates.
            // WARN so any hit is visible in logs.
            responderLogger.warning("[Responder] table-cell resignFirstResponder DURING applySnapshot — pre-apply endEditing missed this responder")
        }
        cv?.offsetLocked = true
        let result = super.resignFirstResponder()
        cv?.offsetLocked = false
        if cv?.trackedTextResponder === self { cv?.trackedTextResponder = nil }
        onResignFirstResponder?()
        return result
    }

    private func findCollectionView() -> NoAnimationCollectionView? {
        var view: UIView? = superview
        while let v = view {
            if let cv = v as? NoAnimationCollectionView { return cv }
            view = v.superview
        }
        return nil
    }

    // MARK: Edit menu — append "Copy Table" + "Add to Chat Input" alongside the standard items.

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        // Allow our own table commands first — copyTableImage's selector name
        // contains "image", so it must be whitelisted BEFORE the iOS-16 image
        // filter below or it would be swallowed.
        if action == #selector(copyTable) { return copyTableAction != nil }
        if action == #selector(copyTableImage) { return copyTableImageAction != nil }
        if action == #selector(addSelectionToChatInput(_:)) {
            return selectedRange.length > 0
        }
        if action == #selector(readSelectedAloud) { return selectedRange.length > 0 }
        if action == #selector(readAllAloud) { return !text.isEmpty }
        // Read-only: never offer edit actions. Also block system Speak
        // (we provide our own TTS via VoiceOutputPlayer).
        let blocked: Set<String> = ["paste:", "cut:", "delete:", "_addShortcut:",
                                     "_speak:", "_accessibilitySpeak:"]
        let actionName = NSStringFromSelector(action)
        if actionName.contains("speak") || actionName.contains("Speak") { return false }
        if blocked.contains(actionName) { return false }
        // iOS 16: a table cell that lays out an inline NSTextAttachment with a
        // transparent placeholder image makes UIKit add system "Copy Image" /
        // "Save to Camera Roll" / "Share Image" items — with a black preview,
        // since the placeholder is 1×1 transparent. Copying that is meaningless
        // (the real action is "Copy Table Image"). Filter the system image
        // selectors so only the text/table actions remain. iOS 17+ uses
        // UIEditMenuInteraction and doesn't surface these. Mirrors the same
        // guard in SelectableMarkdownTextView.canPerformAction.
        if actionName.localizedCaseInsensitiveContains("image") {
            return false
        }
        return super.canPerformAction(action, withSender: sender)
    }

    @objc func copyTable() {
        copyTableAction?()
    }

    @objc func copyTableImage() {
        copyTableImageAction?()
    }

    @objc func readSelectedAloud() {
        guard selectedRange.length > 0,
              let range = Range(selectedRange, in: text) else { return }
        VoiceOutputPlayer.shared.stopAll()
        Self.activateReadAloudState()
        VoiceOutputPlayer.shared.enqueueSegmented(String(text[range]), sessionId: VoiceOutputPlayer.manualOwnerId)
    }

    @objc func readAllAloud() {
        VoiceOutputPlayer.shared.stopAll()
        Self.activateReadAloudState()
        VoiceOutputPlayer.shared.enqueueSegmented(text, sessionId: VoiceOutputPlayer.manualOwnerId)
    }

    /// Menu-triggered read-aloud bypasses AIChatViewModel's reply-TTS flow, so
    /// mirror `readReplyFromStart`'s pre-flight here: force the global
    /// read-replies switch ON (runtime state + persisted pref, un-muting if
    /// needed) and push "reading" to the capsule — via the active chat VM when
    /// one is registered, else directly on the global state object.
    static func activateReadAloudState() {
        let state = VoiceOutputState.shared
        if !state.isEnabled { state.isEnabled = true }   // didSet persists to prefs
        if state.isMuted { state.isMuted = false }
        if let vm = state.activeController as? AIChatViewModel {
            vm.isReadingAloud = true
            vm.speechPaused = false
            vm.syncSpeechStateToGlobal(registerActive: true)
        } else {
            state.syncState(reading: true, paused: false,
                            speed: VoiceOutputPreferences.speedMultiplier)
        }
    }

    /// Same as SelectableMarkdownTextView.addSelectionToChatInput — posts a
    /// `chatInputAppendRequested` notification with the selected cell text.
    /// AIChatView listens and routes to `AIChatViewModel.appendToInputText`.
    @objc func addSelectionToChatInput(_ sender: Any?) {
        guard selectedRange.length > 0,
              let range = Range(selectedRange, in: text) else { return }
        let snippet = String(text[range])
        NotificationCenter.default.post(
            name: .chatInputAppendRequested,
            object: nil,
            userInfo: ["text": snippet]
        )
        resignFirstResponder()
    }

    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        // Build ONE inline menu so the order is explicit and stable, then insert
        // it after the system edit menu. Desired order (top→bottom):
        //   Add to Chat Input  →  Copy Table  →  Copy Table Image
        // i.e. the high-frequency "Add to Chat Input" sits first, with the table
        // copy actions after it. [T-ios-copy-table-menu-order]
        var children: [UICommand] = []
        if selectedRange.length > 0 {
            children.append(
                UICommand(
                    title: AppLocalized("Add to Chat Input"),
                    image: UIImage(systemName: "text.insert"),
                    action: #selector(addSelectionToChatInput(_:))
                )
            )
        }
        if copyTableAction != nil {
            children.append(
                UICommand(
                    title: AppLocalized("Copy Table"),
                    image: UIImage(systemName: "tablecells"),
                    action: #selector(copyTable)
                )
            )
            // [T-ios-copy-table-image] Offer copying the whole table as an image.
            if copyTableImageAction != nil {
                children.append(
                    UICommand(
                        title: AppLocalized("Copy Table Image"),
                        image: UIImage(systemName: "tablecells.badge.ellipsis"),
                        action: #selector(copyTableImage)
                    )
                )
            }
        }
        // TTS actions — read selected or read all via VoiceOutputPlayer.
        if selectedRange.length > 0 {
            children.append(
                UICommand(
                    title: AppLocalized("Read Selected Aloud"),
                    image: UIImage(systemName: "speaker.wave.2"),
                    action: #selector(readSelectedAloud)
                )
            )
        }
        children.append(
            UICommand(
                title: AppLocalized("Read All Aloud"),
                image: UIImage(systemName: "play.circle"),
                action: #selector(readAllAloud)
            )
        )
        // Remove system Speak menu if present.
        builder.remove(menu: .speech)
        let menu = UIMenu(title: "", options: .displayInline, children: children)
        builder.insertSibling(menu, afterMenu: .standardEdit)
    }

    func textView(_ textView: UITextView, shouldInteractWith URL: URL, in characterRange: NSRange, interaction: UITextItemInteraction) -> Bool {
        if interaction == .invokeDefaultAction, let onOpenURL {
            onOpenURL(URL)
            return false
        }
        return true
    }
}

/// Lightweight UILabel used for table cells without links.
/// On long-press the label is swapped out for a UITextView so the user can select text,
/// then swapped back when the selection is dismissed — keeping the steady-state cost
/// at ~1-2 KB instead of ~35 KB per UITextView.
private final class TableCellLabel: UILabel, UIGestureRecognizerDelegate, UIContextMenuInteractionDelegate {
    /// The attributed string to hand off to the UITextView when activated.
    var selectableAttributedText: NSAttributedString?
    /// Closure that copies the entire parent table. Set during `makeView`.
    var copyTableAction: (() -> Void)?
    /// [T-ios-copy-table-image] Closure that copies the full table as a PNG image.
    var copyTableImageAction: (() -> Void)?
    /// Currently promoted UITextView (if any). Kept to clean up on dealloc/reuse.
    private weak var activeTextView: TableCellTextView?
    /// Window-level tap gesture used to dismiss the promoted text view.
    private var dismissTap: UITapGestureRecognizer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = true
        let lp = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        lp.minimumPressDuration = 0.4
        addGestureRecognizer(lp)

        // Double-tap selects the cell text and shows the edit menu — mirrors
        // the long-press and right-click flows so users on iPad without a
        // trackpad can reach Copy quickly.
        let dt = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        dt.numberOfTapsRequired = 2
        addGestureRecognizer(dt)

        // Right-click (external mouse / trackpad secondary) promotes the cell
        // to a selectable UITextView so the native edit menu can appear.
        addInteraction(UIContextMenuInteraction(delegate: self))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    deinit {
        removeDismissTap()
        activeTextView?.removeFromSuperview()
    }

    // MARK: Rounded inline-code background

    // [T-ios-table-inline-code-rounded] The label has no NSLayoutManager, so
    // the body painter (fillBackgroundRectArray) can't reach it. Setting the
    // standard `.backgroundColor` would paint SQUARE corners. Instead we strip
    // `.backgroundColor` from the displayed text (keeping `.inlineCodeBackground`
    // as the range marker) and paint rounded rects ourselves in drawText.
    // `selectableAttributedText` is left untouched — the promoted UITextView
    // keeps `.backgroundColor` so its MinisLayoutManager painter still fires.

    override var attributedText: NSAttributedString? {
        get { super.attributedText }
        set { super.attributedText = newValue.map(Self.strippingSquareCodeBackground) }
    }

    /// Returns a copy with `.backgroundColor` removed from any inline-code
    /// range (ranges carrying `.inlineCodeBackground`), leaving other
    /// background colors — if any — intact.
    private static func strippingSquareCodeBackground(_ attr: NSAttributedString) -> NSAttributedString {
        let full = NSRange(location: 0, length: attr.length)
        var hasCode = false
        attr.enumerateAttribute(.inlineCodeBackground, in: full, options: []) { value, _, stop in
            if value != nil { hasCode = true; stop.pointee = true }
        }
        guard hasCode else { return attr }
        let mutable = NSMutableAttributedString(attributedString: attr)
        attr.enumerateAttribute(.inlineCodeBackground, in: full, options: []) { value, range, _ in
            if value != nil { mutable.removeAttribute(.backgroundColor, range: range) }
        }
        return mutable
    }

    override func drawText(in rect: CGRect) {
        paintInlineCodeBackgrounds()
        super.drawText(in: rect)
    }

    /// Lay the attributed text out in a transient TextKit1 stack matching the
    /// label's bounds, then fill a rounded rect behind each inline-code range.
    private func paintInlineCodeBackgrounds() {
        guard let attr = super.attributedText, attr.length > 0,
              let ctx = UIGraphicsGetCurrentContext() else { return }
        let full = NSRange(location: 0, length: attr.length)
        var codeRanges: [NSRange] = []
        attr.enumerateAttribute(.inlineCodeBackground, in: full, options: []) { value, range, _ in
            if value != nil { codeRanges.append(range) }
        }
        guard !codeRanges.isEmpty else { return }

        let storage = NSTextStorage(attributedString: attr)
        let manager = NSLayoutManager()
        let container = NSTextContainer(size: CGSize(width: bounds.width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        container.maximumNumberOfLines = numberOfLines
        container.lineBreakMode = lineBreakMode
        manager.addTextContainer(container)
        storage.addLayoutManager(manager)
        manager.ensureLayout(for: container)

        // Vertically center the laid-out text the same way UILabel does so the
        // painted rects line up with the glyphs super draws.
        let usedHeight = manager.usedRect(for: container).height
        let yOffset = max(0, (bounds.height - usedHeight) / 2)

        let bgColor = minisInlineCodeBackgroundColor.resolvedColor(with: traitCollection)
        ctx.saveGState()
        ctx.setFillColor(bgColor.cgColor)
        for range in codeRanges {
            let glyphRange = manager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            manager.enumerateEnclosingRects(forGlyphRange: glyphRange,
                                            withinSelectedGlyphRange: NSRange(location: NSNotFound, length: 0),
                                            in: container) { rectPtr, _ in
                let r = rectPtr.offsetBy(dx: 0, dy: yOffset).insetBy(dx: -1, dy: -0.5)
                guard r.width > 2 else { return }
                let path = UIBezierPath(roundedRect: r, cornerRadius: 5)
                ctx.addPath(path.cgPath)
            }
        }
        ctx.fillPath()
        ctx.restoreGState()
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        promoteToTextView()
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended else { return }
        promoteToTextView()
    }

    // MARK: UIContextMenuInteractionDelegate

    /// Right-click (external mouse / trackpad) promotes the cell to a
    /// selectable UITextView with its text already selected — matching the
    /// long-press flow. Returning nil suppresses UIKit's default context menu
    /// so UITextView's native edit menu (Copy / Look Up / etc.) takes over.
    func contextMenuInteraction(
        _ interaction: UIContextMenuInteraction,
        configurationForMenuAtLocation location: CGPoint
    ) -> UIContextMenuConfiguration? {
        promoteToTextView()
        return nil
    }

    /// Replace this label with a selectable UITextView and immediately activate selection.
    private func promoteToTextView() {
        guard let parent = superview, activeTextView == nil else { return }

        let tv = TableCellTextView()
        tv.isEditable = false
        tv.isScrollEnabled = false
        tv.backgroundColor = .clear
        tv.textContainer.lineFragmentPadding = 0
        tv.attributedText = selectableAttributedText ?? attributedText

        // Give the text view a taller frame than the label so the selection
        // handles have enough touch area. Vertically pad around the text so
        // the content stays visually centered at the label's original position.
        let extraPad: CGFloat = 12
        tv.frame = CGRect(
            x: frame.origin.x,
            y: frame.origin.y - extraPad,
            width: frame.width,
            height: frame.height + extraPad * 2
        )
        tv.textContainerInset = UIEdgeInsets(top: extraPad, left: 0, bottom: extraPad, right: 0)

        // When the user taps away or the copy menu closes, the text view resigns
        // first responder. Swap back to the lightweight label at that point.
        tv.onResignFirstResponder = { [weak self] in
            self?.demoteTextView()
        }

        // Pass through the "Copy Table" action so it appears in the edit menu.
        tv.copyTableAction = copyTableAction
        tv.copyTableImageAction = copyTableImageAction

        activeTextView = tv
        isHidden = true
        parent.addSubview(tv)

        // Register the custom menu items, then select all and show the menu.
        // Order matches buildMenu: Add to Chat Input → Copy Table → Copy Table
        // Image. [T-ios-copy-table-menu-order]
        UIMenuController.shared.menuItems = [
            UIMenuItem(title: AppLocalized("Add to Chat Input"), action: #selector(TableCellTextView.addSelectionToChatInput(_:))),
            UIMenuItem(title: AppLocalized("Copy Table"), action: #selector(TableCellTextView.copyTable)),
            // [T-ios-copy-table-image]
            UIMenuItem(title: AppLocalized("Copy Table Image"), action: #selector(TableCellTextView.copyTableImage)),
        ]

        // Select all text after a run-loop turn so TextKit finishes layout.
        DispatchQueue.main.async { [weak self, weak tv] in
            guard let tv else { return }
            tv.becomeFirstResponder()
            tv.selectedRange = NSRange(location: 0, length: tv.attributedText?.length ?? 0)

            // Show the edit menu over the text view.
            UIMenuController.shared.showMenu(from: tv, rect: tv.bounds)

            // Install the dismiss-tap after a short delay so it doesn't
            // immediately interfere with the menu presentation.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                self?.installDismissTap()
            }
        }
    }

    /// Adds a tap recognizer to the window so that tapping *anywhere* outside
    /// the edit menu dismisses the promoted text view.
    private func installDismissTap() {
        guard let window = activeTextView?.window, dismissTap == nil else { return }
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleDismissTap(_:)))
        tap.cancelsTouchesInView = false
        tap.delegate = self
        window.addGestureRecognizer(tap)
        dismissTap = tap
    }

    private func removeDismissTap() {
        if let tap = dismissTap {
            tap.view?.removeGestureRecognizer(tap)
            dismissTap = nil
        }
    }

    @objc private func handleDismissTap(_ gesture: UITapGestureRecognizer) {
        // Let the text view resign naturally — this triggers demoteTextView()
        // via onResignFirstResponder. Calling demoteTextView() directly here
        // would remove the text view before a menu action (e.g. Copy) completes.
        activeTextView?.resignFirstResponder()
    }

    // MARK: UIGestureRecognizerDelegate

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        // Don't let the dismiss tap fire while the edit menu is visible —
        // otherwise it kills the text view before Copy can execute.
        if gestureRecognizer === dismissTap, UIMenuController.shared.isMenuVisible {
            return false
        }
        return true
    }

    /// Remove the promoted UITextView and show the label again.
    private func demoteTextView() {
        removeDismissTap()
        activeTextView?.onResignFirstResponder = nil
        activeTextView?.removeFromSuperview()
        activeTextView = nil
        isHidden = false
        UIMenuController.shared.menuItems = nil
    }
}

// MARK: - Thematic Break Attachment

final class ThematicBreakAttachment: NSTextAttachment {
    let theme: SelectableMarkdownTheme

    init(theme: SelectableMarkdownTheme) {
        self.theme = theme
        super.init(data: nil, ofType: nil)
        self.image = Self.transparentImage
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private static let transparentImage: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
    }()

    /// Spacing controlled by RenderedBlock margins; no extra padding inside attachment
    static let verticalMargin: CGFloat = 0

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        CGRect(x: 0, y: 0, width: lineFrag.width, height: 1 + Self.verticalMargin * 2)
    }

    func makeView(width: CGFloat) -> UIView {
        let totalHeight = 1 + Self.verticalMargin * 2
        let container = UIView(frame: CGRect(x: 0, y: 0, width: width, height: totalHeight))
        let line = UIView(frame: CGRect(x: 0, y: Self.verticalMargin, width: width, height: 1))
        line.backgroundColor = theme.secondaryLabelColor.withAlphaComponent(0.3)
        container.addSubview(line)
        return container
    }
}

// MARK: - Math Attachment

final class MathAttachment: NSTextAttachment {
    let latex: String
    let isBlock: Bool
    let theme: SelectableMarkdownTheme
    private(set) var renderedImage: UIImage?
    private(set) var renderedSize: CGSize = .zero
    /// [issue #117-4] Baseline offset from the rendered image's bottom edge,
    /// straight out of SwiftMath's typesetter. 0 = unknown (Unicode fallback),
    /// in which case `attachmentBounds` falls back to an approximation.
    private(set) var renderedBaselineFromBottom: CGFloat = 0
    private var didAttemptRender = false
    private var isKaTeXPending = false
    /// Both SwiftMath and KaTeX failed — show raw LaTeX fallback.
    private(set) var renderFailed = false
    var onLoad: (() -> Void)?

    init(latex: String, isBlock: Bool, theme: SelectableMarkdownTheme) {
        self.latex = latex
        self.isBlock = isBlock
        self.theme = theme
        super.init(data: nil, ofType: nil)
        self.image = Self.transparentImage
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private static let transparentImage: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
    }()

    private static let inlinePlaceholderHeight: CGFloat = 20
    private static let blockPlaceholderHeight: CGFloat = 44

    /// [T-ios-math-sync-render-at-measure] One-shot guard so a failed sync
    /// render is not retried on every glyph pass (render() does not cache
    /// failures, so retrying would re-parse per typeset).
    private var didAttemptSyncRender = false

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        // [T-ios-math-sync-render-at-measure] Render HERE, synchronously, before
        // ever answering with a placeholder.
        //
        // Why: the placeholder heights (44pt block / 20pt inline) participate in
        // the FIRST measurement of the cell, and that number is cached above us
        // by SwiftUI's hosting-configuration view graph — a cache that only
        // invalidates on a SwiftUI-visible state change, which a UIKit-side
        // async render completion is not. Six successive fixes cleared every
        // cache we own (layout memo 22df168cf, TextKit re-typeset c7ee9f16a,
        // representable size cache 48043be1e) and the device number never
        // moved: cell 6166 vs canvas 7154.7, both before and after, because
        // every fresh parse re-measured with placeholders and every correction
        // dead-ended at the SwiftUI layout cache. The only durable fix is for
        // the first measure to be RIGHT.
        //
        // Why this is affordable: SwiftMathRenderer.render is synchronous and
        // NSCache-backed (256 entries, keyed latex+mode+fontSize). The
        // scheduler's own telemetry on the failing device: 55 formulas in
        // 123ms wall, avg 0.62ms — and that work was ALREADY being done
        // moments after the measure; this moves it before the measure and the
        // cache makes every later parse of the same formula free. KaTeX
        // (WebView, truly async) stays on the scheduler path: if sync render
        // fails we fall through to the placeholder exactly as before.
        // Main-thread only — MTMathUILabel is UIKit.
        if renderedImage == nil, !renderFailed, !isKaTeXPending,
           !didAttemptSyncRender {
            if Thread.isMainThread {
                didAttemptSyncRender = true
                // Mirror performRender's chain exactly. This MUST include
                // renderFallback: SwiftMath cannot render CJK, so render()
                // returns nil for precisely the formulas this bug ships with
                // (`N_{A流}`, `公司整体股权市值 = …`) — instrumented on device:
                // every CJK formula logged "sync render FAILED" while ASCII
                // (`P_A`, `R`) rendered, which is why the placeholder height
                // still won the first measure. renderFallback is equally
                // synchronous and NSCache-backed ("F:" key).
                let result = SwiftMathRenderer.render(latex: latex, displayMode: isBlock, fontSize: theme.baseFontSize)
                    ?? SwiftMathRenderer.renderFallback(latex: latex, displayMode: isBlock, fontSize: theme.baseFontSize)
                if let result {
                    applyResult(result)
                    // Scheduler no longer needs to touch this attachment.
                    didAttemptRender = true
                    MathAttachment.syncRenderHits &+= 1
                    if MathAttachment.syncRenderHits <= 5 || MathAttachment.syncRenderHits % 50 == 0 {
                        AppLogger(category: "MathSync").info("[MathSync] SYNC-RENDERED #\(MathAttachment.syncRenderHits) h=\(String(format: "%.1f", result.image.size.height)) isBlock=\(self.isBlock) latex=\(self.latex.prefix(24))")
                    }
                } else {
                    AppLogger(category: "MathSync").info("[MathSync] sync render FAILED (falls to scheduler) latex=\(self.latex.prefix(24))")
                }
            } else {
                // [T-ios-math-sync-render-at-measure] Diagnostic: if measures
                // reach attachmentBounds OFF-MAIN, the sync path can never help
                // them and the placeholder participates in that measurement.
                MathAttachment.offMainSkips &+= 1
                if MathAttachment.offMainSkips <= 5 || MathAttachment.offMainSkips % 50 == 0 {
                    AppLogger(category: "MathSync").info("[MathSync] OFF-MAIN skip #\(MathAttachment.offMainSkips) — placeholder used in this measurement thread=\(Thread.current)")
                }
            }
        }
        if let img = renderedImage {
            let imgW = min(img.size.width, lineFrag.width)
            let imgH = img.size.height
            if isBlock {
                return CGRect(x: 0, y: 0, width: lineFrag.width, height: imgH)
            } else {
                // [issue #117-4] TRUE BASELINE ALIGNMENT.
                //
                // attachmentBounds' y is the offset from the surrounding text's
                // baseline to the image's BOTTOM edge (positive = up). So to
                // seat the formula's own baseline on the text baseline, the
                // bottom edge must sit `baselineFromBottom` BELOW it:
                //
                //     y = -baselineFromBottom
                //
                // and everything follows from the typesetter: `x` (no descender)
                // renders with a baseline at the image bottom → y ≈ 0, sitting
                // flush on the line; `y`/`p` carry a real descent → the tail
                // drops below the baseline exactly as in running text; `x²` gets
                // its extra height entirely above → the base `x` lands on the
                // baseline, pixel-aligned with a standalone `x`; an inline
                // fraction's denominator descends below the baseline, which is
                // the correct TeX behaviour (the fraction bar rides the math
                // axis ≈0.25em above the baseline) and NOT something to "fix" —
                // TextKit grows the line fragment to fit it.
                //
                // The previous cap-height/x-height centring could not express
                // any of this: it pinned a fixed fraction of the image height
                // regardless of where the content's baseline actually was,
                // leaving a 0–4pt residual that varied with the formula
                // (≈+2.4pt for bare lowercase, ≈+4pt with descenders, ≈0 with
                // superscripts).
                // >0 rather than >=0: an exactly-zero descent is also how
                // "unknown" is spelled (the Unicode fallback), and a formula
                // whose real descent rounds to 0 is indistinguishable from the
                // baseline sitting at the image bottom — which is what the
                // y=0 branch below produces anyway for that case.
                if renderedBaselineFromBottom > 0 {
                    return CGRect(x: 0, y: -renderedBaselineFromBottom, width: imgW, height: imgH)
                }
                // Unknown baseline (Unicode fallback bitmap — drawn by UIKit,
                // not SwiftMath). Approximate: that path draws a single text
                // line with 1pt top padding, so its baseline sits one descender
                // plus that padding up from the bottom.
                let fallbackFont = UIFont.systemFont(ofSize: theme.baseFontSize)
                let approxBaseline = min(abs(fallbackFont.descender) + 1, imgH)
                return CGRect(x: 0, y: -approxBaseline, width: imgW, height: imgH)
            }
        }
        if renderFailed {
            let fallbackFont = UIFont.monospacedSystemFont(ofSize: theme.baseFontSize * 0.85, weight: .regular)
            if isBlock {
                let constraintSize = CGSize(width: lineFrag.width, height: .greatestFiniteMagnitude)
                let textHeight = (latex as NSString).boundingRect(with: constraintSize, options: [.usesLineFragmentOrigin], attributes: [.font: fallbackFont], context: nil).height
                return CGRect(x: 0, y: 0, width: lineFrag.width, height: max(ceil(textHeight), Self.blockPlaceholderHeight))
            } else {
                // [T-ios-math-inline-fallback-width] Measure the actual fallback
                // text width so the label doesn't overflow. The old hardcoded 40pt
                // caused "G(..." truncation artifacts when the LaTeX string was
                // wider than 40pt.
                let textSize = (latex as NSString).size(withAttributes: [.font: fallbackFont])
                let w = min(ceil(textSize.width) + 4, lineFrag.width)
                let h = max(ceil(textSize.height), Self.inlinePlaceholderHeight)
                // [issue #117-4] Baseline-align the raw-LaTeX label too. This
                // branch draws monospaced TEXT, so its baseline is one
                // descender up from the bottom of the drawn line; `h` may have
                // been floored up to the placeholder height, and that extra
                // padding lands below the text, so count it in.
                let lineHeight = ceil(textSize.height)
                let baselineFromBottom = abs(fallbackFont.descender) + max(0, h - lineHeight)
                return CGRect(x: 0, y: -baselineFromBottom, width: w, height: h)
            }
        }
        if isBlock {
            return CGRect(x: 0, y: 0, width: lineFrag.width, height: Self.blockPlaceholderHeight)
        }
        return CGRect(x: 0, y: 0, width: 40, height: Self.inlinePlaceholderHeight)
    }

    /// Enqueue this attachment for async rendering. Idempotent — subsequent
    /// calls are no-ops once `didAttemptRender` is set. The actual render work
    /// is batched by `MathRenderScheduler` so a document with hundreds of
    /// formulas doesn't block the main thread. See `performRender()` for the
    /// synchronous render body.
    func beginRenderingIfNeeded() {
        MathAttachment.beginCount &+= 1
        if MathAttachment.beginCount <= 3 || MathAttachment.beginCount % 50 == 0 {
            AppLogger(category: "MathSched").info("[MathSched] beginRenderingIfNeeded #\(MathAttachment.beginCount) rendered=\(renderedImage != nil) attempted=\(didAttemptRender) katex=\(isKaTeXPending) isBlock=\(isBlock) latex=\(latex.prefix(30))")
        }
        guard renderedImage == nil, !didAttemptRender, !isKaTeXPending else { return }
        didAttemptRender = true
        MathRenderScheduler.shared.enqueue(self)
    }

    nonisolated(unsafe) static var beginCount: Int = 0
    // [T-ios-math-sync-render-at-measure] diagnostics
    nonisolated(unsafe) static var syncRenderHits: Int = 0
    nonisolated(unsafe) static var offMainSkips: Int = 0

    /// Synchronous render body. Called by `MathRenderScheduler` on the main
    /// thread from inside a yielding batch loop — do not call directly.
    func performRender() {
        if let result = SwiftMathRenderer.render(latex: latex, displayMode: isBlock, fontSize: theme.baseFontSize) {
            applyResult(result)
        } else if let fallback = SwiftMathRenderer.renderFallback(latex: latex, displayMode: isBlock, fontSize: theme.baseFontSize) {
            applyResult(fallback)
        } else {
            renderFailed = true
        }
        onLoad?()
    }

    private func applyResult(_ result: SwiftMathRenderResult) {
        renderedImage = result.image
        renderedSize = result.size
        renderedBaselineFromBottom = result.baselineFromBottom
        if inlineDrawable {
            self.image = result.image
        }
    }

    /// When true, `performRender` writes the rendered image into
    /// `NSTextAttachment.image` so UILabel / UITextView draw the formula
    /// via TextKit. Main-flow attachments leave this false and rely on a
    /// dedicated UIImageView overlay built in `updateAttachmentViews`.
    var inlineDrawable: Bool = false

    /// [T-ios-math-invisible-dark-mode] Drop the rendered bitmap so the next
    /// pass re-renders it in the current interface style.
    ///
    /// The image bakes in a concrete text color (it must — see
    /// `SwiftMathRenderer.interfaceStyle`), which makes it style-specific.
    /// `SwiftMathRenderer`'s own NSCache is already keyed by style, but THIS
    /// object holds a rendered image directly, and the Theme picker only flips
    /// `window.overrideUserInterfaceStyle` (ContentView.swift:6948) — nothing
    /// re-parses the markdown. Without this reset, formulas already on screen
    /// when the user switches theme keep their old-color bitmap and go blank.
    ///
    /// Deliberately keeps `renderFailed`: a formula SwiftMath cannot parse
    /// fails identically in both styles, and re-attempting it on every theme
    /// flip would just re-run the parse to reach the same answer.
    func invalidateRenderForInterfaceStyleChange() {
        guard renderedImage != nil else { return }
        renderedImage = nil
        renderedSize = .zero
        renderedBaselineFromBottom = 0
        didAttemptRender = false
        didAttemptSyncRender = false
        if inlineDrawable { self.image = nil }
        needsViewRebuild = true
    }

    /// [T-ios-math-invisible-dark-mode] Set when the cached bitmap was dropped
    /// and the overlay view built from it must be discarded too.
    ///
    /// BLOCK formulas don't draw through TextKit — `updateAttachmentViews`
    /// builds a dedicated `UIImageView` overlay per attachment and reuses it
    /// while the attachment's object identity is unchanged. That reuse check
    /// asks "is there a rendered image but no image view?", which a
    /// just-invalidated attachment fails (its image is nil), so the overlay
    /// holding the OLD-color bitmap survived and display formulas stayed blank
    /// after a live theme switch even though inline ones re-rendered correctly.
    /// Mirrors `ImageAttachment.needsViewRebuild`, which exists for the same
    /// reason (a file rewritten under a stable attachment identity).
    var needsViewRebuild: Bool = false

    func makeView(width: CGFloat) -> UIView {
        // Attempt render if not yet done (e.g. attachment reappeared after recycle)
        if renderedImage == nil && !didAttemptRender && !isKaTeXPending {
            beginRenderingIfNeeded()
        }

        if let img = renderedImage {
            let imgW = img.size.width
            let imgH = img.size.height
            let imgView = UIImageView(image: img)
            imgView.contentMode = .scaleAspectFit
            if isBlock {
                let clampedW = min(imgW, width)
                let container = UIView(frame: CGRect(x: 0, y: 0, width: width, height: imgH))
                imgView.frame = CGRect(x: (width - clampedW) / 2, y: 0, width: clampedW, height: imgH)
                container.addSubview(imgView)
                return container
            } else {
                // Use actual image size — the passed-in width may be stale from
                // placeholder bounds if SwiftMath rendered synchronously after the
                // layout pass already computed attachment bounds with nil image.
                imgView.frame = CGRect(x: 0, y: 0, width: imgW, height: imgH)
                return imgView
            }
        }
        // Placeholder while KaTeX renders, or final fallback: raw LaTeX
        let label = UILabel()
        label.text = latex
        label.font = .monospacedSystemFont(ofSize: theme.baseFontSize * 0.85, weight: .regular)
        label.textColor = theme.secondaryLabelColor
        label.numberOfLines = 0
        if isBlock {
            label.textAlignment = .center
            let fittingSize = label.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
            let height = max(ceil(fittingSize.height), Self.blockPlaceholderHeight)
            label.frame = CGRect(x: 0, y: 0, width: width, height: height)
        } else {
            label.sizeToFit()
            label.frame = CGRect(x: 0, y: 0, width: min(label.frame.width, width), height: max(label.frame.height, Self.inlinePlaceholderHeight))
        }
        return label
    }
}

// MARK: - Image Attachment

final class ImageAttachment: NSTextAttachment {
    let source: String
    let theme: SelectableMarkdownTheme
    /// Assistant message this attachment belongs to, for resolving all sibling
    /// images in the same message when the user taps to preview. nil for
    /// contexts that aren't aware of a message (e.g. standalone previews).
    var messageId: UUID?
    /// Fingerprint (size+mtime) of the file that produced `loadedImage`.
    /// When the renderer reuses a cached attachment for the same source
    /// string, it compares this against the current on-disk fingerprint
    /// and clears `loadedImage` if they differ — so in-place rewrites of
    /// the underlying file get picked up on the next render.
    fileprivate var loadedFingerprint: String?
    /// Set by `invalidateLoadedImage` when the on-disk file changed, so
    /// `updateAttachmentViews` rebuilds the `UIImageView` instead of
    /// reusing the previous view (which still holds the stale bitmap in
    /// its `UIImageView.image` property).
    fileprivate var needsViewRebuild: Bool = false
    private(set) var loadedImage: UIImage?
    /// Resolved file URL of the loaded source (kept for preview/inspection).
    private var resolvedFileURL: URL?
    /// [T-ios-image-single-writer] Monotonic load generation. Every dispatch of
    /// an async load (and every invalidation) bumps it; an async completion
    /// whose captured generation no longer matches is STALE and must be
    /// dropped on the floor instead of writing `loadedImage`.
    ///
    /// This is what makes out-of-order completions impossible by construction.
    /// The predecessor design had up to three concurrent writers racing on
    /// `loadedImage` (initial load, cache adoption, and N bounds-time
    /// re-downsample tasks fired by width-bucket oscillation), guarded only by
    /// a marker that was written BEFORE the async work and never rolled back
    /// on failure — one silently-dropped task and the attachment was locked on
    /// a placeholder until the object itself was recreated (the "first open
    /// shows grey boxes, re-entering fixes it" family).
    private var loadGeneration: Int = 0
    private var isLoading = false
    /// Set when a minis:// file was not found; allows retry on next render.
    private var fileNotFound = false
    /// Number of file-not-found retries remaining (prevents infinite polling).
    private var retriesRemaining = 15
    /// Callback fired after image loads so the text view can refresh layout.
    var onLoad: (() -> Void)?

    /// [AttachHotPath] Per-instance counters: prove or refute that streaming
    /// re-runs layout / decode for the same image attachment. If these grow
    /// linearly with token count, the attachment is being thrashed.
    fileprivate var attachmentBoundsCallCount: Int = 0
    fileprivate var makeViewCallCount: Int = 0

    init(source: String, theme: SelectableMarkdownTheme, messageId: UUID? = nil) {
        self.source = source
        self.theme = theme
        self.messageId = messageId
        super.init(data: nil, ofType: nil)
        self.image = Self.transparentImage
        AppLogger(category: "AttachHotPath").info("[IMG][CTOR] src=\(Self.shortSrc(source)) ptr=\(ObjectIdentifier(self).hashValue & 0xFFFFFF)")
    }

    fileprivate static func shortSrc(_ s: String) -> String {
        let max = 60
        return s.count <= max ? s : "…" + String(s.suffix(max - 1))
    }

    /// Rewrite a markdown image source so relative paths resolve against
    /// the active session's workspace directory. Paths that already carry
    /// a URL scheme (`http`, `https`, `minis`, `file`, …) are returned
    /// unchanged. Bare filenames and relative paths (e.g. `foo.png`,
    /// `./foo.png`, `subdir/bar.jpg`) become `minis://workspace/<path>`
    /// with non-ASCII segments percent-encoded so `URL(string:)` parses
    /// them cleanly.
    static func canonicalizeMarkdownImageSource(_ src: String) -> String {
        // Fast path: if there's any URL scheme, bail out.
        if let colon = src.firstIndex(of: ":") {
            let maybeScheme = src[src.startIndex..<colon]
            // Only accept alphabetic-prefixed schemes (RFC 3986 § 3.1).
            if !maybeScheme.isEmpty && maybeScheme.allSatisfy({ $0.isLetter }) {
                // [T-minis-url-fullwidth-pipe-ios] Exception: a minis:// source the
                // model emitted with raw non-ASCII in the path (e.g. `｜`, CJK) that
                // URL(string:) can't parse. Re-encode the path so the image resolves
                // instead of bailing with a dead source. Idempotent for already-
                // encoded URLs (encodeRawMinisURL decodes-then-encodes per segment).
                if src.hasPrefix("minis://"), URL(string: src) == nil,
                   let fixed = encodeRawMinisURL(src) {
                    return fixed.absoluteString
                }
                return src
            }
        }
        // Strip leading `./` and any leading slashes.
        var trimmed = src
        while trimmed.hasPrefix("./") { trimmed.removeFirst(2) }
        while trimmed.hasPrefix("/") { trimmed.removeFirst() }
        // Percent-encode each path segment so multi-byte characters
        // (CJK, emoji, spaces) don't break URL parsing.
        let segments = trimmed.split(separator: "/", omittingEmptySubsequences: false)
        let encoded = segments.map { seg -> String in
            String(seg).addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? String(seg)
        }.joined(separator: "/")
        return "minis://workspace/\(encoded)"
    }

    /// Drop the currently-loaded bitmap so the next `beginLoadingIfNeeded`
    /// re-reads the source from disk. Called by the renderer when the
    /// on-disk fingerprint for this source changes between renders.
    func invalidateLoadedImage() {
        loadedImage = nil
        loadedFingerprint = nil
        resolvedFileURL = nil
        fileNotFound = false
        retriesRemaining = 15
        needsViewRebuild = true
        // [T-ios-image-single-writer] Invalidate any in-flight load: its
        // completion captured the old generation and will now be dropped,
        // so a stale bitmap can never overwrite the reload this invalidation
        // is about to trigger.
        loadGeneration &+= 1
        isLoading = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private static let transparentImage: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
    }()

    private static let placeholderHeight: CGFloat = 200
    /// Match MarkdownUI's maxWidth: 400 for images
    private static let maxImageWidth: CGFloat = 400
    /// [T-md-image-shadow-clip] Breathing room reserved on EVERY side of the
    /// rendered image inside its attachment box so the drop shadow
    /// (radius 4, y-offset 2) draws fully instead of being clipped by the
    /// text view's clipsToBounds at full-width images. attachmentBounds and
    /// makeImageView MUST use the same value or the cell height drifts.
    private static let imageShadowInset: CGFloat = 6

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        // Quantize the line-fragment width to a 4pt bucket. TextKit can call
        // attachmentBounds many times per layout pass with widths that differ
        // by sub-point floats (e.g. 329.83333 vs 330.0) as the container width
        // flows through cell self-sizing; quantizing keeps the returned box
        // geometry stable across those calls. Visually imperceptible.
        let rawWidth = lineFrag.width
        let width = (rawWidth / 4.0).rounded() * 4.0
        attachmentBoundsCallCount &+= 1
        // [AttachHotPath] Per-call log capped at first 5 + every 50 to avoid log
        // flooding while still proving cadence vs streaming token rate.
        let cnt = attachmentBoundsCallCount
        if cnt <= 5 || cnt % 50 == 0 {
            AppLogger(category: "AttachHotPath").info("[IMG][BOUNDS] #\(cnt) src=\(Self.shortSrc(self.source)) ptr=\(ObjectIdentifier(self).hashValue & 0xFFFFFF) rawW=\(String(format: "%.1f", rawWidth)) bucketW=\(String(format: "%.0f", width)) hasImg=\(self.loadedImage != nil)")
        }

        if let img = loadedImage {
            // [T-md-image-shadow-clip] Inset the displayed image by
            // shadowInset per side so the drop shadow renders INSIDE the
            // attachment box. Previously the image filled the full box width;
            // for full-width images the shadow spilled past the text view's
            // bounds (clipsToBounds=true) and was visibly cut off left/right.
            let boxWidth = min(min(width, img.size.width + Self.imageShadowInset * 2), Self.maxImageWidth)
            let imgWidth = max(boxWidth - Self.imageShadowInset * 2, 40)
            let aspect = img.size.height / max(img.size.width, 1)
            let maxH = UIScreen.main.bounds.height / 2
            let h = min(imgWidth * aspect, maxH)

            // [T-ios-image-single-writer] attachmentBounds is PURE now — it
            // measures and returns, nothing else. The bounds-time re-downsample
            // that used to live here (fire a detached decode task from inside a
            // TextKit measurement callback, replacing `loadedImage` when it
            // landed) was the root of the placeholder-stuck family: measurement
            // runs many times per layout pass at oscillating widths, so the
            // side effect fired repeatedly and concurrently, completions landed
            // out of order, and a silently-failed task left a
            // never-rolled-back marker that blocked all retries. Display-size
            // decoding now happens exactly once, in the load pipeline
            // (`displayTargetPixels`), where the aspect ratio is known and
            // there is a single completion path.

            // Return the attachment box as `imgWidth` (already clamped by
            // `maxImageWidth` and the image's own intrinsic width), NOT the
            // raw `width`. TextKit can pass `lineFrag.width = 10_000_000.0`
            // as an "unconstrained" probe during intermediate layout passes;
            // returning that value unchanged gave TextKit an attachment box
            // 10M points wide, which invalidates line-fragment geometry and
            // causes neighbouring passes (at the real container width) to
            // compute a different bounds, feeding a layout loop that can walk
            // past the 5s watchdog budget. [AttachHang fix]
            // Box = image + shadow breathing room on ALL sides (geometry must
            // match makeImageView's container exactly, or the cell height
            // drifts — the historical attachment-clip class of bugs).
            return CGRect(x: 0, y: 0, width: imgWidth + Self.imageShadowInset * 2,
                          height: h + Self.imageShadowInset * 2)
        }
        // No `loadedImage` yet — but if we've ever loaded this source before
        // we have its pixel size recorded globally. Use that to compute the
        // correct attachment height on the very first layout pass, instead of
        // returning the 200pt placeholder. Without this:
        //   1. First updateUIView creates a fresh ImageAttachment (cacheCount=0)
        //   2. SwiftUI sizeThatFits → attachmentBounds → placeholder 200pt
        //   3. Cell self-sizes to 276pt (placeholder + surrounding text)
        //   4. Async image load completes, attachmentBounds now returns 280pt
        //   5. invalidateCellSizeIfNeeded fires prev=271→new=358 delta=+87
        //   6. That late grow is sometimes lost (deferSelfSizing rejects it,
        //      SwiftUI's UIHostingConfiguration.systemLayoutSizeFitting still
        //      caches 276pt from step 3, etc.)
        //   7. Cell stays at 276pt; the UITextView inside grows to 358pt and
        //      gets clipped by the cell's clipsToBounds — image bottom cut
        //      off and the trailing prose after the image disappears.
        // [AttachHang preload-bounds fix]
        let canonicalSrc = Self.canonicalizeMarkdownImageSource(source)
        if let knownSize = NativeMediaImageCache.shared.size(forSource: canonicalSrc) {
            // [T-md-image-shadow-clip] Same inset geometry as the loaded path.
            let boxWidth = min(min(width, knownSize.width + Self.imageShadowInset * 2), Self.maxImageWidth)
            let imgWidth = max(boxWidth - Self.imageShadowInset * 2, 40)
            let aspect = knownSize.height / max(knownSize.width, 1)
            let maxH = UIScreen.main.bounds.height / 2
            let h = min(imgWidth * aspect, maxH)
            return CGRect(x: 0, y: 0, width: imgWidth + Self.imageShadowInset * 2,
                          height: h + Self.imageShadowInset * 2)
        }
        // First time we see this source — fall back to placeholder. Same
        // unconstrained-probe guard as the loaded path.
        let placeholderWidth = min(width, Self.maxImageWidth)
        return CGRect(x: 0, y: 0, width: placeholderWidth, height: Self.placeholderHeight)
    }

    /// [T-ios-image-squish-probe] Read pixel dimensions from the file HEADER
    /// only — CGImageSource property lookup decodes no pixel data, so this is
    /// millisecond-cheap even on multi-MB files. Orientation-aware: EXIF
    /// orientations 5–8 are 90°-rotated, so displayed width/height swap.
    private static func probePixelSize(at url: URL) -> CGSize? {
        let opts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithURL(url as CFURL, opts),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, opts) as? [CFString: Any],
              let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
              w > 0, h > 0 else { return nil }
        if let o = (props[kCGImagePropertyOrientation] as? NSNumber)?.uint32Value, (5...8).contains(o) {
            return CGSize(width: h, height: w)
        }
        return CGSize(width: w, height: h)
    }

    /// [T-ios-image-squish-probe] Record a header-probed size and kick the
    /// cell re-measure chain BEFORE the heavyweight decode runs. This is the
    /// fc6a5bd43 lesson applied to images: a never-seen portrait image (e.g.
    /// 400×800) used to sit on the fixed 200pt placeholder until the full
    /// read + ImageIO downsample finished, and that whole window measured the
    /// cell hundreds of points short — trailing text drew over the image.
    /// With the probe, attachmentBounds' recorded-size path returns the true
    /// aspect ratio on the very next layout pass. No-op when the size is
    /// already recorded (repeat renders, or the decode won the race).
    private static func probeAndPublishSize(at url: URL, canonicalSrc: String) {
        guard NativeMediaImageCache.shared.size(forSource: canonicalSrc) == nil else { return }
        guard let size = probePixelSize(at: url) else { return }
        NativeMediaImageCache.shared.recordSize(size, forSource: canonicalSrc)
        imgLogger.info("[MinisImage][Probe] header size=\(Int(size.width))x\(Int(size.height)) src=\(canonicalSrc) — publishing before full decode")
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .minisAttachmentSizeChanged, object: canonicalSrc)
        }
    }

    /// [T-ios-image-single-writer] The ONLY code path that writes
    /// `loadedImage`. Every adoption — cache hit, disk decode, HTTP fetch —
    /// funnels through here on the main thread, and the write is always paired
    /// with the refresh signal (`onLoad` + the size-changed notification).
    ///
    /// The pairing is the whole point. Two generations of placeholder-stuck
    /// bugs on this class were, at bottom, "state changed but no signal
    /// followed": first a load completion racing the render pass (the stale
    /// `loaded=false` window), then a bounds-time re-decode assigning the
    /// property bare with no refresh behind it. Making the bare assignment
    /// inexpressible closes the class of bug, not just the two instances.
    private func adoptLoaded(_ image: UIImage, fingerprint: String, canonicalSrc: String, via: String) {
        loadedImage = image
        loadedFingerprint = fingerprint
        isLoading = false
        fileNotFound = false
        imgLogger.info("[MinisImage][Adopt] via=\(via) src=\(canonicalSrc) size=\(Int(image.size.width))x\(Int(image.size.height))")
        onLoad?()
        // Adoption grows the cell: placeholder bounds → image bounds. The
        // cell must re-measure even on the synchronous cache-hit path.
        NotificationCenter.default.post(name: .minisAttachmentSizeChanged, object: canonicalSrc)
    }

    func beginLoadingIfNeeded() {
        // Canonicalize scheme-less / relative sources to minis://workspace/…
        // so the rest of the pipeline (cache key, resolve, fingerprint)
        // treats them uniformly. Agents writing plain markdown like
        // `![img](foo.png)` or `![img](subdir/foo.png)` land here.
        let canonicalSrc = Self.canonicalizeMarkdownImageSource(source)
        if canonicalSrc != source {
            imgLogger.info("[MinisImage][Load] canonicalized src=\(self.source) → \(canonicalSrc)")
        }

        // [T-ios-image-single-writer] Cache first, flags second — the shared
        // cache is the ground truth for "is this bitmap available";
        // `isLoading` is merely scheduling state. With the guard first (the
        // old order), a view that arrived while a load was in flight returned
        // early and never saw the cache, even after that load had populated
        // it: the exact window every "first open shows grey boxes, re-enter
        // fixes it" report landed in. Fingerprint-keyed so an in-place rewrite
        // of the same minis:// path misses and falls through to a fresh decode.
        let fpKey = minisMediaCacheKey(for: canonicalSrc)
        if loadedImage == nil, let cached = NativeMediaImageCache.shared.image(for: fpKey) {
            adoptLoaded(cached, fingerprint: fpKey, canonicalSrc: canonicalSrc, via: "memory-cache")
            return
        }

        guard loadedImage == nil, !isLoading else {
            imgLogger.info("[MinisImage][Load] skip src=\(self.source) alreadyLoaded=\(self.loadedImage != nil) isLoading=\(self.isLoading) — the in-flight completion or the next render's cache check will adopt")
            return
        }
        // Reset fileNotFound so we don't spin; it will be set again if still missing.
        fileNotFound = false
        guard retriesRemaining > 0 else {
            imgLogger.warning("[MinisImage][Load] no retries left src=\(self.source)")
            return
        }
        retriesRemaining -= 1
        isLoading = true
        // [T-ios-image-single-writer] Stamp this dispatch. The completion
        // re-checks the stamp on main; invalidateLoadedImage or a newer
        // dispatch bumps it, and the stale completion is dropped instead of
        // overwriting a newer state. Out-of-order completions become
        // impossible by construction rather than improbable by guard.
        loadGeneration &+= 1
        let gen = loadGeneration
        let parsedURL = URL(string: canonicalSrc)
        imgLogger.info("[MinisImage][Load] START gen=\(gen) src=\(canonicalSrc) scheme=\(parsedURL?.scheme ?? "nil") host=\(parsedURL?.host ?? "nil") path=\(parsedURL?.path ?? "nil") ext=\(parsedURL?.pathExtension ?? "nil") retriesRemaining=\(self.retriesRemaining)")

        let src = canonicalSrc
        let isMinisURL = URL(string: src).map { $0.scheme == "minis" } ?? false
        // [T-ios-failed-image-refetch-storm] For remote (non-minis) URLs that
        // failed recently, don't re-dispatch a network fetch on every cell
        // recycle — render the placeholder and bail. minis:// is exempt: those
        // are local files that may be written shortly after the markdown
        // references them, so the retriesRemaining-scheduled flow must stay.
        if !isMinisURL, NativeMediaImageCache.shared.isRecentlyFailed(src) {
            imgLogger.info("[MinisImage][Load] SUPPRESSED (recent failure within TTL) src=\(src)")
            isLoading = false
            fileNotFound = true
            return
        }
        imgLogger.info("[MinisImage][Load] dispatching async load src=\(src) isMinisURL=\(isMinisURL)")
        // [T-ios-image-single-writer] Captured on the caller's (main) thread;
        // `displayTargetPixels` needs it off-main where UIScreen is off-limits.
        let screenScale = UIScreen.main.scale
        Task.detached(priority: .userInitiated) {
            var fileURL: URL?
            let img: UIImage?
            if let url = URL(string: src), url.scheme == "minis" {
                imgLogger.info("[MinisImage][Load] resolving minis:// URL src=\(src) host=\(url.host ?? "nil") path=\(url.path)")
                if let resolved = resolveMinisFileURLForNativeText(url: url) {
                    imgLogger.info("[MinisImage][Load] minis:// resolved to localPath=\(resolved.path)")
                    fileURL = resolved
                    // [T-ios-image-squish-probe] Publish the true aspect ratio
                    // from the file header before paying for the full decode.
                    Self.probeAndPublishSize(at: resolved, canonicalSrc: src)
                    if let data = try? Data(contentsOf: resolved) {
                        imgLogger.info("[MinisImage][Load] read \(data.count) bytes from localPath=\(resolved.path)")
                        // [T-ios-image-single-writer] Decode straight to display
                        // size — the one and only decode this image gets.
                        let downsampled = downsampleImageData(data, maxPixelSize: displayTargetPixels(for: data, screenScale: screenScale))
                        if let downsampled {
                            imgLogger.info("[MinisImage][Load] downsample OK src=\(src) resultSize=\(downsampled.size.width)x\(downsampled.size.height)")
                        } else {
                            imgLogger.error("[MinisImage][Load] downsample FAILED src=\(src) dataSize=\(data.count)")
                        }
                        img = downsampled
                    } else {
                        imgLogger.error("[MinisImage][Load] Data(contentsOf:) FAILED localPath=\(resolved.path)")
                        img = nil
                    }
                } else {
                    imgLogger.warning("[MinisImage][Load] resolveMinisFileURLForNativeText returned nil src=\(src)")
                    img = nil
                }
            } else if let url = URL(string: src), url.scheme == "http" || url.scheme == "https" {
                imgLogger.info("[MinisImage][Load] fetching HTTP(S) url=\(src)")
                if let data = try? Data(contentsOf: url) {
                    imgLogger.info("[MinisImage][Load] HTTP fetched \(data.count) bytes src=\(src)")
                    img = downsampleImageData(data, maxPixelSize: displayTargetPixels(for: data, screenScale: screenScale))
                } else {
                    imgLogger.warning("[MinisImage][Load] HTTP fetch FAILED src=\(src)")
                    img = nil
                }
            } else if let url = URL(string: src) {
                // file URL or relative
                imgLogger.info("[MinisImage][Load] trying file/relative URL scheme=\(url.scheme ?? "nil") path=\(url.path)")
                fileURL = url
                // [T-ios-image-squish-probe] Same early header probe as the
                // minis:// branch. HTTP(S) is deliberately NOT probed — a
                // range-request header fetch isn't worth the complexity; the
                // full download records the size as before.
                Self.probeAndPublishSize(at: url, canonicalSrc: src)
                if let data = try? Data(contentsOf: url) {
                    imgLogger.info("[MinisImage][Load] file loaded \(data.count) bytes src=\(src)")
                    img = downsampleImageData(data, maxPixelSize: displayTargetPixels(for: data, screenScale: screenScale))
                } else {
                    imgLogger.warning("[MinisImage][Load] file load FAILED src=\(src)")
                    img = nil
                }
            } else {
                imgLogger.error("[MinisImage][Load] cannot parse URL src=\(src)")
                img = nil
            }

            // Recompute the fingerprint post-load in case the file was
            // rewritten between the pre-check at entry and this point.
            let postLoadKey = minisMediaCacheKey(for: src)
            if let img {
                imgLogger.info("[MinisImage][Load] SUCCESS src=\(src) finalSize=\(img.size.width)x\(img.size.height) — caching in memory")
                NativeMediaImageCache.shared.set(img, for: postLoadKey)
                // Also record the size (keyed by canonical source) so future
                // ImageAttachment instances can compute correct bounds before
                // the bitmap reload finishes. [AttachHang preload-bounds]
                NativeMediaImageCache.shared.recordSize(img.size, forSource: src)
                // [T-ios-failed-image-refetch-storm] A previously-failed URL
                // that now succeeded — clear its negative-cache entry.
                if !isMinisURL { NativeMediaImageCache.shared.clearFailure(src) }
            } else {
                imgLogger.warning("[MinisImage][Load] FAILED src=\(src) — no image produced")
                // [T-ios-failed-image-refetch-storm] Negative-cache remote
                // failures so cell recycling during scroll doesn't re-fetch the
                // same broken URL every recycle (the image-session decel storm).
                // minis:// is exempt — its not-yet-written-file case is handled
                // by the retriesRemaining-scheduled retry below.
                if !isMinisURL { NativeMediaImageCache.shared.recordFailure(src) }
            }

            await MainActor.run {
                // [T-ios-image-single-writer] Stale-completion gate. If the
                // generation moved on (invalidateLoadedImage ran, or a newer
                // dispatch superseded this one), this result belongs to a dead
                // request — drop it. The bitmap is already in the shared cache
                // above, so the live generation adopts it from there anyway;
                // nothing is lost, and nothing stale ever lands.
                guard gen == self.loadGeneration else {
                    imgLogger.info("[MinisImage][Load] DROP STALE completion gen=\(gen) current=\(self.loadGeneration) src=\(src)")
                    return
                }
                self.resolvedFileURL = fileURL
                if let img {
                    self.adoptLoaded(img, fingerprint: postLoadKey, canonicalSrc: src, via: "disk-load")
                } else {
                    self.isLoading = false
                    if isMinisURL && self.retriesRemaining > 0 {
                        imgLogger.info("[MinisImage][Load] RETRY scheduled src=\(src) retriesRemaining=\(self.retriesRemaining)")
                        self.fileNotFound = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                            guard let self, self.fileNotFound else { return }
                            self.beginLoadingIfNeeded()
                        }
                    } else {
                        if isMinisURL {
                            imgLogger.error("[MinisImage][Load] GAVE UP src=\(src) — all retries exhausted")
                        }
                        // Failure still signals: views waiting on this load
                        // must get a pass to render their file-not-found state
                        // instead of a placeholder that never resolves.
                        self.onLoad?()
                    }
                }
            }
        }
    }


    func makeView(width: CGFloat) -> UIView {
        makeViewCallCount &+= 1
        // [AttachHotPath] makeView call counter — the GROUND-TRUTH signal:
        // if this fires more than once per attachment per layout pass, the
        // UIImageView subview is being rebuilt and we have a leak in the
        // updateAttachmentViews reuse path.
        AppLogger(category: "AttachHotPath").info("[IMG][MAKEVIEW] #\(self.makeViewCallCount) src=\(Self.shortSrc(self.source)) ptr=\(ObjectIdentifier(self).hashValue & 0xFFFFFF) hasImg=\(self.loadedImage != nil) width=\(String(format: "%.0f", width))")
        if let img = loadedImage {
            imgLogger.info("[MinisImage][MakeView] rendering LOADED image src=\(self.source) imgSize=\(img.size.width)x\(img.size.height) containerWidth=\(width)")
            return makeImageView(img, width: width)
        } else {
            imgLogger.info("[MinisImage][MakeView] rendering PLACEHOLDER src=\(self.source) containerWidth=\(width) fileNotFound=\(self.fileNotFound) retriesRemaining=\(self.retriesRemaining)")
            return makePlaceholderView(width: width)
        }
    }

    private func makeImageView(_ img: UIImage, width: CGFloat) -> UIView {
        // [T-md-image-shadow-clip] Mirror attachmentBounds' inset geometry:
        // the image is inset by imageShadowInset per side inside the
        // attachment box so the shadow renders fully within the text view's
        // clip bounds (it used to be cut off left/right on full-width images).
        let boxWidth = min(min(width, img.size.width + Self.imageShadowInset * 2), Self.maxImageWidth)
        let imgWidth = max(boxWidth - Self.imageShadowInset * 2, 40)
        let aspect = img.size.height / max(img.size.width, 1)
        let maxH = UIScreen.main.bounds.height / 2
        let h = min(imgWidth * aspect, maxH)
        imgLogger.info("[MinisImage][MakeView] layout src=\(self.source) displayWidth=\(imgWidth) displayHeight=\(h) aspect=\(aspect) maxImageWidth=\(Self.maxImageWidth)")

        let shadowInset: CGFloat = Self.imageShadowInset
        // Container == the attachment box (image + shadow room on all sides).
        let container = UIView(frame: CGRect(x: 0, y: 0, width: imgWidth + shadowInset * 2, height: h + shadowInset * 2))
        container.backgroundColor = .clear
        container.clipsToBounds = false

        // Shadow wrapper — positioned at the inset so the shadow's spill stays
        // inside the container/attachment box.
        let shadowView = UIView(frame: CGRect(x: shadowInset, y: shadowInset, width: imgWidth, height: h))
        shadowView.backgroundColor = .clear
        shadowView.layer.shadowColor = UIColor.black.cgColor
        shadowView.layer.shadowOpacity = 0.15
        shadowView.layer.shadowRadius = 4
        shadowView.layer.shadowOffset = CGSize(width: 0, height: 2)
        shadowView.layer.shadowPath = UIBezierPath(roundedRect: CGRect(x: 0, y: 0, width: imgWidth, height: h), cornerRadius: 8).cgPath

        // Image view — clipped to rounded corners, black background for transparent/white images
        let imageView = UIImageView(image: img)
        imageView.contentMode = .scaleAspectFit
        imageView.backgroundColor = .black
        imageView.frame = shadowView.bounds
        imageView.layer.cornerRadius = 8
        imageView.clipsToBounds = true
        imageView.isUserInteractionEnabled = true

        let tap = ImageTapGesture(target: nil, action: nil)
        tap.attachedImage = img
        tap.sourceURL = source
        tap.messageId = messageId
        tap.addTarget(tap, action: #selector(ImageTapGesture.handleTap))
        imageView.addGestureRecognizer(tap)

        shadowView.addSubview(imageView)
        container.addSubview(shadowView)
        return container
    }

    private func makePlaceholderView(width: CGFloat) -> UIView {
        let h = Self.placeholderHeight
        let container = UIView(frame: CGRect(x: 0, y: 0, width: width, height: h))

        let bg = UIView(frame: CGRect(x: 0, y: 0, width: min(width, 400), height: h))
        bg.backgroundColor = .secondarySystemBackground
        bg.layer.cornerRadius = 8
        bg.clipsToBounds = true
        container.addSubview(bg)

        let iconConfig = UIImage.SymbolConfiguration(pointSize: 32, weight: .light)
        let icon = UIImageView(image: UIImage(systemName: "photo", withConfiguration: iconConfig))
        icon.tintColor = .tertiaryLabel
        icon.sizeToFit()
        icon.center = CGPoint(x: bg.bounds.midX, y: bg.bounds.midY - 12)
        bg.addSubview(icon)

        let filename = source.components(separatedBy: "/").last ?? source
        let label = UILabel()
        label.text = filename
        label.font = .systemFont(ofSize: 12)
        label.textColor = .secondaryLabel
        label.textAlignment = .center
        label.frame = CGRect(x: 8, y: icon.frame.maxY + 8, width: bg.bounds.width - 16, height: 16)
        bg.addSubview(label)

        return container
    }
}

/// Tap gesture recognizer that routes a markdown-embedded image tap up to
/// the SwiftUI layer so it can present a paged gallery of **all** inline
/// images in the same assistant message.
///
/// When `messageId` is set, we post a `MarkdownImageTapRouter.tappedNotification`
/// and let `AIChatView` resolve the message → full gallery item list.
/// When `messageId` is nil (standalone preview, e.g. a markdown view outside
/// a chat message), we fall back to the legacy single-image fullscreen
/// preview so nothing breaks.
private final class ImageTapGesture: UITapGestureRecognizer {
    var attachedImage: UIImage?
    var sourceURL: String?
    var messageId: UUID?

    @objc func handleTap() {
        // Preferred path: route up to SwiftUI so a paged gallery can be shown.
        if let messageId, let sourceURL {
            MarkdownImageTapRouter.postTap(messageId: messageId, sourceURL: sourceURL)
            return
        }
        // Fallback: no message context — present a single-image fullscreen
        // preview directly, same as before.
        guard let image = attachedImage else { return }
        guard let presenter = self.view?.nearestViewController() else { return }
        let scrollView = self.view?.enclosingScrollView()
        let savedOffset = scrollView?.contentOffset
        let preview = ScrollRestoringDismissWrapper(scrollView: scrollView, savedOffset: savedOffset) {
            ImagePreviewView(image: image)
        }
        let hosting = UIHostingController(rootView: preview)
        hosting.modalPresentationStyle = .overFullScreen
        hosting.modalTransitionStyle = .crossDissolve
        presenter.present(hosting, animated: true)
    }
}

/// Wraps a fullscreen preview view and restores scroll position on disappear.
private struct ScrollRestoringDismissWrapper<Content: View>: View {
    private weak var scrollView: UIScrollView?
    private let savedOffset: CGPoint?
    private let content: Content

    init(scrollView: UIScrollView?, savedOffset: CGPoint?, @ViewBuilder content: () -> Content) {
        self.scrollView = scrollView
        self.savedOffset = savedOffset
        self.content = content()
    }

    var body: some View {
        content
            .onDisappear {
                if let scrollView, let savedOffset {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                        scrollView.setContentOffset(savedOffset, animated: false)
                    }
                }
            }
    }
}

// MARK: - Video Attachment

final class VideoAttachment: NSTextAttachment {
    let source: String
    let theme: SelectableMarkdownTheme
    private(set) var thumbnail: UIImage?
    private var isLoading = false
    private var resolvedURL: URL?
    var onLoad: (() -> Void)?

    init(source: String, theme: SelectableMarkdownTheme) {
        self.source = source
        self.theme = theme
        super.init(data: nil, ofType: nil)
        self.image = Self.transparentImage
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private static let transparentImage: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
    }()

    private static let placeholderHeight: CGFloat = 200

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        let width = min(lineFrag.width, 400)
        if let thumb = thumbnail {
            let aspect = thumb.size.height / max(thumb.size.width, 1)
            let h = min(width * aspect, UIScreen.main.bounds.height / 2)
            // +24 for filename label below
            return CGRect(x: 0, y: 0, width: lineFrag.width, height: h + 24)
        }
        // [T-ios-video-squish] No thumbnail yet — use the recorded display
        // size (track-metadata probe or a previous run's thumbnail) so a
        // portrait video's placeholder already has the right aspect ratio
        // instead of the fixed 200pt box. Mirrors ImageAttachment's
        // recorded-size fallback (8f9ffc437).
        if let known = NativeMediaImageCache.shared.size(forSource: source),
           known.width > 0 {
            let aspect = known.height / known.width
            let h = min(width * aspect, UIScreen.main.bounds.height / 2)
            return CGRect(x: 0, y: 0, width: lineFrag.width, height: h + 24)
        }
        return CGRect(x: 0, y: 0, width: lineFrag.width, height: Self.placeholderHeight)
    }

    func beginLoadingIfNeeded() {
        guard thumbnail == nil, !isLoading else { return }
        isLoading = true

        let src = source
        Task.detached(priority: .userInitiated) {
            var fileURL: URL?
            if let url = URL(string: src), url.scheme == "minis" {
                fileURL = resolveMinisFileURLForNativeText(url: url)
            } else if let url = URL(string: src) {
                fileURL = url
            }

            guard let fileURL else {
                await MainActor.run { self.isLoading = false }
                return
            }

            // Check thumbnail cache
            let cacheKey = fileURL.path
            if let cached = NativeMediaImageCache.shared.image(for: "thumb:" + cacheKey) {
                await MainActor.run {
                    self.thumbnail = cached
                    self.resolvedURL = fileURL
                    self.isLoading = false
                    self.onLoad?()
                    // Cell-side height re-measure: attachmentBounds now returns
                    // aspect-fit height instead of the 200pt placeholder, so
                    // the surrounding cell must invalidate its cached height.
                    // Without this, the cell stays at the placeholder height
                    // until some unrelated event (scroll, reconfigure) forces
                    // a re-measure (the "appears half-rendered, then snaps
                    // 10s later" bug).
                    NotificationCenter.default.post(name: .minisAttachmentSizeChanged, object: src)
                }
                return
            }

            let asset = AVAsset(url: fileURL)

            // [T-ios-video-squish] Probe the video track's display size BEFORE
            // generating the thumbnail. Track metadata (naturalSize +
            // preferredTransform) is read from the container header — far
            // lighter than AVAssetImageGenerator, which spins up a decoder for
            // a real frame. Recording it now lets attachmentBounds return the
            // true aspect ratio while the (slow) thumbnail is still cooking —
            // the 8f9ffc437 header-probe idea applied to video. Orientation is
            // handled by applying preferredTransform (portrait phone captures
            // store a landscape naturalSize + 90° transform).
            if NativeMediaImageCache.shared.size(forSource: src) == nil,
               let track = try? await asset.loadTracks(withMediaType: .video).first,
               let (natural, transform) = try? await track.load(.naturalSize, .preferredTransform) {
                let rect = CGRect(origin: .zero, size: natural).applying(transform)
                let displaySize = CGSize(width: abs(rect.width), height: abs(rect.height))
                if displaySize.width > 0, displaySize.height > 0 {
                    NativeMediaImageCache.shared.recordSize(displaySize, forSource: src)
                    imgLogger.info("[MinisVideo][Probe] track size=\(Int(displaySize.width))x\(Int(displaySize.height)) src=\(src) — publishing before thumbnail generation")
                    await MainActor.run {
                        NotificationCenter.default.post(name: .minisAttachmentSizeChanged, object: src)
                    }
                }
            }

            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 400, height: 400)

            var thumb: UIImage?
            do {
                let (cgImage, _) = try await generator.image(at: .zero)
                thumb = UIImage(cgImage: cgImage)
            } catch {
                // Fallback: no thumbnail
            }

            if let thumb {
                NativeMediaImageCache.shared.set(thumb, for: "thumb:" + cacheKey)
                // Durable size record (the in-memory thumb cache dies on cold
                // start; the recorded size persists in UserDefaults). Also
                // covers assets whose track probe failed.
                NativeMediaImageCache.shared.recordSize(thumb.size, forSource: src)
            }

            await MainActor.run {
                self.thumbnail = thumb
                self.resolvedURL = fileURL
                self.isLoading = false
                self.onLoad?()
                if thumb != nil {
                    NotificationCenter.default.post(name: .minisAttachmentSizeChanged, object: src)
                }
            }
        }
    }

    func makeView(width: CGFloat) -> UIView {
        let maxW = min(width, 400)
        if let thumb = thumbnail {
            return makeThumbnailView(thumb, maxWidth: maxW, totalWidth: width)
        } else {
            return makePlaceholderView(maxWidth: maxW, totalWidth: width)
        }
    }

    private func makeThumbnailView(_ thumb: UIImage, maxWidth: CGFloat, totalWidth: CGFloat) -> UIView {
        let aspect = thumb.size.height / max(thumb.size.width, 1)
        let h = min(maxWidth * aspect, UIScreen.main.bounds.height / 2)

        let container = UIView(frame: CGRect(x: 0, y: 0, width: totalWidth, height: h + 24))
        container.backgroundColor = .clear

        let thumbView = UIImageView(image: thumb)
        thumbView.contentMode = .scaleAspectFill
        thumbView.frame = CGRect(x: 0, y: 0, width: maxWidth, height: h)
        thumbView.layer.cornerRadius = 8
        thumbView.clipsToBounds = true
        container.addSubview(thumbView)

        // Dark overlay
        let overlay = UIView(frame: thumbView.frame)
        overlay.backgroundColor = UIColor.black.withAlphaComponent(0.3)
        overlay.layer.cornerRadius = 8
        overlay.clipsToBounds = true
        container.addSubview(overlay)

        // Play icon
        let iconConfig = UIImage.SymbolConfiguration(pointSize: 44, weight: .regular)
        let playIcon = UIImageView(image: UIImage(systemName: "play.circle.fill", withConfiguration: iconConfig))
        playIcon.tintColor = .white.withAlphaComponent(0.9)
        playIcon.sizeToFit()
        playIcon.center = CGPoint(x: maxWidth / 2, y: h / 2)
        container.addSubview(playIcon)

        // Filename label with film icon
        let filename = source.components(separatedBy: "/").last ?? source
        let filmIconCfg = UIImage.SymbolConfiguration(pointSize: 10, weight: .regular)
        let filmIcon = UIImageView(image: UIImage(systemName: "film", withConfiguration: filmIconCfg))
        filmIcon.tintColor = .secondaryLabel
        filmIcon.frame = CGRect(x: 0, y: h + 6, width: 14, height: 12)
        container.addSubview(filmIcon)

        let label = UILabel()
        label.text = filename
        label.font = .systemFont(ofSize: 12)
        label.textColor = .secondaryLabel
        label.lineBreakMode = .byTruncatingMiddle
        label.frame = CGRect(x: 18, y: h + 4, width: maxWidth - 18, height: 16)
        container.addSubview(label)

        // Tap to play
        container.isUserInteractionEnabled = true
        let tap = VideoTapGesture(target: nil, action: nil)
        tap.fileURL = resolvedURL
        tap.addTarget(tap, action: #selector(VideoTapGesture.handleTap))
        container.addGestureRecognizer(tap)

        return container
    }

    private func makePlaceholderView(maxWidth: CGFloat, totalWidth: CGFloat) -> UIView {
        let h = Self.placeholderHeight
        let container = UIView(frame: CGRect(x: 0, y: 0, width: totalWidth, height: h))

        let bg = UIView(frame: CGRect(x: 0, y: 0, width: maxWidth, height: h))
        bg.backgroundColor = .secondarySystemBackground
        bg.layer.cornerRadius = 8
        bg.clipsToBounds = true
        container.addSubview(bg)

        let iconConfig = UIImage.SymbolConfiguration(pointSize: 44, weight: .regular)
        let icon = UIImageView(image: UIImage(systemName: "play.circle.fill", withConfiguration: iconConfig))
        icon.tintColor = .tertiaryLabel
        icon.sizeToFit()
        icon.center = CGPoint(x: bg.bounds.midX, y: bg.bounds.midY - 12)
        bg.addSubview(icon)

        let filename = source.components(separatedBy: "/").last ?? source
        let label = UILabel()
        label.text = filename
        label.font = .systemFont(ofSize: 12)
        label.textColor = .secondaryLabel
        label.textAlignment = .center
        label.frame = CGRect(x: 8, y: icon.frame.maxY + 8, width: bg.bounds.width - 16, height: 16)
        bg.addSubview(label)

        return container
    }
}

private final class VideoTapGesture: UITapGestureRecognizer {
    var fileURL: URL?

    @objc func handleTap() {
        guard let url = fileURL else { return }
        guard let presenter = self.view?.nearestViewController() else { return }
        // Save scroll position to restore after dismiss.
        let scrollView = self.view?.enclosingScrollView()
        let savedOffset = scrollView?.contentOffset
        // Reuse the shared MinisVideoFullscreenPlayer from AIChatView.
        let playerView = ScrollRestoringDismissWrapper(scrollView: scrollView, savedOffset: savedOffset) {
            MinisVideoFullscreenPlayer(fileURL: url)
        }
        let hosting = UIHostingController(rootView: playerView)
        hosting.modalPresentationStyle = .overFullScreen
        hosting.modalTransitionStyle = .crossDissolve
        presenter.present(hosting, animated: true)
    }
}

// MARK: - Audio Attachment

final class AudioAttachment: NSTextAttachment {
    let source: String
    let theme: SelectableMarkdownTheme
    private var resolvedURL: URL?
    private var isResolved = false

    static let attachmentHeight: CGFloat = 70

    init(source: String, theme: SelectableMarkdownTheme) {
        self.source = source
        self.theme = theme
        super.init(data: nil, ofType: nil)
        self.image = Self.transparentImage
        resolveURL()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    private static let transparentImage: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { _ in }
    }()

    private func resolveURL() {
        // Strip query parameters for file URL resolution (they are metadata, not part of the path)
        let cleanSource: String
        if let url = URL(string: source), var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.queryItems = nil
            cleanSource = components.url?.absoluteString ?? source
        } else {
            cleanSource = source
        }
        if let url = URL(string: cleanSource), url.scheme == "minis" {
            resolvedURL = resolveMinisFileURLForNativeText(url: url)
        } else if let url = URL(string: cleanSource) {
            resolvedURL = url
        }
        isResolved = true
    }

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        let width = lineFrag.width
        return CGRect(x: 0, y: 0, width: width, height: Self.attachmentHeight)
    }

    func makeView(width: CGFloat) -> UIView {
        let maxW = width
        let h = Self.attachmentHeight

        let container = UIView(frame: CGRect(x: 0, y: 0, width: width, height: h))
        container.backgroundColor = .clear

        let bg = UIView(frame: CGRect(x: 0, y: 0, width: maxW, height: h))
        bg.backgroundColor = .secondarySystemBackground
        bg.layer.cornerRadius = 10
        bg.clipsToBounds = true
        container.addSubview(bg)

        let rawFilename = source.components(separatedBy: "/").last ?? source
        let filename = rawFilename.components(separatedBy: "?").first ?? rawFilename

        // Waveform icon + filename
        let waveIcon = UIImageView(image: UIImage(systemName: "waveform"))
        waveIcon.tintColor = .secondaryLabel
        waveIcon.frame = CGRect(x: 10, y: 8, width: 14, height: 14)
        bg.addSubview(waveIcon)

        let nameLabel = UILabel()
        nameLabel.text = filename
        nameLabel.font = .systemFont(ofSize: 12)
        nameLabel.textColor = .secondaryLabel
        nameLabel.lineBreakMode = .byTruncatingMiddle
        nameLabel.frame = CGRect(x: 28, y: 8, width: maxW - 62, height: 16)
        bg.addSubview(nameLabel)

        // Play button
        let iconConfig = UIImage.SymbolConfiguration(pointSize: 22, weight: .regular)
        let playBtn = UIButton(type: .system)
        playBtn.setImage(UIImage(systemName: "play.circle.fill", withConfiguration: iconConfig), for: .normal)
        playBtn.tintColor = .systemOrange
        playBtn.frame = CGRect(x: 10, y: 30, width: 30, height: 30)
        bg.addSubview(playBtn)

        // Slider
        let slider = UISlider()
        slider.minimumTrackTintColor = .systemOrange
        slider.frame = CGRect(x: 46, y: 34, width: maxW - 100, height: 22)
        slider.value = 0
        bg.addSubview(slider)

        // Duration label
        let durLabel = UILabel()
        durLabel.text = "--:--"
        durLabel.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        durLabel.textColor = .secondaryLabel
        durLabel.textAlignment = .right
        durLabel.frame = CGRect(x: maxW - 50, y: 36, width: 40, height: 16)
        bg.addSubview(durLabel)

        // Expand button (top-right)
        let expandBtn = UIButton(type: .system)
        let expandConfig = UIImage.SymbolConfiguration(pointSize: 11, weight: .medium)
        expandBtn.setImage(UIImage(systemName: "arrow.up.left.and.arrow.down.right", withConfiguration: expandConfig), for: .normal)
        expandBtn.tintColor = .secondaryLabel
        expandBtn.frame = CGRect(x: maxW - 28, y: 6, width: 22, height: 22)
        bg.addSubview(expandBtn)

        // Wire up audio playback controller
        let controller = InlineAudioController(fileURL: resolvedURL, playButton: playBtn, slider: slider, durationLabel: durLabel)
        // Keep controller alive by attaching to the view
        objc_setAssociatedObject(bg, &InlineAudioController.associatedKey, controller, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)

        // Expand button action
        let expandTap = AudioTapGesture(target: nil, action: nil)
        expandTap.fileURL = resolvedURL
        expandTap.addTarget(expandTap, action: #selector(AudioTapGesture.handleTap))
        expandBtn.addTarget(expandTap, action: #selector(AudioTapGesture.handleTap), for: .touchUpInside)
        // Keep expandTap alive
        objc_setAssociatedObject(expandBtn, &AudioTapGesture.expandTapKey, expandTap, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)

        // Tap on filename area opens fullscreen audio preview
        let tapTarget = UIView(frame: CGRect(x: 0, y: 0, width: maxW, height: 26))
        tapTarget.backgroundColor = .clear
        tapTarget.isUserInteractionEnabled = true
        bg.addSubview(tapTarget)
        let audioTap = AudioTapGesture(target: nil, action: nil)
        audioTap.fileURL = resolvedURL
        audioTap.addTarget(audioTap, action: #selector(AudioTapGesture.handleTap))
        tapTarget.addGestureRecognizer(audioTap)

        return container
    }
}

/// Tap gesture to open audio preview sheet.
private final class AudioTapGesture: UITapGestureRecognizer {
    static var expandTapKey: UInt8 = 0
    var fileURL: URL?

    @objc func handleTap() {
        guard let url = fileURL else { return }
        guard let presenter = self.view?.nearestViewController() else { return }
        // Playback continues seamlessly — global player is shared with preview
        let preview = MinisAudioPreviewView(fileURL: url)
        let hosting = UIHostingController(rootView: preview)
        if let sheet = hosting.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = false
        }
        presenter.present(hosting, animated: true)
    }
}

/// Manages inline audio UI by delegating to GlobalAudioPlayer singleton.
private final class InlineAudioController: NSObject {
    static var associatedKey: UInt8 = 0

    private let fileURL: URL?
    private weak var playButton: UIButton?
    private weak var slider: UISlider?
    private weak var durationLabel: UILabel?
    private var cancellables = Set<AnyCancellable>()

    init(fileURL: URL?, playButton: UIButton, slider: UISlider, durationLabel: UILabel) {
        self.fileURL = fileURL
        self.playButton = playButton
        self.slider = slider
        self.durationLabel = durationLabel
        super.init()

        playButton.addTarget(self, action: #selector(togglePlayPause), for: .touchUpInside)
        slider.addTarget(self, action: #selector(sliderChanged), for: .valueChanged)

        // Pre-load duration for display
        // Note: auto_play is NOT triggered here because InlineAudioController is
        // re-created every time the view renders (including when re-entering a
        // conversation). Auto-play on LLM response is handled by
        // AIChatViewModel.triggerAutoPlayAudio() which only fires once at the end
        // of streaming.
        if let fileURL {
            Task.detached(priority: .userInitiated) {
                guard let p = try? AVAudioPlayer(contentsOf: fileURL) else { return }
                p.prepareToPlay()
                let dur = p.duration
                await MainActor.run {
                    slider.maximumValue = Float(dur)
                    durationLabel.text = Self.formatTime(dur)
                }
            }
        }

        // Subscribe to global player state on the main actor
        Task { @MainActor [weak self] in
            guard let self else { return }
            let gp = GlobalAudioPlayer.shared
            let iconConfig = UIImage.SymbolConfiguration(pointSize: 22, weight: .regular)

            gp.$isPlaying.combineLatest(gp.$activeFileURL)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] playing, activeURL in
                    guard let self, let url = self.fileURL else { return }
                    let isActive = activeURL == url
                    let icon = isActive && playing ? "pause.circle.fill" : "play.circle.fill"
                    self.playButton?.setImage(UIImage(systemName: icon, withConfiguration: iconConfig), for: .normal)
                }
                .store(in: &self.cancellables)

            gp.$currentTime.combineLatest(gp.$activeFileURL)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] time, activeURL in
                    guard let self, let url = self.fileURL else { return }
                    if activeURL == url {
                        self.slider?.value = Float(time)
                    }
                }
                .store(in: &self.cancellables)

            gp.$duration.combineLatest(gp.$activeFileURL)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] dur, activeURL in
                    guard let self, let url = self.fileURL else { return }
                    if activeURL == url {
                        self.slider?.maximumValue = Float(dur)
                        self.durationLabel?.text = Self.formatTime(dur)
                    }
                }
                .store(in: &self.cancellables)
        }
    }

    @MainActor @objc private func togglePlayPause() {
        guard let url = fileURL else { return }
        let gp = GlobalAudioPlayer.shared
        if gp.isActive(url: url) {
            gp.togglePlayPause()
        } else {
            gp.play(url: url)
        }
    }

    @MainActor @objc private func sliderChanged() {
        guard let url = fileURL, let slider else { return }
        let gp = GlobalAudioPlayer.shared
        if gp.isActive(url: url) {
            gp.seek(to: TimeInterval(slider.value))
        }
    }

    static func formatTime(_ t: TimeInterval) -> String {
        let m = Int(t) / 60
        let s = Int(t) % 60
        return String(format: "%d:%02d", m, s)
    }
}

// MARK: - InlineNode Plain Text Helper

private extension Array where Element == InlineNode {
    var plainText: String {
        map { node -> String in
            switch node {
            case .text(let t): return t
            case .code(let c): return c
            case .emphasis(let ch), .strong(let ch), .strikethrough(let ch): return ch.plainText
            case .link(_, let ch), .image(_, let ch): return ch.plainText
            case .softBreak: return " "
            case .lineBreak: return "\n"
            case .html(let h): return h
            case .inlineMath(let latex): return latex
            }
        }.joined()
    }
}

// MARK: - MinisLayoutManager

/// Custom NSLayoutManager subclass. Originally added for inline-code rounded
/// background rendering (see `fillBackgroundRectArray` below). Kept as a
/// dedicated class so other layout-manager hooks can be added in one place.
final class MinisLayoutManager: NSLayoutManager {

    /// [T-ios-inline-code-quote-band] Breathing room painted past the last
    /// visible glyph on a wrapped line. Mirrors the ~0.5-1pt of slack the
    /// unclamped rect carries on a tight single-line span, so a wrapped line's
    /// pill doesn't look clipped against its final character.
    private static let inlineCodeTrailingInset: CGFloat = 2

    override func fillBackgroundRectArray(_ rectArray: UnsafePointer<CGRect>, count rectCount: Int, forCharacterRange charRange: NSRange, color: UIColor) {
        // Check if this range has inline code background
        guard let textStorage = self.textStorage else {
            super.fillBackgroundRectArray(rectArray, count: rectCount, forCharacterRange: charRange, color: color)
            return
        }

        var hasInlineCode = false
        if charRange.location + charRange.length <= textStorage.length {
            textStorage.enumerateAttribute(.inlineCodeBackground, in: charRange, options: []) { value, _, _ in
                if value != nil { hasInlineCode = true }
            }
        }

        guard hasInlineCode, let context = UIGraphicsGetCurrentContext() else {
            super.fillBackgroundRectArray(rectArray, count: rectCount, forCharacterRange: charRange, color: color)
            return
        }

        // [T-inline-code-dark-bg-ios] Same dynamic color as the theme; resolve
        // against the current traits explicitly so the CGColor is correct even
        // if UITraitCollection.current isn't the drawing view's during this
        // TextKit callback.
        let bgColor = minisInlineCodeBackgroundColor.resolvedColor(with: UITraitCollection.current)
        context.saveGState()
        context.setFillColor(bgColor.cgColor)

        let glyphRange = self.glyphRange(forCharacterRange: charRange, actualCharacterRange: nil)

        // Count how many line fragments the code span covers (single vs multi-line)
        var lineFragCount = 0
        enumerateLineFragments(forGlyphRange: glyphRange) { _, _, _, _, _ in
            lineFragCount += 1
        }

        for i in 0..<rectCount {
            var rawRect = rectArray[i]
            guard rawRect.width > 2 else { continue }

            // Check if the code span's text on this line has visible (non-whitespace)
            // characters. When word wrap pushes the code span to the next line, the
            // previous line may retain only a leading space with .backgroundColor,
            // producing an empty background rect.
            var hasVisibleText = false
            // correctedOriginY / correctedHeight: the closure captures rawRect
            // by value, so we write back corrections through these vars.
            var correctedOriginY: CGFloat = rawRect.minY
            var correctedHeight: CGFloat = rawRect.height
            // [T-ios-inline-code-quote-band] Trailing edge of the last VISIBLE
            // glyph on this line. See the clamp below for why rawRect's own
            // width can't be trusted on a wrapped line.
            var visibleMaxX: CGFloat = .nan
            enumerateLineFragments(forGlyphRange: glyphRange) { lineFragRect, usedRect, _, lineGlyphRange, _ in
                guard lineFragRect.midY >= rawRect.minY && lineFragRect.midY <= rawRect.maxY else { return }
                let overlapStart = max(glyphRange.location, lineGlyphRange.location)
                let overlapEnd = min(NSMaxRange(glyphRange), NSMaxRange(lineGlyphRange))
                guard overlapEnd > overlapStart else { return }
                let overlapCharRange = self.characterRange(forGlyphRange: NSRange(location: overlapStart, length: overlapEnd - overlapStart), actualGlyphRange: nil)
                guard overlapCharRange.length > 0 else { return }
                let overlapText = (textStorage.string as NSString).substring(with: overlapCharRange)
                if !overlapText.trimmingCharacters(in: .whitespaces).isEmpty {
                    hasVisibleText = true

                    // [T-ios-inline-code-quote-band] Measure out to the last
                    // non-whitespace character of this line's slice. A wrapped
                    // line keeps the space it broke on inside its used rect, so
                    // the rect TextKit hands us reaches past the final glyph —
                    // and in a blockquote (headIndent set, no tailIndent) that
                    // is the container's full width, which is the right-hand
                    // band in the report. The last line has no break space and
                    // is already tight, which is why only the earlier lines
                    // looked wrong.
                    let trailingWS = overlapText.count - overlapText
                        .replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression).count
                    let visibleCharRange = NSRange(location: overlapCharRange.location,
                                                   length: max(0, overlapCharRange.length - trailingWS))
                    if visibleCharRange.length > 0 {
                        let visibleGlyphRange = self.glyphRange(forCharacterRange: visibleCharRange,
                                                                actualCharacterRange: nil)
                        // Bound by the text container so a trailing glyph that
                        // TextKit lays out past the edge can't re-widen the pill.
                        if let tc = self.textContainer(forGlyphAt: visibleGlyphRange.location,
                                                       effectiveRange: nil) {
                            let box = self.boundingRect(forGlyphRange: visibleGlyphRange, in: tc)
                            visibleMaxX = visibleMaxX.isNaN ? box.maxX : max(visibleMaxX, box.maxX)
                        }
                    }

                    // Clamp rect to usedRect (excludes lineSpacing) so the
                    // inline-code background aligns with the text, not the
                    // bloated lineFragRect that includes paragraph spacing.
                    if rawRect.height > usedRect.height + 2 {
                        correctedOriginY = usedRect.minY
                        correctedHeight = usedRect.height
                    }
                    // Fix last-line height issue: TextKit may pass a shorter rect
                    // for the final line. Only correct for single-line spans to
                    // avoid mis-firing on multi-line wrapped code.
                    if lineFragCount == 1 && rawRect.height < usedRect.height * 0.8 {
                        correctedOriginY = usedRect.minY
                        correctedHeight = usedRect.height
                    }
                }
            }
            guard hasVisibleText else { continue }

            // Apply corrected origin and height from the closure above.
            rawRect = CGRect(x: rawRect.minX, y: correctedOriginY, width: rawRect.width, height: correctedHeight)

            // [T-ios-inline-code-quote-band] Shrink (never grow) to the visible
            // glyph extent. Clamping only downward keeps this inert for spans
            // TextKit already measured tightly — single-line code, the final
            // line of a wrapped span — so short inline code and body text are
            // untouched, and the long-path wrap fix (T-ios-inline-code-long-
            // path-wrap) still decides WHERE the break happens; this only
            // decides how far the pill is painted afterwards.
            //
            // A small trailing inset keeps the pill from sitting flush against
            // its last glyph. The `padded < rawRect.maxX` test is what makes
            // this shrink-only: a line TextKit already measured tightly (a
            // single-line span, or the last line of a wrapped one, where
            // visibleMaxX == rawRect.maxX) keeps its original rect untouched.
            if !visibleMaxX.isNaN, visibleMaxX > rawRect.minX {
                let padded = visibleMaxX + Self.inlineCodeTrailingInset
                if padded < rawRect.maxX {
                    rawRect.size.width = padded - rawRect.minX
                }
            }

            // Vertically center the background within the line.
            // rawRect height reflects the full usedRect (dominated by the tallest
            // font on the line). The inline code font is shorter, so we compute
            // the background height from the actual glyph bounding box and center
            // it within rawRect to avoid the "floats to top" misalignment.
            let codeFont = textStorage.attribute(.font, at: charRange.location, effectiveRange: nil) as? UIFont
            let fontLineHeight = codeFont?.lineHeight ?? rawRect.height
            let bgPaddingV: CGFloat = 2   // top + bottom padding inside the pill
            let bgHeight = min(fontLineHeight + bgPaddingV * 2, rawRect.height)
            let bgY = rawRect.midY - bgHeight / 2 - 0.5
            let rect = CGRect(x: rawRect.minX - 0.5, y: bgY, width: rawRect.width + 1, height: bgHeight)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 5)
            context.addPath(path.cgPath)
        }
        context.fillPath()
        context.restoreGState()
    }
}

// MARK: - SelectableMarkdownTextView

/// Non-editable, selectable UITextView subclass for rendering Markdown as NSAttributedString.
final class SelectableMarkdownTextView: UITextView, UIGestureRecognizerDelegate {
    private var attachmentViews: [UIView] = []
    /// Returns true if the text storage contains NSTextAttachment objects but no attachment views
    /// have been created yet. Used by updateUIView to detect the LazyVStack reappear case where
    /// the async updateAttachmentViews call was dropped because window was nil at the time.
    var hasUnrenderedAttachments: Bool {
        guard attachmentViews.isEmpty,
              let storage = textStorage as? NSTextStorage else { return false }
        var found = false
        storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, _, stop in
            if value != nil { found = true; stop.pointee = true }
        }
        return found
    }
    /// Whether a same-markdown attachment recovery has already been scheduled/performed
    /// for the current attributedText snapshot. Prevents repeated recovery storms while
    /// LazyVStack / SwiftUI is still settling.
    var hasScheduledRecoveryForCurrentContent = false

    /// Invoked when the user picks "Copy Screenshot" from the text selection menu.
    /// Set by the SwiftUI layer so the menu item can reach the owning turn.
    var onCopyScreenshot: (() -> Void)?

    /// Coalesce guard for math-attachment `onLoad` refreshes. With hundreds of
    /// formulas each completing asynchronously, scheduling a full
    /// `refreshAttachmentViews()` per callback multiplies the cost N×; instead
    /// we set this flag, dispatch one refresh, and clear it on arrival.
    private var pendingMathRefresh = false

    /// Schedule at most one `refreshAttachmentViews()` per runloop tick from
    /// math-attachment `onLoad` callbacks. Safe to call any number of times.
    func scheduleCoalescedMathRefresh() {
        guard !pendingMathRefresh else { return }
        pendingMathRefresh = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.pendingMathRefresh = false
            self.refreshAttachmentViews()
        }
    }

    /// Reset whenever attributedText content changes, so a future same-markdown reappear
    /// may recover once if attachments are missing/incomplete.
    func resetAttachmentRecoveryState() {
        hasScheduledRecoveryForCurrentContent = false
    }

    /// [T-ios-math-invisible-dark-mode] Re-render formula bitmaps when the
    /// light/dark style flips.
    ///
    /// Math images bake in a concrete text color, so they are style-specific
    /// (`SwiftMathRenderer.interfaceStyle` explains why they cannot stay
    /// dynamic). Text, code and table attachments all keep resolving their
    /// colors normally through UIKit; only the rasterised formulas are stuck,
    /// so this drops just those and lets the existing coalesced refresh redraw
    /// them.
    ///
    /// `hasDifferentColorAppearance` rather than a raw style comparison: it is
    /// the check UIKit intends here, and it filters out the many trait changes
    /// (size class, dynamic type, layout direction) that must NOT trigger a
    /// re-render storm across every formula on screen.
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard traitCollection.hasDifferentColorAppearance(comparedTo: previousTraitCollection),
              let storage = textStorage as? NSTextStorage else { return }
        var invalidated = 0
        storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, _, _ in
            if let math = value as? MathAttachment {
                math.invalidateRenderForInterfaceStyleChange()
                invalidated += 1
            }
        }
        guard invalidated > 0 else { return }
        AppLogger(category: "MathSync").info("[MathSync] interface style changed — re-rendering \(invalidated) formula(s)")
        scheduleCoalescedMathRefresh()
    }

    var needsAttachmentRecovery: Bool {
        guard let storage = textStorage as? NSTextStorage else { return false }

        var expected = 0
        storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, _, _ in
            if value != nil { expected += 1 }
        }

        guard expected > 0 else { return false }

        if attachmentViews.count != expected {
            return true
        }

        if attachmentViews.contains(where: { $0.superview !== self }) {
            return true
        }

        return false
    }

    /// Maps attachment identity (ObjectIdentifier) to its current UIView, for reuse during streaming.
    private var attachmentViewMap: [ObjectIdentifier: UIView] = [:]

    /// Fingerprint of the last successful updateAttachmentViews invocation.
    /// Tuple = (textStorage.length, textContainer.size.width, attachmentViewMap.count).
    /// When the next call sees the same fingerprint AND every attachment in
    /// storage is already in attachmentViewMap, we can skip the full
    /// enumeration — there's nothing to do. This eliminates the redundant
    /// 2nd/3rd UAV call SwiftUI's UIHostingConfiguration triggers per BIG-CELL
    /// onscreen (window=nil → window-set → layoutSubviews chain).
    private var lastUAVStorageLen: Int = -1
    private var lastUAVWidth: CGFloat = -1
    private var lastUAVViewCount: Int = -1
    /// [TableDedupBug] Tracks the sum of all TableAttachments' contentGeneration
    /// at the moment of the last successful `updateAttachmentViews` pass.
    /// Without this term the per-token streaming attachment mutations (which
    /// preserve textStorage length) defeat the (storageLen+width+viewCount)
    /// fingerprint and the table view never gets re-rendered with the new
    /// rows. See the entry-block dedup in `updateAttachmentViews` for the
    /// matching consumer.
    private var lastUAVTableGenSum: UInt64 = 0
    /// Maps code block sequential index to its current UIView, for reuse when content changes
    /// (new attachment object but same visual position). Allows `updateExistingView` instead of full rebuild.
    private var codeBlockViewCache: [Int: UIView] = [:]
    /// Tracks the last textContainer width used for layout, to detect rotation/size changes.
    private var lastLayoutWidth: CGFloat = 0
    /// Tracks the last computed intrinsic content height to detect when height changes
    /// and the parent collection view cell needs to re-self-size.
    fileprivate(set) var lastComputedHeight: CGFloat = 0

    /// Fingerprint inputs that produced `lastComputedHeight`. Used by
    /// `invalidateCellSizeIfNeeded` to skip the costly sizeThatFits + collection
    /// view layout invalidation when nothing observable changed since the last
    /// measurement. Refreshed after a successful sizeThatFits.
    /// [T-ios-refresh-teardown-guard] Content fingerprint of the last
    /// `performRefreshAttachmentViews` pass. When the next async media-load
    /// callback arrives with the SAME storage length and width, the content did
    /// not change — only an attachment's intrinsic size did — so the views can
    /// be repositioned in place instead of destroyed and rebuilt.
    private var lastRefreshStorageLen: Int = -1
    private var lastRefreshWidth: CGFloat = -1

    private var lastSizedStorageLen: Int = -1
    private var lastSizedWidth: CGFloat = -1
    /// Sum of every TableAttachment's `contentGeneration` at the moment of
    /// the last successful sizeThatFits. Streaming tables mutate their rows
    /// in place without changing textStorage.length, so without this extra
    /// term the `(storageLen, width)` dedup fingerprint would falsely report
    /// "unchanged" and the cell would never re-measure — visible to the
    /// user as the table rendering only its first column / first row(s)
    /// even though the markdown source already contains the full table.
    private var lastSizedTableGenSum: UInt64 = 0
    /// Raw markdown source for "Copy Markdown" action.
    var rawMarkdown: String = ""

    // MARK: Attachment-layout re-entry guards (defensive — image-attachment hang)
    /// Set while `refreshAttachmentViews` is executing. Same-runloop re-entry
    /// (e.g. `beginLoadingIfNeeded` synchronously firing `onLoad` from the
    /// `NativeMediaImageCache` hit path) would otherwise recurse into itself.
    private var isRefreshingAttachments = false
    /// Set while `invalidateCellSizeIfNeeded` is executing. The collection-view
    /// `invalidateLayout()` it triggers can synchronously re-enter
    /// `preferredLayoutAttributesFitting` → `sizeThatFits` → `invalidateCellSizeIfNeeded`.
    private var isInvalidatingCellSize = false
    /// [T-ios-lastcell-clip-defer-lost] Armed when a REAL height change was
    /// detected during a deferSelfSizing window and the correction had to be
    /// skipped. Guarantees one post-settle retry of invalidateCellSizeIfNeeded
    /// even if no further UAV/measure call arrives after the glide ends.
    private var pendingDeferredRemeasure = false
    /// [T-ios-defer-retry-never-consumed] Set alongside `pendingDeferredRemeasure`
    /// when a correction is skipped inside a deferSelfSizing window. Two jobs:
    ///
    ///  1. It lets the retry BYPASS the `[SKIP-DEDUPE]` fingerprint early-exit.
    ///     The skip path deliberately does not consume the measurement, on the
    ///     assumption (see the comment there) that "token growth mutates storage
    ///     length, which already defeats the fingerprint". That holds only WHILE
    ///     the stream is running. In the field repro the skip landed 43ms before
    ///     `isProcessing=false`, so the storage stopped changing, the fingerprint
    ///     matched forever, and every retry early-exited before re-measuring —
    ///     the correction was lost for good (log evidence: 128 `SKIP-DEDUPE`
    ///     lines in the session, zero after the skip).
    ///  2. It is consumed by `consumeDeferredCorrectionIfNeeded()`, which the
    ///     Coordinator calls the moment `deferSelfSizing` is actually cleared at
    ///     settle — instead of guessing with a fixed 0.6s timer that can (and
    ///     did) fire while the user is still dragging, landing straight back in
    ///     the same skip branch.
    private var deferredCorrectionPending = false
    /// Records the last time the layout loop tripped the re-entry guard, for
    /// watchdog diagnostics. If this fires many times per second we know the
    /// loop is hot even if we successfully short-circuit it.
    private var reentryGuardHits: Int = 0

    /// Set when `refreshAttachmentViews` was dropped because the view was
    /// detached from window (`window == nil`). Replayed from
    /// `didMoveToWindow` once the view is re-attached so an async image load
    /// that completed while off-screen still gets its cell-size grow.
    ///
    /// Background: when an assistant message contains multiple inline images
    /// that load asynchronously, the LAST image's `onLoad` callback can
    /// arrive after the cell has been briefly recycled out of window (scroll
    /// off-screen, then user immediately scrolls back). With the
    /// `window != nil` guard at the top of `refreshAttachmentViews` the
    /// refresh is silently dropped, leaving the cell sized to the last
    /// known attachment bounds (where the final image was still a 200pt
    /// placeholder). Scrolling back never re-triggers the refresh, so the
    /// last image renders clipped or empty until the user exits + re-enters
    /// the chat. (T-multi-image-last d8a51f73.)
    private var pendingRefreshAfterWindowAttach = false

    /// [T-ios-reuse-cachewipe] Method B burst-coalescing state — see
    /// `refreshAttachmentViews()`. `isCoalescingRefresh` is set while a
    /// commit is already scheduled for the next runloop turn;
    /// `coalescedRefreshPending` records that more async completions landed
    /// during that window (used only to log that a burst was absorbed).
    private var isCoalescingRefresh = false
    private var coalescedRefreshPending = false

    init() {
        // Use TextKit 1 for reliable NSLayoutManager overrides
        let textStorage = NSTextStorage()
        let layoutManager = MinisLayoutManager()
        let textContainer = NSTextContainer()
        textContainer.lineFragmentPadding = 0
        textContainer.widthTracksTextView = true
        layoutManager.addTextContainer(textContainer)
        textStorage.addLayoutManager(layoutManager)

        super.init(frame: .zero, textContainer: textContainer)

        isEditable = false
        isSelectable = true
        isScrollEnabled = false
        // Symmetric 4pt top/bottom inset. Bottom was there originally to keep
        // emoji and Latin descenders (g, p, y) off the clipping edge under
        // TextKit 1; without a matching TOP inset the ascender of glyphs
        // whose mark extends above cap-height — the dot on "i" / "j",
        // accents, the curl on "f" — gets sliced by `clipsToBounds = true`
        // when the first line's `lineFragmentRect` starts at y=0. T-dot-clip
        // 8d2c7f4a.
        //
        // 4pt matches the bottom inset and fits the heightForBlock precalc's
        // existing `bounds.height + 8` buffer (4 top + 4 bottom = 8), so no
        // change is needed in the height-cache layer.
        textContainerInset = UIEdgeInsets(top: 4, left: 0, bottom: 4, right: 0)
        backgroundColor = .clear
        textContainer.lineBreakMode = .byWordWrapping
        // Prevent UITextView from inserting extra padding
        contentInset = .zero
        clipsToBounds = true
        textDragInteraction?.isEnabled = false
        linkTextAttributes = [
            .foregroundColor: SelectableMarkdownTheme().linkColor,
            .underlineStyle: NSUnderlineStyle.single.rawValue,
        ]

        // Tap gesture for inline code copy
        let codeTap = UITapGestureRecognizer(target: self, action: #selector(handleInlineCodeTap(_:)))
        codeTap.delegate = self
        addGestureRecognizer(codeTap)
        inlineCodeTapGesture = codeTap

        // [T-ios-text-selection-autoscroll-followthrough] A non-cancelling,
        // zero-duration long-press that simply TRACKS the live touch during a
        // selection-handle drag. It never consumes the touch (cancelsTouchesInView
        // = false, recognizes simultaneously with everything) so UIKit's own
        // selection knobs keep working; we only read its location to drive the
        // collection view's edge auto-scroll while the finger is held. Unlike the
        // first cut, this gives us a real drag lifecycle (.ended/.cancelled =
        // finger lift) and a per-frame touch position, so holding still at the
        // edge keeps scrolling.
        let tracker = UILongPressGestureRecognizer(target: self, action: #selector(handleSelectionDragTrack(_:)))
        tracker.minimumPressDuration = 0
        tracker.cancelsTouchesInView = false
        tracker.delaysTouchesBegan = false
        tracker.delaysTouchesEnded = false
        tracker.delegate = self
        addGestureRecognizer(tracker)
        selectionDragTracker = tracker
    }

    // [T-ios-table-cell-image-menu] iOS 16 ONLY: UITextView attaches a built-in
    // UIContextMenuInteraction that, when an inline NSTextAttachment carries an
    // image (our TableAttachment / CodeBlockAttachment / MathAttachment all use a
    // transparent placeholder image for layout), long-press pops the system
    // image preview menu — "Copy Image" / "Save to Camera Roll" over an all-black
    // (transparent-placeholder) preview. There is no delegate hook to suppress
    // it on iOS 16 (the UITextItem.menuConfiguration API is iOS 17+, which is why
    // 17/18 are handled in menuConfigurationFor and 16 slipped through). We strip
    // the system context-menu interaction as UIKit installs it; the app's own
    // affordances don't depend on it — text selection/copy uses the edit menu
    // (UIMenuController) and table cells promote to TableCellTextView with their
    // own Copy Table / Copy Table Image menu. Gated to iOS <17 so 17+ keeps the
    // (handled) system menu and link/text-item previews.
    override func addInteraction(_ interaction: any UIInteraction) {
        if #unavailable(iOS 17.0), interaction is UIContextMenuInteraction {
            return
        }
        super.addInteraction(interaction)
    }

    private weak var selectionDragTracker: UILongPressGestureRecognizer?

    private weak var inlineCodeTapGesture: UITapGestureRecognizer?
    /// Called when the user taps a blank (non-link, non-code) area.
    /// The CGPoint is the tap location in **window coordinates**.
    var onTapBlank: ((CGPoint) -> Void)?

    /// Track whether we have an active (non-empty) text selection and notify
    /// the parent NoAnimationCollectionView.
    private var hadSelection = false
    /// Offsets (from beginningOfDocument) of the previous selection's start/end,
    /// so we can tell which handle is the one being dragged this change.
    /// [T-ios-text-selection-collectionview-autoscroll]
    private var prevSelStartOffset: Int?
    private var prevSelEndOffset: Int?

    func updateSelectionState() {
        let hasSelection = selectedTextRange.map { !$0.isEmpty } ?? false
        let cv = findCollectionView()
        if hasSelection != hadSelection {
            hadSelection = hasSelection
            cv?.hasActiveTextSelection = hasSelection
        }
        // [T-ios-text-selection-collectionview-autoscroll] On every selection
        // change while a selection is active (the user is dragging a handle),
        // report the MOVING handle's caret to the collection view so it can
        // auto-scroll when that handle nears an edge. UITextView's own
        // auto-scroll only moves the text view, not the outer list.
        guard let cv else { return }
        guard hasSelection, let range = selectedTextRange else {
            prevSelStartOffset = nil
            prevSelEndOffset = nil
            cv.stopSelectionAutoScroll()
            return
        }
        let startOff = offset(from: beginningOfDocument, to: range.start)
        let endOff = offset(from: beginningOfDocument, to: range.end)
        // The handle that moved since last change is the dragging one. On the
        // first change of a new selection (no prev), default to the end handle.
        let draggingEnd: UITextPosition
        if let pe = prevSelEndOffset, endOff != pe {
            draggingEnd = range.end
        } else if let ps = prevSelStartOffset, startOff != ps {
            draggingEnd = range.start
        } else {
            draggingEnd = range.end
        }
        prevSelStartOffset = startOff
        prevSelEndOffset = endOff
        // [T-ios-text-selection-autoscroll-followthrough] Remember which handle
        // (start vs end) is the one being dragged so the auto-scroll step can
        // extend THAT end to the content scrolled under the finger.
        draggingHandleIsEnd = (draggingEnd === range.end)
    }

    /// Which selection handle the user is currently dragging (true = end/right,
    /// false = start/left). Used to extend the correct end while auto-scrolling.
    private var draggingHandleIsEnd = true

    /// Tracks the live selection-handle drag touch and drives the collection
    /// view's edge auto-scroll off it. [T-ios-text-selection-autoscroll-followthrough]
    @objc private func handleSelectionDragTrack(_ g: UILongPressGestureRecognizer) {
        guard let cv = findCollectionView() else { return }
        switch g.state {
        case .began, .changed:
            // Only drive auto-scroll while a real (non-empty) selection exists —
            // i.e. the user is dragging a selection handle, not just touching.
            let hasSelection = selectedTextRange.map { !$0.isEmpty } ?? false
            guard hasSelection else { cv.stopSelectionAutoScroll(); return }
            // Feed the live touch Y in the collection view's coordinate space and
            // wire the per-frame selection-follow callback.
            let touchPoint = g.location(in: cv)
            cv.onAutoScrollStep = { [weak self, weak cv] pt in
                guard let self, let cv else { return }
                self.extendSelectionToTouch(pointInCollectionView: pt, cv: cv)
            }
            cv.updateSelectionAutoScroll(touchPoint: touchPoint)
        case .ended, .cancelled, .failed:
            cv.onAutoScrollStep = nil
            cv.stopSelectionAutoScroll()
        default:
            break
        }
    }

    /// After an auto-scroll step, extend the dragging end of the selection to the
    /// document position now under the held touch, so the range follows the
    /// scrolled-in content (the iMessage-style behavior).
    private func extendSelectionToTouch(pointInCollectionView pt: CGPoint, cv: NoAnimationCollectionView) {
        // [T-ios-selection-autoscroll-edge-flicker] Convert the REAL touch point
        // (both X and Y) into this text view's coordinates. The earlier version
        // used the text view's mid-X, so the caret it computed sat at a
        // different in-line position than UIKit's own native caret (driven by
        // the real touch X). The two then overwrote `selectedTextRange` with
        // different ranges on alternating frames — the handle/highlight redrew
        // back and forth = the reported edge flicker. Using the real X makes our
        // computed caret agree with UIKit's, so they no longer fight.
        let pointInSelf = cv.convert(pt, to: self)
        guard let touchPos = closestPosition(to: pointInSelf),
              let current = selectedTextRange else { return }
        let newRange: UITextRange?
        if draggingHandleIsEnd {
            // Dragging the end handle downward: anchor = start, moving = touch.
            newRange = textRange(from: current.start, to: touchPos)
        } else {
            // Dragging the start handle upward: anchor = end, moving = touch.
            newRange = textRange(from: touchPos, to: current.end)
        }
        guard let r = newRange, !r.isEmpty else { return }
        // [T-ios-selection-autoscroll-edge-flicker] Skip the write when the range
        // is unchanged — re-assigning the same selection still forces UITextView
        // to redraw its handles/highlight, adding to the per-frame churn.
        // UITextPosition is a reference type, so compare by document offset
        // rather than identity.
        if let cur = selectedTextRange,
           compare(cur.start, to: r.start) == .orderedSame,
           compare(cur.end, to: r.end) == .orderedSame {
            return
        }
        selectedTextRange = r
    }

    @discardableResult
    override func becomeFirstResponder() -> Bool {
        // Momentarily lock the collection view's contentOffset during
        // becomeFirstResponder to block UIKit's _adjustContentOffsetIfNecessary.
        // [T-ios-resign-first-responder-graph-reentry] Deliberately NOT guarded.
        // The crash walks in through -[UITextView resignFirstResponder] during
        // UIKit's responder REBASE; the _UIHostingView.canBecomeFirstResponder
        // frame in that stack is UIKit interrogating a candidate mid-walk, not
        // an entry into this override. Deferring here bought no crash coverage
        // and did cost behaviour: the table-cell promotion path at
        // `promoteTextView` calls becomeFirstResponder() and then immediately
        // sets a selection and shows the edit menu, so returning false and
        // completing a turn later left the menu addressing a view that was not
        // yet first responder.
        let cv = findCollectionView()
        cv?.offsetLocked = true
        let result = super.becomeFirstResponder()
        cv?.offsetLocked = false
        // [T-ios-preapply-endediting] Register as the tracked responder so
        // applySnapshot's pre-apply endEditing gate knows a responder exists
        // inside the list without walking the subview tree.
        if result { cv?.trackedTextResponder = self }
        return result
    }

    @discardableResult
    override func resignFirstResponder() -> Bool {
        // Momentarily lock during resignFirstResponder too.
        let cv = findCollectionView()
        // [T-ios-preapply-endediting] History, because two TestFlight crash
        // families met in this method:
        //
        //  1. crash C4wXLBpem (1.13(4), 8/17): `super.resignFirstResponder()`
        //     INSIDE `dataSource.apply`'s batch update walks the responder
        //     chain → _UIHostingView.canBecomeFirstResponder →
        //     ViewRendererHost.responderNode → AGGraphGetInputValue — a fresh
        //     AttributeGraph read mid-transaction → AG::precondition_failure →
        //     abort() (not catchable).
        //  2. c1fd59e1f fixed (1) by deferring `super` and returning false.
        //     That refusal violates UIKit's rebase contract: TestFlight
        //     1.13(15) then asserted in
        //     _resignOrRebaseFirstResponderViewWithIndexPathMapping:.
        //
        // Both require a live first responder at apply time, so the fix moved
        // up a level: applySnapshot calls endEditing BEFORE the structural
        // apply, outside the transaction, where a synchronous `super` is the
        // ordinary, safe path. This override is synchronous again and never
        // returns false while actually still responder.
        if cv?.isApplyingSnapshot == true {
            // Should be unreachable (see above). Synchronous super here is an
            // occasional graph crash; returning false was a guaranteed
            // assertion — synchronous strictly dominates. WARN for visibility.
            responderLogger.warning("[Responder] resignFirstResponder DURING applySnapshot — pre-apply endEditing missed this responder")
        }
        cv?.offsetLocked = true
        let result = super.resignFirstResponder()
        cv?.offsetLocked = false
        if cv?.trackedTextResponder === self { cv?.trackedTextResponder = nil }
        clearSelectionStateOnResign(cv)
        return result
    }

    /// Selection/auto-scroll teardown that must happen whenever this text view
    /// loses focus, independent of whether `super` ran synchronously or was
    /// deferred. [T-ios-resign-first-responder-graph-reentry]
    private func clearSelectionStateOnResign(_ cv: NoAnimationCollectionView?) {
        // Safety net: always clear selection lock when losing focus.
        if hadSelection {
            hadSelection = false
            cv?.hasActiveTextSelection = false
        }
        // [T-ios-text-selection-collectionview-autoscroll] Losing focus ends any
        // selection drag — stop the edge auto-scroll ticker immediately.
        prevSelStartOffset = nil
        prevSelEndOffset = nil
        cv?.stopSelectionAutoScroll()
    }

    /// Walk the superview chain to find the parent NoAnimationCollectionView.
    private func findCollectionView() -> NoAnimationCollectionView? {
        var view: UIView? = superview
        while let v = view {
            if let cv = v as? NoAnimationCollectionView { return cv }
            view = v.superview
        }
        return nil
    }

    /// Walk up the view hierarchy to find the enclosing UICollectionViewCell.
    private func findCell() -> UICollectionViewCell? {
        var view: UIView? = superview
        while let v = view {
            if let cell = v as? UICollectionViewCell { return cell }
            view = v.superview
        }
        return nil
    }

    @objc private func handleInlineCodeTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended else { return }
        let point = gesture.location(in: self)
        let windowPoint = self.convert(point, to: nil)

        // Check if tap is inside an attachment view (code block, table, image, etc.)
        for subview in attachmentViews {
            if subview.frame.contains(point) {
                tapLogger.debug("[tap] hit attachment view at \(String(format: "%.0f,%.0f", point.x, point.y)) — ignored")
                return
            }
        }

        var fraction: CGFloat = 0
        let charIndex = layoutManager.characterIndex(
            for: point,
            in: textContainer,
            fractionOfDistanceBetweenInsertionPoints: &fraction
        )

        // Determine if the tap is actually over rendered text or in empty space.
        let isBeyondText = charIndex >= textStorage.length
        let isInGlyphRect: Bool
        if !isBeyondText {
            let glyphIndex = layoutManager.glyphIndexForCharacter(at: charIndex)
            let lineRect = layoutManager.lineFragmentUsedRect(forGlyphAt: glyphIndex, effectiveRange: nil)
            isInGlyphRect = lineRect.contains(point)
        } else {
            isInGlyphRect = false
        }
        let isBlankArea = isBeyondText || !isInGlyphRect

        tapLogger.debug("[tap] point=\(String(format: "%.0f,%.0f", point.x, point.y)) charIdx=\(charIndex) len=\(textStorage.length) fraction=\(String(format: "%.2f", fraction)) inGlyph=\(isInGlyphRect) isBlank=\(isBlankArea) hasOnTapBlank=\(onTapBlank != nil)")

        if isBlankArea {
            if let onTapBlank {
                tapLogger.debug("[tap] blank area → calling onTapBlank windowPt=\(String(format: "%.0f,%.0f", windowPoint.x, windowPoint.y))")
                onTapBlank(windowPoint)
            } else {
                tapLogger.debug("[tap] blank area but no onTapBlank handler")
            }
            return
        }

        // Inline code: copy it
        if let codeText = textStorage.attribute(.inlineCodeText, at: charIndex, effectiveRange: nil) as? String {
            tapLogger.debug("[tap] inline code → copy")
            UIPasteboard.general.string = codeText
            showCopiedFeedback(at: charIndex)
            return
        }

        // Link or plain text — don't intercept
        let isLink = textStorage.attribute(.link, at: charIndex, effectiveRange: nil) != nil
        tapLogger.debug("[tap] on text content (link=\(isLink)) — not handled")
    }

    private let tapLogger = AppLogger(category: "MarkdownTap")

    private func showCopiedFeedback(at charIndex: Int) {
        var effectiveRange = NSRange()
        guard textStorage.attribute(.inlineCodeText, at: charIndex, effectiveRange: &effectiveRange) != nil else { return }
        let glyphRange = layoutManager.glyphRange(forCharacterRange: effectiveRange, actualCharacterRange: nil)
        let rect = layoutManager.boundingRect(forGlyphRange: glyphRange, in: textContainer)

        let flash = UIView(frame: rect.insetBy(dx: -2, dy: -1))
        flash.backgroundColor = UIColor.systemBlue.withAlphaComponent(0.18)
        flash.layer.cornerRadius = 4
        flash.isUserInteractionEnabled = false
        addSubview(flash)

        // Show "Copied" toast near the code
        let toast = UILabel()
        toast.text = "Copied"
        toast.font = .systemFont(ofSize: 11, weight: .medium)
        toast.textColor = .white
        toast.backgroundColor = UIColor.black.withAlphaComponent(0.75)
        toast.textAlignment = .center
        toast.layer.cornerRadius = 4
        toast.clipsToBounds = true
        toast.sizeToFit()
        toast.frame.size.width += 12
        toast.frame.size.height += 4
        toast.center = CGPoint(x: rect.midX, y: rect.minY - toast.frame.height / 2 - 4)
        // Keep toast within text view bounds
        if toast.frame.minX < 0 { toast.frame.origin.x = 0 }
        if toast.frame.maxX > bounds.width { toast.frame.origin.x = bounds.width - toast.frame.width }
        toast.alpha = 0
        addSubview(toast)

        UIView.animate(withDuration: 0.15) { toast.alpha = 1 }
        UIView.animate(withDuration: 0.3, delay: 0.8, options: []) {
            flash.alpha = 0
            toast.alpha = 0
        } completion: { _ in
            flash.removeFromSuperview()
            toast.removeFromSuperview()
        }
    }

    // Allow code block UITextViews to handle horizontal pans.
    // The outer text view's panGestureRecognizer can intercept touches
    // even when isScrollEnabled=false; reject horizontal pans that
    // originate inside a scrollable code block subview.
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer == self.panGestureRecognizer {
            let location = gestureRecognizer.location(in: self)
            // Check if touch is inside any code block UITextView subview
            for subview in attachmentViews {
                if subview.frame.contains(location) {
                    // Find the UITextView inside this attachment view
                    if let codeTV = findCodeTextView(in: subview), codeTV.isScrollEnabled {
                        let vel = self.panGestureRecognizer.velocity(in: self)
                        // If primarily horizontal, let the inner code block handle it
                        if abs(vel.x) > abs(vel.y) {
                            return false
                        }
                    }
                }
            }
        }
        // Inline code tap / blank area tap: allow unless tapping a link or attachment view
        if gestureRecognizer === inlineCodeTapGesture {
            // Don't interfere with active text selection
            if let sel = selectedTextRange, !sel.isEmpty { return false }
            let point = gestureRecognizer.location(in: self)
            // Don't interfere with attachment views (code blocks, tables, images)
            for subview in attachmentViews {
                if subview.frame.contains(point) { return false }
            }
            let idx = layoutManager.characterIndex(for: point, in: textContainer, fractionOfDistanceBetweenInsertionPoints: nil)
            let isBeyondText = idx >= textStorage.length
            let isInGlyphRect: Bool
            if !isBeyondText {
                let gi = layoutManager.glyphIndexForCharacter(at: idx)
                let lineRect = layoutManager.lineFragmentUsedRect(forGlyphAt: gi, effectiveRange: nil)
                isInGlyphRect = lineRect.contains(point)
            } else {
                isInGlyphRect = false
            }
            let isBlankArea = isBeyondText || !isInGlyphRect
            // Blank area → allow if handler exists
            if isBlankArea { return onTapBlank != nil }
            // On a link → don't begin (UITextView handles it)
            if textStorage.attribute(.link, at: idx, effectiveRange: nil) != nil { return false }
            // On inline code → allow
            if textStorage.attribute(.inlineCodeText, at: idx, effectiveRange: nil) != nil { return true }
            // On plain text → don't intercept (let text selection work)
            return false
        }
        return super.gestureRecognizerShouldBegin(gestureRecognizer)
    }

    // Allow our tap gesture to coexist with other tap gestures (e.g. link taps),
    // but NOT with long-press or pan gestures used for text selection.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // [T-ios-text-selection-autoscroll-followthrough] The selection-drag
        // tracker is a pure observer — it must run alongside UIKit's selection
        // knobs and everything else, and never blocks another gesture.
        if gestureRecognizer === selectionDragTracker || otherGestureRecognizer === selectionDragTracker {
            return true
        }
        if gestureRecognizer === inlineCodeTapGesture {
            return otherGestureRecognizer is UITapGestureRecognizer
        }
        return false
    }

    // When the user is actively selecting text (selection range exists and is non-zero),
    // don't let our tap gesture interfere with the selection handles.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRequireFailureOf otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === inlineCodeTapGesture,
           otherGestureRecognizer is UILongPressGestureRecognizer {
            // [T-ios-inline-code-tap-blocked-by-selection-tracker] The
            // selection-drag tracker is a `minimumPressDuration = 0` long-press,
            // so it SUCCEEDS on touch-down and never fails. Requiring it to fail
            // (the blanket `is UILongPressGestureRecognizer` rule) permanently
            // blocked codeTap, so tapping inline code did nothing. Only require a
            // *real* long-press (text selection) to fail; never the tracker.
            if otherGestureRecognizer === selectionDragTracker { return false }
            return true  // a real long press must fail before our tap can begin
        }
        return false
    }

    private func findCodeTextView(in view: UIView) -> UITextView? {
        for sub in view.subviews {
            if let tv = sub as? UITextView { return tv }
            if let found = findCodeTextView(in: sub) { return found }
        }
        return nil
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        // Block edit-related actions since we're read-only; allow everything else
        // (select, copy, look up, share, Copy Markdown, etc.)
        let blocked: Set<String> = ["paste:", "cut:", "delete:", "_addShortcut:"]
        if blocked.contains(NSStringFromSelector(action)) {
            return false
        }
        // iOS 16: long-pressing inline NSTextAttachment objects that carry a
        // transparent placeholder UIImage (TableAttachment, CodeBlockAttachment,
        // MathAttachment, etc.) makes the system add "Copy Image" / "Save
        // Image" / "Share Image" entries to UIMenuController. The image is a
        // 1×1 transparent placeholder used so UIKit doesn't render a default
        // "?" glyph — copying it is meaningless. Filter the relevant
        // selectors so the menu only surfaces actions that work on the
        // underlying text. iOS 17+ uses UIEditMenuInteraction and doesn't
        // present these by default.
        let actionName = NSStringFromSelector(action)
        if actionName.localizedCaseInsensitiveContains("image") {
            return false
        }
        if action == #selector(copyMarkdown(_:))
            || action == #selector(copyRichText(_:))
            || action == #selector(copyScreenshotFromMenu(_:)) {
            return true
        }
        if action == #selector(addSelectionToChatInput(_:)) {
            return selectedRange.length > 0
        }
        return super.canPerformAction(action, withSender: sender)
    }

    /// Append the currently-selected text to the active chat's input field
    /// via the `chatInputAppendRequested` notification. AIChatView listens
    /// and routes to `AIChatViewModel.appendToInputText`, which handles the
    /// `<existing> <selected> ` / `<selected> ` formatting.
    @objc func addSelectionToChatInput(_ sender: Any?) {
        guard selectedRange.length > 0,
              let range = Range(selectedRange, in: text) else { return }
        // [T-ios-inline-code-long-path-wrap] Strip the invisible spacers before
        // handing the text to the input bar, the same way `plainTextWithTables`
        // does for Copy / Read Selection. This path reads `text` directly, so it
        // would otherwise carry the ZWSPs that inline-code wrapping (and the CJK
        // emphasis pre-pass) inject into the rendered string.
        let snippet = String(text[range])
            .replacingOccurrences(of: "\u{200B}", with: "")
            .replacingOccurrences(of: "\u{200A}", with: "")
        NotificationCenter.default.post(
            name: .chatInputAppendRequested,
            object: nil,
            userInfo: ["text": snippet]
        )
        // Drop the selection / dismiss the edit menu so the user sees the
        // result land in the input. Mirrors the system Copy behaviour.
        resignFirstResponder()
    }

    /// Copies the raw Markdown source to the pasteboard.
    @objc func copyMarkdown(_ sender: Any?) {
        // [T-ios-math-copy-latex-delimiters] Normalize LaTeX delimiters to the
        // $...$ / $$...$$ form that most LaTeX editors expect. LLM output often
        // uses the \(...\) / \[...\] alternate delimiters which are valid LaTeX
        // but not recognized by many markdown renderers.
        var md = rawMarkdown
        md = md.replacingOccurrences(of: "\\(", with: "$")
        md = md.replacingOccurrences(of: "\\)", with: "$")
        md = md.replacingOccurrences(of: "\\[", with: "$$")
        md = md.replacingOccurrences(of: "\\]", with: "$$")
        UIPasteboard.general.string = md
    }

    /// Override the standard Copy so selections spanning tables include table
    /// cell text instead of a single space (the default attachment fallback).
    override func copy(_ sender: Any?) {
        let range = selectedRange.length > 0 ? selectedRange : NSRange(location: 0, length: attributedText.length)
        UIPasteboard.general.string = plainTextWithTables(in: range)
    }

    /// Invokes the Copy Screenshot closure plumbed through from SwiftUI.
    @objc func copyScreenshotFromMenu(_ sender: Any?) {
        onCopyScreenshot?()
    }

    // MARK: - [T-selection-menu-minis-tts] Minis TTS menu actions

    /// Read the WHOLE reply from the start via the Minis TTS stack
    /// (vm.readReplyFromStart). Plumbed from the cell bridge; nil while the
    /// reply is still streaming (would clash with live streaming TTS).
    var onReadAloud: (() -> Void)?
    /// Speak an arbitrary text snippet via the Minis TTS stack (vm.speakText).
    var onSpeakText: ((String) -> Void)?

    @objc func readReplyFromMenu(_ sender: Any?) {
        onReadAloud?()
    }

    @objc func readSelectionFromMenu(_ sender: Any?) {
        guard selectedRange.length > 0 else { return }
        // plainTextWithTables expands table/math attachments into speakable text.
        onSpeakText?(plainTextWithTables(in: selectedRange))
    }

    /// Copies the rendered rich text (RTF) to the pasteboard.
    @objc func copyRichText(_ sender: Any?) {
        let range = selectedRange.length > 0 ? selectedRange : NSRange(location: 0, length: attributedText.length)
        let subAttr = attributedText.attributedSubstring(from: range)
        if let rtfData = try? subAttr.data(from: NSRange(location: 0, length: subAttr.length),
                                           documentAttributes: [.documentType: NSAttributedString.DocumentType.rtf]) {
            UIPasteboard.general.setData(rtfData, forPasteboardType: "public.rtf")
        }
        // Also write plain-text fallback that includes table cell content (the
        // RTF substring's NSTextAttachment for tables collapses to a space when
        // pasted into plain-text targets).
        UIPasteboard.general.string = plainTextWithTables(in: range)
    }

    /// Returns the plain-text representation of `range`, expanding any
    /// `TableAttachment` runs into tab-separated cell text so multi-block
    /// selections that span a table preserve table content on copy.
    private func plainTextWithTables(in range: NSRange) -> String {
        let attr = attributedText ?? NSAttributedString()
        guard range.location >= 0, NSMaxRange(range) <= attr.length else {
            return attr.string
        }
        var out = ""
        attr.enumerateAttribute(.attachment, in: range, options: []) { value, subRange, _ in
            if let table = value as? TableAttachment {
                out += table.plainText()
            } else if let math = value as? MathAttachment {
                // [T-ios-math-copy-latex-delimiters] Emit LaTeX with $...$ / $$...$$.
                if math.isBlock {
                    out += "$$\(math.latex)$$"
                } else {
                    out += "$\(math.latex)$"
                }
            } else {
                out += (attr.string as NSString).substring(with: subRange)
            }
        }
        // [T-md-cjk-emphasis-pairs] Strip the invisible spacers the markdown
        // pre-pass injects to fix CommonMark flanking around CJK punctuation
        // (ZWSP now; hair space from the pre-2026-05 fixers may survive in
        // cached attributed strings) — they must not leak into the clipboard
        // or the TTS text.
        return out
            .replacingOccurrences(of: "\u{200B}", with: "")
            .replacingOccurrences(of: "\u{200A}", with: "")
    }

    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        // [T-selection-menu-minis-tts] Remove the SYSTEM speech menu ("Speak…"
        // / "Spell") — it reads with the OS voice, bypassing the Minis TTS
        // stack (provider voices, sanitizer, fail-over). Replaced below with
        // our own Read Selection / Read Reply entries.
        builder.remove(menu: .speech)
        let copyMd = UICommand(
            title: AppLocalized("Copy Markdown"),
            image: UIImage(systemName: "text.quote"),
            action: #selector(copyMarkdown(_:))
        )
        let copyRtf = UICommand(
            title: AppLocalized("Copy Rich Text"),
            image: UIImage(systemName: "doc.richtext"),
            action: #selector(copyRichText(_:))
        )
        var extras: [UIMenuElement] = [copyMd, copyRtf]
        if onCopyScreenshot != nil {
            extras.append(UICommand(
                title: AppLocalized("Copy Screenshot"),
                image: UIImage(systemName: "camera.viewfinder"),
                action: #selector(copyScreenshotFromMenu(_:))
            ))
        }
        // [T-selection-menu-minis-tts] Minis-owned read-aloud entries. "Read
        // Selection" speaks just the selected range; "Read from Start" replays
        // the whole reply — both through the in-app TTS stack.
        // Matching speaker glyph family so the two read-aloud entries read as
        // one feature: selection = plain speaker, whole reply = speaker+waves.
        if selectedRange.length > 0, onSpeakText != nil {
            extras.append(UICommand(
                title: AppLocalized("Read Selection"),
                image: UIImage(systemName: "speaker.wave.1"),
                action: #selector(readSelectionFromMenu(_:))
            ))
        }
        if onReadAloud != nil {
            extras.append(UICommand(
                title: AppLocalized("Read from Start"),
                image: UIImage(systemName: "speaker.wave.2"),
                action: #selector(readReplyFromMenu(_:))
            ))
        }
        let extrasMenu = UIMenu(title: "", options: .displayInline, children: extras)
        // insertSibling(afterMenu: .standardEdit) pushes successive inserts
        // toward the anchor — i.e. the LAST inserted sibling lands closest to
        // standardEdit. Insert the secondary copy commands first so the
        // high-frequency "Add to Chat Input" action ends up immediately after
        // the system Copy item.
        builder.insertSibling(extrasMenu, afterMenu: .standardEdit)
        if selectedRange.length > 0 {
            let addToInput = UIMenu(title: "", options: .displayInline, children: [
                UICommand(
                    title: AppLocalized("Add to Chat Input"),
                    image: UIImage(systemName: "text.insert"),
                    action: #selector(addSelectionToChatInput(_:))
                )
            ])
            builder.insertSibling(addToInput, afterMenu: .standardEdit)
        }
    }

    // MARK: Selection Dismissal

    /// Anchor character index for Shift+Click range selection.
    private var selectionAnchor: Int?

    /// Clear text selection on the first tap outside the selected range.
    ///
    /// By default, UITextView (isSelectable=true, isEditable=false) intercepts the
    /// first tap to manipulate the selection, so the outer SwiftUI .onTapGesture
    /// never fires — the user has to tap twice to dismiss the selection and trigger
    /// the parent's tap handler. This override detects a tap outside the current
    /// selection and immediately clears it, then lets the touch propagate normally
    /// so the parent view responds on the same tap.
    ///
    /// When Shift is held (external keyboard), extends the selection from the
    /// anchor to the tap point.
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else {
            super.touchesBegan(touches, with: event)
            return
        }

        let point = touch.location(in: self)
        let glyphIndex = layoutManager.glyphIndex(for: point, in: textContainer, fractionOfDistanceThroughGlyph: nil)
        let charIndex = layoutManager.characterIndexForGlyph(at: glyphIndex)
        let isShift = event?.modifierFlags.contains(.shift) == true

        // Tap on a link: always let UITextView handle it so the link-interaction
        // path (primaryActionFor / shouldInteractWith) fires. Otherwise a stale
        // selection from a prior interaction (which survives until SwiftUI
        // recycles the view) would cause the selection-handling branch below to
        // swallow the touch and the link would silently no-op.
        if !isShift,
           charIndex < textStorage.length,
           textStorage.attribute(.link, at: charIndex, effectiveRange: nil) != nil {
            super.touchesBegan(touches, with: event)
            return
        }

        // Shift+click: extend selection from anchor to clicked position.
        if isShift {
            let anchor = selectionAnchor ?? selectedRange.location
            let newStart = min(anchor, charIndex)
            let newEnd = max(anchor, charIndex)
            selectedRange = NSRange(location: newStart, length: newEnd - newStart)
            // Keep the original anchor so further Shift+clicks extend from the same point.
            if selectionAnchor == nil { selectionAnchor = anchor }
            return
        }

        // Non-shift click resets the anchor.
        selectionAnchor = nil

        let sel = selectedRange
        guard sel.length > 0 else {
            super.touchesBegan(touches, with: event)
            return
        }

        // If the tap lands inside or at the edge of the selected range, let UITextView
        // handle it normally (the user may be tapping or dragging a selection handle).
        let selEnd = sel.location + sel.length
        let isInOrAtEdge = NSLocationInRange(charIndex, sel) || charIndex == selEnd

        if isInOrAtEdge {
            super.touchesBegan(touches, with: event)
            return
        }

        // Also check if the touch point is geometrically close to a selection handle.
        let selGlyphRange = layoutManager.glyphRange(forCharacterRange: sel, actualCharacterRange: nil)
        let selRect = layoutManager.boundingRect(forGlyphRange: selGlyphRange, in: textContainer)
        let handleMargin: CGFloat = 44
        if selRect.insetBy(dx: -handleMargin, dy: -handleMargin).contains(point) {
            super.touchesBegan(touches, with: event)
            return
        }

        // Tap is outside the selection — clear it immediately so this single tap
        // also propagates to the parent SwiftUI view as expected.
        selectedRange = NSRange(location: charIndex, length: 0)
        resignFirstResponder()
        // Do NOT call super: we've already cleared the selection and we want the
        // touch to fall through to the scroll view / SwiftUI gesture recognizers.
    }

    // MARK: Attachment Views

    /// Remove all attachment subviews and clear tracking state.
    /// Called when SwiftUI recycles this view for a different message.
    func clearAllAttachmentViews() {
        for view in attachmentViews { view.removeFromSuperview() }
        attachmentViews.removeAll()
        attachmentViewMap.removeAll()
        codeBlockViewCache.removeAll()
        // Reset dedupe fingerprint so the next updateAttachmentViews fully runs.
        lastUAVStorageLen = -1
        lastUAVWidth = -1
        lastUAVViewCount = -1
        // [T-ios-refresh-teardown-guard] Also drop the refresh fingerprint. The
        // `!attachmentViewMap.isEmpty` term already makes the in-place path
        // unreachable right after this call, but clearing it here keeps the
        // invariant explicit: a cleared view set never counts as "same
        // content", whichever site did the clearing.
        lastRefreshStorageLen = -1
        lastRefreshWidth = -1
    }

    /// [issue #117-4] Exact on-screen origin for an INLINE attachment overlay.
    ///
    /// `boundingRect(forGlyphRange:)` reports the glyph's LINE rect vertically:
    /// x/width are the glyph's own, but y/height describe the whole line
    /// fragment. Block attachments own their line, so the two coincide and
    /// nobody noticed — but an inline formula shares its line with text, and
    /// pinning its overlay at the line's top floats it above where TextKit
    /// actually typeset it. The gap is the line's ascent minus the formula's
    /// own height, so SMALL formulas float MOST (measured ≈13pt for `x` inside
    /// a quote block, versus ≈7pt for `x²`) — the "some formulas look higher
    /// than others" report.
    ///
    /// Rebuild the rect from the glyph origin instead. `location(forGlyphAt:)`
    /// returns the glyph's origin ON the baseline, relative to its line
    /// fragment, so baseline_y = lineFragment.minY + glyphLocation.y, and the
    /// attachment's top edge is that baseline minus (bounds.origin.y + height)
    /// — the same geometry TextKit itself uses when it draws an attachment.
    ///
    /// Returns nil when the caller should keep using `boundingRect` (block
    /// attachments, or a layout state too raw to trust).
    private func inlineAttachmentOrigin(
        for attachment: NSTextAttachment,
        characterRange range: NSRange,
        glyphRange: NSRange,
        layoutManager: NSLayoutManager
    ) -> CGPoint? {
        guard let mathAttach = attachment as? MathAttachment, !mathAttach.isBlock else { return nil }
        guard glyphRange.length > 0 else { return nil }
        let glyphIndex = glyphRange.location
        let lineFragment = layoutManager.lineFragmentRect(forGlyphAt: glyphIndex, effectiveRange: nil)
        guard lineFragment.height > 0 else { return nil }
        let glyphLocation = layoutManager.location(forGlyphAt: glyphIndex)
        let bounds = mathAttach.attachmentBounds(
            for: self.textContainer,
            proposedLineFragment: lineFragment,
            glyphPosition: glyphLocation,
            characterIndex: range.location
        )
        guard bounds.height > 0 else { return nil }
        // Vertical geometry, measured on-device (log [MathPos]/[MathBase],
        // 4 formulas × 17.49pt): for an ATTACHMENT glyph,
        // `location(forGlyphAt:)` does NOT return the text baseline the way it
        // does for a normal glyph — it returns the attachment's TOP-OF-ASCENT,
        // i.e. `baseline − bounds.origin.y` (bounds.origin.y is ≤0 for a
        // formula that descends below the baseline). Confirmed by the
        // invariant: `glyphLoc.y + bounds.origin.y` came out at exactly
        // 16.653 for all four formulas — one shared baseline — while
        // glyphLoc.y itself ranged 17.0–23.1.
        //
        // Hence the attachment's top edge is simply:
        //     top = glyphLoc.y − height
        // and the baseline lands `-bounds.origin.y` up from the image bottom,
        // which is exactly the descent captured at render time. Reading the
        // top directly also means we do NOT have to re-enter
        // `attachmentBounds` for the y (TextKit has already applied it).
        // `lineFragmentRect` / `location(forGlyphAt:)` are TEXT CONTAINER
        // coordinates; view frames are VIEW coordinates. UITextView offsets the
        // container by `textContainerInset` (this view sets top: 4 — the
        // T-dot-clip fix at init). `boundingRect(forGlyphRange:)`, which the
        // non-math attachments still use, comes back already offset, which is
        // why nothing needed this before; the glyph-level APIs do not.
        // Omitting it put every inline formula exactly 4pt too high — the
        // residual that survived the first pass of this fix.
        return CGPoint(
            x: lineFragment.minX + glyphLocation.x + bounds.origin.x + textContainerInset.left,
            y: lineFragment.minY + glyphLocation.y - bounds.height + textContainerInset.top
        )
    }

    func updateAttachmentViews() {
        // [AttachHotPath] Dedupe: when nothing relevant changed since the last
        // successful call AND the existing view map already covers every
        // attachment in storage, there is nothing to do — every branch in the
        // enumeration below would land in `reuse` and just re-set the same
        // frame. This skip eliminates the redundant 2nd/3rd UAV call per
        // BIG-CELL onscreen (window=nil → window-set chain that SwiftUI
        // UIHostingConfiguration triggers on cell reuse).
        if let storage = self.textStorage as? NSTextStorage,
           storage.length == lastUAVStorageLen,
           abs(self.textContainer.size.width - lastUAVWidth) < 0.5,
           !attachmentViewMap.isEmpty,
           attachmentViewMap.count == lastUAVViewCount {
            // Verify every attachment in storage is in the map, AND that
            // each TableAttachment's contentGeneration matches what we saw
            // at the last successful UAV pass.
            // [TableDedupBug] During streaming, a TableAttachment occupies a
            // single character in the attributed string; rows grow via
            // `update()` which mutates the attachment in place (object
            // identity preserved) but does NOT change `storage.length`.
            // The previous fingerprint (storageLen + width + viewCount) was
            // therefore satisfied on every streamed token, causing
            // `updateAttachmentViews` to return early without rebuilding
            // the TableScrollView. Visible symptom: the table cell stays
            // pinned at its first-row rendering even as cell height grows
            // to accommodate later rows the layout manager already knows
            // about — the "table is mostly empty space" report.
            var allMapped = true
            var currentTableGenSum: UInt64 = 0
            storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { v, _, stop in
                guard let att = v as? NSTextAttachment else { return }
                if attachmentViewMap[ObjectIdentifier(att)] == nil {
                    allMapped = false
                    stop.pointee = true
                }
                if let table = v as? TableAttachment {
                    currentTableGenSum &+= table.contentGeneration
                }
            }
            if allMapped && currentTableGenSum == lastUAVTableGenSum {
                AppLogger(category: "AttachHotPath").info("[UAV][SKIP-DEDUPE] storageLen=\(storage.length) tcW=\(String(format: "%.0f", self.textContainer.size.width)) views=\(self.attachmentViewMap.count) tableGen=\(currentTableGenSum) — fingerprint match, nothing to do")
                return
            }
        }
        // [AttachHotPath] One log per call. If imageAttachmentCount > 0, this
        // is in the hot path during streaming.
        let imageAttachmentCount: Int = {
            guard let storage = self.textStorage as? NSTextStorage else { return 0 }
            var n = 0
            storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { v, _, _ in
                if v is ImageAttachment { n += 1 }
            }
            return n
        }()
        if imageAttachmentCount > 0 {
            AppLogger(category: "AttachHotPath").info("[UAV] enter imgAttach=\(imageAttachmentCount) prevViews=\(self.attachmentViews.count) tcW=\(String(format: "%.0f", self.textContainer.size.width))")
        }
        let previousViewMap = attachmentViewMap
        var newViews: [UIView] = []
        var newViewMap: [ObjectIdentifier: UIView] = [:]
        var reusedIds: Set<ObjectIdentifier> = []

        guard let textStorage = self.textStorage as? NSTextStorage,
              let layoutManager = self.layoutManager as? MinisLayoutManager else {
            for view in attachmentViews { view.removeFromSuperview() }
            attachmentViews.removeAll()
            attachmentViewMap.removeAll()
            lastUAVStorageLen = -1
            lastUAVWidth = -1
            lastUAVViewCount = -1
            return
        }

        let fullRange = NSRange(location: 0, length: textStorage.length)
        let containerWidth = self.textContainer.size.width

        // Ensure TextKit has laid out all glyphs including trailing attachments.
        // UIKit may have clamped textContainer height to the current bounds, which
        // causes glyphs beyond that height to have zero bounding rects. Temporarily
        // expand to infinite height for both ensureLayout and boundingRect queries.
        let savedContainerHeight = self.textContainer.size.height
        if savedContainerHeight < CGFloat.greatestFiniteMagnitude {
            self.textContainer.size.height = CGFloat.greatestFiniteMagnitude
        }
        // [T-attachment-zero-origin 2026-05-23] Invalidate glyph properties
        // (not just layout) for the full range before ensureLayout. Otherwise
        // TextKit retains stale glyphProp=controlCharacter cached during an
        // earlier zero/narrow textContainer pass, leaving attachment glyphs
        // with `boundingRect.origin == .zero` even after layout settles — the
        // attachment view then gets pinned at (0,0) overlaying the cell's
        // first text line. Reproducible on iPhone 15 Pro Max (tcW=370);
        // iPad (tcW=900) escapes because its width never narrowed enough
        // to trip the controlCharacter fallback. Pairs with the explicit
        // `view.frame.origin = boundingRect.origin` write below.
        layoutManager.invalidateGlyphs(forCharacterRange: fullRange, changeInLength: 0, actualCharacterRange: nil)
        layoutManager.ensureLayout(forCharacterRange: fullRange)
        // NOTE: we restore savedContainerHeight AFTER enumeration, not here

        textStorage.enumerateAttribute(.attachment, in: fullRange, options: []) { value, range, _ in
            guard let attachment = value as? NSTextAttachment else { return }
            let glyphRange = layoutManager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            var boundingRect = layoutManager.boundingRect(forGlyphRange: glyphRange, in: self.textContainer)
            let existingView = previousViewMap[ObjectIdentifier(attachment)]
            // TextKit may return zero-width rects when layout is invalidated but
            // not yet recalculated (e.g. during refreshAttachmentViews after an
            // async image load). Fall back to the text container width so
            // attachment views are created at a usable size.
            if boundingRect.width < 1 || (containerWidth > 0 && boundingRect.width > containerWidth) {
                boundingRect.size.width = containerWidth
            }
            // [issue #117-4] Inline formulas: replace the line-level y with the
            // glyph-derived one (see inlineAttachmentOrigin). nil for every
            // other attachment kind, which keeps their existing behaviour.
            let inlineOrigin = self.inlineAttachmentOrigin(
                for: attachment, characterRange: range,
                glyphRange: glyphRange, layoutManager: layoutManager)

            let attachId = ObjectIdentifier(attachment)
            let view: UIView

            // For attachments that are cached (same object identity), reuse existing UIView
            // — but discard stale placeholders if the attachment has since loaded its content.
            if let existingView = previousViewMap[attachId] {
                var stale = false
                if let imageAttach = attachment as? ImageAttachment {
                    // File was rewritten since last load — force a rebuild
                    // so the new bitmap reaches the UIImageView inside.
                    if imageAttach.needsViewRebuild {
                        AppLogger(category: "AttachHotPath").info("[IMG][UAV] STALE-needsRebuild src=\(ImageAttachment.shortSrc(imageAttach.source)) ptr=\(attachId.hashValue & 0xFFFFFF)")
                        stale = true
                        imageAttach.needsViewRebuild = false
                    }
                    // Only (re-)arm onLoad and kick loading if the image isn't
                    // loaded yet. Otherwise a `NativeMediaImageCache` hit inside
                    // `beginLoadingIfNeeded` could fire `onLoad` synchronously
                    // and recurse back into `refreshAttachmentViews` during
                    // `updateAttachmentViews`. [AttachHang defensive guard]
                    if imageAttach.loadedImage == nil {
                        imageAttach.onLoad = { [weak self] in
                            DispatchQueue.main.async { self?.refreshAttachmentViews() }
                        }
                        imageAttach.beginLoadingIfNeeded()
                    } else {
                        // Clear any stale onLoad so a later invalidation path
                        // doesn't surface a synchronous refresh from an earlier
                        // incarnation of this attachment view.
                        imageAttach.onLoad = nil
                    }
                    if !stale && imageAttach.loadedImage != nil {
                        let hasImageView = existingView.subviews.contains(where: { $0 is UIImageView || ($0.subviews.contains(where: { $0 is UIImageView })) })
                        if !hasImageView {
                            AppLogger(category: "AttachHotPath").info("[IMG][UAV] STALE-placeholderToLoaded src=\(ImageAttachment.shortSrc(imageAttach.source)) ptr=\(attachId.hashValue & 0xFFFFFF) — replacing placeholder with image view")
                            stale = true
                        }
                    }
                } else if let videoAttach = attachment as? VideoAttachment {
                    videoAttach.onLoad = { [weak self] in
                        DispatchQueue.main.async { self?.refreshAttachmentViews() }
                    }
                    videoAttach.beginLoadingIfNeeded()
                    if videoAttach.thumbnail != nil {
                        stale = !existingView.subviews.contains(where: { $0 is UIImageView || ($0.subviews.contains(where: { $0 is UIImageView })) })
                    }
                } else if let mathAttach = attachment as? MathAttachment {
                    // [T-ios-math-invisible-dark-mode] The bitmap was dropped
                    // for a light/dark flip, so the overlay built from it holds
                    // the old (now invisible) glyph color. Must be checked
                    // BEFORE `beginRenderingIfNeeded` re-renders: once the new
                    // image lands, the `renderedImage != nil` branch below sees
                    // an existing UIImageView and concludes the view is fine.
                    if mathAttach.needsViewRebuild {
                        stale = true
                        mathAttach.needsViewRebuild = false
                    }
                    mathAttach.onLoad = { [weak self] in
                        self?.scheduleCoalescedMathRefresh()
                    }
                    mathAttach.beginRenderingIfNeeded()
                    if !stale, mathAttach.renderedImage != nil {
                        stale = !existingView.subviews.contains(where: { $0 is UIImageView || ($0.subviews.contains(where: { $0 is UIImageView })) })
                    }
                } else if let codeBlock = attachment as? CodeBlockAttachment {
                    // Code block with same object identity (content unchanged) — just track for future reuse
                    codeBlockViewCache[codeBlock.blockIndex] = existingView
                } else if let tableAttach = attachment as? TableAttachment {
                    // In-place content update during streaming markdown: the
                    // TableAttachment object identity is preserved (so the
                    // SelectableMarkdownView fast-path stays alive) but the
                    // rendered TableScrollView still holds the old rows.
                    // Force a rebuild so the user sees the new content.
                    //
                    // [T-ios-table-hscroll-stream-interrupt] EXCEPT while the
                    // user is actively scrolling THIS table horizontally.
                    // A rebuild removes the live TableScrollView from the
                    // hierarchy, which cancels the in-flight pan gesture —
                    // preservedScrollOffset keeps the position but not the
                    // gesture, so during streaming every chunk killed the drag
                    // after a few points. Defer instead: leave
                    // needsViewRebuild pending for the next streaming pass and
                    // arm a one-shot end-of-interaction callback so the
                    // rebuild still lands if the stream finishes mid-drag.
                    let liveScroll = TableAttachment.tableScrollView(in: existingView)
                    let sizeMismatch = abs(existingView.frame.size.height - boundingRect.height) > 1
                        || abs(existingView.frame.size.width - boundingRect.width) > 1
                    if let liveScroll,
                       liveScroll.isTracking || liveScroll.isDragging || liveScroll.isDecelerating {
                        if tableAttach.needsViewRebuild || sizeMismatch {
                            liveScroll.onInteractionEnded = { [weak self] in
                                DispatchQueue.main.async { self?.refreshAttachmentViews() }
                            }
                        }
                        // stale stays false — reuse the live view this pass.
                    } else if tableAttach.needsViewRebuild {
                        stale = true
                        tableAttach.needsViewRebuild = false
                    } else if sizeMismatch {
                        // [TableFrameFix] Even when `needsViewRebuild` is
                        // false (because the previous stale path already
                        // consumed it), the cached TableScrollView's inner
                        // stack/cells are sized against an out-of-date
                        // (typically smaller) layout. Trigger a rebuild
                        // whenever the existing view's size disagrees with
                        // the typesetter's boundingRect.
                        stale = true
                    }
                }

                if stale {
                    // Content loaded since last render — recreate the view
                    existingView.removeFromSuperview()
                    if let imageAttach = attachment as? ImageAttachment {
                        AppLogger(category: "AttachHotPath").info("[IMG][UAV] RECREATE src=\(ImageAttachment.shortSrc(imageAttach.source)) ptr=\(attachId.hashValue & 0xFFFFFF) — same attachment, but stale view → makeView")
                        view = imageAttach.makeView(width: boundingRect.width)
                    } else if let videoAttach = attachment as? VideoAttachment {
                        view = videoAttach.makeView(width: boundingRect.width)
                    } else if let mathAttach = attachment as? MathAttachment {
                        view = mathAttach.makeView(width: boundingRect.width)
                    } else if let tableAttach = attachment as? TableAttachment {
                        // Belt-and-suspenders capture of the live position
                        // right before teardown (onUserScroll normally keeps
                        // preservedScrollOffset current). Only when x > 0 —
                        // a transient 0 here (restore not yet applied) must
                        // not clobber the user's real preserved position.
                        if let oldScroll = TableAttachment.tableScrollView(in: existingView),
                           oldScroll.contentOffset.x > 0 {
                            tableAttach.preservedScrollOffset = oldScroll.contentOffset
                        }
                        // Re-wire onOpenURL on the new view (mirrors the new-attachment branch below).
                        tableAttach.onOpenURL = { [weak self] url in
                            if let coordinator = self?.delegate as? SelectableMarkdownView.Coordinator,
                               let openURL = coordinator.openURL {
                                openURL(url)
                            } else {
                                UIApplication.shared.open(url)
                            }
                        }
                        view = tableAttach.makeView(width: boundingRect.width)
                    } else {
                        view = existingView
                    }
                } else {
                    // Reuse: just update position and size
                    if let imageAttach = attachment as? ImageAttachment {
                        AppLogger(category: "AttachHotPath").info("[IMG][UAV] REUSE src=\(ImageAttachment.shortSrc(imageAttach.source)) ptr=\(attachId.hashValue & 0xFFFFFF) — view kept, only frame updated")
                    }
                    view = existingView
                }
                reusedIds.insert(attachId)
                view.frame = CGRect(origin: inlineOrigin ?? boundingRect.origin, size: view.frame.size)
            } else if let codeBlock = attachment as? CodeBlockAttachment {
                // New attachment object (content changed or first time).
                // Try to reuse the previous view at the same index via updateExistingView.
                if let previousView = codeBlockViewCache[codeBlock.blockIndex] {
                    codeBlock.updateExistingView(previousView)
                    view = previousView
                    // Trust the height computed by updateExistingView from the
                    // real code content. TextKit's boundingRect can be stale
                    // (glyph layout cached against a clamped textContainer
                    // height) and would otherwise collapse the view to the
                    // previous, smaller size.
                    view.frame = CGRect(
                        origin: boundingRect.origin,
                        size: CGSize(width: boundingRect.width, height: view.frame.size.height)
                    )
                } else {
                    view = codeBlock.makeView(width: boundingRect.width)
                }
                codeBlockViewCache[codeBlock.blockIndex] = view
            } else if let table = attachment as? TableAttachment {
                // Re-wire every render: the prior closure may have captured a
                // since-deallocated coordinator (`[weak self] → nil`), in which
                // case taps fell through to UIApplication.open and minis://
                // links failed silently. Closure assignment is cheap.
                table.onOpenURL = { [weak self] url in
                    if let coordinator = self?.delegate as? SelectableMarkdownView.Coordinator,
                       let openURL = coordinator.openURL {
                        openURL(url)
                    } else {
                        UIApplication.shared.open(url)
                    }
                }
                view = table.makeView(width: boundingRect.width)
            } else if let thematicBreak = attachment as? ThematicBreakAttachment {
                view = thematicBreak.makeView(width: boundingRect.width)
            } else if let imageAttach = attachment as? ImageAttachment {
                AppLogger(category: "AttachHotPath").info("[IMG][UAV] NEW src=\(ImageAttachment.shortSrc(imageAttach.source)) ptr=\(attachId.hashValue & 0xFFFFFF) — no previous view (first render OR ObjectIdentifier changed) → makeView")
                // Same guard as the reused-attachment branch above: only arm
                // onLoad when the image isn't already loaded, so a memory-cache
                // hit during beginLoadingIfNeeded can't synchronously recurse
                // into refreshAttachmentViews. [AttachHang defensive guard]
                if imageAttach.loadedImage == nil {
                    imageAttach.onLoad = { [weak self] in
                        DispatchQueue.main.async { self?.refreshAttachmentViews() }
                    }
                    imageAttach.beginLoadingIfNeeded()
                } else {
                    imageAttach.onLoad = nil
                }
                view = imageAttach.makeView(width: boundingRect.width)
            } else if let videoAttach = attachment as? VideoAttachment {
                videoAttach.onLoad = { [weak self] in
                    DispatchQueue.main.async { self?.refreshAttachmentViews() }
                }
                videoAttach.beginLoadingIfNeeded()
                view = videoAttach.makeView(width: boundingRect.width)
            } else if let audioAttach = attachment as? AudioAttachment {
                view = audioAttach.makeView(width: boundingRect.width)
            } else if let mathAttach = attachment as? MathAttachment {
                mathAttach.onLoad = { [weak self] in
                    self?.scheduleCoalescedMathRefresh()
                }
                mathAttach.beginRenderingIfNeeded()
                view = mathAttach.makeView(width: boundingRect.width)
            } else {
                return
            }

            // [T-attachment-zero-origin 2026-05-23] Per-attachment retry: even
            // after the up-front invalidateGlyphs(fullRange), TextKit can still
            // return boundingRect.origin == .zero on the first query for an
            // attachment glyph in a narrow textContainer (iPhone tcW≈370 +
            // CJK paragraph). The diagnostic experiment showed a SECOND
            // invalidate+ensureLayout consistently returns the real origin
            // (e.g. (0, 99.4, 369x254)). Pinning a fresh attachment view at
            // (0,0) overlays the cell's first text line, and the
            // layoutSubviews fallback (line ~4677) refuses to move it
            // because both the cached origin and boundingRect.origin are
            // zero — so the only chance to land it correctly is right here.
            // [issue #117-4] Re-derive the inline origin after a retry: the
            // retry re-runs layout, so the value computed before it describes
            // a superseded glyph position.
            var resolvedInlineOrigin = inlineOrigin
            if boundingRect.origin == .zero {
                layoutManager.invalidateGlyphs(forCharacterRange: range, changeInLength: 0, actualCharacterRange: nil)
                layoutManager.ensureLayout(forCharacterRange: NSRange(location: 0, length: textStorage.length))
                let retryGlyphRange = layoutManager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
                let retry = layoutManager.boundingRect(forGlyphRange: retryGlyphRange, in: self.textContainer)
                if retry.origin != .zero {
                    boundingRect = retry
                    resolvedInlineOrigin = self.inlineAttachmentOrigin(
                        for: attachment, characterRange: range,
                        glyphRange: retryGlyphRange, layoutManager: layoutManager)
                }
            }
            view.frame.origin = resolvedInlineOrigin ?? boundingRect.origin
            if view.superview !== self {
                self.addSubview(view)
            }
            newViews.append(view)
            newViewMap[attachId] = view
        }

        // Remove views that are no longer present.
        // Views reused via codeBlockViewCache may appear under a new ObjectIdentifier
        // (content changed → new attachment), so also check newViewMap.values before removing.
        let newViewSet = Set(newViewMap.values.map { ObjectIdentifier($0) })
        for (id, view) in previousViewMap where !reusedIds.contains(id) && newViewMap[id] == nil {
            if newViewSet.contains(ObjectIdentifier(view)) { continue }
            view.removeFromSuperview()
        }

        // Restore textContainer height after all boundingRect queries are done.
        // Only when it actually changed — see layoutSubviews for why we avoid
        // redundant setSize: calls.
        if self.textContainer.size.height != savedContainerHeight {
            self.textContainer.size.height = savedContainerHeight
        }

        attachmentViews = newViews
        attachmentViewMap = newViewMap
        // [AttachHotPath] Refresh dedupe fingerprint so the next UAV call
        // can short-circuit when storage / width / view count are unchanged.
        if let storage = self.textStorage as? NSTextStorage {
            lastUAVStorageLen = storage.length
            // [TableDedupBug] Snapshot the current sum of TableAttachment
            // contentGeneration so the next dedupe check can detect a
            // streaming `update()` that didn't change storage.length.
            var sum: UInt64 = 0
            storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { v, _, _ in
                if let table = v as? TableAttachment { sum &+= table.contentGeneration }
            }
            lastUAVTableGenSum = sum
        }
        lastUAVWidth = self.textContainer.size.width
        lastUAVViewCount = newViewMap.count
        // Force redraw so blockquote bars are painted with correct attachment
        // exclusion rects. draw() may fire before attachmentViews are populated,
        // causing bars to overlap tables on initial layout.
        setNeedsDisplay()
        #if DEBUG
        // [T-attachment-zero-origin] Capture immediately after attachment views
        // have been rebuilt — async image/video load completes also lands here
        // (via refreshAttachmentViews), so this is the most likely site where
        // table container frame gets reset to (0,0).
        MessageListSnapshotCollector.captureIfEnabled(
            from: self,
            trigger: "mtv.updateAttachmentViews",
            triggerDetail: String(format: "%p", Int(bitPattern: Unmanaged.passUnretained(self).toOpaque()))
        )
        #endif
    }

    /// Re-layout attachment views after an async image/video load completes.
    ///
    /// [T-ios-reuse-cachewipe] Method B — burst coalescing. A cell holding N
    /// LaTeX formulas gets N separate completion callbacks, and each one
    /// re-typesets the whole storage and invalidates the cell size. Field logs
    /// showed 7 height rewrites inside 200ms oscillating 7733↔8066 without
    /// converging, because every commit landed while later formulas were still
    /// rendering. Coalesce a burst into a single commit on the next runloop
    /// turn: correctness is unaffected (the work is idempotent — it rebuilds
    /// from current state, it does not accumulate), and the cell now resizes
    /// once, after the burst, instead of once per formula.
    private func refreshAttachmentViews() {
        guard !isCoalescingRefresh else {
            coalescedRefreshPending = true
            return
        }
        isCoalescingRefresh = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.isCoalescingRefresh = false
            let hadBurst = self.coalescedRefreshPending
            self.coalescedRefreshPending = false
            if hadBurst {
                AppLogger(category: "AttachHotPath").info("[REFRESH] burst coalesced — collapsed into a single updateAttachmentViews + invalidateCellSize")
            }
            self.performRefreshAttachmentViews()
        }
    }

    private func performRefreshAttachmentViews() {
        AppLogger(category: "AttachHotPath").info("[REFRESH] entry — async media-load callback fired (image/video/math finished loading); will run updateAttachmentViews + invalidateCellSize")
        guard window != nil else {
            // Detached from window — defer the refresh until re-attach so an
            // async image load that completed mid-recycle still grows the
            // cell. Replayed in `didMoveToWindow`. (T-multi-image-last
            // d8a51f73)
            pendingRefreshAfterWindowAttach = true
            return
        }
        // Re-entry guard: `invalidateCellSizeIfNeeded` below triggers
        // `collectionViewLayout.invalidateLayout()`, which can synchronously
        // re-enter `layoutSubviews` → `refreshAttachmentViews` if the cell's
        // width changed. [AttachHang defensive guard]
        if isRefreshingAttachments {
            reentryGuardHits &+= 1
            attachLogger.warning("[AttachHang][Refresh] RE-ENTRY BLOCKED hits=\(self.reentryGuardHits) window=\(self.window != nil) attachmentViews=\(self.attachmentViews.count)")
            return
        }
        isRefreshingAttachments = true
        defer { isRefreshingAttachments = false }
        // [T-table-zero-y 2026-05-23] When an async-loaded attachment (Video
        // thumbnail / Image) finishes loading, its attachmentBounds height
        // changes (e.g. Video 200pt placeholder → 231pt thumbnail-aware). We
        // need (a) the typesetter to re-query attachmentBounds and (b) the
        // cell to grow before the typesetter can fit the new layout.
        //
        // (a) invalidate the layout so ensureLayout will re-typeset.
        if let layoutManager = self.layoutManager as? MinisLayoutManager,
           let textStorage = self.textStorage as? NSTextStorage {
            let full = NSRange(location: 0, length: textStorage.length)
            layoutManager.invalidateLayout(forCharacterRange: full, actualCharacterRange: nil)
            // [T-ios-math-height-measured-from-placeholders] Invalidating alone
            // is NOT enough — it only MARKS the range dirty. TextKit re-typesets
            // lazily, so the very next `systemLayoutSizeFitting` can still be
            // answered from the previous layout, i.e. with every formula still
            // contributing its 44pt (block) / 20pt (inline) PLACEHOLDER height
            // instead of its rendered size.
            //
            // Device evidence for this being the live path: on a build where the
            // scheduler logged `burst done rendered=42/42`, every [MemoWrite]
            // AFTER that line still recorded h=9501.5 while the text canvas
            // measured 11433.5 — a 1931.5pt (17%) deficit that no amount of
            // cache invalidation moved. On the field device the same shape was
            // 6166 vs 7154.7 across 31 display formulas: 988.7 / 31 = 31.9pt
            // short EACH, i.e. a rendered display formula (~76pt) being counted
            // as the 44pt placeholder.
            //
            // Forcing the re-typeset here means the measure that follows sees
            // the real `attachmentBounds`. Cost is bounded: this runs on the
            // coalesced async-media callback (at most one per runloop tick via
            // `scheduleCoalescedMathRefresh`), not per formula.
            if let container = self.textContainer as NSTextContainer? {
                layoutManager.ensureLayout(for: container)
            }
        }
        // (b) reset the sizeThatFits fingerprint so invalidateCellSizeIfNeeded
        // below cannot DEDUPE-SKIP. textStorage.length and measureWidth are
        // unchanged after Video.onLoad (the Video glyph is still 1 char), but
        // the attachment's intrinsic height changed — we MUST re-measure. The
        // cascade is: sizeThatFits → newH includes the larger Video → write
        // coordinator cache → collectionView self-sizing → cell grows →
        // live textView.bounds grows → tcH grows (UIKit clamps tcH to
        // bounds.h − inset) → subsequent layoutSubviews can fit the new
        // attachment height inside the container.
        lastSizedStorageLen = -1
        lastSizedWidth = -1
        lastComputedHeight = 0

        // [T-ios-refresh-teardown-guard] Only tear the attachment views down
        // when the CONTENT actually changed. Previously this ran
        // unconditionally on every async media-load callback.
        //
        // Why the unconditional teardown is harmful: with hundreds of formulas
        // each completing asynchronously (see `scheduleCoalescedMathRefresh`),
        // this path fires repeatedly — the field log shows 4 full
        // teardown+rebuild cycles inside ONE second. Each cycle destroys every
        // attachment view and recreates it, so any pass that lands while the
        // layout is mid-flight republishes every attachment at a freshly
        // computed origin. That is the window in which the reported overlap
        // (assistant header / tool capsule / attachment card drawn across the
        // prose) becomes possible.
        //
        // Why skipping the teardown is SAFE for the growth case — which is the
        // regression this must not cause ("image/video/math loads but the cell
        // never grows"):
        //   * `updateAttachmentViews` already REUSES and REPOSITIONS existing
        //     views through `previousViewMap` (see the `existingView` branches)
        //     and removes orphans at the end of that same pass. The teardown
        //     only DEFEATS that reuse by emptying the map first.
        //   * The three `lastSized*` resets above are untouched, so
        //     `invalidateCellSizeIfNeeded` below still cannot dedupe-skip and
        //     the cell still re-measures and grows. Growth comes from those
        //     resets plus the layoutManager invalidation, NOT from destroying
        //     subviews.
        //   * The UAV dedupe fingerprint is still reset, so the reposition pass
        //     runs in full rather than early-exiting.
        //
        // The discriminator is deliberately the same signal the rest of this
        // class already trusts: an async media load changes NEITHER
        // `storage.length` (the attachment is still a single
        // NSAttachmentCharacter) NOR the container width — only the
        // attachment's intrinsic size. So "same length, same width" means
        // "same content, a resource just finished loading" and the cheap
        // in-place path is correct. Anything else (content rewritten, width
        // changed, first pass) still takes the full teardown.
        let storageLen = (self.textStorage as? NSTextStorage)?.length ?? -1
        let widthNow = self.textContainer.size.width
        let sameContent = storageLen >= 0
            && storageLen == lastRefreshStorageLen
            && abs(widthNow - lastRefreshWidth) < 0.5
            && !attachmentViewMap.isEmpty
        lastRefreshStorageLen = storageLen
        lastRefreshWidth = widthNow

        if sameContent {
            AppLogger(category: "AttachHotPath").info("[REFRESH] in-place — same content (len=\(storageLen) w=\(String(format: "%.0f", widthNow))), repositioning \(self.attachmentViewMap.count) attachment view(s) without teardown")
        } else {
            // Remove old attachment subviews before clearing tracking state,
            // otherwise updateAttachmentViews sees an empty previousViewMap
            // and cannot clean up orphaned views — causing duplication/overlap.
            for view in attachmentViews { view.removeFromSuperview() }
            attachmentViews.removeAll()
            attachmentViewMap.removeAll()
            codeBlockViewCache.removeAll()
        }
        // Reset dedupe fingerprint so the next updateAttachmentViews fully runs.
        // Done in BOTH branches: the in-place path still needs the reposition
        // pass to run rather than early-exit on an unchanged fingerprint.
        lastUAVStorageLen = -1
        lastUAVWidth = -1
        lastUAVViewCount = -1
        updateAttachmentViews()
        // [T-ios-memo-key-ignores-render-state] Drop this block's memoised
        // height. This callback IS the moment an async attachment (math /
        // image) finished rendering and the block's true height changed, and it
        // was the one place on that path that never touched the memo — the
        // height stored before the render stayed authoritative and got seeded
        // back onto the cell on every later reuse.
        //
        // Belt-and-braces with the render-qualified key: the qualification
        // means the stale entry can no longer be FOUND, this makes sure it is
        // not merely orphaned in the dictionary. Cheap — one dictionary removal
        // on a callback that already does a full attachment reposition.
        if let cell = enclosingSelfSizingCell(),
           let cv = cell.superview as? UICollectionView,
           let layout = cv.collectionViewLayout as? MessageListLayout,
           let key = cell.contentKey {
            layout.invalidateMemo(forKey: SelfSizingCell.renderQualifiedKey(key, for: cell))
            layout.invalidateMemo(forKey: key)
        }
        // [T-ios-math-height-measured-from-placeholders] Drop the SwiftUI
        // representable's own size cache too.
        //
        // `sizeThatFits` memoises into `coord.cachedSizes[widthKey]`, keyed on
        // (markdown character count, width bucket) — and until now that cache
        // was only ever cleared on a WIDTH CHANGE. An async math render changes
        // the height while leaving both key components identical, so a height
        // computed from 44pt/20pt placeholders stayed servable forever and was
        // handed back on the next reuse.
        //
        // This is the same class of bug as the layout memo fixed in 22df168cf,
        // one level up: character count cannot see a formula rendering. It is
        // the route that survived that fix — measured on device as a cell still
        // reading 1462 against a 2089 canvas (+627) after scrolling, even
        // though the first display had been corrected to −4.5.
        if let coord = self.delegate as? SelectableMarkdownView.Coordinator {
            coord.cachedSizes.removeAll(keepingCapacity: true)
            coord.cachedSizeMarkdownCount = -1
        }
        // invalidateIntrinsicContentSize() alone is NOT enough for
        // UICollectionView self-sizing — it marks the intrinsic size dirty
        // but doesn't trigger preferredLayoutAttributesFitting. We must
        // go through invalidateCellSizeIfNeeded() which clears the
        // SelfSizingCell cache and invalidates the collection view layout.
        invalidateCellSizeIfNeeded()
    }

    /// [T-ios-memo-key-ignores-render-state] Walk up to the owning
    /// `SelfSizingCell`, if this text view is hosted in one.
    private func enclosingSelfSizingCell() -> SelfSizingCell? {
        var v: UIView? = self
        while let cur = v {
            if let cell = cur as? SelfSizingCell { return cell }
            v = cur.superview
        }
        return nil
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            // Release CALayer backing stores when removed from view tree
            layer.contents = nil
            for sub in attachmentViews {
                sub.layer.contents = nil
            }
        } else {
            // Replay any refresh dropped while we were detached. The
            // canonical case is the LAST image of a multi-image message
            // finishing its async load between scroll-out and scroll-in:
            // without this replay the cell stays at its pre-load size,
            // clipping the final image. Async so the rebind / collection
            // view's own layout pass settles first. (T-multi-image-last
            // d8a51f73)
            if pendingRefreshAfterWindowAttach {
                pendingRefreshAfterWindowAttach = false
                DispatchQueue.main.async { [weak self] in
                    self?.refreshAttachmentViews()
                }
            }
            // [T-ios-defer-debt-offscreen] Same replay for an unpaid HEIGHT
            // correction. The debt is created in `invalidateCellSizeIfNeeded`
            // when `deferSelfSizing` is active, and it has exactly two
            // consumers today:
            //
            //   1. `armDeferredRemeasureBackstop` — gives up after
            //      `maxDeferredRemeasureAttempts` (~6s of continuous drag) and
            //      explicitly hands the debt to "the settle hook";
            //   2. `settleAfterInteraction` — but that walks
            //      `cv.visibleCells` ONLY.
            //
            // So a cell that owes a correction and scrolls OFF-SCREEN before
            // the drag settles is seen by neither: the backstop has bowed out,
            // and settle cannot reach a view that is no longer a visible cell.
            // `deferredCorrectionPending` is cleared only where the debt is
            // actually paid (both sites are inside
            // `invalidateCellSizeIfNeeded`), so the flag correctly survives the
            // recycle — it just had nobody to act on it until now.
            //
            // This is the same shape as `pendingRefreshAfterWindowAttach`
            // directly above (T-multi-image-last d8a51f73), which already
            // replays a dropped async-load refresh on re-attach; the height
            // debt simply never got its counterpart. Async for the same reason:
            // let the rebind / collection view layout pass settle first.
            //
            // Cost on the happy path is one Bool test per re-attach.
            if deferredCorrectionPending {
                DispatchQueue.main.async { [weak self] in
                    self?.consumeDeferredCorrectionIfNeeded()
                }
            }
        }
    }

    // MARK: Layout

    override func layoutSubviews() {
        // TEMP(2026-05-13) — layoutSubviews slow-path probe. Most layout
        // work is fast; emit a direct-write line when a single
        // layoutSubviews pass takes >=50ms so a watchdog hang can be
        // attributed to a specific text view + its content.
        let layoutStart = CACurrentMediaTime()
        defer {
            let elapsed = (CACurrentMediaTime() - layoutStart) * 1000
            if elapsed >= 50 {
                let attrLen = self.textStorage.length
                let head = attrLen > 0
                    ? (self.textStorage.string as NSString)
                        .substring(to: min(40, attrLen))
                        .replacingOccurrences(of: "\n", with: "⏎")
                    : ""
                let ptr = String(format: "%p", Int(bitPattern: Unmanaged.passUnretained(self).toOpaque()))
                LoggingManager.shared.writeRawLine(
                    category: "MarkdownLayout",
                    level: "WARN",
                    message: "[HangFix][LayoutSubviews] 💀 \(String(format: "%.0f", elapsed))ms tv=\(ptr) attrLen=\(attrLen) bounds=\(String(format: "%.0fx%.0f", self.bounds.width, self.bounds.height)) head=\"\(head)\""
                )
                LoggingManager.shared.flush()
            }
        }
        super.layoutSubviews()

        // UIKit clamps textContainer.size.height to the view's bounds during
        // layout. For a non-scrolling text view (isScrollEnabled=false), this
        // causes NSLayoutManager to exclude text that extends beyond the
        // current bounds, making trailing paragraphs invisible. Reset to
        // unconstrained so the full text is always laid out and drawn.
        //
        // Only assign when the current value is NOT already unconstrained.
        // Unconditional assignment goes through `-[NSTextContainer setSize:]`
        // → `textContainerChangedGeometry:` → `_invalidateLayoutForExtended
        // CharacterRange:` → `_fillLayoutHoleAtIndex:` even when the value
        // is identical, because UIKit doesn't equality-check size on
        // NSTextContainer. In the BoundsChangeFading path that's the
        // recursion seed for the watchdog hang seen in
        // Minis-2026-05-13-084827.ips: every layout pass re-triggers a full
        // fillLayoutHole on tables, which calls back into attachmentBounds,
        // which re-enters typesetting.
        if textContainer.size.height < CGFloat.greatestFiniteMagnitude {
            textContainer.size.height = CGFloat.greatestFiniteMagnitude
        }

        let currentWidth = textContainer.size.width

        // If the textContainer width changed (rotation, size class change, etc.),
        // fully rebuild attachment views so they get the new width via makeView(width:).
        // Use a 0.5pt threshold — matching the sizeThatFits-path guard in
        // `SelectableMarkdownView.sizeThatFits` — so sub-pt float jitter from
        // cell self-sizing doesn't repeatedly kick `refreshAttachmentViews`.
        let widthDelta = abs(currentWidth - lastLayoutWidth)
        if currentWidth > 0 && widthDelta > 0.5 {
            lastLayoutWidth = currentWidth
            // [RotationFix] On a real width change (rotation / size class), the
            // Coordinator's per-width size cache may hold a stale entry for
            // this width that was written before content settled (e.g. while
            // a table attachment was still computing). Clearing forces the
            // next sizeThatFits to recompute, which is required for the
            // trailing-paragraph truncation seen on rotation back to portrait.
            // Also reset the invalidateCellSizeIfNeeded fingerprint so it
            // doesn't short-circuit when the storage length is unchanged.
            if let coord = (self.delegate as? SelectableMarkdownView.Coordinator) {
                coord.cachedSizes.removeAll(keepingCapacity: true)
            }
            lastSizedStorageLen = -1
            lastSizedWidth = -1
            // [RotationTableFix] Reset every TableAttachment's cached layout so
            // `computeLayout`'s "reuse cached columnWidths at new width"
            // branch doesn't keep returning the old portrait-width columns
            // when the bubble has expanded for the new orientation. Without
            // this, the rendered table view sticks at the pre-rotation width.
            // Also invalidate the layoutManager for each attachment's char
            // range so ensureLayout asks for fresh `attachmentBounds`.
            if let storage = self.textStorage as? NSTextStorage, storage.length > 0 {
                var ranges: [NSRange] = []
                storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, range, _ in
                    if let table = value as? TableAttachment {
                        table.invalidateCachedLayoutForWidthChange()
                        ranges.append(range)
                    }
                }
                if !ranges.isEmpty, let lm = self.layoutManager as? MinisLayoutManager {
                    for r in ranges {
                        lm.invalidateLayout(forCharacterRange: r, actualCharacterRange: nil)
                    }
                }
            }
            if !attachmentViews.isEmpty {
                refreshAttachmentViews()
            } else if hasUnrenderedAttachments {
                // Width just became available (e.g. UIHostingConfiguration finished layout)
                // but updateAttachmentViews was skipped earlier because width was 0.
                updateAttachmentViews()
                invalidateIntrinsicContentSize()
                invalidateCellSizeIfNeeded()
            } else {
                // No attachments — still need a fresh measurement so the cell
                // resizes for the new width. Without this, attachment-free
                // messages keep their pre-rotation height and clip trailing
                // lines.
                invalidateIntrinsicContentSize()
                invalidateCellSizeIfNeeded()
            }
            return
        }

        // Width unchanged — just re-position attachment views so their origins
        // match the freshly computed glyph bounding rects.  This corrects the
        // first-load case where updateAttachmentViews() runs before sizeThatFits
        // has set the correct textContainer size.
        guard !attachmentViews.isEmpty,
              let layoutManager = self.layoutManager as? MinisLayoutManager,
              let textStorage = self.textStorage as? NSTextStorage else { return }
        let fullRange = NSRange(location: 0, length: textStorage.length)

        // textContainer.size.height is already unconstrained (set above after
        // super.layoutSubviews), so boundingRect queries return correct positions
        // for all attachments including those near the end of the text.
        textStorage.enumerateAttribute(.attachment, in: fullRange, options: []) { value, range, _ in
            guard let attachment = value as? NSTextAttachment else { return }
            let attachId = ObjectIdentifier(attachment)
            guard let view = attachmentViewMap[attachId] else { return }
            let glyphRange = layoutManager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            var boundingRect = layoutManager.boundingRect(forGlyphRange: glyphRange, in: self.textContainer)
            // [T-attachment-zero-origin 2026-05-23] Mirror the per-attachment
            // retry from updateAttachmentViews. If TextKit returns
            // boundingRect.origin == .zero here (stale controlCharacter glyph
            // property), an invalidateGlyphs + ensureLayout consistently
            // yields the real origin on the second query.
            var inlineOrigin = self.inlineAttachmentOrigin(
                for: attachment, characterRange: range,
                glyphRange: glyphRange, layoutManager: layoutManager)
            if boundingRect.origin == .zero {
                layoutManager.invalidateGlyphs(forCharacterRange: range, changeInLength: 0, actualCharacterRange: nil)
                layoutManager.ensureLayout(forCharacterRange: NSRange(location: 0, length: textStorage.length))
                let retryGlyphRange = layoutManager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
                let retry = layoutManager.boundingRect(forGlyphRange: retryGlyphRange, in: self.textContainer)
                if retry.origin != .zero {
                    boundingRect = retry
                    // [issue #117-4] Re-derive after the relayout — see the
                    // matching comment in updateAttachmentViews.
                    inlineOrigin = self.inlineAttachmentOrigin(
                        for: attachment, characterRange: range,
                        glyphRange: retryGlyphRange, layoutManager: layoutManager)
                }
            }
            // Only reposition if boundingRect has a valid origin (non-zero rect).
            // A zero rect means layout hasn't been computed yet — keep current position.
            // [issue #117-4] The gate stays keyed on boundingRect (the
            // "is layout ready" signal); only the value written changes.
            if boundingRect.origin != .zero || view.frame.origin == .zero {
                let target = inlineOrigin ?? boundingRect.origin
                if view.frame.origin != target {
                    view.frame.origin = target
                }
            }
        }
        #if DEBUG
        // [T-attachment-zero-origin] Capture render snapshot — disabled by default.
        let msgId = (self.delegate as? SelectableMarkdownView.Coordinator)?.renderer.messageId?.uuidString.prefix(8)
        MarkdownSnapshotCollector.captureIfEnabled(
            textView: self,
            phase: "afterLayoutSubviews",
            messageId: msgId.map(String.init),
            isMeasureView: false
        )
        // Also: full message-list capture so we can see the entire collection
        // state at the same moment a cell's textView finished layout.
        MessageListSnapshotCollector.captureIfEnabled(
            from: self,
            trigger: "mtv.layoutSubviews",
            triggerDetail: String(format: "%p", Int(bitPattern: Unmanaged.passUnretained(self).toOpaque()))
        )
        #endif
    }

    // MARK: Cell Self-Sizing Invalidation

    /// Called after content changes to detect height growth and invalidate the
    /// parent UICollectionView cell's self-sizing so the layout re-measures it.
    /// Without this, UIHostingConfiguration may not propagate height changes
    /// caused by nested @ObservedObject updates (e.g. AssistantBlock.content
    /// growing from 1 line to 2 lines during streaming).
    private let cellSizeLogger = AppLogger(category: "CellSize")

    /// Sum of `TableAttachment.contentGeneration` over every table attachment
    /// currently in `textStorage`, PLUS every `CodeBlockAttachment`'s
    /// `contentFingerprint`. Used as part of the cell-size dedup fingerprint
    /// so in-place attachment mutations defeat the early-exit even when
    /// textStorage.length is constant.
    ///
    /// [CodeGenDedup] Code blocks stream inside a single NSAttachmentCharacter
    /// exactly like tables: each chunk swaps in a fresh CodeBlockAttachment
    /// (new content hash) while storage length and width stay fixed, so the
    /// (storageLen, width, tableGenSum) fingerprint matched on every chunk and
    /// [SKIP-DEDUPE] pinned the host cell at its pre-stream height — the code
    /// view kept growing past the cell bounds and overlapped the neighbouring
    /// cell for the whole stream.
    private func currentTableGenerationSum() -> UInt64 {
        guard let storage = textStorage as? NSTextStorage, storage.length > 0 else { return 0 }
        var sum: UInt64 = 0
        storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, _, _ in
            if let table = value as? TableAttachment {
                sum &+= table.contentGeneration
            } else if let code = value as? CodeBlockAttachment {
                sum &+= UInt64(bitPattern: Int64(code.contentFingerprint))
            }
        }
        return sum
    }

    /// [T-ios-memo-key-ignores-render-state] How much vertical space this
    /// block's ASYNC attachments currently occupy, quantised to a stable
    /// integer.
    ///
    /// The height memo (`measuredHeightByContentKey`) is keyed on
    /// `block.content.count`. A LaTeX formula does not change that count when
    /// it renders — the markdown source `$P_A$` is byte-identical before and
    /// after MathJax turns it into a tall glyph — so a height measured while
    /// the formulas were still blank placeholders is stored under the SAME key
    /// as the finished layout, and is then seeded back onto the cell forever.
    /// Measured on device: cell frame 6166pt vs text canvas 7154.7pt, a 989pt
    /// (16%) deficit that never re-measured because the seed short-circuits
    /// `preferredLayoutAttributesFitting` before the real measure runs.
    ///
    /// Summing the rendered heights makes the key move exactly when the
    /// rendered geometry moves: 0 while every formula is an unrendered
    /// placeholder, non-zero and stable once they have all resolved. Rounded to
    /// whole points so sub-pixel jitter cannot churn the key.
    func asyncAttachmentRenderSignal() -> Int {
        guard let storage = textStorage as? NSTextStorage, storage.length > 0 else { return 0 }
        var total: CGFloat = 0
        storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, _, _ in
            if let math = value as? MathAttachment {
                total += math.renderedSize.height
            } else if let img = value as? ImageAttachment {
                // [T-ios-image-squish-memo-blind] Use the LOADED bitmap's
                // intrinsic height, not `img.bounds.height`. `bounds` is
                // NSTextAttachment's stored property — ImageAttachment only
                // overrides the attachmentBounds(for:) METHOD and never writes
                // the property, so it is permanently .zero and every image
                // contributed 0 here regardless of load state. The memo key
                // therefore never changed when images finished loading, and
                // MessageListLayout kept serving the height measured while the
                // images were still 200pt placeholders — the multi-image
                // "text overlaps the pictures" squish, the exact analogue of
                // the math bug this signal was built to fix (22df168cf).
                // The intrinsic height is 0 until loaded, non-zero and stable
                // once the bitmap is in (a later re-downsample changes it and
                // costs one extra re-measure, which is harmless).
                total += img.loadedImage?.size.height ?? 0
            } else if let video = value as? VideoAttachment {
                // [T-ios-video-squish] Same signal contract as images: the
                // thumbnail's intrinsic height is 0 until AVAssetImageGenerator
                // finishes, stable non-zero after. Without this branch the memo
                // key never moved when a video thumbnail landed — the exact
                // signal blindness 0d34dfa6c fixed for images, and worse here
                // because thumbnail generation is slower than image decode.
                total += video.thumbnail?.size.height ?? 0
            }
        }
        return Int(total.rounded())
    }

    func invalidateCellSizeIfNeeded() {
        guard textContainer.size.width > 1 else {
            return
        }
        // Re-entry guard: `cv.collectionViewLayout.invalidateLayout()` below
        // can synchronously re-enter `preferredLayoutAttributesFitting` →
        // `sizeThatFits` → `invalidateCellSizeIfNeeded`. Without this guard
        // a height that is still settling can spin for the full watchdog
        // window. [AttachHang defensive guard]
        if isInvalidatingCellSize {
            reentryGuardHits &+= 1
            cellSizeLogger.warning("[AttachHang][CellSize] RE-ENTRY BLOCKED hits=\(self.reentryGuardHits) tcW=\(String(format: "%.0f", self.textContainer.size.width)) lastH=\(String(format: "%.1f", self.lastComputedHeight))")
            return
        }
        // Fingerprint early-exit: when textStorage and width are unchanged
        // from the last successful measurement, the height can't have moved.
        // The full path below pays for `sizeThatFits` (~2-4ms on long
        // attributed strings) before discovering NOOP. Skipping here saves
        // that cost on every repeated call (SwiftUI reuse / didMoveToWindow
        // bursts each trigger an UAV chain that includes this).
        // [JitterFix] Measure at the actual render width (bounds.width) when
        // available. textContainer.size.width can be transiently set to the
        // outer-cell probe width (~402) by SwiftUI's preferredLayoutAttributes
        // pass — measuring at that wrong width would write a 23pt-too-small
        // height into lastComputedHeight, producing the streaming spike.
        let measureWidth: CGFloat = bounds.width > 1 ? bounds.width : textContainer.size.width
        // [TableGenDedup] Compute the sum of every TableAttachment's generation
        // counter. Streaming tables mutate rows in place without changing
        // textStorage.length, so the (storageLen, width) fingerprint alone
        // can't detect those mutations and would short-circuit the remeasure
        // — visible as a streaming table rendering only its first column
        // until the entire message stops streaming.
        let tableGenSum = currentTableGenerationSum()
        // [T-ios-defer-retry-never-consumed] A pending deferred correction must
        // NOT be swallowed by the fingerprint. The skip path left
        // `lastComputedHeight` at the stale value on purpose, so "fingerprint
        // matches" here does not mean "height is right" — it means the content
        // stopped changing (stream ended) while the height was still wrong.
        // Fall through to the real `sizeThatFits` so the correction can land.
        if lastSizedStorageLen >= 0,
           !deferredCorrectionPending,
           textStorage.length == lastSizedStorageLen,
           abs(measureWidth - lastSizedWidth) < 0.5,
           tableGenSum == lastSizedTableGenSum,
           lastComputedHeight > 0 {
            cellSizeLogger.info("[invalidateCell][SKIP-DEDUPE] storageLen=\(textStorage.length) measureW=\(String(format: "%.0f", measureWidth)) tcW=\(String(format: "%.0f", textContainer.size.width)) lastH=\(String(format: "%.1f", lastComputedHeight)) tableGen=\(tableGenSum) — fingerprint match, skipping sizeThatFits")
            return
        }
        isInvalidatingCellSize = true
        defer { isInvalidatingCellSize = false }
        let newHeight = sizeThatFits(CGSize(width: measureWidth, height: .greatestFiniteMagnitude)).height
        // sizeThatFits clamps textContainer.size.height — restore it (only
        // when actually clamped, to avoid a redundant setSize: → fillLayoutHole).
        if textContainer.size.height < CGFloat.greatestFiniteMagnitude {
            textContainer.size.height = CGFloat.greatestFiniteMagnitude
        }
        let previousHeight = lastComputedHeight
        if abs(newHeight - previousHeight) <= 1 {
            // Unchanged — refresh the fingerprint so subsequent calls can
            // SKIP-DEDUPE, exactly as before.
            lastSizedStorageLen = textStorage.length
            lastSizedWidth = measureWidth
            lastSizedTableGenSum = tableGenSum
            // [T-ios-defer-retry-never-consumed] Do NOT clear
            // `deferredCorrectionPending` here. Reaching this branch means the
            // TEXT VIEW's own height is stable, but the debt we owe is on the
            // HOST CELL: the skip path returned before `cell.clearCachedHeight()`
            // + `invalidateLayout()`, so SelfSizingCell may still be serving the
            // pre-correction height even though this view now measures fine.
            // Clearing here is what made the first attempt at this fix a no-op —
            // the 0.6s backstop timer landed in this branch ~250ms before settle,
            // dropped the flag, and every subsequent `[SettleConsume]` reported
            // `pending=0` while the cell stayed wrong (device trace 11:25:50).
            // The debt is settled only where the correction is actually applied.
            if deferredCorrectionPending {
                // Clear the debt ONLY if the correction actually landed.
                // `applyCellCorrection` refuses while deferSelfSizing is open,
                // because its `invalidateLayout()` mid-scroll is exactly what
                // 879ce867's skip guards against. When it refuses, keep the flag
                // so the settle hook consumes it the moment the window closes —
                // the same "wait, don't drop it" contract the backstop timer
                // follows. Dropping it here regardless is what made the first
                // version of this fix a silent no-op.
                if applyCellCorrection() {
                    cellSizeLogger.info("[invalidateCell] deferred debt CONSUMED — view height stable at \(String(format: "%.1f", newHeight)), cell invalidated")
                    deferredCorrectionPending = false
                } else {
                    cellSizeLogger.info("[invalidateCell] deferred debt HELD — view height stable at \(String(format: "%.1f", newHeight)) but deferSelfSizing still open; leaving it to settle")
                    armDeferredRemeasureBackstop()
                }
            }
            return
        }
        let delta = newHeight - previousHeight

        // [T-ios-lastcell-clip-defer-lost] Height genuinely moved. Decide the
        // defer-skip BEFORE consuming the measurement. The old order refreshed
        // the dedup fingerprint + lastComputedHeight first and THEN bailed on
        // deferSelfSizing / first-measure — after which every later call hit
        // [SKIP-DEDUPE] and the correction could never fire again. Combined
        // with SelfSizingCell's unbounded same-width height cache (which never
        // re-enters SwiftUI on its own), the cell stayed frozen at the
        // too-short first-commit height and clipsToBounds cropped the tail —
        // the "last message truncated mid-sentence, survives restart" repro
        // (A71D8A0D: CTFramesetter first-commit under-measured the 839-char
        // CJK block by ~1 line vs TextKit's later authoritative measure; the
        // cold-open auto-scroll glide kept deferSelfSizing=true at exactly the
        // moment this correction tried to run).
        let cvOpt = findCollectionView()
        let layout = cvOpt?.collectionViewLayout as? MessageListLayout
        let isDeferred = layout?.deferSelfSizing ?? false

        // When deferSelfSizing is active (user scrolling), skip the full layout
        // invalidation — it causes contentSize changes that push the streaming
        // cell into other cells during deceleration. The height change will be
        // picked up naturally via preferredLayoutAttributesFitting when the
        // deferred heights are flushed after scrolling ends.
        //
        // EXCEPTION: image-attachment placeholder→loaded transition. On cold
        // enter the auto `scrollToBottom` triggers a decel (deferSelfSizing=true)
        // *while* the inline images are still placeholders at 200pt. When an
        // image resolves from the memory cache (synchronous) and refreshes
        // attachments, sizeThatFits now returns the real height (e.g. 327pt).
        // If we skip invalidation here, the cell stays frozen at the 240pt
        // placeholder height and the UIImageView — which was laid out at the
        // correct 280pt by updateAttachmentViews — is clipped by the cell's
        // bounds, producing the "bottom truncated" symptom. The condition is
        // narrow: image attachment present AND a large growth (>30pt). This
        // preserves the existing defer behaviour for streaming text growth.
        // [AttachHang image-attachment defer escape]
        if isDeferred {
            // [T-ios-video-squish] Videos join the escape: a thumbnail landing
            // mid-scroll produces the same placeholder→real large delta as an
            // image load, and it used to be swallowed here (image-only check),
            // leaving the cell pinned short with content overlapping the
            // thumbnail. Video windows are longer (AVAssetImageGenerator),
            // so mid-scroll completion is MORE likely, not less.
            let cellHasGrowableMediaAttachment: Bool = {
                var found = false
                textStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: textStorage.length), options: []) { value, _, stop in
                    if value is ImageAttachment || value is VideoAttachment {
                        found = true
                        stop.pointee = true
                    }
                }
                return found
            }()
            let isLargeGrowth = delta > 30
            if cellHasGrowableMediaAttachment && isLargeGrowth {
                cellSizeLogger.info("[AttachHang][CellSize] ALLOW through defer — media attachment placeholder→loaded growth prev=\(String(format: "%.1f", previousHeight))→new=\(String(format: "%.1f", newHeight)) delta=\(String(format: "%+.1f", delta))")
                // Fall through to the normal invalidate path below.
            } else {
                // [T-ios-lastcell-clip-defer-lost] Return WITHOUT touching the
                // fingerprint / lastComputedHeight / coord cache — the
                // correction is NOT consumed, so the next call re-measures and
                // retries. Streaming cost is unchanged: token growth mutates
                // storage length, which already defeats the fingerprint today.
                // Arm one guaranteed post-settle retry in case no further
                // measure call arrives after the glide ends.
                cellSizeLogger.info("[invalidateCell] SKIPPED — deferSelfSizing active (correction NOT consumed, delta=\(String(format: "%+.1f", delta)); will retry)")
                // [T-ios-defer-retry-never-consumed] Remember that a real
                // correction is owed. The authoritative consumer is
                // `consumeDeferredCorrectionIfNeeded()`, invoked the moment
                // `settleAfterInteraction` clears deferSelfSizing. The timer is
                // only a backstop for the mid-drag window (stream finishes while
                // the finger is still down), and it re-arms itself while that
                // window stays open — see armDeferredRemeasureBackstop.
                // [T-ios-defer-debt-offscreen] Mark WHEN the debt was created so
                // an unpaid one can be attributed later. `wasPending` shows the
                // debt is being re-owed (an earlier correction was still
                // outstanding), which is the signature of a long drag over
                // async-rendering content.
                let wasPending = deferredCorrectionPending
                cellSizeLogger.info("[DeferDebt] OWED delta=\(String(format: "%+.1f", delta)) attached=\(self.window != nil) reOwed=\(wasPending)")
                deferredCorrectionPending = true
                armDeferredRemeasureBackstop()
                return
            }
        }

        // Commit the measurement: fingerprint + authoritative height + the
        // Coordinator's sizeThatFits width-bucket cache. Streaming code blocks /
        // tables grow inside a single NSAttachmentCharacter, so `textLen` stays
        // fixed and the `(width, textLen)`-keyed cache would otherwise return
        // the previous (smaller) height, pinning the cell. We already paid for
        // a real `sizeThatFits` here; sharing the result costs zero additional
        // TextKit work and lets the next `preferredLayoutAttributesFitting`
        // probe HIT the fresh value without another full relayout (the 8s hang
        // `d0d3ffc` fixed).
        lastSizedStorageLen = textStorage.length
        lastSizedWidth = measureWidth
        lastSizedTableGenSum = tableGenSum
        lastComputedHeight = newHeight
        // [T-ios-defer-retry-never-consumed] The correction is being committed
        // for real here, so the debt is settled. Clearing it re-arms the
        // fingerprint early-exit for subsequent calls (no permanent extra cost).
        if deferredCorrectionPending {
            deferredCorrectionPending = false
            cellSizeLogger.info("[invalidateCell] DEFERRED CORRECTION CONSUMED newH=\(String(format: "%.1f", newHeight)) delta=\(String(format: "%+.1f", delta))")
        }
        if let coord = self.delegate as? SelectableMarkdownView.Coordinator {
            // [JitterFix] Key by render width (measureWidth), matching the
            // SwiftUI sizeThatFits cache key. Using textContainer.size.width
            // here would insert under whichever stale probe width was last
            // committed, missing future render-width cache lookups.
            // [T-ios-lastcell-clip-defer-lost] REPLACE the whole bucket dict
            // instead of upserting one key: stale near-width buckets (±2pt
            // probe rounding) written by the CTFramesetter fast path would
            // otherwise be copied back over this authoritative TextKit height
            // by sizeThatFits' near-width reuse, re-pinning the cell short.
            let widthKey = Int(measureWidth.rounded())
            coord.cachedSizes.removeAll(keepingCapacity: true)
            coord.cachedSizes[widthKey] = newHeight
            // [T-ios-lastcell-clip-defer-lost] Key the cache generation by the
            // RAW MARKDOWN length — sizeThatFits gates hits on
            // `bindingLen == cachedSizeMarkdownCount`, and bindingLen is the
            // raw `markdown` binding's NSString length. The old write used
            // textStorage.length (the RENDERED length), which never equals
            // bindingLen once markdown syntax is stripped — so this
            // authoritative TextKit height was permanently unreachable and the
            // correction re-measure fell back to the CTFramesetter path (the
            // short height), re-pinning the cell.
            let bindingLenForKey = rawMarkdown.isEmpty
                ? textStorage.length
                : (rawMarkdown as NSString).length
            coord.cachedSizeMarkdownCount = bindingLenForKey
        }

        // First measurement (previousHeight == 0): normally the layout queries
        // the cell naturally during the initial pass and no invalidation is
        // needed. BUT if the host cell has ALREADY committed a conflicting
        // height (cold-load: the SwiftUI sizeThatFits probe committed a
        // CTFramesetter-measured height that disagrees with this TextKit
        // measure by a line), that commit is frozen by SelfSizingCell's
        // same-width cache and MUST be invalidated here — this is the only
        // correction point. Threshold 8pt: above the ~4pt vertical padding
        // delta between the markdown view and its cell, below one text line.
        if previousHeight <= 0 {
            guard let cell = findCell() as? SelfSizingCell,
                  cell.bounds.height > 4,
                  abs(cell.bounds.height - newHeight) > 8 else { return }
            cellSizeLogger.info("[invalidateCell] FIRST-MEASURE CORRECTION cellH=\(String(format: "%.1f", cell.bounds.height)) newH=\(String(format: "%.1f", newHeight)) — cell committed a conflicting height, invalidating")
        }

        // Clear SelfSizingCell's cached height so preferredLayoutAttributesFitting
        // re-measures instead of returning the stale cached value. Without this,
        // the precalcHeight-based initial measurement (from applySnapshot) gets
        // locked in, even though the actual TextKit layout produced a different height.
        if let cell = findCell() as? SelfSizingCell {
            cell.clearCachedHeight()
        }

        cvOpt?.collectionViewLayout.invalidateLayout()
    }

    /// [T-ios-defer-retry-never-consumed] Backstop for the settle-driven
    /// consumer, and the reason the 0.6s timer is kept rather than deleted.
    ///
    /// `deferSelfSizing` has exactly one setter — `scrollViewWillBeginDragging`
    /// — and both exits of that gesture (`didEndDragging(willDecelerate:false)`
    /// and `didEndDecelerating`) run `settleAfterInteraction`, which clears the
    /// flag and immediately calls `consumeDeferredCorrectionIfNeeded()`. So the
    /// event path covers the normal case. What it does NOT cover is the window
    /// while the finger is still down: if the stream finishes mid-drag, the
    /// correction would otherwise wait for the user to lift their finger.
    ///
    /// The original timer (879ce867) fired exactly once. If it landed while the
    /// drag was still in progress it hit the same skip branch, cleared
    /// `pendingDeferredRemeasure`, and never re-armed — so the backstop silently
    /// gave up precisely when it was needed. Re-schedule instead while the
    /// window is still open, bounded so a stuck flag cannot tick forever.
    private func armDeferredRemeasureBackstop(attempt: Int = 0) {
        guard !pendingDeferredRemeasure else { return }
        guard attempt < Self.maxDeferredRemeasureAttempts else {
            cellSizeLogger.info("[invalidateCell] backstop GAVE UP after \(attempt) attempts — leaving the debt to the settle hook")
            return
        }
        pendingDeferredRemeasure = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            guard let self else { return }
            self.pendingDeferredRemeasure = false
            // Debt already paid (settle got there first, or a natural measure
            // call consumed it) — nothing to do.
            guard self.deferredCorrectionPending else { return }
            let stillDeferred = (self.findCollectionView()?.collectionViewLayout
                as? MessageListLayout)?.deferSelfSizing ?? false
            if stillDeferred {
                // Window still open: re-measuring now would just hit the same
                // skip branch. Wait another interval instead of giving up.
                self.armDeferredRemeasureBackstop(attempt: attempt + 1)
                return
            }
            self.invalidateCellSizeIfNeeded()
        }
    }

    /// Bound on `armDeferredRemeasureBackstop` re-arming (~6s of drag). Past
    /// that the settle hook is the only consumer, which is correct: a drag that
    /// long always ends in `settleAfterInteraction`.
    private static let maxDeferredRemeasureAttempts = 10

    /// [T-ios-defer-retry-never-consumed] Drop the host cell's frozen height and
    /// ask the layout to re-flow. Same two statements the normal commit path
    /// ends with, extracted so the "view height already stable, but the CELL is
    /// still stale" case can apply the correction without pretending to
    /// re-measure. That case is real: after a skip, SwiftUI may re-render the
    /// text view to the right height on its own while `SelfSizingCell` keeps
    /// serving the pre-correction value from its width-matched cache (which
    /// b4268586 made unbounded), so the cell never asks again.
    ///
    /// Returns false when the deferSelfSizing window is still open, in which
    /// case NOTHING was applied and the caller must keep the debt.
    ///
    /// The guard lives here rather than at the call site so it covers every
    /// caller, including future ones. It is not optional politeness: this method
    /// ends in `invalidateLayout()`, and 879ce867's skip exists precisely because
    /// a full layout invalidation mid-scroll "causes contentSize changes that
    /// push the streaming cell into other cells during deceleration" — i.e.
    /// running it inside the window can produce the very overlap this whole
    /// change set is removing. The `abs(delta) <= 1` caller sits ~40 lines ABOVE
    /// the `isDeferred` gate in `invalidateCellSizeIfNeeded`, so it does not
    /// inherit that protection, and `updateUIView` calls into that path on every
    /// SwiftUI re-render — including while a finger is down mid-stream.
    @discardableResult
    private func applyCellCorrection() -> Bool {
        let deferred = (findCollectionView()?.collectionViewLayout
            as? MessageListLayout)?.deferSelfSizing ?? false
        guard !deferred else { return false }
        if let cell = findCell() as? SelfSizingCell {
            cell.clearCachedHeight()
        }
        findCollectionView()?.collectionViewLayout.invalidateLayout()
        return true
    }

    /// [T-ios-defer-retry-never-consumed] Consume a correction that was skipped
    /// during a deferSelfSizing window. Called by the message list's Coordinator
    /// at settle, right after it sets `deferSelfSizing = false` — the first
    /// moment the correction can actually be applied.
    ///
    /// This is what makes the "will retry" promise real. The 0.6s timer alone
    /// could not keep it: it fires on a fixed delay that may still land inside
    /// the drag, and even when it lands correctly the fingerprint early-exit
    /// swallows it once the stream has stopped changing the text storage.
    /// `deferredCorrectionPending` bypasses that early-exit, so this call
    /// re-measures for real.
    func consumeDeferredCorrectionIfNeeded() {
        guard deferredCorrectionPending else { return }
        // [T-ios-defer-debt-offscreen] `attached` distinguishes the two
        // consumers in the log: settle (window != nil, the cell was visible)
        // vs the new re-attach replay (this view just came back on screen
        // still owing a correction — the case that previously had no consumer).
        cellSizeLogger.info("[DeferDebt] CONSUME — paying deferred correction attached=\(self.window != nil)")
        invalidateCellSizeIfNeeded()
    }

    // MARK: Blockquote Bars

    override func draw(_ rect: CGRect) {
        super.draw(rect)
        drawBlockquoteBars(in: rect)
    }

    private func drawBlockquoteBars(in rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext(),
              let textStorage = self.textStorage as? NSTextStorage,
              let layoutManager = self.layoutManager as? MinisLayoutManager else { return }

        let theme = SelectableMarkdownTheme()
        let fullRange = NSRange(location: 0, length: textStorage.length)

        // Collect contiguous blockquote spans and compute their union bounding rects.
        // Multiple attributed-string ranges may have the same depth but different styling
        // (bold, italic, etc.), so we merge adjacent ranges into one continuous bar.
        struct BarSpan {
            let depth: Int
            var rect: CGRect
        }
        var spans: [BarSpan] = []

        // Helper: process a single non-attachment character range for bar drawing.
        //
        // Uses per-line fragment `usedRect`s (not `boundingRect(forGlyphRange:)`)
        // so the resulting span excludes trailing paragraphSpacing. Without this,
        // when a blockquote is followed by a paragraph containing inline code
        // that wraps, the bar overshoots into the next paragraph.
        func addBarSpan(depth: Int, range: NSRange) {
            let glyphRange = layoutManager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            guard glyphRange.length > 0 else { return }

            var spanRect: CGRect = .null
            layoutManager.enumerateLineFragments(forGlyphRange: glyphRange) { _, usedRect, _, _, _ in
                spanRect = spanRect.isNull ? usedRect : spanRect.union(usedRect)
            }
            guard !spanRect.isNull, spanRect.height > 0 else { return }

            if let last = spans.last, last.depth == depth,
               spanRect.minY <= last.rect.maxY + 2 {
                spans[spans.count - 1].rect = last.rect.union(spanRect)
            } else {
                spans.append(BarSpan(depth: depth, rect: spanRect))
            }
        }

        textStorage.enumerateAttribute(.blockquoteDepth, in: fullRange, options: []) { value, range, _ in
            guard let depth = value as? Int, depth > 0 else { return }
            // [T-blockquote-bar-attachment-holes] Measure the WHOLE quoted
            // range, attachment characters included. This used to split the
            // range at every attachment character, which fragmented the span
            // before it was ever drawn — a quote containing only a code block
            // produced no span at all and therefore no bar. Attachment glyphs
            // occupy real line fragments inside the quote, so including them is
            // what makes the bar span the quote's true extent.
            addBarSpan(depth: depth, range: range)
        }

        // Draw each bar as ONE continuous run.
        //
        // [T-blockquote-bar-attachment-holes] This used to punch holes wherever
        // an attachment view's frame overlapped the span *on the Y axis only* —
        // it never checked whether the attachment actually intersected the bar
        // in X (x = depth*13 + 1, width 3). That broke the bar for every
        // attachment kind: a code block indents by `quoteDepth * 13` and never
        // covers the bar at all, yet still cut it; inline math and inline
        // images punched a line-height gap nowhere near the bar; and a quote
        // holding only a code block lost its bar entirely.
        //
        // No clipping is needed. Attachments are SUBVIEWS and therefore
        // composite above `draw(_:)`, so wherever one is genuinely opaque over
        // the bar it already hides it — exactly what the punch-out did — while
        // the transparent regions (a code card's 13pt left inset) now keep the
        // continuous bar they should always have had.
        //
        // The same fix was applied to MarkdownRenderView in e235132f, but that
        // view has no call sites; this is the copy the chat actually renders
        // through, which is why OpenMinis#123 stayed reproducible after it.
        for span in spans {
            let height = span.rect.maxY - span.rect.minY
            guard height > 1 else { continue }
            context.saveGState()
            context.setFillColor(theme.blockquoteBarColor.cgColor)
            for d in 1...span.depth {
                let x = CGFloat(d - 1) * 13 + 1
                let barRect = CGRect(x: x, y: span.rect.minY, width: 3, height: height)
                context.addPath(UIBezierPath(roundedRect: barRect, cornerRadius: 2).cgPath)
            }
            context.fillPath()
            context.restoreGState()
        }
    }
}

// MARK: - SelectableMarkdownView (UIViewRepresentable)

/// SwiftUI wrapper for the UITextView-based selectable Markdown renderer.
struct SelectableMarkdownView: UIViewRepresentable {
    let markdown: String
    var cachedContent: MarkdownContent?
    var cachedAttributedString: NSAttributedString?
    /// Optional owning assistant message id. When set, inline image tap
    /// gestures route to a paged gallery of all markdown-embedded images in
    /// that message. Leave nil for standalone markdown rendering.
    var messageId: UUID?
    /// Optional block identity for table slot-cache keying. In V3
    /// cell-per-block, each AssistantBlock has its own UUID — using it as
    /// the slot key guarantees uniqueness even when two tables in the same
    /// message have identical content.
    var blockId: UUID?
    /// Called when the user taps a blank (non-link, non-code) area of the text view.
    /// The CGPoint is the tap location in **window coordinates**.
    var onTapBlank: ((CGPoint) -> Void)?
    /// Called when the user picks "Copy Screenshot" from the text selection menu.
    var onCopyScreenshot: (() -> Void)?
    /// [T-selection-menu-minis-tts] "Read from Start" (whole reply) via Minis TTS.
    var onReadAloud: (() -> Void)?
    /// [T-selection-menu-minis-tts] "Read Selection" (selected text) via Minis TTS.
    var onSpeakText: ((String) -> Void)?
    @Environment(\.openURL) private var openURL

    func makeCoordinator() -> Coordinator {
        let coord = Coordinator()
        // [TableIdentityFix] When the same messageId remounts a fresh
        // Coordinator (cell reuse / SwiftUI body re-evaluation), hand the
        // new Coordinator the previously-cached renderer for that message.
        // Without this every cell reuse produces a fresh renderer →
        // fresh TableAttachment objects → layoutManager sees attachments
        // with no identity continuity and keeps the early-stream glyph
        // rects cached.
        if let mid = messageId, let existing = SelectableMarkdownView.rendererCache[mid] {
            coord.renderer = existing
        } else if let mid = messageId {
            SelectableMarkdownView.rendererCache[mid] = coord.renderer
        }
        return coord
    }

    /// [TableIdentityFix] Per-message renderer cache: keyed by `messageId`,
    /// kept across Coordinator recreations so TableAttachment identity (and
    /// the streaming `update(...)` path) survives SwiftUI body re-evaluations
    /// / cell reuses that throw away the previous Coordinator. Without this
    /// we observed up to 6 distinct Coordinators (each with its own renderer
    /// + fresh attachments) inside a single streaming response — every
    /// throwaway one writing new attachments into textStorage and leaving
    /// the layout manager with stale glyph rects for the early small-table
    /// state. The cache uses a regular dictionary because the keys are
    /// stable across the chat session and entries are cheap; in practice
    /// it grows at the rate of unique assistant messages in the live chat.
    @MainActor private static var rendererCache: [UUID: MarkdownNSRenderer] = [:]

    func makeUIView(context: Context) -> SelectableMarkdownTextView {
        let textView = SelectableMarkdownTextView()
        textView.delegate = context.coordinator
        textView.setContentHuggingPriority(.required, for: .vertical)
        textView.setContentCompressionResistancePriority(.required, for: .vertical)
        context.coordinator.lastMarkdown = ""
        context.coordinator.openURL = openURL
        textView.onTapBlank = onTapBlank
        textView.onCopyScreenshot = onCopyScreenshot
        textView.onReadAloud = onReadAloud
        textView.onSpeakText = onSpeakText
        #if DEBUG
        // [T-ios-markdown-rerender-burst] makeUIView creates a FRESH textView +
        // resets lastMarkdown="", forcing the next updateUIView through a full
        // re-render (the markdown != lastMarkdown dedup can never hit on a brand
        // new view). Log rnd (coordinator's renderer id) so we can count
        // makeUIView churn against the [RND] burst — same rnd across many
        // [MK-VIEW] => SwiftUI is re-creating the view for a reused coordinator.
        let rId = String(ObjectIdentifier(context.coordinator.renderer).hashValue & 0xFFFFFF, radix: 16)
        AppLogger(category: "MarkdownRenderer").info("[MK-VIEW] new textView rnd=\(rId)")
        #endif
        return textView
    }

    func updateUIView(_ textView: SelectableMarkdownTextView, context: Context) {
        let updateStart = CFAbsoluteTimeGetCurrent()
        context.coordinator.openURL = openURL
        textView.onTapBlank = onTapBlank
        textView.onCopyScreenshot = onCopyScreenshot
        textView.onReadAloud = onReadAloud
        textView.onSpeakText = onSpeakText

        let currentFontSize = FontSettings.shared.scaledMessage(16.5)
        let fontChanged = context.coordinator.lastFontSize != currentFontSize

        // [AttachHotPath] updateUIView entry — only log when this textView has
        // image attachments in its renderer's cache (the only scenario that can
        // trigger the streaming + image re-layout problem). Skips noise from
        // pure-text blocks.
        let imgCacheN = context.coordinator.renderer.imageAttachmentCache.count
        if imgCacheN > 0 {
            let prevLen = context.coordinator.lastMarkdown.count
            let curLen = markdown.count
            let isAppend = curLen > prevLen && markdown.hasPrefix(context.coordinator.lastMarkdown)
            AppLogger(category: "AttachHotPath").info("[UPD] entry imgCache=\(imgCacheN) prevLen=\(prevLen) curLen=\(curLen) Δ\(curLen - prevLen) appendOnly=\(isAppend ? 1 : 0) fontChanged=\(fontChanged ? 1 : 0)")
        }

        // [RotationTableFix] Detect (markdown unchanged + width changed) — the
        // rotation path. SwiftUI calls updateUIView with the textView already
        // at the new textContainer width, but `markdown` is unchanged so the
        // guard below early-returns. Without this branch the TableAttachment
        // cachedLayout never invalidates and the rendered table sticks at the
        // old portrait column widths. We invalidate just the attachment caches
        // (not a full re-render) so the next layout pass asks for fresh
        // `attachmentBounds` at the new width and makeView rebuilds the
        // SelectableTableView with the new columns.
        let _curW = textView.textContainer.size.width
        let _prevW = context.coordinator.lastRenderedWidth
        let _widthChanged = _curW > 1 && _prevW > 1 && abs(_curW - _prevW) > 0.5
        if _widthChanged && markdown == context.coordinator.lastMarkdown && !fontChanged {
            // [WordFade] Width change (rotation) re-lays-out everything; snap
            // any mid-fade words to full opacity so none stick translucent.
            context.coordinator.fadeAnimator.cancelAll()
            if let storage = textView.textStorage as? NSTextStorage, storage.length > 0 {
                var ranges: [NSRange] = []
                storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, range, _ in
                    if let table = value as? TableAttachment {
                        table.invalidateCachedLayoutForWidthChange()
                        ranges.append(range)
                    }
                }
                if !ranges.isEmpty, let lm = textView.layoutManager as? MinisLayoutManager {
                    for r in ranges {
                        lm.invalidateLayout(forCharacterRange: r, actualCharacterRange: nil)
                    }
                }
            }
            context.coordinator.cachedSizes.removeAll(keepingCapacity: true)
            textView.updateAttachmentViews()
            textView.invalidateIntrinsicContentSize()
            textView.setNeedsLayout()
            textView.invalidateCellSizeIfNeeded()
        }
        if _curW > 1 {
            context.coordinator.lastRenderedWidth = _curW
        }
        guard markdown != context.coordinator.lastMarkdown || fontChanged else {
            let shouldRecover = textView.window != nil
                && textView.needsAttachmentRecovery
                && !textView.hasScheduledRecoveryForCurrentContent

            if shouldRecover {
                textView.hasScheduledRecoveryForCurrentContent = true
                DispatchQueue.main.async { [weak textView] in
                    guard let textView, textView.window != nil else { return }
                    textView.updateAttachmentViews()
                    textView.invalidateIntrinsicContentSize()
                    textView.setNeedsDisplay()
                    textView.setNeedsLayout()
                }
            }
            // [DIAG-RENDER-1] markdown matches lastMarkdown — no re-render.
            // If the cell appears empty, it means the PREVIOUS updateUIView call
            // set lastMarkdown but didn't actually render the text (e.g. was skipped
            // due to background state, or attributedText was empty/truncated).
            if !markdown.isEmpty && (textView.attributedText?.length ?? 0) == 0 {
                AppLogger(category: "StreamDiag").info("[RENDER] ⚠️ EMPTY-DESPITE-MATCH markdown len=\(markdown.count) lastMarkdown len=\(context.coordinator.lastMarkdown.count) attrText len=\(textView.attributedText?.length ?? 0) window=\(textView.window != nil) — CELL APPEARS EMPTY")
            }
            return
        }

        // [DIAG-RENDER-2] Three paths based on application state:
        //   .background                   → defer (always; final streaming
        //                                   frame would otherwise be blank
        //                                   until SwiftUI re-evaluates)
        //   .inactive ≥ 30s               → defer (long-running .inactive is
        //                                   indistinguishable from background
        //                                   for watchdog purposes — commit
        //                                   e6efac23 fixed crashes here)
        //   .inactive < 30s               → render normally (control center
        //                                   swipe, incoming call banner,
        //                                   app-switcher peek — short enough
        //                                   that the scene-transition
        //                                   watchdog window has settled)
        //   .active                       → render normally (falls through)
        //
        // Deferred frames are stashed on the coordinator and replayed by the
        // didBecomeActive observer once applicationState is genuinely
        // `.active` again, so the expensive setAttributedText + ensureLayout
        // pass never runs while the watchdog is timing.
        let appState = UIApplication.shared.applicationState
        let inactiveDuration: TimeInterval = appState == .inactive
            ? (context.coordinator.inactiveEnteredAt.map { Date().timeIntervalSince($0) } ?? 0)
            : 0
        let shouldDefer: Bool = {
            switch appState {
            case .background: return true
            case .inactive: return inactiveDuration >= 30.0
            default: return false
            }
        }()
        if shouldDefer {
            context.coordinator.deferRender(markdown) { [weak textView] in
                guard let textView else { return }
                // Defeat the content-hash skip so the deferred frame actually
                // re-renders even if an intervening pass set the same hash.
                context.coordinator.lastRenderedContentHash = nil
                self.updateUIView(textView, context: context)
            }
            return
        }
        let oldMarkdown = context.coordinator.lastMarkdown
        context.coordinator.lastMarkdown = markdown

        if fontChanged {
            context.coordinator.renderer.updateFontSize(currentFontSize)
            context.coordinator.lastFontSize = currentFontSize
            context.coordinator.lastRenderedContentHash = nil
            // Math images are rendered at a specific font size — clear stale caches.
            context.coordinator.renderer.mathAttachmentCache.removeAll()
            textView.clearAllAttachmentViews()
        }

        // [T-ios-reuse-cachewipe] Identity of what we are about to render.
        // Prefer `blockId` — under V3 cell-per-block a single message spans
        // many text views, so `messageId` alone would report "same content"
        // for two different blocks of one message.
        let contentId = blockId ?? messageId
        let previousContentId = context.coordinator.lastContentId
        context.coordinator.lastContentId = contentId
        // True only when this view is being recycled onto DIFFERENT content.
        // `previousContentId == nil` is a first render, not a reuse.
        let isDifferentContent = contentId != nil
            && previousContentId != nil
            && contentId != previousContentId

        // [T-ios-reuse-cachewipe] `isNewMessage` is a misnomer: it only asks
        // whether the new text is a prefix-extension of the old. That is a
        // sound "content was rewritten" test DURING STREAMING, but it is also
        // trivially true whenever a cell is recycled onto an unrelated message
        // — and recycling is exactly what a large jump (the "previous turn"
        // button) produces en masse.
        //
        // Field evidence (2026-08-13, three separate reports): 9 rapid
        // prevUserTurn taps traversed ~58,000pt; each one recycled cells and
        // tripped this branch, so `oldLen/newLen` jumped between the lengths
        // of entirely different messages (3998→1702, 3866→4014). Every hit
        // dropped the math cache and tore down every attachment view, so all
        // the LaTeX in the newly-shown message had to re-render
        // asynchronously; each completion called back into
        // `updateAttachmentViews + invalidateCellSize`, producing 57
        // FIRST-MEASURE CORRECTIONs oscillating 7733↔8066 without converging.
        // Screenshots caught the midpoint: text laid out for not-yet-rendered
        // formulas while tool-card / table / code-block attachment views still
        // sat at their pre-teardown coordinates — the "collapse".
        //
        // So only take the destructive path for a genuine same-content
        // rewrite. A recycled view gets the rebuild path below instead, which
        // reuses the prebuilt attributed string and lays out synchronously.
        let isContentRewrite = !oldMarkdown.isEmpty
            && !markdown.hasPrefix(oldMarkdown)
            && !isDifferentContent
        if isDifferentContent {
            // Recycled onto different content. The caches are keyed per
            // message/block and the incoming content brings its own, so the
            // previous occupant's attachment VIEWS must go — but we must not
            // strand the text in a laid-out-without-attachments state the way
            // the wipe branch did. `clearAllAttachmentViews` is paired with a
            // synchronous re-layout below (the `cachedAttributedString` fast
            // path), so the very first frame this view presents already has
            // its attachments positioned.
            //
            // Deliberately NOT cleared: the renderer's per-content caches
            // (image/video/audio/code/math). They are keyed by content hash,
            // so the incoming block simply misses them and renders its own;
            // wiping them would throw away entries the PREVIOUS occupant will
            // want the moment it scrolls back into view — that repeated
            // discard-and-re-render is what drove the burst.
            context.coordinator.fadeAnimator.cancelAll()
            textView.clearAllAttachmentViews()
            // Force the render below to actually run: the incoming content may
            // hash-collide with whatever this view last drew.
            context.coordinator.lastRenderedContentHash = nil
        }
        if isContentRewrite {
            // [WordFade] The storage is about to be rebuilt from scratch; any
            // in-flight fade ranges are stale. Drop them before the rewrite.
            context.coordinator.fadeAnimator.cancelAll()
            // [AttachHotPath] If this fires DURING streaming (i.e. between
            // tokens of the same message), it means markdown.hasPrefix(oldMarkdown)
            // returned false despite being the same message — every
            // ImageAttachment is destroyed and recreated on the next render,
            // ObjectIdentifier changes, and updateAttachmentViews rebuilds
            // every UIImageView. This is the smoking gun if it appears in
            // the streaming log stream.
            let imgN = context.coordinator.renderer.imageAttachmentCache.count
            let vidN = context.coordinator.renderer.videoAttachmentCache.count
            let audN = context.coordinator.renderer.audioAttachmentCache.count
            let codeN = context.coordinator.renderer.codeBlockAttachmentCache.count
            let tailOld = String(oldMarkdown.suffix(40)).replacingOccurrences(of: "\n", with: "⏎")
            let headNew = String(markdown.prefix(40)).replacingOccurrences(of: "\n", with: "⏎")
            // [T-ios-reuse-cachewipe] `sameId=true` is the invariant this
            // branch now guarantees: a wipe may only happen for content that
            // kept its identity. A cross-message oldLen/newLen jump here
            // means the identity check regressed.
            let idStr = contentId?.uuidString.prefix(8) ?? "nil"
            AppLogger(category: "AttachHotPath").info("[CACHE-WIPE] contentRewrite id=\(idStr) sameId=true — clearing img=\(imgN) vid=\(vidN) aud=\(audN) code=\(codeN) oldLen=\(oldMarkdown.count) newLen=\(markdown.count) oldTail=\"\(tailOld)\" newHead=\"\(headNew)\"")
            let renderer = context.coordinator.renderer
            renderer.imageAttachmentCache.removeAll()
            renderer.videoAttachmentCache.removeAll()
            renderer.audioAttachmentCache.removeAll()
            renderer.codeBlockAttachmentCache.removeAll()
            renderer.codeBlockContentHashes.removeAll()
            // HangFix(2026-05-14) — DO NOT wipe tableAttachmentCache /
            // tableAttachmentLRU. They are keyed by contentHash with a
            // 64-entry LRU bound; identical tables across messages
            // legitimately share the same TableAttachment instance (and
            // its precomputed `cachedLayout`), which is the only thing
            // that prevents a 6-table cell from costing seconds inside
            // `attachmentBounds` on every typesetter pass.
            renderer.mathAttachmentCache.removeAll()
            textView.clearAllAttachmentViews()
        }

        let prepStart = CFAbsoluteTimeGetCurrent()
        // REPRO-FIX(2026-05-16): split off in-flight table tail before
        // markdown parsing. The plain suffix is appended below after
        // markdown rendering completes; it bypasses cmark entirely so no
        // inline syntax (`*`, `**`, `` ` ``, `_`) inside the partial
        // table cells is parsed during streaming.
        //
        // Don't split once the message has finished streaming — the
        // presence of either a prebuilt `cachedAttributedString` or a
        // prebuilt `cachedContent` is the finalised-message signal sent
        // by `AIChatViewModel.cacheAttributedString(for:)`. After that
        // point cmark must see the full markdown so a trailing
        // un-terminated `|...|` final row (no trailing `\n`) is still
        // rendered as a real table row.
        let _isFinalised = cachedAttributedString != nil || cachedContent != nil
        let _splitForUpdate = _isFinalised ? (prefix: markdown, plainSuffix: "") : splitStreamingTableTail(markdown)
        let _hasPlainSuffix = !_splitForUpdate.plainSuffix.isEmpty
        let prepared = prepareMarkdownForRender(_splitForUpdate.prefix)
        // If we split off a suffix, the prebuilt cache (which was rendered
        // from the full markdown) no longer matches what we'll render —
        // force a fresh MarkdownContent parse and skip cachedContent.
        let content = (_hasPlainSuffix ? nil : cachedContent) ?? MarkdownContent(prepared)
        let prepElapsed = (CFAbsoluteTimeGetCurrent() - prepStart) * 1000
        // Image-related diagnostics — only walk the parsed block tree when
        // the raw markdown actually contains image syntax. The block walk
        // is O(N) over every node in every block, which on long messages
        // becomes a measurable chunk of every updateUIView pass (and
        // updateUIView runs on each SwiftUI body re-evaluation, so it
        // multiplies during streaming and self-sizing measurement loops).
        let imageMatches = markdown.ranges(of: /!\[([^\]]*)\]\(([^)]+)\)/)
        if !imageMatches.isEmpty {
            for match in imageMatches {
                let matchStr = String(markdown[match])
                imgLogger.info("[MinisImage][StreamParse] image markdown found: \(matchStr)")
            }
            let imageBlockCount = content.blocks.flatMap { Self.collectImageNodes(from: $0) }.count
            if imageBlockCount > 0 {
                imgLogger.info("[MinisImage][StreamParse] parsed \(imageBlockCount) image node(s) from \(content.blocks.count) block(s), markdownLen=\(markdown.count)")
            }
        }

        var hasher = Hasher()
        hasher.combine(cachedContent != nil ? cachedContent!.blocks.hashValue : markdown.hashValue)
        hasher.combine(FontSettings.shared.scaledMessage(16.5))
        let contentHash: Int = hasher.finalize()
        if contentHash == context.coordinator.lastRenderedContentHash {
            // [DIAG-RENDER-3] Content hash matches — skip re-render.
            // If text was already set correctly, this is fine. But if
            // attributedText is empty despite matching hash, we have a bug.
            if !markdown.isEmpty && (textView.attributedText?.length ?? 0) == 0 {
                AppLogger(category: "StreamDiag").info("[RENDER] ⚠️ HASH-MATCH-BUT-EMPTY markdown len=\(markdown.count) hash=\(contentHash) attrText len=0 — SKIPPING RENDER OF NON-EMPTY CONTENT")
            }
            if textView.textContainer.size.width > 1 {
                textView.updateAttachmentViews()
            }
            return
        }
        #if DEBUG
        // [T-ios-markdown-rerender-burst] Hash MISS — log why so we can tell if
        // the content hash is genuinely changing each updateUIView (unstable
        // hash input) vs lastRenderedContentHash being reset between calls.
        // Gated to non-streaming messages with prebuilt cache so it only fires
        // on the scroll-rerender case, not live streaming.
        if cachedContent != nil || cachedAttributedString != nil {
            let prev = context.coordinator.lastRenderedContentHash
            let usingBlocks = cachedContent != nil
            AppLogger(category: "MarkdownRenderer").info("[RND-MISS] hash=\(contentHash) prev=\(prev.map(String.init) ?? "nil") src=\(usingBlocks ? "blocks" : "markdown") hasPlainSuffix=\(_hasPlainSuffix) fontChanged=\(fontChanged) mdLen=\(markdown.count)")
        }
        #endif
        context.coordinator.lastRenderedContentHash = contentHash

        let renderStart = CFAbsoluteTimeGetCurrent()
        let renderer = context.coordinator.renderer
        let attributed: NSAttributedString
        let usedCache: Bool
        // [TablePathFix] When the prebuilt attributed string contains a
        // TableAttachment we must NOT use it — the cached path comes from
        // `AIChatViewModel.cacheAttributedString(for:)` which uses a fresh
        // `MarkdownNSRenderer` each call and therefore creates a *new*
        // TableAttachment object every flush. During streaming this
        // detaches the live attachments from the coordinator's
        // `tableAttachmentCache` (which is what `update()` mutates with
        // proper `contentGeneration` bumps and `cachedLayout = nil` resets).
        // The visible symptom is the "table rendered as a single column"
        // / "stale layout cached for old probe width" case logged via
        // [TableDiag]. Falling through to `coord.renderer.render` preserves
        // TableAttachment object identity across renders, so `update()`
        // works as intended and the layout manager sees the correct bounds.
        let prebuiltHasTable: Bool = {
            guard let prebuilt = cachedAttributedString, prebuilt.length > 0 else { return false }
            var found = false
            prebuilt.enumerateAttribute(.attachment, in: NSRange(location: 0, length: prebuilt.length), options: []) { value, _, stop in
                if value is TableAttachment {
                    found = true
                    stop.pointee = true
                }
            }
            return found
        }()
        // REPRO-FIX(2026-05-16): when we split off an in-flight plain
        // suffix above, the prebuilt cache covers the wrong content —
        // bypass it and re-render so the appended suffix lives in the
        // current view's storage (the prebuilt was for the full markdown).
        if let prebuilt = cachedAttributedString, !fontChanged, !prebuiltHasTable, !_hasPlainSuffix {
            attributed = prebuilt
            usedCache = true
        } else {
            renderer.messageId = messageId
            renderer.blockId = blockId
            let renderedBody = autoreleasepool {
                renderer.render(blocks: content.blocks)
            }
            if _hasPlainSuffix {
                let mut = NSMutableAttributedString(attributedString: renderedBody)
                let suffixAttrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: FontSettings.shared.scaledMessage(16.5)),
                    .foregroundColor: UIColor.label,
                ]
                mut.append(NSAttributedString(string: _splitForUpdate.plainSuffix, attributes: suffixAttrs))
                attributed = mut
            } else {
                attributed = renderedBody
            }
            usedCache = false
        }
        // Walk every inline image attachment so we can:
        //   1. patch `messageId` (the cached path via renderMarkdownBlocks
        //      creates attachments without one)
        //   2. invalidate any stale bitmap if the underlying file was
        //      rewritten since the attachment last loaded (size+mtime
        //      fingerprint mismatch). Skips the on-disk stat for
        //      non-minis schemes.
        attributed.enumerateAttribute(.attachment,
                                      in: NSRange(location: 0, length: attributed.length),
                                      options: []) { value, _, _ in
            guard let att = value as? ImageAttachment else { return }
            if let messageId { att.messageId = messageId }
            if let loadedFp = att.loadedFingerprint {
                let currentFp = minisMediaCacheKey(for: att.source)
                if loadedFp != currentFp {
                    imgLogger.info("[MinisImage][CachedAttr] FINGERPRINT CHANGED src=\(att.source) old=\(loadedFp) new=\(currentFp) — invalidating cached image")
                    att.invalidateLoadedImage()
                }
            }
        }
        let renderElapsed = (CFAbsoluteTimeGetCurrent() - renderStart) * 1000

        let assignStart = CFAbsoluteTimeGetCurrent()
        // [AttachHotPath] Append-only fast path: when the new attributed string
        // is a strict extension of what's already in textStorage, only replace
        // the tail. The prefix's glyphs / line fragments / attachment bounds
        // stay laid out — TextKit doesn't re-call attachmentBounds for the
        // image attachments in the prefix, which was the per-token hot path
        // (see `[IMG][BOUNDS]` log series climbing into the hundreds during
        // streaming).
        // [WordFade] Compute the freshly-revealed character range BEFORE we
        // mutate storage, using a pure STRING-prefix diff — exactly how
        // Microsoft's SwiftStreamingMarkdown keys its per-word fade
        // (`newContentLength = attr.length - oldLength`). This is deliberately
        // decoupled from the append fast path below: the fade must fire whether
        // or not the attributed-string fast path is taken, and the renderer's
        // attribute churn (CJK NSFont fallback, NSOriginalFont, fresh paragraph
        // styles) must NOT gate it. We only require the OLD text to be a literal
        // prefix of the NEW text — true for every streamed token. [T-ios-word-fade-animation]
        var fadeRevealRange: NSRange? = nil
        if let oldStorage = textView.textStorage as? NSTextStorage,
           oldStorage.length > 0,
           attributed.length > oldStorage.length {
            let oldLen = oldStorage.length
            let oldStr = oldStorage.string as NSString
            let newStr = attributed.string as NSString
            if newStr.length >= oldLen,
               newStr.substring(to: oldLen) == (oldStr as String) {
                fadeRevealRange = NSRange(location: oldLen, length: attributed.length - oldLen)
            }
        }

        var fastPathTaken = false
        var newAttachInTail = 0
        if let oldStorage = textView.textStorage as? NSTextStorage,
           oldStorage.length > 0,
           attributed.length > oldStorage.length {
            let prefixLen = oldStorage.length
            let newPrefix = attributed.attributedSubstring(from: NSRange(location: 0, length: prefixLen))
            // isEqual on NSAttributedString compares both string AND attribute
            // dictionaries (including attachment object identity). Since the
            // renderer reuses ImageAttachment objects via imageAttachmentCache,
            // a token-only append produces an identical prefix here. Ignore the
            // TextKit-injected NSOriginalFont / substituted NSFont so CJK
            // streams (whose laid-out prefix differs only by font fallback)
            // still take the fast path instead of a full re-render.
            let _oldPrefix = oldStorage.attributedSubstring(from: NSRange(location: 0, length: prefixLen))
            if Self.prefixesEqualIgnoringFontSubstitution(newPrefix, _oldPrefix) {
                let tailRange = NSRange(location: prefixLen, length: 0)
                let tail = attributed.attributedSubstring(from: NSRange(location: prefixLen, length: attributed.length - prefixLen))
                tail.enumerateAttribute(.attachment, in: NSRange(location: 0, length: tail.length), options: []) { v, _, _ in
                    if v != nil { newAttachInTail += 1 }
                }
                oldStorage.beginEditing()
                oldStorage.replaceCharacters(in: tailRange, with: tail)
                oldStorage.endEditing()
                fastPathTaken = true
            }
        }
        if !fastPathTaken {
            textView.attributedText = attributed
        }

        // [WordFade] Now that storage holds the final text (via either path),
        // fade the revealed range in word-by-word. Decoupled from fastPathTaken
        // so a full-replace token still animates. A scene-deferred / background
        // frame never reaches here; isNewMessage / rotation already cancelAll'd.
        if let revealRange = fadeRevealRange,
           let liveStorage = textView.textStorage as? NSTextStorage {
            context.coordinator.fadeTextView = textView
            context.coordinator.fadeAnimator.scheduleAnimation(for: revealRange, in: liveStorage)
        }
        let assignElapsed = (CFAbsoluteTimeGetCurrent() - assignStart) * 1000
        textView.rawMarkdown = markdown
        textView.resetAttachmentRecoveryState()
        // [HangRepro] Record the full markdown just assigned to this
        // textView so a subsequent main-thread hang (caught by
        // HangDetector) can dump the actual input that triggered the
        // typesetter feedback loop. Bounded ring of last 10 entries.
        StreamingHangLogger.shared.recordAssignedText(
            messageId: messageId,
            markdown: markdown,
            attrLen: attributed.length,
            path: fastPathTaken ? "fast-append" : "full-assign"
        )

        // Walk attachments that mutated their content in place (currently
        // TableAttachment via `update(...)`) and invalidate JUST those
        // character ranges in the layout manager. With identity-stable
        // attachments TextKit otherwise keeps the cached attachment-bounds
        // (and therefore the wrong rendered height) for the table.
        if let storage = textView.textStorage as? NSTextStorage {
            let storageLen = storage.length
            if storageLen > 0 {
                storage.enumerateAttribute(.attachment,
                                           in: NSRange(location: 0, length: storageLen),
                                           options: []) { value, range, _ in
                    if let table = value as? TableAttachment, table.needsLayoutInvalidation {
                        textView.layoutManager.invalidateLayout(forCharacterRange: range, actualCharacterRange: nil)
                        table.needsLayoutInvalidation = false
                    }
                }
            }
        }

        let layoutStart = CFAbsoluteTimeGetCurrent()
        var ensureLayoutMs: Double = 0
        if fastPathTaken {
            // Prefix is already laid out. Don't ensureLayout the whole range —
            // TextKit will lay out the appended tail on demand. setNeedsLayout
            // is enough so the text view re-draws and re-measures intrinsic size.
            textView.setNeedsLayout()
        } else if (cachedContent != nil || cachedAttributedString != nil) && textView.textContainer.size.width > 1 {
            // Bounded slow-path layout: only ensure the visible glyph range
            // plus a small overscan. Replacing the full-range ensureLayout
            // here was the second prong of the NSTypesetter watchdog fix —
            // even when the fast path is missed (e.g. a new attachment
            // appeared in the prefix), we no longer pay O(N) layout cost
            // synchronously on the main thread for the entire message.
            let tEnsure = CFAbsoluteTimeGetCurrent()
            let containerSize = textView.textContainer.size
            let visibleRect: CGRect
            if textView.bounds.width > 1, textView.bounds.height > 1 {
                visibleRect = CGRect(x: 0, y: 0, width: containerSize.width, height: textView.bounds.height + 800)
            } else {
                visibleRect = CGRect(x: 0, y: 0, width: containerSize.width, height: 1200)
            }
            let visibleGlyphRange = textView.layoutManager.glyphRange(forBoundingRect: visibleRect,
                                                                     in: textView.textContainer)
            if visibleGlyphRange.length > 0 {
                textView.layoutManager.ensureLayout(forGlyphRange: visibleGlyphRange)
            } else {
                textView.setNeedsLayout()
            }
            ensureLayoutMs = (CFAbsoluteTimeGetCurrent() - tEnsure) * 1000
        } else {
            textView.setNeedsLayout()
        }
        textView.invalidateIntrinsicContentSize()
        textView.setNeedsDisplay()
        textView.invalidateCellSizeIfNeeded()
        let layoutElapsed = (CFAbsoluteTimeGetCurrent() - layoutStart) * 1000
        // [WATCHDOG-DIAG] Log when updateUIView's ensureLayout runs long — this
        // is the other hot site that can burn main-thread wall-clock time.
        if ensureLayoutMs >= 50 || attributed.length >= 10_000 {
        }

        let attachStart = CFAbsoluteTimeGetCurrent()
        // When fast path was taken AND no new attachment was introduced in the
        // appended tail, existing attachment views' character indices are
        // unchanged, their boundingRects are stable, and we can skip the full
        // updateAttachmentViews enumeration. If a new attachment did appear
        // (rare during text streaming after an image), fall through to refresh.
        let skipAttachUpdate = fastPathTaken && newAttachInTail == 0
        if skipAttachUpdate {
            // No-op. Existing attachment views remain correctly placed.
        } else if textView.textContainer.size.width > 1 {
            textView.updateAttachmentViews()
        } else {
            DispatchQueue.main.async { [weak textView] in
                guard let textView, textView.window != nil else { return }
                textView.updateAttachmentViews()
                textView.invalidateIntrinsicContentSize()
                textView.setNeedsDisplay()
                textView.setNeedsLayout()
                textView.invalidateCellSizeIfNeeded()
            }
        }
        _ = (CFAbsoluteTimeGetCurrent() - attachStart) * 1000
        let updateElapsed = (CFAbsoluteTimeGetCurrent() - updateStart) * 1000

        // [WatchdogProbe] watchdog crash repro lead — the iOS 26 hang stack
        // shows _performTextKit1LayoutCalculation re-running per cell on
        // collection-view bounds change. Emit when a single updateUIView
        // takes >300ms; chain of these in one main-runloop iteration is the
        // direct cause of a 0x8BADF00D termination.
        #if DEBUG
        let msgIdShort = messageId?.uuidString.prefix(8) ?? "—"
        if updateElapsed >= 300 {
            Self.watchdogProbe.warning("[WatchdogProbe][upd] slow updateUIView elapsed=\(String(format: "%.1f", updateElapsed))ms attrLen=\(textView.attributedText?.length ?? 0) markdownLen=\(markdown.count) msgId=\(msgIdShort)")
        }
        Self.recordUpdateUIView(elapsedMs: updateElapsed, attrLen: textView.attributedText?.length ?? 0, msgId: messageId)
        #endif

        // Keep textContainer height unconstrained so subsequent boundingRect
        // queries return correct positions for attachments near the end.
        // Only assign when it's actually clamped — see layoutSubviews.
        if textView.textContainer.size.height < CGFloat.greatestFiniteMagnitude {
            textView.textContainer.size.height = CGFloat.greatestFiniteMagnitude
        }
        #if DEBUG
        // [T-attachment-zero-origin] Snapshot at end of updateUIView (live uiView).
        MarkdownSnapshotCollector.captureIfEnabled(
            textView: textView,
            phase: "afterUpdateUIView",
            messageId: context.coordinator.renderer.messageId?.uuidString,
            isMeasureView: false
        )
        #endif
    }

    /// Key TextKit injects on laid-out storage to remember the pre-fallback
    /// font (CJK / emoji substitution). It never appears on a freshly-rendered
    /// attributed string, so a literal `isEqual` between the renderer's output
    /// and the already-laid-out storage always differs by exactly this key.
    private static let nsOriginalFontKey = NSAttributedString.Key("NSOriginalFont")

    /// True when `newPrefix` (renderer output) and `oldPrefix` (already
    /// laid-out storage) are equal once TextKit's font-substitution bookkeeping
    /// is normalized away. When TextKit lays out CJK / emoji, it swaps `NSFont`
    /// to a fallback face and stashes the pre-fallback font under
    /// `NSOriginalFont`. The renderer's fresh string has neither — just the
    /// original `NSFont`. A literal `isEqual` therefore fails on every token,
    /// silently disabling the append fast path (and the word-fade) for CJK.
    ///
    /// We accept the run as equal when, after dropping `NSOriginalFont` and
    /// `NSFont` from both sides, all OTHER attributes (color, paragraph style,
    /// attachment identity, …) match exactly AND the fonts are reconcilable:
    /// either the raw `NSFont`s are equal, or the new side's `NSFont` equals the
    /// old side's `NSOriginalFont` (i.e. old is just `new` after fallback).
    /// A genuine font/size change still breaks the match. [T-ios-word-fade-animation]
    private static func prefixesEqualIgnoringFontSubstitution(
        _ newPrefix: NSAttributedString,
        _ oldPrefix: NSAttributedString
    ) -> Bool {
        guard newPrefix.length == oldPrefix.length else { return false }
        guard (newPrefix.string as NSString).isEqual(oldPrefix.string) else { return false }
        let fontKey = NSAttributedString.Key.font
        let full = NSRange(location: 0, length: newPrefix.length)
        var equal = true
        newPrefix.enumerateAttributes(in: full, options: []) { aN, range, stop in
            let aO = oldPrefix.attributes(at: range.location, effectiveRange: nil)
            // Reconcile fonts: equal as-is, or new.NSFont == old.NSOriginalFont.
            let fontN = aN[fontKey] as? UIFont
            let fontO = aO[fontKey] as? UIFont
            let originalO = aO[nsOriginalFontKey] as? UIFont
            let fontsOK = (fontN == fontO) || (fontN != nil && fontN == originalO)
            if !fontsOK { equal = false; stop.pointee = true; return }
            // Compare everything else with font bookkeeping removed.
            var nN = aN; nN.removeValue(forKey: fontKey); nN.removeValue(forKey: nsOriginalFontKey)
            var nO = aO; nO.removeValue(forKey: fontKey); nO.removeValue(forKey: nsOriginalFontKey)
            if !(nN as NSDictionary).isEqual(to: nO) {
                equal = false
                stop.pointee = true
            }
        }
        return equal
    }

    /// Recursively collect `.image` inline nodes from a block tree for logging.
    private static func collectImageNodes(from block: BlockNode) -> [String] {
        var results: [String] = []
        func walkInlines(_ inlines: [InlineNode]) {
            for inline in inlines {
                switch inline {
                case .image(let src, _):
                    results.append(src)
                case .emphasis(let ch), .strong(let ch), .strikethrough(let ch), .link(_, let ch):
                    walkInlines(ch)
                default: break
                }
            }
        }
        func walkBlocks(_ blocks: [BlockNode]) {
            for b in blocks {
                switch b {
                case .paragraph(let inlines), .heading(_, let inlines):
                    walkInlines(inlines)
                case .blockquote(let children):
                    walkBlocks(children)
                case .bulletedList(_, let items):
                    for item in items { walkBlocks(item.children) }
                case .numberedList(_, _, let items):
                    for item in items { walkBlocks(item.children) }
                case .taskList(_, let items):
                    for item in items { walkBlocks(item.children) }
                default: break
                }
            }
        }
        walkBlocks([block])
        return results
    }

    /// [T-ios-longreply-boundingRect-watchdog] Measure attributed-string height with
    /// bare CoreText `CTFramesetter` instead of `NSAttributedString.boundingRect`
    /// (which wraps TextKit1 / `NSLayoutManager` and has near-O(n²) cost in line
    /// count — 100 lines ≈ 1.7s, 1000 lines ≈ 17.7s, the 8.7–10.8s SLOW MEASUREs
    /// behind the 0x8BADF00D watchdog kills). `CTFramesetter` uses the same CoreText
    /// typesetter foundation with no TextKit wrapper: 100 lines ≈ 24ms (71×), 1000
    /// lines ≈ 24ms (738×), and it barely grows with line count.
    ///
    /// Only valid for **attachment-free** text — `CTFramesetter` cannot see the
    /// custom `attachmentBounds` of TableAttachment/CodeBlockAttachment/ImageAttachment
    /// (those need TextKit to invoke the line-fragment typesetter), so callers MUST
    /// gate on "no attachments" before using this. Returns the text height only;
    /// callers add `textContainerInset` (top+bottom) to match `UITextView.sizeThatFits`.
    static func ctFramesetterHeight(for attrStr: NSAttributedString, width: CGFloat) -> CGFloat {
        guard attrStr.length > 0, width > 0 else { return 0 }
        let framesetter = CTFramesetterCreateWithAttributedString(attrStr)
        let constraint = CGSize(width: width, height: .greatestFiniteMagnitude)
        let size = CTFramesetterSuggestFrameSizeWithConstraints(
            framesetter,
            CFRange(location: 0, length: 0),   // 0 length = whole string
            nil,
            constraint,
            nil
        )
        return ceil(size.height)
    }

    @available(iOS 16.0, *)
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: SelectableMarkdownTextView, context: Context) -> CGSize? {
        let width = proposal.width ?? UIScreen.main.bounds.width
        // Key the size cache on the SwiftUI binding length, not
        // uiView.textStorage.length. Within a single render pass SwiftUI calls
        // sizeThatFits BEFORE updateUIView, so textStorage still reflects the
        // previous commit. Streaming providers that push tokens in bursts with
        // pauses (DeepSeek V4 especially) would otherwise hit cached heights
        // keyed on the stale length, locking the cell at the old size and
        // clipping the newly arrived characters until the cell was recycled.
        let bindingLen = (markdown as NSString).length
        let storageLen = uiView.textStorage.length

        let coord = context.coordinator
        coord.sizeThatFitsCallCount &+= 1
        let callIdx = coord.sizeThatFitsCallCount
        let t0 = CFAbsoluteTimeGetCurrent()

        // Cache key includes the width bucket (1pt precision) AND the binding
        // length. Multi-width cache survives SwiftUI's recursive sizeThatFits
        // probing where the same view is measured at 2–3 nearby widths during
        // a single preferredLayoutAttributesFitting pass.
        let widthKey = Int(width.rounded())
        if bindingLen == coord.cachedSizeMarkdownCount,
           let cachedHeight = coord.cachedSizes[widthKey] {
            coord.sizeThatFitsCacheHitCount &+= 1
            if callIdx % 50 == 0 {
            }
            return CGSize(width: width, height: cachedHeight)
        }

        // [T-ios-markdown-table-double-render] Near-width cache hit for FULLY
        // COMMITTED (non-streaming) content. During a deceleration glide UIKit
        // probes a cell's sizeThatFits several times at WIDTHS that differ by
        // 1–2pt of rounding (preferredLayoutAttributesFitting passes), so the
        // exact-`widthKey` hit above misses each time and we fall through to the
        // off-screen measure path — which renders the markdown again (the [RND]
        // burst). For a message whose binding is fully laid into storage
        // (`bindingLen == storageLen`, i.e. NOT mid-stream), a height measured
        // 1–2pt away is correct to the pixel for our purposes; reuse it and skip
        // the re-render entirely. This was the table-message "rendered 6×/decel"
        // cost (measure renderer ×3 + display renderer ×3). Gated to non-streaming so token growth
        // (bindingLen != storageLen) is unaffected, and to a tight ±2pt window so
        // a genuine width change (rotation / sidebar) still re-measures. No new
        // measurement happens — we return a height the SAME content already
        // produced — so this cannot reintroduce the historical
        // sizeThatFits→fillLayoutHole typesetter loop.
        if bindingLen == coord.cachedSizeMarkdownCount,
           bindingLen == storageLen,
           !coord.cachedSizes.isEmpty {
            var nearestHeight: CGFloat?
            var nearestDelta = Int.max
            for (bucket, h) in coord.cachedSizes {
                let delta = abs(bucket - widthKey)
                if delta <= 2, delta < nearestDelta {
                    nearestDelta = delta
                    nearestHeight = h
                }
            }
            if let nearestHeight {
                coord.sizeThatFitsCacheHitCount &+= 1
                // Memoize at the exact requested width so the next identical
                // probe takes the exact-hit fast path above (and a flapping
                // width sequence still respects the 4-bucket cap below).
                coord.cachedSizes[widthKey] = nearestHeight
                if coord.cachedSizes.count > 4, let firstKey = coord.cachedSizes.keys.first {
                    coord.cachedSizes.removeValue(forKey: firstKey)
                }
                return CGSize(width: width, height: nearestHeight)
            }
        }

        // HangFix(2026-05-14) — streaming-aware approximate cache hit.
        // If we have a recent full-measurement anchor at this width, and the
        // binding has only grown by a small amount (typical streaming
        // delta), extrapolate the new height linearly to avoid an expensive
        // off-screen measurement. This avoids the 0x8BADF00D watchdog
        // crash on iPhone 17 Pro Max where every token chunk used to spawn
        // a 50-200ms typesetter pass.
        //
        // Strict guards to avoid runaway estimates (the very-tall-table
        // bug seen 2026-05-14 20:21: prevLen=2 prevH=14 → 7pt/char → 200
        // chars later 1547pt) — the anchor must be substantial enough to
        // give a meaningful per-char rate, the delta must be modest, the
        // estimate is bounded by an absolute cap, and the estimate is NOT
        // written back to cachedSizes so a later sizeThatFits at the same
        // length doesn't reuse it.
        let useMeasureTextView = bindingLen != storageLen
        // [T-ios-streaming-est-attachment-clip] STREAM-EST disabled again.
        // It was restored in de4d3df6 to kill visible-streaming hangs, but the
        // linear pxPerChar extrapolation under-measures cells that contain
        // fixed-height attachments (tool capsules, code/shell blocks, images):
        // those blocks don't grow with character count, so the estimate came
        // out too short and the NEXT cell overlapped the tail of a finished
        // message (user report, macOS, "Minis Feedback Review" — the shell
        // preview + tool capsule covered the body text of the last message).
        // Gate it back off so every streaming sizeThatFits takes a real
        // measurement; the hang de4d3df6 fixed is the tradeoff to revisit with
        // an attachment-aware estimate rather than this whole-cell linear one.
        let disableStreamEst = true
        if !disableStreamEst,
           useMeasureTextView,
           let prevHeight = coord.lastFullMeasureHeight[widthKey],
           coord.lastFullMeasureBindingLen >= 64,                        // anchor must represent enough characters
           prevHeight >= 30,                                              // and a real laid-out block
           bindingLen > coord.lastFullMeasureBindingLen,
           bindingLen - coord.lastFullMeasureBindingLen < 128 {           // tighter delta window
            let prevLen = coord.lastFullMeasureBindingLen
            let pxPerChar = prevHeight / CGFloat(prevLen)
            let delta = CGFloat(bindingLen - prevLen)
            // Cap the extrapolated growth to 1.5× the anchor and to an
            // absolute ceiling matching the on-screen viewport (avoids
            // table/code blocks blowing the estimate past the screen).
            let rawEstimate = prevHeight + delta * pxPerChar
            let estimated = min(rawEstimate, prevHeight * 1.5, 1200)
            // DO NOT write `estimated` to `cachedSizes` — that would let
            // the next sizeThatFits at the same bindingLen think it has a
            // real measurement and skip re-measurement entirely. Just
            // return the estimate for this probe; the next probe will
            // re-enter and either compute a real value (storage catches
            // up) or extrapolate again (delta still in range).
            return CGSize(width: width, height: estimated)
        }

        // If the binding length changed, the old cache is invalid — flush all
        // width buckets. Otherwise we're just adding a new width bucket.
        if bindingLen != coord.cachedSizeMarkdownCount {
            coord.cachedSizes.removeAll(keepingCapacity: true)
            coord.cachedSizeMarkdownCount = bindingLen
        }

        // If textStorage hasn't caught up to the binding yet (SwiftUI's
        // sizeThatFits-before-updateUIView ordering), measure against an
        // off-screen textView. Previously we committed the pending
        // attributedText into `uiView` here so its own sizeThatFits saw the
        // new content — but that rebuilds attachment subviews and calls
        // `addSubview` / `setNeedsLayout` on the live textView during
        // SwiftUI's measurement pass, which causes SwiftUI to re-invoke
        // `sizeThatFits` recursively. The resulting watchdog hang is
        // recorded in `Minis-2026-05-13-084827.ips` (depth=9 recursion at
        // `LayoutEngineBox.sizeThatFits`). Measuring on a detached textView
        // keeps the live view's state stable until the subsequent
        // `updateUIView` runs.
        // (`useMeasureTextView` already declared above in the streaming
        // approximate-cache fast path.)
        let measuringTextView: SelectableMarkdownTextView
        // REPRO-FIX(2026-05-16): when storage doesn't have the latest content
        // yet, we used to write `pending` to an off-screen measureTextView
        // and call its sizeThatFits — but that triggers full _fillLayoutHole
        // + tcSize churn for every stream chunk. Replace with
        // NSAttributedString.boundingRect when pending is attachment-free
        // (boundingRect uses CoreText and never invalidates UIKit layout
        // state). When pending has attachments, TableAttachment /
        // CodeBlockAttachment / ImageAttachment custom `attachmentBounds`
        // is needed for correct heights, so we still fall back to mtv.
        if useMeasureTextView {
            // [TablePathFix] When the prebuilt attributed string contains a
            // TableAttachment we must NOT use it — the cached path comes
            // from `AIChatViewModel.cacheAttributedString(for:)` which uses
            // a fresh `MarkdownNSRenderer` and creates new TableAttachment
            // objects every flush. During streaming this detaches live
            // attachments from the coordinator's `tableAttachmentCache`
            // (which `update()` mutates with `contentGeneration` bumps
            // that the UAV dedup fingerprint relies on).
            let preHasTable: Bool = {
                guard let pre = cachedAttributedString, pre.length > 0 else { return false }
                var found = false
                pre.enumerateAttribute(.attachment, in: NSRange(location: 0, length: pre.length), options: []) { value, _, stop in
                    if value is TableAttachment { found = true; stop.pointee = true }
                }
                return found
            }()
            let pending: NSAttributedString
            if let pre = cachedAttributedString, !preHasTable {
                pending = pre
            } else {
                // REPRO-FIX(2026-05-16): split off any in-flight (`|`-row
                // not yet terminated by `\n`) table block at the buffer's
                // tail. cmark gets only the stable prefix; the in-flight
                // tail is appended as plain attributed text using the body
                // font. This avoids the per-chunk `TableAttachment` rebuild
                // + `_fillLayoutHole` storm that hangs iPhone 17 Pro Max.
                //
                // Skip the split once the message has finished streaming
                // (the presence of `cachedAttributedString` or
                // `cachedContent` is the finalised signal): a message
                // whose final row never gets a trailing `\n` must still
                // be rendered as a real table after stream-end.
                let _isFinalisedSTF = cachedAttributedString != nil || cachedContent != nil
                let split = _isFinalisedSTF
                    ? (prefix: markdown, plainSuffix: "")
                    : splitStreamingTableTail(markdown)
                let preparedPrefix = prepareMarkdownForRender(split.prefix)
                let blocks = MarkdownContent(preparedPrefix).blocks
                coord.renderer.messageId = messageId
                coord.renderer.blockId = blockId
                let rendered = autoreleasepool { coord.renderer.render(blocks: blocks) }
                if split.plainSuffix.isEmpty {
                    pending = rendered
                } else {
                    let mut = NSMutableAttributedString(attributedString: rendered)
                    let suffixAttrs: [NSAttributedString.Key: Any] = [
                        .font: UIFont.systemFont(ofSize: FontSettings.shared.scaledMessage(16.5)),
                        .foregroundColor: UIColor.label,
                    ]
                    mut.append(NSAttributedString(string: split.plainSuffix, attributes: suffixAttrs))
                    pending = mut
                }
            }
            // REPRO-FIX(2026-05-16): scan pending for ANY .attachment.
            // No-attachment case: take the CoreText boundingRect fast path
            // (verified across 14 streaming rounds in commit 01939f7:
            // MAIN HANG = 0, avg processMs = 0.5). Attachments require
            // the legacy mtv path because their custom `attachmentBounds`
            // depends on TextKit invoking it through the line fragment
            // typesetter.
            var hasAnyAttachment = false
            pending.enumerateAttribute(.attachment, in: NSRange(location: 0, length: pending.length), options: []) { v, _, stop in
                if v != nil { hasAnyAttachment = true; stop.pointee = true }
            }
            if !hasAnyAttachment {
                // [T-ios-decel-inv-estimate-calibration] FINALIZED content takes
                // the real-engine measure: CTFramesetter over-measures blocks
                // with inline-code/bold spans by +2..+36pt vs the actual TextKit
                // render (per-cell trajectory traces: first display grew the
                // cell mid-decel, then a later re-measure shrank it back — the
                // user-visible scroll jitter AND oscillation). An offscreen
                // SelectableMarkdownTextView measure matches the final render
                // by construction. Gated to finalized (non-streaming) content
                // and ≤8000 chars — streaming keeps the CT fast path (per-chunk
                // cost), and huge blocks avoid TextKit1's near-O(n²) wall
                // (T-ios-longreply-boundingRect-watchdog).
                let finalizedSTF = cachedAttributedString != nil || cachedContent != nil
                let h: CGFloat
                if finalizedSTF, pending.length <= 8000,
                   let mtv = coord.measureTextView as? SelectableMarkdownTextView {
                    mtv.attributedText = pending
                    h = ceil(mtv.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height)
                } else {
                    // [T-ios-longreply-boundingRect-watchdog] CTFramesetter instead of
                    // NSAttributedString.boundingRect — same CoreText result (±1pt) but
                    // 70–700× faster on many-line text (boundingRect's TextKit1 wrapper
                    // is near-O(n²) in line count). Attachment-free is already asserted
                    // above, so it's safe here.
                    let textHeight = Self.ctFramesetterHeight(for: pending, width: width)
                    // [BoundingRectInset 2026-05-16] CoreText measurement
                    // omits UITextView.textContainerInset (configured top=4
                    // bottom=4 in init, line ~3597). Diagnostic confirmed
                    // UITextView.sizeThatFits returns text height +
                    // exactly inset.top + inset.bottom (=8) consistently
                    // across CJK + inline-code multi-paragraph blocks.
                    // Without this addition cell.frame is 7-8pt short and
                    // the last visual line of every multi-line markdown
                    // cell gets clipped at its descender.
                    let inset = uiView.textContainerInset.top + uiView.textContainerInset.bottom
                    h = textHeight + inset
                }
                coord.cachedSizes[widthKey] = h
                coord.cachedSizeMarkdownCount = bindingLen
                if bindingLen > 0 {
                    coord.lastFullMeasureBindingLen = bindingLen
                    coord.lastFullMeasureHeight[widthKey] = h
                    coord.lastFullMeasureAt = CFAbsoluteTimeGetCurrent()
                }
                let _renderW = uiView.bounds.width
                if _renderW <= 0 || abs(width - _renderW) < 0.5 {
                    uiView.lastComputedHeight = h
                }
                return CGSize(width: width, height: h)
            }
            // Fallback: pending has attachments — use mtv path so
            // attachmentBounds is invoked through TextKit.
            let mtv = coord.measureTextView as? SelectableMarkdownTextView ?? coord.measureTextView as! SelectableMarkdownTextView
            mtv.attributedText = pending
            #if DEBUG
            // [T-attachment-zero-origin] Snapshot the moment after attributedText
            // is assigned to the off-screen measureTextView. This is exactly when
            // TextKit runs setGlyphs:properties: — if textContainer.size.width is
            // still 0 here, attachment glyphs get classified as controlCharacter
            // (the bug we're hunting). The snapshot captures glyphProperty for
            // every attachment so we can see the bad classification directly.
            MarkdownSnapshotCollector.captureIfEnabled(
                textView: mtv,
                phase: "afterMtv.setAttributedText",
                messageId: context.coordinator.renderer.messageId?.uuidString,
                isMeasureView: true
            )
            #endif
            measuringTextView = mtv
        } else {
            measuringTextView = uiView

            // [T-ios-longreply-boundingRect-watchdog] Non-streaming fast path.
            // For a fully-committed (bindingLen == storageLen) attachment-free
            // text block, measure with CTFramesetter instead of falling through
            // to `uiView.sizeThatFits` below — that call runs TextKit1's
            // near-O(n²) layout and is the source of the 8.7–10.8s SLOW MEASURE /
            // WatchdogProbe 2000ms window / 0x8BADF00D watchdog kills on the
            // 29k-char / ~1500-line reply. CTFramesetter is 70–700× faster and
            // returns the same height (±1pt). Attachments (table/code/image) still
            // take the TextKit path so their custom attachmentBounds is honored.
            let liveStorage = uiView.textStorage
            if liveStorage.length > 0 {
                var hasAnyAttachment = false
                liveStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: liveStorage.length), options: []) { v, _, stop in
                    if v != nil { hasAnyAttachment = true; stop.pointee = true }
                }
                if !hasAnyAttachment {
                    // [T-ios-decel-inv-estimate-calibration] Same real-engine
                    // routing as the pre-commit branch above: committed
                    // attachment-free content ≤8000 chars measures on the
                    // offscreen SelectableMarkdownTextView (matches the render
                    // exactly); CT stays for huge blocks (watchdog wall).
                    // FINALIZED-ONLY: during streaming, bindingLen catches up to
                    // storageLen between chunks, so without this gate every
                    // post-commit probe would pay the mtv measure (1.4-11ms vs
                    // CT's 0.3ms) per chunk — the exact per-chunk typesetter
                    // cost de4d3df6 / REPRO-FIX(2026-05-16) fought to remove.
                    let finalizedCommitted = cachedAttributedString != nil || cachedContent != nil
                    let h: CGFloat
                    if finalizedCommitted, liveStorage.length <= 8000,
                       let mtv = coord.measureTextView as? SelectableMarkdownTextView {
                        mtv.attributedText = liveStorage
                        h = ceil(mtv.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height)
                    } else {
                        let textHeight = Self.ctFramesetterHeight(for: liveStorage, width: width)
                        // Match UITextView.sizeThatFits: add textContainerInset (top+bottom).
                        let inset = uiView.textContainerInset.top + uiView.textContainerInset.bottom
                        h = textHeight + inset
                    }
                    coord.cachedSizes[widthKey] = h
                    if coord.cachedSizes.count > 4, let firstKey = coord.cachedSizes.keys.first {
                        coord.cachedSizes.removeValue(forKey: firstKey)
                    }
                    coord.cachedSizeMarkdownCount = bindingLen
                    if bindingLen > 0 {
                        coord.lastFullMeasureBindingLen = bindingLen
                        coord.lastFullMeasureHeight[widthKey] = h
                        coord.lastFullMeasureAt = CFAbsoluteTimeGetCurrent()
                        if coord.lastFullMeasureHeight.count > 4, let firstKey = coord.lastFullMeasureHeight.keys.first {
                            coord.lastFullMeasureHeight.removeValue(forKey: firstKey)
                        }
                    }
                    let renderW = uiView.bounds.width
                    if renderW <= 0 || abs(width - renderW) < 0.5 {
                        uiView.lastComputedHeight = h
                    }
                    let totalMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000
                    coord.sizeThatFitsTotalMs += totalMs
                    #if DEBUG
                    Self.recordSTF(elapsedMs: totalMs, msgId: messageId, attrLen: bindingLen)
                    #endif
                    return CGSize(width: width, height: h)
                }
            }
        }

        // CRITICAL FIX: Only touch textContainer.size when the width ACTUALLY
        // changed. On iOS 26 TextKit1, assigning the same CGSize still triggers
        // -[NSLayoutManager textContainerChangedGeometry:] which invalidates the
        // entire layout and forces a full _fillLayoutHole pass. Time Profiler
        // showed this single line causing 8+ second hangs during CollectionView
        // bounds-change re-measurement on large messages.
        //
        // [JitterFix] SwiftUI drives sizeThatFits at TWO widths per layout
        // pass: the inner content width (~370) and the cell's outer width
        // (~402). Committing both into the live textContainer corrupts
        // render state — the next render uses whichever width "won" most
        // recently, producing a 23pt height oscillation. Only commit when
        // the probe width matches the live bounds.width (the width the cell
        // will actually render at). Other-width probes still measure
        // correctly via UITextView.sizeThatFits, but don't mutate state.
        let tc = measuringTextView.textContainer
        let oldWidth = tc.size.width
        let renderW = measuringTextView.bounds.width
        let isRenderWidth = renderW > 0 && abs(width - renderW) < 0.5
        let widthChanged = abs(width - oldWidth) > 0.5
        // For the off-screen measureTextView (renderW == 0), always sync
        // the width so it measures at the requested fitting width.
        if widthChanged && (isRenderWidth || renderW <= 0 || useMeasureTextView) {
            tc.size = CGSize(width: width, height: .greatestFiniteMagnitude)
        }

        // Let UITextView.sizeThatFits drive layout lazily. Removing the
        // surrounding explicit lm.ensureLayout(for:) calls — they were
        // running a second and third full typesetting pass on top of the
        // one UITextView.sizeThatFits already does internally.
        let tSize = CFAbsoluteTimeGetCurrent()
        let size = measuringTextView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        let sizeMs = (CFAbsoluteTimeGetCurrent() - tSize) * 1000

        // Restore the unbounded height so the layout manager keeps all glyphs
        // laid out for drawing. UITextView.sizeThatFits clamps this internally.
        // Only when actually clamped — see layoutSubviews for the rationale.
        if tc.size.height < CGFloat.greatestFiniteMagnitude {
            tc.size.height = CGFloat.greatestFiniteMagnitude
        }

        // Keep lastComputedHeight in sync so invalidateCellSizeIfNeeded()
        // can detect real deltas. Without this, lastComputedHeight stays 0
        // after the initial sizeThatFits, causing the guard to skip layout
        // invalidation when images load asynchronously and change the height.
        //
        // [JitterFix] Only commit when probe width matches render width;
        // otherwise the outer-width probe (402) clobbers the correct
        // inner-width measurement (370 → ~611) with a 23pt-smaller value.
        // Per-width caching still works via coord.cachedSizes below.
        if isRenderWidth || renderW <= 0 {
            uiView.lastComputedHeight = size.height
        }

        // Cache the new result by width bucket. Cap at 4 buckets to bound
        // memory; evict the first inserted entry when overflowing.
        coord.cachedSizes[widthKey] = size.height
        if coord.cachedSizes.count > 4 {
            if let firstKey = coord.cachedSizes.keys.first {
                coord.cachedSizes.removeValue(forKey: firstKey)
            }
        }

        // HangFix(2026-05-14) — record this real measurement as the
        // anchor for subsequent streaming approximations. Only the live-
        // view path (storage == binding) produces a fully accurate
        // height; the off-screen measureTextView path also produces a
        // real measurement so we anchor on it too (storeLen tracks
        // binding inside useMeasureTextView).
        if bindingLen > 0 && size.height > 0 {
            coord.lastFullMeasureBindingLen = bindingLen
            coord.lastFullMeasureHeight[widthKey] = size.height
            coord.lastFullMeasureAt = CFAbsoluteTimeGetCurrent()
            // Bound the same-width-bucket count to 4 so a flapping width
            // sequence doesn't grow without bound; reuse the same cap as
            // `cachedSizes` above.
            if coord.lastFullMeasureHeight.count > 4 {
                if let firstKey = coord.lastFullMeasureHeight.keys.first {
                    coord.lastFullMeasureHeight.removeValue(forKey: firstKey)
                }
            }
        }

        let totalMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        coord.sizeThatFitsTotalMs += totalMs

        // Only log MISS on slow paths or periodically — every-token MISS during
        // streaming was a primary noise source.
        if totalMs >= 50 || callIdx % 100 == 0 {
            let widthDelta = Int((width - oldWidth).rounded())
            let level: String
            if totalMs >= 200 { level = "🔥🔥" }
            else if totalMs >= 50 { level = "🔥" }
            else { level = "" }
            Self.stfLogger.info("[STF] #\(callIdx) MISS\(level) w=\(widthKey) (Δ\(widthDelta)) len=\(bindingLen) wChg=\(widthChanged ? 1 : 0) stf=\(String(format: "%.1f", sizeMs))ms total=\(String(format: "%.1f", totalMs))ms buckets=\(coord.cachedSizes.count) cumulative=\(String(format: "%.0f", coord.sizeThatFitsTotalMs))ms hits=\(coord.sizeThatFitsCacheHitCount)/\(callIdx)")
        }

        if bindingLen >= 10_000 && totalMs >= 100 {
            Self.stfLogger.warning("[STF] ⚠️ LONG-TEXT SLOW len=\(bindingLen) totalMs=\(String(format: "%.1f", totalMs)) — repeated calls of this size can trigger 0x8BADF00D")
        }

        #if DEBUG
        // [WatchdogProbe] STF window probe — watchdog kills at 10s wall clock.
        // Track cumulative STF time per main-runloop iteration; when the same
        // runloop hop has already burned >2s in TextKit measurement, emit a
        // critical warning. This is the strongest pre-watchdog signal — a
        // single runloop iteration is what FRONTBOARD measures.
        Self.recordSTF(elapsedMs: totalMs, msgId: messageId, attrLen: bindingLen)
        #endif

        #if DEBUG
        // [T-attachment-zero-origin] Snapshot the textView used for measurement.
        // For attachment-bearing markdown this is the off-screen measureTextView
        // (where the lineFrag.width=10M bug surfaces); for attachment-free
        // content it's the live uiView itself.
        let stfMid = context.coordinator.renderer.messageId?.uuidString
        let isMeasure = (measuringTextView !== uiView)
        MarkdownSnapshotCollector.captureIfEnabled(
            textView: measuringTextView,
            phase: isMeasure ? "afterSizeThatFits.mtv" : "afterSizeThatFits.live",
            messageId: stfMid,
            isMeasureView: isMeasure,
            sizeThatFitsResult: CGSize(width: width, height: size.height)
        )
        #endif

        return CGSize(width: width, height: size.height)
    }

    private static let stfLogger = AppLogger(category: "SelectableMarkdownSTF")
    #if DEBUG
    fileprivate static let watchdogProbe = AppLogger(category: "WatchdogProbe")
    #endif

    #if DEBUG
    // MARK: WatchdogProbe — runloop-window cumulative tracking

    /// Tracks how much wall-clock time TextKit measurement has consumed on
    /// the main thread within a *single runloop iteration*. FRONTBOARD's
    /// 0x8BADF00D watchdog measures elapsed real time per scene-update
    /// cycle; when a single iteration burns >2s of STF/updateUIView, we are
    /// already a quarter of the way to termination. Reset by an idle observer
    /// installed lazily on the main runloop.
    private static var stfWindowTotalMs: Double = 0
    private static var stfWindowCallCount: Int = 0
    private static var updWindowTotalMs: Double = 0
    private static var updWindowCallCount: Int = 0
    private static var distinctMsgIds: Set<UUID> = []
    private static var observerInstalled = false
    private static let windowAlarmThresholdMs: Double = 2000
    private static var alarmAlreadyFiredThisHop = false

    fileprivate static func recordSTF(elapsedMs: Double, msgId: UUID?, attrLen: Int) {
        installWindowObserverIfNeeded()
        stfWindowTotalMs += elapsedMs
        stfWindowCallCount += 1
        if let msgId { distinctMsgIds.insert(msgId) }
        checkWindowAlarm(trigger: "stf", lastLen: attrLen, lastMsgId: msgId)
    }

    fileprivate static func recordUpdateUIView(elapsedMs: Double, attrLen: Int, msgId: UUID?) {
        installWindowObserverIfNeeded()
        updWindowTotalMs += elapsedMs
        updWindowCallCount += 1
        if let msgId { distinctMsgIds.insert(msgId) }
        checkWindowAlarm(trigger: "upd", lastLen: attrLen, lastMsgId: msgId)
    }

    private static func checkWindowAlarm(trigger: String, lastLen: Int, lastMsgId: UUID?) {
        let total = stfWindowTotalMs + updWindowTotalMs
        guard total >= windowAlarmThresholdMs, !alarmAlreadyFiredThisHop else { return }
        alarmAlreadyFiredThisHop = true
        let msgIdShort = lastMsgId?.uuidString.prefix(8) ?? "—"
        watchdogProbe.warning("[WatchdogProbe][window] 🚨 cumulative=\(String(format: "%.0f", total))ms (stf=\(String(format: "%.0f", stfWindowTotalMs)) upd=\(String(format: "%.0f", updWindowTotalMs))) sttCalls=\(stfWindowCallCount) updCalls=\(updWindowCallCount) distinctMsgs=\(distinctMsgIds.count) trigger=\(trigger) lastMsgId=\(msgIdShort) lastLen=\(lastLen) — main runloop is heading toward 0x8BADF00D (10s watchdog)")
    }

    private static func installWindowObserverIfNeeded() {
        guard !observerInstalled else { return }
        observerInstalled = true
        // beforeWaiting fires once the runloop is about to sleep — natural
        // boundary for "this hop is done". We reset and, if the hop was
        // expensive, emit a summary so the log captures it even if no alarm
        // fired (cumulative under 2s but still notable).
        let observer = CFRunLoopObserverCreateWithHandler(
            kCFAllocatorDefault,
            CFRunLoopActivity.beforeWaiting.rawValue,
            true, 0
        ) { _, _ in
            let total = stfWindowTotalMs + updWindowTotalMs
            if total >= 500 {
                watchdogProbe.info("[WatchdogProbe][hop] cumulative=\(String(format: "%.0f", total))ms stf=\(String(format: "%.0f", stfWindowTotalMs)) upd=\(String(format: "%.0f", updWindowTotalMs)) stfCalls=\(stfWindowCallCount) updCalls=\(updWindowCallCount) distinctMsgs=\(distinctMsgIds.count)")
            }
            stfWindowTotalMs = 0
            stfWindowCallCount = 0
            updWindowTotalMs = 0
            updWindowCallCount = 0
            distinctMsgIds.removeAll(keepingCapacity: true)
            alarmAlreadyFiredThisHop = false
        }
        if let observer {
            CFRunLoopAddObserver(CFRunLoopGetMain(), observer, .commonModes)
        }
    }
    #endif

    // MARK: Coordinator

    final class Coordinator: NSObject, UITextViewDelegate {
        var lastMarkdown: String = ""
        var lastRenderedContentHash: Int? = nil
        /// [T-ios-reuse-cachewipe] Identity of the content this coordinator's
        /// text view is currently showing — `blockId` when the caller renders
        /// per-block (V3 cell-per-block), else `messageId`. Used to tell a
        /// genuine *identity change* (this view was recycled onto different
        /// content) apart from *the same content being rewritten* (streaming
        /// append, regenerate). See `updateUIView`'s wipe branch.
        var lastContentId: UUID? = nil
        /// [FGReload] Markdown whose render was skipped because the app was
        /// not `.active` (see the `!= .active` guard in `updateUIView`). The
        /// skip exists to avoid the expensive `setAttributedText` +
        /// `ensureLayout` pass blocking the main thread during a scene-update
        /// transition, which trips the 0x8BADF00D watchdog (commit e6efac23).
        /// We stash the markdown here instead of dropping it so the foreground
        /// observer can replay it once the app is safely `.active` again —
        /// otherwise the last streaming frame stays blank until SwiftUI
        /// happens to re-evaluate the view body.
        var pendingMarkdown: String? = nil
        /// Re-invokes the live `updateUIView` render path for the deferred
        /// markdown. Captured fresh on every skip so it always closes over the
        /// current textView + render inputs (cachedAttributedString etc.).
        private var replayDeferredRender: (() -> Void)?

        /// Arms the foreground-replay path. Called from the `updateUIView`
        /// skip branch with a closure that re-runs the render for the current
        /// inputs. Lifecycle observers are armed eagerly in `init` — this
        /// just stores the pending state.
        func deferRender(_ markdown: String, replay: @escaping () -> Void) {
            pendingMarkdown = markdown
            replayDeferredRender = replay
        }

        private var foregroundObserver: NSObjectProtocol?
        private var resignActiveObserver: NSObjectProtocol?
        /// Wall-clock timestamp of the most recent `willResignActive` we
        /// observed. Cleared on `didBecomeActive`. The `.inactive` skip path
        /// uses this to distinguish short interruptions (control center
        /// swipe, incoming call banner — safe to render through) from
        /// long-running `.inactive` windows where the watchdog risk that
        /// commit e6efac23 fixed becomes material again. Armed eagerly in
        /// `init` so the timestamp is correct on the very first `.inactive`
        /// skip check, not only after the first deferred render.
        var inactiveEnteredAt: Date?

        override init() {
            super.init()
            foregroundObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.inactiveEnteredAt = nil
                self?.applyDeferredRenderIfNeeded()
            }
            resignActiveObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.willResignActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.inactiveEnteredAt = Date()
            }
        }

        private func applyDeferredRenderIfNeeded() {
            // Only render once the app is genuinely `.active`. didBecomeActive
            // fires after the watchdog-sensitive scene-update transition has
            // settled, so the expensive layout pass is safe here.
            guard UIApplication.shared.applicationState == .active else { return }
            guard let pending = pendingMarkdown, let replay = replayDeferredRender else { return }
            pendingMarkdown = nil
            replayDeferredRender = nil
            replay()
        }

        deinit {
            if let foregroundObserver {
                NotificationCenter.default.removeObserver(foregroundObserver)
            }
            if let resignActiveObserver {
                NotificationCenter.default.removeObserver(resignActiveObserver)
            }
        }
        var lastFontSize: CGFloat = FontSettings.shared.scaledMessage(16.5)
        var openURL: OpenURLAction?
        /// Cached sizeThatFits results keyed by integer width bucket.
        /// Multi-bucket cache is required because SwiftUI layout protocol
        /// may probe the same view at several nearby widths within one
        /// CollectionView self-sizing pass. A single-width cache was being
        /// bypassed on every probe, triggering full TextKit1 relayouts.
        var cachedSizes: [Int: CGFloat] = [:]
        var cachedSizeMarkdownCount: Int = 0
        /// Last textContainer width at which `updateUIView` ran. Used so that
        /// a rotation that arrives as `(markdown unchanged, width changed)`
        /// still invalidates TableAttachment cached layouts so the table
        /// frame re-bakes at the new width instead of sticking at the old
        /// portrait columns. Initialised to a sentinel so the very first
        /// update doesn't spuriously fire the invalidation path.
        var lastRenderedWidth: CGFloat = -1
        /// [WATCHDOG-DIAG] Counters for localizing 0x8BADF00D watchdog crash.
        var sizeThatFitsCallCount: Int = 0
        var sizeThatFitsCacheHitCount: Int = 0
        var sizeThatFitsTotalMs: Double = 0
        var streamRenderCount: Int = 0
        /// HangFix(2026-05-14) — streaming-aware approximate cache.
        /// Track per-character height (height ÷ bindingLen at last real
        /// measurement) so when `sizeThatFits` is called during streaming
        /// with a bindingLen that has only grown by a small amount, we can
        /// return `cachedHeight + (delta × pxPerChar)` without running a
        /// full off-screen render + UITextView.sizeThatFits pass.
        ///
        /// Why this matters: commit 0f58bb3 changed the STF cache key from
        /// textStorage.length to bindingLen, which fixed DeepSeek V4 char
        /// clipping but means every streamed token invalidates the cache.
        /// Each miss spawns a full prepareMarkdownForRender +
        /// renderer.render + off-screen UITextView measure, which on iPhone
        /// 17 Pro Max + 120Hz + CJK + emoji costs ~50-200ms per call.
        /// SwiftUI calls sizeThatFits at two widths per layout pass, so
        /// streaming a single message accumulates main-thread work
        /// faster than it drains -> background watchdog 0x8BADF00D.
        var lastFullMeasureBindingLen: Int = 0
        var lastFullMeasureHeight: [Int: CGFloat] = [:]
        var lastFullMeasureAt: CFAbsoluteTime = 0
        fileprivate var renderer = MarkdownNSRenderer(baseFontSize: FontSettings.shared.scaledMessage(16.5))

        /// [WordFade] The textView whose storage the fade animator mutates.
        /// Set on the append fast path so the animator's redraw closure can
        /// repaint it. Weak — the SwiftUI representable owns the view.
        weak var fadeTextView: SelectableMarkdownTextView?

        /// [WordFade] Drives word-by-word alpha fade-in for streamed tokens.
        /// Created lazily on first append so non-streaming / history cells
        /// (which never hit the fast path) never allocate a CADisplayLink.
        /// Its redraw closure repaints whatever `fadeTextView` currently points
        /// at, so a Coordinator reused across textViews stays correct.
        lazy var fadeAnimator: TextFadeAnimator = TextFadeAnimator { [weak self] in
            self?.fadeTextView?.setNeedsDisplay()
        }

        /// Off-screen textView used by `sizeThatFits` to measure pending
        /// attributedText that hasn't been committed to the on-screen
        /// textView yet. Writing pending text into the live `uiView` from
        /// inside `sizeThatFits` is what caused the watchdog hang seen in
        /// `Minis-2026-05-13-084827.ips`: it rebuilds attachment subviews
        /// and calls `addSubview` → `setNeedsLayout` on the textView
        /// during SwiftUI's measurement pass, which then triggers SwiftUI
        /// to re-invoke `sizeThatFits` indefinitely.
        ///
        /// Configured once and reused across measurement calls. Layout is
        /// driven by `sizeThatFits` — never attached to a window.
        fileprivate lazy var measureTextView: UITextView = {
            let tv = SelectableMarkdownTextView()
            tv.isScrollEnabled = false
            tv.isEditable = false
            tv.isSelectable = false
            // [ClipFix 2026-05-16] Match the live SelectableMarkdownTextView's
            // textContainerInset (init at line ~3597 sets top=4 bottom=4).
            // Previously this was `.zero`, so mtv.sizeThatFits returned 8pt
            // less than the on-screen UITextView actually rendered, and
            // every attachment-bearing cell (tables, code blocks, images)
            // got a cell.frame 8pt shorter than its content — clipping the
            // last visual line. Verified by the ClipDiag log: BR path
            // (uiView with inset=4/4) returned diff≈-0.5 while HIT path
            // (mtv-written cached value) returned diff=+8.0 for the same
            // text type.
            tv.textContainerInset = UIEdgeInsets(top: 4, left: 0, bottom: 4, right: 0)
            tv.textContainer.lineFragmentPadding = 0
            tv.backgroundColor = .clear
            return tv
        }()

        @available(iOS 17.0, *)
        func textView(_ textView: UITextView, primaryActionFor textItem: UITextItem, defaultAction: UIAction) -> UIAction? {
            if case .link(let url) = textItem.content {
                return UIAction { [weak self] _ in
                    if let openURL = self?.openURL {
                        openURL(url)
                    } else {
                        UIApplication.shared.open(url)
                    }
                }
            }
            // [D1 T-ios-table-attachment-tap-preview-ios18] A TableAttachment
            // carries a 1×1 transparent placeholder image (needed for
            // attachmentBounds), so on iOS 18 and below the host UITextView
            // treats a single tap on it as an IMAGE text-item and pops the
            // system "拷贝图像/存入相机胶卷" preview. Suppress that here by
            // returning a no-op action; the real table interaction lives on the
            // overlaid TableScrollView, which still scrolls/selects. iOS 19+
            // changed the system behavior, so leave its default untouched.
            if #available(iOS 19, *) {
                // iOS 19+: default behavior, no change.
            } else if case .textAttachment(let attachment) = textItem.content,
                      attachment is TableAttachment {
                return UIAction { _ in }
            }
            return defaultAction
        }

        @available(iOS 17.0, *)
        func textView(_ textView: UITextView, menuConfigurationFor textItem: UITextItem, defaultMenu: UIMenu) -> UITextItem.MenuConfiguration? {
            switch textItem.content {
            case .link(let url):
                let copyAction = UIAction(title: AppLocalized("Copy Link"), image: UIImage(systemName: "doc.on.doc")) { _ in
                    UIPasteboard.general.string = url.absoluteString
                }
                let openAction = UIAction(title: AppLocalized("Open in Safari"), image: UIImage(systemName: "safari")) { _ in
                    UIApplication.shared.open(url)
                }
                let menu = UIMenu(children: [copyAction, openAction])
                return UITextItem.MenuConfiguration(preview: nil, menu: menu)
            case .textAttachment(let attachment):
                // [D1 T-ios-table-attachment-tap-preview-ios18] On iOS 18 and
                // below, a tap on a table attachment must not present the image
                // preview. Offer a real "Copy Table" menu (tab-separated text,
                // same payload as the TableScrollView copyTable path) instead of
                // the empty menu, and a nil preview so no image sheet appears.
                // iOS 19+ behavior is unchanged (falls through to the logic
                // below).
                if #available(iOS 19, *) {
                    // iOS 19+: no special-casing, use the logic below.
                } else if let tableAttach = attachment as? TableAttachment {
                    let copyTableAction = UIAction(
                        title: AppLocalized("Copy Table"),
                        image: UIImage(systemName: "tablecells")
                    ) { _ in
                        UIPasteboard.general.string = tableAttach.plainText()
                    }
                    return UITextItem.MenuConfiguration(preview: nil, menu: UIMenu(children: [copyTableAction]))
                }
                guard let imageAttach = attachment as? ImageAttachment,
                      let image = imageAttach.loadedImage else {
                    // Suppress system default menu (which would copy/save the
                    // 1×1 transparent placeholder) when no real image is loaded.
                    return UITextItem.MenuConfiguration(preview: nil, menu: UIMenu(children: []))
                }
                let copyAction = UIAction(title: AppLocalized("Copy Image"), image: UIImage(systemName: "doc.on.doc")) { _ in
                    UIPasteboard.general.image = image
                }
                let saveAction = UIAction(title: AppLocalized("Save to Camera Roll"), image: UIImage(systemName: "square.and.arrow.down")) { _ in
                    PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                        guard status == .authorized || status == .limited else { return }
                        PHPhotoLibrary.shared().performChanges {
                            PHAssetChangeRequest.creationRequestForAsset(from: image)
                        }
                    }
                }
                let menu = UIMenu(children: [copyAction, saveAction])
                return UITextItem.MenuConfiguration(preview: nil, menu: menu)
            @unknown default:
                return nil
            }
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            (textView as? SelectableMarkdownTextView)?.updateSelectionState()
        }

        func textView(_ textView: UITextView, shouldInteractWith URL: URL, in characterRange: NSRange, interaction: UITextItemInteraction) -> Bool {
            if interaction == .presentActions {
                let alert = UIAlertController(title: URL.absoluteString, message: nil, preferredStyle: .actionSheet)
                alert.addAction(UIAlertAction(title: AppLocalized("Copy Link"), style: .default) { _ in
                    UIPasteboard.general.string = URL.absoluteString
                })
                alert.addAction(UIAlertAction(title: AppLocalized("Open in Safari"), style: .default) { _ in
                    UIApplication.shared.open(URL)
                })
                alert.addAction(UIAlertAction(title: AppLocalized("Cancel"), style: .cancel))
                if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                   let root = scene.windows.first?.rootViewController {
                    var presenter = root
                    while let presented = presenter.presentedViewController { presenter = presented }
                    if let popover = alert.popoverPresentationController {
                        popover.sourceView = textView
                        let glyphRange = textView.layoutManager.glyphRange(forCharacterRange: characterRange, actualCharacterRange: nil)
                        popover.sourceRect = textView.layoutManager.boundingRect(forGlyphRange: glyphRange, in: textView.textContainer)
                    }
                    presenter.present(alert, animated: true)
                }
                return false
            }
            if let openURL {
                openURL(URL)
            } else {
                UIApplication.shared.open(URL)
            }
            return false
        }
    }
}

// MARK: - Minis URL Resolution (bridged from AIChatView)

/// Resolves a `minis://` URL to a local file URL.
/// Keeps resolution behavior aligned with AIChatView's minis resolver.
private func resolveMinisFileURLForNativeText(url: URL) -> URL? {
    guard url.scheme == "minis" else { return nil }
    guard let host = url.host else {
        imgLogger.warning("[MinisImage][Resolve] no host in URL: \(url.absoluteString)")
        return nil
    }
    // Try the single-decoded subpath first; fall back to a double-decoded
    // variant when an inline-image link arrives double-encoded. This is the
    // same tolerant resolution the other 4 minis:// resolvers already use; this
    // one (the inline-markdown image resolver) was the remaining single-decode
    // holdout, so double-encoded non-ASCII inline image names still failed here.
    // [T-ios-file-preview-stale-cache, completing T-fix-double-encoding 2026-06-01]
    let subPaths = MinisURLPathDecoding.subPathCandidates(for: url)
    imgLogger.info("[MinisImage][Resolve] BEGIN url=\(url.absoluteString) host=\(host) subPaths=\(subPaths) activeSession=\(AIChatViewModel.activeSessionId ?? "nil")")
    let fm = FileManager.default

    // Primary: active session persistent storage
    if let sid = AIChatViewModel.activeSessionId {
        for subPath in subPaths {
            let persistURL = AIChatViewModel.minisPersistentBase
                .appendingPathComponent(sid, isDirectory: true)
                .appendingPathComponent(host, isDirectory: true)
                .appendingPathComponent(subPath)
            imgLogger.info("[MinisImage][Resolve] checking session path=\(persistURL.path)")
            if fm.fileExists(atPath: persistURL.path) {
                let size = (try? fm.attributesOfItem(atPath: persistURL.path)[.size] as? Int64) ?? -1
                imgLogger.info("[MinisImage][Resolve] FOUND in active session=\(sid) path=\(persistURL.path) size=\(size)")
                return persistURL
            }
        }
        imgLogger.info("[MinisImage][Resolve] NOT in active session=\(sid)")
    } else {
        imgLogger.info("[MinisImage][Resolve] no active session — skipping session lookup")
    }

    // Global namespaces (not session-scoped)
    if host == "skills" {
        for subPath in subPaths {
            let candidate = AIChatViewModel.minisSkillsPersistentDir.appendingPathComponent(subPath)
            imgLogger.info("[MinisImage][Resolve] checking global skills path=\(candidate.path)")
            if fm.fileExists(atPath: candidate.path) {
                let size = (try? fm.attributesOfItem(atPath: candidate.path)[.size] as? Int64) ?? -1
                imgLogger.info("[MinisImage][Resolve] FOUND global skills path=\(candidate.path) size=\(size)")
                return candidate
            }
        }
    } else if host == "memory" {
        for subPath in subPaths {
            let candidate = AIChatViewModel.minisMemoryPersistentDir.appendingPathComponent(subPath)
            imgLogger.info("[MinisImage][Resolve] checking global memory path=\(candidate.path)")
            if fm.fileExists(atPath: candidate.path) {
                let size = (try? fm.attributesOfItem(atPath: candidate.path)[.size] as? Int64) ?? -1
                imgLogger.info("[MinisImage][Resolve] FOUND global memory path=\(candidate.path) size=\(size)")
                return candidate
            }
        }
    }

    // [T-ios-minisurl-cross-session-isolation] DO NOT scan all session dirs.
    // This is the main image-render resolve path; the old "scan every session
    // and return the first same-named file" loop was the same P0 isolation
    // leak fixed in resolveMinisFileURL — session B rendering
    // model-use-zimage-0.jpg would resolve to session A's image. minis:// is
    // session-scoped; not-found is the correct result for a cross-session ref.
    //
    // The rootfs `/var/minis/<host>` fallback is kept ONLY for the global,
    // non-session-scoped namespaces (skills/memory/shared) — for those it just
    // points at the same global dir the iSH mount binds to. We explicitly
    // restrict it to those hosts so it can never reach another session's
    // attachments via whichever session happens to be mounted.
    let globalHosts: Set<String> = ["skills", "memory", "shared"]
    if globalHosts.contains(host) {
        for subPath in subPaths {
            let rootfsURL = RootfsManager.shared.dataPath
                .appendingPathComponent("var/minis", isDirectory: true)
                .appendingPathComponent(host, isDirectory: true)
                .appendingPathComponent(subPath)
            if fm.fileExists(atPath: rootfsURL.path) {
                let size = (try? fm.attributesOfItem(atPath: rootfsURL.path)[.size] as? Int64) ?? -1
                imgLogger.info("[MinisImage][Resolve] FOUND in global rootfs path=\(rootfsURL.path) size=\(size)")
                return rootfsURL
            }
        }
    }

    imgLogger.warning("[MinisImage][Resolve] NOT FOUND in active session / global dirs url=\(url.absoluteString) host=\(host) subPaths=\(subPaths) (cross-session scan disabled for isolation)")
    return nil
}
