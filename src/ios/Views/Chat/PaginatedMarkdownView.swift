//
//  PaginatedMarkdownView.swift
//  MinisApp
//
//  Renders a large markdown document by splitting it into segments at
//  paragraph boundaries and laying each segment out as a separate
//  `SelectableMarkdownView` inside a `LazyVStack`. The motivation is the
//  CoreAnimation `CA::Render::Encoder::grow` abort that fires when the
//  TextKit-backed `UITextView` produced by a single-shot
//  `SelectableMarkdownView` exceeds CALayer's max backing-store size
//  (observed on a 1.46M-char document: the laid-out text container
//  reached 408 × 1,641,062 pt and the CATransaction commit aborted).
//
//  - Documents ≤ the effective threshold render via the existing single
//    `SelectableMarkdownView` path (no overhead for normal-sized notes).
//    The threshold is 50K chars, dropping to 20K for CJK-heavy documents
//    (see `effectiveThreshold(for:)` — CJK costs far more per character
//    to lay out, which is what produced the 0x8BADF00D watchdog kill).
//  - Documents above it are split at `\n\n` paragraph
//    boundaries, fence-aware (`` ``` `` / `~~~` blocks stay intact), and
//    each segment becomes its own SwiftUI row. LazyVStack drops
//    off-screen rows so the in-memory CALayer total never exceeds a few
//    screens worth of TextKit storage.
//
//  Task: T-markdown-preview-paginate
//

import SwiftUI
import UIKit

/// SwiftUI wrapper that paginates oversized markdown documents.
///
/// Behavior:
///   - `markdown.count <= paginationThreshold` → single-shot
///     `SelectableMarkdownView` inside a `ScrollView`. No segmentation
///     overhead.
///   - Otherwise → split into segments and render via `LazyVStack`.
///
/// Splitting runs once, on a background task during `.task`, so the
/// caller doesn't pay it on the main thread.
struct PaginatedMarkdownView: View {
    let markdown: String

    /// Above this character count the document is paginated. Tuned to
    /// stay comfortably below CALayer's per-view backing-store ceiling
    /// (laid-out text containers above ~16K pt tall start losing
    /// pixels; the reported crash happened at 1.6M pt). 50K chars at
    /// the chat preview's font size is roughly 30 screens — plenty of
    /// signal that pagination is needed.
    static let paginationThreshold: Int = 50_000

    /// [T-ios-cjk-preview-watchdog] Lower threshold for CJK-heavy documents.
    ///
    /// The 50K figure above is a CHARACTER count, but characters are not
    /// equal in layout cost. CoreText runs a per-character script/spacing
    /// pass for CJK (`CJKCompositionEngine::GetCharacterClass`,
    /// `TCompositionEngine::AdjustSpacing`), which is markedly more
    /// expensive than Latin shaping. A 40-50K Chinese document therefore
    /// sits just under the limit yet costs far more to measure than a
    /// Latin document of the same length.
    ///
    /// That is the 0x8BADF00D scene-update watchdog kill reported on
    /// 2026-08-20 (crash 6B7E545B): a preview sheet re-laying out in the
    /// BACKGROUND, where the app gets only ~17% CPU, blew past the 10s
    /// wall-clock allowance inside exactly that CJK measurement path.
    static let cjkPaginationThreshold: Int = 20_000

    /// CJK share above which `cjkPaginationThreshold` applies.
    static let cjkRatioTrigger: Double = 0.30

    /// The effective threshold for `text`, lowered when the document is
    /// CJK-heavy. Both decision sites below MUST use this same value —
    /// if the body and the `.task` splitter disagreed, a document between
    /// the two thresholds would render the "Preparing…" spinner forever
    /// (the body would wait for `segments`, the task would return early
    /// without ever producing them).
    static func effectiveThreshold(for text: String) -> Int {
        cjkRatio(of: text) > cjkRatioTrigger ? cjkPaginationThreshold : paginationThreshold
    }

    /// Fraction of `text` that is CJK, counted over the whole string.
    ///
    /// Covers the ranges that actually drive the expensive CoreText path:
    /// CJK Unified Ideographs (+ Extension A), the CJK Symbols and
    /// Punctuation block (、。「」etc. — these participate in the same
    /// spacing-adjustment pass, so counting them keeps a punctuation-dense
    /// Chinese document from scoring artificially low), Hiragana, Katakana,
    /// Hangul syllables, and the fullwidth forms block (fullwidth commas
    /// and parens are common in Chinese text).
    ///
    /// Deliberately NOT normalised against "letters only": the ratio is
    /// over every character including spaces and markdown syntax, which is
    /// what makes 0.30 a meaningful trigger for prose that is mostly CJK
    /// but carries the usual markdown scaffolding.
    static func cjkRatio(of text: String) -> Double {
        guard !text.isEmpty else { return 0 }
        var cjk = 0
        var total = 0
        for scalar in text.unicodeScalars {
            total += 1
            let v = scalar.value
            if (0x4E00...0x9FFF).contains(v)      // CJK Unified Ideographs
                || (0x3400...0x4DBF).contains(v)  // …Extension A
                || (0x3000...0x303F).contains(v)  // CJK Symbols and Punctuation
                || (0x3040...0x309F).contains(v)  // Hiragana
                || (0x30A0...0x30FF).contains(v)  // Katakana
                || (0xAC00...0xD7AF).contains(v)  // Hangul Syllables
                || (0xFF00...0xFFEF).contains(v)  // Halfwidth and Fullwidth Forms
            {
                cjk += 1
            }
        }
        return total == 0 ? 0 : Double(cjk) / Double(total)
    }

    /// Target per-segment character count. ~10K is "about one screen
    /// for a dense markdown doc" so the user only sees one or two
    /// segments inflated at any time during scroll.
    static let segmentTargetSize: Int = 10_000

    @State private var segments: [Segment]? = nil

    var body: some View {
        Group {
            if markdown.count <= Self.effectiveThreshold(for: markdown) {
                // Fast path: short documents render the same way they
                // always have — one `SelectableMarkdownView` inside a
                // plain ScrollView.
                ScrollView {
                    SelectableMarkdownView(markdown: markdown)
                        .padding()
                }
            } else if let segments {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(segments) { segment in
                            SelectableMarkdownView(markdown: segment.text)
                                .padding(.horizontal)
                                .padding(.vertical, 4)
                        }
                    }
                }
            } else {
                // Splitting in progress.
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Preparing large document…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: markdown) {
            // [T-ios-cjk-preview-watchdog] Which path this document took, and
            // the inputs that decided it. Emitted from `.task` (once per
            // document) rather than `body`, which re-evaluates on every layout
            // pass and would flood the log. This is what makes the
            // single-shot vs paginated split observable on device — the view
            // otherwise renders identically either way.
            let threshold = Self.effectiveThreshold(for: markdown)
            let ratio = Self.cjkRatio(of: markdown)
            AppLogger(category: "MarkdownPreview").info(
                "[Paginate] chars=\(markdown.count) cjk=\(String(format: "%.0f%%", ratio * 100)) "
                + "threshold=\(threshold) → \(markdown.count > threshold ? "PAGINATED" : "single-shot")"
            )
            guard markdown.count > threshold else { return }
            let target = Self.segmentTargetSize
            let split = await Task.detached(priority: .userInitiated) {
                splitMarkdownIntoSegments(markdown, targetSize: target)
                    .enumerated()
                    .map { Segment(index: $0.offset, text: $0.element) }
            }.value
            await MainActor.run {
                self.segments = split
            }
        }
    }

    /// One paginated chunk. `index` doubles as the stable identity for
    /// `ForEach` / LazyVStack reuse.
    struct Segment: Identifiable, Hashable {
        let index: Int
        let text: String
        var id: Int { index }
    }
}

// MARK: - Splitter

/// Split markdown into segments at paragraph boundaries (`\n\n`),
/// keeping fenced code blocks (`` ``` `` / `~~~`) intact even if the
/// fence spans more than `targetSize` characters. Each returned
/// segment is independently parseable by cmark.
///
/// Rules:
///   - Track whether we're inside a fenced code block; never cut
///     mid-fence.
///   - When we hit a blank line outside a fence AND the buffered
///     content is ≥ `targetSize` chars, flush the buffer as a segment.
///   - Empty / whitespace-only segments are dropped so a long run of
///     blank lines doesn't produce phantom rows.
///
/// Time complexity: O(n) over the input. No regex, single pass.
func splitMarkdownIntoSegments(_ markdown: String, targetSize: Int) -> [String] {
    guard markdown.count > targetSize else { return [markdown] }
    let lines = markdown.split(separator: "\n", omittingEmptySubsequences: false)
    var segments: [String] = []
    var current: [Substring] = []
    var currentLen = 0
    var inFence = false
    var fenceMarker = ""
    var sawBlankSinceContent = false

    func flush() {
        guard !current.isEmpty else { return }
        var joined = current.joined(separator: "\n")
        while joined.hasSuffix("\n") { joined.removeLast() }
        let trimmed = joined.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            segments.append(joined)
        }
        current = []
        currentLen = 0
        sawBlankSinceContent = false
    }

    for line in lines {
        let lineStr = String(line)
        let stripped = lineStr.drop(while: { $0 == " " || $0 == "\t" })
        if !inFence, (stripped.hasPrefix("```") || stripped.hasPrefix("~~~")) {
            inFence = true
            fenceMarker = String(stripped.prefix(3))
        } else if inFence,
                  stripped.hasPrefix(fenceMarker),
                  stripped.drop(while: { String($0) == String(fenceMarker.first!) })
                      .allSatisfy({ $0 == " " || $0 == "\t" }) {
            inFence = false
        }
        current.append(line)
        currentLen += lineStr.count + 1
        let isBlank = lineStr.allSatisfy { $0 == " " || $0 == "\t" }
        if isBlank && !inFence && !sawBlankSinceContent && currentLen >= targetSize {
            flush()
            continue
        }
        if isBlank {
            sawBlankSinceContent = true
        } else {
            sawBlankSinceContent = false
        }
    }
    flush()
    return segments
}
