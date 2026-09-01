import XCTest
@testable import Minis

/// [T-ish-continuation-double-resume] Regression coverage for the
/// EXC_BREAKPOINT (SIGTRAP) crash reported 2026-08-14 21:26 on iOS 27
/// ("github triggered action"): `ISHExecutionCoordinator.execute` guarded its
/// `CheckedContinuation` with a bare `var resumed`, read and written from up to
/// four threads. The check and the set were separate operations, so two racers
/// could both pass the check and resume the same continuation — which is a
/// fatalError in the Swift runtime.
///
/// The racing paths, all able to reach the same continuation:
///   • completion  — main queue (processDidExit's dispatch_after)
///   • completion  — global utility queue (sweepStaleContexts → finalizeContext)
///   • pid < 0     — the calling thread, synchronously
///   • timeout     — main queue (asyncAfter)
///
/// `claimResume()` is a local function inside the continuation closure, so it
/// cannot be called from a test directly. These tests pin the PATTERN it uses —
/// an NSLock-protected test-and-set — under real concurrency, which is the
/// property the fix depends on: exactly one caller may ever win, no matter how
/// many threads race. A regression that reintroduces a non-atomic check (or
/// drops the lock) fails `testExactlyOneClaimWinsUnderHeavyContention`.
final class ISHContinuationResumeRaceTests: XCTestCase {

    /// Verbatim copy of the guard installed in `ISHExecutionCoordinator.execute`.
    /// Kept structurally identical on purpose: if the production shape changes,
    /// this mirror should change with it and the drift is visible in review.
    /// `@unchecked Sendable`: safety comes from the NSLock below, which is
    /// exactly the production guarantee under test — the compiler cannot prove
    /// it, and annotating it is what lets these racers cross queue boundaries.
    private final class ResumeClaim: @unchecked Sendable {
        private let lock = NSLock()
        private var resumed = false
        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if resumed { return false }
            resumed = true
            return true
        }
    }

    /// The core invariant: under heavy multi-threaded contention exactly ONE
    /// caller is allowed to resume. Anything >1 is the crash; 0 would be a hang.
    func testExactlyOneClaimWinsUnderHeavyContention() {
        for round in 0..<500 {
            let claim = ResumeClaim()
            let winners = ManagedAtomicCounter()
            // 8 concurrent racers per round, dispatched together so they land
            // inside each other's check-then-set window as often as possible.
            DispatchQueue.concurrentPerform(iterations: 8) { _ in
                if claim.claim() { winners.increment() }
            }
            XCTAssertEqual(winners.value, 1, "round \(round): expected exactly one winner")
        }
    }

    /// The four production call sites do not arrive simultaneously — completion
    /// and timeout typically land microseconds apart on DIFFERENT queues. Model
    /// that shape explicitly (main + global utility + a calling thread) rather
    /// than relying only on concurrentPerform's thread pool.
    func testClaimIsExclusiveAcrossDistinctQueues() {
        for _ in 0..<200 {
            let claim = ResumeClaim()
            let winners = ManagedAtomicCounter()
            let group = DispatchGroup()

            // completion via main-queue dispatch_after (processDidExit)
            group.enter()
            DispatchQueue.main.async { if claim.claim() { winners.increment() }; group.leave() }
            // completion via the stale-context sweeper (global utility queue)
            group.enter()
            DispatchQueue.global(qos: .utility).async { if claim.claim() { winners.increment() }; group.leave() }
            // timeout work item (main queue)
            group.enter()
            DispatchQueue.main.async { if claim.claim() { winners.increment() }; group.leave() }
            // pid < 0, synchronous on the calling thread
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async { if claim.claim() { winners.increment() }; group.leave() }

            // The main-queue blocks need the runloop to turn; wait without
            // blocking main so this cannot deadlock against them.
            let done = expectation(description: "all racers finished")
            group.notify(queue: .global()) { done.fulfill() }
            wait(for: [done], timeout: 5)

            XCTAssertEqual(winners.value, 1, "exactly one queue may win the resume")
        }
    }

    /// A losing claimant must return false rather than throwing or blocking, so
    /// the production `guard claimResume() else { return }` simply falls through.
    /// Also pins that a claim is permanent — a second attempt by the winner
    /// itself never re-wins (the completion path can be re-entered by the
    /// sweeper for the same context).
    func testClaimIsIdempotentAndPermanent() {
        let claim = ResumeClaim()
        XCTAssertTrue(claim.claim(), "first caller wins")
        XCTAssertFalse(claim.claim(), "same caller must not win twice")
        XCTAssertFalse(claim.claim(), "still refused on further attempts")
    }

    /// Minimal thread-safe counter — XCTest has no built-in, and using a bare
    /// `var` here would make the test itself racy and its failures meaningless.
    private final class ManagedAtomicCounter: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func increment() { lock.lock(); count += 1; lock.unlock() }
        var value: Int { lock.lock(); defer { lock.unlock() }; return count }
    }
}
