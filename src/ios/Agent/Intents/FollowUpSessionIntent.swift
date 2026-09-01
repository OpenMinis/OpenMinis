import AppIntents
import Foundation
import UniformTypeIdentifiers

private let logger = AppLogger(category: "FollowUpIntent")

/// Sends a follow-up prompt to an existing session, continuing the conversation.
struct FollowUpSessionIntent: AppIntent {
    static var title: LocalizedStringResource = "Follow Up Session"
    static var description = IntentDescription("Sends a follow-up prompt to an existing Minis session, continuing the conversation with the AI agent.")
    static var openAppWhenRun = false

    @Parameter(title: "Session")
    var session: SessionEntity

    @Parameter(title: "Prompt", requestValueDialog: "What follow-up would you like to send?")
    var prompt: String

    @Parameter(title: "Attachments", description: "Images, videos, or files to attach to the prompt. Accepts output from previous Shortcuts actions (e.g. filtered photos or documents).",
               supportedTypeIdentifiers: ["public.image", "public.movie", "public.data"],
               inputConnectionBehavior: .connectToPreviousIntentResult)
    var files: [IntentFile]?

    @Parameter(title: "Wait for Result", description: "When enabled, waits for the AI to finish and returns the full response for use in subsequent actions.", default: false)
    var waitForResult: Bool

    @Parameter(title: "Send Notifications", description: "When enabled, posts a system notification when the task starts and again when it finishes. Turn this off for automations that run silently. The app's global task-notification setting still applies.", default: true)
    var sendCompletionNotification: Bool

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<SendPromptResult> & ProvidesDialog {
        BackgroundKeepAliveManager.shared.setup()

        // [T-shortcuts-eager-keepalive] AppIntent-woken processes get very
        // little wall-clock time before iOS suspends them. The normal
        // setActive() sits behind vm.send() → currentTask → await
        // ensureSession() (2 actor hops + 2 SQLite writes), so the Combine
        // publisher never fires before suspend and silent-audio keep-alive
        // never starts. Arm it HERE, synchronously, before any await —
        // strictly no-op unless the user has enhancedBackgroundEffective on.
        let eagerResult = BackgroundKeepAliveManager.shared.armEagerlyForShortcut(
            sessionId: session.id, caller: "FollowUpSessionIntent")
        // [T-shortcuts-diag-and-pending] Snapshot everything relevant to a
        // future post-mortem AND write a persistent pending-run marker that
        // the completion observer below clears once the agent loop finishes.
        // If the process gets suspended without ever hitting that clear, the
        // record stays and the next foreground scan surfaces guidance.
        ShortcutRunTracker.logPerformEntry(
            intent: "FollowUpSessionIntent",
            sessionId: session.id,
            eagerKeepAliveArmed: eagerResult.armed,
            eagerKeepAliveSkippedReason: eagerResult.skipReason
        )
        let pendingId = ShortcutRunTracker.markPending(
            intent: "FollowUpSessionIntent",
            sessionId: session.id,
            eagerKeepAliveArmed: eagerResult.armed,
            eagerKeepAliveSkippedReason: eagerResult.skipReason
        )

        let (vm, isNew) = ViewModelCache.shared.getOrCreate(for: session.id)
        // [T-shortcut-duplicate-completion-notification] See SendPromptIntent.
        // Deliberately not `sessionSource = "shortcut"` — see RetryRunIntent.
        vm.suppressGeneralCompletionNotification = true
        if isNew {
            await vm.loadSession()
        }

        // Wait if session is currently processing
        if vm.isProcessing {
            for await processing in vm.$isProcessing.values {
                if !processing { break }
            }
        }

        // Add file attachments if provided
        logger.info("📎 FollowUp files param: \(files == nil ? "nil" : "\(files!.count) files")")
        if let intentFiles = files {
            for (i, file) in intentFiles.enumerated() {
                let name = SendPromptIntent.resolvedFileName(for: file)
                let dataSize = file.data.count
                logger.info("📎 FollowUp file[\(i)]: name=\(name) dataSize=\(dataSize) type=\(file.type?.identifier ?? "nil")")
                vm.addDataAttachment(data: file.data, fileName: name)
            }
        }
        logger.info("📎 FollowUp vm.attachments after add: \(vm.attachments.count)")

        vm.inputText = prompt
        vm.send()
        logger.info("📎 FollowUp send() called, isProcessing=\(vm.isProcessing)")

        let sid = vm.sessionId ?? session.id

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

        // Notification: follow-up sent. See the note in SendPromptIntent.
        if sendCompletionNotification {
            let promptPreview = String(prompt.prefix(50))
            ShortcutNotification.post(
                id: "shortcut-followup-\(sid)",
                title: AppLocalized("Minis: Follow-up Sent"),
                body: "\(modelName): \(promptPreview)\(prompt.count > 50 ? "…" : "")",
                sessionId: sid
            )
        }

        if waitForResult {
            // Synchronous mode: wait for completion
            for await processing in vm.$isProcessing.values {
                if !processing { break }
            }

            // [T-shortcuts-diag-and-pending] Loop finished — clear the pending
            // record so the next foreground scan doesn't flag it as orphaned.
            ShortcutRunTracker.markCompleted(recordId: pendingId, reason: "waitForResult.done")

            let responseText = SendPromptIntent.extractResponseText(from: vm)

            // Per-run opt-out. ANDs with the app-wide toggle, which
            // ShortcutNotification.post checks internally — do not duplicate it here.
            if sendCompletionNotification {
                ShortcutNotification.post(
                    id: "shortcut-followup-done-\(sid)",
                    title: AppLocalized("Minis: Follow-up Done"),
                    body: "\(modelName): \(String(responseText.prefix(200)))",
                    sessionId: sid
                )
            }

            let result = SendPromptResult(
                sessionId: sid,
                modelName: modelName,
                status: "Completed",
                isNewSession: false,
                prompt: prompt,
                responseText: responseText
            )
            return .result(value: result, dialog: "\(responseText.prefix(500))")
        }

        // Async mode: return immediately
        let capturedModelName = modelName
        let capturedSid = sid
        let capturedPendingId = pendingId
        let capturedSendCompletionNotification = sendCompletionNotification
        Task { @MainActor in
            for await processing in vm.$isProcessing.values {
                if !processing { break }
            }

            // [T-shortcuts-diag-and-pending] Loop finished — clear the pending
            // record. If the process is suspended before we reach this line
            // (the very bug we're diagnosing), the record survives and the
            // next foreground scan surfaces guidance based on the recorded
            // toggle snapshot.
            ShortcutRunTracker.markCompleted(recordId: capturedPendingId, reason: "async.done")

            let summary = String(SendPromptIntent.extractResponseText(from: vm).prefix(200))

            if capturedSendCompletionNotification {
                ShortcutNotification.post(
                    id: "shortcut-followup-done-\(capturedSid)",
                    title: AppLocalized("Minis: Follow-up Done"),
                    body: "\(capturedModelName): \(summary)",
                    sessionId: capturedSid
                )
            }
        }

        let result = SendPromptResult(
            sessionId: sid,
            modelName: modelName,
            status: "Running",
            isNewSession: false,
            prompt: prompt
        )

        return .result(value: result, dialog: IntentDialog(stringLiteral: AppLocalized("Follow-up sent to \(session.displayName) with \(modelName).")))
    }

    // See the note in QuickTaskIntent: without a `parameterSummary` the action
    // card can render title-only, leaving `sendCompletionNotification`
    // untoggleable. `prompt` and `session` are the required inputs and go on the
    // Summary line; the rest are refinements under "Show More".
    static var parameterSummary: some ParameterSummary {
        Summary("Send \(\.$prompt) to \(\.$session)") {
            \.$files
            \.$waitForResult
            \.$sendCompletionNotification
        }
    }
}
