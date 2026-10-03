// Tests for [T-codex-gpt-image25-variants] — round-2 item M21, iOS half
// (the twin of Android's CodexImageModelTest).
//
// The Codex OAuth image path posts to chatgpt.com/backend-api/codex/responses
// with a built-in `image_generation` tool. Two different model ids live in that
// body and must never be conflated:
//
//   body["model"]      — the Codex TEXT model that drives the tool (gpt-5.5, …)
//   tools[0]["model"]  — the IMAGE model the tool should run
//
// Writing the image id into `body["model"]` would 400. And the tool object is
// named only for the 2.5 variants: `gpt-image-2` predates the field and is
// served by the backend default, so it stays bare and that shipped path keeps a
// byte-identical body.
//
// Ports:
//   LLMModel.codexImageToolNeedsExplicitModel / allCodexOAuthImageModelIDs
//                                        — LLMTypes.swift ~L468-478
//   the tool-object builder in generateImageViaCodexResponses
//                                        — OpenAIProvider.swift ~L2061-2085
//   the routing gate                     — ModelUseOffloadBridge.swift ~L588
//
// Standalone (`swift CodexImageToolModelFieldTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func json(_ obj: Any) -> String {
    let d = try! JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    return String(data: d, encoding: .utf8)!
}

// MARK: - Ports

let gptImage2 = "gpt-image-2"
let gptImage25Sunburst = "gpt-image-2.5-sunburst"
let gptImage25Flare = "gpt-image-2.5-flare"
let allCodexOAuthImageModelIDs: Set<String> = [gptImage2, gptImage25Sunburst, gptImage25Flare]

/// LLMModel.codexImageToolNeedsExplicitModel — an exact-id match, not a prefix.
func codexImageToolNeedsExplicitModel(_ modelId: String) -> Bool {
    modelId == gptImage25Sunburst || modelId == gptImage25Flare
}

/// The two model-carrying fields of the Codex image request, as the builder
/// writes them.
func codexImageBody(boundModelId: String, topLevelModel: String?, imageModel: String?) -> [String: Any] {
    let resolvedImageModel = imageModel ?? boundModelId
    var imageTool: [String: Any] = ["type": "image_generation"]
    if codexImageToolNeedsExplicitModel(resolvedImageModel) {
        imageTool["model"] = resolvedImageModel
    }
    return [
        "model": topLevelModel ?? boundModelId,
        "tools": [imageTool],
        "tool_choice": "auto",
        "reasoning": ["effort": "low"],
        "store": false,
    ]
}

/// ModelUseOffloadBridge's routing gate.
func routesToCodexImage(modelId: String, isOAuth: Bool, customBaseURL: String?, forceResponsesAPI: Bool) -> Bool {
    allCodexOAuthImageModelIDs.contains(modelId) && isOAuth && customBaseURL == nil && !forceResponsesAPI
}

print("▶️  1. the predicate names only the 2.5 variants")
do {
    check("gpt-image-2.5-sunburst → needs an explicit model", codexImageToolNeedsExplicitModel(gptImage25Sunburst))
    check("gpt-image-2.5-flare → needs an explicit model", codexImageToolNeedsExplicitModel(gptImage25Flare))
    check("gpt-image-2 (the base) → does NOT", !codexImageToolNeedsExplicitModel(gptImage2))
    // Exact match, deliberately: a prefix or `contains` test would sweep in ids
    // the backend has never been probed with.
    check("gpt-image-2.5 (bare, no variant) → no", !codexImageToolNeedsExplicitModel("gpt-image-2.5"))
    check("gpt-image-2.5-sunburst-preview → no (not a probed id)", !codexImageToolNeedsExplicitModel("gpt-image-2.5-sunburst-preview"))
    check("GPT-Image-2.5-Sunburst (case) → no (ids are compared verbatim)", !codexImageToolNeedsExplicitModel("GPT-Image-2.5-Sunburst"))
    check("gpt-image-3 (future) → no", !codexImageToolNeedsExplicitModel("gpt-image-3"))
    check("a Codex text model → no", !codexImageToolNeedsExplicitModel("gpt-5.5"))
    check("empty id → no", !codexImageToolNeedsExplicitModel(""))
    // Every id the predicate claims must also be routable to this path at all,
    // or the tool object would be built for a model that never gets there.
    check("every needs-explicit id is in allCodexOAuthImageModelIDs",
          allCodexOAuthImageModelIDs.filter(codexImageToolNeedsExplicitModel) == [gptImage25Sunburst, gptImage25Flare].reduce(into: Set<String>()) { $0.insert($1) })
    checkEq("the routing set is exactly the three image models", allCodexOAuthImageModelIDs.sorted(), [gptImage2, gptImage25Flare, gptImage25Sunburst].sorted())
}

print("▶️  2. on the wire: the two model fields never cross")
do {
    let sunburst = codexImageBody(boundModelId: gptImage25Sunburst, topLevelModel: "gpt-5.5", imageModel: gptImage25Sunburst)
    checkEq("2.5 sunburst: tool names the image model",
            json(sunburst["tools"]!), #"[{"model":"gpt-image-2.5-sunburst","type":"image_generation"}]"#)
    checkEq("…while body[\"model\"] stays the Codex TEXT model", sunburst["model"] as? String, "gpt-5.5")
    check("…the image id never appears in body[\"model\"]", (sunburst["model"] as? String) != gptImage25Sunburst)

    let flare = codexImageBody(boundModelId: gptImage25Flare, topLevelModel: "gpt-5.6-sol", imageModel: gptImage25Flare)
    checkEq("2.5 flare: same shape", json(flare["tools"]!), #"[{"model":"gpt-image-2.5-flare","type":"image_generation"}]"#)
    checkEq("…own text driver preserved", flare["model"] as? String, "gpt-5.6-sol")

    let base = codexImageBody(boundModelId: gptImage2, topLevelModel: "gpt-5.5", imageModel: gptImage2)
    checkEq("gpt-image-2: the tool object stays BARE (shipped path unchanged)",
            json(base["tools"]!), #"[{"type":"image_generation"}]"#)
    check("…no \"model\" key at all, not an empty string", ((base["tools"] as! [[String: Any]])[0]["model"]) == nil)
    checkEq("…and its body[\"model\"] is still the text driver", base["model"] as? String, "gpt-5.5")

    // imageModel defaults to the provider's bound entry, so an older caller that
    // does not pass it produces the same body.
    let defaulted = codexImageBody(boundModelId: gptImage25Flare, topLevelModel: "gpt-5.5", imageModel: nil)
    checkEq("imageModel nil falls back to the bound model id", json(defaulted["tools"]!), #"[{"model":"gpt-image-2.5-flare","type":"image_generation"}]"#)
    let defaultedBase = codexImageBody(boundModelId: gptImage2, topLevelModel: nil, imageModel: nil)
    checkEq("both nil on gpt-image-2: bare tool, model = the bound id", json(defaultedBase), #"{"model":"gpt-image-2","reasoning":{"effort":"low"},"store":false,"tool_choice":"auto","tools":[{"type":"image_generation"}]}"#)

    // The rest of the fingerprint is fixed for every variant — it is part of the
    // codex_cli shape and must not vary with the image model.
    for m in [gptImage2, gptImage25Sunburst, gptImage25Flare] {
        let b = codexImageBody(boundModelId: m, topLevelModel: "gpt-5.5", imageModel: m)
        check("\(m): reasoning effort low", json(b["reasoning"]!) == #"{"effort":"low"}"#)
        check("\(m): tool_choice auto, store false", (b["tool_choice"] as? String) == "auto" && (b["store"] as? Bool) == false)
        check("\(m): exactly one tool", (b["tools"] as? [[String: Any]])?.count == 1)
    }
}

print("▶️  3. the routing gate is the same for all three")
do {
    for m in [gptImage2, gptImage25Sunburst, gptImage25Flare] {
        check("\(m) on an OAuth instance with no custom base → codex image path",
              routesToCodexImage(modelId: m, isOAuth: true, customBaseURL: nil, forceResponsesAPI: false))
        check("\(m) with an API key → NOT this path", !routesToCodexImage(modelId: m, isOAuth: false, customBaseURL: nil, forceResponsesAPI: false))
        check("\(m) with a custom base URL → NOT this path", !routesToCodexImage(modelId: m, isOAuth: true, customBaseURL: "https://relay.example/v1", forceResponsesAPI: false))
        check("\(m) with forceResponsesAPI → NOT this path", !routesToCodexImage(modelId: m, isOAuth: true, customBaseURL: nil, forceResponsesAPI: true))
    }
    check("a Codex text model never routes here", !routesToCodexImage(modelId: "gpt-5.5", isOAuth: true, customBaseURL: nil, forceResponsesAPI: false))
    check("a non-Codex image model never routes here", !routesToCodexImage(modelId: "dall-e-3", isOAuth: true, customBaseURL: nil, forceResponsesAPI: false))
}

print("▶️  4. shipping sources still carry the pinned lines")
do {
    let types = source("Providers/LLMTypes.swift")
    let op = source("Providers/OpenAI/OpenAIProvider.swift")
    let bridge = source("NativeOffloads/ModelUseOffloadBridge.swift")
    if types.isEmpty || op.isEmpty || bridge.isEmpty { print("  ⏭  sources not readable") } else {
        check("predicate is an exact two-id compare", types.contains("modelId == gptImage25Sunburst.id || modelId == gptImage25Flare.id"))
        check("the routing set carries all three", types.contains("gptImage2.id, gptImage25Sunburst.id, gptImage25Flare.id,"))
        for (name, id) in [("gptImage2", "gpt-image-2"), ("gptImage25Sunburst", "gpt-image-2.5-sunburst"), ("gptImage25Flare", "gpt-image-2.5-flare")] {
            check("\(name) id is still \(id)", types.contains("id: \"\(id)\","))
        }
        check("the tool object is named only behind the predicate", op.contains("if LLMModel.codexImageToolNeedsExplicitModel(resolvedImageModel) {\n            imageTool[\"model\"] = resolvedImageModel"))
        check("resolvedImageModel defaults to the bound model", op.contains("let resolvedImageModel = imageModel ?? model.id"))
        check("body[\"model\"] is the topLevel/text model, never the image one", op.contains("\"model\": topLevelModel ?? model.id,"))
        check("the two are documented as different things", op.contains("// only for the 2.5 variants. `body[\"model\"]` is a different thing"))
        check("the bridge gates on set membership, not one id", bridge.contains("LLMModel.allCodexOAuthImageModelIDs.contains(entry.model.id)"))
        check("…plus OAuth, no custom base, no forced Responses", bridge.contains("&& openAI.isOAuth\n                && openAI.customBaseURL == nil\n                && !openAI.forceResponsesAPI"))
        check("the bridge passes the picked entry as imageModel", bridge.contains("imageModel: entry.model.id,"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
