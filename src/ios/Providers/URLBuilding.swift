import Foundation

/// Safe URL joining for provider endpoints.
///
/// Goal: when a user-provided base URL like `https://api.deepseek.com`,
/// `https://api.deepseek.com/`, or `https://api.deepseek.com/v1/` is combined
/// with a path like `/v1/chat/completions` or `chat/completions`, the result
/// must always have exactly one `/` between every segment — never `//` and
/// never a missing separator. The scheme's `://` must be preserved.
///
/// Implementation uses `URLComponents` to keep query/fragment intact and to
/// avoid the classic `replacingOccurrences("//", "/")` pitfall that eats the
/// scheme separator.
enum URLBuilding {

    /// Join a base URL with one or more path segments. Each segment may carry
    /// any number of leading/trailing slashes — they are normalized so the
    /// final URL has exactly one `/` between every part.
    ///
    /// - Query strings (`?foo=bar`) and fragments (`#frag`) on the *last*
    ///   segment are preserved on the result.
    /// - Colons inside a segment (e.g. `models/x:streamGenerateContent`)
    ///   survive untouched — they are not URL-encoded.
    /// - Empty segments are skipped.
    ///
    /// Returns the assembled string. Falls back to a best-effort string
    /// concatenation if `base` cannot be parsed as a URL — that path keeps
    /// existing behavior for malformed input rather than throwing.
    static func join(_ base: String, _ segments: String...) -> String {
        join(base, segments: segments)
    }

    static func join(_ base: String, segments: [String]) -> String {
        let trimmedBase = base.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedBase.isEmpty else {
            return mergePathSegments(segments)
        }

        // Split the last segment into path / query / fragment so the query
        // and fragment ride along on the result rather than being mangled
        // into the path.
        var pathSegments: [String] = []
        var trailingQuery: String?
        var trailingFragment: String?

        for (idx, raw) in segments.enumerated() {
            let isLast = idx == segments.count - 1
            var seg = raw
            if isLast {
                if let hashIdx = seg.firstIndex(of: "#") {
                    trailingFragment = String(seg[seg.index(after: hashIdx)...])
                    seg = String(seg[..<hashIdx])
                }
                if let qIdx = seg.firstIndex(of: "?") {
                    trailingQuery = String(seg[seg.index(after: qIdx)...])
                    seg = String(seg[..<qIdx])
                }
            }
            pathSegments.append(seg)
        }

        // Try parsing the base via URLComponents so we can append to its path
        // without disturbing scheme/host/port/existing-query.
        if var components = URLComponents(string: trimmedBase) {
            let basePath = components.path
            let appended = mergePathSegments([basePath] + pathSegments)
            components.path = appended.isEmpty ? "" : appended

            // Merge queries: preserve any query on the base, then append the
            // last-segment query (if any) joined by `&`.
            //
            // [T-ios-urlbuilding-badkey] Both of these setters take a string that
            // is asserted to be ALREADY percent-encoded, and they do not fail
            // softly on bad input: an illegal character raises
            // NSInvalidArgumentException on iOS 16's ObjC NSURLComponents and
            // traps as a Swift fatalError on newer Foundation. Neither is
            // catchable, so the value has to be checked before assignment.
            // Callers reach here with user-supplied text (e.g. a pasted API key
            // interpolated into a segment), so bad input is expected, not
            // exceptional. Anything not already valid gets percent-encoded.
            if let q = trailingQuery, !q.isEmpty {
                let safeQuery = percentEncodedQueryValue(q)
                if let existing = components.percentEncodedQuery, !existing.isEmpty {
                    components.percentEncodedQuery = existing + "&" + safeQuery
                } else {
                    components.percentEncodedQuery = safeQuery
                }
            }
            if let f = trailingFragment, !f.isEmpty {
                components.percentEncodedFragment = percentEncodedFragmentValue(f)
            }

            if let s = components.string {
                return s
            }
        }

        // Fallback: base wasn't a parseable URL. Strip its trailing slashes
        // and concatenate.
        var result = stripTrailingSlashes(trimmedBase)
        let mergedTail = mergePathSegments(pathSegments)
        if !mergedTail.isEmpty {
            result += mergedTail.hasPrefix("/") ? mergedTail : "/" + mergedTail
        }
        if let q = trailingQuery, !q.isEmpty {
            result += "?" + q
        }
        if let f = trailingFragment, !f.isEmpty {
            result += "#" + f
        }
        return result
    }

    /// `URL` variant. Returns nil if the assembled string isn't a valid URL.
    static func joinURL(_ base: String, _ segments: String...) -> URL? {
        URL(string: join(base, segments: segments))
    }

    // MARK: - Percent-encoding validation

    /// True if `s` is already a legal value for `percentEncodedQuery` /
    /// `percentEncodedFragment`.
    ///
    /// The setters accept the characters in `allowed` plus well-formed `%XX`
    /// escapes. `%` itself is NOT in `.urlQueryAllowed`, so a plain charset test
    /// would reject a correctly pre-encoded string like `key=a%20b`; the escape
    /// sequences have to be validated separately, which is what the `%` branch
    /// below does. Verified against the real setter: `a%20b`, `a+b`, `a/b` and
    /// `a:b` are accepted; a space, a non-ASCII character, `a%zz` and a trailing
    /// `%` all abort the process.
    private static func isAlreadyPercentEncoded(_ s: String, allowed: CharacterSet) -> Bool {
        let scalars = Array(s.unicodeScalars)
        var i = 0
        while i < scalars.count {
            let c = scalars[i]
            if c == "%" {
                // Need exactly two more hex digits.
                guard i + 2 < scalars.count,
                      isHexDigit(scalars[i + 1]),
                      isHexDigit(scalars[i + 2]) else {
                    return false
                }
                i += 3
                continue
            }
            guard allowed.contains(c) else { return false }
            i += 1
        }
        return true
    }

    private static func isHexDigit(_ c: Unicode.Scalar) -> Bool {
        (c >= "0" && c <= "9") || (c >= "a" && c <= "f") || (c >= "A" && c <= "F")
    }

    /// Return `q` unchanged when it is already a valid encoded query, otherwise
    /// percent-encode it so the assignment cannot abort the process.
    ///
    /// Returning valid input untouched matters: every currently-working request
    /// keeps producing byte-identical URLs, so this cannot regress a provider
    /// that relies on an exact query string. Only malformed input changes, and
    /// for that the alternative is a crash.
    private static func percentEncodedQueryValue(_ q: String) -> String {
        if isAlreadyPercentEncoded(q, allowed: .urlQueryAllowed) {
            return q
        }
        // Keep the query's own structure (`&`, `=`) intact while escaping the
        // pieces around it, so `key=bad value&x=1` stays two parameters rather
        // than collapsing into one opaque blob.
        return q.split(separator: "&", omittingEmptySubsequences: false)
            .map { pair -> String in
                let halves = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                return halves.map { encodeComponent(String($0)) }.joined(separator: "=")
            }
            .joined(separator: "&")
    }

    private static func percentEncodedFragmentValue(_ f: String) -> String {
        if isAlreadyPercentEncoded(f, allowed: .urlFragmentAllowed) {
            return f
        }
        return encodeComponent(f)
    }

    /// Percent-encode one query key or value. `&`, `=` and `+` are excluded from
    /// the allowed set so they cannot be mistaken for structure or (in `+`'s
    /// case) be decoded as a space by a server that follows the form-encoding
    /// convention.
    private static func encodeComponent(_ s: String) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=+")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }

    // MARK: - Helpers

    /// Joins path segments with exactly one `/` between each non-empty piece.
    /// Preserves a leading `/` if the *first* non-empty segment had one.
    private static func mergePathSegments(_ segments: [String]) -> String {
        let pieces = segments
            .map { stripSurroundingSlashes($0) }
            .filter { !$0.isEmpty }

        guard !pieces.isEmpty else {
            // Caller may still want a leading "/" if any input segment was
            // explicitly "/" — but if everything was empty/whitespace, drop it.
            return ""
        }

        let firstHadLeadingSlash = segments.first(where: {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        })?.hasPrefix("/") ?? false

        let body = pieces.joined(separator: "/")
        return firstHadLeadingSlash ? "/" + body : body
    }

    private static func stripSurroundingSlashes(_ s: String) -> String {
        var start = s.startIndex
        var end = s.endIndex
        while start < end, s[start] == "/" {
            start = s.index(after: start)
        }
        while end > start, s[s.index(before: end)] == "/" {
            end = s.index(before: end)
        }
        return String(s[start..<end])
    }

    private static func stripTrailingSlashes(_ s: String) -> String {
        var end = s.endIndex
        while end > s.startIndex, s[s.index(before: end)] == "/" {
            end = s.index(before: end)
        }
        return String(s[s.startIndex..<end])
    }
}
