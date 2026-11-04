package com.openminis.app.inbound

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/**
 * [inbound-task-api] The listener on a real loopback socket. Loopback is
 * NOT exempt — that exemption belongs to the debug server only.
 */
class InboundTaskServerTest {

    private val token = "super-secret-token-value"
    private val prompt = "SUPER_SECRET_PROMPT_DO_NOT_LOG"
    private val logs = mutableListOf<String>()
    private val starter = FakeStarter()
    private var server: InboundTaskServer? = null

    @Before
    fun setUp() {
        logs.clear()
        starter.starts.clear()
        val created = InboundTaskServer(
            port = 0,
            bindHost = "127.0.0.1",
            token = { token },
            starter = starter,
            log = { logs += it },
            readTimeoutMs = 2_000,
        )
        created.start()
        assertTrue("listener did not bind", created.awaitBound())
        server = created
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun `loopback still requires the bearer token`() {
        val started = System.nanoTime()
        val response = exchange(method = "POST", path = "/v1/tasks", bearer = null, body = null, contentLength = 100_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(401, response.status)
        assertEquals("unauthorized", JSONObject(response.body).getString("error"))
        assertTrue(response.head.contains("WWW-Authenticate: Bearer"))
        assertFalse(response.head.contains(token))
        assertTrue("auth waited for the body (${elapsedMs}ms)", elapsedMs < 1_500)
        assertTrue(starter.starts.isEmpty())
        assertLogsAreClean()
    }

    @Test
    fun `wrong token and non-bearer headers are rejected`() {
        assertEquals(401, exchange(method = "POST", path = "/v1/tasks", bearer = "nope", body = """{"prompt":"$prompt"}""").status)
        val headerOnly = raw(
            "POST /v1/tasks HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Minis-Token: $token\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
        )
        assertEquals(401, headerOnly.status)
        val query = exchange(method = "GET", path = "/v1/health?access_token=$token", bearer = null, body = null)
        assertEquals(401, query.status)
        assertTrue(starter.starts.isEmpty())
        assertLogsAreClean()
    }

    @Test
    fun `post starts a task and does not echo the prompt`() {
        val response = exchange(
            method = "POST",
            path = "/v1/tasks",
            bearer = "Bearer $token",
            body = """{"prompt":"$prompt","label":"from-laptop"}""",
        )
        assertEquals(202, response.status)
        val json = JSONObject(response.body)
        assertEquals("task-1", json.getString("id"))
        assertEquals("sess-1", json.getString("sessionId"))
        assertEquals("running", json.getString("status"))
        assertEquals("new", json.getString("target"))
        assertFalse(response.body.contains(prompt))
        assertFalse(response.body.contains(token))
        assertEquals(prompt, starter.starts.single().prompt)
        assertEquals("from-laptop", starter.starts.single().label)
        assertLogsAreClean()
    }

    @Test
    fun `follow-up session is forwarded`() {
        val response = exchange(
            method = "POST",
            path = "/v1/tasks",
            bearer = "bearer $token",
            body = """{"prompt":"$prompt","session":"6d0f1234-abcd-4000-8000-000000000001"}""",
        )
        assertEquals(202, response.status)
        assertEquals("follow-up", JSONObject(response.body).getString("target"))
        assertEquals("6d0f1234-abcd-4000-8000-000000000001", starter.starts.single().session)
        assertLogsAreClean()
    }

    @Test
    fun `oversized content length is refused before the body is read`() {
        val started = System.nanoTime()
        val response = exchange(
            method = "POST",
            path = "/v1/tasks",
            bearer = "Bearer $token",
            body = null,
            contentLength = InboundTaskProtocol.MAX_BODY_BYTES + 1,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(413, response.status)
        assertEquals("body_too_large", JSONObject(response.body).getString("error"))
        assertTrue("413 waited for the body (${elapsedMs}ms)", elapsedMs < 1_500)
        assertTrue(starter.starts.isEmpty())
    }

    @Test
    fun `missing length and plain text are rejected`() {
        val missing = exchange(method = "POST", path = "/v1/tasks", bearer = "Bearer $token", body = null)
        assertEquals(411, missing.status)
        val plain = raw(
            "POST /v1/tasks HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
                "Content-Type: text/plain\r\nContent-Length: 11\r\nConnection: close\r\n\r\n" +
                "not-a-json!",
        )
        assertEquals(415, plain.status)
        assertTrue(starter.starts.isEmpty())
        assertLogsAreClean()
    }

    @Test
    fun `health and status stay behind the token`() {
        assertEquals(401, exchange(method = "GET", path = "/v1/health", bearer = null, body = null).status)
        val health = exchange(method = "GET", path = "/v1/health", bearer = "Bearer $token", body = null)
        assertEquals(200, health.status)
        val healthJson = JSONObject(health.body)
        assertTrue(healthJson.getBoolean("ok"))
        assertEquals("bearer", healthJson.getString("auth"))
        assertFalse(health.body.contains(token))

        val missing = exchange(method = "GET", path = "/v1/tasks/missing", bearer = "Bearer $token", body = null)
        assertEquals(404, missing.status)

        val status = exchange(method = "GET", path = "/v1/tasks/task-1", bearer = "Bearer $token", body = null)
        assertEquals(200, status.status)
        val json = JSONObject(status.body)
        assertEquals("running", json.getString("status"))
        assertEquals("sess-1", json.getString("sessionId"))
        assertFalse(json.has("prompt"))
        assertLogsAreClean()
    }

    @Test
    fun `starter rejection is forwarded without the prompt`() {
        starter.next = StartOutcome.Rejected(404, "session_not_found")
        val response = exchange(
            method = "POST",
            path = "/v1/tasks",
            bearer = "Bearer $token",
            body = """{"prompt":"$prompt","session":"6d0f1234-abcd-4000-8000-000000000099"}""",
        )
        assertEquals(404, response.status)
        assertEquals("session_not_found", JSONObject(response.body).getString("error"))
        assertFalse(response.body.contains(prompt))
        assertLogsAreClean()
    }

    private fun assertLogsAreClean() {
        val joined = logs.joinToString("\n")
        assertFalse(joined, joined.contains(token))
        assertFalse(joined, joined.contains(prompt))
        assertFalse(joined, joined.contains("Authorization"))
    }

    private fun exchange(
        method: String,
        path: String,
        bearer: String?,
        body: String?,
        contentLength: Int? = body?.toByteArray(Charsets.UTF_8)?.size,
    ): HttpResult {
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            if (bearer != null) append("Authorization: ").append(bearer).append("\r\n")
            if (body != null) append("Content-Type: application/json\r\n")
            if (contentLength != null) append("Content-Length: ").append(contentLength).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val payload = if (body != null && contentLength == body.toByteArray(Charsets.UTF_8).size) {
            head.toByteArray(Charsets.US_ASCII) + body.toByteArray(Charsets.UTF_8)
        } else {
            head.toByteArray(Charsets.US_ASCII)
        }
        return raw(payload)
    }

    private fun raw(text: String): HttpResult = raw(text.toByteArray(Charsets.UTF_8))

    private fun raw(payload: ByteArray): HttpResult {
        val port = server!!.boundPort()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }
            val text = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val status = text.substringAfter(' ').substringBefore(' ').toInt()
            val split = text.indexOf("\r\n\r\n")
            val head = if (split < 0) text else text.substring(0, split)
            val body = if (split < 0) "" else text.substring(split + 4)
            return HttpResult(status, head, body)
        }
    }

    private data class HttpResult(val status: Int, val head: String, val body: String)

    private class FakeStarter : InboundTaskStarter {
        val starts = mutableListOf<Call>()
        var next: StartOutcome = StartOutcome.Started("task-1", "sess-1", "new")

        override fun start(prompt: String, session: String?, label: String?): StartOutcome {
            val outcome = next
            val target = if (session == null) "new" else "follow-up"
            starts += Call(prompt, session, label)
            return when (outcome) {
                is StartOutcome.Started -> outcome.copy(target = target)
                is StartOutcome.Rejected -> outcome
            }
        }

        override fun status(id: String): TaskView? {
            if (id != "task-1") return null
            return TaskView(id, "note", "running", "new", "sess-1", null)
        }
    }

    private data class Call(val prompt: String, val session: String?, val label: String?)
}
