import XCTest
@testable import Minis

/// [T-gemini-thinking-persist] Gemini/Antigravity streamed thoughts as
/// `.thinkingDelta` only. That paints the live UI block but never reaches the
/// `reasoning_content` column, which is the ONLY thing `RawMessage.toChatMessage`
/// rebuilds a thinking block from on reload — so thinking was visible during the
/// turn and gone after an app restart.
///
/// These tests pin the persistence round-trip and, just as importantly, the
/// FORMAT-CONVERSION paths that a newly-populated `reasoningContent` now flows
/// through, so this fix cannot regress the historical thinking-echo bugs:
///   - Anthropic-compat proxies (unsigned interleaved thinking echo),
///   - Mistral (`reasoning_content` is categorically forbidden → 422),
///   - the Responses-API encrypted-summary marker (must not be fed back).
final class GeminiThinkingPersistTests: XCTestCase {

    private let thoughts = "Let me work through this step by step."

    // MARK: - Persistence round-trip (the reported bug)

    /// The restore path: `reasoning_content` present → the reloaded message
    /// carries a thinking block again. This is what was broken for Gemini.
    func testStoredReasoningRebuildsTheThinkingBlockOnReload() {
        let raw = RawMessage(
            id: "m1", sessionId: "s1", role: .assistant,
            parts: [.text("Here is the answer.")],
            createdAt: Date(),
            reasoningContent: thoughts
        )
        let msg = raw.toChatMessage(mediaResolver: { _ in URL(fileURLWithPath: "/dev/null") })
        let thinking = msg.blocks.filter { if case .thinking = $0.kind { return true }; return false }
        XCTAssertEqual(thinking.count, 1, "a stored reasoning string must restore exactly one thinking block")
        XCTAssertEqual(thinking.first?.content, thoughts)
    }

    /// The pre-fix Gemini shape: thoughts shown live, nothing persisted. Pins
    /// WHY the bug looked the way it did — and that an empty/absent value must
    /// not fabricate an empty thinking block.
    func testMissingReasoningRestoresNoThinkingBlock() {
        for value in [nil, ""] as [String?] {
            let raw = RawMessage(
                id: "m2", sessionId: "s1", role: .assistant,
                parts: [.text("Answer.")], createdAt: Date(),
                reasoningContent: value
            )
            let msg = raw.toChatMessage(mediaResolver: { _ in URL(fileURLWithPath: "/dev/null") })
            let thinking = msg.blocks.filter { if case .thinking = $0.kind { return true }; return false }
            XCTAssertTrue(thinking.isEmpty, "reasoningContent=\(String(describing: value)) must not create a block")
        }
    }

    /// The user's per-conversation "show thinking" toggle still wins on reload.
    func testShowThinkingFalseSuppressesTheRestoredBlock() {
        let raw = RawMessage(
            id: "m3", sessionId: "s1", role: .assistant,
            parts: [.text("Answer.")], createdAt: Date(),
            reasoningContent: thoughts
        )
        let msg = raw.toChatMessage(mediaResolver: { _ in URL(fileURLWithPath: "/dev/null") },
                                    showThinking: false)
        XCTAssertTrue(msg.blocks.allSatisfy { if case .thinking = $0.kind { return false }; return true })
    }

    /// A user turn must never grow a thinking block, whatever the column holds.
    func testUserRoleNeverRestoresAThinkingBlock() {
        let raw = RawMessage(
            id: "m4", sessionId: "s1", role: .user,
            parts: [.text("hi")], createdAt: Date(),
            reasoningContent: thoughts
        )
        let msg = raw.toChatMessage(mediaResolver: { _ in URL(fileURLWithPath: "/dev/null") })
        XCTAssertTrue(msg.blocks.allSatisfy { if case .thinking = $0.kind { return false }; return true })
    }

    // MARK: - Cross-format conversion (regression guards)

    /// Mistral forbids `reasoning_content` outright — a history turn carrying one
    /// 422s the whole request. Gemini reasoning reaching a Mistral turn after a
    /// group fallback must therefore be dropped, not echoed.
    /// [T-mistral-reasoning-forbidden]
    func testMistralNeverEchoesReasoningContent() {
        let upstream = OpenAIProvider(apiKey: "k", model: mistralModel, customBaseURL: "https://api.mistral.ai/v1")
        // `isMistral` is a flag the instance factory sets, not something derived
        // from the URL — set it explicitly, the way the factory would.
        upstream.isMistral = true
        let provider = OpenAIAgentProvider(provider: upstream)
        var assistant = AgentMessage(role: .assistant, parts: [.text("answer")])
        assistant.reasoningContent = thoughts
        let wire = provider.flattenChatCompletionsMessages([assistant])
        for row in wire where (row["role"] as? String) == "assistant" {
            XCTAssertNil(row["reasoning_content"],
                         "Mistral must never receive reasoning_content — it 422s the request")
        }
    }

    /// The Responses-API encrypted summary is a UI string, not usable history:
    /// feeding it back makes other models in-context-learn and parrot it.
    /// It must be flattened to "" rather than echoed verbatim.
    func testEncryptedReasoningSummaryIsNotEchoedVerbatim() {
        let provider = OpenAIAgentProvider(
            provider: OpenAIProvider(apiKey: "k", model: deepseekModel, customBaseURL: "https://api.deepseek.com/v1"))
        var assistant = AgentMessage(role: .assistant, parts: [.text("answer")])
        assistant.reasoningContent = OpenAIAgentProvider.encryptedReasoningMarker + " 512 tokens (encrypted)"
        let wire = provider.flattenChatCompletionsMessages([assistant])
        for row in wire where (row["role"] as? String) == "assistant" {
            if let rc = row["reasoning_content"] as? String {
                XCTAssertEqual(rc, "", "the encrypted-summary marker must be stripped, not round-tripped")
            }
        }
    }

    /// Ordinary reasoning still round-trips for the compat providers that need
    /// it (DeepSeek V4 etc.) — the fix must not suppress the working path.
    func testOrdinaryReasoningStillRoundTripsForCompatProviders() {
        let provider = OpenAIAgentProvider(
            provider: OpenAIProvider(apiKey: "k", model: deepseekModel, customBaseURL: "https://api.deepseek.com/v1"))
        var assistant = AgentMessage(role: .assistant, parts: [.text("answer")])
        assistant.reasoningContent = thoughts
        let wire = provider.flattenChatCompletionsMessages([assistant])
        let assistantRows = wire.filter { ($0["role"] as? String) == "assistant" }
        XCTAssertFalse(assistantRows.isEmpty)
        XCTAssertEqual(assistantRows.first?["reasoning_content"] as? String, thoughts)
    }

    // MARK: - Model fixtures

    private var mistralModel: LLMModel {
        LLMModel(id: "mistral-large-latest", displayName: "Mistral Large",
                 provider: "OpenAI", supportsReasoning: false)
    }

    private var deepseekModel: LLMModel {
        LLMModel(id: "deepseek-v4-pro", displayName: "DeepSeek V4 Pro",
                 provider: "OpenAI", supportsReasoning: true,
                 interleavedReasoningField: "reasoning_content")
    }
}
