import Foundation

/// [T-copilot-provider] Every tunable of the GitHub Copilot integration in one
/// place — the client id, the editor/plugin version strings and the endpoints.
///
/// UNOFFICIAL INTEGRATION. GitHub does not publish these endpoints and its
/// Terms of Service list proxying Copilot usage as grounds for restricting an
/// account. This provider is therefore opt-in, carries an explicit warning in
/// the sign-in UI, and can be switched off wholesale (see `isEnabled`).
///
/// These values are reverse-engineered from open-source clients and WILL rot:
/// GitHub can rotate the client id or start pinning a minimum editor version at
/// any time, with no deprecation notice. They are centralised here precisely so
/// that repair is a one-file edit rather than a hunt.
enum CopilotConstants {

    // MARK: - Kill switch

    /// [T-copilot-provider] Local off switch. The provider disappears from the
    /// Add Provider picker when false.
    ///
    /// Present because this integration depends on undocumented endpoints that
    /// a vendor change can break without warning, and because it carries
    /// account risk for the user: being able to withdraw it without shipping a
    /// build is worth the one `if`.
    ///
    /// [T-copilot-disclaimer] Defaults to **OFF**. An unreleased, unofficial
    /// integration whose failure mode lands on the user's own GitHub account
    /// should not be something a user finds switched on without having asked
    /// for it — opting in is the decision, not opting out. The key being absent
    /// now reads as false; a user (or a debug build) sets it true to surface
    /// the provider in the Add Provider picker.
    ///
    /// Purely local (`UserDefaults`). There is deliberately no remote config or
    /// server behind this: nothing about this integration should depend on a
    /// service we would then have to operate.
    static let enabledDefaultsKey = "copilotProviderEnabled"

    static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: enabledDefaultsKey)
    }

    /// [T-copilot-row-consent-only] Deliberately no setter, and nothing reads
    /// `isEnabled` any more.
    ///
    /// The provider is offered as a plain row in the Add Provider list, exactly
    /// like the other OAuth providers, and consent is taken where it belongs —
    /// `CopilotDeviceLoginSheet`'s consent page, which stands between the user
    /// and the first network call. A second gate here only made the user read
    /// and accept the same warning twice.
    ///
    /// The key is kept defined (not deleted) so a build that needs to withdraw
    /// the entry point again has the name to hand, and so an installed device
    /// that already has the value set does not look like it lost a setting.

    // MARK: - First-use reminder (retired)

    /// Marked whether the one-time "this is unofficial" banner had been shown
    /// before a Copilot request. Written by builds up to 24d4d301b.
    private static let firstUseNoticeShownKey = "copilotFirstUseNoticeShown"

    /// [T-copilot-no-chat-warning] The first-use chat banner was removed, so
    /// nothing consumes this any more. The key stays DEFINED rather than deleted
    /// so a device that already stored it is not left with an orphan whose
    /// meaning nobody can look up; there is deliberately no accessor, because
    /// the disclosure now lives entirely in the sign-in consent page.

    // MARK: - Sign-in consent

    /// [T-copilot-consent-remembered] Whether the user has accepted the
    /// unofficial-access notice.
    ///
    /// Persisted so the notice is shown ONCE, not on every sign-in. Re-asking
    /// someone who has already accepted does not make the disclosure stronger —
    /// a screen that appears every time is one the reader learns to tap past,
    /// which weakens the very warning it is meant to deliver. The acceptance is
    /// a standing decision about this integration, so it is recorded as one.
    ///
    /// Deliberately NOT per provider instance: the notice is about the method of
    /// access (a client identity issued to another tool), not about which
    /// account is being signed in, so a second account raises nothing new.
    ///
    /// Deliberately in `UserDefaults`, not the Keychain: it is a preference, not
    /// a secret, and it SHOULD reset when the app is removed and reinstalled —
    /// a fresh install is a fresh reader, and re-consenting once costs one tap.
    private static let signInConsentAcceptedKey = "copilotSignInConsentAccepted"

    static var hasAcceptedSignInConsent: Bool {
        UserDefaults.standard.bool(forKey: signInConsentAcceptedKey)
    }

    static func recordSignInConsent() {
        UserDefaults.standard.set(true, forKey: signInConsentAcceptedKey)
    }

    // MARK: - OAuth device flow (RFC 8628)

    /// GitHub OAuth app client id.
    ///
    /// SOURCE: the `Iv1.` id used by the `ericc-ch/copilot-api` project. The
    /// alternative seen in the wild is `Ov23li8tweQw6odWQebz` (`sst/opencode`).
    /// Neither is issued to Minis — they belong to editor integrations — which
    /// is the crux of why this is unofficial. If sign-in starts failing with
    /// `unauthorized_client`, this is the first value to re-check.
    static let clientId = "Iv1.b507a08c87ecfe98"

    /// `read:user` is the minimum that yields a token the Copilot token
    /// endpoint accepts; nothing here needs repo or org access.
    static let scope = "read:user"

    static let deviceCodeURL = URL(string: "https://github.com/login/device/code")!
    static let accessTokenURL = URL(string: "https://github.com/login/oauth/access_token")!

    // MARK: - Copilot session token + API

    /// Exchanges the long-lived GitHub token for a short-lived Copilot token.
    /// Note this one is on `api.github.com`, not the Copilot host.
    static let sessionTokenURL = URL(string: "https://api.github.com/copilot_internal/v2/token")!

    /// Chat/models host. OpenAI-compatible below this root, which is why the
    /// provider can ride `OpenAIProvider` rather than needing its own client.
    static let apiBaseURL = "https://api.githubcopilot.com"

    // MARK: - Version identifiers

    /// The editor identity Copilot expects. Requests without a plausible
    /// `Editor-Version` are rejected, so these are load-bearing rather than
    /// decorative. Bump together when the upstream client moves.
    static let editorVersion = "vscode/1.104.0"
    static let editorPluginVersion = "copilot-chat/0.31.0"
    static let userAgent = "GitHubCopilotChat/0.31.0"
    static let apiVersion = "2025-04-01"
    static let integrationId = "vscode-chat"

    // MARK: - Headers

    /// Headers every Copilot API call carries. `Authorization` is added by the
    /// provider from the session token; `X-Initiator` and the vision flag are
    /// per-request (see `requestHeaders`).
    static var baseHeaders: [String: String] {
        [
            "Editor-Version": editorVersion,
            "Editor-Plugin-Version": editorPluginVersion,
            "User-Agent": userAgent,
            "Copilot-Integration-Id": integrationId,
            "X-GitHub-Api-Version": apiVersion,
            "Openai-Intent": "conversation-panel",
        ]
    }

    /// Per-request headers.
    ///
    /// - `X-Initiator`: `user` when a person sent the last message, `agent`
    ///   when the loop is driving itself. Copilot uses it for attribution, and
    ///   an agent turn mislabelled as `user` is exactly the kind of thing that
    ///   gets an account flagged — so it is derived, never hard-coded.
    /// - `Copilot-Vision-Request`: only when images are actually attached.
    static func requestHeaders(isAgentTurn: Bool, hasImages: Bool) -> [String: String] {
        var h: [String: String] = [:]
        h["X-Initiator"] = isAgentTurn ? "agent" : "user"
        h["X-Request-Id"] = UUID().uuidString
        if hasImages { h["Copilot-Vision-Request"] = "true" }
        return h
    }

    /// Derive the per-request headers from the outgoing body.
    ///
    /// [T-copilot-per-request-headers] This is the bridge that was missing:
    /// `requestHeaders` above existed and was documented, but had no caller, so
    /// `X-Initiator` and the vision flag never reached the wire. It is wired to
    /// `OpenAIProvider.perRequestHeaders` in `makeCopilotProvider`.
    ///
    /// Note `requestHeaders` no longer folds in `baseHeaders`: those are already
    /// on the provider's `extraHeaders`, and returning them here would re-set
    /// identical values on every request for no reason.
    ///
    /// Derivation, from the only thing a request builder can see:
    /// - **agent turn** — the last message has `role: "tool"`, i.e. the loop is
    ///   feeding tool results back rather than a person having typed. Erring
    ///   toward `agent` is the safe direction: mislabelling agent traffic as
    ///   human is what gets an account flagged, never the reverse.
    /// - **images** — any content part of type `image_url` (chat completions) or
    ///   `input_image` (responses API). Both shapes are checked because Copilot
    ///   rides whichever builder the model selects.
    static func perRequestHeaders(forBody body: [String: Any]) -> [String: String] {
        let messages = (body["messages"] as? [[String: Any]])
            ?? (body["input"] as? [[String: Any]])
            ?? []

        let isAgentTurn = (messages.last?["role"] as? String) == "tool"

        let hasImages = messages.contains { msg in
            guard let parts = msg["content"] as? [[String: Any]] else { return false }
            return parts.contains { part in
                let t = part["type"] as? String
                return t == "image_url" || t == "input_image"
            }
        }

        return requestHeaders(isAgentTurn: isAgentTurn, hasImages: hasImages)
    }
}
