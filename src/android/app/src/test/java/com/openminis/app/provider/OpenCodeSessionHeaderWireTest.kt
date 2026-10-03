package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [T-android-opencode-session-header] The header decision as it reaches the
 * wire, rather than as a pure function.
 *
 * [OpenCodeSessionHeaderTest] pins the rule; this pins that the rule is
 * actually consulted while a request is built, and — the part worth a live
 * server — that setting a session id on a provider pointed somewhere else
 * leaks nothing. A unit test of the helper alone could pass while the call
 * site was dropped, so this is the regression net for the wiring.
 *
 * The positive case cannot be exercised here: MockWebServer serves 127.0.0.1,
 * which by design fails the host gate. Its absence below IS the leak assertion.
 */
class OpenCodeSessionHeaderWireTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel.gpt4oMini,
        basePath = server.url("/").toString().trimEnd('/'),
    )

    /**
     * The privacy regression. A user with an OpenCode instance also has other
     * providers; every one of them is built through the same factory and given
     * the same conversation id. None may transmit it.
     */
    @Test
    fun `a non-opencode host receives no session header`() = runBlocking {
        val p = provider().apply { sessionId = "3f2a1c7e-9b04-4d55-8e21-0a7c6f5b3d19" }
        p.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64)
        assertNull(
            "the conversation id must not reach a non-OpenCode endpoint",
            server.takeRequest().getHeader(OpenCodeSessionHeader.HEADER),
        )
    }

    /** A provider with no id set behaves exactly as before this feature. */
    @Test
    fun `a provider with no session id sends no header`() = runBlocking {
        provider().sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64)
        assertNull(server.takeRequest().getHeader(OpenCodeSessionHeader.HEADER))
    }

    /**
     * The property is a plain `var` read per request, which is what lets a
     * draft promoted by `ensureSession()` start sending the header without the
     * provider being rebuilt. Pinning the round-trip guards that mechanism.
     */
    @Test
    fun `the session id is readable back off the provider`() {
        val p = provider()
        assertNull(p.sessionId)
        p.sessionId = "abc"
        assertEquals("abc", p.sessionId)
    }
}
