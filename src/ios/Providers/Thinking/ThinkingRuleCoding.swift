import Foundation

/// Persistence encoding for `ThinkingWireFormat` / `ThinkingRule`.
///
/// [T-thinking-rules-phase2] Written by hand rather than synthesised, deliberately.
/// Synthesised enum Codable keys off the CASE DECLARATION ORDER, so reordering cases —
/// an ordinary-looking refactor — silently reinterprets every persisted row as a
/// different format. This encoding is keyed by an explicit stable `kind` string, so
/// cases can be added or reordered freely and old rows keep their meaning.
///
/// Unknown `kind` values decode to nil rather than throwing: a rule written by a NEWER
/// build must not make an older build fail to read the whole table. The caller drops
/// such rows (and logs), matching how `ProviderType.decoded` degrades an unknown
/// provider type to `.unsupported` instead of losing the instance.
extension ThinkingWireFormat {

    private enum Kind: String {
        case omitEverything
        case reasoningEffort
        case reasoningEffortNested
        case deepSeekSibling
        case qwenDual
        case qwenRootOnly
        case anthropicThinking
        case geminiBudget
        case geminiThinkingLevel
        case booleanToggle
        case extraBodyToggle
        case customPath
    }

    /// JSON-object form, suitable for a TEXT column.
    var persistedJSON: [String: Any] {
        switch self {
        case .omitEverything:
            return ["kind": Kind.omitEverything.rawValue]
        case .reasoningEffort(let offValue):
            return ["kind": Kind.reasoningEffort.rawValue, "offValue": offValue as Any]
        case .reasoningEffortNested(let offValue):
            return ["kind": Kind.reasoningEffortNested.rawValue, "offValue": offValue as Any]
        case .deepSeekSibling:
            return ["kind": Kind.deepSeekSibling.rawValue]
        case .qwenDual:
            return ["kind": Kind.qwenDual.rawValue]
        case .qwenRootOnly:
            return ["kind": Kind.qwenRootOnly.rawValue]
        case .anthropicThinking(let style):
            return ["kind": Kind.anthropicThinking.rawValue,
                    "style": style == .adaptive ? "adaptive" : "budgetTokens"]
        case .geminiBudget(let floor, let canDisable):
            return ["kind": Kind.geminiBudget.rawValue, "floor": floor, "canDisable": canDisable]
        case .geminiThinkingLevel:
            return ["kind": Kind.geminiThinkingLevel.rawValue]
        case .booleanToggle(let path):
            return ["kind": Kind.booleanToggle.rawValue, "path": path]
        case .extraBodyToggle(let path):
            return ["kind": Kind.extraBodyToggle.rawValue, "path": path]
        case .customPath(let path, let values, let offValue):
            var map: [String: String] = [:]
            for (lvl, v) in values { map[lvl.rawValue] = v }
            return ["kind": Kind.customPath.rawValue, "path": path,
                    "values": map, "offValue": offValue as Any]
        }
    }

    /// Inverse of `persistedJSON`. Returns nil for an unrecognised or malformed payload.
    static func fromPersistedJSON(_ obj: [String: Any]) -> ThinkingWireFormat? {
        guard let raw = obj["kind"] as? String, let kind = Kind(rawValue: raw) else { return nil }
        switch kind {
        case .omitEverything:        return .omitEverything
        case .reasoningEffort:       return .reasoningEffort(offValue: obj["offValue"] as? String)
        case .reasoningEffortNested: return .reasoningEffortNested(offValue: obj["offValue"] as? String)
        case .deepSeekSibling:       return .deepSeekSibling
        case .qwenDual:              return .qwenDual
        case .qwenRootOnly:          return .qwenRootOnly
        case .anthropicThinking:
            let style: AnthropicThinkingStyle = (obj["style"] as? String) == "adaptive" ? .adaptive : .budgetTokens
            return .anthropicThinking(style: style)
        case .geminiBudget:
            return .geminiBudget(floor: obj["floor"] as? Int ?? 128,
                                 canDisable: obj["canDisable"] as? Bool ?? false)
        case .geminiThinkingLevel:   return .geminiThinkingLevel
        case .booleanToggle:
            guard let p = obj["path"] as? String else { return nil }
            return .booleanToggle(path: p)
        case .extraBodyToggle:
            guard let p = obj["path"] as? String else { return nil }
            return .extraBodyToggle(path: p)
        case .customPath:
            guard let p = obj["path"] as? String else { return nil }
            var values: [ThinkingLevel: String] = [:]
            for (k, v) in (obj["values"] as? [String: String] ?? [:]) {
                if let lvl = ThinkingLevel(rawValue: k) { values[lvl] = v }
            }
            return .customPath(path: p, values: values, offValue: obj["offValue"] as? String)
        }
    }

    /// Short human-readable summary, used as the rule row's subtitle in the UI so a user
    /// can tell rules apart without opening each one.
    var displaySummary: String {
        switch self {
        case .omitEverything:
            return AppLocalized("Send no thinking fields")
        case .reasoningEffort(let off):
            return off == nil ? AppLocalized("reasoning_effort · omit when off")
                              : AppLocalized("reasoning_effort · off = \(off!)")
        case .reasoningEffortNested:
            return AppLocalized("reasoning.effort (nested)")
        case .deepSeekSibling:
            return AppLocalized("thinking + reasoning_effort (root siblings)")
        case .qwenDual:
            return AppLocalized("enable_thinking + budget (root & extra_body)")
        case .qwenRootOnly:
            return AppLocalized("enable_thinking only (no budget)")
        case .anthropicThinking(let style):
            return style == .adaptive ? AppLocalized("thinking: adaptive")
                                      : AppLocalized("thinking: budget_tokens")
        case .geminiBudget(let floor, _):
            return AppLocalized("thinkingBudget · floor \(floor)")
        case .geminiThinkingLevel:
            return AppLocalized("thinkingLevel (string)")
        case .booleanToggle(let path):
            return AppLocalized("boolean toggle · \(path)")
        case .extraBodyToggle(let path):
            return AppLocalized("extra_body toggle · \(path)")
        case .customPath(let path, _, _):
            return AppLocalized("custom path · \(path)")
        }
    }
}

extension ThinkingRule.Scope {
    var persistedKind: String {
        switch self {
        case .allModels: return "allModels"
        case .modelPattern: return "modelPattern"
        }
    }

    var persistedPattern: String? {
        switch self {
        case .allModels: return nil
        case .modelPattern(let p): return p
        }
    }

    static func fromPersisted(kind: String, pattern: String?) -> ThinkingRule.Scope {
        if kind == "modelPattern", let p = pattern, !p.isEmpty { return .modelPattern(p) }
        return .allModels
    }

    /// Human-readable, for the rule row.
    var displayText: String {
        switch self {
        case .allModels: return AppLocalized("All models")
        case .modelPattern(let p): return p
        }
    }
}

extension ReasoningEchoPolicy {
    var persistedTiming: String {
        switch timing {
        case .everyTurn: return "everyTurn"
        case .afterToolUseOnly: return "afterToolUseOnly"
        case .never: return "never"
        }
    }

    static func fromPersisted(field: String?, timing: String?) -> ReasoningEchoPolicy? {
        guard let field, !field.isEmpty, let timing else { return nil }
        let t: Timing = switch timing {
        case "everyTurn": .everyTurn
        case "never": .never
        default: .afterToolUseOnly
        }
        return ReasoningEchoPolicy(fieldName: field, timing: t)
    }
}
