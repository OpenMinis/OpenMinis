import XCTest
@testable import Minis

/// `minis-config subagents` — the rules that make the CLI honest about what the
/// built-in sub agent allows. [T-sub-agents-cli]
///
/// The collection's writers go through `ProviderConfigStore.shared`, which is a
/// live singleton backed by the real config file, so these cover the pure parts:
/// the path-alias mapping that makes the built-in addressable at all, and the
/// permission split the field factories encode. The end-to-end add / edit /
/// reorder / delete lifecycle was exercised on device against the real CLI.
@MainActor
final class SubAgentsCollectionTests: XCTestCase {

    private let collection = SubAgentsCollection()

    // MARK: - Path alias

    /// The built-in's id contains a dot (`builtin.general`) and
    /// `ConfigRegistry.resolveField` splits paths with `maxSplits: 2`, so the
    /// raw id would parse as id=`builtin`, leaf=`general.…` and resolve to
    /// nothing — leaving the one agent that MUST stay editable unreachable.
    func testBuiltInIdContainsADotWhichIsWhyTheAliasExists() {
        XCTAssertTrue(SubAgentDefinition.builtInId.contains("."),
                      "if this ever stops being true the alias can go away")
        XCTAssertFalse(SubAgentsCollection.builtInAlias.contains("."))
    }

    /// A path built from the alias splits into exactly three segments.
    func testAliasedPathSplitsIntoBaseIdLeaf() {
        let path = "subagents.\(SubAgentsCollection.builtInAlias).instructions"
        let segments = path.split(separator: ".", maxSplits: 2,
                                  omittingEmptySubsequences: true).map(String.init)
        XCTAssertEqual(segments, ["subagents", "general", "instructions"])
    }

    /// The raw id does NOT, which is the bug the alias fixes.
    func testRawBuiltInPathWouldMisSplit() {
        let path = "subagents.\(SubAgentDefinition.builtInId).instructions"
        let segments = path.split(separator: ".", maxSplits: 2,
                                  omittingEmptySubsequences: true).map(String.init)
        XCTAssertEqual(segments, ["subagents", "builtin", "general.instructions"],
                       "id=builtin, leaf=general.instructions — resolves to nothing")
    }

    /// Every field the collection exposes for the built-in is addressable under
    /// the alias, so `get`/`set` can reach all of them.
    func testBuiltInFieldsArePublishedUnderTheAlias() {
        let fields = collection.fields(for: SubAgentsCollection.builtInAlias)
        XCTAssertFalse(fields.isEmpty, "the built-in must be reachable by alias")
        let prefix = "subagents.\(SubAgentsCollection.builtInAlias)."
        for f in fields {
            XCTAssertTrue(f.path.hasPrefix(prefix), "unexpected path \(f.path)")
            XCTAssertEqual(f.path.split(separator: ".", maxSplits: 2).count, 3)
        }
        let leaves = Set(fields.map { $0.path.replacingOccurrences(of: prefix, with: "") })
        XCTAssertEqual(leaves, ["name", "description", "instructions", "model_group", "built_in"])
    }

    /// `childIds()` reports the alias, not the raw id — otherwise `list`
    /// would print a path that cannot be used.
    func testChildIdsReportTheAliasForTheBuiltIn() {
        let ids = collection.childIds()
        XCTAssertTrue(ids.contains(SubAgentsCollection.builtInAlias))
        XCTAssertFalse(ids.contains(SubAgentDefinition.builtInId))
    }

    // MARK: - Built-in permissions

    /// Name and description are read-only for the built-in: the model matches
    /// on the name and reads the description, and `SubAgentRoster.normalize`
    /// restores both on every load, so accepting a write would be a lie.
    func testBuiltInNameAndDescriptionRefuseWrites() {
        let fields = collection.fields(for: SubAgentsCollection.builtInAlias)
        for leaf in ["name", "description"] {
            guard let f = fields.first(where: { $0.path.hasSuffix(".\(leaf)") }) else {
                return XCTFail("missing \(leaf)")
            }
            XCTAssertThrowsError(try f.write(.string("anything")), "\(leaf) must refuse") { err in
                guard case ConfigError.permissionDenied(let reason) = err else {
                    return XCTFail("\(leaf): expected permissionDenied, got \(err)")
                }
                // The message has to say what to do instead, not just "no".
                XCTAssertTrue(reason.contains("instructions") || reason.contains("model"),
                              "the refusal should point at what IS writable: \(reason)")
            }
            // [T-subagent-config-honesty] Checking only the throw is what let
            // the bug survive: ClosureField defaults to `.readwrite`, so
            // `topic-help` advertised these paths as writable while every write
            // was refused. An agent that reads the metadata, tries, and is
            // denied concludes the app is broken rather than that it hit a rule.
            XCTAssertEqual(f.access, .readonly,
                           "\(leaf) refuses every write, so it must not be advertised writable")
        }
    }

    /// The mirror of the above: the same two fields on a CUSTOM agent stay
    /// writable. Without this, "make the metadata honest" could be satisfied by
    /// marking name/description read-only everywhere, which would silently take
    /// away the rename the user actually has.
    func testCustomAgentNameAndDescriptionAreAdvertisedWritable() throws {
        let id = try collection.add(.object([
            "name": .string("t-honesty-\(UUID().uuidString.prefix(8))"),
            "description": .string("Temporary agent for access-metadata assertions."),
        ]))
        defer { try? collection.remove(id: id) }

        // Only the built-in is aliased, so a custom agent's path segment is
        // just its id (`pathSegment` itself is private to the collection).
        let fields = collection.fields(for: id)
        XCTAssertFalse(fields.isEmpty, "the new agent should publish fields")
        for leaf in ["name", "description"] {
            guard let f = fields.first(where: { $0.path.hasSuffix(".\(leaf)") }) else {
                return XCTFail("missing \(leaf)")
            }
            XCTAssertEqual(f.access, .readwrite,
                           "\(leaf) is writable on a custom agent and must say so")
        }
    }

    /// Instructions and model stay writable for the built-in — those are the
    /// user's to set, and the built-in's instructions are the standing
    /// instructions for every unnamed delegation.
    func testBuiltInInstructionsAndModelAreWritable() {
        let fields = collection.fields(for: SubAgentsCollection.builtInAlias)
        for leaf in ["instructions", "model_group"] {
            guard let f = fields.first(where: { $0.path.hasSuffix(".\(leaf)") }) else {
                return XCTFail("missing \(leaf)")
            }
            XCTAssertEqual(f.access, .readwrite, "\(leaf) must stay writable on the built-in")
        }
    }

    func testBuiltInFlagIsReadOnly() {
        let fields = collection.fields(for: SubAgentsCollection.builtInAlias)
        let f = fields.first { $0.path.hasSuffix(".built_in") }
        XCTAssertEqual(f?.access, .readonly)
    }

    /// Deleting the built-in is refused with a reason, not silently ignored.
    func testRemovingTheBuiltInIsRefused() {
        XCTAssertThrowsError(try collection.remove(id: SubAgentsCollection.builtInAlias)) { err in
            guard case ConfigError.permissionDenied = err else {
                return XCTFail("expected permissionDenied, got \(err)")
            }
        }
        // Also by its raw id, in case a caller types that.
        XCTAssertThrowsError(try collection.remove(id: SubAgentDefinition.builtInId))
    }

    func testRemovingAnUnknownIdReportsUnknownPath() {
        XCTAssertThrowsError(try collection.remove(id: "no-such-agent")) { err in
            guard case ConfigError.unknownPath = err else {
                return XCTFail("expected unknownPath, got \(err)")
            }
        }
    }

    // MARK: - Add validation

    func testAddRejectsANonObjectPayload() {
        XCTAssertThrowsError(try collection.add(.string("nope")))
    }

    func testAddRequiresNameAndDescription() {
        XCTAssertThrowsError(try collection.add(.object([:])), "name is required")
        XCTAssertThrowsError(try collection.add(.object(["name": .string("A")])),
                             "description is required — it is what the model reads")
        XCTAssertThrowsError(try collection.add(.object([
            "name": .string("  "), "description": .string("d"),
        ])), "a blank name is not a name")
    }

    func testAddRejectsOverLongFields() {
        XCTAssertThrowsError(try collection.add(.object([
            "name": .string(String(repeating: "n", count: SubAgentLimits.nameMaxLength + 1)),
            "description": .string("d"),
        ])))
        XCTAssertThrowsError(try collection.add(.object([
            "name": .string("Fine"),
            "description": .string(String(repeating: "d", count: SubAgentLimits.descriptionMaxLength + 1)),
        ])))
        XCTAssertThrowsError(try collection.add(.object([
            "name": .string("Fine"),
            "description": .string("d"),
            "instructions": .string(String(repeating: "i", count: SubAgentLimits.instructionsMaxLength + 1)),
        ])))
    }

    /// An unknown group must fail loudly rather than storing a dangling id that
    /// would silently fall back to the parent's model at run time.
    func testAddRejectsAnUnknownModelGroup() {
        XCTAssertThrowsError(try collection.add(.object([
            "name": .string("Fine"),
            "description": .string("d"),
            "model_group": .string("definitely-not-a-group-\(UUID().uuidString)"),
        ]))) { err in
            guard case ConfigError.invalidValue(let msg) = err else {
                return XCTFail("expected invalidValue, got \(err)")
            }
            XCTAssertTrue(msg.contains("Available"), "the error should list what IS valid: \(msg)")
        }
    }

    // MARK: - Order field

    /// Order is what the model sees, so a partial list (which would silently
    /// move the omitted agents) and one containing the built-in (which is
    /// always first) are both rejected.
    func testOrderFieldRejectsNonPermutations() {
        let f = SubAgentsCollection.orderField()
        XCTAssertThrowsError(try f.write(.array([.string("not-a-real-id")])))
        XCTAssertThrowsError(try f.write(.array([.string(SubAgentDefinition.builtInId)])))
        XCTAssertThrowsError(try f.write(.string("not-an-array")))
    }

    /// The built-in never appears in the order list.
    func testOrderFieldExcludesTheBuiltIn() throws {
        let value = try SubAgentsCollection.orderField().read()
        guard case .array(let items) = value else { return XCTFail("expected an array") }
        let ids: [String] = items.compactMap { if case .string(let s) = $0 { return s } else { return nil } }
        XCTAssertFalse(ids.contains(SubAgentDefinition.builtInId))
        XCTAssertFalse(ids.contains(SubAgentsCollection.builtInAlias))
    }
}
