// Tests for [T-model-custom-params] — ModelOverrides gains temperature, topP,
// customHeaders and extraBodyParams (phase one: storage + Codable only).
//
// The point of these is backward compatibility, which is the whole risk of an
// additive Codable change: an old backup or a config synced from an older build
// must decode without throwing, and an entry with none of the new fields set
// must re-encode byte-identically so the change produces no diff churn in
// provider_config.json and no LWW noise in sync.
//
// Standalone (`swift ModelOverridesCodableTests.swift`) for the same reason as
// the neighbouring files: the MinisTests target has a pre-existing compile
// break. The Codable surface is reproduced here; section [6] re-reads the
// shipping source so the copy cannot drift.

import Foundation

// Mirrors the shipped ModelOverrides Codable surface.
enum ThinkingLevel: String, Codable { case off, low, medium, high, xhigh
    static func decoded(_ r: String) -> ThinkingLevel? { ThinkingLevel(rawValue: r) } }
struct ModelModality: Codable, Hashable { var raw: String }

struct ModelOverrides: Codable, Hashable {
    var displayName: String?; var maxOutputTokens: Int?
    var modalityOverride: ModelModality?; var contextWindow: Int?
    var supportsReasoning: Bool?; var maxThinkingLevel: ThinkingLevel?
    var temperature: Double?; var topP: Double?
    var customHeaders: [String: String]?; var extraBodyParams: [String: String]?

    var isEmpty: Bool {
        displayName == nil && maxOutputTokens == nil && modalityOverride == nil
            && contextWindow == nil && supportsReasoning == nil && maxThinkingLevel == nil
            && temperature == nil && topP == nil && customHeaders == nil && extraBodyParams == nil
    }
    private enum CodingKeys: String, CodingKey {
        case displayName, maxOutputTokens, modalityOverride, contextWindow, supportsReasoning, maxThinkingLevel
        case temperature, topP, customHeaders, extraBodyParams
    }
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName)
        maxOutputTokens = try c.decodeIfPresent(Int.self, forKey: .maxOutputTokens)
        modalityOverride = try c.decodeIfPresent(ModelModality.self, forKey: .modalityOverride)
        contextWindow = try c.decodeIfPresent(Int.self, forKey: .contextWindow)
        supportsReasoning = try c.decodeIfPresent(Bool.self, forKey: .supportsReasoning)
        if let r = try c.decodeIfPresent(String.self, forKey: .maxThinkingLevel) { maxThinkingLevel = ThinkingLevel.decoded(r) }
        temperature = try c.decodeIfPresent(Double.self, forKey: .temperature)
        topP = try c.decodeIfPresent(Double.self, forKey: .topP)
        customHeaders = try c.decodeIfPresent([String: String].self, forKey: .customHeaders)
        extraBodyParams = try c.decodeIfPresent([String: String].self, forKey: .extraBodyParams)
    }
    func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(displayName, forKey: .displayName)
        try c.encodeIfPresent(maxOutputTokens, forKey: .maxOutputTokens)
        try c.encodeIfPresent(modalityOverride, forKey: .modalityOverride)
        try c.encodeIfPresent(contextWindow, forKey: .contextWindow)
        try c.encodeIfPresent(supportsReasoning, forKey: .supportsReasoning)
        try c.encodeIfPresent(maxThinkingLevel?.rawValue, forKey: .maxThinkingLevel)
        try c.encodeIfPresent(temperature, forKey: .temperature)
        try c.encodeIfPresent(topP, forKey: .topP)
        try c.encodeIfPresent(customHeaders, forKey: .customHeaders)
        try c.encodeIfPresent(extraBodyParams, forKey: .extraBodyParams)
    }
}

var fail = 0
func ck(_ l: String, _ ok: Bool) { print(ok ? "  ✅ \(l)" : "  ❌ \(l)"); if !ok { fail += 1 } }

let enc = JSONEncoder(); enc.outputFormatting = .sortedKeys
let dec = JSONDecoder()

print("\n[1] OLD payload (no new keys) still decodes")
let old = #"{"displayName":"My Model","maxOutputTokens":4096,"supportsReasoning":true,"maxThinkingLevel":"high"}"#
let o = try! dec.decode(ModelOverrides.self, from: old.data(using: .utf8)!)
ck("decoded without throwing", true)
ck("existing fields intact", o.displayName == "My Model" && o.maxOutputTokens == 4096 && o.supportsReasoning == true)
ck("maxThinkingLevel intact", o.maxThinkingLevel == .high)
ck("new fields are nil", o.temperature == nil && o.topP == nil && o.customHeaders == nil && o.extraBodyParams == nil)

print("\n[2] Unset entry re-encodes byte-identically to before the change")
var empty = ModelOverrides()
ck("isEmpty true", empty.isEmpty)
ck("encodes to {}", String(data: try! enc.encode(empty), encoding: .utf8)! == "{}")
empty.displayName = "X"
ck("one old field only → no new keys emitted",
   String(data: try! enc.encode(empty), encoding: .utf8)! == #"{"displayName":"X"}"#)

print("\n[3] isEmpty accounts for every new field")
for (name, mutate) in [("temperature", { (m: inout ModelOverrides) in m.temperature = 0.7 }),
                       ("topP", { m in m.topP = 0.9 }),
                       ("customHeaders", { m in m.customHeaders = ["X": "1"] }),
                       ("extraBodyParams", { m in m.extraBodyParams = ["k": "v"] })] {
    var m = ModelOverrides(); mutate(&m)
    ck("\(name) alone makes isEmpty false", !m.isEmpty)
}
// Edge: a deliberately-empty dictionary is NOT the same as unset.
var edge = ModelOverrides(); edge.customHeaders = [:]
ck("empty dict is still 'set' (distinguishable from nil)", !edge.isEmpty)

print("\n[4] Round-trip with every field populated")
var full = ModelOverrides()
full.displayName = "N"; full.maxOutputTokens = 1; full.contextWindow = 2
full.supportsReasoning = false; full.maxThinkingLevel = .low
full.temperature = 0.25; full.topP = 0.8
full.customHeaders = ["A": "1", "B": "2"]; full.extraBodyParams = ["p": "q"]
let rt = try! dec.decode(ModelOverrides.self, from: try! enc.encode(full))
ck("round-trips equal", rt == full)
ck("temperature preserved exactly", rt.temperature == 0.25)
ck("headers preserved", rt.customHeaders == ["A": "1", "B": "2"])

print("\n[5] New payload read by code that ignores the new keys still works")
// (forward-compat: an OLDER build decoding a NEWER blob)
struct OldShape: Codable { var displayName: String?; var maxOutputTokens: Int? }
let newBlob = try! enc.encode(full)
let asOld = try! dec.decode(OldShape.self, from: newBlob)
ck("old decoder tolerates unknown keys", asOld.displayName == "N" && asOld.maxOutputTokens == 1)

print("\n[6] Shipping source matches these assumptions")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let src = source("Providers/ModelEntry.swift")
if src.isEmpty {
    print("  ⏭  source not readable from this sandbox")
} else {
    for f in ["var temperature: Double?", "var topP: Double?",
              "var customHeaders: [String: String]?", "var extraBodyParams: [String: String]?"] {
        ck("field declared: \(f)", src.contains(f))
    }
    ck("isEmpty covers all four",
       src.contains("&& temperature == nil") && src.contains("&& topP == nil")
       && src.contains("&& customHeaders == nil") && src.contains("&& extraBodyParams == nil"))
    ck("CodingKeys extended", src.contains("case temperature, topP, customHeaders, extraBodyParams"))
    ck("decode uses decodeIfPresent for all four",
       src.contains("decodeIfPresent(Double.self, forKey: .temperature)")
       && src.contains("decodeIfPresent(Double.self, forKey: .topP)")
       && src.contains("decodeIfPresent([String: String].self, forKey: .customHeaders)")
       && src.contains("decodeIfPresent([String: String].self, forKey: .extraBodyParams)"))
    ck("encode uses encodeIfPresent for all four",
       src.contains("encodeIfPresent(temperature, forKey: .temperature)")
       && src.contains("encodeIfPresent(topP, forKey: .topP)")
       && src.contains("encodeIfPresent(customHeaders, forKey: .customHeaders)")
       && src.contains("encodeIfPresent(extraBodyParams, forKey: .extraBodyParams)"))
    ck("memberwise init extended so existing callers still compile",
       src.contains("temperature: Double? = nil") && src.contains("extraBodyParams: [String: String]? = nil"))
}

// ───────────────────────────────────────────────────────────────────────────
// [M12] provider export → import round trip
//
// Two different serializers carry ModelOverrides, and only ONE of them is the
// Codable surface exercised above:
//
//   * BACKUP / iCloud sync go through Codable (sections [1]-[5]), so a new field
//     rides along the moment it is declared.
//   * `provider.export` / `provider.import`
//     (ProviderConfigStore.exportInstanceJSON ~1143-1170 and the import block
//     ~1394-1411) build plain `[String: Any]` dictionaries and enumerate every
//     key BY HAND. A field absent from those two lists is silently dropped.
//
// That hand-enumeration is exactly the bug 939a913b1 fixed: export/import only
// carried displayName and maxOutputTokens, so modalityOverride, contextWindow
// and supportsReasoning — a hand-corrected proxied model's whole point — were
// lost on round trip. 6c43ed30c then added the four custom-param fields to the
// same two lists. Since the failure mode is "a field you added elsewhere just
// vanishes", these cases enumerate the fields independently of the production
// lists and diff the two.

print("\n[7] provider export → import carries the full overrides layer")

// The real `ModelModality` is an OptionSet whose rawValue is an Int, and the
// export encodes exactly that Int. The Codable stub above models it as an opaque
// string, so this section uses the wire shape directly.
func modalityWire(_ m: ModelModality?) -> Int? { m.flatMap { Int($0.raw) } }
func modalityFromWire(_ i: Int?) -> ModelModality? { i.map { ModelModality(raw: String($0)) } }
func mod(_ i: Int) -> ModelModality { ModelModality(raw: String(i)) }

/// Verbatim from exportInstanceJSON's `if !entry.overrides.isEmpty` block.
func exportOverrides(_ o: ModelOverrides) -> [String: Any]? {
    guard !o.isEmpty else { return nil }
    var d: [String: Any] = [:]
    if let dn = o.displayName { d["displayName"] = dn }
    if let mt = o.maxOutputTokens { d["maxOutputTokens"] = mt }
    if let m = o.modalityOverride, let wire = modalityWire(m) { d["modalityOverride"] = wire }
    if let ctx = o.contextWindow { d["contextWindow"] = ctx }
    if let sr = o.supportsReasoning { d["supportsReasoning"] = sr }
    if let mtl = o.maxThinkingLevel { d["maxThinkingLevel"] = mtl.rawValue }
    if let temp = o.temperature { d["temperature"] = temp }
    if let tp = o.topP { d["topP"] = tp }
    if let ch = o.customHeaders, !ch.isEmpty { d["customHeaders"] = ch }
    if let eb = o.extraBodyParams, !eb.isEmpty { d["extraBodyParams"] = eb }
    return d
}

/// Verbatim from the import block.
func importOverrides(_ d: [String: Any]?) -> ModelOverrides {
    var o = ModelOverrides()
    guard let d else { return o }
    o.displayName = d["displayName"] as? String
    o.maxOutputTokens = d["maxOutputTokens"] as? Int
    o.modalityOverride = modalityFromWire(d["modalityOverride"] as? Int)
    o.contextWindow = d["contextWindow"] as? Int
    o.supportsReasoning = d["supportsReasoning"] as? Bool
    o.maxThinkingLevel = (d["maxThinkingLevel"] as? String)
        .flatMap { ThinkingLevel(rawValue: $0.lowercased()) }
    o.temperature = d["temperature"] as? Double
    o.topP = d["topP"] as? Double
    o.customHeaders = d["customHeaders"] as? [String: String]
    o.extraBodyParams = d["extraBodyParams"] as? [String: String]
    return o
}

/// A real round trip: through JSON text, as the share sheet actually does.
func exportImport(_ o: ModelOverrides) -> ModelOverrides {
    guard let dict = exportOverrides(o) else { return importOverrides(nil) }
    let data = try! JSONSerialization.data(withJSONObject: dict)
    let back = try! JSONSerialization.jsonObject(with: data) as? [String: Any]
    return importOverrides(back)
}

var exported = ModelOverrides()
exported.displayName = "Hand-corrected GLM"
exported.maxOutputTokens = 32_768
exported.modalityOverride = mod(0b1011)
exported.contextWindow = 200_000
exported.supportsReasoning = true
exported.maxThinkingLevel = .high
exported.temperature = 0.35
exported.topP = 0.95
exported.customHeaders = ["X-Relay-Tenant": "acme", "X-Trace": "on"]
exported.extraBodyParams = ["enable_thinking": "true", "seed": "7"]

let roundTripped = exportImport(exported)
ck("displayName survives", roundTripped.displayName == exported.displayName)
ck("maxOutputTokens survives", roundTripped.maxOutputTokens == exported.maxOutputTokens)
ck("modalityOverride survives (Int rawValue encoding)",
   roundTripped.modalityOverride == exported.modalityOverride)
ck("contextWindow survives", roundTripped.contextWindow == exported.contextWindow)
ck("supportsReasoning survives", roundTripped.supportsReasoning == exported.supportsReasoning)
ck("maxThinkingLevel survives (lowercase level id)", roundTripped.maxThinkingLevel == .high)
ck("temperature survives exactly", roundTripped.temperature == 0.35)
ck("topP survives exactly", roundTripped.topP == 0.95)
ck("customHeaders survive, all keys", roundTripped.customHeaders == exported.customHeaders)
ck("extraBodyParams survive, all keys", roundTripped.extraBodyParams == exported.extraBodyParams)

print("\n[8] Partial and empty override objects restore exactly what was present")

// Each key is read independently with `as?`, so a partial object must not
// resurrect a neighbouring field or throw.
for (name, mutate) in [("modalityOverride only", { (m: inout ModelOverrides) in m.modalityOverride = mod(3) }),
                       ("contextWindow only", { m in m.contextWindow = 1_000_000 }),
                       ("supportsReasoning=false only", { m in m.supportsReasoning = false }),
                       ("maxThinkingLevel only", { m in m.maxThinkingLevel = .low }),
                       ("temperature only", { m in m.temperature = 0 }),
                       ("extraBodyParams only", { m in m.extraBodyParams = ["k": "v"] })] {
    var only = ModelOverrides(); mutate(&only)
    ck("\(name): round-trips equal", exportImport(only) == only)
}
// An entry with NO overrides emits no "overrides" key at all, and imports empty.
ck("an unmodified entry exports no overrides object", exportOverrides(ModelOverrides()) == nil)
ck("…and imports as empty", importOverrides(nil).isEmpty)
// supportsReasoning = false must survive as false, not be lost to a `?? nil`.
var falseOnly = ModelOverrides(); falseOnly.supportsReasoning = false
ck("an explicit false is not confused with absent",
   exportImport(falseOnly).supportsReasoning == false)
// temperature = 0 likewise (a legitimate value, not a sentinel).
var zeroTemp = ModelOverrides(); zeroTemp.temperature = 0
ck("temperature 0 survives as 0, not nil", exportImport(zeroTemp).temperature == 0)
// An EMPTY dictionary is dropped by export on purpose (`!ch.isEmpty`), so it
// comes back nil rather than [:]. Pinned so the asymmetry is deliberate.
var emptyDicts = ModelOverrides()
emptyDicts.customHeaders = [:]; emptyDicts.extraBodyParams = [:]
emptyDicts.displayName = "keep"
let afterEmpty = exportImport(emptyDicts)
ck("an empty customHeaders dict is not exported, so it returns nil",
   afterEmpty.customHeaders == nil)
ck("…same for extraBodyParams", afterEmpty.extraBodyParams == nil)
ck("…and the sibling field is unaffected", afterEmpty.displayName == "keep")
// Unknown keys in an export written by a NEWER build must be ignored, not fatal.
var future = exportOverrides(exported)!
future["someFutureField"] = ["nested": true]
ck("an unknown key in the export is ignored by this build's importer",
   importOverrides(future).displayName == exported.displayName)

print("\n[9] Field coverage: what the two hand-written lists actually carry")

// The failure this guards is structural: a field added to ModelOverrides and to
// Codable but NOT to the two dictionary lists. So enumerate the fields here,
// independently, and diff.
let allOverrideFields = ["displayName", "maxOutputTokens", "modalityOverride", "contextWindow",
                         "supportsReasoning", "maxThinkingLevel",
                         "temperature", "topP", "customHeaders", "extraBodyParams"]
let exportedKeys = Set(exportOverrides(exported)!.keys)
let carried = allOverrideFields.filter { exportedKeys.contains($0) }
let dropped = allOverrideFields.filter { !exportedKeys.contains($0) }
ck("ALL ten fields are carried by provider export", carried.count == allOverrideFields.count)
ck("no field is dropped", dropped.isEmpty)

// `maxThinkingLevel` is a full member of ModelOverrides (ModelEntry.swift:29),
// rides Codable (so BACKUP and iCloud sync carry it), and is the user's thinking
// CEILING for that model (`overrides.maxThinkingLevel ?? model.catalogMaxThinkingLevel`,
// ModelEntry.swift:283). It used to be absent from BOTH hand-written lists in
// exportInstanceJSON and the import block, so sharing a provider silently reset
// the recipient's per-model thinking ceiling to the catalog default — the same
// shape as the bug 939a913b1 fixed for the other three fields. Now carried; this
// is the regression fence.
var withCeiling = ModelOverrides()
withCeiling.displayName = "Ceiling model"
withCeiling.maxThinkingLevel = .low
let ceilingRT = exportImport(withCeiling)
ck("maxThinkingLevel survives provider export/import", ceilingRT.maxThinkingLevel == .low)
// The wire value is the lowercase level id, the same token Codable/backup write,
// so a payload written by either path is readable by the other.
ck("the wire value is the lowercase level id",
   exportOverrides(withCeiling)?["maxThinkingLevel"] as? String == "low")
// An Android export spells its enum names in upper case ("XHIGH"); the importer
// lower-cases before matching so a cross-platform share is not silently dropped.
ck("an upper-case (Android) level id is still read",
   importOverrides(["maxThinkingLevel": "XHIGH"]).maxThinkingLevel == .xhigh)
// A level a NEWER build invented must stay nil — "inherit the catalog rule" —
// rather than being clamped to some in-range level.
ck("an unknown level id imports as nil, not a guess",
   importOverrides(["maxThinkingLevel": "hyper"]).maxThinkingLevel == nil)
ck("an absent key imports as nil", importOverrides(["displayName": "x"]).maxThinkingLevel == nil)
ck("…while the sibling field in the same object does survive",
   ceilingRT.displayName == "Ceiling model")
// The Codable path, by contrast, carries it — which is what makes the gap easy
// to miss in review.
var ceilCodable = ModelOverrides(); ceilCodable.maxThinkingLevel = .low
ck("backup/sync (Codable) DO carry maxThinkingLevel",
   (try! dec.decode(ModelOverrides.self, from: try! enc.encode(ceilCodable))).maxThinkingLevel == .low)

print("\n[10] Export/import source invariants")

let storeSrc = source("Providers/ProviderConfigStore.swift")
if storeSrc.isEmpty {
    print("  ⏭  ProviderConfigStore not readable from this sandbox")
} else {
    // Export side: each key, written by hand.
    for f in ["o[\"displayName\"] = dn", "o[\"maxOutputTokens\"] = mt",
              "o[\"modalityOverride\"] = mod.rawValue", "o[\"contextWindow\"] = ctx",
              "o[\"supportsReasoning\"] = sr", "o[\"maxThinkingLevel\"] = mtl.rawValue",
              "o[\"temperature\"] = temp",
              "o[\"topP\"] = tp", "o[\"customHeaders\"] = ch", "o[\"extraBodyParams\"] = eb"] {
        ck("export writes \(f)", storeSrc.contains(f))
    }
    ck("export skips the whole object when there are no overrides",
       storeSrc.contains("if !entry.overrides.isEmpty {"))
    ck("empty dictionaries are deliberately not exported",
       storeSrc.contains("if let ch = entry.overrides.customHeaders, !ch.isEmpty")
       && storeSrc.contains("if let eb = entry.overrides.extraBodyParams, !eb.isEmpty"))
    // Import side: each key read independently, so a partial object is fine.
    for f in ["overrides.displayName = o[\"displayName\"] as? String",
              "overrides.maxOutputTokens = o[\"maxOutputTokens\"] as? Int",
              "overrides.contextWindow = o[\"contextWindow\"] as? Int",
              "overrides.supportsReasoning = o[\"supportsReasoning\"] as? Bool",
              "overrides.temperature = o[\"temperature\"] as? Double",
              "overrides.topP = o[\"topP\"] as? Double",
              "overrides.customHeaders = o[\"customHeaders\"] as? [String: String]",
              "overrides.extraBodyParams = o[\"extraBodyParams\"] as? [String: String]"] {
        ck("import reads \(f.prefix(46))…", storeSrc.contains(f))
    }
    ck("modalityOverride is decoded from its Int rawValue",
       storeSrc.contains("overrides.modalityOverride = (o[\"modalityOverride\"] as? Int).map { ModelModality(rawValue: $0) }"))
    // The two lists must name the SAME keys, or one side is dropping a field.
    // Scoped to each block rather than the whole file, since `o["…"]` appears
    // elsewhere too.
    func keys(in block: Substring, pattern: String) -> Set<String> {
        var out = Set<String>()
        var rest = block
        while let r = rest.range(of: pattern) {
            rest = rest[r.upperBound...]
            if let close = rest.firstIndex(of: "\"") { out.insert(String(rest[rest.startIndex..<close])) }
        }
        return out
    }
    let exportBlock: Substring = {
        guard let a = storeSrc.range(of: "if !entry.overrides.isEmpty {"),
              let b = storeSrc.range(of: "m[\"overrides\"] = o") else { return "" }
        return storeSrc[a.upperBound..<b.lowerBound]
    }()
    let importBlock: Substring = {
        guard let a = storeSrc.range(of: "if let o = m[\"overrides\"] as? [String: Any] {"),
              let b = storeSrc.range(of: "let entry = ModelEntry(") else { return "" }
        return storeSrc[a.upperBound..<b.lowerBound]
    }()
    let exportKeys = keys(in: exportBlock, pattern: "o[\"")
    // `modalityOverride` is read as `= (o["…"] as? Int)`, so match the bracket
    // access itself rather than the assignment shape.
    let importKeys = keys(in: importBlock, pattern: "o[\"")
    ck("the export block was located", !exportKeys.isEmpty)
    ck("the import block was located", !importKeys.isEmpty)
    ck("every key the export writes is read back by the import",
       exportKeys.subtracting(importKeys).isEmpty)
    ck("…and the import reads nothing the export never writes",
       importKeys.subtracting(exportKeys).isEmpty)
    ck("both lists carry all ten expected fields", exportKeys == Set(allOverrideFields))
    ck("the thinking ceiling is read back as a level id, unknown values staying nil",
       storeSrc.contains("ThinkingLevel(rawValue: $0.lowercased())"))
    // The note that records WHY hand-enumeration is dangerous must survive.
    ck("the hand-enumeration hazard is still documented",
       storeSrc.contains("serializer enumerates keys BY HAND, so a new field is"))
    ck("[T-provider-export-model-overrides] tag still present",
       storeSrc.contains("[T-provider-export-model-overrides]"))
}

print("\n\(fail == 0 ? "✅ ALL PASSED" : "❌ \(fail) FAILURE(S)")")
exit(fail == 0 ? 0 : 1)
