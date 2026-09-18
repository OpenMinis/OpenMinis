import XCTest
@testable import Minis

/// [GH#340] A model entry's capabilities used to be a snapshot taken at add
/// time. `deepseek-flash` arrived as text-only (OpenAI-compat always-write)
/// and stayed that way even after the catalog learned image input.
final class ModelEntryLiveCapabilitiesTests: XCTestCase {

    private func frozenFlash(id: String = "deepseek-flash") -> LLMModel {
        LLMModel(
            id: id,
            displayName: "DeepSeek Flash",
            provider: "OpenAI",
            modalityOverride: [.textInput, .textOutput],
            contextWindow: 128_000,
            maxOutputTokens: 8_192
        )
    }

    func testFrozenDeepseekFlashGainsImageAnd1MContext() {
        let entry = ModelEntry(providerInstanceId: "p", model: frozenFlash())
        XCTAssertTrue(
            entry.model.capabilities.supportedModalities.contains(.imageInput),
            "frozen text-only snapshot must gain image input"
        )
        XCTAssertEqual(entry.model.contextWindow, 1_000_000)
        XCTAssertEqual(entry.model.maxOutputTokens, 384_000)
        XCTAssertEqual(entry.model.supportsReasoning, true)
        XCTAssertFalse(
            entry.baseModel.capabilities.supportedModalities.contains(.imageInput),
            "persisted snapshot must stay frozen"
        )
        XCTAssertEqual(entry.baseModel.contextWindow, 128_000)
    }

    func testNamespacedIdAlsoUpgrades() {
        let entry = ModelEntry(providerInstanceId: "p", model: frozenFlash(id: "deepseek/deepseek-flash"))
        XCTAssertTrue(entry.model.capabilities.supportedModalities.contains(.imageInput))
    }

    func testUserModalityOverrideStillWins() {
        let entry = ModelEntry(
            providerInstanceId: "p",
            model: frozenFlash(),
            overrides: ModelOverrides(modalityOverride: [.textInput, .textOutput])
        )
        XCTAssertFalse(
            entry.model.capabilities.supportedModalities.contains(.imageInput),
            "an explicit text-only override must not be upgraded to vision"
        )
    }

    func testUnrelatedModelIsUnchanged() {
        let frozen = LLMModel(
            id: "some-local-llama",
            displayName: "llama",
            provider: "OpenAI",
            modalityOverride: [.textInput, .textOutput]
        )
        let entry = ModelEntry(providerInstanceId: "p", model: frozen)
        XCTAssertFalse(entry.model.capabilities.supportedModalities.contains(.imageInput))
    }
}
