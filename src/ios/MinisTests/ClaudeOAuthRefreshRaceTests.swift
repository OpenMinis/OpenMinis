import XCTest

/// [T-oauth-refresh-race] Regression coverage for the Anthropic OAuth
/// refresh-token-rotation race that logged users out ~45 min after login.
///
/// Device evidence (sanitized, 2026-07-23): two `validAccessToken` callers
/// entered ~341 ms apart with the same refresh token. Anthropic rotates the
/// refresh token on refresh, so caller A got 200 + wrote the NEW token, and the
/// stale caller B got 400 `invalid_grant` and (pre-fix) unconditionally deleted
/// the freshly-written credential. These tests pin the compare-before-delete
/// guard: a stale `invalid_grant` must NEVER wipe a token another request
/// already rotated.
final class ClaudeOAuthRefreshRaceTests: XCTestCase {

    // A simple in-memory keychain stand-in so the pure coordinator runs with
    // deterministic state (no real Keychain, no network).
    private final class FakeStore {
        var stored: ClaudeTokenStorage?
        var markCount = 0
        func load() -> ClaudeTokenStorage? { stored }
        func markNeedsReauth() { markCount += 1 }
    }

    private func storage(access: String, refresh: String?, expiresInMinutes: Double = 60) -> ClaudeTokenStorage {
        ClaudeTokenStorage(
            accessToken: access,
            refreshToken: refresh,
            expireDate: Date().addingTimeInterval(expiresInMinutes * 60),
            lastRefresh: nil
        )
    }

    private func invalidGrant() -> LLMError {
        // Mirrors production: a non-2xx becomes providerError with the structured
        // status marker + JSON body (see OAuthRefreshErrorClassifier.makeErrorMessage).
        .providerError(message: "Token refresh failed: " + OAuthRefreshErrorClassifier.makeErrorMessage(
            status: 400,
            body: "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token not found or invalid\"}"))
    }

    private func isFatal(_ e: LLMError) -> Bool {
        // Same structured classification the manager now uses (P2).
        OAuthRefreshErrorClassifier.isTokenInvalid(
            e, fatalErrorCodes: ["invalid_grant", "invalid_token", "invalid_request", "unauthorized_client"])
    }

    // MARK: - The core race: A rotated the token, stale B's 400 must NOT delete it.

    func testStaleInvalidGrant_afterRotation_keepsNewToken() throws {
        let store = FakeStore()
        // Caller A already succeeded: the store now holds the NEW rotated token.
        store.stored = storage(access: "NEW_ACCESS", refresh: "NEW_REFRESH")

        // Caller B failed with invalid_grant using the OLD refresh token.
        let result = try ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: "OLD_REFRESH",
            existingStorage: storage(access: "OLD_ACCESS", refresh: "OLD_REFRESH"),
            error: invalidGrant(),
            isFatal: isFatal,
            loadCurrent: store.load,
            markNeedsReauth: store.markNeedsReauth
        )

        // The freshly-rotated credential must survive, and nothing deleted.
        XCTAssertEqual(result.accessToken, "NEW_ACCESS")
        XCTAssertEqual(result.refreshToken, "NEW_REFRESH")
        XCTAssertEqual(store.markCount, 0, "stale invalid_grant must not mark a rotated token")
        XCTAssertNotNil(store.stored)
    }

    // MARK: - Genuine invalid_grant (token really was revoked) marks, never deletes.

    func testGenuineInvalidGrant_marksForReauth_keepsCredentials() {
        let store = FakeStore()
        // The stored token IS the one that failed — a real revocation.
        store.stored = storage(access: "ACCESS", refresh: "SAME_REFRESH")

        XCTAssertThrowsError(
            try ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure(
                staleRefreshToken: "SAME_REFRESH",
                existingStorage: storage(access: "ACCESS", refresh: "SAME_REFRESH"),
                error: invalidGrant(),
                isFatal: isFatal,
                loadCurrent: store.load,
                markNeedsReauth: store.markNeedsReauth
            )
        ) { error in
            guard case LLMError.invalidAPIKey = error else {
                return XCTFail("expected invalidAPIKey, got \(error)")
            }
        }
        XCTAssertEqual(store.markCount, 1, "a genuine invalid_grant must mark for re-login, keeping credentials")
        XCTAssertNotNil(store.stored)
    }

    // MARK: - Missing store entry on invalid_grant throws re-auth (nothing to preserve).

    func testInvalidGrant_noStoredToken_throwsReauth() {
        let store = FakeStore()
        store.stored = nil // nothing persisted

        XCTAssertThrowsError(
            try ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure(
                staleRefreshToken: "OLD_REFRESH",
                existingStorage: storage(access: "OLD_ACCESS", refresh: "OLD_REFRESH"),
                error: invalidGrant(),
                isFatal: isFatal,
                loadCurrent: store.load,
                markNeedsReauth: store.markNeedsReauth
            )
        )
        // The mark is still requested (a no-op with nothing stored), but the key
        // point is it throws re-auth rather than silently returning a stale token.
        XCTAssertEqual(store.markCount, 1)
    }

    // MARK: - Transient (network) failure keeps a still-valid token, no delete.

    func testTransientFailure_keepsValidToken_noDelete() throws {
        let store = FakeStore()
        store.stored = storage(access: "ACCESS", refresh: "REFRESH", expiresInMinutes: 30)

        let netErr = LLMError.networkError(underlying: URLError(.timedOut))
        let result = try ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: "REFRESH",
            existingStorage: storage(access: "ACCESS", refresh: "REFRESH", expiresInMinutes: 30),
            error: netErr,
            isFatal: isFatal,
            loadCurrent: store.load,
            markNeedsReauth: store.markNeedsReauth
        )

        XCTAssertEqual(result.accessToken, "ACCESS")
        XCTAssertEqual(store.markCount, 0, "a transient failure must never mark for re-login")
    }

    // MARK: - Transient failure but the token is ALSO expired → re-auth needed.

    func testTransientFailure_expiredToken_throwsReauth() {
        let store = FakeStore()
        store.stored = storage(access: "ACCESS", refresh: "REFRESH", expiresInMinutes: -5) // already expired

        let netErr = LLMError.networkError(underlying: URLError(.notConnectedToInternet))
        XCTAssertThrowsError(
            try ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure(
                staleRefreshToken: "REFRESH",
                existingStorage: storage(access: "ACCESS", refresh: "REFRESH", expiresInMinutes: -5),
                error: netErr,
                isFatal: isFatal,
                loadCurrent: store.load,
                markNeedsReauth: store.markNeedsReauth
            )
        ) { error in
            guard case LLMError.invalidAPIKey = error else {
                return XCTFail("expected invalidAPIKey (re-auth), got \(error)")
            }
        }
        XCTAssertEqual(store.markCount, 0, "transient+expired throws re-auth but does not itself mark")
    }

    // MARK: - Transient failure where a concurrent winner already refreshed.

    func testTransientFailure_prefersConcurrentlyRotatedToken() throws {
        let store = FakeStore()
        // A concurrent winner rotated to a fresh token while we were failing on a
        // transient error with the old one.
        store.stored = storage(access: "WINNER_ACCESS", refresh: "WINNER_REFRESH")

        let netErr = LLMError.networkError(underlying: URLError(.timedOut))
        let result = try ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: "OLD_REFRESH",
            existingStorage: storage(access: "OLD_ACCESS", refresh: "OLD_REFRESH", expiresInMinutes: -5),
            error: netErr,
            isFatal: isFatal,
            loadCurrent: store.load,
            markNeedsReauth: store.markNeedsReauth
        )

        // Even though our own existingStorage was expired, the persisted winner
        // token is used instead of throwing.
        XCTAssertEqual(result.accessToken, "WINNER_ACCESS")
        XCTAssertEqual(store.markCount, 0)
    }
}
