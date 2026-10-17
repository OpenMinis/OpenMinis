package com.openminis.app.provider.openai

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zen-structured-error] OpenCode Zen answers with a structured body whose
 * error.type carries semantics the generic HTTP mapping cannot see. Mapping a
 * FreeTierError to "Invalid API key" told the user to check a key that is not
 * involved.
 *
 * [T-zen-free-lane-retired] The keyless lane this class used to defend is gone.
 * Measured 2026-10-17: the official 2.0.32 CLI answers 200 on the free models
 * from the same machine, while its own header set replayed from curl, Node,
 * Python and Chromium is refused with this same 403 on every model, over both
 * HTTP/1.1 and HTTP/2 — so the refusal is not a session id the app can fix.
 * The hint therefore says "bring your own Zen key" instead of blaming a
 * malformed session identity.
 */
class ZenStructuredErrorTest {

    @Test
    fun `FreeTierError body maps to a provider refusal, not an invalid key`() {
        val body = """
            {"type":"error","error":{"type":"FreeTierError","message":
            "Error from provider (Console): OpenCode's free tier can only be used from within OpenCode"}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("FreeTierError:"))
        // [T-zen-free-lane-retired] The upstream text names the client, and that
        // is the actual gate: the hint must send the user to their own Zen key,
        // not to another model or another session id.
        assertTrue(msg, msg.contains("official OpenCode client"))
        assertTrue(msg, msg.contains("key"))
    }

    @Test
    fun `ModelError body maps to a catalogue-mismatch refusal`() {
        val body = """
            {"type":"error","error":{"type":"ModelError","message":"Model test is not supported"}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("ModelError:"))
        assertTrue(msg, msg.contains("lists this id but refuses to serve it"))
    }

    @Test
    fun `zen 500 body with generic error type does not match the sniffer`() {
        // {"type":"error","error":{"type":"error",…}} must fall through so the
        // transient-500 mapping (retryable) stays in charge.
        assertNull(
            zenStructuredProviderRefusal(
                """{"type":"error","error":{"type":"error","message":"Internal server error"}}""",
            ),
        )
    }

    /**
     * The geo-fenced ids answer RegionError, not FreeTierError — the refusal is
     * about geography, so the hint must say the model is unreachable from here
     * rather than pointing at the credential.
     */
    @Test
    fun `RegionError body names the geo-fence`() {
        val body = """
            {"type":"error","error":{"type":"RegionError",
            "message":"This model is not available in your country."}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("RegionError:"))
        assertTrue(msg, msg.contains("geo-fenced"))
    }

    @Test
    fun `generic OpenAI error body does not match the sniffer`() {
        assertNull(
            zenStructuredProviderRefusal(
                """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}""",
            ),
        )
    }

    @Test
    fun `non-json body does not match the sniffer`() {
        assertNull(zenStructuredProviderRefusal("gateway timeout"))
        assertNull(zenStructuredProviderRefusal(""))
    }
}
