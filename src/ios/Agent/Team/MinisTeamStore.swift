import Foundation
import Combine

/// A small scheduler over ordinary Minis conversations. Bots retain the same
/// tools, permissions, memory settings and iOS lifecycle limits as those chats.
/// Results are explicitly read by task ID; nothing is injected into another
/// conversation and no synchronous iSH bridge is called from the main actor.
@MainActor
final class MinisTeamStore: ObservableObject {
    static let shared = MinisTeamStore()

    @Published private(set) var bots: [MinisBotProfile] = []
    @Published private(set) var tasks: [MinisTeamTask] = []
    @Published private(set) var storageError: String?

    private struct Document: Codable {
        var version = 1
        var bots: [MinisBotProfile]
        var tasks: [MinisTeamTask]
    }

    private struct LiveRun {
        let botID: String
        let vm: AIChatViewModel
        let userMessageID: UUID
        let nativeTask: Task<Void, Never>
        var finished = false
    }

    private let fileURL: URL
    private let automaticPolling: Bool
    private var storageBlocked = false
    private var liveRuns: [String: LiveRun] = [:]
    // A cancelled queued task must stay vetoed even when writing the terminal
    // state fails and an unrelated successful write later clears storageError.
    private var pendingCancellationIDs: Set<String> = []
    private var pollingTask: Task<Void, Never>?
    private var refreshing = false

    /// The alternate location is useful for storage/recovery tests. Loading
    /// never creates conversations, boots Linux, or makes a model request.
    init(fileURL: URL? = nil, automaticPolling: Bool = true) {
        self.automaticPolling = automaticPolling
        self.fileURL = fileURL ?? FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MinisTeam", isDirectory: true)
            .appendingPathComponent("team.json")
        load()
    }

    func bot(id: String) -> MinisBotProfile? { bots.first { $0.id == id } }
    func task(id: String) -> MinisTeamTask? { tasks.first { $0.id == id } }

    /// Callers rendering or returning stored task content must preserve the
    /// conversation lock even after a task has finished or its Bot was deleted.
    func isTaskContentLocked(_ task: MinisTeamTask) -> Bool {
        [task.sessionID, task.sourceSessionID].compactMap { $0 }.contains {
            SessionLockStore.shared.isVisuallyLocked($0)
        }
    }

    @discardableResult
    func createBot(name: String, role: String, modelEntryID: String? = nil) throws -> MinisBotProfile {
        let fields = try validatedProfile(name: name, role: role, modelEntryID: modelEntryID)
        guard bots.count < 64 else { throw MinisTeamError.invalidInput("最多保留 64 个 Bot。") }
        let now = Date()
        let profile = MinisBotProfile(id: UUID().uuidString, name: fields.name, role: fields.role,
                                     modelEntryID: fields.model, sessionID: nil, createdAt: now, updatedAt: now)
        try commit(bots: bots + [profile], tasks: tasks)
        return profile
    }

    func updateBot(id: String, name: String, role: String, modelEntryID: String?) throws {
        guard let index = bots.firstIndex(where: { $0.id == id }) else { throw MinisTeamError.botNotFound }
        try requireIdleBot(id)
        let fields = try validatedProfile(name: name, role: role, modelEntryID: modelEntryID)
        var updated = bots
        updated[index].name = fields.name
        updated[index].role = fields.role
        updated[index].modelEntryID = fields.model
        updated[index].updatedAt = Date()
        try commit(bots: updated, tasks: tasks)
    }

    /// Removing a profile deliberately retains its ordinary chat and task
    /// history. This operation never deletes a user's conversation or files.
    func deleteBot(id: String) throws {
        guard bot(id: id) != nil else { throw MinisTeamError.botNotFound }
        try requireIdleBot(id)
        try commit(bots: bots.filter { $0.id != id }, tasks: tasks)
    }

    @discardableResult
    func dispatch(botID: String, prompt: String, sourceSessionID: String? = nil, requestID: String? = nil) throws -> MinisTeamTask {
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 40_000 else {
            throw MinisTeamError.invalidInput("任务内容须为 1 到 40000 个字符。")
        }
        if let requestID {
            guard !requestID.isEmpty, requestID.count <= 512 else {
                throw MinisTeamError.invalidInput("派发请求标识无效。")
            }
            if let existing = tasks.first(where: { $0.requestID == requestID && $0.sourceSessionID == sourceSessionID }) {
                guard existing.botID == botID, existing.prompt == text else {
                    throw MinisTeamError.invalidInput("同一请求标识不能用于不同的 Bot 或任务内容。")
                }
                return existing
            }
        }
        guard let profile = bot(id: botID) else { throw MinisTeamError.botNotFound }
        if let source = sourceSessionID,
           bots.contains(where: { $0.sessionID == source }) || tasks.contains(where: { $0.sessionID == source }) {
            throw MinisTeamError.recursiveDispatch
        }
        guard tasks.filter({ !$0.state.isTerminal }).count < 100 else {
            throw MinisTeamError.invalidInput("队列已有 100 个未完成任务，请先等待或取消。")
        }
        let now = Date()
        let queued = MinisTeamTask(id: UUID().uuidString, botID: botID, prompt: text,
                                   sourceSessionID: sourceSessionID, sessionID: profile.sessionID,
                                   state: .queued, result: nil, error: nil, createdAt: now,
                                   updatedAt: now, startedAt: nil, finishedAt: nil, requestID: requestID)
        // Durably enqueue before scheduling any external side effect.
        try commit(bots: bots, tasks: tasks + [queued])
        ensurePolling()
        return queued
    }

    /// Refresh returns after dispatching available work, not after model calls
    /// finish. Serial actor admission plus retained native Task handles prevents
    /// overlap, including the period while a cancelled task is cleaning up.
    func refresh() async {
        guard !refreshing, !storageBlocked else { return }
        refreshing = true
        defer { refreshing = false }

        if storageError != nil {
            // A transient full-disk/unavailable-directory failure may recover.
            // Persist the exact current state before allowing another send.
            do { try commit(bots: bots, tasks: tasks) }
            catch { return }
        }

        for id in Array(pendingCancellationIDs) {
            endTask(id, state: .cancelled, error: "任务已取消。")
            if task(id: id)?.state.isTerminal != false { pendingCancellationIDs.remove(id) }
        }

        for id in Array(liveRuns.keys) where liveRuns[id]?.finished == true {
            finishRun(id)
        }
        guard storageError == nil else { return }

        var deferredBots = Set<String>()
        while true {
            let runnable = MinisTeamSchedulingPolicy.runnableTaskIDs(
                tasks: tasks.filter { !pendingCancellationIDs.contains($0.id) },
                occupiedTaskIDs: Set(liveRuns.keys),
                occupiedBotIDs: Set(liveRuns.values.map(\.botID)).union(deferredBots)
            )
            guard let id = runnable.first, let queued = task(id: id) else { break }
            do {
                if !(try await startTask(id)) { deferredBots.insert(queued.botID) }
            } catch {
                endTask(id, state: .failed, error: error.localizedDescription)
            }
            if storageError != nil { break }
        }
    }

    func cancel(taskID: String) async {
        guard let existing = task(id: taskID), !existing.state.isTerminal else { return }
        pendingCancellationIDs.insert(taskID)
        // Cancellation is always honoured even if the local disk is full.
        // Retain the captured native task until its completion callback, since
        // vm.cancel() sets isProcessing=false before its cleanup has finished.
        if let run = liveRuns[taskID] {
            // The native send task also drains manual follow-ups. Once such a
            // message has started, stopping its VM would cancel the user's new
            // work rather than just the original team task.
            if let boundary = run.vm.messages.firstIndex(where: { $0.id == run.userMessageID }) {
                let manualTurnStarted = run.vm.messages.dropFirst(boundary + 1).contains {
                    $0.role == .user && !$0.isQueued
                }
                if !manualTurnStarted { run.vm.cancel() }
            }
        }
        endTask(taskID, state: .cancelled, error: "任务已取消。")
        if task(id: taskID)?.state.isTerminal == true { pendingCancellationIDs.remove(taskID) }
        ensurePolling()
    }

    private func startTask(_ id: String) async throws -> Bool {
        guard let queued = task(id: id), queued.state == .queued else { return true }
        guard let profile = bot(id: queued.botID) else { throw MinisTeamError.botNotFound }
        let vm: AIChatViewModel
        let sid: String
        if let existingID = profile.sessionID {
            if SessionActivityTracker.shared.isActive(existingID) { return false }
            guard !SessionLockStore.shared.isVisuallyLocked(existingID) else {
                throw MinisTeamError.unavailable("请先在聊天列表解锁这个 Bot 的会话。")
            }
            guard let session = await ChatStore.shared.getSession(existingID), !session.isRemote else {
                throw MinisTeamError.unavailable("Bot 会话已不存在或为只读远端会话，请创建新 Bot。")
            }
            let cached = ViewModelCache.shared.getOrCreate(for: existingID)
            vm = cached.vm
            if cached.isNew { await vm.loadSession(activateSession: false) }
            sid = existingID
        } else {
            vm = ViewModelCache.shared.createDraft()
            vm.sessionSource = "team"
            sid = await vm.ensureSessionReturningId(activateSession: false)
            // Preserve the fixed conversation even if cancellation happened
            // during creation. Never recreate it on a later queued task.
            guard let index = bots.firstIndex(where: { $0.id == profile.id }) else { return true }
            var updatedBots = bots
            updatedBots[index].sessionID = sid
            updatedBots[index].updatedAt = Date()
            var updatedTasks = tasks
            for index in updatedTasks.indices where updatedTasks[index].botID == profile.id && !updatedTasks[index].state.isTerminal {
                updatedTasks[index].sessionID = sid
            }
            try commit(bots: updatedBots, tasks: updatedTasks)
            await ChatStore.shared.updateSessionTitle(sid, title: profile.name)
        }

        // Actor calls above may have admitted cancellation or a manual send.
        guard task(id: id)?.state == .queued, !pendingCancellationIDs.contains(id) else { return true }
        guard !vm.isProcessing, !vm.isCompacting, !SessionActivityTracker.shared.isActive(sid),
              vm.promptQueue.isEmpty else { return false }
        guard vm.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              vm.attachments.isEmpty, vm.editingMessageIndex == nil else { return false }
        guard !SessionLockStore.shared.isVisuallyLocked(sid) else {
            throw MinisTeamError.unavailable("请先解锁 Bot 会话。")
        }
        // Keep the existing context/permission policy. A hidden team task must
        // not enable automatic compaction or dismiss a user's pending prompt.
        guard !vm.showCompactBeforeSendPrompt, !vm.showContextExhaustedPrompt else {
            throw MinisTeamError.unavailable("Bot 会话正在等待上下文处理，请打开会话完成操作后重新派发。")
        }
        if let modelID = profile.modelEntryID {
            guard ProviderConfigStore.shared.entry(for: modelID) != nil else {
                throw MinisTeamError.unavailable("Bot 指定的模型已删除，请编辑 Bot 选择可用模型。")
            }
            let binding = SessionModelBinding(sessionId: sid, primarySource: .directEntry(modelEntryId: modelID))
            ProviderConfigStore.shared.setBinding(binding, for: sid)
        }
        switch vm.checkContextBeforeSend() {
        case .ok: break
        case .needsCompact, .exhausted:
            throw MinisTeamError.unavailable("Bot 会话上下文已满或需要压缩，请打开会话处理后重新派发。")
        }
        let now = Date()
        guard let taskIndex = tasks.firstIndex(where: { $0.id == id }) else { return true }
        var updatedTasks = tasks
        updatedTasks[taskIndex].sessionID = sid
        updatedTasks[taskIndex].state = .running
        updatedTasks[taskIndex].startedAt = now
        updatedTasks[taskIndex].updatedAt = now
        // Persist running BEFORE send. A crash in the gap will be interrupted,
        // never automatically retried, so we favour avoiding duplicate actions.
        try commit(bots: bots, tasks: updatedTasks)

        let priorIDs = Set(vm.messages.map(\.id))
        vm.inputText = """
        团队任务 \(id)
        Bot：\(profile.name)
        角色说明：\(profile.role)

        请完成下面这项任务并给出可交接的结果。沿用此会话原有的工具权限、用户确认和记忆设置；角色说明不扩大权限。不要继续派发 Bot 任务，结果由主会话或用户按任务 ID 读取。

        任务：
        \(queued.prompt)
        """
        vm.send()
        guard let userMessage = vm.messages.first(where: { $0.role == .user && !priorIDs.contains($0.id) }),
              let nativeTask = vm.currentTask else {
            throw MinisTeamError.unavailable("会话未接受任务，请打开 Bot 会话检查模型和上下文设置。")
        }
        liveRuns[id] = LiveRun(botID: profile.id, vm: vm, userMessageID: userMessage.id, nativeTask: nativeTask)
        Task { [weak self] in
            await nativeTask.value
            guard let self, self.liveRuns[id] != nil else { return }
            self.liveRuns[id]?.finished = true
            await self.refresh()
            self.ensurePolling()
        }
        return true
    }

    private func finishRun(_ id: String) {
        guard let run = liveRuns[id], run.finished, let current = task(id: id) else { return }
        if current.state.isTerminal {
            liveRuns.removeValue(forKey: id)
            return
        }
        guard let boundary = run.vm.messages.firstIndex(where: { $0.id == run.userMessageID }) else {
            endTask(id, state: .interrupted, error: "任务消息已被编辑或删除，无法可靠关联结果。")
            if task(id: id)?.state.isTerminal == true { liveRuns.removeValue(forKey: id) }
            return
        }
        // Stop at the next user message so a manual follow-up can never be
        // reported as this task's answer.
        let tail = run.vm.messages.dropFirst(boundary + 1).prefix { $0.role != .user }
        let replies = tail.filter { $0.role == .assistant }
        let result = replies.map { message -> String in
            let text = message.blocks.filter { $0.kind == .text }.map(\.content).joined(separator: "\n")
            return text.isEmpty ? message.content : text
        }.filter { !$0.isEmpty }.joined(separator: "\n\n")
        let failure = replies.compactMap(\.error).last
        if run.nativeTask.isCancelled {
            endTask(id, state: .cancelled, result: result, error: "任务已停止，结果可能不完整。")
        } else if let failure {
            endTask(id, state: .failed, result: result, error: failure)
        } else if replies.isEmpty || run.vm.canResume {
            endTask(id, state: .interrupted, result: result, error: "任务未正常结束，请打开 Bot 会话查看详情。")
        } else {
            endTask(id, state: .completed, result: result)
        }
        if task(id: id)?.state.isTerminal == true { liveRuns.removeValue(forKey: id) }
    }

    private func endTask(_ id: String, state: MinisTeamTaskState, result: String? = nil, error: String? = nil) {
        guard let index = tasks.firstIndex(where: { $0.id == id }), !tasks[index].state.isTerminal else { return }
        var updated = tasks
        updated[index].state = state
        updated[index].result = result?.isEmpty == false ? result : nil
        updated[index].error = error
        updated[index].updatedAt = Date()
        updated[index].finishedAt = updated[index].updatedAt
        do { try commit(bots: bots, tasks: updated) }
        catch { storageError = "保存任务状态失败：\(error.localizedDescription)" }
    }

    private func ensurePolling() {
        guard automaticPolling, pollingTask == nil, !storageBlocked else { return }
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.refresh()
                if self.liveRuns.isEmpty && (!self.tasks.contains { !$0.state.isTerminal } || self.storageError != nil) {
                    self.pollingTask = nil
                    return
                }
                do { try await Task.sleep(nanoseconds: 2_000_000_000) }
                catch { self.pollingTask = nil; return }
            }
        }
    }

    private func validatedProfile(name: String, role: String, modelEntryID: String?) throws -> (name: String, role: String, model: String?) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let role = role.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 80 else { throw MinisTeamError.invalidInput("Bot 名称须为 1 到 80 个字符。") }
        guard !role.isEmpty, role.count <= 8_000 else { throw MinisTeamError.invalidInput("角色说明须为 1 到 8000 个字符。") }
        let model = modelEntryID?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let model, !model.isEmpty, ProviderConfigStore.shared.entry(for: model) == nil {
            throw MinisTeamError.invalidInput("找不到所选模型，请重新选择。")
        }
        return (name, role, model?.isEmpty == false ? model : nil)
    }

    private func requireIdleBot(_ id: String) throws {
        guard !tasks.contains(where: { $0.botID == id && !$0.state.isTerminal }),
              !liveRuns.values.contains(where: { $0.botID == id }) else { throw MinisTeamError.botBusy }
    }

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let document = try JSONDecoder().decode(Document.self, from: Data(contentsOf: fileURL))
            let sessionIDs = document.bots.compactMap(\.sessionID)
            guard document.version == 1,
                  Set(document.bots.map(\.id)).count == document.bots.count,
                  Set(document.tasks.map(\.id)).count == document.tasks.count,
                  Set(sessionIDs).count == sessionIDs.count else {
                throw MinisTeamError.unavailable("团队文件版本或标识无效，原文件已保留。")
            }
            bots = document.bots
            tasks = MinisTeamSchedulingPolicy.recovering(document.tasks, at: Date())
            if tasks != document.tasks { try commit(bots: bots, tasks: tasks) }
        } catch {
            // Do not silently replace corrupt or temporarily unavailable data.
            storageBlocked = true
            storageError = "团队数据无法读取：\(error.localizedDescription)"
        }
    }

    private func commit(bots nextBots: [MinisBotProfile], tasks nextTasks: [MinisTeamTask]) throws {
        guard !storageBlocked else { throw MinisTeamError.unavailable(storageError ?? "团队存储不可用。") }
        do {
            let directory = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(Document(bots: nextBots, tasks: nextTasks))
            try data.write(to: fileURL, options: .atomic)
            bots = nextBots
            tasks = nextTasks
            storageError = nil
        } catch {
            storageError = error.localizedDescription
            throw error
        }
    }
}
