// Tests for [T-vision-thinking-off-400] + [T-vision-silent-image-drop] +
// [T-thinking-off-custom-provider] — an image routed through the Vision Group
// with thinking OFF must neither 400 on a thinking field the model rejects nor
// quietly lose the image.
//
// Two pieces of shipping logic, ported:
//   * The OFF-tier guard in ThinkingRuleResolver.emit (Providers/Thinking/
//     ThinkingRuleResolver.swift ~440-500, commits 4209a8dc1 → 5076e75ea →
//     621b0f27c). The off tier (`reasoning_effort: "none"` etc.) is withheld
//     only when the CATALOG says so: the model declares effort tiers, none of
//     them is an off value, AND the declaration is authoritative (came from
//     the model's own provider, not the cross-publisher fallback vote). The
//     vendor family name is NOT the predicate — the first version keyed on
//     deepseek/glm/kimi/minimax and silently took thinking-off away from 161
//     catalog entries that document an off tier.
//   * The modality gate in VisionGroupResolver.describeOnce (4209a8dc1): the
//     request carries the image only if the PROVIDER's model record declares
//     imageInput; otherwise the candidate throws into the failure path rather
//     than asking a text-only model to describe pixels it never received.
//
// Standalone (`swift VisionThinkingOffInteractionTests.swift`); section [4]
// greps the sources.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(expected)\n     actual:   \(actual)"); failures += 1 }
}

// MARK: - Port: ThinkingResolveContext (subset) + the generic reasoning_effort OFF path

struct Ctx {
    var modelId: String
    var supportsReasoning: Bool? = true
    var declaredEffortValues: [String]? = nil
    var effortDeclarationIsAuthoritative = false
    var usesUnifiedReasoningEffort = false
    var isXAI = false
    var declaresNoEffortTiers = false
    var levelIsEnabled = false          // thinking OFF for every case here
    var offEffort: String? = "none"     // explicitOffEffort's allowlist decision
}

/// `.reasoningEffort` format, thinking OFF, generic (non OpenAI-native id) path.
/// Returns the body fields the resolver would write.
func emitOff(_ ctx: Ctx) -> [String: String] {
    var body: [String: String] = [:]
    let lid = ctx.modelId.lowercased()
    let strictEffortEnum = lid.contains("mimo") || lid.contains("agnes")
    let declaredTiers = ctx.declaredEffortValues.map { Set($0.map { $0.lowercased() }) }
    let offTierNotDeclared: Bool = {
        guard ctx.effortDeclarationIsAuthoritative else { return false }
        guard let tiers = declaredTiers, !tiers.isEmpty else { return false }
        return tiers.isDisjoint(with: ["none", "off", "minimal", "disabled"])
    }()
    let offEffort = (strictEffortEnum || offTierNotDeclared) ? nil : ctx.offEffort

    let declaresEffort = !(ctx.declaredEffortValues?.isEmpty ?? true)
    if !ctx.usesUnifiedReasoningEffort, !declaresEffort,
       ["deepseek", "glm", "kimi", "minimax"].contains(where: { lid.contains($0) }) {
        return body   // self-reasoning family, catalog silent → emit nothing
    }
    if ctx.isXAI, !ctx.usesUnifiedReasoningEffort, ctx.declaresNoEffortTiers, !declaresEffort { return body }
    guard ctx.supportsReasoning != false else { return body }
    if !ctx.levelIsEnabled {
        if let offEffort, ctx.declaredEffortValues?.contains(offEffort) ?? true {
            body["reasoning_effort"] = offEffort
        }
        return body
    }
    fatalError("enabled tiers are out of scope for this test")
}

// MARK: - Port: describeOnce's modality gate + the message it builds

enum Part: Equatable { case text(String), imageData(bytes: Int, mime: String) }
struct VisionError: Error, Equatable { let reason: String }
func buildVisionRequest(providerDeclaresImageInput: Bool, imageBytes: Int, mime: String) throws -> [Part] {
    guard providerDeclaresImageInput else {
        throw VisionError(reason: "model does not accept image input (its catalog entry declares no image "
            + "modality), so the image could not be sent")
    }
    return [.text("Describe this image in detail…"), .imageData(bytes: imageBytes, mime: mime)]
}

/// The OpenAI serializer's user-part rule for `.imageData`: pixels iff the
/// model declares imageInput, else a text placeholder. (What the gate above
/// exists to pre-empt.)
func serializeUserPart(_ p: Part, modelDeclaresImageInput: Bool) -> String {
    switch p {
    case .text(let t): return "text:\(t.prefix(8))"
    case .imageData(let bytes, let mime):
        return modelDeclaresImageInput ? "image_url:\(mime):\(bytes)B" : "text:[Image attached but this model does not support vision input]"
    }
}

// MARK: - [1] image model + thinking off + catalog says "no off tier"

print("\n[1] image model, thinking off, authoritative tiers without an off value")
do {
    // fireworks' minimax-m3: reasoning_options effort values [low,medium,high]
    let ctx = Ctx(modelId: "minimax-m3", declaredEffortValues: ["low", "medium", "high"], effortDeclarationIsAuthoritative: true)
    let body = emitOff(ctx)
    check("no reasoning_effort in the body", body["reasoning_effort"] == nil)
    check("no thinking field of any kind", body.isEmpty)
    // …and the image is still on the wire.
    let parts = try! buildVisionRequest(providerDeclaresImageInput: true, imageBytes: 120_000, mime: "image/jpeg")
    check("image part present", parts.contains(.imageData(bytes: 120_000, mime: "image/jpeg")))
    checkEq("serialized as image_url, not placeholder text",
            serializeUserPart(parts[1], modelDeclaresImageInput: true), "image_url:image/jpeg:120000B")

    // Same catalog shape on a non-family id: the guard is about the catalog,
    // not the vendor family.
    let other = Ctx(modelId: "acme-reasoner-7", declaredEffortValues: ["low", "high"], effortDeclarationIsAuthoritative: true)
    check("non-family id with the same declaration is suppressed too", emitOff(other).isEmpty)
    // Case-insensitive on the declared values.
    let upper = Ctx(modelId: "minimax-m3", declaredEffortValues: ["LOW", "HIGH"], effortDeclarationIsAuthoritative: true)
    check("declared tiers compared case-insensitively", emitOff(upper).isEmpty)
    // Strict-enum families are suppressed regardless of declaration.
    let mimo = Ctx(modelId: "mimo-v2.5", declaredEffortValues: ["none", "low"], effortDeclarationIsAuthoritative: true)
    check("mimo/agnes never receive an off tier", emitOff(mimo).isEmpty)
}

// MARK: - [2] catalog documents an off tier → explicit off value is sent

print("\n[2] authoritative tiers that include an off value → explicit off sent")
do {
    // greenpt's glm-5.2 / kimi-k3 / minimax-m2.5: ["none","minimal","low","medium","high"]
    for id in ["glm-5.2", "kimi-k3", "minimax-m2.5"] {
        let ctx = Ctx(modelId: id, declaredEffortValues: ["none", "minimal", "low", "medium", "high"], effortDeclarationIsAuthoritative: true)
        checkEq("\(id): reasoning_effort=none", emitOff(ctx)["reasoning_effort"], "none")
    }
    // The off value must be the one this vendor documents: a model declaring
    // only "minimal" as its off tier gets nothing when our allowlist says "none"
    // (deliberately NOT clamped — clampEffort walks UP and would invert intent).
    let minimalOnly = Ctx(modelId: "glm-5.2", declaredEffortValues: ["minimal", "high"], effortDeclarationIsAuthoritative: true, offEffort: "none")
    check("off tier declared but not OUR off value → field omitted, never clamped upward",
          emitOff(minimalOnly).isEmpty)
    let seed = Ctx(modelId: "doubao-seed-2.1", declaredEffortValues: ["minimal", "low", "high"], effortDeclarationIsAuthoritative: true, offEffort: "minimal")
    checkEq("Ark/seed: its own off value 'minimal' is sent", emitOff(seed)["reasoning_effort"], "minimal")
    // No declaration at all on a non-family id: allowlist value passes through.
    let unknown = Ctx(modelId: "acme-reasoner-7", declaredEffortValues: nil)
    checkEq("no declaration → pass-through off value", emitOff(unknown)["reasoning_effort"], "none")
}

// MARK: - [3] the catalog, not the vendor guess, decides — and only an AUTHORITATIVE catalog

print("\n[3] provenance: catalog beats family name; fallback-vote catalog never suppresses")
do {
    // Family says "forced reasoning", catalog (authoritative) says an off tier exists → send it.
    let familyButOff = Ctx(modelId: "minimax-m3", declaredEffortValues: ["none", "high"], effortDeclarationIsAuthoritative: true)
    checkEq("minimax id + documented off tier → sent", emitOff(familyButOff)["reasoning_effort"], "none")
    // Custom relay: same tiers, but from the cross-provider vote → NOT
    // authoritative → the catalog guard does not fire. The field is then
    // omitted by the ordinary "our off value is not in the declared set" rule
    // (device-verified in 621b0f27c: "MiniMax-M3 on a CUSTOM instance: off ->
    // field omitted"). Either way no illegal enum reaches the relay.
    let relay = Ctx(modelId: "minimax-m3", declaredEffortValues: ["low", "medium", "high"], effortDeclarationIsAuthoritative: false)
    check("custom relay, non-authoritative vote: field omitted, no illegal off enum", emitOff(relay).isEmpty)
    // …but when that non-authoritative vote DOES list our off value, it is sent.
    let relayWithOff = Ctx(modelId: "minimax-m3", declaredEffortValues: ["none", "low", "high"], effortDeclarationIsAuthoritative: false)
    checkEq("custom relay whose vote lists 'none' → sent", emitOff(relayWithOff)["reasoning_effort"], "none")
    // Family id with NO declaration at all → the pre-existing family skip emits nothing.
    let silent = Ctx(modelId: "deepseek-v4-pro", declaredEffortValues: nil)
    check("family id, catalog silent → emit nothing (legacy self-reasoning skip)", emitOff(silent).isEmpty)
    // An EMPTY declaration is not a suppression by the catalog guard (it
    // needs a non-empty set), but `[]` still does not contain our off value,
    // so the ordinary declared-set rule omits the field.
    let emptyDecl = Ctx(modelId: "acme-reasoner-7", declaredEffortValues: [], effortDeclarationIsAuthoritative: true)
    check("empty declared list → field omitted by the declared-set rule", emitOff(emptyDecl).isEmpty)
    // A non-reasoning model never gets the field.
    let nonReasoning = Ctx(modelId: "acme-chat", supportsReasoning: false)
    check("supportsReasoning=false → nothing", emitOff(nonReasoning).isEmpty)

    // The image never depends on any of the above: the modality gate is the
    // only thing that can remove it, and it does so by THROWING, not by
    // silently substituting a placeholder.
    let ok = try? buildVisionRequest(providerDeclaresImageInput: true, imageBytes: 10, mime: "image/png")
    check("image kept when the provider model declares imageInput", ok?.count == 2)
    var thrown: VisionError? = nil
    do { _ = try buildVisionRequest(providerDeclaresImageInput: false, imageBytes: 10, mime: "image/png") }
    catch let e as VisionError { thrown = e } catch {}
    check("no imageInput → throws into the per-candidate failure path", thrown != nil)
    check("…with a reason naming the catalog modality", thrown?.reason.contains("declares no image") == true)
    checkEq("what the serializer WOULD have sent to that model (the silent drop being prevented)",
            serializeUserPart(.imageData(bytes: 10, mime: "image/png"), modelDeclaresImageInput: false),
            "text:[Image attached but this model does not support vision input]")
}

// MARK: - [4] shipping source cross-check

print("\n[4] shipping source cross-check")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let resolver = sourceOf("Providers/Thinking/ThinkingRuleResolver.swift")
let vision = sourceOf("Providers/VisionGroupResolver.swift")
let openai = sourceOf("Providers/OpenAI/OpenAIAgentProvider.swift")
let types = sourceOf("Providers/LLMTypes.swift")
check("ThinkingRuleResolver read", !resolver.isEmpty)
check("VisionGroupResolver read", !vision.isEmpty)
check("guard requires an authoritative declaration",
      resolver.contains("guard ctx.effortDeclarationIsAuthoritative else { return false }"))
check("guard checks the declared set against every off spelling",
      resolver.contains("return tiers.isDisjoint(with: [\"none\", \"off\", \"minimal\", \"disabled\"])"))
check("declared tiers are lower-cased before comparison",
      resolver.contains("let declaredTiers = ctx.declaredEffortValues.map { Set($0.map { $0.lowercased() }) }"))
check("off effort is nil'd by strict-enum OR the catalog guard, nothing else",
      resolver.contains("let offEffort = (strictEffortEnum || offTierNotDeclared) ? nil : ctx.offEffort"))
check("the family-name guard version is gone (no family list feeding offTierNotDeclared)", {
    guard let r = resolver.range(of: "let offTierNotDeclared: Bool = {"),
          let e = resolver.range(of: "}()", range: r.upperBound..<resolver.endIndex) else { return false }
    return !resolver[r.lowerBound..<e.upperBound].contains("minimax")
}())
check("off value is not clamped upward",
      resolver.contains("if let offEffort, ctx.declaredEffortValues?.contains(offEffort) ?? true {"))
check("provider passes provenance into the context",
      openai.contains("effortDeclarationIsAuthoritative: model.effortDeclarationIsAuthoritative ?? false,"))
check("LLMModel carries provenance as an Optional (synthesized Codable safety)",
      types.contains("var effortDeclarationIsAuthoritative: Bool?"))
check("Vision Group describe uses thinkingLevel .off", vision.contains("thinkingLevel: .off"))
check("describeOnce gates on the PROVIDER's model record",
      vision.contains("guard provider.model.capabilities.supportedModalities.contains(.imageInput) else {"))
check("…and throws rather than substituting a placeholder",
      vision.contains("throw VisionError.allCandidatesFailed(\n                \"model does not accept image input (its catalog entry declares no image \""))
check("a blind reply is treated as a failed candidate", vision.contains("if looksBlind(trimmed) {"))

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
