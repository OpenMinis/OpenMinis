import XCTest
@testable import Minis

/// [T-ios-group-pause-badge-reconcile-stamp] The group card's 24h freshness
/// window, and the stamping rules that feed it.
///
/// This bug shipped twice. The first fix (`pushFront(restamp:)`) was correct as
/// far as it went and still did not change what the user saw, because the path
/// that actually re-stamped their badges was `reconcileInterruptedSessions`:
/// it runs on every launch AND every foreground, and stamped `Date()` for any
/// interrupted session that had no surviving stamp — on the reporter's device,
/// 288 of them. Code review passed both times; only the device log showed it.
/// So these tests target the STAMP VALUES that reconcile and repair produce,
/// not merely "does the filter compare against a cutoff".
@MainActor
final class SessionBadgeFreshnessTests: XCTestCase {

    private let day: TimeInterval = 24 * 3600
    private var store: SessionBadgeStore { SessionBadgeStore.shared }

    /// Ids are unique per test so the shared singleton can't leak state between
    /// cases; each test clears exactly what it created.
    private func makeId() -> String { "test-\(UUID().uuidString)" }

    private func cleanUp(_ ids: String...) {
        for id in ids { store.clearAll(for: id) }
    }

    // MARK: - The reported symptom

    /// Reconcile restoring a marker for a session interrupted days ago must
    /// stamp it with the TAIL date, not "now" — otherwise the group card treats
    /// a stale pause as fresh forever. This is the exact regression the user
    /// re-reported after the first fix.
    func testReconcileStampsOldInterruptionWithTailDateNotNow() {
        let id = makeId()
        defer { cleanUp(id) }

        let threeDaysAgo = Date().addingTimeInterval(-3 * day)
        store.reconcileInterruptedSessions([id], entryDates: [id: threeDaysAgo])

        // The badge exists (rows must still show it at any age) …
        XCTAssertEqual(store.topCornerBadge(for: id), .paused)
        // … but the group card's 24h window must NOT see it.
        XCTAssertFalse(
            store.freshCornerBadgeSessionIds(within: day).contains(id),
            "A 3-day-old interruption was stamped fresh — the group card would light up")
    }

    /// The counterpart: a genuinely recent interruption must still pass, so the
    /// fix cannot be "silence everything", which would trade a false positive
    /// for a false negative.
    func testReconcileKeepsRecentInterruptionFresh() {
        let id = makeId()
        defer { cleanUp(id) }

        store.reconcileInterruptedSessions([id], entryDates: [id: Date().addingTimeInterval(-3600)])

        XCTAssertTrue(
            store.freshCornerBadgeSessionIds(within: day).contains(id),
            "An interruption from an hour ago must still flag its group")
    }

    // MARK: - Repair of already-polluted installs

    /// Existing installs carry stamps that earlier builds wrote as "now" for
    /// long-stale pauses. Fixing the writers alone leaves those on disk, which
    /// is why the user still saw the badge after the first fix — the repair has
    /// to rewrite them.
    func testRepairRewritesPollutedStampToTailDate() {
        let id = makeId()
        defer { cleanUp(id) }

        // Simulate the polluted state: badge entered "now", tail is days old.
        store.debugSetBadge(.paused, for: id, enteredAt: Date())
        XCTAssertTrue(store.freshCornerBadgeSessionIds(within: day).contains(id),
                      "precondition: the polluted stamp reads as fresh")

        let tail = Date().addingTimeInterval(-3 * day)
        store.repairPollutedPausedStamps(entryDates: [id: tail])

        XCTAssertFalse(
            store.freshCornerBadgeSessionIds(within: day).contains(id),
            "Repair did not correct a stamp that claimed a 3-day-old pause was current")
    }

    /// Repair must not move a stamp that is already older than its tail, and
    /// must not touch a legitimately fresh badge whose tail is equally fresh.
    func testRepairLeavesHonestStampsAlone() {
        let id = makeId()
        defer { cleanUp(id) }

        let tenMinutesAgo = Date().addingTimeInterval(-600)
        store.debugSetBadge(.paused, for: id, enteredAt: tenMinutesAgo)
        store.repairPollutedPausedStamps(entryDates: [id: tenMinutesAgo])

        XCTAssertTrue(
            store.freshCornerBadgeSessionIds(within: day).contains(id),
            "Repair wrongly aged out a badge whose stamp already matched its tail")
    }

    /// Idempotence: reconcile runs on every foreground, so a second pass over
    /// already-correct data must not drift the stamp forward. A regression here
    /// would reproduce the original bug one foreground at a time.
    func testRepeatedReconcileDoesNotRefreshAnExistingStamp() {
        let id = makeId()
        defer { cleanUp(id) }

        let old = Date().addingTimeInterval(-3 * day)
        store.reconcileInterruptedSessions([id], entryDates: [id: old])
        for _ in 0..<5 {
            store.reconcileInterruptedSessions([id], entryDates: [id: old])
            store.repairPollutedPausedStamps(entryDates: [id: old])
        }

        XCTAssertFalse(
            store.freshCornerBadgeSessionIds(within: day).contains(id),
            "Repeated reconcile/repair passes walked the stamp back to 'now'")
    }

    // MARK: - pushFront restamp semantics (the first fix, kept honest)

    /// A real new interruption restarts the window even when the badge is
    /// already present; a mere re-detection must not.
    func testPushFrontRestampSemantics() {
        let redetected = makeId()
        let reinterrupted = makeId()
        defer { cleanUp(redetected, reinterrupted) }

        let old = Date().addingTimeInterval(-3 * day)

        // Re-detection (restamp: false) keeps the original, stale stamp.
        store.debugSetBadge(.paused, for: redetected, enteredAt: old)
        store.pushFront(.paused, for: redetected, restamp: false)
        XCTAssertFalse(
            store.freshCornerBadgeSessionIds(within: day).contains(redetected),
            "Re-detecting an old pause refreshed its stamp")

        // A genuine re-interruption (restamp: true) does restart the window,
        // even though .paused is already at the head of the queue.
        store.debugSetBadge(.paused, for: reinterrupted, enteredAt: old)
        store.pushFront(.paused, for: reinterrupted, restamp: true)
        XCTAssertTrue(
            store.freshCornerBadgeSessionIds(within: day).contains(reinterrupted),
            "A real new interruption failed to restart the freshness window")
    }
}
