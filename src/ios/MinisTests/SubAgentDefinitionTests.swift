import XCTest
@testable import Minis

/// Sub Agent roster rules and model resolution. [T-sub-agents-v1]
///
/// `SubAgentModelResolver.resolve` needs a live AIChatViewModel and store, so
/// what is pinned here are the pure pieces it is built from: the roster rules,
/// and the inputs that decide which of its two branches (pinned / inherited)
/// runs. What these cover is what cannot be seen on a screenshot — that synced
/// garbage cannot break startup, and that the retired primary/sub tier names
/// are gone rather than lingering as vestigial values.
final class SubAgentDefinitionTests: XCTestCase {

    private func custom(_ name: String, order: Int = 1, group: String? = nil) -> SubAgentDefinition {
        SubAgentDefinition(name: name, description: "desc for \(name)",
                           modelGroupId: group, sortOrder: order)
    }

    // MARK: - ensureBuiltIn / self-heal

    func testEmptyRosterGainsTheBuiltIn() {
        let out = SubAgentRoster.normalize([])
        XCTAssertEqual(out.count, 1)
        XCTAssertEqual(out[0].id, SubAgentDefinition.builtInId)
        XCTAssertTrue(out[0].isBuiltIn)
        XCTAssertEqual(out[0].sortOrder, 0)
    }

    func testRosterWithoutBuiltInGetsItBack() {
        let out = SubAgentRoster.normalize([custom("Translator"), custom("Reviewer", order: 2)])
        XCTAssertEqual(out.count, 3)
        XCTAssertEqual(out[0].id, SubAgentDefinition.builtInId, "built-in is always first")
        XCTAssertEqual(out.map(\.name).dropFirst(), ["Translator", "Reviewer"])
    }

    /// A synced roster can carry the built-in anywhere in the array; it is
    /// pinned to the front rather than left where its sortOrder put it.
    func testBuiltInIsPulledToTheFront() {
        var builtIn = SubAgentDefinition.makeBuiltIn()
        builtIn.sortOrder = 7
        let out = SubAgentRoster.normalize([custom("A"), builtIn, custom("B", order: 2)])
        XCTAssertEqual(out[0].id, SubAgentDefinition.builtInId)
        XCTAssertEqual(out[0].sortOrder, 0)
    }

    /// `isBuiltIn` drives "cannot delete" in the UI, so a custom row that
    /// asserts it (hand-edited or from a hostile peer) must not be honoured.
    func testCustomEntryCannotClaimBuiltInIdentity() {
        let impostor = SubAgentDefinition(id: "x", name: "Impostor", description: "d", isBuiltIn: true)
        let out = SubAgentRoster.normalize([impostor])
        XCTAssertEqual(out.count, 1, "the impostor is dropped, not promoted")
        XCTAssertEqual(out[0].id, SubAgentDefinition.builtInId)
    }

    // MARK: - Bounds (synced data may exceed them)

    func testOverLimitRosterTruncatesToMaxCountWithoutThrowing() {
        let many = (1...25).map { custom("Agent \($0)", order: $0) }
        let out = SubAgentRoster.normalize(many)
        XCTAssertEqual(out.count, SubAgentLimits.maxCount)
        XCTAssertEqual(out[0].id, SubAgentDefinition.builtInId, "the built-in keeps its slot")
        XCTAssertEqual(out.last?.name, "Agent 9", "the first 9 custom entries by sortOrder survive")
    }

    func testOverLongFieldsAreClampedNotRejected() {
        let fat = SubAgentDefinition(
            name: String(repeating: "n", count: 200),
            description: String(repeating: "d", count: 5000),
            instructions: String(repeating: "i", count: 99_000)
        )
        let out = SubAgentRoster.normalize([fat])
        let kept = out[1]
        XCTAssertEqual(kept.name.count, SubAgentLimits.nameMaxLength)
        XCTAssertEqual(kept.description.count, SubAgentLimits.descriptionMaxLength)
        XCTAssertEqual(kept.instructions.count, SubAgentLimits.instructionsMaxLength)
    }

    func testNormalizeIsIdempotent() {
        let once = SubAgentRoster.normalize((1...15).map { custom("A\($0)", order: $0) })
        XCTAssertEqual(SubAgentRoster.normalize(once), once)
    }

    func testSortOrderIsRenumberedDensely() {
        let out = SubAgentRoster.normalize([custom("B", order: 90), custom("A", order: 40)])
        XCTAssertEqual(out.map(\.sortOrder), [0, 1, 2])
        XCTAssertEqual(out.map(\.name).dropFirst(), ["A", "B"], "user order preserved, gaps closed")
    }

    // MARK: - Name resolution (what the model passes back)

    func testOmittedAgentResolvesToBuiltIn() {
        let roster = SubAgentRoster.normalize([custom("Translator")])
        XCTAssertEqual(SubAgentRoster.resolve(name: nil, in: roster)?.id, SubAgentDefinition.builtInId)
        XCTAssertEqual(SubAgentRoster.resolve(name: "   ", in: roster)?.id, SubAgentDefinition.builtInId)
    }

    func testNameMatchIsCaseAndWhitespaceInsensitive() {
        let roster = SubAgentRoster.normalize([custom("Translator")])
        XCTAssertEqual(SubAgentRoster.resolve(name: "translator", in: roster)?.name, "Translator")
        XCTAssertEqual(SubAgentRoster.resolve(name: "  TRANSLATOR  ", in: roster)?.name, "Translator")
    }

    /// An unknown name must be reported, never silently run as the built-in —
    /// that is what makes the `unknown_agent` rejection possible.
    func testUnknownNameResolvesToNil() {
        let roster = SubAgentRoster.normalize([custom("Translator")])
        XCTAssertNil(SubAgentRoster.resolve(name: "Nonexistent", in: roster))
    }

    // MARK: - Codable / ProviderConfig integration

    func testDefinitionRoundTripsThroughJSON() throws {
        let original = SubAgentDefinition(name: "Reviewer", description: "Reviews code",
                                          instructions: "Be terse.", modelGroupId: "grp-1",
                                          sortOrder: 3)
        let data = try JSONEncoder().encode(original)
        let back = try JSONDecoder().decode(SubAgentDefinition.self, from: data)
        XCTAssertEqual(back, original)
    }

    /// [T-subagent-own-store] The roster's own file decodes to a valid roster
    /// even when it is absent or malformed — this runs on data that may have
    /// arrived over iCloud from a newer build, and must never block startup.
    func testAbsentRosterNormalizesToBuiltInOnly() throws {
        let roster = SubAgentRoster.normalize([])
        XCTAssertEqual(roster.count, 1)
        XCTAssertEqual(roster[0].id, SubAgentDefinition.builtInId)
    }

    func testOverLimitRosterIsClampedOnLoad() throws {
        let entries = (1...30).map {
            "{\"id\":\"id-\($0)\",\"name\":\"A\($0)\",\"description\":\"d\",\"instructions\":\"\",\"isBuiltIn\":false,\"sortOrder\":\($0),\"updatedAt\":0}"
        }.joined(separator: ",")
        let decoded = try JSONDecoder().decode([SubAgentDefinition].self, from: Data("[\(entries)]".utf8))
        let roster = SubAgentRoster.normalize(decoded)
        XCTAssertEqual(roster.count, SubAgentLimits.maxCount)
        XCTAssertEqual(roster[0].id, SubAgentDefinition.builtInId)
    }

    /// [T-subagent-own-store] The roster array round-trips through the exact
    /// encode/decode pair `SubAgentStore` persists with. This is the round-trip
    /// that was structurally broken while sub agents rode inside ProviderConfig:
    /// `save()` wrote them to provider-config.json, but the v3 SQLite mirror had
    /// no sub-agent table, and the launch path rebuilt `config` from that mirror
    /// — so the complete copy was overwritten by the lossy one and every custom
    /// agent silently collapsed to just the built-in.
    func testRosterFileRoundTripPreservesCustomAgents() throws {
        let roster = SubAgentRoster.normalize([
            SubAgentDefinition(id: "a1", name: "coding-agent", description: "Writes code",
                               instructions: "Be terse.", modelGroupId: "grp-1"),
            SubAgentDefinition(id: "a2", name: "reviewer", description: "Reviews diffs"),
        ])

        let data = try JSONEncoder().encode(roster)
        let back = SubAgentRoster.normalize(try JSONDecoder().decode([SubAgentDefinition].self, from: data))

        XCTAssertEqual(back, roster, "the roster must survive a write/reload verbatim")
        XCTAssertEqual(back.count, 3, "built-in + the two custom agents")
        XCTAssertTrue(back.contains { $0.id == "a1" }, "a custom agent must not be lost on reload")
        XCTAssertTrue(back.contains { $0.id == "a2" })
        XCTAssertEqual(back.first?.id, SubAgentDefinition.builtInId, "built-in stays first")
        XCTAssertEqual(back.first(where: { $0.id == "a1" })?.modelGroupId, "grp-1",
                       "the group foreign key survives — it is the only provider reference left")
    }

    // MARK: - Model origin

    /// [T-sub-agents-v1] The tier mechanism is gone: a sub agent's model comes
    /// only from its own definition, so there are exactly two origins.
    func testModelOriginRawValues() {
        XCTAssertEqual(HelperModelOrigin.pinned.rawValue, "pinned")
        XCTAssertEqual(HelperModelOrigin.inherited.rawValue, "inherited")
        XCTAssertEqual(HelperModelOrigin(rawValue: "pinned"), .pinned)
        XCTAssertEqual(HelperModelOrigin(rawValue: "inherited"), .inherited)
        XCTAssertNil(HelperModelOrigin(rawValue: "primary"), "the old tier names must not resolve")
        XCTAssertNil(HelperModelOrigin(rawValue: "sub"))
    }

    /// A definition with no pinned group inherits; one with a group is pinned.
    /// (The resolver itself needs a live view model; what is pinned here is the
    /// input that decides which branch it takes.)
    func testPinnedIsDecidedByTheDefinitionAlone() {
        XCTAssertNil(custom("Plain").modelGroupId, "no pin = inherit the parent's binding")
        XCTAssertEqual(custom("Fast", group: "grp-1").modelGroupId, "grp-1")
    }

    /// Identity carries the origin; the legacy tier fields are read-only
    /// history and must stay empty on anything this build writes.
    func testIdentityCarriesOriginNotTier() {
        let id = HelperModelIdentity(modelOrigin: HelperModelOrigin.pinned.rawValue)
        XCTAssertEqual(id.modelOrigin, "pinned")
        XCTAssertNil(id.tierRequested)
        XCTAssertNil(id.tierUsed)
        let payload = id.payload()
        XCTAssertEqual(payload["model_origin"] as? String, "pinned")
        XCTAssertNil(payload["tier_used"])
    }

    /// An old transcript's tier values still decode when the payload is a real
    /// identity, so history renders unchanged. (Tier keys ALONE stay nil by
    /// design — `init?(payload:)` requires at least one `model_*` key, and that
    /// predates this change.)
    func testLegacyTierValuesStillDecodeAlongsideAnIdentity() {
        guard let id = HelperModelIdentity(payload: [
            "tier_requested": "sub", "tier_used": "primary", "model_resolved_name": "Old Model",
        ]) else {
            return XCTFail("a payload with model_* keys must decode")
        }
        XCTAssertEqual(id.tierRequested, "sub")
        XCTAssertEqual(id.tierUsed, "primary")
        XCTAssertNil(id.modelOrigin, "an old payload has no origin")
        XCTAssertNil(HelperModelIdentity(payload: ["tier_used": "primary"]),
                     "tier keys alone are not an identity — unchanged behaviour")
    }
}
