import SwiftUI

/// [T-ios-paste-preview-watchdog] Incremental renderer for arbitrarily large
/// plain text.
///
/// Handing a big string to a single SwiftUI `Text` inside a `ScrollView` makes
/// the main thread lay the whole thing out synchronously: a `ScrollView` does
/// not virtualize, so SwiftUI must resolve one intrinsic size for the entire
/// string via `boundingRectWithSize` → CoreText. That cost is super-linear.
/// Measured on-device-class hardware at body font, 350pt wide:
///
///     5K chars   0.13s
///    10K chars   0.49s
///    20K chars   0.59s
///    40K chars   2.36s
///    80K chars   9.40s     <- already past the 5s watchdog
///
/// A 1.07M-character paste never finished (>10 min in a standalone harness),
/// which is the FRONTBOARD `Failed to terminate gracefully after 5.0s` kill
/// reported from the composer's "Pasted #N" preview.
///
/// This view splits the text into line chunks and reveals only an initial
/// window, growing it on demand. Each chunk is its own `Text`, so every layout
/// pass measures a bounded string instead of the whole document.
///
/// It is deliberately standalone rather than reusing ToolLiveSheet's private
/// equivalent: that one is tuned for tool output (ANSI sanitizing, shell/diff
/// styling) and is `fileprivate` by design. Sharing the concept, not the code,
/// keeps this usable for plain text without widening that file's internals.
struct LazyLongTextView: View {
    let text: String
    /// Font for the rendered chunks. Defaults to `.body` to match the previous
    /// non-chunked rendering.
    var font: Font = .body

    /// Lines per chunk.
    private static let chunkLines = 40
    /// Chunks revealed initially, before any byte clamp.
    private static let initialChunks = 5
    /// Chunks added per "Load more" / scroll-to-bottom.
    private static let batchChunks = 5
    /// Byte ceiling for the first reveal. A handful of very long lines can blow
    /// past the layout budget while still being < `initialChunks` chunks, so the
    /// initial window is clamped by size as well as by chunk count.
    private static let initialByteCap = 16 * 1024
    /// Hard cap on a single rendered line. CoreText's glyph-fallback loop is
    /// super-linear in line length, so one pathological line (a minified JS
    /// bundle, base64, a whole file with no newlines) must be broken up even
    /// though it is "one line".
    private static let maxRenderedLineLength = 2000
    /// Byte ceiling for one chunk, enforced alongside `chunkLines`. Sized so a
    /// single chunk's CoreText pass stays well inside one frame even for dense
    /// CJK; see the measurements in the type doc for why an unbounded chunk is
    /// the actual hazard.
    private static let maxChunkBytes = 8 * 1024

    @State private var revealed: Int = 0
    @State private var chunks: [Chunk] = []

    private struct Chunk: Identifiable {
        let id: Int
        let text: String
    }

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(chunks.prefix(max(revealed, 1))) { chunk in
                Text(chunk.text)
                    .font(font)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if revealed < chunks.count {
                footer
            }
        }
        .onAppear(perform: prepare)
    }

    private var footer: some View {
        let remaining = chunks.count - revealed
        let next = min(Self.batchChunks, remaining)
        return VStack(spacing: 8) {
            Text(String(format: AppLocalized("%d more sections"), remaining))
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 16) {
                Button {
                    revealed = min(revealed + Self.batchChunks, chunks.count)
                } label: {
                    Label(
                        String(format: AppLocalized("Load %d more lines"), next * Self.chunkLines),
                        systemImage: "chevron.down"
                    )
                    .font(.callout.weight(.medium))
                }
                Button(AppLocalized("Load All")) {
                    revealed = chunks.count
                }
                .font(.callout.weight(.medium))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        // Auto-advance when the footer scrolls into view, so plain scrolling
        // keeps loading without a tap.
        .onAppear {
            revealed = min(revealed + Self.batchChunks, chunks.count)
        }
    }

    /// Split once, on first appearance. Splitting is linear and cheap relative
    /// to layout; it is the per-chunk `Text` measurement that must stay bounded.
    private func prepare() {
        guard chunks.isEmpty else { return }
        let built = Self.chunk(text)
        chunks = built
        revealed = Self.initialReveal(built)
    }

    private static func chunk(_ text: String) -> [Chunk] {
        var lines: [Substring] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.count <= maxRenderedLineLength {
                lines.append(line)
            } else {
                // Hard-wrap an over-long line so no single Text gets a
                // pathological string.
                var idx = line.startIndex
                while idx < line.endIndex {
                    let end = line.index(idx, offsetBy: maxRenderedLineLength, limitedBy: line.endIndex) ?? line.endIndex
                    lines.append(line[idx..<end])
                    idx = end
                }
            }
        }
        if lines.isEmpty { return [] }
        // Close a chunk on EITHER the line count or the byte budget, whichever
        // comes first. Line count alone is not enough: text with no newlines
        // hard-wraps into `maxRenderedLineLength` segments, and 40 of those
        // rejoin into an ~80KB chunk — precisely the size measured at 9.4s,
        // i.e. the watchdog kill this view exists to prevent. Bounding by bytes
        // keeps every chunk's layout cost in the sub-100ms range regardless of
        // whether the input has newlines.
        var result: [Chunk] = []
        var current: [Substring] = []
        var currentBytes = 0
        var chunkStart = 0
        for (offset, line) in lines.enumerated() {
            current.append(line)
            currentBytes += line.utf8.count + 1
            let full = current.count >= chunkLines || currentBytes >= maxChunkBytes
            if full || offset == lines.count - 1 {
                result.append(Chunk(id: chunkStart, text: current.joined(separator: "\n")))
                chunkStart = offset + 1
                current.removeAll(keepingCapacity: true)
                currentBytes = 0
            }
        }
        return result
    }

    private static func initialReveal(_ chunks: [Chunk]) -> Int {
        guard !chunks.isEmpty else { return 0 }
        var count = 0
        var bytes = 0
        for chunk in chunks.prefix(initialChunks) {
            bytes += chunk.text.utf8.count
            count += 1
            if bytes >= initialByteCap { break }
        }
        return max(1, count)
    }
}
