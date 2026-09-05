// Platform boundary substitutes only. The scheduler, persistence, lifecycle
// decisions and crash recovery compiled by run.sh are the production sources.
import Foundation

enum ContextPolicy { enum CheckResult { case ok, needsCompact, exhausted } }
enum ChatMessageRole { case user, assistant }
enum AssistantBlockKind { case text }
struct AssistantBlock { let kind: AssistantBlockKind; let content: String }
final class ChatMessage {
    let id = UUID()
    let role: ChatMessageRole
    let content: String
    var blocks: [AssistantBlock] = []
    var error: String?
    var isQueued = false
    init(role: ChatMessageRole, content: String) { self.role = role; self.content = content }
}

actor CompletionGate {
    private var released = false
    private var waiting: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        if released { return }
        await withCheckedContinuation { waiting.append($0) }
    }
    func release() {
        released = true
        let pending = waiting
        waiting.removeAll()
        pending.forEach { $0.resume() }
    }
}

@MainActor
final class AIChatViewModel {
    static var sendCount = 0
    static var activeSessionId: String?
    var sessionId: String?
    var sessionSource: String?
    var isProcessing = false
    var isCompacting = false
    var promptQueue: [String] = []
    var inputText = ""
    var attachments: [String] = []
    var editingMessageIndex: Int?
    var showCompactBeforeSendPrompt = false
    var showContextExhaustedPrompt = false
    var messages: [ChatMessage] = []
    var currentTask: Task<Void, Never>?
    var userDidCancel = false
    var canResume = false
    private var gate = CompletionGate()

    func loadSession(activateSession: Bool = true) async {
        if activateSession { Self.activeSessionId = sessionId }
    }
    func ensureSessionReturningId(activateSession: Bool = true) async -> String {
        if let sessionId { return sessionId }
        let id = UUID().uuidString
        sessionId = id
        if activateSession { Self.activeSessionId = id }
        await ChatStore.shared.create(id)
        ViewModelCache.shared.entries[id] = self
        return id
    }
    func checkContextBeforeSend() -> ContextPolicy.CheckResult { .ok }
    func send() {
        guard !isProcessing else { return }
        Self.sendCount += 1
        userDidCancel = false
        messages.append(ChatMessage(role: .user, content: inputText))
        inputText = ""
        isProcessing = true
        if let sessionId { SessionActivityTracker.shared.active.insert(sessionId) }
        let completion = CompletionGate()
        gate = completion
        currentTask = Task { [weak self] in
            await completion.wait()
            guard let self else { return }
            self.isProcessing = false
            if let id = self.sessionId { SessionActivityTracker.shared.active.remove(id) }
        }
    }
    func cancel() {
        userDidCancel = true
        isProcessing = false
        currentTask?.cancel()
        if let sessionId { SessionActivityTracker.shared.active.remove(sessionId) }
        // Deliberately keep the gate closed: emulate a cancelled network call
        // whose completion arrives late, after the UI already says stopped.
    }
    func complete(_ text: String = "finished") async {
        messages.append(ChatMessage(role: .assistant, content: text))
        let task = currentTask
        await gate.release()
        await task?.value
    }
}

@MainActor
final class ViewModelCache {
    static let shared = ViewModelCache()
    var entries: [String: AIChatViewModel] = [:]
    func getOrCreate(for id: String) -> (vm: AIChatViewModel, isNew: Bool) {
        if let vm = entries[id] { return (vm, false) }
        let vm = AIChatViewModel()
        vm.sessionId = id
        entries[id] = vm
        return (vm, true)
    }
    func createDraft() -> AIChatViewModel { AIChatViewModel() }
}

@MainActor
final class SessionActivityTracker {
    static let shared = SessionActivityTracker()
    var active: Set<String> = []
    func isActive(_ id: String) -> Bool { active.contains(id) }
}

@MainActor
final class SessionLockStore {
    static let shared = SessionLockStore()
    var locked: Set<String> = []
    func isVisuallyLocked(_ id: String) -> Bool { locked.contains(id) }
}

struct SessionModelBinding {
    enum Source { case directEntry(modelEntryId: String) }
    let sessionId: String
    let primarySource: Source
}
@MainActor
final class ProviderConfigStore {
    static let shared = ProviderConfigStore()
    func entry(for id: String) -> String? { id == "valid-model" ? id : nil }
    func setBinding(_ binding: SessionModelBinding, for id: String) {}
}

actor ChatStore {
    static let shared = ChatStore()
    struct Session { var isRemote = false }
    private var sessions: [String: Session] = [:]
    func create(_ id: String) { sessions[id] = Session() }
    func getSession(_ id: String) -> Session? { sessions[id] }
    func updateSessionTitle(_ id: String, title: String, category: String? = nil) {}
}
