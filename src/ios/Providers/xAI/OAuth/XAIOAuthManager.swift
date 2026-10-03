import Foundation
import UIKit
import SafariServices
import CryptoKit

private let logger = AppLogger(category: "XAIOAuth")

@MainActor
final class XAIOAuthManager: NSObject, ObservableObject {

    static let shared = XAIOAuthManager()

    // MARK: - OAuth Config

    private let issuer = "https://auth.x.ai"
    private let discoveryURL = "https://auth.x.ai/.well-known/openid-configuration"
    private let clientID = "b1a00492-073a-47ea-816f-4c329264a828"
    /// xAI redirect_uri allow-list pins port 56121 — must not change.
    private let callbackPort: UInt16 = 56121
    private var redirectURI: String { "http://127.0.0.1:\(callbackPort)/callback" }
    private let scopes = "openid profile email offline_access grok-cli:access api:access"

    /// Domains accepted for the discovery-returned endpoints and CORS preflight Origin.
    private let trustedHosts: Set<String> = ["auth.x.ai", "accounts.x.ai"]

    private let refreshBuffer: TimeInterval = 5 * 60

    // MARK: - Published State

    @Published private(set) var isAuthenticating = false

    // MARK: - Private

    private var callbackServer: OAuthCallbackServer?
    private weak var safariVC: SFSafariViewController?

    /// [T-oauth-refresh-race] Instance-level single-flight for token refresh.
    private let refreshSingleFlight = OAuthRefreshSingleFlight<XAITokenStorage>()

    private override init() {
        super.init()
    }

    // MARK: - Public API

    func isAuthenticated(instanceId: String) -> Bool {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self)?.accessToken != nil
    }

    func maskedToken(instanceId: String) -> String? {
        guard let token = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self)?.accessToken else { return nil }
        return ClaudeOAuthManager.maskToken(token)
    }

    func email(instanceId: String) -> String? {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self)?.email
    }

    func displayName(instanceId: String) -> String? {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self)?.displayName
    }

    func accountId(instanceId: String) -> String? {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self)?.accountId
    }

    func login(instanceId: String) async throws {
        // Re-entrancy guard. SwiftUI button taps + sheet onAppear / onChange
        // hooks can fire the same Task twice in rapid succession; without
        // this guard each concurrent call generates its own PKCE+state,
        // both bind port 56121 via SO_REUSEPORT, and Safari's eventual GET
        // is resolved against ONE task's state but the OTHER task's
        // continuation — manifesting as "state mismatch" even though the
        // wire-level state values were byte-identical (T-xai-oauth-double-fire).
        guard !isAuthenticating else {
            logger.warning("xAI login already in progress — ignoring re-entrant call (instance: \(instanceId))")
            return
        }
        logger.info("=== xAI OAuth login started (instance: \(instanceId)) ===")
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

        // 0. Discover endpoints (validated against trusted hosts).
        let discovery = try await fetchDiscovery()
        logger.info("xAI discovery: authorize=\(discovery.authorizationEndpoint) token=\(discovery.tokenEndpoint)")

        let (verifier, challenge) = generateHexPKCE()
        let state = randomURLSafe(byteCount: 32)
        let nonce = randomURLSafe(byteCount: 32)
        logger.info("PKCE (hex) generated — verifier length: \(verifier.count), challenge: \(challenge.prefix(16))...")

        // 1. Start local HTTP server (with CORS-aware OPTIONS support).
        let server = OAuthCallbackServer(
            port: callbackPort,
            callbackPath: "/callback",
            optionsCORSAllowedHosts: trustedHosts
        )
        self.callbackServer = server
        try server.start()
        logger.info("Callback server started on port \(self.callbackPort)")

        // 2. Build authorization URL.
        var components = URLComponents(string: discovery.authorizationEndpoint)!
        components.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "scope", value: scopes),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "nonce", value: nonce),
            URLQueryItem(name: "plan", value: "generic"),
            URLQueryItem(name: "referrer", value: "minis"),
        ]
        let authorizationURL = components.url!
        logger.info("Authorization URL: \(authorizationURL.absoluteString)")

        // 3. Open in-app Safari.
        presentSafariViewController(url: authorizationURL)

        // 4. Wait for callback (5 min).
        logger.info("Waiting for callback...")
        let result = try await server.waitForCallback(timeout: 300)
        logger.info("Callback received — code length: \(result.code.count), state match: \(result.state == state)")

        // 5. Validate state.
        guard result.state == state else {
            logger.error("State mismatch!")
            throw LLMError.providerError(message: "xAI OAuth state mismatch")
        }

        // 6. Exchange code for token.
        logger.info("Exchanging code for token...")
        let token = try await exchangeCode(
            result.code,
            verifier: verifier,
            challenge: challenge,
            tokenEndpoint: discovery.tokenEndpoint
        )
        logger.info("Token exchange successful")

        ProviderKeychainHelper.saveOAuthToken(token, instanceId: instanceId)
        // isAuthenticated(instanceId:) is a plain Keychain read, not @Published —
        // emit objectWillChange so observing views (Provider Detail / AddProvider)
        // refresh their "Signed in" badge immediately on successful login.
        objectWillChange.send()
        logger.info("=== xAI OAuth login complete (instance: \(instanceId)) ===")
    }

    func logout(instanceId: String) {
        logger.info("Logout — clearing token (instance: \(instanceId))")
        ProviderKeychainHelper.deleteOAuthToken(instanceId: instanceId)
        objectWillChange.send()
    }

    func validAccessToken(instanceId: String) async throws -> String {
        guard var storage = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self) else {
            throw LLMError.invalidAPIKey(detail: "xAI: no OAuth token found for instance \(instanceId)")
        }

        let needsRefresh = storage.refreshToken != nil
            && (storage.expireDate.map({ $0.timeIntervalSinceNow <= refreshBuffer }) ?? false)

        if needsRefresh {
            storage = try await refreshTokenGuarded(instanceId: instanceId, existingStorage: storage)
        }

        guard !storage.accessToken.isEmpty else {
            throw LLMError.invalidAPIKey(detail: "xAI: access token is empty for instance \(instanceId)")
        }
        return storage.accessToken
    }

    /// [T-oauth-refresh-race-classify] Structured classification via the shared
    /// classifier. xAI rotates refresh tokens and rejects reuse, so
    /// `refresh_token_reused` is in the fatal set alongside the standard codes.
    private func isRefreshTokenInvalid(_ error: LLMError) -> Bool {
        OAuthRefreshErrorClassifier.isTokenInvalid(
            error,
            fatalErrorCodes: ["invalid_grant", "invalid_token", "invalid_request",
                              "unauthorized_client", "refresh_token_reused"]
        )
    }

    /// [T-oauth-refresh-race] Refresh under instance-level single-flight, with a
    /// compare-before-delete backstop on failure so a stale concurrent refresh
    /// never wipes a token another caller just rotated. Mirrors ClaudeOAuthManager.
    private func refreshTokenGuarded(instanceId: String, existingStorage: XAITokenStorage) async throws -> XAITokenStorage {
        let staleRefreshToken = existingStorage.refreshToken!
        let cachedEndpoint = existingStorage.tokenEndpoint
        do {
            return try await refreshSingleFlight.run(instanceId: instanceId) { [weak self] in
                guard let self else { throw LLMError.providerError(message: "OAuth manager deallocated") }
                logger.info("Refreshing xAI token on-demand (instance: \(instanceId))...")
                let refreshed = try await self.performRefresh(refreshToken: staleRefreshToken, cachedEndpoint: cachedEndpoint)
                ProviderKeychainHelper.saveOAuthToken(refreshed, instanceId: instanceId)
                return refreshed
            }
        } catch {
            return try OAuthRefreshCoordinator.resolveAfterRefreshFailure(
                providerName: "xAI",
                staleRefreshToken: staleRefreshToken,
                existingStorage: existingStorage,
                error: error,
                isFatal: { [weak self] in self?.isRefreshTokenInvalid($0) ?? false },
                loadCurrent: { ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: XAITokenStorage.self) },
                markNeedsReauth: { ProviderKeychainHelper.markOAuthNeedsReauth(instanceId: instanceId) },
                log: { logger.info($0) }
            )
        }
    }

    // MARK: - Discovery

    private struct Discovery {
        let authorizationEndpoint: String
        let tokenEndpoint: String
    }

    private func fetchDiscovery() async throws -> Discovery {
        let url = URL(string: discoveryURL)!
        let (data, response) = try await URLSession.shared.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard (200..<300).contains(status) else {
            throw LLMError.providerError(message: "xAI discovery failed: HTTP \(status)")
        }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard
            let auth = json["authorization_endpoint"] as? String,
            let token = json["token_endpoint"] as? String
        else {
            throw LLMError.providerError(message: "xAI discovery missing endpoints")
        }
        // Defense against open-redirect — endpoints must live on a trusted xAI host.
        try assertTrusted(urlString: auth)
        try assertTrusted(urlString: token)
        return Discovery(authorizationEndpoint: auth, tokenEndpoint: token)
    }

    private func assertTrusted(urlString: String) throws {
        guard
            let host = URL(string: urlString)?.host?.lowercased(),
            host.hasSuffix(".x.ai") || host == "x.ai"
        else {
            throw LLMError.providerError(message: "xAI OAuth discovery returned untrusted endpoint: \(urlString)")
        }
    }

    // MARK: - PKCE (hex verifier, base64url challenge)

    private func generateHexPKCE() -> (verifier: String, challenge: String) {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let verifier = bytes.map { String(format: "%02x", $0) }.joined()  // 64-char hex
        let hash = SHA256.hash(data: Data(verifier.utf8))
        let challenge = Data(hash).xaiBase64URLEncoded()
        return (verifier, challenge)
    }

    private func randomURLSafe(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).xaiBase64URLEncoded()
    }

    // MARK: - Token Exchange / Refresh

    private func exchangeCode(
        _ code: String,
        verifier: String,
        challenge: String,
        tokenEndpoint: String
    ) async throws -> XAITokenStorage {
        let body: [String: String] = [
            "grant_type": "authorization_code",
            "client_id": clientID,
            "code": code,
            "redirect_uri": redirectURI,
            "code_verifier": verifier,
            "code_challenge": challenge,           // xAI-specific
            "code_challenge_method": "S256",       // xAI-specific
        ]
        return try await postTokenRequest(
            body: body,
            endpoint: tokenEndpoint,
            previousRefreshToken: nil,
            context: "Token exchange"
        )
    }

    private func performRefresh(refreshToken: String, cachedEndpoint: String?) async throws -> XAITokenStorage {
        let endpoint: String
        if let cached = cachedEndpoint {
            endpoint = cached
        } else {
            endpoint = try await fetchDiscovery().tokenEndpoint
        }
        let body: [String: String] = [
            "grant_type": "refresh_token",
            "client_id": clientID,
            "refresh_token": refreshToken,
        ]
        return try await postTokenRequest(
            body: body,
            endpoint: endpoint,
            previousRefreshToken: refreshToken,
            context: "Token refresh"
        )
    }

    private func postTokenRequest(
        body: [String: String],
        endpoint: String,
        previousRefreshToken: String?,
        context: String
    ) async throws -> XAITokenStorage {
        var request = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = body.map { "\(urlEncode($0.key))=\(urlEncode($0.value))" }
            .joined(separator: "&")
            .data(using: .utf8)

        logger.info("\(context) POST \(endpoint)")

        let (data, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? "<binary>"
        logger.info("\(context) Response status: \(statusCode)")

        if statusCode == 403 {
            // [T-oauth-refresh-race-classify] Keep the human-readable subscription
            // hint, but embed the structured status so the classifier reads it
            // reliably.
            throw LLMError.providerError(message: "xAI API access denied. Verify your SuperGrok / X Premium+ subscription supports API access. " + OAuthRefreshErrorClassifier.makeErrorMessage(status: statusCode, body: responseBody))
        }
        guard (200..<300).contains(statusCode) else {
            #if DEBUG
            logger.error("\(context) FAILED — status \(statusCode): \(responseBody)")
            #else
            logger.error("\(context) FAILED — status \(statusCode)")
            #endif
            // [T-oauth-refresh-race-classify] Embed real HTTP status structurally.
            throw LLMError.providerError(message: "\(context) failed: " + OAuthRefreshErrorClassifier.makeErrorMessage(status: statusCode, body: responseBody))
        }
        return try parseTokenResponse(data, tokenEndpoint: endpoint, previousRefreshToken: previousRefreshToken)
    }

    private func urlEncode(_ s: String) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    private func parseTokenResponse(
        _ data: Data,
        tokenEndpoint: String,
        previousRefreshToken: String?
    ) throws -> XAITokenStorage {
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        guard let accessToken = json["access_token"] as? String else {
            throw LLMError.decodingError(underlying: NSError(domain: "XAIOAuth", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Missing access_token"]))
        }
        let refreshToken = (json["refresh_token"] as? String) ?? previousRefreshToken
        let expiresIn = json["expires_in"] as? TimeInterval
        let expireDate = expiresIn.map { Date().addingTimeInterval($0) }
        let idToken = json["id_token"] as? String

        var email: String?
        var displayName: String?
        var accountId: String?
        if let idToken {
            let claims = Self.decodeJWTPayload(idToken)
            email = claims?["email"] as? String
            displayName = (claims?["name"] as? String) ?? (claims?["preferred_username"] as? String)
            accountId = (claims?["sub"] as? String) ?? (claims?["account_id"] as? String)
            if let e = email { logger.info("Extracted email: \(e)") }
            if let aid = accountId { logger.info("Extracted accountId: \(aid)") }
        }

        return XAITokenStorage(
            accessToken: accessToken,
            refreshToken: refreshToken,
            idToken: idToken,
            expireDate: expireDate,
            lastRefresh: Date(),
            tokenEndpoint: tokenEndpoint,
            email: email,
            displayName: displayName,
            accountId: accountId
        )
    }

    private static func decodeJWTPayload(_ jwt: String) -> [String: Any]? {
        let segments = jwt.split(separator: ".")
        guard segments.count >= 2 else { return nil }
        var base64 = String(segments[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        guard let data = Data(base64Encoded: base64) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    // MARK: - In-App Safari

    private func presentSafariViewController(url: URL) {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var topVC = root
        while let presented = topVC.presentedViewController { topVC = presented }
        let vc = SFSafariViewController(url: url)
        // Observe user-driven dismissal (Done button / swipe-down) so we
        // can stop the loopback server immediately rather than letting
        // `waitForCallback` hang the full 5-min timeout. Without this,
        // `isAuthenticating` stays true and the re-entrancy guard blocks
        // every subsequent tap (T-xai-oauth-stop-resume).
        vc.delegate = self
        topVC.present(vc, animated: true)
        self.safariVC = vc
    }
}

extension XAIOAuthManager: SFSafariViewControllerDelegate {
    nonisolated func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            // Stopping the server resumes any in-flight waitForCallback
            // with a "cancelled" error, which lets the login()'s defer
            // run and resets `isAuthenticating` — so the user can tap
            // "Sign in with xAI" again.
            self.callbackServer?.stop()
        }
    }
}

private extension Data {
    func xaiBase64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
