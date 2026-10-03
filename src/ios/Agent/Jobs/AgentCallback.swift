import Foundation

// [T-p3-agent-callback-cell] Wire format for the messages an agent injects
// into its parent conversation: the completion report and the mid-run
// progress reports. They still travel as USER messages (every provider
// accepts those and the model already treats them as "something happened"),
// but the text is wrapped in one fixed XML element so that
//   - the model can tell a system-injected callback from a human message, and
//   - the UI can render it as a dedicated callback cell (see
//     AgentCallbackCellView) instead of a right-aligned user bubble.
//
// Shape (attributes on the opening tag, the payload as child elements):
//
//   <agent_callback kind="finished" job="ab12cd34" session="…" title="…"
//                   status="done" model="primary" elapsed="1m02s">
//   <summary>tools 3 (browser_use 2, read_file 1) · turns 4 · tokens in 12k / out 2k</summary>
//   <result>
//   …the agent's final answer…
//   </result>
//   </agent_callback>
//
// Progress reports use kind="progress", carry tool/activity/turn attributes
// and a <last_message> element instead of <result>.
struct AgentCallback: Equatable {
    enum Kind: String {
        case progress
        case finished
        /// [T-scheduled-task-card] A scheduled job (`minis-scheduled` loop /
        /// cron / once / on-completion) landing its prompt in a conversation.
        ///
        /// Same envelope, different origin: this is not an agent reporting
        /// back, it is the scheduler injecting a turn. It rides here rather
        /// than in a parallel type because everything downstream — the prefix
        /// test, the attribute parser, the synthetic tool block, the card
        /// layout — is identical, and a second copy of that pipeline would be
        /// two things to keep in step for one differing colour and glyph.
        case scheduled
    }

    static let tag = "agent_callback"

    let kind: Kind
    let jobId: String
    let childSessionId: String?
    let title: String
    /// running · done · cancelled · timeout · failed · rejected
    let status: String
    let tier: String?
    /// [T-sub-agents-v1] Display name of the sub agent that ran this job.
    /// Absent for a job started before the feature, and omitted from the XML
    /// for the built-in agent so an ordinary envelope is unchanged.
    let agent: String?
    let elapsed: String?
    let tool: String?
    let activity: String?
    let turn: Int?
    let summary: String?
    let body: String
    /// [T-sub-agents-sibling-status] One line about the OTHER sub agents of
    /// this conversation, when there are any: how many are still running,
    /// queued, or sitting interrupted.
    ///
    /// Carried on every callback rather than only on a resume, because the
    /// problem it solves is general: the parent model sees one result at a
    /// time and has no other way to tell whether the rest of a batch it
    /// delegated is still coming, already lost, or never started. Without it a
    /// model that fanned out three delegations and got one back can reasonably
    /// summarise as if it had them all.
    var siblings: String?
    /// [T-scheduled-task-card] Which firing this is, and how many are left.
    ///
    /// UI-facing ONLY. The same facts stay written into `body` in their
    /// original `[Scheduled task "x" · fire 2 · remaining 28 · loop(60s×30)]`
    /// form, because the model reads the body — the prompt tells it to decide
    /// from the remaining count whether to start wrapping up, and moving that
    /// into an attribute the model may or may not attend to would be a silent
    /// behaviour change dressed up as a display change. Duplicating it costs a
    /// few tokens and keeps the model's input byte-identical to before.
    var fireIndex: Int?
    var remaining: Int?
    /// `loop(60s×30)` / `cron(0 9 * * *)` / `once` / `on-completion`.
    var triggerLabel: String?
    /// [T-scheduled-next-fire] ISO-8601 instant of the NEXT firing, or nil when
    /// this was the last one. UI-facing: it lets the card say the task is still
    /// planned, which the firing text alone cannot convey.
    var nextFireAt: String?
    /// [T-agent-model-identity] tier requested / resolved / effective model.
    /// Serialised as attributes so the parent model can read them and the
    /// callback cell / sheet can show the same identity the block shows.
    var modelIdentity: HelperModelIdentity? = nil

    init(kind: Kind, jobId: String, childSessionId: String?, title: String, status: String,
         tier: String?, elapsed: String?, tool: String?, activity: String?, turn: Int?,
         summary: String?, body: String, siblings: String? = nil, modelIdentity: HelperModelIdentity? = nil,
         agent: String? = nil,
         fireIndex: Int? = nil, remaining: Int? = nil, triggerLabel: String? = nil,
         nextFireAt: String? = nil) {
        self.kind = kind; self.jobId = jobId; self.childSessionId = childSessionId
        self.agent = agent
        self.title = title; self.status = status; self.tier = tier; self.elapsed = elapsed
        self.tool = tool; self.activity = activity; self.turn = turn; self.summary = summary
        self.body = body; self.siblings = siblings; self.modelIdentity = modelIdentity
        self.fireIndex = fireIndex; self.remaining = remaining; self.triggerLabel = triggerLabel
        self.nextFireAt = nextFireAt
    }

    /// The attribute subset of the identity payload carried on the tag.
    static let identityAttrs: [String] = [
        "tier_requested", "model_resolved", "model_resolved_entry_id", "model_resolved_provider",
        "model_resolved_id", "model_resolved_name",
        "model_effective", "model_effective_entry_id", "model_effective_provider",
        "model_effective_id", "model_effective_name", "model_effective_source", "model_response",
    ]

    // MARK: Detection

    /// Cheap prefix test used on every render / preview pass before the
    /// full parse is attempted.
    static func isCallbackText(_ text: String) -> Bool {
        text.hasPrefix("<\(tag) ") || text.hasPrefix("<\(tag)>")
    }

    /// The child element carrying the payload, per kind. One place so `xml`
    /// and `parse` cannot disagree about it.
    static func bodyTag(for kind: Kind) -> String {
        switch kind {
        case .finished: return "result"
        case .progress: return "last_message"
        case .scheduled: return "prompt"
        }
    }

    // MARK: Serialisation

    var xml: String {
        var attrs: [(String, String)] = [("kind", kind.rawValue), ("job", jobId)]
        if let childSessionId, !childSessionId.isEmpty { attrs.append(("session", childSessionId)) }
        attrs.append(("title", title))
        attrs.append(("status", status))
        if let tier, !tier.isEmpty { attrs.append(("model", tier)) }
        if let agent, !agent.isEmpty { attrs.append(("agent", agent)) }
        if let elapsed, !elapsed.isEmpty { attrs.append(("elapsed", elapsed)) }
        if let tool, !tool.isEmpty { attrs.append(("tool", tool)) }
        if let activity, !activity.isEmpty { attrs.append(("activity", activity)) }
        if let turn, turn > 0 { attrs.append(("turn", String(turn))) }
        // [T-scheduled-task-card] Only ever present on a scheduled envelope,
        // so an agent callback serialises exactly as before.
        if let fireIndex, fireIndex > 0 { attrs.append(("fire", String(fireIndex))) }
        if let remaining, remaining >= 0 { attrs.append(("remaining", String(remaining))) }
        if let triggerLabel, !triggerLabel.isEmpty { attrs.append(("trigger", triggerLabel)) }
        if let nextFireAt, !nextFireAt.isEmpty { attrs.append(("next_fire_at", nextFireAt)) }
        if let id = modelIdentity {
            let payload = id.payload()
            for key in Self.identityAttrs {
                if let v = payload[key] as? String, !v.isEmpty { attrs.append((key, v)) }
            }
        }
        let open = "<\(Self.tag) " + attrs.map { "\($0.0)=\"\(Self.escapeAttr($0.1))\"" }.joined(separator: " ") + ">"
        var lines: [String] = [open]
        if let summary, !summary.isEmpty { lines.append("<summary>\(Self.escapeText(summary))</summary>") }
        if let siblings, !siblings.isEmpty { lines.append("<other_sub_agents>\(Self.escapeText(siblings))</other_sub_agents>") }
        // [T-scheduled-task-card] `prompt` for a scheduled firing: the body is
        // the instruction being injected, not a result or a progress note.
        let bodyTag = Self.bodyTag(for: kind)
        lines.append("<\(bodyTag)>")
        lines.append(body)
        lines.append("</\(bodyTag)>")
        lines.append("</\(Self.tag)>")
        return lines.joined(separator: "\n")
    }

    // MARK: Parsing

    static func parse(_ text: String) -> AgentCallback? {
        guard isCallbackText(text),
              let openEnd = text.firstIndex(of: ">") else { return nil }
        let open = String(text[text.index(text.startIndex, offsetBy: tag.count + 1)..<openEnd])
        let attrs = parseAttributes(open)
        guard let kindRaw = attrs["kind"], let kind = Kind(rawValue: kindRaw),
              let jobId = attrs["job"] else { return nil }
        let inner: Substring = {
            let after = text[text.index(after: openEnd)...]
            if let close = after.range(of: "</\(tag)>", options: .backwards) {
                return after[after.startIndex..<close.lowerBound]
            }
            return after
        }()
        let summary = element("summary", in: inner).map(unescapeText)
        // Round-trips so a re-parsed callback is equal to the one that was
        // written; `element` matches by tag, so this never eats into summary
        // or the body.
        let siblings = element("other_sub_agents", in: inner).map(unescapeText)
        // [T-scheduled-task-card] `prompt` for a scheduled firing: the body is
        // the instruction being injected, not a result or a progress note.
        let bodyTag = Self.bodyTag(for: kind)
        let body = element(bodyTag, in: inner) ?? String(inner).trimmingCharacters(in: .whitespacesAndNewlines)
        var identityPayload: [String: Any] = [:]
        for key in identityAttrs { if let v = attrs[key] { identityPayload[key] = v } }
        if let tier = attrs["model"] { identityPayload["tier_used"] = tier }
        let identity = HelperModelIdentity(payload: identityPayload)
        return AgentCallback(kind: kind,
                             jobId: jobId,
                             childSessionId: attrs["session"],
                             title: attrs["title"] ?? "",
                             status: attrs["status"] ?? (kind == .finished ? "done" : "running"),
                             tier: attrs["model"],
                             elapsed: attrs["elapsed"],
                             tool: attrs["tool"],
                             activity: attrs["activity"],
                             turn: attrs["turn"].flatMap { Int($0) },
                             summary: summary,
                             body: body, siblings: siblings,
                             modelIdentity: identity,
                             agent: attrs["agent"],
                             fireIndex: attrs["fire"].flatMap { Int($0) },
                             remaining: attrs["remaining"].flatMap { Int($0) },
                             triggerLabel: attrs["trigger"],
                             nextFireAt: attrs["next_fire_at"])
    }

    /// One-line stand-in for session previews / notifications, where the
    /// raw XML would be noise.
    var previewLine: String {
        let head: String = switch kind {
        case .finished: AppLocalized("Agent result")
        case .progress: AppLocalized("Agent progress")
        // [T-scheduled-task-card] Named for what it is — the scheduler firing,
        // not an agent reporting — so a session preview or notification does
        // not claim an agent produced something.
        case .scheduled: AppLocalized("Scheduled task")
        }
        return "\(head) · \(title) · \(Self.localizedStatus(status))"
    }

    static func localizedStatus(_ status: String) -> String {
        switch status {
        case "running": return AppLocalized("running")
        case "done": return AppLocalized("Done")
        // [T-subagent-no-deliverable-status] Matches HelperBlockInfo's label so
        // both renderers name this outcome the same way.
        case "no_deliverable": return AppLocalized("No result")
        case "cancelled": return AppLocalized("Cancelled")
        case "timeout": return AppLocalized("Timed out")
        case "failed": return AppLocalized("Failed")
        case "rejected": return AppLocalized("Rejected")
        default: return status
        }
    }

    // MARK: Helpers

    private static func parseAttributes(_ s: String) -> [String: String] {
        var out: [String: String] = [:]
        var rest = Substring(s)
        while let eq = rest.firstIndex(of: "=") {
            let key = rest[rest.startIndex..<eq].trimmingCharacters(in: .whitespaces)
            let afterEq = rest[rest.index(after: eq)...]
            guard let q1 = afterEq.firstIndex(of: "\"") else { break }
            let valStart = afterEq.index(after: q1)
            guard let q2 = afterEq[valStart...].firstIndex(of: "\"") else { break }
            out[key] = unescapeText(String(afterEq[valStart..<q2]))
            rest = afterEq[afterEq.index(after: q2)...]
        }
        return out
    }

    private static func element(_ name: String, in s: Substring) -> String? {
        guard let open = s.range(of: "<\(name)>"),
              let close = s.range(of: "</\(name)>", options: .backwards),
              open.upperBound <= close.lowerBound else { return nil }
        return String(s[open.upperBound..<close.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func escapeAttr(_ s: String) -> String {
        escapeText(s).replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "\n", with: " ")
    }

    private static func escapeText(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    private static func unescapeText(_ s: String) -> String {
        s.replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&amp;", with: "&")
    }
}
