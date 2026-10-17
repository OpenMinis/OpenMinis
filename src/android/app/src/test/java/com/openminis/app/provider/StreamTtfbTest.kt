package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamTtfbTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call() = client.newCall(
        Request.Builder().url(server.url("/v1/messages")).build(),
    )

    @Test
    fun normalResponseIsReturnedUnchanged() {
        server.enqueue(MockResponse().setBody("ok"))
        StreamTtfb.execute(call(), "test").use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body?.string())
        }
    }

    @Test
    fun headersThatNeverArriveAreCancelledAndMappedToTransientError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        try {
            StreamTtfb.execute(call(), "test", timeoutMs = 150L).close()
            throw AssertionError("expected TransientError")
        } catch (e: LLMError.TransientError) {
            assertTrue(e.message!!.contains("TTFB"))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("watchdog must fire quickly, took ${elapsedMs}ms", elapsedMs < 5_000L)
    }
}
