import SwiftUI
import ObjectiveC
import FileProvider
import UserNotifications

private let shareLog = AppLogger(category: "Share")
private let lifecycleLog = AppLogger(category: "Lifecycle")

// MARK: - Bundle Language Override

/// Overrides `Bundle.main.localizedString(forKey:value:table:)` so that
/// `String(localized:)` and UIKit strings respect the in-app language setting
/// without requiring an app restart.
extension Bundle {
    private static var overrideBundleKey: UInt8 = 0

    /// The language-specific `.lproj` bundle currently in use, or `nil` for system default.
    var languageBundle: Bundle? {
        get { objc_getAssociatedObject(self, &Self.overrideBundleKey) as? Bundle }
        set { objc_setAssociatedObject(self, &Self.overrideBundleKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }

    /// Call once at launch to swizzle `localizedString(forKey:value:table:)`.
    static func enableLanguageOverride() {
        let original = class_getInstanceMethod(Bundle.self, #selector(localizedString(forKey:value:table:)))!
        let swizzled = class_getInstanceMethod(Bundle.self, #selector(overrideLocalizedString(forKey:value:table:)))!
        method_exchangeImplementations(original, swizzled)
    }

    @objc private func overrideLocalizedString(forKey key: String, value: String?, table tableName: String?) -> String {
        if self == Bundle.main, let bundle = languageBundle {
            return bundle.overrideLocalizedString(forKey: key, value: value, table: tableName)
        }
        return overrideLocalizedString(forKey: key, value: value, table: tableName) // calls original (swizzled)
    }

    /// Sets the override language. Pass `nil` or `""` to revert to system language.
    static func setLanguage(_ code: String?) {
        guard let code, !code.isEmpty,
              let path = Bundle.main.path(forResource: code, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            Bundle.main.languageBundle = nil
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
            return
        }
        Bundle.main.languageBundle = bundle
        UserDefaults.standard.set([code], forKey: "AppleLanguages")
    }
}

extension Notification.Name {
    static let newChatRequested = Notification.Name("newChatRequested")
    /// Posted when a new session is persisted to the database. `object` is the session ID (`String`).
    static let sessionDidCreate = Notification.Name("sessionDidCreate")
    /// Posted when a session's title or messages are updated.
    static let sessionDidUpdate = Notification.Name("sessionDidUpdate")
    /// Posted when an agent loop ends on a VM that is not the currently-displayed one.
    /// `object` is the session ID (`String`). Allows the active VM to reload from DB.
    static let sessionAgentLoopDidEnd = Notification.Name("sessionAgentLoopDidEnd")
    /// Posted when the user wants to move input (text + attachments) to another session.
    /// userInfo: ["targetId": String]
    static let moveInputToSession = Notification.Name("moveInputToSession")
    /// Posted when a session's model binding changes (group or direct entry).
    /// userInfo: ["sessionId": String] and optionally ["groupId": String] when bound to a group.
    static let sessionModelBindingChanged = Notification.Name("sessionModelBindingChanged")

    /// Posted by any path that's about to take over the screen (incoming
    /// share, WebApp deep-link launch). Every fullScreenCover host —
    /// AIChatView, the WindowGroup root — listens and dismisses its
    /// covers so the new content actually surfaces instead of getting
    /// stuck behind a leftover WebView / camera / gallery sheet.
    static let dismissAllImmersivePresentations = Notification.Name("dismissAllImmersivePresentations")
}

@main
struct MinisApp: App {
    /// [T-voice-input-mode-preference-ios] Process launch instant, pinned in
    /// the App initializer (Swift statics are lazy — referencing it from
    /// init() makes it accurate). Used to tell a cold-launch LANDING chat
    /// apart from a chat the user navigated into minutes later.
    static let processLaunchedAt = Date()

    // Minimal UIApplicationDelegate adapter — needed only to receive
    // `UIApplicationShortcutItem` events (Home Screen Quick Actions).
    // SwiftUI's pure App lifecycle has no equivalent surface.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @AppStorage("appearanceMode") private var appearanceMode: Int = 0
    @AppStorage("appLanguage") private var appLanguage: String = ""
    @StateObject private var shareCoordinator = ShareCoordinator.shared
    @ObservedObject private var fontSettings = FontSettings.shared
    @ObservedObject private var configConfirmGate = ConfigConfirmationGate.shared
    /// [review S14] Drives the root-level restore sheet for a `.minisbak`
    /// opened from outside the app.
    @ObservedObject private var openRouter = BackupOpenRouter.shared
    /// Observed so the restore sheet re-evaluates when the app locks/unlocks.
    @ObservedObject private var sessionLockStore = SessionLockStore.shared
    @Environment(\.scenePhase) private var scenePhase
    /// Set by the OpenWebAppIntent notification observer; drives a fullScreenCover
    /// presenting `WebAppWebViewScreen`. Cleared when the user dismisses the
    /// immersive WebView (back-edge swipe / programmatic dismiss).
    @State private var pendingWebAppPresentation: WebAppPresentation?
    @State private var pendingURLWhileLocked: URL?

    #if DEBUG
    let debugServer = DebugServer()
    #endif

    /// Tracks when the app entered background for duration logging.
    @State private var backgroundEntryDate: Date?

    init() {
        // [T-ios-mac-uncaught-nsexception] FIRST statement in the process's own
        // code — before any subsystem gets a chance to throw.
        //
        // This used to run only from CrashReporter.onAppLaunch(), which is
        // dispatched from the scenePhase→.active handler AFTER an `await
        // Task.yield()`. Everything before that point — the rest of this init,
        // the entire first view-graph build, and any main-queue block enqueued
        // during it — ran with NO uncaught-exception handler installed, so an
        // NSException there was reported by AppKit with the reason field that
        // the App Store crash report then strips.
        //
        // That window is where the two 1.13(4) macOS crashes landed: the stack
        // bottoms out in NSApplicationMain → -[NSApplication run] with no frame
        // of ours below `MinisApp.$main()`, i.e. a main-runloop turn during
        // startup, not a user gesture. Installing here does not fix a throw, it
        // makes the next one say what it was.
        //
        // `install` is idempotent, so the later onAppLaunch() call is a no-op.
        CrashSignalHandler.install()
        // [T-auto-grouping-default-on] Auto-grouping ships ON. `bool(forKey:)`
        // returns false for an unregistered key, so the default has to be
        // registered here rather than expressed at the (multiple) read sites —
        // registration also leaves an explicit user choice untouched, which a
        // read-site `?? true` would too, but only if every site remembered it.
        //
        // This reverses the original opt-in decision ("moves user data without
        // being asked"). The concern is bounded: the feature only files a chat
        // into a group the user already created, only when the model is
        // confident, only once per chat, and never over a hand-filed session
        // (setFolderIfUnfiled). Android defaults ON to match.
        UserDefaults.standard.register(defaults: ["autoGroupingEnabled": true])
        // [T-voice-input-mode-preference-ios] Pin the lazy static to the real
        // launch instant.
        _ = Self.processLaunchedAt
        #if DEBUG
        try? debugServer.start(port: 8321)
        #endif
        // Install the NSTextContainer setSize: reentrancy guard before any
        // UITextView gets created. Breaks the iOS 26 TextKit1 fillLayoutHole
        // storm that has caused 0x8BADF00D scene-update watchdog kills on
        // markdown tables during streaming.
        NSTextContainerSetSizeGuard.install()
        // Install the attribute:atIndex:effectiveRange: recorder so the
        // last 10 typesetter attribute queries land in a ring buffer; the
        // HangDetector dump path will print them when a stall fires.
        AttributeQueryRecorder.install()
        // Enable in-app language override for String(localized:) and UIKit strings
        Bundle.enableLanguageOverride()
        let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? ""
        Bundle.setLanguage(lang.isEmpty ? nil : lang)
        // [T-ios-soul-name-sidebar-stale] Pre-load cachedMetadata synchronously so
        // ContentView's `@State soulName` gets the real SOUL.md name on its very
        // first render instead of the `.default` stub ("Minis"). Without this the
        // @State initializer (evaluated at ContentView instantiation, before any
        // .onAppear refresh) locks the sidebar title to the default even when the
        // user set a custom name. refreshCache() only reads the tiny SOUL.md file.
        SoulStore.refreshCache()
        // Pre-warm KaTeX WKWebView as fallback for formulas SwiftMath can't render
        KaTeXRenderer.shared.warmUp()
        // Pre-warm the biometric capability probe off the main thread. The
        // first LAContext.canEvaluatePolicy call cold-starts the
        // LocalAuthentication XPC daemon (~500 ms); without this it would run
        // inline on the first sessionContextMenu builder during scroll and
        // hang a frame. (T-ios-biometric-probe-scroll-hang)
        BiometricAuth.prewarm()
        // Clean up Live Activities left over from a previous app session (e.g. app was killed)
        AgentLiveActivityManager.shared.cleanupStaleActivities(source: "MinisApp.init")
        // Start screen-awake controller — it will observe running tasks
        // + the user's opt-in flag and toggle the idle timer accordingly.
        Task { @MainActor in KeepScreenAwakeController.shared.start() }
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView()
                    .overlay(alignment: .top) {
                        BackgroundInterruptionBanner()
                    }
                AudioPiPCapsule()
                // Global read-replies capsule — a SINGLE app-root instance driven by
                // VoiceOutputState, so it persists across chat → home (no per-session
                // copy). It lays itself out full-screen (bottom-trailing capsule +
                // tap-to-dismiss catcher), so no positioning wrapper here.
                SpeechPlayerControl()
                AppLockOverlay()
            }
                .onReceive(SessionLockStore.shared.$appIsLocked) { locked in
                    guard !locked, let url = pendingURLWhileLocked else { return }
                    pendingURLWhileLocked = nil
                    if BackupOpenRouter.handle(url) {
                        // .minisbak → restore flow, not the attachment pipeline.
                    } else if ExternalFileImporter.canIngest(url) {
                        ExternalFileImporter.ingest(url, into: shareCoordinator)
                        return
                    }
                    DeepLinkRouter.handle(url: url, shareCoordinator: shareCoordinator)
                }
                // Force a full ContentView rebuild whenever the user-selected
                // language changes. Without this, SwiftUI keeps Text/Label
                // bodies that were already materialized in their original
                // localization — String(localized:) is only re-evaluated when
                // the owning view's body re-runs, and many setting/dashboard
                // sections (iCloud Sync, Appearance, etc.) hold cached strings
                // captured at first render. Bundle.setLanguage swizzles new
                // lookups but does not invalidate the existing view tree.
                // Re-keying the root drops + re-mounts every descendant,
                // re-running their bodies under the new languageBundle.
                .id(appLanguage)
                // Confirmation gate for every minis-config write. Mounted
                // at the root so the sheet appears regardless of which
                // screen is active when the agent triggers a change.
                // Bind to the @ObservedObject's published `pending` so
                // SwiftUI re-evaluates the sheet when the gate enqueues
                // a request — a plain `Binding(get:)` on the singleton
                // would not subscribe to the publisher.
                .sheet(item: Binding(
                    get: { configConfirmGate.pending },
                    set: { _ in /* dismissal goes through gate.userReject() */ }
                )) { _ in
                    ConfigConfirmSheet(gate: configConfirmGate)
                }
                // [review S14] Restore flow for a `.minisbak` opened from
                // Files / AirDrop / a share sheet. Mounted HERE rather than in
                // BackupSettingsView, which was the only observer before: the
                // user opening a backup is almost always mid device-migration
                // and standing on the chat list, so setting `pendingPackage`
                // did nothing visible until they happened to walk into
                // Settings — at which point a restore sheet appeared
                // unprompted. This is the primary migration entry point, so it
                // has to work from wherever the user actually is.
                //
                // Gated on the lock state, and deliberately at the sheet rather
                // than at each call site: AppLockOverlay is a ZStack sibling, so
                // a sheet presents OVER it. `handle()` runs from three places
                // (onOpenURL, the unlock replay, and AppDelegate's scene URL
                // path for a cold launch) and only the first checks the lock —
                // so a locked device could otherwise show a restore sheet, with
                // the package's device name and contents, to whoever is holding
                // the phone. Gating the presentation covers every path at once.
                // The pending package survives here until unlock, so nothing is
                // lost — `appIsLocked` publishing flips this back on.
                .sheet(item: Binding(
                    get: { sessionLockStore.appIsLocked ? nil : openRouter.pendingPackage },
                    set: { openRouter.pendingPackage = $0 }
                )) { pending in
                    NavigationStack {
                        // Opens on the RESTORE tab with the package already
                        // loaded. Someone who just tapped a .minisbak is mid
                        // device-migration — landing them on the backup form
                        // and making them find the switch would be exactly
                        // backwards.
                        BackupAndRestoreView(initialTab: .restore,
                                             initialPackageURL: pending.url)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close") { openRouter.pendingPackage = nil }
                                }
                            }
                    }
                }
                .environmentObject(shareCoordinator)
                .preferredColorScheme(
                    appearanceMode == 1 ? .light : appearanceMode == 2 ? .dark : nil
                )
                .environment(\.locale, appLanguage.isEmpty ? .current : Locale(identifier: appLanguage))
                .dynamicTypeSize(fontSettings.appBaseScale.dynamicTypeSize)
                .onOpenURL { url in
                    shareLog.info("[Share] onOpenURL: \(url.absoluteString)")
                    guard !SessionLockStore.shared.appIsLocked else {
                        shareLog.info("[Share] onOpenURL deferred — app is locked")
                        pendingURLWhileLocked = url
                        return
                    }
                    if BackupOpenRouter.handle(url) {
                        // .minisbak → restore flow, not the attachment pipeline.
                    } else if ExternalFileImporter.canIngest(url) {
                        ExternalFileImporter.ingest(url, into: shareCoordinator)
                        return
                    }
                    DeepLinkRouter.handle(url: url, shareCoordinator: shareCoordinator)
                }
                // Fullscreen immersive WebView for HTML web-app shortcuts.
                // Driven by `.openWebAppDeepLink` (posted by DeepLinkRouter
                // for `minis://open?session=…&path=…`). Mounted at the
                // WindowGroup root so it covers the chat list / draft / any
                // other foreground state.
                // [T-ios-remove-open-webapp-shortcut-intent] The
                // `.openWebAppFromIntent` path (Home-Screen-pinned Shortcut →
                // OpenWebAppIntent) was removed; only the deep-link entry
                // point remains.
                .fullScreenCover(item: $pendingWebAppPresentation) { pres in
                    WebAppWebViewScreen(shortcut: pres.shortcut, resolved: pres.resolved)
                        .ignoresSafeArea()
                        .statusBar(hidden: true)
                }
                .onReceive(NotificationCenter.default.publisher(for: .openWebAppDeepLink)) { note in
                    guard !SessionLockStore.shared.appIsLocked else { return }
                    guard let shortcut = note.userInfo?["shortcut"] as? WebAppShortcut else { return }
                    Task { @MainActor in
                        Self.presentWebAppDeepLink(shortcut: shortcut, into: $pendingWebAppPresentation)
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: .dismissAllImmersivePresentations)) { _ in
                    // Tear down any open WebApp fullScreenCover so the
                    // upcoming share / deep-link target can surface.
                    // Re-presenting a WebApp goes through this same path
                    // (DeepLinkRouter posts dismiss → waits → posts
                    // .openWebAppDeepLink), so a peer that wants to show
                    // a different WebApp also benefits from this clear.
                    if pendingWebAppPresentation != nil {
                        pendingWebAppPresentation = nil
                    }
                }
                .onAppear {
                    // Populate ConfigRegistry once. Idempotent — every
                    // appearance after the first is a no-op.
                    ConfigRegistry.shared.registerBuiltinsIfNeeded()
                    // Register notification delegate for shortcut task tap-to-open
                    ShortcutNotificationDelegate.shared.register()
                    // Register App Shortcuts with the system so Siri and Spotlight discover them
                    if #available(iOS 17.0, *) {
                        MinisShortcutsProvider.updateAppShortcutParameters()
                    }
                    // Start logging if previously enabled
                    LoggingManager.shared.startIfEnabled()
                    // HangFix(2026-05-14) — always-on hang detector. Was
                    // previously gated to `isProcessing == true`, but the
                    // user can also hit hang while just browsing a session
                    // that contains large attachment-dense markdown.
                    // Acquired once at launch, never released; HangDetector
                    // itself is cheap (100ms poll on a background thread,
                    // observer write on each runloop hop).
                    StreamingHangLogger.shared.acquire(reason: "app-launch always-on")
                    // [T-ios-backup-shared-leak] One-shot migration: an earlier
                    // build delivered backup packages into shared/Backups/,
                    // which is bind-mounted into the guest at /var/minis/shared
                    // and is itself the Shared Files backup category. Move any
                    // leftovers out of the agent-visible workspace.
                    BackupDelivery.migrateLegacySharedBackups()
                    // [T-ios-backup-rollback-persistence] If a restore was
                    // killed mid-flight, put back whatever snapshot survived
                    // and log it — previously nothing knew a restore had even
                    // been interrupted.
                    BackupRestoreJournal.reconcileAtLaunch()
                    // [review I6] Remove staging trees no marker points at.
                    // `defer` cleanup only runs when the process survives, so
                    // an export killed mid-flight used to leave hundreds of MB
                    // in tmp/ that nothing ever swept.
                    BackupExportJournal.sweepAbandoned()
                    // A run still marked .running means the app died mid
                    // backup; without this the list shows a spinner forever
                    // for something that will never finish.
                    BackupHistory.shared.reconcileInterrupted()
                    // NOTE: deliberately NOT releasing the backup keep-alive
                    // here. It looks like useful belt-and-braces and is
                    // actually all downside:
                    //   - it cannot recover anything. `activated` is a static
                    //     in memory, so a killed process zeroes it (and the
                    //     audio session dies with the process anyway) — on the
                    //     next launch the call is a no-op;
                    //   - the ONLY state in which it would do something is when
                    //     a backup is genuinely running, and .onAppear has no
                    //     once-guard, so a root-view rebuild would cut the
                    //     session of a live backup and leave it ~30s of
                    //     beginBackgroundTask before iOS suspends it.
                    // Ownership is enforced where it belongs, by the run token
                    // in BackupRunController.finished(token:).
                    // Migrate legacy provider config on first launch after upgrade
                    ProviderMigration.migrateIfNeeded(store: ProviderConfigStore.shared)
                    // Refresh model lists once per day to keep them current
                    ProviderConfigStore.shared.refreshAllModelsIfNeeded()
                    // [T-mimo-shadow-voice] One-time upgrade fix: force-refresh
                    // mixed-modality providers (MiMo/DashScope) mis-classified by
                    // the old voice-only whitelist, so their text models + shadow
                    // voice rows recover promptly without waiting for a natural refresh.
                    ProviderConfigStore.shared.migrateVoiceModalityIfNeeded()
                    // For existing users with no model groups, create a default group silently
                    Task { await ProviderConfigStore.shared.createDefaultGroupIfNeeded() }
                    shareLog.info("[Share] onAppear — checking for pending share")
                    shareCoordinator.checkForPendingShare()
                    // Set up background keep-alive manager
                    BackgroundKeepAliveManager.shared.setup()
                    // Monitor network changes to keep iSH DNS up to date
                    NetworkMonitor.shared.start()
                    // Register FileProvider domain for shared files
                    Self.registerFileProviderDomain()
                    // Migrate legacy shared dir to App Group container
                    Self.migrateSharedDirToAppGroup()
                    // Trace the resolved AppGroup paths so we can confirm the
                    // main app, FileProvider extension, and iSH bind mount all
                    // agree on which directory holds the user's shared files.
                    Self.logFPSyncTracePaths()
                    // Start watching shared/skills/memory subtrees so iSH writes
                    // and FileBrowserView mutations propagate to the Files app.
                    AppGroupChangeWatcher.shared.start()
                    // Activate security scopes for user-mounted external folders
                    // (e.g. Obsidian vault in iCloud Drive). Held for app lifetime.
                    MountedFoldersManager.shared.activateAll()
                    // Create /var/minis/mounts/<name> symlinks in the fakefs now
                    // that the rootfs exists and mounts are active.
                    AIChatViewModel.refreshMountedFolderSymlinks()
                    // Start iCloud sync engine. v2 takes precedence when its
                    // feature flag is on (see SyncV2Bootstrap); v1 stays
                    // paused while v2 is active. When v2 is off, v1 boots
                    // exactly as before.
                    if #available(iOS 17.0, *) {
                        Task { @MainActor in
                            await SyncV2Bootstrap.startIfEnabled()
                            if !SyncV2Bootstrap.shouldPauseV1() {
                                await CloudSyncEngine.shared.start()
                            }
                        }
                    }
                }
        }
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("New Chat") {
                    NotificationCenter.default.post(name: .newChatRequested, object: nil)
                }
                .keyboardShortcut("n", modifiers: .command)
            }
        }
        .onChange(of: scenePhase) { newPhase in
            handleScenePhaseChange(newPhase)
        }
    }

    // MARK: - Lifecycle Logging

    private func handleScenePhaseChange(_ phase: ScenePhase) {
        switch phase {
        case .active:
            let remaining = UIApplication.shared.backgroundTimeRemaining
            if let entry = backgroundEntryDate {
                let elapsed = Date().timeIntervalSince(entry)
                lifecycleLog.info("[Lifecycle] → Active (was background for \(String(format: "%.1f", elapsed))s, remaining: \(Self.formatTimeRemaining(remaining)))")
            } else {
                lifecycleLog.info("[Lifecycle] → Active (remaining: \(Self.formatTimeRemaining(remaining)))")
            }
            backgroundEntryDate = nil

            // [T-new-session-hang-credential-cache] L2 invalidation hook #4:
            // foreground backstop. Credentials may have changed while backgrounded
            // (another device edited a provider and it synced, a Keychain item
            // rotated) in ways the precise hooks (#1-#3) might not have observed
            // while suspended. Clear the credential cache once on resume so the
            // first resolve re-reads truth.
            ProviderCredentialCache.shared.invalidateAll()
            // [T-ios-provider-row-keychain-in-body] Same backstop for the
            // Providers-list row cache — foreground resume can follow a
            // credential change that never bumped `authRevision`.
            ProviderRowCredentialCache.shared.invalidateAll()

            // Unified audio re-assertion on foreground return: stop the background
            // keep-alive track and re-apply the correct session category for the
            // current intent (reply TTS / capture), fixing stale-category silence.
            AudioSessionCoordinator.shared.reassertForForeground()

            // [T-ios-scenephase-active-sigkill] ALL foreground-resume work is
            // deferred off the synchronous scenePhase→.active callback by one
            // yield. Running ANY non-trivial work inside the callback stalls the
            // main thread at the exact moment SwiftUI tears down / re-evaluates
            // the view graph for the foreground transition. The stall overlaps a
            // ChatSession ModifiedContent modifier-chain destroy and the witness-
            // table deref hits freed memory → SIGKILL (CODESIGNING Invalid Page).
            // Yielding first lets the view-graph transition settle; all the work
            // runs on a later tick, outside the fragile window.
            Task { @MainActor in
                await Task.yield()

                ViewModelCache.shared.resumeAllStreamingUI()

                SessionLockStore.shared.evaluateAppLock()

                CrashReporter.shared.onAppLaunch()
                CrashReporter.shared.updateMarkerPhase(phase: "active")

                _ = SessionBadgeStore.shared
                // [T-ios-session-paused-badge-hardkill] Reconcile .paused badges
                // against the DB's interrupted-session set. The background-expiry
                // push only fires on a graceful task expiry; a hard kill (jetsam/
                // SIGKILL) never runs it, so the badge would be missing after
                // restart. The persisted message tail is the durable source of
                // truth — scan it on the actor, reconcile on the main actor.
                // [T-ios-group-pause-badge-reconcile-stamp] Carry each tail's
                // own date so a restored marker is stamped with WHEN the
                // session was interrupted, not with "now".
                let interruptedWithDates = await ChatStore.shared.interruptedSessionsWithTailDate()
                let interruptedSessions = Set(interruptedWithDates.keys)
                // Exclude sessions that are actively streaming RIGHT NOW: a
                // resumed/running session's DB tail still looks "interrupted"
                // (mid-loop shape), but it is executing, not paused — flagging it
                // would surface a ⏸ badge on a live, spinning session. Active ⇒
                // never paused. (Mirrors the Android foreground reconcile.)
                let activeNow = SessionActivityTracker.shared.activeSessions
                // [T-ios-group-pause-badge-reconcile-stamp] One-time repair of
                // stamps already polluted on existing installs, then reconcile.
                // Order matters: repair first so a corrected stamp is in place
                // before reconcile decides whether one "survives".
                SessionBadgeStore.shared.repairPollutedPausedStamps(entryDates: interruptedWithDates)
                SessionBadgeStore.shared.reconcileInterruptedSessions(
                    interruptedSessions.subtracting(activeNow),
                    entryDates: interruptedWithDates,
                    trigger: "launch-or-foreground")
                ISHKernel.shared.refreshDns()

                #if DEBUG
                debugServer.restartIfDead(port: 8321)
                #endif

                try? await UNUserNotificationCenter.current().setBadgeCount(0)
                BackgroundInterruptionTracker.shared.checkOnForeground()
                // [T-shortcuts-diag-and-pending] Scan for AppIntent runs that
                // were marked pending but never cleared (i.e. the process was
                // suspended before the completion path ran). Records where the
                // user hadn't enabled Background Keep-Alive at the time get a
                // one-shot guidance notification with the setting to turn on;
                // stale (>24h) records are dropped silently.
                // [T-shortcut-orphan-false-positive] Now async: it verifies
                // against ChatStore whether each orphan actually completed before
                // warning. Wrapped in a Task so scenePhase handling stays
                // synchronous; the scan is advisory and nothing below depends on it.
                Task { await ShortcutRunTracker.checkPendingOnForeground() }
                AgentLiveActivityManager.shared.cleanupStaleActivities(source: "scenePhase.active")
                // [T-ios-live-activity-soft-finish] If a completed task's Live
                // Activity is lingering (soft-finished, awaiting the user), the
                // user is now back in the app — dismiss it.
                AgentLiveActivityManager.shared.dismissFinishedActivityOnForeground()
                SkillStore.shared.reload()

                if #available(iOS 17.0, *) {
                    SkillFilesystemNotifier.shared.drainIfDirtyAsync(reason: "scenePhase active")
                }

                Self.signalFileProvider()
                MountedFoldersManager.shared.refreshAllWritability()

                // Credential-presence diagnostic (Keychain reads off-main)
                let credInstances: [(id: String, type: ProviderType, cred: String, enabled: Bool)] =
                    ProviderConfigStore.shared.instances.map {
                        ($0.id, $0.providerType, $0.credentialType.rawValue, $0.isEnabled)
                    }
                Task.detached(priority: .utility) {
                    let logger = AppLogger(category: "Provider")
                    for inst in credInstances {
                        let hasKey = ProviderKeychainHelper.loadAPIKey(instanceId: inst.id) != nil
                        let hasOAuthTok: Bool
                        switch inst.type {
                        case .anthropic: hasOAuthTok = ProviderKeychainHelper.loadOAuthToken(instanceId: inst.id, as: ClaudeTokenStorage.self) != nil
                        case .gemini: hasOAuthTok = ProviderKeychainHelper.loadOAuthToken(instanceId: inst.id, as: GeminiTokenStorage.self) != nil
                        case .openAI: hasOAuthTok = ProviderKeychainHelper.loadOAuthToken(instanceId: inst.id, as: CodexTokenStorage.self) != nil
                        case .xAI: hasOAuthTok = ProviderKeychainHelper.loadOAuthToken(instanceId: inst.id, as: XAITokenStorage.self) != nil
                        case .kimiCode: hasOAuthTok = ProviderKeychainHelper.loadOAuthToken(instanceId: inst.id, as: KimiTokenStorage.self) != nil
                        default: hasOAuthTok = false
                        }
                        let hasManual = ProviderKeychainHelper.loadOAuthString(instanceId: inst.id, account: "manual-oauth-token") != nil
                        logger.info("appPhase=active instanceId=\(inst.id.prefix(8)) type=\(inst.type.rawValue) cred=\(inst.cred) enabled=\(inst.enabled) hasApiKey=\(hasKey) hasOAuthToken=\(hasOAuthTok) hasManualOAuth=\(hasManual)")
                    }
                }
            }
            // Trigger iCloud sync on foreground resume: fetch remote changes + send local dirty records.
            // Route to whichever engine is active. v1 must stay quiet whenever
            // v2 has taken over (SyncV2Bootstrap.shouldPauseV1 == true) —
            // otherwise v1's triggerFetch round-trips v1 records every scene-
            // active tick, observed as endless `[iCloud] mergeRemoteMessage
            // SKIP (local newer)` log spam plus continuous v1-side dirty
            // drain that competes with v2's send pipeline.
            if #available(iOS 17.0, *) {
                Task { @MainActor in
                    if SyncV2Bootstrap.shouldPauseV1() {
                        await SyncCore.shared.fetchNow(trigger: .foregroundTimer)
                        await SyncCore.shared.sendNow(trigger: .foregroundTimer)
                    } else {
                        await CloudSyncEngine.shared.triggerFetch()
                        await CloudSyncEngine.shared.triggerSend()
                    }
                }
            }

        case .inactive:
            let remaining = UIApplication.shared.backgroundTimeRemaining
            lifecycleLog.info("[Lifecycle] → Inactive (remaining: \(Self.formatTimeRemaining(remaining)))")
            CrashReporter.shared.updateMarkerPhase(phase: "inactive")
            ViewModelCache.shared.suspendAllStreamingUI()
            // Privacy screen in task switcher when app lock is enabled
            if SessionLockStore.shared.appLockEnabled {
                SessionLockStore.shared.showPrivacyScreen = true
            }

        case .background:
            backgroundEntryDate = Date()
            let remaining = UIApplication.shared.backgroundTimeRemaining
            lifecycleLog.info("[Lifecycle] → Background (remaining: \(Self.formatTimeRemaining(remaining)))")
            CrashReporter.shared.updateMarkerPhase(phase: "background")
            shareCoordinator.clearBufferIfStale()
            // Sync the app-icon badge to the current running-task count
            // on the way out — covers the case where the publisher in
            // BackgroundKeepAliveManager last fired while we were still
            // foreground (badge was forced to 0 by the .active branch).
            BackgroundKeepAliveManager.shared.refreshActiveTaskBadge()
            // Drain any pending skill-filesystem rescan now that the
            // app is leaving the foreground — last chance to run the
            // heavier disk-scan + markDirty pass while we still have
            // CPU before iOS suspends us.
            if #available(iOS 17.0, *) {
                SkillFilesystemNotifier.shared.drainIfDirtyAsync(reason: "scenePhase background")
            }
            // [T-config-audit-wal-loss] The config-audit DB runs in WAL mode and
            // is NOT covered by ICloudBackupManager's checkpointing (it lives in
            // its own file, deliberately outside minis.db). Without a checkpoint
            // a jetsam can drop history that only ever reached the -wal sidecar.
            // Cheap and synchronous — this is the last reliable moment to do it.
            ConfigAuditLog.shared.checkpoint()
            // "Lock on exit" mode — drop every cached unlock
            // stamp so re-entering any locked session requires Face ID.
            // Per-session AIChatView observer only covers the session
            // currently on screen; this catches the rest.
            if SessionLockStore.shared.idleTimeoutSeconds < 0 {
                SessionLockStore.shared.clearAllUnlocks()
            }
            // [T-applock-repeated-faceid] App-level "lock on exit": record WHEN we
            // backgrounded instead of dropping the unlock immediately. The next
            // foreground evaluateAppLock() re-locks only if the background spell
            // exceeded the grace window (SessionLockStore.lockOnExitGraceSeconds),
            // so the rapid inactive↔active churn iOS emits (banners, control
            // center, background-audio state flips) no longer re-prompts Face ID.
            if SessionLockStore.shared.appLockIdleSeconds < 0 {
                SessionLockStore.shared.noteAppBackgrounded()
            }

        @unknown default:
            lifecycleLog.warning("[Lifecycle] → Unknown scene phase")
        }
    }

    private static func formatTimeRemaining(_ remaining: TimeInterval) -> String {
        if remaining > 99999 {
            return "unlimited"
        }
        return String(format: "%.1fs", remaining)
    }

    // MARK: - FileProvider

    private static let fileProviderDomain = NSFileProviderDomain(
        identifier: NSFileProviderDomainIdentifier("com.openminis.app.files"),
        displayName: "Minis"
    )

    /// Bumped when we need to force-rebuild the FileProvider domain on next launch
    /// (e.g. after a bug where iOS Files ended up paused and only a full
    /// remove+re-add of the domain can clear its internal state).
    /// gen 2: first remove+add (default mode, preserves cache)
    /// gen 3: remove with .removeAll, also signal workingSet after re-add
    private static let fileProviderResetGeneration = 3
    private static let fileProviderResetKey = "fileProviderResetGeneration"

    // MARK: FP launch-failure correlation trace

    /// [T-ios-fp-mac-bootcrash] Append one line to the FP extension's trace file
    /// (`AppGroup/MinisConfig/fp-sync-trace.log`, same format as FPSyncTraceLog)
    /// when the installed bundle changes. The FP appex intermittently dies
    /// pre-main on Macs (SIGILL at DYLD-STUB$$NSExtensionMain, TestFlight
    /// D8_ZguC2Ikr7Wl0fRI7Ubn) — those launches can never log, so the working
    /// theory (fileproviderd launching the appex mid-update while the bundle is
    /// being replaced) can only be confirmed by ordering three timestamps in one
    /// file: this app-updated marker, the .crash time, and the appex's next
    /// successful init (which stamps its executable mtime). FPSyncTraceLog is
    /// compiled into the appex target only, so this is a minimal local append —
    /// deliberately not a pbxproj membership change.
    private static func logAppUpdateMarkerForFPTrace() {
        let fm = FileManager.default
        let bundle = Bundle.main
        let ver = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        var execStamp = "?"
        if let execURL = bundle.executableURL,
           let mod = (try? fm.attributesOfItem(atPath: execURL.path))?[.modificationDate] as? Date {
            execStamp = ISO8601DateFormatter().string(from: mod)
        }
        let current = "\(ver)(\(build)) exec=\(execStamp)"
        let key = "fpTraceLastSeenBundleStamp"
        let previous = UserDefaults.standard.string(forKey: key)
        guard previous != current else { return }
        UserDefaults.standard.set(current, forKey: key)

        guard let container = fm.containerURL(forSecurityApplicationGroupIdentifier: "group.com.openminis.app") else { return }
        let dir = container.appendingPathComponent("MinisConfig", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("fp-sync-trace.log")
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        fmt.locale = Locale(identifier: "en_US_POSIX")
        let onMac = ProcessInfo.processInfo.isiOSAppOnMac
        let line = "[\(fmt.string(from: Date()))] [FPSyncTrace] app-updated old=\(previous ?? "none") new=\(current) mac=\(onMac)\n"
        if let data = line.data(using: .utf8) {
            if fm.fileExists(atPath: url.path), let h = try? FileHandle(forWritingTo: url) {
                defer { try? h.close() }
                _ = try? h.seekToEnd()
                try? h.write(contentsOf: data)
            } else {
                try? data.write(to: url)
            }
        }
        lifecycleLog.info("[FPSyncTrace] app-updated old=\(previous ?? "none") new=\(current) mac=\(onMac)")
    }

    private static func registerFileProviderDomain() {
        logAppUpdateMarkerForFPTrace()
        let root = AIChatViewModel.minisAppGroupRoot
        let fm = FileManager.default
        // Ensure all three subdirectories exist.
        for sub in ["memory", "skills", "shared"] {
            try? fm.createDirectory(at: root.appendingPathComponent(sub, isDirectory: true),
                                    withIntermediateDirectories: true)
        }
        // [T-soul-md] Seed SOUL.md with default content on first launch so
        // the Soul settings page and chat bubble identity have something
        // to render before the user customizes anything. Never overwrites
        // an existing file. Cache the parsed metadata so synchronous call
        // sites (chat bubble header) see the user's name/emoji immediately.
        SoulStore.ensureExists()
        // [T-ios-soul-name-sidebar-stale] Refresh synchronously. This runs from
        // the app's .onAppear (main thread) and refreshCache() is @MainActor, so
        // the async hop only delayed cachedMetadata for no reason and let the
        // sidebar title render stale first. App.init() already pre-loads it; this
        // keeps the cache fresh after ensureExists() seeds a first-launch SOUL.md.
        SoulStore.refreshCache()

        // Clean up stale directory created by a bug where workingSet identifier
        // was passed through as a subdirectory name.
        let staleDir = root.appendingPathComponent("shared/NSFileProviderWorkingSetContainerItemIdentifier")
        if fm.fileExists(atPath: staleDir.path) {
            try? fm.removeItem(at: staleDir)
            lifecycleLog.info("[FileProvider] cleaned up stale workingSet directory")
        }

        let defaults = UserDefaults.standard
        let lastReset = defaults.integer(forKey: fileProviderResetKey)
        let needsForceReset = lastReset < fileProviderResetGeneration

        // Remove any stale domains first, then add fresh
        NSFileProviderManager.getDomainsWithCompletionHandler { domains, error in
            if let error {
                lifecycleLog.warning("[FileProvider] getDomainsWithCompletionHandler failed: \(error.localizedDescription)")
            }

            // Check if our domain is already registered with the correct identifier.
            let alreadyRegistered = domains.contains { $0.identifier == fileProviderDomain.identifier }

            if needsForceReset && alreadyRegistered {
                // One-time aggressive reset. The default `remove(_:)` only
                // unregisters the domain — iOS Files keeps its cached
                // metadata (including any "paused" flag) keyed on the same
                // identifier, so re-adding doesn't clear the broken state.
                //
                // On iOS the only available removal mode is `.removeAll`
                // (the `preserveDirty/Downloaded` modes are macOS-only).
                // `.removeAll` wipes the system-managed sync database and
                // its replicated storage. For replicated extensions iOS
                // creates that storage at a system-managed path that we
                // never read or write — our actual user data lives at the
                // App Group `MinisFileProvider/` path that we hardcoded
                // and that iOS does not own. So `.removeAll` SHOULD leave
                // our data intact, but to be safe we snapshot to
                // `MinisConfig/_reset_backup_v3/` first and restore if we
                // detect any of the three subdirs are missing afterwards.
                lifecycleLog.info("[FileProvider] force-reset gen=\(fileProviderResetGeneration) snapshotting providerRoot before remove(.removeAll)")
                let backupRoot = AIChatViewModel.minisConfigRoot.appendingPathComponent("_reset_backup_v3", isDirectory: true)
                let snapshotResult = Self.snapshotProviderSubdirs(to: backupRoot)
                guard snapshotResult.ok else {
                    lifecycleLog.error("[FileProvider] force-reset SNAPSHOT FAILED — aborting reset to avoid risk of data loss")
                    return
                }
                lifecycleLog.info("[FileProvider] force-reset snapshot OK files=\(snapshotResult.files) bytes=\(snapshotResult.bytes)")

                NSFileProviderManager.remove(fileProviderDomain, mode: .removeAll) { preservedLocation, removeErr in
                    if let removeErr {
                        lifecycleLog.warning("[FileProvider] force-reset remove(.removeAll) failed: \(removeErr.localizedDescription) — leaving snapshot for next try")
                        // Don't bump generation; snapshot stays for next launch.
                        return
                    }
                    let preservedPath = preservedLocation?.path ?? "nil"
                    lifecycleLog.info("[FileProvider] force-reset remove(.removeAll) OK preservedLocation=\(preservedPath)")

                    // Theory check: did .removeAll touch our hardcoded path?
                    let providerRoot = AIChatViewModel.minisAppGroupRoot
                    let fm = FileManager.default
                    let allSubdirsPresent = ["memory", "skills", "shared"].allSatisfy {
                        fm.fileExists(atPath: providerRoot.appendingPathComponent($0).path)
                    }
                    if !allSubdirsPresent {
                        lifecycleLog.warning("[FileProvider] force-reset detected missing providerRoot subdirs after .removeAll — restoring from snapshot")
                        Self.restoreProviderSubdirs(from: backupRoot)
                    } else {
                        lifecycleLog.info("[FileProvider] force-reset providerRoot data intact, no restore needed")
                    }

                    NSFileProviderManager.add(fileProviderDomain) { addErr in
                        if let addErr {
                            lifecycleLog.warning("[FileProvider] force-reset add failed: \(addErr.localizedDescription) — keeping snapshot for next try")
                            return
                        }
                        lifecycleLog.info("[FileProvider] force-reset add OK")

                        // Final integrity check after add (extension init may
                        // recreate empty subdirs and overwrite anything).
                        let postAddPresent = ["memory", "skills", "shared"].allSatisfy {
                            fm.fileExists(atPath: providerRoot.appendingPathComponent($0).path)
                        }
                        if !postAddPresent {
                            lifecycleLog.warning("[FileProvider] force-reset detected missing subdirs after add — restoring from snapshot")
                            Self.restoreProviderSubdirs(from: backupRoot)
                        }

                        // Snapshot can be deleted only if everything is
                        // healthy now. Otherwise leave it in place.
                        if Self.providerSubdirsHaveContent(providerRoot: providerRoot,
                                                            backupRoot: backupRoot) {
                            try? fm.removeItem(at: backupRoot)
                            lifecycleLog.info("[FileProvider] force-reset removed snapshot \(backupRoot.lastPathComponent)")
                        } else {
                            lifecycleLog.warning("[FileProvider] force-reset KEEPING snapshot — providerRoot looks emptier than backup")
                        }

                        defaults.set(fileProviderResetGeneration, forKey: fileProviderResetKey)
                        lifecycleLog.info("[FileProvider] force-reset complete")
                        let mgr = NSFileProviderManager(for: fileProviderDomain)
                        mgr?.signalEnumerator(for: .rootContainer) { _ in }
                        mgr?.signalEnumerator(for: .workingSet) { _ in }
                    }
                }
                return
            }

            if alreadyRegistered {
                // Domain exists — just signal a refresh, don't remove+re-add.
                lifecycleLog.info("[FileProvider] domain already registered, signaling refresh")
                NSFileProviderManager(for: fileProviderDomain)?.signalEnumerator(for: .rootContainer) { signalErr in
                    if let signalErr {
                        lifecycleLog.warning("[FileProvider] signal failed: \(signalErr.localizedDescription)")
                    }
                }
                // Mark the reset as done even if we didn't need to force it —
                // a registered-with-current-identifier path implies the domain
                // is healthy enough, and we don't want to loop force-resetting
                // on every launch.
                defaults.set(fileProviderResetGeneration, forKey: fileProviderResetKey)
                return
            }

            // Remove any stale domains with different identifiers, then add ours.
            let stale = domains.filter { $0.identifier.rawValue.contains("com.openminis") }
            let group = DispatchGroup()
            for d in stale {
                group.enter()
                NSFileProviderManager.remove(d) { _ in group.leave() }
            }
            group.notify(queue: .main) {
                NSFileProviderManager.add(fileProviderDomain) { error in
                    if let error {
                        lifecycleLog.warning("[FileProvider] domain add failed: \(error.localizedDescription)")
                    } else {
                        lifecycleLog.info("[FileProvider] domain registered OK")
                        defaults.set(fileProviderResetGeneration, forKey: fileProviderResetKey)
                        NSFileProviderManager(for: fileProviderDomain)?.signalEnumerator(for: .rootContainer) { signalErr in
                            if let signalErr {
                                lifecycleLog.warning("[FileProvider] signal failed: \(signalErr.localizedDescription)")
                            }
                        }
                    }
                }
            }
        }
    }

    private static func signalFileProvider() {
        NSFileProviderManager(for: fileProviderDomain)?.signalEnumerator(for: .rootContainer) { error in
            if let error {
                lifecycleLog.warning("[FileProvider] signal failed: \(error.localizedDescription)")
            }
        }
    }

    /// Snapshot providerRoot's three user-data subdirs (`memory`, `skills`,
    /// `shared`) into `backupRoot/`. Used as belt-and-suspenders before a
    /// `remove(_:mode: .removeAll)` reset, in case our theory that .removeAll
    /// only touches the system-managed materialized location turns out wrong.
    /// Returns total file count and bytes; `ok=false` if any copy fails.
    private static func snapshotProviderSubdirs(to backupRoot: URL) -> (ok: Bool, files: Int, bytes: Int64) {
        let fm = FileManager.default
        let providerRoot = AIChatViewModel.minisAppGroupRoot

        // Reset any leftover snapshot from a previous attempt.
        if fm.fileExists(atPath: backupRoot.path) {
            try? fm.removeItem(at: backupRoot)
        }
        do {
            try fm.createDirectory(at: backupRoot, withIntermediateDirectories: true)
        } catch {
            lifecycleLog.warning("[FileProvider] snapshotProviderSubdirs: createDirectory failed: \(error.localizedDescription)")
            return (false, 0, 0)
        }

        var totalFiles = 0
        var totalBytes: Int64 = 0
        for sub in ["memory", "skills", "shared"] {
            let srcDir = providerRoot.appendingPathComponent(sub, isDirectory: true)
            guard fm.fileExists(atPath: srcDir.path) else { continue }
            let dstDir = backupRoot.appendingPathComponent(sub, isDirectory: true)
            do {
                try fm.copyItem(at: srcDir, to: dstDir)
            } catch {
                lifecycleLog.warning("[FileProvider] snapshotProviderSubdirs: copy '\(sub)' failed: \(error.localizedDescription)")
                return (false, totalFiles, totalBytes)
            }
            // Tally for diagnostics.
            if let enumerator = fm.enumerator(at: dstDir,
                                              includingPropertiesForKeys: [.fileSizeKey, .isDirectoryKey]) {
                for case let url as URL in enumerator {
                    var isDir: ObjCBool = false
                    fm.fileExists(atPath: url.path, isDirectory: &isDir)
                    if isDir.boolValue { continue }
                    totalFiles += 1
                    if let size = (try? fm.attributesOfItem(atPath: url.path)[.size]) as? Int64 {
                        totalBytes += size
                    }
                }
            }
        }
        return (true, totalFiles, totalBytes)
    }

    /// Restore the three user-data subdirs from `backupRoot/` back into
    /// providerRoot. Used after a destructive reset operation that wiped
    /// data we did not expect to be wiped. Existing files in the
    /// destination are left alone if they're newer; otherwise overwritten.
    private static func restoreProviderSubdirs(from backupRoot: URL) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: backupRoot.path) else {
            lifecycleLog.warning("[FileProvider] restoreProviderSubdirs: backup not found at \(backupRoot.path)")
            return
        }
        let providerRoot = AIChatViewModel.minisAppGroupRoot
        for sub in ["memory", "skills", "shared"] {
            try? fm.createDirectory(at: providerRoot.appendingPathComponent(sub, isDirectory: true),
                                    withIntermediateDirectories: true)
        }

        var restored = 0
        var failed = 0
        guard let enumerator = fm.enumerator(at: backupRoot,
                                             includingPropertiesForKeys: [.isDirectoryKey],
                                             options: [.skipsHiddenFiles]) else {
            return
        }
        let backupPrefix = backupRoot.standardized.path
        for case let srcURL as URL in enumerator {
            let srcPath = srcURL.standardized.path
            guard srcPath.hasPrefix(backupPrefix) else { continue }
            var relative = String(srcPath.dropFirst(backupPrefix.count))
            if relative.hasPrefix("/") { relative.removeFirst() }
            if relative.isEmpty { continue }

            let destURL = providerRoot.appendingPathComponent(relative)
            var isDir: ObjCBool = false
            fm.fileExists(atPath: srcURL.path, isDirectory: &isDir)
            if isDir.boolValue {
                try? fm.createDirectory(at: destURL, withIntermediateDirectories: true)
                continue
            }
            if fm.fileExists(atPath: destURL.path) {
                let srcDate = ((try? fm.attributesOfItem(atPath: srcURL.path))?[.modificationDate] as? Date) ?? .distantPast
                let dstDate = ((try? fm.attributesOfItem(atPath: destURL.path))?[.modificationDate] as? Date) ?? .distantPast
                if dstDate >= srcDate { continue }
                try? fm.removeItem(at: destURL)
            }
            do {
                try fm.createDirectory(at: destURL.deletingLastPathComponent(),
                                       withIntermediateDirectories: true)
                try fm.copyItem(at: srcURL, to: destURL)
                restored += 1
            } catch {
                failed += 1
                lifecycleLog.warning("[FileProvider] restoreProviderSubdirs: copy '\(relative)' failed: \(error.localizedDescription)")
            }
        }
        lifecycleLog.info("[FileProvider] restoreProviderSubdirs: restored=\(restored) failed=\(failed)")
    }

    /// True if the providerRoot subdirs are at least as populated as the
    /// backup snapshot. Used to decide whether it's safe to delete the
    /// backup or whether we should keep it for manual recovery.
    private static func providerSubdirsHaveContent(providerRoot: URL, backupRoot: URL) -> Bool {
        let fm = FileManager.default
        for sub in ["memory", "skills", "shared"] {
            let backupSub = backupRoot.appendingPathComponent(sub)
            let liveSub = providerRoot.appendingPathComponent(sub)
            guard fm.fileExists(atPath: backupSub.path) else { continue }
            let backupCount = countFiles(in: backupSub)
            let liveCount = countFiles(in: liveSub)
            if liveCount < backupCount {
                lifecycleLog.warning("[FileProvider] providerSubdirsHaveContent: '\(sub)' live=\(liveCount) < backup=\(backupCount)")
                return false
            }
        }
        return true
    }

    private static func countFiles(in dir: URL) -> Int {
        let fm = FileManager.default
        guard fm.fileExists(atPath: dir.path),
              let enumerator = fm.enumerator(at: dir,
                                             includingPropertiesForKeys: [.isDirectoryKey]) else {
            return 0
        }
        var n = 0
        for case let url as URL in enumerator {
            var isDir: ObjCBool = false
            fm.fileExists(atPath: url.path, isDirectory: &isDir)
            if !isDir.boolValue { n += 1 }
        }
        return n
    }

    /// One-time migration: move files from old locations to App Group container.
    /// Handles three sources:
    /// 1. Library/MinisChat/shared/ → App Group MinisFileProvider/shared/
    /// 2. Library/MinisChat/memory/ → App Group MinisFileProvider/memory/
    /// 3. Library/MinisChat/skills/ → App Group MinisFileProvider/skills/
    /// 4. Old App Group MinisShared/ → App Group MinisFileProvider/shared/
    private static func migrateSharedDirToAppGroup() {
        let fm = FileManager.default
        let library = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let container = fm.containerURL(forSecurityApplicationGroupIdentifier: "group.com.openminis.app")!

        let migrations: [(source: URL, dest: URL, label: String)] = [
            // Legacy Library/MinisChat/shared → new shared
            (library.appendingPathComponent("MinisChat/shared", isDirectory: true),
             AIChatViewModel.minisSharedPersistentDir, "shared"),
            // Legacy Library/MinisChat/memory → new memory
            (library.appendingPathComponent("MinisChat/memory", isDirectory: true),
             AIChatViewModel.minisMemoryPersistentDir, "memory"),
            // Legacy Library/MinisChat/skills → new skills
            (library.appendingPathComponent("MinisChat/skills", isDirectory: true),
             AIChatViewModel.minisSkillsPersistentDir, "skills"),
            // Old App Group MinisShared → new shared
            (container.appendingPathComponent("MinisShared", isDirectory: true),
             AIChatViewModel.minisSharedPersistentDir, "MinisShared→shared"),
        ]

        for migration in migrations {
            guard fm.fileExists(atPath: migration.source.path) else { continue }
            try? fm.createDirectory(at: migration.dest, withIntermediateDirectories: true)
            guard let contents = try? fm.contentsOfDirectory(at: migration.source, includingPropertiesForKeys: nil) else { continue }
            for item in contents {
                let dest = migration.dest.appendingPathComponent(item.lastPathComponent)
                if !fm.fileExists(atPath: dest.path) {
                    try? fm.moveItem(at: item, to: dest)
                }
            }
            // Remove old directory if empty
            if (try? fm.contentsOfDirectory(at: migration.source, includingPropertiesForKeys: nil))?.isEmpty ?? true {
                try? fm.removeItem(at: migration.source)
            }
            lifecycleLog.info("[FileProvider] migrated \(migration.label) to App Group")
        }
    }

    /// Print the canonical AppGroup paths for the three FileProvider-exposed
    /// roots, plus a child count for each. Lets us correlate against the
    /// FileProvider extension's view of the same paths and the iSH bind mount
    /// targets logged during MOUNT setup.
    private static func logFPSyncTracePaths() {
        let fm = FileManager.default
        let groupID = "group.com.openminis.app"
        let containerURL = fm.containerURL(forSecurityApplicationGroupIdentifier: groupID)
        let containerPath = containerURL?.path ?? "<nil>"
        let resolvedContainer = containerURL?.resolvingSymlinksInPath().path ?? "<nil>"
        lifecycleLog.info("[FPSyncTrace] appGroup=\(groupID) container=\(containerPath) resolved=\(resolvedContainer)")

        let providerRoot = AIChatViewModel.minisAppGroupRoot
        lifecycleLog.info("[FPSyncTrace] providerRoot=\(providerRoot.path) resolved=\(providerRoot.resolvingSymlinksInPath().path)")

        let roots: [(name: String, url: URL)] = [
            ("shared", AIChatViewModel.minisSharedPersistentDir),
            ("skills", AIChatViewModel.minisSkillsPersistentDir),
            ("memory", AIChatViewModel.minisMemoryPersistentDir),
        ]
        for (name, url) in roots {
            let exists = fm.fileExists(atPath: url.path)
            let count = (try? fm.contentsOfDirectory(atPath: url.path).count) ?? -1
            lifecycleLog.info("[FPSyncTrace] root=\(name) path=\(url.path) exists=\(exists) childCount=\(count)")
        }
    }

    // MARK: - WebApp Presentation

    /// Identifiable wrapper passed to `fullScreenCover(item:)`. Carries both
    /// the persisted row (so the WebView can read its title) and the
    /// already-resolved host paths (so resolution failures are surfaced
    /// before the cover appears, not from inside it).
    fileprivate struct WebAppPresentation: Identifiable {
        let id: String
        let shortcut: WebAppShortcut
        let resolved: WebAppPathResolver.Resolved
    }

    // [T-ios-remove-open-webapp-shortcut-intent] `openWebAppShortcut(id:into:)`
    // removed alongside OpenWebAppIntent — it was only called from the
    // `.openWebAppFromIntent` handler. The deep-link entry point below
    // (`presentWebAppDeepLink`) is the remaining WebApp presentation path.

    /// Resolves a transient `WebAppShortcut` reconstructed from a
    /// `minis://open?session=…&path=…` deep link (openminis.app launcher
    /// round-trip) and presents the immersive WebView. Does not touch
    /// ChatStore — the launcher URL is fully self-describing.
    @MainActor
    fileprivate static func presentWebAppDeepLink(shortcut: WebAppShortcut,
                                                  into binding: Binding<WebAppPresentation?>) {
        do {
            let resolved = try WebAppPathResolver.resolve(shortcut)
            binding.wrappedValue = WebAppPresentation(id: shortcut.id, shortcut: shortcut, resolved: resolved)
        } catch {
            lifecycleLog.error("[WebApp] deeplink resolve failed scope=\(shortcut.pathScope.rawValue) htmlPath=\(shortcut.htmlPath) error=\(error)")
        }
    }
}
