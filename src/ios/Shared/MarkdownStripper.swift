import Foundation

enum MarkdownStripper {

    // MARK: - Compiled patterns
    //
    // [T-ios-listsessions-perf] Every one of these used to be compiled on each
    // call, through `replacingOccurrences(of:options:.regularExpression)`. The
    // CPU Profiler trace of an 11-minute agent run attributed ~200 G cycles —
    // 23% of all time inside ChatStore.listSessions, itself 65% of the whole
    // process — to `uregex_open` + `RegexPattern::compile` + the
    // `stringWithFormat` that builds each pattern's internal description. None
    // of that is matching work; it is the same ten patterns being rebuilt for
    // every session on every sidebar refresh.
    //
    // NSRegularExpression is immutable and documented as thread-safe for
    // concurrent matching, so one compiled instance is shared by every caller.
    // Swift `static let` initialisation is itself lazy and once-only.
    //
    // Force-unwrapping is correct here: these are compile-time-constant
    // literals, so a failure is a programming error that would otherwise be
    // silently swallowed by the old `try?` into "return the text unstripped".

    private static let fenceRe = regex("```[\\s\\S]*?```")
    private static let imageRe = regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private static let linkRe = regex("!?\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)")
    private static let autolinkRe = regex("<(https?://[^>]+)>")

    /// Inline emphasis/code spans, applied in order. Paired with the template
    /// the old `replacingOccurrences` call passed.
    private static let inlineRes: [(re: NSRegularExpression, template: String)] = [
        (regex("\\*\\*([^*]+)\\*\\*"), "$1"),
        (regex("__([^_]+)__"), "$1"),
        (regex("\\*([^*]+)\\*"), "$1"),
        (regex("(?<!\\w)_([^_]+)_(?!\\w)"), "$1"),
        (regex("~~([^~]+)~~"), "$1"),
        (regex("`([^`]*)`"), "$1"),
    ]

    private static let headingRe = regex("^#{1,6}\\s+")
    private static let quoteRe = regex("^>\\s?")
    private static let bulletRe = regex("^[-*+]\\s+")
    private static let orderedRe = regex("^\\d+[.):]\\s+")
    private static let emphasisRunRe = regex("[*_~`]{2,}")
    private static let multiSpaceRe = regex("  +")

    private static func regex(_ pattern: String) -> NSRegularExpression {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: pattern)
    }

    /// Apply one compiled pattern over a whole string, matching the semantics
    /// of `replacingOccurrences(of:with:options:.regularExpression)` exactly:
    /// same template syntax (`$1`), same full-string range, same left-to-right
    /// non-overlapping match order.
    private static func replacingAll(
        _ s: String, _ re: NSRegularExpression, _ template: String
    ) -> String {
        let ns = s as NSString
        guard ns.length > 0 else { return s }
        return re.stringByReplacingMatches(
            in: s,
            range: NSRange(location: 0, length: ns.length),
            withTemplate: template
        )
    }

    /// Characters kept before the inline + per-line passes.
    ///
    /// [T-ios-listsessions-perf] The fence / image / link / autolink passes
    /// above must see the whole text, because each needs its closing marker to
    /// know what to remove. Everything after them is line-local work whose
    /// cost is linear in the text that remains — and the only caller that runs
    /// this in a hot loop (the sidebar preview) keeps just 100 characters. A
    /// cap here bounds the six inline regexes and the per-line pass to a fixed
    /// amount of work no matter how long the message is.
    ///
    /// 1024 rather than 512 (decision recorded in the task spec): it leaves
    /// ten times the kept preview length in hand, so text the per-line pass
    /// drops wholesale — table rows, separator rules, fence markers, blank
    /// lines — cannot starve the 100 surviving characters of real prose.
    ///
    /// Opt-in per call (`plainText(_:inlinePassCap:)`), NOT applied by
    /// default: plainText is a general utility with document-facing callers
    /// (the background-completion notification body builds its summary from
    /// it) that must get the whole stripped text back. Only the sidebar
    /// preview passes this.
    static let inlinePassCap = 1024

    /// Full markdown-to-plain-text conversion of `input`.
    ///
    /// Deliberately NOT size-capped by default: this is a general utility and
    /// must return the whole stripped text for its document-facing callers.
    /// Callers that only need a short excerpt of a potentially huge body (the
    /// session-list preview, which runs for every session on every refresh)
    /// must bound their input FIRST with `previewSource(_:maxLength:)` — see
    /// that function for why the bound cannot simply be a `prefix` in front of
    /// the regex passes — and may additionally pass `inlinePassCap` to bound
    /// the line-local passes.
    static func plainText(_ input: String, inlinePassCap: Int? = nil) -> String {
        var s = input

        s = replacingAll(s, fenceRe, " ")
        s = replacingAll(s, imageRe, "$1")
        s = rewriteLinks(s)
        s = replacingAll(s, autolinkRe, "$1")

        // See `inlinePassCap`. Character-indexed, so a grapheme is never split.
        if let cap = inlinePassCap, s.count > cap {
            s = String(s.prefix(cap))
        }

        for (re, template) in inlineRes {
            s = replacingAll(s, re, template)
        }

        let lines = s.components(separatedBy: "\n")
        let cleaned: [String] = lines.compactMap { raw in
            var line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { return nil }

            let sepStripped = line.trimmingCharacters(in: CharacterSet(charactersIn: "-=*_ "))
            if sepStripped.isEmpty { return nil }

            // Byte-level pipe tests: these run for every line of every
            // preview, and the Foundation `contains` path bridges to NSString
            // and rebuilds UTF-16 breadcrumbs each time (85 G in the trace).
            let bytes = Array(line.utf8)
            let hasPipe = bytes.contains(UInt8(ascii: "|"))
            if bytes.first == UInt8(ascii: "|") || (hasPipe && bytes.last == UInt8(ascii: "|")) {
                return nil
            }
            if hasPipe, bytes.allSatisfy({
                $0 == UInt8(ascii: "|") || $0 == UInt8(ascii: "-")
                    || $0 == UInt8(ascii: ":") || $0 == UInt8(ascii: " ")
            }) {
                return nil
            }

            if line.hasPrefix("```") || line.hasPrefix("~~~") { return nil }

            line = replacingAll(line, headingRe, "")
            line = replacingAll(line, quoteRe, "")
            line = replacingAll(line, bulletRe, "")
            line = replacingAll(line, orderedRe, "")
            line = replacingAll(line, emphasisRunRe, "")

            line = line.trimmingCharacters(in: .whitespaces)
            return line.isEmpty ? nil : line
        }

        var result = cleaned.joined(separator: " ")
        result = replacingAll(result, multiSpaceRe, " ")
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func rewriteLinks(_ text: String) -> String {
        let re = linkRe
        let ns = text as NSString
        var result = ""
        // The output is never longer than the input (every rewrite replaces a
        // link with a strictly shorter title/URL), so one reservation up front
        // removes the repeated grow-and-copy this loop's `+=` would otherwise
        // do — that reallocation is the frame the 1.14(11) allocation-failure
        // abort landed on.
        result.reserveCapacity(ns.length)
        var last = 0
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            result += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let title = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            let url   = ns.substring(with: m.range(at: 2))
            result += title.isEmpty ? url : title
            last = m.range.location + m.range.length
        }
        result += ns.substring(from: last)
        return result
    }

    // MARK: - Preview slicing

    /// Opaque regions a preview never shows. A region that starts inside the
    /// budget is skipped whole, however long it is, so the prose after it still
    /// reaches the preview — and so a region that would have straddled the cut
    /// never leaks its opening marker into the output. Order matters only when
    /// two openers start at the same index (they cannot, so it does not).
    ///
    /// Stored as UTF-8 byte arrays: every marker is pure ASCII, and the scan
    /// below runs on the input's `utf8` view to avoid the String→NSString
    /// bridge and the UTF-16 offset translation that `range(of:range:)` pays
    /// on each call (`_toUTF16Offsets` 46 G + `_StringBreadcrumbs` 33 G in the
    /// trace, for a loop that only ever looks for four ASCII literals).
    private static let opaqueRegions: [(open: [UInt8], close: [UInt8])] = [
        ("```", "```"),
        ("<system-reminder>", "</system-reminder>"),
        ("<user-attached-files>", "</user-attached-files>"),
    ].map { (Array($0.0.utf8), Array($0.1.utf8)) }

    /// Index of the first occurrence of `needle` in `haystack[from..<to]`, or
    /// nil. Plain forward scan: the needles are 3–21 bytes and the window is
    /// bounded by the preview budget, so the constant factor of a smarter
    /// algorithm would not pay for itself.
    private static func findUTF8(
        _ haystack: [UInt8], _ needle: [UInt8], from: Int, to: Int
    ) -> Int? {
        let n = needle.count
        guard n > 0, to - from >= n else { return nil }
        let first = needle[0]
        var i = from
        let limit = to - n
        while i <= limit {
            if haystack[i] == first {
                var k = 1
                while k < n, haystack[i + k] == needle[k] { k += 1 }
                if k == n { return i }
            }
            i += 1
        }
        return nil
    }

    /// True if `haystack`'s first `limit` bytes contain `needle`. ASCII needle
    /// only. Used for the hot `contains` checks that guard the preview path.
    ///
    /// Scans the `utf8` view in place rather than materialising an array: the
    /// callers include an unbounded check over whole message bodies, where a
    /// copy would cost more than the NSString bridge this replaces. Bails out
    /// as soon as `limit` bytes have been examined, so the bounded callers stay
    /// O(limit) on a multi-megabyte message.
    static func utf8Contains(_ haystack: String, _ needle: String, withinBytes limit: Int) -> Bool {
        let needleBytes = Array(needle.utf8)
        let n = needleBytes.count
        guard n > 0 else { return true }
        guard limit >= n else { return false }

        // Restart one byte after each failed candidate, so this is correct for
        // ANY needle. A single-cursor scan that resets to 0/1 on a mismatch
        // silently misses needles that overlap their own prefix (needle "aab"
        // in "aaab" — the third 'a' is consumed as a failed match of 'b' and
        // the real match is never tried). Today's needles happen not to
        // overlap, but a helper this general must not depend on that.
        // Worst case O(n·m) with m ≤ 21 bytes; no allocation on the native
        // String path (contiguous UTF-8), one bounded copy for bridged ones.
        func scan(_ buf: UnsafeBufferPointer<UInt8>) -> Bool {
            let end = min(buf.count, limit)
            guard end >= n else { return false }
            let first = needleBytes[0]
            let last = end - n
            var i = 0
            while i <= last {
                if buf[i] == first {
                    var k = 1
                    while k < n, buf[i + k] == needleBytes[k] { k += 1 }
                    if k == n { return true }
                }
                i += 1
            }
            return false
        }
        if let hit = haystack.utf8.withContiguousStorageIfAvailable(scan) { return hit }
        return Array(haystack.utf8.prefix(limit)).withUnsafeBufferPointer(scan)
    }

    /// The first `maxLength` characters of the prose in `input`, with fenced
    /// code and injected system blocks removed, suitable as the input to
    /// `plainText` when only a short preview is kept.
    ///
    /// [T-ios-markdown-preview-cap] Why this exists instead of a plain
    /// `prefix(maxLength)`: the 1.14(11) allocation-failure abort
    /// (ChatStore.listSessions → extractTextFromPartsJSON → plainText →
    /// rewriteLinks) was a background agent run re-deriving the sidebar
    /// preview of a multi-megabyte message once a second, each time through
    /// JSON decode, two marker strips and ~10 whole-string regex passes. The
    /// fix is to bound the text BEFORE any of those run — but a blind
    /// `prefix` cuts fences and reminder blocks in half, and the regexes that
    /// strip them need the closing marker, so the body of a long leading code
    /// block would become the preview instead of the answer after it.
    ///
    /// Cost: every search is confined to the remaining budget, so the prose
    /// part is O(maxLength) regardless of message size. Skipping an opaque
    /// region is a single forward search for its closer (no copy), so a huge
    /// fenced block costs one scan and no allocation. An unclosed region
    /// swallows the rest of the input — the same thing the caller's regex
    /// would have done had it been able to see the whole text.
    ///
    /// [T-ios-listsessions-perf] The scan runs on UTF-8 bytes, but the budget
    /// is still counted in Characters and every cut is Character-aligned: a
    /// byte offset is only ever turned back into a String index at a boundary
    /// the scan proved to be one (a marker start, a marker end, or a position
    /// reached by stepping whole Characters). A grapheme is therefore never
    /// split — the guarantee the existing emoji/CJK test pins down.
    static func previewSource(_ input: String, maxLength: Int = 4096) -> String {
        guard maxLength > 0 else { return "" }
        let bytes = Array(input.utf8)
        let endByte = bytes.count
        guard endByte > 0 else { return "" }

        var out = ""
        out.reserveCapacity(min(maxLength, 8192))
        var remaining = maxLength
        var cursorByte = 0
        // String index walked forward in lockstep with `cursorByte`, so slices
        // can be taken without re-decoding the prefix each time.
        var cursorIndex = input.startIndex

        while cursorByte < endByte, remaining > 0 {
            // Look for an opener only within the budget window: anything that
            // starts beyond it is dropped anyway, so scanning further would
            // make this O(input) for a message with no markers at all. The
            // window is `remaining` CHARACTERS, so walk that many Characters
            // to find its byte end — bounded by the budget, not the input.
            let windowEndIndex = input.index(cursorIndex, offsetBy: remaining, limitedBy: input.endIndex)
                ?? input.endIndex
            let windowEndByte = cursorByte + input[cursorIndex..<windowEndIndex].utf8.count

            var nearest: (start: Int, openLen: Int, close: [UInt8])?
            for region in opaqueRegions {
                guard let at = findUTF8(bytes, region.open, from: cursorByte, to: windowEndByte)
                else { continue }
                if nearest == nil || at < nearest!.start {
                    nearest = (at, region.open.count, region.close)
                }
            }

            guard let hit = nearest else {
                // No opaque region starts inside the budget: take the window
                // and stop. `windowEndIndex` is already Character-aligned, so
                // a multi-scalar grapheme can never be split here.
                out += input[cursorIndex..<windowEndIndex]
                break
            }

            // Prose before the region. `hit.start` is the first byte of an
            // ASCII marker, so it is a Character boundary.
            let hitIndex = input.utf8.index(cursorIndex, offsetBy: hit.start - cursorByte)
            if hit.start > cursorByte, let hitCharIndex = hitIndex.samePosition(in: input) {
                let prose = input[cursorIndex..<hitCharIndex]
                out += prose
                remaining -= prose.count
            }

            // Skip the region whole. A missing closer means the region runs
            // to the end of the input.
            let afterOpen = hit.start + hit.openLen
            guard let closeAt = findUTF8(bytes, hit.close, from: afterOpen, to: endByte) else { break }
            let nextByte = closeAt + hit.close.count
            guard let nextIndex = input.utf8.index(
                input.startIndex, offsetBy: nextByte, limitedBy: input.endIndex
            )?.samePosition(in: input) else { break }
            cursorByte = nextByte
            cursorIndex = nextIndex

            // Keep word separation where a fence sat between two words, the
            // same way plainText's fence regex substitutes a single space.
            if remaining > 0, !out.isEmpty, !(out.last?.isWhitespace ?? true) {
                out += " "
                remaining -= 1
            }
        }
        return out
    }
}
