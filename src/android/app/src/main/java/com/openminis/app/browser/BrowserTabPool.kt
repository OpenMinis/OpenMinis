package com.openminis.app.browser

import android.content.Context
import android.os.Message
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import com.openminis.app.R
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages up to 3 browser tabs for the agent, mirroring iOS BrowserTabPool.
 * All tabs share the same cookie store by default on Android.
 */
class BrowserTabPool(private val context: Context) : BrowserTabPoolRegistry.RegistryPool {

    companion object {
        private const val TAG = "BrowserTabPool"
        private const val MAX_TABS = 3

        /** [T-android-browser-tab-ownership] Tabs one agent may hold at once. */
        const val AGENT_TAB_QUOTA = 2

        /**
         * [T-android-browser-tab-ownership] Absolute pool ceiling while agents
         * run.
         *
         * Android has no cross-pool global cap (iOS has BrowserTabPoolRegistry
         * at 8), and a pool is created per chat view model, so this ceiling is
         * per chat rather than per device. Six is chosen to stay close to the
         * old three in the common single-agent case (3 + 2 = 5) while bounding
         * the fan-out case, precisely because WebView memory is the real
         * constraint here.
         */
        const val MAX_TABS_WITH_AGENTS = 6
        private const val IDLE_CHECK_INTERVAL_MS = 60_000L  // 60 seconds
        /** Default idle timeout — matches iOS BrowserTabPool.idleTimeout (15 minutes). */
        const val DEFAULT_IDLE_TIMEOUT_MINUTES = 15
        /** SharedPreferences key for the user-configurable idle timeout. */
        const val PREF_IDLE_TIMEOUT_MINUTES = "idle_timeout_minutes"
        /** Minimum permitted idle timeout (minutes). Protects against runaway eviction. */
        const val MIN_IDLE_TIMEOUT_MINUTES = 1
        /** Maximum permitted idle timeout (minutes). */
        const val MAX_IDLE_TIMEOUT_MINUTES = 240

        /** Global custom viewport width SharedPreferences key (0 = use UA default). */
        const val PREF_GLOBAL_VIEWPORT_WIDTH = "browser_custom_viewport_width"
        /** Global custom viewport height SharedPreferences key (0 = use UA default). */
        const val PREF_GLOBAL_VIEWPORT_HEIGHT = "browser_custom_viewport_height"

        /**
         * [T-browser-use-per-tab-serial-android] Max time a browser_use call
         * waits to acquire the per-tab-id serial lock before giving up. This is
         * ONLY the lock-acquisition wait (waiting for another tool's operation
         * on the SAME explicit tab id to finish). (GH#245) It really is only
         * that now: it used to wrap the whole locked action too, so a frozen
         * page was reported as "tab busy". The operation has its own ceiling,
         * [BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS].
         */
        private const val TAB_SERIAL_WAIT_TIMEOUT_MS = BrowserActionGuard.TAB_SERIAL_WAIT_TIMEOUT_MS

        /**
         * [T-android-browser-download-ux] Files-app-style middle truncation
         * (iOS v3 String.middleTruncated): keeps the head and the tail so the
         * extension stays visible. Used for the human-facing chat notices;
         * panel rows truncate adaptively in Compose.
         */
        fun middleTruncated(name: String, head: Int = 22, tail: Int = 10): String {
            if (name.length <= head + tail + 1) return name
            return name.take(head) + "…" + name.takeLast(tail)
        }

        /**
         * [T-browser-implicit-tab-inuse-until-load-android] Grace window a tab
         * stays `inUse` after an implicit-tab (tab_id-less) action completes.
         * `navigate` already suspends until onPageFinished / its 30s timeout, so
         * inUse is held through the page load itself; this grace then keeps it
         * held a further 15s, re-armed on every subsequent action to that tab.
         * During the grace window acquireTab(null) sees inUse=true and fans out
         * to a fresh tab (createTab) instead of trampling the busy one — so N
         * back-to-back tab-less navigates open N independent tabs rather than
         * all overwriting tab 0. A same-task follow-up chain re-arms the timer
         * each call, so the tab survives between consecutive operations.
         */
        private const val IMPLICIT_TAB_GRACE_MS = 15_000L

        /**
         * [T-browser-implicit-tab-inuse-until-load-android] When all tabs are
         * busy and the pool is at MAX_TABS, an implicit-tab acquire waits up to
         * this long (polling) for a tab to free instead of trampling a busy one.
         * Bounded so a stuck tab can't hang the agent forever — after the window
         * it falls back to the least-recently-active tab.
         */
        private const val IMPLICIT_TAB_WAIT_MS = 20_000L
        private const val IMPLICIT_TAB_WAIT_POLL_MS = 250L
    }

    /**
     * [T-browser-use-per-tab-serial-android] Per-tab-id serial locks. Parallel
     * tool execution can fire two browser_use calls at the same explicit tab id;
     * letting both drive the one WebView corrupts state, so calls targeting the
     * SAME existing tab id run one-at-a-time through that tab's Mutex. Different
     * tab ids keep their own Mutex and still run concurrently. Calls with NO
     * tab_id are deliberately NOT funneled through a shared lock — they fan out
     * to separate (free / freshly-created) tabs in [acquireTab] so two
     * tab-less navigates run in parallel instead of deadlocking on one tab.
     */
    private val tabLocks = ConcurrentHashMap<Int, Mutex>()

    private fun lockForTab(id: Int): Mutex = tabLocks.getOrPut(id) { Mutex() }

    /**
     * (GH#245) new tab id → note for the model that the tab it named was
     * rebuilt. Written by [acquireTab] (Main), consumed once by
     * [runAcquiredAction] on the caller's dispatcher, hence concurrent.
     */
    private val rebuildNotices = ConcurrentHashMap<Int, String>()

    data class Tab(
        val id: Int,
        val manager: BrowserUseManager,
        var inUse: Boolean = false,
        var lastActivityDate: Date = Date(),
        /**
         * Flag set by `createTab` when a tab is created with no initial URL.
         * `acquireTab` / `newTab` consume it and call `manager.loadBlankPage()`
         * from their suspend contexts so `window.innerWidth/Height` reflects
         * the session viewport instead of the `about:blank` 980px fallback.
         */
        var needsInitialBlankPage: Boolean = false,
        /**
         * [T-browser-implicit-tab-inuse-until-load-android] Pending job that
         * clears [inUse] after the post-action grace window. Cancelled and
         * re-armed by [armImplicitGraceRelease] on every implicit-tab action so
         * a same-task follow-up keeps the tab held; fires once the tab has been
         * idle for [IMPLICIT_TAB_GRACE_MS]. Excluded from equals/hashCode/copy
         * since it's transient scheduling state, not tab identity.
         */
        var inUseGraceJob: Job? = null,
    )

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow(0)
    val selectedTabId: StateFlow<Int> = _selectedTabId.asStateFlow()

    /** Whether any tab is currently executing an agent action. */
    val isAgentBusy: Boolean get() = _tabs.value.any { it.inUse }

    /** Currently selected tab's manager, or the first tab's if none selected — mirrors iOS activeManager. */
    val activeManager: BrowserUseManager?
        get() {
            val tabs = _tabs.value
            if (tabs.isEmpty()) return null
            return tabs.firstOrNull { it.id == _selectedTabId.value }?.manager ?: tabs.first().manager
        }

    private val _userAgentProfile = MutableStateFlow(UserAgentProfile.MOBILE_CHROME)
    val currentUserAgentProfile: StateFlow<UserAgentProfile> = _userAgentProfile.asStateFlow()
    private var userAgentProfile: UserAgentProfile
        get() = _userAgentProfile.value
        set(value) { _userAgentProfile.value = value }
    private var customUserAgentString: String? = null

    private var sessionId: String? = null
    private val savedURLs = mutableMapOf<Int, String>()

    // ── Tab ownership  [T-android-browser-tab-ownership] ────────────────────
    //
    // One pool per chat, with ownership enforced INSIDE it. Deliberately not a
    // pool per agent: three agents would mean twelve WebViews, and Android's
    // WebView is heavier than iOS's — that is a memory-reclaim storm, not
    // isolation. Also deliberately not per-agent tab id remapping: two
    // numbering schemes make a human takeover and the logs unreadable. Tab ids
    // stay globally unique and the pool simply decides who may touch what.
    //
    // Ports iOS BrowserTabPool.swift (2026-09-04, 4487da4b1), added after three
    // research sub agents ping-ponged on tab 0: one agent's get_readable
    // returned another's page, "Maximum of 3 tabs" made them close each other's
    // work, and two sat ~3 minutes in the per-tab serial queue. Android has
    // concurrent sub agent fan-out now, so the same incident is reproducible
    // here — it just has not been reported yet.

    /** tab id → owning agent's session id. Absent = the chat's own tab. */
    private val tabOwner = mutableMapOf<Int, String>()

    /** Each agent's most recently used tab, for implicit targeting. */
    private val lastTabByOwner = mutableMapOf<String, Int>()

    /** Tabs one agent may hold at once. The chat itself keeps [MAX_TABS]. */
    private val agentTabQuota = AGENT_TAB_QUOTA

    /**
     * The chat that owns this pool, or a human (null) — full access.
     *
     * `owner == sessionId` counts as privileged: a parent chat driving its own
     * pool is not an agent competing for tabs.
     */
    private fun isPrivileged(owner: String?): Boolean =
        owner == null || owner == sessionId

    /** Agent owners with at least one tab right now. */
    private fun agentOwners(): Set<String> =
        tabOwner.values.toSet() - setOfNotNull(sessionId)

    /**
     * The pool ceiling as it stands: [MAX_TABS] alone, +[AGENT_TAB_QUOTA] per
     * active agent, hard-capped at [MAX_TABS_WITH_AGENTS].
     */
    fun effectiveMaxTabs(requestingOwner: String? = null): Int {
        val owners = agentOwners().toMutableSet()
        if (requestingOwner != null && !isPrivileged(requestingOwner)) owners.add(requestingOwner)
        return if (owners.isEmpty()) MAX_TABS
        else minOf(MAX_TABS + AGENT_TAB_QUOTA * owners.size, MAX_TABS_WITH_AGENTS)
    }

    /** True when [owner] may act on [tabId]. */
    internal fun mayUse(tabId: Int, owner: String?): Boolean {
        if (isPrivileged(owner)) return true
        return tabOwner[tabId] == owner
    }

    /** Tabs currently attributed to [owner], ascending. */
    internal fun tabIdsOwnedBy(owner: String): List<Int> =
        tabOwner.filterValues { it == owner }.keys.sorted()

    /**
     * The error an agent gets for touching someone else's tab.
     *
     * It names the tabs the agent DOES own: without them the model has no way
     * to correct itself and will simply retry the same rejected id.
     */
    private fun notYourTabError(tabId: Int, owner: String): BrowserActionResult {
        val mine = tabIdsOwnedBy(owner)
        val hint = if (mine.isEmpty()) "open one with action: new_tab"
        else "use your own tab id(s) ${mine.joinToString(", ")} or action: new_tab"
        return BrowserActionResult.error(
            "Tab $tabId belongs to another agent or to the main chat and cannot be used from here — " +
                "$hint. list_tabs shows only your tabs.",
        )
    }

    /**
     * The tab an agent's implicit (no tab_id) action should prefer: its own
     * most recently used one, when it still exists.
     */
    private fun preferredTab(owner: String?): Int? {
        val o = owner ?: return null
        val id = lastTabByOwner[o] ?: return null
        return if (_tabs.value.any { it.id == id }) id else null
    }

    /**
     * Close every tab an agent owns and forget its bookkeeping, so the slots
     * return to the chat and to other agents. Idempotent.
     */
    suspend fun releaseTabs(owner: String) {
        val mine = tabIdsOwnedBy(owner)
        for (id in mine) closeTab(id)
        lastTabByOwner.remove(owner)
        if (mine.isNotEmpty()) {
            Log.i(TAG, "[agent] released ${mine.size} tab(s) of owner ${owner.take(8)}")
        }
    }

    /**
     * Global custom viewport. `0` means "use the UA profile default".
     * Persisted across launches via SharedPreferences. Session-level overrides
     * ([sessionViewportWidth]/[sessionViewportHeight]) shadow this.
     * Mirrors iOS `BrowserTabPool.customViewportWidth/Height`.
     */
    private val _customViewportWidth = MutableStateFlow(0)
    val customViewportWidth: StateFlow<Int> = _customViewportWidth.asStateFlow()
    private val _customViewportHeight = MutableStateFlow(0)
    val customViewportHeight: StateFlow<Int> = _customViewportHeight.asStateFlow()

    /**
     * Per-session viewport override set via `set_viewport`. Both > 0 means
     * active; shadows global custom and UA profile default. Persisted in the
     * session's tab JSON file, not SharedPreferences — matches iOS
     * `BrowserTabPool.sessionViewportWidth/Height`.
     */
    private val _sessionViewportWidth = MutableStateFlow(0)
    val sessionViewportWidth: StateFlow<Int> = _sessionViewportWidth.asStateFlow()
    private val _sessionViewportHeight = MutableStateFlow(0)
    val sessionViewportHeight: StateFlow<Int> = _sessionViewportHeight.asStateFlow()

    private var nextTabId = 0

    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var evictionJob: Job? = null

    /**
     * Current idle-eviction timeout in milliseconds. Read from SharedPreferences
     * in `init`, updatable at runtime via [setIdleTimeoutMinutes] so settings
     * changes take effect without restarting the pool. Mirrors iOS
     * `BrowserTabPool.idleTimeout` (hardcoded 15 min on iOS; made configurable here).
     */
    @Volatile
    private var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MINUTES * 60_000L

    init {
        // Restore persisted User-Agent profile from SharedPreferences so pool-owned
        // WebViews start in the correct mode (Mobile vs Desktop) and
        // captureWebViewBitmap() uses the matching viewport.
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val profileName = prefs.getString("user_agent_profile", null)
        if (profileName != null) {
            runCatching { UserAgentProfile.valueOf(profileName) }.getOrNull()?.let {
                _userAgentProfile.value = it
            }
        }
        customUserAgentString = prefs.getString("custom_user_agent", null)?.ifEmpty { null }

        // Restore global custom viewport (0 = unset → fall back to UA profile).
        // Mirrors iOS `BrowserCustomViewport{Width,Height}` UserDefaults keys.
        _customViewportWidth.value = prefs.getInt(PREF_GLOBAL_VIEWPORT_WIDTH, 0).coerceAtLeast(0)
        _customViewportHeight.value = prefs.getInt(PREF_GLOBAL_VIEWPORT_HEIGHT, 0).coerceAtLeast(0)

        // Load user-configured idle timeout (default 15 min, matches iOS).
        val storedMinutes = prefs.getInt(PREF_IDLE_TIMEOUT_MINUTES, DEFAULT_IDLE_TIMEOUT_MINUTES)
        idleTimeoutMs = storedMinutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES) * 60_000L

        // [T-android-browser-global-tab-cap] Join the process-wide gate. Done
        // here rather than at each construction site because there are three
        // of them (MinisApp, ChatViewModel, SessionListScreen) and
        // `adoptBrowserTabPool` SHARES an existing pool rather than building
        // one — registering in the constructor is the only placement that
        // covers every pool exactly once. The registry holds this weakly, so
        // no unregister call site is required when a chat's ViewModel is
        // cleared; the pool simply stops being counted once collected.
        BrowserTabPoolRegistry.register(this)

        // Start idle tab eviction timer (60-second interval, matching iOS)
        evictionJob = evictionScope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                withContext(Dispatchers.Main) { evictIdleTabs() }
            }
        }
    }

    /**
     * Current idle timeout in minutes. Exposed for Settings UIs that want to
     * read the active value without parsing SharedPreferences themselves.
     */
    val idleTimeoutMinutes: Int
        get() = (idleTimeoutMs / 60_000L).toInt()

    /**
     * Update the idle-eviction timeout. Persists to SharedPreferences and
     * takes effect on the next eviction tick (within [IDLE_CHECK_INTERVAL_MS]).
     * Values outside [MIN_IDLE_TIMEOUT_MINUTES]..[MAX_IDLE_TIMEOUT_MINUTES] are clamped.
     */
    fun setIdleTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES)
        idleTimeoutMs = clamped * 60_000L
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_IDLE_TIMEOUT_MINUTES, clamped).apply()
        Log.i(TAG, "Idle timeout set to $clamped min")
    }

    // -- Session --

    fun setSession(sessionId: String) {
        this.sessionId = sessionId
        loadSavedState()
    }

    // -- Downloads --

    /** UI state for the browser sheet's download banner. progress < 0 = indeterminate. */
    data class DownloadUiState(val filename: String, val progress: Float)

    private val _activeDownload = MutableStateFlow<DownloadUiState?>(null)
    val activeDownload: StateFlow<DownloadUiState?> = _activeDownload.asStateFlow()

    /**
     * [T-android-browser-download-ux] Session-scoped download registry — the
     * Android port of iOS BrowserDownloadCenter (T-browser-download-ux v1-v3).
     * Replaces the single-slot banner state as the source of truth: every
     * download becomes an entry with live progress, a terminal state, a
     * cancel handle, and a `seen` flag driving the toolbar badge. The pool is
     * 1:1 with a chat session, so the registry lives here instead of a global
     * center keyed by a mutable session id.
     */
    enum class DownloadState { DOWNLOADING, COMPLETED, FAILED }

    data class DownloadEntry(
        val id: Long,
        val filename: String,
        val destination: File?,
        val bytesDone: Long,
        /** <= 0 means unknown (indeterminate). */
        val totalBytes: Long,
        val state: DownloadState,
        val failureReason: String? = null,
        val startedAt: Long,
        /** Terminal entries only: false until the user opens the panel. */
        val seen: Boolean = false,
    )

    /** Newest-first. */
    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val nextDownloadId = java.util.concurrent.atomic.AtomicLong(1)

    /**
     * Posts a user-visible download notice into the owning chat. Wired by
     * ChatViewModel to appendSystemInfo; may be invoked from any thread.
     */
    var onDownloadEvent: ((String) -> Unit)? = null

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun registerDownload(dest: File, totalBytes: Long): Long {
        val id = nextDownloadId.getAndIncrement()
        val entry = DownloadEntry(
            id = id,
            filename = dest.name,
            destination = dest,
            bytesDone = 0L,
            totalBytes = totalBytes,
            state = DownloadState.DOWNLOADING,
            startedAt = System.currentTimeMillis(),
        )
        _downloads.value = listOf(entry) + _downloads.value
        _activeDownload.value = DownloadUiState(dest.name, if (totalBytes > 0) 0f else -1f)
        return id
    }

    private fun updateDownloadEntry(id: Long, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.value = _downloads.value.map { if (it.id == id) transform(it) else it }
    }

    private fun updateDownloadProgress(id: Long, name: String, copied: Long, total: Long) {
        updateDownloadEntry(id) { it.copy(bytesDone = copied, totalBytes = total) }
        _activeDownload.value = DownloadUiState(
            name,
            if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f,
        )
    }

    private fun settleDownload(id: Long, state: DownloadState, reason: String? = null, bytes: Long? = null) {
        downloadJobs.remove(id)
        updateDownloadEntry(id) {
            it.copy(
                state = state,
                failureReason = reason,
                bytesDone = bytes ?: it.bytesDone,
            )
        }
        _activeDownload.value = null
    }

    /** Cancel an in-flight download; the coroutine's CancellationException
     *  path deletes the partial file and settles the entry as cancelled. */
    fun cancelDownload(id: Long) {
        downloadJobs[id]?.cancel()
    }

    /** In-flight + unviewed terminal entries — the toolbar badge number
     *  (mirrors iOS BrowserDownloadCenter.badgeCount). */
    fun downloadBadgeCount(entries: List<DownloadEntry>): Int =
        entries.count { it.state == DownloadState.DOWNLOADING || !it.seen }

    /** Panel opened — terminal entries stop counting toward the badge. */
    fun markDownloadsSeen() {
        _downloads.value = _downloads.value.map {
            if (it.state != DownloadState.DOWNLOADING) it.copy(seen = true) else it
        }
    }

    /** "Clear" in the panel: drops ALL finished records (completed AND failed,
     *  iOS v3 semantics) — in-flight rows untouched. */
    fun clearFinishedDownloads() {
        _downloads.value = _downloads.value.filter { it.state == DownloadState.DOWNLOADING }
    }


    /** The session's /var/minis/workspace/ host directory — downloads land here
     *  so the agent can read and operate on them in follow-up turns. */
    private fun sessionWorkspaceDir(): File? {
        val sid = sessionId ?: return null
        return File(File(File(context.filesDir, "minis-sessions"), sid), "workspace")
            .apply { mkdirs() }
    }

    /** name.ext → name-1.ext → name-2.ext … until unused. */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base-$i" else "$base-$i.$ext")
            i++
        }
        return f
    }

    /** Stream an http/https download into the session workspace with progress. */
    internal fun startUrlDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val dir = sessionWorkspaceDir() ?: run {
            Log.w(TAG, "download rejected: no session bound to pool")
            return
        }
        val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val dest = uniqueFile(dir, name)
        onDownloadEvent?.invoke("Downloading ${middleTruncated(dest.name)}…")
        val id = registerDownload(dest, contentLength)
        val job = downloadScope.launch {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                userAgent?.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("User-Agent", it) }
                // Reuse the WebView's cookies so authenticated downloads work.
                android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                    conn.setRequestProperty("Cookie", it)
                }
                val total = if (contentLength > 0) contentLength else conn.contentLengthLong
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            // Makes cancelDownload() actually stop mid-stream
                            // instead of copying to completion.
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            updateDownloadProgress(id, dest.name, copied, total)
                        }
                    }
                }
                conn.disconnect()
                finishDownload(id, dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User cancel is not a failure notice-worthy event: delete the
                // partial file, settle as cancelled, keep the chat quiet.
                Log.i(TAG, "download cancelled: ${dest.name}")
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = "Cancelled")
            } catch (t: Throwable) {
                Log.w(TAG, "download failed: ${dest.name} — ${t.message}")
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = t.message ?: "error")
                onDownloadEvent?.invoke("Download failed: ${middleTruncated(dest.name)} — ${t.message}")
            }
        }
        downloadJobs[id] = job
    }

    /** Persist decoded blob: bytes (from the JS bridge) into the workspace. */
    internal fun saveBlobDownload(data: ByteArray, filename: String, mimeType: String?) {
        val dir = sessionWorkspaceDir() ?: run {
            Log.w(TAG, "blob download rejected: no session bound to pool")
            return
        }
        // guessFileName on a blob: URL yields "downloadfile.bin" — refine the
        // extension from the blob's actual MIME type when we have one.
        var name = filename
        if ((name.endsWith(".bin") || !name.contains('.')) && mimeType != null) {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { ext ->
                name = name.substringBeforeLast('.') + "." + ext
            }
        }
        val dest = uniqueFile(dir, name)
        val id = registerDownload(dest, data.size.toLong())
        val job = downloadScope.launch {
            try {
                dest.writeBytes(data)
                finishDownload(id, dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = "Cancelled")
            } catch (t: Throwable) {
                Log.w(TAG, "blob download save failed: ${t.message}")
                runCatching { dest.delete() }
                settleDownload(id, DownloadState.FAILED, reason = t.message ?: "error")
                onDownloadEvent?.invoke("Download failed: ${middleTruncated(dest.name)} — ${t.message}")
            }
        }
        downloadJobs[id] = job
    }

    private fun finishDownload(id: Long, dest: File) {
        val size = dest.length()
        settleDownload(id, DownloadState.COMPLETED, bytes = size)
        val sizeText = android.text.format.Formatter.formatShortFileSize(context, size)
        Log.i(TAG, "download finished: ${dest.name} ($sizeText) → ${dest.absolutePath}")
        // [T-android-browser-download-ux] iOS v3 semantics: the human-facing
        // notice is just name+size (middle-truncated) — the old full
        // "/var/minis/workspace/… — minis://workspace/…" path+link tail
        // wrapped badly in the bubble, and path navigation is the downloads
        // panel's job now.
        onDownloadEvent?.invoke("Downloaded ${middleTruncated(dest.name)} ($sizeText)")
    }

    /** Route a manager's download callbacks into this pool's workspace saver. */
    private fun wireDownloadHandlers(manager: BrowserUseManager) {
        manager.onDownloadStart = { url, ua, cd, mime, len -> startUrlDownload(url, ua, cd, mime, len) }
        manager.onBlobDownloadData = { data, name, mime -> saveBlobDownload(data, name, mime) }
    }

    // -- Agent Execution --

    /**
     * Execute a browser action. Routes to the correct tab, acquires it, and executes.
     */
    /**
     * @param singleTab [T-browser-readaction-follow-tab-and-yolo-android] YOLO
     *   mode: when true, ALL tab-less actions (including navigate) bypass the
     *   implicit-tab fan-out and target [selectedTabId] serially. This is the
     *   headless / CLI / RPC driver path — a single agent runs a strictly
     *   SERIAL sequence (navigate → execute_js → navigate → …) and expects
     *   "operate on the page I just navigated to", so fan-out (which exists to
     *   keep an agent's CONCURRENT navigates from stomping each other) is wrong.
     *   The UI / in-app agent-tool path leaves this false. Mirrors iOS
     *   `BrowserTabPool.execute(action:singleTab:)`. Explicit tab_id always
     *   routes to that tab regardless of this flag.
     */
    /**
     * @param owner [T-android-browser-tab-ownership] The agent acting, or null.
     *
     *   Null DEFAULTS TO PRIVILEGED on purpose: every existing UI and human
     *   call site keeps working untouched and unchanged, and only the two agent
     *   entry points opt into the constraint by passing an id. `owner ==
     *   sessionId` is privileged too — a chat driving its own pool is not an
     *   agent competing with itself.
     */
    suspend fun execute(
        input: BrowserActionInput,
        singleTab: Boolean = false,
        owner: String? = null,
    ): BrowserActionResult {
        // [T-android-browser-tab-ownership-newtab] The ownership check is
        // PER ACTION, not a blanket pre-check, matching iOS
        // (BrowserTabPool.swift:823-836 handles newTab / listTabs before any
        // mayUse call and gates only closeTab and the content actions).
        //
        // It used to sit here, above the dispatch, applied to every action that
        // carried a `tab_id`. That deadlocked agents on device: models routinely
        // fill in `tab_id: 0` on EVERY call — including `new_tab` and
        // `list_tabs`, where the field is meaningless — so an agent that did not
        // own tab 0 was refused, read the error's advice ("open one with action:
        // new_tab"), did exactly that, and was refused again for the same
        // reason. The advice was unfollowable and the run could never acquire a
        // tab. Observed on a Pixel 4a: 8 of 25 browser_use calls in one
        // delegation fan-out failed this way, across three different sub agents,
        // all on `new_tab` / `list_tabs` / `navigate` carrying `tab_id: 0`.
        //
        // `new_tab` creates a tab and `list_tabs` is already scoped to the
        // caller's own tabs, so neither can touch someone else's page — there
        // was never anything for the check to protect there. The actions that
        // DO act on an existing tab keep it, still before any side effect.
        fun ownershipViolation(tabId: Int?): BrowserActionResult? {
            if (tabId == null || isPrivileged(owner)) return null
            if (!_tabs.value.any { it.id == tabId }) return null
            if (mayUse(tabId, owner)) return null
            return notYourTabError(tabId, owner!!)
        }
        // Handle tab management actions at pool level
        return when (input.action) {
            // No ownership check: this CREATES a tab. A stray tab_id on a
            // new_tab call names nothing it will touch.
            BrowserAction.NEW_TAB -> newTab(input.url, owner)
            BrowserAction.CLOSE_TAB -> {
                val target = resolveCloseTarget(input.tabId, owner)
                ownershipViolation(target) ?: closeTab(target)
            }
            // Already scoped to the caller's own tabs by listTabs(owner).
            BrowserAction.LIST_TABS -> listTabs(owner)
            BrowserAction.SET_VIEWPORT -> handleSetViewport(input)
            else -> {
                ownershipViolation(input.tabId)?.let { return it }
                // [T-browser-use-per-tab-serial-android] Serialize per explicit
                // tab id. Only an explicit tab_id that names an EXISTING tab can
                // be contended by two concurrent tools — that's the trampling
                // case, so those calls take that tab's Mutex (waiting up to
                // TAB_SERIAL_WAIT_TIMEOUT_MS for the prior op to release). A
                // null tab_id (or one pointing at a not-yet-created tab) is left
                // unlocked so concurrent tab-less calls fan out to separate tabs
                // via acquireTab and run in parallel rather than deadlocking.
                val serialTabId = input.tabId?.takeIf { reqId ->
                    _tabs.value.any { it.id == reqId }
                }
                if (serialTabId != null) {
                    executeSerialized(serialTabId, input)
                } else {
                    // [T-browser-readaction-follow-tab-and-yolo-android]
                    // Decide whether this tab-less action may fan out to a fresh
                    // tab or must stick to the current (selected) tab:
                    //
                    //  - YOLO / singleTab: NOTHING fans out — serial driver wants
                    //    every action on the one tab it's been navigating.
                    //  - operate-current-page actions (execute_js, get_text,
                    //    click, scroll, screenshot, …): follow selectedTabId even
                    //    in the default agent path. Fanning these out to a fresh/
                    //    other tab was the #612 bug (`navigate A → execute_js` ran
                    //    on a blank or stale tab because navigate's tab was still
                    //    in its inUse grace window).
                    //  - opens-new-page actions (navigate, fetch): keep the
                    //    grace-based fan-out so an agent's concurrent navigates
                    //    still open distinct tabs instead of trampling.
                    val mustFollowSelected = singleTab || !input.action.opensNewPage
                    // [T-android-browser-tab-ownership] An agent's implicit
                    // target is its OWN last tab, then a free tab it owns, then
                    // a fresh one — never the chat's selected tab and never a
                    // sibling's. This is the actual incident: three agents with
                    // no tab_id all resolved to the same selected tab, so one
                    // agent's get_readable returned another's page.
                    val agentTarget = if (isPrivileged(owner)) null else {
                        preferredTab(owner)
                            ?: _tabs.value.firstOrNull { !it.inUse && tabOwner[it.id] == owner }?.id
                    }
                    if (agentTarget != null) {
                        // Keep every ownership-map write on Main, the same
                        // dispatcher newTab / closeTab / acquireTab mutate from.
                        // The value here is what preferredTab just read, so this
                        // is idempotent — but relying on that would leave the
                        // one write that is not serialised with the rest.
                        withContext(Dispatchers.Main) { lastTabByOwner[owner!!] = agentTarget }
                        executeSerialized(agentTarget, input)
                    } else if (!isPrivileged(owner)) {
                        // The agent owns nothing usable yet: give it a fresh tab
                        // of its own rather than borrowing the chat's.
                        runAcquiredAction(input, implicitTab = true, owner = owner)
                    } else if (mustFollowSelected && _tabs.value.isNotEmpty()) {
                        // Route to the selected tab under its serial lock. If the
                        // selected id was evicted, fall back to the most-recent
                        // existing tab so we still operate on a real page rather
                        // than spawning a blank one.
                        val sel = _selectedTabId.value
                        val targetId = if (_tabs.value.any { it.id == sel }) sel
                            else _tabs.value.maxByOrNull { it.lastActivityDate }!!.id
                        executeSerialized(targetId, input)
                    } else {
                        // Empty pool (first call) OR an opens-new-page action in
                        // the default agent path → original acquire/fan-out.
                        runAcquiredAction(input, implicitTab = true, owner = owner)
                    }
                }
            }
        }
    }

    /**
     * [T-browser-use-per-tab-serial-android] Run [input] against an existing
     * tab id under that tab's serial Mutex. Waits at most
     * [TAB_SERIAL_WAIT_TIMEOUT_MS] to acquire the lock; on timeout returns a
     * guidance error telling the model to open a new tab and retry there
     * (rather than keep contending the busy one). The wait timeout is purely
     * the lock-acquisition wait — once the lock is held, the underlying page
     * operation runs with its own existing timeout, unchanged.
     */
    private suspend fun executeSerialized(tabId: Int, input: BrowserActionInput): BrowserActionResult {
        val mutex = lockForTab(tabId)
        // (GH#245) Two separate limits. Only a lock that ANOTHER call kept past
        // the window is "tab busy"; the operation run under the lock has its own
        // deadline inside runAcquiredAction, with its own message. The timeout
        // used to wrap both, so a frozen page on a tab nobody else was using
        // told the model to go open a new tab.
        if (!BrowserActionGuard.lockWithin(mutex, TAB_SERIAL_WAIT_TIMEOUT_MS)) {
            return BrowserActionResult.error(
                context.getString(
                    R.string.browser_use_tab_busy_timeout,
                    tabId,
                    (TAB_SERIAL_WAIT_TIMEOUT_MS / 1000L).toInt(),
                ),
            )
        }
        try {
            // [T-browser-readaction-follow-tab-and-yolo-android] Pin
            // acquisition to the locked tab id. The follow-selected and
            // YOLO paths resolve a target tab id whose lock we hold here,
            // but input.tabId may be null (tab-less call) — without this
            // override acquireTab(null) would re-run the fan-out and could
            // land on a DIFFERENT tab than the one we locked, defeating
            // both the serialization and the follow-tab fix.
            return runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)
        } finally {
            // Also runs on cancellation (the CLI's 90s timeout), so an
            // abandoned call never leaves the tab locked.
            mutex.unlock()
        }
    }

    /**
     * Acquire a tab (creating / selecting per [acquireTab]) and run the action.
     * Shared by the serialized (explicit existing tab id) and unlocked (tab-less
     * / new-tab) execution paths. [T-browser-use-per-tab-serial-android]
     *
     * @param acquireTabId [T-browser-readaction-follow-tab-and-yolo-android]
     *   when non-null, acquire exactly this tab id instead of `input.tabId` —
     *   used by [executeSerialized] so a tab-less follow-selected / YOLO action
     *   acquires the same tab whose serial lock the caller already holds.
     */
    private suspend fun runAcquiredAction(
        input: BrowserActionInput,
        implicitTab: Boolean,
        acquireTabId: Int? = null,
        owner: String? = null,
    ): BrowserActionResult {
        // [T-android-browser-global-tab-cap] If the registry reclaimed the tab
        // this call names, report that BEFORE acquiring — otherwise acquireTab
        // silently creates a replacement under the same id and the agent gets a
        // blank page where its session used to be, with nothing to explain it.
        val requestedId = acquireTabId ?: input.tabId
        if (requestedId != null) {
            consumePreemptNotice(requestedId)?.let { return BrowserActionResult.error(it) }
        }
        val tab = acquireTab(requestedId, owner)
            ?: return BrowserActionResult.error("Failed to acquire browser tab")
        // (GH#245) If acquireTab just rebuilt the tab this call named, say so
        // first: element references and page state from before are gone.
        val rebuildNote = rebuildNotices.remove(tab.id)
        return try {
            // (GH#245) The page operation gets a hard deadline, on every path
            // (locked or not — the in-app agent's browser_use had none at all).
            // The deadline wraps execute() only, not acquireTab above, so the
            // bounded implicit-tab wait cannot eat into a legitimate action's
            // budget. A deadline hit, or a cancellation that arrives after the
            // action had already run a long time (the CLI giving up at 90s),
            // flags the tab; the next acquireTab then rebuilds it.
            val outcome = BrowserActionGuard.runGuarded(
                onSuspect = { reason -> tab.manager.markWedged(reason) },
            ) { tab.manager.execute(input) }
            val result = when (outcome) {
                is BrowserActionGuard.Outcome.Done -> outcome.value
                is BrowserActionGuard.Outcome.DeadlineExceeded -> return BrowserActionResult.error(
                    withRebuildNote(
                        rebuildNote,
                        "Browser action '${input.action.value}' timed out on tab ${tab.id} after " +
                            "${BrowserActionGuard.ACTION_DEAD_TIMEOUT_MS / 1000}s — the page stopped " +
                            "responding. The tab has been reset and will be rebuilt on your next " +
                            "browser call; retry the action.",
                    ),
                )
                is BrowserActionGuard.Outcome.Wedged -> return BrowserActionResult.error(
                    withRebuildNote(
                        rebuildNote,
                        "Browser tab ${tab.id} is not usable: ${outcome.message}. It will be " +
                            "rebuilt on your next browser call; retry the action.",
                    ),
                )
            }
            // The registry may have preempted this very tab while we were
            // suspended inside execute(): its WebView is gone, so `result` is
            // whatever a destroyed WebView produced. Replace it with the
            // retryable explanation rather than surfacing that noise.
            consumePreemptNotice(tab.id)?.let { return BrowserActionResult.error(it) }
            // [T-android-js-dialogs-256] If this tab's page tried to open an
            // alert/confirm/prompt, the agent browser answered it with a default
            // rather than showing a modal (which would hang an unattended loop).
            // Surface that here, on the next result for this tab, so the model
            // can react — otherwise the interception is invisible and it keeps
            // assuming the page did what it asked. Prepended so it is read
            // before the result it qualifies; draining clears the queue, so each
            // dialog is reported exactly once.
            val withDialogs = tab.manager.drainInterceptedDialogReport()
                ?.let { result.copy(text = it + result.text) }
                ?: result
            // [T-android-browser-result-tab-id] Stamp the VERIFIED tab id — the
            // id of the tab we actually acquired and dispatched on, NOT the
            // global selectedTabId, which the fan-out branch in acquireTab
            // overwrites mid-flight when it spawns a fresh tab for a concurrent
            // navigate. Without this the agent had to guess tab_id for its
            // follow-up reads/scrolls and routinely picked the wrong tab.
            val withRebuild = if (rebuildNote != null) {
                withDialogs.copy(text = withRebuildNote(rebuildNote, withDialogs.text))
            } else {
                withDialogs
            }
            stampTabId(withRebuild.copy(pageURL = tab.manager.currentURL.value), tab.id)
        } finally {
            tab.lastActivityDate = Date()
            if (implicitTab) {
                // [T-browser-implicit-tab-inuse-until-load-android] Keep the tab
                // marked inUse through a grace window instead of releasing it the
                // instant the action returns. The action itself already ran to
                // completion (navigate suspended until the page finished loading),
                // so this grace exists purely to repel a near-simultaneous
                // tab-less acquire — without it, three back-to-back navigates each
                // clear inUse before the next acquireTab(null) runs and all land on
                // tab 0. armImplicitGraceRelease re-arms on each action so a
                // same-task follow-up chain holds the tab; it flips inUse=false
                // after IMPLICIT_TAB_GRACE_MS of inactivity.
                armImplicitGraceRelease(tab)
            } else {
                // Explicit tab_id path keeps the original immediate release — its
                // serialization is the per-tab Mutex (executeSerialized), so a
                // grace hold here would only delay the model's own next deliberate
                // op on that same tab. [T-browser-use-per-tab-serial-android]
                tab.inUseGraceJob?.cancel()
                tab.inUseGraceJob = null
                tab.inUse = false
            }
            updateTabs()
            saveState()
        }
    }

    private fun withRebuildNote(note: String?, text: String): String =
        if (note == null) text else "$note\n$text"

    /**
     * [T-android-browser-result-tab-id] Stamp [targetId] onto a tab-contextual
     * result so the agent can see exactly which tab it just operated on, with no
     * need to guess for follow-up reads/scrolls. Sets the structured [tabId]
     * field and appends a `  tab_id: <N>` line to the human-readable text in the
     * same indent style as the existing `Title:` / `Viewport:` lines. Idempotent
     * — if the text already mentions `tab_id:` (e.g. newTab embeds it in its
     * narrative), skip the append.
     */
    private fun stampTabId(result: BrowserActionResult, targetId: Int): BrowserActionResult {
        val needsLine = !result.text.contains("tab_id:")
        val text = if (needsLine) {
            val needsNewline = result.text.isNotEmpty() && !result.text.endsWith("\n")
            result.text + (if (needsNewline) "\n" else "") + "  tab_id: $targetId"
        } else {
            result.text
        }
        return result.copy(text = text, tabId = targetId)
    }

    /**
     * [T-browser-implicit-tab-inuse-until-load-android] Hold [tab] inUse for a
     * grace window after an implicit-tab action, re-arming on each call. The
     * tab stays inUse=true now; a freshly-launched job flips it false after
     * [IMPLICIT_TAB_GRACE_MS] unless another action re-arms first. Runs on the
     * Main dispatcher so the inUse mutation is consistent with acquireTab (which
     * reads/writes inUse on Main).
     */
    private fun armImplicitGraceRelease(tab: Tab) {
        tab.inUse = true
        tab.inUseGraceJob?.cancel()
        tab.inUseGraceJob = evictionScope.launch {
            delay(IMPLICIT_TAB_GRACE_MS)
            withContext(Dispatchers.Main) {
                tab.inUse = false
                tab.inUseGraceJob = null
                tab.lastActivityDate = Date()
                updateTabs()
            }
        }
    }

    /**
     * Acquire a tab for agent use. Creates tab 0 if none exist.
     *
     * Mirrors iOS BrowserTabPool behavior: even when the model explicitly
     * sends `tab_id: 0` (strict-schema providers like OpenAI Responses API
     * always populate every field), fall back to the default tab when no
     * such tab exists yet, instead of returning null. Otherwise the very
     * first browser_use call in a session fails with "Failed to acquire
     * browser tab" because the model can't know that tab 0 hasn't been
     * lazily created.
     */
    private suspend fun acquireTab(
        requestedTabId: Int? = null,
        owner: String? = null,
    ): Tab? = withContext(Dispatchers.Main) {
        // [T-android-webview-render-process-gone] (GH#341) Drop tabs whose
        // renderer died before any selection runs. Such a tab still looks
        // perfectly usable here — it is in `_tabs`, not `inUse`, and owned by
        // the caller — so without this it is the one most likely to be picked,
        // and every action on it would drive a WebView the system has torn
        // down. Removing it lets the normal create-a-tab path below build a
        // live replacement.
        //
        // (GH#245) The same applies to a renderer that is alive but frozen
        // (isWedged): it never fires onRenderProcessGone, so it used to stay
        // in rotation forever and the CLI's single-tab driver kept landing on
        // it. Both kinds are now dropped, and the tab the caller actually
        // named is rebuilt in place — see [dropAndRebuildUnusableTabs].
        val requested = dropAndRebuildUnusableTabs(requestedTabId) ?: requestedTabId
        var currentTabs = _tabs.value.toMutableList()

        // Find requested tab, falling back to default-or-create when the
        // requested id doesn't exist (covers `tab_id: 0` against an empty pool).
        // [T-android-browser-tab-ownership] An agent may only be handed a tab
        // it owns; anything else falls through to pick-or-create below rather
        // than silently borrowing a sibling's.
        val mayTakeRequested = requested != null &&
            currentTabs.any { it.id == requested } &&
            mayUse(requested, owner)
        val tab = if (mayTakeRequested) {
            currentTabs.first { it.id == requested }
        } else {
            // [T-browser-use-per-tab-serial-android] No explicit (existing)
            // tab id: prefer a tab that is NOT already in use, and create a new
            // one when every existing tab is busy (up to MAX_TABS). This runs on
            // the Main dispatcher so concurrent tab-less calls resolve here one
            // after another — the first claims a free tab and marks it in-use,
            // the second then skips it and lands on a different / freshly-created
            // tab. That lets two parallel tab-less navigates open two tabs and
            // run concurrently instead of both trampling tab 0. A sequential
            // follow-up (e.g. screenshot after navigate) still reuses the same
            // tab because it's no longer in use by then.
            // [T-browser-implicit-tab-inuse-until-load-android] When every tab is
            // busy (inUse, incl. the post-action grace hold) AND we're at
            // MAX_TABS so a new tab can't be created, do NOT overwrite a busy tab
            // — that's the trampling this task forbids. Wait (bounded) for a tab
            // to free up; only fall back to reusing the least-recently-active tab
            // if nothing frees within the wait window.
            // [T-android-browser-tab-ownership] "Free" means free AND mine (or
            // unowned when privileged): grabbing a sibling's idle tab is the
            // trampling this exists to stop.
            var picked = currentTabs.firstOrNull { !it.inUse && mayUse(it.id, owner) }
                ?: createTab(currentTabs, owner = owner)
            if (picked == null) {
                val waitDeadline = IMPLICIT_TAB_WAIT_MS
                var waited = 0L
                while (waited < waitDeadline) {
                    delay(IMPLICIT_TAB_WAIT_POLL_MS)
                    waited += IMPLICIT_TAB_WAIT_POLL_MS
                    currentTabs = _tabs.value.toMutableList()
                    picked = currentTabs.firstOrNull { !it.inUse && mayUse(it.id, owner) }
                        ?: createTab(currentTabs, owner = owner)
                    if (picked != null) break
                }
            }
            picked ?: currentTabs.firstOrNull()
        }

        if (tab != null) {
            // Re-acquiring a tab that was sitting in its post-action grace window
            // cancels the pending release — it's actively in use again now.
            tab.inUseGraceJob?.cancel()
            tab.inUseGraceJob = null
            tab.inUse = true
            tab.lastActivityDate = Date()
            // [T-android-browser-tab-ownership] selectedTabId is what a human
            // sees when they take over, so only the chat and the human move it.
            // An agent acquiring a tab used to drag the user's view onto it —
            // and with several agents running, onto whichever one acted last.
            if (isPrivileged(owner)) _selectedTabId.value = tab.id
            _tabs.value = currentTabs
            // Claim it for this agent, and record it as their most recent so
            // the next implicit call comes back here instead of fanning out.
            //
            // Done HERE rather than in the caller because this block already
            // runs on Dispatchers.Main, which is what serialises these two maps
            // against newTab / closeTab. Claiming from the caller's context
            // would race them.
            if (owner != null && !isPrivileged(owner)) {
                tabOwner.putIfAbsent(tab.id, owner)
                if (tabOwner[tab.id] == owner) lastTabByOwner[owner] = tab.id
            }
        }

        // Apply the pending blank-page load from createTab() now that we're
        // in a suspend context. Must happen BEFORE returning so the agent's
        // first JS evaluation on this tab sees `document.body` populated.
        if (tab != null && tab.needsInitialBlankPage) {
            tab.needsInitialBlankPage = false
            tab.manager.loadBlankPage()
        }
        tab
    }

    /**
     * (GH#245, Android counterpart of iOS `rebuildDeadTab`) Remove every tab
     * whose WebView can no longer be driven — renderer died, or renderer
     * frozen ([BrowserUseManager.isWedged]) — and release its WebView and
     * bookkeeping. Before, a dead tab was only filtered out of [_tabs]: its
     * WebView was never destroyed and its lock entry never removed.
     *
     * A tab the caller NAMED (the requested id, or the selected tab the CLI's
     * single-tab driver follows) is rebuilt in place: a fresh tab under a new
     * id, reloading the last URL, inheriting the owner, the selection and any
     * agent's "last tab" pointer. Other unusable tabs are just dropped — nobody
     * is waiting on them, and a later action recreates tabs on demand.
     *
     * Returns the replacement id for [requestedTabId] if that tab was rebuilt,
     * else null. Main thread only (WebView teardown and the ownership maps).
     */
    private fun dropAndRebuildUnusableTabs(requestedTabId: Int?): Int? {
        val bad = _tabs.value.filter { it.manager.isUnusable }
        if (bad.isEmpty()) return null
        Log.w(
            TAG,
            "acquireTab: dropping ${bad.size} unusable tab(s) " +
                bad.joinToString(prefix = "(", postfix = ")") {
                    "${it.id}:" + if (it.manager.isRendererDead) "renderer-gone" else "unresponsive"
                },
        )
        // Derived from the snapshot above, not re-filtered: the flags are
        // written from other threads, and a tab flagged between two filters
        // would leave the pool without ever being released.
        val badIds = bad.map { it.id }.toSet()
        val survivors = _tabs.value.filterNot { it.id in badIds }.toMutableList()
        _tabs.value = survivors.toList()
        var replacementForRequested: Int? = null
        for (dead in bad) {
            val url = dead.manager.currentURL.value.takeIf { it.isNotEmpty() && it != "about:blank" }
            val owner = tabOwner[dead.id]
            val wasSelected = _selectedTabId.value == dead.id
            val pointingOwners = lastTabByOwner.filterValues { it == dead.id }.keys.toList()
            val cause = if (dead.manager.isRendererDead) "crashed (its renderer process died)"
                else "stopped responding"
            releaseTabResources(dead)
            tabOwner.remove(dead.id)
            lastTabByOwner.entries.removeAll { it.value == dead.id }
            savedURLs.remove(dead.id)

            if (dead.id != requestedTabId && !wasSelected) continue
            val fresh = createTab(survivors, url = url, owner = owner)
            if (fresh == null) {
                Log.w(TAG, "acquireTab: could not rebuild tab ${dead.id} (no slot)")
                continue
            }
            if (owner != null) tabOwner[fresh.id] = owner
            pointingOwners.forEach { lastTabByOwner[it] = fresh.id }
            if (wasSelected) _selectedTabId.value = fresh.id
            if (dead.id == requestedTabId) replacementForRequested = fresh.id
            rebuildNotices[fresh.id] =
                "[Tab rebuilt] Browser tab ${dead.id} $cause and was rebuilt as tab ${fresh.id}" +
                    (url?.let { "; page reloaded from $it" } ?: "; it had no page to reload") +
                    ". Earlier element references and page state are gone — re-locate " +
                    "elements before interacting, and use tab_id ${fresh.id} from now on."
            Log.i(TAG, "acquireTab: rebuilt tab ${dead.id} as ${fresh.id} (url=${url ?: "none"})")
        }
        if (survivors.isNotEmpty() && survivors.none { it.id == _selectedTabId.value }) {
            _selectedTabId.value = survivors.first().id
        }
        saveState()
        return replacementForRequested
    }

    private fun createTab(
        tabs: MutableList<Tab>,
        url: String? = null,
        // [T-android-browser-tab-ownership] The shared allocator has to know
        // who is asking: the ceiling is MAX_TABS for the chat alone but grows
        // per active agent, so a hardcoded MAX_TABS here would cap an agent's
        // quota at the chat's limit and defeat the quota entirely.
        owner: String? = null,
    ): Tab? {
        if (tabs.size >= effectiveMaxTabs(owner)) return null

        // [T-android-browser-global-tab-cap] The local ceiling is necessary but
        // not sufficient: it bounds THIS pool, and there are several. Ask the
        // process-wide registry for a slot only after the local check passes,
        // so a pool that is already over its own budget never causes a sibling
        // to lose a tab. Denial reuses the existing "no tab available" contract
        // (return null) rather than inventing a new failure shape — acquireTab
        // then takes its bounded wait / fall-back-to-existing path, and newTab
        // reports it as a capacity message, both of which already exist.
        if (!BrowserTabPoolRegistry.requestSlot(this)) {
            Log.i(TAG, "Registry denied a global tab slot (cap ${BrowserTabPoolRegistry.GLOBAL_TAB_CAP})")
            return null
        }

        val id = nextTabId++
        val webView = WebView(context)
        // [T-android-minis-url-session-scope] Hand the manager a LIVE reader of
        // this pool's session id (set later via setSession) plus a context, so
        // `minis://workspace/...` resolves against this chat's sandbox instead
        // of the global, last-writer-wins bind-mount map.
        val manager = BrowserUseManager(
            webView,
            userAgentProfile,
            sessionIdProvider = { sessionId },
            appContext = context.applicationContext,
        )
        if (userAgentProfile == UserAgentProfile.CUSTOM && !customUserAgentString.isNullOrEmpty()) {
            manager.setUserAgent(userAgentProfile, customUserAgentString)
        }
        // Honor the resolved viewport (session override > global custom > UA default).
        val (vpW, vpH) = resolvedViewportSize()
        manager.applyViewport(vpW, vpH)

        // Setup window.open / close handlers
        manager.onNewWindow = { resultMsg -> handleNewWindow(resultMsg) }
        manager.onCloseWindow = { handleCloseWindow(manager) }
        wireDownloadHandlers(manager)

        val tab = Tab(id = id, manager = manager)
        tabs.add(tab)
        _tabs.value = tabs.toList()

        // Load saved URL or provided URL. When neither is supplied the tab
        // is marked `needsInitialBlankPage` so the caller (acquireTab /
        // newTab / handleNewWindow) can issue a blank-page load once we're
        // back in a suspend context — a fresh tab with no page loaded
        // reports WebView's hardcoded 980px fallback, hiding the session
        // viewport override until the first real navigation.
        val loadUrl = url ?: savedURLs.remove(id)
        if (loadUrl != null) {
            manager.loadURL(loadUrl)
        } else {
            tab.needsInitialBlankPage = true
        }

        Log.i(TAG, "Created tab $id (total: ${tabs.size})")
        return tab
    }

    // -- Tab Management Actions --

    private suspend fun newTab(url: String?, owner: String? = null): BrowserActionResult = withContext(Dispatchers.Main) {
        val currentTabs = _tabs.value.toMutableList()
        // [T-android-browser-tab-ownership] An agent is bounded by its own
        // quota first: the point is that one agent cannot consume the whole
        // pool and starve its siblings, which is what the incident looked like.
        if (owner != null && !isPrivileged(owner)) {
            val mine = tabIdsOwnedBy(owner)
            if (mine.size >= agentTabQuota) {
                return@withContext BrowserActionResult.error(
                    "You already have ${mine.size} tab(s) (limit $agentTabQuota per agent). " +
                        "Reuse tab id(s) ${mine.joinToString(", ")} — navigate them to the next page — " +
                        "or close_tab one of yours first.",
                )
            }
        }
        // The ceiling is dynamic now, so the old hardcoded "Maximum 3 tabs"
        // text would be a lie whenever an agent is running.
        val ceiling = effectiveMaxTabs(owner)
        if (currentTabs.size >= ceiling) {
            return@withContext BrowserActionResult.error("Maximum $ceiling tabs reached")
        }
        val tab = createTab(currentTabs, url, owner)
        if (tab == null) {
            // [T-android-browser-global-tab-cap] The local ceiling was already
            // checked above, so reaching here with tabs below it means the
            // process-wide registry declined. Say so, and say what works: the
            // model can act on "reuse a tab you have" but not on a bare
            // "failed", which reads as a defect and invites a retry loop.
            return@withContext BrowserActionResult.error(
                if (currentTabs.size < ceiling) {
                    "Cannot open another browser tab: the device-wide limit of " +
                        "${BrowserTabPoolRegistry.GLOBAL_TAB_CAP} is reached and every other " +
                        "tab is busy. Reuse one of your existing tabs (list_tabs) or " +
                        "close_tab one first."
                } else {
                    "Failed to create new tab"
                },
            )
        }
        if (owner != null && !isPrivileged(owner)) {
            tabOwner[tab.id] = owner
            lastTabByOwner[owner] = tab.id
        }
        // [T-android-browser-tab-ownership] selectedTabId follows the CHAT and
        // the human only. It is the tab a person sees when they take over, so
        // an agent opening a tab must not silently move their view.
        if (isPrivileged(owner)) _selectedTabId.value = tab.id
        if (tab.needsInitialBlankPage) {
            tab.needsInitialBlankPage = false
            tab.manager.loadBlankPage()
        }
        saveState()
        // [T-android-browser-result-tab-id] Set the structured tabId. Text isn't
        // restamped here — newTab's own narrative already names the tab id below.
        BrowserActionResult(
            text = "Opened new tab ${tab.id}" + (if (url != null) " at $url" else "") +
                ". Use tab_id: ${tab.id} to target this tab.",
            tabId = tab.id,
        )
    }

    /**
     * [T-android-browser-tab-ownership] Which tab a `close_tab` should target.
     *
     * `closeTab(null)` falls back to [_selectedTabId], which is the CHAT's tab
     * — so an agent closing "the current tab" without an id would close the
     * human's. An agent with no id closes its own most recent tab instead, and
     * an agent with no tabs at all closes nothing (-1 produces "not found"
     * rather than silently taking someone else's).
     */
    internal fun resolveCloseTarget(tabId: Int?, owner: String?): Int? {
        if (isPrivileged(owner)) return tabId
        if (tabId != null) return tabId
        return preferredTab(owner) ?: tabIdsOwnedBy(owner!!).firstOrNull() ?: -1
    }

    private suspend fun closeTab(tabId: Int?): BrowserActionResult = withContext(Dispatchers.Main) {
        val id = tabId ?: _selectedTabId.value
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == id }
        if (idx < 0) return@withContext BrowserActionResult.error("Tab $id not found")

        val closing = currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        // [T-android-browser-global-tab-cap] Actually release the WebView.
        // Dropping the list entry alone left the renderer process alive, so a
        // "closed" tab kept costing what an open one did while no longer being
        // counted — which would make the global cap under-report real usage.
        releaseTabResources(closing)
        // [T-android-browser-tab-ownership] Forget the bookkeeping with the
        // tab, or a later tab reusing this id would inherit a stale owner.
        tabOwner.remove(id)
        lastTabByOwner.entries.removeAll { it.value == id }

        // Select next tab
        if (currentTabs.isNotEmpty() && _selectedTabId.value == id) {
            _selectedTabId.value = currentTabs.first().id
        }
        saveState()
        BrowserActionResult(text = "Closed tab $id")
    }

    private fun listTabs(owner: String? = null): BrowserActionResult {
        // [T-android-browser-tab-ownership] An agent sees only its own tabs.
        // This is what makes the rule discoverable rather than arbitrary: the
        // ids it is allowed to use are exactly the ids it can see.
        val visible = if (isPrivileged(owner)) _tabs.value
        else _tabs.value.filter { tabOwner[it.id] == owner }
        val lines = visible.map { tab ->
            val marker = if (tab.id == _selectedTabId.value) "*" else " "
            val title = tab.manager.pageTitle.value.ifEmpty { "(blank)" }
            val url = tab.manager.currentURL.value.ifEmpty { "about:blank" }
            "$marker Tab ${tab.id}: $title — $url"
        }
        return if (lines.isEmpty()) {
            BrowserActionResult(
                text = if (isPrivileged(owner)) "No open tabs"
                else "You have no tabs open. Use action: new_tab to open one.",
            )
        } else {
            BrowserActionResult(text = lines.joinToString("\n"))
        }
    }

    // -- window.open / close --

    private fun handleNewWindow(resultMsg: Message) {
        val currentTabs = _tabs.value.toMutableList()
        if (currentTabs.size >= MAX_TABS) {
            Log.w(TAG, "window.open rejected: max tabs reached")
            return
        }
        val id = nextTabId++
        val newWebView = WebView(context)
        val manager = BrowserUseManager(
            newWebView,
            userAgentProfile,
            sessionIdProvider = { sessionId },
            appContext = context.applicationContext,
        )
        if (userAgentProfile == UserAgentProfile.CUSTOM && !customUserAgentString.isNullOrEmpty()) {
            manager.setUserAgent(userAgentProfile, customUserAgentString)
        }
        val (vpW, vpH) = resolvedViewportSize()
        manager.applyViewport(vpW, vpH)
        manager.onNewWindow = { msg -> handleNewWindow(msg) }
        manager.onCloseWindow = { handleCloseWindow(manager) }
        wireDownloadHandlers(manager)

        val tab = Tab(id = id, manager = manager)
        currentTabs.add(tab)
        _tabs.value = currentTabs
        _selectedTabId.value = id

        // Send the WebView transport back
        val transport = resultMsg.obj as? WebView.WebViewTransport
        transport?.webView = newWebView
        resultMsg.sendToTarget()

        Log.i(TAG, "window.open → created tab $id")
    }

    private fun handleCloseWindow(manager: BrowserUseManager) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.manager === manager }
        if (idx >= 0) {
            val closing = currentTabs.removeAt(idx)
            val closedId = closing.id
            _tabs.value = currentTabs
            if (_selectedTabId.value == closedId && currentTabs.isNotEmpty()) {
                _selectedTabId.value = currentTabs.first().id
            }
            // [T-android-browser-global-tab-cap] Release the WebView, but NOT
            // synchronously: we are inside this very WebView's own
            // onCloseWindow callback, and destroying a WebView from within its
            // own callback is the documented way to crash the renderer. Post it
            // so teardown happens once the callback has unwound. The other
            // close paths (closeTab / closeTabFromUI) are not re-entrant this
            // way and release inline.
            closing.manager.webView.post { releaseTabResources(closing) }
            Log.i(TAG, "window.close → removed tab $closedId")
        }
    }

    // -- UI Tab Actions --

    /** Select a tab by ID (user tapped on tab chip). */
    fun selectTab(id: Int) {
        if (_tabs.value.any { it.id == id }) {
            _selectedTabId.value = id
        }
    }

    /** Create a new tab from the UI (user tapped + button). */
    suspend fun newTabFromUI(): Tab? = withContext(Dispatchers.Main) {
        val currentTabs = _tabs.value.toMutableList()
        if (currentTabs.size >= MAX_TABS) return@withContext null
        val tab = createTab(currentTabs) ?: return@withContext null
        _selectedTabId.value = tab.id
        if (tab.needsInitialBlankPage) {
            tab.needsInitialBlankPage = false
            tab.manager.loadBlankPage()
        }
        saveState()
        tab
    }

    /** Close a tab from the UI (user tapped X on tab chip). */
    suspend fun closeTabFromUI(tabId: Int) = withContext(Dispatchers.Main) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@withContext
        val closing = currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        // [T-android-browser-global-tab-cap] See closeTab: release, don't just
        // forget. A user closing tabs by hand is the most common way tabs go
        // away, so leaking here would defeat the cap in ordinary use.
        releaseTabResources(closing)
        if (_selectedTabId.value == tabId && currentTabs.isNotEmpty()) {
            _selectedTabId.value = currentTabs.first().id
        }
        saveState()
    }

    /**
     * Select an existing tab whose current URL matches [url] (host + path,
     * ignoring trailing slash and query/fragment differences). If no tab
     * matches, create a new tab loaded with [url]. Returns the resolved tab,
     * or null if the pool is at capacity and creation failed.
     *
     * Mirrors the "if it's in the pool show it, otherwise reload" UX flow for the
     * tool-call preview's globe button — the user expects to land on the
     * agent's existing tab if it's still around, otherwise spawn a new one
     * rather than clobber an unrelated tab.
     */
    fun selectOrCreateTabForURL(url: String): Tab? {
        if (url.isBlank()) return _tabs.value.firstOrNull()
        val target = normalizeUrlForMatch(url)
        val currentTabs = _tabs.value.toMutableList()
        val match = currentTabs.firstOrNull { normalizeUrlForMatch(it.manager.currentURL.value) == target }
        if (match != null) {
            _selectedTabId.value = match.id
            match.lastActivityDate = Date()
            updateTabs()
            return match
        }
        val tab = createTab(currentTabs, url) ?: return null
        _selectedTabId.value = tab.id
        saveState()
        return tab
    }

    private fun normalizeUrlForMatch(raw: String): String {
        if (raw.isEmpty()) return ""
        return try {
            val uri = android.net.Uri.parse(raw)
            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.lowercase() ?: ""
            val path = (uri.path ?: "").trimEnd('/')
            "$scheme://$host$path"
        } catch (_: Exception) {
            raw.trimEnd('/')
        }
    }

    /**
     * Ensure at least one tab exists (for user-facing browser sheet).
     * Does NOT mark as inUse. Must be called on the main thread.
     *
     * Also sets the selected tab id so the sheet's `selectedTab` lookup
     * resolves on first composition, and persists state.
     */
    fun ensureTabForUI(): Tab {
        val currentTabs = _tabs.value.toMutableList()
        val existing = currentTabs.firstOrNull()
        if (existing != null) {
            if (_selectedTabId.value != existing.id &&
                currentTabs.none { it.id == _selectedTabId.value }) {
                _selectedTabId.value = existing.id
            }
            return existing
        }
        val tab = createTab(currentTabs)!!
        _selectedTabId.value = tab.id
        saveState()
        return tab
    }

    // -- User Agent --

    /** Set user agent from UI settings. Applies to all existing tabs and reloads them. */
    fun setUserAgentFromUI(profile: UserAgentProfile, customUA: String? = null) {
        userAgentProfile = profile
        customUserAgentString = customUA
        for (tab in _tabs.value) {
            tab.manager.setUserAgent(profile, customUA)
        }
        // `setUserAgent` resets each tab's layout to the new UA profile's
        // default viewport. Re-apply the resolved viewport so a session or
        // global custom override isn't silently clobbered by a UA switch.
        // applyViewportToAllTabs is suspend because it awaits tab reloads;
        // fire-and-forget since this is called from the UI thread.
        evictionScope.launch { applyViewportToAllTabs() }
    }

    // -- Release --

    /**
     * [T-android-browser-release-all-semantics] Hand every tab back to the
     * user WITHOUT destroying anything.
     *
     * This is the "Takeover" action: the user is watching a page the agent is
     * driving (AgentBrowsingOverlay, "Minis is browsing · Takeover") and wants
     * the wheel. Dropping `inUse` is the whole job — the page they are looking
     * at must survive, so this deliberately does NOT release WebView
     * resources. Destroying here would blank the screen at the exact moment
     * the user asked to take control.
     *
     * Renamed from `releaseAllTabs()`, which promised far more than it did and
     * was called from two sites wanting opposite things — see
     * [destroyAllTabs].
     */
    fun releaseAllTabsToUser() {
        _tabs.value = _tabs.value.map { it.copy(inUse = false) }
        saveState()
    }

    /**
     * [T-android-browser-release-all-semantics] Destroy every tab and free the
     * native WebView memory.
     *
     * The bug this fixes: `releaseAllTabs()` only set `inUse = false`. Clearing
     * a chat called it expecting "drop the browser resources this session
     * spawned" (its call-site comment says exactly that, citing the iOS
     * `deletePersistedData` + `releasePool` pair), but every WebView stayed
     * alive — tens of MB each, for a session whose messages had just been
     * deleted, with no way left to reach them. Nothing else would collect
     * them either: idle eviction only runs at 15 minutes and the pool itself
     * outlives the cleared chat.
     *
     * Mirrors [closeTab]'s teardown for each tab — release the WebView, forget
     * the ownership bookkeeping — then empties the list in one publish so
     * observers see a single transition rather than N.
     */
    fun destroyAllTabs() {
        val doomed = _tabs.value
        if (doomed.isEmpty()) return
        // Publish the empty list FIRST: anything rendering a tab drops it
        // before its WebView is torn down, so no composition is left holding
        // a destroyed view.
        _tabs.value = emptyList()
        for (tab in doomed) {
            releaseTabResources(tab)
            tabOwner.remove(tab.id)
            lastTabByOwner.entries.removeAll { it.value == tab.id }
        }
        savedURLs.clear()
        saveState()
        Log.i(TAG, "destroyed ${doomed.size} tab(s) and released their WebViews")
    }

    // -- Idle Eviction (call from a timer) --

    fun evictIdleTabs() {
        val now = System.currentTimeMillis()
        val currentTabs = _tabs.value.toMutableList()
        val timeoutMs = idleTimeoutMs
        val toRemove = currentTabs.filter { !it.inUse && (now - it.lastActivityDate.time) >= timeoutMs }
        for (tab in toRemove) {
            val url = tab.manager.currentURL.value
            if (url.isNotEmpty()) savedURLs[tab.id] = url
            currentTabs.remove(tab)
            releaseTabResources(tab)
            Log.i(TAG, "Evicted idle tab ${tab.id}")
        }
        if (toRemove.isNotEmpty()) {
            _tabs.value = currentTabs
            if (currentTabs.isNotEmpty() && currentTabs.none { it.id == _selectedTabId.value }) {
                _selectedTabId.value = currentTabs.first().id
            }
            saveState()
        }
    }

    // -- Registry-driven reclaim  [T-android-browser-global-tab-cap] ---------
    //
    // Entry points for BrowserTabPoolRegistry to reclaim a tab in THIS pool on
    // behalf of a different one. All three run on the main thread (their only
    // callers are already inside `withContext(Dispatchers.Main)`), which is
    // both what WebView.destroy() requires and what serialises them against
    // acquireTab / newTab / closeTab without any lock of their own. The
    // registry deliberately never takes a `tabLocks` mutex — see the
    // concurrency note on BrowserTabPoolRegistry.

    /**
     * Tabs the registry preempted while they were `inUse`. The agent driving
     * such a tab is mid-`execute` and will get whatever a destroyed WebView
     * produces; the id parked here converts that into one intelligible,
     * explicitly retryable message on its next call. Consumed on read so the
     * retry itself sails through. Mirrors iOS `preemptedTabIds`.
     */
    private val preemptedTabIds = mutableSetOf<Int>()

    /**
     * If [id] was preempted, consume the flag and return the message the model
     * should see. The wording names the cause and the remedy: a bare
     * "WebView destroyed" would read as a bug and invite the model to give up,
     * whereas the whole point of preemption is that retrying now works.
     */
    private fun consumePreemptNotice(id: Int): String? {
        if (!preemptedTabIds.remove(id)) return null
        return "Browser tab $id was reclaimed under memory pressure (another " +
            "session needed the slot). Please retry — the page URL was saved " +
            "and will be restored."
    }

    /**
     * Actually release a tab's native resources. Dropping the Tab from [_tabs]
     * is NOT enough: the WebView keeps its own renderer process alive until
     * `destroy()`, so a gate that only shortened a list would be a gate over
     * nothing. Detach-then-destroy follows the established recipe in
     * `ui/preview/WebViewHolder.destroy()` — a WebView still parented to the
     * browser sheet's container throws on destroy, and the pool's tabs ARE
     * mounted there whenever the user has the sheet open.
     *
     * Main thread only (WebView's own requirement). Never throws: a failed
     * teardown must not abort the caller's tab list mutation, or we would drop
     * the tab from the pool's accounting while leaving it live — the worst of
     * both.
     */
    private fun releaseTabResources(tab: Tab) {
        tab.inUseGraceJob?.cancel()
        tab.inUseGraceJob = null
        tabLocks.remove(tab.id)
        try {
            val webView = tab.manager.webView
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "Tab ${tab.id} teardown failed: ${t.message}")
        }
    }

    /** Shared bookkeeping after a tab leaves [_tabs] by reclaim. */
    private fun forgetTab(id: Int, remaining: List<Tab>) {
        tabOwner.remove(id)
        lastTabByOwner.entries.removeAll { it.value == id }
        if (remaining.isNotEmpty() && remaining.none { it.id == _selectedTabId.value }) {
            _selectedTabId.value = remaining.first().id
        }
    }

    override fun registrySnapshot(): List<BrowserTabPoolRegistry.TabInfo> =
        _tabs.value.map { BrowserTabPoolRegistry.TabInfo(it.id, it.inUse, it.lastActivityDate) }

    override fun evictTabForRegistry(id: Int) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = currentTabs[idx]
        // Safety net: the registry picked this tab as idle, but it scanned a
        // snapshot and the main thread may have handed the tab out since.
        // Preempting something the registry believed was free would break the
        // "idle first" guarantee silently, so decline instead.
        if (tab.inUse) return
        val url = tab.manager.currentURL.value
        if (url.isNotEmpty()) savedURLs[id] = url
        currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        releaseTabResources(tab)
        forgetTab(id, currentTabs)
        saveState()
        Log.i(TAG, "Evicted tab $id at registry request (url=${url.take(60)})")
    }

    override fun preemptTabForRegistry(id: Int) {
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = currentTabs[idx]
        val url = tab.manager.currentURL.value
        if (url.isNotEmpty()) savedURLs[id] = url
        currentTabs.removeAt(idx)
        _tabs.value = currentTabs
        releaseTabResources(tab)
        preemptedTabIds.add(id)
        forgetTab(id, currentTabs)
        saveState()
        Log.i(TAG, "Preempted in-use tab $id at registry request (url=${url.take(60)})")
    }

    override fun evictAllIdleForMemoryPressure(): Int {
        val currentTabs = _tabs.value.toMutableList()
        val toRemove = currentTabs.filter { !it.inUse }
        if (toRemove.isEmpty()) return 0
        for (tab in toRemove) {
            val url = tab.manager.currentURL.value
            if (url.isNotEmpty()) savedURLs[tab.id] = url
            currentTabs.remove(tab)
            releaseTabResources(tab)
            tabOwner.remove(tab.id)
            lastTabByOwner.entries.removeAll { it.value == tab.id }
        }
        _tabs.value = currentTabs
        if (currentTabs.isNotEmpty() && currentTabs.none { it.id == _selectedTabId.value }) {
            _selectedTabId.value = currentTabs.first().id
        }
        saveState()
        Log.i(TAG, "Evicted ${toRemove.size} idle tab(s) under memory pressure")
        return toRemove.size
    }

    // -- Viewport API (mirrors iOS BrowserTabPool) --

    /**
     * Resolved viewport for new WebViews. Priority (matches iOS
     * `resolvedViewportSize()`): session override > global custom > UA profile default.
     */
    fun resolvedViewportSize(): Pair<Int, Int> {
        if (_sessionViewportWidth.value > 0 && _sessionViewportHeight.value > 0) {
            return _sessionViewportWidth.value to _sessionViewportHeight.value
        }
        if (_customViewportWidth.value > 0 && _customViewportHeight.value > 0) {
            return _customViewportWidth.value to _customViewportHeight.value
        }
        return userAgentProfile.viewportSize
    }

    /** True if a session or global custom viewport is active. */
    fun hasCustomViewport(): Boolean =
        (_sessionViewportWidth.value > 0 && _sessionViewportHeight.value > 0) ||
            (_customViewportWidth.value > 0 && _customViewportHeight.value > 0)

    /**
     * Set the app-wide custom viewport. Persists to SharedPreferences so it
     * survives app restarts. Mirrors iOS `setGlobalViewport(width:height:)`.
     */
    suspend fun setGlobalViewport(width: Int, height: Int) {
        _customViewportWidth.value = width.coerceAtLeast(0)
        _customViewportHeight.value = height.coerceAtLeast(0)
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(PREF_GLOBAL_VIEWPORT_WIDTH, _customViewportWidth.value)
            .putInt(PREF_GLOBAL_VIEWPORT_HEIGHT, _customViewportHeight.value)
            .apply()
        applyViewportToAllTabs()
    }

    /**
     * Set the session-scoped viewport override. Persisted into the session's
     * tab JSON (not SharedPreferences) so a set_viewport survives restarts but
     * doesn't leak to other sessions. Mirrors iOS `setSessionViewport`.
     *
     * Awaits tab reloads so a follow-up get_page_info sees the new
     * `window.innerWidth/Height`.
     */
    suspend fun setSessionViewport(width: Int, height: Int) {
        _sessionViewportWidth.value = width.coerceAtLeast(0)
        _sessionViewportHeight.value = height.coerceAtLeast(0)
        applyViewportToAllTabs()
        saveState()
    }

    /**
     * Clear the session viewport override so tabs fall back to the global
     * setting. Does not touch SharedPreferences. Mirrors iOS
     * `resetSessionViewport`. Awaits tab reloads.
     */
    suspend fun resetSessionViewport() {
        _sessionViewportWidth.value = 0
        _sessionViewportHeight.value = 0
        applyViewportToAllTabs()
        saveState()
    }

    /**
     * Re-apply the current resolved viewport to every live tab's manager.
     * A resize alone doesn't refresh `window.innerWidth/Height` — Android's
     * WebView snapshots the CSS viewport at load time — so reload every tab
     * after the new layout has been applied. Reload unconditionally, even
     * for `about:blank`: a blank tab reports `window.innerWidth=980` (the
     * Android no-viewport fallback) regardless of container size, so
     * leaving it alone would make `set_viewport` look like a no-op on a
     * fresh tab. A blank reload has no network cost. Matches iOS
     * `applyViewportToAllTabs`, which rebuilds the WKWebView and re-navigates.
     */
    private suspend fun applyViewportToAllTabs() {
        val (w, h) = resolvedViewportSize()
        withContext(Dispatchers.Main) {
            for (tab in _tabs.value) {
                tab.manager.applyViewport(w, h)
                // Await navigation so a follow-up `get_page_info` reads
                // the post-reload `window.innerWidth/Height` instead of
                // the stale pre-reload values.
                tab.manager.reloadAndWait()
            }
        }
    }

    private suspend fun handleSetViewport(input: BrowserActionInput): BrowserActionResult = withContext(Dispatchers.Main) {
        if (input.reset) {
            resetSessionViewport()
            val (w, h) = resolvedViewportSize()
            return@withContext BrowserActionResult(text = "Viewport reset to default (${w}x$h)")
        }
        val w = input.viewportWidth
        val h = input.viewportHeight
        if (w == null || h == null || w <= 0 || h <= 0) {
            return@withContext BrowserActionResult.error(
                "set_viewport requires positive --width and --height, or --reset to restore defaults"
            )
        }
        setSessionViewport(w, h)
        BrowserActionResult(text = "Viewport set to ${w}x$h (session override)")
    }

    // -- Disk Persistence --

    private fun saveState() {
        val sid = sessionId ?: return
        try {
            val dir = File(context.filesDir, "browser_tabs")
            dir.mkdirs()
            val file = File(dir, "$sid.json")
            val json = JSONObject()
            val urlsJson = JSONObject()
            for (tab in _tabs.value) {
                val url = tab.manager.currentURL.value
                if (url.isNotEmpty()) urlsJson.put(tab.id.toString(), url)
            }
            for ((id, url) in savedURLs) {
                urlsJson.put(id.toString(), url)
            }
            json.put("tabURLs", urlsJson)
            json.put("selectedTabId", _selectedTabId.value)
            // Persist session viewport override alongside tab URLs so reopening
            // the session restores the override. Mirrors iOS `PersistedTabs`.
            if (_sessionViewportWidth.value > 0 && _sessionViewportHeight.value > 0) {
                json.put("sessionViewportWidth", _sessionViewportWidth.value)
                json.put("sessionViewportHeight", _sessionViewportHeight.value)
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tab state: ${e.message}")
        }
    }

    private fun loadSavedState() {
        val sid = sessionId ?: return
        try {
            val file = File(context.filesDir, "browser_tabs/$sid.json")
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val urlsJson = json.optJSONObject("tabURLs")
            if (urlsJson != null) {
                val keys = urlsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    savedURLs[key.toInt()] = urlsJson.getString(key)
                }
                _selectedTabId.value = json.optInt("selectedTabId", 0)
            }
            // Restore session viewport override. 0/missing = no override; fall
            // back to the global custom viewport / UA profile default.
            val w = json.optInt("sessionViewportWidth", 0)
            val h = json.optInt("sessionViewportHeight", 0)
            if (w > 0 && h > 0) {
                _sessionViewportWidth.value = w
                _sessionViewportHeight.value = h
                // Re-apply to any live tabs that predate load (rare). At
                // session-load time we're not in a suspend context and the
                // usual case has zero live tabs, so fire-and-forget is
                // adequate — the override is already stored and new tabs
                // will pick it up via `resolvedViewportSize()`.
                if (_tabs.value.isNotEmpty()) {
                    evictionScope.launch { applyViewportToAllTabs() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tab state: ${e.message}")
        }
    }

    private fun updateTabs() {
        _tabs.value = _tabs.value.toList()
    }
}
