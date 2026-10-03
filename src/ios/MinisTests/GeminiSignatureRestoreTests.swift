import XCTest
@testable import Minis

/// [T-gemini-signature-restore] `toolCallMetadataMap` is PER-PROVIDER-INSTANCE
/// state, but the agent loop builds several providers per run: on group
/// fallback, on a mid-stream failover, and when the user switches model during
/// a retry countdown. Only the first one ever received the session's recorded
/// signatures — and the session then cleared them — so every later provider
/// started blind, judged history it had signatures for as "unsigned", and
/// downgraded real `functionCall` pairs to narrated text.
///
/// These tests pin the two halves of the fix: signatures survive being handed
/// to more than one provider, and a signature captured mid-run is retained on
/// the session so a provider built later in the SAME run still sees it.
@MainActor
final class GeminiSignatureRestoreTests: XCTestCase {

    private func geminiProvider(model id: String = "gemini-3-flash-preview") -> GeminiAgentProvider {
        GeminiAgentProvider(provider: GeminiProvider(
            apiKey: "k",
            model: LLMModel(id: id, displayName: id, provider: "Gemini", supportsReasoning: true)))
    }

    private func toolUseHistory(id: String) -> [AgentMessage] {
        [
            AgentMessage(role: .user, parts: [.text("do it")]),
            AgentMessage(role: .assistant, parts: [.toolUse(id: id, name: "shell_execute", input: ["command": "ls"])]),
            AgentMessage(role: .user, parts: [.toolResult(id: id, name: "shell_execute",
                                                          content: "total 0", isError: false)]),
        ]
    }

    /// Does this converted history still contain a structural functionCall, or
    /// was it downgraded to narration?
    private func isStructural(_ contents: [[String: Any]]) -> Bool {
        contents.contains { c in
            guard let parts = c["parts"] as? [[String: Any]] else { return false }
            return parts.contains { $0["functionCall"] != nil }
        }
    }

    /// A provider holding the signature replays the call structurally.
    func testSignedHistoryStaysStructural() {
        let p = geminiProvider()
        p.restoreToolCallMetadata(["call-1": ToolCallMetadata(thoughtSignature: "sig-abc")])
        XCTAssertTrue(isStructural(p.convertMessages(toolUseHistory(id: "call-1"))))
    }

    /// Without the signature it degrades — this is the behaviour the fix keeps
    /// OUT of the fallback path, and it must remain correct where it belongs
    /// (genuinely unsigned history, e.g. a DeepSeek call replayed on Gemini).
    func testUnsignedHistoryIsNarrated() {
        let p = geminiProvider()
        let contents = p.convertMessages(toolUseHistory(id: "call-1"))
        XCTAssertFalse(isStructural(contents))
        let allText = contents.flatMap { ($0["parts"] as? [[String: Any]] ?? []) }
            .compactMap { $0["text"] as? String }.joined(separator: "\n")
        XCTAssertTrue(allText.contains("Earlier in this conversation, the shell_execute tool was run"))
    }

    /// THE REGRESSION: the same signature map handed to a SECOND provider — the
    /// one a group fallback builds — must still produce a structural call. The
    /// old code cleared the session's map after the first restore, so this
    /// second provider degraded history it had every right to replay.
    func testSignaturesSurviveIntoAFallbackProvider() {
        let signatures = ["call-1": ToolCallMetadata(thoughtSignature: "sig-abc")]

        let first = geminiProvider()
        first.restoreToolCallMetadata(signatures)
        XCTAssertTrue(isStructural(first.convertMessages(toolUseHistory(id: "call-1"))))

        // Fallback builds a brand-new provider; it gets the SAME map because the
        // session no longer discards it after the first hand-off.
        let second = geminiProvider(model: "gemini-3-pro-preview")
        second.restoreToolCallMetadata(signatures)
        XCTAssertTrue(isStructural(second.convertMessages(toolUseHistory(id: "call-1"))),
                      "a fallback provider must not re-downgrade signed history")
    }

    /// Restoring is additive: a provider that already saw a signature live keeps
    /// it when the session's map is merged in.
    func testRestoreMergesRatherThanReplaces() {
        let p = geminiProvider()
        p.restoreToolCallMetadata(["call-1": ToolCallMetadata(thoughtSignature: "sig-1")])
        p.restoreToolCallMetadata(["call-2": ToolCallMetadata(thoughtSignature: "sig-2")])
        XCTAssertTrue(isStructural(p.convertMessages(toolUseHistory(id: "call-1"))))
        XCTAssertTrue(isStructural(p.convertMessages(toolUseHistory(id: "call-2"))))
    }

    /// Non-Gemini-3 models never required signatures; the downgrade must not
    /// fire for them regardless of what the map holds.
    func testGemini2NeverDowngrades() {
        let p = geminiProvider(model: "gemini-2.5-flash")
        XCTAssertTrue(isStructural(p.convertMessages(toolUseHistory(id: "call-1"))),
                      "only gemini-3.x requires thoughtSignature")
    }
}
