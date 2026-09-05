import Foundation
import UIKit
import SafariServices
import CryptoKit
import os.log

private let logger = AppLogger(category: "CodexOAuth")

@MainActor
final class CodexOAuthManager: NSObject, ObservableObject {

    static let shared = CodexOAuthManager()

    // MARK: - OAuth Config

    private let authURL = "https://auth.openai.com/oauth/authorize"
    private let tokenURL = "https://auth.openai.com/oauth/token"
    private let clientID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private let callbackPort: UInt16 = 1455
    private var redirectURI: String { "http://localhost:\(callbackPort)/auth/callback" }
    private let scopes = "openid profile email offline_access"

    private let refreshBuffer: TimeInterval = 5 * 60

    // MARK: - Published State

    @Published private(set) var isAuthenticating = false

    // MARK: - Private

    private var callbackServer: OAuthCallbackServer?
    private weak var safariVC: SFSafariViewController?

    /// [T-oauth-refresh-race] Instance-level single-flight for token refresh.
    private let refreshSingleFlight = OAuthRefreshSingleFlight<CodexTokenStorage>()

    private override init() {
        super.init()
    }

    // MARK: - Public API (per-instance)

    func isAuthenticated(instanceId: String) -> Bool {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self)?.accessToken != nil
    }

    func maskedToken(instanceId: String) -> String? {
        guard let token = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self)?.accessToken else { return nil }
        return ClaudeOAuthManager.maskToken(token)
    }

    func accountId(instanceId: String) -> String? {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self)?.resolvedAccountId
    }

    func planType(instanceId: String) -> String? {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self)?.resolvedPlanType
    }

    func login(instanceId: String) async throws {
        guard !isAuthenticating else {
            throw LLMError.providerError(message: "An OpenAI login is already in progress")
        }
        logger.info("=== Codex OAuth login started (instance: \(instanceId)) ===")
        // Defensive cleanup: stop any leftover server from a previous failed attempt
        callbackServer?.stop()
        callbackServer = nil
        isAuthenticating = true
        defer {
            isAuthenticating = false
            callbackServer?.stop()
            callbackServer = nil
            safariVC?.dismiss(animated: true)
            safariVC = nil
        }

        let (verifier, challenge) = generatePKCE()
        let state = generateState()
        logger.info("PKCE generated")

        // 1. Start local HTTP server
        let server = OAuthCallbackServer(port: callbackPort, callbackPath: "/auth/callback")
        self.callbackServer = server
        try server.start()
        logger.info("Callback server started on port \(self.callbackPort)")

        // 2. Build authorization URL
        var components = URLComponents(string: authURL)!
        components.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "scope", value: scopes),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "id_token_add_organizations", value: "true"),
            URLQueryItem(name: "codex_cli_simplified_flow", value: "true"),
            URLQueryItem(name: "originator", value: "codex_cli_rs"),
        ]
        let authorizationURL = components.url!
        logger.info("Opening OpenAI authorization page")

        // 3. Open in-app Safari
        presentSafariViewController(url: authorizationURL)

        // 4. Wait for callback (5 min timeout)
        logger.info("Waiting for callback...")
        let result = try await server.waitForCallback(timeout: 300)
        logger.info("Callback received — code length: \(result.code.count), state match: \(result.state == state)")

        // 5. Validate state
        guard result.state == state else {
            logger.error("State mismatch!")
            throw LLMError.providerError(message: "OAuth state mismatch")
        }

        // 6. Exchange code for token
        logger.info("Exchanging code for token...")
        let token = try await exchangeCode(result.code, verifier: verifier)
        logger.info("Token exchange successful")

        ProviderKeychainHelper.saveOAuthToken(token, instanceId: instanceId)
        logger.info("=== Codex OAuth login complete (instance: \(instanceId)) ===")
    }

    func logout(instanceId: String) {
        logger.info("Logout — clearing token (instance: \(instanceId))")
        ProviderKeychainHelper.deleteOAuthToken(instanceId: instanceId)
    }

    func validAccessToken(instanceId: String) async throws -> String {
        guard var storage = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self) else {
            throw LLMError.invalidAPIKey(detail: "Codex: no OAuth token found for instance \(instanceId)")
        }

        let needsRefresh = storage.needsRefresh(buffer: refreshBuffer)

        if needsRefresh {
            storage = try await refreshTokenGuarded(instanceId: instanceId, existingStorage: storage)
        }

        guard !storage.isExpired else {
            throw LLMError.invalidAPIKey(detail: "Codex: token expired; sign in again")
        }
        guard let token = Optional(storage.accessToken), !token.isEmpty else {
            throw LLMError.invalidAPIKey(detail: "Codex: access token is empty for instance \(instanceId)")
        }
        return token
    }

    /// Returns true when the refresh error indicates the refresh token itself
    /// is invalid (revoked, expired, already used) vs a transient network issue.
    /// [T-oauth-refresh-race-classify] Structured classification via the shared
    /// classifier. Codex (ChatGPT OAuth) rotates + rejects reused refresh tokens.
    /// The old bare `refresh_token` substring is dropped: it was the widest
    /// false-positive source (matched benign mentions like refresh_token_expiry);
    /// the precise `invalid_grant` / `refresh_token_reused` codes + auth statuses
    /// cover real revocation without it.
    private func isRefreshTokenInvalid(_ error: LLMError) -> Bool {
        guard case .providerError(let message) = error else { return false }
        return CodexOAuthTokenParser.refreshFailureIsFatal(
            status: OAuthRefreshErrorClassifier.parseStatus(from: message),
            code: OAuthRefreshErrorClassifier.parseErrorCode(fromBody: message)
        )
    }

    /// [T-oauth-refresh-race] Refresh under instance-level single-flight, with a
    /// compare-before-delete backstop on failure so a stale concurrent refresh
    /// never wipes a token another caller just rotated. Mirrors ClaudeOAuthManager.
    private func refreshTokenGuarded(instanceId: String, existingStorage: CodexTokenStorage) async throws -> CodexTokenStorage {
        let staleRefreshToken = existingStorage.refreshToken!
        do {
            return try await refreshSingleFlight.run(instanceId: instanceId) { [weak self] in
                guard let self else { throw LLMError.providerError(message: "OAuth manager deallocated") }
                guard let before = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self) else {
                    throw CancellationError()
                }
                guard before == existingStorage else { return before }
                logger.info("Refreshing Codex token on-demand (instance: \(instanceId))...")
                let refreshed = try await self.performRefresh(existingStorage: existingStorage)
                // A logout or new login during the network round trip wins.
                guard let current = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self) else {
                    throw CancellationError()
                }
                guard current == existingStorage else { return current }
                ProviderKeychainHelper.saveOAuthToken(refreshed, instanceId: instanceId)
                return refreshed
            }
        } catch {
            if error is CancellationError { throw error }
            guard ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self) != nil else {
                throw LLMError.invalidAPIKey(detail: "Codex: signed out during refresh")
            }
            return try OAuthRefreshCoordinator.resolveAfterRefreshFailure(
                providerName: "Codex",
                staleRefreshToken: staleRefreshToken,
                existingStorage: existingStorage,
                error: error,
                isFatal: { [weak self] in self?.isRefreshTokenInvalid($0) ?? false },
                loadCurrent: { ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CodexTokenStorage.self) },
                deleteCredentials: { ProviderKeychainHelper.deleteOAuthToken(instanceId: instanceId) },
                log: { logger.info($0) }
            )
        }
    }

    // MARK: - Legacy singleton Keychain (for migration)

    static let legacyKeychainService = "com.openminis.app.openai-oauth"
    static let legacyKeychainAccount = "token"

    static func loadLegacyToken() -> CodexTokenStorage? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: legacyKeychainService,
            kSecAttrAccount as String: legacyKeychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(CodexTokenStorage.self, from: data)
    }

    static func deleteLegacyToken() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: legacyKeychainService,
            kSecAttrAccount as String: legacyKeychainAccount,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - PKCE

    private func generatePKCE() -> (verifier: String, challenge: String) {
        var bytes = [UInt8](repeating: 0, count: 64)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let verifier = Data(bytes).base64URLEncodedCodex()

        let hash = SHA256.hash(data: Data(verifier.utf8))
        let challenge = Data(hash).base64URLEncodedCodex()

        return (verifier, challenge)
    }

    private func generateState() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64URLEncodedCodex()
    }

    // MARK: - Token Exchange

    private func exchangeCode(_ code: String, verifier: String) async throws -> CodexTokenStorage {
        let body: [String: String] = [
            "grant_type": "authorization_code",
            "client_id": clientID,
            "code": code,
            "redirect_uri": redirectURI,
            "code_verifier": verifier,
        ]

        return try await postTokenRequest(body: body, context: "Token exchange")
    }

    private func performRefresh(existingStorage: CodexTokenStorage) async throws -> CodexTokenStorage {
        guard let refreshToken = existingStorage.refreshToken, !refreshToken.isEmpty else {
            throw LLMError.invalidAPIKey(detail: "Codex: missing refresh token")
        }
        let body: [String: String] = [
            "grant_type": "refresh_token",
            "client_id": clientID,
            "refresh_token": refreshToken,
        ]
        return try await postTokenRequest(body: body, context: "Token refresh", previous: existingStorage)
    }

    private func postTokenRequest(body: [String: String], context: String, previous: CodexTokenStorage? = nil) async throws -> CodexTokenStorage {
        var request = URLRequest(url: URL(string: tokenURL)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 30
        request.httpBody = CodexOAuthTokenParser.formBody(body)

        logger.info("\(context) POST \(self.tokenURL)")

        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let statusCode = http?.statusCode ?? -1
        let responseBody = "{\"error\":\"\(CodexOAuthTokenParser.sanitizedErrorCode(data))\"}"

        logger.info("\(context) Response status: \(statusCode)")

        guard (200..<300).contains(statusCode) else {
            #if DEBUG
            logger.error("\(context) FAILED — status \(statusCode): \(responseBody)")
            #else
            logger.error("\(context) FAILED — status \(statusCode)")
            #endif
            // [T-oauth-refresh-race-classify] Embed real HTTP status structurally.
            throw LLMError.providerError(message: "\(context) failed: " + OAuthRefreshErrorClassifier.makeErrorMessage(status: statusCode, body: responseBody))
        }

        return try CodexOAuthTokenParser.parse(data, previous: previous)
    }

    static func decodeJWTPayload(_ jwt: String) -> [String: Any]? {
        CodexOAuthTokenParser.decodeJWTPayload(jwt)
    }

    // MARK: - In-App Safari

    private func presentSafariViewController(url: URL) {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var topVC = root
        while let presented = topVC.presentedViewController { topVC = presented }
        let vc = SFSafariViewController(url: url)
        topVC.present(vc, animated: true)
        self.safariVC = vc
    }
}

extension CodexTokenStorage: RefreshableOAuthToken {}

// MARK: - Helpers

private extension Data {
    func base64URLEncodedCodex() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
