// Tests for [T-icloud-zone-delete-resurrect] — deleting a legacy zone from
// the iCloud Zones inventory must actually make it stay gone.
//
// Bug: tapping the trash on `[V1] device-<id>` or `[?] devices` appeared to do
// nothing — the row came back on every refresh. Two independent defects:
//
//   1. CloudSyncEngine.queueZonesAndDeviceRecord() re-queues `saveZone` for
//      BOTH `device-<myId>` and the legacy bare `devices` zone on every
//      start() and on accountChange, so a deleted zone was re-created
//      immediately (and again on the next launch).
//   2. SyncMigrationDetailView wrote the delete error into `zonesLoadError`,
//      which refreshZones() clears on entry — and the refresh always ran
//      right after the delete. So a failure was indistinguishable from
//      success: no message, and a row that stayed put.
//
// Standalone (`swift ICloudZonePurgeTests.swift`) like the neighbouring files:
// the MinisTests target has a pre-existing compile break and the shipping
// types pull in the whole app graph (CloudKit, SwiftUI, the sync stack).
// Sections [1]-[3] exercise reproduced logic; section [4] re-reads the
// shipping sources so the copies cannot silently drift from what ships.

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

// MARK: - Reproduced from MigrationEngine.V1FetcherShim.ZoneOwner

enum ZoneOwner: Equatable {
    case v1, v2, none

    // Mirrors ZoneOwner.owning(zoneName:)
    static func owning(zoneName: String) -> ZoneOwner {
        if zoneName == "minis-shared" || zoneName == "minis-devices" || zoneName == "minis-secrets" {
            return .v2
        }
        if zoneName.hasPrefix("device-") || zoneName == "devices" {
            return .v1
        }
        return .none
    }

    // Mirrors ZoneOwner.markPurged — v1 only.
    var tombstones: Bool { self == .v1 }
}

// MARK: - Reproduced from CloudSyncEngine

/// Models the engine's zone-creation queue against a purge list.
struct V1Engine {
    var purged: Set<String> = []
    let myZoneName = "device-A31AAEBC-BFF6-47B3-A8F5-78E25808B659"
    let devicesZoneName = "devices"

    /// Mirrors queueZonesAndDeviceRecord(): which zones get a saveZone.
    func queuedZones() -> Set<String> {
        var out: Set<String> = []
        for name in [myZoneName, devicesZoneName] where !purged.contains(name) {
            out.insert(name)
        }
        return out
    }

    /// Mirrors queueDeviceRecord()'s guard. A record save re-creates its zone
    /// implicitly, so this is a second way `devices` could come back.
    func queuesDeviceRecord() -> Bool { !purged.contains(devicesZoneName) }

    /// Every zone this engine would (re-)create, by any route.
    func zonesItWouldCreate() -> Set<String> {
        var out = queuedZones()
        if queuesDeviceRecord() { out.insert(devicesZoneName) }
        return out
    }
}

// MARK: - Reproduced from SyncMigrationDetailView

/// Models the delete → refresh → report sequence and which error slot is used.
struct DeleteFlow {
    var zonesLoadError: String?
    var zoneDeleteError: String?
    /// true = the pre-fix code, which shared one slot with refreshZones().
    let sharedSlot: Bool

    mutating func refreshZones() { zonesLoadError = nil }

    mutating func delete(failure: String?) {
        if sharedSlot {
            // OLD: write the error, THEN refresh (which wipes it).
            if let failure { zonesLoadError = failure }
            refreshZones()
        } else {
            // NEW: refresh first, then publish into a slot refresh can't clear.
            refreshZones()
            if let failure { zoneDeleteError = failure }
        }
    }

    var visibleError: String? { zoneDeleteError ?? zonesLoadError }
}

print("\n[1] ZoneOwner routing — who re-creates which zone")

checkEq("legacy bare `devices` is owned by v1", ZoneOwner.owning(zoneName: "devices"), .v1)
checkEq("this device's v1 zone is owned by v1",
        ZoneOwner.owning(zoneName: "device-A31AAEBC-BFF6-47B3-A8F5-78E25808B659"), .v1)
checkEq("another device's v1 zone is still v1-shaped",
        ZoneOwner.owning(zoneName: "device-DDD2B17C-6285-47D4-9F06-496ED37A03D8"), .v1)
checkEq("minis-shared is owned by v2", ZoneOwner.owning(zoneName: "minis-shared"), .v2)
checkEq("minis-devices is v2, NOT the legacy `devices`",
        ZoneOwner.owning(zoneName: "minis-devices"), .v2)
checkEq("minis-secrets is owned by v2", ZoneOwner.owning(zoneName: "minis-secrets"), .v2)
checkEq("an unknown zone has no owner", ZoneOwner.owning(zoneName: "_defaultZone"), ZoneOwner.none)
check("only v1 zones get a durable tombstone", ZoneOwner.owning(zoneName: "devices").tombstones)
check("v2 zones are NOT tombstoned (sync must rebuild them)",
      ZoneOwner.owning(zoneName: "minis-shared").tombstones, false)

print("\n[2] The resurrection itself — both zones from the screenshot")

var engine = V1Engine()
// Pre-fix behaviour == empty purge list.
check("BEFORE: engine re-creates device-<id>", engine.zonesItWouldCreate().contains(engine.myZoneName))
check("BEFORE: engine re-creates `devices`", engine.zonesItWouldCreate().contains("devices"))

engine.purged = ["device-A31AAEBC-BFF6-47B3-A8F5-78E25808B659"]
check("AFTER purge of device-<id>: not re-created",
      engine.zonesItWouldCreate().contains(engine.myZoneName), false)
check("...and `devices` is untouched (only what was deleted is suppressed)",
      engine.zonesItWouldCreate().contains("devices"))

engine.purged = ["devices"]
check("AFTER purge of `devices`: no saveZone", engine.queuedZones().contains("devices"), false)
check("AFTER purge of `devices`: device record ALSO suppressed",
      engine.queuesDeviceRecord(), false)
check("...so `devices` cannot come back via the record back door",
      engine.zonesItWouldCreate().contains("devices"), false)

engine.purged = ["devices", "device-A31AAEBC-BFF6-47B3-A8F5-78E25808B659"]
checkEq("both purged => engine creates nothing", engine.zonesItWouldCreate(), [])

// The tombstone must outlive a restart, which is what made the old bug
// survive across launches.
let restarted = V1Engine(purged: engine.purged)
checkEq("purge survives an engine restart", restarted.zonesItWouldCreate(), [])

print("\n[3] Delete failures must reach the user")

var old = DeleteFlow(sharedSlot: true)
old.delete(failure: "Zone devices was re-created by a running sync engine")
checkEq("BEFORE: the failure message is wiped by the refresh", old.visibleError, nil)

var new = DeleteFlow(sharedSlot: false)
new.delete(failure: "Zone devices was re-created by a running sync engine")
checkEq("AFTER: the failure survives the refresh",
        new.visibleError, "Zone devices was re-created by a running sync engine")

var ok = DeleteFlow(sharedSlot: false)
ok.delete(failure: nil)
checkEq("a successful delete shows no error", ok.visibleError, nil)

print("\n[4] Anti-drift — re-read the shipping sources")

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    for prefix in ["", "../", "../../", "../../../", "src/ios/"] {
        let p = root + "/" + prefix + rel
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
    }
    // Walk up looking for the repo root.
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<6 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    print("  ⚠️  could not locate \(rel) — skipping drift checks for it")
    return ""
}

let cloudSync = source("Agent/Sync/CloudSyncEngine.swift")
if !cloudSync.isEmpty {
    check("CloudSyncEngine still names the legacy zone `devices`",
          cloudSync.contains("devicesZoneName = \"devices\""))
    check("zone queue consults the purge list",
          cloudSync.contains("let purged = Self.purgedZoneNames"))
    check("device-record path is guarded too",
          cloudSync.contains("guard !Self.purgedZoneNames.contains(devicesZoneName)"))
    check("purge list is persisted (survives relaunch)",
          cloudSync.contains("cloudSync.v1.purgedZones"))
    check("re-enabling sync clears the tombstones",
          cloudSync.contains("Self.clearZonePurgeTombstones()"))
    check("suspend/resume bracket exists",
          cloudSync.contains("func suspendForZonePurge") && cloudSync.contains("func resumeAfterZonePurge"))
}

let migration = source("Agent/Sync/V2/MigrationEngine.swift")
if !migration.isEmpty {
    check("purgeZone exists", migration.contains("static func purgeZone(zoneName: String)"))
    check("purge verifies by reading the zone list back",
          migration.contains("allRecordZones()") && migration.contains("zoneResurrected"))
    check("tombstone is written BEFORE the engine resumes",
          migration.range(of: "markPurged(zoneName: zoneName)").map { m in
              migration.range(of: "await owner.resume()").map { r in m.lowerBound < r.lowerBound } ?? false
          } ?? false)
    check("ZoneOwner maps the legacy `devices` zone to v1",
          migration.contains("zoneName == \"devices\""))
}

let view = source("Views/Sync/SyncMigrationDetailView.swift")
if !view.isEmpty {
    check("the view calls purgeZone, not the bare delete",
          view.contains("V1FetcherShim.purgeZone(zoneName:"))
    check("delete errors use their own state slot",
          view.contains("@State private var zoneDeleteError"))
    check("the refresh runs BEFORE the error is published",
          view.range(of: "await refreshZones(force: true)").map { r in
              view.range(of: "zoneDeleteError = failure").map { e in r.lowerBound < e.lowerBound } ?? false
          } ?? false)
    check("the error is rendered in the zones section",
          view.contains("if let zoneDeleteError"))
}

print(failures == 0
      ? "\n✅ ALL PASS"
      : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
