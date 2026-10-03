import XCTest
@testable import Minis

// [T-agent-model-identity] Locks the three-fact contract (tier / resolved /
// effective) on every path it travels through: the tool_result JSON payload,
// the <agent_callback> attributes, the parent block the UI parses, and the
// compact one-line formats the inline block / thumbnail / nav bar draw.
// None of this is verifiable from a screenshot — a lookalike string from the
// wrong fact would pass a visual check — so the semantics are pinned here.
final class HelperModelIdentityTests: XCTestCase {

    private func resolved() -> HelperModelIdentity {
        var id = HelperModelIdentity(tierRequested: "sub", tierUsed: "primary")
        id.resolvedEntryId = "inst-53/claude-sonnet-5"
        id.resolvedProviderLabel = "Anthropic (53)"
        id.resolvedProviderType = "anthropic"
        id.resolvedModelId = "claude-sonnet-5"
        id.resolvedModelName = "Claude Sonnet 5"
        return id
    }

    private func served(entry: String = "inst-53/claude-sonnet-5", model: String = "claude-sonnet-5",
                        name: String = "Claude Sonnet 5", provider: String = "Anthropic (53)",
                        response: String? = nil) -> EffectiveModelRecord {
        var r = EffectiveModelRecord()
        r.entryId = entry; r.modelId = model; r.modelName = name; r.providerLabel = provider
        r.responseModel = response
        return r
    }

    // MARK: Semantics

    func testResolvedIsNotCopiedIntoEffective() {
        let id = resolved()
        XCTAssertEqual(id.resolvedLabel, "Anthropic (53) · Claude Sonnet 5")
        XCTAssertNil(id.effectiveModel, "effective must stay unknown until a served turn is recorded")
        XCTAssertFalse(id.hasEffective)
        XCTAssertNil(id.effectiveSource)
        XCTAssertTrue(id.tierDegraded)
        XCTAssertEqual(HelperBlockInfo.tierLabel(id, fallback: nil), "sub → primary")
    }

    func testMergeRequestSideThenResponseSide() {
        var id = resolved()
        id.merge(served())
        XCTAssertEqual(id.effectiveModel, "claude-sonnet-5")
        XCTAssertEqual(id.effectiveSource, "request")
        XCTAssertTrue(id.effectiveMatchesResolved)
        XCTAssertFalse(id.entryFellBack)

        id.merge(served(response: "claude-sonnet-5-20260601"))
        XCTAssertEqual(id.effectiveModel, "claude-sonnet-5-20260601", "the API-reported name wins over the request id")
        XCTAssertEqual(id.effectiveSource, "response")
        XCTAssertTrue(id.effectiveMatchesResolved, "a dated snapshot of the same model normalises equal")
    }

    func testProviderFallbackIsVisibleAsDifferentEntry() {
        var id = resolved()
        id.merge(served(entry: "inst-7/anthropic/claude-sonnet-5", model: "anthropic/claude-sonnet-5",
                        name: "Sonnet 5", provider: "OpenRouter", response: "anthropic/claude-sonnet-5"))
        XCTAssertTrue(id.entryFellBack)
        XCTAssertEqual(id.effectiveLabel, "OpenRouter · Sonnet 5")
        XCTAssertTrue(id.effectiveMatchesResolved, "same model via another provider still counts as the same model")

        var other = resolved()
        other.merge(served(entry: "inst-53/claude-haiku-4-5", model: "claude-haiku-4-5", name: "Claude Haiku 4.5",
                           response: "claude-haiku-4-5-20251001"))
        XCTAssertTrue(other.entryFellBack)
        XCTAssertFalse(other.effectiveMatchesResolved)
        XCTAssertEqual(other.compactLine(), "Claude Sonnet 5 → claude-ha…20251001", "each half is bounded to 18 chars")
        XCTAssertEqual(other.compactLine(maxModel: 40), "Claude Sonnet 5 → claude-haiku-4-5-20251001")
    }

    func testEntryChangeDropsStaleResponseModel() {
        // Mirrors AIChatViewModel.noteEffectiveEntry: a fallback to another
        // entry must not keep the previous provider's reported model.
        var id = resolved()
        id.merge(served(response: "claude-sonnet-5-20260601"))
        var moved = served(entry: "inst-9/gpt-5", model: "gpt-5", name: "GPT-5", provider: "OpenAI")
        moved.responseModel = nil
        var fresh = HelperModelIdentity(tierRequested: id.tierRequested, tierUsed: id.tierUsed)
        fresh.resolvedEntryId = id.resolvedEntryId; fresh.resolvedModelId = id.resolvedModelId
        fresh.resolvedModelName = id.resolvedModelName; fresh.resolvedProviderLabel = id.resolvedProviderLabel
        fresh.merge(moved)
        XCTAssertEqual(fresh.effectiveModel, "gpt-5")
        XCTAssertEqual(fresh.effectiveSource, "request")
    }

    func testNormalization() {
        let n = HelperModelIdentity.normalizedModelId
        XCTAssertEqual(n("claude-sonnet-5"), n("Claude-Sonnet-5-20260101"))
        XCTAssertEqual(n("anthropic/claude-sonnet-5"), n("claude-sonnet-5"))
        XCTAssertNotEqual(n("gpt-5"), n("gpt-5-mini"))
        XCTAssertNotEqual(n("gemini-2.5-pro"), n("gemini-2.5-flash"))
    }

    // MARK: Payload round-trip (what the tool_result persists)

    func testPayloadRoundTrip() throws {
        var id = resolved()
        id.merge(served(response: "claude-sonnet-5-20260601"))
        let payload = id.payload()
        XCTAssertEqual(payload["tier_requested"] as? String, "sub")
        XCTAssertEqual(payload["tier_used"] as? String, "primary")
        XCTAssertEqual(payload["model_resolved"] as? String, "Anthropic (53) · Claude Sonnet 5")
        XCTAssertEqual(payload["model_effective"] as? String, "claude-sonnet-5-20260601")
        XCTAssertEqual(payload["model_effective_source"] as? String, "response")
        // Through JSON, exactly as ChatStore stores the tool_result text.
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        let back = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let parsed = try XCTUnwrap(HelperModelIdentity(payload: back))
        XCTAssertEqual(parsed, id)
    }

    func testPayloadWithoutIdentityKeysIsNil() {
        XCTAssertNil(HelperModelIdentity(payload: ["ok": true, "status": "completed", "model_used": "Claude Sonnet 5"]))
    }

    func testMinimalPayloadOneLineEffective() throws {
        let parsed = try XCTUnwrap(HelperModelIdentity(payload: ["tier_used": "primary", "model_effective": "gpt-5"]))
        XCTAssertEqual(parsed.effectiveModel, "gpt-5")
        XCTAssertEqual(parsed.effectiveSource, "request")
    }

    // MARK: Callback XML (background completion / scheduled child)

    func testCallbackXMLCarriesIdentity() throws {
        var id = resolved()
        id.merge(served(response: "claude-sonnet-5-20260601"))
        let cb = AgentCallback(kind: .finished, jobId: "job-1", childSessionId: "child-1", title: "Summarise",
                               status: "done", tier: "primary", elapsed: "1m02s", tool: nil, activity: nil,
                               turn: nil, summary: "tools none yet · turns 1", body: "the answer",
                               modelIdentity: id)
        let xml = cb.xml
        XCTAssertTrue(xml.contains("model_resolved=\"Anthropic (53) · Claude Sonnet 5\""))
        XCTAssertTrue(xml.contains("model_effective=\"claude-sonnet-5-20260601\""))
        XCTAssertTrue(xml.contains("tier_requested=\"sub\""))
        let parsed = try XCTUnwrap(AgentCallback.parse(xml))
        let pid = try XCTUnwrap(parsed.modelIdentity)
        XCTAssertEqual(pid.tierRequested, "sub")
        XCTAssertEqual(pid.tierUsed, "primary")
        XCTAssertEqual(pid.resolvedLabel, "Anthropic (53) · Claude Sonnet 5")
        XCTAssertEqual(pid.resolvedModelId, "claude-sonnet-5")
        XCTAssertEqual(pid.effectiveModel, "claude-sonnet-5-20260601")
        XCTAssertEqual(pid.effectiveSource, "response")
        XCTAssertTrue(pid.effectiveMatchesResolved)
        XCTAssertEqual(parsed.body, "the answer")
    }

    func testCallbackWithoutIdentityStillParses() throws {
        let cb = AgentCallback(kind: .finished, jobId: "job-2", childSessionId: nil, title: "t", status: "done",
                               tier: "primary", elapsed: "3s", tool: nil, activity: nil, turn: nil, summary: nil,
                               body: "x")
        let parsed = try XCTUnwrap(AgentCallback.parse(cb.xml))
        XCTAssertNil(parsed.modelIdentity, "a tier alone is not an identity — the pre-identity card keeps its own rows")
        XCTAssertEqual(parsed.tier, "primary")
    }

    // MARK: Block parsing (what the inline block / sheet read)

    @MainActor
    func testFinishedBlockParsesIdentityFromPersistedJSON() throws {
        var id = resolved()
        id.merge(served(response: "claude-sonnet-5-20260601"))
        var payload: [String: Any] = ["ok": true, "status": "completed", "result": "done", "elapsed_s": 8,
                                      "model_used": "Claude Sonnet 5", "tier_used": "primary"]
        payload.merge(id.payload()) { _, new in new }
        let json = String(data: try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]), encoding: .utf8)!
        let block = AssistantBlock(kind: .delegateTool(title: "Summarise"), content: json, toolStatus: .success)
        let info = HelperBlockInfo.parse(block)
        guard case .finished(let status, let tier, _, let elapsed, _, _, _, _) = info.phase else {
            return XCTFail("expected finished phase")
        }
        XCTAssertEqual(status, "completed")
        XCTAssertEqual(tier, "primary")
        XCTAssertEqual(elapsed, 8)
        let model = try XCTUnwrap(info.model)
        XCTAssertEqual(model.effectiveModel, "claude-sonnet-5-20260601")
        XCTAssertEqual(model.compactLine(), "Claude Sonnet 5 ✓")
        XCTAssertEqual(HelperBlockInfo.tierLabel(model, fallback: tier), "sub → primary")
        // [T-subagent-model-strategy] The card now answers "what was it told to
        // use" then "what did it actually run on", instead of naming two stages
        // of our resolution pipeline.
        let rows = HelperDetailCard.modelRows(info)
        XCTAssertEqual(rows.map(\.0), [AppLocalized("Model tier"), AppLocalized("Actual model")])
        XCTAssertEqual(rows[1].1, "Claude Sonnet 5（Anthropic (53)）")
    }

    @MainActor
    func testRunningBlockPrefersLiveIdentityAndShowsResolvedUntilConfirmed() {
        let block = AssistantBlock(kind: .delegateTool(title: "Fetch"),
                                   content: "◐ Agent · Fetch · shell_execute · listing files · 0:12",
                                   toolStatus: .running)
        block.helperModel = resolved()
        let info = HelperBlockInfo.parse(block)
        guard case .running(let tool, _, let clock) = info.phase else { return XCTFail("expected running") }
        XCTAssertEqual(tool, "shell_execute")
        XCTAssertEqual(clock, "0:12")
        XCTAssertEqual(info.model?.thumbnailLine(max: 16), "Claude Sonnet 5")
        XCTAssertFalse(info.model?.hasEffective ?? true)
        // Not yet served a turn: the strategy's model is still the honest
        // answer for "actual", since that is what the request will carry.
        let rows = HelperDetailCard.modelRows(info)
        XCTAssertEqual(rows.last?.1, "Claude Sonnet 5（Anthropic (53)）")

        // Confirmation arrives in place: the live value updates, no new block.
        var live = resolved()
        live.merge(served(response: "claude-sonnet-5"))
        block.helperModel = live
        XCTAssertEqual(HelperBlockInfo.parse(block).model?.compactLine(), "Claude Sonnet 5 ✓")
    }

    @MainActor
    func testPreIdentityPayloadStillShowsModelUsed() {
        let json = #"{"ok":true,"status":"completed","result":"r","model_used":"Claude Sonnet 5","tier_used":"primary","elapsed_s":3}"#
        let block = AssistantBlock(kind: .delegateTool(title: "Old"), content: json, toolStatus: .success)
        let info = HelperBlockInfo.parse(block)
        XCTAssertNil(info.model)
        let rows = HelperDetailCard.modelRows(info)
        XCTAssertEqual(rows.map(\.1), ["primary", "Claude Sonnet 5"])
    }

    // MARK: Compact formats

    func testCompactLineBoundsEachHalf() {
        var id = HelperModelIdentity(tierRequested: "primary", tierUsed: "primary")
        id.resolvedModelId = "a-very-long-model-name-with-many-parts-v2"
        id.resolvedModelName = "A Very Long Model Display Name Indeed"
        XCTAssertEqual(id.compactLine(maxModel: 18)?.count, 18)
        id.merge(served(entry: "x/other", model: "another-extremely-long-effective-model-identifier",
                        name: "Other", response: "another-extremely-long-effective-model-identifier"))
        let line = id.compactLine(maxModel: 18)!
        XCTAssertTrue(line.contains(" → "))
        XCTAssertLessThanOrEqual(line.count, 18 + 3 + 18)
        XCTAssertEqual(id.thumbnailLine(max: 16)?.count, 16)
    }

    func testThumbnailLinePrefersDisplayNameWhenSame() {
        var id = resolved()
        XCTAssertEqual(id.thumbnailLine(), "Claude Sonnet 5")
        id.merge(served(response: "claude-sonnet-5-20260601"))
        XCTAssertEqual(id.thumbnailLine(), "Claude Sonnet 5")
        id.merge(served(entry: "inst-53/claude-haiku-4-5", model: "claude-haiku-4-5", name: "Haiku", response: "claude-haiku-4-5"))
        XCTAssertEqual(id.thumbnailLine(), "claude-haiku-4-5")
    }
}
