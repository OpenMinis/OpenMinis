import XCTest
@testable import Minis

/// [T-backup-category-counts] `entries` must be the count in the unit the user
/// thinks in, and nothing else may be folded into it.
///
/// The three bugs this pins, all of which shipped:
///   - skills reported the SKILL count but threw away the file count
///   - mcp_servers reported a hard-coded `1` — the config FILE — for any
///     number of servers, so seven servers rendered as "1"
///   - providers reported `instances + thinkingRules`, so 8 providers and 1
///     rule rendered as "9 providers"
///
/// Android fixed the same three in acab8c732; these tests encode the same
/// contract so the two platforms cannot drift apart again.
final class BackupCategoryCountTests: XCTestCase {

    // MARK: - MCP server counting

    private func writeServers(_ json: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("servers-\(UUID().uuidString).json")
        try json.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    func testCountsEveryDeclaredServer() throws {
        let url = try writeServers("""
        {"mcpServers": {
            "fs":      {"command": "minis-mcp-cli"},
            "github":  {"command": "gh-mcp"},
            "sqlite":  {"command": "sqlite-mcp"},
            "fetch":   {"command": "fetch-mcp"},
            "memory":  {"command": "mem-mcp"},
            "shell":   {"command": "sh-mcp"},
            "browser": {"command": "br-mcp"}
        }}
        """)
        defer { try? FileManager.default.removeItem(at: url) }
        // The bug: this used to be 1 regardless.
        XCTAssertEqual(BackupExporter.mcpServerCount(at: url), 7)
    }

    func testCountsASingleServerAsOne() throws {
        let url = try writeServers(#"{"mcpServers": {"fs": {"command": "x"}}}"#)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertEqual(BackupExporter.mcpServerCount(at: url), 1)
    }

    func testEmptyServerMapCountsZero() throws {
        let url = try writeServers(#"{"mcpServers": {}}"#)
        defer { try? FileManager.default.removeItem(at: url) }
        // Zero is the honest answer for a file that declares no servers — and
        // it must not be confused with "unreadable", which returns nil.
        XCTAssertEqual(BackupExporter.mcpServerCount(at: url), 0)
    }

    /// Externally-authored configs carry keys we do not model. Counting must
    /// survive them, which is why the parse is JSONSerialization and not the
    /// typed decoder.
    func testCountsServersWithUnknownKeys() throws {
        let url = try writeServers("""
        {"mcpServers": {
            "fs": {"command": "x", "somethingNew": {"nested": true}, "args": ["--flag"]},
            "gh": {"url": "https://example.invalid/mcp", "headers": {"A": "b"}}
        }, "topLevelExtra": 42}
        """)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertEqual(BackupExporter.mcpServerCount(at: url), 2)
    }

    func testUnreadableFileReturnsNilSoCallerCanFallBack() throws {
        let missing = FileManager.default.temporaryDirectory
            .appendingPathComponent("nope-\(UUID().uuidString).json")
        XCTAssertNil(BackupExporter.mcpServerCount(at: missing))

        let garbage = try writeServers("not json at all {{{")
        defer { try? FileManager.default.removeItem(at: garbage) }
        XCTAssertNil(BackupExporter.mcpServerCount(at: garbage))

        // Right JSON, wrong shape — still nil, not 0, so the caller's `?? 1`
        // keeps the old behaviour rather than claiming the file is empty.
        let wrongShape = try writeServers(#"{"servers": {"a": {}}}"#)
        defer { try? FileManager.default.removeItem(at: wrongShape) }
        XCTAssertNil(BackupExporter.mcpServerCount(at: wrongShape))
    }

    // MARK: - CategoryStat wire format

    private func roundTrip(_ stat: BackupManifest.CategoryStat) throws
        -> (json: [String: Any], decoded: BackupManifest.CategoryStat) {
        let data = try JSONEncoder().encode(stat)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let decoded = try JSONDecoder().decode(BackupManifest.CategoryStat.self, from: data)
        return (json, decoded)
    }

    func testSkillsCarryEntriesAndFilesSeparately() throws {
        let stat = BackupManifest.CategoryStat(
            entries: 12, bytes: 4096, encrypted: false, files: 569)
        let (json, decoded) = try roundTrip(stat)
        XCTAssertEqual(decoded.entries, 12, "entries is the SKILL count")
        XCTAssertEqual(decoded.files, 569, "the 569 files are reported alongside, not as entries")
        XCTAssertEqual(json["entries"] as? Int, 12)
        XCTAssertEqual(json["files"] as? Int, 569)
    }

    func testProvidersKeepRulesOutOfEntries() throws {
        let stat = BackupManifest.CategoryStat(
            entries: 8, bytes: 1024, encrypted: false,
            thinkingRules: 1, includesCredentials: true)
        let (json, decoded) = try roundTrip(stat)
        XCTAssertEqual(decoded.entries, 8, "8 providers must not become 9")
        XCTAssertEqual(decoded.thinkingRules, 1)
        // snake_case on the wire, matching Android.
        XCTAssertEqual(json["thinking_rules"] as? Int, 1)
        XCTAssertNil(json["thinkingRules"], "must not also emit a camelCase key")
    }

    /// Categories that carry no rules must not gain the key at all, so their
    /// manifest bytes are unchanged from before this field existed.
    func testThinkingRulesKeyOmittedWhenNil() throws {
        let stat = BackupManifest.CategoryStat(entries: 3, bytes: 10, encrypted: false)
        let (json, decoded) = try roundTrip(stat)
        XCTAssertNil(decoded.thinkingRules)
        XCTAssertFalse(json.keys.contains("thinking_rules"))
        XCTAssertFalse(json.keys.contains("files"))
    }

    // MARK: - Backward / forward compatibility

    /// A package written before these fields existed must still decode, with
    /// the new fields simply absent.
    func testHistoricalPackageWithoutNewFieldsStillDecodes() throws {
        let legacy = Data(#"{"entries": 9, "bytes": 2048, "encrypted": false}"#.utf8)
        let stat = try JSONDecoder().decode(BackupManifest.CategoryStat.self, from: legacy)
        XCTAssertEqual(stat.entries, 9)
        XCTAssertEqual(stat.bytes, 2048)
        XCTAssertNil(stat.files)
        XCTAssertNil(stat.thinkingRules)
        XCTAssertNil(stat.includesCredentials)
    }

    /// A newer/other-platform package may carry keys this build does not know.
    /// Decoding must ignore them rather than fail the whole restore.
    func testUnknownFieldsAreIgnored() throws {
        let future = Data("""
        {"entries": 5, "bytes": 64, "encrypted": false, "thinking_rules": 2,
         "files": 7, "somethingFromTheFuture": {"a": [1, 2, 3]}, "another": "x"}
        """.utf8)
        let stat = try JSONDecoder().decode(BackupManifest.CategoryStat.self, from: future)
        XCTAssertEqual(stat.entries, 5)
        XCTAssertEqual(stat.thinkingRules, 2)
        XCTAssertEqual(stat.files, 7)
    }

    /// The Android wire shape (acab8c732) must decode here unchanged — this is
    /// the cross-platform contract, not just an internal one.
    func testAndroidWireShapeDecodes() throws {
        let android = Data("""
        {"entries": 12, "bytes": 999, "encrypted": false, "files": 569,
         "thinking_rules": 3, "includes_credentials": true}
        """.utf8)
        let stat = try JSONDecoder().decode(BackupManifest.CategoryStat.self, from: android)
        XCTAssertEqual(stat.entries, 12)
        XCTAssertEqual(stat.files, 569)
        XCTAssertEqual(stat.thinkingRules, 3)
        XCTAssertEqual(stat.includesCredentials, true)
    }
}
