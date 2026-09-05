import Foundation

struct MinisBotProfile: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    var role: String
    var modelEntryID: String?
    var sessionID: String?
    let createdAt: Date
    var updatedAt: Date
}

enum MinisTeamTaskState: String, Codable, CaseIterable {
    case queued, running, completed, failed, cancelled, interrupted

    var isTerminal: Bool {
        switch self {
        case .queued, .running: return false
        case .completed, .failed, .cancelled, .interrupted: return true
        }
    }
}

struct MinisTeamTask: Identifiable, Codable, Equatable {
    let id: String
    let botID: String
    let prompt: String
    let sourceSessionID: String?
    var sessionID: String?
    var state: MinisTeamTaskState
    var result: String?
    var error: String?
    let createdAt: Date
    var updatedAt: Date
    var startedAt: Date?
    var finishedAt: Date?
    var requestID: String? = nil
}

/// Scheduling operates on values so concurrency and crash recovery can be
/// checked without starting an iSH kernel or making a model request.
enum MinisTeamSchedulingPolicy {
    static let maximumConcurrentTasks = 3

    static func runnableTaskIDs(
        tasks: [MinisTeamTask],
        occupiedTaskIDs: Set<String> = [],
        occupiedBotIDs: Set<String> = []
    ) -> [String] {
        let running = tasks.filter { $0.state == .running }
        let occupied = occupiedTaskIDs.union(running.map(\.id))
        var slots = max(0, maximumConcurrentTasks - occupied.count)
        var busyBots = occupiedBotIDs.union(running.map(\.botID))
        var selected: [String] = []
        for task in tasks where task.state == .queued {
            guard slots > 0 else { break }
            guard !busyBots.contains(task.botID) else { continue }
            selected.append(task.id)
            busyBots.insert(task.botID)
            slots -= 1
        }
        return selected
    }

    /// A process restart cannot prove whether a queued write or a dispatched
    /// model call reached the other side. Never automatically send it again.
    static func recovering(_ tasks: [MinisTeamTask], at now: Date) -> [MinisTeamTask] {
        tasks.map { original in
            guard !original.state.isTerminal else { return original }
            var task = original
            task.state = .interrupted
            task.error = "应用已重新启动，无法确认任务是否完成。请查看 Bot 会话，确认后再手动派发新任务。"
            task.updatedAt = now
            task.finishedAt = now
            return task
        }
    }
}

enum MinisTeamError: LocalizedError {
    case invalidInput(String)
    case botNotFound
    case botBusy
    case recursiveDispatch
    case unavailable(String)

    var errorDescription: String? {
        switch self {
        case .invalidInput(let detail), .unavailable(let detail): return detail
        case .botNotFound: return "找不到这个 Bot。"
        case .botBusy: return "这个 Bot 还有排队或运行中的任务，请先取消或等待完成。"
        case .recursiveDispatch: return "Bot 任务不能继续派发 Bot 任务。请由主会话或团队页面派发。"
        }
    }
}
