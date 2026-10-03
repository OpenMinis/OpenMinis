// Tests for [T-browser-cli-timeout-reclaim] (commit dc42b6bc6, OpenMinis#245)
// — the scope of `BrowserUseOffloadBridge.cancelAndReclaim`.
//
// The existing suite (BrowserCLITimeoutReclaimTests) proves that ONE abandoned
// CLI invocation is cancelled and its wedged tab rebuilt. It does not cover two
// CLI invocations in flight at once.
//
// The bridge picks what to cancel by (sessionId, requested tab_id). But:
//   * the session id is `ISHExecutionCoordinator.mountedSessionIdSnapshot`, a
//     single process-wide value — every concurrent `minis-browser-use` call
//     (main agent, sub-agents, parallel tool calls) resolves to the SAME sid;
//   * the tab id is the one the command ASKED for, and most commands name
//     none, so it is nil for all of them.
// So when one invocation hits the 90 s CLI timeout, `cancelAndReclaim` also
// cancels every other implicit-tab invocation still running — and cancelling a
// task that is inside `withDeadOnTimeout` fires its abort, which rebuilds that
// OTHER (healthy) tab and throws its page away. The commit's own comment says
// the cancel is scoped precisely so that "a blind abort could [not] kill an
// agent action"; the scope key is too coarse to deliver that.
//
// Invariant pinned: a CLI timeout cancels exactly the invocation that timed
// out. Proposed fix: `execute` returns its invocation token and the ObjC
// handler passes that token to `cancelAndReclaim`.
//
// Standalone: `swift BrowserCLIReclaimScopeTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ ok: Bool) {
    if ok { print("  ✅ \(label)") } else { print("  ❌ \(label)"); failures += 1 }
}

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    print("  ⚠️  could not locate \(rel)")
    return ""
}

// MARK: - Model of the bridge's in-flight table (mirrors the shipped filter)

struct InFlight { let sid: String; let tabId: Int?; var cancelled = false }

struct Bridge {
    var inFlight: [UInt64: InFlight] = [:]
    var next: UInt64 = 0
    mutating func execute(sid: String, tabId: Int?) -> UInt64 {
        let id = next; next += 1
        inFlight[id] = InFlight(sid: sid, tabId: tabId)
        return id
    }
    /// Shipped: match by (sid, requested tab).
    mutating func cancelAndReclaimShipped(tabId: Int, sessionId: String) -> [UInt64] {
        let want: Int? = tabId >= 0 ? tabId : nil
        let hits = inFlight.filter { $0.value.sid == sessionId && $0.value.tabId == want }.map(\.key)
        for h in hits { inFlight[h]?.cancelled = true; inFlight.removeValue(forKey: h) }
        return hits
    }
    /// Proposed: match by the invocation token the handler got back.
    mutating func cancelAndReclaim(invocation: UInt64) -> [UInt64] {
        guard inFlight.removeValue(forKey: invocation) != nil else { return [] }
        return [invocation]
    }
}

let sid = "global-mounted-sid"   // one value for the whole process

print("\n▶️  two implicit-tab CLI calls in flight; the first one times out")
var b = Bridge()
let wedged = b.execute(sid: sid, tabId: nil)     // e.g. main agent, page hung
let healthy = b.execute(sid: sid, tabId: nil)    // e.g. sub-agent, working fine on its own tab
let cancelled = b.cancelAndReclaimShipped(tabId: -1, sessionId: sid)
check("the shipped filter cancels the abandoned call", cancelled.contains(wedged))
check("the shipped filter also cancels the healthy concurrent call (demonstrates the bug)",
      cancelled.contains(healthy))

var p = Bridge()
let w2 = p.execute(sid: sid, tabId: nil)
let h2 = p.execute(sid: sid, tabId: nil)
let c2 = p.cancelAndReclaim(invocation: w2)
check("token-scoped reclaim cancels only the call that timed out", c2 == [w2])
check("…and leaves the concurrent call running", p.inFlight[h2] != nil)

print("\n▶️  explicit tab ids are also not unique per invocation")
var e = Bridge()
let a1 = e.execute(sid: sid, tabId: 3)
let a2 = e.execute(sid: sid, tabId: 3)   // queued behind a1 on the same tab
let ce = e.cancelAndReclaimShipped(tabId: 3, sessionId: sid)
check("shipped: a queued follow-up on the same tab is cancelled with the wedged one",
      ce.contains(a1) && ce.contains(a2))

// MARK: - Source invariants

print("\n▶️  source invariants")
let bridge = source("NativeOffloads/BrowserUseOffloadBridge.swift")
let coord = source("Agent/ISH/ISHExecutionCoordinator.swift")
let objc = source("NativeOffloads/BrowserUseOffload.m")
if !bridge.isEmpty {
    check("precondition: the session key is the process-wide mounted-sid snapshot",
          bridge.replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .contains("ISHExecutionCoordinator.mountedSessionIdSnapshot ?? Self.unmountedSentinel")
          && coord.contains("nonisolated static var mountedSessionIdSnapshot: String?"))
    // [T-browser-cli-reclaim-scope] Fixed shape: execute hands back a
    // per-invocation token and the ObjC timeout branch passes exactly that
    // token back; nothing matches by (sid, tab) any more.
    check("the ObjC handler keeps the token execute returns",
          objc.contains("uint64_t invocation = [BrowserUseOffloadBridge executeWithJson:"))
    check("…and hands exactly that token to cancelAndReclaim",
          objc.contains("[BrowserUseOffloadBridge cancelAndReclaimWithInvocation:invocation];")
            && !objc.contains("cancelAndReclaimWithTabId:"))
    check("execute returns its invocation token",
          bridge.contains(") -> UInt64 {") && bridge.contains("        return invocation\n    }"))
    let filterIsPerInvocation = !bridge.contains("$0.value.sid == sessionId && $0.value.tabId == wantTab")
        && bridge.contains("@objc public static func cancelAndReclaim(invocation: UInt64) {")
        && bridge.contains("let hit = inFlight.removeValue(forKey: invocation)")
    check("cancelAndReclaim is scoped to ONE invocation (BUG if this fails: concurrent CLI calls are collateral)",
          filterIsPerInvocation)
}

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
