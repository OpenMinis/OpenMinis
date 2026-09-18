import XCTest
@testable import Minis

/// [GH OpenMinis#361] Cerebras rejects `messages[].assistant.reasoning_content`:
///
///   HTTP 400 `messages.2.assistant.reasoning_content: property ... is unsupported`
///   (code `wrong_api_format`)
///
/// The FIRST turn succeeds; every later turn fails because the history echoes the
/// reasoning captured from the previous answer. This is a message-schema
/// constraint, not a capability one, so it cannot be inferred from model
/// metadata — the vendor is identified by base URL, the same predicate class as
/// the Mistral guard.
final class CerebrasReasoningHistoryTests: XCTestCase {

    private let reasoningModel = LLMModel(
        id: "gpt-oss-120b",
        displayName: "GPT-OSS 120B",
        provider: "TestProvider",
        supportsReasoning: true
    )

    /// History with a prior assistant turn that captured reasoning — the 400 trigger.
    private func historyWithReasoning() -> [AgentMessage] {
        [
            AgentMessage(role: .user, parts: [.text("first question")]),
            AgentMessage(
                role: .assistant,
                parts: [.text("first answer")],
                reasoningContent: "some captured chain of thought"
            ),
            AgentMessage(role: .user, parts: [.text("second question")]),
        ]
    }

    private func flattened(baseURL: String?) -> [[String: Any]] {
        let provider = OpenAIProvider(apiKey: "test-key", model: reasoningModel, customBaseURL: baseURL)
        let agent = OpenAIAgentProvider(provider: provider)
        return agent.flattenChatCompletionsMessages(historyWithReasoning(), thinkingLevel: .medium)
    }

    private func anyMessageHasReasoning(_ messages: [[String: Any]]) -> Bool {
        messages.contains { $0["reasoning_content"] != nil }
    }

    func testCerebrasStripsReasoningContentFromAssistantHistory() {
        let flat = flattened(baseURL: "https://api.cerebras.ai")
        XCTAssertFalse(
            anyMessageHasReasoning(flat),
            "reasoning_content must not be sent to Cerebras (400 unsupported): \(flat)"
        )
    }

    func testCerebrasDetectionIsCaseInsensitive() {
        let flat = flattened(baseURL: "https://API.CEREBRAS.AI")
        XCTAssertFalse(
            anyMessageHasReasoning(flat),
            "uppercase cerebras host must still suppress reasoning_content: \(flat)"
        )
    }

    func testNonCerebrasEndpointStillEchoesReasoningContent() {
        // Negative control: MiMo / DeepSeek return 400 when multi-turn history
        // LACKS this field, so the suppression must be scoped to Cerebras only.
        let flat = flattened(baseURL: "https://api.example.com")
        XCTAssertTrue(
            anyMessageHasReasoning(flat),
            "reasoning_content should still be echoed for non-Cerebras vendors: \(flat)"
        )
    }
}
