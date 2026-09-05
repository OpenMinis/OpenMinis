import Foundation
import XCTest

final class CodexOAuthTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func jwt(_ claims: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: claims)
        let payload = data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        return "e30.\(payload).test-signature"
    }

    private func response(_ fields: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: fields)
    }

    func testNamespaceClaimsAndAccessExpiryWithoutExpiresIn() throws {
        let access = try jwt(["exp": now.timeIntervalSince1970 + 3600])
        let id = try jwt(["https://api.openai.com/auth": ["chatgpt_account_id": "workspace-1", "chatgpt_plan_type": "pro"]])
        let token = try CodexOAuthTokenParser.parse(response(["access_token": access, "id_token": id]), now: now)
        XCTAssertEqual(token.accountId, "workspace-1")
        XCTAssertEqual(token.planType, "pro")
        XCTAssertEqual(token.expireDate, now.addingTimeInterval(3600))
    }

    func testLegacyClaimsAndEarlierExpiryWin() throws {
        let access = try jwt(["exp": now.timeIntervalSince1970 + 600, "chatgpt_account_id": "legacy"])
        let token = try CodexOAuthTokenParser.parse(response(["access_token": access, "expires_in": 3600]), now: now)
        XCTAssertEqual(token.accountId, "legacy")
        XCTAssertEqual(token.expireDate, now.addingTimeInterval(600))
    }

    func testRefreshKeepsIdentityAndRotatedCredentials() throws {
        let old = CodexTokenStorage(accessToken: "old", refreshToken: "refresh-1", idToken: "id-1",
                                    expireDate: now, lastRefresh: now, accountId: "account-1", planType: "pro")
        let retained = try CodexOAuthTokenParser.parse(response(["access_token": "new", "expires_in": 3600]), previous: old, now: now)
        XCTAssertEqual(retained.refreshToken, "refresh-1")
        XCTAssertEqual(retained.idToken, "id-1")
        XCTAssertEqual(retained.accountId, "account-1")
        XCTAssertEqual(retained.planType, "pro")
        let rotated = try CodexOAuthTokenParser.parse(response(["access_token": "next", "refresh_token": "refresh-2"]), previous: retained, now: now)
        XCTAssertEqual(rotated.refreshToken, "refresh-2")
    }

    func testRefreshCannotChangeAccount() throws {
        let old = CodexTokenStorage(accessToken: "old", refreshToken: "refresh", idToken: nil,
                                    expireDate: now, lastRefresh: now, accountId: "account-1", planType: nil)
        let changed = try jwt(["https://api.openai.com/auth": ["chatgpt_account_id": "account-2"]])
        XCTAssertThrowsError(try CodexOAuthTokenParser.parse(response(["access_token": changed]), previous: old, now: now))
    }

    func testExistingKeychainEntryRecoversJWTExpiry() throws {
        let access = try jwt(["exp": now.timeIntervalSince1970 + 60])
        let old = CodexTokenStorage(accessToken: access, refreshToken: "refresh", idToken: nil,
                                    expireDate: nil, lastRefresh: now, accountId: nil, planType: nil)
        XCTAssertTrue(old.needsRefresh(at: now, buffer: 300))
        XCTAssertFalse(old.needsRefresh(at: now.addingTimeInterval(-3600), buffer: 300))
        let opaque = CodexTokenStorage(accessToken: "opaque", refreshToken: "refresh", idToken: nil,
                                       expireDate: nil, lastRefresh: now, accountId: nil, planType: nil)
        XCTAssertTrue(opaque.needsRefresh(at: now.addingTimeInterval(8 * 24 * 3600)))
    }

    func testEmptyAccessTokenRejectedAndIDExpiryNotUsed() throws {
        XCTAssertThrowsError(try CodexOAuthTokenParser.parse(response(["access_token": "   "]), now: now))
        let id = try jwt(["exp": 1])
        let token = try CodexOAuthTokenParser.parse(response(["access_token": "opaque", "id_token": id]), now: now)
        XCTAssertNil(token.expireDate)
    }

    func testFormEncodingPreservesPlusAmpersandAndPercent() {
        let encoded = String(data: CodexOAuthTokenParser.formBody(["code": "a+b&c=1% x", "redirect_uri": "http://localhost:1455/auth/callback"]), encoding: .utf8)
        XCTAssertEqual(encoded, "code=a%2Bb%26c%3D1%25%20x&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback")
    }

    func testStructuredRefreshErrorsDoNotLeakTokensOrDeleteOnWAF() throws {
        let data = try response(["error": ["code": "refresh_token_reused", "message": "secret-token-do-not-log"]])
        XCTAssertEqual(CodexOAuthTokenParser.sanitizedErrorCode(data), "refresh_token_reused")
        XCTAssertTrue(CodexOAuthTokenParser.refreshFailureIsFatal(status: 400, code: "refresh_token_reused"))
        XCTAssertFalse(CodexOAuthTokenParser.refreshFailureIsFatal(status: 403, code: nil))
        XCTAssertFalse(CodexOAuthTokenParser.refreshFailureIsFatal(status: 400, code: "invalid_request"))
        XCTAssertFalse(CodexOAuthTokenParser.refreshFailureIsFatal(status: 503, code: "temporarily_unavailable"))
    }

    func testCodexCatalogDiscoversAstraAndFutureIDs() throws {
        // Protocol fixtures, not a claim that this account has these entitlements.
        let data = try response(["models": [
            ["slug": "gpt-6-astra", "display_name": "GPT-6 Astra", "visibility": "list", "priority": 1,
             "context_window": 272000, "max_context_window": 872000, "supported_in_api": false,
             "input_modalities": ["text", "image"], "supported_reasoning_levels": [["effort": "low"], ["effort": "ultra"]]],
            ["slug": "future-model-fixture", "display_name": "Future", "visibility": "list", "priority": 2],
            ["slug": "hidden", "visibility": "hide"],
            ["slug": "gpt-6-astra", "visibility": "list", "priority": 3],
        ]])
        let models = try CodexModelCatalog.parse(data)
        XCTAssertEqual(models.map(\.id), ["gpt-6-astra", "future-model-fixture"])
        XCTAssertEqual(models[0].contextWindow, 272000)
        XCTAssertEqual(models[0].reasoningEfforts, ["low", "ultra"])
        XCTAssertEqual(models[0].inputModalities, ["text", "image"])
        XCTAssertNil(models[1].reasoningEfforts)
        XCTAssertEqual(CodexModelCatalog.modelsURL.host, "chatgpt.com")
        XCTAssertEqual(URLComponents(url: CodexModelCatalog.modelsURL, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "0.153.4")
    }

    func testPublicAPICatalogCannotMasqueradeAsOAuthCatalog() throws {
        XCTAssertThrowsError(try CodexModelCatalog.parse(response(["data": [["id": "gpt-6-astra"]]])))
    }

    func testReasoningUsesDeclaredLevelsIncludingUltra() {
        XCTAssertEqual(CodexModelCatalog.resolvedEffort(requested: "ultra", supported: ["low", "high", "ultra"]), "ultra")
        XCTAssertEqual(CodexModelCatalog.resolvedEffort(requested: "ultra", supported: ["low", "max"]), "max")
        XCTAssertEqual(CodexModelCatalog.resolvedEffort(requested: "low", supported: ["medium", "high"]), "medium")
        XCTAssertEqual(CodexModelCatalog.resolvedEffort(requested: "none", supported: ["low", "medium"]), "low")
        XCTAssertEqual(CodexModelCatalog.resolvedEffort(requested: "high", supported: nil), "high")
    }
}
