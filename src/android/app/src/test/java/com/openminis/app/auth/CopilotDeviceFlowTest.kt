package com.openminis.app.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-copilot-provider] Covers the decision surface of the Copilot auth
 * protocol — the parts where a wrong answer is silent rather than loud.
 */
class CopilotDeviceFlowTest {

    // ── Poll classification ──────────────────────────────────────────────

    /**
     * The trap this pins: GitHub answers the poll endpoint with HTTP 200 even
     * while the user has not approved yet, putting `authorization_pending` in
     * the body. Reading `httpOK` as success would end the poll loop on the
     * first try and report a login that never happened.
     */
    @Test
    fun `http 200 with authorization_pending is pending, not success`() {
        val json = JSONObject("""{"error":"authorization_pending"}""")
        assertEquals(
            CopilotDeviceFlow.PollResult.Pending,
            CopilotDeviceFlow.classifyPoll(json, httpOK = true),
        )
    }

    @Test
    fun `an access token is success`() {
        val json = JSONObject("""{"access_token":"gho_abc123"}""")
        val r = CopilotDeviceFlow.classifyPoll(json, httpOK = true)
        assertTrue(r is CopilotDeviceFlow.PollResult.Success)
        assertEquals("gho_abc123", (r as CopilotDeviceFlow.PollResult.Success).accessToken)
    }

    @Test
    fun `terminal errors are classified apart from retryable ones`() {
        fun cls(err: String) = CopilotDeviceFlow.classifyPoll(
            JSONObject("""{"error":"$err"}"""), httpOK = false,
        )
        assertEquals(CopilotDeviceFlow.PollResult.SlowDown, cls("slow_down"))
        assertTrue(cls("access_denied") is CopilotDeviceFlow.PollResult.Denied)
        assertTrue(cls("expired_token") is CopilotDeviceFlow.PollResult.Expired)
        // Anything unrecognised must stop the loop rather than spin forever.
        assertTrue(cls("unauthorized_client") is CopilotDeviceFlow.PollResult.Fatal)
    }

    @Test
    fun `slow_down backs off by five seconds per RFC 8628`() {
        assertEquals(10L, CopilotDeviceFlow.bumpedInterval(5))
        assertEquals(15L, CopilotDeviceFlow.bumpedInterval(10))
    }

    // ── Device authorization parsing ─────────────────────────────────────

    @Test
    fun `device authorization parses and defaults interval and expiry`() {
        val a = CopilotDeviceFlow.parseDeviceAuthorization(
            JSONObject("""{"device_code":"d","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device"}"""),
        )!!
        assertEquals("ABCD-1234", a.userCode)
        assertEquals(5L, a.intervalSeconds)
        assertEquals(900L, a.expiresInSeconds)
    }

    /** A response missing a required field must not start a poll loop. */
    @Test
    fun `device authorization missing a required field is null`() {
        assertNull(
            CopilotDeviceFlow.parseDeviceAuthorization(
                JSONObject("""{"user_code":"ABCD","verification_uri":"https://x"}"""),
            ),
        )
    }

    // ── Session token ────────────────────────────────────────────────────

    @Test
    fun `session token prefers absolute expires_at`() {
        val s = CopilotDeviceFlow.parseSessionToken(
            JSONObject("""{"token":"tid=x","expires_at":2000,"refresh_in":1500}"""),
            nowEpochSeconds = 1000,
        )!!
        assertEquals(2000L, s.expiresAtEpochSeconds)
    }

    /** Only `refresh_in` present — derive a deadline rather than give up. */
    @Test
    fun `session token falls back to refresh_in`() {
        val s = CopilotDeviceFlow.parseSessionToken(
            JSONObject("""{"token":"tid=x","refresh_in":1500}"""),
            nowEpochSeconds = 1000,
        )!!
        assertEquals(2500L, s.expiresAtEpochSeconds)
    }

    /**
     * No usable expiry means we cannot tell a live token from a dead one.
     * Returning null forces a fresh exchange instead of caching something
     * that will 401 later.
     */
    @Test
    fun `session token with no expiry at all is rejected`() {
        assertNull(
            CopilotDeviceFlow.parseSessionToken(JSONObject("""{"token":"tid=x"}"""), 1000),
        )
    }

    /** Refreshes a minute early, so a request never rides an expiring token. */
    @Test
    fun `session token refreshes sixty seconds before expiry`() {
        val s = CopilotDeviceFlow.SessionToken("t", expiresAtEpochSeconds = 1000)
        assertFalse(s.needsRefresh(930))
        assertTrue(s.needsRefresh(940))
        assertTrue(s.needsRefresh(1001))
    }

    // ── Model list ───────────────────────────────────────────────────────

    /**
     * An explicit `model_picker_enabled: false` means the account may not choose
     * that model, so it is dropped.
     *
     * [T-android-copilot-model-capabilities] An ABSENT flag is now treated as
     * VISIBLE, which is a deliberate reversal of the previous default and the
     * behaviour iOS has always had. The field is missing on older responses, and
     * there is no fallback behind this list — Copilot ships no built-in catalog
     * and has no models.dev entry — so excluding-on-absent turns a parsing gap
     * into a bare "no models" with nothing to diagnose. The reported free-tier
     * case is the sharp edge: an account limited to the auto-select model would
     * lose the only model it can use. Showing one extra entry that might fail on
     * send is the recoverable direction; showing none is not.
     */
    @Test
    fun `an explicit picker-disabled entry is dropped`() {
        val json = JSONObject(
            """{"data":[
                 {"id":"gpt-4o","name":"GPT-4o","model_picker_enabled":true},
                 {"id":"text-embedding-3","name":"Embedding","model_picker_enabled":false},
                 {"id":"internal-x","name":"Internal"}
               ]}"""
        )
        val ids = CopilotDeviceFlow.parseModelIds(json)
        assertEquals(
            listOf("gpt-4o" to "GPT-4o", "internal-x" to "Internal"),
            ids,
        )
    }

    /** Missing display name falls back to the id rather than an empty label. */
    @Test
    fun `model without a name falls back to its id`() {
        val json = JSONObject("""{"data":[{"id":"o4-mini","model_picker_enabled":true}]}""")
        assertEquals(listOf("o4-mini" to "o4-mini"), CopilotDeviceFlow.parseModelIds(json))
    }

    // ── Headers ──────────────────────────────────────────────────────────

    /**
     * The exchange uses `token`, chat uses `Bearer`. Mixing them up is silent
     * — api.github.com simply refuses — so the scheme is pinned here.
     */
    @Test
    fun `token exchange uses the token scheme, not bearer`() {
        val h = CopilotDeviceFlow.tokenExchangeHeaders("gho_x")
        assertEquals("token gho_x", h["Authorization"])
    }

    @Test
    fun `chat headers mark initiator and add vision only when needed`() {
        val user = CopilotDeviceFlow.chatHeaders(false, hasImages = false, requestId = "r1")
        assertEquals("user", user["X-Initiator"])
        assertFalse(user.containsKey("Copilot-Vision-Request"))

        val agent = CopilotDeviceFlow.chatHeaders(true, hasImages = true, requestId = "r2")
        assertEquals("agent", agent["X-Initiator"])
        assertEquals("true", agent["Copilot-Vision-Request"])
        assertEquals("r2", agent["X-Request-Id"])
    }

    // ── [T-android-copilot-per-request-headers] derivation from the body ──
    //
    // These headers used to be pinned at construction to user/no-images/one
    // UUID, so every agent turn went out labelled as human traffic — the
    // specific thing GitHub acts on.

    private fun msg(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("content", text)

    private fun imageMsg(role: String, type: String): JSONObject {
        val parts = org.json.JSONArray().put(JSONObject().put("type", type))
        return JSONObject().put("role", role).put("content", parts)
    }

    @Test
    fun `a user turn is labelled user`() {
        val body = JSONObject().put("messages", org.json.JSONArray().put(msg("user", "hi")))
        val h = CopilotDeviceFlow.perRequestHeaders(body)
        assertEquals("user", h["X-Initiator"])
        assertNull(h["Copilot-Vision-Request"])
    }

    @Test
    fun `a trailing tool result is labelled agent`() {
        // The agent loop feeding tool output back is not a person typing.
        val msgs = org.json.JSONArray()
            .put(msg("user", "hi"))
            .put(msg("assistant", "calling a tool"))
            .put(msg("tool", "result"))
        val h = CopilotDeviceFlow.perRequestHeaders(JSONObject().put("messages", msgs))
        assertEquals("agent", h["X-Initiator"])
    }

    @Test
    fun `a tool result earlier in the history does not make the turn agent`() {
        // Only the LAST message decides: a conversation that used tools and then
        // got a human reply is a user turn again.
        val msgs = org.json.JSONArray()
            .put(msg("tool", "result"))
            .put(msg("user", "thanks, now do this"))
        val h = CopilotDeviceFlow.perRequestHeaders(JSONObject().put("messages", msgs))
        assertEquals("user", h["X-Initiator"])
    }

    @Test
    fun `the vision flag is set only when an image is attached`() {
        val chat = JSONObject().put(
            "messages", org.json.JSONArray().put(imageMsg("user", "image_url")),
        )
        assertEquals("true", CopilotDeviceFlow.perRequestHeaders(chat)["Copilot-Vision-Request"])

        // Responses API spells it differently; Copilot rides whichever builder
        // the model selects, so both shapes must be recognised.
        val responses = JSONObject().put(
            "input", org.json.JSONArray().put(imageMsg("user", "input_image")),
        )
        assertEquals("true", CopilotDeviceFlow.perRequestHeaders(responses)["Copilot-Vision-Request"])
    }

    @Test
    fun `the request id differs per call`() {
        // It used to be one UUID for the life of the provider, which identified
        // an instance rather than a request.
        val body = JSONObject().put("messages", org.json.JSONArray().put(msg("user", "hi")))
        val a = CopilotDeviceFlow.perRequestHeaders(body)["X-Request-Id"]
        val b = CopilotDeviceFlow.perRequestHeaders(body)["X-Request-Id"]
        assertTrue(!a.isNullOrEmpty())
        assertTrue("two calls must not share a request id", a != b)
    }

    @Test
    fun `an empty or unrecognised body is still answered safely`() {
        // A sizing/derivation helper must never be the thing that throws.
        val h = CopilotDeviceFlow.perRequestHeaders(JSONObject())
        assertEquals("user", h["X-Initiator"])
        assertNull(h["Copilot-Vision-Request"])
    }

    @Test
    fun `static headers carry the editor identity and nothing per-request`() {
        val h = CopilotDeviceFlow.staticChatHeaders()
        assertEquals(CopilotDeviceFlow.EDITOR_VERSION, h["Editor-Version"])
        assertEquals(CopilotDeviceFlow.INTEGRATION_ID, h["Copilot-Integration-Id"])
        // These vary per request and must NOT be frozen onto the provider.
        assertNull(h["X-Initiator"])
        assertNull(h["X-Request-Id"])
        assertNull(h["Copilot-Vision-Request"])
    }

    // ── [T-android-copilot-model-capabilities] /models parsing ────────────

    @Test
    fun `capabilities are carried through, not discarded`() {
        // Dropping these left every Copilot model with a null context window,
        // which disables everything that reasons about context size.
        val json = JSONObject(
            """
            {"data":[{"id":"gpt-5","name":"GPT-5","model_picker_enabled":true,
              "capabilities":{"type":"chat",
                "limits":{"max_context_window_tokens":128000,"max_output_tokens":16384},
                "supports":{"vision":true,"thinking":true}}}]}
            """.trimIndent(),
        )
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertEquals("gpt-5", m.id)
        assertEquals("GPT-5", m.displayName)
        assertEquals(128000, m.contextWindow)
        assertEquals(16384, m.maxOutputTokens)
        assertTrue(m.supportsVision)
        assertEquals(true, m.supportsReasoning)
    }

    @Test
    fun `a missing picker flag means visible, matching iOS`() {
        // Absent is treated as VISIBLE. Excluding on absent is the dangerous
        // direction: there is no built-in catalog and no models.dev entry behind
        // Copilot, so a parsing gap would surface as "no models" with no clue.
        val json = JSONObject("""{"data":[{"id":"only-model"}]}""")
        val parsed = CopilotDeviceFlow.parseModelsDetailed(json)
        assertEquals(1, parsed.models.size)
        assertTrue(parsed.hiddenByPolicy.isEmpty())
    }

    @Test
    fun `policy-hidden and non-chat models are dropped and reported`() {
        val json = JSONObject(
            """
            {"data":[
              {"id":"chat-ok","capabilities":{"type":"chat"}},
              {"id":"hidden","model_picker_enabled":false},
              {"id":"embed","capabilities":{"type":"embeddings"}}
            ]}
            """.trimIndent(),
        )
        val parsed = CopilotDeviceFlow.parseModelsDetailed(json)
        assertEquals(listOf("chat-ok"), parsed.models.map { it.id })
        assertEquals(listOf("hidden"), parsed.hiddenByPolicy)
        assertEquals(listOf("embed:embeddings"), parsed.droppedByType)
        assertEquals(3, parsed.totalReturned)
    }

    @Test
    fun `unknown capability fields stay null rather than becoming false`() {
        // null = "the server did not say"; false would read as a real answer and
        // would, for reasoning, hide the thinking toggle on a capable model.
        val json = JSONObject("""{"data":[{"id":"m","capabilities":{"type":"chat"}}]}""")
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertNull(m.contextWindow)
        assertNull(m.maxOutputTokens)
        assertNull(m.supportsReasoning)
        assertFalse(m.supportsVision)
    }

    @Test
    fun `duplicate ids are collapsed`() {
        val json = JSONObject("""{"data":[{"id":"dup"},{"id":"dup"}]}""")
        assertEquals(1, CopilotDeviceFlow.parseModels(json).size)
    }

    @Test
    fun `a malformed payload yields an empty list rather than throwing`() {
        assertTrue(CopilotDeviceFlow.parseModels(JSONObject("""{"nope":1}""")).isEmpty())
    }

    // ── [T-android-copilot-not-connected] which store answers "signed in" ──
    //
    // CopilotOAuthManager needs a Context and EncryptedSharedPreferences, so a
    // plain JVM test cannot drive the real thing. What IS testable — and what
    // actually broke — is the decision: which key the answer is read from.
    // Copilot persists `copilot_github_token`, while the inherited check looked
    // for `access_token` inside `oauth_tokens_<id>`, a blob Copilot never
    // writes. So a completed sign-in read as "not signed in" everywhere.

    /** The prefs shapes the two implementations inspect. */
    private class FakeStore {
        val prefs = mutableMapOf<String, String>()
        fun copilotLogin(id: String) { prefs["oauth_copilot_github_token_$id"] = "gho_abc123" }
        /** What every OTHER provider writes, and what the base class reads. */
        fun genericLogin(id: String) { prefs["oauth_tokens_$id"] = """{"access_token":"tok"}""" }

        /** The inherited implementation. */
        fun baseIsAuthenticated(id: String): Boolean {
            val blob = prefs["oauth_tokens_$id"] ?: return false
            return JSONObject(blob).optString("access_token", "").isNotEmpty()
        }
        /** Copilot's override. */
        fun copilotIsAuthenticated(id: String): Boolean =
            !prefs["oauth_copilot_github_token_$id"].isNullOrEmpty()
    }

    @Test
    fun `the inherited check cannot see a Copilot sign-in`() {
        // This is the bug, pinned: the base implementation is not merely
        // incomplete for Copilot, it is unable to answer at all.
        val store = FakeStore()
        store.copilotLogin("i1")
        assertFalse("the store Copilot writes is not the one the base reads",
            store.baseIsAuthenticated("i1"))
    }

    @Test
    fun `Copilot's own check sees it`() {
        val store = FakeStore()
        store.copilotLogin("i1")
        assertTrue(store.copilotIsAuthenticated("i1"))
    }

    @Test
    fun `neither reports a signed-in state before any login`() {
        val store = FakeStore()
        assertFalse(store.baseIsAuthenticated("i1"))
        assertFalse(store.copilotIsAuthenticated("i1"))
    }

    @Test
    fun `the override does not disturb other providers`() {
        // The base path must keep working for everyone who does write the
        // generic blob — the fix is an override, not a change of default.
        val store = FakeStore()
        store.genericLogin("i2")
        assertTrue(store.baseIsAuthenticated("i2"))
        // …and a generic login is NOT a Copilot credential.
        assertFalse(store.copilotIsAuthenticated("i2"))
    }

    @Test
    fun `an expired session token does not revoke the sign-in`() {
        // Only the GitHub token is the credential; the session tier is derived
        // and re-minted on demand, so its absence must not read as signed-out.
        val store = FakeStore()
        store.copilotLogin("i1")
        store.prefs.remove("oauth_copilot_session_token_i1")
        assertTrue(store.copilotIsAuthenticated("i1"))
    }

    // ── the credential-visibility rule, stated once ──────────────────────
    //
    // Three screens reported "not signed in" for a Copilot instance that WAS
    // signed in — the detail screen, the provider list, and the send path —
    // because each asked a store Copilot does not write to. They are one bug
    // with three faces, and this pins the rule that fixes all of them.

    /** Mirrors `hasAnyCredential`: api key, else the manager, else the blob. */
    private fun hasAnyCredential(
        apiKey: String?,
        allowsEmptyApiKey: Boolean,
        managerSaysAuthenticated: Boolean,
        genericBlob: String?,
    ): Boolean {
        if (apiKey != null || allowsEmptyApiKey) return true
        if (managerSaysAuthenticated) return true
        return !genericBlob.isNullOrEmpty()
    }

    @Test
    fun `an OAuth provider with no API key is credentialed when its manager says so`() {
        // The Copilot shape: no API key, nothing in the generic blob, but the
        // manager holds the credential. Answering false here is what made
        // sending a message report "No provider configured".
        assertTrue(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = false,
                managerSaysAuthenticated = true,
                genericBlob = null,
            ),
        )
    }

    @Test
    fun `the generic blob still answers for providers that use it`() {
        // The fix must not narrow the existing path: a provider type that
        // `forInstance` omits (gemini) has no manager but does have the blob.
        assertTrue(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = false,
                managerSaysAuthenticated = false,
                genericBlob = """{"access_token":"tok"}""",
            ),
        )
    }

    @Test
    fun `a signed-out instance is still reported as having nothing`() {
        assertFalse(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = false,
                managerSaysAuthenticated = false,
                genericBlob = null,
            ),
        )
    }

    @Test
    fun `a keyless compat endpoint remains credentialed`() {
        assertTrue(
            hasAnyCredential(
                apiKey = null,
                allowsEmptyApiKey = true,
                managerSaysAuthenticated = false,
                genericBlob = null,
            ),
        )
    }

    // ── [T-android-copilot-reasoning-fields] reasoning capability ─────────
    //
    // Copilot does NOT send `supports.thinking` — the field iOS reads and this
    // parser mirrored, which therefore always yielded null. A captured payload
    // states reasoning as `adaptive_thinking` + `reasoning_effort` instead, and
    // null means "unknown", which every thinking gate in the app treats as no.

    @Test
    fun `adaptive_thinking marks a model as reasoning-capable`() {
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":
               {"adaptive_thinking":true,"max_thinking_budget":32000}}}]}""".trimIndent(),
        )
        assertEquals(true, CopilotDeviceFlow.parseModels(json).single().supportsReasoning)
    }

    @Test
    fun `a reasoning_effort tier list also marks it capable, and is carried through`() {
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":
               {"reasoning_effort":["low","medium","high","xhigh","max"]}}}]}""".trimIndent(),
        )
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertEquals(true, m.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), m.reasoningEffortValues)
    }

    @Test
    fun `a model advertising neither signal is not reasoning-capable`() {
        // A real "no", not a gap: this response describes the capability when
        // it exists, so its absence is informative.
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":
               {"streaming":true,"tool_calls":true}}}]}""".trimIndent(),
        )
        val m = CopilotDeviceFlow.parseModels(json).single()
        assertEquals(false, m.supportsReasoning)
        assertNull(m.reasoningEffortValues)
    }

    @Test
    fun `a missing supports block stays unknown rather than false`() {
        val json = JSONObject("""{"data":[{"id":"m","capabilities":{"type":"chat"}}]}""")
        assertNull(CopilotDeviceFlow.parseModels(json).single().supportsReasoning)
    }

    @Test
    fun `the legacy thinking field still works if it ever appears`() {
        // Kept as a fallback in case the shape shifts or a proxy emulates the
        // field iOS assumes.
        val json = JSONObject(
            """{"data":[{"id":"m","capabilities":{"type":"chat","supports":{"thinking":true}}}]}""",
        )
        assertEquals(true, CopilotDeviceFlow.parseModels(json).single().supportsReasoning)
    }
}
