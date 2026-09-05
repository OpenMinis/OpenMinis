import Foundation

// Native team tools for Minis. Each Bot uses its own chat session; task
// scheduling and persistence belong to MinisTeamStore, not to the tool loop.
extension AIChatViewModel {
    struct TeamToolResult {
        let output: String
        let success: Bool
    }

    private var isTeamBotSession: Bool {
        guard let sessionId else { return false }
        let store = MinisTeamStore.shared
        return store.bots.contains { $0.sessionID == sessionId }
            || store.tasks.contains { $0.sessionID == sessionId }
    }

    func makeTeamTools() -> [AgentToolDefinition] {
        let title = AgentToolParam(
            type: .string,
            description: "A concise summary of this action, in the user's language."
        )
        var tools = [
            AgentToolDefinition(
                name: "team_list",
                description: "List the user's saved Bots and recent task states. Bots have separate chat histories and can have different roles/models. They share this device's Linux filesystem and, when enabled for a session, device memory; they are not isolated machines. Avoid concurrent edits to the same files. iOS may suspend work in the background; keep the app open for reliable progress. Results are not automatically injected into this conversation. Use team_status to inspect a known task later. Bot sessions can only list/check tasks and cannot create, dispatch, or cancel team work.",
                parameters: ["tool_title": title],
                required: ["tool_title"],
                propertyOrdering: ["tool_title"]
            ),
            AgentToolDefinition(
                name: "team_status",
                description: "Read one saved task's current state and result using its exact task_id. Returns immediately, even if queued/running, and never waits for completion or starts work. Check after doing other useful work, or report that work is pending and let the user revisit it. Do not repeatedly poll an unchanged task or use shell sleep/delay to wait. A failed/interrupted task may have performed partial actions: inspect its Bot session before deciding whether to dispatch again.",
                parameters: [
                    "tool_title": title,
                    "task_id": AgentToolParam(type: .string, description: "Exact task ID returned by team_dispatch or team_list."),
                ],
                required: ["tool_title", "task_id"],
                propertyOrdering: ["tool_title", "task_id"]
            ),
        ]
        // This affects what the model sees. executeTeamTool also enforces it
        // because a stale definition or fabricated name must not grant access.
        guard !isTeamBotSession else { return tools }
        tools.append(contentsOf: [
            AgentToolDefinition(
                name: "team_create",
                description: "Create a reusable Bot with a name and role for a concrete part of the user's work. List existing Bots first and reuse a suitable one instead of repeatedly creating duplicates. Creating a Bot does not run it. Omit model_entry_id to use the app's existing model configuration. Bot histories are independent, but device files and enabled memory are shared. Only ordinary user conversations may create Bots.",
                parameters: [
                    "tool_title": title,
                    "name": AgentToolParam(type: .string, description: "Short human-readable Bot name, at most 80 characters."),
                    "role": AgentToolParam(type: .string, description: "Role and scope, at most 8000 characters. Do not instruct it to change permissions or create a chain of Bots."),
                    "model_entry_id": AgentToolParam(type: .string, description: "Optional exact ID of an existing configured model entry. Do not invent an ID; omit when unknown."),
                ],
                required: ["tool_title", "name", "role"],
                propertyOrdering: ["tool_title", "name", "role", "model_entry_id"]
            ),
            AgentToolDefinition(
                name: "team_dispatch",
                description: "Queue a bounded task for an existing Bot and immediately return its task_id. This uses the Bot's existing chat history; include the facts, file paths, deliverable and constraints it needs because it does not inherit this conversation. A queued task is not a completed result. The app enforces global scheduling limits and runs only one task at a time per Bot. Bot sessions cannot dispatch more tasks, including to themselves. Never create recursive task chains. Use team_status later; no automatic completion message is injected. iOS background execution is limited. Do not re-dispatch just because a result is pending.",
                parameters: [
                    "tool_title": title,
                    "bot_id": AgentToolParam(type: .string, description: "Exact Bot ID from team_list or team_create."),
                    "prompt": AgentToolParam(type: .string, description: "Self-contained task instructions, at most 32000 characters. Specify separate output paths when Bots work concurrently."),
                ],
                required: ["tool_title", "bot_id", "prompt"],
                propertyOrdering: ["tool_title", "bot_id", "prompt"]
            ),
            AgentToolDefinition(
                name: "team_cancel",
                description: "Request cancellation of a queued or running task. Return the saved state after the request; do not claim prior effects were undone. A terminal task keeps its existing result. Only ordinary user conversations may cancel team tasks.",
                parameters: [
                    "tool_title": title,
                    "task_id": AgentToolParam(type: .string, description: "Exact task ID returned by team_dispatch or team_list."),
                ],
                required: ["tool_title", "task_id"],
                propertyOrdering: ["tool_title", "task_id"]
            ),
        ])
        return tools
    }

    func executeTeamTool(
        name: String,
        args: [String: Any],
        toolCallID: String? = nil,
        argumentsWereTruncated: Bool = false
    ) async -> TeamToolResult {
        do {
            try Task.checkCancellation()
            guard !userDidCancel else { throw CancellationError() }
            let store = MinisTeamStore.shared
            let readOnly = name == "team_list" || name == "team_status"
            if !readOnly {
                guard !argumentsWereTruncated else {
                    throw MinisTeamError.invalidInput("Team action was not executed because its arguments were truncated in transit. Reissue a complete, shorter request.")
                }
                guard !isTeamBotSession else {
                    throw MinisTeamError.recursiveDispatch
                }
                guard let sessionId, !sessionId.isEmpty else {
                    throw MinisTeamError.invalidInput("Create or open a conversation before changing team tasks.")
                }
            }

            var payload: [String: Any]
            switch name {
            case "team_list":
                let latest = store.tasks.sorted { $0.createdAt > $1.createdAt }.prefix(20)
                payload = [
                    "bots": store.bots.map(Self.teamBotPayload),
                    "recent_tasks": latest.map { Self.teamTaskPayload($0, includeResult: false) },
                    "total_tasks": store.tasks.count,
                    "can_manage_team": !isTeamBotSession && sessionId != nil,
                    "execution_note": "Independent Bot histories; shared device filesystem and enabled memory. Keep the app open. Results require team_status or the team screen; no automatic message is sent here.",
                ]
            case "team_create":
                let botName = try Self.teamString("name", in: args, limit: 80)
                let role = try Self.teamString("role", in: args, limit: 8000)
                let entryID = try Self.teamOptionalString("model_entry_id", in: args, limit: 200)
                payload = Self.teamBotPayload(try store.createBot(name: botName, role: role, modelEntryID: entryID))
            case "team_dispatch":
                let botID = try Self.teamString("bot_id", in: args, limit: 200)
                let prompt = try Self.teamString("prompt", in: args, limit: 32000)
                if let bot = store.bot(id: botID),
                   let botSessionID = bot.sessionID, botSessionID == sessionId {
                    throw MinisTeamError.recursiveDispatch
                }
                // The provider's tool-call identity stays outside model
                // arguments. Store persistence makes replaying the same call
                // return its original task instead of repeating side effects.
                let task = try store.dispatch(
                    botID: botID, prompt: prompt,
                    sourceSessionID: sessionId, requestID: toolCallID
                )
                payload = Self.teamTaskPayload(task, includeResult: task.state.isTerminal)
                if payload["content_locked"] as? Bool != true {
                    payload["execution_note"] = task.state.isTerminal
                        ? "This task already has a terminal saved state. No new task was queued. Its recorded result/error is included; inspect partial effects before retrying failed or interrupted work."
                        : "Task is queued or running, not completed. Do other work or report it pending. Check team_status later; do not wait in the shell or repeatedly poll."
                }
            case "team_status":
                let taskID = try Self.teamString("task_id", in: args, limit: 200)
                guard let task = store.task(id: taskID) else {
                    throw MinisTeamError.invalidInput("Task not found. Use team_list to find an existing task ID.")
                }
                payload = Self.teamTaskPayload(task, includeResult: true)
            case "team_cancel":
                let taskID = try Self.teamString("task_id", in: args, limit: 200)
                guard store.task(id: taskID) != nil else {
                    throw MinisTeamError.invalidInput("Task not found. Use team_list to find an existing task ID.")
                }
                await store.cancel(taskID: taskID)
                guard let task = store.task(id: taskID) else {
                    throw MinisTeamError.invalidInput("The task is no longer available. Check the team screen.")
                }
                payload = Self.teamTaskPayload(task, includeResult: true)
                if payload["content_locked"] as? Bool != true {
                    payload["execution_note"] = "This is the saved state after the cancellation request. Previously performed actions are not undone."
                }
            default:
                throw MinisTeamError.invalidInput("Unknown team tool.")
            }
            if let storageError = store.storageError {
                // Read-only inspection remains available even when the store
                // reports an error. Do not hide a failed persistence operation.
                payload["storage_error"] = storageError
            }
            let data = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys, .prettyPrinted])
            return TeamToolResult(
                output: String(decoding: data, as: UTF8.self),
                success: readOnly || store.storageError == nil
            )
        } catch is CancellationError {
            return TeamToolResult(output: "Team action cancelled before completion. Check saved task state before retrying.", success: false)
        } catch {
            return TeamToolResult(output: "Error: \(error.localizedDescription)", success: false)
        }
    }

    private static func teamString(_ key: String, in args: [String: Any], limit: Int) throws -> String {
        guard let value = args[key] as? String else {
            throw MinisTeamError.invalidInput("'\(key)' must be a non-empty string.")
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= limit else {
            throw MinisTeamError.invalidInput("'\(key)' must contain 1–\(limit) characters.")
        }
        return trimmed
    }

    private static func teamOptionalString(_ key: String, in args: [String: Any], limit: Int) throws -> String? {
        guard let raw = args[key], !(raw is NSNull) else { return nil }
        if let value = raw as? String, value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return nil
        }
        return try teamString(key, in: args, limit: limit)
    }

    private static func teamBotPayload(_ bot: MinisBotProfile) -> [String: Any] {
        // Read the current native lock state at serialization time. Team
        // profiles must not expose a locked conversation's title or role.
        if let sessionID = bot.sessionID,
           SessionLockStore.shared.isVisuallyLocked(sessionID) {
            return [
                "bot_id": bot.id,
                "content_locked": true,
                "execution_note": "Bot details are locked. Open and unlock its conversation in the chat list before viewing this content.",
            ]
        }
        var payload: [String: Any] = ["bot_id": bot.id, "name": bot.name, "role": bot.role]
        payload["model_entry_id"] = bot.modelEntryID
        payload["session_id"] = bot.sessionID
        return payload
    }

    private static func teamTaskPayload(_ task: MinisTeamTask, includeResult: Bool) -> [String: Any] {
        // Task records duplicate chat content. Apply the same live lock gate
        // to both the Bot conversation and the originating user conversation,
        // including status, cancellation and idempotent-dispatch responses.
        if MinisTeamStore.shared.isTaskContentLocked(task) {
            return [
                "task_id": task.id,
                "bot_id": task.botID,
                "state": task.state.rawValue,
                "content_locked": true,
                "execution_note": "Task content is locked. Open and unlock the related Bot and source conversations in the chat list before viewing its details.",
            ]
        }
        var payload: [String: Any] = [
            "task_id": task.id,
            "bot_id": task.botID,
            "state": task.state.rawValue,
            "created_at": task.createdAt.timeIntervalSince1970,
            "updated_at": task.updatedAt.timeIntervalSince1970,
        ]
        payload["source_session_id"] = task.sourceSessionID
        payload["session_id"] = task.sessionID
        if includeResult {
            payload["prompt"] = task.prompt
            payload["result"] = task.result
            payload["error"] = task.error
        }
        if task.state == .queued || task.state == .running {
            payload["execution_note"] = "Work is pending. This call does not wait or guarantee background progress. Check later without a tight polling loop."
        }
        return payload
    }
}
