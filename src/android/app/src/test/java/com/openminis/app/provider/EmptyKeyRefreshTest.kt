package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-refresh-models-empty-key] "Refresh model list" failed for a
 * self-hosted provider configured with no API key.
 *
 * `ProviderRepository.refreshModels` read the credential with `loadApiKey`,
 * which returns null when nothing is stored, and its provider-API step is
 * gated on `apiKey != null`. So for a keyless local server the fetch was never
 * attempted: the flow fell through to the models.dev lookup, which knows
 * nothing about a private host, and the user saw a failure describing the
 * fallback rather than the server they had pointed at.
 *
 * The fix routes it through `usableApiKey`, which substitutes "" exactly where
 * an empty key is a valid configuration. That decision is
 * `ProviderInstance.allowsEmptyAPIKey`, pinned here — `refreshModels` itself
 * needs a Context and encrypted prefs, so this covers the predicate the fix
 * turns on rather than the coroutine around it.
 *
 * Both directions matter. Widening this predicate would send unauthenticated
 * requests to official endpoints; narrowing it re-breaks the reported case.
 */
class EmptyKeyRefreshTest {

    private fun instance(
        type: ProviderType,
        credential: ProviderCredential,
        baseURL: String?,
    ) = ProviderInstance(
        id = "test",
        label = "Test",
        providerType = type,
        credentialType = credential,
        customBaseURL = baseURL,
    )

    // ── The reported case ─────────────────────────────────────────────────

    @Test
    fun `self-hosted OpenAI-compatible endpoint may have an empty key`() {
        // ollama / LM Studio / LiteLLM / an unauthenticated internal gateway.
        assertTrue(
            instance(ProviderType.openAI, ProviderCredential.apiKey, "http://192.168.1.10:11434/v1")
                .allowsEmptyAPIKey,
        )
    }

    @Test
    fun `self-hosted Anthropic-compatible endpoint may have an empty key`() {
        assertTrue(
            instance(ProviderType.anthropic, ProviderCredential.apiKey, "http://10.0.0.5:8080")
                .allowsEmptyAPIKey,
        )
    }

    // ── Cases that must KEEP requiring a key ──────────────────────────────

    @Test
    fun `official endpoint with no custom base URL still requires a key`() {
        // An empty key against api.openai.com is always a misconfiguration —
        // failing locally is more useful than a guaranteed 401.
        assertFalse(
            instance(ProviderType.openAI, ProviderCredential.apiKey, null).allowsEmptyAPIKey,
        )
        assertFalse(
            instance(ProviderType.anthropic, ProviderCredential.apiKey, null).allowsEmptyAPIKey,
        )
    }

    @Test
    fun `blank custom base URL counts as no custom base URL`() {
        assertFalse(
            instance(ProviderType.openAI, ProviderCredential.apiKey, "   ").allowsEmptyAPIKey,
        )
    }

    @Test
    fun `OAuth instances are never empty-key valid`() {
        // Their credential IS the token; a missing one must keep
        // short-circuiting rather than sending an unauthenticated request.
        assertFalse(
            instance(ProviderType.openAI, ProviderCredential.oauth, "https://relay.example.com/v1")
                .allowsEmptyAPIKey,
        )
    }

    @Test
    fun `non OpenAI-compatible families are not covered`() {
        // Gemini and OpenRouter authenticate differently and have no
        // keyless self-hosted story; the gate stays {openAI, anthropic}.
        for (type in listOf(ProviderType.gemini, ProviderType.openRouter)) {
            assertFalse(
                "expected $type to require a key",
                instance(type, ProviderCredential.apiKey, "https://example.com/v1")
                    .allowsEmptyAPIKey,
            )
        }
    }

    // ── [M06] The catalog must be fetched at the moment a provider is added ─
    //
    // Same report family (GH#265), other half: after completing OAuth the model
    // list showed the compiled-in catalog until the user found
    // Settings → provider → Models → Refresh. On iOS the cause was a gate in
    // `addInstance` that only auto-refreshed for `hasManualToken ||
    // openRouter`, so every OAuth provider that DOES have live discovery — xAI,
    // kimiCode, antigravity, anthropic, gemini — fell outside it (752056454).
    //
    // Android's structure is different and that difference is the thing worth
    // pinning: there is no capability bit and no single gate, because all THREE
    // add paths in AddProviderScreen call `refreshModels` unconditionally right
    // after `addInstance` (656a5f0b0 audited exactly this). That is a correct
    // design, but it is correct only as long as all three keep doing it — a
    // fourth add path, or one of the three losing its call, reproduces the iOS
    // bug with no compile error and no test failure anywhere else. These are
    // source facts for that reason; the screens are Compose and the repository
    // needs Context + EncryptedSharedPreferences, so neither is reachable from
    // a plain JVM test.

    private fun src(path: String) = com.openminis.app.ProductionSources.read(path)

    private val addScreenSrc by lazy { src("ui/settings/AddProviderScreen.kt") }

    @Test
    fun `every add path refreshes the catalog immediately after addInstance`() {
        // Three paths: API key, OAuth sign-in button, manual bearer token.
        val addCalls = Regex("""providerRepository\.addInstance\(""")
            .findAll(addScreenSrc).count()
        // [T-provider-refresh-outlives-screen] The pairing is now with
        // triggerAsyncModelReconcile: a refreshModels launched on the screen's
        // own scope was cancelled by onSaved()'s pop — the same GH#265 symptom.
        val refreshCalls = Regex("""providerRepository\.triggerAsyncModelReconcile\(""")
            .findAll(addScreenSrc).count()
        assertTrue("expected the three add paths, found $addCalls", addCalls >= 3)
        assertEquals(
            "every addInstance must be paired with a refreshModels — an unpaired " +
                "one is GH#265 (a brand-new provider showing this build's " +
                "compiled-in catalog until the user finds the manual Refresh)",
            addCalls,
            refreshCalls,
        )
    }

    @Test
    fun `the refresh follows the add rather than preceding it`() {
        // Ordering is load-bearing: refreshModels resolves the instance's
        // credential and base URL out of the repository, so calling it before
        // addInstance would fetch against an instance that does not exist yet
        // and quietly return the built-in seed — the exact symptom, from the
        // opposite direction.
        var searchFrom = 0
        var pairs = 0
        while (true) {
            val add = addScreenSrc.indexOf("providerRepository.addInstance(", searchFrom)
            if (add < 0) break
            val refresh = addScreenSrc.indexOf("providerRepository.triggerAsyncModelReconcile(", add)
            assertTrue("addInstance at $add has no refreshModels after it", refresh > add)
            // The pair must be close together — same onClick handler, not a
            // refresh belonging to some later path.
            assertTrue(
                "addInstance at $add and its refresh are ${refresh - add} chars apart",
                refresh - add < 900,
            )
            pairs++
            searchFrom = add + 1
        }
        assertTrue("expected at least three add/refresh pairs, found $pairs", pairs >= 3)
    }

    // ── [M06] xAI must return the LIVE list, not the built-in one ───────────
    //
    // The third strand of GH#265: `refreshModels` returned
    // `XAIModelsApi.fetchModelsOAuth()` unconditionally for xAI, so the
    // hand-authored list was the only list obtainable on either credential path
    // and pressing Refresh re-ran that same code — grok-4.6 could never appear.
    //
    // XAIDynamicCatalogTest drives the composed expression against a
    // MockWebServer. What is pinned here is that the composition is still the
    // shape that test exercises, and that the built-in list is positioned as a
    // FALLBACK rather than the answer.

    @Test
    fun `xAI refresh fetches live and falls back to the built-in list`() {
        val repoSrc = src("data/repository/ProviderRepository.kt")
        val branch = repoSrc.substringAfter("ProviderType.xAI -> OpenAIModelsApi.fetchModels(")
            .substringBefore("ProviderType.kimiCode")
        assertTrue(
            "the built-in list must be reachable only through ifEmpty {}",
            branch.contains("ifEmpty") && branch.contains("XAIModelsApi.fetchModelsOAuth()"),
        )
        // And it must not be the unconditional answer any more.
        assertFalse(
            "xAI must not return the built-in catalog directly",
            repoSrc.contains("ProviderType.xAI -> com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth()"),
        )
    }

    @Test
    fun `the built-in xAI seed still exists and carries the reported model`() {
        // The seed is what paints Add Provider before the network answers, so it
        // must not be emptied "because the fetch covers it" — that would make the
        // first screen blank on a slow link. grok-4.6 is the model from the
        // report and its presence keeps the pre-network paint honest.
        val ids = com.openminis.app.data.model.LLMModel.allXAI.map { it.id }
        assertTrue("the seed must not be empty", ids.isNotEmpty())
        assertTrue("grok-4.6 is the GH#265 model: $ids", ids.contains("grok-4.6"))
    }
}
