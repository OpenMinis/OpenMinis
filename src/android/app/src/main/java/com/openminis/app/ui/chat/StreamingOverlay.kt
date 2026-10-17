package com.openminis.app.ui.chat

import java.util.AbstractList

/**
 * T-streaming-side-channel: overlay active [StreamingDelta]s on top of the
 * canonical [messages] list, producing the snapshot [buildFlatChatItems]
 * folds over.
 *
 * The old implementation did `messages.map { … }` on every throttled tick:
 * a full list allocation plus a lambda call per message, even though only one
 * (the streaming tail) can change. This returns a lazy list instead — frozen
 * entries are the same objects by reference, and the overlaid message is
 * materialised once per index. [buildFlatChatItems] still walks the list, but
 * the per-tick allocation and copy of the whole transcript are gone.
 */
internal fun mergeStreamingOverlay(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
): List<ChatMessage> {
    if (streaming.isEmpty()) return messages
    return StreamingOverlayMessages(messages, streaming)
}

private class StreamingOverlayMessages(
    private val base: List<ChatMessage>,
    private val streaming: Map<String, StreamingDelta>,
) : AbstractList<ChatMessage>() {

    private val overlaid = HashMap<Int, ChatMessage>()

    override val size: Int get() = base.size

    override fun get(index: Int): ChatMessage {
        val message = base[index]
        val delta = streaming[message.id] ?: return message
        return overlaid.getOrPut(index) { overlay(message, delta) }
    }
}

private fun overlay(m: ChatMessage, delta: StreamingDelta): ChatMessage {
    // [T-android-stream-duplicate-text] `delta.content` is the WHOLE
    // assistant reply accumulated across every tool round, while
    // `delta.toolBlocks` is that same reply split chronologically into
    // per-round text/tool blocks (the producer appends each round's text
    // block and keeps advancing only that block; rounds never repeat
    // earlier text). Overwriting one block with the cumulative string
    // therefore duplicated everything before it: whichever text block was
    // last kept its own fragment AND re-rendered rounds 1..N-1 again, so a
    // turn with text A → tool → text B painted "A" then "A+B".
    //
    // The blocks are the authority for what each row renders, so take them
    // verbatim:
    //  - delta.toolBlocks non-empty → newest per-round snapshot. copy() it
    //    (the delta list is shared) but never rewrite a block's content.
    //  - delta.toolBlocks empty but the canonical message already carries
    //    blocks → keep those. Rewriting the last text block with the
    //    cumulative content would re-introduce the exact duplication.
    //  - canonical blocks also empty (pre-first-block turn) → stay empty;
    //    the typing indicator renders and the legacy fallback below is
    //    driven by content == "" while nothing has arrived.
    // Fall back to m.toolBlocks rather than [] so a machine-migration
    // streaming message without a toolBlocks list keeps its blocks.
    val blocks = when {
        delta.toolBlocks.isNotEmpty() -> delta.toolBlocks
        m.toolBlocks.isNotEmpty() -> m.toolBlocks
        else -> emptyList()
    }
    return m.copy(
        // Content stays authoritative on its own field (non-render
        // consumers: persistence, copy/translate fallbacks).
        content = delta.content,
        isStreaming = true,
        toolBlocks = blocks,
        isAwaitingModelResponse = delta.isAwaitingModelResponse,
    )
}
