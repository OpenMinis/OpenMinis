// Tests for [T-envvar-redactor-main-thread-keychain] (commit 271633fb9) —
// EnvVarRedactor now caches the env-var values it masks, and every write path
// that can change the set of secrets must drop that cache. A missed
// invalidation is silent and security-relevant: the new secret is then NOT
// masked in tool output until some unrelated edit happens to clear the cache.
//
// What this pins:
//   1. The cache is dropped by every write path the commit named
//      (saveEntries, saveValue success, deleteValue).
//   2. The paths the commit did NOT name: `reloadFromDisk()` is how the iCloud
//      whole-file merger (CloudSyncEngine.mergeEnvVars, still reachable from
//      the EnvVarV2 hydrator for older peers) publishes a rewritten
//      env-vars.json. When the value for a new key is already in the Keychain
//      (iCloud Keychain delivered it first), importEnvVarSecrets skips
//      saveValue — so nothing invalidates and the new key stays unmasked.
//   3. The cache fill is not atomic with invalidation: the load runs outside
//      the lock and its result is stored unconditionally, so an invalidate
//      that lands while a load is in flight is overwritten by the stale load.
//
// Standalone: `swift EnvVarRedactorCacheInvalidationTests.swift`.

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

/// Body of a Swift function, from its signature to the matching brace.
func body(of signature: String, in src: String) -> String {
    guard let start = src.range(of: signature) else { return "" }
    var depth = 0
    var seenOpen = false
    var idx = start.lowerBound
    while idx < src.endIndex {
        let c = src[idx]
        if c == "{" { depth += 1; seenOpen = true }
        if c == "}" { depth -= 1; if seenOpen && depth == 0 { return String(src[start.lowerBound...idx]) } }
        idx = src.index(after: idx)
    }
    return ""
}

let store = source("Shared/EnvVarStore.swift")
let redactor = source("Shared/EnvVarRedactor.swift")
let cloud = source("Agent/Sync/CloudSyncEngine.swift")

// MARK: - 1. The named write paths invalidate

print("\n▶️  1. write paths named by 271633fb9 drop the cache")
check("saveEntries() invalidates",
      body(of: "private func saveEntries()", in: store).contains("EnvVarRedactor.invalidateCache()"))
check("saveValue() invalidates on success",
      body(of: "nonisolated private static func saveValue(", in: store).contains("EnvVarRedactor.invalidateCache()"))
check("deleteValue() invalidates",
      body(of: "nonisolated private static func deleteValue(", in: store).contains("EnvVarRedactor.invalidateCache()"))

// MARK: - 2. Every direct writer of env-vars.json must also invalidate

print("\n▶️  2. the iCloud whole-file merger rewrites env-vars.json then calls reloadFromDisk()")
let merge = body(of: "static func mergeEnvVars(remoteJson", in: cloud)
check("mergeEnvVars writes the file and reloads the store (precondition)",
      merge.contains("data.write(to: localURL") && merge.contains("EnvVarStore.shared.reloadFromDisk()"))
let importer = body(of: "static func importEnvVarSecrets(", in: cloud)
check("importEnvVarSecrets skips saveValue when the Keychain already has the key (precondition)",
      importer.contains("if EnvVarStore.loadValueSync(forKey: key) == nil"))
// With both preconditions true, the only thing that can drop the cache for a
// key whose value was already in the Keychain is reloadFromDisk() itself.
let reload = body(of: "func reloadFromDisk()", in: store)
check("reloadFromDisk() invalidates the redactor cache (BUG if this fails: a key added by an iCloud merge is never masked)",
      reload.contains("EnvVarRedactor.invalidateCache()"))

// MARK: - 3. Invalidate racing an in-flight load

print("\n▶️  3. an invalidate during an in-flight load must not be lost")

/// Faithful model of EnvVarRedactor.loadAllValues(): check under lock, load
/// OUTSIDE the lock, then store the result under lock unconditionally.
final class ShippedCache {
    var cached: [String]?
    var backing: [String]
    init(_ b: [String]) { backing = b }
    func invalidate() { cached = nil }
    /// Split into the two halves the real code runs on either side of the
    /// unlocked load, so the test can interleave an invalidate between them.
    func beginLoad() -> [String]? { cached }
    func load() -> [String] { backing }
    func finish(_ loaded: [String]) { cached = loaded }
}

let c = ShippedCache(["OLD_SECRET_VALUE"])
// Thread T1 (tool result): cache miss, starts loading.
_ = c.beginLoad()
let snapshot = c.load()                 // reads ["OLD_SECRET_VALUE"]
// Thread T2 (user adds a secret): value written, cache invalidated.
c.backing.append("NEW_SECRET_VALUE")
c.invalidate()
// T1 stores its (now stale) result.
c.finish(snapshot)
// Next tool result: served from cache.
let served = c.beginLoad() ?? c.load()
check("the shipped fill order loses the invalidation (demonstrates the race)",
      !served.contains("NEW_SECRET_VALUE"))

/// Proposed fix: a generation counter bumped by invalidate; a load only
/// publishes its result if no invalidate happened since it started.
final class GenerationCache {
    var cached: [String]?
    var generation = 0
    var backing: [String]
    init(_ b: [String]) { backing = b }
    func invalidate() { cached = nil; generation += 1 }
    func beginLoad() -> (hit: [String]?, gen: Int) { (cached, generation) }
    func finish(_ loaded: [String], startedAt gen: Int) { if gen == generation { cached = loaded } }
}
let g = GenerationCache(["OLD_SECRET_VALUE"])
let (_, gen0) = g.beginLoad()
let snap2 = g.backing
g.backing.append("NEW_SECRET_VALUE")
g.invalidate()
g.finish(snap2, startedAt: gen0)
let served2 = g.cached ?? g.backing
check("a generation-guarded fill keeps the new secret masked", served2.contains("NEW_SECRET_VALUE"))

let loadAll = body(of: "private static func loadAllValues()", in: redactor)
check("shipped loadAllValues() guards the store with a generation/epoch check (BUG if this fails)",
      loadAll.contains("generation") || loadAll.contains("epoch") || loadAll.contains("version"))

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
