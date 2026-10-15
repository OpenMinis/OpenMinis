package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.ui.chat.ChatViewModel.CompactAttempt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-compact-fallback-walk] Pins the stage-list contract of the two-shot
 * compaction walk: every fallback group member becomes a stage (upstream
 * 1.15-beta.3 "failure falls back to other models in the group"), the
 * session model leads, and nextHint tells the progress headline what the
 * NEXT stage is (or that none remains).
 */
class CompactStageWiringTest {

    private class Stub(id: String) : LLMProvider {
        override val name = "stub-$id"
        override var model = LLMModel(id = id, displayName = id, provider = "stub")

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = error("unused")

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            systemStablePrefixLen: Int,
        ): Flow<LLMStreamChunk> = flow { }
    }

    private fun attempt(id: String, kind: String = "fallback") = CompactAttempt(
        kind = kind,
        label = id,
        provider = Stub(id),
        model = LLMModel(id = id, displayName = id, provider = "stub"),
        nextHint = "truncate",
    )

    @Test
    fun `no fallback group yields session then session-retry`() {
        val stages = wireCompactStages(attempt("session", kind = "session"), emptyList())
        assertEquals(2, stages.size)
        assertEquals("session", stages[0].kind)
        assertEquals("session-retry", stages[0].nextHint)
        assertEquals("session-retry", stages[1].kind)
        assertEquals("truncate", stages[1].nextHint)
        assertEquals(stages[0].label, stages[1].label)
        assertEquals(stages[0].provider, stages[1].provider)
    }

    @Test
    fun `single fallback yields session then that fallback`() {
        val stages = wireCompactStages(attempt("session", kind = "session"), listOf(attempt("fb1")))
        assertEquals(2, stages.size)
        assertEquals("session", stages[0].kind)
        assertEquals("fallback", stages[0].nextHint)
        assertEquals("fallback", stages[1].kind)
        assertEquals("fb1", stages[1].label)
        assertEquals("truncate", stages[1].nextHint)
    }

    @Test
    fun `every fallback member becomes a stage in group order`() {
        val stages = wireCompactStages(
            attempt("session", kind = "session"),
            listOf(attempt("fb1"), attempt("fb2"), attempt("fb3")),
        )
        assertEquals(listOf("session", "fb1", "fb2", "fb3"), stages.map { it.label })
        assertEquals(listOf("session", "fallback", "fallback", "fallback"), stages.map { it.kind })
        // Intermediate fallbacks hint another model; only the last hints truncation.
        assertEquals(
            listOf("fallback", "fallback", "fallback", "truncate"),
            stages.map { it.nextHint },
        )
    }

    @Test
    fun `wiring does not touch provider model or temperature of stages`() {
        val session = attempt("session", kind = "session").copy(temperature = 0.3)
        val fallback = attempt("fb1").copy(temperature = 0.7)
        val stages = wireCompactStages(session, listOf(fallback))
        assertEquals(0.3, stages[0].temperature!!, 0.0)
        assertEquals(0.7, stages[1].temperature!!, 0.0)
        assertEquals(session.provider, stages[0].provider)
        assertEquals(fallback.provider, stages[1].provider)
        assertEquals(fallback.model, stages[1].model)
    }
}
