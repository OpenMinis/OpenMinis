import XCTest
@testable import Minis

/// [T-share-routes-to-background-session] The buffer's targeting contract.
///
/// Reported 2026-08-18: sharing a file into Minis put it into a conversation
/// that was running an agent loop in the BACKGROUND (session 87B79110), not
/// into anything the user had chosen. Root cause: the share buffer had no
/// notion of a destination, so `AIChatView.injectPendingShareIfNeeded` let
/// whichever chat happened to be mounted consume it.
///
/// The routing decision itself lives in ContentView (foreground session if one
/// is on screen, otherwise a new session). What is pinned here is the part that
/// makes the decision binding: once a destination is stamped, ONLY that
/// destination may take the share.
@MainActor
final class ShareBufferTargetingTests: XCTestCase {

    private func makeShare(_ name: String = "f.zip") -> PendingShare {
        PendingShare(items: [.init(kind: .attachment, value: name)], timestamp: Date())
    }

    private func freshCoordinator() -> ShareCoordinator {
        // ShareCoordinator.shared is a singleton; drain any buffer a previous
        // test (or the app itself) left so each case starts clean.
        let c = ShareCoordinator.shared
        _ = c.consumeBuffer()
        return c
    }

    /// An unstamped buffer is the cold-launch case: the launch flow owns the
    /// destination, so any view may take it (that is what preserves the
    /// pre-existing cold-start behaviour).
    func testUnstampedBufferIsAcceptedByAnySession() {
        let c = freshCoordinator()
        c.storeBuffer(makeShare())
        XCTAssertTrue(c.bufferTargets("any-session", draftId: nil))
        XCTAssertTrue(c.bufferTargets(nil, draftId: "__new__whatever"))
        _ = c.consumeBuffer()
    }

    /// The reported bug, inverted: a share stamped for one session must be
    /// refused by a DIFFERENT one — the background agent-loop chat in the
    /// device log — and accepted by its own.
    func testStampedBufferIsRefusedByOtherSessions() {
        let c = freshCoordinator()
        c.storeBuffer(makeShare())
        c.setBufferTarget("87B79110-target")

        XCTAssertFalse(c.bufferTargets("some-other-session", draftId: nil),
                       "a chat that is not the destination must not consume the share")
        XCTAssertFalse(c.bufferTargets(nil, draftId: "__new__unrelated"))
        XCTAssertTrue(c.bufferTargets("87B79110-target", draftId: nil),
                      "the destination itself must be able to consume it")
        _ = c.consumeBuffer()
    }

    /// A draft is addressed two ways: the navigation stack (and therefore the
    /// stamp) holds `__new__…`, while the view reports `sessionId = nil,
    /// draftId = __new__…` before its first send and `sessionId = <real>,
    /// draftId = __new__…` after. Both must match, or a share aimed at the
    /// draft the user is looking at gets refused by that very draft the moment
    /// it acquires a real id.
    func testDraftMatchesByEitherIdentity() {
        let c = freshCoordinator()
        c.storeBuffer(makeShare())
        c.setBufferTarget("__new__ABC")

        XCTAssertTrue(c.bufferTargets(nil, draftId: "__new__ABC"), "pre-send draft")
        XCTAssertTrue(c.bufferTargets("real-id-after-send", draftId: "__new__ABC"),
                      "same draft after it acquired a real session id")
        XCTAssertFalse(c.bufferTargets("real-id-after-send", draftId: "__new__OTHER"))
        _ = c.consumeBuffer()
    }

    /// [T-share-buffer-merge] A second share arriving before the first is
    /// consumed still merges, and the merge must KEEP the destination already
    /// decided for the first — otherwise the merged payload would become
    /// unaddressed and any mounted chat could take it again.
    func testMergePreservesTheExistingTarget() {
        let c = freshCoordinator()
        c.storeBuffer(makeShare("a.zip"))
        c.setBufferTarget("session-A")
        c.storeBuffer(makeShare("b.zip"))

        XCTAssertTrue(c.bufferTargets("session-A", draftId: nil),
                      "merged buffer keeps the first share's destination")
        XCTAssertFalse(c.bufferTargets("session-B", draftId: nil))

        let consumed = c.consumeBuffer()
        XCTAssertEqual(consumed?.items.count, 2, "both shares survive the merge")
        XCTAssertEqual(Set((consumed?.items ?? []).map(\.value)), ["a.zip", "b.zip"])
    }

    /// De-duplication inside the merge is unchanged: a doubled delivery of the
    /// same record stays a single item.
    func testMergeStillDeduplicatesIdenticalItems() {
        let c = freshCoordinator()
        c.storeBuffer(makeShare("same.zip"))
        c.storeBuffer(makeShare("same.zip"))
        let consumed = c.consumeBuffer()
        XCTAssertEqual(consumed?.items.count, 1)
    }

    /// Stamping requires a buffer — a stray call with nothing pending must not
    /// invent one (which would then be "targeted" at a session forever).
    func testSetTargetWithoutBufferIsANoOp() {
        let c = freshCoordinator()
        c.setBufferTarget("session-X")
        XCTAssertNil(c.consumeBuffer())
    }
}
