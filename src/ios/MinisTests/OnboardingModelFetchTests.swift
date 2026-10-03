import XCTest
@testable import Minis

/// The bounded fetch behind the onboarding "Select Models" page.
/// [T-onboarding-model-fetch-fallback]
///
/// The page used to show a spinner for as long as `allEntries` was empty, with
/// no way to tell a slow upstream from a dead one. These pin the timeout race
/// the page now uses, without the view or a provider.
final class OnboardingModelFetchTests: XCTestCase {

    func testFastOperationReturnsItsValue() async throws {
        let v = try await OnboardingModelFetch.withTimeout(seconds: 2) { 42 }
        XCTAssertEqual(v, 42)
    }

    func testSlowOperationTimesOut() async {
        do {
            _ = try await OnboardingModelFetch.withTimeout(seconds: 0.2) { () -> Int in
                try await Task.sleep(nanoseconds: 5_000_000_000)
                return 1
            }
            XCTFail("expected a timeout")
        } catch let e as OnboardingModelFetch.TimeoutError {
            XCTAssertEqual(e.seconds, 0.2)
            XCTAssertFalse((e.errorDescription ?? "").isEmpty)
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    /// A failure inside the operation surfaces as-is, not as a timeout.
    func testOperationErrorPropagates() async {
        struct Boom: Error {}
        do {
            _ = try await OnboardingModelFetch.withTimeout(seconds: 2) { () -> Int in throw Boom() }
            XCTFail("expected Boom")
        } catch is Boom {
            // expected
        } catch {
            XCTFail("wrong error: \(error)")
        }
    }

    /// The loser is cancelled: a slow operation that honours cancellation
    /// stops soon after the timeout instead of running to completion.
    func testLoserIsCancelledAfterTimeout() async {
        let finished = expectation(description: "operation observed cancellation")
        _ = try? await OnboardingModelFetch.withTimeout(seconds: 0.2) { () -> Int in
            do {
                try await Task.sleep(nanoseconds: 5_000_000_000)
            } catch {
                finished.fulfill()   // CancellationError from the sleep
                throw error
            }
            return 1
        }
        await fulfillment(of: [finished], timeout: 2)
    }

    /// The spec asks for a 15–20 s page-level bound.
    func testPageTimeoutIsWithinSpec() {
        XCTAssertGreaterThanOrEqual(OnboardingModelFetch.timeoutSeconds, 15)
        XCTAssertLessThanOrEqual(OnboardingModelFetch.timeoutSeconds, 20)
    }
}
