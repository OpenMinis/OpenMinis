import XCTest

/// Pure-logic coverage for Kimi Code OAuth: the refresh-race compare-before-delete
/// backstop (mirrors ClaudeOAuthRefreshRaceTests) and the RFC 8628 device-flow
/// classification. No network, no Keychain.
final class KimiOAuthTests: XCTestCase {

    // MARK: - Test doubles

    private final class FakeStore {
        var stored: KimiTokenStorage?
        var markCount = 0
        func load() -> KimiTokenStorage? { stored }
        func markNeedsReauth() { markCount += 1 }
    }

    private func storage(access: String, refresh: String?, expiresInMinutes: Double = 60) -> KimiTokenStorage {
        KimiTokenStorage(
            accessToken: access,
            refreshToken: refresh,
            expireDate: Date().addingTimeInterval(expiresInMinutes * 60),
            deviceId: "DEV-1",
            lastRefresh: nil
        )
    }

    private func invalidGrant() -> LLMError {
        .providerError(message: "Kimi token refresh failed: {\"error\":\"invalid_grant\"}")
    }
    private func networkError() -> LLMError {
        .networkError(underlying: NSError(domain: "test", code: -1009))
    }

    // MARK: - Refresh race (compare-before-delete)

    func testStaleInvalidGrant_afterRotation_keepsNewToken() throws {
        let store = FakeStore()
        store.stored = storage(access: "NEW_ACCESS", refresh: "NEW_REFRESH")
        let result = try KimiOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: "OLD_REFRESH",
            existingStorage: storage(access: "OLD_ACCESS", refresh: "OLD_REFRESH"),
            error: invalidGrant(),
            isFatal: KimiOAuthRefreshCoordinator.isRefreshTokenInvalid,
            loadCurrent: store.load,
            markNeedsReauth: store.markNeedsReauth
        )
        XCTAssertEqual(result.accessToken, "NEW_ACCESS")
        XCTAssertEqual(store.markCount, 0, "must NOT mark a token another request rotated")
    }

    func testInvalidGrant_noRotation_marksForReauth() {
        let store = FakeStore()
        store.stored = storage(access: "OLD_ACCESS", refresh: "OLD_REFRESH")
        XCTAssertThrowsError(try KimiOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: "OLD_REFRESH",
            existingStorage: store.stored!,
            error: invalidGrant(),
            isFatal: KimiOAuthRefreshCoordinator.isRefreshTokenInvalid,
            loadCurrent: store.load,
            markNeedsReauth: store.markNeedsReauth
        ))
        XCTAssertEqual(store.markCount, 1, "genuine invalid_grant must mark for re-login, keeping credentials")
    }

    func testTransientFailure_keepsExistingValidToken() throws {
        let store = FakeStore()
        store.stored = storage(access: "TOKEN", refresh: "R", expiresInMinutes: 30)
        let result = try KimiOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: "R",
            existingStorage: store.stored!,
            error: networkError(),
            isFatal: KimiOAuthRefreshCoordinator.isRefreshTokenInvalid,
            loadCurrent: store.load,
            markNeedsReauth: store.markNeedsReauth
        )
        XCTAssertEqual(result.accessToken, "TOKEN")
        XCTAssertEqual(store.markCount, 0)
    }

    func testTransientFailure_butExpired_throws() {
        let store = FakeStore()
        let expired = KimiTokenStorage(accessToken: "T", refreshToken: "R",
                                       expireDate: Date().addingTimeInterval(-60),
                                       deviceId: "D", lastRefresh: nil)
        store.stored = expired
        XCTAssertThrowsError(try KimiOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: "R", existingStorage: expired, error: networkError(),
            isFatal: KimiOAuthRefreshCoordinator.isRefreshTokenInvalid,
            loadCurrent: store.load, markNeedsReauth: store.markNeedsReauth
        ))
        XCTAssertEqual(store.markCount, 0, "transient failure never marks, even when expired")
    }

    // MARK: - Device-flow poll classification (RFC 8628 §3.5)

    func testPoll_success() {
        let out = KimiDeviceFlow.classifyPoll(
            json: ["access_token": "AT", "refresh_token": "RT", "expires_in": 3600.0],
            httpOK: true)
        XCTAssertEqual(out, .success(accessToken: "AT", refreshToken: "RT", expiresIn: 3600))
    }
    func testPoll_pending() {
        XCTAssertEqual(KimiDeviceFlow.classifyPoll(json: ["error": "authorization_pending"], httpOK: false), .pending)
    }
    func testPoll_slowDown_bumpsInterval() {
        XCTAssertEqual(KimiDeviceFlow.classifyPoll(json: ["error": "slow_down"], httpOK: false), .slowDown)
        XCTAssertEqual(KimiDeviceFlow.bumpedInterval(5), 10)
    }
    func testPoll_denied() {
        guard case .denied = KimiDeviceFlow.classifyPoll(json: ["error": "access_denied"], httpOK: false) else {
            return XCTFail("expected denied")
        }
    }
    func testPoll_expired() {
        guard case .expired = KimiDeviceFlow.classifyPoll(json: ["error": "expired_token"], httpOK: false) else {
            return XCTFail("expected expired")
        }
    }
    func testPoll_unknownErrorIsFatal() {
        guard case .fatal = KimiDeviceFlow.classifyPoll(json: ["error": "teapot"], httpOK: false) else {
            return XCTFail("expected fatal")
        }
    }

    // MARK: - Device authorization parsing

    func testParseDeviceAuthorization_prefersCompleteURL() throws {
        let auth = try KimiDeviceFlow.parseDeviceAuthorization([
            "device_code": "DC", "user_code": "ABCD-1234",
            "verification_uri": "https://kimi.com/device",
            "verification_uri_complete": "https://kimi.com/device?code=ABCD-1234",
            "interval": 5.0, "expires_in": 900.0,
        ])
        XCTAssertEqual(auth.userCode, "ABCD-1234")
        XCTAssertEqual(auth.openURL, "https://kimi.com/device?code=ABCD-1234")
    }

    func testParseDeviceAuthorization_missingFieldsThrows() {
        XCTAssertThrowsError(try KimiDeviceFlow.parseDeviceAuthorization(["user_code": "X"]))
    }
}
