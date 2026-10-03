import XCTest

/// [T-gemini-empty-part-oneof-400] Regression tests pinning the Gemini
/// wire-format invariant: a part's text is NEVER the empty string, so the
/// protobuf `oneof` is always initialised and the API doesn't 400 with
/// "required oneof field 'data' must have one initialized field". Repro'd on
/// Gemini 3.5 Flash tool calls (empty assistant/model text after a
/// thinking-heavy or safety-stripped turn).
final class GeminiWireFormatTests: XCTestCase {

    // MARK: - nonEmptyText

    func testNonEmptyText_emptyString_becomesSpace() {
        XCTAssertEqual(GeminiWireFormat.nonEmptyText(""), " ")
    }

    func testNonEmptyText_nil_becomesSpace() {
        XCTAssertEqual(GeminiWireFormat.nonEmptyText(nil), " ")
    }

    func testNonEmptyText_nonEmpty_preserved() {
        XCTAssertEqual(GeminiWireFormat.nonEmptyText("hello"), "hello")
    }

    func testNonEmptyText_whitespaceOnly_preserved() {
        // A single space is already valid — don't collapse or trim it away.
        XCTAssertEqual(GeminiWireFormat.nonEmptyText(" "), " ")
        XCTAssertEqual(GeminiWireFormat.nonEmptyText("\n"), "\n")
    }

    // MARK: - textPart (empty text turn → never {"text":""})

    func testTextPart_emptyText_neverEmpty() {
        let part = GeminiWireFormat.textPart("")
        XCTAssertEqual(part["text"] as? String, " ",
                       "{\"text\":\"\"} triggers Gemini oneof 400 — must be substituted")
    }

    func testTextPart_nilText_neverEmpty() {
        XCTAssertEqual(GeminiWireFormat.textPart(nil)["text"] as? String, " ")
    }

    func testTextPart_realText_passesThrough() {
        // Arbitrary payload passthrough (this shape is no longer produced —
        // see [T-gemini-unsigned-narration] — but textPart must stay verbatim).
        XCTAssertEqual(GeminiWireFormat.textPart("[Called foo with: {}]")["text"] as? String,
                       "[Called foo with: {}]")
    }

    // MARK: - functionResponseResult (empty tool result content → never empty)

    func testFunctionResponseResult_emptyContent_neverEmpty() {
        let resp = GeminiWireFormat.functionResponseResult("")
        XCTAssertEqual(resp["result"] as? String, " ",
                       "empty tool result would ship an empty string into the response payload")
    }

    func testFunctionResponseResult_nilContent_neverEmpty() {
        XCTAssertEqual(GeminiWireFormat.functionResponseResult(nil)["result"] as? String, " ")
    }

    func testFunctionResponseResult_realContent_passesThrough() {
        XCTAssertEqual(GeminiWireFormat.functionResponseResult("ok")["result"] as? String, "ok")
    }

    // MARK: - Guarantee: no produced part ever carries an empty string

    func testInvariant_noEmptyStringEverProduced() {
        for input: String? in [nil, "", " ", "x", "多字节文本"] {
            XCTAssertFalse((GeminiWireFormat.textPart(input)["text"] as? String)?.isEmpty ?? true,
                           "textPart must never yield an empty string (input=\(String(describing: input)))")
            XCTAssertFalse((GeminiWireFormat.functionResponseResult(input)["result"] as? String)?.isEmpty ?? true,
                           "functionResponseResult must never yield an empty string (input=\(String(describing: input)))")
        }
    }
}
