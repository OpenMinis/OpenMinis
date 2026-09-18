import Foundation
import UIKit
import SafariServices
import CryptoKit
import os.log

private let logger = AppLogger(category: "ClaudeOAuth")

@MainActor
final class ClaudeOAuthManager: NSObject, ObservableObject {

    static let shared = ClaudeOAuthManager()

    // MARK: - OAuth Config

    private let authURL = "https://claude.ai/oauth/authorize"
    // https://github.com/anthropics/anthropic-sdk-swift/issues/243 reports
    // that tokens issued against console.anthropic.com get silently
    // demoted to pay-per-use pricing; claude.ai is the canonical endpoint.
    // Also: `claude.ai` is the route that proxies through Cloudflare so
    // that session state (device bindings / IP allowlists) stays in-sync
    // with the web UI. Keep in lockstep with sub2api FullClaudeCodeMimicry.
    private let tokenURL = "https://claude.ai/v1/oauth/token"
    private let clientID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private let callbackPort: UInt16 = 54545
    private var redirectURI: String { "http://localhost:\(callbackPort)/callback" }
    private let scopes = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"

    private let refreshBuffer: TimeInterval = 5 * 60

    // MARK: - Published State

    @Published private(set) var isAuthenticating = false

    // MARK: - Private

    private var callbackServer: OAuthCallbackServer?
    private weak var safariVC: SFSafariViewController?

    /// [T-oauth-refresh-race] Single-flight dedup: at most one in-flight refresh
    /// per provider instance. Anthropic rotates the refresh token on every
    /// refresh, so two concurrent refreshes (e.g. parallel tool calls in one
    /// turn) would fire with the SAME refresh token — the first rotates it and
    /// writes new credentials, the second then hits `invalid_grant` on the now-
    /// stale token and (pre-fix) deleted the freshly-written token, logging the
    /// user out ~45 min after login. Concurrent callers await/reuse the same
    /// Task here instead. Keyed by instanceId; entry cleared when the task
    /// settles. Safe without a lock: @MainActor serializes the check-and-insert
    /// (both happen before the first `await`).
    private var inFlightRefreshes: [String: Task<ClaudeTokenStorage, Error>] = [:]

    private override init() {
        super.init()
    }

    // MARK: - Public API (per-instance)

    func isAuthenticated(instanceId: String) -> Bool {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: ClaudeTokenStorage.self)?.accessToken != nil
    }

    func maskedToken(instanceId: String) -> String? {
        guard let token = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: ClaudeTokenStorage.self)?.accessToken else { return nil }
        return Self.maskToken(token)
    }

    static func maskToken(_ token: String) -> String {
        let len = token.count
        guard len > 4 else { return String(repeating: "*", count: len) }
        let edge = min(10, len / 3)
        return "\(token.prefix(edge))\(String(repeating: "*", count: 10))\(token.suffix(edge))"
    }

    func login(instanceId: String) async throws {
        logger.info("=== OAuth login started (instance: \(instanceId)) ===")
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
        logger.info("PKCE generated — verifier length: \(verifier.count), challenge: \(challenge.prefix(16))...")
        logger.info("State: \(state.prefix(16))...")

        // 1. Start local HTTP server
        let server = OAuthCallbackServer(port: callbackPort)
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
        ]
        let authorizationURL = components.url!
        logger.info("Authorization URL: \(authorizationURL.absoluteString)")

        // 3. Open in-app Safari
        presentSafariViewController(url: authorizationURL)
        logger.info("Opened in-app Safari for authorization")

        // 4. Wait for callback (5 min timeout)
        logger.info("Waiting for callback...")
        let result = try await server.waitForCallback(timeout: 300)
        logger.info("Callback received — code length: \(result.code.count), state match: \(result.state == state)")

        // 5. Validate state
        guard result.state == state else {
            logger.error("State mismatch! expected=\(state.prefix(16))... got=\(result.state?.prefix(16) ?? "nil")")
            throw LLMError.providerError(message: "OAuth state mismatch")
        }

        // 6. Exchange code for token
        logger.info("Exchanging code for token...")
        let token = try await exchangeCode(result.code, verifier: verifier, state: state)
        #if DEBUG
        logger.info("Token exchange successful — access_token prefix: \(token.accessToken.prefix(16))...")
        #else
        logger.info("Token exchange successful")
        #endif
        logger.info("Has refresh_token: \(token.refreshToken != nil), expires: \(String(describing: token.expireDate))")

        ProviderKeychainHelper.saveOAuthToken(token, instanceId: instanceId)
        logger.info("=== OAuth login complete (instance: \(instanceId)) ===")
    }

    func logout(instanceId: String) {
        logger.info("Logout — clearing token (instance: \(instanceId))")
        ProviderKeychainHelper.deleteOAuthToken(instanceId: instanceId)
    }

    func validAccessToken(instanceId: String) async throws -> String {
        guard var storage = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: ClaudeTokenStorage.self) else {
            throw LLMError.invalidAPIKey(detail: "Claude: no OAuth token found for instance \(instanceId)")
        }

        let needsRefresh = storage.refreshToken != nil
            && (storage.expireDate.map({ $0.timeIntervalSinceNow <= refreshBuffer }) ?? false)

        if needsRefresh {
            storage = try await refreshSingleFlight(
                instanceId: instanceId,
                staleRefreshToken: storage.refreshToken!,
                existingStorage: storage,
                perform: { [weak self] token in
                    guard let self else { throw LLMError.providerError(message: "OAuth manager deallocated") }
                    return try await self.performRefresh(refreshToken: token)
                }
            )
        }

        guard let token = Optional(storage.accessToken), !token.isEmpty else {
            throw LLMError.invalidAPIKey(detail: "Claude: access token is empty for instance \(instanceId)")
        }
        return token
    }

    /// [T-oauth-refresh-race] Runs the refresh under instance-level single-flight
    /// and applies compare-before-delete on `invalid_grant`. Extracted so the
    /// concurrency + delete-guard logic is unit-testable with an injected
    /// `perform` (no real network / no URLSession.shared dependency).
    ///
    /// - `staleRefreshToken`: the refresh token this caller observed as current.
    /// - `existingStorage`: the caller's already-loaded storage (used to decide
    ///   whether a still-valid access token can survive a transient failure).
    /// - `perform`: does the actual token-endpoint round trip for a refresh token.
    ///
    /// Returns the storage to use going forward (freshly rotated, or — when a
    /// concurrent winner already rotated — whatever is now persisted).
    func refreshSingleFlight(
        instanceId: String,
        staleRefreshToken: String,
        existingStorage: ClaudeTokenStorage,
        perform: @escaping (String) async throws -> ClaudeTokenStorage
    ) async throws -> ClaudeTokenStorage {
        // Join an existing in-flight refresh for this instance if one is running,
        // so N concurrent callers only ever fire ONE network refresh. The
        // check-and-insert below is atomic on the MainActor (no await between).
        if let existing = inFlightRefreshes[instanceId] {
            logger.info("Joining in-flight refresh (instance: \(instanceId))")
            do {
                return try await existing.value
            } catch {
                // The winner failed; fall through to re-evaluate from current
                // persisted state (it may still have written a usable token, or
                // the failure may be genuinely fatal — handled uniformly below).
                return try Self.resolveAfterRefreshFailure(
                    instanceId: instanceId,
                    staleRefreshToken: staleRefreshToken,
                    existingStorage: existingStorage,
                    error: error,
                    isFatal: { [weak self] in self?.isRefreshTokenInvalid($0) ?? false }
                )
            }
        }

        logger.info("Refreshing token on-demand (instance: \(instanceId))...")
        let task = Task<ClaudeTokenStorage, Error> {
            let refreshed = try await perform(staleRefreshToken)
            ProviderKeychainHelper.saveOAuthToken(refreshed, instanceId: instanceId)
            return refreshed
        }
        inFlightRefreshes[instanceId] = task
        defer { inFlightRefreshes[instanceId] = nil }

        do {
            return try await task.value
        } catch {
            return try Self.resolveAfterRefreshFailure(
                instanceId: instanceId,
                staleRefreshToken: staleRefreshToken,
                existingStorage: existingStorage,
                error: error,
                isFatal: { [weak self] in self?.isRefreshTokenInvalid($0) ?? false }
            )
        }
    }

    /// [T-oauth-refresh-race] Thin wrapper over the pure
    /// `ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure` (compare-before-
    /// delete). Wires the real keychain I/O and this manager's logger; the
    /// decision logic itself lives in the UIKit-free coordinator so it's unit-
    /// tested directly.
    private static func resolveAfterRefreshFailure(
        instanceId: String,
        staleRefreshToken: String,
        existingStorage: ClaudeTokenStorage,
        error: Error,
        isFatal: (LLMError) -> Bool
    ) throws -> ClaudeTokenStorage {
        try ClaudeOAuthRefreshCoordinator.resolveAfterRefreshFailure(
            staleRefreshToken: staleRefreshToken,
            existingStorage: existingStorage,
            error: error,
            isFatal: isFatal,
            loadCurrent: { ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: ClaudeTokenStorage.self) },
            deleteCredentials: { ProviderKeychainHelper.deleteOAuthToken(instanceId: instanceId) },
            log: { logger.info($0) }
        )
    }

    /// Returns true when the refresh error indicates the refresh token itself
    /// is invalid (revoked, expired, already used) vs a transient network issue.
    /// [T-oauth-refresh-race-classify] Structured classification (HTTP status +
    /// parsed JSON `error` code) via the shared classifier — replaces the old
    /// substring match that could misread a body number/word as a fatal signal.
    private func isRefreshTokenInvalid(_ error: LLMError) -> Bool {
        OAuthRefreshErrorClassifier.isTokenInvalid(
            error,
            fatalErrorCodes: ["invalid_grant", "invalid_token", "invalid_request", "unauthorized_client"]
        )
    }

    // MARK: - Legacy singleton Keychain (for migration)

    static let legacyKeychainService = "com.openminis.app.claude-oauth"
    static let legacyKeychainAccount = "token"

    static func loadLegacyToken() -> ClaudeTokenStorage? {
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
        return try? JSONDecoder().decode(ClaudeTokenStorage.self, from: data)
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
        var bytes = [UInt8](repeating: 0, count: 96)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let verifier = Data(bytes).base64URLEncoded()

        let hash = SHA256.hash(data: Data(verifier.utf8))
        let challenge = Data(hash).base64URLEncoded()

        return (verifier, challenge)
    }

    private func generateState() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64URLEncoded()
    }

    // MARK: - Token Exchange

    private func exchangeCode(_ code: String, verifier: String, state: String) async throws -> ClaudeTokenStorage {
        let body: [String: String] = [
            "grant_type": "authorization_code",
            "client_id": clientID,
            "code": code,
            "redirect_uri": redirectURI,
            "code_verifier": verifier,
            "state": state,
        ]

        return try await postTokenRequest(body: body, context: "Token exchange")
    }

    private func performRefresh(refreshToken: String) async throws -> ClaudeTokenStorage {
        let body: [String: String] = [
            "grant_type": "refresh_token",
            "client_id": clientID,
            "refresh_token": refreshToken,
        ]

        var storage = try await postTokenRequest(body: body, context: "Token refresh")
        // Anthropic may not return a new refresh token on every refresh;
        // preserve the existing one so subsequent refreshes keep working.
        if storage.refreshToken == nil {
            storage = ClaudeTokenStorage(
                accessToken: storage.accessToken,
                refreshToken: refreshToken,
                expireDate: storage.expireDate,
                lastRefresh: storage.lastRefresh
            )
        }
        return storage
    }

    private func postTokenRequest(body: [String: String], context: String) async throws -> ClaudeTokenStorage {
        var request = URLRequest(url: URL(string: tokenURL)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Match the OAuthClient mimickry headers so the Anthropic OAuth
        // backend sees the same fingerprint as a real claude-cli request.
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        request.setValue(ClaudeCodeMimicry.betaHeaderValue(existing: nil), forHTTPHeaderField: "anthropic-beta")
        request.setValue(ClaudeCodeMimicry.userAgent, forHTTPHeaderField: "User-Agent")

        let jsonData = try JSONSerialization.data(withJSONObject: body)
        request.httpBody = jsonData

        logger.info("\(context) POST \(self.tokenURL)")
        logger.info("\(context) Content-Type: application/json")
        logger.info("\(context) Body keys: \(body.keys.sorted().joined(separator: ", "))")
        #if DEBUG
        if let bodyStr = String(data: jsonData, encoding: .utf8) {
            logger.debug("\(context) Body: \(bodyStr)")
        }
        #endif

        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let statusCode = http?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? "<binary>"

        logger.info("\(context) Response status: \(statusCode)")
        #if DEBUG
        logger.info("\(context) Response body: \(responseBody.prefix(500))")
        #endif

        guard (200..<300).contains(statusCode) else {
            #if DEBUG
            logger.error("\(context) FAILED — status \(statusCode): \(responseBody)")
            #else
            logger.error("\(context) FAILED — status \(statusCode)")
            #endif
            // [T-oauth-refresh-race-classify] Embed the real HTTP status so
            // isRefreshTokenInvalid classifies structurally, not by scraping
            // digits/substrings out of the body.
            throw LLMError.providerError(message: "\(context) failed: " + OAuthRefreshErrorClassifier.makeErrorMessage(status: statusCode, body: responseBody))
        }

        return try parseTokenResponse(data)
    }

    private func parseTokenResponse(_ data: Data) throws -> ClaudeTokenStorage {
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        guard let accessToken = json["access_token"] as? String else {
            logger.error("parseTokenResponse — missing access_token in: \(json.keys.joined(separator: ", "))")
            throw LLMError.decodingError(underlying: NSError(domain: "OAuth", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Missing access_token"]))
        }

        let refreshToken = json["refresh_token"] as? String
        let expiresIn = json["expires_in"] as? TimeInterval
        let expireDate = expiresIn.map { Date().addingTimeInterval($0) }

        return ClaudeTokenStorage(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expireDate: expireDate,
            lastRefresh: Date()
        )
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

// MARK: - Local HTTP Callback Server

struct OAuthCallbackResult {
    let code: String
    let state: String?
}

/// Minimal HTTP server on localhost that listens for the OAuth callback.
final class OAuthCallbackServer: @unchecked Sendable {

    private let port: UInt16
    private let callbackPath: String
    /// Lowercased hosts allowed to trigger a CORS preflight (OPTIONS) reply.
    /// Empty set → OPTIONS requests are answered with 404 (legacy behavior).
    private let optionsCORSAllowedHosts: Set<String>
    private var listenSocket: Int32 = -1
    private var continuation: CheckedContinuation<OAuthCallbackResult, Error>?
    private let queue = DispatchQueue(label: "oauth.callback.server")
    private var stopped = false

    init(
        port: UInt16,
        callbackPath: String = "/callback",
        optionsCORSAllowedHosts: Set<String> = []
    ) {
        self.port = port
        self.callbackPath = callbackPath
        self.optionsCORSAllowedHosts = Set(optionsCORSAllowedHosts.map { $0.lowercased() })
    }

    func start() throws {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else {
            throw LLMError.providerError(message: "Failed to create socket")
        }

        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &reuse, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY

        let bindResult = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                bind(fd, sockPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else {
            close(fd)
            throw LLMError.providerError(message: "Failed to bind port \(port): errno \(errno)")
        }

        guard listen(fd, 1) == 0 else {
            close(fd)
            throw LLMError.providerError(message: "Failed to listen on port \(port)")
        }

        self.listenSocket = fd
        logger.info("[Server] Listening on port \(self.port)")

        queue.async { [weak self] in
            self?.acceptLoop()
        }
    }

    func waitForCallback(timeout: TimeInterval) async throws -> OAuthCallbackResult {
        try await withCheckedThrowingContinuation { cont in
            self.continuation = cont

            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
                guard let self, !self.stopped else { return }
                self.stopped = true
                logger.error("[Server] Callback timeout after \(timeout)s")
                cont.resume(throwing: LLMError.providerError(message: "OAuth callback timeout"))
            }
        }
    }

    func stop() {
        // Close the listen socket OUTSIDE queue.sync. acceptLoop runs on
        // `queue` and blocks inside accept() until a connection arrives —
        // taking queue.sync from the outside while accept() is blocked
        // would deadlock the caller. Closing listenSocket here makes
        // accept() return -1 immediately, the loop's `!stopped &&
        // listenSocket >= 0` predicate fails, and the queue becomes free
        // for stop()'s critical section to run.
        let fdToClose = listenSocket
        listenSocket = -1
        if fdToClose >= 0 {
            close(fdToClose)
            logger.info("[Server] Stopped (listen socket closed)")
        }

        // Now serialize against handleCallback / waitForCallback's timeout,
        // both of which run on `queue`. Without this, the
        // SFSafariViewControllerDelegate path can race the in-flight
        // handleCallback: Safari dismisses itself the instant it follows
        // the redirect to http://127.0.0.1/.../callback, didFinish fires
        // on the main thread before the accept loop's handleCallback has
        // had a chance to set `stopped` and clear `continuation`, and
        // stop()'s wasStopped check sees stopped=false / continuation=set
        // so we throw "OAuth callback cancelled" even though the callback
        // actually succeeded. Running the rest of stop() inside queue.sync
        // forces it to wait until any in-flight handleCallback has
        // finished before reading the state
        // (T-oauth-status-premature-and-redirect).
        queue.sync {
            let wasStopped = stopped
            stopped = true
            // Resume any in-flight `waitForCallback` so the caller's defer
            // runs immediately (e.g. resetting `isAuthenticating`) instead of
            // hanging until the 5-minute timeout fires. Without this, a user
            // who dismisses Safari mid-flow leaves the manager in a stuck
            // "login in progress" state and the re-entrancy guard blocks
            // every subsequent button tap (T-xai-oauth-stop-resume).
            if !wasStopped, let cont = continuation {
                continuation = nil
                cont.resume(throwing: LLMError.providerError(message: "OAuth callback cancelled"))
            }
        }
    }

    // MARK: - Private

    private func acceptLoop() {
        while !stopped && listenSocket >= 0 {
            var clientAddr = sockaddr_in()
            var addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)

            let clientFd = withUnsafeMutablePointer(to: &clientAddr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                    accept(listenSocket, sockPtr, &addrLen)
                }
            }

            guard clientFd >= 0 else { break }
            logger.info("[Server] Accepted connection")
            handleConnection(clientFd)
        }
    }

    private func handleConnection(_ fd: Int32) {
        defer { close(fd) }

        var buffer = [UInt8](repeating: 0, count: 4096)
        let bytesRead = read(fd, &buffer, buffer.count)
        guard bytesRead > 0 else { return }

        let requestString = String(bytes: buffer[0..<bytesRead], encoding: .utf8) ?? ""
        let lines = requestString.split(separator: "\r\n", omittingEmptySubsequences: false)
        guard let firstLine = lines.first else { return }
        logger.info("[Server] Request: \(String(firstLine))")

        let parts = firstLine.split(separator: " ")
        guard parts.count >= 2 else { return }
        let method = String(parts[0]).uppercased()
        let path = String(parts[1])

        if method == "OPTIONS" {
            handleOptionsPreflight(fd: fd, headerLines: lines.dropFirst())
            return
        }

        if path.hasPrefix(callbackPath) {
            handleCallback(fd: fd, path: path)
        } else {
            let response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            _ = response.withCString { write(fd, $0, strlen($0)) }
        }
    }

    /// CORS preflight reply. Some OAuth providers (e.g. xAI auth.x.ai) probe the
    /// redirect_uri before navigating to it. We accept it only when the Origin
    /// belongs to an explicitly trusted host list passed at server init time.
    private func handleOptionsPreflight(fd: Int32, headerLines: ArraySlice<Substring>) {
        var origin: String?
        for line in headerLines {
            if line.isEmpty { break }
            let kv = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2 else { continue }
            if kv[0].trimmingCharacters(in: .whitespaces).lowercased() == "origin" {
                origin = kv[1].trimmingCharacters(in: .whitespaces)
                break
            }
        }
        let allowOrigin: String
        if !optionsCORSAllowedHosts.isEmpty,
           let originStr = origin,
           let originHost = URL(string: originStr)?.host?.lowercased(),
           optionsCORSAllowedHosts.contains(originHost) {
            allowOrigin = originStr
        } else {
            allowOrigin = "null"
        }
        let response = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: \(allowOrigin)\r\n" +
            "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Max-Age: 600\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        _ = response.withCString { write(fd, $0, strlen($0)) }
    }

    private func handleCallback(fd: Int32, path: String) {
        guard let components = URLComponents(string: "http://localhost\(path)") else {
            sendErrorPage(fd: fd, message: "Invalid callback URL")
            return
        }

        let queryItems = components.queryItems ?? []
        let code = queryItems.first(where: { $0.name == "code" })?.value
        let state = queryItems.first(where: { $0.name == "state" })?.value

        logger.info("[Server] Callback — code present: \(code != nil), state present: \(state != nil)")

        guard let code, !code.isEmpty else {
            let errorMsg = queryItems.first(where: { $0.name == "error" })?.value ?? "No code"
            logger.error("[Server] Callback error: \(errorMsg)")
            sendErrorPage(fd: fd, message: errorMsg)
            continuation?.resume(throwing: LLMError.providerError(message: "OAuth error: \(errorMsg)"))
            continuation = nil
            return
        }

        sendSuccessPage(fd: fd)

        stopped = true
        continuation?.resume(returning: OAuthCallbackResult(code: code, state: state))
        continuation = nil
    }

    private func sendSuccessPage(fd: Int32) {
        let html = """
        <!DOCTYPE html>
        <html>
        <head><meta charset="utf-8"><title>Authorization Successful</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            body { font-family: -apple-system, sans-serif; display: flex;
                   justify-content: center; align-items: center; height: 100vh;
                   margin: 0; background: #f5f5f7; }
            .card { text-align: center; padding: 40px; background: white;
                    border-radius: 16px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
            h1 { color: #1a1a1a; font-size: 24px; }
            p { color: #666; margin-top: 8px; }
        </style>
        </head>
        <body>
        <div class="card">
            <h1>Authorization Successful</h1>
            <p>You can close this tab and return to the app.</p>
        </div>
        </body>
        </html>
        """
        let response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(html.utf8.count)\r\nConnection: close\r\n\r\n\(html)"
        _ = response.withCString { write(fd, $0, strlen($0)) }
    }

    private func sendErrorPage(fd: Int32, message: String) {
        let html = "<html><body><h1>Authorization Failed</h1><p>\(message)</p></body></html>"
        let response = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(html.utf8.count)\r\nConnection: close\r\n\r\n\(html)"
        _ = response.withCString { write(fd, $0, strlen($0)) }
    }
}

// MARK: - Helpers

private extension Data {
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
