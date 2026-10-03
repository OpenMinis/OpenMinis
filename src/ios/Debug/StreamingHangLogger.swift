import Foundation

/// Bridges the existing `HangDetector` (ObjC, main-thread stack capture) to
/// the streaming lifecycle. While at least one streaming session is active,
/// `HangDetector` runs with a 1s threshold; hang events are drained to the
/// daily log every 2s along with a snapshot of the in-memory streaming
/// content buffer so we can see *what* was being rendered when the main
/// thread stalled.
///
/// Lifecycle is ref-counted: `acquire(reason:)` on stream start, `release`
/// on stream end. When the count hits zero the detector is stopped and a
/// final flush runs.
///
/// Available in both Debug and Release builds. The runtime cost when not
/// streaming is zero; while streaming it adds a single 50ms-polled monitor
/// thread + a ~6KB bounded ring buffer of recent flushes. See HangDetector.h
/// for the suspend/sample protocol used to capture main-thread frames.
final class StreamingHangLogger {

    static let shared = StreamingHangLogger()

    private let logger = AppLogger(category: "HangDetector")
    private let queue = DispatchQueue(label: "com.openminis.app.StreamingHangLogger")
    private var refCount: Int = 0
    private var flushTimer: DispatchSourceTimer?

    // [T-diag-log-noise] Retuned 2026-08-11. This is a backstop monitor, not a
    // profiler: it earns its keep by catching the rare severe stall in the
    // field, so it is tuned for signal per line rather than completeness.
    // Previous settings produced 888 [StreamHang] lines in a single day, each
    // hit emitting dozens of stack frames — enough volume to bury the events
    // actually worth reading (and to cost real I/O while streaming).

    /// Cap each event's frame list when writing to the log so a single deep
    /// stack doesn't bloat one line into multiple KB. 20 frames still reaches
    /// well past the app→framework boundary that identifies a stall's owner.
    private let maxFramesPerEvent: Int = 20
    /// Flush cadence. Raised from 2s: these are batched writes of an already
    /// bounded buffer, so a longer interval costs nothing but far fewer wakeups.
    private let flushIntervalSeconds: Int = 10
    /// `HangDetector.start` threshold — captures stacks for hangs >= this.
    /// Raised from 1000ms: sub-2s stalls are common enough during heavy
    /// streaming to be noise, while >= 2s is unambiguously user-visible.
    private let thresholdMs: Double = 2000

    // MARK: - Streaming content ring buffer
    //
    // We record the tail of every streaming text flush so that when a hang
    // is captured we can correlate the stack with what was on screen at the
    // moment of the stall. The buffer is bounded (last N entries) and each
    // entry's text is truncated to keep memory bounded under fast streaming
    // bursts.

    private struct StreamingSample {
        let timestamp: TimeInterval
        let messageIdShort: String
        let blockIdShort: String
        let totalLen: Int
        /// Tail of the text (last `streamTailChars` chars) at this flush.
        let tail: String
        /// `cacheAttributedString` flag from the flush (final-flush marker).
        let finalFlush: Bool
    }

    private var streamBuffer: [StreamingSample] = []
    private let streamBufferCap: Int = 200
    private let streamTailChars: Int = 200

    /// Bumped each time `recordStreamingFlush` is called within the current
    /// streaming window. Reset on `acquire`. Used for the dump header so we
    /// can tell whether 30 flushes or 3000 flushes happened before the hang.
    private var streamFlushCount: Int = 0

    // MARK: - Last assignedText snapshots per textView (REPRO-DIAG 2026-05-16)
    //
    // Ring buffer of the most recent N (msgId, markdown, attrLen, path,
    // timestamp) tuples passed through textView.attributedText assignment.
    // These are the actual inputs to the suspected hang site
    // (UIKit-internal _fillLayoutHole triggered by setAttributedString).
    //
    // Why a separate path from recordStreamingFlush:
    // - StreamingFlush captures *what the AI produced* — useful for
    //   correlating hangs with model output content.
    // - AssignedText captures *what we asked UIKit to render* — useful
    //   for narrowing the hang to a specific (msgId, markdown) pair.
    //   The two can diverge: prepareMarkdownForRender mutates markdown,
    //   and cell reuse can re-assign older content to a textView.
    private struct AssignedSample {
        let timestamp: TimeInterval
        let messageIdShort: String
        let markdown: String  // FULL markdown — bounded only by ring cap
        let attrLen: Int
        let path: String      // "fast-append" / "full-replace(prefix-mismatch)" / etc
    }
    private var assignedBuffer: [AssignedSample] = []
    // [HangRepro] User-requested: keep the last 10 chunk full payloads,
    // dumped to log when a main-thread hang fires so we can replay the
    // exact markdown that triggered the typesetter loop.
    private let assignedBufferCap: Int = 10

    // MARK: - UITextView inventory snapshot (probe C)
    //
    // TEMP(2026-05-13) — periodic snapshot of every UITextView mounted in
    // the app's key window subtree. The hang dumps so far show typesetter
    // running on a UITextView we couldn't attribute (no cell self-sizing
    // path involved). Take a snapshot every `inventoryIntervalSec` from the
    // main thread on a runloop tick; when a hang dump fires, include the
    // most recent snapshot so we can see what was on screen at stall time.

    private struct TextViewSample {
        let ptr: String
        let attrLen: Int
        let bounds: CGSize
        let isHidden: Bool
        let head: String
        // TEMP(2026-05-13 codex review): break attachment counts down by
        // class so the log directly proves how many tables / images / other
        // attachments the slow text view carries.
        let attachTotal: Int
        let attachTable: Int
        let attachImage: Int       // NSTextAttachment + others with .image set
        let attachOther: Int       // everything else (CodeBlock, Math, Video, …)
    }
    // TEMP(2026-05-14) — UICollectionView visible cells snapshot. Captured
    // alongside the UITextView inventory so a hang dump shows both
    // "what's mounted in the view tree" and "what UICollectionView
    // currently considers visible".
    private struct VisibleCellSample {
        let indexPath: String      // "section.row"
        let cellClass: String      // SelfSizingCell subclass name
        let bounds: CGSize
        let maxAttrLenInside: Int  // largest UITextView attrLen inside this cell
    }
    private struct InventorySnapshot {
        let timestamp: TimeInterval
        let totalTextViews: Int
        let top: [TextViewSample]                // top-N by attrLen
        let visibleCells: [VisibleCellSample]    // every visible UICollectionView cell
    }
    private var lastInventory: InventorySnapshot?
    private var inventoryTimer: DispatchSourceTimer?
    private let inventoryIntervalSec: Int = 2
    private let inventoryTopN: Int = 5

    private init() {}

    func acquire(reason: String) {
        queue.async {
            self.refCount += 1
            if self.refCount == 1 {
                let started = HangDetector.start(withThresholdMs: self.thresholdMs)
                self.streamBuffer.removeAll(keepingCapacity: true)
                self.streamFlushCount = 0
                // TEMP(2026-05-13): direct-write so start markers always
                // land, even if the next thing the main thread does is
                // hang. Remove with the rest of the TEMP block.
                LoggingManager.shared.writeRawLine(category: "HangDetector", level: "INFO", message: "[StreamHang] start reason=\(reason) started=\(started) thresholdMs=\(HangDetector.thresholdMs)")
                LoggingManager.shared.flush()
                self.startFlushTimerLocked()
                self.startInventoryTimerLocked()
            }
        }
    }

    func release(reason: String) {
        queue.async {
            self.refCount = max(0, self.refCount - 1)
            if self.refCount == 0 {
                self.flushEventsLocked(label: "final")
                HangDetector.stop()
                self.flushTimer?.cancel()
                self.flushTimer = nil
                self.inventoryTimer?.cancel()
                self.inventoryTimer = nil
                self.lastInventory = nil
                self.streamBuffer.removeAll(keepingCapacity: false)
                self.streamFlushCount = 0
                LoggingManager.shared.writeRawLine(category: "HangDetector", level: "INFO", message: "[StreamHang] stop reason=\(reason)")
                LoggingManager.shared.flush()
            }
        }
    }

    /// Schedule periodic UITextView inventory snapshots on the main thread.
    /// Called only on `queue`. When the main thread is hung the dispatched
    /// block simply doesn't run, so we just keep the previous snapshot —
    /// "what the view tree looked like right before the stall" is exactly
    /// the data we want anyway.
    private func startInventoryTimerLocked() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(inventoryIntervalSec),
                   repeating: .seconds(inventoryIntervalSec),
                   leeway: .milliseconds(500))
        t.setEventHandler { [weak self] in
            self?.captureInventoryAsync()
        }
        t.resume()
        inventoryTimer = t
    }

    private func captureInventoryAsync() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let snap = Self.snapshotTextViews(top: self.inventoryTopN)
            self.queue.async {
                self.lastInventory = snap
            }
        }
    }

    /// Walk every connected scene's key window subtree, sample each
    /// UITextView's attributedText length, and keep the top-N largest.
    /// Also collects all UICollectionView.visibleCells so a hang dump can
    /// see which message cells were on screen at stall time.
    private static func snapshotTextViews(top: Int) -> InventorySnapshot {
        var all: [TextViewSample] = []
        var visible: [VisibleCellSample] = []
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            for window in scene.windows {
                walkSubviews(window, into: &all)
                collectVisibleCells(window, into: &visible)
            }
        }
        all.sort { $0.attrLen > $1.attrLen }
        return InventorySnapshot(
            timestamp: Date().timeIntervalSince1970,
            totalTextViews: all.count,
            top: Array(all.prefix(top)),
            visibleCells: visible
        )
    }

    // TEMP(2026-05-14) — locate every UICollectionView in the view tree
    // and snapshot its `visibleCells` with indexPath + the largest
    // UITextView attrLen nested inside. Cheap: visibleCells is already
    // tracked by UIKit.
    private static func collectVisibleCells(_ view: UIView, into out: inout [VisibleCellSample]) {
        if let cv = view as? UICollectionView {
            for cell in cv.visibleCells {
                let ip = cv.indexPath(for: cell)
                let ipStr = ip.map { "\($0.section).\($0.item)" } ?? "?.?"
                var maxLen = 0
                walkForMaxAttrLen(cell, current: &maxLen)
                out.append(VisibleCellSample(
                    indexPath: ipStr,
                    cellClass: String(describing: type(of: cell)),
                    bounds: cell.bounds.size,
                    maxAttrLenInside: maxLen
                ))
            }
        }
        for sub in view.subviews { collectVisibleCells(sub, into: &out) }
    }

    private static func walkForMaxAttrLen(_ v: UIView, current: inout Int) {
        if let tv = v as? UITextView {
            let n = tv.textStorage.length
            if n > current { current = n }
        }
        for sub in v.subviews { walkForMaxAttrLen(sub, current: &current) }
    }

    private static func walkSubviews(_ view: UIView, into out: inout [TextViewSample]) {
        if let tv = view as? UITextView {
            let attrLen = tv.textStorage.length
            let head = attrLen > 0
                ? (tv.textStorage.string as NSString)
                    .substring(to: min(40, attrLen))
                    .replacingOccurrences(of: "\n", with: "⏎")
                : ""
            let ptr = String(format: "%p", Int(bitPattern: Unmanaged.passUnretained(tv).toOpaque()))
            // TEMP(2026-05-13 codex review): break down attachment counts by
            // class. Costs one enumeration over the attributedText but only
            // every 2 s — negligible.
            var totalAtt = 0, tableAtt = 0, imageAtt = 0, otherAtt = 0
            if attrLen > 0 {
                tv.textStorage.enumerateAttribute(
                    .attachment,
                    in: NSRange(location: 0, length: attrLen),
                    options: []
                ) { value, _, _ in
                    guard let v = value as? NSTextAttachment else { return }
                    totalAtt += 1
                    let cls = String(describing: type(of: v))
                    if cls.contains("Table") {
                        tableAtt += 1
                    } else if v.image != nil {
                        imageAtt += 1
                    } else {
                        otherAtt += 1
                    }
                }
            }
            out.append(TextViewSample(
                ptr: ptr,
                attrLen: attrLen,
                bounds: tv.bounds.size,
                isHidden: tv.isHidden || tv.alpha < 0.01,
                head: head,
                attachTotal: totalAtt,
                attachTable: tableAtt,
                attachImage: imageAtt,
                attachOther: otherAtt
            ))
        }
        for sub in view.subviews { walkSubviews(sub, into: &out) }
    }

    /// Record one streaming-text flush. Cheap: a substring + struct push on
    /// a background queue. Called from `AIChatViewModel.setStreamingTextBlockContent`.
    /// No-op when no streaming session is active.
    func recordStreamingFlush(
        messageId: UUID,
        blockId: UUID,
        text: String,
        finalFlush: Bool
    ) {
        // Snapshot the tail on the caller (main) so we don't retain the full
        // attributed text reference across the dispatch hop.
        let totalLen = text.count
        let tail: String = {
            if totalLen <= streamTailChars { return text }
            let startIdx = text.index(text.endIndex, offsetBy: -streamTailChars)
            return String(text[startIdx...])
        }()
        let now = Date().timeIntervalSince1970
        let mShort = String(messageId.uuidString.prefix(8))
        let bShort = String(blockId.uuidString.prefix(8))

        queue.async {
            guard self.refCount > 0 else { return }
            self.streamFlushCount &+= 1
            let sample = StreamingSample(
                timestamp: now,
                messageIdShort: mShort,
                blockIdShort: bShort,
                totalLen: totalLen,
                tail: tail,
                finalFlush: finalFlush
            )
            self.streamBuffer.append(sample)
            if self.streamBuffer.count > self.streamBufferCap {
                self.streamBuffer.removeFirst(self.streamBuffer.count - self.streamBufferCap)
            }
        }
    }

    /// REPRO-DIAG(2026-05-16): record the markdown that was just passed to
    /// `textView.attributedText = attributed` (or fast-append to its
    /// textStorage). Called from `SelectableMarkdownView.updateUIView`.
    ///
    /// Unlike `recordStreamingFlush`, this captures the **full markdown**
    /// (the actual input that triggered _fillLayoutHole), so when a hang
    /// fires we can reproduce it from the log. Bounded by `assignedBufferCap`
    /// entries so memory stays small even under stream bursts.
    func recordAssignedText(messageId: UUID?, markdown: String, attrLen: Int, path: String) {
        let mShort = messageId.map { String($0.uuidString.prefix(8)) } ?? "—"
        let now = Date().timeIntervalSince1970
        queue.async {
            let sample = AssignedSample(
                timestamp: now,
                messageIdShort: mShort,
                markdown: markdown,
                attrLen: attrLen,
                path: path
            )
            self.assignedBuffer.append(sample)
            if self.assignedBuffer.count > self.assignedBufferCap {
                self.assignedBuffer.removeFirst(self.assignedBuffer.count - self.assignedBufferCap)
            }
        }
    }

    private func startFlushTimerLocked() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(flushIntervalSeconds),
                   repeating: .seconds(flushIntervalSeconds),
                   leeway: .milliseconds(200))
        t.setEventHandler { [weak self] in
            self?.flushEventsLocked(label: "tick")
        }
        t.resume()
        flushTimer = t
    }

    /// Drain accumulated hang events from the detector. If any events fired,
    /// also emit a snapshot of the streaming content buffer (most recent
    /// `streamBufferCap` flushes) so we can correlate the stall with the
    /// content being rendered. Called only on `queue`.
    private func flushEventsLocked(label: String) {
        let events = HangDetector.dumpLast(0, clear: true)
        guard !events.isEmpty else { return }

        // TEMP(2026-05-13): use LoggingManager.writeRawLine instead of NSLog
        // so a watchdog SIGKILL can't strand the dump bytes in the kernel
        // pipe buffer. Remove once we confirm dumps reliably land on disk.
        let cat = "HangDetector"
        let lvl = "WARN"
        let mgr = LoggingManager.shared

        // REPRO-DIAG(2026-05-16): include the current diag round (set by
        // AIChatViewModel.runAgentLoop). Bridged via the same global hook
        // SelectableMarkdownView uses, avoiding a cross-module import.
        let _diagRound = AIChatViewModel.currentDiagRound()
        mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] 📸 \(label) round=\(_diagRound): \(events.count) main-thread hang event(s) captured (threshold=\(HangDetector.thresholdMs)ms) bufferedFlushes=\(self.streamBuffer.count)/\(self.streamFlushCount)")

        // TEMP(2026-05-13) — write the most recent UITextView inventory
        // snapshot before the stack frames so a log reader can see what
        // textviews were live (and how large) at stall time. When the main
        // thread has been hung for longer than the snapshot interval, this
        // is the snapshot from just *before* the stall started, which is
        // typically what we want.
        if let inv = self.lastInventory, !inv.top.isEmpty {
            let ageMs = (Date().timeIntervalSince1970 - inv.timestamp) * 1000
            mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] === UITextView inventory (totalTVs=\(inv.totalTextViews) snapshotAge=\(String(format: "%.0f", ageMs))ms top=\(inv.top.count)) ===")
            for (i, sample) in inv.top.enumerated() {
                let dims = String(format: "%.0fx%.0f", sample.bounds.width, sample.bounds.height)
                let hidden = sample.isHidden ? " HIDDEN" : ""
                mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang]   tv#\(i) ptr=\(sample.ptr) attrLen=\(sample.attrLen) bounds=\(dims)\(hidden) attach=\(sample.attachTotal)(table=\(sample.attachTable),image=\(sample.attachImage),other=\(sample.attachOther)) head=\"\(sample.head)\"")
            }
            // TEMP(2026-05-14) — UICollectionView visible cells.
            if !inv.visibleCells.isEmpty {
                mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] === visible cells (\(inv.visibleCells.count)) ===")
                for (i, vc) in inv.visibleCells.enumerated() {
                    let dims = String(format: "%.0fx%.0f", vc.bounds.width, vc.bounds.height)
                    mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang]   cell#\(i) idx=\(vc.indexPath) class=\(vc.cellClass) bounds=\(dims) maxAttrLenInside=\(vc.maxAttrLenInside)")
                }
            }
        } else {
            mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] === UITextView inventory: (no snapshot yet) ===")
        }

        // Emit the streaming content snapshot before the stacks so a log
        // reader sees the "what" before the "where". Each sample is one
        // line; the tail is single-line escaped.
        if !streamBuffer.isEmpty {
            mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] === streaming content snapshot (\(streamBuffer.count) flush(es), totalSeen=\(streamFlushCount)) ===")
            for (i, sample) in streamBuffer.enumerated() {
                let ts = String(format: "%.3f", sample.timestamp)
                let finalMark = sample.finalFlush ? "FINAL" : "incr"
                let escaped = Self.escapeForLog(sample.tail)
                mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang]   flush[\(i)] t=\(ts) msg=\(sample.messageIdShort) blk=\(sample.blockIdShort) len=\(sample.totalLen) \(finalMark) tail=\"\(escaped)\"")
            }
            // Clear so the next hang event reports only flushes that
            // happened since this dump.
            streamBuffer.removeAll(keepingCapacity: true)
        } else {
            mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] === streaming content snapshot: (empty) ===")
        }

        // REPRO-DIAG(2026-05-16): dump the FULL markdown of the last few
        // textView.attributedText assignments. This is the actual hang
        // input — UIKit's _fillLayoutHole runs over whatever this markdown
        // resolved to. Chunked across multiple log lines (800 char each)
        // so a single line stays readable in `tail -f`.
        if !assignedBuffer.isEmpty {
            mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] === assignedText snapshot (\(assignedBuffer.count) entries) ===")
            for (i, sample) in assignedBuffer.enumerated() {
                let ts = String(format: "%.3f", sample.timestamp)
                mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang]   assign[\(i)] t=\(ts) msg=\(sample.messageIdShort) attrLen=\(sample.attrLen) path=\(sample.path) mdLen=\(sample.markdown.count)")
                // Emit full markdown in 800-char slices with newlines escaped.
                let escaped = sample.markdown
                    .replacingOccurrences(of: "\n", with: "⏎")
                    .replacingOccurrences(of: "\t", with: "⇥")
                var idx = escaped.startIndex
                var sliceIdx = 0
                while idx < escaped.endIndex {
                    let end = escaped.index(idx, offsetBy: 800, limitedBy: escaped.endIndex) ?? escaped.endIndex
                    let slice = String(escaped[idx..<end])
                    mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang]   assign[\(i)] slice=\(sliceIdx) │\(slice)│")
                    idx = end
                    sliceIdx += 1
                }
            }
            assignedBuffer.removeAll(keepingCapacity: true)
        } else {
            mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] === assignedText snapshot: (empty) ===")
        }

        for (i, event) in events.enumerated() {
            let dur = String(format: "%.0f", event.durationMs)
            let ts = String(format: "%.3f", event.timestamp)
            let act = event.runLoopActivity
            mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang] event #\(i) duration=\(dur)ms timestamp=\(ts) runLoopActivity=\(act) frames=\(event.frames.count)")
            let limit = min(event.frames.count, maxFramesPerEvent)
            for j in 0..<limit {
                let frame = event.frames[j]
                let sym = frame.symbol ?? "??"
                let img = frame.imageName ?? "??"
                let off = String(format: "0x%lx", frame.imageOffset)
                mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang]   #\(i) [\(String(format: "%2d", j))] \(sym) + \(off)  (\(img))")
            }
            if event.frames.count > maxFramesPerEvent {
                mgr.writeRawLine(category: cat, level: lvl, message: "[StreamHang]   #\(i) … \(event.frames.count - maxFramesPerEvent) more frames elided")
            }
        }

        // TEMP(2026-05-13): fsync immediately so the dump is on disk before a
        // subsequent watchdog SIGKILL can drop it. Remove together with
        // writeRawLine once dumps are confirmed to land reliably.
        mgr.flush()

        persistHangSnapshotToDisk(events)
    }

    private func persistHangSnapshotToDisk(_ events: [HangEvent]) {
        var lines: [String] = []
        let df = DateFormatter()
        df.dateFormat = "HH:mm:ss.SSS"
        for (i, event) in events.enumerated() {
            let dur = String(format: "%.0f", event.durationMs)
            let ts = Date(timeIntervalSince1970: event.timestamp)
            lines.append("Hang #\(i): \(dur)ms at \(df.string(from: ts)) repeat=\(event.repeatCount)")
            let limit = min(event.frames.count, 20)
            for j in 0..<limit {
                let frame = event.frames[j]
                let sym = frame.symbol ?? ""
                let img = frame.imageName ?? "?"
                let off = String(format: "0x%lx", frame.imageOffset)
                lines.append("  #\(j) \(img) +\(off) \(sym)")
            }
        }
        let text = lines.joined(separator: "\n")
        try? text.write(to: CrashReporter.hangSnapshotURL, atomically: true, encoding: .utf8)
    }

    /// Replace CR/LF and other control chars with visible escapes so a tail
    /// stays single-line in the log file.
    private static func escapeForLog(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count + 8)
        for c in s {
            switch c {
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\"": out += "\\\""
            default:   out.append(c)
            }
        }
        return out
    }
}
