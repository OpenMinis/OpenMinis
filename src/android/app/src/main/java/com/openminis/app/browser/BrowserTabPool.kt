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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.openminis.app.R
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages up to 3 browser tabs for the agent, mirroring iOS BrowserTabPool.
 * All tabs share the same cookie store by default on Android.
 */
class BrowserTabPool(private val context: Context) {

    companion object {
        private const val TAG = "BrowserTabPool"
        private const val MAX_TABS = 3
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
         * on the SAME explicit tab id to finish) — it is NOT the browser task
         * timeout. Once the lock is held the actual page operation keeps its
         * existing per-action timeout (BrowserUseManager), untouched.
         */
        private const val TAB_SERIAL_WAIT_TIMEOUT_MS = 60_000L

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
    suspend fun execute(
        input: BrowserActionInput,
        singleTab: Boolean = false,
    ): BrowserActionResult {
        // Handle tab management actions at pool level
        return when (input.action) {
            BrowserAction.NEW_TAB -> newTab(input.url)
            BrowserAction.CLOSE_TAB -> closeTab(input.tabId)
            BrowserAction.LIST_TABS -> listTabs()
            BrowserAction.SET_VIEWPORT -> handleSetViewport(input)
            else -> {
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
                    //    still open distinct tabs (#595) instead of trampling.
                    val mustFollowSelected = singleTab || !input.action.opensNewPage
                    if (mustFollowSelected && _tabs.value.isNotEmpty()) {
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
                        runAcquiredAction(input, implicitTab = true)
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
        val result = withTimeoutOrNull(TAB_SERIAL_WAIT_TIMEOUT_MS) {
            mutex.withLock {
                // [T-browser-readaction-follow-tab-and-yolo-android] Pin
                // acquisition to the locked tab id. The follow-selected and
                // YOLO paths resolve a target tab id whose lock we hold here,
                // but input.tabId may be null (tab-less call) — without this
                // override acquireTab(null) would re-run the fan-out and could
                // land on a DIFFERENT tab than the one we locked, defeating
                // both the serialization and the follow-tab fix.
                runAcquiredAction(input, implicitTab = false, acquireTabId = tabId)
            }
        }
        return result ?: BrowserActionResult.error(
            context.getString(
                R.string.browser_use_tab_busy_timeout,
                tabId,
                (TAB_SERIAL_WAIT_TIMEOUT_MS / 1000L).toInt(),
            ),
        )
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
    ): BrowserActionResult {
        val tab = acquireTab(acquireTabId ?: input.tabId)
            ?: return BrowserActionResult.error("Failed to acquire browser tab")
        return try {
            val result = tab.manager.execute(input)
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
            stampTabId(withDialogs.copy(pageURL = tab.manager.currentURL.value), tab.id)
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
    private suspend fun acquireTab(requestedTabId: Int? = null): Tab? = withContext(Dispatchers.Main) {
        var currentTabs = _tabs.value.toMutableList()

        // Find requested tab, falling back to default-or-create when the
        // requested id doesn't exist (covers `tab_id: 0` against an empty pool).
        val tab = if (requestedTabId != null && currentTabs.any { it.id == requestedTabId }) {
            currentTabs.first { it.id == requestedTabId }
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
            var picked = currentTabs.firstOrNull { !it.inUse } ?: createTab(currentTabs)
            if (picked == null) {
                val waitDeadline = IMPLICIT_TAB_WAIT_MS
                var waited = 0L
                while (waited < waitDeadline) {
                    delay(IMPLICIT_TAB_WAIT_POLL_MS)
                    waited += IMPLICIT_TAB_WAIT_POLL_MS
                    currentTabs = _tabs.value.toMutableList()
                    picked = currentTabs.firstOrNull { !it.inUse } ?: createTab(currentTabs)
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
            _selectedTabId.value = tab.id
            _tabs.value = currentTabs
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

    private fun createTab(tabs: MutableList<Tab>, url: String? = null): Tab? {
        if (tabs.size >= MAX_TABS) return null

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

    private suspend fun newTab(url: String?): BrowserActionResult = withContext(Dispatchers.Main) {
        val currentTabs = _tabs.value.toMutableList()
        if (currentTabs.size >= MAX_TABS) {
            return@withContext BrowserActionResult.error("Maximum $MAX_TABS tabs reached")
        }
        val tab = createTab(currentTabs, url)
        if (tab == null) {
            return@withContext BrowserActionResult.error("Failed to create new tab")
        }
        _selectedTabId.value = tab.id
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

    private suspend fun closeTab(tabId: Int?): BrowserActionResult = withContext(Dispatchers.Main) {
        val id = tabId ?: _selectedTabId.value
        val currentTabs = _tabs.value.toMutableList()
        val idx = currentTabs.indexOfFirst { it.id == id }
        if (idx < 0) return@withContext BrowserActionResult.error("Tab $id not found")

        currentTabs.removeAt(idx)
        _tabs.value = currentTabs

        // Select next tab
        if (currentTabs.isNotEmpty() && _selectedTabId.value == id) {
            _selectedTabId.value = currentTabs.first().id
        }
        saveState()
        BrowserActionResult(text = "Closed tab $id")
    }

    private fun listTabs(): BrowserActionResult {
        val lines = _tabs.value.map { tab ->
            val marker = if (tab.id == _selectedTabId.value) "*" else " "
            val title = tab.manager.pageTitle.value.ifEmpty { "(blank)" }
            val url = tab.manager.currentURL.value.ifEmpty { "about:blank" }
            "$marker Tab ${tab.id}: $title — $url"
        }
        return if (lines.isEmpty()) {
            BrowserActionResult(text = "No open tabs")
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
            val closedId = currentTabs[idx].id
            currentTabs.removeAt(idx)
            _tabs.value = currentTabs
            if (_selectedTabId.value == closedId && currentTabs.isNotEmpty()) {
                _selectedTabId.value = currentTabs.first().id
            }
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
        currentTabs.removeAt(idx)
        _tabs.value = currentTabs
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

    fun releaseAllTabs() {
        _tabs.value = _tabs.value.map { it.copy(inUse = false) }
        saveState()
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
