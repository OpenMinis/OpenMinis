#!/usr/bin/env swift
// [T-browser-cli-timeout-reclaim] OpenMinis#245: after `minis-browser-use
// navigate` hit the sandbox's 90s timeout, the browser stayed unusable for
// about 5 minutes.
//
// Root cause: BrowserUseOffload.m stopped waiting at 90s but left the action
// running natively. BrowserTabPool only treats a tab as dead after its 300s
// `actionDeadTimeout`, so the abandoned action held the tab's serial slot for
// another ~210s. Every follow-up command queued on that slot and failed its
// 20s slot wait.
//
// Fix: on the 90s timeout the handler calls
// BrowserUseOffloadBridge.cancelAndReclaim, which cancels the execution task it
// abandoned. withDeadOnTimeout turns that cancellation into the same abort
// BrowserTabPool.abortAndRebuildTab fires: the op task is cancelled, the tab is
// rebuilt, and the slot is released by the caller's defer. The 300s ceiling is
// left as the last-resort net.
//
// Part 1 runs the race/abort mechanism, ported from BrowserTabPool, because the
// app target can't run on a host. Part 2 pins the ported code to the real
// sources so the two can't drift apart silently.
//
// Run: swift BrowserCLITimeoutReclaimTests.swift
import Foundation

var failures = 0
func check(_ label: String, _ ok: Bool) {
    if ok { print("  ✅ \(label)") } else { print("  ❌ \(label)"); failures += 1 }
}

// ── Part 1: mechanism port ──────────────────────────────────────────────────

struct ActionDeadTimeout: Error { var abortedByCaller = false }

final class RaceBox: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    private var cont: CheckedContinuation<String, Error>?
    private var pending: Result<String, Error>?
    var onFinish: (() -> Void)?
    func attach(_ c: CheckedContinuation<String, Error>) {
        lock.lock()
        if let p = pending { lock.unlock(); c.resume(with: p); return }
        cont = c
        lock.unlock()
    }
    func finish(_ r: Result<String, Error>) {
        lock.lock()
        if done { lock.unlock(); return }
        done = true
        let c = cont; cont = nil
        if c == nil { pending = r }
        let cleanup = onFinish; onFinish = nil
        lock.unlock()
        cleanup?()
        c?.resume(with: r)
    }
}

/// A one-slot-per-tab pool with the same shape as BrowserTabPool's
/// executeInner: acquire slot → withDeadOnTimeout → rebuild on dead → defer
/// release.
@MainActor final class MiniPool {
    var slotHeld = false
    var rebuilds = 0
    var inFlightAborts: [Int: (token: UInt64, abort: () -> Void)] = [:]
    var nextToken: UInt64 = 0
    let deadline: TimeInterval
    let honourCancellation: Bool
    init(deadline: TimeInterval, honourCancellation: Bool) {
        self.deadline = deadline
        self.honourCancellation = honourCancellation
    }

    func execute(tab: Int, _ op: @escaping @Sendable () async throws -> String) async throws -> String {
        guard !slotHeld else { return "slot-busy" }
        slotHeld = true
        defer { slotHeld = false }
        do {
            return try await withDeadOnTimeout(tab: tab, op)
        } catch is ActionDeadTimeout {
            rebuilds += 1
            return "rebuilt"
        }
    }

    func withDeadOnTimeout(tab: Int, _ operation: @escaping @Sendable () async throws -> String) async throws -> String {
        try Task.checkCancellation()
        let box = RaceBox()
        let opTask = Task { do { box.finish(.success(try await operation())) } catch { box.finish(.failure(error)) } }
        let timer = DispatchSource.makeTimerSource(queue: .global())
        timer.schedule(deadline: .now() + deadline)
        timer.setEventHandler { box.finish(.failure(ActionDeadTimeout())) }
        timer.resume()
        box.onFinish = { timer.cancel(); opTask.cancel() }
        let token = nextToken; nextToken &+= 1
        let abort: @Sendable () -> Void = { box.finish(.failure(ActionDeadTimeout(abortedByCaller: true))) }
        inFlightAborts[tab] = (token, abort)
        defer { if inFlightAborts[tab]?.token == token { inFlightAborts.removeValue(forKey: tab) } }
        if honourCancellation {
            return try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { box.attach($0) }
            } onCancel: { abort() }
        }
        return try await withCheckedThrowingContinuation { box.attach($0) }
    }

    @discardableResult
    func abortAndRebuildTab(tabId: Int) -> Bool {
        guard let e = inFlightAborts[tabId] else { return false }
        e.abort()
        return true
    }
}

/// A WebKit call that never calls back and ignores cancellation, like a wedged
/// WebContent process.
@Sendable func wedged() async throws -> String {
    await withCheckedContinuation { (_: CheckedContinuation<Void, Never>) in }
    return "never"
}

func elapsed(_ t0: Date) -> Double { Date().timeIntervalSince(t0) }

func runMechanism() async {
    print("▶️  1. cancelling the abandoned execution reclaims the tab at once")
    do {
        let pool = await MiniPool(deadline: 5, honourCancellation: true)
        let t0 = Date()
        let exec = Task { @MainActor in try await pool.execute(tab: 0, wedged) }
        try? await Task.sleep(nanoseconds: 200_000_000)
        exec.cancel()                                  // cancelAndReclaim
        let r = try? await exec.value
        check("the wedged action ends as a dead tab (\(r ?? "nil"))", r == "rebuilt")
        check("…well before the dead-tab ceiling (\(String(format: "%.2f", elapsed(t0)))s < 1s)", elapsed(t0) < 1)
        check("…the tab is rebuilt exactly once", await pool.rebuilds == 1)
        check("…and the slot is free again", await !pool.slotHeld)
        let next = try? await pool.execute(tab: 0) { "ok" }
        check("the next command on the tab runs (\(next ?? "nil"))", next == "ok")
        check("no in-flight abort is left behind", await pool.inFlightAborts.isEmpty)
    }

    print("\n▶️  2. without the cancellation hook (old code) the slot stays held")
    do {
        let pool = await MiniPool(deadline: 1.5, honourCancellation: false)
        let exec = Task { @MainActor in try await pool.execute(tab: 0, wedged) }
        try? await Task.sleep(nanoseconds: 200_000_000)
        exec.cancel()
        try? await Task.sleep(nanoseconds: 300_000_000)
        let next = try? await pool.execute(tab: 0) { "ok" }
        check("a follow-up command is blocked by the abandoned action (\(next ?? "nil"))", next == "slot-busy")
        _ = try? await exec.value                      // drains via the ceiling
        check("…until the ceiling finally fires", await pool.rebuilds == 1)
    }

    print("\n▶️  3. abortAndRebuildTab")
    do {
        let pool = await MiniPool(deadline: 5, honourCancellation: true)
        let exec = Task { @MainActor in try await pool.execute(tab: 3, wedged) }
        try? await Task.sleep(nanoseconds: 200_000_000)
        let fired = await pool.abortAndRebuildTab(tabId: 3)
        let r = try? await exec.value
        check("aborts the action running on the tab", fired && r == "rebuilt")
        let refired = await pool.abortAndRebuildTab(tabId: 3)
        let rebuilds = await pool.rebuilds
        check("leaves an idle tab alone (returns false, no rebuild)", !refired && rebuilds == 1)
    }

    print("\n▶️  4. a healthy action is unaffected")
    do {
        let pool = await MiniPool(deadline: 5, honourCancellation: true)
        let r = try? await pool.execute(tab: 0) { "done" }
        check("completes normally, no rebuild", r == "done")
        let clean = await pool.inFlightAborts.isEmpty
        let rebuilds = await pool.rebuilds
        check("…and deregisters its abort", clean && rebuilds == 0)
    }

    print("\n▶️  5. a caller cancelled before the action starts does not rebuild the tab")
    do {
        let pool = await MiniPool(deadline: 5, honourCancellation: true)
        let exec = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)  // stuck ahead of the action
            return try await pool.execute(tab: 0) { "late" }
        }
        exec.cancel()
        _ = try? await exec.value
        check("no rebuild of a tab that never wedged", await pool.rebuilds == 0)
        check("…and the slot is not left held", await !pool.slotHeld)
    }
}

// ── Part 2: the real sources carry the ported pieces ────────────────────────

let repo = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func read(_ rel: String) -> String {
    (try? String(contentsOf: repo.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
/// Text from `start` up to the next line starting with `end` (a function body).
func body(_ src: String, from start: String, to end: String = "\n    }\n") -> String {
    guard let a = src.range(of: start) else { return "" }
    guard let b = src.range(of: end, range: a.upperBound..<src.endIndex) else { return String(src[a.lowerBound...]) }
    return String(src[a.lowerBound..<b.upperBound])
}

func runSourceGuards() {
    let pool = read("src/ios/Agent/BrowserUse/BrowserTabPool.swift")
    let bridge = read("src/ios/NativeOffloads/BrowserUseOffloadBridge.swift")
    let objc = read("src/ios/NativeOffloads/BrowserUseOffload.m")

    print("\n▶️  6. BrowserUseOffload.m reclaims on the 90s timeout")
    let timeoutBranch = body(objc, from: "if (waitErr != 0 || resultDict == nil) {", to: "return NOFF_EXIT_ERROR;")
    // [T-browser-cli-reclaim-scope] The reclaim is keyed on the invocation
    // token execute returns, not (session, tab) — see BrowserCLIReclaimScopeTests.
    check("the timeout branch calls cancelAndReclaim",
          timeoutBranch.contains("[BrowserUseOffloadBridge cancelAndReclaimWithInvocation:invocation];"))
    check("…only when the wait actually timed out", timeoutBranch.contains("if (waitErr != 0) {"))
    check("the invocation token comes from execute",
          objc.contains("uint64_t invocation = [BrowserUseOffloadBridge executeWithJson:"))

    print("\n▶️  7. the bridge tracks and cancels the abandoned execution")
    let reclaim = body(bridge, from: "@objc public static func cancelAndReclaim(invocation: UInt64) {")
    check("cancelAndReclaim exists with the ObjC-facing signature", !reclaim.isEmpty)
    check("…matches exactly the abandoned invocation",
          reclaim.contains("let hit = inFlight.removeValue(forKey: invocation)"))
    check("…and cancels the task", reclaim.contains("hit.task?.cancel()"))
    let exec = body(bridge, from: "@objc public static func execute(")
    check("execute registers before spawning, removes in the task's defer",
          exec.contains("inFlight[invocation] = InFlight(sid: sid, tabId: input.tabId, task: nil)")
            && exec.contains("defer { endInvocation(invocation) }")
            && exec.contains("inFlight[invocation]?.task = task"))

    print("\n▶️  8. BrowserTabPool turns cancellation into the dead-tab path")
    let race = body(pool, from: "private func withDeadOnTimeout(")
    check("withDeadOnTimeout refuses to start for a cancelled caller",
          race.contains("try Task.checkCancellation()")
            && (race.range(of: "try Task.checkCancellation()")!.lowerBound < (race.range(of: "let opTask = Task {")?.lowerBound ?? race.startIndex)))
    check("…aborts via ActionDeadTimeout(abortedByCaller: true)",
          race.contains("box.finish(.failure(ActionDeadTimeout(abortedByCaller: true)))"))
    check("…on task cancellation", race.contains("} onCancel: {\n            abort()\n        }"))
    check("…and registers / deregisters the abort per tab",
          race.contains("inFlightAborts[targetId] = (abortToken, abort)")
            && race.contains("if inFlightAborts[targetId]?.token == abortToken {"))
    let abortFn = body(pool, from: "func abortAndRebuildTab(tabId: Int) -> Bool {")
    check("abortAndRebuildTab fires the in-flight abort",
          abortFn.contains("guard let entry = inFlightAborts[tabId] else {") && abortFn.contains("entry.abort()"))
    check("the dead-tab catch still rebuilds the tab",
          pool.contains("} catch let dead as ActionDeadTimeout {")
            && pool.contains("let (newId, deadURL) = rebuildDeadTab(oldId: targetId)"))
    check("the slot is released by executeInner's defer", pool.contains("defer { releaseSerialSlot() }"))
    check("the 300s last-resort ceiling is unchanged (DEBUG + release)",
          pool.contains("static var actionDeadTimeout: TimeInterval { actionDeadTimeoutOverride ?? 300 }")
            && pool.contains("static let actionDeadTimeout: TimeInterval = 300"))
}

await runMechanism()
runSourceGuards()
print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
