import Foundation

/// [T-copilot-provider] Persisted Copilot credentials for one provider instance.
///
/// TWO tokens with very different lifetimes, which is the whole shape of this
/// integration:
/// - `githubToken` (`gho_…`) is account-level and effectively permanent. It is
///   the secret; losing it means re-running the device flow.
/// - `sessionToken` is what the Copilot API actually accepts and lives ~25
///   minutes. It is derived, cached only to avoid a round-trip per request, and
///   disposable — if it is missing or stale we simply mint another.
struct CopilotTokenStorage: Codable {
    var githubToken: String
    var sessionToken: String?
    /// Unix seconds, from the exchange response's `expires_at`.
    var sessionExpiresAt: TimeInterval?
}

/// [T-copilot-provider] GitHub Copilot sign-in and token lifecycle.
///
/// UNOFFICIAL. See `CopilotConstants` for what that means and why the endpoints
/// here are undocumented.
///
/// Structure follows `KimiOAuthManager` — per-instance Keychain via
/// `ProviderKeychainHelper`, single-flight refresh keyed by instance — and the
/// RFC 8628 wire parsing is reused verbatim from `KimiDeviceFlow` rather than
/// written twice. The one genuinely new thing is the second token layer.
@MainActor
final class CopilotOAuthManager: ObservableObject {
    static let shared = CopilotOAuthManager()
    private init() {}

    private let logger = AppLogger(category: "CopilotOAuth")

    /// Mint a new session token this many seconds before the old one lapses.
    /// The exchange response also carries `refresh_in` (~1500s); this buffer is
    /// applied to the authoritative `expires_at` instead, so a clock skew or a
    /// long-running request cannot land on an already-dead token.
    private let sessionBuffer: TimeInterval = 60

    enum CopilotError: LocalizedError {
        case notAuthenticated
        case deviceFlow(String)
        case sessionExchange(String)

        var errorDescription: String? {
            switch self {
            case .notAuthenticated:
                return AppLocalized("Sign in to GitHub Copilot first.")
            case .deviceFlow(let m): return m
            case .sessionExchange(let m): return m
            }
        }
    }

    // MARK: - Status

    func isAuthenticated(instanceId: String) -> Bool {
        ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CopilotTokenStorage.self)?.githubToken.isEmpty == false
    }

    func logout(instanceId: String) {
        ProviderKeychainHelper.deleteOAuthToken(instanceId: instanceId)
        inFlightSession[instanceId]?.cancel()
        inFlightSession[instanceId] = nil
    }

    // MARK: - Device flow (RFC 8628)

    /// Step 1: ask GitHub for a device + user code.
    func requestDeviceAuthorization() async throws -> KimiDeviceFlow.DeviceAuthorization {
        var req = URLRequest(url: CopilotConstants.deviceCodeURL)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(CopilotConstants.userAgent, forHTTPHeaderField: "User-Agent")
        req.httpBody = try? JSONSerialization.data(withJSONObject: [
            "client_id": CopilotConstants.clientId,
            "scope": CopilotConstants.scope,
        ])
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CopilotError.deviceFlow(AppLocalized("GitHub did not return a device code."))
        }
        // Reuse Kimi's RFC 8628 parser — same grant, same field names.
        return try KimiDeviceFlow.parseDeviceAuthorization(json)
    }

    /// Step 2: poll until the user finishes in the browser.
    ///
    /// Honours RFC 8628 §3.5 backoff: `slow_down` widens the interval by 5s
    /// permanently, not just for one tick. `onPending` lets the UI stay alive
    /// without this type knowing anything about views.
    func pollForAccessToken(
        auth: KimiDeviceFlow.DeviceAuthorization,
        instanceId: String,
        onPending: (() -> Void)? = nil
    ) async throws -> String {
        var interval = auth.interval
        let deadline = Date().addingTimeInterval(auth.expiresIn)
        logger.info("[Copilot] device flow polling START interval=\(Int(interval))s expiresIn=\(Int(auth.expiresIn))s instance=\(instanceId.prefix(8))")

        while Date() < deadline {
            try await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
            if Task.isCancelled { throw CancellationError() }

            var req = URLRequest(url: CopilotConstants.accessTokenURL)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.setValue(CopilotConstants.userAgent, forHTTPHeaderField: "User-Agent")
            req.httpBody = try? JSONSerialization.data(withJSONObject: [
                "client_id": CopilotConstants.clientId,
                "device_code": auth.deviceCode,
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            ])

            let (data, response) = try await URLSession.shared.data(for: req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let httpOK = (200...299).contains(status)
            let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]

            // [T-copilot-poll-diagnostics] Log every poll outcome.
            //
            // A device flow that sits on "Waiting for authorization…" forever
            // produced NO log line at all: the loop logged only on success and
            // slow_down, so a stuck flow — the one case anyone would pull logs
            // for — was invisible, and a field report could not be told apart
            // from a wrong `error` code, an unparseable body, or a response the
            // classifier fell through on. The `error` field is GitHub's own
            // machine code (authorization_pending / access_denied / …) and
            // carries no user content, so it is safe to log verbatim; the
            // device code and any token are NOT logged.
            let errCode = (json["error"] as? String) ?? (httpOK ? "none" : "unparsed")
            logger.info("[Copilot] poll status=\(status) error=\(errCode) keys=\(json.keys.sorted().joined(separator: ","))")

            switch KimiDeviceFlow.classifyPoll(json: json, httpOK: httpOK) {
            case .success(let accessToken, _, _):
                let storage = CopilotTokenStorage(githubToken: accessToken)
                ProviderKeychainHelper.saveOAuthToken(storage, instanceId: instanceId)
                logger.info("[Copilot] device flow complete for instance \(instanceId.prefix(8))")
                return accessToken
            case .pending:
                onPending?()
                continue
            case .slowDown:
                interval = KimiDeviceFlow.bumpedInterval(interval)
                logger.info("[Copilot] slow_down — interval now \(Int(interval))s")
                continue
            case .denied(let m):
                logger.error("[Copilot] device flow DENIED: \(m)")
                throw CopilotError.deviceFlow(m.isEmpty ? AppLocalized("You declined the authorization request.") : m)
            case .expired(let m):
                logger.error("[Copilot] device flow EXPIRED: \(m)")
                throw CopilotError.deviceFlow(m.isEmpty ? AppLocalized("The code expired. Start again.") : m)
            case .fatal(let m):
                // The catch-all branch of `classifyPoll`. Worth its own line:
                // an unrecognised `error` code lands here, and that is exactly
                // the case a stuck flow would be caused by.
                logger.error("[Copilot] device flow FATAL: \(m)")
                throw CopilotError.deviceFlow(m)
            }
        }
        logger.error("[Copilot] device flow timed out after \(Int(auth.expiresIn))s without a terminal answer")
        throw CopilotError.deviceFlow(AppLocalized("The code expired. Start again."))
    }

    // MARK: - Session token (layer 2)

    private var inFlightSession: [String: Task<String, Error>] = [:]

    /// The token an API call should carry. Lazily mints or refreshes the short
    /// -lived Copilot token; the caller never has to think about which layer it
    /// is holding.
    ///
    /// Lazy rather than a standing timer on purpose: a timer would keep firing
    /// for instances nobody is using, and would still have to be re-checked at
    /// request time anyway (a device asleep past expiry wakes with a dead
    /// token). Checking at the point of use is both cheaper and the only
    /// version that is actually correct.
    func validSessionToken(instanceId: String) async throws -> String {
        guard let storage = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CopilotTokenStorage.self),
              !storage.githubToken.isEmpty else {
            throw CopilotError.notAuthenticated
        }
        if let token = storage.sessionToken,
           let expiry = storage.sessionExpiresAt,
           Date().timeIntervalSince1970 < expiry - sessionBuffer {
            return token
        }
        // Single-flight: a turn that fires several tool calls at once must not
        // race N exchanges for the same instance.
        if let existing = inFlightSession[instanceId] {
            return try await existing.value
        }
        let task = Task<String, Error> { [weak self] in
            guard let self else { throw CopilotError.notAuthenticated }
            return try await self.exchangeSessionToken(instanceId: instanceId, githubToken: storage.githubToken)
        }
        // [T-copilot-inflight-defer] The slot must be cleared in THIS scope, not
        // inside the Task body. With `defer` in the body, the clear could run
        // before the assignment below (the body is free to finish first — it
        // reaches a `throw` before its first suspension, say), which nils an
        // empty slot and then parks a COMPLETED task in the dictionary forever.
        // Every later caller would await that task and get the token it already
        // resolved — a stale one — until relaunch or logout. KimiOAuthManager
        // orders it this way for the same reason; this is the one place the
        // Copilot version had diverged from it.
        inFlightSession[instanceId] = task
        defer { inFlightSession[instanceId] = nil }
        return try await task.value
    }

    /// Step 3: GitHub token → Copilot session token.
    ///
    /// Note the `token` auth scheme rather than `Bearer` — this endpoint
    /// rejects `Bearer`, which is an easy hour to lose.
    private func exchangeSessionToken(instanceId: String, githubToken: String) async throws -> String {
        var req = URLRequest(url: CopilotConstants.sessionTokenURL)
        req.setValue("token \(githubToken)", forHTTPHeaderField: "Authorization")
        req.setValue(CopilotConstants.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(CopilotConstants.editorVersion, forHTTPHeaderField: "Editor-Version")
        req.setValue(CopilotConstants.editorPluginVersion, forHTTPHeaderField: "Editor-Plugin-Version")
        req.setValue(CopilotConstants.apiVersion, forHTTPHeaderField: "X-GitHub-Api-Version")

        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = json["token"] as? String else {
            // 401 here means the GitHub token itself is dead (revoked, or the
            // account lost Copilot access) — say that, rather than reporting a
            // generic failure the user cannot act on.
            if code == 401 {
                throw CopilotError.sessionExchange(AppLocalized("GitHub rejected the saved sign-in. Sign in again."))
            }
            throw CopilotError.sessionExchange(String(format: AppLocalized("Could not get a Copilot token (HTTP %d)."), code))
        }
        var storage = ProviderKeychainHelper.loadOAuthToken(instanceId: instanceId, as: CopilotTokenStorage.self)
            ?? CopilotTokenStorage(githubToken: githubToken)
        storage.sessionToken = token
        storage.sessionExpiresAt = (json["expires_at"] as? TimeInterval)
            ?? Date().addingTimeInterval(25 * 60).timeIntervalSince1970
        ProviderKeychainHelper.saveOAuthToken(storage, instanceId: instanceId)
        logger.info("[Copilot] session token refreshed for instance \(instanceId.prefix(8))")
        return token
    }
}
