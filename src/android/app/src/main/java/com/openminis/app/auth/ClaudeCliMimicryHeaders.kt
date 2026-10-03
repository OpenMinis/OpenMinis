package com.openminis.app.auth

import com.openminis.app.provider.anthropic.ClaudeCliVersion
import okhttp3.Request

/**
 * The Claude CLI client fingerprint, applied to Anthropic OAuth *auth* requests
 * (token exchange and refresh).
 *
 * [OpenMinis#360] Claude OAuth login and silent refresh were being served a
 * Cloudflare interstitial ("Just a moment...", HTTP 403 with an HTML body)
 * instead of JSON. Two causes, both on the auth path only:
 *
 *  1. The token endpoint had moved. `console.anthropic.com/v1/oauth/token` is
 *     the old host; the CLI now posts to `claude.ai/v1/oauth/token`.
 *  2. These requests carried no client fingerprint at all. The chat path in
 *     `AnthropicProvider` has sent the headers below since OAuth landed, so
 *     chat traffic looked like the CLI while the token requests did not —
 *     which is what Cloudflare's bot management scored as a non-official
 *     client. The failure was invisible in review because each path built its
 *     own request and nothing tied them together.
 *
 * Deliberately NOT included here: `anthropic-beta`. That header advertises
 * message-API capabilities (prompt caching, interleaved thinking, …) and is
 * meaningless on a token request; the real CLI does not send it there either,
 * so adding it would widen the gap between what we advertise and what we send
 * rather than close it.
 *
 * **Keep in lockstep with `AnthropicProvider.buildHeaders`'s `isOAuth` block**
 * — the values are one registered client identity, and a fingerprint that
 * matches on one path but not the other is the exact shape of this bug.
 * `ClaudeCliMimicryHeadersTest` fails if the two drift apart.
 *
 * Mirrors the iOS set in `OAuthHTTPClient`'s interceptor.
 */
object ClaudeCliMimicryHeaders {

    /**
     * Header name → value, in the order the chat path sends them.
     *
     * Values are pinned on purpose. `User-Agent` is version-gated by the
     * backend (below claude-cli/2.1.251 a Fable 5.1 request is refused
     * outright, and below 2.1.280 an Opus 5.5 one is; the floor only rises)
     * outright), while the `X-Stainless-*` values describe the TypeScript SDK
     * and Node runtime rather than the CLI, and move on their own cadence.
     * Bumping either speculatively is how a working fingerprint gets broken.
     */
    val ALL: List<Pair<String, String>> = listOf(
        "User-Agent" to "claude-cli/2.1.280 (external, cli)",
        "X-Stainless-Lang" to "js",
        "X-Stainless-Package-Version" to "0.106.0",
        "X-Stainless-OS" to "Linux",
        "X-Stainless-Arch" to "arm64",
        "X-Stainless-Runtime" to "node",
        "X-Stainless-Runtime-Version" to "v24.18.0",
        "X-Stainless-Retry-Count" to "0",
        "X-Stainless-Timeout" to "600",
        "X-App" to "cli",
        "Anthropic-Dangerous-Direct-Browser-Access" to "true",
    )

    /**
     * [T-anthropic-cli-version] What actually goes on the wire: [ALL] with the
     * `User-Agent` version resolved at runtime by [ClaudeCliVersion].
     *
     * The literal in [ALL] stays the compiled-in floor — it is what a fresh,
     * offline install sends, and the resolver never goes below it. Every
     * other header is passed through unchanged, so the client identity keeps
     * its shape. Both the token path ([applyClaudeCliMimicryHeaders]) and the
     * chat path (`AnthropicProvider.buildHeaders`) read this one list, so a
     * runtime bump reaches both at once and the two cannot drift (#360).
     */
    fun current(): List<Pair<String, String>> {
        val ua = ClaudeCliVersion.userAgent()
        return ALL.map { (name, value) -> if (name == "User-Agent") name to ua else name to value }
    }
}

/**
 * Applies the full Claude CLI fingerprint to an OAuth token request.
 *
 * Every Anthropic auth request must go through this — see
 * [ClaudeCliMimicryHeaders] for why a partial or absent fingerprint gets the
 * request challenged by Cloudflare instead of answered.
 */
fun Request.Builder.applyClaudeCliMimicryHeaders(): Request.Builder {
    for ((name, value) in ClaudeCliMimicryHeaders.current()) {
        header(name, value)
    }
    return this
}
