// Regression test for [T-ios27-scene-create-watchdog] — nothing inside a
// `dispatch_once` singleton initializer may block on the system logging stack
// or on bulk database work.
//
// The crash: iPhone15,3 on iOS 27.0 (24A437), Minis 1.14 (20), SIGKILL by the
// FRONTBOARD `scene-create` watchdog after exhausting the 10 s wall-clock
// allowance — with 6.95 s of total CPU but only 0.161 s of APPLICATION CPU.
// Blocked, not busy. Symbolicated against the matching dSYM
// (b6b6bee7-f2dc-3879-8052-51e986b51b45):
//
//   thread 9  com.apple.root.background-qos.cooperative   HOLDS the once token
//     closure #2 in MinisApp.init          (MinisApp.swift:185)
//     one-time initialization for shared   (ChatStore.swift:462)
//     ChatStore.init                       (ChatStore.swift:505-506)
//     ChatStore.createTables               (ChatStore.swift:739)
//     -[NSNotificationCenter postNotificationName:object:userInfo:]
//     _CFXNotificationPost
//     -[NSOperation waitUntilFinished]     ← parked here, forever
//
//   thread 0  com.apple.main-thread                       WAITS on that token
//     closure #27 in ContentView.body      (ContentView.swift:1791)
//     _dispatch_once_wait
//     __ulock_wait
//
// A background task touched ChatStore.shared first, so it entered the once. A
// log line inside createTables went NSLog -> (iOS 27 libtrace) -> notification
// post -> NSOperation wait. Thread 10 corroborates: it sat in
// `___os_state_request_for_self_block_invoke` inside a blocked dispatch_sync,
// i.e. the logging subsystem itself was wedged. The main thread then touched
// the same singleton during scene creation and inherited the stall.
//
// Standalone (`swift OnceInitNoBlockingTests.swift`) like its neighbours:
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

// MARK: - Model of AppLogger's deferral (mirrors src/ios/Shared/AppLogger.swift)

final class LoggerModel {
    /// Lines that actually reached the system logging stack.
    private(set) var emitted: [String] = []
    private let lock = NSLock()
    private var buffer: [String] = []
    private var depth = 0
    private let bufferCap = 512

    /// Stands in for NSLog + CrashReporter.appendLog. In the crash this is the
    /// call that never returned.
    var emitHook: (() -> Void)?

    func log(_ message: String) {
        lock.lock()
        let deferring = depth > 0
        if deferring, buffer.count < bufferCap { buffer.append(message) }
        lock.unlock()
        if deferring { return }
        emitHook?()
        lock.lock(); emitted.append(message); lock.unlock()
    }

    func withDeferredLogging<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); depth += 1; lock.unlock()
        defer {
            lock.lock()
            depth -= 1
            let flush = depth == 0
            let pending = flush ? buffer : []
            if flush { buffer.removeAll() }
            lock.unlock()
            for m in pending {
                emitHook?()
                lock.lock(); emitted.append(m); lock.unlock()
            }
        }
        return try body()
    }

    var bufferedCount: Int { lock.lock(); defer { lock.unlock() }; return buffer.count }
}

// MARK: - 1. The deadlock reproduction

print("\n▶️  a blocking logging stack no longer stalls the once body")

// Simulate the iOS 27 condition: the emit path blocks (the notification round
// trip that parked on -[NSOperation waitUntilFinished]).
let blockingLogger = LoggerModel()
var emitCalls = 0
blockingLogger.emitHook = { emitCalls += 1 }

// WITHOUT the guard: the once body calls emit directly, so a blocked emit is a
// blocked once token. Proven by the emit running INSIDE the body.
var emitsDuringUnguardedBody = 0
blockingLogger.emitHook = { emitCalls += 1; emitsDuringUnguardedBody += 1 }
func unguardedOnceBody(_ l: LoggerModel) {
    l.log("[part_flags] backfill done: updated 91234 row(s)")
}
unguardedOnceBody(blockingLogger)
checkEq("unguarded: the log reaches the (blocking) stack inside the once body",
        emitsDuringUnguardedBody, 1)

// WITH the guard: nothing reaches the stack until the body has returned.
let guarded = LoggerModel()
var emitsDuringGuardedBody = 0
var bodyFinished = false
guarded.emitHook = { if !bodyFinished { emitsDuringGuardedBody += 1 } }
guarded.withDeferredLogging {
    guarded.log("[iCloudTrace] v1 dirty-row zombie cleanup: deleted=28431")
    guarded.log("[part_flags] backfill done: updated 91234 row(s)")
    checkEq("nothing emitted while still inside the once body", emitsDuringGuardedBody, 0)
    checkEq("but both lines are buffered", guarded.bufferedCount, 2)
    bodyFinished = true
}
checkEq("both lines survive the deferral", guarded.emitted.count, 2)
check("in order", guarded.emitted.first?.contains("zombie cleanup") == true)

// MARK: - 2. Re-entrancy

print("\n▶️  nested deferral: only the outermost flushes")
let nested = LoggerModel()
nested.withDeferredLogging {
    nested.log("outer-1")
    nested.withDeferredLogging {
        nested.log("inner-1")
        checkEq("inner scope emits nothing", nested.emitted.count, 0)
    }
    checkEq("leaving the INNER scope still emits nothing", nested.emitted.count, 0)
    checkEq("both lines buffered", nested.bufferedCount, 2)
    nested.log("outer-2")
}
checkEq("the outermost scope flushes all three", nested.emitted.count, 3)

// MARK: - 3. The buffer is bounded

print("\n▶️  a runaway initializer cannot become a memory problem")
let flood = LoggerModel()
flood.withDeferredLogging {
    for i in 0..<5000 { flood.log("line \(i)") }
    checkEq("buffer is capped at 512", flood.bufferedCount, 512)
}
checkEq("and only the cap is flushed", flood.emitted.count, 512)

// MARK: - 4. Values still come back

print("\n▶️  withDeferredLogging is transparent to its body")
let ret = LoggerModel()
let value = ret.withDeferredLogging { () -> Int in
    ret.log("work")
    return 42
}
checkEq("returns the body's value", value, 42)

enum TestError: Error { case boom }
let thrower = LoggerModel()
var threw = false
do {
    _ = try thrower.withDeferredLogging { () -> Int in
        thrower.log("before the throw")
        throw TestError.boom
    }
} catch { threw = true }
check("rethrows", threw)
checkEq("and still flushes what was buffered before the throw", thrower.emitted.count, 1)

// MARK: - 5. Source invariants

print("\n▶️  the once-init path is clean in the real source")

func read(_ p: String) -> String? { try? String(contentsOfFile: p, encoding: .utf8) }
guard let chatStore = read("../../Agent/Chat/ChatStore.swift"),
      let appLogger = read("../../Shared/AppLogger.swift"),
      let minisApp = read("../../MinisApp.swift"),
      let cloudSync = read("../../Agent/Sync/CloudSyncEngine.swift") else {
    print("  ❌ could not read sources"); failures += 1; exit(1)
}

// ChatStore.init must be wrapped.
check("ChatStore.init defers its logging",
      chatStore.contains("AppLogger.withDeferredLogging {"))

// The two bulk migrations must no longer be inside createTables. Locate
// createTables by brace matching and assert neither appears in it.
func body(of fn: String, in src: String) -> String? {
    guard let r = src.range(of: fn) else { return nil }
    var depth = 0, started = false
    var out = ""
    for ch in src[r.lowerBound...] {
        out.append(ch)
        if ch == "{" { depth += 1; started = true }
        if ch == "}" { depth -= 1; if started && depth == 0 { return out } }
    }
    return out
}
if let ct = body(of: "private func createTables()", in: chatStore) {
    check("part_flags backfill is NOT in createTables",
          !ct.contains("UPDATE messages SET part_flags"))
    check("v1 zombie dirty-row DELETE is NOT in createTables",
          !ct.contains("DELETE FROM sync_dirty_records WHERE record_type IN"))
    check("no logging call remains in createTables",
          !ct.contains("iCloudLogger.info") && !ct.contains("iCloudLogger.error"))
    // ADD COLUMN must stay — the column has to exist before anything queries it.
    check("ADD COLUMN for part_flags stays in createTables",
          ct.contains("column: \"part_flags\""))
} else {
    check("found createTables", false)
}

// They must live in the deferred entry point instead...
check("runDeferredMigrations exists", chatStore.contains("func runDeferredMigrations()"))
check("it runs the part_flags backfill", chatStore.contains("backfillPartFlagsIfNeeded()"))
check("it runs the v1 zombie cleanup", chatStore.contains("cleanupV1ZombieDirtyRowsIfNeeded()"))
// ...and be called off the init path.
check("the launch path calls it", minisApp.contains("await ChatStore.shared.runDeferredMigrations()"))

// The guarded-migration flags must still be honoured, or the work repeats every
// launch (or, worse, never runs).
check("part_flags backfill is still UserDefaults-guarded",
      chatStore.contains("chatStore.partFlagsBackfillV1Done"))
check("zombie cleanup is still UserDefaults-guarded",
      chatStore.contains("cloudSync.v2.zombieDirtyCleanupV1Done"))

// AppLogger's guard must be bounded and must flush off the caller's thread —
// flushing inline would reintroduce the stall it exists to prevent.
// Assert the PROPERTY (a 512 cap exists on the buffer append), not one exact
// spelling — an earlier version of this test pinned the literal
// "deferredBuffer.count < 512" and broke purely because the buffer moved into
// a thread-local box and was renamed, with behaviour untouched.
check("the deferral buffer is capped at 512",
      appLogger.contains("< 512") && appLogger.contains("buffer.append"))
check("the flush hops off the caller's thread",
      appLogger.contains("DispatchQueue.global(qos: .utility).async"))
check("deferral is re-entrant (depth-counted)",
      appLogger.contains("depth += 1") && appLogger.contains("depth -= 1"))
// The deferral state must be THREAD-LOCAL: a process-global flag would also
// silence every other thread for the duration, reordering their output and
// losing it entirely if the app crashed inside the window.
check("deferral state is thread-local, not process-global",
      appLogger.contains("threadDictionary"))

// CloudSyncEngine.shared: same once-token hazard, its legacy-cache delete and
// log are off the init thread now.
check("CloudSyncEngine defers its legacy-cache cleanup",
      cloudSync.contains("DispatchQueue.global(qos: .utility).async { [logger] in"))

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All once-init non-blocking tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
