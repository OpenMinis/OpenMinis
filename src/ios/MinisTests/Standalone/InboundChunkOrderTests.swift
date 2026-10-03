// Guards two claims made by the Tier-1 iCloud sync fixes:
//
//   1e85ef3c1 (T-icloud-sync-tier1-parents-first) — "apply inbound batches
//             parents-first so a message never lands before its session".
//   019428169 (T-icloud-sync-tier1-cursor-after-apply) — "commit the fetch
//             cursor only after the last chunk is applied … Apply order is
//             preserved by SyncCore (one MainActor task per chunk, in hand-off
//             order), so 'last chunk applied' implies all earlier chunks applied."
//
// Both hold INSIDE one SyncInboundBatch. They do not hold ACROSS batches:
// fetchRecentV2 (and the CKSyncEngine path) slice the result into 50-record
// batches and call processInbound once per slice; each call starts its own
// `Task { @MainActor }`, and every record is applied through
// `await SyncCoreHydrators.shared.mergeRemote(record)` — a hop to the
// `actor ChatStore` — with a 50 ms `Task.sleep` after every 25 records. Every
// one of those awaits is a suspension point, so the per-slice tasks
// INTERLEAVE on the main actor. Consequences reproduced below with the real
// Swift concurrency runtime:
//
//   A. A poll returning >25 SessionV2 plus their messages: slice 2's messages
//      run while slice 1 is still on its first 25 sessions, hit
//      mergeRemoteMessage's `guard sessionExists` and are dropped
//      ("SKIP (session row not present yet)"). The last slice's onApplied then
//      commits the cursor past them, so the poll never re-fetches them.
//   B. The last (smallest) slice finishes first, so the cursor commit fires
//      while earlier slices are still being written — the crash window
//      019428169 set out to close is still open.
//
// This file models processInbound/fetchRecentV2 faithfully (source invariants
// at the bottom pin every structural fact the model depends on) and asserts
// the invariant the commits promised. Cases A and B failed on the pre-fix
// shape (reproduced below with `chained: false`, which must STILL fail —
// that proves the model can see the bug).
//
// Fix [T-icloud-inbound-serial-apply]: processInbound chains each apply task
// onto the previous one (`inboundTail`), so batches apply strictly in
// hand-off order. The model's `chained: true` mirrors that shape.
//
// Also covered (case C): incremental polls used to stop at the first
// 200-row page. The query sorts newest-first, so a busy window lost its
// OLDEST rows; withholding the cursor would not help (the next poll gets
// the same newest 200). The fix follows queryCursor up to
// `incrementalPageCap` pages.
//
// Run: cd src/ios/MinisTests/Standalone && swift InboundChunkOrderTests.swift

import Foundation

var failures = 0
func check(_ label: String, _ ok: Bool, _ detail: @autoclosure () -> String = "") {
    if ok { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\(detail().isEmpty ? "" : " — \(detail())")"); failures += 1 }
}

// MARK: - Model

struct Rec { let type: String; let id: String; let parent: String? }

/// Stand-in for `actor ChatStore`: mergeRemoteMessage refuses a message whose
/// session row does not exist yet.
actor Store {
    var sessions = Set<String>()
    var messages = Set<String>()
    var skipped: [String] = []
    var appliedCount = 0
    func merge(_ r: Rec) {
        appliedCount += 1
        if r.type == "SessionV2" { sessions.insert(r.id); return }
        guard let p = r.parent, sessions.contains(p) else { skipped.append(r.id); return }
        messages.insert(r.id)
    }
    func snapshot() -> (applied: Int, skipped: [String], messages: Int) { (appliedCount, skipped, messages.count) }
}

let rank: [String: Int] = ["FolderV2": 0, "SessionV2": 1, "MessageV2": 2, "CompactMarkerV2": 2, "SessionFileV2": 2]
func parentsFirst(_ records: [Rec]) -> [Rec] {
    guard records.count > 1, records.contains(where: { rank[$0.type] != nil }) else { return records }
    return records.enumerated().sorted { a, b in
        let ra = rank[a.element.type] ?? 3, rb = rank[b.element.type] ?? 3
        return ra != rb ? ra < rb : a.offset < b.offset
    }.map(\.element)
}

@MainActor final class Core {
    let store: Store
    let chained: Bool
    var inboundTail: Task<Void, Never>?
    init(store: Store, chained: Bool) { self.store = store; self.chained = chained }
    /// Mirrors SyncCore.processInbound: one MainActor task per call, 25-record
    /// sub-chunks, an awaited hop per record, 50 ms yield, onApplied at the end.
    /// `chained` = the fix: each task awaits the previous one first.
    func processInbound(_ records: [Rec], onApplied: (() -> Void)?) {
        let previous = chained ? inboundTail : nil
        inboundTail = Task { @MainActor in
            await previous?.value
            let all = parentsFirst(records)
            var i = 0
            while i < all.count {
                let end = min(i + 25, all.count)
                for j in i..<end { await store.merge(all[j]) }
                i = end
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
            onApplied?()
        }
    }
    /// Mirrors fetchRecentV2's hand-off: 50-record slices, completion on the
    /// last slice only.
    func handOff(_ portables: [Rec], slice: Int = 50, onCommit: @escaping () -> Void) {
        var i = 0
        while i < portables.count {
            let end = min(i + slice, portables.count)
            let isLast = end >= portables.count
            processInbound(Array(portables[i..<end]), onApplied: isLast ? onCommit : nil)
            i = end
        }
    }
}

/// Poll order: fetchRecentV2 appends per type in typesAndKeys order, so every
/// SessionV2 precedes every MessageV2.
func pollResult(sessions: Int, messagesPerSession: Int) -> [Rec] {
    var out: [Rec] = (0..<sessions).map { Rec(type: "SessionV2", id: "S\($0)", parent: nil) }
    for m in 0..<messagesPerSession {
        for s in 0..<sessions { out.append(Rec(type: "MessageV2", id: "M\(s)-\(m)", parent: "S\(s)")) }
    }
    return out
}

struct Outcome { let skipped: [String]; let appliedAtCommit: Int; let total: Int }

@MainActor func run(_ portables: [Rec], singleBatch: Bool, chained: Bool = true) async -> Outcome {
    let store = Store()
    let core = Core(store: store, chained: chained)
    var appliedAtCommit = -1
    var committed = false
    let commit = {
        Task { let s = await store.snapshot(); appliedAtCommit = s.applied; committed = true }
    }
    if singleBatch { core.processInbound(portables, onApplied: { _ = commit() }) }
    else { core.handOff(portables, onCommit: { _ = commit() }) }
    // Wait until the commit fired AND every record was applied.
    for _ in 0..<400 {
        try? await Task.sleep(nanoseconds: 10_000_000)
        let s = await store.snapshot()
        if committed && s.applied == portables.count { break }
    }
    let s = await store.snapshot()
    return Outcome(skipped: s.skipped, appliedAtCommit: appliedAtCommit, total: portables.count)
}

// MARK: - Cases

print("\n▶️  control: one batch — parentsFirst + onApplied hold (what the commits tested)")
do {
    let p = pollResult(sessions: 30, messagesPerSession: 2)   // 90 records
    let o = await run(p, singleBatch: true)
    check("no message dropped for a missing session", o.skipped.isEmpty, "skipped=\(o.skipped.count)")
    check("cursor commits after every record is applied", o.appliedAtCommit == o.total,
          "applied at commit=\(o.appliedAtCommit)/\(o.total)")
}

print("\n▶️  A: poll with 30 sessions + 60 messages, handed off in 50-record slices")
do {
    let p = pollResult(sessions: 30, messagesPerSession: 2)
    let o = await run(p, singleBatch: false)
    check("no message dropped for a missing session (parents-first must hold across slices)",
          o.skipped.isEmpty, "\(o.skipped.count) message(s) SKIPped, e.g. \(o.skipped.prefix(3))")
    let unchained = await run(p, singleBatch: false, chained: false)
    check("pre-fix shape (independent task per slice) still reproduces the drop",
          !unchained.skipped.isEmpty, "model no longer sees the bug")
}

print("\n▶️  B: small tail slice — cursor must not commit before earlier slices are written")
do {
    // 3 sessions + 99 messages = 102 records → slices of 50, 50, 2.
    let p = pollResult(sessions: 3, messagesPerSession: 33)
    let o = await run(p, singleBatch: false)
    check("cursor commits only after ALL slices are applied",
          o.appliedAtCommit == o.total, "applied at commit=\(o.appliedAtCommit)/\(o.total)")
    let unchained = await run(p, singleBatch: false, chained: false)
    check("pre-fix shape still reproduces the early commit",
          unchained.appliedAtCommit < unchained.total, "model no longer sees the bug")
}

print("\n▶️  B2: empty trailing batch carrying onApplied waits for earlier batches")
do {
    let store = Store()
    let core = Core(store: store, chained: true)
    var appliedAtCommit = -1
    core.processInbound(pollResult(sessions: 5, messagesPerSession: 10), onApplied: nil)   // 55 records
    // Mirrors SyncCore's empty-batch branch: chain the completion on the tail.
    let previous = core.inboundTail
    core.inboundTail = Task { @MainActor in
        await previous?.value
        appliedAtCommit = await store.snapshot().applied
    }
    await core.inboundTail?.value
    check("empty batch completion runs after the earlier batch is applied", appliedAtCommit == 55,
          "applied at commit=\(appliedAtCommit)/55")
}

print("\n▶️  C: incremental poll page cap — newest-first query, 200-row pages")
do {
    // Rows in one window, newest first (index 0 = newest). The query returns
    // them in pages of 200; `pages` pages are followed.
    func poll(_ window: [Int], pages: Int) -> Set<Int> { Set(window.prefix(200 * pages)) }
    let window = Array(0..<450)
    // Pre-fix: one page. Withholding the cursor re-runs the same query, so the
    // oldest 250 rows are never delivered no matter how many polls run.
    var seen = Set<Int>()
    for _ in 0..<5 { seen.formUnion(poll(window, pages: 1)) }
    check("single page never reaches the oldest rows (why withholding the cursor is not a fix)",
          seen.count == 200)
    let paged = poll(window, pages: 25)
    check("following the cursor (cap 25 pages) delivers the whole window", paged.count == window.count)
}

print("\n▶️  boundaries")
do {
    // Exactly one slice (50) — behaves like the single-batch control.
    let p = pollResult(sessions: 10, messagesPerSession: 4)   // 50 records
    let o = await run(p, singleBatch: false)
    check("exactly 50 records (one slice): nothing dropped", o.skipped.isEmpty)
    check("exactly 50 records (one slice): commit after all", o.appliedAtCommit == o.total)
}

// MARK: - Source invariants (the model's structural assumptions)

print("\n▶️  source invariants")
let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let iosRoot = here.deletingLastPathComponent().deletingLastPathComponent()
func src(_ rel: String) -> String { (try? String(contentsOfFile: iosRoot.appendingPathComponent(rel).path, encoding: .utf8)) ?? "" }
let core = src("Agent/Sync/V2/SyncCore.swift")
let transport = src("Agent/Sync/V2/ICloudSharedZoneTransport.swift")
let chatStore = src("Agent/Chat/ChatStore.swift")
let body = core.components(separatedBy: "func processInbound(_ batch: SyncInboundBatch").dropFirst().first?
    .components(separatedBy: "// MARK: - Diagnostics").first ?? ""
check("processInbound starts a MainActor task per call and stores it as the tail",
      body.contains("inboundTail = Task { @MainActor [weak self] in"))
check("processInbound reads the tail before replacing it", body.contains("let previous = inboundTail"))
check("the apply task awaits the previous one before applying anything",
      (body.range(of: "await previous?.value")?.lowerBound ?? body.endIndex)
        < (body.range(of: "let allRecords = Self.parentsFirst")?.lowerBound ?? body.startIndex))
check("an empty batch's onApplied also waits for the tail (no synchronous early commit)",
      !body.contains("else { batch.onApplied?(); return }") && body.contains("await previous.value\n                onApplied()"))
check("inboundTail is declared on SyncCore", core.contains("private var inboundTail: Task<Void, Never>?"))
check("parentsFirst is applied per batch", body.contains("let allRecords = Self.parentsFirst(batch.records)"))
check("each record is an awaited hydrator call", body.contains("await SyncCoreHydrators.shared.mergeRemote(record)"))
check("25-record sub-chunks with a 50 ms yield", body.contains("let chunkSize = 25") && body.contains("Task.sleep(nanoseconds: 50_000_000)"))
check("onApplied fires at the end of the task", body.contains("batch.onApplied?()"))
check("ChatStore is an actor (so every merge is a real suspension point)", chatStore.contains("\nactor ChatStore {"))
check("mergeRemoteMessage drops a message whose session is absent",
      chatStore.contains("mergeRemoteMessage SKIP (session row not present yet)"))
let fetch = transport.components(separatedBy: "private func fetchRecentV2() async {").dropFirst().first ?? ""
check("fetchRecentV2 hands off in 50-record slices", fetch.contains("let chunk = 50"))
check("…with onApplied only on the last slice", fetch.contains("onApplied: (isLast && commitAllowed)"))
check("incremental queries follow queryCursor up to incrementalPageCap pages",
      fetch.contains("let pageCap = paginate ? Int.max : Self.incrementalPageCap")
        && fetch.contains("guard pages < pageCap else")
        && !fetch.contains("guard paginate, let cursor = result.queryCursor"))
check("incrementalPageCap is 25 pages", transport.contains("static let incrementalPageCap = 25"))
check("the query still sorts newest-first (the reason withholding the cursor cannot help)",
      fetch.contains("NSSortDescriptor(key: dateKey, ascending: false)"))
check("poll appends SessionV2 before MessageV2 (typesAndKeys order)",
      (fetch.range(of: "(\"SessionV2\"")?.lowerBound ?? fetch.endIndex) < (fetch.range(of: "(\"MessageV2\"")?.lowerBound ?? fetch.startIndex))

print("")
if failures == 0 { print("✅ InboundChunkOrderTests: all passed") }
else { print("❌ InboundChunkOrderTests: \(failures) failure(s)"); exit(1) }
