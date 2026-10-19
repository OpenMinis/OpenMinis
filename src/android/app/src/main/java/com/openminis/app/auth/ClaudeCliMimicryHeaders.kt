package com.openminis.app.auth

import okhttp3.Request

/**
 * The Claude CLI client fingerprint, applied to Anthropic OAuth *auth* requests
 * (token exchange and refresh).
 *
 * [T-claude-cli-fingerprint] Claude OAuth login and silent refresh were being
 * served a Cloudflare interstitial ("Just a moment...", HTTP 403 with an HTML
 * body) instead of JSON. Two causes, both on the auth path only:
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
 * A third, quieter failure lived in the chat path itself: the User-Agent was
 * pinned at `claude-cli/2.1.195` long after the backend's version floor rose
 * (`2.1.251` refuses Fable-tier models outright, `2.1.280` Opus 5.5). That is
 * the same defect shape as the missing auth headers — one identity, two places
 * to maintain it. [ALL] is now that single place for both.
 *
 * Deliberately NOT included here: `anthropic-beta`. That header advertises
 * message-API capabilities (prompt caching, interleaved thinking, …) and is
 * meaningless on a token request; the real CLI does not send it there either,
 * so adding it would widen the gap between what we advertise and what we send
 * rather than close it. The chat path assembles its own beta list.
 *
 * **Keep [AnthropicProvider]'s OAuth block delegated to this object.** The
 * values are one registered client identity, and a fingerprint that matches on
 * one path but not the other is the exact shape of the bug.
 * `ClaudeCliMimicryHeadersTest` fails if the two drift apart.
 */
object ClaudeCliMimicryHeaders {

    /**
     * Header name → value, in the order the chat path sends them.
     *
     * Values are pinned on purpose. `User-Agent` is version-gated by the
     * backend (below `claude-cli/2.1.251` a Fable-tier request is refused
     * outright, and below `2.1.280` an Opus 5.5 one is; the floor only rises),
     * while the `X-Stainless-*` values describe the TypeScript SDK and Node
     * runtime rather than the CLI, and move on their own cadence. Bumping
     * either speculatively is how a working fingerprint gets broken.
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
}

/**
 * Apply the whole CLI fingerprint to an outbound request.
 *
 * `.header(...)` replaces any value set earlier, so this is safe to call before
 * or after the body-specific headers; it only owns the eleven headers listed in
 * [ClaudeCliMimicryHeaders.ALL].
 */
fun Request.Builder.applyClaudeCliMimicryHeaders(): Request.Builder = apply {
    for ((name, value) in ClaudeCliMimicryHeaders.ALL) {
        header(name, value)
    }
}
