//
//  TerminalCanvasView.swift
//  MinisApp
//
//  UIScrollView-backed terminal renderer using UITextView for native text selection.
//  Uses NSAttributedString for ANSI color rendering with a cursor overlay.
//

import SwiftUI
import UIKit
import CoreText
import Combine

// MARK: - Cell Metrics (shared)

struct TerminalCellMetrics {
    let cellWidth: CGFloat
    let cellHeight: CGFloat
    let ascent: CGFloat
    let descent: CGFloat
    let font: CTFont

    static let fontSize: CGFloat = 12
    static let fontName = "Menlo"

    static let shared: TerminalCellMetrics = {
        let font = CTFontCreateWithName(fontName as CFString, fontSize, nil)

        var chars: [UniChar] = [0x4D] // 'M'
        var glyphs: [CGGlyph] = [0]
        CTFontGetGlyphsForCharacters(font, &chars, &glyphs, 1)
        var advanceSize = CGSize.zero
        CTFontGetAdvancesForGlyphs(font, .horizontal, &glyphs, &advanceSize, 1)

        let cellWidth = ceil(advanceSize.width)
        let ascent = CTFontGetAscent(font)
        let descent = CTFontGetDescent(font)
        let leading = CTFontGetLeading(font)
        let cellHeight = ceil(ascent + descent + leading)

        return TerminalCellMetrics(
            cellWidth: cellWidth,
            cellHeight: cellHeight,
            ascent: ascent,
            descent: descent,
            font: font
        )
    }()
}

// MARK: - SwiftUI Bridge

struct TerminalCanvasView: UIViewRepresentable {
    @ObservedObject var emulator: TerminalEmulator
    var onResize: ((Int, Int) -> Void)?
    var onPaste: ((Data) -> Void)?
    var onTap: (() -> Void)?
    var onDoubleTap: (() -> Void)?

    func makeCoordinator() -> TerminalScrollCoordinator {
        TerminalScrollCoordinator(emulator: emulator, onResize: onResize, onPaste: onPaste)
    }

    func makeUIView(context: Context) -> TerminalScrollContainerView {
        let container = TerminalScrollContainerView(
            emulator: emulator,
            coordinator: context.coordinator
        )
        context.coordinator.containerView = container
        container.onTap = onTap
        container.onDoubleTap = onDoubleTap
        return container
    }

    func updateUIView(_ container: TerminalScrollContainerView, context: Context) {
        // Only refresh callbacks. Rendering is driven by the emulator.$version
        // Combine subscription inside the container — calling terminalDidUpdate
        // here would run a full attributed-string rebuild every SwiftUI
        // redraw (sibling view changes, state updates, etc.), which is both
        // redundant and a flicker source.
        let coord = context.coordinator
        coord.onResize = onResize
        coord.onPaste = onPaste
        container.onTap = onTap
        container.onDoubleTap = onDoubleTap
    }
}

/// Scoped logger for diagnosing terminal flicker/redraw. Remove once the
/// flicker investigation is done.
enum TerminalRedrawLog {
    private static let logger = AppLogger(category: "TerminalRedraw")
    static func log(_ message: @autoclosure () -> String) {
        logger.info(message())
    }

    /// Monotonic ms since process start. Use for pairing events across paths
    /// without worrying about wall-clock log granularity.
    static func nowMs() -> Double {
        CFAbsoluteTimeGetCurrent() * 1000.0
    }

    /// End-to-end latency probe: set by TerminalKeyInputView.insertText
    /// when a keystroke enters the pipeline, cleared by terminalDidUpdate
    /// on the first render that happens afterwards. Exposes the full
    /// "key pressed → pixels on screen" time, which is what the user feels.
    static var pendingInputAt: Double? = nil
    static var pendingInputChar: String = ""
}

// MARK: - Terminal UITextView (filtered edit menu)

final class TerminalTextView: UITextView {
    var onPasteAction: (() -> Void)?

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        switch action {
        case #selector(copy(_:)),
             #selector(selectAll(_:)):
            return super.canPerformAction(action, withSender: sender)
        case #selector(paste(_:)):
            return UIPasteboard.general.hasStrings
        default:
            // Block Speak, Look Up, Share, etc.
            return false
        }
    }

    override func paste(_ sender: Any?) {
        // Clear any selection left over from the long-press edit menu
        // BEFORE the paste bytes flow into the emulator. TerminalScrollContainerView
        // short-circuits `terminalDidUpdate` while `selectedTextRange` is non-empty
        // (to avoid interrupting copy gestures). If we leave the selection in place,
        // the paste output is queued in the buffer but never rendered until the
        // user's next keystroke clears the selection — which matches the reported
        // "nothing appears until I type again" symptom.
        if let start = position(from: beginningOfDocument, offset: 0) {
            selectedTextRange = textRange(from: start, to: start)
        } else {
            selectedTextRange = nil
        }
        onPasteAction?()
    }
}

// MARK: - Glyph Advance Cache Key

/// Key for caching measured glyph advances, keyed by character + font identity.
///
/// [T-terminal-glyph-cache-hash] Neither field is a String, deliberately.
///
/// This key used to carry `font.fontName`, and hashing it trapped inside the
/// Swift runtime — `_StringGutsSlice._normalizedHash` →
/// `_StringGuts.withFastUTF8` → `_fatalErrorMessage`, five identical reports
/// on 1.14(12), all on iOS 27.0 beta (24A5430a). `withFastUTF8` fails that way
/// when the string is not natively stored, which is what a lazily-bridged
/// NSString from UIKit looks like. The character alone is a `Character`, whose
/// hash goes through the same normalization path.
///
/// `ObjectIdentifier` sidesteps the font half: the four fonts are stored
/// properties held for the view's lifetime (regular / bold / italic /
/// boldItalic), so their identity is stable and unique for exactly as long as
/// the cache lives.
///
/// [T-terminal-glyph-key-no-character] The character half took one more round.
/// That first fix replaced `Character` with `character.unicodeScalars.map(\.value)`,
/// which still reads String storage — and 1.14(16) crashed in exactly that
/// read, on iOS 26.6:
///
///   String.UnicodeScalarView.distance(from:to:)
///   → Collection.count.getter
///   → Collection.map
///   → GlyphAdvanceKey.init(character:font:)
///
/// with x0 = 0 and `esr 0x56000080`. Despite the SIGSEGV label that ESR
/// decodes to EC 0x15 — an SVC, i.e. the Swift runtime trapping on purpose,
/// the same *class* of failure as the EXC_BREAKPOINT before it, not a wild
/// pointer. `map` asks the scalar view for its `count` to size the result
/// buffer, and counting walks the string's storage; the earlier report
/// trapped hashing that storage, this one trapped measuring it.
///
/// So the key no longer touches a `Character` at all. The grid already knows
/// each cell's scalars — the ANSI parser decoded them from UTF-8 bytes on the
/// way in — so `TerminalCell` carries them as plain integers and the key just
/// copies the value across. Nothing here reads String storage, allocates, or
/// normalizes.
///
/// Two characters that normalize alike occupy separate entries; each measures
/// to the same advance, so the only cost is a duplicate row.
private struct GlyphAdvanceKey: Hashable {
    /// The cell's scalars, inline for the single-scalar case that is almost
    /// every cell (ASCII, CJK, and any unaccented glyph). `rest` stays empty
    /// unless the cell holds a combining sequence, so the common path does no
    /// heap allocation at all.
    let first: UInt32
    let rest: [UInt32]
    let font: ObjectIdentifier

    init(glyph: TerminalCell.Glyph, font: UIFont) {
        self.first = glyph.first
        self.rest = glyph.rest
        self.font = ObjectIdentifier(font)
    }
}

// MARK: - Container View (owns UIScrollView + UITextView + cursor overlay)

final class TerminalScrollContainerView: UIView {
    let scrollView: UIScrollView
    let textView: TerminalTextView
    let cursorOverlay: TerminalCursorOverlayView
    private let emulator: TerminalEmulator
    private weak var coordinator: TerminalScrollCoordinator?
    private var lastReportedCols: Int = 0
    private var lastReportedRows: Int = 0
    private var lastRenderedVersion: UInt64 = UInt64.max
    private var hasScrolledToBottomOnce = false
    private var cancellable: AnyCancellable?
    var onTap: (() -> Void)?
    var onDoubleTap: (() -> Void)?
    private var resizeDebounceTimer: Timer?

    // MARK: - Frozen scrollback cache
    //
    // buildAttributedString() over a large scrollback is O(cells) and takes
    // ~100ms+ at 1500 lines. Most renders only add a few lines at the tail,
    // so we cache the oldest N scrollback lines once built and reuse that
    // NSAttributedString. Only lines past the freeze line + current grid
    // are rebuilt per render.
    //
    // Invariants:
    //   - frozenAttr represents scrollback[0..<frozenCount] as of the freeze.
    //   - frozenHeadIndex is emulator.activeBuffer.scrollbackHeadIndex at
    //     the freeze. If the buffer's current headIndex differs, the oldest
    //     cached lines have been trimmed from the front → invalidate.
    //   - The active window (last `activeWindow` scrollback lines) is always
    //     rebuilt live so recent redraws (e.g. terminal apps rewriting the
    //     last few lines after output) stay correct.
    private var frozenAttr: NSMutableAttributedString?
    private var frozenCount: Int = 0
    private var frozenHeadIndex: Int = 0
    /// Number of most-recent scrollback lines to always rebuild live.
    private let activeWindow: Int = 200
    /// Only start freezing once scrollback grows past this size.
    private let freezeMinSize: Int = 300

    /// Length of the frozen prefix currently installed in textStorage.
    /// Equal to frozenAttr?.length at last render (0 if never committed).
    /// Used to incrementally update textStorage when the cache grows/trims.
    private var committedFrozenLength: Int = 0
    /// Length of the live tail currently installed in textStorage. Tail
    /// starts at `committedFrozenLength + connectorNewlineLength` when
    /// a frozen prefix exists, or 0 otherwise.
    private var committedTailLength: Int = 0
    /// Whether a frozen connector newline has been inserted. Becomes true
    /// when committedFrozenLength transitions from 0 to >0.
    private var committedHasConnector: Bool = false

    // Pre-resolved fonts
    private let regularFont: UIFont
    private let boldFont: UIFont
    private let italicFont: UIFont
    private let boldItalicFont: UIFont

    /// Cache of glyph advances keyed by (character string, font pointer).
    /// Used to compute kern adjustments that snap every character to the grid.
    private var glyphAdvanceCache: [GlyphAdvanceKey: CGFloat] = [:]

    init(emulator: TerminalEmulator, coordinator: TerminalScrollCoordinator) {
        self.emulator = emulator
        self.coordinator = coordinator

        let name = TerminalCellMetrics.fontName
        let size = TerminalCellMetrics.fontSize
        self.regularFont = UIFont(name: name, size: size)
            ?? .monospacedSystemFont(ofSize: size, weight: .regular)
        self.boldFont = UIFont(name: "\(name)-Bold", size: size)
            ?? .monospacedSystemFont(ofSize: size, weight: .bold)
        self.italicFont = UIFont(name: "\(name)-Italic", size: size)
            ?? .monospacedSystemFont(ofSize: size, weight: .regular)
        self.boldItalicFont = UIFont(name: "\(name)-BoldItalic", size: size)
            ?? .monospacedSystemFont(ofSize: size, weight: .bold)

        // Set up UIScrollView (outer scroll container)
        self.scrollView = UIScrollView()

        // Set up UITextView (non-editable, selectable)
        self.textView = TerminalTextView()
        self.cursorOverlay = TerminalCursorOverlayView()

        super.init(frame: .zero)

        backgroundColor = .black

        // Configure scroll view
        scrollView.backgroundColor = .black
        scrollView.delegate = coordinator
        scrollView.alwaysBounceVertical = true
        scrollView.showsVerticalScrollIndicator = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.indicatorStyle = .white
        scrollView.keyboardDismissMode = .none
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.delaysContentTouches = false
        scrollView.canCancelContentTouches = true

        // Configure text view
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false  // Outer scrollView handles scrolling
        textView.backgroundColor = .black
        // Use a visible selection highlight on dark background
        textView.tintColor = UIColor(red: 0.4, green: 0.6, blue: 1.0, alpha: 1.0)
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.autocorrectionType = .no
        // We run our own URL detection inside `buildRange` and add .link
        // attributes to matching ranges. `dataDetectorTypes` is left empty
        // so UIKit doesn't also try to detect phone numbers / addresses.
        textView.dataDetectorTypes = []
        textView.keyboardDismissMode = .none
        textView.linkTextAttributes = [
            .foregroundColor: UIColor(red: 0.4, green: 0.75, blue: 1.0, alpha: 1.0),
            .underlineStyle: NSUnderlineStyle.single.rawValue
        ]
        textView.delegate = self
        textView.onPasteAction = { [weak self] in
            if let text = UIPasteboard.general.string, !text.isEmpty,
               let data = text.data(using: .utf8) {
                self?.coordinator?.onPaste?(data)
            }
        }

        // Cursor overlay sits on top of textView
        cursorOverlay.isUserInteractionEnabled = false
        cursorOverlay.backgroundColor = .clear

        addSubview(scrollView)
        scrollView.addSubview(textView)
        scrollView.addSubview(cursorOverlay)

        // Single tap toggles the keyboard (show/hide). Double tap sends Tab.
        // Added to scrollView so they work on empty areas and don't conflict
        // with text selection (long-press on textView).
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.cancelsTouchesInView = false
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.cancelsTouchesInView = false
        tap.require(toFail: doubleTap)
        scrollView.addGestureRecognizer(doubleTap)
        scrollView.addGestureRecognizer(tap)

        // Observe emulator version changes
        cancellable = emulator.$version
            .dropFirst()
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.terminalDidUpdate()
            }
    }

    required init?(coder: NSCoder) { fatalError() }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        // A tap on a selectable UITextView can make it the first responder for
        // cursor placement, which steals focus from the hidden input view.
        // Resign here so the keyboard state tracked in ISHTerminalView stays
        // in sync.
        TerminalRedrawLog.log("handleTap textView.isFirstResponder=\(textView.isFirstResponder)")
        if textView.isFirstResponder {
            textView.resignFirstResponder()
        }
        onTap?()
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        onDoubleTap?()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let sizeChanged = scrollView.frame != bounds
        let wasAtBottom = sizeChanged && isAtBottom()
        scrollView.frame = bounds
        if sizeChanged {
            TerminalRedrawLog.log("layoutSubviews bounds=\(bounds.size)")
        }
        reportSizeIfNeeded()
        updateContentLayout()
        // Keyboard show/hide drives layoutSubviews continuously over ~250ms.
        // Without refreshing these, the cursor overlay stays at its old
        // position/rect while the overlay's frame resizes, and the scroll
        // view's auto-adjusted contentOffset can push the cursor off-screen.
        if sizeChanged {
            updateCursorOverlay()
            if wasAtBottom {
                scrollToBottom(animated: false)
            }
        }
    }

    private func reportSizeIfNeeded() {
        let metrics = TerminalCellMetrics.shared
        let cols = max(1, Int(bounds.width / metrics.cellWidth))
        let rows = max(1, Int(bounds.height / metrics.cellHeight))
        guard cols != lastReportedCols || rows != lastReportedRows else { return }

        // Debounce: wait 0.4s of stable bounds before sending SIGWINCH to the shell.
        // Keyboard animation fires layoutSubviews many times over ~0.25s;
        // without debounce each frame sends a resize that causes the shell to redraw
        // and overwrite the visible grid.
        resizeDebounceTimer?.invalidate()
        resizeDebounceTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
            guard let self else { return }
            let stableCols = max(1, Int(self.bounds.width / metrics.cellWidth))
            let stableRows = max(1, Int(self.bounds.height / metrics.cellHeight))
            guard stableCols != self.lastReportedCols || stableRows != self.lastReportedRows else { return }
            TerminalRedrawLog.log("SIGWINCH cols=\(stableCols) rows=\(stableRows) (was \(self.lastReportedCols)x\(self.lastReportedRows))")
            self.lastReportedCols = stableCols
            self.lastReportedRows = stableRows
            self.coordinator?.onResize?(stableCols, stableRows)
        }
    }

    func terminalDidUpdate() {
        guard emulator.version != lastRenderedVersion else { return }

        // Don't update while the user has text selected — setting
        // attributedText clears the selection and interrupts copy gestures.
        if let sel = textView.selectedTextRange, !sel.isEmpty {
            return
        }

        let prevVersion = lastRenderedVersion
        lastRenderedVersion = emulator.version

        let wasAtBottom = isAtBottom()

        let tStart = TerminalRedrawLog.nowMs()
        let result = applyIncrementalUpdate()
        let tEnd = TerminalRedrawLog.nowMs()

        // End-to-end "keystroke → rendered" latency. Consume the pending
        // marker so the next input starts a fresh measurement.
        var e2eSuffix = ""
        if let pressedAt = TerminalRedrawLog.pendingInputAt {
            let e2e = tEnd - pressedAt
            e2eSuffix = String(format: " key='%@' e2e=%.1fms",
                TerminalRedrawLog.pendingInputChar, e2e)
            TerminalRedrawLog.pendingInputAt = nil
        }

        TerminalRedrawLog.log(String(format: "terminalDidUpdate v%llu->%llu len=%d build=%.1fms apply=%.1fms total=%.1fms scrollback=%d %@%@",
            prevVersion, emulator.version, textView.textStorage.length,
            result.buildMs, result.applyMs, tEnd - tStart,
            emulator.activeBuffer.scrollback.count,
            result.path, e2eSuffix))

        updateContentLayout()

        if wasAtBottom || !hasScrolledToBottomOnce {
            scrollToBottom(animated: false)
            hasScrolledToBottomOnce = true
        }

        updateCursorOverlay()
    }

    private func updateContentLayout() {
        let metrics = TerminalCellMetrics.shared
        let totalLines = emulator.activeBuffer.scrollback.count + emulator.rows
        let contentHeight = CGFloat(totalLines) * metrics.cellHeight
        let contentWidth = bounds.width

        // Set text container width to exactly match the grid so lines never wrap
        let gridWidth = CGFloat(emulator.cols) * metrics.cellWidth
        textView.textContainer.size = CGSize(width: gridWidth, height: CGFloat.greatestFiniteMagnitude)

        let newSize = CGSize(width: max(contentWidth, 1), height: max(contentHeight, bounds.height))
        if textView.frame.size != newSize {
            TerminalRedrawLog.log("updateContentLayout textView \(textView.frame.size) -> \(newSize)")
            textView.frame = CGRect(origin: .zero, size: newSize)
            scrollView.contentSize = newSize
        }
        cursorOverlay.frame = textView.frame
    }

    func updateCursorOverlay() {
        let metrics = TerminalCellMetrics.shared
        let buf = emulator.activeBuffer
        let scrollOff = computeScrollOffset()

        cursorOverlay.isHidden = scrollOff != 0 || !emulator.cursorVisible

        if !cursorOverlay.isHidden {
            let scrollbackCount = buf.scrollback.count
            let cursorAbsRow = scrollbackCount + buf.cursorRow

            // Position cursor directly from grid coordinates.
            // This avoids layout-manager drift caused by emoji/CJK glyphs
            // whose rendered advance doesn't match the monospace cell width.
            let x = CGFloat(buf.cursorCol) * metrics.cellWidth
            let y = CGFloat(cursorAbsRow) * metrics.cellHeight

            cursorOverlay.cursorRect = CGRect(
                x: x, y: y,
                width: metrics.cellWidth, height: metrics.cellHeight
            )
            cursorOverlay.cursorShape = emulator.cursorShape
            cursorOverlay.setNeedsDisplay()
        }
    }

    private func computeScrollOffset() -> Int {
        let metrics = TerminalCellMetrics.shared
        let maxOffsetY = scrollView.contentSize.height - scrollView.bounds.height
        if maxOffsetY <= 0 { return 0 }
        let distanceFromBottom = maxOffsetY - scrollView.contentOffset.y
        return max(0, Int(distanceFromBottom / metrics.cellHeight))
    }

    private func isAtBottom() -> Bool {
        let maxOffsetY = scrollView.contentSize.height - scrollView.bounds.height
        return maxOffsetY <= 0 || scrollView.contentOffset.y >= maxOffsetY - 2
    }

    func scrollToBottom(animated: Bool) {
        let maxOffsetY = max(0, scrollView.contentSize.height - scrollView.bounds.height)
        scrollView.setContentOffset(CGPoint(x: 0, y: maxOffsetY), animated: animated)
    }

    // MARK: - NSAttributedString Building

    struct UpdateResult {
        var buildMs: Double
        var applyMs: Double
        var path: String
    }

    /// Re-run the frozen-cache management and push incremental changes into
    /// textView.textStorage. Avoids the O(document) re-layout cost of
    /// assigning `textView.attributedText = ...`.
    private func applyIncrementalUpdate() -> UpdateResult {
        let buf = emulator.activeBuffer
        let scrollbackCount = buf.scrollback.count

        // --- Cache invariant maintenance (in-memory only; textStorage
        // diffed against committed state below).
        var wholesaleInvalidated = false
        var trimLeadingChars = 0

        // Regress: buffer was cleared or swapped to empty alternate.
        if let _ = frozenAttr, buf.scrollbackHeadIndex < frozenHeadIndex {
            frozenAttr = nil
            frozenCount = 0
            frozenHeadIndex = 0
            wholesaleInvalidated = true
        }

        // Front trim: scrollback is full and pushed old lines out. Drop the
        // same number of lines from the cache head without invalidating.
        if !wholesaleInvalidated,
           let cache = frozenAttr,
           buf.scrollbackHeadIndex > frozenHeadIndex {
            let linesToDrop = buf.scrollbackHeadIndex - frozenHeadIndex
            let preLen = cache.length
            let dropped = trimLeadingLines(from: cache, count: linesToDrop)
            trimLeadingChars = preLen - cache.length
            frozenCount -= dropped
            frozenHeadIndex += dropped
            if frozenCount <= 0 {
                frozenAttr = nil
                frozenCount = 0
                // Falls through; committed state synced below.
            }
        }

        // Defensive: unexpected shrink.
        if !wholesaleInvalidated,
           let _ = frozenAttr,
           scrollbackCount < frozenCount {
            frozenAttr = nil
            frozenCount = 0
            frozenHeadIndex = 0
            wholesaleInvalidated = true
        }

        // Decide freeze line.
        let desiredFrozen: Int
        if scrollbackCount >= freezeMinSize {
            desiredFrozen = max(0, scrollbackCount - activeWindow)
        } else {
            desiredFrozen = 0
        }

        // Extend cache (build new suffix portion that will be spliced into
        // textStorage AND the cache).
        let tBuild0 = TerminalRedrawLog.nowMs()
        var frozenAppendString: NSAttributedString? = nil
        if desiredFrozen > frozenCount {
            let newSuffix = buildRange(
                lines: Array(buf.scrollback[frozenCount..<desiredFrozen]),
                trailingNewline: false)
            if let cache = frozenAttr {
                cache.append(newlineAttrString())
                cache.append(newSuffix)
            } else {
                frozenAttr = NSMutableAttributedString(attributedString: newSuffix)
            }
            frozenCount = desiredFrozen
            frozenHeadIndex = buf.scrollbackHeadIndex
            frozenAppendString = newSuffix
        }

        // Build the live tail.
        let tailScrollback = frozenCount < scrollbackCount
            ? Array(buf.scrollback[frozenCount..<scrollbackCount])
            : []
        let tailLines = tailScrollback + buf.grid
        let tail = buildRange(lines: tailLines, trailingNewline: false)
        let tBuild1 = TerminalRedrawLog.nowMs()

        // --- Apply to textStorage.
        let storage = textView.textStorage
        let tApply0 = TerminalRedrawLog.nowMs()
        var path: String

        storage.beginEditing()
        defer {
            storage.endEditing()
        }

        if wholesaleInvalidated {
            // Full reset: replace everything.
            let all = NSMutableAttributedString()
            if let frozen = frozenAttr, frozen.length > 0 {
                all.append(frozen)
                all.append(newlineAttrString())
            }
            all.append(tail)
            storage.setAttributedString(all)
            committedFrozenLength = frozenAttr?.length ?? 0
            committedHasConnector = committedFrozenLength > 0
            committedTailLength = tail.length
            path = "wholesale"
        } else {
            // Incremental mutations.
            var mutations: [String] = []

            // 1. Drop leading chars if front-trimmed.
            if trimLeadingChars > 0 {
                let dropRange = NSRange(location: 0, length: min(trimLeadingChars, storage.length))
                storage.deleteCharacters(in: dropRange)
                committedFrozenLength -= trimLeadingChars
                if committedFrozenLength < 0 { committedFrozenLength = 0 }
                mutations.append("trim\(trimLeadingChars)")
            }

            // 2. If frozen cache was fully evicted by trimming but we still
            // have a committed connector, drop the connector too and reset.
            if frozenAttr == nil && committedFrozenLength > 0 {
                // Evicted via trim; storage now has stale content — redo
                // as wholesale.
                storage.setAttributedString(tail)
                committedFrozenLength = 0
                committedHasConnector = false
                committedTailLength = tail.length
                return UpdateResult(buildMs: tBuild1 - tBuild0,
                                    applyMs: TerminalRedrawLog.nowMs() - tApply0,
                                    path: "trim-evict")
            }

            // 3. Append newly-frozen suffix into storage (before the tail).
            //
            // Layout cases:
            //  - No frozen/connector committed yet: insert [append][\n] at 0;
            //    the existing tail (if any) stays at the new tailStart.
            //    committedHasConnector becomes true.
            //  - Already have frozen + connector committed: insert [\n][append]
            //    at committedFrozenLength. That puts a separator newline
            //    between the old frozen content and the new suffix while the
            //    pre-existing connector at position committedFrozenLength
            //    slides right to sit after the new suffix — i.e. it becomes
            //    the new frozen→tail connector. Length added to frozen
            //    portion = 1 (separator) + append.length.
            if let append = frozenAppendString, append.length > 0 {
                let combined = NSMutableAttributedString()
                if !committedHasConnector {
                    combined.append(append)
                    combined.append(newlineAttrString())
                    storage.insert(combined, at: 0)
                    committedFrozenLength += append.length
                    committedHasConnector = true
                    mutations.append("append0+\(append.length)")
                } else {
                    combined.append(newlineAttrString())
                    combined.append(append)
                    storage.insert(combined, at: committedFrozenLength)
                    // +1 for the separator newline that now lives inside the
                    // committed frozen portion (the old connector slid to
                    // become the new frozen→tail boundary).
                    committedFrozenLength += 1 + append.length
                    mutations.append("append+\(1 + append.length)")
                }
            }

            // 4. Replace the tail portion.
            let tailStart = committedFrozenLength + (committedHasConnector ? 1 : 0)
            let oldTailRange = NSRange(
                location: tailStart,
                length: max(0, storage.length - tailStart))
            storage.replaceCharacters(in: oldTailRange, with: tail)
            committedTailLength = tail.length
            mutations.append("tail\(tail.length)")

            path = mutations.joined(separator: ",")
        }

        let tApply1 = TerminalRedrawLog.nowMs()
        return UpdateResult(
            buildMs: tBuild1 - tBuild0,
            applyMs: tApply1 - tApply0,
            path: path)
    }

    /// Drop the first `count` newline-delimited lines from a mutable cache
    /// in place. Returns the number of lines actually dropped (may be less
    /// than requested if the cache has fewer lines). O(length) but runs
    /// rarely (only when scrollback trims its front).
    private func trimLeadingLines(from cache: NSMutableAttributedString, count: Int) -> Int {
        guard count > 0, cache.length > 0 else { return 0 }
        let str = cache.string as NSString
        var idx = 0
        var dropped = 0
        while dropped < count && idx < str.length {
            let range = str.range(of: "\n", options: [], range: NSRange(location: idx, length: str.length - idx))
            if range.location == NSNotFound {
                // No more newlines — this means one "line" remains at the
                // end with no trailing newline. Drop it entirely.
                cache.deleteCharacters(in: NSRange(location: 0, length: cache.length))
                return dropped + 1
            }
            idx = range.location + range.length
            dropped += 1
        }
        cache.deleteCharacters(in: NSRange(location: 0, length: idx))
        return dropped
    }

    /// Styled newline separator used between frozen prefix and live tail.
    /// Matches the per-line newlines produced inside buildRange.
    private func newlineAttrString() -> NSAttributedString {
        let metrics = TerminalCellMetrics.shared
        let paraStyle = NSMutableParagraphStyle()
        let fontNaturalHeight = regularFont.lineHeight
        paraStyle.lineHeightMultiple = metrics.cellHeight / fontNaturalHeight
        paraStyle.lineSpacing = 0
        paraStyle.paragraphSpacing = 0
        paraStyle.paragraphSpacingBefore = 0
        return NSAttributedString(string: "\n", attributes: [
            .font: regularFont,
            .foregroundColor: UIColor.clear,
            .backgroundColor: TerminalPalette.defaultBackground,
            .paragraphStyle: paraStyle
        ])
    }

    // MARK: - URL Link Detection

    private static let urlDetector: NSDataDetector? = try? NSDataDetector(
        types: NSTextCheckingResult.CheckingType.link.rawValue)

    /// Characters that can legally appear inside an http(s) URL. Used to
    /// detect soft-wrap continuations — if the char before a `\n` and the
    /// char after it are both URL-body chars, the newline is almost
    /// certainly a terminal soft-wrap splitting one logical URL across
    /// two grid rows, not a real line break.
    private static let urlBodyChars: Set<Character> = {
        var s: Set<Character> = []
        for c in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" { s.insert(c) }
        for c in "-._~:/?#[]@!$&'()*+,;=%" { s.insert(c) }
        return s
    }()

    /// Scan `str` for http/https URLs and add `.link` attributes in-place.
    /// Soft-wrapped URLs (terminal broke the URL across multiple grid rows)
    /// are reconstructed into one logical string before detection, then the
    /// `.link` attribute is mapped back to each wrapped segment so the whole
    /// URL becomes a single tappable span visually.
    ///
    /// Everything works in NSString UTF-16 unit space since both the input
    /// `str.string`, `NSDataDetector` match ranges, and `NSMutableString`
    /// share that coordinate system. No character / grapheme slicing.
    private func addURLLinks(_ str: NSMutableAttributedString) {
        guard let detector = Self.urlDetector, str.length > 0 else { return }
        let plain = str.string as NSString
        let count = plain.length

        // Build a compacted NSString that drops newlines flanked by
        // URL-body chars (soft-wrap signature), plus a parallel index map
        // so compact[i] corresponds to original offset indexMap[i].
        let compact = NSMutableString(capacity: count)
        var indexMap: [Int] = []
        indexMap.reserveCapacity(count)

        var i = 0
        while i < count {
            let ch = plain.character(at: i)
            if ch == 0x0A /* '\n' */, i > 0, i + 1 < count {
                let prevU16 = plain.character(at: i - 1)
                let nextU16 = plain.character(at: i + 1)
                // URL body chars are all ASCII (≤0x7E).
                if prevU16 < 0x80, nextU16 < 0x80,
                   let prevScalar = Unicode.Scalar(prevU16),
                   let nextScalar = Unicode.Scalar(nextU16),
                   Self.urlBodyChars.contains(Character(prevScalar)),
                   Self.urlBodyChars.contains(Character(nextScalar)) {
                    // Soft-wrap: skip the newline in the compacted stream.
                    i += 1
                    continue
                }
            }
            compact.append(plain.substring(with: NSRange(location: i, length: 1)))
            indexMap.append(i)
            i += 1
        }

        let compactRange = NSRange(location: 0, length: compact.length)
        detector.enumerateMatches(in: compact as String, options: [], range: compactRange) { match, _, _ in
            guard let match, let url = match.url,
                  let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https" else { return }
            let cStart = match.range.location
            let cEnd = cStart + match.range.length
            guard cStart < indexMap.count, cEnd <= indexMap.count else { return }
            let origStart = indexMap[cStart]
            let origEnd = cEnd == indexMap.count ? count : indexMap[cEnd]

            // Apply `.link` to the contiguous original range, skipping any
            // soft-wrap newlines that sit inside it (attaching `.link` to
            // the newline itself is harmless, but skipping keeps the
            // attribute runs tidy).
            var runStart = origStart
            var p = origStart
            while p < origEnd {
                if plain.character(at: p) == 0x0A {
                    if p > runStart {
                        str.addAttribute(.link, value: url,
                                         range: NSRange(location: runStart, length: p - runStart))
                    }
                    runStart = p + 1
                }
                p += 1
            }
            if origEnd > runStart {
                str.addAttribute(.link, value: url,
                                 range: NSRange(location: runStart, length: origEnd - runStart))
            }
        }
    }

    /// Build an NSAttributedString for an arbitrary sequence of buffer lines.
    /// `trailingNewline` controls whether the last line ends with "\n" — set
    /// true when this segment will be followed by more content, false at the
    /// very end of the output so the grid doesn't render an empty line.
    private func buildRange(lines: [[TerminalCell]], trailingNewline: Bool) -> NSAttributedString {
        let result = NSMutableAttributedString()

        // Paragraph style for exact line height matching cell metrics.
        // We use lineHeightMultiple to force the layout manager to use our
        // exact cell height regardless of font metrics.
        let metrics = TerminalCellMetrics.shared
        let paraStyle = NSMutableParagraphStyle()
        let fontNaturalHeight = regularFont.lineHeight
        paraStyle.lineHeightMultiple = metrics.cellHeight / fontNaturalHeight
        paraStyle.lineSpacing = 0
        paraStyle.paragraphSpacing = 0
        paraStyle.paragraphSpacingBefore = 0

        for (i, line) in lines.enumerated() {
            for cell in line {
                if cell.isWideTrailer { continue }

                let isBold = cell.attributes.contains(.bold)
                let isItalic = cell.attributes.contains(.italic)
                let isInverse = cell.attributes.contains(.inverse)

                // Resolve colors
                var fg = TerminalPalette.resolve(cell.foreground, isForeground: true, bold: isBold)
                var bg = TerminalPalette.resolve(cell.background, isForeground: false)
                if isInverse { swap(&fg, &bg) }
                if cell.attributes.contains(.hidden) { fg = bg }
                if cell.attributes.contains(.dim) { fg = fg.withAlphaComponent(0.5) }

                // Pick font
                let font: UIFont
                if isBold && isItalic {
                    font = boldItalicFont
                } else if isBold {
                    font = boldFont
                } else if isItalic {
                    font = italicFont
                } else {
                    font = regularFont
                }

                var attrs: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: fg,
                    .paragraphStyle: paraStyle
                ]
                // Only set explicit background for non-default colors.
                // Omitting it for the default black lets the UITextView's
                // selection highlight show through — .backgroundColor on
                // the attributed string paints over the system highlight.
                if bg != TerminalPalette.defaultBackground {
                    attrs[.backgroundColor] = bg
                }

                if cell.attributes.contains(.underline) {
                    attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue
                    attrs[.underlineColor] = fg
                }
                if cell.attributes.contains(.strikethrough) {
                    attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
                    attrs[.strikethroughColor] = fg
                }

                // Snap glyph advance to the grid so cursor and text stay aligned.
                // kern = (expectedCellWidth - actualGlyphAdvance)
                let expectedAdvance = CGFloat(cell.width) * metrics.cellWidth
                let actualAdvance = glyphAdvance(for: cell.character, glyph: cell.glyph, font: font)
                let kern = expectedAdvance - actualAdvance
                if abs(kern) > 0.01 {
                    attrs[.kern] = kern
                }

                result.append(NSAttributedString(
                    string: String(cell.character),
                    attributes: attrs
                ))
            }

            let isLast = i == lines.count - 1
            if !isLast || trailingNewline {
                result.append(NSAttributedString(string: "\n", attributes: [
                    .font: regularFont,
                    .foregroundColor: UIColor.clear,
                    .backgroundColor: TerminalPalette.defaultBackground,
                    .paragraphStyle: paraStyle
                ]))
            }
        }

        addURLLinks(result)
        return result
    }

    /// Measure the actual rendering advance of a character in the given font.
    /// Uses CoreText to get the true glyph advance (including font substitution
    /// for emoji, which uses Apple Color Emoji instead of Menlo).
    private func glyphAdvance(for char: Character, glyph: TerminalCell.Glyph,
                              font: UIFont) -> CGFloat {
        let key = GlyphAdvanceKey(glyph: glyph, font: font)
        if let cached = glyphAdvanceCache[key] {
            return cached
        }

        let str = String(char)
        let attrStr = NSAttributedString(string: str, attributes: [.font: font])
        let line = CTLineCreateWithAttributedString(attrStr)
        let width = CTLineGetTypographicBounds(line, nil, nil, nil)
        let advance = CGFloat(width)

        // [T-terminal-glyph-cache-hash] Bound the cache. It was unbounded, which
        // was survivable while keys normalized (a terminal shows a limited
        // alphabet), but the scalar-array key treats two normalization-equal
        // characters as distinct, so a stream of exotic input can add more rows
        // than before. Dropping everything past the cap keeps the memoization
        // for the common alphabet and refills within one redraw; the measure
        // below is a CoreText call, not something worth risking growth for.
        if glyphAdvanceCache.count >= Self.glyphAdvanceCacheMax {
            glyphAdvanceCache.removeAll(keepingCapacity: true)
        }
        glyphAdvanceCache[key] = advance
        return advance
    }

    /// Enough for every character a terminal realistically repeats, across the
    /// four fonts, with room to spare.
    private static let glyphAdvanceCacheMax = 4096
}

// MARK: - UITextViewDelegate (URL tap interception)

extension TerminalScrollContainerView: UITextViewDelegate {
    func textView(_ textView: UITextView,
                  shouldInteractWith url: URL,
                  in characterRange: NSRange,
                  interaction: UITextItemInteraction) -> Bool {
        guard let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else { return true }
        MinisOpenURLBroker.shared.offer(url)
        return false
    }
}

// MARK: - Cursor Overlay View

final class TerminalCursorOverlayView: UIView {
    var cursorRect: CGRect = .zero
    var cursorShape: CursorShape = .block

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        guard cursorRect.intersects(rect) else { return }

        switch cursorShape {
        case .block:
            ctx.setFillColor(UIColor.white.withAlphaComponent(0.5).cgColor)
            ctx.fill(cursorRect)
        case .underline:
            let h: CGFloat = 2
            let underlineRect = CGRect(
                x: cursorRect.minX,
                y: cursorRect.maxY - h,
                width: cursorRect.width,
                height: h
            )
            ctx.setFillColor(UIColor.white.cgColor)
            ctx.fill(underlineRect)
        case .bar:
            let barRect = CGRect(
                x: cursorRect.minX,
                y: cursorRect.minY,
                width: 2,
                height: cursorRect.height
            )
            ctx.setFillColor(UIColor.white.cgColor)
            ctx.fill(barRect)
        }
    }
}

// MARK: - Scroll Coordinator

final class TerminalScrollCoordinator: NSObject, UIScrollViewDelegate {
    private let emulator: TerminalEmulator
    var onResize: ((Int, Int) -> Void)?
    var onPaste: ((Data) -> Void)?
    weak var containerView: TerminalScrollContainerView?

    init(emulator: TerminalEmulator, onResize: ((Int, Int) -> Void)?, onPaste: ((Data) -> Void)?) {
        self.emulator = emulator
        self.onResize = onResize
        self.onPaste = onPaste
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let metrics = TerminalCellMetrics.shared
        let maxOffsetY = scrollView.contentSize.height - scrollView.bounds.height
        if maxOffsetY <= 0 {
            emulator.scrollOffset = 0
            return
        }
        let distanceFromBottom = maxOffsetY - scrollView.contentOffset.y
        let lineOffset = max(0, Int(distanceFromBottom / metrics.cellHeight))
        emulator.scrollOffset = lineOffset

        containerView?.updateCursorOverlay()
    }
}
