import XCTest
@testable import Minis

/// [T-anthropic-cli-version] Unit coverage for the dynamic `claude-cli`
/// fingerprint resolution that replaced the hardcoded `2.1.195` constant.
///
/// The regression these guard against: Anthropic gates new models on a minimum
/// Claude Code version (Fable 5.1 needed >= 2.1.251, Opus 5.5 needs >= 2.1.280)
/// and rejects anything older with `claude_code_version_too_old`.
final class ClaudeCLIVersionTests: XCTestCase {

    override func tearDown() {
        ClaudeCLIVersion.resetForTest()
        super.tearDown()
    }

    // MARK: - Version comparison

    func testComparesDottedVersionsNumericallyNotLexically() {
        // The bug a string compare would introduce: "2.1.9" > "2.1.280"
        XCTAssertGreaterThan(ClaudeCLIVersion.compare("2.1.280", "2.1.9"), 0)
        XCTAssertGreaterThan(ClaudeCLIVersion.compare("2.1.280", "2.1.99"), 0)
        XCTAssertLessThan(ClaudeCLIVersion.compare("2.1.195", "2.1.280"), 0)
        XCTAssertEqual(ClaudeCLIVersion.compare("2.1.284", "2.1.284"), 0)
    }

    func testComparesAcrossMajorAndMinorComponents() {
        XCTAssertGreaterThan(ClaudeCLIVersion.compare("3.0.0", "2.9.999"), 0)
        XCTAssertGreaterThan(ClaudeCLIVersion.compare("2.2.0", "2.1.999"), 0)
    }

    func testTreatsMissingComponentsAsZero() {
        XCTAssertEqual(ClaudeCLIVersion.compare("2.1", "2.1.0"), 0)
        XCTAssertGreaterThan(ClaudeCLIVersion.compare("2.1.1", "2.1"), 0)
    }

    // MARK: - dist-tags parsing

    func testPicksHighestVersionAmongPublishedDistTags() throws {
        // Real payload shape, 2026-09-28.
        let json = #"{"stable":"2.1.277","latest":"2.1.284","next":"2.1.284"}"#
        let data = try XCTUnwrap(json.data(using: .utf8))
        XCTAssertEqual(ClaudeCLIVersion.parseDistTags(data), "2.1.284")
    }

    func testPrefersNewestTagEvenWhenStableLagsBehindAGate() throws {
        // anthropics/claude-code#96130: stable was 2.1.267 while Opus 5.5
        // required 2.1.280. Picking `stable` would still 400.
        let json = #"{"stable":"2.1.267","latest":"2.1.284"}"#
        let data = try XCTUnwrap(json.data(using: .utf8))
        XCTAssertEqual(ClaudeCLIVersion.parseDistTags(data), "2.1.284")
    }

    func testIgnoresNonVersionTagValues() throws {
        let json = #"{"stable":"2.1.277","experimental":"nightly","latest":"2.1.284"}"#
        let data = try XCTUnwrap(json.data(using: .utf8))
        XCTAssertEqual(ClaudeCLIVersion.parseDistTags(data), "2.1.284")
    }

    func testReturnsNilForMalformedOrEmptyPayloads() throws {
        func parse(_ raw: String) -> String? {
            guard let data = raw.data(using: .utf8) else { return nil }
            return ClaudeCLIVersion.parseDistTags(data)
        }
        XCTAssertNil(parse(""))
        XCTAssertNil(parse("not json"))
        XCTAssertNil(parse("{}"))
        XCTAssertNil(parse(#"{"latest":""}"#))
        XCTAssertNil(parse(#"{"latest":"2.1"}"#))
        XCTAssertNil(parse(#"{"latest":"v2.1.284"}"#))
    }

    // MARK: - Fallback + header shape

    func testFallsBackToCompiledInVersionBeforeAnyFetch() {
        ClaudeCLIVersion.resetForTest()
        XCTAssertEqual(ClaudeCLIVersion.current(), ClaudeCLIVersion.fallbackVersion)
    }

    func testCompiledInFallbackClearsGatesKnownAtReleaseTime() {
        // Fable 5.1 needs >= 2.1.251, Opus 5.5 needs >= 2.1.280. A fresh
        // offline install must not reintroduce the 2.1.195 regression.
        XCTAssertGreaterThanOrEqual(
            ClaudeCLIVersion.compare(ClaudeCLIVersion.fallbackVersion, "2.1.251"), 0,
            "fallback must satisfy the Fable 5.1 gate"
        )
        XCTAssertGreaterThanOrEqual(
            ClaudeCLIVersion.compare(ClaudeCLIVersion.fallbackVersion, "2.1.280"), 0,
            "fallback must satisfy the Opus 5.5 gate"
        )
    }

    func testUserAgentKeepsExactClaudeCLIHeaderShape() {
        ClaudeCLIVersion.resetForTest()
        let ua = ClaudeCLIVersion.userAgent()
        // Anthropic pairs UA with the X-Stainless-* headers to identify the
        // official CLI; the surrounding format must stay byte-identical.
        XCTAssertEqual(ua, "claude-cli/\(ClaudeCLIVersion.fallbackVersion) (external, cli)")
        XCTAssertTrue(ua.hasPrefix("claude-cli/"))
        XCTAssertTrue(ua.hasSuffix(" (external, cli)"))
        XCTAssertFalse(ua.contains("2.1.195"), "the stale pin must not come back")
    }

    // MARK: - Wire: one source of truth with ClaudeCLIMimicry

    func testFallbackIsTheMimicryFloor() {
        // The floor lives once, in ClaudeCLIMimicry.headers; the resolver must
        // read it, not carry a second constant that can drift.
        XCTAssertEqual(ClaudeCLIMimicry.headers["User-Agent"],
                       "claude-cli/\(ClaudeCLIVersion.fallbackVersion) (external, cli)")
    }

    func testRuntimeVersionReachesTheMimicrySet() {
        ClaudeCLIVersion.resetForTest()
        defer { ClaudeCLIVersion.resetForTest() }
        XCTAssertTrue(ClaudeCLIVersion.accept("2.9.999"))
        let sent = ClaudeCLIMimicry.current()
        XCTAssertEqual(sent["User-Agent"], "claude-cli/2.9.999 (external, cli)")
        for (field, value) in ClaudeCLIMimicry.headers where field != "User-Agent" {
            XCTAssertEqual(sent[field], value, field)
        }
        var request = URLRequest(url: URL(string: "https://claude.ai/v1/oauth/token")!)
        ClaudeCLIMimicry.apply(to: &request)
        XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "claude-cli/2.9.999 (external, cli)")
    }

    func testNeverBelowTheFloor() {
        ClaudeCLIVersion.resetForTest()
        defer { ClaudeCLIVersion.resetForTest() }
        XCTAssertFalse(ClaudeCLIVersion.accept("2.1.195"))
        XCTAssertEqual(ClaudeCLIMimicry.current()["User-Agent"], ClaudeCLIMimicry.headers["User-Agent"])
    }
}
