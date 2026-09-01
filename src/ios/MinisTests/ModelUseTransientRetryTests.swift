import XCTest
@testable import Minis

/// [T-modeluse-transient-retry] ModelUseOffloadBridge.withTransientRetry —
/// the CLI-level bounded backoff for `Minis.LLMError.transientError` (Gemini
/// 500/502/503/504/529 etc.). Sleeps are injected so the suite runs in
/// milliseconds while still asserting the real 1s/2s/4s schedule.
final class ModelUseTransientRetryTests: XCTestCase {

    private func transient(_ n: Int) -> Minis.LLMError {
        .transientError(message: "Gemini API error 503 (attempt \(n))")
    }

    /// A 503 on the first two attempts, success on the third: the caller sees
    /// the success, and the recorded delays follow the exponential schedule.
    func testTransientErrorRetriesUntilSuccessWithExponentialBackoff() async throws {
        var attempts = 0
        var delays: [UInt64] = []
        let result = try await ModelUseOffloadBridge.withTransientRetry(
            label: "test",
            sleeper: { delays.append($0) }
        ) { () throws -> String in
            attempts += 1
            if attempts < 3 { throw self.transient(attempts) }
            return "ok"
        }
        XCTAssertEqual(result, "ok")
        XCTAssertEqual(attempts, 3)
        // [T-modeluse-503-budget] Schedule now starts at 2s and doubles.
        XCTAssertEqual(delays, [2_000_000_000, 4_000_000_000])
    }

    /// [T-modeluse-503-budget] The image-bearing gemini-3.7-flash case that
    /// motivated the larger budget: curl repro showed 503s on the first three
    /// attempts with success on the FOURTH. The old budget (3 retries) made
    /// that the exhaustion case; the new one must still be mid-flight there.
    func testLargeRequestPatternSucceedsOnFourthAttempt() async throws {
        var attempts = 0
        var delays: [UInt64] = []
        let result = try await ModelUseOffloadBridge.withTransientRetry(
            label: "run/stream gemini-3.7-flash",
            sleeper: { delays.append($0) }
        ) { () throws -> String in
            attempts += 1
            if attempts < 4 { throw self.transient(attempts) }
            return "image-reply"
        }
        XCTAssertEqual(result, "image-reply")
        XCTAssertEqual(attempts, 4)
        XCTAssertEqual(delays, [2_000_000_000, 4_000_000_000, 8_000_000_000])
    }

    /// Retries are bounded: maxRetries=5 → 6 attempts total (~62s of waiting),
    /// then the last transient error surfaces to the caller.
    func testTransientErrorExhaustsRetriesThenThrows() async {
        var attempts = 0
        var delays: [UInt64] = []
        do {
            _ = try await ModelUseOffloadBridge.withTransientRetry(
                label: "test",
                sleeper: { delays.append($0) }
            ) { () throws -> String in
                attempts += 1
                throw self.transient(attempts)
            }
            XCTFail("expected transientError after exhausting retries")
        } catch let Minis.LLMError.transientError(message) {
            XCTAssertTrue(message.contains("attempt 6"), "should surface the LAST attempt's error: \(message)")
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
        XCTAssertEqual(attempts, 6)
        XCTAssertEqual(delays, [2_000_000_000, 4_000_000_000, 8_000_000_000,
                                16_000_000_000, 32_000_000_000])
        // Total wait stays bounded at ~62s — long enough for the observed
        // congestion window, short enough that a genuinely dead endpoint
        // still reports back inside a minute.
        XCTAssertEqual(delays.reduce(0, +), 62_000_000_000)
    }

    /// Non-transient LLM errors (bad input → 400 providerError) keep the
    /// original fail-fast behaviour: one attempt, no sleeps.
    func testProviderErrorFailsFastWithoutRetry() async {
        var attempts = 0
        var delays: [UInt64] = []
        do {
            _ = try await ModelUseOffloadBridge.withTransientRetry(
                label: "test",
                sleeper: { delays.append($0) }
            ) { () throws -> String in
                attempts += 1
                throw Minis.LLMError.providerError(message: "400 INVALID_ARGUMENT")
            }
            XCTFail("expected providerError")
        } catch let Minis.LLMError.providerError(message) {
            XCTAssertEqual(message, "400 INVALID_ARGUMENT")
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
        XCTAssertEqual(attempts, 1)
        XCTAssertTrue(delays.isEmpty)
    }

    /// Non-Minis.LLMError failures are also untouched (no retry, error passes
    /// through unchanged).
    func testUnrelatedErrorPassesThrough() async {
        struct Boom: Error {}
        var attempts = 0
        do {
            _ = try await ModelUseOffloadBridge.withTransientRetry(label: "test") { () throws -> String in
                attempts += 1
                throw Boom()
            }
            XCTFail("expected Boom")
        } catch is Boom {
            // expected
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
        XCTAssertEqual(attempts, 1)
    }

    /// [T-modeluse-503-budget] The streaming path's veto must NOT fire for the
    /// reported failure mode: a 503 raised while OPENING the stream (large
    /// image body, nothing emitted yet). Models the real wrapper — emittedOutput
    /// flips only after a chunk is written — and asserts the retry proceeds.
    func testStreamOpenFailureIsStillRetriedWhenNothingEmitted() async throws {
        var attempts = 0
        var delays: [UInt64] = []
        var emittedOutput = false
        let result = try await ModelUseOffloadBridge.withTransientRetry(
            label: "run/stream gemini-3.7-flash",
            canRetry: { !emittedOutput },
            sleeper: { delays.append($0) }
        ) { () throws -> String in
            attempts += 1
            // Stream open fails before any chunk on the first two tries.
            if attempts < 3 { throw self.transient(attempts) }
            emittedOutput = true
            return "streamed"
        }
        XCTAssertEqual(result, "streamed")
        XCTAssertEqual(attempts, 3)
        XCTAssertEqual(delays, [2_000_000_000, 4_000_000_000])
    }

    /// The streaming veto: once output bytes have been emitted, canRetry
    /// returns false and a mid-stream transient error surfaces immediately
    /// (retrying would duplicate text already written to the caller's fd).
    func testCanRetryFalseSurfacesTransientErrorImmediately() async {
        var attempts = 0
        var delays: [UInt64] = []
        var emittedOutput = false
        do {
            _ = try await ModelUseOffloadBridge.withTransientRetry(
                label: "test",
                canRetry: { !emittedOutput },
                sleeper: { delays.append($0) }
            ) { () throws -> String in
                attempts += 1
                emittedOutput = true   // chunk reached the fd before the drop
                throw self.transient(attempts)
            }
            XCTFail("expected transientError")
        } catch Minis.LLMError.transientError {
            // expected
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
        XCTAssertEqual(attempts, 1)
        XCTAssertTrue(delays.isEmpty)
    }
}
