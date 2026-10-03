package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-context-overflow-selfheal] (GH#352) Which failures may mutate a
 * user's history, and — far more importantly — which may not.
 *
 * The self-heal rewrites a persisted conversation, so every negative case here
 * is load-bearing: a false positive would offload a part over an error that
 * offloading cannot fix.
 */
class ContextOverflowGuardTest {

    private fun check(status: Int?, detail: String?) =
        ContextOverflowGuard.isContextOverflow(status, detail)

    @Test
    fun `the GH#352 error is recognised`() {
        assertTrue(
            check(400, "maximum context length is 1048576 tokens, however you requested ≈1090600 tokens"),
        )
    }

    @Test
    fun `common overflow wordings are recognised`() {
        assertTrue(check(400, "This model's maximum context length is 128000 tokens"))
        assertTrue(check(400, "context_length_exceeded"))
        assertTrue(check(400, "Please reduce the length of the messages"))
        assertTrue(check(400, "prompt is too long: 1201842 tokens > 200000"))
        assertTrue(check(413, "Request too large for this model"))
        assertTrue(check(400, "内容过长，请缩短后重试"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(check(400, "MAXIMUM CONTEXT LENGTH IS 1048576"))
    }

    @Test
    fun `an auth failure never triggers a history mutation`() {
        assertFalse(check(401, "Invalid API key"))
        assertFalse(check(403, "Forbidden"))
    }

    @Test
    fun `a rate limit never triggers a history mutation`() {
        assertFalse(check(429, "Rate limited, please retry"))
    }

    @Test
    fun `a server error never triggers a history mutation`() {
        assertFalse(check(500, "internal error"))
        assertFalse(check(503, "upstream unavailable"))
    }

    @Test
    fun `an unrelated 400 never triggers a history mutation`() {
        // These are the dangerous ones: same status, nothing to do with size.
        assertFalse(check(400, "Malformed JSON in request body"))
        assertFalse(check(400, "content policy violation"))
        assertFalse(check(400, "Invalid reasoning_effort: xhigh"))
        assertFalse(check(400, "unknown variant `image_url`"))
        assertFalse(
            check(400, "An assistant message with 'tool_calls' must be followed by tool messages"),
        )
    }

    @Test
    fun `a missing status or detail never triggers`() {
        assertFalse(check(null, "maximum context length is 1048576"))
        assertFalse(check(400, null))
        assertFalse(check(400, ""))
    }
}
