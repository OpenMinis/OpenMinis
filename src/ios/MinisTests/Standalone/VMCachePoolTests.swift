// Tests for [T-vmcache-pools] / [T-vmcache-release] / [T-renderer-cache-bounded].
//
// Three coupled changes to session caching:
//   1. eviction releases instead of cancelling, so it can never reach into
//      AgentJobRegistry (the 761be79da failure);
//   2. child (sub agent) VMs get their own LRU pool, so a fan-out cannot evict
//      the conversations the user is working in;
//   3. the per-message renderer cache is bounded and drainable.
//
// Standalone (`swift VMCachePoolTests.swift`) for the same reason as the
// neighbouring files: the MinisTests target has a pre-existing compile break
// and the shipping types pull in the whole app graph. The pool/LRU algorithms
// are reproduced and exercised here; section [5] re-reads the shipping sources
// so the copies cannot drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced: two-pool LRU (ViewModelCache)

enum PoolKind: String { case normal, child }

final class Cache {
    var cache: [String: Int] = [:]          // sessionId -> dummy VM
    var lruOrder: [String] = []
    var poolKinds: [String: PoolKind] = [:]
    var evicted: [String] = []
    var cancelled: [String] = []            // must stay empty for capacity evictions
    /// Sessions the guards refuse to evict.
    var processing: Set<String> = []
    var activeChildrenOf: Set<String> = []
    var foreground: String?

    static let softCap = 6
    static let childSoftCap = 10

    func poolKind(_ s: String) -> PoolKind { poolKinds[s] ?? .normal }

    func getOrCreate(_ s: String, kind: PoolKind = .normal) {
        // [T-vmcache-pool-sticky] Assign only when the session has no tag AND
        // is not already cached — the tag belongs to whoever FIRST cached it.
        if kind == .child, poolKinds[s] == nil, cache[s] == nil { poolKinds[s] = .child }
        if cache[s] == nil { cache[s] = 1 }
        touch(s)
        evictIfOverCap()
    }
    func touch(_ s: String) { lruOrder.removeAll { $0 == s }; lruOrder.append(s) }

    func isEvictable(_ s: String) -> Bool {
        if processing.contains(s) { return false }
        if s == foreground { return false }
        if activeChildrenOf.contains(s) { return false }   // 761be79da guard
        return true
    }
    func evictIfOverCap() {
        evictPool(.normal, cap: Self.softCap)
        evictPool(.child, cap: Self.childSoftCap)
    }
    func evictPool(_ kind: PoolKind, cap: Int) {
        let members = lruOrder.filter { poolKind($0) == kind && cache[$0] != nil }
        guard members.count > cap else { return }
        var overflow = members.count - cap
        for s in members where overflow > 0 {
            guard cache[s] != nil, isEvictable(s) else { continue }
            // RELEASE, never cancel.
            evicted.append(s)
            cache.removeValue(forKey: s); poolKinds.removeValue(forKey: s)
            lruOrder.removeAll { $0 == s }
            overflow -= 1
        }
    }
    var normalCount: Int { cache.keys.filter { poolKind($0) == .normal }.count }
    var childCount: Int { cache.keys.filter { poolKind($0) == .child }.count }
}

print("\n[1] Normal pool holds exactly 6")
let a = Cache()
for i in 1...9 { a.getOrCreate("n\(i)") }
checkEq("resident normals capped at 6", a.normalCount, 6)
checkEq("the 3 oldest were evicted", a.evicted, ["n1", "n2", "n3"])
check("newest survives", a.cache["n9"] != nil)
check("capacity eviction cancelled nothing", a.cancelled.isEmpty)

print("\n[2] Child pool holds 10, independently")
let b = Cache()
for i in 1...14 { b.getOrCreate("c\(i)", kind: .child) }
checkEq("resident children capped at 10", b.childCount, 10)
checkEq("4 oldest children evicted", b.evicted.count, 4)

print("\n[3] Children never spend the normal budget (the point of the split)")
let c = Cache()
for i in 1...6 { c.getOrCreate("n\(i)") }          // fill the normal pool
checkEq("6 normals resident", c.normalCount, 6)
for i in 1...10 { c.getOrCreate("c\(i)", kind: .child) }   // a big fan-out
checkEq("still 6 normals — none evicted by children", c.normalCount, 6)
checkEq("no normal session was evicted", c.evicted.filter { $0.hasPrefix("n") }.count, 0)
checkEq("children resident", c.childCount, 10)
// …and the converse: opening more normals does not evict children.
for i in 7...12 { c.getOrCreate("n\(i)") }
checkEq("children untouched by normal churn", c.childCount, 10)

print("\n[4] Guards still refuse — including the 761be79da one")
let d = Cache()
for i in 1...6 { d.getOrCreate("n\(i)") }
d.activeChildrenOf.insert("n1")     // oldest, but has live sub agents
d.processing.insert("n2")
d.foreground = "n3"
for i in 7...9 { d.getOrCreate("n\(i)") }
check("session with live children NOT evicted", d.evicted.contains("n1"), false)
check("processing session NOT evicted", d.evicted.contains("n2"), false)
check("foreground session NOT evicted", d.evicted.contains("n3"), false)
check("an unprotected older session WAS evicted", d.evicted.contains("n4"))
check("still nothing cancelled", d.cancelled.isEmpty)

print("\n[5] Re-entering a child does not promote it to the normal pool")
let e = Cache()
e.getOrCreate("c1", kind: .child)
e.getOrCreate("c1")                       // e.g. a later default-kind lookup
checkEq("c1 stays in the child pool", e.poolKind("c1").rawValue, "child")

print("\n[5b] A normal session cannot be re-tagged into the child pool (FIND-01)")
// Reachable for real: `debug.agent.openHelperSheet` takes an arbitrary
// childSessionId from its caller, so a `.child` call can name an ordinary
// conversation. Re-tagging it would move a session the user owns into the sub
// agent pool — swept against a different cap, and released by HelperSheet's
// disappear handler.
let f = Cache()
f.getOrCreate("n1")                       // opened normally first
f.getOrCreate("n1", kind: .child)         // …then named by a child path
checkEq("stays in the normal pool", f.poolKind("n1").rawValue, "normal")
checkEq("still counted as a normal", f.normalCount, 1)
checkEq("and not as a child", f.childCount, 0)
// The reverse direction still holds (a child stays a child).
f.getOrCreate("c1", kind: .child)
f.getOrCreate("c1")
checkEq("child not promoted to normal", f.poolKind("c1").rawValue, "child")
// A genuinely new session named by a child path IS a child.
f.getOrCreate("c2", kind: .child)
checkEq("first-touch child tag applies", f.poolKind("c2").rawValue, "child")
// Budgets stay honest after the mix-up attempt: n1 must still consume a
// normal slot, so filling the normal pool evicts as if it were never touched.
let g = Cache()
for i in 1...6 { g.getOrCreate("n\(i)") }
g.getOrCreate("n1", kind: .child)          // attempted re-tag on the oldest
g.getOrCreate("n7")                        // pushes the normal pool over 6
checkEq("normal pool still capped at 6", g.normalCount, 6)
checkEq("no session leaked into the child pool", g.childCount, 0)
// n1 survives because that lookup TOUCHED it (now most-recently-used), so the
// next-oldest goes instead — the point being that the victim came from the
// normal pool at all, i.e. n1 still occupies a normal slot rather than having
// been quietly moved out of the user's budget.
check("eviction victim is a normal session", g.evicted.allSatisfy { $0.hasPrefix("n") })
checkEq("exactly one normal was evicted", g.evicted.count, 1)
check("n1 still resident and still normal",
      g.cache["n1"] != nil && g.poolKind("n1") == .normal)

// MARK: - Reproduced: renderer LRU

final class RendererCache {
    var map: [UUID: Int] = [:]
    var lru: [UUID] = []
    let cap = 80
    func touch(_ id: UUID) {
        if let i = lru.firstIndex(of: id) { lru.remove(at: i) }
        lru.append(id)
        while lru.count > cap, let oldest = lru.first {
            lru.removeFirst(); map.removeValue(forKey: oldest)
        }
    }
    func put(_ id: UUID) { map[id] = 1; touch(id) }
    func drop(_ id: UUID) {
        guard map.removeValue(forKey: id) != nil else { return }
        if let i = lru.firstIndex(of: id) { lru.remove(at: i) }
    }
    func dropAll() -> Int { let n = map.count; map.removeAll(); lru.removeAll(); return n }
}

print("\n[6] Renderer cache is bounded and drainable")
let r = RendererCache()
var ids: [UUID] = []
for _ in 0..<200 { let id = UUID(); ids.append(id); r.put(id) }
checkEq("bounded at the cap (was unbounded)", r.map.count, 80)
check("oldest entry evicted", r.map[ids[0]] == nil)
check("newest entry resident", r.map[ids[199]] != nil)
// Re-touching keeps an entry alive across further inserts.
let keep = ids[150]
r.touch(keep)
for _ in 0..<40 { r.put(UUID()) }
check("recently used entry survives churn", r.map[keep] != nil)
// Explicit drops.
r.drop(keep)
check("dropping one entry works", r.map[keep] == nil)
check("dropping an unknown id is a no-op", { r.drop(UUID()); return true }())
checkEq("dropAll clears everything", { _ = r.dropAll(); return r.map.count }(), 0)
checkEq("…and its LRU too", r.lru.count, 0)

print("\n[7] Shipping sources match these assumptions")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let life = source("Agent/Chat/ChatLifecycleSupport.swift")
let vm = source("Agent/Chat/AIChatViewModel.swift")
let md = source("Views/Chat/SelectableMarkdownView.swift")
let helper = source("Agent/Jobs/HelperRunner.swift")
let sheet = source("Views/Chat/HelperSheet.swift")
if life.isEmpty {
    print("  ⏭  sources not readable from this sandbox")
} else {
    check("caps are 6 and 10",
          life.contains("softCap: Int = 6") && life.contains("childSoftCap: Int = 10"))
    check("eviction releases, not cancels", life.contains("vm.releaseForEviction()"))
    check("no cancel remains on the capacity path",
          life.contains("private func evict(_ sessionId: String, vm: AIChatViewModel, reason: String) {\n        vm.cancel()"), false)
    check("session DELETE still cancels (deliberate)", life.contains("removed.cancel()"))
    check("761be79da active-child guard retained",
          life.contains("if AgentJobRegistry.shared.hasActiveChildren(parent: sessionId) { return false }"))
    check("pools swept separately", life.contains("evictPool(.normal, cap: Self.softCap)")
                                  && life.contains("evictPool(.child, cap: Self.childSoftCap)"))
    check("memory warning also drains renderers", life.contains("SelectableMarkdownView.dropAllRenderers()"))
    check("releaseForEviction exists and frees render state",
          vm.contains("func releaseForEviction()") && vm.contains("releaseRenderState()"))
    check("release does NOT touch the job registry",
          vm.contains("func releaseForEviction() {\n        releaseRenderState()"))
    check("renderer cache has a cap", md.contains("rendererCacheCap = 80"))
    check("renderer drop-one exists", md.contains("static func dropRenderer(for messageId: UUID)"))
    check("renderer drop-all exists", md.contains("static func dropAllRenderers() -> Int"))
    check("child VMs created in the child pool",
          helper.contains("getOrCreate(for: childId, kind: .child)"))
    // FIND-01
    check("pool tag is sticky (guarded on both nil checks)",
          life.contains("if kind == .child, poolKinds[sessionId] == nil, cache[sessionId] == nil {"))
    check("unconditional re-tag is gone",
          life.contains("if kind == .child { poolKinds[sessionId] = .child }"), false)
    // FIND-02
    check("child sheet schedules a deferred release, not an immediate one",
          sheet.contains("scheduleRenderStateRelease()"))
    check("immediate synchronous release is gone",
          sheet.contains("childVM?.releaseRenderState()\n        }"), false)
    check("release is skipped while the agent is streaming",
          sheet.contains("guard !vm.isProcessing else { return }"))
    check("release is cancellable and cancelled on reopen",
          sheet.contains("releaseTask?.cancel()") && sheet.contains(".onAppear {"))
    check("release waits out the dismiss animation",
          sheet.contains("try? await Task.sleep(nanoseconds: 700_000_000)"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
