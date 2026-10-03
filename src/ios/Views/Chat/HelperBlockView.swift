import SwiftUI

// [T-p2-helper-block-render] Dedicated rendering for a `delegate_task` block.
// The generic tool capsule / thumbnail / live sheet all print `block.content`,
// which for a helper is the live progress line and then the result JSON —
// correct data, wrong presentation. This file owns the three surfaces:
//   • `HelperBlockView`      — the inline block in the parent transcript
//   • `HelperThumbnailView`  — the floating tool bar's 100×65 preview
//   • `HelperDetailCard`     — the tool live sheet's content area
// All three read `HelperBlockInfo`, the single parser for both content shapes.

/// What a helper block's content currently says, in structured form.
struct HelperBlockInfo {
    enum Phase {
        case starting
        /// `tool` is the child's own tool currently executing (shell_execute,
        /// browser_use …), shown with its icon so the agent block reads like
        /// any other tool that is mid-flight. [T-agent-inner-tool-status]
        case running(tool: String?, activity: String, clock: String)
        case finished(status: String, tier: String?, model: String?, elapsedSeconds: Int?,
                      summary: String, escalation: Bool, background: Bool, childSessionId: String?)
    }
    let title: String
    let phase: Phase
    /// [T-agent-model-identity] tier / resolved / effective model. Live from
    /// the block while the agent runs (HelperRunner mirrors the child's
    /// confirmed model into it), from the persisted payload after a reload.
    let model: HelperModelIdentity?
    /// [T-sub-agents-v1] Sub agent display name, when the run used a custom one.
    /// nil for the built-in, so an ordinary delegation's block is unchanged.
    let agent: String?
    /// [T-subagent-error-surface] Short attribution for a failed run ("Rate
    /// limited"), when the error text could be classified. nil otherwise —
    /// the card then keeps the plain "Failed".
    let errorKind: String?
    /// The child's raw error text, for the detail sheet. Never shown on the
    /// card: it can be a paragraph, and the card has room for a label.
    let errorDetail: String?

    init(title: String, phase: Phase, model: HelperModelIdentity? = nil, agent: String? = nil,
         errorKind: String? = nil, errorDetail: String? = nil) {
        self.title = title; self.phase = phase; self.model = model; self.agent = agent
        self.errorKind = errorKind; self.errorDetail = errorDetail
    }

    /// [T-subagent-error-surface] What the status chip should read. A failed
    /// run names its cause when one could be attributed, because "Failed"
    /// alone tells the user nothing they can act on — a rate limit, a dead
    /// network and a context overflow need three different responses.
    var statusText: String {
        guard case .finished(let status, _, _, _, _, _, _, _) = phase else {
            return HelperBlockInfo.statusLabel("running")
        }
        if status == "failed", let kind = errorKind { return kind }
        return HelperBlockInfo.statusLabel(status)
    }

    /// The built-in's name is suppressed: showing "General Sub Agent" on every
    /// ordinary delegation would be noise, and the block already reads "Agent".
    static func customAgentName(_ raw: String?) -> String? {
        guard let n = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty else { return nil }
        return n == AppLocalized("General Sub Agent") ? nil : n
    }

    static func parse(_ block: AssistantBlock) -> HelperBlockInfo {
        let title: String = {
            if let s = block.toolSummary, !s.isEmpty { return s }
            if case .delegateTool(let t) = block.kind, !t.isEmpty { return t }
            return AppLocalized("Agent")
        }()
        let content = block.content.trimmingCharacters(in: .whitespacesAndNewlines)

        // Result JSON (wait mode result, background start/finish, rejection).
        if let obj = AIChatViewModel.parseDelegateResult(content) {
            // The live value carries every confirmation the payload has and
            // more (a running block's payload is the start snapshot).
            let model = block.helperModel ?? HelperModelIdentity(payload: obj)
            var status = (obj["status"] as? String) ?? "unknown"
            if status == "running" {
                // The persisted tool_result says "running"; the block's live
                // status says whether the job is still alive. After an app
                // restart the job is gone (registry is in-memory), so the
                // honest reading is "interrupted", not "running forever".
                if case .running = block.toolStatus {
                    // A progress callback carries the live tool/activity.
                    let activity = (obj["activity"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                        ?? AppLocalized("running in background")
                    let tool = (obj["tool"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                    return HelperBlockInfo(title: title, phase: .running(tool: tool, activity: activity, clock: ""),
                                           model: model, agent: block.helperAgentName)
                }
                status = "interrupted"
            }
            // [T-sub-agents-queue-orphan] Same reasoning one state earlier.
            // A delegation waiting for a slot exists ONLY in the registry's
            // in-memory queue — unlike an interrupted run it has no child
            // session and no persisted args, so after a restart there is
            // nothing left to start it and nothing to rebuild it from. Left
            // as "Queued" it would spin forever with no button and no way
            // out. Reported as its own state so the parent model reads it as
            // work that never happened and re-delegates.
            //
            // [T-subagent-control-not-lost] Never for a control call. The
            // producer no longer flags those, but a block flagged by an
            // earlier build keeps `helperQueueLost` for the life of the object,
            // so the read side has to agree or the contradictory badge would
            // survive on screen until the session is reloaded.
            if status == "queued", block.helperQueueLost, !isControlOnly(block) {
                status = "queue_lost"
            }
            let result = (obj["result"] as? String) ?? (obj["detail"] as? String) ?? ""
            let firstLine = result.split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
            let summary = firstLine.count > 140 ? String(firstLine.prefix(140)) + "…" : firstLine
            return HelperBlockInfo(title: title, phase: .finished(
                status: status,
                tier: obj["tier_used"] as? String,
                model: obj["model_used"] as? String,
                elapsedSeconds: (obj["elapsed_s"] as? Int) ?? (obj["elapsed_s"] as? Double).map(Int.init),
                summary: summary,
                escalation: (obj["escalation_requested"] as? Bool) ?? false,
                background: obj["delivered_as"] != nil,
                childSessionId: obj["child_session_id"] as? String), model: model,
                agent: block.helperAgentName ?? (obj["agent"] as? String),
                errorKind: obj["error_kind"] as? String,
                errorDetail: obj["error_detail"] as? String)
        }

        // Progress line: "◐ Helper · <title> · [tool ·] [status ·] m:ss"
        if content.hasPrefix("◐") {
            var parts = content.components(separatedBy: " · ")
            if parts.count >= 2 { parts.removeFirst(2) }     // icon+label, title
            let clock = parts.last.flatMap { $0.contains(":") && $0.count <= 6 ? $0 : nil } ?? ""
            if !clock.isEmpty { parts.removeLast() }
            // First remaining part is the child's tool name when it looks
            // like one (progressLine puts it right after the title).
            var tool: String? = nil
            if let first = parts.first, Self.looksLikeToolName(first) {
                tool = first
                parts.removeFirst()
            }
            let activity = parts.joined(separator: " · ")
            return HelperBlockInfo(title: title, phase: .running(tool: tool, activity: activity, clock: clock),
                                   model: block.helperModel, agent: block.helperAgentName)
        }
        return HelperBlockInfo(title: title, phase: .starting, model: block.helperModel,
                               agent: block.helperAgentName)
    }

    /// The tier chip text: the tier that ran, with the requested one in
    /// front when the resolver degraded it ("sub → primary").
    static func tierLabel(_ model: HelperModelIdentity?, fallback: String?) -> String? {
        guard let used = model?.tierUsed ?? fallback else { return nil }
        if let model, model.tierDegraded, let req = model.tierRequested { return "\(req) → \(used)" }
        return used
    }

    /// [T-sub-agents-control-hidden] True for a `subagent_task` call that only
    /// INSPECTED or STEERED other runs — status, steer, cancel, resume.
    ///
    /// Those calls produce no work of their own: they are the model reading or
    /// nudging its own sub agents, and rendering each as a block puts rows in
    /// the transcript that the user cannot act on and did not ask for. The
    /// delegation itself still renders — that one represents real work.
    ///
    /// Detected from the RESULT rather than the arguments, because the block is
    /// what survives a reload and the arguments are not kept on it. Each
    /// control action returns a shape no delegation ever does.
    /// [T-subagent-control-row] One line describing a control call, for the
    /// compact row that replaces the hidden cell. nil when the block is not a
    /// control call.
    ///
    /// Reads the same two sources as `isControlOnly`: the call's own arguments
    /// when the block still has them, else the result's shape.
    static func controlSummary(_ block: AssistantBlock) -> String? {
        guard isControlOnly(block) else { return nil }
        let obj = AIChatViewModel.parseDelegateResult(block.content)
        var action: String?
        if let raw = block.toolInputArgs,
           let d = raw.data(using: .utf8),
           let args = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
            action = (args["action"] as? String)?.lowercased()
        }
        // Fall back to the result's shape for a reloaded block.
        if action == nil, let obj {
            if obj["agents"] != nil { action = "status" }
            else if obj["child_session_ids"] != nil { action = "resume" }
            else if obj["job_id"] != nil { action = "steer" }
        }
        switch action {
        case "status":
            // The count is what makes the line worth reading — "checked on 3"
            // says something "checked on sub agents" does not.
            if let agents = obj?["agents"] as? [Any] {
                return String(format: AppLocalized("Checked %d sub agent(s)"), agents.count)
            }
            return AppLocalized("Checked sub agent status")
        case "resume":
            if let ids = obj?["child_session_ids"] as? [Any] {
                return String(format: AppLocalized("Resumed %d sub agent(s)"), ids.count)
            }
            return AppLocalized("Resumed a sub agent")
        case "steer":
            return AppLocalized("Sent a course correction")
        case "cancel":
            return AppLocalized("Stopped a sub agent")
        default:
            return AppLocalized("Sub agent control")
        }
    }

    /// [T-subagent-control-capsule] What the parent SENT with a control call —
    /// the steer text, the ids it resumed — for the detail sheet.
    ///
    /// The result JSON records what came back; only the arguments record what
    /// went out, and for a steer that text is the entire content of the
    /// interaction. Without it the sheet could say a correction was sent but
    /// never what it said.
    static func controlPayload(_ block: AssistantBlock) -> String? {
        guard isControlOnly(block), let raw = block.toolInputArgs,
              let d = raw.data(using: .utf8),
              let args = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
        else { return nil }
        if let m = (args["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !m.isEmpty {
            return m
        }
        if let ids = args["child_session_ids"] as? [String], !ids.isEmpty {
            return ids.map { String($0.prefix(8)) }.joined(separator: "\n")
        }
        if let cid = args["child_session_id"] as? String, !cid.isEmpty {
            return String(cid.prefix(8))
        }
        return nil
    }

    static func isControlOnly(_ block: AssistantBlock) -> Bool {
        guard case .delegateTool = block.kind else { return false }
        // The call's own `action` when the arguments are on the block: the
        // direct answer to "was this a control call". A control call's effect
        // lands on the block of the run it acted on (resume writes through the
        // child's parentToolUseId), so its own block would only be a duplicate
        // row for work shown elsewhere.
        if let raw = block.toolInputArgs,
           let d = raw.data(using: .utf8),
           let args = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
           let action = (args["action"] as? String)?.lowercased() {
            return action != "delegate"
        }
        // Fallback for a reloaded block whose arguments were not kept: infer
        // from the result's shape.
        guard let obj = AIChatViewModel.parseDelegateResult(block.content) else { return false }
        // A delegation ALWAYS carries child_session_id — it is the run it
        // started. Control calls never do. Testing that first is what keeps a
        // resumed delegation visible: its result also has a `resumed` key (the
        // marker saying the run was interrupted midway), and keying on that
        // hid the very block the user had just resumed. Bridged JSON booleans
        // also read as Int, so `resumed is Int` matched `resumed: true`.
        if obj["child_session_id"] is String { return false }
        if obj["agents"] != nil { return true }                  // action=status
        if obj["child_session_ids"] != nil { return true }        // action=resume
        if let s = obj["status"] as? String, s == "queued", obj["job_id"] != nil { return true }  // steer
        if let r = obj["reason"] as? String,
           ["already_finished", "child_not_running"].contains(r) { return true }
        return false
    }

    static func looksLikeToolName(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 32 && s.allSatisfy { $0.isLetter && $0.isLowercase || $0 == "_" || $0.isNumber }
    }

    var isRunning: Bool {
        switch phase {
        case .starting, .running: return true
        case .finished: return false
        }
    }

    static func statusLabel(_ status: String) -> String {
        switch status {
        case "completed": return AppLocalized("Done")
        // [T-subagent-no-deliverable-status] The loop ended cleanly but wrote
        // no final answer. Deliberately NOT "Failed": nothing broke, and the
        // transcript is intact — there is simply nothing to hand back.
        case "no_deliverable": return AppLocalized("No result")
        case "cancelled": return AppLocalized("Cancelled")
        case "timeout": return AppLocalized("Timed out")
        case "failed": return AppLocalized("Failed")
        case "rejected": return AppLocalized("Rejected")
        case "interrupted": return AppLocalized("Interrupted")
        case "queued": return AppLocalized("Queued")
        case "queue_lost": return AppLocalized("Never started")
        // [T-subagent-unknown-status-label] Never surface the raw token.
        // A payload with no `status` key (a start/progress snapshot that was
        // never overwritten by the final JSON) fell through to here and put
        // the literal string "unknown" on screen in red — an internal value
        // leaking into the UI, and one that reads as an error when all it
        // means is that the app cannot tell. Anything unrecognised is reported
        // as not-known rather than as failure.
        default: return AppLocalized("Status unknown")
        }
    }

    static func statusColor(_ status: String) -> Color {
        switch status {
        case "completed": return .green
        // [T-subagent-no-deliverable-status] Warning, not error: the run is
        // recoverable by re-delegating with a narrower task or a bigger
        // budget — the same "needs attention, nothing is broken" reading as
        // timeout/interrupted, which is the closest existing neighbour.
        case "no_deliverable": return .yellow
        case "cancelled", "timeout", "interrupted": return .yellow
        // Waiting its turn, not a problem — the accent, not a warning colour.
        case "queued": return HelperAccent.color
        // Never ran and never will — the same "needs attention, not broken"
        // yellow that interrupted uses.
        case "queue_lost": return .yellow
        // [T-subagent-error-surface] A run that genuinely failed IS red. It
        // used to fall through to the neutral default below, so a real error
        // looked the same as "the app cannot tell how this ended" — and with
        // an empty result it was reclassified to the yellow "No result", which
        // reads as "nothing to hand back" rather than "something broke".
        case "failed": return .red
        // [T-subagent-unknown-status-label] Neutral, not red. Red asserts the
        // run failed; an unrecognised or missing status only says the app does
        // not know how it ended, and the transcript may well be intact. Use
        // the same secondary grey the rest of the card's chrome uses so it
        // reads as "no information" rather than "error".
        default: return ChatColors.secondaryText
        }
    }

    static func elapsedLabel(_ seconds: Int) -> String {
        seconds >= 60 ? "\(seconds / 60)m \(seconds % 60)s" : "\(seconds)s"
    }
}

/// Shared accent for every helper surface (capsule, sheet header, block).
enum HelperAccent {
    /// [T-agent-accent-color] Electric violet — reads as "AI / tech" without
    /// colliding with the app's semantic hues (red = stop/error, green =
    /// done, yellow = warning/timeout, system blue = thinking). Slightly
    /// deeper in light mode for contrast on the light card, lifted in dark
    /// mode so it stays luminous on near-black.
    static let color = Color(UIColor { tc in
        tc.userInterfaceStyle == .dark
            ? UIColor(red: 0.64, green: 0.52, blue: 1.00, alpha: 1)   // #A385FF
            : UIColor(red: 0.45, green: 0.30, blue: 0.93, alpha: 1)   // #734DED
    })
    /// Glyph with its own circular background — for places that draw the
    /// icon bare (detail header, tool sheet nav bar). [T-agent-icon]
    static let icon = "person.2.circle.fill"
    /// Plain glyph for places that already sit it on a filled circle.
    static let glyph = "person.2.fill"

    /// [T-agent-toolname-display] A wire tool name made presentable:
    /// `shell_execute` -> "Shell execute", `browser-use` -> "Browser use".
    ///
    /// Only for display. Every lookup (icons, matching) keeps using the raw
    /// name — this is the last step before the text reaches a label.
    static func displayName(forTool name: String) -> String {
        let spaced = name.replacingOccurrences(of: "_", with: " ")
                         .replacingOccurrences(of: "-", with: " ")
        guard let first = spaced.first else { return spaced }
        return first.uppercased() + spaced.dropFirst()
    }

    /// SF Symbol for a child's tool name — the same vocabulary
    /// ToolLiveSheet.toolIcon uses for the parent's own blocks.
    static func symbol(forTool name: String) -> String {
        switch name {
        case "shell_execute": return "terminal"
        case "file_read", "read_file": return "doc.text"
        case "file_write", "write_file": return "doc.text.fill"
        case "file_edit", "edit_file": return "square.and.pencil"
        case "browser_use": return "globe"
        case "read_image": return "photo"
        case "memory": return "brain.head.profile"
        case SubAgentDefinition.toolName: return glyph
        case "web_search": return "magnifyingglass"
        default: return "wrench.and.screwdriver"
        }
    }
}

// MARK: - Inline block

struct HelperBlockView: View {
    @ObservedObject var block: AssistantBlock
    @Binding var detailBlock: AssistantBlock?
    @State private var pulse = false

    /// Set the moment Resume is tapped so the button cannot be pressed twice
    /// while the restart is in flight.
    @State private var resuming = false

    private var info: HelperBlockInfo { HelperBlockInfo.parse(block) }

    /// The run the app lost when it was killed — the only status that offers
    /// a Resume. Finished / cancelled / timed-out runs ended by a decision.
    private func isInterrupted(_ i: HelperBlockInfo) -> Bool {
        if case .finished(let status, _, _, _, _, _, _, _) = i.phase { return status == "interrupted" }
        return false
    }

    /// [T-sub-agents-resume] Restart this interrupted sub agent. Its result
    /// goes back into THIS block and, through the job's followUpParent, to the
    /// parent model as a normal completion callback — so the conversation
    /// carries on as if the interruption had not happened.
    private func resume() {
        let childId = block.helperChildSessionId
            ?? (AIChatViewModel.parseDelegateResult(block.content)?["child_session_id"] as? String)
        guard let childId else { return }
        resuming = true
        Task { @MainActor in
            // The parent id comes from the CHILD SESSION ROW, not from the
            // environment: each cell is its own UIHostingConfiguration, a fresh
            // SwiftUI hierarchy that AIChatView's .environment does not reach
            // (see CollectionViewMessageListV3's note on that), so
            // @Environment(\.chatSessionId) would be nil here and the button
            // would silently do nothing.
            guard let child = await ChatStore.shared.getSession(childId),
                  let parentSid = child.parentSessionId,
                  await ChatStore.shared.sessionExists(id: parentSid) else {
                AppLogger(category: "HelperBlockView").warning("[agent] resume: no parent for child \(childId.prefix(8))")
                resuming = false
                return
            }
            // getOrCreate, not get: the parent's view model may have been
            // evicted since the app relaunched, and the resume has to work from
            // a transcript the user is looking at either way.
            let (parent, fresh) = ViewModelCache.shared.getOrCreate(for: parentSid)
            if fresh { await parent.loadSession() }
            let why = await parent.resumeInterruptedHelper(childSessionId: childId)
            if let why {
                AppLogger(category: "HelperBlockView").warning("[agent] resume refused — \(why)")
                // Let the user try again; the block still reads "interrupted".
                resuming = false
            }
            // On success the block flips to .running on its own and the button
            // goes with it, so `resuming` does not need clearing.
        }
    }

    /// [T-sub-agents-badge] The card's top-left chip: the generic label while
    /// the delegation starts up, the sub agent's own name once it is running.
    /// Both the built-in and a custom definition are named — the built-in's
    /// display name is a real name the user chose to see, not noise.
    private func badgeLabel(_ info: HelperBlockInfo) -> String {
        guard let n = info.agent?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty else {
            return AppLocalized("Agent")
        }
        return n == SubAgentDefinition.builtInName ? AppLocalized("General Sub Agent") : n
    }

    var body: some View {
        let info = self.info
        // [T-agent-block-height] ~25% taller than the first cut (46 → 58 pt)
        // with the extra going into line spacing, not just padding — the
        // two text lines sat too tight against each other.
        HStack(alignment: .center, spacing: 12) {
            leadingIcon(info)
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    // [T-sub-agents-badge] Generic "Agent" while the
                    // delegation is starting; the sub agent's own name once
                    // the child is confirmed running. Named too early the
                    // badge would appear and then change, which reads as a
                    // glitch — HelperRunner sets the name at `markRunning`.
                    Text(badgeLabel(info))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(HelperAccent.color)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(HelperAccent.color.opacity(0.15))
                        .clipShape(Capsule())
                    Text(info.title)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(ChatColors.primaryText)
                        .lineLimit(1)
                }
                secondLine(info)
            }
            Spacer(minLength: 4)
            if info.isRunning, let childId = block.helperChildSessionId {
                // [T-helper-block-stop-button] Classic stop-recording look:
                // red disc with a white rounded square cut out of it.
                Button {
                    // [T-sub-agents-stop-silent] Go through the registry, not
                    // straight to the child's `cancel()`. Stopping the child
                    // alone leaves the job running, so the backstop observer
                    // (`sessionLoopDidEnd`) finishes it — with `then` intact,
                    // which posts a "cancelled" callback into the parent. The
                    // parent then reads a failed sub-task and re-delegates it,
                    // which is exactly the restart the user pressed Stop to
                    // prevent. Cancelling through the registry stops the child
                    // AND drops that callback in one step.
                    // [T-stop-sibling-subagent] Cancel the whole sibling set,
                    // not just this child. A turn that fanned out into several
                    // agents keeps running if only one is stopped: the siblings
                    // report in, the parent reads a completed sub-task and
                    // delegates a fresh one — the user pressed Stop and watched
                    // a new agent start (real case 2026-09-07 19:59:08).
                    let reason = "user stopped this sub agent"
                    let registry = AgentJobRegistry.shared
                    // Mute the parent turn BEFORE cancelling: a sibling that is
                    // already finishing delivers its result through the loop or
                    // the job callback, and both check this flag.
                    if let parent = registry.parentSession(ofChild: childId),
                       let parentVM = ViewModelCache.shared.get(for: parent) {
                        parentVM.muteDelegationResults(reason: reason)
                    }
                    let stopped = registry.cancelSiblings(ofChild: childId, reason: reason)
                    // No job behind it (the registry is in-memory, so a run
                    // from a previous process has none): stop the loop itself,
                    // which is all there is left to stop.
                    if stopped == 0 { ViewModelCache.shared.get(for: childId)?.cancel() }
                } label: {
                    ZStack {
                        Circle().fill(Color.red).frame(width: 24, height: 24)
                        RoundedRectangle(cornerRadius: 2).fill(Color.white).frame(width: 9, height: 9)
                    }
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(AppLocalized("Stop agent"))
            } else if isInterrupted(info) {
                // [T-sub-agents-resume] Same slot as Stop, and its counterpart:
                // this is the block's primary control, so what it offers tracks
                // what the run needs. Once resumed the block turns .running and
                // Stop takes the slot back on its own.
                Button { resume() } label: {
                    ZStack {
                        Circle().fill(HelperAccent.color.opacity(resuming ? 0.25 : 1))
                            .frame(width: 24, height: 24)
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(resuming)
                .accessibilityLabel(AppLocalized("Resume sub agent"))
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(ChatColors.tertiaryText)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(HelperAccent.color.opacity(0.07))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(HelperAccent.color.opacity(0.35), lineWidth: 0.8)
        )
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .onTapGesture { detailBlock = block }
        .onAppear { pulse = true }
        .accessibilityIdentifier("assistantDelegateBlock")
    }

    @ViewBuilder
    private func leadingIcon(_ info: HelperBlockInfo) -> some View {
        ZStack {
            Circle().fill(HelperAccent.color.opacity(0.15)).frame(width: 34, height: 34)
            if info.isRunning {
                ReattachingSpinner(size: 15, lineWidth: 2, color: HelperAccent.color)
            } else if case .finished(let status, _, _, _, _, _, _, _) = info.phase {
                // Queued is not a failure — it is waiting its turn, and an
                // exclamation mark read as something having gone wrong. Pause
                // matches the accent colour `statusColor` already gives it.
                Image(systemName: Self.finishedGlyph(status))
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(HelperBlockInfo.statusColor(status))
            } else {
                Image(systemName: HelperAccent.glyph).font(.system(size: 13)).foregroundStyle(HelperAccent.color)
            }
        }
    }

    /// Glyph for a block that is no longer running: done, waiting, or wrong.
    private static func finishedGlyph(_ status: String) -> String {
        switch status {
        case "completed": return "checkmark"
        // [T-subagent-no-deliverable-status] Spelled out rather than left to
        // `default`: this status must never drift back to a checkmark, and the
        // whole point of the fix is that the glyph disagrees with "done".
        case "no_deliverable": return "exclamationmark"
        case "queued": return "pause.fill"
        // Not paused — it will never resume on its own.
        case "queue_lost": return "exclamationmark"
        default: return "exclamationmark"
        }
    }

    @ViewBuilder
    private func secondLine(_ info: HelperBlockInfo) -> some View {
        switch info.phase {
        case .starting:
            Text(AppLocalized("starting")).font(.system(size: 11)).foregroundStyle(ChatColors.secondaryText)
        case .running(let tool, let activity, let clock):
            HStack(spacing: 6) {
                if let tool {
                    HStack(spacing: 3) {
                        Image(systemName: HelperAccent.symbol(forTool: tool)).font(.system(size: 10, weight: .semibold))
                        Text(HelperAccent.displayName(forTool: tool)).font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundStyle(HelperAccent.color)
                    .lineLimit(1)
                    .fixedSize()
                }
                // [T-agent-inline-activity-first] No model name on this line
                // while the agent runs: what it is DOING is the useful part,
                // and the model text was squeezing it to an ellipsis on a
                // 3-agent screen ("Browser use · Cl…t 5 · 读取 iPho… · 0:49").
                // The model stays one tap away on the detail card, and the
                // finished row below still shows it — there the activity is
                // over and the space is free.
                Text(activity.isEmpty ? AppLocalized("working…") : activity)
                    .font(.system(size: 11)).foregroundStyle(ChatColors.secondaryText).lineLimit(1)
                if !clock.isEmpty {
                    Text(clock).font(.system(size: 11, design: .monospaced)).foregroundStyle(ChatColors.tertiaryText)
                        .fixedSize()
                }
            }
        case .finished(let status, let tier, _, let elapsed, _, let escalation, let background, _):
            // [T-helper-block-height] One line, like .starting and .running:
            // the block must not grow when the run finishes (the result
            // summary lives in the detail card / transcript, not here).
            // [T-agent-model-identity] Status, tier and elapsed are fixed;
            // the model text is the one flexible member and truncates in
            // the middle, so "Done · primary · … · 8s" always fits one line.
            HStack(spacing: 6) {
                Text(info.statusText)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(HelperBlockInfo.statusColor(status))
                    .fixedSize()
                // [T-sub-agents-badge] No agent chip here: the badge above
                // already names the agent for the whole card, and repeating it
                // after "Done" only crowded out the model name — which is the
                // one thing this line exists to show.
                if let tier = HelperBlockInfo.tierLabel(info.model, fallback: tier) {
                    Text(tier)
                        .font(.system(size: 9, weight: .semibold, design: .monospaced))
                        .padding(.horizontal, 4).padding(.vertical, 1)
                        .background(ChatColors.secondaryBg)
                        .clipShape(Capsule())
                        .fixedSize()
                }
                if let model = info.model, let line = model.compactLine() {
                    Text(line)
                        .font(.system(size: 11))
                        .foregroundStyle(model.hasEffective ? ChatColors.secondaryText : ChatColors.tertiaryText)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .layoutPriority(-1)
                }
                if let elapsed {
                    Text(HelperBlockInfo.elapsedLabel(elapsed))
                        .font(.system(size: 11, design: .monospaced)).foregroundStyle(ChatColors.tertiaryText)
                        .fixedSize()
                }
                if escalation {
                    Image(systemName: "arrow.up.circle").font(.system(size: 11)).foregroundStyle(.orange)
                }
                if background {
                    Image(systemName: "arrow.turn.down.left").font(.system(size: 10)).foregroundStyle(ChatColors.tertiaryText)
                }
            }
        }
    }
}

// MARK: - Floating bar thumbnail (100×65)

struct HelperThumbnailView: View {
    @ObservedObject var block: AssistantBlock

    var body: some View {
        let info = HelperBlockInfo.parse(block)
        // [T-agent-model-identity] One extra 6 pt line — "tier · model" —
        // in every phase; the title gives up its second line to make room,
        // so the 100×65 frame never clips.
        let modelLine: String? = {
            var parts: [String] = []
            if let t = HelperBlockInfo.tierLabel(info.model, fallback: { if case .finished(_, let tier, _, _, _, _, _, _) = info.phase { return tier }; return nil }()) {
                parts.append(t)
            }
            // A long tier ("sub → primary") leaves less room: shorten the
            // model itself rather than let the whole line truncate mid-tier.
            let tierLen = parts.first?.count ?? 0
            if let m = info.model?.thumbnailLine(max: tierLen > 8 ? 11 : 16) { parts.append(m) }
            return parts.isEmpty ? nil : parts.joined(separator: " · ")
        }()
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 3) {
                Image(systemName: HelperAccent.glyph).font(.system(size: 7, weight: .bold))
                Text(AppLocalized("Agent")).font(.system(size: 6, weight: .bold))
            }
            .foregroundStyle(HelperAccent.color)
            Text(info.title).font(.system(size: 7, weight: .semibold)).foregroundStyle(.white)
                .lineLimit(modelLine == nil ? 2 : 1)
            Spacer(minLength: 0)
            switch info.phase {
            case .starting:
                Text(AppLocalized("starting")).font(.system(size: 6)).foregroundStyle(.white.opacity(0.7))
            case .running(let tool, let activity, let clock):
                if let tool {
                    HStack(spacing: 2) {
                        Image(systemName: HelperAccent.symbol(forTool: tool)).font(.system(size: 6, weight: .bold))
                        Text(HelperAccent.displayName(forTool: tool)).font(.system(size: 6, weight: .semibold))
                    }
                    .foregroundStyle(HelperAccent.color)
                    .lineLimit(1)
                }
                Text([activity, clock].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.system(size: 6)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
            case .finished(let status, _, _, let elapsed, _, _, _, _):
                Text([info.statusText, elapsed.map(HelperBlockInfo.elapsedLabel) ?? ""]
                        .filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.system(size: 6, weight: .semibold)).foregroundStyle(HelperBlockInfo.statusColor(status))
                    .lineLimit(1)
            }
            if let modelLine {
                // [T-subagent-ui-honesty] A pinned group that could not be
                // routed gets a glyph here: at 6 pt there is no room for words,
                // and the model name alone made an ignored pin look like a
                // working one. The detail sheet carries the sentence.
                HStack(spacing: 2) {
                    if info.model?.modelGroupUnavailable == true {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 6, weight: .bold))
                            .foregroundStyle(.yellow.opacity(0.9))
                    }
                    Text(modelLine)
                        .font(.system(size: 6, design: .monospaced))
                        .foregroundStyle(info.model?.hasEffective == true ? .white.opacity(0.85) : .white.opacity(0.6))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(
                    info.model?.modelGroupUnavailable == true
                        ? "\(modelLine) — " + AppLocalized("Pinned group unavailable — ran on the conversation's model")
                        : modelLine
                )
            }
        }
        .padding(5)
        .frame(width: 100, height: 65, alignment: .topLeading)
        .background(Color(white: 0.12))
    }
}

// MARK: - Tool live sheet content

// [T-agent-detail-card-container] Content of the tool sheet for an agent
// block — the SAME frame every other tool result gets (ToolLiveSheet nav bar
// + bottom bar) and the same card language inside: a title bar with its
// own background, a divider, and rounded, stroked content cards, exactly
// like fileEditorContent / browserContent. Cards, top to bottom:
//   1. header card — icon, task title, status · tier · elapsed in the bar;
//      a few key/value lines (model, tools, turns, tokens) in the body;
//   2. current-tool card (running only) — the child's executing tool with
//      its icon, and what it is doing;
//   3. latest-screenshot card — the child's most recent browser/image
//      snapshot, when its session holds one;
//   4. result card — the deliverable (or last message) as Markdown;
//   (the live conversation is reached from the sheet's top-right chat-bubble button).
// Used both for a delegate_task block reached inside ToolLiveSheet and for
// a tapped agent callback cell (synthetic block, see AgentCallbackDetailTarget).
struct HelperDetailCard: View {
    @ObservedObject var block: AssistantBlock
    @State private var latestImagePath: String?
    private let refresh = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    private var childId: String? {
        block.helperChildSessionId
            ?? (AIChatViewModel.parseDelegateResult(block.content)?["child_session_id"] as? String)
    }

    var body: some View {
        let info = HelperBlockInfo.parse(block)
        let result = Self.resultText(block)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                headerCard(info)

                if case .running(let tool, let activity, _) = info.phase, tool != nil || !activity.isEmpty {
                    AgentDetailCard(title: AppLocalized("Current tool"),
                                    icon: tool.map(HelperAccent.symbol(forTool:)) ?? "wrench.and.screwdriver") {
                        VStack(alignment: .leading, spacing: 6) {
                            if let tool {
                                HStack(spacing: 6) {
                                    Image(systemName: HelperAccent.symbol(forTool: tool)).font(.system(size: 13, weight: .semibold))
                                    Text(HelperAccent.displayName(forTool: tool)).font(.system(size: 14, weight: .semibold))
                                }
                                .foregroundStyle(HelperAccent.color)
                            }
                            if !activity.isEmpty {
                                Text(activity).font(.system(size: 13)).foregroundStyle(Color(UIColor.label))
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }

                if let path = latestImagePath, let img = UIImage(contentsOfFile: path) {
                    AgentDetailCard(title: AppLocalized("Latest screenshot"), icon: "photo") {
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(UIColor.separator).opacity(0.5), lineWidth: 0.5))
                    }
                }

                // [T-subagent-error-surface] The full error, for a run that
                // failed. The card upstairs has room only for the short kind;
                // this is where the actual message lives, which is what a
                // "why did it fail" question needs.
                if let detail = info.errorDetail, !detail.isEmpty {
                    AgentDetailCard(title: AppLocalized("Error"),
                                    icon: "exclamationmark.octagon") {
                        Text(detail)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color(UIColor.label))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                // [T-subagent-control-capsule] What the parent sent. Only
                // meaningful for a control call — a delegation's task text is
                // already the card's title.
                if let payload = HelperBlockInfo.controlPayload(block) {
                    AgentDetailCard(title: AppLocalized("Sent to the sub agent"),
                                    icon: "arrow.up.message") {
                        SelectableMarkdownView(markdown: payload)
                    }
                }

                let isFinished: Bool = { if case .finished = info.phase { return true }; return false }()
                let resultTitle = isFinished ? AppLocalized("Result") : AppLocalized("Last message")
                // [T-subagent-no-deliverable-status] A sealed checkmark on the
                // Result card asserts "this is the delivered work". When the
                // card's own body says no deliverable was returned, that glyph
                // contradicts the text directly under it — the same mismatch
                // the status badge had. Use the warning glyph in that case.
                let resultIcon: String = {
                    guard isFinished else { return "text.bubble" }
                    return (result ?? "").isEmpty ? "exclamationmark.triangle" : "checkmark.seal"
                }()
                if isFinished || !(result ?? "").isEmpty {
                    AgentDetailCard(title: resultTitle, icon: resultIcon) {
                        if let result, !result.isEmpty {
                            SelectableMarkdownView(markdown: result)
                        } else {
                            Text(AppLocalized("No deliverable was returned"))
                                .font(.system(size: 13))
                                .foregroundStyle(Color(UIColor.secondaryLabel))
                        }
                    }
                }

                // [T-agent-detail-buttons] The live conversation is reached
                // from the sheet's top-right chat-bubble button; no second
                // entry at the bottom.
            }
            .padding(.horizontal, 12)
            .padding(.top, 12)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task(id: childId) { await loadLatestImage() }
        .onReceive(refresh) { _ in
            if info.isRunning { Task { await loadLatestImage() } }
        }
    }

    // MARK: Header card

    @ViewBuilder
    private func headerCard(_ info: HelperBlockInfo) -> some View {
        let rows = metaRows(info)
        AgentDetailCard(title: info.title, icon: HelperAccent.icon, iconTint: HelperAccent.color,
                        trailing: { statusTrailing(info) },
                        bodyPadding: rows.isEmpty ? 0 : 12) {
            if !rows.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
                        HStack(alignment: .top, spacing: 8) {
                            Text(r.0).font(.system(size: 12)).foregroundStyle(Color(UIColor.secondaryLabel))
                                .lineLimit(1).minimumScaleFactor(0.8)
                                .frame(width: 84, alignment: .leading)
                            Text(r.1).font(.system(size: 12, design: r.mono ? .monospaced : .default))
                                .foregroundStyle(Color(UIColor.label))
                                .textSelection(.enabled)
                            // [T-subagent-thinking-badge] Same pill the main
                            // conversation's nav bar puts beside its model, so
                            // "what reasoning is this running at" reads the same
                            // way in both places. Not tappable here: the level
                            // was decided when the run started and changing it
                            // now would not affect the run.
                            if let level = r.badge {
                                HStack(spacing: 2) {
                                    Image("ThinkingIcon")
                                        .resizable()
                                        .frame(width: 6, height: 6)
                                        .opacity(level.isEnabled ? 1.0 : 0.4)
                                    Text(level.displayName)
                                        .font(.system(size: 8, weight: .medium))
                                }
                                .foregroundStyle(ChatColors.secondaryText)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1)
                                .background(Capsule().fill(Color.secondary.opacity(0.10)))
                            }
                        }
                    }
                }
            }
        }
    }

    /// Status · tier · elapsed, right-aligned in the title bar.
    @ViewBuilder
    private func statusTrailing(_ info: HelperBlockInfo) -> some View {
        HStack(spacing: 6) {
            switch info.phase {
            case .starting:
                Text(AppLocalized("starting")).font(.system(size: 11, weight: .semibold)).foregroundStyle(HelperAccent.color)
            case .running(_, _, let clock):
                ReattachingSpinner(size: 11, lineWidth: 1.6, color: HelperAccent.color)
                Text(AppLocalized("running")).font(.system(size: 11, weight: .semibold)).foregroundStyle(HelperAccent.color)
                if !clock.isEmpty {
                    Text(clock).font(.system(size: 11, design: .monospaced)).foregroundStyle(Color(UIColor.tertiaryLabel))
                }
            case .finished(let status, let tier, _, let elapsed, _, _, _, _):
                Text(info.statusText)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(HelperBlockInfo.statusColor(status))
                if let tier = HelperBlockInfo.tierLabel(info.model, fallback: tier) {
                    Text(tier)
                        .font(.system(size: 9, weight: .semibold, design: .monospaced))
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(HelperAccent.color.opacity(0.15))
                        .foregroundStyle(HelperAccent.color)
                        .clipShape(Capsule())
                }
                if let elapsed {
                    Text(HelperBlockInfo.elapsedLabel(elapsed))
                        .font(.system(size: 11, design: .monospaced)).foregroundStyle(Color(UIColor.tertiaryLabel))
                }
            }
        }
    }

    /// Secondary facts, one short line each: model, tools, turns, tokens,
    /// escalation, delivery. Nothing here competes with the result.
    private func metaRows(_ info: HelperBlockInfo) -> [(String, String, mono: Bool, badge: ThinkingLevel?)] {
        var rows: [(String, String, mono: Bool, badge: ThinkingLevel?)] = []
        // [T-subagent-callback-visible] Lead with WHAT this was and WHICH run
        // it belongs to. A transcript can hold several reports from several
        // agents; without these two the cards are indistinguishable once the
        // conversation has scrolled on.
        let payload = AIChatViewModel.parseDelegateResult(block.content)
        if let kind = payload?["callback_kind"] as? String {
            let label: String
            switch kind {
            case "progress": label = AppLocalized("Progress report")
            case "finished": label = AppLocalized("Final result")
            case "scheduled": label = AppLocalized("Scheduled run")
            default: label = kind
            }
            rows.append((AppLocalized("Interaction"), label, mono: false, badge: nil))
        }
        if let job = payload?["job_id"] as? String, !job.isEmpty {
            rows.append((AppLocalized("Job"), String(job.prefix(8)), mono: true, badge: nil))
        }
        // [T-subagent-thinking-badge] The level this run actually used rides
        // the model row as a pill rather than taking a row of its own: it
        // qualifies the model, the way the main conversation's nav bar shows
        // it beside the model name.
        let ranAt: ThinkingLevel? = (payload?["thinking_level"] as? String)
            .flatMap { ThinkingLevel(rawValue: $0) }
            .flatMap { $0.isEnabled ? $0 : nil }
        rows.append(contentsOf: Self.modelRows(info, thinking: ranAt))
        if case .finished(_, _, _, _, _, let escalation, let background, _) = info.phase {
            if escalation { rows.append((AppLocalized("Escalation"), AppLocalized("The agent asked for a stronger model"), mono: false, badge: nil)) }
            if background { rows.append((AppLocalized("Delivery"), AppLocalized("Result posted as a new message"), mono: false, badge: nil)) }
        }
        if let summary = Self.runSummary(block) {
            for part in summary.components(separatedBy: " · ") {
                let t = part.trimmingCharacters(in: .whitespaces)
                if t.hasPrefix("tools ") { rows.append((AppLocalized("Tools"), String(t.dropFirst(6)), mono: true, badge: nil)) }
                else if t.hasPrefix("turns ") { rows.append((AppLocalized("Turns"), String(t.dropFirst(6)), mono: true, badge: nil)) }
                else if t.hasPrefix("tokens ") { rows.append((AppLocalized("Tokens"), String(t.dropFirst(7)), mono: true, badge: nil)) }
                else if !t.isEmpty { rows.append((AppLocalized("Summary"), t, mono: false, badge: nil)) }
            }
        }
        return rows
    }

    /// [T-agent-model-identity] The three identity facts, one row each, in
    /// full — this is the only surface with room to spell them out:
    ///   Model tier      primary            /  sub → primary
    ///   Resolved model  Anthropic (53) · Claude Sonnet 5 (claude-sonnet-5)
    ///   Effective model claude-sonnet-5 · same as resolved · from response
    ///   Fallback        Anthropic (53) · Claude Sonnet 5 → OpenRouter · Sonnet 5
    /// A pre-identity payload (only `model_used`) still shows its one line.
    static func modelRows(_ info: HelperBlockInfo,
                          thinking: ThinkingLevel? = nil) -> [(String, String, mono: Bool, badge: ThinkingLevel?)] {
        var rows: [(String, String, mono: Bool, badge: ThinkingLevel?)] = []
        guard let model = info.model else {
            if case .finished(_, let tier, let used, _, _, _, _, _) = info.phase {
                if let tier { rows.append((AppLocalized("Model tier"), tier, mono: true, badge: nil)) }
                if let used, !used.isEmpty { rows.append((AppLocalized("Model"), used, mono: false, badge: nil)) }
            }
            return rows
        }
        if let tier = HelperBlockInfo.tierLabel(model, fallback: nil) {
            rows.append((AppLocalized("Model tier"), tier, mono: true, badge: nil))
        }
        // [T-subagent-model-strategy] Two lines, in the order a person asks
        // the question: WHAT WAS IT TOLD TO USE, then WHAT DID IT ACTUALLY RUN
        // ON. This replaces "Resolved model" / "Effective model", which named
        // two stages of our own resolution pipeline and asked the reader to
        // compare them — including a "same as resolved" note that only meant
        // "nothing went wrong", and a from-response/from-request provenance
        // tag that is a detail of how we learned the model, not something the
        // reader has any use for.
        if let strategy = model.strategyLabel {
            rows.append((AppLocalized("Model strategy"), strategy, mono: false, badge: nil))
        }
        if let actual = model.actualModelLabel {
            rows.append((AppLocalized("Actual model"), actual, mono: false, badge: thinking))
        } else {
            // Nothing has served a turn yet, and no strategy entry to name.
            rows.append((AppLocalized("Actual model"),
                         info.isRunning ? AppLocalized("Starting…") : AppLocalized("Not reported"),
                         mono: false, badge: thinking))
        }
        // The exceptions stay visible — these are the cases where the model
        // that ran is NOT the one the strategy picked, which is exactly when a
        // reader needs to be told rather than left to compare two ids.
        if model.entryFellBack, let to = model.effectiveLabel {
            rows.append((AppLocalized("Switched"),
                         String(format: AppLocalized("Fell back to %@"), to), mono: false, badge: nil))
        }
        // [T-subagent-ui-honesty] The pin did not apply. Stated as its own row
        // rather than folded into "Resolved model", because the resolved model
        // IS the parent's — the surprising part is not which model ran but that
        // the user's choice was silently dropped.
        if model.modelGroupUnavailable {
            rows.append((AppLocalized("Model group"),
                         AppLocalized("Pinned group unavailable — ran on the conversation's model"),
                         mono: false, badge: nil))
        }
        return rows
    }

    // MARK: Latest screenshot

    /// The child's most recent browser / image snapshot, if its session
    /// holds one. Loads the child VM the way the transcript page does, so it
    /// works after a restart too.
    @MainActor
    private func loadLatestImage() async {
        guard let childId else { latestImagePath = nil; return }
        // [T-vmcache-pools] Still the child pool: opening a child to read a
        // thumbnail must not move it into the user's own budget.
        let (vm, fresh) = ViewModelCache.shared.getOrCreate(for: childId, kind: .child)
        if fresh || vm.messages.isEmpty { await vm.loadSession() }
        for msg in vm.messages.reversed() where msg.role == .assistant {
            for b in msg.blocks.reversed() {
                if let path = b.imageFilePath, !path.isEmpty, FileManager.default.fileExists(atPath: path) {
                    latestImagePath = path
                    return
                }
            }
        }
        latestImagePath = nil
    }

    // MARK: Payload helpers

    /// The agent's final text (or, for a progress payload, its last message).
    static func resultText(_ block: AssistantBlock) -> String? {
        guard let obj = AIChatViewModel.parseDelegateResult(block.content) else {
            let c = block.content.trimmingCharacters(in: .whitespacesAndNewlines)
            return c.hasPrefix("◐") ? nil : c
        }
        return (obj["result"] as? String) ?? (obj["last_message"] as? String) ?? (obj["detail"] as? String)
    }

    /// "Summary: …" run line with its label stripped, when the payload has one.
    static func runSummary(_ block: AssistantBlock) -> String? {
        guard let s = AIChatViewModel.parseDelegateResult(block.content)?["summary"] as? String,
              !s.isEmpty else { return nil }
        return s.hasPrefix("Summary: ") ? String(s.dropFirst(9)) : s
    }
}

/// The card container shared by the agent detail: title bar (icon + title +
/// trailing) on its own background, divider, body — same colours, radius and
/// stroke as ToolLiveSheet.fileEditorContent.
struct AgentDetailCard<Trailing: View, Content: View>: View {
    let title: String
    let icon: String
    var iconTint: Color = Color(UIColor.secondaryLabel)
    var trailing: () -> Trailing
    var bodyPadding: CGFloat = 12
    @ViewBuilder var content: () -> Content

    init(title: String, icon: String, iconTint: Color = Color(UIColor.secondaryLabel),
         @ViewBuilder trailing: @escaping () -> Trailing,
         bodyPadding: CGFloat = 12,
         @ViewBuilder content: @escaping () -> Content) {
        self.title = title; self.icon = icon; self.iconTint = iconTint
        self.trailing = trailing; self.bodyPadding = bodyPadding; self.content = content
    }

    private static var barBg: Color { Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.92, alpha: 1) }) }
    private static var cardBg: Color { Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.10, alpha: 1) : UIColor(white: 0.94, alpha: 1) }) }
    private static var stroke: Color { Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(white: 0.25, alpha: 1) : UIColor(white: 0.82, alpha: 1) }) }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 12))
                    .foregroundStyle(iconTint)
                Text(title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color(UIColor.label))
                    .lineLimit(1)
                Spacer(minLength: 6)
                trailing()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Self.barBg)

            if bodyPadding > 0 {
                Divider()
                content()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(bodyPadding)
            }
        }
        .background(Self.cardBg)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Self.stroke, lineWidth: 0.5))
    }
}

extension AgentDetailCard where Trailing == EmptyView {
    init(title: String, icon: String, iconTint: Color = Color(UIColor.secondaryLabel),
         bodyPadding: CGFloat = 12,
         @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, icon: icon, iconTint: iconTint, trailing: { EmptyView() },
                  bodyPadding: bodyPadding, content: content)
    }
}

struct ReattachingSpinner: View {
    var size: CGFloat = 14
    var lineWidth: CGFloat = 2
    var color: Color = .accentColor

    var body: some View {
        TimelineView(.animation) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            let angle = t.truncatingRemainder(dividingBy: 1.0) * 360
            Circle()
                .trim(from: 0, to: 0.72)
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(angle))
                .frame(width: size, height: size)
        }
        .accessibilityLabel(AppLocalized("running"))
    }
}
