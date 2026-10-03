//
//  SessionsOffloadBridge.swift
//  MinisApp
//
//  Swift bridge for minis-sessions-cli offload.
//  Provides synchronous @objc entry points that query ChatStore.
//

import Foundation

private let logger = AppLogger(category: "SessionsOffload")

@objc public class SessionsOffloadBridge: NSObject {

    // MARK: - List / Search Sessions

    /// Query sessions with optional filters. Returns NSDictionary with "sessions" array.
    /// Thread-safe: uses Task.detached to call ChatStore actor, blocks caller until done.
    /// Call from any thread EXCEPT main (semaphore would deadlock main).
    @objc public static func querySessions(
        sessionIds: [String]?,
        keywords: [String]?,
        startDate: Date?,
        endDate: Date?,
        limit: Int
    ) -> NSDictionary {
        // ChatStore is an actor — bridge into async via semaphore + Task.detached.
        // Called from the iSH guest thread (never main), so blocking is safe.
        let sem = DispatchSemaphore(value: 0)
        var result: [[String: Any]] = []

        Task.detached {
            let metas = await ChatStore.shared.querySessionsMeta(
                sessionIds: sessionIds,
                keywords: keywords,
                limit: limit,
                startDate: startDate,
                endDate: endDate
            )

            let dateFmt = DateFormatter()
            dateFmt.dateFormat = "yyyy-MM-dd HH:mm"

            result = metas.map { m in
                var dict: [String: Any] = [
                    "session_id": m.id,
                    "started_at": dateFmt.string(from: m.startedAt),
                    "last_active": dateFmt.string(from: m.lastActive),
                    "message_count": m.messageCount,
                ]
                if let t = m.title { dict["title"] = t }
                if let p = m.preview { dict["preview"] = p }
                if let s = m.source { dict["source"] = s }
                return dict
            }
            sem.signal()
        }
        sem.wait()

        return [
            "count": result.count,
            "sessions": result,
        ] as NSDictionary
    }

    // MARK: - Search Messages

    /// Search messages across sessions by keywords. Returns NSDictionary with "messages" array.
    @objc public static func searchMessages(
        sessionIds: [String]?,
        keywords: [String],
        startDate: Date?,
        endDate: Date?,
        limit: Int
    ) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: [[String: Any]] = []

        Task.detached {
            let matches = await ChatStore.shared.searchMessages(
                sessionIds: sessionIds,
                keywords: keywords,
                limit: limit,
                startDate: startDate,
                endDate: endDate
            )

            let dateFmt = DateFormatter()
            dateFmt.dateFormat = "yyyy-MM-dd HH:mm"

            result = matches.map { m in
                // [T-search-cli-title] session_title mirrors SessionList row's
                // `session.title ?? "Untitled"` fallback so CLI users can
                // identify a hit's conversation by name instead of having to
                // cross-reference the raw UUID against `list`.
                [
                    "session_id": m.sessionId,
                    "session_title": m.sessionTitle ?? "Untitled",
                    "message_id": m.messageId,
                    "role": m.role,
                    "created_at": dateFmt.string(from: m.createdAt),
                    "snippet": m.snippet,
                ] as [String: Any]
            }
            sem.signal()
        }
        sem.wait()

        return [
            "count": result.count,
            "messages": result,
        ] as NSDictionary
    }

    // MARK: - Load Messages by Session ID

    /// Load a page of messages for a specific session. Returns NSDictionary with "messages" array and pagination info.
    /// `full` switches the per-message text cap from 1000 to 50000 chars; messages whose
    /// stored text exceeds the cap are still truncated and marked with "truncated": true.
    /// [T-sessions-cli-messages-date-filter] (GH#200) `startDate`/`endDate`
    /// forward the CLI's `--start`/`--end` down to ChatStore. They were absent
    /// from this signature entirely, which is why the ObjC layer had nothing to
    /// pass and the flags were silently dropped.
    @objc public static func loadMessages(
        sessionId: String,
        offset: Int,
        limit: Int,
        full: Bool,
        startDate: Date?,
        endDate: Date?
    ) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var result: [[String: Any]] = []
        var totalCount = 0
        let maxChars = full ? 50_000 : 1_000

        Task.detached {
            let messages = await ChatStore.shared.loadMessagePage(
                sessionId: sessionId,
                offset: offset,
                limit: limit,
                maxChars: maxChars,
                startDate: startDate,
                endDate: endDate
            )
            // Counted over the SAME range so `total` describes the filtered set
            // the caller is paging through, not the whole session.
            totalCount = await ChatStore.shared.messageCount(sessionId: sessionId,
                                                             startDate: startDate,
                                                             endDate: endDate)

            let dateFmt = DateFormatter()
            dateFmt.dateFormat = "yyyy-MM-dd HH:mm"

            result = messages.map { m in
                var dict: [String: Any] = [
                    "message_id": m.messageId,
                    "role": m.role,
                    "created_at": dateFmt.string(from: m.createdAt),
                    "text": m.text,
                ]
                if m.truncated { dict["truncated"] = true }
                return dict
            }
            sem.signal()
        }
        sem.wait()

        return [
            "session_id": sessionId,
            "offset": offset,
            "limit": limit,
            "full": full,
            "max_chars": maxChars,
            "total": totalCount,
            "count": result.count,
            "messages": result,
        ] as NSDictionary
    }

    // MARK: - Shared helpers

    /// Resolve a binding source to a readable model display name.
    /// Returns nil when no matching entry is found.
    @MainActor
    private static func resolveBindingModelName(_ binding: SessionModelBinding) -> String? {
        let store = ProviderConfigStore.shared
        switch binding.primarySource {
        case .group(_, let resolvedEntryId):
            return store.entry(for: resolvedEntryId)?.model.displayName
        case .directEntry(let modelEntryId, _):
            return store.entry(for: modelEntryId)?.model.displayName
        }
    }

    /// Wait for `vm.isProcessing` to flip to false OR a timeout to elapse.
    /// Returns true if completed, false if timed out.
    @MainActor
    private static func waitForCompletion(vm: AIChatViewModel, timeoutSeconds: Int) async -> Bool {
        let effective = max(timeoutSeconds > 0 ? timeoutSeconds : 120, 1)
        return await withTaskGroup(of: Bool.self) { group in
            group.addTask { @MainActor in
                for await processing in vm.$isProcessing.values {
                    if !processing { return true }
                }
                return true
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(effective) * 1_000_000_000)
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
    }

    /// Load attachment files from disk and stage them on the VM.
    /// Returns the number of attachments successfully added.
    @MainActor
    private static func stageAttachments(paths: [String], on vm: AIChatViewModel) -> Int {
        var added = 0
        for path in paths {
            guard let data = FileManager.default.contents(atPath: path), !data.isEmpty else {
                logger.info("attachment file unreadable or empty: \(path)")
                continue
            }
            let name = (path as NSString).lastPathComponent
            vm.addDataAttachment(data: data, fileName: name)
            added += 1
        }
        return added
    }

    // MARK: - Send Prompt

    /// Send a prompt to a new or existing session.
    /// - Parameters:
    ///   - sessionId: nil → create a new session; non-nil → follow up an existing session.
    ///   - prompt: User message text.
    ///   - attachmentPaths: Absolute file paths to attach. Bridge reads bytes itself.
    ///   - modelEntryId: nil → leave the resolver alone (uses Agent Loop default).
    ///                   non-nil → install a session-level directEntry binding before send().
    ///   - source: Written to `vm.sessionSource` on new sessions (e.g. "cli").
    ///
    /// Returns immediately once the prompt has been dispatched. `status` is
    /// the truthful outcome of `submitProgrammaticPrompt`: "Running" (a new
    /// loop started), "Queued" (the session was busy — the prompt runs as a
    /// fresh turn when its loop ends), or ok:false with `error` when the vm
    /// declined it. The caller polls via `getSessionStatus` — the prior
    /// `--wait` / `--timeout` blocking mode was removed because the iSH
    /// shell is single-threaded and blocking it deadlocked agent loops that
    /// dispatched prompts then tried to do further work.
    ///
    /// [T-p0-programmatic-prompt] This used to do `vm.inputText = prompt;
    /// vm.send()` and answer `ok: true, status: Running` unconditionally.
    /// `send()` silently returns while `isProcessing` is true, so a send into
    /// a busy session — including an agent sending to ITSELF from a
    /// shell_execute — reported success and did nothing.
    @objc public static func sendPrompt(
        sessionId: String?,
        prompt: String,
        attachmentPaths: [String],
        modelEntryId: String?,
        source: String
    ) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var output: [String: Any] = [:]

        Task { @MainActor in
            // Resolve / create the VM.
            let vm: AIChatViewModel
            let isNew: Bool
            if let sid = sessionId, !sid.isEmpty {
                let (cached, fresh) = ViewModelCache.shared.getOrCreate(for: sid)
                vm = cached
                if fresh { await vm.loadSession() }
                isNew = false
            } else {
                vm = ViewModelCache.shared.createDraft()
                vm.sessionSource = source.isEmpty ? "cli" : source
                _ = await vm.ensureSessionReturningId()
                isNew = true
            }

            guard let sid = vm.sessionId else {
                output = [
                    "ok": false,
                    "error": "session_unavailable",
                ]
                sem.signal()
                return
            }

            // Apply model override if requested.
            if let entryId = modelEntryId, !entryId.isEmpty {
                let store = ProviderConfigStore.shared
                guard store.entry(for: entryId) != nil else {
                    output = [
                        "ok": false,
                        "error": "model_not_found",
                        "model_entry_id": entryId,
                    ]
                    sem.signal()
                    return
                }
                let binding = SessionModelBinding(
                    sessionId: sid,
                    primarySource: .directEntry(modelEntryId: entryId)
                )
                store.setBinding(binding, for: sid)
            }

            // Attachments.
            _ = stageAttachments(paths: attachmentPaths, on: vm)

            // Send through the one programmatic channel.
            let outcome = vm.submitProgrammaticPrompt(prompt, origin: .cli, silent: true)

            // Resolve model name for the response.
            var modelName = vm.selectedModel.displayName
            if let binding = ProviderConfigStore.shared.binding(for: sid),
               let resolved = resolveBindingModelName(binding) {
                modelName = resolved
            }

            switch outcome {
            case .sent, .queued:
                output = [
                    "ok": true,
                    "action": "send",
                    "session_id": sid,
                    "is_new_session": isNew,
                    "model_name": modelName,
                    "status": outcome == .sent ? "Running" : "Queued",
                    "queued": outcome == .queued,
                    "queue_position": outcome == .queued ? vm.promptQueue.count : 0,
                    "prompt": prompt,
                    "response_text": "",
                ]
            case .rejected(let reason):
                output = [
                    "ok": false,
                    "action": "send",
                    "session_id": sid,
                    "is_new_session": isNew,
                    "model_name": modelName,
                    "error": "send_rejected",
                    "reason": reason,
                    "prompt": prompt,
                ]
            }
            sem.signal()
        }
        sem.wait()
        return output as NSDictionary
    }

    // MARK: - Retry Message

    /// Retry (re-run) from a specific user message in an existing session.
    /// - Parameters:
    ///   - sessionId: Session to operate on.
    ///   - messageId: nil → retry from last user message; non-nil → match by UUID string.
    ///   - attachmentPaths: Non-empty replaces original attachments; empty preserves them.
    ///
    /// Always returns immediately with status "Retrying" once the retry has
    /// been dispatched. The 30-second pre-dispatch wait on the existing
    /// `isProcessing` flag is preserved — that's a guard against racing an
    /// active stream, not a `--wait` blocking mode. The post-dispatch
    /// blocking behavior was removed for the same single-threaded-shell
    /// reason as `sendPrompt`.
    @objc public static func retryMessage(
        sessionId: String,
        messageId: String?,
        attachmentPaths: [String]
    ) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var output: [String: Any] = [:]

        Task { @MainActor in
            let (vm, fresh) = ViewModelCache.shared.getOrCreate(for: sessionId)
            if fresh { await vm.loadSession() }

            // If a prior run is still in flight, wait briefly (capped at 30s)
            // so retry doesn't race the active stream.
            if vm.isProcessing {
                _ = await waitForCompletion(vm: vm, timeoutSeconds: 30)
            }

            // Locate the target user message.
            let target: ChatMessage?
            if let mid = messageId, !mid.isEmpty {
                target = vm.messages.first { $0.role == .user && $0.id.uuidString == mid }
            } else {
                target = vm.messages.last { $0.role == .user }
            }
            guard let targetMessage = target else {
                output = [
                    "ok": false,
                    "error": "message_not_found",
                    "session_id": sessionId,
                ]
                sem.signal()
                return
            }

            // Build replacement attachments (mirror RetryRunIntent's stage-then-detach pattern).
            var replacementAttachments: [InputAttachment]? = nil
            if !attachmentPaths.isEmpty {
                let countBefore = vm.attachments.count
                _ = stageAttachments(paths: attachmentPaths, on: vm)
                if vm.attachments.count > countBefore {
                    replacementAttachments = Array(vm.attachments.dropFirst(countBefore))
                    vm.attachments.removeSubrange(countBefore...)
                }
            }

            vm.retryFromMessage(targetMessage.id, replacementAttachments: replacementAttachments)

            // Resolve model name.
            var modelName = vm.selectedModel.displayName
            if let binding = ProviderConfigStore.shared.binding(for: sessionId),
               let resolved = resolveBindingModelName(binding) {
                modelName = resolved
            }

            output = [
                "ok": true,
                "action": "retry",
                "session_id": sessionId,
                "message_id": targetMessage.id.uuidString,
                "model_name": modelName,
                "status": "Retrying",
                "prompt": String(targetMessage.content.prefix(100)),
                "response_text": "",
            ]
            sem.signal()
        }
        sem.wait()
        return output as NSDictionary
    }

    // MARK: - Open Session (navigate UI to a given session)

    /// Routes the app UI to the given session, mirroring what
    /// `minis://sessions/<id>` does. Validates that the session exists
    /// (via ChatStore) before posting the open-session notification so
    /// a typo from the CLI doesn't leave the user staring at a stale
    /// navigation. Returns immediately — UI navigation happens on the
    /// main actor on the next runloop.
    @objc public static func openSession(sessionId: String) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var output: [String: Any] = [:]

        Task.detached {
            let session = await ChatStore.shared.getSession(sessionId)
            guard let session else {
                output = [
                    "ok": false,
                    "action": "open",
                    "error": "session_not_found",
                    "session_id": sessionId,
                ]
                sem.signal()
                return
            }
            await MainActor.run {
                NotificationCenter.default.post(
                    name: .openSessionFromIntent,
                    object: nil,
                    userInfo: ["sessionId": session.id]
                )
            }
            output = [
                "ok": true,
                "action": "open",
                "session_id": session.id,
                "title": session.title ?? "",
            ]
            sem.signal()
        }
        sem.wait()
        return output as NSDictionary
    }

    // MARK: - Get Session Status

    /// Query session status — works whether or not the VM is in cache.
    @objc public static func getSessionStatus(sessionId: String) -> NSDictionary {
        let sem = DispatchSemaphore(value: 0)
        var output: [String: Any] = [:]

        Task { @MainActor in
            // SessionActivityTracker is the authoritative is-running signal —
            // VM may have been evicted from cache but the tracker survives.
            let isRunning = SessionActivityTracker.shared.isActive(sessionId)

            // Pull canonical session metadata from the DB.
            let session = await ChatStore.shared.getSession(sessionId)
            guard let session else {
                output = [
                    "ok": false,
                    "error": "session_not_found",
                    "session_id": sessionId,
                ]
                sem.signal()
                return
            }

            // Resolve model display name via the session binding.
            var modelName = session.modelId
            if let binding = ProviderConfigStore.shared.binding(for: sessionId),
               let resolved = resolveBindingModelName(binding) {
                modelName = resolved
            }

            // lastMessage / lastTool: prefer live VM when cached, fall back to DB.
            var lastMessage = session.lastMessage ?? ""
            var lastTool = ""
            if let vm = ViewModelCache.shared.get(for: sessionId) {
                if let lastAssistant = vm.messages.last(where: { $0.role == .assistant }) {
                    let textBlocks = lastAssistant.blocks
                        .filter { $0.kind == .text }
                        .map { $0.content }
                    let joined = textBlocks.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
                    if !joined.isEmpty { lastMessage = joined }
                    let toolBlock = lastAssistant.blocks.last { block in
                        switch block.kind {
                        case .text, .thinking, .info: return false
                        default: return true
                        }
                    }
                    if let toolBlock {
                        lastTool = toolBlock.toolSummary ?? toolBlock.toolDescription
                    }
                }
            }

            let dateFmt = DateFormatter()
            dateFmt.dateFormat = "yyyy-MM-dd HH:mm"

            output = [
                "ok": true,
                "action": "status",
                "data": [
                    "session_id": session.id,
                    "title": session.title ?? "",
                    "model_name": modelName,
                    "is_running": isRunning,
                    "last_message": lastMessage,
                    "last_tool": lastTool,
                    "updated_at": dateFmt.string(from: session.updatedAt),
                ] as [String: Any],
            ]
            sem.signal()
        }
        sem.wait()
        return output as NSDictionary
    }
}
