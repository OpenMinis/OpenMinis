// [T30] Blocks produced while nobody is observing the list (app backgrounded,
// foreground-resume in progress, in-loop compaction, loop ending on another
// session) must all reach the collection view through the same deferred-
// snapshot funnel.
//
// Pins:
//   e0526fd90  stream-end while still suspended leaves the snapshot pending;
//              onStreamingUIUpdatesResumed flushes it (chokepoint
//              clearStreamingUIUpdatesSuspended)
//   b5088f72c  the resume flush must NOT be gated on isProcessing == false
//   fba9228c3  [BlocksLost] diagnostics on the deferral / replay path
//   c11545f51  cells are configured against the snapshot's own generation
//   017bc8494  didBecomeActive replay gated on the ACTIVE session (keeps the
//              flag otherwise); the deferral flag is set inside the
//              transition window too
//   512fb88aa  compactBefore restores isProcessing to its entry value, so an
//              in-loop compaction no longer fires a false loop-end
//
// The backlog item wanted the funnel extracted into a pure function first.
// Production code is NOT refactored here: the decisions at each trigger site
// are ported verbatim into a small model (VM side: AIChatViewModel.swift
// isProcessing didSet ~L860-935, clearStreamingUIUpdatesSuspended L1538,
// consumeDeferredSnapshotIfNeeded L1561, didBecomeActive observer L330-360,
// setStreamingUIUpdatesSuspended L7420; coordinator side:
// CollectionViewMessageListV3.swift bind4b block-count sink ~L2520,
// stream-end terminal rescue ~L2497, onStreamingUIUpdatesResumed hook ~L1952,
// flushPendingSnapshotIfNeeded L2536; compaction: +Compaction.swift L740-761),
// and section [3] greps the shipping sources so every known trigger site
// still calls into the funnel.
//
// Standalone (`swift UnobservedSnapshotFlushTests.swift`): the app cannot
// link for a simulator (deps/libs/libish_emu.a is device-only arm64).

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported model

final class Message {
    let id = UUID()
    var blocks: [String] = []
}

/// The coordinator half (CollectionViewMessageListV3 Coordinator).
final class Coordinator {
    private(set) var hasPendingSnapshot = false
    private(set) var snapshotItems = 0                // block items in the applied snapshot
    private(set) var snapshotGeneration: [UUID] = []   // c11545f51: the messages the snapshot was built from
    private(set) var flushCallers: [String] = []
    weak var vm: VM?

    /// applySnapshot(messages:caller:)
    func applySnapshot(messages: [Message], caller: String) {
        snapshotGeneration = messages.map(\.id)
        snapshotItems = messages.reduce(0) { $0 + $1.blocks.count }
    }
    /// flushPendingSnapshotIfNeeded(caller:) — L2536
    func flushPendingSnapshotIfNeeded(caller: String) {
        guard hasPendingSnapshot else { return }
        hasPendingSnapshot = false
        flushCallers.append(caller)
        if let msgs = vm?.messages { applySnapshot(messages: msgs, caller: "flushPending(\(caller))") }
    }
    /// bind4b — lastMessageBlockCountSub sink (~L2520)
    func blockCountChanged() {
        guard let vm else { return }
        if vm.streamingUIUpdatesSuspended {
            hasPendingSnapshot = true
            return
        }
        applySnapshot(messages: vm.messages, caller: "bind4b-blockCount")
    }
    /// STREAM-END terminal rescue (~L2497)
    func streamEnded() {
        if hasPendingSnapshot, vm?.streamingUIUpdatesSuspended != true {
            flushPendingSnapshotIfNeeded(caller: "stream-end")
        }
        // else: left pending (still suspended) — content in DB, UI frozen until suspend-resume
    }
    /// vm.onStreamingUIUpdatesResumed hook (~L1952) — post-b5088f72c: no isProcessing gate.
    func onResumed() {
        guard hasPendingSnapshot else { return }
        flushPendingSnapshotIfNeeded(caller: "suspend-resume")
    }
    /// The pre-b5088f72c hook.
    func onResumed_preFix() {
        guard hasPendingSnapshot, vm?.isProcessing == false else { return }
        flushPendingSnapshotIfNeeded(caller: "suspend-resume")
    }
    /// retrySnapshotReloadSignal sink (~L1974)
    func retrySnapshotReload() {
        guard let vm else { return }
        applySnapshot(messages: vm.messages, caller: "retrySnapshotReload")
    }
}

/// The view-model half.
final class VM {
    static var activeSessionId: String? = nil
    let sessionId: String
    var messages: [Message] = []
    var agentHistoryParts = 0                          // what the DB / model-facing history holds
    private(set) var streamingUIUpdatesSuspended = false
    var transitionSuspended = false
    var appActive = true
    var isProcessing = false { didSet { if oldValue == true && isProcessing == false { loopEndDidSet() } } }
    var needsSnapshotReloadOnResume = false
    var onStreamingUIUpdatesResumed: (() -> Void)?
    var retrySnapshotReloadSignalCount = 0
    var postedDetachedLoopEnd = false
    var subscriber: Coordinator?                      // nil = no CollectionView bound to this VM
    var lateLoopEndFired = 0                           // false loop-ends fired mid-loop (512fb88aa)

    init(sessionId: String) { self.sessionId = sessionId }

    private func sendRetrySnapshotReload() {
        retrySnapshotReloadSignalCount += 1
        subscriber?.retrySnapshotReload()
    }

    /// isProcessing didSet, the "agent loop finished" branch (AIChatViewModel.swift ~L860-935).
    private func loopEndDidSet() {
        let inTransitionWindow = transitionSuspended
        if !inTransitionWindow,
           !streamingUIUpdatesSuspended,
           appActive,
           Self.activeSessionId == sessionId {
            sendRetrySnapshotReload()
        } else {
            needsSnapshotReloadOnResume = true        // 017bc8494 defect 3: set in every deferral case
        }
        if Self.activeSessionId != sessionId {
            postedDetachedLoopEnd = true               // .sessionAgentLoopDidEnd
        }
    }

    /// clearStreamingUIUpdatesSuspended() — L1538, the single chokepoint.
    private func clearStreamingUIUpdatesSuspended() {
        guard streamingUIUpdatesSuspended else { return }
        streamingUIUpdatesSuspended = false
        onStreamingUIUpdatesResumed?()
        if needsSnapshotReloadOnResume {
            needsSnapshotReloadOnResume = false
            sendRetrySnapshotReload()
        }
    }
    /// setStreamingUIUpdatesSuspended(_:) — L7420 (background / sheet)
    func setStreamingUIUpdatesSuspended(_ suspended: Bool) {
        guard streamingUIUpdatesSuspended != suspended else { return }
        if suspended { streamingUIUpdatesSuspended = true } else { clearStreamingUIUpdatesSuspended() }
    }
    /// setSuspendedForTransition(_:) — L7435 (session-switch animation window)
    func setSuspendedForTransition(_ suspended: Bool) {
        guard transitionSuspended != suspended else { return }
        transitionSuspended = suspended
        if suspended { streamingUIUpdatesSuspended = true } else { clearStreamingUIUpdatesSuspended() }
    }
    /// didBecomeActive observer — L330-360, post-017bc8494 (gated on the active session).
    func didBecomeActive() {
        appActive = true
        let gatePassed = Self.activeSessionId == sessionId
        guard needsSnapshotReloadOnResume else { return }
        guard gatePassed else { return }               // keep the flag for consumeDeferredSnapshotIfNeeded
        needsSnapshotReloadOnResume = false
        sendRetrySnapshotReload()
    }
    /// The pre-017bc8494 observer: consumed the flag with no active-session check.
    func didBecomeActive_preFix() {
        appActive = true
        guard needsSnapshotReloadOnResume else { return }
        needsSnapshotReloadOnResume = false
        sendRetrySnapshotReload()                      // fires into the void when no subscriber
    }
    /// consumeDeferredSnapshotIfNeeded() — L1561 (onAppear re-use of a cached VM)
    func consumeDeferredSnapshotIfNeeded() {
        guard needsSnapshotReloadOnResume else { return }
        needsSnapshotReloadOnResume = false
        sendRetrySnapshotReload()
    }

    // The agent loop, reduced to "a block landed".
    func appendBlock(_ content: String) {
        agentHistoryParts += 1
        guard let last = messages.last else { return }
        last.blocks.append(content)
        subscriber?.blockCountChanged()
    }

    /// compactBefore's isProcessing handling (+Compaction.swift L740-761).
    func compactBefore(fixed: Bool) {
        let wasProcessingOnEntry = isProcessing
        isProcessing = true
        // … compaction work …
        if fixed {
            isProcessing = wasProcessingOnEntry        // 512fb88aa
        } else {
            isProcessing = false                       // PRE-FIX: `defer { isProcessing = false }`
        }
    }
}

func bind(_ vm: VM) -> Coordinator {
    let c = Coordinator()
    c.vm = vm
    vm.subscriber = c
    vm.onStreamingUIUpdatesResumed = { [weak c] in c?.onResumed() }
    c.applySnapshot(messages: vm.messages, caller: "bind")
    return c
}

func newRun(_ vm: VM) { vm.messages.append(Message()); vm.isProcessing = true }

// MARK: - [1] Background during a multi-tool loop

print("\n[1] Three tool blocks land while backgrounded → the list shows three after foreground")
do {
    VM.activeSessionId = "S1"
    let vm = VM(sessionId: "S1"); let c = bind(vm)
    newRun(vm)
    vm.appendBlock("text")
    checkEq("foreground streaming applies live", c.snapshotItems, 1)

    vm.appActive = false
    vm.setStreamingUIUpdatesSuspended(true)             // suspendAllStreamingUI on .inactive
    vm.appendBlock("shell_execute"); vm.appendBlock("read_file"); vm.appendBlock("browser_use")
    checkEq("while suspended the snapshot is frozen", c.snapshotItems, 1)
    check("…and the deferral is recorded", c.hasPendingSnapshot)

    // Foreground: the loop is STILL processing (b5088f72c).
    vm.appActive = true
    vm.setStreamingUIUpdatesSuspended(false)
    checkEq("foreground flush applies the deferred snapshot", c.snapshotItems, 4)
    checkEq("…through the suspend-resume caller", c.flushCallers, ["suspend-resume"])
    check("nothing left pending", !c.hasPendingSnapshot)
    checkEq("snapshot matches agentHistory", c.snapshotItems, vm.agentHistoryParts)

    // PRE-FIX: the hook was gated on isProcessing == false → frozen for minutes.
    let pre = VM(sessionId: "S1"); let pc = bind(pre)
    pre.onStreamingUIUpdatesResumed = { [weak pc] in pc?.onResumed_preFix() }
    newRun(pre)
    pre.setStreamingUIUpdatesSuspended(true)
    pre.appendBlock("a"); pre.appendBlock("b")
    pre.setStreamingUIUpdatesSuspended(false)
    check("PRE-b5088f72c: the resume flush was skipped while the loop ran", pc.hasPendingSnapshot && pc.snapshotItems == 0)

    // Subsequent blocks after the resume render live again.
    vm.appendBlock("final text")
    checkEq("blocks after the resume apply live", c.snapshotItems, 5)
}

print("\n[1b] Stream ends while still suspended → left pending, flushed on resume (e0526fd90)")
do {
    VM.activeSessionId = "S1"
    let vm = VM(sessionId: "S1"); let c = bind(vm)
    newRun(vm)
    vm.setStreamingUIUpdatesSuspended(true)             // e.g. a tool sheet is presented
    vm.appendBlock("t1"); vm.appendBlock("t2")
    c.streamEnded()                                      // terminal rescue runs while STILL suspended
    check("stream-end leaves the snapshot pending", c.hasPendingSnapshot && c.snapshotItems == 0)
    vm.isProcessing = false                              // loop end while suspended → deferred flag
    check("loop end while suspended sets needsSnapshotReloadOnResume", vm.needsSnapshotReloadOnResume)
    vm.setStreamingUIUpdatesSuspended(false)             // sheet dismissed
    checkEq("resume flushes the pending snapshot", c.snapshotItems, 2)
    check("…and the deferred loop-end replay also fired", vm.retrySnapshotReloadSignalCount == 1 && !vm.needsSnapshotReloadOnResume)

    // Stream-end while NOT suspended flushes directly.
    let vm2 = VM(sessionId: "S1"); let c2 = bind(vm2)
    newRun(vm2)
    vm2.setStreamingUIUpdatesSuspended(true); vm2.appendBlock("x"); vm2.setStreamingUIUpdatesSuspended(true)
    vm2.setStreamingUIUpdatesSuspended(false)
    c2.streamEnded()
    check("stream-end with nothing pending is a no-op", !c2.hasPendingSnapshot && c2.snapshotItems == 1)
}

// MARK: - [2] In-loop compaction mid-stream

print("\n[2] In-loop compaction mid-stream does not fire a false loop-end (512fb88aa)")
do {
    VM.activeSessionId = "S1"
    let vm = VM(sessionId: "S1"); let c = bind(vm)
    newRun(vm)
    vm.appendBlock("round 1")
    let signalsBefore = vm.retrySnapshotReloadSignalCount
    vm.compactBefore(fixed: true)
    check("isProcessing is still true after a mid-loop compaction", vm.isProcessing)
    checkEq("no loop-end replay fired mid-loop", vm.retrySnapshotReloadSignalCount, signalsBefore)
    vm.appendBlock("round 2"); vm.appendBlock("round 3")
    checkEq("blocks after the compaction still reach the list", c.snapshotItems, 3)
    checkEq("snapshot matches agentHistory", c.snapshotItems, vm.agentHistoryParts)

    // PRE-FIX: `defer { isProcessing = false }` fired the loop-end didSet mid-loop.
    let pre = VM(sessionId: "S1"); _ = bind(pre)
    newRun(pre)
    pre.appendBlock("round 1")
    pre.compactBefore(fixed: false)
    check("PRE-512fb88aa: the compaction flipped isProcessing off mid-loop", !pre.isProcessing)
    check("PRE-512fb88aa: …and fired the loop-end replay early", pre.retrySnapshotReloadSignalCount == 1)
    // From there sync-driven reloads are no longer deferred; the real loop end
    // is a false→false no-op, so nothing replays the final blocks.
    let before = pre.retrySnapshotReloadSignalCount
    pre.isProcessing = false
    checkEq("PRE-FIX: the real loop end is a no-op (false→false)", pre.retrySnapshotReloadSignalCount, before)

    // An idle compaction (not mid-loop) still behaves exactly as before.
    let idle = VM(sessionId: "S1"); _ = bind(idle)
    idle.compactBefore(fixed: true)
    check("an idle compaction ends with isProcessing false", !idle.isProcessing)
}

// MARK: - [3] Loop ends off-screen

print("\n[3] Loop ends while another session is on screen → final snapshot equals agentHistory on return")
do {
    VM.activeSessionId = "S2"                            // the user is looking at S2
    let vm = VM(sessionId: "S1")
    vm.subscriber = nil                                  // no CollectionView bound to S1
    vm.messages.append(Message()); vm.isProcessing = true
    vm.appendBlock("a"); vm.appendBlock("b"); vm.appendBlock("c")
    vm.isProcessing = false                              // detached loop end
    check("loop end without a subscriber defers the replay", vm.needsSnapshotReloadOnResume)
    check("…and posts the detached loop-end notification", vm.postedDetachedLoopEnd)

    // 017bc8494 defect 2: a foreground-return while still on S2 must KEEP the flag.
    vm.appActive = false
    vm.didBecomeActive()
    check("didBecomeActive on a non-active session keeps the flag", vm.needsSnapshotReloadOnResume)
    checkEq("…and does not fire into the void", vm.retrySnapshotReloadSignalCount, 0)

    let pre = VM(sessionId: "S1"); pre.subscriber = nil
    pre.messages.append(Message()); pre.isProcessing = true; pre.appendBlock("a"); pre.isProcessing = false
    pre.didBecomeActive_preFix()
    check("PRE-017bc8494: the flag was consumed with no subscriber (lost)", !pre.needsSnapshotReloadOnResume && pre.retrySnapshotReloadSignalCount == 1)

    // Switch back: onAppear re-uses the cached VM and consumes the deferred flag.
    VM.activeSessionId = "S1"
    let c = bind(vm)                                     // the view binds (applies the bind-time snapshot)
    vm.consumeDeferredSnapshotIfNeeded()
    checkEq("the final snapshot equals agentHistory", c.snapshotItems, vm.agentHistoryParts)
    check("the flag is consumed exactly once", !vm.needsSnapshotReloadOnResume && vm.retrySnapshotReloadSignalCount == 1)
    vm.consumeDeferredSnapshotIfNeeded()
    checkEq("a second consume is a no-op", vm.retrySnapshotReloadSignalCount, 1)

    // Loop end inside the session-switch transition window (defect 3).
    VM.activeSessionId = "S3"
    let t = VM(sessionId: "S3"); let tc = bind(t)
    newRun(t)
    t.setSuspendedForTransition(true)                    // 0.4 s switch animation
    t.appendBlock("x"); t.appendBlock("y")
    t.isProcessing = false
    check("loop end inside the transition window sets the flag", t.needsSnapshotReloadOnResume)
    t.setSuspendedForTransition(false)
    checkEq("leaving the window flushes + replays", tc.snapshotItems, 2)
    check("flag consumed", !t.needsSnapshotReloadOnResume)

    // Backgrounded (not suspended, e.g. keep-alive streaming) loop end → didBecomeActive replays.
    VM.activeSessionId = "S4"
    let b = VM(sessionId: "S4"); let bc = bind(b)
    newRun(b)
    b.appActive = false
    b.appendBlock("bg1"); b.appendBlock("bg2")
    b.isProcessing = false
    check("backgrounded loop end defers", b.needsSnapshotReloadOnResume)
    b.didBecomeActive()
    checkEq("didBecomeActive on the active session replays", bc.snapshotItems, 2)
    check("…and clears the flag", !b.needsSnapshotReloadOnResume)
}

// MARK: - [4] Source-grep guard: every trigger site still calls into the funnel

print("\n[4] Shipping sources: each known trigger site still reaches the flush funnel")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let vmSrc = source("Agent/Chat/AIChatViewModel.swift")
let cv3 = source("Agent/MessageList/CollectionViewMessageListV3.swift")
let comp = source("Agent/Chat/AIChatViewModel+Compaction.swift")
if vmSrc.isEmpty || cv3.isEmpty || comp.isEmpty {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    func count(_ needle: String, in s: String) -> Int { s.components(separatedBy: needle).count - 1 }
    // VM side
    check("loop-end didSet gates on transition / suspended / active app / active session",
          vmSrc.contains("if !inTransitionWindow,\n                   !streamingUIUpdatesSuspended,\n                   UIApplication.shared.applicationState == .active,\n                   let sid = sessionId, Self.activeSessionId == sid {\n                    retrySnapshotReloadSignal.send()"))
    check("…and sets the deferral flag in every other case",
          vmSrc.contains("} else {\n                    needsSnapshotReloadOnResume = true"))
    check("a detached loop end posts .sessionAgentLoopDidEnd",
          vmSrc.contains("NotificationCenter.default.post(name: .sessionAgentLoopDidEnd, object: sid)"))
    check("clearStreamingUIUpdatesSuspended is the ONLY place the flag is cleared",
          count("\n        streamingUIUpdatesSuspended = false", in: vmSrc) == 1)
    check("…it fires the resume hook and replays the deferred reload",
          vmSrc.contains("streamingUIUpdatesSuspended = false\n        onStreamingUIUpdatesResumed?()\n        if needsSnapshotReloadOnResume {"))
    check("setStreamingUIUpdatesSuspended resumes through the chokepoint",
          vmSrc.contains("flushDeferredStreamingTextUpdateIfNeeded()\n            clearStreamingUIUpdatesSuspended()"))
    check("setSuspendedForTransition resumes through the chokepoint too",
          count("\n            clearStreamingUIUpdatesSuspended()", in: vmSrc) >= 2)
    check("didBecomeActive replay is gated on the active session and keeps the flag otherwise",
          vmSrc.contains("guard needsFlag else { return }\n                guard gatePassed else {"))
    check("consumeDeferredSnapshotIfNeeded exists for the onAppear re-use path",
          vmSrc.contains("func consumeDeferredSnapshotIfNeeded() {") && vmSrc.contains("guard needsSnapshotReloadOnResume else { return }"))
    check("the detached-loop-end observer reloads the on-screen VM",
          vmSrc.contains("await self.reloadMessagesFromDB(reason: \"detachedVMLoopEnd\")"))
    // Coordinator side
    check("block-count sink defers while suspended",
          cv3.contains("if self.vm?.streamingUIUpdatesSuspended == true {") && cv3.contains("self.hasPendingSnapshot = true\n                        return"))
    check("stream-end terminal rescue flushes when not suspended",
          cv3.contains("if self.hasPendingSnapshot, self.vm?.streamingUIUpdatesSuspended != true {\n                            self.flushPendingSnapshotIfNeeded(caller: \"stream-end\")"))
    check("…and logs the left-pending case instead of dropping it", cv3.contains("STREAM-END left pending"))
    if let hookStart = cv3.range(of: "vm.onStreamingUIUpdatesResumed = { [weak self] in")?.lowerBound,
       let hookEnd = cv3.range(of: "vm.retrySnapshotReloadSignal", range: hookStart..<cv3.endIndex)?.lowerBound {
        let hook = String(cv3[hookStart..<hookEnd])
        check("the resume hook flushes the pending snapshot", hook.contains("self.flushPendingSnapshotIfNeeded(caller: \"suspend-resume\")"))
        check("the resume hook is NOT gated on isProcessing (b5088f72c)",
              !hook.contains("isProcessing == false") && !hook.contains("!vm.isProcessing") && !hook.contains("isProcessing != true") && !hook.contains("guard !"))
    } else { check("resume hook located", false) }
    check("retrySnapshotReloadSignal rebuilds the snapshot", cv3.contains("self.applySnapshot(messages: msgs, caller: \"retrySnapshotReload\")"))
    check("flushPendingSnapshotIfNeeded is the single apply funnel for deferred snapshots",
          cv3.contains("fileprivate func flushPendingSnapshotIfNeeded(caller: String = \"?\") {\n            guard hasPendingSnapshot else { return }\n            hasPendingSnapshot = false"))
    for caller in ["sheetDismiss", "settle", "stream-end", "suspend-resume", "updateUIVC-backstop", "flushStreamingLayout"] {
        check("flush caller \"\(caller)\" still exists", cv3.contains("flushPendingSnapshotIfNeeded(caller: \"\(caller)\")"))
    }
    check("cells are configured against the snapshot's generation (c11545f51)", cv3.contains("snapshotMessages"))
    // Compaction
    check("compactBefore restores isProcessing to its entry value (512fb88aa)",
          comp.contains("let wasProcessingOnEntry = isProcessing") && comp.contains("isProcessing = wasProcessingOnEntry"))
    check("…and no longer forces it false",
          !comp.split(separator: "\n").contains { $0.trimmingCharacters(in: .whitespaces) == "defer { isProcessing = false }" })
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
