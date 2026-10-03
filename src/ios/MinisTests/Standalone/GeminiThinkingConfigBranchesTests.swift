// Tests for [T-gemini-thinking-config-branches] — round-2 item M15, iOS half.
//
// Pins d5d2aaba4 (3.7+ Flash off floors at "low", not "minimal"), 4b6121833
// (OpenMinis#226: TTS / image / embedding ids get NO thinking config and NO
// systemInstruction) and the three-way 2.5 branch that must never collapse by
// substring: "gemini-2.5-flash-lite".contains("2.5-flash") is TRUE, so the
// Flash branch has to exclude "lite" explicitly or Flash Lite receives a
// thinkingBudget it does not support.
//
// Port: ThinkingRuleResolver.geminiThinkingConfig + geminiDottedMinorVersion
// (Providers/Thinking/ThinkingRuleResolver.swift ~L712-790), verbatim.
// GeminiProvider.rejectsSystemInstruction (~L311) is pinned by source grep —
// it is keyed on the declared audioOutput modality, not on the id.
//
// Standalone (`swift GeminiThinkingConfigBranchesTests.swift`) like its neighbours.
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

// MARK: - Port

enum ThinkingLevel: String, CaseIterable { case off, low, medium, high, xhigh, max, ultra; var isEnabled: Bool { self != .off } }

func geminiDottedMinorVersion(of id: String) -> Int? {
    guard let range = id.range(of: #"gemini-\d+\.\d+"#, options: .regularExpression),
          let dot = id[range].lastIndex(of: ".") else { return nil }
    return Int(id[id.index(after: dot)..<range.upperBound])
}

func geminiThinkingConfig(modelId: String, level: ThinkingLevel) -> [String: Any] {
    let id = modelId.lowercased()
    let noThinkingSuffixes = ["-tts", "-image", "-embedding", "-vision"]
    if noThinkingSuffixes.contains(where: { id.hasSuffix($0) || id.contains("\($0)-") }) {
        return [:]
    }
    if level.isEnabled {
        if id.contains("gemini-3") {
            let geminiLevel: String = switch level {
            case .off: "minimal"
            case .low: "low"
            case .medium: "medium"
            case .high, .xhigh, .max, .ultra: "high"
            }
            return ["thinkingLevel": geminiLevel, "includeThoughts": true]
        }
        if id.contains("2.5-pro") {
            let budget: Int = switch level {
            case .off: 128
            case .low: 2048
            case .medium: 8192
            case .high: 16384
            case .xhigh, .max, .ultra: 32768
            }
            return ["thinkingBudget": budget, "includeThoughts": true]
        }
        if id.contains("2.5-flash") && !id.contains("lite") {
            let budget: Int = switch level {
            case .off: 0
            case .low: 1024
            case .medium: 4096
            case .high: 8192
            case .xhigh, .max, .ultra: 16384
            }
            return ["thinkingBudget": budget, "includeThoughts": true]
        }
        let budget: Int = switch level {
        case .off: 128
        case .low: 1024
        case .medium: 4096
        case .high: 8192
        case .xhigh, .max, .ultra: 16384
        }
        return ["thinkingBudget": budget, "includeThoughts": true]
    }
    if id.contains("gemini-3") {
        let flashAcceptsMinimal = id.contains("flash")
            && (geminiDottedMinorVersion(of: id).map { $0 < 7 } ?? true)
        return flashAcceptsMinimal ? ["thinkingLevel": "minimal"] : ["thinkingLevel": "low"]
    }
    if id.contains("2.5-pro") { return ["thinkingBudget": 128] }
    if id.contains("2.5-flash-lite") { return [:] }
    return ["thinkingBudget": 0]
}

/// The PRE-4b6121833 shape, for contrast: the specialized-id test lived at the
/// bottom of the off branch, below the family branches.
func preFixOffConfig(modelId: String) -> [String: Any] {
    let id = modelId.lowercased()
    if id.contains("gemini-3") { return ["thinkingLevel": id.contains("flash") ? "minimal" : "low"] }
    if id.contains("2.5-pro") { return ["thinkingBudget": 128] }
    if id.contains("2.5-flash-lite") { return [:] }
    if id.hasSuffix("-tts") { return [:] }
    return ["thinkingBudget": 0]
}

print("▶️  1. the 2.5 three-way branch does not collapse by substring")
do {
    check("the trap is real: \"gemini-2.5-flash-lite\" contains \"2.5-flash\"", "gemini-2.5-flash-lite".contains("2.5-flash"))
    checkEq("2.5-flash-lite off → no thinkingConfig at all", json(geminiThinkingConfig(modelId: "gemini-2.5-flash-lite", level: .off)), "{}")
    checkEq("2.5-flash off → thinkingBudget 0 (disables)", json(geminiThinkingConfig(modelId: "gemini-2.5-flash", level: .off)), #"{"thinkingBudget":0}"#)
    checkEq("2.5-pro off → floor 128 (cannot disable)", json(geminiThinkingConfig(modelId: "gemini-2.5-pro", level: .off)), #"{"thinkingBudget":128}"#)
    checkEq("2.5-flash high → 8192", geminiThinkingConfig(modelId: "gemini-2.5-flash", level: .high)["thinkingBudget"] as? Int, 8192)
    checkEq("2.5-pro high → 16384 (a different table, not the Flash one)", geminiThinkingConfig(modelId: "gemini-2.5-pro", level: .high)["thinkingBudget"] as? Int, 16384)
    // Flash Lite with thinking ON does not take the Flash table either — it falls
    // to the conservative unknown-model table, whose floor is never 0.
    checkEq("2.5-flash-lite high → unknown-model table (8192), not the Flash table", geminiThinkingConfig(modelId: "gemini-2.5-flash-lite", level: .high)["thinkingBudget"] as? Int, 8192)
    checkEq("2.5-flash-lite low → 1024, never 0", geminiThinkingConfig(modelId: "gemini-2.5-flash-lite", level: .low)["thinkingBudget"] as? Int, 1024)
    check("preview suffixes ride the same branch", json(geminiThinkingConfig(modelId: "gemini-2.5-flash-lite-preview-06-17", level: .off)) == "{}"
          && geminiThinkingConfig(modelId: "gemini-2.5-flash-preview-05-20", level: .off)["thinkingBudget"] as? Int == 0)
    check("case-insensitive", json(geminiThinkingConfig(modelId: "Gemini-2.5-Flash-Lite", level: .off)) == "{}")
    // A never-seen id takes the conservative table: 128 floor when on, 0 when off.
    checkEq("unknown id (gemini-4-flash) on → budget table with includeThoughts", json(geminiThinkingConfig(modelId: "gemini-4-flash", level: .medium)), #"{"includeThoughts":true,"thinkingBudget":4096}"#)
    checkEq("unknown id off → thinkingBudget 0", json(geminiThinkingConfig(modelId: "gemini-4-flash", level: .off)), #"{"thinkingBudget":0}"#)
}

print("▶️  2. gemini-3.x: thinkingLevel string; 3.7+ Flash off floors at \"low\" (d5d2aaba4)")
do {
    checkEq("gemini-3-pro on → thinkingLevel + includeThoughts", json(geminiThinkingConfig(modelId: "gemini-3-pro-preview", level: .high)), #"{"includeThoughts":true,"thinkingLevel":"high"}"#)
    checkEq("xhigh/max/ultra all map to \"high\" on Gemini", ThinkingLevel.allCases.filter { [.xhigh, .max, .ultra].contains($0) }.map { geminiThinkingConfig(modelId: "gemini-3.5-flash", level: $0)["thinkingLevel"] as? String }, ["high", "high", "high"])
    checkEq("gemini-3-pro off → low (Pro cannot disable)", json(geminiThinkingConfig(modelId: "gemini-3-pro-preview", level: .off)), #"{"thinkingLevel":"low"}"#)
    checkEq("unversioned gemini-3-flash-preview off → minimal (pinned by the golden snapshot)", json(geminiThinkingConfig(modelId: "gemini-3-flash-preview", level: .off)), #"{"thinkingLevel":"minimal"}"#)
    checkEq("gemini-3.5-flash off → minimal", geminiThinkingConfig(modelId: "gemini-3.5-flash", level: .off)["thinkingLevel"] as? String, "minimal")
    checkEq("gemini-3.6-flash off → minimal (last generation that accepts it)", geminiThinkingConfig(modelId: "gemini-3.6-flash", level: .off)["thinkingLevel"] as? String, "minimal")
    checkEq("gemini-3.7-flash off → low (400 on minimal)", json(geminiThinkingConfig(modelId: "gemini-3.7-flash", level: .off)), #"{"thinkingLevel":"low"}"#)
    checkEq("gemini-3.7-flash-preview-09-2026 off → low (dated suffix does not confuse the minor parse)", geminiThinkingConfig(modelId: "gemini-3.7-flash-preview-09-2026", level: .off)["thinkingLevel"] as? String, "low")
    checkEq("gemini-3.8-flash off → low (threshold, not an exact-id special case)", geminiThinkingConfig(modelId: "gemini-3.8-flash", level: .off)["thinkingLevel"] as? String, "low")
    checkEq("gemini-3.10-flash off → low (two-digit minor parses as 10)", geminiThinkingConfig(modelId: "gemini-3.10-flash", level: .off)["thinkingLevel"] as? String, "low")
    checkEq("gemini-3.7-pro off → low (Pro never got minimal)", geminiThinkingConfig(modelId: "gemini-3.7-pro", level: .off)["thinkingLevel"] as? String, "low")
    checkEq("3.7 Flash ON is unchanged: low → low", geminiThinkingConfig(modelId: "gemini-3.7-flash", level: .low)["thinkingLevel"] as? String, "low")
    checkEq("minor parser: dotted → Int", geminiDottedMinorVersion(of: "gemini-3.7-flash"), 7)
    checkEq("minor parser: undotted → nil", geminiDottedMinorVersion(of: "gemini-3-flash-preview"), nil)
    checkEq("minor parser: FIRST occurrence wins", geminiDottedMinorVersion(of: "models/gemini-3.6-flash-vs-gemini-3.7"), 6)
}

print("▶️  3. TTS / image / embedding ids: no thinking at any level, no systemInstruction (4b6121833, #226)")
do {
    let specialized = ["gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts",
                       "gemini-3-pro-image-preview", "gemini-2.5-flash-image", "gemini-embedding-001", "gemini-embedding-exp-03-07"]
    for id in specialized {
        check("\(id): empty at EVERY level", ThinkingLevel.allCases.allSatisfy { geminiThinkingConfig(modelId: id, level: $0).isEmpty })
    }
    // Why the hoist mattered: the tts id also matches the gemini-3 family branch.
    checkEq("PRE-FIX: gemini-3.1-flash-tts-preview off → thinkingLevel minimal (the 400)", json(preFixOffConfig(modelId: "gemini-3.1-flash-tts-preview")), #"{"thinkingLevel":"minimal"}"#)
    checkEq("PRE-FIX: gemini-2.5-pro-preview-tts off → thinkingBudget 128 (the other 400)", json(preFixOffConfig(modelId: "gemini-2.5-pro-preview-tts")), #"{"thinkingBudget":128}"#)
    check("the suffix test is anchored: \"-tts\" as suffix or as \"-tts-\" segment only",
          geminiThinkingConfig(modelId: "gemini-2.5-flash-ttsx", level: .off).isEmpty == false)
    check("a text model whose name merely contains \"vision\" without the dash form still thinks",
          !geminiThinkingConfig(modelId: "gemini-2.5-flash-visionary", level: .off).isEmpty)
    // GeminiProvider drops systemInstruction on audio-output models — keyed on the
    // declared modality, not on the id suffix.
    let gp = source("Providers/Gemini/GeminiProvider.swift")
    if gp.isEmpty { print("  ⏭  GeminiProvider not readable") } else {
        check("rejectsSystemInstruction is keyed on the audioOutput modality", gp.contains("model.capabilities.supportedModalities.contains(.audioOutput)"))
        check("systemInstruction is gated on it in the request builder", gp.contains("if let sys = systemPrompt, !sys.isEmpty, !rejectsSystemInstruction {"))
        check("both thinking builders route through the resolver (one place decides)", gp.components(separatedBy: "ThinkingRuleResolver.geminiThinkingConfig(modelId: model.id").count - 1 == 2)
    }
}

print("▶️  4. shipping source still carries the pinned lines")
do {
    let res = source("Providers/Thinking/ThinkingRuleResolver.swift")
    if res.isEmpty { print("  ⏭  resolver not readable") } else {
        check("specialized-id test runs FIRST, above level.isEnabled", res.range(of: "let noThinkingSuffixes = [\"-tts\", \"-image\", \"-embedding\", \"-vision\"]")!.lowerBound < res.range(of: "if level.isEnabled {\n            if id.contains(\"gemini-3\")")!.lowerBound)
        check("Flash branch excludes lite explicitly", res.contains("if id.contains(\"2.5-flash\") && !id.contains(\"lite\") {"))
        check("Flash Lite off → empty", res.contains("if id.contains(\"2.5-flash-lite\") { return [:] }"))
        check("3.7+ Flash floor uses the version threshold, not an id list", res.contains("&& (Self.geminiDottedMinorVersion(of: id).map { $0 < 7 } ?? true)"))
        check("minimal vs low decided by flashAcceptsMinimal", res.contains("return flashAcceptsMinimal ? [\"thinkingLevel\": \"minimal\"] : [\"thinkingLevel\": \"low\"]"))
        check("2.5-pro off floor is 128", res.contains("if id.contains(\"2.5-pro\") { return [\"thinkingBudget\": 128] }"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
