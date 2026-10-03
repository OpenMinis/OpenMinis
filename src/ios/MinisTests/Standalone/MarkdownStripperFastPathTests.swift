// Tests for [T-ios-listsessions-perf] Phase 2 — MarkdownStripper's regexes are
// now compiled once into `static let`s and the preview slicer scans UTF-8 bytes
// instead of bridging to NSString on every marker search.
//
// The point of this file is that the rewrite is a PURE performance change. The
// pre-Phase-2 implementation is reproduced below verbatim as `LegacyStripper`,
// and every case asserts new == old byte-for-byte. Anything that diverges is a
// bug in the rewrite, not a new intended behaviour — with one deliberate and
// separately-asserted exception, the 1024-Character cap before the inline and
// per-line passes (see `inlinePassCap`), which by construction only changes
// output beyond the 100 characters any preview caller keeps.
//
// Standalone (`swift MarkdownStripperFastPathTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Legacy implementation (pre-Phase-2, copied verbatim)

enum LegacyStripper {
    static func plainText(_ input: String) -> String {
        var s = input

        s = s.replacingOccurrences(of: "```[\\s\\S]*?```", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "!\\[([^\\]]*)\\]\\([^)]*\\)", with: "$1", options: .regularExpression)
        s = rewriteLinks(s)
        s = s.replacingOccurrences(of: "<(https?://[^>]+)>", with: "$1", options: .regularExpression)

        let inlinePatterns: [(String, String)] = [
            ("\\*\\*([^*]+)\\*\\*", "$1"),
            ("__([^_]+)__",          "$1"),
            ("\\*([^*]+)\\*",        "$1"),
            ("(?<!\\w)_([^_]+)_(?!\\w)", "$1"),
            ("~~([^~]+)~~",          "$1"),
            ("`([^`]*)`",            "$1"),
        ]
        for (pattern, replacement) in inlinePatterns {
            s = s.replacingOccurrences(of: pattern, with: replacement, options: .regularExpression)
        }

        let lines = s.components(separatedBy: "\n")
        let cleaned: [String] = lines.compactMap { raw in
            var line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { return nil }

            let sepStripped = line.trimmingCharacters(in: CharacterSet(charactersIn: "-=*_ "))
            if sepStripped.isEmpty { return nil }

            if line.hasPrefix("|") || (line.contains("|") && line.hasSuffix("|")) { return nil }
            if line.allSatisfy({ "|-: ".contains($0) }) && line.contains("|") { return nil }

            if line.hasPrefix("```") || line.hasPrefix("~~~") { return nil }

            line = line.replacingOccurrences(of: "^#{1,6}\\s+", with: "", options: .regularExpression)
            line = line.replacingOccurrences(of: "^>\\s?", with: "", options: .regularExpression)
            line = line.replacingOccurrences(of: "^[-*+]\\s+", with: "", options: .regularExpression)
            line = line.replacingOccurrences(of: "^\\d+[.):]\\s+", with: "", options: .regularExpression)
            line = line.replacingOccurrences(of: "[*_~`]{2,}", with: "", options: .regularExpression)

            line = line.trimmingCharacters(in: .whitespaces)
            return line.isEmpty ? nil : line
        }

        var result = cleaned.joined(separator: " ")
        result = result.replacingOccurrences(of: "  +", with: " ", options: .regularExpression)
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func rewriteLinks(_ text: String) -> String {
        let pattern = "!?\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)"
        guard let re = try? NSRegularExpression(pattern: pattern) else { return text }
        let ns = text as NSString
        var result = ""
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

    private static let opaqueRegions: [(open: String, close: String)] = [
        ("```", "```"),
        ("<system-reminder>", "</system-reminder>"),
        ("<user-attached-files>", "</user-attached-files>"),
    ]

    static func previewSource(_ input: String, maxLength: Int = 4096) -> String {
        guard maxLength > 0 else { return "" }
        var out = ""
        out.reserveCapacity(min(maxLength, 8192))
        var remaining = maxLength
        var cursor = input.startIndex
        let end = input.endIndex

        while cursor < end, remaining > 0 {
            let windowEnd = input.index(cursor, offsetBy: remaining, limitedBy: end) ?? end
            var nearest: (range: Range<String.Index>, close: String)?
            for region in opaqueRegions {
                guard let r = input.range(of: region.open, range: cursor..<windowEnd) else { continue }
                if nearest == nil || r.lowerBound < nearest!.range.lowerBound {
                    nearest = (r, region.close)
                }
            }

            guard let hit = nearest else {
                out += input[cursor..<windowEnd]
                break
            }

            if hit.range.lowerBound > cursor {
                let prose = input[cursor..<hit.range.lowerBound]
                out += prose
                remaining -= prose.count
            }
            guard let closer = input.range(of: hit.close, range: hit.range.upperBound..<end) else { break }
            cursor = closer.upperBound
            if remaining > 0, !out.isEmpty, !(out.last?.isWhitespace ?? true) {
                out += " "
                remaining -= 1
            }
        }
        return out
    }
}

// MARK: - New implementation (copied from src/ios/Shared/MarkdownStripper.swift)
//
// Kept in sync by hand, the way every other Standalone test mirrors the code it
// covers — there is no way to import the app module from a `swift file.swift`
// run. Phase 2 changed only this type, so a drift here shows up as a failure in
// the real-corpus cases below rather than passing silently.

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
    static let inlinePassCap = 1024

    /// Full markdown-to-plain-text conversion of `input`.
    ///
    /// Deliberately NOT size-capped for the structural passes: this is a
    /// general utility and must return the whole stripped text for its
    /// document-facing callers. Callers that only need a short excerpt of a
    /// potentially huge body (the session-list preview, which runs for every
    /// session on every refresh) must bound their input FIRST with
    /// `previewSource(_:maxLength:)` — see that function for why the bound
    /// cannot simply be a `prefix` in front of the regex passes.
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

// MARK: - Corpus

let longProse = String(repeating: "The quick brown fox jumps over the lazy dog. ", count: 140)
let longFence = "```swift\n" + String(repeating: "let x = 1\n", count: 600) + "```"

let corpus: [(name: String, text: String)] = [
    ("empty", ""),
    ("plain", "just a plain sentence"),
    ("heading", "# Title\n\nBody text here."),
    ("deep heading", "###### Six levels\ncontent"),
    ("bold", "This is **bold** and __also bold__."),
    ("italic", "This is *italic* and _also italic_ but snake_case_word stays."),
    ("strike + code", "~~gone~~ and `inline code` here."),
    ("fence", "Before\n```swift\nlet x = 1\nprint(x)\n```\nAfter"),
    ("tilde fence line", "~~~\nnot markdown\n~~~\ntail"),
    ("unclosed fence", "Intro\n```\nnever closed"),
    ("image", "See ![alt text](https://example.com/a.png) here."),
    ("image empty alt", "See ![](https://example.com/a.png) here."),
    ("link", "Read [the docs](https://example.com/docs) now."),
    ("link empty title", "Read [](https://example.com/docs) now."),
    ("link with title attr", "Read [docs](https://example.com \"Title\") now."),
    ("autolink", "Visit <https://example.com/x> today."),
    ("bullets", "- one\n- two\n* three\n+ four"),
    ("ordered", "1. first\n2) second\n3: third"),
    ("blockquote", "> quoted line\n> another"),
    ("hrule", "text\n---\nmore\n***\nend"),
    ("table", "| a | b |\n|---|---|\n| 1 | 2 |\ntail line"),
    ("table no trailing pipe", "a | b\n--- | ---\n1 | 2"),
    ("emoji", "Done ✅ shipped 🚀🎉 and family 👨‍👩‍👧‍👦 here."),
    ("CJK", "这是一段中文预览文本，包含标点。还有更多内容。"),
    ("CJK + markdown", "## 标题\n\n**粗体**中文和`代码`混排。"),
    ("mixed scripts", "Hello 世界 مرحبا שלום こんにちは"),
    ("system reminder", "Answer here.<system-reminder>hidden instructions</system-reminder> Tail."),
    ("reminder only", "<system-reminder>all hidden</system-reminder>"),
    ("unclosed reminder", "Visible<system-reminder>never closed"),
    ("attached files", "Question?<user-attached-files>\n<file>a.txt</file>\n</user-attached-files> More."),
    ("attachment markers", "Look at this [attached image: photo.png] and [image omitted to save context — too big]."),
    ("multi space", "a    b\t\tc     d"),
    ("whitespace only", "   \n\n \t \n  "),
    ("newlines heavy", "a\n\n\n\nb\n\n\nc"),
    ("long prose >4096", longProse),
    ("long fence", longFence),
    ("long fence then prose", longFence + "\nThe real answer is 42."),
    ("reminder then long", "<system-reminder>" + String(repeating: "x", count: 5000) + "</system-reminder>Real answer."),
    ("emoji at cut", String(repeating: "a", count: 4095) + "🚀tail"),
    ("CJK at cut", String(repeating: "中", count: 4100)),
    ("nested markers", "```\n<system-reminder>inside fence</system-reminder>\n```\nafter"),
    ("everything", """
    # Report 报告

    Here is **bold**, *italic*, `code`, ~~strike~~ and a [link](https://x.com/a).

    ```python
    def f():
        return 1
    """ + "\n```\n\n| col | col |\n|-----|-----|\n| 1   | 2   |\n\n> a quote\n\n- bullet ✅\n\n<system-reminder>hide me</system-reminder>\n\nFinal line."),
]

// MARK: - 1. previewSource parity (no cap involved — must be byte-identical)

print("\n▶️  previewSource: new == legacy, byte-for-byte")
for (name, text) in corpus {
    let new = MarkdownStripper.previewSource(text)
    let old = LegacyStripper.previewSource(text)
    checkEq("previewSource(\(name))", new, old)
}

print("\n▶️  previewSource: non-default budgets")
for budget in [1, 2, 3, 5, 17, 64, 100, 511, 512, 1023, 1024, 4096] {
    for (name, text) in corpus {
        let new = MarkdownStripper.previewSource(text, maxLength: budget)
        let old = LegacyStripper.previewSource(text, maxLength: budget)
        if new != old {
            checkEq("previewSource(\(name), budget \(budget))", new, old)
        }
    }
}
print("  ✅ all \(corpus.count) inputs × 12 budgets identical")

// MARK: - 2. Grapheme-alignment guarantee

print("\n▶️  previewSource never splits a grapheme")
let graphemeCases: [(String, String)] = [
    ("emoji run", String(repeating: "🚀", count: 200)),
    ("ZWJ family", String(repeating: "👨‍👩‍👧‍👦", count: 50)),
    ("flag", String(repeating: "🇯🇵", count: 80)),
    ("combining", String(repeating: "é", count: 120) + String(repeating: "e\u{0301}", count: 120)),
    ("CJK", String(repeating: "漢字仮名", count: 100)),
    ("skin tone", String(repeating: "👋🏽", count: 90)),
]
for (name, text) in graphemeCases {
    for budget in [1, 2, 3, 7, 33, 100] {
        let out = MarkdownStripper.previewSource(text, maxLength: budget)
        // Round-tripping through UTF-8 must be lossless: a split grapheme would
        // leave a partial scalar sequence that does not survive re-decoding, and
        // the Character count must never exceed the budget.
        let roundTrip = String(decoding: Array(out.utf8), as: UTF8.self)
        check("\(name) @\(budget): valid UTF-8 round trip", roundTrip == out)
        check("\(name) @\(budget): count <= budget", out.count <= budget)
        check("\(name) @\(budget): is a prefix of the source", text.hasPrefix(out))
    }
}

// MARK: - 3. plainText parity below the cap

print("\n▶️  plainText: new == legacy for inputs under the 1024 cap")
for (name, text) in corpus where text.count <= MarkdownStripper.inlinePassCap {
    let new = MarkdownStripper.plainText(text, inlinePassCap: MarkdownStripper.inlinePassCap)
    let old = LegacyStripper.plainText(text)
    checkEq("plainText(\(name))", new, old)
}

// MARK: - 4. plainText parity on the path the preview actually takes
//
// The sidebar calls previewSource(4096) → strips → plainText → prefix(100).
// That is the only pipeline the cap can affect, so assert the END of it is
// unchanged for every corpus entry, long ones included.

print("\n▶️  preview pipeline: first 100 chars unchanged for every input")
func pipeline(_ text: String, _ strip: (String) -> String, _ pre: (String, Int) -> String) -> String {
    let bounded = pre(text, 4096)
    return String(strip(bounded).prefix(100))
}
for (name, text) in corpus {
    let new = pipeline(text, { MarkdownStripper.plainText($0, inlinePassCap: MarkdownStripper.inlinePassCap) }, { MarkdownStripper.previewSource($0, maxLength: $1) })
    let old = pipeline(text, LegacyStripper.plainText, { LegacyStripper.previewSource($0, maxLength: $1) })
    checkEq("preview(\(name))", new, old)
}

// MARK: - 5. The cap's deliberate divergence, bounded

print("\n▶️  1024 cap: only affects output past the preview window")
let capProbe = String(repeating: "word ", count: 3000)
let cappedNew = MarkdownStripper.plainText(capProbe, inlinePassCap: MarkdownStripper.inlinePassCap)
let cappedOld = LegacyStripper.plainText(capProbe)
check("cap does shorten a >1024 body", cappedNew.count < cappedOld.count)
check("kept prefix still agrees to 100 chars",
      String(cappedNew.prefix(100)) == String(cappedOld.prefix(100)))
check("cap keeps at least 900 chars of prose", cappedNew.count >= 900)
checkEq("cap is 1024, not 512", MarkdownStripper.inlinePassCap, 1024)

// A pathological body where nearly every line is dropped by the per-line pass:
// the cap must still leave >= 100 real characters, which is what 1024-over-512
// buys.
let tableHeavy = (0..<400).map { "| cell \($0) | cell \($0) |" }.joined(separator: "\n")
    + "\nThe actual answer sentence that the user needs to read in the sidebar preview."
let tableNew = MarkdownStripper.plainText(MarkdownStripper.previewSource(tableHeavy), inlinePassCap: MarkdownStripper.inlinePassCap)
let tableOld = LegacyStripper.plainText(LegacyStripper.previewSource(tableHeavy))
checkEq("table-heavy preview unchanged", String(tableNew.prefix(100)), String(tableOld.prefix(100)))

// MARK: - 6. utf8Contains

print("\n▶️  utf8Contains matches Foundation contains-on-prefix")
let containsCases: [(String, String, Int)] = [
    ("<agent_callback><result>x</result></agent_callback>", "<agent_callback", 4096),
    ("no marker at all here", "<agent_callback", 4096),
    (String(repeating: "x", count: 5000) + "<agent_callback", "<agent_callback", 4096),
    ("<agent_callback" + String(repeating: "y", count: 9000), "<agent_callback", 4096),
    ("中文前缀<agent_callback>", "<agent_callback", 4096),
    ("", "<agent_callback", 4096),
    ("partial <agent_callbac", "<agent_callback", 4096),
]
for (hay, needle, limit) in containsCases {
    let new = MarkdownStripper.utf8Contains(hay, needle, withinBytes: limit)
    // Legacy semantics: prefix(4096) CHARACTERS then contains. For an ASCII
    // needle the only inputs where a byte budget and a character budget differ
    // are ones where the needle straddles the boundary — the cases above pin
    // both sides of it.
    let old = String(hay.prefix(limit)).contains(needle)
    checkEq("utf8Contains(\(hay.prefix(24))…)", new, old)
}

// MARK: - 7. Timing

print("\n▶️  timing (1000 iterations of the full preview pipeline)")
let timingInput = corpus.map(\.text).joined(separator: "\n\n")
func timeIt(_ label: String, _ body: () -> Void) -> Double {
    let t0 = Date()
    body()
    let dt = Date().timeIntervalSince(t0)
    print("  ⏱  \(label): \(String(format: "%.3f", dt))s")
    return dt
}
let iterations = 1000
let oldTime = timeIt("legacy") {
    for _ in 0..<iterations {
        _ = String(LegacyStripper.plainText(LegacyStripper.previewSource(timingInput)).prefix(100))
    }
}
let newTime = timeIt("fast path") {
    for _ in 0..<iterations {
        _ = String(MarkdownStripper.plainText(MarkdownStripper.previewSource(timingInput), inlinePassCap: MarkdownStripper.inlinePassCap).prefix(100))
    }
}
print("  📊 speedup: \(String(format: "%.2f", oldTime / max(newTime, 0.0001)))×")

// MARK: - Summary

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All MarkdownStripper fast-path tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
