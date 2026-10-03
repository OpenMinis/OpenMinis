package com.openminis.app.provider

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * [T-android-opencode-session-header] Attach `x-opencode-session` to requests
 * bound for OpenCode Go.
 *
 * OpenCode Go (`https://opencode.ai/zen/go/v1`, OpenAI-compatible) began
 * enforcing this header on 2026-09-06; without it the endpoint answers
 * `400 "Request is missing x-opencode-session and cannot be routed
 * efficiently"` (GH #327), which left Minis unusable against the service
 * (GH #304). The value is what the operator calls "one stable ID per
 * conversation" and is used to key their prompt cache.
 *
 * The operator's stated requirements, and how each is met:
 *
 *  1. *A non-sensitive, unique session ID (a random UUID is fine).* Chat
 *     session ids are `UUID.randomUUID()` (ChatRepository.createSession), so
 *     they carry no account, key or device identity.
 *  2. *Stable across every request, retry and resumption of one conversation;
 *     a new conversation gets a new one.* The id is the session's DB primary
 *     key, so it survives restarts and provider re-creation (model switch,
 *     fallback, OAuth refresh) — see [headersFor]'s contract about
 *     WHICH id callers must pass.
 *  3. *Keep identifying the client honestly in the User-Agent.* Untouched —
 *     this adds a header and never edits the UA, which still reports Minis
 *     via [MinisUserAgent].
 *  4. *No API key or personal information in the id.* Guaranteed by (1).
 *
 * ## Why the header is gated on the host rather than sent everywhere
 *
 * Sending it unconditionally would hand a stable per-conversation identifier
 * to every third-party endpoint a user configures, which is a tracking vector
 * we would be introducing on their behalf. The gate is a privacy control, not
 * a feature switch.
 *
 * ## Why host equality/suffix rather than `contains("opencode.ai")`
 *
 * A substring test also matches `https://opencode.ai.example.com/v1` and
 * `https://not-opencode.ai/v1` — hosts controlled by someone else entirely —
 * so the cheap check leaks the very identifier it exists to protect. Parsing
 * the URL and comparing the host (exactly, or as a dot-anchored subdomain)
 * costs one call and cannot be spoofed by a crafted prefix or suffix.
 */
object OpenCodeSessionHeader {

    /** The header OpenCode Go requires. Lowercase per their docs; HTTP header names are case-insensitive. */
    const val HEADER = "x-opencode-session"

    /** Registrable domain of the service. Matched exactly or as `*.opencode.ai`. */
    private const val HOST = "opencode.ai"

    /**
     * True when [baseUrl] points at OpenCode Go.
     *
     * Null/blank (the official-endpoint case, where `effectiveBaseURL` is null
     * because no custom base URL is set) and unparseable input are false — a
     * URL we cannot read the host of is one we must not send the id to.
     */
    fun matches(baseUrl: String?): Boolean {
        val raw = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        // Users type the endpoint from the docs freely, and the base-URL field
        // only trims — it never adds a scheme. `toHttpUrlOrNull` requires one,
        // so a scheme-less `opencode.ai/zen/go/v1` would otherwise be read as
        // "not OpenCode" and the header silently dropped. Matches iOS, which
        // normalizes the same way ([T-opencode-session-header]).
        val normalized = if (raw.contains("://")) raw else "https://$raw"
        val host = normalized.toHttpUrlOrNull()?.host ?: return false
        // `HttpUrl.host` is already lowercased and punycode-encoded, so a
        // literal comparison is safe. The leading dot on the suffix test is
        // what stops `not-opencode.ai` from matching.
        return host == HOST || host.endsWith(".$HOST")
    }

    /**
     * The headers to merge into a provider built for [baseUrl], given the
     * conversation's [sessionId].
     *
     * Returns empty for anything that is not OpenCode Go, and for a missing or
     * blank session id — a request with no id is better than one carrying a
     * placeholder, since a wrong-but-present value would silently poison the
     * upstream prompt cache instead of failing loudly.
     *
     * **Callers must pass the PERSISTED session id.** A draft chat's id is a
     * synthetic placeholder (`__new__…`, optionally carrying `__fld__`/`__grp__`
     * routing suffixes) that `ChatViewModel.ensureSession()` replaces with a
     * real UUID when the first message lands. Passing the placeholder would
     * send one id on a conversation's first turn and a different one on every
     * turn after — requirement (2) violated silently, since the endpoint keeps
     * answering while its cache never hits. [isPersistedSessionId] is the guard,
     * applied here so no call site can forget it.
     */
    fun headersFor(baseUrl: String?, sessionId: String?): Map<String, String> {
        if (!matches(baseUrl)) return emptyMap()
        val id = sessionId?.trim().orEmpty()
        if (!isPersistedSessionId(id)) return emptyMap()
        return mapOf(HEADER to id)
    }

    /**
     * Whether [sessionId] is a real, persisted conversation id rather than a
     * draft placeholder.
     *
     * Drafts are keyed `__new__…` (ChatViewModel's draft id, which may append
     * `__fld__<folder>` / `__grp__<group>` routing segments). Rejecting the
     * whole `__…__` shape rather than the exact literal keeps this correct if
     * another synthetic key is introduced later: an unknown synthetic id is
     * refused, which costs a header on one turn, whereas accepting it would
     * break cross-turn stability for the whole conversation.
     */
    fun isPersistedSessionId(sessionId: String?): Boolean {
        val id = sessionId?.trim().orEmpty()
        return id.isNotEmpty() && !id.startsWith("__")
    }
}
