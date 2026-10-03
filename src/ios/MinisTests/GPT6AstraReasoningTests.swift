import XCTest
@testable import Minis

// [T-gpt6-astra-effort] gpt-6-astra shipped with `supportsReasoning` nil:
// the static catalog entry did not declare it and models.dev had not
// indexed the id yet, so `ModelsDevAPI.enrichModels()` never filled it in.
// `OpenAIAgentProvider.reasoningEffort(for:)` guards on
// `supportsReasoning ?? false`, so every level the user picked collapsed
// into the Codex-OAuth fallback `reasoning.effort: "low"` on the wire.
// This pins the static entry and the level → effort chain for it.
final class GPT6AstraReasoningTests: XCTestCase {

    func testStaticEntryDeclaresReasoning() {
        XCTAssertEqual(LLMModel.gpt6Astra.supportsReasoning, true)
        XCTAssertEqual(LLMModel.allOpenAICodexOAuth.first?.id, "gpt-6-astra")
    }

    func testCatalogCeilingIsMaxLikeGPT56Sol() {
        XCTAssertEqual(ThinkingLevelCatalog.declaredMaxLevel(for: "gpt-6-astra"), .max)
        XCTAssertEqual(ThinkingLevelCatalog.declaredMaxLevel(for: "gpt-6-astra"),
                       ThinkingLevelCatalog.declaredMaxLevel(for: "gpt-5.6-sol"))
    }

    func testEveryLevelReachesTheWire() {
        let m = LLMModel.gpt6Astra
        XCTAssertNil(OpenAIAgentProvider.reasoningEffort(for: m, level: .off))
        XCTAssertEqual(OpenAIAgentProvider.reasoningEffort(for: m, level: .low), "low")
        XCTAssertEqual(OpenAIAgentProvider.reasoningEffort(for: m, level: .medium), "medium")
        XCTAssertEqual(OpenAIAgentProvider.reasoningEffort(for: m, level: .high), "high")
        XCTAssertEqual(OpenAIAgentProvider.reasoningEffort(for: m, level: .xhigh), "xhigh")
        XCTAssertEqual(OpenAIAgentProvider.reasoningEffort(for: m, level: .max), "max")
        // ultra is client-side only; the shared clamp sends "max".
        XCTAssertEqual(OpenAIAgentProvider.reasoningEffort(for: m, level: .ultra), "max")
    }

    func testNilFlagWouldHaveCollapsedToNil() {
        // The exact pre-fix shape: same id, flag never enriched.
        let unenriched = LLMModel(id: "gpt-6-astra", displayName: "GPT-6 Astra", provider: "OpenAI")
        XCTAssertNil(OpenAIAgentProvider.reasoningEffort(for: unenriched, level: .xhigh),
                     "documents why the explicit flag is load-bearing")
    }
}
