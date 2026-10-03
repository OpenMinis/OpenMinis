import Foundation

// MARK: - Token Storage Model

/// Persisted Claude OAuth credentials. Kept UIKit-free (this file compiles into
/// the unit-test target) so the refresh-race logic below is testable in isolation.
struct ClaudeTokenStorage: Codable {
    let accessToken: String
    let refreshToken: String?
    let expireDate: Date?
    let lastRefresh: Date?

    var isExpired: Bool {
        guard let expire = expireDate else { return false }
        return expire < Date()
    }
}

extension ClaudeTokenStorage: RefreshableOAuthToken {}

// MARK: - Refresh-failure resolution (single-flight backstop)

/// [T-oauth-refresh-race] Pure decision logic for "a refresh attempt failed —
/// now what?", split out from `ClaudeOAuthManager` so it has NO UIKit / network
/// dependency and can be unit-tested against the exact concurrent ordering seen
/// on-device (A=200 rotates + writes the new token, stale B=400 must NOT delete
/// it). All I/O is injected (`loadCurrent`, `markNeedsReauth`) so the test
/// drives the keychain state deterministically without touching the real
/// Keychain.
enum ClaudeOAuthRefreshCoordinator {

    /// Decide what storage to use (or whether to mark the instance for re-login) after a
    /// refresh attempt threw `error`.
    ///
    /// The critical guard is *compare-before-delete*: on a token-invalid error
    /// (`invalid_grant` / HTTP 400) we mark the instance for re-login ONLY when the
    /// currently-persisted refresh token is still the one we failed with. If a
    /// concurrent refresh already rotated it to a new value, this request is
    /// stale and returning `current` preserves the freshly-written token instead
    /// of flagging it (the bug that logged users out ~45 min post-login).
    ///
    /// - Parameters:
    ///   - staleRefreshToken: the refresh token this caller failed with.
    ///   - existingStorage: caller's already-loaded storage (transient-failure fallback).
    ///   - error: the thrown refresh error.
    ///   - isFatal: classifies `error` as "refresh token itself invalid" vs transient.
    ///   - loadCurrent: reads the latest persisted storage (may reflect a concurrent rotation).
    ///   - markNeedsReauth: flags the instance as needing re-login (credentials are kept).
    ///   - log: optional sink for human-readable trace (kept out of the pure path).
    /// - Returns: the storage to continue with.
    /// - Throws: `LLMError.invalidAPIKey` when credentials are genuinely gone /
    ///   the fallback is also expired.
    static func resolveAfterRefreshFailure(
        staleRefreshToken: String,
        existingStorage: ClaudeTokenStorage,
        error: Error,
        isFatal: (LLMError) -> Bool,
        loadCurrent: () -> ClaudeTokenStorage?,
        markNeedsReauth: () -> Void,
        log: ((String) -> Void)? = nil
    ) throws -> ClaudeTokenStorage {
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
            throw LLMError.invalidAPIKey(detail: "Claude: refresh token invalid — \(llmError)")
        }

        // Non-fatal (network / transient). Prefer whatever is now stored (a
        // concurrent winner may have refreshed); else keep the caller's copy.
        let fallback = current ?? existingStorage
        log?("Refresh failed, keeping existing token: \(error)")
        if fallback.isExpired {
            log?("Existing token is also expired — re-auth required")
            throw LLMError.invalidAPIKey(detail: "Claude: token expired and refresh failed — \(error)")
        }
        return fallback
    }
}
