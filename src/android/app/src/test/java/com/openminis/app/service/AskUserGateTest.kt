package com.openminis.app.service

import com.openminis.app.tools.AskUserQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-question-bg-notify] Contract tests for [AskUserGate] — the bridge
 * between a notification answer tap and the deferred the
 * `ask_user_question` tool dispatch is suspended on. Pure JVM: the gate is
 * a plain object with no Android dependencies.
 */
class AskUserGateTest {

    private fun question(
        text: String = "选一个方向",
        header: String = "",
        multiSelect: Boolean = false,
        options: List<String> = listOf("A", "B"),
    ) = AskUserQuestion.Question(
        question = text,
        header = header,
        multiSelect = multiSelect,
        options = options.map { AskUserQuestion.Option(it, it) },
    )

    private fun register(
        sessionId: String = "sess-1",
        questions: List<AskUserQuestion.Question> = listOf(question()),
        deferred: CompletableDeferred<String> = CompletableDeferred(),
    ): Pair<String, CompletableDeferred<String>> {
        val id = AskUserGate.register(sessionId, questions, deferred)
        return id to deferred
    }

    @Test
    fun `answer completes deferred with formatAnswers json`() {
        val (id, deferred) = register()
        val ok = AskUserGate.answer(id, listOf(listOf("A")))
        assertTrue(ok)
        val json = JSONObject(runBlocking { deferred.await() })
        val answers = json.getJSONArray("answers")
        assertEquals(1, answers.length())
        assertEquals("选一个方向", answers.getJSONObject(0).getString("question"))
        assertEquals("A", answers.getJSONObject(0).getJSONArray("answers").get(0))
        // registration is consumed by the caller's finally, not by answer —
        // detailOf still resolves until unregister.
        assertNotNull(AskUserGate.detailOf(id))
    }

    @Test
    fun `answerWithLabel answers single single-select question`() {
        val (id, deferred) = register()
        assertTrue(AskUserGate.answerWithLabel(id, "B"))
        val answers = JSONObject(runBlocking { deferred.await() }).getJSONArray("answers")
        assertEquals(1, answers.length())
        assertEquals("B", answers.getJSONObject(0).getJSONArray("answers").get(0))
    }

    @Test
    fun `answerWithLabel refuses multi-select question`() {
        val (id, deferred) = register(questions = listOf(question(multiSelect = true)))
        assertFalse(AskUserGate.answerWithLabel(id, "A"))
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `answerWithLabel refuses label not among options`() {
        val (id, deferred) = register()
        // A forged or stale intent must not fabricate an answer.
        assertFalse(AskUserGate.answerWithLabel(id, "C"))
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `answerWithLabel refuses multi-question request`() {
        val (id, deferred) = register(
            questions = listOf(question(text = "Q1"), question(text = "Q2")),
        )
        assertFalse(AskUserGate.answerWithLabel(id, "A"))
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `unknown id is a safe no-op`() {
        assertFalse(AskUserGate.answer("nope", listOf(listOf("A"))))
        assertFalse(AskUserGate.answerWithLabel("nope", "A"))
        assertFalse(AskUserGate.cancel("nope"))
        assertNull(AskUserGate.detailOf("nope"))
    }

    @Test
    fun `double resolution is a no-op - in-app answer wins over late tap`() {
        val (id, deferred) = register()
        // In-app path completes first…
        assertTrue(deferred.complete("{\"answers\":[],\"status\":\"in-app\"}"))
        // …a late notification tap then finds it already completed.
        assertFalse(AskUserGate.answerWithLabel(id, "A"))
        assertEquals("in-app", JSONObject(runBlocking { deferred.await() }).getString("status"))
    }

    @Test
    fun `cancel completes with skip-shaped verdict`() {
        val (id, deferred) = register()
        assertTrue(AskUserGate.cancel(id, "interrupted"))
        val json = JSONObject(runBlocking { deferred.await() })
        assertEquals(0, json.getJSONArray("answers").length())
        assertEquals("interrupted", json.getString("status"))
    }

    @Test
    fun `cancelAll drains gate and returns affected session ids`() {
        register(sessionId = "sess-a")
        register(sessionId = "sess-b")
        register(sessionId = "sess-a")
        val sessions = AskUserGate.cancelAll("interrupted")
        assertEquals(listOf("sess-a", "sess-b"), sessions.sorted())
        assertTrue(AskUserGate.pendingIds().isEmpty())
    }

    @Test
    fun `unregister removes entry without resolving deferred`() {
        val (id, deferred) = register()
        AskUserGate.unregister(id)
        assertNull(AskUserGate.detailOf(id))
        assertFalse(AskUserGate.answer(id, listOf(listOf("A"))))
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `formatAnswers round-trips multi-select selections`() {
        val qs = listOf(
            question(text = "Q1", multiSelect = true, options = listOf("A", "B", "C")),
            question(text = "Q2"),
        )
        val json = AskUserQuestion.formatAnswers(qs, listOf(listOf("A", "C"), listOf("B")))
        val answers = JSONObject(json).getJSONArray("answers")
        val first = answers.getJSONObject(0).getJSONArray("answers")
        assertEquals(2, first.length())
        assertEquals("A", first.get(0))
        assertEquals("C", first.get(1))
        assertEquals("B", answers.getJSONObject(1).getJSONArray("answers").get(0))
    }
}
