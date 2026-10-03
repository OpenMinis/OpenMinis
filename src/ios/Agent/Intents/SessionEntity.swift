import AppIntents
import Foundation

/// Wraps a ChatSession as an AppEntity so Shortcuts can reference sessions by name.
struct SessionEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "New Session")
    static var defaultQuery = SessionEntityQuery()

    var id: String
    var displayName: String
    var modelId: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(displayName)")
    }

    init(id: String, displayName: String, modelId: String) {
        self.id = id
        self.displayName = displayName
        self.modelId = modelId
    }

    init(from session: ChatSession) {
        self.id = session.id
        self.displayName = session.title ?? "Untitled"
        self.modelId = session.modelId
    }
}

struct SessionEntityQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [SessionEntity] {
        var results: [SessionEntity] = []
        for id in identifiers {
            if let session = await ChatStore.shared.getSession(id) {
                results.append(SessionEntity(from: session))
            }
        }
        return results
    }

    func suggestedEntities() async throws -> [SessionEntity] {
        let sessions = await ChatStore.shared.listSessions().filter { !$0.isChild }
        return sessions.prefix(100).map { SessionEntity(from: $0) }
    }
}
