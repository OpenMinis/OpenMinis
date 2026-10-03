import SwiftUI

// [T-p3-agent-callback-cell] Rendering for `<agent_callback>` messages (see
// AgentCallback). The message is a user message as far as the model is
// concerned, but nobody typed it: it is the agent reporting back. So it is
// drawn as a compact, left-aligned callback card — same family as the agent
// block the delegate_task tool shows — rather than as a user bubble. Tapping
// it opens the regular tool sheet (ToolLiveSheet + HelperDetailCard) with a way into the
// child session's transcript.

extension Notification.Name {
    /// Posted by AgentCallbackCellView on tap. `userInfo["callback"]` is the
    /// parsed `AgentCallback`; `userInfo["sessionId"]` the parent session it
    /// belongs to, so only that conversation's host presents the sheet.
    static let openAgentCallback = Notification.Name("openAgentCallback")
}

struct AgentCallbackDetailTarget: Identifiable {
    let id = UUID()
    let callback: AgentCallback
    /// [T-agent-result-display] The callback re-expressed as an agent tool
    /// block so the tap opens the SAME ToolLiveSheet frame every other tool
    /// result uses (HelperDetailCard inside), instead of a bespoke list.
    let block: AssistantBlock

    init(callback: AgentCallback) {
        self.callback = callback
        self.block = callback.syntheticBlock()
    }

    /// A real delegate_task block from the transcript (debug RPC path).
    init(block: AssistantBlock) {
        self.callback = AgentCallback(kind: .finished, jobId: "", childSessionId: block.helperChildSessionId,
                                      title: block.toolSummary ?? "", status: "done", tier: nil, elapsed: nil,
                                      tool: nil, activity: nil, turn: nil, summary: nil, body: "")
        self.block = block
    }
}

extension AgentCallback {
    /// Elapsed "1m02s" / "45s" → seconds.
    var elapsedSeconds: Int? {
        guard let e = elapsed else { return nil }
        if let m = e.range(of: "m") {
            let mins = Int(e[e.startIndex..<m.lowerBound]) ?? 0
            let secs = Int(e[m.upperBound...].replacingOccurrences(of: "s", with: "")) ?? 0
            return mins * 60 + secs
        }
        return Int(e.replacingOccurrences(of: "s", with: ""))
    }

    /// A delegate_task-shaped tool block carrying this callback's payload, in
    /// the JSON dialect HelperBlockInfo / HelperDetailCard already parse.
    func syntheticBlock() -> AssistantBlock {
        // [T-subagent-no-deliverable-status] A finished callback whose body is
        // empty carries no deliverable, exactly like the delegate_task payload
        // case. Reported through the same status so this cell, the tool block
        // and the detail sheet cannot disagree — the envelope is just a second
        // route to the same three renderers.
        let finishedStatus: String = {
            guard status == "done" else { return status }
            return AIChatViewModel.resolvedStatus(
                "completed", result: body.trimmingCharacters(in: .whitespacesAndNewlines))
        }()
        var obj: [String: Any] = [
            "status": kind == .progress ? "running" : finishedStatus,
            "job_id": jobId,
            // [T-subagent-callback-visible] Which interaction produced this
            // card. The detail sheet names it, so a reader can tell a mid-run
            // progress report from the final hand-back or a scheduled firing
            // — the whole point of making these visible.
            "callback_kind": kind.rawValue,
        ]
        if let childSessionId { obj["child_session_id"] = childSessionId }
        if let tier { obj["tier_used"] = tier }
        // [T-agent-model-identity] The same identity keys the delegate_task
        // payload carries, so HelperDetailCard shows one consistent card.
        if let modelIdentity { obj.merge(modelIdentity.payload()) { _, new in new } }
        if let s = elapsedSeconds { obj["elapsed_s"] = s }
        if let summary, !summary.isEmpty { obj["summary"] = summary }
        if kind == .progress {
            obj["last_message"] = body
            if let tool, !tool.isEmpty { obj["tool"] = tool }
            var activity: [String] = []
            if let tool, !tool.isEmpty { activity.append(tool) }
            if let a = self.activity, !a.isEmpty { activity.append(a) }
            if let turn, turn > 0 { activity.append(AppLocalized("Turn") + " \(turn)") }
            obj["activity"] = activity.joined(separator: " · ")
        } else {
            obj["result"] = body
            obj["delivered_as"] = "new turn in this conversation"
        }
        let json = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let toolStatus: ToolBlockStatus = {
            if kind == .progress { return .running }
            // [T-subagent-no-deliverable-status] Keyed off the RESOLVED status,
            // so a `done` callback with an empty body no longer paints the
            // block's own success chrome either.
            switch finishedStatus {
            case "completed": return .success
            case "cancelled": return .cancelled
            default: return .failed(message: AgentCallback.localizedStatus(finishedStatus))
            }
        }()
        let block = AssistantBlock(kind: .delegateTool(title: title), content: json, toolStatus: toolStatus)
        block.toolSummary = title
        block.helperChildSessionId = childSessionId
        if let s = elapsedSeconds { block.toolDuration = TimeInterval(s) }
        return block
    }
}

struct AgentCallbackCellView: View {
    /// Fixed card height so CollectionViewMessageListV3 can precalc the row
    /// without a TextKit measurement (rowHeight = card + 2 × 4pt padding).
    static let cardHeight: CGFloat = 56
    static let rowHeight: CGFloat = cardHeight + 8

    /// [T-agent-callback-card-width] The card's horizontal inset, applied by the
    /// CALLER so it lands OUTSIDE the message list's content-width cap.
    ///
    /// In the regular size class `maxContentWidth` caps a row at 900pt
    /// (AIChatView). The assistant cell pads after that cap
    /// (CollectionViewMessageListV3 :346), so a delegate-tool card fills the
    /// full 900. This card used to pad itself, inside the cap, so it measured
    /// 868 — visibly narrower than the delegate card it reports back to, for no
    /// reason a reader could see. Compact (portrait iPhone) has no cap, so both
    /// forms were identical there and the gap only showed on iPad/landscape.
    static let horizontalInset: CGFloat = 16

    let callback: AgentCallback
    let sessionId: String?

    private var accent: Color {
        // [T-scheduled-task-card] Amber for the scheduler, whatever its status:
        // the colour distinguishes WHO injected the turn, not how it went. An
        // agent callback keeps its status-driven palette (blue running, green
        // done, yellow cancelled/timeout, red otherwise), so the two kinds
        // never collide — a scheduled firing carries `status: "running"`, which
        // would otherwise have painted it the same blue as a running agent.
        if callback.kind == .scheduled { return .orange }
        switch callback.status {
        case "running": return HelperAccent.color
        case "done": return .green
        case "cancelled", "timeout": return .yellow
        default: return .red
        }
    }

    private var icon: String {
        switch callback.kind {
        case .progress: return "arrow.triangle.2.circlepath"
        // [T-scheduled-task-card] Calendar+clock reads as "this fired on a
        // schedule", clearly apart from the agent glyphs above and below.
        case .scheduled: return "calendar.badge.clock"
        case .finished:
            switch callback.status {
            case "done": return "checkmark.circle.fill"
            case "cancelled": return "xmark.circle.fill"
            case "timeout": return "clock.badge.exclamationmark"
            default: return "exclamationmark.triangle.fill"
            }
        }
    }

    /// [T-scheduled-cancel-from-card] Whether this card's job is still a live
    /// timer worth offering to cancel.
    ///
    /// Asks the REGISTRY rather than trusting the envelope: the envelope is a
    /// snapshot of one firing and is persisted, so a card re-read from history
    /// would keep claiming the task is live long after it ended. The registry
    /// is the live truth, and after a relaunch it is empty — which is the
    /// honest answer, since the timer is genuinely gone then too.
    private var isCancellable: Bool {
        AgentJobRegistry.shared.isLiveScheduled(jobId: callback.jobId)
    }

    /// [T-scheduled-next-fire] Parse the envelope's `next_fire_at`, ignoring a
    /// value already in the past — a card rendered from history would otherwise
    /// promise a firing that has since happened or been cancelled.
    static func nextFireDate(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty,
              let date = ISO8601DateFormatter().date(from: iso),
              date > Date() else { return nil }
        return date
    }

    /// "in 4 min" / "in 2 hr", localized by the system formatter. Reused rather
    /// than hand-rolled — `RelativeDateTimeFormatter` already covers every
    /// locale this app ships (ListSessionsIntent uses it the same way).
    static func relativeLabel(_ date: Date) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f.localizedString(for: date, relativeTo: Date())
    }

    private var headline: String {
        let kindLabel: String = switch callback.kind {
        case .finished: AppLocalized("Agent result")
        case .progress: AppLocalized("Agent progress")
        case .scheduled: AppLocalized("Scheduled task")
        }
        return callback.title.isEmpty ? kindLabel : "\(kindLabel) · \(callback.title)"
    }

    private var subline: String {
        // [T-scheduled-task-card] A firing has no status/elapsed worth showing
        // — it just happened. What matters is which run this is and how many
        // remain, which is also what the model is told to steer by.
        if callback.kind == .scheduled {
            var parts: [String] = []
            if let fire = callback.fireIndex, fire > 0 {
                parts.append(String(format: AppLocalized("Run %d"), fire))
            }
            if let remaining = callback.remaining {
                parts.append(String(format: AppLocalized("%d left"), remaining))
            }
            // [T-scheduled-next-fire] Say whether the task is still planned.
            // Without this the card reads as a completed event, and a user has
            // no way to tell a recurring task from a one-shot that is over —
            // the "别让用户以为这条卡片出现后任务就结束了" point.
            if let next = Self.nextFireDate(callback.nextFireAt) {
                parts.append(String(format: AppLocalized("Next %@"), Self.relativeLabel(next)))
            } else {
                parts.append(AppLocalized("Task complete"))
            }
            if let t = callback.triggerLabel, !t.isEmpty { parts.append(t) }
            // A one-shot / on-completion job has neither count; fall back to
            // the first line of the prompt so the card is never just a title.
            if parts.isEmpty {
                let firstLine = callback.body
                    .split(whereSeparator: \.isNewline)
                    .first.map(String.init) ?? ""
                if !firstLine.isEmpty { parts.append(firstLine) }
            }
            return parts.joined(separator: " · ")
        }
        var parts: [String] = [AgentCallback.localizedStatus(callback.status)]
        if let e = callback.elapsed, !e.isEmpty { parts.append(e) }
        if callback.kind == .progress {
            if let t = callback.tool, !t.isEmpty { parts.append(t) }
            if let turn = callback.turn, turn > 0 { parts.append(AppLocalized("Turn") + " \(turn)") }
        } else if let s = callback.summary, !s.isEmpty {
            parts.append(s)
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().fill(accent.opacity(0.15))
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(accent)
            }
            .frame(width: 30, height: 30)

            VStack(alignment: .leading, spacing: 2) {
                Text(headline)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(ChatColors.primaryText)
                    .lineLimit(1)
                Text(subline)
                    .font(.system(size: 12))
                    .foregroundStyle(ChatColors.secondaryText)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(ChatColors.tertiaryText)
        }
        .padding(.horizontal, 12)
        .frame(height: Self.cardHeight)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(UIColor.secondarySystemBackground)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(accent.opacity(0.25), lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture {
            var info: [String: Any] = ["callback": callback]
            if let sessionId { info["sessionId"] = sessionId }
            NotificationCenter.default.post(name: .openAgentCallback, object: nil, userInfo: info)
        }
        .contextMenu {
            // [T-scheduled-cancel-from-card] Cancelling a timer required asking
            // the model to run `minis-scheduled delete --id …`; the card the
            // task fired into had no way to stop it. Shown only while the job is
            // actually still live, so a card scrolled back to from history does
            // not offer to cancel something already finished.
            if callback.kind == .scheduled, isCancellable {
                Button(role: .destructive) {
                    AgentJobRegistry.shared.cancel(jobId: callback.jobId,
                                                   reason: "user cancelled via card",
                                                   silent: false)
                } label: {
                    Label(AppLocalized("Cancel scheduled task"), systemImage: "calendar.badge.minus")
                }
            }
            Button {
                UIPasteboard.general.string = callback.body
            } label: {
                // [T-scheduled-task-card] The body of a scheduled firing is
                // the injected prompt, not a result — say so.
                Label(callback.kind == .scheduled
                        ? AppLocalized("Copy prompt")
                        : AppLocalized("Copy result"),
                      systemImage: "doc.on.doc")
            }
        }
        // [T-agent-callback-card-width] Horizontal inset deliberately NOT applied
        // here — see `AgentCallbackCellView.horizontalInset` and the call site in
        // ChatMessageViews. Applying it inside the 900pt content cap made the
        // card 868pt while the delegate-tool card beside it, whose cell pads
        // outside the cap, measured the full 900.
        .padding(.vertical, 4)
        .accessibilityIdentifier("agentCallback")
    }
}
