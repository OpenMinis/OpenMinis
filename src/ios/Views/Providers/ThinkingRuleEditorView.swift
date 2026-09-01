import SwiftUI

/// Phase 2 §3 — add/edit sheet for one user-authored thinking rule (design §7.5/§7.6).
///
/// Deliberately narrow: it exposes the wire formats a user can meaningfully choose
/// between, each with only the inputs that format actually needs. Formats that exist for
/// built-in rules but are not useful to hand-author (the Gemini/Anthropic families, whose
/// shapes are model-generation dependent) are not offered — a user picking
/// "Gemini budget" for an OpenAI endpoint would only be able to build a broken rule.
struct ThinkingRuleEditorView: View {
    let instanceId: String
    let existing: ThinkingRule?
    /// True when Save must MINT A NEW rule rather than update `existing` in place.
    /// Tapping a built-in seeds the form from it with `createsNew: true`, which is the
    /// "duplicate as a custom rule" affordance (design §7.4) — built-ins are read-only and
    /// must never be mutated by an edit.
    var createsNew: Bool = false
    let onSave: (ThinkingRule) -> Void

    @Environment(\.dismiss) private var dismiss

    /// The formats offered in the picker. Order is "most likely to be useful" first.
    private enum FormatChoice: String, CaseIterable, Identifiable {
        case omitEverything
        case reasoningEffort
        case reasoningEffortNested
        case booleanToggle
        case extraBodyToggle
        case deepSeekSibling
        case qwenDual
        case qwenRootOnly
        case customPath

        var id: String { rawValue }

        var title: String {
            switch self {
            case .omitEverything:       return AppLocalized("Send nothing")
            case .reasoningEffort:      return AppLocalized("reasoning_effort (root)")
            case .reasoningEffortNested:return AppLocalized("reasoning.effort (nested)")
            case .booleanToggle:        return AppLocalized("Boolean toggle")
            case .extraBodyToggle:      return AppLocalized("extra_body toggle")
            case .deepSeekSibling:      return AppLocalized("thinking + reasoning_effort")
            case .qwenDual:             return AppLocalized("enable_thinking + budget")
            case .qwenRootOnly:         return AppLocalized("enable_thinking only")
            case .customPath:           return AppLocalized("Custom field path")
            }
        }

        /// One line explaining when this shape is the right answer, so the choice is not
        /// guesswork. Each references the real situation that motivated it.
        var explanation: String {
            switch self {
            case .omitEverything:
                return AppLocalized("No thinking field at all. Use for endpoints that reject unknown keys outright.")
            case .reasoningEffort:
                return AppLocalized("Standard OpenAI Chat Completions shape.")
            case .reasoningEffortNested:
                return AppLocalized("OpenAI Responses / OpenRouter shape.")
            case .booleanToggle:
                return AppLocalized("A plain on/off switch with no intensity tiers.")
            case .extraBodyToggle:
                return AppLocalized("A switch nested under extra_body, as some gateways require.")
            case .deepSeekSibling:
                return AppLocalized("DeepSeek's shape: a thinking switch and reasoning_effort as sibling root fields.")
            case .qwenDual:
                return AppLocalized("Qwen/DashScope: enable_thinking and a token budget, sent at the root and in extra_body. Use ONLY for DashScope itself — other gateways reject the unknown extra_body field with a 400.")
            case .qwenRootOnly:
                return AppLocalized("Qwen on a third-party gateway: a bare root-level enable_thinking, with no extra_body wrapper and no thinking_budget. Relays commonly reject both of those with a 400, so this is the safe choice for self-hosted vLLM/SGLang and OpenAI-compatible relays serving qwen models.")
            case .customPath:
                return AppLocalized("Advanced: write a value at a dotted field path. Not validated by Minis.")
            }
        }
    }

    @State private var label: String = ""
    @State private var scopeIsAllModels: Bool = true
    @State private var pattern: String = ""
    @State private var choice: FormatChoice = .reasoningEffort
    @State private var offValue: String = "none"
    @State private var sendOffValue: Bool = true
    @State private var togglePath: String = "thinking"
    @State private var extraBodyPath: String = "extra_body.thinking.enabled"
    @State private var customPath: String = ""
    @State private var customHighValue: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Rule name", text: $label)
                        .autocorrectionDisabled()
                }

                Section {
                    Toggle("All models", isOn: $scopeIsAllModels)
                    if !scopeIsAllModels {
                        TextField("Model pattern (e.g. deepseek-v4*)", text: $pattern)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                } header: {
                    Text("Applies to")
                } footer: {
                    if !scopeIsAllModels {
                        // The most likely user error is a pattern that matches nothing,
                        // and its failure is silent (the rule just never fires), so say
                        // plainly how matching works.
                        Text("“*” matches any characters. Matching ignores case and treats “.” and “-” as the same character.")
                    }
                }

                Section {
                    Picker("Format", selection: $choice) {
                        ForEach(FormatChoice.allCases) { c in
                            Text(c.title).tag(c)
                        }
                    }
                    Text(choice.explanation)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    formatFields
                } header: {
                    Text("What to send")
                }

                Section {
                    Text(previewText)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                } header: {
                    Text("Request preview")
                } footer: {
                    Text("The thinking fields this rule adds to a request at the High level.")
                }
            }
            .navigationTitle(existing == nil ? Text("New Rule") : Text("Edit Rule"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(!isValid)
                }
            }
            .onAppear(perform: seedFromExisting)
        }
    }

    @ViewBuilder
    private var formatFields: some View {
        switch choice {
        case .reasoningEffort, .reasoningEffortNested:
            Toggle("Send a value when thinking is off", isOn: $sendOffValue)
            if sendOffValue {
                TextField("Off value (e.g. none, minimal)", text: $offValue)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } else {
                // This is not a cosmetic choice: sending an off tier to a strict-enum
                // backend killed whole requests on MiMo (c5efeb1e).
                Text("The field is omitted entirely when thinking is off.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        case .booleanToggle:
            TextField("Field path", text: $togglePath)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        case .extraBodyToggle:
            TextField("Field path", text: $extraBodyPath)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        case .customPath:
            TextField("Dotted path (e.g. extra.thinking.mode)", text: $customPath)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            TextField("Value at High", text: $customHighValue)
                .autocorrectionDisabled()
        case .omitEverything, .deepSeekSibling, .qwenDual, .qwenRootOnly:
            EmptyView()
        }
    }

    private var isValid: Bool {
        if label.trimmingCharacters(in: .whitespaces).isEmpty { return false }
        if !scopeIsAllModels && pattern.trimmingCharacters(in: .whitespaces).isEmpty { return false }
        if choice == .customPath && customPath.trimmingCharacters(in: .whitespaces).isEmpty { return false }
        return true
    }

    private var wireFormat: ThinkingWireFormat {
        switch choice {
        case .omitEverything:        return .omitEverything
        case .reasoningEffort:       return .reasoningEffort(offValue: sendOffValue ? offValue : nil)
        case .reasoningEffortNested: return .reasoningEffortNested(offValue: sendOffValue ? offValue : nil)
        case .booleanToggle:         return .booleanToggle(path: togglePath)
        case .extraBodyToggle:       return .extraBodyToggle(path: extraBodyPath)
        case .deepSeekSibling:       return .deepSeekSibling
        case .qwenDual:              return .qwenDual
        case .qwenRootOnly:          return .qwenRootOnly
        case .customPath:
            return .customPath(path: customPath,
                               values: [.high: customHighValue],
                               offValue: sendOffValue ? offValue : nil)
        }
    }

    /// Live preview of what this rule emits — the most direct correctness feedback a user
    /// can get without sending a real request (design §7.6).
    private var previewText: String {
        var body: [String: Any] = [:]
        let ctx = ThinkingResolveContext(
            modelId: scopeIsAllModels ? "example-model" : pattern.replacingOccurrences(of: "*", with: "x"),
            supportsReasoning: true,
            declaredEffortValues: nil,
            level: .high,
            maxTokens: 8192,
            isOpenRouter: false,
            usesUnifiedReasoningEffort: false,
            isMistral: false,
            offEffort: sendOffValue ? offValue : nil,
            userRules: [ThinkingRule(kind: .custom, scope: .allModels, wireFormat: wireFormat, label: "preview")]
        )
        _ = ThinkingRuleResolver.apply(to: &body, ctx: ctx)
        if body.isEmpty { return "{}" }
        guard let data = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys, .prettyPrinted]),
              let s = String(data: data, encoding: .utf8) else { return "{}" }
        return s
    }

    private func seedFromExisting() {
        guard let e = existing else { return }
        label = createsNew && !e.isEditable
            ? AppLocalized("Copy of \(e.label)")
            : e.label
        switch e.scope {
        case .allModels: scopeIsAllModels = true
        case .modelPattern(let p): scopeIsAllModels = false; pattern = p
        }
        switch e.wireFormat {
        case .omitEverything: choice = .omitEverything
        case .reasoningEffort(let off):
            choice = .reasoningEffort; sendOffValue = off != nil; offValue = off ?? "none"
        case .reasoningEffortNested(let off):
            choice = .reasoningEffortNested; sendOffValue = off != nil; offValue = off ?? "none"
        case .booleanToggle(let p): choice = .booleanToggle; togglePath = p
        case .extraBodyToggle(let p): choice = .extraBodyToggle; extraBodyPath = p
        case .deepSeekSibling: choice = .deepSeekSibling
        case .qwenDual: choice = .qwenDual
        case .qwenRootOnly: choice = .qwenRootOnly
        case .customPath(let p, let vals, let off):
            choice = .customPath; customPath = p
            customHighValue = vals[.high] ?? ""
            sendOffValue = off != nil; offValue = off ?? "none"
        default: choice = .reasoningEffort
        }
    }

    private func save() {
        let scope: ThinkingRule.Scope = scopeIsAllModels ? .allModels : .modelPattern(pattern)
        let rule = ThinkingRule(
            kind: .custom,
            scope: scope,
            wireFormat: wireFormat,
            reasoningEcho: existing?.reasoningEcho,
            label: label,
            // Preserve identity only when editing an existing CUSTOM rule, so the row
            // updates in place. A duplicate (or a built-in seed) must get a fresh id.
            id: createsNew ? nil : existing?.id
        )
        onSave(rule)
        dismiss()
    }
}
