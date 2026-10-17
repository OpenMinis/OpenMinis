package com.openminis.app.ui.sessions

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.SessionForkManager
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.SessionWorkspace
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

    /**
     * [T-android-search-jump] Session id → id of the FIRST message whose
     * content matched the query (the same row `searchSnippets` quotes).
     * Populated/cleared together with the snippets; absent entry = title-only
     * match, so the tap opens the session at the tail with no jump.
     *
     * SessionListScreen stashes this into DeepLinkCoordinator right before
     * opening the session; ChatScreen pages older history until the row is
     * loaded, scrolls to it and pulses a highlight. The map is the ONLY
     * bridge — the anchor never rides inside ChatSessionEntity (which the
     * list also renders for non-search contexts).
     */
    val searchAnchors = MutableStateFlow<Map<String, String>>(emptyMap())

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
     * Which groups are collapsed. Never persisted to the DB, but mirrored to
     * SharedPreferences like iOS mirrors it to UserDefaults — without this the
     * accordion's "only one group open" state resets to ALL-EXPANDED on every
     * cold start, which is exactly the wall of open groups the accordion
     * exists to prevent. (getStringSet's return value must be copied, never
     * mutated in place.)
     */
    /**
     * [T-android-sessionlist-prefs-io] Lazy + first access on [Dispatchers.IO]:
     * the FIRST SharedPreferences read loads the XML synchronously on the
     * calling thread, and this VM is constructed on the main thread inside a
     * Compose frame (viewModel(factory=…)). On a device whose storage layer
     * stalls (crash-loop signature, restartCount in the dozens) that load can
     * hang the frame for minutes — measured in the field as a 119s main-thread
     * stall inside ViewModelProvider.create before observeSessions ever ran,
     * which left isInitialLoadComplete false forever and the list pane black.
     * `lazy` keeps construction off the disk entirely; [loadCollapsedFolderIds]
     * performs the first touch on IO, after which every main-thread access
     * returns the cached instance.
     */
    private val uiPrefs by lazy { context.getSharedPreferences("session_list_ui", Context.MODE_PRIVATE) }
    val collapsedFolderIds = MutableStateFlow<Set<String>>(emptySet())

    private fun loadCollapsedFolderIds() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = uiPrefs.getStringSet("collapsedFolderIds", emptySet())?.toSet() ?: emptySet()
            collapsedFolderIds.value = ids
        }
    }

    private fun setCollapsedFolders(ids: Set<String>) {
        collapsedFolderIds.value = ids
        uiPrefs.edit().putStringSet("collapsedFolderIds", ids).apply()
    }

    /**
     * [T-android-group-accordion] Expand exactly [folderId] and collapse every
     * other group.
     *
     * Groups are an accordion (iOS `ContentView.toggleFolder`): at most one is
     * open at a time. `collapsedFolderIds` stores the INVERSE — the ids that
     * are shut — so "expand only this" means "collapse all, minus this one".
     * Removing a single id from the set, which the callers used to do, expands
     * the target while leaving whatever else was already open still open, and
     * that is how several groups ended up unfolded at once.
     *
     * The mini-bar depends on this invariant: it resolves the floating header
     * with `firstOrNull { !isCollapsed }`, which only names the right group
     * while there is just one.
     */
    private fun expandOnly(folderId: String) {
        setCollapsedFolders(folders.value.map { it.id }.toSet() - folderId)
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
        // [T-android-sessionlist-prefs-io] Kick the collapsed-folder prefs load
        // on IO. Deliberately OUTSIDE the safe-mode await below — folder
        // collapse state has nothing to do with the crash gate and must not
        // wait for the user to dismiss the share dialog.
        loadCollapsedFolderIds()
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
            chatRepository.observeSessions().collect {
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
                        val results = chatRepository.searchSessions(q)
                        searchResults.value = results
                        // Compute per-session content snippets off the main
                        // thread. Sessions whose title already matches don't
                        // need a snippet — we only walk messages when the
                        // title doesn't contain the query.
                        val snips = withContext(Dispatchers.IO) {
                            buildContentHits(results, q)
                        }
                        searchSnippets.value = snips.snippets
                        searchAnchors.value = snips.anchors
                    } else {
                        searchResults.value = emptyList()
                        searchSnippets.value = emptyMap()
                        searchAnchors.value = emptyMap()
                    }
                    isSearching.value = false
                }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            ChatViewModelStore.release(id)
            com.openminis.app.service.SessionBadgeStore.clear(id)
        }
    }

    // ─── Session group actions ─────────────────────────────────────────────
    // [T-android-session-grouping]

    /**
     * True only for a session filed into a group that EXISTS locally. A dangling
     * folder_id is displayed as ungrouped, so treating it as filed would offer
     * "暂不分组" for a group the user cannot see — and label the action 更换 when
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
            var apiKey = providerRepository.usableApiKey(instance) ?: continue
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
                ProviderFactory.create(instance, apiKey, entry.model, context)
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
                    temperature = entry.overrides.temperature,
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
     * Accordion toggle: expanding one group collapses the rest, so the list
     * never turns into a wall of simultaneously-open groups.
     */
    fun toggleFolderCollapsed(folderId: String) {
        val collapsed = collapsedFolderIds.value
        setCollapsedFolders(
            if (folderId in collapsed) {
                folders.value.map { it.id }.toSet() - folderId
            } else {
                collapsed + folderId
            },
        )
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
                chatRepository.deleteSession(id)
                ChatViewModelStore.release(id)
                com.openminis.app.service.SessionBadgeStore.clear(id)
            }
            chatRepository.dissolveFolder(folderId)
            SessionWorkspace.deleteProject(context.filesDir, folderId)
            // Just drop the dead id — do NOT use expandOnly here. The folder no
            // longer exists, so there is nothing to expand; re-deriving the set
            // from the surviving folders would leave one of them open purely
            // because another was deleted.
            setCollapsedFolders(collapsedFolderIds.value - folderId)
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
            setCollapsedFolders(collapsedFolderIds.value - folderId)
            AppLogger.info(TAG, "[Group] dissolved ${folderId.take(8)}, freed ${freed.size} session(s)")
        }
    }

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
                val firstUsers = chatRepository.loadMessageHeadsByRole(id, "user", 800, 2)
                val lastUsers = chatRepository.loadMessageHeadsByRole(id, "user", 800, 2, newest = true)
                val firstAssistants = chatRepository.loadMessageHeadsByRole(id, "assistant", 800, 2)
                val lastAssistants = chatRepository.loadMessageHeadsByRole(id, "assistant", 800, 2, newest = true)
                if (firstUsers.isEmpty()) return false
                fun head(row: com.openminis.app.data.db.MessageHeadRow): String =
                    extractText(row.headText.orEmpty())
                val firstUser = firstUsers.firstOrNull { head(it).isNotBlank() } ?: return false
                firstUserRaw = head(firstUser)
                val userText = firstUserRaw?.take(200) ?: return false
                val firstAssistantText = firstAssistants.firstOrNull { head(it).isNotBlank() }
                    ?.let { head(it).take(200) } ?: ""
                val hasMultipleUserTurns = lastUsers.any { it.id != firstUser.id }
                val lastUserText = if (hasMultipleUserTurns) {
                    lastUsers.firstOrNull { it.id != firstUser.id }?.let { head(it).take(200) } ?: ""
                } else ""
                val lastAssistantText = if (hasMultipleUserTurns) {
                    lastAssistants.firstOrNull { head(it).isNotBlank() }?.let { head(it).take(200) } ?: ""
                } else ""

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

                // Build candidate list: session's model first, then all others.
                // T334: filter out non-text-output models (tts/voiceclone/voicedesign/image/video/audio-only)
                // and models whose id obviously names a non-chat capability — they either reject the
                // chat/completions schema with HTTP 400 or stream nothing useful, masking the real result
                // with a misleading "Param Incorrect" tail error.
                val allEntries = providerRepository.allVisibleEntries()
                val titleEligible = allEntries.filter { entry ->
                    val outs = entry.model.outputModalities
                    val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
                    val idLower = entry.model.id.lowercase()
                    val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
                        .any { idLower.contains(it) }
                    outputsText && !nonChatId
                }
                // [T-android-regenerate-title-submodel] Priority: dedicated
                // title sub-model (defaultSubGroupId's first enabled member) >
                // session's bound primary model > every other eligible model.
                // Aligns the manual Regenerate path with the auto-title path
                // (ChatViewModel.resolveTitleProvider) and iOS resolveSubEntry —
                // previously the sub-model was ignored here, so users who
                // configured a cheap/fast title model still paid for the primary.
                // The sub-entry must pass the same T334 modality filter; when no
                // sub-group is configured / all members disabled it's null and we
                // fall through to the existing primary-first ordering.
                val subEntry = providerRepository.resolveTitleSubEntry()
                    ?.takeIf { sub -> titleEligible.any { it == sub } }
                val primary = titleEligible.firstOrNull { it.model.id == session.modelId }
                    ?.takeIf { it != subEntry }
                val candidates = (listOfNotNull(subEntry, primary) +
                    titleEligible.filter { it != subEntry && it != primary })

                var lastError: Exception? = null
                for (entry in candidates) {
                    val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
                    // [T-android-keyless-provider-selection] usableApiKey —
                    // see the note in runGroupSuggestion above.
                    var apiKey = providerRepository.usableApiKey(instance) ?: continue

                    // Refresh OAuth token if needed
                    if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
                        try {
                            val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                            val freshToken = manager?.validAccessToken()
                            if (freshToken != null && freshToken != apiKey) {
                                providerRepository.saveApiKey(instance.id, freshToken)
                                apiKey = freshToken
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "OAuth refresh failed: ${e.message}")
                        }
                    }

                    val provider = try {
                        ProviderFactory.create(instance, apiKey, entry.model, context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Provider creation failed for ${entry.model.displayName}: ${e.message}")
                        continue
                    }

                    AppLogger.info(
                        "TitleGen",
                        "dispatch origin=$origin session=${id.take(8)} model=${entry.model.id}",
                    )

                    try {
                        // T334: reasoning models burn the entire token budget on hidden thinking
                        // before emitting any content. With maxTokens=100 every reasoning candidate
                        // returned `finish_reason=length` with empty text, then the loop silently
                        // moved on. Give reasoning models a real budget (1024) so they can finish
                        // thinking and still emit the JSON title.
                        val titleMaxTokens = if (entry.model.supportsReasoning == true) 2048 else 100
                        // [T-android-titlegen-reasoning] Explicitly disable
                        // thinking (thinkingLevel = OFF), matching iOS
                        // callSubModelForTitle and the auto-title path. The
                        // provider's injectThinkingParams honors OFF (e.g.
                        // DeepSeek V4 → {"thinking":{"type":"disabled"}}), so a
                        // reasoning model doesn't spend the budget on hidden
                        // thinking; the T334 maxTokens bump above stays as a
                        // safety net for models where OFF is a no-op (Qwen3).
                        val response = provider.sendMessage(
                            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                            // [T-android-titlegen-systemprompt-unify] Shared with
                            // the auto path via TITLE_GEN_SYSTEM_PROMPT (iOS-aligned
                            // wording). Passed bare — AnthropicProvider handles the
                            // OAuth Claude Code prefix at the provider layer.
                            systemPrompt = com.openminis.app.ui.chat.TITLE_GEN_SYSTEM_PROMPT,
                            maxTokens = titleMaxTokens,
                            temperature = entry.overrides.temperature,
                            thinkingLevel = ThinkingLevel.OFF,
                        )
                        val (title, category) = parseTitleResponse(response.text)
                        if (title.isNotEmpty()) {
                            chatRepository.updateSessionTitleAndCategory(id, title, category)
                            AppLogger.info(
                                "TitleGen",
                                "outcome=set origin=$origin session=${id.take(8)} " +
                                    "model=${entry.model.id} elapsedMs=${System.currentTimeMillis() - startedAt}",
                            )
                            return true
                        }
                        // T334: previously this empty-result path was silent — only the *last*
                        // failing candidate's exception got reported, masking budget exhaustion
                        // on reasoning models. Surface it explicitly so logs show the real cause.
                        Log.w(
                            TAG,
                            "Title regen empty from ${entry.model.displayName}: " +
                                "stopReason=${response.stopReason} textLen=${response.text.length} " +
                                "maxTokens=$titleMaxTokens supportsReasoning=${entry.model.supportsReasoning}",
                        )
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "Title regen via ${entry.model.displayName} failed: ${e.message}")
                        // Continue to next candidate on rate-limit / provider error
                        continue
                    }
                }
            AppLogger.warning(
                "TitleGen",
                "outcome=no-title origin=$origin session=${id.take(8)} " +
                    "reason=all-candidates-exhausted lastError=${lastError?.javaClass?.simpleName} " +
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
        viewModelScope.launch(Dispatchers.IO) {
            SessionForkManager(
                chatRepository = chatRepository,
                filesDir = context.filesDir,
            ).duplicateSession(id)
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
    suspend fun createNewWorkspaceSession(groupId: String? = null): String? {
        val folderId = resolveWorkspaceFolderId()
        expandOnly(folderId)
        return createNewSession(groupId = groupId, folderId = folderId)
    }

    /**
     * Create a workspace folder from the main FAB's dialog.
     *
     * Starts expanded so the user sees the folder they just made — with no
     * sessions in it yet, an accordion that opened collapsed would look like
     * the tap did nothing.
     */
    fun createWorkspaceFolder(name: String, description: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // createFolder already provisions the project dirs on disk. The
            // owner map is keyed by SESSION id and this folder has no sessions
            // yet, so there is nothing to remember here — it fills in as
            // sessions are filed, via warmupWorkspaceOwners on next launch.
            val folder = chatRepository.createFolder(trimmed, description)
            expandOnly(folder.id)
            AppLogger.info(TAG, "[Group] created folder=${folder.id} name=$trimmed")
        }
    }

    private suspend fun resolveWorkspaceFolderId(): String {
        val all = folders.value
        val collapsed = collapsedFolderIds.value
        all.firstOrNull { it.id !in collapsed }?.id?.let { return it }
        all.firstOrNull { it.id == FolderEntity.DEFAULT_ID }?.id?.let { return it }
        all.firstOrNull()?.id?.let { return it }
        return chatRepository.ensureDefaultWorkspace(
            context.getString(R.string.group_default_workspace),
        ).id
    }

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

    /**
     * [T-android-search-jump] Snippet + message anchor for every session
     * whose title does NOT contain [query] (case-insensitive): scan its
     * messages to find the first hit in extracted text content and build a
     * ~100-char snippet around it. Sessions with no content hit are omitted
     * — the row falls back to its existing lastMessage preview.
     *
     * The matched row's id rides along as the anchor: `firstSnippetPerSession`
     * already selects it, so the anchor costs nothing here and saves ChatScreen
     * a re-query when the tap lands.
     *
     * Runs on Dispatchers.IO; caller is responsible for thread switching.
     */
    internal data class ContentHits(
        val snippets: Map<String, String>,
        val anchors: Map<String, String>,
    )

    private suspend fun buildContentHits(
        sessions: List<ChatSessionEntity>,
        query: String,
    ): ContentHits {
        if (query.isBlank() || sessions.isEmpty()) return ContentHits(emptyMap(), emptyMap())
        val q = query.lowercase()
        val needingSnippet = sessions.filterNot { it.title.orEmpty().lowercase().contains(q) }
        if (needingSnippet.isEmpty()) return ContentHits(emptyMap(), emptyMap())
        val rows = chatRepository.searchDao().firstSnippetPerSession(
            sessionIds = needingSnippet.map { it.id },
            query = query,
            radius = 50,
            windowChars = query.length + 100,
        )
        val snippets = HashMap<String, String>(rows.size)
        val anchors = HashMap<String, String>(rows.size)
        for (row in rows) {
            val body = row.headText.replace('\n', ' ').replace('\r', ' ')
            if (body.isBlank()) continue
            snippets[row.sessionId] = "…$body…"
            if (row.id.isNotBlank()) anchors[row.sessionId] = row.id
        }
        return ContentHits(snippets, anchors)
    }
}
