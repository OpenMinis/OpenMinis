import XCTest
@testable import Minis

/// [T-openai-tool-result-image] Pins the contract that a `.toolResult` carrying
/// `imageData` actually puts those PIXELS on the wire — on every provider family.
///
/// WHY THIS FILE EXISTS
/// --------------------
/// `read_image` decides between two branches (AIChatViewModel+ConcurrentTools):
/// a native-vision model gets the raw bytes attached to the tool result, and only
/// a model that cannot see gets the Vision Group's textual description. The native
/// branch is silent by construction — it sets `toolImageData` and returns metadata
/// text ("Image loaded successfully… MIME: image/jpeg"). If a serializer then drops
/// `imageData`, the request still succeeds, the tool result still reads like a
/// success, and the ONLY symptom is the model answering from metadata it can see
/// rather than pixels it cannot. Nothing throws, nothing logs, no test fails.
///
/// That is exactly how the bug shipped on iOS (fixed 23351c02c, 2026-05-27) and
/// then shipped AGAIN on Android (fixed 430045fdd). Both times the regression was
/// a destructuring pattern that discarded the image fields:
///
///     case .toolResult(let id, _, let content, let isError, _, _, _, _, _)
///                                                           ^^^^  imageData dropped
///
/// The asymmetry is what makes it durable: Anthropic was always correct (it nests
/// an image block inside tool_result via `pendingToolResultImages`), so any manual
/// spot-check against Claude passes while every OpenAI-compatible and Gemini
/// endpoint silently sees text only.
///
/// So these tests assert the wire body directly, per family. They are cheap, they
/// need no network, and they fail loudly the moment someone re-writes one of these
/// switch statements with an underscore in the wrong position.
///
/// WHAT EACH FAMILY MUST DO
///   * Chat Completions — protocol forbids `image_url` inside role:"tool", so the
///     tool message stays text-only and a synthetic role:"user" message carrying
///     the image follows IMMEDIATELY after it.
///   * Responses API    — same shape, `function_call_output` then a role:"user"
///     item with `input_image`.
///   * Gemini / Antigravity — a single turn accepts heterogeneous parts, so
///     `inlineData` simply follows `functionResponse` in the same parts array.
///
/// The negative cases matter as much as the positive ones: a text-only model must
/// NOT receive image parts, or endpoints that reject unexpected image content
/// return 400 and the whole turn dies. "Carries pixels" and "carries them only
/// when the model can decode them" are one contract, tested together.
final class ToolResultImageWireTests: XCTestCase {

    // MARK: - Fixtures

    /// 1x1 JPEG-ish bytes. Content is irrelevant — these tests assert routing and
    /// encoding, never decoding — but a distinctive prefix makes a base64 needle
    /// easy to find in an assertion failure dump.
    private static let pixels = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x4D, 0x49, 0x4E, 0x49, 0x53])
    private static var pixelsB64: String { pixels.base64EncodedString() }

    private static let toolCallId = "call_readimage_1"

    private func visionModel(id: String = "gpt-5.4", provider: String = "OpenAI") -> LLMModel {
        LLMModel(id: id, displayName: id, provider: provider, modalityOverride: [.textInput, .imageInput])
    }

    private func textOnlyModel(id: String = "deepseek-v4-flash", provider: String = "OpenAI") -> LLMModel {
        LLMModel(id: id, displayName: id, provider: provider, modalityOverride: [.textInput])
    }

    /// The exact shape `read_image`'s native-vision branch produces: an assistant
    /// turn that called the tool, then the tool result carrying metadata text AND
    /// the pixels.
    private func conversationWithImageToolResult() -> [AgentMessage] {
        [
            AgentMessage(role: .user, parts: [.text("what is in this image?")]),
            AgentMessage(role: .assistant, parts: [
                .toolUse(id: Self.toolCallId, name: "read_image", input: ["path": "/var/minis/shared/x.png"]),
            ]),
            AgentMessage(role: .user, parts: [
                .toolResult(
                    id: Self.toolCallId,
                    name: "read_image",
                    content: "Image loaded successfully.\nMIME: image/jpeg",
                    isError: false,
                    imageData: Self.pixels,
                    imageMimeType: "image/jpeg"
                ),
            ]),
        ]
    }

    // MARK: - Helpers

    /// Recursively collect every string value in an arbitrary JSON-ish tree, so an
    /// assertion can ask "do the pixels appear ANYWHERE in this body" without
    /// depending on which key a given family nests them under.
    private func allStrings(_ any: Any) -> [String] {
        switch any {
        case let s as String: return [s]
        case let d as [String: Any]: return d.values.flatMap { allStrings($0) }
        case let a as [Any]: return a.flatMap { allStrings($0) }
        default: return []
        }
    }

    private func bodyContainsPixels(_ body: [[String: Any]]) -> Bool {
        allStrings(body).contains { $0.contains(Self.pixelsB64) }
    }

    // MARK: - OpenAI Chat Completions

    func testChatCompletions_visionModel_carriesPixelsInSyntheticUserMessage() {
        let provider = OpenAIProvider(apiKey: "test", model: visionModel())
        let agent = OpenAIAgentProvider(provider: provider)

        let body = agent.flattenChatCompletionsMessages(conversationWithImageToolResult())

        XCTAssertTrue(bodyContainsPixels(body),
                      "Chat Completions dropped read_image pixels — the model would see only metadata text. \(body)")

        // The tool message itself must stay a plain string: the Chat Completions
        // schema rejects structured content on role:"tool".
        guard let toolIdx = body.firstIndex(where: { $0["role"] as? String == "tool" }) else {
            return XCTFail("no role:\"tool\" message emitted: \(body)")
        }
        XCTAssertTrue(body[toolIdx]["content"] is String,
                      "role:\"tool\" content must be a plain string on Chat Completions")

        // …and the image must ride on the message IMMEDIATELY after it. Order is
        // load-bearing: an image separated from its tool result reads to the model
        // as an unrelated user upload.
        XCTAssertLessThan(toolIdx + 1, body.count, "no message follows the tool result: \(body)")
        let next = body[toolIdx + 1]
        XCTAssertEqual(next["role"] as? String, "user",
                       "expected a synthetic user message carrying the image right after the tool result")
        XCTAssertTrue(allStrings(next).contains { $0.contains(Self.pixelsB64) },
                      "the message after the tool result does not carry the pixels: \(next)")
        XCTAssertTrue(allStrings(next).contains { $0.contains("image_url") || $0.hasPrefix("data:image/") },
                      "expected an image_url data URL on the synthetic user message: \(next)")
    }

    func testChatCompletions_textOnlyModel_omitsPixels() {
        let provider = OpenAIProvider(apiKey: "test", model: textOnlyModel())
        let agent = OpenAIAgentProvider(provider: provider)

        let body = agent.flattenChatCompletionsMessages(conversationWithImageToolResult())

        XCTAssertFalse(bodyContainsPixels(body),
                       "text-only model must not receive image bytes — endpoints 400 on unexpected image content")
        // The textual result must still arrive; suppressing the image must not
        // suppress the tool reply itself.
        XCTAssertTrue(allStrings(body).contains { $0.contains("Image loaded successfully") },
                      "tool result text was lost along with the image: \(body)")
    }

    // MARK: - OpenAI Responses API

    func testResponsesAPI_visionModel_carriesPixelsAsInputImage() {
        let provider = OpenAIProvider(apiKey: "test", model: visionModel())
        let agent = OpenAIAgentProvider(provider: provider)

        let body = agent.convertMessagesResponsesAPI(conversationWithImageToolResult())

        XCTAssertTrue(bodyContainsPixels(body),
                      "Responses API dropped read_image pixels — the model would see only metadata text. \(body)")

        guard let outIdx = body.firstIndex(where: { $0["type"] as? String == "function_call_output" }) else {
            return XCTFail("no function_call_output emitted: \(body)")
        }
        XCTAssertLessThan(outIdx + 1, body.count, "no item follows function_call_output: \(body)")
        let next = body[outIdx + 1]
        XCTAssertEqual(next["role"] as? String, "user",
                       "expected a synthetic user item carrying the image right after function_call_output")
        XCTAssertTrue(allStrings(next).contains { $0.contains("input_image") },
                      "Responses API must use input_image for tool-result images: \(next)")
    }

    func testResponsesAPI_textOnlyModel_omitsPixels() {
        let provider = OpenAIProvider(apiKey: "test", model: textOnlyModel())
        let agent = OpenAIAgentProvider(provider: provider)

        let body = agent.convertMessagesResponsesAPI(conversationWithImageToolResult())

        XCTAssertFalse(bodyContainsPixels(body),
                       "text-only model must not receive image bytes on the Responses API")
    }

    // MARK: - Gemini

    /// Gemini has TWO tool-result shapes and the image must survive both.
    ///
    /// A tool call whose `thoughtSignature` was never captured is "unsigned":
    /// replaying it as a real `functionResponse` makes the API 400, so the
    /// serializer degrades it to a plain narrated text part ("It returned: …";
    /// [T-gemini-unsigned-narration] replaced the old `[Result of …]` marker,
    /// which the model imitated). That branch is
    /// not an edge case — it is what every tool call reaching this converter
    /// WITHOUT a recorded signature takes, including anything replayed from the
    /// DB, and it was the branch this fixture actually exercised. Both paths append
    /// `inlineData` after the result part; asserting only the signed shape would
    /// have let a regression through the more common door.
    ///
    /// So this asserts the invariant that actually matters — the image part
    /// immediately FOLLOWS whichever result part was emitted, in the same turn —
    /// rather than pinning one of the two shapes.
    func testGemini_carriesPixelsAsInlineDataAfterToolResultPart() {
        let provider = GeminiProvider(apiKey: "test",
                                      model: visionModel(id: "gemini-3.5-flash", provider: "Google"))
        let agent = GeminiAgentProvider(provider: provider)

        let body = agent.convertMessages(conversationWithImageToolResult())

        XCTAssertTrue(bodyContainsPixels(body),
                      "Gemini dropped read_image pixels — the model would see only metadata text. \(body)")

        // Find the turn holding the tool result, in EITHER shape.
        let turn = body.first { turn in
            guard let parts = turn["parts"] as? [[String: Any]] else { return false }
            return parts.contains { part in
                if part["functionResponse"] != nil { return true }
                if let t = part["text"] as? String { return t.hasPrefix("It returned") }
                return false
            }
        }
        guard let turn, let parts = turn["parts"] as? [[String: Any]] else {
            return XCTFail("no turn containing a tool-result part: \(body)")
        }
        guard let resultIdx = parts.firstIndex(where: { part in
            if part["functionResponse"] != nil { return true }
            if let t = part["text"] as? String { return t.hasPrefix("It returned") }
            return false
        }) else {
            return XCTFail("tool-result part vanished: \(parts)")
        }

        // Adjacency is the contract: Gemini accepts heterogeneous parts in one
        // turn, so the image belongs in the SAME parts array directly after the
        // result it illustrates.
        XCTAssertLessThan(resultIdx + 1, parts.count, "no part follows the tool result: \(parts)")
        XCTAssertNotNil(parts[resultIdx + 1]["inlineData"],
                        "expected inlineData immediately after the tool-result part: \(parts)")
    }

    // MARK: - Antigravity
    //
    // Antigravity speaks the Gemini wire format through a different provider, with
    // its own copy of the conversion switch — so it can regress independently and
    // gets its own assertion rather than being assumed equivalent.

    func testAntigravity_carriesPixelsAsInlineDataAfterToolResultPart() {
        let provider = AntigravityProvider(
            oauthTokenProvider: { "test-token" },
            model: visionModel(id: "gemini-3.5-flash", provider: "Antigravity")
        )
        let agent = AntigravityAgentProvider(provider: provider)

        let body = agent.convertMessages(conversationWithImageToolResult())

        XCTAssertTrue(bodyContainsPixels(body),
                      "Antigravity dropped read_image pixels — the model would see only metadata text. \(body)")

        // Same adjacency contract as Gemini, and same two-shape tolerance — see
        // testGemini_carriesPixelsAsInlineDataAfterToolResultPart.
        let turn = body.first { turn in
            guard let parts = turn["parts"] as? [[String: Any]] else { return false }
            return parts.contains { part in
                if part["functionResponse"] != nil { return true }
                if let t = part["text"] as? String { return t.hasPrefix("It returned") }
                return false
            }
        }
        guard let turn, let parts = turn["parts"] as? [[String: Any]] else {
            return XCTFail("no turn containing a tool-result part: \(body)")
        }
        guard let resultIdx = parts.firstIndex(where: { part in
            if part["functionResponse"] != nil { return true }
            if let t = part["text"] as? String { return t.hasPrefix("It returned") }
            return false
        }) else {
            return XCTFail("tool-result part vanished: \(parts)")
        }
        XCTAssertLessThan(resultIdx + 1, parts.count, "no part follows the tool result: \(parts)")
        XCTAssertNotNil(parts[resultIdx + 1]["inlineData"],
                        "expected inlineData immediately after the tool-result part: \(parts)")
    }
}
