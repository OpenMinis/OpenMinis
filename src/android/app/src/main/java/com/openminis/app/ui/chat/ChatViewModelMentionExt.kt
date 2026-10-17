package com.openminis.app.ui.chat

// [T-android-split-chat] @-mention picker methods extracted from ChatViewModel
// as top-level extension functions (call syntax unchanged). The 5 mention
// state fields they touch were flipped private->internal. Verbatim logic.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.GroupChat
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * `@` addresses models ONLY while AI group chat is on.
 *
 * [T-android-at-mentions-not-group-chat] This used to also return true whenever
 * the multi-agent roster had non-host members, so a user who had configured
 * sub-agent slots saw the MODEL list on `@` even with group chat OFF — while
 * the feature that list belongs to was disabled. Mentioning a speaker only
 * means something inside a group chat; outside it, `@` must keep offering the
 * file/skill picker.
 */
internal fun ChatViewModel.shouldListGroupModels(): Boolean {
    adoptStoredGroupChatEnabled()
    return groupChatEnabled.value
}

/**
 * Inspect the input + caret position; if the caret sits inside an
 * `@<token>` (preceded by start-of-text or whitespace, no whitespace
 * between `@` and caret) open the mention picker and refresh the
 * filter. Otherwise close it. Mirrors iOS
 * [AIChatViewModel.updateMentionMenuState].
 *
 * Accepts both ASCII `@` and full-width `＠` (U+FF20) so CJK IMEs
 * substituting the full-width form still trigger the picker — same
 * convention as slash commands accepting `／`.
 */
internal fun ChatViewModel.updateMentionMenuState(text: String, caret: Int) {
    val safeCaret = caret.coerceIn(0, text.length)
    // Walk back from caret to find an `@` that opens the active token.
    var anchor = -1
    var i = safeCaret
    while (i > 0) {
        val prev = i - 1
        val ch = text[prev]
        if (ch.isWhitespace()) break
        if (ch == '@' || ch == '＠') {
            // Require start-of-text or whitespace before `@` so emails
            // ("foo@bar.com") don't pop the menu.
            if (prev == 0 || text[prev - 1].isWhitespace()) {
                anchor = prev
            }
            break
        }
        i = prev
    }
    if (anchor < 0) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    val filter = text.substring(anchor + 1, safeCaret)
    val filterChanged = _mentionFilter.value != filter
    val listModels = shouldListGroupModels()
    // Skill commands (`/`) must not stay on top of an @ that addresses a model.
    // Slash still wins for a plain file mention.
    if (listModels) {
        if (_showSlashMenu.value) {
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
        }
    } else if (_showSlashMenu.value) {
        if (_showMentionMenu.value) dismissMentionMenu()
        return
    }
    _mentionAnchor.value = anchor
    _mentionFilter.value = filter
    if (!_showMentionMenu.value) {
        _showMentionMenu.value = true
        // Mirror iOS: pre-select row 0 so a hardware-keyboard Return
        // commits the top match without an extra Down press.
        _mentionSelectedIndex.value = 0
        // Scanning files is what filled the popup with skills/… paths.
        if (!listModels) {
            val sid = realSessionId.ifEmpty { sessionId }
            if (sid.isNotEmpty()) fileMentionIndex.refreshIfNeeded(sid)
        }
        Log.i(ChatViewModel.TAG, "mention menu open anchor=$anchor filter=\"$filter\" models=$listModels")
    } else if (listModels && filterChanged) {
        // The top row is the match Enter will insert. Don't keep a highlight
        // that belonged to the previous, longer list.
        _mentionSelectedIndex.value = 0
    } else {
        val current = _mentionSelectedIndex.value
        if (current < 0) _mentionSelectedIndex.value = 0
    }
}

internal fun ChatViewModel.dismissMentionMenu() {
    if (!_showMentionMenu.value) return
    _showMentionMenu.value = false
    _mentionFilter.value = ""
    _mentionAnchor.value = -1
    _mentionSelectedIndex.value = -1
}

/**
 * T-at-filepicker-keyboard: hardware-keyboard navigation helpers. Wraparound
 * matches iOS so Up at row 0 lands at the last row and vice versa.
 */
internal fun ChatViewModel.mentionMenuUp() {
    val count = activeMentionCount()
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx <= 0) count - 1 else idx - 1
}

internal fun ChatViewModel.mentionMenuDown() {
    val count = activeMentionCount()
    if (count <= 0) return
    val idx = _mentionSelectedIndex.value
    _mentionSelectedIndex.value = if (idx >= count - 1) 0 else idx + 1
}

/**
 * Commit the highlighted entry (or the first match) into [currentText],
 * returning the new (text, caret). Returns null when the menu has no
 * matches to commit, so the caller can fall through to its default
 * Return-key handler (e.g. send-on-enter).
 */
internal fun ChatViewModel.executeSelectedMention(
    currentText: String,
    currentCaret: Int,
): Pair<String, Int>? {
    if (shouldListGroupModels()) {
        val models = groupMentions.value
        if (models.isEmpty()) return null
        val idx = _mentionSelectedIndex.value.let {
            if (it in models.indices) it else 0
        }
        return selectGroupMention(models[idx], currentText, currentCaret)
    }
    val entries = mentionEntries.value
    if (entries.isEmpty()) return null
    val idx = _mentionSelectedIndex.value.let {
        if (it in entries.indices) it else 0
    }
    return selectMention(entries[idx], currentText, currentCaret)
}

internal fun ChatViewModel.selectGroupMention(
    entry: GroupChat.MentionCandidate,
    currentText: String,
    currentCaret: Int,
): Pair<String, Int> {
    val anchor = _mentionAnchor.value
    if (anchor < 0 || anchor > currentText.length) {
        dismissMentionMenu()
        return currentText to currentCaret
    }
    var endOffset = anchor + 1
    while (endOffset < currentText.length && !currentText[endOffset].isWhitespace()) {
        endOffset++
    }
    val token = GroupChat.mentionInsertToken(entry, currentGroupMentions())
    val replacement = "@$token "
    val newText = currentText.substring(0, anchor) +
        replacement +
        currentText.substring(endOffset)
    dismissMentionMenu()
    return newText to (anchor + replacement.length)
}

internal fun ChatViewModel.currentGroupMentions(): List<GroupChat.MentionCandidate> {
    val config = providerRepository.config.value
    val entries = config.modelEntries
    val hostEntry = _activeEntryId.value?.let { id -> entries.find { it.id == id } }
    val hostModel = hostEntry?.model ?: currentModel
    val hostName = hostModel?.displayName?.trim().orEmpty()
    val hostModelId = hostModel?.id?.trim().orEmpty()
    val soulName = com.openminis.app.agent.SoulStore.cachedMetadata.value.name.trim()
    val host = GroupChat.MentionCandidate(
        name = hostName,
        modelId = hostModelId,
        vendor = GroupChat.vendorKey(hostModelId, hostName),
        host = true,
        extra = soulName,
    )
    val slotIds = MultiAgentSettings.retainLive(
        multiAgentSettings.selectedModelEntryIds.value,
        entries.map { it.id }.toSet(),
        multiAgentSettings.maxConcurrent.value,
    )
    val others = slotIds.mapNotNull { id ->
        if (id.isBlank() || id == hostEntry?.id) return@mapNotNull null
        val entry = entries.find { it.id == id } ?: return@mapNotNull null
        GroupChat.MentionCandidate(
            name = entry.model.displayName,
            modelId = entry.model.id,
            vendor = GroupChat.vendorKey(entry.model.id, entry.model.displayName),
            host = false,
        )
    }
    return GroupChat.mentionCandidates(host, others)
}

private fun ChatViewModel.activeMentionCount(): Int =
    if (shouldListGroupModels()) groupMentions.value.size else mentionEntries.value.size

/**
 * Replace the active `@<token>` in [currentText] with `@<linuxPath> ` and
 * return the new text + caret position. The caller writes both back into
 * its TextFieldValue so the cursor lands right after the inserted space
 * — exactly mirroring iOS [AIChatViewModel.selectMention] which sets
 * `inputText` and `pendingCaret` in lockstep.
 *
 * If the menu is not open the call is a no-op and returns the original
 * (text, caret).
 */
internal fun ChatViewModel.selectMention(
    entry: FileMentionIndex.Entry,
    currentText: String,
    currentCaret: Int,
): Pair<String, Int> {
    val anchor = _mentionAnchor.value
    if (anchor < 0 || anchor > currentText.length ||
        entry.linuxPath == "/var/minis/skills" ||
        entry.linuxPath.startsWith("/var/minis/skills/")
    ) {
        dismissMentionMenu()
        return currentText to currentCaret
    }
    // Replacement span: from `@` up to next whitespace (or end).
    var endOffset = anchor + 1
    while (endOffset < currentText.length && !currentText[endOffset].isWhitespace()) {
        endOffset++
    }
    val replacement = "@${entry.linuxPath} "
    val newText = currentText.substring(0, anchor) +
        replacement +
        currentText.substring(endOffset)
    val newCaret = anchor + replacement.length
    // Dismiss before announcing the new text so the consumer's
    // updateMentionMenuState callback (fired on text change) doesn't
    // re-open the menu against the inserted path.
    dismissMentionMenu()
    return newText to newCaret
}
