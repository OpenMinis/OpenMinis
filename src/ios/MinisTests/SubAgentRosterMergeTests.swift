import XCTest
@testable import Minis

/// `SubAgentRoster.merge` — cross-device roster convergence. [T-subagent-sync-dedupe]
///
/// Sub agents are addressed by NAME (the model emits a name; `resolve(name:)`
/// matches on it) but stored under a per-device UUID. Two devices that each add
/// "coding-agent" therefore produce two records that are distinct by id and
/// identical to the model: the roster is injected every turn so the duplicate
/// costs prompt budget on all of them, `resolve` can only reach the first, and
/// both consume one of the ten `SubAgentLimits.maxCount` slots.
///
/// These run against the pure merge function rather than the async sync path,
/// which is why it was factored out as a static.
///
/// [T-subagent-own-store] The CALL SITE moved: sub agents now sync as per-record
/// `SubAgentV3` rather than inside the ProviderConfig blob, so this merge runs
/// in `ChatStoreSyncHydrators.mergeSubAgentV3` — once per inbound record, with
/// `remote` being that single definition — instead of once per whole-config
/// merge. The guarantee is unchanged and is why these tests did not: a
/// one-element `remote` still has to collapse against a same-named local record
/// deterministically, or the two devices re-upload rival rosters forever.
final class SubAgentRosterMergeTests: XCTestCase {

    private func agent(_ name: String,
                       id: String,
                       updatedAt: Date = Date(timeIntervalSince1970: 1_000),
                       instructions: String = "") -> SubAgentDefinition {
        SubAgentDefinition(id: id, name: name, description: "d-\(name)",
                           instructions: instructions, updatedAt: updatedAt)
    }

    private func custom(_ roster: [SubAgentDefinition]) -> [SubAgentDefinition] {
        roster.filter { $0.id != SubAgentDefinition.builtInId }
    }

    // MARK: - The reported bug

    func testSameNameDifferentIdCollapsesToTheNewer() {
        let older = agent("coding-agent", id: "aaa", updatedAt: Date(timeIntervalSince1970: 100))
        let newer = agent("coding-agent", id: "zzz", updatedAt: Date(timeIntervalSince1970: 200))

        let merged = custom(SubAgentRoster.merge(local: [older], remote: [newer]))
        XCTAssertEqual(merged.count, 1, "the rival record must not survive")
        XCTAssertEqual(merged.first?.id, "zzz", "newer updatedAt wins")
    }

    func testTheLoserIsDroppedWholeNotFieldMerged() {
        // Whole-record replacement is a deliberate product decision: folding two
        // independently authored agents together would synthesise a third that
        // neither user wrote.
        let localA = agent("agent", id: "aaa", updatedAt: Date(timeIntervalSince1970: 100),
                           instructions: "LOCAL-ONLY")
        let remoteB = agent("agent", id: "bbb", updatedAt: Date(timeIntervalSince1970: 200),
                            instructions: "REMOTE")

        let merged = custom(SubAgentRoster.merge(local: [localA], remote: [remoteB]))
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged.first?.instructions, "REMOTE",
                       "the winner's fields are taken verbatim — no field-level merge")
    }

    // MARK: - Determinism (the whole point of the tie-break)

    func testTieOnUpdatedAtPicksTheLexicographicallySmallerId() {
        let sameStamp = Date(timeIntervalSince1970: 500)
        let a = agent("dup", id: "aaa-111", updatedAt: sameStamp)
        let b = agent("dup", id: "bbb-222", updatedAt: sameStamp)

        let merged = custom(SubAgentRoster.merge(local: [a], remote: [b]))
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged.first?.id, "aaa-111")
    }

    func testBothDevicesReachTheSameWinnerOnATie() {
        // The two devices see mirrored inputs: what is "local" on one is
        // "remote" on the other. If the merge were order-sensitive they would
        // each keep a different record and re-upload rival rosters forever.
        let sameStamp = Date(timeIntervalSince1970: 500)
        let a = agent("dup", id: "aaa-111", updatedAt: sameStamp)
        let b = agent("dup", id: "bbb-222", updatedAt: sameStamp)

        let deviceA = custom(SubAgentRoster.merge(local: [a], remote: [b]))
        let deviceB = custom(SubAgentRoster.merge(local: [b], remote: [a]))

        XCTAssertEqual(deviceA.map(\.id), deviceB.map(\.id),
                       "swapping the inputs must not change the winner")
        XCTAssertEqual(deviceA.first?.id, "aaa-111")
    }

    func testNewerWinsRegardlessOfWhichSideItIsOn() {
        let older = agent("dup", id: "zzz", updatedAt: Date(timeIntervalSince1970: 100))
        let newer = agent("dup", id: "aaa", updatedAt: Date(timeIntervalSince1970: 900))

        // Newer is the lexicographically SMALLER id here, and the LARGER one in
        // the mirrored run — so a timestamp comparison, not the tie-break, has
        // to be what decides both.
        XCTAssertEqual(custom(SubAgentRoster.merge(local: [older], remote: [newer])).first?.id, "aaa")
        XCTAssertEqual(custom(SubAgentRoster.merge(local: [newer], remote: [older])).first?.id, "aaa")
    }

    // MARK: - Name folding matches resolve()

    func testCaseAndDiacriticDifferingNamesAreTheSameName() {
        let local = agent("Coding-Agent", id: "aaa", updatedAt: Date(timeIntervalSince1970: 100))
        let remote = agent("coding-agent", id: "bbb", updatedAt: Date(timeIntervalSince1970: 200))

        let merged = custom(SubAgentRoster.merge(local: [local], remote: [remote]))
        XCTAssertEqual(merged.count, 1, "case must not make these two different agents")

        let accented = agent("Café", id: "ccc", updatedAt: Date(timeIntervalSince1970: 100))
        let plain = agent("cafe", id: "ddd", updatedAt: Date(timeIntervalSince1970: 200))
        XCTAssertEqual(custom(SubAgentRoster.merge(local: [accented], remote: [plain])).count, 1,
                       "diacritics must not make these two different agents")
    }

    func testWhitespaceOnlyDifferencesAreTheSameName() {
        let padded = agent("  reviewer  ", id: "aaa", updatedAt: Date(timeIntervalSince1970: 100))
        let bare = agent("reviewer", id: "bbb", updatedAt: Date(timeIntervalSince1970: 200))
        XCTAssertEqual(custom(SubAgentRoster.merge(local: [padded], remote: [bare])).count, 1)
    }

    func testTheMergeAgreesWithResolve() {
        // The invariant that makes this correct: anything the merge collapses is
        // exactly what resolve() could not have told apart.
        let a = agent("Coding-Agent", id: "aaa", updatedAt: Date(timeIntervalSince1970: 100))
        let b = agent("coding-agent", id: "bbb", updatedAt: Date(timeIntervalSince1970: 200))
        let merged = SubAgentRoster.merge(local: [a], remote: [b])

        XCTAssertEqual(custom(merged).count, 1)
        XCTAssertEqual(SubAgentRoster.resolve(name: "CODING-AGENT", in: merged)?.id, "bbb")
        XCTAssertEqual(SubAgentRoster.resolve(name: "coding-agent", in: merged)?.id, "bbb")
    }

    // MARK: - The built-in is untouchable

    func testTheBuiltInSurvivesARemoteNameCollision() {
        let builtIn = SubAgentDefinition.makeBuiltIn()
        // A remote custom record claiming the built-in's name, with a much newer
        // stamp — timestamp LWW alone would hand it the slot.
        let impostor = agent(builtIn.name, id: "zzz", updatedAt: Date(timeIntervalSince1970: 9_999_999))

        let merged = SubAgentRoster.merge(local: [builtIn], remote: [impostor])
        XCTAssertTrue(merged.contains { $0.id == SubAgentDefinition.builtInId },
                      "the built-in must never be deduped away")
        XCTAssertFalse(merged.contains { $0.id == "zzz" },
                       "the colliding record is the one that loses")
    }

    func testTheBuiltInIsRestoredEvenIfNeitherSideHasIt() {
        // normalize() guarantees it; merge must not bypass that.
        let merged = SubAgentRoster.merge(local: [agent("x", id: "aaa")], remote: [])
        XCTAssertEqual(merged.first?.id, SubAgentDefinition.builtInId)
    }

    // MARK: - Everything else is preserved

    func testDistinctNamesFromBothSidesAllSurvive() {
        let local = [agent("alpha", id: "a1"), agent("beta", id: "b1")]
        let remote = [agent("gamma", id: "g1"), agent("delta", id: "d1")]

        let names = Set(custom(SubAgentRoster.merge(local: local, remote: remote)).map { $0.name })
        XCTAssertEqual(names, ["alpha", "beta", "gamma", "delta"],
                       "a remote-only agent must reach this device — that is the other half of the bug")
    }

    func testTheSameRecordOnBothSidesTakesTheNewerCopy() {
        let old = agent("shared", id: "same", updatedAt: Date(timeIntervalSince1970: 100),
                        instructions: "OLD")
        let new = agent("shared", id: "same", updatedAt: Date(timeIntervalSince1970: 200),
                        instructions: "NEW")
        let merged = custom(SubAgentRoster.merge(local: [old], remote: [new]))
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged.first?.instructions, "NEW")
    }

    func testTheCountBoundStillHoldsAfterMerging() {
        // Two devices that each filled their roster: 2 * 9 custom + built-in.
        let local = (0..<9).map { agent("local-\($0)", id: "l\($0)") }
        let remote = (0..<9).map { agent("remote-\($0)", id: "r\($0)") }

        let merged = SubAgentRoster.merge(local: local, remote: remote)
        XCTAssertLessThanOrEqual(merged.count, SubAgentLimits.maxCount,
                                 "normalize's count bound must survive the merge")
        XCTAssertEqual(merged.first?.id, SubAgentDefinition.builtInId)
    }

    func testSortOrderIsDenseAndBuiltInFirstAfterMerging() {
        let merged = SubAgentRoster.merge(
            local: [agent("a", id: "a1", updatedAt: Date(timeIntervalSince1970: 10))],
            remote: [agent("b", id: "b1", updatedAt: Date(timeIntervalSince1970: 20))]
        )
        XCTAssertEqual(merged.map(\.sortOrder), Array(0..<merged.count),
                       "renumbering must be dense so the UI order is stable")
    }

    /// [T-subagent-own-store] The exact shape `mergeSubAgentV3` passes: the
    /// whole local roster against ONE inbound definition.
    func testSingleInboundRecordCollapsesAgainstALocalSameNameAgent() {
        let localRoster = [agent("coding-agent", id: "local-uuid",
                                 updatedAt: Date(timeIntervalSince1970: 100)),
                           agent("reviewer", id: "keep-me")]
        let inbound = agent("Coding-Agent", id: "remote-uuid",
                            updatedAt: Date(timeIntervalSince1970: 200))

        let merged = custom(SubAgentRoster.merge(local: localRoster, remote: [inbound]))
        XCTAssertEqual(merged.count, 2, "the collision collapses; the unrelated agent stays")
        XCTAssertTrue(merged.contains { $0.id == "remote-uuid" }, "newer inbound wins")
        XCTAssertFalse(merged.contains { $0.id == "local-uuid" }, "the rival local record is dropped")
        XCTAssertTrue(merged.contains { $0.id == "keep-me" })
    }

    /// An inbound record OLDER than the local one must not apply — the merger
    /// compares the result against the prior roster and skips when unchanged.
    func testAnOlderInboundRecordLeavesTheRosterUnchanged() {
        let localRoster = SubAgentRoster.normalize([
            agent("coding-agent", id: "local-uuid", updatedAt: Date(timeIntervalSince1970: 900)),
        ])
        let inbound = agent("coding-agent", id: "remote-uuid",
                            updatedAt: Date(timeIntervalSince1970: 100))

        let merged = SubAgentRoster.merge(local: localRoster, remote: [inbound])
        XCTAssertEqual(merged, localRoster, "an older inbound record is a no-op")
    }

    func testEmptyRemoteLeavesTheLocalRosterIntact() {
        let local = [agent("alpha", id: "a1"), agent("beta", id: "b1")]
        let merged = custom(SubAgentRoster.merge(local: local, remote: []))
        XCTAssertEqual(Set(merged.map(\.id)), ["a1", "b1"])
    }
}
