package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
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
}
