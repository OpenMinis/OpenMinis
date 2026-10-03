import XCTest
@testable import Minis

/// [T-fallback-503-budget] A provider that answers HTTP 503 (`no_available_workers`,
/// circuit breaker open) means THIS deployment has no capacity — another member of
/// the group probably does. The old `limited` path spent the full network ladder
/// (3/5/10/15/30s = 63s) on the same model before falling back, which read as
/// "fallback never happened". These tests pin the new split:
///
///   - a transient the SERVER produced with a 5xx, inside a group → short budget;
///   - every other transient (no HTTP status: dropped link, DNS, TTFB stall, empty
///     response, compaction breach) → unchanged full ladder;
///   - no group → unchanged full ladder, since there is nothing to fall back to.
///
/// The classification is structural (`statusCode` captured where the mapping site
/// saw the real HTTP response), never a substring match on a body that may itself
/// contain "503".
final class Fallback503BudgetTests: XCTestCase {

    // MARK: - Classification

    func testHTTP503IsServerCapacityTransient() {
        let e = Minis.LLMError.transientError(message: "HTTP 503: no_available_workers", statusCode: 503)
        XCTAssertEqual(e.httpStatusCode, 503)
        XCTAssertTrue(e.isServerCapacityTransient)
        // Still retryable-on-same-model first, and still NOT an immediate
        // fallback — `limited` must retry briefly before switching.
        XCTAssertTrue(e.isRetryable)
        XCTAssertFalse(e.isFallbackable)
    }

    func testOther5xxAlsoCountAsServerCapacity() {
        for code in [500, 502, 504, 529] {
            let e = Minis.LLMError.transientError(message: "HTTP \(code)", statusCode: code)
            XCTAssertTrue(e.isServerCapacityTransient, "HTTP \(code) should use the short budget")
        }
    }

    /// The load-bearing negative: a transient with no HTTP response behind it.
    /// Switching models cannot fix a dead link, so these must not be reclassified.
    func testStatuslessTransientsAreNotServerCapacity() {
        let cases: [Minis.LLMError] = [
            .transientError(message: "No response from the server for 120s"),
            .transientError(message: "Server returned an empty response (overloaded or filtered)"),
            .transientError(message: "The assistant message was removed while preparing the request."),
        ]
        for e in cases {
            XCTAssertNil(e.httpStatusCode)
            XCTAssertFalse(e.isServerCapacityTransient, "\(e) must keep the full retry ladder")
        }
    }

    /// A body that merely mentions a number must not be mistaken for a status.
    /// This is exactly what a loose `contains("503")` / `\d{3}` matcher gets wrong.
    func testBodyTextIsNeverParsedAsAStatusCode() {
        let e = Minis.LLMError.transientError(message: "Server returned an empty response after 5030 tokens")
        XCTAssertNil(e.httpStatusCode)
        XCTAssertFalse(e.isServerCapacityTransient)
    }

    func testNonTransientErrorsHaveNoStatusCode() {
        XCTAssertNil(Minis.LLMError.rateLimited.httpStatusCode)
        XCTAssertFalse(Minis.LLMError.rateLimited.isServerCapacityTransient)
        XCTAssertNil(Minis.LLMError.providerError(message: "[503] upstream").httpStatusCode)
        XCTAssertFalse(Minis.LLMError.providerError(message: "[503] upstream").isServerCapacityTransient)
        let net = Minis.LLMError.networkError(underlying: URLError(.notConnectedToInternet))
        XCTAssertNil(net.httpStatusCode)
        XCTAssertFalse(net.isServerCapacityTransient)
    }

    // MARK: - Budget selection (what `limited` actually spends before falling back)

    /// Scenario 1: limited + HTTP 503 → short budget, then the caller falls back.
    /// Two attempts at 2s and 5s = 7s, versus 63s before.
    @MainActor
    func testHTTP503InAGroupGetsTheShortBudget() {
        let e = Minis.LLMError.transientError(message: "HTTP 503: no_available_workers", statusCode: 503)
        let delays = AIChatViewModel.retryDelaysFor(error: e, hasGroup: true)
        XCTAssertEqual(delays, [2, 5])
        XCTAssertEqual(delays.count, 2, "503 retries twice, then the group falls back")
        XCTAssertEqual(delays.reduce(0, +), 7)
        XCTAssertLessThan(delays.reduce(0, +), AIChatViewModel.retryDelays.reduce(0, +))
    }

    /// Scenario 3: a statusless transient in the same group keeps the full ladder —
    /// this change must not add fallback for local/link problems.
    @MainActor
    func testStatuslessTransientKeepsTheFullLadder() {
        let e = Minis.LLMError.transientError(message: "No response from the server for 120s")
        XCTAssertEqual(AIChatViewModel.retryDelaysFor(error: e, hasGroup: true),
                       AIChatViewModel.retryDelays)
    }

    @MainActor
    func testNetworkErrorKeepsTheFullLadder() {
        let e = Minis.LLMError.networkError(underlying: URLError(.timedOut))
        XCTAssertEqual(AIChatViewModel.retryDelaysFor(error: e, hasGroup: true),
                       AIChatViewModel.retryDelays)
    }

    /// No group: the short budget would just fail sooner with nowhere to go.
    @MainActor
    func testHTTP503WithoutAGroupKeepsTheFullLadder() {
        let e = Minis.LLMError.transientError(message: "HTTP 503", statusCode: 503)
        XCTAssertEqual(AIChatViewModel.retryDelaysFor(error: e, hasGroup: false),
                       AIChatViewModel.retryDelays)
    }

    /// Scenario 4: 429 does not regress. It is `rateLimited` → `isFallbackable`,
    /// so it falls back immediately and never consults a retry budget at all.
    func testRateLimitedStillFallsBackImmediately() {
        let e = Minis.LLMError.rateLimited
        XCTAssertTrue(e.isFallbackable, "429 keeps its immediate-fallback path")
        XCTAssertFalse(e.isRetryable)
        XCTAssertFalse(e.isServerCapacityTransient)
    }

    /// Scenario 2: `always` never reaches the retry planner — it falls back on ANY
    /// error before auto-retry, so a 503 still switches models immediately. Pinned
    /// via the properties that branch reads, since it keys off the group strategy
    /// rather than the error.
    func testAlwaysStrategyPathIsUnaffectedBy503Classification() {
        let e = Minis.LLMError.transientError(message: "HTTP 503: no_available_workers", statusCode: 503)
        // The `always` branch is chosen by strategy, not by isFallbackable, and it
        // runs BEFORE the auto-retry branch this change touches.
        XCTAssertFalse(e.isFallbackable, "unchanged: 503 is not an immediate-fallback error class")
        XCTAssertEqual(FallbackStrategy.always.rawValue, "always")
    }

    // MARK: - The shape the provider mappers produce

    /// What every 5xx mapping site now constructs (OpenAI-compatible, Gemini,
    /// Antigravity, Anthropic): the message the user sees is unchanged, and the
    /// status rides alongside it. `no_available_workers` is the real body from
    /// the report.
    func testMappedTransientKeepsItsMessageAndGainsTheStatus() {
        let body = "{\"error\":{\"message\":\"no_available_workers\"}}"
        let mapped = Minis.LLMError.transientError(message: "HTTP 503: \(body)", statusCode: 503)
        XCTAssertEqual(mapped.httpStatusCode, 503)
        XCTAssertTrue(mapped.isServerCapacityTransient)
        // User-visible text must not change — no new UI/copy in this fix.
        XCTAssertEqual(mapped.errorDescription,
                       "Service temporarily unavailable: HTTP 503: \(body)")
    }
}
