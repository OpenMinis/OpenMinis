package com.openminis.app.auth

import org.json.JSONObject

/**
 * [T-copilot-provider] GitHub Copilot auth protocol — pure request/response
 * shaping and classification. No network, no Android dependencies beyond
 * org.json, so the whole decision surface is unit-testable.
 *
 * ⚠️ UNOFFICIAL INTEGRATION. This drives Copilot through the same endpoints
 * the VS Code extension uses, with a borrowed client id and a spoofed editor
 * User-Agent. GitHub's Terms list proxying Copilot as grounds for account
 * restriction. It ships disabled by default and behind an explicit risk
 * notice — see [CopilotFeatureFlag] and `copilot_risk_notice` in strings.
 *
 * Three stages, and the middle one is what makes this different from every
 * other OAuth provider here:
 *
 *  1. RFC 8628 device flow against github.com → a LONG-LIVED account token
 *     (`gho_…`). Same shape as [KimiDeviceFlow], reused deliberately.
 *  2. Exchange that token for a SHORT-LIVED Copilot session token (~25 min)
 *     at api.github.com. This tier does not exist for Kimi; it is why the
 *     provider cannot simply hand its account token to the request layer.
 *  3. Chat Completions at api.githubcopilot.com with the session token —
 *     wire-compatible with OpenAI, which is why the existing OpenAI provider
 *     can carry it once the headers are injected.
 */
object CopilotDeviceFlow {

    // ── Stage 1: GitHub device flow ──────────────────────────────────────

    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

    /** Only `read:user` is needed — the Copilot token exchange derives the
     *  rest. Requesting more would be a broader grant than the feature uses. */
    const val SCOPE = "read:user"

    /**
     * Client id published by the VS Code Copilot Chat extension and used by
     * the open-source clients this integration follows (ericc-ch/copilot-api).
     * NOT ours, and not something GitHub issued to this app.
     *
     * If GitHub rotates or revokes it, stage 1 starts returning
     * `unauthorized_client` and login stops working — the failure is loud and
     * confined to this provider, which is the reason it is a single constant
     * rather than something threaded through the codebase. The alternative id
     * `Ov23li8tweQw6odWQebz` (sst/opencode) is the documented fallback.
     */
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"

    // ── Stage 2: Copilot session token exchange ──────────────────────────

    const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"

    // ── Stage 3: Chat ────────────────────────────────────────────────────

    const val API_BASE = "https://api.githubcopilot.com"

    /**
     * Editor identity headers. Every one of these is a claim about being VS
     * Code, and the server rejects requests that omit them — so they are
     * version numbers we have to keep plausible, not decoration.
     *
     * Collected here (not inlined at call sites) precisely because they go
     * stale: when Copilot starts refusing requests, this block is the first
     * and only place to bump.
     */
    // [T-android-copilot-parity] Kept in step with iOS CopilotConstants: the
    // two platforms claim the same editor identity, so a version Copilot starts
    // rejecting fails (and gets bumped) on both at once rather than one
    // platform silently aging out.
    const val EDITOR_VERSION = "vscode/1.104.0"
    const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.31.0"
    const val USER_AGENT = "GitHubCopilotChat/0.31.0"
    const val API_VERSION = "2025-04-01"
    const val INTEGRATION_ID = "vscode-chat"

    /**
     * Headers for the stage-2 exchange. Note the `token` scheme — NOT
     * `Bearer`. api.github.com rejects a Bearer-prefixed account token here,
     * which is an easy and silent mistake to make given stage 3 does use
     * Bearer.
     */
    fun tokenExchangeHeaders(githubToken: String): Map<String, String> = mapOf(
        "Authorization" to "token $githubToken",
        "User-Agent" to USER_AGENT,
        "Editor-Version" to EDITOR_VERSION,
        "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
        "X-GitHub-Api-Version" to API_VERSION,
        "Accept" to "application/json",
    )

    /**
     * Headers for stage-3 chat requests, minus Authorization (the provider
     * layer supplies that from the session token so it is always current).
     *
     * @param isAgentInitiated last message came from the assistant/tool loop
     *   rather than the user — GitHub distinguishes the two via X-Initiator.
     * @param hasImages adds the vision opt-in; sending it unconditionally
     *   would claim vision on text-only requests.
     */
    fun chatHeaders(
        isAgentInitiated: Boolean,
        hasImages: Boolean,
        requestId: String,
    ): Map<String, String> = buildMap {
        put("Copilot-Integration-Id", INTEGRATION_ID)
        put("Editor-Version", EDITOR_VERSION)
        put("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
        put("User-Agent", USER_AGENT)
        put("Openai-Intent", "conversation-panel")
        put("X-GitHub-Api-Version", API_VERSION)
        put("X-Initiator", if (isAgentInitiated) "agent" else "user")
        put("X-Request-Id", requestId)
        if (hasImages) put("Copilot-Vision-Request", "true")
    }

    /**
     * [T-android-copilot-per-request-headers] The editor-identity headers that
     * are the SAME on every request, so they can sit on the provider once.
     *
     * Split out from [chatHeaders] because the two halves have different
     * lifetimes: this half is a constant claim about being VS Code, while
     * X-Initiator / X-Request-Id / the vision flag describe one request and are
     * wrong if pinned (see [perRequestHeaders]).
     */
    fun staticChatHeaders(): Map<String, String> = mapOf(
        "Copilot-Integration-Id" to INTEGRATION_ID,
        "Editor-Version" to EDITOR_VERSION,
        "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
        "User-Agent" to USER_AGENT,
        "Openai-Intent" to "conversation-panel",
        "X-GitHub-Api-Version" to API_VERSION,
    )

    /**
     * [T-android-copilot-per-request-headers] Derive the per-request headers
     * from the outgoing body — the bridge [chatHeaders] was missing.
     *
     * `chatHeaders` was correct and documented, but the only caller pinned
     * `isAgentInitiated = false, hasImages = false` when the provider was
     * BUILT, so every request claimed to be human-initiated and no request
     * ever carried the vision flag. Mislabelling agent traffic as human is the
     * specific behaviour GitHub acts on, so erring toward `agent` is the safe
     * direction. `X-Request-Id` was likewise frozen: one UUID identified an
     * instance, not a request.
     *
     * Derivation, from the only thing a request builder can see:
     * - **agent turn** — the last message has `role: "tool"`, i.e. the loop is
     *   feeding tool results back rather than a person having typed.
     * - **images** — any content part typed `image_url` (chat completions) or
     *   `input_image` (responses API). Both shapes are checked because Copilot
     *   rides whichever builder the model selects.
     *
     * Mirrors iOS `CopilotConstants.perRequestHeaders(forBody:)`.
     */
    fun perRequestHeaders(body: JSONObject): Map<String, String> {
        val messages = body.optJSONArray("messages")
            ?: body.optJSONArray("input")

        var isAgentInitiated = false
        var hasImages = false

        if (messages != null && messages.length() > 0) {
            isAgentInitiated =
                messages.optJSONObject(messages.length() - 1)?.optString("role") == "tool"

            outer@ for (i in 0 until messages.length()) {
                val parts = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) {
                    val type = parts.optJSONObject(j)?.optString("type")
                    if (type == "image_url" || type == "input_image") {
                        hasImages = true
                        break@outer
                    }
                }
            }
        }

        // Only the headers that actually vary. The static editor identity is
        // already on the provider's extraHeaders; re-sending identical values
        // on every request would be noise.
        return buildMap {
            put("X-Initiator", if (isAgentInitiated) "agent" else "user")
            put("X-Request-Id", java.util.UUID.randomUUID().toString())
            if (hasImages) put("Copilot-Vision-Request", "true")
        }
    }

    // ── Stage 1 parsing ──────────────────────────────────────────────────

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    )

    /**
     * Parse the device/code response. Returns null when a REQUIRED field is
     * missing so the caller can surface a provider error rather than starting
     * a poll loop that can never succeed.
     */
    fun parseDeviceAuthorization(json: JSONObject): DeviceAuthorization? {
        val deviceCode = json.optString("device_code", "").ifEmpty { return null }
        val userCode = json.optString("user_code", "").ifEmpty { return null }
        val uri = json.optString("verification_uri", "")
            .ifEmpty { json.optString("verification_url", "") }
            .ifEmpty { return null }
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 } ?: 900L,
            intervalSeconds = json.optLong("interval", 0).takeIf { it > 0 } ?: 5L,
        )
    }

    /** Classification of one access_token poll. Mirrors [KimiDeviceFlow]. */
    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        /** authorization_pending — keep polling at the current interval. */
        object Pending : PollResult()
        /** slow_down — keep polling, interval += 5s (RFC 8628 §3.5). */
        object SlowDown : PollResult()
        data class Denied(val description: String) : PollResult()
        data class Expired(val description: String) : PollResult()
        data class Fatal(val description: String) : PollResult()
    }

    /**
     * Classify a poll response.
     *
     * GitHub answers this endpoint with HTTP 200 even for the pending case,
     * putting the OAuth error in the body — so `httpOK` alone must never be
     * read as success. The access_token check comes first for that reason.
     */
    fun classifyPoll(json: JSONObject, httpOK: Boolean): PollResult {
        if (httpOK) {
            val access = json.optString("access_token", "")
            if (access.isNotEmpty()) return PollResult.Success(access)
        }
        val error = json.optString("error", "").lowercase().ifEmpty { "unknown_error" }
        val description = json.optString("error_description", "").ifEmpty { error }
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Denied(description)
            "expired_token" -> PollResult.Expired(description)
            else -> PollResult.Fatal(description)
        }
    }

    /** RFC 8628 slow_down: bump the running interval by a fixed 5 seconds. */
    fun bumpedInterval(currentSeconds: Long): Long = currentSeconds + 5

    // ── Stage 2 parsing ──────────────────────────────────────────────────

    data class SessionToken(
        val token: String,
        /** Absolute unix seconds when the token stops being accepted. */
        val expiresAtEpochSeconds: Long,
    ) {
        /**
         * Refresh a minute early. The server also returns `refresh_in`, but
         * an absolute deadline survives process death and clock drift between
         * requests, which a relative countdown does not — and this is checked
         * lazily before each request rather than by a timer.
         */
        fun needsRefresh(nowEpochSeconds: Long): Boolean =
            nowEpochSeconds >= (expiresAtEpochSeconds - 60)
    }

    /**
     * Parse the session-token exchange. Returns null when the token is absent
     * OR carries no usable expiry: without a deadline we cannot tell a live
     * token from a dead one, and treating it as valid forever turns every
     * later request into a 401.
     */
    fun parseSessionToken(json: JSONObject, nowEpochSeconds: Long): SessionToken? {
        val token = json.optString("token", "").ifEmpty { return null }
        val expiresAt = json.optLong("expires_at", 0)
        if (expiresAt > 0) return SessionToken(token, expiresAt)
        // Some responses carry only `refresh_in`; derive the deadline from it.
        val refreshIn = json.optLong("refresh_in", 0)
        if (refreshIn > 0) return SessionToken(token, nowEpochSeconds + refreshIn)
        return null
    }

    /**
     * Model ids the picker should offer, from `GET /models`.
     *
     * Filters on `model_picker_enabled`: the endpoint also returns embedding
     * and internal models that chat cannot use, and offering those would put
     * entries in the picker that fail on first send. Absent flag = excluded,
     * deliberately — silently showing an unusable model is worse than missing
     * one that a later response will include.
     */
    fun parseModelIds(json: JSONObject): List<Pair<String, String>> =
        parseModels(json).map { it.id to it.displayName }

    /**
     * [T-android-copilot-model-capabilities] One model as `/models` describes
     * it, including the fields the picker and the context-window logic need.
     *
     * `parseModelIds` used to return only (id, name), discarding everything
     * else in the response. That left every Copilot model with a null context
     * window, which in turn leaves anything reasoning about context size — the
     * compaction threshold, the fallback negative guard — with no window to
     * reason about. The server already sends these numbers; dropping them was
     * pure loss.
     */
    data class CopilotModel(
        val id: String,
        val displayName: String,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
        val supportsVision: Boolean,
        val supportsReasoning: Boolean?,
        /**
         * Effort tiers this model accepts, from `supports.reasoning_effort`.
         * Null when the model takes no effort parameter.
         */
        val reasoningEffortValues: List<String>?,
    )

    /**
     * Models the account may actually use, with their capabilities.
     *
     * Two filters, both of which can legitimately empty the list — which is why
     * each drop is reported to the caller rather than silently applied:
     *
     * - `model_picker_enabled == false` — the account is not allowed to choose
     *   it. **Absent is treated as VISIBLE**, matching iOS: the field is missing
     *   on older responses, and hiding everything on an unknown shape looks like
     *   "no models" rather than the parsing gap it is. (This is a deliberate
     *   change from the previous default of excluding; excluding-on-absent is
     *   the more dangerous direction because there is no fallback list behind
     *   it — Copilot has no models.dev entry and no built-in catalog.)
     * - `capabilities.type != "chat"` — embeddings cannot serve a conversation.
     *   Reported because a free account is limited to the auto-select model, and
     *   if that entry is typed as anything else this filter would discard the
     *   only model such an account can use.
     */
    data class ParsedModels(
        val models: List<CopilotModel>,
        val hiddenByPolicy: List<String>,
        val droppedByType: List<String>,
        val totalReturned: Int,
    )

    fun parseModels(json: JSONObject): List<CopilotModel> = parseModelsDetailed(json).models

    fun parseModelsDetailed(json: JSONObject): ParsedModels {
        val data = json.optJSONArray("data")
            ?: return ParsedModels(emptyList(), emptyList(), emptyList(), 0)
        val out = mutableListOf<CopilotModel>()
        val hidden = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue
            if (!m.optBoolean("model_picker_enabled", true)) {
                hidden.add(id)
                continue
            }
            val caps = m.optJSONObject("capabilities")
            val type = caps?.optString("type", "").orEmpty()
            if (type.isNotEmpty() && type != "chat") {
                dropped.add("$id:$type")
                continue
            }
            if (!seen.add(id)) continue
            val limits = caps?.optJSONObject("limits")
            val supports = caps?.optJSONObject("supports")
            out.add(
                CopilotModel(
                    id = id,
                    displayName = m.optString("name", "").ifEmpty { id },
                    contextWindow = limits?.optInt("max_context_window_tokens", 0)
                        ?.takeIf { it > 0 },
                    maxOutputTokens = limits?.optInt("max_output_tokens", 0)
                        ?.takeIf { it > 0 },
                    supportsVision = supports?.optBoolean("vision", false) ?: false,
                    // [T-android-copilot-reasoning-fields] Copilot does NOT
                    // send `supports.thinking`. A captured /models payload
                    // reports reasoning as:
                    //
                    //   "adaptive_thinking": true,
                    //   "max_thinking_budget": 32000,
                    //   "min_thinking_budget": 1024,
                    //   "reasoning_effort": ["low","medium","high","xhigh","max"]
                    //
                    // Reading `thinking` (which is what iOS does, and what this
                    // mirrored) therefore always yielded null — "unknown" — and
                    // every thinking gate in the app tests `== true`. The result:
                    // Deep Thinking was unavailable on every Copilot model, and
                    // when it was force-enabled from a stale catalogue the
                    // request went out with NO reasoning parameter at all. That
                    // is the reported "thinking is on but no thinking text".
                    //
                    // Either signal means the model reasons; absence of both is
                    // a real "no" here rather than a gap, because this response
                    // does describe the capability when it exists.
                    supportsReasoning = when {
                        supports == null -> null
                        supports.optBoolean("adaptive_thinking", false) -> true
                        supports.optJSONArray("reasoning_effort")?.length()?.let { it > 0 } == true -> true
                        // `thinking` kept as a fallback in case the shape shifts
                        // back or a proxy emulates the iOS-assumed field.
                        supports.has("thinking") -> supports.optBoolean("thinking", false)
                        else -> false
                    },
                    reasoningEffortValues = supports?.optJSONArray("reasoning_effort")
                        ?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i).takeIf { it.isNotEmpty() }
                            }.takeIf { it.isNotEmpty() }
                        },
                ),
            )
        }
        return ParsedModels(out, hidden, dropped, data.length())
    }
}
