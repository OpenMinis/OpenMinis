import AppIntents
import Foundation

/// Structured status result for a session, usable in Shortcuts conditionals and text blocks.
struct SessionStatus: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Session Status")
    static var defaultQuery = SessionStatusQuery()

    var id: String  // session ID

    @Property(title: "Session ID")
    var sessionId: String

    @Property(title: "Title")
    var title: String

    @Property(title: "Model")
    var modelName: String

    @Property(title: "Is Running")
    var isRunning: Bool

    @Property(title: "Last Message")
    var lastMessage: String

    @Property(title: "Last Tool")
    var lastTool: String

    @Property(title: "Updated At")
    var updatedAt: Date

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "\(title)",
            subtitle: "\(isRunning ? "Running" : "Idle") · \(modelName)"
        )
    }

    init(sessionId: String, title: String, modelName: String, isRunning: Bool,
         lastMessage: String, lastTool: String, updatedAt: Date) {
        self.id = sessionId
        self.sessionId = sessionId
        self.title = title
        self.modelName = modelName
        self.isRunning = isRunning
        self.lastMessage = lastMessage
        self.lastTool = lastTool
        self.updatedAt = updatedAt
    }
}

struct SessionStatusQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [SessionStatus] {
        []
    }
}

/// Queries the current status of a session — whether the agent is still running,
/// the latest message text, last tool summary, etc. Use after SendPrompt to poll.
struct GetSessionStatusIntent: AppIntent {
    static var title: LocalizedStringResource = "Get Session Status"
    static var description = IntentDescription("Gets the current status of a Minis session, including whether the agent is still running, the latest message, and last tool call.")
    static var openAppWhenRun = false

    @Parameter(title: "Session ID")
    var sessionID: String

    static var parameterSummary: some ParameterSummary {
        Summary("Get status for \(\.$sessionID)")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<SessionStatus> {
        let isRunning = SessionActivityTracker.shared.isActive(sessionID)

        let session = await ChatStore.shared.getSession(sessionID)
        let title = session?.title ?? "Untitled"
        let updatedAt = session?.updatedAt ?? Date()

        // Resolve model name from binding
        var modelName = session?.modelId ?? "unknown"
        let store = ProviderConfigStore.shared
        if let binding = store.binding(for: sessionID) {
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

        // Extract latest assistant text and tool summary from cached VM
        var lastMessage = ""
        var lastTool = ""
        if let vm = ViewModelCache.shared.get(for: sessionID) {
            if let lastAssistant = vm.messages.last(where: { $0.role == .assistant }) {
                // Last text content
                let textBlocks = lastAssistant.blocks
                    .filter { $0.kind == .text }
                    .map { $0.content }
                let text = textBlocks.joined(separator: "\n")
                lastMessage = text.isEmpty ? lastAssistant.content : text

                // Last tool call summary (e.g. "shell: ls -la" or "browser: navigate")
                if let lastToolBlock = lastAssistant.blocks.last(where: { $0.toolStatus != nil }) {
                    lastTool = lastToolBlock.toolSummary ?? lastToolBlock.toolDescription
                }
            }
        } else {
            // VM not cached — fall back to DB
            let sessions = await ChatStore.shared.listSessions()
            lastMessage = sessions.first(where: { $0.id == sessionID })?.lastMessage ?? ""
        }

        let status = SessionStatus(
            sessionId: sessionID,
            title: title,
            modelName: modelName,
            isRunning: isRunning,
            lastMessage: lastMessage,
            lastTool: lastTool,
            updatedAt: updatedAt
        )

        return .result(value: status)
    }
}
