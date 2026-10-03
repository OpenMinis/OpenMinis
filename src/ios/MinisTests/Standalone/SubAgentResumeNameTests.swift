// Tests for [T-subagent-resume-agent-name] — a delegation to a NAMED sub agent
// that is interrupted and later resumed must come back as that same agent, not
// as the built-in "General Sub Agent".
//
// Standalone (`swift SubAgentResumeNameTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced pieces

/// Mirrors HelperRunner.parseDelegateResult.
func parseDelegateResult(_ content: String) -> [String: Any]? {
    guard content.hasPrefix("{"), let d = content.data(using: .utf8),
          let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return nil }
    return o
}

/// Mirrors HelperRunner.progressLine.
func progressLine(title: String, elapsed: Int) -> String {
    "◐ Agent · \(title) · running in background · \(elapsed / 60):\(String(format: "%02d", elapsed % 60))"
}

/// Mirrors the resume resolution: in-memory block first, persisted row second.
func resolveAgentName(blockContent: String, persistedToolResult: String?) -> String? {
    var name = parseDelegateResult(blockContent)?["agent"] as? String
    if name?.isEmpty ?? true {
        name = persistedToolResult.flatMap { parseDelegateResult($0)?["agent"] as? String }
        if name?.isEmpty ?? true { return nil }
    }
    return name
}

struct Agent: Equatable { let name: String; let isBuiltIn: Bool }
let builtIn = Agent(name: "General Sub Agent", isBuiltIn: true)

/// Mirrors the roster lookup + fallback, reporting whether it degraded.
func pickAgent(_ wanted: String?, roster: [Agent]) -> (agent: Agent, missing: String?) {
    var picked = roster.first { $0.isBuiltIn } ?? builtIn
    guard let wanted, !wanted.isEmpty else { return (picked, nil) }
    if let named = roster.first(where: { $0.name == wanted }) { picked = named; return (picked, nil) }
    return (picked, wanted)
}

let roster = [builtIn, Agent(name: "LENS", isBuiltIn: false)]

// The payload a BACKGROUND delegation persists as its tool_result, post-fix.
func startPayload(agent: String?) -> String {
    var d: [String: Any] = ["ok": true, "status": "running", "job_id": "j1",
                            "child_session_id": "c1", "budget_minutes": 10]
    if let agent { d["agent"] = agent }
    return String(data: try! JSONSerialization.data(withJSONObject: d, options: [.sortedKeys]),
                  encoding: .utf8)!
}

print("\n[1] The bug: a running block's content is a progress line, not JSON")
do {
    let live = progressLine(title: "Investigate the logs", elapsed: 95)
    check("a progress line is not JSON", parseDelegateResult(live) == nil)
    check("…so the in-memory block yields no agent name",
          (parseDelegateResult(live)?["agent"] as? String) == nil)
    // That is the normal state of anything worth resuming: the mirror task
    // rewrites content once a second for the whole run.
    check("it does not start with '{'", live.hasPrefix("{"), false)
}

print("\n[2] Recovery from the persisted tool_result")
do {
    let live = progressLine(title: "Investigate the logs", elapsed: 95)
    checkEq("LENS is recovered from the persisted row",
            resolveAgentName(blockContent: live, persistedToolResult: startPayload(agent: "LENS")), "LENS")
    // Pre-fix the start payload had no "agent" key at all.
    check("PRE-FIX: a payload without the field recovers nothing",
          resolveAgentName(blockContent: live, persistedToolResult: startPayload(agent: nil)) == nil)
    // A finished delegation keeps working through the in-memory path.
    checkEq("a completed block still resolves from its own JSON",
            resolveAgentName(blockContent: startPayload(agent: "LENS"), persistedToolResult: nil), "LENS")
    // In-memory wins when both are present and agree.
    checkEq("in-memory JSON takes precedence",
            resolveAgentName(blockContent: startPayload(agent: "LENS"),
                             persistedToolResult: startPayload(agent: "OTHER")), "LENS")
    check("no source at all yields nil",
          resolveAgentName(blockContent: "◐ Agent · x · 0:01", persistedToolResult: nil) == nil)
}

print("\n[3] Roster resolution and the fallback")
do {
    let r1 = pickAgent("LENS", roster: roster)
    checkEq("a named agent that exists is used", r1.agent.name, "LENS")
    check("…and nothing is reported missing", r1.missing == nil)

    // The reported bug, end to end.
    let live = progressLine(title: "t", elapsed: 10)
    let recovered = resolveAgentName(blockContent: live, persistedToolResult: startPayload(agent: "LENS"))
    checkEq("POST-FIX: an interrupted LENS run resumes as LENS",
            pickAgent(recovered, roster: roster).agent.name, "LENS")
    let preFix = resolveAgentName(blockContent: live, persistedToolResult: startPayload(agent: nil))
    checkEq("PRE-FIX: the same run degraded to the built-in",
            pickAgent(preFix, roster: roster).agent.name, "General Sub Agent")

    // A genuinely deleted definition still falls back — but says so.
    let r2 = pickAgent("LENS", roster: [builtIn])
    checkEq("a deleted agent falls back to the built-in", r2.agent.name, "General Sub Agent")
    checkEq("…and names what went missing", r2.missing, "LENS")
    // An absent name is not a "missing agent" — it is simply unknown.
    check("nil name reports nothing missing", pickAgent(nil, roster: roster).missing == nil)
    check("empty name reports nothing missing", pickAgent("", roster: roster).missing == nil)
}

print("\n[4] Shipping sources match these assumptions")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let hr = source("Agent/Jobs/HelperRunner.swift")
if hr.isEmpty { print("  ⏭  source not readable") } else {
    // The core fix: the background-start payload — the only one persisted while
    // a job is still running — must carry the agent name.
    if let r = hr.range(of: "\"status\": \"running\",") {
        let window = String(hr[r.lowerBound...].prefix(1400))
        check("the background-start payload carries \"agent\"", window.contains("\"agent\": job.subAgentName"))
        check("…sourced from the job, which is set before this runs",
              window.contains("job.subAgentName ?? NSNull()"))
    } else { check("background-start payload located", false) }

    check("resume falls back to the persisted row",
          hr.contains("agentName = await Self.persistedAgentName(toolUseId: toolUseId, sessionId: parentSid)"))
    check("the helper reads the tool_result, not the block",
          hr.contains("static func persistedAgentName(toolUseId: String, sessionId: String) async -> String?")
          && hr.contains("case .toolResult(let tr) = part, tr.toolUseId == toolUseId"))
    check("a missing definition is recorded, not swallowed",
          hr.contains("namedAgentMissing = agentName"))
    check("…and surfaced to the user",
          hr.contains("transientNotice = String(format: AppLocalized(\"Sub agent"))
    // It must NOT come back as the return value: every caller treats non-nil
    // as "the resume failed".
    check("the notice is not returned as an error",
          !hr.contains("return \"Sub agent \\\"\\(missing)\\\""))

    // The resume path sets the name on the job before starting the background
    // helper, so a SECOND interruption is also recoverable.
    let resumeIdx = hr.range(of: "job.subAgentName = subAgent.name", options: .backwards)!.lowerBound
    let startIdx = hr.range(of: "_ = startBackgroundHelper(job: job", options: .backwards)!.lowerBound
    check("a resumed run records its own name before restarting", resumeIdx < startIdx)

    let loc = source("Localizable.xcstrings")
    check("the notice is localized", loc.contains("Sub agent \\\"%@\\\" no longer exists"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
