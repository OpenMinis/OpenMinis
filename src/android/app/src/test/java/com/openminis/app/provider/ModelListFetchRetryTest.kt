package com.openminis.app.provider

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Policy matrix for [ModelListFetchRetry], plus end-to-end proof against a
 * [MockWebServer] that the retry actually changes the verdict a caller sees.
 *
 * The integration cases are the point: `classify` alone cannot show that a
 * gateway which 401s once and then serves the catalog stops emptying the model
 * picker.
 */
class ModelListFetchRetryTest {

    private lateinit var server: MockWebServer

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(): Request = Request.Builder().url(server.url("/v1/models")).build()

    private fun MockWebServer.enqueueCode(code: Int, body: String = "{}") {
        enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    // ── pure policy: what is worth a second sample ─────────────────────

    @Test
    fun `2xx is success on the first attempt`() {
        for (code in listOf(200, 201, 204, 299)) {
            assertEquals("code=$code", ModelListFetchRetry.Verdict.SUCCESS, ModelListFetchRetry.classify(code, false, 1))
        }
    }

    @Test
    fun `401 and 403 get exactly one retry, then are final`() {
        for (code in listOf(401, 403)) {
            assertEquals("code=$code attempt1", ModelListFetchRetry.Verdict.RETRY, ModelListFetchRetry.classify(code, false, 1))
            assertEquals("code=$code attempt2", ModelListFetchRetry.Verdict.FATAL, ModelListFetchRetry.classify(code, false, 2))
        }
        assertEquals(2, ModelListFetchRetry.AUTH_ATTEMPTS)
    }

    @Test
    fun `429 and 5xx retry to the general cap`() {
        for (code in listOf(429, 500, 502, 503, 504, 599)) {
            assertEquals("code=$code attempt1", ModelListFetchRetry.Verdict.RETRY, ModelListFetchRetry.classify(code, false, 1))
            assertEquals("code=$code attempt2", ModelListFetchRetry.Verdict.RETRY, ModelListFetchRetry.classify(code, false, 2))
            assertEquals("code=$code attempt3", ModelListFetchRetry.Verdict.FATAL, ModelListFetchRetry.classify(code, false, 3))
        }
        assertEquals(3, ModelListFetchRetry.MAX_ATTEMPTS)
    }

    @Test
    fun `definitive client errors are never retried`() {
        // A repeat of a byte-identical request cannot change these answers;
        // burning the budget on them turns a config typo into a long hang.
        for (code in listOf(400, 404, 405, 415, 422, 451)) {
            assertEquals("code=$code", ModelListFetchRetry.Verdict.FATAL, ModelListFetchRetry.classify(code, false, 1))
        }
    }

    @Test
    fun `a surfaced redirect is treated as final`() {
        // OkHttp follows redirects itself, so a 3xx reaching the caller means
        // the redirect could not be followed — retrying will not help.
        for (code in listOf(301, 302, 307, 308)) {
            assertEquals("code=$code", ModelListFetchRetry.Verdict.FATAL, ModelListFetchRetry.classify(code, false, 1))
        }
    }

    @Test
    fun `transport failure retries to the general cap`() {
        assertEquals(ModelListFetchRetry.Verdict.RETRY, ModelListFetchRetry.classify(null, true, 1))
        assertEquals(ModelListFetchRetry.Verdict.RETRY, ModelListFetchRetry.classify(null, true, 2))
        assertEquals(ModelListFetchRetry.Verdict.FATAL, ModelListFetchRetry.classify(null, true, 3))
    }

    @Test
    fun `backoff is short for an auth challenge and ladders otherwise`() {
        assertEquals(700L, ModelListFetchRetry.delayMillis(1, 401))
        assertEquals(700L, ModelListFetchRetry.delayMillis(1, 403))
        // The auth delay does not ladder: there is only ever one auth retry.
        assertEquals(700L, ModelListFetchRetry.delayMillis(2, 401))
        assertEquals(500L, ModelListFetchRetry.delayMillis(1, 500))
        assertEquals(500L, ModelListFetchRetry.delayMillis(1, null))
        assertEquals(1_500L, ModelListFetchRetry.delayMillis(2, 503))
        assertEquals(1_500L, ModelListFetchRetry.delayMillis(2, 429))
    }

    // ── end to end: the verdict the caller actually receives ───────────

    @Test
    fun `a transient 401 followed by 200 recovers instead of emptying the list`() = runBlocking {
        server.enqueueCode(401, """{"error":{"message":"Invalid token (request id: x)"}}""")
        server.enqueueCode(200, """{"object":"list","data":[{"id":"m1"}]}""")

        val response = ModelListFetchRetry.execute(client, request(), "TestModels")

        response.use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("m1"))
        }
        assertEquals("exactly one retry", 2, server.requestCount)
    }

    @Test
    fun `a genuinely rejected credential still reports 401 after one retry`() = runBlocking {
        server.enqueueCode(401, """{"error":{"message":"Unauthorized"}}""")
        server.enqueueCode(401, """{"error":{"message":"Unauthorized"}}""")
        server.enqueueCode(200, """{"object":"list","data":[]}""")

        val response = ModelListFetchRetry.execute(client, request(), "TestModels")

        response.use { assertEquals(401, it.code) }
        // The third enqueued 200 must never be reached: AUTH_ATTEMPTS is 2.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 400 is returned immediately without spending the budget`() = runBlocking {
        server.enqueueCode(400, """{"error":{"message":"bad request"}}""")

        val response = ModelListFetchRetry.execute(client, request(), "TestModels")

        response.use { assertEquals(400, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a flapping 5xx recovers on the last allowed attempt`() = runBlocking {
        server.enqueueCode(503)
        server.enqueueCode(502)
        server.enqueueCode(200, """{"object":"list","data":[{"id":"m1"}]}""")

        val response = ModelListFetchRetry.execute(client, request(), "TestModels")

        response.use { assertEquals(200, it.code) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `exhausted 5xx hands the last response back rather than throwing`() = runBlocking {
        // Callers branch on !isSuccessful to invalidate the cache and return
        // their fallback; that path must still see a real status code.
        server.enqueueCode(503)
        server.enqueueCode(503)
        server.enqueueCode(503)

        val response = ModelListFetchRetry.execute(client, request(), "TestModels")

        response.use { assertEquals(503, it.code) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `the budget stops the loop even while attempts remain`() = runBlocking {
        for (i in 1..6) server.enqueueCode(503)

        // A budget already spent on arrival: the first attempt runs, the loop
        // then returns that response instead of consuming the enqueued rest.
        // [T-budget-flake] 0L, not 1L: with 1L the property only holds when
        // the first localhost round trip takes >= 1 ms — elapsed is measured
        // in whole milliseconds, so a sub-millisecond MockWebServer response
        // leaves remaining = 1 > 0 and the loop retries, failing the
        // requestCount assertion on fast machines. 0L is spent on arrival on
        // every machine, fast or slow.
        val response = ModelListFetchRetry.execute(
            client, request(), "TestModels", totalBudgetMillis = 0L,
        )

        response.use { assertEquals(503, it.code) }
        assertEquals(1, server.requestCount)
    }
}
