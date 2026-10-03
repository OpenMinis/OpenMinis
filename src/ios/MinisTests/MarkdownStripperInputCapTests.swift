import XCTest
@testable import Minis

/// Preview bounding for the session-list path — the guard against the
/// 1.14(11) allocation-failure abort.
///
/// `ChatStore.listSessions` re-derives every session's one-line preview on
/// each refresh (once a second while a background agent is writing), and did
/// so by pushing the WHOLE last message through JSON decode, two marker
/// strips and `MarkdownStripper.plainText`'s ~10 regex passes, each of which
/// copies the string. A multi-megabyte message in a memory-constrained
/// background process made one of those copies fail outright.
///
/// The bound lives in `MarkdownStripper.previewSource`, applied at the entry
/// of the preview path — NOT inside `plainText`, which stays a full, uncapped
/// conversion for every other caller. These tests pin three things: the
/// bound is O(cap) on the prose part, opaque regions (code fences, injected
/// system blocks) are skipped whole rather than cut in half, and `plainText`
/// itself is unchanged.
final class MarkdownStripperInputCapTests: XCTestCase {

    // MARK: previewSource — bounding

    func testPreviewSourceIsBoundedOnHugeProse() {
        let huge = String(
            repeating: "hello **world** [a](https://example.com/very/long/url) ",
            count: 100_000
        )
        let out = MarkdownStripper.previewSource(huge, maxLength: 4096)
        XCTAssertEqual(out.count, 4096)
        XCTAssertTrue(huge.hasPrefix(out), "the bound is a prefix of the prose")
    }

    func testPreviewSourceIsCheapOnHugeInput() {
        _ = MarkdownStripper.previewSource("warm up")
        let huge = String(repeating: "hello **world** [a](https://example.com/x) ", count: 200_000)
        let start = Date()
        _ = MarkdownStripper.previewSource(huge)
        let elapsed = Date().timeIntervalSince(start)
        // Generous: the point is O(cap) vs O(input), not a precise budget.
        XCTAssertLessThan(elapsed, 0.5, "previewSource must not scan the whole input")
    }

    func testSmallInputPassesThroughUntouched() {
        let input = "# Title\n\nSome **bold** and [link](https://x.com) text."
        XCTAssertEqual(MarkdownStripper.previewSource(input), input)
    }

    func testZeroBudgetReturnsEmpty() {
        XCTAssertEqual(MarkdownStripper.previewSource("anything", maxLength: 0), "")
    }

    // MARK: previewSource — opaque regions

    /// The regression a blind `prefix` had: a leading fence longer than the
    /// cap lost its closer, the fence regex could no longer match, and the
    /// code body became the preview instead of the answer after it.
    func testTextAfterFenceLongerThanCapSurvives() {
        let fenced = "```\n" + String(repeating: "x", count: 8000) + "\n```\nReal answer here."
        let out = MarkdownStripper.previewSource(fenced, maxLength: 4096)
        XCTAssertFalse(out.contains("xxxx"), "fence body must not leak into the preview")
        XCTAssertFalse(out.contains("```"))
        XCTAssertTrue(out.contains("Real answer here"))
        XCTAssertTrue(MarkdownStripper.plainText(out).contains("Real answer here"))
    }

    func testProseBeforeAndAfterFenceIsKeptWithSeparation() {
        let text = "Before```\ncode\n```After"
        XCTAssertEqual(MarkdownStripper.previewSource(text), "Before After")
    }

    func testUnclosedFenceSwallowsTheRest() {
        let text = "Intro\n```\n" + String(repeating: "y", count: 10_000)
        let out = MarkdownStripper.previewSource(text, maxLength: 4096)
        XCTAssertEqual(out, "Intro\n")
    }

    func testSystemReminderStraddlingTheCapIsDroppedWhole() {
        let reminder = "<system-reminder>" + String(repeating: "r", count: 6000) + "</system-reminder>"
        let text = "Hi " + reminder + " tail"
        let out = MarkdownStripper.previewSource(text, maxLength: 4096)
        XCTAssertFalse(out.contains("<system-reminder>"))
        XCTAssertFalse(out.contains("rrrr"))
        XCTAssertEqual(MarkdownStripper.plainText(out), "Hi tail")
    }

    func testAttachedFilesBlockIsSkipped() {
        let text = "Q: <user-attached-files>" + String(repeating: "f", count: 5000) + "</user-attached-files> what is this?"
        let out = MarkdownStripper.previewSource(text, maxLength: 4096)
        XCTAssertFalse(out.contains("ffff"))
        XCTAssertEqual(MarkdownStripper.plainText(out), "Q: what is this?")
    }

    /// A fence that STARTS beyond the budget must not cost a scan of the
    /// whole input, and its contents must not appear either way.
    func testFenceBeyondBudgetIsSimplyCut() {
        let text = String(repeating: "p", count: 5000) + "```\n" + String(repeating: "z", count: 5000) + "\n```"
        let out = MarkdownStripper.previewSource(text, maxLength: 4096)
        XCTAssertEqual(out, String(repeating: "p", count: 4096))
    }

    /// Truncation lands on Character boundaries, so a multi-scalar grapheme
    /// at the cap can never be split into invalid text.
    func testMultiScalarGraphemesAtCapAreNotSplit() {
        let emoji = String(repeating: "👨‍👩‍👧‍👦", count: 5000)
        let out = MarkdownStripper.previewSource(emoji, maxLength: 4096)
        XCTAssertEqual(out.count, 4096)
        XCTAssertEqual(String(decoding: Array(out.utf8), as: UTF8.self), out)
    }

    // MARK: plainText — unchanged and uncapped

    func testPlainTextNormalInputStrippedUnchanged() {
        let input = "# Title\n\nSome **bold** and [link](https://x.com) text."
        XCTAssertEqual(MarkdownStripper.plainText(input), "Title Some bold and link text.")
    }

    /// `plainText` is a full conversion again: a caller that wants the whole
    /// stripped text must get it, and bounding is the preview path's job.
    func testPlainTextIsNotCapped() {
        let long = String(repeating: "word ", count: 5000) + "END"
        XCTAssertTrue(MarkdownStripper.plainText(long).hasSuffix("END"))
    }

    /// End-to-end shape of the preview path: bound first, strip second.
    func testPreviewPipelineOnLongFencedMessage() {
        let message = "```swift\n" + String(repeating: "let x = 1\n", count: 2000) + "```\n**Done.** See [docs](https://d.example)."
        let preview = MarkdownStripper.plainText(MarkdownStripper.previewSource(message))
        XCTAssertEqual(preview, "Done. See docs.")
    }
}
