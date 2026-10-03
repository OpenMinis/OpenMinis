import XCTest
@testable import Minis

/// [T-gemini-unsigned-narration] A Gemini 3.x request must carry a
/// `thoughtSignature` on every `functionCall`. Calls with none — produced by
/// another model before a switch (DeepSeek → Gemini), or by a run whose
/// signatures we no longer hold — cannot be replayed structurally, so the
/// converter renders them as history text.
///
/// That text used to be `[Called shell_execute with: {…}]` /
/// `[Result of shell_execute: …]`. A bracketed pseudo-marker sitting in the
/// transcript is a format the model imitates: it begins EMITTING `[Called …]`
/// as literal output instead of calling the tool. Same in-context-learning
/// failure that made the Responses-API encrypted-reasoning summary get stripped
/// rather than echoed. These tests pin the replacement prose and, above all,
/// that no imitable marker syntax comes back.
final class GeminiUnsignedNarrationTests: XCTestCase {

    // MARK: - The anti-imitation property

    /// The load-bearing assertion: nothing in the narration looks like a
    /// callable/emittable marker the model could copy.
    func testNarrationCarriesNoImitableMarkerSyntax() {
        let call = GeminiAgentProvider.narratedToolCall(
            name: "shell_execute", input: ["command": "ls -la", "tool_title": "List files"])
        let result = GeminiAgentProvider.narratedToolResult(
            name: "shell_execute", content: "total 48\ndrwxr-xr-x  6 root root")

        for text in [call, result] {
            XCTAssertFalse(text.contains("[Called"), "the imitated marker must not return: \(text)")
            XCTAssertFalse(text.contains("[Result of"), "the imitated marker must not return: \(text)")
            // No leading bracketed tag of any kind — that shape is the hazard,
            // not the specific words inside it.
            XCTAssertFalse(text.hasPrefix("["), "narration must not open with a bracketed tag: \(text)")
        }
    }

    /// Prose, and it still says what actually happened: which tool, which args.
    func testNarratedCallReadsAsProseAndKeepsTheFacts() {
        let text = GeminiAgentProvider.narratedToolCall(
            name: "shell_execute", input: ["command": "ls -la"])
        XCTAssertTrue(text.hasPrefix("Earlier in this conversation,"), text)
        XCTAssertTrue(text.contains("shell_execute"), "the tool name is the point of the sentence")
        XCTAssertTrue(text.contains("ls -la"), "arguments must survive the downgrade")
        XCTAssertTrue(text.hasSuffix("."), "reads as a sentence: \(text)")
    }

    // MARK: - Result rendering

    func testShortResultIsInlinedWhole() {
        let out = "total 48\ndrwxr-xr-x  6 root root  4096 Sep  6 22:55 ."
        let text = GeminiAgentProvider.narratedToolResult(name: "shell_execute", content: out)
        XCTAssertTrue(text.contains(out), "a short result must not be truncated at all")
        XCTAssertFalse(text.contains("..."), "no truncation marker when nothing was cut")
    }

    /// The old cap was 500 chars, which reduced a 14k-char page fetch to its
    /// first sentence. The new cap keeps 2000 and says plainly what was cut,
    /// without inventing a marker for it.
    func testLongResultKeepsMoreAndStatesTheTruncationInProse() {
        let out = String(repeating: "x", count: 14_284)
        let text = GeminiAgentProvider.narratedToolResult(name: "browser_use", content: out)
        XCTAssertTrue(text.contains("first 2000 of 14284 characters"),
                      "the cut must be stated in prose: \(text.prefix(120))")
        XCTAssertEqual(text.filter { $0 == "x" }.count, GeminiAgentProvider.narratedResultLimit)
        XCTAssertFalse(text.contains("[Result of"))
    }

    func testEmptyResultIsStatedNotBlank() {
        let text = GeminiAgentProvider.narratedToolResult(name: "shell_execute", content: "")
        XCTAssertEqual(text, "It returned no output.")
        // Gemini 400s on an empty text part — the narration must never be "".
        XCTAssertFalse(text.isEmpty)
    }

    /// Args must serialise deterministically, or the same history would produce
    /// a different prompt on each turn and defeat prompt caching.
    func testArgumentSerialisationIsStable() {
        let input: [String: Any] = ["command": "ls", "tool_title": "List", "delay": 3]
        let a = GeminiAgentProvider.narratedToolCall(name: "shell_execute", input: input)
        let b = GeminiAgentProvider.narratedToolCall(name: "shell_execute", input: input)
        XCTAssertEqual(a, b)
    }

    func testUnserialisableArgumentsDegradeToEmptyObjectNotACrash() {
        let text = GeminiAgentProvider.narratedToolCall(
            name: "shell_execute", input: ["bad": Data([0x00, 0x01])])
        XCTAssertTrue(text.contains("{}"), "non-JSON args fall back to {} rather than trapping")
    }
}
