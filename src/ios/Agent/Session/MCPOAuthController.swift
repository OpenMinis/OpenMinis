//
//  MCPOAuthController.swift
//  MinisApp
//
//  [T-mcp-static-oauth] Native OAuth for MCP servers — Static mode (user-supplied
//  Client ID + optional Client Secret, PKCE Authorization Code flow).
//
//  Ownership split with the in-guest minis-mcp-cli:
//    • Native (this file): interactive authorization (ASWebAuthenticationSession),
//      token exchange, credential storage (Keychain, non-synchronizable), and
//      materializing the "token bridge" file the guest transport reads.
//    • Guest (transport/http.py): attaches Authorization from the bridge file,
//      refreshes autonomously via refresh_token when a call hits 401/expiry, and
//      rewrites the bridge file so both sides stay current.
//
//  Storage decisions (deliberate):
//    1. client_secret + tokens live in the LOCAL Keychain (kSecAttrSynchronizable
//       = false) — never in servers.json, so the iCloud MCPServerItem sync ships
//       only the non-secret oauth config (endpoints/clientId). A peer device
//       receives the server and simply shows "Not authorized" until the user
//       authorizes there.
//    2. The guest bridge file (<MinisConfig>/mcp-servers/oauth/<name>.json,
//       chmod 600) duplicates access/refresh tokens + client credentials so the
//       daemon can self-refresh while the app is backgrounded. It sits OUTSIDE
//       servers.json and outside every sync fingerprint — container-local only.
//       This is the same practical trust level as the pre-existing plaintext
//       `Authorization: Bearer <token>` headers in servers.json, minus the sync.
//

import Foundation
import AuthenticationServices
import CryptoKit
import SafariServices
import Security
import UIKit

/// Non-secret OAuth config stored inside servers.json (syncs with the server).
struct MCPOAuthConfig: Codable, Hashable {
    /// "static" = user-supplied client credentials (this implementation);
    /// "dynamic" reserved for discovery+DCR (design-only for now).
    var mode: String = "static"
    var clientId: String = ""
    var authorizationEndpoint: String = ""
    var tokenEndpoint: String = ""
    /// Space-separated scopes, e.g. "openid email https://www.googleapis.com/auth/calendar".
    var scopes: String?
    /// Custom redirect URI. Default minis-mcp://oauth/callback; Google installed
    /// apps need the reversed-client-id scheme (com.googleusercontent.apps.X:/oauth2redirect).
    var redirectURI: String?
}

@MainActor
final class MCPOAuthController: NSObject, ObservableObject {
    static let shared = MCPOAuthController()

    private let logger = AppLogger(category: "MCPOAuth")
    private var activeSession: ASWebAuthenticationSession?
    /// [T-mcp-oauth-loopback] Loopback path state (RFC 8252): local HTTP
    /// server + in-app Safari, same pattern as ClaudeOAuthManager /
    /// CodexOAuthManager. OAuthCallbackServer is the shared implementation
    /// from ClaudeOAuthManager.swift (internal — same target).
    private var callbackServer: OAuthCallbackServer?
    private weak var safariVC: SFSafariViewController?

    /// [T-mcp-oauth-loopback] Fixed loopback port for MCP OAuth. Chosen clear
    /// of the ports the other managers hold: Claude 54545, Codex 1455,
    /// Gemini 8085, Antigravity 8086, OpenRouter 3000, xAI 56121.
    static let loopbackPort: UInt16 = 54546
    /// Default redirect: loopback HTTP (Google "Web application"/"Desktop"
    /// clients accept http://localhost:*; custom schemes they reject).
    /// A custom-scheme redirect (e.g. reversed Google iOS client id) still
    /// works — authorize() auto-falls back to ASWebAuthenticationSession
    /// when the redirect URI isn't http(s)://localhost|127.0.0.1.
    static let defaultRedirectURI = "http://localhost:\(loopbackPort)/callback"

    enum OAuthError: LocalizedError {
        case badConfig(String)
        case cancelled
        /// [T-mcp-oauth-dcr] The user pressed Deny on the consent page. Kept
        /// apart from `exchangeFailed` so the UI can say something human
        /// instead of surfacing the raw `access_denied` OAuth code — a
        /// deliberate decision by the user is not a malfunction.
        case denied
        case exchangeFailed(String)

        var errorDescription: String? {
            switch self {
            case .badConfig(let m): return m
            case .cancelled: return AppLocalized("Authorization was cancelled.")
            case .denied: return AppLocalized("Access was not granted. You can try connecting again.")
            case .exchangeFailed(let m): return m
            }
        }
    }

    // MARK: - Keychain (non-synchronizable — secrets never ride iCloud)

    nonisolated private static let keychainService = "com.openminis.app.mcp-oauth"

    nonisolated private static func keychainSet(_ data: Data, account: String) {
        let match: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        var status = SecItemUpdate(match as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var add = match
            add.merge(attrs) { _, new in new }
            status = SecItemAdd(add as CFDictionary, nil)
        }
        if status != errSecSuccess {
            AppLogger(category: "MCPOAuth").error("[Keychain] save failed account=\(account) status=\(status)")
        }
    }

    nonisolated private static func keychainGet(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    nonisolated private static func keychainDelete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Client secret

    static func setClientSecret(_ secret: String, server: String) {
        let account = "\(server)#secret"
        let trimmed = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            keychainDelete(account: account)
        } else {
            keychainSet(Data(trimmed.utf8), account: account)
        }
    }

    static func clientSecret(server: String) -> String? {
        keychainGet(account: "\(server)#secret").flatMap { String(data: $0, encoding: .utf8) }
    }

    // MARK: - CLI-seeded client secret [T-mcp-cli-oauth-flags]

    /// `minis-mcp-cli add --oauth-client-secret` can't reach the Keychain from
    /// the guest, so it seeds <mcp-servers>/oauth/<name>.secret. Import it
    /// into the Keychain (the authority) and delete the file. Called when the
    /// server's edit form opens and before authorize — the seed is a handoff,
    /// not a storage location. Returns true when a seed was imported.
    @discardableResult
    static func importPendingSecretIfAny(server: String) -> Bool {
        let url = AIChatViewModel.minisMcpServersPersistentDir
            .appendingPathComponent("oauth", isDirectory: true)
            .appendingPathComponent("\(server).secret")
        guard let data = try? Data(contentsOf: url),
              let secret = String(data: data, encoding: .utf8)?
                  .trimmingCharacters(in: .whitespacesAndNewlines),
              !secret.isEmpty else { return false }
        setClientSecret(secret, server: server)
        try? FileManager.default.removeItem(at: url)
        AppLogger(category: "MCPOAuth").info("[SeedSecret] imported CLI-seeded client secret for '\(server)' into Keychain")
        return true
    }

    // MARK: - Token state

    struct StoredTokens: Codable {
        var accessToken: String
        var refreshToken: String?
        /// Epoch seconds; 0 = unknown.
        var expiresAt: TimeInterval
    }

    /// [T-ios-backup-credential-restore] Write tokens back from a backup.
    ///
    /// Narrow entry point so the importer doesn't need `keychainSet` (private)
    /// widened for everyone. Takes the already-decoded blob so the importer
    /// never has to know this type's storage layout.
    static func restoreTokens(_ tokens: StoredTokens, server: String) {
        guard let data = try? JSONEncoder().encode(tokens) else { return }
        keychainSet(data, account: "\(server)#tokens")
    }

    static func tokens(server: String) -> StoredTokens? {
        guard let data = keychainGet(account: "\(server)#tokens") else { return nil }
        return try? JSONDecoder().decode(StoredTokens.self, from: data)
    }

    static func isAuthorized(server: String) -> Bool {
        tokens(server: server) != nil
    }

    /// Sign out: drop Keychain tokens + the guest bridge file. Client secret is
    /// kept (it's configuration, not a session).
    static func signOut(server: String) {
        keychainDelete(account: "\(server)#tokens")
        try? FileManager.default.removeItem(at: bridgeFileURL(server: server))
    }

    /// Full cleanup on server delete: secret + tokens + bridge file.
    static func purge(server: String) {
        keychainDelete(account: "\(server)#secret")
        signOut(server: server)
    }

    // MARK: - RFC 8707 resource indicator [T-mcp-oauth-resource]

    /// Canonical MCP server URI for the `resource` parameter (RFC 8707 §2,
    /// required by the MCP authorization spec in both authorization and
    /// token requests): absolute URI, fragment stripped, no trailing slash.
    /// Returns nil for empty/scheme-less values — the parameter is then
    /// simply omitted (older configs keep working).
    static func canonicalResourceURI(_ raw: String?) -> String? {
        guard var s = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        if let hash = s.firstIndex(of: "#") { s = String(s[..<hash]) }
        while s.hasSuffix("/") { s.removeLast() }
        guard let url = URL(string: s), url.scheme != nil, url.host != nil else { return nil }
        return s
    }

    /// Resource URI for a configured server — reads the server's current
    /// `url` from MCPStore so authorize / bridge writes never disagree.
    private static func resourceURI(server: String) -> String? {
        canonicalResourceURI(MCPStore.shared.servers.first(where: { $0.id == server })?.url)
    }

    // MARK: - Guest bridge file

    /// Host URL of the guest-visible token bridge for `server`
    /// (bind-mounted at /var/minis/mcp-servers/oauth/<name>.json).
    nonisolated static func bridgeFileURL(server: String) -> URL {
        AIChatViewModel.minisMcpServersPersistentDir
            .appendingPathComponent("oauth", isDirectory: true)
            .appendingPathComponent("\(server).json")
    }

    /// Write the bridge file the guest transport reads. Includes refresh
    /// material so the daemon can renew tokens without the app's help.
    /// [T-mcp-bridge-unreachable issue #380] Throws when the bridge file cannot be
    /// written OR cannot be read back, so an authorization is never reported as
    /// successful while the guest is unable to see the token.
    ///
    /// The failure this guards against is silent by construction: the host writes
    /// into its App Group and reports success, while the sandbox reads
    /// `/var/minis/mcp-servers` — a symlink that a guest-created placeholder
    /// directory can shadow. Everything on the host side looks fine; the guest
    /// simply never finds a token and reports "not authorized" forever.
    private static func materializeBridge(server: String, oauth: MCPOAuthConfig, tokens: StoredTokens) throws {
        var obj: [String: Any] = [
            "access_token": tokens.accessToken,
            "expires_at": Int(tokens.expiresAt),
            "token_endpoint": oauth.tokenEndpoint,
            "client_id": oauth.clientId,
        ]
        if let rt = tokens.refreshToken { obj["refresh_token"] = rt }
        if let secret = clientSecret(server: server), !secret.isEmpty {
            obj["client_secret"] = secret
        }
        // [T-mcp-oauth-resource] Ship the canonical resource URI so the
        // guest's refresh_token grant sends the same RFC 8707 parameter
        // without recomputing or hardcoding it.
        if let resource = resourceURI(server: server) {
            obj["resource"] = resource
        }
        let log = AppLogger(category: "MCPOAuth")
        let url = bridgeFileURL(server: server)
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            let data = try JSONSerialization.data(withJSONObject: obj)
            try data.write(to: url, options: .atomic)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        } catch {
            log.error("[Bridge] write failed for '\(server)': \(error.localizedDescription)")
            throw OAuthError.exchangeFailed(
                AppLocalized("Could not save the authorization for the sandbox to read.")
                + " (\(error.localizedDescription))"
            )
        }

        // Read back what was just written. A write that "succeeded" into a
        // directory the guest cannot reach is the #380 failure mode, and it is
        // indistinguishable from success unless the bytes are verified here.
        do {
            let readBack = try Data(contentsOf: url)
            guard let parsed = try JSONSerialization.jsonObject(with: readBack) as? [String: Any],
                  (parsed["access_token"] as? String) == tokens.accessToken else {
                throw OAuthError.exchangeFailed(
                    AppLocalized("The saved authorization could not be verified.")
                )
            }
        } catch let e as OAuthError {
            log.error("[Bridge] verify failed for '\(server)': \(e.localizedDescription)")
            throw e
        } catch {
            log.error("[Bridge] read-back failed for '\(server)' at \(url.path): \(error.localizedDescription)")
            throw OAuthError.exchangeFailed(
                AppLocalized("The saved authorization could not be verified.")
                + " (\(error.localizedDescription))"
            )
        }
        // [T-mcp-bridge-guest-verify] The read-back above uses the host URL it
        // just wrote, so it cannot fail in the #380 state. Verify through the
        // guest-visible path in the rootfs data tree as well.
        let guestDir = RootfsManager.shared.dataPath
            .appendingPathComponent(String(AIChatViewModel.minisMcpServersLinuxDir.dropFirst()), isDirectory: true)
        if let problem = guestBridgeProblem(guestDir: guestDir, server: server, accessToken: tokens.accessToken) {
            log.error("[Bridge] guest cannot see the bridge for '\(server)': \(problem)")
            throw OAuthError.exchangeFailed(
                AppLocalized("The saved authorization could not be verified.") + " (\(problem))"
            )
        }
        log.info("[Bridge] materialized + verified for '\(server)' (expiresAt=\(Int(tokens.expiresAt)))")
    }

    /// [T-mcp-bridge-guest-verify] Why the guest cannot read the bridge file at
    /// `guestDir` (the host location of `/var/minis/mcp-servers`), or nil when
    /// it can. lstat, not stat: a guest-made real directory at that path is
    /// exactly the #380 shadow, and following links would hide the difference.
    /// A missing path is accepted: the rootfs has not been mounted yet, and the
    /// next mount links it to the persistent dir that already holds the file.
    private static func guestBridgeProblem(guestDir: URL, server: String, accessToken: String) -> String? {
        var st = stat()
        guard lstat(guestDir.path, &st) == 0 else {
            if errno == ENOENT { return nil }
            return "lstat \(guestDir.path) failed: errno=\(errno)"
        }
        guard (st.st_mode & S_IFMT) == S_IFLNK else {
            // Before the kernel boots, a real directory here (e.g. the legacy
            // pre-mount layout) is migrated and replaced by the link on the
            // next performMount, so the guest will still see the file.
            if !ISHKernel.shared.isBooted { return nil }
            return "\(guestDir.path) is not a link to the config dir (mode=0o\(String(st.st_mode & S_IFMT, radix: 8))); a guest-created directory is shadowing it"
        }
        let resolved = guestDir.resolvingSymlinksInPath().standardized.path
        let expected = AIChatViewModel.minisMcpServersPersistentDir.resolvingSymlinksInPath().standardized.path
        guard resolved == expected else {
            return "\(guestDir.path) links to \(resolved), expected \(expected)"
        }
        let guestFile = guestDir
            .appendingPathComponent("oauth", isDirectory: true)
            .appendingPathComponent("\(server).json")
        guard let data = try? Data(contentsOf: guestFile),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (parsed["access_token"] as? String) == accessToken else {
            return "the token is not readable through \(guestFile.path)"
        }
        return nil
    }

    // MARK: - PKCE Authorization Code flow

    /// Run the interactive authorization for `server`. Presents the system
    /// auth sheet; on success stores tokens (Keychain) and materializes the
    /// guest bridge file.
    func authorize(server: String, oauth: MCPOAuthConfig) async throws {
        guard !oauth.clientId.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw OAuthError.badConfig(AppLocalized("Client ID is required."))
        }
        guard let authBase = URL(string: oauth.authorizationEndpoint),
              authBase.scheme?.hasPrefix("http") == true else {
            throw OAuthError.badConfig(AppLocalized("Authorization Endpoint must be a valid https URL."))
        }
        guard URL(string: oauth.tokenEndpoint)?.scheme?.hasPrefix("http") == true else {
            throw OAuthError.badConfig(AppLocalized("Token Endpoint must be a valid https URL."))
        }
        let redirect = (oauth.redirectURI?.isEmpty == false ? oauth.redirectURI! : Self.defaultRedirectURI)
        guard let redirectURL = URL(string: redirect), let scheme = redirectURL.scheme else {
            throw OAuthError.badConfig(AppLocalized("Redirect URI is invalid."))
        }
        // [T-mcp-oauth-loopback] Loopback redirect (the default) → local HTTP
        // server + in-app Safari (RFC 8252; what Google et al. accept).
        // Anything else (custom scheme) → ASWebAuthenticationSession fallback.
        let isLoopback = (scheme == "http" || scheme == "https")
            && ["localhost", "127.0.0.1"].contains(redirectURL.host?.lowercased() ?? "")

        // PKCE verifier/challenge (RFC 7636, S256).
        let verifier = Self.randomURLSafe(length: 64)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8)))
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let state = Self.randomURLSafe(length: 24)

        var comps = URLComponents(url: authBase, resolvingAgainstBaseURL: false)!
        var items = comps.queryItems ?? []
        items.append(contentsOf: [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: oauth.clientId),
            URLQueryItem(name: "redirect_uri", value: redirect),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            // Ask for a refresh token where the provider supports these hints
            // (Google requires both; other providers ignore unknown params).
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
        ])
        // [T-mcp-oauth-resource] RFC 8707 Resource Indicator — the MCP auth
        // spec REQUIRES it on both the authorization and token requests,
        // valued as the MCP server's canonical URI. Servers that don't
        // understand it must ignore unknown parameters (RFC 6749 §3.1), so
        // this is a compatibility-safe addition.
        let resource = Self.resourceURI(server: server)
        if let resource {
            items.append(URLQueryItem(name: "resource", value: resource))
        }
        if let scopes = oauth.scopes, !scopes.trimmingCharacters(in: .whitespaces).isEmpty {
            items.append(URLQueryItem(name: "scope", value: scopes))
        }
        comps.queryItems = items
        guard let authURL = comps.url else {
            throw OAuthError.badConfig(AppLocalized("Could not build the authorization URL."))
        }

        logger.info("[Authorize] '\(server)' starting (endpointHost=\(authBase.host ?? "?"), mode=\(isLoopback ? "loopback" : "scheme:\(scheme)"))")
        let code: String
        if isLoopback {
            code = try await runLoopbackFlow(authURL: authURL, redirectURL: redirectURL, state: state)
        } else if scheme == "http" || scheme == "https" {
            // A public http(s) redirect can't be intercepted by a native app
            // (that's a web-app flow). Point the user at the loopback form.
            throw OAuthError.badConfig(AppLocalized("An http(s) Redirect URI must use localhost, e.g. \(Self.defaultRedirectURI)."))
        } else {
            code = try await runSchemeFlow(authURL: authURL, scheme: scheme, state: state)
        }

        // Token exchange.
        var form: [String: String] = [
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirect,
            "client_id": oauth.clientId,
            "code_verifier": verifier,
        ]
        if let secret = Self.clientSecret(server: server), !secret.isEmpty {
            form["client_secret"] = secret
        }
        // [T-mcp-oauth-resource] Same RFC 8707 parameter on the token request.
        if let resource {
            form["resource"] = resource
        }
        var req = URLRequest(url: URL(string: oauth.tokenEndpoint)!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = form.map { "\($0.key)=\(Self.formEncode($0.value))" }
            .joined(separator: "&").data(using: .utf8)
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode < 400 else {
            let body = String(data: data, encoding: .utf8)?.prefix(200) ?? ""
            logger.error("[Authorize] '\(server)' token exchange HTTP \((resp as? HTTPURLResponse)?.statusCode ?? -1)")
            throw OAuthError.exchangeFailed(AppLocalized("Token exchange failed: \(String(body))"))
        }
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let access = obj["access_token"] as? String else {
            throw OAuthError.exchangeFailed(AppLocalized("Token endpoint returned no access_token."))
        }
        let expiresIn = (obj["expires_in"] as? NSNumber)?.doubleValue ?? 0
        let stored = StoredTokens(
            accessToken: access,
            refreshToken: obj["refresh_token"] as? String,
            expiresAt: expiresIn > 0 ? Date().timeIntervalSince1970 + expiresIn : 0
        )
        Self.keychainSet(try JSONEncoder().encode(stored), account: "\(server)#tokens")
        try Self.materializeBridge(server: server, oauth: oauth, tokens: stored)
        logger.info("[Authorize] '\(server)' OK (hasRefresh=\(stored.refreshToken != nil), expiresIn=\(Int(expiresIn))s)")
    }

    // MARK: - Presentation flows

    /// [T-mcp-oauth-loopback] RFC 8252 loopback: local HTTP server on the
    /// redirect URI's port/path + in-app Safari. Mirrors ClaudeOAuthManager.
    private func runLoopbackFlow(authURL: URL, redirectURL: URL, state: String) async throws -> String {
        // Defensive cleanup of a previous failed attempt.
        callbackServer?.stop()
        callbackServer = nil
        defer {
            callbackServer?.stop()
            callbackServer = nil
            safariVC?.dismiss(animated: true)
            safariVC = nil
        }
        let port = UInt16(redirectURL.port ?? Int(Self.loopbackPort))
        let path = redirectURL.path.isEmpty ? "/callback" : redirectURL.path
        let server = OAuthCallbackServer(port: port, callbackPath: path)
        callbackServer = server
        try server.start()

        presentSafari(url: authURL)
        let result: OAuthCallbackResult
        do {
            result = try await server.waitForCallback(timeout: 300)
        } catch {
            // [T-mcp-oauth-dcr] OAuthCallbackServer is shared with the Claude /
            // Codex managers, so it reports an error-only callback as a generic
            // provider error. Recognise the deny case here rather than widening
            // OAuthCallbackResult, which those flows also depend on.
            if error.localizedDescription.contains("access_denied") { throw OAuthError.denied }
            throw error
        }
        guard result.state == state else {
            throw OAuthError.exchangeFailed(AppLocalized("State mismatch in the OAuth callback."))
        }
        return result.code
    }

    /// Custom-scheme fallback (e.g. Google iOS-client reversed client id).
    private func runSchemeFlow(authURL: URL, scheme: String, state: String) async throws -> String {
        let callbackURL: URL = try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(url: authURL, callbackURLScheme: scheme) { url, error in
                if let url {
                    cont.resume(returning: url)
                } else if let error, (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                    cont.resume(throwing: OAuthError.cancelled)
                } else {
                    cont.resume(throwing: error ?? OAuthError.cancelled)
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.activeSession = session
            if !session.start() {
                cont.resume(throwing: OAuthError.exchangeFailed(AppLocalized("Could not present the authorization page.")))
            }
        }
        activeSession = nil

        let cbComps = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)
        guard cbComps?.queryItems?.first(where: { $0.name == "state" })?.value == state else {
            throw OAuthError.exchangeFailed(AppLocalized("State mismatch in the OAuth callback."))
        }
        guard let code = cbComps?.queryItems?.first(where: { $0.name == "code" })?.value else {
            let err = cbComps?.queryItems?.first(where: { $0.name == "error" })?.value ?? "no code"
            // [T-mcp-oauth-dcr] `access_denied` is the user saying no on the
            // consent page — report it as a decision, not a raw error code.
            if err == "access_denied" { throw OAuthError.denied }
            throw OAuthError.exchangeFailed(AppLocalized("Authorization failed: \(err)"))
        }
        return code
    }

    /// Present the authorization page in in-app Safari, on top of whatever is
    /// currently presented (the MCP form sheet). Mirrors ClaudeOAuthManager.
    private func presentSafari(url: URL) {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first,
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var topVC = root
        while let presented = topVC.presentedViewController { topVC = presented }
        let vc = SFSafariViewController(url: url)
        vc.delegate = self
        topVC.present(vc, animated: true)
        safariVC = vc
    }

    /// Re-materialize the bridge (e.g. after the user edits the client secret
    /// while already authorized, so the guest sees the new refresh material).
    static func refreshBridgeIfAuthorized(server: String, oauth: MCPOAuthConfig) {
        guard let t = tokens(server: server) else { return }
        // [issue #380] Best-effort: this runs on a settings edit, not on an
        // authorization, so there is no result to report and nothing to abort.
        // It is still logged as an error — the same unreachable-bridge condition
        // that fails an authorize will fail here, and a silent catch is what made
        // #380 invisible in the first place.
        do {
            try materializeBridge(server: server, oauth: oauth, tokens: t)
        } catch {
            AppLogger(category: "MCPOAuth").error("[Bridge] refresh for '\(server)' failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Helpers

    private static func randomURLSafe(length: Int) -> String {
        let chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return String((0..<length).compactMap { _ in chars.randomElement() })
    }

    private static func formEncode(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

extension MCPOAuthController: SFSafariViewControllerDelegate {
    /// User closed Safari manually — stop the server, which resumes the
    /// in-flight waitForCallback with "cancelled" (OAuthCallbackServer.stop
    /// serializes against a racing successful callback, see its comments).
    nonisolated func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
        MainActor.assumeIsolated {
            callbackServer?.stop()
        }
    }
}

extension MCPOAuthController: ASWebAuthenticationPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }
}
