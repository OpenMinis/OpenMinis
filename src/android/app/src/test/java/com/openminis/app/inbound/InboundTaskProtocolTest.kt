package com.openminis.app.inbound

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [inbound-task-api] Auth, routing, and body rules with no socket and no device. */
class InboundTaskProtocolTest {

    private val token = "a".repeat(64)

    @Test
    fun `port is not the debug server`() {
        assertEquals(9473, InboundTaskProtocol.PORT)
        assertTrue(InboundTaskProtocol.PORT != 8321)
        assertTrue(InboundTaskProtocol.PORT != 5321)
        assertEquals("0.0.0.0", InboundTaskServer.BIND_HOST)
        assertFalse(InboundTaskServer.BIND_HOST == "127.0.0.1")
    }

    @Test
    fun `bearer compare accepts only the exact token`() {
        assertTrue(InboundTaskProtocol.tokensMatch(token, token))
        assertFalse(InboundTaskProtocol.tokensMatch(null, token))
        assertFalse(InboundTaskProtocol.tokensMatch("", token))
        assertFalse(InboundTaskProtocol.tokensMatch(token, ""))
        assertFalse(InboundTaskProtocol.tokensMatch("", ""))
        assertFalse(InboundTaskProtocol.tokensMatch("deadbeef", token))
        assertFalse(InboundTaskProtocol.tokensMatch(token.dropLast(1) + "b", token))
        assertFalse(InboundTaskProtocol.tokensMatch("b" + token.drop(1), token))
        assertFalse(InboundTaskProtocol.tokensMatch(token + "a", token))
    }

    @Test
    fun `only Authorization Bearer is a credential`() {
        assertEquals(token, InboundTaskProtocol.parseBearer("Bearer $token"))
        assertEquals(token, InboundTaskProtocol.parseBearer("bearer $token"))
        assertEquals(token, InboundTaskProtocol.parseBearer("  BEARER   $token  "))
        assertNull(InboundTaskProtocol.parseBearer(null))
        assertNull(InboundTaskProtocol.parseBearer("Bearer"))
        assertNull(InboundTaskProtocol.parseBearer("Bearer "))
        assertNull(InboundTaskProtocol.parseBearer("Basic $token"))
        assertNull(InboundTaskProtocol.parseBearer(token))
        assertNull(InboundTaskProtocol.parseBearer("Bearer $token extra"))
    }

    @Test
    fun `routes`() {
        assertTrue(InboundTaskProtocol.route("POST", "/v1/tasks") is InboundTaskProtocol.Route.CreateTask)
        assertTrue(InboundTaskProtocol.route("post", "/v1/tasks/") is InboundTaskProtocol.Route.CreateTask)
        assertTrue(InboundTaskProtocol.route("GET", "/v1/tasks") is InboundTaskProtocol.Route.MethodNotAllowed)
        assertTrue(InboundTaskProtocol.route("GET", "/v1/health") is InboundTaskProtocol.Route.Health)
        assertTrue(InboundTaskProtocol.route("POST", "/v1/health") is InboundTaskProtocol.Route.MethodNotAllowed)
        val status = InboundTaskProtocol.route("GET", "/v1/tasks/abc-DEF_1")
        assertTrue(status is InboundTaskProtocol.Route.TaskStatus)
        assertEquals("abc-DEF_1", (status as InboundTaskProtocol.Route.TaskStatus).id)
        assertTrue(InboundTaskProtocol.route("GET", "/v1/tasks/nope/id") is InboundTaskProtocol.Route.NotFound)
        assertTrue(InboundTaskProtocol.route("POST", "/v1/tasks/abc") is InboundTaskProtocol.Route.MethodNotAllowed)
        assertTrue(InboundTaskProtocol.route("GET", "/etc/passwd") is InboundTaskProtocol.Route.NotFound)
        assertTrue(
            InboundTaskProtocol.route("POST", "http://100.1.2.3:9473/v1/tasks?token=secret")
                is InboundTaskProtocol.Route.CreateTask,
        )
    }

    @Test
    fun `create body`() {
        val ok = InboundTaskProtocol.parseCreateBody(
            """{"prompt":"  hello  ","session":"","label":"  note  ","token":"should-be-ignored"}""",
        ) as InboundTaskProtocol.CreateBody.Ok
        assertEquals("hello", ok.prompt)
        assertNull(ok.session)
        assertEquals("note", ok.label)

        val follow = InboundTaskProtocol.parseCreateBody(
            """{"prompt":"hi","session":"6d0f1234-abcd-4000-8000-000000000001"}""",
        ) as InboundTaskProtocol.CreateBody.Ok
        assertEquals("6d0f1234-abcd-4000-8000-000000000001", follow.session)
        assertNull(follow.label)

        assertEquals("prompt_required", (InboundTaskProtocol.parseCreateBody("{}") as InboundTaskProtocol.CreateBody.Reject).code)
        assertEquals("prompt_required", (InboundTaskProtocol.parseCreateBody("""{"prompt":"  "}""") as InboundTaskProtocol.CreateBody.Reject).code)
        assertEquals("invalid_json", (InboundTaskProtocol.parseCreateBody("[]") as InboundTaskProtocol.CreateBody.Reject).code)
        assertEquals("bad_request", (InboundTaskProtocol.parseCreateBody("""{"prompt":1}""") as InboundTaskProtocol.CreateBody.Reject).code)
        assertEquals("session_invalid", (InboundTaskProtocol.parseCreateBody("""{"prompt":"hi","session":"../x"}""") as InboundTaskProtocol.CreateBody.Reject).code)
        assertEquals(
            "prompt_too_long",
            (InboundTaskProtocol.parseCreateBody("""{"prompt":"${"x".repeat(InboundTaskProtocol.MAX_PROMPT_CHARS + 1)}"}""") as InboundTaskProtocol.CreateBody.Reject).code,
        )
        assertEquals(
            "label_too_long",
            (InboundTaskProtocol.parseCreateBody("""{"prompt":"hi","label":"${"n".repeat(InboundTaskProtocol.MAX_LABEL_CHARS + 1)}"}""") as InboundTaskProtocol.CreateBody.Reject).code,
        )
    }

    @Test
    fun `responses do not carry a prompt field`() {
        val created = JSONObject(InboundTaskProtocol.createdJson("id-1", "sess-1", "new"))
        assertEquals(setOf("id", "sessionId", "status", "target"), created.keys().asSequence().toSet())
        assertEquals("running", created.getString("status"))
        assertFalse(created.has("prompt"))
        assertFalse(created.has("token"))

        val status = JSONObject(
            InboundTaskProtocol.statusJson(
                TaskView("id-1", "note", "completed", "follow-up", "sess-1", true),
            ),
        )
        assertFalse(status.has("prompt"))
        assertEquals("note", status.getString("label"))
        assertTrue(status.getBoolean("ok"))

        val pending = JSONObject(
            InboundTaskProtocol.statusJson(TaskView("id-1", "note", "accepted", "new", null, null)),
        )
        assertTrue(pending.isNull("sessionId"))
        assertTrue(pending.isNull("ok"))
    }

    @Test
    fun `run status`() {
        assertEquals("running", InboundTaskProtocol.runStatus(running = true, recorded = false, ok = null))
        assertEquals("running", InboundTaskProtocol.runStatus(running = true, recorded = true, ok = true))
        assertEquals("accepted", InboundTaskProtocol.runStatus(running = false, recorded = false, ok = null))
        assertEquals("completed", InboundTaskProtocol.runStatus(running = false, recorded = true, ok = true))
        assertEquals("failed", InboundTaskProtocol.runStatus(running = false, recorded = true, ok = false))
    }

    @Test
    fun `content type`() {
        assertTrue(InboundTaskProtocol.acceptsJsonBody(null))
        assertTrue(InboundTaskProtocol.acceptsJsonBody("application/json; charset=utf-8"))
        assertFalse(InboundTaskProtocol.acceptsJsonBody("text/plain"))
        assertFalse(InboundTaskProtocol.acceptsJsonBody("application/json; charset=utf-16"))
    }
}
