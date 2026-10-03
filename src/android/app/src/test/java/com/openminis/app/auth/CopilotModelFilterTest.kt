package com.openminis.app.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M02][T-android-copilot-model-filter] `CopilotDeviceFlow.parseModelsDetailed`
 * must have EXACTLY TWO reasons to drop a model, and no third.
 *
 * Background (iOS fe625d5fd, same filter shape on both platforms): GitHub
 * enabled `gpt-6-astra` account-wide and it never appeared in the picker. The
 * refresh gate was one half of that report; the other half is this filter,
 * which is the only place a Copilot model can be lost — Copilot has no
 * models.dev entry and no built-in catalog, so anything this function discards
 * is simply gone with nothing behind it.
 *
 * `CopilotDeviceFlowTest` already covers each drop condition in isolation.
 * What it does NOT cover, and what this file adds, is the CLOSED-WORLD claim:
 * every other field `/models` sends — `policy.state`, `billing`, `preview`,
 * `is_chat_default`, `vendor`, `version`, `model_picker_category`,
 * `supports.streaming` — must be inert. A payload growing one new restrictive-
 * looking key is exactly how a model silently stops being offered, and that is
 * invisible until a user reports a missing model.
 *
 * Direction matters in both senses: widening the filter hides models the
 * account owns; narrowing it offers embeddings that fail on first send. Each
 * test below therefore states which side it guards.
 */
class CopilotModelFilterTest {

    private fun parse(body: String) = CopilotDeviceFlow.parseModelsDetailed(JSONObject(body))

    // ── The reported model ────────────────────────────────────────────────

    /**
     * gpt-6-astra as `/models` actually describes it: a full capability object,
     * `policy` present with `state: "enabled"`, billing metadata, a preview
     * flag. Only `model_picker_enabled` and `capabilities.type` may be consulted
     * — everything else here is decoration and must not cost the entry.
     */
    @Test
    fun `gpt-6-astra passes the filter with its full real-world payload`() {
        val parsed = parse(
            """
            {"data":[{
              "id":"gpt-6-astra","name":"GPT-6 Astra","vendor":"OpenAI","version":"gpt-6-astra",
              "model_picker_enabled":true,"model_picker_category":"versatile",
              "preview":true,"is_chat_default":false,"is_chat_fallback":false,
              "billing":{"is_premium":true,"multiplier":1.0,"restricted_to":["pro","enterprise"]},
              "policy":{"state":"enabled","terms":"Subject to the GitHub Terms"},
              "capabilities":{"family":"gpt-6","object":"model_capabilities","type":"chat",
                "tokenizer":"o200k_base",
                "limits":{"max_context_window_tokens":400000,"max_output_tokens":128000,
                  "max_prompt_tokens":272000,"vision":{"max_prompt_image_size":3145728}},
                "supports":{"streaming":true,"tool_calls":true,"parallel_tool_calls":true,
                  "vision":true,"structured_outputs":true,"adaptive_thinking":true,
                  "reasoning_effort":["low","medium","high","xhigh","max"]}}
            }]}
            """.trimIndent(),
        )
        assertEquals(listOf("gpt-6-astra"), parsed.models.map { it.id })
        assertTrue("nothing may be hidden by policy here", parsed.hiddenByPolicy.isEmpty())
        assertTrue("nothing may be dropped by type here", parsed.droppedByType.isEmpty())
        val m = parsed.models.single()
        assertEquals(400_000, m.contextWindow)
        assertEquals(128_000, m.maxOutputTokens)
        assertTrue(m.supportsVision)
        assertEquals(true, m.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), m.reasoningEffortValues)
    }

    // ── The closed world: only two keys may drop a model ──────────────────

    /**
     * Guards the "hides models the account owns" direction. Each field below is
     * given a value that READS restrictive, one at a time, on an otherwise
     * ordinary chat model. All of them must be ignored.
     */
    @Test
    fun `no field other than the two gates can drop a model`() {
        val decoys = listOf(
            """"policy":{"state":"unconfigured"}""",
            """"policy":{"state":"disabled"}""",
            """"billing":{"is_premium":true,"multiplier":50.0}""",
            """"preview":true""",
            """"is_chat_default":false""",
            """"is_chat_fallback":false""",
            """"model_picker_category":"lightweight"""",
            """"vendor":"Some-New-Vendor"""",
            """"version":"0.0.1-alpha"""",
            """"capabilities":{"type":"chat","supports":{"streaming":false}}""",
            """"capabilities":{"type":"chat","supports":{"tool_calls":false}}""",
            """"capabilities":{"type":"chat","family":"an-unknown-family"}""",
            """"deprecated":true""",
            """"enabled":false""",
        )
        for (decoy in decoys) {
            val parsed = parse("""{"data":[{"id":"m","name":"M",$decoy}]}""")
            assertEquals(
                "a model must survive $decoy",
                listOf("m"),
                parsed.models.map { it.id },
            )
            assertTrue("$decoy must not report a policy drop", parsed.hiddenByPolicy.isEmpty())
            assertTrue("$decoy must not report a type drop", parsed.droppedByType.isEmpty())
        }
    }

    /**
     * `policy.state` is the trap worth naming on its own. iOS's Copilot client
     * is the reference implementation and consults only the two gates; a
     * `policy` block appears on premium models for every account that HAS
     * access, so treating it as an entitlement signal would hide precisely the
     * paid models a Pro user is paying for.
     */
    @Test
    fun `policy state is never an entitlement gate`() {
        val parsed = parse(
            """
            {"data":[
              {"id":"premium","model_picker_enabled":true,
               "policy":{"state":"unconfigured"},"capabilities":{"type":"chat"}},
              {"id":"plain","model_picker_enabled":true,"capabilities":{"type":"chat"}}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("premium", "plain"), parsed.models.map { it.id })
    }

    // ── The two gates themselves, at full strength ────────────────────────

    /**
     * Guards the "offers unusable models" direction for gate 1. The check is
     * `!optBoolean(..., true)`, so only an explicit `false` drops — and the
     * default-true is deliberate (see CopilotDeviceFlowTest): absent means
     * visible, because there is no fallback list behind this one.
     */
    @Test
    fun `gate 1 fires only on an explicit false`() {
        assertTrue(parse("""{"data":[{"id":"m","model_picker_enabled":false}]}""").models.isEmpty())
        assertEquals(1, parse("""{"data":[{"id":"m","model_picker_enabled":true}]}""").models.size)
        assertEquals(1, parse("""{"data":[{"id":"m"}]}""").models.size)
    }

    /**
     * Guards both directions for gate 2. A non-chat `type` drops (embeddings
     * cannot serve a conversation); an ABSENT or EMPTY type does not, because
     * the free-tier auto-select entry arrives with no capability object at all
     * and it is the only model such an account can use.
     */
    @Test
    fun `gate 2 fires only on a present non-chat type`() {
        assertTrue(parse("""{"data":[{"id":"e","capabilities":{"type":"embeddings"}}]}""").models.isEmpty())
        assertEquals(1, parse("""{"data":[{"id":"c","capabilities":{"type":"chat"}}]}""").models.size)
        // No capabilities object at all — the free-tier auto-select shape.
        assertEquals(1, parse("""{"data":[{"id":"auto"}]}""").models.size)
        // Present but empty string: also not a stated "this is not chat".
        assertEquals(1, parse("""{"data":[{"id":"blank","capabilities":{"type":""}}]}""").models.size)
    }

    /** Each gate reports its own drops, so a diagnosis names the real reason. */
    @Test
    fun `the two gates are reported separately and never overlap`() {
        val parsed = parse(
            """
            {"data":[
              {"id":"keep","capabilities":{"type":"chat"}},
              {"id":"policy-off","model_picker_enabled":false,"capabilities":{"type":"chat"}},
              {"id":"embed","capabilities":{"type":"embeddings"}}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("keep"), parsed.models.map { it.id })
        assertEquals(listOf("policy-off"), parsed.hiddenByPolicy)
        assertEquals(listOf("embed:embeddings"), parsed.droppedByType)
        assertEquals("totalReturned must count the raw payload", 3, parsed.totalReturned)
    }

    /**
     * Gate order is observable: a model failing BOTH gates is attributed to the
     * picker flag, not to its type. Worth pinning because the two lists drive
     * the user-facing "N models hidden by your organization" explanation.
     */
    @Test
    fun `a model failing both gates is attributed to the picker flag`() {
        val parsed = parse(
            """{"data":[{"id":"both","model_picker_enabled":false,
                        "capabilities":{"type":"embeddings"}}]}""",
        )
        assertEquals(listOf("both"), parsed.hiddenByPolicy)
        assertTrue(parsed.droppedByType.isEmpty())
    }

    // ── Shape robustness ──────────────────────────────────────────────────

    /**
     * An id-less row is skipped without being counted as a drop: it is not a
     * model the account lost, it is a row this parser cannot name. Reporting it
     * as hidden-by-policy would put a phantom in the "hidden models" count.
     */
    @Test
    fun `rows with no usable id are skipped silently`() {
        val parsed = parse("""{"data":[{"name":"nameless"},{"id":""},{"id":"real"}]}""")
        assertEquals(listOf("real"), parsed.models.map { it.id })
        assertTrue(parsed.hiddenByPolicy.isEmpty())
        assertTrue(parsed.droppedByType.isEmpty())
        assertEquals(3, parsed.totalReturned)
    }

    /**
     * The whole filter must degrade to "offer nothing" rather than throw: this
     * runs on a network response, and an exception here would take the sign-in
     * flow with it.
     */
    @Test
    fun `malformed payloads yield an empty result rather than throwing`() {
        for (body in listOf("""{}""", """{"data":[]}""", """{"data":"nope"}""", """{"data":[1,2,3]}""")) {
            val parsed = parse(body)
            assertTrue("$body should parse to nothing", parsed.models.isEmpty())
        }
    }

    /**
     * `parseModelIds` is what the picker actually calls, so it must apply the
     * same two gates — not a looser copy of them.
     */
    @Test
    fun `parseModelIds applies exactly the same filter`() {
        val body = """
            {"data":[
              {"id":"keep","name":"Keep","policy":{"state":"unconfigured"}},
              {"id":"hidden","model_picker_enabled":false},
              {"id":"embed","capabilities":{"type":"embeddings"}}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("keep" to "Keep"),
            CopilotDeviceFlow.parseModelIds(JSONObject(body)),
        )
    }

    // ── Source-grep drift guard ───────────────────────────────────────────

    /**
     * The closed-world claim above is a claim about the ABSENCE of code, which
     * no positive test can express. This reads the filter loop and asserts it
     * contains exactly the two `continue`-bearing gates plus the id/dup guards
     * — so a third gate added later fails here instead of silently hiding a
     * model that only a user report would surface.
     */
    @Test
    fun `the filter loop still has exactly two drop gates`() {
        val src = com.openminis.app.ProductionSources.read("auth/CopilotDeviceFlow.kt")
        val loop = src.substringAfter("fun parseModelsDetailed(")
            .substringBefore("private fun JSONArray")
        assertTrue("gate 1 must read model_picker_enabled defaulting to true",
            loop.contains("""!m.optBoolean("model_picker_enabled", true)"""))
        assertTrue("gate 2 must only fire on a PRESENT non-chat type",
            loop.contains("""type.isNotEmpty() && type != "chat""""))
        // Five `continue`s exactly: non-object row, empty id, gate 1, gate 2,
        // duplicate id. Only two of them are policy decisions; the other three
        // are shape guards, and none of the five may grow a sixth sibling.
        val continues = Regex("\\bcontinue\\b").findAll(loop).count()
        assertEquals(
            "a new `continue` in the filter loop is a new way to lose a model",
            5,
            continues,
        )
        assertFalse(
            "policy state must not become a gate",
            loop.contains("\"policy\""),
        )
    }
}
