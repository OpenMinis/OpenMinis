package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
): ChatViewModel.InjectedTurn? {
    // [T-android-queue-atomic-mutation] CAS claim shared with drainQueuedPrompts
    // — see the note there for why a read-then-clear lost prompts (and re-sent
    // drained ones) across the Main/IO boundary.
    val queued = claimPromptQueue() ?: return null

    // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
    // placeholder bubbles (the ones enqueuePrompt added with
    // id="queued_msg_…") for the prompts we're injecting. Step (c) below
    // appends a single combined user bubble (id=userEntity.id) for the same
    // text — so flipping isQueued=false and KEEPING the placeholders (the
    // old behaviour) rendered the message TWICE: once as the un-queued
    // placeholder, once as the injected bubble. drainQueuedPrompts reuses
    // its placeholders and never re-appends, so it didn't dupe; this mid-
    // loop inject path appends a fresh bubble, so the placeholders must go.
    val queuedIds = queued.map { it.id }.toSet()
    val msgsAfterUnqueue = _messages.value.filterNot { m ->
        m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
    }

    // Build the combined user message from all queued prompts.
    val sid = ensureSession()
    val combinedAttachments = queued.flatMap { it.attachments }
    val prepared = prepareUserAttachments(combinedAttachments, sid)

    val combinedParts = mutableListOf<AgentContentPart>()
    val combinedText = StringBuilder()
    for (prompt in queued) {
        if (prompt.text.isNotEmpty()) {
            if (combinedText.isNotEmpty()) combinedText.append("\n\n")
            combinedText.append(prompt.text)
            combinedParts.add(AgentContentPart.Text(prompt.text))
        }
    }
    prepared.imageParts.forEachIndexed { idx, part ->
        val path = prepared.imageUploadPaths.getOrNull(idx)
        if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
        combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
    }
    prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

    // Guard: every queued prompt produced no content (no text, no
    // image). An empty user msg is a 400 from every provider. Skip —
    // the caller falls through to a normal next-turn dispatch so the
    // loop doesn't spin.
    if (combinedParts.isEmpty()) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
        )
        return null
    }

    // Bridge entry into agentHistory ONLY (not persisted). The tail
    // before this call is user(tool_result); without the bridge the
    // queued user message becomes two consecutive user roles and the
    // provider merges them — exactly the regression iOS hit at #579.
    // Empty/whitespace-only bridge text would itself be merged out by
    // some sanitizers; keep a small visible string for parity with iOS.
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
            contentParts = listOf(
                AgentContentPart.Text("(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"),
            ),
        ),
    )

    // Persist the queued user message as its own DB row + append to
    // agentHistory so the next API call carries it.
    val userText = combinedText.toString()
    // [T-android-paste-mediaref] Queued prompts carry markers too.
    //
    // sendMessage hands off to enqueuePrompt whenever a turn is already
    // streaming, and it no longer expands markers before doing so — so
    // without this the queued row would persist a literal `[Pasted#3]` and
    // the model would receive the marker instead of the text.
    val queuedPaste = buildPastedParts(userText, sid)
    if (queuedPaste != null) {
        _pastedTexts.value = _pastedTexts.value.filterNot { it.id in queuedPaste.consumedIds }
    }
    val userPartsJson = buildUserPartsJson(
        userText,
        prepared.mediaRefPartsJson,
        prepared.attachedFilesXml,
        bodyPartsJson = queuedPaste?.partsJson,
    )
    val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            // Expanded for the model; the persisted row above stays small.
            content = queuedPaste?.modelText ?: userText,
            imageParts = prepared.imageParts,
            contentParts = queuedPaste?.let { p ->
                // Replace the whole LEADING RUN of text parts with the one
                // expanded body, then keep everything after it.
                //
                // Not "index 0": each queued prompt contributes its own text
                // part, and combinedText joined them with blank lines —
                // p.modelText is the expansion of that join, so it stands
                // for all of them. The image and <user-attached-files> parts
                // that follow must survive untouched.
                val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
            } ?: combinedParts,
            dbMessageId = userEntity.id,
        ),
    )

    // Finalize the just-finished assistant bubble in the UI on Main:
    // (a) un-queue the queued chat bubbles, (b) flush the side-channel
    // delta into the canonical row and clear isStreaming /
    // isAwaitingModelResponse, then (c) append the freshly-created
    // queued user ChatMessage + a NEW empty assistant placeholder so
    // the next iteration's streaming writes target the new bubble.
    val newAssistantId = "assistant_${System.currentTimeMillis()}"
    withContext(Dispatchers.Main) {
        // (a) + (b) one emit: build the post-finalize list.
        _messages.value = msgsAfterUnqueue
        updateAssistantMessage(
            finishedAssistantId,
            finishedAccumulatedText,
            false,
            finishedAllToolBlocks,
            isAwaitingModelResponse = false,
        )
        // (c) — append the queued user bubble + the new assistant
        // placeholder. Mirrors sendMessage's user-bubble append shape so
        // attachments / images / file chips render the same.
        val queuedUserMsg = ChatMessage(
            id = userEntity.id,
            role = "user",
            content = userText,
            imageUris = prepared.imageUris,
            attachmentNames = prepared.attachmentNames,
            attachmentUris = prepared.nonImageUris,
            sourceDbIds = listOf(userEntity.id),
        )
        val nextAssistantMsg = ChatMessage(
            id = newAssistantId,
            role = "assistant",
            content = "",
            isStreaming = true,
            isAwaitingModelResponse = true,
            thinkingLevel = _thinkingLevel.value,
        )
        _messages.value = trimLoadedWindow(_messages.value + queuedUserMsg + nextAssistantMsg)
        notePersistedUiRow(userEntity.id, userEntity.id)
        // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
        // ScrollPin send-grace window) is updated reactively by
        // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
        // when messages.size grows — appending the queuedUserMsg above
        // bumps the size, so the pin window opens just like a normal
        // send. No direct write needed from here (and we couldn't —
        // `lastUserAppendMs` lives in ChatScreen's composition scope).
    }

    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
            "finishedId=$finishedAssistantId newId=$newAssistantId",
    )
    return ChatViewModel.InjectedTurn(newAssistantId)
}
