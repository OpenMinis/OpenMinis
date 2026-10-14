package com.openminis.app.ui.chat

// [T-android-split-chat] Chat data models extracted verbatim from
// ChatViewModel.kt: StreamingDelta, ChatMessage, QueuedPrompt,
// ToolBlockStatus, SlashCommand, AssistantBlock. Visibility unchanged (public).

import android.net.Uri
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Per-message streaming snapshot — the high-frequency fields that
 * [ChatViewModel.updateAssistantMessage] used to write straight into
 * [ChatMessage] (and re-publish via the `messages` StateFlow on every
 * token). Splitting them off into a side-channel
 * ([ChatViewModel.streamingById]) keeps the `messages` reference stable
 * during a turn, so the ChatScreen top-level composable's reads
 * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't recompose on
 * every token — only on message-level structural changes (new message,
 * delete, retry, etc.).
 *
 * Renderers that care about streaming content subscribe per-item; the
 * effective render value is `streamingById[id]?.content ?: message.content`
 * (and analogously for the other fields). At the end of a streaming turn
 * the side-channel is drained back into the canonical message and the
 * map entry is removed.
 */
data class StreamingDelta(
    val content: String,
    val toolBlocks: List<AssistantBlock>,
    val isAwaitingModelResponse: Boolean,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    // True while waiting on the network for the next model response chunk —
    // either before the first chunk of a turn, or in the gap after tool results
    // are sent back and before the next turn starts streaming. Cleared the moment
    // the next content chunk (text / thinking / tool_use) arrives.
    val isAwaitingModelResponse: Boolean = false,
    val imageUris: List<Uri> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    // T150: file:// URIs of non-image attachments that the user bubble's
    // file chip taps into FilePreviewScreen. Aligned with the non-image
    // suffix of `attachmentNames` (after the imageUris-many image entries).
    val attachmentUris: List<Uri> = emptyList(),
    val toolBlocks: List<AssistantBlock> = emptyList(),
    // T300: thinking-level snapshot at the moment this assistant message
    // was created. Used by the chat UI to suppress the "Deep Thinking"
    // collapsible when the user's per-session toggle is OFF (forced-
    // reasoning models on OpenRouter still emit reasoning_content even
    // though the wire request omits the reasoning field — see the T300
    // analysis report for why we hide rather than silence). In-memory
    // only; assistant messages restored from DB get null and fall back
    // to the chat's current thinking level at render time.
    val thinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
    val error: String? = null,
    // Queued user prompt awaiting injection into the running agent loop.
    // Mirrors iOS ChatMessage.isQueued / queuedPromptId.
    val isQueued: Boolean = false,
    val queuedPromptId: String? = null,
    // Set to true when this message belongs to a range that has been folded
    // into a compact summary marker. Mirrors iOS ChatMessage.isCompactedHistory:
    // the message stays in the UI, but renders at reduced opacity so the user
    // can still scroll/read it while seeing it's no longer in the model's
    // active context window.
    val isCompactedHistory: Boolean = false,
    // Every DB row id this UI message represents — usually a single id,
    // but consecutive assistant turns get merged in `loadSessionMessages`
    // and the merged bubble carries every source row's id here. Phase
    // 2.5 boundary resolution looks up `lastCompactedMessageId` /
    // `firstKeptMessageId` against this set so a merged-into-tail row
    // still locates the right divider position. Mirrors iOS
    // ChatMessage.sourceSortOrder, which serves the same UI↔raw mapping
    // role (AIChatViewModel.swift:3411, 3421).
    val sourceDbIds: List<String> = emptyList(),
    /** Set for an AI group-chat utterance. Keeps that model's bubble separate. */
    val speakerName: String? = null,
    /** Vendor key for the speaker mark. Empty on ordinary assistant turns. */
    val speakerVendor: String? = null,
) {
    /**
     * [T-delete-anchor-by-id] Whether this row renders as a REAL user bubble:
     * text, attachments, or both. A pure-image prompt has empty `content` but
     * is a visible user turn — destructive rewinds must anchor to it rather
     * than walk past it into the previous turn. Tool-result carriers and
     * reminder-only rows render nothing and are filtered out before they ever
     * reach the UI list.
     */
    val isRenderedUserBubble: Boolean
        get() = role == "user" && (
            content.isNotBlank() ||
                imageUris.isNotEmpty() ||
                attachmentUris.isNotEmpty() ||
                attachmentNames.isNotEmpty()
            )

    /**
     * [T-bridge-message-ui-leak-android] True when this UI message is the
     * internal role-alternation bridge that `injectQueuedPromptsAsNewTurn`
     * inserts into `agentHistory` (see ChatViewModel). It is an internal
     * LLM-facing message and must NEVER render as a chat bubble.
     *
     * On Android the bridge goes into `agentHistory` ONLY (never persisted
     * to the DB, never appended to `_messages`), so it cannot currently
     * leak through any UI path — unlike iOS, where a persisted bridge row
     * leaked after the 2026-07-23 wording change. This property exists as a
     * belt-and-suspenders filter (applied at the `uiMessages` sink) so a
     * future refactor that accidentally routes the bridge into `_messages`
     * still can't surface it. Mirrors iOS `ChatMessage.isInternalBridge`.
     */
    val isInternalBridge: Boolean
        get() = role == "assistant" && isInternalBridgeText(content)

    companion object {
        /** Current bridge wording — MUST stay byte-identical to the string
         *  written in ChatViewModel.injectQueuedPromptsAsNewTurn. */
        private const val INTERNAL_BRIDGE_TEXT =
            "(Interrupted mid-task by a new user message. Decide based on the new " +
                "message and overall context whether the prior task should continue — do " +
                "not forget or abandon it unless the user explicitly says to stop, or the " +
                "new message makes clear it is no longer needed.)"

        /**
         * Every bridge text this app has ever generated. Matching only the
         * current constant would miss a message produced by an OLDER build
         * carrying the previous wording — exactly the leak class iOS hit after
         * its 2026-07-23 wording change (d2e111e9). Match against the full set
         * so old and new bridges are both recognized. Mirrors iOS
         * `RawMessage.internalBridgeTexts`.
         */
        private val INTERNAL_BRIDGE_TEXTS = listOf(
            INTERNAL_BRIDGE_TEXT,
            // Pre-2026-07-23 wording.
            "(Interrupted mid-task to handle your new message. Will return to the prior task after.)",
        )

        /** True when [text] is any known internal-bridge string. Trims
         *  leading/trailing whitespace to tolerate encoding drift from any
         *  round-trip, matching iOS `RawMessage.isInternalBridgeText`. */
        fun isInternalBridgeText(text: String): Boolean {
            val trimmed = text.trim()
            return INTERNAL_BRIDGE_TEXTS.any { trimmed == it }
        }
    }
}

/** A user prompt queued while the agent loop is still running. Mirrors iOS QueuedPrompt. */
data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<InputAttachment> = emptyList(),
)

/**
 * Execution status of an assistant tool block. Mirrors iOS `ToolBlockStatus`
 * plus two Android-only granularity states for UI animation:
 *
 *  - `STREAMING`: partial tool-input JSON is still arriving (iOS `.streaming(bytes:)`).
 *  - `PENDING`: tool JSON is complete, waiting for the execution dispatcher
 *    to start. Brief window between ToolCallComplete and `executeTool()`
 *    invocation — visible when the agent pipelines multiple tool calls.
 *  - `RUNNING`: tool body is executing (iOS `.running`).
 *  - `SUCCESS`: tool returned without error (iOS `.success`).
 *  - `FAILED`: tool returned an error (iOS `.failed(message:)`).
 *  - `CANCELLED`: user cancelled mid-execution (iOS `.cancelled`).
 *  - `TIMEOUT`: wrapper timeout hit before the tool returned — distinct from
 *    FAILED so the UI can render a clock icon instead of a generic error.
 */
enum class ToolBlockStatus {
    STREAMING, PENDING, RUNNING, SUCCESS, FAILED, CANCELLED, TIMEOUT
}

/** Slash command descriptor shown in the "/" popup. Mirrors iOS SlashCommand. */
data class SlashCommand(
    val id: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    /**
     * [T-skill-slash a88ea8f9] True when this row was synthesized from an
     * installed Skill (vs. a built-in command). Skill rows fill the
     * composer with `/<name>` on tap and dismiss the menu — the actual
     * SKILL.md reading + behavior happens model-side when the message is
     * sent (skills already get injected into the system prompt via
     * SkillRepository.enabledForSession). Default false so existing
     * built-in rows construct unchanged.
     */
    val isSkill: Boolean = false,
    /**
     * [T-mcp-integration-android] True when this row was synthesized from a
     * configured MCP server (vs. a built-in command or a skill). Distinct from
     * [isSkill] so the picker can tag MCP rows with [mcp] + a wrench icon and
     * skills with ⚡. Tapping fills the composer with the server name; the
     * actual discovery/call happens model-side via minis-mcp-cli.
     */
    val isMcp: Boolean = false,
)

data class AssistantBlock(
    val id: String,
    val kind: String,       // "text", "tool_use", "thinking", "info"
    val content: String = "",
    val toolStatus: ToolBlockStatus? = null,
    val toolTitle: String = "",
    val toolName: String = "",
    val toolArgs: String = "",   // raw JSON args for UI rendering (command, path, old_string, etc.)
    val durationMs: Long = 0L,
    val startTimeMs: Long = 0L,
    /** Page URL at time of browser action execution (mirrors iOS AssistantBlock.browserURL). */
    val browserURL: String? = null,
    /** Local file path to screenshot JPEG (mirrors iOS AssistantBlock.imageFilePath). */
    val imageFilePath: String? = null,
    /**
     * [T-android-gemini3-thoughtsig / #179] Gemini 3.x thought signature for a
     * tool_use block. Carried here so [buildTurnParts] (the persistence path,
     * which rebuilds ToolUse parts from blocks) can round-trip it to the DB.
     * Null for non-Gemini providers and thinking-off Gemini calls.
     */
    val thoughtSignature: String? = null,
) {
    val isText: Boolean get() = kind == "text"
}
