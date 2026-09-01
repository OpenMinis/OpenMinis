//
//  MessageListInfrastructure.swift
//  MinisApp
//
//  Shared types for the V3 collection-view-based chat message list.
//  These types were originally defined in the now-removed V1 and V2
//  message list implementations; V3 still depends on them while
//  SelectableMarkdownView / MarkdownRenderView walk the view hierarchy
//  looking for `NoAnimationCollectionView` and `SelfSizingCell`.
//

import Combine
import SwiftUI
import UIKit

// MARK: - MessageListItem

/// Item identifier for the diffable data source.
/// Assistant messages are split into header + individual blocks + footer
/// so each block is its own small cell with a stable height.
enum MessageListItem: Hashable {
    /// A complete user message, compact divider, or system info message.
    case wholeMessage(UUID)
    /// The "sparkles Minis" label row at the top of an assistant turn.
    case assistantHeader(UUID)
    /// A single AssistantBlock within an assistant turn.
    case assistantBlock(UUID, UUID)  // (messageId, blockId)
    /// Footer area: typing indicator, error, resume, usage.
    case assistantFooter(UUID)

    /// The message ID this item belongs to.
    var messageId: UUID {
        switch self {
        case .wholeMessage(let id), .assistantHeader(let id),
             .assistantFooter(let id): return id
        case .assistantBlock(let msgId, _): return msgId
        }
    }
}


// MARK: - Self-Sizing Cell

/// A plain UICollectionViewCell that correctly reports its preferred size
/// for UIHostingConfiguration content with custom layouts.
class SelfSizingCell: UICollectionViewCell {
    /// Clip content to cell bounds so SwiftUI content doesn't visually overflow
    /// into adjacent cells during the brief window between content update and
    /// layout re-measurement (text drift onto tool capsule bug).
    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.clipsToBounds = true
    }
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        contentView.clipsToBounds = true
    }

    /// Incremented each time contentConfiguration is applied.  Used by
    /// `preferredLayoutAttributesFitting` to detect that the hosting
    /// configuration changed between the moment UIKit scheduled the
    /// self-sizing pass and the moment it actually runs.
    private var configGeneration: UInt = 0

    /// Last successfully computed height — used as fallback when self-sizing
    /// is skipped (e.g. during bounds-change passes) to avoid re-entering
    /// SwiftUI's view graph and triggering a geometry observer race.
    private var lastComputedHeight: CGFloat?

    /// The width at which `lastComputedHeight` was measured. A width change
    /// still re-measures; an unchanged width reuses the cached height without
    /// calling `super.preferredLayoutAttributesFitting` or
    /// `systemLayoutSizeFitting`, either of which re-enters
    /// UIHostingConfiguration's view graph and races SwiftUI's async
    /// display-link thread (FB13213926).
    private var lastComputedWidth: CGFloat?

    /// CACurrentMediaTime() of the last successful self-measure.
    ///
    /// [T-ios-plaf-quiescent-graph-reentry] This no longer GATES the cache —
    /// see the note in `preferredLayoutAttributesFitting`. It is retained as
    /// diagnostics (how long ago this cell last really measured) and because
    /// re-introducing a time bound, should a genuine staleness case ever turn
    /// up, is then a one-line change rather than a re-plumb.
    private var lastMeasureMediaTime: CFTimeInterval?

    /// [T-ios-scroll-decel-height-drift] Pre-seeded height from the layout's
    /// content-keyed real-height memo, set in `configureCell` AFTER
    /// `applyContentConfiguration` for a cell whose content was already
    /// measured on a previous appearance. Unlike `lastComputedHeight`, this is
    /// NOT subject to the 50ms dedup window — a recycled cell scrolling back
    /// into view minutes later still skips the (2.5–3.8ms) systemLayoutSizeFitting
    /// pass that was the decel-jitter cost. Width-paired so a stale portrait
    /// height isn't reused after rotation. Cleared by applyContentConfiguration
    /// (content swap) so changed content always re-measures from scratch.
    ///
    /// SAFE vs the historical sizeThatFits→fillLayoutHole typesetter loop
    /// (01939f73 / 34143a73): this performs NO measurement — it returns a
    /// height UIKit itself measured earlier. It only SKIPS work, never adds a
    /// measurement path.
    private var seededHeight: CGFloat?
    private var seededWidth: CGFloat?

    /// [T-ios-scroll-decel-height-drift] Stable content key for the item this
    /// cell currently hosts, set by configureCell. Used to memoize the real
    /// measured height under a position-independent key — robust against the
    /// `indexPath(for:) == nil` (idx=-1) window during reuse, where an
    /// index-based write would miss.
    var contentKey: String?

    #if DEBUG
    /// [T-ios-scroll-metrics-ring] Stable content-hash identity (set by
    /// configureCell while recording) — lets measure events be grouped into a
    /// per-cell height trajectory across message-object regenerations.
    var metricsTag: String?
    #endif

    /// Seed the cell with a previously-measured real height so its first
    /// self-size after reuse short-circuits without re-entering SwiftUI's
    /// layout. Call AFTER applyContentConfiguration (which clears the seed).
    func seedMeasuredHeight(_ height: CGFloat, width: CGFloat) {
        guard height >= 4, width > 1 else { return }
        seededHeight = height
        seededWidth = width
    }

    /// Track configuration changes without overriding the property
    /// (UICollectionViewCell.contentConfiguration is declared in an extension
    /// on newer SDKs and cannot be overridden).
    func applyContentConfiguration(_ config: any UIContentConfiguration) {
        contentConfiguration = config
        configGeneration &+= 1
        lastComputedHeight = nil
        lastComputedWidth = nil
        lastMeasureMediaTime = nil
        // [T-ios-scroll-decel-height-drift] Drop any prior seed — content just
        // changed, so a seed from the old content would be wrong. configureCell
        // re-seeds (from the memo, keyed by the NEW content) right after this.
        seededHeight = nil
        seededWidth = nil
    }

    private static let sizingLogger = AppLogger(category: "CellSizing")

    // [ScrollStall] 1Hz-aggregated cache-hit counters.
    private static var dedupHits: Int = 0
    private static var dedupLastFlush: CFTimeInterval = 0
    private static var broadHits: Int = 0
    private static var broadLastFlush: CFTimeInterval = 0

    override func preferredLayoutAttributesFitting(
        _ layoutAttributes: UICollectionViewLayoutAttributes
    ) -> UICollectionViewLayoutAttributes {
        // Cache-hit short-circuit BEFORE super: when UIKit re-asks a cell to
        // self-size at a width it already measured, return the cached
        // height directly and skip both `super.preferredLayoutAttributesFitting`
        // *and* `systemLayoutSizeFitting`. Calling either re-enters
        // UIHostingConfiguration's view graph, which races with SwiftUI's
        // async display-link thread mutating ViewGraphGeometryObservers
        // and can crash with EXC_BAD_ACCESS (FB13213926, see the recurring
        // `nswtsUU8p6mRY9Pj7c7tU` crashpoint). The cache is invalidated
        // by applyContentConfiguration / prepareForReuse / clearCachedHeight,
        // so genuine content changes still re-measure promptly.
        // [T-ios-plaf-quiescent-graph-reentry, crash 2026-08-03 23:35 1.11(15)
        // iOS 27] Fifth crash of the FB13213926 series, and the first in the
        // QUIESCENT window: symbolication put the main thread in this cell's
        // `super.preferredLayoutAttributesFitting` reached from
        // `-[UICollectionView _updateLayoutAttributesForExistingVisibleViewsFading…]`
        // — a plain visible-cell attribute refresh, with `deferSelfSizing`
        // false and NO active streaming. Both later guards (e9d167c2's
        // interaction window, 81e43b58's streaming window) were therefore
        // inert, and only this dedup stood between that refresh and a hosting-
        // graph re-entry — but its 50ms timer had long since expired on a
        // settled screen, so every such pass re-measured.
        //
        // The time bound is dropped: a cached height whose WIDTH still matches
        // is not stale merely because it is old. Every real invalidation is
        // explicit and unconditional — applyContentConfiguration (content
        // swapped), prepareForReuse (cell recycled), clearCachedHeight (async
        // image load, markdown re-render, font/appearance change) all set
        // `lastComputedHeight = nil`. Nothing about elapsed time can make a
        // still-valid cache wrong, so the timer only bought extra graph
        // re-entries on exactly the settled screens where the crash lands.
        //
        // `lastMeasureMediaTime` is kept (still written, still cleared) — it
        // remains useful diagnostics and leaves the 50ms window one line away
        // if a height-staleness case this reasoning missed ever shows up.
        if let cached = lastComputedHeight,
           let cachedW = lastComputedWidth,
           abs(layoutAttributes.size.width - cachedW) < 1 {
            let copy = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
            copy.size.width = cachedW
            copy.size.height = cached
            // [ScrollStall] Count short-window cache hits; flush summary 1Hz.
            Self.dedupHits &+= 1
            let now = CACurrentMediaTime()
            if now - Self.dedupLastFlush > 1.0 {
                AppLogger(category: "ScrollStall").debug("[CACHE-DEDUP] last1s hits=\(Self.dedupHits)")
                Self.dedupHits = 0
                Self.dedupLastFlush = now
            }
            return copy
        }

        // Off-collection / torn-down guard — MUST run BEFORE super.
        //
        // [T-ios-selfsizingcell-refcount-crash, Build 50] The crash is NOT in
        // our measure block; it is inside `super.preferredLayoutAttributesFitting`
        // itself. super drives UIHostingContentView.systemLayoutSizeFitting →
        // setupSizeInvalidationHandler → ViewGraphGeometryObservers.addObserver →
        // Dictionary.removeAll → swift_deallocClassInstance fatalError (SIGABRT).
        // This fires when UIKit asks an off-collection / reused cell to self-size
        // while its SwiftUI view graph has already been (or is being) torn down
        // (use-after-free, Apple FB13213926). The previous guard sat AFTER the
        // super call, so it could never prevent the crash — by the time it ran,
        // super had already re-entered the dead view graph.
        //
        // When the cell is not attached to a live UICollectionView (window==nil,
        // non-collection superview, or no contentConfiguration), do NOT call super
        // and do NOT touch the SwiftUI hosting size path at all — just return the
        // attributes UIKit handed us, unmodified. This also preserves the original
        // [AttachHang off-collection guard] semantics: an off-collection cell must
        // neither write lastComputedHeight nor mutate the height (which previously
        // produced the post-image-load "extra whitespace below image" oscillation,
        // e.g. 346pt vs 486pt). Returning the input attrs unchanged keeps that
        // guarantee while removing the crash window.
        guard superview is UICollectionView, window != nil, contentConfiguration != nil else {
            return layoutAttributes
        }

        // [T-ios-plaf-interaction-graph-reentry, crash 2026-07-23 20:59]
        // SIGSEGV at 0x8000000000000040 in Update.begin()'s _MovableLockLock
        // while THIS cell's explicit hosting measure ran on the main thread
        // during a visible-cells bounds-change pass — the SwiftUI graph the
        // lock belonged to was concurrently torn down by the async renderer
        // (FB13213926 family; a segfault noff_try_objc cannot catch). The one
        // lever we control is how often the main thread re-enters the hosting
        // graph while the user is interacting: during deferSelfSizing (drag /
        // decel / streaming-suspend) a cell whose width is unchanged and whose
        // real height we already measured gains NOTHING from re-measuring —
        // return the cached height and skip super + systemLayoutSizeFitting
        // entirely. Streaming cells are exempt (their content grows in place
        // and must keep measuring); at settle deferSelfSizing flips false and
        // the normal re-measure path resumes, so any in-place change a
        // non-streaming cell made mid-scroll (async image load) is corrected
        // by the settle re-flow that already reconfigures visible cells.
        // [T-ios-plaf-streaming-graph-reentry, crash 2026-07-24 18:13 1.11(6)
        // iOS 27] Same FB13213926 family as the 07-23 series, but during PURE
        // STREAMING with no user interaction ("持续输出时候崩溃"): the async
        // renderer crashed in ViewGraphGeometryObservers.needsUpdate while the
        // main thread sat in this cell's super PLAF via
        // _updateVisibleCellsNow. deferSelfSizing was false (no drag/decel)
        // and the interaction guard exempts streaming cells, so EVERY chunk's
        // visible-cells pass still re-entered the hosting graph for every
        // visible cell. Two extensions of the e9d167c2 lever:
        //  A. non-streaming cells ALSO short-circuit while streaming is
        //     active (their content isn't changing mid-stream; an in-place
        //     change like an async image load clears the cache via
        //     clearCachedHeight() and falls through to a live measure);
        //  B. the streaming cell itself returns the layout's precalc height —
        //     the real-TextKit-engine calibration reseeded on every chunk,
        //     i.e. the exact value prepare() already laid out — so per-chunk
        //     growth stays graph-free too. Cells without a precalc (tool
        //     capsules, thinking, footer) keep the live measure; the
        //     stream-end settle (ranges cleared → guards inert) re-measures
        //     everything authoritatively.
        if let cv = superview as? UICollectionView,
           let layout = cv.collectionViewLayout as? MessageListLayout {
            let item = layoutAttributes.indexPath.item
            let streamingActive = layout.hasActiveStreaming
            let isStreamingItem = streamingActive && layout.isStreamingCell(item)
            if layout.deferSelfSizing || streamingActive,
               !isStreamingItem,
               let cached = lastComputedHeight, let cachedW = lastComputedWidth,
               abs(layoutAttributes.size.width - cachedW) < 1 {
                let copy = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
                copy.size.width = cachedW
                copy.size.height = cached
                return copy
            }
            if isStreamingItem,
               let precalc = layout.precalcHeight(at: item) {
                let copy = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
                copy.size.height = precalc
                return copy
            }
        }

        // [T-ios-scroll-decel-height-drift] Seed short-circuit: this cell was
        // pre-seeded (in configureCell) with the real height the SAME content
        // measured on a prior appearance. If the width still matches, return it
        // WITHOUT calling super.preferredLayoutAttributesFitting /
        // systemLayoutSizeFitting — that 2.5–3.8ms re-measure on a recycled
        // cell scrolling back into view is the decel-jitter cost we traced.
        // Promote the seed to lastComputedHeight so the broad/window caches
        // take over for subsequent passes, then drop the seed. Placed AFTER the
        // off-collection crash guard so seeded off-screen cells are still safe.
        //
        // WIDTH BASIS: the seed is keyed on the collection view's BOUNDS width
        // (what configureCell + the layout memo use), NOT `layoutAttributes.
        // size.width`, which at self-size time is the cell's inner CONTENT width
        // (bounds minus hosting-config margins). Compare against the live bounds
        // width so the seed isn't rejected by that constant offset. The returned
        // attributes still carry the content width UIKit proposed — we only
        // override the height.
        if let sh = seededHeight, let sw = seededWidth,
           let cv = superview as? UICollectionView,
           abs(cv.bounds.width - sw) < 1 {
            seededHeight = nil
            seededWidth = nil
            lastComputedHeight = sh
            lastComputedWidth = layoutAttributes.size.width
            lastMeasureMediaTime = CACurrentMediaTime()
            let copy = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
            copy.size.height = sh
            return copy
        }

        // [T-ios-selfsizingcell-super-plaf-async-race, crash3 build 43 iOS 18.3]
        // `super.preferredLayoutAttributesFitting` is NOT a passive attribute
        // copy — it drives UICollectionViewCell.systemLayoutSizeFittingSize →
        // UIHostingContentView.systemLayoutSizeFitting → ViewRendererHost.
        // sizeThatFits → Update.begin() (crash frames 6-11), i.e. a FULL
        // re-entry into the hosting view graph on the main thread, taking the
        // SwiftUI global render lock. The explicit measure further down (line
        // ~289) was already pinned with `withExtendedLifetime` + noff_try_objc
        // against the FB13213926 AsyncRenderer dealloc race, but THIS super call
        // — which measures the same hosting subtree first — was left unguarded.
        // crash3 crashed on the DisplayLink async-render thread (Thread 12) in
        // ViewGraphGeometryObservers.needsUpdate while the main thread sat in
        // exactly this super call (Thread 0, blocked on _MovableLockLock inside
        // Update.begin). Pin the whole contentView subtree here too, so no
        // descendant hosting view can be deallocated mid-measure while the
        // async renderer iterates the same geometry observers. Collected once
        // and reused by the explicit measure below. Functionality-neutral:
        // extended lifetime only delays dealloc; the returned attributes are
        // byte-identical.
        var hostingSubtree: [UIView] = []
        Self.collectSubtree(self.contentView, into: &hostingSubtree)
        var superAttrs: UICollectionViewLayoutAttributes = layoutAttributes
        let superOk = noff_try_objc {
            withExtendedLifetime(hostingSubtree) {
                superAttrs = super.preferredLayoutAttributesFitting(layoutAttributes)
            }
        }
        if !superOk {
            // super's hosting measure threw an ObjC exception mid-race. Fall
            // back to the best height we have (cached, else the proposed one)
            // rather than crashing.
            Self.sizingLogger.info("[CellSizing] ⚠️ super.preferredLayoutAttributesFitting threw — using fallback height")
            if let cached = lastComputedHeight {
                let copy = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
                copy.size.height = cached
                return copy
            }
            return layoutAttributes
        }
        let attrs = superAttrs

        // Same-width cache hit (no time bound). Same rationale as the
        // dedup-window above but covers the broader "width never changed"
        // case across longer scroll runs.
        if let cached = lastComputedHeight,
           abs(layoutAttributes.size.width - attrs.size.width) < 1 {
            attrs.size.height = cached
            // [ScrollStall] Count broad cache hits; flush summary 1Hz.
            Self.broadHits &+= 1
            let now = CACurrentMediaTime()
            if now - Self.broadLastFlush > 1.0 {
                AppLogger(category: "ScrollStall").debug("[CACHE-BROAD] last1s hits=\(Self.broadHits)")
                Self.broadHits = 0
                Self.broadLastFlush = now
            }
            return attrs
        }

        let gen = configGeneration
        let targetSize = CGSize(width: layoutAttributes.size.width, height: UIView.layoutFittingCompressedSize.height)
        let start = CACurrentMediaTime()
        // Wrap systemLayoutSizeFitting in noff_try_objc to catch NSExceptions
        // from AttributeGraph when SwiftUI's async display-link thread races
        // with this measurement (EXC_BAD_ACCESS in ViewGraph.updateOutputsAsync).
        //
        // [T-viewgraph-race-lifetime 2026-05-20] Pair with `withExtendedLifetime`
        // pinning every subview of contentView (the UIHostingContentView and
        // anything else UIKit parked here). Crash 2026-05-20 07:33 was a Swift
        // `fatalError` in `swift_deallocClassInstance.cold.1` triggered when
        // `ViewGraphGeometryObservers.addObserver` ran a `Dictionary.removeAll`
        // on this thread while the SwiftUI AsyncRenderer thread was concurrently
        // iterating the same dict in `ViewGraphGeometryObservers.needsUpdate`.
        // Apple FB13213926. By keeping a strong reference to the hosting view
        // around the whole measurement, refcount decrements happen AFTER the
        // measure block returns — past the point where AsyncRenderer is also
        // looking at the same objects — so the dealloc race no longer fires.
        // Functionality unaffected: extended lifetime only delays dealloc; the
        // measured size and SwiftUI render are byte-identical.
        //
        // [T-ios-code-block-scroll-copy-freeze 2026-05-30] The original pin
        // captured only `contentView.subviews` (the top-level UIHostingContentView).
        // Crash heart_crash_304248 (build 32) recurred: the freed object the
        // AsyncRenderer thread dereferenced in `ViewGraphGeometryObservers.needsUpdate`
        // lives DEEP inside the hosting view graph (a per-attachment hosting
        // subtree — the `sh` code-block scroll view is a UIScrollView nested
        // several levels down), not at the top level, so the shallow pin never
        // kept it alive. Walk the whole contentView subtree and pin every view
        // for the duration of the measure so no descendant's refcount can hit
        // zero mid-measurement while AsyncRenderer iterates the same observers.
        var fittingSize = CGSize(width: targetSize.width, height: attrs.size.height)
        // `hostingSubtree` was already collected and pinned around the super
        // call above; reuse it here so the explicit measure runs under the same
        // lifetime guarantee without walking the subtree a second time.
        let measureOk = noff_try_objc {
            withExtendedLifetime(hostingSubtree) {
                fittingSize = self.contentView.systemLayoutSizeFitting(
                    targetSize,
                    withHorizontalFittingPriority: .required,
                    verticalFittingPriority: .fittingSizeLevel
                )
            }
        }
        if !measureOk {
            Self.sizingLogger.info("[CellSizing] ⚠️ systemLayoutSizeFitting threw — using fallback height \(String(format: "%.0f", attrs.size.height))")
            return attrs
        }
        let elapsed = (CACurrentMediaTime() - start) * 1000
        let sinceAppear = (CFAbsoluteTimeGetCurrent() - AIChatViewModel.onAppearTimestamp) * 1000
        let idx = (superview as? UICollectionView)?.indexPath(for: self)?.item ?? -1
        // [ScrollStall] Only log measurements that actually cost time. Streaming
        // produces a height delta on every token, so the old "delta > 1pt"
        // gate fired per token; restrict to real measurement cost (>=5ms) to
        // keep the slow-path signal without the per-token noise.
        let est = layoutAttributes.size.height
        let fit = fittingSize.height
        #if DEBUG
        // [T-ios-scroll-metrics-ring] Record EVERY self-size (no cost/delta
        // threshold) so the complete est→fit distribution is retrievable via
        // debug.scrollMetrics.
        if let cv = superview as? UICollectionView {
            let phase = cv.isDecelerating ? "decel" : (cv.isTracking ? "drag" : "idle")
            ScrollMetricsRecorder.shared.record(
                kind: "measure", idx: idx, a: est, b: fit,
                key: contentKey ?? "",
                src: lastComputedHeight == nil ? "first" : "re",
                pos: phase, offset: cv.contentOffset.y,
                note: String(format: "%.1fms", elapsed),
                tag: metricsTag ?? "")
        }
        #endif
        if elapsed >= 5 {
            Self.sizingLogger.info("[SessionLoad] sizing idx=\(idx) \(String(format: "%.1f", elapsed))ms T+\(String(format: "%.0f", sinceAppear))ms est=\(String(format: "%.0f", est))→\(String(format: "%.0f", fit))")
        }
        // Slow-measure diagnostic: when systemLayoutSizeFitting takes more
        // than ~50ms for a single cell, the main thread can spend most of
        // a frame budget just measuring this one row. Repeated slow
        // measurements (e.g. during scroll) are how the CPU watchdog ips
        // shows main-thread CPU pinned in NSStringDrawing/CoreText. Logging
        // the offending cell's metrics lets us trace it back to the
        // markdown payload via session traces. Threshold matches one half
        // of a 16.7ms frame budget across two cells.
        if elapsed > 50 {
            let cfg = self.contentConfiguration
            let cfgKind = cfg.map { String(describing: type(of: $0)) } ?? "nil"
            Self.sizingLogger.info("[CellSizing] ⚠️ SLOW MEASURE \(String(format: "%.0f", elapsed))ms idx=\(idx) width=\(String(format: "%.0f", layoutAttributes.size.width)) est=\(String(format: "%.0f", layoutAttributes.size.height))→\(String(format: "%.0f", fittingSize.height)) cfg=\(cfgKind)")
        }
        #if DEBUG
        // [ScrollDecel] Log every measure that happens during an active scroll
        // glide (decelerating OR dragging) so we can see WHICH cells the
        // landing-frame jank is paying for. The previous gate (>=8ms AND
        // isDecelerating) logged nothing during the repro: the cost is
        // death-by-many-small-measures — a burst of first-time cell measures
        // each <8ms summing to a 100ms+ frame — and UIKit's isDecelerating flag
        // isn't always set on the exact runloop the measure runs. So: lower to
        // >=3ms and accept dragging|decelerating, tagging which. .info so the
        // device capture keeps it (it filters .debug).
        if elapsed >= 3, let cv = superview as? UICollectionView, cv.isDecelerating || cv.isDragging {
            let cfgKind = self.contentConfiguration.map { String(describing: type(of: $0)) } ?? "nil"
            let phase = cv.isDecelerating ? "decel" : "drag"
            let firstTime = (lastComputedHeight == nil) ? "FIRST" : "recompute"
            Self.sizingLogger.info("[ScrollDecel][cell-measure] phase=\(phase) \(firstTime) idx=\(idx) cost=\(String(format: "%.1f", elapsed))ms est=\(String(format: "%.0f", est))→\(String(format: "%.0f", fit)) offset=\(String(format: "%.0f", cv.contentOffset.y)) cfg=\(cfgKind)")
        }
        #endif
        // Detect potential text-drift: cell was near-zero height and now has real content
        if layoutAttributes.size.height < 4 && fittingSize.height > 20 {
            Self.sizingLogger.info("[TextDrift] ⚠️ CELL GREW FROM ~0: idx=\(idx) \(String(format: "%.0f", layoutAttributes.size.height))→\(String(format: "%.0f", fittingSize.height))pt — potential text overlap during this transition")
        }
        guard configGeneration == gen else { return attrs }
        #if DEBUG
        // [BottomGapDiag] Catch "the layout is holding a taller height than the
        // cell actually renders" — the shape behind every reported blank strip
        // so far, whatever the trigger.
        //
        // Established for the thinking pill: `[ThinkingCollapse] auto-collapse`
        // fired 6 times while `post` (the height-invalidation notification)
        // fired 0 times, so the collection view kept the EXPANDED height after
        // the pill collapsed and the difference showed as dead space. The tool
        // card case has no equivalent invalidation path at all — there is no
        // `.toolBlockToggled` anywhere in the tree — so if a tool card changes
        // height nothing tells the layout.
        //
        // Rather than instrument each suspect separately, this logs the
        // DISAGREEMENT itself: whenever the height the layout proposed exceeds
        // what the cell needs by a visible margin, print both plus the cell's
        // content key. That names the offending cell regardless of which state
        // change caused it, and a run with no such line rules the whole class
        // out. Threshold 24pt is well above sub-pixel/rounding noise and below
        // the ~190pt gap seen in the report.
        let proposed = layoutAttributes.size.height
        if proposed - fittingSize.height > 24, fittingSize.height > 4 {
            Self.sizingLogger.info(
                "[BottomGapDiag][stale-height] idx=\(idx) held=\(String(format: "%.1f", proposed)) "
                + "needs=\(String(format: "%.1f", fittingSize.height)) "
                + "excess=\(String(format: "%.1f", proposed - fittingSize.height))pt "
                + "key=\(contentKey ?? "-") — layout reserved more than the cell renders (blank strip)")
        }
        // [BottomGapDiag][seed-drift] Long-user-message report: log the SIGNED
        // disagreement between the height the layout came in holding (from the
        // estimator / precalc seed) and what the cell actually needs, for BOTH
        // directions and at a much lower threshold than the 24pt strip probe.
        //
        // Direction matters and the two are different bugs:
        //   held > needs  → layout reserved dead space (visible blank strip)
        //   held < needs  → cell laid out SHORT; SwiftUI Text truncates, then
        //                   expands on re-measure, shoving neighbours around.
        // The estimator's own comments admit boundingRect runs ~2.4pt/line
        // tighter than SwiftUI's rendered line box and that the CJK correction
        // is a FLAT +7 calibrated on 1–4 line bubbles, so the drift should grow
        // with line count. Printing `perLine` makes that testable directly:
        // if it is roughly constant across short and long bubbles, the error is
        // per-line and the flat correction is the bug.
        let delta = proposed - fittingSize.height
        if abs(delta) >= 4, fittingSize.height > 4, proposed > 4 {
            let approxLines = max(1, Int((fittingSize.height / 20).rounded()))
            Self.sizingLogger.info(
                "[BottomGapDiag][seed-drift] idx=\(idx) held=\(String(format: "%.1f", proposed)) "
                + "actual=\(String(format: "%.1f", fittingSize.height)) "
                + "delta=\(String(format: "%+.1f", delta))pt "
                + "approxLines=\(approxLines) perLine=\(String(format: "%+.2f", delta / CGFloat(approxLines)))pt "
                + "dir=\(delta > 0 ? "RESERVED-TOO-MUCH" : "LAID-OUT-SHORT") key=\(contentKey ?? "-")")
        }
        #endif
        attrs.size.height = fittingSize.height
        // Don't cache very small heights (< 4pt) — these typically represent
        // empty text blocks that will receive content shortly via streaming.
        // Caching them prevents the cell from re-measuring when content arrives
        // (the @Published change triggers preferredLayoutAttributesFitting, but
        // the cached height short-circuits it, leaving the cell at ~0 height).
        if fittingSize.height >= 4 {
            lastComputedHeight = fittingSize.height
            lastComputedWidth = layoutAttributes.size.width
            lastMeasureMediaTime = CACurrentMediaTime()
            // [T-ios-scroll-decel-height-drift] Memoize the real height under the
            // content key so a later re-entry can seed it and skip this measure.
            // Done HERE (every successful self-size), not in the layout's
            // invalidationContext, which doesn't fire for stable cells during
            // browse/decel (deferSelfSizing → shouldInvalidateLayout=false) —
            // that was why the memo stayed empty (memoForKey=none). Key off the
            // cell-held contentKey (set in configureCell), NOT the index, which
            // is -1 during the reuse window when most measures actually happen.
            if let key = contentKey,
               let cv = superview as? UICollectionView,
               let layout = cv.collectionViewLayout as? MessageListLayout {
                // [T-ios-memo-key-ignores-render-state] Qualify the key with the
                // block's CURRENT async-attachment render state. `contentKey` is
                // built from `block.content.count`, which a LaTeX render does
                // not change (`$P_A$` is the same string before and after
                // MathJax), so without this a height measured while the
                // formulas were still blank placeholders is stored under the
                // same key as the finished layout — and seeded back forever.
                let renderKey = Self.renderQualifiedKey(key, for: self)
                layout.recordMeasuredHeight(forKey: renderKey, height: fittingSize.height, boundsWidth: cv.bounds.width)
                #if DEBUG
                AppLogger(category: "MemoWrite").info("[MemoWrite] key=\(renderKey) h=\(String(format: "%.1f", fittingSize.height)) w=\(String(format: "%.0f", cv.bounds.width))")
                #endif
            }
        }
        #if DEBUG
        // [T-attachment-zero-origin] Capture message-list snapshot right after
        // a cell finishes self-sizing. This is the canonical UIKit cell-size
        // path; if anything mutates attachment view frames during the
        // hosting view's systemLayoutSizeFitting call (e.g. UIKit resetting
        // a NSTextAttachment-anchored view back to attachmentBounds.origin),
        // we'll see the change between two consecutive PLAF snapshots.
        let cellPtr = String(format: "%p", Int(bitPattern: Unmanaged.passUnretained(self).toOpaque()))
        MessageListSnapshotCollector.captureIfEnabled(from: self, trigger: "cell.PLAF", triggerDetail: cellPtr)
        #endif
        return attrs
    }

    /// Recursively collect `view` and every descendant into `out`. Used to pin
    /// the entire hosting view subtree (not just top-level subviews) across a
    /// `systemLayoutSizeFitting` measure so no descendant — e.g. a code-block
    /// UIScrollView nested several levels inside the UIHostingContentView — can
    /// be deallocated mid-measurement while the SwiftUI AsyncRenderer thread is
    /// concurrently iterating ViewGraphGeometryObservers (FB13213926).
    private static func collectSubtree(_ view: UIView, into out: inout [UIView]) {
        out.append(view)
        for sub in view.subviews {
            collectSubtree(sub, into: &out)
        }
    }

    /// [T-ios-memo-key-ignores-render-state] Append the block's current
    /// async-attachment render state to a content key.
    ///
    /// Walks the cell's subtree for the hosted `SelectableMarkdownTextView` and
    /// asks it for `asyncAttachmentRenderSignal()` — the summed rendered height
    /// of its math/image attachments. Returns the key unchanged when the cell
    /// hosts no such view (headers, footers, tool capsules), so those keys keep
    /// their existing shape and cache behaviour exactly as before.
    static func renderQualifiedKey(_ key: String, for cell: UIView) -> String {
        guard let signal = firstMarkdownRenderSignal(in: cell) else { return key }
        return "\(key):r\(signal)"
    }

    private static func firstMarkdownRenderSignal(in view: UIView) -> Int? {
        if let tv = view as? SelectableMarkdownTextView {
            return tv.asyncAttachmentRenderSignal()
        }
        for sub in view.subviews {
            if let s = firstMarkdownRenderSignal(in: sub) { return s }
        }
        return nil
    }

    /// Clear the cached computed height so the next preferredLayoutAttributesFitting
    /// call re-measures via systemLayoutSizeFitting instead of returning stale data.
    func clearCachedHeight() {
        lastComputedHeight = nil
        lastComputedWidth = nil
        lastMeasureMediaTime = nil
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        lastComputedHeight = nil
        lastComputedWidth = nil
        lastMeasureMediaTime = nil
        // [T-ios-contextmenu-liquidmorph-reuse] Cancel any context-menu
        // presentation still in flight for THIS cell before UIKit hands it to a
        // different message.
        //
        // TestFlight 1.10 (57), iPhone 17 Pro, iOS 27: EXC_BREAKPOINT (SIGTRAP)
        // — "Swift runtime failure: Unexpectedly found nil while unwrapping an
        // Optional" inside UIKit's own
        // UIKitLiquidMorphAnimationContext.configureMorphAnimationHierarchyIfNeeded,
        // reached from _UIContextMenuLiquidMorphPresentationAnimation
        // .performTransition(). No Minis frames on the stack: iOS 26/27's Liquid
        // Glass morph resolves the source view's hierarchy as the animation
        // configures itself, and traps if that view is no longer in a window.
        //
        // This list recycles cells continuously while a reply streams, so a cell
        // long-pressed just before it scrolls out is exactly that precondition.
        // We cannot fix UIKit's force-unwrap, but we can stop handing it a
        // detached source view: dismissing here ends the interaction while the
        // cell is still valid. No-op when no menu is showing, so the ordinary
        // reuse path is unaffected.
        // Walk the subtree: SwiftUI's `.contextMenu` installs its
        // UIContextMenuInteraction on a view INSIDE the hosting configuration,
        // not on contentView itself, so checking only contentView would miss it.
        var subtree: [UIView] = []
        Self.collectSubtree(contentView, into: &subtree)
        for view in subtree {
            for interaction in view.interactions {
                (interaction as? UIContextMenuInteraction)?.dismissMenu()
            }
        }
        // Note: do NOT set contentConfiguration = nil here.
        // Clearing the hosting configuration tears down the SwiftUI view graph,
        // but UIKit may later call isHiddenForReuse=false which tries to restore
        // the destroyed graph, causing a use-after-free crash
        // (EXC_BAD_ACCESS in AG::Subgraph::invalidate_now).
        // Let UIKit manage the UIHostingConfiguration lifecycle naturally.
    }
}

// MARK: - NoAnimationCollectionView

/// UICollectionView subclass that strips ALL UIKit implicit animations.
/// This prevents cell insertion/deletion/resize animations that cause
/// the "slide from top" jitter during streaming updates.
final class NoAnimationCollectionView: UICollectionView {
    /// Set to true while a diffable data source snapshot is being applied.
    /// During application the data source may be temporarily inconsistent,
    /// so calling super.layoutSubviews() can trigger an
    /// NSInternalInconsistencyException inside UIKit.
    var isApplyingSnapshot = false

    /// [T-ios-stream-grow-anim] When true, non-snapshot programmatic layout
    /// passes (a streaming cell growing taller) animate ONLY the contentOffset
    /// over a fixed 0.2s window so the viewport glides to the new bottom instead
    /// of snapping. Kept as a flag so it can be flipped off instantly if it
    /// regresses the streaming stability this NoAnimation collection view was
    /// built to guarantee.
    var streamingGrowAnimationEnabled = true

    /// [T-ios-stream-grow-anim] Reentrancy guard: the grow path calls
    /// `setContentOffset`, which can synchronously re-enter `layoutSubviews`.
    /// Without this guard that re-entry would recurse back into the animated
    /// branch (and `super.layoutSubviews()` while a layout pass is already on
    /// the stack). When set, nested passes take the plain `super` path.
    private var inStreamingGrowLayout = false

    /// Back-reference to the owning controller so a bounds-width change can
    /// front-load the visible-cell re-measure (prewarm) the same way rotation
    /// does. Weak to avoid a retain cycle (the VC owns this collection view).
    weak var owningViewController: MessageListViewController?

    /// Last bounds width we prewarmed for. A NavigationSplitView sidebar
    /// collapse/expand changes the detail column width without a UIKit
    /// transition coordinator, so — unlike rotation — there's no completion
    /// callback to re-measure visible cells. We detect the width change here
    /// and schedule the prewarm. Tracked separately from the DEBUG-only
    /// `lastBoundsSize` so the prewarm path compiles in every configuration.
    /// [T-ios-ipad-rotate-collapse-scroll-jank]
    private var lastPrewarmedWidth: CGFloat = 0

    #if DEBUG
    /// [WatchdogProbe] previous bounds size — when this differs from the new
    /// bounds, UIKit goes down `_updateLayoutAttributesForExistingVisibleViewsFadingForBoundsChange`,
    /// which re-asks every visible cell for its preferred size. That's the
    /// path observed in the 0x8BADF00D crash report. We log the transition
    /// so we can correlate with WatchdogProbe STF window alarms.
    private var lastBoundsSize: CGSize = .zero
    private static let watchdogProbe = AppLogger(category: "WatchdogProbe")
    #endif

    override func layoutSubviews() {
        guard !isApplyingSnapshot else { return }
        // Skip expensive layout when backgrounded to avoid 0x8BADF00D watchdog kill.
        guard UIApplication.shared.applicationState != .background else {
            return
        }
        // [T-ios-selfsizingcell-refcount-crash, Build 50] Skip layout entirely when
        // off-window. This layoutSubviews pass is the outer driver (crash frames
        // 22-28) that re-asks visible cells for their preferred size and re-enters
        // SwiftUI hosting self-sizing — the exact path that hits the
        // ViewGraphGeometryObservers use-after-free over reused / torn-down cells.
        // Off-window there is nothing to present, so the whole pass is wasted work
        // anyway; returning here closes the crash window without adding a redundant
        // (animation-stripped or not) layout. Same shape as the background guard
        // above. The SelfSizingCell off-collection guard is the inner backstop.
        guard window != nil else { return }

        #if DEBUG
        let newSize = bounds.size
        let widthChanged = abs(newSize.width - lastBoundsSize.width) > 0.5
        let heightChanged = abs(newSize.height - lastBoundsSize.height) > 0.5
        if widthChanged || heightChanged {
            // Width change is the dangerous one — it forces every visible
            // cell's UITextView to re-measure intrinsic size via TextKit1.
            let visibleCount = visibleCells.count
            Self.watchdogProbe.info("[WatchdogProbe][cv-bounds] \(widthChanged ? "W" : " ")\(heightChanged ? "H" : " ") old=\(Int(lastBoundsSize.width))x\(Int(lastBoundsSize.height)) new=\(Int(newSize.width))x\(Int(newSize.height)) visibleCells=\(visibleCount) tracking=\(isTracking ? 1 : 0) decel=\(isDecelerating ? 1 : 0)")
            lastBoundsSize = newSize
        }
        let t0 = CFAbsoluteTimeGetCurrent()
        #endif

        // [T-ios-stream-grow-anim] Animate ONLY while a message is actively
        // streaming (Processing). The layout sets `streamingCellIndex` to the
        // streaming message's cell and clears it the instant the run ends, so
        // this is the precise "is a Markdown message growing right now" signal —
        // it keeps the grow animation off every other programmatic layout pass
        // (cell reuse, image-load resize, scroll settle), confining it to the
        // streaming Markdown cell's height growth.
        let isStreamingNow = (collectionViewLayout as? MessageListLayout)?.streamingCellIndex != nil

        // [T-ios-stream-grow-anim] Only animate when the content actually
        // OVERFLOWS the viewport, i.e. there is a positive scrollable range. The
        // first message of a session (and any short turn) leaves contentSize <
        // bounds — a sub-viewport state where the resting offset is 0 and there
        // is nothing to scroll. Running the offset animation there fought
        // UIScrollView's bounce around offset 0, producing the −7→−46→0 decel
        // wobble seen on iOS 16 ("the whole collection view shakes"). Gate it
        // out: below the overflow threshold the layout pass stays un-animated.
        let scrollableRange = contentSize.height - bounds.height + adjustedContentInset.bottom
        let contentOverflowsViewport = scrollableRange > 1

        // During user scroll (tracking/decelerating), let UIKit handle layout
        // naturally — stripping animations mid-scroll kills the deceleration
        // curve and causes a visible jitter at the end.
        if isTracking || isDecelerating {
            super.layoutSubviews()
        } else if streamingGrowAnimationEnabled, isStreamingNow, contentOverflowsViewport,
                  !isApplyingSnapshot, !inStreamingGrowLayout {
            // [T-ios-stream-grow-anim] The smooth-streaming trick, done right:
            // let the CELL FRAMES snap to their final positions instantly (the
            // content immediately occupies its final layout), but animate ONLY
            // the contentOffset over a fixed 0.2s window so the VIEWPORT glides
            // up to the new bottom.
            //
            // Why this and not the reverse: a cell's on-screen position is
            // `frame.y − contentOffset.y`. If BOTH the frame AND the offset
            // animate (the naive `UIView.animate { super.layoutSubviews() }`),
            // they interpolate on independent CA timings, so cells above the
            // streaming one (the "Minis" header) drift — the header↔body spacing
            // jitter. Animating ONLY the offset means there is a single moving
            // quantity: every cell, header included, slides up together in
            // lock-step as the viewport eases to the bottom. No relative drift.
            //
            // Implementation: run the layout pass with frames applied
            // instantly (no animation). super.layoutSubviews() also lands the
            // final contentOffset; capture it, restore the PRE-pass offset, then
            // animate to the captured final offset.
            inStreamingGrowLayout = true
            defer { inStreamingGrowLayout = false }

            let preOffset = contentOffset
            var finalOffset = preOffset
            UIView.performWithoutAnimation {
                super.layoutSubviews()
                finalOffset = self.contentOffset
            }
            // Only animate a sane, finite downward (or any) viewport move. Guard
            // against NaN/inf (defensive — a corrupt layout pass) and skip the
            // animation for negligible deltas so we don't churn CA transactions.
            let delta = finalOffset.y - preOffset.y
            if delta.isFinite && finalOffset.y.isFinite && abs(delta) > 0.5 {
                setContentOffset(preOffset, animated: false)
                UIView.animate(withDuration: 0.20,
                               delay: 0,
                               options: [.curveEaseOut, .beginFromCurrentState, .allowUserInteraction]) {
                    self.setContentOffset(finalOffset, animated: false)
                }
            } else if finalOffset != contentOffset {
                // Non-animated correction for the tiny/degenerate case so the
                // viewport still lands where the layout intended.
                setContentOffset(finalOffset, animated: false)
            }
        } else {
            // Kill any pending CALayer animations for programmatic updates
            // (snapshot apply, etc.)
            UIView.performWithoutAnimation {
                super.layoutSubviews()
            }
        }

        // [T-ios-ipad-rotate-collapse-scroll-jank] Front-load the visible-cell
        // re-measure when the bounds WIDTH changes outside of an active scroll
        // (sidebar collapse/expand, detail-column resize). Rotation already
        // gets this via viewWillTransition's completion; collapse has no such
        // coordinator, so without this the height caches stay stale/empty and
        // the user pays a synchronous TextKit self-size per cell as they scroll
        // each one in. Guarded so it never fires on the scroll path (width is
        // unchanged during scroll) and only once per distinct width. Deferred
        // to the next runloop so it runs after this layout pass settles, the
        // same ordering rotation's completion gives.
        let w = bounds.width
        if w > 0, abs(w - lastPrewarmedWidth) > 0.5, !isTracking, !isDecelerating, !isApplyingSnapshot {
            lastPrewarmedWidth = w
            DispatchQueue.main.async { [weak self] in
                self?.owningViewController?.prewarmVisibleCellsAfterWidthChange()
            }
        }

        #if DEBUG
        let elapsed = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        if elapsed >= 200 {
            Self.watchdogProbe.warning("[WatchdogProbe][cv-layout] slow layoutSubviews elapsed=\(String(format: "%.1f", elapsed))ms visibleCells=\(visibleCells.count) bounds=\(Int(newSize.width))x\(Int(newSize.height)) widthChanged=\(widthChanged ? 1 : 0)")
        }
        #endif
    }

    /// Suppress UIKit's automatic scroll-to-first-responder when a UITextView
    /// inside a cell becomes first responder (e.g. long-press text selection).
    override func scrollRectToVisible(_ rect: CGRect, animated: Bool) {
        // no-op: we manage scroll position ourselves
    }

    /// Momentary lock: set to true ONLY during becomeFirstResponder/resignFirstResponder
    /// to block UIKit's _adjustContentOffsetIfNecessary. Must be false at all other times
    /// so normal scrolling and selection handle dragging work.
    var offsetLocked: Bool = false

    override func setContentOffset(_ contentOffset: CGPoint, animated: Bool) {
        if offsetLocked {
            let delta = abs(contentOffset.y - self.contentOffset.y)
            if delta > 1 {
                return
            }
        }
        super.setContentOffset(contentOffset, animated: animated)
    }

    /// Tracks whether a SelectableMarkdownTextView currently has an active text selection.
    var hasActiveTextSelection: Bool = false

    /// [T-ios-preapply-endediting] The message-list text view (if any) that is
    /// currently first responder. Set in becomeFirstResponder / cleared in
    /// resignFirstResponder by SelectableMarkdownTextView and TableCellTextView.
    ///
    /// This exists so applySnapshot can end editing BEFORE a structural
    /// `dataSource.apply`, outside the batch-update transaction. Both TestFlight
    /// crash families require a live first responder inside the collection view
    /// at apply time: the AttributeGraph re-entry (super.resignFirstResponder's
    /// responder-chain walk reads the graph mid-transaction) and the
    /// _resignOrRebaseFirstResponderViewWithIndexPathMapping assertion (our
    /// deferred resign returned false, refusing the rebase). Resigning up front
    /// removes the shared precondition for both.
    ///
    /// Deliberately NOT `hasActiveTextSelection`: a text view can hold first
    /// responder with no selection yet (long-press menu just presented), and the
    /// gate must track responder status, not selection status. Weak, so cell
    /// reuse or teardown can never leave a dangling pointer.
    weak var trackedTextResponder: UIView?

    // MARK: - Text-selection edge auto-scroll
    //
    // [T-ios-text-selection-autoscroll-followthrough] UITextView's native
    // selection auto-scroll only scrolls the text view itself, which here is
    // sized to its content inside a cell — so dragging a selection handle to the
    // top/bottom edge of the collection view does NOT scroll the list, and the
    // user can't extend a selection past the visible page (Android got this via
    // a custom SelectionDragTracker in 8361ca31).
    //
    // The first cut (#673) drove the ticker off textViewDidChangeSelection and
    // stopped it after 150ms of "no selection change". That was wrong: when the
    // user holds the handle STILL at the edge, the selected character range does
    // NOT change as content scrolls (selection is anchored to document offsets,
    // not screen points), so no callback fires and the ticker died — the user
    // saw nothing until they lifted and layout settled.
    //
    // This version drives the ticker off the LIVE TOUCH instead: the text view
    // tracks the selection-handle drag with a non-cancelling long-press gesture
    // and feeds the live touch Y (in this collection view's space) every frame.
    // The ticker keeps running on the last touch point — so a finger held at the
    // edge keeps scrolling — until the gesture ends/cancels (a real finger lift)
    // or the touch leaves the edge zone. As the list scrolls under the held
    // handle, UITextView extends the selection to the text now under the finger.

    /// Distance from the collection view's top/bottom edge within which a
    /// selection-handle drag triggers auto-scroll.
    private static let selectionAutoScrollEdgeInset: CGFloat = 80
    /// Per-tick scroll step at 60Hz (points). ~12pt/tick ≈ 720pt/s.
    private static let selectionAutoScrollStep: CGFloat = 12

    private var selectionAutoScrollLink: CADisplayLink?
    /// The live drag touch point, in THIS collection view's coordinate space,
    /// updated on every gesture `.changed`. The ticker reads this each frame so
    /// it keeps scrolling while the finger is held at the edge — no
    /// selection-change event needed. nil when no drag is active.
    ///
    /// [T-ios-selection-autoscroll-edge-flicker] Stores the full point (not just
    /// Y): the per-frame selection-follow uses the real touch X so the caret it
    /// computes matches UIKit's own (a fixed mid-X mismatched the native caret,
    /// so the two fought each frame → flicker).
    private var selectionDragTouchPoint: CGPoint?

    /// Called by the active SelectableMarkdownTextView while its selection-handle
    /// drag is live (gesture `.began`/`.changed`). `touchPoint` is the current
    /// touch location in this collection view's coordinates. Starts the ticker
    /// (or keeps it alive) when the touch is in an edge zone; stops it otherwise.
    func updateSelectionAutoScroll(touchPoint: CGPoint) {
        selectionDragTouchPoint = touchPoint
        guard hasActiveTextSelection, selectionAutoScrollDirection(forTouchY: touchPoint.y) != 0 else {
            stopSelectionAutoScroll()
            return
        }
        if selectionAutoScrollLink == nil {
            let link = CADisplayLink(target: self, selector: #selector(stepSelectionAutoScroll))
            link.add(to: .main, forMode: .common)
            selectionAutoScrollLink = link
        }
    }

    /// Stop the auto-scroll ticker — the drag gesture ended/cancelled, the
    /// selection cleared, or the touch moved out of the edge zone.
    func stopSelectionAutoScroll() {
        selectionAutoScrollLink?.invalidate()
        selectionAutoScrollLink = nil
        selectionDragTouchPoint = nil
        onAutoScrollStep = nil
    }

    /// Direction the list should scroll for a touch at `touchY`: -1 toward the
    /// top, +1 toward the bottom, 0 if the touch is comfortably inside the page
    /// or we're already at the content extreme in that direction.
    private func selectionAutoScrollDirection(forTouchY touchY: CGFloat) -> CGFloat {
        guard bounds.height > 0 else { return 0 }
        let topThreshold = bounds.minY + Self.selectionAutoScrollEdgeInset
        let bottomThreshold = bounds.maxY - Self.selectionAutoScrollEdgeInset
        if touchY < topThreshold {
            if contentOffset.y <= -adjustedContentInset.top + 0.5 { return 0 }
            return -1
        }
        if touchY > bottomThreshold {
            let maxOffset = max(-adjustedContentInset.top,
                                contentSize.height - bounds.height + adjustedContentInset.bottom)
            if contentOffset.y >= maxOffset - 0.5 { return 0 }
            return 1
        }
        return 0
    }

    @objc private func stepSelectionAutoScroll() {
        // Drive entirely off the last live touch point — no dependency on
        // selection-change events, so holding still at the edge keeps scrolling.
        guard hasActiveTextSelection, let touchPoint = selectionDragTouchPoint else {
            stopSelectionAutoScroll(); return
        }
        let dir = selectionAutoScrollDirection(forTouchY: touchPoint.y)
        guard dir != 0 else { stopSelectionAutoScroll(); return }
        let maxOffset = max(-adjustedContentInset.top,
                            contentSize.height - bounds.height + adjustedContentInset.bottom)
        let proposed = contentOffset.y + dir * Self.selectionAutoScrollStep
        let clamped = min(max(proposed, -adjustedContentInset.top), maxOffset)
        // [T-ios-selection-autoscroll-edge-flicker] Already at the content
        // extreme — stop rather than re-setting the same offset every frame
        // (which, paired with the selection re-extend below, was a source of
        // edge flicker when the user held past the end of the content).
        guard abs(clamped - contentOffset.y) > 0.5 else { stopSelectionAutoScroll(); return }
        // Non-animated, direct offset move so it tracks the 60Hz drag without a
        // competing CA animation (which would jitter against the handle).
        setContentOffset(CGPoint(x: contentOffset.x, y: clamped), animated: false)
        // Nudge the selection to follow the newly-scrolled-in content: extend
        // the selection to the document position now under the held touch, so
        // the user sees the range grow while the list scrolls. Done by the text
        // view (it owns the text geometry) via the callback below. Pass the
        // full point so the text view uses the REAL touch X (matching UIKit's
        // own caret) instead of a mid-X guess.
        onAutoScrollStep?(touchPoint)
    }

    /// Set by the dragging text view: given the live touch point (in this
    /// collection view's space), extend the selection to the document position
    /// now under the touch after an auto-scroll step. Cleared when the drag ends.
    var onAutoScrollStep: ((CGPoint) -> Void)?
}

// MARK: - MessageListViewController

final class MessageListViewController: UIViewController {
    var collectionView: UICollectionView!
    var messageListLayout: MessageListLayout!

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear

        messageListLayout = MessageListLayout()
        messageListLayout.itemSpacing = 8
        let cv = NoAnimationCollectionView(frame: view.bounds, collectionViewLayout: messageListLayout)
        cv.owningViewController = self
        collectionView = cv
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .clear
        collectionView.keyboardDismissMode = .onDrag
        collectionView.alwaysBounceVertical = true
        collectionView.contentInsetAdjustmentBehavior = .automatic
        view.addSubview(collectionView)
    }

    /// [RotationFix] Rotation handler — without this, on rotation the
    /// collection view resizes to the new width but its dequeued cells stay
    /// at the previous-orientation width. Off-screen cells (including any
    /// cell hosting a TableAttachment) never get re-sized, so when the user
    /// scrolls them back into view the SelectableMarkdownTextView is hosted
    /// at the old textContainer width and the rendered table frame stays
    /// at the pre-rotation column widths. Invalidating the collection view
    /// layout forces every cell to be re-measured at the new width during
    /// the transition.
    override func viewWillTransition(to size: CGSize, with coordinator: any UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: { [weak self] _ in
            guard let self else { return }
            self.messageListLayout?.invalidateLayout()
            self.collectionView?.layoutIfNeeded()
        }, completion: { [weak self] _ in
            // After the transition completes, re-measure the visible cells at
            // the new width so the user doesn't scroll into a wall of stale /
            // empty height caches.
            self?.prewarmVisibleCellsAfterWidthChange()
        })
    }

    /// Re-measure every currently-visible cell at the collection view's current
    /// width and warm the layout's height cache, so a subsequent scroll doesn't
    /// trigger a synchronous TextKit self-size per cell as it appears.
    ///
    /// Called from two width-change paths that both leave the height caches
    /// stale/empty:
    ///   1. Device / size-class rotation — `viewWillTransition`'s completion.
    ///   2. Sidebar collapse / expand (and any other NavigationSplitView detail
    ///      column-width change) — `NoAnimationCollectionView.layoutSubviews`
    ///      detects the bounds-width change and schedules this. That path has
    ///      no UIKit transition coordinator, so before this hook the cells were
    ///      only re-measured lazily as the user scrolled them in — the source
    ///      of the iPad rotate→portrait→collapse scroll jank
    ///      (T-ios-ipad-rotate-collapse-scroll-jank, GitHub #31 / #570).
    ///
    /// Reuses the existing measurement stack (TableAttachment column-width
    /// invalidation + SelfSizingCell self-size via `invalidateLayout`); it does
    /// not introduce a second measurement path. No-op cost beyond what the next
    /// scroll would have paid anyway — it just front-loads it to the moment the
    /// width settles instead of mid-scroll.
    func prewarmVisibleCellsAfterWidthChange() {
        guard let cv = collectionView else { return }
        for cell in cv.visibleCells {
            // Drop the per-cell cached height so preferredLayoutAttributesFitting
            // re-measures at the new width instead of returning the stale
            // pre-width-change value (the layout's heightCache was already
            // cleared by shouldInvalidateLayout(forBoundsChange:), but the
            // SelfSizingCell's own lastComputedHeight survives until reuse).
            (cell as? SelfSizingCell)?.clearCachedHeight()
            // Recompute TableAttachment columns at the new width (same reason
            // as rotation: cached column widths are keyed to the old width).
            _ = Self.invalidateTableAttachmentsInSubviews(of: cell)
        }
        // Trigger a fresh self-sizing pass so the visible cells re-measure now,
        // while the user is not yet scrolling.
        messageListLayout?.invalidateLayout()
    }

    /// Recursively walks `view`'s subview tree to find every
    /// SelectableMarkdownTextView, then invalidates each TableAttachment in
    /// its text storage so the column-width cache (keyed by portrait width)
    /// is dropped. Returns the number of TableAttachments reset.
    private static func invalidateTableAttachmentsInSubviews(of view: UIView) -> Int {
        var count = 0
        if let tv = view as? SelectableMarkdownTextView, let storage = tv.textStorage as? NSTextStorage, storage.length > 0 {
            storage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: storage.length), options: []) { value, range, _ in
                if let table = value as? TableAttachment {
                    table.invalidateCachedLayoutForWidthChange()
                    count += 1
                    if let lm = tv.layoutManager as? MinisLayoutManager {
                        lm.invalidateLayout(forCharacterRange: range, actualCharacterRange: nil)
                    }
                }
            }
            tv.setNeedsLayout()
            tv.invalidateIntrinsicContentSize()
        }
        for sub in view.subviews {
            count += invalidateTableAttachmentsInSubviews(of: sub)
        }
        return count
    }
}

// MARK: - Cell State Bridge

/// Per-message bridge. Shared across header/blocks/footer of the same message.
/// Name is kept as `CellStateBridgeV2` for historical reasons — V3 still uses it
/// verbatim as the ObservedObject across its bridged cell views.
final class CellStateBridgeV2: ObservableObject {
    @Published var isActiveMessage: Bool = false
    @Published var commandStartTime: Date?
    @Published var onStop: (() -> Void)?
    @Published var onRetry: (() -> Void)?
    @Published var onEdit: (() -> Void)?
    @Published var onDeleteFrom: (() -> Void)?
    @Published var onWithdraw: (() -> Void)?
    @Published var autoRetryAttempt: Int = 0
    @Published var autoRetryCountdown: Int = 0
    @Published var canResume: Bool = false
    @Published var onResume: (() -> Void)?
    @Published var onCompact: (() -> Void)?
    @Published var onForceSync: (() -> Void)?
    @Published var onCopyScreenshot: (() -> Void)?
    /// Read this whole reply aloud from the start (clears in-progress TTS).
    @Published var onReadAloud: (() -> Void)?
    /// [T-selection-menu-minis-tts] Speak an arbitrary text snippet (the
    /// selection-menu "Read Selection" action) via the Minis TTS stack.
    @Published var onSpeakText: ((String) -> Void)?
    /// True while this reply is still streaming — disables "Read from Start".
    @Published var isStreaming: Bool = false
    @Published var browserPool: BrowserTabPool?
    @Published var toolSnapshots: [ToolSnapshotItem] = []
    /// Tool detail sheet — owned by footer, triggered by block cells.
    @Published var detailBlock: AssistantBlock?
    /// Token usage visibility — toggled by double-tap on block cells, read by footer.
    @Published var showUsage: Bool = false
    @Published var usageContentVisible: Bool = false
    /// Compact summary — presented from overlay outside cell tree for animation.
    var onShowCompactSummary: ((String) -> Void)?
}
