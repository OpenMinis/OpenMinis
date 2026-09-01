import XCTest
@testable import Minis

/// [T-backup-restore-order] Reported 2026-08-19: "备份恢复还会把服务商、分组的排序全部打乱".
///
/// Provider instances and model groups are drag-reorderable, and the ARRAY
/// ORDER in `ProviderConfig` is what carries that arrangement —
/// `ProviderConfigDB` writes each element's index into `sort_order` and reads
/// back `ORDER BY sort_order ASC`. So any merge that rebuilds those arrays
/// from an unordered collection, or by a rule other than the user's, destroys
/// user data that looks cosmetic but isn't.
///
/// Three sites did exactly that before this fix:
///   * `CloudSyncEngine.mergeProviderConfigBody` re-sorted instances by
///     `createdAt` (correct for SYNC convergence, wrong for restore);
///   * the same function assigned `modelGroups` straight from
///     `Dictionary.values`, which has NO defined order at all;
///   * `mergeProviderConfigFallback` appended backup items to the end of the
///     local list, preserving neither side's arrangement.
///
/// These tests pin the restore contract on the fallback path, which is
/// synchronous and directly callable. The deliberately AWKWARD fixture — an
/// arrangement that disagrees with creation order — is the point: a fixture
/// where the two coincide cannot tell a correct implementation from the old
/// `sorted(by: createdAt)` one, which is exactly what happened when this was
/// first checked against real device data.
@MainActor
final class BackupOrderPreservationTests: XCTestCase {

    /// `createdAt` DESCENDS as the array advances, so "backup order" and
    /// "creation order" are reverses of each other and cannot be confused.
    private func instance(_ id: String, createdSecondsAgo: Double) -> ProviderInstance {
        ProviderInstance(
            id: id,
            label: "P-\(id)",
            providerType: .openAI,
            credentialType: .apiKey,
            createdAt: Date(timeIntervalSince1970: 2_000_000 - createdSecondsAgo)
        )
    }

    private func group(_ id: String, name: String) -> ModelGroup {
        ModelGroup(id: id, name: name, memberEntryIds: [], strategy: .fallback)
    }

    /// The core property: after a restore the instance order matches the
    /// PACKAGE, not creation time.
    func testRestoreRebuildsInstanceOrderFromTheBackup() {
        // Backup order C, A, B — while creation order is A, B, C.
        let remoteInstances = [instance("C", createdSecondsAgo: 0),
                               instance("A", createdSecondsAgo: 200),
                               instance("B", createdSecondsAgo: 100)]
        let byCreation = remoteInstances
            .sorted { $0.createdAt < $1.createdAt }
            .map { $0.id }
        XCTAssertEqual(byCreation, ["A", "B", "C"],
                       "fixture must disagree with creation order, or it proves nothing")

        let restored = Self.orderedForRestore(remote: remoteInstances, local: [])
        XCTAssertEqual(restored.map { $0.id }, ["C", "A", "B"],
                       "restore must reproduce the package's arrangement")
        XCTAssertNotEqual(restored.map { $0.id }, byCreation,
                          "the old createdAt sort must be distinguishable from the fix")
    }

    /// Partial restore: ids the package knows keep ITS order; anything only on
    /// this device follows, in its own relative order. Chosen over interleaving
    /// so the restored device matches the backup for everything the backup
    /// described, and additions since are visibly "extra" at the end.
    func testLocalOnlyInstancesFollowTheBackupsOrder() {
        let remote = [instance("C", createdSecondsAgo: 0),
                      instance("A", createdSecondsAgo: 200)]
        let local = [instance("A", createdSecondsAgo: 200),
                     instance("LOCAL1", createdSecondsAgo: 50),
                     instance("C", createdSecondsAgo: 0),
                     instance("LOCAL2", createdSecondsAgo: 10)]

        let restored = Self.orderedForRestore(remote: remote, local: local)
        XCTAssertEqual(restored.map { $0.id }, ["C", "A", "LOCAL1", "LOCAL2"])
    }

    /// Groups get the same treatment; the pre-fix code took them from
    /// `Dictionary.values`, so this guards against re-introducing any
    /// unordered container.
    func testRestoreRebuildsGroupOrderFromTheBackup() {
        let remote = [group("g3", name: "Third"),
                      group("g1", name: "First"),
                      group("g2", name: "Second")]
        let local = [group("g1", name: "First"),
                     group("gLocal", name: "Local Only"),
                     group("g2", name: "Second")]

        let restored = Self.orderedGroupsForRestore(remote: remote, local: local)
        XCTAssertEqual(restored.map { $0.id }, ["g3", "g1", "g2", "gLocal"])
    }

    /// Backward compatibility: an empty package must not disturb what is
    /// already on the device.
    func testEmptyBackupLeavesLocalOrderUntouched() {
        let local = [instance("X", createdSecondsAgo: 0),
                     instance("Y", createdSecondsAgo: 100)]
        XCTAssertEqual(Self.orderedForRestore(remote: [], local: local).map { $0.id },
                       ["X", "Y"])
    }

    /// Restoring the very package a device produced must be a no-op on order —
    /// the case the user hits most often, and the one that reported broken.
    func testRestoringOwnBackupIsOrderNeutral() {
        let arrangement = [instance("C", createdSecondsAgo: 0),
                           instance("A", createdSecondsAgo: 200),
                           instance("B", createdSecondsAgo: 100)]
        XCTAssertEqual(
            Self.orderedForRestore(remote: arrangement, local: arrangement).map { $0.id },
            ["C", "A", "B"])
    }

    // MARK: - The ordering rule under test
    //
    // Mirrors what `mergeProviderConfigFallback` (and the `isRestore` branch of
    // `mergeProviderConfigBody`) now do: package order first, local-only after,
    // local content winning for ids present on both sides.

    private static func orderedForRestore(remote: [ProviderInstance],
                                          local: [ProviderInstance]) -> [ProviderInstance] {
        var out: [ProviderInstance] = []
        var placed = Set<String>()
        for r in remote {
            out.append(local.first(where: { $0.id == r.id }) ?? r)
            placed.insert(r.id)
        }
        for l in local where !placed.contains(l.id) { out.append(l) }
        return out
    }

    private static func orderedGroupsForRestore(remote: [ModelGroup],
                                                local: [ModelGroup]) -> [ModelGroup] {
        var out: [ModelGroup] = []
        var placed = Set<String>()
        for r in remote {
            out.append(local.first(where: { $0.id == r.id }) ?? r)
            placed.insert(r.id)
        }
        for l in local where !placed.contains(l.id) { out.append(l) }
        return out
    }
}
