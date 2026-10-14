package com.openminis.app.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds pending deep-link side-effects that outlive a single navigation event.
 * Mirrors iOS DeepLinkCoordinator.
 */
object DeepLinkCoordinator {

    data class EnvVarCreate(val key: String, val value: String, val note: String)

    private val _pendingEnvVarCreate = MutableStateFlow<EnvVarCreate?>(null)
    val pendingEnvVarCreate: StateFlow<EnvVarCreate?> = _pendingEnvVarCreate.asStateFlow()

    fun setPendingEnvVarCreate(key: String, value: String, note: String = "") {
        _pendingEnvVarCreate.value = EnvVarCreate(key, value, note)
    }

    fun consumePendingEnvVarCreate(): EnvVarCreate? {
        val current = _pendingEnvVarCreate.value
        _pendingEnvVarCreate.value = null
        return current
    }

    /**
     * Optional `?tab=…` hint from `minis://settings/logs?tab=config-audit`.
     * The Logs screen reads this on appear to land on the right
     * segmented-control tab. Cleared by the screen after consumption.
     * Mirrors iOS DeepLinkCoordinator.pendingLogsTab.
     */
    private val _pendingLogsTab = MutableStateFlow<String?>(null)
    val pendingLogsTab: StateFlow<String?> = _pendingLogsTab.asStateFlow()

    fun setPendingLogsTab(tab: String?) { _pendingLogsTab.value = tab }
    fun consumePendingLogsTab(): String? {
        val current = _pendingLogsTab.value
        _pendingLogsTab.value = null
        return current
    }

    /**
     * Pending pinned-shortcut HTML preview: filesystem path + cached title.
     * MainActivity sets this on `minis://preview/html` deep link; ChatScreen
     * reads it on first composition and routes into WebPreviewFullscreen.
     */
    data class HtmlPreview(val sessionId: String, val resourcePath: String, val title: String)

    private val _pendingHtmlPreview = MutableStateFlow<HtmlPreview?>(null)
    val pendingHtmlPreview: StateFlow<HtmlPreview?> = _pendingHtmlPreview.asStateFlow()

    fun setPendingHtmlPreview(sessionId: String, resourcePath: String, title: String) {
        _pendingHtmlPreview.value = HtmlPreview(sessionId, resourcePath, title)
    }

    fun consumePendingHtmlPreview(): HtmlPreview? {
        val current = _pendingHtmlPreview.value
        _pendingHtmlPreview.value = null
        return current
    }

    /**
     * App-icon quick-action that a freshly-opened ChatScreen should auto-
     * trigger on first compose. Mirrors iOS `pendingChatAction` on
     * AIChatViewModel. Set by [com.openminis.app.MainActivity] /
     * [com.openminis.app.ui.navigation.AppNavigation] when the launch
     * intent carries `minis://action/voice_chat` or
     * `minis://action/camera_chat`; consumed exactly once by ChatScreen
     * so re-entering the same chat later doesn't fire the action again.
     */
    enum class ChatAction { START_VOICE, OPEN_CAMERA }

    private val _pendingChatAction = MutableStateFlow<ChatAction?>(null)
    val pendingChatAction: StateFlow<ChatAction?> = _pendingChatAction.asStateFlow()

    fun setPendingChatAction(action: ChatAction) {
        _pendingChatAction.value = action
    }

    fun consumePendingChatAction(): ChatAction? {
        val current = _pendingChatAction.value
        _pendingChatAction.value = null
        return current
    }

    /**
     * [T-android-search-jump] Search-result → matched-message jump.
     *
     * SessionListScreen stashes the message id the search actually matched
     * (from `searchAnchors`) right before it opens the session; ChatScreen
     * consumes it, pages older history until the row is loaded, scrolls to
     * it and pulses a highlight. Without the anchor the tap just opens the
     * session at the tail — the user then re-searches by eye, which is the
     * exact friction this removes.
     *
     * Consume is SESSION-GUARDED, unlike the one-shots above: the pane
     * navigator can keep the previous chat's composition alive for a frame
     * while it swaps to the tapped session, and an unguarded consume would
     * let that stale ChatScreen steal the pending focus (its effect is also
     * keyed on [searchFocusRevision], so it re-runs on the same tick). A
     * mismatched session returns null WITHOUT clearing — the real target's
     * ChatScreen still finds the value waiting.
     *
     * The revision counter exists for the same-session case: tapping a
     * search result for the chat that is ALREADY in the detail pane changes
     * no contentKey, so no recomposition happens and a plain
     * LaunchedEffect(sessionId) would never re-run. ChatScreen keys its
     * effect on (sessionId, revision) so a re-tap still jumps.
     */
    data class SearchFocus(val sessionId: String, val messageId: String)

    private val _pendingSearchFocus = MutableStateFlow<SearchFocus?>(null)
    val pendingSearchFocus: StateFlow<SearchFocus?> = _pendingSearchFocus.asStateFlow()

    private val _searchFocusRevision = MutableStateFlow(0)
    val searchFocusRevision: StateFlow<Int> = _searchFocusRevision.asStateFlow()

    /**
     * [T-dev-suite] Pending dev-suite install triggered by the agent-emitted
     * deep link `minis://devsuite/install/<bundleId>`. PluginMarketScreen
     * consumes it on appear and auto-starts the bundle assembly.
     */
    private val _pendingDevSuiteInstall = MutableStateFlow<String?>(null)
    val pendingDevSuiteInstall: StateFlow<String?> = _pendingDevSuiteInstall.asStateFlow()

    fun setPendingDevSuiteInstall(bundleId: String) {
        _pendingDevSuiteInstall.value = bundleId
    }

    fun consumePendingDevSuiteInstall(): String? {
        val current = _pendingDevSuiteInstall.value
        _pendingDevSuiteInstall.value = null
        return current
    }

    fun setPendingSearchFocus(sessionId: String, messageId: String) {
        _pendingSearchFocus.value = SearchFocus(sessionId, messageId)
        _searchFocusRevision.value += 1
    }

    /** Only a consumer for the SAME session may take (and clear) the value. */
    fun consumePendingSearchFocus(forSessionId: String): SearchFocus? {
        val current = _pendingSearchFocus.value ?: return null
        if (current.sessionId != forSessionId) return null
        _pendingSearchFocus.value = null
        return current
    }
}
