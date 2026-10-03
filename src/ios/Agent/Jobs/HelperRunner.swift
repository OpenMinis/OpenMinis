import Foundation
import Combine

private let logger = AppLogger(category: "HelperRunner")

// [T-p1-delegate-task] The `delegate_task` tool: the model autonomously hands
// a self-contained task to a helper (sub-agent) that runs its own agent loop
// in a HIDDEN child session, concurrently with the parent, and returns the
// helper's final answer as this tool call's result.
//
// Design v4 §4/§6. P1 = wait mode only: the parent turn blocks on the child
// loop, so the "completion hook" is simply the tool_result — no injection
// into the parent, no queue race. `wait: false` (background mode via
// AgentJobRegistry `then: .followUpParent`) is P2.
//
// Why a pure Tool Call and not a CLI (design v4 §4.5): the run needs the
// parent VM reference to suspend reactively, shares in-process state with
// the capsule / sheet UI, and Stop must reach the child VM's `cancel()`
// directly rather than hunting a pid inside iSH.

/// Set on a child (helper) view model before its first turn. Its presence is
/// what every helper-specific branch in AIChatViewModel keys off.
struct HelperConfig {
    let parentSessionId: String
    let parentToolUseId: String
    let jobId: String
    /// Hard cap on the child's agent-loop turns (design §4.3: 25).
    let maxTurns: Int
    let title: String
    let modelOrigin: HelperModelOrigin
    /// [T-sub-agents-v1] Which sub agent definition this run uses. Snapshotted
    /// at start: deleting or renaming the definition mid-run must not change
    /// what an in-flight job reports.
    var subAgentId: String = SubAgentDefinition.builtInId
    var subAgentName: String = ""
    /// Standing instructions from that definition, appended to the child brief.
    var subAgentInstructions: String = ""
}

extension AIChatViewModel {

    enum HelperWrapUpReason { case turns, budget }

    /// Extra seconds a helper gets after its budget expires to answer the
    /// wrap-up prompt before it is cancelled outright.
    static let helperWrapUpGraceSeconds: TimeInterval = 90

    /// [T-subagent-thinking-inherit] Give the child session a thinking level.
    ///
    /// Every request reads its level from
    /// `ProviderConfigStore.inferenceConfig(for: sessionId)?.thinkingLevel ?? .off`
    /// (see AIChatViewModel+Fallback). A session gets that config written when
    /// the user picks a model or group in `SessionModelPicker`, or when
    /// `loadSession` applies a group default — both UI paths. A CHILD session is
    /// created straight from `ChatStore.createSession` here and touched by
    /// neither, so it had no config at all and every sub agent request went out
    /// at `.off`, however the group was configured. That is the reported
    /// symptom: the group says "high", the API log shows no reasoning effort.
    ///
    /// Two sources, matching where the child's MODEL came from:
    ///
    ///   * A definition that pins a Model Group → that group's
    ///     `defaultThinkingLevel`. The user configured the group the sub agent
    ///     runs on, so its default is the one that applies.
    ///   * Auto / inherited → the PARENT session's level, so a delegation made
    ///     from a high-effort conversation reasons like the conversation it came
    ///     from. Falls back to the resolved group's default when the parent has
    ///     no config of its own.
    ///
    /// Clamped to the resolved entry's `effectiveMaxThinkingLevel`, exactly as
    /// the send path clamps it — a group default of `xhigh` inherited onto a
    /// member that tops out at `high` would 400 otherwise
    /// ([T-fallback-thinking-preclamp] covers the same trap on fallback).
    ///
    /// Writes nothing when the resolved level is `.off`, so a run that should
    /// not think stays byte-identical to before this change.
    /// Returns the level it applied, or nil when reasoning stays off — so the
    /// delegation payload can report what actually took effect instead of
    /// re-deriving the precedence chain and risking the two disagreeing.
    @discardableResult
    static func seedChildThinkingLevel(childId: String,
                                       parentSessionId: String?,
                                       resolution: HelperModelResolution,
                                       subAgent: SubAgentDefinition? = nil) -> ThinkingLevel? {
        let store = ProviderConfigStore.shared

        /// The group the child actually runs on, when it runs on one.
        let resolvedGroup: ModelGroup? = {
            if case .group(let gid, _) = resolution.source { return store.group(for: gid) }
            return nil
        }()

        var level: ThinkingLevel = {
            // [T-subagent-thinking-override] The definition's own setting wins
            // over everything below it. It is the most specific statement of
            // intent available — the user chose it for THIS sub agent, knowing
            // which group it runs on — so it overrides the group default the
            // same way a session-level pick does, and it beats inheriting the
            // parent conversation's level too. nil means "not set", which is
            // every definition until the user picks one, and leaves the
            // existing precedence untouched.
            if let override = subAgent?.thinkingLevelOverride { return override }
            // Pinned: the group's own default is the configured intent.
            if resolution.origin == .pinned, let g = resolvedGroup, let lvl = g.defaultThinkingLevel {
                return lvl
            }
            // Otherwise follow the parent conversation, then the group default.
            if let parentSessionId, let cfg = store.inferenceConfig(for: parentSessionId) {
                return cfg.thinkingLevel
            }
            return resolvedGroup?.defaultThinkingLevel ?? .off
        }()

        level = min(level, resolution.entry.effectiveMaxThinkingLevel)
        guard level.isEnabled else { return nil }

        var cfg = store.inferenceConfig(for: childId) ?? SessionInferenceConfig()
        cfg.thinkingLevel = level
        store.setInferenceConfig(cfg, for: childId)
        logger.info("[subagent_task] child \(childId.prefix(8)) thinking=\(level.rawValue) (origin=\(resolution.origin.rawValue) override=\(subAgent?.thinkingLevelOverride?.rawValue ?? "none"))")
        return level
    }

    /// [T-agent-wrapup-turn] The "time's up, write it up" message.
    static func helperWrapUpPrompt(reason: HelperWrapUpReason) -> String {
        let why = reason == .turns
            ? "You have used all of your tool rounds."
            : "Your time budget is up."
        return """
        [\(why) Tools are no longer available for this turn.]
        Write your final answer NOW from what you already have. It must contain the complete deliverable itself — the full report, document, list or answer the task asked for — not a description of what you did, not a pointer to a file, not a request for more time. Mark anything you could not verify as unverified. This message is returned to the parent agent verbatim.
        """
    }

    /// What the parent reads when the child really ended without any text
    /// (cancelled mid-tool, crashed, or cut off after the grace period).
    static func emptyResultNote(status: String) -> String {
        "(the agent ended with status \(status) before writing a final answer; there is no deliverable — re-delegate with a narrower task or a larger budget rather than reading its transcript)"
    }

    /// [T-subagent-no-deliverable-status] The wire status for "the loop ended
    /// cleanly but produced nothing".
    ///
    /// A sub agent that exhausts its turns, or is cut off after the wrap-up
    /// grace period, still leaves the loop with `completed` — nothing threw and
    /// nothing was cancelled. Reporting that as `completed` made the parent
    /// bubble render a green check, "Done", the model name and the elapsed
    /// time, while the result body right below it said there was no
    /// deliverable. The two halves of the same card contradicted each other,
    /// and the reading a user takes from a green check is that the work landed.
    ///
    /// This is deliberately a distinct status rather than reusing `failed`:
    /// nothing malfunctioned. The run happened, cost real tokens and time, and
    /// its transcript is intact — it just did not produce the thing it was
    /// asked for. `failed` would misdescribe that (and reads as "retry the same
    /// thing"), while `completed` overclaims. The actionable advice differs
    /// too: the fix here is a narrower task or a bigger budget, which is
    /// exactly what `emptyResultNote` already tells the model.
    nonisolated static let noDeliverableStatus = "no_deliverable"

    /// [T-subagent-error-surface] A few words naming WHY a run failed, from the
    /// child's error text.
    ///
    /// The card has room for a label, not a stack trace, and "Failed" alone
    /// gives the user nothing to act on — a rate limit, a dead network and a
    /// context overflow need three different responses. Attribution is
    /// best-effort: anything unrecognised keeps the generic label rather than
    /// inventing a category, and the full text is carried separately for the
    /// detail sheet.
    nonisolated static func errorKind(_ raw: String?) -> String? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        let s = raw.lowercased()
        // Ordered most-specific first: an overload response also contains
        // "429"-adjacent wording on some providers, and a context error often
        // mentions "token" which the quota branch would otherwise claim.
        if s.contains("context") && (s.contains("length") || s.contains("window") || s.contains("too long")) {
            return AppLocalized("Context limit")
        }
        if s.contains("rate limit") || s.contains("429") || s.contains("too many requests") {
            return AppLocalized("Rate limited")
        }
        if s.contains("quota") || s.contains("insufficient") || s.contains("credit") || s.contains("billing") {
            return AppLocalized("Quota exhausted")
        }
        if s.contains("overload") || s.contains("529") || s.contains("503") || s.contains("unavailable") {
            return AppLocalized("Provider overloaded")
        }
        if s.contains("timed out") || s.contains("timeout") { return AppLocalized("Timed out") }
        if s.contains("unauthor") || s.contains("401") || s.contains("403")
            || s.contains("api key") || s.contains("credential") {
            return AppLocalized("Auth failed")
        }
        if s.contains("offline") || s.contains("network") || s.contains("connection")
            || s.contains("host") || s.contains("internet") {
            return AppLocalized("Network error")
        }
        if s.contains("cancel") { return AppLocalized("Cancelled") }
        return nil
    }

    /// Whether a finished run actually produced a deliverable.
    ///
    /// The single place this judgement is made, so the wait-mode payload, the
    /// background completion payload and the registry state can never disagree
    /// about it. Only a run that ended cleanly is reclassified: a `cancelled`
    /// / `timeout` / `failed` run already reports a non-success status, and
    /// relabelling those would lose the reason it ended.
    /// `nonisolated` because it is pure string logic over its two arguments and
    /// is called from both the main-actor runner and `AgentCallback`'s
    /// nonisolated `syntheticBlock()`.
    nonisolated static func resolvedStatus(_ status: String, result: String) -> String {
        guard status == "completed" else { return status }
        return result.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? noDeliverableStatus
            : status
    }

    /// Wait until the child stops processing, or `seconds` pass. True = idle.
    static func awaitChildIdle(_ child: AIChatViewModel, seconds: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while child.isProcessing, Date() < deadline {
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        return !child.isProcessing
    }

    /// Seconds a running tool gets to return on its own after the budget
    /// expires before it is interrupted so the wrap-up turn can start.
    static let helperWrapUpToolPatience: TimeInterval = 20

    /// [T-agent-wrapup-turn] Budget over: ask the child for its deliverable
    /// and wait for it. The wrap-up prompt is injected at the top of the
    /// child's NEXT loop turn, so a child blocked inside one long tool call
    /// (a 150 s shell loop, a slow page) would never get there — after a
    /// short patience the running tool is stopped (same path as the user's
    /// stop-this-command button: the loop continues with a cancelled tool
    /// result, the whole run is not cancelled). True = the child finished
    /// by itself within the grace; false = the caller must cancel it.
    static func awaitHelperWrapUp(_ child: AIChatViewModel, childId: String) async -> Bool {
        child.helperWrapUpRequested = true
        if await awaitChildIdle(child, seconds: helperWrapUpToolPatience) { return true }
        if child.isProcessing {
            logger.warning("[delegate_task] child \(childId.prefix(8)) still inside a tool after \(Int(helperWrapUpToolPatience))s — stopping the tool so the wrap-up turn can start")
            child.stopCurrentCommand()
        }
        return await awaitChildIdle(child, seconds: helperWrapUpGraceSeconds - helperWrapUpToolPatience)
    }

    /// True for a helper (child) vm.
    var isHelper: Bool { helperConfig != nil }

    /// [T-p2-shared-workspace] The session whose `/var/minis` bucket this vm's
    /// shell and file tools operate in. A helper works in its PARENT's
    /// workspace — it shares the
    /// parent's files — so what it writes is what the parent (and the user)
    /// can read. Its transcript, media rows and job identity stay its own.
    var fsSessionId: String? { helperConfig?.parentSessionId ?? sessionId }

    // MARK: - Limits (design §4.3, all enforced in code, not prompt)

    /**
     * [T-subagent-turn-parity] Tool-round ceiling for a child, matched to the
     * main chat's own cap (`AIChatViewModel.maxAgentTurns`).
     *
     * Was 25. In the field that proved too tight for the work children are
     * actually given: testers hit it repeatedly, and 13 of 14 recorded child
     * sessions ended at exactly the cap rather than because the task was done.
     * A child asked to survey something and then write the deliverable spent
     * its rounds on the survey and had none left to produce the artifact.
     *
     * There is no principled reason for a delegated task to get 8x fewer
     * rounds than the same work done inline in the parent conversation, so the
     * two ceilings are now the same number. The minutes budget is what actually
     * bounds a runaway child and is unchanged; this cap is the backstop.
     */
    static let helperMaxTurns = 200

    /**
     * [T-subagent-turn-countdown] How many rounds before the cliff the child is
     * warned that its tool budget is nearly gone.
     *
     * The cap itself is deliberate (design §4.3) and unchanged. What was
     * missing is any FEEDBACK on the way to it: the child is told "you have at
     * most N tool rounds" once, in the system prompt, then has to count its own
     * turns across a long run — which models reliably fail to do. The reported
     * symptom is the consequence: a child spends its budget investigating and
     * first learns it is out of rounds on the wrap-up turn, when tools are
     * already gone and it can no longer write the file it was asked to produce.
     *
     * 3 leaves room to act: one round to close out the current thread, one to
     * write the artifact, one spare — still before the tools-withdrawn turn.
     */
    static let turnWarningLead = 3

    /**
     * [T-subagent-turn-countdown] The nudge injected at `turnWarningLead`
     * rounds remaining.
     *
     * Deliberately NOT the wrap-up text: tools still work here, and the point
     * is to spend the remainder finishing rather than investigating. States the
     * number because that is exactly the fact the child cannot derive itself.
     */
    static func turnBudgetWarning(remaining: Int) -> String {
        "[Budget check: \(remaining) tool \(remaining == 1 ? "round" : "rounds") left before tools are withdrawn.] "
            + "Stop investigating now and spend what is left on finishing: if the task asked you to write a file or produce an artifact, do it in the next round, then write your final answer. "
            + "Remember the final message must contain the complete deliverable itself, not a pointer to it."
    }
    static let helperMaxPerAssistantTurn = 3
    /// [T-sub-agents-queue] Marker put into a queued delegation's args so the
    /// re-entry knows the per-turn allowance has already been served.
    static let queuedReentryKey = "__from_queue"

    /// [T-subagent-steer-continues-loop] Restarts an idle child's loop so the
    /// pending steer is delivered. Deliberately says nothing itself: the loop
    /// prepends the actual correction at the top of the turn it starts, and a
    /// second instruction here would compete with it.
    static let steerNudgePrompt =
        "The delegating agent has sent you a course correction. Read it and continue."
    static let helperDefaultMinutes = 10
    static let helperMaxMinutes = 60

    // MARK: - Tool entry point

    /// Execute one `delegate_task` call. Returns the tool_result text and a
    /// success flag; the caller writes them into the parent block.
    func executeDelegateTask(args: [String: Any],
                             toolUseId: String,
                             msgIdx: Int,
                             blockIdx: Int) async -> (output: String, success: Bool) {
        // ── Argument parsing ─────────────────────────────────────────────
        let title = (args["tool_title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let task = (args["task"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let context = (args["context"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // [T-sub-agents-v1] Which named sub agent runs this task. Omitted = the
        // built-in general one. The model no longer chooses a model tier: the
        // sub agent's own definition decides (pinned group, or inherit).
        let requestedAgentName = (args["agent"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let requestedMinutes = (args["max_minutes"] as? Int)
            ?? (args["max_minutes"] as? Double).map(Int.init)
            ?? Self.helperDefaultMinutes
        let minutes = max(1, min(Self.helperMaxMinutes, requestedMinutes))
        // [T-p2-background-default] Background is the default;
        // wait=true is the opt-in for "the next
        // step needs this result before anything else".
        let wait = (args["wait"] as? Bool) ?? false
        // [T-p2-progress-report] Mid-run reporting level (background mode only).
        let progressLevel: String = {
            let raw = ((args["progress_report"] as? String) ?? "none").lowercased()
            return ["none", "frequent", "moderate"].contains(raw) ? raw : "none"
        }()

        func reject(_ reason: String, _ detail: String) -> (String, Bool) {
            let json = Self.jsonString(["ok": false, "status": "rejected", "reason": reason, "detail": detail])
            logger.warning("[delegate_task] REJECTED \(reason): \(detail)")
            writeParentBlock(msgIdx: msgIdx, blockIdx: blockIdx, content: json)
            return (json, false)
        }

        guard !task.isEmpty else { return reject("empty_task", "`task` is required and must describe the whole job.") }
        guard !isHelper else {
            return reject("depth_limit", "Helpers cannot delegate further (max delegation depth is 1). Do the work yourself.")
        }
        guard remoteDeviceId == nil, let parentSid = sessionId else {
            return reject("no_parent_session", "This session cannot spawn helpers.")
        }
        // [T-sub-agents-queue] Position of this call among the delegations of
        // THIS assistant message. The dispatcher runs them concurrently, so
        // this is the only stable place to count. Past the per-turn allowance
        // the call is QUEUED, not refused: the queue exists precisely so extra
        // delegations start as slots free, and refusing them here made a model
        // that fanned out five tasks in one turn see two of them come back
        // "rejected" while three ran — the case the queue was built for.
        // A delegation coming back from the queue has already served the
        // per-turn allowance — it waited for a slot, which is what the
        // allowance exists to enforce. Re-applying the positional test would
        // send it straight back to the queue it just left.
        let fromQueue = (args[Self.queuedReentryKey] as? Bool) == true
        var overTurnAllowance = false
        if !fromQueue, msgIdx < messages.count {
            let delegateBlocks = messages[msgIdx].blocks.enumerated().filter {
                if case .delegateTool = $0.element.kind { return true }
                return false
            }
            if let myPos = delegateBlocks.firstIndex(where: { $0.offset == blockIdx }) {
                overTurnAllowance = myPos >= Self.helperMaxPerAssistantTurn
            }
        }
        let registry = AgentJobRegistry.shared
        // [T-sub-agents-queue] All slots busy: queue instead of rejecting, and
        // return at once. Suspending here would hold the whole assistant turn
        // open (the dispatcher waits for every tool call in a turn), so the
        // sub agents that DID start could not report back either. The queued
        // one starts by itself when a slot frees and reports through the same
        // callback, so the model does not have to remember to re-delegate.
        if overTurnAllowance || !registry.canStartChildJob {
            let queued = registry.enqueueDelegation(
                .init(parentSessionId: parentSid, args: args, toolUseId: toolUseId))
            guard queued else {
                return reject("helper_limit",
                              "\(AgentJobRegistry.maxConcurrentChildJobs) sub agents are already running and the queue is full. Wait for some to finish, then delegate this task again — it was NOT queued.")
            }
            let json = Self.jsonString([
                "ok": true, "status": "queued",
                "queued_behind": registry.runningChildJobCount + registry.queuedCount(parent: parentSid) - 1,
                "detail": overTurnAllowance
                    ? "More than \(Self.helperMaxPerAssistantTurn) delegations in one turn. This task is QUEUED and will start automatically as slots free; its result arrives as a new message like any other. Do not re-delegate it."
                    : "All \(AgentJobRegistry.maxConcurrentChildJobs) slots are busy. This task is QUEUED and will start automatically when one frees; its result arrives as a new message like any other. Do not re-delegate it.",
            ])
            writeParentBlock(msgIdx: msgIdx, blockIdx: blockIdx, content: json)
            logger.info("[subagent_task] QUEUED tool=\(toolUseId.prefix(12)) behind \(registry.runningChildJobCount) running")
            return (json, true)
        }
        // Resolved before the model, because a pinned Model Group on the
        // definition overrides the requested tier.
        let roster = SubAgentStore.shared.subAgents
        guard let subAgent = SubAgentRoster.resolve(name: requestedAgentName, in: roster) else {
            let known = roster.map(\.name).joined(separator: ", ")
            return reject("unknown_agent", "No sub agent named \"\(requestedAgentName ?? "")\". Available: \(known). Omit `agent` to use the general one.")
        }
        // [T-sub-agents-v1] Only consulted when the definition pins no group.
        let modelChoice = SubAgentModelChoice.parse(args["model_choice"] as? String)
        guard let resolution = SubAgentModelResolver.resolve(subAgent: subAgent, parent: self,
                                                            choice: modelChoice) else {
            return reject("no_model", "No model is configured for a helper to run on.")
        }

        // ── Child session ────────────────────────────────────────────────
        let displayTitle = title.isEmpty ? String(task.prefix(40)) : title
        let session = await ChatStore.shared.createSession(
            modelId: resolution.entry.model.id,
            title: AgentJobRegistry.childSessionTitle(displayTitle, subAgentName: subAgent.isBuiltIn ? nil : subAgent.name),
            source: sessionSource,
            parentSessionId: parentSid,
            parentToolUseId: toolUseId
        )
        let childId = session.id
        ProviderConfigStore.shared.setBinding(
            SessionModelBinding(sessionId: childId, primarySource: resolution.source), for: childId)
        let appliedThinking = Self.seedChildThinkingLevel(
            childId: childId, parentSessionId: parentSid,
            resolution: resolution, subAgent: subAgent)

        // wait=true: the result is this tool call's return value, nothing to
        // inject. wait=false: the registry's `then` posts the structured
        // result into the parent as a new turn through component A.
        let job = registry.register(title: displayTitle, origin: .tool, trigger: .immediate,
                                    target: .childOfCurrent(parentSessionId: parentSid, parentToolUseId: toolUseId),
                                    prompt: task, then: wait ? AgentJobThen.none : .followUpParent(template: nil))
        // A failed start must not ALSO report back through `then`.
        // (set below only after the child actually started)
        job.modelOrigin = resolution.origin.rawValue
        job.subAgentName = subAgent.name
        job.progressLevel = progressLevel
        // [T-agent-model-identity] tier requested/used + the resolved entry;
        // the effective half fills in from the child's loop as it runs.
        job.modelIdentity = HelperModelIdentity.make(resolution: resolution)

        // ── Child VM ─────────────────────────────────────────────────────
        // [T-vmcache-pools] Children live in their own LRU pool so a fan-out
        // cannot evict the conversations the user is actually working in.
        let (child, _) = ViewModelCache.shared.getOrCreate(for: childId, kind: .child)
        await child.loadSession()
        child.sessionSource = sessionSource
        child.helperConfig = HelperConfig(parentSessionId: parentSid, parentToolUseId: toolUseId,
                                          jobId: job.id, maxTurns: Self.helperMaxTurns,
                                          title: displayTitle, modelOrigin: resolution.origin,
                                          subAgentId: subAgent.id,
                                          subAgentName: subAgent.name,
                                          subAgentInstructions: subAgent.instructions)
        // No long-term memory for helpers (design §4.3 drops memory_write;
        // flipping the session toggle drops both tools AND the memory
        // injection so prompt and tool list stay consistent — see report).
        child.memoryEnabled = false
        child.suppressGeneralCompletionNotification = true
        // Shared browser: same tab set as the parent (T-p2-shared-workspace).
        child.browserTabPool = browserTabPool

        if msgIdx < messages.count, blockIdx < messages[msgIdx].blocks.count {
            messages[msgIdx].blocks[blockIdx].helperChildSessionId = childId
            messages[msgIdx].blocks[blockIdx].helperModel = job.modelIdentity
            if messages[msgIdx].blocks[blockIdx].toolSummary?.isEmpty ?? true {
                messages[msgIdx].blocks[blockIdx].toolSummary = displayTitle
            }
        }

        var prompt = task
        if !context.isEmpty { prompt += "\n\n--- Context from the delegating agent ---\n" + context }

        let startedAt = Date()
        logger.info("[delegate_task] START job=\(job.id.prefix(8)) child=\(childId.prefix(8)) parent=\(parentSid.prefix(8)) model_origin=\(resolution.origin.rawValue) model=\(resolution.modelLabel) budget=\(minutes)m")
        writeParentBlock(msgIdx: msgIdx, blockIdx: blockIdx,
                         content: Self.progressLine(title: displayTitle, tool: "", status: AppLocalized("starting"), elapsed: 0))

        let outcome = child.submitProgrammaticPrompt(prompt, origin: .job(jobId: job.id), silent: true)
        guard outcome == .sent else {
            job.then = .none
            registry.finish(job.id, state: .failed, result: nil)
            return reject("child_start_failed", "The helper session did not start (\(String(describing: outcome))).")
        }
        registry.markRunning(job.id, sessionId: childId)
        // [T-sub-agents-badge] Name the agent only now that it is confirmed
        // running. Before this point the card's badge stays the generic
        // "Agent": while a delegation is still starting there is nothing
        // meaningful to name yet, and a name that appears and then changes
        // reads as a glitch.
        if msgIdx < messages.count, blockIdx < messages[msgIdx].blocks.count {
            messages[msgIdx].blocks[blockIdx].helperAgentName = subAgent.name
        }

        // ── Background mode (P2): return now, report back later ──────────
        if !wait {
            return startBackgroundHelper(job: job, child: child, childId: childId, toolUseId: toolUseId,
                                         resolution: resolution, modelOrigin: resolution.origin, minutes: minutes,
                                         title: displayTitle, startedAt: startedAt,
                                         msgIdx: msgIdx, blockIdx: blockIdx, converted: false)
        }

        // ── Wait mode: poll until done / timeout / parent cancel / user follow-up ──
        // One polling loop instead of racing tasks: every 500ms it mirrors
        // the child's activity into the parent block, honours the budget, and
        // — new — converts to background the moment the USER sends a
        // follow-up in the parent (a queued prompt that is not a job result):
        // the tool returns `running` so the parent can answer the user now,
        // and the helper's result arrives later as its own message.
        var finalStatus = "completed"
        var tick = 0
        var wrapUpAskedAt: Date? = nil
        var wrapUpToolStopped = false
        while child.isProcessing {
            if Task.isCancelled { break }                       // parent Stop → cascade below
            if Date().timeIntervalSince(startedAt) >= TimeInterval(minutes * 60) {
                // [T-agent-wrapup-turn] Budget over: first ask for the
                // deliverable (one tool-less turn), cancel only if that does
                // not land within the grace period.
                if let asked = wrapUpAskedAt {
                    let waited = Date().timeIntervalSince(asked)
                    if waited >= Self.helperWrapUpGraceSeconds {
                        finalStatus = "timeout"
                        logger.warning("[delegate_task] TIMEOUT after \(minutes)m (+grace) — cancelling child \(childId.prefix(8))")
                        child.cancel()
                        break
                    }
                    if waited >= Self.helperWrapUpToolPatience, !wrapUpToolStopped {
                        // Blocked inside one long tool call: stop the tool
                        // (not the run) so the wrap-up turn can start.
                        wrapUpToolStopped = true
                        logger.warning("[delegate_task] child \(childId.prefix(8)) still inside a tool — stopping it for the wrap-up turn")
                        child.stopCurrentCommand()
                    }
                } else {
                    wrapUpAskedAt = Date()
                    child.helperWrapUpRequested = true
                    logger.warning("[delegate_task] budget over after \(minutes)m — asking child \(childId.prefix(8)) to wrap up")
                }
            }
            if promptQueue.contains(where: { !$0.deferUntilIdle }) {
                logger.info("[delegate_task] user follow-up queued while waiting — converting job \(job.id.prefix(8)) to background")
                job.then = .followUpParent(template: nil)
                return startBackgroundHelper(job: job, child: child, childId: childId, toolUseId: toolUseId,
                                             resolution: resolution, modelOrigin: resolution.origin, minutes: minutes,
                                             title: displayTitle, startedAt: startedAt,
                                             msgIdx: msgIdx, blockIdx: blockIdx, converted: true)
            }
            tick += 1
            if tick % 2 == 0 {
                syncHelperModel(job: job, child: child, toolUseId: toolUseId)
                let info = SessionActivityTracker.shared.sessionToolInfo[childId]
                writeParentBlock(msgIdx: msgIdx, blockIdx: blockIdx,
                                 content: Self.progressLine(title: displayTitle,
                                                            tool: info?.toolName ?? "",
                                                            status: info?.toolStatus ?? "",
                                                            elapsed: Date().timeIntervalSince(startedAt)))
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        if Task.isCancelled, child.isProcessing {
            child.cancel()
        }
        // Let the child's cancel path flip isProcessing before reading results.
        var settle = 0
        while child.isProcessing, settle < 20 { try? await Task.sleep(nanoseconds: 100_000_000); settle += 1 }

        // [T-subagent-error-surface] Read once, here: the payload below needs
        // both the raw text (for the detail sheet) and its classification (for
        // the card), and `errorMessage` can be cleared by a later turn.
        let childError = child.errorMessage
        if finalStatus == "completed" {
            if child.userDidCancel || Task.isCancelled { finalStatus = "cancelled" }
            else if childError != nil { finalStatus = "failed" }
        }
        let elapsed = Date().timeIntervalSince(startedAt)
        let resultText = await AgentJobRegistry.lastAssistantText(sessionId: childId) ?? ""
        let raws = await ChatStore.shared.loadMessages(sessionId: childId)
        let turns = raws.filter { $0.role == .assistant }.count
        let escalationRequested = resultText.contains("[ESCALATE]")

        // [T-subagent-no-deliverable-status] Reclassify a clean exit that wrote
        // nothing BEFORE the state and the payload are derived, so the registry
        // state, `ok` and `status` all describe the same outcome. Computed off
        // the pre-reclassification status so the note keeps naming the real
        // loop state ("completed") rather than the label we show for it.
        let reportedStatus = Self.resolvedStatus(finalStatus, result: resultText)
        let jobState: AgentJobState = switch reportedStatus {
        case "completed": .done
        case "cancelled": .cancelled
        case "timeout": .timeout
        default: .failed
        }
        registry.finish(job.id, state: jobState, result: resultText)
        // finish() merged the child's last served turn into the identity.
        syncHelperModel(job: job, child: child, toolUseId: toolUseId)

        var payload: [String: Any] = [
            "ok": reportedStatus == "completed",
            "status": reportedStatus,
            "result": resultText.isEmpty ? Self.emptyResultNote(status: finalStatus) : resultText,
            "model_used": resolution.modelLabel,
            "model_origin": resolution.origin.rawValue,
            "agent": subAgent.name,
            // [T-subagent-thinking-override] The level this run actually used,
            // reported by the seeder rather than re-derived here.
            "thinking_level": appliedThinking?.rawValue ?? NSNull(),
            // [T-sub-agents-v1] Only present when it is true, so an ordinary
            // result is byte-identical to before this feature.
            "model_group_unavailable": resolution.modelGroupUnavailable ? true : NSNull(),
            "escalation_requested": escalationRequested,
            // [T-subagent-error-surface] Why it failed, in two forms: a short
            // label the card can show, and the raw text for the detail sheet.
            "error_kind": Self.errorKind(childError) ?? NSNull(),
            "error_detail": childError ?? NSNull(),
            "turns": turns,
            "elapsed_s": Int(elapsed),
            "child_session_id": childId,
            "job_id": job.id,
            "summary": Self.runSummaryLine(child: child),
        ]
        if let identity = job.modelIdentity {
            payload.merge(identity.payload()) { _, new in new }
        }
        let json = Self.jsonString(payload)
        logger.info("[delegate_task] END job=\(job.id.prefix(8)) status=\(finalStatus) turns=\(turns) elapsed=\(Int(elapsed))s result=\(resultText.count)ch")
        writeParentBlock(msgIdx: msgIdx, blockIdx: blockIdx, content: json)
        return (json, finalStatus == "completed")
    }

    // MARK: - agent_status tool (T-p2-background-default)

    /// Let the parent model look at (or cancel) its delegated agents without
    /// waiting for the completion message. Scope: jobs whose target parent is
    /// this session.
    func executeAgentStatus(args: [String: Any]) -> (output: String, success: Bool) {
        guard let sid = sessionId else { return (Self.jsonString(["ok": false, "error": "no_session"]), false) }
        let registry = AgentJobRegistry.shared
        let action = ((args["action"] as? String) ?? "status").lowercased()
        let jobIdArg = (args["job_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        var jobs = registry.list().filter { $0.target.parentSessionId == sid }
        if let jid = jobIdArg, !jid.isEmpty {
            jobs = jobs.filter { $0.id == jid || $0.id.hasPrefix(jid) }
            if jobs.isEmpty {
                return (Self.jsonString(["ok": false, "error": "job_not_found", "job_id": jid]), false)
            }
        }
        if action == "cancel" {
            guard let jid = jobIdArg, !jid.isEmpty else {
                return (Self.jsonString(["ok": false, "error": "job_id_required_for_cancel"]), false)
            }
            for job in jobs where job.state == .running || job.state == .pending {
                registry.cancel(jobId: job.id, reason: "subagent_task cancel by parent model")
            }
        }
        // [T-sub-agents-steer] Queue a course correction into a running sub
        // agent without stopping it. The message is read at the child's next
        // loop boundary, so an in-flight tool call finishes first and the work
        // done so far is kept — the whole reason to steer rather than cancel.
        if action == "steer" {
            guard let jid = jobIdArg, !jid.isEmpty else {
                return (Self.jsonString(["ok": false, "error": "job_id_required_for_steer"]), false)
            }
            let message = (args["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !message.isEmpty else {
                return (Self.jsonString(["ok": false, "error": "message_required_for_steer"]), false)
            }
            guard let job = jobs.first else {
                return (Self.jsonString(["ok": false, "error": "job_not_found", "job_id": jid]), false)
            }
            // Rejected rather than queued once the run is over: a steer that
            // cannot change anything must say so, not look accepted.
            guard job.state == .running || job.state == .pending else {
                return (Self.jsonString([
                    "ok": false, "status": "rejected", "job_id": job.id,
                    "reason": "already_finished", "state": job.state.rawValue,
                    "detail": "That sub agent has already finished — its result stands. Delegate a new task instead.",
                ]), false)
            }
            guard let cid = job.runSessionId, let child = ViewModelCache.shared.get(for: cid) else {
                return (Self.jsonString([
                    "ok": false, "status": "rejected", "job_id": job.id,
                    "reason": "child_not_running",
                    "detail": "That sub agent has no live session to steer.",
                ]), false)
            }
            child.pendingSteerMessages.append(message)
            // [T-subagent-steer-continues-loop] If the child's loop is between
            // turns right now, nothing is left to consume the steer — it would
            // sit in the queue until the run ended and then be reported as a
            // missed correction. Nudge the loop so it takes the turn that
            // delivers it, the same way a queued user prompt restarts an idle
            // conversation.
            if !child.isProcessing {
                logger.info("[subagent_task] steer arrived while child \(cid.prefix(8)) was idle — nudging its loop")
                _ = child.submitProgrammaticPrompt(Self.steerNudgePrompt,
                                                   origin: .job(jobId: job.id), silent: true)
            }
            logger.info("[subagent_task] steer queued job=\(job.id.prefix(8)) child=\(cid.prefix(8)) chars=\(message.count)")
            return (Self.jsonString([
                "ok": true, "status": "queued", "job_id": job.id,
                "agent": job.subAgentName ?? NSNull(),
                "detail": "Queued. The sub agent reads it at its next turn; a tool call already running is not interrupted. It may finish before consuming it.",
            ]), true)
        }
        let queuedWaiting = registry.queuedCount(parent: sid)
        let entries: [[String: Any]] = jobs.map { job in
            var d: [String: Any] = [
                "job_id": job.id,
                "title": job.title,
                "state": job.state.rawValue,
                "model_origin": job.modelOrigin ?? NSNull(),
                "agent": job.subAgentName ?? NSNull(),
                "elapsed_s": Int(job.elapsed ?? 0),
                "child_session_id": job.runSessionId ?? NSNull(),
                "delivery": job.then == .none ? "tool_result" : "new_message_when_done",
            ]
            // [T-sub-agents-steer] Only present when something went unread, so
            // its absence is not a claim that a steer landed.
            if !job.missedSteers.isEmpty {
                d["missed_steer"] = job.missedSteers
                d["missed_steer_note"] = "The run ended before reading these — the result does not reflect them."
            }
            if let cid = job.runSessionId, job.state == .running,
               let vm = ViewModelCache.shared.get(for: cid) {
                job.modelIdentity?.merge(vm.lastEffectiveModel)
            }
            if let identity = job.modelIdentity {
                d["tier_requested"] = identity.tierRequested ?? NSNull()
                d["model_resolved"] = identity.resolvedLabel ?? NSNull()
                d["model_effective"] = identity.effectiveModel ?? NSNull()
            }
            if let cid = job.runSessionId, let info = SessionActivityTracker.shared.sessionToolInfo[cid],
               job.state == .running {
                d["current_tool"] = info.toolName
                d["current_status"] = String(info.toolStatus.prefix(120))
                d["loop_iteration"] = info.loopIteration
            }
            if job.state != .running && job.state != .pending, let r = job.resultText {
                d["result"] = String(r.prefix(2000))
            }
            return d
        }
        let out: [String: Any] = [
            "ok": true,
            "action": action,
            "count": entries.count,
            "agents": entries,
            // [T-sub-agents-queue] Waiting for a slot, not yet a job — so they
            // have no entry above. Reported so the model can see that work it
            // delegated is still coming and must not be delegated again.
            "queued": queuedWaiting,
            "note": "Running agents deliver their final result automatically as a new message in this conversation; you do not need to poll for it."
                + (queuedWaiting > 0 ? " \(queuedWaiting) more are queued and will start as slots free — do not re-delegate them." : ""),
        ]
        return (Self.jsonString(out), true)
    }

    // MARK: - Background mode (T-p2-background-helper)

    /// The parent turn ends immediately; the child keeps running. Completion
    /// is closed by the registry (loop-end observer or the watcher below),
    /// which runs `then: .followUpParent` → `submitProgrammaticPrompt` into
    /// the parent. If the parent is mid-turn at that moment the prompt is
    /// queued and drained by its epilogue or by P0's idle-drain rescue — the
    /// exact race the schedule design's §2.4.1 was written for.
    private func startBackgroundHelper(job: AgentJob, child: AIChatViewModel, childId: String,
                                       toolUseId: String, resolution: HelperModelResolution,
                                       modelOrigin: HelperModelOrigin, minutes: Int, title: String,
                                       startedAt: Date, msgIdx: Int, blockIdx: Int,
                                       converted: Bool) -> (output: String, success: Bool) {
        let registry = AgentJobRegistry.shared

        // Live mirror of the child's activity into the parent block, keyed by
        // tool_use id so it survives the parent's later turns and reloads.
        let mirror = Task { @MainActor [weak self] in
            while !Task.isCancelled, child.isProcessing {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                // Re-check AFTER the sleep: the completion hook cancels this
                // task and writes the final JSON while we may be asleep — a
                // write here would overwrite it with a stale progress line
                // (device run 19:41: cancelled block still read "running").
                guard !Task.isCancelled, child.isProcessing, let self else { return }
                self.syncHelperModel(job: job, child: child, toolUseId: toolUseId)
                let info = SessionActivityTracker.shared.sessionToolInfo[childId]
                job.summaryLine = Self.runSummaryLine(child: child)
                self.writeParentBlock(toolUseId: toolUseId,
                                      content: Self.progressLine(title: title,
                                                                 tool: info?.toolName ?? "",
                                                                 status: info?.toolStatus ?? AppLocalized("running in background"),
                                                                 elapsed: Date().timeIntervalSince(startedAt)),
                                      status: .running)
            }
        }

        // Final JSON into the parent block once the job closes, whichever
        // path closes it. Located by tool_use id — the block index is not
        // stable across a reload.
        job.completionHook = { [weak self] finished in
            mirror.cancel()
            finished.summaryLine = Self.runSummaryLine(child: child)
            guard let self else { return }
            // finish() already merged the child's last served turn.
            self.syncHelperModel(job: finished, child: child, toolUseId: toolUseId)
            let result = finished.resultText ?? ""
            // [T-subagent-no-deliverable-status] Same reclassification as the
            // wait-mode path above — a background run that ends `done` with no
            // text is the SAME outcome and must not report a green "Done"
            // either. Kept as one shared helper so the two paths cannot drift.
            let loopStatus = finished.state == .done ? "completed" : finished.state.rawValue
            let reportedStatus = Self.resolvedStatus(loopStatus, result: result)
            var payload: [String: Any] = [
                "ok": reportedStatus == "completed",
                // [T-sub-agents-resume] Only present when it is true, so an
                // uninterrupted run's payload is unchanged.
                "resumed": finished.wasResumed ? true : NSNull(),
                "resumed_note": finished.wasResumed
                    ? "This run was interrupted and resumed; its tool context was lost at that point, and the elapsed/turn counts cover only the resumed part. Verify anything that depended on a page or process staying open."
                    : NSNull(),
                "status": reportedStatus,
                "result": result.isEmpty ? Self.emptyResultNote(status: loopStatus) : result,
                "model_used": resolution.modelLabel,
                "model_origin": resolution.origin.rawValue,
                // Read off the job rather than the definition: this closure
                // outlives the call, and the definition may have been renamed
                // or deleted by the time the run finishes.
                "agent": finished.subAgentName ?? NSNull(),
                "model_group_unavailable": resolution.modelGroupUnavailable ? true : NSNull(),
                "escalation_requested": result.contains("[ESCALATE]"),
                // [T-subagent-thinking-override] Read back from the child's own
                // config rather than threaded through: this closure outlives the
                // call, and the config is where the level actually lives.
                "thinking_level": ProviderConfigStore.shared
                    .inferenceConfig(for: childId)?.thinkingLevel.rawValue ?? NSNull(),
                // [T-subagent-error-surface] Same two fields as the wait-mode
                // payload. Read from the child here rather than captured
                // earlier: this hook runs when the run ends, which is the
                // moment the error is actually known.
                "error_kind": Self.errorKind(child.errorMessage) ?? NSNull(),
                "error_detail": child.errorMessage ?? NSNull(),
                "elapsed_s": Int(finished.elapsed ?? 0),
                "child_session_id": childId,
                "job_id": finished.id,
                "delivered_as": "new turn in this conversation",
                "summary": finished.summaryLine ?? "",
            ]
            if let identity = finished.modelIdentity {
                payload.merge(identity.payload()) { _, new in new }
            }
            let finalStatus: ToolBlockStatus = switch finished.state {
            case .done: .success
            case .cancelled: .cancelled
            case .timeout: .failed(message: AppLocalized("Timed out"))
            default: .failed(message: AppLocalized("Failed"))
            }
            let finalJSON = Self.jsonString(payload)
            self.writeParentBlock(toolUseId: toolUseId, content: finalJSON, status: finalStatus)
            self.persistFinalDelegateResult(toolUseId: toolUseId, content: finalJSON, status: finalStatus)
            logger.info("[delegate_task] BACKGROUND END job=\(finished.id.prefix(8)) state=\(finished.state.rawValue)")
        }

        // Budget + watcher. `job.task` is cancelled by markFinished, so the
        // sleeper dies with the job.
        job.task = Task { @MainActor in
            let watcher = Task { @MainActor in
                // Current value first: the child can fail between `.sent` and
                // this task starting, and `.values` only yields on the next
                // publish — waiting for one that never comes would leave the
                // job `running` forever.
                if !child.isProcessing { return }
                for await processing in child.$isProcessing.values where !processing { break }
            }
            let budget = Task { @MainActor () -> Bool in
                do { try await Task.sleep(nanoseconds: UInt64(minutes) * 60 * 1_000_000_000); return true }
                catch { return false }
            }
            let timedOut: Bool = await withTaskGroup(of: Bool.self) { group -> Bool in
                group.addTask { await watcher.value; return false }
                group.addTask { await budget.value }
                let first = await group.next() ?? false
                budget.cancel(); watcher.cancel(); group.cancelAll()
                return first
            }
            var cancelledForTimeout = false
            if timedOut, child.isProcessing {
                // [T-agent-wrapup-turn] Ask for the deliverable first; cancel
                // only if the wrap-up turn does not finish within the grace.
                logger.warning("[delegate_task] BACKGROUND budget over after \(minutes)m — asking child \(childId.prefix(8)) to wrap up")
                if await !Self.awaitHelperWrapUp(child, childId: childId) {
                    logger.warning("[delegate_task] BACKGROUND TIMEOUT (+grace) — cancelling child \(childId.prefix(8))")
                    cancelledForTimeout = true
                    child.cancel()
                    // [T-sub-agents-busy] Bounded wait, NOT a bare
                    // `for await …$isProcessing.values`: that waits for the
                    // next PUBLISH, so if cancel() already settled the child
                    // (or it finished on its own in the same instant) nothing
                    // is ever emitted and this task hangs — leaving the job
                    // pinned to `running` for the life of the process, which
                    // now also pins the composer to Stop. awaitChildIdle
                    // checks the current value first and gives up after the
                    // grace period regardless.
                    if await !Self.awaitChildIdle(child, seconds: Self.helperWrapUpGraceSeconds) {
                        logger.warning("[delegate_task] child \(childId.prefix(8)) still processing after cancel — closing the job anyway")
                    }
                }
            }
            // Idempotent with the registry's loop-end observer.
            let text = await AgentJobRegistry.lastAssistantText(sessionId: childId)
            let state: AgentJobState = cancelledForTimeout ? .timeout : (child.userDidCancel ? .cancelled : .done)
            registry.finish(job.id, state: state, result: text)
        }

        startProgressReporter(job: job, child: child, childId: childId, title: title, startedAt: startedAt)

        JobInsuranceNotification.schedule(
            jobId: job.id,
            at: startedAt.addingTimeInterval(TimeInterval(minutes * 60 + 60)),
            title: AppLocalized("Minis agent"),
            body: String(format: AppLocalized("The agent \"%@\" may have finished or been interrupted. Open to check."), title),
            userInfo: ["sessionId": job.target.parentSessionId ?? "", "childSessionId": childId, "helperJobId": job.id])

        var payload: [String: Any] = [
            "ok": true,
            "status": "running",
            "job_id": job.id,
            "child_session_id": childId,
            // [T-subagent-resume-agent-name] Which named sub agent is running.
            //
            // The two COMPLETION payloads have always carried this; this one —
            // the only payload persisted while a job is still RUNNING, which is
            // precisely the state a resume recovers from — did not. So a run
            // interrupted by a restart left the parent's stored tool_result
            // with no record of the agent, `resumeInterruptedHelper` read nil,
            // and the resume silently fell back to the built-in: a task
            // delegated to "LENS" came back as a General Sub Agent.
            "agent": job.subAgentName ?? NSNull(),
            "model_used": resolution.modelLabel,
            "model_origin": resolution.origin.rawValue,
            "budget_minutes": minutes,
            "converted_from_wait": converted,
            "note": converted
                ? "The user sent a new message while you were waiting, so this delegation was moved to the background. Answer the user now; the agent's result will arrive as a NEW MESSAGE in this conversation (prefixed [Background task finished …]) when it is done. Use agent_status if you need its current state."
                : "The agent is running in the background. Its result will arrive as a NEW MESSAGE in this conversation (prefixed [Background task finished …]) when it is done — end this turn when you have nothing else to do. Use agent_status to check on it or cancel it; do not poll in a loop.",
        ]
        // This JSON is what the parent persists as the tool_result, so a
        // block reloaded after a restart still knows tier + resolved model
        // (and whatever effective model was confirmed before the parent
        // turn ended — a wait→background conversion happens mid-run).
        syncHelperModel(job: job, child: child, toolUseId: toolUseId)
        if let identity = job.modelIdentity {
            payload.merge(identity.payload()) { _, new in new }
        }
        let json = Self.jsonString(payload)
        writeParentBlock(msgIdx: msgIdx, blockIdx: blockIdx,
                         content: Self.progressLine(title: title, tool: "", status: AppLocalized("running in background"), elapsed: 0))
        logger.info("[delegate_task] BACKGROUND START job=\(job.id.prefix(8)) child=\(childId.prefix(8)) budget=\(minutes)m")
        return (json, true)
    }

    // MARK: - Run summary (tools · turns · tokens)

    /// One line the parent model can read at a glance, built from the child
    /// vm's in-memory transcript (tool blocks) and its token counters.
    static func runSummaryLine(child: AIChatViewModel) -> String {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for msg in child.messages where msg.role == .assistant {
            for block in msg.blocks where block.toolStatus != nil {
                let name: String = switch block.kind {
                case .shellTool: "shell_execute"
                case .fileReadTool: "file_read"
                case .fileWriteTool: "file_write"
                case .fileEditTool: "file_edit"
                case .browserTool: "browser_use"
                case .readImageTool: "read_image"
                case .memoryTool(let a): a
                case .delegateTool: SubAgentDefinition.toolName
                default: "other"
                }
                if counts[name] == nil { order.append(name) }
                counts[name, default: 0] += 1
            }
        }
        let tools = order.isEmpty ? "none yet" : order.map { "\($0)×\(counts[$0] ?? 0)" }.joined(separator: ", ")
        let stats = child.sessionTokenStats
        func k(_ n: Int) -> String { n >= 1000 ? String(format: "%.1fk", Double(n) / 1000) : "\(n)" }
        var tokens = "in \(k(stats.input)) / out \(k(stats.output))"
        if stats.cacheRead > 0 { tokens += " (cache read \(k(stats.cacheRead)))" }
        return "Summary: tools \(tools) · turns \(stats.loopCount) · tokens \(tokens)"
    }

    // MARK: - Progress reports (T-p2-progress-report)

    static let progressIntervalFrequent: TimeInterval = 15
    static let progressIntervalModerate: TimeInterval = 60
    static let progressLastMessageMaxChars = 800

    /// Periodically inject a `[Background task progress · …]` user message
    /// into the parent so the parent model can react mid-run. "frequent"
    /// reports every 15s only when something changed (tool, status, last
    /// message, turn); "moderate" reports every 60s regardless. A report that
    /// is still queued when the next one is due is replaced, so a busy parent
    /// never accumulates a backlog. Reports never interrupt the parent's own
    /// tool chain (they ride the gentle, defer-until-idle path).
    private func startProgressReporter(job: AgentJob, child: AIChatViewModel, childId: String,
                                       title: String, startedAt: Date) {
        let interval: TimeInterval
        switch job.progressLevel {
        case "frequent": interval = Self.progressIntervalFrequent
        case "moderate": interval = Self.progressIntervalModerate
        default: return
        }
        let onlyOnChange = job.progressLevel == "frequent"
        job.progressTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                guard !Task.isCancelled, let self, child.isProcessing,
                      job.state == .running else { return }
                let info = SessionActivityTracker.shared.sessionToolInfo[childId]
                let lastMessage = SendPromptIntent.extractResponseText(from: child)
                let signature = "\(info?.toolName ?? "")|\(info?.toolStatus ?? "")|\(info?.loopIteration ?? 0)|\(lastMessage.prefix(200))"
                if onlyOnChange, signature == job.lastProgressSignature {
                    logger.info("[delegate_task] progress \(job.id.prefix(8)) unchanged — skipped")
                    continue
                }
                job.lastProgressSignature = signature
                let elapsed = Int(Date().timeIntervalSince(startedAt))
                let clock = elapsed >= 60 ? "\(elapsed / 60)m\(String(format: "%02d", elapsed % 60))s" : "\(elapsed)s"
                let body = lastMessage.isEmpty || lastMessage == "No response."
                    ? "(no message from the agent yet)"
                    : String(lastMessage.prefix(Self.progressLastMessageMaxChars))
                let summary = Self.runSummaryLine(child: child)
                job.summaryLine = summary
                self.syncHelperModel(job: job, child: child, toolUseId: nil)
                // [T-p3-agent-callback-cell] Same XML envelope as the completion
                // callback; the parent UI renders it as a progress cell.
                let text = AgentCallback(kind: .progress,
                                         jobId: job.id,
                                         childSessionId: childId,
                                         title: title,
                                         status: "running",
                                         tier: job.modelOrigin,
                                         elapsed: clock,
                                         tool: info?.toolName,
                                         activity: (info?.toolStatus).map { String($0.prefix(80)) },
                                         turn: info?.loopIteration,
                                         summary: summary.hasPrefix("Summary: ") ? String(summary.dropFirst(9)) : summary,
                                         body: body,
                                         siblings: job.target.parentSessionId.flatMap {
                                             AgentJobRegistry.siblingSummary(parentSessionId: $0, excluding: job.id)
                                         },
                                         modelIdentity: job.modelIdentity,
                                         agent: job.subAgentName).xml

                // Replace a report that the parent has not consumed yet.
                if let pending = job.pendingProgressPromptId,
                   self.promptQueue.contains(where: { $0.id == pending }) {
                    self.removeQueuedPrompt(id: pending)
                }
                job.pendingProgressPromptId = nil
                let outcome = self.submitProgrammaticPrompt(text, origin: .job(jobId: job.id), silent: true)
                if outcome == .queued { job.pendingProgressPromptId = self.promptQueue.last?.id }
                logger.info("[delegate_task] progress \(job.id.prefix(8)) level=\(job.progressLevel) elapsed=\(elapsed)s outcome=\(String(describing: outcome))")
            }
        }
    }

    /// [T-stop-sibling-subagent] True when this parent turn has been stopped
    /// by the user, so a sibling delegation that finishes afterwards must not
    /// be allowed to drive the conversation forward.
    ///
    /// The real case (2026-09-07 19:59:08.9, one-second window): the parent had
    /// several `subagent_task` calls open in the same turn. The user pressed
    /// Stop on ONE card; that path cancels by CHILD session id, so it correctly
    /// silenced that child — but a sibling had reported `success` 48ms earlier
    /// and its result was still on its way into `agentHistory`. The loop's
    /// existing `userDidCancel` break never fired, because the card's Stop
    /// never touched the parent. The parent sent the sibling's result to the
    /// model, which read a completed sub-task and delegated a fresh one — a new
    /// "starting" card appeared seconds after the user thought they had stopped
    /// everything.
    ///
    /// Deliberately read live rather than captured when the delegation
    /// started: the stop can land at any point during the child's run, and
    /// only the current value knows about it.
    ///
    /// Either flag mutes. `userDidCancel` covers stopping the conversation
    /// itself; `delegationResultsMuted` covers stopping an agent from its card,
    /// which must not put the parent through a full cancel (see its comment).
    var delegationResultsAreMuted: Bool {
        !Self.delegationResultMayDriveParent(parentCancelled: userDidCancel,
                                             delegationsMuted: delegationResultsMuted)
    }

    /// [T-stop-sibling-subagent] Whether a sub agent result that has just
    /// arrived may drive the parent's loop on to another turn.
    ///
    /// Pure and static so the rule can be tested without a view model, a
    /// session or a running loop — the real case is a sub-second race between
    /// a Stop and a sibling's completion, which is impractical to stage live.
    ///
    /// - Parameters:
    ///   - parentCancelled: the parent's own `userDidCancel`.
    ///   - delegationsMuted: set when the user stopped a sub agent from its
    ///     card, which does not cancel the parent turn.
    /// - Returns: true when the result may be sent to the model.
    static func delegationResultMayDriveParent(parentCancelled: Bool,
                                               delegationsMuted: Bool) -> Bool {
        !parentCancelled && !delegationsMuted
    }

    /// [T-stop-sibling-subagent] Stop this turn from being driven by sub agent
    /// results, without cancelling the turn itself.
    func muteDelegationResults(reason: String) {
        guard !delegationResultsMuted else { return }
        delegationResultsMuted = true
        logger.info("[delegate_task] parent \(self.sessionId?.prefix(8) ?? "nil") muted for sub agent results — \(reason)")
    }

    /// Locate a block by tool_use id across the message list (background
    /// completion happens long after the dispatch indices went stale).
    /// [T-agent-result-persist] A background delegate_task returned
    /// `status: running` as its tool_result, and that is what got persisted
    /// (history + DB). writeParentBlock only updates the live block, so after
    /// an app restart the block reloaded from the DB still said "running" —
    /// shown as "Interrupted" — although the job had finished and the parent
    /// had already been called back. Rewrite the stored tool_result too, in
    /// the model-facing history and in the parent's DB row.
    private func persistFinalDelegateResult(toolUseId: String, content: String, status: ToolBlockStatus) {
        let success: Bool = { if case .success = status { return true }; return false }()
        let statusText: String = {
            switch status {
            case .success: return "success"
            case .cancelled: return "cancelled"
            default: return "failed"
            }
        }()
        // [T-stop-sibling-subagent] The model-facing history is the half that
        // can restart the conversation, so it is the half the stop has to
        // withhold. The DB row below is still rewritten: the user asked to stop
        // the agents, not to lose the record of what the ones already finished
        // came back with, and the block stays readable in the transcript.
        if delegationResultsAreMuted {
            logger.info("[delegate_task] parent stopped — result for \(toolUseId.prefix(12)) kept for display, withheld from agentHistory")
        } else {
            for i in agentHistory.indices {
                for j in agentHistory[i].parts.indices {
                    if case .toolResult(let id, let name, _, _, let img, let mime, let url, let path, _) = agentHistory[i].parts[j],
                       id == toolUseId {
                        agentHistory[i].parts[j] = .toolResult(id: id, name: name, content: content, isError: !success,
                                                               imageData: img, imageMimeType: mime, pageURL: url, imageLinuxPath: path)
                    }
                }
            }
        }
        guard let sid = sessionId else { return }
        Task { @MainActor in
            let raws = await ChatStore.shared.loadMessages(sessionId: sid)
            for raw in raws where raw.role == .user {
                guard raw.parts.contains(where: { if case .toolResult(let tr) = $0 { return tr.toolUseId == toolUseId }; return false }) else { continue }
                let parts: [ContentPart] = raw.parts.map { part in
                    guard case .toolResult(let tr) = part, tr.toolUseId == toolUseId else { return part }
                    return .toolResult(ToolResult(toolUseId: tr.toolUseId, output: content, success: success,
                                                  mediaRef: tr.mediaRef, snapshot: tr.snapshot,
                                                  pageURL: tr.pageURL, status: statusText))
                }
                await ChatStore.shared.updateMessageParts(messageId: raw.id, parts: parts)
                await ChatStore.shared.markDirty(recordType: "Message", recordId: raw.id)
                logger.info("[delegate_task] persisted final result into message \(raw.id.prefix(8)) tool=\(toolUseId.prefix(12))")
                return
            }
            logger.warning("[delegate_task] final result NOT persisted — no stored tool_result for \(toolUseId.prefix(12))")
        }
    }

    /// [T-agent-model-identity] Fold the child's latest served turn into the
    /// job's identity and mirror it onto the parent block (located by
    /// tool_use id — indices go stale across the parent's later turns).
    /// Publishes only on change so the block does not re-render every tick.
    func syncHelperModel(job: AgentJob, child: AIChatViewModel, toolUseId: String?) {
        guard var identity = job.modelIdentity else { return }
        identity.merge(child.lastEffectiveModel)
        if identity != job.modelIdentity { job.modelIdentity = identity }
        guard let toolUseId else { return }
        for msg in messages where msg.role == .assistant {
            if let block = msg.blocks.first(where: { $0.toolUseId == toolUseId }) {
                if block.helperModel != identity { block.helperModel = identity }
                return
            }
        }
    }

    private func writeParentBlock(toolUseId: String, content: String, status: ToolBlockStatus? = nil) {
        for msg in messages where msg.role == .assistant {
            if let block = msg.blocks.first(where: { $0.toolUseId == toolUseId }) {
                block.content = content
                if let status, block.toolStatus != status { block.toolStatus = status }
                return
            }
        }
    }

    // MARK: - Helpers

    private func writeParentBlock(msgIdx: Int, blockIdx: Int, content: String) {
        guard msgIdx < messages.count, blockIdx < messages[msgIdx].blocks.count else { return }
        messages[msgIdx].blocks[blockIdx].content = content
    }

    static func progressLine(title: String, tool: String, status: String, elapsed: TimeInterval) -> String {
        let s = Int(elapsed)
        let clock = String(format: "%d:%02d", s / 60, s % 60)
        var parts = ["◐ \(AppLocalized("Agent")) · \(title)"]
        if !tool.isEmpty { parts.append(tool) }
        if !status.isEmpty { parts.append(String(status.prefix(80))) }
        parts.append(clock)
        return parts.joined(separator: " · ")
    }

    static func jsonString(_ obj: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]),
              let s = String(data: data, encoding: .utf8) else { return "{}" }
        return s
    }

    /// [T-subagent-resume-agent-name] Recover the sub agent's name from the
    /// PERSISTED tool_result for this delegation.
    ///
    /// Why the in-memory block cannot answer this: while a delegation runs, a
    /// mirror task rewrites the parent block's `content` once a second with a
    /// progress line ("◐ Agent · title · … m:ss"). `parseDelegateResult`
    /// requires a leading `{`, so it returns nil for exactly the blocks a
    /// resume cares about. `AssistantBlock.helperAgentName` is no help either —
    /// it is `@Published` UI state that nothing writes to the database, so a
    /// restart loses it.
    ///
    /// The tool_result row, by contrast, holds the JSON the delegation returned
    /// when it started, which carries `"agent"`. That row is only rewritten by
    /// `persistFinalDelegateResult` when the job ENDS, so for an interrupted
    /// run it still holds the start payload.
    ///
    /// Returns nil only when NO row for this tool id carries a name — e.g. a
    /// delegation started by a build older than [T-subagent-resume-agent-name],
    /// whose start payload has no `"agent"` at all. That lands on the same
    /// built-in fallback as before, which is no worse than the old behaviour.
    /// Rows that merely happen to lack a name (control-call results, progress
    /// lines) are skipped, not treated as an answer — see the scan below.
    static func persistedAgentName(toolUseId: String, sessionId: String) async -> String? {
        let raws = await ChatStore.shared.loadMessages(sessionId: sessionId)
        for raw in raws where raw.role == .user {
            for part in raw.parts {
                guard case .toolResult(let tr) = part, tr.toolUseId == toolUseId else { continue }
                // [T-subagent-resume-agent-name-scan] Keep LOOKING rather than
                // giving up on the first row that does not carry a usable name.
                //
                // This used to `return nil` the moment a matching row failed any
                // of the three tests, which threw away the answer whenever
                // anything else had written under this toolUseId first. Several
                // payloads legitimately do: `agent_status` and steer results are
                // written with `"agent": job.subAgentName ?? NSNull()`, and
                // NSNull serialises to JSON `null`, so `obj["agent"] as? String`
                // is nil for them. A progress line fails `parseDelegateResult`
                // the same way. Either one appearing before the start payload
                // sent the resume to the built-in — the reported "delegated to a
                // named agent, came back as General Sub Agent".
                //
                // Skipping instead means the scan finds the start payload
                // wherever it sits among the rows for this id.
                guard let obj = parseDelegateResult(tr.output),
                      let name = obj["agent"] as? String,
                      !name.isEmpty else { continue }
                return name
            }
        }
        return nil
    }

    /// Parsed `delegate_task` result for the parent block's completed
    /// rendering and the history entry point (block content is the JSON).
    nonisolated static func parseDelegateResult(_ content: String) -> [String: Any]? {
        guard content.hasPrefix("{"), let data = content.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return obj
    }

    /// System-prompt preamble for a helper vm — replaces the SOUL identity
    /// section. Mirrors the `minis-model-use` wording that fixed OpenMinis#103
    /// (identity pollution): describe the environment, do not assign identity.
    var helperIdentitySection: String {
        guard let cfg = helperConfig else { return "" }
        return """
        You are running as a helper (sub-agent) inside an app called Minis, on an iOS device with a fully functional iSH Linux shell (Alpine Linux, aarch64). This is the calling environment, not your identity — keep your own model identity unchanged.
        A parent agent delegated ONE focused task to you: "\(cfg.title)". You cannot see the parent conversation and it cannot see yours; everything you need is in the task text. Work the task to completion using your tools, then finish with a single clear final answer — that final message is returned verbatim to the parent agent as the result, and it is the ONLY thing the parent receives. So the final message must contain the complete deliverable itself: the full report, document, list, code or answer the task asked for, in full — never a summary of what you did, a pointer to a file you wrote, or "see above". If you produced a file, paste its full content into the final message as well. Do not ask the parent or the user questions; make reasonable assumptions and state them. Do not restate the task or add meta-commentary.
        Browser: the browser is shared with the parent and with other agents. You may only use your own tabs (up to \(BrowserTabPool.agentTabQuota)); list_tabs shows just yours. To open a page, navigate (a tab of yours is picked or created) or use new_tab; never guess tab ids you did not receive, and reuse your tabs by navigating them instead of opening more.
        You have at most \(cfg.maxTurns) tool rounds; on the last one tools are withdrawn and you are asked to write the deliverable from what you have, so start writing before you run out. If the task genuinely exceeds what you can do at your capability level, end your final message with a line `[ESCALATE] <one-line reason>` so the parent can rerun it on a stronger model.
        \(Self.subAgentInstructionsSection(cfg.subAgentInstructions))
        """
    }

    /// [T-sub-agents-v1] The user's standing instructions for the sub agent
    /// running this task. Built-in and custom definitions take the SAME path —
    /// the built-in's instructions are the "every unnamed delegation" default
    /// and are deliberately NOT stacked onto a custom agent, which would make a
    /// custom agent's behaviour depend on text on another settings page.
    static func subAgentInstructionsSection(_ instructions: String) -> String {
        let trimmed = instructions.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        return """

        --- Sub agent instructions (set by the user) ---
        \(trimmed)
        """
    }
}

extension AIChatViewModel {
    /// [T-sub-agents-queue] Start a delegation that had been waiting for a slot.
    ///
    /// The original tool call returned `status: queued` long ago, so there is no
    /// tool_result to produce here — the run reports through the job's normal
    /// completion callback. Locating the parent block by `toolUseId` (rather
    /// than the msg/block indices the original call had) is what makes this
    /// safe: those indices go stale while the delegation sits in the queue.
    @discardableResult
    func executeDelegateTaskQueued(args: [String: Any], toolUseId: String) async -> Bool {
        // [T-sub-agents-resume] A queued RESUME is not a fresh delegation: it
        // has no `task`, and its child session already exists. Route it back to
        // the resume path instead of trying to spawn a new sub agent.
        if let childId = args["__resume_child"] as? String {
            let failure = await resumeInterruptedHelper(childSessionId: childId)
            if let failure {
                logger.warning("[subagent_task] queued resume failed — \(failure)")
            }
            return failure == nil
        }
        guard let (msgIdx, blockIdx) = blockIndices(forToolUseId: toolUseId) else {
            logger.warning("[subagent_task] queued start abandoned — block \(toolUseId.prefix(12)) is gone")
            return false
        }
        // Mark this as a re-entry from the queue. Without it the per-turn
        // allowance check below re-queues the delegation forever: it is
        // positional (the Nth delegate block of the turn), and the block's
        // position never changes, so a task queued for being 4th of 5 is
        // still 4th of 5 every time a slot frees.
        var args = args
        args[Self.queuedReentryKey] = true
        let r = await executeDelegateTask(args: args, toolUseId: toolUseId,
                                          msgIdx: msgIdx, blockIdx: blockIdx)
        return r.success
    }

    /// Current position of a tool block, looked up by its stable id.
    func blockIndices(forToolUseId toolUseId: String) -> (Int, Int)? {
        for (m, msg) in messages.enumerated() where msg.role == .assistant {
            if let b = msg.blocks.firstIndex(where: { $0.toolUseId == toolUseId }) {
                return (m, b)
            }
        }
        return nil
    }
}

// MARK: - Resuming an interrupted run  [T-sub-agents-resume]

extension AIChatViewModel {

    /// Prefix injected before a resumed child's next turn.
    ///
    /// A resumed sub agent reads a transcript of work it "remembers" doing, but
    /// the RUNTIME behind those tool calls is gone: the app was killed, so the
    /// browser tabs it opened are closed and the shell processes it started are
    /// dead. Files in the shared workspace survive. Without this it happily
    /// calls browser_use on a tab that no longer exists and burns rounds
    /// rediscovering that.
    static let helperResumeNotice = """
    [This run was interrupted and has been resumed. The tool results above are still valid, but all live state is gone: browser tabs are closed, shell processes have ended, and anything unsaved is lost. Files in the workspace are still there. Continue from what the transcript already establishes — reopen pages or re-run commands when you need them, and do not assume anything is still open.]
    """

    /// [T-sub-agents-resume] Restart an interrupted sub agent from its own
    /// transcript, reporting back into the SAME parent block.
    ///
    /// Nothing is read from a persisted registry, because there isn't one — the
    /// registry is in-memory by design. Every field is rebuilt from what is
    /// already on disk: the child session row carries the parent's id and the
    /// tool_use id, and the parent block's result JSON carries the title and
    /// the sub agent's name.
    ///
    /// - Returns: nil on success, or a short reason the caller can surface.
    /// [T-sub-agents-resume] `subagent_task action=resume` — the model's own
    /// way to restart what the app lost, after a sibling callback told it some
    /// runs are interrupted.
    ///
    /// Resumes one child when `child_session_id` is given, otherwise every
    /// interrupted run in this conversation.
    func executeResumeAgents(args: [String: Any]) async -> (output: String, success: Bool) {
        guard let sid = sessionId else {
            return (Self.jsonString(["ok": false, "error": "no_session"]), false)
        }
        var targets: [String] = []
        if let one = (args["child_session_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !one.isEmpty {
            targets = [one]
        } else {
            targets = Self.interruptedChildIds(in: self)
        }
        guard !targets.isEmpty else {
            return (Self.jsonString([
                "ok": true, "resumed": 0,
                "detail": "No interrupted sub agents in this conversation.",
            ]), true)
        }
        var ok: [String] = []
        var failed: [[String: Any]] = []
        for childId in targets {
            if let why = await resumeInterruptedHelper(childSessionId: childId) {
                failed.append(["child_session_id": childId, "reason": why])
            } else {
                ok.append(childId)
            }
        }
        logger.info("[subagent_task] RESUME action requested=\(targets.count) started=\(ok.count) failed=\(failed.count)")
        return (Self.jsonString([
            "ok": !ok.isEmpty,
            "resumed": ok.count,
            "child_session_ids": ok,
            "failed": failed.isEmpty ? NSNull() : failed,
            "detail": ok.isEmpty
                ? "None could be resumed."
                : "\(ok.count) sub agent(s) restarted; each reports back as a new message when it finishes. Do not re-delegate them.",
        ]), !ok.isEmpty)
    }

    /// Child session ids of this conversation's interrupted sub agents: their
    /// block still holds a "running" payload but no job backs it.
    /// [T-sub-agents-queue-orphan] Flag delegations left waiting by a restart.
    ///
    /// A block persisted as `status: queued` is backed only by the registry's
    /// in-memory queue. Unlike an interrupted run it has no child session and
    /// no persisted arguments, so once the process is gone there is nothing to
    /// start it and nothing to rebuild it from: left alone it shows "Queued"
    /// for good, with no button and no path out. Flagging it lets the card say
    /// so, and lets the parent model read it as work that never happened.
    func markLostQueuedDelegations() {
        let registry = AgentJobRegistry.shared
        var lost = 0
        for msg in messages where msg.role == .assistant {
            for block in msg.blocks {
                // [T-subagent-control-not-lost] Skip control calls. `queued`
                // means two unrelated things on two unrelated queues:
                //
                //   delegate — waiting for a CONCURRENCY SLOT in the registry's
                //     in-memory `queuedDelegations`. That queue dies with the
                //     process and holds the only copy of the arguments, so a
                //     restart really does mean "never started": the check below
                //     is right for it.
                //   steer    — waiting for the CHILD to read it on its next
                //     turn (`child.pendingSteerMessages`). Nothing ever puts it
                //     in `queuedDelegations`, so `isQueued` was false from the
                //     first scan onward — not after a restart, immediately —
                //     and every steer was flagged the moment this ran.
                //
                // That is the reported contradiction: a yellow "Never started"
                // badge over a result that reads "Queued. The sub agent reads
                // it at its next turn." The badge was answering a question
                // about the wrong queue.
                //
                // Control calls are not recoverable work and have nothing to
                // report as lost: the steer either lands on the child's next
                // turn or is surfaced through `missed_steer` on the run itself.
                guard case .delegateTool = block.kind, !block.helperQueueLost,
                      !HelperBlockInfo.isControlOnly(block),
                      let obj = AIChatViewModel.parseDelegateResult(block.content),
                      (obj["status"] as? String) == "queued",
                      let tid = block.toolUseId,
                      !registry.isQueued(toolUseId: tid) else { continue }
                block.helperQueueLost = true
                lost += 1
            }
        }
        if lost > 0 {
            logger.warning("[subagent_task] \(lost) queued delegation(s) lost with the previous process — flagged as never started")
        }
    }

    static func interruptedChildIds(in vm: AIChatViewModel) -> [String] {
        var out: [String] = []
        for msg in vm.messages {
            for b in msg.blocks {
                guard case .delegateTool = b.kind,
                      let obj = AIChatViewModel.parseDelegateResult(b.content),
                      (obj["status"] as? String) == "running",
                      let child = obj["child_session_id"] as? String else { continue }
                let alive = AgentJobRegistry.shared.list().contains {
                    $0.runSessionId == child && ($0.state == .running || $0.state == .pending)
                }
                if !alive, !out.contains(child) { out.append(child) }
            }
        }
        return out
    }

    @discardableResult
    func resumeInterruptedHelper(childSessionId childId: String) async -> String? {
        guard !isHelper else { return "a sub agent cannot resume another" }
        guard let parentSid = sessionId else { return "no session" }
        guard await ChatStore.shared.sessionExists(id: childId) else {
            return "that sub agent's session no longer exists"
        }
        let registry = AgentJobRegistry.shared
        // Already running (double tap, or it was resumed from another surface).
        if registry.list().contains(where: { $0.runSessionId == childId
            && ($0.state == .running || $0.state == .pending) }) {
            return nil
        }

        // ── Rebuild the identity from persisted state ────────────────────
        guard let childSession = await ChatStore.shared.getSession(childId),
              let toolUseId = childSession.parentToolUseId,
              childSession.parentSessionId == parentSid else {
            return "that sub agent is not part of this conversation"
        }
        // Split into statements on purpose: as one chained expression this
        // defeats the type checker (it reported "failed to produce diagnostic").
        var parentBlock: AssistantBlock?
        for msg in messages {
            if let b = msg.blocks.first(where: { $0.toolUseId == toolUseId }) {
                parentBlock = b
                break
            }
        }
        var payload: [String: Any]?
        if let parentBlock {
            payload = AIChatViewModel.parseDelegateResult(parentBlock.content)
        }
        var title = AppLocalized("Agent")
        if let t = payload?["title"] as? String, !t.isEmpty {
            title = t
        } else if let t = childSession.title, !t.isEmpty {
            title = t
        }
        // The definition may have been renamed or deleted while the app was
        // away; falling back to the built-in keeps the run possible, and the
        // result records which one actually ran.
        //
        // [T-subagent-resume-agent-name] `payload` is nil whenever the parent
        // block's content is a progress line rather than JSON — which is the
        // normal state of a RUNNING delegation, and therefore the normal state
        // of anything worth resuming. The name is recovered from the persisted
        // tool_result instead; see `resumeAgentName`.
        var agentName = payload?["agent"] as? String
        if agentName?.isEmpty ?? true {
            agentName = await Self.persistedAgentName(toolUseId: toolUseId, sessionId: parentSid)
        }
        let roster = SubAgentStore.shared.subAgents
        var subAgent = roster.first { $0.isBuiltIn } ?? SubAgentDefinition.makeBuiltIn()
        var namedAgentMissing: String?
        if let agentName, !agentName.isEmpty {
            if let named = roster.first(where: { $0.name == agentName }) {
                subAgent = named
            } else {
                // [T-subagent-resume-agent-name] Only a genuinely absent
                // definition may degrade the run to the built-in, and when it
                // does the user is told which name went missing rather than
                // being handed a General Sub Agent with no explanation.
                namedAgentMissing = agentName
                logger.warning("[subagent_task] RESUME agent \"\(agentName)\" no longer exists — falling back to the built-in")
            }
        }

        guard let resolution = SubAgentModelResolver.resolve(subAgent: subAgent, parent: self) else {
            return "no model is configured to run it on"
        }

        // ── Slot / queue, exactly as a fresh delegation ──────────────────
        guard registry.canStartChildJob else {
            let args: [String: Any] = ["__resume_child": childId, "tool_title": title]
            _ = registry.enqueueDelegation(.init(parentSessionId: parentSid, args: args, toolUseId: toolUseId))
            writeParentBlock(toolUseId: toolUseId,
                             content: Self.jsonString([
                                "ok": true, "status": "queued",
                                "detail": "All slots are busy; this resume is queued and starts when one frees.",
                             ]),
                             status: .running)
            return nil
        }

        // ── Child VM ─────────────────────────────────────────────────────
        // [T-vmcache-pools] Resume path — same child pool as the start path.
        let (child, fresh) = ViewModelCache.shared.getOrCreate(for: childId, kind: .child)
        if fresh || child.messages.isEmpty { await child.loadSession() }
        child.sessionSource = sessionSource
        child.memoryEnabled = false
        child.suppressGeneralCompletionNotification = true
        child.browserTabPool = browserTabPool

        let job = registry.register(title: title, origin: .tool, trigger: .immediate,
                                    target: .childOfCurrent(parentSessionId: parentSid,
                                                            parentToolUseId: toolUseId),
                                    prompt: nil, then: .followUpParent(template: nil))
        job.modelOrigin = resolution.origin.rawValue
        job.subAgentName = subAgent.name
        job.modelIdentity = HelperModelIdentity.make(resolution: resolution)
        job.wasResumed = true

        child.helperConfig = HelperConfig(parentSessionId: parentSid,
                                          parentToolUseId: toolUseId,
                                          jobId: job.id,
                                          maxTurns: Self.helperMaxTurns,
                                          title: title,
                                          modelOrigin: resolution.origin,
                                          subAgentId: subAgent.id,
                                          subAgentName: subAgent.name,
                                          subAgentInstructions: subAgent.instructions)
        ProviderConfigStore.shared.setBinding(
            SessionModelBinding(sessionId: childId, primarySource: resolution.source), for: childId)
        // [T-subagent-thinking-inherit] A resumed run re-resolves its model, so
        // it re-seeds the level too — otherwise resuming an interrupted sub
        // agent would silently drop it back to `.off` for the rest of the run.
        Self.seedChildThinkingLevel(childId: childId, parentSessionId: parentSid,
                                    resolution: resolution, subAgent: subAgent)

        // [T-sub-agents-resume] Re-attach the child to its parent block, the
        // same link the fresh delegation makes at the top of this file.
        // Without it the block keeps a nil `helperChildSessionId`, and every
        // surface that reaches the child through it goes dead: the detail
        // sheet's transcript button did nothing, because its only fallback —
        // parsing the child id back out of the block's JSON result — stops
        // working the moment the resumed run overwrites `content` with a
        // progress line.
        for msg in messages where msg.role == .assistant {
            if let block = msg.blocks.first(where: { $0.toolUseId == toolUseId }) {
                block.helperChildSessionId = childId
                block.helperModel = job.modelIdentity
                if block.toolSummary?.isEmpty ?? true { block.toolSummary = title }
                break
            }
        }

        // ── Continue the run ─────────────────────────────────────────────
        // The notice goes in as a normal programmatic prompt, so the child
        // reads it at the top of its next turn like any other injected text.
        let outcome = child.submitProgrammaticPrompt(Self.helperResumeNotice,
                                                     origin: .job(jobId: job.id), silent: true)
        guard outcome == .sent else {
            job.then = AgentJobThen.none
            registry.finish(job.id, state: .failed, result: nil)
            return "the sub agent session did not restart (\(String(describing: outcome)))"
        }
        registry.markRunning(job.id, sessionId: childId)
        // [T-sub-agents-badge] Same rule on resume: named once it is running.
        for msg in messages where msg.role == .assistant {
            if let block = msg.blocks.first(where: { $0.toolUseId == toolUseId }) {
                block.helperAgentName = subAgent.name
                break
            }
        }
        // [T-subagent-resume-agent-name] Say so when the named agent is gone.
        // Silently running a task delegated to "LENS" as a General Sub Agent is
        // the surprise this whole fix exists to remove; when the definition
        // really has been deleted the fallback is correct, but it must not be
        // invisible. Uses the existing transient-banner channel rather than the
        // return value, which every caller treats strictly as "resume failed".
        if let missing = namedAgentMissing {
            transientNotice = String(format: AppLocalized("Sub agent \"%@\" no longer exists — resumed with the built-in agent"), missing)
        }
        logger.info("[subagent_task] RESUME job=\(job.id.prefix(8)) child=\(childId.prefix(8)) parent=\(parentSid.prefix(8)) agent=\(subAgent.name) recovered=\(agentName ?? "nil")")

        // Budget restarts: the original one died with the process, and the user
        // has confirmed a fresh clock is the intended behaviour.
        _ = startBackgroundHelper(job: job, child: child, childId: childId,
                                  toolUseId: toolUseId, resolution: resolution,
                                  modelOrigin: resolution.origin,
                                  minutes: Self.helperDefaultMinutes, title: title,
                                  startedAt: Date(), msgIdx: 0, blockIdx: 0, converted: false)
        return nil
    }
}
