package com.openminis.app.data.model

import org.json.JSONObject

/**
 * Agent-loop stream event — a spec-complete superset of [LLMStreamChunk] that
 * carries tool invocations, thinking, reasoning, and structured stop reasons.
 * Mirrors iOS `AgentStreamEvent` (AgentProvider.swift).
 *
 * Provider implementations may surface events via [LLMStreamChunk] (plain chat)
 * or via this channel (tool-using agent loops); the two coexist by design so
 * non-agent sendMessage callers don't pay for agent-specific plumbing.
 */
sealed class AgentStreamEvent {
    /** A new content block started (text or tool use). */
    data class ContentBlockStart(val start: AgentBlockStart) : AgentStreamEvent()
    /** Incremental text for an in-flight text block. */
    data class TextDelta(val delta: String) : AgentStreamEvent()
    /** Accumulated JSON preview for an in-flight tool_use input. */
    data class ToolInputDelta(val name: String, val accumulated: String) : AgentStreamEvent()
    /** A tool_use block finished assembling; args is fully parsed JSON. */
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        val metadata: ToolCallMetadata? = null,
    ) : AgentStreamEvent()
    /** Token accounting (typically once at stream end). */
    data class Usage(val usage: LLMUsage) : AgentStreamEvent()
    /** Real-time thinking/extended-reasoning delta. */
    data class ThinkingDelta(val delta: String) : AgentStreamEvent()
    /** Accumulated opaque reasoning (DeepSeek/Kimi reasoning_content). */
    data class ReasoningContent(val content: String) : AgentStreamEvent()
    /** Stream ended with a structured stop reason. */
    data class Done(val stopReason: AgentStopReason) : AgentStreamEvent()
}

sealed class AgentBlockStart {
    data object Text : AgentBlockStart()
    data class ToolUse(val id: String, val name: String) : AgentBlockStart()
}

enum class AgentStopReason { END_TURN, TOOL_USE, MAX_TOKENS }

data class ToolCallMetadata(
    /** Gemini thought signature echoed back on the next turn. */
    val thoughtSignature: String? = null,
)

/**
 * Agent-layer message type — content is a list of typed parts (text / tool_use /
 * tool_result / image) rather than a flat string. Mirrors iOS `AgentMessage`.
 *
 * [isInterrupted] flags a stream that was cancelled mid-flight so the agent
 * loop knows whether to inject placeholder tool_result blocks.
 * [reasoningContent] preserves opaque chain-of-thought for reasoning models
 * (Kimi/DeepSeek/QwQ) that must be echoed back on later turns.
 */
data class AgentMessage(
    val role: Role,
    var parts: List<AgentContentPart>,
    var isInterrupted: Boolean = false,
    var reasoningContent: String? = null,
    var dbMessageId: String? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }
}

/**
 * Normalize a tool-use id to the regex Anthropic accepts (`^[a-zA-Z0-9_-]+$`).
 * OpenAI Responses can emit ids like `call_abc|fc_def` which Anthropic rejects
 * on the follow-up turn, so every provider echoes ids through this filter.
 *
 * Delegates to [ToolIdCompat] since 1.15-beta.5 parity: the old body fixed the
 * alphabet but left over-long ids alone, which the OpenAI-compatible relays
 * reject ("maximum length 64") — and the reverse direction truncated ids to 64
 * chars without fixing their alphabet. One deterministic mapping now covers
 * both, and a call plus its result still map to the same wire id.
 */
fun sanitizeToolId(id: String): String = ToolIdCompat.normalize(id)
