import Foundation
var fail = 0
func ck(_ l: String, _ ok: Bool) { print(ok ? "  ✅ \(l)" : "  ❌ \(l)"); if !ok { fail += 1 } }

// Mirrors OpenAIProvider.applyModelOverrides + the caller's chatExtraBody merge.
func applyModelOverrides(to body: inout [String: Any],
                         temp: Double?, topP: Double?, extra: [String: String]) {
    if let t = temp, body["temperature"] == nil { body["temperature"] = t }
    if let p = topP, body["top_p"] == nil { body["top_p"] = p }
    for (k, v) in extra where body[k] == nil { body[k] = v }
}
func mergeExtraBody(_ body: inout [String: Any], _ chatExtraBody: [String: Any], model: String) {
    guard !chatExtraBody.isEmpty else { return }
    for (k, v) in chatExtraBody { body[k] = v }
    body["model"] = model
}

print("\n[1] No overrides ⇒ request is byte-identical to before")
var b1: [String: Any] = ["model": "m", "messages": []]
let before = b1.keys.sorted()
applyModelOverrides(to: &b1, temp: nil, topP: nil, extra: [:])
ck("no keys added", b1.keys.sorted() == before)

print("\n[2] Overrides are injected when set")
var b2: [String: Any] = ["model": "m"]
applyModelOverrides(to: &b2, temp: 0.3, topP: 0.85, extra: ["service_tier": "flex"])
ck("temperature injected", b2["temperature"] as? Double == 0.3)
ck("top_p injected", b2["top_p"] as? Double == 0.85)
ck("extra body param injected", b2["service_tier"] as? String == "flex")

print("\n[3] An explicit per-request value beats the stored override")
var b3: [String: Any] = ["model": "m", "temperature": 1.0]
applyModelOverrides(to: &b3, temp: 0.3, topP: nil, extra: [:])
ck("caller's temperature wins", b3["temperature"] as? Double == 1.0)

print("\n[4] model_use passthrough still wins over a stored override")
var b4: [String: Any] = ["model": "m"]
applyModelOverrides(to: &b4, temp: 0.3, topP: nil, extra: ["service_tier": "flex"])
mergeExtraBody(&b4, ["temperature": 0.9, "service_tier": "priority"], model: "m")
ck("passthrough temperature wins", b4["temperature"] as? Double == 0.9)
ck("passthrough extra param wins", b4["service_tier"] as? String == "priority")
ck("model is force-kept", b4["model"] as? String == "m")

print("\n[5] Reserved headers are refused")
func isReserved(_ n: String) -> Bool {
    ["authorization","api-key","x-api-key","anthropic-version","content-type","content-length","host"]
        .contains(n.lowercased())
}
var headers = ["Authorization": "Bearer real-key", "User-Agent": "Minis/1.14"]
for (k, v) in ["Authorization": "Bearer HIJACK", "X-Workspace": "w1", "Content-Type": "text/plain"]
where !isReserved(k) { headers[k] = v }
ck("Authorization NOT overwritten", headers["Authorization"] == "Bearer real-key")
ck("Content-Type NOT overwritten", headers["Content-Type"] == nil)
ck("ordinary custom header applied", headers["X-Workspace"] == "w1")
ck("per-model header can override a provider-wide one",
   { var h = ["X-Tier": "std"]; for (k,v) in ["X-Tier": "pro"] where !isReserved(k) { h[k] = v }
     return h["X-Tier"] == "pro" }())

print("\n[6] Shipping source matches")
func source(_ r: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(r), encoding: .utf8)) ?? ""
}
let prov = source("Providers/OpenAI/OpenAIProvider.swift")
let fac = source("Providers/LLMProviderFactory.swift")
let store = source("Providers/ProviderConfigStore.swift")
let agent = source("Agent/Chat/AIChatViewModel+ProviderFactory.swift")
if prov.isEmpty { print("  ⏭  sources not readable from this sandbox") } else {
    ck("provider has the override channel",
       prov.contains("var overrideTemperature: Double?") && prov.contains("var overrideExtraBody: [String: String]"))
    ck("injection guarded so caller wins", prov.contains("body[\"temperature\"] == nil"))
    ck("applied in the chat builder before chatExtraBody",
       prov.range(of: "applyModelOverrides(to: &body)\n        if !chatExtraBody.isEmpty {") != nil)
    ck("applied in the responses builder too", prov.contains("if !isCodexOAuth { applyModelOverrides(to: &body) }"))
    ck("factory injector exists", fac.contains("static func applyModelOverrides(_ provider: OpenAIProvider, entry: ModelEntry)"))
    ck("reserved header list present", fac.contains("static func isReservedOverrideHeader"))
    // [T-opencode-dedicated-channel] The chain changed shape: OpenCode is now
    // attached as a dedicated channel (conditionally, per instance) before the
    // entry's overrides are applied, instead of being a nested call.
    ck("makeProvider applies the entry overrides", fac.contains("return applyModelOverrides(p, entry: entry)"))
    ck("and gates the OpenCode channel on instance membership",
       fac.contains("if instance.isOpenCodeChannel {"))
    ck("agent path chains it via wrap()", agent.contains("func wrap(_ p: OpenAIProvider) -> OpenAIProvider"))
    ck("refresh inherits whole overrides struct", store.contains("overrides: prior?.overrides ?? ModelOverrides()"))
    ck("export carries the new fields", store.contains("o[\"extraBodyParams\"] = eb"))
    ck("import restores the new fields", store.contains("overrides.extraBodyParams = o[\"extraBodyParams\"] as? [String: String]"))
}
print("\n\(fail == 0 ? "✅ ALL PASSED" : "❌ \(fail) FAILURE(S)")")
exit(fail == 0 ? 0 : 1)
