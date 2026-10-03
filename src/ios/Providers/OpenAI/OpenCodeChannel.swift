import Foundation

/// [T-opencode-dedicated-channel] A thread-safe cell holding the session id the
/// OpenCode channel should stamp on the next request.
///
/// Exists because the two sides live on different isolation domains:
/// `AIChatViewModel.sessionId` is MainActor state, while the channel's
/// per-request hook runs on whatever context is building the request. A
/// `@Sendable` closure cannot read the MainActor property directly, and
/// `assumeIsolated` would be a lie here — the builder is not guaranteed to be
/// on main.
///
/// Written on main by the view model's `sessionId` `didSet` (the single choke
/// point every draft-promotion path flows through) and read under the same lock
/// by the hook, so a promotion that lands mid-conversation is picked up by the
/// very next request with no provider rebuild.
final class OpenCodeSessionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: String?

    init(_ initial: String? = nil) { _value = initial }

    var value: String? {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); _value = newValue; lock.unlock() }
    }
}

/// [T-opencode-dedicated-channel] The OpenCode Go channel.
///
/// OpenCode Go is an OpenAI-compatible upstream that rejects any request
/// without `x-opencode-session` (`400 Request is missing x-opencode-session
/// and cannot be routed`). The header carries one stable id per conversation so
/// the service can key its prompt cache, so it must be present on EVERY request
/// of a conversation and must not change between turns.
///
/// ## Why this is its own channel rather than a patch in the generic path
///
/// The previous shape was `LLMProviderFactory.applyOpenCodeSession`, which
/// sniffed the base URL and, when it looked like OpenCode, stamped the header
/// into `extraHeaders` at provider-construction time. Two structural problems
/// came out of that, and neither is fixable by improving the sniff:
///
/// 1. **Construction-time capture cannot see a draft's promotion.** A new chat
///    builds its provider while still a draft, when `AIChatViewModel.sessionId`
///    is nil; the real UUID only exists once `ensureSession()` persists the row
///    on first send. So the FIRST turn of every new conversation — the one that
///    opens the upstream cache entry — went out with no header, and every later
///    turn went out with one. That is precisely the cross-turn instability the
///    header exists to prevent, and it is also a hard 400 on the first turn.
///
/// 2. **Host sniffing is the wrong question.** It asks "does this URL look like
///    OpenCode" when what we need to know is "did the user configure this
///    instance as OpenCode". A self-hosted relay in front of OpenCode Go —
///    exactly the setup the sniff was widened for — answers no, and a hostname
///    that merely embeds the string risks answering yes for someone else's
///    server, leaking a conversation id to a third party.
///
/// This type answers the second question from configuration, and solves the
/// first by resolving the id **per request** instead of at construction.
///
/// ## Detection is opt-in and explicit
///
/// `isOpenCodeInstance` is true when the instance is tagged as OpenCode, which
/// the app sets when the base URL is OpenCode's own service at configuration
/// time, and which a user can hold across any relay they put in front of it.
/// Host matching remains only as the AUTO-TAGGING seed for a freshly configured
/// instance — never as the per-request question.
enum OpenCodeChannel {

    /// The header OpenCode Go requires on every request.
    /// Lowercase per their docs; HTTP header names are case-insensitive.
    static let sessionHeader = "x-opencode-session"

    /// Registrable domain of the service. Matched exactly or as `*.opencode.ai`.
    private static let serviceHost = "opencode.ai"

    /// True when `base` points at OpenCode's own service.
    ///
    /// Used ONLY to seed the instance tag when a provider is configured — never
    /// to decide whether to send the header on a request. Once an instance is
    /// tagged, its own relay hostname is irrelevant.
    ///
    /// Matches on the parsed HOST, anchored to a label boundary, not
    /// `base.contains("opencode.ai")`: a substring test fires on a hostname
    /// that merely embeds the string (`opencode.ai.mycorp.net`,
    /// `my-opencode.ai-proxy.example`) — hosts controlled by someone else —
    /// which would send a conversation id to an unrelated third party.
    ///
    /// A URL with no scheme (users type `opencode.ai/zen/go/v1` freely) has no
    /// `host`, so it is normalized before parsing.
    static func looksLikeOpenCodeBaseURL(_ base: String?) -> Bool {
        guard let raw = base?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else { return false }
        let withScheme = raw.contains("://") ? raw : "https://" + raw
        guard let host = URLComponents(string: withScheme)?.host?.lowercased(),
              !host.isEmpty else { return false }
        return host == serviceHost || host.hasSuffix("." + serviceHost)
    }

    /// Normalize a candidate session id, or nil when it cannot be used.
    ///
    /// Rejects the empty string and the `__new__…` draft placeholder. Sending a
    /// placeholder would be worse than sending nothing: OpenCode would key a
    /// cache entry to an id that is about to be replaced, reading one
    /// conversation as two — the exact behaviour the header exists to prevent.
    /// A nil here means "omit the header", and the request-time resolution
    /// below means the next turn picks up the real id with no rebuild.
    static func normalizedSessionId(_ raw: String?) -> String? {
        guard let s = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !s.isEmpty,
              !s.hasPrefix(Self.draftSessionPrefix) else { return nil }
        return s
    }

    /// Prefix of the placeholder key a draft conversation uses before its row
    /// is persisted. Mirrors the Android draft key.
    static let draftSessionPrefix = "__new__"

    /// Attach the channel to an OpenAI-family provider.
    ///
    /// `resolveSessionId` is called **at request-build time**, once per
    /// request, rather than being captured now. That is the whole point: a
    /// provider outlives the draft→session promotion, so a value read now
    /// would be nil for the first turn of every new conversation. Reading late
    /// means the promotion is picked up with no provider rebuild and no
    /// patch-the-provider step at the promotion site.
    ///
    /// Routed through `perRequestHeaders`, which the three conversation
    /// builders (`streamRaw`, `buildChatCompletionsRequest`,
    /// `buildResponsesAPIRequest`) all apply — so ordinary turns, streaming
    /// turns and every tool-call continuation are covered by construction.
    /// `extraHeaders` is deliberately NOT used: it is a snapshot, which is the
    /// bug being fixed.
    ///
    /// A previously-installed hook is preserved and merged, so this composes
    /// with Copilot's per-request headers rather than replacing them.
    @discardableResult
    static func attach(to provider: OpenAIProvider,
                       resolveSessionId: @escaping @Sendable () -> String?) -> OpenAIProvider {
        let existing = provider.perRequestHeaders
        provider.perRequestHeaders = { body in
            var headers = existing?(body) ?? [:]
            // An explicit value already set for this key wins — nothing sets it
            // today, but a future explicit override should not be clobbered by
            // an inference.
            if headers[sessionHeader] == nil,
               let sid = normalizedSessionId(resolveSessionId()) {
                headers[sessionHeader] = sid
            }
            return headers
        }
        return provider
    }
}
