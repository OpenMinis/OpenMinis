import AppIntents
import Foundation

/// Represents a user message in a session, selectable in Shortcuts.
/// Gated behind iOS 17+ because UserMessageEntityQuery uses
/// @IntentParameterDependency which crashes Swift metadata resolution on iOS 16.
@available(iOS 17.0, *)
struct UserMessageEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "User Message")
    static var defaultQuery = UserMessageEntityQuery()

    var id: String          // "sessionId:index"
    var sessionId: String
    var preview: String     // Truncated text content
    var index: Int          // 1-based index among user messages
    var sessionTitle: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "#\(index): \(preview)",
            subtitle: "\(sessionTitle)"
        )
    }

    /// Build entities from a session's user messages (reads from DB).
    static func loadFromDB(sessionId: String, sessionTitle: String) async -> [UserMessageEntity] {
        let messages = await ChatStore.shared.loadMessages(sessionId: sessionId)
        let userMessages = messages.filter { $0.role == .user && !$0.isToolResultOnly }
        return userMessages.enumerated().map { idx, msg in
            let texts = msg.parts.compactMap { part -> String? in
                if case .text(let t) = part { return t }
                return nil
            }
            let joined = texts.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
            let preview: String
            if joined.count > 80 {
                preview = String(joined.prefix(80)) + "…"
            } else {
                preview = joined.isEmpty ? "(attachment)" : joined
            }
            return UserMessageEntity(
                id: "\(sessionId):\(idx)",
                sessionId: sessionId,
                preview: preview,
                index: idx + 1,
                sessionTitle: sessionTitle
            )
        }
    }
}

// MARK: - Query with session dependency

@available(iOS 17.0, *)
struct UserMessageEntityQuery: EntityQuery {
    @IntentParameterDependency<RetryRunIntent>(\.$session)
    var retryIntent

    func entities(for identifiers: [String]) async throws -> [UserMessageEntity] {
        var bySession: [String: [Int]] = [:]
        for identifier in identifiers {
            let parts = identifier.split(separator: ":", maxSplits: 1)
            guard parts.count == 2, let idx = Int(parts[1]) else { continue }
            bySession[String(parts[0]), default: []].append(idx)
        }

        var results: [UserMessageEntity] = []
        for (sessionId, indices) in bySession {
            let session = await ChatStore.shared.getSession(sessionId)
            let title = session?.title ?? "Untitled"
            let all = await UserMessageEntity.loadFromDB(sessionId: sessionId, sessionTitle: title)
            results.append(contentsOf: all.filter { indices.contains($0.index - 1) })
        }
        return results
    }

    func suggestedEntities() async throws -> [UserMessageEntity] {
        // Show only the selected session's messages when available
        if let sessionEntity = retryIntent?.session {
            return await UserMessageEntity.loadFromDB(
                sessionId: sessionEntity.id,
                sessionTitle: sessionEntity.displayName
            )
        }

        // Fallback (no session selected): recent sessions' messages.
        // [T-child-session-leak] Hidden agent child sessions stay hidden here.
        let sessions = await ChatStore.shared.listSessions().filter { !$0.isChild }
        var entities: [UserMessageEntity] = []
        for session in sessions.prefix(10) {
            let msgs = await UserMessageEntity.loadFromDB(
                sessionId: session.id,
                sessionTitle: session.title ?? "Untitled"
            )
            entities.append(contentsOf: msgs)
        }
        return entities
    }
}
