package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.xai.XAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-provider-dynamic-catalog-reconcile] GH#265 — a Grok model released after
 * the app was built stayed invisible forever.
 *
 * `ProviderRepository.refreshModels` returned `XAIModelsApi.fetchModelsOAuth()`
 * (the hand-authored built-in list) unconditionally for xAI, so the built-in
 * set was the only set obtainable on either credential path, and pressing
 * Refresh re-ran that same code. The fix routes xAI through the generic
 * OpenAI-compatible fetcher with the built-in list demoted to a fallback.
 *
 * These tests exercise the composed expression from refreshModels:
 *
 *     OpenAIModelsApi.fetchModels(key, base).ifEmpty { XAIModelsApi.fetchModelsOAuth() }
 *
 * against a MockWebServer, so the real HTTP + JSON parsing runs with no
 * network and no credential. What they cannot cover is the live api.x.ai
 * contract itself; that was checked by hand (an unauthenticated GET answers
 * 401 `invalid-argument`, i.e. an auth challenge rather than a 404, which is
 * what establishes the endpoint exists and is OpenAI-shaped).
 */
class XAIDynamicCatalogTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Base URL shaped like ProviderConfig.effectiveBaseURL (already carries /v1). */
    private fun baseUrl(): String = server.url("/v1").toString().trimEnd('/')

    /** The production expression under test. */
    private fun resolve(base: String): List<LLMModel> =
        runBlocking {
            OpenAIModelsApi.fetchModels("test-key", base)
                .ifEmpty { XAIModelsApi.fetchModelsOAuth() }
        }

    @Test
    fun liveCatalogSurfacesAModelMissingFromTheBuiltInList() {
        // A model id deliberately NOT in LLMModel.allXAI, standing in for
        // "released after this build".
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"object":"list","data":[
                  {"id":"grok-9.9-unreleased","object":"model","owned_by":"xai"},
                  {"id":"grok-4.6","object":"model","owned_by":"xai"}
                ]}
                """.trimIndent(),
            ),
        )

        val models = resolve(baseUrl())
        val ids = models.map { it.id }

        assertTrue(
            "live catalog must surface an id absent from the built-in list, got $ids",
            ids.contains("grok-9.9-unreleased"),
        )
        assertFalse("live result must not be the built-in fallback", models.isEmpty())
    }

    @Test
    fun requestGoesToTheModelsEndpointOfTheGivenBase() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"grok-4.6","object":"model"}]}"""),
        )

        resolve(baseUrl())

        val path = server.takeRequest().path
        // Regression guard for the null-base trap: an OAuth xAI instance has no
        // customBaseURL, so effectiveBaseURL is null and buildURL(null) would
        // resolve to api.openai.com. refreshModels defaults to api.x.ai/v1
        // precisely so that cannot happen.
        assertEquals("/v1/models", path)
    }

    @Test
    fun httpFailureFallsBackToTheBuiltInCatalog() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"nope"}"""))

        val models = resolve(baseUrl())

        // Custom base => fetchModels returns empty on failure (never a
        // wrong-vendor guess), so the built-in list is what the user sees.
        assertFalse("must not end up with an empty picker", models.isEmpty())
        assertTrue(
            "fallback must be the xAI built-ins, got ${models.map { it.id }}",
            models.any { it.id == "grok-4.6" },
        )
        assertTrue(
            "fallback must not contain OpenAI models",
            models.none { it.id.startsWith("gpt-") },
        )
    }

    @Test
    fun malformedBodyFallsBackRatherThanEmptyingThePicker() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        val models = resolve(baseUrl())

        assertFalse(models.isEmpty())
        assertTrue(models.any { it.provider == "xAI" })
    }

    @Test
    fun builtInCatalogContainsGrok46() {
        // The seed is what the user sees before the first network round-trip
        // completes, so the newest known model belongs in it even though the
        // dynamic path no longer depends on that.
        assertTrue(
            "grok-4.6 missing from LLMModel.allXAI",
            LLMModel.allXAI.any { it.id == "grok-4.6" },
        )
    }

    @Test
    fun builtInCatalogHasNoDuplicateIds() {
        val ids = LLMModel.allXAI.map { it.id }
        assertEquals("duplicate ids in allXAI: $ids", ids.size, ids.toSet().size)
    }
}
