package com.openminis.app.debug

import com.openminis.app.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-llmlog-redact] debug.llmRequests must never hand out a credential.
 *
 * Found while testing the OpenCode Go provider on a Pixel 4a: the captured
 * entry carried `Authorization: Bearer oc_sk_…` verbatim, while iOS records
 * `Bearer ***`. A LAN client reaches debug.llmRequests with only the debug
 * server token, so every provider key was one RPC away.
 */
class LLMRequestLogRedactionTest {

    private val secret = "oc_sk_TEST_0123456789abcdef"

    @After fun tearDown() = LLMRequestLog.clear()

    @Test fun bearerTokenIsMaskedButSchemeKept() {
        val out = LLMRequestLog.redactHeaders(mapOf("Authorization" to "Bearer $secret"))
        assertEquals("Bearer ***", out["Authorization"])
    }

    @Test fun headerNamesAreMatchedCaseInsensitively() {
        val out = LLMRequestLog.redactHeaders(
            mapOf("authorization" to "Bearer $secret", "X-Api-Key" to secret, "x-goog-api-key" to secret),
        )
        assertEquals("Bearer ***", out["authorization"])
        assertEquals("***", out["X-Api-Key"])
        assertEquals("***", out["x-goog-api-key"])
    }

    @Test fun anthropicApiKeyAndCookiesAreMasked() {
        val out = LLMRequestLog.redactHeaders(
            mapOf("x-api-key" to secret, "Cookie" to "sid=$secret", "Proxy-Authorization" to "Basic $secret"),
        )
        assertEquals("***", out["x-api-key"])
        assertEquals("***", out["Cookie"])
        assertEquals("Basic ***", out["Proxy-Authorization"])
    }

    @Test fun authorizationWithoutSchemeIsFullyMasked() {
        assertEquals("***", LLMRequestLog.redactHeaders(mapOf("Authorization" to secret))["Authorization"])
    }

    @Test fun nonSecretHeadersAreKept() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "x-opencode-session" to "a828aa3c-a807-4ee7-bf78-e6a5abec9bc6",
            "User-Agent" to "Minis/1.15",
            "anthropic-beta" to "prompt-caching-2024-07-31",
        )
        assertEquals(headers, LLMRequestLog.redactHeaders(headers))
    }

    @Test fun keyQueryParametersAreMasked() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/x:stream?alt=sse&key=***",
            LLMRequestLog.redactUrl("https://generativelanguage.googleapis.com/v1beta/models/x:stream?alt=sse&key=$secret"),
        )
        assertEquals(
            "https://h/p?api_key=***&model=m#frag",
            LLMRequestLog.redactUrl("https://h/p?api_key=$secret&model=m#frag"),
        )
    }

    @Test fun urlsWithoutSecretsAreUnchanged() {
        val url = "https://opencode.ai/zen/go/v1/chat/completions"
        assertEquals(url, LLMRequestLog.redactUrl(url))
        assertEquals("https://h/p?model=m&stream=true", LLMRequestLog.redactUrl("https://h/p?model=m&stream=true"))
    }

    @Test fun storedEntriesNeverContainTheKey() {
        LLMRequestLog.clear()
        LLMRequestLog.add(
            LLMRequestLog.Entry(
                provider = "openai",
                requestURL = "https://opencode.ai/zen/go/v1/chat/completions?key=$secret",
                requestHeaders = mapOf("Authorization" to "Bearer $secret", "x-opencode-session" to "sid-1"),
                requestBody = "{}",
            ),
        )
        if (BuildConfig.DEV_TOOLS) {
            val stored = LLMRequestLog.getAll().single()
            assertFalse(stored.toString().contains(secret))
            assertEquals("Bearer ***", stored.requestHeaders["Authorization"])
            assertEquals("sid-1", stored.requestHeaders["x-opencode-session"])
        } else {
            assertTrue("developer logging is disabled", LLMRequestLog.getAll().isEmpty())
            assertTrue("no request remains in the tail", LLMRequestLog.getLast(1).isEmpty())
            assertNull("the full request body is not retained", LLMRequestLog.lastFullJSON())
        }
    }
}
