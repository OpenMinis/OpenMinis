import UIKit
import os

/// A custom UICollectionViewLayout for the chat message list that maintains
/// cached cell heights across layout invalidation cycles.
///
/// Unlike `UICollectionViewCompositionalLayout`, this layout **never discards
/// cached heights for off-screen cells** during invalidation. This eliminates
/// the contentSize collapse-rebuild cycle that causes scroll jitter and
/// scrollbar flickering during streaming updates.
///
/// Cells self-size via `preferredLayoutAttributesFitting` on first appearance.
/// Once a cell's height is known, it's cached and reused until explicitly
/// invalidated (e.g., the streaming cell changes height).
final class MessageListLayout: UICollectionViewLayout {
    /// Default estimated height for cells that haven't been measured yet.
    var estimatedItemHeight: CGFloat = 200

    /// Per-item estimated heights based on content analysis (text length, role, etc.).
    /// These are cheaper than full UIHostingConfiguration rendering but much more
    /// accurate than the single `estimatedItemHeight` constant, reducing the
    /// contentSize error that causes visible blank gaps during fast scrolling.
    private var estimatedHeights: [Int: CGFloat] = [:]

    /// Pre-calculated heights from NSAttributedString.boundingRect() for completed
    /// text blocks. More accurate than estimatedHeights (within ~8px) but cheaper
    /// than full UIHostingConfiguration rendering. Set during snapshot application
    /// for blocks that have a cachedAttributedString.
    private var precalcHeights: [Int: CGFloat] = [:]

    func setEstimatedHeight(_ height: CGFloat, at index: Int) {
        // Don't overwrite if we already have a real measured height.
        guard heightCache[index] == nil else { return }
        estimatedHeights[index] = height
    }

    func setPrecalcHeight(_ height: CGFloat, at index: Int) {
        // Don't overwrite if we already have a real measured height.
        guard heightCache[index] == nil else { return }
        precalcHeights[index] = height
    }

    /// When true, ALL self-sizing invalidation is deferred (heights stored in
    /// `deferredHeights`). Set by the Coordinator when `scrollMode == .userBrowsing`
    /// to cover the entire browse period — not just the moments where UIKit reports
    /// `isTracking` / `isDecelerating`, which have brief gaps (e.g. between finger
    /// lift and deceleration start) that let height changes slip through.
    var deferSelfSizing: Bool = false

    /// When true, `invalidationContext` will NOT set `contentOffsetAdjustment`.
    /// Used during capture-restore layout flushes where the caller handles
    /// offset preservation manually.
    var suppressContentOffsetAdjustment: Bool = false

    /// Index of the actively streaming cell (set by Coordinator).
    /// Used to skip contentOffsetAdjustment for this cell — its growth
    /// at the bottom edge should not push the user's scroll position.
    /// Item index spans of EVERY assistant message actively receiving content.
    /// [T-stream-hover-earlier-streaming] Historically a single `Int?` for the
    /// LAST assistant message with `index >= si` semantics. But with
    /// queued-turn interrupts more than one message streams at once (an
    /// earlier turn's tool blocks keep flushing output while the newest turn
    /// streams text). Cells of an EARLIER streaming message then counted as
    /// "not streaming" in `isStreamingCell`, so each content flush took the
    /// visible-cell contentOffsetAdjustment below — dragging a hovering
    /// reader's viewport down by the growth delta on every flush ("can't
    /// hover on the earlier streaming message"). Ranges are sorted ascending;
    /// the LAST range keeps the historical open-ended tail semantics.
    ///
    /// [T-streamingcellranges-async-render-race] THREAD SAFETY: this array is
    /// written on the main thread (applySnapshot) but read from `prepare()` /
    /// `invalidationContext(forPreferredLayoutAttributes:)`, which UIKit +
    /// SwiftUI can invoke on a SECONDARY async-render/layout thread (the
    /// crashing Thread 15 in crash-20260722-100621: SwiftUICore render →
    /// CoreFoundation runloop → layout). A Swift `Array` is a value type but
    /// its heap backing buffer is NOT safe for concurrent read+write: a
    /// full-array replacement on main while a background layout iterates it
    /// (`.contains`, `index >=`) races the buffer's release, dereferencing
    /// freed memory → SIGSEGV, and the retain/release churn shows up as the
    /// main-thread AttributeGraph/objc_destroyWeak hangs in crash-...-100532.
    /// The old `Int?` was immune (trivial value; a torn read is at worst a
    /// stale Int). Guard every access with an unfair lock so reads copy a
    /// consistent snapshot and the write is atomic w.r.t. those reads.
    private var _streamingCellRanges: [Range<Int>] = []
    private var _streamingLock = os_unfair_lock_s()

    var streamingCellRanges: [Range<Int>] {
        get {
            os_unfair_lock_lock(&_streamingLock)
            defer { os_unfair_lock_unlock(&_streamingLock) }
            return _streamingCellRanges
        }
        set {
            os_unfair_lock_lock(&_streamingLock)
            _streamingCellRanges = newValue
            os_unfair_lock_unlock(&_streamingLock)
        }
    }

    /// Compat signal for "is anything streaming right now" (grow-animation
    /// gate in MessageListInfrastructure). Reads as the tail streaming
    /// message's first item, matching the old single-index meaning.
    var streamingCellIndex: Int? {
        os_unfair_lock_lock(&_streamingLock)
        defer { os_unfair_lock_unlock(&_streamingLock) }
        return _streamingCellRanges.last?.lowerBound
    }

    /// True while the list is auto-scroll-pinned to the bottom (scrollMode ==
    /// .autoScrolling), set by the Coordinator. When pinned, a streaming cell's
    /// growth SHOULD adjust the content offset by the growth delta — that keeps
    /// the bottom edge of the content glued to the viewport bottom IN THE SAME
    /// layout pass, so newly streamed lines rise smoothly from the bottom
    /// instead of the content snapping taller and an after-the-fact scroll
    /// chasing it ("grow-then-catch-up" jitter). In browse mode this stays
    /// false and streaming growth must NOT push the user's position.
    /// [T-ios-stream-natural-transitions]
    var isAutoScrollPinning: Bool = false

    /// Vertical spacing between cells.
    var itemSpacing: CGFloat = 0

    /// Cached heights keyed by item index. Persists across invalidation cycles.
    private var heightCache: [Int: CGFloat] = [:]

    /// Indices where GeometryReader has reported a precise height.
    /// When set, UIKit self-sizing (`preferredLayoutAttributesFitting`)
    /// is completely ignored for that index — GeometryReader is the
    /// single source of truth.  This breaks the oscillation loop where
    /// GeometryReader and UIKit disagree (e.g. empty footer: GR=4pt,
    /// UIKit=157pt due to invisible .sheet/.contextMenu modifiers).
    private var geometryReaderConfirmed: Set<Int> = []

    /// [T-ios-scroll-decel-height-drift] Real measured heights keyed by a
    /// stable content key (messageId+blockId), NOT by item index. The
    /// index-keyed `heightCache` is the live source of truth, but it is keyed
    /// by position: when the Coordinator rebuilds estimates for a fresh
    /// snapshot (or a cell that was never on-screen scrolls into view) the
    /// per-index entry can be absent, so the cell lands at its rough
    /// `estimatedHeights` value and then jumps to the real height on first
    /// self-size — the decel "settle jitter" we instrumented. This map
    /// remembers the last real height UIKit measured for a given content key
    /// (paired with the width it was measured at) so the Coordinator can seed
    /// `precalcHeight` with a near-exact value on re-entry. NO measurement is
    /// performed here — we only cache the height UIKit already computed in
    /// `invalidationContext`, so this cannot reintroduce the historical
    /// sizeThatFits→fillLayoutHole typesetter feedback loop (01939f73 /
    /// 34143a73). Cleared with the index caches on session change.
    private var measuredHeightByContentKey: [String: (height: CGFloat, width: CGFloat)] = [:]

    /// Index → content key for the current snapshot, set by the Coordinator
    /// during snapshot application. Lets `invalidationContext` (which only
    /// knows the item index) write the measured height back under the stable
    /// content key.
    private var contentKeyByIndex: [Int: String] = [:]

    /// Computed layout attributes for all items.
    private var itemAttributes: [UICollectionViewLayoutAttributes] = []

    /// Total content height (sum of all item heights).
    private var totalHeight: CGFloat = 0

    /// Number of items at last prepare() call — used to detect insertions.
    private var lastItemCount: Int = 0

    // MARK: - Core Layout

    override var collectionViewContentSize: CGSize {
        let width = collectionView?.bounds.width ?? 0
        return CGSize(width: width, height: totalHeight)
    }

    override func prepare() {
        let t0 = CACurrentMediaTime()
        super.prepare()
        guard let cv = collectionView else { return }

        let sectionCount = cv.numberOfSections
        let itemCount = sectionCount > 0 ? cv.numberOfItems(inSection: 0) : 0
        let width = cv.bounds.width
        guard width > 0 else {
            AppLogger(category: "ScrollDiag").info("[ScrollDiag][prepare] SKIP width=0 bounds=\(cv.bounds) itemCount=\(itemCount)")
            return
        }

        itemAttributes.removeAll(keepingCapacity: true)
        itemAttributes.reserveCapacity(itemCount)

        var y: CGFloat = 0
        for i in 0..<itemCount {
            let ip = IndexPath(item: i, section: 0)
            let attrs = UICollectionViewLayoutAttributes(forCellWith: ip)
            let h: CGFloat
            if let cached = heightCache[i] {
                h = cached
            } else if let precalc = precalcHeights[i] {
                h = precalc
            } else if let est = estimatedHeights[i] {
                h = est
            } else {
                h = estimatedItemHeight
            }
            attrs.frame = CGRect(x: 0, y: y, width: width, height: h)
            itemAttributes.append(attrs)
            y += h
            // [T-ios-footer-hug] Suppress the inter-cell spacing when the NEXT
            // cell is this message's assistantFooter (content key prefix "f:").
            // The footer (meta-info row) should hug the last content block
            // rather than float 8pt below it. Only the block→footer gap is
            // zeroed — every other inter-cell gap (incl. tool-block capsules)
            // keeps its full itemSpacing, so this cannot regress tool spacing.
            // CONTENT-AWARE POLICY (user report 2026-07-21): hug ONLY when the
            // footer shows the quiet meta/usage row (or nothing). Prominent
            // footer content — typing indicator, error banner, resume banner —
            // keeps the full itemSpacing (footerHugByIndex, set by the
            // Coordinator which can see message/vm state). The isStreamingCell
            // check is the timing-robust belt-and-suspenders for the typing
            // indicator: streamingCellRanges updates continuously mid-stream,
            // while the hug flag only refreshes on snapshot applies.
            let isLast = (i + 1 == itemCount)
            let nextIsFooter = !isLast
                && (contentKeyByIndex[i + 1]?.hasPrefix("f:") ?? false)
                && (footerHugByIndex[i + 1] ?? true)
                && !isStreamingCell(i + 1)
            // Add trailing spacing unless (a) this is the last cell (no cell
            // follows), (b) the next cell is this message's footer, or (c) this
            // cell is zero-height (an empty text block renders nothing — its
            // trailing gap would double the spacing between its neighbors).
            // [T-ios-empty-textblock-gap]
            if !isLast, !nextIsFooter, h > 0 { y += itemSpacing }
        }
        totalHeight = y
        lastItemCount = itemCount
        // [ScrollStall] prepare() runs on every invalidateLayout; on long
        // sessions iterating the full itemCount can show up as scroll hitches.
        let elapsed = (CACurrentMediaTime() - t0) * 1000
        Self.prepareCallCount &+= 1
        #if DEBUG
        // [T-ios-scroll-metrics-ring] Record every full re-flow.
        ScrollMetricsRecorder.shared.record(
            kind: "prepare", a: Double(itemCount), b: totalHeight,
            offset: Double(collectionView?.contentOffset.y ?? 0),
            note: String(format: "%.1fms", elapsed))
        #endif
        if elapsed >= 5 {
            AppLogger(category: "ScrollStall").debug("[prepare] #\(Self.prepareCallCount) \(String(format: "%.1f", elapsed))ms items=\(itemCount) totalH=\(String(format: "%.0f", totalHeight)) cacheHits=\(self.heightCache.count) precalc=\(self.precalcHeights.count) est=\(self.estimatedHeights.count)")
        }
    }
    /// Counter for prepare() calls — useful to see invalidate frequency
    /// during scroll. Process-global because prepare() runs across multiple
    /// MessageListLayout instances on session switches; we just want a
    /// monotonic id in logs to count fires per second.
    private static var prepareCallCount: Int = 0

    override func layoutAttributesForElements(in rect: CGRect) -> [UICollectionViewLayoutAttributes]? {
        // Return cells in the visible rect plus half-a-screen above and below.
        // A full screen caused too many UIHostingConfiguration cells to be created
        // simultaneously during fast scrolling, each triggering self-sizing that
        // invalidates layout — halving the margin significantly reduces hitch rate.
        let t0 = CACurrentMediaTime()
        guard !itemAttributes.isEmpty else { return nil }

        let prefetchMargin = rect.height * 0.5
        let expandedRect = CGRect(
            x: rect.minX,
            y: rect.minY - prefetchMargin,
            width: rect.width,
            height: rect.height + prefetchMargin * 2
        )

        var result: [UICollectionViewLayoutAttributes] = []
        for attrs in itemAttributes {
            if attrs.frame.maxY < expandedRect.minY { continue }
            if attrs.frame.minY > expandedRect.maxY { break }
            result.append(attrs)
        }

        // [ScrollStall] Sampled rate-limited log. Every 1s we emit one summary
        // line giving fire count + slowest one. Helps see whether this fn's
        // O(itemCount) walk is the hitch source on 100+ item sessions.
        Self.lafeCallCount &+= 1
        let elapsed = (CACurrentMediaTime() - t0) * 1000
        if elapsed > Self.lafeSlowest { Self.lafeSlowest = elapsed }
        let now = CACurrentMediaTime()
        if now - Self.lafeLastFlush > 1.0 {
            AppLogger(category: "ScrollStall").debug("[LAFE] last1s fires=\(Self.lafeCallCount - Self.lafeBaseCount) slowest=\(String(format: "%.2f", Self.lafeSlowest))ms items=\(self.itemAttributes.count) returned=\(result.count)")
            Self.lafeBaseCount = Self.lafeCallCount
            Self.lafeSlowest = 0
            Self.lafeLastFlush = now
        }

        return result
    }
    private static var lafeCallCount = 0
    private static var lafeBaseCount = 0
    private static var lafeSlowest: Double = 0
    private static var lafeLastFlush: CFTimeInterval = 0

    override func layoutAttributesForItem(at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
        guard indexPath.item < itemAttributes.count else { return nil }
        return itemAttributes[indexPath.item]
    }

    // MARK: - Self-Sizing Support

    /// Heights measured by preferredLayoutAttributesFitting while the user
    /// was scrolling.  Stored here (not in heightCache) so prepare() keeps
    /// using the old height → contentSize stays frozen.  Applied on thaw
    /// via `applyDeferredHeights()`.
    private var deferredHeights: [Int: CGFloat] = [:]

    /// Number of pending deferred heights (for diagnostics).
    var deferredHeightCount: Int { deferredHeights.count }

    /// Move deferred heights into the real cache.  Call after scrolling ends.
    /// Only writes if the cache doesn't already have a value (GeometryReader
    /// may have reported a more precise height while we were deferring).
    func applyDeferredHeights() {
        guard !deferredHeights.isEmpty else { return }
        // [SettleJitter] Evidence log: classify every deferred correction by
        // where it sits relative to the viewport and how big the delta is.
        // Corrections ABOVE the viewport are the H1 suspect — after the settle
        // re-flow the offset is restored numerically, so any above-viewport
        // height change shifts the visible content by the summed delta.
        let viewportTop = collectionView?.contentOffset.y ?? 0
        let viewportBottom = viewportTop + (collectionView?.bounds.height ?? 0)
        var aboveSum: CGFloat = 0, insideSum: CGFloat = 0, dropped = 0
        var detail: [String] = []
        for (idx, h) in deferredHeights {
            let old = heightCache[idx] ?? precalcHeights[idx] ?? estimatedHeights[idx] ?? estimatedItemHeight
            // [T-ios-deferred-shrink-dropped] Apply the deferred height even when
            // a cached one already exists.
            //
            // The two ends of this mechanism used to contradict each other.
            // `shouldInvalidateLayout` only DEFERS a height when
            // `heightCache[index] != nil` (a cell with no cache is allowed
            // straight through), yet this loop only APPLIED one when
            // `heightCache[idx] == nil`. Those conditions are mutually
            // exclusive, so every deferred entry was counted as `dropped` and
            // discarded — a cell that shrank while the user was browsing kept
            // its taller frame until something else happened to invalidate it.
            //
            // Applying on thaw is the safe moment by construction: this runs
            // after scrolling ends, which is precisely the point the deferral
            // was waiting for. The GeometryReader concern the old guard cites is
            // moot — `setCachedHeight` (its only writer) has no callers, so
            // `geometryReaderConfirmed` is always empty.
            if heightCache[idx] == nil || abs((heightCache[idx] ?? 0) - h) > 0.5 {
                heightCache[idx] = h
                let delta = h - old
                let frame = idx < itemAttributes.count ? itemAttributes[idx].frame : .zero
                let pos: String
                if frame.maxY <= viewportTop { pos = "above"; aboveSum += delta }
                else if frame.minY < viewportBottom { pos = "inside"; insideSum += delta }
                else { pos = "below" }
                if abs(delta) > 0.5 {
                    detail.append("idx\(idx) \(pos) \(String(format: "%+.1f", delta)) (\(String(format: "%.0f", old))→\(String(format: "%.0f", h)))")
                }
                #if DEBUG
                // [T-ios-scroll-metrics-ring]
                ScrollMetricsRecorder.shared.record(
                    kind: "flush", idx: idx, a: old, b: h,
                    key: String(contentKeyByIndex[idx]?.prefix(2) ?? ""),
                    pos: pos, offset: viewportTop,
                    tag: metricsTagByIndex[idx] ?? "")
                #endif
            } else {
                dropped += 1
            }
        }
        if !detail.isEmpty || dropped > 0 {
            AppLogger(category: "SettleJitter").info("[SettleJitter][flush] n=\(deferredHeights.count) dropped=\(dropped) aboveΣ=\(String(format: "%+.1f", aboveSum)) insideΣ=\(String(format: "%+.1f", insideSum)) :: \(detail.prefix(12).joined(separator: " | "))")
        }
        deferredHeights.removeAll()
    }

    override func shouldInvalidateLayout(
        forPreferredLayoutAttributes preferredAttributes: UICollectionViewLayoutAttributes,
        withOriginalAttributes originalAttributes: UICollectionViewLayoutAttributes
    ) -> Bool {
        let index = preferredAttributes.indexPath.item
        let preferred = preferredAttributes.size.height
        let original = originalAttributes.size.height

        // While the user is browsing (scrollMode == .userBrowsing), defer
        // self-sizing for cells that already have a cached height — prevents
        // content jumps from height changes in off-screen cells.
        // BUT allow first-time measurement (no cached height) so newly
        // scrolled-into-view cells render at correct size immediately
        // instead of being truncated to the estimated height.
        if deferSelfSizing {
            let hasCached = heightCache[index] != nil
            if hasCached {
                // Cell is growing (streaming text) — allow it through so the
                // content doesn't overflow the frozen cell frame and overlap
                // adjacent cells. Only defer shrinking or stable cells.
                if preferred <= original + 0.5 {
                    // Shrinking or stable — defer the update.
                    if abs(preferred - original) > 0.5 {
                        deferredHeights[index] = preferred
                    }
                    // [T-video-squish-evidence] Only worth a line when a REAL
                    // correction is being parked (large deltas are the media
                    // placeholder→loaded case). Token-by-token streaming
                    // produces sub-30pt shrinks constantly; those stay quiet.
                    if abs(preferred - original) > 30 {
                        AppLogger(category: "CellSizing").info("[CellSizing][DEFER-PARKED] idx=\(index) pref=\(String(format: "%.0f", preferred)) orig=\(String(format: "%.0f", original)) — deferSelfSizing, not growing, correction parked")
                    }
                    return false
                }
                // Growing while deferred + has cache — allow through
            } else {
                // First measurement (no cached height) — allow it through
                // so the cell isn't truncated when scrolled into view.
            }
        }

        // If GeometryReader has confirmed this cell's height, completely
        // ignore UIKit self-sizing.  GR is the source of truth — UIKit's
        // systemLayoutSizeFitting often disagrees (e.g. invisible .sheet/
        // .contextMenu modifiers inflate the measured height), causing an
        // infinite oscillation loop.
        if geometryReaderConfirmed.contains(index) {
            // [T-video-squish-evidence] This is the exit that can silently
            // discard a media placeholder→loaded growth: GR confirmed the
            // 200pt height BEFORE the thumbnail landed, and now the real
            // 437pt measurement is ignored as "GR already knows". Log it
            // only for large deltas so streaming stays quiet.
            if abs(preferred - original) > 30 {
                AppLogger(category: "CellSizing").info("[CellSizing][GR-IGNORED] idx=\(index) pref=\(String(format: "%.0f", preferred)) orig=\(String(format: "%.0f", original)) — geometryReaderConfirmed, self-sizing discarded")
            }
            return false
        }

        let delta = abs(preferred - original)

        let shouldInvalidate: Bool
        if heightCache[index] != nil {
            shouldInvalidate = delta > 2
        } else {
            shouldInvalidate = delta > 0.5
        }
        // Streaming produces deltas in the 20–100pt range every token, so the
        // old "> 20" gate fired ~once per token. Restrict to genuinely large
        // height jumps (image attachment placeholder→loaded, etc.).
        if shouldInvalidate && delta > 100 {
            AppLogger(category: "CellSizing").info("[CellSizing] INVALIDATE idx=\(index) delta=\(String(format: "%.0f", delta)) est=\(String(format: "%.0f", original)) → pref=\(String(format: "%.0f", preferred)) cached=\(heightCache[index] != nil)")
        }
        // [ScrollStall] Per-second invalidation summary keyed by idx —
        // identifies cells that flap (re-invalidate repeatedly during scroll).
        if shouldInvalidate {
            Self.invIdxCounts[index, default: 0] += 1
            let now = CACurrentMediaTime()
            if now - Self.invLastFlush > 1.0 {
                let total = Self.invIdxCounts.values.reduce(0, +)
                let top = Self.invIdxCounts.sorted { $0.value > $1.value }.prefix(5)
                let topStr = top.map { "idx\($0.key)×\($0.value)" }.joined(separator: " ")
                if total > 0 {
                    AppLogger(category: "ScrollStall").debug("[INV] last1s=\(total) cells top: \(topStr)")
                }
                Self.invIdxCounts.removeAll(keepingCapacity: true)
                Self.invLastFlush = now
            }
        }
        return shouldInvalidate
    }
    private static var invIdxCounts: [Int: Int] = [:]
    private static var invLastFlush: CFTimeInterval = 0

    override func invalidationContext(
        forPreferredLayoutAttributes preferredAttributes: UICollectionViewLayoutAttributes,
        withOriginalAttributes originalAttributes: UICollectionViewLayoutAttributes
    ) -> UICollectionViewLayoutInvalidationContext {
        let ctx = super.invalidationContext(
            forPreferredLayoutAttributes: preferredAttributes,
            withOriginalAttributes: originalAttributes
        )

        let index = preferredAttributes.indexPath.item
        let oldHeight = originalAttributes.size.height
        let newHeight = preferredAttributes.size.height
        let delta = newHeight - oldHeight

        // [SettleJitter] Capture the estimate source BEFORE the heightCache
        // write below overwrites it — attributes which estimator produced the
        // wrong height (pre = precalc/memo seed, est = coarse heuristic).
        let estimateSrc = heightCache[index] != nil ? "cache"
            : (precalcHeights[index] != nil ? "pre"
            : (estimatedHeights[index] != nil ? "est" : "def"))

        // Cache the new height. prepare() will use this to compute correct totalHeight.
        heightCache[index] = newHeight

        // [SettleJitter] Evidence log (H2): a correction passing through DURING
        // deceleration gets NO contentOffsetAdjustment (the !isDecelerating
        // guard below skips compensation to protect momentum), so an
        // above/inside-viewport height change shifts visible content mid-glide.
        if let cv = collectionView {
            let cellTop = originalAttributes.frame.minY
            let viewportTop = cv.contentOffset.y
            let pos = originalAttributes.frame.maxY <= viewportTop ? "above"
                : (cellTop < viewportTop + cv.bounds.height ? "inside" : "below")
            #if DEBUG
            let phase = cv.isDecelerating ? "decel" : (cv.isTracking ? "drag" : "idle")
            // [T-ios-scroll-metrics-ring] Record EVERY correction (no threshold)
            // so the full delta distribution — including negatives and sub-2pt
            // values the log gate hides — is retrievable via debug.scrollMetrics.
            ScrollMetricsRecorder.shared.record(
                kind: "inv", idx: index, a: oldHeight, b: newHeight,
                key: contentKeyByIndex[index] ?? "",
                src: estimateSrc, pos: pos, offset: viewportTop, note: phase,
                tag: metricsTagByIndex[index] ?? "")
            #endif
            if cv.isDecelerating, abs(delta) > 2 {
                AppLogger(category: "SettleJitter").info("[SettleJitter][decel-inv] idx=\(index) \(pos) Δ\(String(format: "%+.1f", delta)) (\(String(format: "%.0f", oldHeight))→\(String(format: "%.0f", newHeight))) key=\(contentKeyByIndex[index]?.prefix(2) ?? "??") src=\(estimateSrc) offset=\(String(format: "%.0f", viewportTop))")
            }
        }

        // NOTE: the content-keyed height memo (for scroll-reentry seeding) is
        // written from the cell's own self-size path
        // (SelfSizingCell.preferredLayoutAttributesFitting → recordMeasuredHeight),
        // NOT here. invalidationContext doesn't fire for stable cells during
        // browse/decel and has no streaming-exclusion context, so writing the
        // memo here could capture a mid-stream height. See [T-ios-scroll-decel-height-drift].

        // If the resized cell is above the current viewport, shift the offset
        // so the visible content stays in place.
        // Skip the adjustment while the user is actively scrolling (tracking
        // or decelerating) — UIKit applies contentOffsetAdjustment directly
        // inside the layout cycle, bypassing scrollViewDidScroll, which
        // disrupts the natural deceleration momentum and causes visible jitter.
        if let cv = collectionView, !cv.isTracking, !cv.isDecelerating, !suppressContentOffsetAdjustment {
            let cellTop = originalAttributes.frame.minY
            let cellBottom = originalAttributes.frame.maxY
            let viewportTop = cv.contentOffset.y
            let viewportBottom = viewportTop + cv.bounds.height
            if cellTop < viewportTop && cellBottom <= viewportTop {
                // Cell entirely above viewport — always adjust to keep
                // visible content in place.
                ctx.contentOffsetAdjustment = CGPoint(x: 0, y: delta)
            } else if cellTop >= viewportTop && cellTop < viewportBottom && !isStreamingCell(index) {
                // Cell is visible and NOT the actively streaming cell —
                // adjust offset so content below doesn't jump (e.g. image
                // finished loading and changed the cell's height).
                ctx.contentOffsetAdjustment = CGPoint(x: 0, y: delta)
            } else if isStreamingCell(index) && isAutoScrollPinning && delta > 0 {
                // [T-ios-stream-natural-transitions] Actively streaming cell
                // growing at the bottom WHILE pinned to bottom: adjust the
                // offset by the growth delta IN THIS SAME layout pass so the
                // content's bottom edge stays glued to the viewport bottom.
                // The new lines then rise smoothly from the bottom instead of
                // the content snapping taller and the contentSize-KVO scroll
                // chasing it a frame later (the "grow-then-catch-up" jitter).
                // Browse mode keeps the historical skip (isAutoScrollPinning
                // is false), so streaming growth never yanks a reading user.
                ctx.contentOffsetAdjustment = CGPoint(x: 0, y: delta)
            }
        }

        // [T-ios-footer-float-firstlayout] First-entry footer-float fix.
        // When a cell measured TALLER than its estimate during the initial
        // snapshot layout (suppressContentOffsetAdjustment == true), UIKit
        // updates that cell but the cells BELOW it (notably the message footer)
        // can keep their pre-growth frames until an unrelated scroll forces a
        // full prepare() — the footer visibly hangs in the middle of the grown
        // block, then snaps down on first scroll.
        //
        // Fix: request one coalesced full re-layout so prepare() re-flows every
        // cell's y from the updated heightCache. Guarded to avoid an O(n²)
        // avalanche when N cells each correct during load:
        //   • only while suppressing (first snapshot settle), not steady state
        //   • only on a REAL height change (|delta| > 0.5) — both growth (footer
        //     hangs inside the grown block) and shrink (a stale-tall seed, e.g.
        //     a footer that once measured with transient content, leaves a
        //     phantom gap; frames below must be pulled up) need the re-flow
        //   • NOT for streaming cells (their position is owned by the pin logic)
        //   • coalesced: at most one invalidate scheduled per runloop tick, and
        //     re-armed only after it fires — so a burst of corrections collapses
        //     into a single prepare(), not one per cell.
        //
        // [T-ios-steadystate-reflow-gap] The `suppressContentOffsetAdjustment`
        // gate above restricted this re-flow to the FIRST snapshot settle. In
        // steady state (the user scrolling through an already-loaded session)
        // the exact same staleness is reachable, and it is worse there because
        // the frames that go stale are whole message cells rather than a
        // footer:
        //
        //   * `heightCache[index] = newHeight` is written at the top of this
        //     method, so `prepare()` WOULD lay every cell out correctly — but
        //     UIKit's default invalidation context for a preferred-attributes
        //     change invalidates ONLY this index path. `prepare()` is never
        //     asked to re-run, so every cell BELOW keeps the `frame.origin.y`
        //     it was given under the old height.
        //   * The offset adjustment above compensates the SCROLL POSITION; it
        //     does not move any other cell's frame.
        //
        // Result: cell N grows by Δ, cells N+1… stay put, and the grown cell's
        // content is drawn straight over its neighbour. That is the reported
        // artifact — two different assistant messages interleaving line by
        // line, both fully opaque, with the user bubble between them rendering
        // cleanly (it is a different cell that simply never moved).
        //
        // Field evidence this is the live path: the collapse reproduced on a
        // build carrying all three attachment-level fixes (bea0ecfcf /
        // 594800e76 / 03f5d168f), on a session whose content was already fully
        // loaded and static — no streaming, no attachment cache work left to
        // do. What remains at that point is exactly this: a self-size
        // correction during scroll with no full re-flow behind it.
        //
        // The guards that made this safe during first-settle carry over
        // unchanged — real delta, not a streaming cell (its position is owned
        // by the pin logic), and coalesced to at most one `prepare()` per
        // runloop tick so a burst of N corrections cannot go O(N²).
        if abs(delta) > 0.5, !isStreamingCell(index), !pendingFooterReflow {
            pendingFooterReflow = true
            // [T-ios-steadystate-reflow-gap] Rate evidence: a full prepare() is
            // O(items), so if this fired per correction during a fast scroll it
            // would be a regression. Counted per second to prove the coalescing
            // actually collapses bursts.
            Self.reflowCount &+= 1
            let nowR = CACurrentMediaTime()
            if nowR - Self.reflowLastFlush > 1.0 {
                if Self.reflowCount > 0 {
                    AppLogger(category: "ScrollStall").info("[ReflowGap] last1s coalesced re-flows=\(Self.reflowCount) firstSettle=\(self.suppressContentOffsetAdjustment)")
                }
                Self.reflowCount = 0
                Self.reflowLastFlush = nowR
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.pendingFooterReflow = false
                self.invalidateLayout()
            }
        }

        return ctx
    }

    /// [T-ios-footer-float-firstlayout] Coalescing flag so a burst of first-
    /// layout height corrections triggers a single prepare(), never one per cell.
    private var pendingFooterReflow = false

    /// [T-ios-steadystate-reflow-gap] Per-second counter proving the coalescing
    /// holds once the re-flow is no longer restricted to the first settle.
    private static var reflowCount = 0
    private static var reflowLastFlush: CFTimeInterval = 0

    /// [T-ios-plaf-streaming-graph-reentry] True while any streaming cell
    /// range is active. Locked snapshot (same lock as streamingCellRanges) so
    /// SelfSizingCell's PLAF guard can consult it on main while background
    /// layout threads read and the stream pipeline writes the ranges.
    var hasActiveStreaming: Bool { !streamingCellRanges.isEmpty }

    // Internal (not private): SelfSizingCell's interaction-window PLAF guard
    // consults it to exempt streaming cells from the cached-height
    // short-circuit. [T-ios-plaf-interaction-graph-reentry]
    func isStreamingCell(_ index: Int) -> Bool {
        // [T-streamingcellranges-async-render-race] Take ONE locked snapshot of
        // the ranges and evaluate against the local copy — this can run on a
        // background layout thread while applySnapshot replaces the array on
        // main, so both a torn read AND a double-read seeing two different
        // states must be avoided.
        let ranges = streamingCellRanges
        // Tail streaming message keeps the historical open-ended semantics
        // (`index >= start`): its footer and any late-appended cells count as
        // streaming so growing tail content never takes the offset adjustment.
        if let last = ranges.last, index >= last.lowerBound { return true }
        // Earlier streaming messages (queued-interrupt turns still flushing
        // tool output) match by their exact item span.
        // [T-stream-hover-earlier-streaming]
        return ranges.contains { $0.contains(index) }
    }

    // MARK: - Bounds Change

    // MARK: - Width-change cache purge (debounced)

    /// The width at which the height caches are currently valid. Updated only
    /// when a width change has *settled* (see `scheduleWidthPurge`).
    private var lastSettledWidth: CGFloat = 0

    /// Monotonic token so a newer width change cancels an older pending purge
    /// without needing a cancellable handle (we just ignore stale fires).
    private var widthPurgeToken: UInt = 0

    /// ~1 display frame at 30fps. Long enough that intermediate frames of a
    /// sidebar-collapse / rotation width animation coalesce into a single
    /// purge; short enough that the post-settle re-measure is imperceptible.
    private static let widthSettleDelay: TimeInterval = 0.033

    override func shouldInvalidateLayout(forBoundsChange newBounds: CGRect) -> Bool {
        // Invalidate when width changes (rotation / sidebar collapse) but not on scroll.
        guard let cv = collectionView else { return false }
        // Skip layout invalidation when the app is backgrounded — UIKit may report
        // transient zero/different bounds during background transitions, which would
        // clear the height cache and cause a brief layout scramble on foreground return.
        if UIApplication.shared.applicationState != .active { return false }

        if newBounds.width != cv.bounds.width && newBounds.width > 0 {
            // [T-ios-ipad-rotate-sidebar-scroll-jank] (issue #31) DO NOT clear
            // the height caches on every frame here. The detail column width
            // ANIMATES during a sidebar collapse (and rotation), so this fires
            // once per animation frame; clearing all caches each time forced
            // every visible cell to re-measure through SwiftUI
            // systemLayoutSizeFitting every frame — a re-measure storm that is
            // the reported scroll jank.
            //
            // Instead: return true so the layout keeps following the animating
            // width using the EXISTING cached heights (cheap — prepare() just
            // re-lays the same heights at the new width), and DEBOUNCE the
            // actual cache purge until the width stops changing for ~1 frame.
            // The single post-settle purge + invalidateLayout() re-measures
            // once at the final width, restoring an exact contentSize — which
            // is what keeps scroll-to-bottom able to reach the true end (the
            // original correctness constraint that motivated clearing the
            // width-dependent precalc/estimated caches).
            scheduleWidthPurge(for: newBounds.width)
            return true
        }
        return false
    }

    /// Debounced width-change cache purge. Each width change cancels any
    /// pending purge (via token) and reschedules; only when the width has been
    /// stable for `widthSettleDelay` do we clear the width-dependent caches and
    /// trigger a single precise re-measure at the settled width.
    private func scheduleWidthPurge(for targetWidth: CGFloat) {
        // Already valid at this width (e.g. width animated back to where it
        // started) — nothing to purge.
        if abs(targetWidth - lastSettledWidth) < 0.5 { return }
        widthPurgeToken &+= 1
        let token = widthPurgeToken
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.widthSettleDelay) { [weak self] in
            guard let self else { return }
            // A newer width change superseded this one — let the latest one win.
            guard token == self.widthPurgeToken else { return }
            guard let cv = self.collectionView, cv.bounds.width > 0 else { return }
            // Width must have actually settled at the value we scheduled for.
            guard abs(cv.bounds.width - targetWidth) < 0.5 else { return }
            // Nothing to do if it ended up back at the validated width.
            guard abs(cv.bounds.width - self.lastSettledWidth) >= 0.5 else { return }

            // Clear ALL height caches so cells re-measure at the final width.
            // precalcHeights and estimatedHeights are width-dependent (text
            // wrapping changes at different widths), so they must be cleared
            // too — otherwise prepare() uses stale heights from the old width,
            // producing an incorrect contentSize that prevents scroll-to-bottom
            // from reaching the true end.
            self.heightCache.removeAll()
            self.precalcHeights.removeAll()
            self.estimatedHeights.removeAll()
            self.geometryReaderConfirmed.removeAll()
            self.lastSettledWidth = cv.bounds.width
            self.invalidateLayout()
        }
    }

    // MARK: - Height Cache Management

    /// Update the height cache when items are inserted, deleted, or moved.
    /// Called by the Coordinator before applying a new snapshot.
    func updateCacheForSnapshot(oldIds: [MessageListItem], newIds: [MessageListItem]) {
        // Build a mapping from old index → item.
        var oldIndexByItem: [MessageListItem: Int] = [:]
        for (i, item) in oldIds.enumerated() {
            oldIndexByItem[item] = i
        }

        // Build the new cache by looking up each new item in the old cache.
        var newCache: [Int: CGFloat] = [:]
        var newEstimated: [Int: CGFloat] = [:]
        var newPrecalc: [Int: CGFloat] = [:]
        var newConfirmed: Set<Int> = []
        for (newIndex, item) in newIds.enumerated() {
            if let oldIndex = oldIndexByItem[item] {
                if let h = heightCache[oldIndex] { newCache[newIndex] = h }
                if let h = precalcHeights[oldIndex] { newPrecalc[newIndex] = h }
                if let h = estimatedHeights[oldIndex] { newEstimated[newIndex] = h }
                if geometryReaderConfirmed.contains(oldIndex) { newConfirmed.insert(newIndex) }
            }
        }
        heightCache = newCache
        precalcHeights = newPrecalc
        estimatedHeights = newEstimated
        geometryReaderConfirmed = newConfirmed
    }

    /// Invalidate the cached height for a specific item index, forcing re-measurement.
    /// Use this for the actively streaming message whose height is changing.
    func invalidateHeight(at index: Int) {
        heightCache.removeValue(forKey: index)
        geometryReaderConfirmed.remove(index)
        // [T-ios-scroll-decel-height-drift] Belt-and-suspenders: also drop the
        // content-keyed memo for this index. The content-versioned key already
        // makes a height change miss the old entry, but an explicit invalidate
        // (thinking collapse, async image load, streaming re-measure) should not
        // leave a now-wrong memo that a later same-length edit could match.
        if let key = contentKeyByIndex[index] {
            measuredHeightByContentKey.removeValue(forKey: key)
        }
    }

    /// Directly set the cached height for a specific item index.
    /// Used when the SwiftUI GeometryReader reports a height that differs
    /// from what `preferredLayoutAttributesFitting` returned, bypassing
    /// the unreliable self-sizing cycle.
    func setCachedHeight(_ height: CGFloat, at index: Int) {
        heightCache[index] = height
        geometryReaderConfirmed.insert(index)
    }

    /// Read the cached height for a specific item index, if any.
    func cachedHeight(at index: Int) -> CGFloat? {
        heightCache[index]
    }

    /// Check if a precalculated height exists for this index.
    func precalcHeight(at index: Int) -> CGFloat? {
        precalcHeights[index]
    }

    func hasPrecalcHeight(at index: Int) -> Bool {
        precalcHeights[index] != nil
    }

    // MARK: - [T-ios-scroll-decel-height-drift] Content-keyed real-height memo

    /// Register the stable content key for an item index in the current
    /// snapshot. Called by the Coordinator while building estimates so a later
    /// `invalidationContext` can record the measured height under this key.
    func setContentKey(_ key: String, at index: Int) {
        contentKeyByIndex[index] = key
    }

    /// [T-ios-footer-hug] Per-footer spacing policy, decided by the Coordinator
    /// from the footer's CONTENT (it knows message/vm state; the layout does
    /// not): true → the footer hugs the block above (no itemSpacing) — the
    /// meta/usage row case; false → keep the full itemSpacing — prominent
    /// content (typing indicator, error banner, resume banner) reads as its own
    /// bubble and looks cramped glued to a tool capsule. Missing entry
    /// defaults to hug (the common settled case). Rebuilt on every snapshot
    /// apply, same lifecycle as contentKeyByIndex.
    private var footerHugByIndex: [Int: Bool] = [:]

    func setFooterHug(_ hug: Bool, at index: Int) {
        footerHugByIndex[index] = hug
    }

    #if DEBUG
    /// [T-ios-scroll-metrics-ring] index → stable content-hash tag, so layout-
    /// side events (inv/flush) can carry the same cell identity the cells and
    /// seed loop record. Diagnostic only.
    private var metricsTagByIndex: [Int: String] = [:]
    func setMetricsTag(_ tag: String, at index: Int) {
        metricsTagByIndex[index] = tag
    }
    #endif

    /// Return the last real measured height for this content key IF it was
    /// measured at (approximately) the given width. Width-sensitive because a
    /// height measured in portrait is wrong after rotation / sidebar resize.
    func measuredHeight(forKey key: String, width: CGFloat) -> CGFloat? {
        guard let entry = measuredHeightByContentKey[key],
              abs(entry.width - width) < 1 else { return nil }
        return entry.height
    }

    /// [T-ios-scroll-decel-height-drift] Record a real height a cell just
    /// measured, keyed by the cell's content key. Called from
    /// `SelfSizingCell.preferredLayoutAttributesFitting` on EVERY successful
    /// self-size — NOT from `invalidationContext`, because the latter only fires
    /// when `shouldInvalidateLayout` returns true, and during browse/decel that
    /// returns false for stable cells (so the height would be measured but never
    /// memoized). The cell carries its own key (robust against the idx=-1 reuse
    /// window). The width is the bounds width, matching the seed lookup basis in
    /// configureCell. No measurement happens here; we store a height the cell
    /// already computed.
    func recordMeasuredHeight(forKey key: String, height: CGFloat, boundsWidth: CGFloat) {
        guard height >= 4, boundsWidth > 1 else { return }
        // [T-ios-footer-stale-height] NEVER memoize footer heights. The footer's
        // content key is version-less ("f:<messageId>") because its content is
        // driven by transient runtime state (typing indicator, retry countdown,
        // error banner, usage row) rather than message content. A footer that
        // once measured 30–90pt tall mid-stream would be re-seeded at that stale
        // height on EVERY session re-entry, leaving a tall phantom gap between
        // the last block and the footer until a scroll forces re-measurement.
        // Footers are trivially cheap to self-size from the 4pt estimate, so
        // skipping the memo costs nothing.
        guard !key.hasPrefix("f:") else { return }
        measuredHeightByContentKey[key] = (height: height, width: boundsWidth)
    }

    /// Drop a single memo entry — used to evict an actively-streaming cell's
    /// key so a stale mid-stream height can't be seeded onto it.
    func invalidateMemo(forKey key: String) {
        measuredHeightByContentKey.removeValue(forKey: key)
    }

    /// Clear all cached heights (e.g., on session change).
    func clearHeightCache() {
        heightCache.removeAll()
        precalcHeights.removeAll()
        estimatedHeights.removeAll()
        geometryReaderConfirmed.removeAll()
        measuredHeightByContentKey.removeAll()
        contentKeyByIndex.removeAll()
        footerHugByIndex.removeAll()
    }

    // MARK: - Diagnostics

    var debugCachedHeightCount: Int { heightCache.count }
    var debugPrecalcHeightCount: Int { precalcHeights.count }
}

// MARK: - [T-ios-scroll-metrics-ring] In-memory scroll diagnostics ring buffer

#if DEBUG
/// Records every layout-relevant event during scrolling into a fixed-size
/// in-memory ring — no log I/O, no thresholds, negligible overhead — so the
/// COMPLETE change history of a scroll session can be pulled at once through
/// the debug server (`debug.scrollMetrics`). Event kinds:
///   scroll  — per-frame contentOffset/contentSize sample (a=offset, b=csHeight)
///   measure — every SelfSizingCell self-size (a=est, b=fit)
///   inv     — every invalidationContext height correction (a=old, b=new)
///   flush   — each deferred height applied at settle (a=old, b=new)
///   settle  — settle anchor shift (a=screenDelta)
///   prepare — full layout re-flow (a=itemCount, b=totalHeight)
/// DEBUG-only (the whole class and every call site are compiled out in
/// Release) and OFF by default — recording starts only after an explicit
/// `debug.scrollMetrics {enabled:true}` through the debug server.
final class ScrollMetricsRecorder {
    static let shared = ScrollMetricsRecorder()

    /// Master switch — false until enabled through the debug server.
    /// Plain Bool read on the hot path (no lock): worst case a race skips or
    /// records one extra event, which is fine for diagnostics.
    private(set) var isEnabled = false

    func setEnabled(_ on: Bool) {
        lock.lock()
        isEnabled = on
        if !on { head = 0; count = 0 }
        lock.unlock()
    }

    struct Event {
        let t: Double        // CACurrentMediaTime
        let kind: String
        let idx: Int
        let a: Double
        let b: Double
        let key: String      // content key prefix ("b:", "w:", "f:", "h:") or ""
        let src: String      // estimate source (pre/est/cache/def) or phase tag
        let pos: String      // above/inside/below viewport, or scroll phase
        let offset: Double   // contentOffset.y at event time
        let note: String
        let tag: String      // stable content-hash cell identity — survives
                             // message-object regeneration (history paging), so
                             // one cell's height trajectory can be followed
                             // across seed → measure → inv → flush events
    }

    private var buf: [Event?]
    private var head = 0          // next write slot
    private var count = 0
    private let capacity = 20000
    private let lock = NSLock()

    private init() {
        buf = Array(repeating: nil, count: capacity)
    }

    func record(kind: String, idx: Int = -1, a: Double = 0, b: Double = 0,
                key: String = "", src: String = "", pos: String = "",
                offset: Double = 0, note: String = "", tag: String = "") {
        guard isEnabled else { return }
        let e = Event(t: CACurrentMediaTime(), kind: kind, idx: idx, a: a, b: b,
                      key: key, src: src, pos: pos, offset: offset, note: note, tag: tag)
        lock.lock()
        buf[head] = e
        head = (head + 1) % capacity
        if count < capacity { count += 1 }
        lock.unlock()
    }

    /// Snapshot events in chronological order; optionally clear the ring.
    func snapshot(clear: Bool) -> [[String: Any]] {
        lock.lock()
        var events: [Event] = []
        events.reserveCapacity(count)
        let start = (head - count + capacity) % capacity
        for i in 0..<count {
            if let e = buf[(start + i) % capacity] { events.append(e) }
        }
        if clear {
            buf = Array(repeating: nil, count: capacity)
            head = 0
            count = 0
        }
        lock.unlock()
        return events.map { e in
            [
                "t": (e.t * 1000).rounded() / 1000,
                "kind": e.kind, "idx": e.idx,
                "a": (e.a * 10).rounded() / 10, "b": (e.b * 10).rounded() / 10,
                "key": e.key, "src": e.src, "pos": e.pos,
                "offset": (e.offset * 10).rounded() / 10, "note": e.note,
                "tag": e.tag,
            ]
        }
    }
}
#endif
