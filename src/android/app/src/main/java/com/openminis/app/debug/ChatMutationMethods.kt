package com.openminis.app.debug

import android.content.Context
import androidx.core.content.FileProvider
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.InputAttachment
import org.json.JSONObject
import java.io.File

/**
 * Mutation handlers for `chat.*` RPC methods — Phase 2.
 *
 * The `chat.prompt` / `chat.retry` paths drive the existing [ChatViewModel]
 * machinery via [HeadlessChatRunner]; `chat.session.cancel` calls
 * [ChatViewModel.cancelStream] on the cached VM; `chat.session.delete` writes
 * directly to the repo (no VM needed). This keeps the agent-loop / streaming
 * code single-sourced — the RPC is purely a transport.
 */
internal object ChatMutationMethods {

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")

    /**
     * [T-android-ui-prompt-rpc] Send a prompt through the ON-SCREEN chat's own
     * UI ChatViewModel (the one ChatScreen is composed with, cached in
     * [com.openminis.app.ui.chat.ChatViewModelStore]) — unlike [prompt], which
     * drives a separate headless VM whose streaming side-channel the visible
     * ChatScreen never observes. This is the only way an e2e harness can
     * exercise the LIVE streaming render pipeline (frozen/live flatten,
     * StreamPerf, markdown caches, breaker degrade): the stream must originate
     * from the VM the screen is collecting. Returns immediately; observe the
     * turn via chat.session.status / [StreamPerf] log lines.
     */
    suspend fun uiPrompt(context: Context, params: JSONObject): JSONObject {
        val text = params.optString("prompt", "").ifEmpty {
            throw RPCException(-32602, "Missing 'prompt' param")
        }
        val app = app(context)
        val store = com.openminis.app.ui.chat.ChatViewModelStore
        val sessionId = params.optString("sessionId", "").ifEmpty { null }
            ?: store.activeSessionId
            ?: throw RPCException(-32000, "No chat is on screen (activeSessionId is null) and no sessionId was given")
        val vm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            androidx.lifecycle.ViewModelProvider(
                store.ownerFor(sessionId),
                com.openminis.app.ui.chat.ChatViewModel.factory(
                    sessionId = sessionId,
                    chatRepository = app.chatRepository,
                    providerRepository = app.providerRepository,
                    appContext = app.applicationContext,
                    memoryRepository = app.memoryRepository,
                    skillRepository = app.skillRepository,
                    mcpRepository = app.mcpRepository,
                ),
            )[com.openminis.app.ui.chat.ChatViewModel::class.java].also { it.sendMessage(text) }
        }
        return JSONObject().apply {
            put("ok", true)
            put("sessionId", sessionId)
            put("isStreaming", vm.isStreaming.value)
        }
    }

    suspend fun prompt(context: Context, params: JSONObject): JSONObject {
        val text = params.optString("prompt", "").ifEmpty {
            throw RPCException(-32602, "Missing 'prompt' param")
        }
        val app = app(context)
        val existingId = params.optString("sessionId", "").ifEmpty { null }
        val isNew = existingId == null
        val sessionId = existingId ?: HeadlessChatRunner.ensureSession(context)

        // Resolve model override (mutually exclusive with sessionId binding).
        val modelEntryId = if (params.has("modelEntryId") && !params.isNull("modelEntryId")) params.optString("modelEntryId").ifEmpty { null } else null
        val modelGroupId = if (params.has("modelGroupId") && !params.isNull("modelGroupId")) params.optString("modelGroupId").ifEmpty { null } else null
        val overrideName = HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, modelGroupId)

        // Decode attachments — RPC carries base64; we materialize them as
        // FileProvider-backed Uris so InputAttachment + the existing image
        // pipeline can read them like any user pick.
        val attachments = parseAttachments(context, params)

        val wait = params.optBoolean("wait", false)
        val timeoutSec = params.optInt("waitTimeout", 600).coerceIn(1, 1800)
        val thinkingLevel = parseThinkingLevel(params)
        val result = HeadlessChatRunner.prompt(
            context = context,
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            thinkingLevel = thinkingLevel,
            wait = wait,
            timeoutMs = timeoutSec * 1000L,
        )

        // Re-read DB to pick up the latest message metadata after the run.
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        val userMsg = msgs.lastOrNull { it.role == "user" }
        val displayedModelName = overrideName ?: app.chatRepository.dao.getSession(sessionId)?.let { resolveDisplay(context, it.modelId) }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("isNewSession", isNew)
            put("modelName", displayedModelName ?: JSONObject.NULL)
            put("status", result.status)
            put("prompt", text)
            put("responseText", result.responseText ?: JSONObject.NULL)
            put("userMessageId", userMsg?.id ?: JSONObject.NULL)
            if (result.timedOut) put("timedOut", true)
        }
    }

    suspend fun retry(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val messageId = if (params.has("messageId") && !params.isNull("messageId")) params.optString("messageId").ifEmpty { null } else null

        // Apply model override (per-call only; iOS docs say session binding is
        // untouched — but the existing ChatViewModel doesn't expose a per-call
        // override, so we update + restore around the call. Acceptable for
        // automation harnesses.)
        val modelEntryId = if (params.has("modelEntryId") && !params.isNull("modelEntryId")) params.optString("modelEntryId").ifEmpty { null } else null
        val modelGroupId = if (params.has("modelGroupId") && !params.isNull("modelGroupId")) params.optString("modelGroupId").ifEmpty { null } else null
        val overrideName = HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, modelGroupId)

        val wait = params.optBoolean("wait", false)
        val timeoutSec = params.optInt("waitTimeout", 600).coerceIn(1, 1800)
        val result = HeadlessChatRunner.retry(
            context = context,
            sessionId = sessionId,
            messageId = messageId,
            wait = wait,
            timeoutMs = timeoutSec * 1000L,
        )
        val displayedModelName = overrideName
            ?: app.chatRepository.dao.getSession(sessionId)?.let { resolveDisplay(context, it.modelId) }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("status", result.status)
            put("retriedMessageId", result.retriedMessageId ?: JSONObject.NULL)
            put("deletedMessageCount", result.deletedMessageCount)
            put("modelName", displayedModelName ?: JSONObject.NULL)
            put("responseText", result.responseText ?: JSONObject.NULL)
            if (result.timedOut) put("timedOut", true)
        }
    }

    /**
     * [T-android-rerun-from-tool-block-position] Drive the block-boundary
     * re-run headlessly (in-app trigger is a tool-bubble long-press). Needs
     * the UI assistant bubble id + the tool block's id (== tool_use id).
     */
    suspend fun rerunFromToolBlock(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val assistantMessageId = params.optString("assistantMessageId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'assistantMessageId' param")
        }
        val blockId = params.optString("blockId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'blockId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val wait = params.optBoolean("wait", false)
        val timeoutSec = params.optInt("waitTimeout", 600).coerceIn(1, 1800)
        val result = HeadlessChatRunner.rerunFromToolBlock(
            context = context,
            sessionId = sessionId,
            assistantMessageId = assistantMessageId,
            blockId = blockId,
            wait = wait,
            timeoutMs = timeoutSec * 1000L,
        )
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("status", result.status)
            put("deletedMessageCount", result.deletedMessageCount)
            put("responseText", result.responseText ?: JSONObject.NULL)
            if (result.timedOut) put("timedOut", true)
        }
    }

    suspend fun status(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        val s = app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        val lastMsg = msgs.lastOrNull()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("title", s.title ?: JSONObject.NULL)
            put("modelName", resolveDisplay(context, s.modelId))
            put("isRunning", com.openminis.app.service.SessionActivityTracker.isActive(sessionId))
            put("messageCount", msgs.size)
            put("lastMessageRole", lastMsg?.role ?: JSONObject.NULL)
            put("updatedAt", s.updatedAt)
        }
    }

    suspend fun cancel(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val wasRunning = HeadlessChatRunner.cancel(context, sessionId)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("wasRunning", wasRunning)
            put("cancelled", wasRunning)
        }
    }

    /**
     * [T-android-thinking-level-arch] Switch the live session's bound model —
     * the same action as tapping a model in the in-app picker (calls
     * ChatViewModel.selectEntry). Use this (not chat.prompt's modelEntryId) to
     * test an in-session model switch: the thinking level is preserved, and the
     * response echoes the resolved model + the current thinking level so a caller
     * can assert cross-model transfer.
     */
    suspend fun selectModel(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val entryId = params.optString("modelEntryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'modelEntryId' param")
        }
        val (modelName, thinkingLevel) = HeadlessChatRunner.selectModel(context, sessionId, entryId)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("modelEntryId", entryId)
            put("modelName", modelName)
            put("thinkingLevel", thinkingLevel)
        }
    }

    /**
     * Mirrors iOS `chat.compact.before` ([DebugRPCChat.compactBefore]).
     *
     * iOS supports two modes:
     *   - `includesBoundary=false` + `messageId` → fold history up to (but not
     *     including) that message; the boundary message itself is kept.
     *   - `includesBoundary=true` → compactAll semantics: fold the entire
     *     active range up to the anchor (last persisted active message).
     *
     * Android ChatViewModel currently only exposes the compactAll path
     * (`runCompactNow()`), so we accept `includesBoundary=true` and reject
     * the per-message variant with a typed error until [ChatViewModel] grows
     * a `compactBefore(messageId)` entrypoint. The wire format / return
     * shape matches iOS so cross-platform automation can use one harness.
     */
    suspend fun compactBefore(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val includesBoundary = params.optBoolean("includesBoundary", false)
        val messageId = if (params.has("messageId") && !params.isNull("messageId"))
            params.optString("messageId").ifEmpty { null } else null
        // includesBoundary=false requires an explicit boundary message id;
        // includesBoundary=true (compactAll semantics) ignores it. Mirrors
        // iOS chat.compact.before contract.
        if (!includesBoundary && messageId == null) {
            throw RPCException(-32602, "Missing 'messageId' param (required when includesBoundary=false)")
        }
        val waitTimeoutSec = params.optInt("waitTimeout", 120).coerceIn(1, 1800)
        val beforeMarkerCount = app.chatRepository.dao.listCompactMarkers(sessionId).size
        val result = HeadlessChatRunner.compact(
            context = context,
            sessionId = sessionId,
            wait = true,
            timeoutMs = waitTimeoutSec * 1000L,
            messageId = messageId,
            includesBoundary = includesBoundary,
        )
        val afterMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val latest = afterMarkers.maxByOrNull { it.createdAt }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("messageId", messageId ?: JSONObject.NULL)
            put("includesBoundary", includesBoundary)
            put("beforeMarkerCount", beforeMarkerCount)
            put("afterMarkerCount", afterMarkers.size)
            put("wrote", afterMarkers.size > beforeMarkerCount)
            put("status", result.status)
            if (result.timedOut) put("timedOut", true)
            if (result.error != null) put("error", result.error)
            put("latestMarker", latest?.let { m ->
                JSONObject().apply {
                    put("id", m.id)
                    put("version", m.version)
                    put("anchorMessageId", m.lastCompactedMessageId ?: JSONObject.NULL)
                    put("summaryLength", m.summary.length)
                    put("compactedCount", m.compactedCount)
                    put("createdAt", m.createdAt)
                }
            } ?: JSONObject.NULL)
        }
    }

    /**
     * Mirrors iOS `chat.compact.markers.list` ([DebugRPCChat.compactMarkersList]).
     * Returns all markers oldest→newest with the fields a v2 marker actually
     * uses (anchorMessageId, version, summaryLength + summaryPreview).
     */
    suspend fun compactMarkersList(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val includeFullSummary = params.optBoolean("includeFullSummary", false)
        val markers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val arr = org.json.JSONArray()
        for (m in markers) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("version", m.version)
                put("anchorMessageId", m.lastCompactedMessageId ?: JSONObject.NULL)
                put("firstKeptMessageId", m.firstKeptMessageId ?: JSONObject.NULL)
                put("summaryLength", m.summary.length)
                put("summaryPreview", m.summary.take(120))
                put("compactedCount", m.compactedCount)
                put("createdAt", m.createdAt)
                if (includeFullSummary) put("summary", m.summary)
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("count", arr.length())
            put("markers", arr)
        }
    }

    /**
     * Mirrors iOS `chat.compact.revert` ([DebugRPCChat.compactRevert]).
     * Removes the latest compact marker on the session. Refuses while a
     * stream or another compact is in flight (delegates to
     * [ChatViewModel.revertCompact] which handles the gating + system-info
     * notice).
     */
    suspend fun compactRevert(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val beforeMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val removed = beforeMarkers.maxByOrNull { it.createdAt }
        // ChatViewModel.revertCompact() does its DB delete in viewModelScope
        // .launch(IO) — fire-and-forget. We can't observe a completion flow
        // (no isReverting flag), so after kicking it off we poll the marker
        // list briefly until the count drops by one (or timeout).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            HeadlessChatRunner.revertCompact(context, sessionId)
        }
        var afterMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        val deadline = System.currentTimeMillis() + 5000L
        while (afterMarkers.size == beforeMarkers.size && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(150L)
            afterMarkers = app.chatRepository.dao.listCompactMarkers(sessionId)
        }
        val newLatest = afterMarkers.maxByOrNull { it.createdAt }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("beforeMarkerCount", beforeMarkers.size)
            put("afterMarkerCount", afterMarkers.size)
            put("removedMarkerId",
                if (afterMarkers.size < beforeMarkers.size) (removed?.id ?: JSONObject.NULL) else JSONObject.NULL)
            put("newLatestMarkerId", newLatest?.id ?: JSONObject.NULL)
        }
    }

    suspend fun delete(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete a session")
        }
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        // [T-android-child-session-delete-storage] The funnel cancels the
        // stream, drops rows/files/VM/badges for the session and every child.
        val report = com.openminis.app.data.session.SessionDeleter.deleteTree(
            context, app.chatRepository, sessionId, "rpc",
        )
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("deleted", true)
            put("deletedSessionIds", org.json.JSONArray(report.deletedIds))
            put("bytesFreed", report.bytesFreed)
            put("jobsCancelled", report.jobsCancelled)
        }
    }

    /** [T-agent-port-round3] Debug/e2e: run a scheduled agent-child task now. */
    suspend fun runScheduledAgentChild(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty { throw RPCException(-32602, "Missing 'sessionId' param") }
        val prompt = params.optString("prompt", "").ifEmpty { throw RPCException(-32602, "Missing 'prompt' param") }
        val label = params.optString("label", "Agent child probe")
        val app = app(context)
        app.chatRepository.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val cal = java.util.Calendar.getInstance()
        val task = com.openminis.app.scheduled.ScheduledTask(
            label = label,
            timeOfDayHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            timeOfDayMinute = cal.get(java.util.Calendar.MINUTE),
            repeatMode = com.openminis.app.scheduled.ScheduledRepeatMode.ONCE,
            prompt = prompt,
            targetMode = com.openminis.app.scheduled.ScheduledTargetMode.ChildOfCurrent(sessionId),
            enabled = false,
        )
        com.openminis.app.scheduled.ScheduledTaskManager(app).create(task)
        val childId = com.openminis.app.scheduled.ScheduledAgentRunner.run(app, task, waitForCompletion = false)
        return JSONObject().apply {
            put("taskId", task.id)
            put("childSessionId", childId ?: JSONObject.NULL)
        }
    }

    private fun parseAttachments(context: Context, params: JSONObject): List<InputAttachment> {
        val arr = params.optJSONArray("attachments") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val out = mutableListOf<InputAttachment>()
        val cacheDir = File(context.cacheDir, "rpc-attachments").also { it.mkdirs() }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").ifEmpty { "attachment-$i" }
            val data = obj.optString("data", "")
            if (data.isEmpty()) continue
            val mime = obj.optString("mime", "").ifEmpty { guessMime(name) }
            val bytes = try {
                android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            } catch (_: Exception) { null } ?: continue
            val file = File(cacheDir, "${System.currentTimeMillis()}-$name")
            file.outputStream().use { it.write(bytes) }
            // Use FileProvider so the URI is consumable by Android components
            // (matches what the document/image pickers hand back to the app).
            val authority = context.packageName + ".fileprovider"
            val uri = try {
                FileProvider.getUriForFile(context, authority, file)
            } catch (_: Exception) {
                // Fall back to file:// (older code paths accept it; image
                // pipeline uses ContentResolver which copes with both).
                android.net.Uri.fromFile(file)
            }
            val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
                else InputAttachment.Kind.DOCUMENT
            out.add(InputAttachment(fileName = name, uri = uri, mimeType = mime, kind = kind))
        }
        return out
    }

    /**
     * Map the RPC `thinkingLevel` string ("off"/"low"/"medium"/"high"/"xhigh")
     * to the [ThinkingLevel] enum. Unknown values raise -32602 so callers
     * notice typos rather than silently falling back to OFF. Returns null when
     * the param was omitted, signaling "leave the VM's existing level alone".
     */
    private fun parseThinkingLevel(params: JSONObject): ThinkingLevel? {
        if (!params.has("thinkingLevel") || params.isNull("thinkingLevel")) return null
        val raw = params.optString("thinkingLevel", "").trim().lowercase()
        if (raw.isEmpty()) return null
        return when (raw) {
            "off" -> ThinkingLevel.OFF
            "low" -> ThinkingLevel.LOW
            "medium" -> ThinkingLevel.MEDIUM
            "high" -> ThinkingLevel.HIGH
            "xhigh" -> ThinkingLevel.XHIGH
            // [T-android-thinking-level-arch] MAX/ULTRA are now distinct tiers.
            // "max" previously aliased XHIGH (when XHIGH's label was "Max"); it
            // now maps to the real MAX tier.
            "max" -> ThinkingLevel.MAX
            "ultra" -> ThinkingLevel.ULTRA
            else -> throw RPCException(-32602, "Invalid thinkingLevel: $raw (expected off/low/medium/high/xhigh/max/ultra)")
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun resolveDisplay(context: Context, modelId: String): String {
        val cfg = app(context).providerRepository.config.value
        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId } ?: return modelId
        return entry.model.displayName
    }
}
