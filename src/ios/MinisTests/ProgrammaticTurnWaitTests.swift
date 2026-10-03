import XCTest
@testable import Minis

/// The completion rule behind "Wait for Result" in the Shortcuts intents.
/// [T-shortcut-wait-queued-turn]
///
/// Drives `AIChatViewModel.awaitTurnCompletion(in:)` with hand-built state
/// sequences, so the rule is pinned without a view model, a session or a
/// running agent loop.
final class ProgrammaticTurnWaitTests: XCTestCase {

    private typealias S = ProgrammaticTurnState

    private func stream(_ states: [S]) -> AsyncStream<S> {
        AsyncStream { c in
            for s in states { c.yield(s) }
            c.finish()
        }
    }

    // MARK: .sent

    /// send() flips isProcessing before returning, so the replayed first value
    /// is `true`; the wait ends on the subsequent `false`.
    func testSentTurnFinishesOnFirstIdle() async {
        let done = await AIChatViewModel.awaitTurnCompletion(in: stream([
            S(processing: true, stillQueued: false),
            S(processing: false, stillQueued: false),
        ]))
        XCTAssertTrue(done)
    }

    /// A turn that already finished resolves on the replayed current value —
    /// no waiting for a change that will never arrive.
    func testAlreadyIdleResolvesImmediately() async {
        let done = await AIChatViewModel.awaitTurnCompletion(in: stream([
            S(processing: false, stillQueued: false),
        ]))
        XCTAssertTrue(done)
    }

    // MARK: .queued — the bug this replaces

    /// The earlier turn ending must NOT count as completion while our prompt is
    /// still sitting in the queue.
    func testQueuedPromptIgnoresTheEarlierTurnEnding() async {
        var seen: [S] = []
        let tracking = AsyncStream<S> { c in
            for s in [
                S(processing: true, stillQueued: true),    // earlier turn running, ours queued
                S(processing: false, stillQueued: true),   // earlier turn ended — the old code broke here
                S(processing: true, stillQueued: false),   // drain took ours and started it
                S(processing: false, stillQueued: false),  // ours finished
            ] { seen.append(s); c.yield(s) }
            c.finish()
        }
        let done = await AIChatViewModel.awaitTurnCompletion(in: tracking)
        XCTAssertTrue(done)
        XCTAssertEqual(seen.count, 4, "must consume through the queued prompt's own completion")
    }

    /// The drain clears the queue and flips processing in the same tick in some
    /// paths; either order of observation must still end on the final idle.
    func testQueueDrainedAndIdleInOneObservation() async {
        let done = await AIChatViewModel.awaitTurnCompletion(in: stream([
            S(processing: true, stillQueued: true),
            S(processing: false, stillQueued: false),
        ]))
        XCTAssertTrue(done)
    }

    // MARK: termination without completion

    /// Publisher completion (vm gone) with no finished state is reported as
    /// not-finished, never as success.
    func testSequenceEndingWhileBusyIsNotFinished() async {
        let done = await AIChatViewModel.awaitTurnCompletion(in: stream([
            S(processing: true, stillQueued: false),
        ]))
        XCTAssertFalse(done)
    }

    /// Cancellation (Shortcuts' time limit, user stop) unblocks the wait and
    /// reports not-finished so the caller can return a partial result.
    func testCancellationUnblocksAndReportsNotFinished() async {
        let never = AsyncStream<S> { c in
            c.yield(S(processing: true, stillQueued: false))
            // never finishes on its own
        }
        let task = Task { await AIChatViewModel.awaitTurnCompletion(in: never) }
        try? await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        let done = await task.value
        XCTAssertFalse(done)
    }

    // MARK: rule itself

    func testIsFinishedRule() {
        XCTAssertTrue(S(processing: false, stillQueued: false).isFinished)
        XCTAssertFalse(S(processing: true, stillQueued: false).isFinished)
        XCTAssertFalse(S(processing: false, stillQueued: true).isFinished)
        XCTAssertFalse(S(processing: true, stillQueued: true).isFinished)
    }
}
