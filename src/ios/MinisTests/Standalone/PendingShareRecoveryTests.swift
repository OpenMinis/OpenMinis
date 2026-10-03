// [T34] A pending share on disk is consumed on launch without any intent
// extra, and a "Move to…" transfer lands ONLY in its target session — never
// in whichever chat happens to appear next.
//
// Issues: OpenMinis#120 (Move-to: the pop/push transition race let the moved
// content surface in a different session, or never; a second Move-to to the
// same target then did nothing), #10 (launch/restore state). The existing
// MinisTests/ShareBufferTargetingTests.swift is XCTest (the app cannot link
// for a simulator) and covers only the buffer stamping; this file ports the
// disk-record check and the Move-to targeting decision.
//
// Ported verbatim (file:line cited):
//   ShareCoordinator.checkForPendingShare / raisePendingShare / storeBuffer /
//     setBufferTarget / bufferTargets / consumeBuffer / clearBufferIfStale
//     (Shared/ShareCoordinator.swift:52-240)
//   ContentView.processPendingShare (Views/ContentView.swift:4485-4505)
//   ViewModelCache.PendingTransfer + discardPendingTransfer
//     (Agent/Chat/ChatLifecycleSupport.swift:715-785)
//   AIChatView.injectPendingTransferIfNeeded + the Move-to sheet callback
//     (Views/Chat/AIChatView.swift:1082-1130, 1873-1915)
//
// Standalone (`swift PendingShareRecoveryTests.swift`).

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func knownGap(_ label: String, _ holds: Bool) {
    if holds { print("  ✅ \(label) (gap closed)") } else { print("  ⚠️  KNOWN GAP: \(label)") }
}

// MARK: - Ported: the share record + coordinator

struct ShareItem: Equatable { let kind: String; let value: String }
struct PendingShare: Equatable { let items: [ShareItem]; let timestamp: Date }

/// SharedContainerStore, over an in-memory stand-in for the app-group defaults.
final class SharedContainerStore {
    var record: PendingShare?
    var cleanedFiles = 0
    func savePendingShare(_ s: PendingShare) { record = s }
    func loadPendingShare() -> PendingShare? { record }
    func clearPendingShare() { record = nil }
    func cleanSharedFiles() { cleanedFiles += 1 }
}

final class ShareCoordinator {
    static let bufferTTL: TimeInterval = 300
    let disk: SharedContainerStore
    var now: Date = Date()
    var hasPendingShare = false
    var raiseInFlight = false
    var raises = 0
    var toasts: [String] = []
    struct PendingShareBuffer { let share: PendingShare; let bufferedAt: Date; var targetSessionId: String? }
    private(set) var pendingShareBuffer: PendingShareBuffer?
    private(set) var bufferVersion = 0

    init(disk: SharedContainerStore) { self.disk = disk }

    /// raisePendingShare — coalesces while a raise is in flight or pending.
    func raisePendingShare() {
        guard !hasPendingShare, !raiseInFlight else { return }
        raiseInFlight = true
        raises += 1
        // (dismiss immersive covers, 350 ms later:)
        raiseInFlight = false
        hasPendingShare = true
    }

    /// checkForPendingShare — reads the DISK RECORD, nothing else.
    func checkForPendingShare() {
        if let pending = disk.loadPendingShare() {
            let age = now.timeIntervalSince(pending.timestamp)
            if age < 300 {
                raisePendingShare()
            } else {
                disk.clearPendingShare()
                disk.cleanSharedFiles()
            }
        }
    }

    func storeBuffer(_ share: PendingShare) {
        if let existing = pendingShareBuffer {
            var merged = existing.share.items
            for item in share.items where !merged.contains(where: { $0.kind == item.kind && $0.value == item.value }) {
                merged.append(item)
            }
            pendingShareBuffer = PendingShareBuffer(share: PendingShare(items: merged, timestamp: share.timestamp),
                                                    bufferedAt: now, targetSessionId: existing.targetSessionId)
            bufferVersion += 1
            return
        }
        pendingShareBuffer = PendingShareBuffer(share: share, bufferedAt: now, targetSessionId: nil)
        bufferVersion += 1
    }
    func setBufferTarget(_ sessionId: String) {
        guard pendingShareBuffer != nil else { return }
        pendingShareBuffer?.targetSessionId = sessionId
    }
    func bufferTargets(_ sessionId: String?, draftId: String?) -> Bool {
        guard let target = pendingShareBuffer?.targetSessionId else { return true }
        return target == sessionId || target == draftId
    }
    func consumeBuffer() -> PendingShare? {
        guard let buf = pendingShareBuffer else { return nil }
        pendingShareBuffer = nil
        let age = now.timeIntervalSince(buf.bufferedAt)
        guard age < Self.bufferTTL else {
            disk.cleanSharedFiles()
            toasts.append("Shared content expired. Please share again.")
            return nil
        }
        return buf.share
    }
    func clearBufferIfStale() {
        guard let buf = pendingShareBuffer else { return }
        if now.timeIntervalSince(buf.bufferedAt) >= Self.bufferTTL {
            pendingShareBuffer = nil
            disk.cleanSharedFiles()
        }
    }
}

/// ContentView.processPendingShare (Views/ContentView.swift:4485-4505).
func processPendingShare(_ c: ShareCoordinator) -> String {
    guard let pending = c.disk.loadPendingShare() else {
        let outcome = c.pendingShareBuffer != nil ? "duplicate-raise-noop" : "no-data"
        c.hasPendingShare = false
        return outcome
    }
    c.disk.clearPendingShare()
    c.hasPendingShare = false
    c.storeBuffer(pending)
    return "buffered"
}

/// The root view's onAppear (MinisApp.swift:423) → ContentView .task
/// (ContentView.swift:2079-2083): a cold launch with NO url / extra.
func coldLaunch(_ c: ShareCoordinator) -> String {
    c.checkForPendingShare()                      // onAppear
    guard c.hasPendingShare else { return "nothing-pending" }
    return processPendingShare(c)                 // .task: processing pending share
}

// MARK: - Ported: Move-to transfer

struct Attachment: Equatable { let name: String }

struct PendingTransfer {
    let targetId: String
    let inputText: String
    let attachments: [Attachment]
    let createdAt: Date
    static let staleAfter: TimeInterval = 30
    func isStale(now: Date) -> Bool { now.timeIntervalSince(createdAt) > Self.staleAfter }
}

final class ChatVM {
    let sessionId: String?
    var inputText = ""
    var attachments: [Attachment] = []
    init(sessionId: String?) { self.sessionId = sessionId }
}

final class ViewModelCache {
    static var pendingTransfer: PendingTransfer?
    static var discardReasons: [String] = []
    static func discardPendingTransfer(reason: String) {
        guard pendingTransfer != nil else { return }
        pendingTransfer = nil
        discardReasons.append(reason)
    }
    static func reset() { pendingTransfer = nil; discardReasons = [] }
}

/// The Move-to sheet callback (AIChatView.swift:1084-1130): stash, clear the
/// source composer, and arm the stranded-restore. Returns the restore closure
/// the production code schedules after `staleAfter`.
func moveTo(_ targetId: String, from vm: ChatVM, now: Date) -> (stash: PendingTransfer, restoreIfStranded: () -> Bool) {
    let movedText = vm.inputText
    let movedAttachments = vm.attachments
    let stash = PendingTransfer(targetId: targetId, inputText: movedText, attachments: movedAttachments, createdAt: now)
    ViewModelCache.pendingTransfer = stash
    vm.inputText = ""
    vm.attachments.removeAll()
    let restore: () -> Bool = {
        guard let stranded = ViewModelCache.pendingTransfer,
              stranded.targetId == targetId,
              stranded.createdAt == stash.createdAt else { return false }
        ViewModelCache.pendingTransfer = nil
        if vm.inputText.isEmpty { vm.inputText = movedText }
        else if !movedText.isEmpty { vm.inputText += "\n" + movedText }
        vm.attachments.append(contentsOf: movedAttachments)
        return true
    }
    return (stash, restore)
}

enum InjectOutcome: Equatable { case notTarget, discardedStale, injected }

/// AIChatView.injectPendingTransferIfNeeded (AIChatView.swift:1873-1915), post-#120.
func injectPendingTransferIfNeeded(_ vm: ChatVM, draftId: String?, now: Date) -> InjectOutcome {
    guard let transfer = ViewModelCache.pendingTransfer else { return .notTarget }
    let isTarget = transfer.targetId == vm.sessionId || transfer.targetId == draftId
    guard isTarget else {
        if transfer.isStale(now: now) {
            ViewModelCache.discardPendingTransfer(reason: "stale, target \(transfer.targetId) never appeared")
            return .discardedStale
        }
        return .notTarget
    }
    guard !transfer.isStale(now: now) else {
        ViewModelCache.discardPendingTransfer(reason: "target \(transfer.targetId) appeared too late")
        return .discardedStale
    }
    ViewModelCache.pendingTransfer = nil
    if !vm.attachments.isEmpty { vm.attachments.removeAll() }
    if !transfer.inputText.isEmpty {
        if !vm.inputText.isEmpty { vm.inputText += "\n" }
        vm.inputText += transfer.inputText
    }
    vm.attachments = transfer.attachments
    return .injected
}

/// The pre-#120 injector: whichever chat appeared first took the stash.
func injectPendingTransifer_preFix(_ vm: ChatVM) -> InjectOutcome {
    guard let transfer = ViewModelCache.pendingTransfer else { return .notTarget }
    ViewModelCache.pendingTransfer = nil
    vm.inputText += transfer.inputText
    vm.attachments = transfer.attachments
    return .injected
}

// MARK: - [1] Cold launch consumes the disk record without an extra

print("\n[1] A cold launch with no URL / extra still consumes the share record on disk")
do {
    let disk = SharedContainerStore()
    let c = ShareCoordinator(disk: disk)
    let t0 = Date()
    c.now = t0
    disk.savePendingShare(PendingShare(items: [ShareItem(kind: "attachment", value: "report.pdf")], timestamp: t0.addingTimeInterval(-5)))
    checkEq("onAppear → check → raise → buffered", coldLaunch(c), "buffered")
    check("the disk record is cleared once buffered", disk.loadPendingShare() == nil)
    check("hasPendingShare is lowered after processing", !c.hasPendingShare)
    checkEq("the buffer holds the shared item", c.pendingShareBuffer?.share.items.map(\.value), ["report.pdf"])
    check("an unstamped buffer may be taken by the launch flow's chosen session", c.bufferTargets("any", draftId: nil))
    checkEq("consume returns it", c.consumeBuffer()?.items.count, 1)

    // Nothing on disk: no raise.
    let empty = ShareCoordinator(disk: SharedContainerStore())
    checkEq("no record → nothing pending", coldLaunch(empty), "nothing-pending")
    checkEq("…and no raise happened", empty.raises, 0)

    // A stale record (> 300 s) is discarded, files cleaned, never raised.
    let staleDisk = SharedContainerStore()
    let s = ShareCoordinator(disk: staleDisk)
    s.now = t0
    staleDisk.savePendingShare(PendingShare(items: [ShareItem(kind: "text", value: "old")], timestamp: t0.addingTimeInterval(-301)))
    checkEq("stale record → not raised", coldLaunch(s), "nothing-pending")
    check("…record cleared and files cleaned", staleDisk.loadPendingShare() == nil && staleDisk.cleanedFiles == 1)

    // A duplicate raise (deep link + onAppear) after the record was consumed is a no-op.
    let d = ShareCoordinator(disk: SharedContainerStore())
    d.now = t0
    d.disk.savePendingShare(PendingShare(items: [ShareItem(kind: "text", value: "x")], timestamp: t0))
    _ = coldLaunch(d)
    d.raisePendingShare()                                 // minis://share arrives late
    checkEq("second raise with the buffer already staged is a no-op", processPendingShare(d), "duplicate-raise-noop")
    checkEq("the buffer is untouched", d.pendingShareBuffer?.share.items.count, 1)
    check("hasPendingShare lowered again", !d.hasPendingShare)

    // Coalescing: two raises before processing count once.
    let r = ShareCoordinator(disk: SharedContainerStore())
    r.raisePendingShare(); r.raisePendingShare()
    checkEq("raises coalesce while pending", r.raises, 1)

    // Buffer TTL: a share nobody consumed for 5 minutes expires with a toast.
    let ttl = ShareCoordinator(disk: SharedContainerStore())
    ttl.now = t0
    ttl.storeBuffer(PendingShare(items: [ShareItem(kind: "text", value: "late")], timestamp: t0))
    ttl.now = t0.addingTimeInterval(301)
    check("an expired buffer is dropped with a toast", ttl.consumeBuffer() == nil && ttl.toasts.count == 1)

    // The check is disk-only: nothing about an intent extra is consulted.
    knownGap("every foreground return (scenePhase .active) re-checks the disk record, not only onAppear / minis://share (spec: check on every return to the foreground)",
             false)
}

// MARK: - [2] Move-to targets the chosen session

print("\n[2] Move to… buffers content for the TARGET, not for whichever session appears next")
do {
    ViewModelCache.reset()
    let t0 = Date()
    let source = ChatVM(sessionId: "A")
    source.inputText = "shared text"; source.attachments = [Attachment(name: "f.zip")]
    let (stash, restore) = moveTo("B", from: source, now: t0)
    check("the source composer is cleared at once", source.inputText.isEmpty && source.attachments.isEmpty)
    checkEq("the stash is addressed to B", ViewModelCache.pendingTransfer?.targetId, "B")

    // The #120 race: the push to B is swallowed, the user opens C instead.
    let other = ChatVM(sessionId: "C")
    checkEq("session C is not the target → leaves the stash alone", injectPendingTransferIfNeeded(other, draftId: nil, now: t0.addingTimeInterval(2)), .notTarget)
    check("C's composer stays empty", other.inputText.isEmpty && other.attachments.isEmpty)
    check("the stash is still pending for B", ViewModelCache.pendingTransfer?.targetId == "B")

    // B finally appears: it takes the content.
    let target = ChatVM(sessionId: "B")
    target.attachments = [Attachment(name: "stale-in-B.png")]
    checkEq("B injects the transfer", injectPendingTransferIfNeeded(target, draftId: nil, now: t0.addingTimeInterval(5)), .injected)
    checkEq("B's composer has the moved text", target.inputText, "shared text")
    checkEq("B's stale attachments are replaced by the moved ones", target.attachments, [Attachment(name: "f.zip")])
    check("the stash is consumed", ViewModelCache.pendingTransfer == nil)
    check("the stranded-restore does NOT fire after a successful consume", restore() == false && source.inputText.isEmpty)
    _ = stash

    // PRE-#120: any session took it.
    ViewModelCache.reset()
    let src2 = ChatVM(sessionId: "A"); src2.inputText = "again"
    _ = moveTo("B", from: src2, now: t0)
    let c2 = ChatVM(sessionId: "C")
    checkEq("PRE-FIX: session C swallowed the transfer", injectPendingTransifer_preFix(c2), .injected)
    checkEq("PRE-FIX: …and showed it in the wrong composer", c2.inputText, "again")

    // A draft target matches by draftId before it has a real id.
    ViewModelCache.reset()
    let src3 = ChatVM(sessionId: "A"); src3.inputText = "to a draft"
    _ = moveTo("__new__D", from: src3, now: t0)
    let draft = ChatVM(sessionId: nil)
    checkEq("a draft target matches by draftId", injectPendingTransferIfNeeded(draft, draftId: "__new__D", now: t0), .injected)

    // Target never appears: after 30 s the content goes back to the source.
    ViewModelCache.reset()
    let src4 = ChatVM(sessionId: "A"); src4.inputText = "lost?"; src4.attachments = [Attachment(name: "g.zip")]
    let (_, restore4) = moveTo("B", from: src4, now: t0)
    src4.inputText = "typed meanwhile"
    check("stranded restore fires when the stash is still pending", restore4())
    checkEq("…and appends the moved text below what was typed since", src4.inputText, "typed meanwhile\nlost?")
    checkEq("…and returns the attachments", src4.attachments, [Attachment(name: "g.zip")])
    check("the stash is gone", ViewModelCache.pendingTransfer == nil)

    // A stale stash does not poison the next Move-to to the same target (#120 secondary damage).
    ViewModelCache.reset()
    let src5 = ChatVM(sessionId: "A"); src5.inputText = "first"
    _ = moveTo("B", from: src5, now: t0)
    let late = ChatVM(sessionId: "B")
    checkEq("target appearing after 30 s discards the stale stash", injectPendingTransferIfNeeded(late, draftId: nil, now: t0.addingTimeInterval(31)), .discardedStale)
    check("…with the reason recorded", ViewModelCache.discardReasons.last == "target B appeared too late")
    let src6 = ChatVM(sessionId: "A"); src6.inputText = "second"
    _ = moveTo("B", from: src6, now: t0.addingTimeInterval(40))
    let b2 = ChatVM(sessionId: "B")
    checkEq("the next Move-to to the same target works", injectPendingTransferIfNeeded(b2, draftId: nil, now: t0.addingTimeInterval(41)), .injected)
    checkEq("…with the new content", b2.inputText, "second")

    // A non-target appearing after 30 s cleans up too.
    ViewModelCache.reset()
    let src7 = ChatVM(sessionId: "A"); src7.inputText = "x"
    _ = moveTo("B", from: src7, now: t0)
    checkEq("a non-target after 30 s discards the stale stash", injectPendingTransferIfNeeded(ChatVM(sessionId: "C"), draftId: nil, now: t0.addingTimeInterval(31)), .discardedStale)
    check("…reason names the target that never appeared", ViewModelCache.discardReasons.last == "stale, target B never appeared")
}

// MARK: - [3] Share buffer targeting (the XCTest contract, re-pinned here)

print("\n[3] A stamped share buffer is consumed only by its destination")
do {
    let c = ShareCoordinator(disk: SharedContainerStore())
    c.storeBuffer(PendingShare(items: [ShareItem(kind: "attachment", value: "f.zip")], timestamp: Date()))
    c.setBufferTarget("87B79110-target")
    check("another session is refused", c.bufferTargets("some-other-session", draftId: nil), false)
    check("the destination is accepted", c.bufferTargets("87B79110-target", draftId: nil))
    c.storeBuffer(PendingShare(items: [ShareItem(kind: "attachment", value: "b.zip")], timestamp: Date()))
    check("a merge keeps the destination", c.bufferTargets("87B79110-target", draftId: nil) && !c.bufferTargets("x", draftId: nil))
    checkEq("both shares survive the merge", c.consumeBuffer()?.items.count, 2)
    c.setBufferTarget("session-X")
    check("stamping without a buffer invents nothing", c.consumeBuffer() == nil)
}

// MARK: - [4] Drift guards

print("\n[4] Shipping sources match these ports")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let coord = source("Shared/ShareCoordinator.swift")
let app = source("MinisApp.swift")
let cv = source("Views/ContentView.swift")
let chat = source("Views/Chat/AIChatView.swift")
let life = source("Agent/Chat/ChatLifecycleSupport.swift")
let deep = source("Shared/DeepLinkRouter.swift")
if [coord, app, cv, chat, life, deep].contains(where: { $0.isEmpty }) {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    check("checkForPendingShare reads the disk record unconditionally",
          coord.contains("func checkForPendingShare() {") && coord.contains("if let pending = SharedContainerStore.loadPendingShare() {"))
    check("…with the 300 s staleness cut", coord.contains("if age < 300 {"))
    check("the root view calls it on appear with no argument", app.contains("shareCoordinator.checkForPendingShare()"))
    check("minis://share funnels through raisePendingShare", deep.contains("case \"share\":") && deep.contains("shareCoordinator.raisePendingShare()"))
    check("raisePendingShare coalesces", coord.contains("guard !hasPendingShare, !raiseInFlight else {"))
    check("processPendingShare treats a consumed record + staged buffer as a duplicate raise",
          cv.contains("record already consumed, buffer staged (duplicate raise) — no-op"))
    check("…and clears the record before buffering",
          cv.contains("SharedContainerStore.clearPendingShare()\n        shareCoordinator.hasPendingShare = false\n        shareCoordinator.storeBuffer(pending)"))
    check("bufferTargets accepts unstamped, else session or draft id",
          coord.contains("guard let target = pendingShareBuffer?.targetSessionId else { return true }")
          && coord.contains("return target == sessionId || target == draftId"))
    check("a merge keeps the existing target", coord.contains("targetSessionId: existing.targetSessionId)"))
    check("PendingTransfer carries its target and a 30 s staleness",
          life.contains("let targetId: String") && life.contains("static let staleAfter: TimeInterval = 30"))
    check("only the target consumes the transfer",
          chat.contains("let isTarget = transfer.targetId == vm.sessionId || transfer.targetId == draftId\n        guard isTarget else {"))
    check("a stale stash is discarded by whoever sees it", chat.contains("ViewModelCache.discardPendingTransfer(reason: \"stale, target \\(transfer.targetId) never appeared\")")
          && chat.contains("ViewModelCache.discardPendingTransfer(reason: \"target \\(transfer.targetId) appeared too late\")"))
    check("the Move-to sheet stamps the target on the stash", chat.contains("targetId: targetId,\n                    inputText: movedText,"))
    check("the stranded-restore checks target AND createdAt", chat.contains("stranded.targetId == targetId,\n                          stranded.createdAt == stash.createdAt"))
    check("the .active handler does not re-check the disk record (documents the KNOWN GAP above)",
          !app.components(separatedBy: "case .active:").dropFirst().joined().prefix(4000).contains("checkForPendingShare"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
