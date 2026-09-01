import AppIntents
import Foundation
import UniformTypeIdentifiers
import UserNotifications

/// Sends a prompt to the Minis AI agent and returns immediately with structured session info.
/// The agent continues running in the background — use Get Session Status to poll for completion.
struct SendPromptIntent: AppIntent {
    static var title: LocalizedStringResource = "Send Prompt"
    static var description = IntentDescription("Sends a prompt to the Minis AI agent. Returns session info immediately while the task runs in the background.")
    static var openAppWhenRun = false

    // [T-shortcuts-automation-no-prompt-field] `requestValueDialog` alone marks
    // this as a Siri "ask the user at run time" parameter. In the Automation
    // editor (Time of Day etc.) there is no one to ask, and with no
    // `parameterSummary` naming it the parameter was not surfaced at all — the
    // action card rendered as a bare "Send Prompt" title with no editable
    // field. Keeping the dialog for Siri is fine; the summary below is what
    // makes the text field appear. `inputConnectionBehavior` additionally lets
    // the field accept the previous action's output as a variable.
    @Parameter(title: "Prompt",
               requestValueDialog: "What would you like to ask Minis?",
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var prompt: String

    @Parameter(title: "Attachments", description: "Images, videos, or files to attach to the prompt. Accepts output from previous Shortcuts actions (e.g. filtered photos or documents).",
               supportedTypeIdentifiers: ["public.image", "public.movie", "public.data"],
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var files: [IntentFile]?

    @Parameter(title: "Session", description: "Existing session to continue. Leave empty for a new session.")
    var session: SessionEntity?

    @Parameter(title: "Model", description: "Specify a model or model group to use. Leave empty to use the app default.")
    var model: ModelSelectionEntity?

    @Parameter(title: "Wait for Result", description: "When enabled, waits for the AI to finish and returns the full response. Use this to chain the result into subsequent Shortcuts actions.", default: false)
    var waitForResult: Bool

    @Parameter(title: "Send Notifications", description: "When enabled, posts a system notification when the task starts and again when it finishes. Turn this off for automations that run silently. The app's global task-notification setting still applies.", default: true)
    var sendCompletionNotification: Bool

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<SendPromptResult> & ProvidesDialog {
        // Ensure BackgroundKeepAliveManager is set up
        BackgroundKeepAliveManager.shared.setup()

        // [T-shortcuts-eager-keepalive] Arm keep-alive BEFORE any await. For an
        // existing session we already know the id; for a NEW session we don't
        // have one yet (ensureSessionReturningId will create it a moment later),
        // so we register a temporary placeholder id — enough to make the
        // activeSessions Set non-empty and flip isActive=true. Once the real
        // sessionId lands we re-arm with the real id and drop the placeholder.
        // This is strictly no-op unless enhancedBackgroundEffective is on.
        let placeholderSid: String? = (session == nil) ? "intent-eager:\(UUID().uuidString)" : nil
        let eagerInitialSid = session?.id ?? placeholderSid ?? ""
        var eagerArmed = false
        var eagerSkipReason: String? = nil
        if !eagerInitialSid.isEmpty {
            let r = BackgroundKeepAliveManager.shared.armEagerlyForShortcut(
                sessionId: eagerInitialSid, caller: "SendPromptIntent")
            eagerArmed = r.armed
            eagerSkipReason = r.skipReason
        }
        // [T-ios-session-status-mismatch] Guarantee the eager placeholder is
        // dropped no matter how this perform() exits (throw / early return /
        // ensureSessionReturningId returns without a sessionId). Without this,
        // a failed shortcut invocation leaves "intent-eager:<UUID>" stuck in
        // SessionActivityTracker.activeSessions forever — phantom "running"
        // session on the home spinner, inflated `chat.session.status`, Live
        // Activity count skew. Only the placeholder needs a safety net: the
        // real sessionId path (session != nil) is paired by the VM's
        // $isProcessing sink via loadSession + send(). setInactive is
        // idempotent so the explicit swap path (line ~93) is unaffected.
        defer {
            if let placeholder = placeholderSid, eagerArmed {
                SessionActivityTracker.shared.setInactive(placeholder,
                    source: "SendPromptIntent.eager.cleanupDefer")
            }
        }
        // [T-shortcuts-diag-and-pending] Snapshot state + mark pending. For a
        // new-session flow the sessionId here is the placeholder — that's fine
        // for diagnostics; the record is cleared by the completion path either
        // way and doesn't need to carry the eventual real id.
        let diagSessionId = eagerInitialSid.isEmpty ? "unknown" : eagerInitialSid
        ShortcutRunTracker.logPerformEntry(
            intent: "SendPromptIntent",
            sessionId: diagSessionId,
            eagerKeepAliveArmed: eagerArmed,
            eagerKeepAliveSkippedReason: eagerSkipReason
        )
        let pendingId = ShortcutRunTracker.markPending(
            intent: "SendPromptIntent",
            sessionId: diagSessionId,
            eagerKeepAliveArmed: eagerArmed,
            eagerKeepAliveSkippedReason: eagerSkipReason
        )

        let vm: AIChatViewModel
        let isNewSession: Bool
        if let session = session {
            let (cached, _) = ViewModelCache.shared.getOrCreate(for: session.id)
            vm = cached
            await vm.loadSession()
            isNewSession = false
        } else {
            vm = ViewModelCache.shared.createDraft()
            isNewSession = true
        }

        // [T-shortcut-duplicate-completion-notification] This intent posts its
        // own completion notification, so suppress the generic background one for
        // this run. Set on BOTH branches — a shortcut continuing an existing
        // session would otherwise still double-notify. Consumed by
        // endBackgroundProcessing, so it never leaks into a later manual send.
        vm.suppressGeneralCompletionNotification = true

        // For new sessions, create the DB record first
        if isNewSession {
            vm.sessionSource = "shortcut"
            await vm.ensureSessionReturningId()
            // [T-shortcuts-eager-keepalive] Real id now known — re-arm with it
            // (keeps activeSessions non-empty across the swap) and drop the
            // placeholder so it doesn't linger. Re-arm is idempotent when the
            // engine is already running.
            if let placeholder = placeholderSid, let realSid = vm.sessionId {
                BackgroundKeepAliveManager.shared.armEagerlyForShortcut(
                    sessionId: realSid, caller: "SendPromptIntent.swap")
                SessionActivityTracker.shared.setInactive(placeholder,
                    source: "SendPromptIntent.eager.placeholderSwap")
            }
        }

        // Apply model override if specified (nil = use app default, backward-compatible)
        if let modelSelection = model, let sid = vm.sessionId {
            let store = ProviderConfigStore.shared
            if let binding = modelSelection.toSessionModelBinding(sessionId: sid, store: store) {
                store.setBinding(binding, for: sid)
            }
        }

        // Add file attachments if provided
        if let intentFiles = files {
            for file in intentFiles {
                let name = Self.resolvedFileName(for: file)
                vm.addDataAttachment(data: file.data, fileName: name)
            }
        }

        // Send the prompt
        vm.inputText = prompt
        vm.send()

        let sid = vm.sessionId ?? "unknown"

        // Resolve actual model from session binding (matches what the agent loop uses)
        var modelName = vm.selectedModel.displayName
        let store = ProviderConfigStore.shared
        if let binding = store.binding(for: sid) {
            switch binding.primarySource {
            case .group(_, let resolvedEntryId):
                if let entry = store.entry(for: resolvedEntryId) {
                    modelName = entry.model.displayName
                }
            case .directEntry(let modelEntryId, _):
                if let entry = store.entry(for: modelEntryId) {
                    modelName = entry.model.displayName
                }
            }
        }

        // Local notification: task started. Posted here — the prompt is sent and
        // the model is resolved — so the user gets immediate confirmation that the
        // run began; without it a long task reads as "nothing happened". Governed
        // by the same toggle as the completion notification, so switching
        // notifications off silences both. Fire-and-forget: nothing below awaits it.
        if sendCompletionNotification {
            let promptPreview = String(prompt.prefix(50))
            ShortcutNotification.post(
                id: "shortcut-start-\(sid)",
                title: AppLocalized("Minis Task Started"),
                body: "\(modelName): \(promptPreview)\(prompt.count > 50 ? "…" : "")",
                sessionId: sid
            )
        }

        if waitForResult {
            // Synchronous mode: wait for the agent to finish, then return the full response
            for await processing in vm.$isProcessing.values {
                if !processing { break }
            }

            // [T-shortcuts-diag-and-pending] Loop finished.
            ShortcutRunTracker.markCompleted(recordId: pendingId, reason: "waitForResult.done")

            let responseText = Self.extractResponseText(from: vm)

            // Per-run opt-out. ANDs with the app-wide toggle, which
            // ShortcutNotification.post checks internally — do not duplicate it here.
            if sendCompletionNotification {
                ShortcutNotification.post(
                    id: "shortcut-done-\(sid)",
                    title: AppLocalized("Minis Task Completed"),
                    body: "\(modelName): \(String(responseText.prefix(200)))",
                    sessionId: sid
                )
            }

            let result = SendPromptResult(
                sessionId: sid,
                modelName: modelName,
                status: "Completed",
                isNewSession: isNewSession,
                prompt: prompt,
                responseText: responseText
            )
            return .result(value: result, dialog: "\(responseText.prefix(500))")
        }

        // Async mode: return immediately, notify on completion in background
        let capturedModelName = modelName
        let capturedSid = sid
        let capturedPendingId = pendingId
        let capturedSendCompletionNotification = sendCompletionNotification
        Task { @MainActor in
            for await processing in vm.$isProcessing.values {
                if !processing { break }
            }

            // [T-shortcuts-diag-and-pending] Loop finished.
            ShortcutRunTracker.markCompleted(recordId: capturedPendingId, reason: "async.done")

            let summary = String(Self.extractResponseText(from: vm).prefix(200))

            if capturedSendCompletionNotification {
                ShortcutNotification.post(
                    id: "shortcut-done-\(capturedSid)",
                    title: AppLocalized("Minis Task Completed"),
                    body: "\(capturedModelName): \(summary)",
                    sessionId: capturedSid
                )
            }
        }

        let result = SendPromptResult(
            sessionId: sid,
            modelName: modelName,
            status: "Running",
            isNewSession: isNewSession,
            prompt: prompt
        )

        return .result(value: result, dialog: IntentDialog(stringLiteral: AppLocalized("Task started with \(modelName). I'll notify you when it's done.")))
    }

    // [T-shortcuts-automation-no-prompt-field] Without a `parameterSummary` the
    // Shortcuts action card has nothing to render: AppIntents uses the summary
    // as the card's layout, so an intent that omits it can fall back to a
    // title-only card with no inline editors — exactly the issue reported for
    // the Automation editor (#121). `RetryRunIntent` was the only intent here
    // that defined one, and it was the only one that rendered correctly.
    //
    // `prompt` goes on the Summary line so it is always an inline, always-shown
    // text field. The remaining parameters are optional refinements and live in
    // the "Show More" section — `\.$files` first so the attachment slot is the
    // first thing surfaced when chaining from a previous action.
    static var parameterSummary: some ParameterSummary {
        Summary("Send \(\.$prompt) to Minis") {
            \.$files
            \.$session
            \.$model
            \.$waitForResult
            \.$sendCompletionNotification
        }
    }

    /// Ensures the IntentFile has a usable filename with a correct extension.
    /// Shortcuts often passes files with no extension (e.g. "IMG_1234") or a
    /// generic name like "Photo". This uses the IntentFile's UTType to append
    /// a proper extension when the filename lacks one.
    static func resolvedFileName(for file: IntentFile) -> String {
        let name = file.filename
        let ext = (name as NSString).pathExtension.lowercased()
        // Already has a known extension — use as-is
        if !ext.isEmpty { return name }
        // Derive extension from the IntentFile's declared UTType
        if let type = file.type,
           let preferred = type.preferredFilenameExtension {
            return "\(name).\(preferred)"
        }
        return name
    }

    /// Extracts the full response text from the last assistant message in the VM.
    @MainActor
    static func extractResponseText(from vm: AIChatViewModel) -> String {
        // [T-bgnotif-internal-text-leak] Skip internal bridge turns — they are
        // instructions addressed to the model, not a response, and returning one
        // to a Shortcut (or any intent caller) leaks prompt text verbatim.
        guard let lastAssistant = vm.messages.last(where: { $0.role == .assistant && !$0.isInternalBridge }) else {
            return "No response."
        }
        let textBlocks = lastAssistant.blocks
            .filter { $0.kind == .text }
            .map { $0.content }
        let text = textBlocks.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? "Task completed." : text
    }
}

/// Helper for posting local notifications from Shortcuts intents.
/// Tapping the notification opens the associated session.
enum ShortcutNotification {
    /// Category ID for shortcut task notifications — enables tap-to-open-session.
    static let categoryId = "SHORTCUT_TASK"

    static func post(id: String, title: String, body: String, sessionId: String) {
        // Respect the global task notifications toggle
        guard UserDefaults.standard.object(forKey: "backgroundNotificationsEnabled") == nil
                || UserDefaults.standard.bool(forKey: "backgroundNotificationsEnabled") else { return }

        let center = UNUserNotificationCenter.current()

        // [T-shortcut-authprompt-midrun] Only ask for permission when the user
        // has never been asked. iOS suppresses the alert once the status is
        // decided, so the previous unconditional call was harmless in the steady
        // state — but on a first run it pops a system dialog, and now that a
        // "task started" notification fires DURING the shortcut, that dialog can
        // land in the middle of an automation. Checking first means the prompt
        // only ever appears in the genuinely-undecided case.
        //
        // Both calls are async with completion handlers and nothing awaits them,
        // so this stays fire-and-forget: post() returns immediately and the
        // intent's return timing is unchanged.
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .notDetermined else { return }
            center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        }

        // Register category (idempotent)
        let category = UNNotificationCategory(
            identifier: categoryId,
            actions: [],
            intentIdentifiers: []
        )
        center.setNotificationCategories([category])

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = categoryId
        content.userInfo = ["sessionId": sessionId]

        // Increment app badge count
        let current = UIApplication.shared.applicationIconBadgeNumber
        content.badge = NSNumber(value: current + 1)
        UIApplication.shared.applicationIconBadgeNumber = current + 1

        let request = UNNotificationRequest(
            identifier: id,
            content: content,
            trigger: nil  // deliver immediately
        )
        center.add(request)
    }
}

/// [T-notification-tap-vs-launch-session] Cold-launch handoff for a
/// notification-tap navigation. On a cold launch the delegate's `didReceive`
/// fires before ContentView has mounted its `.onReceive(.openSessionFromIntent)`
/// subscriber, so the posted NotificationCenter event is simply lost — and the
/// Launch Session preference (e.g. "New Chat") then opens a fresh session
/// instead of the tapped one. The delegate buffers the target here;
/// ContentView's launch `.task` consumes it with top priority, and the warm
/// path (`.onReceive` did navigate) marks it handled so the launch-screen
/// logic yields either way.
@MainActor
final class NotificationNavigationStore {
    static let shared = NotificationNavigationStore()

    private var pendingSessionId: String?
    private var pendingSetAt: Date?
    private var handledAt: Date?

    /// Buffer a tap target (called from didReceive before posting the event).
    func setPending(_ sessionId: String) {
        pendingSessionId = sessionId
        pendingSetAt = Date()
    }

    /// One-shot consume for the cold-launch path. Entries older than 30s are
    /// stale (a warm tap that `.onReceive` already navigated for) and ignored.
    func takePending() -> String? {
        defer { pendingSessionId = nil; pendingSetAt = nil }
        guard let sid = pendingSessionId,
              let t = pendingSetAt,
              Date().timeIntervalSince(t) < 30 else { return nil }
        return sid
    }

    /// Warm path: `.onReceive` navigated directly — drop the buffered copy so
    /// a later launch can't replay it, and remember when it happened so an
    /// in-flight launch `.task` (post arrived during its await) doesn't
    /// clobber the navigation with the Launch Session default.
    func markHandled() {
        pendingSessionId = nil
        pendingSetAt = nil
        handledAt = Date()
    }

    /// True when a notification navigation happened moments ago — the
    /// launch-screen logic must not override it.
    var handledRecently: Bool {
        guard let t = handledAt else { return false }
        return Date().timeIntervalSince(t) < 10
    }
}

/// Handles notification tap → navigates to the session.
final class ShortcutNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = ShortcutNotificationDelegate()

    /// Call once at app startup to register as delegate. MUST run inside
    /// `application(_:didFinishLaunchingWithOptions:)` — if the delegate isn't
    /// set by the time didFinishLaunching returns, iOS does not deliver the
    /// cold-launch notification tap to `didReceive` at all (the SwiftUI
    /// `.onAppear` registration alone was too late, which is why tapping a
    /// notification on a killed app used to land on the Launch Session
    /// default instead of the tapped session).
    func register() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// Called when user taps the notification (app in foreground or background).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        if let sessionId = userInfo["sessionId"] as? String {
            DispatchQueue.main.async {
                // Buffer first (cold-launch consumer), then post (warm-path
                // consumer). Whichever runs marks the other's copy dead.
                NotificationNavigationStore.shared.setPending(sessionId)
                NotificationCenter.default.post(
                    name: .openSessionFromIntent,
                    object: nil,
                    userInfo: ["sessionId": sessionId]
                )
            }
        }
        completionHandler()
    }

    /// Show notification even when app is in foreground.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
