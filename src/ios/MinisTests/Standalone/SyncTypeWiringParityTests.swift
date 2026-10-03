// Guards the "a V2 sync record type is live only when ALL wiring points agree"
// invariant, which broke repeatedly in the 2026-09-16..23 window:
//
//   668150aed  scripted delete of MCPServersV2's hydrator block also removed the
//              CompactMarkerV2 / FolderV2 / SessionFileV2 / SkillV2 /
//              ProviderConfigV2 registrations — BUILD SUCCEEDED, sync of five
//              types silently died in both directions.
//   016c2f23c  restored those five registrations.
//   266fa68dc  never-written types polled every run tripped the self-backoff.
//   fd53f4ce5  SessionV2 must be polled by updatedAt (createdAt never moves).
//   1e85ef3c1  parentsFirst rank: FolderV2 < SessionV2 < children < rest.
//
// The five wiring points (see memory note "New V2 sync type = 5 wiring points"):
//   1. SyncedTypes.swift syncMetadata + SyncedTypesBootstrap.registerAll()
//   2. ChatStoreSyncHydrators.registerAll()  h.register(recordType: …)
//   3. ChatStore.v2SyncRecordTypes           outbound dirty-row whitelist
//   4. ICloudSharedZoneTransport.zoneByRecordType
//   5. ICloudSharedZoneTransport.fetchRecentV2 typesAndKeys (poll list)
//
// Every assertion here re-reads the shipping source, so a scripted edit that
// drops a block fails this file instead of shipping silently.
//
// Run: cd src/ios/MinisTests/Standalone && swift SyncTypeWiringParityTests.swift

import Foundation

var failures = 0
func check(_ label: String, _ ok: Bool, _ detail: @autoclosure () -> String = "") {
    if ok { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\(detail().isEmpty ? "" : " — \(detail())")"); failures += 1 }
}

let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let iosRoot = here.deletingLastPathComponent().deletingLastPathComponent()
func src(_ rel: String) -> String {
    let p = iosRoot.appendingPathComponent(rel).path
    guard let s = try? String(contentsOfFile: p, encoding: .utf8) else {
        print("❌ cannot read \(p)"); exit(1)
    }
    return s
}

let syncedTypes = src("Agent/Sync/V2/SyncedTypes.swift")
let hydrators = src("Agent/Sync/V2/ChatStoreSyncHydrators.swift")
let chatStore = src("Agent/Chat/ChatStore.swift")
let transport = src("Agent/Sync/V2/ICloudSharedZoneTransport.swift")
let syncCore = src("Agent/Sync/V2/SyncCore.swift")

/// Text between the first occurrence of `start` and the next `end` after it.
func slice(_ s: String, from start: String, to end: String) -> String {
    guard let a = s.range(of: start) else { return "" }
    guard let b = s.range(of: end, range: a.upperBound..<s.endIndex) else { return "" }
    return String(s[a.upperBound..<b.lowerBound])
}
func matches(_ pattern: String, in s: String, group: Int = 1) -> [String] {
    let re = try! NSRegularExpression(pattern: pattern)
    return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap {
        Range($0.range(at: group), in: s).map { String(s[$0]) }
    }
}
/// Remove `//` line comments so a type named only in a comment never counts.
func stripComments(_ s: String) -> String {
    s.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
        guard let r = line.range(of: "//") else { return String(line) }
        return String(line[line.startIndex..<r.lowerBound])
    }.joined(separator: "\n")
}

// MARK: - Parse the five wiring points

// 1. SyncedTypes: every `static let syncMetadata` block names its recordType.
let metadataTypes = Set(matches(#"syncMetadata: SyncTypeMetadata<\w+> = \{\s*[^}]*?recordType: "(\w+)""#, in: syncedTypes))
let bootstrapBody = stripComments(slice(syncedTypes, from: "enum SyncedTypesBootstrap {", to: "\n}\n"))
let bootstrapRegisterCount = matches(#"r\.register\((\w+)\.self\)"#, in: bootstrapBody).count

// 2. Hydrators: h.register(recordType: "X", builder: …, merger: …)
let registerAllBody = stripComments(slice(hydrators, from: "static func registerAll()", to: "registered: \\("))
struct HydratorReg { let type: String; let hasBuilder: Bool; let hasMerger: Bool; let builderAlwaysNil: Bool }
var hydratorRegs: [String: HydratorReg] = [:]
for block in registerAllBody.components(separatedBy: "h.register(").dropFirst() {
    guard let type = matches(#"recordType: "(\w+)""#, in: block).first else { continue }
    let upToClose = block.components(separatedBy: "\n        )").first ?? block
    hydratorRegs[type] = HydratorReg(
        type: type,
        hasBuilder: upToClose.contains("builder:") && !upToClose.contains("builder: nil"),
        hasMerger: upToClose.contains("merger:") && !upToClose.contains("merger: nil"),
        builderAlwaysNil: upToClose.contains("-> PortableRecord? in nil")
    )
}

// 3. Outbound whitelist.
let whitelistBody = stripComments(slice(chatStore, from: "static let v2SyncRecordTypes: [String] = [", to: "\n    ]"))
let whitelist = Set(matches(#""(\w+)""#, in: whitelistBody))

// 4. Zone map.
let zoneBody = stripComments(slice(transport, from: "private static let zoneByRecordType: [String: String] = [", to: "\n    ]"))
let zoneTypes = Set(matches(#""(\w+)":"#, in: zoneBody))

// 5. Poll list.
let pollBody = stripComments(slice(transport, from: "let typesAndKeys: [(String, String)] = [", to: "\n        ]"))
let pollPairs = matches(#"\("(\w+)", "(\w+)"\)"#, in: pollBody, group: 1)
    .enumerated().map { ($0.element, matches(#"\("(\w+)", "(\w+)"\)"#, in: pollBody, group: 2)[$0.offset]) }
let pollTypes = Set(pollPairs.map { $0.0 })
let pollKey = Dictionary(pollPairs, uniquingKeysWith: { a, _ in a })

print("\n▶️  parsed wiring points")
print("   metadata=\(metadataTypes.count) bootstrap=\(bootstrapRegisterCount) hydrators=\(hydratorRegs.count) whitelist=\(whitelist.count) zones=\(zoneTypes.count) poll=\(pollTypes.count)")
check("parser found the metadata types", metadataTypes.count >= 15, "\(metadataTypes.sorted())")
check("parser found hydrator registrations", hydratorRegs.count >= 15)
check("parser found the whitelist", whitelist.count >= 15)
check("parser found the zone map", zoneTypes.count >= 15)
check("parser found the poll list", pollTypes.count >= 15)

// MARK: - Invariants

print("\n▶️  point 1 ↔ point 2: every registered sync type has a hydrator (016c2f23c)")
check("bootstrap registers exactly one struct per metadata type",
      bootstrapRegisterCount == metadataTypes.count, "bootstrap=\(bootstrapRegisterCount) metadata=\(metadataTypes.count)")
check("hydrator set == metadata set",
      Set(hydratorRegs.keys) == metadataTypes,
      "missing hydrator: \(metadataTypes.subtracting(hydratorRegs.keys).sorted()), no metadata: \(Set(hydratorRegs.keys).subtracting(metadataTypes).sorted())")
for t in ["CompactMarkerV2", "FolderV2", "SessionFileV2", "SkillV2", "ProviderConfigV2"] {
    check("the five types 668150aed deleted by accident are registered: \(t)", hydratorRegs[t] != nil)
}
check("MCPServersV2 stays gone from hydrators (668150aed)", hydratorRegs["MCPServersV2"] == nil)
check("MCPServersV2 stays gone from metadata", !metadataTypes.contains("MCPServersV2"))

print("\n▶️  every type has a merger — inbound for that type would silently no-op otherwise")
for (t, r) in hydratorRegs.sorted(by: { $0.key < $1.key }) {
    check("\(t) has a merger", r.hasMerger)
}

print("\n▶️  point 3: every whitelisted (outbound) type is fully wired")
for t in whitelist.sorted() {
    check("\(t): metadata registered", metadataTypes.contains(t))
    check("\(t): hydrator has a builder", hydratorRegs[t]?.hasBuilder == true)
    check("\(t): has a zone (else the push loop's zone guard drops it)", zoneTypes.contains(t))
}
check("every metadata type is either whitelisted or deliberately inbound-only",
      metadataTypes.subtracting(whitelist).isEmpty,
      "not whitelisted: \(metadataTypes.subtracting(whitelist).sorted())")

print("\n▶️  point 5: every polled type can be applied and has a zone")
for t in pollTypes.sorted() {
    check("\(t): metadata (else SyncCore logs unknownRecordType and skips)", metadataTypes.contains(t))
    check("\(t): merger", hydratorRegs[t]?.hasMerger == true)
    check("\(t): zone mapping", zoneTypes.contains(t))
}
// Types deliberately NOT polled, with the reason; anything else missing from
// the poll list is a type whose peer updates only arrive via the token path.
let deliberatelyUnpolled: [String: String] = [
    "SyncDeviceV2": "device records have their own bootstrap path",
    "EnvVarV2": "legacy whole-file; builder emits nil, cleaned up server-side",
]
for t in metadataTypes.subtracting(pollTypes).sorted() {
    check("\(t) is unpolled only by documented decision", deliberatelyUnpolled[t] != nil,
          "add it to typesAndKeys or to deliberatelyUnpolled with a reason")
}
check("the only always-nil builder is the legacy EnvVarV2",
      hydratorRegs.values.filter { $0.builderAlwaysNil }.map { $0.type } == ["EnvVarV2"],
      "\(hydratorRegs.values.filter { $0.builderAlwaysNil }.map { $0.type })")
check("MCPServersV2 is not polled (266fa68dc: never saved → code 11 every run)", !pollTypes.contains("MCPServersV2"))

print("\n▶️  poll sort keys (fd53f4ce5)")
check("SessionV2 polled by updatedAt (createdAt never moves for an existing session)", pollKey["SessionV2"] == "updatedAt",
      "got \(pollKey["SessionV2"] ?? "nil")")
check("MessageV2 polled by createdAt", pollKey["MessageV2"] == "createdAt")
check("CompactMarkerV2 polled by createdAt", pollKey["CompactMarkerV2"] == "createdAt")
check("FolderV2 polled by updatedAt", pollKey["FolderV2"] == "updatedAt")
for t in pollTypes where t.hasSuffix("V3") {
    check("\(t) polled by updatedAt (V3 records carry no createdAt)", pollKey[t] == "updatedAt")
}

print("\n▶️  parentsFirst rank (1e85ef3c1) — containers before children")
let rankBody = slice(syncCore, from: "private static let applyRank: [String: Int] = [", to: "]")
let rank = Dictionary(uniqueKeysWithValues: zip(
    matches(#""(\w+)": (\d+)"#, in: rankBody, group: 1),
    matches(#""(\w+)": (\d+)"#, in: rankBody, group: 2).compactMap(Int.init)))
check("FolderV2 ranks before SessionV2", (rank["FolderV2"] ?? 99) < (rank["SessionV2"] ?? -1))
for child in ["MessageV2", "CompactMarkerV2", "SessionFileV2"] {
    check("\(child) ranks after SessionV2", (rank[child] ?? -1) > (rank["SessionV2"] ?? 99))
}
check("every ranked type is a real sync type", Set(rank.keys).isSubset(of: metadataTypes),
      "\(Set(rank.keys).subtracting(metadataTypes))")

// Model of parentsFirst (verbatim algorithm) — stable, and a no-op without ranked types.
struct R { let type: String; let id: Int }
func parentsFirst(_ records: [R]) -> [R] {
    guard records.count > 1, records.contains(where: { rank[$0.type] != nil }) else { return records }
    return records.enumerated().sorted { a, b in
        let ra = rank[a.element.type] ?? 3, rb = rank[b.element.type] ?? 3
        return ra != rb ? ra < rb : a.offset < b.offset
    }.map(\.element)
}
check("source still carries the modelled comparator",
      syncCore.contains("return ra != rb ? ra < rb : a.offset < b.offset") &&
      syncCore.contains("applyRank[a.element.id.type] ?? 3"))
let mixed = [R(type: "MessageV2", id: 1), R(type: "SkillV2", id: 2), R(type: "SessionV2", id: 3),
             R(type: "MessageV2", id: 4), R(type: "FolderV2", id: 5), R(type: "SoulV2", id: 6)]
check("parents first, arrival order kept inside each rank",
      parentsFirst(mixed).map(\.id) == [5, 3, 1, 4, 2, 6], "\(parentsFirst(mixed).map(\.id))")
check("empty batch unchanged", parentsFirst([]).isEmpty)
check("single record unchanged", parentsFirst([R(type: "MessageV2", id: 9)]).map(\.id) == [9])
let unranked = [R(type: "SoulV2", id: 1), R(type: "SkillV2", id: 2)]
check("no ranked types → identity", parentsFirst(unranked).map(\.id) == [1, 2])

print("")
if failures == 0 { print("✅ SyncTypeWiringParityTests: all passed") }
else { print("❌ SyncTypeWiringParityTests: \(failures) failure(s)"); exit(1) }
