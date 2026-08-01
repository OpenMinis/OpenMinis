import AppIntents
import Foundation
import UserNotifications

/// Predefined tasks that Siri can invoke by name in a single utterance.
/// e.g. "Minis analyze sleep" (or the same phrase pattern in the user's locale).
enum QuickTask: String, AppEnum {
    case analyzeSleep
    case healthReport
    case checkWeather
    case morningBriefing
    case checkCalendar
    case takePhoto
    case setAlarm
    case controlHome

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Quick Task")

    static var caseDisplayRepresentations: [QuickTask: DisplayRepresentation] = [
        .analyzeSleep:   DisplayRepresentation(title: "Analyze Sleep", subtitle: "分析睡眠"),
        .healthReport:   DisplayRepresentation(title: "Health Report", subtitle: "健康报告"),
        .checkWeather:   DisplayRepresentation(title: "Check Weather", subtitle: "查看天气"),
        .morningBriefing: DisplayRepresentation(title: "Morning Briefing", subtitle: "早间简报"),
        .checkCalendar:  DisplayRepresentation(title: "Check Calendar", subtitle: "查看日程"),
        .takePhoto:      DisplayRepresentation(title: "Take Photo", subtitle: "拍照"),
        .setAlarm:       DisplayRepresentation(title: "Set Alarm", subtitle: "设置闹钟"),
        .controlHome:    DisplayRepresentation(title: "Control Home", subtitle: "智能家居"),
    ]

    var prompt: String {
        switch self {
        case .analyzeSleep:
            return "Read my recent sleep data (apple-healthkit sleep), analyze sleep quality and generate a report"
        case .healthReport:
            return "Read my health data for today: steps (apple-healthkit steps --today), heart rate (apple-healthkit heart-rate --today), blood oxygen, and generate a daily health report"
        case .checkWeather:
            return "Check today's weather (apple-weather) and give clothing and travel suggestions"
        case .morningBriefing:
            return "Give me a morning briefing for today: first check the weather (apple-weather), then check today's calendar events (apple-calendar), finally search for important news today using the browser, and summarize into a briefing"
        case .checkCalendar:
            return "Check my schedule for today and tomorrow (apple-calendar)"
        case .takePhoto:
            return "Take a photo using the device camera (apple-media camera) and describe what was captured"
        case .setAlarm:
            return "Set an alarm to remind me in 5 minutes"
        case .controlHome:
            return "List the status of my HomeKit smart home devices (apple-homekit list)"
        }
    }
}

/// Runs a predefined quick task — enables single-utterance Siri invocation.
struct QuickTaskIntent: AppIntent {
    static var title: LocalizedStringResource = "Quick Task"
    static var description = IntentDescription("Runs a predefined Minis task like sleep analysis, weather check, or morning briefing.")
    static var openAppWhenRun = false

    @Parameter(title: "Task")
    var task: QuickTask

    @Parameter(title: "Attachments", description: "Images, videos, or files to attach to the task. Accepts output from previous Shortcuts actions (e.g. filtered photos or documents).",
               supportedTypeIdentifiers: ["public.image", "public.movie", "public.data"],
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var files: [IntentFile]?

    @Parameter(title: "Model", description: "Specify a model or model group to use. Leave empty to use the app default.")
    var model: ModelSelectionEntity?

    @Parameter(title: "Wait for Result", description: "When enabled, waits for the AI to finish and returns the full response for use in subsequent actions.", default: false)
    var waitForResult: Bool

    static var parameterSummary: some ParameterSummary {
        Summary("Run \(\.$task)") {
            \.$model
            \.$files
            \.$waitForResult
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<SendPromptResult> & ProvidesDialog {
        BackgroundKeepAliveManager.shared.setup()

        // [T-shortcuts-eager-keepalive] Arm keep-alive BEFORE the ensureSession
        // await. QuickTask always starts a new session, so we don't have the
        // real id yet — register a placeholder, then swap to the real id once
        // ensureSessionReturningId returns. No-op unless the user has
        // enhancedBackgroundEffective on.
        let placeholderSid = "intent-eager:\(UUID().uuidString)"
        let eagerResult = BackgroundKeepAliveManager.shared.armEagerlyForShortcut(
            sessionId: placeholderSid, caller: "QuickTaskIntent")
        // [T-ios-session-status-mismatch] Guarantee the placeholder is dropped
        // from SessionActivityTracker no matter how this perform() exits (throw,
        // early return, ensureSessionReturningId failure). Without this, a
        // failed intent leaves "intent-eager:<UUID>" in activeSessions forever
        // → home spinner permanently on, ground-truth `chat.session.status`
        // reports phantom running sessions, Live Activity count inflates.
        // The successful swap path calls setInactive(placeholder) explicitly
        // BEFORE this defer runs; setInactive on an already-removed id is a
        // no-op, so this is idempotent.
        defer {
            if eagerResult.armed {
                SessionActivityTracker.shared.setInactive(placeholderSid,
                    source: "QuickTaskIntent.eager.cleanupDefer")
            }
        }
        // [T-shortcuts-diag-and-pending] Snapshot + mark pending under the
        // placeholder id (final id is created moments later; the marker is
        // cleared by the completion path either way).
        ShortcutRunTracker.logPerformEntry(
            intent: "QuickTaskIntent",
            sessionId: placeholderSid,
            eagerKeepAliveArmed: eagerResult.armed,
            eagerKeepAliveSkippedReason: eagerResult.skipReason
        )
        let pendingId = ShortcutRunTracker.markPending(
            intent: "QuickTaskIntent",
            sessionId: placeholderSid,
            eagerKeepAliveArmed: eagerResult.armed,
            eagerKeepAliveSkippedReason: eagerResult.skipReason
        )

        let vm = ViewModelCache.shared.createDraft()
        vm.sessionSource = "shortcut"
        await vm.ensureSessionReturningId()
        // [T-shortcuts-eager-keepalive] Real id now known — re-arm with it and
        // drop the placeholder.
        if let realSid = vm.sessionId {
            BackgroundKeepAliveManager.shared.armEagerlyForShortcut(
                sessionId: realSid, caller: "QuickTaskIntent.swap")
            ShortcutRunTracker.rebindSession(recordId: pendingId, toSessionId: realSid)
            SessionActivityTracker.shared.setInactive(placeholderSid,
                source: "QuickTaskIntent.eager.placeholderSwap")
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
                let name = SendPromptIntent.resolvedFileName(for: file)
                vm.addDataAttachment(data: file.data, fileName: name)
            }
        }

        vm.inputText = task.prompt
        vm.send()

        let sid = vm.sessionId ?? "unknown"

        // Resolve model name
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

        // Notification: started
        let taskDisplay = QuickTask.caseDisplayRepresentations[task]
        let taskName: String
        if let res = taskDisplay?.title {
            taskName = String(localized: res)
        } else {
            taskName = "Task"
        }
        ShortcutNotification.post(
            id: "shortcut-start-\(sid)",
            title: "Minis: \(taskName)",
            body: "\(modelName) is working on it…",
            sessionId: sid
        )

        if waitForResult {
            // Synchronous mode: wait for the agent to finish
            for await processing in vm.$isProcessing.values {
                if !processing { break }
            }

            // [T-shortcuts-diag-and-pending] Loop finished.
            ShortcutRunTracker.markCompleted(recordId: pendingId, reason: "waitForResult.done")

            let responseText = SendPromptIntent.extractResponseText(from: vm)

            ShortcutNotification.post(
                id: "shortcut-done-\(sid)",
                title: "Minis: \(taskName) Done",
                body: "\(modelName): \(String(responseText.prefix(200)))",
                sessionId: sid
            )

            let result = SendPromptResult(
                sessionId: sid,
                modelName: modelName,
                status: "Completed",
                isNewSession: true,
                prompt: task.prompt,
                responseText: responseText
            )
            return .result(value: result, dialog: "\(responseText.prefix(500))")
        }

        // Async mode: return immediately
        let capturedModelName = modelName
        let capturedSid = sid
        let capturedTaskName = taskName
        let capturedPendingId = pendingId
        Task { @MainActor in
            for await processing in vm.$isProcessing.values {
                if !processing { break }
            }

            // [T-shortcuts-diag-and-pending] Loop finished.
            ShortcutRunTracker.markCompleted(recordId: capturedPendingId, reason: "async.done")

            let summary = String(SendPromptIntent.extractResponseText(from: vm).prefix(200))

            ShortcutNotification.post(
                id: "shortcut-done-\(capturedSid)",
                title: "Minis: \(capturedTaskName) Done",
                body: "\(capturedModelName): \(summary)",
                sessionId: capturedSid
            )
        }

        let result = SendPromptResult(
            sessionId: sid,
            modelName: modelName,
            status: "Running",
            isNewSession: true,
            prompt: task.prompt
        )

        return .result(value: result, dialog: "\(taskName) started with \(modelName).")
    }
}
