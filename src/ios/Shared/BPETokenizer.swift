import Foundation
import UIKit
import os.log

/// Singleton BPE tokenizer using cl100k_base vocabulary.
/// Ported from tiktoken — performs exact BPE token counting.
final class BPETokenizer: @unchecked Sendable {
    static let shared = BPETokenizer()

    private let logger = AppLogger(category: "BPETokenizer")

    /// byte sequence → rank
    private var encoder: [[UInt8]: Int] = [:]
    private var isLoaded = false
    private let lock = NSLock()

    // cl100k_base special token count (for overhead estimation)
    private static let tokensPerMessage = 3 // every message has <|start|>{role}\n and \n
    private static let tokensPerReply = 3   // every reply is primed with <|start|>assistant<|message|>

    private init() {}

    // MARK: - Public API

    /// Count tokens in a text string using BPE encoding.
    /// First call lazy-loads the vocabulary from bundle (~100ms).
    func countTokens(_ text: String) -> Int {
        ensureLoaded()
        let tokens = encode(text)
        return tokens.count
    }

    /// Count tokens for an AgentContentPart.
    /// Estimate image tokens from raw image data by decoding pixel dimensions.
    /// Uses a unified 32×32 = 1 token rule: `ceil(w/32) * ceil(h/32)`.
    /// Falls back to 1,000 tokens if dimensions cannot be determined.
    func countImageTokens(_ data: Data) -> Int {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else {
            return 1_000 // conservative fallback
        }
        let w = image.size.width * image.scale
        let h = image.size.height * image.scale
        // Cap at 2048×2048 (providers resize larger images)
        let maxEdge: CGFloat = 2048
        let scale: CGFloat = (max(w, h) > maxEdge) ? maxEdge / max(w, h) : 1.0
        let sw = w * scale
        let sh = h * scale
        let tokens = Int(ceil(sw / 32.0) * ceil(sh / 32.0))
        return max(85, tokens) // minimum 85 tokens
    }

    func countPartTokens(_ part: AgentContentPart) -> Int {
        switch part {
        case .text(let text):
            return countTokens(text)
        case .toolUse(_, let name, let input, _):
            // tool_use: name + JSON-serialized input
            var total = countTokens(name)
            if let data = try? JSONSerialization.data(withJSONObject: input),
               let json = String(data: data, encoding: .utf8) {
                total += countTokens(json)
            }
            return total + 4 // overhead for tool_use structure
        case .toolResult(_, let name, let content, _, let imageData, _, _, _, _):
            var total = countTokens(name) + countTokens(content)
            if let imgData = imageData {
                total += countImageTokens(imgData)
            }
            return total + 4 // overhead for tool_result structure
        case .imageData(let data, _, _):
            return countImageTokens(data)
        }
    }

    // MARK: - Vocabulary Loading

    private func ensureLoaded() {
        lock.lock()
        defer { lock.unlock() }
        guard !isLoaded else { return }

        let start = CFAbsoluteTimeGetCurrent()

        guard let url = Bundle.main.url(forResource: "cl100k_base", withExtension: "tiktoken") else {
            logger.error("cl100k_base.tiktoken not found in bundle")
            return
        }

        guard let data = try? String(contentsOf: url, encoding: .utf8) else {
            logger.error("Failed to read cl100k_base.tiktoken")
            return
        }

        var dict = [[UInt8]: Int]()
        dict.reserveCapacity(100_300)

        for line in data.split(separator: "\n") {
            let parts = line.split(separator: " ", maxSplits: 1)
            guard parts.count == 2,
                  let decoded = Data(base64Encoded: String(parts[0])),
                  let rank = Int(parts[1]) else { continue }
            dict[Array(decoded)] = rank
        }

        encoder = dict
        isLoaded = true

        let elapsed = (CFAbsoluteTimeGetCurrent() - start) * 1000
        logger.info("BPE vocabulary loaded: \(dict.count) entries in \(String(format: "%.1f", elapsed))ms")
    }

    // MARK: - BPE Encoding (cl100k_base)

    /// Encode text into BPE token IDs.
    private func encode(_ text: String) -> [Int] {
        guard !text.isEmpty else { return [] }

        // cl100k_base regex pattern (simplified — split on whitespace boundaries and punctuation)
        let pieces = splitIntoPieces(text)
        var tokens = [Int]()
        tokens.reserveCapacity(text.utf8.count / 3) // rough estimate

        for piece in pieces {
            let bytes = Array(piece.utf8)
            // Check if the whole piece is in the vocabulary
            if let rank = encoder[bytes] {
                tokens.append(rank)
                continue
            }
            // BPE merge
            let merged = bytePairEncode(bytes)
            tokens.append(contentsOf: merged)
        }

        return tokens
    }

    /// Split text into pieces matching cl100k_base's tokenization pattern.
    /// This approximates the tiktoken regex:
    /// (?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}{1,3}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+
    private func splitIntoPieces(_ text: String) -> [String] {
        // Use NSRegularExpression for the cl100k_base pattern
        let pattern = #"(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}{1,3}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"#

        guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else {
            // Fallback: return whole text as one piece
            return [text]
        }

        let nsText = text as NSString
        let range = NSRange(location: 0, length: nsText.length)
        let matches = regex.matches(in: text, options: [], range: range)

        return matches.map { nsText.substring(with: $0.range) }
    }

    /// Core BPE algorithm: merge byte pairs until no more merges possible.
    ///
    /// Performance: the previous implementation re-evaluated every adjacent
    /// pair on each outer iteration AND allocated a fresh `[UInt8]` for the
    /// dictionary key each time, producing O(N²) allocations. On large
    /// tool-result strings (>10 KB), `offloadContextIfNeeded` was hot enough
    /// to trip the 10s background scene-update watchdog (`swift_slowAlloc`
    /// in flame graphs). We now cache pair ranks in a parallel array and
    /// only refresh the (≤2) neighbors of each merge — same algorithm,
    /// vastly fewer lookups and allocations.
    private func bytePairEncode(_ bytes: [UInt8]) -> [Int] {
        guard bytes.count > 1 else {
            if let rank = encoder[bytes] {
                return [rank]
            }
            return [] // unknown single byte
        }

        // Each segment is a half-open [start, end) slice of `bytes`.
        // Initially every segment is a single byte.
        var segStart = [Int](unsafeUninitializedCapacity: bytes.count) { buf, count in
            for i in 0..<bytes.count { buf[i] = i }
            count = bytes.count
        }
        var segEnd = [Int](unsafeUninitializedCapacity: bytes.count) { buf, count in
            for i in 0..<bytes.count { buf[i] = i + 1 }
            count = bytes.count
        }

        // Reusable scratch buffer for dictionary lookups — avoids reallocating
        // a new [UInt8] per call. `Dictionary` still requires an Array key
        // (it cannot key on a slice), but reusing the same backing storage
        // means we only realloc when capacity grows.
        var scratch = [UInt8]()
        scratch.reserveCapacity(8)

        @inline(__always) func rankOf(_ i: Int) -> Int {
            // Look up rank for the pair formed by segments[i] + segments[i+1].
            // Returns Int.max for "no entry" so callers can use < comparison.
            scratch.removeAll(keepingCapacity: true)
            let lo = segStart[i]
            let hi = segEnd[i + 1]
            for k in lo..<hi { scratch.append(bytes[k]) }
            return encoder[scratch] ?? Int.max
        }

        // pairRank[i] = rank of pair (segment i, segment i+1). One slot
        // shorter than segments. Int.max means "no merge available".
        var pairRank: [Int] = []
        pairRank.reserveCapacity(bytes.count - 1)
        for i in 0..<(bytes.count - 1) {
            pairRank.append(rankOf(i))
        }

        var segCount = bytes.count
        while segCount > 1 {
            // Find the pair with the lowest rank in O(segCount).
            var minRank = Int.max
            var minIdx = -1
            for i in 0..<(segCount - 1) {
                if pairRank[i] < minRank {
                    minRank = pairRank[i]
                    minIdx = i
                }
            }
            if minIdx < 0 { break } // no more merges possible

            // Merge segments[minIdx] and segments[minIdx+1] in place: extend
            // the left, then shift everything past minIdx+1 down by one.
            segEnd[minIdx] = segEnd[minIdx + 1]
            for j in (minIdx + 1)..<(segCount - 1) {
                segStart[j] = segStart[j + 1]
                segEnd[j] = segEnd[j + 1]
            }
            segCount -= 1

            // Refresh pair ranks adjacent to the merge:
            //   - drop pairRank[minIdx + 1] (its right side is gone)
            //   - shift the tail of pairRank down to match
            //   - recompute pairRank[minIdx]   (new right neighbor)
            //   - recompute pairRank[minIdx-1] if it exists (new right neighbor)
            //
            // After a merge segCount may equal 1 (the entire input collapsed
            // to a single segment), in which case `segCount - 1 == 0` and the
            // shift range becomes (minIdx+1)..<0 — that's a Range precondition
            // failure. The outer `while segCount > 1` will exit on the next
            // iteration anyway, so skip the shift + neighbor refresh when
            // there's no longer any pair left to track.
            if segCount > 1 {
                if minIdx + 1 < segCount - 1 {
                    for j in (minIdx + 1)..<(segCount - 1) {
                        pairRank[j] = pairRank[j + 1]
                    }
                }
                // pairRank now logically has segCount - 1 valid entries.
                if minIdx < segCount - 1 {
                    pairRank[minIdx] = rankOf(minIdx)
                }
                if minIdx > 0 {
                    pairRank[minIdx - 1] = rankOf(minIdx - 1)
                }
            }
        }

        var tokens: [Int] = []
        tokens.reserveCapacity(segCount)
        for i in 0..<segCount {
            scratch.removeAll(keepingCapacity: true)
            for k in segStart[i]..<segEnd[i] { scratch.append(bytes[k]) }
            if let rank = encoder[scratch] { tokens.append(rank) }
        }
        return tokens
    }
}
