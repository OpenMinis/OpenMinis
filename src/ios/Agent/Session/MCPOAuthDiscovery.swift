import Foundation

/// [T-mcp-oauth-dcr] Discovery + Dynamic Client Registration for remote MCP
/// servers — the half of the MCP authorization spec that lets a user connect
/// WITHOUT pasting a Client ID (issue #315 / #319).
///
/// Everything downstream of a `client_id` already existed
/// (`MCPOAuthController`: PKCE/S256, ASWebAuthenticationSession + loopback,
/// RFC 8707 `resource`, Keychain tokens, refresh). What was missing is how to
/// obtain that `client_id` automatically. This file adds exactly that and then
/// hands a filled-in `MCPOAuthConfig` back to the existing flow, so the manual
/// path is untouched and remains the fallback.
///
/// Chain (MCP authorization spec, 2025-06-18):
///   1. unauthenticated request → 401 + `WWW-Authenticate: Bearer
///      resource_metadata="…"`                                    (RFC 9728 §5.1)
///   2. GET that URL → `authorization_servers[]`                   (RFC 9728)
///   3. GET `<AS>/.well-known/oauth-authorization-server`          (RFC 8414)
///   4. if it advertises `registration_endpoint` → POST it        (RFC 7591)
///      else → report `.dcrUnsupported` so the UI can fall back to manual
///
/// Deliberately NOT doing token work here: that would duplicate logic that
/// already exists and can drift. This type's only output is a config.
enum MCPOAuthDiscovery {

    private static let logger = AppLogger(category: "MCPOAuthDCR")

    // MARK: - Errors

    enum DiscoveryError: LocalizedError {
        /// The AS metadata parsed fine but advertises no `registration_endpoint`.
        /// This is the documented fallback branch, not a failure of ours.
        case dcrUnsupported(issuer: String)
        /// Server answered, but not in a way the spec allows.
        case notProtected
        case malformed(String)
        case registrationRejected(String)
        case network(String)

        var errorDescription: String? {
            switch self {
            case .dcrUnsupported:
                return AppLocalized("This service does not support automatic registration. Enter a Client ID manually.")
            case .notProtected:
                return AppLocalized("This server did not ask for authorization, so there is nothing to connect.")
            case .malformed(let m):
                return m
            case .registrationRejected(let m):
                return m
            case .network(let m):
                return m
            }
        }
    }

    // MARK: - Wire types

    /// RFC 9728 protected resource metadata (only the fields we act on).
    private struct ResourceMetadata: Decodable {
        let resource: String?
        let authorizationServers: [String]?
        enum CodingKeys: String, CodingKey {
            case resource
            case authorizationServers = "authorization_servers"
        }
    }

    /// RFC 8414 authorization server metadata (only the fields we act on).
    struct ASMetadata: Decodable {
        let issuer: String?
        let authorizationEndpoint: String?
        let tokenEndpoint: String?
        let registrationEndpoint: String?
        let codeChallengeMethodsSupported: [String]?
        let scopesSupported: [String]?
        enum CodingKeys: String, CodingKey {
            case issuer
            case authorizationEndpoint = "authorization_endpoint"
            case tokenEndpoint = "token_endpoint"
            case registrationEndpoint = "registration_endpoint"
            case codeChallengeMethodsSupported = "code_challenge_methods_supported"
            case scopesSupported = "scopes_supported"
        }
    }

    /// RFC 7591 client registration response.
    private struct RegistrationResponse: Decodable {
        let clientId: String
        let clientSecret: String?
        enum CodingKeys: String, CodingKey {
            case clientId = "client_id"
            case clientSecret = "client_secret"
        }
    }

    // MARK: - Registered-client cache

    /// [T-mcp-oauth-dcr] DCR results are cached BY AS ISSUER, not by MCP
    /// server, because that is the identity the registration actually belongs
    /// to: two MCP servers behind one authorization server should reuse a
    /// single registration rather than each burning a new one (TC-06).
    ///
    /// UserDefaults is the right store here — a `client_id` from an
    /// unauthenticated `/register` is a public identifier, not a secret. Any
    /// `client_secret` goes to the Keychain via the existing
    /// `MCPOAuthController.setClientSecret`, which is where secrets already live.
    private static let cacheKey = "mcp.oauth.dcr.clients.v1"

    private static func cachedClientId(issuer: String) -> String? {
        let map = UserDefaults.standard.dictionary(forKey: cacheKey) as? [String: String]
        return map?[issuer]
    }

    private static func cacheClientId(_ id: String, issuer: String) {
        var map = (UserDefaults.standard.dictionary(forKey: cacheKey) as? [String: String]) ?? [:]
        map[issuer] = id
        UserDefaults.standard.set(map, forKey: cacheKey)
    }

    /// Drop a cached registration — used when an AS rejects a `client_id` it
    /// once issued (server-side wipe), so the next attempt re-registers
    /// instead of failing forever on a dead id.
    static func forgetClientId(issuer: String) {
        var map = (UserDefaults.standard.dictionary(forKey: cacheKey) as? [String: String]) ?? [:]
        map[issuer] = nil
        UserDefaults.standard.set(map, forKey: cacheKey)
        logger.info("[DCR] dropped cached client for issuer=\(issuer)")
    }

    // MARK: - Step 1: 401 → WWW-Authenticate

    /// Parse `resource_metadata="…"` out of a `WWW-Authenticate` header
    /// (RFC 9728 §5.1). Tolerates parameter order, extra parameters, and
    /// unquoted values, which real servers vary on.
    static func resourceMetadataURL(fromWWWAuthenticate header: String) -> URL? {
        guard let range = header.range(of: "resource_metadata", options: .caseInsensitive) else { return nil }
        var rest = header[range.upperBound...].drop(while: { $0 == " " })
        guard rest.first == "=" else { return nil }
        rest = rest.dropFirst().drop(while: { $0 == " " })
        let value: String
        if rest.first == "\"" {
            let body = rest.dropFirst()
            guard let end = body.firstIndex(of: "\"") else { return nil }
            value = String(body[..<end])
        } else {
            value = String(rest.prefix(while: { $0 != "," && $0 != " " }))
        }
        return URL(string: value)
    }

    // MARK: - Public entry

    /// What a discovery attempt produced.
    struct Outcome {
        /// Ready to hand to `MCPOAuthController.authorize`.
        var config: MCPOAuthConfig
        /// AS issuer, for cache bookkeeping and for the confirmation card.
        var issuer: String
        /// True when the client_id came from cache rather than a fresh POST
        /// (TC-06 asserts this).
        var reusedCachedClient: Bool
    }

    /// Run the whole chain for `serverURL`, returning a config with a
    /// `client_id` filled in.
    ///
    /// Throws `.dcrUnsupported` when the AS advertises no registration
    /// endpoint — the caller is expected to present the existing manual form
    /// rather than treat it as an error (TC-08).
    static func discover(serverURL: String, redirectURI: String) async throws -> Outcome {
        guard let mcpURL = URL(string: serverURL), mcpURL.scheme?.hasPrefix("http") == true else {
            throw DiscoveryError.malformed(AppLocalized("The server URL is not valid."))
        }

        // 1. Unauthenticated probe. A 401 is the EXPECTED answer here.
        logger.info("[DCR] probing \(mcpURL.absoluteString)")
        let resourceMetaURL = try await probeForResourceMetadata(mcpURL)

        // 2. RFC 9728 → authorization_servers[]
        let resourceMeta: ResourceMetadata = try await getJSON(resourceMetaURL, label: "protected-resource")
        guard let asBase = resourceMeta.authorizationServers?.first, !asBase.isEmpty else {
            throw DiscoveryError.malformed(AppLocalized("The server did not name an authorization server."))
        }
        // Multiple authorization servers are allowed; we take the first, which
        // is the spec's own ordering hint (TC-04). Logged so a surprise is
        // visible rather than silent.
        if (resourceMeta.authorizationServers?.count ?? 0) > 1 {
            logger.info("[DCR] \(resourceMeta.authorizationServers?.count ?? 0) authorization servers offered — using the first: \(asBase)")
        }

        // 3. RFC 8414 (with the OIDC path as a documented fallback — some
        //    servers only publish there).
        let meta = try await fetchASMetadata(asBase: asBase)
        let issuer = meta.issuer ?? asBase
        guard let authEndpoint = meta.authorizationEndpoint, let tokenEndpoint = meta.tokenEndpoint else {
            throw DiscoveryError.malformed(AppLocalized("The authorization server's metadata is incomplete."))
        }
        // PKCE S256 is mandatory under OAuth 2.1. If a server explicitly lists
        // its methods and S256 is absent, say so plainly rather than starting a
        // flow that cannot succeed.
        if let methods = meta.codeChallengeMethodsSupported, !methods.contains("S256") {
            throw DiscoveryError.malformed(AppLocalized("This authorization server does not support PKCE (S256), which is required."))
        }

        // 4. DCR, or the documented fallback.
        guard let registration = meta.registrationEndpoint, !registration.isEmpty else {
            logger.info("[DCR] issuer=\(issuer) advertises no registration_endpoint — manual fallback")
            throw DiscoveryError.dcrUnsupported(issuer: issuer)
        }

        var reused = false
        let clientId: String
        if let cached = cachedClientId(issuer: issuer) {
            logger.info("[DCR] reusing cached client for issuer=\(issuer)")
            clientId = cached
            reused = true
        } else {
            clientId = try await register(endpoint: registration, redirectURI: redirectURI)
            cacheClientId(clientId, issuer: issuer)
            logger.info("[DCR] registered new client for issuer=\(issuer)")
        }

        var config = MCPOAuthConfig()
        config.mode = "dynamic"
        config.clientId = clientId
        config.authorizationEndpoint = authEndpoint
        config.tokenEndpoint = tokenEndpoint
        config.redirectURI = redirectURI
        if let scopes = meta.scopesSupported, !scopes.isEmpty {
            config.scopes = scopes.joined(separator: " ")
        }
        return Outcome(config: config, issuer: issuer, reusedCachedClient: reused)
    }

    // MARK: - Steps

    /// Send an unauthenticated request and read the challenge. Falls back to
    /// the well-known path when a server 401s without the header — some
    /// deployments omit it even though RFC 9728 §5.1 says MUST.
    private static func probeForResourceMetadata(_ mcpURL: URL) async throws -> URL {
        var req = URLRequest(url: mcpURL)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = #"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#.data(using: .utf8)
        req.timeoutInterval = 20

        let (_, response): (Data, URLResponse)
        do {
            (_, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw DiscoveryError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw DiscoveryError.malformed(AppLocalized("The server gave an unexpected response."))
        }
        guard http.statusCode == 401 || http.statusCode == 403 else {
            throw DiscoveryError.notProtected
        }
        let header = http.value(forHTTPHeaderField: "WWW-Authenticate") ?? ""
        if let url = resourceMetadataURL(fromWWWAuthenticate: header) {
            return url
        }
        logger.info("[DCR] 401 without a usable WWW-Authenticate — trying the well-known path")
        guard var comps = URLComponents(url: mcpURL, resolvingAgainstBaseURL: false) else {
            throw DiscoveryError.malformed(AppLocalized("The server URL is not valid."))
        }
        comps.path = "/.well-known/oauth-protected-resource"
        comps.query = nil
        comps.fragment = nil
        guard let url = comps.url else {
            throw DiscoveryError.malformed(AppLocalized("The server URL is not valid."))
        }
        return url
    }

    /// RFC 8414 path first, OIDC discovery second.
    private static func fetchASMetadata(asBase: String) async throws -> ASMetadata {
        guard var comps = URLComponents(string: asBase) else {
            throw DiscoveryError.malformed(AppLocalized("The authorization server URL is not valid."))
        }
        let basePath = comps.path.hasSuffix("/") ? String(comps.path.dropLast()) : comps.path
        comps.query = nil
        comps.fragment = nil

        for suffix in ["/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"] {
            comps.path = basePath + suffix
            guard let url = comps.url else { continue }
            do {
                return try await getJSON(url, label: "as-metadata")
            } catch {
                logger.info("[DCR] \(suffix) did not resolve — \(error.localizedDescription)")
                continue
            }
        }
        throw DiscoveryError.malformed(AppLocalized("Could not read the authorization server's metadata."))
    }

    /// RFC 7591. `token_endpoint_auth_method: "none"` marks us a PUBLIC client
    /// — a mobile app cannot hold a client secret, and PKCE is what actually
    /// protects the exchange.
    private static func register(endpoint: String, redirectURI: String) async throws -> String {
        guard let url = URL(string: endpoint) else {
            throw DiscoveryError.malformed(AppLocalized("The registration endpoint is not valid."))
        }
        let body: [String: Any] = [
            "redirect_uris": [redirectURI],
            "client_name": "Minis",
            "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"],
            "token_endpoint_auth_method": "none",
        ]
        guard JSONSerialization.isValidJSONObject(body),
              let data = try? JSONSerialization.data(withJSONObject: body) else {
            throw DiscoveryError.malformed(AppLocalized("Could not build the registration request."))
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = data
        req.timeoutInterval = 20

        let (respData, response): (Data, URLResponse)
        do {
            (respData, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw DiscoveryError.network(error.localizedDescription)
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...201).contains(code) else {
            // Surface the AS's own error code — "invalid_redirect_uri" is
            // actionable, "registration failed" is not (TC-09).
            let detail = (try? JSONSerialization.jsonObject(with: respData) as? [String: Any])
                .flatMap { ($0?["error_description"] as? String) ?? ($0?["error"] as? String) }
            throw DiscoveryError.registrationRejected(
                detail ?? String(format: AppLocalized("The authorization server refused the registration (HTTP %d)."), code))
        }
        guard let parsed = try? JSONDecoder().decode(RegistrationResponse.self, from: respData) else {
            throw DiscoveryError.malformed(AppLocalized("The registration response could not be read."))
        }
        return parsed.clientId
    }

    private static func getJSON<T: Decodable>(_ url: URL, label: String) async throws -> T {
        var req = URLRequest(url: url)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 20
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw DiscoveryError.network(error.localizedDescription)
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            throw DiscoveryError.malformed(String(format: AppLocalized("%@ returned HTTP %d."), label, code))
        }
        guard let parsed = try? JSONDecoder().decode(T.self, from: data) else {
            throw DiscoveryError.malformed(String(format: AppLocalized("%@ returned data that could not be read."), label))
        }
        return parsed
    }
}
