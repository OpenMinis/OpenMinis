import Foundation

// MARK: - Refreshable token contract

/// [T-oauth-refresh-race] The minimal shape the refresh-race guard needs from a
/// provider's persisted OAuth credential. Every provider's `*TokenStorage`
/// (Claude / Gemini / xAI / Codex / Antigravity) already satisfies this, so they
/// conform with an empty extension and share ONE tested decision engine —
/// avoiding five divergent copies of the "when to delete vs keep" semantics.
protocol RefreshableOAuthToken {
    var accessToken: String { get }
    var refreshToken: String? { get }
    var isExpired: Bool { get }
}

// NOTE: Each provider's persisted `*TokenStorage` conforms via an empty
// extension declared IN ITS OWN manager file (not here), because those types
// live in UIKit-importing files that are not part of the unit-test target,
// whereas this coordinator IS compiled into the test target. Keeping the
// conformances next to the types avoids dragging UIKit into the test build.

// MARK: - Structured refresh-error classification (P2)

/// [T-oauth-refresh-race-classify] Structured replacement for the old naive
/// `msg.contains("400") || msg.contains("invalid_grant") ...` substring test.
///
/// Substring matching over a flattened error string mis-fires: a response BODY
/// that merely contains "400" (a request id, a byte count, a quota number, a
/// timestamp) or the substring "refresh_token" (in prose, or inside
/// "refresh_token_expiry_ms") was classified as a fatal token-invalid error and
/// triggered credential deletion — logging the user out on a benign/transient
/// failure. This classifier instead reads:
///   1. the REAL HTTP status code, passed structurally by the throw site (never
///      scraped from the body), via the `oauth_http_status=<code>` marker; and
///   2. the OAuth `error` field parsed from the JSON body with JSONDecoder.
///
/// A failure is fatal (re-login-worthy) only when the HTTP status is one of the
/// auth-rejection codes AND/OR the parsed `error` code is in the provider's
/// known-fatal set. Providers pass their own fatal-code set so the differing
/// semantics (xAI/Codex honor `refresh_token_reused`; Google honors
/// `invalid_token`) stay explicit while the fragile parsing is shared + tested.
enum OAuthRefreshErrorClassifier {

    /// Stable marker the throw site embeds so the real HTTP status survives into
    /// the flattened `LLMError.providerError(message:)` string without being
    /// confused with numbers in the body.
    static let statusMarkerPrefix = "oauth_http_status="

    /// Build the machine-readable message a token-endpoint throw site should use:
    /// `oauth_http_status=<code> <body>`. The classifier parses the marker; the
    /// body is preserved verbatim for logs/UI.
    static func makeErrorMessage(status: Int, body: String) -> String {
        "\(statusMarkerPrefix)\(status) \(body)"
    }

    /// HTTP statuses that indicate the refresh token / grant itself is rejected
    /// (not a transient 5xx / network blip).
    static let fatalStatuses: Set<Int> = [400, 401, 403]

    /// Extract the structured HTTP status embedded by `makeErrorMessage`, if any.
    /// Returns nil when the message carries no marker (e.g. a network error, or a
    /// message from an unrelated throw site) — in which case status is unknown
    /// and must not by itself imply fatal.
    static func parseStatus(from message: String) -> Int? {
        guard let range = message.range(of: statusMarkerPrefix) else { return nil }
        let rest = message[range.upperBound...]
        // status is the leading run of digits after the marker
        let digits = rest.prefix { $0.isNumber }
        return Int(digits)
    }

    /// Parse the OAuth `error` field from a JSON body, if present and well-formed.
    /// Only reads the top-level `error` string (RFC 6749 token-error shape:
    /// `{"error":"invalid_grant", ...}`). Returns lowercased for comparison.
    static func parseErrorCode(fromBody message: String) -> String? {
        // The body follows the status marker; if there's no marker, treat the
        // whole message as the candidate body.
        let body: Substring
        if let range = message.range(of: statusMarkerPrefix) {
            // skip marker + digits + one space
            let afterMarker = message[range.upperBound...].drop { $0.isNumber }
            body = afterMarker.drop { $0 == " " }
        } else {
            body = Substring(message)
        }
        guard let jsonStart = body.firstIndex(of: "{"),
              let data = String(body[jsonStart...]).data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = obj["error"] as? String
        else { return nil }
        return code.lowercased()
    }

    /// Structured verdict: is this refresh failure a genuine token-invalid error
    /// that warrants marking the instance for re-login?
    ///
    /// - Parameters:
    ///   - error: the thrown error.
    ///   - fatalErrorCodes: provider's known-fatal OAuth `error` codes, lowercased
    ///     (e.g. ["invalid_grant", "refresh_token_reused"]).
    static func isTokenInvalid(_ error: LLMError, fatalErrorCodes: Set<String>) -> Bool {
        guard case .providerError(let message) = error else { return false }

        // 1. Parsed JSON error code is the most reliable signal — an exact match
        //    against the provider's fatal set (no substring ambiguity).
        if let code = parseErrorCode(fromBody: message), fatalErrorCodes.contains(code) {
            return true
        }

        // 2. Real HTTP status (structurally embedded, not scraped) in the
        //    auth-rejection set. A 400/401/403 from the token endpoint means the
        //    grant was rejected. Absence of the marker → status unknown → this
        //    branch does not fire (transient/network stays non-fatal).
        if let status = parseStatus(from: message), fatalStatuses.contains(status) {
            return true
        }

        // 3. Fallback for non-JSON error bodies: match a fatal error code only as
        //    a WHOLE identifier token (delimited by non-identifier chars), never a
        //    loose substring. This still catches a plain-text `refresh_token_reused`
        //    while refusing to fire on "400" inside a request id or "refresh_token"
        //    inside "refresh_token_expiry_ms". Numeric-looking codes are excluded
        //    (handled by the structured status branch above).
        let lower = message.lowercased()
        for code in fatalErrorCodes where !code.allSatisfy(\.isNumber) {
            if containsWholeToken(code, in: lower) { return true }
        }

        return false
    }

    /// True when `needle` appears in `haystack` bounded by identifier delimiters
    /// on both sides (start/end of string, whitespace, quotes, punctuation) — so
    /// it matches the code as a standalone token, not as a substring of a longer
    /// identifier or word.
    static func containsWholeToken(_ needle: String, in haystack: String) -> Bool {
        guard !needle.isEmpty else { return false }
        var searchStart = haystack.startIndex
        while let range = haystack.range(of: needle, range: searchStart..<haystack.endIndex) {
            let beforeOK: Bool = {
                guard range.lowerBound > haystack.startIndex else { return true }
                let prev = haystack[haystack.index(before: range.lowerBound)]
                return !isIdentifierChar(prev)
            }()
            let afterOK: Bool = {
                guard range.upperBound < haystack.endIndex else { return true }
                let next = haystack[range.upperBound]
                return !isIdentifierChar(next)
            }()
            if beforeOK && afterOK { return true }
            searchStart = range.upperBound
        }
        return false
    }

    private static func isIdentifierChar(_ c: Character) -> Bool {
        c.isLetter || c.isNumber || c == "_"
    }
}

// MARK: - Shared refresh-failure resolution (compare-before-delete)

/// [T-oauth-refresh-race] Pure, UIKit-free decision logic for "a refresh attempt
/// failed — now what?". Generic over any `RefreshableOAuthToken`, so all OAuth
/// providers reuse the identical guard the Claude fix introduced. Compiles into
/// the unit-test target; all I/O is injected so tests drive keychain state
/// deterministically without touching the real Keychain or the network.
enum OAuthRefreshCoordinator {

    /// Decide what storage to use (or whether to mark the instance for re-login) after a
    /// refresh attempt threw `error`.
    ///
    /// The critical guard is *compare-before-delete*: on a token-invalid error
    /// (`invalid_grant` / `refresh_token_reused` / HTTP 400-403) we mark the
    /// instance for re-login ONLY when the currently-persisted refresh token is
    /// still the one we failed with. If a concurrent refresh already rotated it
    /// to a new value, this request is stale and returning `current` preserves
    /// the freshly-written token instead of wiping it (the bug that logged users
    /// out ~45 min after login).
    ///
    /// - Parameters:
    ///   - providerName: for error messages / logs (e.g. "Gemini").
    ///   - staleRefreshToken: the refresh token this caller failed with.
    ///   - existingStorage: caller's already-loaded storage (transient-failure fallback).
    ///   - error: the thrown refresh error.
    ///   - isFatal: classifies `error` as "refresh token itself invalid" vs transient.
    ///   - loadCurrent: reads the latest persisted storage (may reflect a concurrent rotation).
    ///   - markNeedsReauth: flags the instance as needing re-login (credentials are kept).
    ///   - log: optional human-readable trace sink.
    /// - Returns: the storage to continue with.
    /// - Throws: `LLMError.invalidAPIKey` when credentials are genuinely gone /
    ///   the fallback is also expired.
    static func resolveAfterRefreshFailure<T: RefreshableOAuthToken>(
        providerName: String,
        staleRefreshToken: String,
        existingStorage: T,
        error: Error,
        isFatal: (LLMError) -> Bool,
        loadCurrent: () -> T?,
        markNeedsReauth: () -> Void,
        log: ((String) -> Void)? = nil
    ) throws -> T {
        // Re-load the latest persisted state — a concurrent winner may have
        // rotated the token while we were awaiting.
        let current = loadCurrent()

        if let llmError = error as? LLMError, isFatal(llmError) {
            if let current, current.refreshToken != staleRefreshToken {
                log?("Stale invalid_grant ignored — token already rotated; keeping new credentials")
                return current
            }
            // [T-oauth-keep-credentials] Never delete on a rejected refresh:
            // the classifier can misread a transient reply, and a wiped
            // credential cannot be recovered. Mark the instance so the UI shows
            // it red and routing skips it; the mark lapses on its own once a
            // new credential is stored (re-login, or a Keychain sync from a
            // peer), and only an explicit Sign Out removes the blob.
            log?("Refresh token invalid, marking instance for re-login (credentials kept): \(llmError)")
            markNeedsReauth()
            throw LLMError.invalidAPIKey(detail: "\(providerName): refresh token invalid — \(llmError)")
        }

        // Non-fatal (network / transient). Prefer whatever is now stored (a
        // concurrent winner may have refreshed); else keep the caller's copy.
        let fallback = current ?? existingStorage
        log?("Refresh failed, keeping existing token: \(error)")
        if fallback.isExpired {
            log?("Existing token is also expired — re-auth required")
            throw LLMError.invalidAPIKey(detail: "\(providerName): token expired and refresh failed — \(error)")
        }
        return fallback
    }
}
