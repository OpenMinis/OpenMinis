import XCTest
@testable import Minis

/// [T-backup-thinking-rules] User-authored thinking rules used to be dropped by
/// backup entirely: they live in their own table (`provider_thinking_rules`),
/// not in `ProviderConfig`, and `exportProviders` only serialized the config
/// object. Restoring therefore lost exactly the settings a user had gone out of
/// their way to author — including the custom request-body parameters carried
/// opaquely in `wire_format_json` (`extraBodyToggle` and friends).
///
/// These tests pin the record's round trip. The wire-format payload is the
/// part that matters most: the whole design stores it as one opaque JSON
/// column so a new format case needs no migration, so the backup must carry it
/// through byte-identically rather than re-encoding it.
final class BackupThinkingRulesRoundTripTests: XCTestCase {

    /// Mirrors the exporter/importer pair: JSONL envelope + the `.iso8601` date
    /// strategy both sides configure.
    private func roundTrip(_ record: BackupThinkingRuleRecord) throws -> BackupThinkingRuleRecord {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        let line = try encoder.encode(BackupRecordEnvelope(t: "ThinkingRuleV1", d: record))

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(BackupRecordEnvelope<BackupThinkingRuleRecord>.self,
                                  from: line).d
    }

    /// A realistic custom rule — an `extraBodyToggle`, which is precisely the
    /// "custom request parameter" class the report named — survives intact.
    func testExtraBodyToggleRuleSurvivesRoundTrip() throws {
        let wire = #"{"kind":"extraBodyToggle","path":"chat_template_kwargs.thinking"}"#
        let original = BackupThinkingRuleRecord(
            id: "11111111-2222-3333-4444-555555555555",
            instanceId: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
            sortOrder: 3,
            scopeKind: "modelPattern",
            scopePattern: "qwen*",
            wireFormatJson: wire,
            echoField: "reasoning_content",
            echoTiming: "afterToolUseOnly",
            label: "Qwen thinking toggle",
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            updatedAt: Date(timeIntervalSince1970: 1_700_000_500)
        )

        let back = try roundTrip(original)

        XCTAssertEqual(back.id, original.id)
        XCTAssertEqual(back.instanceId, original.instanceId)
        XCTAssertEqual(back.sortOrder, original.sortOrder)
        XCTAssertEqual(back.scopeKind, original.scopeKind)
        XCTAssertEqual(back.scopePattern, original.scopePattern)
        // The decisive assertion: opaque payload carried through unchanged.
        XCTAssertEqual(back.wireFormatJson, wire)
        XCTAssertEqual(back.echoField, original.echoField)
        XCTAssertEqual(back.echoTiming, original.echoTiming)
        XCTAssertEqual(back.label, original.label)
        XCTAssertEqual(back.createdAt.timeIntervalSince1970,
                       original.createdAt.timeIntervalSince1970, accuracy: 1)
        XCTAssertEqual(back.updatedAt.timeIntervalSince1970,
                       original.updatedAt.timeIntervalSince1970, accuracy: 1)
    }

    /// The `allModels` scope leaves `scopePattern` nil; nil must stay nil
    /// rather than becoming an empty string, because
    /// `ThinkingRuleCoding.fromPersisted(kind:pattern:)` distinguishes them.
    func testAllModelsScopeKeepsNilPattern() throws {
        let record = BackupThinkingRuleRecord(
            id: "rule-2", instanceId: "inst-2", sortOrder: 0,
            scopeKind: "allModels", scopePattern: nil,
            wireFormatJson: #"{"kind":"reasoningEffort"}"#,
            echoField: nil, echoTiming: nil, label: "",
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0)
        )
        let back = try roundTrip(record)
        XCTAssertNil(back.scopePattern)
        XCTAssertNil(back.echoField)
        XCTAssertNil(back.echoTiming)
        XCTAssertEqual(back.label, "")
    }

    /// A wire format this build does not understand must still survive the
    /// round trip untouched — that is the entire reason the column is opaque,
    /// and it is what lets an older build restore a newer package without
    /// corrupting the rule.
    func testUnknownWireFormatIsCarriedOpaquely() throws {
        let exotic = #"{"kind":"someFutureFormat","nested":{"a":[1,2,3]},"flag":true}"#
        let record = BackupThinkingRuleRecord(
            id: "rule-3", instanceId: "inst-3", sortOrder: 1,
            scopeKind: "allModels", scopePattern: nil,
            wireFormatJson: exotic,
            echoField: nil, echoTiming: nil, label: "From a newer build",
            createdAt: Date(timeIntervalSince1970: 10),
            updatedAt: Date(timeIntervalSince1970: 20)
        )
        XCTAssertEqual(try roundTrip(record).wireFormatJson, exotic)
    }
}
