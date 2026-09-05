import XCTest
@testable import TeamHarness

@MainActor
final class MinisTeamStoreTests: XCTestCase {
    private func location() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("team.json")
    }

    private func makeBot(_ store: MinisTeamStore, _ name: String) throws -> MinisBotProfile {
        try store.createBot(name: name, role: "Complete the assigned task")
    }

    private func settle() async {
        for _ in 0..<30 { await Task.yield() }
    }

    private func stop(_ store: MinisTeamStore) async {
        for task in store.tasks where !task.state.isTerminal { await store.cancel(taskID: task.id) }
        for bot in store.bots {
            if let id = bot.sessionID, let vm = ViewModelCache.shared.entries[id] {
                await vm.complete("late result during test cleanup")
            }
        }
        await settle()
    }

    func testThreeSlotLimitAndFixedSessionSerialization() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let a = try makeBot(store, "A"), b = try makeBot(store, "B")
        let c = try makeBot(store, "C"), d = try makeBot(store, "D")
        let a1 = try store.dispatch(botID: a.id, prompt: "first")
        let a2 = try store.dispatch(botID: a.id, prompt: "second")
        _ = try store.dispatch(botID: b.id, prompt: "third")
        _ = try store.dispatch(botID: c.id, prompt: "fourth")
        let d1 = try store.dispatch(botID: d.id, prompt: "fifth")
        await store.refresh()
        XCTAssertEqual(store.tasks.filter { $0.state == .running }.count, 3)
        XCTAssertEqual(store.task(id: a2.id)?.state, .queued)
        XCTAssertEqual(store.task(id: d1.id)?.state, .queued)
        let session = try XCTUnwrap(store.bot(id: a.id)?.sessionID)
        let vm = try XCTUnwrap(ViewModelCache.shared.entries[session])
        await vm.complete("first answer")
        await settle()
        await store.refresh()
        XCTAssertEqual(store.task(id: a1.id)?.state, .completed)
        XCTAssertEqual(store.task(id: a1.id)?.result, "first answer")
        XCTAssertEqual(store.task(id: a2.id)?.state, .running)
        XCTAssertEqual(store.task(id: a2.id)?.sessionID, session)
        XCTAssertEqual(store.tasks.filter { $0.state == .running }.count, 3)
        XCTAssertEqual(Set(store.tasks.filter { $0.state == .running }.map(\.botID)).count, 3)
        await stop(store)
    }

    func testCancelledNativeTaskOccupiesSlotUntilCleanupAndLateResultCannotOverwrite() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        let first = try store.dispatch(botID: bot.id, prompt: "one")
        let second = try store.dispatch(botID: bot.id, prompt: "two")
        await store.refresh()
        let sid = try XCTUnwrap(store.bot(id: bot.id)?.sessionID)
        let vm = try XCTUnwrap(ViewModelCache.shared.entries[sid])
        await store.cancel(taskID: first.id)
        await store.refresh()
        XCTAssertEqual(store.task(id: first.id)?.state, .cancelled)
        XCTAssertEqual(store.task(id: second.id)?.state, .queued)
        XCTAssertFalse(vm.isProcessing, "The native UI can say stopped while cleanup is still pending")
        await vm.complete("response that arrived after cancellation")
        await settle()
        await store.refresh()
        XCTAssertEqual(store.task(id: first.id)?.state, .cancelled)
        XCTAssertNil(store.task(id: first.id)?.result)
        XCTAssertEqual(store.task(id: second.id)?.state, .running)
        await stop(store)
    }

    func testRestartInterruptsBothQueuedAndRunningWithoutSendingAgain() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let firstStore = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(firstStore, "Worker")
        _ = try firstStore.dispatch(botID: bot.id, prompt: "running task")
        _ = try firstStore.dispatch(botID: bot.id, prompt: "queued task")
        await firstStore.refresh()
        let sends = AIChatViewModel.sendCount
        let restored = MinisTeamStore(fileURL: url, automaticPolling: false)
        XCTAssertEqual(restored.tasks.count, 2)
        XCTAssertTrue(restored.tasks.allSatisfy { $0.state == .interrupted })
        await restored.refresh()
        XCTAssertEqual(AIChatViewModel.sendCount, sends)
        XCTAssertEqual(restored.bots.first?.sessionID, firstStore.bots.first?.sessionID)
        await stop(firstStore)
    }

    func testPersistentRequestIDRejectsConflictAndSurvivesDeletedProfile() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        let first = try store.dispatch(botID: bot.id, prompt: "task", sourceSessionID: "parent", requestID: "call_1")
        let replay = try store.dispatch(botID: bot.id, prompt: "task", sourceSessionID: "parent", requestID: "call_1")
        XCTAssertEqual(first.id, replay.id)
        XCTAssertEqual(store.tasks.count, 1)
        XCTAssertThrowsError(try store.dispatch(botID: bot.id, prompt: "changed", sourceSessionID: "parent", requestID: "call_1"))
        await store.cancel(taskID: first.id)
        try store.deleteBot(id: bot.id)
        let restored = MinisTeamStore(fileURL: url, automaticPolling: false)
        let replayAfterDelete = try restored.dispatch(botID: bot.id, prompt: "task", sourceSessionID: "parent", requestID: "call_1")
        XCTAssertEqual(replayAfterDelete.id, first.id)
        XCTAssertEqual(replayAfterDelete.state, .cancelled)
        XCTAssertTrue(restored.bots.isEmpty)
    }

    func testStorageFailureBeforeDispatchDoesNotStartModel() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        let task = try store.dispatch(botID: bot.id, prompt: "must not send")
        let sends = AIChatViewModel.sendCount
        let directory = url.deletingLastPathComponent()
        try FileManager.default.removeItem(at: directory)
        try Data("not a directory".utf8).write(to: directory)
        await store.refresh()
        XCTAssertEqual(AIChatViewModel.sendCount, sends)
        XCTAssertNotNil(store.storageError)
        XCTAssertEqual(store.task(id: task.id)?.state, .queued)
    }

    func testUnreadableDocumentIsNotReplaced() throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let original = Data("this is not a team document".utf8)
        try original.write(to: url)
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        XCTAssertNotNil(store.storageError)
        XCTAssertThrowsError(try makeBot(store, "Worker"))
        XCTAssertEqual(try Data(contentsOf: url), original)
    }

    func testQueuedCancellationSurvivesWriteFailureAndUnrelatedSuccessfulMutation() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        let task = try store.dispatch(botID: bot.id, prompt: "cancel before running")
        let sends = AIChatViewModel.sendCount
        let directory = url.deletingLastPathComponent()
        try FileManager.default.removeItem(at: directory)
        try Data("disk failure".utf8).write(to: directory)
        await store.cancel(taskID: task.id)
        XCTAssertNotNil(store.storageError)
        try FileManager.default.removeItem(at: directory)
        _ = try makeBot(store, "Unrelated mutation")
        XCTAssertNil(store.storageError)
        await store.refresh()
        XCTAssertEqual(store.task(id: task.id)?.state, .cancelled)
        XCTAssertEqual(AIChatViewModel.sendCount, sends)
    }

    func testTaskContentLocksFollowBothSessionsDynamically() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        let queued = try store.dispatch(botID: bot.id, prompt: "private", sourceSessionID: "parent-locked-test")
        XCTAssertFalse(store.isTaskContentLocked(queued))
        SessionLockStore.shared.locked.insert("parent-locked-test")
        XCTAssertTrue(store.isTaskContentLocked(queued))
        SessionLockStore.shared.locked.remove("parent-locked-test")
        await store.refresh()
        let running = try XCTUnwrap(store.task(id: queued.id))
        let sid = try XCTUnwrap(running.sessionID)
        SessionLockStore.shared.locked.insert(sid)
        XCTAssertTrue(store.isTaskContentLocked(running))
        SessionLockStore.shared.locked.remove(sid)
        XCTAssertFalse(store.isTaskContentLocked(running))
        await stop(store)
    }

    func testBotOriginCannotDispatchAndBusyProfileCannotChange() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        _ = try store.dispatch(botID: bot.id, prompt: "work")
        XCTAssertThrowsError(try store.updateBot(id: bot.id, name: "new", role: "new role", modelEntryID: nil))
        await store.refresh()
        let sid = try XCTUnwrap(store.bot(id: bot.id)?.sessionID)
        XCTAssertThrowsError(try store.dispatch(botID: bot.id, prompt: "recurse", sourceSessionID: sid))
        await stop(store)
    }

    func testTeamSessionCreationDoesNotChangeForegroundConversation() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        AIChatViewModel.activeSessionId = "user-foreground"
        _ = try store.dispatch(botID: bot.id, prompt: "work")
        await store.refresh()
        XCTAssertEqual(AIChatViewModel.activeSessionId, "user-foreground")
        await stop(store)
    }

    func testTeamCancelDoesNotStopStartedManualFollowUp() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let bot = try makeBot(store, "Worker")
        let task = try store.dispatch(botID: bot.id, prompt: "original")
        await store.refresh()
        let sid = try XCTUnwrap(store.bot(id: bot.id)?.sessionID)
        let vm = try XCTUnwrap(ViewModelCache.shared.entries[sid])
        vm.messages.append(ChatMessage(role: .assistant, content: "original result"))
        vm.messages.append(ChatMessage(role: .user, content: "manual follow-up now running"))
        await store.cancel(taskID: task.id)
        XCTAssertEqual(store.task(id: task.id)?.state, .cancelled)
        XCTAssertTrue(vm.isProcessing)
        XCTAssertFalse(vm.currentTask?.isCancelled ?? true)
        await vm.complete("manual answer")
        await settle()
        XCTAssertNil(store.task(id: task.id)?.result)
    }

    func testBusyConversationDoesNotBlockOtherBotsOrOverwriteDraft() async throws {
        let url = try location()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = MinisTeamStore(fileURL: url, automaticPolling: false)
        let first = try makeBot(store, "A")
        _ = try store.dispatch(botID: first.id, prompt: "initial")
        await store.refresh()
        let sid = try XCTUnwrap(store.bot(id: first.id)?.sessionID)
        let vm = try XCTUnwrap(ViewModelCache.shared.entries[sid])
        await vm.complete()
        await settle()
        vm.inputText = "User's unsent draft"
        let blocked = try store.dispatch(botID: first.id, prompt: "must wait")
        let other = try makeBot(store, "B")
        let ready = try store.dispatch(botID: other.id, prompt: "can run")
        await store.refresh()
        XCTAssertEqual(store.task(id: blocked.id)?.state, .queued)
        XCTAssertEqual(store.task(id: ready.id)?.state, .running)
        XCTAssertEqual(vm.inputText, "User's unsent draft")
        await stop(store)
    }
}
