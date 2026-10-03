package com.openminis.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.StringFormat
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.DatabaseVersionGuard
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.notification.BackgroundTaskNotifier
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.MountedFolderCoordinator
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.offload.AccessibilityOffloadHandler
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.BrowserUseOffloadHandler
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ClipboardOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.ModelUseOffloadHandler
import com.openminis.app.sandbox.offload.SessionsOffloadHandler
import com.openminis.app.sandbox.offload.ShizukuOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PhotosOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.SpeakOffloadHandler
import com.openminis.app.sandbox.offload.SpeechOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.MinisImageFetcher
import kotlinx.coroutines.launch

class MinisApp : Application(), ImageLoaderFactory {
    /**
     * T-android-safemode-lateinit-crash: true once the heavy subsystem
     * block in [onCreate] has fully run (DB + every repository assigned).
     *
     * Safe-mode makes [onCreate] early-return BEFORE those assignments,
     * and that early return is permanent for the life of the process —
     * the Application object is never re-created just because the user
     * dismissed the crash dialog. Callers must therefore not use
     * `CrashFrequencyDetector.isSafeMode()` as a proxy for "are the
     * repositories usable": that flag flips back to false on dismiss
     * while the repositories stay unassigned forever.
     *
     * This flag is the authoritative signal instead — it only ever goes
     * false → true, and only after every lateinit below is assigned.
     */
    @Volatile
    var subsystemsInitialized: Boolean = false
        private set

    /**
     * [T-android-downgrade-compat] Why init was skipped, when it was.
     *
     * Distinguishes "the database is from a newer build" (recoverable: the
     * user just needs to reinstall the newer version, and nothing on disk has
     * been touched) from a genuine init failure, so MainActivity can show
     * accurate guidance instead of a generic crash-report prompt.
     */
    @Volatile
    var dbVersionDecision: DatabaseVersionGuard.Decision =
        DatabaseVersionGuard.Decision.PROCEED
        private set

    /**
     * [T-android-safemode-lateinit-crash-147] Null-safe view of
     * [chatRepository] for code that can run BEFORE (or entirely without)
     * MainActivity.
     *
     * GH#147: a user installed a third-party skill and the app then crashed on
     * every launch with `lateinit property chatRepository has not been
     * initialized`. MainActivity has guarded this since b72dc591, but that
     * guard only covers the UI entry point. Alarms, notification taps, the
     * notification-listener service and the scheduled-task runner can all
     * start the process with NO Activity at all: Android creates the
     * Application, `onCreate` early-returns under safe-mode, and the component
     * then reads a lateinit that will never be assigned for the life of the
     * process.
     *
     * Reading this property instead of the lateinit turns a hard crash into a
     * null the caller can handle — the process stays alive, the crash-burst
     * detector is not re-tripped, and the user is not locked out.
     *
     * Deliberately checks [subsystemsInitialized] rather than
     * `::chatRepository.isInitialized`: the latter would report true midway
     * through onCreate, when chatRepository is assigned but the repositories
     * assigned after it are not — callers would then trip over the NEXT
     * uninitialized field instead. One flag, one meaning: "everything the app
     * layer needs is ready".
     */
    val chatRepositoryOrNull: ChatRepository?
        get() = if (subsystemsInitialized) chatRepository else null

    /**
     * [T-android-share-launch-crash] Same contract as [chatRepositoryOrNull],
     * for the share-import path.
     *
     * ShareReceiverActivity is reachable from the system share sheet at any
     * time, including in a process whose [onCreate] early-returned under
     * safe-mode. Reading the `providerRepository` lateinit there would throw
     * UninitializedPropertyAccessException from a dialog callback and crash the
     * app — writing another crash log and feeding the very burst detector that
     * put the process in safe-mode. Returning null instead lets the caller show
     * "import failed", which it already does for the missing-Application case.
     */
    val providerRepositoryOrNull: ProviderRepository?
        get() = if (subsystemsInitialized) providerRepository else null

    /**
     * [T-android-safemode-lateinit-crash-147] True when the app-layer
     * dependencies are usable. Prefer this over
     * `CrashFrequencyDetector.isSafeMode()`, which flips back to false the
     * moment the user dismisses the crash dialog while the repositories stay
     * unassigned forever (that mismatch was the b72dc591 crash loop).
     */
    fun subsystemsReady(): Boolean = subsystemsInitialized

    lateinit var database: AppDatabase
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var providerRepository: ProviderRepository
        private set
    lateinit var envVarRepository: EnvVarRepository
        private set
    lateinit var skillRepository: SkillRepository
        private set
    lateinit var mcpRepository: MCPRepository
        private set
    lateinit var memoryRepository: MemoryRepository
        private set
    lateinit var webAppShortcutRepository: WebAppShortcutRepository
        private set
    lateinit var backgroundSettingsRepository: BackgroundSettingsRepository
        private set
    lateinit var backgroundTaskNotifier: BackgroundTaskNotifier
        private set
    lateinit var mountedFoldersStore: MountedFoldersStore
        private set

    /**
     * T180-bg-notif: foreground-Activity counter, mutated by the
     * ActivityLifecycleCallbacks registered in [onCreate]. Read by
     * [BackgroundTaskNotifier] to decide whether to suppress completion
     * notifications (no notification while the user is already looking
     * at the app).
     */
    @Volatile
    private var foregroundActivityCount: Int = 0

    fun isAppForeground(): Boolean = foregroundActivityCount > 0

    /**
     * T-bg-overlay phase 2: live "is the app foreground?" stream so the
     * AgentForegroundService can react to background ↔ foreground
     * transitions and toggle the floating tool-status overlay. Same
     * source as [isAppForeground] (started/stopped balanced count) —
     * just exposed as a StateFlow for collectors.
     */
    // Initial true: prevents overlay flash before first Activity onStart emits foreground=true
    // (T-overlay-startup-flash). ActivityLifecycleCallbacks below will flip to the real value
    // on the next lifecycle tick.
    private val _isAppForegroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isAppForegroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _isAppForegroundFlow

    /**
     * App-wide network monitor. Mirrors iOS NetworkMonitor.shared — observes
     * connectivity transitions, evicts shared OkHttp connection pools, and
     * refreshes the sandbox's /etc/resolv.conf when DNS servers change.
     */
    val networkMonitor: NetworkMonitor = NetworkMonitor()

    /**
     * Application-scoped BrowserTabPool for shell-invoked `minis-browser-use`.
     * Separate from the per-ChatViewModel pool so browser state driven from
     * within an ish shell doesn't collide with the agent's own tabs.
     */
    val sharedBrowserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(this).also { it.setSession("minis-browser-use") }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // T283: install ACRA before any app-level singleton runs so a crash
        // anywhere from onCreate forward is captured. CrashFileSender
        // (registered via META-INF/services/org.acra.sender.ReportSenderFactory)
        // writes filesDir/logs/crash-<stamp>.log — same dir + .log extension
        // that AppLogger.listLogFiles already filters for, so reports surface
        // in LogManagementScreen with no extra UI.
        ACRA.init(
            this,
            CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportFormat(StringFormat.JSON)
                .withLogcatArguments(listOf("-t", "200", "-v", "time"))
                .withReportContent(
                    ReportField.APP_VERSION_NAME,
                    ReportField.APP_VERSION_CODE,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                ),
        )
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        // [T-android-content-capture-off] Opt every Compose window out of
        // Android content capture, before any of them exists.
        //
        // With a content-capture service active (Pixel's Android System
        // Intelligence enables one), Compose re-walks the whole semantics tree
        // after UI changes to report screen content to it. Streaming replies
        // change the UI continuously, and a Pixel 6 perf-build profile of a
        // task put that walk (AndroidContentCaptureManager
        // .contentCaptureChangeChecker) at 10.45% of all app CPU — about a
        // quarter of the main thread, more than the frame work itself.
        //
        // Minis's own UI is unaffected: rendering, accessibility (a separate
        // path), autofill, IME and in-app selection all stay the same. What is
        // lost is system features that read app content through content
        // capture (e.g. text selection in Recents, some screen-content
        // suggestions), which also means chat text is no longer reported to
        // that service.
        //
        // Set once, here, rather than paused while tasks run: in Compose 1.9.1
        // a change check that finds the switch off returns WITHOUT clearing
        // its pending flag, so nothing schedules another check even after the
        // switch is turned back on. Only a switch that never flips is safe.
        androidx.compose.ui.contentcapture.ContentCaptureManager.isEnabled = false
        // [T-tools-granular-switches] Carry a pre-split delegation choice onto
        // the per-tool key, once, before anything reads it. Mirrors iOS
        // MinisApp.swift's AgentToolSwitch.migrateLegacyIfNeeded().
        com.openminis.app.tools.AgentToolSwitch.migrateLegacyIfNeeded(this)

        // T287-followup: ACRA spawns a separate reporter process named
        // "<package>:acra" (declared by the library's manifest) to send
        // the crash report after the main process dies. Application
        // subclasses run in EVERY process of the package, so without
        // this early-return the :acra process would also try to bind
        // the abstract socket / boot the database / register offload
        // handlers — racing the next main-process spawn for resources
        // it doesn't need. Symptom: main process gets EADDRINUSE on
        // LocalServerSocket and stays in a permanent restart loop on
        // the splash screen. Skip everything except ACRA.init (already
        // done in attachBaseContext, which is what makes the :acra
        // process do its job).
        if (ACRA.isACRASenderServiceProcess()) {
            Log.i("MinisApp", "skipping app init in :acra reporter process")
            return
        }

        // T-android-safemode-lateinit-crash: hand AppLogger a Context before
        // any early-return below can skip AppLogger.init(). This costs
        // nothing (no I/O, no prefs, no capture) and is what lets the in-app
        // log/crash list still find filesDir/logs on a safe-mode launch —
        // the exact launch where the user is trying to read the crash files.
        AppLogger.primeContext(this)

        // [T-codex-fast-mode] Capture the app context + warm the Fast Mode
        // flag cache so the provider layer (no Context) can read it at
        // request-build time — including offload / title-gen calls that
        // never pass through a ViewModel.
        com.openminis.app.data.FastModePrefs.prime(this)

        // Warm the auto-compact flag the same way: the pre-send context check
        // and the in-chat one-tap opt-in both read it from places that have no
        // Activity context.
        com.openminis.app.data.AutoCompactPrefs.prime(this)

        // [T-anthropic-cli-version] Warm the claude-cli fingerprint used on
        // the Anthropic OAuth path and refresh it in the background when the
        // persisted value is older than a day. Never blocks: the provider
        // reads a volatile cache, and a failed refresh keeps the last known
        // version (falling back to the compiled-in floor on a fresh install).
        com.openminis.app.provider.anthropic.ClaudeCliVersion.prime(this)

        // T283: install NDK signal handler for native crashes (SIGSEGV/
        // SIGABRT/SIGBUS/SIGFPE/SIGILL/SIGSYS). Writes a one-shot text
        // report to filesDir/logs/native-crash-<stamp>.log before re-raising
        // the signal so the system tombstone is also generated. Runs
        // before any other native lib (proot, pty_bridge, …) is dlopen'd
        // by the rest of onCreate so the handler is in place when those
        // libs first execute.
        try {
            com.openminis.app.crash.NativeCrashHandler.install(
                java.io.File(filesDir, "logs"),
            )
        } catch (t: Throwable) {
            Log.w("MinisApp", "NativeCrashHandler install failed: ${t.message}")
        }

        // [T-android-alarm-reschedule-on-restart] Re-register scheduled-task
        // alarms on every process start.
        //
        // AlarmManager drops an app's pending alarms whenever the app is
        // force-stopped — which includes an app UPDATE, and the aggressive
        // "cleanup" some OEM launchers apply. Neither sends BOOT_COMPLETED, so
        // the receiver's boot branch never ran and the user's scheduled tasks
        // stayed dead until the next reboot. The MY_PACKAGE_REPLACED filter
        // added alongside this covers the update case promptly; this covers
        // everything else, at the cost of one cheap pass per launch.
        //
        // Safe to run unconditionally: registerAlarm() reuses one PendingIntent
        // per task id (requestCode = taskId.hashCode(), FLAG_UPDATE_CURRENT), so
        // re-registering REPLACES rather than duplicates. No debounce needed
        // even when the broadcast and this both fire during an update.
        //
        // On Dispatchers.IO, matching every other init in this method: it reads
        // the task store off disk and makes one binder call per task, and cold
        // start is not the place for either.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.openminis.app.scheduled.ScheduledTaskManager(this@MinisApp).rescheduleAll()
            }.onFailure {
                Log.w("MinisApp", "startup rescheduleAll failed: ${it.message}")
            }
        }

        // T-android-fgs-timeout-crash: chain an UncaughtExceptionHandler
        // ahead of ACRA's so we can intercept
        // android.app.RemoteServiceException$ForegroundServiceDidNotStopInTimeException
        // specifically. The mediaPlayback FGS type change removes the
        // dataSync 6h cap that was tripping this, but this handler keeps
        // the user from seeing a raw process-death if a future Android
        // version adds a new cap to mediaPlayback too. We can't actually
        // survive this exception (it's thrown on the main looper after
        // SystemServer has already decided to kill us) but we CAN:
        //  - stop the foreground service explicitly so the notification
        //    drops cleanly instead of lingering as a zombie row
        //  - delegate to ACRA so the crash log still hits disk
        try {
            val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val isFgsTimeout = throwable.javaClass.name.endsWith(
                        "RemoteServiceException\$ForegroundServiceDidNotStopInTimeException",
                    ) || (throwable.message?.contains("foreground service of type") == true &&
                        throwable.message?.contains("did not stop within its timeout") == true)
                    if (isFgsTimeout) {
                        Log.w(
                            "MinisApp",
                            "FGS timeout caught; stopping service before deferring to ACRA: ${throwable.message}",
                        )
                        // Stop the service so the system tears the
                        // sticky binding down cleanly instead of
                        // re-spawning into the same trap.
                        runCatching {
                            val intent = Intent(this, com.openminis.app.service.AgentForegroundService::class.java)
                            stopService(intent)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MinisApp", "FGS-timeout handler internal failure: ${t.message}")
                }
                // Always defer to the prior handler so ACRA's
                // dump-and-relaunch flow runs intact.
                priorHandler?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.w("MinisApp", "install FGS-timeout handler failed: ${t.message}")
        }

        // T-android-crash-freq-share: local fallback for Crashlytics (#458).
        // Scan filesDir/logs/ for crash-*.log + native-crash-*.log files
        // touched in the last hour; if THRESHOLD+ are present, stash the
        // list so MainActivity.onCreate can prompt to share them.
        com.openminis.app.crash.CrashFrequencyDetector.checkAtLaunch(this)

        // [OpenMinis#363] Recover the PREVIOUS process death from the system.
        // A native SIGABRT kills us outright, so the only full account of it
        // is debuggerd's tombstone — which the app cannot read from
        // /data/tombstones/ and the user cannot fetch without adb.
        // ApplicationExitInfo hands the same content back through a channel
        // we ARE allowed to read, including the abort message and the memory
        // map that turns a raw PC into a library name.
        //
        // Off the main thread: it does file I/O and must not sit on startup.
        // Deliberately AFTER checkAtLaunch, which only stats existing files —
        // safe-mode's decision is about this launch, not the previous death,
        // and running before it would delay that decision for no gain.
        Thread {
            com.openminis.app.crash.ExitInfoCollector.collect(this)
        }.apply { isDaemon = true; name = "exit-info-collect" }.start()

        // Hard short-circuit: when checkAtLaunch flips safe-mode ON, skip
        // every heavy subsystem (DB, repositories, offload server, PRoot
        // bind mounts, network monitor, …). The only thing MainActivity
        // will do is pop the share-or-dismiss dialog and finish. Without
        // this guard, anything from `chatRepository = ChatRepository(...)`
        // onward is a potential re-crash trigger on a loop — the whole
        // point of safe-mode is to stop the bleeding before another
        // segfault rewrites the log files.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w("MinisApp", "safe-mode ON — skipping app subsystem init")
            return
        }

        // Initialize the daily-rotating file logger first. When the user has
        // logging enabled in Settings, this also kicks off stdout/stderr
        // capture so subsequent println / Throwable.printStackTrace lines from
        // the rest of onCreate land in today's log file. Mirrors iOS
        // `LoggingManager.startIfEnabled()` (called from MinisApp.swift:143).
        AppLogger.init(this)

        // Bug 2 (MIUI silent kill) diagnostic: write a launch-cycle beacon
        // so a subsequent launch can observe whether the previous run
        // exited cleanly (onTerminate hit) or was force-killed by LMK /
        // MIUI's aggressive background cleaner. Read on next launch by
        // [com.openminis.app.diagnostics.LaunchCycleBeacon].
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordLaunch(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordLaunch failed: ${t.message}")
        }

        // Start the main-thread hang watchdog before the heavier subsystems
        // (DB / repositories / iSH bring-up) get going so it can observe any
        // stall in onCreate itself. Posts heartbeats at 1s cadence; if the
        // main thread fails to land one for 3s, the detector dumps the main
        // stack to filesDir/logs/stall-<date>.log and bumps a persisted
        // counter. AppNavigation reads that counter on cold start to
        // override the launch destination to home after 3 hangs in a row,
        // so a user trapped opening a session that hangs the UI gets
        // unstuck on the next launch.
        com.openminis.app.diagnostics.HangDetector.start(this)

        // [T-android-safemode-lateinit-crash-147] Structural backstop for the
        // whole repository block.
        //
        // The individual guards below (and inside SkillRepository) close the
        // known holes, but the failure MODE is what makes this dangerous: any
        // throw between the first assignment and `subsystemsInitialized = true`
        // leaves the Application permanently half-built. onCreate never re-runs,
        // so every later launch crashes reading an unassigned lateinit, each
        // crash re-trips the crash-burst detector, and the user is locked out
        // until they reinstall — exactly GH#147.
        //
        // Rethrowing here would keep that loop. Instead: log loudly, leave
        // subsystemsInitialized false, and let MainActivity's existing guard
        // show the crash-share dialog. The app still cannot do real work this
        // launch, but it FAILS VISIBLY AND RECOVERABLY instead of dying on the
        // first Compose frame forever.
        // [T-android-downgrade-compat] Probe the on-disk schema version BEFORE
        // Room opens the file. If the database was written by a newer build and
        // no downgrade migration covers the jump, Room would throw at first
        // access and the app could never start. Deciding here — while the file
        // is still untouched, read only via SQLiteDatabase.OPEN_READONLY — lets
        // the UI show recoverable guidance instead of dying, and guarantees
        // nothing has migrated or dropped a table by the time we choose.
        dbVersionDecision = DatabaseVersionGuard.evaluate(this)

        try {
        // Guard INSIDE the try, so a newer-schema database takes the very same
        // degraded-mode path the existing init-failure handling already
        // provides (subsystemsInitialized stays false and MainActivity shows a
        // recoverable screen) instead of returning early from onCreate — which
        // would also skip SoulStore, ConfigRegistry, RootfsManager and the rest
        // of startup that has nothing to do with the chat database.
        if (dbVersionDecision == DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE) {
            error(
                "on-disk chat schema is newer than this build " +
                    "(code=${DatabaseVersionGuard.CODE_DB_VERSION}); refusing to open it. " +
                    "The database file is left completely untouched — upgrading restores everything."
            )
        }
        database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(database.chatDao())
        // [T-p2-agent-series] How a background agent's completion reaches its
        // parent: submit the <agent_callback> as a PROGRAMMATIC prompt on the
        // parent's ViewModel (queued behind a running loop, never interrupting).
        com.openminis.app.agent.jobs.AgentJobRegistry.followUpDispatcher = { parentId, text, jobId ->
            // [T-agent-port-round2] HeadlessChatRunner.prompt (not a bare
            // submitPrompt): it waits up to 5 s for the parent's provider to
            // resolve when the VM is freshly created (a scheduled child firing
            // while the parent chat is not open), and reports a real outcome.
            // A parent that no longer exists gets nothing — submitting would
            // re-create rows for a deleted session.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching {
                    if (chatRepository.getSession(parentId) == null) {
                        com.openminis.app.logging.AppLogger.warning("AgentJobRegistry", "followUp ${jobId.take(8)} dropped — parent ${parentId.take(8)} no longer exists")
                        return@launch
                    }
                    val r = com.openminis.app.debug.HeadlessChatRunner.prompt(
                        context = this@MinisApp, sessionId = parentId, text = text,
                        attachments = emptyList(), thinkingLevel = null, wait = false, timeoutMs = 0L,
                    )
                    com.openminis.app.logging.AppLogger.info("AgentJobRegistry", "followUp ${jobId.take(8)} → parent ${parentId.take(8)} outcome=${r.status}")
                }.onFailure { com.openminis.app.logging.AppLogger.warning("AgentJobRegistry", "followUp ${jobId.take(8)} failed: ${it.message}") }
            }
        }
        com.openminis.app.agent.jobs.AgentCallbackLabels.install(this)
        providerRepository = ProviderRepository(this)
        envVarRepository = EnvVarRepository(this)
        // [T-android-safemode-lateinit-crash-147] SkillRepository parses
        // third-party content (skills imported from external hubs), which
        // makes it the realistic source of a throw in this block. Its own
        // init is now fully guarded, so construction cannot escape here — see
        // the comment there for why an exception at this point permanently
        // breaks the Application and produces the GH#147 crash loop.
        skillRepository = SkillRepository(this)
        // [T-android-skill-scan-parity] Mirrors iOS reloading SkillStore on
        // scenePhase .active: pick up skills changed while the app was away.
        // Background and signature-gated, so a no-op resume costs one listing.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    skillRepository.requestReload("foreground")
                }
            }
        )
        mcpRepository = MCPRepository(this)
        memoryRepository = MemoryRepository(java.io.File(filesDir, "minis-global/memory"))
        webAppShortcutRepository = WebAppShortcutRepository(database.webAppShortcutDao())

        // T-android-safemode-lateinit-crash: every repository the UI layer
        // reads is now assigned, so MainActivity may safely compose. Set
        // here rather than at the end of onCreate: the remaining work
        // (sandbox, offload handlers, receivers) is all independently
        // guarded and none of it is required by AppNavigation's
        // constructor arguments. Setting it early keeps a failure in a
        // late, non-UI subsystem from permanently locking the user out
        // of an app whose UI dependencies are in fact ready.
        subsystemsInitialized = true
        } catch (t: Throwable) {
            // subsystemsInitialized stays false — MainActivity will show the
            // crash-share dialog rather than composing against unassigned
            // repositories. Do NOT rethrow: that is what turns a one-off init
            // failure into an unrecoverable launch loop.
            Log.e("MinisApp", "subsystem init failed — app will start in degraded mode", t)
            return
        }

        // [T-soul-md] Seed SOUL.md with the default content on first launch
        // so the Soul settings page and chat bubble identity have a real
        // file to read. Safe no-op on subsequent launches — never
        // overwrites existing user edits. Cache refresh primes the
        // synchronous metadata read-path (chat header / system prompt).
        com.openminis.app.agent.SoulStore.ensureExists(this)
        com.openminis.app.agent.SoulStore.refreshCache(this)

        // T-config: minis-config CLI surface — registry / audit log /
        // master-switch store. Initialized eagerly here so
        // ConfigRegistry.get() is safe from any thread for the rest of
        // the process. Mirrors iOS ConfigRegistry.shared.registerBuiltinsIfNeeded().
        com.openminis.app.config.MinisConfigPermissionStore.init(this)
        com.openminis.app.config.audit.ConfigAuditLog.init(this)
        com.openminis.app.config.ConfigRegistry.init(
            this, providerRepository, envVarRepository, chatRepository,
        )

        // Initialize models.dev registry (loads from bundled asset, refreshes in background)
        ModelsDevApi.init(this)

        // [T-android-model-sort-anr] Build the model-release ranking index off
        // the main thread, before anything asks for a sorted model list.
        //
        // `ModelReleaseIndex.rank()` builds its tables lazily on first call, and
        // that build reads + parses the whole models.dev catalog (4.5 MB / 7561
        // models on a real device). The first caller pays for it synchronously,
        // and the first caller is normally `ChatViewModel.init` ->
        // `applyNewChatDefaultModel`, i.e. the main thread while opening a chat.
        // On a Pixel 4a under memory pressure that blew the 5s input-dispatch
        // deadline and ANR'd the app.
        //
        // MUST come after ModelsDevApi.init(this): the index reads the catalog
        // through ModelsDevApi, whose disk and bundled loaders both return null
        // until they have a Context. Warming before that produced an EMPTY index
        // (`full=0 tail=0`) that then stayed cached, silently unranking every
        // model list — a worse bug than the one being fixed. Verified on device.
        //
        // Warming it here means the index is almost always already built by the
        // time a chat opens. It is only a warm-up, not a barrier: `ensureIndex`
        // is @Synchronized and idempotent, so a caller that arrives first still
        // gets a correct (merely slower) answer, and one that arrives during the
        // build simply waits for it. Failure is non-fatal — an unbuilt index
        // means models fall back to their unranked order, never a crash.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.openminis.app.provider.ModelReleaseIndex.warmUp() }
                .onFailure {
                    com.openminis.app.logging.AppLogger.warning(
                        "MinisApp", "release-index warm-up failed: ${it.message}"
                    )
                }
        }


        // Initialize sandbox singletons (does not trigger extraction)
        RootfsManager.getInstance(this)
        ExecutionCoordinator.init(this)
        ExecutionCoordinator.envVarRepository = envVarRepository

        // Privacy Mode store + redactor wiring. Mirrors iOS
        // EnvVarPrivacyStore.init / EnvVarRedactor static handoff.
        com.openminis.app.data.EnvVarPrivacyStore.init(this)
        com.openminis.app.data.EnvVarRedactor.envVarRepository = envVarRepository

        // Start network monitoring — mirrors iOS NetworkMonitor.shared.start().
        // The monitor writes /etc/resolv.conf immediately and on every
        // ConnectivityManager callback so shells inside the sandbox see fresh
        // DNS servers after Wi-Fi ↔ cellular swaps or VPN toggles.
        networkMonitor.start(this)

        // Register global /var/minis/{memory,skills,shared} bind mounts up-front
        // so direct file I/O tools (file_read) resolve these paths even before
        // PRoot has booted or any shell has started.
        PRootKernel.registerGlobalBindMounts(this)

        // T219-1: load user-mounted external folders and seed PRoot's
        // bindMounts before the first proot invocation, so the very first
        // `shell_execute` already has `/var/minis/mounts/<name>/` visible.
        // Entries whose SAF tree URI didn't resolve to a real POSIX path
        // (cloud providers, unmounted SD card) are silently skipped by
        // bindMountSpecs.
        mountedFoldersStore = MountedFoldersStore(this)
        // T219-5: hand the singleton to PRootKernel so applyMountedFoldersSnapshot
        // can read the live state, and wire an onChange callback so any UI CRUD
        // (add/remove/rename/toggle) re-applies the snapshot.
        // T277: PersistentShell reuses one PRoot process per chat session for the
        // session's lifetime, so an applyMountedFoldersSnapshot call alone never
        // reaches the live shell — proot's `-b` argv is frozen at spawn time.
        // Kill any live shells so the next execute() rebuilds them with the
        // updated bind set. Mount CRUD is a Settings-screen action; the user
        // is not in chat mid-command, so this restart is safe and user-invisible.
        PRootKernel.mountedFoldersStore = mountedFoldersStore
        mountedFoldersStore.onChange = {
            PRootKernel.applyMountedFoldersSnapshot(this)
            ExecutionCoordinator.stopCurrentCommand()
        }
        // T219-6: route launch-time seeding through applyMountedFoldersSnapshot
        // so it (a) reads the live store consistently and (b) materializes the
        // /var/minis/mounts/<name> placeholder dirs that PRoot's `-b` needs.
        // Note: this runs before PRootKernel.boot, so rootfs may not yet exist —
        // applyMountedFoldersSnapshot tolerates that case (mkdirs fails silently
        // and PRootKernel.boot calls applyMountedFoldersSnapshot again at the
        // end of boot to materialize the targets once rootfs is on disk).
        PRootKernel.applyMountedFoldersSnapshot(this)

        // Register native_offload handlers and start the server eagerly —
        // the server only needs the rootfs tmp directory, which can be
        // materialized lazily. Starting here means the abstract socket is
        // reachable even before any shell session is launched.
        NativeOffloadServer.register("android-alarm", AlarmOffloadHandler(this))
        NativeOffloadServer.register("android-calendar", CalendarOffloadHandler(this))
        NativeOffloadServer.register("android-clipboard", ClipboardOffloadHandler(this))
        NativeOffloadServer.register("android-contacts", ContactsOffloadHandler(this))
        NativeOffloadServer.register("android-device", DeviceOffloadHandler(this))
        NativeOffloadServer.register("android-location", LocationOffloadHandler(this))
        NativeOffloadServer.register("android-notification", NotificationOffloadHandler(this))
        NativeOffloadServer.register("android-open", OpenOffloadHandler(this))
        NativeOffloadServer.register("android-photos", PhotosOffloadHandler(this))
        NativeOffloadServer.register("android-player", PlayerOffloadHandler())
        NativeOffloadServer.register("android-speak", SpeakOffloadHandler(this))
        NativeOffloadServer.register("android-speech", SpeechOffloadHandler(this))
        NativeOffloadServer.register("android-weather", WeatherOffloadHandler(this))
        // T323: UI-layer automation backed by MinisAccessibilityService.
        NativeOffloadServer.register("android-a11y-cli", AccessibilityOffloadHandler(this))
        NativeOffloadServer.register("minis-model-use", ModelUseOffloadHandler(this, providerRepository))
        // T-config: minis-config — agent-facing settings management
        // (read/write registered ConfigFields with audit + revert).
        // Mirrors iOS `config_offload_register()` in ISHKernel.m.
        NativeOffloadServer.register(
            "minis-config",
            com.openminis.app.sandbox.offload.ConfigOffloadHandler(),
        )
        NativeOffloadServer.register("minis-browser-use", BrowserUseOffloadHandler(this))
        // T188: minis-sessions-cli — agent-side query of chat history.
        // Registers next to the other minis-* tools so PRootKernel.
        // installHandlerStubs() picks it up on the next rootfs boot
        // (writes a 17-byte exit-0 stub at /usr/local/bin/minis-sessions-cli
        // so PATH lookup succeeds; PRoot intercepts the execve before
        // the stub runs and routes to this handler).
        NativeOffloadServer.register("minis-sessions-cli", SessionsOffloadHandler(chatRepository))
        // [T-android-scheduled-tasks-full] minis-scheduled — create/list/run
        // timed AI tasks (new chat / follow-up / re-run), mirroring the in-app
        // Scheduled Tasks editor and the iOS Shortcuts intent set.
        NativeOffloadServer.register(
            "minis-scheduled",
            com.openminis.app.sandbox.offload.ScheduledTaskOffloadHandler(this),
        )
        // T322: android-shizuku-cli — privileged Android control via Shizuku.
        // The handler short-circuits with a typed error envelope when the
        // user hasn't installed / started / authorized Shizuku, so we
        // can register unconditionally; ShizukuManager.init below wires
        // up the binder lifecycle listeners + StateFlow.
        NativeOffloadServer.register("android-shizuku-cli", ShizukuOffloadHandler(this))
        com.openminis.app.offload.ShizukuManager.init(this)

        // T-android-minis-debug-cli: shell-side CLI wrapper around the in-app
        // DebugServer (127.0.0.1:5321) JSON-RPC. DEBUG-only — Release builds
        // ship neither the DebugServer nor this handler, so the
        // `/usr/local/bin/minis-debug` stub is also absent (PRootKernel.
        // installHandlerStubs enumerates currently-registered handlers).
        if (BuildConfig.DEV_TOOLS) {
            NativeOffloadServer.register(
                "minis-debug",
                com.openminis.app.sandbox.offload.DebugOffloadHandler(this),
            )
        }

        NativeOffloadServer.start(RootfsManager.getInstance(this).rootfsDir)

        // Initialize session activity tracker for foreground service management
        SessionActivityTracker.init(this)

        // [T-android-session-paused-badge] Per-session badge-state queue
        // displayed in the session-list cell corner. Init early so the
        // session list can read persisted PAUSED badges on first compose.
        com.openminis.app.service.SessionBadgeStore.init(this)

        // [T-android-session-paused-badge-hardkill] Reconcile PAUSED badges
        // against the DB's interrupted-session set. The lifecycle-callback push
        // (onActivityStarted, below) only fires on a graceful background→
        // foreground round-trip; a hard kill (force-quit / process death) never
        // runs it, so the badge would be missing after restart. The persisted
        // message tail is the durable source of truth — scan it off-main and
        // reconcile. Runs after init() so it merges with the restored queues.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
            // Exclude any session that is already actively streaming (defensive;
            // at cold start this is empty, but keeps the rule "active ⇒ never
            // paused" uniform with the foreground reconcile path).
            val active = SessionActivityTracker.activeSessions.value
            com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
        }

        // [T-android-zombie-child-sweep] Remove hidden sub agent sessions whose
        // parent is gone — killed mid-run, they are invisible to every picker
        // and accumulate with no way for the user to reach them. Runs after the
        // repository is up, on IO, and only at cold start: a restore happens
        // within a single app run, so it can never straddle this.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.openminis.app.data.session.OrphanChildSweeper.sweep(this@MinisApp, chatRepository)
            }.onFailure {
                com.openminis.app.logging.AppLogger.warning("OrphanSweep", "sweep threw: ${it.message}")
            }
        }

        // T180-bg-notif: background-settings + task-completion notifier.
        // The notifier is wired into SessionActivityTracker's completion
        // hook so any session whose stream finishes (success or error)
        // posts a tap-to-open notification when the app is backgrounded.
        // Mirrors iOS BackgroundKeepAliveManager.postBackgroundTaskNotification.
        backgroundSettingsRepository = BackgroundSettingsRepository(this)
        backgroundTaskNotifier = BackgroundTaskNotifier(
            context = this,
            chatRepository = chatRepository,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        SessionActivityTracker.setCompletionListener { sessionId, isError ->
            backgroundTaskNotifier.notifyTaskCompleted(sessionId, isError)
        }

        // [T-android-config-confirm-timeout] Wire the config-confirm background
        // notifier into the (Context-free) gate, so a minis-config approval that
        // is waiting while the app is backgrounded nudges the user before the
        // 120s timeout. Mirrors iOS ConfigConfirmationGate.notifyIfBackgrounded.
        val configConfirmNotifier = com.openminis.app.notification.ConfigConfirmNotifier(
            context = this,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        com.openminis.app.config.confirm.ConfigConfirmationGate.backgroundNotifier = {
            configConfirmNotifier.notifyIfBackgrounded(it)
        }
        com.openminis.app.config.confirm.ConfigConfirmationGate.cancelNotification = {
            configConfirmNotifier.cancel(it)
        }

        // Track foreground state via ActivityLifecycleCallbacks. Counting
        // started/stopped balances out around configuration changes (the
        // Activity is briefly destroyed-then-created, so the count would
        // momentarily drop to zero if we used onResume/onPause). Started/
        // stopped is more conservative — counts non-zero while the
        // Activity is even partially visible.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                val wasBackgrounded = foregroundActivityCount == 0
                foregroundActivityCount++
                if (wasBackgrounded) _isAppForegroundFlow.value = true
                // T298: as soon as the app transitions background → foreground,
                // clear any task-completed notifications still in the tray.
                // The user is back in front of the app — there's no point
                // making them swipe away a "task completed" entry for the
                // result they're about to look at directly.
                if (wasBackgrounded && ::backgroundTaskNotifier.isInitialized) {
                    backgroundTaskNotifier.cancelAllCompletedNotifications()
                }
                // [T-android-session-paused-badge-hardkill] On foreground,
                // reconcile PAUSED badges from the DB tail (authoritative
                // interrupted-state) instead of the old heuristic that marked
                // every STILL-ACTIVE session paused. That heuristic was wrong:
                // a session still in activeSessions after backgrounding kept
                // RUNNING (keep-alive) — it is executing, not paused — which
                // surfaced a ⏸ badge on a live, spinning session. The DB tail
                // only looks "interrupted" for a session whose loop is actually
                // stranded; we additionally exclude currently-active sessions so
                // a mid-loop running session is never flagged.
                if (wasBackgrounded) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
                        val active = SessionActivityTracker.activeSessions.value
                        com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
                    }
                }
            }
            override fun onActivityResumed(activity: Activity) {
                // T-android-crash-freq-share: if checkAtLaunch flagged a
                // recent burst, show the share-logs dialog on the first
                // Activity that resumes. One-shot — clears the pending
                // list internally so config-change re-resumes don't
                // re-prompt. Safe no-op when nothing is pending.
                com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(activity)
                // T219-1: re-probe mounted-folder writability on every
                // foreground resume so OS permission revocations (user
                // toggled "Allow access" off in system Files, removable
                // storage unmounted, etc.) propagate into the UI badge
                // and the read-only enforcement gate. Mirrors iOS
                // MountedFoldersManager.refreshAllWritability().
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { mountedFoldersStore.refreshWritability() }
                }
                // [T-android-models-refresh-window] Re-check the model lists on
                // a foreground return. The call is internally gated by a
                // 6-hour rolling window, so this is a cheap no-op on ordinary
                // app switches and only actually fetches when the window has
                // expired while the app sat in the background.
                //
                // This is what closes the reported gap: a provider that turns
                // a model on mid-session (GitHub enabling gpt-6-astra
                // account-wide) used to be invisible until the next calendar
                // day, because launch was the only trigger. iOS hooks the same
                // event (scenePhase -> .active, fe625d5fd).
                providerRepository.refreshAllModelsIfNeeded(
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                )
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                if (foregroundActivityCount == 0) {
                    _isAppForegroundFlow.value = false
                    // [T-android-config-confirm-timeout] The user switched away
                    // while a config-confirm dialog may still be showing — nudge
                    // them so they can come back before the 120s timeout.
                    com.openminis.app.config.confirm.ConfigConfirmationGate.notifyPending()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Initialize offload permission manager
        OffloadPermissionManager.init(this)

        // Initialize speech-recognition adapter layer (system + provider engines).
        com.openminis.app.speech.SpeechRecognitionManager.init(this)

        // Refresh model lists once per calendar day (mirrors iOS MinisApp.swift).
        // Runs per-instance in parallel; `autoRefreshModels` skips instances with custom models.
        providerRepository.refreshAllModelsIfNeeded(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        )

        // Propagate system timezone and HTTP-proxy changes into the sandbox.
        // iOS recomputes TZ for every command (ISHShellExecutor.m:335-353);
        // here we update PRootKernel.customEnvironment and push `export …`
        // into every live shell so interactive sessions pick up the change
        // without a restart.
        val sandboxSystemReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastTimezoneChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastTimezoneChange failed: ${t.message}")
                        }
                    }
                    android.net.Proxy.PROXY_CHANGE_ACTION -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastProxyChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastProxyChange failed: ${t.message}")
                        }
                    }
                }
            }
        }
        registerReceiver(
            sandboxSystemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(android.net.Proxy.PROXY_CHANGE_ACTION)
            },
        )

        // Debug server: only start in debug builds (NEVER in release)
        if (BuildConfig.DEV_TOOLS) {
            try {
                com.openminis.app.debug.DebugServer(this).start()
            } catch (e: Exception) {
                Log.w("MinisApp", "Failed to start debug server: ${e.message}")
            }
        }

        // T268: one-shot migration of pre-T266 internal alarms into the
        // system Clock app. Pre-T266 builds wrote alarms into Minis's own
        // SharedPreferences + AlarmManager; T266 retired that path but old
        // installs still have ghost entries that fire only inside Minis.
        // Replay each future-dated entry through the same SET_ALARM /
        // SET_TIMER intents the new path uses, then clear prefs so the
        // migration runs at most once. Wrapped in runCatching so an
        // unexpected prefs shape never blocks app launch.
        runCatching { migrateGhostAlarms() }
            .onFailure { Log.w("MinisApp", "ghost alarm migration failed: ${it.message}") }
    }

    /**
     * T268: replay any pre-T266 internal alarm/timer entries from
     * minis_alarms_prefs through SET_ALARM / SET_TIMER, then clear the
     * prefs blob so subsequent launches no-op. Past-dated entries are
     * dropped (the OS never re-fires them anyway). Idempotent: if the
     * blob is missing or empty the function returns immediately.
     *
     * Silent migration rather than an in-app dialog — Application has no
     * Activity context to host one, and the user-visible outcome (alarms
     * reappear in their Clock app) is what they want regardless of any
     * prompt. AlarmOffloadManager's PendingIntents are left in place; the
     * OS will fire them once more if scheduled, but T268 also clears the
     * prefs blob that AlarmOffloadHandler previously read, so list/cancel
     * commands will no longer surface them.
     */
    private fun migrateGhostAlarms() {
        val prefs = getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("alarms_json", null) ?: return
        if (raw.isBlank() || raw == "[]") return
        val arr = org.json.JSONArray(raw)
        if (arr.length() == 0) {
            prefs.edit().remove("alarms_json").apply()
            return
        }
        val now = System.currentTimeMillis()
        var migrated = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val triggerAt = entry.optLong("triggerAtMs", 0L)
            if (triggerAt in 1L..now && entry.optString("type") == "timer") {
                skipped++; continue  // Past timer — nothing to recover.
            }
            if (triggerAt in 1L..now && entry.optString("repeatMode", "ONCE") == "ONCE") {
                skipped++; continue  // Past one-shot alarm.
            }
            val migrationOk = runCatching {
                if (entry.optString("type") == "timer") {
                    val secs = entry.optInt("durationSec", -1)
                    val remaining = ((triggerAt - now) / 1000L).toInt()
                    if (remaining <= 0 && secs <= 0) return@runCatching false
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (remaining > 0) remaining else secs)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Timer"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                } else {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, entry.optInt("hour", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, entry.optInt("minute", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Alarm"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (migrationOk) migrated++ else skipped++
        }
        // Clear the blob unconditionally — entries we couldn't replay are
        // still useless ghosts, and leaving the blob would re-trigger
        // migration on every launch.
        prefs.edit().remove("alarms_json").apply()
        Log.i("MinisApp", "T268 ghost alarm migration: migrated=$migrated skipped=$skipped (prefs cleared)")
    }

    /**
     * Coil global ImageLoader — registers [MinisImageFetcher] so `minis://`
     * URIs in Markdown images (e.g. `![alt](minis://attachments/x.png)`)
     * resolve to local files under /var/minis/.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(MinisImageFetcher.Factory())
                add(MinisImageFetcher.UriFactory())
                // T-image-cache-mtime-35133: include File.lastModified() in
                // memory + disk cache key so Grok-style in-place rewrites of
                // minis://attachments/foo.jpg invalidate Coil's cached bitmap.
                add(MinisImageFetcher.MtimeKeyer())
                add(MinisImageFetcher.StringMtimeKeyer())
            }
            .build()

    /**
     * [GH#206] Respond to system memory pressure.
     *
     * The app previously implemented NOTHING here (no `onTrimMemory`, no
     * `onLowMemory` anywhere in the codebase), so every warning the system sent
     * on the way to an OOM was ignored and the only remaining lever was killing
     * the process. The reported failure had the app pinned at a ~1.94 GB native
     * heap while ART ran 478 concurrent-copying GCs in 30 minutes without
     * recovering — expected, because the memory sat in NATIVE bitmap pixels that
     * Java GC cannot reclaim. These caches are exactly that memory, and every
     * entry is reconstructible by re-rendering, so dropping them is free apart
     * from a re-render.
     *
     * Deliberately staged rather than "clear everything on any signal":
     *   - RUNNING_LOW and above (and any BACKGROUND-class level, which means we
     *     are on the LRU list and cheap to kill): drop the formula bitmaps.
     *   - COMPLETE: additionally tear down the offscreen KaTeX WebView, which
     *     is itself a large native allocation. It is recreated on next render.
     *
     * RUNNING_MODERATE is intentionally NOT acted on: it fires routinely on
     * healthy devices, and re-rendering formulas on every mild dip would trade a
     * real user-visible cost for little memory.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val dropFormulaCaches = when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (!dropFormulaCaches) return

        Log.i("MinisApp", "onTrimMemory(level=$level): releasing formula bitmap caches")
        runCatching { com.openminis.app.ui.chat.KatexWebViewPool.evictAll() }
            .onFailure { Log.w("MinisApp", "KatexWebViewPool.evictAll failed: ${it.message}") }
        runCatching { com.openminis.app.ui.markdown.KaTeXRendererCache.evictAll() }
            .onFailure { Log.w("MinisApp", "KaTeXRendererCache.evictAll failed: ${it.message}") }

        // [T-android-browser-global-tab-cap] Browser tabs are the largest
        // reclaimable allocation in the process — each is an out-of-process
        // WebView renderer — so they go at the same staged threshold as the
        // formula bitmaps above rather than waiting for COMPLETE. Only IDLE
        // tabs are released; a tab an agent is mid-action on stays, because
        // failing a running task is a worse outcome than the memory it holds,
        // and the registry can still preempt it later if pressure persists.
        runCatching {
            com.openminis.app.browser.BrowserTabPoolRegistry.handleMemoryPressure()
        }.onFailure { Log.w("MinisApp", "BrowserTabPoolRegistry.handleMemoryPressure failed: ${it.message}") }

        // [T-android-trimmemory-vmstore] Cached ChatViewModels are the other
        // large reclaimable allocation: each holds a session's messages,
        // agentHistory and flattened render rows. ChatViewModelStore's own cap
        // is a soft, count-based LRU that only trims when a NEW session is
        // added, so four legitimately-cached heavy sessions survive any amount
        // of external memory pressure. This is the path that gives them back.
        //
        // RUNNING_CRITICAL and above only — deliberately one step stricter than
        // the formula/browser caches above. Dropping a cached session costs a
        // full DB reload + re-flatten when the user navigates back to it, which
        // is far more visible than re-rendering a formula bitmap, so it is not
        // worth doing on RUNNING_LOW. Sessions running an agent loop and the
        // on-screen session are exempt inside the store.
        // NOT a `level >= RUNNING_CRITICAL` test. The constants are not a
        // severity ladder: UI_HIDDEN (20) outranks RUNNING_CRITICAL (15) but
        // means only "the UI went away", which happens on every home-button
        // press. Treating that as pressure would throw away every cached
        // session on a routine app switch and make coming back slow for no
        // memory reason. Enumerate the levels that actually mean "reclaim".
        val dropCachedSessions = when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,   // still foreground, genuinely low
            TRIM_MEMORY_BACKGROUND,         // on the LRU list, cheap to kill
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (dropCachedSessions) {
            runCatching {
                val freed = com.openminis.app.ui.chat.ChatViewModelStore.handleMemoryPressure()
                Log.i("MinisApp", "onTrimMemory(level=$level): released $freed cached session store(s)")
            }.onFailure { Log.w("MinisApp", "ChatViewModelStore.handleMemoryPressure failed: ${it.message}") }
        }

        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.i("MinisApp", "onTrimMemory(level=$level): tearing down the offscreen KaTeX WebView")
            runCatching { com.openminis.app.ui.chat.KatexWebViewPool.releaseWebView() }
                .onFailure { Log.w("MinisApp", "KatexWebViewPool.releaseWebView failed: ${it.message}") }
        }
    }

    override fun onTerminate() {
        // onTerminate is called only on emulators or when the system
        // explicitly tears down — real devices usually skip it. Still
        // worth marking the beacon: a present clean_exit on a real
        // device proves we shut down voluntarily; its absence is the
        // signal we care about for [LaunchCycleBeacon].
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordCleanExit(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordCleanExit failed: ${t.message}")
        }
        super.onTerminate()
    }

}
