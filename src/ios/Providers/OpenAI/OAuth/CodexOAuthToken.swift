import Foundation

/// The persisted schema stays compatible with existing Minis keychain entries.
struct CodexTokenStorage: Codable, Sendable, Equatable {
    let accessToken: String
    let refreshToken: String?
    let idToken: String?
    let expireDate: Date?
    let lastRefresh: Date?
    let accountId: String?
    let planType: String?

    /// Older versions saved nil when the endpoint omitted expires_in, even if
    /// the access token contained exp. Recover that expiry without a migration.
    var effectiveExpiry: Date? {
        expireDate ?? CodexOAuthTokenParser.expiry(of: accessToken)
    }

    var resolvedAccountId: String? {
        accountId ?? CodexOAuthTokenParser.accountInfo(idToken: idToken, accessToken: accessToken).id
    }

    var resolvedPlanType: String? {
        planType ?? CodexOAuthTokenParser.accountInfo(idToken: idToken, accessToken: accessToken).plan
    }

    var isExpired: Bool {
        effectiveExpiry.map { $0 <= Date() } ?? false
    }

    func needsRefresh(at now: Date = Date(), buffer: TimeInterval = 300) -> Bool {
        guard let refreshToken, !refreshToken.isEmpty else { return false }
        if let expiry = effectiveExpiry { return expiry.timeIntervalSince(now) <= buffer }
        // Opaque tokens have no readable exp. Do not silently keep them forever.
        return lastRefresh.map { now.timeIntervalSince($0) >= 8 * 24 * 3600 } ?? true
    }
}

enum CodexOAuthTokenParser {
    private static let authClaim = "https://api.openai.com/auth"

    static func parse(_ data: Data, previous: CodexTokenStorage? = nil, now: Date = Date()) throws -> CodexTokenStorage {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let access = nonEmpty(json["access_token"] as? String) else {
            throw NSError(domain: "CodexOAuth", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Missing or empty access_token"])
        }
        let newIDToken = nonEmpty(json["id_token"] as? String)
        let info = accountInfo(idToken: newIDToken, accessToken: access)
        if let oldID = previous?.resolvedAccountId, let newID = info.id, oldID != newID {
            throw NSError(domain: "CodexOAuth", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "OAuth refresh changed account; sign in again"])
        }
        let expiresIn = (json["expires_in"] as? NSNumber)?.doubleValue
        let reportedExpiry = expiresIn.flatMap { $0.isFinite ? now.addingTimeInterval(max(0, $0)) : nil }
        // If both are present, honor the earlier expiry. Never use the ID-token
        // expiry to schedule refresh of the separate access token.
        let expiry = [reportedExpiry, Self.expiry(of: access)].compactMap { $0 }.min()
        return CodexTokenStorage(
            accessToken: access,
            refreshToken: nonEmpty(json["refresh_token"] as? String) ?? previous?.refreshToken,
            idToken: newIDToken ?? previous?.idToken,
            expireDate: expiry,
            lastRefresh: now,
            accountId: info.id ?? previous?.resolvedAccountId,
            planType: info.plan ?? previous?.resolvedPlanType
        )
    }

    /// These claims are metadata from the TLS token response, not a local
    /// signature check or an authorization decision. The service validates the
    /// bearer token on every API call; decoded claims never grant permissions.
    static func decodeJWTPayload(_ jwt: String) -> [String: Any]? {
        let segments = jwt.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 3, !segments[1].isEmpty else { return nil }
        var base64 = String(segments[1]).replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: base64) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    static func expiry(of token: String) -> Date? {
        guard let exp = decodeJWTPayload(token)?["exp"] as? NSNumber,
              exp.doubleValue.isFinite else { return nil }
        return Date(timeIntervalSince1970: exp.doubleValue)
    }

    static func accountInfo(idToken: String?, accessToken: String) -> (id: String?, plan: String?) {
        var id: String?
        var plan: String?
        for token in [idToken, accessToken].compactMap({ $0 }) {
            guard let claims = decodeJWTPayload(token) else { continue }
            let namespaced = claims[authClaim] as? [String: Any] ?? [:]
            id = id ?? nonEmpty(namespaced["chatgpt_account_id"] as? String)
                ?? nonEmpty(claims["chatgpt_account_id"] as? String)
            plan = plan ?? nonEmpty(namespaced["chatgpt_plan_type"] as? String)
                ?? nonEmpty(claims["chatgpt_plan_type"] as? String)
        }
        return (id, plan)
    }

    static func formBody(_ values: [String: String]) -> Data {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return Data(values.sorted { $0.key < $1.key }.map { key, value in
            "\(key.addingPercentEncoding(withAllowedCharacters: allowed)!)=\(value.addingPercentEncoding(withAllowedCharacters: allowed)!)"
        }.joined(separator: "&").utf8)
    }

    /// Keep only a structured error identifier. OAuth error descriptions may
    /// echo a submitted code/token and must not reach durable logs or the UI.
    static func sanitizedErrorCode(_ data: Data) -> String {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "oauth_error" }
        let nested = json["error"] as? [String: Any]
        let raw = (json["error"] as? String) ?? (nested?["code"] as? String) ?? "oauth_error"
        let known: Set<String> = ["invalid_grant", "invalid_token", "refresh_token_reused", "refresh_token_expired",
                                  "refresh_token_invalidated", "invalid_request", "invalid_client", "unauthorized_client",
                                  "access_denied", "temporarily_unavailable", "server_error", "rate_limit_exceeded"]
        return known.contains(raw.lowercased()) ? raw.lowercased() : "oauth_error"
    }

    static func refreshFailureIsFatal(status: Int?, code: String?) -> Bool {
        let invalidTokens: Set<String> = ["invalid_grant", "invalid_token", "refresh_token_reused",
                                          "refresh_token_expired", "refresh_token_invalidated"]
        // 400 can be a malformed request and 403 can be a proxy/WAF rejection.
        // Neither, alone, proves the refresh credential was revoked.
        return status == 401 || code.map { invalidTokens.contains($0) } == true
    }

    private static func nonEmpty(_ text: String?) -> String? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return text
    }
}
