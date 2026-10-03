import XCTest

/// [T-oauth-refresh-race] Regression coverage for the shared refresh-race guard
/// applied to xAI, Codex, Gemini, and Antigravity (mirroring the Claude fix).
///
/// All four providers route refresh failures through
/// `OAuthRefreshCoordinator.resolveAfterRefreshFailure`, so these tests exercise
/// that generic engine with a lightweight conforming token plus each provider's
/// actual fatal-error classifier (`isRefreshTokenInvalid`), including the
/// provider-specific rotation signals (`refresh_token_reused` for xAI/Codex).
///
/// Each provider covers three scenarios per the spec:
///   ① concurrent race — stale fatal error must KEEP a token another caller just rotated
///   ② genuine invalid — the stored token really is dead → mark for re-login + throw (never delete)
///   ③ transient/network — never mark a still-valid token
final class OAuthRefreshRaceAllProvidersTests: XCTestCase {

    // Lightweight stand-in conforming to the shared protocol.
    private struct FakeToken: RefreshableOAuthToken {
        let accessToken: String
        let refreshToken: String?
        let expiresInMinutes: Double
        var isExpired: Bool { expiresInMinutes < 0 }
    }

    private final class FakeStore {
        var stored: FakeToken?
        var markCount = 0
        func load() -> FakeToken? { stored }
        func markNeedsReauth() { markCount += 1 }
    }

    private func tok(_ a: String, _ r: String?, _ mins: Double = 60) -> FakeToken {
        FakeToken(accessToken: a, refreshToken: r, expiresInMinutes: mins)
    }

    // MARK: - Provider fatal-error classifiers (delegate to the SHARED structured
    // classifier with each provider's real fatal-code set, matching production
    // after the P2 change — no more substring matching).

    private let googleFatal: Set<String> = ["invalid_grant", "invalid_token", "invalid_request", "unauthorized_client"]
    private let rotatingFatal: Set<String> = ["invalid_grant", "invalid_token", "invalid_request", "unauthorized_client", "refresh_token_reused"]

    private func fatal(_ codes: Set<String>) -> (LLMError) -> Bool {
        { OAuthRefreshErrorClassifier.isTokenInvalid($0, fatalErrorCodes: codes) }
    }

    /// Build a fatal rotation error the way the throw sites now do (with the
    /// structured HTTP-status marker).
    private func rotationError(status: Int, body: String) -> LLMError {
        .providerError(message: "Token refresh failed: " + OAuthRefreshErrorClassifier.makeErrorMessage(status: status, body: body))
    }

    private struct Provider {
        let name: String
        let isFatal: (LLMError) -> Bool
        /// A fatal error that matches this provider's rotation-reuse signal.
        let rotationError: LLMError
    }

    private var providers: [Provider] {
        [
            Provider(name: "Gemini", isFatal: fatal(googleFatal),
                     rotationError: rotationError(status: 400, body: "{\"error\":\"invalid_grant\"}")),
            Provider(name: "xAI", isFatal: fatal(rotatingFatal),
                     rotationError: rotationError(status: 400, body: "{\"error\":\"refresh_token_reused\"}")),
            Provider(name: "Codex", isFatal: fatal(rotatingFatal),
                     rotationError: rotationError(status: 400, body: "{\"error\":\"refresh_token_reused\"}")),
            Provider(name: "Antigravity", isFatal: fatal(googleFatal),
                     rotationError: rotationError(status: 400, body: "{\"error\":\"invalid_grant\"}")),
        ]
    }

    // MARK: - ① Concurrent race: stale fatal must keep the rotated token.

    func testStaleRotationError_keepsNewToken_allProviders() throws {
        for p in providers {
            let store = FakeStore()
            store.stored = tok("NEW_ACCESS", "NEW_REFRESH") // winner already rotated + wrote

            let result = try OAuthRefreshCoordinator.resolveAfterRefreshFailure(
                providerName: p.name,
                staleRefreshToken: "OLD_REFRESH",
                existingStorage: tok("OLD_ACCESS", "OLD_REFRESH"),
                error: p.rotationError,
                isFatal: p.isFatal,
                loadCurrent: store.load,
                markNeedsReauth: store.markNeedsReauth
            )

            XCTAssertEqual(result.accessToken, "NEW_ACCESS", "\(p.name): must keep rotated token")
            XCTAssertEqual(result.refreshToken, "NEW_REFRESH", "\(p.name)")
            XCTAssertEqual(store.markCount, 0, "\(p.name): stale error must NOT mark a rotated token")
            XCTAssertNotNil(store.stored, "\(p.name)")
        }
    }

    // MARK: - ② Genuine invalid: same token still stored → mark + throw, credentials kept.

    func testGenuineInvalid_marksForReauth_allProviders() {
        for p in providers {
            let store = FakeStore()
            store.stored = tok("ACCESS", "SAME_REFRESH")

            XCTAssertThrowsError(
                try OAuthRefreshCoordinator.resolveAfterRefreshFailure(
                    providerName: p.name,
                    staleRefreshToken: "SAME_REFRESH",
                    existingStorage: tok("ACCESS", "SAME_REFRESH"),
                    error: p.rotationError,
                    isFatal: p.isFatal,
                    loadCurrent: store.load,
                    markNeedsReauth: store.markNeedsReauth
                ),
                "\(p.name): a genuine invalid_grant must throw"
            ) { error in
                guard case LLMError.invalidAPIKey = error else {
                    return XCTFail("\(p.name): expected invalidAPIKey, got \(error)")
                }
            }
            XCTAssertEqual(store.markCount, 1, "\(p.name): genuine invalid must mark for re-login")
            XCTAssertNotNil(store.stored, "\(p.name)")
        }
    }

    // MARK: - ③ Transient/network: never delete a still-valid token.

    func testTransientFailure_keepsValidToken_allProviders() throws {
        for p in providers {
            let store = FakeStore()
            store.stored = tok("ACCESS", "REFRESH", 30)

            let result = try OAuthRefreshCoordinator.resolveAfterRefreshFailure(
                providerName: p.name,
                staleRefreshToken: "REFRESH",
                existingStorage: tok("ACCESS", "REFRESH", 30),
                error: LLMError.networkError(underlying: URLError(.timedOut)),
                isFatal: p.isFatal,
                loadCurrent: store.load,
                markNeedsReauth: store.markNeedsReauth
            )

            XCTAssertEqual(result.accessToken, "ACCESS", "\(p.name)")
            XCTAssertEqual(store.markCount, 0, "\(p.name): transient must never mark")
        }
    }

    // MARK: - ③b Transient + expired → re-auth thrown, still no delete.

    func testTransientFailure_expiredToken_throws_allProviders() {
        for p in providers {
            let store = FakeStore()
            store.stored = tok("ACCESS", "REFRESH", -5) // expired

            XCTAssertThrowsError(
                try OAuthRefreshCoordinator.resolveAfterRefreshFailure(
                    providerName: p.name,
                    staleRefreshToken: "REFRESH",
                    existingStorage: tok("ACCESS", "REFRESH", -5),
                    error: LLMError.networkError(underlying: URLError(.notConnectedToInternet)),
                    isFatal: p.isFatal,
                    loadCurrent: store.load,
                    markNeedsReauth: store.markNeedsReauth
                ),
                "\(p.name): transient+expired must throw re-auth"
            )
            XCTAssertEqual(store.markCount, 0, "\(p.name): transient must not mark even when expired")
        }
    }

    // MARK: - Provider-specific: xAI/Codex refresh_token_reused is fatal;
    // Gemini/Antigravity do NOT treat a bare "reused" string as fatal.

    func testRotationSignalClassification() {
        // A bare, non-JSON reuse string with a non-fatal (200) status.
        let reused = LLMError.providerError(message: "Token refresh failed: " +
            OAuthRefreshErrorClassifier.makeErrorMessage(status: 200, body: "refresh_token_reused"))
        XCTAssertTrue(fatal(rotatingFatal)(reused), "xAI/Codex must treat refresh_token_reused as fatal")
        // Gemini/Antigravity don't list reuse; a bare reuse string (no 4xx /
        // invalid_grant) is NOT fatal for them — token kept (correct: Google
        // returns invalid_grant on real revocation, which IS in their list).
        XCTAssertFalse(fatal(googleFatal)(reused), "Gemini/Antigravity do not list reuse")
    }
}
