package com.openminis.app.ui.sessions

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.session.SessionDeleter
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(FlowPreview::class)
class SessionListViewModel(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "SessionListVM"

        /**
         * [GH#210] The placeholder ChatViewModel writes for a session whose
         * title has not been generated yet. The fallback path keys off this
         * exact string (plus null/blank), which is what keeps a user-typed
         * title from ever being overwritten.
         */
        private const val NEW_CHAT_TITLE = "New Chat"

        /**
         * [T-android-group-ai-suggest] Verbatim from iOS `suggestFolder`, so
         * both platforms constrain the sub model identically.
         */
        private const val GROUP_SUGGEST_SYSTEM_PROMPT =
            "You organize chat sessions into folders. Respond with a single valid JSON object only."

        /**
         * [T-android-group-ai-suggest] Parse the sub model's JSON reply.
         *
         * Mirrors iOS: the JSON is located by first `{` / last `}` (models
         * habitually wrap it in prose or a ```json fence), and a "merge"
         * naming a group that does not exist degrades to the CREATE branch
         * with the string prefilled — so the user still gets a one-tap path
         * and nothing is invented silently.
         *
         * `internal` for unit testing; the parse is the part most likely to
         * meet malformed model output, and it is pure.
         */
        internal fun parseGroupSuggestion(
            text: String,
            folders: List<FolderEntity>,
        ): GroupSuggestion? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = try {
                JSONObject(text.substring(start, end + 1))
            } catch (_: Exception) {
                return null
            }
            val decision = json.optString("decision").lowercase()
            val folderName = json.optString("folder").takeIf { it.isNotBlank() }
            if (decision == "merge" && folderName != null) {
                // Duplicate names are legal in the schema (two devices can each
                // create "Work" offline), so tie-break on the most recently
                // touched record — the merge has to land somewhere predictable.
                val match = folders
                    .filter { it.name.trim().equals(folderName.trim(), ignoreCase = true) }
                    .maxByOrNull { it.updatedAt }
                if (match != null) return GroupSuggestion.Merge(match.id, match.name)
            }
            val newName = (json.optString("name").takeIf { it.isNotBlank() } ?: folderName)?.trim()
            if (newName.isNullOrEmpty()) return null
            val desc = json.optString("description").trim()
                .takeIf { it.isNotEmpty() }?.take(FolderEntity.DESC_MAX_CHARS)
            return GroupSuggestion.Create(newName, desc)
        }

        /**
         * Factory for use with `androidx.lifecycle.viewmodel.compose.viewModel`.
         * Hosting the VM on the NavBackStackEntry's ViewModelStore (instead of
         * `remember {}` inside the composable) is what lets [searchQuery] and
         * [isSearchActive] survive navigation push/pop — the user can tap a
         * session in the search results, view it, and pop back to find the
         * filter still applied. Mirrors iOS where ContentView's `@State
         * searchText` survives because the parent view does not unmount during
         * a NavigationLink push.
         */
        fun factory(
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SessionListViewModel(
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                ) as T
            }
        }
    }

    private val _allSessions = MutableStateFlow<List<ChatSessionEntity>>(emptyList())

    /**
     * Tracks whether the first DB emission has landed. Before this flips true
     * the session list is "unknown" — not "empty". Callers (e.g. onboarding
     * gate) must wait for this before deciding the user has no history,
     * otherwise the onboarding UI flashes on launch for users with existing
     * sessions. Mirrors iOS `didInitialLoad` on ContentView.
     */
    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

    // Search
    val searchQuery = MutableStateFlow("")
    val isSearchActive = MutableStateFlow(false)
    val searchResults = MutableStateFlow<List<ChatSessionEntity>>(emptyList())

    /**
     * True while the user has typed something but the debounced search query
     * has not yet finalised + run. Drives the trailing CircularProgressIndicator
     * in the search field so a slow query (or fast typing) shows visible
     * progress instead of a stale-results-then-snap transition. Cleared the
     * moment a query resolves to results (or to empty when query is blank).
     */
    val isSearching = MutableStateFlow(false)

    /**
     * Per-session content snippet centred on the search-query match. Only
     * populated for sessions whose match is in message content (not just the
     * title). Cleared whenever the search query goes blank. Keyed by
     * session id; absent entries mean "title-only match — no snippet needed".
     */
    val searchSnippets = MutableStateFlow<Map<String, String>>(emptyMap())

    // The list to actually show: search results when searching, otherwise all sessions
    val displayedSessions: StateFlow<List<ChatSessionEntity>> = combine(
        _allSessions, searchResults, searchQuery, isSearchActive
    ) { all, results, q, active ->
        if (active && q.isNotBlank()) results else all
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ─── Session groups ("folders") ────────────────────────────────────────
    // [T-android-session-grouping]

    /**
     * Groups, ordered `updated_at DESC`.
     *
     * Collected in the SAME init block as the session list, not lazily on
     * first use: if groups arrive after sessions, the first paint sees every
     * filed session as an orphan and draws a flat list, then visibly reflows —
     * the "group cards only show up after a moment" symptom iOS hit.
     */
    val folders = MutableStateFlow<List<FolderEntity>>(emptyList())

    /**
     * [T-android-groups-collapsed-on-launch] The one group that is open, or
     * null when every group is collapsed.
     *
     * Deliberately NOT persisted. The user wants every group folded on each
     * cold start and at most one open at a time, opened by their own tap. The
     * previous model kept a persisted set of collapsed groups (the inverse:
     * which groups are shut) plus a persisted "last expanded" id, and fell
     * back to "first group not in the set" — so a cold start always opened a
     * group the user had not just asked for, and a set that had never been
     * written (fresh install, restore, a group created since) opened one too.
     * A single nullable id makes both rules structural: null on launch means
     * all folded, and one id cannot name two open groups.
     *
     * Kept in memory for the life of the process, so the open group survives
     * going into a chat and back.
     */
    val expandedFolderId = MutableStateFlow<String?>(null)

    init {
        // Drop the keys the persisted model wrote; nothing reads them now.
        context.getSharedPreferences("session_list_ui", Context.MODE_PRIVATE)
            .edit().remove("collapsedFolderIds").remove("lastExpandedFolderId").apply()
    }

    /**
     * Expand exactly [folderId]; any other open group closes (accordion, as
     * iOS `ContentView.toggleFolder`). Used where the app itself must show a
     * group's rows — a group just created from a selection.
     */
    private fun expandOnly(folderId: String) {
        expandedFolderId.value = folderId
    }

    /** Non-null while the group picker is open. */
    val groupPickerRequest = MutableStateFlow<GroupPickerRequest?>(null)

    /**
     * Sessions the picker is about to file.
     *
     * @param anyFiled true when at least one already has a group — ANY, not
     *   all, so a mixed multi-selection still offers "No Group".
     * @param fromMultiSelect drives teardown: selection mode is exited only
     *   after the sheet closes, never at choice time, so the two animations
     *   don't fight.
     */
    data class GroupPickerRequest(
        val sessionIds: List<String>,
        val anyFiled: Boolean,
        val fromMultiSelect: Boolean,
    )

    /**
     * [T-android-group-ai-suggest] Outcome of the manual "✨ AI Suggest" flow
     * in the group picker. Ported from iOS `AIChatViewModel.FolderSuggestion`.
     *
     * Nothing here is auto-applied. A merge renders as a confirm row and a
     * create prefills the name/description fields — the user still taps. iOS
     * made that call deliberately and the reasoning carries over unchanged: a
     * wrong grouping is a batch data move, whereas a wrong title is one edit.
     */
    sealed interface GroupSuggestion {
        data class Merge(val folderId: String, val folderName: String) : GroupSuggestion

        data class Create(val name: String, val description: String?) : GroupSuggestion
    }

    /** True while a suggestion request is in flight (drives the spinner). */
    val groupSuggesting = MutableStateFlow(false)

    /** Last successful suggestion, consumed by the sheet. Cleared on re-run. */
    val groupSuggestion = MutableStateFlow<GroupSuggestion?>(null)

    /** True when the last attempt failed — the button relabels to invite a retry. */
    val groupSuggestFailed = MutableStateFlow(false)

    // Multi-select
    val isSelecting = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    // Session IDs currently regenerating their titles (UI overlay)
    val regeneratingIds = MutableStateFlow<Set<String>>(emptySet())

    // [T-android-newchat-list-autoscroll] One-shot signal: a session id that
    // we have never seen before has appeared at the TOP of the list (the list
    // is ORDER BY updated_at DESC, so a brand-new chat lands at index 0). The
    // UI collects this and scrolls the list to the top so the new chat is
    // visible — needed because the LazyColumn keeps its old scroll offset
    // across navigation (open chat → back). Lives in the VM (retained across
    // navigation) so the baseline isn't reset when the list composable is
    // disposed during the chat-detail push, which a composable-scoped tracker
    // would lose. extraBufferCapacity=1 + DROP_OLDEST so an emission that
    // happens while the UI isn't collecting (mid-navigation) is still
    // delivered on the next collect.
    val newTopSessionEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    // Baseline of session ids already observed. Seeded on the FIRST emission
    // (so pre-existing sessions never fire the event); thereafter any id not
    // in this set that lands at index 0 is a genuinely-new session.
    private var knownSessionIds: Set<String> = emptySet()
    private var newTopBaselineSeeded = false

    init {
        // T-android-crash-safe-mode-v2: gate the cold-start session list
        // observer behind the safe-mode flag. The Room observable issues a
        // full SELECT on first collect; if a malformed row was contributing
        // to the crash burst, we don't want to re-deserialize it before the
        // user has acknowledged the share-logs dialog.
        viewModelScope.launch {
            if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
                android.util.Log.w(
                    TAG,
                    "SessionListVM init: safe-mode active, deferring observeSessions",
                )
                // Mark initial-load complete so the empty-state UI surfaces
                // immediately (rather than an indefinite progress spinner).
                _isInitialLoadComplete.value = true
                // Subscribe for the safe-mode-cleared signal and then begin
                // observing. registerSafeModeClearedListener fires exactly
                // once on ON → OFF; after that we start the Flow collector
                // for the rest of the VM's life.
                val started = kotlinx.coroutines.CompletableDeferred<Unit>()
                val unsub = com.openminis.app.crash.CrashFrequencyDetector
                    .registerSafeModeClearedListener {
                        if (!started.isCompleted) started.complete(Unit)
                    }
                started.await()
                runCatching { unsub() }
            }
            chatRepository.observeSessions().collect { rows ->
                // [T-p1-delegate-task] Child sessions never appear in the home
                // list (design §3.2) — filtered HERE, in the UI layer, not in the
                // DAO: sync, backup and the debug RPC must still see them. Done
                // at the source rather than only in displayedSessions so group
                // counts, select-all and the new-session detector agree.
                val it = rows.filter { !it.isChild }
                _allSessions.value = it
                if (!_isInitialLoadComplete.value) _isInitialLoadComplete.value = true
                detectNewTopSession(it)
            }
        }
        // [T-android-session-grouping] Started alongside the session collector,
        // not after it — see `folders` for why ordering matters on first paint.
        viewModelScope.launch {
            chatRepository.observeFolders().collect { folders.value = it }
        }
        viewModelScope.launch {
            combine(searchQuery, isSearchActive) { q, active -> q to active }
                .distinctUntilChanged()
                .onEach { (q, active) ->
                    // Flip [isSearching] true the moment a meaningful query
                    // arrives, BEFORE debounce. The trailing CircularProgress
                    // shows up immediately when the user types, hiding the
                    // small gap until the debounced search runs.
                    isSearching.value = active && q.isNotBlank()
                }
                .debounce(300)
                .collect { (q, active) ->
                    if (active && q.isNotBlank()) {
                        // [T-android-search-visible-only] One query returns each
                        // result with the line that shows why it matched (iOS
                        // bbd21900a / 6b0ee14c1). Title matches keep their line
                        // too, as on iOS; a title-only match has none and the
                        // row falls back to its normal preview.
                        // [T-android-search-blob-cap] A failing query must not
                        // take the app down: this collector runs in
                        // viewModelScope, where an uncaught SQLiteException
                        // (e.g. a row past the 2 MB CursorWindow) crashed the
                        // process on every search for that word.
                        val hits = withContext(Dispatchers.IO) {
                            try {
                                chatRepository.searchSessionsWithHits(q)
                            } catch (e: android.database.sqlite.SQLiteException) {
                                com.openminis.app.logging.AppLogger.error(
                                    "SessionList", "[Search] query failed: ${e.javaClass.simpleName}: ${e.message}",
                                )
                                emptyList()
                            }
                        }
                        searchResults.value = hits.map { it.session }
                        searchSnippets.value = hits.mapNotNull { h -> h.snippet?.let { h.session.id to it } }.toMap()
                    } else {
                        searchResults.value = emptyList()
                        searchSnippets.value = emptyMap()
                    }
                    isSearching.value = false
                }
        }
    }

    fun toggleSelect(id: String) {
        selectedIds.value = selectedIds.value.toMutableSet().also {
            if (id in it) it.remove(id) else it.add(id)
        }
    }

    /**
     * [T-android-sessionlist-longpress-select] Long-press → Select: enter
     * selection mode WITH this row selected. ADD semantics, not toggle — if
     * the id is somehow already in the set, tapping Select must still select
     * it. The context-menu item previously only toggled the id into
     * [selectedIds] without ever setting [isSelecting], so the list never
     * showed checkboxes and the id sat invisibly pre-selected.
     */
    fun enterSelection(id: String) {
        selectedIds.value = selectedIds.value + id
        isSelecting.value = true
    }

    fun selectAll() {
        selectedIds.value = _allSessions.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        isSelecting.value = false
    }

    fun deleteSelected() {
        val ids = selectedIds.value.toList()
        viewModelScope.launch {
            // [T-android-child-session-delete-storage] One funnel: DB rows,
            // files, ViewModel, badges, running helper jobs — for the session
            // AND every hidden child session under it.
            ids.forEach { SessionDeleter.deleteTree(context, chatRepository, it, "list-multi") }
        }
        clearSelection()
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            SessionDeleter.deleteTree(context, chatRepository, id, "list-single")
        }
    }

    // ─── Session group actions ─────────────────────────────────────────────
    // [T-android-session-grouping]

    /**
     * True only for a session filed into a group that EXISTS locally. A dangling
     * folder_id is displayed as ungrouped, so treating it as filed would offer
     * "暂不分组" for a group the user cannot see — and label the action "Change" when
     * there is nothing to change from. Mirrors partitionByFolder's presence test.
     */
    private fun isFiled(session: ChatSessionEntity?): Boolean {
        val fid = session?.folderId ?: return false
        return folders.value.any { it.id == fid }
    }

    /** Open the picker for ONE session (context-menu entry point). */
    fun requestGroupPicker(sessionId: String) {
        val filed = isFiled(_allSessions.value.firstOrNull { it.id == sessionId })
        groupPickerRequest.value = GroupPickerRequest(
            sessionIds = listOf(sessionId),
            anyFiled = filed,
            fromMultiSelect = false,
        )
    }

    /** Open the picker for the current multi-selection (toolbar entry point). */
    fun requestGroupPickerForSelection() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        val anyFiled = _allSessions.value.any { it.id in ids && isFiled(it) }
        groupPickerRequest.value = GroupPickerRequest(
            sessionIds = ids,
            anyFiled = anyFiled,
            fromMultiSelect = true,
        )
    }

    fun dismissGroupPicker() {
        val wasMultiSelect = groupPickerRequest.value?.fromMultiSelect == true
        groupPickerRequest.value = null
        // [T-android-group-ai-suggest] Reset suggestion state with the sheet.
        // These flows outlive the composable (they live on the VM so an
        // in-flight request survives recomposition), so without this the next
        // open would inherit the previous selection's suggestion — offering to
        // merge sessions the user never picked.
        groupSuggestion.value = null
        groupSuggestFailed.value = false
        groupSuggesting.value = false
        // Teardown happens HERE, after the sheet is gone — tearing down at
        // choice time makes the selection UI animate out from under the
        // closing sheet.
        if (wasMultiSelect) clearSelection()
    }

    /**
     * [T-android-group-ai-suggest] Ask the sub model where the selected
     * sessions belong. Port of iOS `AIChatViewModel.suggestFolder`.
     *
     * Context sent is deliberately lightweight: existing group names (plus
     * their descriptions and a few member titles, for the merge judgment) and
     * the selected sessions' titles + categories. Titles are already
     * AI-written semantic summaries, so **no message content** crosses into
     * the sub model from this path — the same privacy property iOS relies on.
     */
    fun suggestGroup() {
        val request = groupPickerRequest.value ?: return
        if (groupSuggesting.value) return
        val sessionIds = request.sessionIds
        if (sessionIds.isEmpty()) {
            AppLogger.error(TAG, "[GroupSuggest] FAILED reason=no-sessions-selected")
            groupSuggestFailed.value = true
            return
        }
        groupSuggesting.value = true
        groupSuggestFailed.value = false
        groupSuggestion.value = null
        AppLogger.info(TAG, "[GroupSuggest] START sessions=${sessionIds.size}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = runGroupSuggestion(sessionIds)
                withContext(Dispatchers.Main) { groupSuggestion.value = result }
            } catch (e: Exception) {
                // iOS's [T-ios-folder-suggest-retry] lesson: the UI flag alone
                // left "failed — try again" with nothing in the log to hunt
                // with. Every exit below is traced with its reason.
                AppLogger.error(TAG, "[GroupSuggest] FAILED reason=exception error=${e.message}")
                withContext(Dispatchers.Main) { groupSuggestFailed.value = true }
            } finally {
                withContext(Dispatchers.Main) { groupSuggesting.value = false }
            }
        }
    }

    /** Clears a consumed/stale suggestion so the sheet stops offering it. */
    fun clearGroupSuggestion() {
        groupSuggestion.value = null
        groupSuggestFailed.value = false
    }

    private suspend fun runGroupSuggestion(sessionIds: List<String>): GroupSuggestion {
        // [T-ios-folder-suggest-anchor-nondeterminism] Walk the selection in a
        // STABLE (sorted) order and take the first session that actually
        // resolves a usable model, instead of letting one arbitrary session
        // decide whether the feature works. On iOS the ids arrived from a Set,
        // so an unlucky first session with an unconfigured model made
        // "AI Suggest" fail permanently for reasons the user could neither see
        // nor influence. Ours is a List, but the same failure mode applies to
        // whichever session happens to be first, and sorting also makes the
        // sample below reproducible across launches.
        val sorted = sessionIds.sorted()

        val titleEligible = providerRepository.allVisibleEntries().filter { entry ->
            val outs = entry.model.outputModalities
            val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
            val idLower = entry.model.id.lowercase()
            val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
                .any { idLower.contains(it) }
            outputsText && !nonChatId
        }
        val subEntry = providerRepository.resolveTitleSubEntry()
            ?.takeIf { sub -> titleEligible.any { it == sub } }
        // Anchor on the first selected session whose bound model is usable,
        // falling back to the dedicated sub-model and then anything eligible.
        val anchorPrimary = sorted.firstNotNullOfOrNull { sid ->
            val modelId = chatRepository.getSession(sid)?.modelId ?: return@firstNotNullOfOrNull null
            titleEligible.firstOrNull { it.model.id == modelId }
        }
        val candidates = (listOfNotNull(subEntry, anchorPrimary) +
            titleEligible.filter { it != subEntry && it != anchorPrimary })
        if (candidates.isEmpty()) {
            AppLogger.error(
                TAG,
                "[GroupSuggest] FAILED reason=no-sub-model-available " +
                    "(no enabled+credentialed+title-eligible entry for ${sessionIds.size} session(s))",
            )
            throw IllegalStateException("No sub model available")
        }

        // Existing groups + up to 3 member titles each, deduped by name so two
        // offline-created "Work" folders don't both bid for the merge.
        val folders = chatRepository.listFolders()
        val seenNames = mutableSetOf<String>()
        val folderLines = mutableListOf<String>()
        for (f in folders) {
            if (!seenNames.add(f.name.lowercase())) continue
            val memberTitles = chatRepository.sessionIdsInFolder(f.id).take(3)
                .mapNotNull { chatRepository.getSession(it)?.title }
            val descPart = f.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            folderLines += "- \"${f.name}\"$descPart: ${memberTitles.joinToString(" / ")}"
        }

        // Sorted + capped at 20 for the same reason as the anchor: an unsorted
        // sample would send a different 20 sessions on each run.
        val sessionLines = sorted.take(20).mapNotNull { sid ->
            chatRepository.getSession(sid)?.let { s ->
                "- ${s.title ?: "Untitled"} [${s.category ?: "other"}]"
            }
        }
        if (sessionLines.isEmpty()) {
            AppLogger.error(
                TAG,
                "[GroupSuggest] FAILED reason=no-sessions-found " +
                    "(${sessionIds.size} id(s) selected, none resolved in the store)",
            )
            throw IllegalStateException("No sessions found")
        }

        val foldersBlock = if (folderLines.isEmpty()) "The user has no folders yet."
        else "Existing folders with sample member titles:\n${folderLines.joinToString("\n")}"
        val prompt = buildString {
            append("The user selected these chat sessions to file into a folder:\n")
            append(sessionLines.joinToString("\n"))
            append("\n\n")
            append(foldersBlock)
            append("\n\n")
            append("Decide: merge them into ONE existing group (only if they clearly fit it), ")
            append("or propose ONE new group name (2-8 characters preferred, in the same language as the session titles). ")
            append("For a new group also write \"description\": one sentence (under 100 characters, same language as the name) ")
            append("describing what belongs in it — it will guide future automatic grouping.\n\n")
            append("You MUST respond with valid JSON only. Examples:\n")
            append("{\"decision\": \"merge\", \"folder\": \"Work\"}\n")
            append("{\"decision\": \"create\", \"name\": \"Trip Planning\", \"description\": \"Flights, hotels and itineraries for upcoming trips\"}")
        }

        var lastError: Exception? = null
        for (entry in candidates) {
            val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
            // [T-android-keyless-provider-selection] usableApiKey: a keyless
            // self-hosted provider is a valid candidate, and `loadApiKey`
            // silently skipped it here. See QuickTestSheet for the rationale.
            // [T-android-copilot-not-connected] `?: continue` dropped any OAuth
            // provider that keeps no API key — Copilot stores its credential
            // under its own keys — so it was never a candidate here.
            var apiKey = providerRepository.usableApiKey(instance)
                ?: if (providerRepository.hasAnyCredential(instance)) "" else continue
            if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
                try {
                    val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val freshToken = manager?.validAccessToken()
                    if (freshToken != null && freshToken != apiKey) {
                        providerRepository.saveApiKey(instance.id, freshToken)
                        apiKey = freshToken
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GroupSuggest OAuth refresh failed: ${e.message}")
                }
            }
            val provider = try {
                ProviderFactory.create(instance, apiKey, entry.model, context, overrides = entry.overrides)
            } catch (e: Exception) {
                Log.w(TAG, "GroupSuggest provider creation failed for ${entry.model.displayName}: ${e.message}")
                continue
            }
            try {
                // Same budget shape as title-gen: a reasoning model needs room
                // to finish thinking before it can emit the JSON, even with
                // thinking explicitly OFF (which is a no-op on some models).
                val maxTokens = if (entry.model.supportsReasoning == true) 2048 else 256
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = GROUP_SUGGEST_SYSTEM_PROMPT,
                    maxTokens = maxTokens,
                    // null, not 0.3 — the gpt-5.x family 400s on anything but
                    // temperature=1, which would silently skip the candidate.
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                )
                val parsed = parseGroupSuggestion(response.text, folders)
                if (parsed != null) {
                    AppLogger.info(TAG, "[GroupSuggest] OK model=${entry.model.id} result=$parsed")
                    return parsed
                }
                Log.w(
                    TAG,
                    "GroupSuggest empty/unparseable from ${entry.model.displayName}: " +
                        "stopReason=${response.stopReason} raw=\"${response.text.take(160)}\"",
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "GroupSuggest via ${entry.model.displayName} failed: ${e.message}")
                continue
            }
        }
        AppLogger.error(TAG, "[GroupSuggest] FAILED reason=all-candidates-exhausted last=${lastError?.message}")
        throw lastError ?: IllegalStateException("Unparseable suggestion")
    }

    fun applyGroupChoice(choice: GroupChoice) {
        val request = groupPickerRequest.value ?: return
        viewModelScope.launch {
            when (choice) {
                is GroupChoice.Existing ->
                    chatRepository.setFolderForSessions(choice.folderId, request.sessionIds)
                is GroupChoice.Create -> {
                    // [T-android-group-ai-suggest] Stamp provenance when the
                    // name being created is the one AI Suggest proposed. The
                    // `origin` column exists for exactly this and nothing
                    // branches on it today — but recording it at the moment we
                    // know is the only chance; the picker's create path cannot
                    // reconstruct it later.
                    val suggested = groupSuggestion.value as? GroupSuggestion.Create
                    val fromAi = suggested != null &&
                        suggested.name.trim().equals(choice.name.trim(), ignoreCase = true)
                    val folder = chatRepository.createFolder(
                        choice.name,
                        choice.description,
                        origin = if (fromAi) FolderEntity.ORIGIN_AI else FolderEntity.ORIGIN_MANUAL,
                    )
                    chatRepository.setFolderForSessions(folder.id, request.sessionIds)
                    // A brand-new group starts expanded so the sessions the
                    // user just filed are visible immediately — and, being an
                    // accordion, that closes whatever was open before.
                    expandOnly(folder.id)
                }
                GroupChoice.RemoveFromGroup ->
                    chatRepository.setFolderForSessions(null, request.sessionIds)
            }
            AppLogger.info(
                TAG,
                "[Group] applied ${choice::class.simpleName} to ${request.sessionIds.size} session(s)",
            )
            dismissGroupPicker()
        }
    }

    /**
     * Accordion toggle: tapping a closed group opens it and closes whichever
     * was open; tapping the open group closes it, leaving all folded.
     */
    fun toggleFolderCollapsed(folderId: String) {
        expandedFolderId.value = if (expandedFolderId.value == folderId) null else folderId
    }

    /**
     * iOS requestDeleteFolderWithSessions parity: delete the folder AND every
     * member session. Reuses the standard per-session delete path (repo
     * delete + VM cache release + badge clear) rather than a bespoke one —
     * that path owns the correctness. The folder row itself is dropped via
     * dissolveFolder AFTER the members are gone (it is empty by then, so
     * dissolve degenerates to deleting the row), mirroring iOS's
     * pendingDeleteFolderId epilogue.
     */
    fun deleteFolderWithSessions(folderId: String) {
        viewModelScope.launch {
            val memberIds = chatRepository.sessionIdsInFolder(folderId)
            for (id in memberIds) {
                SessionDeleter.deleteTree(context, chatRepository, id, "folder-delete")
            }
            chatRepository.dissolveFolder(folderId)
            // The folder no longer exists: if it was the open one, everything
            // is folded now. Never open another group in its place.
            expandedFolderId.compareAndSet(folderId, null)
            AppLogger.info(
                TAG,
                "[Group] deleted folder ${folderId.take(8)} with ${memberIds.size} session(s)",
            )
        }
    }

    fun toggleFolderPin(folderId: String) {
        viewModelScope.launch { chatRepository.toggleFolderPin(folderId) }
    }

    fun renameFolder(folderId: String, name: String, description: String?) {
        viewModelScope.launch { chatRepository.renameFolder(folderId, name, description) }
    }

    /**
     * Dissolve: the group row goes away and its members return to the ungrouped
     * list. **No session is deleted** — this is the only way to remove a group,
     * so a misfire can never cost a conversation.
     */
    fun dissolveFolder(folderId: String) {
        viewModelScope.launch {
            val freed = chatRepository.dissolveFolder(folderId)
            // Stale-id cleanup only — see deleteFolderWithSessions.
            expandedFolderId.compareAndSet(folderId, null)
            AppLogger.info(TAG, "[Group] dissolved ${folderId.take(8)}, freed ${freed.size} session(s)")
        }
    }

    /**
     * [T-android-group-picker-recent] Newest member activity per group: the
     * latest `updatedAt` of the sessions filed in it (a new message, a rename).
     * Groups with no members are absent. Only the Move to Group picker reads
     * it — the session list keeps its own group order.
     */
    val folderLastActivity: StateFlow<Map<String, Long>> =
        _allSessions.map { sessions ->
            buildMap {
                for (s in sessions) {
                    val fid = s.folderId ?: continue
                    if (s.updatedAt > (get(fid) ?: Long.MIN_VALUE)) put(fid, s.updatedAt)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Member count per group, for the picker subtitles and group cards. */
    val folderMemberCounts: StateFlow<Map<String, Int>> =
        combine(_allSessions, folders) { sessions, _ ->
            sessions.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun togglePin(id: String) {
        viewModelScope.launch {
            val session = chatRepository.getSession(id) ?: return@launch
            val newPinnedAt = if (session.pinnedAt != null) null else System.currentTimeMillis()
            chatRepository.dao.updatePinnedAt(id, newPinnedAt)
        }
    }

    fun updateTitleAndCategory(id: String, title: String, category: String?) {
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(id, title, category)
        }
    }

    fun regenerateTitle(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                regeneratingIds.value = regeneratingIds.value + id
            }
            try {
                generateTitleFromStore(id, origin = "manual")
            } finally {
                withContext(Dispatchers.Main) {
                    regeneratingIds.value = regeneratingIds.value - id
                }
            }
        }
    }

    /**
     * [GH#210] Generate a title for [id] purely from what is in the DB.
     *
     * Takes no ViewModel state, so the same code serves any USER-INITIATED
     * entry point. It is deliberately never called automatically: an implicit
     * LLM request the user did not ask for — e.g. sweeping untitled sessions
     * at list load — would mean a burst of hidden network calls at startup,
     * which is not an acceptable trade for a cosmetic title.
     *
     * @param origin tags the log line so different entry points can be told
     *   apart when reconciling dispatch against outcome.
     * @return true when a title was written (LLM or fallback).
     */
    private suspend fun generateTitleFromStore(id: String, origin: String): Boolean {
        val startedAt = System.currentTimeMillis()
        var firstUserRaw: String? = null
        try {
                val session = chatRepository.getSession(id) ?: return false
                val messages = chatRepository.loadMessages(id)
                if (messages.isEmpty()) return false

                // [T-titlegen-context-first-last-pair] Summary = first user +
                // first assistant, plus (when the session has more than one user
                // turn) the last user + last assistant, each truncated to 200
                // chars — so a regenerated title reflects a mid/late topic shift
                // rather than only the opener.
                val userMessages = messages.filter { it.role == "user" }
                // Keep the untruncated first user message for the fallback path.
                firstUserRaw = userMessages.firstOrNull()?.let { extractText(it.partsJson) }
                val userText = firstUserRaw?.take(200) ?: return false
                // First/last assistant *text* message — skip tool-only messages
                // whose extracted text is blank so the summary carries real prose.
                val assistantTexts = messages.filter { it.role == "assistant" }
                    .map { extractText(it.partsJson) }
                    .filter { it.isNotBlank() }
                val firstAssistantText = assistantTexts.firstOrNull()?.take(200) ?: ""
                val hasMultipleUserTurns = userMessages.size > 1
                val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.let { extractText(it.partsJson) }?.take(200) ?: "" else ""
                val lastAssistantText = if (hasMultipleUserTurns) assistantTexts.lastOrNull()?.take(200) ?: "" else ""

                val prompt = buildString {
                    append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                    append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                    append("You MUST respond with valid JSON only. Example:\n")
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                    append("User: $userText\n")
                    if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                    if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                    if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                    append(com.openminis.app.ui.chat.titleLanguageDirective())
                }

                // [T-titlegen-group-order] Candidates in iOS order (title sub
                // group → the session's own model/group → default primary
                // group → every other eligible model), each GROUP walked whole
                // in the order the agent loop falls back through it — not just
                // its first member. T334 eligibility and availability are
                // applied inside. See TitleCandidates.
                val candidates = com.openminis.app.ui.chat.TitleCandidates.forSession(
                    providerRepository,
                    sessionId = id,
                    primary = com.openminis.app.ui.chat.TitleCandidates.PrimarySource.parse(session.modelBinding),
                    sessionModelId = session.modelId,
                )
                val written = com.openminis.app.ui.chat.TitleCandidates.walk(candidates, origin = origin) { entry ->
                    // [T-android-opencode-subtask-session] Regenerate is this
                    // chat's traffic: tagged with the chat's id so an OpenCode
                    // Go title model gets `x-opencode-session`.
                    val provider = com.openminis.app.ui.chat.TitleCandidates.providerFor(
                        providerRepository, context, entry, sessionId = id,
                    ) ?: return@walk null
                    AppLogger.info(
                        "TitleGen",
                        "dispatch origin=$origin session=${id.take(8)} model=${entry.model.id}",
                    )
                    // T334: reasoning models burn the whole budget on hidden
                    // thinking before emitting content; give them room.
                    val titleMaxTokens = if (entry.model.supportsReasoning == true) 2048 else 100
                    // [T-android-titlegen-reasoning] thinking OFF, matching iOS
                    // callSubModelForTitle and the auto-title path; the T334
                    // budget bump stays as a net where OFF is a no-op (Qwen3).
                    val response = provider.sendMessage(
                        messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                        // [T-android-titlegen-systemprompt-unify] Shared with the
                        // auto path; passed bare (the OAuth Claude Code prefix is
                        // handled at the provider layer).
                        systemPrompt = com.openminis.app.ui.chat.TITLE_GEN_SYSTEM_PROMPT,
                        maxTokens = titleMaxTokens,
                        // [T-android-titlegen-temperature] null so the field is
                        // omitted — gpt-5.x 400s on anything but 1.
                        temperature = null,
                        thinkingLevel = ThinkingLevel.OFF,
                    )
                    val (title, category) = parseTitleResponse(response.text)
                    if (title.isEmpty()) {
                        // T334: an empty result is a failure of THIS candidate
                        // (often budget exhaustion on a reasoning model) — log
                        // why and let the walk try the next one.
                        Log.w(
                            TAG,
                            "Title regen empty from ${entry.model.displayName}: " +
                                "stopReason=${response.stopReason} textLen=${response.text.length} " +
                                "maxTokens=$titleMaxTokens supportsReasoning=${entry.model.supportsReasoning}",
                        )
                        return@walk null
                    }
                    chatRepository.updateSessionTitleAndCategory(id, title, category)
                    AppLogger.info(
                        "TitleGen",
                        "outcome=set origin=$origin session=${id.take(8)} " +
                            "model=${entry.model.id} elapsedMs=${System.currentTimeMillis() - startedAt}",
                    )
                    true
                }
                if (written == true) return true
            AppLogger.warning(
                "TitleGen",
                "outcome=no-title origin=$origin session=${id.take(8)} " +
                    "reason=all-candidates-exhausted " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        } catch (e: Exception) {
            AppLogger.warning(
                "TitleGen",
                "outcome=exception origin=$origin session=${id.take(8)} " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        }
        // Every failure exit lands here. Mirrors iOS applyFallbackTitle: the
        // session must never be left permanently untitled just because the LLM
        // was unreachable.
        return applyFallbackTitle(id, firstUserRaw, origin)
    }

    /**
     * [GH#210] Write a title derived from the first user message.
     *
     * Mirrors iOS `applyFallbackTitle` — including its re-read of the session
     * immediately before writing. That re-check is the guard that keeps this
     * from clobbering a title the user typed (or a concurrent attempt set)
     * while the LLM call was in flight, which can be tens of seconds.
     */
    private suspend fun applyFallbackTitle(id: String, firstUserRaw: String?, origin: String): Boolean {
        val current = chatRepository.getSession(id)?.title?.trim()
        if (!current.isNullOrEmpty() && current != NEW_CHAT_TITLE) {
            AppLogger.info(
                "TitleGen",
                "outcome=fallback-skipped origin=$origin session=${id.take(8)} reason=already-titled",
            )
            return false
        }
        val cleaned = fallbackTitleFrom(firstUserRaw)
        if (cleaned == null) {
            AppLogger.warning(
                "TitleGen",
                "outcome=fallback-unavailable origin=$origin session=${id.take(8)} " +
                    "reason=first-user-message-empty-after-cleanup",
            )
            return false
        }
        chatRepository.updateSessionTitle(id, cleaned)
        // Length only — never the prompt text itself.
        AppLogger.info(
            "TitleGen",
            "outcome=fallback origin=$origin session=${id.take(8)} titleLen=${cleaned.length}",
        )
        return true
    }

    /**
     * Strip the composer's `<user-attached-files>` block, collapse whitespace
     * and truncate to 30 chars. Same shape as iOS `fallbackTitle(fromFirst‑
     * UserMessage:)` and ChatViewModel.applyFallbackTitleFromFirstMessage, so
     * a title recovered here is indistinguishable from one written by the auto
     * path. Returns null when nothing usable remains.
     */
    private fun fallbackTitleFrom(raw: String?): String? {
        var text = raw ?: return null
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
            } else {
                text.substring(0, startIdx)
            }
        }
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
    }

    private fun extractText(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") != "text") return@mapNotNull null
                val value = obj.optString("value")
                // [T-android-retry-attachment-loss] The user-message
                // <user-attached-files> XML is now persisted as a text part so
                // the model keeps file paths across retry/reload. Drop it from
                // session-list previews + search snippets so the inventory XML
                // never surfaces as visible session content (iOS strips it the
                // same way in ChatStore.toChatMessage).
                if (value.contains("<user-attached-files>")) {
                    val start = value.indexOf("<user-attached-files>")
                    val endTag = "</user-attached-files>"
                    val end = value.indexOf(endTag, start)
                    val cleaned = if (end >= 0) {
                        value.substring(0, start) + value.substring(end + endTag.length)
                    } else {
                        value.substring(0, start)
                    }.trim()
                    cleaned.ifEmpty { null }
                } else {
                    value
                }
            }.joinToString("\n")
        } catch (_: Exception) {
            partsJson
        }
    }

    private fun parseTitleResponse(text: String): Pair<String, String?> {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        try {
            val json = JSONObject(cleaned)
            val title = json.optString("title", "").trim()
            val category = json.optString("category", "").trim().ifEmpty { null }
            if (title.isNotEmpty()) return title to category
        } catch (_: Exception) {}
        val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        if (titleMatch != null) {
            return titleMatch.groupValues[1].trim() to catMatch?.groupValues?.getOrNull(1)?.trim()
        }
        val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
        return firstLine.take(50) to null
    }

    fun duplicateSession(id: String) {
        viewModelScope.launch {
            val session = chatRepository.getSession(id) ?: return@launch
            val messages = chatRepository.loadMessages(id)
            val newSession = chatRepository.createSession(
                modelId = session.modelId,
                title = "${session.title ?: "Chat"} (Copy)",
            )
            for (msg in messages) {
                chatRepository.appendMessage(
                    sessionId = newSession.id,
                    role = msg.role,
                    partsJson = msg.partsJson,
                    tokenUsage = msg.tokenUsage,
                    reasoningContent = msg.reasoningContent,
                )
            }
        }
    }

    /**
     * Create a draft session ID for navigation. The actual DB record is created
     * only when the user sends the first message (deferred creation, matching iOS).
     */
    /**
     * @param groupId MODEL group (fallback/load-balancing) — long-press FAB.
     * @param folderId session GROUP (folder) — "New Chat in Group" on the
     *   folder card's menu. Encoded in the draft id like the model group;
     *   ChatViewModel files the session into the folder at draft promotion
     *   (the folder_id row can only be written once the session exists —
     *   iOS defers the same way via pendingFolderDraft).
     */
    fun createNewSession(groupId: String? = null, folderId: String? = null): String? {
        if (providerRepository.allVisibleEntries().isEmpty()) return null
        var id = "__new__${java.util.UUID.randomUUID()}"
        if (groupId != null) id += "__grp__$groupId"
        if (folderId != null) id += "__fld__$folderId"
        return id
    }

    /**
     * [T-android-newchat-list-autoscroll] Emit [newTopSessionEvent] when a
     * never-before-seen session id appears at index 0 (the newest session,
     * since the list is updated_at DESC). The first emission only seeds the
     * baseline so existing sessions don't trigger a scroll on launch. Reorders
     * of existing sessions keep their ids (already in [knownSessionIds]) so
     * they never fire. Runs on the collector coroutine; no thread switch.
     */
    private fun detectNewTopSession(sessions: List<ChatSessionEntity>) {
        val topId = sessions.firstOrNull()?.id
        if (!newTopBaselineSeeded) {
            knownSessionIds = sessions.mapTo(HashSet()) { it.id }
            newTopBaselineSeeded = true
            return
        }
        val isNewTop = topId != null && topId !in knownSessionIds
        knownSessionIds = knownSessionIds + sessions.map { it.id }
        if (isNewTop) newTopSessionEvent.tryEmit(Unit)
    }

    fun hasProviders(): Boolean = providerRepository.instances.isNotEmpty()
}
